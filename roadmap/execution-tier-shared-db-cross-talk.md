---
id: R863
title: "Execution-tier tests cross-talk through the shared local-db instance, and trunk CI is red on it"
status: Backlog
bucket: bug
priority: 1
theme: testing
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# Execution-tier tests cross-talk through the shared local-db instance, and trunk CI is red on it

Trunk CI fails most runs, and every failure sampled so far is one test class in
`graphitron-sakila-example` reading rows another test class wrote. Of the 40 workflow runs on
`claude/graphitron-rewrite` on 2026-08-27, 4 were green, 28 red and 8 still running. Every red one
sampled failed in that module, on an execution-tier assertion, with no code in the commit that could
plausibly cause it: several of the failing commits changed only roadmap metadata.

This is a test-isolation bug, not a generator bug. The generated code is correct in every case below;
the assertions are reading a database another test is mid-way through mutating.

## Why the shared database exists, and what it removes

Each execution-tier class opens its own PostgreSQL container in `@BeforeAll` when no
`test.db.url` is set. The `local-db` profile sets one, so every class instead shares a single
instance. That profile is what CI uses, and what the web sandbox uses (no Docker there), so the
per-class isolation the suite is written against is exactly what the profile removes.

Beside that, `graphitron-sakila-example/src/test/resources/junit-platform.properties` runs four test
classes concurrently. So on CI, four classes at a time read and write one database.

The properties file already states the rule that keeps this safe:

> Writers scope their cleanup to rows they can name (a UUID marker, a title, an id); readers assert
> what their own query means rather than what a table holds.

Three tests break one half of that rule or the other. The rule is right; what is missing is anything
that enforces it, and one table where following it is currently impossible.

## The writer

`DmlBulkMutationsExecutionTest` seeds `film_actor` rows around its `@nodeId`-decode tests, and picks
its pairs out of the seeded id space on purpose, each comment noting the pair is "not in the
init.sql seed":

| Test | Pair seeded |
|---|---|
| `deleteFilmActorByNodeId_singleRow_deletesByDecodedComposite` | (actor 1, film 4) |
| `deleteFilmActorsByNodeId_bulkRows_deletesAllViaRowIn` | (2, 3) and (3, 4) |
| `deleteFilmActorByNodeId_wrongTypeNodeId_surfacesError` | (3, 3) |

Each row is committed, held for the duration of one GraphQL execution, and deleted in a `finally`.
The cleanup is correct and the window is milliseconds wide. It is still long enough, because the
readers below run continuously beside it.

`film_actor` is the one table in the fixture where the documented writer rule cannot be followed as
written. Its primary key is the pair `(actor_id, film_id)` and it has no marker column, so there is
no such thing as a privately-named `film_actor` row over seeded actors and films. A writer that
needs one has to bring its own actor and its own film.

## The readers, and what each one actually asserts

**`RoutineFieldExecutionTest`** reads the seeded cast through the `films_for_actor(p_actor_id,
p_min_length)` routine. The seed casts ED (actor 3) in films 2 and 5; film 3 has length exactly 50,
so at `minLength: 50` a `(3, 3)` row makes film 3 appear in ED's list.

Reproduced deterministically. With `film_actor (3, 3)` present for the whole run, 7 of the class's 16
tests fail, with the message CI shows, including both methods CI has reported so far
(`correlatedChildRoutineBindsArgumentThroughDotPath:156` and
`splitRoutineChildBatchesByBoundColumns:372`):

```
Expecting actual:
  [3, 5]
to contain exactly (and in same order):
  [5]
```

On CI only one method fails per run, because the polluting row exists for one GraphQL execution
rather than for the whole class. Which method fails is therefore a coin toss, which is why the
failure looks like a different bug every run.

**`CompositeKeyLookupQueryTest.compositeKeyLookup_subset_returnsOnlyMatchingPair`** is built on the
premise, stated in its own comment, that `(film 4, actor 1)` is not a real row: it asserts the
unmatched composite key holds null in its slot. That is the exact pair
`deleteFilmActorByNodeId_singleRow_deletesByDecodedComposite` seeds.

Reproduced deterministically. With `film_actor (1, 4)` present, the test fails on "the unmatched
composite keeps its slot", the CI failure at `:118`.

**`MultiTableNodeIdRouteExecutionTest.adifferentLanguageSelectsADifferentSetOnBothBranches`** is the
most frequent failure of the three and a different shape. It reads language 1's films through
GraphQL at line 132, then compares that against a fresh SQL read of the same set at line 137. Every
`insertFilm` helper in the module writes `language_id = 1` and deletes the row in a `finally`, so any
concurrent film test landing between those two reads breaks the comparison. This is a
read-versus-read race over an unfiltered set, so no static row reproduces it; it is the reader half
of the documented rule, asserting what the table holds while meaning what the test owns.

## What lands

Each of the three stops depending on rows it does not own.

* The `film_actor` writers bring their own actor and their own film, marker-named like every other
  writer in the module, and cast those two. A composite-key `@nodeId` decode does not care which
  pair it decodes, so the tests keep their meaning exactly and the collision disappears rather than
  being narrowed.
* `CompositeKeyLookupQueryTest` asserts the unmatched-slot contract over a pair it owns, instead of
  over a pair it believes the seed does not contain. The current form is a claim about the whole
  table and will rot again the next time anyone needs a spare `film_actor` row.
* `MultiTableNodeIdRouteExecutionTest` takes both of its reads against one snapshot, or restricts
  both sides to films it created. Comparing two reads taken at different instants against a
  concurrently mutated table cannot be made reliable by narrowing who writes.

The gate to argue at Spec is whether anything should enforce the properties file's rule rather than
restating it. The module already has the precedent for the blunt version: every `@QuarkusTest` class
carries `QuarkusTestLock.KEY` and `QuarkusTestLockEnforcementTest` fails the build when a new one
arrives without it. A resource lock over the shared fixture tables is the equivalent, and it is the
fallback if any of the three fixes above turns out to be impractical. It should not be the primary
fix: it serialises broadly to paper over three specific tests, and it leaves the next reader free to
make the same mistake.

## Why this was not caught in the inner loop

The documented inner-loop command excludes the execution group, so it never runs these classes. The
full verification build does run them, with the same four-way class parallelism against the same
shared database, so it is exposed to exactly the same race: a green local `mvn install -Plocal-db` is
luck rather than coverage, at the 4-in-40 rate CI is currently measuring. That is the part worth
fixing first, because it is what lets a red-trunk change look verified.

## Related

The other half of "our local build tells us something rosier than CI does" is a wall-clock question
rather than a correctness one, and is filed separately if at all. The fact-store cache is not
involved in this item: the execution tier reads PostgreSQL, and the two failing reads above have
nothing to do with the fact store's warmth.
