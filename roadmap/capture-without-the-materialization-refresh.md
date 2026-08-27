---
id: R865
title: "A capture cannot be run without the materialization refresh, so a schema whose refresh never returns leaves no store to debug"
status: Spec
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# A capture cannot be run without the materialization refresh, so a schema whose refresh never returns leaves no store to debug

Every capture pays the materialization refresh, and no caller can decline it. `FactCapture.capture`
ends its one transaction with `Materializations.refresh`, then calls `Materializations.analyse`
straight after the commit. There is no parameter, no property and no goal that stops before either.

## Vocabulary

A **registration** is a row of `meta_materialize`: a derivation kept as a view under a `_live` name,
plus a table of the same shape under the canonical name that readers spell. The **refresh** is the
pass that empties and refills those tables, and today it runs inside the capture's own transaction,
after the captured rows are flushed.

## Why this is worse than a slow step

Because the refresh is inside the capture's transaction, a capture that is killed during the refresh
commits **nothing**. Not the refreshed tables, which is expected, but also not the SDL rows, not the
catalog rows, not the classpath census, and not the capture-cadence derivations. A run that spent an
hour transcribing a schema leaves a store file holding zero graphs and zero fields.

That is not hypothetical. The investigation in R856 surveyed fifteen store files a real consumer
schema had left on disk and found that every one of them at the current registration count held zero
graphs and zero fields: not one had ever been captured into. The only populated stores were from an
older DDL with fewer registrations.

So the tooling has a hole exactly where it is needed most. On the one schema where somebody most
wants to look at the captured facts, the captured facts are the thing that cannot be obtained, and
the reason is that they are hostage to a later step that does not return. Timing the refresh's
statements against a populated store, which is how R856 and R848 both do their work, requires a
populated store that nobody on that schema can produce.

## What changes when this lands

**A developer gets a captured store out of a schema whose refresh does not return.** That is the
whole point, and today it is impossible: the store file such a run leaves behind holds zero graphs
and zero fields. With this in, `mvn graphitron:capture -Dgraphitron.skipMaterialize=true` returns and
leaves a store that can be opened, queried, and timed against.

**A figure the tree cannot state today becomes a subtraction.** Running the same goal with and
without the flag separates what transcription costs from what the register costs. R848 needs exactly
that number to say whether twenty registrations earn their refresh, and R856 needs a populated
consumer store to time its suspects against rather than a fixture.

**And `graphitron` gains a goal whose job is to fill the store,** which is the first half of the
shape R864 is heading for: capture produces a store, the generator consumes one. This item does not
deliver that inversion and does not depend on it, but it puts the producing half on the command line
where a person can reach it.

The default path does not change. A capture that is not asked to skip the refresh runs it exactly as
it does now, in the same transaction, with the same rows at the end.

## What is not being proposed

This is not a fix for the refresh being slow, and it is not the transaction-boundary change R856
tested and recorded as a dead end. That arm split the capture into one transaction per registration
to give the planner statistics mid-pass, and it came back with a ratio of 0.82 against it. This item
takes no position on how many transactions a full capture uses. It asks for the ability to not run
the refresh at all.

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

## The measurement this buys

Two runs, differing in one flag, over the same schema:

| command | what it costs |
|---|---|
| `mvn graphitron:capture` | transcription plus the whole register |
| `mvn graphitron:capture -Dgraphitron.skipMaterialize=true` | transcription alone |

The difference is the refresh's contribution to a capture, which is a figure nobody can state today
and which R848 needs in order to say whether the register earns its keep. The second command is also
the one that terminates on a schema where the first does not, which is the debugging half.

## Three seams in the code

**1. The refresh becomes a decision the capture entry point carries.** `FactCapture.capture`'s
widest overload owns the transaction and ends it with `Materializations.refresh`, followed by
`Materializations.analyse` after the commit. Both move behind one new parameter. An enum
(`REFRESH` / `SKIP`) rather than a boolean, because the signature already carries a positional
`warm` boolean and a second one would make the call sites read `capture(dsl, true, false, ...)`.
The narrower overloads keep their signatures and pass `REFRESH`.

The refresh stays *inside* the transaction when it runs. Hoisting it out is a different change with
a measurement against it already recorded in R856, and this item takes no position on it.

**2. The walk-side write has to come out of `detect`.** `FactCapture.detect` currently does two
unrelated things in one arm: it writes `walk_type_backing_class` from the run's `ClassifiedRun`, and
it runs the store-backed detections. A capture-only run wants the first and not the second, so
today "capture faithfully but detect nothing" is not reachable. Lifting `TypeBackingClassRows.write`
into its own step the caller sequences is a small change, and it is the same lift R864 names as its
blocker, so this item pays down a piece of that one rather than working around it.

A capture-only run therefore still walks and still writes those rows. That is deliberate: the
artifact this goal exists to produce is *the store a real capture writes*, minus the refresh, and a
store missing a relation would not answer the question anyone opens it for.

If R864 lands first this seam disappears rather than changing: it deletes `walk_type_backing_class`
outright, having established that the comparison the relation served reads the walk in memory and
needs no store-side copy, and `detect` becomes detections-only with nothing left to separate. Plan
this seam as work, but check whether it is already done before starting it.

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

* A test captures a fixture graph twice, once per mode, and asserts the base relations hold
  identical rows while the registered targets are empty under `SKIP` and populated under `REFRESH`.
  This is the item's central claim and the one a green build would otherwise not answer.
* A test asserts the skip-mode capture *commits*: reopen the store, count graphs and fields, and
  find them non-zero. This is the defect the item was filed for, and the population fact in R856 is
  what it pins against.
* A test asserts that an ordinary capture following a skip-mode capture leaves the targets fully
  refreshed, which is the first bullet of the hazard bound above.
* `mvn graphitron:capture` on `graphitron-sakila-example` produces a store, and the two commands in
  the measurement table above produce a stated difference recorded on this item before it moves to
  Done.


