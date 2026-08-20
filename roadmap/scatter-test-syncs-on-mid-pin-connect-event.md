---
id: R744
title: "TenantScatterSubstrateTest synchronizes on a connect event that fires mid-pin, so a worker's self-abort races the release assertion"
status: Backlog
bucket: cleanup
priority: 3
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# TenantScatterSubstrateTest synchronizes on a connect event that fires mid-pin, so a worker's self-abort races the release assertion

`TenantScatterSubstrateTest` has two tests that fail intermittently in CI. Both wait for a
recorded `connect:<tenant>` event before doing the thing that quarantines a tenant key, on the
belief that seeing the event means the tenant's worker has finished acquiring its connection. It
does not: the event fires part-way through the acquisition, and the worker still has one more
step to run. When the quarantine lands inside that gap the worker cleans up after itself, and the
test then asserts against a cleanup that already happened and was thrown away.

## Vocabulary

A *pinned connection* is the one database connection a tenant key owns for the duration of a
request. *Quarantining* a key marks it as no longer safe to hand out: its worker may still be
running, so the connection must be discarded (JDBC `abort`) rather than closed and returned to
the pool. `releaseAll` walks the surviving pins at the end of an operation and aborts the
quarantined ones. The tests observe all of this through a recorded event list fed by fake JDBC
objects (`getConnection:A`, `connect:A`, `abort:A`, and so on).

## What actually happens

The emitted carrier's `entryFor` mints a pin inside a `computeIfAbsent`, and then, *after* that
call returns, re-checks whether the key was quarantined while the pin was in flight. If it was,
the worker removes its own entry from the map and aborts its own connection. That re-check is
deliberate and correct: it is what stops a late pin from leaking into a finished operation.

The fake connection records `connect:<tenant>` from inside `prepareStatement`, which runs inside
`computeIfAbsent`, before that post-check. So the window between "the test can see `connect:A`"
and "the worker has passed its post-check" is unsynchronized. Both affected tests fire their
quarantine into that window:

* `interruptedJoin_quarantinesKeysLikeATimeout_soReleaseAbortsNotCloses` waits for `connect:A`
  and `connect:B`, then interrupts the dispatch thread, which quarantines both keys.
* `rejectedExecutionMidSubmit_quarantinesAlreadySubmittedKeys_andPropagates` waits for
  `connect:A`, then throws a rejection from the executor, which quarantines the submitted key.
  Its inline comment claims this defeats the race; it does not.

Each test then calls `events.clear()` and asserts that `releaseAll` produced `abort:<tenant>`. If
a worker self-aborted first, the abort event is wiped by the `clear()`, `releaseAll` finds no
entry left to abort, and the assertion fails on a missing `abort:` line. The reported CI failure
is exactly this shape, with only one of the two expected aborts surviving.

The behaviour under test is correct in both interleavings: the connection is aborted, never
closed, and never reused. Only the test's synchronization point is wrong.

## Reproduction

Sleeping for 400ms inside the fake's connect-phase `prepareStatement` for one tenant widens the
gap and fails the interrupt test on every run, with the event list showing the worker's own
`abort:A` landing before the `clear()`:

----
DEBUG before clear: [getConnection:A, getConnection:B, connect:B, connect:A, abort:A]
DEBUG after releaseAll: []
----

## Plan

Synchronize on the workers being inside the per-tenant body instead of on the connect event. A
worker only reaches its `perTenant` function once `entryFor` has returned, which is provably
after the post-check, so the self-abort path is closed by construction and `releaseAll` is the
only remaining aborter.

. In the interrupt test, add a `CountDownLatch` of two counted down at the top of the per-tenant
  body, and await it before interrupting the dispatch thread, replacing the poll loop over
  `events`.
. In the rejection test, do the same with a latch of one, awaited inside the rejecting executor
  before it throws, replacing that test's poll loop over `events`.
. Correct the stale comments in both tests to say what the latch actually establishes.

Both changes are test-only; no emitted code changes. The fix is verified by re-running the
reproduction above: with the artificial mount delay in place, the tests must still pass.
