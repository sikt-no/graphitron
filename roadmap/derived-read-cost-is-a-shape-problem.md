---
id: R876
title: "Expensive derived reads are a modelling defect: every rule needs an owner, and once ownership is computed the derivation gatherer is unearned and meta_materialize has no subject"
status: In Progress
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-28
last-updated: 2026-09-04
---

# Expensive derived reads are a modelling defect: every rule needs an owner, and once ownership is computed the derivation gatherer is unearned and meta_materialize has no subject

Every performance decision the fact model has taken has asked one question: should this rule be
stored or recomputed. That framing admits two answers, and `meta_materialize` is the record of
choosing "stored" twenty-two times. It never asks the prior question, which is why one evaluation of
the rule is expensive at all. On every relation measured against a real consumer schema, the answer
is almost never that the rule is inherently costly. It is a relation the capture family never wrote,
because capture modelled the kinds of a thing and never the thing, so readers reconstruct it from
multi-arm unions that H2 expands once per path through the dependency graph; or a join key that
exists only as an expression, which no index can serve. Both are defects in the model rather than in
any query, both are fixable at the source for milliseconds, and where they are fixed a registration
has nothing left to buy. That last claim is established at one relation by a controlled comparison
and is what the rest of this item has to establish at the others; the honest status against it is in
"What changes when this lands" rather than here.

This item takes over the performance narrative from nine dissolved items and states the lever
ordering the fact model should have been using. The evidence is in
`roadmap/audits/2026-08-28-derived-read-cost-premise.md`, which is filed as an audit precisely so it
outlives this file.

## What changes when this lands

**Every rule gets an owner, and `meta_materialize` dissolves.** That is what this item is about. A
registration is not a thing to be justified or retired one at a time; it is what a rule with no owner
gets given, so that something somewhere refreshes it. Give every rule an owner and there is nothing
left for a register to schedule. A rule reading one family's facts moves into that family, where its
owner keeps it by whatever means and no register hears about it. A rule crossing families has an
owner too, the gatherer that runs last, and that gatherer's refresh plan is not a register: it is the
same thing every other gatherer holds for its own family. So the register does not shrink to a
defensible core and does not get retired row by row. It stops being a mechanism, by necessity, the
moment ownership is total.

**And the crossing rules do not keep it alive either, because the gatherer they would wait for is not
earned.** Walking every `intent_` rule down
to the base relations it bottoms out at reaches nothing the graphitron gatherer lacks. No rule in the
family reads the configuration corpus, Java sources or the compiler, which are three of the six
dependencies the derivation gatherer declares. That gatherer was created to run last and nothing it
owns needed it to, so `intent_` collapses into `graphitron` and the two families become one owner's
relations, differing only in whether the rows were written by Java or stated in SQL. `meta_materialize`
then loses its subject rather than its justification: it schedules refreshes for relations whose owner
runs too late to schedule them itself, and under one derivation gatherer there is no such relation.
The counts are under "What the evidence is" and the target under "What is not done".

**The register is a diagnostic, not the deliverable.** Every defect this item fixed was found by
asking why a relation had been registered, and not one of the fixes was a retirement. What ships is
in "What landed" below: a fact model that states more about itself, and a capture that can read what
it has written.

**The evidence the diagnosis rests on is a coincidence counted two ways.** When this item opened,
every registered target was a relation with no primary key, and every relation with no primary key
was a registered target. Twenty and twenty. A relation with no key has not said what one of its rows
is about, which is what makes it unindexable, which is what left materialization as the only lever
anybody could reach. So the question the item had been asking each registration, whether it is
necessary, was asking too late: a registration is what a relation with no grain gets given.

**The target this item was pointed at is not reachable, and this item is where that got measured.**
The target, R899's as filed, is a test: no relation in the consumer read set refuses a five-second
budget with nothing materialized.
On a fresh capture the unregistered arm does not finish. Sixteen relations pass a 120-second budget
and the planner then exhausts its heap, against a registered arm that reads
all 114 relations in 88.2 s with none over budget. The gap widened in both directions while this item
ran, because the registered arm improved so much: 43.0 s of refresh became 7.6 s and 251.5 s of reads
became 88.2 s.

**The diagnosis rests on the shape of the difference rather than on the total, and that survived.**
The one relation that refused a budget with every registration in place is fixed by a stored key and
an index, which no registration could have done, because no registration can index an expression. On
the fresh capture the worst reader remaining is fixed the same way, by restating its rule rather than
by registering it, with the register untouched and every row unchanged. "The lever order" carries the
figures.

**So what this item hands on is not a smaller register but the end of one.** A materialization nobody
owns is a materialization nobody maintains, which is how twenty of them came to stand over rules
whose shape had never been priced, several over rules returning nothing at all on a real consumer.
What is misplaced is not which rules were registered but where they were put: a family-local rule
sitting outside its family, carrying the largest read cost in the store. The order of that work is in
"What is not done".

**The refresh is not the cost and this item does not claim it.** The pass was 43 s when this item
opened and is 7.6 s now.

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

A **grain** is the unit of fact a relation states one row of.

A **subtype set** is a closed group of captured tables that state the same fact about different kinds
of site, carrying the same attributes and differing only in the key that says which site owns the row.
The eight `*_arg_mapping_pair` tables are one. Its **supertype** is the relation that states the fact
once, with a **discriminator** column saying which kind of site a row came from and a uniform key. A
supertype is **missing** when no captured table states it, at which point readers reconstruct it by
`UNION`, once per reader. The first draft called this a missing grain and argued it from cost; the
vocabulary here is deliberate, because a missing supertype is a defect in the model whether or not any
reader is currently slow, and that is what makes the set of them enumerable.

## The diagnosis

**Capture writes the subtypes and omits the supertype.** Where one fact is written at several kinds
of site, the capture family writes one table per site kind and no table for the fact. Every reader
that wants the fact reconstructs it by union, and H2 expands that union once per path through the
dependency graph above it. The schema already contained a confession: a view existed whose whole body
was a union of three sibling tables, which is a supertype written as a query.

**The signature is mechanical and needs no captured store**, which is why it became a gate rather
than a census. Subtype sets come from grouping capture tables by attributes outside their own primary
key; confirmations come from view bodies unioning three or more members of a set; and the set's own
attributes must be named by that view. The third part is not a refinement to add later. Without it
the scan credits a view unioning three tables for `class_name` with reconstructing every other
attribute those tables share, and a gate carrying that fault fires on supertypes the view does not
reconstruct.

**The other defect is a join key that exists only as an expression.** No index can serve one, so no
registration can help: materializing a relation whose join key is computed per row moves the
computation, it does not remove it. That is the case where the lever order below is not a preference
but the only thing that works.

**The third defect is a relation modelled at the wrong grain, and it is the dearest of the three.**
An omitted supertype or an expression key makes a correct rule slow. A grain that fuses two facts
makes the rule wrong, and the wrongness surfaces as cost because a reader that has to undo the fusion
cannot be cheap. Three measured cases, each found by asking why a relation had been registered.

`intent_resolved_type_binding` keys on the type and counts candidates over the union of two different
facts: a `@table` spelling that resolved, and a table a `@routine` chain's return landed on. Those
are not competing answers to one question. A type can be table bound and result bound at once, and it
can be result bound by several fields whose chains land differently, so the count turns true facts
into an ambiguity and the `candidates = 1` guard discards them. Captured against the test catalog, a
`Row` carrying `@table(name: "film")` and also returned by a routine gives `graphitron_tabletype` the
row `Row -> film` and gives every guarded reader nothing at all: an authored, unambiguous binding
annihilated by the presence of a routine elsewhere. Eleven view sites read that relation and eight
spell the guard, so the loss is silent at eight and the other three take the ambiguous rows instead.
(An older figure on `graphitron_tabletype` says eleven spell it and four forget; this count is
measured over the shipped DDL today and is the one to trust.)

`intent_argument_scope_table` is `intent_field_scope_table` crossed with the field's arguments and
nothing else. No argument predicate appears in the rule, so the argument cannot enter the answer, and
all nine read sites hold the field already. It is a registered materialization of a fan-out that adds
nothing.

And the written order of a field's directive applications, which the manual states is load-bearing,
is not captured at all. Ordinals are per directive name, so interleaving `@reference` with `@routine`
can only be recovered by comparing source line and column, which is what `intent_field_chain_node`
does. Measured on the manual's own sandwich example, that walk reports a two-node chain where the
manual describes four: the hop written before the routine is not in it. The terminus it exists to
compute is still right, which is why nothing has ever failed.

The pattern across the three is one claim, and it is the item's thesis stated from the capture side
rather than the register side: a materialization is usually the price of a fact nobody captured.

**What the register costs beyond its refresh.** A materialized target is a table with statistics of
its own, so every planner decision above it bottoms out there and the rule underneath becomes
invisible to the planner and to anyone reading a plan. This item has a worked case: a relation's real
cost was a window function one relation below it, five and a half seconds of it, unseen behind the
registration until the register was emptied to look. A registration that is not paying for itself is
not neutral; it is a blindfold over whatever is underneath it.

**One inference to refuse.** That the readers of a registered relation get dearer without it does not
show the registration earns its keep. It shows the rule underneath is expensive, which is the thing
to fix.

## The lever order

Ordered by what each does to the model, cheapest and most durable first. The fact-model page carries
this rule; the figures behind it are here.

1. **Capture writes the fact.** A supertype the readers were unioning, a column the readers were
   computing. Removes the evaluation rather than relocating it.
2. **An index on a stored column.** Available only once the key is a column, which is why it follows
   the first.
3. **A rewrite.** Restate the rule so the planner can prune it.
4. **A registration.** Correct where the rule is well modelled and still cannot be planned. Costs a
   refresh and blinds the planner to everything below it.

The order is a claim about what to try first, not that a registration is never the answer: the
largest single measured improvement anywhere in this subject was a pair of registrations, taking two
positions from 464.5 s and 82.3 s to 0.2 s and 1.3 s.

