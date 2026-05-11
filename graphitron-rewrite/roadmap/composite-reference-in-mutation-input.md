---
id: R130
title: "Lift reference-field deferral on @mutation inputs"
status: Spec
bucket: architecture
priority: 5
theme: mutations-errors
depends-on: []
---

# Lift reference-field deferral on @mutation inputs

## Problem

`MutationInputResolver` rejects four input-field carrier shapes on every `@mutation` field at
classify time (`MutationInputResolver.java:308-315`):

```
@mutation input '<TypeName>' field '<name>': ColumnReferenceField in @mutation inputs is not yet supported
@mutation input '<TypeName>' field '<name>': CompositeColumnField in @mutation inputs is not yet supported
@mutation input '<TypeName>' field '<name>': CompositeColumnReferenceField in @mutation inputs is not yet supported
@mutation input '<TypeName>' field '<name>': NodeId-decoded ColumnField in @mutation inputs is not yet supported
```

The R22 changelog (`graphitron-rewrite/roadmap/changelog.md`) listed these as deferred at classify
time with the wording "tracked separately"; no separate roadmap item was filed. R22's DML emitters
walk `tia.fields()` casting each entry to `InputField.ColumnField` with `extraction = Direct` and
walk `tia.fieldBindings()` against `List<InputColumnBinding.MapBinding>`. The mutation-arm
partition slots `lookupKeyFields: List<InputField.ColumnField>` and `setFields: List<InputField.
ColumnField>` (`ArgumentRef.java:235-236`) carry the same narrow element type. Both walks and
both partition slots are too narrow for any reference / composite carrier; the narrowing is
load-bearing for an invariant this item is explicitly lifting.

The forcing function is `Mutation.slettRegelverksamling` (sis), whose `SlettRegelverksamlingInput`
carries a single field `id: ID! @nodeId(typeName: "Regelverksamling")` against a Node type with
composite PK (arity &ge; 2). R50 routes that shape to `InputField.CompositeColumnReferenceField`
with `extraction = NodeIdDecodeKeys.ThrowOnMismatch`; it is gated at line 312, so a production
schema cannot build today.

## Shape of the work

The four deferred carriers split along two independent axes:

| Carrier                          | Reach axis  | Arity axis |
|----------------------------------|-------------|------------|
| `ColumnField` (NodeId-decoded)   | own table   | 1          |
| `ColumnReferenceField`           | joined      | 1          |
| `CompositeColumnField`           | own table   | &ge; 2     |
| `CompositeColumnReferenceField`  | joined      | &ge; 2     |

The two axes have independent emitter implications:

- **Reach axis (own-table vs joined).** Joined carriers carry `joinPath` and `liftedSourceColumns`
  (the source-table columns that the FK projects through to reach the target table's
  `keyColumns`). For lookup-bearing verbs (DELETE / UPDATE / UPSERT WHERE-clause) the join can
  emit as a correlated subquery or row-IN against `liftedSourceColumns` (mirrors R50's
  rooted-at-child shape on the query side). For INSERT / UPSERT INSERT-arm, the source-side write
  uses `liftedSourceColumns` directly; there is no join.
- **Arity axis (1 vs &ge; 2).** Composite carriers produce a decoded `Record<N>` from one wire
  scalar via `NodeIdDecodeKeys`. WHERE-side emits `DSL.row(c1, …, cN).eq(decoded)` /
  `.in(decoded list)`; the analogue exists today as `BodyParam.RowEq` / `RowIn`. INSERT-side emits
  one `DSL.val(decoded.value(i), col.getDataType())` per slot.

The four carriers therefore share one classifier-side gate but split into four emitter dispatch
arms; the binding-shape model needs one widening shared across all four. The four-carrier
bundling is the right grain because Phase 1's binding-shape work is shared infrastructure all
four carriers need; splitting forces a partial migration of `fieldBindings` that lives in the
model for the lifetime of the gap.

## Plan

### Phase 1: model widening — `BindingGroup` on `TableInputArg`, partition slot widening

