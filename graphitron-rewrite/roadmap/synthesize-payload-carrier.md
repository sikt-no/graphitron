---
id: R75
title: "Plain payload types via identity passthrough"
status: In Progress
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

## Phasing

R75 ships in three phases ordered by user-visible value. Each phase is a discrete
implementation track; downstream phases bind structurally to upstream ones but are
not schedule-coupled.

- **Phase 1: single-data-field payloads on `@mutation` (DML).** Closes the
  legacy-parity gap on `@mutation` returning a plain Object payload with
  one `@table`-element data field.
- **Phase 2: multi-field payloads via `localContext` on `@mutation` (DML).**
  Extends the trigger to multi-field payloads. Non-data fields carry state
  via graphql-java's `DataFetcherResult.localContext`; a single new
  `MutationFieldWithSlots` wrapper lifts the wrap-or-not fork into the
  type system, so the DML emitter dispatches structurally rather than on
  a `slots.isEmpty()` predicate. Phase 2 ships the wrap with an empty
  `localContext` map; the populator interface, registration mechanism,
  and per-slot trigger conditions land alongside the first real
  populator (errors, in its own roadmap item).
- **Phase 3: `@service` mutations and `@record`-element data.** Admits
  passthrough payloads on `@service` mutations (where the consumer
  constructs `DataFetcherResult` directly) and admits `@record`-element
  data fields (in addition to `@table`-element). `PassthroughInfo` carries
  a sealed `DataElement.{Table | Record}` sub-taxonomy; a sibling
  `PassthroughRecordField` permit joins the `IdentityPassthrough`
  capability for the new element kind.

All three phases are specified in implementation-ready detail below.
Promoting R75 from Spec → Ready means signing off on the entire body;
each phase ships as its own implementation cycle.

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
`BuildContext` itself, alongside `resolveReturnType`. The function consults
`ctx.types` (the type registry) and `ctx.schema` (the SDL); it does not reach
for raw jOOQ types and does not extend `BuildContext`'s parse-boundary surface
beyond what the existing `@reference`-validation messages already use, so the
"Classification belongs at the parse boundary" rule on permitted holders of
raw jOOQ types is unaffected.

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

Each new `ChildField` permit (`PassthroughDataField` here, `PassthroughSlotField`
in Phase 2, `PassthroughRecordField` in Phase 3) is added to
`TypeFetcherGenerator.IMPLEMENTED_LEAVES` so
`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`
classifies the new leaf into the four-way disjoint partition. Omitting the
update fails the audit at build time; the rule lifts the dispatch-status
bookkeeping into the same commit that introduces the permit.

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

The check lifts into a single helper method
`FieldBuilder.requireDataTableMatchesInputTable(inputTable, returnInfo,
kind, name)` called from each of the four DML-kind switch arms in
`FieldBuilder.classifyMutationField` (the `case INSERT` / `UPDATE` /
`DELETE` / `UPSERT` arms that invoke `buildDmlField` with kind-specific
constructor lambdas, see FieldBuilder.java:2579-2592) before the
resulting `Mutation<Kind>TableField` is constructed. The helper wears
`@LoadBearingClassifierCheck(key =
"passthrough-payload.data-table-equals-dml-target", description = "...")`
as the single producer site; the calling switch arms do not wear the
annotation themselves. The consumer site (the DML emitter's
`Projected*` arm, which assumes the projection target equals the DML
target) wears the matching `@DependsOnClassifierCheck(key =
"passthrough-payload.data-table-equals-dml-target", reliesOn = "...")`.
`LoadBearingGuaranteeAuditTest`'s "no duplicate producer" rule is
satisfied by construction (one annotated method, called from four
sites); the audit pair is enforced automatically with no new test
method.

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
- The runtime traversal of the DML's `Result<Record>` is graphql-java's own
  list iteration through the existing `@table` per-field fetchers; no
  graphitron code reads the list itself.

### Out of scope for Phase 1

- **Multi-field passthrough payloads.** Phase 1 admits exactly one SDL
  field on the payload type (the data field). Multi-field payloads with
  non-data slot fields are Phase 2 territory.
- **Service-backed mutations returning passthrough payloads.** A `@service`
  mutation whose return type is a passthrough-payload `PlainObjectType` is
  admitted at the type-resolution level (the unwrap is consumer-agnostic),
  but the service-fetcher emitter has no path for the data field's identity
  passthrough on a service-method return value. Phase 1 keeps such cases at
  the existing service-fetcher's reject path. Phase 3 covers the
  `@service`-side admission.
