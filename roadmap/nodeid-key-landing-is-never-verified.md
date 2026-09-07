---
id: R926
title: "The @nodeId key landing is asserted, never verified"
status: In Review
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-09-07
last-updated: 2026-09-07
---

# The @nodeId key landing is asserted, never verified

## Goal

A `@nodeId(typeName: T)` filter whose `@reference` path does not reach `T`'s table, or reaches it and
lands `T`'s key on a column of a different Java type, becomes a build-time refusal naming both columns
and both types. Today all of those schemas are accepted with zero errors, and the consumer finds out
either from their own `javac` (a column that does not exist on the table the predicate was written
against, or a `Row9<..., Short>` compared against a `List<Row9<..., Integer>>`) or not at all, because
a mislanded path can also emit SQL that is correct by coincidence.

*Landing* is the per-position fact the classifier computes for such a filter: for each column of the
target *node type*'s key (a node type being a GraphQL type carrying `@node`, whose key columns are its
table's primary key or the columns `@node(keyColumns:)` names), which column the decoded id's value at
that position binds against. Every position landing on a column of the filtered row's own table makes
the predicate a local tuple comparison; any position landing nowhere makes it a correlated `EXISTS` on
the node type's own table. Landing is the whole of that arm choice, and it is computed by matching SQL
column names along the path's foreign-key hops.

Two things it never checks. It does not check that the path's last hop reaches `T`'s table at all, so a
path that stops short lands the key on whatever the intermediate table happens to have named like it.
And it matches by name only, so two columns that share a name and disagree on Java type count as one
column.

A minimal pair over the sakila fixtures. The first form is in the tree today
(`TranslatedFkTargetRailGatesPipelineTest`) and is what the author means:

```graphql
input FilmFilter {
  categoryRef: ID! @nodeId(typeName: "Category") @reference(path: [
      {key: "film_category_film_id_fkey"},
      {key: "film_category_category_id_fkey"}
  ])
}
```

Drop the last hop and the path stops on `film_category` instead of `category`:

```graphql
input FilmFilter {
  categoryRef: ID! @nodeId(typeName: "Category") @reference(path: [
      {key: "film_category_film_id_fkey"}
  ])
}
```

`Category`'s key column is `category_id`, which the one-hop path's arrival (`film_category.film_id`)
does not carry, so nothing lands and the predicate binds remotely: `EXISTS (SELECT 1 FROM film_category
WHERE film_category.film_id = film.film_id AND film_category.category_id IN (<decoded>))`. That
compiles, and the SQL is right, because `film_category` happens to carry a `category_id` column of the
same type, it being the child column of that table's own foreign key to `category`. Point the same
truncated path at a table that carries no such column and the identical classification emits
`FILM.CATEGORY_ID`, and the consumer's build fails.

So the outcomes today, by what the terminal table happens to hold:

| What the terminal table holds at a key position | Outcome today |
|---|---|
| no column of that name | uncompilable generated Java |
| a column of that name, of a different Java type | uncompilable generated Java |
| a column of that name and type, holding a different value | silently wrong SQL |
| a column of that name and type, holding the same value | correct, by coincidence |

The last two rows are why this is a verification gap and not a codegen crash. They are the same
classification, and graphitron has no way to tell them apart: the name and the type agree in both, and
whether the column carries the value the author meant is a fact about the consumer's schema semantics.
Nothing today separates "graphitron checked that this predicate binds the columns the author meant"
from "it happened to". When this item lands, all four rows are refusals at `graphitron:generate` time,
the fourth included, because a coincidence graphitron cannot see is not one it can keep emitting on
purpose.

Which routes those refusals cover, stated here because it is part of the outcome and not a detail of
the plan. A decoding `@nodeId` slot reaches its target one of three ways, and this item judges two:
along an `@reference` path the author wrote, and across the single foreign key auto-discovery finds
where nothing was written. Own-row identity is the third and has nothing to land, the keys arriving on
the row's own key columns. There is a fourth spelling of the first, a per-participant
`@referenceFor(type:)` path under a polymorphic consumer, and a slot on that route is not judged: the
fact store states such a slot's navigation as auto-discovery's rather than as the path the author
wrote, so a verdict there would judge a route the generator does not take. The four-row table above is
therefore about the two judged routes, and
`roadmap/store-reads-no-referencefor-step.md` carries the store's side of it. Both verdicts below
exclude a participant-route branch by a predicate that stops matching anything the day that item
lands, so the coverage arrives without a second decision here.

## Implementation

Both refusals are predicates over facts the fact store already captures, so they land in
`graphitron-model` as one derived defect view plus one derived relation the view's population needs,
read by one reader beside the two `@nodeId` detections that already report from the store, and the
classification walk is not touched. That placement is not a
preference. `docs/architecture/explanation/pipeline-overview.adoc` names the walk (`GraphitronSchemaBuilder`
and the leaf resolvers under it, `NodeIdLeafResolver` included) as the transitional surface of the
strangler migration, "a surface being drained, not a place to extend", and states the rule for the
window: new facts land only in the store. The two `@nodeId` refusals that shipped most recently,
`NodeIdDecodeDefects` over `intent_node_id_decode_defect` and `ReferenceForParticipantDefects`, are
both store readers "gated on nothing of the walk's" (`GraphQLRewriteGenerator.captureAndRead`). The
second reports on `@referenceFor`, and what it judges is the participant *name*: each application's
`participant_type_ref` against the participant sets the applications' consumers offer, read straight
off `graphitron_reference_for` and `graphitron_argument_reference_for`. It resolves no path, and the
resolution it does perform ("does this application apply at this consumer, and to which participant")
is the one this item's population needs too, which is why that resolution becomes the new relation
rather than a second spelling. Everything below was read at `faadfb7`; re-grep the symbols at
pickup, the file moves.

### The facts, and the one that is missing

The decode family under `intent_node_id_decode_endpoint` states, for every slot carrying a decoding
`@nodeId` and every use site and branch of it:

- `intent_node_id_decode_endpoint`: the table the slot's own predicate binds on (`from_*`), the table
  the named node type's keys live on (`to_*`, "where a decode has to arrive"), and `navigation`, a
  closed vocabulary of three: `SAME_TABLE`, `AUTHORED_PATH`, `DISCOVERED_KEY`.
