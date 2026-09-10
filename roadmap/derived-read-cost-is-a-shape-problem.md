---
id: R876
title: "Expensive derived reads are a modelling defect: every rule needs an owner, and once ownership is computed the derivation gatherer is unearned and meta_materialize has no subject"
status: In Progress
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-28
last-updated: 2026-09-08
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

**One sharpening from running the arc, and it makes the remaining work cheaper than the paragraph
above implies.** "A relation the capture family never wrote" turned out to overstate the problem.
Capture read the facts; what nobody wrote was a relation stating them at a grain. Every relation this
arc has landed was filled by a select over populations already in the store, and the one candidate
for a genuine capture gap, the written order of a field's directive applications, was already in
`graphql_field_directive` with every position. So the work is placement, not capture, and the
architecture is why: the graphitron gatherer runs last, after both crawlers have flushed, as a
sequence of stages in an order it chooses, with the whole transcription and the whole catalog in
hand. A rule it can compute in a stage needs to be neither a view, nor a registration, nor a reader's
join.

That also says what the `intent_` family is. Not a layer with 132 relations to tidy, but the shape a
pipeline takes when the facts are not written down: 107 of them are views, and the ones this arc has
reached have been deleted rather than improved. The remaining sequencing follows from that reading
rather than from their names.

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

Sixteen slices, summarised by what each established rather than by what it changed; the diffs are in
the git history and the design arguments that still bind are in the sections below.

[cols="3,5"]
|===
| slice | what it established

| supertypes captured
| eight per-site argMapping tables and three classpath type-reference tables became one relation
  each, written by capture rather than reconstructed by every reader

| join keys stored
| two keys that existed only as expressions became columns an index can serve

| grains given
| five of the twenty registered targets gained a primary key, against none at the start

| the argMapping right-hand side modelled
| a written path resolves by equality against a candidate tree instead of by a walk each reader
  spelled itself; the family moved onto schema coordinates

| the gathering architecture
| one flush at the end became a flush per gatherer, which is what lets a stage read what the stage
  before it wrote

| the macro expansion left the transcription
| `graphql_type` and its siblings hold what the author declared and only that, with the expansion's
  output beside it

| two registrations retired
| one on a structural argument, one on the blindfold argument, which is the first evidence for this
  item's own thesis

| the node family became entry and anchor pairs
| with the type binding, and it is where the pattern this arc runs on was named

| the coordinate family took the specification's vocabulary
| `graphql_element` over four anchors, `element_kind` in the specification's own words, the field and
  input-field split settled at the write

| the element family the generator emits
| `graphitron_element` over type, field and argument as tables, and `intent_expanded_type` and
  `intent_expanded_field` deleted with their forty read sites repointed

| the minting family
| three relations keyed by the coordinate that coined each row, replacing four; precedence as a
  column, the machinery-shares-its-type's-fate anti-join, and the pagination arguments recorded for
  the first time

| contested mints refused
| two applications minting one coordinate with different payload get neither, and
  `graphitron_minted_conflict` says which coordinate and how many readings

| the chain applications ordered
| the written order across directive names stated as rows, and the chain walk reads it instead of
  comparing source positions
|===

Three findings from those slices outlived the slices themselves.

**A relation the store could not key at.** A graphitron relation naming a coordinate had nowhere to
point, because `graphql_*_element` holds what the document declares and a macro mints coordinates it
does not. `graphitron_field_table` was written with that key and the build failed on
`QueryFilmsConnection.nodes`. Three relations have since taken back a coordinate key their own
comments said they gave up only because the population was a view.

**H2 refuses a duplicate source key in a `MERGE`, and that is not always what you want.** The upsert
filling an anchor raises rather than picking a winner, so a schema an author can write turned into a
capture that produced nothing. `WHEN MATCHED THEN DELETE` fails identically, H2 matching against the
pre-statement target. The resolution is to withhold the contested coordinate and record it.

**Capture must not throw on author input, and the boundary is sharper than it looks.** Two carriers
naming one connection over different element types disagree about that connection's own fields.
Assembly rejects that schema with a diagnostic; capture runs before assembly and for readers that
never run it, so refusing there leaves an author mid-edit with no store rather than with a store and
a diagnostic.


## The entry and anchor pattern, and the plan it makes possible

The slices above each fixed a rule. What the node and field arcs added is the shape the fixes have in
common, and stating it is what turns a sequence of repairs into a plan. What follows is the rule,
then how it was found, then the plan it makes possible, then the slice that comes next.

### The rule

**An entry holds what the author wrote; an anchor holds what resolved.** The entry is keyed to the
coordinate the directive sits on, carries unconstrained nullable columns, and holds rows the
directive did nothing for, which is exactly why it can be keyed there. The anchor carries a primary
key and foreign keys into what the resolution reached. What an author got wrong is the anti-join
between the two, and it is the only place a diagnostic can find it.

**An anchor's population is not its entry's.** `graphitron_node` unions a declared arm and a
published one, `graphitron_node_keycolumn` ranks three tiers, `graphitron_field_table` has three disjoint
target rules and only one of them reads a directive. A pair is not a decode with its resolution
bolted on; it is two relations answering different questions that happen to meet at an anti-join.

**The SDL walk writes every entry; the graphitron gatherer writes every anchor.** Settled 2026-09-07,
and it is the ownership rule applied to base relations rather than an exception to it. The fact model
already decides an owner by asking what a row is a function of, and it has only ever run that
question over views. An entry is a function of one document and nothing else: it joins nothing,
resolves nothing, defaults nothing, and reads no corpus but the document the directive sits in. So
its owner is that document's crawler. An anchor is the opposite shape by construction, `Nodes.derive`
joining an entry against `sql_node_metadata`, and could not run inside a walk at all. The two halves
are the two answers to the ownership question, and the family they share was never what the question
asked.

What this asks of the SDL gatherer is not a new capability. It is already a graphitron gatherer in
the sense that matters: it holds the parse, it sees a field's applications in written order, and it
is expected to understand what a graphitron directive means well enough to unpack it into the entry
relation that directive belongs to. Most of what it writes is in the document's own vocabulary and
the rest is in graphitron's, and that is two vocabularies over one document rather than two passes
over one corpus.

**A family names a vocabulary; an owner names a writer.** These have been separate columns in
`meta_relation` from the start, each with its own foreign key, and the correlation between them is a
property of the current population rather than a rule: the catalog gatherer already owns two
prefixes, `sql_` and `jvm_`. What is new is one prefix with two owners, and what makes it legitimate
is that the split is exactly entry against anchor, which is the split between needing only the
document and needing the store. Two pages state the old correlation as though it were the rule and
both need restating with slice one. The fact model's ownership section says the two families have "a
gatherer each" and that the decode "runs after the transcription has flushed rather than inside its
walk"; its corpus-isolation section said the decode is outside that gate's scope because its gatherer
may reach the catalog legitimately. The first becomes false when the writer moves and still says the
old thing. The second stayed true of the 15 and became false of the 56, and was restated with the
widening rather than with the move, because the widening is what made it false.

**The name says which half, and it does now.** Every as-written relation carries the `_entry`
suffix. Three of the 56 did; the other 53 were renamed on 2026-09-07, as one transform over exact
identifiers rather than one relation at a time, the writer having moved as one thing rather than
relation by relation as the plan first expected. The pattern's central operation is the anti-join
between the two halves, and a reader who cannot tell from a relation's name which half it is cannot
find that join, which is the whole justification and it does not depend on any gate.

**What the suffix bought on top of that is that two gates stopped carrying a list.** Enumerating 56
relations inside a test is the list that goes stale the first time somebody writes the 58th, and it
did not survive first contact: the fixture's two lists were written on 2026-09-07 and were edited
again the same day. The entry half is read off the generated model by name now, and the anchor list
is simply gone: the complement of the suffix is a bag rather than a half, holding both the stages'
relations and a view nothing writes, and nothing needed it once the partition case went. That case
is what the suffix retires, it being true by construction once the name decides which half a
relation is in, and what it was really protecting moves to `EntryNamingGuardTest`: a scan of the
decode's own source holding it to naming only suffixed relations, since a relation the decode writes
is a relation whose rows are a function of one document and the name has to say so. Verified failing
by naming an anchor inside the decode. What no source scan can say is the other direction, a
suffixed relation some stage writes, that being a read and a write it cannot tell apart; the
declaration pass says it properly by giving every relation a declared owner, and the two disagreeing
is a check neither could be alone.

**Entry, match, and the anti-join between them.** The pattern has a third member the sections below
name only for the argMapping family, where `graphitron_argmapping_entry` is what was written,
`graphitron_argmapping_candidate` is what may be written and `graphitron_argmapping_match` is where a
written one landed, with the match's own comment stating the rule: a resolution and not a rejection,
and an entry naming nothing has no row. The route family is the same triple at two grains rather than
one, an application resolving to one node or to several steps, so the match carries the chain
position back to the entry. That is what gives provenance a join instead of a reconstruction, lets a
rule about the well-formed schema join matches and never filter, makes the diagnostic an anti-join at
the application grain, and leaves a schema with one unresolvable application still capturing
everything else.

