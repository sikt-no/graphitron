---
id: R1131
title: "A clean build runs in under five minutes"
status: In Progress
bucket: dx
priority: 5
theme: tooling
depends-on: []
created: 2026-10-30
last-updated: 2026-10-30
---

# A clean build runs in under five minutes

## Goal

The verification build, a clean `mvn install` of the whole reactor, finishes in under five minutes
on a 16-core workstation with the machine to itself, running every test and gate it runs today. It
takes 23:09 now. A contributor or an agent session then verifies a change in the time it takes to
read the diff, instead of a coffee break, and the machine is free again before the next session
needs it.

The target is wall clock on that machine, measured by the method in `## Measuring`. Nothing is
dropped to reach it: no test tier, no gate, no fixture is removed or skipped, and every saving is
priced by removal or by an interleaved before-and-after comparison, never by summing reported test
times.

## Where the time goes

Measured on 2026-10-30 with the JFR Maven extension (the `build-profile` skill) on a quiet machine,
H2 2.5.250, forked surefire:

| Goal | Seconds |
|---|---:|
| `graphitron` tests | 355 |
| `graphitron-model` tests | 332 |
| `graphitron-model` javadoc reference gate | 135 |
| `graphitron-sakila-example` tests | 85 |
| `graphitron-lsp` tests | 72 |
| `graphitron-maven-plugin` invoker run | 56 |
| `graphitron` `generate-test-schemas` | 52 |
| everything else | ~300 |
| **whole build** | **1389 (23:09)** |

The reactor runs one module at a time; mojo time sums to the wall clock. Recordings of each
test-heavy module run unforked (`-DforkCount=0`, tests inside the recorded JVM) show four things
that shape the plan:

1. **Statement preparation, not execution, dominates the store-heavy tests.** In `graphitron`'s
   test phase, 48% of execution samples sit under H2's parser (`org.h2.command.Parser`) and 41%
   under its optimizer, against 6% under query execution
   (`org.h2.command.query.Select.queryWithoutCache`). In `graphitron-model` the share is about 37%
   of test samples. 91% of that preparation happens while a capture writes, led by the derivation
   steps' large `INSERT ... SELECT` statements (`ArgumentReferenceStepTargets`, `FieldPaths`,
   `FieldReferenceStepHops`), and 9% inside `StoreReader.read`.

   The same statements are prepared again and again, for three reasons found in H2's
   `SessionLocal.prepareLocal`:
   * The per-session statement cache holds 8 statements by default (`QUERY_CACHE_SIZE`). jOOQ's
     statement log over `GraphitronStoreTest` shows 12,138 executions of 601 distinct SQL texts, of
     which 410 ran exactly once per capture: a capture runs about 500 distinct statements, a set
     fixed by the code rather than by the schema captured. With 8 slots none of them survives
     until it comes round again.
   * A rollback empties the session's cache (`SessionLocal.rollbackTo`), and `StoreReader.read`
     ends every read with one, so a reader prepares everything afresh on every read. A commit
     leaves the cache alone.
   * A schema change empties every session's cache, database-wide. Plain DML and `ANALYZE` do
     not.

   jOOQ already binds values rather than inlining them (the store sets no `statementType`):
   folding repeated value lists and `IN` lists merges only 5 of the 601 texts, so the statements
   do repeat textually.

   One interleaved pair on `FactExpectationTest`, which captures many graphs into one store,
   with the cache raised to 1024 through the `h2.queryCacheSize` system property: 515 s wall and
   781 s CPU at 8, 374 s and 581 s at 1024, a quarter less of each. A second pair ran at load 15
   to 35 and is discarded; the figure stands to be confirmed by the method in `## Measuring`.
