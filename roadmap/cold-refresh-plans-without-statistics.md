---
id: R867
title: "A cold capture's refresh plans without statistics, and the penalty grows with the schema"
status: Backlog
bucket: bug
priority: 4
theme: model-cleanup
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# A cold capture's refresh plans without statistics, and the penalty grows with the schema

`Materializations.analyse` runs after the capture transaction commits, because H2 commits as a side
effect of `ANALYZE` and a commit between the refresh's `DELETE` and its `INSERT` would publish the
emptied target that the one-transaction contract exists to prevent. The consequence is that every
statement a cold capture's refresh issues is planned with no selectivity on anything it reads, while
every timing anybody has ever taken of those same statements was taken afterwards, against a settled
store the analysis had run on.

This is a filed measurement rather than a proposal. Nothing here should be picked up before the
question in the paragraph headed "When to pick this up" is answered yes.

## What is measured

`RefreshPlanStatisticsTest` holds three claims, over one store captured from the read-cost gate's
twelve-unit fixture with the same rows and the same declared indexes under every regime.

Seven of the twenty registrations plan differently cold than with the registered targets analysed.
One mechanism, seen plainly on `intent_node_id_decode_hop_column_live`'s read of
`intent_field_reference_step_hop`: with statistics H2 seeks `IX_FIELD_REFERENCE_STEP_HOP_STEP` on
seven columns, and without them it seeks `CONSTRAINT_INDEX_98` on `graph_name` alone, which is a scan
of the whole graph's partition per driving row. H2's no-statistics default assumes every column has
half as many distinct values as its table has rows, which reads a partition column as highly
selective, so the one-column seek prices as though it were nearly exact.

The statistics they turn on belong to the *registered targets*, which the refresh itself writes,
rather than to the base fact tables. Analysing the fact tables alone reaches none of the seven and
moves two further registrations onto plans of their own; analysing the targets alone reproduces the
settled store's plans on all twenty.

And the penalty is not a constant. One whole `Materializations.refresh` against a populated store,
cold and then with the targets analysed, twice each and the second run reported:

| fields | types | cold refresh | targets analysed | ratio |
|---|---|---|---|---|
| 187 | 79 | 338 ms | 274 ms | 1.2 |
| 349 | 139 | 504 ms | 377 ms | 1.3 |
| 673 | 259 | 1841 ms | 976 ms | 1.9 |
| 1321 | 499 | 9633 ms | 3036 ms | 3.2 |
| 2617 | 979 | 68347 ms | 13199 ms | 5.2 |

Still climbing at the last row, which is what a per-driving-row seek against a growing partition
looks like. That table is a probe run by hand rather than a gate, for the reason the wall-clock
guardrail item gives; the three claims above are what is asserted.

## What it is not

It is not the reason a consumer capture spends an hour in the refresh. That was measured to be an
unregistered view re-evaluated once per driving row inside a correlated term, which no statistics
inform because there is nothing there for a planner to choose, and the fix for it is a registration
worth eighty-one times on the expensive statement. This is a smaller, separate cost that sits beside
it. The two are independent on the evidence: the fixture behind the table above scales its input,
reference and node-id families and holds the `@mutation` payload surface fixed at three, so the
growth measured is not the larger mechanism under another name.

## A real-schema figure, taken 2026-08-27 and recorded here 2026-08-28

The gate below asks for a figure from a real schema rather than from the fixture above, and one
exists. Measured on H2 2.4.240 against a captured store from the sis consumer project, the same
store and the same SQL with only the presence of statistics varying:

| refresh position | registration | fresh capture | selectivity zeroed | statistics present |
|---|---|---|---|---|
| 14 | `intent_node_id_instruction` | 624.5 s | 584.8 s | 3.8 s |
| 15 | `intent_input_field_filter_role` | 2823.2 s | 2555.4 s | 20.9 s |
| 16 | `intent_node_id_decode_hop_column` | 2721.5 s | did not finish | 4.7 s |
| 1 to 16 | total | 6293 s | | 90.8 s |

Sixty-nine times on the measured prefix, against 1.2 to 5.2 on the fixture. `EXPLAIN` diffs confirm
the join order reversing between the two conditions, with identical `tableScan` counts either way,
so the count `DerivedReadCostTest` sums does not move when this defect is present and is not the
instrument for it. The whole refresh of that capture took four hours and nineteen minutes; an
`ANALYZE` over the registered targets on that store costs 0.2 s.

**These are not the statements the section above measured, and that is why both readings stand.**
The 0.82 and 0.98 ratios recorded against this arm in R856 were taken on
`intent_mutation_payload_column_live` and `intent_mutation_payload_refusal_live`, whose dominant term
is a `GROUP BY` over a recursive term re-evaluated once per driving row and which no selectivity
informs. The three above are disjoint from that pair. So the arm is statistics-independent where
R856 measured it and 69-fold where this store measures it, which is the "grows with the schema"
claim of the table above arriving at consumer scale rather than a contradiction of anything.