**A graphitron relation cannot key at `graphql_*_coordinate`.** Macro expansion mints coordinates the
transcription does not hold, so a foreign key there excludes exactly the fields a connection is made
of. `graphitron_field_table` was written with one and the build failed on `QueryFilmsConnection.nodes`.
Every graphitron relation has to key through a macro-aware anchor over the expanded coordinate set,
and for a while no such anchor existed: `intent_expanded_type` and `intent_expanded_field` were
views, so nothing could point at them. The element family below is what the rest of this arc keys
through, and it is where those two went.

**Lifecycle is a cascade at the minted grain and a refcount at the anchor grain.** A minted row can
be coined by several sources: `PageInfo` is one type in the schema however many `@asConnection`
applications called for it, two in a two-connection schema. This arc first read that as proof the
coining direction could carry no `ON DELETE CASCADE` at all. It is not, and the whole of the
difference is where the provenance sits. Held in a relation beside the minted row, the coining is a
set with no single parent and nothing to cascade from. Held in the minted row's own key, every minted
row has exactly one parent, the coordinate whose directive coined it, and cascades from it cleanly.
What still cannot cascade is the anchor, whose row must survive while any source still coins it, so
that grain is refcounted and swept. The two mechanisms answer different questions rather than
competing, which is only visible once the key carries the source. Neither is exercised today:
`StoreRefresh.clear` deletes every graph-keyed relation on every capture and derives that set from
the presence of a `GRAPH_NAME` column, so a new graphitron relation is rebuilt wholesale by default.
The sweep is owed the day an incremental path exists, and what matters until then is that the cascade
declared now is the one that will still be right.

That day has a number. R872 deletes the wholesale arm outright, so "not exercised today" is true now
and false on its first phase, and the refcounted grain becomes live rather than declared. The two
items reach the same line from opposite sides and agree on it: R872 sorts the family by whether a
row's existence is a function of one source, which is the entry test under another name, and the two
decompositions coincide exactly. Every one of its 40 source-owned `graphitron_` relations is an
entry, no anchor is source-owned, and 40 owned plus 24 descendants plus 7 graph-keyed is 56 entries
plus 15 anchors read a second way. What that session found while measuring is filed in that item's
own findings rather than argued here, including one blocking finding: its phase four would refuse
the second graph of any store, the bundled `directives.graphqls` being a schema-file source that
carries a membership per graph.

### How the rule was found

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
| `graphql_element`, the supertype the family always implied, and the four subtypes pointing at it

| nodehood, first attempt
| six of the seven nodehood relations read `intent_resolved_type_binding`, which unions the `@table`
  decode with where a routine chain lands, so a `@node` on a routine-returned type was table bound as
  far as they could tell and collected a key it had no claim to
| `graphitron_tabletype`, the settled `@table` binding, and eleven `candidates = 1` predicates with it

| nodehood, second attempt: the node id pair
| the rule reads scope relations that are registered targets, so a gatherer cannot see them at all,
  and those in turn read a reference walk that is itself unmigrated
| `graphitron_field_table`, the endpoints. The route and the reference decode under it are still owed

| the reference step decode
| a foreign key into `graphql_field_element` refused `QueryFilmsConnection.nodes`, a field macro
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

### The plan

Nine slices, in the order they will be taken. Two things about that order changed on 2026-09-07, when
the writer decision landed. The entry migration is now first, because every slice after it either
adds an entry, renames one or reads one, and a relation landed in the gatherer today is a relation
that moves later. And the macro consumer moved from last to third on its own argument, which is that
it is the first consumer this arc retires and the size of what a retired consumer turns out to know
is worth learning early rather than at the end. Everything else keeps its relative order.

This is the order and the scope, and it is not an estimate. The finding above stands: a prerequisite
is discovered by something refusing, so what a slice costs is known once its first relation refuses
to be written and not before. What the list settles is what gets attempted next, and what each
attempt owes before it can claim to be done.

1. **The entry migration.** `SdlFactCapture` writes the 56 relations the decode used to write,
   from the parse it is already holding, and `GraphitronFactCapture` keeps the 15 its own stages
   write. The writer move and the `_entry` suffix both landed 2026-09-07, behind the fixture and the
   widened gate, which closes the slice. Specified in full below, because what it moved was measured
   before the decision was taken.
2. **The two hierarchies**, described below, which are one mechanism applied at two grains. The
   named-type half landed 2026-09-08: `graphql_type` carries the unique, `graphitron_tabletype`
   carries `named_type_kind` and references the pair, and `TableTypes` reads the kind so a `@table`
   on a union, a scalar or an enum resolves to no row instead of failing a write. The edge cascades,
   which is one of the 39 R872's phase four owes and is why that phase now owes 38. What the slice
   withdrew is recorded below with the reason. A named
   type is one of the specification's six kinds, and boundness, participants and fields are facts
   each legal for some of them. A producer is what runs to fetch a field's rows, which most fields
   do not have: a table-bound child is absorbed into its parent's query, and only a root slot,
   `@service` or `@splitQuery` makes a field a fetch origin. Four values survive, three of them
   carrying a subtype relation, and they are exclusive at a coordinate, so a coordinate carrying two
   should be unwritable rather than rejected downstream. Both hierarchies are a unique on the
   anchor's key plus its discriminator, and a composite reference from each dependent.
   `intent_resolved_type_binding` dissolves with them, being a union that lets a routine's result
   pass for a type binding.
3. **The macro arc's consumer**, which is the first one this arc retires. `MacroCapture` states the
   `@asConnection` expansion as rows and `ConnectionPromoter` derives the same expansion again in
   the generator, 732 lines against 403, so the naming, the field shapes, the nullability mirroring
   and the precedence against an authored name are each decided twice. One value is pinned across
   the boundary, the default page size, and the thirteen Relay description strings exist verbatim in
   both modules with nothing holding them equal. Three capture gaps stand in the way, and each is a
   fact the store should hold anyway. A structural connection carrier, a field whose return type is
   already Connection-shaped with no directive on it, is promoted by the generator and has no row
   here, `graphitron_connection_entry` being the directive decode alone. `@asFacet` is a second macro,
   minting a facets type and a facet-value type per carrier, and `graphitron_facet_entry` is likewise only
   its decode. And the descriptions belong on the minted row, which already carries the column. The
   slice closes those, then cuts `ConnectionPromoter` along the seam it already has: deciding what
   to mint becomes a read, and `rebuildAssembledForConnections` stays, building graphql-java objects
   out of rows rather than out of a second derivation. How those 732 lines divide between deciding
   and constructing is unmeasured, and measuring it is the first thing the slice does, because it is
   what says whether this is one slice or two. The falsifier comes first, an agreement gate over a
   corpus that fails if the two populations differ today, because claiming agreement without one is
   how the page size came to be the only pinned value.
4. **The route family**, described below, which now carries three things that were separate: the
   target fact onto the field anchor, Route and Step as grains of their own with
   `graphitron_field_table` dissolving into them, and the entry-and-match pairing named.
   `graphitron_field_chain_application` becomes `graphitron_field_chain_application_entry` and is
   the 57th entry, written by the walk with the rest: it is derived today by ranking source
   positions and needs an invented tie-break to make the rank total, where the walk sees a field's
   applications in written order natively and needs no rank at all. This slice also carries the
   emitter migration, because `intent_field_chain_node.seq` names generated SQL aliases through
   `ReservedAliases.chainHop`, so the complete chain arrives with its consumers rather than as a
   change underneath them.
5. **The reference decode on the field sites**: `graphitron_field_reference_step_entry` and
   `graphitron_reference_for_step_entry`, which differ only in the key saying which directive owns the
   row. The two argument-site relations beside them are item 6's, not this one's.
6. **The argument and input-field sides**, deliberately last of the decodes. Their `@reference`
   support differs from the output-field side, a routine segment has no meaning there, and the
   multi-table polymorphic root fans the departure out per branch, so folding them in before the
   field side is settled would model three unlike things as one.
7. **The declaration pass**, which is the documentation and the audit in one. 249 relations carry no
   `meta_relation` row, and writing one forces a grain to be named, after which the existing gate
   checks the primary key against it. Ordered after the dissolutions above and not before them:
   `intent_` alone is 129 of the 249, and most of those are views the slices above delete, so
   declaring them first would be writing rationales for relations about to go. The entry half is
   where the pass starts when it arrives: 56 relations whose owner and grain slice 1 has already
   settled, so the only thing left to write is the prose, and the declared owner becomes the second
   statement of what the suffix says.
8. **The register**, which is downstream of all of it and is where this item's own thesis is
   settled: a registration either has no rule left to buy or it is re-argued on its own evidence.
9. **The materialization targets**, whatever of them is still standing. Fifteen of the twenty-five
   `intent_` tables carry no primary key at all and are exactly those targets, so nothing refuses a
   duplicate row in them and the gate that checks a key against its grain is vacuous on every one.
   That is a real hole and it is deliberately last: the register is item 8's subject and most of
   these dissolve with it, so keying them now would be hardening relations on their way out. The
   existing tests carry their correctness until then, and what has not dissolved when the arc
   reaches this point gets a key and a grain.

**There is no capture gap in the slices that only move facts, and the one candidate turned out not to
be one.** An earlier draft named the written order of directive applications as the single fact a
gatherer stage could not read out of what is already captured. It can. `graphql_field_directive`
holds every application with its source position, so the order is a rank over those positions;
measured on the manual's own sandwich, ranking them yields `@reference#0`, `@routine#0`,
`@reference#1` in exactly the written order. The chain walk reports two nodes where the manual
describes four not because the fact is absent but because it reads the per-directive decodes and
anchors on the routine, admitting only applications that follow it.

