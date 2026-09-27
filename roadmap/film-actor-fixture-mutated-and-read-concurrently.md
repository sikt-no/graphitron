---
id: R825
title: "A mutation test seeds film_actor rows a query test asserts the absence of"
status: Backlog
bucket: cleanup
priority: 2
depends-on: []
created: 2026-08-24
last-updated: 2026-09-27
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

A fourth occurrence, 2026-09-27, and this one lands on a writer this item had cleared. The text
above reasons that `TenantFanOutExecutionTest` and its sibling are safe because they use film ids in
the hundreds, clear of every seeded read. That reasoning is about what they write. This failure is a
read: `downedTenant_byTimeout_classifiesTenantFanOutTimedOut` expected three films and got
`[null, null]`, so the tenant-1 fan-out arm returned nulls and the appended element for the timed-out
tenant never arrived. Observed on a full `mvn clean install`; the class on its own passes on the same
tree. The harvest carrying it touched no file in `graphitron-sakila-example` and nothing tenant-side,
which is what makes it this item's rather than that work's.

Worth recording because it widens the reader set the same way the third occurrence did. A writer
picking rows outside every reader's window does not protect that writer's own reads, and a fan-out
case reads through several datasources at once, so the window it is exposed to is every one of them.

**A doubt about the evidence in this item, raised by a session that caught itself making the
mistake.** "Passed alone, passed on a rerun" does not discriminate a flake from a stale artifact,
because both of those rebuild the thing that was stale. It reads as nondeterminism and is equally
consistent with a tree that was simply wrong, and the execution tier is where that bites hardest: it
runs generated code out of `graphitron-sakila-example/target`, which a plain `install` leaves in
place and a rebuild silently corrects.

That lands on this item's own record. The first and third occurrences were both observed on
`mvn install -Plocal-db`, with no `clean`, so staleness is not excluded for either. The fourth was
observed on a full `mvn clean install`, and the log shows `clean:3.5.0:clean` running on
`graphitron-sakila-example` in that same build, so the generated code it executed was produced by
the build that failed; a full reactor also resolves its inter-module dependencies from its own
outputs rather than from installed jars, which is the other way staleness enters and only bites a
`-pl` build. So the fourth stands and the other two want re-reading.

What follows is that the flake reading now rests on one occurrence rather than three, and that this
item may be two items: a nondeterministic writer, and a build-hygiene trap that has been producing
phantom failures nobody could reproduce. Whoever takes the Spec should settle which before designing
a fix, because the two want different answers.

Adjacent to R823, which records a different execution-tier test reading a mutating table; whether
the two want one answer (a convention about which rows an execution test may write) or two is for
the Spec to decide.