- `intent_node_id_decode_hop`: one row per foreign-key hop the decode traverses, in authored order, each
  with the table it arrives at (`to_*`) and `last_position`, so the terminal hop is the row whose
  `position` equals it. On `AUTHORED_PATH` the hops come from `intent_argument_reference_step_target`
  or `intent_input_field_reference_step_target` by site; on `DISCOVERED_KEY` the one hop is the single
  foreign key from the departing table to `to_*`, so it arrives there by construction. Both
  reference-target views resolve `@reference` steps and only those, their whole lineage
  (`intent_argument_reference_step_hop` and `intent_field_reference_step_hop_live`) driving off
  `graphitron_argument_reference_step` and `graphitron_field_reference_step`, so an `AUTHORED_PATH` is
  always an `@reference`.
- `intent_node_id_decode_column` (materialized from its `_live` rule): one row per position of the node
  type's key, carrying `key_column_name` and the `local_column_name` on the slot's own table the
  position lifts back to, or `NULL` where the walk reached none. Its comment already promises this
  item's second diagnostic: "recording it position by position is what lets a diagnostic say which
  position was the one that did not arrive."
- `sql_column.binding_type`: "the fully qualified Java type jOOQ binds the column to, as
  `Field.getType()` reports it", which is the captured twin of `ColumnRef.columnClass()` and carries a
  converter's user type exactly as the walk's `ColumnRef` does.

The item's two checks are therefore a join between the terminal hop's `to_*` and the endpoint's `to_*`,
and a join from the column relation to `sql_column` twice with a `<>` on `binding_type`. Neither needs
a new captured fact.

The fact that is missing is a derived one, and it is what decides the population rather than the
checks. `intent_node_id_instruction_live` computes `carries_reference_path` from the two `@reference`
step tables alone, so a slot whose only stated route is a `@referenceFor` carries no authored path as
far as the store can see: its navigation reads `SAME_TABLE` or `DISCOVERED_KEY`, and the one hop it
carries where it carries any is the foreign key auto-discovery would find, not the chain
`NodeIdLeafResolver`'s `Route.ParticipantRoute` arm walks. The steps themselves are captured, into
`graphitron_reference_for_step` and `graphitron_argument_reference_for_step`; no view reads either
table. That walk-store disagreement is `roadmap/store-reads-no-referencefor-step.md`, not this item:
it is a re-keying of the endpoint family onto the participant axis, because the endpoint's departure
comes from `intent_argument_scope_table` over `intent_field_scope_table`, whose participant arm is a
`SELECT DISTINCT` over `intent_field_participant_scope_table` that drops the participant name
deliberately: two participants binding one table are one endpoint row, where the walk decides per
participant, `selectRoute` dispatching on `ParticipantRef.TableBound`.

What this item needs from that route is not the path but the answer to "does a per-participant route
apply at this branch", so that a branch on one is excluded rather than judged on facts describing a
different navigation. That question is a resolution over authored applications and consumers, and it
already has a reader: `ReferenceForParticipantDefects` spells it twice as a `notExists`, once per
site, joining `graphitron_reference_for` to `intent_input_occurrence_path` and
`intent_field_participant_scope_table` at the input-field site and to the participant relation
directly at the argument site. A second reader is what turns a query-local join into a relation, so
name it once:

- `intent_reference_for_application`, one row per `@referenceFor` application, consuming coordinate
  and table-bound participant the application's `participant_type_ref` matches there: the application
  by its own key (`graph_name`, `site`, the coordinate columns, `ordinal`), the consuming coordinate
  the participant set comes from (the argument's own field at an argument site, the occurrence path's
  root field at an input-field site), `participant_type_name`, and the participant's table as its
  `sql_table` key. An application naming a participant no consumer offers has no row, which is the
  inertness rule as a population: `selectRoute` takes `Route.ParticipantRoute` exactly where an
  application names the participant in scope and `Route.AutoDiscover` where none does, and that is a
  fact of the schema rather than of the walk, the same input type being consumable by two queries
  with different participant sets. Filed `intent_` because it crosses `graphitron_` capture and two
  `intent_` resolutions, per the ownership rule.

`ReferenceForParticipantDefects` moves onto it in the same commit, its two `notExists` clauses
becoming an anti-join over the new relation. That retires a spelling rather than adding one, which is
the point: the follow-up item needs these rows positively, as the head of the `AUTHORED_PATH` it
derives, and a resolution living in one reader's `WHERE` clause is one the next reader re-spells.
Its own build-error gates stay where they are, the input-field arm's "consumed somewhere" `EXISTS` and
the argument arm's `intent_type_domain` membership being that reader's population and not this
relation's.

### One defect view, two verdicts

Add `intent_node_id_decode_landing_defect`, an `intent_` view beside `intent_node_id_decode_defect`
and shaped like it: one row per refused instruction, use site and branch, a closed `verdict`
vocabulary, typed witness columns, source location, and no message column, the prose belonging with
the consumer that composes it. Its two verdicts:

- `PATH_STOPS_SHORT`. Population: judged endpoints (below) with `navigation = 'AUTHORED_PATH'`,
  joined to the hop at
  `position = last_position`, where the hop's `(to_source_name, to_schema, to_table)` differs from the
  endpoint's. Gated on the chain being whole: the number of hops (`last_position + 1`) equals the
  number of steps the author wrote, read off `graphitron_argument_reference_step` at the argument site
  and `graphitron_field_reference_step` at the input-field site. A chain that stalled (a step that
  resolved to no hop, or to several) is already the walk's unknown-key or ambiguous-key rejection at
  the same coordinate, and a second row here would be a second answer to one fault. Witnesses: the
  terminal hop's table and the endpoint's table, each as its `sql_table` key.
