---
id: R826
title: "intent_node_id_instruction costs 26 seconds per evaluation, and the fix is stranded on a quickfix branch"
status: In Progress
bucket: model
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-08-24
last-updated: 2026-08-24
---

# intent_node_id_instruction costs 26 seconds per evaluation, and the fix is stranded on a quickfix branch

`intent_node_id_instruction` is named by three view bodies, and one of them, the decode slot,
names it twice through a local alias that is itself named twice, so a single read of the slot
relation expands to several whole evaluations of the rule. Measured against a consumer schema
with 95 classpath sources and a 388-row catalog census, one evaluation is 26 seconds while every
relation it reads answers in under a second: the cost is internal to one union arm, which joins a
local alias to a second local alias derived from the first. H2 inlines a non-recursive `WITH`
exactly like a view and eliminates no common subexpression, so the inner alias is recomputed once
per driving row. What it costs downstream is the point: the decode slot relation does not answer
in 400 seconds, and the decode defect view a build reads sits directly on it, so a consumer with a
schema this size has a build that does not finish.

The fix exists and is not on trunk. It sits on `quickfix/10.0.0-RC34`, which carries three commits
trunk does not have, on a history whose merge base with trunk is now 100 commits back.

## What is stranded

1. Materialize the relation, on the registry's own mechanism: rename the rule to
   `intent_node_id_instruction_live`, declare the canonical name as the target table with its
   column and table comments, and add the `meta_materialize` registration carrying the
   measurement. With the rows stored, the decode slot relation answers in 278 ms.
2. Register `intent_node_id_instruction_live` in `FactCaptureAgreementTest`, which the first
   commit missed.
3. Key `DevExecuteExecutionTest.query_throughTheExecutor_matchesDirectInAppExecution` on films the
   class can name rather than on an unfiltered root field. The subject is byte-equality between
   two execution paths, which an unfiltered field states no better; on the local-db path every
   class in the module shares one PostgreSQL instance and classes run concurrently, so a sibling's
   rolled-back insert is visible to one of the two reads and not the other. The module's
   `junit-platform.properties` already states that hazard, and this is the reader half of its
   remedy. Independent of the first two, and worth taking whatever happens to them.

## Implementation

The branch predates three trunk gates, so this is a port rather than a cherry-pick. Four steps,
and only the first is the branch's own work.

### 1. Land the three commits

`8c56f05`, `11422c6` and `68a2eb2` on `quickfix/10.0.0-RC34`, in that order. Two conflict, both
keep-both:

- The model DDL conflicts in the tail of the `meta_materialize` INSERT, where trunk and the branch
  each appended a registration. Keep trunk's three (`intent_field_reference_step_hop_live`,
  `intent_errors_field_live`, `intent_carrier_data_field_live`) and this one, minding the
  terminator on the last row.
- `FactCaptureAgreementTest` conflicts the same way, one line each. Keep both. The canonical name
  is already registered as `DERIVED` at line 438; what the branch adds is the `_live` view, which
  is a newly generated jOOQ relation and would otherwise arrive unclassified, since that test's
  driver fails on any relation without a registered agreement source. A registered materialization
  is two relations under one rule and the arm's own javadoc already says both are `DERIVED`.

The third commit applies clean and is independent of the other two.

Nothing else in the model needs a hand edit. `meta_materialize_dependency` is derived at boot by
`MaterializeDependencies`, the family rosters are prefix-derived, and the reference pages render
from `meta_` rows rather than from an authored enumeration. The target table's column list already
matches the view name for name in order, which is what
`MaterializeRegistryGateTest.targetsAreShapedLikeTheViewsThatFillThem` asks.

### 2. Roster the index exemption

`MaterializeRegistryGateTest.everyTargetIsIndexedOrStatesWhyNot` asserts by equality in both
directions that every registered target carries a declared index or sits on the exemption roster.
The stranded DDL declares neither, so the gate fails until a row argues it in.

The argument is a reader shape rather than a measurement, which makes this entry different in kind
from the four already rostered. All three readers drive from this relation and join outward, so
they scan it whole and never probe it by key: there is no coordinate an index could serve, so no
index shape is a candidate to measure. Say that, rather than borrowing the measured-and-declined
phrasing of its neighbours.

