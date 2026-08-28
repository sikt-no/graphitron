---
id: R875
title: "The payload-pair registrations are priced only on a 928-field fixture, and the schema they were cut for has never been captured against them"
status: Backlog
bucket: bug
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-08-28
last-updated: 2026-08-28
---

# The payload-pair registrations are priced only on a 928-field fixture, and the schema they were cut for has never been captured against them

Two registrations landed on 2026-08-28 to cut the cost of the two most expensive statements in the
materialization refresh. Every figure that justifies them was taken on the `graphitron-sakila-example`
fixture, 302 types and 928 fields. The schema they were cut for is an internal consumer schema at Sikt
of about 2,300 types and 8,400 fields, and no capture of it has ever been taken with those two
registrations in the DDL. So the fix is priced and its target is unmeasured, and this item is the
measurement.

## Vocabulary

A **registration** is a row of `meta_materialize`: a derivation kept in a view under a `_live` name,
and a table of the same shape under the canonical name that every reader spells. The **refresh**
refills those tables once per capture, walking them in a dependency order derived from the stored view
definitions. A **capture** is what `mvn graphitron:validate`, `mvn graphitron:dev` and
`mvn graphitron:capture` all do first: read the schema and the catalog, write the facts, then run the
refresh. Both language-server and MCP port binds in `graphitron:dev` sit behind the capture, so a
capture that does not return is a capture that leaves a developer with no tooling at all.

## What landed, and what it is worth on the fixture

`intent_node_id_decode_column` and `intent_input_field_carrier_role` were registered, in that order
and priced one at a time. Both are read by the two statements that dominate the refresh,
`intent_mutation_payload_column_live` and `intent_mutation_payload_refusal_live`, and the mechanism
that made them expensive is that H2 re-evaluates an unregistered view once per driving row of a
correlated term. Measured against a store captured from the fixture, result reuse off, three runs
apiece:

[cols="3,2,2"]
|===
| statement | both rules on demand | both rules registered

| `intent_mutation_payload_column_live` | 851 / 826 / 827 ms | about 27 ms
| `intent_mutation_payload_refusal_live` | 69 / 73 / 82 ms | 10 / 9 / 12 ms
|===

The two registrations add about 67 ms of refresh per graph between them, which is one evaluation of
each source view. Every reader of either target improved and none got worse, which is what
`DerivedReadCostTest` holds, and four of that gate's recorded regressions left with the change.

## Why the fixture figure does not settle it

The fixture holds the `@mutation` payload surface fixed at three fields. That surface is the driving
population of both expensive statements, so the term the consumer schema grows is exactly the term
this fixture holds constant. Two factors multiply in the mechanism and both grow with the schema: the
number of driving rows, and the per-evaluation cost of the aggregate that no predicate can restrict.
A product of two growing terms is what turns a second into an hour, and no fixture in this repository
exercises both. Nothing in the fixture tables licenses an exponent, and no consumer-scale figure may
be extrapolated from them.

## What is known at consumer scale, and what it says

One capture of that schema has completed and been measured, on 2026-08-27, against a DDL that
predates these two registrations. The figures are in R867, which took them for a different arm:
the whole refresh cost four hours and nineteen minutes, and refresh positions 1 to 16 of that store
account for 6293 seconds of it. Two readings follow, and the second is this item's whole reason to
exist.

The first is a correction. Before that measurement the working belief was that no capture of this
schema had ever finished at the current registration count, on a survey of fifteen store files that
found every one at 16 registrations or more holding zero graphs and zero fields. A capture does
finish. It costs over four hours, so in practice everybody kills it, which is why the survey found
what it found, and "never finishes" and "nobody waits four hours" are different claims with different
fixes.

The second is that the largest measured cost on that store is not the mechanism these two
registrations address. R867 attributes 69-fold on the measured prefix to a separate defect, that a
cold capture's refresh plans with no statistics on the registered targets it reads, and the three
dearest positions it prices are disjoint from this pair. What the prefix does not cover is the tail:
6293 seconds of a four-hour-nineteen-minute refresh leaves the positions past 16 unmeasured on that
store, and this pair's two statements are among them. That subtraction is arithmetic across two of
R867's own figures rather than a measurement anybody took, and it is stated as the reason to take one
rather than as a result.

## What this item does

**One instrumented capture of that schema on the DDL the tree ships, and the figure recorded.** That
is the whole of it. There is no code to write unless the capture says there is.

Two things make the run reportable rather than an hour of silence, and both are already in the tree:

- **Take it under `mvn -X`.** `Materializations.refresh` reports to a `RefreshProgress` and
  `FactCapture.capture` supplies one. The pass boundary prints at the info tier, but the
  per-registration line is at the debug tier, so a default-verbosity run reports that a refresh
  started and nothing else. A second attempt at a four-hour run for want of a flag is the avoidable
  failure here.
- **Pin the store with `-Dgraphitron.store.directory`,** so the file is findable afterwards and can be
  timed against relation by relation.

The registration's name is emitted *before* its `DELETE` is issued, which is the ordering
`Materializations`' javadoc and `docs/architecture/explanation/fact-model.adoc` both state as the
instrument's whole point: the registration that never returns is the one that gets named. So this item
does not need a completion to report. It is discharged the moment the run either finishes with a
duration or has sat inside one named registration for longer than every earlier registration's lines
put together.

A run that is stopped costs what every kill on this schema costs and nothing more. The refresh is
inside the capture's transaction, so an interrupted run commits nothing and the next run starts as
cold as the last; one measured that way held 67 rows in a 124 MB file. That is a reason not to make a
habit of it, not a reason to watch this one to the end.

**This step needs the machine that holds the consumer schema.** No session working from this
repository alone can take it, and that is why this is an item rather than a test.

## The pickup gate: after the cold-refresh split

Do not take this measurement before R867 lands. Its own argument for going first is the reason: every
optimisation decision the fact model has taken so far, these two registrations included, was measured
against a settled store whose statistics were current, and the capture refresh runs with none. On the
sis consumer store that difference is 69-fold on the measured prefix. So a capture taken now prices
this pair in a regime that is about to change, and the figure would have to be re-taken afterwards
anyway. R867 says the same thing from its side.

Two consequences worth stating, so the gate is not read as a reason to defer indefinitely. If R867
lands and a capture of that schema then completes in a workable time, this item's measurement is the
figure that says so and it closes with that figure recorded. And if the capture still costs hours
after R867, the per-registration line names the position it sits in, and that name is the first
consumer-scale evidence anybody has about which half of the register the residue belongs to.

## The two outcomes

- **The capture completes in a workable time.** Record the duration and the per-registration
  breakdown, add the two figures to the registrations' own `reason` rows in `meta_materialize`, and
  close the item. The pair's fixture case is then confirmed at scale rather than merely unrefuted.
- **The capture still does not complete, or completes at a cost nobody will pay.** Then the named
  registration is the finding. If it names one of this pair, the mechanism holds at scale and the
  lever was too small, which puts the next rung in play: the register's shape, which R848 argues, or
  the narrower registration of the fold that R856 named and declined, promoting
  `intent_node_id_decode_column_live`'s `lifted` term from a local CTE to a named relation first. If
  it names something else, that is a new localisation and a new item.

## What this item does not do

It does not build a consumer-scale fixture. Nothing in this repository captures a schema of that size,
and a fixture that did would be a wall-clock gate, which the build-guardrail item is the place for.
The consumer-scale result stays a figure recorded in prose, in this item while it lives and in the
registrations' `reason` rows or `roadmap/changelog.md` afterwards.

It also does not re-open the choice of lever. The two registrations are on the tree, they are
defensible on their own numbers, every gate that governs a registration admits them, and this item is
a measurement of what they were worth rather than a review of whether they should have landed.

## Tests

None, and that is the point of the item rather than a gap in it. The three gates that govern these two
registrations are already in the tree and already green:
`MaterializeRegistryGateTest.everyTargetIsIndexedOrStatesWhyNot` and its sibling
`everyIndexOnATargetStatesItsReader` hold the index decisions,
`RefreshPlanStatisticsTest.PLAN_DEPENDS_ON_STATISTICS` pins which registrations plan differently cold,
and `DerivedReadCostTest` refuses a registration that costs another reader more than it saves. What
this item produces is a number and, on the second outcome, a finding.

## Related

R856 is where the two registrations were designed, measured and landed, and it carries the full
investigation: the localisation, the per-driving-row measurement, the snapshot control, and the
transaction-boundary arm that was set down. That file dies when R856 reaches Done, which is why the
measurement it still owed lives here instead.

R867 is the pickup gate above and the source of the only consumer-scale figures anybody has. R848 asks
whether the register's shape is right at all and is where a twenty-third registration would be argued.
R865 would let a capture skip the refresh, which is the cheapest route to a populated consumer store
to time relations against and would make this measurement easier to take. R841 asks the same
fixture-versus-real question about the same pair of statements from the refresh side, and its first
step is a figure this run would supply.
