---
id: R981
title: "Stop the thread store's clear paying almost a boot to reset statistics after a capture"
status: Spec
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-09-28
last-updated: 2026-10-12
---

# Stop the thread store's clear paying almost a boot to reset statistics after a capture

## Goal

Make borrowing the per-thread test store (`ThreadConfinedStore`, the store a test thread boots once
and empties between cases instead of booting a fresh one per case) cost a small fraction of a boot
after a catalog capture, where today it costs most of one. The statistics reset each borrow ends
with drops from about 180 ms to about 25 ms after a capture, and from about 28 ms to about 9 ms on a
borrow with nothing to reset. The end state stays exactly the same: column statistics that read as
a freshly created store's do. Then a module that moves its per-case fixtures from booting to
borrowing actually gets faster. `graphitron-lsp` did this, cut its boots from 207 to 54, and saw no
wall-clock change. And `graphitron` gets back the largest single project frame in its test CPU.

The same fix takes about 60 ms off every store boot (out of about 285 ms), because the query the
boot uses to find the graph-keyed tables has the same shape and the same cost.

## Where the time goes

Measured 2026-10-12 at `81b0a34` with a scratch harness over `FactStores.inMemory()` on the
`graphitron-model` test classpath. The sandbox had 4 vCPUs; each figure is a median of 15 runs after
5 warm-up runs. To simulate a capture's drift, the harness ran `ALTER ... SELECTIVITY 7` on 670
base-table columns across 83 tables, the count a capture leaves.

**The catalog read is the cost, not the `ALTER`s.** `StoreStatistics.reset` makes one read to find
the columns whose selectivity has drifted, then issues one `ALTER` per hit. With 670 drifted
columns the read took 136 ms and the 670 `ALTER`s took 14 ms together.

**The read's cost grows with the rows it returns.** It took 23 ms with no drifted columns, 44 ms
with 100, 63 ms with 300 and 131 ms with 670. Issuing the same 670 `ALTER`s with values that leave
nothing drifted kept it at about 22 ms. So each row the read returns costs about 0.17 ms.

**The join is why.** The read joins `INFORMATION_SCHEMA.COLUMNS` to `INFORMATION_SCHEMA.TABLES` so
that it keeps only base tables. Reading `COLUMNS` alone and applying the base-table filter outside
the join returns the same 670 rows in 5 ms. A cost per surviving row fits H2 rebuilding `TABLES`
for each probe of the join. That mechanism is not traced in H2's source; the scaling is the measured
fact.

**Every borrow pays the read cold, drifted or not.** H2 caches a query's result until the database
changes. A borrow always runs a `TRUNCATE` just before the reset, so the read is never served from
that cache. That is the 28 ms a clean borrow pays today.

**The boot pays for the same join.** `GraphPartition.keyedBaseTables` joins `COLUMNS` to `TABLES` to
list the base tables that carry `graph_name`. It returns 217 rows and takes 64 ms cold, out of a
286 ms `FactStores.inMemory()` boot. `GraphitronModelStore.partitionSelectivity` calls it once per
store creation.

A prototype of the implementation below gave these numbers:

* **Reset after 670 drifted columns:** 25 ms instead of 178 ms.
* **Reset with nothing drifted:** 9 ms instead of 28 ms.
* **`keyedBaseTables`:** 5 ms instead of 64 ms, returning an identical list.
* **End state:** after the reset, no column differs from a created store's.

## What it costs at module scale

Measured 2026-10-12 at `86a38bf`, 4 vCPU web sandbox, before the cause above was found. These
figures price the reset for `graphitron`, the module that pays the most for it.

**It is the largest single project frame in `graphitron`'s test CPU.** The run was an unforked
`mvn test -pl :graphitron -DforkCount=0` under JFR (`settings=default`, 36,459 execution samples).
`StoreStatistics.reset` is the first project frame on **12.2%** of samples, plus 1.0% in its
lambda. For comparison, `GraphitronModelStore.create`, the DDL boot, is 6.7% as a first project
frame.

**The profile does not price the saving.** The module's test phase is CPU-bound, so 13% of a test
phase of about 430 s suggests something like 55 s. That is a projection, and the `build-profile`
skill is explicit that a profile finds where time goes and never prices it. The Tests section below
prices the change by A/B.

Three things to know before re-reading that profile:

* **Not a valid test result.** The unforked run ended with 35 failures out of 4,360 tests, from the
  working-directory and isolation changes the `build-profile` skill warns `forkCount=0` brings. Use
  it for proportions only.
* **Taken before INFO logging.** It predates trunk setting the test modules' logging to INFO
  (`106f1b9`), so jOOQ's statement logging was 6.6% of the samples. Without that, the reset's share
  of what is left is slightly larger.
