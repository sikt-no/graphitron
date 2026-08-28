---
id: R876
title: "Expensive derived reads are mostly a shape defect, and materialization has been the first lever reached for instead of the last"
status: Spec
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-28
last-updated: 2026-08-28
---

# Expensive derived reads are mostly a shape defect, and materialization has been the first lever reached for instead of the last

Every performance decision the fact model has taken has asked one question: should this rule be
stored or recomputed. That framing admits two answers, and `meta_materialize` is the record of
choosing "stored" twenty-two times. It never asks the prior question, which is why one evaluation of
the rule is expensive at all. On every relation measured against a real consumer schema, the answer
is almost never that the rule is inherently costly. It is a grain the capture family never wrote, so
views reconstruct it from multi-arm unions that H2 expands once per path through the dependency
graph; or a join key that exists only as an expression, which no index can serve. Both are fixable at
the source for milliseconds, and both leave a registration with nothing left to buy.

This item takes over the performance narrative from nine dissolved items and states the lever
ordering the fact model should have been using. The evidence is in
`roadmap/audits/2026-08-28-derived-read-cost-premise.md`, which is filed as an audit precisely so it
outlives this file.

## What changes when this lands

A capture of a real consumer schema stops costing hours. Today it costs four hours and nineteen
minutes on the measured store, and the cold-refresh split that has since landed removes about 6172
seconds of that while leaving over 7126, so the problem is not closed by anything on the tree. The
target is a capture in the tens of seconds, reached by fixing shapes rather than by storing more
rows, so `mvn graphitron:validate` and `mvn graphitron:dev` become usable on that schema and
`graphitron:dev` reaches its language-server and MCP binds, which sit behind the capture.

Nothing a reader asks the store changes: the same relations, the same rows, under the same names.

## Vocabulary

Carried from the dissolved cut-set item, because the subject needs it and the file that held it is
gone.

A **derived relation** is a view: a rule stated once in SQL and evaluated whenever a reader names it.
A **registration** is a row of `meta_materialize` keeping the rule in a view under a `_live` name and
moving the canonical name every reader spells onto a table, which the **refresh** refills once per
capture. A registration is paid for twice: **read cost** is what it buys, the evaluations that no
longer happen because readers meet a table, and **refresh cost** is what it charges, the one
evaluation the materializer pays per capture. A relation read once between writes gains nothing from
a registration; a relation read zero times gains nothing at all and still pays.

A **grain** is the unit of fact a relation states one row of. A grain is **missing** when no captured
table states it and views reconstruct it, typically by unioning per-directive sibling tables.

## What is established

Stated compactly; the audit carries the tables, the method and the provenance of each figure.

- **The refresh is 15477.1 seconds and five of twenty positions carry 99.4% of it.** One position is
  half the pass and inserts 545 rows. No target is large, so nothing here is expensive for the volume
  it produces.
- **Ten of the twenty registered targets are unreachable from anything that reads the store, and they
  carry 95.4% of the pass.** They are not orphans: their readers are each other plus seven further
  `intent_` views, none of which is reached either. It is a closed subgraph with no exit, and it is
  the mutation write surface and the input-filter surface, which are under construction.
- **Two missing grains, 313 rows and 108 rows, cost 0.05 and 0.04 seconds to build and take the worst
  plan in the stratum from 235756 lines to 89296.** Five relations that had not completed in 120
  seconds complete, with nothing materialized.
- **One expression-keyed join, fixed by a stored computed column plus an index, goes from over 120
  seconds to 1.84 seconds** at 21287 rows either way.
- **Materialization kept winning because it was the only candidate on the ballot, and because a
  registration silently delivers an index** that a view cannot carry. That is why demoting the
  register looks catastrophic and why it reads as load-bearing.
- **Statistics were necessary and are not sufficient.** The cold-refresh split is 69-fold across
  positions 1 to 16 and about 1.2-fold on the tail that now dominates.

## How this stands against the set-level pricing of the register

