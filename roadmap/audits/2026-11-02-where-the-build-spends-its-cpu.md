# Where the build spends its CPU: the verification build, its test JVMs and its statements

A working document, not a roadmap item; it lives in `audits/` so the roadmap-tool ignores it. It
records what was measured while working `roadmap/clean-build-under-five-minutes.md` (R1131), why
the steps priced there moved the build less than their individual figures suggest, and where the
CPU actually goes. Sibling record: `2026-10-19-where-a-dev-round-spends-its-time-on-sis.md`, which
asks the same question of a dev round. The item carries the decisions; this carries the evidence
and the method, including where earlier readings in the same investigation were wrong.

Every figure below comes from a run in that investigation. The machine is a 4-core, 16 GB VM
running Java 25 and Maven 3.9, not the 16-core workstation the item's target names, so absolute
times are about twice a 16-core machine's where a phase is CPU-bound and about equal where the
test pool is the limit. Trunk moved under the work (other items landed derivation changes between
runs); each figure names its commit or says it was taken in an interleaved pair.

## Headline

1. **The build is CPU-bound, and the target is below its CPU floor.** A sequential
   `mvn clean install` burned 7,192 CPU-seconds in 2,372 s of wall clock, 3.03 of 4 cores busy.
   Perfect scaling to 16 cores is 450 s, more than the 300 s target, which needs the work cut to
   about 4,800 CPU-seconds or less. Single-class and single-module savings priced in seconds cannot
   reach that; only a cut in the work itself can.
