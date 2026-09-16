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

A consumer's build resolves each authored `@reference` path once per capture, written down by the
gatherer that owns it, and every reader afterwards seeks into stored rows on an index over the
coordinate it asks by. Today that resolution is a stack of SQL views held up by registrations: the
walk that chains the hops is a recursive view carrying window functions no outer predicate can prune,
two of its three departure sites name it where it is re-evaluated once per driving row, and beside it
in the same statement an anonymous derived table recounts the foreign keys between a table pair per
row. That makes the `@nodeId` decode rule grow as the square of the store's size, which on the `sis`
consumer is a `graphitron:dev` round that runs past twenty minutes. When this lands, the resolution
is written by the `graphitron` gatherer into its own family, the register is smaller rather than
larger, and the round's cost grows with the schema instead of with its square.

Three terms, glossed once. The *fact store* is the H2 database each generator pass captures the
schema, the jOOQ catalog and the classpath into, and answers its verdicts out of by SQL. A
*registration* is a row of `meta_materialize`, which keeps a rule in a view under a `_live` name and
moves the canonical name onto a table a refresh pass empties and refills; it exists to schedule
refreshes for rules that have no owner to schedule them. A *capture stage* is a step of a gatherer
that computes a relation and writes its rows with an `INSERT ... SELECT` of its own, of which
`GraphitronFactCapture.capture` runs eight, the last being `FieldEndpoints.derive`.

Two claims sit under that outcome and they are separable, because only one of them is measured.

**The cost claim.** With the walk stored and the foreign-key count stored, the decode rule grows
linearly in the store's size where today it grows as its square. This is conditioned on a measurement
this plan does not yet have; "What this plan is conditioned on" names it and says what the plan
becomes under each outcome.

**The modelling claim.** Nothing in this stack ever needed a register. Every relation under the three
walks bottoms out entirely in captured facts, so every one of them is a resolution whose owner is
known and whose inputs a gatherer holds. That is the top rung of the lever order in
`docs/architecture/explanation/fact-model.adoc`, and R876's thesis stated for one subtree. No
measurement moves it.

The cost claim is what makes this priority 1. The modelling claim is what decides the shape, and it
is the one that survived the structural check below.

## The structural finding this plan is built on

Computed from the shipped DDL, so it is checkable rather than argued. The transitive closure of the
three walks through `intent_` relations holds **27 relations, 8 of them registered**. Every one of
the 27 bottoms out entirely in captured facts: not one reads anything that is not ultimately
`graphitron_`, `graphql_`, `sql_`, `jvm_` or `store_`. There is no cross-family derivation anywhere
in this subtree. It is all resolution, which is to say matching what an author wrote against what the
catalog holds, and capture holds both sides of every one of them.

`intent_spelled_table` is the clearest case and the bottom rung. Its whole rule is a three-way equi
join of `graphitron_spelled_reference_entry`, `store_graph_source` and `sql_table`, and capture has
already pre-folded the case into `name_part_upper` and `namespace_part_upper` so the match is an
indexable equality. Capture does most of that work and hands the last join to a view, which then
needs a register row to be affordable.

**What the registrations are holding up.** By the fact-model page's inlining rule, counted over the
DDL: `intent_node_id_decode_hop_live` as shipped is a statement of size 12, and with the
registrations under it demoted to their rules it is **4796**. The field walk is 4 against 163, the
input-field walk 4 against 709. So plan size is not what costs today, and the reason is that eight
registrations are truncating the tree. They are scaffolding under a subtree that is entirely
resolution.

**A registration already evaluates its rule exactly once per capture.** `Materializations.refresh`
issues one `DELETE` and one `INSERT ... SELECT` per registration per graph. So for the eight relations
that carry one, the rows are already on disk and a stage would not evaluate them fewer times. What
converting them buys is not fewer evaluations; it is the per-graph partition write, the register's
disappearance, and the seam below.

**The seam is self-dissolving bottom-up, which is what makes this plan possible.** A capture stage
may read a plain view, whose own inputs are current by the time the stage runs, and may not read a
registered target, because `FactCapture.capture` runs every producer before `Materializations.refresh`
and the stage would read the previous capture's rows. Taken one relation at a time that blocks the
move, and `meta_materialize`'s own `reason` for `intent_node_id_decode_column_live` records it being
blocked for exactly this reason. Taken bottom-up it does not block anything: convert
`intent_spelled_table` first, whose inputs are all captured, and the hop's inputs are then all
captured or plain views over captured facts, so the hop converts with no seam, and so on up. Each
conversion removes the seam for the one above it. No ordering mechanism is needed, which is what the
earlier reading of this item got wrong.

**A converted relation moves to `graphitron_`, and that is load-bearing rather than cosmetic.**
`MaterializeRegistryGateTest.nothingMaterializesOutsideTheMechanism` scans `intent_` base tables
only, and `HAND_WRITTEN`, the roster it checks them against, admits a relation on an impossibility
argument: "no view could state its rule". None of these relations can make that argument, the view
existing and being the oracle every conversion is proved against. A relation the gatherer writes
belongs to the gatherer's family by the ownership rule anyway, and in that family the gate does not
apply, exactly as it does not apply to `graphitron_field_table`. So renaming with the move is what
keeps the impossibility criterion meaningful instead of forcing this item to weaken it. R876 set the
precedent when `intent_argmapping_binding_leaf` became `graphitron_argmapping_match`, on the finding
that the `intent_` prefix there "recorded the default placement rather than a decision"; the
`graphitron_` family now holds 127 tables and 2 views, so neither shape is new.

**The target the conversions copy already exists.** `FieldEndpoints.derive` is the last stage of
`GraphitronFactCapture.capture`, it reads `graphitron_`, `graphql_` and `sql_` relations across three
families, it joins and ranks, and it writes `graphitron_field_table` with three `INSERT ... SELECT`
statements. It is not hand-rolled Java resolution and nothing here would be either: the rule stays
stated once, in SQL, and what changes is who runs it and when.