Rung 3 was measured against rung 4 on the workload's worst reader, which is the case the order was
always asserting without evidence. `intent_condition_table_parameter` cost 25.53 s of an 88.2 s
workload because it asks `EXISTS (closure) OR EXISTS (catalog)`, where the closure is
`intent_jvm_ancestor`. An OR of two correlated subqueries admits no semi-join, so a recursive climb
of the class hierarchy runs once per candidate parameter. Restating the two tests as one union joined
to the driving rows took it to 2.73 s, with the register untouched and every relation returning
identical rows. Registering `intent_jvm_ancestor` instead took it to 0.02 s for 2284 rows and 0.2 s
of refresh. The rewrite is nine tenths of the win, and the registration would have bought the last
tenth by burying the defect: nothing downstream would ever again show that the rule asks its question
in a form no planner can answer. This is the item's one side-by-side of the two rungs, and the two
relations are one finding rather than two; later sections refer to these figures rather than restate
them.


## What landed

Thirty-one slices, grouped by what they changed. The per-slice reasoning, the arms that were tried
and withdrawn, and the figures that were later voided are in the git history; what follows is the
delivered state.

**Supertypes captured.** Eight per-site argMapping tables, three classpath type-reference tables and
two polymorphic membership tables became one relation each: `graphitron_arg_mapping_pair`,
`jvm_declared_type_ref`, `graphql_poly_member`. Every view that reconstructed one by union reads the
table. Isolated on the kept store, the two argMapping supertypes take `intent_carrier_routine_hop`
from 77.90 s to 0.07 s with nothing materialized in either arm.

**Join keys stored.** Two keys that existed only as expressions became columns an index can serve.
The measured case: `intent_field_accessor_hop` refuses a 120-second budget with all registrations in
place and returns its 21287 rows in 1.90 s with none of them, on a generated bean-property column
with one index.

**Grains given.** Five of the twenty registered targets carry a primary key, against none at the
start. `SupertypeSignatureGateTest` holds the signature, and `MetaDeclarationGateTest`'s frozen
roster is what keeps a new relation from arriving undeclared.

**The argMapping right-hand side modelled.** A written path used to be resolved by walking a
positional segment list, a hundred-odd probes per statement. `graphitron_argmapping_candidate` keys
the candidate tree by the path it resolves, and the resolution is a prefix match ranked by depth.

**The argMapping family put on schema coordinates.** The candidate tree is keyed by the coordinate
the directive carrying the argMapping sits on, spelled as the GraphQL specification spells one and
anchored by `graphql_coordinate`: `Type.field`, `Type.field(argument:)`, `InputType.field`. It holds
every spelling an author may legally write at that coordinate, including the one that repeats the
coordinate's own name, marked `deprecated`. `graphitron_argmapping_entry` carries `written_path` and
three generated readings of it, and `graphitron_argument_path_segment` is deleted: the stored
decomposition existed so a reader could probe every prefix, and with every legal spelling a
candidate there are only two prefixes worth asking about. `graphitron_argmapping_match` lost its two
window functions, its subquery wrapper, its ranking, and the nine-way `CASE` that enforced the
repeating spelling; what survives is two left joins and a five-site list, those four of the nine
sites that bind nothing whatever they spell.

Two relations then shed what they were keeping twice. `graphitron_argmapping_entry` had `type_name`,
`field_name` and `argument_name` beside the coordinate that spells them, and they are gone; a reader
wanting the parts joins `graphql_coordinate_field`, the decomposition stated once instead of a
two-arm COALESCE per reader. And `graphitron_argmapping_match` was carrying `written_path` and
`trailing_name`, neither of which the resolution decided: a match says which candidate bound, the
author's spelling is the entry's, and every consumer of a match already holds the entry's key.
Removing that pass-through is what let the case fold return to a stored column, because a value
travelling through a view has nowhere to keep one; `graphitron_argmapping_entry.tail_name_upper`
sits beside the name it folds and the schema's per-row `UPPER` count in views is back to eleven.

The plan-size figure moved the wrong way throughout and is reported rather than explained away: the
shipping arm goes 2774 to 2842 instantiations across the three commits and the heaviest read 349 to
372. The match view goes 6 to 9, having traded one segment join for a second candidate join and a
decomposition it no longer keeps beside itself. What the measure does not count is what left the
same view, two window partitions over the entry grain, or that
`intent_argmapping_projection_defect` lost a third of its text and one of its six verdicts, or that
one per-row fold became a stored one. That is worth stating as a limit of the measure rather than as
a defence: relation instantiations count namings, so a rewrite that replaces a ranked probe with two
equalities, and a decomposition kept in four places with one relation, both read as worse.

**The gathering architecture.** `FactSink` buffered every gatherer's rows until one flush at the end,
so no gatherer could read another's facts and everything crossed as hand-threaded Java parameters.
Each gatherer now flushes inside the same transaction in declared order, `meta_gatherer_dependency`
states that order as data, and the catalog leads. `GraphitronFactCapture` stopped being a visitor the
SDL walk called and became a gatherer with three stages that read each other through the store.

**The macro expansion left the transcription.** `graphql_type`, `graphql_type_declaration` and
`graphql_field` hold what the author declared and nothing else. What `@asConnection` mints is
`graphitron_minted_type`, `graphitron_minted_type_site` and `graphitron_minted_field`;
`graphitron_field_synthesis` holds the macro's replacement expression;
`intent_expanded_type` and `intent_expanded_field` union the two. Both readings of a rewritten field
are now rows at one coordinate, where before the authored expression survived only in a text column
no anti-join could recover.

**Two registrations retired**, one on a structural argument and one on the blindfold argument: a
registration standing over a rule hides what the rule costs.

**The argMapping alias dissolved.** `intent_argmapping_pair` selected all eighteen columns of
`graphitron_arg_mapping_pair` and did nothing else: no filter, no join, no union. It existed to widen
eight per-site relations, those eight are gone, and its own comment says so. Its five reader views,
`ArgmappingProjectionDefects` and its test now name the table. It was hiding a drift worth recording,
because the drift is what an alias buys: six downstream comments described the site vocabulary as
that relation's "closed vocabulary of eight" where the relation underneath states nine and
enumerates nine. A count restated in six comments about a relation that restates another relation is
the failure mode, not an accident. 287 relations to 286, with the frozen undeclared roster and the
stated view count following.

**The candidate relation is keyed by coordinate and path, and holds only writable things.** It was
keyed by an origin, which was a serialized position with the path's head folded into it, and a root
was a row whose path was the empty string. Neither survives. A coordinate is the container whose
members a head may name, the field where a head names an argument and the input type where it names
one of that type's fields; the path is the whole right-hand side, head included; and a root is a row
with no parent. Nothing stores `''`, because `''` is not something an author can write.

Three columns went with the change. `origin_kind` described the rooting and was constant down a
subtree; `element_kind` replaces it and describes the row, which removed every `depth = 0` test in
the reader, `bound_kind` collapsing from a two-arm CASE to a column. `argument_name` was origin-level
data repeated on every descendant and equal to `element_name` at the one row that used it. And the
serialized origin existed only because a nullable `argument_name` could not sit in the key; with the
argument now the head of a path, the key needs neither.

The sigil follows from the same rule rather than from a special case: a sigil is a path written at a
coordinate, not a coordinate of its own, so it is a root like an argument is, and the only thing that
distinguishes it is that it names no GraphQL type. What had looked like a subtype needing nullable
columns was a path modelled as a place.

**The node family became entry and anchor pairs, and the type binding with it.**
`graphitron_tabletype` holds the settled type-to-table bindings, so eleven readers stop spelling a
candidate count and four stop forgetting it. `graphitron_node_entry` and `graphitron_node` split what
an author wrote from what took effect, `graphitron_node_keycolumn_entry` and
`graphitron_node_keycolumn` do the same for the key tuple, and the key columns resolve against the
bound table instead of forwarding a spelling, which retired four per-row folds and absorbed R731.
`graphitron_field` states where a field's rows come from and where the field departs from to reach
them, which no relation said before: the departure lived in one derivation keyed on the parent type
and the arrival in another keyed on the field.

## The entry and anchor pattern, and the scope it makes visible

The slices above each fixed a rule. What the node and field arcs added is the shape the fixes have in
common, and stating it is what turns a sequence of repairs into a scope.

### Four attempts, and what each of them found missing

Not one of the four landed the relation it set out to land without first landing something
underneath it. The pivots are worth recording because they are not accidents of sequencing: each was
found by the model refusing rather than by planning, and each turned out to be a fact the store
should have been holding all along.

[cols="2,4,3"]
|===
| the attempt | what refused | what had to be taken first

| the argMapping family
| `graphitron_argmapping_candidate` rendered a coordinate into a string and met
  `graphitron_argmapping_entry` on the text, because four relations stated that a coordinate exists,
  one per grain, and nothing stated that a coordinate exists
| `graphql_coordinate`, the supertype the family always implied, and the four subtypes pointing at it

| nodehood, first attempt
| six of the seven nodehood relations read `intent_resolved_type_binding`, which unions the `@table`
  decode with where a routine chain lands, so a `@node` on a routine-returned type was table bound as
  far as they could tell and collected a key it had no claim to
| `graphitron_tabletype`, the settled `@table` binding, and eleven `candidates = 1` predicates with it

| nodehood, second attempt: the node id pair
| the rule reads scope relations that are registered targets, so a gatherer cannot see them at all,
  and those in turn read a reference walk that is itself unmigrated
| `graphitron_field`, the endpoints. The route and the reference decode under it are still owed

| the reference step decode
| a foreign key into `graphql_field_coordinate` refused `QueryFilmsConnection.nodes`, a field macro
  expansion minted and the transcription does not hold
| the macro-aware coordinate anchors, which do not exist yet
|===

Three things follow that no single slice would have shown.

**The pattern was coined during the work, not designed before it.** Entry and anchor came out of the
nodehood arc and were then applied backwards to the relations already shipped, which is why the node
pair carries a rename commit. A shape found this way is worth more than one chosen up front, and it
is also why the earlier slices above do not describe themselves in its terms.

**A prerequisite is discovered by something refusing.** A foreign key that will not hold, a
population that turns out fused, a rule whose inputs are empty at the cadence its reader runs at.
None of the four was visible from reading the schema, and all four were visible within an hour of
trying to write the relation. The practical consequence is that this arc cannot be planned much
further ahead than the next relation, and estimating it as a list of relations understates it.

