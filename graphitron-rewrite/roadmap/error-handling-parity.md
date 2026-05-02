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
`List<ErrorType.Handler>` (`TypeBuilder.buildErrorType`). Nothing
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

| Caller                                  | Context                                                |
|-----------------------------------------|--------------------------------------------------------|
| `classifyQueryField`                    | Query root `@service` returning a polymorphic type     |
| `classifyMutationField`                 | Mutation root `@service` returning a polymorphic type  |
| `classifyChildFieldOnResultType`        | Child `@service` on a `@record` parent                 |
| `classifyChildFieldOnResultType`        | Child non-service field on a `@record` parent          |
| `classifyChildFieldOnTableType`         | Child `@service` on a `@table` parent                  |

Each rejection arm is a `case ReturnTypeRef.PolymorphicReturnType ... -> new
UnclassifiedField(...)` switch arm with reason `"@service returning a polymorphic
type is not yet supported"` (or `"@record type returning a polymorphic type is not
yet supported"` on the non-service `@record`-parent arm); locate them via that
predicate rather than by line, since the file churns.

The canonical hit is the consumer's `BehandleSakPayload.errors: [BehandleSakError!]!` (a union of
`@error` types) on a `@record`-typed payload returned by a `@service`-backed mutation. The
mutation root field hits the `classifyMutationField` arm; the payload's own `errors` field
hits the non-service `@record`-parent arm of `classifyChildFieldOnResultType` once the
parent does classify. All five must be lifted in the same classifier pass: an
`errors`-shaped field can land on any of them depending on whether the carrier is
service-backed and whether the parent is `@record` or `@table`. Other polymorphic
uses (non-`@error` interface/union returns) stay rejected as before; the lift is
gated on "every member type is an `@error` type".

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

The Direction sections below describe the target design; the deliverables list
tracks code state. No consumer-visible surface ships until the per-fetcher
wrapper + dispatch arm lands; phasing within is the implementer's call.

**Landed:**

- Sealed `Handler` taxonomy (§1) at `GraphitronType.ErrorType.Handler` with the four
  permits `ExceptionHandler` / `SqlStateHandler` / `VendorCodeHandler` / `ValidationHandler`.
  The `ErrorHandlerType` enum already carries `GENERIC | DATABASE | VALIDATION`. The
  legacy enum-with-shared-fields shape and its `ErrorHandlerType.GENERIC | DATABASE`
  predecessor were replaced in the same edit.
- `ChildField.ErrorsField` (§2a) and the five `PolymorphicReturnType`-on-`@error`
  rejection lifts in `FieldBuilder.liftToErrorsField` (the production blocker). A
  field whose return type is a union of, or interface implemented by, `@error` types
  now produces `ErrorsField` instead of falling through to `UnclassifiedField`.
  Mixed-`@error`/non-`@error` unions and non-null `errors` lists are rejected with
  precise `AUTHOR_ERROR` reasons; pure non-`@error` polymorphic returns still fall
  through to the existing "polymorphic not supported" `DEFERRED` rejection.
- `ErrorsField` dispatch wiring (§2a continued): the variant moved from
  `NOT_IMPLEMENTED_REASONS` into `IMPLEMENTED_LEAVES`; `FetcherEmitter.dataFetcherValue`
  emits `PropertyDataFetcher.fetching(name)` for it (graphql-java's reflective accessor
  reaches the parent payload's `errors` accessor regardless of whether the backing
  class is a Java record, a JavaBean, or untyped). The `*Fetchers` class emits no
  per-field method; the wiring entry is the entire footprint, parallel to `PropertyField` /
  `RecordField`. Consumer schemas with `errors`-shaped fields now build and classify
  cleanly. The runtime carrier (per-error dispatch + try/catch wrapping) lands later
  in this plan via the channel-detection phase.
- `ErrorChannel` record (§2c) with typed `payloadClass: ClassName`,
  `payloadCtorParams.type: TypeName`, and resolved `mappedErrorTypes:
  List<GraphitronType.ErrorType>`; `WithErrorChannel` capability interface implemented
  by every fetcher-emitting field variant (`MutationField` permits + `QueryField`
  service variants).
- Carrier classifier (§2c continued): `FieldBuilder.resolveErrorChannel` walks the
  payload's GraphQL field set looking for an `errors`-shaped field, reflects on the
  developer-supplied payload class's canonical (all-fields) constructor, identifies the
  unique errors-slot parameter, captures each non-error slot's language default literal,
  and resolves the `mappingsConstantName` (SCREAMING_SNAKE on the payload class simple
  name; the §3 dedup hash-suffix lands with `ErrorMappings` emission). Wired into the
  five `WithErrorChannel` construction sites (`MutationServiceTableField`,
  `MutationServiceRecordField`, `MutationInsertTableField`,
  `MutationUpdateTableField`, `MutationDeleteTableField`,
  `MutationUpsertTableField`, `QueryServiceTableField`,
  `QueryServiceRecordField`) so the slot is populated whenever the payload qualifies.
  Currently gated on `ResultReturnType` payloads (`@record`); `@table`-returning
  fetchers carry an empty channel pending a payload-factory shape for jOOQ Record
  returns. The errors-slot is identified by SDL-to-record-component index: the
  SDL field that classifies as `ErrorsField` has an index in the payload type's
  field declaration order; the constructor parameter at that same index is the
  errors slot. The slot's element type is `Object`; the classifier records the
  slot's index and per-other-slot `defaultLiteral`.
- §1 channel-level reject rule 7 in the carrier classifier:
  `FieldBuilder.checkChannelLevelHandlerRules` rejects a channel that carries more
  than one `ValidationHandler` across its flattened handler list (a channel has at
  most one validation target per payload). Surfaces as `UnclassifiedField` on the
  carrier with a reason naming the offending `@error` types. Rule 9 (the
  `ValidationViolationGraphQLException`-shadowing check) is in the codebase; it
  retires together with the exception class in the native-validation chunk below.
- `(List<String>, String)` constructor reflection check on each `@error` type's
  developer-supplied backing class via co-located `@record(record: {className:
  ...})`, populating `ErrorType.classFqn`. Transitional state: the developer-supplied
  data class is retired in the source-direct unwind below.
- §3 rule 8 (duplicate-criteria classifier check) in the carrier classifier:
  `FieldBuilder.checkDuplicateMatchCriteria` walks the channel's flattened handler list
  and rejects two intra-variant handlers with identical match-criteria tuples
  (`(exceptionClassName, matches)` for `ExceptionHandler`, `(sqlState, matches)` for
  `SqlStateHandler`, `(vendorCode, matches)` for `VendorCodeHandler`). Optional
  `matches` equality treats absent and present as distinct values, and an absent
  `matches` collides only with another absent `matches`. Cross-variant overlap is
  intentionally allowed (an `ExceptionHandler(SQLException)` and a
  `SqlStateHandler("23503")` both match a Postgres FK violation; §3 source-order picks
  the first). Catches both intra-type duplicates (within one `@error` type's `handlers`
  array) and cross-type duplicates (two `@error` types in the same channel). Surfaces
  as `UnclassifiedField` on the carrier with a reason naming both colliding handler
  fingerprints, closing the legacy gap where
  `ExceptionStrategyConfigurationGenerator` silently allowed duplicates.