`graphitron_field_chain_application` states the order as rows, which is worth doing on
`graphitron_field_navigation`'s terms, a reader joining the answer rather than re-ranking by
position. But it is placement, not capture.

The claim holds for every slice that only moves facts between relations and stops at slice 3, and the
exception is the interesting part. Placement is all the arc owes for as long as it moves facts
between relations; the moment it retires a consumer, that consumer turns out to know things the store
never wrote down. A structural connection carrier and the `@asFacet` mint are both facts the
generator has been deciding for itself, invisible from inside the store because nothing here ever
needed them. That is the general shape rather than two oversights, and it is why the macro consumer
moved up the list: every consumer this arc eventually retires will surface its own set, and the
sooner one of them does the sooner the size of that class is known rather than assumed. Slice one is
a writer move rather than a consumer retirement, so it is not expected to surface one, and the
shadow-sink diff specified below is what would say otherwise.

The evidence for the ordering is in two audits:
`roadmap/audits/2026-09-05-coordinate-facts-as-relations.md` names the grains and the four tiers at
which an illegal state can be refused, and
`roadmap/audits/2026-09-06-graphitron-family-grain-inventory.md` places every relation of both
families against them and measures the dependency graph.

### Slice one: the entry migration

**Where the decision came from.** The argument was raised by session `01QDMJx75yxRTJACZ9fW2D5S` on
2026-09-06 while mapping the incremental-refresh arc, offered to this item rather than adopted into
it, and it is adopted here. What it argued is in the rule above. What it did not have, and what was
measured before the decision was taken, is the population the rule cuts.

**The population it cuts.** 71 `graphitron_` tables ship today and one view. 56 of the tables are
written by the decode arm and are the entry half; 15 are written by a deriver or by macro expansion
and are the anchor half. Three of the 56 carry the `_entry` name. The decode arm reads the five
directive transcription relations, their argument siblings, and `graphql_type` for the set of input
object types, and it reads no catalog at all, which is what makes the whole of it entry-shaped
rather than most of it. The widened corpus-isolation run below is the check on that claim, and its
49 empty relations are the reason this slice starts with a fixture rather than with the move.

**The seam is already cut.** `GraphitronFactCapture.capture` is two halves either side of its own
first `sink.flush()`. Above the line are five methods, one per directive location, which fetch the
transcription back and write 56 relations. Below it are eight stages that join, resolve, read the
catalog and read each other, and write 15. The 56 above the line are the entries and they move. The
15 below it are the anchors and they stay, with the gatherer, its two declared dependencies, and its
javadoc's argument for existing, which is an argument about anchors and always was: a decode driven
by callbacks "cannot join the coordinate it is decoding against anything" is exactly right about a
resolution and says nothing about a relation that joins nothing.

**What moving them deleted, and the prediction was wrong about which imports go.** The decode's input
was a printed literal. `SdlFactCapture` holds a parsed `Value` and prints it with
`AstPrinter.printAstCompact` into `graphql_type_directive_arg.value_sdl`; `GraphitronFactCapture`
called `Parser.parseValue` per argument to rebuild a synthetic `graphql.language.Directive` it could
read. That round trip was the second pass and it is gone, along with the five per-location fetch
loops, the grouped argument fetch beside them, the two rebuilders, the `inputTypes` query, which
asked the transcription for something the walk knows by standing inside an
`InputObjectTypeDefinition`, and the quarantine arm for a stored literal that would not parse back,
which was a failure mode of reading applications out of the store and had no other cause. 209 lines
deleted against 17 added. What does not go is the twelve `graphql.language` imports an earlier draft
predicted: the decode still reads an AST, which is the whole point, and it is `graphql.parser` that
leaves, two imports of it, along with `org.jooq.Record` and eleven static references to the
transcription relations the decode no longer queries.

**One gate has widened and a second becomes reachable.** `CaptureCorpusIsolationTest` holds a
crawler's rows about its corpus to being identical with and without a catalog, and `b5c9ff262` cut
its scope to `graphql_` alone, correctly, because a gatherer that may read the catalog cannot be held
to catalog-independence. An entry can be, so the entry half rejoined that scope on 2026-09-07, and
the widening is a check recovered rather than a tidiness gain. It landed ahead of the move rather
than with it, which is the point: the scope is a property of the rows and not of their writer, so
the check ran first against the arm the move was about to retire and then, unchanged, against the
one that replaced it. Verified by making one decode catalog-dependent and watching the gate go red
naming `graphitron_pivot_entry`, on the entry corpus alone and not on the transcription's own fixture,
which is the widening being what caught it. The second is `MetaDeclarationGateTest`'s corpus check,
which exempts every graphitron-owned relation today because the graphitron gatherer has no
`meta_gatherer_corpus` row and crossing is its job. An sdl-owned entry is not exempt: its grain has to
live in the sdl corpus, which is a check the entry half has always been able to pass and has never
been asked to. That one does not fire on the move, because the gate binds per declared row and none
of the 56 is declared. The move makes them declarable and the declaration pass collects it.

**One entry is keyed by its value rather than by a site, and the rule survives it.**
`graphitron_spelled_reference_entry` holds each distinct table or routine spelling once across the seven
sites that can write one, deduplicated at capture, because a spelling's resolution does not vary by
site. It is as-written in every other respect and asks nothing of the catalog, so it moves with the
rest. What it shows is that "keyed to the coordinate" is a property of most entries rather than the
definition of one; the definition is what the rows are a function of.

**The falsifier came first and it has been built.** Widening `CaptureCorpusIsolationTest` to the
entry half passed before any code moved, measured 2026-09-07 over the 56 relations that scope
selects, and it passed for the wrong reason: eight of the 56 held a row under that gate's fixture,
so 48 of them agreed by being empty twice, and a gate that cannot fail is what this arc has twice
mistaken for evidence. `EntryFamilyFixture` is the answer to that, landed 2026-09-07 ahead of the
move, and the widened gate runs against it rather than against the transcription's own fixture. It
applies every graphitron directive the decode writes a relation for, across two documents, and
`EntryFamilyCoverageTest` holds it to writing a row into all 56 entry relations and to some of them
holding rows from both documents, which is what a per-source delete needs to have a subject at all;
nine do, measured, though what the gate holds is that at least one does. The differential runs over
86 relations of which 83 are populated and none differs between the arms, and the shipped gate
asserts that all 56 of the entry half are among the populated, so a fixture that stopped applying a
directive fails on the relation rather than going quietly empty. The claim that the entry half reads
no catalog now has a check that could have said otherwise.

**Two things the fixture settles beyond the widening.** The classification this slice rests on is a
stated artifact rather than a grep: 56 entries against 16 anchors, the sixteenth being the match
view. It was enumerated in the fixture and gated against the generated model while it was a list,
and the suffix has since made it a name the model itself carries. And the entry half's source
attribution is measured rather than assumed: 40 of the 56 carry a `source_name` and 16 are
descendants that carry none, which is exactly the split the refresh work reads the family through.

**The second falsifier was the agreement between the two writers, and it took the additive shape.**
The walk wrote into a sink of its own beside the gatherer's arm, `EntryWriterAgreementTest` compared
the two populations relation by relation and row by row, and the gatherer's arm went when they
agreed rather than before. They agreed: over the entry fixture, which reaches every argument shape
the decode reads, and over a second corpus whose applications sit on extension sites, where the walk
stands on the site and the gatherer rebuilt it from three columns. So the round trip through
`value_sdl` was as lossless as its javadoc claimed, which nobody had checked. Computed columns are
excluded from the comparison and asked of the store rather than of the generated model, which models
a generated column as an ordinary field; the gate was verified failing by dropping one directive
from the walk's arm. It shipped in the additive commit and went with the flip, one writer leaving
nothing to compare, which is what a falsifier for a move is supposed to do.

**One way for the two to disagree was not covered and could not be.** First-wins is decided in the
order each arm meets the applications, so two applications at one claim key could in principle be
resolved differently. No corpus that reaches the fixture has such a pair: a second application of a
single-application directive is what makes a schema fail assembly, and both arms number the ordinals
from the same column anyway, the walk computing it and the gatherer reading back what the walk
wrote.

**This restored a mechanism the arc itself retired, and the difference the second time is the whole
of why it is right.** The five `captureXDirective` callbacks the walk drove the decode through went
when the decode stopped being a visitor, recorded under "Retired vocabulary" below. What was missing
then was the split: the family moved as one undivided thing, so the anchors' need for the store
decided the entries' home with it. The callbacks are back for the entry half alone, and the anchor
half stays exactly where that move put it.

**R713 was refused by this slice and its thesis is honoured by it.** That item's complaint is that one
corpus is transcribed twice and the second pass's rows are a function of the first pass's. Its move
one landed and bought the wrong half, removing the AST dependency while keeping the pass. This
removed the pass and restored the AST dependency, and its two remaining alternatives, both of which
existed to serve a decode that reads printed literals back out of the store, went with it. Discarded
with this slice, its measurements filed as
`roadmap/audits/2026-09-07-directive-decode-census.md` rather than deleted with the plan: the
63-relation decode census, the sub-grammar frequency over the corpus, and what driving an H2 function
with a graphql-java parse costs.

