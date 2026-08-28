---
id: R867
title: "A cold capture's refresh plans without statistics, and the penalty grows with the schema"
status: In Progress
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

Eight of the twenty-two registrations plan differently cold than with the registered targets analysed.
One mechanism, seen plainly on `intent_node_id_decode_hop_column_live`'s read of
`intent_field_reference_step_hop`: with statistics H2 seeks `IX_FIELD_REFERENCE_STEP_HOP_STEP` on
seven columns, and without them it seeks `CONSTRAINT_INDEX_98` on `graph_name` alone, which is a scan
of the whole graph's partition per driving row. H2's no-statistics default assumes every column has
half as many distinct values as its table has rows, which reads a partition column as highly
selective, so the one-column seek prices as though it were nearly exact.

The statistics they turn on belong to the *registered targets*, which the refresh itself writes,
rather than to the base fact tables. Analysing the fact tables alone reaches none of the eight and
moves two further registrations onto plans of their own; analysing the targets alone reproduces the
settled store's plans on all twenty-two.

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
  runs against twenty-two empty tables and states nothing about any of them.
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
tree and already failing anyone who lands the cheap rung believing it sufficient.

**And the split path does not analyse the base fact tables at all, which is a decision rather than an
omission.** Three things settle it. `Materializations.analyse` covers the registered targets and
nothing else, by a measured decision its own javadoc records against the broader form, which on the
read-cost fixture left one reader dearer than the targeted form did; so reaching the facts would mean
minting a second scope beside a scope that was measured, and a stop-gap does not get to make that
call untested. The facts' statistics are measured to move two registrations onto plans *of their own*
rather than onto better ones, which `RefreshPlanStatisticsTest` states as a second finding and not as
a win. And at the only place on the split path such a call could sit, between the load commit and the
first refresh transaction, every target is still empty, so an `analyse` there would record nothing
even about the population it does cover, by this item's own first engine fact. Whether the base fact
tables deserve statistics is a real open question and it belongs with the register re-derivation,
which is where a measurement that could answer it will be taken. The figure this item does pay is on
the other population: an `ANALYZE` over the registered targets on the sis store costs 0.2 s, and that
is the whole cost of the per-registration analysis the split path adds.

### Shape

- `Materializations` gains a third cadence beside `refresh` and `refreshAll`: per registration, one
  transaction carrying that target's `DELETE` and `INSERT` together, then `ANALYZE` on the target it
  just refilled, outside that transaction. Each transaction leads with the anchor lock the
  concurrent-writer invariant below decides, this being the code that opens them. It reuses
  `refreshOne`, the sequence from `refreshOrder`, and the progress contract verbatim, so the event
  order `MaterializationProgressTest` holds needs no edit. `refreshAll` is the precedent that a
  stale-beside-current pair of targets is acceptable, already issuing every `DELETE` and `INSERT` in
  autocommit.
- `FactCapture.capture` reads the predicate before it opens its transaction and branches once. On the
  split branch the load transaction commits the facts and the anchor row, the new cadence runs the
  refresh, and `sources.commitStamps` runs in a transaction of its own after it. Four statements'
  worth of change, and no new method beyond the cadence above. The trailing
  `Materializations.analyse(dsl)` stays exactly where it is on both branches, so
  `MaterializeRegistryGateTest`'s count contract is untouched, and on the split branch it is the
  idempotent restatement of what the pass already analysed. The other branch keeps every statement it
  has today.

### The three invariants, decided here rather than left to the diff

- **The emptied target.** Answered by the predicate for the thing the contract protects: there is no
  committed state to empty. What the split branch does publish, and today's capture cannot, is a
  window in which `store_graph` holds the graph while its targets are still incomplete. That is not a
  state new to the store. It is what `refreshAll` produces on every reader open, issuing every
  `DELETE` and `INSERT` in autocommit with no transaction at all over a `store_graph` that already
  names the graph, and a reader that opens this store mid-split calls exactly that. So the window is
  the reader cadence's existing exposure arriving on the capture cadence, on a store that came into
  existence a moment ago.
