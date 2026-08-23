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

Two of those rows have a consumer attached and the rest do not, so the table above is the measurement
and this is the claim.

`intent_field_reference_step_target`, the largest of them, is read from main source in two places:
`ClaimFacts` in `graphitron-lsp`, which is an editor surface, and `SchemaQueries` in `graphitron-mcp`.
Both read it filtered to one coordinate, `(graph_name, type_name, field_name)`, which is what makes
this row the consumer claim rather than a schema-internal figure. A separate measurement prices that
exact shape: the coordinate-filtered read costs 19260 scans, which is the same figure as the
unfiltered read of the whole relation, to the scan. The predicate prunes nothing. After indexing it
costs 747, again identical to the whole-relation figure. So one hover on a field carrying a
`@reference` pays the entire relation today and would pay a twenty-sixth of it, and the same holds for
the MCP tool that reports a field's reference path.

That identity is worth reading carefully, because it corrects the mechanism this item and the
architecture doc both assumed. The outer predicate is not being lost; it never applied. A recursive
term cannot be pruned by a predicate outside it, which is a rule `fact-model.adoc` already states for
window functions and recursion, and `intent_field_reference_step_target` is both. What the index
changes is inside the recursion: the step's join against the hop table becomes a seek instead of a
full scan of it, once per iteration. So this is not a reader that was pruning cheaply and stopped. It
is a reader that was never pruning, over a target that gave the planner nothing to work with.

`intent_node_id_decode_defect` is the second, read on the build path through
`NodeIdDecodeDefects.detect`. Its improvement is the modest one, 1628 scans to 1323.

`intent_node_id_decode` has no consumer, and an earlier draft of this section wrongly claimed it was
the relation the editor's node-id diagnostics reach. Nothing reads it: not `graphitron-lsp`, not any
main source, and no other view in the schema names it. Its own view comment says as much, that it
carries no registration because nothing on the build path reads it yet and the sibling read there is
the defect relation. Its row stays in the table as a schema-internal measurement, on the reasoning
that the relation is the deepest derivation in the schema and a future reader inherits the win, but it
carries no part of the consumer case. `intent_field_column_scope_live` and
`intent_argument_scope_table_live` are the same: real improvements to relations other views read,
with no main-source reader of their own.

Wall clocks moved with scan counts in every run, but they are single samples on one machine at a
fixture size chosen for the gate rather than for realism, so they belong here as a direction and not
as figures to pin.

## What was measured, and what it refutes

The three non-monotonic pairs reproduce exactly: 19260 against 598, 20779 against 2117, 2938 against
1269, which is where this item's original table came from. What the probe added was the third shape,
the registered target with an index on it. All three pairs improve by most of their gap, and the
binding pair crosses over and becomes monotonic outright at 952 against 1269.

This refutes the causal claim this item was filed with, and the one the architecture doc states.
`docs/architecture/explanation/fact-model.adoc` says that materializing means "every relation whose
derivation names that target now plans against a full scan of it instead of against the rule the
planner could prune", and this item read that as pointing the lever at the harmed reader, away from
the usual direction. Both are one step short, and the coordinate measurement above says where.

The doc's sentence locates the loss in the reader's own predicate, as if a reader that used to prune
the rule now cannot. For `intent_field_reference_step_target` that reader never pruned: its
coordinate-filtered read and its whole-relation read cost the same number of scans in both shapes,
because a recursive term is not prunable from outside whatever the target underneath it is. What is
actually lost is inside the derivation, where the recursive step joins the hop relation once per
iteration. Against the rule, that join reaches base relations that are keyed, every one of them.
Against the target, it reaches a heap and scans all of it, every iteration.

So the lever is underneath the reader, which is the ordinary direction, and no reader has to be
restructured to reach it. The correction the doc needs is sharper than "conditional on the target
being unkeyed": the cost lands on the joins inside the derivation rather than on the reader's
predicate, and that is why it is invisible at the call site and why an index answers it.

## Two mechanisms, and the second is the surprising one

The gain splits in two, and the split is worth stating because only the first half survives a
capture on its own.

An index alone takes `intent_field_reference_step_target` from 19260 scans to 2431. Running `ANALYZE`
afterwards takes it from 2431 to 747. The two were separated directly: the same store, the same rows,
one `ANALYZE` between the two measurements and nothing else.

The second half does not persist. A refill of the targets puts the figure back to 2431, so the
statistics have to be current *after* the last refill for the planner to use the index fully, and
today nothing in the tree runs `ANALYZE` at all. That makes it a change to the refill path rather
than to the schema.

There are two refill paths, not one, and the difference matters enough to name here rather than
leave to the task. `Materializations.refresh` runs per graph inside capture's transaction, on every
capture. `Materializations.refreshAll` is the other, and `DevMojo` calls it once on dev-session open,
with a comment saying it exists precisely so that a warm store whose capture was skipped does not
serve the language server and MCP stale rows. Those two paths serve different readers, and the
surfaces with a consumer attached are reached through both: the build path through capture, the
editor and MCP reads through the dev session.

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

