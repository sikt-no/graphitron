---
title: "Error-handling parity: emit per-fetcher error channels from `@error`"
status: Backlog
bucket: architecture
priority: 7
---

# Error-handling parity: emit per-fetcher error channels from `@error`

The classifier already resolves `@error` types into `GraphitronType.ErrorType` with a
`List<ErrorType.Handler>` (see `TypeBuilder.buildErrorType` at
`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java:485`).
Nothing emits the runtime exception-to-GraphQL-error mapping that legacy provides via
`graphitron-common/src/main/java/no/sikt/graphql/exception/`. As of today, `@error` is a parse
target with no consumer; mutation payload error fields and top-level typed-error responses are
absent. `code-generation-triggers.md:109` records the gap as "No generation (error mapping config)".

This item scopes the emission side and the model refactor that makes it tractable. Pairs with
[`checked-exceptions-typed-errors.md`](checked-exceptions-typed-errors.md) (Backlog) and depends on
[`mutations.md`](mutations.md) (Spec) for the mutation-payload carrier.

---

## Legacy reference points

Mapping the rewrite work back to the parts of legacy worth preserving (and the parts to drop):

- `SchemaBasedErrorStrategy` + `CustomExecutionStrategy`
  (`graphitron-common/src/main/java/no/sikt/graphql/exception/`): a custom
  `AsyncExecutionStrategy` overriding `handleFetchingException` to route schema-mapped
  exceptions into the mutation payload's `errors` field, falling through to a top-level handler
  otherwise.
- `GenericExceptionMatcher` (className + cause-chain walk + optional message substring) and
  `DataAccessMatcher` (adds `sqlState` and `errorCode`, unwraps `DataAccessException`'s nested
  `SQLException`). Both ship in `graphitron-common`; their matching logic is the runtime-side
  baseline the rewrite must reproduce.
- Two emitted classes: `GeneratedExceptionStrategyConfiguration` (per-operation maps:
  `Map<Class<? extends Throwable>, Set<String>>` and `Map<String, PayloadCreator>`) and
  `GeneratedExceptionToErrorMappingProvider` (matcher instances paired with error-builder
  lambdas). Both produced by `ExceptionStrategyConfigurationGenerator` /
  `ExceptionToErrorMappingProviderGenerator` in `graphitron-codegen-parent/.../generate/`.
- `ExceptionHandlingBuilder` is the consumer-facing fluent builder; it is wired manually into
  `GraphQLBuilder.executionStrategy(...)`. There is no auto-wiring.
- The javax/jakarta split lives only in `RecordValidator` (separate
  `graphitron-error-handling-{javax,jakarta}` modules differing by one import line). The core
  exception-handling code in `graphitron-common` has zero `javax.*` imports.

Preserve: dual-layer routing (schema-mapped vs top-level fallback); the three matcher inputs
(class identity walking the cause chain, sqlState, message substring); per-operation scope.
Drop: the global per-operation runtime maps; the custom `ExecutionStrategy`; the manual
consumer wiring; the javax module duplicate.

---

## Direction

### 1. Sealed `Handler` taxonomy

