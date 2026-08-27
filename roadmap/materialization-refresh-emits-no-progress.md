---
id: R855
title: "The materialization refresh emits nothing, so a hang inside it is anonymous"
status: In Progress
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# The materialization refresh emits nothing, so a hang inside it is anonymous

`Materializations.refresh` walks the registered materializations in dependency order and issues a
`DELETE` and an `INSERT ... SELECT` per registration. It logs nothing at any point. On a schema
where one of those statements is slow, the console goes silent after whatever line happened to print
last, and stays silent for as long as the statement runs.

## Vocabulary

A **registration** is a row of `meta_materialize`: a rule that stays written in a view under a
`_live` name, and a table of the same shape under the canonical name every reader spells. The
**refresh** is the pass that refills those tables, once per capture, and it runs inside the capture's
own transaction. The **refresh order** is derived from the stored view definitions, so the pass is a
flat sequence of statements whose order is a property of the schema rather than of the caller.

## What this cost, concretely

Two Claude sessions spent an afternoon locating a refresh that did not finish on a consumer schema.
What it took, in order: three thread dumps of two live JVMs to reach
`Materializations.refreshPartition`; four copies of on-disk store files out of the per-user cache;
a per-relation timing harness over one of them; and a hand-reproduced Kahn sort over
`meta_materialize_dependency` to turn the stack frame into a position in the sequence. What came out
of all of that was a price list, a two-way suspicion, and an argument about whether the suspicion
followed from the prices at all. It never became a name, and the sibling item now carries that
question open because no instrument in the tree can close it.

One log line per registration, printed before its `INSERT` is issued, would have named the relation
outright in the first thirty seconds. Every wrong turn either session took, and there were several
recorded in the sibling item, was a substitute for a fact the pass already knows and does not say.

The narrower cost is ordinary rather than dramatic and it is the reason to do this even if no
relation is ever pathological again: a person watching a build has no way to tell a refresh that is
working from one that is stuck, and no way to tell which of twenty evaluations a slow capture is
spending its time in.

## Why the obvious shape of the fix is wrong

Timing each registration and logging the result after it returns is the shape a reader reaches for
first, and it fails at exactly the moment it is needed. A statement that does not return emits
nothing under that shape, so a healthy store would look instrumented and a stuck one would look
identical to today.

The name has to be emitted **before** the statement is issued. What may be emitted afterwards is the
duration, which is the ordinary-case value and the part that makes a slow-but-finishing pass legible.
The difference is the whole value of the change on the case that motivated it, so the plan below says
which of the two it is doing at every emission point, and the first test listed is the one that fails
if someone later swaps them.

## Who the callers actually are

The Backlog draft of this item guessed that a language server was among the callers and that this
made the destination hard to choose. It is not one. Two call sites reach the refresh in the whole
reactor, and both already own a place to print:

- `FactCapture.capture` calls `Materializations.refresh` inside the capture transaction. That class
  already holds an slf4j logger and already reports through it, `LOG.warn(DEMOTED_TO_MEMORY)` being
  the visible case. This is the call site the motivating failure sat in.
- `DevMojo.execute` calls `Materializations.refreshAll` on the session store, and a mojo has
  `getLog()`.

The language server and the MCP server open stores to read them and never refresh one; the dev
session's refresh happens in the mojo, before either is wired. So the destination question is
narrower than the draft made it: what the refresh needs is a way to say what it is doing, not a
logging framework of its own.

## The decision: the refresh reports to an observer the caller supplies

`Materializations` stays free of any logging dependency and gains a small interface,
`RefreshProgress`, that the caller passes in. The refresh emits events; the caller decides what
becomes of them. Both call sites above map events onto the surface they already have, in one place
each.

Three reasons, in the order they decided it.

**A test can assert an event sequence, and cannot easily assert a log.** The one property this whole
item exists to hold is that the name is emitted before the statement is issued, and that property is
invisible to every ordinary test: a refresh instrumented the wrong way passes the same green build.
An observer makes the invariant a list a test reads. A logger would make it a log-capturing appender,
and `graphitron-model`'s test scope carries no logging backend to capture with. The gate is the point
of the change, so the shape that makes the gate cheap wins.

**The module's dependency shape is deliberate and is already load-bearing in prose.**
`graphitron-model` carries jOOQ and H2 and nothing else, and `Materializations.analyse` already
explains a design choice by that fact: it returns a count rather than logging its refusals because
"this module carries jOOQ and H2 and no logging framework". Adding slf4j here would make that
paragraph false and would put the module's one logging dependency in the tree for two call sites that
each already have one.

