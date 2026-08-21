---
id: R796
title: "The diagnostics drain never runs on the thread that triggered it"
status: In Review
bucket: bug
priority: 2
theme: lsp
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# The diagnostics drain never runs on the thread that triggered it

`Workspace.enqueueAndNotify` runs the recalculation listener inline, on whatever thread performed the
mutation that queued the work, and that listener is the whole diagnostics drain: walk every queued
file, read the store, publish per file. Its own javadoc says so, "the listener, which drains the queue
and computes diagnostics inline on this thread". Two mutators reach it, and both threads are the wrong
place for a read bounded only by the 30 s session budget.

`didOpen` is an LSP *notification*, so lsp4j invokes it on the single thread that reads the inbound
message stream. A drain there does not merely delay diagnostics: it stops the server reading its own
input. Every message queued behind it waits, and the request handlers that are already
`CompletableFuture.supplyAsync` do not help, because a request cannot be dispatched before the
notification ahead of it returns. `$/cancelRequest` waits with the rest, so the client's own escape
hatch is unreachable exactly when it is needed. An editor whose LSP client issues any request
synchronously therefore blocks its own UI for as long as our drain runs, which is what a developer
reports as the editor freezing rather than as diagnostics being late. That report has been made
against a real session, with the drain holding for the full 30 s, and
`roadmap/lsp-surface-latency-budgets.md` has since reproduced it in a harness: the real request methods
over the sakila example's schema put the drain, via `didOpen`, at 31310 ms. Every surface it measured
beside that one answered in a median of 4 to 20 ms, which is the asymmetry this item is about. Those
surfaces are fast and they are queued behind the one that is not.

`markAllForRecalculation`, called from `DevMojo` after each build, occupies the watcher thread for the
same duration. Less visible, and still the thread the dev loop needs for the next swap.

## Why asynchrony is sufficient here, and what it does not fix

The expensive part of a drain holds no lock and no shared reader. The walk takes the workspace lock
per file and returns snapshots; the store read happens after the walk, outside the lock, on the
*session-wide* reader, which exists separately from the interactive one precisely so a drain and a
keystroke never queue behind each other on one connection. So moving the drain off the triggering
thread leaves hover, completion and definition answering at their own 3 s budget while a drain is in
flight. Nothing else needs re-plumbing for that to hold.

What it does not fix is the drain being slow. Diagnostics still arrive late, or not at all when the
read overruns; the fix for that is the relation-level work in
`roadmap/diagnostics-drain-overruns-its-session-budget.md`. These two are independent and neither
waits on the other: this item removes a structural hazard that would outlive any single slow query,
because a drain on a large workspace can always grow past a keystroke's patience.

## What changes

**One executor, owned by the document service, injected.** `GraphitronTextDocumentService` takes an
`Executor` alongside the workspace, and `publishDiagnosticsForRecalculate` submits to it rather than
running. Production passes a single-thread daemon executor, minted with a named thread so a stack
dump of a stuck session says which thread the drain is on.

Single-threaded rather than pooled, and that is a correctness point rather than a frugality one. Two
drains in flight would each hold a read transaction on one connection, so the second would serialize
inside the reader anyway while holding a walk's worth of snapshots, and their publications could
interleave per file so the client ends on the older of two answers. One thread makes the drain
sequential by construction, which is what it already was.

**One pending drain, not a queue of them.** Every build calls `markAllForRecalculation`, so submits
arrive faster than drains complete under exactly the conditions that make a drain slow. A single
"another drain is wanted" flag, set on submit and cleared when a drain starts, collapses N submits
during one drain into one follow-up. This is safe because the queue is already the state: a drain
takes whatever `drainRecalculate` hands it, and a drain that finds nothing queued is a no-op, which
is the property the existing javadoc already leans on for the interleaving race.

**A file closed mid-drain is not published for.** This is the one hazard asynchrony genuinely
introduces. Today `didClose` cannot interleave with a drain, so the drain's walk is proof the file was
open; asynchronously, a close can land between the walk and the publish, and `didClose` publishes an
empty list to clear the client's squiggles. Publishing the drain's stale list after that clear would
restore diagnostics for a buffer the developer has closed. The publish loop therefore skips any URI
the workspace no longer holds a view for.

