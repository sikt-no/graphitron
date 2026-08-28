---
id: R865
title: "Obtaining a fact store means running a generation, and capture welds in a refresh cadence no caller can decline"
status: Spec
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# Obtaining a fact store means running a generation, and capture welds in a refresh cadence no caller can decline

> **Re-premised 2026-08-28, and retitled.** This item was filed against a refresh that appeared never
> to return. It returns. A capture of the consumer schema has since been observed to finish at four
> hours and nineteen minutes, and the same store re-priced on the shipping DDL refreshes in **43.0
> seconds**, so the survey of fifteen empty store files is explained by everybody killing a long run
> rather than by non-termination. The number this item offered to produce, what the register's refresh
> costs, has been measured position by position. R856 is dissolved into R876; the evidence is in
> `roadmap/audits/2026-08-28-derived-read-cost-premise.md`, and R848 reached Done.
>
> An earlier redirect note read this as strengthening the item. It does not, and the honest accounting
> is below under "What the re-premising cost this item". The parts that survive are real and this item
> should still land; the `<skipMaterialize>` half of it is now the weakest thing in it, and the section
> that used to be its headline argument is its open question.

Every capture pays the materialization refresh, and no caller can decline it. `FactCapture.capture`
ends its one transaction with `Materializations.refresh`, then calls `Materializations.analyse`
straight after the commit. There is no parameter, no property and no goal that stops before either.

And there is no way to obtain a fact store except by generating from it. Every entry point that fills
a store is a generation or a validation, so a person who wants the facts has to run the thing that
consumes them. That is the half of this item nothing has weakened.

## What the re-premising cost this item

Four of this item's arguments are dead and one is diminished. Naming them here rather than leaving a
reviewer to find them.

**Dead: the refresh does not return.** It does. The title said otherwise and has been changed.

**Dead: the measurement subtraction.** Running the goal with and without the flag was going to produce
the refresh's contribution to a capture, "a figure nobody can state today". It is stated: the audit
prices every position, and the pass is 43.0 seconds.

**Dead: R848 needs that number.** R848 is Done, having priced the register as a set without it.

**Dead: R856 needs a populated consumer store.** R856 is dissolved, and a populated consumer store
exists and is the audit's instrument.

**Diminished: the debugging hazard.** A capture killed mid-refresh still commits nothing, and that is
still a bad shape, because the captured rows are hostage to a step that does not return them. But the
exposure is 43 seconds rather than four hours, so this is now a design objection rather than an
operational one, and it does not on its own buy a parameter.

**Untouched: the cadence defect, and the missing goal.** `FactCapture.capture` welds the writer
cadence in, so no caller can express the reader cadence the API otherwise supports. And nothing fills
a store without generating. Both are true today and neither was ever an argument about the clock.

## Vocabulary

A **registration** is a row of `meta_materialize`: a derivation kept as a view under a `_live` name,
plus a table of the same shape under the canonical name that readers spell. The **refresh** is the
pass that empties and refills those tables, and today it runs inside the capture's own transaction,
after the captured rows are flushed.

## The transaction shape, which is a design objection and no longer an operational one

Because the refresh is inside the capture's transaction, a capture that is killed during the refresh
commits **nothing**. Not the refreshed tables, which is expected, but also not the SDL rows, not the
catalog rows, not the classpath census, and not the capture-cadence derivations. Everything the run
transcribed is hostage to a later step that returns nothing to it.

The survey that motivated this is still a fact and its explanation has changed. Fifteen store files a
real consumer schema left on disk all held zero graphs and zero fields; the only populated ones came
from an older DDL with fewer registrations. That reads as non-termination and is not: the pass does
finish, and what those fifteen files record is fifteen people deciding not to wait four hours.

Which is the honest scope of the complaint. Coupling the transcription's durability to the refresh's
completion is the wrong shape whatever the refresh costs, because it makes an unrelated step able to
throw away work that succeeded. But at 43 seconds nobody is killing a refresh, so this buys a design
argument and not a user.

## What changes when this lands

**`graphitron` gains a goal whose job is to fill the store.** This is now the item's principal
deliverable rather than its third bullet. Today the only way to obtain a fact store is to run a
generation or a validation, which means anybody who wants to look at the facts has to run the thing
that consumes them, and anybody who wants a store as an instrument has to keep one from a run that
happened to leave it behind. That is exactly what the derived-read-cost audit had to do, and it is
why every figure in R876 rests on one kept file with a recorded SHA rather than on a store anyone can
make. `mvn graphitron:capture` makes producing one a command.

