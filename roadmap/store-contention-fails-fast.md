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
   `JdbcSQLTimeoutException` ("Timeout trying to lock table"). Reproduced end to end: 60 857 ms.
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

## The design

Two halves. The second is what fixes the reported symptom; the first is what stops the collision
happening at all.

### The opener declares its role, and the store derives the rest

`GraphitronModelStore.openAt` grows a role: a long-lived interactive session, or a batch build.
The role is what the caller passes; the *path* and the *lock budget* stay derived inside the store
class, for the reason its javadoc already gives about the stamp segment. An opener that computed
its own segment would not fail, it would look in the wrong directory, boot empty, and report a
schema with no facts as a schema with no facts.

- A session opens under a `session` segment beneath the home, a build under a `build` one, each
  still carrying the existing `<ddl-hash>-<version>` stamp inside it. Appending for both roles
  rather than special-casing the build to today's bare path is deliberate: the stamped-path design
  already makes a path change safe (a run that finds nothing boots cold and correct), so the
  one-time cold start is worth not carrying an empty-segment special case forever. The segment is
  appended inside `openAt`, below the `<storeDirectory>` override too, so a consumer who pins a
  home does not thereby re-create the collision.
- A build's lock budget drops from `LOCK_TIMEOUT=60000` to a few seconds; a session keeps the
  generous one, which is now harmless because a build is no longer behind it. The value stays a
  named constant with its rationale, not a configuration knob: nothing yet suggests a consumer
  needs to tune it, and a knob would invite tuning around a diagnosis instead of reading one.

The role has to reach *every* opener a dev session drives, not just the obvious one. `DevMojo`
opens the store twice in two different shapes: `sessionStore` directly, and transiently once per
generator pass, because `runGeneratorPass` goes through `GraphQLRewriteGenerator` into
`FactCapture.runWithDetections(ctx.storeDirectory(), ...)`. Routing only `sessionStore` would leave
every regeneration still writing the shared file, and the contention with it. The role therefore
belongs on `RewriteContext` beside `storeDirectory`, set once at the single construction site in
`AbstractRewriteMojo.buildContext` from a hook `DevMojo` overrides, so it travels with the home
that it qualifies.

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

Docs to follow it:

- `docs/manual/reference/mojo-configuration.adoc`, the `storeDirectory` row: it currently promises
  "one store per project checkout, shared by that checkout's modules", which the role split makes
  false. It becomes one store per checkout per role, with the reason stated in one clause (a dev
  session holds its handle for hours and a build should not queue behind it).
- `docs/manual/how-to/dev-loop.adoc`: a troubleshooting entry for the symptom as the user meets it,
  a build that pauses and then warns while `graphitron:dev` runs, alongside the existing port-
  conflict entry.

## Implementation

- `GraphitronModelStore`: a public role enum (session / build) as a parameter on `openAt`; a
  role-derived segment in the path resolution beside `stampSegment`; a role-derived lock timeout in
  `fileUrl`. The `openAt` javadoc's account of why the segment is not published extends to the role
  segment for the same reason.
- `RewriteContext`: a role component beside `storeDirectory`, defaulting with it on the overload
  that defaults the home to `null`.
- `AbstractRewriteMojo`: a `storeRole()` hook returning the build role, read at the single
  `RewriteContext` construction site; `resolveStoreDirectory`'s javadoc drops its "the store itself
  appends a compatibility-stamped subdirectory" sentence in favour of naming both segments.
- `DevMojo`: overrides `storeRole()`, and passes it at the `sessionStore` open so the long-lived
  handle and the per-pass captures agree by construction.
- `FactCapture.captureWithRetry`: the cause-chain split above, and the `warn` message.

## Tests

Unit tier, in `PersistentStoreTest`, which already owns "how two writers share one file" and
already has both the in-process second-handle and the forked-process machinery these need.

- Two handles in one JVM, one holding an uncommitted write on the `store_graph` anchor row, the
  other capturing the same graph in the build role: the capture gives up inside the build budget
  rather than the session one, and the run completes cold. Two connections in one JVM lock
  identically to two processes (verified against h2 2.4.240 during diagnosis), so this needs no
  fork and stays deterministic.
- The role split as a path property: a session open and a build open under one home land in
  different directories, and neither reports the other's rows as its own.
- The retry split: a lock timeout is attempted once, and a non-timeout `DataAccessException` is
  still attempted twice, so the fix cannot silently retire the retry's original purpose.

The absence of a test asserting the message text is deliberate; the value is that a human reads it,
and a string assertion on prose pins the wording rather than the behaviour.

## Out of scope

- The hard-killed-holder demotion above, which needs its own item.
- Eviction, freshness checking, and anything else about the store's lifecycle.
- Any configuration surface for the lock budget.
- Making a contended store fail a build, which the cache rule forbids.

