---
id: R876
title: "Expensive derived reads are a modelling defect: every rule needs an owner, and once ownership is computed the derivation gatherer is unearned and meta_materialize has no subject"
status: In Progress
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-28
last-updated: 2026-09-16
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

## Entries, derivations and anchors, and what each one forces

The target shape, stated here because every slice below is a step toward it.

An **entry** is computed from a corpus: one row per written thing, read from one file. A
**derivation** is computed from rows already in the store. An **aggregating derivation** is one that
collapses, meaning no single input row answers for a row of it; a window function does not collapse,
since `lead`, `lag` and `count() over` preserve one row per input row. Aggregation names a sort of
derivation and forces nothing of its own: it is the case where no foreign key could state the
dependency even in principle, which is why the view below is required of every derivation and not
only of this sort.

An **anchor** establishes a grain, its key being one no other relation is the authority for. That
cuts across the rest: an anchor may be an entry or a derivation. It is decidable from the declared
keys and the foreign keys between them, so it needs no declaring. A key its own references already
cover is composed; a key they do not cover is established.

| what it is | why | what it forces |
|---|---|---|
| an entry | a view cannot read a corpus | a table |
| a derivation | a foreign key states only what a relation keys into, and a derivation consults more than that | its rule stated as a view |
| an anchor | a foreign key cannot reference a view | a table |
| stored, for any of those | a later reading must correct its rows | an owner, and mark and sweep |
| measured hot | read cost | may be stored, and forces nothing else |

So the full arrangement, a view and a table and an owner that sweeps, is earned by a derivation that
establishes a grain, and by nothing else. `graphql_element` and `graphitron_field_chain_link` are
that; the resolved chain links, which compose a grain, are a view and no more.

Dependencies are never declared. A derivation's come from its view body; an entry's are its own
foreign keys, there being nothing else it reads. A gatherer's set is the union over its relations,
which is why the declaration belongs to the relation: a gatherer captures entries and derivations at
once, so a gatherer-grained edge is too coarse to be true of either. The document gatherer owns 84
declared relations and carries no dependency rows at all.

A view body is read to check, not to plan. Ordering is the owner's own and local to it, and no
graph-wide walk decides what runs when. The register and its dependency planner both dissolve, which
is what naming an owner buys: a rule nobody owns needs scheduling, and an owned one does not.

Three gates follow. A relation's declared owner is the gatherer that refreshes it. A derivation's
view reads only what its owner owns or depends on. A stored relation got its rows by mark and
sweep.

Storing has three possible reasons and no default, which is the habit this item exists to break.

### Owed before this is cut

- **The aggregate census**: which relations collapse and have no view stating their rule. That count
  is the size of the migration.
- **The anchor test as a gate**, since it is decidable rather than declarable, catching both a
  relation inventing a grain and one claiming a grain it composes.
- **The rules a view cannot state.** One candidate turned out not to be: the chain walk wanted a
  window function over the path order, consecutive key elements sharing a table. What remains is the
  expansion that mints population.
- **What an owner states about its own order**, where one of its stored relations reads another.

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

**A document's reader writes every entry; a derivation writes every anchor.** Settled 2026-09-07,
and it is the ownership rule applied to base relations rather than an exception to it. The fact model
already decides an owner by asking what a row is a function of, and it has only ever run that
question over views. An entry is a function of one document and nothing else: it joins nothing,
resolves nothing, defaults nothing, and reads no corpus but the document the directive sits in. So
its owner is that document's crawler. An anchor is the opposite shape by construction, `Nodes.derive`
joining an entry against `sql_node_metadata`, and could not run inside a walk at all. The two halves
are the two answers to the ownership question, and the family they share was never what the question
asked.

That is still the rule, in the vocabulary the section above settles: an entry is computed from a
corpus and a derivation from rows, which is this section's own question, what a row is a function of,
asked about the input rather than the owner. What it does not decide is the derivation's form. Two
properties do that, and they are independent of the kind and of each other: collapsing, which means
no foreign key can state the dependency, and being keyed into, which means it cannot be a view. This
sentence's "anchor" means the first, so it means aggregate.

That sentence read "the SDL walk writes every entry" until 2026-09-11, and the correction is the rule
working rather than the rule bending. An entry is a function of one document, so its owner is
whatever reads one document, and the walk never was that: it reads a merged registry, which is a
function of the corpus. Naming it the entry writer put a corpus-shaped reader in front of a
document-shaped fact, and everything that cost is in the diagnosis two sections down, where the
walk's 42 sites of first-wins logic and its overflow relation are exactly what a coordinate-keyed
grain needs and a position-keyed one does not. The writers now are `SdlEntries` and
`GraphitronEntries`, per document, behind `SdlCapture`; the anchors are `SdlAnchor` and, from
2026-09-11, `GraphitronAnchor`. The walk is being retired, and the measurement that it can be is a
later chapter here.

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
of a schema that did not build is the transcription plus the `graphql_schema_problem` row beside it.
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
Two condition views whose subject moved into the arm that answers it,
`intent_condition_param_extraction` and `intent_condition_table_parameter`. And
`code_condition_method_parameter`, now `code_method_parameter`, which is a rename in the schema and
a change of subject in fact: a method's parameters stopped being the admitting arm's and became the
method's, so the arms hold membership and nothing else.

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
`code_external_field_method.table_parameter_type`, which was the sole position's type and is that
position's role. `code_condition_method.is_static`, which is the method's and is on `code_method`.
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

**Java, the write path.** `RowChunks`, its `ROWS_PER_STATEMENT` bound, `of` and `execute`, and the
chunk vocabulary in the javadoc around them: a statement carrying its rows has no bound worth
picking once the rows are bound per row instead. `MultiRowWritesAreChunkedTest` went with it,
replaced by `WritesBindPerRowTest`, which holds the stronger claim that the module has no multi-row
write rather than that its multi-row writes are bounded.

**Java, the gatherer split.** `SdlCapture`, which was four gatherers behind one face, along with
its `captureFacts`, `captureEntries` and `captureGraphitronAnchors` entry points and the transitional
`SubjectConfig` overloads it grew while the corpus reader was being lifted out from under it. Its
callers name `GraphQLSourceCapture`, `GraphQLAstCapture`, `GraphitronAstCapture` and
`GraphQLAssemblyCapture` instead. `SdlAnchor` went with it, dissolved into `GraphQLAstCapture`, the
anchoring being a step of the gatherer whose entries it derives from rather than a thing of its own;
`SdlAnchorTest` is `GraphQLAnchorTest`. The gatherer roster row `document` is retired and is four
rows now, `graphql-source`, `graphql-ast`, `graphitron-ast` and `graphql-assembly`.

**Java, the document states.** `GraphQLSourceCapture.SourceDocument` was a record of three
components and is a sealed interface of four arms, `Changed`, `Unchanged`, `Unparsable` and
`Dropped`, under a `Stated` supertype for the two that carry a registry. Its `parsed()` accessor is
retired, the question it answered being which arm a document is; its `registry()` moved to `Stated`,
where it cannot be null; and its `changed` component is retired, a boolean nothing read having been
replaced by the distinction between two arms. `reclaimVanished` is `reclaim` and is public, the pass
sequencing it after the gatherers rather than the reader folding it into its own return.

**Three writers renamed.** `SdlEntries` is `GraphQLAstEntries`, `SdlSchemaProblems` is
`GraphQLSchemaProblems` and `GraphitronEntries` is `GraphitronAstEntries`, each now named for the
family it writes and matching the gatherer beside it; `SdlEntriesTest` and `SdlSchemaProblemsTest`
followed. The remaining `Sdl` names belong to the incumbent walk and go when it does.

**Columns, the per-graph stamp.** Nothing retired, two added, recorded here because the grain is the
point: `store_graph_source.stamp` and `store_graph_source.read_at`. The stamp on `store_source` is
store-global and says what a file hashed to for whoever read it, which cannot answer whether a given
graph holds rows derived from those bytes; a reading that skips an unchanged document reads the new
pair instead.

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

## The field site's decode (2026-09-10)

Seventeen relations hold what a graphitron directive written on an output field meant, each keyed by
the position of the `@` token, which is `graphql_ast_field_directive_entry`'s key. Same shape as the
type site a day earlier: a row is the decode of exactly one applied-directive row, carries no type
name, no field name and no file, and resolves nothing.

**The site roster is sixteen names, not the switch's eighteen.** `GraphitronFactCapture` routes an
input object's field through `captureFieldDirective`, so `@asFacet` and `@lookupKey` are cases of
that switch although their definitions name no `FIELD_DEFINITION` location. An input object's field
is an input value to the parser, so an entry for one references
`graphql_ast_input_value_directive_entry` and belongs to that site's writer. The foreign key decides
this, not a preference: an entry references the AST row it decodes, and those rows are in different
relations. The same split sends `@field`, `@condition`, `@reference`, `@referenceFor` and `@nodeId`
to two writers each, which is the split the anchors already have between
`graphitron_field_condition_entry` and `graphitron_argument_condition_entry`.

**Four of the sixteen get no relation**, on the rule `@error` set. `@splitQuery` and `@tenantFanOut`
declare no argument; `@multitableReference` is rejected before anything reads its one argument; and
`@reference` carries only its path, so its steps get a relation and the application does not. A row
for any of the four would carry its key and nothing else, which is what the applied-directive row
already says.

**A list argument's children hang off the decode of the application they were written inside**, so
deleting a decode takes its elements. `@reference`'s steps are the one exception and for the one
reason: its application has no decode to hang from, so they reference the AST row directly, which is
the shape `@error`'s handlers already take.

Nothing here writes `graphitron_argmapping_entry`, `graphitron_method_reference_entry`,
`graphitron_spelled_reference_entry` or `graphitron_undecoded_argument_entry`, all four of which the
incumbent writes from inside these same branches. An `argMapping` is kept as the one string the
author typed, on the terms `graphitron_ast_enum_entry.argmapping` already states; the class and
method are columns of the decode; the spelling split is already on the row; and there is no
quarantine relation, the whole application standing verbatim in
`graphql_ast_applied_argument_entry`. The four stay on the incumbent's path until the anchors
dissolve it.

No ordinal is assigned. `@reference`, `@referenceFor` and `@routine` repeat, and two applications on
one field are two positions in the file; numbering them is the anchors' work, over rows that already
carry the position the numbering has to sort by.

`GraphitronEntries` becomes the facade and the shared vocabulary for reading an application, with
`GraphitronTypeEntries` and `GraphitronFieldEntries` under it, one writer per site. The type site's
`handlers` reader generalised to `elementsOf`, which the path-carrying directives use too.

`SupertypeSignatureGateTest` gained eleven sets and they read as one thing. A position-keyed entry
and the coordinate-keyed relation it will be derived into carry the same payload by construction:
that is what makes the derivation a `SELECT` with nothing decided in it, and it is exactly what that
gate reports. So the block is the ledger of what the anchor commit has to delete, spelled out rather
than exempted by a rule over the name. One set outlives the migration and says so: `@enum`,
`@service` and `@externalField` all record a class, a method and an `argMapping`, and collapsing
those needs a supertype at a coordinate none of them has.

`EntryNamingGuardTest` scanned only `GraphitronFactCapture` and would not have seen the new writers
at all, so a decode relation named without the suffix was unguarded the moment the replacement
started. It now scans the facade and both site writers as whole files, with no seam to find.

## The input-value site's decode (2026-09-10)

`GraphitronInputValueEntries` decodes the eight graphitron directives that reach an input value,
keyed by the position of the `@` token, which is `graphql_ast_input_value_directive_entry`'s key. One
writer over the three parents the parser treats alike: a field's argument, an input object's field,
and a directive definition's own argument. The incumbent has two relations here and routes an input
object's field down its field path, which is why `@asFacet` and `@lookupKey` sit under
`captureFieldDirective` there and here they do not.

Three of the eight get no relation. `@lookupKey`, `@orderBy` and `@asFacet` declare no argument at
all, so a row would carry its key and repeat what the applied-directive row already says.
`@reference` gets no row of its own either, its only argument being the path.

The third parent is live, and the note above said it would not be. No consumer schema writes a
graphitron directive on a directive definition's argument, but the bundled vocabulary deprecates
`@asConnection(connectionName:)`, so that arm of the union carries a row on every reading.

Written first against trunk and then rewritten onto the field site's structure, which had landed in a
sibling worktree meanwhile. Two designs of the shared vocabulary had been reached independently and
the field site's is the one kept: a facade the document walk calls once, site writers handed the
applications rather than the registry, and one sweep with a per-writer table list. The path element's
decode moved into it, both directives that carry a path spelling an element identically at both sites.

## Entry facts are what the author said, decided and normalized (2026-09-10)

The first two site decodes transcribed the directive's argument shape, and that is the wrong subject.
An entry holds a fact the author communicated through the schema, decided out of the syntax they
wrote and put in a relation holding facts of that kind and nothing else; the anchor is that fact met
with the catalog and the classpath. `fact-model.adoc` now states it, with the test to apply per
column: an attribute of the fact the row states stays a column, a different fact gets a relation.

Two framings were tried and discarded on the way, and both are worth recording because each looked
right. Transcribing the input type is what produced a nine-column step row: `ReferenceElement` is one
GraphQL input, so it looked like one thing, and it is three assertions. Splitting by what the anchor
would join is closer but still wrong, being a rule about today's consumers, and it gives the wrong
answer on `@mutation`: a table reaches `sql_table`, so the join rule splits it out, where the fact
rule keeps it because a mutation with a table and one without are the same kind of thing.

The evidence the subject was wrong was already in the readers.
`intent_field_reference_step_hop` has four arms and each is discriminated by which columns of the
step row are null. `@nodeId` respells `node_type_ref IS NULL` in six places, with two different
deduction rules behind the implicit case. A fact left undecided at capture is a fact every reader
decides for itself, which is what those predicates are.

What changed at the input-value site, which is the worked example. The two path-step relations became
six, by assertion rather than by directive: an element naming a table and a key and a condition
writes three rows at one list position, and the position is what says they are one step. `@field`,
`@referenceFor`, `@condition` and `@nodeId` took `NOT NULL` payloads and the writer now drops the
applications that cannot fill them, children included, a context argument hanging off a `@condition`
nobody decoded having nothing to belong to. A bare `@nodeId` writes no row, the deduction being what
the author asked for and the absence being what records it.

`SupertypeSignatureGateTest` regrouped and the regrouping is the argument for the split. The table
steps joined `graphitron_ast_table_entry`, naming a table being one fact wherever it is written, and
the condition steps joined `@enum`, `@service` and `@externalField`, an external code reference being
one fact likewise. Neither was visible while one row carried all three.

Still owed, on the same rule: the field site's two step splits, its `NOT NULL` pass and its bare
`@nodeId`; the type site's `@table` and `@record`, whose absent argument is a deduction the same way,
its two required references, and the `DATABASE` handler's two discriminators, which are alternative
spellings of one assertion and take a CHECK rather than a split.

A naming consequence, not acted on. These relations carry an `ast` infix that belongs only to the
parse-tree family; an entry here is a decided fact, not a node. The end state is `graphitron_<x>_entry`
beside an anchor named `graphitron_<x>`, which is the shape `SdlAnchor` already has and which the
incumbent blocks by holding the unprefixed names.

A dependency this creates. A malformed application writing no row is only safe while what the author
wrote is still recoverable, and today that is `graphql_ast_applied_argument_entry.value_sdl`, one
opaque string per argument. Recoverable by re-parsing, not queryable. Transcribing argument values as
nodes is what would close it, and it is the same gap that keeps the decode in Java rather than in
`INSERT ... SELECT`.

## The entry burn down (2026-09-11)

The question "is every entry fact captured" was settled by diffing lists rather than by reading
writers. Two diffs: the directive names `directives.graphqls` declares against the names
`GraphitronEntries` decodes, and the SDL node kinds the parser produces against the relations the
transcription family holds. What follows is what those diffs returned, ordered as the work will be
taken. The transcription family goes first, because its one remaining gap is what forces the decode
family to do its work in Java.

One rule governs the whole list, and it is the lesson of the argmapping work. That work took the
right step: it read a mini language out of a directive argument and wrote one row per pair, with the
path splitting done by generated columns rather than by a reader. It did not take the last step. The
raw string is still set on every owning relation beside the pairs, so two representations of one fact
sit in the store and a reader can pick either. And the pairs are keyed by coordinate with a nine
valued `site` discriminator, which is the anchor grain: the entry stratum does not hold them at all,
so nothing records where the mapping was written. **Decomposing a blob is only finished when the blob
is gone and the rows are keyed where the author wrote them.**

### The graphql_ family: the node kinds are complete, the values are not

Every node kind the parser yields has a relation, and the check is a reading of the DDL rather than a
belief. Type declarations carry their kind and their extension flag, so a `scalar` and an
`extend type` are rows on the same terms as an object. Implements clauses, union members, fields,
field arguments, input fields and enum values are each a relation with a written position and a
parent position. Directive definitions carry `repeatable` and hang their locations and their
arguments off themselves. The schema definition carries its extension flag and its operation types.
Applied directives have a relation per site, all five of them, and their arguments have one relation
between them, parented by position because a parent living in one of five relations admits no
declarable key. Descriptions are a column on every node that can be described. Arguments of directive
definitions reach the input value site, so a directive applied to one is transcribed like any other.

What is not transcribed is any value.

1. **Value trees were one opaque string, four times over. Landed 2026-09-11.** An applied argument holds
   its value in `value_sdl`, and an argument, an input field and a directive definition argument each
   hold a default in `default_value_sdl`. All four are printed AST. A directive argument is not a
   string in the schema; it is a tree of literals, lists and input objects, and the family says so
   nowhere. Everything downstream pays for that. The whole decode reads graphql-java `Value` objects
   through hand written accessors, which is the only reason it is Java at all: the writers cannot be
   `INSERT ... SELECT` while the thing they select from is a blob. It is also why "a malformed
   application asserts nothing" is enforced by a Java filter rather than by the absence of a row.
   What landed is `graphql_ast_value_entry`: one recursive relation over value nodes, keyed by the
   value's own written position, carrying the position of the node that holds it, the position of
   the enclosing value where there is one, the index or field name it sits at inside that enclosing
   value, its kind, and its written text for the leaf kinds. One relation and not four, where the
   directive sites get one each, because what forced the split there was that a key names one table
   and a directive's parent is one kind of node; a value's holder is four kinds, so no key is
   available at any grain and the reason to split is gone. The holder position is repeated on every
   node of a tree rather than held at its root, so one predicate fetches a whole expression, which
   is the choice `graphitron_argmapping_candidate` already made for the same reason.

   Two things the build taught. The parent reference is a real key back into the relation, and H2
   rejects it inside a single batched insert: a merge of a whole tree has no order for the
   constraint to see, so a child can be checked before the parent it names exists. The writer goes a
   level at a time, shallowest first, which is four statements for the deepest expression anyone
   writes and keeps the constraint. And the object field gets no row of its own, a field being a
   name and a value: the value is the row and the name is a column on it.

   A second consumer is already waiting on it, in the session dissolving the capture port. Eight of
   the nine lint rules there now read values rather than a parse tree; the ninth keeps its
   graphql-java node because it descends an applied directive's argument values to report deprecated
   input fields used inside an application, and the store held those values as rendered SDL with
   nothing to descend. That rule is the shape of reader this relation exists for, and it is why the
   decode is not the only thing the blob was costing.

   Still owed here: the four blob columns. They are not dead, `value_sdl` propagating into five
   anchor relations that the incumbent and the minting path both read, so the subtraction waits on
   the decode flip below rather than being part of this step. Both columns now say so in their
   comments.

2. **A written type expression truncated past one list level. Landed 2026-09-11, harvested from the
   session holding the connection work, and the reasoning this item first gave for deferring it was
   wrong.** Beside `type_sdl` sit `named_type`, `non_null`, `is_list` and `item_non_null`, which
   describe exactly one list wrapper, so `[[Film]]` and `[Film]` agreed on all four.

   This item's first answer was to design a chain relation, one row per wrapper, and then decline to
   build it on the ground that no reader needed it. The second half was false. The `@asConnection`
   expansion mints only where the carrier is a bare list of a named type, and the only way to ask
   that was to take the rendered expression apart a character at a time. `MacroCapture.element` is
   where it happens: trim, strip a trailing bang, require a leading bracket and a trailing one, strip
   the item's bang, then reject anything still holding a bracket or a bang, which is how a nested
   list is excluded. The reader existed; it had just paid for the missing column in string surgery
   rather than by asking for one. **Absence of a reader is not absence of a need, and a survey of
   what readers select will not find the need, because the need shows up as what they had to write
   instead.**

   What landed is `list_depth`, `NOT NULL` on the four entry relations, 0 for a named type, 1 for a
   list of one, 2 for a list of lists, under `CHECK (is_list = (list_depth > 0))` so the flag and
   the depth cannot disagree.

   Two corrections to how that was recorded, both found in the review below. The surgery is Java and
   not SQL: the commit that landed the column said the comparison happened in a statement, and this
   item repeated it. And the column does not yet serve the reader it was cut for. `MacroCapture`
   reads `graphql_field`, an anchor, and the column landed on the entries only, so the predicate that
   would replace the string parsing cannot be written yet. The commit deferred the anchors for a
   reason that is sound on its own terms, that they have a second writer in the walk and teaching a
   dissolving gatherer to fill a new `NOT NULL` column would duplicate the depth helper across a
   boundary a guard polices, but it did not connect that deferral to this reader, so the column reads
   as done when it is half placed. Item 10 below is the other half.

   Worth being precise about what that does and does not do, because it is not the chain relation
   and is better than it here. It is not lossless: `[[Film!]]` and `[[Film]]` still agree on every
   column, the inner item's nullability having nowhere to go. What it buys is that the four columns
   describe an expression *exactly when* the depth is 0 or 1, which is a claim a reader can check
   rather than a caveat it has to carry. A silent truncation became a bounded one, which is what the
   reader actually needed, and it costs one column against a relation and a writer.

   Two things left behind deliberately, both named in the harvested commit. A nested list is legal
   GraphQL and capture records it, but graphitron cannot represent one: its wrapper algebra is
   single, list or connection, so `[[String]]` builds as a list of a nullable string and the
   generator emits `[String]` code with no rejection saying otherwise. That is a fact about the
   generator and belongs in a detection over this column, which does not exist yet. And the three
   `graphql_` anchors carry the same four columns and the same one-level reading; they are still
   written by the walk as well as by the derivation, so propagating the depth waits on the walk
   going away, at which point it is one line in each of three selects.