**Rows in the store are ruled out by the failing case.** The refresh runs inside capture's
transaction, so progress written as facts is invisible until the transaction commits, and the run
this item is about never commits. Writing them on a second connection instead would put a second
writer against a store the capture is holding, for output whose whole purpose is to be readable while
the holder is stuck.

## What the refresh will say

Two tiers, and what separates them is how often a line is worth printing.

**The pass boundary, printed by default.** One line before the first statement of the pass, naming
how many registrations are about to run and for which graph, and one line when the pass returns,
carrying its total duration. A build that prints the first and never the second is stuck inside the
refresh, which is exactly the fact two sessions established by taking three thread dumps. Two lines
per pass is affordable at every cadence the refresh runs on.

**The registration, printed on request.** One line before each registration's statements, naming the
relation, its position in the sequence and the scope being refreshed, and one line after they return,
carrying the two statement durations and the two row counts. Twenty registrations ship today and a
dev round refreshes on every save, so forty lines a save is not a default anybody would keep. A
person who has already killed a run can re-run it with this tier on and have the name within seconds,
which is the trade this item is buying.

Both tiers obey the rule stated above: the name goes out before the statement, the duration after it.
Concretely, the started event is emitted before the `DELETE`, not between the `DELETE` and the
`INSERT`, so a refresh stuck in either statement has already named itself. The one query that
precedes the started event is the `INFORMATION_SCHEMA` probe deciding which of the two refresh shapes
applies, which is a metadata read against a catalog view and is not a candidate for the failure this
instruments.

The tiers are the default rendering rather than a rule the interface enforces: `RefreshProgress`
carries events, and a caller that wants both tiers at one level is free to say so.

## Slow-relation warning: decided against

No threshold, no warning tag, and the reason is that no honest number exists to set it to. The
sibling item's price list has a registration legitimately costing 47 seconds on a working schema, so
a threshold low enough to fire on the pathological case fires on every capture of a large consumer
schema, and one high enough to stay quiet there says nothing about a statement that never returns at
all. The durations are in the record already, and ranking them is the reader's job for the ten
seconds a year they need to.

Cost regressions have a home, and it is a build-time gate on a fixture rather than a runtime warning
on a consumer's console: `DerivedReadCostTest` and `MaterializeRegistryGateTest` are where a
registration getting dearer is supposed to be caught.

## Implementation

`graphitron-model/src/main/java/no/sikt/graphitron/model/derive/RefreshProgress.java`, new. One
functional interface taking a sealed `Event`, so a caller mapping events to a surface writes one
switch and the compiler tells them when an event kind is added. The events:

- `PassStarted(int registrations, List<String> graphs)`, before the pass's first statement. Capture's
  cadence passes the one graph it is refreshing; `refreshAll` passes the store's graphs.
- `RegistrationStarted(Registration registration, int position, int total, String graph)`, before the
  registration's `DELETE`. A whole-relation refresh carries no graph; spell the absence however this
  module's records already spell an absent component.
- `RegistrationFinished(Registration registration, long deleteNanos, long insertNanos, int
  rowsDeleted, int rowsInserted)`, after the `INSERT` returns. Both row counts are the return values
  of the two `execute()` calls, so they cost nothing and they are what explains a slow registration
  once it finishes. The durations are split because the two statements can fail differently and
  nothing else in the record would distinguish them.
- `PassFinished(long nanos)`, after the last registration returns.

Two factories on the interface. `RefreshProgress.none()` is the no-op every existing caller gets, and
`RefreshProgress.lines(Consumer<String> pass, Consumer<String> registration)` renders each event to
one line and hands it to the tier's consumer, so the wording lives in one place and each caller
supplies two method references. An observer that throws is a programming error and propagates; this
seam has none of `analyse`'s best-effort posture, and containing an exception here would hide a
broken caller behind a silent refresh.

`Materializations`. New overloads `refresh(DSLContext, String, RefreshProgress)` and
`refreshAll(DSLContext, RefreshProgress)`; the existing two-argument and one-argument forms delegate
with `none()`, so no test caller churns. Thread the observer and the position through the private
`refresh`, `refreshPartition` and `refreshWhole`, time each statement with `System.nanoTime()`, and
keep the emission points where the rule above puts them. The class javadoc gains a paragraph on what
the pass reports and why the name precedes the statement, since a future reader collapsing the two
events into one after-line is the regression the test below exists to catch.

