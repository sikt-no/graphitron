---
id: R954
title: "A resolved @reference path is rows on disk every reader seeks into, not a recursive view re-walked once per driving row"
status: Spec
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-09-16
last-updated: 2026-09-16
---

# A resolved @reference path is rows on disk every reader seeks into, not a recursive view re-walked once per driving row

## Goal

A consumer's build resolves each authored `@reference` path once per capture, and every reader
afterwards seeks into stored rows on an index over the coordinate it asks by. Today two of the three
sibling walks that do this resolution are recursive SQL views carrying window functions no outer
predicate can prune, and one of the two is named on the inner side of a `LEFT JOIN` where it is
re-evaluated once per driving row; beside it in the same statement an anonymous derived table counts
the foreign keys connecting an ordered table pair, and is re-counted per driving row for the same
reason. That makes the `@nodeId` decode rule over them grow as the square of the store's size, which
on the `sis` consumer is a `graphitron:dev` round that runs past twenty minutes. When this lands, the
decode rule joins four stored relations, the count of foreign keys between two tables is a fact the
catalog gatherer writes, and the round's cost grows with the schema rather than with its square.

Three terms, glossed once. The *fact store* is the H2 database each generator pass captures the
schema, the jOOQ catalog and the classpath into, and answers its verdicts out of by SQL. A
*registration* is a row of `meta_materialize`, which keeps a rule in a view under a `_live` name and
moves the canonical name onto a table that a refresh pass empties and refills, so readers meet stored
rows instead of re-evaluating a view. A *capture stage* is Java code in a gatherer that computes a
relation out of what that gatherer transcribed and writes its rows itself, of which
`GraphitronFactCapture.capture` runs eight.

Two claims sit under that outcome and they are separable, which matters because only one of them is
measured.

**The cost claim.** With the walk and the foreign-key count stored, the `intent_node_id_decode_hop`
refresh grows linearly in the store's size where today it grows as its square. This is conditioned on
a measurement this plan does not yet have; "What this plan is conditioned on" below names it and says
what the plan becomes under each outcome.

**The modelling claim.** The count of foreign keys connecting an ordered table pair is a catalog
fact, not a subquery. It reads one captured relation, it has a grain of its own, and it is written
inline inside a rule that wanted it. That is the top rung of the lever order in
`docs/architecture/explanation/fact-model.adoc`, which admits a captured fact whether or not any
reader is currently slow, and no measurement moves it.

The cost claim is what makes this priority 1. The modelling claim is what makes slice one land first
and alone.

## What is in scope

Three sibling walks, which differ only in where the chain departs from, and which all read
`intent_field_reference_step_hop`'s candidate joins:

- `intent_field_reference_step_target`, departing from the enclosing type's own binding through
  `intent_resolved_type_binding`. **A view.** Three view bodies name it, one of them twice:
  `intent_field_reference_step_fanout`, `intent_field_column_scope_live`, and
  `intent_node_id_instruction_live`, which is the 592 s statement R953 measured. The MCP server's
  `SchemaQueries` and the language server's `ClaimFacts` read it directly.
- `intent_input_field_reference_step_target`, departing from the table the consuming field handed the
  expansion, through `intent_input_field_resolving_table`. **A view**, and the one this investigation
  measured. Two readers: `intent_input_field_column_scope` and `intent_node_id_decode_hop_live`,
  the second naming it on the inner side of a `LEFT JOIN`.
- `intent_argument_reference_step_target`, departing from the table the argument's content binds
  against. **Already a table**, registered by R943's fourth lever, carrying
  `ix_argument_reference_step_target_coordinate`, and costing nothing measurable. It is in scope as
  the shape the other two take rather than as work.

One relation that is not a walk and belongs to a different family. `intent_node_id_decode_hop_live`
inlines an anonymous derived table over `sql_referential_constraint` that counts the foreign keys
connecting an ordered table pair, and names it on the inner side of a `LEFT JOIN`, so it is recounted
once per decode endpoint. It reads that one catalog relation and nothing else, so by the ownership
rule R876 states, the latest owner of anything it reads, it belongs to the catalog gatherer, which
runs first. It has one reader today and no name, no key and no index.

Consequential rather than primary, and settled by measurement in slice three rather than assumed:
`intent_node_id_decode_hop` is a registered target whose rule is a join of an endpoint view to two of
these walks, and `intent_node_id_decode_hop_column` is a registration whose stated case is a
recursive walk over those very rows. Whether either has anything left to stand over once the walks
are stored is a question this item asks with a number rather than answers here.

## Why the walks are registered rather than written by a stage

