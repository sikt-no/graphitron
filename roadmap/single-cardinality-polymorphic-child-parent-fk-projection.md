---
id: R481
title: "Single-cardinality parent-holds-FK polymorphic child fields crash on non-key parent correlation column"
status: Spec
bucket: architecture
priority: 3
theme: interface-union
depends-on: []
created: 2026-07-15
last-updated: 2026-07-16
---

# Single-cardinality parent-holds-FK polymorphic child fields crash on non-key parent correlation column

## Problem

A table-backed type's SELECT contains exactly the client-selected fields plus the columns `TypeClassGenerator.collectRequiredProjection` force-projects (`RequiredProjection`: `BatchKeyField` source-key columns, `TableMethodField` single-FK-hop source-side columns, `NestingField` recursion). `ChildField.InterfaceField` and `ChildField.UnionField` fall into that walk's `default -> {}` arm, so multi-table polymorphic child fields demand nothing of their parent's projection. This corrects the premise of the original filing: the parent's key columns are **not** auto-projected either; every existing execution test happens to select a field mapping the parent key (e.g. `categoryId` in `MultiTablePolymorphicSelfFkOrientationExecutionTest`), which masks the width of the gap. Three consequences, all on table-backed parents (discovered during the R458 slice-1 review; see that item's "Slice-1 review (2026-07-14)" note):

- **Gap A, the filed crash.** The single-fetch form (`MultiTablePolymorphicEmitter.buildScalarPerParentFetcher` and `branchParentFkWhere`) reads each `KeyTupleWhere` slot's parent side off the already-fetched parent row by name: `parentRecord.get(DSL.name("<slot.sourceSide().sqlName()>"), <T>.class)`. A parent-holds-FK participant (self-FK `category.parent_category_id` "navigate to parent", or cross-table `customer.address_id` toward an `address`-backed participant, the `Customer.address: Named` shape in the R281 classified corpus) has its parent side on that FK column, which no client selection projects, so the read always throws `IllegalArgumentException: Field "<fk-col>" is not contained in row type`. The healthy child-holds-FK orientation (parent side = parent key) hits the same exception whenever the client's query selects no field mapping that key column.
- **Gap B, same family, batched forms.** The batched list/connection fetchers extract the DataLoader key by reading `parentSourceKey.columns()` off `env.getSource()` the same way (`GeneratorUtils.buildRecordParentKeyExtraction`, `KeyLift.FkColumns` arm). `InterfaceField`/`UnionField` do not implement `BatchKeyField`, so neither the guarantee walk (`collectRequiredProjection`) nor the requirement walk (`ParentProjectionContainmentCheck`, keyed on the `BatchKeyField` capability) sees the demand. This is exactly the R425 walk-omission family that check exists to catch, latent behind the same test-selection masking.
- **Gap C, batched forms, different mechanism.** The batched correlation joins each branch to a `parentInput` VALUES table whose columns are aliased to the bound-key names only (`buildParentInputValuesEmitter`), and `batchedBranchJoinPredicate` looks the parent side up by name inside it. Nothing constrains a cross-table FK's direction by field cardinality (the `selfRefFkOnSource = !isList` hint orients same-table FKs only), so a list/connection field with a parent-holds-FK participant classifies today and emits `parentInput.field("<fk-col>")` returning null: broken generated code. Supporting that shape means carrying correlation columns through the DataLoader key, which is real capability work: filed as R487 (`batched-polymorphic-parent-holds-fk-correlation`); this item only closes it at build time.

Record-backed parents are outside all three gaps: the single-fetch accessor arm reads a held `TableRecord` whose row type is complete (populating it is the service author's existing contract), and record-backed key lifts ride the held object, never the parent SELECT.

The filed item's "a classification-time guard is the wrong fix" reasoning stands for gap A (the fix is projection, which the classifier effects through the model it already produces) but is single-cardinality-specific; gap C's emitter assumption (parent side within the bound key) is statically checkable and the capability genuinely does not exist yet, which is what the deferred-rejection discipline is for.

## Design

One new orthogonal capability carries the whole projection demand (name sketch `ParentRowDemand`; a standalone non-sealed interface like `BatchKeyField`, principles consult 2026-07-16): `List<ColumnRef> parentRowColumns()`, the columns this child field's generated fetcher reads off the parent's already-materialized row by base name. Implementers:

- `ChildField.TableMethodField`: its single-FK-hop source-side columns (the existing dedicated arm in `collectRequiredProjection` collapses onto the capability; same pattern, `buildTableMethodParentCorrelation` reads `parentRecord` by name).
- `ChildField.InterfaceField` / `ChildField.UnionField`, forked on the field's cardinality (`returnType.wrapper()`): non-list returns the union of `KeyTupleWhere` slot `sourceSide()` columns across `participantJoinPaths` (what `branchParentFkWhere` reads, fixing gap A for both FK orientations); list/connection returns `parentSourceKey.columns()` (what the batched key extraction reads, fixing gap B). A `JoinedCorrelation` entry throws `IllegalStateException`, mirroring `MultiTablePolymorphicEmitter.keyTupleWhereSlots`: its projection demand is undetermined until R458 slice 2 designs the joining correlation (it may correlate inside the joined subquery against the bound key rather than read hop-0 columns off the parent row), and the throw forces that slice to wire the correct projection the moment its classifier arm can construct the shape. Projecting a guess (hop-0 source columns) would be silently wrong if slice 2 lands differently.

Consumers, all keyed on the capability, never on leaf identity:

- `TypeClassGenerator.collectRequiredProjection`: a capability arm replaces the `TableMethodField` arm and adds the demanded columns to `baseColumns`. The walk's existing record-source tripwire (the `BatchKeyField` + `SourceShape.Record` hard throw) extends to the new capability rather than gaining a silent `KeyLift` gate: the walk runs only under the table-type filter, so a record-sourced field carrying parent-row demands reaching it is a generator bug and must fail loudly.
- `ParentProjectionContainmentCheck`: the requirement walk gains a capability-keyed enumeration of `parentRowColumns()` demands alongside the existing `BatchKeyField` one. This preserves the check's documented "never on leaf identity" contract and widens it to correlation reads, closing the R425 family for gaps A and B in one clause. Traversal independence is untouched (its own worklist over `schema.fields()`).
- `GraphitronSchemaValidator.collectBaseNamedKeyColumns`: the capability arm replaces its `TableMethodField` enumeration so every force-projected base-named column participates in the sibling-alias collision rejection (a sibling `@field(name:)` aliasing onto a force-projected column name would otherwise silently miswire reads).

**Gap C guard.** In `FieldBuilder.resolveChildPolymorphicJoinPaths` / `classifyParticipantRoute` (the single choke point for all four producer arms), a list/connection-cardinality field whose resolved `KeyTupleWhere` slots include a `sourceSide()` outside the parent/hub table's primary-key columns gets a `Rejection.deferred` keyed to R487's slug, naming the participant and the FK column and steering toward single cardinality where the relationship is single-valued (a parent-holds-FK participant yields at most one row per parent). Both the explicit `@referenceFor` and auto-discovery arms pass through it. Unlike `JoinedCorrelation`, this invariant is representable in the model (a list-cardinality `KeyTupleWhere` with a non-key parent side is constructible), so the guard's enforcer is `resolveChildPolymorphicJoinPaths` remaining the sole producer; a pipeline test pins the rejection, and the guard carries a comment recording that fact.

Considered and rejected:

- **Full `BatchKeyField` membership for `InterfaceField`/`UnionField`** (fixes gap B through the existing arms with zero new walk code). `parentSourceKey` satisfies `sourceKey()`, but membership also carries `loaderRegistration()` and `rowsMethodName()`, and the polymorphic emitter registers its DataLoaders and names its rows methods inline rather than through the `SplitRowsMethodEmitter` machinery `BatchKeyField` feeds; membership risks routing the polymorphic leaves into generic DataLoader emission partitions, and it still leaves gap A uncovered (correlation columns are not the batch key). If the implementer's survey of `instanceof BatchKeyField` consumers shows membership is inert outside the three walks, it may be revisited; the narrow capability covers both gaps on one axis either way.
- **Leaf-keyed `InterfaceField`/`UnionField` arms in the three walks**: collides with `ParentProjectionContainmentCheck`'s documented no-leaf-identity contract and makes three sites enumerate two more leaves for one predicate (the R425 drift surface).
- **Projecting `JoinedCorrelation` hop-0 source columns now**: bakes a guess about an unshipped slice's internal design into a silently green arm; see the tripwire rationale above.
- **Fixing gap C here**: carrying correlation columns through the DataLoader key changes key identity and dedup semantics; it is an orthogonal axis to both this item's projection contract and R458's route-shape slices, hence its own item (R487).

Gaps A and B belong in one item because they are one invariant, "polymorphic child fields honor the parent-projection contract", carried by one capability arm; scoping B out would leave the same walk omission half-closed behind the same masking.

## Implementation

- `model/`: new capability interface (naming is implementer's latitude; `ParentRowDemand` sketch above), implemented by `TableMethodField`, `InterfaceField`, `UnionField`; the polymorphic accessors hold the cardinality fork and the `JoinedCorrelation` tripwire.
- `TypeClassGenerator.collectRequiredProjection`: capability arm; extended record-source tripwire.
- `ParentProjectionContainmentCheck.check`: capability-keyed requirement enumeration.
- `GraphitronSchemaValidator.collectBaseNamedKeyColumns`: capability arm replaces the `TableMethodField` case.
- `FieldBuilder.classifyParticipantRoute` (threading cardinality from `resolveChildPolymorphicJoinPaths` as needed): the gap C deferred rejection; parent bound key is in scope as the `parentTable` parameter's primary-key columns.
- Cleanups riding along: `MultiTablePolymorphicSelfFkOrientationExecutionTest`'s class-doc points at a nonexistent pipeline test (`singleCardinalitySelfFk_parentSideNotBoundKey_deferred`) and wrongly claims the single-cardinality case is rejected at build time; the `Category` block comment in `graphitron-sakila-example`'s `schema.graphqls` says the single counterpart "crashes at runtime today". Both become descriptions of the shipped fixtures.

## Tests

Pipeline tier (inline SDL fixtures, `MultiTableChildReferenceForPipelineTest` style):

- Cross-table parent-holds-FK single-cardinality field classifies to `KeyTupleWhere` with the parent side on the parent's FK column (pins catalog-identity orientation; the existing same-table test `sameTableSelfFkRoute_classifies_andAutoDiscoveredSiblingMerges` already pins the self-FK single case and keeps passing).
- List-cardinality cross-table parent-holds-FK field rejects DEFERRED naming R487's slug, on both the auto-discovery arm and an explicit `@referenceFor` route; a list-cardinality child-holds-FK field still classifies.
- Unit-tier pinning of the new capability accessors: the polymorphic cardinality fork, and the `JoinedCorrelation` tripwire throw.

Execution tier (sakila):

- `Category.parentRef: CategoryRef @referenceFor(type: "CategoryNode", path: [{key: "category_parent_category_id_fkey"}])`: the single-cardinality navigate-to-parent half deliberately left out of the R458 slice-1 fixture; Action (category 2) resolves its parent Genre node, and multi-table dispatch against `CategoryLabel` stays correct.
- Cross-table parent-holds-FK single cardinality: a `Customer` field returning a small interface or union over an `address`-backed and a `store`-backed participant (`customer.address_id` and `customer.store_id`, both participants with 1-column PKs, satisfying the union's uniform PK arity). This answers the filed question about `Customer.address`: the R281 corpus case stays classification-only, and this fixture is its executable twin.
- Projection-independence queries: select no parent-key-mapped field over (a) the new single-cardinality child and (b) an existing batched list child (`Category.childRefs` without `categoryId`), pinning that correlation and batching work regardless of the client's selection (the masking that hid gaps A and B).

## User documentation

One constraints sentence in `docs/manual/.../polymorphic-types.adoc`: a participant whose correlating foreign key lives on the parent's table is served at single cardinality; on list and connection fields it is rejected at build time as a deferred capability. No `R<n>` markers in the prose (the rejection message itself carries the roadmap slug, matching the existing deferred-rejection precedent in `classifyParticipantRoute`).

## Out of scope

- Delivering batched parent-holds-FK correlation (R487; this item ships its build-time rejection).
- `JoinedCorrelation` projection demands (R458 slices 2/3; the capability tripwire hands the decision to the slice that designs the correlation).
- Root-level multi-table polymorphic fields (R382 / R76 territory).
- Non-polymorphic inline reference shapes (`InlineTableFieldEmitter` correlates inside one SQL statement via the parent's live alias; no parent-row read, no projection demand).
