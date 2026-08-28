---
id: R867
title: "A cold capture's refresh plans without statistics, and the penalty grows with the schema"
status: Spec
bucket: bug
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-27
last-updated: 2026-08-28
---

# A cold capture's refresh plans without statistics, and the penalty grows with the schema

`Materializations.analyse` runs after the capture transaction commits, because H2 commits as a side
effect of `ANALYZE` and a commit between the refresh's `DELETE` and its `INSERT` would publish the
emptied target that the one-transaction contract exists to prevent. The consequence is that every
statement a cold capture's refresh issues is planned with no selectivity on anything it reads, while
every timing anybody has ever taken of those same statements was taken afterwards, against a settled
store the analysis had run on.

**This is a stop-gap and it goes first.** What it removes is the pathological cost of a first capture
into a fresh store, which is the case a consumer actually meets and the case no timing in this repo
has ever been taken against. What it does not do is settle whether the `meta_materialize` registry is
right: every registration's stated `reason` was measured on a store whose statistics were current,
which is not the regime the pass those reasons describe runs in, so re-deriving the register is
separate and larger work. The paragraph headed "Why this goes first" carries the ordering, which is
the reverse of what this file recorded on 2026-08-27.

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

The fixture above scales one cluster repeated, so its partitions grow while its shapes do not, and a
figure from a real schema is what says whether the growth is the fixture's or the defect's. One
exists. Measured on H2 2.4.240 against a captured store from the sis consumer project, the same store
and the same SQL with only the presence of statistics varying:

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

## Why this goes first

This file previously ordered itself after R856 and gated itself behind three conditions, on the
argument that landing the split mid-flight would move the regime that item's timings were taken in.
That argument is backwards, and the reason is the same measurement it was written to protect.

Every optimisation decision the fact model has taken so far was taken in the wrong regime. A timing
can only be taken against a settled store, so the register's twenty-two stated reasons, the read-cost
gate's pinned counts, and every registration argued for on a measured win were all read against a
store whose statistics were current. The pass those reasons describe is the capture refresh, which
runs with none. That is not a caveat on those figures, it is a different regime: 69-fold on the
measured prefix of a real consumer store, and enough on the last three positions that the pass has
never been observed to finish. So a registration whose reason reads "measured at 49 s, worth
registering" may be a statement about an absent `ANALYZE` rather than about the registration, and
nobody can tell which until the refresh plans the way the readers that justified it did.

Moving the regime is therefore the point rather than the hazard. Landing this first means the next
measurement anybody takes of the register, R856's remaining verification included, is taken in the
regime the build actually runs in. Taking it second means re-taking that verification afterwards
anyway, and meanwhile leaving a consumer-size capture at four hours and nineteen minutes.

R856 stays a separate and larger item: its mechanism is an unregistered view re-evaluated once per
driving row inside a correlated term, which no statistics inform, and the two are independent on the
evidence the section above gives. It went `In Review` back to `Ready` on 2026-08-28 with its own
proof owed. Its remaining verification against a consumer-size schema should be re-taken once this
has landed rather than before.

The three conditions this section used to carry are dropped rather than met. Two of them were
answered by the real-schema figure above. The third, that the larger mechanism land first, is the
ordering this section just reversed.

## The fix

One transaction per registration, each committing its own target's `DELETE` and `INSERT` together and
analysing the target it just refilled, with the facts committed ahead of the first of them. That
reaches the population the plans turn on: a registration plans against targets the registrations
before it analysed, which is exactly the regime that reproduces the settled store's plans.
`MaterializeDependencies` refuses a registration whose source view reads its own target and orders
every registration after the ones whose targets it reads, so nothing meets its own target unanalysed.

Applied on one branch and not on both, which is what keeps this a stop-gap rather than a change to
what a capture is. The split runs when **`store_graph` holds no row at capture entry**, and every
other capture keeps today's single transaction unchanged.

Three things decide that predicate, and the second is the one that rules out the smaller-looking
spellings of it:

- It is the case that hurts. A store with no committed graph is a store whose every registered target
  is empty, so every plan in the pass is chosen with no selectivity anywhere. This is the four hours
  and nineteen minutes.
- It is not `warm`, and it is not "no committed partition for this graph". `warm` is a store-open
  property, and a reactor run capturing several graphs into one store has rows committed by the first
  capture while the later ones still see `warm` false. Per-graph is worse than useless: a target with
  no `graph_name` column is refreshed *whole* by `refreshWhole`, so a capture cold for one graph would
  empty a target holding another graph's derived rows. "No committed graph at all" is the only reading
  under which nothing committed can be emptied.
- It is where the contract has nothing to protect. The one-transaction contract exists so that no
  reader observes an emptied target. A reader of a store with no committed graph has nothing to
  observe: readers reach a partition through `store_graph`, and there is no row. So the split is safe
  exactly here, and the branch is not a convenience.

**The cheap alternative is refuted rather than merely unpriced, and this is the main thing the item
saves whoever implements it.** Committing the facts and analysing them ahead of a
still-single-transaction refresh is the smallest change that could be believed to close this, and it
closes none of the eight registrations whose plan moves, because those eight read a *registered
target* and no statement before the refresh has written one.
`RefreshPlanStatisticsTest.analysingTheFactsAloneReachesNoneOfThem` is that claim, already in the
tree and already failing anyone who lands the cheap rung believing it sufficient. Analysing the facts
is still worth doing on the split path, since it moves two further registrations onto plans of their
own and costs 0.2 s on the sis store, but it is not what makes the fix work.

