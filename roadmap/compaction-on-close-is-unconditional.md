---
id: R937
title: "The fact store compacts on every close, at full price, whether or not there is anything to reclaim"
status: Backlog
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-09-08
last-updated: 2026-09-08
---

# The fact store compacts on every close, at full price, whether or not there is anything to reclaim

## Goal

Closing a fact store costs time proportional to what compaction actually reclaims, rather than to
the size of the store. Today it is proportional to the store's live bytes and is paid whether or
not there is a single dead page to drop, which on a real store is about **1.6 seconds on every
close** for a 2% reclaim.

A *compaction* here is `SHUTDOWN COMPACT`, which rewrites the live pages of an H2 database and drops
the rest. `GraphitronModelStore.close` issues it from `compactIfLast` when the closing handle is the
last one on a file-backed store; an in-memory store takes a plain `SHUTDOWN` instead and is not
affected by any of this.

## Why it is worth an item

The mechanism is right and the reasoning behind it is recorded correctly: a plain close gives H2 its
default 200 ms of compaction, which on a store cleared and rewritten a few hundred times reclaims
nothing, so the file grows without bound while the rows in it do not. What it lacks is a condition.
Compacting unconditionally means paying the full rewrite on a store that has no dead pages, and
that is the steady state for a warm store, because the previous close already compacted it.

Measured 2026-09-08 at `7a3fae6e`, opening a copy of each store and timing its close, with the
compaction's own reported millis and byte counts:

| Store | Live data | Compaction | Reclaimed |
|---|---|---|---|
| plugin test fixture | 0.7 MB | 41 ms | 2.5 to 0.7 MB, 70% |
| capture-only fixture | 1.2 MB | 72 to 79 ms | 1.9 to 1.2 MB, 37% |
| plugin it-store | 15.8 MB | 788 to 1069 ms | 16.5 to 15.8 MB, 4% |
| the real build store, `~/.cache/graphitron/model` | 33.4 MB | **1561 to 1791 ms** | 34.1 to 33.4 MB, **2%** |

Cost is linear in the live bytes rewritten, about **47 to 60 ms per MB**, and it does not vary with
how much there is to reclaim. The two small fixtures are the case the mechanism was designed for and
it serves them well. The two large ones are the case it was not: full price, almost no return.

**The exposure is consumer-facing rather than build-facing.** The build's file-backed classes,
`PersistentStoreTest` and `WarmStartRefreshTest`, run on small fixtures and pay a few seconds in
total. What pays the real price is every `graphitron:generate`, every `graphitron:dev` session, and
every language-server or MCP shutdown, each closing a store of the size in the last row.

## What a Spec pass has to settle

* **What the condition is.** Candidates, cheapest first: a size threshold; asking H2 what it would
  reclaim before deciding; tracking whether this handle's session wrote or cleared anything at all,
  since a read-only session cannot have created dead pages; or compacting on a cadence rather than
  on every close. The last of these keeps the file bounded without paying per close and is probably
  the shape, but it needs a home for the "when did we last compact" fact.
* **Where a throwaway store opts out entirely.** A test that copies a template store and deletes it
  moments later should never compact. That is 39 to 93 ms of pure waste per case today, and it is
  the one place the answer is unambiguous.
* **Whether the reclaim is even needed once the census is cut.** R762 removes 97% of the census,
  which is 99.3% of this store's rows, so it takes the store from 34 MB to a few hundred KB and this
  cost with it. Sequenced after R762 this item may be worth much less; sequenced before, it is worth
  the table above. That ordering is the main thing to decide, and it is why this item sits at
  priority 3 rather than higher.

## What this item does not do

It does not revisit whether compacting is the right way to keep the file bounded. It is, and the
tests that pin it firing should stay. This item adds a precondition to an unconditional act.

## How to re-measure

```
Copy a store directory to a temp dir, openAt it, close it, and read the reported compaction:
GraphitronModelStore.compaction() returns millis, bytesBefore and bytesAfter on the handle that
was the last one on the file. Do it twice per store: the first close compacts whatever the build
left, the second is the steady state a warm store actually pays. Copy rather than opening the
real cache store, which the close would mutate.
```