- `LANDING_TYPE_DISAGREEMENT`. Population: column rows with `local_column_name IS NOT NULL` on judged
  endpoints whose `navigation <> 'SAME_TABLE'`, joined to `sql_column` once on the origin table and
  `local_column_name` and once on the endpoint's `to_*` table and `key_column_name`, where the two
  `binding_type` values differ. Both joins are on the column name with the case folded, using the
  generated `column_name_upper`, since `key_column_name` is spelled as the winning key tier spells it
  and `local_column_name` as the catalog does. Excluded where the same endpoint draws a
  `PATH_STOPS_SHORT` row: on a mislanded path the "key column" the lift matched by name on the wrong
  table is not the node's key at all, and the one fault should draw one row. Witnesses: `position`,
  both column names, both binding types, both tables.

**The judged endpoints, stated once.** Both arms drive off the endpoint relation and both are
narrowed the same way, so the narrowing is one CTE of judged endpoints that each arm reads rather than
a predicate repeated per arm; two skip lists that have to agree with nothing binding them is the
shape the fact model warns about. Judged: the endpoint's `navigation` is `AUTHORED_PATH` or
`DISCOVERED_KEY`, `SAME_TABLE` having nothing to land and each arm above excluding it on its own
terms, *and* no row of `intent_reference_for_application` at the slot's own coordinate names a
participant whose table is this branch's departure. The second clause is not "the store does not model
this yet", which is a sentence about the model's maturity and would rot; it is that an endpoint whose
navigation is auto-discovery's while a per-participant authored route applies at that branch states a
resolution the schema contradicts, and no landing computed under it is one anything should judge. That
sentence stays true, and it retires itself: the day
`roadmap/store-reads-no-referencefor-step.md` lands, such an endpoint reads `AUTHORED_PATH` with the
authored hops under it, the clause matches nothing by construction, and both verdicts cover the route
with nothing here to unbuild.

Where two participants of one consuming field bind one table the exclusion is forced to be
table-grained, both of them going unjudged, and that is a property of the endpoint relation's grain
rather than a conservatism chosen here: the endpoint is keyed by coordinate plus departing table, and
the participant axis the distinction would need is exactly what the follow-up item adds. The
exclusion sits on the view's population and not on the reader's build-error gate, which is where the
sibling family puts a narrowing, because these rows are not true-but-unemitted: an editor arm reading
the view ungated would show an author a diagnostic about a route their schema does not take, and can
refuse a schema that is sound. `intent_node_id_decode_endpoint.navigation`'s comment gains the
sentence that says so, naming the per-participant route as one the column does not account for,
beside the story it already carries for the retired `UNRESOLVED_PATH` value; the new view's comment
states what its own silence means, absence in a derived relation owing that sentence.

Two `UNION ALL` arms rather than one pass with a `CASE`. The fact model's inline-multiplicity rule
prefers the `CASE` where two verdicts read the same driving relations for the same facts, which is why
`intent_node_id_decode_defect` is one pass; here the arms drive off different relations (the hop
relation and the column relation) and share only the endpoint, so a single pass would join both
children on every row to decide which one applies.

Keying and the remaining columns follow the endpoint relation: `graph_name`, `site`, `type_name`,
`field_name`, `argument_name`, `path`, `use_site`, `node_type_name`, and the branch as the three
`origin_*` columns, since a slot under a multi-table polymorphic root decodes once per branch and a
branch can mislead where its sibling does not. Source location rides along from
`intent_node_id_instruction` the way the sibling view carries it through the slot relation. A plain
view, not a materialization: nothing on the build path reads it more than once per graph, and the same
holds for `intent_reference_for_application`. Register both
where the schema gates demand a new relation be declared (the `meta_relation` roster row with its
owner, and the `Arm.DERIVED` registration in `FactCaptureAgreementTest`); the gates fail the build
until all four entries are in, so there is nothing to remember.

### The reader

Add `NodeIdLandingDefects` in `no.sikt.graphitron.model.derive`, a copy of `NodeIdDecodeDefects`'s
shape: a private `Verdict` enum decoding the view's vocabulary and failing loudly on drift, a
`Detection(List<Defect>)` product with `violations()` minting `ValidationError.forField(coordinate,
rejection, location)`, a `Defect` record carrying the coordinate, the node type and the witness
columns beside the `Rejection` so no consumer recovers them from prose, and a static
`detect(DSLContext, String graphName)`. The build-error population gate is the sibling's: the use
site's root type is a member of `intent_type_domain` (the argument's own type at an argument site,
the consuming query's owning type at an input-field site, which `ReferenceForParticipantDefects`
reaches through `intent_input_occurrence_path`). Only a coordinate the generator intends to classify
can fail a build; the view itself carries no domain gate, so an editor arm can read it later. That is
a different gate from the judged-endpoint population above and the two do not collide: the population
decides which endpoints have a landing worth judging at all, and the domain membership decides which
of the resulting rows can fail a build. A row the population keeps and the domain gate drops is a
true defect at a coordinate nothing generates, which is what the editor arm wants; a row the
population drops is not a defect.

The reader joins `StoreDetections` as one more component with its `empty()` arm, and
`FactCapture.detect` calls it beside the other four under `ClassifiedRun.Present`. `violations()`
appends it in declaration order. Both rejections are `Rejection.structural`, as the sibling's are:
neither names a closed set an editor could offer alternatives from.

The two messages, stated in full so the wording is reviewed before it is emitted. Both are composed
through `NodeIdMessages`, the vocabulary the `@nodeId` rejection families already share so that two
sites cannot improve one wording and leave the other reading like a different rule:
`nodeIdSpelling(nodeTypeName)` mints the directive echo, and `simpleName(bindingType)` shortens each
qualified Java type, which is why the drafts below read `String` and `Long` rather than the qualified
pair the store holds.

The coordinate lead needs one addition to that vocabulary rather than a copy. `NodeIdDecodeDefects`
spells `argument 'x'` and nothing else, its view being `ARGUMENT`-only by population, and
`ReferenceForParticipantDefects` carries an input-field coordinate on the `Defect` for
`ValidationError.forField` while its message text opens with no lead at all. This item's view has both
sites, so the `input field 'Type.field'` lead is minted here; it goes into `NodeIdMessages` beside
`nodeIdSpelling` so the next family with both sites reads it rather than re-mints it. The branch is
named only where the endpoint population holds several.

