---
id: R953
title: "The first refresh on a store plans the recursive chain with no statistics, and one default selectivity costs the pass a hundredfold"
status: Spec
bucket: bug
priority: 1
theme: dev-loop
depends-on: []
created: 2026-09-16
last-updated: 2026-09-17
---

# The first refresh on a store plans the recursive chain with no statistics, and one default selectivity costs the pass a hundredfold

## Goal

A consumer's first `graphitron:generate` or `graphitron:dev` round on a fresh fact store finishes its materialization refresh in the seconds the same statements take on a settled store, instead of the 2662 s a round on the `sis` consumer paid after R943 landed. The refresh pass (the `INSERT ... SELECT` per `meta_materialize` registration that fills each derived target table) is planned with the statistics it needs before it runs: the cadence that analyses each target as it fills it is taken on the store that needs it, rather than defeated by an anchor row some earlier writer in the build left behind, and the one column whose default selectivity costs the pass a hundredfold states its value in the model instead of waiting for a measurement that cannot run inside a transaction. A first round is then bounded by what the rules cost, not by which cadence the store happened to fall into.

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

Two levers, and the item ships both. They close different halves and neither subsumes the other: lever 1 restores the cadence that gives every registration statistics on the relations it reads, and lever 2 states the one fact no cadence can supply to a pass that has to plan inside a transaction. The item's third lever, registering `intent_field_reference_step_target`, is not planned here; R954 phase 2 lands the same rows by a different route, and "Relation to other items" below records that split.

### Lever 1: decide the cadence on the fact it depends on

`FactCapture.capture` computes `firstGraph` as `!dsl.fetchExists(STORE_GRAPH)` before it opens its load transaction, and that condition is a proxy for the two facts the cadence actually turns on, defeated by writers it does not know about. Three writers reach the anchor row ahead of a capture. `ModelCapture.writeGraph` is the build path's: `AbstractRewriteMojo.captureModel` writes the run-configuration families through `CapturePort.captureModel` before it hands the same store to the generator, and that pass leads with the anchor. `OwnedGraphPartition.prepare` and `CompileFacts.writeRound` are the dev session's, minting the anchor so a diagnostics row has something to hang its foreign key on. The store under `target/graphitron-model` is fresh on every build and has an anchor row by the time the check runs, so every build takes the in-transaction cadence with no statistics anywhere. That is the path every slow log we hold was taken on.

Replace the proxy with the fact. `Materializations` gains a predicate over its own register, answering whether any registered target holds a row, and `FactCapture` asks it exactly where it asks `fetchExists` today, before the transaction opens. Nothing else about the two paths changes: the facts, the anchor row and the hand-written derivations are written the same way on both, as they are now.

Why that predicate rather than a repair of the anchor ordering. It is the conjunction of the two conditions `refreshAnalysing`'s contract actually rests on, stated directly instead of measured through a proxy that three writers can defeat:

- **Safety.** The cadence commits per registration, which is admissible exactly when the pass's `DELETE`s remove no committed row. A graph-keyed target is emptied for one graph and a graph-free one whole, which is the split `Materializations.refreshPartition` and `refreshWhole` carry, so "no registered target holds a row" is precisely "nothing this pass deletes is committed", for both shapes and every graph in the store. Today's predicate reaches that condition only by implication, an empty `store_graph` implying empty targets, and the implication is one-way: targets can be empty on a store whose anchor row exists, which is the case that hurts.
- **Benefit.** The plans that move are the ones reading a registered target, and `ANALYZE` on an empty table records nothing. A store whose targets are all empty carries no statistics worth planning against, whatever `store_graph` says.

The new predicate is strictly wider than the old one: every store that takes the analysing cadence today still takes it, plus the fresh store whose anchor a non-capture writer minted. One further widening is reachable and is stated here rather than discovered later. A workspace store holding a graph whose registered targets legitimately produce no rows makes a second graph's capture take the analysing cadence. That is safe on the same argument, no committed row being deleted, so a reader of the first graph sees nothing move; and it is the cadence that store wants anyway, its targets carrying no statistics.

Cost is one `EXISTS` per registration against an empty or small table, issued once per capture, over the registrations `Materializations.refreshOrder` already reads.