- `ExceptionHandler.exceptionClassName` resolution check at parse time:
  `TypeBuilder.validateExceptionClass` reflects the className with `Class.forName`
  on the classifier classpath and verifies the resolved class extends
  `java.lang.Throwable`. A non-resolvable or non-Throwable className surfaces the
  parent `ErrorType` as `UnclassifiedType` with a descriptive reason, mirroring the
  `@record(record: {className: ...})` reflection check in
  `TypeBuilder.buildResultType`. Applies to the GENERIC arm; the no-discriminator
  DATABASE lift to `ExceptionHandler(SQLException)` is unconditionally resolvable so
  the check is skipped there.
- `TypeBuilder.parseErrorHandler` parse-time lift (§1's table) and intra-type reject
  rules 1-5: each SDL `{handler: ...}` entry returns one of the four sealed
  variants by reading the discriminator and the optional `className`/`sqlState`/
  `code`/`matches`/`description` fields. `GENERIC` lifts to `ExceptionHandler`;
  `DATABASE` with `sqlState` lifts to `SqlStateHandler`, with `code` to
  `VendorCodeHandler`, with neither to `ExceptionHandler(java.sql.SQLException)`;
  `VALIDATION` lifts to `ValidationHandler`. A missing or unknown `handler` value,
  `GENERIC` without `className` (rule 1), `GENERIC` carrying `sqlState` or `code`
  (rule 2), `DATABASE` carrying both `sqlState` and `code` (rule 3), `DATABASE`
  carrying any explicit `className` (rule 4), or `VALIDATION` carrying any of
  `className`/`sqlState`/`code`/`matches` (rule 5) appends a reason and surfaces
  the parent `ErrorType` as `UnclassifiedType` per §1's table.
  `TypeBuilder.buildErrorType` enforces rule 6 (the `path: [String!]!` /
  `message: String!` structural contract on every `@error` type, rejecting missing
  fields, wrong-shape declarations, and any extra field beyond `path` and
  `message`). Rules 7-9 are already tracked separately (channel-level, in
  `FieldBuilder`).
- `TypeBuilder.buildErrorType` wears `@LoadBearingClassifierCheck(key =
  "error-type.path-message-fields")` (the producer side; the consumer annotation lands
  with the dispatch arm).
- `ErrorRouter.redact` emitted via `ErrorRouterClassGenerator` at
  `<outputPackage>.schema.ErrorRouter`; logs the original throw under a fresh UUID
  correlation ID and returns a `DataFetcherResult` carrying only the correlation ID.
- `ErrorRouter.dispatch` arm + nested `Mapping` taxonomy (§3): `Mapping` interface
  with `match(throwable)` and `description()`; three concrete implementations
  (`ExceptionMapping`, `SqlStateMapping`, `VendorCodeMapping`) carrying the
  per-variant criteria. `payloadFactory` on `dispatch` is typed as
  `Function<List<?>, P>`. The dispatch method walks `mappings` in source order;
  for each one, it walks the cause chain outermost-first and the first match
  places the matched throwable directly into the errors list. Falls through to
  `redact` on no match. (The legacy `ValidationViolationGraphQLException`
  fan-out arm and `ValidationMapping` retired with the native Jakarta
  validation chunk; validation now runs as a wrapper pre-execution step and
  never reaches the dispatcher.)
- `ErrorMappings` helper emitted via `ErrorMappingsClassGenerator` at
  `<outputPackage>.schema.ErrorMappings`: walks every classified `WithErrorChannel`
  field, groups by `ErrorChannel.mappingsConstantName`, and emits one
  `public static final ErrorRouter.Mapping[]` constant per distinct channel.
  Identical channels (same payload class + same flattened handler list) share a
  constant; the §3 hash-suffix dedup pass (`MappingsConstantNameDedup`) resolves
  collisions at classify time so the emitter sees already-resolved names.
  `ValidationHandler` entries produce no `Mapping` (validation runs as a
  wrapper pre-execution step). `FieldBuilder.resolveErrorChannel` wears
  `@LoadBearingClassifierCheck(key = "error-channel.mappings-constant")`;
  `ErrorMappingsClassGenerator.generate` carries the matching
  `@DependsOnClassifierCheck`.
- Per-fetcher try/catch wrapper now wires the catch arm through the channel: a
  present `ErrorChannel` emits `return ErrorRouter.dispatch(e, ErrorMappings.<CONST>,
  env, errors -> new <PayloadClass>(...))` with the synthesized payload-factory lambda
  walking `payloadCtorParams` (errors slot binds the lambda parameter; every other
  slot prints its `defaultLiteral`); an absent channel keeps the existing
  `ErrorRouter.redact` disposition. Live on `buildServiceFetcherCommon` (the shared
  body shape for `QueryServiceTableField` / `QueryServiceRecordField`). The async
  helper (`asyncWrapTail`) takes a matching `Optional<ErrorChannel>` so future
  `MutationServiceTableField` / `MutationServiceRecordField` implementations get the
  same fork on the `.exceptionally(...)` arm; today's async call sites are all
  DataLoader-backed child fields with no `WithErrorChannel`, so they pass
  `Optional.empty()`.
- DML payload assembly + dispatch (the DML-specific extension of the per-fetcher
  wrapper). Invariant #14 (DML rejects `ResultReturnType`) is lifted in
  `FieldBuilder.validateMutationReturnType`: a `@record`-typed payload return is
  accepted as long as its wrapper is single (list-of-payload remains rejected with
  a follow-up message). A new sibling resolver `resolveDmlPayloadAssembly` reflects
  the developer-supplied payload class's canonical constructor and looks for the
  unique parameter typed as the DML's table record (resolved through
  `JooqCatalog.findRecordClass(tableSqlName)`); that parameter is the row slot. A
  missing or duplicate row slot rejects the carrier with a descriptive reason.
  The new `PayloadAssembly` record (`payloadClass: ClassName`, `params: List<PayloadConstructorParam>`,
  `rowSlotIndex: int`) is held in a new `Optional<PayloadAssembly> payloadAssembly()`
  slot on every `DmlTableField` permit; channel resolution is symmetric and
  independent (a payload with a row slot but no errors-shaped GraphQL field carries
  an assembly without a channel and falls back to `redact`). The delete emitter
  (`buildMutationDeleteFetcher`) gains a third branch: when `payloadAssembly` is
  present, capture the row record from `dsl.deleteFrom(...).where(...).returning().fetchOne()`
  in a typed local, then construct `new PayloadClass(...)` walking
  `payloadAssembly.params()` (row local at `rowSlotIndex`, `null` at the errors
  slot, `defaultLiteral` elsewhere); wrap in `DataFetcherResult<PayloadClass>` and
  route the catch arm through `catchArm(outputPackage, errorChannel)` so dispatch
  fires when the channel is populated. Insert/update/upsert remain stubs; they
  inherit the slot and the emit machinery once they un-stub.
- Source-direct dispatch (R12 §2c "@error is TypeResolver wiring"): **landed**.
  `ErrorType` carries no `classFqn` slot; the `Mapping` interface has no `build`
  factory; `ErrorRouter.dispatch` places the matched throwable directly into the
  errors list. `GraphitronSchemaClassGenerator` emits one `TypeResolver` per
  @error-only union/interface that dispatches each runtime source by
  source-class instanceof + handler discriminator (mirroring the dispatcher's
  source-order semantics). Per-@error-type `path` and `message` `DataFetcher`s
  are registered in the same pass: `path` routes through `GraphQLError.getPath()`
  for VALIDATION-derived sources and synthesises from
  `env.getExecutionStepInfo().getPath().toList()` for `Throwable` sources;
  `message` routes through `getMessage()` (universal on both `GraphQLError` and
  `Throwable`). The synthesized `path` fetcher means the SDL's `path: [String!]!`
  contract holds for GENERIC/DATABASE handlers whose source class lacks a
  `getPath()` accessor.

