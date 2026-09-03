---
id: R919
title: "Two processes in one checkout both keep a warm store"
status: Backlog
bucket: dx
priority: 4
theme: dev-loop
depends-on: []
created: 2026-09-03
last-updated: 2026-09-03
---

# Two processes in one checkout both keep a warm store

## Goal

A developer running `graphitron:dev` beside `quarkus:dev` in one checkout gets a warm start in both,
rather than whichever process loses the race booting cold on every round. A *warm start* is a run
that begins from the previous run's captured facts instead of re-reading the schema and classpath
from scratch. When this lands, the two processes stop taking warm start away from each other.

## Provenance

Split out of R914, which bounds the store's growth. This was never part of the reported failure in
[issue 544](https://github.com/sikt-no/graphitron/issues/544); it is a warm-start convenience that
surfaced while the store's locking was being read, and it is filed so the observation is not lost.

## What is known

A file-backed store is held by one process at a time by design. `GraphitronModelStore.fileUrl`
records why: `AUTO_SERVER=TRUE` would share one file across processes, but in mixed mode an opener
that meets a stale lock file or a suspended holder blocks forever with no timeout reaching it, which
is not a cost a cache may impose on a build. Without the flag H2 takes the MVStore's own operating
system lock and reports a held file in well under a second, straight into the in-memory fallback.

So the second process is already refused quickly, and the question is not the lock budget. It is
whether the loser can keep a warm store of its own, on its own path, rather than falling back to a
private in-memory one that dies with the run. The workaround today is
`-Dgraphitron.dev.skipInitial=true` on the dev goal, so only one of the two captures at start.

The lock budget still matters separately, for the in-JVM case where a concurrent or leaked connection
holds a table; `FILE_LOCK_MILLIS` is 60 s and `FactCapture` narrows it where waiting buys nothing.

## Open questions

* Whether a second warm store per checkout is worth the disk it costs, given that R918 exists to
  bound exactly that.
* Whether the loser's store can be discovered and reused on the next round, or whether it is one
  private store per process for as long as both run.
