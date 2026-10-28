---
id: R1121
title: "execution tests assert only what they control, on a database other tests write to"
status: Backlog
bucket: bug
priority: 3
theme: testing
depends-on: []
created: 2026-10-28
last-updated: 2026-10-28
---

# execution tests assert only what they control, on a database other tests write to

## Goal

The execution tier stops failing on a green tree: every test in `graphitron-sakila-example` asserts only on rows and orderings it controls, so it passes whatever else is writing to the shared database or however the database orders what the query leaves unordered. The database is built for many writers at once, and so are the tests: a flaky test is a badly designed test, not a reason to give each class a pristine database.

## Relation to R863

`execution-tier-shared-db-cross-talk` (R863) removes the cross-talk by giving each class its own database. This item takes the other position: the database is shared by design, so a test must not depend on it being private. The two cannot both be the plan; whichever is kept, the items need reconciling before either leaves Spec or Backlog.

## Provenance

Trunk CI on `claude/graphitron-rewrite` failed on roadmap-only commits, with neighbouring runs green. Two mechanisms showed up; the first is a test asserting beyond what it controls:

* *Reading the shared table whole.* `GraphQLQueryTest.filmsConnection_emptyBeforeCursor_returnsLastPage` runs `last: 2` twice, with and without `before: ""`, and compares the two results, then asserts `hasPreviousPage`/`hasNextPage` for the table as a whole. A film written by another class between the two reads, or while the last page is being read, changes the page. The same shape was already fixed once for a helper in `1c6d9ea`, which narrowed it to the seeded rows.
* *The tie order nobody specified is not this item's.* `SortKeyOrderExecutionTest.pathKey_plainListRoot_ordersByTheRelatedColumn` failed with two films that tie on the sort key swapped. Its expectation was right: generated SQL should end every order in a tiebreaker, and a plain list root does not. That is a generator fault and is filed as `every-generated-ordering-is-total`; the test needs no change.

## Plan

1. *Inventory.* List the execution-tier assertions that read a table another class writes (`film`, `rental`, `inventory`, and whatever else those classes insert into) without narrowing to rows the test seeded, and any assertion on an order that is not total, which is a generator report rather than a test change. Start from the two above and from every test class that inserts or deletes in a shared table; `OptionalNodeIdProjectionExecutionTest` and `GraphQLQueryTest` already narrow to `SEEDED_FILM_IDS` and show the idiom.
2. *Fix each at the test.* The rule is that a test asserts only what it has control of. Where that is the rows, the test seeds its own (ids or titles unique to it) and narrows to them before comparing. Where it is order, graphitron guarantees a total order (see `every-generated-ordering-is-total`), so the test asserts it, and a test never compensates for a missing guarantee. A paging test that must read the end of a shared table asserts on its own seeded rows, not on `hasNextPage` for the table. No test is made safe by serialising the classes, by a per-class database or by deleting other classes' rows.
3. *Keep it from returning.* Add the rule to `docs/architecture/how-to/testing.adoc` under the execution tier (the shared database is the contract, so a test judges its own rows and the order the query specifies), and give the shared seeded-row narrowing helpers one home where several classes now carry their own copy. Whether a mechanical guard is worth building is left to Spec; a runtime check cannot see an unspecified order, so prefer the review rule unless the inventory shows a cheap lexical tell.

## Tests

Each fixed test is its own evidence. For the named case, an added concurrent writer inserting films with the same language and inventory while the test runs must leave it green.