**What did not land with it is the declaration.** Writing a `meta_relation` row means naming a grain
and arguing a rationale per relation, which is item 7's work and its cost. Until it happens the
ownership move is real in the code and unstated in the store, and the two documentation pages
restated with this slice are the only place the store's readers are told. Both are restated:
the fact model's ownership section now says a family names a vocabulary and an owner names a writer,
with one family split between two owners, and its corpus-isolation section names the half the gate
holds.

**One consequence for the target architecture stated below.** It assigns 186 relations to
`graphitron` and 31 to `sdl`, computed by asking which gatherer writes each base relation rather than
which corpus it transcribes, which are the same question everywhere except here. 56 base relations
have moved from the first column to the second, and the counts there are not recomputed. What that does to the view counts is not recomputed, a
view's owner being the latest of the owners of what it reads, and a view reading only entries moves
with them.

### The element family, and what minting writes into it

**The anchor is the element family mirrored, one relation per element kind.** `graphitron_element`
carries the graph, the coordinate and the element kind over `graphitron_type`, `graphitron_field` and
`graphitron_argument`, which is `graphql_element` and its subtypes with one difference, and it takes
that relation's vocabulary rather than a second one. The graphql side splits the anchor from its
payload because the two sit at different cadences, the coordinate settled by the per-file parse and
the payload only once the document composes. The graphitron side is derived at one cadence, so anchor
and payload are one relation and `graphitron_type` carries the kind and the description that
`graphql_type` carries beside `graphql_type_element`.

**The anchors carry the payload rather than joining the twin for it.** A minted row has no twin, so a
reader that joins `graphql_field` for the details is reading a union again, which is the shape this
item exists to remove. The copy is bounded by schema size and not by data. The line it draws is that
the anchor carries what rendering needs and the twin keeps what only the transcription can assert,
which is why `graphql_field.declaration_line` and its foreign key into `graphql_type_declaration` do
not come across: a minted field has no declaration site to point at.

**Three element kinds are minted, and the third was missed by reading the store.** `@asConnection`
mints types and their fields, which the store records, and it mints the `first` and `after` arguments
on the carrier when the author has not written them, which the store does not record at all. This
item asserted the opposite in an earlier draft, on the evidence that `MacroCapture` writes no argument
row, and the evidence was the defect: `ConnectionPromoter.rewriteCarrierField` builds both arguments
against the assembled graphql-java schema, with `first` carrying the page size that
`PaginationResolver` resolves from the authored default or the fallback. So the expansion has two
halves in two modules, one writing facts and one writing schema objects, and nothing holds them to
each other. The argument relation below is where that stops being possible, and it is the reason the
macro unification is part of this slice rather than a later tidy.

**Three minted relations, each keyed by the coordinate that coined the row.** The source coordinate
leads the key, so a cascade from it is a seek and "what did this application mint" is one range scan.

[cols="2,3,4"]
|===
| relation | key after the graph | payload

| `graphitron_minted_type`
| source coordinate, type name
| coining directive, precedence, kind, description

| `graphitron_minted_field`
| source coordinate, type name, field name
| coining directive, precedence, ordinal, the type expression and its four decomposed wrappers,
  description

| `graphitron_minted_argument`
| source coordinate, type name, field name, argument name
| the same, plus the default value literal
|===

The source coordinate is the field the directive sits on, and it carries one foreign key, into
`graphql_element`, with `ON DELETE CASCADE`. Positions are not carried: the coordinate reaches the
field's own position through `graphql_field` and the application's through `graphql_field_directive`,
which is strictly more than a flattened site row holds.

**The coining directive replaces the closed macro vocabulary.** A `macro` column with a CHECK is a
list that has to be edited every time an expansion is added, and it names something the schema
already describes. The directive is an element of the graph with a definition and a description of
what it does, so the minted row points at it and a reader asking why a coordinate exists gets the
documentation rather than an enum label. Today that is a foreign key into `graphql_directive`; it
becomes a directive coordinate when the graphql family's own conformance work lands.

**Four relations become three, and the shared-machinery rule stops existing.** `graphitron_minted_type`
and `graphitron_minted_field` keep their names and change their keys.
`graphitron_minted_type_site` dissolves, because the site is now the source coordinate in the key.
`graphitron_field_synthesis` dissolves into a `graphitron_minted_field` row whose coordinate and
source coordinate are both the carrier. What goes with them is machinery, not just tables: `PageInfo`
is currently defined by the first carrier and extended by the rest, which needs `merge_ordinal`,
`is_extension`, a site counter and a minted-name set inside `MacroCapture`. Under a key that already
carries the source, every carrier writes its whole contribution and the primary key is the only
dedupe, so the sharing case is not a case. `intent_expanded_type` and `intent_expanded_field` went
with them, being exactly the union the anchors now hold as rows, and
`ExpandedPopulationReaderGateTest`'s frozen roster is what made moving their readers a decision
somebody records rather than a drift.

**Precedence is a column on the minted row, and it is not derivable.** Whether a mint beats the
author's declaration is a property of what the macro is doing rather than of whether a collision
happened, and `@asConnection`'s two cases settle it: rewriting `Query.films` replaces the authored
field, while minting `PageInfo` yields to an author who declared that name. Both are collisions with
an authored coordinate and they resolve opposite ways, so no predicate over the two populations can
tell them apart. A replacing row states its whole row, copying the ordinal and description it does not
change, so the winner is taken wholesale and neither side coalesces.

**Capture writes the minted row whether or not it wins**, and that is worth more than the tidiness.
Today the expansion reads every declared type name in the schema into a set and each mint returns
early against it, while `PageInfo`'s minting additionally turns on a counter of how many carriers came
before. So the expansion is not a function of one carrier's own declaration, which is the
qualification rule this family's own comment gives as the reason `@asConnection` may run inside
capture at all. Writing unconditionally makes the stated rule true: the whole-schema lookup and the
cross-carrier counter both go. It also gains the store rows it does not have today, a suppressed mint
being silence at present and becoming a row saying which application would have minted what and stood
down, which is what a diagnostic about a shadowed `PageInfo` would need.

**The population is two statements per grain, each with an anti-join.** The minted arm takes the rows
that replace, plus the rows that yield where the transcription holds no such coordinate; the
transcription's arm takes the rows no replacing mint covers. `SELECT DISTINCT` on the minted arm,
because two sources minting one coordinate with disagreeing payload should be a primary key violation
and not an arbitrary winner. Both exclusions are anti-joins rather than insert order, so the
precedence is readable in the statement.

**A field the macro wrote while minting a type shares that type's fate**, which is the one rule the
row's own precedence cannot carry. An author declaring `type PageInfo { foo: String }` collides with
the minted type, and the four machinery fields collide with nothing, so a field-grain anti-join alone
would let `hasNextPage` land on the author's type and fuse two types nobody asked to merge. The
machinery fields are told from a rewritten carrier by their source: a machinery field shares a source
coordinate with a minted type row for its own owning type, where `Query.films` has no minted `Query`.
So the field arm carries a second anti-join, dropping a minted field whose owning type was minted from
the same source and lost.

**Minting is single level, and the foreign key says so.** Every source coordinate is one the author
wrote, so nothing mints off a minted thing. That is an assumption the cascade into `graphql_element`
encodes rather than states, and it is worth a test of its own, because the day a macro expands into
another macro's output the constraint fails at capture rather than in a reader.

**What this section leaves parked.** The graphql family took the specification's vocabulary ahead of
this work, recorded above, and it left one thing this section will eventually want: directives and
directive arguments are schema elements with coordinate forms of their own and the store holds no
anchor for either, which is why a minted row points at `graphql_directive` by name rather than at a
directive coordinate. Nothing here is blocked on that. The other finding closes a question this
section had open rather than opening one: union members and interface implementations have no
coordinate at all by the specification's own note, so a macro that adds one could never be modelled
at this grain, and `graphql_poly_member` sitting outside the family is conformance rather than a
gap.

### The two hierarchies, and the one mechanism under them

A field has one type, possibly wrapped, and that type is a scalar, an object, an interface, a union
or an enum. Objects and interfaces can be bound to a table. Interfaces and unions have participants,
which can themselves be bound. So the type is a supertype with subtypes, and `INTERFACE` sits in two
of them at once, which is why no partition of type kinds has ever held and why `tablefield` and
`nestingfield` would not sit still as kinds of field.

**The type hierarchy is discriminated by kind, and boundness is not a subtype of it.** Bound,
participant-bearing and field-bearing are neither disjoint nor exhaustive: an interface is all three
at once and a scalar is none, so they are optional facts rather than subtypes. The subtype axis is
the specification's own, which Section 3 states as "there are six kinds of named type definitions in
GraphQL, and two wrapping types", and the store already carries it as `graphitron_type.kind` with the
six-value check. Two genuine subtype relations exist where a kind carries payload no other kind can:
`graphitron_scalar_type_entry` holds a `@scalar` reference to a Java field and `graphitron_enum_entry` holds a
class and method, and neither can describe anything but its own kind. The facts hang off kinds; the
subtypes are the kinds.

**Boundness is one fact and only `@table` states it.** A routine is an operation, not a binding: it
produces rows, and the shape of those rows is not a property of the type the field returns. The store
currently says otherwise. `intent_resolved_type_binding` is `intent_bound_table UNION
intent_routine_return_binding`, which exists so that a routine's result can be read wherever a type
binding is read, and it is a category error rather than a convenience. It dissolves. What replaces it
is not another binding but the field-level fact it was standing in for: where a field's rows come
from, which is the enclosing type's binding when there is one and the enclosing field's operation
when there is not.

