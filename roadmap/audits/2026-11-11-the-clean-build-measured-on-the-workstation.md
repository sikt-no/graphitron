# The clean build, measured on the workstation, 2026-11-11

A working document, not a roadmap item; it lives in `audits/` so the roadmap-tool ignores it. It is
the baseline R1131's rewritten plan is measured against. It follows the `build-profile` skill's
method, and it repeats neither `2026-11-02-where-the-build-spends-its-cpu.md` nor
`2026-11-09-why-the-build-takes-35-minutes.md`; where a figure moved since 11-09 it says so.

## The run

* **Command:** one sequential `mvn clean install` at trunk `8d34bcd6a3`.
  * It ran into an empty `-Dmaven.repo.local` with the shared `~/.m2` as tail, and no `-Plocal-db`, so the database came from Testcontainers PostgreSQL.
  * Heap settings: `MAVEN_OPTS=-Xmx2g`, `JDK_JAVA_OPTIONS=-Xmx6g`.
* **Machine:** the workstation, an Intel Core i9-9880H with 8 cores and 16 threads.
  * It ran on 32 GB of memory, after a faulty module was removed on 2026-11-11.
  * Load average was 3.1 at the start, and no other build ran during the run.
* **Recording:**
  * JFR in the Maven JVM (`settings=default`, the repo's JFR Maven extension).
  * JFR in each surefire fork, through `-DargLine`.
  * `/proc/stat` every 2 s.
  * `ps` every 5 s over every java, postgres and docker process.
* **Result:** `BUILD SUCCESS`, every test passing, 33:56 wall (2,039 s).
* **Sequential:** mojo durations sum to 2,037.6 s, and the ProjectEvent windows are disjoint and run on one thread, so the reactor was strictly sequential.

Gaps:
* **Three forks wrote no recording.** The `graphitron-lsp`, `graphitron-mcp` and `graphitron-maven-plugin` forks override `<argLine>` with `@{argLine} --enable-native-access=...`, which drops the flag passed on the command line. Their CPU comes from `ps` and surefire alone.
* **Truncated stacks.** The forks recorded at the default stack depth of 64, so 99.6% of `graphitron`'s stacks are truncated. Inclusive figures in our own code are therefore lower bounds.

## Wall and CPU

**9,711 CPU-seconds over 2,039 s: an average of 4.76 of 16 threads, so 70% idle.** Java processes
account for 9,064 of them, and postgres and docker for about 4.

Seconds of wall clock at each level of busy threads, in 10-second buckets:

| busy threads | under 2 | 2-4 | 4-6 | 6-8 | 8 or more |
|---|---:|---:|---:|---:|---:|
| seconds | 480 | 260 | 750 | 390 | 170 |

| Module / goal | wall s | CPU-s | avg cores |
|---|---:|---:|---:|
| sakila-db (jOOQ codegen, compile) | 23.8 | 74 | 3.1 |
| **graphitron-model** | 303.4 | 1,853 | 6.1 |
| &nbsp;&nbsp;surefire | 252.1 | 1,723 | 6.8 |
| **graphitron** | 1,097.0 | 4,663 | 4.3 |
| &nbsp;&nbsp;exec `generate-test-schemas` | 254.6 | 439 | **1.7** |
| &nbsp;&nbsp;surefire | 814.2 | 4,127 | 5.1 |
| graphitron-mcp | 48.6 | 298 | 6.1 |
| graphitron-lsp | 172.2 | 910 | 5.3 |
| graphitron-maven-plugin (surefire 46.9, invoker 82.4) | 138.1 | 742 | 5.4 |
| graphitron-sakila-example (8 sequential `graphitron:generate` 69.1, surefire 104.7) | 194.2 | 977 | 5.0 |
| roadmap-tool / docs | 34.3 / 24.0 | 88 / 77 | 2.6 / 3.2 |

Surefire is 71% of the wall clock and 82% of the CPU. The pools in `graphitron-model`, `graphitron`
and `graphitron-sakila-example` are fixed at 4 workers, which explains the 5 to 6.8 cores seen in
their test phases.

## The critical path and the serial tails

The path runs fixtures → sakila-db → sakila-service → model → graphitron → maven-plugin →
sakila-example, and it is **1,759 s** long. LSP (172 s), MCP (49 s), roadmap-tool (34 s) and docs (24 s)
are off it, and today they run after it rather than beside it.

Periods with fewer than two cores busy, 480 s in all:

| window (s from start) | length | cores | what ran |
|---|---:|---:|---|
| 360-590 | 230 s | 1.5 | `generate-test-schemas`: one JVM looping over 92 scenarios in sequence, about 2.8 s each (183 s on 11-09) |
| 1260-1420 | 160 s | 1.4 | `EmittedRegistryAgreementTest`: one test method, 301.7 s in all (229 s on 11-09), on one worker thread in its tail |
| 1930-1970 | 40 s | 1.6 | `GeneratorDeterminismTest`: 93.6 s, three tests, the whole of sakila-example's test window |
| 50-70 | 20 s | 1.7 | the model's javac |
| 1980-2039 | about 60 s | 2.4-3.0 | roadmap-tool and docs: small sequential `exec:java` runs |

Other long single classes:
- `WarmStartRefreshTest`: 134.6 s.
- `NodeIdDecodeDefectsTest`: 95.5 s.
- `PersistentStoreTest`: 89.6 s.
- `GraphCountGrowthGateTest`: 87.3 s, one test.

## Inside the test JVMs

Shares of execution samples, taken every 20 ms on Java threads. GC and JIT threads are not sampled, so they have their own rows below.

| | graphitron | model | sakila-example |
|---|---:|---:|---:|
| samples (about CPU-s) | 112,883 (2,258) | 45,032 (901) | 8,838 (177) |
| **H2 planning** | **65.8%** | **61.0%** | 29.8% |
| H2 execution | 13.5% | 20.0% | 31.7% |
| H2 parsing | 9.1% | 8.5% | 4.4% |
| jOOQ rendering / other | 3.7 / 4.9% | 4.1 / 4.2% | 3.4 / 10.8% |
| graphql-java | 1.2% | 0.2% | 8.5% |
| our own code, self time | under 0.3% | under 0.3% | under 1% |
| **GC CPU** | 876 s (22% of the fork's 3,969) | 253 s (15%) | 47 s (9%) |
| JIT compilation | 362 s | 261 s | 295 s (54% of 541) |

**What the planning is.** These are inclusive shares in `graphitron`, with the model's in brackets:

| frame | graphitron [model] |
|---|---:|
| `Optimizer.calculateBestPlan` | 66.9% [59.3%] |
| `QueryExpressionTable.getBestPlanItem`, which plans a derived table or view as a join input | 66.0% [58.3%] |
| `Optimizer.calculateBruteForceAll`, H2's exhaustive join-order search over up to 7 filters | 52.3% [46.6%] |
| `TableView.createIndex`, which re-plans the nested query for each candidate order | 33.9% [33.2%] |

So the cost is join-order search multiplied by nested-query planning. The leading self frames are
condition evaluability checks and index cost estimates: `ConditionAndOrN.setEvaluatable` at 13.1%,
`Index.getCostRangeIndex` at 7.9% and `Comparison.setEvaluatable` at 7.5%.

**Where the planning comes from.** The table gives the innermost `no.sikt` frame for each sample in `graphitron`, with the model's share in brackets:

| stage | share of samples | planning |
|---|---:|---:|
| `PathNeeds.write` | **20.0%** [15.5%] | 19.6% |
| `BindBatch.execute` (row inserts) | 10.7% [6.8%] | 0.7% |
| `OccurrenceSources.write` | 7.2% [5.2%] | 6.4% |
| `OccurrenceProducers.refresh` | 4.4% [4.1%] | 4.2% |
| `TypeBackings.derive` | 4.0% [3.7%] | 3.9% |
| `EntryDefects.derive` | 3.6% [3.3%] | 3.4% |
| `OccurrenceConditions.writeDepth` | 3.4% [3.0%] | 3.4% |
| `GraphitronModelStore.create` (boot DDL) | 3.3% [3.7%] | 0.8% |
| `Steps.writeColumns` | 1.7% [1.6%] | 1.6% |
| no `no.sikt` frame within the 64 recorded | 22.0% [24.2%] | 15.4% |

The derivation stages under `OccurrenceDepths` carry about 43% of `graphitron`'s Java CPU (about
970 CPU-s, an estimate) and 35% of the model's (about 315). The statement cache is 64 since
`300edfb296`, and yet `PathNeeds.write` is still 20% of the samples. That is because the
planning is paid per capture, in each fresh store, rather than once.

## Duplicated work

**How many times the store captures.** These counts are lower bounds, taken from the derivation-pass log line:

| where | captures |
|---|---:|
| `graphitron` tests | at least 695 |
| LSP | at least 210 |
| `generate-test-schemas` | 92 |
| MCP | at least 36 |
| sakila-example tests | at least 16 |
| sakila `generate` | 8 |

**Inline capture call sites in test sources:**

| module | call sites | files |
|---|---:|---:|
| `graphitron` | 1,256 | 246 |
| model | 72 | 53 |
| LSP | 15 | 4 |
| MCP | 8 | 1 |

**The roughly 104 scenario fact documents are captured at least three times per build:**
1. in the model's tests;
2. again by `generate-test-schemas`, in a fresh JVM whose output is deleted every run;
3. again by `EmittedRegistryAgreementTest`, cold, once per document.

Then each module's own population captures them again: `graphitron`, LSP, MCP and the plugin.

**Classes comparing the walk against the store,** as summed class time:

| module | classes | time |
|---|---:|---:|
| `graphitron` | 17 | 364 s, 302 of it `EmittedRegistryAgreementTest` |
| model | 7 | 64 s |
| plugin | 2 | 22 s |

The walk itself is cheap, at about 1.4% of `graphitron`'s samples. Its cost is the extra captures its comparisons run.

## GC

| fork | young GCs, pause | old GCs, pause | GC CPU | peak heap / max |
|---|---|---|---:|---|
| graphitron | 596, 67.9 s | 93, 0.9 s | 876 s | 5,719 / 6,144 MB |
| model | 222, 19.6 s | 38, 0.3 s | 253 s | 5,752 / 6,144 MB |
| sakila-example | 85, 3.5 s | 16, 0.2 s | 47 s | 2,537 MB |

Both big forks run at their heap ceiling with only four workers. A wider pool needs more heap or fewer live stores.

## The arithmetic

* **Parallelism alone has a floor of about 16 minutes.** 9,711 CPU-s over 8 cores with hyperthreading, about 10 effective, is still about 16 minutes.
* **The non-test goals leave little room.** They take about 195 s of the critical path:
  * upstream modules, 27 s;
  * the model's codegen and compile, 48 s;
  * `graphitron`'s testCompile, 22 s;
  * the plugin descriptors, about 10 s;
  * sakila's `generate`, 69 s;
  * sakila's compile, 14 s.

  That leaves about 105 s for the test phases on the path, which take 1,300 s today.
* **Five minutes needs total CPU of about 2,500 to 3,000 CPU-s,** roughly 70% less, held at 8 or more busy cores. It is plausible only if per-capture planning largely disappears and the duplicate captures go.
