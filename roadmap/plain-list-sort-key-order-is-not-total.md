---
id: R1128
title: "A plain list ordered by a sort key has no tiebreaker, so rows that tie come back in an undetermined order"
status: Backlog
bucket: bug
priority: 6
theme: codegen-correctness
depends-on: []
created: 2026-10-29
last-updated: 2026-10-29
---

# A plain list ordered by a sort key has no tiebreaker, so rows that tie come back in an undetermined order

## Goal

A plain (non-connection) list ordered by an `@order` or `@defaultOrder` sort key returns rows in a determined order, or the tests that assert such an order stop claiming one the query does not guarantee. Today the list's `ORDER BY` is only the sort key, so rows that tie on it come back in whatever order PostgreSQL produces, and `SortKeyOrderExecutionTest.pathKey_plainListRoot_ordersByTheRelatedColumn` flakes on it.

## The failure

Seen once on 2026-10-29 in a full clean reactor install on a heavily loaded machine (`graphitron-sakila-example`, execution tier). The query `{ filmsByOriginalLanguageList { filmId } }` returned `[5, 3, 2, 1, 4]` where the test expected `ORIGINAL_ASC`, `[5, 2, 3, 1, 4]`. Three isolated reruns of the class passed, 13 tests each, so the failure does not reproduce on demand.

The cause is in the fixture: `init.sql` gives films 2 and 3 the same original language, so they tie on the `path:` sort key. The connection roots do not have this exposure, because connections append the table's row key after the listed order. A plain list does not: plain lists are deliberately outside that completion.

The inline child list (`pathKey_inlineChildList_ordersInTheSubselect`) is a plain list over the same fixture and asserts the same `ORIGINAL_ASC`, so it has the same exposure and has not failed yet.

## The decision

Two readings are defensible, and the item exists to pick one.

* *The test is wrong.* A plain list never promised a total order, so the assertion should accept either order within a tie (compare the sort keys in order, and compare the tied films as a set), or the fixture should give films 2 and 3 distinct original languages. This keeps the generator as is and leaves a consumer to add their own tiebreaker.
* *The generator should complete the list.* Append the table's row key to a plain list's order the way connections get it, in the direction of the last listed column, so that every list is total and its output is reproducible. This costs an extra `ORDER BY` column on every list, and the earlier decision to leave plain lists uncompleted has to be revisited, which is the larger question.

Recommendation: settle the generator question first, since the test change follows from it either way. If plain lists stay uncompleted, tighten the two assertions to the set-within-tie form and say so in the test.

## Tests

Whichever reading wins, name the evidence that closes it: for the generator reading, an execution walk over the tied fixture that fails with the completion disabled; for the test reading, the two assertions pass under both tie orders (a `@RepeatedTest` is not evidence, since the flake does not reproduce on demand).
