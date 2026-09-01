---
id: R899
title: "A registration's alternative is counted from the schema, so the last lever stops being the first one reached for"
status: Spec
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-31
last-updated: 2026-09-01
---

# A registration's alternative is counted from the schema, so the last lever stops being the first one reached for

The fact model has four levers for an expensive derived read, ordered on the fact-model page from
the one that removes work to the one that adds it: capture writes the fact, an index on a stored
column, a rewrite, and last a registration in `meta_materialize`. The order is doctrine and the
register is the record of the doctrine losing. Twenty registrations stand in the schema, and the
reason is mechanical rather than anybody's preference: adding a row to `meta_materialize` costs
three lines and shows up in review, while finding out what the rule costs without one has meant
building an instrument in a scratch directory that dies with the session. A lever nobody can price
is a lever nobody reaches for.

This item makes the alternative countable from the repository. Not the wall clock, which needs a
captured consumer store and correctly stays outside the tree, but the one dimension in which a
registration's necessity has a decisive answer that the schema alone contains: how large a statement
the planner has to build before it reads a row, with the registration and without it.

## What this item's file used to say, and why none of it survives unchanged

The file was filed as a target: no relation in the consumer read set refuses a five-second budget
with nothing materialized. Four things have happened to that target and each is checkable now.

**The target was measured and is unreachable.** The predecessor item took a fresh capture of a
26 818-line consumer schema and priced both arms. With all twenty registrations the store reads all
114 relations in 88.2 s with none over budget. With nothing registered the arm does not finish:
sixteen relations pass a 120-second budget and the planner then exhausts its heap. That is not a
figure to improve on, it is a different failure. Emptying the register is not a reachable state.

**The reason it is unreachable is countable from the DDL and needs no capture at all.** The
fact-model page already states the mechanism. H2 inlines a view at every naming and eliminates no
common subexpression, so the statement the planner builds grows multiplicatively with the depth and
fan-out of the view graph. A registered target is a table, and a table's subtree is itself, so a
registration truncates that tree at its own name for every rule above it. Past some size a
registration is not buying speed, it is buying a plan existing. That is a static property of the
schema text.

**The prediction the file staked itself on has had its cause removed rather than confirmed.** The
file predicted that three relations moving the wrong way sit above
`intent_argmapping_bound_parameter_type`, described there as a six-arm reconstruction of the one
confirmed missing supertype nobody has captured. That relation has two arms today. Its
`UNION ALL` resolves an authored `(class, method)` pair on one side and reaches
`sql_routine_parameter` through `intent_field_routine_method` on the other, and the seven arms that
used to differ only in which owner relation carried the class and the method collapsed into one join
when `graphitron_method_reference` was captured as a table. The supertype the prediction was waiting
for exists. What the three relations cost now is unmeasured, which is a different statement from the
one the file makes.

**The five-second number is not a budget this tree installs, and `ReadBudget` refuses to mean what
the target asked of it.** `DevMojo` installs three: `INTERACTIVE_READ_BUDGET` at 3 s for every
keystroke-grain read, `SESSION_READ_BUDGET` at 30 s for the diagnostics drain, and
`MCP_READ_BUDGET` at 60 s for an agent's turn. Each javadoc says the same thing about what the
number is for, that the target is a query which would otherwise never return and that a threshold
tight enough to police slowness would start refusing correct answers on a loaded machine. A target
phrased as a `ReadBudget` therefore asks the shipped mechanism to be a latency policy, which its own
documentation declines to be. A threshold for research belongs to the instrument that measures, not
to the guard that ships.

So the title changed with the premise. The file was
"No relation a consumer reads refuses a five-second budget with nothing materialized"; citations to
that phrasing in the predecessor item and in the changelog are the same item under its measured
subject.

## What changes when this lands

