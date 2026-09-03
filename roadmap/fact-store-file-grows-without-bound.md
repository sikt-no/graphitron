---
id: R914
title: "The fact store file grows without bound, and a warm clear on a huge file costs minutes before it times out"
status: Backlog
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-09-03
last-updated: 2026-09-03
---

# The fact store file grows without bound, and a warm clear on a huge file costs minutes before it times out

## Goal

A workspace's fact store stays a cache: bounded in size, cheap to refresh, and never the reason a
build waits. Today the H2 file under the per-user cache directory (`store.mv.db`, one per checkout and
stamp) grows on every warm capture and nothing ever shrinks it. Once it is large, the clear a warm run
performs takes minutes, hits the lock budget, and the run falls back to an in-memory capture anyway.
When this lands, a consumer running `graphitron:generate` (including Quarkus dev mode, which runs the
goal on every start) pays seconds for the store, not minutes, and can see from the log when the store
is the cost. This is [issue 544](https://github.com/sikt-no/graphitron/issues/544).

## What the issue reports

Measured on a consumer schema (opptak) with generator 10.0.0-RC35, `mvn -o -pl <module>
graphitron:generate` alone took 3 min 28 s. About 2 min 18 s of that was `StoreRefresh.clear`, the
row-by-row `DELETE ... WHERE SOURCE_NAME IN (...)` over the `jvm_` partitions of a warm store, with
the main thread sampled inside H2's MVStore (`lockRow`, `readPage`, `rollbackTo`). The clear ended
with `Timeout trying to lock table "JVM_METHOD"` at the 60 s lock budget, the run demoted to memory
with the "could not use the shared fact store" warning, and then spent another minute on the cold
capture. Both halves are paid on every run.

The file for that checkout was 21 GB after nine days. A sibling worktree on the same machine was
478 MB after brief use. A file-backed store closes with a plain connection close, so H2 gets its
default 200 ms of compaction, which on a file that size reclaims nothing; each clear-and-rewrite
cycle leaves more dead chunks behind. The store's trace file also shows 36 "The file is locked"
refusals (a second process, `graphitron:dev` running beside `quarkus:dev` in the same checkout, taking
the fast fallback) and 13 lock timeouts (contention inside one JVM, or the giant delete itself).

## Why trunk does not already fix it

Checked on trunk at 2026-09-03 against RC35. The reaper (R858) releases stale stamped *directories*
under a home and spares the live one by design; it never compacts the file it keeps. The dev-session
single-store change and the fact-tier move (R865) change which process owns the file, not its size.
The cold-refresh cadence (R867) only affects a store holding no graph. `FILE_LOCK_MILLIS` is still
60 000, `StoreRefresh.clear` still deletes by `SOURCE_NAME IN (...)` per table, and the only store log
lines are the demotion warnings and the reaper's freed-bytes report.

## What the item has to settle

The issue asks for four things; the Spec decides which belong to one item and which split off.

1. **Bound the file.** Compact on close (`SHUTDOWN COMPACT`, or a `MAX_COMPACT_TIME` that scales
   with the file rather than H2's 200 ms default), or discard and rebuild the stamped directory once
   the file passes a threshold, treating it as the cache the reaper already says it is. A rebuild
   costs one cold capture, which is what the current state pays on every run.
2. **Fail fast.** A clear that is going to take minutes should not be attempted. A cheap pre-check
   (file size, or row counts in the partitions about to be dropped) can send the run straight to a
   fresh store or to memory. Consider whether the per-source delete should be a partition drop rather
   than a row-by-row `DELETE`.
3. **Two processes in one checkout.** `graphitron:dev` and `quarkus:dev` (which runs `generate`) lose
   warm start to each other on every round today. The second process is refused in under a second
   already, so this is about whether the loser can keep a warm store of its own rather than about the
   60 s budget; the budget matters for the in-JVM case where a leaked or concurrent connection holds a
   table.
4. **Make the cost visible.** Log the time spent in the store per run and the file's size, so a
   developer reading a slow build sees the store named rather than inferring it from a jstack.

## Open questions for the Spec

* Whether the growth is H2 chunk retention (`RETENTION_TIME`, 45 s by default, longer than most
  captures live) or the undo log of one huge delete transaction. The answer decides between compacting
  at close and never issuing the delete in the first place.
* What threshold, if any, is the right one for "too big to clear", and whether it is stated in bytes
  or as a multiple of the size a cold capture of that graph produces.
* Whether the existing hard-killed-store item (R757), which also ends in "discard a file no run can
  warm from", shares the discard mechanism this item needs.

## Workaround for consumers until it lands

Delete the cache (`~/Library/Caches/graphitron` on macOS, `$XDG_CACHE_HOME/graphitron` or `~/.cache/graphitron` on Linux, `%LOCALAPPDATA%\graphitron` on Windows);
the store rebuilds on the next run. Or point `-Dgraphitron.store.directory` at a path under the build
directory so `mvn clean` clears it. A checkout running `graphitron:dev` beside `quarkus:dev` can pass
`-Dgraphitron.dev.skipInitial=true` to the dev goal so only one of them captures at start.