**The witness is the hard part, not the fix.** The nodehood repair's first test would have passed
against the defect it was written for: a `@node` type with no `@table` returned by a routine resolves
no key columns under either reading, because a bare function result has no primary key. The fixture
that separates them puts a `@reference` after the routine so the chain lands on a real table with a
key. The same failure has recurred since: `TableTypeTest` asserts that the settled bindings equal the
derivation's unambiguous rows and its fixture contains no routine, so the equality it states cannot
fail and did not notice the two-grain fusion described in the diagnosis. The rule the arc now works
under is to build the fixture that can falsify the claim before believing the claim, and to verify it
by removing the thing under test and watching the test go red.

**An entry holds what the author wrote; an anchor holds what resolved.** The entry is keyed to the
coordinate the directive sits on, carries unconstrained nullable columns, and holds rows the
directive did nothing for, which is exactly why it can be keyed there. The anchor carries a primary
key and foreign keys into what the resolution reached. What an author got wrong is the anti-join
between the two, and it is the only place a diagnostic can find it.

**An anchor's population is not its entry's.** `graphitron_node` unions a declared arm and a
published one, `graphitron_node_keycolumn` ranks three tiers, `graphitron_field` has three disjoint
target rules and only one of them reads a directive. A pair is not a decode with its resolution
bolted on; it is two relations answering different questions that happen to meet at an anti-join.

**A graphitron relation cannot key at `graphql_*_coordinate`.** Macro expansion mints coordinates the
transcription does not hold, so a foreign key there excludes exactly the fields a connection is made
of. `graphitron_field` was written with one and the build failed on `QueryFilmsConnection.nodes`.
Every graphitron relation has to key through a macro-aware anchor over the expanded coordinate set,
and no such anchor exists: `intent_expanded_type` and `intent_expanded_field` are views, so nothing
can point at them. `graphitron_type_coordinate` and `graphitron_field_coordinate` are what the rest
of this arc keys through, and they are the next thing owed.

**Lifecycle is provenance and a refcount, not a cascade.** A minted row can be coined by several
sources: `PageInfo` is minted once and carries one site per `@asConnection` application, two in a
two-connection schema. There is no single parent for it to cascade from, so the coining direction
cannot carry `ON DELETE CASCADE` at all, and `graphitron_minted_type_site` is the provenance relation
whose site count is the refcount. Nothing exercises it today: `StoreRefresh.clear` deletes every
graph-keyed relation on every capture and derives that set from the presence of a `GRAPH_NAME`
column, so a new graphitron relation is rebuilt wholesale by default. The sweep is owed the day an
incremental path exists, and until then what matters is not baking in a cascade that would be wrong
when it does.

**What the arc still owes**, in the order the dependencies force:

1. `graphitron_type_coordinate` and `graphitron_field_coordinate`, the macro-aware anchors, plus the
   foreign key `graphitron_minted_type_site` lacks on the carrier that coined it. `graphitron_field`
   repoints onto the field one.
2. The join path family, described below. `graphitron_field_joinpath` is its head.
3. The reference decode on the field sites: `graphitron_field_reference_step` and
   `graphitron_reference_for_step`, which differ only in the key saying which directive owns the row.
   The two argument-site relations beside them are item 4's, not this one's.
4. The argument and input-field sides, deliberately last. Their `@reference` support differs from the
   output-field side, a routine segment has no meaning there, and the multi-table polymorphic root
   fans the departure out per branch, so folding them in before the field side is settled would model
   three unlike things as one.

### The join path family

`graphitron_field` says where a field's rows come from and where it departs from. The route between
those two endpoints is a family of its own, and it is the largest thing this arc has left.

**A path runs from one source table to one target table.** One source can have many targets: a field
whose navigated type is a multi-table interface or a union departs once and arrives once per
participant, and the paths to those participants need not agree, since each is a different join. So
`graphitron_field_joinpath` is keyed by the whole of `graphitron_field`'s key rather than by the
coordinate, and the two cannot disagree about which target a path leads to.

**A path's nodes are of two kinds, and that is the manual's own definition rather than a
convenience.** A field's table chain is the concatenation, in written order, of the enclosing type's
table and each directive application's contribution: `@reference` contributes hops, and `@routine`
contributes its result table as a node. A relation holding only the hops would hold half a chain,
which is what the present walk does. The order across applications is load-bearing and is not
captured: ordinals are per directive name, so `@reference#0`, `@routine#0` and `@reference#1` carry
no relative order and the walk recovers it by comparing source line and column. Measured on the
manual's own sandwich example, that recovery reports two nodes where the manual describes four, the
hop written before the routine being absent. The chain position therefore has to be minted at
capture, where the order is known, rather than reconstructed by a reader.

**More relations sit under it.** The reference decode it resolves, the routine decode it interleaves,
and whatever the resolution of a hop turns out to need, since the hop relation today is a candidate
enumeration rather than a resolution: it emits both directions and every matching constraint, and two
separate recursive walks pick between them. How many relations that is, is not yet known, and the
arc's own history says the number will be discovered by something refusing rather than by planning.

What this reorders in the item as a whole: the register is downstream of all of it. Each of the four
removes the reason a registration existed rather than arguing the registration down, which is the
lever order's first rung applied to relations rather than to columns.

## What the evidence is

**Plan instantiations, on a fresh capture of a 26 818-line consumer schema.** The criterion this item
set itself was three counts in one plan of `intent_node_id_decode`.

[cols="4,2,2"]
|===
| relation | before | after

| `graphitron_argument_path_segment` | 106 | 2
| `graphitron_arg_mapping_pair` | 84 | 14
| `intent_input_occurrence_path_step` | 38 | 0
|===

Two met, one missed: the pair table was to reach low single digits and is at fourteen. Total relation
references in that plan fall from 427 to 128 and the plan text from 525 497 characters to 138 962.

**Row identity across every repoint**, on the same capture: `intent_field_accessor_hop` 21287,
`intent_field_navigated_type` 8408, `intent_spelled_table` 313, `intent_argmapping_pair` 108,
`intent_class_member_slot` 4198. All five as recorded before the work.

**The expansion, on the same capture.** 218 `@asConnection` applications produce 218
`graphitron_connection` rows and 218 field rewrites; 434 minted types across 436 sites carry 1302
minted fields. `intent_expanded_type` is 2345, which is 1911 plus 434. `intent_expanded_field` is
8408, which is 7106 plus 1302. `graphitron_field_navigation` is 8408, one row per expanded field.

**Capture wall clock.** The same consumer sources at the same commit captured in 4h19m on 2026-08-27
and 1m08s on 2026-08-31. Not this item's doing, and stated because every figure above rests on being
able to take a capture cheaply.

**What the register is worth, on the fresh capture.** Priced by rebuilding the schema with a chosen
subset of registrations demoted to views, copying the captured base facts in, refreshing what is
still registered and reading every `intent_` relation once.

[cols="3,2,2"]
|===
| arm | refresh | reads

| all twenty registered | 7.6 s | 88.2 s over 114 relations, none over budget
| nothing registered | 0 s | did not finish: sixteen relations over a 120 s budget, then the planner exhausted the heap
| one registration dropped, `intent_node_id_instruction` | 8.9 s | 495 s, three relations over budget
|===

Emptying the register is therefore not reachable, which retired the target R899 was filed against;
that item has since been respecified around counting a registration's alternative from the schema
instead. The register's own recorded prices are stale in both directions: the registration whose
reason calls its removal the steepest figure in the register, at ninety-five minutes of refresh, now
refreshes in 7.2 s without it.

**The register is ownerless.** `meta_relation` declares six relations, all six created by this item.
Two hundred and eighty-one relations sit on the frozen undeclared roster, and none of the twenty
registered targets has an owner. The materializer runs as one anonymous pass at the end of capture
because there is no gatherer to attribute it to. Thirteen of the twenty targets hold no rows at all
on the consumer capture measured.

**The gatherer chart.** A crawler is a gatherer with at least one corpus. Two gatherers have none.

[cols="2,3,3"]
|===
| gatherer | corpora | depends on

| `configuration` | configuration | nothing
| `sdl` | sdl | nothing
| `catalog` | catalog, classpath | nothing
| `java-source` | java-source | nothing
| `compile` | javac | nothing
| `graphitron` | none | `sdl`, `catalog`
| `derivation` | none | all five crawlers, and `graphitron`
|===

**What each family holds.** Only the two gatherers with no corpus write anything that reads another
relation.

[cols="2,2,2,4"]
|===
| family | base tables | views | outgoing reads

| `sql_` | 14 | 0 | none, a corpus transcription
| `jvm_` | 7 | 0 | none, a corpus transcription
| `graphql_` | 27 | 1 | none, a corpus transcription
| `graphitron_` | 64 | 0 | none in SQL; every row written by Java that read `graphql_` and `sql_`
| `intent_` | 25 | 89 | 134 into `graphitron_`, 67 `graphql_`, 47 `sql_`, 26 `jvm_`, 12 `store_`
|===

So there are two derivation gatherers already, and the boundary between them is mechanism rather
than dependence: `graphitron_` is derived in Java and written early, `intent_` is stated in SQL and
written late, and neither prefix records what a relation reads.

**What the second gatherer actually reads.** Walking all 114 `intent_` rules to the base relations
they bottom out at gives 41 in `graphitron_`, 11 in `sql_`, 8 in `graphql_`, 6 in `jvm_`,
`store_graph_source`, and 6 hand-written `intent_` tables. Nothing reaches `java_`, `javac_`, the
configuration corpus, `lint_`, `rejection_` or `diagnostic`: zero rules, not few. The
derivation gatherer declares six dependencies and three of them, `configuration`, `java-source` and
`compile`, are used by nothing it owns. Its real inputs are the SDL transcription, the catalog and
classpath transcriptions, and `graphitron_`, which is what the graphitron gatherer has once it has
written its own facts: that gatherer already depends on `sdl` and on `catalog`, and `catalog` carries
the classpath corpus that `@service` and `@condition` need. This is the count the collapse rests on.

**How many families each rule reads**, walked from each rule's view body down to the base facts,
ignoring `store_`. This is the test of whether a rule belongs to a family or to the gatherer that runs
last.

[cols="4,2,3"]
|===
| rule | families | placement under the rule

| `intent_jvm_ancestor` | 1, `jvm_` | belongs to the `jvm_` family; needs no registration
| `intent_spelled_table` | 2 | crossing
| `intent_expanded_type`, `intent_expanded_field` | 2 | crossing
| `intent_condition_table_parameter` | 3 | crossing
| `intent_field_reference_step_hop` | 3 | crossing
| `intent_resolved_type_binding` | 4 | crossing
| `intent_field_column_scope` | 4 | crossing
| `intent_node_id_instruction`, `intent_carrier_data_field` | 5 | crossing
| `intent_field_scope_table`, `intent_argument_scope_table` | 5 | crossing
|===