The filed diagnosis pointed at a capture stage on the `InputOccurrencePaths` precedent. Three facts
in the tree close that route, and the plan below is what is left once they are applied. They are set
out here rather than under "Other solutions" because they are the reason the plan has the shape it
has.

**The seam. A capture stage cannot read a registered target.** `InputOccurrencePaths` and
`FieldEndpoints` can sit where they sit because everything they read is a captured fact:
`graphql_argument`, `graphql_type`, `graphql_field` for the first, `graphitron_field_navigation`,
`graphitron_tabletype`, `graphql_poly_member`, `sql_table` and `store_graph_source` for the second.
Nothing either reads is filled by the refresh pass. Every one of the three walks reads at least two
registered targets: all three read `intent_field_reference_step_hop`, the field walk reads
`intent_resolved_type_binding`, the input-field walk reads `intent_input_field_resolving_table`, and
the argument walk reads `intent_argument_scope_table`. `FactCapture.capture` runs every hand-written
producer before `Materializations.refresh`, so a producer in that slot reads the *previous* capture's
rows and writes a wrong answer inside one pass. That is not a hypothesis: it is what
`UnlowerableOrderingRejectionRows` already has to run in its own transaction after the refresh to
avoid, and its comment at `FactCapture.capture` says so.

The after-the-refresh slot exists, then, and is closed for these three by their readers: each is read
by a registered source view (`intent_field_column_scope_live`, `intent_argument_column_scope_live`,
`intent_node_id_instruction_live`, `intent_node_id_decode_hop_live`), so a walk has to be current
*inside* the order, not before it or after it. The register has no node for that, and
`MaterializeRegistryGateTest.noOrderingNeedCrossesTheHandWrittenBoundary`'s javadoc discloses exactly
that asymmetry: a registered view reading a hand-written table is visible to the population walk,
while "a hand-written derivation reading a registered target is jOOQ code rather than a stored view
definition, so no catalog parse can see it".

**The precedent. This exact move was proposed once and refused for this exact reason.**
`meta_materialize`'s own `reason` for `intent_node_id_decode_column_live` records it: "The cheaper
rung, a captured fact, is unavailable for a reason that is about the seam and not about the
computation: the fold walks `intent_node_id_decode_hop_column`, which is a registered target the
refresh itself fills, and `FactCapture.capture` runs every hand-written producer before
`Materializations.refresh`, while moving one after the refresh would be too late, both expensive
readers being refresh statements themselves." Same shape, same seam, resolved by registration.

**The criterion. The hand-written roster admits impossibility, and these walks are possible.**
`MaterializeRegistryGateTest.HAND_WRITTEN` holds the six `intent_` base tables a producer writes, and
its javadoc states what earns a place: "Each argues impossibility in its own table comment: no view
could state its rule." `InputOccurrencePaths` meets it, cyclic input nesting being legal GraphQL with
no safe H2 recursive view form. A reference walk is a fold over a finite ordered list with no cycle,
no fixpoint and no depth stratification, which is an argument that it *fails* the criterion: the view
exists, is correct, and is the oracle every swap below is proved against. Changing that criterion
from impossibility to ownership is R876's move to make, in the commit that dissolves the register,
not a performance item's.

The ordering mechanism a producer would need is therefore not a detail this item can absorb. R876's
target architecture says what supersedes `meta_materialize_dependency` is "whatever orders two
relations under one owner, which is not settled", and the obvious sketch, a `meta_` relation
declaring what each producer reads, is the one artifact the fact-model page rules out by name: a
hand-kept ordering with no derivable source, "which is the shape `SchemaIdentifierDriftCheck` exists
to refuse". To be admissible it needs a gate that derives the edge set from the producer's own source
and fails on a drift, which is the larger half of that work and belongs with R876. "What this does not
do" below states the hand-off.

## What was measured

Fixture only, and the section after this one is what corrects that. Everything below was taken on the
scaled registry fixture (`MaterializedRegistryFixture.scaledSdl`, with a `@nodeId` input field added
so the `INPUT_FIELD` decode arm has a population), not on a consumer store. Three sweeps per
configuration, best of three, `OPTIMIZE_REUSE_RESULTS` off.

`intent_node_id_decode_hop_live`, which is the statement the refresh issues per graph:

| decode endpoints | as shipped | input-field walk stored | walk and foreign-key count stored |
|---|---|---|---|
| 96 | 73.4 ms | 10.5 ms | 1.0 ms |
| 192 | 264.0 ms | 20.0 ms | 1.4 ms |
| 384 | 1069.4 ms | 41.8 ms | 2.8 ms |

