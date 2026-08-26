---
id: R832
title: "CatalogRefreshTest budgets a real refresh like a no-op trigger"
status: In Progress
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-08-25
last-updated: 2026-08-26
---

# CatalogRefreshTest budgets a real refresh like a no-op trigger

`CatalogRefreshTest.javaSourceWriteMovesTheStoreRowWithoutAGeneratorPass` (in
`graphitron-maven-plugin`) waits on a `CountDownLatch` for 1.6 seconds (a 100 ms debounce window
plus 1500 ms) and fails with "source refresher must fire on .java write" when the latch times out.
That budget was inherited from `SchemaWatcherTest`, whose triggers are no-op `countDown()`
runnables, but this test's trigger does real work inside the awaited window: the production
refresh path, which is a cold javac parse (`SourceWalker` via the Compiler Tree API, first use in
the surefire JVM) plus jOOQ writes into an in-memory fact store. Measured on a *passing*
full-suite run, that refresh took 854 ms of the 1500 ms window, so the headroom is under a factor
of two, and the module's test classes run four-way concurrent (the parallelism arrives through the
`graphitron-model` test-jar leak R764 documents; this module is a fourth consumer that item should
also name), with `DevMojoTest`'s ~13 s of real generator work competing for the same cores. The
test failed twice on one machine on 2026-08-25 (both times on a cold first build of a session),
passes reliably in isolation, and showed no swallowed exception when instrumented: it is pure
slowness, a wall-clock threshold that is a flake on a loaded machine.

## Plan

One test class changes, no production code. The defect in `CatalogRefreshTest` is not that a
number is too small; it is that one name, `WAIT_MS`, carries two independent quantities that
happen to share a value today:

- A **failure-path ceiling**: how long a positive test hangs before concluding the trigger never
  fired. `CountDownLatch.await` returns the moment the latch counts down, so this is paid only on
  a red run; a larger value is strictly safer.
- A **quiescence window**: how long the negative test watches to be confident a mis-wired watcher
  genuinely did not fire. `Thread.sleep` pays it in full on every green run; a larger value is
  strictly slower, a smaller one strictly less sensitive.

The change replaces `WAIT_MS` with two constants named for those facts (something like
`FIRE_CEILING_MS` and `QUIESCENCE_MS`; the implementer picks the names). Both positive awaits
(`classFileWriteReachesTheWorkspace` and `javaSourceWriteMovesTheStoreRowWithoutAGeneratorPass`)
take the ceiling, because that is what an await budget is, not because either test flaked; the
first test's rebuilder is near-free today, but nothing holds it that way, and a generous ceiling
on it costs nothing green. The sleep in `graphqlsWriteDoesNotFireClasspathWatcher` takes the
window.

The values, each argued on its own axis:

- Ceiling: `DEBOUNCE_MS + 15_000`. Sized so machine load cannot reach it, not snug around the
  measured cost: the real-work trigger's refresh (cold javac parse plus jOOQ store writes)
  measured 854 ms on a passing full-suite run, so this is roughly 17x headroom where the old
  window gave under 2x. If this ceiling is ever hit, the refresher is broken or hung, not slow.
- Window: `DEBOUNCE_MS + 1500`, the current value, now deliberate rather than inherited. On its
  own axis the window prices dispatch plus debounce for a fire that must *not* happen, so the
  floor is a small multiple of the 100 ms debounce; it stays at 16x that multiple because under
  the very load that caused the flake, a genuinely mis-wired fire could land late, and a short
  window would turn that loud failure into a silent false green. The 1.6 s per-run cost buys that
  sensitivity.

Each constant carries a short javadoc making its argument in the shape the tree already uses for
wall-clock budgets (`DerivedReadCostTest.BUDGET_FLOOR_MILLIS` is the closest precedent, with
`ThreadConfinedStore.BOOT_BUDGET` alongside): what runs inside the budget, the source measurement,
which direction is safe, and what hitting it means. Live symbols only, per the comment conventions
in `CLAUDE.md`. No shared budget helper: the repo's convention for wall-clock waits is a local
constant per test class, and a helper for two call sites would be a parallel mechanism.

## Verification

For a flake fix, a green build is nearly zero evidence: the test was green on most runs before the
change too, and the original failure is not practically reproducible on demand (it needs a loaded
machine and a cold surefire JVM). The completeness answer is the headroom ratio, which is
falsifiable: the measured refresh cost and the ceiling both live in the constant's javadoc, so a
reviewer can re-measure, divide, and compare. Roughly 17x over the 854 ms measurement, against
under 2x before. The full verification build then confirms the mechanical edit broke nothing.

## Out of scope

- The module's test-class parallelism (R764's fourth consumer). Settling it reduces contention
  here but is tracked there; this item makes the budget safe under whatever parallelism the module
  ends up with.
- `SchemaWatcherTest`'s budgets. Its triggers really are no-op `countDown()` runnables, so its
  1.6 s value prices only watcher dispatch plus debounce and has held. Disclosed gap: nothing
  enforces that its triggers stay no-ops; a future test wiring a real-work trigger to the
  inherited constant is exactly how this defect arrived, and the constraint there is review-only.
- `DebounceExecutorTest`'s 2 s await: same no-op-trigger situation, same reasoning.

## Implementation notes