Every registered target sampled crosses. Walked over all 114 `intent_` relations the same way, twelve
read exactly one corpus family: four `sql_`, four `graphitron_`, two `jvm_` and two `graphql_`. A
further six bottom out at hand-written derivations the walk cannot see through and are unresolved
rather than local. The remaining ninety-six cross two to five families.

## The target architecture

The end state is computable from the shipped DDL, so it is stated here as a computation rather than
as a description. Assign every base relation to the gatherer whose corpus it transcribes; give the
five hand-written `intent_` base tables to `graphitron` per the collapse; resolve each registered
target through the rule that fills it, so a target's owner is its rule's owner; and take every view's
owner to be the latest, in gatherer dependency order, of the owners of what it reads. That runs over
all 287 relations the schema ships today, 170 tables and 117 views.

**Nothing computes to `derivation`.** Not one relation of 287. The gatherer is empty, which is the
collapse as a result rather than as an argument.

[cols="3,2,5"]
|===
| owner | relations | what it holds

| `graphitron` | 186 | the decode's own facts, and every rule derived from more than one corpus
| `sdl` | 31 | the document transcription, and two rules local to it
| `catalog` | 27 | the catalog and classpath transcriptions, and six rules local to them
| `configuration` | 14 | the session and graph configuration
| `java-source` | 7 | the Java source corpus
| `compile` | 1 | the compiler corpus
| `derivation` | 0 | nothing
|===

Eleven relations sit outside the model and are not a gap in it: nine `meta_` registry tables, which
are the model's description of itself rather than any gatherer's output, and `rejection_` and
`build_warning_no_rule`, which are outputs rather than facts.

**All twenty registered targets compute to `graphitron`.** Every one, not most, and without a
judgement call anywhere in the walk. The register's whole population moves to one owner that already
exists and already runs, which is what makes the dissolution one change rather than twenty decisions.

**Nine relations named `intent_` compute to an owner that runs before `graphitron`.** These are the
family-local rules this item has been calling misplaced, now enumerated rather than counted.

[cols="2,6"]
|===
| computed owner | relation

| `catalog` | `intent_class_member_slot`, `intent_foreign_key_column_pair`, `intent_jvm_ancestor`, `intent_name_matched_key_pair`, `intent_node_metadata_defect`, `intent_table_key_candidate`
| `sdl` | `intent_poly_member`, `intent_type_exemption`
| ambiguous | `intent_java_enum_class`, for the reason below
|===

### Two gaps in the rule, found by running it

**"The latest in gatherer dependency order" is not a total order.** Five gatherers depend on nothing
and therefore tie. `intent_java_enum_class` reads `store_graph_source`, `sql_enum_binding` and
`jvm_class`, owned by `configuration`, `catalog` and `catalog`: two gatherers are equally late and the
rule does not say which wins. The recommendation is to exclude `store_` from the computation rather
than to invent a tie-break. Session and graph configuration is state every gatherer reads to know
which graph it is working on, so counting it as an input makes `configuration` a candidate owner for
almost everything while saying nothing about where a rule belongs. Excluding it puts this relation in
`catalog`, where its facts are. Whether any genuine tie survives that exclusion is unmeasured.

**A rule with no inputs has no computed owner.** `intent_delivery_container` is a `VALUES` list of
seven container classes, a named vocabulary rather than a derivation, and the rule is a function of
what a relation reads. Five views above it inherit the gap: `intent_class_member_element`,
`intent_declared_type_element`, `intent_field_accessor_hop`, `intent_producer_cardinality_conflict`
and `intent_type_backing_seed`. That reconciles the six the family-crossing walk left unresolved:
five hand-written base tables and one constant view. Constants are owned by declaration rather than
by computation, and the rule has to say so instead of returning nothing.

Neither gap argues against the rule. Both are cases it is silent on, and both were invisible until it
was computed.

### What each mechanism becomes

[cols="3,5"]
|===
| today | after

| `meta_materialize`, 20 rows | gone. Its population is `graphitron`'s own to refresh, and a gatherer refreshing its own family needs no register.
| `meta_materialize_dependency`, 62 rows | replaced or gone. It derives a refresh order among registrations; what supersedes it is whatever orders two relations under one owner, which is not settled.
| the `_live` views | gone as a convention. A rule its owner decides to store is stored under its own name.
| the `intent_` prefix | kept, and stops naming an owner. `sql_` and `jvm_` already share `catalog`, so a prefix has never been an owner, and renaming 114 relations buys nothing this item can name.
| `meta_gatherer`, 7 rows | 6 rows. The `derivation` row goes, and with it the six `meta_gatherer_dependency` rows under it.
| `meta_relation.owner_name` | unchanged in shape, and checkable: a declared owner must equal the computed one.
|===

What this does not settle: what orders two relations under one owner, which is the one mechanism
`meta_materialize_dependency` provides today and nothing here replaces; and what becomes of the
`java-source` and `compile` gatherers, whose eight relations no derivation reads.

## What is not done

**An unowned register is the mechanism behind every stale figure above.** A materialization is work
somebody has to keep true, and none of the twenty has anybody. That is the mechanism behind every
stale figure recorded above: nobody re-checked a price, noticed an empty target, or had to agree to
carry a new registration, because carrying one is not anybody's job. Adding a row to
`meta_materialize` costs three lines. Maintaining what it stands over costs nothing that anyone can
see, which is exactly why the first lever has been the last one.

The mechanism for fixing this is R877's, not this item's. That item is already declaring grains and
owners family by family and has reached `sql_` and `jvm_`, and the fact model states the end state
directly: a derivation that reads one family's facts moves into that family, where its owner keeps it
by whatever means it likes and needs no `meta_materialize` row, because the register exists to
schedule refreshes for rules with no owner to schedule them. A rule that moves into a family is not
converted into anything; it leaves the register's question. The rules that cross families do not stay
registered either, and the target below says why: once the collapse lands there is no gatherer
running after `graphitron` for a crossing rule to wait for. Nothing is retired and nothing is argued
down row by row. The mechanism is left with no work.

Measured against that rule, the twenty are not wrongly chosen. Every registered target sampled reads
between two and five families, so each is genuinely the last gatherer's to refresh. What is misplaced
sits in the prefix rather than in the register.

The evidence is that an unowned register rots in ways nothing surfaces. A registration's recorded
price can be wrong by three orders of magnitude, thirteen of twenty targets can hold no rows on a real
consumer, and a twenty-first can look like a twenty-seven percent win, with nothing failing in any of
those cases. R877's own finding, that the twenty unkeyed tables are exactly the twenty registered
targets, is the same observation reached from the modelling side.

Declaring an owner makes a materialization somebody's work; it does not by itself make the
alternative visible at the moment somebody reaches for one, and a `meta_materialize` reason is
unchecked prose today with several provably stale. That half is R899's, filed below.

**`intent_` is owned, and putting a relation there has stopped being a decision.** The family belongs
to the derivation gatherer, which is a real owner with a real reason to exist: it runs after every
corpus gatherer, which is the earliest point a rule crossing families has all its inputs. The defect
is that landing there became the default rather than the reasoned choice. A relation goes to `intent_`
because it is derived, not because it crosses anything, and nothing asks the question.

The cost of that default is precise. A rule placed in `intent_` is owned by the gatherer that runs
last, so it cannot be settled until everything else has finished, and the only lever its owner has
left is a registration. The same rule placed in the family whose facts it actually reads is owned by
a gatherer that has already run, and its owner can store it, index it or leave it a view, at the
point where the facts are complete and nothing downstream has started.

The twelve family-local relations counted above were each a reasoned placement nobody made. Two of
them are relations this item created, which is worth saying plainly: `intent_argmapping_pair` and
`intent_field_navigated_type` read only `graphitron_` facts, so this item took the default twice
while arguing against it.

**The target is stated under "The target architecture" above, as the owner every one of the 287
relations computes to.** What belongs here is what taking it costs. `intent_` moves to `graphitron`,
and the difference between writing a row from Java and stating it in SQL becomes an implementation
choice inside one owner, made where the cost is visible and changeable without anyone else being
told. The register is not retired, argued down or shrunk to a defensible core; the gatherer it was
compensating for stops existing. Read the other way, this is why the register exists at all: a
gatherer invented to run last needs a mechanism to schedule the refreshes of relations that never had
to wait for it.

**The rule that keeps it from coming back is that an owner is computed, not chosen.** A relation's
owner is the latest, in gatherer dependency order, of the owners of the relations it reads. That is a
function of the schema, so a gate can check it, and it makes the default impossible to take: a rule
reading only `jvm_` facts cannot be owned by a gatherer that runs after `catalog`, because nothing it
reads is owned there. The same rule is what says the derivation gatherer is unearned, so the gate and
the collapse are one check rather than two changes.

Two things have to be true first, and neither is this item's. R877's declarations, at 27 of 287
relations with 260 still on the frozen roster, because computed ownership cannot be checked over
undeclared relations. And per-gatherer transaction control, named above, which the collapse makes
easier rather than harder: one derivation gatherer needs one boundary, not two.

Three of the four questions the chart raised are answered above: the prefix is kept and stops naming
an owner, the six unresolved relations turned out to be one constant view and the five that read it,
and the ordering question survives as the one mechanism nothing here replaces. The fourth stands
unchanged. What becomes of the `java-source` and `compile` gatherers, whose eight relations no
derivation reads, is a different finding from this one and is not evidence that they are unnecessary.

**One of the twelve costs more than anything else in the store.** `intent_jvm_ancestor` reads
`jvm_class_supertype` and `jvm_declared_type_ref` and nothing else, so it belongs to the `jvm_`
family, whose owner is the catalog gatherer: the one that runs first. Placed with the derivation
gatherer instead, it is settled last, and it is read through a correlated existence test that
re-climbs the whole class hierarchy per driving row, which is the largest single read cost in the
store and is the case measured under "The lever order". Under the owner it should have had, both
fixes there are available and neither needs a register row: an owner may restate its reader's rule or
simply decide to store the closure. Proposing to register it was reaching for the last lever on a
rule whose real problem was that nobody chose where it lived.