As shipped the rule grows by 3.6 and then 4.05 per doubling, an exponent of about 1.93. Both stored
forms grow by 1.9 to 2.1, so linear. The walk answers in 0.8 to 2.7 ms standalone at these sizes and
the foreign-key count in 0.3 ms over 56 rows at every size, so neither is expensive; what costs is
that each is evaluated once per driving endpoint row. `intent_argument_reference_step_target` costs
nothing measurable, being an indexed table since R943, which is the same claim from the other
direction. Snapshotting was proved to change cost and nothing else: `EXCEPT` in both directions
returned zero rows at every size.

Two limits on the table, both load-bearing for the plan. The foreign-key count was never timed
without the walk, so the two are priced as a pair and the third column is not evidence that either
half suffices. And **every column of it is neutral between a registration and a stage**: what it
prices is the rows being on disk, not who wrote them.

Two further readings on the same fixture:

**The refresh's graph predicate does not prune, and this is the one reading that discriminates.**
With three graphs of 72 endpoints in one store, the refresh of a single graph came out at 170.9 ms
against 175.3 ms for the same statement with no predicate at all, and 22.6 ms for that graph alone in
a store of its own. `Materializations.refreshPartition` issues `INSERT INTO target SELECT * FROM
source WHERE graph_name = ?`, and that predicate cannot reach inside a recursive term, so a workspace
store holding several subgraph modules pays the rule over all of them at every module's refresh. A
stage writing one graph's partition would not. This is the whole of the case for a stage over a
registration, it is 170 ms on a fixture, and slice three is where it gets a number on a real store.

**Rung 3 was tried on the walk and does not reach it.** Restating `last_position` as a join to a
`GROUP BY` derived table instead of a window function measured 41.2 s against 170.9 ms, a regression
of about 240 times, because it names the body twice. Moving the site discriminator out of the `ON`
clause, and hoisting the walk into a non-recursive `WITH`, each changed nothing. Splitting the rule
into one arm per site buys 3.6 times and stays quadratic, so it is a constant and not a fix; it is
recorded here because it also refutes a claim on `intent_node_id_decode_hop`'s own comment, that a
second naming of the endpoint subtree "would dominate the read", which is not what the measurement
says and which this item corrects where it touches that comment.

## What this plan is conditioned on

The table above is a shape claim on a synthetic population, and this family has produced a wrong
reading before. The `store-performance` skill's posture section records one: "a family of recursive
reference-target views was the expensive term, when timing each relation on its own said the term was
somewhere else entirely." The investigation that filed this item had a second conclusion overturned,
reporting that statistics were not the lever from a regime that measured identically at every fixture
size, which R953's readings on a copy of the `sis` store contradict.

So the first act is a measurement, and it is one nobody can take without the `sis` workspace on disk:
the store is that consumer's own build cache, which is where the `store-performance` skill sends you
first and the only population carrying the classpath census and source membership a fixture omits.
**The implementer takes it at pickup, before any code.** Per that skill's method, on a copy of the
`sis` store, timing `intent_node_id_decode_hop_live` and `intent_node_id_instruction_live` per graph:

1. as shipped;
2. with R953's lever 2 alone, a static `SELECTIVITY 1` on `graph_name`;
3. with the foreign-key count snapshotted into a table, walks left as views;
4. with the two walks snapshotted into tables carrying slice two's indexes, count left inline;
5. with both, which is the shipping shape.

Three and four are separated because the fixture priced only the pair. Each snapshot is proved
answer-preserving by `EXCEPT` in both directions before it is timed.

What the plan becomes under each outcome:

- **Lever 2 alone brings the round into seconds.** The cost claim is then R953's, not this item's.
  Slice one still lands on the modelling claim; slice two drops to priority 3 and says in this body
  that it is a modelling tidy rather than a fix.
- **The count is the term and the walks are not.** Slice one is the item and ships alone; slice two
  becomes a follow-up with no cost argument behind it.
- **The walks are the term and the count is not.** Slice two is the item; slice one still lands
  first, being cheaper and independently justified.
- **Both are real, as the fixture says.** The plan below stands as written.

Under every outcome the measurement is written into this body, replacing this list with what it
found. Slice three's own measurement is separate and comes last.

## Implementation

Three slices. One is independent of the others, is the top rung of the lever order, and needs no
mechanism at all. Two is the shape the third walk already has. Three is a question the first two make
answerable.

### Slice one: the foreign-key count becomes a captured catalog fact

The anonymous derived table inside `intent_node_id_decode_hop_live` asks how many foreign keys
connect an ordered table pair. It reads one captured relation, it has a grain of its own, and rung 1
of the lever order is admitted whether or not a reader is currently slow.