The query side already has the right shape for composite-key bindings:
`LookupMapping.ColumnMapping.LookupArg` (`LookupMapping.java:100-163`) sealed into `MapInput`
(carries `List<InputColumnBinding.MapBinding>`) and `DecodedRecord` (carries
`CallSiteExtraction.NodeIdDecodeKeys` plus `List<InputColumnBinding.RecordBinding>`). The
*group* is the carrier of the decode-helper identity, not the per-slot binding; the slot bindings
within a group share one `decode<TypeName>` invocation.

R130 mirrors this on the mutation side rather than flattening:

- Introduce `InputColumnBindingGroup` (sealed) with two arms:
  - `MapGroup(List<InputColumnBinding.MapBinding> bindings)` — today's plain
    `@lookupKey`-on-input-field shape.
  - `DecodedRecordGroup(String sourceFieldName, CallSiteExtraction.NodeIdDecodeKeys extraction,
    List<InputColumnBinding.RecordBinding> bindings)` — composite-PK NodeId arg; one decode
    invocation produces a `Record<N>` whose slots feed N target columns. The decode helper is
    reachable through `extraction.decodeMethod()` (already the single source of truth for the
    helper reference per R50; see `GraphitronType.NodeType.decodeMethod` and
    `CallSiteExtraction.NodeIdDecodeKeys`). No new `tia.decodeHelpers` slot.
- Retype `TableInputArg.fieldBindings` from `List<InputColumnBinding.MapBinding>` to
  `List<InputColumnBindingGroup>`. Today's call sites that produce a flat list become a single
  `MapGroup` wrapper; composite-PK NodeId fields produce a `DecodedRecordGroup`.

The grouper carries the structural invariant ("all N record-bindings share one decode source") in
the type system rather than as a recurring predicate two consumers (PK-coverage check and emitter
walk) would have to reconstruct.

The mutation-arm partition slots widen in lockstep:

- Introduce sealed sub-interfaces `LookupKeyField` (permits `ColumnField`, `ColumnReferenceField`,
  `CompositeColumnField`, `CompositeColumnReferenceField`) and `SetField` (same permits).
- Retype `TableInputArg.lookupKeyFields` and `setFields` from
  `List<InputField.ColumnField>` to `List<LookupKeyField>` / `List<SetField>`. The four carrier
  records implement both interfaces.
- `TableInputArg.of` (`ArgumentRef.java:251`) updates its partition derivation to dispatch on the
  sealed interface instead of `instanceof ColumnField`.

Producer-side: `MutationInputResolver.buildTableInputArg` (and adjacent field-binding construction
around `MutationInputResolver.java:230-280`) gains arms that build `DecodedRecordGroup` from
`CompositeColumnReferenceField` / `CompositeColumnField`, and that route `ColumnReferenceField`
through `MapGroup` paired with its arity-1 binding.

Consumer-side: `buildLookupWhere` (`TypeFetcherGenerator.java:1974`) and `buildBulkLookupRowIn`
(`:1946`) switch on `InputColumnBindingGroup` rather than walking flat bindings. The `MapGroup`
arm keeps today's per-slot `.eq` / per-slot tuple emission. The `DecodedRecordGroup` arm emits one
decode invocation (`decode<TypeName>(in.get($S))`) and then a `DSL.row(c1, …, cN).eq(record)`
predicate, or for the row-in case, the analogous `.in(stream.map(decode).toList())`.

This phase is the prerequisite for the classifier gate lift; ship independent of it. No schema
change yet because no schema reaches the new groups until the classifier admits the carriers.

### Phase 2: classifier gate lift — `MutationInputResolver`

Replace the four `not yet supported` rejections at `MutationInputResolver.java:308-315` with arm
handling that builds the Phase 1 group shape:

- `ColumnField` with `extraction = NodeIdDecodeKeys` (the "NodeId-decoded ColumnField" case):
  arity-1 NodeId decode where the wire scalar resolves to a single column on the own table.
  Builds a `MapGroup` with one `MapBinding` carrying `extraction = NodeIdDecodeKeys`; the existing
  `MapBinding.extraction` slot admits this without a binding-shape change.
- `ColumnReferenceField`: arity-1 NodeId or `@reference` reaching a joined table. Builds a
  `MapGroup` with one `MapBinding`; the carrier's `joinPath` and `liftedSourceColumns` are read by
  the WHERE-side (joined-table predicate) and INSERT-side (write to source column) emitters
  respectively.
- `CompositeColumnField`: own-table composite-PK NodeId decode (arity &ge; 2). Builds a single
  `DecodedRecordGroup` carrying the carrier's `extraction: NodeIdDecodeKeys` plus N
  `RecordBinding` slots indexed `0..N-1` into the decoded `Record<N>`.
- `CompositeColumnReferenceField`: composite-PK NodeId reaching a joined table. Same as
  `CompositeColumnField` plus `joinPath` / `liftedSourceColumns` read by the join-aware emitter
  paths.

Verb-carrier admission rules:

- **Lookup-bearing verbs (DELETE, UPDATE, UPSERT ON-CONFLICT key list).** `@lookupKey` on a
  composite or reference carrier produces the corresponding group; the WHERE clause emits the
  row-tuple predicate (composite) or single-column predicate (arity-1 reference).
