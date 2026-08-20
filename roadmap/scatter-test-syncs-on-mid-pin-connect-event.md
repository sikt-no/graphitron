---
id: R744
title: "TenantScatterSubstrateTest synchronizes on a connect event that fires mid-pin, so a worker's self-abort races the release assertion"
status: Spec
bucket: bug
priority: 3
theme: testing
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

Sleep 400ms inside the fake connection's connect-phase `prepareStatement` for tenant A, placed
*after* the `events.add` rather than before it. Placement is what makes the probe deterministic:
recording the event first releases the test's poll immediately, so the interrupt lands while the
worker is still parked inside `prepareStatement`, with its whole post-connect path (hook `execute`,
`DSLContext` build, provider swap, `computeIfAbsent` return, post-check) still ahead of it. The
interrupt test then fails on every run, and the surviving assertion shows the worker's own abort
having consumed the entry before `releaseAll` could:

```
releaseAll produced ["abort:B"], could not find ["abort:A"]
```

`abort:A` is missing because worker A self-aborted before the `events.clear()`; B, which passed its
post-check before the interrupt, is still in the map, so `releaseAll` aborts it normally. One of the
two expected aborts survives, which is the shape the CI failure reported.

The delay is a diagnostic, not a proposed test fixture: it is how the interleaving was pinned
down, and it is the check the implementer re-runs to confirm the fix closes it. With the probe
still active, the latch below turns the every-run failure into a pass.

---

## Decision: synchronize on the per-tenant body, not on a recorded event

The spec pass asked whether the emitted runtime should change, because the cheaper-looking fix is
to make the worker stop aborting its own entry. It should not, and saying why is what fixes the
right thing.

### The post-check is load-bearing, and both interleavings are correct

`entryFor`'s re-check after `computeIfAbsent` exists because a pin can complete after the
operation it belongs to has moved on: after the key's join deadline passed, or after `releaseAll`
already drained the map. Without it, such a pin leaks a live connection into a finished
operation, which is the exact leak the `closed` flag was introduced to catch. Deleting or
weakening it to make a test deterministic would trade a real leak for a green assertion.

So the contract the test is there to hold is: the tenant's connection is *aborted*, never closed
under a possibly-live worker, and never reused. That holds in both interleavings. The only thing
that varies is which thread performs the abort and when, and the test was written as though only
`releaseAll` ever could.

### The event is the wrong clock

`connect:<tenant>` is recorded by the fake `Connection`'s `prepareStatement`, which the session
hook runs while the pin is still being minted. Between that record and the worker becoming
quiescent there is still: the hook's `execute`, the `DSLContext` build, the transaction-provider
swap, the `computeIfAbsent` return, and the post-check. A test that treats the event as "the
worker is settled" is asserting on a state the event does not report.

The per-tenant body is the correct clock, and it is exact rather than approximate. A worker
reaches `perTenant` only after `entryFor` has returned normally, which is *after* the post-check.
Once every worker is parked in its body on the `hold` latch, no worker will call `entryFor`
again, so the self-abort path is unreachable for the rest of the test and `releaseAll` is the
only remaining aborter. This is a proof from the control flow, not a widened timing margin.

## Implementation

All test-only, in `TenantScatterSubstrateTest`. No emitted code changes.

* `interruptedJoin_quarantinesKeysLikeATimeout_soReleaseAbortsNotCloses`: add a
  `CountDownLatch pinned` of two, counted down as the first statement of the per-tenant body, and
  await it (bounded, asserted) before `dispatch.interrupt()`. Delete the poll loop over `events`
  and its deadline arithmetic.
* `rejectedExecutionMidSubmit_quarantinesAlreadySubmittedKeys_andPropagates`: the same, with a
  latch of one, awaited inside the rejecting `Executor` before it throws. The await runs on the
  submitting thread while the accepted worker runs on the delegate pool, so it cannot self-block.
  `Executor.execute` declares no checked exception, so the existing interrupt-handling shape
  around the wait is kept.
* Both tests' comments currently assert the old, false claim (the rejection test's says in as many
  words that its wait "cannot flake the release assertion"). Replace them with what the latch
  establishes: the workers are inside the per-tenant body, so the post-check has already run.
* Add a one-line note at the fake connection's `prepareStatement` recording site saying the
  connect event fires mid-pin and is not a worker-is-settled signal. That site is what misled two
  tests; the warning belongs where the next author reads it.

## Tests

No new test is proposed. The two repaired tests are themselves the enforcers, and the change makes
them assert the same contract on a sound clock rather than a lucky one. Verification is the
reproduction above: with the artificial mount delay injected, both tests fail before the change
and pass after it. Then the class runs clean without the delay.

The Spec review ran that gate rather than reasoning about it. Under the probe both tests fail
every run on the missing `abort:A`; with the latches applied each passes three runs out of three
with the probe still active; and the class is 9/9 green with the probe removed. The implementer
should reproduce this rather than trust it, but the plan is known to work as written.

A guard that fails any test polling `events` as a synchronization primitive was considered and is
not worth its weight at two call sites, both of which this item removes.

## Rejected alternatives

* **Drop the `events.clear()` and assert over the whole stream.** It admits either aborter, but it
  does not close the race: when the worker wins the entry, its `abort` lands after `releaseAll`
  has already returned, so the assertion can still run first. It weakens the assertion without
  making it sound.
* **Retry or sleep before asserting.** Turns a proof into a timing margin, on the same runner
  whose load produced the failure.
* **Make the worker skip its own abort when `releaseAll` will run anyway.** This is the emitted
  change rejected above; it reintroduces the late-pin leak.

## Not in scope

* Any change to `ConnectionRuntimeClassGenerator` or the emitted carrier. The runtime is correct
  as it stands and this item must not be read as licence to touch it.
* The other seven tests in the class, with one honest caveat recorded below.
* The `straggler_releaseAllAbortsItsConnection_...` margin. That test reaches the same quarantine
  state through the join deadline rather than an event poll, and it carries the *same* defect this
  item fixes, protected only by a wider margin: raising the probe above to 900ms so the pin outlasts
  the 800ms deadline fails it with the identical missing-`abort:A` shape. What protects it in
  practice is 800ms of in-memory fake JDBC with jOOQ's static init already warmed in `@BeforeAll`,
  which is a timing margin and not the control-flow proof the latch gives the two tests above. It
  stays out of scope because no equivalent fix is available here: when the deadline fires the
  dispatch thread is blocked inside `scatter`, so the test has nowhere to await a latch from, and
  making it deterministic means restructuring what the test drives rather than how it synchronizes.
  Left as is, deliberately and with the reason written down; worth its own item if CI ever shows it.
  `scatter_joinDeadline_...` shares the margin but not the exposure, since it asserts on the
  `TimedOut` outcome and never on abort events.
* The `execution`-tier and pipeline coverage of fan-out. This is a unit-tier synchronization fix.
