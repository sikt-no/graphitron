---
id: R953
title: "The first refresh on a store plans the recursive chain with no statistics, and one default selectivity costs the pass a hundredfold"
status: Backlog
bucket: bug
priority: 1
theme: dev-loop
depends-on: []
created: 2026-09-16
last-updated: 2026-09-16
---

# The first refresh on a store plans the recursive chain with no statistics, and one default selectivity costs the pass a hundredfold

## Goal

A consumer's first `graphitron:generate` or `graphitron:dev` round on a fresh fact store finishes its materialization refresh in the seconds the same statements take on a settled store, instead of the 2662 s a round on the `sis` consumer paid after R943 landed. The refresh pass (the `INSERT ... SELECT` per `meta_materialize` registration that fills each derived target table) is planned with the statistics it needs before it runs, or the one rule whose plan is sensitive to them no longer runs inside a correlated loop, or both. A first round is then bounded by what the rules cost, not by which cadence the store happened to fall into.

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

## Levers

In R876's order, cheapest and most durable first. Which of these the item ships is for Spec to decide; the first two close the trap and the third removes the cliff, and they compose.

1. **Decide the cadence on the fact it depends on.** The analysing cadence exists so that a pass never plans against unanalysed targets. Keying it on `store_graph` being empty measures something else, and the anchor row written before the check is what defeats it. Either compute the first-graph fact before any anchor row can be written, or key the cadence on whether the register's targets carry statistics (or rows). The build path's store under `target/graphitron-model` is fresh on every run, so today it takes the wrong cadence on every run.
2. **State the partition column's selectivity in the DDL.** `graph_name` is one value per partition, and a store holds one or a few partitions; `ALTER TABLE ... ALTER COLUMN graph_name SELECTIVITY 1` on the materialization targets is a static fact, not a measurement, and on the copy it alone restores the fast plan with every other column at the default. This makes even an in-transaction first pass plan correctly, where `ANALYZE` cannot run.
3. **Register `intent_field_reference_step_target`.** The twin of R943's fourth lever, which registered the argument step target. The recursion then runs once per refresh instead of once per `table_node` row per reader, for both node-id rules, and a wrong index choice costs one evaluation rather than 250. R876's family test applies: the view reads `intent_field_reference_step_hop` and `intent_resolved_type_binding`, both registered targets that bottom out in `graphitron_` and `sql_`.

Not sufficient alone: reordering `ix_field_reference_step_hop_step` so the base arm's equalities form a prefix. Under default statistics H2's formula still prices the one-column index lower, so the index shape cannot fix the trap without the statistics.

## Other solutions we've considered

Running `ANALYZE` inside the capture transaction: H2's `ANALYZE` commits, and a commit between the pass's delete and its inserts publishes an emptied partition, which the one-transaction contract in `FactCapture` exists to prevent. Committing the capture before the refresh on every path: this is what the analysing cadence already does for the first graph, and lever 1 is the smaller change that routes the first fill there. Dropping the foreign-key index on `graph_name`: it is the constraint's own index and every target carries one; removing it is not a local change. Disabling H2's result reuse or changing lock or isolation settings: measured, no effect.

## Provenance

R943 named the three expensive registrations and shipped four shape levers that took the `sis` pass from 5424 s to 2662 s, and recorded that its offline instrument under-reproduced the wall clock sixty to eighty fold. Its statistics control compared a cold and a warm round and found them alike; both were in-transaction passes on unanalysed stores, so the control could not see the variable it was built to test. The investigation that filed this item started from that residual, established on copies that no transaction state reproduced it, and found the column by resetting statistics table by table until the copy matched the round.
