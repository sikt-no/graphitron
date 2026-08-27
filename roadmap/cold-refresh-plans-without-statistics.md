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

## When to pick this up

Only if all three hold. The registration that closes the larger mechanism has landed. A capture of a
consumer-size schema still costs materially more than the same refresh on a settled store. And the
figure comes from a real schema rather than from the fixture above, whose growth is one cluster
repeated and whose partitions therefore grow while its shapes do not.

Absent those, this is a recorded measurement and the right action is nothing.

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