That precedent transfers to the ladder and to nothing outside it, which is worth stating because the
distinction is easy to lose. `meta_gatherer_corpus` defines a crawler as a gatherer carrying a corpus
row, whose rows about its own corpus "may not vary with any other corpus's contents". The graphitron
gatherer carries none and is thereby free to cross corpora, which is exactly the licence every rung of
this ladder needs and exactly what `FieldEndpoints` is already exercising. Phase 0 sits in a crawler
and therefore argues its own case rather than citing this one.

## What is in scope

The conversion ladder, bottom rung first. Each rung is convertible only once the rung below it is, and
each rung is independently landable and independently verifiable.

| rung | relation | today | reads, in `intent_` terms |
|---|---|---|---|
| 0 | `intent_spelled_table` | registered | nothing |
| 0 | `intent_name_matched_key_pair` | plain view | nothing; owner computes to `catalog` |
| 0 | `intent_condition_method_route` | plain view | nothing |
| 1 | `intent_field_reference_step_hop` | registered | the three rung-0 relations |
| 2 | `intent_resolved_type_binding` | registered | `intent_bound_table`, `intent_routine_return_binding` |
| 3 | `intent_field_reference_step_target` | plain view | rungs 1 and 2 |
| 4 | `intent_argument_scope_table`, `intent_field_scope_table`, `intent_carrier_data_field`, `intent_input_field_resolving_table` | all registered | a wider subtree of the column-scope family |
| 5 | `intent_input_field_reference_step_target`, `intent_argument_reference_step_target` | view, registered | rungs 1 and 4 |

Two things the table is saying that are easy to miss. The field walk needs only rungs 0 to 2, which is
**three registrations**, and its subtree is closed: `intent_bound_table`,
`intent_routine_return_binding`, `intent_field_chain_terminus`, `intent_field_chain_node`,
`intent_field_chain_start` and `intent_field_navigated_type` are all plain views a stage may read
inline. The other two walks need rung 4, which reaches into the column-scope family and is where this
item stops being small.

Beside the ladder, and independent of all of it: `intent_node_id_decode_hop_live` inlines an anonymous
derived table over `sql_referential_constraint` counting the foreign keys connecting an ordered table
pair, on the inner side of a `LEFT JOIN`, so it is recounted per decode endpoint. It reads one catalog
relation, so its owner computes to `catalog`, and it needs no rung of the ladder at all.

**Convert or demote, per relation.** A rung does not have to become a stage. Where a registered
relation's only reader is the stage above it, demoting it to a plain view the stage evaluates inline
is the smaller change and costs one evaluation inside one statement, which is what the registration
was buying for many readers and is not buying for one. Where it has several readers, it converts.
`DerivedReadCostTest`'s reader counts decide this per relation rather than a rule in this body, and
the commit records which way each went and why.

## Implementation

Five phases. The first is independent of the ladder entirely. After that each phase is one rung or
two, lands on its own, and leaves the tree green and the register smaller or the same.

### Phase 0: the foreign-key count becomes a captured catalog fact

Independent of everything below, the top rung of the lever order, and admitted whether or not a reader
is currently slow.

```sql
CREATE TABLE sql_table_reference (
  source_name            VARCHAR NOT NULL,
  table_schema           VARCHAR NOT NULL,
  table_name             VARCHAR NOT NULL,
  referenced_source_name VARCHAR NOT NULL,
  referenced_schema      VARCHAR NOT NULL,
  referenced_table       VARCHAR NOT NULL,
  constraints            INT     NOT NULL,
  touched_at             TIMESTAMP NOT NULL,
  PRIMARY KEY (source_name, table_schema, table_name,
               referenced_source_name, referenced_schema, referenced_table)
);
```

Grain: one ordered pair of tables at least one foreign key connects, and how many connect it. It admits
a real primary key, which nothing on the ladder does. `intent_node_id_decode_hop_live`'s
`DISCOVERED_KEY` arm joins it on the six columns it already holds and tests `constraints = 1`.

`sql_` and not `intent_` because the relation reads one family, which by the family rule makes it a
relation of that family whatever shape it takes.

**The gatherer is `jooq`, not `catalog`.** `CatalogFactCapture` writes no `sql_` row: its javadoc is
"The `jvm_` family", it records that "It used to fill the `sql_` family too, and that half is gone",
and its `capture` calls `captureExtensions` alone. `JooqFactCapture` is "the `sql_` family and nothing
else", and its `referentialConstraints` stage writes the rows this relation counts. So the stage is
`JooqFactCapture`'s, added after `referentialConstraints` and before the sweep.

**The corpus-purity argument is made directly rather than by precedent.** `meta_gatherer_corpus`
defines a crawler as a gatherer carrying a corpus row, "a transcription pass whose rows about its own
corpus may not vary with any other corpus's contents", and `jooq` carries one. So the
`FieldEndpoints` precedent does not transfer here: that is the graphitron gatherer, which carries no
corpus row and is thereby free to cross corpora. The argument this relation needs is its own and is
stronger: it reads `sql_referential_constraint` and nothing else, so its rows vary with the catalog
corpus alone and the invariant holds by construction.

**The declared owner is `jooq`, and this spec settles that rather than leaving it to the gate.** The
relation needs a `meta_relation` row, that roster only shrinking. All fourteen declared `sql_`
relations name `catalog` as owner while `JooqFactCapture` writes them, and the `jooq` gatherer owns no
declared relation at all, so the tree's declared ownership and its actual writer already disagree for
this family. Declaring `catalog` would entrench a mismatch no gate currently catches; declaring `jooq`
makes this the first correct row and leaves fourteen beside it that are not. Take `jooq`, and file the
fourteen as a Backlog item rather than correcting them here: they are a pre-existing declaration
defect this item happens to surface, and reconciling them is R877's kind of work.

