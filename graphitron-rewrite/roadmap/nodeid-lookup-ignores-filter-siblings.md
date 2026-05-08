---
id: R106
title: "Lift same-table `@nodeId` arg/field to a `WHERE pk IN (...)` filter, not a lookup"
status: Spec
bucket: validation
depends-on: []
---

# Lift same-table `@nodeId` arg/field to a `WHERE pk IN (...)` filter, not a lookup

A query field with a same-typename `@nodeId` argument or input-field (e.g. `field(input: { id: ID @nodeId(typename: "Customer"), name: String, active: Boolean }): Customer`) is classified as a `QueryLookupTableField` even when the input also carries fields with filter semantics. The classifier short-circuits on the first NodeId-typename match: `NodeIdLeafResolver.resolve` returns `Resolved.SameTable` whenever the `@nodeId(typeName:)` annotation's table equals the field's return-type table (`NodeIdLeafResolver.java:258`); the `SameTable` arm in `FieldBuilder.classifyArgument` (`FieldBuilder.java:784-802`) builds a `ColumnArg` / `CompositeColumnArg` with `isLookupKey=true`; `buildNodeIdArgPlan` records `anyArgSameTable = true` at the first hit; and `classifyQueryField` (`FieldBuilder.java:2336`) routes to `QueryLookupTableField` purely on `anyArgSameTable()` regardless of sibling input fields or sibling arguments.

The chosen direction is to lift same-table `@nodeId` to a filter predicate rather than a lookup key. The `@nodeId` arg becomes one filter among many: a `WHERE pk IN (decoded_ids)` condition (or `WHERE (pk_a, pk_b) IN ((..., ...), ...)` for composite-PK NodeTypes). The field then classifies as `QueryTableField` (or its child equivalents) with the NodeId-derived predicate composed against any sibling filter args; pagination/order-by stay on whatever path they're on today.

This direction is the structurally honest answer: same-table `@nodeId` and FK-target `@nodeId` are both "id-shaped column predicates", and the FK-target path already lifts to `BodyParam.In/Eq/RowIn/RowEq` via `Resolved.FkTarget.DirectFk` (`FieldBuilder.java:803-818`). Pulling `Resolved.SameTable` onto the same filter rail collapses two near-identical paths into one and makes mixed-shape inputs first-class. The N×M derived-table contract that `QueryLookupTableField` exists to deliver is what `@lookupKey` opts into; the implicit same-table `@nodeId` promotion is a footgun the rewrite inherited and should drop.

## Goal

Same-typename `@nodeId` arg / input-field on a table-bound query field classifies as a filter predicate (`BodyParam.In/Eq/RowIn/RowEq`) on the table's primary key, not as a lookup key. The field classifies as `QueryTableField` (or `ChildField.TableField` / `ChildField.NestingField` on the child path), composing the NodeId-derived filter with any sibling filter args. Pure same-table-`@nodeId` schemas (no sibling filters; today's lookup-by-id) migrate into the same shape: a `QueryTableField` with one PK-IN filter.

## Implementation

### Classifier seam: lift `Resolved.SameTable` onto the filter rail

`FieldBuilder.classifyArgument` (`FieldBuilder.java:784-802`): replace the current `SameTable` arm. It builds `ColumnArg(..., isLookupKey=true)` / `CompositeColumnArg(..., isLookupKey=true)` today; that shape promotes the field to `QueryLookupTableField`. Mirror the existing `FkTarget.DirectFk` arm at `:803-818` instead — produce `ArgumentRef.ScalarArg.ColumnReferenceArg` for arity-1 PK and `ArgumentRef.ScalarArg.CompositeColumnReferenceArg` for arity-N PK, both carrying:

- `keyColumns()` from `Resolved.SameTable` (already provided by the resolver).
- `joinPath = List.of()` — same-table is by definition zero-hop, so the existing column-reference filter projection emits the predicate directly against `rt`'s columns without any FK join. The DirectFk arm passes `direct.joinPath()` (a non-empty FK chain); the same-table arm passes empty.
- `extraction = SkipMismatchedElement(st.decodeMethod())` — already what the current arm uses, and what DirectFk uses; "malformed id drops silently to no-match" is the right semantics for a filter.

`projectFilters` already emits `BodyParam.In` / `BodyParam.RowIn` for `ColumnReferenceArg` / `CompositeColumnReferenceArg` with list-typed args, and `BodyParam.Eq` / `BodyParam.RowEq` for non-list args. No change to `projectFilters` is required; the new arm hands it shapes it already understands. Verify by reading the existing emit path for FK-target same-table-arity NodeId args.

### Drop the lookup-promotion gate on `anyArgSameTable`

`FieldBuilder.classifyQueryField` (`FieldBuilder.java:2336`): the gate is `if (hasLookupKeyAnywhere(fieldDef) || lookupPlan.anyArgSameTable())`. Drop the `anyArgSameTable()` half. After the lift, same-table `@nodeId` no longer carries `isLookupKey=true`, so it correctly falls through to the `QueryTableField` arm at `:2376-2384`. Explicit `@lookupKey` still triggers the lookup arm (the directive is the user-opt-in for the N×M contract).

