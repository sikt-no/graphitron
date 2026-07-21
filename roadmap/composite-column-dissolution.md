---
id: R508
title: "Dissolve the composite-column leaf family: arity becomes QueryPart multiplicity"
status: Backlog
bucket: structural
priority: 3
theme: classification-model
depends-on: []
created: 2026-07-20
last-updated: 2026-07-20
---

# Dissolve the composite-column leaf family: arity becomes QueryPart multiplicity

Composite columns are the first entry in R333's "What dissolves" list: one coordinate lowers to `N`
column QueryParts, and `CompositeColumnField` / `CompositeColumnReferenceField` retire along with
arity-as-a-leaf-property. Today arity is encoded in the type system twice over: the composite leaves
carry an arity-`N` `columns` repeating group (R333 names this a 1NF violation), and the arity-1 case
routes to a *different leaf type* entirely (`ColumnField` / `ColumnReferenceField`), so "how many
columns" is a classification verdict rather than a count. Every consumer pays for the split with
duplicated switch arms; adding the composite dimension multiplied leaves instead of adding a fact.
This item dissolves the family the way R432 (batched leaf merge) and R314 (reentry dissolution)
dissolved theirs: same acceptance discipline, next family in the chain.

## What the 2026-07-20 research found

**The family spans three parallel axes, not one.** The R333 text names only the `ChildField` pair,
but the same arity-two-or-more split (with identical compact-constructor guards) exists on all three
classification axes, and a dissolution that stops at the output pair leaves the disease in the other
two organs:

| Axis | Composite pair | Arity-1 sibling |
|---|---|---|
| output | `ChildField.CompositeColumnField` / `CompositeColumnReferenceField` | `ChildField.ColumnField` / `ColumnReferenceField` |
| input | `InputField.CompositeColumnField` / `CompositeColumnReferenceField` | `InputField.ColumnField` / `ColumnReferenceField` |
| argument | `ArgumentRef.ScalarArg.CompositeColumnArg` / `CompositeColumnReferenceArg` | `ScalarArg.ColumnArg` / `ColumnReferenceArg` |

**One trigger, and it is nodeId-shaped.** Every composite classification comes from `@nodeId`
targeting a node type whose `nodeKeyColumns()` has more than one column
(`FieldBuilder.buildNodeIdOutputCarrier`,
`buildNodeIdReferenceCarrier`; `BuildContext.inputFieldFromNodeIdResolved` plus the R27 synthesis
shims). There is no plain composite projection: the composite carriers narrow their compaction /
extraction slot to `NodeIdEncodeKeys` / `NodeIdDecodeKeys` *at the type level* (documented on
`CallSiteCompaction`), where the arity-1 siblings carry the general slot. So the composite leaves are
not "multi-column fields"; they are the N-column *node key* materialized as a leaf dimension.

**R333 dissolves the family on both sides, and both are in scope:**

- *Projection side*: the `N`-column repeating group becomes `N` (or one `N`-ary) `select`
  operations; arity is the count of projected QueryParts, not a coordinate dimension.
- *Read side*: a composite key is not a composite transform but an `N`-read locator feeding one node
  codec; the codec is entailed by the node facts (`NodeType` / `NodeKeyColumn` /
  `NodeKeyProjection`), nothing the field carries. See R333's *Node facts* and the accessor/locator
  section.

## Consumer census (anchors measured 2026-07-20; symbols are the stable citation)

- **Generators**: `TypeClassGenerator` threads a per-type `compositeColumnFields` list and loops
  `columns()` in the `$fields` SELECT arm; `FetcherEmitter` has an encode arm looping `columns()`
  for `CompositeColumnField` and a throwing stub for `CompositeColumnReferenceField` (the R24
  deferral); `TypeFetcherGenerator` places the pair in `PROJECTED_LEAVES` vs `STUBBED_VARIANTS`,
  lists both input composites in `NOT_DISPATCHED_LEAVES`, and its INSERT/SET emitters loop
  `columns()` / `liftedSourceColumns()` reading positional `.value<i>()` slots.