- **`@record`-element data fields.** Phase 1 admits `@table`-element data
  only. `@record`-element data is Phase 3 territory (admitted on `@service`
  mutations).

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
  {className: ...})` keeps the existing authored-carrier path; the unwrap
  returns `NotCandidate` and the mutation routes through the existing
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
- `fetcherEmitter_identityPassthrough_dispatchArmCount`: the
  `FetcherEmitter.dataFetcherValue` dispatch logic has exactly one arm
  matching `ChildField.IdentityPassthrough`, and zero arms matching the
  individual permits (`ConstructorField`, `NestingField`,
  `PassthroughDataField`) directly. Structural assertion on the
  generator's source (count of dispatch cases against the capability
  versus its permits), not on the body of any emitted method. The
  execution-tier sakila fixtures are the primary signal that all three
  permits behave identically through graphql-java.

**Execution-tier** (`graphitron-sakila-example/src/test/java/no/sikt/graphitron/sakila/`),
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

## Phase 2: multi-field payloads via localContext

Phase 1 admits single-data-field passthrough payloads. Phase 2 extends the
trigger to multi-field payloads, where one field carries the DML row(s)
(the data field, as in Phase 1) and any number of additional fields carry
state via graphql-java's `DataFetcherResult.localContext`. The mechanism is
general; specific slot semantics (errors, affected-row counts, warnings,
echoed-back input) ship as separate downstream roadmap items per
application. Phase 2 ships a usable skeleton: multi-field payloads admit
through the same boundary as Phase 1; their non-data fields render as null
until a per-slot populator wires up.

### Trigger extension

`unwrapPassthroughPayload`'s condition #2 changes from "the SDL Object
declares exactly one field" to "the SDL Object declares at least one field
whose element type is `@table`-mapped (the data field) and zero or more
additional non-`@table`-element fields (slot fields)". Conditions #1 (the
type is registered as `PlainObjectType` or `PojoResultType(_, _, null)`)
and #3 (the data field's element is `@table`-mapped, now implied by the
new #2) stay.

`PassthroughInfo` extends with a slot-descriptor list; Phase 1 callers see
an empty list and behave identically:

```java
record PassthroughInfo(
    String payloadTypeName,
    String dataFieldName,
    String dataElementName,
    TableRef dataTable,
    FieldWrapper dataWrapper,
    List<SlotDescriptor> slots
) {}

record SlotDescriptor(
    String name,
    String elementName,
    FieldWrapper wrapper
) {}
```

The trigger rejects payloads where more than one field has an
`@table`-mapped element type, with `Rejected("ambiguous data field: both
'X' and 'Y' have @table-mapped elements; passthrough payloads admit
exactly one @table-element data field; multi-@table-element shapes are
tracked under R122")`. Multi-`@table`-element payloads form the
compound-mutation pattern (parent entity row + child normalised rows in
one INSERT) covered by R122; R75's structural choices (single `dataElement`
on `PassthroughInfo`, `inner: DmlTableField` on `MutationFieldWithSlots`,
single `IdentityPassthrough` capability) do not block that future
direction. R122's spec lifts `PassthroughInfo` to a sealed sub-taxonomy
(`Single | Compound`) and adds compound permits to `DmlTableField`; the
existing `MutationFieldWithSlots` wrapper covers compound carriers
without modification.

Slot fields with `@record`-mapped element types are also rejected (Phase
3 territory); the `Rejected` reason names the off-shape slot.

### Field classification

A new `ChildField` permit handles slot fields:

```java
record PassthroughSlotField(
    String parentTypeName,
    String name,
    SourceLocation location,
    String elementName,
    FieldWrapper wrapper
) implements ChildField {}
```

`PassthroughSlotField` does *not* join the `IdentityPassthrough` capability:
its emit is name-parameterised (`env.getLocalContext().get(name)`), not the
literal `env.getSource()` shared by `IdentityPassthrough`'s permits.
Mixing them under one capability would force the capability arm to read a
field-level `name` slot to decide which env method to call, which is the
sealed-switch shape the principle steers away from. A separate dispatch
arm in `FetcherEmitter.dataFetcherValue` is the correct shape:

```java
if (field instanceof ChildField.PassthroughSlotField slot) {
    return CodeBlock.of(
        "($T env) -> env.getLocalContext() == null ? null "
            + ": (($T<$T,$T>) env.getLocalContext()).get($S)",
        DATA_FETCHING_ENV, MAP_CLASS, STRING_CLASS, OBJECT_CLASS, slot.name());
}
```

The null-guard accounts for the consumer-controlled-population case where
the localContext map may not have been populated.

`GraphitronSchemaBuilder.buildSchema`'s `PlainObjectType` arm registers the
data field (per Phase 1) and additionally iterates the slots, registering
each as a `PassthroughSlotField`:

```java
case PlainObjectType ignored -> {
    if (unwrapPassthroughPayload(name, ctx) instanceof PassthroughResolution.Ok ok) {
        var p = ok.info();
        registry.put(name, p.dataFieldName(),
            new ChildField.PassthroughDataField(name, p.dataFieldName(), location,
                new ReturnTypeRef.TableBoundReturnType(
                    p.dataElementName(), p.dataTable(), p.dataWrapper())));
        for (var slot : p.slots()) {
            registry.put(name, slot.name(),
                new ChildField.PassthroughSlotField(name, slot.name(), location,
                    slot.elementName(), slot.wrapper()));
        }
    }
}
```

### Mutation-field wrapping for slot carriage

The slot-carrying concern is orthogonal to the DML kind: any mutation
field that returns a passthrough payload with non-data slots needs the
same `DataFetcherResult.localContext(slots)` wrap, regardless of whether
the underlying DML is INSERT / UPDATE / DELETE / UPSERT (and regardless,
in future, of whether the mutation is single-table or compound per
R122). Lifting the concern into a single wrapper record keeps the DML
carrier records flat and makes the wrap-or-not fork visible in the type
system rather than as a `slots.isEmpty()` predicate the emitter forks on.

A new `MutationField` permit:

```java
record MutationFieldWithSlots(
    DmlTableField inner,
    List<SlotDescriptor> slots
) implements MutationField {}
```