**Shutdown, on the seam another item is already building.** The executor is shut down when the
connection ends, so `graphitron:dev` does not keep a live thread or a live drain past a detach. Daemon
threads make JVM exit correct regardless; the explicit shutdown is what stops a drain in flight from
publishing into a closing client.

Where that shutdown goes is not this item's call to make alone.
`roadmap/lsp-teardown-stream-closed-write-noise.md` is at Spec on the same path, and its second
deliverable establishes exactly the seam this needs: `exit()` is a client-driven notification a
disconnecting editor may never send, so the `finally` in `DevServer.serve` is the only place
guaranteed to run, and the recalculate listener is compare-and-cleared there rather than
unconditionally, because a reconnect can install its listener before the old connection's teardown
runs. An executor whose lifetime matches that listener's belongs in the same place, cleared under the
same compare. So: if that item lands first, this one hangs its shutdown on the seam it introduces; if
this one lands first, it puts the shutdown where that item's reasoning says the clear must go, so the
two do not grow two teardown hooks with different rules. Neither blocks the other, and the reviewer of
whichever comes second should check that one place owns per-connection teardown.

What that item's failure actually looks like matters here, and its round 1 review has just corrected
it: `RemoteEndpoint.notify` in lsp4j 0.24.0 catches its own write failure, consults
`indicatesStreamClosed`, and logs at INFO rather than propagating, so a stale listener's publish never
throws into the thread that fired the recalculation. Which means this item's interaction with it is
narrower than an exception crossing threads. What moves is where that log record originates, from a
Maven thread today to the drain thread after this lands, and who wastes the work: a drain submitted for
a connection that is gone runs in full and publishes into nothing.

Neither changes that item's deliverables and neither is fixed by this one. An executor does not make a
dead listener right; it relocates the symptom. The compare-and-clear is the fix, and this item's
executor shutdown belongs beside it for the same reason.

## What does not change

* The drain's shape: one walk, one read transaction over the whole batch, one statement per graph,
  per-file publication. Nothing here splits the read, and nothing here touches the
  `StoreAnswer.OutOfBudget` posture of publishing nothing at all.
* Staleness. A drain that started before an edit already publishes for a version that has moved, and
  the following drain already corrects it. Asynchrony widens that window; it does not create it, and
  the correction is the same.
* The interactive surfaces. They are `supplyAsync` already and read through their own reader.

## Verification

The existing tests are the interesting part of the plan, because they assert the publish
*synchronously* right after `workspace.didOpen(...)` returns: `StoreOutOfBudgetTest`,
`BuildTriggerPublishesDiagnosticsTest` and the diagnostics tier all rely on the inline drain as a
happens-before. Injecting the executor is what keeps them honest without a rewrite: they pass a
same-thread executor and their assertions stand exactly as written, testing what they were written to
test.

Three new cases carry the async claim itself, none of them asserting a duration:

* A drain blocked on a latch inside the store read does not prevent a second notification handler from
  returning. The assertion is ordering, not time: the handler returns while the latch is still held,
  then releasing it produces the publish.
* N enqueues during one blocked drain produce at most one follow-up drain, which pins the collapsing
  flag rather than a queue depth.
* A file closed while a drain is in flight receives the `didClose` clear and no later publication.


## Reviewer findings

### Round 1: In Review -> Ready, rework requested (2026-08-21, session_01ArRUrte6WnVy19HnpRyvLM)

The drain does leave both triggering threads, the collapse flag is correct under the interleavings I
worked through, and the three new cases in `DiagnosticsDrainThreadingTest` are the ordering-not-duration
shape the plan asked for. Both production entry points pass a real executor, so no production path is
left on the same-thread default, and the existing synchronous harnesses keep their assertions verbatim.
Two findings stand against question 1, "is it the change the spec approved", and the first is why this
goes back.