R827 moves this roster out of the Java set and into a relation of its own. The two items are
independent and either order works: if R827 has landed first, the row goes into the model relation
instead, with the same argument. Neither blocks the other, and this item should not wait.

### 3. Re-price the read-cost matrix

`DerivedReadCostTest` pins its domain deliberately so a new registration fails until somebody has
priced its cells. Both axes come off the booted store, so the figures are read from the run rather
than computed by hand: run `mvn test -pl :graphitron -Plocal-db -Dtest=DerivedReadCostTest` and
re-pin from what it reports. What to expect of each figure, because a surprise in one of them is
itself a finding:

- `READERS_IN_SCHEMA` should not move. The rename turns one view into a table and introduces one
  new `_live` view, so the view count is net neutral. Movement here means something changed that
  this item did not intend, and wants explaining before it is re-pinned.
- `READERS_WITH_CELLS` moves if the new `_live` view's own derivation reaches a registered target,
  which it does, the rule reading relations that are themselves registered.
- `CELLS` grows by the views whose derivation reaches the new target, plus the registrations the
  new `_live` view reaches.
- `KNOWN_NON_MONOTONIC` already pins `intent_argument_scope_table|intent_node_id_decode_slot`, and
  the decode slot is precisely the reader this registration exists to rescue. Its plan changes
  when the instruction relation becomes a table, so that row may stop being a regression. The set
  is asserted by equality in both directions, so if it has, the row is deleted rather than left
  standing. Check it deliberately; do not only read off the new failures.
- A genuinely new non-monotonic pair is an index question first. Answer it on measurement the way
  the existing rows were answered, and pin it only once a lever has been tried and declined.

### 4. Re-measure the registration comment

The comment states measured figures as fact, and they were taken before trunk declared five new
indexes, one of whose comments names this relation as a reader. Re-take them on the tree they land
in, by the `store-performance` procedure: time each relation in isolation against a populated
store, read `EXPLAIN ANALYZE` scan counts, and run a same-fixture control before believing any of
it. Three figures to re-take: the rule's own evaluation cost, the decode slot's cost with and
without the rows stored, and the refresh cost this registration adds per capture.

The internal cost is a per-driving-row recomputation of a local alias, which no index on an input
touches, so the registration should still pay. If the measurement says otherwise, that is the
finding and it belongs in this item rather than in a footnote: the premise would have changed, not
a detail.

## Tests

No new test. The claim this change makes is already gated, and the item's own work is largely the
re-pinning those gates force:

- `MaterializeRegistryGateTest`: the register closes against the schema, the target is shaped like
  its view, the derived dependency rows admit a refresh order, and the index roster holds nothing
  but what is argued in.
- `FactCaptureAgreementTest`: the `_live` view cannot arrive unclassified.
- `DerivedReadCostTest`: no registration makes another relation's read more expensive.
- `MaterializationOrderTest`: the refresh order respects the dependency edges derived at boot. The
  new registration's edges come from the stored view definitions, so this should pass untouched; a
  failure here means a cycle, which is a registration error rather than a figure to re-pin.
- `FactSchemaGateTest`: comment coverage on the new table and every one of its columns.
- `SurfaceScanCountTest` and `DiagnosticsStatementCountTest` in `graphitron-lsp` hold ceilings over
  reader surfaces. This registration should move them down or leave them alone; a breach is a real
  finding, not a ceiling to raise.
- The execution-tier fix is verified by `graphitron-sakila-example`'s own run, and its point is
  that the run stops depending on what sibling classes did to the shared database.

Verification is the full `mvn install -Plocal-db`, not a scoped build: the change is in the model
that every downstream module reads.

## Alternatives the branch already ruled out

Recorded so the Spec does not re-run them: widening the inner alias to carry the outer one's
columns measured 94 seconds, driving the arm from the inner alias measured 66 seconds, and
spelling the join's null-safe comparisons as `IS NOT DISTINCT FROM` changed nothing. Snapshotting
the inner alias into a table put the arm at 0.7 seconds, which is why this is a registration
rather than a rewrite.

## Out of scope

**Rewriting the rule.** The alternatives above were measured and lost to the registration. This
item stores the rule's rows; it does not restate the rule.

