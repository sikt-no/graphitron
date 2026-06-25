---
id: R375
title: "Empty list passed to a list-IN filter renders IN () = false, zeroing the query"
status: Backlog
bucket: architecture
theme: nodeid
depends-on: []
created: 2026-06-25
last-updated: 2026-06-25
---

# Empty list passed to a list-IN filter renders IN () = false, zeroing the query

An external bug report (filed against 10.0.0-RC18, regression from 9.3.0) describes a list-typed `@nodeId` filter argument with no `@condition`: when the client sends an empty list `[]`, the generated WHERE clause emits `field.in(<empty>)`, which jOOQ renders as the constant predicate `false`. AND-ed into the WHERE clause, this zeroes the entire result set even though the client semantically meant "no filter". Apollo Client commonly serializes an empty selection as `[]`, so this hits real frontends as a silent data-loss bug. The reporter prescribes restoring the 9.3.0 guard `... && list.size() > 0 ? hasIds(...) : DSL.noCondition()`.

The reporter's diagnosis is anchored in the legacy (9.x) generator and does not map onto the rewrite. In the rewrite there is **no `hasIds` branch**: per the R50/e4b collapse, a list `@nodeId` filter without `@condition` lowers to a plain column-shaped `BodyParam.In` (a `ColumnField` with `NodeIdDecodeKeys.SkipMismatchedElement`), identical to a regular `[ID!]`/`[String!]` filter. The `.in(...)` is emitted once in `TypeConditionsGenerator` (the `BodyParam.In` / `BodyParam.RowIn` arms, ~lines 111-143): the non-null arm emits `condition = condition.and(table.<col>.in(<arg>))` with **a null guard but no empty guard**. So the symptom is general to every list-IN filter in the rewrite, not specific to `@nodeId`.

The current empty-list behaviour is **deliberate and test-locked**, not an accidental regression. `GraphQLQueryTest.films_filteredBySameTableNodeId_emptyListReturnsNoRows` asserts exactly this outcome, with a comment stating the legacy `empty → noCondition` short-circuit "is gone" on purpose, to align `@nodeId` filters with every other column-equality filter. R230 separately added the `null` guard for the sibling `.in(null) → false` footgun but intentionally left `[]` distinct from `null` (omitted/`null` = no filter → `noCondition`; `[]` = match the empty set → no rows). The change this report asks for is therefore a design fork (do we collapse `[]` semantics back to "no filter", and if so for **all** list-IN filters or only `@nodeId`?), it would break the existing test and contradict an explicit prior decision, and it would need a reviewer-gated Spec. Captured here for triage rather than implemented unilaterally; awaiting a decision on whether `[]` should mean "no filter" or "match nothing".
