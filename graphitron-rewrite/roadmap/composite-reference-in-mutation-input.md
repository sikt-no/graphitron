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
walk `tia.fieldBindings()` against `List<InputColumnBinding.MapBinding>`. Both walks are too narrow
for any reference / composite carrier.

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
arms; the binding-shape model needs one widening shared across all four.

## Plan

### Phase 1: model widening — composite-key bindings on `TableInputArg`

`ArgumentRef.InputTypeArg.TableInputArg.fieldBindings` is currently
`List<InputColumnBinding.MapBinding>` (narrowed in `ArgumentRef.java:232`). Widen to
`List<InputColumnBinding>` so the existing `RecordBinding` arm (already defined for the query side)
can participate on the mutation side. R50's `RecordBinding(int index, ColumnRef targetColumn)` is
the right shape: per-slot index into the decoded `Record<N>` produced by the per-NodeType
`decode<TypeName>` helper.

- Migrate the two existing consumers (`buildLookupWhere`, `buildBulkLookupRowIn`,
  `TypeFetcherGenerator.java:1974` / `:1946`) to pattern-match on `InputColumnBinding` instead of
  hard-casting to `MapBinding`. Add a private `RecordBinding` arm to each; emit the index-into-record
  read path.
- Producer (`MutationInputResolver.buildTableInputArg`, and the field-binding construction sites
  around `MutationInputResolver.java:230-280`) needs an arm that builds `RecordBinding` slots from
  `CompositeColumnReferenceField.columns` paired with the decoded-record slot indices.
- The narrowing on `List<MapBinding>` in `ArgumentRef.java:232` was load-bearing for a now-stale
  invariant; the widening replaces it with a sealed dispatch.

This phase is the prerequisite for the composite-key WHERE-clause emission. Ship independent of
the classifier gate lift; no schema change yet.

### Phase 2: classifier gate lift — `MutationInputResolver`

Replace the four `not yet supported` rejections at `MutationInputResolver.java:308-315` with arm
handling:

- `ColumnField` with `extraction = NodeIdDecodeKeys` (the "NodeId-decoded ColumnField" case):
  produces a `MapBinding` carrying the field name + a `NodeIdDecodeKeys` extraction; the existing
  `MapBinding.extraction` slot already admits this without a binding-shape change. The walk only
  needs to admit the carrier; no model widening for this arm.
- `ColumnReferenceField`: produces `MapBinding` (arity-1, no record decode) plus a join-path
  resolution that the WHERE-side emitter consumes. The `liftedSourceColumns` (singleton list)
  carries the source-side column for the INSERT-arm.
- `CompositeColumnField`: produces N `RecordBinding` slots (one per `columns` entry), all
  indexed into the same decoded `Record<N>`. The decode helper reference rides on a new
  `tia.decodeHelpers: List<HelperRef.Decode>` slot.
- `CompositeColumnReferenceField`: same as `CompositeColumnField` plus `joinPath` /
  `liftedSourceColumns`.

Verb-specific guards:

- **INSERT-arm carriers (INSERT / UPSERT INSERT-arm).** A reference carrier writes its
  `liftedSourceColumns` (the FK columns on the input's own table). PK-coverage check at
  `MutationInputResolver.java:326-340` already gates "are all PK columns covered"; reference
  carriers contribute their `liftedSourceColumns` to the bound-set so the check passes when an
  FK column is the PK or part of it.
- **Lookup-arm carriers (DELETE, UPDATE WHERE, UPSERT ON-CONFLICT, UPDATE/DELETE WHERE).**
  `@lookupKey` on a `CompositeColumnReferenceField` is the canonical shape; the binding-set
  contains the N target-table columns and the WHERE clause emits the row-tuple predicate.

### Phase 3: emitter dispatch — DML verb emitters

Per the user's call (handle all DML verbs together), wire all four emitters in this phase. Share
infrastructure aggressively; the four verbs differ only in whether the binding-list / fields-list
feeds the INSERT column-list, the SET clause, the WHERE clause, or the ON-CONFLICT key list.

- **`buildLookupWhere` (`TypeFetcherGenerator.java:1974`).** Switch on `InputColumnBinding`. The
  `MapBinding` arm keeps today's shape. The `RecordBinding` arm folds into a single
  `DSL.row(c1, ..., cN).eq(decode<TypeName>(in.get($S)))` predicate per decode group (one decode
  helper invocation per `RecordBinding` cluster sharing the same source field name).
- **`buildBulkLookupRowIn` (`TypeFetcherGenerator.java:1946`).** Same widening; the `RecordBinding`
  arm emits `.in(in.stream().map(row -> decode<TypeName>(row.get($S))).toList())`.
- **INSERT / UPSERT column-list walk (`buildMutationInsertFetcher` at `:1491`,
  `buildMutationUpsertFetcher` at `:1812`).** Replace the today's hard-cast to `ColumnField` in the
  `tia.fields()` walk with a sealed switch over `InputField` that emits, for each non-`NestingField`
  carrier:
  - `ColumnField(Direct)`: today's `DSL.val(in.get(name), col.getDataType())` path.
  - `ColumnField(NodeIdDecodeKeys)`: decode-then-val per slot.
  - `ColumnReferenceField(_)`: write to `liftedSourceColumns.get(0)`; extraction dispatches as for
    `ColumnField`.
  - `CompositeColumnField` / `CompositeColumnReferenceField`: one decode invocation, N
    `record.value(i)` reads, N `DSL.val(...)` slots written to the N target / source columns.
- **UPDATE SET-clause walk (`buildMutationUpdateFetcher` at `:1579`).** Same shape, skipping
  `@lookupKey`-bearing fields by name. Reference carriers contribute their `liftedSourceColumns`
  to the SET clause when not bound to `@lookupKey`.

`@LoadBearingClassifierCheck` annotations on the new arms tie classifier producer to emitter
consumer per the project pattern.

### Phase 4: fixtures + tests

Extend `nodeidfixture` with a composite-PK DELETE-by-id mutation (the user's chosen forcing
function shape). The existing composite-PK `Bar` Node type from R50 is the natural backing table;
add a `Mutation.deleteBar(input: DeleteBarInput!): BarPayload` with
`input DeleteBarInput { id: ID! @nodeId(typeName: "Bar") @lookupKey }`. Mirror for INSERT,
UPDATE, UPSERT to cover all four verbs.

Test tiers (per `rewrite-design-principles.adoc`):

- **Classifier:** `MutationDmlNodeIdClassificationTest` extension verifying each carrier
  classifies (not rejected), one new test per carrier arm.
- **Pipeline:** `FetcherPipelineTest` golden output for the emitted lookup-WHERE and the emitted
  INSERT values list — pin `DSL.row(...).eq(decode<Bar>(in.get("id")))` and the per-slot
  `record.value(i)` reads.
- **Execution:** PostgreSQL round-trip in `…executiontier…`; insert a Bar with known composite
  PK via direct jOOQ, build a NodeId, run `deleteBar(input: {id: <encodedId>})`, assert the row
  is gone. One execution test per verb (4 total).

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

- The Phase 1 widening on `tia.fieldBindings` is the largest blast radius (every existing
  consumer migrates). The query side already has the precedent at `LookupMapping.ColumnMapping.
  LookupArg.DecodedRecord` (R50, `lift-nodeid-out-of-model.md`) — this item brings the
  mutation-side binding shape into alignment with the query side rather than inventing a new
  abstraction. Worth confirming the reviewer agrees that's the right precedent (the alternative
  is a mutation-local `DmlBindingGroup` that aggregates the N slots, which would not align with
  R50's per-slot binding shape).
- Independence from R24 is asserted, not load-bearing — both items consume the same
  `CompositeColumnReferenceField` carrier but emit different code paths (R24: output-side JOIN
  with projection; R130: input-side decode + row-tuple predicate or column-list write). If R24
  re-specs first, this item's Phase 1 widening still stands.
- The `@LoadBearingClassifierCheck` key for "mutation-input binding cardinality matches input
  field arity" is new; surface in the Spec body if the reviewer wants it pre-named.
- INSERT-arm of UPSERT and INSERT proper share the column-list/values-list code path — the
  Phase 3 helper extraction can land once and serve both; pin via shared key.
