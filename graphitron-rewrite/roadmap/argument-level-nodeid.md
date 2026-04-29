---
id: R40
title: "Argument-level `@nodeId` support"
status: Backlog
bucket: architecture
priority: 2
theme: nodeid
depends-on: [lift-nodeid-out-of-model]
---

# Argument-level `@nodeId` support

`@nodeId` is declared on `ARGUMENT_DEFINITION` in [`directives.graphqls`](../graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls) but `FieldBuilder.classifyArgument` (line 754) never inspects it. An explicit `@nodeId(typeName: T)` on a `[ID!]` arg falls through to the column-binding fallthrough at line 826 and surfaces as `column 'X' could not be resolved in table 'Y'`. Reproducer from opptak: `kompetanseregelverkGittIdV2(ider: [ID!]! @nodeId(typeName: "Kompetanseregelverk")): [Kompetanseregelverk!] @asConnection`.

## Assumes R50 has landed

R50 (`lift-nodeid-out-of-model`) introduces `CallSiteExtraction.NodeIdDecodeKeys(typeId)`, the column-shaped composite-key carriers, and the row-IN body emission. R40 builds on that foundation; do not start R40 until R50 is Done. Without R50, R40 either has to ship a wire-shape variant (which R50 then deletes) or duplicate R50's foundation work, both of which the R50 framing already rejected.

## Scope

Same-table only: `@nodeId(typeName: T)` on a top-level argument where `T` resolves to the field's own backing table. Produces a primary-key IN predicate (single-column or composite-row, depending on the target's `nodeKeyColumns`). FK-target args (`T` resolves to a different table) are a separate Backlog item; under R50's framing the shape is "classify as a `ColumnReferenceField`-style argument with an FK path plus `NodeIdDecodeKeys` extraction", trivially small once R50's column carriers exist.

## Work

The whole change is a classifier extension in `FieldBuilder.classifyArgument`, sitting between the input-type arms (today lines 783-811) and the column-binding fallthrough (today line 826):

1. If the argument is `ID` or `[ID!]` and carries `@nodeId(typeName: T)`:
   - Resolve `T` against the schema. Reject non-existent types (UnclassifiedArg with candidate hint), non-`@table` object types (UnclassifiedArg), and FK-target `T` (out of scope; UnclassifiedArg pointing at the FK-target follow-on item).
   - For same-table `T`: resolve `(typeId, keyColumns)` via R50's existing fallback (catalog `nodeIdMetadata` → post-first-pass `ctx.types` → SDL-only `@node` with `catalog.findPkColumns`). Reject if all three miss.
   - Build a column-shaped `ArgumentRef` (R50's column carrier) over the resolved key columns, with `extraction = CallSiteExtraction.NodeIdDecodeKeys(typeId)`.
2. The existing scalar synthesized-route block (today `NodeIdArg` at lines 813-824) is gone post-R50; the synthesized route already routes through R50's column carrier with the same extraction.

Projection, body emission, lookup mapping, validator coverage, and call-site extraction are all R50's. R40 adds no new variants, no new emitter arms, no new validator arms.

## Spec-day-one questions

- **`@asConnection` composition.** `GraphitronSchemaBuilder.rewriteCarrierField` appends `first` / `after` before classifyArguments runs, so `isPaginationArg` claims those names first. Confirm filter + `PaginationSpec` compose (filter narrows, seek paginates within) with an execution test on the opptak reproducer.
- **`@lookupKey` policy.** `[ID!] @nodeId @lookupKey` rejects at classify time, matching `buildLookupBindings`'s rejection of `@lookupKey` on list input fields and the `@asConnection` + `@lookupKey` rejection at lines 331/346. (Single-id `@lookupKey` is the lookup-mapping path and stays via R50's `LookupMapping`-over-PK collapse, not R40's surface.)
- **`@condition`.** With column-shaped arguments, `@condition` co-existence is the same as on any other column-bound arg; no special case needed.
- **Scalar same-table case.** `ID @nodeId(typeName: SameTable)` (non-list, declared) routes through the same classifier arm; emission degenerates to single-column `c.eq(decodedKey)`. Pin whether R40 enables it or leaves it to a sibling line item; the only marginal cost is the extra test case.

## Test surface

`nodeidfixture` catalog (Sakila lacks `__NODE_KEY_COLUMNS`). Pipeline-tier classification: composite-PK same-table, single-PK same-table, target-not-`@node` Unresolved, target-non-`@table` Unresolved, target-different-table Unresolved (FK-target follow-on placeholder), missing-`typeName` Unresolved. Execution-tier against PostgreSQL on the opptak reproducer shape: filter-by-ids returns exactly those rows; empty list passes through to no-condition; combined with `first` / `after` returns the paginated subset of the filtered set; `QUERY_COUNT == 1`. Generated SQL inspection via `ExecuteListener`: predicate is `pkCol IN (...)` (single-PK) or `(col1, col2) IN (...)` (composite), with no `hasIds` call surviving anywhere.