- **The concurrent same-graph writer.** `FactCapture.capture`'s javadoc names the single transaction
  as what makes a second writer of one graph serialize on the anchor row instead of interleaving
  deletes with inserts, and a per-registration transaction holds no anchor row. Three facts narrow
  the case before a decision is needed. Two *processes* are not it:
  `GraphitronModelStore.fileUrl`'s javadoc records that the store takes MVStore's own
  operating-system lock and a second process is refused as `90020` straight into the in-memory
  fallback, so the writer is inside one process. A same-graph capture that starts *before* this one's
  load commits is excluded already, by the anchor upsert that leads the load transaction and whose
  `ANCHOR_LOCK_MILLIS` budget exists to demote the loser to memory rather than let it wait. What the
  split leaves is one case: a same-graph capture starting *after* this one's load commits, which
  therefore sees a populated `store_graph`, takes the unsplit path, and whose single transaction can
  overlap this one's remaining refresh transactions on one graph's partition.

  Decision, in statements rather than in a path name. Each refresh transaction on the split path
  leads with `SELECT ... FOR UPDATE` on the graph's `store_graph` row, under the store's ordinary
  `GraphitronModelStore.FILE_LOCK_MILLIS` budget and deliberately not under `ANCHOR_LOCK_MILLIS`: the
  fail-fast budget is for the row a capture leads with, where nothing has been done yet and waiting
  buys nothing, and here the load is committed and the whole refresh sits in front of the lock, which
  is the case `FILE_LOCK_MILLIS`'s own javadoc is written for. There is no fallback. The previous
  revision said such a capture "falls back to the unsplit path", and that path is one transaction
  spanning load and refresh, so it cannot be entered from a state where the load has committed; the
  sentence named an unreachable branch. A lock this cannot take inside that budget raises
  `DataAccessException` from a refresh statement, which is what any statement in today's refresh can
  raise, so it needs no handling of its own and gets none.

  **What a run that stops mid-refresh leaves behind**, stated once here because the invariant below
  and the new test are both written against it, and identical whether the run was killed, refused the
  lock, or failed on any other statement: the facts and the anchor row committed, the targets
  refreshed up to the registration that stopped and stale or empty after it, and no stamp, the stamps
  being the last thing the split path writes. The next capture reads a null stamp, does not retain the
  partition, and reloads and re-derives it. That is the recovery `ClasspathSources` already documents
  for a part-way run, reached by a new route.

  **The retry is that state's first reader, and it is inside the same run.** `captureWithRetry` gives a
  capture that failed on anything but a lock timeout one more attempt, and a deadlock out of the
  refresh is precisely the casualty it exists for, so this is the intended path rather than a corner.
  What an attempt has to reconcile is therefore read from the store at the attempt rather than taken
  from the store's warm flag, which is fixed at the open and stood in for the question only while a
  capture was all-or-nothing. Handed the flag, the retry would skip `StoreRefresh.prepare` and collide
  with its own first attempt on the first key it re-inserts, and report the collision as a
  deterministic capture bug.
- **What a stamp means.** `ClasspathSources` states that a stamp is written after the rows it vouches
  for, and on the split path the derived targets are written after the load's flush, so the stamps
  move after the last refresh transaction. That placement is what makes the paragraph above true: move
  the stamps back ahead of the refresh and a run that stops mid-refresh leaves a stamp vouching for
  targets it never filled, which the next capture retains.
  `WarmStartRefreshTest.warmAndColdAgreeRelationByRelation` is the test in the warm-reconciliation
  family that fails if the placement moves the wrong way, and the family gains a case for a run that
  stops between the load commit and the last refresh transaction: the next capture over that store
  must produce the rows a cold run would, relation by relation.

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

The four phases shipped at `60e4ea8`; the retry fix below shipped after the Done-gate review. Each on
its own full-reactor verification build.

1. `Materializations`: the third cadence `refreshAnalysing`, its anchor lock, its javadoc, and the
   class javadoc's cadence paragraph. Shipped.
2. `FactCapture.capture`: the predicate, the single branch, the stamp placement, and the two
   comments. Shipped.