**The lifecycle is the family's stamp-and-sweep, so the table carries `touched_at`.** Fourteen of the
fifteen `sql_` tables carry one, `sql_referential_constraint.touched_at`'s comment states the rule the
sweep implements, and `JooqFactCapture.capture` closes with `sweep(dsl, sourceNames(tables),
touchedAt)`. The stage takes the same instant and the sweep covers it. This is not tidiness: a pair row
that outlives the foreign key it counted says `constraints = 1` about a pair with none, which is
exactly the arm the decode rule's `LEFT JOIN` reads. The one table without a stamp,
`sql_table_record_supertype`, is written by the one stage called without an instant, which is the
precedent for the other arm and not the one this relation is on.

**The reach for the constraint name.** After the change the `DISCOVERED_KEY` arm still joins
`sql_referential_constraint` on six columns, of which only the first three prefix its primary key
`(source_name, table_schema, table_name, constraint_name)`, and that relation carries no other index.
Measure whether the join wants one at the same time as the phase-0 timing; a three-column prefix seek
on a relation of this size very likely does not, and the commit records the reading either way rather
than the expectation.

Deliberately not a `constraints` column on `sql_referential_constraint`, which would repeat one value
down every constraint of a pair, the denormalisation the referenced-side discipline declines.

### Phase 1: the departure, and the hop all three walks read

Rung 0 then rung 1, in one phase because rung 0 buys nothing on its own.

`intent_spelled_table` becomes `graphitron_spelled_table`, written by a stage of the graphitron
gatherer immediately before `FieldEndpoints.derive`. Its registration is retired. Its `_live` view is
deleted, the rule moving into the stage's `INSERT ... SELECT` unchanged.

`intent_field_reference_step_hop` becomes `graphitron_field_reference_step_hop`, written by the stage
after it, reading `graphitron_spelled_table` and the two rung-0 plain views inline. Its registration is
retired. It keeps `ix_field_reference_step_hop_step`, whose comment already prices what that index
removes: reading the field walk whole costs 18308 scans without it and 523 with it.

Two registrations retired, none added, and the hop is the relation *all three* walks read, so this
phase is the one that moves the most for the least.

`intent_name_matched_key_pair`'s owner computes to `catalog` and it stays a plain view here. Moving it
into the catalog family is one of the nine misplacements R876 enumerates and is that item's to take;
this phase only stops it being read through a registration.

### Phase 2: the field walk

`intent_resolved_type_binding` converts or demotes per the rule above; eight view bodies and `StoreNodeTables` name it, so
converting is the expectation and the commit says which way the count sent it.

`intent_field_reference_step_target` becomes `graphitron_field_reference_step_target`, written by a
stage as a fold: position 0 is an insert joining the hop table to the type binding; position k joins the
rows written at position k-1 to the hop on the eight columns the step index serves; the loop stops when
a pass inserts no rows, bounded by `MAX(position)` on the walk's own entry relation and asserted so a
non-terminating edit fails loudly. `targets` and `candidates` are then one grouped `UPDATE` per graph
rather than two window functions inside a recursive term.

Indexed and not keyed: the grain includes `constraint_name` and `fk_on_from`, both meaningfully
nullable, and H2 refuses a primary key over a nullable column. The coordinate index is shaped like
`ix_argument_reference_step_target_coordinate` and carries a `COMMENT ON INDEX` naming its reader.

This phase is what reaches `intent_node_id_instruction_live`, the 592 s statement R953 measured, since
that rule reads the field walk once per `table_node` row.

### Phase 3: the foreign-key count and the field walk, measured together

No code. The acceptance measurement for phases 0 to 2 on a `sis` store copy, written into the body,
and the decision point for whether phases 4 and 5 are this item's or a successor's. If the round is in
seconds here, the remaining rungs are a modelling tidy rather than a fix and say so in their own
priority.

### Phase 4: the column-scope departures

Rung 4, and the phase that is not small: `intent_argument_scope_table`, `intent_field_scope_table`,
`intent_carrier_data_field` and `intent_input_field_resolving_table`, which reach `intent_type_backing`,
`intent_errors_field`, `intent_field_payload_producer`, `intent_poly_member` and
`intent_field_participant_scope_table`. All still bottom out in captured facts; the subtree is simply
wider, and it belongs to the column-scope family rather than the reference family. Four registrations
convert or demote.

Split this into its own item if the phase-3 measurement says the field walk was the cost. The body says
so rather than leaving it to judgement: an item that reaches into a second family on a measurement that
did not name it is scope creep whatever its doctrine.

### Phase 5: the input-field and argument walks

`intent_input_field_reference_step_target` and `intent_argument_reference_step_target` become
`graphitron_` stage-written tables on the phase-2 shape. The argument walk's registration is retired,
the third of the three. The register ends this item between three and eight rows smaller than it
started, having gained none.

**What every phase owes the register's prose, because the register is set-relative.**
`meta_materialize.reason`'s own comment requires it: "A registration whose source view reads another
registration's target is priced by that other row still being there, so dropping a neighbour can make
this one dearer rather than cheaper." A retirement re-prices neighbours exactly as an addition does. So
each phase edits the `reason` of every surviving registration whose rule reads what that phase
converted, and `intent_node_id_decode_hop_live`'s is the one most of them touch, its argument being
built on the walks' per-driving-row cost. Separately and on a different surface: the refuted claim that
a second naming of the endpoint subtree "would dominate the read" is on `intent_node_id_decode_hop`'s
`COMMENT ON TABLE` and is the only occurrence of that phrase in the DDL, so correcting the reason does
not reach it. Both surfaces, named separately, or the correction is made in one place and left standing
in the other.

## What was measured

Fixture only, and the section after this one is what corrects that. Taken on the scaled registry
fixture (`MaterializedRegistryFixture.scaledSdl`, with a `@nodeId` input field added so the
`INPUT_FIELD` decode arm has a population), three sweeps per configuration, best of three,
`OPTIMIZE_REUSE_RESULTS` off. `intent_node_id_decode_hop_live`, which is the statement the refresh
issues per graph:

| decode endpoints | as shipped | input-field walk stored | walk and foreign-key count stored |
|---|---|---|---|
| 96 | 73.4 ms | 10.5 ms | 1.0 ms |
| 192 | 264.0 ms | 20.0 ms | 1.4 ms |
| 384 | 1069.4 ms | 41.8 ms | 2.8 ms |

As shipped the rule grows by 3.6 and then 4.05 per doubling, an exponent of about 1.93. Both stored
forms grow by 1.9 to 2.1, so linear. The walk answers in 0.8 to 2.7 ms standalone at these sizes and
the foreign-key count in 0.3 ms over 56 rows at every size, so neither is expensive; what costs is that
each is evaluated once per driving endpoint row. `intent_argument_reference_step_target` costs nothing
measurable, being an indexed table since R943, which is the same claim from the other direction.
Snapshotting was proved to change cost and nothing else: `EXCEPT` in both directions returned zero rows
at every size.

One limit on the table and one correction to what it leaves open. Every column of it is neutral
between a stored rule and a registered one: what it prices is rows being on disk, not who wrote them,
so the case for who wrote them is the structural finding above and never this table.

The terms have been separated once already, on a consumer capture rather than a fixture, and
`intent_node_id_decode_hop_live`'s own `meta_materialize` reason carries the reading. Against a 4.7 s
baseline, dropping one inner-side naming at a time leaves 3.4 s without the argument-site walk, 1.9 s
without the input-field one, 3.3 s without the derived table counting foreign keys per table pair, and
4.2 s without the closing `MAX(position) OVER`, while dropping both walks together leaves 1.1 s. The
row states the conclusion as arithmetic: the two walks "cost 12 ms and 25 ms standalone and account
for 6.0 s of the rule between them, which is the per-driving-row re-evaluation stated as arithmetic".

So each half is separately large on a real population: the foreign-key count about 1.4 s of 4.7 s, the
two walks about 3.6 s between them. Two things that reading does not settle. It was taken before R943
registered the argument-site walk, so the shipping baseline is not 4.7 s and the remaining unstored
term is the input-field walk alone. And it is one population at one moment, where the conditioning
measurement below asks about growth. It narrows what that measurement owes rather than replacing it.

**The refresh's graph predicate does not prune.** With three graphs of 72 endpoints in one store, the
refresh of a single graph came out at 170.9 ms against 175.3 ms for the same statement with no
predicate at all, and 22.6 ms for that graph alone in a store of its own.
`Materializations.refreshPartition` issues `INSERT INTO target SELECT * FROM source WHERE graph_name = ?`,
and that predicate cannot reach inside a recursive term, so a workspace store holding several subgraph
modules pays the rule over all of them at every module's refresh. A stage writes one graph's partition,
so this is the one cost term that a registration cannot reach and a conversion removes by construction.

**Rung 3 of the lever order was tried on the walk and does not reach it.** Restating `last_position` as
a join to a `GROUP BY` derived table instead of a window function measured 41.2 s against 170.9 ms, a
regression of about 240 times, because it names the body twice. Moving the site discriminator out of the
`ON` clause, and hoisting the walk into a non-recursive `WITH`, each changed nothing. Splitting the rule
into one arm per site buys 3.6 times and stays quadratic. That last reading also refutes a claim on
`intent_node_id_decode_hop`'s own comment, that a second naming of the endpoint subtree "would dominate
the read", which this item corrects where it touches that comment.

## What this plan is conditioned on

The table above is a shape claim on a synthetic population, and this family has produced a wrong reading
before. The `store-performance` skill's posture section records one: "a family of recursive
reference-target views was the expensive term, when timing each relation on its own said the term was
somewhere else entirely." The investigation that filed this item had a second conclusion overturned,
reporting that statistics were not the lever from a regime that measured identically at every fixture
size, which R953's readings on a copy of the `sis` store contradict.

So the first act is a measurement, and it is one nobody can take without the `sis` workspace on disk.
**The implementer takes it at pickup, before any code.** Per the `store-performance` skill's method, on
a copy of the `sis` store, timing `intent_node_id_decode_hop_live` and `intent_node_id_instruction_live`
per graph:

1. as shipped;
2. with R953's lever 2 alone, a static `SELECTIVITY 1` on `graph_name`;
3. with the foreign-key count snapshotted into a table, walks left as views;
4. with the field walk snapshotted, the rest left alone;
5. with the input-field walk snapshotted, the rest left alone;
6. with all three, which is the shipping shape.

Four, five and six are separated because the phases above land in that order and each owes its own
evidence. The register's ablation has already separated the terms once on a consumer population, so
what these configurations add is growth across sizes and a post-R943 baseline, not the separation
itself. Each snapshot is proved answer-preserving by `EXCEPT`
in both directions before it is timed.

What the plan becomes under each outcome:

- **Lever 2 alone brings the round into seconds.** The cost claim is R953's, not this item's. Phases 0
  to 2 still land on the modelling claim and phases 4 and 5 drop to priority 3, which is an honest
  outcome rather than a defeat: the register is still holding up a subtree that is entirely resolution.
- **The field walk is the term.** Phases 0 to 3 are the item and phases 4 and 5 are a successor.
- **The input-field walk is the term.** Phases 4 and 5 are needed and the item runs to its end.
- **The foreign-key count is the term and neither walk is.** Phase 0 is the item and ships alone.
- **The `sis` store cannot be obtained.** R943 and R953 both took readings on a copy, so this is a
  scheduling problem rather than a structural one, and it does not stall the item. Phase 0 and phases
  1 and 2 land on the modelling claim, which no measurement moves, with the fixture table and the
  register's consumer ablation as the cost evidence on record. Phases 4 and 5 wait, because they reach
  into a second family and the only thing that justifies that reach is a measurement naming the
  input-field walk. Say in the changelog entry that the growth claim went unverified, rather than
  letting a green build stand in for it.

Under every outcome the measurement is written into this body, replacing this list with what it found.

## Tests

