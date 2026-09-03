---
id: R917
title: "A store too large to service is discarded rather than cleared"
status: Backlog
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-09-03
last-updated: 2026-09-03
---

# A store too large to service is discarded rather than cleared

## Goal

A run that meets a fact store too large to service gives up on it in milliseconds and starts from a
fresh one, rather than spending minutes on a clear that will not finish. The fact store is the
per-workspace H2 cache of facts about a consumer's schema and classpath; a *clear* is the
delete-then-rewrite a warm run does over the partition it owns. When this lands, no run can spend
more than a moment discovering that its cache is unusable.

## Provenance

Split out of R914, which bounds the store's growth by compacting at the last handle's release. That
fix stops producing the state this item guards against, which is why it is not a condition of it: a
consumer picks R914 up by taking a release, taking a release rotates `stampSegment()`, and the run
carrying the fix therefore opens a fresh directory rather than the oversized one. This item covers
the states that fix does not reach: a store that grew before the fix and is still inside
`StoreReaper`'s retention, a `-Dgraphitron.store.directory` pinned across versions, and any future
mechanism that lets a file grow again.

## What is known

On the reporting consumer of [issue 544](https://github.com/sikt-no/graphitron/issues/544), a 21 GB
store made `StoreRefresh.clear` spend 2 min 18 s of a 3 min 28 s run, end in `Timeout trying to lock
table "JVM_METHOD"` at the 60 s `FILE_LOCK_MILLIS` budget, and demote the run to an in-memory capture
that paid another minute capturing cold. Compaction cost also grows faster than the file does (829 ms
for 443 MB, 4.1 s for 864 MB, both measured 2026-09-03 against real store copies on H2 2.4.240), so
a large enough file is not something a run may compact its way out of either.

## Open questions

* What threshold makes a store too large to service, and whether it is stated in bytes or as a
  multiple of what a cold capture of that graph produces. A cheap pre-check on file size is the
  obvious shape; what it compares against is not settled.
* Whether the per-source delete should be a partition drop rather than a row-by-row `DELETE`, which
  would change how much size the clear can absorb before the guard is reached at all.
* Whether the editor's read path wants the same guard. A query joining `intent_resolved_field_claim`
  and `intent_column_match_claim` per field ends in H2 `57014` on a bloated store, which is
  `StoreReader`'s bounded `ReadBudget` expiring rather than anything the clear touches. R914 should
  lift those reads back under budget; if it does not, the evidence lands here.