That also settles the target vocabulary, which has been three-and-a-half things for several rounds. A
field's target type is bound, or it is an unbound object, or it is a leaf. `resultfield` is not a
fourth: it names a field whose operation set includes a routine, so it belongs on the operation axis
and not on the target axis at all.

**One rung of the hierarchy is already enforced and nowhere stated.** `graphitron_node` keys into
`graphitron_tabletype` rather than into the type element, so nodehood presupposes boundness
structurally and an unbound node is already unwritable. That is the shape the other rungs want, and
it is worth naming because it was arrived at once and not generalised.

**One refusal the store can take for free, and one it cannot, which is not what this said before
it was built.** `graphitron_tabletype` kept into `graphql_type_element` with no kind constraint at
all, so a union, a scalar or an enum could be table-bound and nothing refused it. That one is the
mechanism: a unique on `(graph_name, type_name, kind)` at the anchor, and a composite reference to
that pair from the dependent carrying its own kind check. No new relation, one unique and one
foreign key, and three illegal states stopped being writable on 2026-09-08.

`graphql_poly_member` was named beside it as the same fix and it is not the same fix. It carries
`CHECK (container_kind IN ('UNION', 'INTERFACE'))` with nothing tying the value to what the type
relation calls the container, and the reference that would tie them was written, run and withdrawn:
it refused 103 captures.

What it collides with is when capture runs, and the first reading of this was wrong in a way worth
recording. A type implementing an interface nobody declared is not a state an author can ship:
graphql-java's assembly refuses it with "The interface type 'Node' is not present when resolving
type 'Inventory'" and no such schema builds. The transcription argument on its own would therefore
have been wrong. What makes the reference unaffordable is that capture reads the parsed registry
before assembly and is handed the refusal as a value rather than an exception, so the store's record
of a schema that did not build is the transcription plus the `graphql_schema_error` row beside it.
Measured on exactly that document: capture completes, `graphql_poly_member` holds the row naming
`Node`, `graphql_type` holds no `Node`, and one `ASSEMBLY` row carries the message and its position.
A reference from `container_kind` makes that capture throw an integrity violation instead, so the
store refuses to record the one schema whose error it exists to explain.

What separates the two relations is the half of the family each sits in, read at the level of a
constraint rather than a writer. `graphitron_tabletype` states a resolution and may be held to what
resolved. `graphql_poly_member` transcribes, and a transcription has to survive the document being
wrong, because a document being wrong is when it is read. The plan said "both are the same fix"
because it was reading the shape of the defect rather than the tense of the relation, and the shape
is what a hierarchy makes look alike.

**The hierarchy spans two families, and that is the naming rule working rather than an exception.**
Participants are `graphql_poly_member` because `union X = A | B` is a fact in GraphQL's vocabulary;
boundness and nodehood are graphitron's because `@table` and `@node` are. A family states whose
semantics its rows are written in, which the architecture docs already say under stratum three, and
the hierarchy crossing a family boundary is that rule holding rather than bending.

**Two discriminators, two words, and the specification separates them.** Its Schema Coordinates
section carries a table headed "Element Kind" over named type, field, input field, enum value, field
argument, directive and directive argument, which is exactly what `graphql_element.element_kind`
holds. Section 3's six-way split is a different axis and the specification does not call it that; on
its own phrasing it is the named type kind. Every named type has one element kind, `NAMED_TYPE`, and
its six-way kind one level down, so the two must not share a column name.

The store spells the second five ways and not three, counted off the DDL rather than from memory:
bare `kind` on `graphql_type`, on `graphql_type_declaration`, on `graphitron_type` and on
`graphitron_minted_type`, whose check admits the single value `OBJECT`, and `container_kind` on
`graphql_poly_member`. `named_type_kind` is the specification's word for all five, and it is the
name the new column on `graphitron_tabletype` already carries. Four other relations spell a bare
`kind` that is a different axis entirely and must not be swept with them, `store_graph_schema_input`,
`javac_diagnostic`, `intent_authored_claim_rejection` and
`intent_field_unlowerable_ordering_rejection` among them, which is why the pass is per relation and
not a substitution over the word.

### The graphql_ family has one entry and eleven anchors, and the walk is the difference

Found by trying to state the named-type hierarchy and being unable to. The rule this arc applies to
`graphitron_` applies to `graphql_` and was never applied there, and the cost is paid in Java.

**The measurement.** `SdlFactCapture` carries 42 sites of first-wins logic: 20 `sink.claim`, 5
coordinate claims and 17 quarantines. None of it is transcription. Of the 18 relations it claims, 16
are keyed by the coordinate and only `graphql_type_declaration` and the overflow relation carry the
declaration site. So when two documents declare one coordinate the relation has room for one row, and
the walk picks the winner because the schema cannot hold the loser.

**The data is already there, which is what makes this a defect rather than a trade.** Eleven of the
twelve relations the walk writes already record `source_name`, `source_line` and `source_column`, and
none of them keys by it. Making them entries is a key change, not a capture change.

**`graphql_duplicate_declaration` is question-shaped and fails the one-sentence test.** The honest
completion of "one row of this table says that" is "the walk declined to write this", which is a fact
about the walk and not about a document. Its payload is `AstPrinter.printAstCompact`, so a
declaration that was parsed into a tree is flattened back to a string and the loser's fields and
directives are unqueryable. Over site-keyed entries the same question is a group-by, so the relation
dissolves into a view along with the 42 sites.

**The family already contains the worked example.** `graphql_type_declaration` is an entry, site-keyed
and carrying `merge_ordinal` and `is_extension`; `graphql_type` is its anchor. One grain has the pair
and eleven have only the anchor, and the claim machinery is what stands in for the eleven missing
entries.

**What this makes possible, and why it is this item's.** The developer adds `type X` to a file while
another declares it, and today capture is never told: the loader offers every definition to one
shared registry, whose second `add` refuses, and the losing definition is dropped there. Measured, the
store's whole account of that edit is one declaration and graphql-java's sentence. Under the split it
is two declarations, both with their fields, and the disagreement is a query. The loader stops
throwing and the gatherer gathers what it can, on the ground that a document that cannot be merged is
still a document that was read, and refusing to capture it withholds every fact the author got right.
Ordering comes from `store_source.mtime`, landed 2026-09-08, which is the one question a content hash
cannot answer and is what lets a report name the incumbent and the incursion rather than listing both.
The final registry is then built by adding files in that same order, so graphql-java's own errors
agree with what the store derives.

Additive throughout: the entries are created and populated beside the anchors, and the anchors keep
their names, shapes and coordinate uniques, so readers and the foreign keys that need a unique to
point at are untouched until the derivation can hold the weight.

**The entries landed in two increments, and the second reversed a decision the first made.** The
first wrote twelve relations, one per registry accessor, and held the named type declarations only:
three of the registry's accessors were never called and no node was descended into, so the store had
twelve declaration headers and nothing inside any brace. The second added the rest and collapsed those
twelve into one, `graphql_ast_type_declaration_entry`, because a reader following a child's parent
reference would otherwise union four relations to find a field's parent and twelve to find any
declaration's, and because the switch over AST classes the twelve were meant to avoid never went away.
Twelve relations again, one per node kind the grammar has, and none of them payload twins. Twelve
population methods beside them, each naming where in the document its own nodes live: one descent
collecting into twelve lists was written first and refused, on the ground that relations holding
different data change for different reasons and should not share the machinery that finds them.
No node carries an ordinal either. Within a parent the nodes of a kind are ordered by the position
they were written at, which is the key, so a column would have restated it.

**A supertype was tried in between and was the wrong shape.** The intent was a relation every node
writes into, keyed by position, so a child's parent reference could be a foreign key rather than two
undefended columns. It was built, it worked, and the user refused it on the order of construction: the
aggregate over the twelve is an anchor, derived from the entries for downstream readers, so an entry
with a foreign key into it makes the entries conditional on something built out of them. The parent
reference is two plain columns, and following it is a resolution the anchors do the once rather than
every reader doing it in a join. Recorded because the argument for the supertype was good and still
lost, and because the same argument will come back when the anchors are built, where it is correct.

**The source is named twice, per R872's twin.** `source_name` sits in the primary key carrying no
foreign key, and a nullable `source_ref` beside it carries the reference under
`ON DELETE SET NULL` with a `CHECK` tying the two together. This is the first instance of that design
in the schema; R872 specifies it for five family roots, and with no supertype above them these twelve
entries are roots. The key column gives up its foreign key because H2 lets a cascade win over a
set-null on one column, and what that buys is the thing R872 wants: removing a file flags every
reading of it instead of deleting them, and the owning graph reaps its own on its next refresh.

### The producer hierarchy

A producer is what runs to fetch a field's rows. The first thing to say about it is that most fields
do not have one, and the design has to make that the default rather than the exception.

**A table-bound field is not a producer.** A child field navigating to another table is absorbed
into its parent's query as a join or a correlated projection: nothing runs for it, and there is
nothing for a producer row to describe. It becomes a producer only when something forces it to be
fetched separately, and three things do. The root slot, because a field in a `Query` or `Mutation`
slot has no parent query to be absorbed into. `@service`, because a Java call is not something SQL
can absorb. And `@splitQuery`, which is the author saying so.