`FactCapture.capture` passes `RefreshProgress.lines(LOG::info, LOG::debug)`. The pass boundary at
info is two lines per capture, and the per-registration tier arrives with `mvn -X`. Check at
implementation that slf4j debug from this module does reach the Maven console under `-X`, since the
whole recovery recipe rests on it; if it does not, the fallback is an enabling system property in the
shape of `LspTrace.ENABLE_PROPERTY` rather than raising the tier to info.

`DevMojo` passes `RefreshProgress.lines` over `getLog()::info` and `getLog()::debug` at its
`refreshAll` call.

## Tests

`MaterializationProgressTest`, new, in `graphitron-model/src/test`, beside `MaterializationOrderTest`
and on the same scratch-store fixture: that test already creates ordinary tables and views and
registers them, which is all this needs.

- **The name precedes the statement.** A registration whose `INSERT` cannot succeed, a view yielding
  `NULL` into a `NOT NULL` target column, leaves a `RegistrationStarted` for that registration and no
  `RegistrationFinished`, and the `DataAccessException` propagates. This is the one case the item
  exists for and the only test that fails if someone moves the emission after the call.
- **Sequence and pairing.** On a healthy scratch store the recorded events are `PassStarted`, then
  each registration's started event immediately followed by its own finished event, in
  `refreshOrder`'s order, then `PassFinished`. The order fixture's dependent-before-prerequisite
  naming carries over, so a sequence read off the census instead of the refresh order fails here.
- **Both shapes.** A graph-keyed registration reports the graph it was refreshed for; a graph-free
  one reports none. Under `refreshAll` on a store holding two graphs, the graph-keyed registration
  reports one started event per graph and the graph-free one reports a single event.
- **The counts are the statements' own.** `rowsInserted` equals the source view's row count and
  `rowsDeleted` equals what the target held before the pass.
- **The silent default.** The existing `refresh(dsl, graphName)` and `refreshAll(dsl)` overloads
  still refresh and observe nothing.

No new tier and no execution-tier work: this is a unit-tier change on a module test.

## Console output, first-client check

Default, on a capture of a graph named `orders`:

```
[INFO] graphitron: refreshing 20 materializations for graph 'orders'
[INFO] graphitron: materialization refresh done in 3.4 s
```

With `-X`, per registration, between those two lines:

```
[DEBUG] graphitron:  4/20 intent_field_reference_step_hop_live -> intent_field_reference_step_hop, graph 'orders'
[DEBUG] graphitron:  4/20 done in 3.1 s, deleted 812 rows in 4 ms, inserted 812 rows in 3.1 s
```

A run that stops after the first line is inside the refresh. A run that stops after a `4/20` line is
inside that registration, which is the sentence the sibling item currently cannot write.

## Documentation

- `docs/architecture/explanation/fact-model.adoc`, in the materializer material: a short paragraph
  saying the refresh reports its pass and its registrations, and why the name precedes the statement
  rather than following it.
- `docs/architecture/how-to/dev-loop-internals.adoc`: the recovery recipe, which is one re-run with
  `-X` and a grep for the registration line.
- `.claude/skills/store-performance/SKILL.md`, step 1's evidence list. It currently offers two ways to
  make a non-returning statement name itself, interrupting the build and reading jOOQ's DEBUG log,
  and both are downstream of knowing the refresh is where you are. Add the pass line as the cheapest
  entry on that list, and say that a per-registration name is one re-run away, so this class of hang
  does not need a thread dump.

## Out of scope

Making anything faster. Changing the refresh order, either refresh shape, or the transaction the
capture holds. Instrumenting `analyse`, the hand-written derivations that run beside the registered
refresh, or any other phase of capture. Any threshold, warning, or configuration surface beyond the
fallback property named above, which ships only if the `-X` check fails.

## Related

The sibling item on the mutation-payload refresh is the failure this one is the instrument for, and
it should be read first for the measurements; it should also be read for what it says about reading
durations, since this item adds a source of them. R857 says a dev start evaluates the whole register
twice, and this instrument makes that claim visible in a console rather than argued from code. R848
asks whether the register's shape is right at all; this item takes the register as given and only
asks it to say what it is doing.