2. **In the test JVMs 84% of the CPU samples are inside H2, and most of that is preparing
   statements, not running them.** Parsing and planning is about 60% of the `graphitron` JVM's
   samples (41 to 45% in the model's); executing queries is 8% (22%). Garbage collection is 7% and 5%
   of the time in pauses.
3. **The preparation cost is a heavy tail.** Over a full `graphitron` phase the writer made 1.2
   million prepare calls over 23,112 distinct statement texts, 1,169 CPU-seconds preparing against
   390 executing. Six statement shapes, all derivation-stratum `merge into` / `insert into
   graphitron_*`, hold 73% of it (851 CPU-seconds, about 27% of that JVM's CPU and 12% of the whole
   build's).
4. **Those statements are expensive because of the views under them, not their own text.** The
   heaviest has one `SELECT` and no joins and reads two relations directly, but reaches 116
   relations through 18 views; H2 expands a view where it is used and plans the whole expansion.
5. **The statement cache that would remove most of it is flushed, or never warm.** A long-lived cache
   takes a repeat capture's preparation from 1,231 ms to 50 ms, but the harness reset between cases
   empties it (borrowed cases cost 3.15 s each, flat, against 1.25 s chained in one store), and 476
   stores per `graphitron` run start cold.
6. **The test pool ran far more classes at once than its configured four.** About 19 in
   `graphitron`, 10 in the model, 15 in LSP, and 30 distinct threads booted the per-thread store.
   Holding the pool at four took 14 to 34% off those modules' runs.
7. **The wins priced so far are real and small against 7,192 CPU-seconds.** See the accounting below.

## Method, and what each instrument can and cannot see

| Instrument | What it gave | What it cannot see |
|---|---|---|
| JFR with the Maven extension, `settings=default`, plain sequential `mvn clean install -Plocal-db` | Per-goal and per-module wall clock, with the mojo sum checked against the reactor total | The forked test JVMs; the recording's clock was skewed 2,011 s against the system's, so windows were aligned by offsets |
| `/proc/stat` every 2 s | System-wide busy, idle, iowait and steal per module window | Which process spent it |
| `-XX:StartFlightRecording` in the forks via `argLine` | GC pauses, CPU load and execution samples per test JVM | Four forks wrote files: `graphitron`, the model, and two shorter ones (135 s and 34 s) not matched to modules; the other modules' forks wrote none |
| A jOOQ `ExecuteListener` on the store's writer (and readers), timing `prepareStart` to `prepareEnd`, with thread CPU time | Prepare and execute time per distinct statement text | Boot DDL, which runs as raw JDBC; statements prepared outside jOOQ |
| A tally in the store's `create` path | Cold boots, with wall and thread CPU time, and who asked | Anything after the boot |
| Interleaved before/after pairs, first pair discarded, spread quoted | The price of a change | Anything about a machine with more cores |

Two rules that earlier mistakes in this work established. A run that overlaps edits to files the
build reads (the model's codegen reads the SQL) is not a measurement. And wall time on a saturated
machine is not cost: boots measured 3.1 s of wall and 0.39 s of CPU each.

## The CPU budget

One sequential build at trunk `6907df9`, 39:37 wall including the recording; the module windows are
disjoint and sum to the wall clock.

| Module | Wall s | CPU s | Cores busy | Utilization |
|---|---:|---:|---:|---:|
| `graphitron` | 1,037 | 3,494 | 3.37 | 84% |
| `graphitron-model` | 467 | 1,312 | 2.81 | 70% |
| `graphitron-sakila-example` | 264 | 706 | 2.67 | 67% |
| `graphitron-lsp` | 184 | 678 | 3.68 | 92% |
| `graphitron-maven-plugin` | 207 | 512 | 2.48 | 62% |
| `graphitron-mcp` | 72 | 213 | 2.97 | 74% |
| `roadmap-tool` | 55 | 102 | 1.87 | 47% |
| `docs` | 46 | 92 | 2.00 | 50% |
| `graphitron-sakila-db` | 32 | 70 | 2.19 | 55% |
| everything else | about 9 | about 13 | | |
| **total** | **2,372** | **7,192** | **3.03** | **76%** |

Idle capacity was 1,940 core-seconds (20%), 299 stolen by the hypervisor and 78 in iowait. The idle
is where a parallel reactor could help, at best 7,192 / 4 = 1,798 s here, 24% off the wall clock, and
it cannot beat the CPU bound. The module critical path (`sakila-db`, model, `graphitron`, plugin,
example) is 1,845 s of a 2,238 s run in the earlier recorded build.

An earlier recorded build at `9627778` (37:15, every test passing) split the goals the same way:
surefire 1,644 s (74%), `compiler:compile` 123 s, the `graphitron` test-schema exec 109 s (52 s in the
item's first profile), the plugin's invoker run 103 s, `graphitron:generate` 75 s, test compile 48 s.
The javadoc gate is gone from that table.

## Inside the test JVMs

| | `graphitron` fork | model fork |
|---|---:|---:|
| Recorded | 840 s | 372 s |
| Cores used by the JVM | 3.71 | 3.05 |
| GC pause time | 60.8 s (7.2%) | 19.6 s (5.3%) |
| Samples under any `org.h2` frame | 83.9% | 84.0% |
| Under `org.h2.command.Parser` | 63.6% | 44.6% |
| Under plan search (`Select.prepare`, `getBestPlanItem`) | 59.2% | 40.6% |
| Under query execution (`queryWithoutCache`) | 7.8% | 21.6% |
| Under `GraphitronModelStore.create` (boot DDL) | 6.7% | 7.4% |
| javac or other compile | 0.5% | 0 |

The categories are inclusive and overlap. The top self frames in `graphitron` are
`Comparison.setEvaluatable` 8.9%, `ConditionAndOrN.setEvaluatable` 8.6%, `Index.getCostRangeIndex`
4.2%, `TableFilter.getBestPlanItem` 2.2%: the optimizer's search over a join. A GC "collection
time" sum from the management beans may include G1's concurrent cycles and was not used as a pause
figure; the pause figures above come from the recordings' `sumOfPauses`.

## Statement preparation

### One cold capture

A scaled ten-type fixture captured into a fresh store, the writer's statements only: 602 distinct statements,
886 prepare calls, 1,405 ms preparing and 888 ms executing.

| Statements | Share of prepare time |
|---|---:|
| top 1 | 35% |
| top 5 | 70% |
| top 10 | 79% |
| top 20 | 84% |
| top 50 | 91% |

By mean prepare time per call: 3 statements at 100 ms or more, 3 at 20 to 100, 12 at 5 to 20, 67 at
1 to 5, and 517 (86%) under 1 ms.

### The whole `graphitron` phase

4,684 tests, thread CPU time, at the trunk of that afternoon (the commit was not recorded):

| | |
|---|---:|
| Prepare calls | 1,199,946 |
| Distinct statement texts | 23,112 (23,106 writer, 6 reader) |
| Prepare CPU | 1,169 s (1,586 s wall) |
| Execute CPU | 390 s |

| Distinct statements by mean prepare CPU per call | Statements | CPU s | Share |
|---|---:|---:|---:|
| 100 ms or more | 944 | 667 | 57% |
| 20 to 100 ms | 1,114 | 237 | 20% |
| 5 to 20 ms | 2,097 | 84 | 7% |
| 1 to 5 ms | 5,826 | 129 | 11% |
| under 1 ms | 13,131 | 52 | 4% |

Grouped by shape (verb and target relation, 929 shapes), 92% of preparation CPU is derivation-stratum
`merge into` and `insert into graphitron_*`, and the top six shapes are 851 s, 73%; the top ten
920 s, 79%.

| Shape | Texts | Prepares | ms per prepare | Prepare CPU s | Execute CPU s |
|---|---:|---:|---:|---:|---:|
| `merge into graphitron_entry_defect` | 2,817 | 2,820 | 113 | 318 | 15.9 |
| `merge into graphitron_type_backing` | 1 | 940 | 235 | 221 | 47.4 |
| `insert into graphitron_argument_reference_step_target_keyed` | 1 | 941 | 131 | 123 | 0.4 |
| `merge into graphitron_argmapping_site` | 1 | 940 | 68 | 64 | 0.6 |
| `merge into graphitron_field_path` | 939 | 1,088 | 58 | 63 | 1.2 |
| `insert into graphitron_field_reference_step_hop_keyless` | 2 | 1,880 | 33 | 62 | 0.1 |
| `insert into graphitron_node_id_instruction` | 1 | 941 | 24 | 23 | 0.6 |
| `merge into graphitron_field_key_row` | 1 | 940 | 19 | 18 | 0.3 |
| `merge into graphitron_field_path_method` | 939 | 940 | 18 | 17 | 0.1 |
| `merge into graphitron_entry_defect_method` | 2,817 | 2,820 | 4 | 12 | 0.2 |

Preparation is 1,169 of the JVM's roughly 3,100 CPU-seconds, 38%, where the samples put parsing and
planning at about 60%. The gap is not explained: the tally sees jOOQ-prepared statements only, so boot
DDL and any statement prepared through raw JDBC are outside it, and the sampling categories are
inclusive.

### What makes them heavy

Reading each statement's tables through the DDL's 134 views (306 base tables), from the cold-capture
statements:

| Statement | Relations read directly | Reached through views | Views among them |
|---|---:|---:|---:|
| `merge into graphitron_entry_defect` | 2 | 116 | 18 |
| `merge into graphitron_type_backing` | 2 | 29 | 3 |
| `merge into graphitron_field_path` | 2 | 27 | 2 |
| `insert into graphitron_node_id_instruction` | 2 | 24 | 4 |
| `insert into ..._argument_reference_step_target_keyed` | 3 | 14 | 2 |
| `merge into graphitron_field_key_row` | 2 | 15 | 1 |

Over the cold capture's 602 statements, prepare time correlates 0.72 with the relations a statement
reaches through views and 0.07 with the nested selects in its own text. The median cheap statement
(under 1 ms) reaches 1 relation and has no nested select; the median at 20 ms or more reaches 24 and
has one. The statements execute in 1 to 53 ms in a capture, so they cost ten times more to plan than
to run.

### The cache, and why the long-lived one works

Nineteen of a capture's statements carry the capture's own timestamp as an inlined literal
(`timestamp '2026-11-02 10:53:57.303100802'`), so their text is new every time: in the suite the
entry-defect merge has 2,817 texts for 2,820 calls. They hold 598 of the cold capture's 1,405 prepare
milliseconds. A long-lived cache (1,024 statements) still takes a repeat capture's preparation from
1,231 ms to 50 ms, and those nineteen from 470 ms to 45 ms, so the statement-text cache is not what
carries the saving. H2 also caches the planned view indexes per session in a cache sized by the same
setting, and with the default of 8 that cache thrashes and every capture re-plans the whole view
stack.

## Cold stores, boots and the reset

A full `graphitron` phase did 476 cold boots, each 0.39 s of CPU (183.6 s in all, 6.0% of the test
JVM's 3,078.5 CPU-seconds) and 3.1 s of wall time, the rest waiting for a core. By source: 182 through
`CapturedStore`'s own-store arms (72.6 s CPU), 43 through the production facade in
`OutcomeBlockRenderer`, 30 through the per-thread store, and the rest over about thirty classes, some
of which test boot and warm start themselves. A warm in-memory boot is about 0.4 s; reopening a copied
already-booted file through `openAt` takes about 1.8 s (the file is 884 KB and copies in
milliseconds), because H2 re-parses every stored definition on open, so a template does not pay.

The boot is the small part of a new store's cost. The first capture into it is about 1.9 s slower than
a warm one, so a cold store costs about 2.3 s of CPU, not 0.4 s.

Capture time per case, in ms, on the harness's per-thread store (reset between cases) against one
owned long-lived store with no reset:

| Case | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---:|---:|---:|---:|---:|---:|
| Borrowed, reset between | 3,600 | 3,163 | 3,129 | 3,188 | 3,193 | 3,126 |
| Owned, chained, no reset | 3,268 | 2,030 | 1,284 | 1,195 | 1,297 | 1,354 |

The reset restores column selectivities with one `ALTER TABLE ... SELECTIVITY` per drifted column,
and a schema change empties every session's statement cache, so the borrowed series never warms.
Over a `graphitron` phase the harness counted 719 captures and 262 boots (captures through the production
facade are in neither count); at most about 450 captures could be warm, and at 1.9 s each that bounds the saving near 870 CPU-seconds, 12% of the
build, an upper limit not yet tested.

By position in a store, at cache sizes 8 and 1,024 (three runs, mean): the first capture 2,639 and
2,869 ms (no gain), captures two to six 1,967 and 1,365 ms (31% faster). The memory cost, over six
captured stores held at once: 40.7 MB per store at 8, 44.2 at 64, 46.0 at 128, and 70.5, 70.7 and 70.5 at
700, 1,024 and 2,048, since a capture fills only the statements it runs: about +30 MB a store, about 50
KB a cached statement.

## The test pool

JUnit's pool starts a compensating worker when one blocks, up to the parallelism plus 256 by default,
so the configured parallelism of four was not what ran. Surefire's per-class times overlap, so their
sum against the phase's wall clock gives the average number of classes in flight:

| Module | Sum of class times | Test phase | In flight |
|---|---:|---:|---:|
| `graphitron` | 14,367 s | 774 s | about 19 |
| `graphitron-lsp` | 3,255 s | 218 s | about 15 |
| `graphitron-model` | 3,517 s | 367 s | about 10 |
| `graphitron-sakila-example` | 410 s | 136 s | about 3 |

The boot tally agrees: 30 distinct threads booted the per-thread store in `graphitron`, where four
were meant. Setting `junit.jupiter.execution.parallel.config.fixed.max-pool-size=4`, switched on
from the command line as a system property for the comparison, in interleaved runs with every test
passing and no run hanging:

| Module | Default | Capped | Saved | Boots |
|---|---|---|---|---|
| `graphitron-model` (pairs 2 to 4) | 397, 394, 388 s | 329, 340, 342 s | 56 s, 14% (46 to 68) | 88 to 70 |
| `graphitron-lsp` (pairs 2 and 3) | 217, 220 s | 147, 142 s | 74 s, 34% | 67 to 47 |
| `graphitron` (two pairs, none discarded) | 904, 886 s | 784, 715 s | 146 s, 16% (120 to 171) | 260 to 238 |

The capped arms never started at a lower load than the default arms. Adopted in the `graphitron` and
model property files; `sakila-example` averages three in flight and was left alone.

## A timing gate that fails for reasons outside the code

`GraphCountGrowthGateTest` asserts that a stage's time does not grow with the other graphs in the
store, with a bound of four times the first graph's time plus 40 ms, per relation. It failed two of
roughly nine full builds taken in this work, on a different relation each time: 3 ms alone against 59
ms as one of eight (`GRAPHITRON_MINTED_CONFLICT`), and 0 ms against 46 ms
(`GRAPHITRON_FIELD_PARTICIPANT_PATH`), each past the bound by 6 to 7 ms. Alone it passes in 44 to 50 s on
both trunk and a branch; inside a full build it ran 160 to 198 s. A collection pause or a stalled
thread inside a measurement of a few milliseconds is enough, and there are hundreds of relations to
fail on. A verification build that fails for that reason is not a trustworthy verification build. Not
fixed here.

## What the steps bought

R1131's steps, each priced by interleaved pairs on this machine unless noted. The savings are in
wall clock on single commands and were not re-measured together, so they overlap (the cap and the
statement cache both reduce the same test phases) and the sum is an upper bound.

| Step | Change | Priced saving |
|---|---|---|
| 1 | Statement cache sized by use (`StoreUse`), read-only reader user | `FactExpectationTest` 108 s, 23%; about +30 MB per captured store; does not reach the harness's borrowed cases, which the reset flushes |
| 2 | `{@link}` gate moved from javadoc into javac | 218 s (213 to 223) off a clean `graphitron-model` verify |
| 3 | Parallel reactor | Audited, no collision, parked: CI and `mvnd` already parallel, plain `mvn` sequential |
| 4 | Corpus count test no longer captures a population nobody reads | 44 s (37 to 58) off the `graphitron` test tail |
| 5 | Split `FactExpectationTest` | Not done: the seven methods take 0.0 s, the cost is one `@BeforeAll`; a per-graph check looked like 230 to 95 s but trunk had moved, and pairs found no effect |
| 6 | Boot from a template | Closed: a copied file reopens in 1.8 s against 0.4 s for a boot |
| 8 | Pool held at its parallelism | 56, 74 and 146 s on the model, LSP and `graphitron` |

Summed, these are about 540 s of 2,372 s (23%), as an upper bound. Against the CPU floor the picture
is less favourable: the statement cache and the pool cap reduce contention and repeated planning, the
gate move removes sequential single-thread work, and none of them touches the 851 CPU-seconds in six
statements, which are most of the 1,169 CPU-seconds spent preparing.

## Levers, ranked, with their bounds

These overlap: levers 1 to 3 all attack the same 1,169 preparation CPU-seconds in `graphitron`, so their
effects do not add.

| # | Lever | Bound | Confidence | Note |
|---|---|---|---|---|
| 1 | Make the six statements cheaper to plan, by flattening or avoiding the view stacks under them (18 views, 116 relations under the entry-defect merge) | up to 851 CPU s in the `graphitron` JVM, 12% of the build, plus the model's share, unmeasured | high that the cost is there; the achievable part depends on the model | Modelling work; the derivation items already name these views |
| 2 | Keep the statement and view-plan cache warm across harness cases: reset without the schema change that flushes it | up to about 870 CPU s (450 warm-capable captures at 1.9 s) | medium; the 1.9 s is measured, the count is an upper bound, and plan-sensitive tests depend on the reset | Not built or tested |
| 3 | Reuse booted, warm stores instead of fresh ones | about 420 CPU s (182 own-store boots at about 2.3 s) | medium | Needs shared fixtures reset between uses, with the leakage risk the harness documents |
| 4 | Parallel reactor | at most 24% of wall here, none of the CPU | high | Does not move the CPU floor |
| 5 | The 109 s `graphitron` test-schema exec, a single process | not investigated | | The largest non-test goal |
| 6 | Fix the timing gate | reliability, not time | | |

How far this goes toward the target: by the samples, parsing and planning is up to about 60% of the
test JVMs' CPU. The test JVMs account for roughly 5,700 of the 7,192 CPU-seconds, so removing all of
the preparation would leave about 3,800 (at the samples' 60%) to about 5,000 (at the tally's 38%),
which straddles the 4,800 floor and would still need the reactor to scale well on 16 cores. So the
target is reachable only if preparation is nearly eliminated, and no single lever here does that:
levers 2 and 3 only remove the repeats. These two figures are estimates from the shares above, not
measurements.

## What was not measured

- Any run on a 16-core machine. Every figure is from 4 cores, and CPU-seconds there are not identical
  to CPU-seconds on 16 (garbage-collection threads, JIT compilation and spinning scale with cores).
- The statement tally covers the `graphitron` JVM. The model and LSP JVMs were not tallied, so the six
  statements' share of the whole build is the `graphitron` share only.
- The gap between the tally's 38% preparation share and the samples' 60%: boot DDL and raw-JDBC
  statements are outside the tally.
- The forks of the modules that wrote no recording; which modules the two short recordings belong to
  was not established.
- Whether trunk's derivation work has already changed the six statements. Several landed while this
  was measured, and the tally is a single moment.
- The reset flush's effect on a whole phase: the 1.9 s per case is measured on one fixture, the count
  of warm-capable cases is a bound.

## Where earlier readings in this work were wrong

Kept because a reader of the numbers above should know which instruments misled.

1. **A saving credited to a change when trunk had moved.** A per-graph check looked like 230 s down to
   95 s on one run; the class had fallen from about 420 s to about 170 s through other items' derivation
   work in between, and interleaved pairs against the same trunk showed +2 s. The lesson is to run the
   control before reporting.
2. **A sweep run with `-DargLine=...` and a search for "Out of memory".** The override changed what
   the JVM ran, and the search missed the plain `OutOfMemoryError` the engine reports, so size 8 looked
   like it failed.
3. **Wall time read as cost.** Boots measured 3.1 s of wall and 0.39 s of CPU; the first estimate
   priced them at an assumed 0.4 s and then explained the larger figure away as contention without
   evidence. Both were later measured, and the CPU figure held.
4. **A memory failure attributed to commit against rollback, then to the cache size.** The bisect
   showed the cache setting (700 and above ran the LSP module out of heap); the commit was innocent.
5. **A comment that was wrong until the pool was capped.** The per-thread store was documented as "a
   handful of stores"; there were 30.
6. **Overlapping edits during a measured run.** The model's code generation reads the SQL, so editing
   it mid-run contaminated a baseline.
7. **Per-class times read as exclusive costs.** They overlap 10 to 19 deep; only removing a class and
   re-running prices it.
8. **A recording whose clock was skewed 2,011 s.** The Maven recording's absolute times disagreed with
   the system's; relative offsets agreed.
9. **A tool's garbled labels.** The skill's `mojos.py` printed unrelated class names for the compile
   and jOOQ rows; goals and phases were used instead.

## Reproducing

Scripts lived in the session scratchpad and are not kept; this is what each was.

- The recorded build: `MAVEN_OPTS="-XX:StartFlightRecording=filename=build.jfr,settings=default,dumponexit=true
  -XX:FlightRecorderOptions=stackdepth=256 -Xmx4g"`, then `mvn clean install -Plocal-db
  -Dmaven.test.failure.ignore=true`, with the forks recorded through
  `-DargLine=-XX:StartFlightRecording=filename=fork-%p.jfr,settings=default,dumponexit=true`.
- The CPU sampler: a loop writing `date +%s.%N` and `head -1 /proc/stat` every 2 s, aligned to module
  windows from the recording's `ProjectEvent`s by offset from the first project, not by absolute time.
- The statement tally: a jOOQ `ExecuteListener`, one instance per execution, recording the rendered SQL
  at `prepareStart`, wall and `ThreadMXBean.getCurrentThreadCpuTime` at `prepareEnd`, and the same
  around `executeStart` and `executeEnd`, aggregated by SQL text in a concurrent map and written at JVM
  exit to the path in a system property; attached to the store's writer and its readers' contexts. In the
  installed model jar only, with the source restored and the jar reinstalled afterwards.
- The boot tally: the same technique around the store's `create` path, with the first non-store frame
  of the stack as the asker.
- Shapes: the verb and the target relation (`merge into X`, `insert into X`, otherwise the first `from`).
  Reach: the DDL's views parsed for the relation names each body mentions, closed transitively.
- Pairs: arms alternated, the first pair discarded, the spread quoted; no `clean` unless the plugin being
  priced is stale-checked.