3. `RefreshPrerequisiteStatisticsTest`, and the `WarmStartRefreshTest` round for a run that stops
   part-way. Shipped.
4. `fact-model.adoc`. Shipped.

### What shipped differently from the plan above, and why

Five departures, none of them changing the fix. Four were found by building it and the fifth by the
Done-gate review, which is recorded here rather than only in the findings below because it is part of
what shipped.

- **The cadences differ only in what encloses one registration's statement pair, so that is the
  parameter.** `refreshOne` takes an `Enclosure`: the two old cadences run the pair on the caller's
  context, the new one runs it in a transaction of its own behind the anchor row. A second loop per
  cadence was the obvious alternative and it breaks the emission-order rule the class states, which
  cannot hold for every cadence if each cadence emits the events itself. The pair receives its
  context from the enclosure rather than closing over one, so a cadence cannot issue its statements
  outside the transaction it opened for them. The anchor lock therefore sits inside the enclosure,
  which is also what keeps the registration's name ahead of the wait for the lock.
- **The new test's population excludes targets holding no row, which was measured rather than
  foreseen.** `ANALYZE` on an empty table records nothing, so an empty target reports unanalysed
  forever; three registrations of the read-cost fixture read a target the `@mutation` payload
  surface leaves empty, and without the filter they read as a defect the cadence cannot fix. The
  filter asks per target rather than trusting the fixture size, which also corrects a claim
  `RefreshPlanStatisticsTest` carries in passing, that twelve units is the size at which every
  registered target holds rows.
- **The `WarmStartRefreshTest` round does not gate the stamp placement, and says so.** No failing
  case exists to write: every capture and every reader open refreshes every registered target for
  its graph unconditionally, so a stale target comes back whichever side of the refresh the stamps
  are written on. The stamps still move, because a stamp claiming rows that were never written is
  wrong on `ClasspathSources`' own stated rule, but the invariant is a consistency requirement
  rather than a defence against a reachable stale store, and the round is written and documented as
  what it does hold: that a store left with facts complete, a target emptied and no stamp is brought
  back to what a cold run produces. The round also needed a table-bound schema, that family's
  two-type schema filling no registered target at all.
- **The retry's warmth, which round 2 found and the first version of this section did not name.**
  `captureWithRetry` asks `FactCapture.reconciles` per attempt instead of handing both attempts the
  store's warm flag; the invariant section carries why, under what a stopped pass leaves behind. Shipped
  as part of this item because the split path is what makes the flag wrong.
- **One shared instrument rather than two spellings of "unanalysed".** `StoreStatistics` carries the
  reset and the analysed reading, and `RefreshPlanStatisticsTest` now uses it instead of its own
  copy. That is the one edit to an existing test beyond the round above.

## Already landed, so this item owes none of it

`RefreshPlanStatisticsTest` asserts the three claims. The engine behaviour behind them, and the
instrument correction that a plain `EXPLAIN` renders the unmasked form of a view's inner query and so
is not the plan that runs, are recorded in `docs/architecture/explanation/fact-model.adoc`.

## Reviewer findings

### Round 1: Spec -> Ready, revisions requested

Reviewer session `session_01TbLMaJoBnnDRxdfJnRU5LT`, 2026-08-28.

Question 1 is answered well. What changes for a consumer is legible without reading the phase list: a
first capture into a fresh store stops planning every refresh statement against a store with no
selectivity anywhere, which on the sis capture is the difference between four hours and nineteen
minutes and something in the minutes. The measurement half is unusually strong, the two engine facts
close the escapes they are aimed at, and the reversal argument in "Why this goes first" is sound: a
regime nobody has ever measured in is not a caveat on the register's reasons, and moving it first is
cheaper than re-taking every figure afterwards. Every symbol, test class and test method the spec
names exists under the name given, `MaterializeDependencies`' refusal and ordering guarantees are as
described, `analyse`'s return value is what `MaterializeRegistryGateTest` pins, and
`GraphitronModelStore.fileUrl`'s `90020` claim is in the javadoc as quoted. The stale
seven-of-twenty count is corrected to eight of twenty-two in this commit, matching
`PLAN_DEPENDS_ON_STATISTICS` and the fact-model page.

