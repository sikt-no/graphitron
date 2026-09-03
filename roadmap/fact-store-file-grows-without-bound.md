---
id: R914
title: "The fact store cache grows without bound, and a large store stalls every build that opens it"
status: Spec
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-09-03
last-updated: 2026-09-03
---
# The fact store cache grows without bound, and a large store stalls every build that opens it

## Goal

A workspace's fact store stays a cache: bounded in size, cheap to refresh, and never the reason a
build waits. When this lands, a consumer running `graphitron:generate` (including Quarkus dev mode,
which runs the goal on every start) pays seconds for the store rather than minutes, the cache on a
developer's machine stays a size they would not think to delete, and the log names the store when it
is the cost. This is [issue 544](https://github.com/sikt-no/graphitron/issues/544).

## What is wrong

**A store file is almost entirely dead space.** A 443 MB store from a real workspace holds 193,863
rows across 151 tables, a few tens of MB of live facts. The rest is chunks that MVStore has not
reclaimed. A file-backed store closes with a plain connection close, so H2 gets its default 200 ms of
compaction, which at these sizes reclaims nothing, and every clear-and-recapture cycle leaves more
behind. On the reporting consumer the file reached 21 GB after nine days.

**The cache is bounded by a count of directories, not by bytes.** `StoreReaper.sweep` keeps
`RETAINED_STAMPS` (three) stamped directories per workspace, the live one included, and evicts the
rest by recency. That caps how many stores a workspace holds and says nothing about how large any of
them may grow. The sweep also runs only on the home the current build opens, so a workspace nobody
builds in any more is never revisited and keeps its three stores indefinitely.

**A stamp rotation multiplies that across every workspace at once.** `stampSegment()` is the first
sixteen hex digits of the DDL hash plus the generator version. A consumer rotates it by taking a new
release; this repo rotates it on any edit to `graphitron-model.sql`. Each rotation pushes a fresh
full-size store into every workspace's three retained slots.

**A large store stalls a run in two different ways.** On the write path, `StoreRefresh.clear` deletes
row by row with `SOURCE_NAME IN (...)` per table; on the reporting consumer that took 2 min 18 s of a
3 min 28 s run, ended in `Timeout trying to lock table "JVM_METHOD"` at the 60 s `FILE_LOCK_MILLIS`
budget, and demoted the run to an in-memory capture that then paid another minute capturing cold. On
the read path, the per-field query that hydrates intent claims fails with H2 `57014` (statement
cancelled). A fix aimed only at the clear leaves the read path standing.

## What the measurements show

Measured 2026-09-03 with the pinned H2 2.4.240, against copies of real stores.

| Store | Size | After `SHUTDOWN COMPACT` | Reclaimed | Time |
|---|---|---|---|---|
| A workspace store, idle | 443 MB | 26.7 MB | 94% | 829 ms |
| The largest store on the machine | 864 MB | 59.9 MB | 93% | 4.1 s |

A machine carrying ten workspaces held 7.4 GB across 21 `store.mv.db` files, no workspace holding
more than the three the reaper retains, and 1.81 GB of it in a worktree last built on 2026-08-22.

Compaction cost grows faster than the file does across those two points, so a 21 GB file may still
cost minutes to compact. That is the reason for the size pre-check below rather than an argument
against compacting.

## Plan

1. **Compact on close.** Issue `SHUTDOWN COMPACT` when a run closes a file-backed store, in place of
   the plain close. One call site, no schema or protocol change, and a consumer picks it up by taking
   the release. This is the smallest change that addresses the root, and it ships first and alone.
   It needs a check that the closing run owns the store, since `SHUTDOWN COMPACT` closes the database
   for every connection: correct at the end of a `generate`, wrong in the middle of a held dev
   session.
2. **Fail fast on a store too large to service.** A cheap pre-check on file size sends a run straight
   to a fresh store rather than attempting a clear, or a compaction, that will not finish in a time a
   build may spend. Whether the per-source delete should be a partition drop rather than a row-by-row
   `DELETE` belongs here too.
3. **Bound the cache in bytes, and reach the workspaces no build opens.** Give the cache home a byte
   budget spanning its workspaces, and a way to reclaim a workspace that has gone quiet. This is the
   axis that produced most of the 7.4 GB, so it is not a follow-up to the file-level fix.
4. **Let two processes in one checkout both keep warm.** `graphitron:dev` and `quarkus:dev` (which
   runs `generate`) lose warm start to each other every round. The second process is already refused
   in under a second, so the question is whether the loser can keep a warm store of its own, not what
   the lock budget is. The budget still matters for the in-JVM case where a concurrent or leaked
   connection holds a table.
5. **Make the cost visible.** Log time spent in the store per run and the file's size, so a developer
   reading a slow build sees the store named rather than inferring it from a thread dump.

## Verification

A growth curve across repeated real captures with compact-on-close enabled, measured on this repo,
which reproduces the mechanism without any consumer checkout. The store's size after each capture is
the measurement; a bounded curve is the pass.

## Considered and rejected

`RETENTION_TIME=0` on the store's JDBC URL, on the theory that a run shorter than H2's 45 s retention
window can never reclaim the chunks it writes. Twelve open, clear and recapture cycles against a
single-table file store are bounded with and without it: the baseline settles at 8.8 MB against
4.2 MB of live rows, and the flag only lowers the transient peak from 55 MB to 19 MB. A reopened
store restarts its retention clock, so the following run reclaims the previous run's chunks.

## Open questions

* Which property of a 151-table capture defeats the reclamation that a single-table control shows
  working. Compact-on-close makes this moot for the fix, but the answer decides whether compaction
  must run on every close forever or is covering for something addressable.
* What threshold makes a store "too large to service", and whether it is stated in bytes or as a
  multiple of what a cold capture of that graph produces.
* Whether the read-path stall is bounded by the same size guard as the clear. Discard-and-rebuild
  fixes it only if the rebuilt store is small enough for the query to complete.
* Whether the hard-killed-store item (R757), which also ends in "discard a file no run can warm
  from", shares the discard mechanism this item needs.

## Out of scope

The efficiency of the dev session's index and refresh loop, which is R916. That is a question about
how much the loop re-reads per round; this item bounds the cache the loop fills.

## Workaround for consumers until it lands

Delete the cache (`~/Library/Caches/graphitron` on macOS, `$XDG_CACHE_HOME/graphitron` or `~/.cache/graphitron` on Linux, `%LOCALAPPDATA%\graphitron` on Windows);
the store rebuilds on the next run. Or point `-Dgraphitron.store.directory` at a path under the build
directory so `mvn clean` clears it. A checkout running `graphitron:dev` beside `quarkus:dev` can pass
`-Dgraphitron.dev.skipInitial=true` to the dev goal so only one of them captures at start.
