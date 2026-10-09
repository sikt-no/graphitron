---
id: R808
title: "DML film_actor cases seed rows that concurrent reader cases see on -Plocal-db"
status: Spec
bucket: bug
priority: 3
theme: testing
depends-on: []
created: 2026-08-22
last-updated: 2026-10-09
---

# DML film_actor cases seed rows that concurrent reader cases see on -Plocal-db

## Goal

On a `-Plocal-db` build (the profile that points every execution test class at one shared
PostgreSQL instance, and the way the web sandbox builds because it has no Docker), the
`graphitron-sakila-example` reader cases that assert an exact list of actors per film, or of films
per actor, answer the same whatever runs beside them. Today they intermittently return one row too
many, because `DmlBulkMutationsExecutionTest` commits `film_actor` pairs that `init.sql` does not
seed and only removes them in `finally`; a reader class running concurrently can query the table
inside that window. When this lands, those seeded pairs exist only inside a transaction the case
rolls back, so no other connection can ever see them, and a green or red verdict from these reader
cases means what it says about the generator again.

## What is known

Four occurrences, all in full `-Plocal-db` reactor builds, all passing on an immediate rerun of
the class or case on the same commit:

* `GraphQLQueryTest.splitTableField_bridgingConditionJoin_returnsActorsPerFilm`: expected `[1]`
  actors for a film, got `[1, 2]` (2026-08-22); later got `[1, 3]` for film 3 (2026-10-09).
* `GraphQLQueryTest.referenceFilter_reverseDirectionFkHop_matchesEachParentOnce`: expected actor
  1's films `[1, 2, 3]`, got `[1, 2, 3, 4]` (2026-09-05).
* `GraphQLQueryTest.splitTableField_conditionJoin_returnsActorsPerFilm`, the unbridged sibling of
  the first case, the same shape (2026-09-14).

The writer is the composite-key `@nodeId` DELETE block of `DmlBulkMutationsExecutionTest`. Its
helpers `seedFilmActor` and `cleanupFilmActor` insert, commit, and later delete these pairs:

[cols="2,1,2"]
|===
|Case |Pairs (actor, film) |Extra row it explains

|`deleteFilmActorByNodeId_singleRow_deletesByDecodedComposite`
|(1, 4)
|film 4 in actor 1's films, the reverse-hop case's `4`

|`deleteFilmActorsByNodeId_bulkRows_deletesAllViaRowIn`
|(2, 3), (3, 4)
|actor 2 on film 3, the `[1, 2]`

|`deleteFilmActorByNodeId_wrongTypeNodeId_surfacesError`
|(3, 3)
|actor 3 on film 3, the `[1, 3]`
|===

`graphitron-sakila-example/src/test/resources/junit-platform.properties` runs test classes
concurrently at a fixed parallelism of 4. Each execution class opens its own `DSLContext` in
`@BeforeAll`, against its own Testcontainers PostgreSQL unless `test.db.url` is set, and only the
`local-db` profile sets it. So on the default profile the writer and the readers live in different
databases and the race cannot happen; on `-Plocal-db` they share one, and a reader can land between
a seed's commit and its cleanup. That is why a single case or a single class never reproduces it.

The same pairs threaten readers that have not failed yet. `RoutineFieldExecutionTest`'s
`allActors` cases assert NICK's films `[1, 4]` and ED's `[2, 5]` exactly, which `(2, 3)`, `(3, 3)`
and `(3, 4)` would each break. The other `film_actor` writers in the module,
`TenantDivinedRoutingExecutionTest` and `TenantFanOutExecutionTest`, write into tenant databases
they create for themselves, so they are not part of this.

## Implementation

All in `graphitron-sakila-example` test sources; no generator, model, or schema change.

`DmlBulkMutationsExecutionTest`: the three `film_actor` cases run their whole body, seed,
mutation, and assertions, inside one `dsl.transaction(...)` that always ends by rolling back, and
hand the transaction's `DSLContext` (`DSL.using(cfg)`) to both the seed and to
`Graphitron.newExecutionInput`. A small private helper carries the shape, something like
`inRolledBackTransaction(Consumer<DSLContext>)`, which runs the body and then throws a private
sentinel exception that `DefaultTransactionProvider` turns into a rollback and the helper swallows;
any other exception or assertion failure propagates as itself. `run`/`execute`/`executeRaw` gain an
overload taking the `DSLContext` to execute against, and `seedFilmActor` and `countFilmActor` take
it as a parameter. `cleanupFilmActor` and the `finally` blocks go: the rollback is the cleanup.

Why this works with the code under test:

* The class's `dsl` comes from `DSL.using(url, user, password)`, which holds one JDBC connection,
  and methods within a class run on one thread (`mode.default=same_thread`), so a transaction on it
  cannot interleave with another case of the same class.
* The generated fetchers read their `DSLContext` from the execution input
  (`graphitronContext(env).getDslContext(env)`), so the mutation's DELETE runs on the transaction's
  connection and sees the uncommitted seed. PostgreSQL's read-committed isolation keeps it from
  every other connection.
* The two composite-key DELETEs are single statements, not `dsl.transactionResult` blocks, and if
  one ever becomes one, jOOQ nests it as a savepoint.
* `deleteFilmActorByNodeId_wrongTypeNodeId_surfacesError` throws in the fetcher before any SQL, so
  the transaction is not left aborted, and its "seeded row is untouched" count still runs inside it.

`junit-platform.properties`: the "database" paragraph states the rule the fix applies. Cleanup in
`finally` bounds what a table holds afterwards, not what it holds meanwhile, so a writer that seeds
rows into a table whose seeded content reader classes assert exactly seeds them in a transaction it
rolls back. Name `DmlBulkMutationsExecutionTest`'s helper as the pattern.

## Tests

The fault is a scheduling race, so "the full build stayed green" is weak evidence. The invariant
the fix establishes is checkable deterministically: rows seeded by these cases are invisible to any
other connection. `seedFilmActor` (or the helper) asserts that, right after the insert, a second
connection to the same database counts zero rows for the pair. The second connection is opened
from the same URL the class used, `test.db.url` or the container's JDBC URL, so the check runs on
both profiles. That turns the property into a failing assertion if a future edit moves a seed back
outside the transaction, which a race cannot be relied on to do.

The existing assertions in the three cases keep their meaning, deleted-count zero and
untouched-count one, now observed inside the transaction. The reader cases in `GraphQLQueryTest`
and `RoutineFieldExecutionTest` are unchanged; their exact lists over seeded films are what they
mean to assert.

## Other solutions we've considered

* *Seed pairs no reader asserts on.* There is no such pair. Every reader keyed on films 1 to 5 or
  actors 1 to 3 asserts exact lists, actor 4 (JOAN) is asserted cast in nothing, and a fresh actor
  breaks `RoutineFieldExecutionTest`'s `allActors` size of 4. Fresh film and fresh actor together
  still trip that size, and would rest on a convention nothing enforces.
* *A shared `@ResourceLock` on `film_actor`*, after the `QuarkusTestLock.KEY` pattern. It has to
  sit on every class that reads the table, `GraphQLQueryTest` among them, which serialises the
  module's largest class against the writer, and unlike the Quarkus key there is no annotation an
  enforcement test could key the rule on: which classes read `film_actor` is not visible from the
  test source.
* *Relax the reader assertions* to containment. That gives up what the readers mean to check, that
  the join returns exactly the related rows; a reader that admitted an extra row through a broken
  predicate would pass.