R848 priced the register as a set and reached Done on 2026-08-28, so its file is gone and its result
lives in `roadmap/changelog.md` under its own entry, which is where a reader should be sent for the
figures. It concluded that all twenty registrations earn their place, six by three or four orders of
magnitude, with the four-shoulder alternative coming out 720 times worse as a set. This item has to
say how the two results sit together, because on a first reading they look opposed and they are not.

**They measure different quantities.** R848's harness removes a registration and reports the refresh
saved against the read time its readers lose. This item walks the view graph outward from the 39
relations any consumer actually names and reports whether a target is reachable at all. The first
asks what a registration is worth to the relations above it. The second asks whether anything above
it is ever demanded. Both results hold, and for ten targets both hold at once: the readers exist,
they do get much more expensive when the registration goes, and no consumer is ever waiting on any of
them.

**That entry contains the seam itself.** It records that `intent_argument_column_scope` "has no
reader that is not itself a registered source view, so removing it moved nothing this instrument can
see", and lets that row stand on the pre-committed rule rather than on a found value. That is this
item's finding, met at one relation, by an instrument with no notion of reachability. The walk says
the same thing about ten.

**And its own Done gate bounded it on population.** The entry states that the consumer-scale
population was never reached, that every verdict in it is conditional on sakila, and that on a
population whose refresh axis weighs orders of magnitude more, the candidate-C trade would need
re-taking before anyone acts on it there. That is the limit this item would otherwise be raising,
raised first by its own reviewer, and it is why the two results need reconciling rather than
adjudicating. The population it names as unreached is the one measured in the audit: 15477.1 seconds
against about 1317 ms on sakila.

**What is therefore sound and what does not carry.** The harness, the three-capture spread analysis
that reversed four verdicts, the candidate-C result and the pin are real, and this item does not
re-run them; the spread discipline in particular is the right standard for anything measured here and
should be adopted rather than re-derived. What does not carry is one inference, from "the readers of
X get dearer without X" to "X earns its place". That holds only where somebody pays for those reads.
For the ten unreachable targets nobody does, and the same arithmetic that makes them look earned is
what puts 95.4% of a four-hour refresh into filling them.

**One figure worth re-taking rather than inheriting.** Candidate C's cost, 568 seconds across thirty
readers, is summed over readers whose reachability was never checked, so some part of it may be cost
that nothing collects. Slice 0 takes that check, and it is a test of the audit as much as of the
entry.

**What this does not license.** Nothing here says the register is incoherent, and nothing reopens a
Done item. It says the register is coherent against a metric that is the wrong one for half of it, on
a population its own author flagged as the one not measured.

## The lever order

The fact-model page already states a hierarchy. What this item adds is that the top rung was never
actually tried, so the ordering was doctrine rather than practice. In the order to attempt them:

1. **Capture the grain.** If a view reconstructs a unit of fact from a multi-arm union over sibling
   tables, and nothing in those arms derives anything, capture should write the grain directly. It
   needs no refresh, cannot go stale, needs no registry row, and carries indexes for free.
2. **Store the key.** If a join key exists only as an expression, put the value in a column: a
   `GENERATED ALWAYS AS` column when it is a pure function of a neighbouring column, a captured fact
   when the value arrives from outside. Then index it.
3. **Index what is already stored.** Necessary, and never a fix for expansion.
4. **Rewrite the rule.** Only against a measured alternative; see the two refuted rewrites in the
   audit.
5. **Register it.** Last, and only for a relation with a reader, after the four above have been tried
   on it.

Rung 5's precondition is new and it is the one with teeth: a registration is currently made without
anybody asking whether the target has a reader, and ten of twenty do not.

## Implementation

### Slice 0: the two determinations that decide the cut, before any code

Both are reads against the kept 2026-08-27 store, both are minutes rather than hours, and both change
what the rest of this item is. Nothing below is designed until they are answered.

**Is the pair of known grains enough?** Re-take plan sizes and per-relation timings on a DDL carrying
both grains and the cold-refresh split, over the fourteen relations the audit characterised. If the
nine that still exceeded 30 seconds fall under it, this item is two slices and closes. If they do not,
the residual signatures already name where the next grain is, and slice 3 is real work rather than a
contingency.

