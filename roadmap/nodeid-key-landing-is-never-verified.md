---
id: R926
title: "The @nodeId key landing is asserted, never verified"
status: Spec
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

## Implementation

Both refusals are predicates over facts the fact store already holds, so they land as one derived
defect view in `graphitron-model` and one reader beside the two `@nodeId` detections that already
report from the store, and the classification walk is not touched. That placement is not a
preference. `docs/architecture/explanation/pipeline-overview.adoc` names the walk (`GraphitronSchemaBuilder`
and the leaf resolvers under it, `NodeIdLeafResolver` included) as the transitional surface of the
strangler migration, "a surface being drained, not a place to extend", and states the rule for the
window: new facts land only in the store. The two `@nodeId` refusals that shipped most recently,
`NodeIdDecodeDefects` over `intent_node_id_decode_defect` and `ReferenceForParticipantDefects`, are
both store readers "gated on nothing of the walk's" (`GraphQLRewriteGenerator.captureAndRead`), and the
second of them already judges the `@referenceFor` route on a `@nodeId` filter input field, which is one
of the two routes this item has to cover. Everything below was read at `faadfb7`; re-grep the symbols at
pickup, the file moves.

### The facts are already there

The decode family under `intent_node_id_decode_endpoint` states, for every slot carrying a decoding
`@nodeId` and every use site and branch of it:

- `intent_node_id_decode_endpoint`: the table the slot's own predicate binds on (`from_*`), the table
  the named node type's keys live on (`to_*`, "where a decode has to arrive"), and `navigation`, a
  closed vocabulary of three: `SAME_TABLE`, `AUTHORED_PATH`, `DISCOVERED_KEY`.
- `intent_node_id_decode_hop`: one row per foreign-key hop the decode traverses, in authored order, each
  with the table it arrives at (`to_*`) and `last_position`, so the terminal hop is the row whose
  `position` equals it. On `AUTHORED_PATH` the hops come from `intent_argument_reference_step_target`
  or `intent_input_field_reference_step_target` by site; on `DISCOVERED_KEY` the one hop is the single
  foreign key from the departing table to `to_*`, so it arrives there by construction.
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

### One defect view, two verdicts

Add `intent_node_id_decode_landing_defect`, an `intent_` view beside `intent_node_id_decode_defect`
and shaped like it: one row per refused instruction, use site and branch, a closed `verdict`
vocabulary, typed witness columns, source location, and no message column, the prose belonging with
the consumer that composes it. Its two verdicts:

- `PATH_STOPS_SHORT`. Population: endpoints with `navigation = 'AUTHORED_PATH'`, joined to the hop at
  `position = last_position`, where the hop's `(to_source_name, to_schema, to_table)` differs from the
  endpoint's. Gated on the chain being whole: the number of hops (`last_position + 1`) equals the
  number of steps the author wrote, read off `graphitron_argument_reference_step` at the argument site
  and `graphitron_field_reference_step` at the input-field site. A chain that stalled (a step that
  resolved to no hop, or to several) is already the walk's unknown-key or ambiguous-key rejection at
  the same coordinate, and a second row here would be a second answer to one fault. Witnesses: the
  terminal hop's table and the endpoint's table, each as its `sql_table` key.
- `LANDING_TYPE_DISAGREEMENT`. Population: column rows with `local_column_name IS NOT NULL` on
  endpoints whose `navigation <> 'SAME_TABLE'`, joined to `sql_column` once on the origin table and
  `local_column_name` and once on the endpoint's `to_*` table and `key_column_name`, where the two
  `binding_type` values differ. Both joins are on the column name with the case folded, using the
  generated `column_name_upper`, since `key_column_name` is spelled as the winning key tier spells it
  and `local_column_name` as the catalog does. Excluded where the same endpoint draws a
  `PATH_STOPS_SHORT` row: on a mislanded path the "key column" the lift matched by name on the wrong
  table is not the node's key at all, and the one fault should draw one row. Witnesses: `position`,
  both column names, both binding types, both tables.

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
view, not a materialization: nothing on the build path reads it more than once per graph. Register it
where the schema gates demand a new relation be declared (the `meta_relation` roster row with its
owner, and the `Arm.DERIVED` registration in `FactCaptureAgreementTest`); the gates fail the build
until both are in, so there is nothing to remember.

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
can fail a build; the view itself stays ungated so an editor arm can read it later.

