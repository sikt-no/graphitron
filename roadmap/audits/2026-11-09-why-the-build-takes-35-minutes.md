# Why the verification build takes 35 minutes: a re-profile at current trunk

A working document, not a roadmap item; it lives in `audits/` so the roadmap-tool ignores it. It
re-asks `2026-11-02-where-the-build-spends-its-cpu.md`'s question 420 commits later, on the machine
the 35 minutes was reported from. The work findings feed R876 (`### A dev round costs what its
reads' shapes ask`); the configuration and scheduling findings feed R1131, which owns the
five-minute target and has had no commit since 2026-11-02.

## Headline

1. **The build reproduces: 34:36 wall, `BUILD SUCCESS`, every test passing**, one sequential
   `mvn clean install` at `f6403c10a`, no `-Plocal-db` (Testcontainers PostgreSQL).
   `graphitron` alone is 18:10 of it. The 35 minutes reported was not recorded, so the command
   is the one this machine's local setup prescribes; the match is consistent with it, not proof.
2. **The machine is a laptop, not the 16-core workstation R1131's target names.** An Intel Core
   Ultra 7 165U: 15 W, 2 performance cores, 8 efficiency cores, 2 low-power cores, 14 threads.
   The build burned 7,366 CPU-seconds in its own processes; spread perfectly over all 14 threads
   that is 526 s, so five minutes is out of reach here without cutting the work, whatever the
   configuration does.
3. **63% of the machine sat idle.** The reactor is sequential and every store-heavy test JVM runs
   a JUnit pool fixed at 4, so the test JVMs ran at 3.8 to 6.3 cores.
4. **About 600 s, 29% of the wall clock, is four serial tails running at one to one and a half
   cores**: `EmittedRegistryAgreementTest` alone for the last ~220 s of `graphitron`'s test
   phase, the `generate-test-schemas` exec (183 s), `GeneratorDeterminismTest` (120 s, all of
   `sakila-example`'s test window) and `GraphCountGrowthGateTest` (the last ~70 s of the model's).
5. **The work is still H2 preparing statements, but it moved.** 90% of the `graphitron` test JVM's
   Java samples are inside H2 and about 70% are parse and plan; execution is 13%. Three of the six
   statements the 2026-11-02 audit named are gone. In their place, **two statements in
   `PathNeeds.write` are each 12.3% of that JVM, entirely preparation**, and
   `OccurrenceSources.write` is another 11%. Both are steps of the per-depth occurrence loop that
   landed 2026-11-04 to 11-08.
6. **The instrument the derivation work is tuned against cannot see this cost.**
   `WriteTimes`, which fills `store_graph_write` and `store_graph_read` (and so MCP `status`, the
   `graphitron:dev` line and `GraphCountGrowthGateTest`), starts its clock at jOOQ's
   `executeStart`, after H2 has parsed and planned. A derivation change can look free in every
   number the store records and still add minutes to the build.

## Method

| Instrument | What it gave |
|---|---|
| JFR in the Maven JVM (`MAVEN_OPTS`, `settings=default`, the repo's JFR Maven extension) | Module and goal windows; mojo sum 2,074 s against 2,076 s of reactor, so the reactor is sequential and the windows exclusive |
| JFR in the surefire forks (`-DargLine=-XX:StartFlightRecording=filename=<dir>/,...`) | Execution samples for the `graphitron` (129,425 samples), model (45,583) and `sakila-example` forks; the LSP, MCP and plugin forks wrote none, as on 2026-11-02 |
| `/proc/stat` every 2 s | System CPU per window |
| `ps` every 5 s over every java, postgres and docker process, with parent pids | CPU per process; the build tree separated from everything else on the box |
| Surefire XML (`time`) with file mtime as the class's end | Per-class windows and classes in flight; surefire 3.6's XML carries no timestamp |
| The `sakila-example` store the run left behind, copied | `store_graph_write` and `store_graph_read` for a production capture |
| Source reading, a five-agent survey and a critic that spot-checked each claim | What each hot frame's statement is, and why it is costly |

**The run was not clean, and the shares are more robust than the absolute times.** Of 10,891
busy CPU-seconds, 7,366 were in the build's process tree; two Maven JVMs outside it took 811 (at
1,155 to 1,585 s), at least one of them another session's, and 2,540 were unattributed, mostly this
investigation's own survey agents during the first 20 minutes. The tree walk also files the
invoker's shell-launched child JVMs as outside it, about 130 CPU-seconds, so the build's own figure
is a little low. The test phases were pool-bound with idle cores to spare, so the
contention mostly cost turbo headroom on a power-limited chip; the serial tails, which are
single-thread bound, are the figures it inflates most, by an unknown few percent.

