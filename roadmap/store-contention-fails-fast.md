---
id: R706
title: "A build that meets a held fact store fails fast and says so"
status: Spec
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

1. The build's capture blocks on the anchor row for a silent 60 s, then H2 throws
   `JdbcSQLTimeoutException` ("Timeout trying to lock table"). Measured at 60 857 ms, against an
   anchor row held uncommitted on purpose rather than against a live dev session: what the number
   establishes is the cost of the wait, not how often a real session provokes one.
2. `FactCapture.captureWithRetry` catches the resulting `DataAccessException` and retries the same
   capture against the same store, which blocks for another silent 60 s. That retry exists to tell a
   transient concurrency casualty apart from a deterministic capture bug, which is a good reason to
   retry a *failed* capture and a bad reason to re-enter a wait that just expired.
3. Only then does the run demote to an in-memory capture and log. Total: about two minutes of
   silence followed by a message that arrives after the user has already concluded the build is
   wedged.

Nothing in the wait is unbounded, so "does not time out" is a description of how it reads rather
than of the code; two minutes with zero output is indistinguishable from a hang, which is the
defect either way.

## What holds the anchor row, and a hypothesis that did not survive

A live `graphitron:dev` session holds the anchor row for one capture transaction, which covers the
whole load: the SDL walk, the classpath census, the flush, and the capture-cadence derivations.
That is seconds on a real project, not a minute, so on this account the collision window is the few
seconds around each save and the 60 s budget is never exhausted.

