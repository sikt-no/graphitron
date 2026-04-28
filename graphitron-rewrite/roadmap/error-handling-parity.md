---
title: "Error-handling parity: emit per-fetcher error channels from `@error`"
status: Spec
bucket: architecture
priority: 7
---

# Error-handling parity: emit per-fetcher error channels from `@error`

The classifier resolves `@error` types into `GraphitronType.ErrorType` with a
`List<ErrorType.Handler>` (`TypeBuilder.buildErrorType` at `TypeBuilder.java:485`). Nothing
consumes them. Two visible consequences on production schemas today:

1. A payload's `errors: [SomeUnion!]!` field hits one of five sibling `PolymorphicReturnType`
   rejections in `FieldBuilder` and produces no fetcher (see "Current blocker" below).
2. Even on a payload whose `errors` field is `[SomeError!]!` (single `@error` type, not a
   union, so no rejection fires), the fetcher body produced today does not route a thrown
   exception into the typed payload, and graphql-java's default
   `SimpleDataFetcherExceptionHandler` leaks the raw exception message to the client.

`code-generation-triggers.md:109` records the gap as "No generation (error mapping config)".

This item scopes the model refactor that makes the routing tractable, the classifier work that
unblocks payload-shaped `errors` fields, and the emission story. Subsumes
[`checked-exceptions-typed-errors.md`](checked-exceptions-typed-errors.md) (Backlog) per §4 below;
phase 0 is independent of [`mutations.md`](mutations.md), phases 1-3 sweep across the emitters
that plan introduces (see Phasing below).

---

## Current blocker (production)

A payload field shaped `errors: [SomeUnion!]!` (or `[SomeInterface!]!`) where the union members
or interface implementers are all `@error` types currently classifies as `UnclassifiedField`.
Five sibling sites in `FieldBuilder.java` reject the same shape with `RejectionKind.DEFERRED`
when the resolved return type is `ReturnTypeRef.PolymorphicReturnType`:

| Line | Caller                                  | Context                                                |
|------|-----------------------------------------|--------------------------------------------------------|
| 1587 | `classifyQueryField`                    | Query root `@service` returning a polymorphic type     |
| 1712 | `classifyMutationField`                 | Mutation root `@service` returning a polymorphic type  |
| 1861 | `classifyChildFieldOnResultType`        | Child `@service` on a `@record` parent                 |
| 1918 | `classifyChildFieldOnResultType`        | Child non-service field on a `@record` parent          |
| 2015 | `classifyChildFieldOnTableType`         | Child `@service` on a `@table` parent                  |

The canonical hit is the consumer's `BehandleSakPayload.errors: [BehandleSakError!]!` (a union of
`@error` types) on a `@record`-typed payload returned by a `@service`-backed mutation. The
mutation root field hits 1712; the payload's own `errors` field hits 1918 once the parent does
classify. All five must be lifted in the same classifier pass: an `errors`-shaped field can
land on any of them depending on whether the carrier is service-backed and whether the parent
is `@record` or `@table`. Other polymorphic uses (non-`@error` interface/union returns) stay
rejected as before; the lift is gated on "every member type is an `@error` type".

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
consumer wiring; the javax module duplicate; the abstract `SchemaBasedErrorStrategy` subclass
hooks (`handleValidationException`, `handleIllegalArgumentException`,
`createDefaultDataAccessError`) — replaced by directive-side declarations per §5's migration
table.

---

## Phasing

The plan splits along a clean seam: phase 0 (the classifier lift) is independent of every
other piece and can land on its own. Phases 1-3 build the runtime story; phase 4 folds in the
checked-exception roadmap item. The table is the schedule recommendation.

