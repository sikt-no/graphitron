---
id: R706
title: "A build that meets a held fact store fails fast and says so"
status: In Progress
bucket: bug
priority: 3
theme: dev-loop
depends-on: []
created: 2026-08-18
last-updated: 2026-08-20
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

## Two stalls, and only one of them is bounded

Two independent stalls sit on the path a build takes to the shared store, at two different layers,
and they need two different answers. The unbounded one is at `open` and is almost certainly the
reported one; the bounded one is inside `capture` and is a real defect of its own. They are described
in that order below.

## The unbounded stall: H2's file-lock liveness probe

Nothing in this one is graphitron's code, and no SQL-level setting reaches it. `AUTO_SERVER=TRUE`
makes the first process write a `store.lock.db` naming the TCP port its embedded server listens on:

```
#FileLock
hostName=localhost
id=1a0166fa01bf431efbafa2105e80816a5aa15fdf8fd
method=file
server=localhost\:36115
```

A later process opening the same file goes `GraphitronModelStore.connect` → `JdbcDataSource` →
`SessionRemote.connectEmbeddedOrServer` → `Engine.createSession` → `Database.<init>` →
`FileLock.lock` → `FileLock.checkServer`, and `checkServer` decides whether the recorded holder is
still alive by talking to that port: open a socket, write a short handshake, read an int back. A
holder that answers throws `DATABASE_ALREADY_OPEN_1` carrying the server key, which is the signal
that turns the opener into a TCP client of the holder. That is the whole of mixed mode, and when the
holder answers it costs 243 ms end to end.

The socket comes from `NetUtils.createSocket(String, int, boolean)`, which passes a read timeout of
zero. So the read has **no timeout at all**, and an opener whose socket connects to something that
never answers blocks in `DataInputStream.readInt` forever. Two ways to be that something, both
reproduced against h2 2.4.240 with the store's own URL, each confirmed by a stack in
`FileLock.checkServer`:

1. **The holder is alive but not answering.** `SIGSTOP` on the holder (a developer's Ctrl-Z on
   `mvn graphitron:dev`, a laptop suspended with the dev loop running, a debugger stopped at a
   breakpoint) leaves the listening socket open in the kernel, so the connect succeeds and the
   handshake is never read. The opener sat in `readInt` from the moment the holder was suspended and
   returned only when the holder was killed 26 s later, with `Connection is broken`. Nothing about
   the wait was bounded; the 26 s is when the experiment ended.
2. **The holder is gone and its port has been taken over.** A `kill -9`'d dev session leaves
   `store.lock.db` behind naming an ephemeral port, and nothing ever removes it. Any unrelated
   process that later listens on that port makes every subsequent opener hang: measured still blocked
   at 90 s, with the squatting listener confirming it had accepted the connection and said nothing.
   This one is permanent until the squatter exits or somebody deletes the cache directory by hand.

Three things make this worse than a long wait:

- **`openAt`'s fallback cannot fire.** Its `catch (RuntimeException)` promises that any failure to
  open costs warmth and never correctness. A block is not a failure, so the promise is defeated by
  the one outcome it was written to absorb.
- **It needs no capture in flight.** This is at `open`, so an *idle* `graphitron:dev` session is
  enough. That matches the report ("cannot run while a dev session runs") far better than a race
  against the few seconds around a save.
- **It is held under a JVM-wide monitor.** The stack shows `Engine.openSession` holding
  `Engine$DatabaseHolder`, so in the blocked JVM every other thread opening the same store queues
  behind it too.

The mechanism exists only because of `AUTO_SERVER`. `checkServer` returns immediately when the lock
file carries no `server=` entry, and without `AUTO_SERVER` h2 2.4.240 writes no `store.lock.db` at
all: it takes the MVStore's own OS-level file lock instead. Measured on the same holder, same file:

| Opener meets | With `AUTO_SERVER` | Without it |
|---|---|---|
| a live holder | attaches in 243 ms, shares the rows | fails in 101 ms, `90020 Database may be already in use` |
| a suspended holder | blocks in `readInt`, unbounded | fails in 92 ms, same `90020`; an OS lock outlives scheduling |
| a `kill -9`'d holder whose port was taken over | blocks in `readInt`, unbounded, permanently | opens in 211 ms; the OS drops the lock with the process |

Both `90020` arms land in the existing `catch (RuntimeException)` and demote to memory, which is the
behaviour this item is asking for, already written and currently unreachable.

## The bounded stall: 60 seconds on the anchor row

`FactCapture.capture` is one transaction end to end, and it opens with the `store_graph` anchor-row
upsert precisely so that a concurrent writer of the same graph *serializes on that row* instead of
interleaving its deletes with the other's inserts. Same workspace plus same module means the same
graph name, so it is the same row.