- **Walkers**: `UpdateRowsWalker.classifyInto` and `DeleteRowsWalker` route both input composites
  through `classifyColumnCarrier`.
- **Projections**: `CatalogBuilder.projectFieldClassification` maps both axes onto
  `FieldClassification.CompositeColumn` / `CompositeColumnReference`. Per R333's projection-seam
  invariant, these must re-source when the leaves go, or the leaves survive as a shim feeding the
  LSP / model-context surface.
- **Validation**: `GraphitronSchemaValidator.validateCompositeColumnField` /
  `validateCompositeColumnReferenceField` (arity cap 22, the jOOQ `RecordN` ceiling, a constraint
  that must survive the dissolution in some home) and the input-side FK-hop check.
- **Model helpers**: `InputColumnBindingGroup` (composite produces a `DecodedRecordGroup` of `N`
  positional `RecordBinding`s), `EnumMappingResolver`, `MutationInputResolver`
  (`CompositeColumnField` rejected on INSERT, admitted elsewhere; see R419),
  `ContextArgumentClassifier` (no-op arms), `CompileDependencyGraphBuilder` (NodeIdEncoder edges;
  see R462).
- **Tests**: `NodeIdPipelineTest` is the primary functional coverage (2-column-key fixture);
  `VariantCoverageTest` / `ProjectionCoverageTest` / `SourceShapeProjectionTest` enumerate the
  leaves; walker and DML nodeId classification tests reference them.
- **Docs**: `code-generation-triggers.adoc` trigger rows, generated `supported-schema-shapes.adoc`
  entries, `argument-resolution.adoc` scalar arms.

## Discipline carried from R431/R432/R314

Third dissolution family, same gates: execution-tier equivalence (byte-identical sakila output where
the slice is a pure re-plumbing), `@classified` corpus classification unchanged, closure oracles
green, and R432's fresh-names lesson (any successor carrier gets a new name so every switch arm and
set membership is compiler-forced through the change, not silently widened).

## The merge (pinned design)

Per axis, the composite pair dissolves into its arity-1 sibling under a **fresh name** (the R432
lesson: reuse would let existing `instanceof` / switch-arm / set-membership sites silently start
receiving multi-column instances; a rename compiler-forces every consumer through the change). The
single `ColumnRef column` slot widens to `List<ColumnRef> columns`, arity 1..N:

| Axis | Retiring pair | Arity-1 sibling | Merged leaf (working name) |
|---|---|---|---|
| output | `ChildField.CompositeColumnField` / `CompositeColumnReferenceField` | `ColumnField` / `ColumnReferenceField` | `ChildField.ColumnTupleField` / `ColumnTupleReferenceField` |
| input | `InputField.CompositeColumnField` / `CompositeColumnReferenceField` | `ColumnField` / `ColumnReferenceField` | `InputField.ColumnTupleField` / `ColumnTupleReferenceField` |
| argument | `ScalarArg.CompositeColumnArg` / `CompositeColumnReferenceArg` | `ColumnArg` / `ColumnReferenceArg` | `ScalarArg.ColumnTupleArg` / `ColumnTupleReferenceArg` |

"Tuple" is the working vocabulary because a 1-tuple is the arity-1 case and the validator's 22-slot
ceiling is exactly jOOQ's `RecordN` / `RowN` tuple-width cap; the name is reviewable at the
Spec → Ready gate.

**Compact-constructor invariants** (the R432 checked-not-structural discipline, each documented at
the constructor):

1. `columns` is non-empty (and defensively copied).
2. `columns.size() > 1` implies the compaction is `NodeIdEncodeKeys` (output) / the extraction is
   `NodeIdDecodeKeys` (input, argument). This is today's type-level narrowing restated as a checked
   invariant: no plain composite projection exists; a multi-column carrier is always a node-key
   codec call. Corollary: `Direct` implies arity 1, so every `Direct` read stays single-column.
3. Output reference arm: `ParentCorrelation.checkCarrierInvariant` runs unconditionally (see the
   asymmetry table).

