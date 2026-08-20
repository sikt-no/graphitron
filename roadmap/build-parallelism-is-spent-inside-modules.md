---
id: R763
title: "mvnd points at the reactor graph, but the build already spends the box inside single modules"
status: Backlog
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# mvnd points at the reactor graph, but the build already spends the box inside single modules

Every `mvnd` build ends with a notice naming "bottleneck projects that decrease concurrency" and
pointing at a system property for details. Turning the property on prints a critical path through the
reactor and a list of modules that ran alone, which together read as an invitation to break module
dependencies and let more modules run at once. Measured, that invitation is worth nothing here: the
parallel build is **340 seconds** and the same build forced sequential is **339 seconds**. Five of
the six modules on the critical path already run their own test classes on four threads, so a second
module running beside them takes cores away rather than adding them. What *is* unspent is the
opposite of what the notice suggests, and it is large: two modules on the critical path run
essentially single-threaded, **121 of the 339 seconds**, with three cores idle throughout. This item
is about those two, and about not paying for the reactor-shape work the notice invites.

## First, what the notice actually says

The notice comes from the Takari smart builder that mvnd uses as its scheduler. Two of its terms
mislead if read as properties of the reactor.

**"Maximum degree of concurrency" is the thread count, not a graph property.** The summary line reads
`effective/maximum degree of concurrency 1.38/3`. The 3 is not "three modules is all this graph
permits"; it is "three threads is what you gave me". Probed directly on the same reactor, `mvnd
validate` reports `/3`, `-T4` reports `/4`, and `-T8` reports `/8`. So mvnd's default on this box is
one thread per core minus one, and the figure carries no information about the dependency graph. The
effective figure, 1.38, is service time over wall clock: the average number of modules in flight.

**"Bottleneck" means a module that ran with no company**, and the notice's advice that "removing
bottlenecks improves overall build time" is true only where the idle cores are real. Here the
bottleneck list totals 210 seconds of the 340, and for two of the four entries the cores are not idle
at all: the module is using them itself.

The property is `-Dsmartbuilder.profiling=true`, and it is worth knowing about. The critical path it
prints is the useful half of the output, and the rest of this item is built on it.

## The matched pair, which settles the reactor question

Same tree, same sandbox, one 4 vCPU 15 GB machine, warm local repository, `mvnd install -Plocal-db`
with profiling on. Every figure in this item was taken on trunk at `660aba6`. CI runs the same reactor
on `ubuntu-latest`, also 4 vCPU, at `-T 1C`, so the shape transfers.

| Run | Wall clock | Service time | Effective concurrency |
|---|---|---|---|
| default, 3 threads | 340 s | 470 s | 1.38 |
| `-T1` | 339 s | 339 s | 1.00 |

Parallelism bought **one second**. It did not fail to find work; it found work and paid for it in
full. Service time rose 39%, from 339 s to 470 s, because concurrency between modules is not
additional throughput on a box the modules already saturate.

Per module, sequential against parallel:

| Module | `-T1` | 3 threads | Change |
|---|---|---|---|
| `graphitron-model` | 46.8 s | 52.8 s | +13% |
| `graphitron` | 78 s | 97 s | +24% |
| `graphitron-lsp` | 35.6 s | 63.0 s | +77% |
| `graphitron-mcp` | 26.9 s | 63.0 s | +134% |
| `graphitron-maven-plugin` | 32.8 s | 32.6 s | 0% |
| `graphitron-sakila-example` | 88 s | 92 s | +5% |
| `graphitron-roadmap-tool` | 9.5 s | 15.8 s | +66% |
| `graphitron-docs` | 15.6 s | 45.6 s | +192% |

The two modules that did not inflate are the two that ran alone in both runs. Everything else paid
for its company.

The clearest single illustration is the `graphitron-docs` row. Sequentially, `graphitron-model`,
`graphitron`, `graphitron-roadmap-tool` and `graphitron-docs` take 46.8 + 78 + 9.5 + 15.6 = **149.9
s**. In parallel, mvnd fits `roadmap-tool` and `docs` inside `graphitron`'s window, and the four take
52.8 + 97 = **149.8 s**. Dead even. `docs` went from 15.6 s to 45.6 s to save `graphitron` nothing.

## The critical path, and how much of the build is off it

From the sequential run, where each module's figure is its uninflated service time:

| Module | Service time |
|---|---|
| `graphitron-rewrite-parent` | 0 s |
| `graphitron-model` | 46 s |
| `graphitron` | 78 s |
| `graphitron-lsp` | 35 s |
| `graphitron-maven-plugin` | 32 s |
| `graphitron-sakila-example` | 88 s |
| **total** | **283 s** |

283 s of a 339 s build. Only 56 seconds of work, 17%, sits off the critical path, and that is the
whole budget any amount of inter-module parallelism can ever recover. The ceiling is 339/283 =
**1.20x**, on a machine with as many cores as you like.

In the parallel run the profiler reports the critical path at 340 s against a 340 s wall clock, which
is to say the path had no slack at all: at no moment was the build waiting on anything but the path.

## Where the concurrency is actually unspent

The measure is each module's summed surefire class spans divided by its wall clock. Above 1 means
test classes overlapped; near 0.5 means the module spent half its time outside surefire and ran that
half on one thread. Sequential run, so nothing else was competing.

| Module | Class-time sum | Wall clock | Overlap |
|---|---|---|---|
| `graphitron-lsp` | 231.4 s | 35.6 s | 6.5x |
| `graphitron` | 380.2 s | 78 s | 4.9x |
| `graphitron-model` | 182.3 s | 46.8 s | 3.9x |
| `graphitron-mcp` | 82.1 s | 26.9 s | 3.1x |
| `graphitron-maven-plugin` | 15.5 s | 32.8 s | **0.5x** |
| `graphitron-sakila-example` | 38.8 s | 88 s | **0.4x** |

The top four are the reason the matched pair came out even. The bottom two are this item.

### `graphitron-sakila-example`, 88 s, one thread nearly throughout

Per-goal timings, taken with `mvn` and the SLF4J timestamp switches so each goal's start is stamped.
The module totals 93.6 s built on its own.

| Goal | Time |
|---|---|
| `graphitron:generate` (`rewrite-generate`) | 15.4 s |
| `graphitron:generate` (`rewrite-generate-federated`) | 4.8 s |
| `graphitron:generate` (`rewrite-generate-multitenant`) | 4.3 s |
| `graphitron:generate` (`rewrite-generate-multischema`) | 4.4 s |
| `graphitron:generate` (`rewrite-generate-multischema-mutation`) | 4.2 s |
| `compiler:compile` | 6.3 s |
| `compiler:testCompile` | 3.0 s |
| `surefire:test`, 805 tests | 44.8 s |
| `jar` plus `quarkus:build` | 3.1 s |
| everything else | 3.3 s |

Two things stand out.

**The 805 tests run one at a time.** The module has no `junit-platform.properties`, so no class-level
parallelism. 44.8 s of the critical path on one core.

**Five `generate` executions run one after another**, 33.1 s in total, on schemas that write to
disjoint output packages and share nothing but the module. Note the shape: the first costs 15.4 s and
the other four cost 4.2 to 4.8 s each. They run in one Maven JVM with one plugin classloader, so the
store boot and the classpath census are already amortised across the five; what a fan-out would
recover is the 16.7 s the four warm executions spend, not the full 33.1 s.

### `graphitron-maven-plugin`, 32.8 s, likewise

| Goal | Time |
|---|---|
| `plugin:descriptor` (`default-descriptor`) | 3.8 s |
| `plugin:descriptor` (`mojo-descriptor`) | 3.5 s |
| `surefire:test`, 112 tests | 7.4 s |
| `invoker:run`, 3 integration projects | 15.1 s |
| everything else | 2.9 s |

**The descriptor goal runs twice.** The `maven-plugin` packaging lifecycle binds `descriptor` to
`process-classes` as `default-descriptor`, and the pom declares a second execution, `mojo-descriptor`,
for the same goal with no phase, which attaches to the same phase. 3.5 s of the critical path for a
second copy of a file that was just written.

**Three integration projects run one at a time**, 15.1 s, each forking a Maven build of its own.
`maven-invoker-plugin` has a `parallelThreads` setting, and this is the one place in the reactor where
forked child builds could use the idle cores.

## What breaking dependencies would buy, for completeness

Two edges on the critical path are **test-scope only**, which means the downstream module's own
compilation does not need the upstream at all and waits for it purely because Maven schedules whole
module lifecycles: `graphitron` to `graphitron-lsp`, and `graphitron` to `graphitron-mcp`. Both
modules' main code needs only `graphitron-model`. Their tests genuinely use the generator, and not as
fixtures: `GraphQLRewriteGenerator`, `JooqCatalog`, `ClasspathScanner`, `CompileRound`,
`ValidationReport` and `CompletionData` are all real production surfaces exercised there. So the edge
is real; it is only its *granularity* that is wasteful.

Removing `graphitron-lsp` from the path takes it from 283 s to about 248 s, lifting the unlimited-core
ceiling from 1.20x to 1.37x. That would cost a module split, tests moved away from the code they
cover, and it would buy nothing at all until the build runs somewhere with more cores than the modules
already claim. The two single-threaded nodes above are worth roughly the same and are available now.

The remaining edges are compile-scope and load-bearing: `graphitron-maven-plugin` wires the LSP server
and the MCP server into its `dev` and `mcp` goals, and `graphitron-sakila-example` runs the plugin.
The head of the chain, `graphitron-fixtures-codegen` to `graphitron-sakila-db` to
`graphitron-sakila-service`, costs 2.9 s in total and is not worth an argument.

## One experiment already run, which failed usefully

Dropping the four-thread `junit-platform.properties` into `graphitron-sakila-example` cuts the module
from 92.5 s to 69.2 s, and **fails 36 of 805 tests**. The failures are specific and they name the
work:

### `@QuarkusTest`: the extension keeps per-test bookkeeping in static single slots

Four of the five `@QuarkusTest` classes fail, and the mechanism is visible in
`io.quarkus.test.junit.QuarkusTestExtension`'s own field list:

```
private static java.lang.Class<?> actualTestClass;
private static java.lang.Object actualTestInstance;
private static final java.util.Deque<java.lang.Object> outerInstances;
private static java.lang.Class<?> quarkusTestMethodContextClass;
```

Not a map keyed by class. One slot each, per JVM, non-volatile and unguarded.
`interceptTestClassConstructor` writes them through `initTestState` when a class starts, and every
later callback (`beforeEach`, `afterEach`, the method interceptors) reflects the *currently running
JUnit method* onto whatever `actualTestInstance` holds. With two classes in flight the second
construction overwrites the first, and the first class's callbacks then look up their own method name
on the wrong class and invoke it against the wrong receiver. Both halves of that show in one stack:

```
RuntimeException: Could not find method void TutorialSmokeTest.page3_activeFilter() on test class
  at QuarkusTestExtension.createQuarkusTestMethodContextTuple(QuarkusTestExtension.java:564)