Every conversion is proved against the rule it replaces rather than re-asserted, which is what keeps a
phase cheap to review however large the ladder gets.

- **Answer preservation, per rung.** `EXCEPT` in both directions between the stage-written table and the
  view text it was converted from, over a populated store, per graph. This is the oracle the whole plan
  rests on and it exists precisely because these rules are expressible as views.
- **`ReferenceStepTargetTest`, `ArgumentReferenceStepTargetTest`, `ReferenceStepFanoutTest`,
  `ChainTerminusTest`, `InputFieldResolvingTableTest`** already pin what these relations answer at the
  coordinate grain. They must pass with no edit beyond the renames; a test whose expectations move is a
  signal a conversion moved an answer.
- **A case for `sql_table_reference`'s grain**: a pair connected by two foreign keys is one row saying
  two, a self-referential key is one row, a pair with none has no row. The third is what the decode
  rule's `LEFT JOIN` depends on.
- **`MaterializeRegistryGateTest`** is the gate that notices this item most. `REGISTRATIONS` falls
  rather than rises and its refresh-stage depth moves; both are equality-pinned so they cannot move in
  a commit arguing for something else. `nothingMaterializesOutsideTheMechanism` keeps its meaning
  untouched, every converted relation having left the `intent_` prefix its scan is scoped to.
- **`DerivedReadCostTest`** prices every pair of a registration and a relation reaching its target. A
  retirement moves its pinned set as surely as an addition does, and the set is edited per phase, which
  is the confrontation it is built to force. Its reader counts are also what decides convert against
  demote.
- **`MetaDeclarationGateTest`** binds on any converted relation that carries a declaration, including
  the view-ownership gate, which is the mechanical check that a moved relation's reads match its new
  owner.
- **`FactCaptureAgreementTest`, `FactSchemaGateTest`, `CaptureCorpusIsolationTest`** are the regression
  surface for a column list or a capture path that moved, and the third is what covers a producer's
  reads, which no catalog parse can see.
- **Acceptance evidence for the goal**, which a green build does not supply: the `sis` timings from the
  section above, re-taken on the shipped tree per phase and written into the changelog entry. The goal is
  a growth claim, and only the same configurations answering linearly demonstrate it.

## What this item does not do

- **It does not mint an ordering mechanism for producers inside the refresh pass.** It does not need
  one: converting bottom-up means no stage ever reads a registered target. R876's open question, what
  orders two relations under one owner, stays open and stays R876's; this item avoids it rather than
  answering it, and the phase table is the proof that avoiding it is possible for this subtree.
- **It does not change what admits a hand-written derivation.** `HAND_WRITTEN`'s impossibility criterion
  stands, and every relation here leaves its scope by moving family rather than by weakening it.
- **It does not move `intent_name_matched_key_pair` into the catalog family**, which is one of the nine
  misplacements R876 enumerates and that item's to take.
- **It does not touch R953's levers 1 and 2.** They fix which plan one evaluation gets; this changes how
  many evaluations there are. Lever 2 should land whatever this item does.

## Relation to other items

**R953** is a different defect on an overlapping path and the two compose. It diagnoses a statistics
cliff: `intent_field_reference_step_hop.graph_name` at H2's default selectivity makes the planner choose
a one-column index over the step index, and the first refresh on a store plans with no statistics at all.
That is a cliff in the cost of *one* evaluation; this item is about the *number* of evaluations. They
multiply, because these walks join that table in both the anchor and the step. Its lever 3 proposes
registering the field walk, which phase 2 supersedes: the same rows land, written by their owner rather
than scheduled by the register. The record of that split is **one-way and this item's**: R953 is
`Backlog` and nobody has picked it up, so a mutual clause would be a gate with no owner, and
`depends-on:` here stays empty deliberately rather than inventing a wait on a transition nobody is
committed to. Phase 2 states the supersession, and R953's body is amended whenever it is next touched;
an implementer who reaches phase 2 first and finds lever 3 already landed takes the registration as
that phase's fallback and says so. Its lever 2 is a static `SELECTIVITY` on a partition column and
stays true of a stage-written table too, so it survives this item unchanged.

**R876** is the doctrine this item instantiates, and this is the first subtree to be taken bottom-up
rather than one relation at a time. It contributes three things back: the finding that a 27-relation
closure bottoms out entirely in captured facts, the observation that a bottom-up order needs no
successor to `meta_materialize_dependency`, and between three and eight register rows retired against
its twenty-three. The precedent it follows is R876's own `graphitron_argmapping_match`.

**R900** is the naming sweep, and the renames here are taken with their moves rather than deferred to
it, on R876's reasoning that correcting the family and the noun together is one edit rather than two.
Nothing here pre-empts the spellings that sweep is about.

**R942** is a gate rather than a fix. Its detector drops the `INNER_SIDE` position because
`ViewReferences`' javadoc records that an inner-side reading is the executed shape "only where the join
order is fixed, which is the outer joins", and asks for such a naming to be priced rather than asserted.
`intent_node_id_decode_hop_live` naming the input-field walk is one of those, now priced. A scan of the
DDL puts the outer-join subset at 9 distinct (reader, view) pairs across 12 namings against 45 distinct
on inner joins, so widening is a nine-row question rather than the fifty-six R942 costed. Phase 0 removes
the foreign-key naming from that census anyway.

**R899** exists to make a registration's alternative countable so the last lever stops being the first
reached for. This item is the counted alternative for one subtree, and it retires register rows rather
than adding them, which is the direction R899 was filed to make ordinary.

**R857** and **R872** would stop a dev round refreshing this rule when the edit did not touch it, which
mitigates the symptom on the dev loop and does nothing for a `generate` or for CI. R872 also owns the
catalog family's refresh lifecycle, which phase 0's relation joins.

## Other solutions we've considered

