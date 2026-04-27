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
    String sqlState,
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

`GenericHandler.exceptionClass` resolves the FQN to a `ClassName` at classify time. If the
class cannot be resolved on the classifier classpath, the parent `ErrorType` becomes
`UnclassifiedType` with a descriptive reason (mirrors how `@record(record: {className: ...})`
already validates Java reflection at build time).

`ErrorHandlerType` then collapses to a private discriminator, or disappears entirely.

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

### 5. Jakarta-only validation

The `RecordValidator` two-module split exists only to bridge `javax.validation` vs
`jakarta.validation` imports. Rewrite consumers are jakarta-only by assumption; the
`graphitron-rewrite-runtime` (or wherever `ErrorRouter` and the validator land) imports
`jakarta.validation-api` directly. One file, one dependency, no `<profile>` dance. This is a
pom hygiene win, not architectural; flagged here only so the parity discussion is complete.

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
  already exposes it for federation, custom scalars, and tenant-scoped `DSLContext`).

---

## Open questions for the Spec phase

- **Where does `ErrorRouter` and the matcher code live?** Options: (a) a new
  `graphitron-rewrite-runtime` module that emitted code depends on at runtime; (b) emit the
  matcher classes alongside the fetchers, no runtime jar. (a) keeps the emitter tight; (b)
  preserves the rewrite's "no runtime support library" property documented in
  [`rewrite-design-principles.md:111-117`](../docs/rewrite-design-principles.md#rewrite-builds-independently-of-legacy-graphitron-modules).
  Decide before Spec → Ready.
- **`@error` on query fields.** Legacy applies error mapping to any operation whose return
  type carries an `errors` field. Confirm against production schemas whether query-side error
  channels are in use; if zero usage, narrow scope to mutation fields and document the cut.
- **Fixture coverage.** The current `graphitron-test` schema has no `@error` types. Spec phase
  needs to add at least one `@error` fixture exercising both `DatabaseHandler` (sqlState match
  via a Sakila constraint violation) and `GenericHandler` (developer-thrown business
  exception). The mutation-bodies fixture gap closure (`mutations.md:457-465`) is the natural
  place to extend.