> input field 'FilmFilter.categoryRef': `@nodeId(typeName: "Category")` reaches its target through an
> `@reference` path whose last step lands on table 'film_category', not on Category's `@table`
> 'category'. A decoded Category id binds against columns of 'category', so the path has to end there:
> add the remaining step, or name the type the path already reaches.

> input field 'DivergedRefChildFilter.orgRef': `@nodeId(typeName: "ConverterOrg")` decodes key column
> 'org_code' of table 'converter_org', which jOOQ binds as String, and the reference path
> lands it on column 'org_code' of table 'diverged_ref_child', which jOOQ binds as Long. The
> two columns disagree on Java type, so no predicate can bind one against the other. Either align the
> two columns' catalog types, or route the reference through a path that lands the key on a column of
> its own type.

Shortening the two types is the sibling's trade and it is taken knowingly: two types differing only
in package read as one name, and a message that says a column binds as `OrgCode` and another binds as
`OrgCode` says nothing. That is `NodeIdMessages`'s to fix for both families if a consumer meets it,
and it is not fixed here, the qualified pair being on the `Defect` record either way so no consumer
recovers a type from prose.

The second remedy is deliberately not a `forcedType`. A divergence can be accidental (one physical
column, one of its tables given a converter for an unrelated reason, where aligning the catalog is
right) or intended (a code table typing its own key differently from the tables referencing it, where
aligning the catalog would be wrong and the author needs a different path). A message naming one fix
is wrong half the time; this one states the disagreement and both remedies.

### What the walk keeps doing, and why that is enough

`NodeIdLeafResolver` is not changed. It goes on classifying a truncated path as `TranslatedFk` and a
diverged landing as `DirectFk`, `landKeyColumns` stays a total function with one row per key
position (which is the property `intent_node_id_decode_column` states and `FactCaptureAgreementTest`
pins the walk against), and the build fails on the store's violation before anything the walk
classified is compiled by a consumer. Three consequences the item leans on follow from the placement
rather than needing code:

- Every rail is covered at once, on the routes the store models. The write and `@lookupKey` rails
  refuse a `FilterBinding.Remote`
  carrier through `FilterBinding.remoteBindingUnsupported`, which catches only the mislandings where
  no position landed; a mislanded or diverged landing that lands every position classifies `DirectFk`
  and reaches INSERT, UPDATE, DELETE and the lookup today with nothing examining it. A detection keyed
  on the coordinate never passes through a carrier, so there is no rail for it to miss. The boundary
  is the route and not the rail: a participant-route mislanding still reaches a write rail unjudged,
  which is this item's honest edge and is what the follow-up item closes.
- `@condition(override: true)` needs no new branch. The resolver's rule is that only an undiscovered
  route escapes into the author-owned predicate and a stated route that failed stays a rejection,
  because naming a foreign key asks the build to check it. The detection fires on the authored path
  whatever the leaf's `@condition` says, which is that rule.
- `DISCOVERED_KEY` draws no `PATH_STOPS_SHORT` row by construction and can draw a
  `LANDING_TYPE_DISAGREEMENT` row, which is exactly the diverged-fixture case below: nothing was
  written, the one foreign key was found, and its two ends disagree.
- The participant route is untouched in both directions. `Route.ParticipantRoute` goes on walking the
  authored chain and landing whatever it lands, and no verdict reads a branch on that route, so a
  `@referenceFor` schema's outcome is byte-identical before and after: what compiles still compiles,
  and what is right by coincidence is still emitted. That is a gap, and it is filed rather than
  absorbed, for the reason the population gate gives.

No edit-time surface follows yet. `graphitron-lsp` validates a `@nodeId(typeName:)` leaf only for the
named type existing and carrying `@node` (`Diagnostics`, the `@nodeId` arm), and no reader in that
module consumes `intent_node_id_decode_defect` today either. The new view is shaped for that reader
(ungated rows, source location on the row) so an editor diagnostic is a query away when a consumer
asks for it; that is a separate item.

### Three notes from the implementation

Recorded here rather than left for a reviewer to reconstruct from the diff. None of them changes what
the item delivers; each is a place where following the plan's letter would have contradicted
something already in the tree.

**Which coordinate the error attaches to, and why the input-field lead is not a duplicate.**
`ValidationError.forField` unconditionally prefixes `Field '<coordinate>': `, so a message body that
opened `input field 'FilmFilter.categoryRef': ` while the coordinate was the input field itself would
render that coordinate twice. Both sites therefore attach to the *consuming* coordinate and name the
slot in the lead: `Field 'Query.films': input field 'FilmFilter.categoryRef': ...` at an input field
and `Field 'Query.stock': argument 'orgRef': ...` at an argument. That is the drafted wording
unchanged, and it is also the right coordinate on this family's own terms, the landing being a fact of
the use site: one input field reached from two queries whose scope tables differ can land right at one
and wrong at the other, so attaching both rows to the input field would put two contradictory-looking
errors on one coordinate. Where the coordinate holds several branches the lead names the departing
table, which the plan asked for; that reads
`input field 'OrgHolderFilter.orgRef' on the 'diverged_ref_child' branch: `.

**The view carries `root_type_name`, `root_field_name` and `branches`.** The plan had the reader reach
the consuming coordinate through `intent_input_occurrence_path` for its domain gate. It carries them on
the view instead, which is `intent_node_id_decode_defect`'s own shape (that view carries
`root_type_name`, `root_field_name` and `root_argument_name` for exactly this reason) and which the
coordinate lead above needs anyway, so the alternative was one join spelled twice in one reader.
`branches` is the endpoint count for the coordinate and node type, counted over the endpoint population
rather than over the judged one: it is what tells the consumer whether naming a branch says anything,
and a count over the judged rows would fall to one on the coordinate where a sibling branch was
excluded, which is precisely where an author most needs the branch named.