## Where the wall clock goes

| Module | Wall s | Build-tree cores in its test JVM | Largest goals |
|---|---:|---:|---|
| `graphitron` | 1,091 | 3.85 | tests 883, `generate-test-schemas` exec 183, test compile 19 |
| `graphitron-model` | 323 | 4.69 | tests 281, compile 30, jOOQ codegen 9 |
| `graphitron-sakila-example` | 227 | 3.77 | tests 131, eight `graphitron:generate` 67, compile 15 |
| `graphitron-lsp` | 182 | 4.99 | tests 177 |
| `graphitron-maven-plugin` | 127 | 6.30 | invoker 70 (13 sequential forked builds), tests 49 |
| `graphitron-mcp` | 55 | 6.13 | tests 51 |
| roadmap-tool, docs, sakila-db, the rest | 69 | | |
| **reactor** | **2,076** | 5.24 system-wide | |

The same goals across the three recorded builds. They are three machines as well as three trunks,
so read them for shape, not as a regression series:

| Goal | 2026-10-30, 16-core, pool uncapped | 2026-11-02, 4-core VM | 2026-11-09, this laptop |
|---|---:|---:|---:|
| `graphitron` tests | 355 | 774 | 883 |
| `graphitron-model` tests | 332 | 367 | 281 |
| `graphitron-lsp` tests | 72 | 218 | 177 |
| `graphitron-sakila-example` tests | 85 | 136 | 131 |
| `generate-test-schemas` exec | 52 | 109 | 183 |
| invoker | 56 | 103 | 70 |
| **whole build** | **1,389** | **2,238** | **2,076** |

This laptop behaves like the 4-core VM, which is what a pool fixed at 4 on a sequential reactor
predicts. The model's phase is faster than the 16-core baseline; what grew is `graphitron`'s test
phase and the `generate-test-schemas` exec, which now loops over about 80 scenarios, 2.2 times as
many, one cold in-memory store each, in one thread.

### The serial tails

| What | Wall s | Shape |
|---|---:|---|
| `EmittedRegistryAgreementTest` | 229, ~220 alone | One test method looping over every corpus document: per document the walk's bundle, a cold `CapturedStore`, the emitted registry, and a printed comparison. Its javadoc says "about a minute". It ran last and alone, from 1,206 to 1,435 s, while the box idled |
| `generate-test-schemas` exec | 183 | One JVM, a sequential loop over ~80 scenarios, each booting a fresh store from the 19,066-line DDL and capturing the same catalog and classpath again; output deleted every run, nothing cached. ~1.3 build cores |
| `GeneratorDeterminismTest` | 120 | 3 tests spanning the whole of `sakila-example`'s 124 s test window |
| `GraphCountGrowthGateTest` | 81, ~70 alone | One test, the last class of the model's phase |

`graphitron`'s phase holds 472 classes summing to 2,900 s of class time over an 879 s window, 3.3
in flight; the model 954 s over 279 s (3.4); LSP 683 s over 175 s (3.9). The pool is full until the
tails, then it is one class.

## Where the CPU goes inside the test JVMs

Shares of Java execution samples, categories inclusive and overlapping:

| | `graphitron` fork | model fork |
|---|---:|---:|
| Process CPU (ps) | 3,380 s over 879 s | 1,304 s over 278 s |
| Any `org.h2` frame | 90.2% | 89.4% |
| Parser (`org.h2.command.Parser`) | 66.9% | 58.9% |
| Plan search (`Query.prepare`, `getBestPlanItem`, `Optimizer`) | 69.8% | 62.0% |
| View expansion (`TableView`, `ViewIndex`) | 24.6% | 25.9% |
| Execution (`queryWithoutCache`, insert, merge) | 13.2% | 18.8% |
| Store boot (`GraphitronModelStore.create`) | 3.6% | 4.4% |
| `ANALYZE` | 0.8% | 2.2% |

Top self frames in `graphitron`: `ConditionAndOrN.setEvaluatable` 12.7%, `Comparison.setEvaluatable`
7.3%, `MVSecondaryIndex.getCost` 4.5%, `TableFilter.getBestPlanItem` 3.3%: the optimizer costing
every condition for every join order it tries.

### Which statements

The graphitron frame nearest H2 in each sample, by source line, at `f6403c10a`:

| Statement | `graphitron` fork | of which preparing | model fork |
|---|---:|---:|---:|
| `PathNeeds.write:220`, upsert into `graphitron_path_need` from the rule | 12.3% | 100% | 19.6% (both lines) |
| `PathNeeds.write:259`, upsert into `graphitron_path_need_table` from the same rule | 12.3% | 100% | |
| `BindBatch.execute:72`, batched row writes | 8.2% | 2% (81% executing) | 5.6% |
| `OccurrenceSources.write:125,133,152,177`, four statements over one rule | 11.3% | 97 to 100% | 8.7% |
| `TypeBackings.derive:45` | 4.6% | 100% | 4.4% |
| `EntryDefects.derive:108` | 4.2% | 100% | 4.1% |
| `GraphitronModelStore.create:821`, boot DDL | 3.1% | 57% | 3.8% |
| `Steps.writeColumns:724` | 2.8% | 99% | 2.4% |

In CPU, `PathNeeds` and `OccurrenceSources` together are about 1,200 CPU-seconds in `graphitron`
and 370 in the model: roughly a fifth of the build's own CPU, and all of it planning.

### Why those two are expensive

Read from the code; the shape is the finding, the attribution above is the evidence.

- **Each is a large union planned more than once per depth.** `PathNeeds.candidates` is a union of
  three arms at depth 0 and eight at deeper depths, each naming three to eight tables counting its
  `EXISTS` subqueries. `write` wraps it as a derived table and inlines it into two upserts, so H2 parses and
  plans the whole union twice. The class says "the rule is read once per relation", which holds for
  execution and not for planning. `OccurrenceSources.candidates` is a union of three arms at depth 0
  and fifteen deeper, all arms but one carrying a `NOT EXISTS` for services that hand back records, inlined
  into four statements (a delete with `EXISTS` and three upserts).