- **INSERT-arm carriers (INSERT verb, UPSERT INSERT-arm).** `ColumnReferenceField` is the
  canonical "FK identified by NodeId on a child row" shape; the source-side columns
  (`liftedSourceColumns`) participate in the column list. `CompositeColumnField` × INSERT is
  structurally valid (caller supplies a client-chosen composite PK) but architecturally rare and
  easy to misuse with auto-generated PKs. **R130 rejects it at classify time** with a clear
  message ("@nodeId-decoded composite-PK on INSERT input requires client-supplied PK; if
  intentional, route through individual column fields"). Lifting this rejection is a Backlog
  follow-on, not in R130's scope.
- **PK-coverage check (`MutationInputResolver.java:326-340`).** For INSERT / UPSERT, reference
  carriers contribute their `liftedSourceColumns` to the bound-set so the check passes when an
  FK column is or participates in the PK. Composite-reference INSERT carries N source columns
  per group; the bound-set walks the group's bindings to extract them.

Producer-consumer pairing via the `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck`
annotation pair (the correct annotation names; `@LoadBearingGuarantee` does not exist as a type):

- Key `mutation-input.composite-binding-arity-matches-decoded-record-arity` — producer at the
  `DecodedRecordGroup` build site in `MutationInputResolver`, consumers at the lookup-WHERE and
  INSERT-arm emitter arms in `TypeFetcherGenerator` that read the `Record<N>` slots.
- Key `mutation-input.reference-carrier-has-lifted-source-columns` — producer at the
  reference-carrier-handling arm, consumer at the INSERT-arm walk that writes to the source
  table's columns.

### Phase 3: emitter dispatch — DML verb emitters

Per the user's call (handle all DML verbs together), wire all four emitters in this phase. Four
walks (`buildLookupWhere`, `buildBulkLookupRowIn`, INSERT/UPSERT column-list/values-list, UPDATE
SET) share one operation uniformly true across all four carriers: "expand this input-field into
one or more `(target-column, value-expression)` pairs." Per the principles' distinction between
capability interfaces and sealed switches, lift that expansion into a capability rather than
re-deriving it in four parallel `InputField` switches.

**Carrier-to-column capability.** A small helper `expandWrites(InputField) → List<ColumnWrite>`
where `ColumnWrite` is sealed:

```java
sealed interface ColumnWrite {
    ColumnRef column();
    record DirectFromMap(ColumnRef column, String fieldName) implements ColumnWrite {}
    record DecodedSlot(ColumnRef column, DecodedRecordGroup group, int index) implements ColumnWrite {}
    record LiftedSourceColumn(ColumnRef sourceColumn, ValueSource source) implements ColumnWrite {}
}
```

`ValueSource` is the same sealed `(DirectFromMap | DecodedSlot)` distinction reused for the
lifted case (joined-table reference carriers produce one or N source-column writes whose values
come from either a direct map read or a decoded record slot). The four walks each switch on
`ColumnWrite`, not on `InputField`; the carrier-to-column expansion lives in one place.

The four walks consume the capability:

- **`buildLookupWhere` (`TypeFetcherGenerator.java:1974`) / `buildBulkLookupRowIn` (`:1946`).**
  Today's per-binding `.eq` chain becomes a per-`InputColumnBindingGroup` walk. `MapGroup` keeps
  today's shape. `DecodedRecordGroup` emits one decode invocation and a
  `DSL.row(c1, …, cN).eq(decoded)` / `.in(stream.map(decode).toList())` predicate. The widening
  is binding-shape, not carrier-shape; `expandWrites` does not participate.
- **INSERT / UPSERT column-list walk (`buildMutationInsertFetcher` at `:1491`,
  `buildMutationUpsertFetcher` at `:1812`).** Replace the hard-cast-to-`ColumnField` walk with a
  walk over `tia.fields().stream().flatMap(expandWrites).toList()`. Each `ColumnWrite` arm emits
  one column-name into the column list and one `DSL.val(...)` expression into the values list,
  parallel.
- **UPDATE SET-clause walk (`buildMutationUpdateFetcher` at `:1579`).** Same shape, partitioning
  on `lookupKeyFields` vs `setFields` via the Phase 1 sealed sub-interfaces. Each `setField`
  expands through `expandWrites`; the result feeds `.set(col, DSL.val(...))` chains.

Producer-consumer pairing via the `@LoadBearingClassifierCheck`/`@DependsOnClassifierCheck`
annotation pair on the two keys named in Phase 2.

### Phase 4: fixtures + tests

Phase 4 extends `nodeidfixture` with composite-PK DML mutations against R50's existing
composite-PK `Bar` Node type. New SDL surface (sketched, finalized in implementation):

```graphql
type Mutation {
  deleteBar(input: DeleteBarInput!): BarPayload @mutation(typeName: DELETE)
  updateBar(input: UpdateBarInput!): BarPayload @mutation(typeName: UPDATE)
  upsertBar(input: UpsertBarInput!): BarPayload @mutation(typeName: UPSERT)
}
input DeleteBarInput { id: ID! @nodeId(typeName: "Bar") @lookupKey }
input UpdateBarInput { id: ID! @nodeId(typeName: "Bar") @lookupKey, …setFields… }
input UpsertBarInput { id: ID! @nodeId(typeName: "Bar") @lookupKey, …setFields… }
```

INSERT-side coverage is supplied by adding a `ColumnReferenceField` shape (arity-1 FK identified
by NodeId) to an `INSERT` fixture on `nodeidfixture`; `createBar` itself stays out (since
`CompositeColumnField` × INSERT is rejected per Phase 2).

Test tiers (per `rewrite-design-principles.adoc`):

- **Classifier:** Phase 4 adds one classification test per admitted carrier arm to
  `MutationDmlNodeIdClassificationTest` (or a sibling), each asserting the carrier classifies
  rather than rejects, and adds one rejection test for the carved-out `CompositeColumnField` ×
  INSERT case.
- **Pipeline:** Phase 4 adds golden pipeline coverage pinning the emitted lookup-WHERE
  (`DSL.row(c1, c2).eq(decode<Bar>(in.get("id")))` for the composite case) and the emitted
  INSERT values list with per-slot `record.value(i)` reads for the reference case. Lives in
  `FetcherPipelineTest` if it fits, or in a new sibling pipeline test if the golden output is
  large.
- **Execution:** Phase 4 adds one PostgreSQL round-trip per admitted DML verb (DELETE, UPDATE,
  UPSERT, and INSERT for the reference shape): seed a `Bar` row via direct jOOQ, build the
  NodeId, run the mutation, assert post-state. Lives in the existing execution-tier test
  package.

### Phase 5: validator + axis-coverage roll-up

`InferenceAxisCoverage` (`graphitron-rewrite/roadmap/inference-axis-coverage.adoc`) lists the four
carriers' `Roadmap` columns; add R130 to each entry. `GraphitronSchemaValidator` arms covering the
new mutation-input dispatch flow per existing validator-arm-per-leaf discipline.

## Out of scope

- `NestingField` in `@mutation` inputs (the fifth `InputField` carrier) — stays deferred. Has its
  own R-item-shaped scope: nested input shapes require multi-table or grouped-column writes
  whose semantics live in R128 / `compound-entity-mutations` territory, not the column-shaped
  carriers this item covers.
- List-shaped reference fields (`list: true` on the carrier) — covered by `BodyParam.RowIn`
  shape on the WHERE side via `buildBulkLookupRowIn`, but INSERT-arm semantics for a list of
  references would require multi-row writes (R75 / batched-mutations territory). This item ships
  scalar (`list: false`) reference fields; the binding model already accommodates list extension
  when needed.
- `@condition`-bearing reference fields. `ColumnReferenceField.condition` is an Optional today,
  populated from `@condition`; this item ships the no-condition path. The condition-bearing arm
  is small follow-on (condition method call augments the row-eq predicate) but not the
  forcing-function shape.
- Build-time INSERT column-coverage validation widening for reference carriers (whether
  `liftedSourceColumns` participate in NOT-NULL coverage). Deferred until the jOOQ catalog
  reliably exposes per-column NULL / default metadata (R22 already deferred this).
- Cross-table writes via reference carriers. The `joinPath` is read-only — it locates the target
  for the predicate; INSERT does not traverse the path to write to the joined table.

## Notes for the Spec reviewer

- The Phase 1 model shape mirrors R50's query-side `LookupMapping.ColumnMapping.LookupArg`
  (sealed `MapInput` / `DecodedRecord` group shape; `LookupMapping.java:100-163`). The
  mutation-side group analogues (`MapGroup` / `DecodedRecordGroup`) carry the per-group
  structural invariants in the type system rather than as ambient predicates. Worth confirming
  the reviewer agrees the group shape is the right precedent (the alternative of a flat
  `List<InputColumnBinding>` would flatten this structure and force PK-coverage and emitter
  walks to reconstruct grouping ambiently).
- Decode-helper identity stays on `CallSiteExtraction.NodeIdDecodeKeys.decodeMethod` (R50's
  single source of truth, reachable from each `DecodedRecordGroup.extraction`); no new
  `tia.decodeHelpers` slot. This was a draft mistake; corrected here.
- Partition-slot widening (`lookupKeyFields` / `setFields` from `List<ColumnField>` to
  sealed `List<LookupKeyField>` / `List<SetField>`) is the type-system enforcement of the four
  admitted carrier identities. Without it, the partition slots claim a guarantee the classifier
  no longer enforces.
- Independence from R24 is asserted, not load-bearing — both items consume the same
  `CompositeColumnReferenceField` carrier but emit different code paths (R24: output-side JOIN
  with projection; R130: input-side decode + row-tuple predicate or column-list write). If R24
  re-specs first, this item's Phase 1 widening still stands.
- `CompositeColumnField` × INSERT is explicitly carved out (rejected at classify time) per
  Phase 2; the rationale is in the verb-carrier admission rules. Lifting this rejection is a
  Backlog follow-on if a forcing-function schema appears.
- Annotation pair throughout is `@LoadBearingClassifierCheck` (producer) +
  `@DependsOnClassifierCheck` (consumer). `@LoadBearingGuarantee` is principles-doc prose, not a
  type in the codebase.
- INSERT-arm of UPSERT and INSERT proper share the column-list/values-list code path via the
  Phase 3 `expandWrites` capability — the carrier-to-column expansion lives once.
