---
id: R815
title: "Index the materialized targets, the only unkeyed tables in a keyed schema"
status: Spec
bucket: store
priority: 2
theme: nodeid
depends-on: []
created: 2026-08-23
last-updated: 2026-08-23
---

# Index the materialized targets, the only unkeyed tables in a keyed schema

Seven relations in the fact schema are materialized: a rule lives in a view and a table beside it
holds what the rule computed, under a registration in `meta_materialize`. Those same seven are the
only tables in the schema with no key on them. The other 145 declare a primary key; these declare a
foreign key on `graph_name` and nothing else, so every read of a target is a full scan of it.

That is the whole of why three registrations make some other relation's read more expensive, which is
the symptom this item was filed for. It is not a property of materializing. It is a property of
materializing into a heap.

## What changes for a consumer

Every reader of every materialized target gets cheaper, not only the three that got worse. The three
pairs are how the defect was found rather than the extent of it.

Measured on the gate's own twelve-unit fixture, indexing the targets and giving the planner current
statistics:

[cols="3,2,2,2"]
|===
| Relation | Today | Indexed | Ratio

| `intent_field_reference_step_target`
| 19260 scans
| 747
| 26x

| `intent_field_column_scope_live`
| 20779 scans
| 2543
| 8x

| `intent_argument_scope_table_live`
| 2938 scans
| 952
| 3x

| `intent_node_id_decode`
| 9543 scans
| 6551
| 1.5x

| `intent_node_id_decode_defect`
| 1628 scans
| 1323
| 1.2x
|===

The last two are the ones a person waits on. `intent_node_id_decode_defect` is read on the build path
through `NodeIdDecodeDefects.detect`, and `intent_node_id_decode` is the deepest derived read in the
schema and the relation the editor's node-id diagnostics reach. Their wall clocks moved with their
scan counts in every run of the probe, the decode from around 370 ms to around 220 and the defect
relation from around 160 to around 80, though those are single samples on one machine and belong in
this item as a direction rather than as figures to pin.

## What was measured, and what it refutes

The three non-monotonic pairs reproduce exactly: 19260 against 598, 20779 against 2117, 2938 against
1269, which is where this item's original table came from. What the probe added was the third shape,
the registered target with an index on it. All three pairs improve by most of their gap, and the
binding pair crosses over and becomes monotonic outright at 952 against 1269.

This refutes the causal claim this item was filed with, and the one the architecture doc states.
`docs/architecture/explanation/fact-model.adoc` says that materializing means "every relation whose
derivation names that target now plans against a full scan of it instead of against the rule the
planner could prune", and this item read that as pointing the lever at the harmed reader, away from
the usual direction. Both are one step short. The planner cannot prune the target because there is
nothing on the target to prune with; the rule it replaced was prunable because the rule's own base
relations are keyed, every one of them. Give the target a key and the pruning comes back. The lever
is underneath the reader after all, which is the ordinary direction, and no reader has to be
restructured to reach it.

## Two mechanisms, and the second is the surprising one

The gain splits in two, and the split is worth stating because only the first half survives a
capture on its own.

An index alone takes `intent_field_reference_step_target` from 19260 scans to 2431. Running `ANALYZE`
afterwards takes it from 2431 to 747. The two were separated directly: the same store, the same rows,
one `ANALYZE` between the two measurements and nothing else.

The second half does not persist. A refill of the targets, which is what `Materializations.refresh`
does on every capture, puts the figure back to 2431. So the statistics have to be current *after* the
last refill for the planner to use the index fully, and today nothing in the tree runs `ANALYZE` at
all. That makes it a change to the refresh path rather than to the schema, which is the part of this
work with a cost to argue: it runs inside capture's transaction, on every capture, for every
consumer.

## What a primary key cannot do

The obvious move is to declare each target's grain as a primary key, which would document the grain
and index it in one stroke, and match what the other 145 tables do. It does not work, and the reason
is worth writing down so nobody spends the afternoon.