Today `ErrorType.Handler` is the enum-with-shared-fields shape that
[`rewrite-design-principles.md:17-21`](../docs/rewrite-design-principles.md#sealed-hierarchies-over-enums-for-typed-information)
warns against: a single record with a kind field plus mostly-nullable strings.

```java
record Handler(
    ErrorHandlerType handlerType,  // DATABASE | GENERIC
    String className, String code, String sqlState, String matches, String description
) {}
```

Lift to a sealed interface so each variant carries exactly the fields it uses, and the emitter
dispatches via exhaustive switch:

```java
sealed interface Handler permits DatabaseHandler, GenericHandler {
    Optional<String> matches();
    Optional<String> description();
}

record DatabaseHandler(
    ClassName exceptionClass,    // resolved at classify time; defaults to DataAccessException per the directive grammar
    Optional<String> sqlState,   // optional per directive: a `[{handler: DATABASE}]` entry with no sqlState/code is valid (databaseHandledError fixture)
    Optional<String> code,
    Optional<String> matches,
    Optional<String> description
) implements Handler {}

record GenericHandler(
    ClassName exceptionClass,    // resolved at classify time, not a String
    Optional<String> matches,
    Optional<String> description
) implements Handler {}
```

`exceptionClass` resolves the FQN to a `ClassName` at classify time. For `GenericHandler` the
directive requires `className`. For `DatabaseHandler` it defaults to
`org.jooq.exception.DataAccessException` per the directive's documented grammar
(`directives.graphqls`); the rewrite continues to default it to that FQN to preserve schema
parity, even though §3's runtime matcher no longer requires the exception to actually be a
`DataAccessException` (see "Behaviour change vs legacy" below). If the class cannot be resolved
on the classifier classpath, the parent `ErrorType` becomes `UnclassifiedType` with a descriptive
reason (mirrors how `@record(record: {className: ...})` already validates Java reflection at
build time).

`ErrorHandlerType` then collapses to a private discriminator, or disappears entirely.

#### Field structural requirements on `@error` types

The current classifier (`FieldBuilder.classifyChildFieldOnErrorType`,
`FieldBuilder.java:1525`) restricts fields on `@error` types to scalar or enum. Tighten this to
match the legacy `Error` GraphQL-interface contract that every legacy fixture under
`graphitron-codegen-parent/graphitron-java-codegen/src/test/resources/exceptions/` satisfies:

- A non-null `path` field of type `[String!]!`.
- A non-null `message` field of type `String!`.

The validator rejects an `@error` type that lacks either field with a clear message; emission
in §3 then assumes both are present, matching the load-bearing-classifier-check pattern in
[`rewrite-design-principles.md`](../docs/rewrite-design-principles.md#classifier-guarantees-shape-emitter-assumptions).

### 2. Resolve operation-to-error wiring at classify time

Legacy builds `Map<String, PayloadCreator>` at runtime construction because the legacy generator
had no per-field resolution layer. The rewrite already classifies mutation and service fields.
Add a sub-taxonomy on the field model:

```java
record ErrorChannel(
    String errorsFieldName,                  // the "errors" field on the payload
    List<ErrorTypeRef> mappedErrorTypes,     // union members or list-element type
    PayloadShape shape                       // PayloadObject | RootErrorsArrayOnly
) {}
```

Carry it as `Optional<ErrorChannel>` on `MutationField.DmlTableField` (the new sealed supertype
introduced by [`mutations.md`](mutations.md) Phase 1a) and on
`MutationField.MutationServiceTableField` / `MutationServiceRecordField`. Same pattern as
`nodeIdMeta` in `mutations.md:197-208`: classifier resolves once, emitter dispatches over a
settled shape. Consult [`rewrite-design-principles.md:43-47`](../docs/rewrite-design-principles.md#sub-taxonomies-for-resolution-outcomes)
for the sub-taxonomy contract.

The classifier walks the field's payload return type, identifies the `errors` field by its
return type's relationship to declared `@error` types (list-of-`@error`, or union whose members
are all `@error`), and resolves which `Handler`s apply. No global map; each field carries its
own list. Query fields can also opt in: the legacy code applied `@error` to any operation whose
return type carried an `errors` field, not just mutations.

**Channel-detection rules (settled at classify time):**

1. The payload type must be a `@table` or `@record` object whose field set includes exactly
   one field whose return type is either a list `[E]` or a list-of-union `[U]` where `E` is an
   `@error` type or `U` is a union all of whose members are `@error` types. That field's name is
   `errorsFieldName`; its element types populate `mappedErrorTypes`.
2. A payload with no such field has no `ErrorChannel`. Throwing inside that fetcher takes the
   top-level path (§3).
3. The detection ignores the field's name; legacy convention is `errors:` but the rewrite keys
   off the structural relationship to `@error` types, not a hardcoded field name. This matches
   how mutations.md keys off return-type shape rather than directive presence.

**`@error` type's Java class contract (developer-supplied, not generated).** Legacy never
generates a Java class for an `@error` GraphQL type; the developer supplies it (the fixture
`graphitron-codegen-parent/.../exceptions/provider/default/expected/GeneratedExceptionToErrorMappingProvider.java`
imports `fake.graphql.example.model.SomeError` from the developer codebase, and there is no
matching `SomeError*Generator` under `generators/`). The emitted runtime calls
`new SomeError(path, msg)` with `path: List<String>` from `DataFetchingEnvironment.getExecutionStepInfo().getPath().toList()` and
`msg: String` from the resolved `description` (or the exception's message when `description`
is absent).

The rewrite preserves this contract:

- Each `@error` type's Java class is **developer-supplied**, located under the package the
  rest of the schema's developer-supplied types live in (the same lookup path the existing
  `@record` flow uses).
- Required constructor: `(List<String> path, String message)`. Mandated at classify time; a
  missing or wrong-shape constructor produces an `UnclassifiedType` with a descriptive reason,
  same pattern as `@record` reflection failure (`TypeBuilder.java:457-459`).
- The class is reflected on the classifier classpath at build time, the same property that
  enables `@record` reflection, with an identical fail-mode if the classpath is missing the
  class.

Spec-phase open question: should the rewrite generate the `@error` Java class instead, given
that its shape is fully determined by the SDL? Trade-off: generation removes the developer
boilerplate, but breaks symmetry with `@record` (where the class is always developer-supplied)
and conflicts with the existing `code-generation-triggers.md:109` line "No generation (error
mapping config)". Default for now: keep developer-supplied to match legacy and `@record`.