The three dear positions are also exactly the ones reading a target the same pass refilled earlier:
`intent_node_id_decode_hop_column_live` reads `intent_node_id_decode`, which reads
`intent_node_id_instruction`, the target filled at position 14. Positions 1 to 13 together are 124 s
of the 6293, against 90.8 s for all sixteen with statistics. That is the same split this item
already states, holding at consumer scale: the target half is the whole of the penalty and the base
half is a fraction of it.

Two engine facts checked while this was recorded, both closing an escape the fix would otherwise be
asked about:

- `ANALYZE` on an empty table records nothing. Selectivity stays at H2's unanalysed default of 50 on
  every column, and a later fill does not revisit it. This is a second and sharper reason the cheap
  rung fails: quite apart from reaching the wrong population, an analysis placed before the refresh
  runs against twenty empty tables and states nothing about any of them.
- `ALTER TABLE ... ALTER COLUMN ... SELECTIVITY n` commits, verified the same way `ANALYZE` was. So
  stating the figure by hand instead of calling `ANALYZE`, which would be the one route to
  statistics that left the single transaction intact, is not available. Every route into H2's
  statistics crosses a commit, and the split is forced rather than preferred.

## When to pick this up

Only if all three hold. The registration that closes the larger mechanism has landed. A capture of a
consumer-size schema still costs materially more than the same refresh on a settled store. And the
figure comes from a real schema rather than from the fixture above, whose growth is one cluster
repeated and whose partitions therefore grow while its shapes do not.

Absent those, this is a recorded measurement and the right action is nothing.

**Where the three stand as of 2026-08-28.** The second and third are met by the section above: a real
consumer store, and a cost of 6293 s against 90.8 s on the same rows. The first is close but not yet
met. R856 went `Ready` to `In Progress` on 2026-08-28 and its first implementation commit registers
`intent_node_id_decode_column` and `intent_input_field_carrier_role`, taking that item's refresh from
60 ms to 6 on the sakila example schema; it is not yet at `Done`, and its verification against a
consumer-size schema is what would answer its own second condition here.

The order therefore stays R856 then this, and the reason is now about instrument rather than about
priority: landing the split while R856 is mid-flight would change the regime every one of that
item's timings was taken in, so its remaining verification would be read against a store whose
statistics regime had moved under it. What has changed is that this item has its figure and will not
need one when its turn comes.

## What a fix would be

One transaction per registration, each committing its own target's `DELETE` and `INSERT` together and
analysing the target it just refilled, with the facts committed ahead of the first of them. That
reaches the population the plans turn on: a registration would plan against targets the registrations
before it analysed, which is exactly the regime that reproduces the settled store's plans.
`MaterializeDependencies` refuses a registration whose source view reads its own target and orders
every registration after the ones whose targets it reads, so nothing would meet its own target
unanalysed.

The cheap alternative is refuted rather than merely unpriced, and that is the main thing this item
saves whoever picks it up: committing and analysing the facts ahead of a still-single-transaction
refresh reaches none of the seven, because the seven turn on the other population.

Three invariants the split touches, none of which may be left to the diff:

- **The emptied target.** The split is per registration rather than per refresh so that a reader
  between two registrations sees one target current and another stale rather than an emptied
  relation. `Materializations.refreshAll` is the precedent that a stale-beside-current pair is
  acceptable, already issuing every `DELETE` and `INSERT` in autocommit.
- **The concurrent same-graph writer.** `FactCapture.capture`'s javadoc names the single transaction
  as what makes a second writer of one graph serialize on the anchor row instead of interleaving
  deletes with inserts. A per-registration transaction holds no anchor row, so the refresh
  transactions have to take it too, or same-graph concurrency has to be argued out of scope.
- **What a stamp means.** `ClasspathSources` states that a stamp is written after the rows it vouches
  for, which puts the stamps after the last refresh transaction. Name the test in the
  warm-reconciliation family that fails if the placement moves.

Four prose surfaces argue for today's shape and would have to move in the same pass:
`Materializations`' class javadoc on the capture cadence, `analyse`'s javadoc on why it runs outside
the transaction, the two comments around the refresh call in `FactCapture.capture`, and the
ANALYZE-placement passage in `docs/architecture/explanation/fact-model.adoc`.

## Already landed, so this item owes none of it

`RefreshPlanStatisticsTest` asserts the three claims. The engine behaviour behind them, and the
instrument correction that a plain `EXPLAIN` renders the unmasked form of a view's inner query and so
is not the plan that runs, are recorded in `docs/architecture/explanation/fact-model.adoc`.
