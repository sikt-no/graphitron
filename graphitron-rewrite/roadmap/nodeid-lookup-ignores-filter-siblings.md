---
id: R106
title: "Lift same-table `@nodeId` arg/field to a `WHERE pk IN (...)` filter, not a lookup"
status: Spec
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

### Classifier seam: flip `isLookupKey` on `Resolved.SameTable`

`FieldBuilder.classifyArgument` (`FieldBuilder.java:784-802`): the `Resolved.SameTable` arm builds `ColumnArg(..., /* isLookupKey= */ true)` / `CompositeColumnArg(..., /* isLookupKey= */ true)` today. The shape carrier (`ColumnArg` / `CompositeColumnArg`) is already correct for a same-table-own-column predicate; the `isLookupKey` flag is what gates the lookup-mapping projection in `projectFilters` (see `:1088` and `:1106` — `!ca.isLookupKey()` and `!cca.isLookupKey()` are the filter-vs-lookup forks). The change is to flip the flag from `true` to `false` so the existing filter-projection branch fires; `BodyParam.In` (arity 1) and `BodyParam.RowIn` (arity ≥ 2) are already what `projectFilters` emits for that branch.

This avoids introducing a new variant or relying on `joinPath = List.of()` as a same-table sentinel: the existing seal already distinguishes "own-column predicate" (`ColumnArg` / `CompositeColumnArg`) from "FK-target predicate with join chain" (`ColumnReferenceArg` / `CompositeColumnReferenceArg`). Same-table `@nodeId` is conceptually own-column and stays in that arm. The input-field-side path mirrors this pick: `BuildContext.java:1206-1219` already emits `InputField.ColumnField` / `InputField.CompositeColumnField` for nested same-table `@nodeId` leaves (filter shape on own-column), reserving `InputField.ColumnReferenceField` for FK-target leaves with a non-empty `joinPath`. The arg-side change brings argument-level classification into the same shape the input-field-level path already uses.

### `@lookupKey` × same-table `@nodeId` becomes a deliberate opt-in

`FieldBuilder.classifyArgument` (`FieldBuilder.java:765-770`) currently rejects `@lookupKey` on a `@nodeId` arg as redundant: *"@nodeId already implies @lookupKey for same-table; the explicit directive is redundant"*. That rejection's premise — same-table `@nodeId` implies lookup — is exactly what this item unwinds. Post-R106 the directive is meaningful: `@lookupKey` re-enables the N×M derived-table contract on top of an `@nodeId` arg, restoring the lookup shape for callers who want it. The deliberate pick is **lift the rejection on the same-table arm; keep it on the FK-target arm** (FK-target `@nodeId` is structurally a filter, never a lookup, where `@lookupKey` remains meaningless). The SameTable arm at `:784-802` then forks on `arg.hasAppliedDirective(DIR_LOOKUP_KEY)`: explicit `@lookupKey` keeps `isLookupKey = true` (lookup shape preserved); absence sets `isLookupKey = false` (the new filter default). The composition rejection's wording at `:765-770` updates to apply only to FK-target.

### Drop the lookup-promotion gate on `anyArgSameTable`

`FieldBuilder.classifyQueryField` (`FieldBuilder.java:2343`): the gate is `if (hasLookupKeyAnywhere(fieldDef) || lookupPlan.anyArgSameTable())`. Drop the `anyArgSameTable()` half. With the flag-flip above, same-table `@nodeId` args without `@lookupKey` no longer carry `isLookupKey = true`; the field falls through to the `QueryTableField` arm at `:2383-2391`. Same-table `@nodeId` args **with** `@lookupKey` still classify as `QueryLookupTableField` because `hasLookupKeyAnywhere(fieldDef)` returns true via the explicit directive. The gate's job is now exactly "explicit `@lookupKey` opt-in"; no implicit promotion remains.

`NodeIdArgPlan.anyArgSameTable`, `anyNestedSameTable`, and `sameTableHit` stay on the carrier. The `@asConnection` + same-table rejection at `:403-407` is the only remaining consumer; collapsing the three booleans into a sealed `AsConnectionGuard` carrier is a clean follow-up but out of scope for this item — call out in commit message so the principles-architect record stays honest.

The parallel child-field path (`classifyObjectReturnChildField` at `FieldBuilder.java:456,468-475`) does not need a separate change: its lookup gate is `hasLookupKey = hasLookupKeyAnywhere(fieldDef)` only — no `anyArgSameTable` half. Once the arg-side flag flip lands, child fields with same-table `@nodeId` (no `@lookupKey`) classify as `ChildField.TableField` because the `isLookupKey = false` flag flows through `tfc.filters()` into the table-field arm at `:444-453`. Verify by reading the diff.

### Walked nested-input path: no change required

`walkInputTypeForSameTableNodeId` (`FieldBuilder.java:281-303`) is a detector-only walker; the actual InputField construction for nested `@nodeId` leaves lives in `BuildContext.resolveInputField` (`BuildContext.java:1201-1237`), which **already** emits filter-shaped InputField variants for `Resolved.SameTable` (own-column `InputField.ColumnField` / `InputField.CompositeColumnField`) and for `Resolved.FkTarget.DirectFk` (FK-target `InputField.ColumnReferenceField` / `InputField.CompositeColumnReferenceField`). The walker's only post-R106 consumer is the `@asConnection` rejection at `:403-407`, which already inspects `anyNestedSameTable` and `sameTableHit`. No edit to the walker; no edit to `BuildContext.resolveInputField`'s `@nodeId` arms.