### 3. Drop the custom `ExecutionStrategy`. Wrap try/catch at the fetcher.

Legacy needs `CustomExecutionStrategy` because it intercepts graphql-java's per-fetcher
exception path before the engine surfaces it. The rewrite emits per-field bodies via
`FetcherRegistrationsEmitter` and has a unified `Graphitron.buildSchema(Consumer<...>)` facade.
The cleaner emission shape:

```java
public static Object createFilm(DataFetchingEnvironment env) {
    try {
        // existing INSERT / UPDATE / service body
    } catch (Throwable t) {
        return ErrorRouter.dispatch(t, MAPPINGS, env, FilmPayload::new);
    }
}
```

`MAPPINGS` is a static `final` array literal emitted into the same `*Fetchers` class, populated
from the field's resolved `ErrorChannel`. `ErrorRouter.dispatch` lives in the rewrite's
runtime-support module, walks the cause chain with the legacy matcher logic, and returns either
the populated payload (schema-mapped) or rethrows (top-level fallback). Top-level handling is a
vanilla graphql-java `DataFetcherExceptionHandler` installed by `Graphitron.buildSchema`.
Consumers plug in nothing.

#### Concrete dispatch signature

```java
public final class ErrorRouter {
    public static <P> P dispatch(
            Throwable thrown,
            Mapping[] mappings,                          // emitted MAPPINGS array
            DataFetchingEnvironment env,                 // for path/source-location
            Function<List<Object>, P> payloadFactory     // (errors) -> payload
    ) throws Throwable { ... }

    public sealed interface Mapping permits DatabaseMapping, GenericMapping {
        Object build(List<String> path, String message);  // constructs the @error instance
    }
}
```

The emitted call site supplies a payload factory rather than a no-arg constructor reference;
this mirrors the legacy `PayloadCreator` shape (`SchemaBasedErrorStrategy.java:139-145`) but
binds the errors list explicitly rather than via a setter. For payloads whose `errors` field is
their only field (the `RootErrorsArrayOnly` shape in §2), the factory is `errors -> errors`
itself, returned untouched.

