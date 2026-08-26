---
id: R843
title: "Execution-tier test classes mutate shared Sakila rows other classes assert on"
status: Backlog
bucket: cleanup
priority: 5
theme: testing
depends-on: []
created: 2026-08-26
last-updated: 2026-08-26
---

# Execution-tier test classes mutate shared Sakila rows other classes assert on

The `graphitron-sakila-example` module runs its test classes concurrently (`junit-platform.properties`
sets `mode.classes.default=concurrent` at a fixed parallelism of 4) against one shared PostgreSQL
database. Some classes write rows into that database and clean up afterwards; others assert on the
exact contents of the same tables. Nothing keeps the two apart, so a write and an assertion can
overlap and the reactor build fails on a test that is correct.

The observed instance, hit independently by two sessions on unrelated work:
`GraphQLQueryTest.splitTableField_bridgingConditionJoin_returnsActorsPerFilm` asserts that film 3 has
exactly one actor. `DmlBulkMutationsExecutionTest` seeds real `film_actor` rows through
`seedFilmActor(2, 3)`, `seedFilmActor(3, 3)`, `seedFilmActor(1, 4)` and `seedFilmActor(3, 4)`, removing
them in a `finally`. During that seed-to-cleanup window film 3 has a second actor, and the query test's
exact-content assertion fails, expecting `[1]` and getting `[1, 3]`. Both classes pass alone; both
pass when the whole class runs; the failure needs the interleaving.

This is a test-isolation defect, not a flake, and the distinction matters because the failure looks
exactly like one: it is rare, it does not reproduce on re-run, and the natural response is to re-run
the build. Every session that meets it pays to re-derive the same diagnosis. It also erodes the
verification build's value directly, since a red result that is not the tree's fault teaches an
implementer to discount red results.

Three shapes a fix could take, none obviously right, which is why this is filed rather than patched:

- Move the DML tests off the film ids the read-side tests assert on, by seeding their own films
  rather than borrowing low-numbered Sakila ones. Cheapest, and it holds only until the next test
  picks the same rows.
- Scope each read-side assertion to rows the test itself owns, so no assertion depends on a table's
  global contents. Most robust, and the largest change to existing tests.
- Keep the mutating classes out of the same parallel slot as the asserting ones, through a JUnit
  resource lock on the tables they share. Narrowest, and it makes the constraint explicit in the
  code rather than implicit in the row numbering, at the cost of some parallelism.

Sizing the blast radius is the first task: `film_actor` is the pair this instance found, and the
question the Spec has to answer is how many other write/assert pairs exist across the module's
execution-tier classes.

