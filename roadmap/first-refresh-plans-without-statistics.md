---
id: R953
title: "The first refresh on a store plans the recursive chain with no statistics, and one default selectivity costs the pass a hundredfold"
status: In Review
bucket: bug
priority: 1
theme: dev-loop
depends-on: []
created: 2026-09-16
last-updated: 2026-09-18
---

# The first refresh on a store plans the recursive chain with no statistics, and one default selectivity costs the pass a hundredfold

## Goal

A consumer's first `graphitron:generate` or `graphitron:dev` round on a fresh fact store plans its materialization refresh against the statistics that refresh needs, rather than against none at all. The refresh pass (the `INSERT ... SELECT` per `meta_materialize` registration that fills each derived target table) now reaches the planner with two facts it had been reaching it without. The cadence that analyses each target as it fills it is taken on the store that needs it, decided on the register's own state rather than defeated by an anchor row some earlier writer in the build left behind; and the one column whose default selectivity costs the pass a hundredfold, the partition dimension, states its value in the model instead of waiting for a measurement that cannot run inside a transaction. A first round is then bounded by what the rules cost, not by which cadence the store happened to fall into.

**What this does not claim, and why the claim was narrowed.** This item was filed against a number, the 2662 s a round on the `sis` consumer paid after R943 landed, and its goal promised that round would finish in the seconds the same statements take on a settled store. That number is not this item's to promise alone. R954 is open and holds the other half of the same pass, the *number* of evaluations where this item holds the cost of one, and the two multiply; a round on the shipped tree can be far better than 2662 s and still not be in seconds, with the residue belonging there. The goal above is what this item delivers and what the tree demonstrates. The wall clock of a consumer round is the two items' jointly, and R954's Done gate is where it is owed. The Tests section records why the re-measurement that would have settled it at this item's delivery was not obtainable.

## What was measured

Everything below was reproduced on a copy of the `sis` store on 2026-09-16, with the same H2 2.4.240 and jOOQ 3.20.11 the round uses; the figures for the live round come from an instrumented `generate -X` run the same morning.

The round's refresh runs inside the capture transaction. `FactCapture.capture` takes the analysing cadence (`Materializations.refreshAnalysing`: one committed transaction per registration, `ANALYZE TABLE` on each target as it is filled) only when `store_graph` is empty when the capture begins. But `OwnedGraphPartition.prepare`, called from the diagnostics writers, inserts the graph's anchor row earlier in the build, so the check finds a row on a brand-new store and the capture takes the in-transaction cadence (`Materializations.refresh`). In the instrumented round the anchor row lands at log line 1805 and the check runs at line 11004. Inside that transaction no `ANALYZE` has ever run on any target (it commits, so it cannot), and every target column carries H2's default selectivity of 50. Every slow log we have, R943's cold and warm controls included, is this path.

With `intent_field_reference_step_hop.graph_name` at the default, H2 prices the one-column foreign-key index on `graph_name` below the eight-column `ix_field_reference_step_hop_step` for the base arm of the recursive chain in `intent_field_reference_step_target` (the wide index pays `len - i` in `Index.getCostRangeIndex` for its unused columns, and default selectivity makes every equality look decisive). The lookup by `graph_name` alone returns every hop row of the graph for each type binding: 493 bindings times 11183 hops is 5.5 million row visits per evaluation of the view, 1.7 to 3 s against 0.06 s with the analysed value of 1. `intent_node_id_instruction_live` evaluates that view once per `table_node` row, 250 on `sis`, through the correlated `slot_table` CTE, and `intent_node_id_decode_hop_live` reads it the same way.

[cols="3,1"]
|===
| Measurement | `intent_node_id_instruction_live`