The `String columnName` component on the arity-1 leaves is redundant with `column.javaName()` and
drops; consumers derive it from `columns.get(0)` where they need the single-column name.
`domainReturnType()` unifies to one formula: `NodeIdEncodeKeys`/`NodeIdDecodeKeys` implies
`STRING_CLASS`, else `columns.get(0).columnType()` (well-defined by invariant 2's corollary).

## Asymmetries between merge partners, and their resolutions

Each divergence between a composite leaf and its arity-1 sibling is a place the merge could
silently widen behavior. Pinned one by one:

- **`ResultKeyAliasedField` membership** (output reference): `ColumnReferenceField` is a member,
  `CompositeColumnReferenceField` is not. The merged leaf **is** a member, and the membership
  caveat in `ResultKeyAliasedField`'s javadoc ("a member on every emittable instance") carries
  over unchanged: `NodeIdEncodeKeys` compaction is a validate-time deferral on the merged leaf at
  every arity (next bullet), so only `Direct` instances reach emission, and by invariant 2's
  corollary those are arity-1, exactly today's aliased-subquery population. No composite instance
  can reach the write-arm / read-binding agreement the marker enforces. Caution: R499/R500 (In
  Review / Ready) work this exact seam; slice 1 rebases over whatever they land.
- **The R24 deferral has two encodings today, and the merge unifies them.** Arity-1:
  `ColumnReferenceField` sits in `PROJECTED_LEAVES` and `validateColumnReferenceField` emits
  `Rejection.deferred(..., ColumnReferenceField.class)` on `NodeIdEncodeKeys` ahead of generation.
  Composite: `CompositeColumnReferenceField` sits in `STUBBED_VARIANTS` with a `deferredFor(...)`
  map entry and a `stub(f)` switch arm in `TypeFetcherGenerator`. Post-merge there is **one**
  compaction-gated validate-time deferral on the merged leaf (any arity); the stub arm and map
  entry delete, following the R314 precedent of upgrading stub surfaces to classification/validate
  rejections. The `Rejection.StubKey.VariantClass` anchor re-keys to the merged class; R24
  re-anchors in slice 4. Pipeline-tier coverage asserts the deferral still fires for both arities.
- **`ParentCorrelation`** (output reference): the composite constructor skips it today only
  because the arity-1 arm derives it via `ctx.buildParentCorrelation(joinPath, parentTable)`, an
  arity-independent derivation (`FieldBuilder.buildNodeIdReferenceCarrier`). The merged
  construction site runs the same derivation for every arity; `checkCarrierInvariant` applies
  unconditionally.
- **INSERT admission** (input): `admitMutationInputFields` admits `ColumnField` always and rejects
  `CompositeColumnField` on INSERT with a deferred rejection (the composite-PK INSERT carve-out).
  The arm becomes arity-gated on the merged leaf: `columns.size() > 1 && kind == INSERT` defers,
  behavior-preserving, same rejection text.
- **Validator**: `validateCompositeColumnField` / `validateCompositeColumnReferenceField` fold
  into the sibling validators; the 22-slot `RecordN` cap becomes a conditional check on
  `columns.size()`, and the reference-path validation is shared already
  (`validateReferencePath`).
- **Input and argument axes carry no marker asymmetry**: all four `InputField` variants implement
  the same `InputField, LookupKeyField, SetField` set, so the merge there is purely mechanical
  (walkers, `EnumMappingResolver`, `InputColumnBindingGroup`'s `MapGroup` vs `DecodedRecordGroup`
  fork becomes extraction-gated, `TypeFetcherGenerator` INSERT/SET loops already iterate lists).

## Projections stay wire-stable

`FieldClassification.CompositeColumn` / `CompositeColumnReference` are **kept** as projection
variants, derived by an arity predicate inside `CatalogBuilder.projectFieldClassification`'s
compile-checked switch (the classification wire surface the LSP and model-context server consume
does not churn). This satisfies R333's projection-seam invariant, re-sourcing the projection from
the merged leaves with compile-time coverage; the projection layer is explicitly allowed to be a
denormalized view. Collapsing the projection variants themselves is out of scope.

## Slices

Each slice lands independently, trunk-green, with the R431/R432/R314 acceptance gates: generated
sakila output byte-identical (`diff -r` empty) where the slice is pure re-plumbing, `@classified`
corpus renames-only (no verdict delta), `GeneratorCoverageTest` partition exhaustive-and-disjoint.

1. **Output axis.** Merge the `ChildField` pair; re-plumb `TypeClassGenerator` (the
   `compositeColumnFields` threading becomes an arity-gated walk of the merged leaf),
   `FetcherEmitter` (encode arm loops `columns()` uniformly; 1-element loop replaces the scalar
   arm), `TypeFetcherGenerator` partitions, validator fold, `CompileDependencyGraphBuilder`
   NodeIdEncoder edges, `CatalogBuilder` projection re-source.
2. **Input axis.** Merge the `InputField` pair; re-plumb `UpdateRowsWalker` / `DeleteRowsWalker`
   `classifyInto`, `MutationInputResolver` (admission + writer collection),
   `EnumMappingResolver`, `InputColumnBindingGroup`, `ContextArgumentClassifier`, the
   `BuildContext` construction sites (including the R27 synthesis shims, which only re-target
   constructors here).
3. **Argument axis.** Merge the `ScalarArg` pair and its `ArgumentRef` consumers.
4. **Docs and ride-alongs.** Regenerate `supported-schema-shapes.adoc` and
   `inference-axis-coverage.adoc`; update `code-generation-triggers.adoc` trigger rows and
   `argument-resolution.adoc`; re-anchor R24 (deferral key and title symbols), R27, R419, R462
   mentions of the retired names; R333 shipped-note.

Slices 1..3 may compress into fewer commits if the plumbing turns out mechanical, but each axis
must hold the byte-identical gate on its own before the next starts.

## Coverage

- Unit tier: constructor-invariant tests for the merged leaves (arity floor, invariant 2, the
  `Direct` corollary), mirroring `BatchedTableField`'s six-invariant precedent.
- Meta-tests: `VariantCoverageTest` / `ProjectionCoverageTest` / `SourceShapeProjectionTest`
  leaf-set updates (the composite `NO_CASE_REQUIRED` / `NO_PROJECTION_REQUIRED` entries retire
  with the leaves); `GeneratorCoverageTest` partition update.
- Pipeline tier: `NodeIdPipelineTest` cases re-anchor to the merged names (renames-only for the
  live shapes); a deferral assertion pins that the composite non-mirror reference still rejects
  at build time post-unification (both arities, same `StubKey` anchor).
- Execution tier: the nodeid fixture's 2-column-key execution proofs stay green unchanged.
- No code-string assertions on generated method bodies.

## Out of scope

- Implementing the R24 JOIN-with-projection emission (the deferral moves house; it does not close).
- Collapsing the `FieldClassification.CompositeColumn*` projection variants.
- The full R333 normalization of the columns list into per-column `select` operation rows; this
  item retires arity-as-a-leaf-**type**. The list survives as an ordinary multi-valued fact on one
  leaf per axis, which is the same landing R432 chose for `SourceShape` (stored fact over leaf
  split) and what R314's emit re-platforming consumes.
- Retiring the R27 synthesis shims (their construction sites re-target mechanically here).

## Interactions

- **R24** (Backlog): deferral anchor and title symbols re-anchor in slice 4; the item's scope is
  unchanged (implement the emission for the merged reference leaf, both arities, one rule).
- **R27** (Backlog): shim construction sites re-target in slice 2; shim retirement stays R27's.
- **R419** (Backlog): the INSERT carve-out it names becomes arity-gated; premise intact.
- **R462** (Spec): its leaf enumerations pick up the merged names; one fewer edge-kind to model.
- **R499/R500** (In Review / Ready): active on the `ResultKeyAliasedField` / `$fields` aliasing
  seam slice 1 touches; slice 1 starts after they land or rebases over them.
- **R222** (Spec, umbrella): this is the next executed proof of the dimensional pivot; the leaf
  count drops by six.
- **R302** (Backlog, `ChildField` rename): orthogonal; whichever lands second re-anchors names.
