---
id: R967
title: "The per-case statistics reset ALTERs every column of every base table, and is most of the store-heavy test build; reset only the columns that drifted"
status: Spec
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-09-22
last-updated: 2026-09-22
---

# The per-case statistics reset ALTERs every column of every base table, and is most of the store-heavy test build; reset only the columns that drifted

## Goal

The fact store's shared-store test fixture (`ThreadConfinedStore`, which lends one in-memory H2 fact
store per test thread and clears it between cases) puts H2's per-column statistics back to their
booted state after every case by touching only the columns whose statistics actually moved, instead of
issuing an `ALTER TABLE ... ALTER COLUMN ... SELECTIVITY` for every column of every base table. The
guarantee the reset exists for, that no case reads a query plan an earlier case's row volume shaped,
is kept exactly; what changes is that a clear costs milliseconds again rather than three quarters of a
second, which on the current schema is most of the store-heavy test build.

## Where the time goes, measured

A fifth build measurement pass, taken 2026-09-22 on a 4 vCPU, 15 GB sandbox against a warm local
repository. Sequential `mvn clean install -Plocal-db`, JFR-recorded through the registered
`jfr-maven-extension`, green: **28:13, 1693 s**. Module windows are disjoint and mojo time sums to
1697 s against the 1693 s span, so the figures below are additive.

| Module | Wall clock | Share | | Goal | Time | Share |
|---|---|---|---|---|---|---|
| `graphitron-model` | 702.6 s | **41.4%** | | `surefire:test` | 1317.7 s | **77.6%** |
| `graphitron` | 557.4 s | **32.8%** | | `javadoc:javadoc-no-fork` | 126.7 s | 7.5% |
| `graphitron-sakila-example` | 117.0 s | 6.9% | | `graphitron:generate` | 69.4 s | 4.1% |
| `graphitron-mcp` | 88.1 s | 5.2% | | `compiler:compile` | 50.0 s | 2.9% |
| `graphitron-lsp` | 81.2 s | 4.8% | | `invoker:run` | 41.9 s | 2.5% |
| `graphitron-maven-plugin` | 79.4 s | 4.7% | | `exec:exec`, the MCP docs index | 19.2 s | 1.1% |