. *Give the planner current statistics after a refill, on both refill paths.* `ANALYZE` after the
  last target is refilled, in `Materializations.refreshAll` and in `Materializations.refresh`. Both,
  because a path left out leaves its own readers on stale statistics and each path has readers the
  other does not, and the two costs are different enough to decide separately rather than together.
+
  `refreshAll` is the cheaper case to argue and should land first: it is paid once per dev session
  rather than per capture, the store it serves is read-only afterwards, and its readers are exactly
  the editor and MCP surfaces task 4 re-measures. `refresh` is the one with a real cost to weigh,
  running inside capture's transaction on every capture for every consumer. Measure that path both
  ways and take the gain only if the measurement earns it; declining it is a legitimate outcome, and
  it means a captured store's readers get the index without the statistics, which is still the
  larger half of the gain. Say which paths ended up with `ANALYZE` and what each cost.

. *Close the gate on the schema.* `MaterializeRegistryGateTest` already closes the register against
  the schema for kinds, column lists, acyclicity and refresh order, reading `INFORMATION_SCHEMA` over
  a booted store, so an index claim extends it without new machinery. The claim is that every
  registered target carries an index *or* a row in a pinned roster saying why it does not, on the
  model of that test's own `HAND_WRITTEN` set, asserted by equality the way this family's other
  rosters are. Not a bare "every target carries an index": task 1 expects two targets may have no
  reader that justifies one, and a bare claim would force an index nothing wants. Equality is what
  keeps the roster honest, since a target that later earns an index fails the test until its row
  goes.

. *Re-tighten what goes slack.* `SurfaceScanCountTest`'s ceilings in `graphitron-lsp`
  (`INLAY_CEILING`, `INLAY_PER_DECLARATION_CEILING` and the rest) are `isLessThan` bounds over
  surfaces that read these targets. Lowering the figures underneath them leaves the ceilings loose by
  whatever the improvement was, and a loose ceiling is a weakened gate. Re-measure and re-tighten.

. *Correct the architecture doc.* The passage quoted above states the full-scan consequence as
  intrinsic to materializing, and locates it in the reader's own predicate. Both need fixing, per the
  refutation section: the consequence is conditional on the target being unkeyed, and the cost lands
  on the joins inside the derivation rather than on a predicate the reader stopped being able to push.
  The doc is where the depth rule's readers will look, and the version now standing would send them
  at the wrong half of the query.

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
read per refused row has its own item. The recursive-view read per conflict row in
`AuthoredClaimConflicts.fieldGrain` is recorded nowhere at all: an earlier draft of this section said
it was recorded on the class, and no javadoc or comment there mentions the cost. The read is real and
`intent_authored_field_claim` is a recursive view, so it wants a Backlog stub of its own rather than a
sentence here. Indexing the targets may well make both cheaper without making either correct, and a
cheaper N+1 is still an N+1.

## Loose end worth an answer

`intent_node_id_decode_hop_column` holds no rows on this fixture, at four units or twelve. That makes
one of the seven registrations' cells in `DerivedReadCostTest` a comparison between two readings of an
empty table, and it sits against that test's own javadoc, which says every registered target is
populated by this schema and excepts only the defect relations. Either the fixture does not reach the
shape that populates it, in which case the gate covers six registrations and not seven and should say
so, or it should be made to. Small, and adjacent enough to belong here rather than in an item of its
own.

## Reviewer findings

### Round 1, Spec -> Ready

Revisions requested. Two findings, one against each gate question. The measurement work behind this
spec holds up: the seven unkeyed tables are exactly the seven materialized targets and exactly the
tables in the schema with no key, the other 145 declare a primary key, the two targets named as
accepting one are the only two whose every column is `NOT NULL`, nothing in the tree runs `ANALYZE`,
the quoted `fact-model.adoc` passage is verbatim, the pinned pairs and the twelve-unit fixture are as
described, and every symbol the spec names exists under that name. The findings are about what the
spec claims for a consumer and about where the second task lands, not about the numbers.

**Question 1, the consumer claim.** `intent_node_id_decode` is presented as one of "the ones a person
waits on" and as "the relation the editor's node-id diagnostics reach". Nothing reads it. There is no
reader of it in `graphitron-lsp`, no reader in any main source, and no other view in the schema names
it; the only readers in the tree are three tests. Its own view comment says so directly: "It carries
no registration of its own because nothing on the build path reads it yet; the sibling that is read
there is `intent_node_id_decode_defect`". So the largest of the two figures the section rests its
user-visible case on, 9543 scans to 6551, reaches nobody today, and a reader working from this spec
would believe an editor surface gets faster when it does not.