- **Register the two unstored walks**, which is R953's lever 3 and the shape R943 gave the third walk.
  This is the cheap fallback and it remains available per phase: it lands the same rows on disk, needs no
  conversion, and is three DDL lines. It is not the plan for two reasons the structural finding supplies.
  It grows a register that is already holding up a subtree with no cross-family rule in it, which is the
  accretion R876 diagnoses. And it cannot reach the per-graph refresh term, the one measured cost a
  conversion removes by construction. If a phase turns out to be larger than its measured win, taking the
  registration for that rung and recording why is a legitimate outcome rather than a failure.
- **A producer inside the refresh order, with a `meta_` relation declaring what it reads.** This was the
  earlier reading of the item and the bottom-up order makes it unnecessary. It is also the one artifact
  the fact-model page rules out by name, a hand-kept ordering with no derivable source, "which is the
  shape `SchemaIdentifierDriftCheck` exists to refuse"; an admissible version would derive the edge set
  from the producer's own source and gate on drift, which is a larger piece of work than this item and
  belongs with R876.
- **A rewrite of the walk.** Three were measured, above. One regressed about 240 times, two changed
  nothing, and the one that helped left the exponent alone.
- **R953's lever 2 alone.** Cheap, stated in a Backlog body, orthogonal, and whether it is *sufficient* is the
  first question the conditioning measurement asks.
- **Converting the whole 27-relation closure in one move.** Eight registrations and two families, with
  the column-scope half reached only through two of the three walks. The phase table exists so the
  measurement decides how far up the ladder this item goes, rather than the doctrine deciding it in
  advance.

## Provenance

Filed 2026-09-16 from an investigation into a `graphitron:dev` round on the `sis` consumer taking more
than twenty minutes, which began as a question about `intent_node_id_decode_hop_live` and found the shape
defect underneath it. The investigation's instrument was a throwaway probe in a scratch directory, which
is the failure mode R899 names, so its figures are recorded above rather than left to be re-derived. Two
of its conclusions were overturned before filing and are recorded in this body rather than dropped: that
statistics were not the lever, and that the window function was what blocked the graph predicate from
pruning.
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

*Response.* Both halves taken, and verified against the tree before acting: `CatalogFactCapture.capture`
calls `captureExtensions` alone and its javadoc records the `sql_` half having moved, while
`JooqFactCapture`'s referentialConstraints stage writes the rows. Phase 0 now puts the stage in
`JooqFactCapture` after that stage and before the sweep, and names the `jooq` gatherer as owner. The
declaration fork is settled in the body rather than deferred to the gate: declare `jooq`, on the
reasoning that it is the first correct row rather than the fifteenth wrong one, and file the fourteen
`sql_` relations declaring `catalog` as a Backlog item rather than reconciling them inside this item.

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

*Response.* Taken. The DDL carries `touched_at TIMESTAMP NOT NULL`, the stage takes the capture's
instant and the existing `sweep(dsl, sourceNames(tables), touchedAt)` covers it. The body states the
discipline and the reason, including the failure the finding names: a pair row outliving its foreign
key would say `constraints = 1` about a pair with none, on the arm the decode rule's `LEFT JOIN`
reads. `sql_table_record_supertype` is named as the precedent for the other arm and why this relation
is not on it. The smaller point is taken as a measurement rather than an assertion: the six-column
reach into `sql_referential_constraint` is timed alongside the phase-0 reading and the commit records
what it found, rather than the body predicting that a three-column prefix seek suffices.

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

*Response.* Taken, and it improved two places rather than one. Phase 0 now makes the corpus-purity
argument directly, that the relation reads `sql_referential_constraint` alone so its rows vary with
the catalog corpus only and the crawler invariant holds by construction, and cites no precedent. The
`FieldEndpoints` precedent is kept for the graphitron ladder, where it does apply, and the reason is
now stated instead of assumed: the graphitron gatherer carries no `meta_gatherer_corpus` row and is
thereby free to cross corpora, which is the licence every rung needs.

**Finding 4 (question two, non-blocking). Slice two's edit list points the "would dominate the read"
correction at the wrong surface.**

The bullet reads "`intent_node_id_decode_hop_live`'s reason, whose argument is built on the two
walks' per-driving-row cost, and whose claim that a second naming of the endpoint subtree 'would
dominate the read' the measurements above refute". The first half is right, the reason row is built
on that cost. The second half is not: the refuted sentence is on `intent_node_id_decode_hop`'s
`COMMENT ON TABLE`, and it is the only occurrence of the phrase in the DDL. An implementer working
the list edits the reason and leaves the refuted claim standing where it lives. Name both surfaces.

*Response.* Taken. The plan was re-cut around a bottom-up conversion and no longer carries that edit
list, so the obligation is restated at the end of the phases in the form the finding asks for: each
phase edits the `reason` of every surviving registration whose rule reads what it converted, and the
"would dominate the read" claim is named separately as living on `intent_node_id_decode_hop`'s
`COMMENT ON TABLE` and reachable from nowhere else.

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

*Response.* Taken, and it is the most useful finding of the round. The passage is quoted in "What was
measured" with its figures, the "never timed apart" limit is withdrawn, and the two halves are given
their rough sizes on a real population, the count at about 1.4 s of 4.7 s and the two walks at about
3.6 s between them. Two caveats are stated with it rather than left implicit: the reading predates
R943 registering the argument-site walk, so 4.7 s is not the shipping baseline and the remaining
unstored term is the input-field walk alone, and it is one population at one moment where the
conditioning measurement asks about growth. The conditioning configurations are re-described as adding
growth and a post-R943 baseline rather than the separation itself.

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

*Response.* Taken, as the one-way form. This item records the supersession, R953's body is amended
whenever it is next touched, and `depends-on:` stays empty deliberately with the body saying why
rather than leaving a reader to infer it. The plan also says what an implementer does if lever 3 lands
first: take the registration as that phase's fallback and record it. "Already specced" is corrected to
"stated in a Backlog body".

**Finding 7 (question one, non-blocking). The conditioning measurement has four outcomes and no
branch for not being able to take it.**