The obvious way to get a hold long enough to exhaust it was a bug rather than a slow capture, and it
was worth ruling out before designing around the wait. `DevMojo` hands one connection
(`sessionStore.dsl()`) to four writers driven from four threads: `CompileFacts` and the two
diagnostics writers off the schema and classpath watchers, `JavaSourceFacts` off the source
watcher, and the MCP tools' `StoreHandle` off Jetty. Each opens `dsl.transaction(...)`, and
`CompileFacts.writeRound` upserts the same `store_graph` anchor row a capture takes. `StoreReader`'s
javadoc states the rule that arrangement breaks ("the store's `DSLContext` is single-threaded by
construction and a capture holds it for a whole transaction"), and an interleaving that left
`autoCommit` false over uncommitted work would hold the anchor row for as long as the session stayed
idle, which would exhaust any budget.

It does not happen. Driven against h2 2.4.240 and jOOQ 3.20.11, sixty rounds of two to four
concurrent `dsl.transaction(...)` calls on one shared `Connection`, randomized entry delays, hold
times and injected rollbacks, left zero stranded row locks: after every round an outside connection
took all four contended rows within its own 2 s budget, `autoCommit` was back to true, and no
failure surfaced beyond the rollbacks the probe injected itself. So the shared connection is a
documented rule being broken and is not the reported stall's cause. It is not this item's business,
and the account above stands: the hold is one capture long.

Which leaves a gap between the account and the report. If the hold is one capture long, the reporter
should have waited seconds rather than the two minutes they describe, so either their capture is
genuinely that slow (a large project, a cold classpath census, a first run) or something not yet
found holds the row longer. The design below deliberately does not depend on knowing which: it makes
*any* hold cost two seconds and a sentence, which is the same answer for a slow capture and for a
cause still unaccounted for. What it would not survive is the hold being permanent, and that is what
the probe above rules out.

## Deliberately separate: the hard-killed holder poisons the store

Adjacent and worth its own item rather than folding in here. `graphitron:dev` is normally ended
with Ctrl-C. A holder killed before H2 flushes leaves `store.mv.db` present but with no
`store_stamp` row in it. `GraphitronModelStore.openAt` finds a file at the stamped path whose stamp
does not match, correctly refuses to repair or delete a file it did not write, and falls back to
memory. It will do so on *every* subsequent run, so the workspace loses warm start permanently and
silently until somebody deletes the cache directory by hand. Reproduced with `kill -9` on a holder:
the next opener reports `Table "T" not found (this database is empty)`.

## What "fail fast" has to mean here

Not "fail the build". The store is a cache with no state of record, and the rule that cache trouble
costs warmth and never correctness is load-bearing across `GraphitronModelStore` and `FactCapture`;
a contended cache earning a build failure would invert it. So "fail fast" reads as *stop waiting
fast, say why, and carry on cold*. The observable the user asked for is that `mvn test` stops
looking wedged, and that is delivered by a short wait plus a sentence, not by an exit code.

## The design: a short budget on the one row where waiting buys nothing

The split is by *row*, not by role. The generous budget is right for every row a capture takes
except the anchor, and the anchor is the only row the reported collision touches.

Once a capture holds `store_graph`, it is the sole writer of that graph: every SDL row hangs off
that row, so no same-graph writer can contend with it a second time. What a capture can still wait
on after the anchor is the shared `jvm_` and `sql_` families, which two *different* graphs' captures
write concurrently in a parallel reactor build. That is the case `LOCK_TIMEOUT=60000` was raised for,
and there the wait earns its keep: the other module is committing rows this one also needs, within
seconds, and a writer that waits does beat one that falls back cold.

Waiting on the anchor row earns nothing. Blocking there means another process is mid-capture of the
same graph under the same base directory, `ownsGraph` having already refused the other case, so it is
the same module of the same checkout writing the same rows. Waiting buys the right to delete that
capture and rewrite it identically. Falling back costs this run its warm start and leaves the store
holding the other process's rows, which for a `graphitron:dev` session are fresher than this build's
would have been.

### Two statements and a cause check

`FactCapture.capture` already opens its transaction with the anchor upsert, so the change is local
to that method:

```java
dsl.transaction(tx -> {
    DSLContext txDsl = tx.dsl();
    txDsl.execute("SET LOCK_TIMEOUT " + ANCHOR_LOCK_MILLIS);
    writeGraph(txDsl, sources, graph, config);
    txDsl.execute("SET LOCK_TIMEOUT " + GraphitronModelStore.FILE_LOCK_MILLIS);
    ...
```

The generous value stops being a literal inside a URL string and becomes a named constant on
`GraphitronModelStore` that `fileUrl` interpolates and this restore reads, so the budget a capture
returns to and the budget the connection opened with cannot drift apart.

Four things were measured against h2 2.4.240 with the store's own URL, driven through jOOQ 3.20.11's
`dsl.transaction`, because the design rests on all four:

1. A contended anchor upsert under `SET LOCK_TIMEOUT 2000` gives up in 2054 ms.
2. Raising the budget back inside the same transaction takes effect: a later statement behind a
   5 s holder waited 5002 ms rather than giving up at 2 s. `SET LOCK_TIMEOUT` is a session command
   and is not rolled back with the transaction, which is why the value is set at the top of every
   capture instead of once at open.
3. The failure arrives as `DataAccessException` wrapping `org.h2.jdbc.JdbcSQLTimeoutException` (a
   `java.sql.SQLTimeoutException`, error code 50200, SQL state `HYT00`) wrapping an
   `MVStoreException`, so the cause-chain walk below has something stable to key on.
4. `autoCommit` is back to true on the connection after the failed transaction, so the fallback
   inherits a clean handle rather than a half-configured one.

Nothing before the anchor upsert can block. `ownsGraph`'s `SELECT` runs outside the transaction and
H2's MVStore serves it the committed row: 7 ms against an anchor row held uncommitted by another
connection.

### The anchor budget is two seconds

Long enough to absorb a commit already in flight, since a capture that has reached its own commit
releases the row in milliseconds and there is no reason to lose warmth to a race that close. Short
enough that a human reads the pause as the build starting rather than as the build stopping, which
is the whole observable this item owes.

Both values stay named constants carrying their rationale, not configuration. Nothing yet suggests a
consumer needs to tune either, and a knob would invite tuning around a diagnosis instead of reading
one.

### Contention reports itself, and is not waited for twice

In `FactCapture.captureWithRetry`:

- A lock timeout is not retried. Today every `DataAccessException` gets one retry against the same
  store, which doubles a wait that just expired. The retry exists to tell a transient concurrency
  casualty apart from a deterministic capture bug and must survive for that; the split is by cause,
  walking the chain for `java.sql.SQLTimeoutException` (H2 raises `JdbcSQLTimeoutException`, error
  code 50200, SQL state `HYT00`). A deadlock is a different animal and keeps its retry:
  `SQLTransactionRollbackException` is exactly the transient casualty the retry was written for.
- The demotion logs at `warn`, not `debug`. Maven binds slf4j at INFO, so `warn` reaches the
  console where the current `debug` line does not. That the message arrives at all is most of the
  fix; that it arrives seconds in rather than two minutes in is the rest.

## User-visible surface (first-client check)

The whole deliverable is a message, so the message is the design. Draft, for a build that finds the
store held:

```
[WARNING] graphitron: the shared fact store for this workspace is held by another graphitron
[WARNING] process (a running `mvn graphitron:dev` is the usual one). Capturing in memory for this
[WARNING] run instead of waiting for it. This costs warm-start speed and nothing else; the
[WARNING] generated output is identical.
```

It names the likely holder, because "held by another process" without that guess sends a user
looking for a stuck build rather than at the editor session they left running. It says what the run
did instead, because a warning with no consequence attached reads as damage. It says the output is
unaffected, because the store is a cache and a user who does not know that will assume a warning
about a database means their schema did not generate.

One doc change follows it: `docs/manual/how-to/dev-loop.adoc` gains a troubleshooting entry for the
symptom as the user meets it, a build that pauses briefly and then warns while `graphitron:dev` runs,
alongside the existing port-conflict entry. `docs/manual/reference/mojo-configuration.adoc` needs
nothing: its `storeDirectory` row promises "one store per project checkout, shared by that
checkout's modules", and that stays true.

## Implementation

- `GraphitronModelStore`: the lock budget in `fileUrl` becomes a named public constant so
  `FactCapture` can restore to the same number. No signature change, no path change, nothing else.
- `FactCapture.capture`: the two `SET LOCK_TIMEOUT` statements bracketing `writeGraph`, and the
  anchor budget as a constant beside them. The transaction's javadoc already explains why the anchor
  upsert leads; it gains the sentence saying that is also the only row worth failing fast on.
- `FactCapture.captureWithRetry`: the cause-chain split above, and the `warn` message.

## Tests

Unit tier, in `PersistentStoreTest`, which already owns "how two writers share one file" and already
has the in-process second-handle machinery these need. Two connections in one JVM lock identically to
two processes (verified against h2 2.4.240 during diagnosis), so none of these needs a fork and all
stay deterministic.

- Two handles in one JVM, one holding an uncommitted write on the `store_graph` anchor row, the other
  capturing the same graph: the capture gives up inside the anchor budget, well short of the generous
  one, and the run completes cold with every fact present.
- The generous budget survives the anchor. A capture whose anchor is uncontended but whose shared
  `jvm_` rows are held uncommitted by another writer waits past the anchor budget rather than giving
  up at it. Without this, a later edit could lower one number and silently lower both, retiring the
  parallel-reactor case the generous budget exists for.
- The retry split: a lock timeout is attempted once, and a non-timeout `DataAccessException` is still
  attempted twice, so the fix cannot silently retire the retry's original purpose.

The absence of a test asserting the message text is deliberate; the value is that a human reads it,
and a string assertion on prose pins the wording rather than the behaviour.

## Considered and rejected: one store per role

The earlier draft of this item split the store by opener role, a `session` path segment for
`graphitron:dev` and a `build` one for the mojos, each with its own lock budget. It buys more than
this design does: a build never queues behind a dev session at all, and keeps a warm store of its own
while one runs, where the design above goes cold for the seconds around each save.

It is rejected on cost. The role has to reach every opener a dev session drives, not just the obvious
one, because `runGeneratorPass` reaches `FactCapture.runWithDetections(ctx.storeDirectory(), ...)` on
every regeneration; routing only `DevMojo.sessionStore` would leave each pass writing the shared file.
So the role travels on `RewriteContext` beside `storeDirectory`, through a hook on
`AbstractRewriteMojo` that `DevMojo` overrides, into a new parameter on `openAt`: four files, a public
enum, a changed store-opening signature, a one-time cold start for every consumer as the path moves,
and a documentation promise to rewrite. All of that to convert a rare few-second warmth loss into no
warmth loss, in a cache.

Nothing here forecloses it. The row-scoped budget and the role split are independent, so if the
warmth loss turns out to bite, the role split arrives later as its own item and this change stays
correct underneath it.

## Out of scope

- The hard-killed-holder demotion above, which needs its own item.
- `DevMojo`'s four writers on one connection. A documented rule broken, measured not to strand a
  lock, and not this item's cause; its own item if it earns one.
- Eviction, freshness checking, and anything else about the store's lifecycle.
- Any configuration surface for the lock budget.
- Making a contended store fail a build, which the cache rule forbids.