3. **The node kind gate existed and was told to skip values. Corrected 2026-09-11.** This item said
   nothing gated coverage, which was wrong. `SdlEntriesTest.everyNodeParsedIsARow` counts the
   document twice, once as the store holds it and once by handing the same registry to
   graphql-java's own `NodeTraverser`, which descends every child without being told which children
   exist. It is exactly the gate this item asked for, and it was silent on values because the
   function mapping a node to its relation returned null for them: a deliberate exclusion, written
   when there was no relation to name. Naming the new one makes the gate count them, and it
   falsifies. Stopping the walk from descending into an object's fields turns twenty five nodes into
   twenty two and the gate goes red on the difference.

   The lesson is worth more than the fix. The gap was invisible not because nothing checked, but
   because the check had an exemption in it, and an exemption is a claim that ages. What is still
   open is the same question one level up: the gate proves every node the parser reads becomes a
   row, and nothing proves every relation the family declares is reachable from a document. That is
   the other direction and it is what item nine asks for on the decode side.

### The graphitron_ family: eighteen of thirty one directives decoded

The decode covers `@table`, `@record`, `@scalarType`, `@enum`, `@error`, `@field`, `@condition`,
`@reference`, `@referenceFor`, `@service`, `@externalField`, `@sourceRow`, `@asConnection`,
`@nodeId`, `@mutation`, `@pivot`, `@defaultOrder` and `@routine`. The rest of the list is what it does
not cover, plus what it covers at the wrong shape.

4. **Two of the five sites are unwired. Open.** `SdlEntries` transcribes types, fields, input values,
   enum values and schemas; the decode facade calls three. The enum value site is what strands the
   three directives below that live only there. The schema site is where federation's `@link` lands,
   which arrives from the consumer's corpus rather than from the shipped vocabulary and so shows up
   in neither diff; the incumbent carries five relations for `@link` and `@key`, which is the measure
   of what is missing.

5. **Thirteen directives have no decode. Open.** At the type site, `@node` with its key column list,
   `@discriminate` and `@discriminator`. At the field site, `@splitQuery`, `@tenantFanOut`,
   `@multitableReference` and `@experimental_constructType`. At the input value site, `@lookupKey`,
   `@orderBy` and `@asFacet`. At the enum value site, `@field`, `@index` and `@order`. And
   `@notGenerated`, which is declared at five locations and is refused by the classifier wherever it
   appears, so what the entry stratum records is an application the author should delete. Four of the
   thirteen carry no arguments at all, which makes them the payload free shape the VALIDATION handler
   already has: presence is the whole fact.

6. **The field site's reference steps carry nine nullable columns. Open.** The input value site split
   its steps into a key relation, a table relation and a condition relation, on the reading that a
   path element naming a table, a path element naming a key and a path element naming a condition are
   three different facts that a list index reunites. The field site's `@reference` and `@referenceFor`
   steps are still one relation each with all nine columns nullable. This is a direct mirror of work
   already done and is the cheapest item on the list.

7. **Three alternative bases share one row at `@defaultOrder`. Open.** `index_ref`, `primary_key` and
   the child field list are three ways of saying what a field sorts by, held as three nullable columns
   plus a child relation, so a reader tells them apart with `IS NULL`. `@order` on an enum value is
   declared with the identical three way shape, in as many words: exactly one of index, fields or
   primaryKey. Deciding this once settles both, which is the argument for taking it before the enum
   value site rather than after. `primaryKey: Boolean = false` is the interesting corner: absent and
   `false` say the same thing, so the fact exists only when the flag is true, and a payload free
   relation states it.

8. **argMapping and columnMapping are blobs in the entry stratum. Open.** `@routine` holds both, and
   `@service`, `@condition`, `@externalField` and `@enum` hold an argMapping inside their external
   code reference. Every one of them is stored as the written string. The pairs exist, in
   `graphitron_argmapping_entry`, but at the anchor grain and beside the blob rather than instead of
   it. Two things are owed: the pairs move to the entry stratum keyed where they were written, and
   the blob columns go. Whether a dotted path becomes a row per segment or stays a path with generated
   columns is the open part; the generated columns work today and the segment relation would be the
   consistent choice against how the value trees above are modelled.

9. **Nothing gates directive coverage. Open.** The vocabulary is a file we ship, so a gate reading it
   and asserting every declared name reaches a decode is cheap, and it is the only thing that stops
   this list from silently reopening. The incumbent's own answer to the same problem is
   `graphitron_undecoded_argument_entry`, a relation recording what it failed to model, which is worth
   noting as a different and weaker choice: it makes the gap visible at run time to whoever reads the
   store, rather than at build time to whoever opened the hole.

10. **`list_depth` reaches no reader, because the reader is on the other side of the entry boundary.
    Open, and it is item two's other half.** The column is on the four entry relations. The reader
    the column was cut for, `MacroCapture.element`, joins `graphql_field`, which is an anchor and has
    no such column, so the string parsing that motivated the whole thing is still there and the
    column is `NOT NULL` on four relations with nothing selecting it.

    What that needs is settled by the chapter below rather than by a choice here. The reason the
    anchors could not take the column is that they have a second writer, and that writer is now
    measured as redundant, so the answer is not "make it nullable" or "copy the depth helper across
    the boundary" but "the walk goes, then the column lands". After it, `SdlAnchor` carries the
    column in one field of each of three selects, `element` becomes a read of `named_type`,
    `item_non_null` and `list_depth = 1` off the row the carrier query already fetches, and the
    character-at-a-time parser goes with it.

### The order

One through three, then four onwards, is the order the two families are named in, and it is also the
order the dependencies run. Value trees first, because every item from five onwards is a writer that
would otherwise be written twice: once against graphql-java accessors and again after the blob is
gone. That first pass is done, and with it the graphql_ family: one landed, two landed
from another session with this item's reasoning about it corrected, three found to be a gate that
already existed. Six and seven next, being normalizations of relations that already exist and whose consumers
are inside this arc. Then the sites and the thirteen directives, which is the bulk of the remaining
volume but is mechanical once the decode is a select. Eight is placed after the value trees for the
same reason and is the one item whose target shape is not yet settled. Nine is a gate and lands with the work it
guards rather than before it; three turned out to be a gate that already existed and needed one
line. Ten arrived from the review below rather than from the two diffs, which is worth saying because
neither diff could have returned it: both ask what the entries hold, and ten is about a column the
entries do hold and a reader that cannot reach it. It goes with the walk's dissolution, not with the
rest of this list, because that is the thing standing in its way.

What the chapter after next changes about this order is not the order but the reason for it. Four
through nine read as completeness, one more site and one more directive until the list is empty.
They are not: they are the remaining blockers on deleting both walks, and the walks are most of what
this arc exists to remove.

That reframing was the whole of the plan this item carried for a while, and a reason to keep going is
not a plan. "The capture layer dissolves into one linear read" is, and it supersedes this list as the
statement of what happens next: it names the end state, orders the moves toward it, and gives each a
gate. Items one through nine keep their content and their relative order. What moved out of here is
the sequencing argument and, with the three-anchor chapter, item seven, which turned out to be a
precondition on every move rather than a slice waiting its turn.

## The port dissolution, folded in (2026-09-11)

Two commits from the session working the capture port arrived as exploration marked not for trunk,
which is a judgement their author was not the one to make: the arc has no item of its own and this
item is carrying it. Both fold in, and one of them bears directly on machinery already recorded
above.

**A build's store is build output, and that makes its ownership binary.** The store moves under the
module's build directory, where the three resolvers beside it already put their output, and the
`<storeDirectory>` parameter moves down to the dev goal, which is the only one that needs it. The
reasoning is a distinction this item had not drawn: a session's store is shared, its language server
and its readers answering from it between builds, so it has to outlive a clean and be reachable from
a module that is not the one being built. A one-shot goal has neither need. One store per module,
holding that module's one graph, removed by a clean like everything else a run produces.

What that decides is not the location. It is that "delete exactly what this run owns" becomes
"delete everything, it is all ours", and most of what `RunStore` and `StoreRefresh` do is police the
sharing it removes: ownership arbitration between graphs, retain-or-rewrite per classpath source,
and a retention seed scoped so a sibling's partition cannot block this run's row. The self-sweeping
disclaim this item added to the clear, which reads `meta_relation` to find the relations whose
gatherer sweeps its own rows, is paid for by the same sharing. None of it is touched here and none of
it becomes dead, the dev session still sharing; what changes is that the machinery is a session's
concern rather than every build's, which is the smaller thing to have to keep true. The commit is the
decision, not the simplification, and the simplification is now available to take.

One consequence the commit did not name, recorded here because it is a capability and not a detail.
`store_graph_supergraph` says which supergraph a graph declared itself part of, and the point of a
grouping fact is asking it across the group. In a build's store there is one graph, so the relation
holds at most one row and the question cannot be put to it at all. Nothing loses an answer today:
the readers that would ask are the session's, and the session keeps the workspace-wide home where
every module's graph still lands. But the relation now answers only in one of the two kinds of
store, and a reader written against a build's store would find it silently degenerate rather than
empty for a reason. Worth knowing before anything is built on it.

Riding with it, `schemaFiles` moves off `SdlCapture` onto `SubjectConfig`. It took the configuration
as its first argument and consulted nothing else, which is the shape of a method on it, and it leaves
the capture a writer of rows rather than an expander of recipes.

**Eight of nine lint rules stopped reading structure out of a parse tree.** `LintTarget` carries
values instead of a node: name, description, source position, the enclosing type and whether it is a
root operation, and an applied directive's arguments. Every one is a column the store holds, so when
the traversal becomes a query the rules do not change, which is the point.

That sentence is deliberately narrower than the commit's. The commit said the eight no longer touch
graphql-java, and two of them still name `SourceLocation`, one constructing a position and one
reading a column off it. The type is not a reach into the parse tree, a position being a line and a
column, but it is a graphql-java type in the rules and in the record's own signature, and the
difference matters for the claim being made: the rules will need an edit the day the walk becomes a
query, just a mechanical one. Getting rid of it is not a lint change. `SourceLocation` is the shared
vocabulary of the whole diagnostics surface, carried by `BuildWarning`, `ValidationError`,
`SchemaParseException` and the fix records the language server projects, so replacing it here alone
would leave two position types inside one record family. It is a diagnostics item and not one of
this arc's, and `LintRuleIsolationTest` now holds it as a named exemption so it cannot quietly widen
into something else.

Two details the move had to get exactly
right, both recorded in the commit: the description column stays raw, because one rule asks whether
the author documented the node and two rename fixes ask whether a description token occupies the
source ahead of it, and normalising would silently change when a fix is offered; and the argument map
holds a null for an argument written as something other than a string, because passing a number is
still passing something, which is the difference between a fix and a plain report.

The ninth rule keeps its node, and the reason the commit gave for it had already lapsed. It descends
an applied directive's argument values to report deprecated input fields used inside an application,
and the store held those values as rendered SDL with no structure to descend. That was true when the
commit was written and stopped being true the same day: item one of the burn down decomposes every
written expression into rows. The residue stands for a different reason, which is the honest one:
that rule walks where the others look up, and one row per argument name cannot serve a walk. The
rows it wants exist now, so this is remaining work rather than an open question, and the projection
the record already carries is the one the query will make, an argument's name against its value
where that value is a string.

One caution, discharged rather than assumed. The harvested store move was verified against the plugin
and the model, two modules, and it changes where every module's store lives. Run over the whole
reactor it holds, 7619 tests green, with the example module's store visibly landing under its own
build directory. The sibling-partition guard this item leans on elsewhere, which is what proved the
clear's disclaim is scoped rather than switched off, still passes: the dev session's sharing is the
population it covers and that is the half the move keeps.

## The harvest, reviewed (2026-09-11)

Folding two commits in is a decision about direction. Whether they meet the bar is a separate
question and was not asked at the time, so it is asked here, against the tree rather than against
what the commits say about themselves. The direction holds in all three, counting the `list_depth`
commit harvested a day earlier. The evidence does not, and it fails the same way in each: a decision
is argued well and then shipped with nothing that would go red if the decision were reversed. That
is the one thing the value-tree work does differently, and it is what this section closes.

**The argument map's null was protected by nothing, and the measurement says what it costs.** The
lint record holds an applied directive's arguments as a name against its value where the author wrote
a string and against null where they wrote anything else, built by hand because `Map.copyOf` rejects
a null value. The commit argues that carefully and ships two test cases, a bare `@deprecated` and one
with a string reason, neither of which reaches the null. Writing the third case,
`@deprecated(reason: 5)`, and then swapping the rejected alternative back in gives thirty three
passes and one error: a `NullPointerException` out of the engine's own run, not a misreport. So the
alternative the commit rejects crashes a consumer's lint pass, and the suite had nothing to say about
it. The commit's stated evidence, that no test changed, is evidence for the eight rules it moved and
none at all for the two decisions the record itself makes.

The rest of the review, in the order it is being worked.

1. **Two records state what this branch falsified.** `LintTarget`'s node component says the store
   holds an applied directive's argument values as rendered SDL with no structure to descend. Item
   one of the burn down made that false the same day. The correction went into the commit message
   and the false sentence stayed in main sources, which is the wrong half to fix. Alongside it, the
   `list_depth` commit motivates itself by string surgery over `type_sdl` in SQL. The surgery is
   real and it is in Java, `MacroCapture.element` taking the rendered expression apart a character at
   a time, and no statement anywhere compares `type_sdl` to anything. This item repeated the SQL
   claim and added one of its own, that the connection predicate is `list_depth = 1`, in the present
   tense, when there is no such predicate and there cannot yet be one: the reader is on an anchor and
   the column is on the entries. Both records now say so, and the placement is item 10.

2. **The consumer manual now lies, and the gate that exists to stop that cannot see it.** The store
   move left the `storeDirectory` row under the manual's shared-parameters heading, which says the
   parameters below apply to every goal, describing a default that is no longer the default and
   telling the reader to set the parameter to keep the store inside the build, which is now where it
   already is, and closing with the claim that a clean no longer removes it. The page's own summary
   counts eleven shared parameters and four dev-only ones; it is ten and five. `MojoDocCoverageTest`
   stayed green because it unions every parameter table against every goal, so a row that changes
   which goal owns it is invisible to it. A gate whose exemption is structural rather than listed,
   which is the same shape as the census gate's silence about values.

3. **The store move's load-bearing half has no test.** Nothing in the tree names
   `resolveStoreDirectory`, before the move or after. Removing the dev goal's override moves a
   session's store into the build directory, where the next clean deletes it and the readers it
   serves go cold between builds, which is the exact failure the override exists to prevent. The
   invoker case covers the base class's new default and nothing covers the override.

4. **Nothing guards the isolation the lint move exists to create.** No test asserts the rules package
   is off graphql-java, though this repository has the pattern three times over. Without one the next
   rule reaches for the node and the arc reverses quietly. The exemption wants to be a named list of
   exactly the residue, so that shrinking it is a deliberate edit rather than a number going down.

5. **The claim that eight rules are off graphql-java is not what the code says.** Two of them import
   `SourceLocation`, one constructing one and the other reading its column, and the record's own
   signature carries that type. So the rules do still know about a parse tree, and the central claim,
   that they will not change when the walk becomes a query, is not yet true. The store holds the
   position as two integers; our own position value is what makes the residue genuinely one rule.

6. **The record's description column is not total over the kinds the engine dispatches, and its
   javadoc says it is.** The reading has seven arms and a default that answers null, while the engine
   also dispatches input fields, field arguments and enum values, all three description-bearing. No
   rule reads it at those kinds, both consumers guarding by kind first, so nothing is red. That is
   burn down item three's lesson word for word.

7. **Three helpers were left in the wrong class and widened so a subclass could reach them.** The
   cache root, the workspace segment and the workspace root have exactly one caller now, and their
   javadoc explains a policy the class holding them no longer has.

8. **Two pins are inert and their comments still take the credit.** The unit tier's pinned home and
   the invoker's both claim to be what stops a run writing the developer's real cache, true now only
   for the dev goal. The invoker's adds that its three cases share one store under three artifactIds,
   a free extra exercise of the multi-graph store, and that exercise is gone: each case writes its
   own store under its own clone. The sibling-partition guard still covers the population at the unit
   tier, so nothing is uncovered, but the loss was silent.

9. **What the supergraph relation can say has narrowed, unrecorded.** One graph per store means a
   build's store holds at most one row in it and the grouping is unqueryable there. The dev session
   keeps the workspace-wide home and is where the readers are, so no answer is lost today. A designed
   capability scoped down belongs in the record either way.

Two things were checked and are clean. The readers are handed their handle and resolve no home of
their own, which a boundary guard in that module already enforces, so the move's reasoning about them
holds. And the per-version sweep still runs under the new home, so nothing accumulates that did not
accumulate before.

## One gatherer per thing an author writes, not one index of every class (2026-09-11)

The classpath is captured as a census of every public class on it, and that is the wrong shape. The
census exists for nameability: `ClasspathScanner`'s own charter calls it "the set a schema is
permitted to name", and the editor reads it for completion, hover and diagnostics. The generator
reads none of it. Every Java-facing decision it makes is taken by loading the class reflectively
through the codegen loader, across a hundred-odd sites in `ServiceCatalog`, `ScalarTypeResolver`,
`ClassAccessorResolver`, `RecordBindingResolver`, `InputBeanResolver`, `LifterMethodResolver`,
`ErrorChannel` and `FieldBuilder`. So the store holds a broad, shallow index nobody generates from,
and the facts a build actually turns on are held nowhere.

The census's one exclusion rule is where the defect shows. It skips the generated jOOQ package,
correctly, because an author may not name a generated record in `@service`. That is right for
nameability and wrong for assignability, and `sql_table_record_supertype` exists to patch the
difference from the catalog side. One index, one scope rule, two purposes that want different ones.

**The replacement is a gatherer per thing an author writes.** Each declares its own corpus and its
own fact shape, so the scope rule sits with the purpose rather than being shared by everything.
Five of them read the reactor, meaning the Maven multi-module build the run is part of:

1. a service gatherer, over what may appear in `@service(service:)`
2. a condition gatherer, over what may appear in `@condition(condition:)`
3. an externalField gatherer, over what may appear in `@externalField(reference:)`
4. an enum gatherer, over what may appear in `@enum(enumReference:)`
5. a reference gatherer, over what may appear in `ReferenceElement.condition`

There is no record gatherer and no argument gatherer. `@record` is deprecated and ignored, its own
definition saying the backing class is inferred and there is no successor directive, so nothing is
owed it. Arguments are not a subject of their own: the five above each capture the arguments, the
return type and the declared exceptions of the methods they capture, because those are facts about a
method and a method has exactly one gatherer.

Two further arms read the classpath rather than the reactor, and each has a reason the reactor
constraint cannot hold. The `GraphQLScalarType` static fields `@scalarType(scalar:)` names are the
first, and the history says why rather than the reasoning: the scan once skipped everything that was
not a directory on the premise that consumer vocabulary lives in reactor source, and
`@scalarType(scalar: "graphql.scalars.ExtendedScalars.Date")` falsified that outright. The second is
the throwables an `@error` handler names, described at the end of this chapter.

**Eager rather than lazy, and the reactor constraint is what makes that affordable.** Capturing only
what a schema already references would make the store's contents a function of the schema, so an
editor could not offer a method nobody had written yet, which is most of what completion is for.
Anything we know how to reflect on when referenced we know how to reflect on when not. The cost is
the whole question, and bounding the population to reactor classes is the answer to it.

The constraint is not a narrowing of what consumers do. Counted over every `className:` written in
this repository's schemas, 198 references land as 191 reactor, four `java.lang`, two
`org.jooq.exception` and one `org.jooq.impl`. The last is the `transitive-not-nameable` integration
test, a negative fixture proving that naming it is refused, so it argues for the constraint. The
other six are `@error` handler classes, and the arm that answers them is the last section here.

**One family, one model, one main gatherer.** The seven arms share most of their shape, a class and a
member and the types around it, so they are a `code_` family with one model behind them rather than
six unrelated writers. What differs between them is the corpus each reads and the predicate each
admits a candidate on, which is exactly what a per-arm gatherer is for.

**What the arms have to capture that nothing captures today.** The discriminators the generator
already uses are in `ReflectionError.AmbiguousMethod`: name shared, static modifier, return type,
parameter count, throws clause, parameter position. Of those, neither census holds the static
modifier on a method, the throws clause, or constructors, which `InstanceHolderUnconstructible`
needs because an instance `@service` method is dispatched through a public constructor whose
parameters are each a `DSLContext` or a declared context argument. And the newer census erases
generics where the older one kept them: `jvm_method.declared_return_type` reads the classfile
Signature attribute and holds `List<Film>`, `Field<String>`, `T`, while
`jvm_classfile_method.return_type` is the descriptor's erasure. A service returning a list of
something cannot be read off the newer family at all.