Shipped as planned, no design fork. `WAIT_MS` became `FIRE_CEILING_MS` (`DEBOUNCE_MS + 15_000`,
taken by both positive awaits) and `QUIESCENCE_MS` (`DEBOUNCE_MS + 1500`, taken by the negative
test's sleep), each with a javadoc arguing its own number: what runs inside the budget, the
measurement it is sized against, which direction is safe, and what reaching it means. The names are
the ones the plan floated. No production code, no other file.

Two things the plan left to the implementer and how they were settled:

- The javadoc links resolve as live symbols across module boundaries rather than falling back to
  `{@code}`. `graphitron` is a compile-scope dependency of `graphitron-maven-plugin` and its
  test-jar is on the test classpath, so `SourceWalker` and `FactWriters.refreshJavaSources` are
  both linkable from here; `DevMojoTest` is linked by FQN so the citation costs no import. The
  ceiling's javadoc also links the two test methods that take it and the window's links the one
  that takes it, so each budget names its own call sites.
- The ceiling's javadoc carries the history paragraph the precedent uses, stating that the figure
  was previously the window's and that under 2x over a measured 854 ms is a race with machine load
  rather than a ceiling. That is what makes the number re-derivable at the next gate: the
  measurement and the ceiling are both in the text, so the ratio can be checked by division.

Per-run cost is unchanged, which is the property that says the split landed on the right axis: the
only figure that grew is one a green run never pays. The class runs 3/3 green in isolation in
3.5 s against 3.9 s before, the 1.6 s of that which is the negative test's sleep being untouched.

## Reviewer findings

Round 1, In Review -> Ready. Question 1 (is this the approved change) passes cleanly. Question 2
(how do we know the item is complete) fails on the one piece of evidence this item named for
itself.

**Question 1, no finding.** The delivered tree is line for line what Spec approved: `WAIT_MS` split
into `FIRE_CEILING_MS` (`DEBOUNCE_MS + 15_000`, taken by both positive awaits) and `QUIESCENCE_MS`
(`DEBOUNCE_MS + 1500`, taken by the negative test's sleep), each carrying a javadoc in the
`DerivedReadCostTest.BUDGET_FLOOR_MILLIS` shape, live symbols only, no shared helper, no production
code, one file plus roadmap markdown. Nothing was skipped, nothing unapproved was added, no design
was substituted. The out-of-scope boundaries hold: `SchemaWatcherTest` and `DebounceExecutorTest`
are untouched, and R764 already names `graphitron-maven-plugin` as its consumer. The javadoc's
cross-module `{@link}` targets do resolve; `mvn javadoc:test-javadoc-no-fork -pl
:graphitron-maven-plugin -Ddoclint=reference` is green on the delivered file.

The split also demonstrably does its job. On one reviewer run the refresh took 4,308 ms, which the
retired 1.6 s budget would have failed outright and the new ceiling absorbed without noticing.

**Question 2, blocking finding: the headroom ratio does not survive re-measurement.** The
Verification section made the ratio the completeness answer and said why: "the measured refresh
cost and the ceiling both live in the constant's javadoc, so a reviewer can re-measure, divide, and
compare." Re-measured, with a `System.nanoTime` bracket around the `refreshJavaSources` call and the
module's own suite running four-way concurrent, the refresh cost across four runs was:

    528 ms   801 ms   3,588 ms   4,308 ms

on a 14-core machine at load average ~21, which is the loaded condition this item exists to survive
and the same condition the implementation commit reports building under. The 854 ms in the javadoc
sits near the bottom of that spread, not at its centre and nowhere near its top. Dividing as the
spec asks: 15,100 / 4,308 is about 3.5x, not the "roughly seventeen times the room it took" the
javadoc claims. The figure is a single sample from a quiet run presented as the measurement, and it
does not travel to another machine.

Two sentences of the ceiling's javadoc are therefore untrue as written: "this leaves it roughly
seventeen times the room it took", and "If this ceiling is ever reached, the refresher is broken or
hung, not slow." At 4.3 s observed against a 15.1 s ceiling, a slow refresher is three and a half
times away from reaching it, not out of reach.

This is the shape of the original defect at a safer magnitude. The item was filed because a
wall-clock figure was carried over without an argument for this class's conditions; the replacement
figure is argued from a measurement taken outside the conditions the argument invokes by name.
Note that the 854 ms and the 17x both come from the approved Spec body, so this is a defect in the
premise the implementer faithfully carried, not a deviation from it. It surfaces here because this
gate is the item's first fresh-context reading, the Spec -> Ready sign-off and the implementation
having been the same session.

**What would satisfy it.** Re-measure the refresh under the module's own concurrency on a loaded
machine, take the worst case rather than a single quiet sample, and make the javadoc's stated
measurement and derived ratio match it. Then either:

- keep `DEBOUNCE_MS + 15_000` and state the honest ratio (a range, or worst-observed, giving
  roughly three to four times under load), dropping the "broken or hung, not slow" claim or
  weakening it to what 3.5x supports; or
- raise the ceiling until "machine load cannot reach it" is true with the headroom the argument
  wants. The value is free on the green path, which is the whole point of the ceiling axis, so a
  larger figure costs nothing.

The choice between them is the implementer's; the finding is only that the number in the javadoc
and the number a reviewer measures have to be the same number. `QUIESCENCE_MS` is unaffected: its
figure is argued on its own axis, and 1,600 ms is 16x the 100 ms debounce as stated.

**Non-blocking, no action required here.** The reference gate (`javadoc-no-fork`, bound at `verify`
in the root pom) reads main sources only; `test-javadoc-no-fork` is named in that pom's comment as
the pair for test sources but is not wired. So the live-symbol `{@link}`s this item deliberately
chose over `{@code}` are compiler-checked for the statically imported symbol and unchecked for the
rest. They resolve today, verified above. Worth a Backlog item, not a condition on this gate.
