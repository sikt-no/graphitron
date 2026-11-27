---
id: R1212
title: "A multi-table child list returns each parent's rows in no defined order"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-11-27
last-updated: 2026-11-27
---

# A multi-table child list returns each parent's rows in no defined order

## Goal

A child field (one with a parent row) that returns a list of a multi-table interface or union
gives each parent its rows in the order the manual promises: on the participants' primary-key
columns, position by position, then on the participant's type name. That is the order every other
multi-table read already delivers when nothing is declared; today this one shape delivers no order
at all.

## Context

The list arm leaves the order to the database: `MultiTablePolymorphicEmitter.buildBatchedListRowsMethod` builds the stage-1
`UNION ALL` of participant branches joined to `parentInput` (the `VALUES` table of the batch's
parent keys, each tagged with its position `__idx__`) and fetches it with no `ORDER BY`, and the
scatter into per-parent buckets keeps whatever order the rows arrived in. So `Address.occupants`
in the sakila example returns its customers and staff in an order PostgreSQL does not guarantee,
and which can change with the plan.

The other multi-table shapes order:

* A root list or connection orders on the lowered `PolymorphicOrdering`, or on the key slots
  when nothing is declared.
* The inline single-valued child passes `keyOrderReferences(pkArity, false)` into
  `buildStage1Block`.
* The batched child connection orders on `pk0, __typename` inside its
  `ROW_NUMBER() OVER (PARTITION BY __idx__ ...)` envelope; that it reads only the first key column
  is R1074, a sibling of this item.

The batched list arm is the one shape with no order at all. It contradicts
`docs/manual/how-to/sort-results.adoc` § Sort across polymorphism, whose opening paragraph states
the key-then-typename order for every multi-table field, child fields included.

Declared orderings are out of scope. `@defaultOrder` and `@orderBy` on a child multi-table field
are refused at build time (`ORDERING_ACROSS_PARTICIPANTS`), as the same manual section says, and
that refusal stays. This item is only about the implicit order a child list delivers when nothing
is declared.

The defect has gone unnoticed because the execution tests over child multi-table lists
(`GraphQLQueryTest`'s `addressOccupants_*` cases) compare with `containsExactlyInAnyOrder` or group
by type, and `MultiTableOrderingExecutionTest` covers root fields only. PostgreSQL usually returns
`UNION ALL` branches in sequence, which also hides the problem when the output is read by eye.

## Sketch

Order the stage-1 select on `__idx__`, then the key slots `__pk0__ .. __pkN__` ascending, then
`__typename`, matching the root list's fallback. The list arm already projects the full key arity,
so it does not share R1074's truncation. Pin the order with an execution test over a parent whose
occupants mix participants and whose key order differs from branch order, asserting
`containsExactly`, so the test would fail against today's emitter.
