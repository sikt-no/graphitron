# What a capture costs, by the store it lands in, 2026-11-11

A working document, not a roadmap item; it lives in `audits/` so the roadmap-tool ignores it. It is
R1131's step A1: the price of a capture into a store booted for the test, against the same graph
captured into a store like the `FactGraphs` population's. It builds on
`2026-11-11-the-clean-build-measured-on-the-workstation.md` and does not repeat it.

## The answer

**A capture costs about 2.3 s of CPU in a store that is empty, and about 0.3 to 0.45 s in a store
that already holds other graphs and has kept its statement cache.** That is a factor of six to eight,
on every document sampled, and the real test run shows the same split.

Every inline arm a test uses today lands in the expensive case:
- a store booted for the capture;
- the thread's long-lived store (`ThreadConfinedStore`), because its clear empties it and invalidates
  its statement cache before every test.

So most of a capture's cost is not reading the inputs. It is the first-time planning of the
document readings and the anchoring in an empty store. Moving captures into a populated store
removes about three quarters of the capture CPU in `graphitron` and `graphitron-model`, before any
duplicate capture is removed.

## The run

* **Machine:** the agent sandbox, 4 cores and 15 GB, not the workstation. Only ratios and thread CPU
  are quoted here; wall clock is not comparable with the baseline.
* **Tree:** trunk `f3f6285` plus two temporary pieces, not committed:
  * a probe class in `graphitron-model`'s tests;
  * per-stage timing in `ModelCapture.capture`, written to a file a system property names.
* **Unit:** CPU time of the capturing thread (`ThreadMXBean`). H2 is embedded and runs on the caller's
  thread, so this is the capture's own work. GC and JIT threads are not in it; process CPU and
  allocation are quoted beside it.

### The probe

Each sampled fact document over the common catalog was captured through `Scenario.run` into
`CapturedStore.captureFolder`, the population's own reading, on several kinds of store, in rotated
order per document and round. Round 0 was discarded. Two runs:
* 12 documents, 4 rounds;
* 8 documents, 4 rounds, with two more arms.

The arms:
- **cold:** a store booted for the capture, `StoreUse.ONE_CAPTURE`.
- **cold, no classpath** and **cold, no catalog or classpath:** the same with fewer inputs.
- **funnel:** `ThreadConfinedStore.run`, the thread's `LONG_LIVED` store after its clear.
- **shard:** one `LONG_LIVED` store per round, every sampled document captured into it in turn, as a
  population shard does. The first capture into it is "first", the rest "Nth".
- **clear without the statistics reset:** the funnel's truncate, without `StoreStatistics.reset`.
- **clear by DELETE:** the funnel's clear by `DELETE`, with referential integrity left on.

The table gives thread ms: median, then p25 to p75 in brackets.

| arm | 12 documents | 8 documents | process ms | allocated |
|---|---|---|---:|---:|
| cold | 2,396 [1,680 to 2,495] | 2,380 [1,705 to 2,496] | 3,275 | 1,378 MB |
| cold, no classpath | 2,250 [1,547 to 2,351] | | 3,015 | 1,309 MB |
| cold, no catalog or classpath | 2,202 [2,146 to 2,264] | | 2,985 | 1,234 MB |
| funnel | 2,320 [1,759 to 2,398] | 2,374 [1,622 to 2,429] | 3,030 | 1,370 MB |
| clear without the statistics reset | | 2,363 [1,744 to 2,414] | 3,375 | 1,369 MB |
| clear by DELETE | | 2,364 [1,641 to 2,435] | 3,085 | 1,369 MB |
| shard, first | 1,704 (n=3) | 2,407 (n=3) | 2,590 | 1,024 MB |
| **shard, Nth** | **287 [250 to 391]** | **308 [239 to 420]** | **360** | **151 MB** |

Two shorter runs, of 6 to 8 documents and 3 rounds, separate the two conditions behind the cheap case:

| store between captures | Nth capture, mean thread ms |
|---|---:|
| keeps its rows; `StoreStatistics.reset` before each capture | 334 |
| keeps its rows; `SET REFERENTIAL_INTEGRITY FALSE` then `TRUE` before each capture | 2,142 |
| loses its rows (truncate, or DELETE), cache otherwise kept | 2,083 to 2,364 |

So the cheap case needs both conditions:
* **The statement cache survives.** Toggling referential integrity alone throws it away. The funnel's
  clear toggles it to truncate tables that foreign keys reference.
