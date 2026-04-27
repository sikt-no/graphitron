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
dispatches via exhaustive switch. The variants are split by the **discriminator** the matcher
keys off (not by vendor: vendor-named variants like `PostgresHandler` / `OracleHandler` bake
brittle product names that don't fit MySQL/CockroachDB/Yugabyte cleanly):

```java
sealed interface Handler permits ExceptionHandler, SqlStateHandler, VendorCodeHandler {
    Optional<String> matches();
    Optional<String> description();
}

record ExceptionHandler(
    ClassName exceptionClass,        // resolved at classify time
    Optional<String> matches,
    Optional<String> description
) implements Handler {}

record SqlStateHandler(
    String sqlState,                 // required (e.g. "23503"); Postgres / SQL-standard discriminator
    Optional<String> matches,
    Optional<String> description
) implements Handler {}

record VendorCodeHandler(
    String vendorCode,               // required (e.g. "1" for ORA-00001); Oracle / vendor-specific discriminator
    Optional<String> matches,
    Optional<String> description
) implements Handler {}
```

`SqlStateHandler` and `VendorCodeHandler` carry no `exceptionClass` field. They implicitly match
any `SQLException` reachable in the cause chain (the rewrite's structural-match rule from §3's
"Behaviour change vs legacy"). The legacy DATABASE handler's `className` defaulted to
`DataAccessException` and was always defaulted in practice (no legacy fixture sets it
explicitly), so dropping the field on these two variants sheds an unused string. Schema authors
needing exception-class-narrowed SQL-state matching combine an `ExceptionHandler` and a
`SqlStateHandler` as two separate channel entries; the dispatch ordering rule in §3 makes the
intersection deterministic.

`ExceptionHandler.exceptionClass` is required and validated at classify time. If the class
cannot be resolved on the classifier classpath, the parent `ErrorType` becomes
`UnclassifiedType` with a descriptive reason (mirrors how `@record(record: {className: ...})`
already validates Java reflection at build time).

`ErrorHandlerType` (the legacy `DATABASE | GENERIC` enum at
`no.sikt.graphitron.rewrite.model.ErrorHandlerType`) and the legacy-shape `Handler` record at
`GraphitronType.java:202-209` are deleted in the same edit that lands the sealed hierarchy.
Validators and any in-tree references update in lockstep; otherwise the build breaks before
the new shape compiles.

#### Parse-time lift from the legacy SDL grammar

The SDL grammar stays byte-identical to legacy
(`directive @error(handlers: [ErrorHandler!]!)` with `ErrorHandler { handler, className, code,
sqlState, matches, description }`); existing schemas keep parsing. `TypeBuilder.parseErrorHandler`
lifts each entry into one of the three model variants by inspecting which fields are present,
and rejects combinations that are ambiguous or vendor-conflicting:

| SDL entry                                              | Model variant                                |
|--------------------------------------------------------|----------------------------------------------|
| `{handler: GENERIC, className: "X"}`                   | `ExceptionHandler(X, ...)`                   |
| `{handler: DATABASE, sqlState: "23503"}`               | `SqlStateHandler("23503", ...)`              |
| `{handler: DATABASE, code: "1"}`                       | `VendorCodeHandler("1", ...)`                |
| `{handler: DATABASE}` (no sqlState, no code)           | `ExceptionHandler(SQLException, ...)`        |

Reject (each surfaces as `UnclassifiedType` with a descriptive reason, so the validator reports
it at build time):

1. **`GENERIC` without `className`**: the directive doc already requires it for GENERIC, but
   the legacy generator silently produced an unmatched mapping. Reject explicitly.
2. **`GENERIC` with `sqlState` or `code`**: these fields are not consulted on `GENERIC` in
   legacy; the schema almost certainly meant DATABASE. Reject with a hint.
3. **`DATABASE` with both `sqlState` and `code`**: vendor-conflicting. Postgres populates
   `getSQLState()` and leaves `getErrorCode()` at 0; Oracle populates `getErrorCode()` and
   leaves `getSQLState()` mostly stubbed. A handler ANDing both is unreachable in practice
   (legacy `DataAccessMatcher.java:28-30` ANDs them silently). Reject and instruct the author
   to split into two entries: a `SqlStateHandler` and a `VendorCodeHandler`.
4. **`DATABASE` with a non-default `className`**: the rewrite's runtime no longer matches on
   class identity for the SQL variants (§3 behaviour change), so a non-default `className`
   has no effect and is misleading. Reject with a hint pointing at `GENERIC` for
   class-narrowed matching.
5. **Duplicate match-criteria across handlers in the same channel**: covered by the §3
   classifier check; listed here so the parse-time rules are exhaustive.

The DATABASE-with-no-discriminator case (last row of the lift table above) deserves a note:
legacy's default `className` was `DataAccessException`, but the rewrite's runtime walks the
cause chain for any `SQLException` (§3 behaviour change). The lift to
`ExceptionHandler(SQLException)` makes the rewrite's actual semantic visible in the model
rather than hidden in the runtime matcher. Schemas that relied on legacy's
"DataAccessException-only" nominal match (rare; Spring-
specific) get a documented behaviour shift, not a silent one.

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
This is a [load-bearing classifier check](../docs/rewrite-design-principles.md#classifier-guarantees-shape-emitter-assumptions):
the producer (the §1 structural check on `@error` field shape) wears
`@LoadBearingClassifierCheck(key = "error-type.path-message-fields", ...)` and the consumer
(§3's `ErrorRouter.dispatch` payload-factory call site, which constructs
`new SomeError(path, message)` against the developer-supplied two-arg constructor) wears
`@DependsOnClassifierCheck` with the same key.

### 2. Resolve operation-to-error wiring at classify time

Legacy builds `Map<String, PayloadCreator>` at runtime construction because the legacy generator
had no per-field resolution layer. The rewrite already classifies mutation and service fields.
Add a sub-taxonomy on the field model:

```java
record ErrorChannel(
    String errorsFieldName,                  // the "errors" field on the payload
    List<ErrorTypeRef> mappedErrorTypes,     // union members or list-element type
    PayloadShape shape
) {}

sealed interface PayloadShape
        permits PayloadShape.PayloadObject, PayloadShape.RootErrorsArrayOnly {

    /** The fetcher returns a payload object whose `errorsFieldName` field is the errors list. */
    record PayloadObject(ClassName payloadClass) implements PayloadShape {}

    /** The fetcher returns the errors list directly (no wrapping payload). */
    record RootErrorsArrayOnly() implements PayloadShape {}
}
```

`PayloadShape` is a sub-taxonomy in the sense of
[`rewrite-design-principles.md:43-47`](../docs/rewrite-design-principles.md#sub-taxonomies-for-resolution-outcomes):
each variant carries exactly the data its emitter arm consumes. `PayloadObject` carries the
`ClassName` the emitter needs to write the payload-factory reference (`FilmPayload::new`);
`RootErrorsArrayOnly` carries nothing (the factory is `errors -> errors`). An enum + nullable
`ClassName` would conflate the two and re-introduce the antipattern §1 dismantles for `Handler`.

Carry it as `Optional<ErrorChannel>` on every field variant whose body is a candidate for the
try/catch / `.exceptionally` wrapper in §3. Concretely:

- `MutationField.DmlTableField` (the sealed supertype introduced by
  [`mutations.md`](mutations.md) Phase 1a, covering insert/update/delete/upsert).
- `MutationField.MutationServiceTableField`, `MutationServiceRecordField`.
- `QueryField.QueryServiceTableField`, `QueryServiceRecordField`, `QueryRowsTableField` (and
  any other Query-side variant that resolves to a fetcher body) when the field's payload type
  matches the channel-detection rules below.

The `DmlTableField` dependency is a *naming* convenience, not a structural one: this plan
adds the same `Optional<ErrorChannel>` component to each affected variant individually. If
[`mutations.md`](mutations.md) reshuffles Phase 1a (renames the supertype, splits it
differently, or sequences the introduction later), this plan still works as long as the four
DML variants exist somewhere; the spec phase only needs to revisit which sealed-type root is
referenced. Same pattern as `nodeIdMeta` in [`mutations.md`](mutations.md) Phase 1a:
classifier resolves once, emitter dispatches over a settled shape. Consult
[`rewrite-design-principles.md:43-47`](../docs/rewrite-design-principles.md#sub-taxonomies-for-resolution-outcomes)
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

    public sealed interface Mapping permits ExceptionMapping, SqlStateMapping, VendorCodeMapping {
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
wrapping an `ExecutionException` wrapping the real exception still routes correctly. The walk
direction is **outermost-first** (the same order `Stream.iterate(t, Throwable::getCause)`
produces): the outer wrapper is examined first, then each `getCause()` hop, until either a
mapping matches or the chain is exhausted. Combined with §3's source-order rule on `MAPPINGS`,
the deterministic answer to "which mapping fires?" is: the first `(mapping, throwable-in-chain)`
pair, scanning the chain outermost-first per mapping, that satisfies the mapping's predicate.
This is a pure improvement over legacy's single-level unwrap and costs nothing.

The async fetcher path matters here. Service-style fetchers in the rewrite return
`CompletableFuture` from a DataLoader (`TypeFetcherGenerator.buildServiceDataFetcher`,
`TypeFetcherGenerator.java:1456-1477`): the fetcher returns `loader.load(key, env)` and the
batch lambda is registered with `DataLoaderFactory.newDataLoaderWithContext(...)`. The lambda
body itself synchronously calls the rows method and wraps the result in
`CompletableFuture.completedFuture(...)`; an exception thrown from the rows method therefore
escapes synchronously from the lambda, which DataLoader catches and surfaces as a *failed*
`CompletableFuture` to the fetcher's caller. Two consequences:

1. The synchronous try/catch in §3 wrapping the body of the rows method (the developer-written
   service call plus the projection) catches throws there directly, where the cause chain is
   unwrapped and source locations are still in scope.
2. The fetcher-level CompletableFuture surface still needs an `.exceptionally(...)` arm,
   because graphql-java's `ExecutionStrategy` invokes the fetcher and observes the returned
   future; any exception escaping past step 1 (e.g. thrown during DataLoader dispatch
   bookkeeping, or by code outside the rows method) reaches the caller as a failed future
   wrapped in `CompletionException`. The emitter therefore wraps both the rows-method body
   (try/catch) and the fetcher-method return (`.exceptionally`).

```java
public static CompletableFuture<Result<FilmRecord>> film(DataFetchingEnvironment env) {
    // ... DataLoader registration ...
    return loader.load(key, env)
        .exceptionally(t -> ErrorRouter.dispatch(t, MAPPINGS, env, FilmPayload::new));
}
```

Whether a fetcher is sync (returns `Result<...>` directly) or async (returns
`CompletableFuture<...>`) is a property of the existing classifier output (the field variant
plus its `ReturnTypeRef.wrapper()`); §2's `ErrorChannel` does not need to encode it. The
emitter forks on the field variant, as it already does for the rest of the body shape; both
forks call into the same `ErrorRouter.dispatch`, so the runtime contract is uniform.

Note on graphql-java's own wrapping: graphql-java internally wraps fetcher exceptions in
`CompletionException` (and sometimes `ExecutionException`) inside `AsyncExecutionStrategy`
*before* invoking the `DataFetcherExceptionHandler`. The rewrite's wrappers run
*before* graphql-java sees the throw, so this layer of wrapping does not affect
`ErrorRouter.dispatch`. The cause-chain walk handles only the wrappers visible at the fetcher
boundary (DataLoader's `CompletionException`, jOOQ's `DataAccessException`, etc.).

#### Validation short-circuit precedes channel matching

Before any `MAPPINGS` iteration, `ErrorRouter.dispatch` checks whether the cause chain
contains a `ValidationViolationGraphQLException` and rethrows immediately if so (§5). This
runs ahead of source-order matching below; a `Throwable`-wide `ExceptionHandler` declared
first cannot intercept a validation violation. Rationale: the carried `GraphQLError`s have a
shape incompatible with the `@error` payload contract, so routing them through `MAPPINGS`
would only construct meaningless instances. The arm exists at the top of `dispatch` and is
not configurable.

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
match-criteria** on different `@error` types. The match-criteria tuple per variant:

- `ExceptionHandler`: `(exceptionClass, matches)`
- `SqlStateHandler`: `(sqlState, matches)`
- `VendorCodeHandler`: `(vendorCode, matches)`

Tuple equality treats absent `matches` as a distinct value from any present `matches`; two
`ExceptionHandler(SQLException, matches=null)` entries collide, two `ExceptionHandler(SQLException,
matches="foo")` entries collide, and `(SQLException, null)` does not collide with
`(SQLException, "foo")`. The check is intra-variant: an `ExceptionHandler(SQLException, null)`
and a `SqlStateHandler("23503", null)` discriminate on different fields and *can* both match the
same Postgres FK violation; the §3 source-order rule resolves which `@error` type the runtime
emits, and this overlap is intentional (it lets schemas declare a fallback `Throwable`/`SQLException`
arm after vendor-specific arms). The second-occurrence-of-an-intra-variant-tuple case is
unreachable and almost certainly an author mistake; a duplicate within a single `@error` type's
`handlers` array is similarly rejected. This closes the legacy gap where duplicates were
silently allowed (`ExceptionStrategyConfigurationGenerator.java:107-117`).

This is a [load-bearing classifier check](../docs/rewrite-design-principles.md#classifier-guarantees-shape-emitter-assumptions):
the producer (this duplicate-criteria check) wears
`@LoadBearingClassifierCheck(key = "error-channel.unique-match-criteria", ...)` and the
consumer (the `ErrorRouter.dispatch` arm that does `findFirst()` on `MAPPINGS` and treats the
first match as authoritative) wears `@DependsOnClassifierCheck` with the same key. Without the
check, two channel mappings with identical criteria would silently shadow each other at
runtime in source order; with it, the only ambiguity left is the intentional cross-variant
overlap.

#### Top-level handler

The `DataFetcherExceptionHandler` installed by `Graphitron.buildSchema` mirrors legacy
`TopLevelErrorHandler` (`graphitron-common/.../TopLevelErrorHandler.java:36-69`), which is
*not* graphql-java's `SimpleDataFetcherExceptionHandler` despite extending it: legacy
overrides `handleException` to hide internals. The rewrite preserves that arm shape:

- `ValidationViolationGraphQLException`: emit the carried `List<GraphQLError>` verbatim
  (this case actually fires only when validation throws *outside* a fetcher with a matching
  channel; inside one, §5's router arm catches it first).
- `IllegalArgumentException`: emit the raw `getMessage()` plus source location. Documented as
  client-visible so authors who throw `IllegalArgumentException` know what surfaces.
- Any other `Throwable` (including `SQLException`, jOOQ exceptions, NPEs, and unmatched
  business exceptions): log at ERROR with a freshly-generated UUID and emit a generic
  `"An exception occurred. The error has been logged with id <uuid>."` to the client. The
  raw exception message is **never** included. This is the privacy property legacy preserves;
  trivially adopting `SimpleDataFetcherExceptionHandler` would regress it.

The legacy `DataAccessException` arm prefixed the generic message with a Spring-mapper-derived
short message; the rewrite drops the Spring dependency (§5 below) and folds that arm into the
generic case. Schema-mapped DB errors still surface through their `@error` payload; only
*unmatched* DB errors reach the top-level handler, where exposing the raw SQL message is the
behaviour we are deliberately hiding.

Consumer override path: the existing `Consumer<GraphQLSchema.Builder>` slot on
`Graphitron.buildSchema` doesn't reach the `DataFetcherExceptionHandler` (which is set on
`AsyncExecutionStrategy`, not `GraphQLSchema.Builder`). The plan adds a sibling slot, either
`Graphitron.buildSchema(Consumer<Builder>, DataFetcherExceptionHandler)` or a builder-style
`GraphitronOptions` carrier; this is covered in the open-questions list.

#### Behaviour change vs legacy

The rewrite drops the Spring `DataAccessException` requirement on the runtime side:
`SqlStateHandler` / `VendorCodeHandler` match any cause-chain entry that exposes
`getSQLState()` / `getErrorCode()` (i.e. any `SQLException`), not only one wrapped in a Spring
`DataAccessException`. Two implications:

- A non-Spring app (which is the rewrite's primary target) gets database error mapping for
  free; legacy required `spring-jdbc` on the consumer classpath.
- A Spring app where a `DataAccessException` wraps a `SQLException` continues to match because
  the chain walk reaches the inner `SQLException` regardless. The wrapping no longer matters.

This is the runtime side of the parse-time lift in §1: `[{handler: DATABASE}]` (no
discriminator) parses to `ExceptionHandler(SQLException)` rather than the legacy
`DataAccessException`-nominal match. Consumers writing schemas for the rewrite see the looser
match in the model variant the SDL lifts to, not just in the runtime behaviour.

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

For an `ExceptionHandler`, a declared checked exception `T` matches when `T` is assignable to
the handler's `exceptionClass` (i.e. the handler is **wider or equal**, not narrower). This is
the natural runtime semantic: an `ExceptionHandler(exceptionClass = SQLException)` catches a
method declaring `throws SQLDataException` because at runtime any `SQLException`-subclass cause
in the chain matches via `isInstance`, but the inverse (a handler narrower than the declared
exception) would let the runtime path slip past. Concretely: a method `throws Throwable` with
no `Throwable`-wide handler in the channel is a build-time error.

For `SqlStateHandler` / `VendorCodeHandler`, a declared `SQLException` (or any subclass)
satisfies the channel because both variants match on any `SQLException` in the cause chain
regardless of subclass. A declared exception that is *not* assignable to `SQLException` does
not satisfy these variants (and is rejected unless the channel also carries an
`ExceptionHandler` that is wider-or-equal).

#### Special cases

- **`InterruptedException`, `IOException` from non-database I/O.** These are usually
  infrastructure errors that should reach the top-level handler, not a typed `@error`. The
  rule above forces the schema to either (a) declare a wide handler (e.g. `Throwable`-wide
  `ExceptionHandler`), which is usually wrong because it leaks infrastructure detail to clients,
  or (b) wrap them in a domain exception in the service implementation. Document this in the
  spec; it's not
  load-bearing for the generator but is the prevailing pattern in real services.
- **`IllegalArgumentException`.** Legacy
  (`SchemaBasedErrorStrategy.java:58-73`) treats `IllegalArgumentException` as a special arm,
  separate from the schema-mapped strategy. The rewrite folds it into the same pipe: an
  `IllegalArgumentException` matches whichever channel includes an `ExceptionHandler` against
  it. This is a behaviour simplification: legacy's separate arm exists only because its
  dispatch was per-exception-type, not per-channel.
- **Unchecked `RuntimeException` thrown from non-`@service` code.** The same dispatch applies;
  the runtime cannot tell the difference between a checked and unchecked throw at the
  fetcher's boundary. The classifier check in this section gates only `@service` /
  `@tableMethod` declared exceptions; unchecked exceptions thrown anywhere else reach
  `ErrorRouter.dispatch` via the catch arm in §3 and either match a channel handler or fall
  through to the top-level handler.

### 5. Jakarta-only validation

The `RecordValidator` two-module split exists only to bridge `javax.validation` vs
`jakarta.validation` imports. Rewrite consumers are jakarta-only by assumption; the validator
class (and the `ValidationViolationGraphQLException` it throws) lives wherever
`recordValidator.validate(...)` is invoked from generated code, with `jakarta.validation-api`
imported directly. Per the open-questions resolution above, `ErrorRouter` itself is emitted
as a generated helper rather than living in a runtime jar; the validator follows the same
convention or is emitted alongside the input-type fetcher that calls it. One file, one
dependency, no `<profile>` dance. This is a pom hygiene win, not architectural; flagged here
only so the parity discussion is complete.

#### `ValidationViolationGraphQLException` path

Legacy `SchemaBasedErrorStrategy` has a dedicated `handleValidationException` arm
(`SchemaBasedErrorStrategy.java:58-65`) that intercepts `ValidationViolationGraphQLException`
(an `AbortExecutionException` carrying a list of `GraphQLError`s pre-built by `RecordValidator`).
The carried `GraphQLError`s do not have the `(path, message)` shape an `@error` payload
expects, so they cannot be slotted into the same payload factory. The rewrite resolves this by
treating `ValidationViolationGraphQLException` as a top-level exception, not a schema-mapped
one:

- When `ErrorRouter.dispatch` sees a `ValidationViolationGraphQLException` anywhere in the
  cause chain, it does not consult `MAPPINGS` and does not invoke the payload factory. It
  rethrows. The top-level handler (above) then emits the carried `List<GraphQLError>`
  verbatim, which is the legacy on-the-wire shape (`GraphQLError`s appear in the response's
  top-level `errors` array, not inside a payload's `errors` field).
- The rethrow is unconditional and precedes any `@error`-based matching. A schema author
  cannot route a validation violation into a typed `@error` payload; the carried errors
  already have a fixed shape from `RecordValidator`. This matches legacy: the validation arm
  in `SchemaBasedErrorStrategy.handleValidationException` is implemented per consumer to
  produce a payload from the carried errors, but the *only* carrier is the validation
  errors, not application-shaped `@error` instances. The rewrite consolidates by sending
  them top-level, removing the per-consumer payload-conversion seam.

Behaviour shift to document: legacy let consumers override `handleValidationException` to
synthesise a payload from the `List<GraphQLError>`. The rewrite drops that hook; the
violations always surface as top-level errors. Real usage of the legacy hook is rare (no
`graphitron-test` fixture exercises it), but call this out in the spec so consumers
relying on the override can plan a migration.

The developer-facing flow upstream is unchanged: code calls `recordValidator.validate(...)`,
which throws `ValidationViolationGraphQLException` if violations exist; the trigger is still
developer-invoked, and no auto-wiring is added.

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

- `default/`: single `@error` type with one `GENERIC` handler. Lifts to `ExceptionHandler`.
- `databaseHandledError/`: `[{handler: DATABASE}]` with no sqlState/code. Lifts to
  `ExceptionHandler(SQLException, ...)` per §1's no-discriminator rule (legacy default
  `DataAccessException` becomes the documented behaviour shift in §3).
- `databaseWithSqlState/`, `databaseWithCode/`: DATABASE handler with sqlState / code in turn.
  Lift to `SqlStateHandler` and `VendorCodeHandler` respectively per §1.
- `databaseWithMatches/`: DATABASE handler with only `matches` (no sqlState, no code). Lifts to
  `ExceptionHandler(SQLException, matches=...)` per §1's no-discriminator rule.
- `bothGenericAndDatabase/`, `bothGenericAndDatabaseInMultipleErrorsForOneResponse/`: mixed
  handlers on the same / on different `@error` types in one channel.
- `multipleHandlers/`, `unionMultipleHandlers/`: multiple handlers per `@error` type.
- `multiple/`, `multipleInOneResponse/`: multiple `@error` types per channel (list shape).
- `multipleDatabaseHandlers/`: two DATABASE handlers with different `code` values on the same
  `@error` type. Different match-criteria, both reachable; §3's ordering rule preserves the
  legacy semantic (first matching `code` wins).
- `union/`, `unionDatabase/`, `unionMultipleErrorsWithMultipleMixedHandlers/`,
  `unionMultipleMixedHandlers/`, `unionWithUnhandledError/`: union shape for `errors` field.
- `withDescription/`, `withMatches/`: optional handler-fields populated.
- `query/`: `@error` channel on a Query field, not just Mutation. (Hinges on the open question
  on query-side scope below; if narrowed to mutation-only, this fixture moves to "rejected"
  with a documented behaviour shift.)
- `service/`: `@error` on a `@service`-backed mutation.
- `onlyOneHandled/`: partial schema coverage (some `@error` types in the union have no matching
  handler for the thrown exception).
- `noErrors/`, `noHandlers/`: degenerate inputs (payload with no `errors` field; `@error` type
  with empty `handlers` list). Asserts that classification produces "no `ErrorChannel`" for
  the former and a classifier rejection (`UnclassifiedType`) for the latter, since an `@error`
  type with zero handlers is unmatched by anything.

**Rejected by the classifier (rewrite is stricter; document in spec):**

- `databaseWithCodeAndSqlState/`: legacy ANDed both `code` and `sqlState` silently
  (`DataAccessMatcher.java:28-30`); §1's reject rule 3 forbids the combination and tells the
  author to split into a `SqlStateHandler` + `VendorCodeHandler` pair. Update this fixture's
  expected output to a classifier error.
- `unionMultipleDatabaseHandlers/`: two `DATABASE` handlers across two `@error` types in the
  same channel where the match-criteria collide. §3's duplicate-criteria classifier check
  rejects; legacy permitted it silently and the second was unreachable. Update the expected
  output to a classifier error. (If the fixture's two handlers actually differ on
  match-criteria, it is in-scope and behaves like `multipleDatabaseHandlers/`; spec phase
  reads the fixture once and partitions accordingly.)

The pipeline-test set in `graphitron-test` covers each row above with one execution-test
asserting the actual on-the-wire error shape; the per-row fixtures live in `graphitron-test`'s
SDL alongside the mutation fixtures (the mutation-bodies fixture-gap closure in
[`mutations.md`](mutations.md)).

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

- **Where does `ErrorRouter` and the matcher code live?** Settled: emit the router as a
  generated static helper class alongside the existing `*Fetchers`. A new runtime jar that
  emitted code depends on would directly violate the
  ["Rewrite builds independently of legacy Graphitron modules"](../docs/rewrite-design-principles.md#rewrite-builds-independently-of-legacy-graphitron-modules)
  invariant (`rewrite-design-principles.md:111-117`); consumers depend on the rewrite
  aggregator alone, with no separate runtime artifact to version against the generator.
  `ErrorRouter` is ~150 lines of pure cause-chain walking and matcher application; emitting
  it once per build is cheap and keeps the no-runtime-jar property intact. Spec phase commits
  to this path; remaining open question is the file naming convention (one `ErrorRouter`
  per `*Fetchers` class, or one shared `ErrorRouter` per generated package).
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
  needs to add at least three `@error` fixtures, one per Handler variant: a `SqlStateHandler`
  (sqlState match via a Sakila constraint violation; e.g. `23503` foreign-key on the
  `film_actor` link table), a `VendorCodeHandler` (any vendor-code-bearing fixture, even if
  Postgres reports `0` at runtime; the parse-side classification is what's exercised), and an
  `ExceptionHandler` (developer-thrown business exception). The mutation-bodies fixture-gap
  closure in [`mutations.md`](mutations.md) is the natural place to extend. The full fixture
  list to reproduce is in the "Legacy fixture coverage" section above.
- **Phase ordering with `mutations.md`.** The author leans toward option (a) in the
  "Relationship to neighbouring items" section: land mutations Phases 2-6 first, then layer
  error-channel emission as a sweep across the new emitters. Rationale: every mutation phase's
  emitter touches the same fetcher body, and adding a second axis of variation (error
  wrapping) inside each phase would interleave concerns. Sweeping the error wrapper across
  finished emitters in a single follow-up commit keeps each phase's diff focused.