- `ResultAssembly` carrier (R12 §2c, §5) — service-side counterpart of
  `PayloadAssembly`. The classifier reflects the developer-supplied payload
  class's canonical constructor and looks for a parameter whose
  {@code TypeName} matches the service method's reflected return type; that
  parameter is the result slot. `Optional<ResultAssembly> resultAssembly()` is
  attached to all four service field variants (`MutationServiceTableField`,
  `MutationServiceRecordField`, `QueryServiceTableField`,
  `QueryServiceRecordField`). The strict-return check on `ResultReturnType`
  payloads is loosened to accept either "service returns the SDL payload type"
  (legacy passthrough, `NoAssembly`) or "service returns a domain object
  matching one constructor parameter" (`Assembly`); a service that returns
  neither rejects with a `must return ...` reason. The service-fetcher emitter
  forks on the slot: `Assembly` captures the service return into a typed
  `__row` local and walks the constructor positionally
  (result slot ← `__row`, errors slot ← `List.of()` when a channel is also
  present, every other slot ← its `defaultLiteral`); `NoAssembly` keeps the
  legacy passthrough shape. Multi-constructor payload classes (e.g. jOOQ
  table records) can't carry a domain-object shape and are restricted to the
  legacy passthrough shape.

**Remaining work:**

All five bullets below are R12 scope. The first three form a dependency chain
(each enables the next); the last two are independent.

*Dependency chain (relaxation → accessor check → extensions wiring):*

- **Relax `TypeBuilder.buildErrorType` rule 6 to admit fields beyond
  `path` and `message`.** *Not gated; precondition for the next two bullets.*
  Today rule 6 of §1's "Reject" table (the `path` / `message` structural
  contract enforced in `TypeBuilder.buildErrorType`) rejects any `@error` type
  that declares a field other than `path: [String!]!` or `message: String!`. The
  rewrite emits a synthesized per-@error-type `DataFetcher` for each of those
  two fields (landed alongside the TypeResolver wiring); extras would route
  through graphql-java's `PropertyDataFetcher`, reading directly from the
  matched source object. Loosening the rule entails: (a) keep `path` and
  `message` mandatory and shape-checked (every legacy and rewrite fixture
  declares both, and the synthesized fetchers depend on the contract);
  (b) admit any scalar/enum-shaped declared field beyond those two, matching
  what `classifyChildFieldOnErrorType` already permits at the field level;
  (c) drop the explicit "extra-field" reject branch in
  `TypeBuilder.buildErrorType`. Until the accessor reflection check below
  lands, an extra field that the source class can't populate would surface
  only as a runtime `null` on serialisation, so this bullet ships paired with
  the next rather than ahead of it.

- **Per-(channel, @error type, handler) source-class accessor reflection
  check (§2c).** *Gated on the rule-6 relaxation above.* Per §2c "Classifier
  check: source class can populate every declared SDL field": for each
  `(channel, @error type, handler)` triple, walk every field declared on the
  `@error` SDL type; for each field, verify the handler's source class
  (`handlers[i].className` for GENERIC, `java.sql.SQLException` for DATABASE,
  `graphql.GraphQLError` for VALIDATION) exposes a PropertyDataFetcher-visible
  accessor (`fieldName()` / `getFieldName()` / public field of that name)
  whose return type is compatible with the SDL field's type. Mismatch
  surfaces as `UnclassifiedField` on the carrier with a reason naming the
  missing or wrong-typed accessor. The two synthesized-fetcher fields
  (`path`, `message`) are exempt from the per-field check on the same
  triple: they are populated by the rewrite's own per-@error-type
  `DataFetcher`s, not by the source class.

- **§5 `extensions.constraint` field population.** *Gated on the accessor
  check above.* When the SDL `@error` type for a VALIDATION-handled channel
  declares an `extensions` field, the source-class accessor reflection check
  detects the SDL signal automatically and the runtime populates
  `extensions.constraint` from the violation's constraint descriptor
  (`getAnnotation().annotationType().getSimpleName()`, per the §5
  `ConstraintViolations` helper).

*Independent:*

- **Lift `errorChannel` onto child `@service` and `@tableMethod` variants.**
  *Not gated; surfaces today.* `ServiceTableField` / `ServiceRecordField`
  (child `@service`) and `TableMethodField` / `QueryTableMethodTableField`
  (root + child `@tableMethod`) don't carry an `errorChannel` slot, so
  `FieldBuilder.checkDeclaredCheckedExceptions` runs with `Optional.empty()`
  channel at six of its eight call sites and blanket-rejects any non-exempt
  declared checked exception on those variants. Until the lift lands, schema
  authors of those fields must keep service method signatures clean of
  non-exempt checked exceptions, which is friction the §4 design already
  contemplates as temporary. The lift is also the natural collapse of those
  six `Optional.empty()` literals into a single `MethodBackedField`-capability
  validation pass (the same shape `MappingsConstantNameDedup` already runs as
  a cross-field post-classify pass).

- **Test fixture updates for source-direct dispatch.** *Not gated.*
  `SakPayload` and `DeleteFilmPayload` errors slots become `List<Object>`
  (the source-direct contract puts matched throwables and `GraphQLError`s
  directly into the list, with no developer-supplied data class). SDL fixtures
  stop using `@record` co-locations on `@error` types (the source-direct
  contract removes the developer-supplied data class). New fixtures cover the
  service-method-returns-domain-object shape (`ResultAssembly.Assembly` arm)
  and the validator integration (`ValidationHandler` channel + Jakarta
  pre-step).
- Resolve `mappingsConstantName` collision suffix at classify time
  (subsumes the standalone §3 hash-suffix dedup follow-up): **landed**.
  The per-field classifier (`FieldBuilder.resolveErrorChannel`) stamps every
  `ErrorChannel` with the bare `SCREAMING_SNAKE` payload-class name; the
  classifier-side cross-field `MappingsConstantNameDedup` pass runs in
  `GraphitronSchemaBuilder.buildSchema` immediately after the per-field loop
  and before `GraphitronSchema` construction, so the resolved name lands on
  `ErrorChannel.mappingsConstantName` before any emitter sees the schema.
  Identical handler lists for the same payload share the bare name; distinct
  shapes for the same payload get a deterministic 8-hex SHA-256-derived
  suffix; different payload classes never share a constant. Direct unit
  coverage in `MappingsConstantNameDedupTest`; emitter-tier integration in
  `ErrorMappingsClassGeneratorTest`.