```sql
CREATE TABLE sql_table_reference (
  source_name            VARCHAR NOT NULL,
  table_schema           VARCHAR NOT NULL,
  table_name             VARCHAR NOT NULL,
  referenced_source_name VARCHAR NOT NULL,
  referenced_schema      VARCHAR NOT NULL,
  referenced_table       VARCHAR NOT NULL,
  constraints            INT     NOT NULL,
  PRIMARY KEY (source_name, table_schema, table_name,
               referenced_source_name, referenced_schema, referenced_table)
);
```

Grain: one ordered pair of tables that at least one foreign key connects, and how many connect it.
The grain admits a real primary key, which the walks below do not, so this one is keyed rather than
indexed. `intent_node_id_decode_hop_live`'s `DISCOVERED_KEY` arm then joins it on the six columns it
already holds and tests `constraints = 1`, reaching `sql_referential_constraint` for the constraint
name under the join it writes today.

**Why `sql_` and not `intent_`, since a count is a derivation.** The prefix names the family, and a
gatherer writing a computed relation into its own family is `FieldEndpoints` exactly: it joins, ranks
and reaches the catalog, and writes `graphitron_field_table`. So this is a stage of the catalog
gatherer, which today has none, `CatalogFactCapture.capture` being a transcription into a `FactSink`.
It is not an `intent_` hand-written derivation and does not join
`MaterializeRegistryGateTest.HAND_WRITTEN`, whose roster carries an impossibility criterion this rule
could not meet. Check the choice against `MetaDeclarationGateTest`'s corpus gate before landing and
say which way it went in the commit; the ordered table pair is a catalog grain either way.

**Lifecycle.** `sql_` relations are keyed by `source_name` and refreshed per source rather than per
graph, which is R872's subject. The stage clears and refills the sources the capture touched, the
same scope `CatalogFactCapture.capture` writes. R872's landing changes when it runs, not what it
holds.

Deliberately not done: a `constraints` column on `sql_referential_constraint` itself. It would repeat
one value down every constraint of a pair, which is the denormalisation the referenced-side
discipline declines, stated on `intent_field_reference_step_hop.constraint_name`'s own comment.

This slice touches no walk, no register row and no refresh order, and it can ship on its own.

### Slice two: the two unstored walks get the shape the third already has

`intent_field_reference_step_target` and `intent_input_field_reference_step_target` each become a
registration: the view text moves to a `_live` name unchanged, a table of the same column list takes
the canonical name, and a row goes into `meta_materialize`. No reader is edited, which is the
property a registration has by construction and the reason the swap is provable rather than arguable.
The shape to copy is `intent_argument_reference_step_target`, landed by R943 for the same rule at the
third departure site; three siblings that differ only in where the chain departs from end up in one
mechanism rather than two.

**Indexes.** Each table declares the coordinate index its readers join on, shaped like
`ix_argument_reference_step_target_coordinate`: `(graph_name, type_name, field_name, ordinal,
position)` for the field walk, and the same with the resolving triple ahead of the ordinal for the
input-field walk, that departure being part of its key. Each index carries a `COMMENT ON INDEX`
naming the reader that justifies it, which `MaterializeRegistryGateTest.everyIndexOnATargetStatesItsReader`
requires. No primary key: the grain includes `constraint_name` and `fk_on_from`, both meaningfully
nullable, and H2 refuses a primary key over a nullable column, which is the case that class's
`everyTargetIsIndexedOrStatesWhyNot` javadoc already argues for most targets.

**The register is set-relative, so neighbours get re-priced.** `meta_materialize.reason`'s own comment
requires it: "A registration whose source view reads another registration's target is priced by that
other row still being there, so dropping a neighbour can make this one dearer rather than cheaper."
Two new rows land beside three that are priced against these walks being views. The commit edits:

- `intent_node_id_decode_hop_live`'s reason, whose argument is built on the two walks' per-driving-row
  cost, and whose claim that a second naming of the endpoint subtree "would dominate the read" the
  measurements above refute;
- `intent_node_id_decode_hop_column_live`'s reason, for the same reason one rung out;
- `intent_argument_reference_step_target_live`'s reason, which becomes one of three rather than one of
  one;
- `MaterializeRegistryGateTest.REGISTRATIONS`, 23 to 25, and its refresh-stage depth, both
  equality-pinned so they cannot move in a commit arguing for something else;
- `DerivedReadCostTest`'s pinned reader and cell figures, which a new registration necessarily moves.

**Coordination with R953.** R953's lever 3 proposes registering `intent_field_reference_step_target`
and is the same change as half of this slice. One item ships it, and this one should, being the item
that measured the walk and the one that also has to place the input-field sibling and the count. R953
keeps levers 1 and 2, which are about which plan one evaluation gets and compose with this rather than
overlapping it. **Neither item implements until both bodies record that split**; R953's Spec is where
its half of the record goes.