**Two roster rows the plan did not name, both demanded by gates that fired.**
`MetaDeclarationGateTest`'s undeclared roster only shrinks, so both new relations owe a `meta_relation`
row *and* a `meta_grain` row, and a declared relation's whole comment must be its grain sentence plus
its example, both length-checked; the essays the undeclared siblings carry go in `rationale` instead.
And `SupertypeSignatureGateTest` counts `intent_reference_for_application` as a new reconstruction of
the `@referenceFor` subtype set, which it is: the union was previously spelled inside
`ReferenceForParticipantDefects`'s `WHERE` clause twice, where that gate could not see it, so naming
the resolution as a relation is what made the reconstruction visible. Its roster row says so.

## Tests

Both faults are accepted today, so the first commit of the implementation may pin today's outcome
(the walk classifying the truncated `Category` path `TranslatedFk` and the diverged landing
`DirectFk`, and `StoreDetections.violations()` empty for both) before the view exists, then flip the
assertions with it. One commit or two is the implementer's call.

Model tier, `graphitron-model`, a `NodeIdDecodeLandingDefectTest` beside `NodeIdDecodeDefectTest` in
`no.sikt.graphitron.model.intent`, asserting rows of the view over a fixture store the way that test
does:

- A path whose terminal hop arrives on a table other than the endpoint's draws one
  `PATH_STOPS_SHORT` row naming both tables; the same path with its last step added draws none.
- A path whose second step resolves to no hop draws no row here (the walk's unknown-key rejection
  owns it), which is the completeness gate.
- A landed position whose two columns disagree on `binding_type` draws one `LANDING_TYPE_DISAGREEMENT`
  row carrying the position, both column names and both types; the same landing with agreeing types
  draws none; a position landing nowhere (`local_column_name IS NULL`) draws none.
- A mislanded path whose wrongly matched column also disagrees on type draws the one
  `PATH_STOPS_SHORT` row and no type row.
- `SAME_TABLE` draws nothing; `DISCOVERED_KEY` draws only the type verdict; a sibling graph is
  refused nothing.
- The participant exclusion, pinned in both directions over one fixture, which is the boundary this
  item's narrowing rests on rather than a gap reported in prose. One slot carrying a `@referenceFor`
  naming branch A's participant, under a consumer whose branches are A and B: the A branch draws
  neither verdict even where its discovered key both stops short of the node's table and diverges in
  type, and the B branch, named by no application, still draws its type row. A second fixture pins the
  shared-table case: two participants binding one table, one of them named, and the single endpoint
  row draws nothing.
- `intent_reference_for_application` gets its own rows asserted beside the defect view, one case per
  shape the exclusion turns on: an application naming a participant of its consumer, one naming a
  participant no consumer offers (no row, which is the inertness rule), and an input type consumed by
  two queries with different participant sets, where the application has a row under one consumer and
  not the other. `ReferenceForParticipantDefects`'s own test is the other end of that relation and
  must pass unchanged when the reader moves onto it, which is what says the anti-join is the same
  predicate and not a new one.

Pipeline tier, `graphitron`, a `NodeIdLandingDefectsTest` beside `NodeIdDecodeDefectsTest` in
`no.sikt.graphitron.rewrite.derive`, running the reader over a real capture of sakila SDL and asserting
`violations()` messages in full, since the two texts above are this item's author-facing surface:

- A truncated `@reference` path refuses. Sakila only, no new DDL: `Category` over `category` reached
  from `film` by `[{key: "film_category_film_id_fkey"}]` is the coincidence twin (it lands nothing and
  emits correct SQL today), and from `inventory` by `[{key: "inventory_film_id_fkey"}]` is the
  uncompilable twin (`FILM` has no `CATEGORY_ID`). Both refuse after this item, and the pair is worth
  keeping precisely because their current outcomes differ so widely.
- A `@referenceFor` route refuses nothing, at both the input-field and the argument coordinate. The
  same truncated-path and diverged-landing shapes that refuse above, rewritten as
  `@referenceFor(type:)` applications under a polymorphic consumer, leave `violations()` empty for
  this reader. This is the narrowing's enforcer, not a control: it is what fails if a later change
  lets a verdict judge a route the endpoint family does not model, and it is the assertion
  `roadmap/store-reads-no-referencefor-step.md` inverts when it lands.
- A diverged landing refuses. The DDL already exists and nothing in the fixture build changes:
  `converter_org.org_code` is a primary key whose jOOQ type is `java.lang.String` (the
  `org_code_domain` `forcedType` carrying `OrgCodeStringConverter`), and `diverged_ref_child.org_code`
  is a plain `bigint` referencing it, so the foreign key's two ends diverge by construction. Declaring
  `type ConverterOrg @table(name: "converter_org") @node { id: ID! }` in the test SDL is the whole
  setup: `converter_org` carries no `NodeIdFixtureGenerator` metadata, so its key resolves off the
  primary key, exactly as `Category` over sakila's `category` does in
  `TranslatedFkTargetRailGatesPipelineTest` today. Then `@nodeId(typeName: "ConverterOrg")` on a
  `DivergedRefChild` filter discovers the foreign key, lands `org_code` (String) on `org_code` (Long),
  and reproduces the whole of the second fault with no new tables and no jOOQ regeneration.
- An unaffected control per fault: the correct two-hop `Category` path draws nothing, and
  `converter_campus` filtering by `ConverterOrg`, where both ends of the foreign key carry the
  converter and report `java.lang.String`, draws nothing. The second control is the one that proves
  the comparison is on the reported type and not on the converter's presence.
- A refusal at a coordinate outside the classification domain fails no build, as the sibling test
  pins for its family.

The walk-side unit test `NodeIdLeafResolverTest` gains nothing: its arm choice is unchanged by design,
and the agreement `FactCaptureAgreementTest` holds between `intent_node_id_decode_column` and the
walk's landings stays as it is, the landing relation being unchanged. It gains the two `Arm.DERIVED`
registrations and nothing else. In particular it does not gain a `@referenceFor` `@nodeId` agreement
case: that case fails today, on the disagreement
`roadmap/store-reads-no-referencefor-step.md` exists to repair, and adding it here would fail the
build on a fault this item does not fix. The case belongs with the repair, and that item's body says so.

No execution-tier work. Both checks only ever remove schemas from the accepted set, and the schemas
they remove are the ones that do not compile or are right by accident. The
`graphitron-sakila-example` schema declares `ConverterOrg` without `@node` and filters nothing by it,
so the full reactor build is the proof that no accepted schema in the tree moves.