It is also the first half of the shape R864 is heading for, capture produces a store and the
generator consumes one. This item does not deliver that inversion and does not depend on it, but it
puts the producing half on the command line where a person can reach it.

**A caller can express its cadence.** `Materializations` already distinguishes the writer cadence
from the reader cadence and calls the difference "a real contract, not a convenience". Capture cannot
express it. After this it can, at one call site, which is the seam R864 generalises.

The default path does not change. A capture that is not asked to skip the refresh runs it exactly as
it does now, in the same transaction, with the same rows at the end.

## The open question: whether `<skipMaterialize>` still earns its place

It was the item's headline and it is now its weakest part, so it is stated as a question rather than
carried as a decision.

**What it has lost.** Every argument that justified it was about the clock: a refresh that would not
return, a subtraction two other items needed, a store nobody could obtain. The first is false, the
second is measured, and the third is answered by the goal alone, since a `graphitron:capture` that
runs the refresh still produces a store in 43 seconds more than one that skips it.

**What it still has.** One argument, and it is the one that was always the good one: the cadence
belongs to the caller, and a parameter is where that becomes expressible. Under that reading the flag
is not a debugging escape hatch that happens to be useful, it is the first instance of a rule R864
makes general.

**The recommendation is to keep it and to re-argue it on the cadence alone,** deleting the
measurement table below and the debugging framing with it. The alternative, dropping the flag and
shipping only the goal, is defensible and cheaper, and a reviewer who thinks one instance is too thin
a basis for a parameter should say so; the goal is the part that must land either way.

**One consequence that is not this item's to settle.** R857 depends on this item for a correctness
interaction: its rule says the capture-cadence refresh "stays unconditional and records a claim per
partition it refills", which is compatible with a declinable refresh only if a capture that declined
records no claims. If the flag is dropped, that interaction disappears and R857's dependency on this
item should be removed with it. R857 is another session's item and mid-review, so the direction is to
tell its author rather than to edit its front-matter from here.

## What is not being proposed

This is not a fix for the refresh being slow. That axis is closed: R876 states it plainly, the pass
is 43.0 seconds, and work proposing to make a registration's refresh cheaper is work against a
43-second pass. Nor is it the transaction-boundary change the dissolved cut-set item tested and
recorded as a dead end, which split the capture into one transaction per registration to give the
planner statistics mid-pass and came back with a ratio of 0.82 against it. This item takes no
position on how many transactions a full capture uses.

It is also not a mode anyone generates from, and the design below makes that structural rather than
advisory: the flag lives on a goal that reads nothing back, so there is no run in which a stale
target can reach a verdict or an emitted file.

## The shape

**A new goal, `mvn graphitron:capture`, that fills the store and stops.** It runs schema loading,
attribution, the classification walk and the capture loads, commits, and does nothing else: no
store-backed detections, no validation, no plan, no emission. One parameter,
`<skipMaterialize>` (user property `graphitron.skipMaterialize`, default `false`), ends the capture
before the refresh.

Putting the parameter on this goal alone is what keeps the design honest, and it is the reason this
is a goal rather than a flag on `validate`. Every reader downstream of capture spells canonical
target names, so a run that skipped the refresh and then read them would report from stale rows.
With the parameter on a goal that reads nothing, that combination does not exist to be refused: it
is unreachable rather than guarded.

The goal is also cheap, because the shape is already in the tree. `ValidateMojo` is thirty lines and
its whole body is `runGenerator(GraphQLRewriteGenerator::validate)`; `GraphQLRewriteGenerator.validate`
is ten. `CaptureMojo` is the same shape against a new `capture(...)` entry point, and
`AbstractRewriteMojo.runGenerator` already owns the context build, the codegen classloader scope and
the error wrapping.

## The measurement this was going to buy, and no longer does

This section used to hold a table of two commands differing in one flag, whose difference was the
refresh's contribution to a capture: "a figure nobody can state today". It is stated. The audit
prices every position of the pass and R848 reached Done without needing the subtraction, so the flag
now separates two numbers that are both known. Kept as a heading rather than deleted, so a reader who
remembers the argument can see that it was retired rather than quietly dropped.

## Three seams in the code

**0. The framing, so the parameter is not mistaken for an escape hatch.** The store has two kinds of
consumer and the register serves them differently. A reader that opens a store it did not write (the
language server, the MCP server) has to ask for current targets, which is what `refreshAll` exists
for and says so in its javadoc. A run that captures does not ask, because currency is implied by its
own write. `Materializations` already calls that difference "a real contract, not a convenience".
What the API does not support is a caller expressing it: `FactCapture.capture` welds the writer
cadence in, so no caller can decline. This parameter is the first place that becomes expressible,
and R864 makes it the general rule. Read `<skipMaterialize>` as naming a cadence, not as a debugging
switch that happens to be useful.

