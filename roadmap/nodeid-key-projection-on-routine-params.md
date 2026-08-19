---
id: R668
title: "Decode @nodeId leaves bound to @routine parameters via argMapping key-column projection"
status: In Progress
bucket: feature
priority: 3
theme: routine
depends-on: []
created: 2026-08-14
last-updated: 2026-08-19
---

# Decode @nodeId leaves bound to @routine parameters via argMapping key-column projection

A `@nodeId` field carries a base64-encoded node identity on the wire, not the primary key it
encodes. Graphitron already knows how to turn that wire form back into typed key values: the
decode side ships for lookups and filters, where an argument or input field annotated
`@nodeId(typeName: T)` is decoded into `T`'s key columns and fed to an `IN` / `VALUES` join.
That decode is not wired into the `@routine` parameter binding. `argMapping` hands a routine
IN parameter the *raw* value at the path it names, so a `@nodeId`-carrying input field delivers
the base64 string.

The concrete case, an access-control mutation whose routine takes the organisation's integer
key:

```graphql
input OpprettFeideApplikasjonInput {
  navn: String!
  organisasjonId: ID! @nodeId(typeName: "Organisasjon")   # encodes organisasjon.organisasjonskode (INTEGER)
  serviceId: String!
  beskrivelse: String
}

type Mutation {
  opprettFeideApplikasjon(input: OpprettFeideApplikasjonInput!): OpprettFeideApplikasjonPayload
    @routine(
      name:       "opprett_feide_applikasjon"   # (p_navn TEXT, p_organisasjonskode INTEGER, p_service_id TEXT, p_beskrivelse TEXT)
      argMapping: "pNavn: input.navn, pOrganisasjonskode: input.organisasjonId, pServiceId: input.serviceId, pBeskrivelse: input.beskrivelse"
    )
}
```

This item makes the node type's key columns nameable as a trailing path segment, so the binding
reads:

```
argMapping: "..., pOrganisasjonskode: input.organisasjonId.organisasjonskode, ..."
```

`organisasjonskode` is not a field of any SDL type. It is a *key column of the node type the
`@nodeId` names*, and the segment means "decode this node id and project that column out of the
decoded key tuple".

## What happens today

Three outcomes, measured against the sakila test catalog by classifying a `@nodeId` input field
into `rent_film(p_inventory_id INTEGER, ...)` and into `create_secure_note(p_owner TEXT, ...)`.
None of them is the one the author wants, and the worst of them is silent.

* **`ID` into an `INTEGER` parameter: a rejection that never says `@nodeId`.**
  `pInventoryId: input.inventoryId` resolves the leaf to the `ID` scalar.
  `RoutineDirectiveResolver.leafTypeGate` runs the shared coercion gate
  (`ServiceCatalog.argExtraction` → `WireCoercionResolver.checkScalar`), which compares `ID`'s
  graphql-java coercion output against the parameter's Java type and rejects with
  `Assignability[sdlLeafType=ID!, coercionOutputType=java.lang.String,
  declaredType=java.lang.Integer, site=@routine parameter 'pInventoryId']`. The message reads as
  a type mistake, not as a missing decode.
* **`ID` into a `TEXT` parameter: no rejection at all, and the wrong value ships.** The same
  binding against `create_secure_note`'s `p_owner TEXT` classifies clean, as
  `ArgBinding[routineParamName=pOwner, paramType=java.lang.String, source=Arg[extraction=Direct[],
  path=…owner]]`. The base64 node id goes to the database verbatim. This is the sharper half of
  the problem: silently wrong data rather than a build failure.
* **The proposed segment is rejected by the shared path resolver.**
  `pInventoryId: input.inventoryId.inventory_id` never reaches the routine resolver;
  `ArgBindingMap.of` rejects it first with `@routine argMapping entry 'pInventoryId:
  input.inventoryId.inventory_id' walks through scalar 'ID' at segment 'inventoryId'; only
  input-object types may be traversed`. The traversal rule is right for every path segment that
  exists today; the proposed segment is a new kind of segment it has no concept of.

## Design

### The rule: a dot opens the thing at that position

The grammar does not gain a second form. It gains a second *openable thing*, under a rule the
existing form was already a case of:

> A dot opens the thing at that position. What it opens depends on what the thing is.

An input object opens into its fields. A node id opens into the key columns of the type it
refers to. Nothing else opens, and a segment on something that does not open is the same
rejection it is today, restated at this grain: "this thing has nothing to open" rather than
"only input-object types may be traversed". So the rejection stays permanent rather than
becoming conditional on a lookup, which is the property that matters about it.

This is worth stating in the item because the alternative reading, that the dot separator now
carries two vocabularies (GraphQL field names and SQL column names) sharing one separator, is
the reading that makes the form look expensive. It is the wrong reading. There is one
vocabulary, "what can I open here", and the answer has always depended on the thing being
opened; today only one kind of thing opens, so the dependence has never been visible. The rule
also extends: whatever the next openable thing is, it slots in without a new separator, and
`roadmap/nested-argmapping-syntax.md` (R249) composes with the rule rather than negotiating
against it.

The LSP inherits this directly, and favourably. Completion after a dot asks one question, "what
does the thing at this position open into", and answers it per kind: input-object fields, or the
node type's key columns. That is a uniform trigger rather than a special case, and the node-id
arm is answerable *today* without the nested-input-field projection the general arm is waiting
on (see "Relationship to other items").

A lexically disjoint form was considered and rejected: a sigil, or a call form such as
`key(input.organisasjonId, organisasjonskode)`. It was proposed in order to keep the two
vocabularies separable at the lexer, and it is unnecessary once the rule above is stated, because
there are not two vocabularies to separate. It would also cost the property it was meant to buy:
a separate form gives the LSP a second trigger to implement and an author a second syntax to
learn, for the thing the dot already means.

### What the key-column segment names, and why `typeName:` must be explicit

The segment names a column of the referenced type's **node key**: what
`@node(keyColumns: [...])` declares when the author pinned it, and the catalog's key metadata
otherwise. The authority is the `@node` declaration on the type the `@nodeId` refers to, which is
the same place an author already looks to know what a node id encodes.

"Otherwise" hides two distinct sources rather than one, and the resolution has three tiers rather
than two: the reconciled `NodeType`, the table's own generator metadata, then `@node` plus the
catalog primary key. `graphitron_node_key_column (graph_name, type_name, position, column_ref)`
holds the pinned list in written order; the primary-key fallback is `sql_primary_key` joined
through `sql_constraint_column` for the ordered columns, reached from the node type through
`intent_resolved_type_binding`; and the middle tier is `sql_node_metadata` with its ordered
`sql_node_key_column` entries, which R710 has shipped. The reconciliation is a view
and it is the item's spine (see "The resolution is a view over facts").

Two things about that middle tier follow from R710 recording the metadata **as stated** rather than
as validated, and neither is a detail. Its rows carry a form vocabulary
(`type_id_form` in `STRING` / `NULL` / `OTHER` / `ABSENT`, `key_columns_form` in `FIELD_ARRAY` and the
same three), a row exists whenever the class declares either constant, and a key-column entry's
`column_name` may name
a column the table does not have. So this tier reads *well-formed* rows, which is an anti-join
against R710's own well-formedness derivation, `intent_node_metadata_defect`, and not a direct read
of the base relation. Take the conjunction that view's comment insists on rather than the anti-join
alone: well-formed means a `sql_node_metadata` row with zero defect rows, since no defect rows is
also what a table publishing no metadata at all has. A malformed
constant is a fact the tier passes over rather than an absence, which is a distinction the tier's
comment owes a sentence. Reading the raw relation instead would let a malformed key-column list
project a column that does not exist, which is this item's own failure mode arriving through the
back door.

The spelling is the SQL column name, because that is what `@node(keyColumns:)` itself is a list of,
and matching is case-insensitive. The behaviour is inherited rather than introduced, but the
mechanism named here is stale and has to be re-decided: `intent_spelled_table` and
`intent_column_match_claim` no longer compare under `UPPER()`, the grammar-normalisation item having
moved both onto stored `_upper` companion columns the database generates. The rule it settled is that
a fold is minted where an authored spelling meets a catalog name, and a `@node(keyColumns:)` entry
matched against a catalog column is exactly that crossing, so this item's comparisons want folded
columns rather than per-row `UPPER` calls. `intent_resolved_node_key_projection` shipped with two
such calls against `graphitron_argument_path_segment.segment_name`; whether they become a fold on
that column or go another way is this item's to settle before its own gate.
`JooqCatalog.resolveColumn` still uses `equalsIgnoreCase`, so the docs can state the behaviour
without announcing a rule. *Settled in stage 3: one fold, on the authored side, the key-column side
folded at the crossing because no view here exposes a fold and this reduction hands out a spelling.
See that stage's note, and R731 for whether it should.* On the middle tier
take the store's own twin rather than re-deriving it: `intent_node_metadata_defect`'s
`KEY_COLUMN_UNRESOLVED` arm resolves a stated entry against `sql_column` under
`UPPER(jooq_name) = UPPER(...) OR UPPER(column_name) = UPPER(...)`, two tiers of spelling and not
just the SQL one, which is `findColumn`'s rule. The tier's own resolution has to agree with the arm
that decides the row is well-formed, so it is the same predicate or the two can disagree about which
entries resolved.

