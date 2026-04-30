---
id: R12
title: "Error-handling parity: emit per-fetcher error channels from `@error`"
status: In Progress
bucket: architecture
priority: 7
theme: mutations-errors
depends-on: [mutations]
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

This item ships as one piece: the model refactor, the classifier lift that unblocks
payload-shaped `errors` fields, the emission of per-package `ErrorRouter` and `ErrorMappings`
helpers, the per-fetcher try/catch wrapper, and the override-retirement migration. Subsumes
[`checked-exceptions-typed-errors.md`](checked-exceptions-typed-errors.md) (Backlog) per §4
below. Independent of [`mutations.md`](mutations.md): the `Optional<ErrorChannel>` slot
attaches to whatever field variants exist when this lands; new variants from `mutations.md`
inherit the slot as they're added.

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

**Schema migration note.** The production schema's `[BehandleSakError!]!` declaration has a
non-null *field* (the trailing `!`); §2b's nullability rule rejects that. Consumers must
migrate the field to `[BehandleSakError!]` (nullable list, element nullability preserved) or
`[BehandleSakError]` (fully nullable). This is a schema-level edit on the consumer side,
expected as part of adopting the rewrite.

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
`createDefaultDataAccessError`), replaced by directive-side declarations per §5's migration
table.

Audit note: those three are the only abstract methods on `SchemaBasedErrorStrategy`
(verified by `grep -E 'abstract' SchemaBasedErrorStrategy.java`). The other extension points
on the class (`handleDataAccessException`, `handleBusinessLogicException`, `createPayload`)
are protected non-abstract methods with default bodies; consumers rarely override them, and
the rewrite's `ErrorRouter.dispatch` covers their default behaviour. If a future audit finds
a consumer subclassing those, the migration table extends; the spec is exhaustive against
the abstract-method set as the surface contract.

---

## Implementation

Ships as one coherent change, not phased. The Direction sections below describe *what* is
done; concrete deliverables across the diff:

- Sealed `Handler` taxonomy (§1) replaces the legacy `Handler` record at
  `GraphitronType.java:202-209`; `ErrorHandlerType` enum gains `VALIDATION`.
- `ErrorsField` variant on `GraphitronField` (§2a); five `PolymorphicReturnType`-on-`@error`
  rejections in `FieldBuilder` lifted to produce it (the production blocker).
- `ErrorChannel` record (§2c); `Optional<ErrorChannel>` slot on every fetcher-emitting field
  variant; channel-detection in the carrier classifier.
- `TypeBuilder.parseErrorHandler` lifts SDL `ErrorHandler` entries to the sealed variants per
  §1's table; the nine reject rules (intra-type and channel-level, including the
  validation-shadowing rule and the `path`/`message`-only structural rule) fire as
  `UnclassifiedType` / `UnclassifiedField`.
- §3's duplicate-criteria classifier check on flattened channel mappings.
- `(List<String>, String)` constructor reflection check on each `@error` type's
  developer-supplied class (parallels `@record` reflection at `TypeBuilder.java:457-459`).
- Per-package `ErrorMappings` and `ErrorRouter` helpers emitted at
  `<outputPackage>.schema.ErrorMappings` / `…ErrorRouter` (§3).
- Try/catch wrapper on every fetcher body (channel → `ErrorRouter.dispatch`; no channel →
  `ErrorRouter.redact`); `.exceptionally(...)` on async fetchers.
- `ValidationHandler` fan-out arm in `ErrorRouter.dispatch` (§5); the
  `CustomSchemaBasedErrorStrategy` consumer subclass and `ExceptionHandlingBuilder` wiring
  go away in `graphitron-test`.
- `@service` / `@tableMethod` declared checked exceptions checked against the field's
  `ErrorChannel` and routed through the same `ErrorRouter.dispatch` (§4).
- [`checked-exceptions-typed-errors.md`](checked-exceptions-typed-errors.md) is deleted when
  this lands.

The whole change should land as one PR (or a tightly-clustered series; implementer's call on
seam value). There is no intermediate state worth shipping: an `errors` fetcher without the
try/catch wrapper would leave the privacy leak from the introduction in place, defeating
half the point.

---

## Direction

### 1. Sealed `Handler` taxonomy

