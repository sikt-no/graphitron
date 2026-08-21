---
id: R728
title: "@nodeId encode and decode become store relations, and an instruction the generator drops fails the build"
status: In Progress
bucket: feature
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-19
last-updated: 2026-08-21
---

# @nodeId encode and decode become store relations, and an instruction the generator drops fails the build

`@nodeId` is an instruction. The manual states it in one sentence
(`docs/manual/reference/directives/nodeId.adoc`): "The site axis decides direction: on a
`FIELD_DEFINITION` the directive *encodes* the parent's primary-key columns into the opaque ID; on
`INPUT_FIELD_DEFINITION` and `ARGUMENT_DEFINITION` it *decodes* the ID back to typed key columns at
the carrier."

So there are two states, not three. Either a slot carries the instruction, explicitly or by one of
the rules below, and the generator owes an encode or a decode; or it does not, and there is nothing
for the generator to do. The contract that follows is the one this item enforces everywhere: **a slot
carrying the instruction never delivers the wire format to consumer code, and never takes it from
there.** A service does not know that node ids exist, and neither does the bean that feeds it or the
exception an `@error` type reads. Nothing else needs saying, and this item introduces no vocabulary for it:
encode and decode are the words the manual uses and the words the code already carries in
`CallSiteCompaction.NodeIdEncodeKeys`, `CallSiteExtraction.NodeIdDecodeKeys` and the generated
`encode<TypeName>` / `decode<TypeName>` helpers.