Every one of the seven grains is genuinely unique. Checked on the fixture at four units and at
twelve, row count equals distinct count on the candidate key for all seven. But five of the seven
grains include a column that is nullable and meaningfully so: a `KEY` hop names a constraint where a
`NAME_MATCH` hop cannot, an unqualified spelling has no namespace half, and H2 refuses a primary key
over a nullable column. Only `intent_resolved_type_binding` and `intent_field_column_scope` accept
one.

So the index is an index, declared beside the target it serves. Whether it should also be `UNIQUE`
is a real question and this spec does not settle it: H2 counts nulls as distinct in a unique index,
so on the five nullable grains a unique index would document the grain without wholly enforcing it,
which may still be worth more than a plain index. The implementer decides that per target and says
why in the DDL comment.

## Plan

. *Index the seven targets.* One or more indexes per target in the schema file, each on the columns
  a named reader actually joins that target on, with the reader named in a comment. The probe used
  seven indexes across five targets, chosen by reading the joins, and that set is a starting point
  and not a result: `intent_argmapping_pair` and `intent_node_id_decode_hop_column` got none and
  should be looked at on their own readers. Every index is a cost on refresh and wants a reader to
  justify it.

. *Give the planner current statistics after a refill.* `ANALYZE` at the end of
  `Materializations.refresh`, or a reasoned decision not to, with the refresh cost measured both
  ways. This is the half of the gain that does not survive a capture without it, and the half that
  touches every consumer's build. If the measured cost does not justify the gain, take the first task
  alone and say so; the first task stands without this one.

. *Close the gate on the schema.* `MaterializeRegistryGateTest` already closes the register against
  the schema for kinds, column lists, acyclicity and refresh order. Add the claim that a registered
  target carries an index, so the next registration cannot land as a heap. This is what stops the
  defect coming back, and it is the reason this item is a schema change rather than seven index
  statements.

. *Re-tighten what goes slack.* `SurfaceScanCountTest`'s ceilings in `graphitron-lsp`
  (`INLAY_CEILING`, `INLAY_PER_DECLARATION_CEILING` and the rest) are `isLessThan` bounds over
  surfaces that read these targets. Lowering the figures underneath them leaves the ceilings loose by
  whatever the improvement was, and a loose ceiling is a weakened gate. Re-measure and re-tighten.

. *Correct the architecture doc.* The passage quoted above states the full-scan consequence as
  intrinsic to materializing. It is conditional on the target being unkeyed, which is a sharper and
  more useful claim, and the doc is where the depth rule's readers will look.

## What the gate will do when this lands

`DerivedReadCostTest`'s pinned set is asserted by equality, so a pair that stops being non-monotonic
fails the test until its row goes. The binding pair goes: at 952 against 1269 it is monotonic.

The other two do not, and that is the honest reading of the measurement rather than a shortfall to
argue away. At 747 against 598 and 2543 against 2117 they are still non-monotonic, at about 1.2x
where they were 32x and 10x. That is plausibly the instrument's per-naming floor, which is the
category the pinned set already has three members of, and it may equally be a residue of real cost.
The implementer must not reclassify them by assertion: show the residual flat in schema size, the way
the current rows were shown to grow with it, before moving them into the floor group, and leave them
where they are with the new figures recorded if it is not flat.

## What this does not touch

The two per-row read defects found alongside these figures stay where they are. The `keyColumnsOf`
read per refused row has its own item, and the recursive-view read per conflict row in
`AuthoredClaimConflicts.fieldGrain` is recorded on the class and filed nowhere. Indexing the targets
may well make both cheaper without making either correct, and a cheaper N+1 is still an N+1.

## Loose end worth an answer

`intent_node_id_decode_hop_column` holds no rows on this fixture, at four units or twelve. That makes
one of the seven registrations' cells in `DerivedReadCostTest` a comparison between two readings of an
empty table, and it sits against that test's own javadoc, which says every registered target is
populated by this schema and excepts only the defect relations. Either the fixture does not reach the
shape that populates it, in which case the gate covers six registrations and not seven and should say
so, or it should be made to. Small, and adjacent enough to belong here rather than in an item of its
own.