**1. The refresh becomes a decision the capture entry point carries.** `FactCapture.capture`'s
widest overload owns the transaction and ends it with `Materializations.refresh`, followed by
`Materializations.analyse` after the commit. Both move behind one new parameter. An enum
(`REFRESH` / `SKIP`) rather than a boolean, because the signature already carries a positional
`warm` boolean and a second one would make the call sites read `capture(dsl, true, false, ...)`.
The narrower overloads keep their signatures and pass `REFRESH`.

The refresh stays *inside* the transaction when it runs. Hoisting it out is a different change with
a measurement against it already recorded in the derived-read-cost audit, and this item takes no
position on it.

**2. The walk-side write has to come out of `detect`.** `FactCapture.detect` currently does two
unrelated things in one arm: it writes `walk_type_backing_class` from the run's `ClassifiedRun`, and
it runs the store-backed detections. A capture-only run wants the first and not the second, so
today "capture faithfully but detect nothing" is not reachable. Lifting `TypeBackingClassRows.write`
into its own step the caller sequences is a small change, and it is a piece of the edge R870
removes, so this item pays down part of that one rather than working around it.

A capture-only run therefore still walks and still writes those rows. That is deliberate: the
artifact this goal exists to produce is *the store a real capture writes*, minus the refresh, and a
store missing a relation would not answer the question anyone opens it for.

If R870 lands first this seam disappears rather than changing: it deletes `walk_type_backing_class`
outright, having established that the comparison the relation served reads the walk in memory and
needs no store-side copy, and `detect` becomes detections-only with nothing left to separate. R870
is small and unblocked, so that is the likely order. Plan this seam as work, but check whether it is
already done before starting it, and if it is, delete the seam rather than reinstating a write to
have something to lift.

**3. `packagesRequired()` returns `false`, as it does for `validate`.** The sentinel only substitutes
when the parameter is absent, so a consumer with `<jooqPackage>` configured gets a full catalog
crawl. A capture run that fell back on the sentinel writes no `sql_` rows at all, which makes the
store useless for timing views that join the catalog, so the goal logs a warning when it substitutes
rather than leaving that silent.

## The hazard, and what bounds it

A store left by a skip-materialize run holds current base relations and stale materialized targets
for the captured graph. Three facts bound how far that travels, and all three are properties of code
already in the tree rather than of anything this item adds:

* Every normal capture ends with `Materializations.refresh` for its graph unconditionally, so the
  next ordinary `generate` or `validate` of that graph restores its partition.
* `graphitron:dev` calls `Materializations.refreshAll` at session start, so an editor session
  opening such a store repairs it whole.
* The source stamps `ClasspathSources.commitStamps` writes do not change this. Warm-start
  reconciliation decides which *captured partitions* to rewrite; it does not gate the refresh, which
  runs whether or not anything was recaptured.

What is not bounded: a sibling graph's capture does not repair graph A's partition, so a store shared
by several graphs keeps graph A stale until something touches graph A. The goal says so on the way
out, in one line naming the graph and the repair, rather than leaving a user to discover it from a
wrong answer in the editor.

One thing this does *not* change: the four hand-written derivations (`ClassificationDomainCapture`,
`InputOccurrencePaths`, `TypeBackingRows`, `AuthoredClaimRejectionRows`) already run before the
refresh in both modes, so what they see is identical either way.

## How we will know it is delivered

The first criterion is the one that holds whether or not the flag survives the open question above.
The three after it are conditional on it surviving, and go with it if it does not.

* **`mvn graphitron:capture` on `graphitron-sakila-example` produces a store, and nothing else.** No
  emitted file, no validation report, no plan. Reopen the store and find graphs and fields non-zero,
  which is what says the goal produced the artifact rather than an empty file. This is the item's
  principal deliverable and the reason it still exists.
* A test captures a fixture graph twice, once per mode, and asserts the base relations hold
  identical rows while the registered targets are empty under `SKIP` and populated under `REFRESH`.
  This is the flag's central claim and the one a green build would otherwise not answer.
* A test asserts the skip-mode capture *commits*: reopen the store, count graphs and fields, and
  find them non-zero. What this pins is the transaction shape, that the transcription's durability
  does not depend on the refresh completing, which stands as a design property whatever the refresh
  costs.
* A test asserts that an ordinary capture following a skip-mode capture leaves the targets fully
  refreshed, which is the first bullet of the hazard bound above.