### Shape

- `Materializations` gains a third cadence beside `refresh` and `refreshAll`: per registration, one
  transaction carrying that target's `DELETE` and `INSERT` together, then `ANALYZE` on the target it
  just refilled, outside that transaction. It reuses `refreshOne`, the sequence from `refreshOrder`,
  and the progress contract verbatim, so the event order `MaterializationProgressTest` holds needs no
  edit. `refreshAll` is the precedent that a stale-beside-current pair of targets is acceptable,
  already issuing every `DELETE` and `INSERT` in autocommit.
- `FactCapture.capture` reads the predicate before it opens its transaction and branches once. On the
  split branch the load transaction commits the facts, `Materializations.analyse` states the fact
  tables' statistics, the new cadence runs the refresh, and `sources.commitStamps` runs in a
  transaction of its own after it. The trailing `Materializations.analyse(dsl)` stays exactly where it
  is on both branches, so `MaterializeRegistryGateTest`'s count contract is untouched. The other
  branch keeps every statement it has today.

### The three invariants, decided here rather than left to the diff

- **The emptied target.** Answered by the predicate: there is no committed state to publish. The
  per-registration transaction is still what the split uses, so that a killed run leaves whole
  targets rather than one emptied relation.
- **The concurrent same-graph writer.** `FactCapture.capture`'s javadoc names the single transaction
  as what makes a second writer of one graph serialize on the anchor row instead of interleaving
  deletes with inserts, and a per-registration transaction holds no anchor row. Two *processes* are
  not the case: `GraphitronModelStore.fileUrl`'s javadoc records that the store takes MVStore's own
  operating-system lock and a second process is refused as `90020` straight into the in-memory
  fallback, so the writer this invariant protects against is inside one process. Decision: each
  refresh transaction on the split path leads with a `SELECT ... FOR UPDATE` on the graph's
  `store_graph` row under `ANCHOR_LOCK_MILLIS`, which is the same lead-with-the-anchor rule capture
  already states, and a capture that cannot take it falls back to the unsplit path, which is correct
  and merely slow. Falling back rather than interleaving is deliberate: interleaved registrations
  would leave targets whose rows came from two runs' fact sets, and that mix is observable once both
  runs commit.
- **What a stamp means.** `ClasspathSources` states that a stamp is written after the rows it vouches
  for, and on the split path the derived targets are written after the load's flush, so the stamps
  move after the last refresh transaction. A run killed mid-refresh then leaves a null stamp, which no
  refresh retains, which is the behaviour that class already documents rather than a new one.
  `WarmStartRefreshTest.warmAndColdAgreeRelationByRelation` is the test in the warm-reconciliation
  family that fails if the placement moves the wrong way, and the family gains a case for a run
  killed between the load commit and the refresh.

### How we know it is delivered

A new test beside `RefreshPlanStatisticsTest`, asserting the invariant rather than a wall clock:
drive the new cadence on a store with every selectivity reset, with a `RefreshProgress` observer that
reads `INFORMATION_SCHEMA.COLUMNS.SELECTIVITY` at each `RegistrationStarted`, and assert that every
registered target the starting registration's source view reads carries analysed selectivity by then.
That is sufficient rather than a proxy, because
`RefreshPlanStatisticsTest.theSettledStoreIsTheTargetsAnalysedRegime` already asserts that
targets-analysed is the settled store's plans: reaching that state before each registration is
reaching the plans every timing in this investigation was taken against.

No figure is asserted, for the reason the wall-clock guardrail item gives. `DerivedReadCostTest`'s
pinned counts are taken on a settled store and should not move; if they do, that is a finding and not
a tolerance to widen. `MaterializeRegistryGateTest`, `MaterializationOrderTest`,
`MaterializationProgressTest` and `WarmStartRefreshTest` stay green.

### Prose that argues for today's shape and moves in the same pass

- `Materializations`' class javadoc on the two capture cadences, which becomes three.
- `analyse`'s javadoc on why it runs outside the transaction. The rule stays true; what changes is
  that the capture path is no longer forced to put it after the whole pass.
- The two comments around the refresh call and the `analyse` call in `FactCapture.capture`, which
  currently assert that today's ordering is right.
- The ANALYZE-placement passage in `docs/architecture/explanation/fact-model.adoc`, and the paragraph
  after it that calls this cost "not worth restructuring a capture for on its own".

### Phases

1. `Materializations`: the third cadence and its javadoc, plus the class javadoc's cadence paragraph.
2. `FactCapture.capture`: the predicate, the single branch, the anchor lock and its fallback, the
   stamp placement, and the two comments.
3. The invariant test, and the `WarmStartRefreshTest` case for a run killed between the load commit
   and the refresh.
4. `fact-model.adoc`.

## Already landed, so this item owes none of it

`RefreshPlanStatisticsTest` asserts the three claims. The engine behaviour behind them, and the
instrument correction that a plain `EXPLAIN` renders the unmasked form of a view's inner query and so
is not the plan that runs, are recorded in `docs/architecture/explanation/fact-model.adoc`.