IllegalArgumentException: object of type GraphQLOverHttpConformanceTest
                          is not an instance of TutorialSmokeTest
  at QuarkusTestExtension.interceptAfterEachMethod(QuarkusTestExtension.java:913)
```

Worth being precise about what is *not* broken. Sharing the running Quarkus application across test
classes is deliberate and works; the augmentation cost is why it exists. What is not thread-safe is
the per-test bookkeeping beside it. That is a property of the extension, not of these five tests and
not of anything this repository can fix, so the only lever here is scheduling: exclude the
`@QuarkusTest` classes from each other while leaving them free to overlap everything else.

### `GraphQLQueryTest`: seven cases assert a property of the whole database

This one is ours, and all seven failures are one defect rather than seven. Each is an unfiltered
`films` query asserting an absolute count against the five rows `init.sql` seeds:

```
[omitting the nullable filter must return the unfiltered baseline of 5 films, ...]
Expected size: 5 but was: 7
```

and the extra rows name their author:

```
{"isEnglish"=true, "title"="ACADEMY DINOSAUR"},
...
{"isEnglish"=true, "title"="R144-MULTIROW-A-8a56b78d-94d2-44a0-99b1-529c218d2b2d"}
```

`DmlBulkMutationsExecutionTest` inserts that row. That class is *well* disciplined about isolation:
every marker is UUID-suffixed, every case cleans up in a `finally`, and there is an `@AfterAll` sweep.
It is isolation-safe for its own assertions. The defect is on the reading side: "the query returned
five films" and "the film table holds only the seed" are different claims, and these seven cases make
the second while meaning the first. Sequentially that is true because writers tidy up before the
reader runs. Concurrently the writer's window is visible, and 11 of the 47 `querydb` classes write to
`film`.

So the 23 s is available but not for free. Note the constraint the CI comment on `-T 1C` sets on how
it is taken: "The fix for such a failure is the test, never serializing this build." That reads
differently for the two cases above, which is exactly why they should not be handled with one
mechanism. Excluding classes that a third-party extension makes thread-hostile is scheduling around a
dependency's limitation. Narrowing an over-broad assertion is fixing the test. Reaching for a lock on
the `film` table instead would be the thing the comment forbids: it would hide the over-broad
assertion rather than correct it.

## What a Spec pass has to settle

* **Whether the guardrail R733 wants measures wall clock or the critical path.** They are the same
  number today, which is exactly why the distinction is invisible and worth fixing now: a wall-clock
  budget on a 4 vCPU runner silently conflates "the build got slower" with "the build got wider", and
  a change that trades 20 s of critical path for 40 s of off-path work would read as a regression.
* **How the `@QuarkusTest` classes are excluded from each other.** `@Execution(SAME_THREAD)` is the
  wrong tool and worth naming so nobody reaches for it: it pins a class to its parent's thread and
  says nothing about which *other* classes run beside it. A shared `@ResourceLock` in `READ_WRITE`
  mode is the built-in that expresses "these five are mutually exclusive, and free to overlap the
  other 67". The alternative is a second surefire execution holding just those five, which is heavier
  but keeps the reason in the pom where a reader looks. Either way the same-key discipline has to
  survive the sixth `@QuarkusTest` class somebody adds later, and this repository's habit for that is
  a meta-test that fails the build when a class carries the annotation without the lock.
* **How the seven `GraphQLQueryTest` cases stop asserting a global row count.** The seed is five films
  at ids 1 through 5, so scoping the assertion is available; whether it is a filter in the GraphQL
  document, a narrowed assertion, or a distinct set of fixture rows those cases own is the design
  choice. Note that this defect exists today and is merely unobserved, because nothing runs the module
  concurrently: the assertions are already wrong about what they claim, and enabling parallelism only
  makes them say so.
* **Whether the five `generate` executions become one invocation over several units.** The saving is
  16.7 s, not 33.1 s, because the store boot and census are already shared inside the module's JVM.
  Weigh that against what five separate executions currently document in the pom: each carries a long
  comment explaining which compile-tier property it proves, and one goal taking a list of units must
  not lose that.
* **Whether both `plugin:descriptor` executions are needed.** If the explicit `mojo-descriptor`
  execution predates something, that should be established rather than assumed; if it is redundant,
  it is 3.5 s of the critical path for nothing.
* **Whether `maven-invoker-plugin` `parallelThreads` is safe here.** The three integration projects
  deliberately share one store directory under `target/it-store`, which the pom's comment calls "a
  free extra exercise of the multi-graph store". Running them concurrently exercises something else
  again, concurrent access to one store from three JVMs, and whether that is a feature or a flake is
  the question.
* **Whether the test-scope edges to `graphitron-lsp` and `graphitron-mcp` are worth breaking at all.**
  My reading is no, not on four cores, and the item states the ceiling so that the answer can be
  revisited if CI ever runs wider. The question deserves an explicit verdict rather than silence,
  because the mvnd notice will keep suggesting it on every build.

## How to re-measure

```bash
# The matched pair. Run both arms on the same tree, warm repository, and alternate them
# if you intend to trust a delta smaller than ten seconds.
mvnd install -Plocal-db -Dsmartbuilder.profiling=true
mvnd install -Plocal-db -Dsmartbuilder.profiling=true -T1

# Per-goal timings inside one module. Use mvn, not mvnd: the timestamped SLF4J output
# is what carries the numbers and mvnd reformats it.
mvn install -pl :graphitron-sakila-example -Plocal-db \
    -Dorg.slf4j.simpleLogger.showDateTime=true \
    -Dorg.slf4j.simpleLogger.dateTimeFormat="HH:mm:ss.SSS"

# Whether a module saturates the box: sum the class spans in its surefire XML and divide
# by the module's wall clock. Above 1 means classes overlapped.
```

The absolute seconds are a 4 vCPU sandbox and do not transfer. The ratios do, and the two that matter
are the matched pair coming out even and the two modules sitting near 0.5 overlap.