**The prerequisite for any of this is per-gatherer transaction control**, which the fact model already
names and the store does not have. `FactCapture` runs every gatherer inside one transaction, so no
gatherer can commit its family and then refresh its own relations against statistics reflecting what
it just wrote. The empty-store path is the one exception already carved out, and the rule needs it
promoted to the normal shape.

**The argMapping groundwork is captured and unused, which is two subsumptions and a join swap.**
This item landed a supertype and a candidate tree and then did not repoint the readers, so the claim
that the right-hand side is modelled is only half true. Both halves are measured, on the same
consumer capture, and neither needs a rewrite of any rule.

The first subsumption is that `graphitron_argmapping_candidate` contains
`intent_input_occurrence_path` outright. Of 3027 occurrence paths, 3027 have a candidate at the same
coordinate and none is missing; the candidate tree carries 122 more, all at depth zero, being the
arguments whose named type is not an input object that the occurrence walk skips. Below the root the
depth histograms are identical, 1936, 505, 140 and 40 either way. Every shared attribute agrees on
every row: leaf type, depth and the root coordinate, 3027 of 3027 each. The step child is not a
second fact but a denormalization, 3526 rows over 2621 tree nodes, each node repeated once per
descendant path through it, which the candidate tree states once with a parent link. So two
hand-written `intent_` base tables and the 164-line writer that fills them are a view apiece over a
relation capture already writes, and the reader interface does not move: the 19 slots that name them
keep their columns. Priced as views the whole schema costs 55 more instantiations, two percent, with
the heaviest read unchanged.

The second is that the resolution is already captured, and it is already used. Every argMapping pair
carries the candidate coordinate its right-hand side selects and every path segment carries the
candidate its own position resolves to, both resolving completely at 108 of 108 pairs and 202 of 202
segments. `intent_argmapping_binding_leaf` was rewritten onto that column when the candidate tree
landed, and its own comment records the shape it replaced: a three-arm head union over two
decompositions of one descent aligned by an anti-join inside an anti-join.

**So the percolation is mostly done, and the census that said otherwise was measuring the wrong
thing.** Counting readers of `graphitron_argument_path_segment` counts every use of the authored
decomposition, not every positional walk, and the two are not the same relation.
`intent_argmapping_key_column_candidate` and `intent_argmapping_projection_defect` read it one
position past where a path resolved, for the segment the author wrote naming a key column; that
segment names no input field, so it is in no candidate tree and the join is the only way to reach it.
`intent_argmapping_segment_binding` was the old shape and the exception, still aligning
decompositions. R896's census came back empty, no reader in SQL or in main sources, and it is
retired: the view, its own test, its agreement registration and its line on the undeclared
roster are all gone, and three comments that named it are repaired, two of which had gone stale
when the candidate tree took its readers.
One real target was left: `intent_type_backing_seed` joined the segment relation at position zero to
read a path head that `graphitron_arg_mapping_pair.head_segment` already carries, which is exactly
what that column was added to stop readers doing. Measured on the capture, the two agree on 108 of
108 pairs with no absences, so the join was redundant rather than defensive.

**One relation moved into the family that computes its owner, and it is the first `graphitron_`
view.** `intent_argmapping_binding_leaf` reads `graphitron_arg_mapping_pair`,
`graphitron_argument_path_segment`, `graphitron_argmapping_candidate`, `graphitron_argument_node_id`
and `graphitron_field_node_id`, and nothing else. Every input belongs to the graphitron gatherer, so
the computed owner is graphitron and the `intent_` prefix on it recorded the default placement rather
than a decision. It is now `graphitron_argmapping_match`, which corrects the noun at the same time:
the schema already conceded that a leaf in the candidate tree is a candidate with no children while
this relation states where a written path stopped, routinely at an interior candidate.

Nothing in the build objected to a view in the `graphitron_` family, which is the part worth
recording. The prefix had 64 tables and no views, so the shape looked forbidden; it was only absent.
The `graphql_` family already holds one, so the precedent existed and nothing had to be relaxed. That
makes this the cheapest possible test of the collapse: a relation crossing from the SQL-stated family
to the Java-written one, under one owner, with the full build green and no gate touched. The rename
was taken here rather than deferred to the naming sweep it was filed under, because the defect was a
placement and not a spelling.

**And the scale is the indictment rather than the cost.** Those are 108 pairs and 202 segments on a
26 818-line schema. The heaviest read in the whole store, at 349 relation instantiations, is
`intent_argmapping_projection_defect` over exactly that population. No registration was ever going to
be the answer to a plan of that size over two hundred rows, and none stands there: the relation is
unregistered and always was.

**One rationale is spent and still load-bearing.** `intent_input_occurrence_path` justifies being a
table by saying cyclic input nesting has no safe recursive H2 view form. That was true when written.
The candidate tree resolves the cycles before any reader starts, under the same first-visit guard,
and records the outcome in a column, so the recursion is spent and the view form is a prefix join
rather than a recursion. This is the same defect the dissolved alias was hiding, in a different
place: a reason that was sound when written, never rechecked, and holding a decision up by itself.
Whatever this item does about the tables, the comment cannot stay as it is.

**The other two plan defects are unfixed.** One rule was rewritten and measured, above. The demand
and exemption family names the expansion union nine to twelve times per plan inside sixteen
correlated existence tests, and its probes then resolve on the partition dimension alone, which on a
single-graph consumer selects the whole relation. Both were only tested in their storage form and
neither has had its rule examined, which is the same mistake this item exists to name.

**Thirty-nine readings of the transcription are unadjudicated.** Splitting `graphql_type` and
`graphql_field` from the expanded population turned forty-two readings into forty-two decisions.
Four were made by a test failing, one by a row count catching a defect, and the rest are frozen on
`ExpandedPopulationReaderGateTest`'s roster, which asserts they are known and not that they are
right. One of the forty-two was wrong; the rate among the rest is unmeasured.

**The argMapping relation is not total.** Capture writes a row per authored binding, not a row per
parameter of every call with its provenance. Slice 16 reordered its own plan to put the base facts
first, and the base facts are what landed.

**No instrument for any of this lives in the repository.** Every read figure here was taken with a
bench built outside the tree and thrown away with the session. That asymmetry is the root cause
rather than an inconvenience: adding a registration is cheap and visible, and pricing the alternative
is expensive and invisible, so the register grows whatever the doctrine says. A rule bench that
prices a relation as a view against a captured store belongs in the tree.

## Tests

What holds this item in the build:

- `SupertypeSignatureGateTest` states the subtype sets and their reconstructions in full, and refuses
  a view that unions members of a set without projecting the set's own attributes. Adding a ninth
  sibling table becomes a decision somebody records rather than one that accumulates.
- `MetaDeclarationGateTest` holds the roster: every relation declares an owner and a grain, a declared
  view reads only what its owner may, a declared table's key matches its grain, and the roster of
  undeclared relations shrinks and never grows.
- `ExpandedPopulationReaderGateTest` holds the boundary the macro expansion moved. Every view reading
  `graphql_type` or `graphql_field` sits on a frozen roster, so a view that should read the union
  cannot start reading the transcription unnoticed, and the two union views are held to reading both
  arms.
- `GathererHandoffTest` holds the two properties per-gatherer flushing rests on: an upstream
  gatherer's row is readable by the one after it, and a load that dies between two flushes publishes
  nothing.
- `CaptureCorpusIsolationTest` holds the gathering order as a differential. One registry captured with
  the jOOQ catalog and once without must produce identical `graphql_` and `graphitron_` rows. Leading
  with the catalog is precisely the change that could break it, because a crawler now has rows to read
  where before it had none.
- `MaterializeRegistryGateTest` holds the register itself: which registrations exist, that each target
  is shaped like the view that fills it, that the dependency rows admit a refresh order, that every
  target carries an index or a roster row saying why not, and that every index names the reader
  justifying it.
- `FactSchemaGateTest.everyMaterializedTargetEqualsItsRule` is row identity on the register: a
  registered target equals what its view states. `everyRelationLeadsWithItsPartitionDimension` covers
  the supertype tables this item added.
- `NodeTypeTest`, `NodesTest`, `NodeKeyColumnTest` and `FieldEndpointsTest` hold the four pairs the
  capture arc has landed, each stating which rule answered rather than only that an answer came out.
  `FieldEndpointsTest` additionally pins the two properties the pattern turns on: that a macro-minted
  connection field has endpoints like any other, which is why the relation keys at no coordinate
  relation, and that a hop written after a `@routine` moves the target onto the catalog table so the
  routine rule knows when not to fire.
- `SchemaIdentifierDriftCheckTest` refuses prose in the store or the architecture pages that names a
  relation the schema does not declare. It caught this arc citing a relation one increment before it
  existed, which is the failure mode a spec describing planned relations invites.
- `DerivedReadCostTest` refuses a change that costs some reader more than it saves. Its
  `KNOWN_NON_MONOTONIC` set now carries thirteen rows: ten are the instrument's own scan floor on
  `intent_field_reference_step_hop`, two are the unindexed named-type join under
  `intent_spelled_table`,
  and one is the node-id instruction's. The argMapping row the set carried at the start of this item
  is gone, cleared by the supertype capture.

No wall-clock assertion, for the reason `DerivedReadCostTest` already states: a duration is not a
build assertion. Every timing in this item is research evidence taken against a captured consumer
store, and a consumer store is not a fixture.

## Retired vocabulary

What this item removed, for the retirement sweep at the Done gate. Determined by diffing the
schema's relation names across the item's life rather than from its own prose, then swept; the
survivors found are listed with what was done about them.

**Relations.** Eight per-site argMapping tables collapsed into one supertype,
`graphitron_argument_condition_arg_mapping_pair`,
`graphitron_argument_reference_for_step_arg_mapping_pair`,
`graphitron_argument_reference_step_arg_mapping_pair`,
`graphitron_field_condition_arg_mapping_pair`,
`graphitron_field_reference_step_arg_mapping_pair`,
`graphitron_reference_for_step_arg_mapping_pair`, `graphitron_routine_arg_mapping_pair` and
`graphitron_service_arg_mapping_pair`, all now `graphitron_arg_mapping_pair`. Three classpath
type-reference tables collapsed likewise: `jvm_method_parameter_type_ref`,
`jvm_method_return_type_ref` and `jvm_record_component_type_ref`, all now `jvm_declared_type_ref`.
Two polymorphic membership tables, `graphql_implements` and `graphql_union_member`, now
`graphql_poly_member`. `graphitron_source_row`, which was the shared fact the eight tables were
hiding. `intent_declared_type_ref`. `graphitron_type_declaration_synthesis`, now
`graphitron_minted_type` and `graphitron_minted_type_site`. And two retired registrations, which
deletes a `_live` view and a table each: `intent_argmapping_pair_live` and `intent_errors_field_live`.

