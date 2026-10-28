---
id: R1122
title: "every ordering graphitron generates is deterministic and ends in a tiebreaker"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-28
last-updated: 2026-10-28
---

# every ordering graphitron generates is deterministic and ends in a tiebreaker

## Goal

Every ordering graphitron generates is total: two runs of the same query over the same rows return them in the same order, because an order that leaves rows tied is completed with the table's row key. Today that holds only for a single-table connection; a plain list root, an inline child list and a `@splitQuery` child list sort on the listed columns and leave tied rows to whatever order the database happens to produce, which can differ between executions and between PostgreSQL versions. When this lands, a consumer and a test can rely on list order the way they rely on connection order.

## Provenance

`SortKeyOrderExecutionTest.pathKey_plainListRoot_ordersByTheRelatedColumn` failed on trunk CI with the two films that tie on the sort key swapped (`[5, 3, 2, 1, 4]` against `[5, 2, 3, 1, 4]`). The test was right to expect the tiebreaker: the connection variants of the same order append `film_id` and pass, and the plain list is the same order without it. The fault is in the generated SQL, not in the test.

## Plan

1. *Where the gap is.* `OrderByResolver` completes an order with the row key (`OrderBySpec.SequenceCompletion.AppendRowKey`) only when the field is a single-table connection (`isConnectionField`, the `FieldWrapper.Connection` check). Lift that restriction to every single-table list site the order renders at: root list, inline child list, `@splitQuery` child list, and the `@orderBy` helper compositions. A connection keeps appending the key in the direction of the last listed column, since its cursor carries it; a plain list has no cursor, so the key's direction is the Spec's to settle (ascending is the natural default).
2. *The multi-table sites.* `MultiTablePolymorphicEmitter` already adds the participant key and `__typename`; the Spec checks every other ordering the generator emits (routine chains, DML `returning` lists, and any `ORDER BY` a generator writes) against the same rule and lists the ones that stay out, with reasons.
3. *Keyless tables.* A table with no primary key, `@node` key or all-`NOT NULL` unique key cannot be completed. The connection path already lints it (`connection-order-not-unique`); decide whether a list site lints, fails or accepts.
4. *Say it where it is promised.* State the rule in the user manual's ordering chapter next to the connection behaviour, and extend the breaking-change note the connection change carried to the list sites: a list whose order did not identify a row now has a defined tie order, which can differ from what a consumer saw before.

## Tests

For each site in step 1, an execution case over two rows that tie on the sort key, asserting the key-ordered result, and a pipeline case that the emitted order ends in the row key. The existing `SortKeyOrderExecutionTest` plain-list case is the first of them and needs no change.
