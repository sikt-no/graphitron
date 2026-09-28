---
id: R981
title: "Stop the thread store's clear paying almost a boot to reset statistics after a capture"
status: Backlog
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-09-28
last-updated: 2026-09-28
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