**Two candidate sets that disagree, and the authority question between them.** A reactor class has
bytecode and source alike, and they hold different facts: the classfile has erased types and the
Signature attribute, the source has parameter names unconditionally and javadoc. The `java_` family
already exists for the source side, written by `JavaSourceFacts` per file against a content hash, so
an unchanged file costs one hash and no write; what it holds today is coordinates,
`parameter_count` and javadoc, and it is driven only from `graphitron:dev`. Which side is
authoritative is a per-fact decision and it should be taken the way the catalog side was: ask the
artifact that owns the fact. Parameter names are a source fact, since `ReflectionError.ParameterNamesMissing`
is a live refusal for a consumer compiled without `-parameters` and the source never loses them.
Erased types are a bytecode fact.

**A seventh arm, over every throwable the classpath holds.** Six of the seven out-of-reactor
references above are `@error` handler exception classes: `java.lang.IllegalArgumentException`, and in
`graphitron-sakila-example`'s own schema `org.jooq.exception.IntegrityConstraintViolationException`
on two error types. No reactor method declares either, an unchecked jOOQ exception appearing in no
`throws` clause, so none of the five method arms reaches them and the reactor constraint cannot
apply. This arm harvests every throwable on the classpath instead, which is affordable because the
population is small and self-limiting: measured over `graphitron-sakila-example`'s compile classpath,
154 entries and 19901 classes yield 418 throwables, one class in forty-eight.

Which classpath that is taken over matters more than the figure. `GenerateMojo`, `ValidateMojo` and
`CaptureMojo` declare `ResolutionScope.COMPILE`, and `DevMojo` hands `resolveCompileClasspath()` to
the census even though it resolves test scope for its own incremental compiler. A test-scoped
dependency is therefore never in the census and this arm never sees one. The same module measured
over its test classpath yields 1193 throwables across 62362 classes, and the difference is almost
entirely testcontainers at 175 and groovy at 87: a count taken over the wrong classpath overstates
the arm threefold and points its ranking at libraries no build reads.

What it captures beyond the name is what makes the harvest usable rather than a list. The classpath
entry it came from, so a consumer's own exception is told from a library's; whether that entry is
reactor, declared or transitive, which `ClasspathEntry.Origin` already classifies for the scanner;
and the supertype chain, which is what a `DATABASE` handler's match against any `java.sql.SQLException`
in the cause chain reads and what sorts a specific exception under its family. Unchecked
outnumber checked 299 to 119, and the distinction matters at the author's end rather than ours: a
checked exception is one their own signature can name, an unchecked one is what jOOQ throws past
them, which is exactly the `IntegrityConstraintViolationException` case above.

Relevance has to be a stored axis rather than a reader's guess, and the measurement is what says so.
The largest origins are graphql-java at 81 and jOOQ at 29, then a tail of Jakarta, Vert.x, Netty and
Jackson at nineteen or fewer each. Only jOOQ's, with the JDK's `java.sql` set beside them, are what a
consumer maps an error from; the rest are transport and serialisation internals nobody names. So the
ordering a completion list wants is not the harvest's own, and a reader given only names would have
to invent the ranking. The facts above are what it ranks on.

That leaves this chapter with no open question. What it does leave is a measurement taken on one
classpath, and ours at that: `graphitron-sakila-example` carries the whole Quarkus runtime because it
is an example rather than a library, so 418 is an upper bound on a leaner consumer and a floor on
nothing.

## The SDL walk is redundant, measured (2026-09-11)

The classpath side of this item is replacing one broad index of every class with a gatherer per
thing an author writes, on the ground that one index with one scope rule cannot serve two purposes
and grows a patch relation where the purposes differ. The SDL side has the same defect in a
different key, and it had never been stated: the `graphql_` anchors have two producers.

`SdlFactCapture` walks a merged registry with graphql-java accessors in hand and writes twenty seven
relations. `SdlAnchor` derives twenty six of them out of the entry stratum in SQL. Both run on a
mojo build, the derivation first and the walk second, both writing with an upsert, so **every anchor
row a reader sees is the walk's and the derivation's is overwritten unexamined.** Nothing chose that
arrangement and nothing states it. What it costs is not the duplication, it is that a column added
to an anchor has to be taught to two writers in two languages, which is exactly what stopped item ten.

**So the question was measured rather than argued.** `SdlWalkIsRedundantTest` captures one corpus
twice, once by each producer, into two stores, and compares the twenty six shared relations by
primary key and then column by column, excluding the instant, because two readings are two instants
and that is what the column is for, and the generated columns, which are a function of the row
beside them and would report one disagreement twice. The corpus carries every declaration form, both
directive sites that take arguments, an interface, a union, an enum, an extension and a schema block,
so agreement means something.

The first run returned eight relations, and the shape of the answer is the useful part. Five differed
in one column and it was the same column: the derivation numbered ordinals from one where the walk
and the schema number them from zero. Six of `SdlAnchor`'s eleven ordinal computations already
subtracted one and five had not, and a sixth defect fell out of fixing them, a base declaration
selected as `merge_ordinal = 1` that had been consistent with the derivation's own off-by-one and
with nothing else. The column comment says it outright, "on a base-less chain the first extension
holds 0", and a density gate says it again. Three relations differed by five rows each, the scalars
the specification gives every schema, which no document declares and the entries therefore do not
hold. Those are the engine's facts rather than an author's, so they belong to the derivation as a
constant and not to a transcription as rows, and the walk's own comment already framed them the same
way: an existence row and no declaration site.

**With those closed the derivation writes what the walk writes, exactly, and the case is green.**

The twenty seventh relation needs no closing. `graphql_duplicate_declaration` is the overflow of a
coordinate-keyed grain: the anchors admit one row per coordinate, so the losing occurrence of a
duplicate has nowhere to go and lands there rendered as text. Its own comment already names it "its
family's overflow relation" and pairs it with `graphitron_undecoded_argument_entry`, which this
item's burn down had separately called the weaker choice. The entry stratum is keyed by position,
where a field declared twice is two ordinary rows, so the shape that needs an overflow is the shape
the entries replaced. Nothing in the tree reads it. A second case pins that: the walk fills it, the
derivation leaves it empty, and the same duplicate stands in the entries as two rows.

**What this is worth.** The SDL walk is 1234 lines. It cannot go alone: the graphitron decode is
driven by its traversal, taking a site reference from it and borrowing its position helpers, so the
two are 2690 lines that leave together. With them go the sink's record-binding arms for twenty seven
relations, the overflow relation, and the second writer that makes every anchor column cost twice.

**What that changes about the order.** Nothing, and that is the point. Items four through nine of the
burn down read as completeness, one more site and one more directive until the list is empty, and
that reading undersells them: they are the remaining blockers on deleting both walks. The decode has
to be complete in the entry stratum before the traversal driving it can go. The prize was not visible
while the SDL half was assumed to be load-bearing, and it is measured now rather than assumed.

**Then the same question was put to the whole reactor rather than to one corpus.** A case over one
schema is evidence about that schema. So the running order was inverted in a throwaway experiment,
the entries and the anchors derived from them running after the walk inside the pass, which makes
the derivation's rows the ones every downstream reader sees: the classification stages, the intent
derivations, the generator, the pipeline tier, the integration builds. Every behavioural test in the
reactor passes. The one failure was `GathererIsolationTest` objecting that the pass named
`SdlEntries` and `SdlAnchor` directly instead of going through their package's face, which is a
remark about the experiment's shortcut and not about a row. An earlier round of the same experiment
had two failures, both one duplicated diagnostic, because the face also writes schema problems and
running it twice reported the assembly's error twice; narrowing to the entries and the anchors left
none.

So the walk's `graphql_` half is not redundant on a fixture. It is redundant against every reader in
the tree.

**Which makes the answer to "delete it" precise, and it is half yes.** `SdlFactCapture` is two things
sharing a file. One writes the twenty seven relations and is dead weight. The other is the traversal
that drives the graphitron decode: the walk holds a `GraphitronFactCapture` and calls into it at each
directive site with the coordinate and the per-name application ordinal. That half cannot go until
the `graphitron_` anchors are derived the way the `graphql_` ones now are.

Written the same day this was, and corrected here rather than left standing: that derivation is not
absent. `GraphitronAnchor` arrived from the session working the entry migration and is
`SdlAnchor`'s counterpart by the same idiom, one statement per anchor, an insert over a select, run
once the corpus is read. It holds three relations. The walk writes fifty one of the seventy one, so
what is owed is a size rather than a beginning, and burn down items four through nine are the entry
side that derivation reads from.

The two halves are interleaved rather than layered, which is why the file cannot simply be cut in
two: `captureSite` writes a declaration row and then dispatches that site's directives,
`captureFields` writes a field row and then dispatches the field's. What a strip removes is
twenty four row writes, twenty claims, seventeen quarantine calls and the whole of `SdlCoordinates`,
leaving a traversal that computes directive ordinals and makes five decode calls. The claim and
quarantine machinery goes with them, being the duplicate detection that feeds the overflow relation,
which is the one part needing care rather than mechanism: a claim that currently skips a duplicate
also stops the decode seeing it, so removing it changes what the decode is offered, and that has to
be established before it is assumed.

Two things this deliberately does not do. It does not strip the walk, which wants its own
verification rather than riding on this one. And it does not leave the running order inverted: with
the walk still writing last, the six defects fixed here stay invisible in production until the strip
lands, and the case above is what will notice if they come back.

This chapter called the strip "the next commit" and left it at that, which is how it came to be the
largest deletion on the arc with no item of its own. "The capture layer dissolves into one linear
read" answers that: it is not a commit, it is what the last reader's move leaves behind, in that
chapter's phase 2. The order flip this chapter owed goes with the second pass it was an ordering
between; the duplicate-claim hazard above survives as a fixture that phase owes.

## The catalog family had two producers too (2026-09-12)

The SDL walk is not a special case. Every family the two capture entry points reach has a gatherer
each: the SDL walk against `SdlAnchor`, `CatalogFactCapture` against `JooqFactCapture`,
`ConfigurationFactCapture` against `StoreEntries`, and the classpath pair the code family has already
half dissolved. The two entry points did not merely duplicate an orchestration. They doubled the
capture layer, and every family is mid-migration between its pair.

The catalog pair is now one. What it took is worth recording, because three attempts failed first and
they failed the same way.

**A relation name is not a row.** Both gatherers named the same fourteen relations, which is what
made them look interchangeable, and swapping the pass onto the newer one turned forty four classes
red. A second attempt fixed one measured difference and turned the same forty four red. A third
guessed at another and did it again. Each guess came off a grep and was tested with a twenty minute
reactor build, which is the most expensive instrument available and the least informative.

**What worked was a cheap instrument used first.** Capture the same catalog twice, once by each
gatherer, into two stores, and diff row counts for every relation the schema declares. One minute.
It named three gaps in the newer gatherer, and the important one was not on any list a person would
have written:

1. `sql_enum_binding.table_schema` and `.type_name` were literal nulls. The binding named the Java
   class and not the database enum it stands for.
2. Neither `store_source` nor `store_graph_source` was written. This is the one that mattered. The
   `sql_` family keys on the source, so every graph-scoped view joins membership to scope it; absent
   it, the catalog is captured and every view over it returns nothing, which is exactly what forty
   four classes were seeing while three diagnoses blamed something else.
3. `sql_table_record_supertype` was not written at all, and genuinely could not be derived: the
   classpath census drops the generated jOOQ package on purpose, so no census edge climbs out of a
   generated record and only the catalog walk holds the record class.

**Why a hand-picked list could not have found the second or third.** The first instrument compared
relations both gatherers were known to write, which is the intersection, so a relation only one of
them populates is invisible to it by construction. Diffing the whole schema costs a dozen more lines
and is the difference between an instrument that answers and one that reassures.

**The sink is why membership was free.** A gatherer writing through `FactSink` gets its graph stamped
on everything, and notes membership as a side effect. One writing through the `DSLContext` has no
graph and must say so. That is not an argument for keeping the sink: it is what the sink was hiding,
and the newer gatherer now registers its own sources and states its own membership, which is what
`SdlCapture` and `CodeCapture` already do for themselves.

**The instrument was deleted when the two became one.** It was a migration tool and not a gate: run
once, act, dissolve. A standing comparison between two producers is a standing acceptance that there
are two.

What is left in the older gatherer is the classpath references, which it alone can produce; fifteen
helpers went with the catalog half, 682 lines down to 222. The configuration pair is next, by the
same method. "The capture layer dissolves into one linear read" takes the method above as the gate
every later collapse takes, and demotes the configuration pair from a project to a deletion: with one
capture entry point the two gatherers are adjacent lines in one method, and one writes a superset of
the other.

## The consumers, and the order the read side moves in (2026-09-12)

The burn down counts what capture writes. What anything reads had not been counted, and it decides
the order: a relation with no reader is not a migration step, and a reader still on the incumbent
shape is one whether the burn down lists it or not.

**How it was counted.** The main sources of the three consumers were scanned for relation names, the
names resolved against the DDL, and each expanded to the relations nothing derives: a view through
its definition, an anchor through the `INSERT ... SELECT` that fills it. That second expansion is
what a reading of the schema file alone misses, the store having two derivation mechanisms of which
only one is SQL. Anchors are tables filled from Java, one statement each, twenty six in `SdlAnchor`;
the intent layer is 114 views over 27 tables.

| | relations named | files naming them | roots reached |
|---|---|---|---|
| `graphitron` | 17 | 2 | 32 |
| `graphitron-mcp` | 43 | 8 | 69 |
| `graphitron-lsp` | 57 | 14 | 82 |
| union | 81 | 24 | 89 of 210 |

**The generator is barely a consumer of the store**, naming seventeen relations in two files, because
six of `EmitPlan`'s seven producers read `GraphitronSchema` and the layer building it is 285 files
and 82194 lines. A plan measuring progress by the generator's read count reports nothing for a long
time and then everything at once. `RoutineWriteFacts` is what the other six become and states the
discipline itself: nothing there decides anything, membership is the seat relation's verdict.

**The language server reaches lowest and that is correct**, naming sixteen entry relations. An editor
asks what is written at a cursor; the entry stratum is keyed by the position a node was written at
and the anchors by coordinate. The grain of the question picks the stratum, which is the first
evidence from a real consumer that both keys are wanted.

### Three strata, and the one that is mis-keyed

| | key | references | carries |
|---|---|---|---|
| `graphitron_ast_*_entry`, 59 | position | `graphql_ast_*_entry` | authored text, position |
| `graphitron_*_entry`, 56 | coordinate | the `graphql_` anchors | both, wrongly |
| the `graphitron_` anchors, 18 | coordinate | the other families' anchors | resolved facts only |

The middle row is not an anchor wearing the wrong suffix but an entry wearing the wrong key. A
transcription says what stands at a position and references the `graphql_` entry it decorates; it
cannot reference an anchor, the anchors being derived after the documents are read. The incumbent
references `graphql_type_element` and `graphql_type_declaration` and keys on the coordinate, which is
one mistake stated twice: it resolved at capture time because the pipeline writing it held a merged
registry and had no stratum to put an unresolved fact in. The anchors carry neither a position nor
the text an author typed: `graphitron_tabletype` holds a resolved schema and table,
`graphitron_field_table` a `target_basis`, `graphitron_node` a `type_id_origin`. So a reader picks
its stratum by what it asks for, and seventeen facts are currently stated in both.

**What the new shape costs to write is already measured.** The same site, decoded both ways:

| | `GraphitronFactCapture` | `GraphitronFieldEntries` |
|---|---|---|
| lines | 1359 | 627 |
| `if` / `else` | 91 | 2 |
| `switch` / `case` | 45 | 0 |
| `instanceof` | 13 | 0 |
| null checks | 40 | 0 |

The incumbent branches because it decodes and resolves in one pass with nothing captured to fall back
on, so every question it cannot answer from the node in hand becomes a conditional. The replacement
is twenty one statements and two conditionals because transcription has no decisions in it. The
resolution those 149 branch points performed is what one `INSERT ... SELECT` does per anchor.

### The fixture had to capture the way a run does

Every read that moves to an entry was blocked by the fixture level, not by the query: `CapturePort`
captures through `ModelCapture` and `CapturedStore` captured through the walk alone, so the entry
strata were populated for a real run and empty in every test. A level promising that a fixture cannot
encode a state capture never writes was keeping half of that promise.

The gap was measured before it was closed. One corpus captured twice, every relation counted under
each: the `graphql_` anchors agree exactly, thirty one directives, fifty two elements, twenty three
fields, eighteen types. Only the capture writes the entry strata, the configuration corpus and the
problem rows; only the walk writes the `graphitron_` decode and its anchors. Neither is a superset,
so a swap was never available and running both is.

**Order is the whole of it.** Both write the `graphql_` anchors and agree on the rows, but the walk's
sink inserts where the derivation upserts. Derivation first fails on its own rows at the first
directive either writes; walk first lets the derivation replace identical values in silence. This is
an augmentation and not the rebuild the level eventually wants: `SeededStore` and `CapturedStore`
predate `ModelCapture`, and rebuilding them on it is gated on the walk strip, the walk still writing
fifty one of the seventy one anchors.

**Two defects fell out, both invisible until one store held both families.** The three deprecation
anchors referenced the `graphql_` declarations without cascading, so a warm recapture clearing a
directive argument was blocked by the marker pointing at it; every sibling cascades and these now do.
And both producers numbered a type's arguments with one counter spent across
the type, where `graphql_argument.ordinal`'s comment already said "declaration order within the
field". An argument belongs to a field and needs no counter surviving the site boundary, the field
carrying it being claimed once; the type's counter is what fields, enum values and union members
need, and it was spent on arguments too. Nothing could tell: the column's only consumers are views
that window by field before ordering on it, and the generator never sees it. Both writers now number
within the field.

The durable part is how it stayed hidden. `SdlWalkIsRedundantTest` compares this relation and passed,
its corpus having no type where an extension annotates a second field, so with one argument-bearing
field per type the two numberings coincide and a case built to prove the producers agree was blind
exactly where their counters differ. The corpus has that type now, and reverting either writer makes
the case name the column.

### The order

`@table` is done, and it is the shape the rest take: `Hovers` wanted the table a node type binds to
and had been taking the authored text, splitting a qualifier off the last period, standing the type
name in where the argument was absent and matching the catalog with `equalIgnoreCase`, which are
three steps `TableTypes` takes once per corpus and a fourth that no index can serve. It reads
`graphitron_tabletype`. `BindingUsages` wanted the site a binding was written at, so it reads
`graphitron_ast_table_entry` and reaches the coordinate through the reference the transcription
carries, to the type directive holding its declaration's position and from there to the declaration
naming it. One resolved question and one positional question, answered from the two strata.

1. **The five remaining covered reads**, `service`, `routine`, `mutation`, `external_field` and
   `field_node_id`, each with a correct entry written and no anchor yet, so each owes the anchor
   `@table` already had.
2. **`field_reference`**, where the new stratum split one relation into table, key and condition
   steps. The anchor recombining them is where per-stated-fact splitting has to pay for itself at a
   read rather than at a write; if the recombination is awkward that is evidence about the split.
3. **`node` and `node_keycolumn`**, read by both the editor and the macro consumer, and burn down
   work before they are migration work.
4. **The editor's six remaining reads**, each needing its decode captured first.
5. **The generator's two**, `graphitron_argmapping_entry` and `graphitron_field_reference_step_entry`,
   whose conversion leaves `GraphitronSchema` as its single remaining incumbent input.

## The entry stratum costs more to write than everything else the store holds (2026-09-12)

The read side has been counted twice now, the write side never. Capture's largest single cost is
writing the entry stratum, and it is the mechanism rather than the tuning: 7194 entry rows cost more
wall clock than the 86139 rows of every other family put together. The stratum this item is growing
is the expensive one, so the number gets worse as the migration proceeds rather than better.

**How it was measured.** JFR at `settings=profile` over `mvn graphitron:capture` on
`graphitron-sakila-example`, plus a temporary probe timing each write mechanism and counting its
rows. Neither instrument was kept; the probe is reproduced in the numbers below and nothing in the
tree depends on it.

| mechanism | rows | time | per row |
|---|---|---|---|
| `FactSink` generic arm, a bind batch | 64589 | 1.6 s | 25 us |
| `FactWrites`, a written bind batch | 21550 | 0.8 s | 35 us |
| `RowChunks`, a multi-row `VALUES` upsert | 7194 | 8.7 s | 1200 us |

Forty times the per-row cost, and the hot callers are not incidental. Ranked, they are
`SdlEntries.valuesOfDepth`, `fieldDefinitions`, `appliedArguments`, `fieldDirectives`,
`fieldArguments` and `typeDeclarations`, with `GraphitronFieldEntries.bindings` and
`JooqFactCapture.columns` behind them. That is the entry stratum and the families feeding it.

**The cost is H2 parsing, not inserting.** The hottest leaf frame in the whole process is
`org.h2.command.Parser.setSQL`, 334 of 2788 samples. Of those, 253 carry no graphitron frame at all,
because the stack is truncated at 200 frames by `parseQueryExpressionBody` recursing into itself: a
multi-row upsert renders as a MERGE over a chain of unioned SELECTs and H2 recurses once per row
group. `RowChunks` predicts exactly this and bounds it; what its bound got wrong is the number. One
500-row chunk of `fieldDefinitions` renders to 172740 characters of SQL and takes 844 ms to parse.

