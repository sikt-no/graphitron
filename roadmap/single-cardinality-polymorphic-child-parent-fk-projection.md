---
id: R481
title: "Single-cardinality parent-holds-FK polymorphic child fields crash on non-key parent correlation column"
status: In Review
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

- **Gap A, the filed crash.** The single-fetch form (`MultiTablePolymorphicEmitter.buildScalarPerParentFetcher` and `singleBranchCorrelationWhere`, renamed from `branchParentFkWhere` when R458 slices 2-3 landed at 5968d53) reads each branch's parent side off the already-fetched parent row by name, `parentRecord.get(DSL.name("<col>"), <T>.class)`: the `KeyTupleWhere` slots' `sourceSide()` columns (`valueBoundParentWhere`), a `JoinedCorrelation` FK hop-0's (`On.ColumnPairs`) slot `sourceSide()` columns (same helper), or the parent's bound key for a `JoinedCorrelation` condition hop-0 (`parentKeyBoundWhere`). A parent-holds-FK participant (self-FK `category.parent_category_id` "navigate to parent", or cross-table `customer.address_id` toward an `address`-backed participant, the `Customer.address: Named` shape in the R281 classified corpus) has its parent side on that FK column, which no client selection projects, so the read always throws `IllegalArgumentException: Field "<fk-col>" is not contained in row type`. The healthy child-holds-FK orientation (parent side = parent key) hits the same exception whenever the client's query selects no field mapping that key column.
- **Gap B, same family, batched forms.** The batched list/connection fetchers extract the DataLoader key by reading `parentSourceKey.columns()` off `env.getSource()` the same way (`GeneratorUtils.buildRecordParentKeyExtraction`, `KeyLift.FkColumns` arm). `InterfaceField`/`UnionField` do not implement `BatchKeyField`, so neither the guarantee walk (`collectRequiredProjection`) nor the requirement walk (`ParentProjectionContainmentCheck`, keyed on the `BatchKeyField` capability) sees the demand. This is exactly the R425 walk-omission family that check exists to catch, latent behind the same test-selection masking.
- **Gap C, batched forms, different mechanism.** The batched correlation joins each branch to a `parentInput` VALUES table whose columns are aliased to the bound-key names only (`buildParentInputValuesEmitter`), and the branch's `JOIN parentInput` predicate (`batchedBranchCorrelationChain` / `parentInputSlotPredicate`, post-R458 names for `batchedBranchJoinPredicate`) looks the parent side up by name inside it, for a `KeyTupleWhere` and equally for a `JoinedCorrelation` FK hop-0 (a condition hop-0 correlates on the bound key via `parentInputKeyPredicate` and is immune). Nothing constrains a cross-table FK's direction by field cardinality (the `selfRefFkOnSource = !isList` hint orients same-table FKs only), so a list/connection field with a parent-holds-FK participant, whether the FK correlates a single hop or heads a longer route, classifies today and emits `parentInput.field("<fk-col>")` returning null: broken generated code. Supporting that shape means carrying correlation columns through the DataLoader key, which is real capability work: filed as R487 (`batched-polymorphic-parent-holds-fk-correlation`); this item only closes it at build time.