**An author reaching for a registration is told what the schema costs without it, by the build, in
the dimension where the answer is not a matter of taste.** `report-inline-multiplicity` already
counts relation instantiations per read from the DDL alone, needing no database and no profiler, and
prints a ranking on every roadmap-tool run. It counts one arm: the schema as it ships, where every
registered target is a table. It gains the second arm, the same count with every registration
demoted to the `_live` view that states its rule, and the per-registration delta between them. The
delta is what a registration is worth in plan size, per registration, with no store and no timing.

**Two rules that were only ever tested in their storage form get their rules examined**, which is
the mistake the predecessor item exists to name and did not finish clearing. Details under
"The two rules nobody has examined".

Nothing a reader asks the store changes: the same relations, the same rows, under the same names.
A consumer's observable gain is on the two rules, not on the metric, and the metric is what stops
the next twenty registrations arriving unpriced.

## Why the counterfactual arm and not the bench

The predecessor item's closing section says a rule bench that prices a relation as a view against a
captured store belongs in the tree. The audit it points at says the opposite about the apparatus that
took its figures, that none of it is reactor code and none of it belongs there, because it measures
one consumer's store against schemas from several days of the tree. Both are right about different
halves, and the split is what this item is built on.

What cannot live in the tree is anything whose input is a captured consumer store. A capture needs
the consumer's sources, its catalog and its build, none of which are in this repository and none of
which should be. A wall-clock figure taken against one is research evidence, and the predecessor
item's own test section states the rule it lives under: a duration is not a build assertion.

What can live in the tree is every question the schema text answers on its own. Plan size is one,
and it is the question that decides the headline claim: the unregistered arm's failure is a plan that
cannot be built, not a read that is slow, so it is fully diagnosable from the DDL. Adding the
counterfactual arm to a metric that already ships beats building a bench that cannot run in CI, and
it is what makes the pricing requirement the predecessor item hands to the ownership item mechanical
rather than a convention nobody can check.

**Two figures already in the tree say the metric needs the arm and needs to be a tool rather than a
measurement.** The fact-model page records the shipping schema's largest statement as 963 and the
fully demoted schema's as 2739455. Running `report-inline-multiplicity` on the tree today reports
`intent_argmapping_projection_defect` at 368 as the heaviest, so the first of those two figures has
rotted. It rotted for a good reason, the supertype captures having collapsed unions the count was
compounding, which is precisely why the number wants to be a reported metric that moves with the
schema instead of prose in a document.

## The two rules nobody has examined

Both were found on the fresh capture, both are named as plan defects rather than as timings, and
neither has had its rule looked at. The predecessor item states them as unfinished and this item
takes them.

**The demand and exemption family names the expansion union many times over and asks its questions
as correlated existence tests.** Counted over the six view bodies from `intent_field_demand_rule`
through `intent_resolved_type_demand`: 13 references to `intent_expanded_type`, 12 to
`intent_expanded_field`, and 10 `EXISTS` terms. `intent_resolved_type_demand` at 111 and
`intent_type_demand` at 106 sit in the metric's top eight today. The work is to state what each
`EXISTS` is asking and whether it is asking it in a form a planner can answer, which is the rung-3
question, and to reach for a registration only if the answer is that it cannot.

**The probes resolve on the partition dimension alone.** Where a probe's only equality is the graph
partition, a single-graph consumer selects the whole relation, so the predicate reads as a scope and
prunes nothing. What is owed is the enumeration of which probes those are and, for each, either a
key the probe could match on or a statement that no narrower key exists.

Both are held by row identity: the relations answer with the same rows before and after, on the
fixture and on the sakila example. Neither is held by a duration.

## Implementation

**`InlineMultiplicityCheck` in `roadmap-tool` gains the demoted arm.** It parses the DDL today,
distinguishes `CREATE VIEW` from `CREATE TABLE`, and treats a registered target as a table by
construction because that is what the schema says it is. The demoted arm reads the
`INSERT INTO meta_materialize` seed rows for the target-to-source-view pairs, rewrites every
reference to a target into a reference to its `_live` view, and runs the same multiplication. Counts
in the demoted arm exceed the range of an `int`, so the accumulator is a `long` on both arms.

