---
id: R798
title: "A build swap still spends its session budget loading the directive vocabulary on the watcher thread"
status: Backlog
bucket: bug
priority: 3
theme: lsp
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# A build swap still spends its session budget loading the directive vocabulary on the watcher thread

`Workspace.markAllForRecalculation` calls `loadVocabulary` before it enqueues anything, and that call is a
read on the session-wide reader, so it is bounded only by `DevMojo.SESSION_READ_BUDGET`, 30 s. The
diagnostics drain has since moved off the triggering thread, which was the larger half of the same
problem and is now fixed; this call did not move with it. Every build swap therefore still occupies the
dev loop's watcher thread for as long as the vocabulary read takes, and the thread it occupies is the
one the loop needs for the next swap. Filed out of the drain item's In Review review as the residue its
"What changes" did not cover, rather than widened into it after approval.

Two things make this worth measuring before it is worth fixing. The read may well be cheap, in which
case the finding is a latent hazard rather than a live cost and the item is about where the ceiling
sits rather than about a number a developer feels; nobody has timed it. And the read is a *different*
read from the drain's, on the same reader, so if it is not cheap the two now contend on one connection
in a way they did not when both were inline on one thread.

The second half is a diagnosability point, and it is the sharper one. The dev loop's trace splits a
mutation into `workspace.mutate` and `workspace.notify`, and
`docs/architecture/how-to/dev-loop-internals.adoc` now teaches a reader that `notify` should be
trivially small because the listener is a flag-and-submit. This call sits outside both spans, so time
spent here is attributed to no span at all: a contributor investigating a slow build swap reads a small
`mutate`, a small `notify` and a small drain span, and the trace offers nowhere for the missing seconds
to have gone. Whatever the remedy for the blocking is, the span coverage is worth closing on its own,
because a trace that accounts for none of a cost is worse than one that names it.

Not in scope: the cost of the vocabulary read itself as a database question, which is the shape the
diagnostics-drain budget-overrun item, since shipped, took for the drain's statement and which the
`store-performance` skill's method covers if a measurement here turns out to want it.
