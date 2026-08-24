---
id: R825
title: "A mutation test seeds film_actor rows a query test asserts the absence of"
status: Backlog
bucket: cleanup
priority: 2
depends-on: []
created: 2026-08-24
last-updated: 2026-08-24
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

Adjacent to R823, which records a different execution-tier test reading a mutating table; whether
the two want one answer (a convention about which rows an execution test may write) or two is for
the Spec to decide.