**Total time is linear in the bound**, which is what per-statement cost quadratic in rows predicts.
Same 7194 rows every run:

| chunk | 500 | 200 | 100 | 50 | 25 | 10 |
|---|---|---|---|---|---|---|
| ms | 7829 | 3607 | 1839 | 1595 | 1694 | 1488 |

It flattens around 50, where fixed per-statement overhead takes over. Three interleaved repeats of
the endpoints under a loaded machine, carrying the untouched `FactSink` figure as a control: 7592 /
8689 / 9973 against 1799 / 1635 / 2053, with the control flat at 1549 to 1686 throughout. So the
constant alone is worth about 4.8x. It is not the fix, because even at its optimum the mechanism is
still nine times the bind batch's per-row cost.

### What jOOQ offers, and the one that looks right and is not

Three candidates, measured against the real relation, seventeen columns and 800 rows, interleaved in
one JVM so machine load falls on all of them equally. All five wrote the same 800 rows, asserted
rather than assumed.

| shape | steady state |
|---|---|
| `valuesOfRows(chunk)`, chunk 500, today | 99 ms |
| `valuesOfRows(chunk)`, chunk 50 | 38 ms |
| `dsl.batched(cfg -> ...)` | 114 ms |
| `dsl.batch(query).bind(...)` | 36 ms |
| `dsl.batch(query, Object[][])` | 27 ms |

`dsl.batched` is the trap. It is jOOQ's transparent batching: wrap a block, keep writing ordinary
single-row statements, and `BatchedConnection` turns consecutive identical SQL into `addBatch()`. It
names MERGE among the statements it buffers, and it is the option that preserves a declarative call
site most obviously. It is also slower than what we do today, because it batches only the JDBC side:
jOOQ still builds and renders a Query per row, and rendering that 2581-character MERGE 800 times
costs more than the parse it saves.

`dsl.batch(Query, Object[][])` is what jOOQ documents for this, and its javadoc states the logic it
runs: prepare once from `query.getSQL(false)`, bind per value, `addBatch()` per row, one
`executeBatch()`. One render and one parse for a whole relation. It is also what `FactWrites` already
does, so it makes capture's two write paths agree rather than diverge. One precondition, checked: the
store uses default `Settings`, so `StatementType` is `PREPARED_STATEMENT`. Under `STATIC_STATEMENT`
jOOQ inlines the bind values and the whole benefit is gone.

### The declarative half does not move

This is the fact that was not available when the bound was chosen. The bind batch was considered then
and set aside partly on the expectation that it would cost the typed `Rows.toRowList` spelling at
every site. It does not. The collector already produces the values; only the statement changes.

```diff
-        RowChunks.execute(rows, chunk ->
+        BindBatch.execute(dsl, rows, markers ->
             dsl.insertInto(t, t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN,
                     t.SOURCE_REF, t.TOUCHED_AT, t.KIND, t.IS_EXTENSION, t.NAME, t.DESCRIPTION)
-                .valuesOfRows(chunk)
+                .values(markers)
                 .onDuplicateKeyUpdate()
```

Three lines per site, and the extractor list above it, the column list and the conflict clause are
all untouched. `BindBatch` is about 25 lines: take the `List<RowN>` the collector built, unwrap each
`Param` to its value, render the statement once against a row of null markers, hand jOOQ the value
table. Column types still come off `insertInto(t, cols)`, which is where they came from before;
`val(x, t.COL)` never gave compile-time checking either, its value parameter being `Object`.

**Measured on the whole capture**, converting all 81 sites across the eight capture classes. Machine
load was 27 to 32 during the run so wall clock was unusable, and the figures below are JFR sample
proportions, which are load independent, with untouched code as the control.

| | before | after |
|---|---|---|
| entry-write mechanism | 15.85% | 2.43% |
| of which deep parser recursion | 9.29% | 0% |
| any H2 `Parser` frame anywhere | 15.39% | 2.78% |
| `FactSink.flush`, control | 6.99% | 7.75% |
| `Materializations.refreshPartition`, control | 14.78% | 19.05% |

Against the `FactSink.flush` control the write path falls from 2.27 times its cost to 0.31, a 7.3x
shift. The controls rising in share is the expected shape when the measured thing shrinks and the run
is otherwise unchanged.

**Behaviour is unchanged.** The one failure the conversion produces is
`MultiRowWritesAreChunkedTest`, which asserts that at least sixty multi-row writes exist and that
each sits inside the bound; after the conversion there are none, so its floor fails. That guard
polices the mechanism being removed rather than any behaviour, and it is replaced below. Landed on
this branch with the replacement in place, `graphitron-model` runs 1191 tests green and the reactor
7657 with no failures, the sakila example's execution tier among them.

The site count differs between the measurement and the landing, 81 against 89, and the eight are
writers this branch carries that the measured tree did not: three catalog writers added when the
catalog family was reduced to one producer, and five in `CodeCapture`. The measurement is left at
the number it was taken over rather than restated, since nothing about the proportions depends on
the eight. What is worth recording is that the gate found them: the conversion arrived as a patch
against the smaller tree, applied cleanly, and left all eight writing rows into the statement, and
`WritesBindPerRowTest` named each by file and line rather than letting a green build claim the
module had been converted.

There is one semantic difference and it runs in the safe direction. Two rows sharing a key inside one
multi-row MERGE can collide, which is why the bound has to argue that no caller has an intra-call key
collision. One statement per row makes the second an update instead, so the change can remove a
failure mode and cannot add one.

### What it retires, and what replaces the guard

`RowChunks`, `ROWS_PER_STATEMENT` and the chunk vocabulary in the javadoc around them lost their
callers and are gone. `MultiRowWritesAreChunkedTest` is inverted rather than deleted, as
`WritesBindPerRowTest`: the module has no multi-row write at all, instead of having bounded ones.
That is the stronger claim, there is no longer a bound to pick or a constant to justify, and the
javadoc explaining why the quadratic matters carries over unchanged. The floor moves with it, held
over the `BindBatch.execute` sites, because what must not quietly disappear is the population of
relations written through the bind batch.

The bug report filed against the original heap exhaustion is a report rather than a solution, and
this dissolves it: the bound it delivered was the right diagnosis reaching for the wrong lever, and
there is nothing left for it to hold once the statement no longer carries its rows.

Left out deliberately. The extractor list and the column list are two parallel sequences that have to
stay in the same order, and nothing checks that. A shape pairing each column with its extractor would
derive both from one declaration and make them impossible to desynchronise. That is a real
improvement and it rewrites all 89 call sites rather than substituting one line in each, so it is a
separate piece of work, not a rider on this one.

## The first three anchors off the walk, and what moving one costs (2026-09-12)

The walk's `graphql_` half is measured redundant earlier in this item. This one moves the
first of the `graphitron_` half and reports what the move turned out to cost, because the cost is
not where the plan had it. Three relations, `graphitron_table_entry`,
`graphitron_scalar_type_entry` and `graphitron_record_entry`, are derived by `GraphitronAnchor` out
of the entry stratum and the walk's arms that wrote them are gone. The derivation is fifty lines and
was the cheap part. Everything else here is a prerequisite that refused, which is this arc's usual
shape, and four of them are worth recording because they bind every later relation too.

**Moving a relation means declaring it, and the declaration pass is not optional after all.** It is
item 7 of the burn down and it is ordered late, on the reasoning that declaring relations about to
dissolve is writing rationales for the doomed. That reasoning holds for the `intent_` views and not
for these: `StoreRefresh` decides what a warm pass empties by reading `meta_relation`, so a relation
whose owner is not declared `document` is cleared by the walk's pass and, once the walk no longer
writes it, is not written back. The declaration is the mechanism, not the documentation. So each of
the three arrived with a `meta_relation` row, a grain, and its `COMMENT ON TABLE` restated to echo
them, and the undeclared roster is down to 231. A new grain, `graph-type`, is the key these relations
share and nothing else had named.

**The derivation's own content is a rank and two joins, and the rank is the fact the walk was
hiding.** A type-keyed relation admits one row and a type may carry the directive on its base
declaration and on any extension, so the walk chose by arriving first. Stated in SQL that is
`row_number` over the corpus merge order, which is the same choice said out loud. The two joins are
the polarity question: `@table` joins the decode outwards, a bare application being a binding whose
name column is empty rather than an absent row, and `@scalarType` joins it inwards, its reference
being NOT NULL and an application naming nothing having no fact to state. `GraphitronTypeAnchorsTest`
is those three properties and nothing about spelling.

**One relation cannot move alone if a second one is fed from its site.** The `@table` arm is not
gone entirely: it still writes `graphitron_spelled_reference_entry`, which is keyed by the spelling,
fed from seven sites, and therefore moves when the last of them does rather than when the first does.
`@enum` is out of this batch for the same reason and a second one: it feeds the `ENUM` arm of
`graphitron_method_reference_entry`, and its entry relation makes `class_name` NOT NULL where the
incumbent keeps a row with the method and the argMapping and no class. That second one is a real
narrowing rather than a coupling, so it wants a decision rather than an ordering.

**The pass that reads a merged registry cannot write relations keyed by a position in one file, and
that is what actually gates this work.** The document gatherer runs in `ModelCapture`; the walk runs
in `FactCapture`; a mojo build runs both and everything else in the tree runs one. So the first
relation to move off the walk was, in every store captured through the walk alone, simply empty,
and 112 cases said so. `FactCapture` now runs the entry writers before its walk and graphitron's
anchors between the walk and the stages, which is the only place the anchors fit: they key into the
`graphql_` anchors, which are still the walk's, and the stages below read what they derive. The
face `SdlCapture` offers for that is deliberately not the whole of itself, because running the whole
face there would raise one assembly's problems twice and meet the walk's own inserts on the
`graphql_` keys.

That change has a consequence a caller can trip over: a pass handed a registry with
`SubjectConfig.none()` beside it now clears the entry stratum and has no corpus to write back. Every
production caller has the configuration, and the harnesses that did not now state the corpus they
captured. It is still a shape that can be got wrong silently, and the honest fix is upstream of this
item: a pass should be given the documents rather than the merge.

**An exemption that was protecting rows was breaking them, and the demotion hid it.** `StoreRefresh`
exempted every `document`-owned relation from the clear, so that what the model capture wrote before
the pass survived it. But the clear still emptied the `graphql_` anchors those rows key into, so on
every warm pass the delete met a foreign key and threw, and `RunStore` answers a capture failure by
demoting the run to a private store rather than failing it. Two exempt relations reach it on the
vocabulary graphitron itself ships, so this was the ordinary path and not a corner. With the document
gatherer running inside the pass the exemption is unnecessary as well as wrong, and it is gone.
Worth stating on its own: the mechanism that made a deterministic capture bug survivable is also the
mechanism that kept this one invisible, and nothing in the tree was in a position to notice.

### The field site, four directives, and the number the two strata disagree about

`@asConnection`, `@pivot`, `@mutation` and `@defaultOrder` follow the type site off the walk, which
is five relations counting the ordered child. They were chosen by a property rather than by size:
each is declared at `FIELD_DEFINITION` and nowhere else, so each anchor derives from one entry
relation. A directive also legal on an input object's field has its applications split across two,
an input object's field being an input value in the entry stratum rather than a field, and its
anchor is then a union with a parent-kind test. `@field` and `@nodeId` are the two that need that
shape and they are deliberately not here; getting the test wrong would admit a field argument's row
as if it were an input field's, silently.

**That criterion is about which relation an anchor reads, and it is not the hard part.** One entry
relation is still a bag. An author who copies a schema file gets two declarations of every type in
it, so `graphitron_ast_table_entry` holds `film` from one copy and, once the copy is edited,
`film_copy` from the other, while the anchor holds one row. Measured: the anchor keeps the older
declaration's, the other claim is not in it, and `graphql_duplicate_declaration` is empty. Assembly
does report a redefined *type*, so a build is not silent there, but an element-level duplicate is
retained by the registry with no error at all, which is the case the field-site rank case exercises.
The multiplicity is the same at every site and was already true of all eight relations here.

**And keeping the first is the right answer, which is the overflow argument one stratum up.** An
anchor is a designed resolution: its grain admits one row per coordinate and deciding which is what
it is for. Asking whether the decision was contested is a query across the two strata, not a third
relation, for the same reason the transcription needs no overflow: the bag holds every reading at
the position it was written, so nothing is lost by resolving above it. What this does oblige is that
an anchor state its rule rather than merely apply it, since a query over the bag has to know what
was resolved and how. Every derivation here says so in as many words, oldest declaration first by
corpus merge order, and that sentence is load-bearing rather than descriptive.

What has no home yet is the query. A reader wanting to know that a coordinate was contested can
write it, and none does, so a diverged copy is discoverable and undiscovered. That is a detection
over the entry-to-anchor anti-join, which is where the entry and anchor pattern has always said a
diagnostic about author error belongs, and it is worth an item rather than a relation.

**The rank reaches one hop further.** A field's coordinate is its type and its name, so the merge
order that decides between two applications is the order of the declarations their fields were
written in: application to field definition to type declaration. The choice is also reachable here
in a way it is not at the type site, where a repeated non-repeatable application fails assembly. A
field declared twice is retained by the registry rather than refused, so one coordinate genuinely
carries two applications and the anchor genuinely picks, which the case pins by first asserting both
are entries.

**One place the two strata disagree about a value rather than a row, and it is worth stating.**
`@defaultOrder(fields:)` numbers its elements by where the author wrote them in the entry, so an
element the decode refuses keeps its index and contributes no row. The anchor numbers the rows it
holds, densely from zero, because that is what the walk did by counting as it wrote. So the position
is ranked again rather than copied. Dense numbering loses where the element sat and nothing reads
that gap today; the anchor keeps what its readers have and the entry keeps what the author wrote,
which is the division of labour the two strata exist for.

**And the clear became one statement instead of eight.** Each derivation emptied the relation it
fills, which works until a relation has a child: the ordered fields reference their ordering, so the
parent's delete met the child's key. One clear over the writer's whole population, children before
parents, in the order the roster already states.

### The wholesale clear, retired, and what that says about the register's cousin

`StoreRefresh` is the mechanism that lets a walk delete. A writer upserting rows keyed by a
coordinate cannot notice a coordinate the author removed, so something empties the partition ahead
of it, and that is the whole of why the class exists. It is the same shape as `meta_materialize` one
layer down, and it dissolves the same way: not by being argued down, but by every relation acquiring
an owner that marks and sweeps its own rows, after which the mechanism has nothing left to do.

Measured, it is three arms of very different sizes. The graph-scoped arm covers 205 relations, every
graph-keyed base table bar the graph's anchor row, and it shrinks one relation at a time as
ownership spreads. The classpath arm serves the `jvm_` census and goes with it. The wholesale arm
turned out to cover **five** relations, and it is retired here.

**Retired because it was redundant where it was right and destructive where it was not.** All five
are `sql_`, all five are keyed on `source_name`, and `CatalogFactCapture.clearSchemaSources` already
deletes all five per owned source, in dependency order, before rewriting them. The wholesale arm
deleted them for *every* source, because the list it consulted to tell a source-partitioned relation
from a rebuild-me-wholesale one was hand-written and the schema had grown five relations past it. On
a store shared by two modules that leaves a partition kept by halves: the sibling's `sql_table`
survives, its `sql_node_metadata` and `sql_routine` children do not, and no reader can tell that
from a schema declaring neither. The class's own comment forbids exactly this.

Uncaught because the case for it was stated one family too narrow.
`WarmStartRefreshTest.aSiblingGraphsPartitionSurvivesARefresh` asserts survival over `graphql_type`,
which is graph-keyed and therefore scoped by a column; the source-partitioned families are scoped by
the list, and nothing asserted over them. The new case seeds a well-formed foreign source rather
than producing one from a second jOOQ catalog, deliberately: the claim is about a source this run
never reads, and a fixture that had to generate one would be asserting something narrower. Verified
failing before the fix, on the routine row alone with the schema row already passing, which is the
half-deleted partition stated as an assertion.

**And the list was the defect rather than its contents.** Extending it would have restored the
behaviour and kept the mechanism that fails silently the next time the schema grows. What the arm
was really carrying is a polarity worth keeping, that a relation nobody thought about is rebuilt
rather than silently retained, and that polarity does not need it: `MetaDeclarationGateTest` fails
the build on an observed relation with no declared owner and no line on the frozen roster. That is
stricter, it fires at build time rather than at a consumer's next read, and it asks for an owner,
which is the thing actually wanted.

The class now says all of this in its own comment, marked as dissolving, with what is left of it
named as a measure of what has not moved yet.

### The two passes do not compose, which is why the pass runs the document gatherer

The arrangement above, where `FactCapture` runs the entry writers before its walk and graphitron's
anchors between the walk and the stages, was first justified from the test harnesses: a relation off
the walk is empty in a store captured through the walk alone. That justification is backwards, and
the experiment that replaced it with a harness fix is recorded here because it failed for a better
reason than the one it was testing.

Nothing in production runs `FactCapture` without the document gatherer having run. Both mojos call
`captureModel` first; every caller that does not is a test. So the harness was the thing producing a
store no run produces, and `CapturedStore` says in its own comment that it cannot do that.

But a harness cannot replicate the run's sequence either, because the sequence does not compose.
`ModelCapture` and `FactCapture` both write `store_graph_schema_input`, one by mark and sweep and one
by plain insert, and both write the `graphql_` families. Run them in order on a cold store and the
second collides with the first. Production survives it only because the pass is warm and the clear
sits between them. Make the harness warm to match, and the clear deletes the `graphql_` anchors out
from under the document-owned rows that key into them, which is the failure the exemption causes and
which a two-pass probe reproduces directly.

So while the pass clears, anything written before the clear is either destroyed or blocks it, and
the pass running the document gatherer itself is the only arrangement with neither fault. In
production that costs one extra parse of the corpus per build; it looked expensive only because the
test suite performs thousands of captures. What removes it is not a better justification but
finishing this migration: when the decode is empty the walk drives nothing, when the walk goes the
clear has nothing that cannot delete itself, and when the clear goes the pass has nothing left to
compensate for.

**Underneath all of it is that no single thing owns "capture a graph".** Two mojos write the sequence
down; 77 test files reach it through `CapturedStore`, twelve drive `FactCapture` directly, 21 go
through the generator's own port, and one has a harness of its own. That is why half a capture was
reachable without anything noticing, and it is the same absence the collision above is a symptom of.

**One more thing refused, and it is not this batch's to fix.** `SdlCapture.capture` assembles in
order to raise the problem rows, and `SchemaAssembly.of` throws on a schema that applies a
non-repeatable directive twice. So the face this item has been building cannot capture the very case
its own rank exists for, which is a schema an author can write and which the walk captures happily.
The facts and the problems are two public entry points now and the rank's case uses the first, but
capture must not throw on author input is one of the three findings this item already carries, and
this is a live instance of it.

## The entry stratum holds what the directive definition admits (2026-09-12)

The rule this item works to from here: an entry is written for a directive application its own
directive definition admits, and for no other. Input that does not match the definition is not
transcribed at all. An entry is still a bag rather than a set, and what it is a bag of is legal
input.

The question became live during the `@defaultOrder` migration. The walk's arm quarantined a `fields`
element it could not decode, writing the raw text to `graphitron_undecoded_argument_entry`; the
derivation that replaced the arm does not. That looked like a regression worth repairing until the
reachability was measured, and the measurement says the opposite.

**The measurement.** Five corpora, each captured through the full `SdlCapture.capture` face, which
parses, builds the registry, transcribes, derives, and then assembles to raise the problem rows.
`FieldSort` declares `name: String!`, so the four malformed corpora are all SDL-invalid.

- `fields: [{name: "title"}]` assembles. One entry row, one anchor row.
- `fields: ["title"]` refused at ASSEMBLY, `DirectiveIllegalArgumentTypeError` at 1:14, "Argument
  value is of type 'StringValue', expected an Object value." No entry row, no anchor row.
- `fields: [{collate: "xdanish_ai"}]` refused at ASSEMBLY, same class, "Missing required field
  'name'." One entry row with a null `name_ref`, no anchor row.
- `@defaultOrder(bogus: 1)` refused at ASSEMBLY, `DirectiveUnknownArgumentError`. No rows.
- `@defaultOrder` on an OBJECT refused at ASSEMBLY, `DirectiveIllegalLocationError` at 2:1. No rows.

Two results there contradict reasonable assumptions and both matter. The registry admits every
invalid form: `TypeDefinitionRegistry` type-checks no directive argument, so nothing is refused at
PARSE or at REGISTRY. And every refusal is caught at ASSEMBLY, classified and positioned, inside the
same capture pass that writes the entries.

**Why the library cannot do better here, and why we can.** A `TypeDefinitionRegistry` is a partial
view by construction: it holds what has parsed so far, and the definition of a directive an
application names may sit in a document that has not arrived yet, or in one merged after it.
Checking an application against a definition the registry might not hold would refuse legal input
for an ordering reason, so graphql-java does not check, and assembly, where the corpus is whole, is
the first place it can. We are not in that position. Every directive this stratum transcribes is
graphitron's own and ships with it, so the definitions are in hand before the first user document is
read and the check is available at a point the library has no way to reach. Knowing more, we can do
better, and should.

**So the quarantine was never buying anything.** An invalid application is already reported by a row
naming the error class, the position, and what was expected, which is more than raw quarantined text
says. No generator run completes on a corpus carrying one, because assembly refuses it first. For
this family the population `graphitron_undecoded_argument_entry` exists to hold is one the store
already describes better elsewhere, and it has no production reader today.