- `@service` / `@tableMethod` declared checked exceptions checked against the
  field's `ErrorChannel` (§4): **landed**. `ServiceCatalog.reflectServiceMethod`
  and `reflectTableMethod` capture each method's
  `Method.getExceptionTypes()` onto `MethodRef.Basic.declaredExceptions()`;
  `EnumMappingResolver.enrichArgExtractions` carries the slot through its
  rebuild pass. The classifier-side `CheckedExceptionMatcher.unmatched`
  walks each declared checked exception against the channel's flattened
  handler list using the §4 match rule (`ExceptionHandler` covers an exception
  assignable to its class; `SqlStateHandler` / `VendorCodeHandler` cover any
  `SQLException`; `ValidationHandler` covers nothing in dispatch);
  `InterruptedException` / `IOException` are exempt per the "Special cases"
  subsection. `FieldBuilder.checkDeclaredCheckedExceptions` is wired into the
  four root `@service` paths (via `buildServiceField`), the two child
  `@service` paths, and the root + child `@tableMethod` paths; unmatched
  declared exceptions surface as `UnclassifiedField` with a reason naming the
  offending FQNs and the two fixes (declare an `@error` that covers each, or
  remove the throws). The runtime path is unchanged: the existing
  per-fetcher `catch (Exception e)` arm routes through `ErrorRouter.dispatch`
  (channel present) or `ErrorRouter.redact` (channel absent), so no second
  runtime mechanism is introduced. `buildServiceField` wears
  `@LoadBearingClassifierCheck(key = "service-method.declared-exceptions-covered")`.
  Direct unit coverage in `CheckedExceptionMatcherTest`; classifier
  integration coverage in `CheckedExceptionClassificationTest` and the new
  declared-exception capture cases in `ServiceCatalogTest`. The
  child-variant `errorChannel` lift that would let this check run against a
  populated channel on tablemethods and child `@service` is tracked above as
  its own Remaining-work bullet.

  *R2 retirement*: the
  [`checked-exceptions-typed-errors.md`](checked-exceptions-typed-errors.md)
  Backlog item (R2) is now subsumed by §4 and can be retired when its
  status moves to Done.

---

## Direction

### 1. Sealed `Handler` taxonomy