**The narrower registration.** Refresh here is one evaluation of the rule per capture, the most
expensive refresh in the registry. The registration that would cut it is the inner alias rather
than the whole rule, and that alias is local today; promoting it to a named relation is what would
make it registrable. Worth its own item once this lands, and filing it is not this item's job
either.

**The input-site gap.** The relation still cannot enumerate an input field carrying its own
`@reference` path, for the reason its comment already gives: the target views resolve a path from
a type's table binding and an input type has none. Materializing changes nothing about that, and
closing it wants an input-site target view.

**R827.** The exemption roster's home is that item's contract. This one adds a row to whichever
form exists when it lands.

## Retired vocabulary

None, and that is worth stating rather than omitting, because a rename usually implies some. The
registry's mechanism is invisible to readers by construction: the canonical name every existing
reader already spells is the name the target table takes, and what gets renamed is the view
stating the rule. No consumer spelling changes, no reader is edited, and the rule is still written
exactly once. The only new spelling is `intent_node_id_instruction_live`, and its own comment
tells a reader not to reach for it.

## Open questions for the reviewer

1. **Is the exemption the right answer, or should the reader shape change instead?** The argument
   for exempting is that all three readers drive from this relation, so no index has a coordinate
   to serve. The alternative reading is that a relation three readers all scan whole is a relation
   whose readers should be probing it by key, and that the exemption records a shape worth fixing
   rather than a fact worth accepting. This item takes the first reading. If the second is right,
   the exemption row should say so, and there is a follow-up item to file.

2. **Should the re-measurement gate the port, or follow it?** As written, step 4 re-takes the
   figures before the comment ships, which is what keeps the comment honest but puts a consumer
   schema of real size on the critical path of a fix for a build that does not finish. The case
   for landing on the branch's figures and re-measuring after is speed; the case against is that
   the registry's comments are the only record of why each registration exists, and one carrying
   stale figures is worse than one carrying none.

3. **Is priority 2 right?** The failure mode is a consumer build that does not finish, which reads
   more urgent than the priority-2 neighbours. Step 3 is the part with unknown cost; if that is
   what holds the item, the third commit is independent and could land on its own first.

## Reviewer findings

### Round 1, Spec -> Ready, sign off

Reviewer session `session_01PXfXUgERb8cqaWW1QKuCUM`, 2026-08-24.

No findings on either gate question. Both are answered, and every claim the plan makes about code
was checked against the tree rather than taken on the plan's word. Recorded here only because the
plan asked the reviewer three questions, and a Ready spec carrying three unsettled forks is not
handed off. The answers are the reviewer's, not plan prose; the implementer is free to reopen the
item if any of them turns out wrong under the measurement.

**Open question 1, the index exemption: exempt, and no follow-up item.** The alternative reading
does not survive contact with the three readers. Each of `intent_node_id_decode_endpoint`,
`intent_node_id_decode_slot` and `intent_node_id_encode` names this relation in its own driving
`FROM` and joins outward from it; the slot reaches it through the `rooted` local alias, which is
where its second evaluation comes from, and that alias is itself the driving side of both its
union arms. So there is no outer relation probing in, and no coordinate an index could serve. That
is a property of what the relation is, the population those three views each partition, rather than
a shape somebody chose and could choose differently: a reader whose grain is one row per instruction
drives from the instructions. Write the argument as the plan states it.

**Open question 2, does the re-measurement gate the port: gate it, as written.** The registry's
comments are the only record of why each registration exists, and this one would be shipping figures
taken before `ix_argument_scope_table_coordinate` was declared, whose own comment names this
relation as a reader in three arms. Figures from before that index are figures about a different
tree, and a comment that states them as fact is worse than one that states fewer. The speed argument
is already answered inside the plan: the third commit is independent, applies clean, and can land
first, so the fix that is not waiting on a measurement does not wait on one.

**Open question 3, priority: 2 is right, leave it.** Priority 2 is the top of the band anything is
actually worked in here. The three priority-1 items on the roadmap are all Backlog, so promoting
this one would place it beside work nobody has started and buy no ordering it does not already have.
It is the highest priority carried by anything in Spec today.

**Non-blocking, no response wanted.** The merge base is 105 trunk commits back rather than the 100
the plan states. The number is making a point about distance and drifts every time trunk moves, so
it is better left round than pinned.

