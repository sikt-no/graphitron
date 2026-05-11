---
id: R130
title: "Admit @nodeId-decoded carriers in @mutation inputs and @lookupKey bindings"
status: Ready
bucket: architecture
priority: 5
theme: mutations-errors
depends-on: []
---

# Admit @nodeId-decoded carriers in @mutation inputs and @lookupKey bindings

## Problem

The canonical "delete a row by its NodeId" mutation shape

```graphql
input SlettRegelverksamlingInput @table(name: "regelverksamling") {
    id: ID! @nodeId @lookupKey
}
extend type Mutation {
    slettRegelverksamling(input: SlettRegelverksamlingInput!): SlettRegelverksamlingPayload!
        @mutation(typeName: DELETE)
}
```

does not build. `regelverksamling` has a composite primary key, so post-R131 the `id` field
classifies as `InputField.CompositeColumnField` carrying `extraction = NodeIdDecodeKeys`. The
classifier is correct; two downstream gates block this shape:

1. **`EnumMappingResolver.buildLookupBindings:296-303`** rejects `@lookupKey` on any
   `@nodeId`-decorated input field at the SDL boundary (the R131 cleanup `beb0e92` unified the
   single-PK and composite-PK arities into one SDL-boundary check) with *"@lookupKey on an
   @nodeId-decoded input field is not supported … expose the decoded key column(s) explicitly
   via @field instead, or move @lookupKey to the outer argument"*.
2. **`MutationInputResolver:293`** rejects `CompositeColumnField` (and three sibling carriers) in
   any `@mutation` input with *"CompositeColumnField in @mutation inputs is not yet supported"*.

The R22 changelog listed all four sibling carriers (`ColumnField` with `NodeIdDecodeKeys`,
`ColumnReferenceField`, `CompositeColumnField`, `CompositeColumnReferenceField`) as deferred at
classify time. Post-R131, only the two same-table carriers (`ColumnField` with
`NodeIdDecodeKeys`, `CompositeColumnField`) have a forcing-function schema; the two genuinely-
joined reference carriers are now only reachable from real FK-target schemas, none of which exist
in sis today. R130 lifts the two reachable carriers; the joined-reference half follows R24's
"wait for a forcing-function schema" discipline.

## Implicated load-bearing bug

R131's follow-up chain introduced a structural-rejection guard at
`EnumMappingResolver:296-303` keyed `lookup-key-input-field-non-nodeid-decoded`. The guard
papers over a bug one layer up: `buildLookupBindings:327` calls
`deriveExtraction(cf.typeName(), cf.column(), …)`, never reading `cf.extraction()` — the
resolver-supplied `NodeIdDecodeKeys` is discarded and a generic extraction is re-derived from
raw column metadata. R130 fixes the bug at source so the guard retires. See *Notes for the
Spec reviewer* for the alternatives considered.

## Shape of the work

Two same-table NodeId-decoded carriers come in scope; the two genuinely-joined reference
carriers defer:

| Carrier                          | Status post-R130       | Forcing function                           |
|----------------------------------|------------------------|--------------------------------------------|
| `ColumnField` (NodeId-decoded)   | admitted               | single-PK NodeId DELETE/UPDATE/UPSERT       |
| `CompositeColumnField`           | admitted               | `slettRegelverksamling` (composite-PK)      |
| `ColumnReferenceField`           | stays deferred (R24-style) | no real FK-target schema today          |
| `CompositeColumnReferenceField`  | stays deferred (R24-style) | no real FK-target schema today          |

Three load-bearing changes follow:

- **`buildLookupBindings` propagates the carrier's extraction** rather than re-deriving one from
  raw column metadata. The R131 follow-up guard at `EnumMappingResolver:296-303` retires once
  the re-derivation is gone; the load-bearing key `lookup-key-input-field-non-nodeid-decoded`
  retires with it.
