---
id: R823
title: "The dev-executor fidelity test reads a mutating table twice and calls the difference a fidelity failure"
status: Backlog
bucket: bug
priority: 3
theme: testing
depends-on: []
created: 2026-08-24
last-updated: 2026-08-24
---

# The dev-executor fidelity test reads a mutating table twice and calls the difference a fidelity failure

> `DevExecuteExecutionTest.query_throughTheExecutor_matchesDirectInAppExecution` proves a real
> thing: the dev tool's JSON and the app's JSON are byte-equal, so the tool sees what the app
> sees. It proves it by running `{ films { filmId castMembers { … } } }` twice, once through each
> path, and comparing the two strings. The query is unfiltered, the two reads are sequential, and
> `graphitron-sakila-example` runs its test classes concurrently against one database. A writer
> class that inserts a film between the two reads makes the strings differ, and the test reports
> that as a fidelity failure.

---

## Observed failure

Four full-suite runs on one unchanged tree, same generated sources, same seeded database. Two
green. Two red, and not on the same assertions: this test both times, joined once by
`RoutineFieldExecutionTest.childRoutineThenHopsChainJoinsOutOfRoutineResultPerParent` and once by
its sibling `splitRoutineChildBatchesByBoundColumns`. Every failure has the same shape, a row the
assertion did not expect: three film rows (229, 230, 231) on one side of the fidelity comparison
and not the other, an extra `film_actor` row for an actor, an extra film id in a per-parent list.
All of them pass standalone.

The mechanism is confirmed rather than inferred: the same 848 tests run with
`junit.jupiter.execution.parallel.enabled=false` pass, 0 failures. Only concurrent class
execution produces these failures, which is what says the reads are racing a writer rather than
reading a wrong answer.

## Why this is the test's defect, not the suite's

The module states its own contract, in `src/test/resources/junit-platform.properties`:

> Writers scope their cleanup to rows they can name (a UUID marker, a title, an id); readers
> assert what their own query means rather than what a table holds. A test that asserts a row
> count over an unfiltered root field is asserting the second while meaning the first, and the
> symptom is a sibling failing on rows it never wrote.

This test asserts what the table holds, twice, and compares. It means neither of those things: it
means "the two execution paths agree". Concurrency is what exposes the gap, but the assertion was
never about the rows.

The failure is also the expensive kind to read. It renders as two large JSON strings whose
difference the reader has to eyeball, and the difference is genuinely there, so nothing about the
output suggests looking at a sibling test.

## Sketch

The fidelity claim needs both sides to read the same rows, which means the query must name them.
`filmById(film_id: [...])` over a fixed key set is the same fidelity statement over a stable
answer, and this class already has a `variables_bindThroughTheExecutor` case using exactly that
shape. Whether the nested `castMembers` selection needs preserving (it is what makes the JSON
non-trivial, so probably yes) is a detail of picking the keys.

Worth checking the sibling at the same time: `RoutineFieldExecutionTest`'s per-parent assertions
read `allActors` unfiltered and assert exact film-id lists, which is the same violation reached by
a different route.

A survey pass over the module's execution tier for other unfiltered-root readers would say whether
these two are the whole set or the two that happened to lose a race first. That census is the
useful deliverable even if it finds nothing else.

## Acceptance

* The fidelity assertion holds over rows the query names, so a concurrent insert cannot change
  either side.
* `RoutineFieldExecutionTest`'s per-parent cases likewise assert over rows they name.
* The census of unfiltered-root readers in the module's execution tier is recorded, whatever it
  finds, so the next occurrence is not re-derived from scratch.