`path` resolves to `env.getExecutionStepInfo().getPath().toList()`; `message` resolves to the
handler's `description` if present, otherwise the matched exception's `getMessage()` (preserves
legacy fallback per `ExceptionToErrorMappingProviderGenerator.java:230-233`).

#### CompletionException unwrap and async fetcher path

Legacy `SchemaBasedErrorStrategy.java:84-88` does a one-level `CompletionException` unwrap
before matching. The rewrite must do the same, with one improvement: walk the entire cause
chain (Stream-based, like `GenericExceptionMatcher.streamCauses`) so a `CompletionException`
wrapping an `ExecutionException` wrapping the real exception still routes correctly. This is a
pure improvement over legacy's single-level unwrap and costs nothing.

The async fetcher path matters here: every `QueryServiceTableField` and similar variant
returns a `CompletableFuture` today (`FetcherRegistrationsEmitter` wraps DataLoader bodies in
`CompletableFuture.supplyAsync`). The try/catch wrapper in §3 only catches synchronous throws.
For async fetchers, the wrapper instead does:

```java
return future.exceptionally(t -> ErrorRouter.dispatch(t, MAPPINGS, env, FilmPayload::new));
```

The classifier sub-taxonomy (§2) exposes whether the field is sync or async; the emitter forks
on that. Both forks call into the same `ErrorRouter.dispatch`, so the runtime path is uniform.

#### Dispatch ordering: source order, first match wins

When a channel resolves multiple `@error` types, and each carries multiple `Handler`s, the
flattened mapping list preserves source order: `@error` type declaration order in SDL, then
handler-array order within each type. `ErrorRouter.dispatch` iterates the flattened list and
returns on the first match (`SchemaErrorMapper.java:55-58`'s
`mappings.stream().filter().findFirst()` semantics). This matches legacy behaviour and is the
deterministic, easy-to-reason-about contract; the alternative (most-specific-class-first, or
priority numbers) hides ordering decisions in handler metadata and makes schema review harder.

The ordering is documented at the directive level: schema authors who want a specific
exception class to win against a broader one (e.g. `SQLException` before `Throwable`, or a
specific FK-violation `sqlState` before a generic `DataAccessException`) put the more-specific
handler first.

A classifier check rejects a channel that contains two handlers with **identical
match-criteria** (same `exceptionClass`, same `sqlState`, same `code`, same `matches`) on
different `@error` types: the second is unreachable and almost certainly an author mistake. A
duplicate within a single `@error` type's `handlers` array is similarly rejected. This closes
the legacy gap where duplicates were silently allowed
(`ExceptionStrategyConfigurationGenerator.java:107-117`).

#### Top-level handler

The vanilla `DataFetcherExceptionHandler` installed by `Graphitron.buildSchema` is a
straightforward implementation: log at WARN, build a `GraphqlErrorBuilder` exception with the
exception message and source location from the `DataFetchingEnvironment`. This matches
graphql-java's `SimpleDataFetcherExceptionHandler`, which is what
`AsyncExecutionStrategy.handleFetchingException` falls through to in legacy.

Consumer override path: the existing `Consumer<GraphQLSchema.Builder>` slot on
`Graphitron.buildSchema` doesn't reach the `DataFetcherExceptionHandler` (which is set on
`AsyncExecutionStrategy`, not `GraphQLSchema.Builder`). The plan adds a sibling slot, either
`Graphitron.buildSchema(Consumer<Builder>, DataFetcherExceptionHandler)` or a builder-style
`GraphitronOptions` carrier; this is covered in the open-questions list.

#### Behaviour change vs legacy

The rewrite drops the Spring `DataAccessException` requirement on the runtime side: a
`DatabaseHandler` matches any cause-chain entry that exposes `getSQLState()` / `getErrorCode()`
(i.e. any `SQLException`), not only one wrapped in a Spring `DataAccessException`. Two
implications:

- A non-Spring app (which is the rewrite's primary target) gets database error mapping for
  free; legacy required `spring-jdbc` on the consumer classpath.
- A Spring app where a `DataAccessException` wraps a `SQLException` continues to match because
  the chain walk reaches the inner `SQLException` regardless. The wrapping no longer matters.

The `className` field on `DatabaseHandler` continues to default to
`org.jooq.exception.DataAccessException` for SDL-parity, but the runtime match is structural
(presence of `getSQLState()`) rather than nominal. This is documented in the non-goals section
("Reverse-engineering DataAccessException") but the runtime semantic shift is worth flagging
explicitly here so consumers writing schema for the rewrite understand the looser match.

### 4. `@service` checked exceptions fold into the same pipe

[`checked-exceptions-typed-errors.md`](checked-exceptions-typed-errors.md) becomes a thin
classifier change: `ServiceCatalog.reflectServiceMethod` reads `getExceptionTypes()`; the
classifier checks each declared exception against the surrounding field's `ErrorChannel`
mapped types; the emitter declares `throws` on the generated method (or wraps a checked-narrow
catch). The runtime path is the same `ErrorRouter.dispatch` call. No second runtime mechanism.

A declared exception with no matching `@error` becomes a build-time error, surfaced through
`validateUnclassifiedField` per
[`rewrite-design-principles.md:65-69`](../docs/rewrite-design-principles.md#validator-mirrors-classifier-invariants).
That replaces the legacy "silently swallowed at runtime" behaviour with a
[load-bearing classifier check](../docs/rewrite-design-principles.md#classifier-guarantees-shape-emitter-assumptions),
annotated with `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` so the audit test
catches any future emitter that grows to consume `ErrorChannel` without the corresponding
classifier rejection.

#### Narrow-or-wide match rule

A declared checked exception `T` matches an `ErrorChannel` `Handler` when `T` is assignable to
the handler's `exceptionClass` (i.e. the handler is **wider or equal**, not narrower). This is
the natural runtime semantic: a `DatabaseHandler(exceptionClass = DataAccessException)`
catches a method declaring `throws SQLException` because at runtime any `SQLException` cause
in the chain matches via `isInstance`, but the inverse (a handler narrower than the declared
exception) would let the runtime path slip past. Concretely: a method `throws Throwable` with
no `Throwable`-wide handler in the channel is a build-time error.

#### Special cases

- **`InterruptedException`, `IOException` from non-database I/O.** These are usually
  infrastructure errors that should reach the top-level handler, not a typed `@error`. The
  rule above forces the schema to either (a) declare a wide handler (e.g. `Throwable`-wide
  `GenericHandler`), which is usually wrong because it leaks infrastructure detail to clients,
  or (b) wrap them in a domain exception in the service implementation. Document this in the
  spec; it's not
  load-bearing for the generator but is the prevailing pattern in real services.
- **`IllegalArgumentException`.** Legacy
  (`SchemaBasedErrorStrategy.java:58-73`) treats `IllegalArgumentException` as a special arm,
  separate from the schema-mapped strategy. The rewrite folds it into the same pipe: an
  `IllegalArgumentException` matches whichever channel includes a `GenericHandler` against it.
  This is a behaviour simplification: legacy's separate arm exists only because its dispatch
  was per-exception-type, not per-channel.
- **Unchecked `RuntimeException` thrown from non-`@service` code.** The same dispatch applies;
  the runtime cannot tell the difference between a checked and unchecked throw at the
  fetcher's boundary. The classifier check in this section gates only `@service` /
  `@tableMethod` declared exceptions; unchecked exceptions thrown anywhere else reach
  `ErrorRouter.dispatch` via the catch arm in §3 and either match a channel handler or fall
  through to the top-level handler.

### 5. Jakarta-only validation

The `RecordValidator` two-module split exists only to bridge `javax.validation` vs
`jakarta.validation` imports. Rewrite consumers are jakarta-only by assumption; the
`graphitron-rewrite-runtime` (or wherever `ErrorRouter` and the validator land) imports
`jakarta.validation-api` directly. One file, one dependency, no `<profile>` dance. This is a
pom hygiene win, not architectural; flagged here only so the parity discussion is complete.

#### `ValidationViolationGraphQLException` path

Legacy `SchemaBasedErrorStrategy` has a dedicated `handleValidationException` arm
(`SchemaBasedErrorStrategy.java:58-65`) that intercepts `ValidationViolationGraphQLException`
(an `AbortExecutionException` carrying a list of `GraphQLError`s pre-built by `RecordValidator`)
and emits the carried errors verbatim. The rewrite preserves this arm but folds it into
`ErrorRouter.dispatch` rather than a separate exception-strategy method:

- The router unwraps a top-level `ValidationViolationGraphQLException` and short-circuits to
  the carried `List<GraphQLError>` via the same payload-factory path as a schema-mapped
  match. No matcher is consulted.
- This requires `ValidationViolationGraphQLException` (or its rewrite equivalent) to live in
  the same runtime module as `ErrorRouter`. Since the validator class itself lands there per
  the section above, this is automatic.

The developer-facing flow stays identical: code calls `recordValidator.validate(...)`, which
throws `ValidationViolationGraphQLException` if violations exist, which propagates to
`ErrorRouter.dispatch`, which routes the carried errors into the payload's `errors` field. No
auto-wiring; the trigger is still developer-invoked.

---

## Relationship to neighbouring items

- **[`mutations.md`](mutations.md)** (Spec, priority 9) is the carrier. Every mutation phase
  emits a fetcher whose body is a candidate for the try/catch wrapper. Realistic options:
  (a) land mutations Phases 2-6 first, then layer error-channel emission as a sweep across
  the new emitters; (b) absorb error-channel emission as one extra phase inside `mutations.md`
  before that plan moves to Done. Picking the order is part of moving this item from Backlog
  to Spec; the work itself is independent of which order.
- **[`checked-exceptions-typed-errors.md`](checked-exceptions-typed-errors.md)** (Backlog,
  priority 8) is subsumed by §4 above. When this item moves to Spec, fold that one in or
  retire it depending on how the spec partitions.

---

## Legacy fixture coverage (scope anchor)

The legacy generator's behaviour is locked by ~25 fixtures under
`graphitron-codegen-parent/graphitron-java-codegen/src/test/resources/exceptions/`. The rewrite
spec must commit to which scenarios are reproduced; the table below partitions them into
in-scope / out-of-scope, so the spec phase has a concrete checklist.

**In-scope (Phase 1 of this item):**

- `default/`: single `@error` type with one `GENERIC` handler.
- `databaseHandledError/`: `[{handler: DATABASE}]` with no sqlState/code (defaults to
  `DataAccessException`).
- `databaseWithSqlState/`, `databaseWithCode/`, `databaseWithMatches/`: DATABASE handler with
  each discriminator in turn.
- `bothGenericAndDatabase/`, `bothGenericAndDatabaseInMultipleErrorsForOneResponse/`: mixed
  handlers on the same / on different `@error` types in one channel.
- `multipleHandlers/`, `unionMultipleHandlers/`: multiple handlers per `@error` type.
- `multiple/`, `multipleInOneResponse/`: multiple `@error` types per channel (list shape).
- `union/`, `unionDatabase/`, `unionMultipleDatabaseHandlers/`, `unionMultipleErrorsWithMultipleMixedHandlers/`,
  `unionMultipleMixedHandlers/`, `unionWithUnhandledError/`: union shape for `errors` field.
- `withDescription/`, `withMatches/`: optional handler-fields populated.
- `query/`: `@error` channel on a Query field, not just Mutation.
- `service/`: `@error` on a `@service`-backed mutation.
- `onlyOneHandled/`: partial schema coverage (some `@error` types in the union have no matching
  handler for the thrown exception).

**Out-of-scope or rejected (commit to in spec):**

- `multipleDatabaseHandlers/`: two `DATABASE` handlers with different `code` values on the same
  `@error` type. The §3 ordering rule requires this to be allowed (different match-criteria);
  reproduce the legacy semantic.
- `unionMultipleDatabaseHandlers/` (when criteria collide): duplicate match-criteria across
  `@error` types is rejected by the §3 classifier check; legacy permitted it silently.
  Document the rewrite as stricter and update the rejected fixture's expected output to a
  classifier error.

The pipeline-test set in `graphitron-test` covers each row above with one execution-test
asserting the actual on-the-wire error shape; the per-row fixtures live in `graphitron-test`'s
SDL alongside the mutation fixtures (mutations.md:457-465 closure).

---

## Non-goals

- **Custom `ExecutionStrategy` for non-error reasons.** This item commits to graphql-java's
  default `AsyncExecutionStrategy` plus a `DataFetcherExceptionHandler`. Consumers needing a
  custom strategy for unrelated reasons (custom subscription dispatch, alternative async
  scheduler) plug it in via the `Consumer<GraphQLSchema.Builder>` slot on `Graphitron.buildSchema`.
- **Bean-validation triggering.** `RecordValidator` integration with `@Valid`-style
  bean-validation triggering on input types is out of scope. The current `ValidationViolationGraphQLException`
  path in legacy converts violations to GraphQL errors after a developer-written caller invokes
  the validator; the rewrite preserves the same shape (developer-driven; no auto-wire) when the
  validator class lands.
- **Reverse-engineering `DataAccessException`.** The matcher unwraps `DataAccessException` to
  reach the nested `SQLException` because legacy depends on `spring-jdbc`. The rewrite runtime
  has no spring dependency; the matcher uses reflective `getCause()` walking the chain instead,
  which is what the legacy `streamCauses` already does at
  `GenericExceptionMatcher.java:streamCauses`. The DB-error variant of the matcher checks
  `SQLState`/`ErrorCode` directly on any `SQLException` it finds.
- **A consumer-facing `ExceptionHandlingBuilder` analogue.** Auto-wiring is the goal. If a
  consumer needs to override the top-level handler, the slot is the same
  `Consumer<GraphQLSchema.Builder>` parameter on `Graphitron.buildSchema` (today's facade
  already exposes it for federation, custom scalars, and tenant-scoped `DSLContext`). See the
  open question below: the existing slot doesn't reach `DataFetcherExceptionHandler`, so a
  small additional slot is needed.
- **Subscription error paths.** Legacy has no subscription-specific exception handling, and
  the rewrite does not generate subscription fetchers (`FieldBuilder.classifyRootField` rejects
  `Subscription` with a `DEFERRED` rejection at `FieldBuilder.java:1544-1545`). Subscription
  error handling is deferred until subscriptions land as a feature; that future plan owns
  subscription-specific routing.
- **Batch-loader error handling.** Legacy's `DataLoaderMapper` propagates exceptions opaquely;
  the rewrite's split-query DataLoader emission inherits the same property. Routing inside a
  batch's per-key result map (where some keys fail and others succeed) is a separate
  multi-key-batch concern, not error-channel parity. Out of scope here.
- **Federation entity-resolver errors.** `_entities` resolver errors flow through the same
  `DataFetcherExceptionHandler` as any other field; per-`@key` typed-error mapping for
  federated subgraphs is a federation concern owned by the federation roadmap item, not this
  one.
- **Instrumentation / metrics hooks on error paths.** Counters, log forwarding, OTel spans on
  the error path are a runtime-observability concern. The rewrite's runtime path stays
  graphql-java-vanilla; consumers wire instrumentation via the
  `Consumer<GraphQLSchema.Builder>` slot or graphql-java's `Instrumentation` API.
- **Transaction rollback semantics.** Whether a thrown exception rolls back a JDBC transaction
  is a `DSLContext` / connection-management concern, owned by the consumer's transaction
  strategy (`@Transactional`, manual `dsl.transaction(...)`, etc.). The rewrite's fetcher
  emission does not change transaction shape; an error inside a fetcher propagates through
  whatever transaction wrapper the consumer set up.

---

## Open questions for the Spec phase

- **Where does `ErrorRouter` and the matcher code live?** Options: (a) a new
  `graphitron-rewrite-runtime` module that emitted code depends on at runtime; (b) emit the
  matcher classes alongside the fetchers, no runtime jar. (a) keeps the emitter tight; (b)
  preserves the rewrite's "no runtime support library" property documented in
  [`rewrite-design-principles.md:111-117`](../docs/rewrite-design-principles.md#rewrite-builds-independently-of-legacy-graphitron-modules).
  Decide before Spec → Ready. The author's lean is (b): `ErrorRouter` is ~150 lines of pure
  cause-chain walking and matcher application; emitting it as a single per-build static helper
  class (alongside the existing `*Fetchers`) keeps the no-runtime-jar property and avoids
  versioning the runtime jar against the generator.
- **Top-level `DataFetcherExceptionHandler` slot.** The existing
  `Consumer<GraphQLSchema.Builder>` slot on `Graphitron.buildSchema` is invoked after schema
  build but does not reach `AsyncExecutionStrategy.handleFetchingException`'s
  `DataFetcherExceptionHandler`. Spec phase decides between: (a) overload
  `Graphitron.buildSchema(Consumer<Builder>, DataFetcherExceptionHandler)`; (b) introduce a
  `GraphitronOptions` carrier object that bundles the customizer, the exception handler, and
  any future global slots; (c) ship with no override slot and document that consumers wanting
  a custom top-level handler use graphql-java's `GraphQL.Builder` directly. (b) is the safest
  shape long-term but is the largest API surface change.
- **`@error` Java class generation vs developer-supplied.** Today's contract is
  developer-supplied (matches legacy and `@record`). Generation is feasible (the SDL fully
  determines the Java shape) but breaks the `@record`-symmetric story and changes the
  `code-generation-triggers.md:109` line. Default position: keep developer-supplied. Revisit
  if a consumer survey shows the boilerplate is meaningful.
- **`@error` on query fields.** Legacy applies error mapping to any operation whose return
  type carries an `errors` field. Confirm against production schemas whether query-side error
  channels are in use; if zero usage, narrow scope to mutation fields and document the cut.
- **Generation-efficiency: matcher dedup across fetchers.** Legacy's
  `ExceptionToErrorMappingProviderGenerator.java:189-193` dedupes matcher *variables* across
  operations sharing the same `@error` type. The rewrite's per-fetcher static `MAPPINGS` array
  duplicates handler instances across every fetcher in a channel that shares the same
  `@error` types. For a schema with N fetchers each mapping the same 5 `@error` types, that's
  5N `Mapping` instances vs legacy's 5. Decide whether to introduce a per-`*Fetchers`-class
  static cache, or accept the duplication (mitigation: handler instances are tiny, just a
  `ClassName` plus 3 optional Strings, and JVM constant-folding handles the array literals
  well).
- **Fixture coverage.** The current `graphitron-test` schema has no `@error` types. Spec phase
  needs to add at least one `@error` fixture exercising both `DatabaseHandler` (sqlState match
  via a Sakila constraint violation; e.g. `23503` foreign-key on the `film_actor` link table)
  and `GenericHandler` (developer-thrown business exception). The mutation-bodies fixture gap
  closure (`mutations.md:457-465`) is the natural place to extend. The full fixture list to
  reproduce is in the "Legacy fixture coverage" section above.
- **Phase ordering with `mutations.md`.** The author leans toward option (a) in the
  "Relationship to neighbouring items" section: land mutations Phases 2-6 first, then layer
  error-channel emission as a sweep across the new emitters. Rationale: every mutation phase's
  emitter touches the same fetcher body, and adding a second axis of variation (error
  wrapping) inside each phase would interleave concerns. Sweeping the error wrapper across
  finished emitters in a single follow-up commit keeps each phase's diff focused.