**How much of candidate C's cost is collected by anybody?** R848 reports 568 seconds across thirty
readers as the price of the four-shoulder set. Re-run this item's reachability walk over exactly those
thirty and report the split. This is a check on the audit as much as on R848: if most of the thirty
are reachable, the unreachable subgraph is smaller than section 2 of the audit claims and this item's
own framing weakens.

Report both as figures whichever way they come out, and take them under the three-capture spread
discipline R848 established, since a single reading is an ordering and not a measurement.

### Slice 1: capture the two known grains

Write the written-table-or-routine reference grain and the argMapping-pair grain as tables, and
repoint the views that reconstruct them from multi-arm unions. This is the largest measured lever and
no dissolved item covered it. The row counts to reproduce are 313 and 108; the anchor is that every
repointed view returns identical rows.

Two things the Spec decides here rather than the implementer: where each grain lands in the
`graphitron_` family, and whether writing it is capture's job or a producer's. The audit's class A
argues capture, on the ground that both grains re-tag rows that were facts on arrival and capture
already holds their provenance.

### Slice 2: store the two known expression keys

The bean-property name as a `GENERATED ALWAYS AS` column on `jvm_method` with an index, and the
authored named type as a captured fact on `graphql_field`. Then repoint the readers. The split is the
audit's rule: a value computed from a neighbouring column is a generated column, a value arriving
from outside is a captured fact.

The named-type site carries a caution from the item it supersedes. A fact on
`graphitron_field_synthesis` would need `COALESCE` at every reader, which is an expression again and
buys none of the plannability, so the fact has to be total on `graphql_field` even though it
duplicates its neighbour on almost every row.

### Slice 3: the remaining grains, scoped by slice 0

Only if slice 0 says the first two are not enough. The residual scan signatures name where to look:
`graphql_field_directive`, `graphql_field`, `graphql_type`, `graphitron_field_node_id`,
`graphitron_argument_lookup_key`.

### Slice 4: repoint the three `intent_` views that re-derive semantics from the SDL

Both layers answer 529 types identically and the semantic tables are an order of magnitude smaller.
The two `authored_*_claim` views and the `@notGenerated` read stay as they are, for the reasons the
audit's section 5 gives.

### Slice 5: re-measure the performance claims written into DDL comments

Every claim in the store's relation comments was taken in the regime the audit's section 4 describes,
and several steer the next author away from a shape. Census which comments make a re-checkable claim
at all before deciding whether any of them should become rows a gate can read.

### Deferred: the registration precondition

Whether a rule earns a `meta_materialize` row before anything reads it. This governs 95% of the
refresh and no other item holds it, but it is a policy question that should not be decided while R848
is in review on an overlapping subject. It waits for R848 to reach Done, and this item's slice 0
result is the input it will need.

### Not in this item: the consumer capture on the shipping DDL

Inherited from the payload-verification item and worth keeping written down, but it needs the machine
that holds the consumer schema and no session working from this repository can take it: `mvn -X` for
the per-registration tier, a pinned store directory, and the discharge rule that a position's name is
emitted before its `DELETE`, so the one that never returns is the one that gets named. Timing
relations against the kept store does not need that machine, which is what slice 0 relies on.

## Tests

The gates that already govern this ground, and what each will say when the slices land:

- `DerivedReadCostTest` refuses a change that costs some reader more than it saves. A grain that
  removes expansion should clear cells rather than add them, and the audit predicts four named
  regressions in `KNOWN_NON_MONOTONIC` become removable.
- `MaterializeRegistryGateTest` holds the index decisions on registered targets. Slice 2's index sits
  on a captured base table, outside that gate's scope, which is the gap the surviving named-type
  index item documents; take its doctrine rather than inventing one.
- `FactSchemaGateTest.everyRelationLeadsWithItsPartitionDimension` needs a case for each new grain
  table.

What this item owes that no gate holds today:

- **Row-identity anchors on every repointed view.** A view that reads a grain table must return what
  it returned when it reconstructed the grain, and the reproduction figures are in the audit: 21287
  rows for the accessor hop, 529 types for the carrier's directive question, 313 and 108 for the two
  grains. This is the one class of defect the whole item can plausibly introduce, and it is cheap to
  pin.