The item is not sunk by this. `intent_node_id_decode_defect` genuinely is on the build path through
`NodeIdDecodeDefects.detect`, and the LSP surfaces `SurfaceScanCountTest` bounds genuinely read these
targets, which is why task 4 exists. What would satisfy the finding is restating the section against
the surfaces that actually reach the targets: keep the decode figure if it is worth keeping, as a
schema-internal measurement with no consumer attached and a note that a future reader inherits the
win, and let the build path and the LSP surfaces carry the consumer claim on their own.

**Question 2, the fit of task 2, and a smaller fork in task 3.** Task 2 places `ANALYZE` at the end of
`Materializations.refresh`. That is one of two refill entry points. `Materializations.refreshAll` is
the other, and `DevMojo` calls it on session open with a comment saying it exists precisely so the
language server and MCP are not served stale materialized rows. Since the spec's own second mechanism
is that a refill puts the statistics back, `ANALYZE` in `refresh` alone leaves the dev-loop store's
statistics stale for exactly the surfaces task 4 re-measures, and an implementer reading the plan
literally would ship that. The cost argument the task gives, "it runs inside capture's transaction, on
every capture, for every consumer", is the capture path's cost; `refreshAll`'s is a different one, paid
once per dev session rather than per capture, and it is the cheaper of the two to argue. Naming both
paths and saying which gets `ANALYZE`, with the cost stated per path, is what would satisfy this.

The smaller fork is in task 3. The gate claim as worded is that a registered target carries an index,
while task 1 says every index wants a reader to justify it and expects `intent_argmapping_pair` and
`intent_node_id_decode_hop_column` may end up with none. Those two cannot both hold. The implementer
would have to either index a target no reader justifies or add an exemption roster the spec does not
mention, and `MaterializeRegistryGateTest`'s own `HAND_WRITTEN` set is the precedent sitting right
there for the second. Say which, since it decides whether the mechanism that stops the defect
returning is satisfiable as stated.

**Non-blocking, noted rather than asked for.** "What this does not touch" says the recursive-view read
per conflict row in `AuthoredClaimConflicts.fieldGrain` "is recorded on the class". It is not: the
per-row read is real, and `intent_authored_field_claim` is indeed a recursive view, but no javadoc or
comment in `AuthoredClaimConflicts` mentions the cost. So that defect is currently recorded nowhere at
all rather than recorded and unfiled. Nothing here needs to change in the plan; it may be worth a
Backlog stub.

Status stays Spec.

### Author response, round 1

Both findings accepted and the plan body revised. Each was verified against the tree before revising
rather than taken on the reviewer's word, and both hold exactly as stated.

*Finding 1.* Confirmed and worse than the finding puts it. `intent_node_id_decode` has no reader:
the two main-source hits are `{@code}` javadoc mentions in `ServiceCatalog` and `NodeIdDecodeDefects`,
no view body names it, and the only readers by jOOQ constant are three tests. The aggravating part is
that the Backlog body this spec replaced already said "intent_node_id_decode has no Java reader", in a
section titled for exactly this question, and the Spec draft overwrote that correct sentence with a
claim in the opposite direction. Not a gap in what was known; a regression in it.

The section is restated, and the restatement is stronger than the claim it replaces rather than a
retreat from it. `intent_field_reference_step_target` is read from main source by `ClaimFacts` in
`graphitron-lsp` and `SchemaQueries` in `graphitron-mcp`, both filtered to one coordinate, so the
finding sent the consumer case to a reader that is real and is the largest figure in the table. That
prompted the measurement the spec was missing: the coordinate-filtered read costs the same 19260
scans as the unfiltered one, to the scan, and the same 747 after indexing. The predicate prunes
nothing, before or after.

That result also corrects the mechanism this spec asserted in its own refutation section, which had
the harmed readers losing a pushdown they turn out never to have had. The cost is in the recursive
step's join against the heap, not in the reader's predicate, which is a sharper claim than the one
that went into review and changes what task 5 tells the doc to say.

*Finding 2.* Confirmed. `DevMojo` line 304 calls `refreshAll` on session open under a comment about
not serving the language server and MCP stale rows, and `refresh` is the per-capture path. Task 2 now
names both, orders them, and states the cost per path: `refreshAll` first as the cheaper case and the
one serving the surfaces task 4 re-measures, `refresh` second with its per-capture cost to be measured
and legitimately declinable. The smaller fork in task 3 is resolved toward the roster the finding
points at: the gate claims an index *or* a pinned row saying why not, on `HAND_WRITTEN`'s model,
asserted by equality.

*Non-blocking note.* Confirmed and corrected in place; the only "cost" in `AuthoredClaimConflicts` is
about emitted source. The sentence now says the defect is recorded nowhere and wants its own stub,
which is filed separately rather than folded into this item.