The bug is the third state the generator actually has today, which is being given the instruction and
dropping it. The author writes the directive, the build says nothing, and the raw base64 flows through
to consumer code that takes it apart by hand. The development principles name that outcome as a smell
in their own terms (`docs/architecture/explanation/development-principles.adoc`, "Boundaries decode
and encode; the interior is typed"): "a bypass around classified information the boundary already
carries."

A field report against 10.0.0-RC32 named four coordinates where it happens. This item does not take
that list on trust. It makes the instruction and its resolution facts, which turns "does `@nodeId`
mean the same thing everywhere" from a claim into a query, and then answers every coordinate the
query finds. The census is wider than the report in one direction and narrower in another: the
reported `@error` case is one arm of four that share its cause, and one reported case already works
and needs only a message and a page.

There is no coordinate where a dropped instruction is wanted, so the rule is total and
unconditional. Three candidates were tested and none survives. A documentation marker ("this ID is a
`PlasstildelingV2`") is the bypass the principle names, and the type system plus the manual already
do that job. A forward declaration written before its destination exists wants the build to fail,
which is the signal. And an interface declaration site does not exist as a pattern: SDL directive
applications are per-declaration with no interface inheritance, while a `@table` interface is itself
table-bound and encodes like any bound type, so an author who wrote `@nodeId` only on the interface
field has made a mistake worth reporting.

## Where the instruction comes from

The instruction has three forms, and the manual documents all three. Getting the population right
matters more than anything else in this plan, because the detection is "instructed and not carried
out" and an instruction the population misses is a coordinate that stays silent.

* **Explicit.** `@nodeId(typeName: T)`. Captured as a row in `graphitron_field_node_id` on
  `(graph_name, type_name, field_name)`, which covers output fields and input fields alike, or in
  `graphitron_argument_node_id` on `(graph_name, type_name, field_name, argument_name)`. Both carry
  `node_type_ref` as the author wrote it and a source position.
* **Bare.** `@nodeId` with no `typeName:`, which is the same captured row with `node_type_ref` NULL,
  the relation's own comment already reading that NULL as "inference when NULL is a derivation". The
  manual gives inference exactly two rules: (a) on a non-`@reference` object field, the containing
  type is itself a `@node`; (b) on a `@reference` field or jOOQ-record input field, exactly one
  `@node` type binds to the same target table. Rule (b) "is the only place the backing table decides
  anything", an explicit `typeName:` taking the `typeId` and key columns from the named type's own
  `@node` instead, which is what keeps several node types over one table from making a named leaf
  ambiguous.
* **Name-carried, with no directive at all.** Two documented cases, one per direction. A node type's
  own `id:` field is "a node ID by construction", and `typeName:` is *rejected* there because the
  containing type has already answered. And a slot *named* for the target's own `id` decodes without
  the directive: `id` on an input consumed against a node-backed table, or on an argument of a field
  returning a node type. "The name is what carries it, so a differently-named slot (`ids`,
  `customerId`) still needs the directive; graphitron does not guess at plurals or suffixes."

The third form has no row in either `@nodeId` relation, so the population is not simply those two
relations. It is a derivation over them plus the name-carried rule, which the store can state:
`graphql_field` and `graphql_argument` carry the slot's name, and `intent_node_type` with
`intent_resolved_type_binding` carry which types are node-backed and over which table. Where the
target table backs more than one node type the build already fails and asks for `typeName:`, and a
directive-less slot colliding with a real column of the same name is already an error at every
coordinate, so the two ambiguity cases the name-carried rule could raise are answered before this
item starts.

What the instruction *means* is then one further join: the node type plus the graph name reaches
`intent_resolved_node_key_column`, keyed `(graph_name, type_name, position, column_name, tier)`. That
relation is the opaque format's other end, T's key columns in order with the tier that answered.

None of this is a classifier reading, and that is the design. The classifier knows nothing here the
store cannot: it resolves these same facts in Java, separately per coordinate kind, which is exactly
why the coordinate kinds it never grew an arm for are silent instead of reported. Asking the
classifier which coordinates carry out the instruction would be asking the thing whose gaps are the
bug.

## Design

### The resolution is two relations, one per direction

`intent_node_id_decode` and `intent_node_id_encode` state how one instruction is carried out. An
instruction with no row in its own direction's relation was not carried out, and the defect view
below says which precondition stopped it. Absence here is therefore never the message: it is the
membership condition the defect view is keyed on, which is what keeps the two from restating one
fact. That split follows `intent_argmapping_key_column_candidate`'s own argument for being separated
from the projection beside it, that stating an unknown column as the absence of a candidate row and a
type disagreement as a candidate row with no projected row makes the two arms "disjoint by
construction" so neither has to re-test the other's predicate.

**Two relations rather than one with a direction column**, because the two directions genuinely
answer different questions and sharing a row shape would make half the columns nullable by kind.
Decode has a destination that receives a tuple, and where that destination is a table it also has a
column correspondence and possibly a join path. Encode has a source that produces one, and never has
either. `intent_input_occurrence_path_step` states the same discipline for its own shape, that it is
"homogeneous over input-field steps only ... so no column here is nullable by kind". Direction is
never a column: it follows from the coordinate kind, `graphql_type.kind` on the owning type plus
which relation answered.

**Grain: the instruction and its use site.** An argument and an output field are their own use site.
An input field's use sites come from `intent_input_occurrence_path`, whose
`(root_type_name, root_field_name, root_argument_name)` is exactly the consuming coordinate, whose
every prefix is a row, and whose steps are ordinal-decomposed in
`intent_input_occurrence_path_step` so no reader parses the serialized key.

The use-site grain is load-bearing rather than tidy. One input type may be consumed where the decode
resolves and where it cannot, so a verdict keyed on the instruction alone would have to pick one
answer for two consumers. `ArgmappingProjectionDefects` already argues this for its own messages,
that "one input type can be consumed where inference works and where it cannot, and an author told to
add `typeName:` needs to know which consumer is asking". The same reasoning fixes the grain here, and
the message names the use site for the same reason.

It also settles a question the previous draft answered with policy. An input field on an input type
nothing reaches produces no occurrence path, so no use site, so no row and no verdict. That is not a
reachability gate and it is not an exemption: a decode is "these values go here", and with no
consuming coordinate there is no here. The earlier draft argued instead for a detection deliberately
ungated by walk reach, reasoning over `walk_claim_domain_type` / `walk_claim_domain_field`. That
reasoning is retired twice over: this rule never needed those relations, and they no longer exist,
R743 having deleted both when `intent_authored_claim_conflict` stopped gating on the walk's reach.
The shape that replaced them is the one this item follows, and the detection section below states it
as such: the relation carries no accept line of its own, and each consumer applies the population its
own question needs.

**`intent_node_id_decode`** carries the coordinate, the use site, the resolved node type, and the
destination that receives the decoded tuple. Four destinations, a closed vocabulary:

* `OWN_TABLE_COLUMNS`. The decoded keys land on a column tuple of the row's own table: same-table
  identity, a foreign key's child columns, or the tuple an identity-carrying chain lifts back to. The
  predicate binds locally with no join.
* `TARGET_TABLE_COLUMNS`. No own-table tuple exists, so the predicate binds the node type's own key
  columns on its own table inside a correlated `EXISTS`.
* `JOOQ_RECORD`. A generated `*Record`: a `@service` method parameter, or a record-typed member of a
  consumer bean. A record holds a tuple, which is why these two work today.
* `SINGLE_KEY_COLUMN`. The destination holds one value, so one key column's value goes in it. Reached
  two ways that share a shape: the author names the column as a trailing `argMapping` segment
  (R668's capability), or the node type has exactly one key column and arity names it. Requires the
  column's Java type to match the slot's own; see "Sites 1 and 2" below.

**Two ordinal-keyed children on the decode relation**, following the parent-plus-steps shape
`intent_input_occurrence_path` / `_step` already uses:

* `intent_node_id_decode_column`, one row per key position, carrying the node type's key column at
  that position and the local column it lifts to on the row's own table, `NULL` where no lift exists.
* `intent_node_id_decode_hop`, one row per foreign-key hop from the coordinate's own table to the
  node type's table, in authored order.

Those two children are why the destination vocabulary can be flat. `OWN_TABLE_COLUMNS` is every
position having a local column; `TARGET_TABLE_COLUMNS` is none of them having one. Which is the whole
of site 4b, as the next section explains.

**`intent_node_id_encode`** carries the coordinate, the resolved node type, and the source that
produces the tuple. Two sources:

* `PROJECTED_COLUMNS`. The field's value is a column tuple in scope and the encode wraps it at the
  SELECT-side projection. Everything that encodes today.
* `READ_VALUE`. The field's value is *read* rather than projected, through an accessor, a by-name
  record read, a typed column off a record, or graphql-java's own property machinery, and the encode
  applies to what the read yields. New with this item; see "The dropped encode is the whole read
  family" below.

**`NodeIdLeafResolver` becomes a reader.** It resolves these facts in Java today. After this item the
relations resolve them and the classifier reads the rows, which is R682's direction
(`roadmap/planners-read-facts-emitters-read-commands.md`: planners read facts) arriving early for one
directive rather than being retrofitted onto a Java sealed hierarchy a release later.

*Corrected during stage 2.* That paragraph is wrong about which tier can read, and the section
"The resolver cannot be the reader" below carries the argument and what replaced it. In short: the
resolver runs inside the classification walk, the walk runs before capture, and a relation over
captured facts is therefore not readable from it at all. What this item delivers instead is the
resolver stating the relation's own reduction rather than a different one, so the two are one rule
spelled twice rather than two rules that agree until one changes; the reader is R682's planner, which
sits after capture.

**Where a view cannot express the walk, materialize it, through the registered mechanism rather than
by hand.** The lift is a positional-subset check between adjacent hops, and computing the lifted tuple
walks the chain back from the terminal hop. Where that has no safe recursive H2 view form it lands as
a materialized relation, which `intent_input_occurrence_path` and `intent_type_domain` both already do
and both state in their own comments. What has changed since those two is that R742, now In Review with the
registry landed, mints one for it: `meta_materialize`, a constrained table carrying one row per registration, the source
view under a `_live` suffix and the target table under the canonical name every existing reader already
uses, plus a required `reason` that is where the materialization doctrine lives. One materializer in
`graphitron-model` empties each target and refills it from its source on the capture cadence. Anything
this item materializes registers there rather than growing a second writer. Refresh *ordering* is
deliberately not in that registry, R742 having established that neither of its two registrations sits in
the other's closure and left the general case to R746; a relation this item registers whose source reads
another registered target is therefore the case that makes R746 real rather than additive, and stage 2
says so if it arrives.

### The derivation depth is a design constraint, not an afterthought

R742 measured the precedent this item follows and the number is the reason to read it first. One read
of `intent_argmapping_projection_defect` expanded to **2066 relation instantiations** and 24.5
seconds, because H2 inlines a view wherever it is named and does no common-subexpression elimination,
so multiplicities compound down a tree eight levels deep over seventeen distinct views. Those are
R742's figures from before its own reductions landed, and it has since taken that read to 0.72s by
materializing two of the relations underneath it, so the absolute numbers below are the diagnosis and
not the tree's current cost. What carries over is the multiplier, which is a property of the shape
and survives any reduction under it. Two of R742's findings are instructive here, and its own
decomposition says which of the two binds harder.

**A deep relation named more than once dominates, and this item names one twice by nature.** R742
prices each direct child of the defect view as `times named x (subtree + 1)`, and the largest single
contribution is **1038** from just two references to `intent_argmapping_key_column_candidate`, whose
subtree was 518 then and is 284 now that `intent_argmapping_pair` is materialized underneath it. Two
namings, half the read, and still the dominant term after the reduction: the second naming is what
costs, whatever the subtree shrinks to. This item joins `intent_resolved_node_key_column` twice by
nature, once for the key columns and once for the arity count, which is exactly that shape. It is also
windowed, carrying a `DENSE_RANK() OVER` for its tier precedence, so a window sees its whole partition
whatever predicate a reader applies outside and no rewrite restores pushdown. The remedy the fact
model already prescribes is to take the relation once and pair it on its key; the arity is then a
count over the rows already taken, not a second read.

**Per-verdict `UNION ALL` arms are the smaller cost, and the rule here is not to mint any.** The
defect view's six arms each re-join the same driving relations with a different `WHERE` and a
different verdict literal, contributing 750 through five references to
`intent_argmapping_binding_leaf`. R742 prices collapsing them at about 600 instantiations, some 29% of
the read, and files it as a follow-on worth doing on its own merits rather than as the remedy for the
24.5 seconds. So the framing is "do not create the cost" rather than "this is where the time goes".
The defect view below has two verdicts and is written in one pass over the population with the verdict
picked by a `CASE`, so the driving relations are named once. Same for the two resolution relations,
whose destination and source vocabularies are tempting to write as one arm per value.

Both points are exit conditions rather than advice, because the metric is checkable without a
database: inline multiplicity is computable statically from `graphitron-model.sql` by parsing the
`CREATE VIEW` bodies and multiplying textual reference counts down the tree, and R742 proposes exactly
that as a `roadmap-tool` build gate. Whether that gate has landed or not, the number for each relation
this item adds is computable the same way, and stage 2 states it rather than discovering it in a
profile later.

**Measured, and the number is worse than the precedent.** That gate has landed:
`roadmap-tool report-inline-multiplicity` computes the metric with no database and no profiler, and
it reports rather than fails, on the stated ground that an honest ceiling has no basis yet. Its
figures for the relations stage 2 has added so far:

* `intent_argument_scope_table` 83, `intent_foreign_key_column_pair` 4. Both shallow, both read
  from several places, which is what they were lifted out of inline spellings to be.
* `intent_node_id_instruction` 590. Every relation below is a multiple of this one, so it is the
  term that matters and not the ones above it.
* `intent_node_id_decode_endpoint` 715, `intent_node_id_decode_hop` 823,
  `intent_node_id_decode_hop_column` 828, `intent_node_id_encode` 808. Each is its child plus a
  little, which is what a chain of single namings looks like and is the shape the CASE-in-one-pass
  rule was there to keep.
* `intent_node_id_decode_column` 2528, now the heaviest read in the schema. The previous heaviest
  was `intent_argmapping_projection_defect` at 765, which is the same view R742 measured at 24.5
  seconds before its own reductions, so this is 3.3 times the tree's worst case rather than a
  comparable one.

Where it comes from is not the rule the section above warns about. No relation here mints a
per-value `UNION ALL` arm, and the one place a relation is named twice is the lift's recursion,
which references its input in both the seed and the step because that is what a recursive walk is:
1656 of the 2528 is two namings of `intent_node_id_decode_hop_column`. The rest is the instruction
population multiplying up the chain three times over.

Neither is reducible by rewriting. A recursive walk cannot name its input once, and every
alternative spelling of the lift names more rather than fewer relations. The reduction the fact
model prescribes is to materialize, and the relation to materialize is the instruction population,
which every number here is a multiple of.

**That registration was made, measured, and reverted, and what it found is the more important
number.** R746's ordering had landed, derived from the catalog rather than declared, so registering
the first dependent derivation in the registry was ordinary rather than blocked. The static metric
improved exactly as arithmetic said: `intent_node_id_decode_column` 2528 to 761,
`intent_node_id_decode_hop_column` 828 to 239, `intent_node_id_decode_hop` 823 to 234,
`intent_node_id_encode` 808 to 219, the whole family under the tree's previous heaviest read.

Then the reactor went from eight minutes to still-running at thirty-one, and a thread dump of the
stuck fork said why: one H2 query, ten minutes of CPU, nested `EXISTS` predicates driving a
recursive CTE through `RecursiveIndex.find`. The refresh of the instruction population against the
sakila schema is that query. So the registration did not create a cost, it *revealed* one, and the
revealed cost is the finding: **the instruction population as authored cannot be evaluated against a
real schema in acceptable time, and no amount of materialization fixes that, because materializing
is what makes it be evaluated once rather than never.**

Which reframes the whole depth question for this item. Every number in the ranking above is a cost
per read, and nothing reads any of these relations during a build yet, so the eight-minute reactor
was measuring zero evaluations of a family whose single evaluation costs ten minutes. Stage 2c is
where the first build-time reader lands, so that is when the cost arrives whether or not anything is
registered, and the registration is reverted only so trunk stays fast in the meantime.

**Making the instruction population cheap is therefore a prerequisite of stage 2c rather than a
follow-on.** The static metric is not what to optimise against: it counts namings, and a naming's
cost is not a property of how many there are.

**Measured against the sakila schema, and the first diagnosis was wrong.** Reading the thread dump of
a killed build was guesswork, and what it guessed was that the recursive reference-target views were
the term, unprunable because H2 evaluates a recursive CTE whole. A probe that captures the sakila
example's own 4082-line schema against the sakila catalog and times each relation in isolation says
otherwise:

[cols="1,1,1,1",options="header"]
|===
| relation | rows | before | after

| `intent_field_reference_step_target` | 62 | 0.08 s | 0.07 s
| `intent_argument_scope_table` | 157 | 19.9 s | 0.13 s
| `intent_argument_reference_step_target` | 3 | 19.2 s | 0.09 s
| `intent_node_id_instruction` | 69 | 79.1 s | 0.40 s
|===

The field-site recursive walk is free. The argument-site one costs what its seed costs, and its seed
is `intent_argument_scope_table`, which this stage added. The instruction population costs four
times that, because four of its arms name the scope relation. Nothing in the measurement is about
recursion, and no correlated `MAX(position)` subquery appears in it.

What the 19.9 seconds is: the scope relation's upper rung resolves `intent_resolved_type_binding`
against the field's authored named type, which is a written expression rather than a column, being
the author's type spelling with its wrappers stripped where a macro rewrote it. **Joining a derived
relation on an expression rather than on a column makes H2 evaluate that relation once per driving
row instead of once.** The binding is a union under a window function, so once per row costs about
20 ms, and this rung drives from every argument in the graph. Projecting the expression as a column
in an inner derived table and joining the binding on that column is the same 147 rows for 0.10 s, a
factor of about 200. That is the fix, and it is four lines.

Three controls rule the alternatives out, each measured on the same fixture. Joining the binding on
`f.named_type` directly, wrappers and macro ignored, is 0.08 s, which locates the cost in the
expression and not in the population size. Materializing the binding in a `WITH` clause first is
still 19.6 s, H2 inlining a non-recursive `WITH`, so this is not view inlining and no relation
extracted for tidiness would have helped. And routing the expression through `graphql_type` before
joining the binding on its column, which is how the two other places in this schema that strip the
same wrappers happen to spell it, is 19.6 s and no fix at all.

That last control is the finding worth keeping past this item. Those two sites,
`intent_routine_return_binding` and `intent_field_column_scope`'s named-type rule, carry the same
hazard and are cheap only because a chain terminus and a field's own scope drive orders of magnitude
fewer rows than every argument does. Their spelling is safe by accident of population size, not by
construction, and it is the third copy of one wrapper-stripping rule that a relation should state
once. Filed as its own Backlog item rather than widened into this one.

One consequence for the static metric, which keeps its stated standing but a lower one than this item
gave it a paragraph ago: 2528 namings of a relation that answers in 0.4 s is not a problem, and 83
namings of one that answered in 20 s was. The metric ranks breadth. Cost is measured.

**The same probe run over the rest of the family, and the instruction population being cheap does not
make the family cheap.** Measured after the fix above:

[cols="2,1,1",options="header"]
|===
| relation | rows | time

| `intent_node_id_instruction` | 69 | 0.38 s
| `intent_node_id_encode` | 15 | 1.46 s
| `intent_node_id_decode_endpoint` | 40 | 4.93 s
| `intent_node_id_decode_hop` | 16 | 7.15 s
| `intent_node_id_decode_hop_column` | 20 | 6.79 s
| `intent_node_id_decode_column` | | no answer in 300 s
|===

So the destination relation stage 2c reduces over cannot be evaluated at all yet, and that is the
gate on stage 2c rather than anything in stage 2b's own population.

Three isolations locate it, and two of them refute readings this item reached for first. Snapshotting
the lift's recursive CTE into a table and running the final `SELECT` against the snapshot is 6.69 s
for its 54 rows, against 6.68 s for the same query with the lift left out entirely, so the final
join's null-safe coordinate match and its folded column-name comparison cost about 0.01 s and are not
the term. The live view exceeding 300 s where the CTE alone is 146 s and the final select is 7 s puts
the CTE at roughly two evaluations rather than one per outer row, so the recursion is not being
re-entered per row either, which is what the killed build's thread dump was read as saying.

What is left is the recursion itself: **146 seconds to produce 20 rows.** Each evaluation of
`intent_node_id_decode_hop_column` costs 6.8 s and 146 divided by 6.8 is about 21, which is the
number of rows the walk accumulates. That is a nested loop inside the recursive step, evaluating the
step's whole input once per accumulated row, and the reason there is nothing to plan instead is the
step's join predicate: a null-safe disjunction on two coordinate columns and an `UPPER()` on both
sides of the column match, neither of which is an equality H2 can drive an index from. The same
predicate in the final join costs nothing, because there it is evaluated once over 54 rows rather
than once per row of a growing relation.

Two levers suggested themselves, and both were built and measured rather than argued. **Only one of
them does anything, and it is not the one this item predicted.**

The lever that was predicted to help and does not: give the recursive step a predicate a planner can
drive an index from, by carrying the `use_site` column the endpoint relation already has down through
the two hop relations and joining on it instead of on the five-column null-safe match, and folding
column-name case once where the walk records the arriving name rather than on both sides of the
comparison. Built, all 60 model-tier cases green on it, and the destination relation still gives no
answer in 300 s. Reverted. A control then measured the pre-change predicate against a *table* holding
the same 20 rows: also instant. So the predicate shape is not a factor at either end, and the
prediction of "21 evaluations down to about 2" was arithmetic over a mechanism that was not there.

The lever that fixes it outright: **make the recursion's input a table.** Snapshotting
`intent_node_id_decode_hop_column` into an indexed table takes the lift from 146 s to **0.00 s** for
the same 20 rows, and the whole destination relation to 6.5 s, of which the lift contributes 0.2 s
and the endpoint subtree the rest. Registering that relation as a materialization is therefore the
increment, and it is the lever stage 2b deferred for want of a reader: the recursion naming its input
once per accumulated row is that reader, and no rewriting of how it names it helps.

A second registration is worth making at the same time, and its case is separate rather than
additional. Snapshotting `intent_node_id_decode_endpoint` costs 5.4 s and takes the hop relation from
7.5 s to 2.4 s and its column child from 7.5 s to 2.4 s, because three relations read the endpoint
and each was paying for its whole subtree. That is the shared-departure argument the relation was
factored out on, arriving as a cost. It does not substitute for the first: with the endpoint
materialized and the hop column relation still a view, the recursion reads a 2.4 s view once per
accumulated row and is no better off. With both, the refresh is about 8 s and every read below is
close to free; with only the hop column relation, the refresh is 7.5 s and the destination still pays
the live endpoint's 5 s on every read.

The general reading, which is the third correction this measurement forced: what makes a relation
expensive here is being a view that something reads many times, and how the reader spells the read
does not enter into it. Two of the three transforms tried in this item were rewrites of a predicate
and both bought exactly nothing; the one that worked in the scope table's case worked because it
changed which relation was re-evaluated, not because it changed a join key's shape.

**Which registration to make was then decided by three reactor builds on one base, and that base
mattered more than anything the registrations did.** Every reactor figure quoted above this line was
taken while trunk changed underneath: store boot stopped compiling Java partway through this work,
which took `graphitron-model` from about two and three quarter minutes to about one, so the
minute-scale differences this item was reasoning about sat inside a two-minute shift in the ground.
The three builds that decide it were taken after that landed, on one base, one variable apart:

[cols="2,1,1",options="header"]
|===
| configuration | reactor | graphitron-model

| trunk, neither registration | 9:00 | 2:43
| `intent_argument_scope_table` registered | 8:56 | 2:39
| that plus `intent_node_id_decode_hop_column` | 10:10 | 2:48
|===

One earlier run of the middle row came in at 7:37 with the model module at 1:10, and it is discarded
rather than reported: three runs put that module at 2:39, 2:43 and 2:48, so a single 1:10 is an
artefact of the machine and not of the registration. It is recorded here because a first draft of
this section believed it and claimed the registration was worth 1:23, which is the fourth thing this
item has had to take back for reading a difference off runs that were not comparable.

**Those totals do not support a conclusion either, and this is the last time this item quotes one.**
Five runs of `graphitron-model` on this machine, across code that differs by at most one registration:
1:10, 1:29, 2:39, 2:43, 2:48. A 2.4-times spread on the same module means the minute-scale differences
in the table above are the machine, and the 7:37 discarded two paragraphs up was discarded for being
one run of exactly this spread rather than for being uniquely bad. Reading a registration's cost off a
reactor pair has now been wrong four times in this item for one reason each time, which is one reason:
a single run of a noisy total. What replaces it is the measurement that is same-process and repeats,
`CapturedStore` open time. The hop column registration takes that from about 5.0 s to between 8.9 and
10.2 s across several runs, so its refresh is **about 4 s per store open**, and how many opens a
reactor build makes is a separate question this item does not need to answer to decide a registration.

The scope table's registration is therefore neither shown to help nor shown to hurt on the reactor. What justifies it is the per-relation cost, which is same-process and reproducible where these totals are not. The two still have opposite
signs on the build, which rules out both of the single-factor explanations this item reached for. It is not that a registration's refresh is free, and it is not that a refresh costs a
view evaluation per store open and therefore never pays: it is refresh cost against reads avoided.
The scope table refreshes in seventy milliseconds and four view bodies name it, so materialising it
removes far more re-evaluation than the refresh adds. The hop column relation refreshes through the
whole hop chain and has exactly one reader, the lift, which no build-time consumer exercises yet, so
every refresh today buys nothing. That is why its registration moves to the stage that adds the
consumer rather than shipping dormant.

**What the stage that adds the consumer then found, which is a fifth thing this item has had to take
back.** The hop column registration landed as planned and the destination relation is its reader. Two
other things were tried on the way and only one of them survived measurement.

The first was a simplification of the lift's key. The hop relations keyed on five coordinate columns,
two of them nullable by site, so the recursive step joined on four null-safe disjunctions and a fold
applied to both sides of a column comparison. All of that was replaced by the use site, which is one
non-null column the endpoint relation already carries and which the two sites cannot collide in. The
theory was that an unplannable predicate was what made the step re-read its input. It is a real
simplification and it is kept, and it moved the timeout **not at all**: the lift still did not finish
in two minutes. What that rules out is the predicate as the cause, leaving the re-evaluation, which is
what the registration addresses and what took the relation from no answer to 3.0 s. What the collapse
may be worth is on the refresh rather than the read, the source view's window now partitioning by two
columns instead of five, and that is not separately measured.

The second was a reading of how H2 behaves in a join, and the first version of it was wrong. The
destination is a reduction over the key-column relation, so the relation naming it joins that
reduction, the instruction population, and the node key's arity. Spelled that way it does not finish
in two minutes. The first diagnosis blamed the exclusion of Java-slot coordinates, because
`NOT EXISTS`, `NOT IN` and an outer join tested for null all timed out while `EXCEPT` returned the same
rows in 2.65 s, and that difference was written into a comment as a rule about anti-joins. Then the
control was run: the same query with **no exclusion at all** also times out. So the anti-join was
never the cause. What decides it is the operand count. Two relations and the expensive one is
evaluated once, at 5.91 s; add a third, even one as cheap as a `COUNT(*)` group-by over sixteen rows,
and H2 reorders into probing the expensive one per driving row.

[cols="3,1",options="header"]
|===
| shape | cost

| the reduction alone | 3.12 s
| instruction joined to the reduction | 5.91 s
| that plus the arity group-by (no exclusion at all) | no answer in 120 s
| four spellings of the exclusion, on top of that | no answer in 120 s
|===

So the fix is neither a spelling nor a registration: it is to stop having three operands. The
reduction is expressible as two window functions over the key-column relation's own rows, which makes
the destination relation one pass over one relation plus a correlated probe of the slot relation. That
terminates at 10.7 s and needs no further registration. It is left at 10.7 s deliberately: nothing
generates from it yet, and an expensive unread relation costs nothing, which is the same rule that
kept the hop column relation unregistered until this stage.

**The Java-slot fork was also being asked one step too late, and that was worth an order of
magnitude.** The first version of `intent_node_id_decode_slot` asked which class member receives the
value: the type backing of the instruction's owning type, then that class's member of the field's
name, then the member's declared type. It cost 3.0 s, of which the member lookup was 3.3 s measured on
its own, and registering it was going to add that to every store open. It was also answering a
question one step past the fork. What decides whether a decoded value reaches consumer code is not
which member receives it but whether the argument the value descends from is fed to a parameter at
all: an input field's value reaches Java exactly when its occurrence path's root argument does. Keyed
at that root, the relation reads the occurrence path (a table) and two cheap census joins, needs no
type backing and no member relation, and costs **0.28 s**. It is also the more correct question, since
a bean-backed input type that carries no `@table` has no backing this schema resolves while its root
argument is plainly a parameter.

**The two table destinations shipped first and the two slot destinations followed, per precondition
rather than per convenience.** `OWN_TABLE_COLUMNS` and `TARGET_TABLE_COLUMNS` are the reduction,
total over the endpoint population once Java-slot coordinates are excluded from it. The exclusion is
what made that honest rather than a gap: a coordinate whose value reaches Java drew no destination row
at all, so nothing was defaulted into a table predicate that has nothing to bind.

The slot pair then landed as a second arm over the slot relation, and the fact each needed turned out
to be one relation rather than two. `intent_resolved_node_key_shape` reduces a node type's key to what
a slot has to agree with: the arity, the generated record of the type's own table, and at arity one
that column's name and its jOOQ binding type. `JOOQ_RECORD` is the slot's declared type equalling that
record class, tested first and winning, because a record holds a tuple whatever the arity and reading
a single-column key into its own table's record as a bare column value is the mistake the precedence
exists to prevent. `SINGLE_KEY_COLUMN` is arity one with the type gate standing aside on either
operand being unknown, which is `intent_resolved_node_key_projection`'s discipline for the same two
operands and is what lets an untypeable parameter be carried out on arity alone with javac as the
backstop.

Two arms rather than one query, and that is not the per-destination decomposition the derivation-depth
rule forbids. They drive off different relations for different facts: the table arm reduces the
key-column child's lift, the slot arm reads a parameter and a key's shape, and neither re-joins the
other's operands. H2 evaluates the arms independently, so the cost is additive rather than
multiplicative, which the measurement below confirms.

**What is still owed is a slot the value lands *inside*, and it is one walk rather than a
destination.** A parameter typed as a consumer bean, or as some other table's record standing for the
enclosing input type, receives the whole argument and the decoded value goes to a member of it. Which
member that is needs a walk into the class, which is exactly the walk the slot relation was rewritten
to avoid, so such a slot draws no destination row today. That is deliberately not a fall-through to
`SINGLE_KEY_COLUMN`: binding one decoded value into a row type is worse than resolving nothing. The
other stated hole is unchanged, an `argMapping` path that descends into an argument and binds a single
input field below it while its siblings still bind predicates.

**One gap in the partition claim, found by writing the arm and named rather than absorbed.** An
overloaded `@service` resolves several candidate slots, and the slot arm requires one candidate rather
than picking a type, so such an instruction has no row in either population. The invariant stage 5
states is that every instruction either resolves or is refused, and this one does neither. It is a gap
in the census rather than an author error the two verdicts cover, which is precisely what the
invariant was stated to surface. Stage 5 owes it either a third verdict naming the ambiguous
reference or a reading that the ambiguity is already refused upstream; what it must not do is let the
arm pick. **Discharged in stage 5, and it was the second reading.** `ServiceCatalog.pickMethod`
rejects with `ReflectionError.AmbiguousMethod` as soon as more than one declaration shares the name,
so such a coordinate never reaches emission and the candidate rows are a shape no consumer is asked
about. A verdict here would have been a second answer to a question the reference resolution settled
two phases earlier.

**A second gap in the partition, found by stage 4 and named on the same terms.** The encode relation
routes a field whose value a named producer returns to `READ_VALUE`, deliberately and for a stated
reason. The Java classifier drops the directive at exactly those coordinates: the `@externalField` and
`@service` arms of the table-parent classifier run ahead of its `@nodeId` arm, so `@nodeId` on such a
field is inert the way it was at the four read arms. The relation says the instruction resolves and the
generator does not carry it out, which is the one direction the accept-set guard cannot catch, since
nothing was generating there to go red. Stage 4 did not widen to it, its exit naming the read arms, and
stage 5 owes it either an emitter at those two coordinates or a refusal; what it must not do is let the
relation keep claiming a resolution nobody performs.

**Discharged in stage 5 as a refusal, and writing it found a third arm and corrected the invariant's
own wording.** The gap was stated as two coordinates on a table parent, and it is three: the root
`@service` arm drops the directive too, for the same reason and with no `@nodeId` arm of its own to
reach. So the refusal is not a patch on the table-parent classifier but a placement gate beside the
one `@referenceFor` already has, on the field's directives rather than on its parent, which is why one
gate covers arms that share nothing else. It is a deferral rather than a structural refusal: the
producer hands back one value and encoding it is the same helper call every other carrier makes, so
what is missing is an emitter and not the author's understanding. The two messages share their lead
and differ in the remedy, because the remedy differs: an `@service` producer returns a Java value and
can call the generated encoder itself, while an `@externalField` producer returns a jOOQ expression
and cannot.

**The invariant clause was the thing that needed correcting, not the relation.** "Let the relation keep
claiming a resolution nobody performs" reads as though the row were the fault, and following it would
have narrowed the encode population to what emits today. The tree already had a shape that refutes
that reading: a `@nodeId(typeName:)` field over an authored path that does not collapse to the parent's
own columns has a `PROJECTED_COLUMNS` row and is *deferred* at the reference carrier, and has been
since before this item. So presence in a resolution relation was never an emitter's existence, and
saying it was would make the relation track emitter maturity, changing rows in a graph whose schema
nobody touched. The relation keeps both shapes and now says so, naming the two deferrals; what the
build owed was a word at the coordinate, which is what the gate emits.

That gap also corrected the slot relation itself. Its named-parameter arm shipped demanding one
producer candidate, which every other reader of `intent_field_producer_method` does, and here that was
wrong in a way its own comment already contradicted: dropping the rows makes the use site look like
one binding a table predicate, so an author whose `@service` names two overloads would have got the
wire format bound into SQL on the strength of an ambiguity nobody resolved. The demand moved to the
destination arm, where declining to pick means resolving nothing rather than resolving a predicate.

**The slot arm's cost, and the one figure this section will not compute.** Two runs of the same probe
against one sakila capture, milliseconds and rows:

[cols="3,1,1,1",options="header"]
|===
| relation | rows | run 1 | run 2

| `intent_node_id_instruction` | 69 | 79 | 100
| `intent_node_id_decode_endpoint` | 40 | 656 | 677
| `intent_node_id_decode_hop` | 16 | 1251 | 824
| `intent_node_id_decode_hop_column` (table) | 20 | 1 | 1
| `intent_node_id_decode_column` | 54 | 2406 | 2038
| `intent_resolved_node_key_shape` | 12 | 237 | 241
| `intent_node_id_decode_slot` | 0 | 127 | 138
| `intent_node_id_decode` | 40 | 6630 | 6370
| `intent_node_id_encode` | 15 | 760 | 719
|===

Two samples that agree to within a few per cent, which is the skill's claim about per-relation
timings holding. What this table deliberately does not do is subtract the 10.7 s recorded for the
destination relation a few paragraphs up. That figure was taken on an older base, trunk has moved a
great deal since, and this item's whole measurement history is the record of what happens when a
difference is read off two numbers taken under different conditions. The slot arm's own cost is
readable without any subtraction: the relation it drives off is 0.13 s, the shape relation is 0.24 s,
and the two arms are independent, so the arm adds a fraction of a second to a relation costing six.

**Inline multiplicity ranked this relation first and the cost did not follow, which is the report
telling the truth about what it measures.** With both arms in place `intent_node_id_decode` expands to
1597 relation instantiations per read, the highest in the schema, its slot relation named twice
(once by the arm, once by the table arm's exclusion). Breadth is not cost, the report says so, and
here it is not: the relation costs what its expensive operand costs. Deduplicating the slot naming, by
registering that relation or by restructuring the exclusion, would move the 1597 and would be a
registration with no reader to justify it, so it waits for the stage that adds one.

**The defect view's own figure, and the relation it is modelled on is now the schema's heaviest read
for exactly the reason this section warned about.** `intent_node_id_decode_defect` expands to 630
instantiations, its slot child at 412 named once plus the key shape beside it, which is what a
single-pass `CASE` over two driving relations looks like. In the same report
`intent_argmapping_projection_defect` is now the heaviest relation in the schema at 2267, and 2022 of
that is three namings of `intent_argmapping_key_column_candidate`. That is R742's per-verdict-arm
prediction landing on the very relation this view was told to follow the shape of, so what this view
took from it was the fact base and not the six arms.

**The zero in that table is a fixture artefact, and finding out why is what verified the fork.** The
probe captures the sakila schema against its jOOQ catalog and passes no classpath census, so the
`jvm_` families are empty and no parameter resolves. Read carelessly, no slot rows on the real schema
would have said the fork was inert, and the sakila schema does carry the shapes: an input type bound
directly as a `FilmRecord` `@service` parameter with a `@nodeId` field on it is a fixture already in
the tree. What settles it is a capture with a hand-built census, which is the test named in the Tests
section below: three coordinates naming one node type and one key resolve a table predicate, the
key's single column, and the film record respectively, off a real capture rather than seeded rows.
That test is the one that would have caught an inert fork, and no store-tier case could have.

**A captured fact is the lever neither of those is, and it is the one this family should have reached
for first.** A registration trades a refresh for avoided re-evaluations and has to win that trade; a
fact written at capture time has no refresh to pay for and leaves every reader joining a column.
Capture is already most of the way to the one this family wants:
`graphitron_field_synthesis.authored_type_sdl` holds the type expression as the author wrote it, and
three view bodies then strip its wrappers per row with a `COALESCE` over three nested `REPLACE`
calls. That expression is what made the scope table 19.9 s, and through the endpoint it is what makes
the hop relations cost what they cost. graphql-java hands capture the bottomed-out name directly, and
`graphql_field.named_type` is already exactly that fact for the expanded expression, documented as
author-spelled with integrity left to a detection. Its authored sibling has the same standing.

Where that fact lands is a real fork. On `graphitron_field_synthesis` it sits beside the expression
it comes from, but that relation only has rows for macro-rewritten fields, so readers still need
`COALESCE(fs.authored_named_type, f.named_type)`, which is an expression again and buys none of the
plannability. On `graphql_field`, non-null and equal to `named_type` wherever no macro rewrote it,
readers join a bare column; the cost is a column on a core captured relation that duplicates its
neighbour on almost every row. The second is the recommendation: a field genuinely has two named
types, which is why the synthesis relation exists at all, and only a total fact removes the
expression from a join key instead of relocating it. Filed on the Backlog item rather than taken
here, and it may make the scope table's registration unnecessary, which would be the better outcome
than a registration that currently earns its place.

**One piece of navigation has to be authored first.** `intent_field_reference_step_hop` and
`intent_field_reference_step_target` resolve reference-path hops, and they are field-site only. An
argument-site `@reference` path has no equivalent, over
`graphitron_argument_reference_step`. R723 named authoring those sibling views as the prerequisite it
was declining to take on. This item takes it on, because a `@nodeId` filter path is an argument-site
path and the decode hop child above cannot be populated without it.

### Site 4b dissolves into the relation rather than being refactored

The junction-table case (`Sak` filtered by `Tagg` ids through `soknad_tagg`, or `film` through
`film_category` to `category` in sakila) is reported as unsupported and the emitter it needs is
already built. `BodyParam.RemoteColumnPredicate` carries a whole `joinPath`;
`ConditionCommands.narrowPath` narrows every step; and `ConditionGlueRenderer.reachExists` walks the
whole reach, selecting from the terminal alias, bridging back through hops `n-1 .. 1`, and
correlating hop 0 against the row's own table. `FkTargetConditionFilter` says so in its own javadoc
("Single-hop for the common case; multi-hop walked inside the `EXISTS`").

What blocks it is upstream, and it was one conjunct doing the work of two. `NodeIdLeafResolver` picked
`DirectFk` when `permutationToKeyColumns` succeeded on the terminal hop, while `DirectFk`'s *meaning*
is "the decoded keys lift to a tuple on the field's own table". Those are two facts and the resolver
checked one, because `validateLift` runs earlier and rejects rather than recording that no lift
exists. `TranslatedFk`, whose whole premise is "no own-table tuple, bind on the target inside an
`EXISTS`", was therefore unreachable for a multi-hop path.

The conjunct is split as of stage 2, and only the gate is left. The resolver now lands each key
position on a column of the row's own table or on nothing, and picks the arm on whether every position
landed, which is the reduction the relation performs; `permutationToKeyColumns` went with it, an
arrival matched against a key column needing no realignment. So what stage 3 removed is `validateLift`
and nothing else: the landing it would have consulted was already computed beside it, and an unlanded
position already routed to the remote arm wherever the gate was not standing in front of it. The
deletion is nine lines of call site and message, and what it buys is the whole junction case.

In the relation those are two columns. A decode whose `intent_node_id_decode_column` rows all
carry a local column is `OWN_TABLE_COLUMNS`; one whose rows carry none is `TARGET_TABLE_COLUMNS`; and
the junction chain is the second because the lift walk contributes no local column, not because
anything rejects it. There is no sealed result to invent, no discriminator to state twice, and
nothing to retire from Java beyond the gate itself.

This was spiked before the relation was designed, with the discriminator expressed as a nullable slot
in Java, and the spike is still the evidence that the emitter side is ready.
`film -> film_category -> category` lowered to a two-hop `RemoteColumnPredicate` and rendered:

```java
DSL.exists(DSL.selectOne()
    .from(table_fkt0_1)
    .join(table_fkt0_0).onKey(Keys.FILM_CATEGORY__FILM_CATEGORY_CATEGORY_ID_FKEY)
    .where(table_fkt0_0.FILM_ID.eq(table.FILM_ID).and(table_fkt0_1.CATEGORY_ID.in(categoryIds))))
```

which is the predicate the report asks for. The identity-carrying chain still bound locally and still
emitted the local `IN`. The full non-execution suite ran 3626 of 3627 green, and the single failure
was `NodeIdLeafResolverTest.multiHopLiftTranslationRejected`, the test pinning the rejection the
change deliberately removes. That fixture is worth keeping and rewriting rather than deleting,
because it is the case that proves the two conjuncts are two: its terminal hop's target-side columns
*are* the node type's key columns, so a change that dropped `validateLift` without recording the
absent lift would route it to a local predicate over a tuple that does not exist.

Two properties make the relaxation safe rather than merely cheap.

* **The write rails already refuse a remote binding.** INSERT (`MutationInputResolver`),
  `UpdateRowsWalker`, `DeleteRowsWalker` and `FieldBuilder.classifyPlainLookupKeyArg` each gate on the
  `FilterBinding` arm with the shared `FilterBinding.remoteBindingUnsupported` text, and
  `TranslatedFkTargetRailGatesPipelineTest` already pins all four against a single-hop translated FK.
  A junction chain reaching a write or `@lookupKey` coordinate meets a stated message rather than
  emitting a wrong statement.
* **`EXISTS` is already the argued-for shape at non-unique cardinality.** R57's changelog entry
  settles it: no row multiplication when the path is non-unique, and a NULL foreign-key column fails
  the correlation instead of duplicating or dropping rows. A junction chain is the non-unique case
  that argument was written for.

Relaxing a producer obliges a consumer audit in the same commit, which is a stated rule here
(`development-principles.adoc`, "Acceptances: classifier guarantees shape emitter assumptions"). The
spike's 3626 is evidence about the *read* path only: nothing pins a write-side multi-hop non-lifting
`@nodeId` today, because the lift rejection fires before those coordinates are reached. Afterwards
such a path binds remotely and meets each rail's own refusal, whose text is about a remote binding
rather than about the author's chain. So the audit is per rail and it is an exit condition, not a
follow-up: each of the four states which message the author now sees, and the junction fixture joins
`TranslatedFkTargetRailGatesPipelineTest` rather than starting a second class beside it.

**The condition-join hop is not relaxed here, and the reason is the emitter.** `CONDITION_STEP_MARKER`
and `validateLift` state one predicate twice, so both express themselves through the relation, and
that much is this item's. But the `EXISTS` emitter is hop-general over foreign-key hops and over
nothing else: `ConditionCommands.narrowPath` narrows every step through `FkHop.narrow`, which throws
`IllegalStateException` on any hop whose `on()` is not `On.ColumnPairs`. Routing a condition hop to a
remote binding today would replace a stated author-facing rejection with an untyped generation-time
throw. Widening it is R705's work, which retires `FkHop`, `FkHop.narrow` and `narrowPath` outright.
So a condition hop keeps its own rejection through this item, and R705 inherits the relation rather
than co-authoring it.

### The resolver cannot be the reader, and the tree had already said so

The plan above says `NodeIdLeafResolver` becomes a reader of `intent_node_id_decode`. It cannot, and
the obstacle is not effort: `NodeIdLeafResolver` runs inside the classification walk, the walk runs
before capture, and a relation over captured facts has no rows yet when the resolver is asked. The
generator's order is walk, then capture, then detections, then plan, and capture is placed after the
walk on purpose, taking the classified model as an input.

The tree states this rule already, in the class this item's own earlier stage extended.
`ArgmappingProjectionDefects` explains why two of its arms are not the walk's: both "are questions
about captured directive facts and the walk runs before capture", so "the walk carries every segment
it cannot resolve against SDL and judges none of them", because "a rule spelled in the walk instead
would be an earlier second copy that wins by rejecting first, which is how one family ends up with two
answers that agree until one changes". Read against the plan's sentence, that argument says the
opposite of "the resolver becomes a reader": what belongs in the store is the judgment, and what
belongs after capture is the reader.

R682 settles where the reader goes, and settles it by having already faced this. Its planner half
starts at the plan rather than at the walk, for a reason it states outright: the plan "sits after
capture, so nothing about the pipeline's stage order has to change". Moving capture ahead of the walk
is a change to that stage order, it is R682's to make or decline, and this item is not the place to
make it unilaterally on behalf of one directive.

So the deliverable changes shape and not direction. The resolver stops stating a *different* rule from
the relation's:

* The arm choice becomes the relation's own reduction, "did every position of the node type's key land
  on a column of the row's own table". It was two facts checked as one conjunct, the terminal hop's
  referenced columns compared against the key as a multiset, with a chain that translated a column
  refused before the question was reached.
* The lift becomes per-position landings, computed by the forward walk the relation's own comment
  argues for, each pairing carrying "this column of the departing table is, after this many hops, this
  column of the table reached". The backward walk it replaces could not express a position that failed
  to land; it indexed into the previous hop unconditionally, which is why a gate had to run first.
* Matching an arrival against the key column rather than against a position retires the separate
  permutation step, exactly as the relation's comment says it does. Three helpers become one.
* `JoinPathResult` retires for a two-armed result carrying a `Rejection` rather than its prose, so the
  auto-discovery arm's typed rejections stop being flattened and re-wrapped as structural. Those two
  arms are defensive-only today, so nothing in the corpus reaches them and there is no behaviour to
  pin; the value is that the slot no longer downgrades what a reachable arm would carry.

Behaviour is preserved on every shape the corpus can express, and that is a claim about the catalog
rather than a hope. `@node(keyColumns:)` must name a real unique key and a foreign key references one,
so the reachable relations between the two column sets are equal-as-a-set, which both spellings call
local, and anything else, which both call remote. The one shape where the two answers differ is a key
strictly inside a foreign key's referenced columns, which needs two nested unique constraints on one
table; the new answer there is the relation's and is the better predicate, and no fixture was added to
reach it.

What this left for stage 3 was the deletion the plan promised, and stage 3 took it: `validateLift` was
the only thing left turning an unlanded position into a rejection, and the landing it would have had
to consult was already computed beside it.

### The dropped encode is the whole read family, not the `@error` type

The report named an `@error` type's extra field: an `ID` carrying `@nodeId(typeName:)` whose value
reaches the consumer already encoded by hand, because `FieldBuilder.classifyChildFieldOnErrorType`
classifies it as a plain `ChildField.RecordReadField` with a `ValueLocator.DefaultRead` and the
directive contributes nothing.

The cause is wider than the `@error` type, and the store framing is what makes that visible. A
`RecordReadField` is every output field whose value is *read* rather than projected, and
`ValueLocator` has four arms: `TypedColumn` off a jOOQ table record, `JavaAccessor` off a class-backed
parent, `ByName` off a record carrier, `DefaultRead` where graphitron locates nothing. Not one of them
carries a `CallSiteCompaction`; only `ChildField.ColumnBackedField` and `ColumnBackedReferenceField`
do. So `@nodeId` is equally inert at all four, and the reporter hit one of them. Same silence, same
cause: a read has no wire direction.

*Corrected in stage 4.* That census is one carrier short, and the missing one already covered a
coordinate this paragraph counts as broken. `ChildField.SingleRecordIdField` and
`SingleRecordIdFieldFromReturning` each carry a `NodeIdEncodeKeys` too, and the first claims every
`ID` field on a producer *carrier* parent, a type a `@service` binds directly to a jOOQ table record,
where it has encoded the whole key tuple straight off that record all along. So the population the
read slot adds is narrower than "the whole read family": the class-backed accessor read, the untyped
by-name read, a table record reached through a *parent accessor* rather than through a producer of its
own, and the `@error` type. Reading the four locator arms as the population, rather than the
coordinates the read leaf actually owns, is what hid the third carrier; the slot is still one slot and
still lands at all four arms, which is why the correction changes the account and not the design.

So the fix is one slot, not a per-`@error`-type carrier. `RecordReadField` gains a `CallSiteCompaction`
beside its `ValueLocator`, and the encode then works at every read arm at once. That is the vocabulary
`ColumnBackedField` already uses, and the `NodeIdEncodeKeys` arm carries only a `HelperRef.Encode`,
whose emitter reads `encoderClass()` and `methodName()`, so it ports to a read unchanged. The
classification change is one arm in each `RecordReadField` construction site;
`classifyChildFieldOnErrorType` today ignores every directive, and
`recordReadFieldOrUnclassified` is the shared lift the other arms go through.

The `@error` runtime path needs one further move, and it is a unification rather than an addition. An
`@error` type's extra fields are not projected by a generated fetcher at all: they are read at runtime
by graphql-java's `PropertyDataFetcher`, registered in
`GraphitronSchemaClassGenerator.buildErrorTypeFieldFetchers`, which emits two hardcoded registrations
for `path` and `message` and then folds over `GraphitronType.ErrorType.accessorOverrides`. That list
holds only the fields carrying `@field(name:)`; an extra field without one gets no registration and
falls through to graphql-java on the SDL name. So the encode has to be reachable both with an override
and without one, which means the registration folds over every extra field rather than over the
override subset. `ErrorType` carries one per-field list whose slot holds the read (an accessor base, or
the built-in `path` / `message` arm) and the wire direction, `buildErrorTypeFieldFetchers` becomes a
fold over that one list, and the classified `RecordReadField` and the type-level override list stop
being two spellings of one per-field read.

R686 landed the same move on the neighbouring field while this was being written, which makes the shape
a precedent rather than a proposal. `message` used to carry an `Optional<String> description` on each
handler and fall back at runtime; it now carries a sealed `ClientMessage` resolved once at lift time,
`Static` or `FromSource`, so the emitted body picks its statement per arm rather than "carrying a
runtime null test over a decided value", and the arm sits on the three dispatch variants rather than on
`Handler` because `ValidationHandler` has no client message to carry and "would have to fake one". The
wire direction this item adds to a per-field read is the same kind of fact, decided at lift and read per
arm, and that argument says it belongs on the slot that can carry it rather than on a supertype that
cannot.

**The encode side takes the same two preconditions, for the same reason.** `encode<TypeName>` takes
N key values positionally and a read yields one Java value, so a `READ_VALUE` source carries the
encode out exactly when the node type's key is one column and that column's Java type matches what
the read yields. Both are facts in the relation rather than a validator mirroring a classifier
invariant: the arity is `COUNT(*)` over `intent_resolved_node_key_column`, and the read's own type
comes off the accessor or column the `ValueLocator` names. The refusals name which precondition
failed, the type and the count on one, both types on the other, and one place then says everything
about that coordinate.

*The type precondition turned out to be per locator arm, and one arm already had the comparison,
pointed at the wrong operand.* Which type a read yields is not one fact: the typed-column arm has the
column's binding type from the catalog, the accessor arm has the accessor's declared return, and the
by-name and default arms type nothing. So the comparison is made where its operand lives rather than
once. The accessor arm is the one worth recording, because `resolveRecordAccessor` was already
comparing: it takes the SDL type's reflected form as the expected return, an `ID` field maps to
`String`, and the accessor on a `@nodeId` read yields the key column's own type, so such a coordinate
was already refused, by a message about the SDL type. Passing the key column as the expected return is
therefore not a new gate but an existing one repointed at the operand the encode actually needs, which
is why that arm's refusal reads as the resolver's own diagnostic wrapped in a sentence naming the key
column. The arity precondition stayed one comparison and one message, as written.

This is the same rule as sites 1 and 2 read in the other direction, and stating it once in both
places is deliberate: a consumer neither receives nor supplies the wire format. A read yielding a
`String` where the key column binds as `Long` is refused rather than encoded off a coerced value,
because the value the consumer supplied is then not the key. The unreadable-operand behaviour is the
same too, and for the same reason: a `ValueLocator.DefaultRead` locates nothing and so types nothing,
and that draws no refusal. The encode is emitted on arity alone and javac objects if the read cannot
feed it. Falling back to the unencoded read would put the raw key on the wire where the author asked
for an id, which is this direction's spelling of the bug.

The reporter's own case (`opptaksrundeId`) is single-key, so this refuses only what it can name.
Widening to composite wants a spelling that does not exist yet, either a read yielding a jOOQ
`Record` of the node's key shape unpacked positionally, or a way for the SDL to name N reads, which
`@field(name:)` cannot express; that is a later item and this one states the refusal rather than
inventing the spelling.

### Site 4a: the reverse hop needs a message and a page

One reported case already works. A single reverse hop, filtering parents by their children's node
ids, resolves to a remote binding and lowers to the correlated `EXISTS` on both the argument and the
input-field surface. The only thing between an author and it is that
`JooqCatalog.findUniqueFkToTable` searches from the containing table outward, so the reverse
direction rejects with "no unique FK from X to Y; declare `@reference(path: [{key: ...}])` to
disambiguate". That message does name the spelling that works, which is why this is a wording fix and
not a missing remedy: it frames the spelling as *disambiguation among several candidates*, and an
author whose problem is *zero* candidates in the searched direction reads it as not applying to them.

Two changes, and one of them has a shape the current signature does not admit.

* The rejection distinguishes its two causes. Several foreign keys is a disambiguation; none in the
  searched direction is a different fact, and the message should say that a foreign key declared on
  the *target* side is reachable by naming it explicitly. Note that `findUniqueFkToTable` returns
  `Optional.empty()` for both zero matches and several
  (`matches.size() == 1 ? Optional.of(...) : Optional.empty()`), so the two causes are not
  distinguishable at the call site today; `findForeignKeysBetweenTables` plus `foreignKeyOnSource`
  already give the count, and the split is a signature or call-site change rather than a message
  edit.
* `docs/manual/how-to/multi-hop-nodeid-filter.adoc` stops asserting that a single direct foreign key
  never produces a subquery. That page correction is R691, and this item **absorbs** it rather than
  depending on it: this item is already editing that page twice over, so leaving a third false
  sentence to a separate item would mean three passes over one file to fix one page's account of one
  mechanism. R691 is discarded at this item's Done gate and its file stays as the redirect until
  then.

*Shipped in stage 6, and the split found a message this tree had been writing correctly elsewhere the
whole time.* `BuildContext.fkCountMessage` already separates zero from several for the
direction-agnostic `@reference` path resolution: "no foreign key found between tables", "multiple
foreign keys found", every candidate named, one worked `@reference` spelling. The auto-discovery site
was the one foreign-key refusal in the tree that had not split, so the change is a message converging
on the house one rather than a new wording to invent, and the third arm is what only a *directional*
search can have. Two deliberate divergences from `fkCountMessage`, both stated in the new method's
javadoc: the chained remedy names `{ key: ... }` per hop rather than showing a two-element example,
and the condition-step escape hatch that message offers is not offered here, because a `@nodeId` path
rejects condition steps.

The signature change is the one the note above predicted, taken as a sealed result rather than a
second method: `findUniqueFkToTable`'s `Optional` becomes `findOutgoingFkToTable`'s
`OutgoingFkLookup` with `Unique`, `Ambiguous(fkNames)`, and `NoneInDirection(reverseFkNames)`,
in the `TableResolution` / `ForeignKeyLookup` family of `JooqCatalog`-local result types. The arm
count is the cause count, and the reverse names ride on the last arm rather than splitting it,
because "which foreign keys connect these two tables the other way round" is evidence for the
sentence and not a fourth conclusion. The FKs the existing direction filter *discards* are exactly
that evidence, so the search already had the answer in hand and was dropping it.

One consequence worth stating, because it is the reason the message matters more than the wording
suggests: the constraint the reverse arm names is the whole remedy. An author who reads it writes one
`@reference(path: [{key: ...}])` and the coordinate builds, lowering to the same correlated `EXISTS` a
junction chain does. Nothing else about the shape was ever missing.

### Sites 1 and 2: the consumer never sees the wire format

A plain argument on a `@service` field, and a member of a bean-backed `@service` input, are one
problem wearing two coordinates, and the rule at both follows from the invariant this whole item
exists to enforce: **a slot carrying the instruction never delivers base64 to consumer code.** A
service does not know that node ids exist, and neither does the bean that feeds it. The author wrote
`@nodeId`, so the value the consumer receives is decoded, and there is no arm in which the generator
hands a `@service` method the wire string and calls it done.

The evidence that neither coordinate honours that today is the classified carrier rather than a
reading of the source. `ServiceCatalog.argExtraction` is the whole story at site 1: it takes the
parameter's Java type and the SDL leaf type and no directive container at all, checks enum parity and
wire coercion, and resolves every scalar to `CallSiteExtraction.Direct`. It cannot see `@nodeId` even
in principle. At site 2 an `ID` field carrying `@nodeId(typeName:)` on an input type backing a
consumer bean generates `java.lang.String title = (java.lang.String) raw.get("title");` inside the
`create<Bean>` helper. That is the shape the reporter reached for as the workaround for site 1 and
found equally inert. What exists today is the tuple-shaped destination, `JOOQ_RECORD`: a `@service`
parameter typed as a generated `*Record`, and a record-typed member of a consumer bean.
`InputBeanResolver` shows the asymmetry directly. Its `buildJooqRecordLeaf` reads `@nodeId` on a
record-typed bean member and rejects a missing `typeName:` there; `collectJooqBindings` and
`buildRecordKeyDecode` do the same on the record-param axis; and neither has an arm for the
single-valued member.

**The two coordinates part company at the bean boundary, and only one of them ends this item with an
emitter.** Site 1's value arrives at a parameter and the decode is emitted there. Site 2's arrives one
step inside a value handed to a parameter, and the decode does not emit at a bean member yet, so the
coordinate is *refused as deferred* rather than resolved. That is not the arm this section set out to
write, and it is the whole of what site 2 needed: what the reporter found there was silence, an `ID`
member reaching `CallSiteExtraction.Direct` and handing the bean the opaque id with nothing in the
build saying so. A deferral closes the silence, names both remedies an author has (declare the member
as the node type's own generated record, which takes the whole tuple today; or take the id at the
producer's own parameter, which now decodes), and says out loud that the emitter is owed. The store
reached the same conclusion from the other side before this: `intent_node_id_decode_defect` excludes
the input-field site from its population and its comment calls that shape "owed an emitter rather than
a verdict", because comparing a container's type against a key column's would be refusing a parameter
the author was right to declare. A deferral is how the walk says "owed an emitter" out loud.

**That arm is the whole of what this item adds at site 1, and the gate it stands behind already exists.**
Passing the base64 through is the bug; refusing the coordinate outright is also wrong, because the
instruction is carriable whenever the node type has one key column, the decode yielding exactly one
value that a single-valued slot takes. R668 shipped both halves of deciding that, one directive over,
and this item reads them rather than restating them:

* **Arity.** `COUNT(*)` over `intent_resolved_node_key_column` for the node type. A composite key has
  N values and the slot has one place, so the refusal names the type and the count.
* **Type agreement.** `intent_argmapping_key_column_candidate` already carries a matched key column
  with "that column's Java type beside it where the catalog can say", and
  `intent_resolved_node_key_projection` already makes the agreement a *join predicate* rather than a
  check after the fact, at **equality of the erased Java type with no widening admitted**. A
  `SMALLINT` key column against an `Integer` slot is a disagreement, and softening that is what would
  let a narrowing through. So this item supplies the slot's own type at one new coordinate, the
  `@service` parameter's, and the existing predicate decides there.

**One property of that gate is easy to lose in the reuse, and it is load-bearing: it fires only where
both operands are known.** The catalog cannot always type the key column (a pinned key column on an
unbound or ambiguously-bound node type, which `intent_resolved_node_key_column` deliberately admits as
a row) and the classpath census cannot always type the slot (a consumer compiled without
`-parameters`, a reference resolving no method). Both relations reach the type by outer join for
precisely this reason, so an untypeable operand is a NULL and never a missing row, and R668's rule is
that the gate then stands aside rather than refusing: "requiring the match in either case would have
turned such a pair into one that is neither a projection nor a defect", with "the compiler's own error
as the backstop it always was".

**Standing aside means something different here than it does there, and inheriting the words rather
than the principle would reintroduce the bug.** At R668's coordinate the behaviour a stood-aside gate
falls back to is an existing projection whose types nothing checked, so leaving the pair where it was
is harmless. At these two coordinates the fallback is the base64 pass-through, because the arm is new.
"Resolve as it did before the predicate existed" would therefore hand the consumer the wire format,
which is the one outcome this item exists to eliminate. So the principle transfers and the emission
does not: with the slot's type unreadable the decode is emitted anyway, on arity alone, and javac
objects if the slot cannot take the decoded value. Nothing fabricates a verdict from an absent operand,
the consumer still never sees base64, and the compiler is the backstop exactly as R668 left it. Only
the *refusal* requires two known types; the resolution requires arity. Stage 5 states this as an exit
condition rather than leaving an implementer to read the neighbouring comment and copy the wrong half.

The arity half is what makes the reporter's own code fail rather than quietly keep working, and that is
the intended outcome. Their method takes `String plasstildelingId` and today receives base64;
afterwards the build tells them the key column binds as `Long` and asks for a parameter of the column's
own type. The remedy is one line of Java in their own signature and no SDL change at all.

**The authored and inferred forms are one destination, not two.** R668's capability is the author
naming a key column as a trailing `argMapping` segment; the rule above reaches the same column by
arity when no segment names it. Both put one key column's value in one slot, so both are
`SINGLE_KEY_COLUMN` with the same column in the key-column child and the same emitted decode.
Whether a segment named it is provenance rather than shape, and it is already answerable by joining
`intent_argmapping_pair`, so this relation does not restate it.

That has a consequence for a verdict already in the tree, and stage 2 owes the edit.
`ArgmappingProjectionDefects`' `BARE_NODE_ID` currently rejects an `argMapping` pair that binds a
`@nodeId` leaf and names no key column, saying the encoded id "would reach the database verbatim".
Under the arity rule that is no longer true for a single-key node type, where the inferred projection
carries the decode out. Its population shrinks to composite keys, and its text moves from "names no
key column" to the arity fact. The type-disagreement half needs no new wording at all: R668's
`KEY_COLUMN_TYPE_MISMATCH` already says "projects '<column>' of '<type>', which jOOQ binds as X, but
the parameter it binds to takes Y; bind a parameter of the column's own type, or project a key column
the parameter can take", and the second clause of that remedy simply has nothing to offer at arity 1.

**The stated limit, since reuse inherits weaknesses too.**
`intent_resolved_node_key_column.column_name` hands out a *spelling*, the winning tier's own, its
comment saying outright that whether the name is a column the table actually has "is deliberately not
asked here". Reaching that column's Java type therefore crosses from a spelled reference to a catalog
reading, which is why the candidate relation folds case on both sides of it. R731 is that crossing's
item and R724 is the machinery it names. Neither is a dependency and this item does not wait: it reads
the candidate relation's payload as it stands, on the same terms R668 already ships it. What changes if
they land first is that the type a refusal names comes off a column decided rather than picked, which
matters because a refusal is text an author has to be able to check.

### The detection is a verdict view, and it partitions the population

Once arity and type agreement are facts in the relations, the refusals stop being absences and become
statements. A composite key at a single-valued slot and a type disagreement are both things the store
can say, with the operands to say them. So the detection is not a bare anti-join: it is a view over the
instruction population whose rows are the instructions with no resolution, each carrying which
precondition failed, in a closed vocabulary. That is `intent_argmapping_projection_defect`'s shape
one directive over, and following it is what lets the message text converge on
`ArgmappingProjectionDefects.rejectionOf` rather than be renegotiated.

Written in one pass over the population, with the verdict picked by a `CASE` rather than one
`UNION ALL` arm per verdict, for the reason under "The derivation depth is a design constraint"
above. The verdicts the census yields:

* `KEY_ARITY_EXCEEDS_SLOT`. The node type's key is several columns and the destination or source
  holds one value. Names the type, the count, and the coordinate.
* `KEY_COLUMN_TYPE_DISAGREEMENT`. One key column, and its Java type is not the slot's. Names both
  types and the column, on `KEY_COLUMN_TYPE_MISMATCH`'s existing wording.

**The view carries no accept line of its own, and the schema has settled that this is how a detection
is shaped.** `intent_authored_claim_conflict` used to gate on the walk's reach as a join and now states
its whole predicate and nothing else, its comment giving the reason in one sentence: "a filter wearing
the view's name would substitute one consumer's population for the fact." Each consumer then applies the
population its own question needs, and the two genuinely differ. The build-error surface joins
`intent_type_domain`, because only the emitted surface can fail a build. The editor's diagnostic arm
reads the rows ungated, a coordinate nothing reaches being precisely where an author most needs the
signal. This item inherits that split unamended: a refused instruction at an unreached coordinate is
still a refused instruction and the LSP should say so, while the build fails on the ones the emitted
surface reaches. The use-site grain is not a competing filter, it is what makes a row answerable at all.

**With no accept line the refusals partition the population, and that is the invariant worth stating.**
Every instruction either resolves, with a row in its direction's relation, or is refused, with a row
here. An instruction in neither is not an author error at all: it is a coordinate the model cannot
account for, which means the census missed a shape. Stating the partition is how a gap announces
itself as a defect in this item's own work rather than as a silent pass at a consumer, and it is why
the detection reads the population rather than the captured `@nodeId` rows.

The type gate's stand-aside is not a third arm of that partition, and it reads like one, so the
relation's own comment should say it is not. An instruction whose type gate stood aside for want of an
operand *resolves*: it lands in its direction's relation and its decode or encode is emitted on arity
alone, with javac as the backstop if the slot cannot take the value. Only the refusal needs two known
types; the resolution needs arity. What a stand-aside never does is fall back to the pass-through,
which is the misreading the section above heads off.

The projector is small and its home exists: a further component on `StoreDetections` beside the two
detection families already there, `AuthoredClaimConflicts` and `ArgmappingProjectionDefects`, decoded
into located `ValidationError`s the same way. One rule, every coordinate, and the same fact available
to the LSP and the MCP context rather than living inside two walk classes.

Both verdicts are `Rejection.structural`. Neither is a deferral: a composite key at a single-valued
slot wants a spelling nobody has minted, and a type disagreement is the author's to fix in one line.
There is no arm in this item that fails while promising an emitter later.

**Written, and the population is narrower than "the instructions with no resolution" by three
exclusions, each of which turned out to be a different kind of fact.** The relation is
`intent_node_id_decode_defect`, the two verdicts are the two the census yielded, and one `CASE` on the
arity picks between them over one pass, so the driving relations are named once. What writing it found
is that the hard part is not the verdicts but which slot rows the relation may judge at all.

* **The mapped carrier is R668's population, and `carrier` already names the boundary.** A
  `MAPPED_PARAMETER` slot at an argument site is an `argMapping` pair whose bound leaf is that
  `@nodeId` argument itself, so `intent_argmapping_projection_defect` judges both of these facts
  there already: `BARE_NODE_ID` for the arity and `KEY_COLUMN_TYPE_MISMATCH` for the type
  disagreement, each keyed on the entry the author wrote. Restating them would be a second copy with
  a precedence between the two, which is the failure mode that family's own comment warns about. So
  this view's population is `NAMED_PARAMETER`, the carrier no pair exists for, which the slot
  relation calls "the coordinate at which the wire format reached consumer code unread". The
  discriminator was already in the schema: `carrier` was added for provenance in a message, and it
  turns out to be exactly the line between the two families' populations. The two families key their
  use sites differently, the pair's being the directive application's coordinate and the
  instruction's being the argument, which is why the boundary is drawn on the carrier rather than by
  joining one use site to the other.
* **The input-field site draws no row, and comparing types there would be wrong advice rather than
  merely out of scope.** The parameter receives the whole input object and the decoded value goes to
  a member of it, so the only destination that resolves is the node type's own record and everything
  else wants the walk into the class the slot relation deliberately does not perform. The parameter's
  type is not the value's type, so a refusal naming both would ask an author to change a declaration
  they were right to write. That shape is owed an emitter, and a verdict there would have quietly
  turned an owed capability into an author error.
* **The encode side is the walk's and stays there, which is also what settles what stage 4 left
  owing.** Stage 4 ships both encode refusals at classify time, and the walk holds both operands: it
  loads the backing class through the codegen classloader and reads the catalog for the column. A
  view arm restating them would lose the race and never mint an error. The relation's own type
  precondition, named as owed, turns out not to be statable at all: what type a read yields is
  decided by which of four locator arms the classification chose, two of the four type nothing, and
  one of the two that do resolves an accessor by a candidate order and a naming rule, which is an
  algorithm rather than a fact this schema holds. So `intent_node_id_encode` states arity, its
  `READ_VALUE` rows over-claim by exactly the coordinates the walk refuses on type, and its comment
  now says so. A partial precondition would be worse than the stated absence, resolving or refusing
  by which arm answered without being able to say which arm that was.

**The arity verdict reads the slot's type as an absence, and that is the one place the stand-aside
rule needed reading rather than copying.** A refusal needs the operands it names, so the type verdict
fires only where the catalog typed the column and the census typed the parameter. The arity verdict
names no type at all: what it needs is whether the slot is the tuple's own row type, and at this
carrier a parameter position naming no class is a primitive or a type variable, neither of which is a
generated record. So a composite key still refuses at a parameter the census could not type. That is
not a verdict fabricated from a missing operand; the operand is the row type's identity and the
absence answers it.

**So the invariant is a stratification rather than a partition, and the residue splits in two once
each shape is chased down.** Every instruction resolves, draws a verdict here, or is refused by a
family that owns it. Two shapes are owed an emitter: every input-field slot above, and a slot the
value lands inside. Two are refused elsewhere, and finding that out is what discharges the first of
the two gaps this item named rather than absorbed. **The overloaded `@service` is already refused
upstream, by name, and the answer was in `ServiceCatalog` all along.** `pickMethod` rejects with
`ReflectionError.AmbiguousMethod` the moment more than one declaration shares the name, so a
coordinate whose slot relation resolves several candidates never reaches emission and declining to
pick here leaves no silence behind. The gap was stated as "either a third verdict or a reading that
the ambiguity is already refused upstream", and it is the second: the ambiguity is a fact about the
reference, refused where the reference is resolved, and a verdict here would have been a second
answer to a question already settled two phases earlier. The other refused shape is a departing or
arriving table that did not resolve, which the walk likewise refuses on its own.

Writing the invariant as an absolute partition would have forced one of those four to be either
absorbed into a verdict it does not fit or judged by a relation that cannot see the operand. The
value of stating it was never the claim itself but that each gap has to be named to be excluded, and
naming this one is what found the rejection that had been covering it.

**The projector shipped as `NodeIdDecodeDefects`, and the one thing it added beyond the decode was a
shared vocabulary.** It is the fourth component on `StoreDetections`, wired in `FactCapture.detect`
beside the two families already there, and it gates on `intent_type_domain` exactly as
`AuthoredClaimConflicts` does, with the same one-sentence reason in its own javadoc. Both arms are
`Rejection.structural`, neither is a deferral, and the messages name the operands the view compared
and nothing it did not.

The convergence the stage asked for turned out to need a home rather than a habit. "The message
vocabulary converges with `ArgmappingProjectionDefects.rejectionOf`" is satisfiable by copying three
helpers, and copied text is what drifts: a wording improved on one side and not the other reads as two
different rules for one fault. So `nodeIdSpelling`, the qualified-type shortener and the key-column
list moved into a `NodeIdMessages` the two families share, and what stayed private to each is the part
that genuinely differs. The remedies differ because the carrier does: an author who wrote an
`argMapping` entry is told about their entry, and an author who wrote none is told about the name
match that found the parameter, which is what the `carrier` column exists to make sayable. Both arms
here offer two remedies rather than one, and the second is the other family's population: declare the
parameter as the node type's own generated record, or bind one key column to it with `argMapping`. A
refusal that named only the first would be telling an author to change a signature when an entry in
the directive they already wrote is the smaller change.

**The pipeline tier needed both corpora, which is the one fixture fact worth recording.** Every
existing fixture for the sibling family captures SDL alone, and that is enough there. It is not enough
here: the jOOQ catalog is what types a key column and the classpath census is what types a parameter,
so a fixture missing either constructs a stand-aside it did not mean to and asserts an absence for the
wrong reason. The census also takes public top-level classes only, so the tree's shared
package-private service stub is invisible to it however well reflection resolves the same method, and
the fixtures name a small public stub of their own. Seven cases: the two verdicts, the two remedies
each drawing no verdict, both readings of an untypeable parameter (no type verdict at one column, an
arity verdict still at two), and the domain gate.

## Stages

Ordered so each stage is separately verifiable, and so nothing ships a rejection ahead of its
replacement.

1. **Argument-site reference-step resolution.** The sibling views over
   `graphitron_argument_reference_step` that `intent_field_reference_step_hop` and
   `intent_field_reference_step_target` have at field site and argument site lacks. Exit: an authored
   argument-site `@reference` path's hops and terminal target are readable from the store, agreeing
   with the field-site views' answers on the same path shape. R723 named this as its own prerequisite
   and gains it. Shipped at `4548c98f`.
2. **The instruction population and the two resolution relations.** The population first, all three
   forms of the instruction including the name-carried one that has no captured row; then both
   relations, the use-site grain over `intent_input_occurrence_path`, the four decode destinations and
   two encode sources, and the decode relation's key-column child with its lift plus its hop child.
   The `SINGLE_KEY_COLUMN` destination gains its inferred arm, which is what makes sites 1 and 2
   resolve rather than refuse, and `BARE_NODE_ID`'s text is edited down to the arity fact in the same
   stage. `NodeIdLeafResolver` stops spelling a different reduction from the decode relation's, which
   is as far towards reading the rows as this item goes; "The resolver cannot be the reader" above says
   why, and hands the reader itself to R682. Exit: every `@nodeId` shape that generates today has a row naming the
   destination or source it actually uses; a `@service` method whose parameter type matches a
   single-column node key receives the decoded value and never the base64; and the tree's existing
   `@nodeId` behaviour suite stays green apart from the four `BARE_NODE_ID` cases the arity rule
   deliberately retargets, which the Tests section names so a fifth red is a finding. Each relation added here states its
   own inline multiplicity, computed statically from the DDL, and none of them introduces a
   per-verdict or per-destination `UNION ALL` arm that re-joins the driving relations. Shipped across
   `705f96b6` (the population), `e1ad3ae3`..`375943a9` (the two children and the encode relation),
   `e578ef47`..`e9dc149f` (the evaluability work the section above records), `417298d3` and
   `cb502a21` (the four destinations), `9c801d16` (the inferred arm and the `BARE_NODE_ID` edit) and
   `12dabb11` (the resolver's reduction). The named-parameter half of the exit did not land with the
   relations and is the rework round's own increment, below.
3. **The junction chain.** With the relation in place this is the absence of a rejection rather than
   an addition: `validateLift` stops rejecting and its absent lift becomes absent local columns, so
   the chain binds remotely and reaches the hop-general `EXISTS`. Exit: a junction chain returns each
   parent once against PostgreSQL; the identity-carrying chain still binds locally; a condition hop
   still rejects with its own message; each of the four write rails has a stated message. Shipped at
   `2e46090a` (the classifier and the diagnostics) and `d0f6358c` (the row count).
4. **The read-family encode.** The `CallSiteCompaction` slot on `RecordReadField`, the classification
   arm at each construction site, the `ErrorType` per-field unification with its registration swap,
   and both preconditions stated in the relation. Exit: an `@error` field carrying
   `@nodeId(typeName:)` returns an encoded node id and the reporter's hand-written encoder call sites
   can go; the same holds at the accessor, by-name and typed-column read arms; a composite-key node
   type at a read coordinate is refused with a message naming the count, and a read whose type
   disagrees with the key column's is refused naming both. Shipped; the type precondition is stated
   per locator arm rather than in the relation, for the reason the design section gives, and the
   relation's own type precondition is what stage 4 leaves owing. Answered in stage 5, and the answer
   is that it is not owed: what type a read yields follows from which locator arm the classification
   chose, and one of the two arms that type anything resolves an accessor by a candidate order, which
   is an algorithm rather than a fact the store holds. The relation states arity, its `READ_VALUE`
   rows over-claim by exactly the coordinates the walk refuses on type, and its comment now says so.
   A stated absence beats a precondition that resolves or refuses by which arm answered without being
   able to name the arm. Shipped at `6d70b6a4` (the read arms) and `8b819323` (the `@error` type).
5. **The defect view and its projector.** The two verdicts over the instruction population, read by a
   projector into located `ValidationError`s as a further component on `StoreDetections` beside the two
   detection families already there (`AuthoredClaimConflicts` and `ArgmappingProjectionDefects`;
   `ResolvedKeyProjections` is the record's third component and not a detection family). Both arms are
   `Rejection.structural`. Exit: a composite key at a single-valued slot and a type disagreement each
   fail the build naming their own operands; a slot or key column no census can type draws no refusal
   and still gets its decode or encode emitted on arity alone, never the pass-through, so this view
   strictly adds refusals and removes no emission and never reinstates the bug at the coordinate whose
   type nobody could read; the view
   carries no population filter of its own, the build-error surface applying one and the editor's arm
   reading it ungated; the resolution relations and this view partition the
   instruction population, so no instruction falls in neither; and the same fact is available to the
   LSP and the MCP context rather than living inside two walk classes. The message vocabulary
   converges with `ArgmappingProjectionDefects.rejectionOf`, which is shipped text to read rather than
   a wording to negotiate.
   The relation shipped first, as `intent_node_id_decode_defect`, and writing it moved three of those
   clauses: the population is the carrier the pair-grain family cannot see rather than every
   unresolved instruction, the partition is a stratification with four named gaps rather than an
   absolute one, and the encode half stays in the walk that already holds both its operands, which is
   also what settles the type precondition stage 4 left owing. The design section above states each.
   The projector followed as `NodeIdDecodeDefects`, the fourth component on `StoreDetections`, with
   its pipeline tier; the convergence clause turned into a shared `NodeIdMessages` rather than three
   copied helpers, for the reason the design section gives. The stage's last piece was the second gap
   it named, the producer-backed output field the classifier drops, and it shipped as a deferral at a
   placement gate covering three arms rather than the two the gap named. That closed the silence and
   left the encode relation's population alone, for the reason the design section gives: a resolution
   relation's row was never an emitter's existence, and the tree had a deferred `@nodeId` shape with a
   claiming row before this item started. Shipped at `270cc6a3` (the relation), `2d7223b4` (the
   projector) and `7287451e` (the producer-backed placement gate).
6. **Site 4a, the message and the page.** The auto-discovery rejection separates its two causes; the
   manual page's single-hop claim is corrected; the reverse filter gets the execution-tier row-count
   pin it has never had. Independent of every other stage and the smallest thing in the item.
   Shipped, with the row-count pin having landed early: stage 3b took it alongside the junction
   chain's, both reaching one execution class because they are two ways of reaching one binding. So
   this stage is the refusal's three arms and the page, and the page needed one section more than the
   list asked for, the reverse hop having been authorable all along with nothing telling an author
   so. Shipped at `5335f0ec`.

Stage 6 is independent throughout. Stages 1 and 2 are the spine and nothing after them lands without
them.

**Stage 2 was taken in sub-increments, and one of its exits moved.** The order is the SHA list
above. The exit moved at the last of them, `NodeIdLeafResolver` converging on the decode relation's
own reduction: it was written as the resolver becoming
a reader of these rows, and the resolver runs before the rows exist. "The resolver cannot be the
reader" carries that argument; stage 2 exits on the relations being total and on the resolver spelling
their rule rather than a second one, and R682 owns the read. The Java-slot fork itself ships with the
table destinations rather than after them, because the two table destinations are only total once the
coordinates that reach Java are out of their population.

**Stage 3 splits at the database.** The gate's removal, the classifier's answer and every diagnostic
an author can now meet are one increment, verifiable without PostgreSQL and shipped first. The row
semantics are the second: a junction is where "the `EXISTS` multiplies nothing" stops being an
argument and becomes a count, and only an execution against a real database settles it. Splitting
there rather than by tier keeps the first increment's exit honest, since a stated message is
falsifiable at the pipeline tier and a row count is not.

**The junction fixture this item named is the wrong one, and the seed data is why.** The Tests section
picked `film_category` as the natural junction, correctly as a *shape*: it pairs two tables and is
already in the schema. What it cannot do is carry the assertion, because the seed gives every film
exactly one category, so no requested pair of categories can produce the parent that matches twice
and the count would have passed against a predicate that multiplies. `film_actor` is the fixture that
does carry it: PENELOPE is cast in films 1 and 2, so asking for both is the multiplying case, and the
seed already says so. Reading a junction as a shape rather than as a shape plus a population is what
picked the wrong one, and the correction cost no DDL: the pipeline and unit tiers keep
`film_category`, where only the shape is being asked about.

**Stage 4 splits at the coordinate that has two spellings of one read.** The slot, the classification
arm, the two refusals and the emitter are one increment: they are the whole of the encode at the
coordinates where graphitron's own classification locates the value, and they are verifiable from the
classified leaf plus one emitted body. The `@error` type is the second, because there the classified
leaf is not what the runtime reads: the registration folds over a type-level override list instead, so
the coordinate needs the two collapsed into one per-field read before a wire direction on the leaf
means anything at all. Splitting there rather than by tier keeps the first increment's exit checkable
without the unification, which is the part with a shape decision in it.

**Where that per-field list can live is decided by an ordering, not by taste.** The plan says
`ErrorType` carries it, and `ErrorType` cannot: `classifyType` runs for every type inside
`buildClassificationIndices`, which is what *builds* `ctx.nodes`, so the lift that produces an
`ErrorType` cannot resolve an encoder. It is also memoized, so a lift reading a half-built index would
cache the answer. The list therefore hangs off the classified fields, which `FieldBuilder` produces
with the node index in hand, and `GraphitronSchema` exposes it as the one per-field read the
registration folds over. That satisfies what the plan was after, the classified leaf and the
type-level override list stopping being two spellings of one read, and puts the list where the
resolution is possible.

**The `@error` increment's real finding is that one of its two check sites had a message the change
made false.** Two sites run the accessor-coverage check, `FieldBuilder`'s for a class-backed payload
and `HandlerAccessorCheck`'s for the `@service` channel, and both compare an accessor's declared
return against the SDL field's reflected form. Repointing that expectation at the key column is what
makes a `@nodeId` extra field authorable at all; what it also does is make the walker's message a
lie. That message says the source class "exposes no accessor for SDL field 'filmRef'" and then lists
the available accessors, which now includes an accessor of exactly the looked-for name, differing only
in return type. So the typed arm gained the expected type and states it, and both sites reach the
expectation through one method rather than each deriving it. A message that lists the thing it says is
missing is worse than a missing message, and only the fixture that reads a `String` where the key
column is an `Integer` shows it.

**The arity rule landed at the `argMapping` site, and it landed by adding a population rather than by
removing a rejection.** The plan above says stage 2 edits `BARE_NODE_ID`'s text down to the arity
fact, and reading that as a text edit is what would have shipped a hole: lifting the rejection at
arity 1 with nothing resolving in its place puts the base64 back on the wire, which is the one
outcome this item exists to prevent, and no test in the tree would have caught it because the tests
assert the rejection rather than the emission. What makes the lift safe is the item's own claim that
the authored and inferred forms are one destination. So the inferred arm went into
`intent_argmapping_key_column_candidate` as a second arm over the zero-trailing-segment population,
and every consumer of the relations beside it picked it up unchanged: the projection relation resolves
the sole column, R668's emitter reads the same row shape it already read, the type gate compares the
same two operands, and `intent_argmapping_projection_defect`'s bare arm shrank to an anti-join against
that candidate rather than to an arity test of its own. `BARE_NODE_ID`'s remaining population is three
ways to have nothing to infer, none of which is "the author named no column": no node type named, no
key columns resolved for the one that is, and a key of two or more. Its text is those three facts, and
the claim that the encoded id would reach the database is gone from all of them, being true only
because the build stops.

**The rejection this increment had to remove was not the one the plan named.** Lifting the bare arm
left an arity-1 binding still failing the build, and the second refusal came from the schema walk:
`RoutineDirectiveResolver`'s leaf-type gate compares graphql-java's coercion output for the SDL leaf
against the routine parameter's declared Java type, so an `ID!` bound to an `Integer` parameter is
rejected before capture, by a message that offers "route the value through a converting scalar /
`@nodeId` decode" as the remedy for a schema that had already written the decode. The authored form
escapes that gate for a reason that is not a rule: a path descending past a scalar resolves no leaf
type, so the gate declines to judge it. The bare form resolves its leaf and gets judged.

That gate now stands aside on a leaf carrying `@nodeId`, and the reason is worth keeping because it is
not leniency. A `@nodeId` leaf's value is decoded before anything consumes it, so the parameter
receives a key column's own value and the coercion output the gate compares against never reaches it:
the comparison was between two things that never meet. The gate is also structurally unable to make
the comparison that does matter, the key column's binding type against the parameter's, both being
captured facts it runs before. So the whole judgment is the store's, which has an arm for each way it
fails. This is the same division `intent_argmapping_projection_defect`'s comment argues for the walk's
other segment rules, applied to a gate that predates them.

Two consequences to carry forward. The same stand-aside is owed at every other site whose walk gates a
leaf type against a declared Java type, and `@service` through `ServiceCatalog.argExtraction` is the
next one. It was deferred here on an argument that turned out not to reach it: a resolved projection at
an unwired `@service` site draws the deferral `ArgmappingProjectionDefects` mints from
`EMITTING_SITES`, so at *that* carrier the gate's extra error changes no verdict. The named-parameter
carrier is a different coordinate, and there the gate's error is the only verdict at the remedy, which
turns an author's outcome from "builds" into "cannot". The rework increment below pays that debt. And
the stand-aside is what makes arity 1 emittable at `@routine` today, which is why this increment
carries a pipeline case asserting the build *completes* rather than only that the detection is silent:
silence at the detection and a red build are indistinguishable at every tier below that one.

**The rework increment: the named-parameter carrier gets its stand-aside and its emitter, and the bean
member gets a deferral.** Stage 2's exit named a `@service` method whose parameter type matches a
single-column node key receiving the decoded value; the relations landed and that sentence did not.
Three spellings of one coordinate all failed, and the two that failed are the two the new refusals
themselves prescribe: `Integer key` (the type remedy) and `InventoryRecord key` (the arity remedy) both
drew `WireCoercionError.Assignability`, whose own message asks the author to route the value "through a
converting scalar / `@nodeId` decode" on a schema that had already written the decode. The third,
`String key`, classified with the wire format still reaching the parameter.

What ships: `ServiceCatalog.bindServiceMethod` reads the same `pathLeafDeclaresNodeId` the
`@routine` gate reads and mints the decode in place of the type check, at the named-parameter carrier
only. Which decode is the same question the fact model asks, decided on the same operand: a slot typed
as the node type's own generated record takes the whole tuple (`NodeIdDecodeRecord`), and every other
slot takes one value (`NodeIdDecodeKeys.ThrowOnMismatch`, whose helper projects a one-column key to
that column's own value). Both emitters follow, at the root coordinate through
`ServiceMethodCallEmitter` and at the child coordinate through `ArgCallEmitter`, where the decode arms
threw an invariant before. `ArgBindingMap` gains an `authoredTargets` component, because the carrier is
"no `argMapping` pair names this parameter" and the map's identity entries had made that unanswerable:
`byJavaName` holds an entry for every unclaimed slot, so membership there says nothing about what the
author wrote. The store's named-parameter arm draws the same line with the same `NOT EXISTS`.

The mapped carrier is deliberately untouched. An `argMapping` pair binding the root argument already
has both an emitter (the projected-binding path) and two refusals (`BARE_NODE_ID` and
`KEY_COLUMN_TYPE_MISMATCH`); minting either here would be a second copy with a precedence between them,
which is how one family ends up with two answers that agree until one changes.

**The second rework increment: the bare spelling resolves by the same rule.** The first increment
gated the new arm on a written `typeName:`, so a bare `@nodeId` at the same carrier still fell through
to the type gate and still had no signature an author could write. Both authored forms now resolve
here. A bare directive inherits its target from the table the consuming field's own return type binds,
then the one node type over that table, which is the store's `TARGET_TABLE_NODE_TYPE` basis arrived at
from the other direction: the argument's predicate binds on the table its field returns, so
`intent_argument_scope_table` and the walk reach the same table and the two spell one rule. The rule
itself moves to `BuildContext.inferNodeTypeOverTable`, which `NodeIdLeafResolver` now reads instead of
carrying its own copy, so "which node backs this table" and the two absences that answer it have one
wording. A third absence is this coordinate's own: a field whose scope resolves to no table at all has
nothing to inherit, and that is refused naming `typeName:`. Refusing a written directive the walk
cannot resolve is the direction the population section argues for, an instruction the population
misses being a coordinate that stays silent.

The slot's scope is two rungs rather than one, ranked as `intent_argument_scope_table` ranks them:
the consuming field's own return table, and beneath it the table `@mutation(table:)` names, which is
what a delete surface binds against, its return being a scalar or a status type. The walk reads both,
one lookup each off the `fieldDef` already in hand. Reading only the first left the bare form refused
at a coordinate whose explicit spelling classified and whose store rows resolved, with the defect
view's own prose quoting a `typeName:` back at an author who had written none.

## Tests

Behaviour, at the tier that can observe it. No test asserts that a relation agrees with the classified
model: how a fact is sourced is not a behaviour, and a test that knows is a test that breaks when
R682 moves the sourcing.

The strongest guard is already installed and costs nothing. The tree's existing `@nodeId` suite is the
accept set: if a resolution relation fails to enumerate a shape that works today, a currently-green
pipeline or execution test goes red, because the build now rejects a schema that used to generate.
That is what makes a total census safe to attempt.

**The guard runs in one direction, and stage 2 deliberately moves the other, so the expected reds are
named here rather than discovered.** The accept set catches a resolution the relations miss. The arity
rule runs the opposite way at `argMapping` sites: a single-key node type bound without a key column
stops being a defect and starts resolving, so the cases standing on that fixture change with the rule
rather than around it. This paragraph named four and predicted the wrong four, which is worth keeping
as written rather than quietly corrected, because the way it was wrong is the same in both directions.

What it named: `ArgmappingProjectionDefectsTest.aNodeIdBoundWithNoKeyColumnIsRejectedNamingTheKeyColumns`
and `aServiceArgMappingReportsUnderItsOwnDirective`, and
`ArgmappingProjectionRejectionPipelineTest.aBareNodeIdBindingFailsTheBuild`, all three moving to a
composite-key node type along with `BARE_NODE_ID`'s own population, which is what they did. Plus
`theUseSiteClauseAppearsOnlyWhereItSaysMoreThanTheCoordinate` as the one needing more than a fixture
swap, on the reading that it tests use-site clause suppression off the *first* violation and so needs a
node type that still mints a violation. That case stayed green, and its staying green was the problem:
it asserts the message does *not* contain a use-site clause, which a deferral also does not, so it had
silently stopped reading the arm it is about. It moved to the composite type anyway and gained an
assertion that the rejection is the author arm, which is what a case about a clause of that arm's
prose needs in order to fail when the arm changes underneath it.

What it missed, in two families. Nine store-tier cases in
`no.sikt.graphitron.model.intent.ArgmappingProjectionDefectTest` and
`ResolvedNodeKeyProjectionTest` went red, because eight of them departed from one shared single-key
fixture and used the bare form as a vehicle for a subject that is not the bare form at all: which
sites report, where the location points, that a sibling graph's rows stay in their partition. The
prediction was written by reading the behaviour tiers and never looked at the class whose whole
content is this relation's algebra. And two graphitron-tier cases went red on message text rather than
on arity, `aBareNodeIdWithNoTypeNameIsRejectedNamingBothOmissions` and
`aKeyColumnTheParameterCannotTakeFailsTheBuild`, both asserting prose the item itself mandated
rewriting: the paragraph predicted the arity churn and not the churn from the edit it was pairing that
prediction with.

So the rule the "fifth red is a finding" heuristic actually needs is narrower than a count of named
cases. What a red means is: a case whose *subject* is the bare arm and which now resolves is expected;
a case that merely stood on a single-key fixture is expected and says the fixture was doing work the
case never declared; a red in a message assertion is expected wherever this item edits that message.
A red anywhere else is a missed resolution. The fixtures now carry both arities and every case names
which it binds, so the next increment's reds are readable without a list.

The increment after it drew no reds at all, which is the answer that rule wanted. Converging the
resolver on the decode relation's reduction is behaviour-preserving over everything the catalog can
express, so `NodeIdLeafResolverTest`'s eleven cases stayed green through a replaced discriminator, a
replaced lift walk and a retired result type, and the case that pins a non-identity permutation stayed
green through the retirement of the permutation step itself. Two of the eleven were renamed, their
names having stated the discriminator that went; a rename is not a red and the sweep is what found
them. The one shape where the old and new answers differ is unreachable in this catalog, and the
attempt to reach it is worth recording: pinning a node key strictly inside a foreign key's referenced
columns fails at the `@node` gate, which demands a real unique key, so producing that shape means
declaring two nested unique constraints on one table and no fixture does. A test asserting it would
have had to ship the fixture, and the shape is not what this item is about.

The gate's removal then drew no reds either, over 3762 cases, and that is a finding about the gate
rather than a quiet result. Nothing in the tree pinned `validateLift` except the one unit case written
to pin it, so the rejection had exactly one consumer and it was its own test. The rewrite of that case
is where the value sits: the same fixture that proved the gate now proves the routing, and it is the
fixture worth keeping because its terminal hop arrives on the node key itself, so a reduction reading
the terminal hop instead of the landing would bind it locally against a tuple the row does not have.
The junction case is the second half of that pair and says the same thing from the other side, a chain
that renames nothing and still binds remotely.

Stage 4's first increment drew no reds either, over the same 3762, and the one it *nearly* drew is the
finding. `CommandSeamRatchetTest.leafDispatchSitesInGenerators` went 69 to 70, because reaching the
leaf's compaction from `armSwitchedInlineDataFetcher` needed an `instanceof ChildField.RecordReadField`
that the method next to it already performs. The pin is a deliberate-update pin rather than a
prohibition, so raising it was available; not raising it was better. `inlineSuccessRead` became
`inlineSuccessReturn` and returns the whole `return` statement rather than a value expression, so the
one narrowing that method already made now also decides how the value reaches the wire. A pin doing
what a pin is for.

The `@error` increment drew no reds either, and the two it drew were both a new hierarchy meeting a
gate that exists to catch exactly that: `ErrorFieldRead` needed a kind label in the hierarchy-kind
registry (a resolved view, being a projection over the type's own classified leaves), and the typed
error arm's extra component needed its construction site in the LSP's severity-coverage test updated.
Neither is a behaviour change and both are the guards working.

The defect view's own increment drew one red on the same terms and it is worth naming because it is
the cheapest guard in the tree: `FactCaptureAgreementTest.everyRelationIsRegistered` fails on a
relation with no registered agreement source, so a new view cannot land without a line saying which
arm answers for it. One line, and the point is that nobody has to remember.

One test fixture is worth naming because finding it is what proved the arm. The typed-column read at
an `ID` coordinate is unreachable through a `@service` returning a table record, that binding making
the SDL type a producer carrier and the carrier leaf claiming the coordinate. The shape where the read
arm owns it is a record reached through a *parent accessor*, which the tree already had in
`FilmKeySummary`, a Java record holding a fully populated `FilmRecord`. So the compilation and
execution tiers cost SDL only, on the same fixture whose batch-key contract they already pin.

The `@error` coordinate's fixture is better still, and it was already in the tree.
`FilmLookupInvalidIdException.getAttemptedId()` returns `film_id`'s own type, so the same accessor is
published twice on one `@error` type, once raw as `attempted: Int` and once as
`attemptedFilm: ID @nodeId(typeName: "Film")`. One query then sees both forms of one value, which
fixes what the encode is *of* rather than only that an encode happened, and the existing execution
case grew one assertion instead of a new test. That the tier is needed at all is the same argument as
above: the classified leaf carries an encode either way, and the raw key would have been a legal `ID`
on the wire.

* **Pipeline tier**, carrying the primary behavioural weight. The junction chain lowering to a remote
  binding with a two-hop path, on both the argument and the input-field surfaces. Each of the four
  write rails refusing a remote-bound junction carrier, asserting the text that rail actually
  produces, as cases in `TranslatedFkTargetRailGatesPipelineTest`. The read-family encode at each of
  the four read arms; shipped as `NodeIdReadEncodePipelineTest`, which asserts the three arms
  graphitron's own classification locates as a set with the same negative control beside them, the
  directive's absence leaving the same coordinate a plain read, plus each precondition where its
  operand lives. And the behaviour the invariant is about, stated as a matrix over the two
  preconditions at a single-valued slot, on both a `@service` parameter and a bean member: a
  single-column key whose type matches, which receives the decoded value and never the base64; a
  composite key, which draws the arity refusal; and a matching-arity type disagreement, which draws
  the type refusal naming both types. The authored-`argMapping` and inferred spellings of the
  matching case draw the *same* resolution rather than different ones, which is the claim that the
  two forms are one destination. The partition against R668 is then over arity rather than over
  spelling, and is pinned as such. The bean-member half of that matrix is not a row of it, and the
  sentence is left as drafted because the way it is wrong is worth keeping: it was written before
  stage 2c found that a slot the value lands *inside* wants a walk into the class, so the store
  neither resolves nor refuses there. The matrix is therefore the `@service` parameter's. The bean
  member is pinned instead as what it is, a deferral in the walk, in the rework increment's own class
  below. Shipped as
  `NodeIdDecodeDefectsTest`, and the matrix grew one axis in the writing: each precondition's refusal,
  each precondition's *remedy* drawing no refusal, both readings of a parameter the census cannot
  type, and the domain gate. The remedy cases are the half that keeps this family honest about adding
  refusals only, and there are two because a composite key has a remedy the sibling family's messages
  never had to offer. These fixtures capture both corpora rather than SDL alone, for the reason the
  design section records, and name their own public service stub because the census does not see a
  package-private one. The producer-backed deferral is a sixth class at this tier,
  `NodeIdProducerBackedFieldPipelineTest`: the three arms that drop the directive, and the three
  things the gate must leave alone, which are a producer-backed field carrying no `@nodeId`, a
  coordinate that encodes today, and a coordinate whose own rejection is the more specific one. The
  last of those is the case that would go red if the gate ever ran ahead of a classifier's own
  reflection failure and replaced a precise cause with a vague one. The rework increment adds a
  seventh class, `NodeIdProducerSlotDecodePipelineTest`, and its cases are shaped by what the review
  found: each asserts the schema *builds* and reads the slot's own transform, because a case asserting
  only that some family reported nothing passes equally well against a red build, which is exactly how
  the named-parameter carrier stayed broken behind two green remedy cases. Ten cases, six of them from
the first rework round, three from the second, which found the same defect in the bare spelling of
the directive that the six all wrote explicitly, and one from the third, which found the bare form
still refused where the field's scope comes from `@mutation(table:)` rather than from its return.
The six: the two remedies
  the refusals prescribe, now resolving to the decode their shapes ask for; the parameter typed as the
  wire format, which classifies to the decode and fails on the store's type verdict rather than
  passing base64 through; the parameter no census can type, which still gets its decode on arity
  alone; an argument carrying no `@nodeId`, which keeps its wire-coercion-checked `Direct` read, since
  the stand-aside is keyed on the directive and not on the site; and the bean member, whose deferral
  names both remedies an author has. The three: a bare `@nodeId` inheriting its target from the
  consuming field's own return table and reaching the same decode the explicit spelling reaches; the
  ambiguity over a table two node types share, refused naming both; and a consuming field returning a
  scalar, refused naming the absent table, that being the one absence this coordinate owns rather than
  the shared inference. The tenth is the delete surface, whose scope is the table `@mutation(table:)`
  names, the lower of the two rungs the slot's scope is resolved through.
* **Store tier**, for the two relations whose whole content is a fork. The destination and the
  Java-slot fork are pinned together in one class over the seeded store, because each is defined as
  the population the other does not claim: a case asserting only the destination would pass equally
  well with the fork seeing nothing, and a decode routed to a table predicate when its value goes to a
  parameter is exactly the bug that shape hides. So each case states both, and the fork's rows render
  the root coordinate they were answered at, that being the whole of what makes an input-field row
  correct. This is where the algebra is stated finely: each destination, each precondition, and each
  way of arriving at no destination, including the type disagreement and the composite key that the
  defect view will later name. The defect view then gets its own class beside them,
  `NodeIdDecodeDefectTest`, weighted the way the relation is: two verdicts and seven edges. Every case
  states the destination beside the verdict, because a case asserting a verdict alone would pass
  equally well if the same coordinate had also resolved, which is the one outcome that must not
  happen. The case worth reading is the boundary against R668: it seeds the same type disagreement
  through an `argMapping` entry and asserts no row here *and* the row there, so the exclusion is
  pinned from both sides rather than as an absence on one. Two more carry the reasoning the relation
  needed, the arity verdict at a parameter the census could not type, where the stand-aside rule had
  to be read rather than copied, and the input-field slot, whose absence is the claim that an owed
  capability is not an author error.
* **Capture tier**, one coarse case, for the thing the store tier structurally cannot say. The fork
  spans three families that only a capture fills together, the directive applications, the
  input-occurrence paths and the classpath census, so a fork correct over seeded rows and finding
  nothing on a captured schema would be inert with every store-tier case green.
  `NodeIdDecodeSlotCaptureTest` captures one schema with a hand-built census and reads three
  coordinates naming one node type and one key: the table predicate, the key's single column, and the
  node type's own generated record. Deliberately coarse, and deliberately asserted as one list rather
  than three cases, because the claim is the difference between them. An earlier draft of this item
  asserted here that the sakila schema exercises no fork arm; that was read off a probe that passed
  no census, and the schema does carry the shape, an input type bound directly as a record `@service`
  parameter with a `@nodeId` field on it.
* **Unit tier.** `NodeIdLeafResolverTest` gains the junction-chain case, and
  `multiHopLiftTranslationRejected` is *rewritten* from a rejection assertion to a remote-binding
  assertion rather than deleted, so the fixture that proved the old gate proves the new routing. A
  sibling case pins that an identity-carrying chain still binds locally with its lifted tuple, which
  is the regression this change could plausibly cause and the spike shows it does not. Stage 6 adds
  the search's own three arms in `JooqCatalogIdRefTest`, where two existing cases had been asserting
  the same `isEmpty()` for the two causes and now assert different arms, which is the change stated as
  a test. The `film` to `inventory` directionality case is the one worth reading: it used to say only
  that nothing was found, and now names `inventory_film_id_fkey` as the constraint an author writes,
  so the evidence the direction filter discards is pinned as evidence rather than as an absence. The
  refusals themselves are pinned at the pipeline tier, in `GraphitronSchemaBuilderTest`'s reference
  corpus, because a message an author meets is a property of the built schema and not of the catalog
  helper: the ambiguous case names both candidates, the disconnected case offers the chain, and a new
  case reaches `Customer` from `address` and is answered with the target-side constraint spelled as
  the `@reference` that works.
* **Compilation tier.** Rides `graphitron-sakila-example`. The hope that a junction is already in the
  schema held, so this cost SDL only and no `init.sql` change: three coordinates, the chain on the
  argument and input-field surfaces and the single reverse hop, each carrying its `@reference` because
  a reverse or multi-hop path is never auto-discovered. Stage 4 adds one more, and it is the tier that
  matters most for the read encode: the emitted body is new (the read bound to a local of the key
  column's declared type, the null test, the encode call), and javac is the stated backstop for a read
  the model cannot type, so a coordinate that compiles is the claim. `FilmKeyRow.filmRef` off
  `FilmKeySummary`'s held record renders as a five-line fetcher method and compiles at release 17.
* **Execution tier**, carrying the row-semantics claim. R57's argument for `EXISTS` is that a
  non-unique path multiplies no rows and a NULL foreign key fails the correlation instead of
  duplicating or dropping. A junction table is the shape where that claim is load-bearing and only
  PostgreSQL can check it, so the shipped assertion is a row count through a junction fixture with a
  parent matching two children appearing exactly once, not the generated SQL text. Site 4a's reverse
  filter gets the same pin, which is the verification the Backlog notes flagged as outstanding before
  calling that shape shipped. Both shipped, on `film_actor` rather than `film_category` for the reason
  the note above gives, and joining `TranslatedFkTargetFilterExecutionTest` rather than starting a
  second class: it is already the execution tier for this binding, and the pairing is the point, the
  `xlat` shapes reaching the binding through a foreign key aimed at the wrong unique key and these two
  reaching it through a reach that is non-unique. The reverse hop is `address` filtered by `Customer`,
  where two customers share address 1. Both also pin the correlation's other half, a parent with no
  matching row being dropped rather than returned with nulls, address 4 having no occupant. Stage 4's
  own execution case is a different kind of claim and needs the tier for a different reason: the
  classified leaf carries an encode either way, and the raw key would have been a legal `Int` on the
  wire, so only a running query says which value the consumer got. Three rows over two distinct films,
  which also shows the encode is per row rather than per dispatch.

The spike's rendered SQL was the right evidence for a spike and is the wrong assertion to ship:
code-string matching on generated bodies is banned at every tier. Where an emission claim genuinely has
to be made about a rendered body, R668's Done-gate rework is now the worked shape and this item follows
it rather than re-deciding: `TypeSpecAssertions` grew a projected-key section, so the call site asks a
typed question ("does this method materialise the decoded record once?", "is the column named rather
than indexed?") and the rendered spelling lives in the one file the ban allows. The behavioural half of
each such claim moves to the tier that owns behaviour, which is where that rework put "the decode
precedes the write transaction": a malformed or wrong-type node id surfaces as a request-level error with
a null payload and no committed row.

## Risks

* **A resolution the relations fail to enumerate fails a schema that works.** This is the cost of going
  total and it is the item's main risk. It is bounded rather than open: the tree's existing `@nodeId`
  behaviour suite is the accept set, so a missed resolution is a red test during stage 2 and not a
  shipped false rejection. What the guard cannot cover is a shape no fixture exercises, which
  is why stage 2's exit condition is stated over the suite rather than over a count. The guard also
  runs in one direction only: the shipped `BARE_NODE_ID` cases the arity rule turns from rejections
  into resolutions go red on purpose. The Tests section enumerated them ahead of time and got the set
  wrong in both directions, so what keeps the signal readable is the rule stated there rather than the
  list: a red is expected where the case's subject is the bare arm, where a shared single-key fixture
  was doing undeclared work, or where this item edits the asserted message, and is a missed resolution
  anywhere else.
* **A consumer that decodes by hand today stops compiling, and that is the point.** The invariant is
  that the consumer never sees the wire format, so a `@service` method or an exception constructor
  typed `String` against a `Long` key column no longer receives base64: it draws a type-disagreement
  refusal, and the remedy is one line in the consumer's own signature. That is a breaking change for
  every existing consumer who wrote the hand decode the report is about, which is most of them, and
  the changelog entry has to say so in those words rather than describing it as a fix. This project
  has no warning severity to soften it, a `ValidationError` carrying a `Rejection` and nothing
  weaker, so "tell the author" and "fail the build" are one act. The mitigation available is the
  message: it names the column, the type it binds as, and the type the slot declares, so the fix is
  mechanical wherever it fires.
* **Where the type gate cannot fire, that same consumer changes behaviour silently.** The refusal
  above needs a disagreement to report, and a node type keyed on a text or UUID column agrees with
  the `String` slot a hand decode was written for. That consumer draws nothing: their parameter starts
  receiving the decoded key where it used to receive base64, and their own decode call fails at
  runtime instead. The encode direction is the mirror image, a hand-encoded `String` read being
  encoded a second time. Neither is distinguishable by type, because "a key value that happens to be
  a string" and "a node id" are one Java type, which is the ambiguity this whole item exists to take
  out of the type system and put in the directive. So there is no gate to add here and the mitigation
  is the changelog entry, which has to name this case beside the refusing one: the migration is to
  delete the hand decode or encode, and only a consumer whose key column is numeric gets told so by
  the build. Stating it is what keeps the item's own promise honest, a numeric key column being where
  the reporter's case sits and not the whole population.
* **The site-4b relaxation widens what classifies.** Schemas that fail the build today start
  generating, which is the point, but a schema whose author wrote a junction path expecting the
  rejection now gets an `EXISTS` over a fan-out. R723 is the item that says "this path multiplies" out
  loud, and its rule does not reach here and does not need to; see "Relationship to other items".
* **The write-side diagnostic gets worse before the audit fixes it.** Handled by making the per-rail
  message an exit condition of stage 3 rather than a follow-up.
* **Editing a shipped verdict's text.** The arity rule makes `BARE_NODE_ID` wrong for single-key node
  types, so stage 2 edits its text down to the arity fact rather than leaving two answers to one
  question standing. R668 has since gone Done, which removes the coordination hazard this entry
  originally named (its file is deleted, so there is no open item whose author needs telling) and
  leaves the ordinary one: the verdict is shipped text with shipped tests, so the edit lands with its
  own test rather than as a wording change. The mitigation on the substance is that
  the two rules are the only producers on either side of the partition: R668's remaining population is
  composite keys at authored `argMapping` sites, this item's is composite keys and type disagreements
  everywhere, and the boundary has a test in the Tests section rather than only an argument here. The
  wording itself needs no negotiation, `ArgmappingProjectionDefects.rejectionOf` being text already in
  the tree. As shipped, the wording did need negotiation, and for a reason this entry did not see: the
  old text was one prefix plus three remedies, and the prefix was the claim the arity rule falsifies.
  Three clauses replaced it, one per way of having nothing to infer, because the fact and the remedy
  move together on each. The entry was also right about the substance and wrong about the hazard: the
  boundary that needed a test was not R668's population against this item's but the *walk's* type gate
  against the store's, which is what the shipped increment found.
* **A carrier named for the reporter's subject would inherit the question's shape.** The hazard of an
  item scoped by subject is producing a model type to match: a `NodeIdBinding` or `NodeIdEffective`
  spanning coordinates would take its grain from "whatever the sites needed". The check is the one the
  fact model prescribes: every stage must be able to say what it asserts without naming the reporter
  or this item. Nothing in the design needs such a type, the two relations being named for the
  mechanism, and the review should treat one appearing as a signal the grain slipped.
* **This item lands ahead of R682 on a mechanism R682 will touch.** Deliberate. Doing it narrow means
  someone rewrites a Java sealed result a release later when the planners move to facts. The exposure
  is that stage 2's relation shape has to survive R682's own planner rewrite, which it should, being a
  fact relation rather than a planner.

## User documentation

* `docs/manual/reference/directives/nodeId.adoc` gains the coordinate table this item is really
  about: where `@nodeId` encodes and decodes, what it resolves against, and what happens where it
  cannot. The destination and source vocabularies are the table's own spine, and the page states the
  invariant the table serves, that a consumer neither receives nor supplies the wire format. Its
  Constraints list gains the two preconditions at a single-valued slot, and its `argMapping` bullet
  loses the claim that binding a `@nodeId` leaf without opening it "sends the encoded id to the
  database verbatim", which the arity rule makes false for a single-key node type. The `argMapping`
  bullet landed with the `BARE_NODE_ID` edit; the table, the invariant and the preconditions landed in
  the rework increment, and they needed one section the list did not ask for. The named-parameter
  carrier had no page at all: an author whose producer parameter is named for a `@nodeId` argument had
  nowhere to read what type to declare, and the two build failures they can meet were documented
  nowhere. So the page now carries that coordinate as a worked example with both signatures, both
  failures stated as what the build says and what it asks for, and the untypeable-parameter case, which
  is the one place the build stays silent on purpose.
* `docs/manual/reference/directives/routine.adoc` states that same requirement twice and is the page
  `nodeId.adoc` cross-references as documenting the rule in full, so both statements move with it.
  The `[[node-id-key-projection]]` section's first build error reads "Binding a `@nodeId` without
  opening it. `pOrganisasjonskode: input.organisasjonId` would hand the routine the base64 string";
  the Constraints bullet reads "Binding the leaf itself would send the encoded node id to the
  database verbatim". Both stay true for a composite key and become false at arity 1, so this is the
  same edit as `BARE_NODE_ID`'s text and lands in the same stage. Missing it would leave the
  mechanism's canonical page asserting a rejection the build no longer makes, with `nodeId.adoc`
  pointing the reader at it.
* `docs/manual/how-to/multi-hop-nodeid-filter.adoc` gains the junction shape as a worked example,
  loses its `=== identity-carrying FKs` rejection section, and loses the false single-hop claim. Done
  in stage 3, and it needed one thing the list did not ask for: the page led with the identity-carrying
  property as its subject, so both shapes now hang off one question ("is this key column observable on
  the parent's own row?") and the two worked examples are peers. It also gained the write and
  `@lookupKey` refusals, which an author reaching the remote binding for the first time meets and the
  page had no reason to mention while the gate stood in front of them.
* `docs/manual/how-to/multi-hop-nodeid-filter.adoc`, a third time, in stage 6. The single-hop
  paragraph answers the page's own landing question instead of claiming an exemption from it, which is
  R691 discharged. Two things the list did not ask for: the "two chains reach the `EXISTS`" paragraph
  is now three shapes, the reverse hop having been missing from a page whose subject is exactly which
  shapes bind remotely; and the auto-discovery section says out loud that the search is *directional*,
  with the reverse hop as a worked example and the three refusals summarised by what each one asks the
  author to do. The directionality is also contrasted with the `{table:}` shortcut on
  `join-with-references.adoc`, which resolves a hop from either endpoint, because that asymmetry is
  real and is what made the old single message read as wrong advice rather than as incomplete advice.
* `docs/manual/reference/directives/error.adoc` gains the `@nodeId` extra-field case. Done in
  stage 4, and the worked example is the fixture: one accessor published twice, raw and encoded, which
  is what makes "the encode happens on the way out" a thing the reader can see rather than a claim.
  The page also states the consequence an author most needs, that an accessor already returning an
  encoded id is now a build error, so a hand-written encoder call site goes rather than disagreeing
  silently with the generated one. Its extra-fields section gained the expected type in the
  build-failure sentence for the same reason the message did.

## Retired vocabulary

* `NodeIdLeafResolver.LIFT_FAILURE_MARKER` and the "identity-carrying FKs" rejection text.
* The residual "identity-carrying-lift" phrasing in `NodeIdLeafResolver.resolveFkJoinPath`'s javadoc,
  which describes a gate this item removes.
* `NodeIdLeafResolver.JoinPathResult` and its nullable-slot shape, whose `error` slot carries prose
  (it downgrades typed `Rejection`s to their message, only for `resolve` to re-wrap them as
  `Rejection.structural`). The relation replaces it rather than a sealed result succeeding it.
* The `=== identity-carrying FKs` rejection section of
  `docs/manual/how-to/multi-hop-nodeid-filter.adoc`, and the two further statements of the same gate
  on that page: the intro's "deferred to a sibling Backlog item" and the `== Why identity-carrying`
  section's framing of the property as a requirement.
* Three statements of the old one-conjunct discriminator, in `NodeIdLeafResolver`'s own javadoc. All
  are falsified by a junction chain, whose terminal target-side columns *are* the node's key columns
  and which translates nothing; the remote arm is reached because no own-table tuple exists, not
  because a translation is needed. The three are the `FkTarget` seal's "sealed into two arms on the
  positional-correspondence question between the FK's target-side columns and `T`'s `keyColumns`";
  `TranslatedFk`'s "FK target-side columns differ from `T`'s key columns", which appears twice, in the
  seal's arm list and in the record's own javadoc with its "SQL has to convert a decoded key into an
  FK-column value" gloss; and `TranslatedFk`'s
  `@param joinPath single-hop FK path from the containing table to T.table()`.
* `CallSiteCompaction`'s statement of its own carrier population, "Carried by the column-backed output
  carriers (`ChildField.ColumnBackedField`, `ChildField.ColumnBackedReferenceField`)". The read
  carrier is a third and is not column-backed. The neighbouring sentence about arity goes with it: it
  justifies the arity-1 claim by "the carriers' constructor invariant", and the read carrier is not one
  of those carriers, so the refusal is stated in the encode relation instead.
* `JooqCatalog.findUniqueFkToTable` and its `Optional` contract, along with the rejection text
  "no unique FK from X to Y; declare `@reference(path: [{key: ...}])` to disambiguate" and the two
  test names that read the two causes as one empty (`findUniqueFkToTable_multipleFks_returnsEmpty`,
  `findUniqueFkToTable_noFkToTarget_returnsEmpty`). The directional search survives under
  `findOutgoingFkToTable`; what retires is the presence-shaped answer and the single message.
  Discharged in stage 6.
* The previous draft's own claim that the detection is "capture-total and deliberately ungated by walk
  reach", along with its reasoning over `walk_claim_domain_type` / `walk_claim_domain_field`. The
  use-site grain answers the question those relations were being consulted about, and both relations
  have since been deleted outright rather than re-pointed. Nothing in this item's vocabulary survives
  from that draft, so the sweep has nothing to find here; the entry stands so a reader of the earlier
  draft knows the argument was withdrawn and not merely reworded.

Four of those entries were discharged in the stage-2 increment that converged the resolver on the
decode relation's reduction: `JoinPathResult`, and the three statements of the one-conjunct
discriminator. Two test names carried the same one-conjunct reading and went with the javadoc, the
sweep being about the vocabulary and not about the file it sits in.

Stage 3's first increment discharged the rest of the gate's vocabulary: `LIFT_FAILURE_MARKER` and its
rejection text, the `resolveFkJoinPath` javadoc entry's remaining half, and the how-to page's three
statements of the gate. The page keeps *identity-carrying* as the name of the local-binding shape,
which is what the entry above scoped: what retired is the property stated as a requirement and the
rejection that enforced it, not the word for the shape that still has that property. Two things the
sweep found beyond the enumerated entries: the fixture generator's own comment on `lift_fail_a`
described the fixture by the rejection it drew, and the page's "the runtime touches one table" was
stated of multi-hop chains generally rather than of the identity-carrying ones.

Stage 4's second increment retires one thing the list did not name, and it is a reading rather than a
symbol. `GraphitronType.ErrorType.accessorOverrides` survives as the *authored* fact, which is what
its javadoc now says; what retires is its standing as the per-field read the runtime registration folds
over. That reading is what made an extra field without `@field(name:)` invisible to the fold, and it is
the reason the type could carry no wire direction at all. The Done-gate sweep should read a surviving
`accessorOverrides` as intact, on the same terms as `CONDITION_STEP_MARKER` below.

Stage 4's first increment discharged the `CallSiteCompaction` entry, and the replacement text says
more than the entry asked for. The retired sentence named two carriers as the population; naming the
third (the read carrier) would have restated a census the section above has now corrected twice. So
the slot's javadoc states the two *families* instead, the column-backed carriers that have the whole
tuple in scope and the read carrier that has one value, which is the distinction the arity demand
actually follows from. The neighbouring arity sentence went with it, as the entry said, and the demand
is now stated on the read carrier's own constructor as a backstop and in the encode relation as the
membership condition, the author-facing refusal being the classifier's.

`CONDITION_STEP_MARKER` is deliberately *not* retired here, and neither is the rejection it anchors;
see the emitter argument under Design. The Done-gate sweep should read a surviving
`CONDITION_STEP_MARKER` as intact rather than as a missed retirement.

The arm name `TranslatedFk` outlives its own description: the property that selects it is "binds
remotely" rather than "translates". Renaming it is not in this item, and saying so here is the point.
The javadoc rewrites above state the arm's actual precondition so the name is the only thing left
carrying the old reading.

The retirement sweep has one coordination point beyond the tree.
`roadmap/nodeid-filter-per-participant-paths.md` (R676, Spec) names `LIFT_FAILURE_MARKER` as a
constraint its path grammar inherits, so its author has to be told the constraint moved rather than
disappeared. The `@LoadBearingClassifierCheck` mechanism the original lift work paired with that marker
no longer exists (R237 retired the annotations and their audit wholesale), so there is no annotation
obligation; the surviving structural pin is the decode relation plus the pipeline-tier behaviour, and
stage 3 expresses it there.

## Relationship to other items

* **R682** (`planners-read-facts-emitters-read-commands`, Spec) is the architecture this item works
  inside. Its sentence is that capture writes facts, the walk's sealed leaves dissolve into those
  facts, planners read facts and produce commands, and emitters render commands. Making the `@nodeId`
  encode and decode relations is that sentence applied to one directive, ahead of R682 rather than
  against it. Worth telling that item's author, because the `@nodeId` decode and encode facts are one
  fewer thing its planner rewrite has to source.

  It also inherits one thing this item found it could not do, and the finding is R682's rather than a
  loose end here. This item planned for `NodeIdLeafResolver` to read the decode relation, and the
  resolver runs inside the walk, which runs before capture; the argument is under "The resolver cannot
  be the reader". The consequence for R682 is that the `@nodeId` destination is a fact whose reader has
  to be its planner, and that the walk-side resolver it deletes is by then spelling the relation's own
  reduction rather than a rival one, so the conversion is a lookup replacing a computation that already
  agrees. If R682 ever does move capture ahead of the walk, the argument its planner half rests on
  ("nothing about the pipeline's stage order has to change") is the thing being revisited, and this
  item's stage 2 is one more caller that would benefit.
* **R742** (shipped; see `roadmap/changelog.md`) is why this item states its own derivation depth.
  It measured the precedent this plan follows, `intent_argmapping_projection_defect`, at 2066 relation
  instantiations and 24.5 seconds for one read, and diagnosed the cause as H2 inlining views with no
  common-subexpression elimination over a tree eight levels deep across seventeen views. Two of its
  findings are design constraints here rather than context, both stated under "The derivation depth is
  a design constraint": the deep-relation-named-twice shape that carries half that bill, which is
  exactly how this item joins the windowed `intent_resolved_node_key_column`, and the per-verdict
  `UNION ALL` arms this item's defect view must not reproduce even though R742 prices them as the
  smaller half. R742 also mints the materialization
  registry anything here materializes should register into, and proposes the static multiplicity
  metric as a build gate, which this item's new relations are computable under whether or not that
  gate has landed. A notification in both directions: R742 gains relations to price, and this item
  gains its ceiling.
* **R743** (`sdl-fact-gatherer-staged-pipeline`, Ready, its Done gate having sent it back over a
  failing retirement sweep and a partial census pin rather than over anything it decided) settled the question the previous draft
  of this item argued at length, and has already landed the part that mattered here. Its gatherer now
  owns its assembly stage and closes with a rooted traversal that writes `intent_type_domain`
  (`ClassificationDomainCapture`), and the walk's two membership grains are deleted rather than
  re-pointed, `intent_authored_claim_conflict` having stopped gating on the walk's reach. This item
  reads none of those relations, so nothing here blocks or waits on it. What it does inherit is that
  traversal's own doctrine, stated in its class javadoc and worth quoting because this item applies it
  to the same subject: the node seed is the *declaration* and not the inference, seeding being monotone,
  so "what each member's nodehood amounts to is a question for the readers of the captured node facts,
  one join away". This item is one of those readers.
* **R668** (`nodeid-key-projection-on-routine-params`, Done, see `roadmap/changelog.md`) is the nearest neighbour. It makes a
  node type's key columns nameable as a trailing `argMapping` path segment, which is the
  `SINGLE_KEY_COLUMN` destination above. Its production surfaces are in the tree and this item reads
  them; its Done gate sent it back once over a test rather than a design, and that rework landed
  and was approved by a third session. The finding was that
  `ArgmappingKeyProjectionEmissionPipelineTest` asserted raw generated-method-body strings, the pattern
  `development-principles.adoc` bans at every tier and `TypeSpecAssertions` exists to replace, with no
  production change requested. So the surfaces below are stable to converge on, and the round trip is a
  worked example of the rule this item's own Tests section states, which is why that section now
  names the helper the rework grew rather than re-deciding the question. Two of R668's relations do more
  than neighbour this item: it reads
  `intent_argmapping_key_column_candidate` for a key column's Java type and
  `intent_resolved_node_key_projection` for the erased-type-equality rule, which are the two
  preconditions' operands and not a parallel invention. That relation's stand-aside rule is inherited
  in principle and deliberately *not* in wording, for the reason "Sites 1 and 2" gives: R668 falls back
  to an unchecked projection and this item would be falling back to the pass-through. With that item
  shipped there is nobody to notify, so the divergence is stated in this body and belongs in the new
  relation's own comment when stage 2 writes it, since a later reader comparing the two rules will
  otherwise read an agreement about the principle as a disagreement about it. What has landed:
  the resolution views, the rejection
  family (`intent_argmapping_projection_defect` plus `ArgmappingProjectionDefects`, six verdicts across
  three `Rejection` channels), the carrier move, and the `@routine` emitter with its execution round
  trip. Outstanding is the `@service` emitter, a named empty slot rather than an open question:
  `ArgmappingProjectionDefects.EMITTING_SITES` holds `ROUTINE`, `FIELD_CONDITION` and
  `ARGUMENT_CONDITION`, with its javadoc stating that "`SERVICE` joins when its emitter lands".
  `depends-on:` stays empty; the field means "must ship first" and renders as *blocked by*, and this
  item reads R668's shipped surface rather than waiting on it.

  That delivery also names a coordinate it cannot reach and says why, and the statement is in the
  shipped code rather than in the deleted spec, so it stands as a fact to read:
  `ArgmappingProjectionDefects`' `EMITTING_SITES` javadoc holds the input-field `@condition` out
  because "its pair rows are keyed by the input type and input field, while the condition row rendering
  it is keyed by the consuming output field, so the projection relation's coordinate never matches and
  the lookup misses by construction rather than by omission". Under this item's total rule that
  coordinate becomes this item's to answer, and under the use-site grain above the two coordinates are
  one row, so it is a keying fix rather than an emitter. That is why the census counts it as answerable
  rather than deferred.
* **R57** (Done, see `roadmap/changelog.md`) shipped the single-hop translated-FK `EXISTS` and filed
  multi-hop translated paths as deferred. The junction case is that deferral. Its reasoning that
  `EXISTS` is the semantically right shape rather than a convenient one is the argument stage 3
  inherits.
* **R705** (`condition-join-hops-in-reference-filter-paths`, Spec) is R57's sibling deferral for the
  other rejected hop kind. The two are adjacent in the classifier and far apart in the emitter: both
  gates express themselves through the decode relation, but only the lift gate can relax without the
  reach carrier being widened first, and that widening is R705's own work (retiring `FkHop`,
  `FkHop.narrow` and `ConditionCommands.narrowPath`). R705 does not relax the marker either: its
  targets are the plain-`@reference` filter rejections in
  `FieldBuilder.referenceFilterConditionJoinRejection` and
  `GraphitronSchemaValidator.validateInputColumnBackedReferenceField`, and its non-goals keep an FK
  path required where a `@nodeId` leaf is involved. This item lands first and lands the relation; R705
  inherits it. Worth telling that item's author, because its body cites `NodeIdLeafResolver`'s
  FK-only-at-every-position rule as a standing fact and after stage 2 that rule is stated in a
  different place.
* **R676** (`nodeid-filter-per-participant-paths`, Spec) states that its path grammar inherits "the
  identity-carrying lift validation ... the `NodeIdLeafResolver` arms behind `LIFT_FAILURE_MARKER`".
  Stage 3 removes that gate, so its author needs to know the constraint moved rather than vanished.
  A notification, not a dependency in either direction. Stage 6 adds a second thing to tell that
  author, and it reaches further into their item than the first: both the diagnostic their report
  quotes verbatim and the method their "Why it happens" section names are gone, replaced by
  `findOutgoingFkToTable` and by three messages instead of one. Their reported shape is
  `feide_applikasjon` reaching `miljo` differently per participant, so each participant now gets the
  message for its own cause, and a participant whose foreign key is declared on `miljo` is told which
  constraint to name. That does not solve their item, whose subject is that one stated path cannot
  describe three differently-keyed tables, but it does change what an author sees before reaching
  that wall, and the quoted line in their body is now historical rather than current.
* **R723** (`reference-path-fanout-verdict`, Spec) is the item that warns when a `@reference` path fans
  out, and it gains something here. Its own scope section names authoring sibling views over
  `graphitron_argument_reference_step` as the prerequisite for covering argument-site paths, and
  declines it; stage 1 authors them. In the other direction R723's rule does not reach this item's new
  population and does not need to: the defect it names is duplicate rows in a *projection*, and a
  filter path lowers to a correlated `EXISTS`, which is the shape R57 argued does not multiply rows.
  What R723 keeps is a documentation obligation it already accepted, that a quiet build is not a
  statement about filter paths.
* **R691** (`multi-hop-nodeid-filter-single-fk-claim`, Backlog) is why site 4a reads as unsupported.
  The manual still tells the reader that a single direct foreign key never produces a subquery and that
  the translated emission "is not yet shipping", both of which R57 made false. **Absorbed**: this item
  edits that page in two stages already, so the correction rides along and R691 is discarded at the
  Done gate rather than sending a third pass over one file. Its `status: Backlog` file is a tombstone
  in the meantime. Discharged in stage 6, and its diagnosis was right about the paragraph while the
  fix turned out to be smaller than "name the two single-hop shapes": stage 3a had already made the
  landing question the page's subject, so the paragraph only had to answer that question for the
  single-hop case instead of asserting an exemption from it. Nothing was left for R691 to stand again
  on, so its tombstone deletes at the Done gate as written.
* **R731** (`resolved-key-column-forwards-a-spelling`, Backlog) is the weakness under this item's type
  precondition, and naming it here is how this item avoids pretending otherwise.
  `intent_resolved_node_key_column` answers with the winning tier's *spelling*, not a resolved column,
  so every reader that has to match against it folds case at the crossing. This item's type refusal
  reads a Java type reached through exactly that crossing. Not a dependency, and not a blocker: the fold
  is correct today and R668's candidate relation already performs it on both sides. What R731 would
  change is the payload, so the type comes off a column rather than a name.
* **R724** (`stated-key-column-match-states-its-arity`, Ready) is the machinery R731 names, and it
  matters here for one reason. Its subject is `intent_node_metadata_defect`'s `KEY_COLUMN_UNRESOLVED`
  arm spending an ambiguity silently: on a table with two columns differing only by case, which one
  resolved the entry is decided by whichever the join reached. A type refusal minted by this item on a
  silently-picked column would name a type the author cannot check. R724 lands the arity, so if it goes
  first this item refuses on a column decided rather than picked; if it goes second, nothing here is
  wrong, the ambiguity being upstream of the operand rather than introduced by it. A notification in
  R724's direction: this item adds a consumer that turns that pick into a build failure's text.
* **R262** (Done) rejects `@nodeId` on a non-`ID` coordinate at validate time: the precedent for the
  rejection half, and the reason its vocabulary is already established. This item extends the same
  judgement from the slot's *type* to what the slot can *hold*.
* **R673** (`nodeid-arg-dispatches-on-typeid`) landed its implementation while this item is in
  flight, and gives the population a named consumer plus the fixtures its store-tier suite lacks.
  Two facts for this item's implementer. First, the multitable coordinate produces no
  `intent_node_id_instruction` row on either bare-inference arm (the arms reach the slot's table
  through `intent_argument_scope_table`, which demands an unambiguous binding, and a multitable
  return type has none), so R673 computes the cross-participant verdict on the Java side, in
  `FieldBuilder`, behind a single producer whose signature and sealed verdict do not depend on
  whether the inputs are computed or read: if the participant-keyed arm ever lands, one call site
  repoints. Second, `NodeIdInstructionTest` still carries no interface or union case, and R673
  deliberately did not add one, because pinning today's silence would freeze a consequence of
  `intent_argument_scope_table`'s certainty demand as though it were a decision. The sakila
  fixtures R673 added (`Query.occupantById`, `occupantsByIds`, `occupantByOptionalId`,
  `occupantsByIdsConnection` over `AddressOccupant = Customer | Staff`, with `Staff` now
  `@node`-backed) are the shape that store-tier case wants once the population question is settled.

## Reviewer findings

In Review → Done, reviewed at `b709d02` rebased onto trunk. `mvn install -Plocal-db` is green
(BUILD SUCCESS, zero test failures), no delivered test asserts a code string against a generated
method body, and the retirement sweep is clean apart from one comment noted at the end. The
relations are delivered, documented at unusual depth, and the measurement history is honest about
what it took back. What sends this back is behaviour, not design: the relations were built to make
one thing happen at a consumer, and at the two coordinates this item added it does not happen.

Each finding names what would satisfy it. The probes below are reproducible against fixtures
already in the tree.

### 1. At the `NAMED_PARAMETER` carrier, no schema builds and no decode is emitted

The carrier this item newly judges is a plain `@service` method parameter matched by name to a
`@nodeId` argument. Three spellings of it, against `PublicNodeIdServiceStub` on the
`films(key: ID! @nodeId(typeName: ...))` shape `NodeIdDecodeDefectsTest` already uses:

* `getFilmsByIntegerKey(Integer key)`, which is the schema the type refusal tells the author to
  write and which `aParameterOfTheKeyColumnsOwnTypeIsNoDefect` documents as "the schema an author
  writes after reading either message above", does not build. It draws
  `WireCoercionError.Assignability`, whose message asks for a `String` parameter or for the value
  to be routed "through a converting scalar / `@nodeId` decode" on a schema that wrote the decode.
* `getFilmsByInventoryKey(InventoryRecord key)` at the composite node type, which is the first
  remedy the arity refusal offers ("declare 'key' as the generated record of that node type's own
  table"), draws the same `Assignability` rejection.
* `getFilmsByStringKey(String key)` classifies successfully, with
  `leafTransform = CallSiteExtraction.Direct`. The wire string still reaches the parameter; only
  the new store detection fails the build.

So both remedies the two new messages prescribe are unbuildable, and the decode is emitted at none
of the three. Stage 2's exit condition, "a `@service` method whose parameter type matches a
single-column node key receives the decoded value and never the base64", is not met, and neither is
the reporter's own remedy as this body states it, "one line of Java in their own signature and no
SDL change at all".

The cause is placement. The `@nodeId` stand-aside `ServiceCatalog.pathLeafDeclaresNodeId` states,
in a javadoc that gets the argument exactly right ("it rejects exactly the binding the decode
exists to make work"), is read from `RoutineDirectiveResolver` only. The `@service` argument gate,
`ServiceCatalog.argExtraction` through `WireCoercionResolver.checkScalar`, has no equivalent. The
paragraph under "The rejection this increment had to remove was not the one the plan named" excuses
that deferral, but it reasons about a resolved projection at an unwired `@service` site drawing
`ArgmappingProjectionDefects`' `EMITTING_SITES` deferral, which is the `MAPPED_PARAMETER` carrier.
The `NAMED_PARAMETER` carrier did not exist when that paragraph was written and its conclusion does
not carry to it: here the gate's extra error is the only verdict at the remedy, so it changes the
author's outcome from "builds" to "cannot".

What would satisfy it: the stand-aside at the `@service` argument gate, the emitted decode at the
carrier, and a pipeline case asserting the build *completes* and the parameter receives the key
column's own value. That last one is the case shape this item already added at `@routine`, for the
reason it states there and which applies verbatim here: silence at the detection and a red build
are indistinguishable at every tier below that one. `NodeIdDecodeDefectsTest`'s two remedy cases
assert only that this family is silent, so they pass against a red build and could not have caught
this.

**Author's response.** All three, and the finding was right about the cause. The stand-aside now sits
in `ServiceCatalog.bindServiceMethod`, reading the same `pathLeafDeclaresNodeId` the `@routine` gate
reads, and it mints the decode rather than merely declining to reject: a record-typed slot gets
`NodeIdDecodeRecord` and every other slot gets `ThrowOnMismatch`, which is the fact model's own fork on
the fact model's own operand. The emitters follow at both coordinates, the root's through
`ServiceMethodCallEmitter` and the child's through `ArgCallEmitter`, where the decode arms had been
invariant throws. The paragraph that excused the deferral is corrected rather than deleted: its
argument was sound for the mapped carrier it was reasoning about and does not carry to this one, and
saying which is what stops the same excuse being reached for again. `NodeIdProducerSlotDecodePipelineTest`
carries the build-completes cases, six from this round and three more once the second round found the
same fall-through under the bare spelling, and the finding's point about the two remedy cases is
recorded in the Tests section as the reason that class asserts what it asserts. The rework increment
paragraph under Stages states the whole of what shipped.

### 2. Site 2 is undelivered and silent, and the body states both readings

`title: ID @nodeId(typeName: "Film")` on an input type backing
`TestServiceStub.runWithInputBean(TestInputBean)` classifies to `CallSiteExtraction.Direct` with no
rejection at any tier. `InputBeanResolver`'s scalar branch reaches `Direct` without consulting
`@nodeId` at all, and `intent_node_id_decode_defect` restricts to `site = 'ARGUMENT'`, so neither
the walk nor the store says anything. `TestInputBean.title` is `String` and `film_id` binds as
`Integer`, so this is the disagreement the sibling coordinate refuses, passed through in silence.

That is the field report's second coordinate, in the state the report found it, and it is what the
title forbids: the instruction is dropped and the build says nothing. The body asserts both
readings of it. "Sites 1 and 2" says "this item supplies the slot's own type at two new
coordinates, the `@service` parameter's and the bean member's, and the existing predicate decides";
the Tests section says "the bean member's row is a stated gap rather than an assertion waiting to
be written". Both cannot stand.

What would satisfy it: either the arm, or a refusal at the coordinate. A deferral is available and
this item established the pattern for it one stage later: `FieldBuilder.producerBackedNodeIdDeferral`
is an owed emitter stated as `Rejection.deferred` at a placement gate, on the argument that what is
missing is an emitter and not the author's understanding. The same argument holds here, and it is
why "a verdict there would have quietly turned an owed capability into an author error" does not
settle the question: a deferral is not an author error. Either way one of the two readings above
has to leave the body.

**Author's response.** A refusal, and the body keeps one reading. The bean member is now a
`Rejection.deferred` at `InputBeanResolver.bindField`'s scalar branch, naming both remedies an author
has: declare the member as the node type's own generated record, which takes the whole tuple today, or
take the id at the producer's own parameter, which the same increment made work. Picking the deferral
over the arm was not only effort: the store had already reached it from the other side, its
input-field exclusion calling this shape "owed an emitter rather than a verdict", and a deferral is how
the walk says that out loud. The "Sites 1 and 2" sentence now claims one new coordinate rather than
two, a new paragraph beside it says where the two coordinates part company and why only one ends this
item with an emitter, and the Tests sentence says the bean member is pinned as a deferral rather than
as a gap. The silence the finding names is gone either way, which was the part that mattered.

### 3. `nodeId.adoc`'s coordinate table was not written, and the new behaviour is undocumented

The first User-documentation bullet is the page this item is about gaining "the coordinate table
this item is really about", with the destination and source vocabularies as its spine, the invariant
stated, and the Constraints list gaining the two preconditions at a single-valued slot. The page has
one table, the pre-existing Parameters one. It does not state the invariant. Its Constraints list
carries the two preconditions for the `argMapping` carrier only, in the bullet that was correctly
edited. Every other bullet in that section carries a "Done in stage N" note; this one carries none,
which reads as an omission rather than a decision, this body being scrupulous elsewhere about
recording what it took back.

Nothing in the manual documents the `NAMED_PARAMETER` decode or the two new build failures. Both
Risks entries turn on an author meeting them: the refusing case is called "a breaking change for
every existing consumer who wrote the hand decode the report is about, which is most of them", and
the silent case has "the changelog entry" as its whole mitigation. An author who meets
`KEY_COLUMN_TYPE_DISAGREEMENT` has no page to land on. (The diagnostics glossary carries no verdict
of the sibling family either, so that surface is consistent with the house convention and is not
part of this finding.)

**Author's response.** Written. `nodeId.adoc` gains a `Where the ID resolves` section stating the
invariant in one sentence and then two tables, encode and decode, whose spines are the source and
destination vocabularies. The Constraints list gains the two preconditions at a single-valued slot as
one bullet, since the build states whichever one fails and an author meets them as one question about
their own signature. The finding's second half turned out to be the larger gap: the named-parameter
carrier had no page at all, so the page now carries it as a worked example with both signatures, both
build failures in the terms the build states them, and the untypeable-parameter case where the build
stays silent on purpose. The missing "Done in stage N" note is the User-documentation bullet's own
correction.

### Non-blocking

* `NodeIdLeafResolverTest:500` still comments on `permutationToKeyColumns`, retired in stage 2e. The
  sweep is about the vocabulary and not the file it sits in, as this body says of two test names it
  did catch.
* The Stages section is not collapsed to one-line `shipped at <sha>` notes and no SHA appears
  anywhere in the body; stages 1 and 2 carry no shipped note at all. Worth doing on the next pass
  regardless of the findings above, since it is what makes remaining work readable.

**Author's response.** Both done. The comment at `NodeIdLeafResolverTest:500` now states the ordering
without naming the retired step. Every stage carries a one-line shipped-at note with its SHAs, and
stage 2's sub-increment paragraph now points at that list rather than re-narrating the order; the
bolded paragraphs below the list stay, being findings rather than stage narration.

## Reviewer findings, round two

Re-reviewed at `6737ae4` rebased onto trunk. `mvn install -Plocal-db` is green, zero test failures.
All three findings above are fixed, and verified independently rather than read off the responses:
at the named-parameter carrier `Integer key` now yields `ThrowOnMismatch`, `InventoryRecord key` at
the composite type yields `NodeIdDecodeRecord`, `String key` yields the decode rather than
`Direct`, and the untypeable primitive yields the decode on arity alone; the bean member is
`kind=DEFERRED` with both remedies in the message; `nodeId.adoc` carries the invariant, both
coordinate tables and the producer-parameter section. The stale comment is gone and every stage
carries its shipped-at SHAs. `NodeIdProducerSlotDecodePipelineTest`'s non-regression case, that an
argument without the directive keeps its wire-coercion-checked `Direct`, is the case that keeps the
stand-aside honest and was not asked for.

One finding, and it is the round-one defect surviving in a second spelling of the same directive at
the same carrier.

### 4. The bare `@nodeId` spelling still has the two-sided refusal, and the walk and the store disagree

`nodeIdSlotExtraction` returns `null` when the directive names no `typeName:`, so a bare `@nodeId`
never reaches the new arm and falls back to the type gate. Measured on
`films(key: ID! @nodeId): [Film!]!` over `PublicNodeIdServiceStub`, `Film` being the only node type
the field returns:

* `getFilmsByStringKey(String key)` classifies to `CallSiteExtraction.Direct`.
* `getFilmsByIntegerKey(Integer key)` draws `WireCoercionError.Assignability` as an `AUTHOR_ERROR`,
  the same message asking the author to route the value "through a converting scalar / `@nodeId`
  decode" on a schema that wrote it.

The store, meanwhile, treats the bare form exactly like the explicit one. Captured against the jOOQ
catalog and a census, the bare schema yields an `intent_node_id_instruction` row at
`Query.films.key` with `node_type_name = Film`, an `intent_node_id_decode_slot` row with
`carrier = NAMED_PARAMETER` and `java_type = java.lang.String`, and one
`intent_node_id_decode_defect` row, `KEY_COLUMN_TYPE_DISAGREEMENT` naming `film_id`,
`java.lang.Integer` and `java.lang.String`, which `NodeIdDecodeDefects` projects as one violation.
Both spellings produce identical store rows.

So the author is between two refusals again: the store's verdict says declare `Integer`, and the
walk then refuses `Integer`. No schema at this coordinate builds, which is the state finding 1
named. The population section makes this the item's own business rather than a neighbour's, the
instruction having three forms and the bare one being the second, and "an instruction the population
misses is a coordinate that stays silent" being why it says so.

The comment covering the fall-through is the thing to correct either way:
`// a bare @nodeId names no target here; the argMapping family judges it`. At this carrier no
`argMapping` pair exists, which is the carrier's definition and what the increment's own
`authoredTargets` guard enforces, so that family cannot judge it. `intent_node_id_decode_defect`
does, as above.

What would satisfy it: the walk resolving the bare form's node type at this carrier as the store
already does, so the two spell one rule; or, if bare at this carrier is meant to be unsupported, a
refusal that says so, in place of a message prescribing the decode the schema already wrote. Either
way one case in `NodeIdProducerSlotDecodePipelineTest`, whose six cases all name `typeName:`
explicitly, which is why nothing there could have caught this.

**Author's response.** Taken as reported, and taken the first way: the walk now resolves the bare
form's target as the store does, so the two spell one rule rather than two. `nodeIdSlotExtraction`
no longer returns `null` on a directive naming no `typeName:`; it infers, from the table the
consuming field's own return type binds, then the one node type over that table. That is the store's
`TARGET_TABLE_NODE_TYPE` basis arrived at from the other direction, an argument's predicate binding
on the table its field returns being what makes `intent_argument_scope_table` answer with that same
table, so the agreement is by construction and not by comment.

The inference rule now lives in one place rather than two. `BuildContext.inferNodeTypeOverTable` is
where "which node backs this table" is answered and where the two absences that answer it are
worded, and `NodeIdLeafResolver.inferTypeName` reads it rather than carrying its own copy. Both
callers differ only in how they arrive at the table, which is what one rule two coordinates share
has to look like if it is not to drift.

A third absence belongs to this coordinate rather than to the inference, and it is the reviewer's
second remedy applied where the first cannot reach: a consuming field whose return type binds no
table has nothing to inherit from, and that is refused, naming the return type and `typeName:` as
the fix. Falling through there is exactly what the finding objected to, the gate below answering a
written directive with a message prescribing the decode already written. The store is silent at that
shape, having no scope table and so no instruction row, so the walk is the stricter of the two by
one case; strictness in the direction of a written directive not being dropped is the direction the
population section argues for.

*Corrected in round three, and left standing rather than rewritten so the correction has something to
point at.* The store is not silent at that shape. Its scope relation has a second rung, and where a
delete surface names its table the store resolves what this paragraph says it cannot see. The walk
now reads both rungs; round three's response below has the whole of it.

The fall-through comment is gone with the fall-through. What stands in its place says why both
spellings resolve here, which is the fact a reader needs at that line.

Three cases in `NodeIdProducerSlotDecodePipelineTest`, and the class javadoc now says the bare form
is a subject rather than leaving nine cases to imply it: the bare directive inheriting `Film` from
the return table and reaching `decodeFilm` on `film_id`; the ambiguity over a table two node types
share, refused naming both and `typeName:`; and the scalar-returning field, refused naming the
absent table. `PublicNodeIdServiceStub` grew the one signature the third needs, a producer whose own
return type binds nothing.

Verified at the tier the finding was measured at, plus the reactor: `NodeIdProducerSlotDecodePipelineTest`
9 cases, `NodeIdPipelineTest` 82, `NodeIdLeafResolverTest` 12, `ServiceCatalogTest` 49 and
`NodeIdDecodeDefectsTest` 7 all green, and `mvn install -Plocal-db` green across all 14 modules,
6,288 tests with no failures, the compilation and execution tiers and the docs render included.
The manual's producer-parameter section carries the bare spelling with its example and its two
refusals, inference rule (b) in the argument table now names this coordinate, and the
`typeName:`-is-required constraint says what "neither rule fires" means here.

## Reviewer findings, round three

Round two's finding is fixed, and verified independently rather than read off the response. The bare
spelling at the named-parameter carrier now reaches the decode: `films(key: ID! @nodeId): [Film!]!`
over `getFilmsByIntegerKey` classifies to the decode on `film_id`, the ambiguity over a shared table
is refused naming both node types, and the scalar-returning field is refused naming the absent table.
The inference lives in one place with one wording, `NodeIdLeafResolver` reads it, and the
fall-through comment the last round objected to is gone. Round one's three findings remain fixed.

One finding, and it is the same shape as the last one at a narrower coordinate.

### 5. The walk shares one of the store's two scope rungs, and the spec says it shares both

`inferNodeTypeAtSlot` arrives at the table from `unwrapAll(fieldDef.getType())` through
`tableNameForTypeName`, which is the store's `NAMED_TYPE_TABLE` rung. `intent_argument_scope_table`
has a second rung beneath it, `MUTATION_TABLE`, which is what answers where the field returns a
payload type nothing binds: that relation's own comment says a delete surface returns a scalar or a
status type and its arguments still bind against the table the mutation names. The walk does not read
that rung, so the shape it answers is the shape the store answers with its lower one.

Measured on a service-backed delete, the canonical surface the rung exists for:

```graphql
type Mutation {
    deleteFilm(key: ID! @nodeId): String
        @mutation(typeName: DELETE, table: "film")
        @service(service: {className: "...", method: "..."})
}
```

The walk refuses the field: `@nodeId without typeName: cannot infer node type, the field's return
type 'String' binds no table to inherit a target from. Add typeName: explicitly.` The store, captured
from the same SDL, resolves it: `intent_argument_scope_table` carries one row for that argument with
`basis = MUTATION_TABLE` and `table_name = film`, `intent_node_id_instruction` carries the argument
with `basis = TARGET_TABLE_NODE_TYPE` and `node_type_name = Film`, and the defect view mints a verdict
whose prose quotes `@nodeId(typeName: "Film")` back at an author who wrote no `typeName:` at all. The
explicit spelling of the same coordinate classifies, so the coordinate is supported and it is the bare
form alone that is refused.

So the sentence stating this absence is the wrong way round. "The store is silent at that shape,
having no scope table and so no instruction row, so the walk is the stricter of the two by one case"
is measurably false where the mutation names its table: the store has both, and the walk is not
stricter but differently based. The claim also stands in main sources, where it outlives this
document: `BuildContext.inferNodeTypeOverTable`'s javadoc says the callers "differ only in how they
arrive at the table, which is what keeps the walk and the store one rule rather than two", and
`inferNodeTypeAtSlot`'s says the two "arrive at the same table from the two directions". A comment
asserting an invariant a reachable schema contradicts is worse than no comment, because the next
contributor reads it and does not go looking for the rung.

The distance from round two's finding is real and worth stating: nothing is silently handed the wire
format here, the build fails, and the message an author meets names a fix that works. That is the
item's own invariant holding. What does not hold is the rule the two coordinates are said to share,
at a shape the fact model documents as the reason its lower rung exists.

What would satisfy this. Either the walk reads the second rung, which is one lookup off the `fieldDef`
already in hand, `MutationInputResolver` reading `@mutation`'s `table` argument off that same field
today; or the narrowing is declared, in the spec and in both javadocs, as the one rung the walk shares
with the store and the one it does not yet, with the owed rung recorded rather than described as a
silence on the store's side. Either way one case at the tier this was measured at, and the manual's
inference rule (b) and `typeName:`-is-required constraint saying which of the two a delete surface
falls under, since as written they tell an author the return table is the whole rule.

For the record, one shape I checked and am not raising: a connection-returning field, where the store
reads the authored element type through `graphitron_field_synthesis` and the walk would read the
expanded wrapper. `@service` at the root refuses Connection return types outright, bare and explicit
alike, so the two cannot disagree there.

**Author's response.** Taken as reported, and taken the first way: the walk reads the second rung. It
is the one lookup the finding said it was, `MutationInputResolver.parseMutationTableArg` off the
`fieldDef` already in hand, and it sits beneath the return-table rung rather than beside it, which is
the precedence `intent_argument_scope_table` ranks the two with. So a bare `@nodeId` on a
service-backed delete now resolves `Film` from `@mutation(table: "film")` and reaches `decodeFilm` on
`film_id`, where it met a refusal before.

The false claim is corrected in all three places it stood, and the two in main sources are the ones
that mattered: they outlive this document and would have told the next contributor there was no rung
to look for. `BuildContext.inferNodeTypeOverTable`'s javadoc now says the walk arrives from the slot's
own scope, naming both rungs; `inferNodeTypeAtSlot`'s lists them in the order the relation ranks them
and says what each answers. The Stages paragraph that asserted the store's silence is rewritten. Round
two's response paragraph is left standing with a correction marker on it rather than edited away: a
response that quietly loses the sentence a reviewer measured false leaves the next reader unable to
tell what was fixed.

The third absence survives, narrowed to what it actually is: neither rung answering, rather than a
return type binding no table. Its message says so, naming both the return type and
`@mutation(table:)`, so an author who wrote a delete surface and misspelled the table is told which of
the two ways in they missed rather than being pointed only at the one that does not apply to them.

One case at the tier the finding was measured at, `aBareDirectiveOnADeleteSurfaceInheritsTheTableTheMutationNames`,
reading the same coordinate the finding measured. `PublicNodeIdServiceStub` grew the delete surface's
producer, and the absent-table case now asserts the widened message. The manual's inference rule (b)
and its `typeName:`-is-required constraint both name the second rung, and the producer-parameter
section carries the delete surface beside the query in its example, since as written they told an
author the return table was the whole rule.

The connection-returning shape the reviewer checked and did not raise is recorded here as checked:
`@service` refusing Connection return types outright is what keeps the two from disagreeing, and it is
a fact about the service gate rather than about this inference, so nothing here depends on it holding.

Verified with `mvn install -Plocal-db`: green across all 14 modules, 6,289 tests with no failures, the
compilation and execution tiers and the docs render included.