| Live round, in-transaction cadence, fresh store | 592 s
| Copy of the settled store, as is | 3.7 to 6 s
| Copy with every hop column reset to selectivity 50 | 455 s
| Copy with every column on every table reset to 50 | 522 s
| Copy with hop at 50 except `graph_name` at 1 | fast plan, 0.06 s per view evaluation
| Same statements in `refreshAll` after `Materializations.analyse`, same JVM (R943's dev-run3 log) | 3.9 s
|===

Plain `EXPLAIN` does not print a recursive CTE's plan, which is why the round's plan read identical to the copy's until `EXPLAIN ANALYZE` on the step-target view alone showed the index choice and the 5.5 million scan count. Refuted on copies, all 3 to 6 s: `OPTIMIZE_REUSE_RESULTS` on or off, base tables marked updated in the transaction, 280,000 uncommitted rows in the transaction, the view planned while the hop table was empty, a fresh boot with rows copied in and the analysing cadence, and 2x CPU oversubscription. The store-performance skill's per-relation method and a bisection over which table's statistics were reset found the column.

## Implementation

Both levers shipped. What each one is and why it is the shape it is now lives in
the code it landed as, which is where a reader meets it; what follows is where to
look and what the gate should know.

**Lever 1, the cadence is decided on the register, shipped at `1da1f07`.** `FactCapture.capture` had computed the cadence from
`!dsl.fetchExists(STORE_GRAPH)`, a proxy three unrelated writers of the anchor row
defeat, the build's own run-configuration capture among them, so every consumer
build took the in-transaction cadence on a store with no statistics anywhere.
`Materializations.analysingCadenceApplies` is the predicate now, over the
register's own state, and its javadoc carries the two conditions the cadence rests
on, the single witness for both, and what the wider predicate newly admits.
`RunStore`'s separate reading of the same row was named as the second instance and
deliberately left alone.

**Lever 2, the store declares its partition dimension's selectivity, shipped at
`cf5f6ff`.** `no.sikt.graphitron.model.catalog.GraphPartition` is the one home for
the partition column, the census of relations carrying it and the declared value;
`GraphitronModelStore.create` sweeps one `ALTER ... SELECTIVITY` per graph-keyed
base table where the DDL runs, and the DDL header states the rule where a reader of
a `CREATE TABLE` meets it. Scope is every graph-keyed base table rather than the
registered targets, for the reason "Other solutions" records. The lever falsified
`StoreStatistics.analysed`, which landed repaired in the same commit.

**Lever 3 is not this item's.** Registering `intent_field_reference_step_target` was
dropped from this plan at Spec; R954 phase 2 lands the same rows by a different
route, and "Relation to other items" records the split.

**Measurements re-established after R956 at `bb76f73`,** which converted the
relation lever 2 was measured on into two keyed arm tables under a union view;
`75dd92a` repaired a conflict marker that commit left in the fact-model page. "What
R956 changed under this item" below carries the reading.

**Sequencing, as it happened.** Lever 2 landed first and lever 1 second, which is
the order this plan proposed; the Spec gate considered splitting the two into
separate items at `35a745d` and did not.

**What the first Done gate sent back, and what the rework did.** Three findings,
recorded in full below with their resolutions. The two prose ones are fixed. The
one that matters is the first: the consumer re-measurement this section's Tests
asked for at delivery could not be taken from this repository, so the Goal is
narrowed to what the tree demonstrates and the wall-clock number is named as owed
at R954's gate. That is a change to what this item claims, and judging it is the
next gate's first job rather than a detail of this one.

## Tests

Four claims, each in the tier that can hold it, and none of them a wall clock: a tier that must not fail for being slow cannot hold a figure, which is `DerivedReadCostTest`'s rule and this item inherits it.

**The cadence is decided on the register's state, not on the anchor row.** The lever-1 deliverable, and the enforcer the invariant never had. A leg on `RefreshPrerequisiteStatisticsTest` driven through `FactCapture.capture` rather than through `Materializations` directly, on a store whose `store_graph` anchor was pre-written the way `ModelCapture.writeGraph` writes it, asserting the class's existing claim: every registration meets the targets its own rule reads analysed. No new instrument, the observation being `RefreshProgress`'s started event and `StoreStatistics.analysed` as the class already uses them, and the control is what the class's own "two legs, because one of them is the control" paragraph teaches: with the anchor pre-written this leg fails on today's predicate.

**The declaration is on every graph-keyed base table of a fresh store.** A gate over a store opened and never analysed: every base table with a `graph_name` column reports `SELECTIVITY 1` on that column in `INFORMATION_SCHEMA.COLUMNS`. Exact rather than approximate, and it fails loudly when a new graph-keyed table arrives outside the sweep.

**The set of registrations whose refresh plans need statistics shrinks, and the cold regime is redefined.** The acceptance evidence for lever 2, and it is an existing gate rather than a new one. `RefreshPlanStatisticsTest` pins `PLAN_DEPENDS_ON_STATISTICS` by equality so the set cannot grow or shrink unremarked, and its worked example is literally this item's defect: with statistics the read of `intent_field_reference_step_hop` seeks `IX_FIELD_REFERENCE_STEP_HOP_STEP` on seven columns, without them it seeks `CONSTRAINT_INDEX_98` on `GRAPH_NAME` alone. Lever 2 removes that choice, so the set shrinks and the test fails; the shrink is the deliverable and is expected up front rather than discovered. The `cold` regime moves with it: today it is `StoreStatistics.reset` and nothing, which after this lever models a store that can no longer exist, and what models a real cold store becomes reset followed by re-declaring the partition columns. Any sibling sharing that reset, `MaterializedRegistryFixture` included, moves the same way.

**The declaration is worth what it is claimed to be worth.** The figure behind the goal, and the only claim here that measures rather than asserting state. On a captured store put back to `SELECTIVITY 50` on every column and then given the partition-column declaration alone, reading `intent_node_id_instruction_live` visits within a stated factor of what it visits on a fully analysed store. The instrument is `EXPLAIN ANALYZE`'s summed `scanCount`, which `DerivedReadCostTest` already carries and which is a row count rather than a clock, so it reads the same on every machine. It is worth a test of its own rather than being left to the plan comparison above because `EXPLAIN` without `ANALYZE` does not print a recursive CTE's plan at all, which is what hid the index choice from every cheaper instrument until a scan count made it visible. The figure is measured at implementation and stated in the test, not guessed here.

A fifth thing is deliberately not tested, and a sixth turned out not to be obtainable. Nothing asserts the wall clock of a `sis` round, that store being a consumer's rather than the repository's. Beyond that, this section had asked for one recorded re-measurement on the consumer at delivery, reported at the Done gate rather than held by a test, and the first Done gate found it missing; the finding is below. It is not supplied, and the reason is that it cannot be from here. No `sis` store exists in this repository, the figures above were taken on a copy held by the session that took them, and that copy went with the session. Reaching one again means the consumer supplying a fresh copy, which is a thing to arrange rather than a step to run.

So the second arm of that finding is the one taken: the Goal above is narrowed to what this item delivers and what the four claims demonstrate, and the wall-clock number is named as jointly R954's and owed at that item's gate, which has a consumer measurement in its own plan. What is lost by narrowing is worth stating plainly rather than burying: nobody has yet watched a consumer round on the shipped tree, so the size of what these two levers buy at consumer scale is inferred from the mechanism and from R867's measurement of the same cadence substitution (6293 s against 90.8 s on a captured consumer store), not observed. A reviewer who thinks the item should not close without that observation should say so; the alternative is holding this item open on a dependency neither it nor the repository controls.

## What R956 changed under this item

R956 landed on trunk while this item's levers were being verified, and it converted `intent_field_reference_step_hop` from one table with a declared eight-column index into two keyed arm tables under a union view. That is the relation lever 2's measurement was taken on, so the fixture-scale figures moved and the reading is recorded here rather than left to be rediscovered.

On the single hop table, the decode-hop rule's read visited 10939 rows with the partition column unstated and 1113 with the declaration alone, which is what a fully analysed store visited. On the keyed arm tables the same read is 1499 bare against 1105 declared, with 1107 analysed. The arm keys lead with the coordinate the reader seeks on, so the planner now reaches a seekable ordering whether or not it has been told what `graph_name` holds, and what is left for the declaration to buy at this fixture size is the residue. Both levers still hold their own argument: a key on the target removes most of this cliff where the target has one, and the declaration remains the only statement of that column available to a pass that plans inside a transaction, where `ANALYZE` cannot run at all.

Two consequences for this item's acceptance evidence. `RefreshPlanStatisticsTest`'s pinned set is eight of twenty-four rather than the four this plan predicted: the declaration takes `intent_node_id_instruction_live` out, and three registrations join whose cold plans differ from their analysed ones on the declared regime as they did on the unstated one. And the consumer-scale figures in "What was measured" above were taken on the single-table shape, so the re-measurement this item's Tests section asks for at delivery is now the only evidence for what a `sis` round pays on the shape that ships.

## Reviewer findings

Done-gate round 1, withheld, and addressed in the rework commit that follows it; the resolution of each finding is recorded under it. The findings are kept rather than deleted so the next gate can judge the answers against what was asked.

Done-gate round 1, withheld. Both levers are implemented as this plan describes
them, and the implementation is not what sends this back. What sends it back is
question 2: the evidence this plan named for its own goal is not all in hand,
and the plan itself says so.

What the reviewer checked and found sound, so that the next pass does not
re-litigate it: `mvn install -Plocal-db` passes on the rebased tree. Lever 1 is
the predicate this plan specified, over the register rather than the anchor, with
`RunStore`'s reading deliberately untouched. Lever 2 is the one home, the boot
sweep, the DDL header rule and the `StoreStatistics.analysed` repair, scoped to
every graph-keyed base table rather than to the register. Lever 3 is dropped per
the supersession this body already records. `DerivedReadCostTest`'s four departed
rows left the got-cheaper way with their figures recorded in place, which is a
gate getting a fact rather than a gate getting weaker.

The reviewer also verified the lever-1 enforcer rather than taking its word:
reverting `FactCapture`'s selector to `!dsl.fetchExists(STORE_GRAPH)` and running
`RefreshPrerequisiteStatisticsTest` fails
`aCaptureBehindAPreWrittenAnchorMeetsThemAnalysed` on every dependent
registration, while the two legs that call `Materializations` directly still
pass. The control is real and it is the lever's deliverable, as this plan
claimed.

One thing worth recording because it strengthens lever 2 against the R956
question and is not stated above: `Materializations.analyse` walks the
registrations, so it reaches registered targets only. The graph-keyed base fact
tables the refresh reads inside the same transaction are never analysed on any
path, so the declaration is the only statement of `graph_name` those relations
ever carry, on a warm store as much as a cold one. That is a standing structural
benefit, independent of the fixture-scale figure R956 moved, and it is the
argument lever 2 should lead with rather than the ratio.

### 1. The goal's own number has no evidence on the shape that ships (question 2)

The Tests section ends by naming what demonstrates the goal: the four claims
above "plus one recorded re-measurement on the consumer at delivery, reported at
the Done gate rather than held by a test". The Done gate is this round, and no
re-measurement is recorded in the tree, in the body, or in any landing commit
message. "What R956 changed under this item" then raises the same obligation to
the only one there is: the consumer-scale figures in "What was measured" were
taken on the single-table shape, so that re-measurement "is now the only evidence
for what a `sis` round pays on the shape that ships". The delivery identified the
gap and shipped without either closing it or amending what the item claims.

This is not a request for ceremony. The goal paragraph leads with a number, 2662
s down to the seconds a settled store pays, and R954 is open and says the number
of evaluations is the other half of that same pass and that the two multiply. So
a round on the shipped tree could be much better than 2662 s and still nowhere
near seconds, with the residue belonging to R954. Nothing in the tree
distinguishes those two outcomes, and the difference is exactly what this item's
goal asserts. The four in-tree claims demonstrate the mechanism, which is real:
the cadence is taken on the store that needs it, and a test the reviewer
independently controlled holds it there. They do not demonstrate the outcome the
goal states.

What would satisfy it, either arm:

- Run the round on a copy of the consumer store on the shipped tree and record
  what the refresh pass costs, in this body, reported at the next Done gate as
  the Tests section asks.
- Or, if that store is no longer reachable to any session, say so plainly here
  and rewrite the goal paragraph to state the outcome the tree can demonstrate,
  naming the residue as R954's. That is a change to what the item claims to
  deliver, so it belongs in the body where the next gate reads it, not in a
  reviewer's note.


**Resolved by narrowing the claim, not by measuring.** The measurement cannot be taken from this repository and the Tests section now says so and why. The Goal is rewritten to the outcome this item delivers and the four claims demonstrate, with the wall-clock number named as jointly R954's and owed at that item's gate. The next gate's question on this finding is whether that narrowing is honest or whether it is a goal trimmed to fit what shipped; the case for it is that R954 genuinely holds the other multiplicand and the tree cannot separate the two, and the case against it is that this item was filed against the number and no longer promises it.

### 2. The retirement sweep left three live uses of the retired phrase (question 1)

This item declares `"a store that holds no graph"` retired everywhere as the
condition the analysing cadence turns on, with one deliberate survivor in
`Materializations.analysingCadenceApplies`' javadoc. The sweep covers `.adoc`
files and roadmap bodies. Three live uses remain, and they are live claims rather
than history:

- `docs/architecture/explanation/fact-model.adoc:308`, under the per-gatherer
  transaction-control rule: "One exception is already carved out for exactly
  this: a store holding no graph commits its facts and refreshes outside that
  transaction". Present tense, published, and false as of this item. It is on the
  same page the item edited, two hundred lines above the paragraph that was
  fixed.
- `roadmap/register-rules-become-owner-written-facts.md:261`: "a store holding no
  graph runs `refreshAnalysing` outside the transaction". R955 is `Ready`, so
  this is a premise its implementer will read and carry forward.
- The same file at line 399, naming the successor test's subject the same way.

`FactCapture.capture`'s inline comment quotes the phrase too, but as history and
in the same register as the sanctioned survivor; the reviewer reads that as
within the exemption rather than a fourth instance. Worth adding to the survivor
sentence when the body is next touched, so the next sweep does not re-raise it.


**Resolved.** `docs/architecture/explanation/fact-model.adoc` now reads "a capture into a store no registered target holds a row in" at the per-gatherer transaction-control rule, and R955's two premises are corrected the same way, minimally and without touching that item's plan. `FactCapture.capture`'s inline comment is added to the sanctioned survivors in Retired vocabulary, which is where the reviewer suggested it belonged.

### 3. The body still reads as an unexecuted plan

The Done gate's precondition is that the body reflects what shipped: phases
collapsed to one-line "shipped at `<sha>`" notes with the remaining work named.
The Implementation and Sequencing sections are unchanged forward-looking plan
prose, down to "The Spec gate may still prefer the split ... The gate decides",
a question answered at `35a745d` and settled by two commits landing in the order
this section proposed. Collapse both to what landed and where, and name the
re-measurement of finding 1 as the remaining work.


**Resolved.** Implementation is four one-line landing notes with their SHAs, Sequencing is folded into it as what happened rather than what to do, and the remaining work is named as finding 1 above.

## Retired vocabulary

Three names go, and the sweep at the Done gate is over prose as much as over code.

- **"a store that holds no graph"**, and its variants ("a store holding no graph", "a store that holds no graph at all"), as the condition the analysing refresh cadence turns on. What replaces it is *a store no registered target holds a row in*. Two deliberate survivors, both quoting the phrase to say what the predicate used to be and why the proxy broke, which is history rather than a live claim: `Materializations.analysingCadenceApplies`' javadoc, and the inline comment at `FactCapture.capture`'s selector. The first Done gate's sweep found three live uses that had been missed and are now fixed, in `docs/architecture/explanation/fact-model.adoc` under the per-gatherer transaction-control rule and twice in R955's body; a sweep that greps this file's own declaration will still hit this section, which declares the retirement rather than asserting the mechanism.
- **"first graph" / "first-graph refresh cadence" / the local `firstGraph`**, as a name for that cadence or for the capture that takes it. What replaces it is *the analysing refresh cadence*, and the local is `analysingCadence`.
- **`Materializations.graphKeyedRelations`**, the private census of relations carrying `graph_name`. It is `no.sikt.graphitron.model.catalog.GraphPartition.keyedRelations` now, beside `keyedBaseTables`, `COLUMN` and `DECLARED_SELECTIVITY`, which is the one home this item's lever 2 gives the predicate.

## Other solutions we've considered

Running `ANALYZE` inside the capture transaction: H2's `ANALYZE` commits, and a commit between the pass's delete and its inserts publishes an emptied partition, which the one-transaction contract in `FactCapture` exists to prevent. Committing the capture before the refresh on every path: this is what the analysing cadence already does for the first graph, and lever 1 is the smaller change that routes the first fill there. Dropping the foreign-key index on `graph_name`: it is the constraint's own index and every target carries one; removing it is not a local change. Disabling H2's result reuse or changing lock or isolation settings: measured, no effect.

Reordering `ix_field_reference_step_hop_step` so the base arm's equalities form a prefix was considered and is not sufficient alone: under default statistics H2's formula still prices the one-column index lower, so the index shape cannot fix the trap without the statistics.

Repairing the anchor ordering instead of lever 1, so that no writer mints a `store_graph` row before a capture's check: it restores the proxy rather than removing it, and lever 1 argues why above. The short form is that three writers reach the anchor for three different reasons and each has a reason to be where it is, so making the cadence's correctness depend on all three staying ordered is a standing invariant with no enforcer, where the register's own state is a fact already in hand.

Declaring the selectivity on the 23 registered targets alone, which is what this item filed, and declaring it as 226 `ALTER` lines in `graphitron-model.sql` beside each table rather than as a sweep: both are cheaper diffs and both are correct as far as they go. Lever 2 takes neither, and says why at each of the two choices. The register-scoped form keys a partition fact on a consumer's question and evaporates as R954 and R955 empty the register; the hand-written form leaves an invariant that needs a gate of its own where the sweep leaves one true by construction.

## Relation to other items

**R954** diagnoses the same pass from the other side and the two compose rather than overlap: this item is the cost of *one* evaluation, that one is the *number* of evaluations, and they multiply, because the walks it converts join `intent_field_reference_step_hop` in both the anchor and the step. Its phase 3 priced this item's cliff on a `sis` store and handed the figure over rather than keeping it, 135x on `intent_node_id_instruction_live` and 315x on `intent_node_id_decode_hop_live`, and its body records that all 23 `ANALYZE TABLE` statements of the failing round ran after the pass that read the tables they analyse.

**Lever 3 of this item's filing is superseded by R954 phase 2 and is dropped from this plan.** Registering `intent_field_reference_step_target` and converting the walk to owner-written rows land the same rows; the difference is whether the register schedules the refill or the gatherer writes it, and R876's doctrine prefers the owner. R954 states the supersession and asked that this body be amended when next touched, which this Spec is. No `depends-on:` edge either way: the two levers that remain here are orthogonal to that item, lever 2 stays true of a stage-written table and R954 says so, and an implementer who reaches phase 2 first and finds a registration already landed takes it as that phase's fallback.

**R955** converts the register's remaining registrations on the same doctrine and names lever 2 as the cheap floor under both cadences, landing per table as this item specifies. Orthogonal, no edge.

## Provenance

R943 named the three expensive registrations and shipped four shape levers that took the `sis` pass from 5424 s to 2662 s, and recorded that its offline instrument under-reproduced the wall clock sixty to eighty fold. Its statistics control compared a cold and a warm round and found them alike; both were in-transaction passes on unanalysed stores, so the control could not see the variable it was built to test. The investigation that filed this item started from that residual, established on copies that no transaction state reproduced it, and found the column by resetting statistics table by table until the copy matched the round.