Two findings block, both on question 2.

**Blocking, question 2: `Materializations.analyse` cannot state the fact tables' statistics, so the
split branch as written contains a step that does nothing and the step it means needs a method that
does not exist.** The Shape section says that on the split branch "the load transaction commits the
facts, `Materializations.analyse` states the fact tables' statistics, the new cadence runs the
refresh". `analyse` analyses the registered targets and only those, and its javadoc records a
measured decision against the broader form: "Scoped to the registered targets rather than the whole
database, for a measured reason and not only a modest one ... on the same fixture it left one reader
dearer than the targeted form did." `RefreshPlanStatisticsTest`'s own reference-leg javadoc says the
same, that `analyse` "covers the registered targets and nothing else".

Called where the Shape puts it, the targets are still empty, and this item's own first engine fact is
that `ANALYZE` on an empty table records nothing. So the sentence describes a no-op, while the prose
under "The cheap alternative is refuted" claims a benefit from it that is real ("it moves two further
registrations onto plans of their own and costs 0.2 s on the sis store") and reachable only through
code no phase provides. An implementer has to invent the surface: a second entry point beside
`analyse`, or a scope argument on it, and either way a decision about which base tables it covers
that has to be reconciled with the measurement `analyse`'s javadoc already carries against covering
them all. That is a design fork in a section headed "decided here rather than left to the diff", and
it is the kind that gets settled silently in a diff nobody reviews as a draft.

What would satisfy it: name what actually analyses the facts, where it lives, what it covers (the
`facts` regime in `RefreshPlanStatisticsTest` computes exactly this set, base tables minus registered
targets, and is the obvious reference), how it squares with the whole-database measurement `analyse`
declines on, and which phase carries it. Or drop the fact-side analysis from the split branch and say
so, since the item is explicit that it is not what makes the fix work.

> *Author response, revision 1.* Accepted, and taken the second way: the split branch analyses no
> base fact table, and the paragraph after the cheap-alternative refutation now says so with the
> reasons. The finding is right on all three counts, and the third one is the decisive one, since it
> means the sentence was not merely naming the wrong method but placing a call where nothing it could
> cover holds a row yet. Minting a second scope beside a scope `analyse`'s javadoc records a
> measurement for is not a call a stop-gap gets to make, especially when the facts' measured effect is
> two registrations on *different* plans rather than better ones. The 0.2 s figure is re-attributed to
> where it was taken, the registered targets, which is the cost the split path actually pays. Whether
> the fact tables deserve statistics is left named as an open question for the register re-derivation,
> which is where a measurement able to answer it will be taken.

**Blocking, question 2: the anchor-lock fallback is not reachable at the moment it applies.** The
concurrent-writer invariant decides that "each refresh transaction on the split path leads with a
`SELECT ... FOR UPDATE` on the graph's `store_graph` row under `ANCHOR_LOCK_MILLIS` ... and a capture
that cannot take it falls back to the unsplit path, which is correct and merely slow." By the time
the first refresh transaction runs, the split branch has already committed the load. The unsplit path
is one transaction spanning load and refresh; it cannot be entered from a state where the load is
committed, so there is nothing to fall back to under that name.

The nearest implementable readings differ in observable behaviour, which is why this is not a
wording matter. Running the whole refresh in one transaction over already-committed facts is not
today's unsplit path: a run killed inside it leaves committed facts with stale or empty targets, which
is a state today's capture cannot produce and which interacts with the stamp-placement invariant
directly below it and with the `WarmStartRefreshTest` case phase 3 adds. Failing the capture outright
is the other reading and is not "merely slow". Taking the lock once for the whole pass rather than per
transaction is a third, and changes what the per-registration split buys.

What would satisfy it: say which of those the fallback is, in terms of statements rather than of a
path name, and state what a run killed in it leaves behind, so the invariant below it and the new
`WarmStartRefreshTest` case are written against a decided answer. If the answer is that the fallback
is unreachable in practice and the lock is a belt-and-braces assertion, saying that is also an answer,
but then the invariant should say what happens when the assertion fires.

> *Author response, revision 1.* Accepted; the fallback is deleted rather than reassigned, and the
> invariant is rewritten around what the finding exposed. There is no fallback: a refused lock raises
> `DataAccessException` from a refresh statement, which is what any statement in today's refresh can
> raise, so it needs no path of its own. The lock budget moves off `ANCHOR_LOCK_MILLIS` to the store's
> ordinary `FILE_LOCK_MILLIS` in the same pass, because the fail-fast budget is for the row a capture
> leads with, where waiting buys nothing, and this lock has a committed load behind it and the whole
> refresh in front of it, which is the case the ordinary budget's javadoc is written for.
>
> Working the finding also narrowed what the lock is for. The same-graph writer that starts before
> this one's load commits is already excluded by the anchor upsert leading the load transaction, so the
> only case the split opens is a capture starting *after* that commit, which sees a populated
> `store_graph`, takes the unsplit path, and can overlap the remaining refresh transactions. And the
> exposure the finding pointed at is not really concurrency: the split branch publishes a window in
> which the graph is in `store_graph` while its targets are incomplete, whether or not a second writer
> exists. That is now stated under the emptied-target invariant, with the argument that it is the
> exposure `refreshAll` already produces on every reader open in autocommit rather than a new one.
>
> What a run that stops mid-refresh leaves behind is stated once, in statements, and is the same for a
> kill, a refused lock, and any other statement failure: facts and anchor row committed, targets
> refreshed up to the registration that stopped, no stamp. The stamp invariant now says why its
> placement is what makes that recovery work, and the `WarmStartRefreshTest` case is written against
> that state rather than against a kill point.

**Non-blocking.** All twenty-two registered targets carry a `graph_name` column today, so
`refreshWhole` is not reachable from the current register and the second bullet of the predicate
argument is a claim about a shape the mechanism permits rather than one the register exhibits. The
argument is still the right one to make, and the bullet is right that per-graph is the wrong reading;
worth knowing only because it means the hazard it rules out has no live instance to test against.

> *Author response, revision 1.* Noted and left as it stands. The bullet is about what the mechanism
> permits rather than what the register exhibits, and the day a graph-free target is registered is the
> day the per-graph reading would silently empty another graph's rows, which is exactly when nobody
> would be re-reading this argument. Recorded here rather than moved into the bullet, so the bullet
> does not read as describing a live case.

### Round 2: In Review -> Done, rework requested

Reviewer session `session_01TbLMaJoBnnDRxdfJnRU5LT`, 2026-08-28.

The implementation is close and the shape is right. `refreshAnalysing` is the cadence the spec
decided, the `Enclosure` parameter is a better answer than the spec's own sketch (the emission-order
rule stays at one site, and the statement pair cannot escape the transaction opened for it because it
receives its context rather than closing over one), and `RefreshPrerequisiteStatisticsTest` asserts
the invariant with a control leg that makes the claim non-vacuous. All four departures are honest,
and the third one, downgrading what the `WarmStartRefreshTest` round holds, is right on the code:
every capture and every reader open refreshes every registered target for its graph unconditionally,
so a stale target does come back either way, and saying so beats writing a round that pretends
otherwise. `mvn install -Plocal-db` is green on this tree.