* **Fewer captures compound with this fix.** `StoreCostExtension` (`GRAPHITRON_STORE_COST_REPORT`)
  counted 888 captures and 273 boots for the module on a forked run of the same tree. The reset
  follows captures, so capturing less and resetting cheaply compound. This item does only the
  second.

## Implementation

The fix has two parts. Each catalog read that joins `COLUMNS` to `TABLES` becomes two reads: the base
tables from `INFORMATION_SCHEMA.TABLES` (`TABLE_TYPE = 'BASE TABLE'`, under 0.1 ms), then
`INFORMATION_SCHEMA.COLUMNS` alone with the same predicates, keeping only rows whose table is in that
set. The rest of each method is unchanged: its contract, its ordering and its `ALTER`s.

* **`StoreStatistics.reset`** (`graphitron-model` test sources): read the drifted columns from
  `COLUMNS` with the existing selectivity predicate and the `TABLE_NAME, ORDINAL_POSITION` ordering,
  then drop rows outside the base-table set. The base-table read can be
  `ThreadConfinedStore.baseTables`, which lives in the same package and already reads exactly that.
  The `ALTER` loop stays as it is.
* **`StoreStatistics.resetIncludingTheDeclaration`:** the same change. It returns every base-table
  column, about 2,400 rows, so the join costs it the most of the three. Its only caller is
  `PartitionSelectivityWorthTest`.
* **`GraphPartition.keyedBaseTables`** (`graphitron-model` main sources): read `TABLE_NAME` from
  `COLUMNS` where `COLUMN_NAME = GraphPartition.COLUMN`, ordered by name, and keep the tables in the
  base-table set, which it reads itself since main code cannot reach the test helper. The returned
  list must stay identical in content and order; the prototype confirmed it is.
* **Javadoc on all three:** say in one sentence why the read is not a join (H2 charges for the join
  per surviving row, which made it the reset's whole cost). Without that sentence, the next tidy-up
  folds it back into a join.

## Tests

* **New: the clear hands back a created store's statistics.** `ThreadConfinedStoreTest` has no case
  on statistics today, so nothing pins what this change must preserve. Add one. Inside a `run` body,
  seed a graph and run a whole-database `ANALYZE`. That moves columns on the seeded tables, which
  the clear truncates, and on the populated `meta_` tables, which it does not. The next `run` body
  then compares the selectivity of every base-table column with a snapshot taken from a fresh
  `FactStores.inMemory()` store, and the two must be equal. Equal snapshots imply the partition
  column reads `DECLARED_SELECTIVITY` and every other column reads `UNANALYSED`. The case must fail
  against a reset that skips a non-truncated table, and against one that drops the partition
  restatement; check both by hand while implementing.
* **`keyedBaseTables` is already covered.** `PartitionSelectivityTest` asserts the list has more than
  100 entries and that each carries the declaration, on a created store and on a reopened one. If a
  view leaked into the list, the boot's `ALTER` would be refused and every store creation would fail.
* **Existing callers keep passing:** `StagePrerequisiteStatisticsTest` and
  `PartitionSelectivityWorthTest` run against the rewritten reads unchanged.
* **Evidence for the Done gate is an A/B, not a timing assertion.** The In Review commit message
  records medians before and after the change for two things. First, a borrow that follows a
  `CapturedStore.ofCatalog` capture, the original 2026-09-28 measurement, rerun. Second, the forked
  `graphitron` test phase's wall clock, both sides on the same machine. The goal holds if the borrow
  after a capture costs a small fraction of a boot. A cost assertion in the suite would be flaky on
  shared runners and is deliberately not added.

## Other solutions we've considered

* **Batching the `ALTER`s into one JDBC batch:** 151 ms against 145 ms for the current loop. The
  `ALTER`s were never the cost.
* **`ANALYZE TABLE` on each truncated table:** H2 sets every column of an empty table back to
  unanalysed. That was 138 ms, because the read still dominated. It also cannot reset the populated
  `meta_` tables, which a capture's whole-database `ANALYZE` moves too.
* **Reading `Column.getSelectivity` through H2's embedded session:** the read drops to 0.6 ms from
  5 ms. But a test fixture would then reach into `org.h2.engine` internals, and assigning
  selectivities without DDL would stop the reset from invalidating H2's cached plans, which the
  `ALTER` path does today. The 4 ms left is not worth either cost.
* **Not letting statistics drift inside test captures:** this would change what the captures
  observe. `ModelCapture.capture` runs `ANALYZE` on purpose around the derivations, and
  `DerivationStratum` analyses each step's writes, so test captures would plan differently from
  real runs.
