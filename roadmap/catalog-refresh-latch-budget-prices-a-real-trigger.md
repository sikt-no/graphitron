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

- Ceiling: `DEBOUNCE_MS + 60_000`. Sized so machine load cannot reach it, not snug around the
  measured cost. (Corrected under the round 1 finding below; this bullet read `15_000`, "854 ms",
  and "roughly 17x" when Spec signed off, and those three figures came from a single quiet-machine
  sample. The measurement that replaces them is in the next paragraph, and the finding that forced
  it is recorded under "Reviewer findings".) The real-work trigger's refresh (cold javac parse plus
  jOOQ store writes) was re-measured with a `System.nanoTime` bracket under this module's own
  four-way class concurrency on a loaded fourteen-core machine: sixteen samples between 483 ms and
  4,308 ms. Sized against the top of that spread, this is roughly 14x headroom, where the retired
  1,600 ms budget sat *inside* the spread, under five of the sixteen samples. If this ceiling is
  ever hit, the refresher is broken or hung, not slow.
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
reviewer can re-measure, divide, and compare. Roughly 14x over the worst of sixteen samples
(4,308 ms), against a retired budget that five of those sixteen samples exceeded outright. The
full verification build then confirms the mechanical edit broke nothing.

Round 1 taught the item how the measurement has to be taken, and that method is now part of what is
being verified, not just the number it produced. A single sample from a quiet machine is not a
measurement of a figure whose whole job is to survive a loaded one. To re-measure: bracket the
`refreshJavaSources` call in `javaSourceWriteMovesTheStoreRowWithoutAGeneratorPass` with
`System.nanoTime`, run `mvn test -pl :graphitron-maven-plugin` repeatedly with the module's suite
running its classes four-way concurrent and the machine held under real load (concurrent module
test runs will do it), and take the worst sample rather than the typical one. The spread is wide
because the refresh's own work is milliseconds and the clock is mostly measuring scheduling delay,
so a handful of samples on an idle machine says nothing about the ceiling.

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
  was previously the window's and that 1,600 ms was a race with machine load rather than a ceiling.
  That is what makes the number re-derivable at the next gate: the measurement and the ceiling are
  both in the text, so the ratio can be checked by division. (Round 1 found that the measurement in
  that text was the wrong measurement; the rework below replaced it and the ceiling with it, and
  the re-derivability property is what made the finding possible.)

Per-run cost is unchanged, which is the property that says the split landed on the right axis: the
only figure that grew is one a green run never pays. The class runs 3/3 green in isolation in
3.5 s against 3.9 s before, the 1.6 s of that which is the negative test's sleep being untouched.
That property is what made the round 1 rework cheap: raising the ceiling from 15.1 s to 60.1 s
changed no green run's wall clock at all, which is the whole argument for choosing the ceiling axis
over the honest-smaller-ratio one.

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

### Round 1 response (rework, 2026-08-26)

The finding is accepted in full and its diagnosis is the right one: 854 ms and 17x were a
quiet-machine sample presented as a measurement, and an argument that names loaded conditions has to
be measured under them.

**Re-measured.** A `System.nanoTime` bracket around the `refreshJavaSources` call, twelve runs of
`mvn test -pl :graphitron-maven-plugin` with the module's classes four-way concurrent and the
fourteen-core machine held at load average seventeen to thirty-four by three concurrent module test
suites (`graphitron`, `graphitron-lsp`, `graphitron-mcp`) looping alongside:

    483  701  919  939  1016  1106  1259  1364  1465  2098  2154  2459   (ms)

Folded together with the reviewer's four samples (528, 801, 3,588, 4,308 ms), taken the same way on
the same machine class, that is sixteen samples spanning 483 ms to 4,308 ms. The worst case is the
reviewer's 4,308 ms, so the review's number stands as the campaign's worst and is the one the
javadoc is sized against. The spread itself is the substantive finding: the refresh parses one small
source file and writes a handful of rows, so its intrinsic work is milliseconds and almost the whole
clock reading is scheduling delay under contention. Nothing about a five-fold spread is knowable
from one sample, which is exactly why the original figure did not travel.

**Of the two options offered, the ceiling was raised** rather than the ratio restated, because the
ceiling axis is free on the green path and the "broken or hung, not slow" claim is worth keeping
true rather than weakening. `FIRE_CEILING_MS` is now `DEBOUNCE_MS + 60_000`, about 14x the 4,308 ms
worst case, restoring the order of magnitude the original argument wanted and paying for it only on
a run that was already going to fail. The javadoc states the sixteen-sample spread, the conditions
it was taken under, the worst case it is sized against, and the derived ratio, so the same division
the reviewer performed still checks out.

**One claim in the javadoc got stronger, not weaker.** The history paragraph no longer says the
retired 1,600 ms budget gave "under 2x"; it says the retired budget falls inside the measured
spread, below five of the sixteen samples. That is a checkable statement about the defect rather
than a ratio against a sample, and it is the sharpest available evidence that the item was real: on
the measurements now recorded here, the old budget would have failed roughly a third of the
refreshes.

**Two edits reach approved Spec prose,** disclosed rather than made quietly. The Plan's ceiling
bullet and the Verification section both carried the 854 ms / 17x premise the finding rejected;
leaving them would leave this file asserting the same false measurement the item exists to correct.
Both now carry the re-measured figures, the ceiling bullet says in-line which three figures it
replaced, and the Verification section additionally records *how* the measurement must be taken,
since round 1 showed that the method, not just the number, was the thing missing.

**The non-blocking observation** (the `verify`-bound reference gate reads main sources only, so the
cross-module `{@link}`s here are checked by `test-javadoc-no-fork` on demand and not by the build)
is left where the reviewer put it, as Backlog material rather than a condition on this gate. The
links were re-verified green on the reworked file with `mvn javadoc:test-javadoc-no-fork -pl
:graphitron-maven-plugin -Ddoclint=reference`.
