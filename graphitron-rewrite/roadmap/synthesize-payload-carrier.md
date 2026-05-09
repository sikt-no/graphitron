---
id: R75
title: "Plain payload types via identity passthrough"
status: Spec
bucket: architecture
priority: 7
theme: mutations-errors
---

# Plain payload types via identity passthrough

A passthrough `@mutation` whose return type is a plain SDL Object (no `@record(className)`,
no `@table`) should compile and serve correctly without the consumer authoring a Java
carrier. Today the rewrite forces an authored class on every payload that isn't a bare
`ID` or `@table`, which inverts legacy graphitron's default and (more importantly) drags
graphql-java's mapping concerns into the consumer's source tree.

The fix is not to synthesize a Java class. Generating transport DTOs is the legacy
mistake the rewrite already disowns under the "no DTOs, no TypeMappers" emitter
convention (`graphitron-rewrite/docs/rewrite-design-principles.adoc`, §"Return types").
Java payload classes earn their place only when the consumer needs to capture state or
behaviour on the carrier. For everything else, graphql-java's mapping plus graphitron's
existing identity-passthrough fetcher pattern (`FetcherEmitter.dataFetcherValue` lines
56-60: `($T env) -> env.getSource()`) does the work.

## Concrete friction

```graphql
input OpprettKvotesporsmalPreutfyllingInput @table(name: "kvotesporsmal_preutfylling") {
    kvotesporsmalPreutfyllingKode: String! @field(name: "KVOTESPORSMAL_PREUTFYLLING_KODE")
}

type KvotesporsmalPreutfyllingPayload {
    kvotesporsmalPreutfylling: [KvotesporsmalPreutfylling!]
}

type Mutation {
    opprettKvotesporsmalPreutfylling(
        input: OpprettKvotesporsmalPreutfyllingInput!
    ): KvotesporsmalPreutfyllingPayload @mutation(typeName: INSERT)
}
```

Today this fails at `MutationInputResolver.validateReturnType:173-182` (the
`ScalarReturnType` arm rejecting non-`ID` returns) with *"return type
'KvotesporsmalPreutfyllingPayload' is not yet supported; use ID or a @table
type"*. The plain Object lands as `PlainObjectType` in the type classifier
(`TypeBuilder.buildTypes`, the no-domain-directive fall-through), and
`GraphitronSchemaBuilder.buildSchema` skips field classification for
`PlainObjectType` parents entirely. There is no machinery downstream that would
route the DML round-trip's `Result<Record>` to graphql-java for traversal.

## Independence from R12

R12 has shipped the authored-carrier path on trunk: canonical-ctor reflection,
`ErrorChannel`, `PayloadAssembly`, `ResultAssembly`, error-routing dispatch,
default-literal capture, `ErrorMappings`, `ErrorRouter`, `MappingsConstantNameDedup`.
That path continues to govern any payload declared with `@record(record: {className: ...})`.

R75 has no remaining dependency on R12. Phase 1 never reaches an error channel by
construction, so it doesn't touch `ErrorRouter.dispatch`, `payloadFactoryLambda`, or
canonical-ctor reflection. Phase 2 reuses R12's `ErrorMappings` mapping-table half
(*which throwables map to which `@error` types*), but every piece of that machinery
already lives on trunk. R12's remaining `Ready` work (rule-6 relaxation, accessor
reflection check, `extensions.constraint`, validator-integration fixture) is all
`@error`-side and gates none of R75's phases.

The two paths split cleanly: authored carriers traffic in canonical-ctor slot indices
(`PayloadAssembly.rowSlotIndex`, `ErrorChannel.errorsSlotIndex`); the no-authored-class
path has no Java class to reflect on, so none of those slot indices apply. R75 is
purely additive across all three phases.

## Phasing

R75 ships in three phases ordered by user-visible value. Each phase is a discrete
implementation track; downstream phases bind structurally to upstream ones but are
not schedule-coupled.

- **Phase 1: single-data-slot payloads, no errors.** Closes the legacy-parity gap on
  `@mutation` returning a plain Object payload with one data field. Specified in
  implementation-ready detail below.