- **The lookup-binding return shape becomes a group**, mirroring R50's query-side
  `LookupMapping.ColumnMapping.LookupArg.{MapInput, DecodedRecord}`. `buildLookupBindings`
  returns `List<InputColumnBindingGroup>` where `InputColumnBindingGroup` is sealed
  `MapGroup(List<MapBinding>) | DecodedRecordGroup(String sourceFieldName,
  CallSiteExtraction.NodeIdDecodeKeys extraction, List<RecordBinding>)`. Composite-PK
  `@lookupKey` lands one `DecodedRecordGroup` carrying the per-NodeType decode helper once
  per group, with N positional `RecordBinding` slots into the decoded `Record<N>`.
  `TableInputArg.fieldBindings` retypes to `List<InputColumnBindingGroup>` in lockstep.
- **The mutation-arm partition slots widen.** `lookupKeyFields` / `setFields` on
  `TableInputArg` retype from `List<InputField.ColumnField>` to sealed `List<LookupKeyField>` /
  `List<SetField>` (both permits: `ColumnField`, `CompositeColumnField`). The two reference
  carriers do *not* enter the permits set — they stay outside R130's admitted carrier set, and
  the type system reflects that. R130's narrowed scope means narrower permits.

The two carriers ship together because they share Phase 1's infrastructure (binding-group
shape, extraction-propagation fix, partition-slot widening).

## Plan

### Phase 1: extraction-propagation fix + `InputColumnBindingGroup` shape

Two model edits land together; both are prerequisites for the carrier admission.

**Extraction propagation in `buildLookupBindings`.** Replace the `deriveExtraction(cf.typeName(),
cf.column(), …)` call at `EnumMappingResolver:327` with logic that honors `cf.extraction()` when
the carrier already holds a context-specific extraction:

- If `cf.extraction()` is `Direct`, fall through to `deriveExtraction` (enum / map / JooqConvert
  derivation from raw column metadata, today's behavior).
- If `cf.extraction()` is `NodeIdDecodeKeys` (or any other resolver-supplied non-`Direct` arm),
  use it directly. The carrier already named the right extraction; re-deriving discards it.

With the fix in place, the R131 follow-up guard at `EnumMappingResolver:296-303` deletes,
the load-bearing key `lookup-key-input-field-non-nodeid-decoded` retires (producer-only;
zero `@DependsOnClassifierCheck` consumers), and the class-level javadoc on
`buildLookupBindings` (`EnumMappingResolver:231-244`) regenerates against the post-R130
rejection set.

**`InputColumnBindingGroup` sealed shape.** Sibling sealed root to R50's
`LookupMapping.ColumnMapping.LookupArg` (`LookupMapping.java:91-163`), sharing the
binding-arity axis but rooted at an input-field cluster rather than an outer GraphQL
argument:

```java
sealed interface InputColumnBindingGroup {
    record MapGroup(List<InputColumnBinding.MapBinding> bindings) implements InputColumnBindingGroup {}
    record DecodedRecordGroup(
        String sourceFieldName,
        CallSiteExtraction.NodeIdDecodeKeys extraction,
        List<InputColumnBinding.RecordBinding> bindings
    ) implements InputColumnBindingGroup {}
}
```

The decode helper is reachable through `extraction.decodeMethod()` (R50's single source of
truth on `CallSiteExtraction.NodeIdDecodeKeys`). `buildLookupBindings` retypes its return
from `List<InputColumnBinding.MapBinding>` to `List<InputColumnBindingGroup>`: today's
single-key `@lookupKey` paths build a `MapGroup` carrying one `MapBinding`; the new
composite-PK path builds one `DecodedRecordGroup` with N `RecordBinding` slots indexed
`0..N-1` into the decoded `Record<N>`. `TableInputArg.fieldBindings` retypes in lockstep.

**Partition slot widening.** `TableInputArg.lookupKeyFields` and `setFields` retype from
`List<InputField.ColumnField>` to sealed `List<LookupKeyField>` / `List<SetField>` (both
permits: `ColumnField`, `CompositeColumnField` — R130's admitted carrier set). The two
reference carriers stay outside the permits set; their re-admission is R24-shaped follow-on
work. `TableInputArg.of` (`ArgumentRef.java:251`) updates its partition derivation to dispatch
on the sealed interface instead of `instanceof ColumnField`.

Phase 1 is independent of Phase 2's classifier admission; no schema reaches the new groups
until Phase 2 lands. Existing `buildLookupWhere` / `buildBulkLookupRowIn` consumers migrate
to switch on `InputColumnBindingGroup` (see Phase 3 for the emission shape).

### Phase 2: classifier gate lift — `MutationInputResolver`

Replace the four `not yet supported` rejections at `MutationInputResolver:292-296` with arm
handling that admits the two same-table carriers and re-states the two reference rejections in
R24-deferral terms:

- **`ColumnField` with `extraction = NodeIdDecodeKeys`** (arity-1 NodeId, own table): admitted.
  The Phase 1 group shape carries it through `EnumMappingResolver.buildLookupBindings` as a
  `MapGroup` with one `MapBinding` whose extraction is the carrier's `NodeIdDecodeKeys`.
- **`CompositeColumnField`** (arity &ge; 2 NodeId, own table): admitted. Produces one
  `DecodedRecordGroup` from `EnumMappingResolver.buildLookupBindings` (composite-PK case widens
  past today's scalar-column-fields gate). N `RecordBinding` slots indexed `0..N-1` feed the
  WHERE-clause `DSL.row(c1, …, cN).eq(record)` predicate.
- **`ColumnReferenceField` / `CompositeColumnReferenceField`**: stay rejected at classify time,
  with the rejection message reframed as an R24-style deferral pointing at the future schema:
  *"@reference / FK-target @nodeId in @mutation inputs is not yet supported; tracked in R24's
  scope when a forcing-function schema appears"*. The `Rejection.Deferred` shape carries the
  slug pointer.

Verb-carrier admission rules (narrowed scope, no reference-carrier branches):

- **Lookup-bearing verbs (DELETE, UPDATE, UPSERT ON-CONFLICT key list).** `@lookupKey` on
  `ColumnField(NodeIdDecodeKeys)` produces a `MapGroup`; `@lookupKey` on `CompositeColumnField`
  produces a `DecodedRecordGroup`. WHERE clause emits the single-column or row-tuple predicate.
  This is the headline forcing-function fix for `slettRegelverksamling`.
- **INSERT-arm carriers (INSERT verb, UPSERT INSERT-arm).** `ColumnField(NodeIdDecodeKeys)` is
  the canonical "create row, supply PK as a decoded NodeId" shape (rare but admitted; the same-
  table single-PK NodeId is a valid client-supplied PK on INSERT). `CompositeColumnField` ×
  INSERT is structurally valid (client supplies a composite PK) but architecturally rare and
  easy to misuse with auto-generated PKs. **R130 rejects `CompositeColumnField` × INSERT at
  classify time via `Rejection.Deferred`** keyed to the slug of a future Backlog follow-on
  (file separately when a forcing-function schema appears); the rejection surfaces through
  `GraphitronSchemaValidator` on the existing `MutationInputResolver`-partition validator arm,
  and the classifier test in Phase 4 asserts the rejection arm identity (not the message
  text), mirroring how R24-shaped deferrals surface elsewhere.
- **PK-coverage check (`MutationInputResolver:308`+).** For INSERT / UPSERT,
  `ColumnField(NodeIdDecodeKeys)` contributes its column to the bound-set so the check passes
  when the FK / PK column is matched.

Producer-consumer pairing via `@LoadBearingClassifierCheck` (producer) +
`@DependsOnClassifierCheck` (consumer):

- Key `mutation-input.lookup-binding-decoded-record-arity-matches-carrier-columns` — producer at
  the `DecodedRecordGroup` build site in `EnumMappingResolver.buildLookupBindings`, consumers at
  the lookup-WHERE emitter arms in `TypeFetcherGenerator` that read the `Record<N>` slots.
- Key `mutation-input.lookup-binding-honors-carrier-extraction` — producer at the
  extraction-propagation site in `buildLookupBindings`, consumer at the WHERE-clause
  per-binding emit that builds `DSL.val(in.get(name), col.getDataType())` calls assuming the
  binding's extraction matches the carrier's wire-format intent.

### Phase 3: emitter dispatch — DML verb emitters

Three walks consume the new shape: `buildLookupWhere` / `buildBulkLookupRowIn` switch on
`InputColumnBindingGroup` (Phase 1); INSERT/UPSERT column-list/values-list (`buildMutationInsertFetcher:1476`, `buildMutationUpsertFetcher:1797`)
and UPDATE SET walks (`buildMutationUpdateFetcher:1564`) switch on the carriers admitted in
Phase 2 (`ColumnField`, `CompositeColumnField`).

**Lookup WHERE / row-IN (`TypeFetcherGenerator:1959` / `:1931`).**

- `MapGroup` arm: today's per-`MapBinding` `.eq(DSL.val(in.get(name), col.getDataType()))`
  chain, with the wire-to-typed extraction running per the binding's `extraction` slot. The
  Phase 1 extraction propagation means a `NodeIdDecodeKeys`-bearing `MapBinding` builds a
  `decode<TypeName>(in.get(name))` value source instead of a raw `in.get(name)` read.
- `DecodedRecordGroup` arm: one decode invocation (`decode<TypeName>(in.get(sourceFieldName))`)
  produces the `Record<N>`. The predicate is `DSL.row(c1, …, cN).eq(record)` for the scalar
  case or `.in(stream.map(decode).toList())` for the row-IN case. Both shapes already exist as
  `BodyParam.RowEq` / `RowIn` on the query side; reuse the emission helpers where possible.

**INSERT / UPSERT / UPDATE walks dispatch on carrier identity.** Walks iterate `tia.fields()`
directly (the column-list walk includes every field; the SET walk excludes `@lookupKey`-bound
fields via the Phase 1 `lookupKeyFields` / `setFields` partition). Each walk pattern-matches
on the carrier:

- `ColumnField(Direct)`: one column write, value `DSL.val(in.get(name), col.getDataType())`.
- `ColumnField(NodeIdDecodeKeys)`: one column write, value `decode<TypeName>(in.get(name))`
  passed through `DSL.val(...)`. Decode invocation lifts to a local once per carrier.
- `CompositeColumnField`: one decode invocation lifts to a local; N
  `DSL.val(record.value(i), col.getDataType())` reads against the carrier's `columns`,
  indexed `0..N-1`.

INSERT/UPDATE-side switch sites wear `@DependsOnClassifierCheck` against
`mutation-input.lookup-binding-decoded-record-arity-matches-carrier-columns` (the per-carrier
column count must match the decoded record arity for the per-slot reads to line up).

### Phase 4: fixtures + tests

Phase 4 extends `nodeidfixture` with composite-PK and single-PK NodeId-decoded DML mutations.
The natural backing types are R50's existing composite-PK `Bar` Node type and a single-PK
`Foo`-shaped sibling. Sketched SDL surface (final detail in implementation):

```graphql
type Mutation {
  deleteBar(input: DeleteBarInput!): BarPayload @mutation(typeName: DELETE)
  updateBar(input: UpdateBarInput!): BarPayload @mutation(typeName: UPDATE)
  upsertBar(input: UpsertBarInput!): BarPayload @mutation(typeName: UPSERT)
  createBarRow(input: CreateBarSinglePkInput!): SinglePkPayload @mutation(typeName: INSERT)
}
input DeleteBarInput { id: ID! @nodeId(typeName: "Bar") @lookupKey }
input UpdateBarInput { id: ID! @nodeId(typeName: "Bar") @lookupKey, …setFields… }
input UpsertBarInput { id: ID! @nodeId(typeName: "Bar") @lookupKey, …setFields… }
```

INSERT-side composite-PK is explicitly *not* covered (per Phase 2's rejection of
`CompositeColumnField` × INSERT); only the single-PK INSERT shape (`ColumnField` with
`NodeIdDecodeKeys`) ships.

Test tiers (per `rewrite-design-principles.adoc`):

- **Classifier:** Phase 4 adds classification tests covering the admitted carrier × verb
  matrix: composite-PK DELETE/UPDATE/UPSERT, single-PK DELETE/UPDATE/UPSERT/INSERT. Plus one
  rejection test for the carved-out composite-PK × INSERT case. Lives in
  `MutationDmlNodeIdClassificationTest` (or a sibling). Adds one extraction-propagation test
  on the lookup-binding side asserting that `cf.extraction() = NodeIdDecodeKeys` flows into
  the `MapBinding`'s extraction instead of being re-derived to `JooqConvert`.
- **Pipeline:** assertions on classified model shape, not emitted body strings. Composite-PK
  cases assert `tia.fieldBindings()` carries one `DecodedRecordGroup` with arity matching the
  NodeType's `keyColumns` and `bindings[i].targetColumn()` positionally matches the carrier's
  `columns[i]`. Single-PK cases assert one `MapGroup` whose `MapBinding.extraction` is the
  resolver-supplied `NodeIdDecodeKeys`, not a re-derived `JooqConvert`. The compilation tier
  (`graphitron-sakila-example` + `nodeidfixture`) catches row-arity / column-type drift.
- **Execution:** Phase 4 adds PostgreSQL round-trips: composite-PK DELETE/UPDATE/UPSERT
  against `Bar` rows, and single-PK INSERT against a `Foo`-shaped row. Each test seeds the
  pre-state via direct jOOQ (or a prior mutation), builds the NodeId, runs the mutation,
  asserts post-state. The composite-PK DELETE test is the headline `slettRegelverksamling`-
  shaped forcing-function execution proof.
- **Rejection cleanup:** the R131 follow-up rejection tests
  `GraphitronSchemaBuilderTest.ArgumentParsingCase.LOOKUP_KEY_ON_NODEID_INPUT_FIELD_REJECTED`
  (single-PK, from `fe2de55`) and `LOOKUP_KEY_ON_NODEID_INPUT_FIELD_REJECTED_COMPOSITE_PK`
  (composite-PK, from the `beb0e92` cleanup) both retype to admission tests (the combinations
  are now valid; the tests assert the classifier-to-emission round-trip rather than the
  rejection). The R131 follow-up's load-bearing-key audit pair retires with them.

### Phase 5: validator + axis-coverage roll-up

`InferenceAxisCoverage` (`graphitron-rewrite/roadmap/inference-axis-coverage.adoc`) lists the
admitted carriers' `Roadmap` columns; add R130 to `ColumnField` and `CompositeColumnField`
entries. `ColumnReferenceField` and `CompositeColumnReferenceField` keep their existing
`Roadmap` entries (R24-coupled) and gain a note that the mutation-input deferral is a separate
R24-shaped item; defer filing it until a forcing-function schema appears.
`GraphitronSchemaValidator` arms cover the new admission paths per the existing
validator-arm-per-leaf discipline.

## Out of scope

- `ColumnReferenceField` and `CompositeColumnReferenceField` in `@mutation` inputs. Re-admission
  re-opens when a forcing-function schema appears; until then the `MutationInputResolver`
  rejection stays in place with a `Rejection.Deferred` slug pointer. The Phase 1
  binding-group infrastructure is general enough to absorb these carriers when they re-admit;
  no model rework expected.
- `NestingField` in `@mutation` inputs (the fifth `InputField` carrier). Nested input shapes
  require multi-table or grouped-column writes whose semantics live in R128 /
  `compound-entity-mutations` territory.
- `CompositeColumnField` × INSERT. Carved out explicitly at Phase 2 with a clear "client-
  supplied composite PK; route through individual @field columns if intentional" message.
  Lifting is a Backlog follow-on if a forcing-function schema appears.
- List-shaped admitted carriers (`list: true` on `ColumnField(NodeIdDecodeKeys)` or
  `CompositeColumnField`). The binding-group shape accommodates list extension via the WHERE-
  side row-IN emission, but the mutation-input list semantics (one mutation per row, batched
  decode) belong with R75 / batched-mutations work. R130 ships `list: false` only.
- `@condition` on admitted carriers. R130 ships the no-condition path; `@condition` augmentation
  of the row-eq predicate is a small follow-on if a forcing-function schema appears.
- Build-time INSERT column-coverage validation widening (whether decoded slots participate in
  NOT-NULL coverage). R22 already deferred this; R130 inherits the deferral until the jOOQ
  catalog reliably exposes per-column NULL / default metadata.

## Notes for the Spec reviewer

- **The extraction-propagation fix is the design crux.** R131's follow-up guard at
  `EnumMappingResolver:296-303` papers over a bug at `:327`: `buildLookupBindings` discards
  `cf.extraction()` and re-derives a generic extraction from raw column metadata. R130 fixes
  the bug at source so the guard retires. The architecturally clean shape is "the carrier
  already named the right extraction; the consumer reads it instead of re-deriving." Worth a
  focused reviewer pass on whether the `MapBinding.extraction` slot is read anywhere else
  that assumes the re-derived shape — the lookup-WHERE emitter is the known consumer.
- **`InputColumnBindingGroup` is a sibling sealed root to `LookupArg`, not a shared root.**
  `LookupArg` carries `argName: String` and `list: boolean` — rooted at the outer GraphQL
  argument, driving N-row broadcasting in `LookupValuesJoinEmitter`. The new group is rooted
  at an input-field cluster inside a `TableInputType`: source is one input field
  (`sourceFieldName`), list cardinality lives on the enclosing `TableInputArg.list()`.
  Folding both rootings into one root would either require an `Optional<argName>` slot (the
  recurring-predicate smell) or a `Source` sub-record pulling query-side N-row broadcasting
  into the mutation-input model. The sealed sibling carries the shared binding-arity
  invariant without entangling the rooting axis.
- **Phase 3 dispatches on carrier identity, not on a flattened intermediate.** The group
  identity is inherent to the carrier (`CompositeColumnField` is one decode group;
  `ColumnField(NodeIdDecodeKeys)` is a single-slot group; `ColumnField(Direct)` is a
  no-decode read). An earlier draft introduced a `ColumnWrite` sealed taxonomy that the
  walks would consume; the architect's review noted this re-encodes the binding-arity axis a
  third time. The carrier-direct switch is three arms in one place and no re-grouping
  predicate; the alternative would force walks to re-group by `(sourceFieldName, extraction)`
  to lift the decode call once per group, which is the invariant the carrier identity
  already encodes.
- **The two-carrier bundling is the right grain.** Phase 1's infrastructure (binding-group
  shape, extraction-propagation fix, partition-slot widening) is shared by both admitted
  carriers; splitting forces a partial migration of `fieldBindings` and partition slots that
  lives in the model for the lifetime of the gap. The two reference carriers stay outside
  the admitted set; their re-admission is R24-shaped follow-on, and Phase 1's shape is
  general enough to absorb them when they re-admit.
- **Retired key has zero consumers.** `lookup-key-input-field-non-nodeid-decoded` is
  declared only at the producer site (`EnumMappingResolver:258`); a grep across the codebase
  finds no `@DependsOnClassifierCheck` consumer, so retirement is producer-only and
  `LoadBearingGuaranteeAuditTest` surfaces no orphan.
- **Partition-slot permits widen in lockstep.** `LookupKeyField` / `SetField` permit
  `ColumnField` and `CompositeColumnField` only; reference carriers stay outside the permits
  set. When R24 (or successor) admits them, the permits widen.
- **Annotation pair throughout** is `@LoadBearingClassifierCheck` (producer) +
  `@DependsOnClassifierCheck` (consumer); `@LoadBearingGuarantee` is principles-doc prose,
  not a type.
- **Two R131 follow-up rejection tests retype to admission tests** (single-PK and
  composite-PK). The rename + assertion-shape change should be visible in the diff so the
  reviewer can verify the surface flip is intentional.
