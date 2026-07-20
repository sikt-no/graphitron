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

## Interactions to resolve at Spec pickup

- **R24** (Backlog): the `CompositeColumnReferenceField` output emission is today a stubbed variant
  deferred to R24. The dissolution re-homes that deferral (the stub key anchors by
  `Rejection.StubKey.VariantClass`, which names the retiring class); it does not implement it.
- **R27** (Backlog): the nodeId synthesis shims are construction sites for the input composites;
  retiring the shims and dissolving the leaves touch the same code.
- **R462** (Spec): its planned per-field dependency edges enumerate the composite leaves by name.
- **R222** (Spec, umbrella) and **R302** (rename): stage-tracking and naming interactions.
- **Scope fork**: one item or per-axis slices (output / input / argument); and whether the
  `FieldClassification.CompositeColumn*` projection arms collapse into column-list-carrying
  `Column*` projections or stay distinct views. R333 leaves "`N` QueryParts vs one `N`-ary
  operation" open; the Spec pins it.