2. **The `graphitron` test phase is CPU-bound.** Median CPU use is 90% of 16 cores with four test
   classes running. The rest of the load is garbage collection: 13 parallel GC threads, 34 s of
   pauses, and the top allocation sites are H2 tokens and plans (`Token.clone`,
   `Tokenizer.tokenize`, `TableFilter.getBestPlanItem`). Raising test parallelism therefore buys
   nothing until the work shrinks, and reactor parallelism competes for the same cores.
3. **The corpus sweeps form a long serial chain.** The corpus (`CorpusStore`, every spec-by-example
   document captured as its own graph) is captured once per test JVM behind a lock. About six
   classes then run for two to five minutes each, their methods sequential, and the phase ends
   with `CorpusCapturedOnceTest`, which is `@Isolated` and so runs alone for about 49 s after
   everything else. Removing the four slowest sweep classes saved 1 to 11 s of 440: the shared
   capture, not the readers, is the cost.
4. **One class carries `graphitron-model`'s test phase.** `FactExpectationTest` is 41% of the
   module's samples, and `CorpusExpectations.compare` alone 20%. Its seven methods run in
   sequence, so the class is the phase's long pole.

The module graph bounds what reactor parallelism can do. After `graphitron-model`, the modules
`graphitron`, `graphitron-mcp`, `graphitron-lsp` and `roadmap-tool` are independent;
`graphitron-maven-plugin` needs the first three, `graphitron-sakila-example` needs the plugin, and
`docs` needs `graphitron` and `roadmap-tool`. The critical path is
`graphitron-sakila-db`, `graphitron-model`, `graphitron`, `graphitron-maven-plugin`,
`graphitron-sakila-example`: about 1200 s today, most of it the two big test phases.

### Re-profile after steps 1, 2 and 4

The table above predates the steps and a good deal of trunk movement, so one clean recorded build
was taken again: plain `mvn clean install -Plocal-db` (sequential, the build the 23:09 came from) on
a 4-core, 16 GB machine at trunk, `settings=default`, load 1.1 at the start, `BUILD SUCCESS` with
every test passing, 37:15 wall (2,238 s). The mojo times sum to 2,231 s and the project windows are
disjoint, so the reactor is sequential and the split below is exclusive. This is not the 16-core
reference machine, and its numbers are about twice those where a phase is CPU-bound and about the
same where the test pool is the limit; the shape is the finding.

[cols="3,1,1,4"]
|===
| Module | Seconds | Share | Largest goals

| `graphitron` | 924 | 41% | tests 774, `generate-test-schemas` exec 109, test compile 30
| `graphitron-model` | 452 | 20% | tests 367, compile 58, jOOQ codegen 20
| `graphitron-sakila-example` | 255 | 11% | tests 136, `graphitron:generate` 75, compile 25
| `graphitron-lsp` | 224 | 10% | tests 218
| `graphitron-maven-plugin` | 184 | 8% | invoker 103, tests 59, descriptor 18
| `graphitron-mcp` | 64 | 3% | tests 57
| `roadmap-tool`, `docs`, `sakila-db`, the rest | about 130 | 6% | tests 33, asciidoctor 27, jOOQ codegen 25
|===

By goal, surefire is 1,644 s (74%), `compiler:compile` 123 s, the `graphitron` test-schema exec 109 s,
the plugin's invoker run 103 s, `graphitron:generate` 75 s, test compile 48 s. The module critical
path, `sakila-db`, `graphitron-model`, `graphitron`, `graphitron-maven-plugin`,
`graphitron-sakila-example`, is 1,845 s of the 2,238, and the other four modules overlap only if the
reactor is parallel and the cores are free.

Two things in it were not in the plan. The javadoc gate is gone from the table, and the `graphitron`
test-schema exec, 52 s in the first profile, is now the largest goal that is not a test, at 109 s of a
single sequential process. And the test pool runs far more classes at once than its configured four:
the surefire class times sum to 14,367 s in `graphitron` against a 774 s phase (an average of about
19 classes in flight), 3,517 s against 367 s in `graphitron-model` (about 10) and 3,255 s against
218 s in `graphitron-lsp` (about 15), and a tally of cold boots in the `graphitron` run found 30
distinct threads that booted the per-thread store, where four were meant. A pool that grows past
its parallelism when tasks block holds more stores, more heap and more garbage-collection work than
the fixed setting suggests, which fits the 3.1 s of wall time per 0.39 s of CPU a boot took.