One finding blocks, and one is bookkeeping.

**Blocking: the split path breaks `captureWithRetry`, in exactly the case the retry exists for, and
turns its diagnostic into a false accusation.** `captureWithRetry` passes `store.warm()` to both
attempts. That is a store-open property, a field fixed when the store opened, and it was a sound
stand-in for "this store holds rows this run must reconcile" only because a capture used to be
all-or-nothing: attempt one rolled back, so attempt two really did meet the store attempt one found.
The split path breaks that coupling. Its load transaction commits the facts, the anchor row and the
hand-written derivations before `refreshAnalysing` runs, so a `DataAccessException` out of the
refresh leaves them committed. The retry then re-enters `capture` with `warm` still false, skips
`StoreRefresh.prepare`, and re-inserts a partition that is already there.

Reproduced rather than argued. A capture into a fresh persisted store followed by a second
`capture(dsl, false, ...)` against the same store fails on the second:

    org.jooq.exception.IntegrityConstraintViolationException:
    insert into "PUBLIC"."GRAPHQL_DIRECTIVE" ...
    Unique index or primary key violation: PRIMARY_KEY_D1C ON PUBLIC.GRAPHQL_DIRECTIVE(GRAPH_NAME, DIRECTIVE_NAME)

That is a `DataAccessException` and not a lock timeout, so `captureWithRetry` takes it as the second
failure and logs "failed twice in a row; this looks like a deterministic capture bug rather than a
concurrency casualty, and warm start will stay unavailable for this graph until it is fixed". Both
halves of that sentence are now wrong on the split path: the second failure is the retry colliding
with its own first attempt, not evidence about the capture, and the run that provoked it may have
been the transient casualty the retry was written to absorb. `captureWithRetry`'s own javadoc names a
deadlock as "exactly the transient casualty the retry was written for", and a deadlock out of
`refreshAnalysing` keeps its retry by cause, so this is the intended path rather than a corner.