* **The store holds other graphs' rows.** An emptied store is expensive again even with nothing
  toggled. This is consistent with H2 planning a view's inner query at execution from the tables'
  row counts (`docs/architecture/explanation/fact-model.adoc`, on `EXPLAIN`). That is a reading of
  the result, not something measured here. The `store-performance` skill owns that question.

The statistics reset is harmless to the cache. `ALTER COLUMN ... SELECTIVITY` does not invalidate it.

### Where a capture's time goes

This is the per-stage timing from the instrumented `ModelCapture.capture`, on the same documents.
The catalog reading costs about 0.1 s. The classpath reading costs 0.1 to 0.4 s the first time and
nothing once its entries are vouched. Everything else is in the document readings and anchoring:
- about 2.2 s into an empty store;
- 0.1 to 0.2 s into a populated one.

The derivation tail (`FactCapture.derive` and the two `ANALYZE`s) costs about 30 ms either way. The
stages the 2026-11-11 profile ranks (`PathNeeds.write` and the occurrence stages) run inside the
document span, under the anchoring.

### The real test run

`mvn test -pl :graphitron-model,:graphitron -DexcludedGroups=execution`, with the capture timing on.
4,757 tests ran and one failed: `StoreFixtureGuardTest`, which caught the probe class opening a store
outside a harness. 1,108 captures were logged:

| store the capture landed in | captures | thread CPU | median |
|---|---:|---:|---:|
| the thread's store, after its clear | 311 | 933 s | 2,556 ms |
| a store of its own, booted per capture (`ownStore*`, `BuiltStore` and `GraphitronStore.captured`) | 335 | 807 s | 2,344 ms |
| a test driving the pass itself | 54 | 104 s | 2,168 ms |
| a second graph into a fixture's store (`captureAnother`) | 97 | 98 s | 374 ms |
| the `FactGraphs` population | 311 | 170 s | 459 ms |
| **all** | **1,108** | **2,111 s** | |

* **Inline captures are 92% of the capture CPU:** 797 captures and 1,941 s.
* **695 of them landed in an empty store,** for 1,836 s. Of that, the document readings and
  anchoring are 1,508 s, the classpath readings 260 s, the catalog 40 s and the derivation tail 27 s.
* **The two cheap rows prove the rule outside the probe.** A second graph into a populated store
  costs a capture of the population's kind, whoever asked for it.

## What it says about the plan

* **Track A holds, and the target is not restated.** Had every one of the 797 inline captures cost
  what a populated capture costs (about 0.45 s), the two modules' capture CPU would be about 540 s
  instead of 2,111 s. That removes about 1,570 thread-seconds, 75%, before GC. Allocation falls by
  the same factor, from 1.4 GB to 150 MB a capture, and that is what the forks' 22% GC share is made
  of. Whether that reaches five minutes is the workstation's question, priced when the first change
  lands.
* **The lever is the populated store, not the population as such.** A capture is cheap once it lands
  in a store that holds other graphs and has kept its cache. A2, moving tests onto `FactGraphs`, gets
  there one class at a time. A shorter road gets most of it in one harness change: let the thread's
  store accumulate graphs instead of clearing. Each borrow gets a unique graph name, and the thread
  keeps one store per catalog and classpath. That is the population's sharding rule, and the reason
  the population can run without a clear:
  * **The cost of it:** about 290 test references to `CapturedStore.GRAPH` read the fixture's own name
    instead.
  * **The tests that keep a store of their own:** those that read without scoping to their graph,
    those that write, and those that change the schema. They are not counted yet.
  * **The heap is the open risk:** a thread store holding a few hundred graphs, in forks that already
    run at their 6 GB ceiling.
* **Duplicate captures are the second-order saving.** Once a capture costs 0.45 s, capturing a graph
  once instead of three times saves about 0.9 s per graph. A3, sharing captured stores across modules,
  should be priced against that and not against the 2.3 s figure. Of the three captures per document,
  the `EmittedRegistryAgreementTest` one leaves with the walk under R876.
* **B6 is stale as written.** The LSP, MCP and plugin modules already lead their `<argLine>` with
  `@{argLine}`, and the root pom declares an empty `argLine` property, yet the 2026-11-11 run saw no
  recording from those forks. The fix the step names is already in place, so the step needs
  re-diagnosing before it is done.