"What this plan is conditioned on" says the measurement is "one nobody can take without the `sis`
workspace on disk" and makes it "the implementer's first act at pickup, before any code". All four
outcomes below presuppose it was taken. R943 and R953 both took `sis` readings, so this is plainly
obtainable and not a reason to doubt the plan, but a Ready item whose first act may be unavailable
should say what happens then. The body already contains the answer: slice one is justified by the
modelling claim alone, which no measurement moves, and "Other solutions" says slice one would stand
as its own item. State it as the fifth branch rather than leaving the implementer to assemble it.

*Response.* Taken. A fifth branch says what happens if the store cannot be obtained: phase 0 and
phases 1 and 2 land on the modelling claim with the fixture table and the register's ablation as the
cost evidence on record, phases 4 and 5 wait because nothing else justifies reaching into a second
family, and the changelog entry says the growth claim went unverified rather than letting a green
build stand in for it.

**Small, and left as findings rather than corrected here because their resolution depends on
Finding 1.** The Goal says "the decode rule joins four stored relations" and slice three says it
"becomes a join of one view to three stored relations". Counting the shipping shape:
`intent_node_id_decode_endpoint` is the view, and `intent_argument_reference_step_target`,
`intent_input_field_reference_step_target`, `sql_table_reference` and `sql_referential_constraint`
are four stored relations, the last of them because slice one still reaches it for the constraint
name. One of the two numbers is wrong, and which one depends on whether that reach survives Finding
1's rework.

*Response.* Both numbers are gone. The re-cut's Goal no longer counts the decode rule's operands and
the phase that used to is now a measurement phase with no such sentence, so there is nothing left to
be inconsistent. The underlying fact the finding establishes is kept where it matters: phase 0 still
reaches `sql_referential_constraint` for the constraint name, and the body now says so explicitly and
asks whether that reach wants an index.

### Round 2 (2026-09-16, Spec -> Ready, reviewer session 01AXRLbCvhG9iCJNKVHG5akT)

Verdict: withhold. Two blocking findings on question two. Both are about the shape of a phase rather
than about the goal, which question one clears: a consumer whose schema is large gets a
`graphitron:dev` round, a `generate` and a CI build whose cost grows with the schema rather than with
its square, because the `@reference` walk every `@nodeId` decode reads becomes rows the graphitron
gatherer wrote once per capture instead of a recursive view re-expanded under each driving row. The
structural finding checks out as computed rather than as argued: the closure of the three walks
through `intent_` relations is 27 relations with 8 registered, the field walk's own closure carries
exactly the three registrations the ladder names and no hand-written table, and every rung 0 to 2
relation's inputs are captured facts or plain views over them. `FactCapture.capture` runs the jooq,
catalog and sdl gatherers before the graphitron one, so a stage placed before `FieldEndpoints.derive`
reads current `sql_`, `jvm_` and `graphql_` rows. Round 1's findings are all answered in the body as
their responses say.

**Finding 1 (question two: architecture fit). The seam dissolves bottom-up for registered targets,
and rungs 4 and 5 read hand-written targets the same seam covers and nothing on the ladder converts.**

"The seam is self-dissolving bottom-up" and "converting bottom-up means no stage ever reads a
registered target" are both true and both too narrow. `FactCapture.capture` runs
`GraphitronFactCapture.capture`, flushes, and then runs the hand-written producers
`ClassificationDomainCapture.derive`, `InputOccurrencePaths.derive`, `ArgMappingCandidates.derive`,
`TypeBackingRows.derive` and `AuthoredClaimRejectionRows.derive` before `Materializations.refresh`. A
stage inside the graphitron gatherer therefore reads a hand-written table's *previous* capture's rows,
exactly as it would a registered target's. Computed from the DDL: the input-field walk's closure
contains `intent_input_occurrence_path`, `intent_input_occurrence_path_step` and
`intent_type_backing_class`, and the argument walk's contains `intent_type_backing_class`; all three
are `HAND_WRITTEN` and written after the gatherer. The field walk's closure contains none of them,
so phases 1 and 2 are placed correctly and this finding does not touch them. Phase 4's list of what
rung 4 reaches names `intent_type_backing` and stops one relation short of the table under it.

The fix is placement rather than a mechanism, and the tree already has the shape: `ArgMappingCandidates.derive`
writes `graphitron_argmapping_candidate`, the R876 precedent this body cites, and it runs in the
derivation stratum after the hand-written producers, not as a stage of `GraphitronFactCapture.capture`.
None of the five hand-written producers reads any ladder relation (checked against their `Tables`
imports and the closures of the views they do read), so placing rungs 4 and 5 after them closes the
seam with no cycle. What would satisfy this: phases 4 and 5 say where their producers run and why
that position is current for every input, and the "What this item does not do" bullet says "no stage
ever reads a registered or hand-written target that a later step of the pass writes", or the
equivalent in the author's words. Whether the rung 0 to 2 stages stay in the gatherer or all of them
move to the stratum is the author's call; the body should make it, since it decides which class an
implementer edits.

**Finding 2 (question two: architecture fit). Phase 2's fold restates a rule the tree states in SQL,
on a reading of the view that is not what the DDL holds, and phase 5 inherits the shape.**

The structural finding says the rule "stays stated once, in SQL, and what changes is who runs it and
when", and phase 1 does exactly that, "the rule moving into the stage's `INSERT ... SELECT`
unchanged". Phase 2 does something else: a Java loop of one insert per position with a termination
bound and an assertion, then "one grouped `UPDATE` per graph rather than two window functions inside
a recursive term". Two things about that.

The reading is off. In `intent_field_reference_step_target` as shipped, `targets` and `candidates`
are `MAX(target_rank) OVER` and `COUNT(*) OVER` in the outer `SELECT` over the finished `chain`, not
inside the recursive term; the recursive term is a plain `UNION` of the seed with one join to the hop
table on the eight columns `ix_field_reference_step_hop_step` serves. So the fold removes nothing that
is there, and the walk standalone is milliseconds by this body's own table; the cost was the
per-driving-row re-evaluation, which any once-per-capture write removes.