The classifier produces `MutationFieldWithSlots(inner=<bare DML
carrier>, slots=<non-empty list>)` when the resolved `PassthroughInfo`
has slots; otherwise the classifier produces the bare DML carrier
directly. The existing four DML kind carriers
(`MutationInsertTableField`, `MutationUpdateTableField`,
`MutationDeleteTableField`, `MutationUpsertTableField`) keep their
current shape: no per-record widening, no per-kind sealed split. The
component slot `inner: DmlTableField` is narrow (per the "narrow
component types" principle), so a `MutationFieldWithSlots` is
structurally guaranteed to wrap a DML mutation, not an arbitrary
`MutationField`.

Other consumers of `MutationField` (the schema validator, mappings-
constant dedup, etc.) gain one new switch arm that recurses into
`inner`. The wrap concern lives at exactly one site (the DML emitter's
top-level dispatch); it does not propagate as a per-kind branch.

Forward direction: R122 (compound mutations, parent + child rows in
one INSERT) adds new permits to `DmlTableField`. The wrapper covers
them without modification because `inner: DmlTableField` accepts any
DML kind; compound carriers wrap exactly the same way as the existing
four. Conversely, if `MutationFieldWithSlots` later needs a sibling
(for example a service-side variant), the single permit lifts to a
sealed sub-taxonomy at that point with no churn on existing consumers.

### DML emitter

`TypeFetcherGenerator.buildDmlFetcher` dispatches on `MutationField`,
unwrapping `MutationFieldWithSlots` to share the per-kind emit core:

```java
return switch (field) {
    case MutationFieldWithSlots ws ->
        wrapInDataFetcherResult(emitDmlCore(ws.inner()), ws.slots());
    case DmlTableField bare -> emitDmlCore(bare);
    // ... existing non-DML arms unchanged
};
```

`emitDmlCore` runs the existing per-kind emit
(`INSERT/UPDATE/UPSERT/DELETE...RETURNING $fields(sel, table, env).fetch()`
or `.fetchOne()`); `wrapInDataFetcherResult` applies the
`DataFetcherResult.<Result<Record>>newResult().data(rows).localContext(slots).build()`
shell. Single-vs-list cardinality stays in the existing `ProjectedSingle`
vs `ProjectedList` choice per the existing `DmlReturnExpression`
Javadoc; the wrap is uniform across both.

### Slot population

Phase 2 ships the wrap mechanism and the per-slot fetcher emit; it does
*not* ship a populator framework. When the classifier produces a
`MutationFieldWithSlots`, the DML emitter wraps the result in
`DataFetcherResult.localContext(emptyMap())`. Every slot fetcher reads
through the null-guarded `env.getLocalContext().get($S)` shape and
yields null at request time.

The shape of any populator interface, the registration mechanism, and
the per-slot trigger conditions are all undefined in Phase 2. They land
alongside the first real populator (the canonical candidate is errors,
which is a separate roadmap item with its own design space): the first
populator's emit needs will inform the contract surface. Designing the
populator API in advance, against zero consumers, would be a "premature
framework" against the strategic principle of extracting infrastructure
from working code.

What this means in practice: a SDL author who writes a passthrough
payload with `errors: [Error!]` or any other non-data slot will see the
data field render correctly and the slot fields render as null until
the per-slot populator's roadmap item ships. That's a usable
intermediate state.

### What does *not* change (from Phase 1)

- `BuildContext.resolveReturnType`'s short-circuit and the inner
  `TableBoundReturnType` shape are unchanged.
- `MutationInputResolver.validateReturnType` is unchanged at the dispatch
  level; it sees `TableBoundReturnType` for both single-field and
  multi-field passthrough payloads.
- `DmlReturnExpression.{ProjectedSingle, ProjectedList}` are unchanged.
- The `IdentityPassthrough` capability and its three Phase 1 permits are
  unchanged. `PassthroughSlotField` does not join the capability.
- The four DML kind carriers (`MutationInsertTableField`,
  `MutationUpdateTableField`, `MutationDeleteTableField`,
  `MutationUpsertTableField`) keep their existing shape: no per-record
  widening, no per-kind sealed split. The slot concern lives on the
  `MutationFieldWithSlots` wrapper instead.
- The Phase 1 load-bearing classifier check
  (`passthrough-payload.data-table-equals-dml-target`) applies unchanged;
  the data-table check happens on the data field regardless of slot
  count.

### Out of scope for Phase 2

- **Slot populators.** Phase 2 wires the wrap with an empty
  `localContext` map; every slot renders as null at request time. The
  populator interface, registration mechanism, and per-slot trigger
  conditions land alongside the first real populator (errors is the
  canonical candidate) in its own roadmap item, where the contract
  surface can be informed by the populator's actual emit needs rather
  than predicted in advance.
- **`@service` multi-field payloads.** Phase 2 covers `@mutation` (DML)
  multi-field payloads only, where graphitron owns the emit and can wrap
  in `DataFetcherResult`. `@service` multi-field payloads route through
  Phase 3 instead, where the consumer constructs `DataFetcherResult`
  directly.
- **Slot fields with `@table` element type.** The trigger forbids more
  than one `@table`-mapped field; multi-`@table`-element payloads form
  the compound-mutation pattern tracked under R122 (parent entity row +
  child normalised rows in one INSERT) and fall back to authored
  carriers (`@record(record: {className: ...})`) until R122 ships.
- **Slot fields with `@record` element type.** Phase 3 territory; the
  trigger rejects them at admission.

### Tests

Pipeline-tier additions to `PassthroughPayloadPipelineTest` (parameterised
over `DmlKind` where applicable):

- `payload_dataPlusSingleScalarSlot_resolvesWithSlotDescriptor_<DmlKind>`:
  a payload with one `@table`-element data field and one scalar slot
  resolves; the slot descriptor's name and element type appear on
  `PassthroughInfo.slots`.
- `payload_dataPlusMultipleSlots_preservesSlotOrder_<DmlKind>`: multi-slot
  admission preserves slot order from SDL.
- `payload_twoTableElementFields_returnsRejected`: two `@table`-element
  fields yield `Rejected("ambiguous data field: both 'X' and 'Y' have
  @table-mapped elements; passthrough payloads admit exactly one
  @table-element data field; multi-@table-element shapes are tracked
  under R122")`.
- `payload_slotWithRecordElement_returnsRejected`: slot field with
  `@record`-element type yields `Rejected("slot field 'X' has
  @record-mapped element 'Y'; Phase 3 covers @record-element data,
  Phase 2 admits scalar/enum/list-of-scalar slots only")`.
- `payload_dataPlusSlot_schemaBuilderRegistersDataAndSlotPermits_<DmlKind>`:
  the schema-builder registers the data permit (Phase 1 path) and
  iterates the slot list, registering each as `PassthroughSlotField`.
- `payload_dataPlusSlot_classifierProducesMutationFieldWithSlots_<DmlKind>`:
  the classified mutation is wrapped as `MutationFieldWithSlots`, with
  `inner` matching the corresponding `Mutation<Kind>TableField` and
  `slots` matching the SDL slot order. Phase 1 single-field payloads
  (no slots) classify as the bare DML carrier (regression pin).
- `fetcherEmitter_passthroughSlotField_dispatchArmCount`: the
  `FetcherEmitter.dataFetcherValue` dispatch logic has exactly one arm
  matching `ChildField.PassthroughSlotField`. Structural assertion on
  the generator's source (count of dispatch cases), not on the body of
  any emitted method.

Execution-tier (sakila): one new fixture verifying multi-field admission
compiles and runs end-to-end with slots rendering null:

- `PassthroughPayloadMultiFieldDmlTest`: SDL
  `type ActorPayload { actor: [Actor!]; warningCount: Int; warnings: [String!] }`.
  Per-DmlKind drivers assert the data field renders the
  inserted/updated/deleted/upserted row, and the slot fields render null
  (no populator wired in Phase 2). The execution tier is the primary
  signal that the `DataFetcherResult.localContext(emptyMap())` wrap
  composes through graphql-java's traversal correctly; pipeline-tier
  tests do not assert on emitted method body content.

Audit-tier: no new load-bearing keys in Phase 2.

## Phase 3: @service mutations and @record-element data

Phases 1 and 2 cover `@mutation` (DML) passthrough payloads with
`@table`-mapped data elements. Phase 3 extends along two orthogonal axes
that ship together as the `@service`-side admission story:

- **Consumer**: the trigger admits `@service` mutations whose return type
  is a passthrough payload. The `@service` consumer constructs the
  `DataFetcherResult` directly (no graphitron-side DML wrap or slot
  populators needed); graphitron registers the identity-passthrough
  fetchers on the SDL data field and any slot fields, and the existing
  `@service` unwrap logic in `ServiceCatalog` handles wrapper composition
  (`Optional<T>`, `CompletableFuture<T>`, etc.).
- **Element kind**: the trigger admits data fields whose element type is
  `@record`-mapped, in addition to `@table`-mapped. Service methods may
  return a domain record (a Java record or POJO bound via
  `@record(record: {className: ...})`) and have graphitron emit identity
  passthrough on the data field; graphql-java's per-field fetchers on the
  `@record`-classified element handle the accessor reads.

### Trigger extension

Condition #3 changes from "the data field's element type is registered as
`TableBackedType`" to "the data field's element type is registered as
`TableBackedType` OR `ResultType`". The other conditions stay; the
multi-field admission and slot rejection from Phase 2 carry over unchanged
(the data field is still the unique element-bound field, where "element-
bound" now spans `@table` and `@record`).

`PassthroughInfo`'s data-element descriptor becomes a sealed sub-taxonomy.
Phase 1 and 2 callers see `DataElement.Table`; Phase 3 introduces the
sibling:

```java
sealed interface DataElement {
    String name();
    FieldWrapper wrapper();

    record Table(String name, TableRef table, FieldWrapper wrapper)
        implements DataElement {}

    record Record(String name, String fqClassName, FieldWrapper wrapper)
        implements DataElement {}
}

record PassthroughInfo(
    String payloadTypeName,
    String dataFieldName,
    DataElement dataElement,
    List<SlotDescriptor> slots
) {}
```

The sealed sub-taxonomy carries the genuinely-distinct information per
element kind (`TableRef` for `@table`, `String fqClassName` for `@record`)
without forcing one into the other's shape; this is the resolution to
the deferred question carried from Phase 1's draft.

### Wire-format unwrap

`BuildContext.resolveReturnType`'s short-circuit dispatches on the
`DataElement` sub-arm:

```java
if (unwrapPassthroughPayload(targetTypeName, this) instanceof
        PassthroughResolution.Ok ok) {
    return switch (ok.info().dataElement()) {
        case DataElement.Table t -> new ReturnTypeRef.TableBoundReturnType(
            t.name(), t.table(), t.wrapper());
        case DataElement.Record r -> new ReturnTypeRef.ResultReturnType(
            r.name(), r.wrapper(), r.fqClassName());
    };
}
```

`@table`-element payloads continue to unwrap to `TableBoundReturnType`
(the DML emitter path from Phase 1 and 2). `@record`-element payloads
unwrap to `ResultReturnType` (the existing authored-carrier path's
`ReturnTypeRef` arm), which `ServiceCatalog`'s `@service` classifier
admits.

### Field classification

A new `ChildField` permit handles `@record`-element data fields, joining
the `IdentityPassthrough` capability (the emit is the same
`($T env) -> env.getSource()` graphql-java traverses through to the
per-field fetchers on the `@record` element):

```java
record PassthroughRecordField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef.ResultReturnType returnType
) implements ChildField, ChildField.IdentityPassthrough {}
```

The narrow `ResultReturnType` component (per the "narrow component types"
principle) is guaranteed by trigger condition #3's `@record` arm. The
capability membership means `FetcherEmitter`'s existing
`IdentityPassthrough` arm covers it without a new dispatch case. Net
dispatch change at `FetcherEmitter` from Phases 1 through 3: −2 (Phase 1
collapsed two existing arms to one; Phase 2 added a separate slot arm;
Phase 3 reuses the capability for the new permit).

`GraphitronSchemaBuilder.buildSchema`'s `PlainObjectType` arm extends to
dispatch on the `DataElement` arm:

```java
case PlainObjectType ignored -> {
    if (unwrapPassthroughPayload(name, ctx) instanceof PassthroughResolution.Ok ok) {
        var p = ok.info();
        switch (p.dataElement()) {
            case DataElement.Table t ->
                registry.put(name, p.dataFieldName(),
                    new ChildField.PassthroughDataField(name, p.dataFieldName(), location,
                        new ReturnTypeRef.TableBoundReturnType(
                            t.name(), t.table(), t.wrapper())));
            case DataElement.Record r ->
                registry.put(name, p.dataFieldName(),
                    new ChildField.PassthroughRecordField(name, p.dataFieldName(), location,
                        new ReturnTypeRef.ResultReturnType(
                            r.name(), r.wrapper(), r.fqClassName())));
        }
        // slot registration per Phase 2
        for (var slot : p.slots()) {
            registry.put(name, slot.name(),
                new ChildField.PassthroughSlotField(name, slot.name(), location,
                    slot.elementName(), slot.wrapper()));
        }
    }
}
```

### `@service` classifier admission

`ServiceCatalog`'s `@service` mutation classifier admits passthrough-payload
returns. Today it rejects them (per Phase 1's "Out of scope"); Phase 3
lights up the path.

For each `@service` mutation field, the classifier:

1. Resolves the SDL return type via `unwrapPassthroughPayload`. On `Ok`
   the `DataElement` arm tells the classifier which method-return shape
   to expect.
2. Reflects on the service method's declared return type, applying the
   existing `@service` wrapper unwrap (`Optional<T>`, `CompletableFuture<T>`,
   etc.) to recover the inner type.
3. Verifies the inner type matches the data element:
   - `DataElement.Table(name, table, wrapper)`: the inner type must be
     `Result<<TableRecord>>` (or `<TableRecord>` for single-cardinality,
     or a `DataFetcherResult` wrapping either; see "Wrapper composition"
     below).
   - `DataElement.Record(name, fqClassName, wrapper)`: the inner type
     must be the class named `fqClassName` (or a list / optional thereof
     matching `wrapper`, or a `DataFetcherResult` wrapping it).
4. If verification fails, the classifier rejects with a per-mismatch
   message naming the SDL data element, the method's return type, and
   the gap.

Validator-mirrors-classifier coverage extends to the new Phase 3
criteria. Every classifier rejection above must surface as a
build-time error via the validator: the `@service` mutation classifier
emits its rejections through the same typed `Resolved` shape Phase 1's
`ScalarReturnType` and `ResultReturnType` arms route through (or, where
the rejection lives at the schema-validation layer rather than at
return-type resolution, through `GraphitronSchemaValidator`'s existing
arm for the `@service` mutation field). New rejection categories the
validator carries: data-element-vs-method-return-type mismatch (one
message shape per `DataElement` arm), `@record`-element fqClassName
not loadable (existing R-x machinery), wrapper-vs-element-kind
combinations explicitly rejected (e.g. `Mono<Result<TableRecord>>`
treated as ambiguous if both are admitted as wrappers; the spec keeps
the shape simple by requiring at most one wrapper layer outside
`DataFetcherResult`).

The matched service method is the source value for graphql-java
traversal. graphql-java calls the data field's identity-passthrough
fetcher (`($T env) -> env.getSource()`), which yields the service
method's return value; the per-field fetchers on the `@table` or
`@record` element type read off that source as today.

Slot fields on a `@service` mutation payload are admitted automatically:
the service method returns a `DataFetcherResult` whose `.data(value)`
carries the domain object (or `Result<Record>`) and whose
`.localContext(slots)` carries the slot map. Graphitron registers the
slot fetchers per Phase 2; the service method populates the slot map
directly. No graphitron-side wrap or populator emit is involved for
`@service` mutations; the consumer's returned `DataFetcherResult` flows
through unchanged.

### Wrapper composition

Service methods may return `T`, `Optional<T>`, `CompletableFuture<T>`,
`Mono<T>`, etc. The existing `@service` unwrap logic in `ServiceCatalog`
strips these wrappers before classifying the inner `T`; Phase 3's
admission composes with the existing unwrap unchanged.

`DataFetcherResult<T>` is treated as another wrapper layer: a service
method returning `DataFetcherResult<T>` is admitted with the same
trigger as one returning `T`, with the consequence that the consumer
opts into slot population by returning `DataFetcherResult` explicitly.
A service method returning bare `T` and matching a payload that has
slot fields is admitted (the slots render as null), parallel to the
DML case in Phase 2 with no populator wired.

The matrix of wrapper × element kind (4 wrappers × 2 element kinds = 8
combinations) is verified at the execution tier (see "Tests" below).

### What does *not* change (from Phases 1 and 2)

- `BuildContext.resolveReturnType`'s short-circuit shape is unchanged;
  only the switch in the `Ok` arm extends to dispatch on `DataElement`.
- The `IdentityPassthrough` capability gains one permit
  (`PassthroughRecordField`) but its emit is unchanged
  (`($T env) -> env.getSource()`).
- DML mutation paths (Phase 1 single-field, Phase 2 multi-field) are
  unchanged.
- `MutationInputResolver.validateReturnType` is still unchanged at the
  dispatch level; it sees `TableBoundReturnType` (Phase 1+2) or
  `ResultReturnType` (Phase 3) and applies its existing arm rules.
- The Phase 1 load-bearing classifier check applies unchanged for
  `@table`-element data; no equivalent check is needed for
  `@record`-element (no DML projection to misalign).
- `PassthroughSlotField` and the `MutationFieldWithSlots` wrapper from
  Phase 2 are unchanged. `@service` mutations do not produce a
  `MutationFieldWithSlots`; the consumer's returned `DataFetcherResult`
  carries the slot map directly.

### Out of scope for Phase 3

- **`@record`-element data on `@mutation` (DML).** Phase 3 admits
  `@record`-element data on `@service` mutations only. DML mutations
  returning `@record`-element payloads would require a "DML row →
  domain record" conversion step at the emitter; that's a separate
  roadmap item.
- **Service-side slot populators.** Slot populators remain per-slot
  roadmap items (Phase 2's "Out of scope" carries forward). The
  `@service` consumer can populate slots manually via
  `DataFetcherResult.localContext` without graphitron-side populator
  wiring.
- **`@service` queries returning passthrough payloads.** Phase 3 covers
  `@service` mutations specifically; query-side `@service` admission
  flows through the same trigger (the unwrap is consumer-agnostic) but
  the per-query fetcher emit has not been audited end-to-end. Treat
  query-side `@service` passthrough as a follow-up item.

### Tests

Pipeline-tier additions to `PassthroughPayloadPipelineTest`:

- `payload_atRecordElementData_resolvesAsResultReturnType`: a payload
  with a `@record`-element data field unwraps to `ResultReturnType`
  (not `TableBoundReturnType`).
- `payload_atRecordElementData_dataFieldRegistersAsPassthroughRecordField`:
  the schema-builder dispatches on `DataElement.Record` and registers
  `PassthroughRecordField`.
- `payload_atServiceMutation_admittedByServiceCatalog_<elementKind>`:
  a `@service` mutation returning a passthrough-payload type admits at
  the `@service` classifier (no `UnclassifiedField`). Parameterised
  over `{Table, Record}`.
- `payload_atServiceMutation_methodReturnTypeMismatch_rejects_<elementKind>`:
  service method's declared return type doesn't match the data
  element; classifier rejects with the per-mismatch message.
  Parameterised over `{Table, Record}`.
- `payload_atServiceMutation_wrapperUnwraps_<wrapperKind>_<elementKind>`:
  service method's `Optional<T>` / `CompletableFuture<T>` / `Mono<T>` /
  `DataFetcherResult<T>` wrapper unwraps to the inner `T` for trigger
  matching. Parameterised over `{T, Optional, CompletableFuture, Mono,
  DataFetcherResult} × {Table, Record}` (10 cases; the bare `T` case
  exercises the no-wrapper baseline alongside the four wrapper kinds).
- `fetcherEmitter_identityPassthrough_dispatchArmCount_phase3`: the
  `IdentityPassthrough` capability now permits four records
  (`ConstructorField`, `NestingField`, `PassthroughDataField`,
  `PassthroughRecordField`); the dispatch logic still has exactly one
  arm matching the capability and zero against the individual permits.
  Structural assertion (count of dispatch cases), extending Phase 1's
  Phase-1-permit-count pin to the Phase-3-permit count.
- `payload_atServiceMutation_withSlots_methodReturnsDataFetcherResult`:
  a `@service` payload with both data and slot fields admits when the
  method returns `DataFetcherResult<T>`; the slot fetchers read from
  `localContext` populated by the consumer.

Execution-tier (sakila): a new `PassthroughPayloadServiceTest`
parameterising over `(elementKind, wrapper)`:

- SDL: two payload variants in parallel:
  - `type ActorTablePayload { actor: [Actor!] }` (`@table`-element).
  - `type ActorRecordPayload { actor: ActorRecord! }`
    (`@record`-element, where `ActorRecord` is a Java record bound via
    `@record(record: {className: "...sakila.ActorRecord"})`).
- Per-wrapper drivers (`T`, `Optional<T>`, `CompletableFuture<T>`,
  `Mono<T>`) for each element kind: invoke the service mutation
  against the testcontainer, assert response shape and follow-up
  reads. 8 driver cases (2 element kinds × 4 wrappers).
- One additional driver per element kind exercises a multi-field
  payload over `DataFetcherResult<T>` to verify slot population from
  the consumer side. 2 more driver cases.
- Total: 10 execution-tier driver cases.

Audit-tier: no new load-bearing keys in Phase 3.

## Non-goals

- **Synthesizing Java payload classes.** Explicitly rejected: the redirect away
  from this is the entire premise of R75. No `<outputPackage>.synthesized` package,
  no `SynthesizedPayloadClassGenerator`, no per-payload-type Java emission.
- **Setter-based errors injection.** Same rejection as before: violates immutability
  and would have graphitron mutate consumer-produced objects. Legacy's
  `payload.setErrors(...)` shape is not coming back.
- **Authored carriers with custom fields.** Anything beyond the trigger's
  admitted shape stays in the consumer's source tree as `@record(record:
  {className: ...})` on the existing authored-carrier path.
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
  `fetcherEmitter_identityPassthrough_dispatchArmCount` regression pin
  holds.
- `PassthroughPayloadDmlTest`'s execution-tier driver passes against the
  sakila testcontainer for all four DML kinds (INSERT / UPDATE / DELETE /
  UPSERT), verifying the per-kind RETURNING projection compiles and runs
  end-to-end.
- The single new load-bearing key
  `passthrough-payload.data-table-equals-dml-target` shows a producer/consumer
  pair under `LoadBearingGuaranteeAuditTest`'s scan, with no orphans on
  either side; `FieldBuilder.requireDataTableMatchesInputTable` is the
  single producer site, called from all four DML-kind classifier paths.
- `FetcherEmitter.dataFetcherValue`'s post-change form has one
  `IdentityPassthrough` capability arm where there were two `instanceof`
  checks before; existing `ConstructorField` and `NestingField` fixtures
  continue to pass through the capability arm without behaviour change.
- Authored payloads with `@record(record: {className: ...})` are unaffected:
  same generation, same rejection messages, same fixture coverage as today
  (regression pin via the existing authored-carrier fixtures).
- A SDL fixture taken verbatim from a legacy graphitron consumer (no
  `@record(className)` on the payload, single data slot, `@mutation(typeName:
  …)`) compiles cleanly on the rewrite.

**Phase 2:**

- `PassthroughPayloadPipelineTest`'s Phase 2 admission cases pass for all
  four `DmlKind` values; the
  `fetcherEmitter_passthroughSlotField_dispatchArmCount` regression pin
  holds.
- The classifier produces `MutationFieldWithSlots` wrapping the bare
  per-kind DML carrier when the resolved `PassthroughInfo` has slots;
  Phase 1 single-data-field payloads continue to classify as the bare DML
  carrier (regression pin via existing Phase 1 fixtures).
- `PassthroughPayloadMultiFieldDmlTest`'s execution-tier driver passes
  for all four DML kinds with slot fields rendering null (no populator
  wired in Phase 2; the `DataFetcherResult.localContext(emptyMap())`
  wrap composes through graphql-java cleanly).
- No populator framework or catalog ships in Phase 2; the populator
  contract surface lands alongside the first real populator in its own
  roadmap item.

**Phase 3:**

- `PassthroughPayloadPipelineTest`'s Phase 3 admission cases pass for both
  element kinds and all four wrapper kinds (10 parameterised cases).
- `PassthroughPayloadServiceTest`'s execution-tier matrix passes (10
  driver cases over 2 element kinds × 4 wrappers + 2 multi-field
  variants over `DataFetcherResult`).
- The `fetcherEmitter_identityPassthrough_dispatchArmCount_phase3`
  regression pin holds; the capability now permits four records
  (`ConstructorField`, `NestingField`, `PassthroughDataField`,
  `PassthroughRecordField`) and the dispatch logic still has exactly one
  arm matching the capability.
- `PassthroughInfo`'s `DataElement` sealed sub-taxonomy compiles; Phase 1
  and Phase 2 callers see `DataElement.Table` and behave unchanged.
- `ServiceCatalog`'s `@service` mutation classifier admits passthrough
  payloads (Phase 1's "Out of scope" item lights up).
- Authored payloads with `@record(record: {className: ...})` are still
  unaffected across all three phases (regression pin via the existing
  authored-carrier fixtures).
