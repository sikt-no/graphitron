---
id: R769
title: "withSeededStore boots the schema 420 times in one module; a row reset costs 0.85ms against 138ms"
status: Spec
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-21
---

# withSeededStore boots the schema 420 times in one module; a row reset costs 0.85ms against 138ms

When this lands, `graphitron-model`'s test suite stops executing the fact schema's 1894 DDL statements
420 times and executes them **four times**, once per test thread, resetting rows between cases
instead. The module's test-class time falls from about 182 seconds to about 49, its wall clock from
46.8 to roughly 14, and the full build loses about **33 seconds**. It is the first slice of R768, which
measured the same waste across four modules at 1051 boots and 395.8 seconds; this one takes the module
where the waste is the highest share of the work and where it flows through a single helper, so the
mechanism is proved once and cheaply before the other three modules adopt it.

Everything below restates the facts it needs rather than pointing into another item's body, because
those bodies are deleted when they reach Done.

## The measurement

One 4 vCPU, 15 GB sandbox, warm local repository. Boots were counted with an env-guarded `AtomicLong`
pair around `GraphitronModelStore.create`, incremented per completed schema application and dumped by a
shutdown hook, so the figure is executions rather than call sites. Reset and boot costs were measured
against a booted in-memory store over 20 warm rounds.

| | |
|---|---|
| boots in `mvn test -pl :graphitron-model -Plocal-db` | **420** |
| time inside the DDL | **133.0 s** |
| per boot, in situ, four threads on four cores | 317 ms |
| per boot, measured alone | 138 ms |
| module wall clock | 46.8 s |
| module test-class time | 182.3 s |
| **`TRUNCATE` across the 143 clearable tables** | **0.85 ms** |

So boots are **73% of this module's test-class time**, and the replacement is **160 times cheaper**
than the thing it replaces. The module is the reactor's clearest case: `graphitron` spends more
absolute time booting (146.5 s) but over a much larger suite, and its boots do not funnel through one
helper.

Why the per-boot figures differ: 317 ms is a boot under four-way contention, which is the cost as
actually paid; 138 ms is a boot with the box to itself. The saving projection below uses the in-situ
total, because that is what disappears.

## The funnel, and it is one helper

`SeededStore.withSeededStore(Consumer<DSLContext>)` opens `FactStores.inMemory()`, which is
`GraphitronModelStore.open()`, in a try-with-resources that closes it when the body returns. Its
graph-anchoring overload delegates to it. **161 call sites across 30 test classes** reach it, and 420
runtime boots come out, because parameterized cases multiply a call site into executions.

Four classes in the module boot directly instead, at seven sites, and every one of them has the boot or
the schema's shape as its subject rather than as setup: `StoreReaderTest` (which also takes the
`fileBacked` and `reader()` paths), `MaterializationOrderTest`, `MaterializeRegistryGateTest` and
`CommentRenderabilityGateTest`. **They are out of scope and stay exactly as they are.** Naming them is
load-bearing: the saving is computed net of them, and a change that routed them through a shared store
would delete the coverage they exist for.

## What a reset has to do, and what it does not

A booted store holds rows in exactly two places outside the fact relations: the `meta_` family, whose
authored rows the DDL seeds with an `INSERT`, and `store_stamp`. A reset clears the other **143** base
tables and leaves those two alone. On a fresh boot every one of the 143 is empty, which is what makes
`TRUNCATE` over the whole list the right shape rather than a curated one.

**Re-deriving the dependency materialization is not part of a reset, and establishing that is what
makes this slice cheap.** R768 left this open as a 10x swing on its own premise, so it is settled here.
`GraphitronModelStore.open` calls `MaterializeDependencies.populate`, which costs 8.4 ms, ten times the
clear. But `populate` derives its edges from `Materializations.registrations` and from the stored view
definitions it reads out of `INFORMATION_SCHEMA`; it reads no fact relation. So no row a test writes
can invalidate `meta_materialize_dependency`, and clearing rows cannot either. Verified twice: by
reading `populate`, and by observing the relation byte-identical across a clear. A reset is therefore
the clear alone, 0.85 ms, and not 9.3 ms.

The *other* derivation is already handled and must not be confused with it. `SeededStore.derive`, which
every case calls once its rows are seeded, is `Materializations.refreshAll`, and that one does depend on
fact rows. It is called per case today and will be called per case after this change, so it is neither
new cost nor a reset concern.

## Implementation

