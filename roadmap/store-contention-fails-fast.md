---
id: R706
title: "A build that meets a held fact store fails fast and says so"
status: Backlog
bucket: bug
priority: 3
theme: dev-loop
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# A build that meets a held fact store fails fast and says so

Running `mvn test` in a consumer project while `mvn graphitron:dev` is running in the same
workspace stalls for about two minutes with no output, and then continues as if nothing happened.
The user reads the stall as a hang, because nothing on the console distinguishes it from one. The
two commands are queueing on the same file, and the queueing is by design; what is missing is a
short wait and a sentence explaining it.

## What the store is, and why the two commands meet in it

The *fact store* is graphitron's warm-start cache: an H2 database holding the facts a previous run
captured about a graph, so the next run starts from those rows instead of an empty schema. It is a
cache and never state of record, so a run that cannot use it boots cold and stays correct.

`AbstractRewriteMojo.resolveStoreDirectory` puts one store per user per *workspace* (the outermost
Maven aggregator directory), not per module and not per goal. That is deliberate: parallel module
builds of one workspace share one file, so the second module to build finds the first one's rows.
The consequence is that every graphitron process in a checkout opens the same file, and
`graphitron:dev` holds its handle open for the whole session while `mvn test` opens the same file
through `graphitron:generate` at `generate-sources`.

Sharing the file is not itself the problem. H2 opens it in mixed mode (`AUTO_SERVER=TRUE`), so the
second process attaches through the first rather than being refused. Measured against
h2 2.4.240 with the store's own URL: an attach onto an idle holder takes about 750 ms and the
write that follows about 10 ms.

## Where the stall comes from

`FactCapture.capture` is one transaction end to end, and it opens with the `store_graph` anchor-row
upsert precisely so that a concurrent writer of the same graph *serializes on that row* instead of
interleaving its deletes with the other's inserts. Same workspace plus same module means the same
graph name, so it is the same row.

`GraphitronModelStore.fileUrl` then raises H2's lock timeout from its one-second default to
`LOCK_TIMEOUT=60000`, on the stated reasoning that "a writer that waits its turn beats one that
falls back cold". Two mechanisms that are each individually right compose into the stall:

. The build's capture blocks on the anchor row for a silent 60 s, then H2 throws
  `JdbcSQLTimeoutException` ("Timeout trying to lock table"). Reproduced end to end: 60 857 ms.
. `FactCapture.captureWithRetry` catches the resulting `DataAccessException` and retries the same
  capture against the same store, which blocks for another silent 60 s. That retry exists to tell a
  transient concurrency casualty apart from a deterministic capture bug, which is a good reason to
  retry a *failed* capture and a bad reason to re-enter a wait that just expired.
. Only then does the run demote to an in-memory capture and log. Total: about two minutes of
  silence followed by a message that arrives after the user has already concluded the build is
  wedged.

Nothing in the wait is unbounded, so "does not time out" is a description of how it reads rather
than of the code; two minutes with zero output is indistinguishable from a hang, which is the
defect either way.

## Deliberately separate: the hard-killed holder poisons the store

Adjacent and worth its own item rather than folding in here. `graphitron:dev` is normally ended
with Ctrl-C. A holder killed before H2 flushes leaves `store.mv.db` present but with no
`store_stamp` row in it. `GraphitronModelStore.openAt` finds a file at the stamped path whose stamp
does not match, correctly refuses to repair or delete a file it did not write, and falls back to
memory. It will do so on *every* subsequent run, so the workspace loses warm start permanently and
silently until somebody deletes the cache directory by hand. Reproduced with `kill -9` on a holder:
the next opener reports `Table "T" not found (this database is empty)`.

## Shape of the fix

The user's framing was "`mvn test` and `graphitron:dev` should not share a database file, and a
locked database should fail fast rather than hang". The second half is unambiguous. The first half
needs a choice, because sharing the file is what buys parallel module builds their warmth, and the
axis that actually separates the two commands is not the goal name:

- *Fail fast, keep sharing.* Make the lock budget depend on what the opener is: a long-lived
  session can afford to wait its turn, a batch build cannot. A build that finds the store held
  waits a couple of seconds, says which process holds it and that this run is capturing cold, and
  proceeds. Cheapest, and it keeps the warmth story intact for the parallel-reactor case the
  budget was raised for.
- *Give the session its own file.* A dev session opens a session-scoped segment, so it never
  contends with a build at all. Closest to what was asked, and it costs the dev session the rows
  builds captured and the builds the rows the session captured, which is a real loss for the
  goal whose whole point is a warm editor.
- *Both.* Separate segments, and a short lock budget as the backstop for the remaining
  same-role contention (two module builds).

Whichever arm wins, the reporting half is not optional: entering the wait must print why, and
demoting to a cold capture must say so at a level the Maven console shows.

## Out of scope

- The hard-killed-holder demotion above, which needs its own item.
- Eviction, freshness checking, and anything else about the store's lifecycle.
- The retry's other purpose (catching a deterministic warm-capture bug), which must survive.