The single-statement form is available. H2 2.4.240 accepts `INSERT INTO t WITH RECURSIVE chain AS
(...) SELECT ..., COUNT(*) OVER (...) FROM chain`, checked on the jar in the local repository with a
seed filtered to one graph. So the view text moves into the stage as phase 1's does, per graph, with
the `EXCEPT` oracle comparing two evaluations of the same text rather than two texts, and the
termination assertion is unnecessary because `UNION` reaches a fixpoint and `position` strictly
increases. A Java fixpoint loop has one precedent in the tree, `ClassificationDomainCapture`, which is
a `HAND_WRITTEN` producer admitted on the argument that no view could state its rule; this rule is
stated by a view, so the licence does not transfer. What would satisfy this: phase 2 (and phase 5,
"on the phase-2 shape") adopt the single-statement form, or the body states the H2 limit or the
measurement that makes the fold necessary, so an implementer is not left to pick.

**Non-blocking, question two.** Phase 0 says "the existing `sweep(dsl, sourceNames(tables),
touchedAt)` covers it". The sweep iterates `TABLES_TO_SWEEP`, a fixed list in `JooqFactCapture`,
so the new table is covered once it is added there; the discipline the body names is right, the
sentence understates the edit by one line.

**Non-blocking, question one.** Phase 2 says `intent_resolved_type_binding` "has five named readers".
Eight view bodies name it in the DDL (`intent_field_reference_step_target`,
`intent_field_column_scope_live`, `intent_field_reference_discovery`, `intent_type_backing`,
`intent_field_participant_scope_table`, `intent_field_scope_table_live`, `intent_carrier_routine_hop`,
`intent_input_field_filter_role_live`) and `StoreNodeTables` reads it from Java. The count only
strengthens the convert expectation, so nothing in the plan moves; the number should match whatever
`DerivedReadCostTest` says when the phase lands.

### Round 3 (2026-09-16, Spec -> Ready, reviewer session 01XUN2yJExpaZCTtLj3DPWqC)

Verdict: withhold. Nothing new is blocking; round 2's two blocking findings stand unanswered in the
body, and both were re-checked against the tree by this session rather than taken on trust. The plan
sections still read as they did when round 2 was written: phase 2 still describes a Java fold and
"two window functions inside a recursive term", phases 4 and 5 still say nothing about where their
producers run, and neither finding carries a response. A spec with an open blocking finding is not
Ready whatever a third reader thinks of it, so this round exists to record that the findings were
independently confirmed and to say what the next pass will look at.

**Round 2, finding 1 stands (question two).** `FactCapture.capture` runs `GraphitronFactCapture.capture`,
flushes, then runs `ClassificationDomainCapture.derive`, `InputOccurrencePaths.derive`,
`ArgMappingCandidates.derive`, `TypeBackingRows.derive` and `AuthoredClaimRejectionRows.derive`, and
only then `Materializations.refresh`. Recomputed from the DDL: the input-field walk's closure holds
`intent_input_occurrence_path`, `intent_input_occurrence_path_step` and `intent_type_backing_class`,
the argument walk's holds `intent_type_backing_class`, and rung 4's own reach holds all three through
`intent_type_backing`. All are `HAND_WRITTEN` and written after the gatherer. The field walk's closure
holds none, so phases 1 and 2 are placed correctly. What satisfies it is what round 2 said: phases 4
and 5 name where their producers run and why that position is current for every input, and the
"does not do" bullet widens from "registered target" to any target a later step of the pass writes.

**Round 2, finding 2 stands (question two).** In `intent_field_reference_step_target` as shipped the
recursive term is a plain `UNION` of the seed with one join to the hop on the eight indexed columns;
`DENSE_RANK`, `MAX(target_rank) OVER` and `COUNT(*) OVER` sit in the outer `SELECT` over the finished
`chain`. Phase 2's fold therefore removes nothing that is there, and the plan's own structural finding
("the rule stays stated once, in SQL") argues against it. What satisfies it is what round 2 said:
phase 2 and phase 5 adopt the single-statement `INSERT ... WITH RECURSIVE ... SELECT` per graph, or
the body names the H2 limit or measurement that makes the fold necessary.

**Everything else was re-verified and holds.** The closure of the three walks through `intent_`
relations is 27 relations with 8 registered, exactly the eight the ladder names, and every leaf is
`graphitron_`, `graphql_`, `sql_`, `jvm_` or `store_`. Rung 0's inputs are all captured facts, and
`graphitron_spelled_reference_entry` is written by `SdlCapture.captureGraphitronAnchors` before the
graphitron gatherer runs, so a stage placed before `FieldEndpoints.derive` reads current rows for
phase 1. `GraphitronFactCapture.capture` runs eight stages ending in `FieldEndpoints.derive`.
`JooqFactCapture.capture` writes `referentialConstraints` and closes with the sweep over
`TABLES_TO_SWEEP`; `CatalogFactCapture.capture` calls `captureExtensions` alone. Fourteen of the
fifteen `sql_` tables carry `touched_at`, `sql_table_record_supertype` being the one without. All
fourteen declared `sql_` relations name `catalog` as owner and none names `jooq`; `jooq` carries a
`catalog` corpus row and `graphitron` carries none. The `graphitron_` family is 127 tables and 2
views, `graphitron_argmapping_match` is among the views, and `sql_table_reference`,
`graphitron_spelled_table` and `graphitron_field_reference_step_hop` are free names. Both indexes the
body names exist with the comments it quotes, "would dominate the read" occurs once in the DDL and on
`intent_node_id_decode_hop`'s table comment, and R953 is `Backlog`. Every test class, gate method and
fixture the body names exists under that name.

**Corrected in passing.** Phase 2's "five named readers" of `intent_resolved_type_binding` is now the
count the DDL and `StoreNodeTables` give, per round 2's non-blocking note; the convert expectation it
supports is unchanged.