Inside the two large test phases, an in-fork JFR profile (`settings=profile`, `stackdepth=1024`,
injected through `JDK_JAVA_OPTIONS` so surefire's fork isolation is kept) puts **about 90% of test CPU
inside `org.h2`** and essentially none in graphitron's own code, with the forks saturating all four
cores (97.5% and 88.6% machine-wide). The hottest leaf frames are `ValueVarchar.get` (19%),
`InformationSchemaTable.tables` (10.6%) and `ValueStringBase.<init>` (9.2%): H2 materialising its
`INFORMATION_SCHEMA` meta-tables and parsing DDL.

JDK 25 JFR method timing (`jdk.MethodTiming#filter=...`, no source edit) on the fixture's own entry
points attributes it. Per call, isolated, single-threaded, on `DemandRuleTest`:

| Step of `ThreadConfinedStore.clear()` | Per call |
|---|---|
| `StoreStatistics.reset` | **749 ms** |
| of which `resetEveryColumn` | 673 ms |
| of which `declarePartitionSelectivity` | 66 ms |
| `counts`, the 282-arm row census | 6 ms |
| `verifyCleared` | 0.07 ms |
| the `TRUNCATE` loop | not measurable |

`resetEveryColumn` queries `INFORMATION_SCHEMA.COLUMNS` joined to `INFORMATION_SCHEMA.TABLES` and
then issues one `ALTER TABLE ... ALTER COLUMN ... SELECTIVITY 0` **per column of every base table**:
2329 columns on today's 282 base tables, once per test case. `graphitron-model` ran 1138 clears and
`graphitron` 478 (it shares the fixture through the model test-jar), so a build issues about 3.8
million `ALTER TABLE` statements and 3200 meta-table scans. The cost is linear in the fact schema's
column count, which is why the build tracked the schema's growth from 178 to 282 tables between
2026-09-08 and this pass.

Priced by removal rather than projected, per the build-profile skill's rule. `mvn test -pl
:graphitron-model`, the reset behind a temporary environment guard, arms interleaved:

| Pair | Reset on | Reset off |
|---|---|---|
| 1 | 621 s | **184 s** |
| 2 | 611 s | **183 s** |

All 1315 tests green in every arm. That is **70% of the module's test build**, and the module is 41%
of the reactor. `graphitron`'s share was not priced by removal; at 478 clears of the same cost it is
about 360 core-seconds more.

## Where it came from

`fa1656f`, 2026-09-21, "R876: reset the statistics when the shared store is cleared". Before it a
clear was the truncates plus the census and the leak guard, under 10 ms together. The commit fixed a
real order-dependent flake: H2 keeps per-column `SELECTIVITY` across a `TRUNCATE` and sets some of its
own once a table passes about two thousand changes, so a plan-asserting case
(`RefreshPlanStatisticsTest`, `PartitionSelectivityWorthTest`, `RefreshPrerequisiteStatisticsTest`)
read a number an earlier class left behind. Its message names the cost and the remedy and defers
both:

> Cost: the reset walks every column of every relation, so a borrow is more expensive than it was. A
> cheaper form would ALTER only the columns whose SELECTIVITY differs from UNANALYSED, which is most
> of them cold and few of them warm. Not taken here, because the flake is the thing being fixed and
> the measurement belongs with the optimisation rather than with this.

This item is that optimisation, carrying that measurement. Reverting the commit is not an option; it
would reinstate the flake.

## Plan

One file, `graphitron-model/src/test/java/no/sikt/graphitron/model/test/StoreStatistics.java`.

1. `reset(dsl)` reads, in one statement over `INFORMATION_SCHEMA.COLUMNS` joined to
   `INFORMATION_SCHEMA.TABLES`, the base-table columns whose `SELECTIVITY` differs from what a created
   store carries: `GraphPartition.DECLARED_SELECTIVITY` on the partition column
   (`GraphPartition.COLUMN`), `UNANALYSED` on every other column. It then issues one `ALTER` per
   drifted column, to that expected value. On a case that wrote fewer than two thousand changes to
   every table, which is nearly all of them, the query returns no rows and the reset issues nothing.
2. The end state is identical to the current `resetEveryColumn` followed by
   `declarePartitionSelectivity`: every base-table column at `UNANALYSED` except the partition column
   at its declaration. The guard is not weakened; the same predicate `analysed` already uses is what
   decides which columns to touch.
3. `resetIncludingTheDeclaration` keeps the full walk. Its one caller,
   `PartitionSelectivityWorthTest`, is measuring what the declaration is worth and needs the state no
   created store is in.
4. Price it the same way the regression was priced: `mvn test -pl :graphitron-model`, arms
   interleaved, against the 611 to 621 s baseline above and the 183 s floor with the reset removed
   entirely. The target is within a few seconds of the floor; a single filtered meta-table scan per
   clear is the cost that remains.

## Verification

* The module's own suite, green, including the three statistics-measuring tests the regression
  commit touched.
* The A/B in plan step 4, recorded here with its numbers.
* The full verification build before publishing, and its wall clock recorded here against the 28:13
  of this pass.

### Measured, at the implementation

`mvn test -pl :graphitron-model`, same box, same day as the baselines above, 1315 tests green in
every run:

| Arm | Runs |
|---|---|
| reset as landed by `fa1656f`, every column | 611 s, 621 s |
| reset of drifted columns only, this item | **275 s, 272 s** |
| no reset at all, the floor | 183 s, 184 s |

Isolated on `DemandRuleTest`, single-threaded, JFR method timing: `StoreStatistics.reset` **749 ms to
81.5 ms** per call, `ThreadConfinedStore.clear()` 749 ms to 105 ms. What remains is the one
`INFORMATION_SCHEMA.COLUMNS` scan per clear that asking the catalog what drifted costs; H2
materialises the whole meta-table before filtering it. Under four-way class concurrency the residual
is larger than the isolated figure projects (about 90 s of wall clock against 1138 clears at 81.5 ms),
which reads as allocation pressure from building the meta-table's values on four threads at once
rather than as lock contention, each thread's store being its own database. Closing that gap means
not asking the catalog: the fixture would have to know which tables a case changed past H2's
analysis threshold, which is a different design and wants its own measurement before it is taken.

The full verification build on the tree that shipped: `mvn clean install -Plocal-db`, green,
**30:13**, with `graphitron-model` at **7:57 against 11:42** in the pass above. The reactor total is
not comparable to that pass's 28:13: the sandbox was recycled as the build started and the
SessionStart hook's background reactor warm-up ran beside it on the same four cores (load average
5.8 over the window), so every module the change does not touch came in 30 to 80% slower, `docs`
and `roadmap-tool` included. The per-module A/B above is the instrument for this item, as its plan
says; the reactor wall clock is recorded because the Verification section asked for it, with the
confound named rather than the number dropped.

A second full build, owed after a rebase brought in a 334-line DDL change and edits to
`SeededStore`, was green at **29:03** with `graphitron-model` at **7:31**. The box was idle when it
started (load average 0.9), and the untouched modules still came in about 1.4x slower than the first
pass, `docs` 30 s against 22, `roadmap-tool` 36 s against 22. So the confound is the recycled
sandbox's per-core capacity rather than the warm-up alone, and the honest reactor-level statement is
a ratio: the one module this item touches went from the largest in the build to under the
`graphitron` module, 7:31 against 11:15 on the same box in the same run, where the first pass had it
at 11:42 against 9:17.

## Out of scope, filed elsewhere or left for the fifth pass write-up

* R733 carries the build measurement passes and should receive this pass. Three of its fourth-pass
  findings are corrected by it: the javadoc link gate is 126.7 s rather than 21.6 s, 107 s of it
  `graphitron-model` alone with the 1238-file generated jOOQ tree on the gate's sourcepath; R768's
  "a reset costs a fraction of a boot" has inverted, a reset being 749 ms against a boot; and
  `graphitron-model` has already largely retired R768's boot count, at 128 boots for 1315 tests,
  leaving `graphitron` (520 boots) and `graphitron-lsp` (207 boots for 74 classes, nearly its whole
  test phase) as that item's live scope.
* `graphitron-mcp` and `graphitron-lsp` are not slow; their long spans in a parallel build are lanes
  descheduled behind `graphitron`'s test phase. Sequentially they are 88 s and 81 s.