Two of those are structural and one is authored, and that exhausts the list, because a fact about a
field cannot be created by the emitter's reach. Today a list-cardinality child over a table-bound
polymorphic target is delivered batched, and it is tempting to read that as a fourth cause. It is
not. `ChildField.InterfaceField` is the inline per-parent delivery of exactly that target type at
single cardinality, so the polymorphic branching itself inlines; what is missing at list cardinality
is a shape for projecting many correlated branch rows, not a difference in where the rows come from.
The classifier picks the batched leaf because it has nowhere else to put the field, and the day
someone writes the inline list shape the field's facts do not change, only the delivery does. A
producer row minted for it would have to be un-minted then, which is the test: **anything that would
change when a missing shape lands is a status and not a fact, and the fact base does not record it.**
The rule generalises past this case, and it is worth stating once here because the store has no other
guard against an emitter limitation entering it as vocabulary.

So inline against batched is a delivery, and delivery is the classifier's and the emitter's. Producer
constrains it, a fetch origin having to be its own fetch, but does not determine it: the emitter may
also split what the model calls absorbable, and that is a decision downstream of these relations
rather than a fact inside them. The same cut disposes of the `@asConnection` rejection on an inline
table field, which reads as though a connection needed the split. It is a rejection, and its own
message says "is not supported".

The three causes sit on two axes and they overlap: a root `@service` field is caused twice. So the
cause is not a function of the coordinate and must not be a column on the producer row; what the row
states is that the field is a fetch origin, and the derivation states why. Its population is a strict
subset of `graphitron_field`, and a reader wanting the absorbed fields anti-joins.

**`@routine` is two things and only one of them is a producer.** A `@routine` application in the
middle or at the end of a chain is a step: it contributes its result table as a node in the join
path, interleaved with `@reference` hops in written order, which is what
`graphitron_field_chain_application` was built to state and what the manual's sandwich example
describes. On a sandwich the field's rows come from the parent's table by a join and the routine is a
hop; the field's producer, if it has one at all, is not the routine.

The way not to model this is as two roles for one directive. `@routine` has one role, a step in the
route, and the step at position 0 is where the field's rows originate. Producer-hood is a separate
question asked of the field and not of the application. So `graphitron_routine_entry` belongs to the route
family in full and is not a producer subtype: a subtype reference from it would assert that every
coordinate holding a routine application has `ROUTINE` as its producer, which the sandwich falsifies.
Where a producer does need to name the originating application it references
`(coordinate, ordinal)` with a field-grain unique above it, so at most one application can be the
origin and the rest are steps by construction.

**Which leaves four values rather than eight, and the store's own test is what cuts them.** A
subtype keeps a relation when it carries payload no sibling can hold.

`insert`, `update`, `delete` and `upsert` share `graphitron_mutation_entry`'s exact column set, so they are
one subtype with a verb column and not four subtypes. The relation already is that; splitting it
would put a discriminator to work twice.

`lookup` is not a producer at all. `LookupFacts` fires a coordinate's lookup trigger when
`@lookupKey` appears anywhere on its argument surface, directly or through a reachable input object
field, so lookup-ness is a derived predicate over the argument grain rather than a marker at the
field grain. It is legal at root and child sites both and composes with `@splitQuery` at the child
one, which is exactly what a modifier does and what a sibling of `service` could not. It keys a table
read; it does not replace one.

`query` carries no payload and no relation, which is the same observation from the other side: it is
the default when no directive names something else, so it is the anchor's value with no subtype under
it.

What survives is `QUERY`, `SERVICE`, `ROUTINE` and `MUTATION`, with three subtype relations under
four values. These four are genuinely exclusive at a coordinate, which the eight were not, so the
disjointness the mechanism enforces is now a true claim rather than a hopeful one.

**Today the exclusion is real in the generator and unrepresentable in the store.**
`graphitron_service_entry` and `graphitron_mutation_entry` both key at `(graph_name, type_name, field_name)` and
nothing stops one coordinate carrying both. The exclusions live in the classifier as directive
conflicts, the `@routine` with `@splitQuery` pair being the named precedent, which is a rejection a
reader of the store cannot see and a constraint the store cannot state. A producer anchor keyed at
the coordinate, with each subtype referencing `(coordinate, producer_kind)` and checking its own
value, makes a second producer unwritable.

**The word is `producer` and not `operation`, and the store chose it already.** `operation` is taken
twice: `graphql_root_operation.operation` is the specification's, "which root slot", and
`graphitron_mutation_entry.operation` holds the DML verb. This item's own model uses it for a third thing,
the multi-valued set where one field selects and joins and paginates at once, and that axis has to
survive alongside this one: a field with `producer = QUERY` and `operations = {select, join,
paginate}` is a sentence two axes called operation could not carry. The store meanwhile runs four
relations on the right word already, `intent_field_producer_method`,
`intent_field_producer_reference`, `intent_field_payload_producer` and
`intent_producer_cardinality_conflict`.

**Flat, and one level.** `@routine` and `@mutation` are mutually exclusive: a field on the Mutation
type carrying `@routine` calls the routine and the routine handles the write, and `@service` is the
same. So a write is not a producer with a delegate attached. What decides whether a field writes is
not its producer but the root slot it hangs under, which is the position axis: one producer,
`ROUTINE`, reads under `Query` and writes under `Mutation`. Two axes crossing, which is the whole
reason this model has axes, and the cut from eight to four is what makes the crossing carry the
weight instead of the value list.

**And one column becomes load-bearing that is unguarded today.**
`graphitron_mutation_entry.operation` carries the DML verb with no CHECK at all. It is not the producer
discriminator, that being `MUTATION` for all four verbs, but it is the vocabulary the write half of
the model reads, and it gains the four-value check it should always have had.

**What gets renamed.** Grain first: `graphitron_type_table` and `graphitron_type_node` for the
anchors, `graphitron_type_table_entry` and `graphitron_type_node_entry` for the decodes, retiring
`graphitron_tabletype`, `graphitron_node` and `graphitron_table_entry`, with `graphitron_scalar_type_entry` and
`graphitron_enum_entry` becoming `graphitron_type_scalar_entry` and `graphitron_type_enum_entry` on
the same rule, both being decodes and so keeping the suffix.
Declared together with their grains, so the pass that follows has a worked example of the rule rather
than a rule and no example.

### The route family, and the grains it hangs off

`graphitron_field_table` says where a field's rows come from, where it departs from, and which of
three rules named the target, all in one key. It dissolves. It is the only relation in the store with
its key shape, it has no reader outside its own writer and tests, and the three grains it runs
together are the reason the naming would not settle: four rounds of argument over `field_table`
against `tablefield` were an argument about which grain the name should mean.

The grain analysis that settles it is in two audits rather than here, because it is about the whole
family and not this item:
`roadmap/audits/2026-09-05-coordinate-facts-as-relations.md` names the grains and the four tiers at
which an illegal state can be refused, and
`roadmap/audits/2026-09-06-graphitron-family-grain-inventory.md` places every graphitron_ and intent_
relation against them. What this item owes is the part that is in its own path.

**The target is a Field-grain fact, and it needs no new capture.** Which type a field navigates to,
and whether that type is bound to a table, to a routine's result, or to nothing, is determined by the
field alone. Three relations already carry the inputs at a compatible key:
`graphitron_field_navigation`, which is total over `graphitron_field`; `graphitron_tabletype`; and
`intent_field_chain_terminus.via`. Measured on a capture, the classification is total and it is
independent of the producer axis: a `@service` field over a table-bound return classifies as a table
target, and the rule that says so reads none of the service relations.

**The participants are a Type-grain fact, so there is no per-field target fan-out.** A field has one
navigated type. That a union or interface has several members is a fact of the container, and
`graphql_poly_member` already keys it at `(graph, container, member)`. Today's relation materializes
that fan-out once per field, which is a duplicate of a type fact rather than a field fact.

**What genuinely varies per field and participant is the route.** Two participants of one container
need not be reached by the same join, which is why Route is a grain of its own at
`(graph, coordinate, target)` and Step sits under it at `(graph, coordinate, target, position)`.

**`target_basis` is a discriminator standing in for a relation that was never written.** Its three
values are not a property of one thing: `NAMED_TYPE_TABLE` and `PARTICIPANT_TABLE` differ only in how
many participants the navigated type has, which is the Type fact above, and `ROUTINE_RESULT` is a
different target kind entirely, arriving through `FieldEndpoints.routineResult`, which demands
`candidates = 1` and so is one-to-one where the other two fan out. One relation was holding a
one-to-one population and a one-to-many population under one key.

**The chain position is an Application-grain fact, and its absence is the sandwich defect.**
`graphitron_field_reference_entry`, `graphitron_reference_for_entry` and `graphitron_routine_entry` each key
`(coordinate, ordinal)` with the ordinal counting applications of that one directive, where
`graphql_field_directive` keys the real application grain at
`(coordinate, directive_name, ordinal)`. So no relation orders the three against each other,
`intent_field_chain_node` recovers the order by comparing source line and column, and on the manual's
own sandwich it reports two nodes where the manual describes four: the hop written before the routine
is excluded by the predicate that admits only applications following the routine's position. The
order has to be minted where it is known, which is capture.