### Decode-helper, arity caps, and rejections

No change to `NodeIdLeafResolver.resolve` itself: the arity-22 cap (`:243-249`), the `@table`-required check (`:227-229`), and the structural-rejection messaging stay.

## Tests

### Pipeline-tier (primary behavioural tier)

- New pipeline-test class `QueryTableFieldPipelineTest` (paralleling the existing validation-tier `QueryTableFieldValidationTest` at `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/validation/QueryTableFieldValidationTest.java`; the existing `TableFieldPipelineTest` is `ChildField.TableField`-scoped, so the root-level cases need their own home). Cases:
  - `sameTableNodeId_withFilterSiblings_classifiesAsTableField` — a query field with a same-typename `@nodeId` argument and a sibling scalar filter; assert the classified node is `QueryTableField` (not `QueryLookupTableField`), and that `tfc.filters()` carries one `BodyParam.In` (or `RowIn` for composite PK) for the NodeId arg plus one `BodyParam.Eq` for the sibling.
  - `sameTableNodeId_pureLookupShape_classifiesAsTableField` — a query field with only a same-typename `@nodeId` arg (today's pure-lookup-by-id shape); assert it now classifies as `QueryTableField` with a single PK filter (not `QueryLookupTableField`). This pins the migration: the lift is uniform, not gated by sibling presence.
  - Composite-PK variant of both cases above (`RowIn` shape).
- `LookupTableFieldPipelineTest`: existing same-table-`@nodeId` cases either remove (the shape they pin no longer classifies as a lookup) or migrate to the new `QueryTableFieldPipelineTest` with the new expected classification. Audit each case during implementation; the test file's `@lookupKey`-driven cases stay (those still classify as lookups).
- `NodeIdPipelineTest`: the `ArgumentSameTableNodeIdCase` enum (line 887-1022 per prior research) updates to assert the new classification. Existing `ArgumentFkTargetNodeIdCase` cases (line 1024+) stay green since the FK-target path is unchanged. New `ArgumentSameTableNodeIdCase` case: `sameTableNodeId_withExplicitLookupKey_classifiesAsQueryLookupTableField` pins that explicit `@lookupKey` re-enables lookup shape on a same-table `@nodeId` arg.
- `nestedInputSameTableNodeId_classifiesAsTableField`: a query field whose argument is an input type containing a same-typename `@nodeId` leaf alongside a sibling filter input-field; assert the field classifies as `QueryTableField`, the NodeId leaf becomes `InputField.ColumnField` (or `CompositeColumnField`), and the sibling becomes a `ColumnField`-driven filter. Pins that the input-field-side lift `BuildContext.java:1201-1237` already emits stays load-bearing for this case post-R106.
- `ChildField` parallels: `LookupTableFieldValidationTest` and the child-field pipeline cases get the same treatment (lift to `ChildField.TableField`).

### Execution-tier (the proof)

- New test under `graphitron-rewrite/graphitron-sakila-example/src/test/java/.../internal/`: a sakila query field exercising same-table `@nodeId` + filter sibling (e.g. `customer(input: { id: ID, active: Boolean }): Customer` against the sakila customer table); asserts (a) the right SQL fires (one statement, `WHERE customer_id IN (?) AND active = ?`, no derived-table N×M shape), (b) the result is a single customer matching both predicates, (c) decoded-id mismatch falls silently to no-match (per the existing `SkipMismatchedElement` contract).

### Audit / unit-tier

- `LoadBearingGuaranteeAuditTest`: confirmed (during spec authoring) that no existing `@LoadBearingClassifierCheck` keys depend on `lookupPlan.anyArgSameTable()` or on the `isLookupKey = true` setting in the `Resolved.SameTable` arm. The flip is contractually inert at the audit boundary; if the implementation introduces a new load-bearing reliance during the lift, the producer / consumer pair lands in the same commit.

## Out of scope

- The implicit `@lookupKey` directive walker `inputTypeHasLookupKey` (`FieldBuilder.java:2528-2538`) is untouched. Explicit `@lookupKey` (whether on `@nodeId` args via the new fork or on standalone args) continues to opt into the N×M derived-table shape; this item only changes the *implicit* same-table `@nodeId` promotion path.
- FK-target `@nodeId` (already lifts to a filter via `Resolved.FkTarget.DirectFk`). No behaviour change there.
- Adding a search-field classifier arm. The `QueryTableField` shape with composed filters already serves the search use case once this lift lands; `faceted-search.md` covers larger search-surface work.
- `@asConnection` + same-table `@nodeId` rejection at `FieldBuilder.java:403-407`. The validator there stays; pagination semantics on a PK-IN filter is incoherent for the same reasons it was for the lookup shape.
- Collapsing `NodeIdArgPlan.{anyArgSameTable, anyNestedSameTable, sameTableHit}` into a sealed `AsConnectionGuard.{None | Hit}` carrier. Clean follow-up once this is the only consumer; out of scope here to keep the diff focused on the lift.
