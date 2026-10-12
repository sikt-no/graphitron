---
id: R981
title: "Stop the thread store's clear paying almost a boot to reset statistics after a capture"
status: Backlog
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
and empties between cases) cost what its javadoc says it costs after a real capture, not almost what
a fresh boot costs. Today a borrow that follows a catalog capture takes about 200 ms against a
272 ms boot on the same small schema, and nearly all of it is `StoreStatistics.reset`: the capture
leaves about 670 columns with measured selectivity, and the reset issues one `ALTER TABLE ... ALTER
COLUMN ... SELECTIVITY` per drifted column (truncate plus the leak census together cost about 6 ms).
So a module that moved its per-case fixtures from booting to borrowing, as `graphitron-lsp` did, cut
its boots from 207 to 54 and saw no wall-clock change. When this lands, the reset after a capture
costs a small fraction of a boot and that move pays off.

## Notes for whoever picks this up

* Measured on 2026-09-28 in a web sandbox (4 cores) with a scratch test over
  `CapturedStore.ofCatalog` and `ThreadConfinedStore.borrow`; rerun it rather than trusting the
  figures on another machine.
* The reset's contract is `StoreStatistics.reset`'s javadoc: the end state must be what a created
  store carries, the partition-column declaration included. Anything cheaper has to keep that.
* Candidate levers, unmeasured: batching the `ALTER`s into one round trip; finding why a capture
  leaves so many columns analysed (an explicit `ANALYZE` in the derivation stratum, or H2's own
  threshold) and whether the clear can undo that per table rather than per column; or not letting
  the statistics drift inside test captures at all, if that does not change what the capture tests
  observe.

## What it costs at module scale

Measured 2026-10-12 at `86a38bf`, 4 vCPU web sandbox. The figures above price one borrow; this
prices what the reset is worth to the module that pays it most.

**It is the largest single project frame in `graphitron`'s test CPU.** An unforked
`mvn test -pl :graphitron -DforkCount=0` under JFR (`settings=default`, 36,459 execution samples)
puts `StoreStatistics.reset` as the first project frame on **12.2%** of samples, plus 1.0% in its
lambda. For comparison, `GraphitronModelStore.create`, the DDL boot, is 6.7% as a first project
frame. Bucketed by what is on the stack rather than by first frame: H2 reads outside capture and
boot 37%, `FactCapture` 28%, the reset 13%, the boot 12%.

**CPU share is close to wall-clock share here, but that is an inference.** The module's test phase is
CPU-bound: machine utilisation sat at 0.99 for most of a forked run and fell off only in the last
minute and a half. So 13% of a test phase that is now about 430 s suggests something like 55 s
available. That is a projection from a profile, and the `build-profile` skill is explicit that a
profile finds where time goes and never prices it. Price the change by removal or by A/B before
quoting a saving.

Three things to know about that profile before re-reading it:

* The unforked run ended with 35 failures out of 4,360 tests, the working-directory and isolation
  changes the `build-profile` skill warns `forkCount=0` brings. The run is readable for proportions,
  not as a test result.
* It was taken before trunk set the test modules' logging to INFO (`106f1b9`), so jOOQ's statement
  logging was 6.6% of the samples. With that gone, the reset's share of what is left is slightly
  larger, not smaller.
* `StoreCostExtension` (`GRAPHITRON_STORE_COST_REPORT`) gave the module's totals on a forked run of
  the same tree: 888 captures and 273 boots. The reset follows captures, so lowering the capture
  count (the corpus sweeps that each capture the whole corpus) and making the reset cheap compound.