`NodeIdArgPlan.anyArgSameTable` and `anyNestedSameTable` stay as fields, because `@asConnection` rejection at `:403-407` still uses them — a same-table `@nodeId` under `@asConnection` is incoherent for a different reason (cardinality bound by id-list, not paginatable). The validator there is unchanged.

The parallel child-field path (`FieldBuilder.java:456,468-475` in `classifyObjectReturnChildField`) needs the same change: `hasLookupKey = hasLookupKeyAnywhere(fieldDef)` is the gate today; drop any same-table-NodeId trigger if present (audit when reading the diff). Children with same-table `@nodeId` then classify as `ChildField.TableField` with the PK-IN filter, not `ChildField.LookupTableField`.

### Walked nested-input path: same lift inside input types

The same lift applies to `Resolved.SameTable` reached via `walkInputTypeForSameTableNodeId` (`FieldBuilder.java:281-308`). The walker today only records the hit on `NodeIdArgPlan` for the lookup-promotion gate; with the gate gone, the walker's job is complete once the per-leaf classifier inside the nested input emits the filter shape. Trace the input-field classification path (the analogue of `classifyArgument` for `GraphQLInputObjectField`) and confirm the `SameTable` arm there does the same lift.

### Decode-helper, arity caps, and rejections

No change to `NodeIdLeafResolver.resolve` itself: the arity-22 cap (`:243-249`), the `@table`-required check (`:227-229`), and the structural-rejection messaging stay. The lift is a downstream consumer change.

## Tests

### Pipeline-tier (primary behavioural tier)

- `QueryTableFieldPipelineTest`: new `sameTableNodeId_withFilterSiblings_classifiesAsTableField` case — a query field with a same-typename `@nodeId` argument and a sibling scalar filter; assert the classified node is `QueryTableField` (not `QueryLookupTableField`), and that `tfc.filters()` carries one `BodyParam.In` (or `RowIn` for composite PK) for the NodeId arg plus one `BodyParam.Eq` for the sibling.
- `QueryTableFieldPipelineTest`: new `sameTableNodeId_pureLookupShape_classifiesAsTableField` case — a query field with only a same-typename `@nodeId` arg (today's pure-lookup-by-id shape); assert it now classifies as `QueryTableField` with a single PK filter (not `QueryLookupTableField`). This pins the migration: the lift is uniform, not gated by sibling presence.
- `QueryTableFieldPipelineTest`: composite-PK variant of both cases above (`RowIn` shape).
- `LookupTableFieldPipelineTest`: existing same-table-`@nodeId` cases either remove (the shape they pin no longer classifies as a lookup) or migrate to `QueryTableFieldPipelineTest` with the new expected classification. Audit each case during implementation; the test file's `@lookupKey`-driven cases stay (those still classify as lookups).
- `NodeIdPipelineTest`: the `ArgumentSameTableNodeIdCase` enum (line 887-1022 per prior research) updates to assert the new classification. Existing `FkTargetNodeIdCase` cases (line 1024+) stay green since the FK-target path is unchanged.
- `ChildField` parallels: `LookupTableFieldValidationTest` and the child-field pipeline cases get the same treatment (lift to `ChildField.TableField`).

### Execution-tier (the proof)

- New test under `graphitron-rewrite/graphitron-sakila-example/src/test/java/.../internal/`: a sakila query field exercising same-table `@nodeId` + filter sibling (e.g. `customer(input: { id: ID, active: Boolean }): Customer` against the sakila customer table); asserts (a) the right SQL fires (one statement, `WHERE customer_id IN (?) AND active = ?`, no derived-table N×M shape), (b) the result is a single customer matching both predicates, (c) decoded-id mismatch falls silently to no-match (per the existing `SkipMismatchedElement` contract).

### Audit / unit-tier

- `LoadBearingGuaranteeAuditTest`: any `@LoadBearingClassifierCheck` keys gated on `lookupPlan.anyArgSameTable()` need re-pinning. The audit-tier sweep catches orphaned guarantees automatically; updates land in the same commit.

## Out of scope

- The `@lookupKey` directive path. Explicit `@lookupKey` continues to opt into the N×M derived-table shape; this item only changes implicit same-table `@nodeId` promotion. The `inputTypeHasLookupKey` recursive walker (`FieldBuilder.java:2521-2530`) is untouched.
- FK-target `@nodeId` (already lifts to a filter via `Resolved.FkTarget.DirectFk`). No behaviour change there.
- Adding a search-field classifier arm. The `QueryTableField` shape with composed filters already serves the search use case once this lift lands; `faceted-search.md` covers larger search-surface work.
- `@asConnection` + same-table `@nodeId` rejection at `FieldBuilder.java:403-407`. The validator there stays; pagination semantics on a PK-IN filter is incoherent for the same reasons it was for the lookup shape.