The reader joins `StoreDetections` as one more component with its `empty()` arm, and
`FactCapture.detect` calls it beside the other four under `ClassifiedRun.Present`. `violations()`
appends it in declaration order. Both rejections are `Rejection.structural`, as the sibling's are:
neither names a closed set an editor could offer alternatives from.

The two messages, stated in full so the wording is reviewed before it is emitted. The coordinate
spelling is the sibling's (`argument 'x'` at an argument, `input field 'Type.field'` at an input
field), and the branch is named only where the endpoint population holds several.

> input field 'FilmFilter.categoryRef': `@nodeId(typeName: "Category")` reaches its target through an
> `@reference` path whose last step lands on table 'film_category', not on Category's `@table`
> 'category'. A decoded Category id binds against columns of 'category', so the path has to end there:
> add the remaining step, or name the type the path already reaches.

> input field 'DivergedRefChildFilter.orgRef': `@nodeId(typeName: "ConverterOrg")` decodes key column
> 'org_code' of table 'converter_org', which jOOQ binds as java.lang.String, and the reference path
> lands it on column 'org_code' of table 'diverged_ref_child', which jOOQ binds as java.lang.Long. The
> two columns disagree on Java type, so no predicate can bind one against the other. Either align the
> two columns' catalog types, or route the reference through a path that lands the key on a column of
> its own type.

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

- Every rail is covered at once. The write and `@lookupKey` rails refuse a `FilterBinding.Remote`
  carrier through `FilterBinding.remoteBindingUnsupported`, which catches only the mislandings where
  no position landed; a mislanded or diverged landing that lands every position classifies `DirectFk`
  and reaches INSERT, UPDATE, DELETE and the lookup today with nothing examining it. A detection keyed
  on the coordinate never passes through a carrier, so there is no rail for it to miss.
- `@condition(override: true)` needs no new branch. The resolver's rule is that only an undiscovered
  route escapes into the author-owned predicate and a stated route that failed stays a rejection,
  because naming a foreign key asks the build to check it. The detection fires on the authored path
  whatever the leaf's `@condition` says, which is that rule.
- `DISCOVERED_KEY` draws no `PATH_STOPS_SHORT` row by construction and can draw a
  `LANDING_TYPE_DISAGREEMENT` row, which is exactly the diverged-fixture case below: nothing was
  written, the one foreign key was found, and its two ends disagree.

No edit-time surface follows yet. `graphitron-lsp` validates a `@nodeId(typeName:)` leaf only for the
named type existing and carrying `@node` (`Diagnostics`, the `@nodeId` arm), and no reader in that
module consumes `intent_node_id_decode_defect` today either. The new view is shaped for that reader
(ungated rows, source location on the row) so an editor diagnostic is a query away when a consumer
asks for it; that is a separate item.

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

Pipeline tier, `graphitron`, a `NodeIdLandingDefectsTest` beside `NodeIdDecodeDefectsTest` in
`no.sikt.graphitron.rewrite.derive`, running the reader over a real capture of sakila SDL and asserting
`violations()` messages in full, since the two texts above are this item's author-facing surface:

- A truncated `@reference` path refuses. Sakila only, no new DDL: `Category` over `category` reached
  from `film` by `[{key: "film_category_film_id_fkey"}]` is the coincidence twin (it lands nothing and
  emits correct SQL today), and from `inventory` by `[{key: "inventory_film_id_fkey"}]` is the
  uncompilable twin (`FILM` has no `CATEGORY_ID`). Both refuse after this item, and the pair is worth
  keeping precisely because their current outcomes differ so widely.
- The same refusal on the `@referenceFor` route, at both the input-field and the argument coordinate,
  since a participant route reaches the hop relation through the same reference-target views.
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
walk's landings stays as it is, the landing relation being unchanged.

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
the three `Route` arms is a convention the fourth arm the seal anticipates can skip, where a store
predicate over the hop relation covers every navigation at once; and a `Refused` out of
`landKeyColumns` makes landing a partial function, breaking the one-row-per-position totality the
walk and `intent_node_id_decode_column` are pinned to agree on. None of the three is fatal, and the
walk-side variant remains the fallback if the store route hits a fact it cannot reach; nothing found
while reading suggests it will.

**A separate validator pass over the classified model.** Rejected for the same reason as the walk-side
refusal and one more: it would re-derive the route, the participant in scope and the per-position
landing from the classified carriers, which is the store's landing relation computed a third time.

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