**Columns and values.** `graphitron_field_synthesis.authored_type_sdl`, the relation's payload having
flipped to carry the macro's replacement rather than the expression it overwrote.
`AUTHORED_EXPRESSION`, retired from the navigation basis vocabulary, which is two values now.

**The argMapping coordinate remodelling retired more.** One relation,
`graphitron_argument_path_segment`, and with it `graphitron_argmapping_match.segment_position` and
`trailing_segments`, which counted over its rows. Four columns of
`graphitron_argmapping_entry`: `head_segment`, `head_kind`, `candidate_coordinate` and
`candidate_path`, the first two because the matched candidate says what was bound and the last two
because the coordinate is the site's own and the path is the written one. `argument_path` is
`written_path` there and everywhere it was carried through, having claimed an argument that two of
the nine sites do not sit under. `graphitron_argmapping_candidate.element_name` is `name`, and its
`type_name` and `field_name` are gone, a reader wanting the coordinate's parts joining the coordinate
relation. One verdict, `TRAILING_SEGMENTS_BEYOND_ONE`, from a vocabulary of six now five, along with
`intent_resolved_node_key_projection.trailing_segment_name`, now `trailing_name`.

Three more columns of `graphitron_argmapping_entry` followed, `type_name`, `field_name` and
`argument_name`, the coordinate beside them saying the same thing; and two of
`graphitron_argmapping_match`, `written_path` and `trailing_name`, which were the author's spelling
passing through a resolution that had not decided them. `graphql_coordinate_field` is new and is
where a reader now decomposes a coordinate.

**Java.** `MacroCapture.expandConnections`, now `MacroCapture.expand` and driven by store rows rather
than by the walk. The `Expansions` record and the five `captureXDirective` callbacks `SdlFactCapture`
drove the decode through, along with `captureNavigation` and `connectionElementByType`, all of which
went when the decode stopped being a visitor of the SDL walk.

**Swept, with seven survivors found and fixed.** One in main sources: the comment on
`intent_field_navigated_type.basis` still described a closed vocabulary of three and named the retired
rung as current. Six in roadmap bodies, four of them live plans rather than history:
`capture-expands-facet-synthesis` and `corpus-directives-to-expect-equals` named the retired synthesis
relation, `producer-registration-after-duplication-removal` offered the retired errors-field pair as
the model to copy, and `planners-read-facts-emitters-read-commands` named a retired census relation
twice. `census-stores-members-it-reads-by-name` carries the three census relations inside a measured
table; the counts are left as taken with a note, because renaming them would falsify a measurement.

**One survivor was not a name fix and is flagged rather than corrected.**
`authored-connection-type-scope-silence` rests its whole premise on
`graphitron_field_synthesis.authored_type_sdl` and on `intent_field_scope_table` reading it. The
column is gone, the direction is reversed, and that view reads neither relation now. A dated note
says so in its body; whether the defect it reports still exists is for its own author to re-measure,
not for the sweep to decide.

## What this item does not do

It does not delete the twelve unreachable targets or their relations. They are work under
construction, they appear in two to five test sources each, and their registrations may be right the
moment their readers land. The question is when a registration is earned, not whether the relations
belong.

It does not reopen the two payload registrations that landed 2026-08-28, and the case for leaving
them alone is now measured rather than deferential; the figures are under "Superseded items", where
the judgement they correct was recorded. That pair is the largest single measured improvement
anywhere in this subject and it was a registration, which this item's own lever ordering puts last.
"The lever order" states why that is not a contradiction, and nothing here proposes undoing it.

It does not build a consumer-scale fixture. Nothing in this repository captures a schema of that
size, and a fixture that did would be a wall-clock gate, which the build-guardrail item owns.

And it does not promise a scoring function over the register. One was built for the dissolved cut-set
item, failed its own pre-committed gate, and was deleted; the audit records why a static reading of
the view definitions cannot rank the register.

And it does not re-argue the refresh. That axis is closed on the tree, section 10 measures it, and a
slice here proposing to make a registration's refresh cheaper would be work against a 43-second pass.
Anything this item does to the register it does for the reads above it or for the coherence of when a
row is added, never for the pass.

## Filed out of this item

Threads this item opened and did not close, each filed on its own terms rather than carried here.
None of them is a precondition for anything left in this item.

- **R899**, filed as the target itself and since respecified: this item's arms retired that target,
  and the item now makes a registration's alternative countable from the schema so the last lever
  stops being the first reached for. What it still inherits is the four confirmed supertype
  omissions, and the two plan defects found on the fresh capture whose rules were never examined.
  **Reopened from Ready to Spec on 2026-09-01, blocked on this item.** It prices one
  `meta_materialize` row at a time, and the collapse above takes the register away as the unit of
  account. The instrument survives the collapse and the framing does not, so it waits rather than
  ships against a foundation still moving. Its Round 3 states what the revision owes.
- **R900**, the argMapping relations spelling their own subject three ways, and the two names the
  schema already admits are inaccurate.
- **R901**, what `trailing_segments` is a count of. The proposal to collapse it to a boolean is
  refused by the column's own comment, which makes the question sharper rather than closed.
- **R895**, the four language-server surfaces that cannot tell a minted Connection type from an
  authored one. The provenance is a row now, so the mechanism is easy; what is owed is four
  decisions about what each surface should show.
- **R897**, typeId uniqueness, which is scoped to the supergraph and therefore cannot be a
  constraint on a relation capture writes one graph at a time.
- **R898**, the candidate tree stopping one level above the key column, which is this item's own
  defect one level lower down.

**And one more, whose subject changed twice while it was being investigated.** R902 takes `@node`'s
defaulted key columns. The gathering architecture does not block them: the gatherer that writes the
classpath census now leads the run, so the decode can reach everything the default needs. Two things
were then measured that the item should be rewritten around. The defaulted arm returns no rows at all
on the consumer schema measured and `intent_resolved_node_key_column` reads in 0.03 s, so there is no
read-cost case for capturing the default. And an attempt to capture it anyway failed on reading
`intent_resolved_type_binding` at capture cadence: that relation is owned by the gatherer that runs
last, so it is filled after the gatherer decoding `@node` has finished, and per-owner refresh does not
change that. The item is therefore about whether the arm earns its evaluation on every read, not
about capturing a fact.

**One thread was dropped rather than filed.** An earlier note in this item's working record proposed
splitting what claims a type is a node into three relations. The schema does not support the
description: the authored claim and the inferred one already live in `graphitron_node` and
`intent_inferred_node_type`, separate relations coalesced by a view, exactly as the provenance rule
requires. Whatever the proposal was about, it cannot be restated from the tree, and filing an item
with a body nobody can check is worse than the gap.

## Superseded items

Dissolved against this item and the audit, all on 2026-08-28. Each is named by subject rather than by
id, because the ids become gaps and the audit's section 8 carries what each established.

- The consumer-capture item, whose two registrations had already landed and whose remaining work was
  four transitions to record delivered code. Its framing was that the hour is a refresh cost and the
  fix is two registrations, and this item dissolved it as the purest example of the premise being
  argued against. **That judgement was wrong on the facts and is corrected here rather than left
  standing.** The refresh pass on the shipping DDL is 43.2 seconds; with those two registrations
  demoted it is 588.2. They were the fix, they were a refresh cost, and the item that said so was
  right about its own subject. What this item retains against it is narrower: nothing tried the four
  rungs above a registration on those two relations, so the pair shows a registration worked and not
  that it was the only thing that would have.
- The payload-verification item, filed as the consumer-capture item's escape hatch for an unpaid
  measurement. The measurement has since been taken, and the audit's section 10 carries it; the
  recipe it contributed for a reportable long capture is recorded under "Not in this item" because
  the boundary it drew turned out to be in the wrong place.
- The write-payload read-cost item, whose central question is answered and whose reader-count premise
  is dead, the count being zero across the family.
- The node-id decode-read item, whose lever question this item's ordering answers. Its second half,
  that no gate holds a figure over that read, is an obligation inherited here.
- The field-column-table inlining item, whose remaining question was whether a residual still earns
  work. It does: `intent_field_column_table` costs 9.2 seconds to read with the whole register in
  place and refuses a 120-second budget without it, so it is one of the seven residual relations with
  no cause named yet.
- The expression-keyed-join item, which is rung 2 above, filed narrowly against one of the two known
  instances.
- The inline-multiplicity reporter item. Plan size measures expansion directly, so the metric is
  superseded rather than repaired.
- The DDL performance-claims item, now a systematic consequence rather than a single catch.

**Not dissolved, and why.** The per-refused-row reader item is a caller-side loop in Java, at two
call sites the item names itself, not a store shape, and nothing here touches it. The `graphql_field` named-type index item
carries the doctrine for indexing a captured base table, which rung 1 needs rather than replaces. The
`meta_relation_reference` item is a measured, self-contained fix. The view-read census and bridge
closure is a gate, and the layer violation above is live evidence for it. The build wall-clock
guardrail is independent.

## What a reviewer should press on

Five places where this plan is weakest, named so the gate does not have to find them.

**This item's thesis has been measured against, and it is confirmed in shape while refuted in
scope.** Emptying the register does not work; the arms are tabled above. What survives is the claim
about which lever to reach for first, and that now has a direct
measurement rather than an argument. The workload's worst reader was fixed by restating its rule,
with the register untouched and every relation returning identical rows, and registering the same
rule instead would have bought the last tenth by burying the defect; the figures are under "The lever
order". A reviewer should
press on whether one rewritten rule entitles this item to a general claim. It does not: two further
plan defects were found on the same capture and neither has had its rule examined, only its storage
form tested, which is the very substitution this item exists to name.

