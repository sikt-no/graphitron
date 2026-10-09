---
id: R808
title: "A bridging-condition split-table execution case returns a second actor only in a full-module run"
status: Backlog
bucket: bug
priority: 3
theme: testing
depends-on: []
created: 2026-08-22
last-updated: 2026-10-09
---

# A bridging-condition split-table execution case returns a second actor only in a full-module run

`GraphQLQueryTest.splitTableField_bridgingConditionJoin_returnsActorsPerFilm` in
`graphitron-sakila-example` failed one full `mvnd install -Plocal-db` and passed the next, on an
unchanged tree. The assertion is an exact list of actor ids: it expected `[1]` and got `[1, 2]`, so
the read returned one row too many rather than timing out or erroring.

What makes it worth an item rather than a re-run is that the same code answers differently depending
on what ran beside it. Re-run alone the case passes; re-run as the whole `GraphQLQueryTest` class,
378 tests, it passes; the second full reactor build was green. No commit between the last known-green
full build and the failing one touched generator main sources, the model DDL, or this module, which
is what rules out a regression and leaves execution order or residual database state as the
mechanism. An execution-tier case whose answer depends on its neighbours is a case that cannot be
trusted either way: it will fail a green tree, and it will pass a broken one.

Where to start is what a second actor row means for this shape. The case is a `@splitQuery` field
whose join is bridged by a `@condition`, so the candidates are a bridging predicate that admits an
extra row when a row another case wrote is present (making the expected `[1]` correct only against a
pristine table), and a fixture that mutates shared state without restoring it. The full-module
failure is reproducible material: the failing run's ordering is recoverable from the surefire report
beside the passing one.

A second order-dependent failure surfaced in the same session, in `graphitron-lsp` and with nothing
in common with this one beyond being order-dependent; it is filed as
`roadmap/trace-static-state-leaks-between-cases.md`. Two in three full builds is what says the suite
has such cases rather than one unlucky test, which is the reason both are items instead of re-runs.

A second case in the same class showed the same shape on 2026-09-05:
`GraphQLQueryTest.referenceFilter_reverseDirectionFkHop_matchesEachParentOnce` expected `[1, 2, 3]`
and got `[1, 2, 3, 4]` in a full `mvnd install -Plocal-db`, then passed the whole class, 409 tests,
on the same commit. That is one row too many again, in a different shape: a reference filter over a
reverse-direction foreign-key hop rather than a bridged split-table join. The two cases share the
suite and the module and not the feature, which points the search at residual rows over any one
rule's predicate, and says the exact-list assertions in this class are reading a table other cases
write.

Found while holding the In Review gate on the inlay enforcer item, which is unrelated to this
module; filed rather than folded into that verdict.

A third case, on 2026-09-14: `GraphQLQueryTest.splitTableField_conditionJoin_returnsActorsPerFilm`,
the unbridged sibling of the case this item opens with, failed one full `mvn install -Plocal-db` and
passed the whole class, 419 tests, on the same commit minutes later. Same class, same exact-list
assertion, same "passes alone, fails beside its neighbours" answer. What it adds is that the bridging
predicate is not the variable: the bridged and unbridged forms of one split-table join both show it,
which narrows the search further toward residual rows in the shared table over any one rule.
Observed while verifying the node-type decode identity, which touches neither this module's fixtures
nor the split-table path.

A fourth occurrence, on 2026-10-09, names the mechanism. The opening case failed again in a full
verification build, expecting `[1]` for film 3 and getting `[1, 3]`, then passed on a
`-rf :graphitron-sakila-example` rerun. The writer is `DmlBulkMutationsExecutionTest`: its
composite-key DELETE cases insert `film_actor` pairs that `init.sql` does not seed, `(3, 3)`,
`(2, 3)`, `(3, 4)` and `(1, 4)`, commit them, and delete them again in `finally`. Those pairs line up
with every row-too-many seen so far: `(3, 3)` is the `[1, 3]`, `(2, 3)` is the `[1, 2]` this item
opens with, and the film-4 pairs fit the reverse-hop case's extra `4`. `junit-platform.properties`
runs test classes concurrently at parallelism 4, so a read-side class can query `film_actor` between
an insert and its cleanup. That window is why a single class, or a single case, never reproduces it.

The race needs one shared database, so it is a `-Plocal-db` failure. Each execution class's
`@BeforeAll` starts its own PostgreSQL container unless `test.db.url` is set, and only the
`local-db` profile sets it. On the default Testcontainers profile the writer and the readers are in
different databases and cannot see each other's rows. Every occurrence on record was a `-Plocal-db`
build. That still matters, because the web sandbox, which has no Docker, builds that way.

The fix options are now concrete:

* Move the DML cases' seeded pairs onto films and actors that no read-side case asserts on. That is
  local and cheap, but it is a convention nothing enforces, and the next writer can break it.
* Put a shared JUnit `@ResourceLock` on `film_actor` across the classes that write it and the classes
  that read it. `QuarkusTestLock.KEY` is the existing pattern for this, including its enforcement
  test. The cost is serialising those classes.
* Seed inside a transaction the case rolls back, so no other connection ever sees the rows. Whether
  that works depends on whether the generated mutation can run on the test's transaction-bound
  connection.

The `junit-platform.properties` comment already states the module's rule: writers scope cleanup to
rows they can name, and readers assert what their own query means. R1003 fixed a sibling of this
pattern on the reader side, with `createFilm` counting only its own row. Here the readers'
exact-list assertions over seeded films are reasonable, so the fix most likely belongs on the writer
side: a cleanup in `finally` scopes what the table holds afterwards, not what it holds meanwhile.