**Three things follow, and they are why the rule is worth adopting rather than merely defensible.**

A payload column takes its nullability from the directive definition. `FieldSort.name` is `String!`,
so `graphitron_ast_default_order_field_entry.name_ref` should be NOT NULL, and the same reasoning
runs across the stratum wherever a directive declares a non-null argument. Today it is nullable,
which asserts that a nameless element is a thing capture holds. Under this rule it is not.

An anchor stops filtering. `GraphitronAnchor.defaultOrderFields` carries a `name_ref IS NOT NULL`
predicate and a dense renumbering to close the gap a refused element leaves. Both are unreachable in
any corpus that assembles, and under this rule unreachable in any corpus that is transcribed, so the
derivation becomes a straight projection.

The entry-to-anchor anti-join gains a single meaning. Today a coordinate present as an entry and
absent as an anchor means either that the input was malformed or that the claim was lost to an
earlier declaration, and a conflict query cannot tell those apart. Under this rule only the second
can occur, which is what makes the query readable.

**Where the check comes from.** Ours, written out per application against the definition.
graphql-java's `SchemaTypeDirectivesChecker` is package-private, and the public
`SchemaTypeChecker.checkTypeRegistry` wants an `ImmutableTypeDefinitionRegistry` and throws
`SchemaProblem`, which collides with the standing finding that capture must not throw on author
input. The predicate is: the location is legal, no argument is unknown, and every value conforms to
its declared input type through non-null, list, input object, scalar and enum.

The definitions are already in reach. `SchemaLoader.parsePerSource` offers the bundled
`directives.graphqls` first and builds a merged registry beside the per-source ones, so a
per-document transcription can look up any directive's definition without assembling, and without a
corpus-wide dependency the reading does not already have.

**The safeguard, which is not optional.** The predicate has to agree with assembly in both
directions, falsified by a test that captures a corpus and asserts the applications transcribed are
exactly the applications assembly did not complain about. Looser than assembly and an invalid
application becomes an entry carrying garbage. Stricter and entries vanish for valid schemas, which
is the dangerous direction: `BindingUsages.boundTypeSites` reaches `graphitron_ast_table_entry` by
inner join from `intent_bound_table`, so a missing entry row for a binding that did resolve deletes
a find-references result and reports nothing anywhere.

That same join is the evidence that the rule costs the editor nothing. It is the only entry read in
either `graphitron-lsp` or `graphitron-mcp`, it reads position columns only, and it starts from a
resolution view that invalid input never reaches.

**What does not conform today.** `GraphitronEntries.elementsOf` and its sibling `writtenIn` drop an
element that is not the expected literal shape, which lands near the rule for the wrong reason: they
filter on the shape a decode wants rather than on what the definition admits, inside the layer whose
job is transcription. `graphitron_ast_default_order_field_entry.name_ref` is nullable where the
definition says non-null. And the anchor carries the filter and the renumbering that the rule makes
dead. Bringing the existing entries to the rule is the next step of this arc.

## The rule is every site, and the collector is what stood in the way (2026-09-13)

The rule landed at two of the five sites. The type and field sites judge; the input-value,
enum-value and schema sites transcribe whatever parses. A rule that holds at some sites is not the
rule, it is a filter with a coverage table, and the gap is already doing damage: a differential
written against the walk had to put its subject at an unjudged site to keep agreeing, which is the
target design being shaped by the part that is dissolving rather than the other way round.

**Why three sites were left.** Two of them for no reason at all. `GraphitronEnumValueEntries` and
`GraphitronSchemaEntries` arrived from a session that did not have the rule, and their locations are
constants: an enum value is `ENUM_VALUE` and a schema block is `SCHEMA`, with nothing to derive. The
third had a reason, and the reason is a legacy shape rather than a difficulty.
`SdlEntries.inputValues` assembles three lists that each know their enclosure, `argumentsOfFields`,
`fieldsOfInputObjects` and `argumentsOfDirectiveDefinitions`, and then concatenates them into one
list that does not. `directivesOnInputValues` reads that list and sets each application's parent to
the input value itself, so by the time a judge could ask, two SDL locations have been merged into
one and the enclosure that told them apart is three lines upstream.

Nothing about that flattening is load-bearing. It is what a walk that never asked the question left
behind, and the fix is to stop discarding what the collector already computed rather than to route
around it. A field's argument and a directive definition's argument are both
`ARGUMENT_DEFINITION`; an input object's field is `INPUT_FIELD_DEFINITION`. Three lists, two
locations, no new traversal.

**What that unblocks, and what it then costs.** With every site judging, an application carrying an
element the definition does not admit is refused wherever it was written, so the one gap the
ranking was built for is gone: `GraphitronEntries.elementsOf` increments past an element it does not
transcribe, and no admitted application now contains one. A recombination can read the position
instead of ranking it, which retires the second of the two clauses this arc set out to retire.

It does not make the element set recoverable, and the first draft of this section said otherwise.
An element can be entirely legal and still write no decode row, because a decode relation has its
own NOT NULL columns and the definition need not require any of them. `ReferenceElement` declares
every field optional, so `@reference(path: [{table: "a"}, {}, {table: "b"}])` assembles, and the
bare element names no table, no key and no condition. Measured: the walk writes rows at 0, 1 and 2
while the three step relations hold 0 and 2 between them. What is missing there is a row and not a
number, so no numbering over those relations reaches it, a rank turning it into a renumbering and a
copy into a hole. That is the case for keying a decode by the element's own coordinate rather than
by an ordinal it recomputes, the element already being a row of `graphql_ast_value_entry` in
authored order and unfiltered, and it is a separate change from the rule.

The cost lands on the differential. `graphitron_field_reference_step_entry` is the walk's, the walk
has not adopted the rule and will not, being the thing this replaces, so on a corpus carrying a
refused application the two strata disagree by construction: the walk writes rows and the entry
stratum writes none. A differential that keeps agreeing with the walk there is pinning the walk's
behaviour on input no assembled schema can carry, which is the shape to refuse. The comparison is
scoped to corpora that assemble, and says so, which is the same scope the rule itself is argued
from.

**Ownership, stated because it is the thing that keeps going wrong.** Both strata are ours. When the
dissolving one disagrees with the target, the question is which is right and not how to keep them
agreeing, and the cheapest-looking answer has twice now been to bend the new design to the old one.
Rewriting the collector is a smaller change than the workaround it removes, and it is the second
time on this arc that has been true.


## The capture layer dissolves into one linear read (2026-09-13)

The dissolution work has been stated in fragments across this item, one aside per chapter, and never
as a target with an order and a gate. This is that plan. It covers the capture layer and stops there.

### The target

```java
// Every goal but dev. Which store this is gets decided when it opens, so the capture is a
// call and not a callback handed to the thing that chooses.
try (var store = GraphitronStore.forRun(ctx.storeDirectory(), graph)) {
    var captured = ModelCapture.capture(store.dsl(), ctx, jooq, census);
    call.invoke(new GraphQLRewriteGenerator(ctx, captured));
}

// Dev. The session store is opened at startup and closed at shutdown, which it already is;
// each round captures into it, and the language server and MCP reader are on the same handle.
```

Two acceptance criteria, both countable, because "trivial to follow" otherwise means nothing.

**Every call in `ModelCapture.capture` takes the run's own inputs and nothing another layer derived.**
Four of its five lines qualify today. At the end of phase 1 there is one violation, the walk's
registry argument, named in the method. At the end of phase 2 there are none. A person checks this by
reading one method, which is the property being bought.

**`CaptureMojo`'s body is opening a store, capturing, and closing it.** No generator, no pipeline, no
projection constant. It is the goal whose whole job is capture, so it is the one that cannot be
half-converted without showing it.

### Scope

The store ships 381 relations, 255 tables and 126 views. This plan is one of four pieces of the
architecture and the smallest by line count. Stated here so it is judged for what it claims:

1. **The capture layer.** Two entry points become one linear read. Below.
2. **The read side**, which is this item's own subject and is larger. `intent_` is 142 relations, 27
   tables and 115 views; the consumers reach 89 of 210 roots; the register is 21 registrations
   refreshing in 4.7 s of a 28.2 s `graphitron:capture` on the sakila example. None of it moves
   because the decode moved. It moves when ownership becomes total, which is item 8. What this plan
   buys it is a precondition: a rule cannot be given to the gatherer whose corpus it reads while two
   gatherers write that corpus.
3. **The generator's second derivation of the model**, which no move here reaches. Six of
   `EmitPlan`'s seven producers take `GraphitronSchema` rather than the store, and
   `GraphitronSchemaBuilder` is 1430 lines inside the 65 files and 38163 lines directly under
   `rewrite/`. `ConnectionPromoter` at 732 lines against `MacroCapture`'s 403 is one measured
   instance and is burn down item 3.
4. **The synthesis that belongs to neither side.** `KeyNodeSynthesiser` and `FederationLinkApplier`
   inject declarations no document contains, which is what the walk's `SYNTHESISED_SOURCE_NAME`
   carries. Phase 2 answers what reads those rows before it deletes the writer.

### Why the schema load moves before anything else

`GraphQLRewriteGenerator.assembleAndCaptureVerdicts` is forty lines with three arms, and all three
capture before deciding anything. A stage refused the document: capture, then throw. Graphitron's own
rewrite broke a document the author wrote correctly: capture, then fail. Nothing refused: capture, and
go on. Its own comment states the rule as "capture first, fail second".

That is the target shape, written inside the task because `loadAttributedRegistry` is in the same
class. Nothing in either method belongs to a generator: they parse per source, apply four
configuration-driven rewrites, assemble, and record verdicts. A validator wants that. A language
server wants it. `CaptureMojo` runs an entire generator to get it.

So the schema load is upstream of both capture and the task, and is currently filed under the task.
Move it and the seam falls out. Leave it and no amount of relation migration reaches the target,
because the fiftieth relation moving still leaves a mojo constructing a generator in order to
capture.

Migrating relations first also costs more than doing it after. Three things that look like projects
today are not, once there is one entry point:

- The configuration pair becomes two adjacent lines writing the same seven relations, one of which
  writes two more. A deletion, not a migration.
- The walk's `graphql_` half stops being an ordering property between two passes nobody controls
  together and becomes two adjacent lines under one instant and one transaction.
- The order flip the redundancy chapter owed goes with the second pass it was an ordering between.

### Phase 1: one read, one capture, one store

`loadAttributedRegistry`, `assembleAndCaptureVerdicts`, the jOOQ catalog load and the classpath census
read move out of `GraphQLRewriteGenerator` into the capture layer, and `ModelCapture.capture` returns
what it read: the store handle, the detections, the attributed registry with both its registries, the
verdicts and the assembly. The mojo captures and hands that to the task. No relation moves and no
gatherer changes what it writes.

**Retires** `CapturePort`, 236 lines and two arms differing only in how long a store is held, with two
callers who each know their own answer; `CaptureRequest`, which exists to carry a capture between a
caller and a port; `FactCapture`'s entry points, whose body becomes `ModelCapture`'s; and
`RunStore.forRun`'s `CaptureBody` argument, along with `recapture` and the `Borrowed` arm that serve
the held port. The demotion is already gone, below.

**Fixes, without migrating anything.** The corpus is parsed once instead of three times. Measured over
`mvn graphitron:capture` on the sakila example, a 28.2 s goal, with a counter on
`SchemaLoader.parsePerSource` printing its caller:

1. `RunStore.captureWithRetry` to `ModelCapture.capture` to `SdlCapture.capture` to `captureFacts`
   to `captureEntries`
2. `AbstractRewriteMojo.runGenerator` to `GraphQLRewriteGenerator.runPipeline` to
   `loadAttributedRegistry`
3. `FactCapture.capture` to `SdlCapture.captureEntries`

Parses 1 and 3 each call `SdlEntries.write` and `GraphitronEntries.write` per document, so the entry
stratum is written twice per goal and one of the two writes is discarded by the other's sweep. Not a
cost argument: `BindBatch` made that stratum cheap to write. It is an argument about what a reader can
know, two writers filling one relation with nothing stating that they agree and call order deciding
which rows survive. That was the catalog pair's situation exactly, where the symptom was forty four
classes reading an empty view while three diagnoses blamed something else.

**Predicted refusals**, since on this arc a prerequisite is found by something refusing.
`store_graph.build_file_path` and `build_file_stamp` are written only by `FactCapture.writeGraph`, so
a store captured by `ModelCapture` alone has never held them: either it takes them over or nothing
reads them, and the second is the cheaper question. `ClassificationDomainCapture.derive` takes the
assembled schema, the one non-store input in the pass; this phase carries it rather than hitting it,
and it decides how much of the pass's non-gathering content phase 2 can move. And the anchor row's short
budget is taken during the write today, so with the store's identity decided at the open a contended
anchor row is a failure raised mid-capture rather than a store chosen differently. That is the one
thing below that has not been checked against the code.

**`RunStore` dissolves, and a refused store fails the build. Landed 2026-09-14.** The policy is that a run which cannot
have the store it asked for says so and stops, with a message naming the cause and the fix, and the
person runs it again. Not a silent second-best. That decision removes the class rather than trimming
it, because everything in it is machinery for continuing.

`forRun` takes the capture as a `CaptureBody` for one reason, stated in its own class comment:
whether the shared store can be had is only knowable once a write has been attempted. That is true
of one arm, `CaptureFailedTwice`, and under this policy that arm is a build failure. The rest go with
it: `attemptShared`, `captureWithRetry`, `inMemory`, `captureCold`, `recapture`, the `Demoted` and
`Borrowed` arms and the four `Demotion` records. What survives is the ownership read, as a check that
throws, and the store is then `GraphitronModelStore.openAt(dir)` or `open()` for a caller with no
home. 534 lines to a function and an exception.

The four arms, and what each becomes:

- `NoHomeGiven` is not a failure and never was. The caller named no directory, so an in-memory store
  is what it asked for. It is an arm of which store to open, filed under why the run was demoted, and
  it is already logged at debug with a comment saying it is not a warning.
- `GraphOwnedElsewhere` fails. It is a configuration error, its own message already prints the fix,
  and it is a read of `store_graph` before the capture. A build that finds two modules claiming one
  graph name and quietly captures to a throwaway will keep finding it forever.
- `CaptureFailedTwice` fails, and it is the arm with a scalp already. The `StoreRefresh` exemption was
  deleting a parent out from under an exempt child, the referential failure fired on every warm pass
  on the vocabulary graphitron itself ships, and the chapter above records that the run was demoted
  rather than failed. It went unseen, and systematically: the fallback capture is cold and the defect
  was warm-only, so the arm succeeds precisely when the shared store's failure is real.
- `Unavailable` fails too, and it is close to unreachable on the path that carries it. Its message
  names a dev session in the same checkout as the common cause, and the two stores cannot collide:
  `AbstractRewriteMojo.resolveStoreDirectory` is `<module>/target/graphitron-model` unconditionally,
  `DevMojo` overrides it to the user cache root under a per-workspace segment, and that override's
  own javadoc says a one-shot goal needs neither the survival across `mvn clean` nor the reach into
  another module. So a one-shot store is single-writer by construction, its only contender being a
  second build of the same module into the same target at the same time.

**The sharing model the capture layer documents is the dev store's, and the one-shot path does not
have it.** `RunStore` opens with "the persisted store is shared by every module of a workspace, so a
warm open reconciles only what this run owns", and `FactCapture` repeats it. For the goals that run
in a build it is not so: each module writes its own file under its own target and it holds the one
graph that module captures. `GraphOwnedElsewhere` cannot fire there at all, the base class honouring
no `<storeDirectory>` override, so no two modules can be pointed at one file. `StoreRefresh`'s
graph-scoped clear is likewise scoping a partition that has no siblings. Both are written for the dev
store, which is the one that really is per workspace and really does hold several graphs, and both
are documented as if every store were that one. Worth knowing before phase 2 leans on either.

**Two things the fallback was right about, recorded so the case for removing it is not overstated.**
Its content claim holds: `WarmStartRefreshTest` pins that a warm run ends with the rows a cold run
would have produced, and an in-memory store is a cold store, so "the generated output is identical"
was true. And it is not slow. Measured on the sakila example, cold 29.5 s against warm 27.9 s, both
refreshing 21 materializations in 3.9 s. The reason to remove it is that it is silent, not that it is
expensive, and no consumer-size measurement exists to say otherwise.

**And the policy reaches a second class.** `GraphitronModelStore.openAt` has three arms of its own
that return an in-memory store without saying anything, which `RunStore` notes as "the one demotion
no other layer reports": the stamped directory cannot be created, a file at the stamped path carries
a mismatched stamp, or the connection throws, the last being where a file another process holds
lands. Three causes with three different fixes, currently one silent outcome. They become three
failures with three messages.

**What landed, and the one thing the reading missed.** 507 lines deleted against 310 added.
`RunStore` is 534 lines down to 306: `attemptShared`, `captureWithRetry`, `inMemory`, `captureCold`,
`report`, the `Demoted` arm and the four `Demotion` records are gone, `Shared` is `Owned`, and
`recapture` returns nothing, a store never being swapped underneath a caller now.
`GraphitronModelStore.openAt` raises `StoreUnavailableException` on its three arms instead of
answering each with a private store. Six tests that pinned the fallback state the refusal instead,
which is the same specification read the other way round; 14 modules and 7695 tests green.

`timedOutOnALock` survives the retry it was written for, and decides a message rather than a second
attempt: a contended lock is the one write failure a person can act on, so it is said in their words
while a capture bug keeps the driver's. The first draft of that message named the anchor row, which
`store-too-large-to-service` falsifies: its 21 GB consumer store times out on `JVM_METHOD` under the
generous budget, nowhere near the anchor, and the message would have been a confident wrong answer.
It names no row now. That item also carries the sharpest evidence against this whole decision, and a
dated note now says so in its body: on that consumer the demotion bought a cold minute where the
refusal buys a failed build.

**And then the waiting went, which the refusal is what earned.** A capture carried two lock budgets,
2 s for the anchor row and 60 s for everything after it, and the difference between them was an
argument about which rows are worth waiting out. Both rest on "a writer that waits its turn beats one
that falls back cold", which `fileUrl` says in as many words. Neither half survives: nothing falls
back cold, and the concurrent writers of one file were every module of a workspace sharing one, which
stopped being the arrangement on 2026-09-11 when the one-shot store became build output under the
module's own target. Two modules are two files; the session store has one writer. So the connection
carries no budget, `ANCHOR_LOCK_MILLIS` and the `SET LOCK_TIMEOUT` narrow-and-restore pair are gone,
and a capture that meets a held row ends.

**The obvious spelling of that is the one spelling that does not work, and it is measured.** Against
H2 2.4.240, a contended row blocks 2000 ms under `LOCK_TIMEOUT=0` and 2005 ms with the parameter
absent, where `LOCK_TIMEOUT=1` blocks 1 ms. Zero reads as no value given and H2 substitutes its own
default, so anybody tidying the budget to zero would restore it while appearing to remove it. The
value is 1, the reason is in the constant's own comment, and `PersistentStoreTest` bounds the elapsed
below H2's default so the tidy-up fails rather than passing quietly.

**Phase 1's first step landed 2026-09-14, and it moved the read without moving the seam.**
`AttributedRegistry` is in `no.sikt.graphitron.model.schema` with the load on it as
`AttributedRegistry.load(ctx, jooq)`: the per-source parse, the four configuration-driven rewrites,
the pre-synthesis handle and the `@key` synthesis. The delegate on the generator went rather than
staying as a shim, so nothing there implements a read any more and three test call sites go direct.
What has not moved is the call. `GraphQLRewriteGenerator.runPipeline` still opens by asking for the
read, and `CaptureMojo` still constructs a generator to reach it, so the module boundary is where it
should be and the seam is where it was. That is the next step rather than a defect in this one, and
both acceptance criteria above are still unmet.

**The seam needs the expansion to move first, and the first piece of that landed 2026-09-15.**
Phase 1 has capture returning the assembly, and the sharper version of that is worth stating: not
the assembly capture happened to make alongside the store, but *the assembly that corresponds to the
store*. Correspondence by construction rather than by gate, which is the whole reason to put it
here.

Reading the code for it found the ordering wrong. The thing that builds the final schema is not in
the generator's emission half at all: `ConnectionPromoter.synthesiseForField` runs per field
*inside* the classification walk and `ConnectionPromoter.rebuildAssembledForConnections` runs after
it, consuming the walk's own output. So capture cannot hand back a final schema until the expansion
it depends on is somewhere capture can run, which makes the expansion's move the prerequisite of the
seam rather than a later tidy-up.

What the delta actually is turns out to be small, and `AttributedRegistry.load` already marks it in
its own body: everything above `preSynthesis` is a loading rewrite capture sees, everything below is
synthesis it does not. Two things live below that line and the store states both as rows. So the
emitted registry is the transcribed one with those rows applied, and that is
`EmittedRegistry.of(transcribed, store)`, new in `no.sikt.graphitron.model.schema` beside
`SchemaAssembly`. It is inert: nothing calls it in production, the existing path is untouched, and
the build behaves exactly as it did.

Three decisions inside it are worth the author's eye, because each could reasonably have gone the
other way:

- **It reads the anchors, not the minted relations.** `graphitron_type`, `graphitron_field` and
  `graphitron_argument` are the minted rows already reconciled against the transcription with
  `precedence` applied. Reading `graphitron_minted_*` and re-applying precedence here would put that
  rule in two places, which is the defect being removed rather than one to reproduce. A contested
  coordinate reaches the patch as an absence, `graphitron_minted_conflict` holding it and the anchor
  deliberately holding no row, so it needs no arm.