### Slice three: price what the registrations still stand over, and what a stage would still buy

Two questions, both answered with numbers on the `sis` copy rather than by argument, and both only
askable once slices one and two have landed.

**Do the decode registrations still earn their rows?** `intent_node_id_decode_hop_live` becomes a join
of one view to three stored relations, and `intent_node_id_decode_hop_column_live`'s stated case is a
recursive walk over rows that are now a table's. Price each against its absence in both shapes on
`DerivedReadCostTest`'s own fixture, then re-time on the `sis` copy. Demote whichever no longer pays,
in a commit that edits the pinned figures deliberately, which is what they exist to force.

**What does the per-graph refresh still cost?** The one measurement that discriminates a stage from a
registration is the graph predicate that does not prune: 170.9 ms against 22.6 ms on the fixture, for
a store holding three graphs. Re-time it on a `sis` workspace store holding several subgraph modules.
If the residual is material, that figure is the evidence R876 needs to mint an ordering mechanism for
producers inside the refresh, and this item files a successor carrying it rather than minting one
here. If it is not, the register is the end state for these three walks and this item says so.

## Tests

The swaps are proved against the views they replace rather than re-asserted, which is what makes
slices one and two cheap to review.

- **Answer preservation, both slices.** `EXCEPT` in both directions between the stored relation and
  the rule it replaces, over a populated store, per graph. For slice one that is the anonymous derived
  table lifted into a named query; for slice two it is the `_live` view against its target, which the
  registration mechanism gives for free and which the fixture measurements already ran.
- **`ReferenceStepTargetTest`, `ArgumentReferenceStepTargetTest`, `ReferenceStepFanoutTest`** already
  pin what the walks answer, at the coordinate grain, and must pass unchanged. A test that needs
  editing is a signal the swap moved an answer.
- **A case for `sql_table_reference`'s own grain**, in the catalog family's test tier: a table pair
  connected by two foreign keys is one row saying two, a self-referential key is one row, and a pair
  with none has no row. The third is the one the decode rule's `LEFT JOIN` depends on.
- **`MaterializeRegistryGateTest`** carries the register's shape, the index-or-roster claim and the
  index-names-its-reader claim; all three bind on the two new registrations with no new case.
  `MetaDeclarationGateTest` binds on `sql_table_reference` if it is declared.
- **`DerivedReadCostTest`** is the gate that fails a registration costing another relation more scans
  than leaving it a view. Its pinned set is edited in the same commit, which is the confrontation it
  is built to force.
- **`FactCaptureAgreementTest` and `FactSchemaGateTest`** both name these relations today and are the
  regression surface for a column list or a capture path that moved.
- **Acceptance evidence for the goal**, which a green build does not supply: the `sis` timings from
  "What this plan is conditioned on", re-taken on the shipped tree and written into the changelog
  entry. The goal is a growth claim, and the only thing that demonstrates it is the same four
  configurations answering linearly.

## What this item does not do

- **It does not mint an ordering mechanism for producers inside the refresh.** That is R876's open
  question, its target architecture naming it as unsettled, and the sketch that first suggests itself
  is ruled out by name on the fact-model page. Slice three produces the measurement that would justify
  minting one; the minting is R876's or a successor's.
- **It does not change what admits a hand-written derivation.** The impossibility criterion on
  `MaterializeRegistryGateTest.HAND_WRITTEN` stands until R876 replaces it with ownership.
- **It does not touch R953's levers 1 and 2.** They fix which plan one evaluation gets; this item
  changes how many evaluations there are. Lever 2 should land whatever this item does.

## Relation to other items

**R953** is a different defect on an overlapping path and the two compose rather than competing. It
diagnoses a statistics cliff: `intent_field_reference_step_hop.graph_name` at H2's default selectivity
makes the planner choose a one-column index over the step index, and the first refresh on a store
plans with no statistics at all. That is a cliff in the cost of *one* evaluation. This item is about
the *number* of evaluations. They multiply, because these walks join that very table in both the
anchor and the step, so each of N driving rows pays a bad plan. Its lever 3 is half of slice two, and
slice two says which item ships it.

**R876** is the doctrine this item works inside. Two things it owns and this item therefore does not:
the criterion that admits a hand-written derivation, and the mechanism that orders two relations under
one owner. This item's slice three is built to hand R876 a number for the second. Its slice one is an
instance of R876's own top rung, and moves a rule to the gatherer whose corpus it reads, which is one
of the misplacements R876 enumerates.

