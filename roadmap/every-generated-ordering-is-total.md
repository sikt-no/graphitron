---
id: R1122
title: "every ordering graphitron generates is deterministic and ends in a tiebreaker"
status: Spec
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-28
last-updated: 2026-10-29
---

# every ordering graphitron generates is deterministic and ends in a tiebreaker

## Goal

Every ordering graphitron generates is total: two runs of the same query over the same rows return them in the same order, because a declared order that leaves rows tied is completed with the table's row key. Today that holds only for a single-table connection; a plain list root, an inline child list and a `@splitQuery` child list sort on the listed columns and leave tied rows to whatever order the database happens to produce, which can differ between executions and between PostgreSQL versions. When this lands, a consumer and a test can rely on a declared list order the way they rely on connection order. A list that declares no order stays unordered: this item completes an order, it does not invent one.

## Provenance

`SortKeyOrderExecutionTest.pathKey_plainListRoot_ordersByTheRelatedColumn` failed on trunk CI with the two films that tie on the sort key swapped (`[5, 3, 2, 1, 4]` against `[5, 2, 3, 1, 4]`). The test was right to expect the tiebreaker: the connection variants of the same order append `film_id` and pass, and the plain list is the same order without it. The fault is in the generated SQL, not in the test. It recurred on 2026-10-29 in a full clean reactor install on a heavily loaded machine and passed in three isolated reruns, so it does not reproduce on demand. `pathKey_inlineChildList_ordersInTheSubselect` runs the same fixture and the same expected order through an inline child list, so it has the same exposure and has not failed yet.

## Plan

1. *Where the gap is.* `OrderByResolver.resolve` builds the table's `RowIdentity` only when `singleTable && ctx.buildWrapper(fieldDef) instanceof FieldWrapper.Connection`; everything that completes an order (`complete`, `completed`, the `@orderBy` single and list compositions, `SequenceCompletion.AppendRowKey`) keys off that value. Drop the `Connection` conjunct so the identity is decided for every single-table list field, and the existing completion applies at each site the order renders: root list, inline child list, `@splitQuery` child list, and the `@orderBy` helper compositions. No new completion path is added.
2. *The key's direction.* A list has no cursor, but the key takes the direction of the last listed column exactly as on a connection, because `complete` is reused unchanged and a runtime `direction:` then flips the key with the rest. An ascending-only rule for lists would be a second branch through `complete` for no consumer-visible gain.
3. *An undeclared order stays undeclared.* `complete` returns early on an empty entry list, so a list with no `@defaultOrder` and no `@orderBy` is left alone. Ordering every such list by its row key would change the SQL of every unordered list in every consumer; that is a separate decision and not this item's.
4. *The multi-table sites.* `MultiTablePolymorphicEmitter` already adds the participant key and `__typename`, and `resolve(..., singleTable = false)` stays uncompleted by design. The Spec's remaining audit is the other `orderBy` emissions in `TypeFetcherGenerator` (routine chains, DML `returning` lists, the keyed lookup in `SelectMethodBody`): each is checked against the rule, and the ones that stay out are listed here with a reason before the item moves to Ready.
5. *Keyless tables.* A table with no primary key, `@node` key or all-`NOT NULL` unique key cannot be completed. Today `Resolved.Ok(spec, true)` raises the lint `connection-order-not-unique`, whose name authors put in `<disabledRules>`, so it is a public setting: leave it alone for connections and add a sibling rule for list sites (a warning, not a build failure, since a list has no cursor that breaks). The sibling's name and its disable path are settled here before Ready.
6. *Cost.* An ordered list gains one trailing `ORDER BY` column. Where the sort column is indexed but the key is not part of that index, the database may have to sort rows it could previously read in index order. Accept this, state it in the breaking-change note. An author who lists the key themselves changes nothing, as on a connection.
7. *Say it where it is promised.* `docs/manual/how-to/sort-results.adoc` (`stable-cursors-and-tie-breakers`) states the rule for connections only; extend it to lists, and add the breaking-change note that a list whose declared order did not identify a row now has a defined tie order which can differ from what a consumer saw before. `docs/manual/explanation/design-decisions.adoc` and `batching-model.adoc` mention connection ordering and are checked for the same claim.

## Tests

* *Pipeline, one case per site in step 1,* over a table whose order does not identify a row, asserting the resolved `OrderBySpec` ends in the row key, the way `ConnectionOrderCompletionCase` pins connections. These assert the model, not the emitted source: no generated-body string assertions.
* *Execution, one walk per site in step 1,* over the two films that tie on the original-language sort key: root list, inline child list, `@splitQuery` child list, and both `@orderBy` compositions at a list. Each walk is demonstrated to fail with the completion disabled for that site, as `ConnectionTiebreakerExecutionTest`'s walks were. `SortKeyOrderExecutionTest.pathKey_plainListRoot_ordersByTheRelatedColumn` and `pathKey_inlineChildList_ordersInTheSubselect` are the first two and need no change.
* *Keyless:* a pipeline case that the sibling lint fires on a keyless list and does not fire on a keyed one.
* A repeated-run loop is not evidence: the flake does not reproduce on demand, which is why the walks assert a specific tie order rather than stability across runs.