`GraphitronModelStore.fileUrl` then raises H2's lock timeout from its one-second default to
`LOCK_TIMEOUT=60000`, on the stated reasoning that "a writer that waits its turn beats one that
falls back cold". Two mechanisms that are each individually right compose into the stall:

1. The build's capture blocks on the anchor row for a silent 60 s, then H2 throws
   `JdbcSQLTimeoutException` ("Timeout trying to lock table"). Measured at 60 857 ms in one process
   and 60 023 ms across two, in both cases against an anchor row held uncommitted on purpose rather
   than against a live dev session: what the numbers establish is the cost of the wait, not how often
   a real session provokes one.
2. `FactCapture.captureWithRetry` catches the resulting `DataAccessException` and retries the same
   capture against the same store, which blocks for another silent 60 s. That retry exists to tell a
   transient concurrency casualty apart from a deterministic capture bug, which is a good reason to
   retry a *failed* capture and a bad reason to re-enter a wait that just expired.
3. Only then does the run demote to an in-memory capture and log. Total: about two minutes of
   silence followed by a message that arrives after the user has already concluded the build is
   wedged.

Nothing in *this* wait is unbounded, so on its own it would make "does not time out" a description of
how the stall reads rather than of the code. Two minutes with zero output is indistinguishable from a
hang either way, which is why it stays in scope alongside the genuine hang above.

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

Which leaves a gap between this account and the report, and the gap is what sent the search to the
`open` layer. If the anchor-row hold is one capture long, the reporter should have waited seconds
rather than the two minutes they describe. The file-lock probe explains the report without that
strain: it needs no capture in flight, it is unbounded, and "does not time out" describes it
literally.

Telling the two apart in a live incident is cheap, and worth saying out loud because the two fixes
below are independent: a `jstack` of the stalled Maven process names its layer directly. A stack in
`FileLock.checkServer` is the probe; a stack in a jOOQ `execute` under `FactCapture.capture` is the
anchor row. `cat store.lock.db` in the cache directory settles the rest, its `server=` naming the
port the opener is trying to reach.

## The hard-killed holder leaves two things behind

`graphitron:dev` is normally ended with Ctrl-C. A holder killed harder leaves both of its files, and
they poison the store in two different ways. One belongs here and one does not.

The **lock file** is this item's business, because it is half of the unbounded stall above:
`store.lock.db` survives with a `server=` naming an ephemeral port that nothing owns any more, and
nothing ever removes it. Until something else takes that port the cost is small, a 4114 ms open where
`checkServer`'s connect is refused; once something does, every opener in that workspace hangs.

The **database file** is the separate item. `store.mv.db` survives with no `store_stamp` row in it,
so `openAt` finds a file at the stamped path whose stamp does not match, correctly refuses to repair
or delete a file it did not write, and falls back to memory. It will do so on *every* subsequent run,
so the workspace loses warm start permanently and silently until somebody deletes the cache directory
by hand. Reproduced with `kill -9` on a holder: the next opener reports `Table "STORE_GRAPH" not
found (this database is empty)`. Dropping `AUTO_SERVER` would remove the lock-file half of this
outright, since h2 2.4.240 then writes no lock file at all, and would leave the stamp half untouched.

## What "fail fast" has to mean here

Not "fail the build". The store is a cache with no state of record, and the rule that cache trouble
costs warmth and never correctness is load-bearing across `GraphitronModelStore` and `FactCapture`;
a contended cache earning a build failure would invert it. So "fail fast" reads as *stop waiting
fast, say why, and carry on cold*. The observable the user asked for is that `mvn test` stops
looking wedged, and that is delivered by a short wait plus a sentence, not by an exit code.

## The design, first half: the open cannot block

The open has to be bounded by us, because H2 offers nothing to bound it with. `h2.socketConnectTimeout`
caps the connect and not the read, and the `FileLock` probe's read timeout is a hard-coded zero with no
property behind it. Two ways to get a bound, and they are not exclusive.

**Drop `AUTO_SERVER=TRUE`.** One line, and the mechanism is gone rather than bounded: no lock file, no
recorded port, no socket, no probe. An opener that meets a held file learns so from the OS in about
100 ms and takes the `catch (RuntimeException)` path that already does the right thing. The table above
is the whole argument, and the reported case ends at 100 ms and a sentence.

What it costs is the sharing the flag was bought for. Two processes can no longer hold the store at
once, so the second goes cold in memory and contributes nothing back. Which cases that touches:

1. **Sequential module builds**, the ordinary `mvn test`: unaffected. Each module opens, captures,
   closes, and the next finds the previous module's rows. This is where most of the warmth is.
2. **A parallel reactor build** (`-T 1C`, which CI uses): the non-first modules to open concurrently
   go cold. This is the real loss, and it is a loss of *sharing within one build*, not of warm start
   across builds: the next build still opens onto whatever the winner committed.
3. **A build beside a dev session**: goes cold, in 100 ms, with a warning. Today it either shares in
   243 ms or hangs, and the fix for the hang is what this whole item is.