- **Phase 2: errors-slot integration via `localContext` (sketch).** Lights up when the
  SDL payload adds an `errors:` field; the DML/service emitter wraps its result in
  `DataFetcherResult` with `localContext(errors)`, and the errors field's emitted
  fetcher reads from `env.getLocalContext()`.
- **Phase 3: domain-record returns from `@service` (sketch).** Allows a `@service`
  method to return its domain object directly; graphitron's existing per-field
  fetchers (record accessors) read off the source unchanged.

This Spec body specifies Phase 1. Promoting R75 from Spec → Ready means signing off
on Phase 1 only; Phases 2 and 3 each return to Spec for sharpening before Ready.

## Phase 1: single-data-slot payloads, no errors

### Trigger function

A pure function over `ctx.schema` and `ctx.types`, returning a sealed result
that callers route on:

```java
PassthroughResolution unwrapPassthroughPayload(String typeName, BuildContext ctx);

sealed interface PassthroughResolution {
    record Ok(PassthroughInfo info) implements PassthroughResolution {}
    record NotCandidate()             implements PassthroughResolution {}
    record Rejected(String reason)    implements PassthroughResolution {}
}

record PassthroughInfo(
    String payloadTypeName,
    String dataFieldName,
    String dataElementName,
    TableRef dataTable,
    FieldWrapper dataWrapper
) {}
```

`Ok(info)` when *all* of the following hold:

1. The type is registered as `PlainObjectType` (no domain directive) or
   `PojoResultType(_, _, null)` (the `@record`-without-`className` case
   `TypeBuilder.buildResultType` produces today at lines 530, 533, 542).
2. The SDL Object declares exactly one field.
3. That single field's element type is registered as `TableBackedType`.

`NotCandidate` when condition #1 fails (the type is `@table`, `@record` with
`className`, an interface, a union, an enum, etc.). No rejection message; no
consumer reaction beyond falling through to the existing dispatch.

`Rejected(reason)` when the type *is* a candidate (#1 holds) but #2 or #3 fails.
The reason names the failed positive criterion in the validator-mirrors-classifier
shape, e.g. *"payload 'KvotesporsmalPreutfyllingPayload' declares 3 fields; Phase
1 admits exactly one data field"* or *"payload field 'kvotesporsmalPreutfylling'
element type 'Foo' is not @table-mapped; Phase 1 admits @table elements only"*.

The split between `NotCandidate` and `Rejected` is the "Builder-step results
are sealed" principle: silent non-applicability (the function doesn't apply to
this kind of type at all) and explicit rejection (the type is in scope but
fails a criterion) are different events with different consumer reactions, so
they get different sealed arms rather than a single boolean-or-message return.

`unwrapPassthroughPayload` is referentially transparent given a frozen
`ctx.schema` and `ctx.types`; it materialises no state and is called on demand
at exactly two production sites and one validator site. A natural home is
`BuildContext` itself, alongside `resolveReturnType`.