`GraphCountGrowthGateTest` failed two of the full builds taken while this was measured, on a
different relation each time (0 ms alone against 46 ms as one of eight, and 3 ms against 59 ms,
each past a bound of four times the first plus 40 ms) and passed in the others; alone it takes about
45 s and in the full build up to 200 s. A garbage-collection pause or a stalled thread inside a
millisecond-scale measurement is enough to fail it, and the bound has hundreds of relations to
fail on, so it fails a verification build for a reason that is not in the code under test.

## Implementation

Each step is measured before it starts and priced after it lands, and lands on its own; the
numbering is the order to take them in, largest and cheapest first.

1. **Stop preparing the same statement twice.** Two changes, both in `GraphitronModelStore` and
   both production code, so the long-lived stores of `graphitron:dev`, the LSP and MCP gain as well
   as the tests:
   * **A statement cache that holds a capture, for the callers that capture again.** The caller
     says what it will do with the store, `StoreUse.ONE_CAPTURE` or `StoreUse.LONG_LIVED`, and the
     store sets `QUERY_CACHE_SIZE` on its connection URLs (in-memory and file-backed) to match:
     H2's own 8 for one capture, and 1024 for a connection that captures or reads again and again,
     room for the roughly 600 distinct statements a capture and its reads run with headroom. The
     cache is not free (about 50 KB a statement, so roughly 30 MB per session that fills it), and it
     pays only from a connection's second capture, the first one preparing every statement for the
     first time, so a global default is too broad: a build running modules in parallel holds one
     session per module and captures once in each. The build goals and `roadmap-tool` keep the
     default; the dev loop, the harness's per-thread store and the corpus store say `LONG_LIVED`.
     The size is a database-level setting H2 reads when the first connection opens the database, so
     the first handle to open a database decides it for every handle after.
   * **Readers end in a commit, kept read-only by H2's rights.** `GraphitronModelStore` creates a
     reader user with `GRANT SELECT ON SCHEMA PUBLIC` when it boots a store, and
     `GraphitronModelStore.reader` connects as that user, on a URL without the database-level
     settings (`DB_CLOSE_DELAY`, `ANALYZE_AUTO`, `LOCK_TIMEOUT`) that need admin rights.
     `StoreReader.read` keeps one transaction per answer and ends it with a commit, so the
     session's cache survives from read to read. Measured on 2.5.250: such a user can select
     through views and set its query timeout, so `ReadBudget` keeps working, while `INSERT`,
     `UPDATE`, `DELETE`, `MERGE`, `TRUNCATE`, `CREATE`, `DROP` and `ANALYZE` are refused with an
     error. That is stronger than the rollback it replaces: a write fails where it is issued
     instead of vanishing, and DDL, which H2 commits past a rollback, is refused too.
     `StoreConsole` is untouched; it connects to a database of its own.

   Expected: about a quarter off a store that captures many graphs, as `FactExpectationTest`
   does, and off repeated reads. A test that boots a fresh store and captures once gains little,
   since every statement is new to that store; what it pays is first-time planning, which step 6
   addresses.

   Landed, priced. Four interleaved pairs of `FactExpectationTest` on this 4-core machine, forked,
   the first pair discarded, the "before" arm at a cache of 8 and the "after" arm on the default
   path through `StoreUse.LONG_LIVED`: 473.7, 456.8, 473.6 s before and 361.1, 361.1, 358.4 s
   after, a saving of 108 s (23%), spread 96 to 115 s; the discarded first pair (105 s) agrees. By
   position in a store, on a ten-type scaled fixture, the first capture gained nothing (2639 ms
   against 2869 ms, noise) and the second to sixth gained 31% (1967 ms against 1365 ms), which is
   the reason the use is the caller's to state. The cost is memory, measured over six captured
   stores held at once: 40.7 MB per store at 8, 44 MB at 64, 46 MB at 128, and 70.5 MB at 700, 1024
   and 2048 alike, since a capture fills only the statements it runs. Sizes below a capture's
   statement count cost little and should save nothing, the statements coming round in the same
   order. `graphitron-lsp`'s tests, which keep dozens of stores alive, ran out of heap at 700 and
   above and pass at 8; they now take the default, which is 8, with no override.
   Findings from landing it: `QUERY_CACHE_SIZE` has no `SET` form and is not persisted, only a URL
   setting read when the first connection opens the database, so the writer's URL states it and a
   reader inherits it; a select-only reader can still create session-local temporary tables, which
   touch nothing the writer owns; and `GraphCountGrowthGateTest` can miss its bound by a few
   milliseconds when it shares this machine with other test classes, passing in 44 to 50 s alone.
