---
id: R106
title: "Lift same-table `@nodeId` arg/field to a `WHERE pk IN (...)` filter, not a lookup"
status: In Review
bucket: validation
depends-on: []
---

# Lift same-table `@nodeId` arg/field to a `WHERE pk IN (...)` filter, not a lookup

A query field with a top-level same-typename `@nodeId` argument (e.g. `field(id: ID @nodeId(typename: "Customer"), name: String, active: Boolean): Customer`) is classified as a `QueryLookupTableField` even when the field also carries sibling arguments with filter semantics. The classifier short-circuits on the first NodeId-typename match: `NodeIdLeafResolver.resolve` returns `Resolved.SameTable` whenever the `@nodeId(typeName:)` annotation's table equals the field's return-type table (`NodeIdLeafResolver.java:258`); the `SameTable` arm in `FieldBuilder.classifyArgument` (`FieldBuilder.java:784-802`) builds a `ColumnArg` / `CompositeColumnArg` with `isLookupKey=true`; `buildNodeIdArgPlan` records `anyArgSameTable = true` at the first hit; and `classifyQueryField` (`FieldBuilder.java:2343`) routes to `QueryLookupTableField` purely on `anyArgSameTable()` regardless of sibling arguments. (The parallel nested-input case — a `@nodeId` leaf inside an arg-typed input object — already classifies as `QueryTableField` today: the gate checks only `anyArgSameTable()`, not `anyNestedSameTable()`, and `BuildContext.resolveInputField` at `:1206-1219` already lifts nested same-table leaves to `InputField.ColumnField` filter shape. R106 brings the top-level arg path into alignment with the nested-input path that's already correct.)

The chosen direction is to lift same-table `@nodeId` to a filter predicate rather than a lookup key. The `@nodeId` arg becomes one filter among many: a `WHERE pk IN (decoded_ids)` condition (or `WHERE (pk_a, pk_b) IN ((..., ...), ...)` for composite-PK NodeTypes). The field then classifies as `QueryTableField` (or its child equivalents) with the NodeId-derived predicate composed against any sibling filter args; pagination/order-by stay on whatever path they're on today.

This direction is the structurally honest answer: same-table `@nodeId` and FK-target `@nodeId` are both "id-shaped column predicates", and the FK-target path already lifts to `BodyParam.In/Eq/RowIn/RowEq` via `Resolved.FkTarget.DirectFk` (`FieldBuilder.java:803-818`). Pulling `Resolved.SameTable` onto the same filter rail collapses two near-identical paths into one and makes mixed-shape inputs first-class. The N×M derived-table contract that `QueryLookupTableField` exists to deliver is what `@lookupKey` opts into; the implicit same-table `@nodeId` promotion is a footgun the rewrite inherited and should drop.

## Goal

Same-typename `@nodeId` arg / input-field on a table-bound query field classifies as a filter predicate (`BodyParam.In/Eq/RowIn/RowEq`) on the table's primary key, not as a lookup key. The field classifies as `QueryTableField` (or `ChildField.TableField` / `ChildField.NestingField` on the child path), composing the NodeId-derived filter with any sibling filter args. Pure same-table-`@nodeId` schemas (no sibling filters; today's lookup-by-id) migrate into the same shape: a `QueryTableField` with one PK-IN filter.

## Implementation

Shipped on `claude/review-r81-spec-YPvFu`. Single commit folds three classifier-seam edits and the test/coverage updates together.

- **Classifier seam — `FieldBuilder.classifyArgument` SameTable arm.** Flag `isLookupKey` is no longer hard-coded `true` on the `Resolved.SameTable` arm; it now reads `arg.hasAppliedDirective(DIR_LOOKUP_KEY)`. Absent `@lookupKey` ⇒ `isLookupKey = false` ⇒ `projectFilters` emits `BodyParam.In` (arity 1) / `BodyParam.RowIn` (arity ≥ 2). Explicit `@lookupKey` keeps the prior lookup shape. No new variant introduced; the existing `ColumnArg` / `CompositeColumnArg` carriers handle both arms.
- **`@lookupKey` × `@nodeId` composition.** The blanket "redundant" rejection on `@nodeId @lookupKey` is gone; on the same-table arm `@lookupKey` is now the deliberate opt-in for the N×M derived-table shape. The FK-target arm rejects `@lookupKey` with a pointed message (`@lookupKey is meaningless on an FK-target @nodeId arg`) since FK-target `@nodeId` is structurally a filter.
- **`classifyQueryField` gate.** The `lookupPlan.anyArgSameTable()` half of the lookup-promotion gate is dropped; the gate is now purely `hasLookupKeyAnywhere(fieldDef)`. Same-table `@nodeId` with `@lookupKey` still routes through the lookup arm (because `hasLookupKeyAnywhere` returns true via the explicit directive); without `@lookupKey` the field falls through to `QueryTableField`.
- **Child-field path.** No edit needed: `classifyObjectReturnChildField`'s lookup gate is `hasLookupKey = hasLookupKeyAnywhere(fieldDef)` only — no `anyArgSameTable` half — so the arg-side flag flip alone carries same-table `@nodeId` child fields onto the `ChildField.TableField` rail (verified by the green build).
- **Walked nested-input path.** No edit: `BuildContext.resolveInputField` already emits filter-shaped `InputField.ColumnField` / `CompositeColumnField` for nested same-table `@nodeId` leaves. The walker's only remaining consumer is the `@asConnection` rejection at `FieldBuilder.java:403-407`, which still inspects `anyNestedSameTable` / `sameTableHit`.
- **`NodeIdArgPlan` carriers.** `anyArgSameTable`, `anyNestedSameTable`, and `sameTableHit` stay on the carrier. The only remaining consumer is the `@asConnection` rejection. Collapsing the three booleans into a sealed `AsConnectionGuard` is a clean follow-up but out of scope for R106 — keeps the diff focused on the lift.

