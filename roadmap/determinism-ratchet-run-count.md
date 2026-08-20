---
id: R742
title: "The determinism ratchet pays for four full-fixture generator runs and needs two"
status: Spec
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# The determinism ratchet pays for four full-fixture generator runs and needs two

`GeneratorDeterminismTest` is the second most expensive class in the reactor, at 229.0 seconds
across two test methods, which is a third of the whole build. It is not slow because it asserts
anything expensive. It is slow because it runs the entire generator over the entire fixture schema
four times, and the contract it guards needs two of those runs at most.

This item is the run count and the coverage question next to it. It is deliberately not the
generator's own cost: that is R733's, and the two compound rather than compete. R733's measured
changes take the same class from 229.0s to about 86s by making a run cheaper; this item removes
runs. Either alone is worth having and neither waits on the other.

## What the test is actually for

The generator's output contract has three clauses. Naming them first, because the rest of this item
turns on which clause needs what.

**Determinism.** Two independent runs of the generator over one schema produce byte-identical
output trees. Without it a consumer's build produces spurious diffs, and a generated tree cannot be
checked in or compared across machines.

**Minimal-change writes.** A run against a tree the generator already wrote leaves unchanged files
untouched *on disk*, rather than rewriting identical bytes. The file's modification time is the
observable: a consumer's incremental compiler decides what to recompile from it, so a generator that
rewrites every file on every run turns a one-field schema edit into a full recompile.

**Clean removal.** A compilation unit the schema no longer calls for is swept out of the output
rather than left behind as an orphan that still compiles and still resolves.

Two test classes cover these, at two different breadths:

| Class | Tier | Schema | Tests | Cost | Clauses covered |
|---|---|---|---|---|---|
| `IdempotentWriterTest` | unit | trivial two-type SDL | 6 | 4.3s | all three, as writer mechanics |
| `GeneratorDeterminismTest` | cross-cutting | the full fixture, 4024 lines, 215 type definitions | 2 | 229.0s | determinism, minimal-change writes |

The division is sound and each class says so in its own javadoc: the writer's mechanics do not
depend on emitter breadth, so they are pinned cheaply on a two-type SDL, while the cross-cutting
class exists to hold the clauses over *every* emitter at once (interfaces, unions, `@splitQuery`,
`@asConnection`, `@lookupKey`, input types, enums, federation). That breadth is the whole value and
this item does not propose reducing it.

**One thing is off, and it should be settled here.** `GeneratorDeterminismTest`'s class javadoc
names its subject as "the three-clause generator contract (determinism + minimal-change writes +
clean removal)" and then tests two. Clean removal has no cross-cutting case at all: it is pinned
only against the two-type SDL, where an orphan sweep has almost nothing to sweep and no chance to
sweep the wrong thing. So the Spec pass owes a decision, and both answers are defensible: add the
third case (which the run-count reduction below makes affordable, since a clean-removal case needs a
populated tree and a re-run, and this item produces a shared populated tree anyway), or correct the
javadoc to claim the two clauses it holds. What is not defensible is leaving a javadoc that asserts
coverage the class does not have, since the next reader takes it at its word.

## Why it is slow

One full run over the fixture costs 57.3 seconds. About 95 percent of that is the fact store
re-evaluating its own derived relations, which is R733's subject and not restated here. Emission and
the writing of 798 generated files is the remaining few seconds.

The test runs the generator four times, so 4 × 57.3 ≈ 229.0s. That is the arithmetic in full; there
is nothing else in the class.

The control that proves the cost is the fixture's size rather than the invocation:
`IdempotentWriterTest` constructs the generator eleven times and costs 4.3 seconds, because its
schema has two types.

For scale, the whole build pays for six full-fixture runs: four here, one in
`FixtureWarningsGateTest`, and one in the module's default `graphitron:generate` execution.

## Why four runs, and how many the contract needs

Reading the two methods for what each genuinely requires:

* `twoConsecutiveRunsProduceIdenticalOutputTrees` writes into two empty directories and compares the
  trees. Both runs are load-bearing: the assertion is that two *independent* pipeline runs agree, so
  neither can be a copy of the other.
