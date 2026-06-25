---
id: R375
title: "Empty list passed to a list-IN filter renders IN () = false, zeroing the query"
status: In Review
bucket: architecture
theme: nodeid
depends-on: []
created: 2026-06-25
last-updated: 2026-06-25
---

# Empty list passed to a list-IN filter renders IN () = false, zeroing the query

An external bug report (filed against 10.0.0-RC18, regression from 9.3.0) describes a list-typed `@nodeId` filter argument with no `@condition`: when the client sends an empty list `[]`, the generated WHERE clause emits `field.in(<empty>)`, which jOOQ renders as the constant predicate `false`. AND-ed into the WHERE clause, this zeroes the entire result set even though the client semantically meant "no filter". Apollo Client commonly serializes an empty selection as `[]`, so this hits real frontends as a silent data-loss bug. The reporter prescribes restoring the 9.3.0 guard `... && list.size() > 0 ? hasIds(...) : DSL.noCondition()`.

The reporter's diagnosis is anchored in the legacy (9.x) generator and does not map onto the rewrite. In the rewrite there is **no `hasIds` branch**: per the R50/e4b collapse, a list `@nodeId` filter without `@condition` lowers to a plain column-shaped `BodyParam.In` (a `ColumnField` with `NodeIdDecodeKeys.SkipMismatchedElement`), identical to a regular `[ID!]`/`[String!]` filter. The `.in(...)` is emitted once in `TypeConditionsGenerator` (the `BodyParam.In` / `BodyParam.RowIn` arms, ~lines 111-143): the non-null arm emits `condition = condition.and(table.<col>.in(<arg>))` with **a null guard but no empty guard**. So the symptom is general to every list-IN filter in the rewrite, not specific to `@nodeId`.

The current empty-list behaviour was **deliberate and test-locked**, not an accidental regression. `GraphQLQueryTest.films_filteredBySameTableNodeId_emptyListReturnsNoRows` asserts exactly this outcome, with a comment stating the legacy `empty → noCondition` short-circuit "is gone" on purpose, to align `@nodeId` filters with every other column-equality filter. R230 separately added the `null` guard for the sibling `.in(null) → false` footgun but intentionally left `[]` distinct from `null` (omitted/`null` = no filter → `noCondition`; `[]` = match the empty set → no rows).

## Decision

The G9 → G10 deviation was the wrong call: the production-frontend impact (Apollo serializing an empty selection as `[]` → silent zero-result data loss) outweighs the consistency argument. **Empty list → `noCondition` for fetch fields; lookup fields keep empty → empty.** This restores the G9 behaviour for fetch filters while preserving the rewrite's lookup semantics.

The fetch/lookup split is principled, not a per-field exception, and falls directly out of the model's existing `SqlGeneratingField` vs `LookupField` capability distinction:

- On a **fetch** field, a list `@nodeId`/`[ID!]` filter is an *optional narrowing predicate* AND-ed into a `WHERE` that already selects the full table. "No elements" is the identity of that conjunction (`DSL.noCondition()`), i.e. "narrow by nothing", the exact list-arity sibling of the `null`/omitted case R230 already drops to `noCondition`.
- On a **lookup** field, the input rows *are* the FROM-side of a `VALUES … JOIN` (`LookupValuesJoinEmitter`); the result set is defined as "target rows matching the supplied keys". "No keys" is not "no filter", it is an empty join domain, and 0 rows is the only coherent answer. There is no conjunction for empty to be the identity of.

## Plan

Single emission site on the fetch path: `TypeConditionsGenerator.buildConditionMethod`, the `BodyParam.In` and `BodyParam.RowIn` arms (currently `TypeConditionsGenerator.java:111-143`). Add an emptiness guard so an empty list contributes nothing:

- `BodyParam.In` non-null: `condition = condition.and(table.<col>.in(<arg>))` → `if (!<arg>.isEmpty()) condition = condition.and(table.<col>.in(<arg>))`.
- `BodyParam.In` nullable: `if (<arg> != null)` → `if (<arg> != null && !<arg>.isEmpty())`.
- `BodyParam.RowIn` non-null and nullable: the same guard over the row-list parameter. The empty `RowIn` case is live, not dead defensive code: a client sending `[]` for a composite-key `@nodeId` filter decodes to an empty row list (nothing to drop, just empty), so it reaches the arm.

This one change covers regular `[ID!]`/`[String!]` list filters **and** collapsed `@nodeId` list filters (R50/e4b lowered the latter onto the same `BodyParam.In`/`RowIn`), and it fixes the connection data **and** count paths transitively, since both compose the same generated condition method via `QueryConditions.<field>Condition` → `FkTargetConditionEmitter.emitTerm` → the entity condition method. The scalar `BodyParam.Eq`/`RowEq` arms are unchanged (a scalar has no empty state). Lookup fields need no change: `TypeConditionsGenerator` already skips `LookupField` (line 63), and `LookupValuesJoinEmitter` already short-circuits an empty input list to `dsl.newResult()` (no SQL round-trip).

