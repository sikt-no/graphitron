---
id: R130
title: "Admit @nodeId-decoded carriers in @mutation inputs and @lookupKey bindings"
status: Spec
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

R131's follow-up chain (`fe2de55` then `beb0e92`) introduced a structural-rejection guard at
`EnumMappingResolver:296-303` keyed `lookup-key-input-field-non-nodeid-decoded`. The guard's
stated reason is that `deriveExtraction:179-192` would silently re-derive a `Direct` /
`JooqConvert` extraction from the column's `typeName` and `javaName`, *dropping the
resolver-supplied `NodeIdDecodeKeys` extraction the carrier already holds*
(`buildLookupBindings:327` calls `deriveExtraction(cf.typeName(), cf.column(), …)`, never
reading `cf.extraction()`). The net effect would be a binding that compares a base64-encoded
wire value against the raw PK column. The `beb0e92` cleanup moved the guard from a post-cast
`cf.extraction() instanceof NodeIdDecodeKeys` check to a pre-cast
`sdlField.hasAppliedDirective(DIR_NODE_ID)` check so both single-PK and composite-PK arities
surface the same diagnostic; the underlying re-derivation bug is unchanged.

The guard works around a deeper bug: `buildLookupBindings` discards `cf.extraction()` and
re-derives an extraction from raw column metadata. The architecturally clean fix is to make
`buildLookupBindings` honor `cf.extraction()` when the carrier already holds a context-specific
extraction (`NodeIdDecodeKeys`, `EnumValueOf`, …) rather than re-deriving a generic one. With that
fix, the new guard becomes inert and retires: a `@nodeId`-decoded input field's `NodeIdDecodeKeys`
propagates into the lookup binding, the decode helper runs once per row, the decoded value
matches the raw column.

R130 owns this fix because the bug and the carrier-admission lift are entangled: lifting the
carrier admission without fixing the extraction propagation re-creates the silent-miscompilation
shape R131's guard was rejecting.

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

The two-carrier bundling is the right grain because the binding-group shape, the extraction-
propagation fix, and the partition-slot widening are shared infrastructure both carriers need;
splitting forces a partial migration of `fieldBindings` and `lookupKeyFields` / `setFields` that
lives in the model for the lifetime of the gap.

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

With this fix, the R131 follow-up guard at `EnumMappingResolver:296-303` becomes inert and
deletes. The load-bearing key `lookup-key-input-field-non-nodeid-decoded` retires; it has zero
`@DependsOnClassifierCheck` consumers in the codebase today (verified by grep — the producer
declaration at `EnumMappingResolver:258` is the only site), so retirement is producer-only and
`LoadBearingGuaranteeAuditTest` surfaces no orphan. The class-level javadoc on
`buildLookupBindings` (`EnumMappingResolver:231-244`) regenerates against the post-R130
rejection set. The `LOOKUP_KEY_ON_NODEID_INPUT_FIELD_REJECTED` test case retypes to assert the
post-R130 admission (the encoded id round-trips through the decode helper and matches the raw
PK column correctly).

**`InputColumnBindingGroup` sealed shape.** Mirror R50's query-side
`LookupMapping.ColumnMapping.LookupArg` (`LookupMapping.java:91-163`) on the binding-arity axis,
with one rooting delta:

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

**Why a sibling sealed root rather than reusing `LookupArg`.** `LookupArg` carries
`argName: String` and `list: boolean` at the root — it is rooted at the outer GraphQL argument
and drives N-row broadcasting in `LookupValuesJoinEmitter`. `InputColumnBindingGroup` is rooted
at an input-field cluster *inside* a `TableInputType`: the cluster's wire-format source is one
input field (`sourceFieldName`), and list cardinality lives one level up on the enclosing
`TableInputArg.list()`. Folding both rootings into one shared root would require either an
`Optional<argName>` slot (with consumers branching on presence — the recurring-predicate smell)
or an additional `Source` sub-record (`OuterArg(argName, list) | InputField(sourceFieldName)`),
which is itself a sub-taxonomy and pulls the unrelated query-side N-row broadcasting concern
into the mutation-input model. The two rootings live in different model layers; the sealed
sibling shape carries the shared binding-arity invariant without entangling the rooting axis.

