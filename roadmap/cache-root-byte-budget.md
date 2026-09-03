---
id: R918
title: "The fact store cache root is bounded in bytes, and quiet workspaces are reclaimed"
status: Backlog
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-09-03
last-updated: 2026-09-03
---

# The fact store cache root is bounded in bytes, and quiet workspaces are reclaimed

## Goal

The graphitron cache costs a developer's disk a bounded number of bytes across every workspace on the
machine, and a workspace nobody builds in any more stops holding stores indefinitely. The *cache
root* is `<userCacheRoot>/graphitron/model`, the directory holding one per-workspace *home* per
checkout; a home in turn holds one *stamped directory* per DDL-hash-and-version combination it has
seen. When this lands, the root has a byte budget and a way to reclaim quiet workspaces, so the cache
stays a size a developer would not think to delete.

## Provenance

Split out of R914, which compacts each store at the last handle's release. That fix takes most of the
measured footprint out on its own, which is why this is a disk-footprint item rather than a stall:
the 7.4 GB below was 21 uncompacted files, and at the 93% to 94% reclamation measured there the same
machine holds a few hundred MB. What compaction does not touch is the shape of the bound.

## What is wrong

**The cache is bounded by a count of directories, not by bytes.** `StoreReaper.sweep` keeps
`RETAINED_STAMPS` (three) stamped directories per home, the live one included, and evicts the rest by
recency. That caps how many stores a workspace holds and says nothing about how large any of them may
grow.

**The sweep only reaches the home the running build opened.** `GraphitronModelStore.sweepOnce` is
driven by an opener and guarded by `SWEPT_HOMES`, so a workspace nobody builds in any more is never
revisited and keeps its retained stores indefinitely. Measured 2026-09-03: a machine carrying ten
workspaces held 7.4 GB across 21 `store.mv.db` files, no workspace over the three the reaper retains,
and 1.81 GB of it in a worktree last built on 2026-08-22.

**A stamp rotation multiplies that across every workspace at once.** `stampSegment()` is the first
sixteen hex digits of the DDL hash plus the generator version, so a consumer rotates it by taking a
release and this repo rotates it on any edit to `graphitron-model.sql`. Each rotation pushes a fresh
store into every workspace's retained slots.

## The level the budget lives at

Two levels have to be kept apart, because the tree already uses "home" for the lower one.
`AbstractRewriteMojo.resolveStoreDirectory` returns
`<userCacheRoot>/graphitron/model/<workspace-segment>`, and that per-workspace directory is the home
an opener hands `StoreReaper.sweep`. The level that held the measured 7.4 GB is its parent, the cache
root: a directory no opener is ever given and that `graphitron-model` cannot see, since the only
resolver that knows it lives in `graphitron-maven-plugin`. A byte budget over it therefore needs an
owner that does not exist today, which is what makes this larger than the sweep it sits beside.

## Open questions

* Which module owns a budget over the cache root, given that the resolver and the reaper are in
  different modules and the reaper is only ever handed one home.
* What the budget means when a consumer pins `-Dgraphitron.store.directory`, which
  `resolveStoreDirectory` takes verbatim: there is no sibling workspace set under a pinned home, so
  the budget either degrades to that one home or does not apply.
* Whether reclaiming a quiet workspace is the same mechanism as R757's "discard a file no run can
  warm from", which ends in the same place from a different start.