* `secondRunAgainstSameOutputDirPreservesMtimes` generates into a directory, winds every file's
  modification time back two seconds, generates again, and asserts no time moved. Only the *second*
  run is load-bearing. The first one exists to produce a populated tree, and the assertion does not
  care where that tree came from.

So the fourth run is buying a populated directory that the first method already produced. Sharing
one canonical run across both methods, produced once per JVM and copied rather than written into so
the shared tree stays pristine, takes the class to three runs.

**Measured, with both tests still green: 229.0s to 167.3s.** Exactly one run's worth, as predicted.
The change is local to the test class and needs nothing from production code.

## The fork the Spec pass has to settle: is three the floor, or is it two?

Three is the floor *without touching production code*. Two is reachable, by two different routes,
and each gives something up. The Spec pass should pick one and record why, because "just get it to
two" hides a real trade.

**Route A: a seam that writes an already-built output.** `GenerationResult` already carries
`emittedUnits`, the full `Map<String, TypeSpec>` a run emitted, but the code that lands those units
on disk (`writeCommand` / `writeUnits`) is private. Given a public way to write a `GenerationResult`
into a directory, the minimal-change case needs no pipeline run at all: write the units once into an
empty directory, backdate, write the same units again, assert nothing moved. The class drops to the
two independent runs determinism genuinely needs.

What it gives up: the case stops asserting that a *second full generation* leaves the tree alone and
starts asserting that a *second write of one generation's output* does. Those are different
statements. They are equivalent given the determinism clause the sibling method proves, which is a
clean decomposition rather than a loophole, but it is a decomposition and the Spec should say so out
loud. It also adds public API surface whose only consumer is a test, which this repo is right to be
suspicious of.

**Route B: fold determinism into the mtime assertion.** Seed a directory from run one, backdate it,
run the generator into it, and assert no modification time moved. Because the writer's skip decision
*is* a content comparison, "nothing was rewritten" already says "run two produced byte-identical
content to run one". One run proves both clauses.

What it gives up: more, and this is why it is second. The determinism guarantee would then rest on
the writer's comparison being correct. A writer bug that skipped unconditionally would make both
assertions pass vacuously, and the current first method is valuable precisely because it is
independent of the writer: it writes into two empty directories and compares bytes itself. A ratchet
should not depend on the mechanism it guards. Route B would also need an explicit file-set assertion
bolted on, since a file that vanished in run two moves nobody's modification time.

**Recommendation.** Take the three-run version now: it is measured, it is confined to the test, and
it gives up nothing. Treat two as a separate question that the seam decision drives, and note that
after R733 lands, the remaining gap between three runs and two is about 21 seconds rather than 57,
which is a materially weaker case for adding public API.

## Adjacent, and smaller

* **The module runs nothing in parallel.** `graphitron-sakila-example` has no
  `junit-platform.properties` at all. The two methods here hold separate temporary directories and
  are independent once the shared run exists, so concurrent methods would overlap the two remaining
  runs. R733 already carries the rule for this kind of change: one module at a time, and each module
  answers its own shared-state question first. Note it here, do it there.
* **`readAll` slurps both trees into memory.** Two maps of 798 file contents, built to compare them
  entry by entry. `Files.mismatch` per path would allocate nothing and report the differing byte
  offset, which is a better failure message than an AssertJ string diff over a generated Java file.
  Small, and worth doing while the class is open.

## How to re-measure

```bash
# The class alone. -Dleaf-coverage.skip keeps the run from truncating the full-suite traces.
time mvn test -pl :graphitron-sakila-example -Plocal-db -Dleaf-coverage.skip \
  -Dtest=GeneratorDeterminismTest -Dsurefire.failIfNoSpecifiedTests=false
```

The per-run cost is the class total divided by the run count, the runs being uniform. Cross-check
against `FixtureWarningsGateTest`, which is one pipeline run with no emission, so the difference
between the two is emission plus the write of 798 files.

Standing caveats, both of which will produce nonsense if ignored: pass `-Plocal-db` or the jOOQ
catalog jar is silently emptied and the failures will be unrelated cascades, and measure against a
warm local repository or artifact downloads will dominate. Figures in this item were taken on one
4 vCPU sandbox; ratios transfer between machines and absolute seconds do not.