`@nodeId` without `typeName:` is rejected at this position. `NodeIdLeafResolver.inferTypeName`
infers a bare `@nodeId`'s target from the *containing table*, and a routine parameter has no
containing table; there is nothing to infer from. The message to converge with is
`InputBeanResolver`'s, which states this same condition for a jOOQ-record-typed input-bean member
("@nodeId on a jOOQ-record-typed member must specify typeName: explicitly, the record type alone
does not name the NodeType to decode against"), and not `NodeIdLeafResolver`'s pair: those two say
*zero candidates* and *ambiguous candidates*, which are different facts that happen to share a
remedy. The projection's rejection is derived elsewhere (see "The bare form becomes a rejection"),
so what carries over is the wording, not the site.

### The resolution is a view over facts, one of which needs capturing first

Most of what this projection needs is already captured, and one thing is not. The SDL side is
complete: `graphitron_routine_arg_mapping_pair.argument_path` (the right side as written, keyed at
the application's coordinate), `graphitron_field_node_id (type_name, field_name, node_type_ref)`
for the `@nodeId` on an input field and `graphitron_argument_node_id` for the one on an argument,
`graphitron_node` and `graphitron_node_key_column` for the node identity and its pinned key list,
and `sql_table` / `sql_constraint` / `sql_constraint_column` / `sql_primary_key` through
`intent_resolved_type_binding` for the catalog fallback. The catalog side has a gap, covered two
sections down.

**Read the binding through the resolution, not through `intent_bound_table`.** R704's slice 9 split
the question: `intent_bound_table` is now the `@table` population specifically, the routine return
binding is its own relation beside it, and `intent_resolved_type_binding` is where the two meet for
the reader whose question is "which table stands for this type". Five readers were repointed onto it
when it landed and two deliberately were not, each named in its comment, so a new reader asking the
what-table question and reaching for the `@table` population alone would be the sixth reader on the
wrong side of a rule that has just been drawn. It carries `candidates`, and there is no precedence:
a type whose `@table` and whose routine return name different tables is two rows and the relation
declines to pick. This item's tiers therefore need a stance on arity above one, which is a silence
rather than a guess (see the key-column view below).

**The dotted path is already walked, and this is the finding that removes the item's biggest
unknown.** `intent_input_occurrence_path` is a capture-cadence derivation whose rows are exactly
this: an argument whose named type is an input object, or a nested input field reached from one,
keyed by the serialized path `<root type>.<root field>(<argument>)[/<input field>...]`, with every
prefix present as its own row and an `intent_input_occurrence_path_step` child carrying the same
data relationally "so no consumer parses the key". It exists because cyclic input nesting has no
safe recursive H2 view form, which is the question this item would otherwise have had to answer for
itself.

So the binding-leaf resolution is a **keying over that relation, not a second walk**, which is the
move the stratum's own block comment records for `intent_bound_table` over `intent_spelled_table`
("the binding view is a keying over it rather than a second copy of it").

**And the written path arrives already decomposed, which makes the keying a join rather than string
surgery.** `graphitron_argument_path_segment (graph_name, type_name, field_name, argument_path,
position, segment_name)` holds one row per segment of one path in written order, position 0 being the
head, dense from zero, and capture writes it at all seven pair sites; every one of the seven reaches
its own decode by joining on `(graph_name, type_name, field_name, argument_path)`, the coordinate all
seven lead with. Against it, `intent_input_occurrence_path` needs no key parsing either: it carries
`root_type_name`, `root_field_name`, `root_argument_name`, `leaf_named_type` and `depth` as columns.
So the argument-rooted keying is: the pair row's coordinate to the occurrence row's root coordinate,
segment position 0's `segment_name` to `root_argument_name`, each further segment position *i* to
`intent_input_occurrence_path_step.ordinal = i` with `segment_name` matched to the step's
`field_name`, and the unconsumed count is the segment count against the occurrence row's `depth`. The
serialized path is never constructed and never split.

**Do not compute this with `POSITION` / `SUBSTRING` over the serialized keys.** The segment relation
exists so that no reader has to: its capture site records the parse's own segment list because
"recording it costs nothing and is the only chance the store gets, since no reader may split a
string", and its `segment_name` comment hands this item its own question outright ("which of those a
segment resolves to is a question for the derived stratum, and this relation only says what was
written"). Writing a second decomposition instead is the drift the fact model names: two spellings of
one resolution that agree until one of them changes.

One caveat the arms below inherit from that comment. It describes position 0 as "naming an argument
of the field the directive sits on", which is the output-field reading; at the input-field
`@condition` site the head names an input field of the type the pair row sits on. The relation
records what was written either way, so discriminating the two is the arm's business and not the
relation's.

**The head is not always an argument, so the keying has four arms rather than one.** Each is
narrow, and each absence below is a stated one rather than a silent one:

* **Argument-rooted dotted head.** The main arm, and the right-trim onto the prefix row described
  above. `InputOccurrencePaths.seed` seeds strictly from `graphql_argument`, so this arm covers
  every site whose head names a field argument.
* **Bare head, no dots.** The leaf *is* the head slot, so no occurrence path is involved: an
  argument head reads `graphitron_argument_node_id`, an input-field head reads
  `graphitron_field_node_id`. Cheap, and the most load-bearing arm of the four, because a bare
  `@nodeId` head is exactly the silent-`TEXT` shape this item exists to close.
* **Input-field-rooted dotted head.** `graphitron_field_condition` is a shared coordinate,
  "@condition on a field or input field (shared coordinate; the parent kind decides which SDL site
  this was)", and `SdlFactCapture.captureInputFields` routes an input-object field's directives
  through the same `captureFieldDirectives` as an output field's. So a `@condition` on an input
  field produces pair rows whose `type_name` is an *input* type and whose head names that input
  field rather than an argument; `BuildContext.buildInputFieldCondition` seeds `ArgBindingMap.of`
  with the input field's own name for exactly that reason. Two consequences. The arm is
  discriminated by a join to `graphql_type.kind = 'INPUT_OBJECT'`, which is how the capture side
  already tells the two halves apart ("the owning type's kind is a join away, so the SDL location
  kind of an application on one falls out of a join rather than a second table"). And the keying
  starts **partway down** the occurrence path rather than at its root, because that is where the
  written path starts: one row per use site that reaches the input type, so this arm is
  one-to-many where the others are one-to-one, and an input type no argument reaches yields no rows
  at all. That last absence is the arm's own caveat and owes a sentence in the view's comment.
  Where the argument-rooted arm anchors segment position 0 on `root_argument_name`, this one anchors
  it on a step whose `container_type_name` is the pair row's own `type_name` and whose `field_name`
  is that segment, then walks the remaining segments at consecutive ordinals from there. Anchoring on
  the container is what makes it a join rather than a name coincidence: matching the field name alone
  would accept a path ending in a field of that name whatever input type owns it. The step child
  exists for exactly this ("the step child carries the same data relationally so no consumer parses
  the key"), and the use-keyed rejection below depends on the arm naming the right consuming
  coordinate.
* **Path-step heads resolve no leaf, by construction.** The three step-site pair relations (two
  `*_reference_step_*`, one `*_reference_for_step_*`) carry rows, but `BuildContext` resolves a
  path-step
  `@condition` against an *empty* slot map ("no GraphQL arguments are in scope at a path-step
  @condition"), so no head at that site resolves today at all. Those arms belong in the union for
  the `site` vocabulary and for the deferred arm, and they resolve nothing. Saying so is what keeps
  their emptiness a recorded fact rather than a suspected bug in the view.

Two further caveats the view's comment must own:

* The expansion stops at a type already visited on the path (the classification walk's own
  first-visit guard, restated), so a cyclic re-entry has no row. That absence is load-bearing and
  owes a sentence.
* The trailing-segment count is a **column, not a flag**. One unconsumed segment is this item; two
  is a typo or R249's nested form, and the rejection messages must tell them apart. The stratum
  already states arity as a column rather than leaving each reader to count (`intent_spelled_table.
  candidates`). It is arithmetic rather than parsing: the segment rows' own count against the
  occurrence row's `depth`.

**The arms are a fork plus a basis, and that shape is already shipped next door.**
*Superseded in delivery. This subsection and the two after it prescribe a per-path grain with a
`disposition`/`basis` fork; what shipped is a per-segment grain where absence at a position carries
the same information without a vocabulary, and the fork is gone. The reasoning is in the stage-2
note under "Delivery", and it is the note rather than this text that a stage-3 implementer should
read. Left in place because the argument below is what the delivered shape had to answer, and
because the arity and cyclic-guard readings it ends on survived unchanged.*

`intent_field_column_table` answers the same kind of question this view does, which table a name
written at a site resolves against, over `@reference` paths rather than `argMapping` paths. It
carries two columns for it: a closed two-value `disposition` (`RESOLVE` / `SILENT`) that every
consumer switches on, and a `basis` naming which of its four rules fired (`PATH_TERMINAL`,
`NAMED_TYPE_TABLE`, `UNRESOLVED_PATH`, `CONFLICTED`; the `disposition` comment calls that a
"five-value vocabulary", which is stale against the four arms and should not be copied forward). Its
own comment argues
for carrying both even though the first is determined by the second, because the fork is "the
reading every consumer needs and re-deriving it from a [multi]-value vocabulary at each of them is
how the two would drift". This view has three consumers (the detection stratum, `EmitPlan`, the
editor), so the argument applies with more force here. Take the same shape:

* `RESOLVE`, with basis `ARGUMENT_PATH`, `BARE_HEAD` or `INPUT_FIELD_PATH`, one per resolving arm
  above.
* `SILENT`, with basis `NO_SLOT_IN_SCOPE` (a path-step site, where nothing is in scope to resolve
  against), `UNREACHED_INPUT_TYPE` (an input-field-rooted head whose input type no argument
  reaches), or `UNRESOLVED_PATH` (a segment naming no input field, which is the typo). The last is
  not a nicety: `leafTypeGate` is already silent on a path that descends through a non-input-object
  for exactly the reason it is silent on the motivating path, so the store is the only place that
  case can be caught at all.

**A silence is a row, and absence means one thing.** The sibling view states this outright
("Absence therefore means 'the parent's own scope answers'"), and it is what stops the three
unresolvable shapes above from sharing one indistinguishable gap with the ordinary case. Under it,
absence from this view means exactly "the pair row's path resolves to a leaf carrying no
`@nodeId`", which is the ordinary binding and rightly needs no row. That is also what keeps the
bare-form rejection an anti-join over a positive population rather than negative space maintained
by hand.

**Arity is distinct answers, not rows**, which is the same reading
`intent_field_reference_step_target.targets` already carries ("how many distinct tables this
element reaches ... a table element with three foreign keys connecting the two tables reaches one
table by three routes"). The input-field-rooted arm is one-to-many in *rows*, one per use site that
reaches the input type, and it must require one distinct *leaf* rather than one row. Those rows
cannot disagree: the leaf is fixed by walking input-field types down from the head, a definition-side
fact independent of which argument reached the type. So the constraint is a guard against writing
the view wrong, not a real fork, and saying which of the two it is belongs in the comment.

**One thing not to copy from that view: it collapses to one row per field coordinate**, taking
`MIN(ordinal)` over the applications and then the last element. This view must not. `@routine` and
`@reference` are repeatable and each application carries its own `argMapping`, so the grain here is
the pair's own key with `ordinal` intact; collapsing would resolve one application's path and drop
its siblings silently.

**The key-column resolution has three arms, not two.** `BuildContext.resolveTargetKeys` prefers the
`NodeIndex` entry (which `TypeBuilder` already reconciled against the table's metadata, SDL winning
on `typeId` outright and on `keyColumns` order), falls back to the table's own
`KjerneJooqGenerator` metadata read through `JooqCatalog.nodeIdMetadata`, and only then to `@node`
plus the catalog primary key. A two-arm view would answer differently from the generator for every
type in the middle arm's population, which is the metadata-carrying table with no matching
`NodeType`. `graphitron_node`'s comment already anticipates this: "the SDL-versus-jOOQ-metadata
precedence rules are detections". So the reduction is three arms with a `tier` column naming which
one answered, in `intent_resolved_field_claim`'s shape, and the middle arm reads the facts R710
captured rather than the reflective read `resolveTargetKeys` makes today.

The views, in the stratum's naming (`intent_resolved_*` for a reduction, the suffix at the front):

* **`intent_resolved_node_key_column (graph_name, type_name, position, column_name, tier)`.** The
  three-arm reduction above. Worth naming on its own terms, and not only for this item: the LSP
  wants exactly this list for completion, and an editor reading the store is the second reader that
  turns a derivation into a relation. The first-tier-that-answers pick is the
  `ROW_NUMBER() OVER (PARTITION BY ... ORDER BY precedence)` with `WHERE rn = 1` that
  `intent_field_column_table` ships, so the mechanism is borrowed rather than invented. **Partition
  by the type, not by the (type, position) coordinate.** The tiers carry *ordered lists*, not
  independent facts per position, so a per-position pick would splice one tier's column into
  another tier's order. That is exactly the transposition `resolveTargetKeys`' own comment warns
  about, "a `@node(keyColumns:)` that pins a different order than the metadata would project
  columns transposed against the order its own decode helper returns values in". One tier wins for
  a type and its whole list is taken. **An ambiguous binding resolves no key columns**, on the
  **lower two tiers both**, which is a correction R710's landed shape forces. Both reach a table
  through `intent_resolved_type_binding`, and that relation carries `candidates` without a
  precedence: two candidate tables are two different key tuples, and picking one would encode ids
  against a table the author never named. The third tier reaches the primary key that way, and so
  does the middle one, because R710 keys its rows on the catalog's own key
  (`source_name, table_schema, table_name`) with no graph partition at all, so getting from a graph's
  type to the metadata a class published *is* the binding question. Both tiers are silent there and
  the detection stratum names the ambiguity, on the same reasoning that makes every other
  unresolvable shape in this item a stated row rather than a gap. Only the pinned-SDL tier survives
  an ambiguous binding, `graphitron_node_key_column` being keyed by graph and type and needing no
  table to answer.
* **`intent_argmapping_binding_leaf`.** The keying over `intent_input_occurrence_path` described
  above, unioned across the **seven** `*_arg_mapping_pair` relations
  (`graphitron_routine`, `graphitron_service`, `graphitron_field_condition`,
  `graphitron_argument_condition`, `graphitron_field_reference_step`,
  `graphitron_argument_reference_step`, `graphitron_reference_for_step`) with a `site` literal per
  arm, plus the unconsumed-segment count. *Delivered as three relations rather than one: the union
  is `intent_argmapping_pair`, the keying is `intent_argmapping_segment_binding` at segment grain,
  and this name survives as the reduction to the last bound segment. See the stage-2 note.*
* **`intent_resolved_node_key_projection`.** The reduction: a binding whose leaf carries `@nodeId`
  and whose single trailing segment names one of that node type's resolved key columns. Named
  `intent_resolved_*` because it is a reduction, per the same rule that names the key-column view.

Uniformity across `@routine`, `@service` and `@condition` is then structural: a projection is
resolved identically everywhere because it is resolved once, in a `UNION` with a `site` literal per
arm. That is a stronger guarantee than the previous draft's "one shared resolver called by three
directive resolvers", which was three call sites agreeing by discipline.

Two things about that `UNION` the previous draft asserted away. The seven relations are one shape
only in their tail (`position`, `param_name`, `argument_path`); their use-site keys run from four
columns (`graph_name, type_name, field_name, position`) to seven (adding `argument_name`,
`ordinal`, `step_position`), so the view has to carry the consuming coordinate in a projection that
survives the widest arm. Carry it as a serialized use-site key beside the columns every arm has,
in the same vocabulary `intent_input_occurrence_path` already uses for its own key, with the
per-arm components reachable by joining back to the arm's own relation on `site` plus that key.
This is not a detail to settle at pickup: the rejection's use-keyed property below is precisely the
requirement that the message can name the consuming coordinate. And there are **eight** `site`
values over the seven relations, because `graphitron_field_condition_arg_mapping_pair` is the
shared coordinate whose parent kind splits it into an output-field site and an input-field site
with different heads and different emitters.

Case-insensitive column matching is a settled convention rather than a new rule, so the docs need
not introduce it: `intent_spelled_table` and `intent_column_match_claim` both compare under
`UPPER()`, and `JooqCatalog.resolveColumn` uses `equalsIgnoreCase`.

The typed product is `AuthoredClaimConflicts`' shape exactly: a class in `rewrite/derive` reading
the views through the `DSLContext` inside the capture transaction, returning records built from
query rows. Those records are the item's new Java types, and they are the only ones it introduces.

### What this item does not add

**No new `CallSiteExtraction` arm, no `NodeIdRecordColumn`, no `BoundPath`, no new sealed variant on
the walk surface, no walk-side registry.** The sealed leaf model is the strangler migration's
transitional producer surface, drained rather than extended, and a capability is added by adding a
fact relation. The routine-write command this item mints is not a counter-example: a command arm is
plan-side vocabulary assembled *from* the drained surface, which is the direction of travel, and it
is what lets the emitters stop reading the leaves at all (see "The emitters move onto commands"). An earlier draft of this item did the opposite: it proposed a new top-level
`CallSiteExtraction` arm wrapping `NodeIdDecodeRecord`, a `BoundPath` type in `ArgBindingMap`, a
widened `resolvePathLeaf` producer, and a new render-side registry. Every one of those is
walk-side, and the reasoning that produced them, that the arm's compile errors in each exhaustive
switch would be a useful work list, is an argument for a well-shaped leaf zoo, not an argument for
extending one that is being drained. The fact model names this failure directly: duties welded onto
a leaf that functionally depend on another coordinate's query.

The projection *is* another coordinate's query. It is a functional dependency of the pair's key
(graph, type, field, ordinal, position), resolved against the node type's key columns, and it has
no business on a leaf minted while walking a different coordinate.

**The walk's own contribution is one widening, and it is not free.** An earlier draft called it
"two deletions"; measured against the code it is one deletion with a blast radius and one
non-event:

* `ArgBindingMap.of` must stop rejecting a trailing segment after an `ID`-typed leaf ("walks
  through scalar 'ID' at segment ...; only input-object types may be traversed"), which is what
  makes the spelling unwritable today. But `of` has **six** call sites across four classes
  (`RoutineDirectiveResolver`, `ServiceDirectiveResolver`, `ConditionResolver` twice,
  `BuildContext` twice, the second of which is the input-field `@condition`), and the widened
  `PathExpr` is *consumed*: `RoutineCallEmitter.nestedSlotRead` registers a descent helper that
  walks every tail segment over the raw argument map and casts at the leaf. An admitted but
  uninterpreted key-column segment therefore emits `get("organisasjonskode")` against a `String`,
  which is this item's own failure mode relocated. The walk cannot gate itself on the projection:
  `GraphQLRewriteGenerator.runPipeline` builds the schema before it captures, so the store is empty
  when `ArgBindingMap.of` runs, and a walk-local re-check would be a second spelling of the
  resolution the view computes. What closes it instead is the pipeline order: capture and validate
  both run before `EmitPlan` and the renderers, so once stage 3's detections are in, an
  unresolved trailing segment fails the build before any emitter runs. The obligation the widening
  carries is therefore a test obligation, one case per `ArgBindingMap.of` call site proving the
  detection fires, and not a gate on the walk. "Admits the segment and carries it without
  interpreting it" is safe exactly to the degree those detections are complete, which is why stage 3
  precedes the widening rather than shipping beside it.
* `RoutineDirectiveResolver.leafTypeGate` needs **no** change. Trace the motivating path:
  `ServiceCatalog.resolvePathLeafType` returns `null` as soon as a segment descends through a
  non-input-object, so the gate hits its `if (leafType == null) return null; // unresolvable leaf:
  pass through` arm and rejects nothing. That is a better fact for this item than a deletion would
  be: the gate is *already silent* on this shape, and it is equally silent on a typo'd key column,
  which is precisely why the store-side detection is load-bearing rather than a nicety.

### Where the resolved projection is consumed

Two consumers, and the pipeline order (walk, then capture, then validate, then plan, then render)
decides what each can do.

* **Validation reads it as violations, and this is the shipped pattern.** `FactCapture` already
  runs `AuthoredClaimConflicts` over freshly captured rows inside the transaction and returns a
  typed `Detection` the caller folds into the error stream. The rejections this item needs are
  detection views in the `intent_authored_claim_conflict` mould, decoded into located
  `ValidationError`s by a sibling of that class. Rejection stays a typed value; what changes is
  that the rule lives in SQL and the Java decodes a closed verdict vocabulary.
* **Planning joins it onto the command, and every routine-call emitter reads it there.**
  `RoutineRef.ArgBinding` and `RoutineChain` live in `rewrite/model`, the walk surface this item may
  not touch. `LauncherCommand` and `LaunchSource.RoutineChain` live in `command/`, the plan surface,
  which is where a complete command row is assembled. So the projection is read by `EmitPlan` into a
  plan-local relation keyed by the pair's natural key and joined into the command row. Nothing in
  `rewrite/model` changes and the command reaching the renderer is complete by construction.

The distinction is worth stating plainly because "a projection map on the routine-call command"
reads either way, and that ambiguity is where the ruling gets broken by accident.

### The emitters move onto commands

**The launcher command is not the only carrier, and it is not the one the motivating case uses.**
`LaunchSource.RoutineChain` is minted at exactly one site, in `LauncherCommands`, off a
`QueryField.QueryTableField`: the query-side root read. A `@routine` on a `Mutation` field is
classified by `FieldBuilder.classifyMutationRoutineChain` into
`MutationField.MutationRoutineWriteField`, or by its hop-less carrier-payload fork into
`MutationField.MutationRoutineWriteRecordField`, and both emit from `TypeFetcherGenerator`
(`buildMutationRoutineWriteFetcher`, `buildMutationRoutineWriteRecordFetcher`) reading the model leaf
directly. That is the shape the manual already documents as the canonical `argMapping` example
(`rentFilmPayloadNested` in `docs/manual/reference/directives/routine.adoc`), the shape of this
item's motivating case, and the shape its pipeline-tier test binds. Of the four
`RoutineCallEmitter.emitCall` call sites, only `RootLauncherRenderer`'s reads a command row: the two
in `TypeFetcherGenerator` read `MutationField` leaves, and `PathFragments.emitTableExpression` takes
a `JoinStep`.

**So the emitters stop seeing leaves, and that is scoped into this item.** An emitter may not reach
into the leaf zoo for the projection, because an emitter may not reach into the leaf zoo at all.
That a routine-call emitter *can* read `MutationField.MutationRoutineWriteRecordField` is what let
"join it onto the command row" be written against the wrong carrier without anything catching it.
The rule already exists and is already enforced, just not over this package:
`PackageImportDirectionTest` pins that `no.sikt.graphitron.render` interprets commands, holds no
`GraphitronSchema` and no fact hierarchy, and borrows from the legacy tree only the named pure-data
refs on its dial. `TypeFetcherGenerator` sits in `rewrite/generators`, outside that guard, which is
why the leaf read is reachable there and nowhere in `render`.

Concretely, for the routine-write family:

* **`plan` mints a routine-write command**, one row per routine-write coordinate, carrying what the
  two fetchers read off their leaves today and nothing more: the routine call and its result table,
  the captured column pairs, the target table, the data field's arity, the error channel, the hop
  chain on the hopful arm, and this item's projection joined on. Every one of those is already a
  pure-data ref (`RoutineRef`, `TableRef`, `ColumnRef`, `Arity`, `ErrorChannel`) or a plain string,
  so the row needs no new model vocabulary and the two arms mirror the two leaves rather than
  inventing a shape. *(Superseded on one name: `ErrorChannel` is not pure data, it exposes the
  resolved `@error` types and through them the whole type hierarchy, so the row carries a
  command-side `ErrorDispatch` holding the mappings constant's name instead. See the stage-4 note.)*
* **`render` hosts the two emitters, reading only that command.** `TypeFetcherGenerator`'s two
  `case` arms delegate to the command relation the way its `MutationField.DmlTableField` arm already
  reads `launchers.rowFor(...)`, and stop reading the leaf.
* **`PackageImportDirectionTest`'s borrow dial grows by exactly the refs the new command carries.**
  That test is the point: once the emitters are in `render`, "an emitter sees only commands" is a
  build gate rather than a convention, and the dial's own comment records why each entry is there.

This is a structural pivot on a surface two emitters pin, so it lands additive-then-cutover per
`roadmap/workflow.adoc`: mint the command relation and the render-side emitters alongside the
existing ones, cut the two `TypeFetcherGenerator` arms over, then delete the leaf-reading bodies.
The execution tier holds at each step.

**What this does not do.** It dissolves no leaf. `MutationField.MutationRoutineWriteField` and its
record sibling stay exactly as they are; what changes is that `plan` reads them and `render` does
not. That is the same move the `facts-and-commands` programme made family by family, not the leaf
zoo's dissolution, which stays with `roadmap/coordinate-lowers-to-datafetcher-queryparts.md`.

**One rejection arm this design needs and an earlier draft lacked.** The view resolves a projection
at every `site` its `UNION` covers, but the emitters land site by site. A projection that resolves
where no emitter is wired is a classified decision implying a generator branch that does not exist,
which is the silence this item was filed to close. So the detection stratum carries a `deferred`
arm keyed on the `site` column, naming the sites that emit, and it shrinks as sites land.

### The catalog facts this reads, and who captured them

**Both populations this item once planned to capture belong to other items, and both have landed.**
The section this replaces argued for capturing the routine call surface and the jOOQ node metadata
here, on the ground that both are unreachable outside the codegen classloader and so a run that
does not capture them cannot answer the question afterwards. That argument held; what changed is
who acts on it.

**Landed, in R704 slice 7.** `sql_table.table_type` records jOOQ's `TableOptions.TableType` for
every row, so table-valuedness is a column rather than a live-catalog probe, and `sql_routine` plus
`sql_routine_parameter` capture the callable: the generated `Routines` class, the value-parameter
method the parameters belong to, and those parameters in declaration order with their Java binding
types. Four things this item asked for came through unchanged, and are worth naming because they
are what this item would otherwise still owe: the facts are read off the resolved `Table<?>` through
`JooqCatalog.routineCallFactsOf`, sitting beside the name-keyed resolution the way
`candidateKeys(Table<?>)` sits beside its own, so capture never re-runs a lookup that would collapse
a function two schemas both declare; the record carries type names rather than javapoet; `table_type`
landed; and the `-parameters` dependency the join key inherits is owned in the `jooq_name` column
comment, with an agreement test asserting the captured names are the database's own and not `arg0`,
so losing the flag fails the build rather than quietly degrading the join.

**The shape came out the other way from this item's proposal, and the reason matters more than the
outcome.** This plan proposed hanging the parameters off the function-typed `sql_table` row, as
`sql_column` hangs off a table. R704 made the callable its own subject, on the rule the family is
named for: the standard separates ROUTINES from TABLES, a routine's parameters are a fact about the
callable rather than about a result, and a routine with no `RETURNS TABLE` form has a callable and
no table row at all, so parameters hung off `sql_table` would have nowhere to go the moment a walk
reads one. Table-valuedness is the join, a `FUNCTION`-typed `sql_table` row at the routine's
coordinate, not a third column. Two findings arrived with it that this plan could not have had: jOOQ
generates no `Routine` object for a table-valued function at all, only the result-table class and
the convenience method, which is what makes the non-table-valued routines a separate population
rather than a subset; and a routine with no generated call surface is a real arm, so the class and
method names are nullable and their nullness separates a routine that takes no parameters from one
whose call surface is not exposed. The SQL-side parameter vocabulary resolved the way this plan
recommended, omitted rather than shipped always-null, because the database's own names survive only
as jOOQ's camelCase transform and the SQL types only as anonymous bind placeholders behind a
protected `TableImpl` field.

**Shipped, in R710** (`jooq-node-metadata-as-stated-facts`, Done, see `roadmap/changelog.md`). The
jOOQ node metadata, the `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` statics
`JooqCatalog.nodeIdMetadata` reflects on today, is now `sql_node_metadata` plus an ordered
`sql_node_key_column` child, recorded *as stated* rather than as validated, with
`intent_node_metadata_defect` beside them as the well-formedness derivation. That is the middle tier
of the key-column resolution, and it was the last capture this item was waiting on. Nothing reads
those rows yet; this item is their first reader. The as-stated shape is
a better fact than the reflective read it replaces, and it is why the tier joins a well-formedness
derivation rather than the base relation (see "What the key-column segment names").

So this item captures nothing. What it owes on the catalog side is reads, and the obligations that
travel with a read rather than with a write: naming the relations it joins, stating what it does
when one answers ambiguously, and pinning those answers against fixtures rather than against a
shape it authored itself.

### The bare form becomes a rejection

Binding a `@nodeId` leaf with *no* key-column segment is rejected, naming the node type and listing
its resolved key columns. This is the change that closes the silent `TEXT`-parameter hole, and it
is worth landing even if everything else here slipped: today that spelling writes a base64 string
into a database column and nothing in the build says a word.

As a detection view it is an anti-join: pair rows whose leaf carries `@nodeId` and whose path
consumed every segment. That is a positive statement about a captured population, not a negative
space maintained by hand, which is what makes it additive in the same way the demand stratum's
exemption arms are.

The counter-proposal, implicit decode for single-key node types, is rejected. It would make the
same spelling mean two different things depending on a fact (the node's key arity) that is not
visible at the `argMapping` site, and it would leave composite-key node types needing the explicit
segment anyway. One spelling, always explicit.

The rejection is universal, not target-driven. An earlier draft argued that binding the *whole*
decoded record to a `@service` parameter typed as the generated `*Record` should stay legal; that
was checked and it is not reachable today (`ParamRole.ArgBound` routes through `argExtraction`,
which rejects an `ID` leaf against a record type, and `NodeIdDecodeRecord` is minted only for
jOOQ-record-typed input-bean members). Enabling it would be a second capability, so it is out.

Three properties to settle here rather than let fall out of implementation:

* **Verdict class.** `Rejection.structural`. There is no future in which the raw base64 was
  intended, so it holds even if the rejection ships ahead of the projection (see "Scope").
  *Shipped as that for the bare form and the missing `typeName:`; the unknown key column ships as
  the typed `Rejection.unknownNodeIdKeyColumn` instead, a lookup against a closed set having
  candidates to offer, which `structural` cannot carry.*
* **Location.** The view carries it, the way `intent_authored_claim_conflict` does: the pair row's
  own application position, so the message points at the `argMapping` the author wrote rather than
  at the input type's declaration.
* **Keying axis.** The rejection is *use-keyed*. One input type can be consumed by a `@routine`
  mutation (no containing table, projection required) and by a table-bound `@service` mutation
  (inference works). An author who reads "add `typeName:`" and edits the shared input type is
  editing a definition-keyed fact to satisfy a use-site constraint, so the message must name the
  consuming coordinate that is asking. The view's key already carries it.

`@nodeId` without `typeName:` is rejected at this position for the reason the authoring section
gives, and as a view arm it is simply `graphitron_field_node_id.node_type_ref IS NULL` on a leaf a
projection binds. The message vocabulary should still converge with
`NodeIdLeafResolver`'s two existing "cannot infer a node type here" messages, which today end
differently ("Add typeName: explicitly." and "Specify typeName: explicitly."), since the whole
argument for a shared vocabulary is that authors meet one wording for one condition.

### Emission

The emitted expression is what the existing `@nodeId` machinery already produces one level up:
decode the node id into the target `TableRecord` once, then read the named column off it. That is
the same body `InputBeanInstantiationEmitter.buildRecordDecodeHelper` emits for a jOOQ-record-typed
input-bean member, so the decode is reused rather than rebuilt. Naming the column rather than
indexing a tuple is what makes a transposed composite-key projection unconstructable, and it comes
free from a view whose row *is* a column name.

What changes is where the facts come from: the command row, not a `CallSiteExtraction` arm. Three
emission facts have to be on it, and each has a store answer to confirm at pickup: the node type's
`type_id` (`graphitron_node.type_id`, which is "as written", with the type-name fallback the table's
own comment defers to a derivation),
the target table (`intent_resolved_type_binding`), and the column (the projection view). The encoder class is
a generator-configuration fact rather than a captured one; locate it at pickup and say which side
of the line it sits on.

Two mechanical facts about the emitters survive the redesign and still bind:

* **`RoutineCallEmitter` cannot import `FetchersHelperNames`.** It lives in
  `no.sikt.graphitron.render`, and `PackageImportDirectionTest`'s render leg rejects
  `no.sikt.graphitron.rewrite` imports that are not on the borrow dial. Whatever carries the helper
  name has to be render-side or on the command row.
* **The decode helper body is hosted per generated class.** `decode<RecordType>` (`decodeFilmRecord`,
  the record class name already carrying the suffix) is drained
  onto `<Type>Fetchers` from a walk over that class's input-bean carriers, and
  `ConditionGlueRenderer` builds separate conditions classes with their own registry that cannot
  call it. So a projected read at `@condition` needs the body emitted onto the conditions class:
  `@condition` is the expensive emit site, not the cheap one. Watch for two bodies landing on
  `<Type>Fetchers` when one class hosts both an input-bean decode and a projection.

**Materialise once.** Two projections off one node id must share one decode: one materialisation
and one failure site, not two identical throw points for one bad id. That needs a hoisted local,
which means `emitCall` yields pre-statements alongside its expression. Three of its four call sites
have statement context to hand; `PathFragments.emitTableExpression` returns a bare expression
consumed inside alias-declaration loops, so it either propagates the same signature change to its
own callers or keeps a per-read call with a comment saying why. That is a signature question one
level up, not an implementation detail.

### Fact capture

The store grows on one stratum only, which is the change R704 and R710 make to this item.

**No base relations.** The routine call surface landed with R704 and the node metadata with R710,
per "The catalog facts this reads". Nothing here writes a row.

**Derived relations:** the three resolution views plus the detection views, all `intent_`, all
registered under the derived arm.

**No new column on the SDL side.** `graphitron_routine_arg_mapping_pair.argument_path` holds the
right side as written, it is still a dotted path, and capture still records it verbatim. What
changes is that its comment enumerates what a segment can name ("a GraphQL argument name or dotted
input path") and now enumerates it incompletely; restate it at the rule's grain on the `@routine`
pair relation and on its six siblings.

That the `sql_` half is worth doing on its own terms is what let both populations leave this item
without leaving the roadmap: they are unreachable outside the codegen classloader, so a run that
does not capture them cannot answer a routine or node-metadata question afterwards, and every
consumer that wants one is forced back through a live reflective walk. That is the argument
`sql_column.binding_type`'s comment already makes for columns ("read off the live `Field` during the
catalog walk and unrecoverable afterwards"), and it is the argument R704 and R710 each acted on.

## Implementation

* **No base-relation work and no capture work.** The routine call surface is R704's and the node
  metadata is R710's; both have shipped. This item reads them.
* **Views**, house style per `intent_bound_table` (declared column list, full comment coverage,
  closed vocabularies as `CHECK` or as stated column comments), and structurally per
  `intent_field_column_table`, the sibling path-resolution view whose `disposition` / `basis` shape
  and precedence pick both apply directly here: `intent_resolved_node_key_column` (three tiers,
  `tier` column, the pick partitioned by type so a tier's column order survives),
  `intent_argmapping_binding_leaf` (the four-arm keying over `intent_input_occurrence_path` joined
  through `graphitron_argument_path_segment`, no string surgery on either key, `site`
  literal per union arm, `disposition` plus `basis`, unconsumed-segment count; *delivered at segment
  grain without the fork, per the stage-2 note*), and
  `intent_resolved_node_key_projection`, plus the detection views for the rejections including
  the site-keyed `deferred` arm (*shipped as one detection view of three arms, the deferred arm
  derived in Java instead, per the stage-3 note*). The key-column view reads `intent_resolved_type_binding` for its
  third tier, `intent_node_metadata_defect` for its middle one, and the binding for both, so neither
  the `@table` population nor a raw as-stated row is read directly.
* **Typed products** in `rewrite/derive`, in `AuthoredClaimConflicts`' shape: records built from
  query rows, decoding a closed verdict vocabulary into `Rejection` arms. The only new Java types
  the item introduces.
* **`FactCapture`**: run the detection over the freshly captured rows inside the existing
  transaction and return it in the typed product the caller already folds into the error stream.
* **`GraphitronSchemaValidator`**: fuse the new violations the way the claim conflicts are fused.
* **`ArgBindingMap.of`**: widen the traversal rejection to admit one trailing segment after an
  `ID`-typed leaf, *with* the six-call-site audit and the `RoutineCallEmitter.nestedSlotRead`
  consequence handled in the same commit (see "What this item does not add").
* **`EmitPlan`**: read the projection view into a plan-local relation keyed by the pair's natural
  key and join it into every command row that carries a routine call: `LaunchSource.RoutineChain`
  in `LauncherCommands`, and the new routine-write command below. Nothing in `rewrite/model`
  changes.
* **A routine-write command** in `command/`, two arms mirroring `MutationRoutineWriteField` and
  `MutationRoutineWriteRecordField`, minted in `plan` from those leaves and carrying the facts the
  two fetchers read today plus the projection (see "The emitters move onto commands").
* **`render`**: the two routine-write emitters, reading only that command.
  `TypeFetcherGenerator`'s two `case` arms cut over to it, following the
  `MutationField.DmlTableField` arm's existing `launchers.rowFor(...)` shape, and the leaf-reading
  bodies are deleted once the cutover holds.
* **`PackageImportDirectionTest`**: extend the borrow dial by the refs the routine-write command
  carries, each with the one-line justification the dial's comment convention asks for. The dial is
  not the only thing to update: `borrowDialComponentClosureIsPinned` computes the legacy-tree closure
  of the borrowed refs' arms and components by reflection and pins it as `BORROWED_COMPONENT_CLOSURE`,
  so a borrowed ref brings its components' names with it.
* **`RoutineCallEmitter`**: emit the decode-and-read from the command row; `emitCall` yields
  pre-statements alongside its expression and the four call sites add them.
* **`ConditionGlueRenderer`** and the `@service` pair, when their sites land: the same read, with
  the decode helper body hosted on the conditions class for the `@condition` site.
* **Comments**: restate `argument_path` on all seven `*_arg_mapping_pair` relations.

## Tests

* **No capture tests.** Both populations are pinned by the items that captured them, and both of
  those tests exist rather than being owed: R704's
  agreement test asserts the routine parameters are the database's own names rather than
  `arg0`, and R710 shipped its two relations in `FactCaptureAgreementTest`'s equality arm with the
  defect view as derived, the form-to-nullability correspondences as `CHECK` constraints, and an
  assertion comparing the store's per-table verdict against the live probe's. What this item pins on
  the catalog side is what its own views make of those rows.
* **View-level tests** in the `ColumnMatchClaimTest` / `DemandShadowTest` mould, one per view, and
  for the binding-leaf view specifically in `FieldColumnTableTest`'s, which is the closest match in
  the tree: a path-resolution view with silences, anchored case by case (twenty-three of them today,
  over that view and its sibling scope view). Two habits to take from
  it. It asserts on `disposition` and `basis` rather than only on the value resolved, so a case pins
  *which rule fired* and not merely that the answer came out right (*at segment grain the delivered
  suites pin the bound positions instead, which is the same habit against a shape with no
  vocabulary: where the list stops is which rule fired*). And it pins absence explicitly,
  in roughly ten of those cases asserting no row at the coordinate, which is how the boundary of the
  relation gets tested rather than assumed. It also asserts at most one row per coordinate in its
  read helper; the analogue here is per pair-row key, since this view keeps `ordinal` rather than
  collapsing it.
  `intent_resolved_node_key_column` needs a case where `intent_resolved_type_binding` answers with
  `candidates = 2`, pinning **both table-reaching tiers** silent rather than picking, and the pinned-SDL
  tier still answering at the same coordinate, which is what separates "no table to read" from "no key
  columns". That population is reachable now that a type
  can be bound by a `@table` and by a routine return at once. It also needs all three tiers
  populated, not two, plus a composite-key
  type (`bar` in the `nodeidfixture` catalog is the one `NodeIdPipelineTest` already uses), and a
  case pinning that a tier's key column *order* survives the pick rather than being spliced across
  tiers.
* **The binding-leaf view wants each of its four arms pinned, not just its happy path**: a
  bare-scalar argument head, a bare `@nodeId` head (the arm the whole silent-`TEXT` case runs
  through), an input-field-level `@condition` whose head names the input field, one whose input
  type no argument reaches (the zero-row absence), a path-step `@condition` pair row that resolves
  no leaf, a path whose leaf is an input object rather than a scalar, a path whose middle segment
  names no input field at all (the `UNRESOLVED_PATH` typo, which the walk is silent on and the store
  is the only place that catches), and the two-trailing-segment
  case that must not resolve as a projection.
* **Registration**: `FactCaptureAgreementTest` for every new relation and view, base and derived.
* **Corpus population per arm.** A view arm no fixture reaches is a vacuous pin. Each rejection
  arm, each key-column tier and each of the eight `site` values needs a coordinate that reaches it,
  including the two the field-condition relation splits into.
* **Cross-site parity**: one test over `@routine`, `@service` and both `@condition` sites. The
  `UNION` arms are seven hand-written `SELECT`s over relations whose key arities differ, and a typo
  in one is exactly the drift it catches.
* **The carrier move is pinned as a refactor, not as a feature.** Stage 4 changes no output, so the
  assertion is that it changes no output: the routine-write pipeline-tier fixtures that exist today
  keep their expected sources verbatim across the cutover. Beside that, a command-relation test in
  the launcher family's mould pinning one row per routine-write coordinate with the facts the
  emitter needs, and `PackageImportDirectionTest` covering the emitters' new home, which is what
  turns "an emitter sees only commands" into a gate. The motivating case is a
  `MutationRoutineWriteRecordField`, so at least one fixture must be that arm and not only the
  hopful one.
* **Pipeline tier**: the `rent_film` fixture binding `pInventoryId` from
  `ID! @nodeId(typeName: "Inventory")` through the projected key column, asserting the emitted call
  materialises the record once and reads the column off it, plus the rejection cases. The fixture
  has to be a shape that actually emits: a hop-less `Mutation @routine` classifies as a typed
  `Deferred` unless its return scans as a routine carrier payload, so mirror the manual's
  `rentFilmPayloadNested` shape rather than the bare payload return the motivating example sketches.
* **Validate-time tests** that the build fails, not only that a `Rejection` is produced.
* **The widening's blast radius**: a test at each of the six `ArgBindingMap.of` call sites that a
  trailing segment which resolves to no projection cannot reach `nestedSlotRead` and emit a raw
  map read.
* **`@condition` emission** needs a compilation-tier assertion about *which class* hosts the decode
  helper body, since that is the half the pipeline tier cannot see.
* **Execution tier** (`graphitron-sakila-example`): one round trip proving the decoded key reaches
  the database as a key rather than a base64 string, alongside the existing
  `NodeIdValueAgreementExecutionTest`.

## Risks

* **The widening at `ArgBindingMap.of` is the sharp edge among the views.** Six call sites, and a
  consumer (`RoutineCallEmitter.nestedSlotRead`) that will happily emit a raw map read for a segment
  nobody interpreted. The pipeline order contains it (see "What this item does not add"), but only
  if stage 3's detections actually cover every site the widening admits; a site with no detection is
  a silent raw map read.
* **The carrier move is the largest single piece of this item, and it is a re-platforming slice
  rather than a feature.** Two emitters change package, a command relation is minted, and a guard
  test's dial grows. It is bounded (the facts the two fetchers read are enumerable and already
  pure-data) and it lands additive-then-cutover with output held identical, but it is the stage most
  likely to want its own item if the schedule tightens. Lifting it out is fine; threading the
  projection through a `MutationField` leaf to avoid it is not, because that re-creates the
  emitter-reads-the-leaf coupling this item exists to stop relying on.
* **The spine reads a relation another item still owns.** `graphitron_argument_path_segment` is
  R715's. Its delivery, including the coordinate key this item joins on, is in the tree; R715 sits at
  `Ready` after an In Review reopen over its own stale accounting. What is still moving there is that
  family's comment prose, so the exposure is a comment conflict rather than a shape one.
* **The capture half is discharged.** Both halves shipped as other items, R704's routine call surface
  and R710's node metadata, so every catalog relation this item reads exists and the schedule risk
  this bullet used to carry is gone. What replaces it is smaller and worth keeping: this item is the
  first reader of R710's rows, so any disagreement between the as-stated relations and what the live
  probe answers surfaces here first rather than in the item that wrote them. R710 shipped an
  agreement assertion comparing the store's per-table verdict against the probe's, which is the thing
  to re-run rather than re-derive if the middle tier ever answers surprisingly.
* **`intent_resolved_type_binding` can answer with two candidates**, and this item's response is
  silence plus a detection rather than a pick. That is the right answer but it is a new population
  to reach in fixtures: a type bound by both a `@table` and a routine return, which did not exist
  before R704's slice 9.
* **The parameters' SQL-side vocabulary and the `-parameters` dependency are discharged**, both by
  R704 and both the way this plan recommended: the SQL-side columns were omitted rather than shipped
  always-null, with the finding in the relation comment, and the agreement test fails the build if
  the captured parameter names degrade to `arg0`. Recorded because they were live risks here for
  four days and a reader of the history should see where they went.

## User documentation (first-client check)

The user surface is a new spelling on an existing directive argument, so the docs change is
small and lands in three places:

* `docs/manual/reference/directives/service.adoc#arg-mapping` is the shared home of the
  right-hand-side path form. Its intro currently lists `@service`, `@condition`, `@routine` and
  `@tableMethod` as the directives it covers; the last of those is legacy residue in the prose (the
  rewrite does not declare `@tableMethod`, per `docs/manual/how-to/migrating-from-legacy.adoc`) and
  is a stale mention to drop while editing the paragraph, not a site this item owes anything to.
  The rule list currently reads "each subsequent segment must name a field on the
  input-object type at that depth", which is the openability rule stated for the only kind that
  existed. Generalise that bullet rather than appending a special case: a segment opens the thing
  at that position, an input object opens into its fields, a `@nodeId` leaf opens into the key
  columns of the type it refers to. Because the form works at every directive that accepts an
  `argMapping`, the shared section needs no per-directive caveat: that uniformity is the point of
  the section already existing.
* `docs/manual/reference/directives/routine.adoc`: a short subsection after the existing
  wrapper-input example, showing the `@nodeId` input field and the projected binding. The
  Constraints list gains the bare-form rejection and the explicit-`typeName:` requirement.
* `docs/manual/reference/directives/nodeId.adoc`: a cross-reference from the decode side, so an
  author reading about `@nodeId` finds the routine binding without going through `@routine`.

Draft of the `routine.adoc` subsection:

> **Binding a routine parameter from a node id**
>
> When the input field carries `@nodeId`, its wire value is an opaque base64 id, not the key it
> encodes. Name the key column after the field to bind the decoded key instead:
>
> ```graphql
> input RentFilmInput {
>     inventoryId: ID! @nodeId(typeName: "Inventory")
>     customerId:  Int!
> }
>
> type Mutation {
>     rentFilm(input: RentFilmInput!): RentFilmPayload
>         @routine(
>             name:       "rent_film"
>             argMapping: "pInventoryId: input.inventoryId.inventory_id, pCustomerId: input.customerId"
>         )
> }
> ```
>
> A dot opens the thing to its left. An input object opens into its fields, which is what
> `input.customerId` does; a node id opens into the key columns of the type it refers to, which
> is what `input.inventoryId.inventory_id` does. `inventory_id` is a key column of `Inventory`,
> spelled the way `@node(keyColumns:)` spells it. A node type with a composite key opens into
> each of its key columns, so two parameters can be bound from one id.
>
> A malformed id, or a well-formed id of the wrong type, fails the field with a client error;
> it is never passed through. Binding a `@nodeId` field without naming a key column is a build
> error listing the columns available, and `@nodeId` at this position requires an explicit
> `typeName:` because there is no containing table to infer the node type from.

## Relationship to other items

* R704 (`routine-composition-surface-from-facts`, Done, see `roadmap/changelog.md`) **has shipped**,
  and it is the reason two sections of this plan were rewritten rather than annotated. It owned the
  `@routine` read surface end to end; what it leaves this item is a set of reads and one pivot.

  **Its slice 7 captured the routine catalog facts, and chose the other shape.** This plan proposed
  hanging the parameters off the function-typed `sql_table` row; the callable landed as its own
  subject, `sql_routine` plus `sql_routine_parameter`, with table-valuedness expressed as the join
  to a `FUNCTION`-typed `sql_table` row. The reasoning is recorded under "The catalog facts this
  reads" and it is better than this plan's: a routine with no `RETURNS TABLE` form has a callable
  and no table row, so the parameters had nowhere to hang. All four inputs this item handed over
  were honoured, including the `-parameters` dependency, which is now a build failure rather than a
  silent degradation.

  **Its slice 9 moved the binding question, which is the pivot that touches this item's own views.**
  `intent_bound_table` is now the `@table` population specifically; the routine return binding is a
  sibling relation; `intent_resolved_type_binding` is the reduction where they meet, and it is what
  a reader asking "which table stands for this type" reads. Five readers were repointed onto it and
  two deliberately left, each named in its comment. This item's third key tier and its emission
  facts are both that question, so both were repointed here. The arity that comes with it is real
  work, not a rename: the relation carries `candidates` and declines to pick between a `@table` and
  a routine return that disagree, so the tier goes silent and the detection stratum names it.

  **Its Track A landed, so one interaction is now live rather than pending.** Routine-backed read
  fields have a real `@condition` and `@orderBy` surface, so `argMapping` paths resolve at
  coordinates that carried none. That is corpus population for this item's `site` arms, reachable
  today.

  **Its slice 13 left for R682**, so the sequencing note this plan carried has moved with it: see
  the R682 bullet below. Track A already filled the two literal `null`s in
  `LauncherCommands.routineRow`, which is the half of that collision that has resolved on its own.

  The write seat stayed where it was. R704 kept the deferral alive as
  `RoutineDirectiveResolver.writeSeatReadSurfaceDeferral`, seat-gated to the
  write classifiers, and left the write-side read surface with R454, so stage 4's carrier move
  relocates those emitters without touching the deferral.
* The grammar-normalisation item (shipped, `28c4f64`) owns `graphitron_argument_path_segment`,
  the relation this item's spine view reads, and it landed the coordinate key that makes it readable
  from a pair row at all: the relation used to be interned by path text for the whole graph, so a
  segment set had no owner and could only be joined on a bare string. It now keys
  `(graph_name, type_name, field_name, argument_path, position)` with a foreign key to `graphql_field`,
  which is the join the binding-leaf arms use. That item has since shipped in full, so
  the shape is settled and what the two share is a comment surface: this item restates
  `argument_path` on the seven pair relations, that one rewrote the segment relation's own. Coordinate on
  those, not on the key. The relationship is otherwise one-way: nothing here changes anything R715
  owns, and R715's own reading of the redundancy trade ("a consumer needing the exact owner joins the
  pair relation on `(type_name, field_name, argument_path)`") is exactly what this item does with it.
* R710 (`jooq-node-metadata-as-stated-facts`, Done, see `roadmap/changelog.md`) owned what was
  stage 1's remainder, the `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` statics
  as `sql_node_metadata` plus an ordered `sql_node_key_column` child, and it has shipped, so this item
  is no longer blocked by anything. Four things it delivered that stage 2 should read rather than
  rediscover. It records the metadata **as stated**, with a form vocabulary per constant and a
  row whenever either is declared, so the key-column view's middle tier reads well-formed
  rows through `intent_node_metadata_defect` rather than the base relation, taking the conjunction
  (a metadata row with zero defect rows) and not the anti-join alone. Its `KEY_COLUMN_UNRESOLVED` arm
  fixes the entry-resolution predicate the middle tier has to share. Its rows are keyed on the
  catalog's own key with no graph partition, which is what puts the middle tier behind
  `intent_resolved_type_binding` alongside the third rather than beside the first. And it chose the
  relation name this plan had sketched for its own child, a naming collision resolved in R710's favour
  before either was written; nothing here should reintroduce it. Nothing reads the rows yet, R710
  having shipped facts with their reader deliberately left to arrive later, and this item is it.
* The nodehood derivation (R711, shipped in `b503a79` and recorded in `roadmap/changelog.md`; its item
  file is gone, the item being Done) is the other item R710's facts were captured for, and it is a
  different question: whether a type *is* a node, joined SDL claim to jOOQ metadata so the
  federation-key macro stops reading a live catalog from inside capture. This item's
  `intent_resolved_node_key_column` is not that derivation and does not wait on it: nodehood is a
  predicate, the key-column view is an ordered list with a tier precedence, and neither subsumes the
  other. They are two readers of one relation, which is the ordinary shape here.
* `roadmap/planners-read-facts-emitters-read-commands.md` (R682) inherited R704's slice 13 outright:
  re-sourcing `LauncherCommands.routineRow` off facts, as the read-side worked example of driving
  the plan tier onto the store. That is the method stage 5 joins the projection into, so the
  sequencing note this plan carried against R704 now points here. Landing after R682's routine
  family is one edit against a fact-sourced row; landing against it is two sessions editing one
  method from opposite directions. Stage 4 is the write-side counterpart of the same programme
  (an emitter that reads a command rather than a leaf), so if R682 is picked up first this item's
  stage 4 should be read beside it rather than duplicated.
* `roadmap/routine-coercing-arg-extractions.md` (R625) makes the routine emitter honour
  non-`Direct` extraction arms (`EnumValueOf`, `JooqConvert`). An earlier draft of this item made
  the relationship directional by riding the extraction slot; under the store-derived design it is
  **independent again**, because this item adds no extraction arm and leaves `ParamSource.Arg`
  carrying `Direct`. The two touch the same emitter and nothing else, so the only coordination left
  is the ordinary one about editing `argExpression` at the same time. Worth noting for R625's own
  reviewer: this item's shape is an argument that R625's capability may also belong in the store
  rather than in a wider switch on the drained surface.
* `roadmap/lsp-reads-the-fact-store.md` (R638) is the nearest *shipped* precedent, and the one to
  read first. Its `intent_field_column_table` resolves an authored `@reference` path to the table a
  name written at that site means, which is the same problem this item has with `argMapping` paths:
  a written path of unbounded length, resolved relationally, with several ways to reach no answer.
  Four of its rulings are imported above and marked where they land: the `disposition` / `basis`
  pair over a bare arm literal, a silence being a row so that absence means exactly one thing,
  arity read as distinct answers rather than rows, and the `ROW_NUMBER` precedence pick for a
  first-tier-wins reduction. Two of its moves deliberately are not: it collapses a repeatable
  directive's applications to one row per field coordinate, which this view must not do, and its
  `path_terminal` resolution lives as a CTE inside its only reader rather than as a named relation.
  The relationship is one-way and needs no coordination: R638 has landed the parts this item reads,
  and nothing here changes anything it owns.
* `roadmap/delivery-verdict-derives-from-the-store.md` (R666) is the nearest structural sibling and
  the model this item now follows: a verdict computed by a walk-side switch, restated as an
  `intent_` view over captured base relations, landed in shadow with residues before any consumer
  flips. Read it before picking this one up. If both are in flight, they should agree on the
  shadow-versus-flip discipline rather than inventing two. R666 is still `Spec`, though, so the
  *shipped* instance of the same move is now R704's Track B, in this item's own domain: read that
  for how the pattern actually lands and R666 for the discipline it argues.
* `roadmap/coordinate-lowers-to-datafetcher-queryparts.md` (R333) owns the leaf zoo's dissolution.
  This item must not anticipate it or depend on it: it neither extends the sealed model nor
  retires any of it, which is what lets the two proceed without a joint decision. Stage 4's carrier
  move is not an exception. It leaves both routine-write leaves standing and only changes who reads
  them, moving one family's emission from the leaf to a command exactly as the `facts-and-commands`
  programme did family by family before R333's dissolution begins. If anything it shortens R333's
  work, since a leaf with no emitter-side reader is easier to dissolve than one with two.
* `roadmap/lsp-argmapping-routine-coordinate.md` (R626) gives `@routine(argMapping:)` completions
  and diagnostics at all. R626 explicitly leaves dot-path expansion unmodelled ("offer nothing
  rather than a misleading flat list") because the LSP snapshot carries no nested-input-field
  projection. Under the openability rule that limitation splits by kind rather than being uniform:
  the input-object arm still waits on the snapshot projection, while the node-id arm is answerable
  from the node type's key columns. Under this design it is answerable from a *relation*:
  `intent_resolved_node_key_column` is exactly the completion list, all three tiers of it. That the
  editor is a second reader of the same view is the argument for naming it rather than leaving it a
  CTE inside whoever asked first, and it is why the view earns its place independently of this
  item's own use of it. So key-column completion is reachable *ahead* of the general case rather
  than after it. It is still its own item and must not ride this one, but R626's "offer nothing"
  note should be narrowed to the input-object arm when either item lands, so it does not read as a
  blanket bar on a case that is no longer blocked.
* `roadmap/nested-argmapping-syntax.md` (R249) extends the right-hand side with a nested object
  form. It varies the same grammar from the other end and composes with the openability rule
  rather than negotiating against it, so the two no longer need a joint decision on the
  separator. They still share an owner, so coordinate on edits to
  `ArgBindingMap.parseArgMapping` plus `ArgBindingMap.of`.

## Stages

One item, four stages in dependency order, numbered 2 to 5 because stage 1 left rather than because
the list was rewritten around it; every cross-reference in this plan keeps its number. The numbering
is a real seam rather than bookkeeping: each stage's result is observable on its own (a view a test
can query, a rejection the build emits, a refactor that holds output identical, generated source),
so there is something to verify between them. The plan tracks what is next by collapsing a shipped
stage into a one-line note.

1. **Capture.** *Not this item's, on either half, and both halves have shipped.* The routine
   call surface landed with R704 slice 7; the node metadata with R710, now Done. Stage 2 is the first
   stage and nothing gates its start: every relation it reads exists, and the binding-leaf view and
   the projection reduction join no catalog fact at all, so neither ever depended on either.
2. **Resolution views.** *Shipped, at a different grain than this plan prescribed.*
   `intent_resolved_node_key_column`, `intent_argmapping_segment_binding`,
   `intent_argmapping_binding_leaf` and `intent_resolved_node_key_projection`, plus
   `intent_argmapping_pair`. Two of the five this plan did not name. `intent_argmapping_pair`
   normalises the seven pair relations into one shape, so the eight-arm union is written once and a
   reader recovers an arm's own key columns by joining rather than by parsing the use-site key.
   `intent_argmapping_segment_binding` is the grain correction, and it is the change worth reading
   this note for.

   This plan asked for one row per path, with a `disposition`/`basis` fork borrowed from
   `intent_field_column_table` to say what a path that resolved nothing had done instead. Built that
   way, the fork cost more than it bought. A path that stops halfway has no leaf, so the silent arms
   had to null every leaf column, which discarded the descent the view had just computed and left a
   downstream reader unable to say *where* the path stopped without re-deriving it. Worse, two
   different facts shared one `basis` value, since a head naming no slot and a segment naming no
   input field both came out as `UNRESOLVED_PATH` with an identical all-null payload.

   The grain that removes all of it is the segment. `intent_argmapping_segment_binding` carries one
   row per segment that binds something and no row for a segment that binds nothing, and because
   `graphitron_argument_path_segment` already says whether a segment exists at a position, absence at
   a position means exactly one thing and means it locally. `disposition`, `basis`,
   `unconsumed_segments` and the null-padded arms all dissolve; `NO_SLOT_IN_SCOPE` and
   `UNREACHED_INPUT_TYPE` stop being stored verdicts and become joins, which is where they belong,
   the negatives being stage 3's to state. `intent_argmapping_binding_leaf` survives as a thin
   reduction (the bound segment with no bound successor) so the projection and stage 3's detections
   share one spelling of "no successor" rather than one each, and it carries `node_id_declared`
   beside `node_type_ref` because three answers are wanted there and a fork would collapse two of
   them. No recursion is needed for any of it: every prefix of an occurrence path is its own row, so
   a segment binds exactly when some occurrence path of that depth matches segment for segment.

   The tell that the plan's grain was wrong: under it, the trailing-segment fact was computed twice,
   once as `unconsumed_segments` in the leaf view and again as a `NOT EXISTS` on the next segment
   position in the projection. Two spellings of one fact is the thing this store's own rules forbid,
   and at segment grain it is computed once.

   Four smaller departures, recorded in the commits: the tier pick is `DENSE_RANK` rather than
   `ROW_NUMBER`, which is what "one tier wins for a type and its whole list is taken" actually
   needs, `ROW_NUMBER` having kept only position 0 of a composite key; the argument-rooted head is
   two head rules rather than one, since the slots in scope differ between a field-site and an
   argument-site `@condition`; the use-site key carries its components beside it for the reason
   `intent_argmapping_pair` exists; and an ordinary binding is now a row with `node_id_declared`
   false rather than an absence, which moves the meaning of absence from "nothing to decode here" to
   "this path bound nothing at all".
3. **Rejections.** *Shipped, as one detection view of three arms plus a fourth derived in Java.*
   `intent_argmapping_projection_defect`, `ArgmappingProjectionDefects` in `rewrite/derive`, and
   `StoreDetections` carrying both rule families into the error stream. The bare form, the missing
   `typeName:` and the unknown key column now fail the build at every `argMapping` site, and a
   resolvable projection defers rather than emitting nothing. The silent-base64 hole is closed.

   Four decisions worth reading, none of which this plan prescribed.

   **The verdicts are three, not four, and the trailing-segment count alone chooses.** Zero trailing
   segments means the author never asked for a projection, so `BARE_NODE_ID` fires whether or not
   the directive names a type; one means they did ask, and the resolution either succeeds or says
   what stopped it. That makes the arms disjoint by construction with no precedence rule, and it
   keeps a bare untyped `@nodeId` from drawing two errors for one entry: naming the type is a second
   clause of one remedy, not a second defect. Two or more trailing segments is deliberately not an
   arm at all, the walk rejecting a scalar traversal before and after stage 5's widening, so an arm
   here would double-report.

   **The unwired-site arm is Java's, not the view's.** Whether an emitter reads a resolved
   projection is a fact about this codebase and not about the author's schema, and a view asserting
   it would be claiming something it cannot see. `ArgmappingProjectionDefects.EMITTING_SITES` holds
   it, empty today, beside the enum that names all eight `site` values so a member can neither be
   misspelled nor forgotten. It shrinks as stage 5 wires sites rather than being deleted.

   **The case-fold question this plan parked is settled, and the answer is one fold, not four.**
   The crossing is an authored `argMapping` segment against a resolved key column, so
   `graphitron_argument_path_segment` gains `segment_name_upper` under the ordinary rule, which is a
   fold on the authored side of an authored-meets-catalog comparison. The key-column side does not
   get one, and the reason is a rule three shipped comments already state
   (`sql_column.column_name_upper`, `sql_table.table_name_upper`,
   `sql_constraint.constraint_name_upper`): a comparison wanting a fold on both sides reaches it by
   joining the owning base relation on its key, never by having a derived view forward it.
   `intent_resolved_node_key_column` is a reduction, so the only way to hand its fold to a consumer is
   to expose one on it, which is what this first shipped and then reverted: no view in this schema
   exposes an `_upper` column, and this stage is not the place to become the first. That the relation
   is a pick across three tiers is *not* the reason, and the earlier wording that gave it as one is
   withdrawn: `intent_spelled_table` is a union across as many arms with no single owning relation
   either, and it reads each arm's own stored fold internally without trouble. What that view does not
   do is expose one, because what it hands out is a resolved table rather than a spelling. This
   relation hands out a spelling, and whether that is the right payload is R731's question rather than
   this stage's. That side is therefore folded at the crossing, in
   `intent_resolved_node_key_projection`, and that view is now the only place the match is spelled at
   all: stage 3's unknown-column arm states the defect as *the absence of a projection row* rather
   than repeating the predicate, which is a strictly better shape than the one first written and the
   reason the two cannot drift. `intent_resolved_node_key_column.column_name`'s comment now says why
   it exposes no fold, so the next reader does not mint one.

   One correction owed on that comment, and it is this stage's to make rather than R731's. It
   currently gives the three-tier pick as the reason no fold is reachable, which is the sentence
   withdrawn above; the reason that survives is that no view exposes a fold and this relation hands
   out a spelling rather than a resolved column. Rewrite it in those terms and point the adjacency at
   R731 so a reader who wonders why finds the question rather than a closed door.

   Two neighbouring things were deliberately left alone. `intent_node_metadata_defect`'s
   `KEY_COLUMN_UNRESOLVED` arm keeps its four per-row `UPPER` calls, and
   `sql_node_key_column.column_name_upper` stays unminted: both are R724's, whose Spec was filed
   while this stage was being built and whose design replaces that arm's boolean `NOT EXISTS` with a
   relation stating its own arity. Folding the arm's operands here would have been the weaker fix
   that item argues against, and minting its column would have taken a deliverable its reviewer
   signed off on.

   A generated column costs a hand-written insert in `FactWrites`, which `FactSink.flush`'s own
   comment already says and `WrittenStatementCoverageTest` enforces; one writer was owed and is
   there.

   **One thing the stage picked up from trunk.** The nodehood derivation landed mid-stage (R711,
   `b503a79`, now Done) and made `intent_node_type` the relation every reader of nodehood joins,
   `graphitron_node` becoming one of its two arms. `intent_resolved_node_key_column`'s `CATALOG_PRIMARY_KEY` tier read the authored arm
   and now reads the union. The widening is inert and is recorded as inert rather than as a fix:
   inference requires well-formed node metadata, well-formedness requires a declared key-columns
   list, and that list is what the `JOOQ_METADATA` tier above answers with, so an inferred node type
   always resolves on the higher tier. `ResolvedNodeKeyColumnTest` pins that, which is the case that
   will speak up if inference ever loosens.

   **`intent_argmapping_pair` carries the owning application's source position.** Every one of the
   eight arms reaches one by an inner join on its own key, so a detection locates its message
   without knowing which of the seven relations the pair came from, and a repeatable directive's
   second application points at its own line rather than at the field heading above it.

   One thing was written and then removed: the view rendered the node type's key columns as a
   comma-joined candidate list and the Java split it apart again, which is the one move this
   schema's rules forbid a reader. The candidates are a join to
   `intent_resolved_node_key_column` instead, in key order, which is also what
   `Rejection.unknownNodeIdKeyColumn`'s typed candidate list wants. That factory existed with no
   production caller; the unknown-column arm is its first.

   Testable *before* stage 5's widening, which is what made the ordering work: capture reads SDL,
   and `ArgBindingMap.of` returns a typed `Result.PathRejected` folded into the error stream rather
   than throwing, so a projected spelling the walk still rejects is captured verbatim and reaches
   the detections. The `MISSING_TYPE_NAME` fixture is exactly that case and fails the build twice
   today, once from the walk and once from the store.
4. **The carrier move.** *Shipped, and the byte-identity claim is measured rather than argued.*
   `RoutineWriteCommand` with its two arms, `RoutineWriteRelation` keyed by coordinate,
   `RoutineWriteCommands` producing it, and `RoutineWriteFetcherRenderer` rendering both arms in
   `render/`. `TypeFetcherGenerator`'s two dispatch arms now read a row and hand in the tenancy
   fragments; the two leaf-reading fetcher bodies are gone. The 796 files the sakila example
   generates are byte-for-byte identical across the cutover, checked by generating them on both
   sides of the change rather than inferred from a green test tier.

   Four decisions worth reading.

   **The plan said the error channel is a pure-data ref to borrow. It is not, and the reason
   matters.** `ErrorChannel` exposes `List<GraphitronType.ErrorType>`, so putting it on the borrow
   dial would admit the whole sealed type hierarchy into the surface
   `PackageImportDirectionTest.BORROWED_COMPONENT_CLOSURE` pins, which is exactly the "render holds
   no fact hierarchy" rule the guard exists to keep. What a catch arm actually emits from a channel
   is the mappings constant's *name*, so the command carries `ErrorDispatch`: two arms, the
   redacting one and the localContext-routed one, the latter holding that name and the two unit refs
   it calls. That is the plan's own "or a plain string" clause, reached by a route the plan did not
   anticipate.

   **The two tenancy fragments arrive from the shell, not the row.** `RootLauncherRenderer` already
   takes its batched-dsl declaration and its service call that way, with the stated reason that a
   tenancy binding's declaration form is classification-side emission; the same reason applies
   verbatim here, so the renderer takes the declaration and the localContext tail as
   `CodeBlock`s. The leaf is read for that and for nothing else.

   **Four emission fragments moved to `render/` rather than being copied into it.** The result
   envelope (`FetcherResult`), the carrier sentinel (`RecordSentinel`) and the two catch-arm
   dispositions (`ErrorDispatchFragments`) are shared with the unmigrated hosts, which now delegate
   to them, so each emitted form keeps one spelling while families cross the seam; this also
   collapsed a pre-existing duplicate of the result envelope between two legacy generators. The
   key-IN predicate went the other way: it had exactly one caller, so it is private to the renderer,
   carrying forward the note that a reentry companion resolves its correlation through its launcher
   row instead.

   **One self-caught defect.** The relation first carried the case-folded method census its sibling
   relations carry. That census exists because `rows` plus upper-camelling is not injective; a
   fetcher entry point's name is the field's own, so distinct coordinates always mint distinct
   methods and the census is provably vacuous, while the *folded* form is worse than vacuous: it
   rejects `rentFilm` beside `rentfilm`, a schema that emits two perfectly legal Java methods. Both
   are gone, the reason is in the relation's comment, and a test pins the admitted pair so the
   census cannot come back by analogy.

   Two pins moved as the Spec → Ready review predicted. `CommandSeamRatchetTest`'s
   `PLAN_LEAF_REFERENCES` rose 128 to 139, the producer's total nine-arm mutation switch plus its
   two narrowings, which is the price of membership living in one place. `HierarchyKindRegistryTest`
   gained the two new sealed hierarchies as commands. `GENERATOR_LEAF_CASE_PATTERNS` did not move:
   the two dispatch arms stay, only their bodies left.
5. **Grammar and emit.** *Partly shipped: the grammar widened and the `@routine` site emits and
   executes. The `@service` and output-field `@condition` sites remain, and defer honestly until
   they land.*

   What shipped. `ArgBindingMap.of` admits one segment past an `ID`; `ResolvedKeyProjections` reads
   the projection view; `KeyProjection` and `KeyProjectionRelation` carry it command-side;
   `KeyProjectionCommands` joins it onto the walked model's node types; `ProjectedKeyHost` and
   `ProjectedKeyReads` render the decode-and-project read; `EMITTING_SITES` holds `ROUTINE`. The
   sakila example carries `Mutation.rentFilmPayloadProjected`, whose round trip proves the decoded
   key reaches the database as a key.

   Six decisions worth reading, four of which the plan did not prescribe.

   **The widening opens a hole the plan did not name, and closing it is a fifth verdict.** The walk
   admits a segment past an `ID` and asks nothing about the directive, because it has to: a path's
   head is a slot reached through a name-to-type map, which carries no directives, so an argument
   head could not be asked whether it declares a decode and a rule the two path positions answered
   differently would be worse than one they share. So an `ID` carrying no `@nodeId` is now admitted
   where the walk used to reject it, resolves no projection, and would reach an emitter as a segment
   nothing had judged. `UNDECLARED_NODE_ID` is that arm, and its `named_type = 'ID'` predicate is its
   disjointness rule rather than a convenience: on any other leaf type the walk still rejects, so
   widening past `ID` would double-report. This is the open question stage 3 parked, and the answer
   is that the detection is not optional but forced.

   **The six-call-site audit's finding is that no site needs a change.** Each site's post-resolution
   leaf check reads `ServiceCatalog.resolvePathLeafType`, which returns null the moment a path
   descends through a non-input-object, and every consumer of a null leaf type passes through rather
   than rejecting: `RoutineDirectiveResolver.leafTypeGate` returns early, and `argExtraction` reaches
   `WireCoercionResolver.checkScalar`'s null arm and yields `Direct`. The obligation was therefore a
   test obligation exactly as the plan said, and it is met per site at the pipeline tier, the sixth
   site (the path-step `@condition`, which resolves against an empty slot map) meeting it one step
   earlier through the walk's own unknown-slot rejection.

   **The projection is handed to a renderer beside the command row, not folded into it.** The plan
   said join it onto every command row that carries a routine call. A routine call reached through a
   `JoinStep` belongs to whichever command owns the join path, so that would have put the same map on
   four unrelated row families and still left the child-side hop reading someone else's copy. What
   ships instead is a `command/` relation handed down the path the per-class helper registries already
   travel, which keeps `render` reading only command-package data while the lookup stays one value a
   renderer never has to assemble.

   **The pre-statement change is a sink, not a signature.** The plan asked whether
   `PathFragments.emitTableExpression` should propagate `emitCall`'s new pre-statements to its own
   callers or keep a per-read call with a comment. Neither: `emitCall` takes a per-method sink and the
   caller drains it, so no return type changes and the alias-declaration loops are untouched. The
   decode depends on an argument and nothing else, so its declaration hoists to the top of the method,
   which is what makes "materialise once" cheap and also what keeps the decode *outside* the
   routine-write entry point's `try`: that arm catches everything and routes it through the field's
   error channel, where a malformed node id has no business.

   **The site-keyed deferral does not cover an unevenly wired site, so the plan gates it.**
   `EMITTING_SITES` is keyed on the directive and cannot see that one site's emitters are wired
   unevenly; the child-side routine hop is exactly that case. `EmitPlan` therefore refuses a plan whose
   projected binding sits at a coordinate holding neither a routine-write row nor a launcher row
   sourced from a routine chain, asked as row presence against the two relations rather than
   re-derived. The two gates are complementary: this one runs before any plan exists, that one cannot
   see the directive.

   **The descent helper's leaf cast had to become conditional, and the example's compile step is what
   said so.** A projected leaf's wire read is untyped, the decode helper guarding the wire shape
   itself, so the descent registers with an `Object` leaf type and the helper's closing
   `return (Object) value1;` casts a local already declared `Object`. That is a redundant cast, which
   a consumer compiling emitted sources under `-Werror` rejects; the pipeline tier could not see it
   and the sakila example's compile step did, which is the gate that surface exists for. The cast is
   now skipped at an `Object` leaf. Worth noting beside it: the registry's suffix disambiguation
   fired for real in the example, `argInputCustomerId` walking to `Integer` for the unprojected
   mutation and `argInputCustomerId2` to `Object` for the projected one, which is two helpers over one
   path rather than a collision.

   **The plan's own site accounting was short by one.** It lists `@routine`, `@service` and the
   output-field `@condition` as stage 5's sites and the input-field `@condition` plus the three path
   steps as the residue, which is seven of eight: the argument-site `@condition` is unaccounted for. It
   resolves a leaf (the pipeline-tier cases prove it) and its emitter is the conditions class's, so it
   belongs with the output-field condition rather than with the residue. Wire the two together.

   What remains: the `@service` site (`ArgCallEmitter`'s `NestedInputField` arm), the two
   conditions-class sites with the decode helper body hosted there, and the compilation-tier assertion
   about which class hosts it. One gap is filed rather than fixed: a
   projected column whose Java type the consuming parameter cannot take is a consumer compile error
   rather than a graphitron rejection, the coercion gate having passed through on a null leaf type. It
   is loud rather than silent, so it is a wording problem rather than a correctness one, and it has its
   own Backlog item.

   Exit, restated for what is left: the projection emits and executes at every site whose emitter
   reads it, and `EMITTING_SITES` names exactly those.

**Why this lands as one item rather than several.** Stages 3 and 5 are two halves of one
author-facing change: stage 3 rejects a spelling and stage 5 makes the replacement spelling work.
Shipping 3 alone would leave an author told to write something the generator does not yet accept,
which is a worse state than either end. The staging exists so the work is verifiable in order, not
so the stages ship to consumers separately.

Stage 4 is the one stage that would survive being split out, since it is a refactor with no
author-facing edge and no dependency on stages 2 and 3. It is kept here because it is the reason
stage 5 has a carrier at all: split out, it becomes a refactor with no stated client, and the next
session to route a routine-call fact would face the same wrong-carrier fork this item already hit
once. If it has to be lifted later for scheduling, lift it as its own item and make stage 5 depend
on it, rather than letting stage 5 thread the projection through a leaf.

The site-keyed `deferred` arm from stage 3 is what keeps that honest while stages 4 and 5 are in
flight and afterwards: a projection that resolves at a site whose emitter is not wired says so,
rather than emitting nothing or emitting the raw base64. When stage 5 lands the `@routine`,
`@service` and
output-field `@condition` sites, the arm shrinks to the `site` values that carry pair rows but no
emitter: the input-field `@condition`, and the three path-step sites (which resolve no leaf today
and so can only ever defer). It stays as the standing enforcer for whichever site is wired next.
`@tableMethod` is deliberately absent from that list: it is a legacy directive the rewrite does not
declare, so it has no relation, no `site` value and nothing to defer.

## Open questions

* ~~What the plan-local projection relation looks like in `EmitPlan`.~~ *Settled in stage 5, and not
  as a plan-local relation: it is a `command/` relation handed to a renderer beside the command row,
  for the reason that note gives.*
* ~~How the projection reaches the child-side routine hop.~~ *Settled in stage 5 by measuring rather
  than plumbing: it does not reach it, and `EmitPlan` refuses a plan whose projected binding sits at
  a coordinate no wired emitter owns. A `deferred` `site` value would not have covered it, the site
  vocabulary being keyed on the directive rather than on which of a directive's emitters is wired.*
* ~~Whether `PathFragments.emitTableExpression` takes the pre-statement change.~~ *Neither arm: the
  pre-statements go to a per-method sink the caller drains, so no signature above `emitCall` changes.
  See the stage-5 note.*
* Whether a `@nodeId` input field that nothing consumes should warn. Today it is silently
  ignored wherever no consumer reads it, which is how the `TEXT` case above stays invisible;
  the bare-form rejection closes it at the `argMapping` sites only. A general "declared and
  unconsumed" warning is a larger question and belongs in its own item if anyone wants it.
