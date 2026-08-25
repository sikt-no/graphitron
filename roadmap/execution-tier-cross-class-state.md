---
id: R833
title: "The execution tier fails under its own parallel run"
status: Backlog
bucket: Testing
priority: 3
depends-on: []
created: 2026-08-25
last-updated: 2026-08-25
---

# The execution tier fails under its own parallel run

A verification build of the whole reactor failed in `graphitron-sakila-example` on
`GraphQLQueryTest.splitTableField_conditionJoin_returnsActorsPerFilm`, which expected one actor per
film and read two. The same test passes on its own, its whole class passes on its own, and the whole
module passes on its own; the next full reactor build passed too. So the failure is not in the
generated SQL and not in the test's own assertion. It is the tier's shared database: the module's
test classes run against one PostgreSQL instance, some of them write, and a reading test that
happens to run beside a writing one sees the write. Under a sequential run the order is stable
enough to hide that, and under the parallel run the reactor and CI both use it is not.

This is worth a plan rather than a retry loop. A tier that fails a build for reasons unrelated to
the change under test is a tier nobody can read a red build from, and the standing rule that a
failing test is never an infra flake stops meaning anything the first time a build teaches an author
to re-run. The question to answer is which isolation the tier actually wants: a transaction per test
rolled back at the end, a schema per test class, a read-only fixture the writing tests never touch,
or a declared partition of tables between the writers and the readers. Whichever it is, the property
to gate on afterwards is that the module passes with its classes deliberately interleaved, because
an isolation nothing re-checks is one the next writing test quietly breaks.

Observed on 2026-08-25 against `claude/graphitron-rewrite`; the condition-join execution cases were
added shortly before it, so the population that writes and the population that reads may only
recently have started overlapping.