2. **Check `{@link}` references in the compiler.** Replace the `javadoc-no-fork` execution
   (`check-link-references`) with javac's own `-Xdoclint:reference`, excluding the generated jOOQ
   packages that the javadoc sourcepath reads today, so the reference gate costs nothing beyond
   compiling. The gate keeps its meaning: a dangling `{@link}` or `{@see}` fails the build.
   Expected: about 150 s.

   Landed. The gate is `-Xdoclint:reference/protected` on the main compile of every in-scope
   module: `/protected` because the javadoc run it replaces looked at public and protected members
   only, and the main compile only because the old gate never read test sources. A module outside
   the gate sets `graphitron.doclint` to `-Xdoclint:none`, and `graphitron-model` excludes its three
   generated jOOQ packages by name, which also lets it drop its javadoc `sourcepath` override.
   Proved by a deliberately dangling `{@link}` failing the compile at its line, then removed; the
   whole reactor compiles under it, so no module carried a link the old gate was not reading.
   Priced: four interleaved pairs of a clean `verify -DskipTests` of `graphitron-model` on this
   4-core machine (load 1.2 to 2.5 at the start of each), the first pair discarded. Before, with
   the javadoc gate: 309, 305, 311 s. After: 96, 86, 88 s. A saving of 218 s, spread 213 to 223 s,
   against the 150 s expected; the discarded first pair (308 s to 100 s) agrees. Only this module
   is priced. The javadoc plugin skips a run whose output is current, so the gate only ever cost
   anything on a clean build, and the other modules' share, a smaller one, is part of the whole
   build's figure rather than measured separately here.
3. **Build independent modules in parallel.** Run the reactor with `-T` (and say so in CLAUDE.md
   and the `build` skill), after confirming that nothing collides when modules build together:
   the Quarkus test port, temporary directories, the leaf-coverage trace file, and the store
   files tests write. Expected: up to 150 s, less while the test phases stay CPU-bound.

   Parked after the audit, not done. The audit found no collision: tests bind ephemeral ports and
   the Quarkus tests live in one module behind a lock; temporary directories are unique; the
   leaf-coverage traces are written per module and read by a separate step after the reactor; a
   build's store lives under each module's own `target/graphitron-model`; and the two modules that
   open the shared PostgreSQL under `-Plocal-db` cannot overlap, the example depending on the plugin
   and the plugin on `graphitron`. What the audit also found is that the premise was off: CI
   already builds with `-T 1C` and `mvnd` is module-parallel by default, so only a plain
   `mvn install` is sequential, and the 23:09 baseline is that sequential build. What is left is
   making plain `mvn` parallel (a `.mvn/maven.config` carrying `-T 1C`, with the release deploy
   pinned back to one thread, or documentation alone) and pricing it. Pricing it on a 4-core
   machine says little about a 16-core one: after `graphitron-model` only four modules are
   independent, the `graphitron` test phase is about 21 minutes of CPU-bound work, the
   `graphitron-lsp` phase took 172 s alone and 11:28 inside the reactor, and identical trees
   varied between 28 and 40 minutes, so one sequential-against-parallel pair proves nothing.