## User documentation

The how-to `docs/manual/how-to/multi-hop-nodeid-filter.adoc` already has a "Where the chain is
refused" section listing the two refusals a chain can meet (a write or `@lookupKey` rail, a condition
step). The two new refusals join it, one paragraph each in the same register, placed before the
condition-step subsection. Draft:

> *A path that stops short of the node type's table.* The last step of the `@reference` path has to
> land on the table the `@nodeId` type is bound to, because a decoded id is a value of that table's
> key columns and binds against nothing else. A path that ends on an intermediate table is refused
> naming both tables; add the remaining step, or name the type the path already reaches.
>
> *A landing whose Java type disagrees.* Where a key position lands on a column of the filtered row,
> the two columns have to report the same Java type in the jOOQ catalog, since that is the pair the
> generated predicate compares. A converter on one end and not the other is the usual way they
> disagree. The refusal names both columns and both types and prescribes nothing, because the fix
> depends on which side is deliberately typed: align the two columns' catalog types, or route the
> reference through a path that lands the key on a column of its own type.

Neither paragraph needs a route caveat. The how-to is a `@reference`-path document throughout, and
its "Where the chain is refused" section is about the chain the author wrote there, so both new
refusals read in the register the page already has. A consumer on a per-participant route learns
nothing false from it; they learn nothing at all, which is what the follow-up item changes.

The `@nodeId` reference page's decoding table (`docs/manual/reference/directives/nodeId.adoc`) is
correct as it stands and gains nothing; its closing sentence already says a coordinate satisfying no
row is refused by name.

## Out of scope

**Auto-extending a truncated path when the missing hop is unique.** A truncated path is not an
incomplete spelling of one meaning, it is a different filter: graphitron cannot tell "the author forgot
a hop" from "the author meant to filter through the intermediate table". Extending it silently would
also keep the coincidence rows quiet, which is the case this item exists for. It contradicts two
positions the resolver already holds: that a stated route is checked rather than repaired, and that
auto-discovery stops at one hop with disambiguation left to the author.

**The bind asymmetry in `ConditionGlueRenderer.columnCompare`.** Its four branches disagree about
binding: single-column equality emits `DSL.val(value, alias.COL)`, so the value binds at a column's
`DataType` and its converter applies, while single-column membership and both row forms pass the bare
local. That is a live fault of its own, independent of validation and reachable on schemas this item
accepts: a converter-backed key column whose landing agrees in Java type still binds untyped in three
of the four branches, which is the shape the `converter_campus` fixture pins, and PostgreSQL answers a
`bigint` domain compared against a `varchar` bind with "operator does not exist" at runtime. Filed
separately as `roadmap/nodeid-row-predicate-binds-untyped.md`.

**The `@referenceFor` route.** The endpoint family does not model a per-participant path, so a slot
on that route reads `SAME_TABLE` or `DISCOVERED_KEY` in the store while the walk takes
`Route.ParticipantRoute`, and both verdicts exclude such a branch rather than judge it. Closing that
is a re-keying of the endpoint family onto the participant axis plus a step-target derivation over the
two `@referenceFor` step tables, together with the `FactCaptureAgreementTest` case that would have
caught the disagreement in the first place. Filed as
`roadmap/store-reads-no-referencefor-step.md`. Neither item blocks the other: this one's exclusion
goes vacuous when that one lands, and that one is a store-fidelity repair whether or not any verdict
reads it.

**Coercing a divergence rather than refusing it.** See below.

## Other solutions we've considered

**Coerce the type divergence instead of refusing it.** `ColumnComparison` is the tree's single minting
surface for comparing two catalog columns and, by its own javadoc, the one place in the generator that
reconciles a Java type disagreement between them. It does not refuse. It emits
`left.eq(right.coerce(left))`, with a companion bind rule that binds a value at the `DataType` of the
column it was read from. Its soundness argument is that a jOOQ `Converter` is a client-side mapping
only, so two ends of a foreign key are the same SQL type whatever their Java spelling; `coerce`
reinterprets a field's Java type and leaves the rendered SQL alone; and no value is ever parsed or
widened, because the bind runs the converter the catalog already declares. That argument reaches the
landing case too, once the terminal-table check above has established that a landing really is a
declared foreign key's two ends, and the row forms would coerce per cell. It also handles the
divergence a consumer *intends*, which the refusal cannot: for a code table typing its key differently
from the tables referencing it, refusing leaves no legal spelling of a filter that is semantically
fine, and the author's remedies are to change a catalog that is deliberately shaped or to drop the
filter.

The refusal wins on two points. The first is a correctness point about the emitter as it stands: the
coercion `ColumnComparison` mints is sound only together with its companion bind rule, and three of the
four branches of `ConditionGlueRenderer.columnCompare` (single-column membership and both row forms)
bind the bare local today, so for a landed key the coercion path does not yet exist and shipping a
coercion here would emit a comparison whose bind side the tree has not made sound. That is the fault
`roadmap/nodeid-row-predicate-binds-untyped.md` carries, and until it lands a coercion for this operand
pair cannot be proven against a database. The second is a sequencing point: a coercion makes the
catalog's self-disagreement invisible, and the author is the only party who can say whether it is
accidental or intended. A refusal can be relaxed into a coercion later, once the bind side is sound and
a fixture proves the coerced emission executes; a coercion shipped first means no consumer ever learns
their catalog diverges. The first consumer to hit an intended divergence with no alternative path is the
signal to revisit, and the two answers are compatible rather than rival: a relaxation would be a
verdict the defect view stops drawing and a comparison the emitter starts coercing, with nothing in
between to unbuild. A reviewer who disagrees with that ordering should say so at the `Spec → Ready`
gate, because it is the one fork in this item whose answer changes what gets built.