Today `ErrorType.Handler` is the enum-with-shared-fields shape that
[`rewrite-design-principles.md:17-21`](../docs/rewrite-design-principles.adoc#sealed-hierarchies-over-enums-for-typed-information)
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

`matches` on `SqlStateHandler` and `VendorCodeHandler` is a substring filter against the
`getMessage()` of the **matched `SQLException`**, i.e. the cause-chain entry that satisfied the
`sqlState` / `vendorCode` predicate, not the outer wrapper (jOOQ `DataAccessException`,
`CompletionException`, etc.). Walk direction is the same outermost-first scan §3 specifies for
the chain; the first `SQLException` whose `getSQLState()` / `getErrorCode()` matches and whose
message contains the substring wins. `matches` on `ExceptionHandler` follows the same rule
against the matched class instance's `getMessage()`. A null `matches` skips the substring step.

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
| 4 | `DATABASE` with any explicit `className`             | `TypeBuilder.parseErrorHandler` (type-level) | `UnclassifiedType` |
| 5 | `VALIDATION` with any of `className`/`sqlState`/`code`/`matches` | `TypeBuilder.parseErrorHandler` (type-level) | `UnclassifiedType` |
| 6 | `@error` type with fields beyond `path` and `message` | `TypeBuilder.parseErrorHandler` (type-level) | `UnclassifiedType` |
| 7 | More than one `VALIDATION` handler in the same channel | `FieldBuilder` (channel-level, see §2c)    | `UnclassifiedField` on the carrier |
| 8 | Duplicate match-criteria across handlers in the same channel | `FieldBuilder` (channel-level, see §3)   | `UnclassifiedField` on the carrier |
| 9 | `VALIDATION` coexisting with an `ExceptionHandler` whose class is `ValidationViolationGraphQLException` or any supertype | `FieldBuilder` (channel-level, see §3 + §5) | `UnclassifiedField` on the carrier |

Rules 1-6 are intra-`@error`-type and check at parse time. Rules 7-9 span multiple `@error`
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
4. **`DATABASE` with any explicit `className`**: the rewrite's runtime no longer matches on
   class identity for the SQL variants (§3 behaviour change), so any `className` on a
   `DATABASE` handler has no effect and is misleading, including the legacy default
   `org.springframework.dao.DataAccessException`. Reject with a hint pointing at `GENERIC`
   for class-narrowed matching. Schemas migrated from legacy that carry an explicit
   `DataAccessException` className delete the field as part of adopting the rewrite; the
   no-discriminator lift (`{handler: DATABASE}` → `ExceptionHandler(SQLException)`)
   produces the same runtime semantic.
5. **`VALIDATION` with any of `className`, `sqlState`, `code`, `matches`**: the implicit
   exception class is `ValidationViolationGraphQLException`; SQL discriminators are
   irrelevant; `matches` would short-circuit all violations rather than filter individual
   ones, which is not a meaningful intent. Reject with a hint pointing at the override-
   migration table.
6. **`@error` type with fields beyond `path` and `message`**: the runtime factory contract
   is `Mapping.build(List<String> path, String message)`; extra fields would have no source
   and either default to null (violating non-null SDL types) or require a separate
   reflection mechanism the plan does not introduce. Reject with a hint to move custom
   shape onto the carrier payload class instead.
7. **More than one `VALIDATION` handler in the same channel**: validation is a single fan-out
   target per payload; two `VALIDATION` handlers would compete for the same `@error` slot.
   Reject on the carrier, naming both offending `@error` types. Across separate channels on
   different fields, multiple `VALIDATION` handlers are fine, each scoped to its own field's
   payload.
8. **Duplicate match-criteria across handlers in the same channel**: covered by the §3
   classifier check; listed here so the parse-time rules are exhaustive.
9. **`VALIDATION` coexisting with an `ExceptionHandler` whose class is
   `ValidationViolationGraphQLException` or any supertype** (`AbortExecutionException`,
   `RuntimeException`, `Throwable`): VALIDATION runs ahead of `MAPPINGS` iteration (§5), so
   the broader handler would be unreachable for `ValidationViolationGraphQLException`. The
   schema author probably meant the broader handler to catch *other* abort-execution causes;
   ask them to split into two `@error` types, one carrying VALIDATION and one carrying the
   broader handler. Surfaces the shadowing decision at SDL review time, not at runtime.

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

The schema-level check is the [load-bearing classifier check](../docs/rewrite-design-principles.adoc#classifier-guarantees-shape-emitter-assumptions):
the producer (`TypeBuilder.buildErrorType`) wears
`@LoadBearingClassifierCheck(key = "error-type.path-message-fields", ...)` and the consumer
(§3's `ErrorRouter.dispatch` payload-factory call site, which constructs
`new SomeError(path, message)`) wears `@DependsOnClassifierCheck` with the same key. The
reflection check is a secondary guarantee; both must pass for the type to classify.

### 2. Resolve operation-to-error wiring at classify time

Legacy builds `Map<String, PayloadCreator>` at runtime construction because the legacy generator
had no per-field resolution layer. The rewrite already classifies mutation and service fields.
Two model additions, one per side of the carrier/payload split:

#### 2a. `ErrorsField`: the payload-side variant the carrier walks

A new sealed permit on `GraphitronField`:

```java
record ErrorsField(
    String parentTypeName,
    String name,
    SourceLocation location,
    List<ErrorTypeRef> errorTypes      // flat across single / union / interface list shapes
) implements GraphitronField {}
```

`ErrorsField` exists so that `classifyChildFieldOnResultType` (and its `@table`-parent and
`@service`-backed siblings) have an explicit arm to take when they encounter a list-of-error
shape, rather than falling through to `PolymorphicReturnType`'s rejection. The list is flat:
`[SingleError!]` carries one entry, `[SomeUnion!]` or `[SomeInterface!]` carries the
resolved members. (Field-level non-null wrappers like `[X!]!` are rejected by §2b's
nullability rule; the shapes here are the accepted ones.) The polymorphism source is a classification-time concern that does not
survive into the model; downstream the carrier-side `ErrorChannel` consumes `errorTypes`
uniformly. (Earlier drafts modelled three variants; collapsed once it became clear no
consumer branched on the distinction. Same precedent as the `ErrorChannel` flatness in §2c.)

The emission for an `ErrorsField` is a passthrough fetcher: at request time the parent's
payload object already carries the `errors` list (the carrier's `ErrorRouter.dispatch`
produced it, or the service-method body did), so the fetcher reads it directly.

#### 2b. Per-child classifier coordination

The five rejection sites in "Current blocker" share a single check: when the resolved
`ReturnTypeRef.PolymorphicReturnType` names a union or interface whose every member type is an
`@error` type, lift to `ErrorsField` instead of returning `UnclassifiedField`. The check needs
the `BuildContext`'s catalog of `ErrorType`s, which is populated before child-field
classification runs (`TypeBuilder` resolves all object types before `FieldBuilder` walks
fields). Two rules pin the coordination down:

- **The lift is field-shape-driven, not parent-driven.** A field returning `[U]` where `U` is
  a union of `@error` types lifts to `ErrorsField` regardless of whether its parent is `@table`,
  `@record`, or root. The carrier-side `ErrorChannel` (next subsection) is built by the
  *carrier's* classifier, not the child's; the child's job is to commit to a typed shape.
- **The lift gates on the all-`@error` predicate.** A union with one non-`@error` member is
  rejected as `UnclassifiedField` with a "not supported and not planned" reason: every union
  or interface member used as an `errors` field's element type must carry `@error`. The
  classifier message names the offending member and points at adding `@error` or removing it
  from the union. A future schema design that wants mixed lists must propose a directive
  extension; the current rewrite has no machinery for non-error members in an error channel.
- **The `errors` field itself must be nullable.** A non-null declaration on the field
  (`errors: [...]!`) is rejected as `UnclassifiedField`. The payload-side fetcher returns
  `null` for the list on the happy path (no `ErrorChannel` produced one); a non-null field
  declaration would force the runtime to invent an empty list, conflating "no errors thrown"
  with "errors is empty". Element nullability inside the list is unconstrained: `[X]` and
  `[X!]` both classify; `[X]!` and `[X!]!` are rejected. All legacy fixtures use `[X]` (fully
  nullable); the looser rule accommodates schema authors who prefer `[X!]` without breaking
  the legacy shape.

A schema author writing `errors: SomeError` (single, non-list) is rejected: the legacy
fixtures all use list shapes, the runtime fan-out semantics assume a list, and a non-list
single-error shape would not survive the `VALIDATION`-fan-out arm in §5. The reject message
points at the list shape.

#### 2c. `ErrorChannel`: the carrier-side sub-taxonomy

The carrier-side resolution:

```java
record ErrorChannel(
    String errorsFieldName,                  // the "errors" field on the payload
    List<ErrorTypeRef> mappedErrorTypes,     // union members or list-element type
    ClassName payloadClass,                  // the developer-supplied payload class (e.g. FilmPayload)
    List<PayloadConstructorParam> payloadCtorParams,  // ordered all-fields constructor signature; see "Payload-factory contract" below
    String mappingsConstantName              // emitted constant name on ErrorMappings; see §3 dedup rule
) {}

record PayloadConstructorParam(String name, TypeName type, boolean isErrorsSlot) {}
```

The flattened handler list, used by the §3 duplicate-criteria check and the
`mappingsConstantName` dedup-suffix derivation, is a derived view: walk `mappedErrorTypes`
in declaration order and concatenate each resolved `ErrorType`'s `List<Handler>` in source
order. Both consumers compute it on demand from `mappedErrorTypes`; it is not a stored
field on `ErrorChannel`.

`ErrorChannel` is flat; `payloadClass` plus `payloadCtorParams` is what the emitter needs to
synthesize the per-fetcher factory lambda (see "Payload-factory contract" below). A
`PayloadShape` sub-taxonomy was considered but defers to a real second variant; until one
exists, the seal-with-one-permit pattern is overhead. If a future channel shape (e.g. a
list-of-payloads aggregator, or a streaming target) needs to dispatch on shape, lift this
field to a sealed interface at that point.

Carry `Optional<ErrorChannel>` on every field variant whose body is a candidate for the
try/catch / `.exceptionally` wrapper in §3. Concretely:

- `MutationField.DmlTableField` (the sealed supertype introduced by
  [`mutations.md`](mutations.md), covering insert/update/delete/upsert); joins later when
  the new variants land; this plan adds the slot to whatever variants exist when it lands.
- `MutationField.MutationServiceTableField`, `MutationServiceRecordField`.
- `QueryField.QueryServiceTableField`, `QueryServiceRecordField`, `QueryRowsTableField` (and
  any other Query-side variant that resolves to a fetcher body) when the field's payload type
  matches the channel-detection rules below.

The `DmlTableField` reference is a *naming* convenience, not a structural dependency: this
plan adds `Optional<ErrorChannel>` to each affected variant individually as it exists today.
Same pattern as `nodeIdMeta` in [`mutations.md`](mutations.md): classifier resolves once,
emitter dispatches over a settled shape.

The carrier classifier walks the field's payload return type, identifies the `errors` field by
its `ErrorsField` classification (committed in §2a), and resolves which `Handler`s apply. No
global map; each field carries its own list. Query fields are in scope on the same terms as
mutations: any operation field whose return type carries an `errors` field gets an
`ErrorChannel` and a wrapped fetcher. Legacy applied the same rule, and there's no asymmetry
in the runtime contract that would justify deferring queries.

**Channel-detection rules (carrier classifier):**

1. The payload type must be a `@table` or `@record` object whose field set includes exactly
   one `ErrorsField` (committed by §2a). That field's `name` becomes `errorsFieldName`; its
   `mappedErrorTypes` populate the channel.
2. A payload with no `ErrorsField` has no `ErrorChannel`. Throwing inside that fetcher takes
   the top-level path (§3).
3. The detection ignores the field's name; legacy convention is `errors:` but the rewrite keys
   off the structural relationship to `@error` types, not a hardcoded field name. This matches
   how `mutations.md` keys off return-type shape rather than directive presence.
4. A payload with two `ErrorsField` children is rejected (`UnclassifiedField` on the second
   one): the runtime contract assumes one channel per fetcher body. The legacy fixture set
   has been audited and contains no payload with two error fields; the canonical
   `multiple/` fixture uses two separate payloads, each with one `errors` field.

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
- Required interface: the class implements `GraphitronError` (see "Marker interface" below).
  Mandated at classify time alongside the constructor check.
- The class is reflected on the classifier classpath at build time, the same property that
  enables `@record` reflection, with an identical fail-mode if the classpath is missing the
  class.

Generation of the `@error` Java class from SDL was considered and rejected: it would break
symmetry with `@record` (where the class is always developer-supplied), would prevent
consumers from carrying extra fields on the class (a `code` enum, a `severity`, a logged
`cause` reference), and conflicts with the existing `code-generation-triggers.md:109` line
"No generation (error mapping config)". If a future consumer survey shows the boilerplate is
meaningful, a directive extension (e.g. `@error(generate: true)`) can opt into generation
without changing the default.

#### Marker interface: `GraphitronError`

Every `@error` Java class implements a generated marker:

```java
// GENERATED at <outputPackage>.schema.GraphitronError
public interface GraphitronError {
    List<String> path();
    String message();
}
```

Two reasons the marker pulls its weight:

1. **Typed lists at the channel boundary.** The router builds a list of error instances and
   hands it to the payload constructor. Without a marker, that list is `List<Object>` (a union
   channel mixes concrete types). With it, `List<? extends GraphitronError>` is the precise
   signature the developer's payload constructor declares, which is what every `@record` POJO
   is going to spell anyway.
2. **The `Mapping.build` return type collapses to `GraphitronError`** instead of `Object`. The
   §3 router signature is fully typed end-to-end with no widening past the `errors` list's
   element type, which is genuinely heterogeneous in a union channel.

The marker is emitted once per output package (alongside `ErrorMappings` and `ErrorRouter`),
so consumers don't import a runtime jar. Adding the implements clause is a one-line edit on
each developer-supplied `@error` class; the migration table in §5 mentions the touch-up.

A classifier check parallels the constructor check: an `@error` Java class that does not
implement `GraphitronError` produces `UnclassifiedType` with a descriptive reason. Both checks
are [load-bearing](../docs/rewrite-design-principles.adoc#classifier-guarantees-shape-emitter-assumptions)
and wear the matching annotations.

#### Payload-factory contract

The carrier classifier resolves how the error list flows back into the developer's payload
class. The contract is structural, not nominal: the payload's all-fields constructor (the one
generated for a Java `record`, or the one a developer hand-rolls on a POJO) must include
exactly one parameter assignable from `List<? extends GraphitronError>`; the others are bound
to defaults at the catch site.

```java
// developer-supplied (e.g. as a Java record)
public record FilmPayload(Film film, List<? extends GraphitronError> errors) { }
```

Resolution steps in the carrier classifier:

1. Reflect `payloadClass` (the resolved `@record` class) and locate its all-fields constructor.
   Single canonical constructor for a record; a hand-rolled `@record(record: {...})` is
   expected to expose one matching the SDL field order. Multiple matching constructors → reject
   `UnclassifiedField` with a descriptive reason.
2. Walk the constructor parameters in order, recording `(name, type)` for each into
   `payloadCtorParams`. Mark exactly one parameter as the `isErrorsSlot`: the parameter whose
   type is assignable from `List<GraphitronError>` (i.e. `List<GraphitronError>`,
   `List<? extends GraphitronError>`, or `Collection<? extends GraphitronError>` and friends).
   Zero or more than one such parameter → reject `UnclassifiedField` with a descriptive reason
   pointing at the all-fields constructor.
3. Every other parameter is bound to its language default at the call site: `null` for
   reference types, `0` / `0L` / `0.0` / `false` / `'\0'` for primitives. The synthesized
   factory is:

   ```java
   // emitted
   (List<? extends GraphitronError> errors) -> new FilmPayload(null, errors)
   ```

   Multiple non-error parameters → all defaulted, in declaration order. The emitter walks
   `payloadCtorParams` and prints a literal per slot: the errors slot prints the lambda
   parameter; every other slot prints its default literal. No reflection at runtime.

Two consequences worth naming:

- **Defaulting non-error fields is intentional.** A schema's `errors`-only response means
  "no successful result"; legacy `SchemaBasedErrorStrategy.createPayload` defaulted the rest
  via a setter chain. The rewrite collapses that into the constructor call. A schema author
  who needs richer error-path payload state writes a `DataFetchingEnvironment`-aware
  alternative themselves; that's outside the typed-error-channel contract.
- **Constructor-parameter-name preservation matters.** The classifier records `name` for
  audit / diagnostic purposes (the rejection message says "errors slot must be parameter
  `errors`, not `someOtherList`"). The match itself is by **type assignability**, not name,
  so a developer renaming the constructor parameter doesn't break the channel.

The classifier check on the constructor shape is [load-bearing](../docs/rewrite-design-principles.adoc#classifier-guarantees-shape-emitter-assumptions):
the producer (`FieldBuilder` channel-detection) wears
`@LoadBearingClassifierCheck(key = "error-channel.payload-ctor-shape", ...)` and the consumer
(the synthesized factory lambda printed by the fetcher emitter) wears
`@DependsOnClassifierCheck` with the same key.

### 3. Drop the custom `ExecutionStrategy`. Wrap try/catch at the fetcher.

`Graphitron.buildSchema(...)` returns a `GraphQLSchema`; the runtime is constructed by the
consumer via `GraphQL.newGraphQL(schema)`. graphql-java's `DataFetcherExceptionHandler` is
configured on `GraphQL.Builder`, not `GraphQLSchema.Builder`. A top-level handler is
therefore structurally outside the facade's scope: not a class consumers might forget to
install, but a class Graphitron has no place to install. Per-fetcher try/catch is the
natural consequence, and the privacy contract (UUID-log unmatched throws, never expose
internal messages) lives at the rewrite-controlled emission boundary.

Cost: every emitted body gains one wrapper plus one catch arm. Stack traces gain one frame
at the catch site (the thrown exception's own trace is captured before the wrapper sees it).
The wrapper is uniform enough to template via `FetcherRegistrationsEmitter` rather than
woven into each variant's body shape individually.

Both arms of every wrapped fetcher return `DataFetcherResult<P>` (or
`CompletableFuture<DataFetcherResult<P>>` for async). The success path wraps the produced
payload; the catch path receives an already-typed `DataFetcherResult<P>` from the router. One
allocation per fetch on the success path; in exchange, the entire emission boundary is
statically typed and `Object` never appears in a generated signature. graphql-java already
unwraps `DataFetcherResult` uniformly downstream, so the wrapper is invisible to consumers.

For a fetcher with an `ErrorChannel`:

```java
public static DataFetcherResult<FilmPayload> createFilm(DataFetchingEnvironment env) {
    try {
        FilmPayload payload = /* existing INSERT / UPDATE / service body */;
        return DataFetcherResult.<FilmPayload>newResult().data(payload).build();
    } catch (Exception e) {
        return ErrorRouter.dispatch(
            e,
            ErrorMappings.FILM_PAYLOAD,
            env,
            errors -> new FilmPayload(null, errors));   // synthesized per §2c's payload-factory contract
    }
}
```

The lambda body is the synthesized factory from §2c: every constructor parameter except the
errors slot prints its language default; the errors slot binds the lambda parameter. For a
single-parameter payload (`record OnlyErrors(List<? extends GraphitronError> errors)`), the
lambda collapses to a method reference (`OnlyErrors::new`); the multi-parameter case is the
typical one and uses the explicit lambda.

For a fetcher without an `ErrorChannel` (no `@error` types declared on the payload, or no
payload, e.g. a query field returning a scalar):

```java
public static DataFetcherResult<Long> getActorCount(DataFetchingEnvironment env) {
    try {
        Long value = /* existing body */;
        return DataFetcherResult.<Long>newResult().data(value).build();
    } catch (Exception e) {
        return ErrorRouter.redact(e, env);  // logs UUID, returns DataFetcherResult<Long> with data=null + redacted error
    }
}
```

`redact` is generic on the field's data type (`<P> DataFetcherResult<P>`); the call site's
target type infers `P` so the catch arm fits the method's declared return type without an
explicit type witness.

The wrapper catches `Exception`, not `Throwable`: `Error` and its subclasses
(`OutOfMemoryError`, `StackOverflowError`, `LinkageError`, `AssertionError`) propagate. The
JVM is in degraded state for any of those and should fail fast rather than respond cleanly
with a UUID-referenced redaction; the privacy contract applies to exceptions a fetcher might
plausibly throw, not to fatal-VM conditions.

The async `.exceptionally(...)` arm (see "Async fetcher path") receives `Throwable` per
`CompletableFuture`'s API, which can carry an `Error` wrapped in `CompletionException`.
`ErrorRouter.dispatch` accepts `Throwable` and rethrows any unwrapped `Error` cause before
running the matcher: same fail-fast guarantee, just enforced one layer down.

`ErrorRouter.dispatch` and `ErrorRouter.redact` never rethrow non-`Error` causes. The
unmatched arm in `dispatch` falls through to the same redaction logic as `redact`.
graphql-java's default `DataFetcherExceptionHandler` therefore never fires for a
Graphitron-emitted fetcher except when the cause is an `Error`. The consumer wires nothing.

`ErrorMappings` lives at `<outputPackage>.schema.ErrorMappings` and defines one named
constant per distinct channel-flattened mapping list. The catch arm references the relevant
constant directly; multiple fetcher methods on the same `*Fetchers` class can target
different payloads (e.g., `createFilm` -> `ErrorMappings.FILM_PAYLOAD`, `deleteFilm` ->
`ErrorMappings.DELETE_FILM_PAYLOAD`), each catch arm naming its own constant. Example shape:

```java
// GENERATED: ErrorMappings
public final class ErrorMappings {
    public static final ErrorRouter.Mapping[] FILM_PAYLOAD = new ErrorRouter.Mapping[] { ... };
    public static final ErrorRouter.Mapping[] BEHANDLE_SAK_PAYLOAD = new ErrorRouter.Mapping[] { ... };
    // ...
}
```

Three reasons the constants live on `ErrorMappings` rather than inlined per fetcher:

1. **Dedup.** A schema with N fetchers each mapping the same K `@error` types produces K mapping
   instances total instead of K·N. For the canonical Sikt schema this is a real saving, not just
   a code-smell concern.
2. **Testability.** Pipeline tests assert SDL → TypeSpec at one site (`ErrorMappings`) per
   channel, rather than chasing inline literals across N `*Fetchers` classes. Aligns with the
   tier model in `rewrite-design-principles.md:86-88`; fetcher pipeline tests focus on their
   own variant and just confirm the static reference.
3. **Naming as classifier output.** The constant name is produced by the classifier
   (`ErrorChannel.mappingsConstantName`), not invented at print time. This makes the cross-class
   reference eligible for the [load-bearing classifier check](../docs/rewrite-design-principles.adoc#classifier-guarantees-shape-emitter-assumptions)
   pattern: producer wears `@LoadBearingClassifierCheck(key = "error-channel.mappings-constant", ...)`,
   consumer wears `@DependsOnClassifierCheck`. Closes the same loop §1 already opened for the
   `(List<String>, String)` constructor.

The constant name is derived from the *payload class* (e.g. `FILM_PAYLOAD` ←
`FilmPayload`), and the dedup key is the flattened mapping-list contents (variant + criteria +
description, in source order). Two fetchers returning the same payload class with identical
channel declarations share one constant; two fetchers returning the same payload class with
*different* channel declarations get distinct constants suffixed by an 8-hex-char (32-bit)
prefix of the SHA-256 of the canonicalized mapping list (`FILM_PAYLOAD`,
`FILM_PAYLOAD_A1B2C3D4`). Two fetchers returning *different* payload classes never share a
constant even if their mapping lists happen to be equal, since the emitted `payloadFactory`
differs and a shared constant would be misleading. A 32-bit suffix keeps collision probability
negligible for any plausible schema size; on the rare collision the classifier rejects with a
descriptive reason and the implementer widens the suffix as a one-line edit.

The `Mapping[]` is emitted as a literal array; consumers should treat it as immutable. (The
generator does not wrap with `List.copyOf` to avoid the wrapping overhead on the hot path; the
guarantee is editorial: no Graphitron-emitted code mutates the array.)

`ErrorRouter` is also emitted at `<outputPackage>.schema.ErrorRouter` (one per output package,
not one per `*Fetchers` class). It walks the cause chain with the legacy matcher logic and
returns either the populated payload (schema-mapped) or a redacted `DataFetcherResult`
(unmatched). Consumers write no error-handling Java at all in the common case; the legacy
`SchemaBasedErrorStrategy` subclass disappears in favour of `@error` directive declarations
(see §5's migration table) and there is no engine-level handler to install (see "No top-level
handler" below).

Both `ErrorRouter` and `ErrorMappings` are emitted, not shipped as a runtime jar; this preserves
the [rewrite-builds-independently invariant](../docs/rewrite-design-principles.adoc#rewrite-builds-independently-of-legacy-graphitron-modules).

`ErrorRouter.Mapping` mirrors §1's classifier-side `Handler` taxonomy with the same permit
list. The duplication is forced by the no-runtime-jar invariant: emitted runtime code cannot
import classes from `graphitron`'s model package, so the runtime needs its own shape. A
future variant added to `Handler` must be added to `Mapping` in the same edit.

#### Concrete dispatch signature

```java
public final class ErrorRouter {
    /**
     * Channel-mapped dispatch:
     *   matched   -> DataFetcherResult.newResult().data(payloadFactory.apply(errors)).build()
     *   unmatched -> redacted DataFetcherResult (data=null + correlation-ID error).
     */
    public static <P> DataFetcherResult<P> dispatch(
            Throwable thrown,
            Mapping[] mappings,                                            // an entry on ErrorMappings, e.g. ErrorMappings.FILM_PAYLOAD
            DataFetchingEnvironment env,                                   // for path/source-location
            Function<List<? extends GraphitronError>, P> payloadFactory    // (errors) -> payload
    ) { ... }

    /** No-channel disposition: log with correlation ID, return DataFetcherResult with data=null and a redacted error. */
    public static <P> DataFetcherResult<P> redact(Throwable thrown, DataFetchingEnvironment env) { ... }

    public sealed interface Mapping
            permits ExceptionMapping, SqlStateMapping, VendorCodeMapping, ValidationMapping {
        GraphitronError build(List<String> path, String message);  // constructs the @error instance
    }
}
```

`dispatch` and `redact` are generic on `P`, the field's payload type. Both arms always return
`DataFetcherResult<P>`; graphql-java unwraps that uniformly downstream regardless of whether
the result was produced via a successful payload or a redacted error. No `Object` widening
remains in the router signature, the synthesized factory, or the wrapped fetcher's static
return type. `Mapping.build` returns `GraphitronError` (per §2c's marker), so the heterogeneous
errors list a union channel produces is statically `List<? extends GraphitronError>` from the
build site through the factory call.

`ValidationMapping` is in the sealed `Mapping` set so the dispatch arm can locate the
validation `@error` factory by exhaustive switch (see §5's fan-out: it calls `mapping.build(...)`
once per underlying violation). It is *not* iterated in the regular source-order match loop;
the validation arm in "Validation arm precedes channel matching" runs ahead of `MAPPINGS`
iteration and either fans out into the validation factory or routes unhandled. Schema authors
see it as one entry in the channel; the runtime contract is the special-case arm.

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
is `UUID.randomUUID().toString()`. The raw exception message is never put into the response.
This is the privacy property legacy preserved via its top-level handler; the rewrite preserves
it at the fetcher catch site instead.

(Tracing-aware correlation, e.g. using an active OpenTelemetry trace ID instead of a fresh
UUID, is a deliberate non-goal here. If a consumer wants it later, the fix is a small SPI: a
`CorrelationIdProvider` interface a consumer can install on `Graphitron`'s builder. Out of
scope for this item; file as Backlog when demand arises.)

The emitted call site passes the per-fetcher synthesized factory lambda (§2c's
"Payload-factory contract"); this mirrors the legacy `PayloadCreator` shape
(`ExceptionStrategyConfiguration.java:30-32`, invoked at `SchemaBasedErrorStrategy.java:118-122`)
but binds the errors list explicitly through the all-fields constructor rather than via a
setter chain.

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
`TypeFetcherGenerator.java:1463-1505`): the fetcher returns `loader.load(key, env)` and the
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
public static CompletableFuture<DataFetcherResult<FilmPayload>> createFilm(DataFetchingEnvironment env) {
    // ... DataLoader registration ...
    return loader.load(key, env)                                              // CompletableFuture<FilmPayload>
        .thenApply(payload -> DataFetcherResult.<FilmPayload>newResult().data(payload).build())
        .exceptionally(t -> ErrorRouter.dispatch(
            t,
            ErrorMappings.FILM_PAYLOAD,
            env,
            errors -> new FilmPayload(null, errors)));
}
```

The `.thenApply` wraps the success arm in `DataFetcherResult<P>` so the future's element type
matches the `.exceptionally` lambda's return type; both arms produce `DataFetcherResult<P>`
and the future's static type is `CompletableFuture<DataFetcherResult<P>>`. Whether a fetcher
is sync (returns `DataFetcherResult<P>` directly) or async (returns
`CompletableFuture<DataFetcherResult<P>>`) is a property of the existing classifier output (the
field variant plus its `ReturnTypeRef.wrapper()`); §2's `ErrorChannel` does not need to encode
it. The emitter forks on the field variant, as it already does for the rest of the body shape;
both forks call into the same `ErrorRouter.dispatch`, so the runtime contract is uniform.

For a no-channel async fetcher (e.g. a query field returning `[Film]`), the same
`.thenApply` + `.exceptionally(t -> ErrorRouter.redact(t, env))` pair applies; `redact`'s `<P>`
infers from the `.exceptionally` target type, no witness needed.

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

This is a [load-bearing classifier check](../docs/rewrite-design-principles.adoc#classifier-guarantees-shape-emitter-assumptions):
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
[`rewrite-design-principles.md:65-69`](../docs/rewrite-design-principles.adoc#validator-mirrors-classifier-invariants).
That replaces the legacy "silently swallowed at runtime" behaviour with a
[load-bearing classifier check](../docs/rewrite-design-principles.adoc#classifier-guarantees-shape-emitter-assumptions),
annotated with `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` so the audit test
catches any future emitter that grows to consume `ErrorChannel` without the corresponding
classifier rejection.

#### Match rule

A handler covers a declared exception when the handler would catch it at runtime. For an
`ExceptionHandler`, that means the declared exception must be assignable to the handler's
`exceptionClass` (the handler's class is a supertype of, or equal to, the declared class).
An `ExceptionHandler(SQLException)` covers a method declaring `throws SQLDataException`; a
method `throws Throwable` is covered only by an `ExceptionHandler(Throwable)`.

For `SqlStateHandler` / `VendorCodeHandler`, a declared `SQLException` (or any subclass) is
covered, because both variants match any `SQLException` in the cause chain. A declared
exception that is not assignable to `SQLException` is not covered by these variants alone
and must also have a covering `ExceptionHandler`.

#### Special cases

- **`InterruptedException`, `IOException` from non-database I/O.** These are infrastructure
  errors that should redact rather than surface as a typed `@error`. They are exempt from the
  match rule: a service method declaring `throws IOException` does not need a
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
3. Hand the resulting `List<? extends GraphitronError>` to the payload factory (the
   per-fetcher synthesized lambda from §2c's "Payload-factory contract"); wrap the produced
   payload in `DataFetcherResult.<P>newResult().data(payload).build()`. Same return path as a
   schema-mapped match.

If the channel has no `ValidationHandler`, the validation arm in §3 returns a
`DataFetcherResult` with `data=null` and the carried `List<GraphQLError>` attached verbatim,
without consulting `MAPPINGS`. Same client-visible outcome as legacy's "consumer returned
`Optional.empty()` from `handleValidationException`" fallback (the violations surface as
top-level errors in the response), just routed at the fetcher rather than through an engine
handler.

A schema cannot declare both a `VALIDATION` handler and an `ExceptionHandler` whose class
is `ValidationViolationGraphQLException` or a supertype in the same channel: §1 reject rule
9 catches the combination at classify time. The runtime "VALIDATION ahead of MAPPINGS" arm
is therefore an unambiguous priority over unrelated handlers, not a tie-break against
intentionally-broad ones.

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
remaining Java is the `UgyldigInput` record itself (developer-supplied per §2), with the
`(List<String> path, String message)` constructor and the `GraphitronError` marker:

```java
public record UgyldigInput(List<String> path, String message) implements GraphitronError {}
```

Adding `implements GraphitronError` to existing `@error` classes is the one schema-touching
step beyond the directive change. The marker lives at `<outputPackage>.schema.GraphitronError`
(generated, no runtime jar).

---

## Relationship to neighbouring items

- **[`mutations.md`](mutations.md)** (Spec, priority 9): no scheduling dependency in either
  direction. The `Optional<ErrorChannel>` slot attaches to existing field variants when this
  item lands; new variants from `mutations.md` (the `DmlTableField` family) inherit the slot
  when they're added. Either order works.
- **[`checked-exceptions-typed-errors.md`](checked-exceptions-typed-errors.md)** (Backlog,
  priority 8) is subsumed by §4. Retire it when this plan moves to In Review.

---

## Legacy fixture coverage (scope anchor)

The legacy generator's behaviour is locked by ~25 fixtures under
`graphitron-codegen-parent/graphitron-java-codegen/src/test/resources/exceptions/`. The rewrite
spec must commit to which scenarios are reproduced; the table below partitions them into
in-scope / out-of-scope, so the spec phase has a concrete checklist.

**In-scope (this item):**

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
- `query/`: `@error` channel on a Query field. Query fields are in scope; the rewrite wires
  them on the same terms as mutations.
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
  same channel, both declared `[{handler: DATABASE}]` with no discriminator. Both lift to
  `ExceptionHandler(SQLException, null)`, which collide under §3's duplicate-criteria check.
  Legacy permitted it silently and the second was unreachable. Update the expected output to
  a classifier error.

The pipeline-test set in `graphitron-test` covers each row above with one execution-test
asserting the actual on-the-wire error shape; the per-row fixtures live in `graphitron-test`'s
SDL alongside the mutation fixtures (the mutation-bodies fixture-gap closure in
[`mutations.md`](mutations.md)).

### New `graphitron-test` fixtures (to introduce with this item)

The current `graphitron-test` schema has no `@error` types. Introduce at least four `@error`
fixtures, one per Handler variant, each with one execution-test asserting the on-the-wire
shape:

- A `SqlStateHandler` fixture: sqlState match via a Sakila constraint violation; e.g. `23503`
  foreign-key on the `film_actor` link table.
- A `VendorCodeHandler` fixture: parse-side only (Postgres reports `0` at runtime, so the
  classifier coverage is the test value, not a runtime match).
- An `ExceptionHandler` fixture: a developer-thrown business exception caught by class match.
- A `ValidationHandler` fixture: `{handler: VALIDATION}` plus a fetcher that throws
  `ValidationViolationGraphQLException` with two underlying `GraphQLError`s, asserting fan-out
  into two payload instances.

The full legacy fixture list above is the comparison anchor; these four are the minimum
pipeline coverage for the runtime wiring.

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
  errors: [UgyldigInput]
}
```

The generated fetcher catches all exceptions. When one matches a handler, it constructs a
typed instance and places it in `errors`. Unmatched exceptions are logged with a random UUID
correlation ID and returned to the client as a redacted error. No internal detail, no stack
trace, no exception message.

### `@error` type requirements

Every `@error` type must declare `path: [String!]!` and `message: String!`. The
developer-supplied Java class must provide a matching `(List<String> path, String message)`
constructor. Both are validated at build time; a missing field or wrong-shape constructor
produces a classifier error (not a runtime failure).

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

A mutation with two violated constraints produces two entries in `errors`, one per violation:

```json
{
  "data": { "createFilm": { "film": null, "errors": [
    { "path": ["input", "title"],   "message": "must not be blank" },
    { "path": ["input", "release"], "message": "must not be null" }
  ]}}
}
```

### Dispatch order

Two rules combine to decide which handler fires. They look similar at a glance, so it's worth
seeing each one in isolation before the cases that mix them.

**Rule 1: source order, first match wins.** Handlers are tried top-to-bottom in the array.
The first one whose criteria are satisfied produces the typed `@error` instance; later
handlers are not consulted. Place more-specific handlers before broader ones:

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

A `Throwable`-shaped FK violation hits the first handler (the sqlState `23503` matches), not
the third, because rule 1 stops at the first match.

**Rule 2: each handler walks the entire cause chain.** A single handler doesn't only look at
the outermost exception; it scans the chain (outermost to innermost) for an exception that
satisfies its criteria. So a `MyDomainException` wrapping a `SQLException` satisfies a
`{handler: GENERIC, className: "java.sql.SQLException"}` declaration even though
`MyDomainException` is the outer throw.

Combining the two: when an exception wraps a cause, **source order decides which handler
fires, not which exception in the chain is "outermost"**:

```graphql
{handler: GENERIC, className: "java.sql.SQLException"},     # rule 1: tried first
{handler: GENERIC, className: "com.example.MyDomainException"}  # tried second
```

A `MyDomainException` wrapping a `SQLException` fires the **SQLException** handler. Both
match (rule 2 finds the inner `SQLException` for the first handler, the outer
`MyDomainException` for the second), so rule 1 picks the first declaration. To match the
outer exception specifically, swap the order.

### Unmatched and infrastructure exceptions

Exceptions that match no handler, including infrastructure exceptions like `IOException` and
`InterruptedException` that a service method may declare, are caught at the fetcher boundary
and redacted. The client receives:

```json
{ "errors": [{ "message": "An error occurred. Reference: <id>" }] }
```

where `<id>` is a random UUID. The same ID appears in the server log at ERROR level alongside
the original exception, so an operator can find the cause from a client's reference.

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
  custom scalars) install a `DataFetcherExceptionHandler` on `GraphQL.Builder` themselves;
  that's graphql-java's standard extension point, unrelated to Graphitron's emission.
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

## Open questions

(None remaining for the Spec phase. Items previously marked open are folded into the
Direction sections above; deferred extensions are noted as Backlog candidates in their
respective subsections, not here.)

### Resolved

- **`ErrorRouter` location.** Emitted as a generated class at `<outputPackage>.schema.ErrorRouter`,
  one per output package (alongside `ErrorMappings`). No runtime jar; preserves the
  no-runtime-jar invariant.
- **Top-level handler "slot" on `Graphitron.buildSchema`.** Resolved by removing the need for
  one entirely. Every emitted fetcher catches at its own boundary; the privacy contract
  (correlation-ID-log unmatched, redact message) is enforced at the rewrite-controlled
  emission site rather than delegated to a class consumers must remember to wire. graphql-java's
  default `DataFetcherExceptionHandler` stays default. No new parameter, no `GraphitronOptions`
  carrier, no consumer-facing handler class.
- **Correlation ID for unmatched throws.** `ErrorRouter.redact` uses
  `UUID.randomUUID().toString()`. Clients receive `"An error occurred. Reference: <id>."` The
  raw exception message is never in the response. Tracing-aware correlation (e.g. an active
  OpenTelemetry trace ID) is a deferred extension via a future `CorrelationIdProvider` SPI;
  not part of this item.
- **Field-structural strictness.** Schema-first: every `@error` type must declare
  `path: [String!]!` and `message: String!`; missing or wrong-shaped fields produce
  `UnclassifiedType`. The reflection check on the `(List<String>, String)` constructor is a
  secondary guarantee. Both must pass.
- **`InterruptedException`/`IOException` exemption.** Infrastructure exceptions
  (`InterruptedException`, `IOException`, and similar) are exempt from §4's match-rule
  channel-membership rule. A service method declaring them does not need a corresponding
  channel handler; they fall through to the `ErrorRouter.redact` path. Schema authors who want
  explicit handling may declare a matching `ExceptionHandler` in the channel.
- **Generation-efficiency: matcher dedup.** Resolved via the per-package `ErrorMappings` class
  in §3. Each distinct channel gets one named `Mapping[]` constant; per-fetcher `MAPPINGS`
  fields reference it.
- **Phase ordering with `mutations.md`.** Resolved by collapsing the phasing: this item ships
  as a single change; `mutations.md` is independent. See "Implementation outline" near the top.
- **Mixed unions.** Resolved as `UnclassifiedField` rejection ("not supported and not
  planned"); see §2b.
- **`@error` Java class generation.** Resolved as developer-supplied (matches `@record` and
  legacy); see §2c. A directive extension to opt into generation can be filed later if a
  consumer survey shows boilerplate is meaningful.
- **`GraphQLError` extensions/locations on validation fan-out.** Resolved as deferred: the
  `(path, message)` projection matches legacy parity; per-violation extensions/locations
  would change the developer-supplied `@error` constructor contract and are out of scope.
  A future directive extension (e.g. `{handler: VALIDATION, withExtensions: true}`) can opt
  in if demand arises.
- **Two-`ErrorsField` payloads.** Resolved by audit: no legacy fixture has two error fields
  on one payload; the rejection rule in §2c stands.
- **`unionMultipleDatabaseHandlers/` partition.** Resolved as Rejected by audit: both
  members declare `[{handler: DATABASE}]` with no discriminator, lifting to identical
  `ExceptionHandler(SQLException, null)` and colliding under §3's duplicate-criteria check.
- **Query fields.** Resolved as in-scope on the same terms as mutations; see §2c.