The prose that states the old condition has to state the new one, and there is a fair amount of it: `Materializations`'s class javadoc and `refreshAnalysing`'s, `FactCapture.capture`'s javadoc and the inline comments at both `firstGraph` branches, `WarmStartRefreshTest`'s recovery-round javadoc, the cold-store framing in `RefreshPlanStatisticsTest` and `RefreshPrerequisiteStatisticsTest`, and the cadence paragraph in `docs/architecture/explanation/fact-model.adoc`. The phrase to retire everywhere is "a store that holds no graph"; what replaces it is a store no registered target holds a row in.

### Lever 2: state the partition column's selectivity

`graph_name` holds one value per partition and a store holds one or a few partitions, so its selectivity is a static fact about the model rather than a measurement of a population. H2 reports 50 for a column no `ANALYZE` has looked at, which `StoreStatistics.UNANALYSED` records as verified on 2.4.240, and 50 on a partition column is what prices the one-column foreign-key index below `ix_field_reference_step_hop_step` for the recursive chain's base arm. Declaring `SELECTIVITY 1` restores the fast plan with every other column left at the default, which is the copy reading in the table above and state (c) of R954's three-regime measurement.

This is the only lever that reaches a pass planning inside a transaction, and that is why it survives lever 1 rather than being made redundant by it. `ANALYZE` commits, so an in-transaction refresh can never run one; lever 1 takes the first capture off that cadence and leaves every other capture on it, a second graph into a warm workspace store included. There the declared value is all the planner has.

**Scope: every base table carrying a `graph_name` column**, 226 of the schema's 270, rather than the 23 registered targets alone. The argument for the column is a fact about what a partition column is, and it is equally true of the captured fact tables the refresh reads, which capture writes inside the same transaction and which therefore carry no statistics either. Scoping to the targets would state the fact where it was measured and leave the same trap live on the relations underneath them; the measured instance is one column of one table, and what is being fixed is the class. The narrower scope is the cheaper diff and is what the item filed; it is offered to the reviewer as the fork below.

**Site: a sweep at schema creation**, in `GraphitronModelStore.create`, immediately after the DDL statements execute and before the commit that closes it. It reads the graph-keyed base tables off `INFORMATION_SCHEMA.COLUMNS`, which is the same question `Materializations.graphKeyedRelations` asks of a live store and for the same reason, and issues one `ALTER TABLE ... ALTER COLUMN graph_name SELECTIVITY 1` each. `create` runs exactly where the DDL runs, once per store creation and never on reopen, so the sweep costs what the DDL line would and runs when it would. Three things it buys over 226 hand-written `ALTER` lines in `graphitron-model.sql`: a table added later carries the declaration without anyone remembering, which is what a static list in a 14000-line file cannot promise; a relation a test creates at runtime is covered, which the register's own catalog read is already written to accommodate; and the fact is stated once rather than 226 times. What it costs is siting a schema fact in Java rather than beside the table it describes, which is the fork the reviewer should weigh.

`Materializations.analyse` overwrites the declared value with the measured one on its next run, which is correct and not a loss: on a store with one partition the measured value is 1, and on a workspace store with four graphs it is 4. The declaration is the floor that holds during the window before any `ANALYZE` has run, which is exactly the window the defect lives in.

## Tests

Three claims, each in the tier that can hold it, and none of them a wall clock: a tier that must not fail for being slow cannot hold a figure, which is `DerivedReadCostTest`'s rule and this item inherits it.

**The cadence is decided on the register's state, not on the anchor row.** A pipeline-tier case that writes the `store_graph` anchor before capturing, which is what the build path does, and asserts the capture still took the analysing cadence. The observation is `RefreshPrerequisiteStatisticsTest`'s and needs no new instrument: every registration meets the targets its own rule reads analysed, read through `RefreshProgress`'s started event and `StoreStatistics.analysed`. That test already holds both legs of the pair over a store with no anchor row, so the new case is its third leg and belongs in it rather than in a class of its own. The control is what makes it more than a tautology: with the anchor pre-written, the predicate as it stands today fails this case.

**The declaration is on every graph-keyed base table of a fresh store.** A gate over a store opened and never analysed: every base table with a `graph_name` column reports `SELECTIVITY 1` on that column in `INFORMATION_SCHEMA.COLUMNS`. Exact rather than approximate, and it fails loudly if a new graph-keyed table arrives outside whatever mechanism lever 2 lands. `StoreStatistics` already reads that column and already knows what an unanalysed reading is, so the gate is a reader of it rather than a second spelling.