**More relations sit under the route.** The reference decode it resolves and the routine decode it
interleaves, and the hop relation today is a candidate enumeration rather than a resolution: it emits
both directions and every matching constraint, and two separate recursive walks pick between them.
How many relations that is, is not yet known, and the arc's own history says the number will be
discovered by something refusing rather than by planning.

What this reorders in the item as a whole: the register is downstream of all of it. Each slice
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
`graphitron_connection_entry` rows and 218 field rewrites; 434 minted types across 436 sites carry 1302
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

**The `graphitron` and `sdl` rows below are stale as of 2026-09-07 and the direction is known.** The
walk that produced them assigned each base relation to the gatherer that writes it, which is the same
answer as the corpus it transcribes everywhere except in this family. Under the writer decision,
56 base relations move from `graphitron` to `sdl`, leaving 130 and 87. The view counts are not
recomputed: a view's owner is the latest of the owners of what it reads, so a view reading only
entries moves with them and one reading an anchor does not.

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

## Build-side evidence added 2026-09-08 from outside this item

Added by R733's fourth measurement pass, which set out to ask where the build's wall clock goes and
arrived here. Recorded as evidence for this item's owner and its Done gate to use or dispute, not as
a change to its plan. Figures taken at `7a3fae6e` on one 4 vCPU 15 GB sandbox.

**This item's subject is also the largest single regression in our own build.**
`FixtureWarningsGateTest` is one full-fixture generator run and nothing else, and it is the
reference instrument R733 has used across three passes. In isolation it was **2.862 s** on
2026-08-20 and is **26.28 s** now. Both confounds were ruled out: the generator's input grew 13%
(`schema.graphqls`, 4203 to 4742 lines), and the machine is not the cause, DDL cost per statement
being 0.066 ms then against 0.062 ms best now. What grew between the two measurements is the fact
model, views 71 to 120. So the cost tracks the view count rather than the schemas fed to it, which
is this item's mechanism observed from the build side. The build pays it six times over, and
`graphitron-sakila-example` is 223 s of a roughly 1080 s build.

**A second cost of the register, which this item has not counted and which it removes for free.**
`GraphitronModelStore` runs `MaterializeDependencies.populate` inside every boot. That walk starts
at each `meta_materialize` row, parses each stored view definition it reaches with jOOQ's parser,
and now reaches all 120 views. It has gone from **8.41 ms to 139.7 ms** and is **32% of a 436 ms
boot**, up from 6% of a 138 ms one. With the register dissolved the walk has no roots, parses
nothing, and the step goes to zero. The build performs on the order of a thousand boots, so this is
worth roughly two minutes of build CPU on top of the read-side win, and it is paid by every
consumer at every store open as well.

Worth stating as a check rather than a credit: **the Done gate should confirm the step actually
reaches zero rather than assume it.** The measurement is six lines, timing
`MaterializeDependencies.populate` directly against a booted store.

**One piece of counter-evidence, offered because it argues against a claim this item could
otherwise be read as making.** The boot has two halves and only one of them goes. The DDL half is
283.5 ms of the 436 ms, and this item's own remedy pushes it upward: it replaces registrations with
stored keys and indexes, and while it has been in progress the schema has gone from 0 to 22
`CREATE INDEX`, from 148 to 190 tables, and from 2138 to 3278 statements. That is a fair trade if
the read-side win is as large as the figures above suggest, and it is almost certainly the right
trade. It is recorded so that "the boot gets cheaper" is not inferred from "the register goes".

**Two things this item does not absorb, recorded so they are not expected of it.** First, store
size: the real 34 MB build store is 99.3% classpath census rows and the twenty registered targets
hold **25 rows of 251,807**, so dissolving the register changes the store's size by nothing
measurable. That belongs to R762, and through it to R937, compaction on close costing 1.6 s per
close on a store that size. Second, boot *count*: R768's roughly one thousand boots per build are
unaffected by what a boot contains.

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
`graphitron_argument_path_segment`, `graphitron_argmapping_candidate`, `graphitron_argument_node_id_entry`
and `graphitron_field_node_id_entry`, and nothing else. Every input belongs to the graphitron gatherer, so
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

**Fifty-three relations renamed, not retired, and they are listed by rule rather than one by one.**
Every relation of the as-written half of `graphitron_` gained the `_entry` suffix, which is the
whole of the change to each name: `graphitron_table` is `graphitron_table_entry`,
`graphitron_field_reference_step` is `graphitron_field_reference_step_entry`, and so on for the 53
that did not already carry it. The three that did are `graphitron_argmapping_entry`,
`graphitron_node_entry` and `graphitron_node_keycolumn_entry`. Nothing in the resolved half moved.
Two index names followed their relations, `graphitron_spelled_reference_name_ix` and
`graphitron_method_reference_method_ix`. A sweep for a survivor is a search for a `graphitron_` name
that is neither suffixed nor one of the sixteen in the resolved half, which is cheaper than a list
and does not go stale.

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
passing through a resolution that had not decided them. `graphql_element_field` is new and is
where a reader now decomposes a coordinate.

**Java.** `MacroCapture.expandConnections`, now `MacroCapture.expand` and driven by store rows rather
than by the walk. The `Expansions` record and the five `captureXDirective` callbacks `SdlFactCapture`
drove the decode through, along with `captureNavigation` and `connectionElementByType`, all of which
went when the decode stopped being a visitor of the SDL walk. The five callbacks returned in slice 1,
for the entry half only: what that move got wrong was moving the family as one thing, so the anchors'
need for the store decided the entries' home with it, and the entry and anchor split is what
separates them.

**Java, slice 1, the suffix.** `EntryFamilyFixture.ENTRY_RELATIONS` and `ANCHOR_RELATIONS`, the two
enumerated lists, now one `entryRelations()` read off the generated model by name; and
`EntryFamilyCoverageTest`'s partition case, which held the two lists to covering the family and is
true by construction once the name decides it. The anchor accessor went with its only reader: the
complement of the suffix is a bag rather than a half, holding both the stages' relations and a view
nothing writes, and telling those apart is the declaration pass's work rather than a name's.

**Java, slice 1.** The store-driven half of `GraphitronFactCapture`: `schemaDirectives`,
`typeDirectives`, `fieldDirectives`, `argumentDirectives` and `enumValueDirectives`, the five loops
that fetched applications back out of the transcription; `argumentsBy`, which grouped their
arguments; `directive` and `parsed`, which rebuilt a synthetic application from printed literals;
`location`, which rebuilt a position from three columns; and the four-argument `undecoded`, which
quarantined a stored literal that would not parse back and had no other caller. All private, so
nothing outside the class spelled them. `SdlFactCapture.capture` returned the walk's shadow sink
during the additive window and returns void again, and `EntryWriterAgreementTest` and
`CapturedStore.entriesDecodedByTheWalk` went with the arm they compared against.

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

Six places where this plan is weakest, named so the gate does not have to find them.

**The writer decision rests on a classification whose evidence is mostly vacuous, and it picks a
mechanism for a sequencing reason.** Slice 1 moves 56 relations on the claim that each is a function
of one document alone. The check available today, the widened corpus-isolation run, agrees for all 56
and is empty for 49 of them, so the claim is measured at eight relations and asserted at the rest.
The press is on what happens to a relation the classification gets wrong: it lands in a gatherer with
no store to fall back on, and the failure is a missing row rather than an error. Second, the slice
moves ownership in the code and declares it nowhere, because a `meta_relation` row costs a grain and
a rationale per relation and that is item 7's budget. So between slice 1 and item 7 the store's own
account of who writes the entry half is wrong, and the gate that would catch it is the one that binds
per declared row. A reviewer should press on whether that window is acceptable or whether the 56
declarations belong in slice 1 after all.

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

### Rounds 1 to 3 (2026-08-28, Spec to Ready, reviewer session 01P2HFCFzA3YiKbaLjgXzet7)

Three rounds, all findings addressed before the item moved to Ready, and the item has been In
Progress since. The full exchange is in the git history of this file; what survived it and still
governs is in the sections above rather than in the rounds.

Two findings are worth keeping here because they shaped the item rather than a paragraph of it. The
first was that the central claim, that a registration has nothing left to buy once the model is
fixed, was established at one relation and asserted at the others; the item now says so in its own
opening and carries the honest status in "What changes when this lands". The second was that the
lever order was stated as a principle before it had been run, which is why "Four attempts, and what
each of them found missing" exists: each attempt is the order being tested rather than illustrated.

### Round 4 (2026-09-09, In Progress, reading of the new capture family from the session holding R872)

Four findings against the document gatherers as they stood at `7cf4d1ad6`. Three were defects and
are fixed in this item; the fourth is a constraint on work this item has not reached yet and is
recorded here so the anchor aggregation is written against it rather than corrected afterwards.

**The verdict was documented in two places that disagreed.** `SdlSchemaProblems`'s own javadoc
claimed absence of problems and presence of anchors were the same fact, which would make a graph
that does not build a graph with no anchors. `docs/architecture/explanation/fact-model.adoc` says
the opposite and says it correctly: the anchors are derived from the entries, so a corpus that does
not build still has them, which is the ordinary state of a schema while somebody is editing it. The
javadoc now says what absence does decide, a count, and says explicitly that it decides nothing
about anchors.