Pin (Phase 1 scope, was an open question in earlier drafts): the data field's
SDL wrapper may be either single (e.g. `kvotesporsmalPreutfylling: KvotesporsmalPreutfylling`)
or list (e.g. `[KvotesporsmalPreutfylling!]`). The function reports the data
field's wrapper unchanged; the existing `DmlReturnExpression.{ProjectedSingle,
ProjectedList}` dispatch handles both cardinalities downstream.

Pin (Phase 1 scope, was an open question in earlier drafts): all four DML
kinds (INSERT / UPDATE / DELETE / UPSERT) admit passthrough payload returns.
The DML emitter path is uniform across kinds (see "DML emitter" below), so the
matrix lands once rather than per kind.

Mutation-reachability is *not* a trigger condition. Earlier drafts gated the
lift on "type is the return of at least one @mutation field" to scope Phase 1
conservatively; under the wire-boundary approach (see "Wire-format unwrap"
below) the gate is unnecessary because query and `@externalField` consumers
also flow through the same unwrap and the unwrapped `TableBoundReturnType` is
well-formed for any caller that already handles `TableBoundReturnType`. The
primary use case in Phase 1 is the `@mutation` slot; query-side use is covered
automatically and at no extra dispatch cost.

### Passthrough rule (data field's element type)

The single SDL field's element type must be `@table`-mapped (per trigger
condition #3, the only shape Phase 1 admits). Off-shape element types
(interface, union, `@record`, scalar, enum) yield `Rejected` from the trigger
function, the `PassthroughResolution` consumers fall through to existing
dispatch, and the type's reject path applies through `validateReturnType` with
the per-condition message described under "Mutation return-type admission".

`@table` is the right Phase 1 boundary because the existing `DmlReturnExpression`
arms (`ProjectedSingle`, `ProjectedList`) already emit
`INSERT/UPDATE/UPSERT/DELETE...RETURNING $fields(table)` projections against an
`@table` target. Phase 1 reuses both arms unchanged by anchoring the projection
on the data field's table; see "DML emitter" below.

### Wire-format unwrap at the boundary

The payload SDL type is a wire-format wrapper; the typed inner shape is the
data field's `TableBoundReturnType`. `BuildContext.resolveReturnType`
short-circuits via `unwrapPassthroughPayload` before its existing four-way
dispatch, returning the inner shape:

```java
ReturnTypeRef resolveReturnType(String targetTypeName, FieldWrapper wrapper) {
    if (unwrapPassthroughPayload(targetTypeName, this) instanceof
            PassthroughResolution.Ok ok) {
        var p = ok.info();
        return new ReturnTypeRef.TableBoundReturnType(
            p.dataElementName(), p.dataTable(), p.dataWrapper());
    }
    // existing four-way dispatch unchanged below
    GraphitronType target = types.get(targetTypeName);
    if (target instanceof TableBackedType tbt)
        return new ReturnTypeRef.TableBoundReturnType(targetTypeName, tbt.table(), wrapper);
    // ...
}
```

The wrapper passed in is the SDL field's outer wrapper around the payload type
(typically single for a mutation field). The wrapper recorded on the resolved
`TableBoundReturnType` is the *data field's* wrapper (single or list per the
SDL). This override is the wire-format unwrap: outer wrapper is the payload's,
which is wire shape; inner wrapper is the truth the model carries.

Downstream consumers of `ReturnTypeRef` see only the existing four arms. No new
`ReturnTypeRef` permit is added. `validateReturnType`, the DML emitter, and the
`MutationField.returnExpression` slot all see `TableBoundReturnType` and apply
their existing logic to the unwrapped inner shape.

The same function is called by `GraphitronSchemaBuilder.buildSchema` at the
`PlainObjectType` / `PojoResultType(_, _, null)` arms to register the data
field's identity-passthrough fetcher; see "Field classification" next.

### Field classification: IdentityPassthrough capability + PassthroughDataField permit

Two model changes, both on `ChildField`:

**1. New permit.** `ChildField.PassthroughDataField(String parentTypeName,
String name, SourceLocation location, ReturnTypeRef.TableBoundReturnType returnType)`.
The narrow `TableBoundReturnType` component (per the "narrow component types
over broad interfaces" principle) is guaranteed by trigger condition #3. The
record carries no Java backing class; its parent is a `PlainObjectType` or
`PojoResultType(_, _, null)` left in place by the unwrap (no parent-type lift).

**2. New capability sealed sub-interface.** `ChildField.IdentityPassthrough`
permits `{ConstructorField, NestingField, PassthroughDataField}`. The capability
names what is *uniformly true* across all three permits: the emitted fetcher
is `($T env) -> env.getSource()`. The two existing arms in
`FetcherEmitter.dataFetcherValue` (FetcherEmitter.java:56-57 for
`ConstructorField`, 59-60 for `NestingField`, both emitting identical
`($T env) -> env.getSource()` code) collapse to one capability check:

```java
if (field instanceof ChildField.IdentityPassthrough) {
    return CodeBlock.of("($T env) -> env.getSource()", DATA_FETCHING_ENV);
}
```

Net dispatch change at `FetcherEmitter`: −1 (two existing arms collapse to one
capability arm; the new `PassthroughDataField` permit is covered by the
capability without introducing a new arm).

A new `ChildField` permit is preferred over reusing `ConstructorField` or
`NestingField`: `ConstructorField` is `@table` parent → `@record` child;
`NestingField` carries a `TableBoundReturnType` and a recursive `nestedFields`
list. Neither shape fits a payload-typed parent passing through to a `@table`
child, and overloading either would muddy `classifyChildFieldOnTableType`'s
existing arms. The capability marker correctly groups them by uniform fetcher
behaviour without conflating their identity. Phase 3's `@record`-element
extension would add a sibling permit (`PassthroughRecordField`) under the same
capability, with a `ResultReturnType`-typed component slot.

**Schema-builder registration.** `GraphitronSchemaBuilder.buildSchema`'s
existing `PlainObjectType` skip-arm (and the `PojoResultType(_, _, null)`
equivalent) calls `unwrapPassthroughPayload` once and registers the data field
on `Ok`:

```java
case PlainObjectType ignored -> {
    if (unwrapPassthroughPayload(name, ctx) instanceof PassthroughResolution.Ok ok) {
        var p = ok.info();
        registry.put(name, p.dataFieldName(),
            new ChildField.PassthroughDataField(name, p.dataFieldName(), location,
                new ReturnTypeRef.TableBoundReturnType(
                    p.dataElementName(), p.dataTable(), p.dataWrapper())));
    }
    // else: skip as today
}
```

`FieldBuilder.classifyField` keeps its four-arm parent dispatch unchanged
(`RootType`, `TableBackedType`, `ResultType`, `ErrorType`); no fifth arm is
added. The data field's classification is delegated to the schema-builder's
side registration above, not to a new parent-dispatch case.

### Mutation return-type admission

`MutationInputResolver.validateReturnType` is unchanged at the dispatch level:
it sees `TableBoundReturnType` (the unwrapped inner shape) for passthrough
payloads and applies the existing arm's checks. Invariants #14 (return shape)
and #15 (bulk-input + single-cardinality return) operate on the data field's
wrapper, which is the right thing to check; bulk-input + single-data-field
combinations rejection-route through the existing `TableBoundReturnType` path.
No new arm is added.

The `ScalarReturnType` arm's negative message tightens. Today it produces
*"return type 'X' is not yet supported; use ID or a @table type"* for any
unrecognised non-ID return. New behaviour: when the type lands as
`ScalarReturnType` because `unwrapPassthroughPayload` returned `Rejected(reason)`
(the type is a candidate but failed a trigger condition), the validator
substitutes the trigger's reason:

```java
case ReturnTypeRef.ScalarReturnType s -> {
    if ("ID".equals(s.returnTypeName())) yield null;
    if (unwrapPassthroughPayload(s.returnTypeName(), ctx)
            instanceof PassthroughResolution.Rejected rej) {
        yield "@mutation(typeName: " + kind + ") return type '"
            + s.returnTypeName() + "': " + rej.reason()
            + "; or author a carrier with @record(record: {className: ...})";
    }
    yield "@mutation(typeName: " + kind + ") return type '"
        + s.returnTypeName() + "' is not yet supported; use ID or a @table type";
}
```

`PojoResultType(_, _, null)` candidates that fail a trigger condition fall
through to `ResultReturnType` rather than `ScalarReturnType`; the same
`Rejected`-routed message check lifts into the `ResultReturnType` arm with the
same shape. This keeps validator-mirrors-classifier honest: every trigger
criterion the classifier checks surfaces as a per-condition rejection message,
and the message text is sourced from the same function the classifier consults.

### DML emitter

The mutation field's `returnExpression` is a regular `DmlReturnExpression.ProjectedList`
or `ProjectedSingle` projecting onto the data field's `@table`, identical in
shape to a direct `: [<Table>!]` mutation return. Both arms keep their existing
single-`String returnTypeName` slot; no widening with a `TableRef` is required,
because the projection table threads through the caller (`buildDmlFetcher` and
the four kind-specific entry points in `TypeFetcherGenerator`) the same way it
does today. Single-vs-list carrier dispatch stays in the existing variant
choice (`ProjectedSingle` vs `ProjectedList`), per the existing principle in
the `DmlReturnExpression` Javadoc.

All four DML kinds are uniform here: the classifier hands the same
`ProjectedSingle` / `ProjectedList` arm to per-kind emitter sites in
`TypeFetcherGenerator.buildDmlFetcher`, and the existing
`INSERT/UPDATE/UPSERT/DELETE...RETURNING $fields(sel, table, env).fetch()` (or
`.fetchOne()`) emitter does the work. The mutation-field carrier records
(`MutationInsertTableField`, `MutationUpdateTableField`, `MutationDeleteTableField`,
`MutationUpsertTableField`) already carry `DmlReturnExpression rex`; nothing
new flows through any carrier type.

The data field's identity-passthrough fetcher (registered on the
`PassthroughDataField` permit, emitted via the `IdentityPassthrough` capability
arm in `FetcherEmitter.dataFetcherValue`) makes graphql-java traverse the
resulting `Result<Record>` through the existing `@table` per-field fetchers
without further intervention.

### Load-bearing classifier check

When the trigger fires, the data field's `@table` must equal the DML target
table for the `INSERT/UPDATE/UPSERT/DELETE...RETURNING $fields(table)`
projection to be valid (jOOQ rejects RETURNING on columns from a different
table). The check happens at mutation-field classification time, when the
classifier resolves the input table (from `@table` on the mutation's input)
and the return shape (via `unwrapPassthroughPayload`'s `dataTable`):

```java
// In FieldBuilder, when classifying a DML @mutation field whose return type
// resolved through the passthrough unwrap:
if (!inputTable.equals(returnInfo.dataTable())) {
    return Rejection.unknownName(
        "@mutation(typeName: " + kind + ") field '" + name
        + "' returns passthrough payload '" + returnInfo.payloadTypeName()
        + "' projecting onto table '" + returnInfo.dataTable()
        + "', but the input @table is '" + inputTable
        + "'; the data field's @table must match the input table"
    );
}
```

The producer site (the mutation-field classifier path that builds
`MutationInsertTableField` / siblings on a passthrough-payload return) wears
`@LoadBearingClassifierCheck(key = "passthrough-payload.data-table-equals-dml-target",
description = "...")`. The consumer site (the DML emitter's `Projected*` arm,
which assumes the projection target equals the DML target) wears the matching
`@DependsOnClassifierCheck(key = "passthrough-payload.data-table-equals-dml-target",
reliesOn = "...")`. `LoadBearingGuaranteeAuditTest` enforces the pair
automatically; no new audit-test method is needed.

This is the only new load-bearing key in Phase 1. The narrow component types
on `PassthroughDataField.returnType` (forced to `TableBoundReturnType` by the
trigger function) and on `PassthroughInfo` carry their guarantees in the type
system itself; only the cross-pair invariant (data table = input table) needs
the audit-pair to prevent silent emit-side breakage if the trigger is later
relaxed.

### What does *not* change

- No new `GraphitronType` arm. `PlainObjectType` and `PojoResultType(_, _, null)`
  stay where they are in the type registry; the unwrap is on demand at
  consultation sites, not a registry-time lift. `TypeBuilder.buildTypes`'s
  two-pass structure (TypeBuilder.java:94 javadoc) is unchanged; no new pass
  is added.
- No new `ReturnTypeRef` arm. `validateReturnType`, the DML emitter, and the
  `MutationField.returnExpression` slot all see the existing four arms; the
  payload's wire shape is resolved at the boundary into `TableBoundReturnType`.
- No new `MutationInputResolver.validateReturnType` arm. The existing four-way
  switch is unchanged at the dispatch level; only the `ScalarReturnType` and
  `ResultReturnType` arms' rejection messages tighten via the trigger's
  `Rejected(reason)` carry.
- No new `FieldBuilder.classifyField` parent-dispatch arm. The data field's
  classification is delegated to the schema-builder's `PlainObjectType` /
  `PojoResultType(_, _, null)` arms via `unwrapPassthroughPayload`.
- No widening of `DmlReturnExpression.{ProjectedSingle, ProjectedList}`. The
  existing arms keep their single-`String returnTypeName` slot; the table
  threads through the caller as today.
- No precomputed side-table or index. `unwrapPassthroughPayload` is a pure
  function called at three sites (`resolveReturnType`, the schema-builder's
  `PlainObjectType` arm, and `validateReturnType`'s rejection-message path);
  it materialises no state.
- No new generator class, no synthesized package, no Java payload class on
  disk.
- `PayloadAssembly`, `ResultAssembly`, `ErrorChannel` keep their current shape
  and scope. They remain authored-carrier-only.
- `FieldBuilder.findCanonicalCtor`, `resolveDmlPayloadAssembly`,
  `resolveErrorChannel`, `resolveServiceResultAssembly` are untouched.
- `ErrorRouter.dispatch` and `redact`, `ErrorMappings`,
  `MappingsConstantNameDedup` are unused on the Phase 1 path (no error
  channel until Phase 2).
- The runtime traversal of the DML's `Result<Record>` is graphql-java's own
  list iteration through the existing `@table` per-field fetchers; no
  graphitron code reads the list itself.

### Out of scope for Phase 1

- **Service-backed mutations returning passthrough payloads.** A `@service`
  mutation whose return type is a passthrough-payload `PlainObjectType` is
  admitted at the type-resolution level (the unwrap is consumer-agnostic),
  but the service-fetcher emitter has no path for the data field's identity
  passthrough on a service-method return value. Phase 1 keeps such cases at
  the existing service-fetcher's reject path. Phase 3 ("domain-record returns
  from `@service`") covers the service-side admission.

### Tests

**Pipeline-tier** (`graphitron/src/test/java/no/sikt/graphitron/rewrite/`),
new test class `PassthroughPayloadPipelineTest`. Trigger-admission cases run
parameterised over `DmlKind ∈ {INSERT, UPDATE, DELETE, UPSERT}` (Phase 1
covers the full DML matrix; the parameterisation makes per-kind divergence
visible if it ever appears):

Trigger admission, parameterised over DmlKind:
- `payload_listDataField_resolvesAsTableBoundReturn_<DmlKind>`: a passthrough
  payload with a list data field appears on a `@mutation` field of `<DmlKind>`;
  `BuildContext.resolveReturnType` returns a `TableBoundReturnType` whose
  `table` and `wrapper` match the data field's `@table` and SDL list wrapper.
- `payload_singleDataField_resolvesAsTableBoundReturn_<DmlKind>`: same shape,
  single-cardinality data field; the resolved wrapper is single.
- `payload_atRecordWithNullClassName_resolvesAsTableBoundReturn_<DmlKind>`:
  `@record(record: {})` without `className` admits the same way as no
  `@record`.
- `payload_listDataField_dataFieldRegistersAsPassthroughDataField_<DmlKind>`:
  the schema-builder registers a `ChildField.PassthroughDataField` with a
  narrow `TableBoundReturnType` for the payload's single field, and the field
  implements the `IdentityPassthrough` capability.
- `payload_listDataField_mutationFieldCarriesProjectedList_<DmlKind>`: the
  classified mutation-field's `rex` is the existing `ProjectedList` (no new
  arm), with `returnTypeName` equal to the data field's element type; the
  emitter's `tableRef` parameter receives the data field's `@table`.

Trigger rejection and validator messages (DmlKind-agnostic; the rejection
paths don't fork on kind, so one INSERT fixture per case is sufficient):
- `payload_withErrorsField_returnsRejected`: a two-field payload (data +
  errors) yields `Rejected` from the trigger; the mutation rejects through
  `validateReturnType.ScalarReturnType` with a per-condition message
  (*"declares 2 fields; Phase 1 admits exactly one data field"*). Phase 2
  admits this shape.
- `payload_withInterfaceField_returnsRejected`: off-shape element type yields
  `Rejected` with a per-condition message naming the `@table` rule.
- `payload_withMultipleDataFields_returnsRejected`: arity rule rejection with
  a count-naming message.
- `payload_atTableType_returnsNotCandidate`: a `@table`-mapped Object goes
  through the existing `TableBoundReturnType` path; `unwrapPassthroughPayload`
  returns `NotCandidate` and never enters the `Rejected`-message path.
- `payload_atRecordWithClassName_returnsNotCandidate`: `@record(record:
  {className: ...})` keeps R12's authored-carrier path; the unwrap returns
  `NotCandidate` and the mutation routes through the existing
  `ResultReturnType` arm.

Cross-paths:
- `payload_returnedFromQueryField_resolvesUniformly`: a query field returning
  the same passthrough payload type also unwraps to `TableBoundReturnType`
  via the same function call; query consumers do not become
  `UnclassifiedField`.
- `payload_dataTableMismatchesInputTable_rejectsAtClassifier_<DmlKind>`: when
  the data field's `@table` differs from the mutation input's `@table`, the
  load-bearing classifier check rejects with the data-table = input-table
  message. Parameterised over DmlKind because the rejection site lives
  per-kind in mutation-field classification.
- `fetcherEmitter_identityPassthrough_singleArmCoversAllThreePermits`:
  `FetcherEmitter.dataFetcherValue` emits `($T env) -> env.getSource()` for
  `ConstructorField`, `NestingField`, and `PassthroughDataField` via a single
  `IdentityPassthrough` capability check. Regression pin against the previous
  two-arm dispatch.

**Execution-tier** (`graphitron/src/test/java/no/sikt/graphitron/rewrite/sakila/`),
new sakila fixture `PassthroughPayloadDmlTest`, parameterised over `DmlKind`:

- SDL fixture (per kind): `type ActorPayload { actor: [Actor!] }` over the
  existing sakila `@table` Actor type, with one `@mutation` per kind:
  - `INSERT`: `insertActor(input: ActorInsertInput!): ActorPayload @mutation(typeName: INSERT)`.
    Driver asserts the inserted row appears in the payload's `actor` list and
    is queryable on follow-up reads.
  - `UPDATE`: `updateActor(input: ActorUpdateInput!): ActorPayload @mutation(typeName: UPDATE)`.
    Driver asserts the updated row is reflected in the payload and on
    follow-up reads.
  - `DELETE`: `deleteActor(input: ActorDeleteInput!): ActorPayload @mutation(typeName: DELETE)`.
    Driver asserts the deleted row is in the payload's `actor` list (the
    RETURNING projection captures pre-delete state) and absent on follow-up
    reads.
  - `UPSERT`: `upsertActor(input: ActorUpsertInput!): ActorPayload @mutation(typeName: UPSERT)`.
    Driver asserts insert-on-miss and update-on-hit semantics through the same
    payload shape.

All four execute against the same testcontainer fixture. The matrix verifies
the per-kind RETURNING projection emits compile-correct DSL (compile tier
catches mismatches via `mvn compile -pl :graphitron-sakila-example -Plocal-db`)
and runs end-to-end against PostgreSQL.

**Audit-tier**: one new load-bearing key,
`passthrough-payload.data-table-equals-dml-target`. The producer
(`@LoadBearingClassifierCheck`) lives on the mutation-field classifier path
that builds the `MutationInsertTableField` / siblings on a passthrough-payload
return; the consumer (`@DependsOnClassifierCheck`) lives on the DML emitter's
`Projected*` arm in `buildDmlFetcher`. `LoadBearingGuaranteeAuditTest`
enforces the pairing automatically; no new audit-test method is needed.

## Phase 2: errors-slot integration via localContext (sketch)

When the SDL payload declares two fields (one data, one errors-shaped), the
trigger function extends to admit the two-field shape and `PassthroughInfo`
extends with an `errorsField` descriptor. The same `Ok` / `NotCandidate` /
`Rejected` discipline applies; one-field and two-field shapes both reach the
admit path through `unwrapPassthroughPayload`.

The DML/service emitter wraps its result in
`DataFetcherResult.<List<Record>>newResult().data(rows).localContext(errors).build()`
on the success arm and the catch-arm equivalent on failure. The errors field's
emitted fetcher reads `env.getLocalContext()` directly, replacing today's
`PropertyDataFetcher.fetching(name)` form at FetcherEmitter.java:62-70 with
the lightweight `($T env) -> env.getLocalContext()` shape. No graphitron-runtime
envelope class is needed; graphql-java's `DataFetcherResult.localContext`
propagates to child environments.

`ErrorMappings` and `ErrorRouter`'s mapping-table half (which throwables map
to which `@error` types, dedup of mapping-list constants) are reused
unchanged. The factory-lambda half (`payloadFactoryLambda`, which constructs
an authored `PayloadClass` with an errors slot at a canonical-ctor index)
does not apply on the passthrough path; the catch arm produces a
`DataFetcherResult` with empty data and populated `localContext` directly.

Spec questions deferred to Phase 2:

- Errors-slot element type (Object vs typed union) and its surface
  implications.
- Catch-arm `data(...)` value shape: typed-empty `Result<Record>` vs null.
- Whether the `@error` types' dispatch table is rebuilt or the existing
  authored-carrier dispatch table is reused as-is.
- Whether the errors field's fetcher reuses the `IdentityPassthrough`
  capability (against `env.getLocalContext()` rather than `env.getSource()`)
  or warrants its own capability arm.

## Phase 3: domain-record returns from `@service` (sketch)

For `@service` mutations, allow the service method to return its domain object
directly (e.g. `LagreOgBeregnResultat(List<KvoteSporsmalSvar> svar)`) and have
graphitron emit identity passthrough on the SDL data field. The trigger
function extends to admit `@record`-element data fields (as a sibling
condition to today's `@table`-element); a new sibling permit
`ChildField.PassthroughRecordField` joins the `IdentityPassthrough`
capability, parameterised on a `ResultReturnType` slot rather than a
`TableBoundReturnType`. The fetcher emitter's single capability arm covers
both via the same `($T env) -> env.getSource()` code; graphql-java's per-field
fetchers on the `@record`-classified element handle accessor reads.

Spec questions deferred to Phase 3:

- Component-to-field name matching rules and rejection messages.
- Wrapper conventions when the domain record itself is shared across multiple
  payload types.
- Whether `ResultAssembly` collapses entirely (Phase 3 obsoletes its
  single-slot walk) or stays for authored carriers.
- Whether `PassthroughInfo` becomes a sealed sub-taxonomy
  (`PassthroughInfo.{Table | Record}`) when both element kinds are admitted,
  or stays as a single record with an element-kind discriminator.

## Non-goals

- **Synthesizing Java payload classes.** Explicitly rejected: the redirect away
  from this is the entire premise of R75. No `<outputPackage>.synthesized` package,
  no `SynthesizedPayloadClassGenerator`, no per-payload-type Java emission.
- **Setter-based errors injection.** Same rejection as before: violates immutability
  and would have graphitron mutate consumer-produced objects. Legacy's
  `payload.setErrors(...)` shape is not coming back.
- **Authored carriers with custom fields.** Anything beyond the trigger's admitted
  shape stays in the consumer's source tree as `@record(record: {className: ...})`,
  governed by R12's existing path.
- **Removing `@record` entirely from payload types.** The directive still has
  a job (declaring that the SDL type is record-shaped, distinguishing it from
  `@table`). Only the `className` argument becomes optional in the sense that
  omitting it is no longer a footgun: a `@record` without `className` is
  admitted by `unwrapPassthroughPayload` on the same trigger as a no-`@record`
  Object.

## Success criteria

**Phase 1 (this Spec body):**

- The reproduction case at the top compiles and serves correctly through
  `mvn -f graphitron-rewrite/pom.xml install -Plocal-db`, with no
  `@record(className)` declaration on the SDL payload type and no Java class
  on disk for the payload.
- `PassthroughPayloadPipelineTest`'s parameterised admission cases pass for
  all four `DmlKind` values; the rejection and cross-path cases pass; the
  `fetcherEmitter_identityPassthrough_singleArmCoversAllThreePermits`
  regression pin holds.
- `PassthroughPayloadDmlTest`'s execution-tier driver passes against the
  sakila testcontainer for all four DML kinds (INSERT / UPDATE / DELETE /
  UPSERT), verifying the per-kind RETURNING projection compiles and runs
  end-to-end.
- The single new load-bearing key
  `passthrough-payload.data-table-equals-dml-target` shows a producer/consumer
  pair under `LoadBearingGuaranteeAuditTest`'s scan, with no orphans on
  either side.
- `FetcherEmitter.dataFetcherValue`'s post-change form has one
  `IdentityPassthrough` capability arm where there were two `instanceof`
  checks before; existing `ConstructorField` and `NestingField` fixtures
  continue to pass through the capability arm without behaviour change.
- Authored payloads with `@record(record: {className: ...})` are unaffected:
  same generation, same rejection messages, same fixture coverage as today
  (regression pin via the existing R12 fixtures).
- A SDL fixture taken verbatim from a legacy graphitron consumer (no
  `@record(className)` on the payload, single data slot, `@mutation(typeName:
  …)`) compiles cleanly on the rewrite.

**Phases 2 and 3:** success criteria sharpened in their respective Spec
revision passes.