What it does *not* cost is anything inside one JVM, which is where the flag is easy to assume
load-bearing and is not. A second connection onto a store this process already holds does not go
through the file lock at all: H2 gives one process one `Database` per file and hands further
connections off it. Measured without `AUTO_SERVER`, all in one JVM with the writer's handle open: a
`reader()`-shaped second connection opened in 0 ms and read the writer's committed rows under
`SNAPSHOT` isolation; a `DevMojo`-shaped transient third connection (the per-pass `openAt`) opened,
wrote, and closed, with the writer seeing its rows and staying usable afterwards. So the language
server's reader, the MCP reader and the dev session's per-pass captures are all untouched, and
`reader()`'s javadoc needs its "in mixed mode" clause corrected rather than its claim withdrawn.

**Bound the open with a watchdog.** Keep `AUTO_SERVER` and its sharing, and run `connect` on a thread
with a deadline: on expiry, abandon it and fall back to memory. It bounds *every* way an open can
block rather than the one found here, which is the more honest shape for a promise phrased as "any
failure at all falls back". It costs a daemon thread parked in a socket read that we cannot interrupt,
since we do not own the socket. Harmless in a build JVM that exits, and a dev session is the holder
rather than the opener, so it never takes this path.

Recommendation: drop `AUTO_SERVER`, and treat the watchdog as the follow-up if the parallel-reactor
warmth turns out to matter. The one-line change removes a permanent wedge and a hang; the watchdog
keeps a sharing case that only CI exercises. Doing the watchdog *first* keeps the hang mechanism alive
behind a timer, which is a worse place to stand.

## The design, second half: a short budget on the one row where waiting buys nothing

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

One message covers both halves, and that is deliberate: from where the user stands, a store held by
another process and a store whose anchor row is held by another process are the same event with the
same consequence. The distinction matters to whoever fixes it, not to whoever reads it, and the
`jstack` recipe above is how a maintainer recovers it when it matters.

One doc change follows it: `docs/manual/how-to/dev-loop.adoc` gains a troubleshooting entry for the
symptom as the user meets it, a build that pauses briefly and then warns while `graphitron:dev` runs,
alongside the existing port-conflict entry. `docs/manual/reference/mojo-configuration.adoc` needs
nothing: its `storeDirectory` row promises "one store per project checkout, shared by that
checkout's modules", and that stays true.

## Implementation

- `GraphitronModelStore.fileUrl`: `AUTO_SERVER=TRUE` comes off. Its javadoc's account of mixed mode
  is replaced by why the flag is refused, which is the finding above and not a preference: a probe
  that cannot time out is not a cost a cache may impose. The class javadoc's mixed-mode paragraph and
  `openAt`'s ("the first process holds the file, later ones attach transparently") go with it, and
  `reader()`'s claim that "a file-backed one is in mixed mode and hands out connections to the same
  process freely" needs restating, since same-process connections do not depend on the flag.
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
- The open cannot block, which needs the forked-process machinery the class already has: a holder
  process owns the store, and an `openAt` in this JVM returns within a bound and reports a cold store,
  rather than attaching. This is the test that would fail today, and it is the one that keeps
  `AUTO_SERVER` from coming back for a plausible-sounding reason later.
- A second connection while this JVM holds the store still opens and reads the holder's rows, which is
  `reader()`'s and the per-pass capture's contract and the one thing dropping the flag could plausibly
  have broken. Measured above; asserting it is what stops that measurement rotting.
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

The file-lock finding partly rehabilitates it, and it is worth saying which part. A separate store per
role does keep a build and a dev session off each other's file, which removes the *reported* collision
at its source rather than bounding it. What it does not do is remove the mechanism: two builds of a
parallel reactor still open each other's file, and a stale lock file whose port has been taken over
still wedges whoever meets it. So the role split is an optimisation over a bounded open, never a
substitute for one, and on that reading its cost lands the same way as before.

Nothing here forecloses it. The row-scoped budget, the bounded open and the role split are mutually
independent, so if the warmth loss turns out to bite, the role split arrives later as its own item and
these changes stay correct underneath it.

## Out of scope

- The hard-killed-holder stamp demotion above, the `store.mv.db` half, which needs its own item. The
  lock-file half is in scope, being half of the unbounded stall.
- Reporting H2's own lock state to the user beyond the one warning: naming the holder's pid, reading
  `store.lock.db` in the message, offering to clear a stale lock. All tempting once the mechanism is
  understood, and all of it is a second item on top of a store that no longer hangs.
- `DevMojo`'s four writers on one connection. A documented rule broken, measured not to strand a
  lock, and not this item's cause; its own item if it earns one.
- Eviction, freshness checking, and anything else about the store's lifecycle.
- Any configuration surface for the lock budget.
- Making a contended store fail a build, which the cache rule forbids.