**R942** is a gate rather than a fix and does not overlap, but one reading here is evidence for its
Spec. Its detector drops the `INNER_SIDE` position because `ViewReferences`' javadoc records that an
inner-side reading is the executed shape "only where the join order is fixed, which is the outer
joins", and asks for such a naming to be priced rather than asserted.
`intent_node_id_decode_hop_live` naming the input-field walk is one of those, now priced. A scan of
the DDL puts the outer-join subset at 9 distinct (reader, view) pairs across 12 namings, against 45
distinct on inner joins, so if R942 wants to widen it is a nine-row question rather than the fifty-six
R942 costed for the whole inner-side set. No scope of R942 reaches the foreign-key count, which names
a table rather than a view; slice one removes that naming anyway.

**R899** exists to make a registration's alternative countable so the last lever stops being the first
reached for, and stands demoted on the argument that R876 dissolves the register and leaves it without
a subject. This item is evidence in both directions and should be read as such: it reaches for a
registration twice, and it reaches for it having priced the three rungs above it and found that the
top one is closed by a seam rather than by cost. That is the reading R899 wants to make routine.

**R857** and **R872** would stop a dev round refreshing this rule when the edit did not touch it,
which mitigates the symptom on the dev loop and does nothing for a `generate` or for CI. R872 also
owns the catalog family's refresh lifecycle, which slice one's relation joins.

## Other solutions we've considered

- **A capture stage, as this item was filed.** Closed by three facts in the tree, set out under "Why
  the walks are registered rather than written by a stage": the seam a producer cannot cross, the
  register row that already refused this move for this reason, and the impossibility criterion the
  hand-written roster carries. What a stage would still buy over a registration is one thing and one
  thing only, the per-graph refresh that does not prune, which slice three prices.
- **A `meta_` relation declaring what each producer reads,** folded into
  `meta_materialize_dependency`'s graph so producers and registrations refresh in one order. This is
  the mechanism a stage needs, and as sketched it is a hand-kept ordering with no derivable source,
  which the fact-model page rules out by name. An admissible version derives the edge set from the
  producer's own source and fails the build on a drift, in the shape `EntryNamingGuardTest` already
  uses to read the decode's source. That gate is the larger half of the work and belongs with R876.
- **A rewrite of the walk.** Three were measured, above. One regressed by about 240 times, two changed
  nothing, and the one that helped left the exponent alone.
- **R953's lever 2 alone**, a static `SELECTIVITY 1` on the partition column in the DDL. Cheap,
  already specced, and orthogonal: it fixes which index one evaluation picks and does not reduce the
  number of evaluations. It should land whatever this item does, and whether it is *sufficient* is the
  first question the conditioning measurement asks.
- **Splitting slice one into an item of its own.** It reads one relation, has an owner R876 has
  already computed, and needs nothing the other slices need, so it would stand alone. It stays here
  because the two terms were measured as a pair and never apart, and the item that took that
  measurement is the one that owes the separation. If the conditioning measurement shows the walks are
  not the term, slice one becomes the item rather than leaving it.

## Provenance

Filed 2026-09-16 from an investigation into a `graphitron:dev` round on the `sis` consumer taking more
than twenty minutes, which began as a question about `intent_node_id_decode_hop_live` and found the
shape defect underneath it. The investigation's instrument was a throwaway probe in a scratch
directory, which is the failure mode R899 names, so its figures are recorded above rather than left to
be re-derived. Two of its conclusions were overturned before filing and are recorded in this body
rather than dropped: that statistics were not the lever, and that the window function was what blocked
the graph predicate from pruning.

## Reviewer findings

### Round 1 (2026-09-16, Spec -> Ready, reviewer session 01Un87ZyPLmwD9kLBuTdSFKx)

Verdict: withhold. Two blocking findings on question two, both inside slice one, both about which
gatherer owns the new relation and on what lifecycle. Five non-blocking findings follow them.