4. **Shorten the corpus chain.** Capture the corpus once per build into a store file the tests
   open, or capture its graphs in parallel, so the sweeps start immediately. Replace the
   `@Isolated` count assertion in `CorpusCapturedOnceTest` with one that does not need the JVM to
   itself. Expected: about 100 s from `graphitron`.

   Landed in part, and the rest left. The item's premise was off in one respect: the three
   walk-comparison sweeps read only the bare population, and the second population, the corpus over
   the module's own test classes, was asked for by `CorpusCapturedOnceTest` alone, so its 43 s capture
   (the bare one takes 50 s, 63 documents on a quiet 4-core machine) was paid in the `@Isolated` tail
   for a population no sweep reads. The test now asks for the bare corpus only and asserts the one
   invariant it exists for, that a population is captured once, from a per-population count that is
   exact whenever it is read, which is what lets it run beside everything else. Priced: four
   interleaved pairs of the four classes that use the corpus store (the test and the three sweeps),
   trunk before and this change after, the first pair discarded: 312, 306, 324 s before and 274, 269,
   266 s after, a saving of 44 s, spread 37 to 58 s, the discarded pair (66 s) above that range.
   The bare capture itself is left. A persisted store file needs a build step to write it and a
   second process to open it, which the store refuses, and a parallel capture needs concurrent
   writers against a lock timeout of 1 ms; either would save at most 50 s, mostly overlapping other
   classes on a CPU-bound phase, for a population whose only three readers are the sweeps that retire
   with their walk side.
5. **Split `FactExpectationTest`.** Turn its document-driven cases into ones the test pool can
   spread across workers, and make `CorpusExpectations.compare` cheaper. Expected: about 100 s
   from `graphitron-model`.

   Not done, and the premise has gone stale. The class's seven methods take 0.0 s each: its whole
   cost is one `@BeforeAll` that captures every document in sequence into one shared store and
   checks each reading, so there was never anything at method level for the test pool to spread,
   and no class split is called for. On a quiet 4-core machine that setup split as 155 s capturing
   154 plain documents, 230 s in one `divergences` check over all their blocks, and 33 s for 14
   stepped graphs. Asking each graph's blocks separately, for relations that carry a graph column,
   was tried and read as 230 s down to 95 s in a single run, but trunk had moved between the two
   runs: derivation changes under another item landed in between and the class fell from 360 to 420 s
   to about 170 s without it. Interleaved pairs against the same trunk, the first pair discarded,
   found no effect: 178.6, 161.8, 167.3 s before and 161.6, 174.4, 177.8 s after, a mean of 2 s the
   wrong way against noise of about 15 s. So the change was not shipped, and this item's figures for
   the class are those of a trunk that no longer exists; its phases on current trunk are not
   measured. A single large capture of all the documents was considered and would need the
   derivation steps to run set-based over many graphs, each step running for one graph today,
   which is production code another item is rewriting. A query over the one shared store that
   fingerprints each document's rows would find duplicate documents, and belongs in an item of its
   own.