The consequence is bounded and worth stating so the fix is scoped rather than feared: the store
self-heals, the next run opening warm with null stamps and reloading the partition, and no generated
output is wrong. What is lost is the retry on the one path this item introduces, plus a warn line
that fingers a bug that is not there.

What would satisfy it: make the reconcile decision follow the store's state rather than the open, so
that an attempt meeting a committed partition reconciles it. The predicate this item already computes
is the same fact from the other side, and the item's own invariant section is where the answer
belongs, since "what a run that stops mid-refresh leaves behind" is stated there and this is the
first thing that reads it. A case in the retry's own family, beside
`PersistentStoreTest.aHeldAnchorRowGivesUpFast`, would hold it.

> *Author response, revision 2.* Accepted, and the finding is right about the cause and about where
> the answer goes. `captureWithRetry` no longer hands either attempt the store's warm flag; both ask
> `FactCapture.reconciles`, which is the flag or this graph standing committed in the store, read at
> the attempt rather than at the open. The first attempt's answer is unchanged by construction,
> nothing of this run having committed before it, so the fix costs the unsplit path nothing.
>
> Two things the finding sharpened. The broken thing is an *equivalence*, not a flag: warmth at open
> answered "what will this attempt walk into" only while a capture was all-or-nothing, and it is that
> coupling the split severed rather than the flag going wrong. And the consequence is invisible to
> every assertion over rows or output, the store self-healing next run, so the case is on the
> predicate, in the retry's own family as suggested, beside the lock-timeout case that asserts
> `timedOutOnALock` the same way. It goes one step further than the predicate: it runs the retry with
> what the predicate answered and pins that it does not throw, so the test says both that the
> predicate flips and that the flipped value is the one that works.
>
> The invariant section's "what a run that stops mid-refresh leaves behind" now names the retry as its
> first reader, which is the sentence that would have caught this at the gate.

**Non-blocking, bookkeeping: a departure claims a correction that did not ship.** The second
departure says the population filter "also corrects a claim `RefreshPlanStatisticsTest` carries in
passing, that twelve units is the size at which every registered target holds rows". That claim is
still in the tree, on that test's `UNITS` javadoc, and the new test's own filter is the evidence
against it: three registrations read a target the `@mutation` payload surface leaves empty. Either
correct the javadoc or drop the clause from the departure; leaving both is a record that says a thing
was done and a tree that says it was not.

> *Author response, revision 2.* Fixed the tree rather than the claim, and measured the correction
> instead of hedging it: exactly two registered targets are empty on that fixture at twelve units,
> `intent_mutation_payload_key_membership` and `intent_mutation_payload_refusal`, and they are empty
> at every size because their rules read a `@mutation` payload surface the fixture holds fixed. Both
> claim sites are corrected, the `UNITS` javadoc and `MaterializedRegistryFixture`'s own class
> javadoc, which stated the same property for both gates and is where a later gate would have read it.
> The finding's "three registrations" is the count of *registrations* that read one of those two
> targets, which is what the failing assertion listed.
