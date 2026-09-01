---
id: R905
title: "A polymorphic-connection execution test asserts over an unfiltered root field and races sibling film inserts"
status: Backlog
bucket: bug
priority: 3
theme: testing
depends-on: []
created: 2026-09-01
last-updated: 2026-09-01
---

# A polymorphic-connection execution test asserts over an unfiltered root field and races sibling film inserts

`ConnectionSharedResultKeyProjectionTest.polymorphicConnection_divergentNestedSelections_bothSidesResolve`
fails intermittently in the verification build, on rows it never wrote. It queries
`searchConnection(first: 100)`, an unfiltered root field over the multi-table `Searchable` union,
and asserts that every returned `Film` has `summary.releaseYear` equal to 2006, on the stated
premise that "all seed films are 2006". The premise is about the seed, but the assertion is over
whatever the `film` table holds at that moment.

`graphitron-sakila-example` runs test classes concurrently (`parallelism=4`), and on the
`local-db` path every class shares one PostgreSQL instance. `DmlBulkMutationsExecutionTest`'s
`insertFilm` helpers set `title` and `language_id` and never `release_year`, so every film they
leave behind, or hold uncommitted mid-test, carries `release_year = NULL`. When those rows are
visible to the connection query, the assertion sees extra `Film` rows with a null `releaseYear`
and fails with `expected: 2006 but was: null`. Observed as a build failure with seven film
summaries, five at 2006 and two null, on a tree whose change touched launcher-method naming and
could not produce a film row at all. That same tree then built green on a rerun, trunk without the
commit built green, and the identical change built green before the rebase, so the failure follows
the concurrency and not the diff.

This is the anti-pattern the module's own `junit-platform.properties` names in prose: a reader
that "asserts what a table holds" while meaning "rows it wrote", whose symptom is "a sibling
failing on rows it never wrote". The fix is to make the reader's query mean its own rows, not to
serialise the classes: the assertion wants the seed films, so it should either scope the query to
rows it can name or assert only over films whose `releaseYear` is non-null by construction. Worth
checking the sibling cases in the same class for the same shape while here, and worth checking
whether other readers in the module assert over unfiltered root fields the DML classes write to.

Costly because it is indistinguishable, at the point of failure, from a real regression: it
surfaced during unrelated work and cost a control build on trunk plus a repro build before the
change under test could be cleared.