**The exit claim changed late, and should be pressed hardest.** The item now ends on the collapse:
the derivation gatherer has no input the graphitron gatherer lacks, so `intent_` is `graphitron` and
`meta_materialize` has no subject. That is a stronger claim than the ownership argument it replaced
and it rests on less: one static walk of the shipped view definitions, by one investigator, with six
of the 114 rules unresolved because the walk cannot see through hand-written derivations. The press
is on the walk rather than on the conclusion. Whether six unresolved rules can overturn it. Whether
"reads nothing the other gatherer lacks" is the right test at all, given that it says nothing about
what a gatherer writes, and nothing about what orders two rules under one owner. The item names that
second gap and does not close it. What survives either way is the count that no rule in the family
reads the configuration corpus, Java sources or the compiler, which is three declared dependencies
that nothing uses.

**The read workload is a proxy, biased upward.** Every read figure here is `SELECT count(*)` over one
of the 39 relations a consumer names. That is the right set of relations and the wrong set of queries:
a real read carries predicates that can prune where a count forces the whole relation. The figures are
therefore upper bounds, sound for comparing arms because the same statement runs in each, and not
sound as a claim about what any consumer pays for a given relation. Before any registration is retired
on read evidence, the workload owes a faithful extraction from a traced `generate` run, and a reviewer
should treat that as a precondition on the target rather than a refinement of it.

**The evidence is one store, one schema, one investigator.** Every figure comes from a single capture
of a single consumer schema, and the reachability walk is a static analysis of the shipped DDL
against main sources rather than anything the build checks. It has not been reproduced by a second
party. Each lever was measured against a named alternative, which is the standard the audit sets, but
nothing here has the three-capture spread discipline R848 established, and the read-side figures in
particular are single readings on a shared machine. The separations that carry weight here are large
ones, over a hundred-fold in two places; the single-digit differences in the same tables should not
be read as measurements.

**The reachability walk rests on a claim about how consumers read the store, and the first draft
stated that claim too strongly.** It said no raw-SQL relation name appears in any main source. A
third access form does exist, jOOQ's `table(name(...))`, in five classes. Every site resolves to a
`meta_*` relation, `store_graph` or `INFORMATION_SCHEMA`, so no `intent_` relation is reached that way
and the walk stands, but the form is there and nothing stops it being pointed at a derived relation.
One of the five names nothing literally, `StoreProse` building the name from a variable, which is the
shape a grep-shaped gate cannot see. The audit is corrected. All of this makes the gate more worth
building rather than less, and whether it belongs in this item is a fair question for the gate: it is
the one claim in this whole subject that a build could hold, and everything else here is research
evidence.

## Reviewer findings

### Round 1 (2026-08-28, Spec -> Ready, reviewer session 01P2HFCFzA3YiKbaLjgXzet7)

Verdict: withhold. Two blocking findings on question one, one on question two, one traceability
finding, one figure to reconcile.

*What was checked and holds.* Every test symbol the item names exists under the name it gives:
`DerivedReadCostTest` and its `KNOWN_NON_MONOTONIC` set, `MaterializeRegistryGateTest` and
`everyTargetIsIndexedOrStatesWhyNot`, `FactSchemaGateTest.everyRelationLeadsWithItsPartitionDimension`.
So do the DDL objects the two key slices land on: `jvm_method`, `graphql_field.named_type`,
`graphitron_field_synthesis`, and the schema's thirty-five existing `GENERATED ALWAYS AS` columns, the
count the audit cites. Section 1's table sums to 15477.19 against its stated 15477.1, the ten
unreachable positions to 14759.5 as stated, and positions 14 to 18 to 15382.5 as stated, so the pass
arithmetic is internally sound. Each surviving item the supersession section says is untouched exists and is untouched; the four
citation redirects landed and read correctly. The dissolution itself is well argued and the decision to
file the evidence as a dated audit rather than in a file that dies at Done is right, and worth keeping
whatever happens to the findings below. The diagnosis, that a comparison between "evaluate" and "store"
cannot report that a third option was better, is the strongest thing in the item and I am not disputing
any of it.

**Finding 1 (question one: is the stated outcome reachable). "What changes when this lands" promises a
capture in the tens of seconds, and no slice in this item is measured against the positions that carry
99.4% of the pass.** Section 1 puts 15382.5 s in positions 14 to 18. Four of those five are in the
unreachable subgraph, so the only lever the item names over them is the registration precondition, which
this file explicitly defers. The reachable ten carry 717.6 s, so perfect success on every non-deferred
slice leaves over four hours on the audit's own store, and on the shipped DDL leaves the predicted 7126 s
of which the item's own section 4 citation says over 98.7% is positions 17 and 18. Slices 1 to 5 name
`intent_spelled_table`, `intent_argmapping_pair`, `intent_carrier_data_field`, the accessor hop and the
named-type sites; none of them is a payload or filter-role position, and nothing in the audit measures a
grain fix against one. There may well be a real claim here, that cutting expansion at the two grains cuts
the refresh of the unreachable views too, since section 3 shows five timed-out relations completing with
nothing materialized. But the item does not make that claim, and it is the whole distance between the
promised outcome and the slices. Resolve it one of three ways: state and evidence the claim that the
grain and key fixes reach positions 14 to 18; or restate what changes when this lands as what the slices
actually deliver, with the hours left to the deferred question; or pull the deferred slice into the item.
The third is now open in a way it was not when the item was drafted: the stated blocker, that R848 should
not be adjudicated while in review, cleared when R848 reached Done on 2026-08-28.

*Author's response.* Resolved, by none of the three routes offered, because the finding was right
about a bigger thing than it claimed. The promised outcome was not merely unreached by the slices; it
was on the wrong axis. The kept store re-priced on the shipping DDL refreshes in 43.0 seconds, so
positions 14 to 18 are not a gap this item has to close and the deferred slice is not the way to
close it: the tree closed it, with the cold-refresh split and the two payload registrations, and the
audit's new section 10 measures each contribution separately. "What changes when this lands" is
restated on the read side, which is the axis every slice was always measured on, and the target is now
a store that materializes nothing rather than one that refreshes faster. The finding's underlying
complaint, that the outcome and the slices were measured against different things, is what the rewrite
fixes, and it is now fixed with an arm rather than an argument: the outcome is measured by emptying the
register, applying the slices this item knows about, and timing the consumer read set. That arm comes
out worse than the register today, which is stated in the item rather than smoothed over, and the
residual it leaves is what slice 3 is scoped by.

**Finding 2 (question one: viability). Slice 0 gates the design of everything below it and runs against an
artifact this repository does not contain, with no stated way for an implementer session to obtain it.**
"Nothing below is designed until they are answered", and both determinations are reads "against the kept
2026-08-27 store". The audit says only that the store file is kept, 99 MB, with a SHA-256 recorded
alongside it; where it is kept, and by whom, is nowhere in either document. Meanwhile the item's own
"Not in this item" section establishes that no session working from this repository can take a fresh
consumer capture. Taken together, an implementer picking this up may be unable to start, which is a
viability question rather than a detail. Say where the store lives and how a session gets at it; or state
that slice 0 belongs to the session that holds it and cannot be handed off, and what the item does if that
session is not available. A reproduction recipe good enough to re-take the capture elsewhere would settle
it, but the item argues that is out of reach, so the location is the answer that is left.

*Author's response.* Resolved by taking slice 0 rather than by documenting a hand-off, so there is no
determination left for an implementer to be blocked on. The finding's premise turned out to be wrong
in the useful direction: the item claimed a capture on the shipping DDL was out of reach, and the
distinction it had missed is that refreshing an already-captured schema needs the kept store and a DDL
file, not the consumer's machine. The whole pass is therefore reproducible by anyone holding the
store, which is what the audit's section 10 does and documents. The store's location, its provenance
file and its recorded SHA-256 are stated at the end of that section anyway, and "Not in this item" is
rewritten to draw the line where it actually falls, between refreshing a captured schema and
capturing one.

