---
id: R832
title: "CatalogRefreshTest budgets a real refresh like a no-op trigger"
status: Backlog
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-08-25
last-updated: 2026-08-25
---

# CatalogRefreshTest budgets a real refresh like a no-op trigger

`CatalogRefreshTest.javaSourceWriteMovesTheStoreRowWithoutAGeneratorPass` (in
`graphitron-maven-plugin`) waits on a `CountDownLatch` for 1.6 seconds (a 100 ms debounce window
plus 1500 ms) and fails with "source refresher must fire on .java write" when the latch times out.
That budget was inherited from `SchemaWatcherTest`, whose triggers are no-op `countDown()`
runnables, but this test's trigger does real work inside the awaited window: the production
refresh path, which is a cold javac parse (`SourceWalker` via the Compiler Tree API, first use in
the surefire JVM) plus jOOQ writes into an in-memory fact store. Measured on a *passing*
full-suite run, that refresh took 854 ms of the 1500 ms window, so the headroom is under a factor
of two, and the module's test classes run four-way concurrent (the parallelism arrives through the
`graphitron-model` test-jar leak R764 documents; this module is a fourth consumer that item should
also name), with `DevMojoTest`'s ~13 s of real generator work competing for the same cores. The
test failed twice on one machine on 2026-08-25 (both times on a cold first build of a session),
passes reliably in isolation, and showed no swallowed exception when instrumented: it is pure
slowness, a wall-clock threshold that is a flake on a loaded machine.

## The fix

Give this test its own latch budget, generous enough that machine load cannot reach it (10 s or
more); `CountDownLatch.await` returns as soon as the latch counts down, so a large budget costs
nothing on a passing run. The sibling negative case (`graphqlsWriteDoesNotFireClasspathWatcher`)
sleeps its full budget by design and should keep the short one. Independent of, but amplified by,
R764: settling that item's parallelism question for `graphitron-maven-plugin` reduces the
contention, but a sub-2x headroom on a real-work trigger is a flake waiting for a slower machine
either way.
