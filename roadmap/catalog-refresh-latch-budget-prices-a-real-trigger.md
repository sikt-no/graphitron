---
id: R832
title: "CatalogRefreshTest budgets a real refresh like a no-op trigger"
status: In Review
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