Record-backed parents are outside all three gaps: the single-fetch accessor arm reads a held `TableRecord` whose row type is complete (populating it is the service author's existing contract), and record-backed key lifts ride the held object, never the parent SELECT.

The filed item's "a classification-time guard is the wrong fix" reasoning stands for gap A (the fix is projection, which the classifier effects through the model it already produces) but is single-cardinality-specific; gap C's emitter assumption (parent side within the bound key) is statically checkable and the capability genuinely does not exist yet, which is what the deferred-rejection discipline is for.

## Design

One new orthogonal capability carries the whole projection demand (name sketch `ParentRowDemand`; a standalone non-sealed interface like `BatchKeyField`, principles consult 2026-07-16): `List<ColumnRef> parentRowColumns()`, the columns this child field's generated fetcher reads off the parent's already-materialized row by base name. Implementers:

- `ChildField.TableMethodField`: its single-FK-hop source-side columns (the existing dedicated arm in `collectRequiredProjection` collapses onto the capability; same pattern, `buildTableMethodParentCorrelation` reads `parentRecord` by name).
- `ChildField.InterfaceField` / `ChildField.UnionField`, forked on the field's cardinality (`returnType.wrapper()`). Non-list returns the union, across `participantJoinPaths` values, of what `singleBranchCorrelationWhere` reads off the parent row (fixing gap A for both FK orientations): `KeyTupleWhere` slot `sourceSide()` columns; a `JoinedCorrelation` FK hop-0's slot `sourceSide()` columns; the parent's bound key (`parentSourceKey.columns()`) for a `JoinedCorrelation` condition hop-0, which `parentKeyBoundWhere` pins the joined parent alias to. List/connection returns `parentSourceKey.columns()` regardless of correlation shape (what the batched key extraction reads, fixing gap B). An `On.Lateral` hop-0 throws `IllegalStateException`, mirroring the emitter's own unreachable-arm guard. (The drafted spec had a blanket `JoinedCorrelation` tripwire throw here, deferring the demand to R458 slice 2's design of the joining correlation; slices 2-3 shipped between drafting and review, commit 5968d53, and the parent-row reads enumerated above are now shipped code exercised by `MultiTablePolymorphicJoinedCorrelationExecutionTest`, so the demand is determined and a throw would break generation of live shapes.)

Consumers, all keyed on the capability, never on leaf identity:

- `TypeClassGenerator.collectRequiredProjection`: a capability arm replaces the `TableMethodField` arm and adds the demanded columns to `baseColumns`. The walk's existing record-source tripwire (the `BatchKeyField` + `SourceShape.Record` hard throw) extends to the new capability rather than gaining a silent `KeyLift` gate: the walk runs only under the table-type filter, so a record-sourced field carrying parent-row demands reaching it is a generator bug and must fail loudly.
- `ParentProjectionContainmentCheck`: the requirement walk gains a capability-keyed enumeration of `parentRowColumns()` demands alongside the existing `BatchKeyField` one. This preserves the check's documented "never on leaf identity" contract and widens it to correlation reads, closing the R425 family for gaps A and B in one clause. Traversal independence is untouched (its own worklist over `schema.fields()`).
- `GraphitronSchemaValidator.collectBaseNamedKeyColumns`: the capability arm replaces its `TableMethodField` enumeration so every force-projected base-named column participates in the sibling-alias collision rejection (a sibling `@field(name:)` aliasing onto a force-projected column name would otherwise silently miswire reads).

**Gap C guard.** In `FieldBuilder.resolveChildPolymorphicJoinPaths` / `classifyParticipantRoute` (the single choke point for both the explicit `@referenceFor` and auto-discovery arms), a list/connection-cardinality field whose resolved correlation reads a parent-side column outside the parent/hub table's primary key, a `KeyTupleWhere` slot or a `JoinedCorrelation` FK hop-0 slot with an off-key `sourceSide()` (a condition hop-0 correlates on the bound key and is exempt), gets a `Rejection.deferred` keyed to R487's slug, naming the participant and the FK column and steering toward single cardinality where the relationship is single-valued (a parent-holds-FK participant yields at most one row per parent). This invariant is representable in the model (a list-cardinality correlation with a non-key parent side is constructible), so the guard's enforcer is `resolveChildPolymorphicJoinPaths` remaining the sole producer; a pipeline test pins the rejection, and the guard carries a comment recording that fact.

Considered and rejected:

- **Full `BatchKeyField` membership for `InterfaceField`/`UnionField`** (fixes gap B through the existing arms with zero new walk code). `parentSourceKey` satisfies `sourceKey()`, but membership also carries `loaderRegistration()` and `rowsMethodName()`, and the polymorphic emitter registers its DataLoaders and names its rows methods inline rather than through the `SplitRowsMethodEmitter` machinery `BatchKeyField` feeds; membership risks routing the polymorphic leaves into generic DataLoader emission partitions, and it still leaves gap A uncovered (correlation columns are not the batch key). If the implementer's survey of `instanceof BatchKeyField` consumers shows membership is inert outside the three walks, it may be revisited; the narrow capability covers both gaps on one axis either way.
- **Leaf-keyed `InterfaceField`/`UnionField` arms in the three walks**: collides with `ParentProjectionContainmentCheck`'s documented no-leaf-identity contract and makes three sites enumerate two more leaves for one predicate (the R425 drift surface).
- **A blanket `JoinedCorrelation` tripwire throw in the accessors** (the drafted design): correct while R458 slices 2/3 were unshipped and the joining correlation's parent-row reads were undesigned, obsolete once 5968d53 shipped them. The throw would fire during generation of live fixtures, and the demand it guarded is now determined by shipped emitter code (see the accessor bullet).
- **Fixing gap C here**: carrying correlation columns through the DataLoader key changes key identity and dedup semantics; it is an orthogonal axis to both this item's projection contract and R458's route-shape slices, hence its own item (R487).

Gaps A and B belong in one item because they are one invariant, "polymorphic child fields honor the parent-projection contract", carried by one capability arm; scoping B out would leave the same walk omission half-closed behind the same masking.

## Implementation

- `model/`: new capability interface (naming is implementer's latitude; `ParentRowDemand` sketch above), implemented by `TableMethodField`, `InterfaceField`, `UnionField`; the polymorphic accessors hold the cardinality fork and the per-correlation-shape enumeration (`On.Lateral` hop-0 throw only). While there, refresh the `InterfaceField` javadoc's stale "the `JoinedCorrelation` arm arrives with slices 2/3" sentence.
- `TypeClassGenerator.collectRequiredProjection`: capability arm; extended record-source tripwire.
- `ParentProjectionContainmentCheck.check`: capability-keyed requirement enumeration.
- `GraphitronSchemaValidator.collectBaseNamedKeyColumns`: capability arm replaces the `TableMethodField` case.
- `FieldBuilder.classifyParticipantRoute` (threading cardinality from `resolveChildPolymorphicJoinPaths` as needed): the gap C deferred rejection; parent bound key is in scope as the `parentTable` parameter's primary-key columns.
- Cleanups riding along: `MultiTablePolymorphicSelfFkOrientationExecutionTest`'s class-doc points at a nonexistent pipeline test (`singleCardinalitySelfFk_parentSideNotBoundKey_deferred`) and wrongly claims the single-cardinality case is rejected at build time; the `Category` block comment in `graphitron-sakila-example`'s `schema.graphqls` says the single counterpart "crashes at runtime today". Both become descriptions of the shipped fixtures.

## Tests

Pipeline tier (inline SDL fixtures, `MultiTableChildReferenceForPipelineTest` style):

- Cross-table parent-holds-FK single-cardinality field classifies to `KeyTupleWhere` with the parent side on the parent's FK column (pins catalog-identity orientation; the existing same-table test `sameTableSelfFkRoute_classifies_andAutoDiscoveredSiblingMerges` already pins the self-FK single case and keeps passing).
- List-cardinality cross-table parent-holds-FK field rejects DEFERRED naming R487's slug, on both the auto-discovery arm and an explicit `@referenceFor` route; a list-cardinality multi-hop route whose hop-0 FK lives on the parent table rejects the same way (the `JoinedCorrelation` half of the gap C guard); a list-cardinality child-holds-FK field still classifies.
- Unit-tier pinning of the new capability accessors: the polymorphic cardinality fork across the three correlation shapes (`KeyTupleWhere`, `JoinedCorrelation` FK hop-0, `JoinedCorrelation` condition hop-0).

Execution tier (sakila):

- `Category.parentRef: CategoryRef @referenceFor(type: "CategoryNode", path: [{key: "category_parent_category_id_fkey"}])`: the single-cardinality navigate-to-parent half deliberately left out of the R458 slice-1 fixture; Action (category 2) resolves its parent Genre node, and multi-table dispatch against `CategoryLabel` stays correct.
- Cross-table parent-holds-FK single cardinality: a `Customer` field returning a small interface or union over an `address`-backed and a `store`-backed participant (`customer.address_id` and `customer.store_id`, both participants with 1-column PKs, satisfying the union's uniform PK arity). This answers the filed question about `Customer.address`: the R281 corpus case stays classification-only, and this fixture is its executable twin.
- Projection-independence queries: select no parent-key-mapped field over (a) the new single-cardinality child, (b) an existing batched list child (`Category.childRefs` without `categoryId`), and (c) an existing `JoinedCorrelation` single-cardinality child (a `MultiTablePolymorphicJoinedCorrelationExecutionTest` field without `filmId`; that suite's queries all select the parent key today, the same masking), pinning that correlation and batching work regardless of the client's selection (the masking that hid gaps A and B).

## User documentation

One constraints sentence in `docs/manual/.../polymorphic-types.adoc`: a participant whose route correlates through a foreign key held on the parent's table (a single hop, or the first hop of a longer route) is served at single cardinality; on list and connection fields it is rejected at build time as a deferred capability. No `R<n>` markers in the prose (the rejection message itself carries the roadmap slug, matching the deferred-rejection precedent elsewhere in `FieldBuilder`).

## Out of scope

- Delivering batched parent-holds-FK correlation (R487; this item ships its build-time rejection).
- Root-level multi-table polymorphic fields (R382 / R76 territory).
- Non-polymorphic inline reference shapes (`InlineTableFieldEmitter` correlates inside one SQL statement via the parent's live alias; no parent-row read, no projection demand).

## Spec review (2026-07-16)

R458 slices 2-3 (commit 5968d53) landed between this spec's drafting and review, and the review re-verified every code anchor against that trunk. The gap analysis, capability shape, and consumer list all held; three things did not, and this revision folds them in: the blanket `JoinedCorrelation` accessor tripwire became build-breaking (the joining correlation's parent-row reads are now shipped code, so the demand it deferred is determined; the accessors now enumerate all three correlation shapes, with only `On.Lateral` hop-0 throwing), the gap C guard widened from `KeyTupleWhere`-only to any off-key hop-0 parent side (the batched `JoinedCorrelation` FK hop-0 arm uses the same `parentInput.field(...)` lookup), and the emitter symbol anchors were refreshed to their post-R458 names (`branchParentFkWhere` → `singleBranchCorrelationWhere`, `batchedBranchJoinPredicate` → `batchedBranchCorrelationChain`, `keyTupleWhereSlots` deleted). Tests widened to match. These are substantive revisions, so per the reviewer rule the Spec → Ready sign-off must come from a session other than this one.