Nearly everything else checks out against the tree, and the checks were not cheap ones. The three
walks are as described: `intent_field_reference_step_target` and
`intent_input_field_reference_step_target` are views, `intent_argument_reference_step_target` is a
table carrying `ix_argument_reference_step_target_coordinate`. The field walk is named by exactly
three view bodies and by `intent_field_reference_step_fanout` twice, and `SchemaQueries` and
`ClaimFacts` both read it through `Tables.INTENT_FIELD_REFERENCE_STEP_TARGET`.
`intent_node_id_decode_hop_live` names the input-field walk on the inner side of a `LEFT JOIN` and
inlines an anonymous derived table over `sql_referential_constraint` counting foreign keys per
ordered pair through a `COUNT(*) OVER (PARTITION BY ...)`, exactly as the body says.
`MaterializeRegistryGateTest.REGISTRATIONS` is 23, `HAND_WRITTEN` holds six `intent_` tables under
the impossibility javadoc quoted, `GraphitronFactCapture`'s stages are eight,
`noOrderingNeedCrossesTheHandWrittenBoundary` discloses the asymmetry claimed, and both verbatim
quotations (the `intent_node_id_decode_column_live` reason and `intent_node_id_decode_hop`'s "would
dominate the read") are in the DDL word for word. `sql_table_reference` is a free name. R953's 592 s
figure for `intent_node_id_instruction_live` is its own. Every named class, gate method and test
exists. The goal paragraph answers question one on its own reading: a consumer of graphitron gets a
`graphitron:dev` round whose cost grows with the schema rather than its square, and the three terms
it needs are glossed where they first appear.

**Finding 1 (question two: architecture fit). Slice one puts the stage in a class that writes a
different family, and the ownership fork it walks into is not seen.**

The slice says the new relation "is a stage of the catalog gatherer, which today has none,
`CatalogFactCapture.capture` being a transcription into a `FactSink`", and its Lifecycle paragraph
says the stage writes "the same scope `CatalogFactCapture.capture` writes". `CatalogFactCapture`
writes no `sql_` row at all. Its own class javadoc says so: it is "the `jvm_` family", and "It used
to fill the `sql_` family too, and that half is gone: `JooqFactCapture` was writing the same fourteen
relations from the other capture entry point". Its `capture` method calls one thing,
`captureExtensions`, which writes `jvm_class` and its children; its `SQL_REFERENTIAL_CONSTRAINT`
import is unused residue. What transcribes the relation slice one reads is
`JooqFactCapture.capture`, whose javadoc is "The `sql_` family and nothing else: one source of one
shape behind one entry point", and whose `referentialConstraints` stage writes the rows.

By the ownership rule the slice invokes, the latest owner of anything it reads, `sql_table_reference`
therefore belongs to the jooq gatherer. That is not a rename of the paragraph: it decides which class
the implementer edits and which lifecycle applies, and it is the input to the second finding.

There is a live fork underneath it that the slice does not see, and it is the author's to settle
rather than the implementer's. The new relation needs a `meta_relation` row, because
`MetaDeclarationGateTest`'s frozen roster only shrinks and "a new relation is on no frozen roster, so
it cannot arrive undeclared". That row carries `owner_name`, a `meta_gatherer` key. All fourteen
declared `sql_` relations today name `catalog` as their owner while `JooqFactCapture` is what writes
them, and the jooq gatherer owns no declared relation at all. So the tree's declared ownership and
its actual writer already disagree for this family, and slice one has to pick one: declaring
`catalog` entrenches a mismatch the gate does not currently catch, declaring `jooq` makes the new row
the first correct one and leaves fourteen beside it that are not. The slice's "Check the choice
against `MetaDeclarationGateTest`'s corpus gate before landing and say which way it went in the
commit" reads as a gate formality; it is a modelling question with a wrong answer available, and the
spec should answer it.

**Finding 2 (question two: architecture fit). The `sql_` family's lifecycle is stamp-and-sweep, and
the proposed table carries neither the stamp nor a stated reason to be the exception.**

The Lifecycle paragraph says "The stage clears and refills the sources the capture touched". That is
a third discipline, neither of the two the family actually uses. Fourteen of the fifteen `sql_`
tables carry a `touched_at TIMESTAMP`, and `sql_referential_constraint.touched_at`'s own comment
states the rule: "The reading finishes by deleting this source's rows carrying a different instant,
which are the tables, columns and keys the consumer's database no longer has and which an upsert
alone cannot find." `JooqFactCapture.capture` closes with `sweep(dsl, sourceNames(tables),
touchedAt)` and takes the instant as a parameter. The fifteenth, `sql_table_record_supertype`, has no
`touched_at` and its writer is the one stage called without one, so there is a precedent for the
other arm.

The proposed DDL has no `touched_at`, and the body does not say which arm it takes or why. This is
load-bearing beyond tidiness: a derived pair relation that is not swept the same way its source is
outlives the foreign key it counted, and the surviving row says `constraints = 1` about a pair with
no constraints, which is precisely the arm the decode rule's `LEFT JOIN` reads. Name the discipline
and the reason.

Smaller, in the same slice and not blocking on its own: after the change the `DISCOVERED_KEY` arm
joins `sql_referential_constraint` on six columns to reach the constraint name, of which only the
first three are a prefix of its primary key `(source_name, table_schema, table_name,
constraint_name)`, and the relation carries no other index. Slice two states an index discipline and
a `COMMENT ON INDEX` obligation for its own tables; slice one should say whether this join wants
anything, even if the answer is that a three-column prefix seek on a small relation does not.

**Finding 3 (question two, non-blocking). The `FieldEndpoints` precedent comes from the one gatherer
the crawler invariant does not bind.**

"A gatherer writing a computed relation into its own family is `FieldEndpoints` exactly" is true as
far as it goes: it is a stage of the graphitron gatherer and writes `graphitron_field_table`. But
`meta_gatherer_corpus`'s comment defines a crawler as a gatherer carrying a corpus row, "a
transcription pass whose rows about its own corpus may not vary with any other corpus's contents",
and says in the same breath that the graphitron gatherer carries none and "is thereby free to cross
corpora". Both jooq and catalog carry a `catalog` corpus row, so both are crawlers and both are bound
by that invariant. The precedent cited is the one place it does not apply.

The argument slice one actually needs is available and stronger: `sql_table_reference` reads
`sql_referential_constraint` and nothing else, so its rows vary with the catalog corpus alone and the
crawler invariant holds by construction. Make that argument rather than the precedent one.

**Finding 4 (question two, non-blocking). Slice two's edit list points the "would dominate the read"
correction at the wrong surface.**

The bullet reads "`intent_node_id_decode_hop_live`'s reason, whose argument is built on the two
walks' per-driving-row cost, and whose claim that a second naming of the endpoint subtree 'would
dominate the read' the measurements above refute". The first half is right, the reason row is built
on that cost. The second half is not: the refuted sentence is on `intent_node_id_decode_hop`'s
`COMMENT ON TABLE`, and it is the only occurrence of the phrase in the DDL. An implementer working
the list edits the reason and leaves the refuted claim standing where it lives. Name both surfaces.

**Finding 5 (question one, non-blocking). The register already carries a per-term ablation for the
foreign-key count, on a consumer capture rather than a fixture.**

"What was measured" says of the two limits on its table that "The foreign-key count was never timed
without the walk, so the two are priced as a pair and the third column is not evidence that either
half suffices." That is true of this item's own fixture sweeps, but `intent_node_id_decode_hop_live`'s
`meta_materialize` reason already carries the separated reading, taken on a capture of a consumer
schema: against a 4.7 s baseline, dropping one inner-side naming at a time leaves "3.4 s without the
argument-site reference-target walk, 1.9 s without the input-field one, 3.3 s without the derived
table counting foreign keys per table pair", and dropping both walks together leaves 1.1 s.

That reading does not overturn anything here, and it points the same way the plan does. It does two
things for the body. It narrows what the conditioning measurement still has to establish, since
configurations three and four exist to separate a pair that has in fact been separated once already
on a real population. And it puts a rough size on each half, the count at about 1.4 s of 4.7 s and
the two walks at about 3.6 s between them, which bears directly on the outcome branch that says
"The count is the term and the walks are not." Cite it and say what it leaves open.

**Finding 6 (question one, non-blocking). R953 is Backlog, and the coordination clause assumes it is
further along than it is.**

Slice two says "**Neither item implements until both bodies record that split**; R953's Spec is where
its half of the record goes", and "Other solutions" calls lever 2 "already specced". R953's
front-matter reads `status: Backlog`. It has a body, and lever 3 in it is indeed the twin of half of
slice two, but it has no Spec state and nobody has picked it up, so as written this item's
implementation waits on a transition no one is committed to making. `depends-on:` here is empty,
which is the one place a reader would look for that wait.

Either drop the mutual clause to a one-way one, this item records the split and R953's body is
amended whenever it is next touched, or make the dependency real in the front-matter. The clause as
it stands is a gate with no owner.

**Finding 7 (question one, non-blocking). The conditioning measurement has four outcomes and no
branch for not being able to take it.**

"What this plan is conditioned on" says the measurement is "one nobody can take without the `sis`
workspace on disk" and makes it "the implementer's first act at pickup, before any code". All four
outcomes below presuppose it was taken. R943 and R953 both took `sis` readings, so this is plainly
obtainable and not a reason to doubt the plan, but a Ready item whose first act may be unavailable
should say what happens then. The body already contains the answer: slice one is justified by the
modelling claim alone, which no measurement moves, and "Other solutions" says slice one would stand
as its own item. State it as the fifth branch rather than leaving the implementer to assemble it.

**Small, and left as findings rather than corrected here because their resolution depends on
Finding 1.** The Goal says "the decode rule joins four stored relations" and slice three says it
"becomes a join of one view to three stored relations". Counting the shipping shape:
`intent_node_id_decode_endpoint` is the view, and `intent_argument_reference_step_target`,
`intent_input_field_reference_step_target`, `sql_table_reference` and `sql_referential_constraint`
are four stored relations, the last of them because slice one still reaches it for the constraint
name. One of the two numbers is wrong, and which one depends on whether that reach survives Finding
1's rework.
