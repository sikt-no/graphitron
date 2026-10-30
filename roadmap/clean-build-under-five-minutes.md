---
id: R1131
title: "A clean build runs in under five minutes"
status: Spec
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
   of test samples. The same statements are prepared again and again. H2's per-session statement
   cache holds 8 statements by default (`QUERY_CACHE_SIZE`), and whether jOOQ binds or inlines
   values decides whether two executions even share SQL text.
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

1. **Stop preparing the same statement twice.** Establish, per store reader and writer, whether
   jOOQ renders bind values or inlined literals (`Settings.statementType`), raise H2's
   `QUERY_CACHE_SIZE` on the store's connection URL (`GraphitronModelStore`), and check whether
   the test stores' resets (`ThreadConfinedStore`, the truncations between cases) invalidate the
   cache through H2's modification counter. Where a hot statement is built per row or per graph,
   prepare it once and rebind. This step changes production code: real captures (the sis schema,
   the consumer modules) prepare the same statements, so they speed up too. Expected: 30 to 45%
   off every store-heavy test phase, about 250 to 300 s, and the matching share of allocation.
2. **Check `{@link}` references in the compiler.** Replace the `javadoc-no-fork` execution
   (`check-link-references`) with javac's own `-Xdoclint:reference`, excluding the generated jOOQ
   packages that the javadoc sourcepath reads today, so the reference gate costs nothing beyond
   compiling. The gate keeps its meaning: a dangling `{@link}` or `{@see}` fails the build.
   Expected: about 150 s.
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
   about nine minutes: the compile chain still waits for each module's tests before the next
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
* Step 1 adds a test that pins the statement reuse it relies on, for example that a capture of a
  fixed schema prepares each distinct statement a bounded number of times, so a regression back to
  re-preparing fails the build rather than slowing it.
* Step 2 is proved by a deliberately dangling `{@link}` failing the compile, then removed.
* Step 3 is proved by two consecutive `-T` builds passing.

## Other solutions we've considered

* **Raising JUnit parallelism.** The `graphitron` phase already uses 90% of the machine's cores
  at four concurrent classes; more workers only queue for the same CPU.
* **Skipping the javadoc gate locally.** It is the only check of `{@link}` validity, and the
  verification build owes it. Moving it into javac keeps the check and drops the cost.
* **Dropping or sampling tests.** Out of scope by the goal: coverage stays what it is.