**A file that would not parse was invisible.** `SchemaLoader.parsePerSource` returns rejected
sources as data and the gatherer iterated only the parses that succeeded, so such a file produced no
entry rows, no registry row and no problem row. Absent is what a file nobody configured looks like,
so the store could not tell an unreadable file from an unconfigured one. The gatherer now writes the
registry row for a rejected source too, and the parse stage joins the other two in the problem
relation. That relation could not hold a syntax failure as it stood, being keyed at the graph with
no site, so it gained `stage`, `source_name`, `source_line` and `source_column`, the last three
nullable because the two document-wide stages point at a declaration where they can and nowhere
where they cannot. The stage is a column rather than three relations for the reason the relation
already gave for merging two stages: all three are what happened when we tried to make a schema out
of these documents.

**The verdict half of the pipeline never ran.** `SdlSchemaProblems.write` had exactly one caller,
its own test, which assembled the stages itself and therefore passed over a capture that recorded
none of them. `SdlCapture` now writes the verdict from the same parse it writes the entries from,
and the test drives `SdlCapture` rather than reassembling the pipeline beside it. The writer stays
its own class, matching `SdlEntries`: the gatherer orchestrates and the writers write.

**The anchors need ordinals the entries deliberately do not carry.** `graphql_field.ordinal`,
`graphql_argument.ordinal` and `graphql_poly_member.position` have no entry counterpart, because an
entry is keyed by the position its node was written at and a position is not an order. They are
computed in the anchor aggregation, and the ordering has to be deterministic in two dimensions:
position within a parent, and oldest source first across sources. That second one is not a new
choice. `SchemaLoader.oldestFirst` already imposes it on the reading, and
`graphql_type_declaration.merge_ordinal` already records the result, so the aggregation reads the
order the corpus already has rather than inventing a second one that could disagree with the
sentence graphql-java writes about a collision.

## The entry keys, and the split that earned them (2026-09-09)

A node written inside another names its parent's position. That position was a column nobody could
key on, and the reason was that three of the relations had a parent that was a union: an input
value is a field's argument, an input object's field or a directive definition's argument, and a
directive application sits at any of five kinds of site. A foreign key names one table, so the
merged relation could carry the position and nothing more.

The two merged relations are now nine. `graphql_ast_input_value_definition_entry` becomes
`graphql_ast_field_argument_entry`, `graphql_ast_input_field_entry` and
`graphql_ast_directive_argument_entry`; `graphql_ast_applied_directive_entry` becomes
`graphql_ast_type_directive_entry`, `graphql_ast_field_directive_entry`,
`graphql_ast_input_value_directive_entry`, `graphql_ast_enum_value_directive_entry` and
`graphql_ast_schema_directive_entry`. `graphql_ast_applied_argument_entry` stays one relation.

Thirteen references follow, six on relations that were always single-parent and seven on the new
ones. Two rows keep a position and no key, and for the same reason as before rather than a new one:
a directive on an input value has a parent in one of three relations, and an applied argument has a
parent in one of five. A position is unique in a file whichever relation holds the parent, so those
two references are sound and only unspellable, which makes them detections over the union.

Not chased further. Closing those last two would split the input-value directives three ways and
the applied arguments five, taking three relations to seventeen rather than nine, and one of the
arms it would add holds a directive on a directive definition's argument, which no consumer schema
we have writes.

The keys decide two orders that used to be free. The writers run outermost first, and the sweep
walks the same list backwards, both spelled out where the list is declared. Splitting also creates
two payload-identical sibling sets, which `SupertypeSignatureGateTest` reports and its roster now
carries: the union those sets ask for belongs in the anchors, once, rather than in each reader.

## The decode, keyed where the directive was written (2026-09-09)

The type-site directives now have a second family beside the coordinate-keyed one:
`graphitron_ast_table_entry`, `graphitron_ast_scalar_type_entry`, `graphitron_ast_enum_entry`,
`graphitron_ast_record_entry`, and one relation per handler kind under `@error`. Each is keyed by
the position of the `@` token, which is `graphql_ast_type_directive_entry`'s key, so a row is the
decode of exactly one row there and carries no position, no type name and no file of its own.

What that buys is the thing the coordinate key could not give. Two documents binding one type are
two rows, neither refused, so the collision is a query over the rows resolved oldest source first
rather than a first-write-wins the writer had to arbitrate. Nothing in this writer claims,
deduplicates or orders.

`@error` gets no relation of its own. Its only argument is the handler list, so a row carrying its
key and nothing else would say exactly what the applied-directive row already says. That is the
position keying paying for itself: a relation that exists only to be a coordinate disappears.

A handler gets a relation per kind, for the same reason the directive applications do: the kind
decides which of the input's six fields mean anything. `GENERIC` matches by class identity and the
directive rejects both SQL discriminators on it; `DATABASE` matches any SQLException on one of two
discriminators and rejects a class name; `VALIDATION` takes nothing at all, so its row is its
position, which is a fact the applied-directive row does not carry. One relation for the three
would be six nullable columns whose legal combinations no constraint over that shape could state.

There is no quarantine relation. Every argument of every application is already transcribed
verbatim in `graphql_ast_applied_argument_entry`, so a value that is not the shape the directive
asked for, and a field written on a handler kind that rejects it, decode to nothing here and are
still one join away. That is the cost of the per-kind split, and it is what pays for it.

The reference cascades on delete, and that is the one place a cascade is doing real work: it lets
the two writers of one document sweep independently. An application the author moved or deleted is
swept with the directive row it hung on; what is left for the decode's own sweep is the application
whose position another directive now occupies. Both halves are held by a test that goes red when
its half is removed.

Not yet done. The field site is eighteen directive names and most of the complexity, and it should
wait until the co-keyed shape has been read back by something other than a test. The coordinate-keyed
family is still what the pipeline reads.

## The anchors, derived from the entries (2026-09-10)

`SdlAnchor` derives sixteen anchor relations from the entry rows of the same reading, one
`INSERT … SELECT` per anchor. Nothing is read into Java and written back, and nothing is decided:
the statement is the derivation. It runs from `SdlCapture` once every document has been read,
because a coordinate is declared by the corpus and no per-file pass can say one went away.

The merge order is stated once. `graphql_type_declaration` ranks base-before-extension, then oldest
file, then name, then position, which is `SchemaLoader.oldestFirst` written as an `ORDER BY`, and it
is the only place `store_source.mtime` is joined. Everything downstream orders by the
`merge_ordinal` it wrote, which is what the ordinals in `graphql_field` and `graphql_argument` are
counted over. That answers the fourth finding recorded above: the ordinals the entries refuse to
carry are computed here, deterministically, from an order the corpus already has.

A collision resolves by taking rank one rather than by refusing. An anchor holds no duplicates and
refuses nothing, because the schema whose anchors an author still needs mid-edit is exactly the one
a refusal would empty.

The entries gained `type_name`, `field_name` where a coordinate needs it, and a generated
`coordinate` column. That is transcription rather than duplication: both names are written in the
document, one at the node's position and one at its parent's, so the coordinate is as much a fact of
that file as the type expression is. It is the natural key and it cannot be the key, an entry
relation being a bag: two documents writing `Film.title` are two rows, which is the whole reason the
family exists.

`graphql_poly_member` is two relations now, `graphql_union_member` and
`graphql_implements_interface`, with the old name surviving as a view. The two arms disagree about
which end declares the membership, which is what made one relation carry a discriminator and a
`declared_on` column to say which end the key meant.

One commit rather than the two this was planned as. The anchors write the split relations, the DDL
interleaves them in one region, and three test fixtures straddle both, so the first of two commits
would have been verified by argument where the pair together is verified by the reactor.

## The configuration corpus (2026-09-10)

Harvested from session-b. `StoreEntries` transcribes `SubjectConfig` into the nine
`store_graph_*` relations, eight methods over nine relations, one per parameter or per group that
arrives together. It decides nothing: no defaulting, no resolution, no comparison, and a parameter
the run was not asked for writes no row, absence being a missing row rather than a configured
blank.

Until now the production path wrote none of them. `ConfigurationFactCapture` sits on the walk that
only the old tests reach, so every real run left the recipe, the output coordinates, the supergraph
declaration, the tenant column, the lint suppression and the session hooks empty. The recipe rows
are what a currency check re-expands without building the module, and what the freshness replay
decodes a sibling graph's recipe out of, so the gap reached past tidiness.

The nine gain the stamp rather than being cleared per graph and rewritten. Uniformity is most of
it, the sweep predicate being one sentence everywhere the new capture writes, and there is a second
reason: a delete-then-insert leaves the graph with no configuration for the width of the operation,
which is a state the store should not be able to represent.

Two things the harvest settled that the author left open.

The kind taxonomy is spelled twice while the incumbent stands, once by this writer and once by
`StoredRecipe.decode`, in packages that cannot name each other's constants. What holds them
together is a round-trip case: write with one, decode with the other, assert the bindings come
back. The relation's CHECK catches only a value neither admits. The end state is the decode moving
beside the encode when the incumbent goes.

`writeGraph` moved out of `SdlCapture` and into `ModelCapture`, first. Every relation the gatherers
fill holds a foreign key into the graph's anchor row, and two of them now need it, so it belongs to
whoever runs them. Ordering the gatherers by which one happens to mint it would have been a rule
held by a comment. Both direct callers of `SdlCapture` already seed the anchor, so the lift cost
nothing.