- **Additive and type-replacing, never wholesale.** The anchors carry what rendering needs and not
  what only the transcription can assert, applied directives among them, so a field rebuilt from its
  anchor row would silently lose every directive its author wrote. An existing field therefore keeps
  its node and gets its type expression replaced where the anchor disagrees, and gains only the
  arguments it lacks. Only a type the registry does not have at all is built from rows, where there
  is no authored detail to lose. Nothing is removed: the store holding no row for something the
  registry declares is a capture defect, and stating it as a difference is the gate's job.
- **Synthesised keys are read from the relation that derives them**, not filtered out of the
  composition. The first draft read `intent_federation_key` and kept the rows whose `ordinal` was
  null, which is a discrimination on a statement about document position that happens to coincide
  with provenance, and it was wrong on review. `intent_synthesized_federation_key` says what to do
  instead in its own last sentence: *this relation is its own provenance*, which is what lets a
  synthesized application leave the transcription families entirely. So the patch names that
  relation and the question disappears. Two things fall out rather than being coded: the rows are
  disjoint from the authored applications by the relation's third condition, so nothing re-checks
  whether the type already carries the key, and both argument values come off the row, the
  relation's comment saying its constants live in it rather than in a comment each composing
  reader re-mints from.

`EmittedRegistryTest` drives a real capture rather than seeding rows, since seeding the expansion's
own output would assume the thing under test, and it carries a control case requiring an
unmacro'd schema to emit exactly the population it declared. The three connection guards were made
to fail before being trusted: neutering the patch fails the minted types, the retyped carrier and the
appended pagination arguments, and leaves the control and the input-untouched case green. The
assembly case is disclosed in its own javadoc as not failing on an unpatched registry; what it
catches is a patch producing a schema the specification refuses.

**One correctness gain and one cost, both deliberate.** `ConnectionPromoter` mints
`GraphQLObjectType` values downstream of `SchemaAssembly.of`, so everything the expansion produces
today bypasses what that class's own javadoc calls "the only place the specification's structural
rules get checked at all". Patching before assembly puts the minted types through it. The cost is
that `assemblyForPipeline`'s second `makeExecutableSchema` stops being federation-only and becomes
near-universal, which is close to neutral against the rebuild it replaces.

**What is still owed**, and none of it is in this step: prove the new registry and the incumbent
`rebuiltAssembled` agree, by coordinate-set equality against `graphitron_element` in both directions
and a printed-schema diff over a corpus; then flip `ModelCapture.capture` to return the store, the
final schema and the verdicts; then retire `ConnectionPromoter`. Note that the flip contradicts the
return listed above: with the transition private, neither registry is exposed, and
`AttributedRegistry`'s two-registry shape then has no consumer, its own javadoc saying
`preSynthesisRegistry` exists only because `KeyNodeSynthesiser` rewrites in place. The retirement is
also not clean, `ConnectionPromoter` hanging forms on the classified variants through
`CarriesObjectForm` and running a scalar-demand sweep, which is classification-side residue that
goes with the form resolution rather than with this.

**The two producers were compared, and they disagree in two places. 2026-09-15.**
`EmittedRegistryAgreementTest` prints both schemas through one `SchemaPrinter` and compares the
text, over the whole corpus, both directions and no hand-picked list. One capture per document, so
about a minute, which is the price this item's own gate paragraph budgets for exactly this.

It found a defect in the patch on its first run, and that is the argument for having run it before
building anything on top. `graphitron_field` is merged across declaration sites, so one row stands
for a field whichever site declared it; a registry does not merge, and `extend type Query` is a
separate node. The patch decided presence by looking only at the base definition, so every
extension's field was declared a second time and all 58 documents failed assembly with
`TypeExtensionFieldRedefinitionError`. Presence is now asked of every site that declares the type,
a field that needs patching is patched at the site that has it, and a field no site declares lands
on the base. Fixed here rather than recorded.

What survives are five documents and two causes, both capture-side rather than defects in the patch:

- **The carrier lost the author's outer non-null. Fixed 2026-09-15.** `ConnectionPromoter` carries
  the authored expression's outer nullability across the rewrite, its synthesis row taking
  `fieldDef.getType() instanceof GraphQLNonNull`, so `films: [Film!]!` emits as
  `QueryFilmsConnection!`. `MacroCapture.rewriteCarrier` wrote the connection as "a bare nullable
  name" unconditionally and its javadoc said so, so the row said `QueryFilmsConnection`. Every
  `@asConnection` carrier in the corpus is written `[X!]!` and four disagreed on exactly that
  character; `connection.graphqls` is the control that stayed green, being the structural form with
  no directive to expand.

  Nothing about the emitted schema changes, and that is the point. The two producers disagreed about
  what graphitron emits, and the row was the one making the false statement: `MacroCapture.element`
  parsed the outer `!` and threw it away, so the information was read and discarded rather than
  never available. It is carried now, on the rule the rewrite already implies, that an expansion
  replaces what a field returns and says nothing about whether the field may be null. Had the row
  been believed instead, every consumer with an `@asConnection` on a non-null field would have
  silently lost the `!`, which is a breaking change; fixing the row is what avoids one rather than
  causing one. The blast radius is small and was checked: `graphitron_field` has three main-source
  readers, `ElementAnchors` which writes it, `ReferencePathFanout` which joins on names rather than
  nullability, and `EmittedRegistry`, which nothing calls in production.

  The ratchet earned its keep here. The fix made four documents agree, and
  `EmittedRegistryAgreementTest` failed until they were struck off, which is the direction a
  standing allowlist never fails in.
- **The facet machinery had no capture-side producer. Fixed 2026-09-16, and it was a mint rather
  than a capability.** `ConnectionPromoter` mints `FacetsType` and `FacetValueType` off
  `facetSpecsFor`; `MacroCapture` did not mention facets. The decode was never the gap:
  `graphitron_facet_entry` held the `@asFacet` applications, `intent_facet_binding` resolved what
  each binds, and `intent_connection_facet` already stated which facets a carrier surfaces and in
  which order, its own comment calling itself what a consumer emitting a faceted connection reads.

  `MacroCapture.expandFacets` mints the triad from that relation: the `<Connection>Facets` container
  with a field per facet, a `<Scalar>FacetValue` per distinct value shape, and the connection's own
  nullable `facets` field. Position order, field name, value type, value nullability and the
  first-wins dedup on a repeated facet name are all the relation's, so nothing here decides what the
  store had already decided.

  **It is a second phase, and the ordering is the design rather than an accident.**
  `intent_connection_facet` reaches carriers through the rewrite rows `expand` itself writes, a
  minted field whose coining coordinate is its own, so those rows have to be in the store before the
  relation can answer. The facet mint therefore runs after `expand`'s flush, which is the rule the
  gatherer's stages already run under one level up. The alternative was to reimplement that
  relation's join against the carriers already in hand, which is one rule read twice.

  `FacetNaming` moved to `no.sikt.graphitron.model.grammar` on the way, beside `ConnectionNaming`
  and `ConnectionDefaults` and for the third time on one argument: a name either side spells for
  itself is a fact with two homes.

**The two producers agree over the whole corpus, and `KNOWN_DISAGREEMENTS` is empty. 2026-09-16.**
That is the condition stage 3 was waiting on and the condition under which `ConnectionPromoter` and
this gate can both be retired.

Worth recording what the last mile looked like, because it is the argument for this gate over a
cheaper one. After the facet mint the type sets matched exactly, in both directions, and the schemas
still differed: on one description, on one line, the count field's docstring having been written
fresh here instead of copied from the generator. A relation-count comparison would have called that
agreement. So would a coordinate-set comparison. Only printing the artifact a consumer actually
receives caught a description that would otherwise have shipped wrong into every faceted schema.

The test's `KNOWN_DISAGREEMENTS` is a ratchet rather than an exemption list: the sweep stays total, a
new disagreement fails the build, and so does one of these being fixed without being struck off. Both
causes are the author's to settle, being decisions inside the capture-side expansion rather than
inside the reader; neither is a reason to hold the reader.

**A check that came out of fixing the above, and earned its keep three times. 2026-09-15.** Before
reaching for Java, ask whether the query can state it. Every instance found so far was a reader
paying in code for something the model already held, and one of them cost a defect rather than only
duplication.

- `MacroCapture.element` took the carrier's authored expression apart a character at a time to get
  the element name, the item's nullability, the list-ness and the outer non-null. All four are
  columns on `graphql_field`. It now selects them, and the outer non-null cannot be dropped on the
  floor by a parser that was never needed. One string test survives, whether the list is nested,
  because `graphql_field` describes one list wrapper and `[[Film]]` and `[Film]` agree on every
  column; `list_depth` reaching the anchors retires it, which is the open half this item already
  records.
- `EmittedRegistry` read fields and arguments flat and regrouped them in memory, keying the
  arguments by a field coordinate it spelled for itself with a string concatenation. A second
  spelling of a coordinate is how two of them come to disagree. It is one nested read now, types
  carrying their fields and fields their arguments on their own keys, which removes the key rather
  than correcting it, and collapses the two passes into one traversal of one answer.
- The fallback page size was declared twice, in `MacroCapture` and on the generator's
  `FieldWrapper`, with a test comparing an emitted row against the field to hold them equal and a
  javadoc calling that "the nearest thing to a shared constant two tiers can have". It was not:
  `graphitron` depends on `graphitron-model`, so the generator could always have read a constant
  here. It is `ConnectionDefaults.DEFAULT_PAGE_SIZE` now, one declaration the compiler enforces,
  and the test that stood in for it is deleted.

**Capture is tested where capture lives.** `MacroCaptureTest` was in `graphitron`, asserting on the
rows `graphitron-model` writes, which put the tests for one module's responsibility in another's.
All ten cases moved to `no.sikt.graphitron.model.capture.macro`; the constant collapse above is what
let the tenth move with them rather than staying behind as a cross-module pin. Four cases reading one
identical document now capture it once for the class, and `ThreadConfinedStore.BOOT_BUDGET` goes 60
to 70: a capture cannot use the funnel, its whole subject being what a capture writes, so these are
boots the module means to pay for. Recounted at 61 and 62 on two reads of one tree, which is the
drift that constant's javadoc describes, so the budget is set with headroom rather than at the count.

**The gate**, which every collapse in phase 2 takes too. One corpus captured before and after, every
relation the schema declares counted under each, both directions. Not the intersection: three
attempts at the catalog pair failed on a hand-picked list, and the two gaps that mattered were
relations only one gatherer populated. One minute against a twenty minute reactor build. Run it, act,
delete it, because a standing comparison between two producers is a standing acceptance that there
are two.

### Phase 2: empty the list

Subtraction from one method everybody reads. The order is the consumers chapter's rather than the burn
down's, because what moves is a reader and not a writer.

**This is a slog and not a design, and the strata table is why.** `graphitron_ast_*_entry` is 59
relations keyed by position, referencing the `graphql_` entries. The incumbent `graphitron_*_entry` is
56 keyed by coordinate, referencing the anchors, which is an entry wearing the wrong key rather than
an anchor wearing the wrong suffix. The anchors are 18. Seventeen facts are stated in both. The same
site decoded both ways is 627 lines with 2 conditionals against 1359 with 149 branch points, because
transcription has no decisions in it.

**Two deletions and one fixture.** `ConfigurationFactCapture` and `StoredRecipe` go, `StoreEntries`
already writing their seven relations plus `store_graph_schema_extension` and
`store_graph_schema_input`. The `graphql_` half goes the same way, `SdlWalkIsRedundantTest` reporting
its 27 relations redundant against every reader. One hazard survives and is a fixture rather than a
reading: the claim and quarantine machinery is duplicate detection, and a claim that skips a duplicate
also stops the decode seeing it, so removing it changes what the decode is offered. Build the corpus
with the duplicate declaration, watch the decode's population change, then decide.

**One query before the last of it.** `FederationLinkApplier` and `KeyNodeSynthesiser` inject
declarations the walk records under `SYNTHESISED_SOURCE_NAME`, and the entry stratum is per document
and has no row for them. Three outcomes: nothing reads them and they go with the walk; something does
and the injection becomes a derivation over captured facts the way `MacroCapture` already made
`@asConnection`'s expansion one; or the fact is genuinely absent from the store, which would be the
first real capture gap this arc has found and is worth knowing early.

**What the last relation deletes.** After the `graphql_` half goes, the traversal exists to compute
directive ordinals and make five decode calls, and each relation that moves takes part of a call with
it. The last one leaves `SdlFactCapture` with no caller: 1122 lines, on top of the 125 of
`SdlCoordinates`, with the sink's record-binding arms and the overflow relation. Nothing is scheduled
for that. A strip is what the last reader's move leaves behind, which is the correction to the chapter
that called it the next commit. That chapter also said the walk and the decode are 2690 lines that
leave together, and they are not any more: `GraphitronFactCapture` is 1270 lines of which the
store-driven half is the target's own, nine stages that read the store, and what leaves with the walk
is `decodingInto` and the five per-site callbacks.

**Then `StoreRefresh`, which is not a thing to dissolve.** Its own class comment says it exists
because the walk it prepares for cannot delete anything, and that every gatherer written since marks
and sweeps instead. Both arms end by subtraction: the graph-scoped arm covers every graph-keyed base
table whose declared owner is not self-sweeping, `SELF_SWEEPING` being `Set.of("code")` today and six
relations; the classpath arm goes with the `jvm_` census the `code_` family is replacing. The 79
relations owned by `document` are not exempt, because that gatherer runs inside the pass and the clear
runs in front of it. With no pass and no clear, what the exemption bought is bought by there being
nothing to be exempt from.

### Declare before you move

A relation whose owner is not declared in `meta_relation` is cleared by whatever clear still stands
and, once its old writer is gone, is not written back. Found by refusing: the first relation moved off
the walk was empty in every store captured through the walk alone, and 112 cases said so. So the
declaration pass is not the burn down's item seven waiting its turn. It is a precondition on every
move in phase 2, one relation at a time, and the roster is the ratchet. 226 relations carry no
declaration today, 113 of them `intent_`, 50 `graphitron_`, 28 `graphql_`. The 134 declared, by owner:
`document` 79, `catalog` 20, `graphitron` 14, `derivation` 8, `code` 6, `sdl` 2, `compile` 1. That
last pair is the measure of phase 2: the walk owns two declared relations and writes 52.

Names left behind go through the retirement sweep at the Done gate, under "Retired vocabulary" above.

### What this does not do

It does not settle the classpath pair. The `jvm_` census and the `code_` family are mid-migration on
their own terms, and the only place that arc touches this one is `StoreRefresh`'s classpath arm.

It does not promise the schema is parsed once for all purposes. Phase 1 removes two of three parses by
removing two callers. Capture wants a per-document parse, two documents declaring one name being two
rows where a merged registry can carry one; the generator wants a merged registry with synthesis
applied. Whether those can be one read is a measurement nobody has taken.

It does not take the in-memory store away from a caller that wants one. A run with no store
directory configured still captures in memory, that being the store it asked for rather than a
fallback from one it could not have.

It does not reorder the burn down's items four through six, rename the entry vocabulary, or re-argue
the register.
## The chain is the fact, and it is missing at all three grains (2026-09-13)

`intent_node_id_decode_hop` is the most expensive thing a capture does: 6.2 seconds of an 8.8 second
materialization pass, to produce 38 rows. The reason is not the rows and not the inputs. Every input
reads fast alone, `intent_condition_method_route` in a millisecond for three rows and
`intent_node_id_decode_endpoint` in none for 84. Composing them costs 529 milliseconds.

**The mechanism, measured by swapping two names.** The view left-joins two reference-target views
under a site predicate and reads their columns with `COALESCE` over the pair. Both are
`WITH RECURSIVE`, so H2 re-runs a whole recursive walk once per driving row. Replacing only those two
names with tables holding 11 and 5 rows, same SQL otherwise, takes it from 529 milliseconds to 8. The
argument-site one alone accounts for nearly all of it, 529 to 20, and it is named twice more:
`intent_node_id_instruction_live` falls from 168 milliseconds to 48 and
`intent_argument_column_scope_live` from 11 to 2, both themselves registered sources.

**The first answer was wrong and is recorded because the reasoning is the trap.** Registering the
argument-site walk removes the measured cost, and the register exists for exactly the shape it has.
It is also motion away from this item: `meta_materialize` is what the item's title says has no
subject, and a twenty-second registration caches a closure that should not be recomputed at a read at
all. Bending the target design to the dissolving one is the failure mode the previous section names,
and this is the third time on this arc.

### What the readers ask

Stated as questions, the three readers of the argument-site walk are two questions, not one:

| reader | grain | the fact |
|---|---|---|
| `intent_argument_column_scope` | the argument | which table a column name written here resolves against |
| `intent_node_id_instruction` | the argument | which table the decode operates on |
| `intent_node_id_decode_hop` | the hop | which foreign keys the decode traverses, in order |

The first two are the same question at the same grain, asked for different reasons: one table per
argument. The third is a different question at a different grain: one row per step.

The model holds neither. It holds a per-element closure with candidate arities, and all three answers
are taken by running it and discarding: the first two keep the last row, the third keeps them all and
filters to the unambiguous ones. So the terminus, which is what two of the three want, is never
stated and is re-derived three times in three different spellings of a `MAX(position)`.

### The fact is a chain, and the manual already names its parts

The user manual teaches this vocabulary already, and it is wider than `@reference`. The directives on
a field, read left to right, describe the path the data travels, starting at the enclosing type's
table and ending at the field's type, and `@routine` contributes a node to that same path: a routine
can sit before, between or after the `@reference` hops. Filtering and ordering resolve against the
*terminus*, the chain's last node, and the manual distinguishes a catalog terminus from a routine
terminus because a function result has no primary key.

So the subject is the chain, not the directive that contributed to it, and R333 has the design:
`TABLE_EXPR` is a sealed `Catalog | RoutineCall`, and a `JOIN_STEP` carries a `stepIndex` and an `on`
that is a sealed `ColumnPairs | Predicate | Lateral`. That design is preferred over anything
reference-shaped for one reason: it is the only candidate in which `@routine` is not a special case.
Every other spelling in the tree is reference-shaped, so a routine node has to be merged in
afterwards, which is the merge the manual describes and the model does not hold.

R877 has the other half of the diagnosis, with these relations as its worked example: the four
reference-step relations are "four spellings of one thing", and ten views reconstruct a fact by
unioning an argument-site relation with its field-site twin, "one fact written at two coordinates
with no relation naming it once".

### The shape

The endpoints relation already exists at one of the three grains. `graphitron_field_table` is keyed
by the field with the arrival in the key, carries the departure as three columns or none of them, and
its `target_basis` already admits `ROUTINE_RESULT` beside the catalog arms. It is the chain's
endpoints, and nothing about it needs to change.

What is missing is the chain's interior, and the same pair at the other two grains.

**The links are the spine.** A link relation keyed by its endpoints row plus an ordinal: the parent
coordinate, the arrival that identifies which chain, and the position in it. Each link carries its
own departure and arrival as foreign keys into `sql_table`. Ordinal zero's departure is the parent's
departure and the last link's arrival is the parent's arrival, which is the denormalization this
buys: the terminus is stated where the readers ask for it and again where a link states what it is,
and the agreement is checkable rather than merely true.

The spine has to be one relation and it has to be total. That is the lesson this arc paid for one
level down: an element set taken as the union of the relations that carry its payloads is missing
every element that wrote no payload, and no numbering over those relations reaches the gap.

**How a link joins is a separate fact**, on the same discipline, three relations rather than three
nullable column groups: the columns it joins on, the external condition for a `condition`-only hop,
and the routine with its parameter bindings, which is what makes a join lateral.

**A routine node needs no arm of its own in the spine.** jOOQ generates a table-valued function as a
first-class catalog table and the store already records `table_type = 'FUNCTION'`, so a routine's
result is an `sql_table` row and a link to one is a link. What differs is the join, which is where
the difference belongs.

**The column pairs are the fact and the constraint is provenance.** Today a hop carries
`constraint_name` and `fk_on_from`, and `intent_node_id_decode_hop_column` exists only to turn that
back into oriented pairs by joining the pair relation and swapping sides on the boolean. If the link
states the oriented pairs, the generator reads what it emits, a name-matched join stops being a
special case carrying a null constraint, `fk_on_from` goes, and that relation has nothing left to do.
The constraint that justified the pairs is still worth naming for diagnostics and for the ambiguity
rules, as a reference rather than as a mechanism a reader decodes.

### Three grains, and they differ in two ways rather than one

The same pair appears at the field, the argument and the input field, and what separates them is two
independent things, neither of which is the site.

**The node vocabulary is richer at the field.** `@routine` is declared `repeatable on
FIELD_DEFINITION` and nowhere else, so only a field's chain can hold a routine node. An argument's
chain and an input field's are catalog hops throughout. That costs the design nothing, because the
routine is a payload relation hanging off a link rather than an arm of the spine: an argument's links
never have a routine row. A population difference and not a shape difference, which is what keeps
three grains from needing three shapes.

**The key is wider at the input field.** A field's departure and an argument's departure are
functions of their coordinates. An input field's is not: one reached under two arguments bound to
different tables walks two chains from one authored path, so its departure belongs in the key rather
than being a column it happens to carry. `intent_input_field_reference_step_target` already keys that
way and its comment argues why.