**`withSeededStore` takes a thread-confined store from a `ThreadLocal` rather than opening one.** First
call on a thread boots and keeps it; every call resets first, then runs the body. The store is not
closed when the body returns, which is the whole change: an in-memory H2 dies with the JVM, so nothing
leaks beyond the fork. Under the module's four-thread class-level parallelism that is four boots per
surefire JVM.

**The reset lives beside the store rather than in the fixture.** It needs the table list from
`INFORMATION_SCHEMA` (base tables in `PUBLIC`, excluding `META\_%` and `STORE_STAMP`), `SET
REFERENTIAL_INTEGRITY FALSE` around the truncates, and the list computed once per store rather than per
reset. Whether this is a new method on `GraphitronModelStore`, an addition to `StoreRefresh`, or a
test-only helper in `FactStores` is the one design fork worth stating: `StoreRefresh` already owns
row-clearing vocabulary, and extending it beats standing a second mechanism beside it, but its contract
may be per-graph where this wants per-store. Settle that before writing the method, and do not
duplicate the truncate loop into the fixture if `StoreRefresh` can carry it.

**Nothing else in the fixture changes.** `withSeededStore(String graphName, ...)` keeps delegating; the
seed helpers, `SEED_SOURCE`, and `derive` are untouched. No test class changes, which is the property
that makes this slice worth doing first: 161 call sites and 30 classes adopt it by not changing.

## Tests

**A leak guard, and it is the only part of this that can fail silently.** A case passing because a
previous case's row survived the reset is worse than a slow suite, and it would not announce itself.
The guard has to be positive: after each reset, assert every clearable table is empty. Two decisions
for the reviewer. Whether it runs always, which prices 143 `COUNT(*)` queries into every one of 420
cases and may cost more than the boots did, or only under a system property that CI sets on a nightly
run. And whether the table list is re-derived per assertion or once, since a table added to the schema
must not silently stop being cleared *or* checked.

**A boot-count pin.** The mechanism's whole claim is a number, so a test should assert it: after the
suite, boots per JVM are bounded by the thread count plus the four direct-boot classes' own opens. That
turns the counter from a throwaway instrument into a standing invariant, and it is what catches a
future helper that opens a store per case again. It needs the counter to become a real, if internal,
observable rather than the env-guarded patch R768 used.

**The existing suite is the correctness acceptance.** Thirty classes and 420 cases pass unchanged, and
they pass with the classes shuffled: sequential-order dependence is exactly the defect a shared store
can introduce, and JUnit's random class-order option is the cheapest way to hunt it. Run it three
times, not once.

Verification of the win is `mvn test -pl :graphitron-model -Plocal-db` before and after, alternated,
plus the boot counter reporting four rather than 420. Expect the module near 14 s against 46.8 s.
Absolute seconds differ per machine; the boot count does not.

## Roadmap entries

* R768 carries the reactor-wide measurement, 1051 boots and 395.8 s, and the remaining three modules.
  It should record that the re-materialization question this item settles is settled, and in which
  direction, because its saving estimate was computed on the conservative reading.
* R759 cuts the price of a boot, this cuts their number, and the two compose. R759's alias removal is
  already in the 138 ms and 317 ms figures above rather than double-counted against them.

## What this item deliberately does not do

**It does not touch `graphitron`, `graphitron-lsp` or `graphitron-mcp`**, which are the other 631 boots
and 262.8 seconds. Their boots do not funnel through one helper, so adopting this needs a per-module
read of how each reaches the store, and it should happen after the mechanism has run green here for a
while. Doing all four at once would put the leak guard's design and three modules' fixture surgery in
one review.

**It does not make the store shared across threads.** One store for four threads would serialize every
fixture write behind a lock and trade a measured cost for an unmeasured one. Per thread is the shape
that keeps cases independent, and four boots is already 99% of the saving that one boot would give.

## How to re-measure

```bash
# Boot count. Add an AtomicLong pair around the statement loop in GraphitronModelStore.create
# plus a shutdown hook that appends "<count> <millis>" to the file an environment variable names,
# then install the model and run the module. Revert before trusting anything else on the tree.
mvn install -pl :graphitron-model -Plocal-db -Pquick
GRAPHITRON_BOOT_COUNT_FILE=/tmp/boots.txt mvn test -pl :graphitron-model -Plocal-db
awk '{c+=$1; t+=$2} END {print c" boots, "t" ms"}' /tmp/boots.txt

# Reset cost. Open one store through GraphitronModelStore.open(), list INFORMATION_SCHEMA.TABLES
# for BASE TABLE in PUBLIC excluding META\_% and STORE_STAMP, and time TRUNCATE over that list
# with SET REFERENTIAL_INTEGRITY FALSE around it. Twenty rounds; the first is cold and reads high.
```