The decode helper is reachable through `extraction.decodeMethod()` (R50's single source of
truth on `CallSiteExtraction.NodeIdDecodeKeys`). The group carries the per-cluster invariant
("all N `RecordBinding` slots share one decode source") in the type system rather than as a
predicate consumers reconstruct.

`buildLookupBindings` retypes its return from `List<InputColumnBinding.MapBinding>` to
`List<InputColumnBindingGroup>`. The today-existing single-key path (`@lookupKey` on a
`ColumnField`) builds a `MapGroup` carrying one `MapBinding` (extraction propagated per the
fix above). The new composite path (`@lookupKey` on a `CompositeColumnField` carrying
`NodeIdDecodeKeys`) builds one `DecodedRecordGroup` with N `RecordBinding` slots indexed
`0..N-1` into the decoded `Record<N>`.

`TableInputArg.fieldBindings` retypes from `List<InputColumnBinding.MapBinding>` to
`List<InputColumnBindingGroup>` in lockstep. Today's call sites that build a flat list become
one `MapGroup` wrapper; composite-PK `@lookupKey` fields produce one `DecodedRecordGroup`.

**Partition slot widening.** `TableInputArg.lookupKeyFields` and `setFields` retype from
`List<InputField.ColumnField>` to sealed `List<LookupKeyField>` / `List<SetField>` (both
permits: `ColumnField`, `CompositeColumnField` — R130's admitted carrier set). The two
reference carriers stay outside the permits set; their re-admission is R24-shaped follow-on
work. `TableInputArg.of` (`ArgumentRef.java:251`) updates its partition derivation to dispatch
on the sealed interface instead of `instanceof ColumnField`.

Existing consumers (`buildLookupWhere` / `buildBulkLookupRowIn` at `TypeFetcherGenerator:1959` /
`:1931`) switch on `InputColumnBindingGroup`. The `MapGroup` arm keeps today's per-slot `.eq` /
per-slot tuple emission. The `DecodedRecordGroup` arm emits one decode invocation
(`decode<TypeName>(in.get($S))`) and then a `DSL.row(c1, …, cN).eq(record)` predicate (or, for
the row-in case, the analogous `.in(stream.map(decode).toList())`). The shape mirrors
`BodyParam.RowEq` / `RowIn` from R50's query-side machinery; the difference is the binding
source, not the predicate emission.

This phase is the prerequisite for the carrier admission; ship independent of the gate lifts.
No schema admits the new groups until Phase 2 lands; the post-R131 follow-up guard collapses
as soon as the extraction-propagation fix is in place.

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

The R131 follow-up key `lookup-key-input-field-non-nodeid-decoded` retires in Phase 1; this
phase does not re-pair anything against it.

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

**INSERT / UPSERT / UPDATE walks dispatch on carrier identity.** The INSERT column-list /
values-list walks and the UPDATE SET-clause walk all iterate `tia.fields()` directly (the
column-list walk includes every field; the SET walk excludes `@lookupKey`-bound fields via the
Phase 1 `lookupKeyFields` / `setFields` partition). Each walk pattern-matches on the carrier
identity rather than going through a flattened `ColumnWrite` intermediate:

- `ColumnField(Direct)`: one column write, value `DSL.val(in.get(name), col.getDataType())`.
- `ColumnField(NodeIdDecodeKeys)`: one column write, value `decode<TypeName>(in.get(name))` and
  then `.value(0)` of the resulting `Record<1>` passed through `DSL.val(...)`. The decode
  invocation lifts to a local once per carrier; the column-list/values-list walks read from the
  local.
- `CompositeColumnField`: N column writes against the carrier's `columns` (positional). One
  decode invocation per carrier (`decode<TypeName>(in.get(name))`) lifts to a local; N
  `DSL.val(record.value(i), col.getDataType())` reads, one per slot, indexed `0..N-1`.

The group identity is *inherent to the carrier*: one `CompositeColumnField` is one decode
group, one `ColumnField(NodeIdDecodeKeys)` is a single-slot decode group, one
`ColumnField(Direct)` is a no-decode read. Flattening to a flat write-list would force the
walks to re-group by `(sourceFieldName, extraction)` to lift the decode call once per
group — exactly the invariant the carrier identity already encodes. The carrier-direct switch
is three arms; abstracting the switch behind a flat `ColumnWrite` intermediate would re-encode
the same axis a third time.

The two reference carriers are not in the admitted set, so no joined-table arms are needed.
When R24 (or a successor item) admits them, the switch grows two arms for the joined cases.

Producer-consumer pairing per the two `@LoadBearingClassifierCheck` keys named in Phase 2; the
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
- **Pipeline:** Phase 4 asserts on the *classified model shape* rather than on emitted body
  strings (the "no code-string assertions on generated bodies" rule). Composite-PK lookup-key
  cases assert `tia.fieldBindings()` carries one `DecodedRecordGroup` with arity matching the
  NodeType's `keyColumns`, the carrier's `extraction` arm is `NodeIdDecodeKeys.ThrowOnMismatch`
  (or `SkipMismatchedElement` per resolver semantics), and `bindings[i].targetColumn()`
  positionally matches the carrier's `columns[i]`. Single-PK cases assert one `MapGroup` with
  one `MapBinding` whose extraction is the resolver-supplied `NodeIdDecodeKeys`, not a
  re-derived `JooqConvert`. The compilation tier (`graphitron-sakila-example` + the
  `nodeidfixture` example module) catches row-arity / column-type drift between emitter and
  jOOQ DSL without anyone asserting on a code string.
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

- **The R131 follow-up entanglement is the design crux.** R131's follow-up
  (`R131 follow-up: reject @lookupKey on @nodeId-decoded input field`, `fe2de55`) added a
  composition guard at `EnumMappingResolver:296-303` because `deriveExtraction` re-derives
  extractions from raw column metadata, dropping the carrier's resolver-supplied extraction.
  R130 fixes that bug at the source (`buildLookupBindings` honors `cf.extraction()`) and
  retires the guard plus its `lookup-key-input-field-non-nodeid-decoded` load-bearing key.
  Worth a focused reviewer pass on whether the extraction-propagation change has other
  consumers that would surprise: the `MapBinding` extraction slot is read by the lookup-WHERE
  emitter; the question is whether anywhere else reads it and assumes the re-derived shape.
- **Phase 1 model shape** is a sibling sealed root to R50's query-side
  `LookupMapping.ColumnMapping.LookupArg` (`LookupMapping.java:91-163`), sharing the
  binding-arity axis (`MapGroup` ~ `MapInput`, `DecodedRecordGroup` ~ `DecodedRecord`) but
  rooted at an input-field cluster (`sourceFieldName`) rather than the outer GraphQL argument
  (`argName + list`). The two rootings live in different model layers; folding them into one
  root would either require an Optional discriminator slot or pull query-side N-row
  broadcasting into the mutation-input model. Decode-helper identity stays on
  `CallSiteExtraction.NodeIdDecodeKeys.decodeMethod` (R50's single source of truth).
- **Partition-slot widening** narrows in lockstep with admitted carriers. `LookupKeyField` /
  `SetField` permits `ColumnField` and `CompositeColumnField` only; the two reference
  carriers stay outside the permits set. When R24 (or successor) admits them, the permits set
  widens.
- **Annotation pair** is `@LoadBearingClassifierCheck` (producer) + `@DependsOnClassifierCheck`
  (consumer); `@LoadBearingGuarantee` is principles-doc prose, not a type.
- **The R131 follow-up rejection test retypes to an admission test.** The combination R131's
  follow-up rejected is now valid; the rename + assertion-shape change should be visible in
  the diff so the reviewer can verify the surface flip is intentional.