**The report gains a per-registration delta.** For each of the twenty registrations, the largest
statement any relation reaches with it registered against the largest with it demoted and every
other registration left as it ships. That is the figure an author adding a registration is claiming,
and the figure a reviewer of a retirement needs.

**The two rules above are restated where restating them is what the shape asks for**, and left alone
where the examination concludes the rule is already in the form the planner wants. An examination
that changes nothing is a result and gets recorded as one; this item does not owe a rewrite per
defect.

**The fact-model page's two stale statement-size figures are replaced by a pointer to the metric.**
A figure in prose that the schema moves under is what rotted; the page states the mechanism and the
lever order, and the numbers come off the tool.

## Tests

- `InlineMultiplicityCheck`'s two arms get a test in `roadmap-tool` over a stated miniature schema
  rather than over the shipping DDL: a base table, a rule naming it twice, a registration over that
  rule, and a reader above the registration. Four relations are enough to make both arms' arithmetic
  visible in the lines that produced it, and a case over the shipping schema would pin a figure that
  moves whenever a view is added.
- The demoted arm's register parse is held against the booted store rather than against its own
  regex: the pairs it reads out of the DDL text must equal `Materializations.registrations` as the
  store reports them. That is the assertion that catches the seed-row format changing, and it is the
  reason this half of the work is worth a test at all.
- Row identity on any relation the two examinations restate, on the fixture and on the sakila
  example, at the counts already recorded for them.
- `DerivedReadCostTest`'s directional claim covers any restatement automatically, and its
  `KNOWN_NON_MONOTONIC` set is where a restatement that clears a pinned pair shows up. A cleared
  pair is a row that goes, and the set is asserted by equality, so the build fails until it does.

No wall-clock assertion anywhere, for the reason the predecessor item's test section states: a
duration is not a build assertion, and every timing this item quotes is research evidence taken
against a captured consumer store which is not a fixture and is not in this repository.

## What this item does not do

**It does not build the bench.** A wall-clock instrument whose input is a captured consumer store
cannot run in CI, cannot be pointed at anything this repository contains, and has no fixture. The
research apparatus that took the predecessor item's figures is correctly outside the tree and stays
there; what comes into the tree is the half whose input is the schema.

**It does not take a position on any registration's fate.** The delta is a number, and reading it as
a retirement is the ownership item's decision to make with an owner attached. Thirteen of the twenty
targets hold no rows at all on the consumer capture measured, which is a stronger argument about
several of them than plan size is, and it is not this item's argument to make.

**It does not add the priced-reason gate over `meta_materialize.reason`.** That requirement is
handed to the ownership item, and eighteen of the twenty reasons already carry an unregistered price
in prose. The two that do not are `intent_input_field_carrier_role_live` and
`intent_node_id_decode_column_live`. Naming them here is provenance for whoever writes the gate, not
a claim on the work.

**It does not restate the register's own recorded prices.** Several are stale and the predecessor
item says so; a wall-clock figure in a `reason` is a figure only the consumer store can refresh.

## Inherited questions this item does not close

Two questions arrived with the file and neither is answerable from the repository, so both are
recorded rather than planned.

Whether `intent_spelled_table`'s registration is still priced correctly now that the relation it
reads has a grain. Its reason records the broadest fan-out in the register, thirty-two readers about
half again as dear without it, and no re-measurement since the grain landed.

The planner degradation observed on `intent_resolved_type_binding`. Its reason records removing it
alone taking the refresh from about a second to forty-seven minutes and a reader past a sixty-second
budget. The demoted arm will say what that registration is worth in plan size, which is a different
quantity from the one the reason records and does not replace it.

Both get an answer the next time somebody takes a capture, and the metric this item lands is what
tells them which relations to time.