**The declaration is worth what it is claimed to be worth.** The acceptance evidence for the goal, and the only one of the three that measures rather than asserts state. On a captured store put back to `SELECTIVITY 50` on every column through `StoreStatistics.reset` and then given the partition-column declaration alone, reading `intent_node_id_instruction_live` visits within a stated factor of what it visits on a fully analysed store. The instrument is `EXPLAIN ANALYZE`'s summed `scanCount`, which `DerivedReadCostTest` already carries as a helper and which is a row count rather than a clock, so it is the same on every machine. The reason this claim is worth a test at all is the item's own finding that `EXPLAIN` without `ANALYZE` does not print a recursive CTE's plan, so the index choice is invisible to the cheaper instrument; the scan count is what made it visible in the first place. The figure to pin is measured at implementation and stated in the test, not guessed here.

A fourth thing is deliberately not tested. Nothing asserts the wall clock of a `sis` round, that store being a consumer's rather than the repository's, and the goal's "in the seconds the same statements take on a settled store" is demonstrated by the three claims above plus one recorded re-measurement on the consumer at delivery, reported in the Done gate rather than held by a test.

## Other solutions we've considered

Running `ANALYZE` inside the capture transaction: H2's `ANALYZE` commits, and a commit between the pass's delete and its inserts publishes an emptied partition, which the one-transaction contract in `FactCapture` exists to prevent. Committing the capture before the refresh on every path: this is what the analysing cadence already does for the first graph, and lever 1 is the smaller change that routes the first fill there. Dropping the foreign-key index on `graph_name`: it is the constraint's own index and every target carries one; removing it is not a local change. Disabling H2's result reuse or changing lock or isolation settings: measured, no effect.

Reordering `ix_field_reference_step_hop_step` so the base arm's equalities form a prefix was considered and is not sufficient alone: under default statistics H2's formula still prices the one-column index lower, so the index shape cannot fix the trap without the statistics.

Repairing the anchor ordering instead of lever 1, so that no writer mints a `store_graph` row before a capture's check: it would restore today's predicate rather than replace it, and the predicate would still be a proxy. Three writers reach the anchor for three different reasons and each has a reason to be where it is; making the cadence's correctness depend on all of them staying ordered is a standing invariant with no guard, where the register's own state is a fact already in hand.

Declaring the selectivity on the 23 registered targets alone, which is what this item filed: cheaper as a diff and correct as far as it goes, and it leaves the class open on the 203 other graph-keyed tables the same argument covers. Offered to the Spec gate as the fork lever 2 names.

## Relation to other items

**R954** diagnoses the same pass from the other side and the two compose rather than overlap: this item is the cost of *one* evaluation, that one is the *number* of evaluations, and they multiply, because the walks it converts join `intent_field_reference_step_hop` in both the anchor and the step. Its phase 3 priced this item's cliff on a `sis` store and handed the figure over rather than keeping it, 135x on `intent_node_id_instruction_live` and 315x on `intent_node_id_decode_hop_live`, and its body records that all 23 `ANALYZE TABLE` statements of the failing round ran after the pass that read the tables they analyse.

**Lever 3 of this item's filing is superseded by R954 phase 2 and is dropped from this plan.** Registering `intent_field_reference_step_target` and converting the walk to owner-written rows land the same rows; the difference is whether the register schedules the refill or the gatherer writes it, and R876's doctrine prefers the owner. R954 states the supersession and asked that this body be amended when next touched, which this Spec is. No `depends-on:` edge either way: the two levers that remain here are orthogonal to that item, lever 2 stays true of a stage-written table and R954 says so, and an implementer who reaches phase 2 first and finds a registration already landed takes it as that phase's fallback.

**R955** converts the register's remaining registrations on the same doctrine and names lever 2 as the cheap floor under both cadences, landing per table as this item specifies. Orthogonal, no edge.

## Provenance

R943 named the three expensive registrations and shipped four shape levers that took the `sis` pass from 5424 s to 2662 s, and recorded that its offline instrument under-reproduced the wall clock sixty to eighty fold. Its statistics control compared a cold and a warm round and found them alike; both were in-transaction passes on unanalysed stores, so the control could not see the variable it was built to test. The investigation that filed this item started from that residual, established on copies that no transaction state reproduced it, and found the column by resetting statistics table by table until the copy matched the round.
