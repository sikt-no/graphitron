---
id: R959
title: "An execution case reads a film another case inserted, so its two queries disagree"
status: Spec
bucket: cleanup
priority: 6
theme: testing
depends-on: []
created: 2026-09-18
last-updated: 2026-10-20
---

# An execution case reads a film another case inserted, so its two queries disagree

## Goal

`OptionalNodeIdProjectionExecutionTest.anOmittedNodeIdReachesAConditionMethodAsNull` asserts that two
queries against the same database return the same films, and it fails intermittently because another
case inserts a film between them. The execution tier runs classes in parallel against one PostgreSQL
instance seeded by `init.sql`, which creates five films (`film_id` 1 to 5), and several mutation cases create more; the
observed failure compared `[1, 2, 3, 4, 5]` against `[1, 2, 3, 4, 5, 151]`, a film id far outside the
seeded range. When this lands the case reads only rows it owns, so a green build means what it says
rather than meaning no mutation case happened to run in the window.

The fix is the case's to make and not the tier's: this is one assertion comparing two whole-table
reads, and the tier's shared database is deliberate. Narrowing what the case selects, or scoping it to
the films it seeds, is the shape; widening the fixture's isolation is not.

Filed in passing while implementing another item, on a failure seen once in a full verification build
and not reproduced on a re-run of the class alone, which is what a race between parallel classes looks
like from the outside.

It has since failed trunk CI: run #6820 (`9cc26bd`, 2026-10-18) compared `[1, 2, 3, 4, 5]` against
`[1, 2, 3, 4, 5, 41]`. `SingleRecordPayloadDmlTest`, which inserts and commits `film` rows with
`language_id = 1` and deletes them in cleanup, started just before this class and finished after it,
so it is one writer the fix has to account for. CI builds the reactor fresh from a clean checkout, so
this occurrence is not the stale-artifact reading R825 raises about its own evidence.

## The exposure, case by case

`OptionalNodeIdProjectionExecutionTest` (in `graphitron-sakila-example`, package `querydb`) pins that a `@nodeId` (a field carrying an encoded node id that the generator decodes to key columns) left out of a request reaches its consumer as `null`. Every row-reading case funnels through one helper, `filmIds`, which returns every `filmId` the root field produced. None of the three root fields it reads is bounded to rows the class owns: `filmsForOptionalActor` and `filmsForOptionalActorFilter` call `films_for_actor_or_all`, which with a null actor returns every film of at least `minLength`, and `filmsByOptionalLanguage` with no language returns every film. So each read is a whole-table read of `film`, and the module's `junit-platform.properties` already names what that costs: classes run concurrently against one PostgreSQL instance, and "readers assert what their own query means rather than what a table holds".

Read against concurrent writers (a film inserted, or one deleted, between two reads of the same case):

* `anOmittedNodeIdReachesAConditionMethodAsNull`: `omitted.isEqualTo(english)`, two whole reads compared exactly. The observed failure. Its third read, `italian.isEmpty()`, is exposed the same way to any writer that commits a film with `language_id = 2`; none does today, which is luck rather than design.
* `anExplicitlyNullNodeIdProjectsNullToo`: the same shape, two whole reads of `filmsForOptionalActor` with a null actor compared with `isEqualTo`. Not yet observed failing; the window is the same.
* `anOmittedNullableNodeIdProjectsNullIntoARoutineParameter` and `anOmittedAncestorOfANonNullNodeIdProjectsNull`: `omitted.containsAll(supplied).hasSizeGreaterThan(supplied.size())`. A film inserted between the two reads lands in `supplied` without being in `omitted` only if the writer also links it to actor 1 through `film_actor`; `DmlBulkMutationsExecutionTest` seeds `film_actor` pairs, inside a rolled-back transaction, so this is narrow today. The fix covers these two anyway because it costs nothing extra: they read through the same helper.

The goal names one case; the plan fixes the class, because the other comparison is the same defect waiting for its window, and filing it again when it fires would be the same item twice.

## Implementation

One file, `OptionalNodeIdProjectionExecutionTest`, following the convention `GraphQLQueryTest` states under its "The seeded film rows, and why a case narrows to them" banner:

* Add `SEEDED_FILM_IDS = List.of(1, 2, 3, 4, 5)`, the films `init.sql` seeds, with a one-line javadoc saying so.
* Make `filmIds` return only the seeded ids, in the order the query returned them, and say in its javadoc why: the root fields here read the whole `film` table, which other classes write while this one runs. The class javadoc gains one sentence pointing at the module's `junit-platform.properties` for the hazard rather than restating it.
* Tighten what the condition case asserts now that its reads are bounded. `omitted` is asserted equal to `SEEDED_FILM_IDS` ("the method received null and constrained nothing") rather than `isNotEmpty().isEqualTo(english)`: over a bounded set, "constrained nothing" is a definite list, and comparing to it says more than comparing to a second read. `english` keeps its equality with `omitted`. `italian.isEmpty()` stays and keeps its meaning: had the method ignored the key it was handed, the italian read would return all five seeded films, which the narrowing does not hide.
* The routine cases need no assertion change. Actor 1 is seeded into films 1, 2 and 3 (`init.sql`'s `film_actor` block), so after the narrowing `supplied` is `[1, 2, 3]` and `omitted` is `[1, 2, 3, 4, 5]`, and `hasSizeGreaterThan` still distinguishes "every actor" from "this actor".

What this deliberately does not do: add rows, change `init.sql`, open a transaction, or touch the tier's parallelism. The shared database is the tier's design and the reader-side narrowing is the module's stated remedy.

## Tests

The change is to a test, so the acceptance evidence is the test's own behaviour under the race it used to lose, plus proof it still detects the defect it pins:

* The class passes in the verification build (`mvn install -Plocal-db`).
* Race witness, run once by the implementer and reported in the In Review commit message, not committed: commit a probe film from a second connection before the class runs (`INSERT INTO film (title, language_id) VALUES ('PROBE', 2)` against the local-db instance, deleted afterwards). On the parent commit this fails `anOmittedNodeIdReachesAConditionMethodAsNull` deterministically, because the probe sits in `omitted` but not in `english`, and in `italian` too; with the fix the class passes. The probe stands in for the concurrent writer by being present for the whole class rather than landing between two reads, which turns the race into a reproducible precondition.
* Mutation witness, likewise reported and not committed: change `ProjectedKeyConditionFixtures.filmsOfOptionalLanguage` to return `DSL.falseCondition()` for a null key, and the condition case fails; change it to ignore a non-null key, and the `italian` assertion fails. This shows the narrowing did not make the case vacuous.

## Other solutions we've considered

* **Seed the case's own films in a rolled-back transaction** (`DmlBulkMutationsExecutionTest.inRolledBackTransaction`). It would also let the case own a non-English film, which would separate "null means unconstrained" from "null means English", a distinction the all-English seed cannot draw today. Rejected for this item: running the GraphQL execution on the seeding transaction's connection is new plumbing for this class, and the distinction is a strengthening of what the case pins, not the flake this item exists to remove. If it is wanted, it is its own item.
* **Add a non-English film to `init.sql`.** Every class that lists the seeded films exactly (`GraphQLQueryTest.SEEDED_FILM_IDS` among them) would move with it; far wider than the defect.
* **Make the class run in isolation** (`@Isolated`, or a resource lock against every film writer). Widens the fixture's isolation, which the goal rules out, and costs build wall clock for a fault the reader can remove itself.