The site is not one of the two, and the relation names are the reason that needs saying. An output
field and an input object's field are both `graphql_field` rows on one coordinate shape, 694 and 183
of them on the sakila example, so the field and input-field grains are told apart by the departure
rather than by which relation the coordinate came from. And `graphitron_field_table` holds no
input-object field at all today, 364 object rows and one interface row and nothing else, so it is the
output field's endpoints and the input-field grain has no endpoints relation rather than a sparse one.

That is also why these anchors are site-named where the entry relations must not be. R877's complaint
is about transcription, the same fact written four times because the site is in the name, with
readers unioning the twins back together. An anchor is coordinate-keyed, and a field coordinate, an
argument coordinate and an input-field-plus-departure coordinate are three different keys. One
relation over all three carries the columns of the widest and leaves them empty for the other two,
which is the nullable-column defect this item keeps finding.

### What it replaces

Five relations spell the hop and target rule today, split by a site axis the anchors do not have:
`intent_field_reference_step_hop` and `intent_argument_reference_step_hop`, and `intent_field_`,
`intent_argument_` and `intent_input_field_reference_step_target`. With the chain stated, the
recursion moves to capture, `candidates` and `targets` stop being arity columns every reader must
remember to filter on, `last_position` is not needed to find a terminus that is stated, and
`intent_node_id_decode_hop_column` has no work. Whether the registration of
`intent_node_id_decode_hop` survives is then a question with an answer rather than a lever: a relation
that is rows costs nothing to read twice.

## The anchors resolve nothing, and the read side pays for it (2026-09-16, revised 2026-09-17)

Read against "Entries, derivations and anchors, and what each one forces" above, which is the model
this chapter reports a defect against. An earlier revision proposed a rule of its own and a
migration of gatherers; that rule is superseded by the section above and the prescription with it,
and what is kept here is the measurement and the ordering fact, which nothing else records.

### The defect

An authored coordinate is transcribed unresolved and stays unresolved.
`intent_field_producer_reference.class_name` says so itself: "the fully-qualified binary name the
reference spells, exactly as authored. Unresolved: no row here asserts the class is on the
classpath, and a misspelling is a row like any other."

Resolution then happens in a view a consumer reads, joining through `store_graph_source` into
`sql_table` or `jvm_method`, and the arity travels with it:

```sql
CAST(COUNT(*) OVER (PARTITION BY r.graph_name, r.type_name,
                                 r.field_name, r.declared_via) AS INT) AS candidates
```

On the shipping DDL that column is on 20 relations and 34 predicates guard `candidates = 1`.

`intent_field_producer_method` states what it is for: "The intended reading is that a reference
matching more than one method is a rejection, and what a rejection needs is the arity, which is why
this relation states it rather than picking." The mechanism carries a rejection to a position able
to make it and there is none downstream, so every consumer carries the number instead. It spreads by
citation rather than by decision: the same comment reads "Ambiguity is rows and never a decline, as
on `intent_bound_table`."

### The form that fixes it is already in the tree

The section above requires a derivation's rule to be stated as a view, which reads as forbidding the
resolution from ever deciding. It does not. `graphitron_field_routine` is the worked example:
the rule is a view, it computes the arity with a window function, it filters `candidates = 1`, and
the stored relation holds only the resolved row. A spelling two schemas both answer draws no row.
The count is computed once where the rule is stated and never reaches a consumer, which is the whole
of what the twenty relations and thirty-four guards are paying for.

So the defect is not that the resolution is in SQL. It is that the arity is published instead of
spent. Two things are owed at each site that publishes one: the filter, and a defect relation total
over the population it excludes, on `intent_condition_method_route_defect`'s settled terms, so that
absence means "not reached" alone.

### The ordering fact, which is the live blocker

`ModelCapture.capture` is five statements and the anchors run second.

```java
writeGraph(dsl, graph, readAt);
SdlCapture.capture(dsl, graph, config, readAt);              // captureGraphitronAnchors runs inside
StoreEntries.write(dsl, graph.name(), config, readAt);
JooqFactCapture.capture(dsl, graph.name(), jooq, readAt);    // sql_ first exists here
CodeCapture.capture(dsl, classpath, ..., readAt);            // jvm_ first exists here
```

`sql_` does not exist until the fourth statement and `jvm_` until the fifth, so a rule resolving
against either cannot be stated at the anchors on this path. The incumbent carries the opposite
order, `CatalogFactCapture` at `FactCapture:366` against the anchors at `:382`, so the two entry
points disagree and the target one has it backwards. Resolving at anchor time was never rejected on
the merits; on the path this item is converging on, it was unavailable.

That is an ordering defect and nothing more, which is what the section above buys by making ordering
the owner's own and local to it. No gatherer has to move and no edge has to be declared: what a rule
reads is read off its body, and what has to be true is that the statement writing it runs after the
statements writing what it reads.

### What the document gatherer is, in the vocabulary above

It owns 84 declared relations, and the count is a fact about a refactor in progress rather than
about a boundary. Seventy-two are at the written-position grain, which is one family; eleven are
coordinate-grained `graphitron_` relations written by `GraphitronAnchor`, which is another; and
`graphql_schema_problem` is an assembly verdict, which is a third. The `graphql_` anchors `SdlAnchor`
writes are a further population again and carry no `meta_relation` row at all.

**Grain separates those families and the writing class does not, which is the trap this chapter fell
into twice.** `graphitron_ast_table_entry` is keyed by written position and `graphitron_table_entry`
by coordinate; one class writes relations of both families today because the decode has not finished
moving, so what a class currently writes is evidence about the state of the refactor and not about
where a relation belongs. The earlier revision read the mixing as a rule and proposed a migration
from it. The field-chain QC then read it the other way and moved a coordinate-grained relation to
`document` to match the class writing it, which was the same mistake with the sign flipped and is
reverted: `graphitron_field_chain_link` sits at the grain of
`graphitron_field_chain_application`, which is the `graphitron` gatherer's.

What survives is narrower than either. Kind is decided by what a row is a function of and ownership
by which gatherer refreshes the relation, and neither is decided by which package the current writer
happens to sit in. Where a declaration and today's writer disagree mid-refactor, which of the two
the first gate holds to is a real question and not one this chapter settles.

### R697 stays blocked

R697 would copy "ambiguity as rows, arity as a column" into the key and column namespaces, which is
the published-arity shape above at two more sites. It is held rather than dissolved: its problem
statement is independent evidence and survives, thirteen in-scope lines carrying an inline `UPPER(`,
the effective-name rule written six times across two views, and `intent_field_reference_step_hop`
computing its tier decision twice. It also carries the only measurement of what getting this shape
wrong costs, a seventy-times regression on `intent_column_match_claim`. What remains of the fold
restatement once the sites spend their arity rather than publishing it is not knowable until they
do.

## The entry sweep reclaims nodes, not files (2026-09-16)

Found alongside the chapter above and a precondition for it. The entry stratum marks and sweeps per
source: `SdlEntries.write` and `GraphitronEntries.write` each end in a delete scoped
`graph_name = ? AND source_name = ? AND touched_at <> ?`, which reclaims nodes an author removed
from a file. `captureEntries` loops the sources this reading parsed, so a file that left the corpus
is never visited and nothing deletes its rows. Mark and sweep reclaims within a file. No pass
reclaims files.

`store_source` has the same gap from the other side. `writeSource` is an upsert and no site in main
deletes from the relation, so a schema file removed from the configuration keeps its registry row
with a stale `last_seen`.

The DDL anticipated exactly this and neither half of the mechanism runs. Every entry relation
carries `source_ref`, nullable, `REFERENCES store_source (source_name) ON DELETE SET NULL`, with a
`CHECK (source_ref IS NULL OR source_ref = source_name)`, and the column comment states the intended
cycle: "Deleting a registry row nulls this and deletes nothing, so a file that went away leaves this
graph's reading of it standing and flagged, and the owning graph reaps its own on its next refresh."
Nothing deletes the registry row, so the flag never sets, and no query anywhere reads
`source_ref IS NULL`, so nothing would reap if it did.

It is latent rather than live because `StoreRefresh.clear` still empties every graph-keyed base
relation for the graph on a warm pass, and the entry relations are in that set: their owner is
`document` and `SELF_SWEEPING` is `Set.of("code")`. The clear that the per-source sweep exists to
replace is what currently removes a vanished file's rows. Adding `document` to `SELF_SWEEPING`
without closing this first makes a removed schema file permanent, and it does not stay in the entry
stratum: `SdlAnchor` reads the entries filtered on `graph_name` alone, so a stale entry keeps a
deleted type's coordinate alive in the anchors and from there in front of the generator.

The fix is local to `captureEntries`, which already holds the list of sources it read: delete the
graph's entry rows whose `source_name` is not in that list. This is the entry stratum's half of what
the section above forces of anything stored, an owner and a mark and sweep that a later reading can
correct; the per-source sweep is the half that corrects nodes, and this is the half that corrects
files.

It is blocked, and on the same requirement one stratum up. A sweep that actually deletes anchors
meets 45 stored relations that no owner corrects, so their rows are held only by a parent's clear.
Cascading them builds green and is the wrong answer: a cascade corrects a row when its parent goes
and does nothing for a row whose parent stands and which this reading no longer derives. Twenty-six
of the 45 are composed rather than establishing a grain, so under the table above they are views and
not stored at all. Across the whole schema 125 of 266 tables carry no stamp, which is one cut of the
census that section records as owed.

## The lint rules become statements, and the build reads them (2026-09-16, revised 2026-09-18)

Harvested from session-b. Nine rules that were visitors over the parsed document are now nine arms
of `lint_violation`, each a statement over the captured rows: `input-object-name-suffix`, the three
name shapes, `field-names-camel-case`, `no-typename-prefix`, `types-and-fields-have-descriptions`,
`deprecations-have-a-reason` and `no-deprecated-directive-usage`.

The relation is keyed on the written position rather than on the coordinate, and the consequence is
taken rather than tolerated: a name spelled wrongly at a base declaration and at two extensions is
three rows, because that is three lines the author edits. A coordinate-keyed relation would have
said it once and pointed at whichever site capture met first.

A row asserts a defect, which decides each rule's population and not only its predicate. A type
extension carries no description slot, so an undescribed one draws no row: it is not an undocumented
type, it is a place where documenting cannot be done, and a finding there would be a false fact
rather than a noisy one.

Two things are worth keeping from how it was verified. The statements were held against the visitors
they replace for as long as those existed, and separately against this repository's own five
thousand line example schema, which nobody wrote for a lint test. Only the second of those survives
the retirement, which is why the rules gained per-rule cases of their own before the walk went: a
shadow says two implementations agree and says nothing about whether either is right, so a tree
holding only shadows is one where nine rules could drift together and stay green. And the anchors
are the whole difference between a pattern in Java and the same pattern in SQL: `Pattern.matches`
anchors both ends, `regexp_like` does not, so an unanchored camel-case pattern holds of nearly every
name there is and the rule goes quiet rather than loud. A rule gone quiet reads exactly like a
corpus with nothing wrong in it.

The shadow family is gone and production reads the rows. `GraphQLRewriteGenerator` calls
`LintFindings.of`, which turns `lint_violation` into what a build report prints and is the only
reader of the rules there is. `LintEngine`, the five SPI types it dispatched through, the nine
visitors and both shadows were deleted with it, so the walk is not a second opinion held in reserve;
there is one producer of a lint finding and it is the view.

### The ownership question, answered by the relation not being a table

This chapter asked which gatherer owns `lint_violation`, said the `derivation` filing was a
placeholder, and left the answer to whatever refreshed the relation once something did. The question
was malformed, and the shape of the mistake is this item's own subject pointed at this item.

Nothing refreshes `lint_violation`, and nothing ever will, because it is a view. The gate it was
being held to reads the owner off whichever gatherer writes the rows, which presumes rows get
written. A view has no such gatherer and is not missing one: it is evaluated by being read, so the
placeholder was standing in for a writer that was never owed. The `derivation` filing is not a
name held until a better one arrives. It is the answer.

That is this item's thesis arriving at its own lint section. The claim it opens with is that cost
is a modelling question rather than a storage one and that registration is unearned once the shape
is right; `meta_materialize` is the record of choosing "stored" twenty-two times without asking the
prior question. The lint rules were the twenty-third such choice in the making, and asking the prior
question retired it: no corpus is read, no key is established that the relation's own references do
not already cover, and no read cost has been measured. The schema says exactly this in the
relation's own rationale, and has since the writer was deleted. This chapter is where the record
lagged behind the store it describes.

What the view buys beyond stating the rules once is that the integrity a stored version would have
needed foreign keys to defend is structural instead: every row is constructed by joining the
relations it cites, so there is no row that can outlive what it was derived from.

## QC of the field-chain slices (2026-09-17)

The five arms are each covered, positives and negatives both, and the two stored derivations sweep
after their writes rather than clearing before them.

**One relation named an owner its grain contradicts.** `graphql_ast_entry` was declared to the sdl
gatherer. It sits at the written-position grain along with seventy other relations, every one of
them owned by `document`, and `document` is what writes it. Now `document`.

**One finding here was wrong and is reverted.** `graphitron_field_chain_link` was read the same way
and moved to `document` because `GraphitronAnchor` writes it and the other relations that class
writes are declared `document`. That is reasoning from the state of a refactor. The class writes
relations of two families at once, `graphitron_ast_` at the written position and `graphitron_` at
the coordinate, because the decode has not finished moving; the relation is coordinate-grained and
sits with `graphitron_field_chain_application`, which is the `graphitron` gatherer's. The original
declaration was right and stands.

What the pair shows is not what the first draft of this section claimed. The corpus gate is the only
declaration gate that reads an owner and it exempts a gatherer reading no corpus, so the roster can
name any corpus-less gatherer and nothing objects; that much holds and is the first gate's case. But
a gate comparing a declaration against the class that currently writes a relation would have passed
the one real error and failed the correct declaration, so what such a gate compares against needs
deciding before it is built, and during a refactor a declaration may legitimately lead its code.

### Owed, not done here

- **A resolution that comes up empty owes a defect relation.** `graphitron_field_routine` filters
  `candidates = 1` and stores only the resolved row, which is right and is what keeps the arity off
  every consumer downstream. But absence there now means four things at once: no routine written, a
  spelling nothing answers, a spelling two schemas answer, and a result that is not FUNCTION-typed.
  `intent_condition_method_route_defect` states the settled shape for this, total over the
  unresolved population so that absence means "not reached" alone. Owed before a consumer reads the
  relation; nothing reads it yet.
- **No case re-reads.** All three new writers mark and sweep and no test writes twice, so the sweeps
  are unexercised. The arms are pinned; what is not pinned is that a row this reading stopped
  deriving goes away.
- **`sql_name_matched_key_column` clears whole rather than sweeping.** Defensible on its own terms,
  being a function of the catalog rather than of the run, and stated as such in its writer. It is
  still the one new relation that does not get its rows the way the third gate says a stored
  relation does, and the gate should either admit the total recompute or the relation should stamp.

## One gatherer per stage of reading the corpus (2026-09-17)

`SdlCapture` was four gatherers behind one face. It parsed the corpus, transcribed each document,
decoded the directives on what it transcribed, and reduced the documents into a schema to see what
reducing them raised. The four are now four classes, and the face is gone.

| Gatherer | Input | Writes | Anchors with |
| --- | --- | --- | --- |
| `GraphQLSourceCapture` | the configuration | the `SCHEMA_FILE` rows of `store_source` and this graph's membership | nothing; it establishes no grain |
| `GraphQLAstCapture` | the documents | `graphql_ast_*` | its own anchor statements, then the entry-position index |
| `GraphitronAstCapture` | the documents | `graphitron_ast_*` | `GraphitronAnchor` |
| `GraphQLAssemblyCapture` | the documents | `graphql_schema_problem` | nothing |

Three things this settles that the single face left unsaid.

**Anchoring is a gatherer's last step, not a gatherer.** An anchor establishes a grain out of what
the whole corpus says, so it cannot run per document; it runs after the gatherer's own mark and
sweep, on the rows that survived. Each gatherer's anchoring reads only what that gatherer just wrote
plus what an earlier one settled. `SdlAnchor` was a class named as though the step were a thing, and
its 26 anchor statements and their sweep are `GraphQLAstCapture`'s own now. `GraphitronAnchor` is
still a class of its own and is the same case; it moves when `GraphitronAstCapture` takes it.

**The corpus is read once and the read set has one owner.** The gatherers below the parser are
handed a list of documents, not a configuration, so no two of them can disagree about which files a
reading met. Each element carries the source name, the registry where there is one, and whether the
file's bytes differ from what the store last held. A document that would not parse is in the list
carrying no registry, which is what lets the gatherers below sweep the rows an author just broke.

**The order is the source gatherer's contract and the assembly's obligation.** The list comes back
oldest file first, bound by `GraphQLSourceCaptureTest` rather than inherited from whatever order the
parser walked. The assembly honours it, so a collision between two declarations of one name leaves
the older standing, and it is free to merge better than the library does: `SchemaLoader.merge`
refuses the clashing declaration where `TypeDefinitionRegistry.merge` refuses the whole document.

The roster follows the code. One `document` row became four, and its 86 relations partition by grain:
20 at the written position under `graphql_ast_` to `graphql-ast`, 53 at the written position under
`graphitron_ast_` to `graphitron-ast`, `graphql_schema_problem` to `graphql-assembly`, and the 12
coordinate-grained `graphitron_` relations to `graphitron`, where they already sat.
`graphql-source` owns no declared relation, the one relation it writes rows of being shared across
every source kind and partitioned by none of them.

The first cut of that partition took the name prefix instead, which put those 12 under
`graphitron-ast` because `GraphitronAnchor` writes them. That is the trap this item has now fallen
into three times: what a class currently writes is evidence about the state of the refactor and not
about where a relation belongs, `GraphitronAnchor` writing relations of two grains at once precisely
because the decode has not finished moving. Grain separates them and the writing class does not.

### Owed, not done here

- **`GraphitronFactCapture` is still mid-pass.** It reads `graphql_`, `sql_` and `code_`, so it
  belongs after all three are current, and it is named for a stratum rather than for what it does.
  It becomes `GraphitronAssemblyCapture` at the tail of the capture run.
- **The walk still parses for itself.** `FactCapture` reads the corpus its own way, deriving its read
  set from what it parsed and writing membership through its sink, so the corpus reader stays the
  other pass's. Both go together when the walk does.
- **Nothing reads `changed` yet.** It is captured because it is free where the bytes are already in
  hand and expensive anywhere else. Skipping the recapture of an unchanged document is what it is
  for.

## The three site anchors the walk was still the only producer of (2026-09-17)

`GraphitronFactCapture`'s nine resolution stages read five families, and `ModelCapture`'s pass writes
all five. Asking what actually stopped them running there produced a list of three, not a design
problem: `graphitron_node_entry`, `graphitron_node_keycolumn_entry` and `graphitron_routine_entry`
were written by the walk alone, while `GraphitronAnchor` already derived ten siblings of exactly
their shape from the `graphitron_ast_` stratum. They are derived now too, so nothing the stages read
comes from the walk.

Each is the same statement the ten beside it are, an insert over a select over the entries, with
`touched_at` added to all three so they mark and sweep like every other anchor.

Three decisions worth stating, because each was a fork with a wrong branch that still compiles.

**`routines()` does not use `claimedOnField`.** That helper joins the type declaration for its merge
order, and `chainLinks` had already found that wrong for a field-site directive: a field coordinate
is declared once, two declarations of one field being a duplicate-field error rather than an order
to settle, so the join multiplies each application by the number of declarations the type carries.
Filtered to rank one the multiplication is invisible. This relation is keyed *by* the ordinal, so
every rank is a value a reader sees, and a numbering has to be right everywhere a choice only has to
be right once. The coordinate therefore comes off the entry stratum's own index.

**The ordinal is assigned before the decode is joined, not after.** `routine_ref` is NOT NULL, so an
application naming nothing writes no row in either stratum, and the walk had already spent its
ordinal on it. Ranking after the join would close that gap and renumber every application behind it.

**A key column's position is selected, not ranked.** It is the element's own index on
`graphql_ast_value_entry`, which is `defaultOrderFields`' argument reused: the order is part of what
the directive says, the element's row carries it, and the legality rule stops a gap opening between
the two strata.

### How the agreement is held

Both readings run against one store in the `CapturedStore` fixtures, the walk first with its own
instant and the derivation second with another, and the derivation ends by sweeping every row of the
graph carrying an instant that is not its own. So a row the walk wrote and the derivation failed to
reproduce is deleted rather than left standing, and what survives is the derivation's work by
construction. `GraphitronSiteAnchorAgreementTest` reads the three relations after that and pins what
the corpus says they should hold.

The test was checked against a broken derivation rather than assumed to be live: with the directive
name misspelled the relation comes back empty and the case fails, which is the sweep doing what the
paragraph above claims. The existing agreement fixture would not have caught it, carrying neither
`@node` nor `@routine`.

### Owed, not done here

- **The walk still writes all three.** One producer each is the next slice, and until it lands the
  rows a reader sees depend on which pass ran last.
- **The sink, and then the move.** `MacroCapture.expand` takes a `FactSink` for its first-wins claims
  on `graphitron_minted_`, which is a construction rather than a redesign, and after it the nine
  stages move to the tail of `ModelCapture` as `GraphitronAssemblyCapture`.
- **One `intent_` read is left, and it is somebody else's item.** `intent_node_metadata_defect` is
  R952's, which splits it per grain rather than renaming it; the section below says why. It does not
  block the move, reading the catalog alone and therefore resolving in either pass.

