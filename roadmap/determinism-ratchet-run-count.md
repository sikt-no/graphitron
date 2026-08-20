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

This item is the run count, the per-run cost that survives R733, and the coverage question next to
them. R733 owns the generator's store reads and the two measured changes that make a run cheaper;
this item removes runs. They compound rather than compete and neither waits on the other:
**together, measured, they take the class from 229.0s to 62.91s.**

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

### The test itself costs nothing measurable

Worth establishing before looking for savings inside a run, because it decides where to look. With
R733's two changes applied and the run count at three, the class measures 62.91s and a run measures
21.0s. Three times 21.0 is 63.0. The test's own scaffolding, which is reading 798 files into a map
twice to compare them, copying a tree, and walking it twice for modification times, disappears into
the noise between those two figures.

So there is no version of this item that speeds the class up by tidying the test. Every second is a
generator run, and the only two levers are how many runs there are (the rest of this item) and what
a run costs (below).

### What a run is made of, after R733

Profiling one run with R733's two changes already applied, so this is the residual rather than the
original: **88.1% of a 21.0s run is four store reads**, and no single thing outside them reaches two
percent.

| Call site | Share of the run | Reads |
|---|---|---|
| `ArgmappingProjectionDefects.authorDefects` | 38.3% | `intent_argmapping_projection_defect` |
| `ArgmappingProjectionDefects.unemittableProjections` | 18.6% | `intent_resolved_node_key_projection` |
| `ResolvedKeyProjections.read` | 18.6% | `intent_resolved_node_key_projection`, again |
| `StoreNodeTables.bindings` | 12.6% | `intent_resolved_node_type_id` joined to `intent_resolved_type_binding` |
| `GraphitronModelStore.create` | 1.8% | the store's own DDL, 140 tables and 56 views per run |
| javapoet emission | 0.6% | |
| schema parse | 0.4% | |
| everything else | under 0.4% each | |

Nothing *outside* the store is worth attacking: store boot at 1.8% is 0.4 seconds, emission and the
writing of 798 files are together under one percent, and the schema parse and classpath scan do not
register.

But that is a statement about where the time is not, and it should not be read as "a run cannot get
cheaper". It can, by a lot, and R745 is that finding: the four rows above are deep view stacks that
H2 inlines without common-subexpression elimination, one of them expanding to 2149 relation
instantiations per read. Reducing two relations takes that read from 24.5s to 0.72s and this class
from 229.0s to **20.66s**, measured. Read R745 before assuming the per-run figure in this item is
fixed; the run-count arithmetic here holds whatever a run ends up costing.

### One exception, and it is worth taking: the same view is read twice per run

Rows three and two of that table read the *same relation* over the *same graph*, from two calls
`FactCapture.detect` makes back to back on the same `DSLContext`.

Reads of that view are fully additive, which is the fact that makes this worth fixing rather than a
curiosity. Two measurements pin it. Adding a redundant third read of it costs **+8.1s per run** (the
view plus the `StoreNodeTables.read` that `ResolvedKeyProjections.read` performs first). Removing
one of the two existing reads saves **4.5s per run**, which is 19% of the residual. Nothing caches,
nothing reuses a plan, and the view carries a window so no outer predicate prunes it.

The two reads are not identical, which is why this is a small piece of work rather than a deletion:

* `unemittableProjections` selects twelve columns and inner-joins `intent_argmapping_pair` on
  (graph, site, use site, position).
* `ResolvedKeyProjections.read` selects five columns and does not join.

One fetch serves both: select the union of the columns with the pair table `LEFT JOIN`ed, then
recover each caller's result in Java. The inner join becomes a filter on the pair columns being
present, and each caller re-applies its own `DISTINCT` over its own column set. That is sound rather
than approximate: a left join never drops a row of the view, so the five-column distinct is
unchanged, and it produces the same row multiset the inner join did for the rows that do match, so
the twelve-column distinct is unchanged too.

**A scope note the reviewer should settle.** This is derived-read work, and its natural home is
R733, which already carries the store's read discipline and the other two measured fixes. It is
recorded here because it is the honest answer to "why is this test slow", and because R742 is the
item in motion while R733 is still Backlog. Moving it to R733 and leaving R742 purely about run
count is a defensible call and costs nothing but a cross-reference.

### What the three changes come to together

| Configuration | Runs | Class total | Per run |
|---|---|---|---|
| trunk | 4 | 229.0s | 57.3s |
| R742's run reduction alone | 3 | 167.3s | 55.8s |
| R733's two changes alone | 4 | about 93s, derived | 23.3s |
| R733 + R742, measured | 3 | **62.91s** | 21.0s |
| and the duplicate read removed | 3 | about 56s, ceiling | 18.8s |
| R733 + R745's two reductions, 4 runs, measured | 4 | **20.66s** | 5.2s |

229.0s to 62.91s is measured with both tests green. The last row is a ceiling rather than a
measurement: it was taken by skipping one read outright, which is not a legal implementation, and
the merged query will land slightly above it by the cost of the left join.

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
  entry by entry. This is *not* a performance item and the section above has the number that says
  so: the whole of the test's scaffolding is below measurement noise against the generator runs. It
  is a failure-message item. `Files.mismatch` per path allocates nothing and reports the differing
  byte offset, which beats an AssertJ string diff over a generated Java file when this ratchet
  actually fires, which is the moment it exists for. Worth doing while the class is open, on those
  grounds and not on speed.

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
