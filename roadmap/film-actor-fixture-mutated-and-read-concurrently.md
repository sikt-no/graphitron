---
id: R825
title: "A mutation test seeds film_actor rows a query test asserts the absence of"
status: Backlog
bucket: cleanup
priority: 2
depends-on: []
created: 2026-08-24
last-updated: 2026-09-22
---

# A mutation test seeds film_actor rows a query test asserts the absence of

`graphitron-sakila-example`'s execution tier runs every test class against one shared PostgreSQL
database, and two classes disagree about who owns the `film_actor` seed.
`DmlBulkMutationsExecutionTest.deleteFilmActorsByNodeId_bulkRows_deletesAllViaRowIn` inserts the
pairs `(actor 2, film 3)` and `(actor 3, film 4)` before its mutation and deletes them in a
`finally`, on a comment that reasons the pairs are safe because neither is in `init.sql`'s seed.
That reasoning covers a sequential run and not a concurrent one.
`GraphQLQueryTest.splitTableField_conditionJoin_returnsActorsPerFilm` reads film 3's actors through
the condition-join split-rows path and asserts the answer is exactly `{1}`, so while the mutation
test holds its transient row the query test's assertion is false. Observed on a full
`mvn install -Plocal-db`, failing with `[1, 2]` against an expected `[1]`; the same tree passes when
the class or the module runs on its own, and passed a second full build, so the two classes have to
be running concurrently for it to land. The two other classes that write `film_actor`
(`TenantDivinedRoutingExecutionTest`, `TenantFanOutExecutionTest`) use film ids in the hundreds and
are clear of every seeded read, which is the shape the fix wants: a writer either picks rows outside
every reader's assertion window or takes a row nobody else reads. Worth answering because the
failure presents as a correctness defect in condition-join emission, which is where the next reader
of a red build will spend their afternoon, and because CI runs the reactor with `-T 1C`, so the
concurrency that produces it is the normal case rather than the unlucky one.

Two more occurrences, both 2026-08-25 and on consecutive full installs on one machine, widen both
sides of the race. The same writer class has a third seeding site the paragraph above does not
list, `seedFilmActor(3, 3)`, and a second reader observed it:
`RoutineFieldExecutionTest.correlatedChildRoutineReturnsPerParentRows` failed with `[2, 3, 5]`
against an expected `[2, 5]` for actor 3, the extra film 3 being exactly that transient pair, gone
from the database by the time anyone looked. The very next run failed a different method of the
same reader (`childRoutineThenHopsChainJoinsOutOfRoutineResultPerParent`, actor 2 showing the
already-listed `(2, 3)` pair), so on that machine the two classes overlap more often than not. The
fix should inventory every `seedFilmActor` call rather than the two pairs first observed, and the
reader set is every per-parent film assertion in the module, not one condition-join case.

A fourth occurrence, 2026-09-22, moves the race onto a second table and so widens what the fix has
to cover. The same writer class inserts and deletes `film` rows with `language_id = 1` around its
bulk-insert cases, and `OptionalNodeIdProjectionExecutionTest.anOmittedNodeIdReachesAConditionMethodAsNull`
read across that window: its unconstrained query returned `[1, 2, 3, 4, 5]` where the query
constrained to English returned `[1, 2, 3, 4, 5, 58]`, which is impossible from one database state,
an unconstrained read being a superset of a constrained one by construction. Observed on a full
`mvn install -Plocal-db`; the class on its own and the whole module on the same tree both pass. So
the inventory the paragraph above asks for is not only `seedFilmActor`: it is every row the writer
class touches, `film` included, and the reader set is every execution case that compares two reads
of one table rather than only the per-parent film assertions.

Adjacent to R823, which records a different execution-tier test reading a mutating table; whether
the two want one answer (a convention about which rows an execution test may write) or two is for
the Spec to decide.