- **A reachability check over `meta_materialize`.** The mechanical form of rung 5's precondition:
  every registered target is reachable from the consumer read set, or states why not. It mirrors
  `everyTargetIsIndexedOrStatesWhyNot` in shape. Whether it ships as a gate is the deferred policy
  question above; the walk itself is slice 0's and can land as a report first.

No wall-clock assertion, for the reason `DerivedReadCostTest` already states: a duration is not a
build assertion, and every timing in this item is research evidence rather than a ratchet.

## What this item does not do

It does not delete the ten unreachable targets or their relations. They are work under construction,
they appear in two to five test sources each, and their registrations may be right the moment their
readers land. The question is when a registration is earned, not whether the relations belong.

It does not reopen the two payload registrations that landed 2026-08-28. They are on the tree, they
are defensible on their own numbers, every gate that governs a registration admits them, and making
the refresh of a target cheaper is a real reduction in a cost the build pays today even when the
target has no reader.

It does not build a consumer-scale fixture. Nothing in this repository captures a schema of that
size, and a fixture that did would be a wall-clock gate, which the build-guardrail item owns.

And it does not promise a scoring function over the register. One was built for the dissolved cut-set
item, failed its own pre-committed gate, and was deleted; the audit records why a static reading of
the view definitions cannot rank the twenty.

## Superseded items

Dissolved against this item and the audit, all on 2026-08-28. Each is named by subject rather than by
id, because the ids become gaps and the audit's section 8 carries what each established.

- The consumer-capture item, whose two registrations had already landed and whose remaining work was
  four transitions to record delivered code. Its framing, that the hour is a refresh cost and the fix
  is two registrations, is this item's premise in its purest form.
- The payload-verification item, filed as the consumer-capture item's escape hatch for an unpaid
  measurement. The measurement survives as a slice above.
- The write-payload read-cost item, whose central question is answered and whose reader-count premise
  is dead, the count being zero across the family.
- The node-id decode-read item, whose lever question this item's ordering answers. Its second half,
  that no gate holds a figure over that read, is an obligation inherited here.
- The field-column-table inlining item, whose remaining question was whether a residual still earns
  work.
- The expression-keyed-join item, which is rung 2 above, filed narrowly against one of the two known
  instances.
- The inline-multiplicity reporter item. Plan size measures expansion directly, so the metric is
  superseded rather than repaired.
- The DDL performance-claims item, now a systematic consequence rather than a single catch.

**Not dissolved, and why.** The two per-refused-row reader items are caller-side loops in Java, not
store shapes, and neither is touched by anything here. The `graphql_field` named-type index item
carries the doctrine for indexing a captured base table, which rung 1 needs rather than replaces. The
`meta_relation_reference` item is a measured, self-contained fix. The view-read census and bridge
closure is a gate, and the layer violation above is live evidence for it. The build wall-clock
guardrail is independent.

## What a reviewer should press on

Three places where this plan is weakest, named so the gate does not have to find them.

**Slice 0 can refute this item's own framing and the plan is written to let it.** If most of
candidate C's thirty readers turn out to be reachable, the unreachable subgraph is smaller than the
audit claims and the lever ordering below rung 5 survives while the argument about the register does
not. That is the right outcome from a measurement and it is why slice 0 leads.

**The evidence is one store, one schema, one investigator.** Every figure in the audit comes from a
single capture of a single consumer schema, and the reachability walk is a static analysis of the
shipped DDL against main sources rather than anything the build checks. It has not been reproduced by
a second party. The two grains and the bean-property column were each measured against a named
alternative, which is the standard the audit sets, but nothing here has the three-capture spread
discipline R848 established, and slice 0 is where that gets applied.

**The reachability walk's soundness rests on one claim about how consumers read the store**, that the
only access forms are jOOQ `Tables.INTENT_*` constants and `model.tables.IntentX`, with no raw-SQL
relation name anywhere in main sources. That was verified by grep. If a third access form exists, or
one arrives later, the walk under-reports reachability and the 95.4% figure moves. A gate over the
access form would make the claim durable rather than point-in-time, and whether that is worth building
is a fair question for the Spec gate.
