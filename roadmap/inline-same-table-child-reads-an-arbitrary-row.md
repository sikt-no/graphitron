---
id: R1045
title: "An inline child bound to its parent's own table with no @reference reads the whole table, so a single-cardinality one returns an arbitrary row"
status: Backlog
bucket: bug
priority: 4
theme: classification-model
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# An inline child bound to its parent's own table with no @reference reads the whole table, so a single-cardinality one returns an arbitrary row

## Goal

A child field whose return type is bound to the same table as its parent, with no `@reference` and no `@splitQuery`, returns rows that answer a question the author can state: either the parent's own row, or a documented read of the table narrowed by the field's arguments, with a single-cardinality field that cannot be narrowed rejected rather than answered with an arbitrary row. Today such a field classifies as an inline `TableField` with an empty join path (no foreign-key inference runs between a table and itself) and renders as an uncorrelated multiset over the whole table (`ProjectionUnitRenderer`'s standalone arm, `WHERE DSL.noCondition()`), so a single-cardinality field with no `@condition` or `@lookupKey` gets `limit(1)` over every row: an arbitrary row of the table, the same for every parent, with no build error. Graphitron 9 authors porting a same-row field (`personProfil: PersonProfil` where both types are `@table(name: "PERSON")`) land on exactly this shape when they drop `@splitQuery`.

## Evidence

- Found while speccing R1043 (the `@splitQuery` twin of this shape, which rejects). The one fixture exercising the standalone arm, `NodeIdReferenceFilterPipelineTest.inlineReferenceField_conditionOnlyFilter_emptyJoinPath_standaloneSubquery` (`Bar.related(filter: BarFilter): [Bar!]` over a `@condition` filter), asserts only that rendering does not throw; no test pins which rows the arm returns, and no user-manual page says what a same-table child without `@reference` means.
- `ProjectionUnitRenderer`'s comment on the arm calls it "a conditions-only correlated subquery"; it is uncorrelated.

## Questions for the Spec

- Whether the unnarrowed shape (no `@condition`, no `@lookupKey`) should reject at every cardinality, or only at single cardinality, and what the inline same-row spelling is (a nesting type without `@table`, or a `{condition:}` path) that the rejection steers to.
- Whether the narrowed list shape (`Bar.related` above) is a capability worth documenting as such, in which case R1043's "drop `@splitQuery`" remedy points at it.