### Mechanism: literal guard, not a model slot

The guard is emitted as a literal `if (!arg.isEmpty())` in `TypeConditionsGenerator`, not lifted into a sealed `EmptyBehavior` field on `BodyParam`. Rationale: within `TypeConditionsGenerator` *every* `In`/`RowIn` is a fetch-path narrowing predicate (lookup is excluded upstream at line 63), so "drop on empty" is a constant invariant of this emitter, not a predicate evaluated over model data in two places. The Generation-thinking trigger ("two consumers evaluate the same predicate over a model field → lift the fork into the model") does not fire: `LookupValuesJoinEmitter` does not evaluate an empty-behaviour predicate, it has its own intrinsic shape. A sealed `EmptyBehavior` carrying a single value across all current and architecturally-foreseeable consumers of the fetch condition emitter would be an untyped distinction with no second case, i.e. the over-engineering the sub-taxonomy rule warns against. Should a future DML consumer (mutation `WHERE`, generated DELETE/UPDATE predicate) ever want match-empty-set over a composed condition method, that is the point to lift the decision into the model; it does not exist today.

## Tests

The invariant must be pinned above the execution tier, since `.in(<empty>) → false` (like `.in(null) → false`) is only *observable* against real Postgres but should not *depend* on it to catch a regression:

- **Unit / pipeline tier** (`TypeConditionsGeneratorTest`): assert the emitted condition-method body carries the empty guard for both the `In` and `RowIn` arms (the existing `body.contains("table.FILM_ID.in(ids)")` substring assertions survive the change; add explicit `!ids.isEmpty()` assertions so the guard cannot silently regress).
- **Execution tier** (`GraphQLQueryTest`, the only tier that observes jOOQ's `IN () → false` rendering): invert `films_filteredBySameTableNodeId_emptyListReturnsNoRows` to expect the unfiltered baseline (all 5 films) and rewrite its comment to record the decision; add a regression test matching the bug report exactly (an `@asConnection` query with a list `@nodeId` filter, empty list → unfiltered nodes, and the count path → full total); and add/confirm a lookup-field execution test that an empty input list still yields 0 rows (`inlineLookupTableField_emptyInput_returnsEmpty` already covers this, line ~1459) so the fetch/lookup divergence is named by live tests on both sides.

## Out of scope

`@nodeId` filters carrying `@condition` (routed through hand-written condition methods that guard `isEmpty()` themselves) are unaffected and unchanged. No model-shape or classifier change; no `EmptyBehavior` model slot (see rationale above).

## Implementation notes (shipped)

Landed as specced: the four-arm empty guard in `TypeConditionsGenerator.buildConditionMethod`, the pipeline-tier `!ids.isEmpty()` assertions in `TypeConditionsGeneratorTest` (plus a new `inFilter_nonNullList_emitsEmptyGuardWithoutNullCheck` pinning the non-null `In` arm the nodeId helpers don't reach), the inverted `films_filteredBySameTableNodeId_emptyListReturnsUnfilteredBaseline`, the connection regression test `filmsConnectionByOptionalIds_idsEmptyList_paginatesFullTableAndCountsAll` (nodes + `totalCount` both unfiltered), and the lookup-side divergence comment on `inlineLookupTableField_emptyInput_returnsEmpty`.

One scope item surfaced during implementation that the spec did not name: `filmsByNodeIdArg` (argument-level same-table `@nodeId`, schema line ~191) is a **fetch** field, not a lookup field. R106 lifted the argument-level same-table `@nodeId` onto the `WHERE film_id IN (...)` rail, but two execution tests (`filmsByNodeIdArg_emptyList_returnsNoRows`, `filmsByNodeIdArg_allMalformedIds_returnsNoRows`) still carried pre-R106 lookup wording (referencing the `dsl.newResult()` short-circuit) and asserted the empty set. Those assertions only passed because `IN () = false` coincidentally also zeroed the query; both are squarely within the spec's "covers collapsed `@nodeId` list filters" scope and are now inverted to the unfiltered baseline.

The all-malformed case is the one genuine semantic the spec left implicit: `SkipMismatchedElement` drops every malformed id, so an all-garbage filter reaches the `BodyParam.In` arm as an empty `List<Integer>`, indistinguishable from a literal `[]`. Per §Mechanism (literal guard, no provenance threading) and the wire-boundary principle (decode at the boundary, downstream sees tuples), the condition method cannot and must not distinguish the two; both narrow by nothing and return the unfiltered baseline. Distinguishing them would require carrying decode provenance across the adapter/composer boundary, which the design explicitly rejects.