**Status: landed** in the C2 review-followup. `ErrorType.Handler` is now the sealed
interface below, on `GraphitronType.ErrorType`; the four variants carry exactly the
fields each uses and the parse-time lift produces one variant per discriminator. The
section retains the original framing for reference; before the lift, the record was
the enum-with-shared-fields shape that
[`rewrite-design-principles.md:17-21`](../docs/rewrite-design-principles.adoc#sealed-hierarchies-over-enums-for-typed-information)
warns against (a single record with a kind field plus mostly-nullable strings, with
`ErrorHandlerType` as the kind field carrying only `DATABASE | GENERIC`).

The variants are split by the **discriminator** the matcher keys off (not by vendor:
vendor-named variants like `PostgresHandler` / `OracleHandler` bake brittle product
names that don't fit MySQL/CockroachDB/Yugabyte cleanly):

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

`ValidationHandler` is the schema-side marker that opts a channel into pre-execution
Jakarta validation (§5). It carries no exception-matching criteria; the wrapper inserts
a `validator.validate(input)` step before the body runs and the channel's TypeResolver
dispatches `GraphQLError` sources arriving from the validator to the parent `@error`
SDL type.

`ErrorHandlerType` is now `GENERIC | DATABASE | VALIDATION` and exists only at the SDL
parse boundary; downstream code consumes the sealed `Handler` variants on
`GraphitronType.ErrorType`. The legacy enum-with-shared-fields `Handler` record was
deleted in the same edit that landed the sealed hierarchy; validators and in-tree
references migrated in lockstep.

The landed `ExceptionHandler` carries `String exceptionClassName` rather than
the `ClassName exceptionClass` form this section's code block describes. The
classify-time class-resolution check (the `Class.forName` against the classifier
classpath, mirroring `@record` reflection) is part of `parseErrorHandler`'s remaining
work; whether the resolved form is stored as a typed `ClassName` or a validated
`String` is a tactical call for that follow-up.

#### SDL grammar change: add `VALIDATION` to `ErrorHandlerType`

The directive grammar stays compatible with legacy except for one additive change: the
`ErrorHandlerType` enum gains a third value, `VALIDATION`. Existing schemas (which use only
`GENERIC` and `DATABASE`) keep parsing without modification.

```graphql
enum ErrorHandlerType { GENERIC, DATABASE, VALIDATION }
```

`{handler: VALIDATION}` is the schema-author signal that opts the channel into
pre-execution Jakarta validation. See §5.

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

Rules 1-6 are intra-`@error`-type and check at parse time. Rules 7-8 span multiple `@error`
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
5. **`VALIDATION` with any of `className`, `sqlState`, `code`, `matches`**: validation
   isn't an exception matcher; `{handler: VALIDATION}` is the schema marker that opts the
   channel into the wrapper's pre-execution Jakarta validation step (§5). SQL discriminators,
   exception class, and message-substring filters have no role here. Reject.
6. **`@error` type with fields the source class can't populate.** The classifier
   check ("source class has an accessor for every declared SDL field on the
   `@error` type") fires per (channel, `@error` type, handler). Schema authors
   get a build-time rejection naming the missing accessor on the offending
   source class.
7. **More than one `VALIDATION` handler in the same channel**: validation is a single fan-out
   target per payload; two `VALIDATION` handlers would compete for the same `@error` slot.
   Reject on the carrier, naming both offending `@error` types. Across separate channels on
   different fields, multiple `VALIDATION` handlers are fine, each scoped to its own field's
   payload.
8. **Duplicate match-criteria across handlers in the same channel**: covered by the §3
   classifier check; listed here so the parse-time rules are exhaustive.
The DATABASE-with-no-discriminator case (fourth row of the lift table) deserves a note:
legacy's default `className` was `DataAccessException`, but the rewrite's runtime walks the
cause chain for any `SQLException` (§3 behaviour change). The lift to
`ExceptionHandler(SQLException)` makes the rewrite's actual semantic visible in the model
rather than hidden in the runtime matcher. Schemas that relied on legacy's
"DataAccessException-only" nominal match (rare; Spring-specific) get a documented behaviour
shift, not a silent one.

#### Field structural requirements on `@error` types

The current classifier (`FieldBuilder.classifyChildFieldOnErrorType`)
restricts fields on `@error` types to scalar or enum. The SDL
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
Two model additions, one per side of the carrier/payload split.

#### 2a. `ErrorsField`: the payload-side variant the carrier walks

**Status: landed** as `ChildField.ErrorsField` (a permit on `ChildField`, the
sealed sub-hierarchy of `GraphitronField` that holds non-root field variants):

```java
record ErrorsField(
    String parentTypeName,
    String name,
    SourceLocation location,
    List<GraphitronType.ErrorType> errorTypes   // resolved; flat across single / union / interface list shapes
) implements ChildField {}
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
**Status: landed** in `FetcherEmitter.dataFetcherValue` as `PropertyDataFetcher.fetching(name)`;
graphql-java's reflective accessor handles record-style accessor → JavaBean getter → field for
every payload backing class shape. The `*Fetchers` class emits no per-field method (no-op
dispatch arm in `TypeFetcherGenerator`).

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

The carrier-side wiring is `no.sikt.graphitron.rewrite.model.ErrorChannel` plus
the `WithErrorChannel` capability interface:

```java
record ErrorChannel(
    List<GraphitronType.ErrorType> mappedErrorTypes,  // resolved; union members or list-element type, in source order
    ClassName payloadClass,                           // the developer-supplied payload class (e.g. FilmPayload)
    int errorsSlotIndex,                              // index in the canonical constructor's parameter list
    List<DefaultedSlot> defaultedSlots,               // every parameter except the errors slot, with its pre-resolved default literal
    String mappingsConstantName                       // resolved at classify time, including any collision suffix; see §3 dedup rule
) {}

record DefaultedSlot(
    int index,
    TypeName type,           // resolved JavaPoet type, recorded for diagnostics
    String defaultLiteral    // pre-resolved: "null" / "0" / "0L" / "false" / "'\\0'"
) {}
```

DML fields keep a sibling slot — `Optional<RowAssembly> rowAssembly()` — that
captures the DML row-slot index against the same payload class:

```java
record RowAssembly(
    ClassName payloadClass,
    int rowSlotIndex,
    List<DefaultedSlot> defaultedSlots   // every parameter except the row slot
) {}
```

A DML field with both an errors-shaped GraphQL field and a row slot carries both
`ErrorChannel` and `RowAssembly`; they reference the same payload class and
together cover every constructor parameter (the errors slot and the row slot
must be distinct indices, which the classifier verifies). A DML field without
an errors-shaped field carries only `RowAssembly`. A service field carries only
`ErrorChannel`. There's no shared "PayloadShape" abstraction because the
catch-arm and success-arm emitters have different per-slot needs (the
catch arm binds the errors slot to the dispatched list; the DML success arm
binds the row slot to the captured row record); each carrier holds exactly the
indices its emitter consumes.

The errors slot's index is identified positionally: the SDL field that
classified as `ErrorsField` (§2a) has an index in the payload type's field
declaration order, and the canonical constructor's parameters follow that order
(records preserve declaration order; SDL→constructor parameter binding by index
is the documented contract). The classifier records that index plus
per-non-errors-slot `defaultLiteral`. The slot's element type is `Object` —
sources placed in the list are heterogeneous (matched exceptions, `GraphQLError`
violations) so the only universal supertype is `Object`. Consumer payload
constructors should declare the slot as `List<?>` or `List<Object>`.

The flattened handler list, used by the §3 duplicate-criteria check and the
`mappingsConstantName` dedup-suffix derivation, is a derived view: walk
`mappedErrorTypes` in declaration order and concatenate each resolved
`ErrorType`'s `List<Handler>` in source order. Both consumers compute it on
demand from `mappedErrorTypes`; it is not a stored field on `ErrorChannel`.

Every field variant whose body is a candidate for the try/catch / `.exceptionally`
wrapper in §3 implements the `WithErrorChannel` capability interface
(`Optional<ErrorChannel> errorChannel()`). Capability rather than a slot on every
`GraphitronField` root: only some root variants are fetcher-emitting, and the others
stay free of the slot. Generators consume the field via `instanceof WithErrorChannel`
when they need to know whether to dispatch via the generated `ErrorRouter`. Concretely:

- All `MutationField.DmlTableField` permits (insert/update/delete/upsert).
- `MutationField.MutationServiceTableField`, `MutationServiceRecordField`.
- `QueryField.QueryServiceTableField`, `QueryServiceRecordField`, and any other
  Query-side variant that resolves to a fetcher body once the channel-detection rules
  below match the field's payload type.

The capability is non-structural: each variant lists `WithErrorChannel` alongside
`MethodBackedField` etc., and the carrier classifier produces `Optional.of(channel)` /
`Optional.empty()` per call site. Same pattern as `encodeReturn: Optional<HelperRef.Encode>`
on `DmlTableField` (introduced by R50, `lift-nodeid-out-of-model` — shipped):
classifier resolves once into a typed slot, emitter dispatches over a settled shape.

The carrier classifier walks the field's payload return type, identifies the `errors` field by
its `ErrorsField` classification (committed in §2a), and resolves which `Handler`s apply. No
global map; each field carries its own list. Query fields are in scope on the same terms as
mutations: any operation field whose return type carries an `errors` field gets an
`ErrorChannel` and a wrapped fetcher. Legacy applied the same rule, and there's no asymmetry
in the runtime contract that would justify deferring queries.

**Channel-detection rules (carrier classifier):**

1. The payload type must be a `@table` or `@record` object whose field set includes exactly
   one `ErrorsField` (committed by §2a); its `errorTypes` populate the channel's
   `mappedErrorTypes`.
2. A payload with no `ErrorsField` has no `ErrorChannel`. Throwing inside that fetcher takes
   the top-level path (§3).
3. The detection ignores the field's name; legacy convention is `errors:` but the rewrite keys
   off the structural relationship to `@error` types, not a hardcoded field name. This matches
   how `mutations.md` keys off return-type shape rather than directive presence.
4. A payload with two `ErrorsField` children is rejected (`UnclassifiedField` on the second
   one): the runtime contract assumes one channel per fetcher body. The legacy fixture set
   has been audited and contains no payload with two error fields; the canonical
   `multiple/` fixture uses two separate payloads, each with one `errors` field.

#### `@error` is TypeResolver wiring (no developer-supplied data class)

An `@error` directive teaches graphql-java which Java class corresponds to which
SDL type. The runtime *source* for an entry in the payload's `errors` list is
the matched object itself; graphql-java's `PropertyDataFetcher` reads each
declared SDL field directly from that source. There is no developer-supplied
parallel data class for `@error` types, no `Mapping.build` factory, no wrapper
carrier.

**Source class per handler kind.**

| Handler    | Source class | Where it comes from |
|------------|--------------|---------------------|
| GENERIC    | `handlers[i].className` | matched at the catch-arm dispatch step |
| DATABASE   | `java.sql.SQLException` | matched at the catch-arm dispatch step |
| VALIDATION | `graphql.GraphQLError`  | each violation produced by the wrapper's pre-execution Jakarta validation step (§5) |

For `GENERIC` and `DATABASE`, the dispatcher places the matched exception (the
cause-chain entry that satisfied the handler's predicate) into the errors list.
graphql-java reads `getMessage()` for the `message` field plus any
consumer-defined accessors (`getCode()`, `getSqlState()`, ...) for additional
declared fields.

For `VALIDATION`, the wrapper's pre-execution validator step
(`Validator.validate(input)`) produces a `Set<ConstraintViolation>` and translates
each to a `GraphQLError` via `ConstraintViolations.toGraphQLError`. Each
`GraphQLError` is its own source: `getPath()` for the SDL `path` field,
`getMessage()` for `message`, `getExtensions()` for an optional `extensions`
field. The dispatcher's catch arm is never involved on the validation path.

**Generated `TypeResolver` per `@error` union/interface.**

The schema's `@error` union (`union SakError = ValidationErr | DbErr`) or
interface gets one generated `TypeResolver` registered alongside the schema:

```java
TypeResolver = env -> {
    Object src = env.getObject();
    // Source-order match against the channel's handlers; first hit wins.
    if (src instanceof GraphQLError)            return objectTypeFor("ValidationErr");
    if (src instanceof SQLException sql && "23503".equals(sql.getSQLState()))
                                                return objectTypeFor("DbErr");
    if (src instanceof IllegalArgumentException) return objectTypeFor("ValidationErr");
    return null;  // unreachable; the dispatcher's match step has already filtered
};
```

This is the schema author's `handlers:` list lifted into runtime code. graphql-java
calls it once per element in the list at serialisation time and dispatches each
source to the right SDL type.

**Classifier check: source class can populate every declared SDL field.**

For each `(channel, @error type, handler)` triple, the classifier:

1. Determines the source class (handler's `className` for GENERIC,
   `java.sql.SQLException` for DATABASE, `graphql.GraphQLError` for VALIDATION).
2. Walks every field declared on the `@error` SDL type. For each field, looks
   up an accessor on the source class via PropertyDataFetcher convention
   (`fieldName()`, `getFieldName()`, or a public field of that name).
3. Verifies the accessor's return type is compatible with the SDL field's type
   (graphql-java coerces `Object` path segments to `String`, etc., per its
   default coercion rules).
4. Mismatch surfaces as `UnclassifiedField` on the carrier with a reason naming
   the missing/wrong accessor.

This makes the schema honest by construction. An `@error` type backed by
`IllegalArgumentException` that declares `path: [String!]!` rejects at build
time because `IllegalArgumentException` has no `getPath()`. A VALIDATION-handled
`@error` type's `path` and `message` fields work because `GraphQLError` exposes
both. Schema authors who need extra structured fields (e.g. `code: String!`)
either use a consumer exception class that exposes the matching accessor or
omit the field.

**No `@error` Java backing class, no `className` arg on `@error`, no SDL→class
lookup.** The directive's only role is configuring the matching predicates and
the SDL→source-class binding (implicit via handler kind for VALIDATION/DATABASE,
explicit via `handlers[i].className` for GENERIC). The schema is the contract;
the runtime maps what the source has.

**Payload Either: success arm vs. errors arm.**

The payload class is an Either in disguise. Its canonical constructor lists
both the success-arm parameters (the data fields) and the errors-arm parameter
(the list of sources). The graphitron-emitted wrapper assembles the payload on
both arms; consumer code never constructs payload instances directly.

```java
public record CreateFilmPayload(Film film, List<Object> errors) { }

// Consumer's service method: plain Java, no payload knowledge.
public Film createFilm(CreateFilmInput input) {
    return /* INSERT */;
}

// Consumer's DML body: graphitron-emitted; produces a row record.
// (Same shape DML mutations have today.)

// Wrapper assembles in both cases:
//   success arm → result/row slot bound to the body's return value, errors slot → []
//   errors arm  → errors slot bound to the validator's violations OR the
//                 dispatcher's matched source(s), every other slot defaulted.
```

This makes the consumer-side contract uniform across DML, service-backed
mutations, and service-backed queries: each body returns the domain object (or
row record) that fills the payload's result slot; the wrapper handles
everything else.

**Slot resolution at classify time.**

For each fetcher-emitting field with an `ErrorChannel`, the classifier resolves:

- `errorsSlotIndex: int` — the SDL field that §2a classifies as `ErrorsField`
  has an index in the payload type's field declaration order; the canonical
  constructor's parameter at that same index is the errors slot. Records
  preserve declaration order; hand-rolled POJOs are expected to expose a
  canonical constructor matching SDL order. Slot's element type is `Object`
  (sources mix matched exceptions and `GraphQLError`s; the channel's source
  classes share only `Object`). Consumer payload classes should declare the
  slot as `List<?>` or `List<Object>`.
- `resultSlotIndex: int` (DML and service-backed both) — the constructor
  parameter typed as the result type:
  - For DML, the result type is the jOOQ table record class
    (`JooqCatalog.findRecordClass(tableSqlName)`). The wrapper binds the row
    captured from `RETURNING ... fetchOne()` here.
  - For service-backed, the result type is the consumer's service method's
    declared return type. The wrapper binds the call's return value here.
- Per-other-slot `defaultLiteral` (`"null"` for reference types, `"0"` /
  `"0L"` / `"0.0"` / `"false"` / `"'\\0'"` for primitives), so the emitter
  prints a complete constructor call without runtime reflection.

A fetcher's success arm and errors arm both go through the same payload
constructor, just binding different slots:

| Slot           | Success arm                              | Errors arm                                                       |
|----------------|------------------------------------------|------------------------------------------------------------------|
| Errors slot    | `List.of()` (empty)                       | the validator's violations (validation path) OR `List.of(matched)` (dispatch path) |
| Result slot    | the body/service return value            | `null`                                                            |
| Defaulted slot | the slot's `defaultLiteral`              | the slot's `defaultLiteral`                                       |

These resolutions are [load-bearing](../docs/rewrite-design-principles.adoc#classifier-guarantees-shape-emitter-assumptions):
producer (`FieldBuilder` channel/payload-shape resolution) wears
`@LoadBearingClassifierCheck(key = "error-channel.payload-slots")`; consumer
(the wrapper emitter that prints constructor calls) wears
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
allocation per fetch on the success path; in exchange, every fetcher method's
*own* signature stays parameterised on the concrete payload type. The
`Function<List<?>, P>` payload-factory only widens the *router-facing* contract;
the consumer-visible fetcher signatures don't carry it. graphql-java already
unwraps `DataFetcherResult` uniformly downstream, so the wrapper is invisible to
consumers.

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
            errors -> new FilmPayload(null, errors));   // synthesized per §2c
    }
}
```

The lambda body is the synthesized payload factory: at the errors-slot index
(known to the catch-arm emitter from `ErrorChannel.errorsSlotIndex`) it prints
the lambda parameter; at every other index it prints the slot's pre-resolved
`defaultLiteral`. The list passed in is whatever the dispatcher built for the
match — a single matched exception for `GENERIC` / `DATABASE`, or
`vve.getUnderlyingErrors()` for VALIDATION fan-out. graphql-java's
PropertyDataFetcher reads each declared `@error` field directly off whatever
source object happens to be in the slot, with the channel's TypeResolver
dispatching each one to the right SDL `@error` type.

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
     *   matched   -> DataFetcherResult.newResult().data(payload-with-errors-arm-filled).build()
     *   unmatched -> redacted DataFetcherResult (data=null + correlation-ID error).
     *
     * The dispatcher matches the throwable against the channel's mappings, picks
     * the source(s) (the matched exception itself for non-validation; each
     * underlying GraphQLError for VALIDATION fan-out), and constructs the
     * payload via its all-fields constructor with the errors-slot index bound to
     * the source list and every other slot defaulted. graphql-java's per-channel
     * TypeResolver dispatches each source object to its SDL @error type at
     * serialisation time; PropertyDataFetcher reads each declared @error field
     * directly from the source.
     */
    public static <P> DataFetcherResult<P> dispatch(
            Throwable thrown,
            Mapping[] mappings,                       // an entry on ErrorMappings, e.g. ErrorMappings.FILM_PAYLOAD
            DataFetchingEnvironment env,              // for source-location on the unmatched/redact path
            Function<List<?>, P> payloadFactory       // synthesized: (errors) -> new Payload(default, ..., errors, ..., default)
    ) { ... }

    /** No-channel disposition: log with correlation ID, return DataFetcherResult with data=null and a redacted error. */
    public static <P> DataFetcherResult<P> redact(Throwable thrown, DataFetchingEnvironment env) { ... }

    public sealed interface Mapping
            permits ExceptionMapping, SqlStateMapping, VendorCodeMapping, ValidationMapping {
        boolean match(Throwable thrown);   // first match wins per the §3 source-order rule
        String description();              // when set, overrides the throwable's getMessage() at serialisation; otherwise null
        // No build() method: there's no construction step. The matched source object
        // (the exception itself, or each GraphQLError for VALIDATION) goes straight
        // into the errors list; graphql-java reads from the source directly.
    }
}
```

`dispatch` and `redact` are generic on `P`, the field's payload type. Both arms
always return `DataFetcherResult<P>`; graphql-java unwraps that uniformly
downstream regardless of whether the result was produced via a successful
payload or a redacted error. `payloadFactory` is a `Function<List<?>, P>`
because the source list is heterogeneous (exception instances + `GraphQLError`s)
with no useful Java ancestor beyond `Object`; the catch-arm emitter prints the
factory as `errors -> new Payload(null, ..., errors, ..., null)` with literal
defaults at every non-errors slot.

`ValidationMapping` is in the sealed `Mapping` set so the dispatch arm can
locate the channel's validation routing by exhaustive switch (see §5's
fan-out). It is *not* iterated in the regular source-order match loop; the
validation arm in "Validation arm precedes channel matching" runs ahead of
`MAPPINGS` iteration and either passes the underlying `GraphQLError`s into
the errors list verbatim or routes unhandled. Schema authors see it as one
entry in the channel; the runtime contract is the special-case arm.

The `description` field on each `Mapping` is consulted by graphql-java's
`PropertyDataFetcher` reading the `message` SDL field: when the schema author
set `description: "..."` on the handler, the runtime substitutes that for the
source's `getMessage()`. Implementation: the catch-arm emitter wraps each
source in a thin per-handler facade (constructed once per match, not stored)
when `description` is set, so the wrapped object's `getMessage()` returns the
override; without `description`, the source goes in unwrapped. This keeps the
"sources straight in the list" property for the common case while preserving
legacy's `description` override.

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

#### Validation runs in the wrapper, not the dispatcher

The wrapper inserts a pre-execution validation step ahead of the body when the
channel carries a `ValidationHandler` (§1, §5). Violations short-circuit the
service call: the wrapper builds the payload's errors arm directly with the
violations, and `ErrorRouter.dispatch` is never invoked on the validation
path. The dispatcher only handles real exceptions thrown from the body.

Schema authors who declare `{handler: VALIDATION}` get the pre-step
automatically; schema authors who don't get a wrapper that just runs the body
+ catch arm. The dispatcher has no validation special case.

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

- Bean-validation violations are handled by §5's wrapper pre-step on channels
  declared with `{handler: VALIDATION}`. The wrapper builds the payload's
  errors arm from the violations directly; the body is never called on invalid
  input. Channels without `VALIDATION` get no validator pre-step.
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

### 5. Native Jakarta validation

Validation is a wrapper-level pre-execution step, not a runtime exception path.
The wrapper inserts a `Validator.validate(input)` call before invoking the
service or DML body; if there are violations, the wrapper builds the payload's
errors-arm directly from them and returns. The body is never called on invalid
input. The dispatcher is never involved.

**Validator wiring.**

The rewrite depends directly on `jakarta.validation-api`. Default factory:
`Validation.buildDefaultValidatorFactory().getValidator()`, lazy on first use.
Consumers can install a custom `Validator` via `Graphitron.Builder` (same
shape as the existing `DSLContext` injection point). Hibernate Validator (or
any compliant `ValidatorFactory` provider) supplies the runtime
implementation.

`RecordValidator` and `ValidationViolationGraphQLException` retire. Consumers
who currently call `recordValidator.validate(input)` from inside their service
methods delete the call: the wrapper does it now, ahead of the service method.

**Wrapper shape with validation.**

```java
public static DataFetcherResult<CreateFilmPayload> createFilm(DataFetchingEnvironment env) {
    CreateFilmInput input = env.getArgument("input");

    // Pre-execution Jakarta validation, when the channel has {handler: VALIDATION}.
    Set<ConstraintViolation<CreateFilmInput>> violations = VALIDATOR.validate(input);
    if (!violations.isEmpty()) {
        List<GraphQLError> errors = violations.stream()
            .map(v -> ConstraintViolations.toGraphQLError(v, env))
            .toList();
        return DataFetcherResult.<CreateFilmPayload>newResult()
            .data(new CreateFilmPayload(/* film */ null, /* errors */ errors))
            .build();
    }

    // Service body runs only when input is valid.
    try {
        Film film = SERVICE.createFilm(input);
        return DataFetcherResult.<CreateFilmPayload>newResult()
            .data(new CreateFilmPayload(film, List.of()))
            .build();
    } catch (Exception e) {
        return ErrorRouter.dispatch(e, ErrorMappings.CREATE_FILM_PAYLOAD, env, /* factory */);
    }
}
```

The pre-validation step is conditional: when the channel carries a
`ValidationHandler`, the emitter inserts the step and the
`ConstraintViolations.toGraphQLError(...)` call. When the channel doesn't,
neither line is emitted; the wrapper's only steps are body-call + catch-arm.

**`ConstraintViolation` → `GraphQLError` translation.**

A small generated helper, `ConstraintViolations`, lives at
`<outputPackage>.schema.ConstraintViolations`:

- `getMessage()` → `message`. Jakarta's `MessageInterpolator` has already
  resolved the constraint's localised message string by the time the
  violation surfaces.
- Property path → response path. The path is the GraphQL field's response path
  (`env.getExecutionStepInfo().getPath().toList()`), plus the input argument
  name (the SDL arg key, usually `input`), plus the bean property path walked
  segment-by-segment. Each `Path.Node`: `getName()` per `PROPERTY` node; the
  integer `getIndex()` per `CONTAINER_ELEMENT` (list element); `getKey()` per
  map entry. Concatenated into the resulting `GraphQLError.path`.
- `getConstraintDescriptor().getAnnotation().annotationType().getSimpleName()`
  → optional `extensions.constraint`, populated only when the SDL `@error`
  type for VALIDATION declares an `extensions` field. The classifier's
  source-class accessor reflection check (§2c) catches the SDL signal
  automatically.

**Service / DML body shape.**

Bodies return the domain object the payload's result slot expects:

```java
// Service-backed:
public Film createFilm(CreateFilmInput input) {
    return /* INSERT */;
}

// DML body (graphitron-emitted, always was this shape):
//   FilmRecord row = dsl.insertInto(FILM).set(...).returning().fetchOne();
//   ... wrapper assembles new CreateFilmPayload(row, List.of()) ...
```

Service signatures lose the parallel "payload-returning" form they have in
legacy. The classifier resolves the result-slot index per §2c (the constructor
parameter typed as the service method's return type); the wrapper binds the
return value there.

**Migration table.**

| Legacy artifact                                | Replacement                                                                                                                                                                                          |
|------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `recordValidator.validate(input)` inside the service method | Delete the call. Declare `{handler: VALIDATION}` on the channel's `@error` type. The wrapper validates ahead of the service.                                                                          |
| `ValidationViolationGraphQLException`          | Gone. Consumer code never imports or throws it; bean-validation violations come back from `Validator.validate(...)` directly, but only the wrapper sees that.                                          |
| `RecordValidator` (the legacy bridge class)    | Replaced by `jakarta.validation.Validator`. Consumer wires their preferred factory via `Graphitron.Builder` or accepts the default.                                                                  |
| Service method returning the SDL payload type  | Service method returns the domain object (e.g. `Film`); the wrapper assembles the payload.                                                                                                          |
| `handleValidationException(...)` override      | Delete. Replaced by `{handler: VALIDATION}` on the SDL.                                                                                                                                              |
| `handleIllegalArgumentException(...)` override | Declare `{handler: GENERIC, className: "java.lang.IllegalArgumentException"}` on the `@error` type. **Behaviour shift**: legacy emitted the raw IAE message at the top level even without a declaration; the rewrite redacts unmatched IAEs like any other unmatched throw. Schemas that relied on the legacy auto-leak must add the declaration explicitly. |
| `createDefaultDataAccessError(...)` override   | Declare a *trailing* `{handler: DATABASE}` (no `sqlState`, no `code`). §1 lifts it to `ExceptionHandler(SQLException)`; §3 source-order makes it the catch-all after specific entries.               |

**Concrete before/after.**

```graphql
type UgyldigInput @error(handlers: [
  {handler: VALIDATION},                                                # was handleValidationException
  {handler: GENERIC, className: "java.lang.IllegalArgumentException"},  # was handleIllegalArgumentException
  {handler: DATABASE}                                                   # was createDefaultDataAccessError
]) {
    path: [String!]!
    message: String!
}
```

```java
// Before (legacy)
public CreateFilmPayload createFilm(CreateFilmInput input) {
    recordValidator.validate(input);   // throws ValidationViolationGraphQLException
    Film film = /* INSERT */;
    return new CreateFilmPayload(film, List.of());
}

// After (rewrite)
public Film createFilm(CreateFilmInput input) {
    return /* INSERT */;
}
```

**`GraphQLError.getPath()` returns `List<Object>`** (path segments alternate between
field names and list indices). The SDL `path: [String!]!` triggers graphql-java's
standard `Object → String` coercion at field-resolution time. Same behaviour legacy
had, just at a different layer.

The corresponding `CustomSchemaBasedErrorStrategy` subclass deletes; the consumer
writes **no Java for `@error` types at all**. The runtime sources are the
exception classes the consumer's code already throws (`IllegalArgumentException`,
the `SQLException` jOOQ surfaces, the `ValidationViolationGraphQLException` the
record validator throws). graphql-java's PropertyDataFetcher reads `getMessage()`
and (for `GraphQLError` violations) `getPath()` directly from those sources;
graphitron's generated TypeResolver dispatches each source to its SDL `@error`
type by walking the channel's handler predicates.

The legacy consumer's `record UgyldigInput(List<String> path, String message) {}`
is no longer load-bearing on graphitron's side. Schema authors who keep it
around get nothing extra; schema authors who delete it get a smaller codebase.
The `@error` SDL type is the contract; the runtime maps from whichever
exception/violation class actually showed up.

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

Graphitron routes exceptions thrown inside a generated fetcher into typed GraphQL
error payloads. Declare an `@error` type and list its handlers; the runtime maps
the matched exception (or each underlying validation violation) to the SDL type.
**You don't write a Java class for the `@error` type itself** — the consumer
exception classes you already throw are the runtime sources, and graphql-java
reads each declared SDL field directly off them.

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

The handler's `className` (when set) names the *exception class* the handler
matches; the `@error` SDL type itself describes the response shape.

Return it from a mutation payload's `errors` field:

```graphql
type CreateFilmPayload @record(record: {className: "no.sikt.example.CreateFilmPayload"}) {
  film: Film
  errors: [UgyldigInput]
}
```

The generated fetcher catches all exceptions. When one matches a handler, the
runtime places the matched object directly in the `errors` list (the matched
exception itself for `GENERIC` / `DATABASE`; each underlying `GraphQLError` for
`VALIDATION` fan-out). graphql-java's per-channel `TypeResolver` dispatches each
list element to its `@error` SDL type, and `PropertyDataFetcher` reads each
declared field straight off the source's accessors. Unmatched exceptions are
logged with a random UUID correlation ID and returned to the client as a redacted
error. No internal detail, no stack trace, no exception message.

### `@error` type requirements

Every `@error` type declares the response fields you want clients to see; the
runtime fills them by reading from whichever exception class (or `GraphQLError`)
matched. graphitron verifies at build time that every declared field has a
matching accessor on the source class for every handler that can match it; a
mismatch is a classifier error (not a runtime failure).

For example, an `@error` declaring `path: [String!]!` and `message: String!` and
backed only by `GENERIC` matches against `IllegalArgumentException` rejects at
build: `IllegalArgumentException` exposes `getMessage()` but no `getPath()`. To
fix, either drop `path` from this `@error` type, use it in a channel whose
matching exception classes do expose `getPath()`, or supply a custom exception
class that does. For `VALIDATION` `@error` types, the source is
`graphql.GraphQLError`, which exposes `getPath()` and `getMessage()` natively;
declaring both is the canonical shape.

No supertype, interface, marker, or `(List<String>, String)` constructor is
required on consumer code. graphitron does not generate or expect any
`@error`-specific Java class.

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

**`VALIDATION`** opts the channel into pre-execution Jakarta validation. The
generated wrapper calls `Validator.validate(input)` before the service/DML body
runs; if violations exist, the wrapper builds the payload's `errors` arm
directly from them and the body never executes. At most one `VALIDATION` entry
per error channel; `className`, `sqlState`, `code`, and `matches` are not
allowed alongside it (validation isn't an exception matcher).

```graphql
{handler: VALIDATION}
```

A mutation whose input violates two `jakarta.validation` constraints produces
two entries in `errors`, one per violation:

```json
{
  "data": { "createFilm": { "film": null, "errors": [
    { "path": ["createFilm", "input", "title"],   "message": "must not be blank" },
    { "path": ["createFilm", "input", "release"], "message": "must not be null" }
  ]}}
}
```

Annotate input model classes with standard `jakarta.validation.constraints`
(`@NotBlank`, `@Size`, `@Min`, `@Pattern`, `@Valid` cascade, etc.). Hibernate
Validator (or any compliant `ValidatorFactory` provider) supplies the runtime;
graphitron uses `Validation.buildDefaultValidatorFactory().getValidator()` by
default, or a custom `Validator` configured on `Graphitron.Builder`.

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
| `handleValidationException(...)` | `{handler: VALIDATION}` (the wrapper validates ahead of the body; no consumer Java) |
| `handleIllegalArgumentException(...)` | `{handler: GENERIC, className: "java.lang.IllegalArgumentException"}` |
| `createDefaultDataAccessError(...)` | `{handler: DATABASE}` (place last; catches any remaining `SQLException`) |

Delete the `CustomSchemaBasedErrorStrategy` subclass and the
`ExceptionHandlingBuilder` wiring.

**Delete the developer-supplied `@error` Java classes.** Legacy required one
data class per `@error` SDL type so the runtime had something to instantiate.
The rewrite reads SDL fields directly off the matched exception (or
`GraphQLError` for validation); those classes are unused.

**Delete `recordValidator.validate(input)` from your service methods.** The
wrapper validates ahead of the call when `{handler: VALIDATION}` is on the
channel. The legacy `RecordValidator` and `ValidationViolationGraphQLException`
retire; the wrapper uses `jakarta.validation.Validator` directly.

**Service methods return the domain object, not the SDL payload type.**

```java
// Before
public CreateFilmPayload createFilm(CreateFilmInput input) {
    recordValidator.validate(input);
    Film film = /* INSERT */;
    return new CreateFilmPayload(film, List.of());
}

// After
public Film createFilm(CreateFilmInput input) {
    return /* INSERT */;
}
```

The generated wrapper assembles the payload around the returned domain object.

**Verify your declared `@error` fields are populated by the matched exceptions.**
For each `@error` type, check that every consumer exception class registered as
a `GENERIC` / `DATABASE` handler exposes accessors for the SDL fields the type
declares. `path: [String!]!` rejects against an `IllegalArgumentException`-only
`@error` because `IllegalArgumentException` has no `getPath()`; either drop the
field, route validation violations through the same `@error` (whose source is
`GraphQLError`, which *does* expose `getPath()`), or add `getPath()` to a custom
exception class.

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
  `Subscription` with a `DEFERRED` rejection). Subscription error
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
  `(path, message)` projection matches legacy parity. Schema authors who want extensions or
  locations can declare them on the validation `@error` SDL type; `GraphQLError.getExtensions()`
  / `getLocations()` populate via the same source-direct accessor reflection, no special
  case needed.
- **Two-`ErrorsField` payloads.** Resolved by audit: no legacy fixture has two error fields
  on one payload; the rejection rule in §2c stands.
- **`unionMultipleDatabaseHandlers/` partition.** Resolved as Rejected by audit: both
  members declare `[{handler: DATABASE}]` with no discriminator, lifting to identical
  `ExceptionHandler(SQLException, null)` and colliding under §3's duplicate-criteria check.
- **Query fields.** Resolved as in-scope on the same terms as mutations; see §2c.