**Finding 3 (question two: architecture fit). The lever order contradicts the one in
`docs/architecture/explanation/fact-model.adoc`, and no slice amends that page.** The page orders three
rungs: a captured fact top, a registration middle, and "a rewrite is the last rung, because it usually
changes nothing the planner cares about". This item orders five, with rewrite fourth and registration last
behind a new reader precondition. That is a doctrine change, not the gloss the item gives it ("the top rung
was never actually tried, so the ordering was doctrine rather than practice"): it demotes the middle rung
below the one the page calls last, and it adds two rungs the page does not have. The item is right on the
merits as far as the evidence goes, but if it lands as written the tree carries two orderings and the one a
contributor finds first is the page. Name the page edit as a slice and say whether the precondition sentence
goes in with it or waits for the deferred policy question, or say why the page stands as written. Related:
the item's own claim that the page "already states a hierarchy" should be narrowed to which part of it
survives.

*Author's response.* Accepted in full. The page edit is now slice 5, scoped to three edits and with
the two things it must not touch named. "The lever order" is rewritten to say which part of the page
survives (its top rung, and its reasoning about rewrites, which the audit's two refuted rewrites
confirm) and which part this item changes (where a registration sits, plus a rung the page does not
carry at all). The gloss the finding objected to is gone, and the change is labelled a doctrine
change. The finding's sub-question, whether the precondition sentence ships with the page edit or
waits for the policy question, is named in the slice as its one open question with a recommendation
rather than left implicit.
**Finding 4 (traceability). "The audit predicts four named regressions in `KNOWN_NON_MONOTONIC` become
removable" is a prediction the audit does not contain.** Neither the audit nor this file mentions
monotonicity or that set anywhere else, and the four pairs are not named. A reader can guess at them from
the two grains, the set's rows on `intent_argmapping_pair` and the two on `intent_spelled_table`, but a
guess is not what the Tests section should hand an implementer, and the count is a figure the Done gate
would be checked against. Name the four pairs in the item, or drop the count and keep the direction.

*Author's response.* Accepted, taking the second option and then the first as well. The count is
dropped because the audit does not contain it, and three pairs are named instead: the ones sitting on
relations the slices rebuild, with the set's own comments quoted for why each is there. The Tests
section now also says explicitly that whether any of the three becomes removable is for the Done gate
to measure rather than a prediction inherited from the audit, since the audit measures plan sizes and
wall clock and says nothing about scan-count monotonicity.
**Finding 5 (figure to reconcile, minor). The audit's two readings of positions 1 to 16 disagree by about
30 seconds.** Section 4 states 6293 s cold against 90.8 s analysed, from which the 69-fold ratio comes.
Section 1's table sums to 6262.6 s over the same sixteen positions, which is where this item's "removes
about 6172 seconds" comes from (6262.6 minus 90.8), so the item is consistent with the table and the audit
is not consistent with itself. Half a percent changes no conclusion, but the audit is the artifact that
outlives every file citing it, and a figure a later reader cannot reconcile is exactly what an audit is for
avoiding. Say which reading is the pass and where the other came from.

*Author's response.* Accepted, and settled from the capture's own log rather than by choosing between
the two readings. The per-registration lines were re-summed: positions 1 to 16 are 6262.6 s, all
twenty are 15477.2 s, and positions 14 to 18 are 15382.5 s, so section 1's table is the pass and is
internally consistent. The 6293 exceeds the pass by 30.4 seconds, which is exactly position 8 counted
twice, so it is arithmetic and not a second measurement. The audit now states this where the 6293
stood, and the 69-fold ratio is unchanged.
*Fixed in passing, per the reviewer-fix rule.* "The two per-refused-row reader items" was a stale count:
there is one such item, R812, which names both call sites in its own body. Corrected in the supersession
section; nothing else in that paragraph changed.

### Round 2 (2026-08-28, Spec -> Ready, reviewer session 01P2HFCFzA3YiKbaLjgXzet7)

Verdict: withhold. One blocking finding on question two, one small correction beside it. Every round 1
finding is addressed, and two of them were addressed by taking the measurement rather than by editing
the prose, which is the better answer in both cases.

*What was checked this round.* The reframing from cost to modelling is the right move and the detector
is the reason: a defect you can state off the DDL is enumerable where a wall-clock hunt is not, and the
item is honest about what that reframing found that the cost pass missed. The subtype-set inventory
checks out against `graphitron-model.sql` exactly as stated: ten capture tables carry `class_name` and
`method`, eight are `*_arg_mapping_pair`, six carry `table_ref` with `graphitron_routine` spelling the
seventh `routine_ref`, three are `jvm_*_type_ref`, and `graphql_field`, `graphql_argument` and
`graphql_directive_argument` share all eight of the named columns. `meta_materialize` seeds twenty-two
registrations. `intent_argmapping_bound_parameter_type`'s `hosted` CTE is six arms differing only in the
directive table joined and the `site` literal filtered, as described; its seventh `UNION ALL` is in the
separate `resolved` CTE and is not one of the six. `intent_declared_type_ref` carries the comments quoted
from it, and reads as the worked confession the item says it is. The 43.0-second re-pricing, the
attribution arms and the store's location are in the audit's section 10, and the 6293 correction landed
where it stood. The read-side honesty in "What changes when this lands", stating an arm that comes out
worse than the register today, is what makes this item trustworthy on the rest.

**Finding 1 (question two: what the implementer builds). The detector's union-site column is inflated
at two of its four rows, and slice 1 is scoped by one of the inflated numbers.** The detector confirms
an omission when a view `UNION`s three or more members of a subtype set, with no check that the set's
shared attributes are what those arms project or join on. Membership overlaps between sets, so a view
that unions six directive tables to reconstruct one fact is counted as a reconstruction of every set
those tables belong to.

That is not hypothetical. Slice 1 says the table-or-routine reference is reconstructed at four sites and
makes "repointing only the first leaves three reconstructions standing" its main correction over the
audit's class A. Grepping the three: `intent_condition_param_extraction`, `intent_condition_table_parameter`
and `intent_argmapping_bound_parameter_type` contain no occurrence of `table_ref` or `routine_ref` at all.
All three union those tables for `class_name` and `method`, which is row 1 of the table and slice 3's
work. A table-or-routine-reference supertype cannot repoint any of them, so the count is one site and the
slice's central correction dissolves. The same rule inflates row 1 the other way round: of its six union
sites, only those same three mention `class_name` or `method`; `intent_spelled_table_live`,
`intent_argmapping_pair_live` and `intent_condition_membership` union member tables for other facts
entirely. Rows 3 and 4 survive the tightened rule at one and two sites.

Three consequences, and the reason this blocks rather than being a note. The count that scopes slice 1 is
wrong, and slice 1 is the slice with the strongest isolation behind it, so the item should not ship
telling an implementer to repoint three views that cannot be repointed. Slice 3's prediction inherits the
same arithmetic: "repoint the six sites and those three residual relations should move" is a prediction
over three sites, which changes what a failed prediction would mean. And the gate this item says it most
owes is specified as this detector, so it would ship the same false positives into the build, which is
the one place they would be expensive: a gate that confirms an omission from set membership alone will
fire on a supertype the view does not reconstruct, and attribute a real reconstruction to the wrong set.
The fix looks small and is the author's to take: require the union's arms to project or join the set's
shared attribute group, re-derive the union-site column under that rule, and re-scope slices 1 and 3 to
what it gives. The audit's section 11 owes the same correction in its own table and in its "there are
four" sentence, since the audit outlives this file and the detector script is cited from it.

Nothing about the defect thesis moves. Four subtype sets with no supertype still exist off the DDL, the
genuine reconstructions are still there, and the modelling argument does not depend on how many readers
happen to be paying today, which is the item's own point.

*Author's response.* Accepted in full; the finding is right and the defect was mine. Verified before
fixing: `intent_condition_param_extraction`, `intent_condition_table_parameter` and
`intent_argmapping_bound_parameter_type` contain no occurrence of `table_ref` or `routine_ref`, and of
the method-bearing set's six reported sites only those same three name `class_name` or `method`. The
scan now requires the view to name the set's own attributes as well as to union three or more of its
members, which puts row 1 at three sites and row 2 at one and leaves rows 3 and 4 untouched.

Everything the inflated numbers scoped is re-scoped. Slice 1's "main correction" is deleted rather than
adjusted, because there was nothing to correct: the audit's class A named the one reconstruction that
exists and was right, and the slice now says so and says the claim against it was this scan's defect.
Slice 3's prediction is over the three sites it can actually be run against. The gate specification
carries the attribute check as one of three parts, with the measured consequence of omitting it stated
where an implementer will read it, and with the further limit that even the tightened check is name
matching over a view body rather than proof that the attribute is projected from the unioned arms.

The audit takes the same corrections in its section 11 table and prose, and it now records both
calibration errors together, since they pull in opposite directions and the pair is more instructive
than either: the first hid a real set, the second invented reconstructions for sets that had none, and
both were caught only by checking output against the DDL by hand.

**Finding 2 (small, question one). The access-form claim is off by one class and understates the case for
the gate it argues for.** Round 1's finding on this was answered well, and the corrected claim is nearly
right: `table(name(...))` appears in five main-source classes, not four (`StoreProse`, `StoreCatalog`,
`Materializations`, `MaterializeDependencies`, `ViewReferences`). The conclusion holds, every site names a
`meta_*` relation, `store_graph` or `INFORMATION_SCHEMA`. But one site does not name anything literally:
`StoreProse` builds the name from a variable, `table(name(relation.toUpperCase(Locale.ROOT)))`, bounded to
`metaRelations(dsl)` at the call site. That is the form a grep-shaped gate cannot see, and it is worth a
sentence where the gate is proposed, because a gate that only refuses a literal `intent_` name would pass a
computed one.

*Author's response.* Accepted, both halves verified. Five classes carry the form, the fifth being
`ViewReferences`, and `StoreProse` line 61 builds the name from a variable. Corrected in the item and in
the audit's section 2, with the resolved-to set widened to include `INFORMATION_SCHEMA`, which the
four-class version had not accounted for either. The computed site is now a sentence on the access-form
gate bullet rather than only a correction: the check belongs on what the argument can resolve to and not
on how it is spelled, which is a real constraint on how that gate gets written.

*Fixed in passing, per the reviewer-fix rule.* The Tests section's account of `KNOWN_NON_MONOTONIC`'s
remaining rows was a miscount: the set holds thirteen, of which the three named leave ten, and those are
eight on `intent_field_reference_step_hop` plus the node-id instruction's plus the `intent_errors_field`
pair, which shares its comment with the argmapping row. Corrected in place.

### Round 3 (2026-08-28, Spec -> Ready, reviewer session 01P2HFCFzA3YiKbaLjgXzet7)

Verdict: sign off. Both round 2 findings are resolved at the source rather than papered over, and the
resolution of the first is better than the finding asked for: the detector grew a third part, the two
inflated rows are re-derived under it, everything they scoped is re-scoped, and the two calibration
errors are recorded together in the audit because they fail in opposite directions. Deleting slice 1's
"main correction" rather than adjusting it is the right call, since there was nothing to correct.

*Re-verified against `graphitron-model.sql` this round.* Of the method-bearing set's six previously
reported sites exactly three name `class_name` or `method`: `intent_argmapping_bound_parameter_type`
at six arms and `intent_condition_param_extraction` and `intent_condition_table_parameter` at five
each, which is the table's new row 1 and slice 3's new prediction. `intent_spelled_table_live` is the
only view naming `table_ref` or `routine_ref`, so row 2 is one site. Rows 3 and 4 are unchanged at one
and two, `intent_jvm_ancestor` and `intent_declared_type_ref` being the two. Five main-source classes
carry `table(name(...))` and `StoreProse:61` is the computed one, as the item and the audit now say.

**One thing for the implementer to carry into slice 1, not a finding against the plan.** The one
argMapping reconstruction covers seven of the set's eight members: eight arms, with
`graphitron_field_condition_arg_mapping_pair` appearing twice for `FIELD_CONDITION` and
`INPUT_FIELD_CONDITION`, and `graphitron_argument_reference_for_step_arg_mapping_pair` absent. That
table's own comment says capture is total across every SDL-legal location even though today's validator
rejects the coordinate, so the eighth member can hold rows the current view does not return. The
supertype is over the closed set of eight; the repointed view has to keep returning 108. Those two are
consistent only if the view keeps the eighth arm out, so the discriminator has to let a reader exclude
it, and a supertype that silently folded it in would break the row-identity anchor in the one direction
the anchor exists to catch. The plan already forces this through its anchor rather than leaving it open,
which is why it is a note and not a finding. The arm count is corrected in the slice and in the audit's
section 3 table.

*Fixed in passing, per the reviewer-fix rule.* Two stale arm counts for that view: "seven arms" in
slice 1 and "7-arm `UNION ALL`" in the audit's class A table, both now eight arms over seven of eight
members.