6. **Boot test stores from a template.** `GraphitronModelStore.create` replays the creation
   statements of all 442 relations for each store (7.7% of `graphitron`'s samples). Boot once and
   copy, or reuse a booted store through the reset `ThreadConfinedStore` already performs in
   `graphitron-model`. Expected: about 30 s.

   Not done, closed on measurement. Copying a booted store does not pay: a warm in-memory boot takes
   about 0.4 s, and reopening a copied, already-booted file through `openAt` took about 1.8 s,
   because H2 re-parses every stored definition on open and a copy skips only the cheap part, the
   execution of the creation statements (the file is 884 KB and copies in milliseconds). Reuse
   through the reset is already how most of the harness runs. What is left is small: a full
   `graphitron` test phase on a 4-core machine (4,683 tests, 963 s) did 476 cold boots, 182 of them
   through `CapturedStore`'s own-store arms, 43 through the production facade in
   `OutcomeBlockRenderer`, 30 through the per-thread store and the rest spread over about thirty
   classes, several of which test boot and warm start themselves. Their thread CPU time summed to
   183.6 s, 0.39 s a boot, against 3,078.5 s of CPU for the whole test JVM, which averaged 3.7 of its
   4 cores: 6.0% of the phase's CPU. The wall time of those boots summed to 1,463 s, 3.1 s a boot,
   almost all of it waiting for a core, so wall time is the wrong thing to price them by. Removing
   every boot would save at most that 6%, about 57 s of wall clock here, and the pool that could be
   removed without changing what a test does is the own-store arm, 72.6 s of CPU, about 23 s of
   wall, which needs a shared fixture to be reset between uses and so carries the leakage risks
   `ThreadConfinedStore` documents. Not worth that.
7. **Take the slow test suites off the critical path.** With steps 1 to 6 the build projects to
   about ten minutes: the compile chain still waits for each module's tests before the next
   module starts. Moving the heaviest suites into test modules of their own (or adopting Maven 4's
   concurrent builder, if it lets a dependent start once the jar is packaged) lets those suites
   run beside the compile chain, which is about 200 s on its own. This is the step that reaches
   five minutes, and it moves where the testing tiers live, so its shape is decided at this
   step's design fork with `docs/architecture/how-to/testing.adoc` updated to match.

The walk-comparison sweeps (`InputOccurrenceShadowTest`, `ColumnMatchShadowTest`,
`RoutineSpentInputShadowTest` and their kin, which compare the store against the classification
walk) retire with the walk under R876; that removal counts toward step 4 when it lands, and this
item does not wait for it.

## Measuring

The `build-profile` skill owns the method; this item follows it:

* Whole-build figures come from a forked, recorded `mvn clean install` on a machine whose load
  average is under 3 at the start, with the load recorded.
* A step's saving is priced by interleaved pairs, before and after, discarding the first pair,
  and quoted with its spread.
* Inside a test phase, a recording run unforked locates where time goes; it never decides what to
  delete.

## Tests

* The acceptance is the measurement itself: a recorded clean `mvn install` under five minutes on
  the reference machine, by the method above, with the same gates as the 2026-10-30 baseline and
  per-module surefire test totals no lower than it, except where a test retired with the walk.
* Step 1 adds a test that pins the statement reuse it relies on: capturing a fixed schema twice
  into one store prepares no statement a second time, and a reader's second identical read
  prepares nothing, so a regression back to re-preparing fails the build rather than slowing it.
  `CorpusStoreTest`'s case that a write inside a read never lands becomes a case that it is
  refused, and gains one for DDL.
* Step 2 is proved by a deliberately dangling `{@link}` failing the compile, then removed.
* Step 3 is proved by two consecutive `-T` builds passing.

## Other solutions we've considered

* **Raising JUnit parallelism.** The `graphitron` phase already uses 90% of the machine's cores
  at four concurrent classes; more workers only queue for the same CPU.
* **Skipping the javadoc gate locally.** It is the only check of `{@link}` validity, and the
  verification build owes it. Moving it into javac keeps the check and drops the cost.
* **Dropping or sampling tests.** Out of scope by the goal: coverage stays what it is.
* **Keeping the reader's rollback and only raising the cache.** The rollback empties the cache
  on every read, so readers would keep preparing everything afresh; the select-only user gives
  the read-only guarantee without that cost.
* **Marking the reader's connection read-only through JDBC.** H2 treats
  `Connection.setReadOnly` as a hint and enforces nothing.

## Reviewer findings

### Round 1: Spec → Ready (session_01WkW2JJcostW9nAqBvWsyhd, 2026-10-30)

Verdict: withhold. The goal reads cleanly without the phase list, steps 1 and 2 extend shapes already
in the tree, and the claims about `StoreReader.read` (ends in `rollback()`), the `check-link-references`
execution (`javadoc-no-fork`, `doclint` `reference`) and the store URLs check out. Two findings on
question one (is the goal viable), one on question two (fit).

**Finding 1 (question one: viability). Nothing in the item shows that steps 1 to 7 can reach five
minutes, and its own diagnosis argues they cannot by rearrangement alone.** The body says the
`graphitron` phase is CPU-bound (90% of 16 cores) and that "the build projects to about ten minutes"
after steps 1 to 6. Step 7 then closes the remaining five minutes by overlapping the heavy suites with
the compile chain. Overlap moves work in time; it does not remove it. The heavy suites already
saturate the cores, so running them beside the compile chain competes for the same CPU. The item needs
a CPU budget: total core-seconds of the baseline (test phases at their measured utilisation, plus
everything else), divided by 16, as the floor on wall clock for any scheduling. If that floor is above
300 s, the plan must name which steps cut core-seconds (step 1 does; steps 4, 5 and 6 may) and by how
much, and say that step 7 only reaches five minutes after they have. If the floor is below 300 s the
item should say so, with the figure. Without it, the goal rests on the one step the spec leaves to a
future design fork.

**Finding 2 (question one: viability). Step 3 is largely in place and the baseline it prices does not
match it.** CI already builds with `-T 1C` (`.github/workflows/rewrite-build.yml`), CLAUDE.md says the
test suite supports module parallelism, and `GraphitronModelStore` already handles two modules reaching
`openAt` concurrently. "The reactor runs one module at a time" and "up to 150 s" describe a plain local
`mvn install`, which the goal names as the target. State which build the five minutes is measured on
(plain `mvn install`, or `-T`), and restate step 3 as what is actually missing: the collision audit the
body lists (Quarkus port, temp directories, leaf-coverage trace file, store files) for the modules that
CI's `-T 1C` may not yet exercise together, and making the parallel invocation the documented default.
Recheck its saving against Finding 1's floor, since the item itself says parallelism competes for the
same cores.

**Finding 3 (question two: fit). Step 1's reader user is created "when it boots a store", but a warm
file-backed store is not booted.** `openAt` reuses an existing file under the stamped directory; if the
`CREATE USER` and `GRANT` run only in the `create(connection)` path, a reopened store has no reader and
`reader(...)` fails on connect. The spec should say the user is ensured on every open (`CREATE USER IF
NOT EXISTS` plus the grant, idempotent) and name its credential, since the reader URL will carry one the
writer's does not. It should also say what `QUERY_CACHE_SIZE` on the reader URL does when the database is
already open: it is a database-level setting the reader should inherit rather than state, and the
step's test (second identical read prepares nothing) should run against a reopened file store as well as
an in-memory one.

Not blocking, a correction the author can make in passing: the Tests bullet names `CorpusStoreTest`'s
write-inside-a-read case; the class lives under `graphitron-model/src/test/.../model/test/`, so step 1's
test belongs beside it rather than in `graphitron`.

### Round 2: Spec → Ready (session_01WkW2JJcostW9nAqBvWsyhd, 2026-10-30)

Verdict: approve. Finding 1 is withdrawn: the item prices every step by measurement and lands each on
its own, so whether five minutes is reachable is settled by doing the steps, and a stretch goal reached
by iteration is a sound goal. Finding 2 is not a blocker: the implementer states which build the figure
is measured on when step 3 is priced. Finding 3 is an implementation detail of step 1, not a gap in the
design: the implementer ensures the reader user on every open (not only at create) and tests the reuse
against a reopened file store too.
