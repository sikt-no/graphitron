---
id: R1075
title: "An LSP round-trip test times out on a 5-second wait while the reactor saturates the runner"
status: In Progress
bucket: cleanup
priority: 3
theme: testing
depends-on: []
created: 2026-10-20
last-updated: 2026-10-20
---

# An LSP round-trip test times out on a 5-second wait while the reactor saturates the runner

## Goal

`TextDocumentServiceTest`'s round trips stop failing trunk CI on a slow runner, so a red
`graphitron-lsp` means a language-server defect and not that the machine was busy. Today every
request the class sends over the wire (`initialize`, `completion`, `hover`, `definition`,
`references`) waits through `CompletableFuture.get(5, TimeUnit.SECONDS)`, and CI runs the reactor
with `-T 1C`, so `graphitron-lsp`'s tests share the runner with `graphitron`'s 30-minute suite,
`graphitron-mcp` and `roadmap-tool`. Under that load the module's test classes run two to five
times slower than on a quiet machine (`FieldCompletionsTest` above 520 s, `DiagnosticsTest` above
450 s), and a 5-second wall-clock cap on one request is a guess about scheduling rather than a
statement about the server.

`completionAfterMultiByteDescriptionResolvesCorrectDirective` has failed with a
`java.util.concurrent.TimeoutException` in four trunk runs between 2026-10-17 and 2026-10-19:
#6798 (`9d984eb`), #6799 (`2d0609e`), #6817 (`4ed5156`) and #6825 (`0e6faef`). The call that timed
out differs: `initialize` at line 115 in #6825, `completion` at line 123 in the other three. So no
single request is hanging. The method itself took 63 to 107 s, nearly all of it building
`StoreFixture.ofCatalog` before `labelsAt` sends anything; its sibling
`completionRequestRoundTripsThroughTableCompletions` runs the same `labelsAt` flow on the same
fixture and passed in every one of those runs, which fits a timing window rather than a
multi-byte defect. The file has not changed across the failing commits.

Nothing the server does sits inside those five seconds for `initialize`:
`GraphitronLanguageServer.initialize` returns an already-completed future, so the #6825 timeout
was spent entirely in lsp4j's wire path (the piped streams and the message-reader thread) on a JVM
that was not being scheduled. And the stores these tests read carry no budget of their own:
`StoreFixture.access()` is the production shape with the read budgets (the store's per-read
runaway timeout) taken out, so the client-side `get` is the only bound on a request in this class.

## Plan

A fixed wait in this module becomes a hang guard, owned in one place: a bound whose only job is
to stop a test that would otherwise never finish, set far enough above any plausible scheduling
delay that tripping it means the thing waited for is not coming. A wait that succeeds returns as
soon as its answer arrives, so a generous bound costs nothing on a green run; it only changes how
long a genuine hang takes to fail.

**What counts.** Every *positive* wait in `graphitron-lsp`'s test sources, meaning a wait for
something the case expects to happen, bounded by a fixed 5-second literal:

- `TextDocumentServiceTest`: the fifteen `get(5, TimeUnit.SECONDS)` calls on wire requests
  (`initialize`, `completion`, `hover`, `definition`, `references`), including the ones inside
  `labelsAt` and `references`.
- `DiagnosticsDrainThreadingTest`: the `enteredGate.await(5, ...)` calls, the
  `drain.join(TimeUnit.SECONDS.toMillis(5))` before `isAlive()` is asserted false, and the
  deadline in `awaitPublished`. Also `GatedClient.publishDiagnostics`'s
  `release.await(10, TimeUnit.SECONDS)`, which is a different shape: it runs on the drain thread
  and releases the gate by itself when it expires, so once the test-side waits grow to 60 s a test
  thread starved past 10 s would find the gate already open and fail `published` being empty for
  a reason that is not the server's. It becomes a raw `release.await` on `HangGuard.BOUND` with no
  assertion (an assertion thrown on the drain thread would be swallowed); what matters is that it
  is never shorter than the test-side waits it sits under.
- `LauncherFactoryTest` (`no.sikt.graphitron.lsp.server`): the `entered.await(5, ...)` and the
  deadline in `RecordingHandler.awaitAttemptAfter`.
- `StoreOutOfBudgetTest`'s `HANG_GUARD` (60 s), which is already this concept under this name;
  it moves to the shared constant rather than keeping a second copy in the module.
  `graphitron-mcp`'s `StoreOutOfBudgetTest` keeps its own `HANG_GUARD`: that module has no edge to
  `graphitron-lsp`'s test sources, and adding one for a constant is not worth the dependency.

The goal names the module, not the one class, because every one of these waits shares the
exposure that failed: a JVM starved by the reactor stalls a latch release or a drain thread
exactly as it stalls the wire. Only `TextDocumentServiceTest` has been seen failing, and the
others are cheap to carry in the same change. A *negative* wait (one asserting something does not
happen within a window) would cost real wall-clock if lengthened; the inventory above contains
none, and the implementer confirms that while converting, keeping any such wait on its own
literal with a comment saying what the window measures.

**Where it lives.** A small public test-scope class in `graphitron-lsp`, `HangGuard` in
`no.sikt.graphitron.lsp` (public because `LauncherFactoryTest` sits in the `server` subpackage),
holding:

- `HangGuard.BOUND`, a `Duration` of 60 seconds, the value `StoreOutOfBudgetTest` already uses.
  Sixty against an observed stall above five and a whole method under load of 63 to 107 s, nearly
  all of it fixture build, which this bound does not cover.