**Refuse in the walk, inside `NodeIdLeafResolver`.** This was the plan's first shape and it is
smaller on paper: `BuildContext.parsePath` and `parseExplicitPath` already compute a
`TerminalTargetVerdict` against the target table the resolver passes and the resolver reads only
`hasError()`, so the two explicit arms of `resolveFkJoinPath` could return `PathResolution.Refused` on
a `Mismatch`, and `landKeyColumns` could compare `ColumnRef.columnClass()` at the point a position
lands. It was set aside for one architectural reason and three costs the shape carries. The reason is
the strangler rule in `pipeline-overview.adoc`: the walk is the surface being drained, and every
`@nodeId` refusal added since the fact store gained the decode family has landed in the store rather
than in the resolver. The costs: `TerminalTargetVerdict.Mismatch` is named and worded for the output
projection that mints it, so this coordinate would be its third formatter over one record whose
`returnTableName` component would then mean "the node type's `@table`"; a check written into two of
the three `Route` arms is a convention with no enforcer, which the fourth arm the seal anticipates can
skip and nothing will notice, where the store's `navigation` is a closed column whose comment and
gates say which routes it accounts for, so a route the model does not reach is a stated absence a
population can read and a test can pin; and a `Refused` out of
`landKeyColumns` makes landing a partial function, breaking the one-row-per-position totality the
walk and `intent_node_id_decode_column` are pinned to agree on. None of the three is fatal, and the
walk-side variant remains the fallback if the store route hits a fact it cannot reach; nothing found
while reading suggests it will.

The middle cost is worth stating precisely, because it is the one place the walk-side variant is
currently ahead. On coverage the two are level, and the walk is in fact wider: it walks all three of its `Route` arms today, the store's hop relation
resolves two of them, and this item's population declines the third. So the argument is not breadth,
it is where the fourth route lands. In the walk it is another arm plus another copy of the check,
carried by convention, with the seal's exhaustiveness saying nothing about whether the check is in it.
In the store it is one derivation the fact model owes anyway, after which one predicate widens and
this item's boundary test inverts, both of them things that fail loudly if they are forgotten. The
other two costs are unaffected by any of this, and the totality one is the decisive one on its own:
it is a property the walk and the store are pinned to agree on, so a walk-side refusal breaks a gate
rather than adding one.

**A separate validator pass over the classified model.** Rejected for the same reason as the walk-side
refusal and one more: it would re-derive the route, the participant in scope and the per-position
landing from the classified carriers, which is the store's landing relation computed a third time. It
does hold one thing the store does not, and naming it is the honest form of the rejection: the
carriers hold whatever the walk resolved, participant route included, so this variant would cover the
route this item declines. That is an argument for closing the store's gap, which is filed, and not for
computing the landing a third time on a surface the strangler rule is draining.

## Provenance

A consumer subgraph of roughly 60k lines of SDL over an Oracle catalog, migrating to graphitron 10, got
generated Java that does not compile out of a schema `graphitron:generate` accepted with zero errors.
Both faults above, in one schema. Five sites wrote a `@nodeId(typeName:)` filter whose path stopped one
foreign key short of the node's table, where two of the node's three key columns exist on the terminal
table with an unrelated meaning and the third does not exist at all, so the remote predicate rendered a
column the terminal table does not have. One site landed all nine positions of a composite key by name,
where one position's column carries a converter on the node's own table and not on either consuming
table, emitting a `Row9<..., Short>` compared against a `List<Row9<..., Integer>>`. Six further sites
in the same schema land a wrong terminal table whose columns agree with the node's key in both name and
type, because they are the child columns of that table's own foreign key to the node: those compile and
produce correct SQL, and they are why this item is about verification rather than about a codegen
crash.

Which route each of those twelve sites wrote is not recorded, and it matters for the ordering, so the
assumption is stated rather than left implied: they are read here as `@reference` paths and
auto-discovered keys, which is what the shapes reported support (a path written one foreign key short
of the node's table is a written chain, and the nine-position landing is a discovered key's two ends).
On that reading this item covers the reported schema whole. If a re-check of the consumer's subgraph
finds a per-participant `@referenceFor` route among them, the reading is wrong for those sites and
`roadmap/store-reads-no-referencefor-step.md` outranks this item rather than following it. Whoever
picks this up should ask, because it is one question to the reporting consumer and it decides which of
the two lands first.

## Reviewer findings

### Round 1 (2026-09-07, Spec -> Ready, reviewer session 01Cq156uGUZ1uZy5zaknuRo8)

Verdict: withhold. One finding, on question one with a question-two consequence. The goal reads
without reconstruction: after this lands, a `@nodeId(typeName:)` filter whose path stops one table
short of the node type's table, or whose decoded key lands on a same-named column of a different
Java type, is a `graphitron:generate` refusal naming both tables or both columns and types, where
today it is uncompilable generated Java or SQL that is right by accident. The store placement is the
right shape under the strangler rule, and every symbol, relation and fixture the spec names exists
as named, with one exception below. The exception is a claim about facts the store holds that the
plan's participant-route coverage stands on, and the tree does not hold them.

**Finding 1 (question one: the `@referenceFor` route does not reach the hop relation; question
two: the implementer would have to design the derivation that does).**

"The facts are already there" and "a participant route reaches the hop relation through the same
reference-target views" are both false for the `@referenceFor` route today. `@referenceFor` steps
are captured, by `GraphitronFactCapture` through `FactWrites`, into `graphitron_reference_for_step`
at the field coordinate and `graphitron_argument_reference_for_step` at the argument coordinate.
No view in `graphitron-model.sql` reads either table. `intent_node_id_instruction_live` computes
`carries_reference_path` from `graphitron_field_reference_step` and
`graphitron_argument_reference_step` alone, and `intent_node_id_decode_hop` joins
`intent_argument_reference_step_target` and `intent_input_field_reference_step_target`, both built
over those same `@reference` step tables. A `@nodeId` slot whose stated route is a `@referenceFor`
is therefore `SAME_TABLE` or `DISCOVERED_KEY` in the store, and its hops, where it has any, are the
one foreign key auto-discovery would find, not the path the author wrote and the walk's
`Route.ParticipantRoute` arm follows. `FactCaptureAgreementTest` pins no `@referenceFor` `@nodeId`
case, so the store and the walk disagree on this route today with nothing failing.

Three parts of the plan inherit that:

- `PATH_STOPS_SHORT` is gated on `navigation = 'AUTHORED_PATH'`, so it cannot fire for a
  `@referenceFor` path. The pipeline-tier bullet "The same refusal on the `@referenceFor` route, at
  both the input-field and the argument coordinate" cannot pass as specified.
- `LANDING_TYPE_DISAGREEMENT` over a participant route judges whatever landing `DISCOVERED_KEY`
  computes for that branch, which is not the landing the generator emits for it. It can miss the
  fault (authored path diverges, discovered key does not) and it can refuse a sound schema
  (discovered key diverges, authored path does not).
- The case for the store over the walk includes "a store predicate over the hop relation covers
  every navigation at once". Today it covers two of the walk's three routes, and the walk-side
  alternative's cost "a check written into two of the three `Route` arms" is a cost the store
  route shares until the decode family models the third arm.

What satisfies it is the author's fork, and the spec has to say which arm it takes:

- Extend the decode family so a participant route is an `AUTHORED_PATH` whose hops come from the
  `@referenceFor` step tables, selected per branch by `participant_type_ref` against the branch's
  type, with `FactCaptureAgreementTest` pinning the walk against the extended landing relation.
  The plan's claims then hold as written, and "The facts are already there" becomes a section
  that says which fact is new (a derived one; the captured steps do exist).
- Or narrow this item to the `@reference` and discovered-key navigations: drop the `@referenceFor`
  test bullet, state that a participant route is not judged until the store models it, and file
  the store's missing `@referenceFor` navigation as its own Backlog item, since the walk-store
  disagreement exists whether or not this item lands.

> *Author response, revision 1.* Confirmed against the tree on every mechanical claim before revising:
> `reference_for_step` occurs in `graphitron-model.sql` only in its two `CREATE TABLE` blocks and their
> comments, `carries_reference_path` is three `EXISTS` clauses over the `@reference` step tables,
> `intent_node_id_decode_hop`'s two reference-target joins have a lineage that bottoms out in those
> same tables, and `FactCaptureAgreementTest` contains no occurrence of `referenceFor` at all. Taken on
> the second arm, narrowing. What changed, by section. The **Goal** now closes with a paragraph naming
> the three navigations, saying which two are judged, and qualifying the four-row table as being about
> those two; the goal is what the gate restates, so the scope belongs there and not only in the plan.
> **The facts, and the one that is missing** (renamed from "The facts are already there") states the
> missing fact as a derived one, says which route the store cannot see and why the repair is a re-keying
> onto the participant axis rather than a fourth vocabulary value, and stops short of that repair.
> **One defect view, two verdicts** gains "The judged endpoints, stated once": one CTE both `UNION ALL`
> arms drive off, excluding an endpoint whose navigation is auto-discovery's while a per-participant
> route applies at that branch. That predicate is deliberately not phrased as "the store does not model
> this yet", which would rot; phrased on the contradiction it is permanently true and goes vacuous by
> construction when the follow-up lands, so there is no arm to unbuild and no second decision to make.
> The exclusion sits on the view rather than the reader's domain gate because these rows are not
> true-but-unemitted: an editor arm reading them ungated would diagnose a route the schema does not
> take. **What the walk keeps doing** gains a fourth consequence and a boundary clause on the rails
> bullet, a participant-route mislanding still reaching a write rail unjudged. **Tests** drops the
> `@referenceFor` refusal bullet and replaces it with its inverse at both coordinates as the narrowing's
> enforcer, adds the model-tier exclusion fixture in both directions (including the shared-table case),
> and says explicitly that `FactCaptureAgreementTest` must *not* gain a `@referenceFor` `@nodeId` case
> here, since that case fails today. **Out of scope** carries the route as its own entry. The Backlog
> item is `roadmap/store-reads-no-referencefor-step.md`, and its body carries the participant-axis
> re-keying so the follow-up does not discover it late.
>
> One thing the arm did not anticipate, from consulting `principles-architect` on the derivation shape.
> The exclusion's resolution ("does a per-participant route apply at this branch") is join for join what
> `ReferenceForParticipantDefects` already spells twice as a `notExists`, so writing it into the new
> view's population would be its second spelling, which the fact model's "a derivation gets a relation
> as soon as a second reader asks it" rule forbids. The item therefore also adds
> `intent_reference_for_application` and moves that reader onto it as an anti-join, retiring a spelling
> rather than adding one; it is the relation the follow-up item needs positively, as the head of the
> `AUTHORED_PATH` it derives. That makes the item two relations plus one reader plus one retired
> spelling, which the Implementation preamble now says. **Provenance** also gained a paragraph the
> finding did not ask for and the narrowing makes load-bearing: the reported sites' routes are not
> recorded, they are read here as `@reference` and discovered-key, and if that reading is wrong the
> Backlog item outranks this one.

Non-blocking. The spec says the coordinate spelling is the sibling's, with `input field
'Type.field'` at an input field. `NodeIdDecodeDefects` reads an `ARGUMENT`-only view and spells
`argument 'x'` only, and `ReferenceForParticipantDefects` spells an input-field coordinate as bare
`Type.field`. The input-field lead the messages open with is minted by this item, not copied; noted
because the spec states both messages in full for review.

> *Author response, revision 1.* Taken, and answered by putting the lead where the next family finds
> it rather than by rewording it. Confirmed both halves: `intent_node_id_decode_defect`'s population is
> `s.site = 'ARGUMENT'` and its view comment says why the input-field site draws no row, so
> `NodeIdDecodeDefects.lead` spells `argument 'x'` and could spell nothing else; and
> `ReferenceForParticipantDefects` carries `Type.field` on the `Defect` for
> `ValidationError.forField` while its message text opens with `@referenceFor names participant ...`
> and no lead at all. This item's view has both sites, so the lead is minted here, and "The reader"
> now says so and puts it in `NodeIdMessages` beside `nodeIdSpelling`, which is the home the two
> families already share for exactly this reason. The same paragraph routes both messages through
> `nodeIdSpelling` and `simpleName`, which is why the type-disagreement draft now reads `String` and
> `Long` rather than the qualified pair; the trade `simpleName` takes (two types differing only in
> package read as one name) is stated where the message is drafted rather than discovered by whoever
> implements it.
