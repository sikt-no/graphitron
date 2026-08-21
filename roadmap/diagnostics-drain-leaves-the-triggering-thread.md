---
id: R796
title: "The diagnostics drain never runs on the thread that triggered it"
status: Spec
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
against a real session, with the drain holding for the full 30 s.

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