- `<T> T answer(String what, Future<T> future)`: `future.get` within `BOUND`, and on
  `TimeoutException` a failing assertion that reads "the server never answered <what> within
  60 s", so the report names the request rather than surfacing a bare `TimeoutException` with a
  line number. `ExecutionException` propagates as it does today.
- `void await(String what, CountDownLatch latch)` and `void until(Supplier<String> what,
  BooleanSupplier condition)` for the latch and polling shapes, with the same failure wording; the
  polling form replaces the two hand-rolled `System.nanoTime()` deadline loops. `until` takes its
  description as a supplier evaluated at failure time so it can carry the observed state:
  `awaitPublished`'s closing `hasSize(count)` prints the published list today, and a converted
  call puts that list into the message rather than losing it. `DiagnosticsDrainThreadingTest`'s
  `join` becomes `join(BOUND)` followed by its existing `isAlive()` assertion.

The `what` strings are the request or event in the reader's words (`"initialize"`,
`"completion at 1:23"`, `"the drain reaching its publish"`), and where a converted call already
carries an AssertJ `.as(...)` description, that sentence becomes the `what` so nothing a failure
says today is lost.

**What stays out.**

- Test scheduling against the reactor. CI's `-T 1C` with tests on is deliberate: the workflow's
  comment on the build step makes parallel test tolerance an enforced invariant and says the fix
  for a parallel-only failure is the test, never serializing the build. Pinning `graphitron-lsp`
  to run alone, or after `graphitron`, would reintroduce exactly that serialization for one
  module.
- `graphitron-maven-plugin`'s `DevServerTest` (seven `get(5, TimeUnit.SECONDS)` calls) and the
  5-second waits in `graphitron`'s and the plugin's other tests. Same shape, no observed failure,
  and the goal is about what a red `graphitron-lsp` means. Adopting `HangGuard` there later costs
  nothing structural, since `graphitron-maven-plugin` already depends on `graphitron-lsp`'s
  test-jar; a flake there is a Backlog item of its own that reuses this class.
- Speeding up `StoreFixture.ofCatalog`. It is where the method's minute goes, but it is outside
  every bound this item touches, so making it faster changes nothing about the failure.

## Tests

Before converting anything, try to reproduce the failure locally so the change is shown to
remove it rather than presumed to: run `TextDocumentServiceTest` pinned to one core with CPU
burners on the same core (for example `taskset -c 0` on both the surefire run and a few
`yes > /dev/null` loops), and record whether a round trip trips the 5-second bound; if it does,
repeat after the change and record that the class passes. Neither run is committed; the commands
and outcomes go in the implementation commit's message. The stall may as well come from GC
pressure after the fixture build as from scheduling, so a reproduction that does not trip within
a handful of attempts is recorded as such and is not chased further: the change's claim, that a
stall shorter than the bound no longer fails a case, follows from the bound itself, and the
structural evidence below carries it.

Committed coverage is `HangGuardTest`, unit-tier, beside the class: `answer` on a future that never
completes fails with a message naming the request, `await` on a latch nobody counts down likewise,
and `until` on a condition that never holds likewise, each driven through a package-private
overload that takes the bound so the test runs in milliseconds rather than a minute. A completed
future, a counted-down latch and a true condition return immediately.

The rule outlives this change only if something enforces it, since a new case copied from
`DevServerTest` brings `get(5, TimeUnit.SECONDS)` straight back. So `FixedWaitGuardTest`, a
source-scan meta-test in `graphitron-lsp`'s test tier in the manner of the existing guard tests
(`RoadmapReferenceGuardTest`, `ModelEmitVocabularyGuardTest`), fails the build on a timed `get`,
`await` or `join` whose bound is a numeric literal, and on a `System.nanoTime()` deadline built
from one, anywhere in `graphitron-lsp/src/test` other than `HangGuard` itself. A genuine negative
wait escapes by naming its window as a constant whose javadoc says what it measures, which is the
documentation such a wait owes anyway; the guard matches literals only. It carries a scanned-file floor as
`RoadmapReferenceGuardTest` does, so a walk that reaches nothing fails rather than passing
vacuously, and its own cases pin one flagged literal and one named-constant pass on inline source
strings.

Completion is then: `FixedWaitGuardTest` green over the module's tests with no exemptions beyond
`HangGuard`; `StoreOutOfBudgetTest` declares no `HANG_GUARD` of its own; the full module suite
green under the verification build, which runs it under `-T 1C`.

## Implementation notes

Shipped as planned. `HangGuard`, `HangGuardTest` and `FixedWaitGuardTest` sit in
`no.sikt.graphitron.lsp`'s test sources; every wait in the inventory goes through `HangGuard`, and
`StoreOutOfBudgetTest` reads `HangGuard.BOUND`. Three points where the code settled a detail the
plan left open:

- `FixedWaitGuardTest` needs no exemption at all, `HangGuard` included: its own waits are bounded
  by a `Duration` parameter, which the literal-only match passes. The scan blanks comments, strings
  and text blocks before matching, which is what lets its inline-source cases live in the file it
  scans. Run against the pre-change tree it reports exactly the inventory above, 23 sites.
- The latch and polling failures read "waited 60 s for <what>, which never came", so their `what`
  strings are phrased as what was awaited ("the drain to reach its publish while the mutator had
  already returned"), keeping the former `.as(...)` sentences.
- `FixedWaitGuardTest` finds the module directory through `StoreClientBoundaryTest.moduleRoot()`,
  now package-private, rather than a second copy of the walk to the repository root.