| Phase | Scope                                                                                                              | Depends on                          | Decouples |
|-------|--------------------------------------------------------------------------------------------------------------------|-------------------------------------|-----------|
| 0     | Lift the five `PolymorphicReturnType`-on-`@error` rejections in `FieldBuilder`. Add `ErrorsField` model variant; emit a passthrough fetcher (`(payload).errors`). No router, no try/catch, no mapping. | None.                               | Unblocks production schemas with `errors: [SomeUnion!]!`. The fetcher returns whatever the upstream payload object carries; runtime exceptions still escape to graphql-java's default handler, same as today. |
| 1     | Sealed `Handler` taxonomy (§1); per-field `ErrorChannel` + `PayloadShape` sub-taxonomy (§2); validator changes (§1's reject rules + duplicate-criteria check from §3).                              | Phase 0.                            | Pure model + classifier work; emitters still call the phase-0 passthrough. |
| 2     | Emit per-package `ErrorMappings` + `ErrorRouter` helpers (with `dispatch` and `redact`); wrap *every* fetcher body in try/catch (channel → `dispatch`; no channel → `redact`). No engine-level handler; the privacy contract lives at the fetcher catch site. | Phase 1; the four DML mutation variants from `mutations.md` Phase 2 (each is a fetcher body that needs the wrapper). |  |
| 3     | `VALIDATION` handler runtime + override-retirement (§5).                                                            | Phase 2.                            | Schema-author-visible migration story for the legacy override hooks. |
| 4     | Fold checked exceptions on `@service` / `@tableMethod` into the same channel (§4); retire `checked-exceptions-typed-errors.md`. | Phase 2.                            |  |

**Recommended order with `mutations.md`.** Land phase 0 ahead of `mutations.md` (it touches
`FieldBuilder` only and unblocks the BehandleSakPayload-shaped consumer schemas immediately).
Land phase 2 after `mutations.md` Phase 6 is Done, as a sweep across the new mutation
emitters; folding the try/catch wrapper into each mutation phase would interleave concerns.
Phases 1, 3, 4 can run on their own schedule once phase 2 has landed.

This subsumes the earlier "phase ordering" open question.

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
sealed interface Handler
        permits ExceptionHandler, SqlStateHandler, VendorCodeHandler, ValidationHandler {
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

record ValidationHandler(
    Optional<String> description     // matches and exceptionClass intentionally absent
) implements Handler {
    @Override public Optional<String> matches() { return Optional.empty(); }
}
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

`ValidationHandler` retires the legacy `CustomSchemaBasedErrorStrategy.handleValidationException`
override entirely; see §5 for the runtime contract (one
`ValidationViolationGraphQLException` fans out into N `(path, message)` instances of the
parent `@error` type's developer-supplied class). It carries no fields the matcher
discriminates on: the implicit exception class is `ValidationViolationGraphQLException`, the
per-violation messages come from each carried `GraphQLError`, and a top-level message-substring
filter would only short-circuit *all* violations (not "filter individual violations"), which is
not a meaningful schema-author intent.

`ErrorHandlerType` (the legacy `DATABASE | GENERIC` enum at
`no.sikt.graphitron.rewrite.model.ErrorHandlerType`) and the legacy-shape `Handler` record at
`GraphitronType.java:202-209` are deleted in the same edit that lands the sealed hierarchy.
The replacement enum (`GENERIC | DATABASE | VALIDATION`, see the SDL change below) only exists
at the SDL parse boundary; downstream code consumes the sealed `Handler` variants. Validators
and any in-tree references update in lockstep; otherwise the build breaks before the new shape
compiles.

#### SDL grammar change: add `VALIDATION` to `ErrorHandlerType`

The directive grammar stays compatible with legacy except for one additive change: the
`ErrorHandlerType` enum gains a third value, `VALIDATION`. Existing schemas (which use only
`GENERIC` and `DATABASE`) keep parsing without modification.

```graphql
enum ErrorHandlerType { GENERIC, DATABASE, VALIDATION }
```

The reason this enum value belongs in the directive rather than being a runtime-only special
case: the rewrite is the chance to retire the legacy `SchemaBasedErrorStrategy` consumer
override hooks, which forced every consumer to write near-identical Java to fan out
`ValidationViolationGraphQLException.getUnderlyingErrors()` into typed `@error` instances. With
`VALIDATION` declared at the schema level, the fan-out becomes a router-side responsibility
the schema author never sees. See §5 for the runtime contract and the migration subsection
below for the override → directive translation.

The `ErrorHandler` input shape is otherwise unchanged. `handler`, `className`, `code`,
`sqlState`, `matches`, `description` keep their current names and types. Existing GENERIC and
DATABASE schemas behave the same as before (modulo the documented behaviour shift on
DATABASE-with-no-discriminator, below).

#### Parse-time lift

`TypeBuilder.parseErrorHandler` lifts each entry into one of the four model variants by
inspecting which fields are present, and rejects combinations that are ambiguous,
vendor-conflicting, or contradict the new VALIDATION semantic:

| SDL entry                                              | Model variant                                |
|--------------------------------------------------------|----------------------------------------------|
| `{handler: GENERIC, className: "X"}`                   | `ExceptionHandler(X, ...)`                   |
| `{handler: DATABASE, sqlState: "23503"}`               | `SqlStateHandler("23503", ...)`              |
| `{handler: DATABASE, code: "1"}`                       | `VendorCodeHandler("1", ...)`                |
| `{handler: DATABASE}` (no sqlState, no code)           | `ExceptionHandler(SQLException, ...)`        |
| `{handler: VALIDATION}`                                | `ValidationHandler(description=...)`         |

Reject (rules split between two classifier sites; the table notes each):

| # | Rule                                                 | Site                                         | Surfaces as       |
|---|------------------------------------------------------|----------------------------------------------|-------------------|
| 1 | `GENERIC` without `className`                        | `TypeBuilder.parseErrorHandler` (type-level) | `UnclassifiedType` |
| 2 | `GENERIC` with `sqlState` or `code`                  | `TypeBuilder.parseErrorHandler` (type-level) | `UnclassifiedType` |
| 3 | `DATABASE` with both `sqlState` and `code`           | `TypeBuilder.parseErrorHandler` (type-level) | `UnclassifiedType` |
| 4 | `DATABASE` with a non-default `className`            | `TypeBuilder.parseErrorHandler` (type-level) | `UnclassifiedType` |
| 5 | `VALIDATION` with any of `className`/`sqlState`/`code`/`matches` | `TypeBuilder.parseErrorHandler` (type-level) | `UnclassifiedType` |
| 6 | More than one `VALIDATION` handler in the same channel | `FieldBuilder` (channel-level, see §2c)    | `UnclassifiedField` on the carrier |
| 7 | Duplicate match-criteria across handlers in the same channel | `FieldBuilder` (channel-level, see §3)   | `UnclassifiedField` on the carrier |

Rules 1-5 are intra-`@error`-type and check at parse time. Rules 6-7 span multiple `@error`
types in one channel (the carrier's `ErrorChannel.mappedErrorTypes`) and must run after the
carrier resolves which `@error` types apply; they live in the carrier classifier and surface
as `UnclassifiedField` on the carrier (the field with the channel), not on the offending
`@error` type itself. Detail per rule:

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
5. **`VALIDATION` with any of `className`, `sqlState`, `code`, `matches`**: the implicit
   exception class is `ValidationViolationGraphQLException`; SQL discriminators are
   irrelevant; `matches` would short-circuit all violations rather than filter individual
   ones, which is not a meaningful intent. Reject with a hint pointing at the override-
   migration table.
6. **More than one `VALIDATION` handler in the same channel**: validation is a single fan-out
   target per payload; two `VALIDATION` handlers would compete for the same `@error` slot.
   Reject on the carrier, naming both offending `@error` types. Across separate channels on
   different fields, multiple `VALIDATION` handlers are fine, each scoped to its own field's
   payload.
7. **Duplicate match-criteria across handlers in the same channel**: covered by the §3
   classifier check; listed here so the parse-time rules are exhaustive.

The DATABASE-with-no-discriminator case (fourth row of the lift table) deserves a note:
legacy's default `className` was `DataAccessException`, but the rewrite's runtime walks the
cause chain for any `SQLException` (§3 behaviour change). The lift to
`ExceptionHandler(SQLException)` makes the rewrite's actual semantic visible in the model
rather than hidden in the runtime matcher. Schemas that relied on legacy's
"DataAccessException-only" nominal match (rare; Spring-specific) get a documented behaviour
shift, not a silent one.

#### Field structural requirements on `@error` types

The current classifier (`FieldBuilder.classifyChildFieldOnErrorType`,
`FieldBuilder.java:1538-1545`) restricts fields on `@error` types to scalar or enum. The SDL
contract is schema-first: every `@error` type must declare `path: [String!]!` and
`message: String!`. Missing or differently-shaped fields produce `UnclassifiedType`. Every
legacy fixture under
`graphitron-codegen-parent/graphitron-java-codegen/src/test/resources/exceptions/` satisfies
this constraint.

The developer-supplied Java class must also provide a `(List<String> path, String message)`
constructor, validated at classify time via reflection (the same mechanism `@record` uses). A
class without the matching constructor produces `UnclassifiedType` with a descriptive reason,
even if the SDL fields are correctly declared.

The schema-level check is the [load-bearing classifier check](../docs/rewrite-design-principles.md#classifier-guarantees-shape-emitter-assumptions):
the producer (`TypeBuilder.buildErrorType`) wears
`@LoadBearingClassifierCheck(key = "error-type.path-message-fields", ...)` and the consumer
(§3's `ErrorRouter.dispatch` payload-factory call site, which constructs
`new SomeError(path, message)`) wears `@DependsOnClassifierCheck` with the same key. The
reflection check is a secondary guarantee; both must pass for the type to classify.

### 2. Resolve operation-to-error wiring at classify time

Legacy builds `Map<String, PayloadCreator>` at runtime construction because the legacy generator
had no per-field resolution layer. The rewrite already classifies mutation and service fields.
Two model additions, one per side of the carrier/payload split:

#### 2a. `ErrorsField` — the payload-side variant the carrier walks

The phase-0 lift (above) introduces a new sealed permit on `GraphitronField`:

```java
record ErrorsField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ErrorListShape shape,                      // see below
    List<ErrorTypeRef> mappedErrorTypes        // resolved at classify time
) implements GraphitronField {}

sealed interface ErrorListShape
        permits ErrorListShape.SingleType, ErrorListShape.UnionMembers, ErrorListShape.InterfaceImpls {
    /** [SomeError!]! — single @error type, no polymorphism. */
    record SingleType(ErrorTypeRef errorType) implements ErrorListShape {}
    /** [SomeUnion!]! where every union member carries @error. */
    record UnionMembers(List<ErrorTypeRef> members) implements ErrorListShape {}
    /** [SomeInterface!]! where every implementer carries @error. */
    record InterfaceImpls(List<ErrorTypeRef> implementers) implements ErrorListShape {}
}
```

`ErrorsField` exists so that `classifyChildFieldOnResultType` (and its `@table`-parent and
`@service`-backed siblings) have an explicit arm to take when they encounter a list-of-error
shape, rather than falling through to `PolymorphicReturnType`'s rejection. The emission for an
`ErrorsField` is a passthrough fetcher: at request time the parent's payload object already
carries the `errors` list (the carrier's `ErrorRouter.dispatch` produced it, or the
service-method body did), so the fetcher reads it directly. Phase 0 emits exactly this; the
typed shape information feeds phase 1's carrier-side `ErrorChannel`.

#### 2b. Per-child classifier coordination

The five rejection sites in "Current blocker" share a single check: when the resolved
`ReturnTypeRef.PolymorphicReturnType` names a union or interface whose every member type is an
`@error` type, lift to `ErrorsField` instead of returning `UnclassifiedField`. The check needs
the `BuildContext`'s catalog of `ErrorType`s, which is populated before child-field
classification runs (`TypeBuilder` resolves all object types before `FieldBuilder` walks
fields). Two rules pin the coordination down:

- **The lift is field-shape-driven, not parent-driven.** A field returning `[U!]!` where `U` is
  a union of `@error` types lifts to `ErrorsField` regardless of whether its parent is `@table`,
  `@record`, or root. The carrier-side `ErrorChannel` (next subsection) is built by the
  *carrier's* classifier, not the child's; the child's job is to commit to a typed shape.
- **The lift gates on the all-`@error` predicate.** A union with one non-`@error` member falls
  through to the existing `PolymorphicReturnType` rejection. Mixed unions are out of scope; the
  spec phase decides whether to make this a build-time error or a deferred-stub case.

A schema author writing `errors: SomeError!` (single, non-list) is rejected: the legacy
fixtures all use list shapes, the runtime fan-out semantics assume a list, and a non-list
single-error shape would not survive the `VALIDATION`-fan-out arm in §5. The reject message
points at the list shape.

#### 2c. `ErrorChannel` — the carrier-side sub-taxonomy

Phase 1 adds the carrier-side resolution:

```java
record ErrorChannel(
    String errorsFieldName,                  // the "errors" field on the payload
    List<ErrorTypeRef> mappedErrorTypes,     // union members or list-element type
    PayloadShape shape
) {}

sealed interface PayloadShape
        permits PayloadShape.PayloadObject {

    /** The fetcher returns a payload object whose `errorsFieldName` field is the errors list. */
    record PayloadObject(ClassName payloadClass) implements PayloadShape {}
}
```

`PayloadShape` is a sub-taxonomy in the sense of
[`rewrite-design-principles.md:43-47`](../docs/rewrite-design-principles.md#sub-taxonomies-for-resolution-outcomes):
each variant carries exactly the data its emitter arm consumes. `PayloadObject` carries the
`ClassName` the emitter needs to write the payload-factory reference (`FilmPayload::new`).

Carry `Optional<ErrorChannel>` on every field variant whose body is a candidate for the
try/catch / `.exceptionally` wrapper in §3. Concretely:

- `MutationField.DmlTableField` (the sealed supertype introduced by
  [`mutations.md`](mutations.md) Phase 1a, covering insert/update/delete/upsert).
- `MutationField.MutationServiceTableField`, `MutationServiceRecordField`.
- `QueryField.QueryServiceTableField`, `QueryServiceRecordField`, `QueryRowsTableField` (and
  any other Query-side variant that resolves to a fetcher body) when the field's payload type
  matches the channel-detection rules below.

The `DmlTableField` dependency is a *naming* convenience, not a structural one: this plan
adds the same `Optional<ErrorChannel>` component to each affected variant individually. Same
pattern as `nodeIdMeta` in [`mutations.md`](mutations.md) Phase 1a: classifier resolves once,
emitter dispatches over a settled shape.

The carrier classifier walks the field's payload return type, identifies the `errors` field by
its `ErrorsField` classification (committed in §2a), and resolves which `Handler`s apply. No
global map; each field carries its own list. Query fields can also opt in: the legacy code
applied `@error` to any operation whose return type carried an `errors` field.

**Channel-detection rules (carrier classifier, phase 1):**

1. The payload type must be a `@table` or `@record` object whose field set includes exactly
   one `ErrorsField` (committed by §2a). That field's `name` becomes `errorsFieldName`; its
   `mappedErrorTypes` populate the channel.
2. A payload with no `ErrorsField` has no `ErrorChannel`. Throwing inside that fetcher takes
   the top-level path (§3).
3. The detection ignores the field's name; legacy convention is `errors:` but the rewrite keys
   off the structural relationship to `@error` types, not a hardcoded field name. This matches
   how `mutations.md` keys off return-type shape rather than directive presence.
4. A payload with two `ErrorsField` children is rejected (`UnclassifiedField` on the second
   one): the runtime contract assumes one channel per fetcher body. Spec phase confirms no
   legacy fixture uses two; if one does, that fixture moves to "rejected".

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
Every emitted fetcher gets a try/catch — universally, even when the field's payload has no
`@error` types — so the privacy contract (UUID-log unmatched throws, never expose internal
messages) is enforced at the rewrite-controlled emission boundary rather than delegated to
an engine-level handler the consumer might forget to install.

For a fetcher with an `ErrorChannel`:

```java
public static Object createFilm(DataFetchingEnvironment env) {
    try {
        // existing INSERT / UPDATE / service body
    } catch (Throwable t) {
        return ErrorRouter.dispatch(t, MAPPINGS, env, FilmPayload::new);
    }
}
```

For a fetcher without an `ErrorChannel` (no `@error` types declared on the payload — or no
payload, e.g. a query field returning a scalar):

```java
public static Object getActorCount(DataFetchingEnvironment env) {
    try {
        // existing body
    } catch (Throwable t) {
        return ErrorRouter.redact(t, env);  // logs UUID, returns DataFetcherResult with redacted error
    }
}
```

`ErrorRouter.dispatch` and `ErrorRouter.redact` never rethrow. The unmatched arm in
`dispatch` falls through to the same redaction logic as `redact`. graphql-java's default
`DataFetcherExceptionHandler` therefore never fires for a Graphitron-emitted fetcher. The
consumer wires nothing.

`MAPPINGS` is a `private static final Mapping[]` reference *into* a shared
`<outputPackage>.schema.ErrorMappings` class, not an inline array literal in each `*Fetchers`
class. `ErrorMappings` defines one named constant per distinct channel-flattened mapping list:

```java
// GENERATED — ErrorMappings
public final class ErrorMappings {
    public static final ErrorRouter.Mapping[] FILM_PAYLOAD = new ErrorRouter.Mapping[] { ... };
    public static final ErrorRouter.Mapping[] BEHANDLE_SAK_PAYLOAD = new ErrorRouter.Mapping[] { ... };
    // ...
}
```

Each `*Fetchers` class declares `private static final Mapping[] MAPPINGS = ErrorMappings.FILM_PAYLOAD;`
and refers to it from the catch arm. Three reasons:

1. **Dedup.** A schema with N fetchers each mapping the same K `@error` types produces K mapping
   instances total instead of K·N. For the canonical Sikt schema this is a real saving, not just
   a code-smell concern.
2. **Testability.** Pipeline tests assert SDL → TypeSpec at one site (`ErrorMappings`) per
   channel, rather than chasing inline literals across N `*Fetchers` classes. Aligns with the
   tier model in `rewrite-design-principles.md:86-88`; fetcher pipeline tests focus on their
   own variant and just confirm the static reference.
3. **Naming as classifier output.** The constant name is produced by the classifier
   (`ErrorChannel.mappingsConstantName`), not invented at print time. This makes the cross-class
   reference eligible for the [load-bearing classifier check](../docs/rewrite-design-principles.md#classifier-guarantees-shape-emitter-assumptions)
   pattern: producer wears `@LoadBearingClassifierCheck(key = "error-channel.mappings-constant", ...)`,
   consumer wears `@DependsOnClassifierCheck`. Closes the same loop §1 already opened for the
   `(List<String>, String)` constructor.

`ErrorRouter` is also emitted at `<outputPackage>.schema.ErrorRouter` (one per output package,
not one per `*Fetchers` class). It walks the cause chain with the legacy matcher logic and
returns either the populated payload (schema-mapped) or a redacted `DataFetcherResult`
(unmatched). Consumers write no error-handling Java at all in the common case; the legacy
`SchemaBasedErrorStrategy` subclass disappears in favour of `@error` directive declarations
(see §5's migration table) and there is no engine-level handler to install (see "No top-level
handler" below).

Both `ErrorRouter` and `ErrorMappings` are emitted, not shipped as a runtime jar; this preserves
the [rewrite-builds-independently invariant](../docs/rewrite-design-principles.md#rewrite-builds-independently-of-legacy-graphitron-modules).

#### Concrete dispatch signature

```java
public final class ErrorRouter {
    /** Channel-mapped dispatch: matched → payload via factory; unmatched → redacted DataFetcherResult. */
    public static Object dispatch(
            Throwable thrown,
            Mapping[] mappings,                          // emitted MAPPINGS array
            DataFetchingEnvironment env,                 // for path/source-location
            Function<List<Object>, ?> payloadFactory     // (errors) -> payload
    ) { ... }

    /** No-channel disposition: log with correlation ID, return DataFetcherResult with a redacted error. */
    public static Object redact(Throwable thrown, DataFetchingEnvironment env) { ... }

    public sealed interface Mapping
            permits ExceptionMapping, SqlStateMapping, VendorCodeMapping, ValidationMapping {
        Object build(List<String> path, String message);  // constructs the @error instance
    }
}
```

Neither method throws; the catch arm at the call site never rethrows either. The redaction
arm builds:

```java
DataFetcherResult.newResult()
    .data(null)
    .error(GraphqlErrorBuilder.newError(env)
        .message("An error occurred. Reference: " + correlationId + ".")
        .build())
    .build();
```

with the original `Throwable` logged at ERROR alongside the correlation ID. The correlation ID
is the current OTel trace ID when an active span is present (using the OTel API if on the
compile classpath), falling back to `UUID.randomUUID().toString()`. The emitter detects OTel
availability at build time and generates the appropriate resolution. This makes the ID findable
as a distributed trace, not just a log entry. The raw exception message is never put into the
response. This is the privacy property legacy preserved via its top-level handler; the rewrite
preserves it at the fetcher catch site instead.

The emitted call site supplies a payload factory rather than a no-arg constructor reference;
this mirrors the legacy `PayloadCreator` shape (`SchemaBasedErrorStrategy.java:139-145`) but
binds the errors list explicitly rather than via a setter.

`path` resolves to `env.getExecutionStepInfo().getPath().toList()`; `message` resolves to the
handler's `description` if present, otherwise the matched exception's `getMessage()` (preserves
legacy fallback per `ExceptionToErrorMappingProviderGenerator.java:230-233`). This applies only
to the matched path; the unmatched path uses the redacted correlation-ID message regardless of
what the exception's `getMessage()` returned.

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

#### Validation arm precedes channel matching

Before any `MAPPINGS` iteration, `ErrorRouter.dispatch` checks whether the cause chain
contains a `ValidationViolationGraphQLException`. If so:

- If the channel includes a `ValidationHandler` (the `{handler: VALIDATION}` declaration from
  §1), the router fans out `getUnderlyingErrors()` into typed instances and returns the
  populated payload. See §5 for the full contract.
- If the channel does not include a `ValidationHandler`, the router returns a
  `DataFetcherResult` with `data=null` and the carried `List<GraphQLError>` attached verbatim
  as the result's errors. Bean-validation messages are sanitized by definition (`"must not be
  null"`, `"size must be between 1 and 64"`); they're meant to be client-visible, so this path
  doesn't redact. Same client-visible outcome as legacy's "consumer returned `Optional.empty()`
  from `handleValidationException`" fallback, just routed at the fetcher rather than the
  engine.

The arm runs ahead of source-order `MAPPINGS` iteration. A `Throwable`-wide `ExceptionHandler`
declared first cannot shadow it; the validation behaviour is the schema-author-visible
declaration of intent, not an emergent property of dispatch order. Rationale: the carried
`GraphQLError`s have a shape incompatible with the regular `(path, message)` factory contract
when treated as a single error, so dispatching through `MAPPINGS` would only construct one
meaningless instance from the wrapping exception's message. The fan-out arm and the
verbatim-emission arm are the only two correct dispositions.

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

#### No top-level handler

There is no top-level `DataFetcherExceptionHandler` to install. Every Graphitron-emitted
fetcher catches at its own boundary; the unmatched case is dispatched into a redacted
`DataFetcherResult` (see "Concrete dispatch signature" above). graphql-java's default handler
never sees a Graphitron-emitted throw.

What this collapses, compared to legacy `TopLevelErrorHandler`'s three arms:

- `ValidationViolationGraphQLException` is handled by §3's validation arm (channel with
  `VALIDATION` handler → typed fan-out; channel without → carried errors verbatim). Both
  dispositions live at the fetcher catch site. Validation thrown *outside* a fetcher (e.g.
  during a custom directive's instrumentation) hits graphql-java's defaults; that case has
  no Graphitron-emitted control point and nothing useful to add.
- `IllegalArgumentException` becomes a regular dispatch arm: schemas that want the raw
  `getMessage()` client-visible declare `{handler: GENERIC, className:
  "java.lang.IllegalArgumentException"}` on their `@error` type. Unmatched IAEs redact like
  any other unmatched throw. **Behaviour change vs legacy:** legacy's automatic IAE-message
  exposure is removed; schemas relying on it must add the explicit declaration. The migration
  table in §5 already names this transition.
- Generic `Throwable` (including `SQLException`, jOOQ exceptions, NPEs, business exceptions
  with no matching channel) → correlation-ID-logged and redacted at the catch site. Same
  client-visible shape and privacy guarantee as legacy.

Out-of-fetcher exception paths (parsing, validation, coercion, custom scalar errors) still
flow through graphql-java's defaults; Graphitron has no emission control there. None of these
expose SQL or business-exception messages, so the privacy concern doesn't apply. If a future
consumer wants uniform redaction even for these, that's an opt-in extension item, not part of
this plan.

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

- **`InterruptedException`, `IOException` from non-database I/O.** These are infrastructure
  errors that should redact rather than surface as a typed `@error`. They are exempt from the
  wider-or-equal rule: a service method declaring `throws IOException` does not need a
  corresponding channel handler. They flow through the catch arm's redact path, logged with a
  correlation ID (see "Resolved" in Open Questions). Schema authors who want explicit handling
  may still declare a matching `ExceptionHandler`; the exemption only means the absence of one
  is not a classifier error.
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
  through to the redact arm.

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

#### `ValidationViolationGraphQLException` fan-out (the `VALIDATION` handler runtime)

Legacy `SchemaBasedErrorStrategy.handleValidationException` is an *abstract* method: every
consumer subclassed `SchemaBasedErrorStrategy` and wrote a near-identical body that walked
`ValidationViolationGraphQLException.getUnderlyingErrors()` and constructed N typed `@error`
instances from each `GraphQLError`'s `(path, message)`. The rewrite encodes that loop in the
router so the consumer writes zero Java for it. The `VALIDATION` handler kind from §1 is what
the schema author opts in with.

When `ErrorRouter.dispatch` sees a `ValidationViolationGraphQLException` anywhere in the cause
chain, the dispatch differs from the regular one-exception-to-one-error path:

1. Locate the channel's `ValidationHandler`. Per §1's reject rules, at most one exists per
   channel; per §1's structural rule, the parent `@error` type has the `(List<String> path,
   String message)` constructor.
2. Walk `e.getUnderlyingErrors()`. For each carried `GraphQLError`, build a typed instance
   via `mapping.build(err.getPath().stream().map(Object::toString).toList(), err.getMessage())`.
   This is the same factory call the regular dispatch uses; only the *number* of calls differs
   (one per violation instead of one per fetcher).
3. Hand the resulting `List<Object>` to the payload factory. Same return path as a schema-
   mapped match.

If the channel has no `ValidationHandler`, the validation arm in §3 returns a
`DataFetcherResult` with `data=null` and the carried `List<GraphQLError>` attached verbatim,
without consulting `MAPPINGS`. Same client-visible outcome as legacy's "consumer returned
`Optional.empty()` from `handleValidationException`" fallback (the violations surface as
top-level errors in the response), just routed at the fetcher rather than through an engine
handler.

If a schema declares both a `VALIDATION` handler *and* an `ExceptionHandler` whose
`exceptionClass` is `ValidationViolationGraphQLException` or a supertype (e.g.,
`AbortExecutionException`, `RuntimeException`, `Throwable`), the `VALIDATION` arm wins
unconditionally — it runs ahead of `MAPPINGS` iteration so the more general handler cannot
shadow it. (Schema authors who declare such an `ExceptionHandler` alongside a `VALIDATION`
handler probably want the `ExceptionHandler` to catch *other* abort-execution causes; the
spec note in the migration table below covers this.)

The developer-facing flow upstream is unchanged: code calls `recordValidator.validate(...)`,
which throws `ValidationViolationGraphQLException` if violations exist; the trigger is still
developer-invoked, and no auto-wiring is added.

#### Migration: legacy override hooks → schema expression

The legacy `CustomSchemaBasedErrorStrategy` subclass with three-or-four override methods is
the artifact this plan retires. Production override code (canonical example used during the
spec phase: a Sikt consumer with `UgyldigInput` as the typed validation error) maps directly
onto schema declarations:

| Legacy override                           | Replacement in rewrite                                                                                                                |
|-------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| `handleValidationException(...)`          | Declare `{handler: VALIDATION}` on the relevant `@error` type. Router fans out the violations into typed instances per the rule above. |
| `handleIllegalArgumentException(...)`     | Declare `{handler: GENERIC, className: "java.lang.IllegalArgumentException"}` on the `@error` type. Regular one-to-one dispatch. **Behaviour shift**: legacy emitted the raw IAE message at the top level even without a declaration; the rewrite redacts unmatched IAEs like any other unmatched throw. Schemas that relied on the legacy auto-leak must add the declaration explicitly. |
| `createDefaultDataAccessError(...)`       | Declare a *trailing* `{handler: DATABASE}` (no `sqlState`, no `code`) on the channel. §1 lifts to `ExceptionHandler(SQLException)`; §3 source-order makes it the catch-all after specific `SqlStateHandler`/`VendorCodeHandler` entries. |
| `DataAccessExceptionMapper.getMsgFromException` | Out of scope here. Top-level `DataAccessException` no longer exposes a message (§3 generic-with-UUID); schema-mapped `DataAccessException` uses the `description` on the matched handler or the cause's `getMessage()`. A separate roadmap item can add a per-channel message-transform hook if real usage demands one. |

Concrete before/after for the canonical consumer:

```graphql
# After (zero Java for the @error path):
type UgyldigInput @error(handlers: [
  {handler: VALIDATION},                                                # was handleValidationException
  {handler: GENERIC, className: "java.lang.IllegalArgumentException"},  # was handleIllegalArgumentException
  {handler: DATABASE}                                                   # was createDefaultDataAccessError
]) {
    path: [String!]!
    message: String!
}
```

The corresponding `CustomSchemaBasedErrorStrategy` subclass deletes; the consumer's only
remaining Java is the `UgyldigInput(List<String> path, String message)` record itself
(developer-supplied per §2).

---

## Relationship to neighbouring items

- **[`mutations.md`](mutations.md)** (Spec, priority 9) is the carrier for phase 2 onward.
  See the Phasing table near the top: phase 0 lands ahead of `mutations.md`; phase 2 lands
  as a sweep after `mutations.md` Phase 6 is Done. Phase 1 (model + classifier scaffolding)
  is independent and can land in parallel.
- **[`checked-exceptions-typed-errors.md`](checked-exceptions-typed-errors.md)** (Backlog,
  priority 8) is subsumed by §4 / phase 4 above. Retire it when this plan moves to In Review.

---

## Legacy fixture coverage (scope anchor)

The legacy generator's behaviour is locked by ~25 fixtures under
`graphitron-codegen-parent/graphitron-java-codegen/src/test/resources/exceptions/`. The rewrite
spec must commit to which scenarios are reproduced; the table below partitions them into
in-scope / out-of-scope, so the spec phase has a concrete checklist.

**In-scope (phases 0-3 of this item; phase 0 unblocks the union-shaped fixtures by lifting the `PolymorphicReturnType` rejection, phases 1-3 wire the routing):**

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
- `query/`: `@error` channel on a Query field, not just Mutation. Query-side `ErrorChannel`
  wiring is deferred (see Open Questions); this fixture is out of scope for phases 0-3 and
  moves to "rejected" with a documented behaviour shift. Revisit after mutations land.
- `service/`: `@error` on a `@service`-backed mutation.
- `onlyOneHandled/`: partial schema coverage (some `@error` types in the union have no matching
  handler for the thrown exception).
- `noErrors/`, `noHandlers/`: degenerate inputs (payload with no `errors` field; `@error` type
  with empty `handlers` list). Asserts that classification produces "no `ErrorChannel`" for
  the former and a classifier rejection (`UnclassifiedType`) for the latter, since an `@error`
  type with zero handlers is unmatched by anything.

**Net-new fixtures introduced by the rewrite (no legacy counterpart):**

- `validation/`: a single `@error` type with `[{handler: VALIDATION}]`. A pipeline test
  throws `ValidationViolationGraphQLException` with two underlying `GraphQLError`s and asserts
  the response payload contains two typed instances, one per violation, each with the
  violation's `path` and `message`. Covers the §5 fan-out arm.
- `validationWithFallbacks/`: an `@error` type with the canonical
  `[{handler: VALIDATION}, {handler: GENERIC, className: "java.lang.IllegalArgumentException"},
  {handler: DATABASE}]` pattern from the migration table. Three pipeline tests, one per arm.
  This is the consumer-zero-Java acceptance fixture for the override-retirement story.
- `validationWithoutHandler/`: a payload whose `@error` type omits `VALIDATION`, with a fetcher
  that throws `ValidationViolationGraphQLException`. Asserts the violations surface as a
  `DataFetcherResult` with the carried errors verbatim (legacy-compatible fallback per §5's
  no-`ValidationHandler` arm).

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

## User documentation (first-client check)

This section drafts the schema-author-facing documentation. If it does not read simply, the
design needs revision before implementation.

### Declaring typed error payloads

Graphitron routes exceptions thrown inside a generated fetcher into typed GraphQL error
payloads. Declare an `@error` type, add the required `path` and `message` fields, and list
its handlers:

```graphql
type UgyldigInput @error(handlers: [
  {handler: VALIDATION},
  {handler: GENERIC, className: "no.sikt.example.DomainException"},
  {handler: DATABASE, sqlState: "23503"}
]) {
  path: [String!]!
  message: String!
}
```

Return it from a mutation payload's `errors` field:

```graphql
type CreateFilmPayload @record(record: {className: "no.sikt.example.CreateFilmPayload"}) {
  film: Film
  errors: [UgyldigInput!]!
}
```

The generated fetcher catches all exceptions. When one matches a handler, it constructs a
typed instance and places it in `errors`. Unmatched exceptions are logged with a correlation
ID (the current OTel trace ID when a span is active, otherwise a random UUID) and returned
to the client as a redacted error. No internal detail, no stack trace, no exception message.

### Handler kinds

**`GENERIC`** matches by exception class, walking the full cause chain. `className` is
required. `matches` is an optional substring filter on the exception message.

```graphql
{handler: GENERIC, className: "no.sikt.example.DomainException"}
{handler: GENERIC, className: "java.lang.IllegalArgumentException", matches: "unknown film id"}
```

**`DATABASE`** matches any `SQLException` in the cause chain. Narrow by SQL state
(Postgres / standard SQL) or vendor code (Oracle / vendor-specific), but not both at once.
`{handler: DATABASE}` with no discriminator is a catch-all for any `SQLException`.

```graphql
{handler: DATABASE, sqlState: "23503"}  # foreign-key violation
{handler: DATABASE, code: "1"}          # ORA-00001 unique constraint
{handler: DATABASE}                     # catch-all for any SQLException
```

**`VALIDATION`** catches `ValidationViolationGraphQLException` and fans out each constraint
violation into a separate typed instance, each carrying the violated field's path and the
constraint's message. At most one `VALIDATION` entry per error channel; `className`, `sqlState`,
`code`, and `matches` are not allowed alongside it.

```graphql
{handler: VALIDATION}
```

### Dispatch order

Handlers are matched in source order; the first match wins. Place more-specific handlers
before broader ones:

```graphql
type MyError @error(handlers: [
  {handler: DATABASE, sqlState: "23503"},  # specific FK violation first
  {handler: DATABASE},                     # any other SQLException
  {handler: GENERIC, className: "java.lang.Throwable"}  # final catch-all
]) {
  path: [String!]!
  message: String!
}
```

### Unmatched and infrastructure exceptions

Exceptions that match no handler — including infrastructure exceptions like `IOException` and
`InterruptedException` that a service method may declare — are caught at the fetcher boundary
and redacted. The client receives:

```json
{ "errors": [{ "message": "An error occurred. Reference: <id>" }] }
```

where `<id>` is the OTel trace ID when a span is active, otherwise a random UUID. The same ID
appears in the server log at ERROR level alongside the original exception, making it findable
in a distributed trace.

### `@error` type requirements

Every `@error` type must declare `path: [String!]!` and `message: String!`. The
developer-supplied Java class must provide a matching `(List<String> path, String message)`
constructor. Both are validated at build time; a missing field or wrong-shape constructor
produces a classifier error (not a runtime failure).

### Migrating from `SchemaBasedErrorStrategy`

Map each legacy override method to a handler declaration:

| Legacy override | Replacement |
|---|---|
| `handleValidationException(...)` | `{handler: VALIDATION}` |
| `handleIllegalArgumentException(...)` | `{handler: GENERIC, className: "java.lang.IllegalArgumentException"}` |
| `createDefaultDataAccessError(...)` | `{handler: DATABASE}` (place last; catches any remaining `SQLException`) |

Delete the `CustomSchemaBasedErrorStrategy` subclass and the `ExceptionHandlingBuilder` wiring.
The generated fetchers install no engine-level handler.

**Behaviour changes from legacy:**

- `IllegalArgumentException` messages are no longer automatically exposed to clients. Add
  `{handler: GENERIC, className: "java.lang.IllegalArgumentException"}` explicitly if your
  schema relied on the automatic exposure.
- `DATABASE` handlers now match any `SQLException` in the cause chain, not only those wrapped
  in Spring's `DataAccessException`. Spring apps behave identically; non-Spring apps no longer
  need Spring JDBC for database error mapping.

---

## Non-goals

- **Custom `ExecutionStrategy` for non-error reasons.** This item commits to graphql-java's
  default `AsyncExecutionStrategy` plus a `DataFetcherExceptionHandler`. Consumers needing a
  custom strategy for unrelated reasons (custom subscription dispatch, alternative async
  scheduler) install it via `GraphQL.Builder.queryExecutionStrategy(...)` themselves; this is
  not a Graphitron-side concern.
- **Bean-validation triggering.** `RecordValidator` integration with `@Valid`-style
  bean-validation triggering on input types is out of scope. The trigger is still
  developer-invoked: code calls `recordValidator.validate(...)` which throws
  `ValidationViolationGraphQLException`. The §1 `VALIDATION` handler routes the carried
  violations into a typed `@error` payload; the trigger-mechanism for *invoking* the
  validator (auto-wiring, framework integration) is a separate roadmap item.
- **Reverse-engineering `DataAccessException`.** The matcher unwraps `DataAccessException` to
  reach the nested `SQLException` because legacy depends on `spring-jdbc`. The rewrite runtime
  has no spring dependency; the matcher uses reflective `getCause()` walking the chain instead,
  which is what the legacy `streamCauses` already does at
  `GenericExceptionMatcher.java:streamCauses`. The DB-error variant of the matcher checks
  `SQLState`/`ErrorCode` directly on any `SQLException` it finds.
- **A consumer-facing `ExceptionHandlingBuilder` analogue.** Auto-wiring is the goal. The
  rewrite catches at every fetcher; there is no top-level handler to override. Consumers
  who want to intercept exceptions for non-Graphitron-emitted code (parsing, validation,
  custom scalars) install a `DataFetcherExceptionHandler` on `GraphQL.Builder` themselves —
  graphql-java's standard extension point, unrelated to Graphitron's emission.
- **Subscription error paths.** Legacy has no subscription-specific exception handling, and
  the rewrite does not generate subscription fetchers (`FieldBuilder.classifyRootField` rejects
  `Subscription` with a `DEFERRED` rejection at `FieldBuilder.java:1557`). Subscription error
  handling is deferred until subscriptions land as a feature; that future plan owns
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
  graphql-java-vanilla; consumers wire instrumentation via graphql-java's `Instrumentation` API
  on `GraphQL.Builder`.
- **Transaction rollback semantics.** Whether a thrown exception rolls back a JDBC transaction
  is a `DSLContext` / connection-management concern, owned by the consumer's transaction
  strategy (`@Transactional`, manual `dsl.transaction(...)`, etc.). The rewrite's fetcher
  emission does not change transaction shape; an error inside a fetcher propagates through
  whatever transaction wrapper the consumer set up.

---

## Open questions for the Spec phase

- **`@error` on query fields (deferred).** Query-side `ErrorChannel` wiring (phases 2-3
  emitter scope) is out of scope for this item; mutation fields only. Phase 0's `ErrorsField`
  lift still classifies `errors` fields on payloads returned by query fields, but no try/catch
  wrapping or `ErrorMappings` emission targets query fields. Revisit after mutations land and
  the pattern is understood.
- **Mixed unions (some members `@error`, some not).** §2's lift gates on the all-`@error`
  predicate. Spec phase decides whether a mixed union is a build-time error (probable schema
  bug) or a deferred-stub case (re-classify to `PolymorphicReturnType` rejection). Audit
  production schemas for any mixed-union `errors` fields before committing.
- **`@error` Java class generation vs developer-supplied.** Today's contract is
  developer-supplied (matches legacy and `@record`). Generation is feasible (the SDL fully
  determines the Java shape) but breaks the `@record`-symmetric story and changes the
  `code-generation-triggers.md:109` line. Default position: keep developer-supplied. Revisit
  if a consumer survey shows the boilerplate is meaningful.
- **`GraphQLError` extensions/locations on validation fan-out.** §5's fan-out projects each
  carried `GraphQLError` to `(path, message)` only. Legacy did the same; the rewrite preserves
  parity. Spec phase decides whether to flag this as a separate roadmap item for consumers
  who care about extensions/locations on per-violation errors.
- **Fixture coverage.** The current `graphitron-test` schema has no `@error` types. Spec phase
  needs at least four `@error` fixtures, one per Handler variant: a `SqlStateHandler`
  (sqlState match via a Sakila constraint violation; e.g. `23503` foreign-key on the
  `film_actor` link table), a `VendorCodeHandler` (parse-side only; Postgres reports `0` at
  runtime), an `ExceptionHandler` (developer-thrown business exception), and a
  `ValidationHandler` (`{handler: VALIDATION}` plus a fetcher that throws
  `ValidationViolationGraphQLException` with two underlying `GraphQLError`s, asserting
  fan-out into two payload instances). The mutation-bodies fixture-gap closure in
  `mutations.md` is the natural place to extend. The full fixture list to reproduce is in
  the "Legacy fixture coverage" section above.

### Resolved (no longer open)

- **`ErrorRouter` location.** Emitted as a generated class at `<outputPackage>.schema.ErrorRouter`,
  one per output package (alongside `ErrorMappings`). No runtime jar; preserves the
  no-runtime-jar invariant.
- **Top-level handler "slot" on `Graphitron.buildSchema`.** Resolved by removing the need for
  one entirely. Every emitted fetcher catches at its own boundary; the privacy contract
  (correlation-ID-log unmatched, redact message) is enforced at the rewrite-controlled
  emission site rather than delegated to a class consumers must remember to wire. graphql-java's
  default `DataFetcherExceptionHandler` stays default. No new parameter, no `GraphitronOptions`
  carrier, no consumer-facing handler class.
- **Correlation ID for unmatched throws.** `ErrorRouter.redact` uses the current OTel trace ID
  when an active span is present (OTel API on compile classpath), falling back to
  `UUID.randomUUID()`. The emitter detects OTel availability at build time and generates
  accordingly. Clients receive `"An error occurred. Reference: <id>."` The raw exception
  message is never in the response.
- **Field-structural strictness.** Schema-first: every `@error` type must declare
  `path: [String!]!` and `message: String!`; missing or wrong-shaped fields produce
  `UnclassifiedType`. The reflection check on the `(List<String>, String)` constructor is a
  secondary guarantee. Both must pass.
- **`InterruptedException`/`IOException` exemption.** Infrastructure exceptions
  (`InterruptedException`, `IOException`, and similar) are exempt from §4's wider-or-equal
  channel-membership rule. A service method declaring them does not need a corresponding
  channel handler; they fall through to the `ErrorRouter.redact` path. Schema authors who want
  explicit handling may declare a matching `ExceptionHandler` in the channel.
- **Generation-efficiency: matcher dedup.** Resolved via the per-package `ErrorMappings` class
  in §3. Each distinct channel gets one named `Mapping[]` constant; per-fetcher `MAPPINGS`
  fields reference it.
- **Phase ordering with `mutations.md`.** Resolved in the Phasing table near the top: phase 0
  lands ahead of `mutations.md`; phase 2 lands as a sweep after `mutations.md` Phase 6 is Done.