## A stage reading an intent_ view, and what the reaches turned out to be (2026-09-17)

A capture stage must not read the derivation family, for four reasons that stack.

It inverts the model's direction. `intent_` is the read side, the vocabulary the generator asks its
questions in, shaped by what a consumer needs. A stage is a producer. When a producer reads a
consumer-layer relation, a rule written to answer somebody's question becomes the definition of a
captured fact, and tuning that rule for its actual consumer silently changes what capture recorded.

The declared edge runs the other way. `meta_gatherer_dependency` has `derivation` depending on the
graphitron and catalog gatherers, so a stage reading an `intent_` view is that edge reversed. The
store already has the gate: a declared view may read only what its owner owns or depends on. These
reads escape it because the stages are hand-written jOOQ rather than stored view definitions, not
because they satisfy it.

It makes a gatherer's output depend on a different reading. An anchor is derivable from the entries
the same reading wrote, which is what lets its sweep mean anything; reading a derivation makes the
output a function of whatever the derivation gatherer last refreshed.

And the reach is a signal rather than only a debt. It usually means a fact the stage needs was never
placed at a grain, and the derivation family is where that omission goes to hide.

### Applying the family's own admission test

Neither of the two reaches turned out to be a derivation-family rule. The `intent_` charter states a
decidable test: expand a candidate through the family until only captured relations are left and
count the families those sit in, where `graphql_` and `graphitron_` count as one. Two or more admits
it; one means the rule belongs to the family it reads.

`intent_connection_element_type` expands to `graphitron_type` and `graphitron_field` and stops. One
family, so it is `graphitron_connection_element_type` now. The rename was the small part: the frozen
undeclared roster reads a rename as a retirement plus a new relation, so it came off the roster and
gained a `meta_relation` row. Its grain is `graphitron_type`'s, being that relation's key restricted
to the types the rule admits, so it establishes no grain and owes no table; its owner owns both
relations it reads, which is what the ownership gate wants; and the comment gate moved its prose
into the two places that hold prose, the comment carrying the grain sentence and the example and the
rationale carrying the why.

`intent_node_metadata_defect` expands to `sql_node_metadata`, `sql_node_key_column` and `sql_column`
and stops, so it is the catalog family's by the same test. It is **not** renamed here, and the
rename this work started was backed out. R952 already owns it and its plan is a split rather than a
rename: eight of its ten defect values sit at the metadata row's grain and two at the key-column
entry's, so `position` is NULL on eight rows in ten, and a declared table cannot carry that against
a key gate that requires the primary key to equal the grain's key shape. The name this work reached
for, `sql_node_metadata_defect`, is the one R952 reserves for the narrower of its two relations, so
taking it would have claimed the name while leaving the fault. The stages keep reading the view
until that item lands, which costs the move nothing: it reads one corpus and resolves in either
pass.

## QC of the site anchors and the intent_ admission test (2026-09-17)

The three site anchors hold up. Each carries a stamp, each joined `GraphitronAnchor`'s sweep list in
the right place with the key column's child ahead of its parent, the walk's writer gained the column
it now has to supply, and the agreement cases assert values rather than shapes, one of them over the
sweep. The roster partition holds too, checked against every relation rather than the twelve that
moved: 53 at the written position under `graphitron-ast`, 20 under `graphql-ast`, 29 coordinate-
grained under `graphitron`, and the assembly verdict at its own grain. No relation sits under an
owner its key contradicts.

**One claim in it is false, and it is about code the commit did not touch.** `routines`'s javadoc
said `claimedOnField`'s declaration join "multiplies each application by the number of declarations
the type has". It does not. The join binds all five columns of `graphql_type_declaration`'s key,
including the field's own enclosing position, so it matches exactly one row. Measured rather than
read: a `Widget` carrying three declarations and one `@asConnection` yields one row from that join,
not three. The decision the sentence was supporting is still right, and for the reason stated beside
it, that a relation keyed by the ordinal shows every rank where one keyed by the coordinate shows
only the winner. What the sentence added was a defect in five existing callers that do not have it,
which is worse than a loose sentence: it invites a fix where nothing is broken. Corrected in place.

### The admission test, applied to the family rather than to one member

The rename states a decidable test for `intent_` membership: expand a candidate until only captured
relations remain and count the families they sit in, `graphql_` and `graphitron_` counting as one.
Two or more admits it; one means the rule belongs to the family it reads.

Run over every view in the family, the test condemns **57 of them**. The implementation was held to
the two verdicts the rename itself states before the count was believed: it agrees that
`intent_node_metadata_defect` reaches only `sql_`, and `intent_connection_element_type` is absent
because it has already moved.

That is not a finding against the rename, which fixed one and deferred one deliberately. It is the
size of what the test implies, and it belongs with the aggregate census this item already owes. The
number is large enough to be a claim about the family rather than about its members: a stratum where
half the rules read one family is not a resolution layer, it is where rules were put.

## Two of the three site anchors get their single producer (2026-09-17)

The walk's `@node` arm is gone. `graphitron_node_entry` and `graphitron_node_keycolumn_entry` are
`GraphitronAnchor`'s alone now, so which pass ran last no longer decides what a reader sees. The arm
lifted out whole: it wrote those two relations and nothing else, and `@node` falls to the decode's
`default`, which is what a directive with no relation of its own already does.

Nothing else moved with it. Neither relation is declared, both standing on the frozen undeclared
roster, so there were no ownership rows to carry; and neither went through a hand-written statement
in `FactWrites`, so the sink's generic arm covered them and still does for whatever else uses it.

Ordering holds in both passes. In `ModelCapture` the derivation is the only writer. In the walk's
pass `GraphitronAstCapture.anchor` still runs inside the same transaction, after the walk's flush and
before the stages that read these relations, so the rows are there when `Nodes` and `NodeKeyColumns`
ask for them.

### `graphitron_routine_entry` is not retired here, and the reason is a second relation

`graphitron_routine_column_mapping_pair_entry` holds a foreign key into it and the walk writes both
in one buffered pass, flushed before the anchors run. Remove the parent's write alone and the child
dangles at that flush. Reordering does not answer it either: the anchors read the `graphql_` anchors
the walk itself writes, so they cannot precede the flush that publishes them.

The shape that does answer it is the one this item keeps arriving at: the pair belongs in the entry
stratum, keyed at the written position like every other decode, with the resolved relation derived
from it. What makes that its own slice rather than a line here is that the pair grammar is one
shared decoder with a quarantine path, reached from ten sites across the argMapping family, so
moving it for `columnMapping` alone would either fork the decoder or drag the family with it.

That slice landed in the same arc. `graphitron_ast_routine_column_mapping_pair_entry` states the
pairs at the position the application was written at, keyed by the index the grammar gave each one,
on the shape `graphitron_ast_federation_key_selection_entry` already uses for a string with its own
grammar. The resolved relation is derived from it, carrying that index across to the coordinate
rather than ranking the pairs again, and both it and `graphitron_routine_entry` now have one
producer. The walk's `@routine` arm keeps three things and loses two: it still claims, still records
the spelling, and still writes the argMapping pairs, which key into nothing that moved; it no longer
writes the routine row or the mapping pairs. It still parses `columnMapping`, for its quarantine
alone, the entry stratum stating what a definition admits and nothing about what it refused.

### What retiring a walk writer actually depends on

The retirement broke one case, and the break is worth more than the fix. `PipelineCapturedStore`
captured with `SubjectConfig.none()`, so that pass read no corpus, wrote no entry stratum, and
therefore derived no `graphitron_node_entry`; two synthesised federation keys went missing and the
agreement case against the registry rewrite caught it. The fix is that the pass now names the
fixture it had already written to disk, which is what makes its two halves read the same documents.

The condition is older than this slice and wider than the one site. A `none()` capture runs the
document gatherers over nothing, so every relation standing on the entry stratum comes back empty,
and `graphitron_table_entry` has been in that state since its own arm was retired. Nothing noticed
because nothing in those passes asserts on it. There are around fifty `SubjectConfig.none()` capture
sites in the test tree and only one of them happened to assert on a relation that had moved.

So the rule a retirement has to satisfy is not "one producer writes it" but "every pass that reads
it supplies the corpus it is derived from", and the second is a property of the callers rather than
of the gatherer.

`SubjectConfig.none()` is now compatible with that rather than a hole in it, and the fix is one
branch rather than fifty edits. A merged registry has not forgotten where its definitions came from:
its parse order is grouped by source, so a pass configured with no files takes its corpus from the
registry it was already handed, splitting it back into one registry per source with the same two
calls `SchemaLoader.merge` makes in the other direction. The entry stratum such a pass writes says
what the walk beside it says, because both read the same merged registry.

**It refuses the one thing it cannot do soundly, and finding that out is what the experiment was
for.** The first version wrote whatever the registry held, and the pipeline fixture failed several
frames down in a writer asking a null source location for its file name. The entry stratum is keyed
by the position a node was written at, so a node carrying no location was never written and has
nowhere to key; a registry holding one was assembled rather than parsed and is not a corpus,
whatever else it is. That is refused at the boundary with a message naming the definition and
telling the caller to configure the files it reads. Across the whole `graphitron` module the refusal
fires nowhere: every remaining `none()` caller hands in a registry parsed from files it wrote, and
the one that did not is the pipeline store, which has a fixture on disk and now names it.

What the fallback does not do is make a registry as good as a corpus. A merge has already dropped
the losing side of a duplicate declaration, so two documents declaring one name are two entry rows
when the files are read and one when the registry is split. No caller on this path can reach that
difference, the merge having happened before capture was called, and it is the same loss the walk's
pass already carries.

## R952 absorbed, and the nodehood merge had two implementations (2026-09-18)

R952 was filed to move `intent_node_metadata_defect` to the family whose relations it reads, and to
split it per grain because eight of its ten defect values sit at the metadata row's grain and two at
the key-column entry's. Absorbed here because the capture stages read it and the move needed
settling before they move. Looking at it properly turned up something the item had not seen, and the
answer is neither the move nor the split.

**The nodehood merge exists twice.** `graphitron_node` and `graphitron_node_keycolumn` are stored
relations written by the graphitron gatherer, merging what the author wrote with what the generated
class published, each carrying which tier answered. Beside them stood `intent_resolved_node_type_id`
and `intent_resolved_node_key_column`, restating the same tiers, the same dense-rank pick and the
same well-formedness gate as views, with `intent_inferred_node_type` as the published arm both
leaned on. That is why the defect rule appeared to have four readers: it was two readers, counted
once per implementation.

The live side had already moved without the dead side being removed. `intent_resolved_node_key_shape`,
the busiest consumer in the neighbourhood at seven readers, already stood on
`graphitron_node_keycolumn`; `intent_resolved_node_key_column` had no production reader at all; and
`intent_resolved_node_type_id` had one, `StoreNodeTables`, which took two columns
`graphitron_node` carries.

### What the retirement settled, which was not only duplication

Three cases failed when their reads were swapped onto the stored relations, and each was the view
admitting something the stored relation's foreign keys forbid. A pinned column the table lacks was
forwarded as though resolved, where the stored relation keys into `sql_column` and cannot hold it. A
`@node` with no `@table` resolved key columns, where a node without a binding is not a node. A
metadata entry spelled the generated way came back in the author's spelling rather than the
catalog's. Two of those cases carried javadoc saying they existed to record a difference rather than
an outcome; the difference is settled and they state the outcome now.

One behaviour changed rather than being confirmed, and it is worth naming: an ambiguous binding now
silences the pinned tier as well as the two table-reaching ones. A key column is a column of a
table, so a name pinned against two candidate tables resolves against neither, and the relation
cannot express the row the view produced.

### What it bought

`DetectionReadReachGateTest` loses three relations from `ResolvedKeyProjections`' reach, and nothing
replaces them: node identity is read off a table now, so the seek costs nothing there and the tiered
union is no longer re-expanded per driving row. That is R952's stated goal reached without its
split. The fact schema holds 125 views where it held 128.

### The provenance rule the fact model page stated, corrected

The page said the resolved value is "always a view over the populations, never a stored merge", and
that authored and inferred belong in separate relations rather than one relation with a provenance
tag. `graphitron_node` is a stored merge with exactly such a tag, by a decision this work had
already taken, so the page described a design the store had left behind.

It is rewritten to say what holds. Two sources of one value are at least three facts: what the
author wrote, what the other corpus published, and the derived answer, each stated where it comes
from. The derived fact owes an answer where its sources disagree, and that answer is the substance
of the derivation rather than a detail of it. How the derivation is realised is the storage rule's
question, not provenance's. And the origin is worth recording on the derived row, not as a label but
as the way back: a reader holding `JOOQ_METADATA` knows to join `sql_node_metadata` for the rest of
what that class published, where one holding `SDL_DECLARED` reaches the entry and the written
position. That answers the clause the old rule leaned on, that a tag no consumer branches on is
inventory: consumers do not branch on it, they follow it.

### Owed, not done here

- **The defect rule still has two readers**, `Nodes` and `NodeKeyColumns`, both inside the gatherer
  that writes the authority. Its two-grain problem is no longer load-bearing, nothing reading
  `defect` or `position`, so what remains is whether it stays a view those two read or becomes a
  predicate inside them. R952's split is not the answer either way.
- **`graphitron_node_type` admits a `@node` with no `@table`**, which is not a legal node. It is a
  different relation from the two this chapter settled and it disagrees with them at that edge.

## The gates over the schema run where the schema is (2026-09-18)

Six gates ask questions about the store's shape. Three ran in `graphitron-model` at module five of
fourteen, about four minutes into a build; three ran in `graphitron` at module seven, after that
module's whole test tier, about twenty minutes in. Nothing about the later three needs a generator.

The split was an accident of where a field was written. `UnregisteredRelationTest` reached into the
generator's module for one thing, a `@Tag` annotation. `DerivedReadCostTest` reached for that and a
six-line `RunContext` factory over a type this module already owns. The two arms of
`FactCaptureAgreementTest` that enumerate relations and pair anchors against attributes reached for
neither: they sat beside the arms that compare capture against `GraphitronSchema` because the
registration map they read was declared in that class, and the map is read by nothing else.

What it cost is the reason to move them. Every one of the three is a gate that fires when the schema
changes, which is to say when someone is in the middle of changing it. `DerivedReadCostTest`'s
reader count moved twice in one day, once because a sibling session added relations and once because
another retired three; each time the answer arrived twenty minutes into a build rather than four.

They are in `graphitron-model` now. No tier vocabulary moved with them: this module declares none,
the four tiers being the generator module's own way of selecting within its suite, so a case moving
down simply stops carrying a tag. Three fixtures moved to where they are read from:
`MaterializedRegistryFixture`, a hundred and seventy lines of SDL-string building with no import
outside the JDK; `TestRunContext`, which names the jOOQ package every store-reading case here
already names; and `AgreementCorpus`, the schema both the moved gate and the arms that stayed
capture, held once rather than copied.

The five arms that stayed are the ones whose subject is the generator's model, and they retire with
the consumers they shadow, which is what their own comment already said.

## One reading of the classfiles, and a family that holds a declaration once (2026-09-20)

The chapter above states the `code_` family's shape and leaves the migration open. This is what
closing it costs, measured on the arms that exist.

**Two readers of one classpath, and the duplication had already stopped paying.** `ClasspathScanner`
read the compile classpath for the `jvm_` census and `ClassfileCensus` read it again for the
`code_` arms, each with its own scope rules and its own spelling of the signature walk. The scope
rules had drifted in two ways, both silent. The arms read `TRANSITIVE` entries, which
`ClasspathEntry.Origin` says the census does not read and `ClasspathNameability` refuses a name
from: on this module's own test classpath, graphql-java reclassified as transitive contributed five
scalar constants and every throwable it declares, all of them completions the build would then
reject. And the arms' package exclusion was a character prefix rather than a package, so excluding
`graphql.Assert` took `graphql.AssertException` with it, which reads exactly like a classpath that
does not carry the class.

So the signature walk moved rather than being copied. `ClassfileCensus` is the reader and
`ClasspathScanner` is a projection over it holding its four public signatures, so `ClasspathCensus`
keeps its per-entry grain and no caller moved. `GathererIsolationTest` decided where the reader
lives by failing: a helper read from outside its gatherer's package is either part of that gatherer
or tier vocabulary, and a reader two families write from is the second. The projection is
scaffolding with a stated end, which is the `jvm_` census's.

**A declaration's facts are the declaration's, and an arm says which coordinate may name it.** The
arms each held a copy of the method they admitted, which held one fact as many times as there were
arms pointing at it and let the copies disagree. Whether a method may be named at `@service` and
whether it may be named at `@condition` are two questions and earn two relations. What it returns,
what it takes and what it throws are not: they do not vary by the coordinate somebody reached it
through. So `code_method` holds the reactor's public methods, with `code_method_result`,
`code_method_parameter`, `code_method_parameter_element` and `code_method_exception` beside it, and
the three arms are membership: four key columns and an instant.

The role a parameter plays is one column over four exclusive values rather than one vocabulary per
arm, because a position typed as a jOOQ table is typed that way whoever is asking and a position
typed as a `DSLContext` likewise. What either means at a coordinate is the arm's reading of it.

**What a method results in, and why it is not a view.** A field backed by a method is backed by
whatever that method finally hands back, and a return type is a tree: the rule peels `List`, `Set`,
`Collection`, `Optional`, `CompletableFuture`, jOOQ `Result` and `Map` at its value position until
it reaches something that is not one of them, and reports whether anything peeled multiplied.
Stated in SQL that is 43 lines of five self-joins unrolled to a fixed depth of four, because SQL has
no loop, which bounds what it can answer as well as costing what it costs. At capture it is a loop,
and a case pins the difference at five containers, where the unrolled form reports the fifth
container as though it were the payload. The container vocabulary is read from the relation that
states it rather than spelled again; it carries no declared owner, so reading it crosses no
ownership and waits on no gatherer.

The shape rule this arc is worth stating for: an optional fact is a relation whose presence is the
optionality, not a nullable column. A return type names no class when it is a void, a primitive, an
array or a type variable, and a placeholder would make four silences look like one answer. The same
rule gives the table a concrete position names its own relation, which turned an `ON DELETE SET
NULL` into a cascade that deletes the resolution and not the parameter: a consumer dropping a table
has not unwritten their method.

**Measured, against the criterion that a consumer's query must get simpler and not merely change
prefix.**

| | before | after |
|---|---|---|
| `intent_condition_context_parameter` and what it read | 103 lines over three views | 37 over one |
| `intent_condition_method_route` | 63 lines, three CTEs, three `class_fqn` joins | 59, two CTEs, one `sql_table` join |
| `intent_condition_method_route_defect`, `jvm_` namings | 6 | 2 |
| views in the fact schema | 122 | 120 |

Both deleted views are the register's own case stated at capture instead. One asked whether a
position receives the source table as a recursive ancestor climb ORed against a catalog lookup,
once per driving row; the other re-derived `Class.isEnum` through a view. Three of the correlated
per-row namings R942's table lists go with them, and every resolution now joins `sql_table`'s
primary key rather than `class_fqn`, which is no key and can match more than one row.

That is a third lever beside rewriting and materializing, and `fact-model.adoc` carries it now,
because the anti-join it used as its worked example was this one: materialization is what an
excluded relation gets when it states a rule over rows the store already holds, and where the rule
is really a question about a source nobody has asked yet, the gatherer that reads that source
answers it and the anti-join stops existing.

The family costs, on this module's main sources with the generated package excluded as a run
excludes it: 682 methods, 474 results, 759 parameters, 739 elements and 682 arm rows, 3,337 rows at
798 milliseconds. A first reading that included the generated jOOQ package said 16,430 methods,
which is what that package is rather than what this costs.

**What it cost elsewhere.** Keying a concrete position to `sql_table` makes the code gatherer a
reader of the jOOQ gatherer's rows. `ModelCapture` already ran the two in that order and
`meta_gatherer_dependency` says so now; `CapturedStore` did not, which two pipeline cases caught and
which nothing else would have, a condition hop resolved against an empty catalog being a missing
route rather than an error. A parameter relation spanning every method no longer scopes to an arm
by its key alone either, so the three readers that reach parameters by class and method name rather
than by descriptor now name the arm they mean; a reader that forgot would have answered for methods
no condition could name.

**One live behaviour change.** A position written `<T extends Table<?>> T` drew no row in the
deleted view, so it was not a table parameter and was eligible as a context parameter. The
generator asks `Table.class.isAssignableFrom` of the erasure, which for that position is
`org.jooq.Table`, so it is a table slot and not bindable. The arm follows the generator and the old
view was wrong.

**What is not done, and the two questions this shape does not answer.** Four arms remain unbuilt and
the `jvm_` relations they would retire are still written. `intent_condition_method_route_defect`
keeps two `jvm_` namings, `CLASS_NOT_IN_CENSUS` and `METHOD_NOT_ON_CLASS`, and they are the boundary
of the idea: a relation of candidates cannot tell "not a candidate" from "not there", both being no
row. Either those verdicts stop being store answers, or `code_` carries a relation of the classes
the reactor built so absence stays distinguishable.

And a parameter carries no rendering of its own type. That is deliberate as far as the arms go, the
role being what the store is asked, but it is not free: an absent element is equally a primitive, an
array and a type variable, and `ArgmappingProjectionDefects` distinguishes them today off the `jvm_`
side to tell an author to declare `Integer` rather than `int`. A type a person can read is owed to
that reader and to the editor surfaces, once, in the form they render; it is a different column from
either of the two this arc removed, and nothing here supplies it.