## Tests

- **`NodeIdPipelineTest`.** `ArgumentSameTableNodeIdCase` cases 1-4 migrated from `QueryLookupTableField` / `ScalarLookupArg` / `DecodedRecord` assertions to `QueryTableField` + `BodyParam.In` / `BodyParam.RowIn` assertions. Replaced the legacy `LOOKUP_KEY_REDUNDANT_REJECTED` case with two new cases pinning the new behaviour: `SAME_TABLE_WITH_EXPLICIT_LOOKUP_KEY` (same-table `@nodeId @lookupKey` re-enables `QueryLookupTableField` with `ScalarLookupArg`) and `FK_TARGET_LOOKUP_KEY_REJECTED` (FK-target `@nodeId @lookupKey` → `UnclassifiedField` with the new pointed message). Added `SAME_TABLE_WITH_FILTER_SIBLING` to pin the headline R106 lift: composite-PK same-table `@nodeId` arg + sibling scalar filter classifies as `QueryTableField` with a `BodyParam.RowIn` for the NodeId arg and a `BodyParam.Eq` for the sibling.
- **`LookupTableFieldPipelineTest` / `LookupTableFieldValidationTest`.** No edits needed: both files only exercise explicit `@lookupKey`-driven cases, which still classify as lookups post-R106. Verified by the green build.
- **No new `QueryTableFieldPipelineTest` class.** The spec called for one as a new home for the migrated cases; in practice the existing `NodeIdPipelineTest.ArgumentSameTableNodeIdCase` enum is already the canonical home for arg-level `@nodeId` classification, and folding the migration into that enum keeps the pipeline-test taxonomy single-rooted. Adding a new class for the same coverage was redundant. Pure-lookup-shape and composite-PK variants both ship as cases inside the existing enum.
- **Execution-tier.** Added `filmsByNodeIdArgWithTitleFilter` query field to the sakila example schema and a new `GraphQLQueryTest.filmsByNodeIdArgWithTitleFilter_composesPkInWithSiblingFilter` test exercising the headline lift end-to-end (PK-IN composed with `WHERE title = ?`). The existing `films_filteredByArgNodeId_returnsRowsMatchingDecodedIds` test stayed green; its comment was updated to reflect the new `QueryTableField` shape.
- **Audit.** No `@LoadBearingClassifierCheck` keys touched: the flip is inert at the audit boundary as predicted during spec authoring.

## Out of scope

- The implicit `@lookupKey` directive walker `inputTypeHasLookupKey` (`FieldBuilder.java:2528-2538`) is untouched. Explicit `@lookupKey` (whether on `@nodeId` args via the new fork or on standalone args) continues to opt into the N×M derived-table shape; this item only changes the *implicit* same-table `@nodeId` promotion path.
- FK-target `@nodeId` (already lifts to a filter via `Resolved.FkTarget.DirectFk`). No behaviour change there.
- Adding a search-field classifier arm. The `QueryTableField` shape with composed filters already serves the search use case once this lift lands; `faceted-search.md` covers larger search-surface work.
- `@asConnection` + same-table `@nodeId` rejection at `FieldBuilder.java:403-407`. The validator there stays; pagination semantics on a PK-IN filter is incoherent for the same reasons it was for the lookup shape.
- Collapsing `NodeIdArgPlan.{anyArgSameTable, anyNestedSameTable, sameTableHit}` into a sealed `AsConnectionGuard.{None | Hit}` carrier. Clean follow-up once this is the only consumer; out of scope here to keep the diff focused on the lift.
