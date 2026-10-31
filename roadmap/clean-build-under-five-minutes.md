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

## Implementation

Each step is measured before it starts and priced after it lands, and lands on its own; the
numbering is the order to take them in, largest and cheapest first.

1. **Stop preparing the same statement twice.** Two changes, both in `GraphitronModelStore` and
   both production code, so real captures (the sis schema, the consumer modules) and the
   long-lived stores of `graphitron:dev`, the LSP and MCP gain as well as the tests:
   * **A statement cache that holds a capture.** Set `QUERY_CACHE_SIZE=1024` on the store's
     connection URLs (in-memory and file-backed): room for the roughly 600 distinct statements a
     capture and its reads run, with headroom as the store grows. A larger cache buys nothing,
     the statement set being bounded by the code.
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

   Landed. The size is 1024, and the cost is memory: about 50 KB a cached statement, filled up to
   the roughly 600 a capture runs, so about 30 MB more per captured store than H2's default of 8
   (40.7 MB to 70.7 MB measured over six stores held at once); sizes from 700 up cost the same, and
   64 or 128 cost only 3 to 5 MB but, a capture cycling through its statements in order, should
   save nothing. A JVM that keeps dozens of stores alive does not afford that: `graphitron-lsp`'s
   tests ran out of heap at 700 and above and pass at 8, so its surefire configuration sets
   `graphitron.queryCacheSize=8`. One before-and-after pair on `FactExpectationTest` (forked, load
   under 2 at the start of each, cache at 2048): 297.0 s before, 235.7 s after, 21% off. A single
   pair, not yet the interleaved set `## Measuring` asks for, so the spread is still owed.
   Findings from landing it: `QUERY_CACHE_SIZE` has no `SET` form and is not persisted, only a URL
   setting read when the first connection opens the database, so the writer's URL states it and a
   reader inherits it; and a select-only reader can still create session-local temporary tables,
   which touch nothing the writer owns.
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
4. **Shorten the corpus chain.** Capture the corpus once per build into a store file the tests
   open, or capture its graphs in parallel, so the sweeps start immediately. Replace the
   `@Isolated` count assertion in `CorpusCapturedOnceTest` with one that does not need the JVM to
   itself. Expected: about 100 s from `graphitron`.
5. **Split `FactExpectationTest`.** Turn its document-driven cases into ones the test pool can
   spread across workers, and make `CorpusExpectations.compare` cheaper. Expected: about 100 s
   from `graphitron-model`.
6. **Boot test stores from a template.** `GraphitronModelStore.create` replays the creation
   statements of all 442 relations for each store (7.7% of `graphitron`'s samples). Boot once and
   copy, or reuse a booted store through the reset `ThreadConfinedStore` already performs in
   `graphitron-model`. Expected: about 30 s.
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