- **The per-depth loop multiplies it, and the cache cannot help.** `OccurrenceDepths.derive` runs
  producers, sources, needs, accessor paths, paths and steps once per depth: three depths for a small
  fixture, five for sakila (from `store_graph_write`'s statement counts). Depth and instant are bound
  parameters, so from depth 1 on each statement's text repeats. But a test's own store and
  `generate`'s are `StoreUse.ONE_CAPTURE`, an 8-statement cache, and one depth runs far more than 8
  statements, so every depth re-prepares everything. `StoreUse`'s javadoc reasons that "the first
  capture saves nothing at all: every statement in it is new", which was true before the loop and
  is not now.
- **The relations are tables, not views.** None of the relations `PathNeeds` reads is a view, so
  this is not the view-expansion cost the 2026-11-02 audit found. It is join-order search over wide
  unions: H2 2.5.250's `Optimizer` brute-forces every order for up to 7 tables
  (`MAX_BRUTE_FORCE_FILTERS = 7`) and costs every condition for each, which is what the self frames
  show.
- **It is a test-suite cost more than a production one.** In `sakila-example`'s first `generate`
  capture (19.7 s), `graphitron_path_need` and `_table` take 272 ms of execution and the `Results`
  round's three relations take 11.3 s. The suites run thousands of small captures, where a fixed
  planning cost per capture dominates and execution is nothing; a large capture is the reverse.

### The instrument's blind spot

`WriteTimes.executeStart` stamps the start and `end` reads it. jOOQ calls `prepareStart` and
`prepareEnd`, where `Connection.prepareStatement` makes H2 parse and plan, before
`executeStart`. So every figure in `store_graph_write`, `store_graph_write_stage` and
`store_graph_read` excludes preparation. The sakila store shows it: `graphitron_path_need` at 1 to 9
ms per small capture, while the same statements are a quarter of the `graphitron` JVM. Timing from
`prepareStart` (or recording prepare separately) would put the dominant test-suite cost in the
place the derivation work already looks.

## Levers, ranked for this machine

They overlap; the bounds do not add.

| # | Lever | Bound | Confidence | Owner |
|---|---|---|---|---|
| 1 | Break up the serial tails: run `EmittedRegistryAgreementTest`'s documents in parallel or as a dynamic test per document (it is a gate meant to be deleted with the second producer), the `generate-test-schemas` scenarios in parallel, and the same for `GeneratorDeterminismTest` and `GraphCountGrowthGateTest` | up to ~600 s of wall, near one core each today | high that the time is there; the achievable part depends on the parallel pool's headroom | R1131 |
| 2 | Plan each per-depth rule once: write `candidates` into a staging table once per depth and let the two (`PathNeeds`) or four (`OccurrenceSources`) statements read the table. Use a permanent table emptied by `TRUNCATE`, since DDL and `SET` flush every session's statement cache. A stored relation owes an owner, a grain and a mark and sweep, and the stage gates will see it, so budget for the discipline as well as the SQL | about half of `PathNeeds`' and three quarters of `OccurrenceSources`' planning, ~800 to 900 CPU-s | medium; inferred from the shape, not measured | R876 |
| 3 | Size the capture cache for the loop: `ONE_CAPTURE` holds 8, a depth runs more, so depths 1 and on re-prepare what they could reuse | the repeats of every per-depth statement, roughly a third to a half of their planning at 3 to 5 depths | medium | R876 (`StoreUse`), with R1131 step 1's memory reasoning |
| 4 | Time preparation in `WriteTimes` | no time; it makes 3 and 2 visible and keeps the next one from going unseen | high | R876 |
| 5 | A pool sized to the machine instead of fixed at 4, and a parallel reactor (`-T 1C`; LSP, MCP, roadmap-tool and docs are off the critical path, ~290 s here) | up to the idle 63%, less the memory a wider pool needs (the `graphitron` fork reached 9.4 GB RSS at 4) | low on this chip, untested; the E-cores and a 15 W limit make core count a weak guide | R1131 |
| 6 | The harness clear's two `SET REFERENTIAL_INTEGRITY` flush the per-thread store's 1,024-entry cache before every borrowed case | the borrowed cases' repeat planning; the 2026-11-02 audit measured 3.15 s per case against 1.2 to 1.35 s chained | medium | R1131 / R1160 |

Lever 1 is the only one that moves this laptop's wall clock a lot without touching the model.
Levers 2 to 4 are where the derivation work can make the build cheaper everywhere, and 4 is what
lets that work see its own cost.

## What was not measured

- Per-statement prepare times. This run had no statement listener; the shares come from sampling.
- CPU-seconds per statement. Those above multiply a share of Java execution samples by the
  process's CPU; garbage collection and the JIT are not sampled, so "a fifth of the build's CPU" is
  nearer 15 to 18%.
- The pool at more than 4 on this chip, and the heap it would need.
- The LSP, MCP and plugin forks' CPU breakdown: they wrote no recording.
- Whether levers 2 and 3 deliver their bounds; both are inferred from code and samples.
- Trunk since `f6403c10a`: 25 files moved before this was written, none in `graphitron-model`'s
  derivation package.

## Where readings were wrong

1. **This investigation contaminated its own baseline** by running survey agents during the timed
   build, and the `ps` sampler matched only java, postgres and docker, so their CPU is visible only
   as an unattributed residue. Do not run subagents during a timed build; sample every process.
2. **The first hypothesis, that the pool cap explains 23 minutes becoming 35, is not what the data
   says.** The cap explains the idle machine; the model is faster than its uncapped baseline. What
   grew is `graphitron`'s test phase, the exec and the tails, and the baseline was a different
   machine.
3. **Two claims in the 2026-11-02 audit do not hold at H2 2.5.250.** H2's view-index cache is a
   fixed 64 entries (`Constants`), not sized by `QUERY_CACHE_SIZE`. And the harness reset's cache
   flush comes from `SET REFERENTIAL_INTEGRITY`, not from `ALTER TABLE ... SELECTIVITY`, which with
   `ANALYZE` and `TRUNCATE` leaves the cache alone.
4. **The store's own timings understate the derivation's cost**, as above. A change judged by
   `store_graph_write` can look free and cost minutes.

## Reproducing

```bash
export JAVA_HOME=~/.local/share/mise/installs/java/25.0.2   # the system JDK 25 lacks ct.sym
export MAVEN_OPTS="-XX:StartFlightRecording=filename=$OUT/jfr/,settings=default,dumponexit=true \
  -XX:FlightRecorderOptions=stackdepth=256"
mvn clean install -Dmaven.test.failure.ignore=true \
  "-DargLine=-XX:StartFlightRecording=filename=$OUT/forks/,settings=default,dumponexit=true"
```

A directory as the recording's `filename` gives each JVM, the invoker's child builds among them, its
own file. Beside it, `head -1 /proc/stat` every 2 s and `ps -eo pid,ppid,etimes,times,rss,args`
every 5 s, with no filter on the process name. Samples were attributed by streaming
`jfr print --stack-depth 256 --events jdk.ExecutionSample` and taking, per sample, the first
`no.sikt.graphitron` frame below any `org.h2` frame, with its line number. Module windows come from
the extension's `ProjectEvent`s; the clock agreed with the system's to within 2 s this time.

To price lever 5 on another machine, interleave arms on the model's phase (281 s here), first pair
discarded:

```bash
mvn surefire:test@default-test -pl graphitron-model \
  -Djunit.jupiter.execution.parallel.config.fixed.parallelism=N \
  -Djunit.jupiter.execution.parallel.config.fixed.max-pool-size=N
```