**1. The per-connection shutdown turns a stale listener from silent into throwing, on the dev goal's
own threads.** `DevServer.serve`'s `finally` calls `drainExecutor.shutdownNow()`, and nothing clears
`Workspace`'s recalculate-listener slot, so between an editor detaching and the next connection's
`setClient` the slot still holds the dead connection's service with its non-null client. A submit in
that window is rejected.

Reproduced, not reasoned: a probe that mints a service on a single-thread executor, opens a file,
shuts the executor down, then calls `markAllForRecalculation` gets
`RejectedExecutionException: Task ... rejected from ThreadPoolExecutor[Shutting down, ...]`. That
exception leaves `Workspace.enqueueAndNotify` and lands on the caller's thread, which is a Maven
thread.

`DevMojo` makes it worse rather than absorbing it, on both paths that call the mutator inside a
`catch (RuntimeException)` that then calls it again:

* `regenerate` logs `catalog refresh after save failed; keeping previous: Task ... rejected` for a
  refresh that in fact succeeded, then throws a second time out of the inner catch, past an outer
  catch that only handles `MojoExecutionException`, into a `DebounceExecutor` task whose
  `ScheduledFuture` nobody inspects. Silently swallowed, with a misleading warning as the only trace.
* `rebuildCatalog` calls the mutator *before* its catalog-refreshed log line and its recompile, so a
  classpath change while no editor is attached loses the recompile and reports a rebuild failure that
  did not happen.

This is a new failure mode, and the comparison is the point: inline, this window was quiet.
`RemoteEndpoint.notify` catches its own write failure and logs at INFO, which the teardown item's
round 1 review established and this plan quotes. The plan's coordination paragraph concluded "neither
blocks the other"; that is the half this review retracts. Shutting the executor down is safe only once
the listener slot cannot still reach it, or once the submit tolerates rejection. Either satisfies the
finding: make `publishDiagnosticsForRecalculate` treat a rejected submit the way the inline path
treated a dead client (reset `drainWanted`, say nothing louder than debug), or sequence the shutdown
behind the compare-and-clear the teardown item owns and say so in the plan. A mutator must not be able
to throw into the dev goal, because before this change it could not.

Second-order, and fixed by the same change: `drainWanted` stays `true` after a rejected submit, so a
service that survives one rejection never submits again.

**2. A contributor-facing doc now teaches the opposite of what the code does.**
`docs/architecture/how-to/dev-loop-internals.adoc`, "Three things to read off a trace", still says a
`workspace.notify` much larger than its `workspace.mutate` means "the mutation's real cost is the
diagnostic recalculation it triggers", and sends the reader to `files=` on `publishDiagnostics.drain`
under the same thread. After this change `notify` is a flag-and-submit and the drain span is on
`graphitron-lsp-diagnostics-drain`, so a large `notify` means the listener has stopped being a submit,
which is the inverse reading. The delivery updated exactly that sentence in
`Workspace.enqueueAndNotify`'s javadoc and left the page that documents the same span alone. The
first row of that table ("while a notification's phases run, the server is not reading the
connection") also now overstates the case for the drain specifically, which was its motivating
example. Neither mechanical gate catches this: the item declares no retired vocabulary and touches no
`docs/`, which is what makes it worth naming here rather than trusting a check.

**Non-blocking, no action required for this gate.**

* `markAllForRecalculation` still calls `loadVocabulary` inline, which is a session-budget read on the
  watcher thread, so that thread can still spend 30 s per build even with the drain moved off. The
  plan's problem statement named that thread while its "What changes" named only the drain, so whether
  this belongs in the rework or in a fresh item is the author's call.
* `shutdownNow()` interrupts the drain thread, but a drain inside a JDBC read will not notice
  promptly, so "stops a drain in flight from publishing into a closing client" is approximate: the
  drain can still reach its publish and hit a closed socket, which lsp4j logs at INFO. Worth a word in
  the plan rather than a code change.

Verified along the way: `mvn install -Plocal-db` on the delivered tree, the collapse flag against the
enqueue-versus-drain interleavings, `holdsViewFor` against the close-mid-drain window the third test
pins, and every symbol the plan names.
