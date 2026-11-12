---
id: R876
title: "Expensive derived reads are a modelling defect: every rule needs an owner, and once ownership is computed the derivation gatherer is unearned and meta_materialize has no subject"
status: In Progress
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-28
last-updated: 2026-11-12
---

# Expensive derived reads are a modelling defect: every rule needs an owner, and once ownership is computed the derivation gatherer is unearned and meta_materialize has no subject

## Goal

**An expensive derived read is a modelling defect, so fix the shape and the cost goes with it.**
A read is dear because a fact is keyed by where it was written rather than by what it is, or because
a family describes its corpus and affords nothing, or because a rule lives somewhere no instrument
can see it. Reach for the shape first and the cost is not a thing to be managed.

**The first consequence, and the one this item was filed for, was ownership.** A registration is not
a thing to be justified or retired one at a time; it is what a rule with no owner gets given, so that
something somewhere refreshes it. Give every rule an owner and there is nothing left for a register to
schedule. A rule reading one family's facts moves into that family. A rule crossing families has an
owner too, the gatherer that runs last, and that gatherer's refresh plan is not a register; it is
what every other gatherer already holds for its own family.

The crossing rules do not keep the register alive either, because the gatherer they would wait for is
not earned. No `intent_` rule reads the configuration corpus, Java sources or the compiler, which are
three of the six dependencies the derivation gatherer declares. It was created to run last and
nothing it owns needed it to, so `intent_` collapses into `graphitron` and `meta_materialize` loses
its subject rather than its justification.

When this lands, a contributor adding a derived relation picks a family and an owner, and there is no
register to add a row to.

**That consequence is delivered, and the thesis outlived it.** This item said emptying the register
outright was not reachable. R955 reached it. `meta_materialize` is gone, no
registration is left and no `_live` view is left, so the thesis holds in full rather than in shape
only. The claim about which lever to reach for first also has a direct measurement behind it: the
workload's worst reader was fixed by restating its rule, register untouched, every relation
returning identical rows.

What kept going under this number afterwards is the same thesis applied elsewhere: a directive
application keyed five ways by site, a classpath family that described and afforded nothing, a chain
resolution written as a loop no rule parser reaches, a classpath re-read once per graph. The graph
below is rooted at the thesis for that reason, with ownership as its first branch.

**A shape is not fixed while the old one still stands.** Each of those arcs replaces a relation, and
a replacement that leaves its predecessor in the schema has added a second producer of one fact
rather than removed the first. Retiring what a new shape supersedes is therefore part of reaching
this goal and not tidying afterwards: the read stays expensive for every consumer still on the old
relation, and two producers of one fact can disagree with nothing comparing them, which is the
defect this item is named for. A branch is done when what it replaced is gone.

## The plan, as a Mikado graph

The goal is the root. A child is a prerequisite discovered by trying; a node is `open` when it can
be worked, `blocked` on the node it names, or `done`, and a branch heading says what holds when the
branch is done. A node is a claim, a status and at most two sentences. Whoever picks a node up
researches it then, in the code and in the history below, and grows the graph with what they find.

This graph was rebuilt from the one at `0721c28fb`, which had grown past what could be kept
consistent. That version is the history, with the evidence, measurements and reasoning behind each
node: `git show 0721c28fb:roadmap/derived-read-cost-is-a-shape-problem.md`. Done work is not
restated here; the history and `## Retired vocabulary` keep it.

**Root: an expensive derived read is a modelling defect, so fix the shape rather than the cost.**

**Destination: a `@service` field is generated from the store alone.** Its fetcher is assembled by
emitters that each own a jOOQ query over the store, `multiset` for what nests, so neither
`ServiceCatalog` nor the walk resolves anything for it. Additive, flip, subtract; the critical path,
by node title:

```text
The walk's service path is gone
└── The service fields are emitted from the store
    └── A producer's fetcher parts are emitted from the store
        └── What an occurrence takes is a binding   (the fills only)
```

Every other branch is a supporting track: worked when it unblocks this path, or when its own cost or
a flip in flight below asks for it.

**Flips in flight.** Each place where an old and a new statement of one fact both run today, and
the node that ends it. No node adds in an area with an open subtract here unless it is that
subtract's prerequisite, and a node that adds says which line it retires.

* `graphitron_ast_*_entry` and the coordinate-keyed `graphitron_*_entry`, both every capture. Ends
  at "No `graphitron_*_entry` survives, an entry being the transcription's alone".
* `graphitron_routine_entry`, written by the anchor and by the registry decode. Ends at
  "`graphitron_routine_entry` is gone".
* `graphitron_path_element_*` beside the join path's steps. Ends at "Nothing resolves a path but the walk".
* The code reference in the twins' columns beside `graphitron_ast_code_reference_entry`. Ends at
  "The per-site copies and coordinate-keyed entries go".
* The walk's `@condition` shape refusals beside `CONDITION_OVERLOAD_SHAPE_DISAGREES`. Ends at "The walk's condition shape refusals go".
* The name-match join in `sql_name_matched_key_column` and in
  `BuildContext.synthesizeNameMatchedJoin`. Ends at "`sql_name_matched_key_column` is gone".
* Clear-and-recompute in `DerivationStratum` and the assembly steps beside mark and sweep. Ends at
  "`DerivationStratum` is gone, each step anchored by its owner".
* A field's departure table in the occurrence's source and in `graphitron_result_table`. Ends at "An object result is lifted onto a key row".
* A routine write's refusals in the store's codes and in the walk's routine classification. Ends
  at "A routine write's refusals are codes a stage writes".
* The walk's validator and `graphitron_entry_defect_site`, both judging a schema. Ends at
  "`validate` reads its verdict from the store".
* The walk's own schema beside the emitted one capture builds. Ends at "The generator builds no schema".
* `FederationLinkComposer` rewriting the link `graphitron_federation_link` states. Ends at "The generator builds no schema".
* The run's configuration in the `store_graph_` relations and on `RunContext`. Ends at "The generator reads its configuration from the store".
* `OperationMemberRelation` and `DeliveryFactRelation` beside the leaf crosswalk. Ends at "Operation members and delivery are read from their relations alone".
* The type generators rendering the emitted schema's forms while the variants carry their own. Ends
  at "The type and input generators read the emitted schema and the store".
* The `util` package's fixed classes written through the plan. Ends at "The `util` package's fixed classes are not planned".
* `FixedRuntimeEmitter` reading the store from `rewrite.generators.schema`, outside `emit`, beside the
  renderers it calls. Ends at "The fixed classes' renderers move into `emit`".
* The position columns and the `code_type` dictionary, both stating a signature's parse. Ends at
  "`code_type` and `code_type_element` are gone".
* `CodeCapture` sweeping by instant beside the class-file sweep. Ends at "A single changed class re-reads only itself, swept at the class file".
* An argument's or input field's `@nodeId` route in `NodeIdLeafResolver`'s walk and in the store's
  inferred and written paths. Ends at "The classifier reads its routes from the store, the @nodeId filter first".
* `graphitron_node_type`, `graphitron_node` and `NodeDeclaration.isNodeType`, each saying what a
  node is. Ends at "A node is what the manual says takes effect".
* Walk derivations and their store replacements, pinned by the shadow tests. Ends at "The shadow tests go with their walk side".
* The vocabulary listed twice, by the editor from `graphitron_directive` and by MCP from
  `graphql_directive`. Ends at "The MCP lists the composed vocabulary".
* `SeededStore` and capture, both building test stores. Ends at "`SeededStore` is deleted".
* A stub's own `id`, encoded by the walk's arm in `FieldBuilder` beside `graphitron_occurrence_node_id`. Ends at "An output field's node id is encoded from the store".

### Every rule has an owner, and the register has no subject `open`

Ownership leaves a register nothing to schedule, and `meta_materialize`, every registration and
every `_live` view are already gone. What is left is that every relation names its owner, so a new
relation cannot arrive without one.

* **Every relation has a `meta_relation` row naming its owner and its grain** `open`. Each
  relation listed in `undeclared-relations.txt` gets its row, or goes if nothing owns it.
* **No relation can be added without an owner** `blocked`, on "Every relation has a `meta_relation` row naming its owner and its grain". `undeclared-relations.txt` is deleted and
  `MetaDeclarationGateTest` refuses any undeclared relation outright.

### A fact is keyed by what it is, not by where it was written `open`

A fact keyed by where it was written has to be re-matched by every reader, and agrees only by luck.
When this is done a directive application is identified by its position in the document, and a
built-in directive means what graphql-java executes.

* **A redeclared `@skip` or `@include` loses to the specification, and says so** `open`. The
  anchor keeps the specification's declaration for those two whatever a document says, and the
  discarded redeclaration is a `graphql_schema_problem` row at its own position.
* **An application is named by its coordinate, directive and ordinal** `blocked`, on the two below.
  Only the `ast_*_entry` relations key a fact by file, line and column; every other relation names an
  application, and what is written inside one, by names, keeping where it was written only for a
  defect to point at, since a stable key is what spares its readers churn. `graphitron_directive_application`
  is keyed so already.
  * **A code reference below its application is a subtype of the reference** `open`, E's. `graphitron_code_reference`,
    `graphitron_argmapping_site`, `_pair`, `_landing`, `_step` and `graphitron_call_context_key` are keyed by name: the
    element coordinate, the directive and its ordinal, the owner the reference was written under (`service`, `path.0`,
    `fields.1`, `fields.1.path.0`) and, for a pair, the parameter, the written position kept only for a defect. What is
    left is a subtype per owner below the application, `PATH_ELEMENT`, `ORDER_FIELD`, `ORDER_FIELD_PATH`, referencing what
    owns it: F's path element for a path's condition, the `@order` field's ordinal for its method.
  * **The emitted applications number their own** `open`. A configured or minted application keeps
    an ordinal of its own, and the authored ones rank by position instead of copying one.
* **The schema-file extensions are a set, keyed by the extension** `open`.
  `store_graph_schema_extension` numbers each accepted extension by an ordinal, but the extensions
  arrive as a `Set` (`AbstractRewriteMojo.buildSchemaRecipe`, `RunContext`), whose iteration order
  differs from one JVM to the next, so two captures of one configuration number the same extensions
  differently: on opptak, tilgangsstyring and sis the two rows swapped positions 0 and 1 between
  runs. The filter is a set, so the relation is keyed by graph and extension, the ordinal goes, and
  a reader that ordered by it orders by the extension. A capture twice over one configuration
  writes the same rows.

### The classpath family affords what its readers ask `open`

`code_` holds every fact its readers ask for, read from the classpath it is allowed to read, and the
census it replaced is gone.

* **A class's supertypes are one code fact** `open`. Every type a class is, transitively, is one `code_` relation over every class the reading reads, and `code_throwable_supertype` and `sql_table_record_supertype` dissolve into it; the readers are `@error` handler matching, the node-id slot's supertype checks and `RESULT_SHAPES_CONFLICT`, which asks whether an object is an `org.jooq.Record` and today only recognises a table's own record class.
* **A graph's `code_` and `sql_` answers are a function of its own sources, whatever else the store holds** `open`.
  A classpath entry or catalog source is shared by every graph that reads it, so what the store holds
  for it may depend on the source alone; of two entries declaring one class name, the first in the
  graph's classpath wins, an order the store captures. Absorbs R1182: today a graph's backings change with what was captured before it, and a
  graph read after another can be given a wrong backing.
  * **An entry is read alone** `done`. Each entry is read with `readEntry`, so the store holds
    every copy of a class name; `store_graph_dependency.position` captures the graph's classpath
    order, and `graphitron_type_backing_rule`'s slot hops and pass-through test take the first
    declaring entry. Nested slots are read for the names an entry's own methods carry, so a nested
    class one entry names and only another declares is not read, accepted by Alf (2026-11-12) since
    reading it would write rows under an entry the reading did not open and cannot sweep.
  * **A graph's visible class is one relation, read by every rule that resolves a name** `open`. The
    first-declaring-entry predicate is written inline three times in the type-backing rule, and
    about 25 rules joining `code_` rows through `store_graph_source` do not apply it yet
    (`graphitron_occurrence_built_class_rule`, the condition candidates, the producer lift and
    result rules, `graphitron_path_element_method_rule`, `graphitron_scalar_class_rule`,
    `graphitron_session_handle_member`, the outbox targets, among others); nor does code reference
    resolution, which draws no row when the classpath offers two candidates. One relation of
    (graph, class name, entry), anchored, replaces the predicate everywhere.
  * **A catalog source is read alone** `open`. Not yet checked: whether the jOOQ catalog reading
    writes anything for a source that depends on the sources read beside it, or skips a source
    store-wide as the classpath reading does.
  * **What a class is by its supertypes is derived per graph** `blocked`, on "A class's supertypes
    are one code fact". Throwable, lifter, enum and parameter role are classified through
    `ClassAncestry` over the reading graph's classpath and written into shared rows, so the first
    graph's answer is everyone's.
  * **The test population shares stores across classpaths and catalogs** `blocked`, on the two above.
    `FactGraphs` shards by name alone, the library fixtures are distinct classes compiled
    with and without `-parameters`, graphitron-model's heap pin goes, and a document's `code_` claims
    read through its graph's entries rather than additively.

### A resolution keeps what a diagnostic needs `open`

Every entry defect the store reports agrees with what the generator accepts, so an author is never
shown an error on a schema that generates, and validate can then fail on them.

* **A condition method over untyped tables resolves** `open`. A condition-only element whose method takes `Table<?>` steps from where the walk stands,
  as the generator calls it.
* **sis draws no entry defect the generator accepts** `open`. Of the 34 sis drew, the 5 `OUTCOME_FIELD_NON_NULL` were ours and are fixed; the 19 `NODE_ID_TYPE_NAME_NOT_NODE` name federation stubs sis writes `@node(resolvable: false)` once graphitron reads it, the 8 `COLUMN_UNRESOLVED` on a nesting type's deprecated `id: ID!`, keys a legacy split query needed, stay refused, the generator now refusing them too, and are sis's to fix by dropping the fields (fs-plattform MR 6639), and the 2 `NODE_ID_LANDS_ON_TABLE` are fs-plattform MR 6636 (Alf, 2026-11-07).
* **An unknown `$session` member's defect states what the handle offers** `open`. A detail relation under the defect holds one row per member the handle offers, as `graphitron_entry_defect_table` holds `TABLE_AMBIGUOUS`'s candidates, and the build's message reads it instead of querying `graphitron_session_handle_member` when it renders.
* **An unresolved path element's defect names the element** `open`. `ELEMENT_UNRESOLVED` carries no detail, so `@reference(path: [{key: "no_such_fk"}])` is reported at the element without saying `no_such_fk`; the path stage states the spelling the element wrote as the defect's detail.

### The path replaces the walk it was built to retire `open`

A field's path is the join from its source table to its target table. When this is done one forward
walk, starting from the source and reading each written element once, resolves every path at every
grain into rows that say where each step comes from, where it goes and how it joins; the readings,
resolutions and links built beside it are gone.

* **An inferred join follows the key the field's cardinality allows** `open`. Where no path is written and two foreign keys join the tables, a list field follows the key its target holds and a single field the key its parent holds or a unique key on the target, so `Store.occupants: [Occupant!]!` takes `staff_store_id_fkey` and a manager field `store_manager_staff_id_fkey`, and only two keys fitting one cardinality stay `ROUTE_AMBIGUOUS`. The store's route count, the path stage that writes `graphitron_join_path_step` and the walk's `BuildContext.parsePath` apply it together, or the store and the walk disagree.
* **A round of the walk steps only its newest layer** `open`. Each round re-reads the rule from every
  start and keys, columns and stops read it again after the fixpoint, about 9 rule evaluations per
  capture and 1.4 to 1.6 s on sis at load 5 to 13; a round that steps only the steps the last one
  added, and writes their keys, columns and stops as it goes, reads each step once.
* **A broken or refused path is a defect at its position** `open`. A walk that stops, ends at the wrong
  table or has no source, an element naming nothing, and `@reference` beside `@referenceFor` or
  `@referenceFor` on a non-abstract type each draw a code at their position. No source means
  neither a result table nor a key row starts the walk.
* **Nothing resolves a path but the walk** `open`. The step hops and `graphitron_condition_method_route`
  are gone; `graphitron_path_element_*` goes last, and the `@reference` page says a path is read from
  source to target only.
* **Every field has a path, the written one or the one discovery implies** `open`. Inference is
  stored as path rows; what is left is the editor's omitted-`@reference` hint (`InlayHints`), which
  fills in from those rows rather than discovering for itself.

### Arguments and input fields are resolved as fields are, from a target model `open`

Every path a schema states or implies is a row in the store, and a field left without one is a
defect at the field. A path belongs to an output field: its own path runs from the table of the type
it is declared on to the type it returns, one path per pair of tables from its two ends; an
argument's path is anchored at the argument; an input field's exists only per occurrence under an
output field; both start on a step of their field's path, the one an author named with `as:`, or at
its landing. The rules are stated in "User documentation (first-client check): paths" below, the
relations in "Path model (draft)", and `scenarios/path-rules` asserts both. The `intent_argmapping_` relations go with "The argMapping readers read the landing, and the candidate and match go". R1099, the
author-facing navigation over output fields and its `$source`, `$path.<as>` and `$target`, builds on
this branch rather than modelling inputs again. Taken up on 2026-10-31. The relations it has
retired, the argument step targets, `graphitron_field_path` with its methods and the field reference
step hops, were among the costliest statements the build planned, about 265 CPU-seconds of
preparation over a `graphitron` test phase (`roadmap/audits/2026-11-02-where-the-build-spends-its-cpu.md`).

* **A single-table implementer without its own `@table` is a defect** `open`. The manual requires
  an implementer of a `@table` interface to carry the same `@table`; without one it has no
  `graphitron_tabletype` row and its fields no source, and nothing says so.
* **A connection is recognised by the specification's shape** `open`. The Relay Cursor
  Connections specification states the shape: a connection has `edges` and `pageInfo` and may have
  more fields, an edge has `node` and `cursor` and may have more, and `PageInfo` has
  `hasPreviousPage`, `hasNextPage`, `startCursor` and `endCursor`; `nodes` and `totalCount` are
  conventions graphitron adds and facets graphitron's own. The shape is stated:
  `graphitron_connection_position` is its vocabulary and `graphitron_field_position` the position
  each field of a recognised connection, edge and page info fills, written at macro expansion;
  the producer rule's `COUNT` and `FACETS` arms read the position. The connection and edge rules
  keep their literal `edges` and `node`: read through the vocabulary the name is no constant to the
  planner, which then probes `graphitron_field` by graph alone instead of seeking
  `graphitron_field_name_ix` (measured on the sakila store's plan). What is left is recognition itself. Today's test, `edges` to an OBJECT edge to `node`, is narrower than the specification although
  `graphitron_connection`'s rationale calls it the specification's own; recognising
  the full shape changes what is recognised, so it goes with `asConnection.adoc` and Alf's ruling.
* **An authored connection type that departs from the shape is a defect** `blocked`, on "A connection is recognised by the specification's shape".
  A type used as a connection that lacks a required field raises a defect at the type, and the
  manual states the refusal. What an extra field binds is the open question below.
* **A connection's `totalCount` is a producer of its own** `open`. It starts from the
  returning producer, whose path, conditions and argument values say how the result set was
  produced, and is counted by running that producer's statement where graphitron selected it; it
  runs only when queried.
* **A connection's facets are a producer of their own** `open`. The `FACETS` sibling of
  `totalCount`, with the same start.
* **A count or facets graphitron cannot run is a defect** `open`. Under a producer graphitron
  does not compose, a `@service` or an index-backed connection, a `totalCount` or facets raises a
  defect rather than generating nothing. No connection producer today is one: every page is built
  by graphitron's own statements, so this lands with the first that is not.
* **The participant askers read the occurrence's source tables** `open`. Four field-keyed rules
  ask whether, or across which tables, a union or interface target is read per table-bound
  participant: `graphitron_producer_rule`'s `landing`, the reader rule's polymorphic arm, the
  `ORDERING_ACROSS_PARTICIPANTS` arm and `graphitron_occurrence_producer_result_rule`'s polymorphic
  flag (a fifth, `graphitron_field_scope_table`'s `PARTICIPANT_TABLE`, went with the scope-table
  chain). Each joins
  `graphitron_tabletype` (resolved) or the written `@table` with the type's members; no relation of
  its own is owed, since that would restate stored facts at a grain that cannot hold a nested type
  bound per occurrence (Alf, 2026-11-12). Which tables an occurrence's statement reads is
  `graphitron_occurrence_source_table`; the askers move onto it as the field-keyed family
  dissolves, "whether it runs anything" reading the written claim and "which tables" the resolved
  binding. F's "A polymorphic type's members are stored" touches the same rules, so F is told
  before either moves one.
* **A connection's producer returns the connection, and its fields read it** `open`.
  A connection's producer returns an instance of a class graphitron generates for the connection
  type, holding the page and closures over how the result set was produced, and each field of the
  connection, its edge and its page info is registered to read one member (Alf, 2026-11-11:
  generated code changes authorised, it simplifies the model and makes it more powerful; every
  field is wired, `emitter-conventions.adoc` § Return types). The store states which position each
  field fills; the class names and members are the emitter's. `ConnectionHelper`'s resolvers, the
  per-type fetcher delegates and the maps behind `pageInfo` and facets go.
  The design, with the principles-architect:
  * One class per connection type and one per edge type, not generic: graphql-java registers a
    fetcher per type and field, so what differs per producer (table, condition, ordering, the
    tenant's `DSLContext`, how a hit's `node` is fetched) is supplied by the producer as closures
    over the values it already bound, never as a type parameter or an argument bag. A page's
    elements are jOOQ `Record`s, as the generated code holds them (Alf, 2026-11-12).
  * A cursor is computed from the element and the ordering, a keyset position, never from the
    element's index in the page. Cursor encoding and decoding, whose bytes the manual promises, and
    the batch-count memo stay as one small generated utility.
  * The template is a constant vocabulary of positions (`EDGES`, `NODES`, `PAGE_INFO`,
    `TOTAL_COUNT`, `FACETS`, `NODE`, `CURSOR` and the four page-info positions) and a relation at the
    field's grain saying which position a field fills; the emitter maps a position to a member. The
    connection rows of `graphitron_runtime_class` and `graphitron_runtime_member`,
    `graphitron_occurrence_producer_result.runtime_kind` and `RESULT_EXPRESSION` go; the earlier
    idea of binding positions to runtime members is withdrawn.
  * The classes are written by an emitter reading the store, counted by `EmissionCensusTest`; the
    plan layer's connection units shrink rather than grow.
  * `PageInfo` is wired to the shared generated `util.PageInfo`, each specification field it
    declares registered to its member; the specification's fields sit on
    `GraphitronType.PageInfoType` until "A connection is recognised by the specification's shape"
    states them as positions, and the wiring's leaf arms in the schema-shape planner and the
    registrations emitter leave with the connection units. A field an author adds to `PageInfo`
    beyond the specification is refused (Alf, 2026-11-11); the refusal is owed, with an existing
    code if one fits and the reference page that states it.
  * The facets types are wired to `util.Facets`, a record per facets type (one component per facet)
    and per facet value type (`Object value`, `int count`: a facet value type is shared by columns
    of different Java types). Rendered from a facets command row the plan produces off each faceted
    connection's facet specs, the source the facets delegate reads too, since the store's facet
    relations are views over views today; the command row goes when R606 stores the facets
    derivation and a store-reading emitter writes the records. The per-connection facets delegate builds the record from
    the helper's buckets, and its registrations arm, its schema-shape planner arm and its
    compile-graph edges leave with the connection units.
  * `ConnectionHelper.totalCount(env)` is generated wiring, not consumer API: `runtime-api.adoc`
    names it only as an example of a helper using the `graphitronContext(env)` shim, and no
    consumer calls it. Removing it changes nothing an author or consumer sees, so that commit is a
    `refactor` (Alf, 2026-11-12); the `runtime-api.adoc` sentence is reworded to the closure's count.
  * `totalCount` and facets read the producer's arguments: the count's condition and each facet's
    condition are the arguments bound (`RootLauncherRenderer.facetBindings`), built eagerly by the
    producer today whether or not they are selected (Alf, 2026-11-11). So the closure carries the
    producer's bound arguments and builds those conditions when its count or facets member is read.
    An author's own fields on a connection or an edge type, and whether they may read an argument,
    are left for later (Alf, 2026-11-11).
  * `PageInfo` is one generated class shared by every connection, and each facets type and facet
    value type has a generated class of its own (Alf, 2026-11-11; `emitter-conventions.adoc`
    § Return types).
  * Every page a producer builds carries its count. A union or interface field with no table-bound
    participant, which the polymorphic emitter answered with an empty page and no count, is
    refused as `FIELD_WITHOUT_FETCHER` wherever graphitron would fetch it (Alf, 2026-11-12): the
    reader rule no longer reads it as `NESTED` and the producer rule no longer splits it, and its
    defensive branches are generator invariants; an interface's own `@table` does not count, so one
    carrying it that no table-bound type implements is refused as well (Alf, 2026-11-12). The page a tenant-routed batched fetcher answers
    for a parent whose key cannot match counts 0 (Alf, 2026-11-12). `FIELD_WITHOUT_FETCHER` is
    stated in `diagnostics-glossary.adoc` under "Named structural errors" (Alf, 2026-11-12).
    The askers of "read per table-bound participant" are listed in "The participant askers read the
    occurrence's source tables".
    "A connection is recognised by the specification's shape" states the positions before the
    classes.
  * The classes are written: `ConnectionsQuery` reads each connection and edge type and the
    positions its fields fill, and `ConnectionsEmitter` writes `Connections`, a class per connection
    type and per edge type with a member for each position its type fills. A connection is built
    from its elements, whether a next or a previous page exists, the cursor function and, where its
    type fills them, a count and a facets function of the request; an edge from its element and the
    cursor function, since one edge type serves every connection paging through it. The page info
    and facets classes are named by the caller. No unit emits them yet, so no consumer sees them
    before the producers return them; `ConnectionsEmitterTest` compiles and drives them over
    `scenarios/connection-positions`, and the emission census compiles the sakila example's.
  * **The producers return the connection type's class** `open`.
    One change, since a connection type may have several producers: a global `util.Connections`
    unit rendered from the store (no plan-layer row; one global unit for now, Alf 2026-11-12, at
    the cost of coarser dev-loop recompiles), the five construction sites building the
    closures (`Scoped` and `BatchCounts` becoming how a producer makes its count function, moved
    with the cursor codec into the small utility), the registrations reading each position's member,
    and the per-type fetchers and `ConnectionHelper`'s resolvers going. A `refactor`:
    `ConnectionHelper` is generated wiring, not consumer API. Pinned by `CursorEncodingTest`, `CursorFormatExecutionTest` and the count, facets and
    batched-count execution tests; `emitter-conventions.adoc`, `connections.adoc`,
    `runtime-api.adoc`, `runtime-extension-points.adoc` and `code-generation-triggers.adoc` change.
  * **The runtime connection vocabulary goes** `blocked`, on "The producers return the connection type's class".
    The connection runtime rows, `runtime_kind`, `RESULT_EXPRESSION`, `ConnectionResult` and
    `ResultShape.Connection`'s helper and carrier go, with the retirement sweep.

**What the connection thread found (2026-11-11, Alf with this session and the principles-architect).**
The terms *result set*, *page*, *cursor position* and *how the result set was produced* (Alf's
*environment*) are working terms for thinking, not rulings.
* `graphitron_result` models a connection badly and works by accident: it treats each wrapper as an
  object flowing down through readers, and needs the carrier exclusion in its reader arm to stop
  that object reaching the element. Each level has a source of its own instead, narrowing: the
  connection, an element with its position, the element's row.
* What a connection's own fields beyond the specification need is what `totalCount` and facets
  already use: the table and condition the result set was produced with. The specification allows
  such fields on a connection and on an edge.
* Graphitron writing the query, cursor included, over rows of one table is an assumption, not the
  model. Where a search index or a service produces the result set, the edge (a hit: key, type,
  score, highlights) and the node (the entity) are two things, and `node`/`nodes` may be a lookup
  producer lifted from the hit's key, batched over the page, which may miss a row a stale index
  still names. Where graphitron selected the rows, edge and node coincide and the lookup is free.
* `totalCount` and facets are facts about the result set; running the producer's statement again is
  how graphitron obtains them where it selected the result set, not what they are. A search engine
  returns both in its response.

**Open for Alf and Filip.**
1. Does the 2026-11-03 ruling that a source states its tables or class and never a pointer to the
   occurrence it came from stand for a connection? The principles-architect recommends it does: the
   connection and its edge are object sources of `CONNECTION_RESULT` and `CONNECTION_EDGE`, and how
   the result set was produced is the producing occurrence's own facts, reached through the
   producer; a count or facets' start is then a fact between producers on
   `graphitron_occurrence_producer`. The thread leaned towards a source that is the connection
   itself, its result and how it was produced.
2. Is a result set a fact of its own grain, one per producer and, for a child connection, per
   parent key, or the producer's result as the runtime reads it?
3. Is a `@service` connection fused, the service handing back elements graphitron pages, or like an
   index, handing back hits whose `node` is lifted? Who supplies the cursors?
4. What do facets compute over today? Answered from the generated `ConnectionHelper.facets`: each
   facet counts under the connection's condition and every other facet's predicate, not its own,
   so facets read a variant of how the result set was produced rather than the result set. Still
   to be pinned by a test before it is modelled.
5. What does an author's extra field on a connection or an edge bind to? Left for later (Alf,
   2026-11-11).
6. Does the hit being an edge reopen R427's decision to keep search apart from connections? R427's
   question, recorded there.
* **The occurrence source is written once per depth** `open`, F's. The writer recomputes its union of rule arms for each relation it writes, so `graphitron_occurrence_source` costs about 1.6 s and its two subtypes 0.55 s on sis, against 0.5 s for `graphitron_occurrence` over the same 10,394 occurrences; a depth's rows written once, the supertype read off them, is the expected fix.
  The rules re-derive what a type is, its table and its members, so the type grain is stated as
  structure first (Alf, 2026-11-11): `graphitron_type` is the strict supertype, complete over the
  schema's types and written first after the anchor, and a table is a type, a node a table, each a
  foreign key on type and kind.
  * **A table defect is owned by the stage that decides the table** `open`, F's. `TABLE_AMBIGUOUS`
    and `TABLE_UNRESOLVED` move from `entry-defects` to `table-types`, which leaves an ambiguous
    `@table` without a `graphitron_tabletype` row, so the missing row and its refusal sit in one place.
  * **A polymorphic type's members are stored** `open`, F's. `graphitron_type_member` (container,
    member), both keyed into `graphitron_type` by kind, replaces the `graphql_poly_member` view, and
    the per-depth rules and `graphitron_occurrence_producer_result_rule` read `graphitron_tabletype`
    and it in place of their type, member and `NOT EXISTS` combinations, priced by a quiet-machine
    A/B on `prepare_ms`.
  * **The source arms that read no parent leave the depth loop** `open`, F's. The loop holds only
    its recursion: `occurrence-producers` writes the producers and service calls ahead of it and
    `occurrence-bindings` what each occurrence binds after it. Of the source rule's arms,
    `declaringTable`, `rootRoutine`, `ownServiceRecord`, `argumentTarget`, `argumentMember`,
    `argumentWrite` and `recordParameter` read no row the parent wrote, so they could be written once
    for every depth, the loop adding only the arms that read the parent. Before that, how the arms
    exclude one another is checked, and `graphitron_occurrence_source` would take two writing
    stages, which the split by kind ruled on 2026-11-12 may or may not cover.
* **Relations nobody reads go** `open`, F's.
  A census of every relation's readers, static over the main sources and at runtime over a full
  build (`roadmap/audits/2026-11-07-relations-nobody-reads.md`), found 56 that no main code reads.
  Each is checked by hand before it goes, its readers in the DDL included (a subtype's foreign key,
  a view or rule view naming it), since the census holds false leads.
  Gone 2026-11-12, each rechecked on trunk first: `intent_poly_member`; the defect details
  `graphitron_entry_defect_method` and `graphitron_entry_defect_parameter`, the parameter rule view
  answering the defect rule itself; and the scope-table chain, `graphitron_field_scope_table`,
  `graphitron_argument_scope_table` and `graphitron_input_field_resolving_table` with their rule
  views, `intent_field_navigated_type` and their three `DerivationStratum` steps, which leaves
  `ClassificationDomainCapture` the stratum's only step.
  * **Capture written twice and read by neither copy goes** `open`, F's. The `graphitron_ast_*`
    entries and the decode copies beside them that the audit's second group lists, a family at a
    time, each family's fact shown to reach its readers another way or its reader named. The
    `@service`, `@externalField`, `@pivot`, `@defaultOrder` and DATABASE handler decodes went
    (2026-11-12), their applications standing in the `graphql_ast_` entries.
  * **Each relation written ahead of its reader names it** `open`. The audit's third group, among
    them `graphitron_occurrence_source_object`: the owner names the reader in the graph, or the
    relation goes. `graphitron_entry_defect_method` went (2026-11-12), and `graphitron_occurrence_parameter`
    went with E's PARAMETER deletion.
    `graphitron_fetcher` is read: `graphitron_producer` and `graphitron_reader` key into it as its
    subtypes and `graphitron_entry_defect_rule` reads it, a reach a census of Java readers does not
    see; it goes with them under "The producer's readers read the occurrence producer, and the field-keyed family goes".
    E's, answered (2026-11-07): the key row is gone, its fill `graphitron_occurrence_producer_lift`
    read by `FrameQuery`, `RoutineWriteQuery`, the sources, the key defects and the outbox, and by
    "A split carries the target's key where the source states it"; `graphitron_occurrence_producer_write`
    by "A `@mutation(table:)` that disagrees with its return's table is a defect" and by the write
    producer's readers under
    "The producer's readers read the occurrence producer, and the field-keyed family goes";
    `graphitron_fetcher` goes under that same node, the fetcher being
    derived off `graphitron_occurrence_producer`, which the frame's query reads already.
    `graphitron_occurrence_source_object` is F's, the source being F's.
  * **Settings and catalog facts nothing reads go** `open`, F's. The audit's fourth group, among
    them the `store_graph_` settings and `sql_table_record_supertype`, each checked against the
    generator's configuration first. `store_graph_lint_disabled_rule` and
    `federation_version_directive_location` went (2026-11-12), the generator reading its lint
    settings off its configuration; `store_graph_supergraph` stays, the `<supergraph>` parameter's
    only copy and named as its reader by R643's peer surface.
* **A path is keyed per pair of tables** `blocked`, on "A walked landing judges `PATH_LANDING_UNKNOWN`".
  Landed but for its child.
  * **A walked landing judges `PATH_LANDING_UNKNOWN`** `open`, F's.
    A scalar's written path whose last element names no table is `PATH_LANDING_UNKNOWN` at its claim.
* **`scenarios/path-rules` runs** `open`. It runs as a scenario; the condition cases of the six
  documents the draft below lists have not moved into it.
* **The readers read the paths, and the old path relations go** `blocked`, on "`scenarios/path-rules` runs".
  The condition, lookup-key, node-id and `argMapping` readers move, and the relations
  "Path model (draft)" lists as replaced go.
* **The manual states the path rules** `blocked`, on "The readers read the paths, and the old path relations go".
  `reference.adoc` carries them; `referenceFor.adoc`, `how-to/polymorphic-types.adoc` and the
  examples rendered from query files over `scenarios/path-rules` remain.
* **Every defect code is stated on its reference page** `open`. The rule is
  `docs/manual/reference/index.adoc`'s, and no page under `docs/manual` names `LIST_OVER_ONE_VALUE`,
  `MANY_KEYS_INTO_ONE`, `OUTCOME_IN_A_LIST`, `WRITE_RESULT_HOLDS_ONLY_KEYS`, `REREAD_OF_DELETED_ROWS`,
  `CONNECTION_RETURN`, `WRITE_TABLES_DISAGREE`, `CONDITION_ON_WRITE_RESULT`,
  `ARGMAPPING_PATH_UNRESOLVED`, `SCOPE_MEMBER_UNKNOWN`, `ARGMAPPING_SOURCE_MISFIT`,
  `NODE_ID_LANDS_ON_TABLE`, `NODE_ID_LANDING_TYPE_DISAGREES`, `NODE_ID_KEY_ARITY_EXCEEDS_SLOT`,
  `NODE_ID_MEMBER_NOT_NODE_TYPE` or `MUTATION_WITHOUT_VERB`. `mutation.adoc` still advertises
  `UPSERT`, which `MUTATION_UPSERT` refuses, and a nesting type's id rule (a column unless the type
  implements `Node`) is not on its page.
* **Each node-id rule is an entry defect** `open`. The store's checks on `@nodeId` and
  `@referenceFor` become codes in `graphitron_defect_type` and arms of `graphitron_entry_defect_rule`,
  each row at the application's written position, so the editor shows them beside the build:
  * the participant set: a name no consumer's participants carry, and a consumer returning a single
    table, at the input-field and the argument site;
  * a `@referenceFor` on a slot without `@nodeId`, which only the walk refuses today.

  The slot and container rules are already entry defects, the `NODE_ID_` codes over
  `graphitron_occurrence_node_id_slot`, with the members a container defect names in
  `graphitron_entry_defect_member`, and the landing's are `NODE_ID_LANDS_ON_TABLE` and
  `NODE_ID_LANDING_TYPE_DISAGREES`, each branch judged on its own route. `ReferenceForParticipantDefects`
  reads the rows. The walk's copies of the same refusals go with
  them, so an author meets one error rather than two: the four container refusals in
  `BuildContext` and `NodeIdLeafResolver`, and the refusal of `@referenceFor` without `@nodeId` in
  `BuildContext` and `FieldBuilder`. Two cases are settled by fact documents on the way: a
  polymorphic id at a parameter bound through `argMapping`, which only the store may check, and the
  participant set's single-table message at the argument site, which has no test.
* **The node-id rules read the path model, and a `@referenceFor` route's landing is checked** `open`.
  The landing and participant arms read the walk from the using field's target. Neither side
  checks where a `@referenceFor` route lands today; it becomes a landing arm here.
  `NodeIdDecodeCoverage` stays, comparing the census with the walk's ledger, until the walk goes.

### A reference resolves where its directive is decoded `open`

A directive naming Java code or a routine is resolved once, by the graphitron anchor against the
classpath and catalog, and every reader reads that resolution rather than a view of its own.

* **The walk's condition shape refusals go** `open`.
  `ServiceCatalog.admitConditionShape` refuses a `@condition` set with `AmbiguousMethod` where
  `CONDITION_OVERLOAD_SHAPE_DISAGREES` already says the same. A path step's condition no longer
  folds declarations its call `method(sourceAlias, targetAlias)` cannot reach, which refused
  `agreeingOnBoth(Customer, Address)` beside the same with a `String` though the store keeps the
  two-parameter declaration.
* **The per-site copies and coordinate-keyed entries go** `open`. The twins lose their
  class, method and argMapping columns.
* **`sql_name_matched_key_column` is gone** `blocked`, on the child below. With `NameMatchedKeys`,
  and `fact-model.adoc` stops citing it; the walk's name-match step reads it today and moves too.
  * **A routine result's name-matched pairs are written where a join asks for them** `open`. By the
    anchor, keyed to the element or the routine write and to both `sql_column` rows, read by the
    walk's step rule and `RoutineWriteQuery`.
* **A scalar routine is a path element that lands on a value** `blocked`, on the children below.
  A scalar function reads at a root or projects in its parent's statement. jOOQ's routine model and
  what the store takes from it are in `roadmap/audits/2026-10-22-jooq-routine-model.md`.
  * **A method's form is read off its signature** `open`. The `code_` family states, for every
    public static method whose signature is jOOQ-shaped, the form it offers: execute (`Configuration`
    first), value (a `Field` returned, over values or over fields), table (a `Table` returned) and
    aggregate. A method written to these rules is available as if jOOQ had generated it, whoever
    wrote it, so jOOQ's holder classes are read for their members like any other class.
  * **A routine is enriched by the database construct behind it** `blocked`, on "A method's form is read off its signature". The `sql_` family states each routine jOOQ generated, read from its
    `AbstractRoutine` instance found by type: qualified name with package, parameters with mode,
    `DataType` and default, and return, tied to the holder methods that call it by name and arity. A
    generated class that does not load is a stored fact, not a skip.
  * **`@externalField` is `@routine`** `blocked`, on "A method's form is read off its signature". A
    value form bound to the parent's table; its readers move to the form, and the directive is
    deprecated later as an alias.
  * **A table-valued function returned as a scalar is a defect** `blocked`, on "A routine is enriched by the database construct behind it". It can land before the reads. Today
    `Query.titleFor`, a scalar over `films_for_actor`, draws nothing.

### A service call is resolved in the store and emitted from it `open`

A `@service` field's call is the store's answer: which method, on which holder, with what filling
each parameter, so the generator reads it and `ServiceCatalog` resolves nothing. A service class is
one whose instances graphitron can build; its public methods are service methods, and a public
static method needs no holder.

* **The generated jOOQ classes are read with their members** `open`, off the critical path. The census's header-only
  package goes, so tables, records, `Routines`, package classes and DAOs are read like any other
  class, with a sis capture timed before and after.
* **The argMapping readers read the landing, and the candidate and match go** `open`, E's. R1099's step 2 stored where an argument path lands, `graphitron_argmapping_landing`, under each occurrence of its call; what follows is its readers.
  `graphitron_outbox_key` reads the landing, the node-id readers (`ResolvedKeyProjections`, the
  `intent_argmapping_` key-column chain and its projection defects) read the node-id binding at the
  landing's path end, and `graphitron_argmapping_candidate`, its stage and `graphitron_argmapping_match` go.
* **`$tenant` in argMapping routes a service call** `open`, E's. The sigil on a pair's left side names
  the value whose tenant the call runs on, beside `@tenant`, with the same type and same-tenant rules.
  * **`@tenant` is gone** `blocked`, on "`$tenant` in argMapping routes a service call" and sis
    moving its ten uses. The directive, the tenant index's reading of it and its placement refusals go.
* **A producer's fetcher parts are emitted from the store** `open`, E's. The frame, the fills, the
  call, the re-read and the polymorphic re-read each have an emitter reading the store; what remains
  is routine writes, which take neither the re-read, today an unordered `IN` (a bug), nor the frame,
  whose error channel and tenancy their caller still hands `RoutineWriteEmitter` as values.
* **The projection units are rendered from the store** `blocked`, on the facts below.
  The store-fed lift selects through `<Type>.$project`, the projection unit `ProjectionUnitRenderer`
  renders from `ProjectionCommand` rows, which `ProjectionCommands` derives from the classified
  model; so a producer whose body the store emits still projects through units the walk decides,
  and the classified model cannot go while it does. The units are read off the store instead, the
  type's fields, their columns and their nested reaches at the occurrence, each unit's query its
  own, a polymorphic field's parent-row columns among them in the order its participants are stated
  (today `ParentRowDemand.polymorphicParentRowColumns`, kept in that order by `inParticipantOrder`), with the generated selection adapters (`SelectionOccurrences`, the per-member `restrictTo`
  a polymorphic lift calls) unchanged, the unit tests pinning what each unit selects for a given
  selection before and after (Alf, 2026-11-07). A unit restricts itself to its own type's occurrences
  of the selection, taking the shared-alias field names where it is merged into a discriminated
  select, so the four call sites that restrict before calling it today (`SelectMethodBody`,
  `MultiTablePolymorphicEmitter`, `DiscriminatedTableFragments` and the store-fed `LiftEmitter`)
  stop doing so; after the `@service` flip, which keeps the call-site restriction.
  One unit per table-bound type, its arms the occurrences of the chain the type's fields start: a
  nesting field's arms are its chain's deeper occurrences, inline in the anchor's unit, so
  `NestedUnit`, `CallWrap.Splice` and the `<Anchor><Nesting>` classes go. A result-key alias is the
  runtime result keys from the row's owner down to the field, as many as the occurrence lies deep
  below it, in place of the threaded `rkPath`; the flip is one pass over every arm, each fact
  below landed first, as its own store change pinned by a fact document (Alf, 2026-11-08).
  The store-fed discriminated lift (`DiscriminatedQuery`) refuses what this node decides: a participant
  on a table of its own, a field the interface declares off the shared table, a participant's field
  nesting an object and a cross-table field more than one step away, and it restricts each participant
  with no shared-alias names; those cases stay with the walk's emitters until the units are designed.
  * **An `@externalField` binds an expression at its occurrence** `open`. A subtype of
    `graphitron_occurrence_binding` names the method `graphitron_code_reference` resolves and the
    parameter its table fills, so the `EXPRESSION` kind has a writer and the arm a reader.
  * **A field's or argument's `@condition` is a call at its occurrence** `blocked`, on
    "A `@condition` is a call per table its occurrence binds". The overload is pinned as `graphitron_join_path_step_method` pins a step's, rather than by
    name, with whether it reads context or the session; the multiset's filter and the `dsl` closure
    read it there, and `graphitron_code_reference` is no longer read for a `@condition` (Alf,
    2026-11-09, the shape weighed with the principles review the same day).
    * **An overloaded `@service` method is pinned by its fills** `open`, off the critical path. A
      service's candidates are every method of its reference's class and name rather than the one a
      name-unique reference resolves, so an overload set the store refuses today pins the overload every
      position fills, agreeing with `ServiceCatalog`'s choice where the walk has one; a capability change.
    * **A `@condition` is a call per table its occurrence binds** `open`.
      `graphitron_occurrence_condition(graph, coordinate, bound, call, override_written)`, a call
      subtype keyed into `graphitron_occurrence_binding`, one row per occurrence carrying a
      `@condition` and per table it binds: a field's `TABLE`, root or child, one per participant; a
      scalar argument's or input field's `COLUMN`; an `@nodeId` one's `NODE_ID` at the foreign key's
      target; an input object's or a `@condition`-taking scalar's `TABLE`, so the table is the
      binding's and no view derives it. Per table, not per type: the table parameter's type picks the
      overload, so a discriminated interface on one table is one call. `call` is `condition:<bound>`
      with `CHECK (call = 'condition:' || bound)`, and readers join on `bound`, never parsing `call`.
      It carries `override_written`, the `override:` written, doomed with the consumption rule and
      kept for the transition against the review's point that it is a definition-keyed fact repeated
      per table (Alf, 2026-11-09), read off `graphql_directive_application_arg` so no reader moves onto the
      condition entries, whose two relations a reader would have to union. Its fills follow the manual's
      `@condition` reference, § How the method's parameters are bound, today's rules reproduced, by
      rule views of the condition's own beside the service's, never one view branching on
      `call_kind`, into the one stored `graphitron_occurrence_call_parameter`, whose check gains a
      `TABLE` fill only a condition takes, the table being the call's `bound`. The depth writer marks
      and sweeps calls by kind, so a `@condition` whose binding goes leaves no call and no fills.
      What remains of the principles review's three commits (Alf, 2026-11-10):
      * **A `@condition`'s calls, candidates and fills are written per depth** `open`. Landed but
        for one thing: a condition applied to a node id's table or taking a decoded key does not
        emit (`AUTHORED_NODE_ID`). An argument's fill names the occurrence it takes, a condition's as
        a service's, so no binding at the argument is owed.
    * **A `@condition` and a service pair a parameter with an argument by one rule** `open`, off the
      critical path. The condition keeps its own by-type pairing through the flip; the differences
      from the service's, listed under "A `@condition`'s fills", are closed then, one rule for every
      call, each difference a test first.
    * **An input field's `@condition` takes a bean** `open`, off the critical path. Refused today
      because that condition was resolved once per input type with no consuming field; a call per
      occurrence has one, so the refusal can go.
  * **An inline list's ordering is resolved at its occurrence** `open`. `@defaultOrder` and `@order`
    are stored as written today; the occurrence states its sort keys in order, each a column with
    direction and collation or an order-field method, read by the multiset's `orderBy`.
  * **An inline list states how it is narrowed** `open`. Its `@lookupKey` arguments in key order,
    which today claim `LOOKUP` only at a root or a split, and whether `first` limits it, which only a
    connection carrier says today.
  * **A `@pivot` is resolved against the catalog** `open`. The attribute table, its on and value
    columns as `sql_column` rows, and each slot's token, where today the entry holds the strings
    written and the slots bind no column.
  * **A child producer states the parent columns it is handed** `open`. Beside the key
    `graphitron_occurrence_producer_lift` fills it with, the columns of the parent occurrence's table its
    loader keys on, so the parent's unit projects them by a join from the producer rather than by
    `correlationKeyArm`.
  * **A single child's null contract is stated** `blocked`, on the expression and condition nodes.
    Nothing states when a nesting field or a single inline child is null, which the `__p_` probe
    decides today as a disjunction over its paths and expressions; it is modelled once those land.
  * **An input object is read once, by a reader of its own** `open`. One generated typed record per
    input type, built from the argument map with its node ids decoded, enums converted and lists
    walked, so a condition, a lookup's rows and a service's fills read `filter.range().fra()` rather
    than each spelling its own `instanceof Map` ladder over the wire (Alf, 2026-11-08). A node id
    decodes through one method per node type and policy beside `NodeIdEncoder`, strict or
    not-this-participant, rather than through private copies in every class that reads one.
  * **The filter conditions are emitted from the store** `open`, the generated predicates first,
    the authored calls on the `@condition` call node. One condition per field occurrence and table it
    filters, named and aliased from its coordinate, composing the generated predicates, the authored
    `@condition` calls and the path steps' conditions with `override:` read as written, its behaviour
    today's; a faceted condition is built term by term, the full condition the base and every facet's
    term, its facet sources reusing the condition's paths, and facets back off to a node of their own
    if that proves hard. Additive and uncalled until the flip, which moves every caller at once, the
    root, split and batched fetchers, the facets and `$project`'s multisets, so the old glue goes in
    it; pinned by tests that compile the emitted code and run it against the database, and by
    `ConditionEmissionCensusTest`, whose reasons are what is left (Alf, 2026-11-08).
    Every reason closes before the flip, which keeps no fallback to the classified model per
    occurrence (principles review at take-up, 2026-11-12); before it, the census's `CONSUMED` and
    `NOTHING` fields are checked against the old glue for a filter it emits today.
    * **A list of input objects' element fields are refused by the store where they cannot be compared as rows** `open`.
      `LIST_ELEMENT_CONDITION`, `LIST_ELEMENT_NODE_ID` and `LIST_ELEMENT_NOT_ONE_VALUE` are the store's
      at the element field's declaration, and `LIST_CONDITION_BELOW_LIST` at a `@condition` binding
      below a list, each grouped from `graphitron_occurrence_list_element_defect`, the rule each
      occurrence breaks. Left: an element field whose value no row cell takes, written by a stage after
      `occurrence-values`, whose value binding it reads; element fields reaching their columns by
      different paths, one defect at the list field, skipped under an override, each element occurrence
      and its path a row beneath it, so no field is blamed for coming second, a step being its
      position, the table it reaches and the column pairs of `graphitron_join_path_step_column`
      (principles review, 2026-11-12). Each is pinned by a `defects/`
      document with near misses, and the glossary's "An element field a list-of-input filter cannot
      compare" names it. The walk's cap of 22 element fields, an artefact of jOOQ's typed rows, is not
      carried into the store: it goes after the flip as a relaxed refusal of its own.
      `ConditionEmitter`'s refusal of these shapes then becomes an invariant a defected graph never
      reaches, and the flip waits on this node, since until it lands the shapes left are refused by the
      walk alone (principles review, 2026-11-12).
  * **The root query fetchers are emitted from the store** `blocked`, on the filter conditions and
    the ordering node. A root `SELECT` or `LOOKUP` producer's fetcher, its condition, order, page
    and facets, is read off its occurrence and flips with `$project`, so the conditions' callers move
    in one pass; the hard part is `$project`, the root fetchers following it (Alf, 2026-11-08).
* **What a filter means does not depend on where it is written** `open`, off the critical path.
  Today an empty list drops its predicate where a polymorphic participant's all-foreign ids answer
  `falseCondition()`, the lenient node-id decode drops a malformed id with one of another type, a
  non-object list element is false in one matcher and a row of nulls in another, and a faceted enum
  list is cast where a scalar one is converted by `valueOf`, untested beyond `G`, and a top-level enum
  list argument on an enum column emits glue that does not compile. Each gets a test
  first, then a fix or a stated breaking change, after the flip, which keeps them as they are.
* **A joined participant's re-read reads its join from the base** `open`, E's.
  `graphitron_occurrence_participant` states a participant on a detail table with that table, its
  container's table and column beside it; the re-read reads it through the join from the base, a
  path between the two tables on F's join paths, and until that path is stated the re-read and the
  resolver refuse it, naming it.
* **A polymorphic service's list keeps a null where a record finds no row** `open`, E's, off the critical path.
  The old generator's multitable service re-read drops from a list every record whose class matches
  no participant and every key whose row is gone, so the list a service hands back comes out shorter
  and its positions no longer line up with what was asked for, which breaks lookup semantics (Alf,
  2026-11-07). The store-fed re-read mimics the drop first, as every flip keeps functionality
  identical, and this node then keeps a null in the slot instead, as the table-bound lift already
  does: a breaking change for an author relying on the shorter list, so the manual's polymorphic
  page and the changelog say so, with an execution test pinning the slot.
* **A split carries the target's key where the source states it** `open`, E's, off the critical path.
  At a split, the frame's key is read off the path's first step rather than always the parent table's
  primary key: where the source states the target's key, as foreign-key columns on its row or an
  accessor on its object, the loader carries that and the continuation is a lookup on a derived target
  table, the `@lookupKey` shape; a `SOURCE`-filled parameter receives whichever key the split carries.
* **An enum hands graphql-java its Java constants** `open`, off the critical path. A GraphQL enum bound
  to one Java enum, by `@enum` or by every use agreeing, defines its values as that class's constants,
  so graphql-java converts arguments both ways and every `valueOf` in the emitters goes; the store
  states each enum's Java class and whether a class is an enum, and uses that disagree are decided
  there. Output serializes unchanged, graphql-java matching a constant or its name either way.
* **The service fields are emitted from the store** `open`, E's. The flip: every `@service` field's fetcher is assembled by the parts' emitters, and
  the generator's unit tests and sakila's execution tests pass unchanged; the generated code's shape
  changes on purpose, its behaviour does not, save a behaviour ruled a defect. It goes field by
  field as the parts land: `ServiceEmission` emits a field the store states whole, the classified
  emitters the rest, and `EmissionCensusTest` pins the count, 124 of the example schema's services
  on 2026-11-10, the 3 left being the polymorphic node ids on the `BUILD_REFUSED` line.
  * **A built argument decodes what `BuildQuery` refuses today** `open`. `NodeIdDecodeQuery` follows
    the id's stated path. A list of node ids into a class member, a decode into one key value and a
    record's groupings are built; what is left is an id of several node types into a supertype of
    their records (`assignOccupantRecord`, `assignOccupantRecordList`, `assignOccupantByArgument`).
    Whether each member's record fits is captured already (`sql_table_record_supertype`), and
    `NODE_ID_SLOT_NOT_SUPERTYPE_OF_MEMBER` refuses one that does not, but only at a root argument's
    parameter: an id filling a bean member is never compared. Widened to member slots, the emitter
    dispatches on the type id over the member decodes it already groups, and the three
    `BUILD_REFUSED` services leave the census's exclusions.
  * **A node id's decode shape is decided once, in the store** `open`, E's, off the critical path.
    At a root argument's parameter it is: `graphitron_occurrence_node_id_shape` states the shape, a
    `RecordN` or `RowN` matching the key on its degree and its column types, and the slot defects and
    `BuildQuery.decodeParameter` read it. What is left is the bean member an input field fills, which
    `BuildQuery.bind` still judges with `NodeIdDecodeQuery.resolve`, and a record of another table
    the id reaches through a foreign key. The model (Alf, 2026-11-10): the class each input object
    occurrence is built into, a `MEMBER` binding beside `VALUE` keyed into `code_write_slot` (matched
    on the bound name for a service and the GraphQL name for a condition), the node id slot stored
    per call parameter for every call kind, and the shape relation widened to those slots. The first
    two are stated: `graphitron_occurrence_built_class` and `graphitron_occurrence_binding_member`,
    which `BuildQuery` reads in place of matching member names. The slot is stored too:
    `graphitron_occurrence_node_id_slot`, a supertype over the call parameter, the built class's
    member and the built record an id lands on, each subtype written by one plain rule.
    * **`BuildQuery`'s refusals are defects** `open`, E's. A class nothing constructs, a field
      filling no member, a list that cannot group, two fields on one member, a `@condition` taking a
      jOOQ record and a record's field naming no column are entry defects at the field, and a node id
      with no route onto a record under a `@service` is `NODE_ID_LANDS_ON_TABLE`. What is left: a
      list of node ids written onto a record and a nested input object filling a member typed as a
      jOOQ record, both refused only in Java, and the Java refusals themselves, which go once nothing
      falls back to the classified emitter.
    * **A record's column defects read the column binding** `open`, off the critical path. The
      output and input arms of `COLUMN_UNRESOLVED` and `COLUMN_AMBIGUOUS` each work out spelling
      resolution from `sql_column_spelling`; reading what `graphitron_occurrence_column` leaves
      unbound, as `BuildQuery` does, they judge the binding the emitter writes. `COLUMN_AMBIGUOUS` on
      a record has no pinning case yet.
    * **The node-id slot defects leave the gatherer's last rule with the call-parameter defects**
      `open`, E's, off the critical path. The slot arm, `NODE_ID_KEY_TUPLE_TYPE_DISAGREEMENT` among its
      codes, judges the decode shapes `occurrence-builds` writes, but stays in
      `graphitron_entry_defect_rule`: `NODE_ID_KEY_COLUMN_TYPE_DISAGREEMENT` is also
      `graphitron_occurrence_call_parameter_defect`'s, and a code has one owning stage. Moving the
      call-parameter defects to the stage that writes the call parameters takes the slot arm with
      them.
    * **A shared input built into several classes names which in its defect** `open`, off the
      critical path. A build defect's detail is the least class or member where an input is built
      into several, so which class and occurrence failed is lost; a detail relation, as the
      parameter defects have, names each.
    * **Grouping under a record is decided once** `open`, off the critical path.
      `BuildQuery.columns` decides in Java that a nested input object under a jOOQ record groups onto
      it, which `graphitron_occurrence_built_class` states as a `GROUPING` row; it reads the row.
    * **A built class at a parameter names its call parameter** `open`, off the critical path.
      `BuildQuery.roots` recomputes the class a parameter takes from `code_method_parameter` to find
      its `PARAMETER` row; tied to the call parameter it lands on, the row is read directly.
    * **A class's entry in the graph is resolved once** `open`, off the critical path. The graph's
      first entry declaring a class, by name, is spelled in `graphitron_occurrence_source_object`,
      `graphitron_occurrence_producer_result` and `graphitron_occurrence_built_class`; one stored
      resolution of a class to its entry is read by all three.
  * **A record's writer per column is stated in the store** `open`, off the critical path. Which input
    field writes each column of a jOOQ record, a live one over a deprecated one, is decided in
    `BuildQuery`, again in `BuildEmitter`'s conflict label and a third time in `ValidationEmitter`'s
    violation paths; stated per column, two live writers become a defect at the input type and every
    emitter reads the writer it is told.
  * **The unchecked-column advisory reads the store** `open`, off the critical path.
    `validation-column-unchecked` partitions the classified model's column bindings while a service
    the store emits takes its checks from `BuildQuery`'s column writes; read off the validating
    producer's occurrence columns anti-joined to `sql_column_record_accessor`, the advisory and the
    checks state one fact.
  * **A child service whose source collects no table's records is refused** `open`. A child
    `@service` whose `Sources` parameter collects records of no table falls between
    `SOURCE_PARAMETER_MISSING` and the key-row arms, and which code refuses it is undecided (Alf,
    2026-11-10).
  * **A table-bound field read off a member's value is judged** `open`. `FilmBlurbHolder.blurb`,
    whose member hands back a `FilmBlurb` object while the field's type is bound to `film`, is
    refused by the walk ("requires a typed accessor") and passes the store, `NO_KEY_FROM_PARENT`
    reading the member's value; whether that is intended or a defect is missing is Alf's to decide.
  * **A class states whether it is nested** `open`, off the critical path. The code census finds a
    nested class by the `$` in its binary name; the classfile's inner-classes attribute states it,
    and reading nested classes reached through another class's members closes the one hop the census
    reads today.
* **The walk's service path is gone** `blocked`, E's, on "The service fields are emitted from the store". The subtract: `ServiceCatalog`'s decode, claim and bind, the ten service leaves,
  `MethodRef.Service`, `ServiceMethodCall` and `ServiceMethodCallEmitter` go; `@service` then admits
  a `$context` pair, reading `graphitron_call_context_key`.
* **A routine call is stated per occurrence and emitted from the store, as a service call is** `open`, F's.
  A root `@routine` is the producer call: its parameters are filled per occurrence in
  `graphitron_occurrence_call_parameter`, widened from service calls to any producer's call, and its
  call is emitted from the store as a service call's is. A child `@routine`, split or not, is never a
  producer but a join step of the path; under `@splitQuery` the producer is the split's derived source
  lookup. Then `graphitron_routine_entry` and the routine decode go.
  * **A child `@routine` is a lateral step of the path** `open`, F's. The step and its parameters
    are stated, `graphitron_join_path_step_parameter`; what is left is the editor, whose `argMapping`
    completion at a path routine offers the field's arguments and not yet the standing table's columns.
  * **A split child `@routine` is a path routine** `blocked`, F's, on "A child `@routine` is a lateral step of the path". `graphitron_producer_claim` claims `ROUTINE` for a child `@routine` under
    `@splitQuery`, such as `Actor.filmsSplit`; its producer is the split re-query, and the routine is
    the walk's first step from the parent's keys.
* **An `@outbox` binds its target's columns, its payload a selection set** `open`, off the critical
  path. `argMapping` binds a call's parameters on `@service` and `@routine`, its left side a name the
  store checks; on `@outbox` it names JSON keys nothing declared, and the target's columns are two
  roles graphitron fills (Alf, 2026-11-09). A message is a call of its occurrence, so its parameters
  are the target's: a table's columns or a routine's parameters, each bound by name, a scalar one to
  a path and a JSON one to a selection set, so `payload: { filmId: $result.id, title: in.title }`
  builds the body and `aggregateid: $result.id` fills a routing column an author keeps. `type` takes
  the event unless bound. A key naming no column is the unresolved-name defect, and a `NOT NULL`
  column with no default that nothing binds is an unbound parameter, as `ROUTINE_PARAMETER_UNBOUND`
  is; the default check this replaces carries over as that.
  The grammar is one for every `argMapping`, a strict subset of Apollo Connectors' `JSONSelection`
  (`apollo-federation/src/connectors/json_selection/README.md` in `apollographql/router`), whose
  principle that a selection's output shape is static is the store's own: shorthand (`title`),
  alias (`filmId: $result.id`), nested objects (`key: { ... }`), a path's sub-selection
  (`in { title releaseYear }`), string, number and boolean literals, and whitespace or commas
  between entries, `#` comments allowed. Our sigils stay (`$result`, `$session`, `$scope`, valid
  `VarPath`s there), and a bare name stays relative to the call's arguments. Left out: `->` methods,
  `??` and `?!`, `@`, `$(...)`, spread (a payload following an input type's every change is the
  contract coupling a payload must not have) and `?` (an argument a client left out is already
  absent). Each site admits what its parameters take: a nested object at a non-JSON parameter is a
  defect, not a parse error, and today's flat strings are already valid. Slices: the parser; the
  store's landing of nested keys and sub-selections; the column and parameter binding with its
  defects; the companion.
* **An `@outbox` on a `@routine` mutation emits from the store** `open`, off the critical path.
  R1113's phase 3 for routine writes. A routine write is emitted from the store already
  (`RoutineWriteEmitter`), and like a DML write it captures, inside its transaction, what it reads
  back by: the routine's own result columns. So a `$result` key reads those, where DML reads the
  written row: a field whose landed column is a captured result column, or a node id whose key
  columns all are; a field past the first hop needs a read and misfits. `graphitron_occurrence_producer_write`
  admits `ROUTINE` (or `OUTBOX_NOT_A_WRITE` reads the routine producer beside it), the key rule gains
  the routine arm, and `RoutineWriteEmitter` calls R1113's companion with the captured record, in
  one commit, so a routine `@outbox` is never accepted without being emitted.
* **A table class's column fields are stated** `blocked`, on "The generated jOOQ classes are read with their members". The code family keeps each generated table's public column fields, which
  the census reads and the capture drops.
* **The catalog says which columns jOOQ computes** `open`. `sql_column` states a column's computed
  flag and where it is computed, so a client-side computed column is told from a stored one.

### An argument a call consumes generates nothing `open`

An SDL argument or input field has exactly one kind of consumer: a call that takes its value as a
parameter, or else the generator, which writes a column predicate, a lookup, an ordering or
pagination from it. One with neither is an author error. This replaces `@condition(override:)`,
whose default of `false` (the method's condition ANDed with the generated predicates) is the wrong
default by fs-plattform's own usage: 443 applications write `override: true`, 5 compose on purpose,
and one omission is a likely bug. The census, the 18 omissions and the reasoning are in
`roadmap/audits/2026-11-02-condition-override-is-argument-consumption.md`. Decided with Alf on
2026-11-02.

* **The rule reproduces fs-plattform's intent** `open`. Over captured fs-plattform subgraphs, list
  every argument or input field `override: true` switches off that no call consumes, and every
  `@lookupKey` or `@orderBy` argument a call does consume. Both lists empty, or only author errors,
  is the evidence the flag can go; anything else comes to Alf before the nodes below.
* **An argument's consumer is stated** `blocked`, on "A producer's fetcher parts are emitted from the store".
  A relation over every argument and input-field occurrence, derived from the parameter fills: the
  calls that consume it, or the generator, or none. Consuming an input-typed argument consumes every
  field inside it; consuming one input field leaves its siblings to the generator. None is the entry
  defect `ARGUMENT_WITHOUT_CONSUMER` at the argument's written position. The generator is a
  candidate only under a producer it composes with, `@condition`, `@routine` or a table read, so
  under `@service` and `@externalField` an occurrence the call does not take is that defect.
  `ARGUMENT_WITHOUT_CONSUMER` is a code whose `graphitron_defect_type` row names its
  `owner_stage`, written through that stage's rule view and swept by its gatherer.
  * **A path or a column a consumer does not take is a defect** `blocked`, on "An argument's consumer is stated".
    A written path on an occurrence whose consumer takes no table is `PATH_NOT_TAKEN`. A scalar
    argument or input field matching no column at its path's end is `COLUMN_UNRESOLVED` only where
    no call parameter consumes it.
* **The generator emits from an argument only when no call consumes it** `blocked`, on "An argument's consumer is stated".
  `override:` is no longer read, and `graphitron_ast_field_condition_entry`, the store's only typed copy
  of a written `override:` on a field, has its reader or goes. What goes: the `enclosingOverride` cascade in `ClassifyContext`,
  `InputFieldResolver` and `FieldBuilder`, `suppressedByFieldOverride` on `ArgumentRef`,
  the `override` axis of `ConditionFactVisitor` and `ArgConditionRef`, and the walk's refusals that
  turn on it (the `override: false` column miss in `BuildContext`, the mutation-input and `@nodeId`
  carrier refusals that require `override: true`), each replaced by the consumer relation or by
  `ARGUMENT_WITHOUT_CONSUMER`. The sakila fixtures `filmsOuterOverrideFilterInput` and
  `filmsOuterOverridePlainInput` are re-asserted as behaviour under the rule.
* **A written `override:` is a deprecation warning** `blocked`, on "The generator emits from an argument only when no call consumes it".
  Accepted for a transition, deprecated in `directives.graphqls`, and a lint detection where the
  written value disagrees with what consumption implies, so an author sees the sites whose behaviour
  changed.
* **The manual states the rule in one sentence** `blocked`, on "The generator emits from an argument only when no call consumes it".
  `reference/directives/condition.adoc`, `how-to/condition-cascade.adoc` and
  `how-to/migrating-from-legacy.adoc` lose the override, cascade and legacy-divergence prose; the
  rule and its consequences replace them.

### The graphitron family completes its flip and subtract `open`

Every replacement this family built has had its readers moved and its predecessor deleted, so the
store keeps one shape of each fact. That means no coordinate-keyed entries, no separate assembly
pass and no stratum that clears and recomputes.

* **No `graphitron_*_entry` survives, an entry being the transcription's alone** `blocked`, on the
  two below. Only the `graphitron_ast_` family holds entries, and the registry decode that wrote
  the others goes. A reader moves onto the `graphitron_ast_` entry joined to
  `graphql_directive_application` by the application's position, ordinal zero standing for the one
  application a coordinate-keyed copy kept, as the `@error` hover and the `@orderBy` and
  `@lookupKey` readers now read.
  * **`graphitron_routine_entry` is gone** `open`. `@routine` is what is left: `GraphitronAnchor`
    copies `graphitron_ast_routine_entry` onto it, the registry decode writes its spelling, and its
    readers (`RoutineWriteQuery`, the language server's declaration and field facts, four sites of
    `graphitron_entry_defect_rule` and `intent_argmapping_bound_parameter_type`) wait on the call and
    its parameters being stated per occurrence and emitted from the store, as `@service`'s are.
  * **The untwinned entries get an ast entry first** `open`. Each entry with no `graphitron_ast_`
    twin gets one, its readers move, and the old one goes. Left: `graphitron_spelled_reference_entry`,
    a spelling being a column of several `graphitron_ast_` entries and no relation of its own, and
    the decode's `graphitron_field_reference_entry`, whose twin is `graphitron_ast_reference_entry`;
    both wait on `@routine`, whose decode writes a spelling and whose readers read the field reference.
* **The supertype roster becomes shrink-only** `open`. The roster of duplicated entries is a
  ratchet that can only lose rows.
* **`GraphitronAssemblyCapture` is gone, its steps the anchor's own lines** `blocked`, on the child
  below and on "Nothing resolves a path but the walk".
  * **The assembly steps that clear and refill are marked and swept** `open`. `NodeKeyColumns` and
    `SpelledTables`; the path steps go with the walk.
* **`DerivationStratum` is gone, each step anchored by its owner** `blocked`, on the three below.
  With `StageProgress` and `StageOrderGateTest`, the order they kept being the gatherer order.
  `DerivationStratumCeilingGateTest` keeps it from growing, and each child lowers its ceilings.
  * **A defect is written by the stage whose facts it judges, and swept by its gatherer** `open`.
    A gatherer's anchor phase is a sequence of stages, and the defects a stage's facts break are
    that stage's to write, as its last statement, from a rule view of its own; one rule over every
    arm is one statement reaching every relation the gatherer derives, `graphitron_entry_defect_rule`
    reaching 123 relations through 21 views and costing about 0.5 s to prepare on a cold ten-type
    capture (2026-11-08), and each walk rejection that becomes a code would add to it.
    `graphitron_entry_defect` is a supertype, one row per defect at its written position; a stage
    is encouraged to add subtype and other detail relations where a defect has more to say than its
    code and detail, each keyed by the defect's key with a foreign key to it `ON DELETE CASCADE`, so
    a swept defect takes its details with it.
    * **A route defect has one writer** `open`. `ROUTE_AMBIGUOUS` and `NO_ROUTE_TO_TARGET` are
      written by `Steps` for a route the walk takes from a written path and by the rule's
      inferred-route arm for one no path is written for, two statements of one rule with nothing
      comparing them; `occurrence-depths` owns them and the gate lists the arm as a second writer
      until it moves to the walk.
    * **An arm belongs to the last stage whose facts it reads** `open`. Each arm of `graphitron_entry_defect_rule` moves into a rule view of the stage
      that writes the last relation it reads, its codes' `owner_stage` with it, as a refactor: every
      fact document and sis write the same `graphitron_entry_defect` rows before and after. The
      checks that read only the documents (`TYPE_NAME_CASE_COLLISION`, `CONNECTION_RETURN`,
      `DEFAULT_PAGE_SIZE_ABOVE_MAXIMUM`, the subscription arm) go to a stage of the anchor, and the
      depth loop's go to `occurrence-depth-defects`, the stage straight after it, so the loop itself
      stays lean (Alf, 2026-11-12); the lift and column families are there already, and the build
      defects are `occurrence-builds`'. A stage's
      arms that expand one view several times read it once, from a table the stage writes. Priced
      by a prepare tally per stage on a cold capture before and after, against the 0.5 s the single
      rule costs today; `graphitron_entry_defect_rule` and the `entry-defects` stage go when their
      last arm leaves.
    * **The detail writers move with their codes** `blocked`, on
      "An arm belongs to the last stage whose facts it reads". `EntryDefectClaims`, `EntryDefectMembers`, `EntryDefectTables`,
      `ConditionShapeDefectMethods` and `ArgMappingPairDefects` each write their rows in the stage
      that owns the codes they detail, so no defect writer runs after the stages but the gatherer's
      one sweep.
    * **A routine write's refusals are codes a stage writes** `open`. A path the write's result
      leads on is the path family's to judge, `NO_ROUTE_TO_TARGET` at a landing or a payload data
      field no hop reaches and `REFERENCE_WITHOUT_TARGET_TABLE` at a path on a payload return, so
      only what a write alone refuses is a code of its own, and `RoutineWriteQuery`'s verdict and
      the walk's routine refusals go as each lands.
      * **A table-bound return with no written path is a lift** `open`. The store states the
        write's result as records of the return's table re-read by its key in a statement of their
        own, a write's statement joining nothing onward since it holds its locks, and the emitter
        refuses the shape only because it has no lift for a write.
    * **What a producer's object result holds is judged by the families it is stated in** `open`.
      The walk's single-channel rules for the type a write, a routine or a service returns are no
      rule of the model: each field under the result is an occurrence with its own lift off its parent, so two
      fields re-reading the same rows, a child `@service`, a filter on a re-read and a path from the
      key table are each a field's own (Alf, 2026-11-09).
      * **A field read off its parent's member is no lookup** `open`. `graphitron_producer_rule`
        calls `FilmBlurbHolder.blurb` a `LOOKUP` for its table-bound type though `blurb()` hands back
        the value it reads, so `NO_KEY_FROM_PARENT` exempts it by the member rather than the claim.
      * **A node id is encoded from a routine result's returned keys** `open`. `NODE_ID_LANDS_ON_TABLE`
        refuses a `@nodeId(typeName:)` under a routine write's object result, its rule taking a
        foreign key and not the name-matched keys that carry the node's key out of the result.
      * **What the model admits the emitters follow** `open`. An id encoded from returned keys, a
        non-null single field under a routine with no errors field, a list field taking one row by
        a lift of one key per parent, a routine lift with no step and several fields re-reading a routine's
        rows are no defect, the routine ones `RoutineWriteQuery.Unrendered`, kept out of the rows by
        name, so each emitter refusal joins
        "The store, the generator and the emitters agree on what a service call can be" before the walk's refusals go.
    * **A name claimed that does not exist is one defect** `open`. `TABLE_UNRESOLVED`,
      `COLUMN_UNRESOLVED`, `CODE_REFERENCE_METHOD_NOT_FOUND` and `ELEMENT_UNRESOLVED` each state a
      written name resolving to nothing, the position saying what was named, so they fold into one
      code (Alf, 2026-11-09).
  * **A defect is keyed by the occurrence it judges, with the written position where there is one** `blocked`, on "An arm belongs to the last stage whose facts it reads".
    The split by stage comes first, writing the same rows, so the re-keying changes one grain inside
    rule views that are already small rather than a shape and a grain at once.
    `graphitron_occurrence_defect`, keyed (graph, coordinate, code) with a cascade from the occurrence,
    carries the written position as an attribute where the problem was written, the element's
    declaration being where an editor points otherwise, and `graphitron_entry_defect_site` reads both
    (Alf, 2026-11-05). A defect about what an occurrence means moves there, the `@nodeId`, column and
    path defects among them, since one declaration reused under several fields fails at some and not
    others; one about what was written stays at its position.
  * **Each stratum step moves into its owner's anchor phase, marked and swept** `open`.
  * **The stratum's `intent_` writers go** `open`. `ClassificationDomainCapture` goes with the
    relation it writes.
* **`FactSink` and `FactWrites` are gone, each writer stating its own insert** `blocked`, on "No `graphitron_*_entry` survives, an entry being the transcription's alone". A capture site builds a generated record, sets one column at a time and hands it to a sink that assembles the insert at run time, with `FactWrites` overriding that for six relations; the statement a writer issues is nowhere in its source. Twenty-nine relations still arrive that way: the legacy `graphitron_*_entry` decodes from `GraphitronFactCapture`, which leave with their node, then the schema inputs and extensions from `SdlFactCapture` and `SdlCoordinates`, the configuration from `StoredRecipe`, the Java declarations from `EmittedRegistry`, the build warnings, rejections, lint findings and javac diagnostics, and the `sql_` catalog from `JooqFactCapture`. Each writes through its own statement instead, set-based where it reads the store and bound once per row through `BindBatch` where it reads a corpus, the shape `GraphQLAstEntries` writes in. It absorbs R701, which stated the same end from the column side.

### The classified model dissolves into the store `open`

The store is the model. For every field it states what fetches it, what arrives at it and what it
lands on, and the payloads, the candidates and the editor read those facts, so the walk's
classified records have no reader left and can go with the walk.

* **A write's re-read reads what was committed, for every write shape** `open`. An execution test
  pins the update, delete and service re-reads, as the routine and insert ones already are.
* **The matched key is stated** `open`. `graphitron_producer_matched_key` holds one row per `ONE`
  write and each key its input columns cover, keyed to the producer and `sql_constraint`.
* **The DataLoader's key is read from the producer's lift** `blocked`, on "`TypeFetcherGenerator` reads the store, one family of field at a time".
  The fetcher's batch key and its lift are generated from `graphitron_occurrence_producer_lift`, not
  from the leaf's `KeyLift`.
* **A child producer's lift agrees with the classifier's key lift** `open`, E's.
  `graphitron_occurrence_producer_lift`, with `_lift_candidate` and `_lift_accessor`, states the key
  each child producer's source fills it with, pinned against the classifier's key lift over sakila
  but for the gap below.
  * **A record's method beside its components is a read slot** `open`. A record offers only its
    components, so `FilmCardData.example()` backs nothing and `RecordExample` holds an OBJECT of no
    class, where the classifier reads it through that accessor.
* **A table child under a handed value deeper than its carrier refetches** `open`. A table-bound
  field below a value a producer handed down, deeper than its carrier, fetches its rows itself.
* **What an occurrence takes is a binding** `open`, F's and E's. The `COLUMN`, `NODE_ID`, `TABLE`,
  `VALUE` and `MEMBER` bindings are written; an argument a call consumes binds none, the call's
  parameter fill naming it. What is left is `EXPRESSION`, which has only its kind in the binding's
  check and no writer.
* **The result relations dissolve into the producer's result** `open`, E's. The key-row half has
  landed; `graphitron_result`, `_result_table`, `_result_class` and `_result_pivot` remain, saying per
  type what the producer's result says per producer, and go with the result rule's fixpoint, their
  type-level readers (the LSP's type hover and inlays, the outbox rule, `RESULT_SHAPES_CONFLICT`)
  reading the results grouped by type.
  * **The other spellings of a connection read the stored shape** `open`. `graphitron_producer_rule`'s
    page arm ("the rest of a connection reads the page", which reaches every field of a connection,
    its edge, its page info and its facets type, positions or not) and
    `graphitron_occurrence_start_rule` still ask whether a type is a connection or its edge over
    `graphitron_field_target`; each moves onto `graphitron_connection`, `graphitron_edge` and the
    types `graphitron_field_position`'s `PAGE_INFO` and `FACETS` fields name, with its own row-for-row
    agreement against trunk. The `COUNT` and `FACETS` arms read `graphitron_field_position` already.
    The minting, `graphitron_field_minted_candidate`, still spells the shape's eleven field names;
    each mint arm can join the vocabulary for its field's name by position. And
    `connection-positions.graphqls` asserts `graphitron_producer` rows, the field-keyed family that
    is going: when it goes the assertion moves onto the occurrence producer rather than being
    dropped. A read
    swap only: the field-keyed arms gain nothing, and they go with
    "The producer's readers read the occurrence producer, and the field-keyed family goes".
  * **The key defects read the fill's candidates alone** `open`. The service arm of
    `NO_KEY_FROM_PARENT`/`KEY_ROW_AMBIGUOUS` still counts members itself over `code_read_slot` and
    `code_method_parameter`, a second spelling of the candidates that disagrees with the fill on a
    method with two record-set parameters; it reads the stored `RECORD` candidates per occurrence
    instead, and the selection arm reads the occurrence's source object rather than the field-keyed
    `graphitron_result`. The details move to `graphitron_entry_defect_occurrence` with its table and
    method rows, where a field filled differently under two parents now keeps one. The fill rule's
    service arm stops reading `org.jooq.Record`/`Row` key types off the class name.
  * **A source says whether graphitron selected its rows** `open`, F's.
    `graphitron_occurrence_source` carries its basis, `SELECTED` or `HANDED`, so a lift tells a row
    graphitron selected from a record a service or a write handed over, which guarantees only its key.
    `edges.node` under a page graphitron selected is `SELECTED`, the case where an element and the
    result set it sits in come from one statement.
  * **A child producer's source is its parent's value** `open`, F's. `keyRow` has left
    `OccurrenceSources`; `ownServiceRecord` and `rootRoutine` leave it, and
    `writePayload`/`writePayloadMember` read the written rows; the § Source bullets of `code-generation-triggers.adoc` change with them (Alf,
    2026-11-10).
  * **The classifier lifts by the settled rule** `open`. `OnFkSlots` only on a single forward hop off
    a selected row; a path of several hops and a `@service` or write record's foreign key take
    `OnParentJoin`, keyed on the parent's primary key; a reverse single hop keeps its shortcut, and a
    single forward hop to several participant tables takes the source lift (Alf, 2026-11-10).
    `splitQuery.adoc` and `dispatch-axes.adoc` change in the same commit, execution tests pinning
    each shape.
  * **A write's payload member on another table is a defect** `open`, E's. A union or interface
    member of a root write's payload bound to a table other than the written one is never produced.
* **A `@mutation(table:)` that disagrees with its return's table is a defect** `open`, E's.
  `WRITE_TABLES_DISAGREE` raises it at the `@mutation`; what remains is its detail naming both tables.
* **The producer's readers read the occurrence producer, and the field-keyed family goes** `blocked`, E's, on "The result relations dissolve into the producer's result".
  `graphitron_occurrence_producer` and its start and call subtypes state per occurrence what
  `graphitron_producer`, `_service`, `_routine`, `_write` and `graphitron_service_holder` state per
  field, read today off them through the occurrence's field; the readers move, the rule reads the
  occurrence's source rather than the field, and the field-keyed relations go with
  `graphitron_reader` and `graphitron_fetcher`.
* **The forced producers become join-path continuations** `open`. A union or interface child with a
  table-bound participant, a child `@routine` and a connection child produce today because the
  generator builds no inline form for them, and the occurrence producer keeps them so (Alf,
  2026-11-04); each stops producing, its join path continuing its producer's query, when the
  generator learns to join it.
* **An object result is lifted onto a key row** `open`, E's.
  Under an object parent the producer that starts a field lifts it onto the table of the key its
  source fills, `graphitron_occurrence_producer_lift`, while the field's source names only that table
  ("Producer, bind and call on the occurrence (draft)"), and only a key on the source side lifts one,
  a key read off the parent's record table's columns, so a field whose key re-fetches its own target
  gets none. E keeps the fill and the first key; the hover's departing table for an input type's
  record binding is a reader of the source, kept, not dropped.
  * **A `@pivot` gives an OBJECT result** `open`, off the critical path. A `@pivot` field's type holds an OBJECT, so
    `graphitron_result_pivot` goes, and a type both nested under a table's field and pivoted holds
    two shapes, `RESULT_SHAPES_CONFLICT`; R1096 plans `@localized`
    on a constructed row, which this replaces.
* **The backing closure stops at a table-bound type** `open`, off the critical path. The class a
  table-bound type holds is its record class, which `BOUND_TABLE` already states, so the
  `graphitron_type_backing` closure need not walk on from it; since a service's object passes
  through to its payload's data element, the rule takes about 0.9 s to 1.4 s on sis at load 9 to 10
  (2026-10-29), its new seed arm 7 ms of that and the rest the walk from the 18 new seed classes into
  the table-bound types their members return, a cost accepted until this lands. Its merge is the
  build's second costliest statement to plan, 235 ms a prepare and 221 CPU-seconds over a
  `graphitron` test phase, reaching 29 relations through 3 views (`roadmap/audits/2026-11-02-where-the-build-spends-its-cpu.md`).
* **A routine's result is an object of its function's record class** `open`, off the critical path. An unbound type a
  `@routine` returns is a result, an OBJECT whose class is the function's record off
  `sql_table.record_class_fqn`, whether the routine is a producer (a root, `@splitQuery`, under an
  object) or a lateral step inline, while one returning a bound type gives a row of that table; this
  supersedes the row of its function that a `@routine` read with nothing after it has handed since
  `0428ef411`, and `graphitron_routine_result` and the landing's row with no place go. Today Tilgang
  holds a ROW and an OBJECT, and Brukertilgang's three bound children have no start and no key.
* **A path starts at its parent's table and targets a table** `blocked`, off the critical path, on "A routine's result is an object of its function's record class" and "Every field has a path, the written one or the one discovery implies".
  A table-bound parent starts every field's path on its binding, whatever its result, so for almost
  every field the start is known before the results and the editor completes on an unreached type;
  only an unbound parent asks its result, a nested type starting on its result's tables and an
  OBJECT lifted into a derived table by mapping it onto a key row, and a root `@routine`
  establishes a result, a path to the field's target where that is table-bound. The target is
  the occurrence's `TABLE` binding's or a scalar's written path's, so the start rule, the result
  rule and the column match read the binding, the result and the target instead of probing
  whether a `@reference` was written, and the RESULT start narrows to unbound parents. A paginated
  field's path runs from its parent to its items' table, its connection and edge types passing the
  cursored result forward with no path of their own, and a field on an unbound type nothing returns
  has nothing to start from and gets no help. On the stores of 2026-10-28, 45 to 59 percent of the
  non-root fields that need a path start on their parent's binding, and the 29 bound children of
  service results with no key row (27 on sis, 2 on sakila) sit under a payload data element whose
  class is not carried down.
* **The candidates are one family** `open`. What a coordinate could name, from the argMapping,
  node id, `@referenceFor` participant and catalog-name askers, is one rule view read by the
  rejection messages, the language server's completions and the MCP server.
* **`graphitron_spelled_table` is reconsidered** `open`. A written table name resolves there per
  spelling, shared by the `@table` defects and the field scope, routine element, write producer and
  code-reference rules, where each site could resolve its own as `graphitron_path_element_table`
  does, the resolution rule stated once.

### Validate is a query over the store, and runs against the model alone `open`

`validate` opens the store, captures, reads its verdict from the store and closes it, naming
nothing in the `graphitron` module. A verdict is a narrower read than an emission, so this flips
before `generate` does.

* **A refusal of what capture injected reaches the author** `open`. A declaration the mint or the
  configuration injected that the emitted schema's assembly refuses is recorded where the written
  schema's refusals are, and the log names it.
* **Each rule the walk enforces is a located defect** `open`. Every walk rejection becomes a code
  and a branch of `graphitron_entry_defect_rule`, and the walk's hand-built validation test for it
  becomes a fact document or goes with its rule.
  * **A select's condition, paging and ordering are keyed to its producer** `open`. The
    modifiers are stated over `graphitron_producer`, so the operation rules are written over them
    once.
  * **A scalar constant says why it has no input type** `open`. `code_scalar_constant` tells an
    erased coercing apart from the other silences, so the erased-coercing rule reads the store.
* **`validate` reads its verdict from the store** `blocked`, on "Each rule the walk enforces is a located defect" and "What the model admits the emitters follow". Node-id decode coverage is restated over store facts or goes, and
  `GraphQLRewriteGenerator.validate` and the `VALIDATE` projection go.

### `generate` is a function of the store and the emitted schema `open`

`generate` renders the emitted schema (the schema as written plus what graphitron mints into it
at capture) and the datafetchers from the store, writes a file only when its content changes and
sweeps a file no longer produced. It opens no store, captures nothing and builds no schema of its
own, so its output is fixed by the store, the emitted schema and the run's configuration.

* **The generator builds no schema** `blocked`, on "No emitter reads a leaf". The walk goes with
  `KeyNodeSynthesiser`, `ConnectionPromoter`'s expansion, what is left of `AttributedRegistry`,
  `FederationLinkComposer`'s rewrite of the written schema and the walk's reflective `JooqCatalog`,
  whose per-lookup scans cost a sis dev round about 8 s. `EmittedRegistryAgreementTest` goes with
  `ConnectionPromoter`.
  * **The authored pagination default change is announced** `open`. When the walk's schema stops
    shipping, an authored `first` no longer gets the page size as its default; the manual or the
    changelog says so.
* **The generator's inputs are values** `open`. `GraphQLRewriteGenerator` takes a `StoreHandle`,
  the `CapturedSchema` and the run's configuration, and its constructor over `CapturedGraph` goes.
* **The generator reads its configuration from the store** `open`. Each setting it still takes off
  `RunContext` or `BuildContext` (the tenant column, the lint configuration, the session hooks, the
  output coordinates) is read from the `store_graph_` relations, and `RunContext` stops carrying it.
  * **The entity dispatch's tenant routing is read from the store** `blocked`, on "The tenant column and the session hook signature are captured".
    `EntityTenantRouting` is filled from the walk's tenant fold over `EntityResolutionBuilder`'s map,
    paired with the store's keys by field list, a disagreement failing the build; filled from the
    store, `EntityResolutionBuilder`, `TenantBinding.EntityRepBound` and the fold's entity arm go
    together, the builder's deletion having moved here from R1119.
  * **The tenant column and the session hook signature are captured** `blocked`, on "The generator builds no schema".
    `<tenantColumn>` is one pattern, matched against database names as jOOQ matches them: a Java
    regular expression, case-insensitive, whole-name against `column`, `table.column` or
    `schema.table.column`, so a plain name matches what it matched before. The fact is per matched
    column, `graphitron_tenant_column`, anchored from `store_graph_tenant_column` and `sql_column`
    through the graph's sources; matched columns that disagree on type, and a pattern that matches
    nothing, are defects located in the build file the configuration was read from. An argument or
    input field is a tenant field when its column joins a matched column, which needs the argument
    model; the walk's classifier goes with the walk rather than learning the pattern. The session
    hook half reads `code_method` and its parameters' `role`, where sis's `FsSesjon.mount` and
    `unmount` take the context as `CONFIGURATION`, waiting on the two `CodeCapture` lines in the
    register.
* **`graphitron` does no orchestration** `open`. A harness in `graphitron`'s tests that captures and
  reads nothing of the generator moves to the model's test fixtures, with the tests that exercise
  capture alone.
* **Federation's facts are the federation gatherer's** `open`. The `@link`, import and `@key`
  decodes move from the graphitron entries into the `federation_` family,
  `graphitron_federation_link` becomes `federation_link`, and a minted directive the link's version
  cannot import is a defect row rather than an undeclared-directive error.
  * **A key selecting `id` alone is captured once** `open`. `graphitron_minted_key_rule`'s stand-down
    and `graphitron_key_field_binding_rule`'s node id arm both probe the key's parse for one
    selection whose one segment is `id`; the parse states it once per application, a column on the
    key entry, and both rules read it.
* **The MCP lists the composed vocabulary** `open`. MCP's `DirectivesResource` reads
  `graphitron_directive`, as the editor does, so a federated schema's `@key` and `@tag` are known to
  both rather than to the editor alone.
* **The assembly runs as the anchor's last step** `open`. `GraphQLAssemblyCapture` becomes the step
  after `EmittedAnchor` in the graphitron gatherer, its own gatherer row goes, and
  `graphql_schema_problem`'s owner becomes graphitron.

### An emitter reaches a fact one way `open`

An emitter owns its query and its emission: it reads the store with jOOQ at the grain of what it
emits and renders from the rows, with no command layer and no leaf of the classified model between
them. The gate is behaviour, so execution and compile tests hold each move, and no test reads
generated Java or SQL as text.

* **No test reads generated Java as text** `open`. Each assertion on a generated body is replaced by
  compile or execution coverage ahead of the conversion of the emitter it reads, and
  `testing.adoc`'s renderer-test guidance is rewritten to match.
* **The recompile graph is read from the store** `open`. The dev loop learns which file to rebuild
  from the store, and `PlanCompileGraph` and `FetcherEdgeRelation` go.
* **`emit` imports no `render` class** `open`. The shared one-spelling helpers move into `emit`, the
  old renderers import them from there, and the import rule's exception list is empty.
* **Routine writes read only the store** `blocked`, E's, on "The tenant column and the session hook signature are captured". The write's tenancy is read from the tenant table that node captures, not decided a second
  way, so `RoutineWriteEmitter` takes nothing from `GraphitronSchema`, and its inherited and
  argument-bound routings, which a rejected tenant-scoped write never reaches, go.
* **Operation members and delivery are read from their relations alone** `open`. The readers of
  `OperationMembers.membersOf` and `DeliveryFact.leafDerivedOf` move to the relations,
  `LauncherCommands`' two productions merge, and the crosswalk goes.
* **The store, the generator and the emitters agree on what a service call can be** `open`. The
  emitters handle four shapes that the store refuses or the generator refuses with no defect stored:
  a node id decoded into a service parameter (any but the node's own record a defect, that one the
  generator's refusal), three node ids filling one member, a checked exception no `@error` handler
  covers, and a service under a table taking no source. Each becomes a located defect or a supported
  shape, its emitter path following. The tests read documents already: `NodeIdFillEmitterTest` and
  `NodeIdBuildEmitterTest` `scenarios/node-id-decode-record`, `CallEmitterTest`
  `scenarios/service-call-shapes`, and the service census is part of `EmissionCensusTest`.
* **No emitter reads a leaf** `blocked`, on "`TypeFetcherGenerator` reads the store, one family of field at a time". `CommandSeamRatchetTest` counts no leaf `instanceof`, case pattern or `plan`
  reference left in the generator.

### Each emitter reads the store `open`

The generator turns over one emitter at a time, each held to behaviour by tests that run the
generated classes against PostgreSQL from the fact scenarios. The fixed runtime classes and the
output package they are written to are settled.

* **The fixed classes' renderers move into `emit`** `open`. The six classes `FixedRuntimeEmitter`
  renders are written by generators that name `GeneratedUnits`, `ErrorDispatchFragments` and the
  model, so the emitter sits beside them in `rewrite.generators.schema` rather than in `emit`.
  When they name only the store, javapoet and the package, they move into `emit` and the emitter
  follows them, its record of what it wrote no longer spelled in the planner's `GlobalUnitKind`.
* **The `util` package's fixed classes are not planned** `open`. `GraphitronValues`, `LightFetcher`,
  `ColumnValues`, `SelectionOccurrences` and `OrderByResult` are written from the package alone;
  `ConnectionResult` and `ConnectionHelper` take the tenancy flag, `NestingCoordinates` the nesting
  reach, and `OneOfDirectiveSdl` the federation link and `@oneOf`.
* **The configuration-read runtime classes read the store** `blocked`, on "The generator reads its configuration from the store". `ConnectionRuntime`'s units,
  `GraphitronConnectionInstrumentation`, `ConnectionResult` and `ConnectionHelper` take the tenancy
  and the session hooks from the store rather than from `GraphitronSchema`.
* **The node id encoder is not planned** `open`. It renders from the store already and is written
  straight from it, as `FixedRuntimeEmitter`'s classes are, and `GlobalUnitKind.NODE_ID_ENCODER`
  goes, with `PlanCompileGraph`'s edge to it.
* **`Query.node` and `_entities` each read their own query** `open`. Node resolution and entity
  resolution are separate lookups with a query each against the store (ruled 2026-11-12, in
  `fact-model.adoc`). `Query.node` reads the nodes off `graphitron_node`, filtered to those whose
  `id` binds `NODE_ID`, with their type ids and key columns; `EntityKeyQuery`'s alternatives become
  exactly the type's `@key` applications, so its prepended node id alternative and the resolvable-node
  disjunct in its entity selection go, as does the matching prepend in `EntityResolutionBuilder`. A
  key whose field binds the node id still decodes as `Query.node` does; today the two stay apart only
  because a synthesised key covers every federated node, which this makes a matter of shape.
* **The facade, dev executor, registrations and schema class read the store** `open`.
  `GraphitronFacadeGenerator`, `GraphitronDevExecutorGenerator`, `FetcherRegistrationsEmitter` and
  `GraphitronSchemaClassGenerator` take roots, nodes, scalars, error types and entities from the
  emitted schema and the store, once the facts below exist. Whether the entity dispatch and the node
  fetcher are emitted, decided from the walk by the plan, the schema class and the facade alike, is
  one fact this node states once; the dispatch itself renders from `EntityKeyQuery`.
  * **An enum's runtime value is stated by coordinate** `open`. It reads `graphitron_ast_enum_entry`,
    the store's only typed copy of an enum's `@enum` reference.
  * **A scalar's resolution is stated** `open`.
  * **A discriminated interface's type resolver is stated** `open`. It reads
    `graphitron_ast_discriminate_entry` and `graphitron_ast_discriminator_entry`.
  * **An enum value's ordering is read from the store** `open`. The generator reads an enum value's
    `@order` and `@index` off the schema; `graphitron_ast_order_entry`,
    `graphitron_ast_order_field_entry` and `graphitron_ast_index_entry` are the store's only typed copy.
  * **The `@error` handlers are stated** `open`.
  * **Each field's wiring is stated** `open`. The cases `FetcherEmitter.bind` switches on, read with
    the families of "`TypeFetcherGenerator` reads the store, one family of field at a time".
* **The type and input generators read the emitted schema and the store** `open`. The enum, input
  and object type generators, `InputRecordGenerator` and `ConnectionFetcherClassGenerator` stop
  taking the walk's types, and the variants stop carrying forms nothing renders.
  * **An absent emitted form is an invariant failure** `open`. `CarriesObjectForm.formOf`, the enum
    value source and the schema class's scalar form fail rather than fall back, ahead of the variants
    losing their forms.
* **`TypeFetcherGenerator` reads the store, one family of field at a time** `open`. Root queries
  (taken by "The root query fetchers are emitted from the store"), services, DML writes, routine writes, projected child fields, batched fetches, polymorphic
  fields, pivots and the errors field each render from a query at the grain they emit, the
  launcher's routine row reading the facts R704 captured, with
  `ConditionGlueRenderer` and `ProjectionUnitRenderer` folded into the families that use them.
* **The undecided fact documents are sorted** `open`. Each document under `undecided` becomes a
  scenario or states the defect it trips over, and the folder is gone; the ones the walk refuses with
  nothing stored wait on "Each rule the walk enforces is a located defect".

### A dev round costs what its reads' shapes ask `open`

A dev round's capture costs what the shape of each rule's reads asks for, not what an
unlucky plan or an unindexed lookup adds, and a regression fails a gate instead of waiting for
someone to profile.

* **Every partition read the plan gate lists is indexed or exempted** `open`. `RulePlanGateTest`'s
  known list is empty.
* **A capture's time grows in proportion to its schema** `open`. A synthetic schema captured at two
  sizes fails a stage whose time grows faster than the schema, which is what catches a join that fans
  out and a graph filter pushed into a rule's plan.
* **A stage's time does not grow with the other graphs in the store** `open`. The same schema
  captured into a store holding one graph and into one holding many fails a stage whose time grows
  with the number of other graphs, since a rule reading across graphs is a regression caught early.
  `GraphCountGrowthGateTest` holds it as a backstop, one graph's write and read time per relation
  alone and as one of eight within a generous multiple, and `UpsertKeyGateTest` holds the upsert
  shape the slack would miss; what is left is the tables both name as known.
  * **A stage's time includes its preparation** `open`. A capture records `prepare_ms` beside the
    execution time; what remains is `GraphCountGrowthGateTest.times()`, which sums execution only, so
    the backstop still misses preparation, about 70% of the store-heavy test JVMs' samples
    (`roadmap/audits/2026-11-09-why-the-build-takes-35-minutes.md`).
  * **A stage's rows written count every row a batch writes** `open`. `store_graph_write.rows_written`
    undercounts writes that bind one row per batch entry: on sis it records 22 rows for
    `graphitron_ast_field_path_entry`, which holds 828, and 23 for `graphitron_ast_path_entry`, which
    holds 1,244. `WriteTimes` sums `batchRows()` with negative counts read as zero, so a batch whose
    driver answers each entry without a count, as JDBC's `SUCCESS_NO_INFO` does, adds nothing; the
    cause is to be confirmed before the fix. Until then the column is wrong for every relation
    `BindBatch` writes, and a rows-per-millisecond comparison over it misleads.
  * **The growth gate fails only on growth** `open`, unowned. `GraphCountGrowthGateTest` failed two
    of about nine full builds in the CPU audit, each on a different relation and 6 to 7 ms past its
    bound, and once in the R876 harvest builds, where a collection pause or a stalled thread inside
    a measurement of a few milliseconds crosses it. A verification build that fails for that is not
    one; the gate takes a margin or repeated samples a pause cannot cross, and still fails a
    relation that reads across graphs.
  * **The entry defect rule reads one graph's rows** `open`, unowned. After the chain-break fix the
    whole rule filtered to one graph takes 54 ms with one graph in the store and 98 ms with eight;
    what still grew was its two column-match parts, 2 to 32 ms, which read
    `graphitron_field_target_column_match`, since gone. Inside the backstop's slack, so not on its known list.
    Its merge was also the costliest statement to plan in the build: the rule reaches 116 relations
    through 18 views, 318 CPU-seconds of preparation over a `graphitron` test phase (`roadmap/audits/2026-11-02-where-the-build-spends-its-cpu.md`);
    at the 2026-11-09 re-profile it is 4% of that JVM, and each of the path-need rule's two upserts is 12%.
  * **The TABLE binding's rule restricts to the graph and depth inside its view** `open`, unowned.
    `OccurrenceTables` writes `graphitron_occurrence_binding_table` from
    `graphitron_occurrence_binding_table_rule`, a union of five SELECTs under a DISTINCT with no
    graph predicate inside it. It ran once per depth until the depth loop kept only its recursion;
    it now runs once, in `occurrence-bindings`, but the writer's filter on the graph is still outside
    the view, which H2 does not push through the DISTINCT into the union, so it evaluates the whole
    rule over every graph's occurrences, and the view is inlined twice, once for the binding
    supertype and once for the subtype. On 2026-11-11 `GraphCountGrowthGateTest` failed on it,
    writing the table in 13 ms alone and 120 ms as one of eight graphs, at load 5.8 inside the
    model suite. It passed twice after at load 2.2, but 107 ms over is growth, not the few
    milliseconds of a pause. The rule takes the graph and the depth inside each SELECT, or the
    writer reads it as a parameterised select, and `prepare_ms` prices the change.
  * **An upsert into a table with an independent unique key is decided per table** `open`.
    `graphitron_federation_definition` is unique by local name, and `graphitron_ast_reference_element_entry` and
    `graphitron_ast_error_handler_entry` by the application's position and ordinal, none derived
    from the primary key, so naming the primary key turns a clash on the unique key from an update
    into a capture failure. `UpsertKeyGateTest` lists them.
* **A stage's statement reads tables, not a chain of views** `open`. A gate holds the cost of
  preparing each stage's statement, since on a fresh store most of a capture's time goes to H2
  expanding views rather than executing them.
* **A per-depth rule is planned once per depth** `open`. `PathNeeds.writeDepth` inlines its rule's union
  into two upserts and `OccurrenceSources.write` into four, so H2 plans the whole union that many
  times a depth; together they are a sixth to a fifth of the verification build's CPU, all of it
  planning (`roadmap/audits/2026-11-09-why-the-build-takes-35-minutes.md`).
* **No view reads a view, and every rule view is simple** `open`. The store's rulings
  (`fact-model.adoc` § Every relation has a grain) are not yet met. Views reading views:
  `graphitron_occurrence_call_pin` (and `_call_rule`, `_call_method_rule` reading it),
  `graphitron_entry_defect_parameter_rule`, `graphitron_outbox_key_rule`,
  `graphitron_argmapping_pair_defect_rule`, `graphitron_entry_defect_member_rule` (over
  `graphql_poly_member`), `graphitron_occurrence_call_parameter_rule`
  and `_defect`, `graphitron_argmapping_step_head_rule` and `graphitron_occurrence_node_id_rule`.
  Oversized rule views, each a derivation skipped upstream: `graphitron_entry_defect_rule`,
  `graphitron_occurrence_condition_parameter_rule`, `graphitron_occurrence_producer_lift_rule` and
  `graphitron_join_path_step_rule`. Each is fixed by storing the skipped derivation at its own
  grain, priced by `prepare_ms` on a cold capture.
* **A dev session's store is compacted when it ends** `open`. Compaction at close runs before H2's
  own shutdown hook closes the database, so a long session's store does not keep growing.

### A capture reads only what changed `open`

A round re-reads, re-anchors and re-derives only what an edit can have moved, so a dev round costs
what the edit touched rather than what the project holds. Deletion rides on the same grain: a
class, a document or an entry that goes takes what hung off it by cascade. What a sis round does
today is in `roadmap/audits/2026-10-19-where-a-dev-round-spends-its-time-on-sis.md`.

* **A classpath change reruns only the gatherers that read the classpath** `open`. A round that
  succeeds says which gatherers it ran and which it skipped.
* **Every `code_` row hangs off a class** `open`. `code_throwable` and `code_throwable_supertype`
  key into `code_class` rather than the entry, so a class that goes takes them with it.
* **A single changed class re-reads only itself, swept at the class file** `blocked`, on "Every `code_` row hangs off a class" and "`code_type` and `code_type_element` are gone". `CodeCapture`
  sweeps once, at `store_class_file`, and reads `byte_size` and `mtime` to skip an unchanged class.
* **A corrupt store is set aside and captured cold** `open`. When `generate`, `validate` or `dev`
  opens or commits to a warm store and H2 reports corruption (`MVStoreException`, "Unable to read
  the page"), the goal renames the store aside, keeping it as evidence, captures cold and warns with
  the moved file's name; today the goal fails and the author must know to delete
  `target/graphitron-model/`. Only H2's corruption codes count: a store locked by another process,
  or failing for any other reason, still fails the goal. A test truncates a store mid-page and
  shows the next capture succeeds with the old file beside it. Seen 2026-11-07, when sakila-example's
  warm store from before a machine restart failed a build every other module of which was green.

### A signature's type is a fact about the position that writes it `open`

A method, parameter or slot carries the parse of its own type (spelling, erased class, delivered
class, delivery) instead of pointing into `code_type`, a dictionary of spellings shared by every
class that mentions one. Shared rows cannot be invalidated per class, so the dictionary is what
keeps the classpath capture from working at the class grain.

* **The editor and the MCP render a type from the spelling the row holds** `open`.
  `ClassMemberSlots` and `ClasspathMethods` in the language server and `SchemaQueries.members` and
  `CodeQueries` in the MCP server format the spelling instead of joining `code_type`.
* **A delivered class the reading holds is referenced by its key** `open`. A position whose
  delivered class was gathered carries that class's entry beside its name, keying `code_class`;
  NULL says the class was not gathered.
* **`code_type` and `code_type_element` are gone** `blocked`, on "The editor and the MCP render a type from the spelling the row holds" and "`SeededStore` is deleted". The relations, their
  writer and the fixture's dictionary rows go; no reader joins on a spelling to fetch a parse.
  * **The modeling discipline's worked example follows them out** `blocked`, on "`code_type` and `code_type_element` are gone". `modeling-discipline.adoc` restates its example over the
    positions that carry the parse.

### Gatherers run in the order their corpora stand in `open`

The order gatherers run in is the order foreign keys can point in: the configuration, then the
classpath and the catalog, then the documents, then what resolves the documents against the rest.
The call order already follows it; what is left is a gate that holds it and the pages that say it.

* **A gate holds the call order to the roster** `open`. A test fails when `ModelCapture` calls a
  gatherer before one it declares in `meta_gatherer_dependency`.
* **The pipeline overview states the order and what holds it** `blocked`, on "A gate holds the call order to the roster". `pipeline-overview.adoc` § Capture names the order and the gate.
* **The pipeline overview describes a dependency's row** `open`. The `store_` bookkeeping sentence
  names `store_graph_dependency` and what each kind of entry lets a capture skip.
* **The manual says what the jOOQ package is read for** `blocked`, on "The generated jOOQ classes are read with their members". The `jooqPackage` row of `mojo-configuration.adoc` no longer implies
  the package is read differently from any other.

### The store knows whether an artifact can go stale `open`

Reading a jar is safe for a release and dangerous for a snapshot, and Maven settles which. The
store records what each classpath entry is and where it came from; what is left is the version,
treating each kind by what it can do, and catching a stale catalog.

* **A dependency is a coordinate and a version** `open`. The configuration gatherer records the
  version the build declares beside `store_graph_module`'s coordinate, and a dependency stops being
  keyed by the entry's path.
* **Three kinds get three treatments** `blocked`, on "A dependency is a coordinate and a version". A
  release jar needs no content stamp, a snapshot jar does, and only a directory needs the stat walk.
* **A catalog is compared with what generated it** `open`. The migrations or DDL the catalog's
  codegen read are captured as a fact, and a stale catalog is a warning beside
  `build_warning_stale_module`.
* **A directory's stamp covers what the reading reads** `open`. Regenerating jOOQ into a module that
  also holds services no longer makes every service class read again.

### Which types are nodes, with which id and key, is stated once and every reader reads it `open`

A node is what the manual says takes effect: a type that implements `Node` over a `@table` the
catalog resolves, with `@node` or published catalog metadata giving its type id and key columns.
Today that answer is settled from coordinate copies of `@node` and `@table`, refilled whole each
reading, and `graphitron_node_type` adds every written `@node` to it, so readers and the editor
disagree with the build about which types are nodes.

* **Table types, nodes and node key columns are marked and swept** `open`. An unchanged binding
  keeps its node and key columns rather than losing them to a clear and refill; `Nodes.derive` still
  deletes and refills `graphitron_node`.
* **A node is what the manual says takes effect** `open`.
  `graphitron_node_type` goes, its readers read `graphitron_node`, and
  `NodeDeclaration.isNodeType` moves to the same predicate in the same commit.
* **The editor and the MCP read the resolved node** `open`. Completion of `typeName:`, its
  squiggle and `SchemaQueries` read `graphitron_node` and `graphitron_node_keycolumn`, as the hover
  already does.

### A node id is a target type, read as its ID `open`

A `@nodeId` field, argument or input field has a target type like any other, the node type, and
says only that the value is that type's ID; the route to it is the ordinary forward walk.
`graphitron_node_id_instruction` blended what was written with what was inferred and the node-id
relations kept a private model of the route; both were replaced from scratch, reading nothing from
`intent_`, and are gone. `graphitron_node_id_instruction`'s insert cost 23 CPU-seconds of preparation over a
`graphitron` test phase, reaching 24 relations through 4 views (`roadmap/audits/2026-11-02-where-the-build-spends-its-cpu.md`).
The replacement is the binding's `NODE_ID` subtype, `graphitron_occurrence_node_id`: at each
occurrence, the node type, the rule that decided it and the node's key columns on its own table.

* **The corpus is written against the model** `open`. Fact documents state a written
  `typeName:` naming an interface over nodes beside the union already stated, and a bare `@nodeId`
  on a `@reference` field once its path's landing is filled.
* **An output field's node id is encoded from the store** `open`, F's.
  The encode is an emitter of its own with its own query over `graphitron_occurrence_node_id`,
  written when the store emits output selections; the walk dissolves into store emitters this way
  (Alf, 2026-11-06), and the decode already has, in `NodeIdDecodeQuery` and `NodeIdDecodeEmitter`.
  * **The classifier reads its routes from the store, the @nodeId filter first** `open`, F's.
    The classifier (`GraphitronSchemaBuilder.buildBundle`, `BuildContext`) takes an optional store
    handle, `TestSchemaHelper` capturing a store per distinct schema text as `emittedSchema` is
    cached, and the `@nodeId` filter's route becomes a store query owned by the resolver, so
    `NodeIdLeafResolver`'s `selectRoute`, `resolveFkJoinPath`, `walkExplicit` and `landKeyColumns`
    go. Meanwhile the walk's auto-discovery counts the one key in both directions, as the store
    does, an interim (Alf, 2026-11-07).
* **An overload accepts a member by two spellings** `open`. Its parameter is the member's own
  record or one of `Object`, `Record`, `TableRecord<?>` and `UpdatableRecord<?>`, most specific
  first.
* **The destination names the overload** `open`. A `graphitron_` relation holds one row per member
  naming the overload it lands in, and a member no overload accepts is a defect.
* **The overloads differ only in the polymorphic parameter** `open`. Overloads that differ in
  another parameter or in argument mapping are refused.
* **The generator dispatches from these rows** `blocked`, on "The destination names the overload".
  R984 owns the dispatch and reads the rows the three nodes above write.
* **The record supertype walk goes** `blocked`, on "The generator dispatches from these rows".
  `sql_table_record_supertype` and the capture walk that climbs a record class are deleted.

### Seeding dissolves, and the fixture goes with it `open`

A seeded test writes a relation's columns by hand, so a relation cannot go while a test seeds it
and a reshape breaks tests whose behaviour did not change. Every seeded class becomes a fact
document or a captured Java case, and `SeededStore` is deleted; this frees the subtractions
elsewhere in the graph.

* **Every other seeded class converts by its subject** `open`. 21 test classes still name
  `SeededStore`, against `SeedingDissolutionGateTest`'s ceiling of 16; fields, mutations, input
  fields and arguments convert whole, a subject listing its corpus gaps once before its first
  conversion.
* **`SeededStore` is deleted** `blocked`, on "Every other seeded class converts by its subject". The fixture goes, and `SeedingDissolutionGateTest` goes with them.

### What the converted seeded cases turned up is fixed `open`

Stating the seeded cases as documents put real schemas in front of rules that had only met
hand-written rows, and six of those rules answered wrongly, threw, or never fired. Each is fixed
here with a document that shows it.

* **A `@nodeId` beside `@field(name:)` names a target of the decode's shape** `blocked`, on "A table class's column fields are stated" and "What an occurrence takes is a binding".
  The name is the `NODE_ID` claim's target: under a table source a column, under an object source a
  member, and its type, a column's generated `Field<T>` or the member's, matches the shape the encode
  or decode needs, a mismatch the defect (Alf, 2026-11-05).
* **A `@nodeId` argument walking its own path decodes the type it arrives at** `blocked`, on
  "The classifier reads its routes from the store, the @nodeId filter first". On the field below
  the store resolves `Actor` from the table the path arrives at, while the generator infers `Film`
  from the field's table and emits `actor.FILM_ID`, which does not compile. The generator never
  supported the shape and is not patched for it: reading the store's target type is the fix. When
  it lands, the fragment goes into the scenario `node-id-instruction-bases`, which already declares
  `Film` and `Actor` as nodes: the field into its `extend type Query`, the row into its
  `graphitron_occurrence_node_id` expectation.

  ```graphql
  extend type Query {
    filmsByActor(byActor: ID @nodeId @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])): [Film]
  }

  extend schema @expectEquals(relation: "graphitron_occurrence_node_id", rows: """
    coordinate,                   bound,        position, basis,                  node_type_name, column_name
    Query.filmsByActor(byActor:), public.actor, 0,        TARGET_TABLE_NODE_TYPE, Actor,          actor_id
    """)
  ```

### The agreement test dissolves, each case to the corpus it pins `open`

`FactCaptureAgreementTest` and the shadow tests compared the store against the walk. Each case
ends up pinned where its corpus or its writer lives, and the registration says what anchors a
relation rather than what it shadows.

* **The oracle writers' lifecycle cases stay with their writers** `open`. The javac partition and
  loaded diagnostics cases move to a `graphitron` test named for what it pins.
* **A registration says what pins a relation, not what it shadows** `open`. Each `graphql_` and
  `graphitron_` relation is registered as corpus, derivation or oracle writer, and one left with no
  anchor is reported.
* **Any relation that carries the graph can be asserted empty** `open`. The corpus's comment-phrase
  silence check goes, and `@assertEmpty` applies to every relation carrying the graph.
* **The fact model cites what pins it** `blocked`, on "A registration says what pins a relation, not what it shadows". `fact-model.adoc` stops naming `FactCaptureAgreementTest`.
* **`FactCaptureAgreementTest` is gone** `blocked`, on "The oracle writers' lifecycle cases stay with their writers", "A registration says what pins a relation, not what it shadows" and "Any relation that carries the graph can be asserted empty".
* **The shadow tests go with their walk side** `blocked`, on "A node is what the manual says takes effect" and "The generator builds no schema". `RoutineSpentInputShadowTest`
  and `NodeTypeShadowTest` are deleted.
* **The corpus goes** `blocked`, on "The shadow tests go with their walk side". The corpus
  documents and `CorpusStore` are deleted, each reader becoming a fact document or going with the walk
  it tests; `CorpusExpectations` and `CorpusDocuments` stay, the fact documents' assertions and loader.

### The editor's answers are tested where the editor gets them `open`

Hover and inlay hints went blank in a real editor session while every test passed, because no
test takes the path a session takes: the dev goal's own capture read through `Workspace`. Each
gate on that path answers nothing when it fails, and the store must survive the session.

* **A dev round answers hover and inlay hints over the wire** `open`. A plugin-tier test runs a dev
  round over a schema on disk, attaches a client, opens the file and asserts both answer.
* **A read that answers nothing says why** `open`. No store and a document no graph read each
  report once per document, as an out-of-budget read already does.
* **A hover on a type name written as a field's type answers** `open`. It shows the type as a
  hover on its declaration does.
* **A store whose file is gone fails loudly** `open`. A `ClosedChannelException` on read closes the
  store and reopens it cold rather than serving from page cache.

### The data model is written down as it now stands `open`

Contributors read the store's shape from one architecture page that states what this item learned:
the store is the model, facts are keyed by where they were written, the emitted schema is decided
in capture, and a path is one forward walk from source to target.

* **The data model is an architecture page beside the fact model** `open`. A page under
  `docs/architecture/explanation/` states the coordinate, its small facts and the emitter decision,
  rewritten from R333 rather than copied, landing with or after R1163's vocabulary.
* **The documentation examples render without the walk** `open`. `QueryViewRenderer` builds its
  schema with `GraphitronSchemaBuilder`; it reads the emitted schema capture builds instead, so a
  page's examples outlive the walk.
* **A view's column prose lives where the store keeps it** `open`. H2 2.5 refuses `COMMENT ON
  COLUMN` on a view, and 2.4 already dropped those comments when a file store was reopened, so the
  1202 are SQL comments in `graphitron-model.sql` for now. They move to the view's own comment, to
  a `meta_view_column` relation, or go for a rule view whose stored table states the same columns;
  `FactSchemaGateTest`, `CommentRenderabilityGateTest` and the schema reference pages widen back.

### The build cannot hang or fail on its own fixtures `open`

A build that hangs or fails for reasons no change caused costs every session a rerun and makes a
real failure look like noise. The LSP test JVM crash seen once is in the two
`roadmap/audits/2026-10-21-lsp-test-jvm-sigsegv-*` audits, for a recurrence to be read against.

* **A capture's mark is a UTC instant, bound without the JVM's zone** `open`. `touched_at` binds
  through `java.sql.Timestamp` and the default zone, so a zone switch mid-capture splits the mark
  and the sweep deletes parents under live children; a `forcedType` binding that hands H2 the
  `LocalDateTime` itself, the mark taken at UTC, removes the dependence without touching the DDL.
* **A test that sets the JVM's zone or locale is isolated, and the build says so** `open`. A scan
  of test sources fails a `TimeZone.setDefault` or `Locale.setDefault` in a class without
  `@Isolated`; the UTC mark makes it hygiene rather than the capture's only defence.

## User documentation (first-client check): paths

User-visible authoring, so the docs draft is part of the design of
"Arguments and input fields are resolved as fields are, from a target model". It replaces the rules on
`docs/manual/reference/directives/reference.adoc` and `referenceFor.adoc`, and the shapes
`docs/manual/how-to/polymorphic-types.adoc` walks through, when that section ships. Until then the
manual describes what the generator does today, which differs: it rejects `@referenceFor` on a
single-table polymorphic field, rejects any field-level `@reference` on a multi-table child, and
inherits nothing from an interface's field. If the draft does not read simply, the rules are wrong
and change before implementation.

### `@reference` and `@referenceFor`: where a path starts and where it lands

A field whose type is bound to a table is a *path field*: selecting it joins from the table the
field's rows come from to the table its type is bound to. The path starts at the type the field is
declared on and lands on the type the field returns.

**Where a path starts.** A union is never bound to a table, so a path field sits on an object type or
an interface.

* On an object type with `@table`, the path starts at that table.
* On an interface with its own `@table`, the path starts at the interface's table.
* On an interface whose implementations each carry their own `@table`, there is no one table to start
  at, so there is one path per implementation, each starting at that implementation's table.

A path written on an interface's field is inherited by every implementation's field. An
implementation states its own path on its own field to start from its own table instead.

**Where a path lands.**

* A field returning an object type with `@table` lands on that table. `@reference` states the path.
* A field returning an interface with its own `@table` lands on the interface's table, stated by
  `@reference`. `@referenceFor(type:)` states a different path for one implementation where it needs
  one; the others take the interface's.
* A field returning a union, or an interface whose implementations each carry their own `@table`, has
  no one table to land on. There is one path per member, each stated by `@referenceFor(type:)` naming
  the member. `@reference` on such a field is an error, since it has nothing to land on.

**Paths you do not write.** Where exactly one foreign key joins the two tables, you need not write the
path; the generator takes that key. Every path a field needs must be written, inherited or decided
this way; a field left without one is an error naming the implementation or member it lacks.

**A condition is chosen by both tables.** A `{condition:}` element names a method, and each path calls
the overload whose two table parameters take the table the step leaves and the table it reaches. One
`@reference` may therefore stand for several paths that differ in where they start: written on an
interface's field, each implementation's path calls the overload for its own table.

A condition may not stand for paths that differ in where they land. Write one `@referenceFor` per
member instead; they may all name the same method, and each calls the overload for its member's table.

**Examples.** Each example is a query file beside the page, selecting from the fact document
`scenarios/path-rules` drafted below; the build renders it to the SDL the page includes, so an example
cannot say what the store does not. Three files carry the rules above:

```graphql
# examples/interface-paths.graphql
fragment InterfacePaths on Person @factDocument(path: "scenarios/path-rules") {
  address { addressId }
  homeAddress { addressId }
  customerAddress { addressId }
  store { storeId }
}
```

`Person` has no table, so each of `Customer` and `Staff` starts from its own. `address` is inferred
from each one's key to `address`. `homeAddress` is one written path that calls
`homeAddress(Customer, Address)` from `customer` and `homeAddress(Staff, Address)` from `staff`.
`customerAddress` names a method with no overload for `staff`, so it is an error at
`Staff.customerAddress`, naming the pair. `store` is inferred for `Customer`, while `Staff`, with two
keys to `store`, states its own path on its own field.

```graphql
# examples/member-paths.graphql
fragment MemberPaths on Store @factDocument(path: "scenarios/path-rules") {
  people { ... on Customer { address { addressId } } }
  peopleByKey { ... on Customer { address { addressId } } }
  peopleByCondition { ... on Staff { address { addressId } } }
}
```

`people` lands on two tables: `Customer`'s path is inferred and `Staff`'s is stated with
`@referenceFor`. `peopleByKey` is an error, a `@reference` with no one table to land on.
`peopleByCondition` names one method in a `@referenceFor` per member, each calling the overload for its
member's table.

```graphql
# examples/argument-paths.graphql
query ArgumentPaths @factDocument(path: "scenarios/path-rules") {
  people(storeId: "c3RvcmU6MQ==") { address { addressId } }
}
```

On an argument or input field the path starts at the type the consuming field returns, once per
consuming field, here at each implementation of `Person`, and lands on the type the argument
targets, `Store`. `@nodeId` says only how to read the id.

**Who takes an argument's path.** An argument or input field is taken by one consumer: a method a
directive calls, which receives its value as a parameter, or else graphitron, which writes a
predicate from it. A path on it hands that consumer tables. Graphitron writes its predicate on the
path's landing, and a method receives the path's tables in its table-typed parameters, chosen by
type as a condition's overload is, so `@condition` and `@nodeId` each combine with `@reference`. A
path whose consumer takes no table, such as a `@service` method that takes only values, is an
error. Graphitron cannot add to what a `@service` or `@externalField` method returns, so under
those an argument the method does not take is an error; under `@condition` and `@routine`
graphitron writes a predicate from it.

### Draft fact document: `scenarios/path-rules.graphqls`

One document covers the rules, one field per rule, so the manual's examples select from it through
query files beside the pages rather than carrying SDL of their own. It cannot run before "A path is keyed per pair of tables"
lands; it is the document "`scenarios/path-rules` runs" asks for, and the
condition parts of `undecided/field-path-and-participant-path`, `undecided/condition-method-route`,
`undecided/condition-method-route-departures`, `undecided/field-path-condition`,
`defects/reference-step-target-refused` and `defects/argument-reference-step-target-chain` move into
it. `graphitron_path` stands for the per-pair relation the target model declares, one row per field,
target member and pair of tables, with where the path came from; its final name and columns are that
node's to settle. The `PathRulesFixtures` methods are new, in `graphitron-sakila-service` beside
`ConditionMethodRouteFixtures`; the defect codes after `ROUTE_AMBIGUOUS` are new too.

The rules it does not cover yet: a lifted field's start, which needs a record-backed type, and an
inferred path on a single-table interface's own field.

```graphql
# The path rules, one field per rule, over the sakila catalog. customer and staff each have one key
# to address; customer has one key to store, staff two (staff.store_id, and store.manager_staff_id
# back to it); film has two keys to language.

type Address @table(name: "address") { addressId: Int @field(name: "address_id") }
type Store implements Node @table(name: "store") @node { storeId: Int @field(name: "store_id") }

"An interface no table backs: each implementation's path starts at its own table."
interface Person {
  "Inherited, and inferred from each implementation's one key to address."
  address: Address
  "Inherited, one condition, an overload per starting table: homeAddress(Customer, Address), homeAddress(Staff, Address)."
  homeAddress: Address @reference(path: [{condition: {className: "no.sikt.graphitron.rewrite.test.conditions.PathRulesFixtures", method: "homeAddress"}}])
  "Inherited, and only homeAddress's Customer overload exists for this method: Staff has no overload for its pair."
  customerAddress: Address @reference(path: [{condition: {className: "no.sikt.graphitron.rewrite.test.conditions.PathRulesFixtures", method: "customerOnlyAddress"}}])
  "Inferred for Customer; ambiguous for Staff, which states its own."
  store: Store
}

type Customer implements Person @table(name: "customer") {
  address: Address
  homeAddress: Address
  customerAddress: Address
  store: Store
  "A scalar path: a column reached through two keys."
  city: String @reference(path: [{key: "customer_address_id_fkey"}, {key: "address_city_id_fkey"}]) @field(name: "city")
}

type Staff implements Person @table(name: "staff") {
  address: Address
  homeAddress: Address
  customerAddress: Address
  "Overrides the inherited path, which cannot be inferred from staff."
  store: Store @reference(path: [{key: "staff_store_id_fkey"}])
}

type Film @table(name: "film") { title: String }

"A single-table interface: one table, members told apart by a column."
interface MediaItem @table(name: "film") @discriminate(on: "text_rating") { title: String }
type FilmItem implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
type ShortItem implements MediaItem @table(name: "film") @discriminator(value: "short") { title: String }

type Language @table(name: "language") {
  "The interface's own path, with one member reached by another key."
  media: [MediaItem!]!
    @reference(path: [{key: "film_language_id_fkey"}])
    @referenceFor(type: "ShortItem", path: [{key: "film_original_language_id_fkey"}])
  "Two keys from language to film and no path: ambiguous."
  films: [Film!]!
}

extend type Store {
  "A target with no table of its own: Customer inferred, Staff stated."
  people: [Person!]! @referenceFor(type: "Staff", path: [{key: "staff_store_id_fkey"}])
  "A @reference on a target with no table to land on."
  peopleByKey: [Person!]! @reference(path: [{key: "customer_store_id_fkey"}])
  "The remedy for a condition that differs by member: one @referenceFor each, one method."
  peopleByCondition: [Person!]!
    @referenceFor(type: "Customer", path: [{condition: {className: "no.sikt.graphitron.rewrite.test.conditions.PathRulesFixtures", method: "storePerson"}}])
    @referenceFor(type: "Staff",    path: [{condition: {className: "no.sikt.graphitron.rewrite.test.conditions.PathRulesFixtures", method: "storePerson"}}])
  "Two overloads take the one pair: agreeingOnBoth(Customer, Address) and the same with a String."
  tied: Address @reference(path: [{condition: {className: "no.sikt.graphitron.rewrite.test.conditions.ConditionMethodRouteFixtures", method: "agreeingOnBoth"}}])
}

extend type Query {
  people(
    "Per member of Person, to the node type's table: Customer inferred, Staff stated."
    storeId: ID @nodeId(typeName: "Store") @referenceFor(type: "Staff", path: [{key: "staff_store_id_fkey"}])
    "A @reference on an argument whose consumer returns members on two tables."
    byKey: ID @nodeId(typeName: "Store") @reference(path: [{key: "customer_store_id_fkey"}])
  ): [Person!]!
  languages: [Language!]!
  stores: [Store!]!
}

extend schema @expectEquals(relation: "graphitron_path", where: "origin <> 'EMPTY'", rows: """
  coordinate,                target_type, start_table,  target_table,  origin
  Customer.address,          Address,     customer,     address,       INFERRED
  Customer.city,             String,      customer,     city,          WRITTEN
  Customer.customerAddress,  Address,     customer,     address,       INHERITED
  Customer.homeAddress,      Address,     customer,     address,       INHERITED
  Customer.store,            Store,       customer,     store,         INFERRED
  Language.media,            FilmItem,    language,     film,          WRITTEN
  Language.media,            ShortItem,   language,     film,          WRITTEN
  Query.people(storeId:),    Customer,    customer,     store,         INFERRED
  Query.people(storeId:),    Staff,       staff,        store,         WRITTEN
  Staff.address,             Address,     staff,        address,       INFERRED
  Staff.homeAddress,         Address,     staff,        address,       INHERITED
  Staff.store,               Store,       staff,        store,         WRITTEN
  Store.people,              Customer,    store,        customer,      INFERRED
  Store.people,              Staff,       store,        staff,         WRITTEN
  Store.peopleByCondition,   Customer,    store,        customer,      WRITTEN
  Store.peopleByCondition,   Staff,       store,        staff,         WRITTEN
  """)

extend schema @expectEquals(relation: "graphitron_entry_defect_site", rows: """
  code,                           coordinate
  CONDITION_NO_OVERLOAD_FOR_PAIR, Staff.customerAddress
  CONDITION_OVERLOAD_TIE,         Store.tied
  REFERENCE_WITHOUT_TARGET_TABLE, Query.people(byKey:)
  REFERENCE_WITHOUT_TARGET_TABLE, Store.peopleByKey
  ROUTE_AMBIGUOUS,                Language.films
  """)
```

What the document pins beyond the rows:

* A row is keyed by the type the need reaches, each member of a single-table interface its own row,
  as `Language.media`'s `FilmItem` and `ShortItem`; the empty paths are filtered out. A written
  path's target table comes from its steps, so a path that stops has no row: `Staff.customerAddress`
  has none, as `Store.tied` has none.

* `Staff.store` states its own path because the inherited one cannot be inferred from `staff`; without
  it the document would carry `ROUTE_AMBIGUOUS` at `Staff.store`, which is how a missing path for one
  implementation reads.
* `Staff.customerAddress` inherits a path and draws the defect at the implementation's own field, the
  written position an author edits, naming the pair `(staff, address)` in its detail.
* The `homeAddress` rows are one written path resolved twice, by the overload each starting table
  takes; `graphitron_field_path_method`, or its successor, holds one method row per pair.
* `peopleByCondition` reuses one method across members; `storePerson(Store, Customer)` and
  `storePerson(Store, Staff)` are each taken by one pair. Written as one `@reference`, the same field
  would draw `REFERENCE_WITHOUT_TARGET_TABLE`, as `peopleByKey` does.

## Nested coordinates and their paths (draft)

**What we are after.** A field means one thing wherever the store holds it. Today a field is keyed
by `(type_name, field_name)`, once per declaration, but a field of a type with no table of its own
(a *nested type*, reached under a field of another type) and a field of an input type mean
something different under each parent: which table they read, where their path starts, what their
parent holds. The store tells parents apart by a table column added to the key instead
(`from_*`, `start_*`, `table_*`, `resolving_*`, `origin_*`). So a field reached from two parents on
one table is one row, a field reached from two tables is two rows nobody can name, and every reader
that wants "the table" has to choose. The goal is to state such a field once per parent chain, at a
*nested coordinate*, so every parent-dependent fact has one row per coordinate and readers read it
without choosing. And every occurrence has a path, an empty one ending where it starts, so the
tables a field reads and the column it binds are read off its occurrence's path, not off a relation
per question that each choose a table.

**The coordinate.** The field's place in the schema, spelled `TypeName.nestingField.nestedField`
from the nearest field whose parent is not nested, its nested steps dotted as argMapping already
spells a path (`filter.actorRef.actor_id`). An argument keeps the specification's `(argument:)`, and
an input field is spelled inside the parentheses, from the argument: `Type.field(x:)` names the
argument, `Type.field(x.y:)` and `Type.field(x.y.z:)` input fields under it, and `Type.field.x` only
ever a nested output field. `Emne.navn.en` is `en` on the nested type `EmnenavnAlleSprak` under
`Emne.navn`, and `Query.films(filter.cast.firstName:)` an input field under the argument `filter`.
A field declared on an interface or reached through a union is spelled from the
participant, never the interface: `Film.title` and `Book.title`, not `Media.title`, so each
coordinate departs from the participant's one table. A field whose parent is table-bound or a root
has exactly one coordinate, its own `Type.field`, so the population is uniform: every field has at
least one.

**What the stores hold.** Measured on sakila, sis and the three FS-plattform consumer modules.

[cols="3,1,1,1,1,1"]
|===
| | sakila | sis | tilgangsstyring | utdanningsregisteret | opptak

| Nested output types | 156 | 727 | 55 | 96 | 190
| Their fields (one row each today) | 290 | 1,591 | 127 | 211 | 465
| Nested types under more than one parent field | 33 | 14 | 4 | 4 | 22
| Nested types whose rows come from more than one table | 1 | 3 | 0 | 1 | 1
| Nested coordinates, one per parent chain and field | 550 | 2,516 | 351 | 335 | 854
| Deepest nesting chain | 2 | 3 | 2 | 3 | 4
| Input fields (one row each today) | 277 | 2,526 | 116 | 359 | 769
| Input occurrences already enumerated (`intent_input_occurrence_path`) | 651 | 3,028 | 160 | 442 | 1,162
| Deepest input chain | 3 | 4 | 1 | 5 | 5
|===

No store has a cycle in its output nesting. Stating a field per parent chain costs 1.6 to 2.8 times
today's nested field rows. "Nested output type" counts every object or interface no table binds that
is some field's target, payloads and service results included, not only directiveless types under a
table.

**What already works this way.**

* The generator classifies a nested type's fields once per parent field (`FieldBuilder` builds a
  `ChildField.NestingField` per embedding edge, resolved against that parent's table), but emits one
  class per nested type from a representative edge (`NestingReach`), so
  `GraphitronSchemaValidator.validateNestingParentCompat` refuses reuse where parents disagree on a
  column, its class, a filter or the presence columns, and tells the author to split the type.
* The generator resolves input fields per use (`TypeBuilder.resolveInputFields` against the
  consuming field's table), keyed by `ClassifyContext.UseSite`: the root field, the argument and the
  chain of input fields under it. That is the input half of the nested coordinate.
* `intent_input_occurrence_path` and its steps enumerate input occurrences, spelled
  `Type.field(arg)/inputField/...`, and `graphitron_node_id_instruction.use_site` reuses that
  spelling. Both are derivation-written.
* The path model draft below keys its needs on the occurrence, in this section's spelling.

**The rules.** Where an occurrence's values come from, how its path runs and what it binds are
stated once, in the path model's Source, path, bind below. They grew from rules drafted here and
checked against sis on 2026-11-02 and 2026-11-03, the check below.

**Checked on sis**, the sources, path ends and columns those rules give, against today's relations,
per declared field; the scripts are a session's, not the tree's.

[cols="3,1,1,2"]
|===
| | today | path model | difference

| Output field departures, against `graphitron_field_source_table` | 3,772 | 3,772 | none
| Scalar columns, against `graphitron_field_target_column` | 1,913 | 1,915 | the node identity of two `Node` types' unbound `id`
| Argument tables, against `graphitron_argument_scope_table` | 970 | 1,470 | none missing; 436 rows for `first:`/`after:`, which `graphql_argument` lacks, and 46 `@service` mutations over a record-backed payload, whose inputs now take a table through a `TableRecord` parameter
| Input-field tables, against `graphitron_input_field_resolving_table` | 1,803 | 2,236 | none missing; the 433 extra are the 93 input types under those 46 services
|===

The check ended a same-table `@table` target with nothing written at its target's table, which the
rules now refuse; no count depended on it.

**The relations, sorted.** Of 118 field-keyed relations (excluding `_rule` views and the `intent_`
family), 76 state a fact about the field as declared and stay keyed by `Type.field`: its written
directives, its declared type and arguments, the emitted schema, the reference spellings. The ones
that depend on the parent fall three ways.

* Dissolve into the occurrence and its path. Each is a read of the occurrence, its path and what the
  path's end resolves; none re-keys first.
  ** `graphitron_field_source_table`: the occurrence's source.
  ** `graphitron_field_scope_table`, `graphitron_argument_scope_table` and
     `graphitron_input_field_resolving_table`: the end of the field's path, forks included.
  ** `graphitron_field_target_column`: `graphitron_occurrence_column`, the `COLUMN` binding at the
     path's end, and its defects.
  ** `graphitron_node_id_decode_column`, `_hop` and `_hop_column`, and
     `graphitron_node_id_instruction`, all written by `DerivationStratum`: the `NODE_ID` binding,
     `graphitron_occurrence_node_id`, at the path's end.
  ** The `graphitron_field_path` family and its starts and stop, the field, argument and input-field
     reference step targets and hops, keyed and keyless: the path family, as the path model draft
     below lists.
  ** `intent_input_occurrence_path` and its steps: the input occurrences.
  ** `graphitron_result_table`: a table-bound type's table is `graphitron_tabletype`, a record-backed
     type's its record class's table, and a nested type's the start of its fields' occurrences.
* Dissolve into declared facts, keyed by `Type.field` or by type.
  ** `graphitron_field_target_table`: the arriving type is the declared target, or each member of a
     single-table interface, read off `graphitron_field_target`, `graphql_poly_member` and
     `graphitron_tabletype`; the needs carry one per member.
  ** `graphitron_resolved_type_binding`: dissolved into `graphitron_tabletype` (2026-11-04).
  ** `graphitron_join_path_claim` is already keyed by the declared element and target type.
* Stay keyed on the occurrence, `(graph_name, coordinate)` with a foreign key to it:
  ** `graphitron_occurrence_source`, one per occurrence, and its subtypes on the same key:
     `graphitron_occurrence_source_table` and `graphitron_occurrence_source_object`, the lift being
     the producer's;
  ** the path family itself, `graphitron_path_need`, `graphitron_path`, `graphitron_join_path_step` and its
     children, and `graphitron_join_path_stop`;
  ** `graphitron_occurrence_binding` and a subtype per kind, keyed by
     `(graph_name, coordinate, bound)`, the bound spelled from names: a table for `TABLE`, `COLUMN`
     and `NODE_ID`, a forked end being a binding per table, the method for `EXPRESSION` and
     `RESULT_EXPRESSION`, and the class
     and member for `VALUE`
     (`no.sikt.fs.EmneNavn#getEn`):
     `graphitron_occurrence_column` with its `sql_column` and whether the SQL or the jOOQ name
     matched, `graphitron_occurrence_node_id`, one row per key column by ordinal,
     `graphitron_occurrence_binding_table`, `graphitron_occurrence_binding_value`,
     `graphitron_occurrence_binding_member` and E's `EXPRESSION` subtype. A miss is `COLUMN_UNRESOLVED` or `COLUMN_AMBIGUOUS` at the field's written
     position, and for an input under a parent bound `TABLE` only where no parameter fill takes it;
  ** `graphitron_field_key_row` and its method subtype: the keys a parent hands a child `@service`,
     which no path end states;
  ** `graphitron_producer`, `graphitron_reader` and `graphitron_fetcher`, whose rules already decide
     per parent (a nested field is a re-read producer where a write, routine or service returns its
     parent; a reader only where its parent has one result shape).
  ** Unsorted: `graphitron_result_pivot`, which follows what it reads; it is sorted when its readers
     are.

**The relations, with their grains.**

* `graphitron_occurrence`, anchoring: an occurrence of an element, one use of a declared field,
  argument or input field reached along a chain of parents, as the fact model's use-keyed facts are
  keyed on it and its definition's on `graphitron_element`. One row per nested coordinate, keyed by `(graph_name, coordinate)`. It carries:
  ** `element_coordinate`, the declared element it is an occurrence of, in `graphitron_element`,
     which holds fields, input fields and arguments alike. Nullable, with a foreign key there
     `ON DELETE SET NULL`: an anchor's key to what it was built from does not cascade, and the sweep
     removes a row whose element went.
  ** `parent_coordinate`, the occurrence it hangs under, null where a chain starts, with a cascading
     foreign key to itself, which runs downstream.
  ** `name`, the field or argument name at this step, and `kind`, `FIELD`, `FIELD_ARGUMENT` or
     `INPUT_FIELD`, so an argument and a field are told apart without parsing, with an index on
     `(graph_name, parent_coordinate, name)`: a written path such as argMapping's `input.a.b`
     resolves as one keyed join per segment, never by building or parsing a coordinate.
  ** `depth` and `touched_at`.
  ** Its owner is `graphitron`, whose inputs it reads (`graphitron_element`, `graphitron_field`,
     `graphitron_field_target`, `graphitron_tabletype`, `graphitron_argument`, `graphitron_type`).
     A type is table-bound where `graphitron_tabletype` binds it, the anchor's own one-row-per-type
     binding. It is
     written as a step of `GraphitronAssemblyCapture`, ahead of the target table and column, because
     the field targets it reads are written there; it moves with the other steps when that class
     dissolves into the anchor's own lines. Marked and swept.
  ** Who has an occurrence, below.
  ** Where a chain starts. Every field of a root type, of a table-bound type, and of a type no field
     targets has one occurrence at its own `Type.field`, with no parent.
  ** Output nesting. Under an occurrence whose field targets an object type with no table, each field
     of that type has one at `<coordinate>.<field>`. The chain grows only through such a nested
     field, and a table-bound field is not nested by definition: a target with a table, an interface
     or a union ends the chain, its fields or its participants' fields starting their own, so
     `Store.people` has no `Store.people.name` and an `errors` field none under it.
  ** A single-table interface's implementations without a `@table` of their own are types no field
     targets, so their fields start at their own coordinate, `Film.title`; a reader takes the start
     table from the binding, as the path needs do.
  ** Arguments. Each argument of a field occurrence has one at `<coordinate>(<argument>:)`, under that
     occurrence: `Query.films(filter:)`, and on a nested field `Emne.navn.en(lang:)`. An argument's
     parent-dependent facts, the table its content binds against and its `@reference` steps, key on
     it as a field's do.
  ** Input nesting. Under an argument occurrence whose type is an input object, each input field has
     one inside the argument's parentheses, `Type.field(arg.field:)`, and an input-object-typed field
     descends further: `Query.films(filter.cast.firstName:)`, the population
     `intent_input_occurrence_path` holds today, in this spelling.
  ** Cycles. A chain does not expand into a type already on it, the first-visit guard
     `InputOccurrencePaths` and the classifier apply; the occurrence that would close the cycle has a
     row and does not expand. No store measured has an output cycle; the deepest input chain is 5.
  ** Writing. `graphitron_occurrence_start_rule` names the starts and
     `graphitron_occurrence_step_rule` one step down from an occurrence the reading wrote; the writer
     merges a depth at a time, bounded by the type count, as `InputOccurrencePaths` writes, since a
     recursive view is not safe over a cyclic graph. A pass knows its parents' depth, so it reaches
     each ancestor by that many joins up the parent links for the first-visit guard; the instant and
     the depth are bound as parameters. On sis it writes 10,394 occurrences, 2,487 of them nested
     output and 3,028 the argument and input occurrences, in 0.55 s cold and 0.64 s warm.
* Settled on the occurrence:
  ** A field declared on an interface. Under the participant rule `Media.title` is reached as
     `Film.title` and `Book.title`, each a declared field, so `Media.title` has an occurrence only
     where the interface has a table of its own, and none otherwise.
  ** An interface or union target ends the chain, whatever its participants: the chain grows only
     through a nested field. So no coordinate names a participant's field through it, and no defect
     is needed; an `errors` union of `@error` types and `Query.node` over table-less participants are
     ordinary.
  ** A written path deeper than the cycle guard, `input.parent.parent.x` through an input type that
     nests itself, has no occurrence to bind to; the binding reports it as an unsupported deferral,
     as `graphitron_argmapping_candidate.closes_cycle` does today.
  ** An argument has an occurrence of its own, under the field occurrence it is written on, and the
     input fields under it hang beneath it.
* `graphitron_node_id_instruction.use_site` and `intent_input_occurrence_path` go with the
  relations they belong to, sorted above.

**What changes for readers.** The editor resolves a cursor to a declared field, so a hover on a
nested field lists its occurrences by coordinate rather than its tables, and completion and the
member-name diagnostic read the occurrence's target table instead of finding none, which they do
today. MCP's `fieldRef` carries the coordinate; `Type.field` is the coordinate of a field with one
occurrence, so nothing changes on the wire for those. The generator reads one fact per occurrence
where it rebuilds them per edge today.

**Owners and order.** Agreed with A, F, E and Alf (2026-11-03); the graph's nodes carry it.

1. G: the input-field spelling in `graphitron_occurrence_step_rule` and its fact document, done.
2. F: the path writer, widened to every occurrence: needs with their forks (written
   2026-11-04), then paths with `EMPTY` beside the other origins, steps and stops, by the rules the
   path model below states.
3. G: `graphitron_occurrence_column` and the `@nodeId` unpack, once paths stand. Then
   `graphitron_field_target_column` and the `graphitron_node_id_decode_*` family go, the editor's
   column scope moves off `intent_field_column_table` onto it, and `graphitron_field_target_table`
   dissolves into declared facts.
4. `graphitron_field_scope_table`, `graphitron_argument_scope_table` and
   `graphitron_input_field_resolving_table` went on 2026-11-12 with no reader left to move, and
   `intent_input_occurrence_path` before them.
5. E: `graphitron_field_source_table` and a field's source are the occurrence's source and come off
   E's graph into step 2 (both landed).
   "A child producer's lift agrees with the classifier's key lift" stays E's, keyed on the occurrence;
   "An object result is lifted onto a key row" is the producer's lift, E's, and a plain object's first
   key read off its members is discarded, a plain object not lifting. The argMapping binding
   binds to occurrences and leaves the unpack to step 3; the fill of a jOOQ record reads its columns
   off path ends rather than `sql_table.record_class_fqn` to `sql_column`.
6. Whoever moves their readers: `graphitron_producer`, `graphitron_reader` and `graphitron_fetcher`
   re-key onto the occurrence.

**Settled.**

* The spelling is `TypeName.nestingField.nestedField` for output fields and
  `Type.field(arg.inputField:)` inside the parentheses for input fields, an argument spelled
  `(argument:)` as the specification has it; the occurrence path's `(arg)` and `/` steps retire with
  it, and the path model's holders take it (2026-11-03).
* A polymorphic parent is spelled by its participant, so an interface field reached from
  implementations on different tables is one coordinate per implementation, each with one table.
* Support does not widen. The generator keeps one class per nested type and its
  parent-compatibility rejection; what changes is that the store states the per-occurrence facts
  that rejection compares.
* Every occurrence has a path, and an empty one ends where it starts (2026-11-02).
* A path between two table-bound types is not empty, even on one table (2026-11-03).
* A `@service` argument has a path: the call's input is converted to its parameter types before
  the call (2026-11-03).
* The `@nodeId` unpack is stated at the path's end, not in the argMapping binding (2026-11-03).
* Every argument and input-field occurrence has a path; a scalar one matching no column is a defect
  only where no call parameter consumes it (2026-11-03).
* What an occurrence takes is a binding, keyed by `(graph_name, coordinate, bound)` with its kind an
  attribute; the column at a path's end is its `COLUMN` subtype, `graphitron_occurrence_column`, and
  a node id its `NODE_ID` subtype, `graphitron_occurrence_node_id` (2026-11-03).
* E's source-table nodes move to F's path writer. E keeps the key row and the first key; the source
  that reads them is F's (2026-11-03).
* A forked end is a need, in `graphitron_path_need`.

## Path model (draft)

The relations "Arguments and input fields are resolved as fields are, from a target model" builds,
agreed with Alf on 2026-10-31 and not yet turned into nodes; the section's nodes are rewritten from
this when it is picked up. It holds the rules the docs draft above states, and `scenarios/path-rules`
asserts it once `graphitron_path` stands where that document's placeholder does.

**Source, path, bind** (Alf, 2026-11-03). Three facts work together at every occurrence, the place a
field, argument or input field is reached, and none of them is about SQL as such: a source and a
bind are the same whether the values are rows or objects, and only a path has a kind for each
world. A *table source* is rows of a table and an *object source* an instance of a class a producer
hands back. A *join path* goes from table to table by joins, the SQL path `@reference(path:)` writes,
and an *accessor path* from object to object by accessors, the Java path field names and
`@field(name:)` write. A *lift* is where the two worlds cross, an object made rows of a table; it is
the producer's, stated by the producer that makes it and not by the source.
Today's writers build both sides, the accessor path being `graphitron_accessor_path`.

* *Source*: what its values come from, stated on the occurrence itself rather than as a pointer to
  another: rows of one or more tables, or an instance of a class. The writer derives it by rules no
  reader needs, the parent being the occurrence its `parent_coordinate` names: at a chain start the
  declaring type's table, a field declared on an interface being an occurrence per participant; a
  nested field or an argument takes its parent's tables, its parent's path targets or, where the
  parent has no path, the tables the parent binds, so a root field's arguments take what the root
  field binds. A write's payload is what the write produces: a payload field with a table-bound
  target is a producer of its own, lifted from the keys the write returned, and its source is its
  target's table; any other payload field has no source until the producer's result is stated. A
  write whose target binds no table, a scalar, an enum or a payload object, gives its arguments the
  write's table. An input field takes its argument's tables,
  at any depth. A source states only tables or a class. Where a value not of a table's shape becomes
  rows of one, the lift is stated by the producer, E's ("Producer, bind and call on the occurrence (draft)"),
  and the source states the table it lands on: a key row's table for a field with one, a
  `TableRecord` a producer hands back its record class's table, and a `@routine` call the derived
  table its function returns, a root field's and a mutation's included where the function has a
  table. A child of an occurrence bound to a `TableRecord` parameter will take that record's table
  as an ordinary table source; nothing writes it yet. A nested field whose parent field writes a
  path starts where that path lands, which only the join path knows, so it has no source until the
  join path stands. An object source is a value a producer hands back with a class but no record
  class and no key row, which is rows of no table, deriving its table being solvable but out of
  scope; the source reads its class off its producer's result, the parent field's own producer's
  result class, else the type's one class. A root field with no `@routine` has no source. A carrier
  graphitron builds has the class it generates; a connection's fields take their source from the
  returning producer's result ("A connection's producer returns the connection, and its fields read it");
  an errors field's subtree has no source yet. Where the parent forks, the occurrence has a table
  source row per table.
* *Path*: how it gets from its source to one type it reaches, one path per occurrence and target
  type, of the source's kind. A join path runs from a table source between two tables, concrete or
  derived, and an accessor path from an object source between two classes; either may be empty,
  ending where it starts, its target its start. One with no start or no end is invalid, so a root
  field with no source has no path. An argument or input field reaches its field's target types,
  and for each starts where its field lands for that type. A producer on a table source whose result
  has no table, a child `@service` handing back a value object, has an empty join path at its
  source, and its children have it as their object source.
* *Bind*: what it takes at the path's end: on the table side a scalar a column, an object or input
  object the table, an `id` on a `Node` or a `@nodeId` the node identity, the key columns there; on
  the object side a member's value at the accessor path's end; a value a call consumes binds
  nothing, the call's parameter fill naming it. A root field with no path binds its target's table,
  once per table its end would fork to. A parameter fill is decided before `COLUMN`: a scalar input
  with no binding under
  a parent bound `TABLE` is `COLUMN_UNRESOLVED` or `COLUMN_AMBIGUOUS`, and `ARGUMENT_WITHOUT_CONSUMER`
  only where the generator cannot consume it, under `@service` or `@externalField`, and no
  parameter does.

Where each starts and ends: a chain start
departs from its declaring type's table, or from the table a producer's lift lands on, as above; a nested field or an argument starts
where the occurrence it hangs under ends, and every input field under an argument where the
argument starts, a `@reference` on one moving none of its children. A join path between two
table-bound types is never empty, even on one table: with nothing written and no single foreign key
it is a defect. A join path is empty exactly where the target is not table-bound: a reader's ends at its start, a
producer's at its result's table. An end forks once per target type, a write's single data field
beside its errors field, a table-less target's participants, and a `@mutation(table:)`, which
declares its one table and takes the field's own target type, and each fork is a target of its own.
Every argument and input-field occurrence with a table source has a path, a `@service` one
included, its input converted to the parameter's type before the call; which consumer takes it
matters only to the defects a binding raises.

The base facts are already captured: `graphql_type`, `graphql_poly_member`, `graphitron_tabletype`,
`graphitron_field_key_row`, the `graphitron_ast_reference_*` entries and the
`graphitron_path_element_*` resolutions. `graphitron_field_target_table` is not one: it holds one
row for a single-table interface target, where the needs want one per member, and none for a target
whose binding names several tables.

**The relations, with their grains.** Declared in `graphitron-model.sql`, owned by `graphitron`;
the claim, the source and the need are written, the empty and inferred paths by `Paths`, and the
written and inherited paths with their `_claimed` rows, every output field's steps with their
subtypes and columns, the inferred paths' one step and the stops by `Steps`. The argument and
input-field paths and the accessor path are not written yet. The SQL carries the names
decided on 2026-11-03 (2026-11-04), and `graphitron_ast_path_entry` keeps its name as the written
entry.

* `graphitron_join_path_claim`, written by `PathClaims`: one place a path is written, by the declared
  element it is written on and the type it reaches. Keyed by (graph, element coordinate, target
  type), both spelled from names, so no column is ever NULL. The coordinate is the declared
  element's, not an occurrence's: a path is written once, where it is declared. The element and the
  type are referenced through `_ref` copies their removal sets null, and the sweep removes the
  claim; two claims on one key keep the first written, and two `@referenceFor` naming one type on
  one declaration are `REFERENCE_FOR_DUPLICATE_TYPE` at the second. `Customer.address` for a field's
  composed `@routine` and `@reference`, `Query.people(storeId:)` for an argument,
  `StockFilter.languageId` for an input field, and `Media.title` for a path on a multi-table
  interface's field, which reaches `Film.title` and `Book.title` as their `INHERITED` paths. The target
  type is the member a `@referenceFor` names, else the type the declaration names, a scalar or input
  type included: (`Store.people`, `Staff`) and (`Store.people`, `Customer`) are two claims on one
  field. It carries its `claim_kind` (`FIELD`, `REFERENCE_FOR`, `ARGUMENT`, `INPUT_FIELD`) and its
  own written position, an attribute within its file and no key.
* `graphitron_ast_path_entry`, capture: one element of one written path, at its place. Keyed by the
  written anchor's position and the place, both within one file, with the element's position and
  its `as:` name, NULL until R1099 adds `as:` to the directive. Captured by the path family's writer
  beside `graphitron_ast_field_path_entry`, which it generalises: that one covers output field
  declarations only and stays until its readers move.
* The holder is `graphitron_occurrence`: an occurrence of an element, one use of a declared field,
  argument or input field reached along a chain of parents, at its nested coordinate, a dot before a nested output field, `(x:)` naming an argument and an input
  field inside the parentheses (`Customer.address`, `Emne.navn.en`, `Query.films(filter:)`,
  `Query.films(filter.cast.firstName:)`). Every relation below keys on the coordinate with a
  foreign key to the occurrence; there is no holder relation of its own.
* `graphitron_occurrence_source`, anchoring: what one occurrence's values come from, one row per
  occurrence that has a source, keyed by (graph, coordinate) with a foreign key to the occurrence and
  its kind, `TABLE` or `OBJECT`, an attribute. How the writer found it is not stored. Its subtypes
  carry what they reference as nullable copies a vanished source sets null, the sweep removing the
  row:
  ** `graphitron_occurrence_source_table`: one table an occurrence's values are rows of, one row per
     occurrence and table, keyed by (graph, coordinate, table) with a cascading foreign key to the
     source; an argument under a forked parent has a row per table. A join path starts from one of
     these rows: a need's start is a foreign key to it.
  ** The lift is not the source's: it belongs to the producer, E's, which states why a table that is
     not the value's shape is a source, by a key row, a record class or a routine
     ("Producer, bind and call on the occurrence (draft)"). The source states only the table, a key
     row's wherever the field has one, since it fetches its own rows and an `ACCESSOR` key row can
     land on another table than the record's.
  ** `graphitron_occurrence_source_object`: the class an occurrence's values are an instance of, a
     value a producer hands back with no table, E's `OBJECT` result and its class in
     `graphitron_result_class`, one class per occurrence since a coordinate names one producer. It
     starts an accessor path, and a lift is the producer's crossing from it to a table.
  E keeps the key row and the first key, and the lift is the producer's, E's; the source is F's. It
  reads the record class and the key row off E's result facts rather than restating them, the key
  row through the occurrence's element until `graphitron_field_key_row` keys on the occurrence, a
  join that goes when it does. An input field under an argument bound to a `TableRecord` parameter
  takes an ordinary table source off the record table its call parameter fill names. It is the successor of
  `graphitron_field_path_start`, which starts only fields that wrote a path, and of
  `graphitron_field_source_table`. The producers are written for the whole graph by
  `occurrence-producers`, ahead of the source's loop, since nothing they read is written in it.
* `graphitron_path_need`, anchoring, written by `PathNeeds` a depth at a time: one type an
  occurrence with a table source must reach. Keyed by (graph, coordinate, target type), as the claim
  is keyed by its element and target type, with a cascading foreign key to the occurrence. Its start
  is a reference to one of the occurrence's table source rows (coordinate, start table), and its
  landing table an attribute, each through nullable `_ref` copies a removal sets null, as is the
  target type's reference to `graphitron_type`. Only an occurrence with a table source has needs; one
  waiting on a join path has no source and none. A field reaches its target where it has a table of
  its own and is no interface; each member with a table of a single-table interface (`FilmItem`,
  `ShortItem`, never the interface), a union or a multi-table interface; and otherwise its own type, a
  scalar, an enum, a plain object or a carrier, landing on its start, or on no table yet where its
  declaration writes a path. A write's payload fields follow the same rules; the write itself has no
  source and no needs. An argument copies its field's needs, starting where the field lands for each
  type; under a field with no needs, a root field with no path, it expands its field's target the
  same way, starting on each type's table, an interface's own table for its members where it has
  one, or on its own one source table, the write's, where the type has none, which is what its
  source rows already state. A connection's pagination arguments' needs stand until the
  connection's binding states what they take. An argument of a field that writes a path has no
  source, and so no need, until that path is resolved. An input field copies its parent's needs
  unchanged. A path follows the claim matching its need: an argument's or input field's need
  the `@referenceFor` claim naming its target type, else the element's one claim. The object side,
  the start class and the landing class, waits on value objects being bound.
* `graphitron_path`, anchoring: how one need is met, in either world. A supertype keyed by the
  need's key, so one-to-one with it, with a cascading foreign key to the need, its kind (`JOIN` from
  a table source, `ACCESSOR` from an object source) and its origin (`WRITTEN`, `INHERITED`,
  `INFERRED`, `EMPTY`); `EMPTY` is its own origin so the defects cannot read an empty path as a
  failed inference. The kind is copied into each subtype's key, a UNIQUE on the key and the kind
  being what a subtype's foreign key names, as `graphitron_occurrence_source` does. A need with no
  path is the defect. `Paths` writes the paths of the needs no claim covers, a claim at the need's
  own element or one inherited from an interface's same-named field: `EMPTY` where the need starts
  on its landing, an argument or input field, a target bound to no table, a write payload's data
  field, a field read off a handed-back record, or a child whose own `@service` hands back its
  target's records, which reads the table its producer's result names; `INFERRED` for an output
  field between two different tables exactly one route joins, its target its landing: one foreign
  key counted in both directions, or from a routine's derived table, which has no foreign key, the
  landing's primary key with every column matched by name among the routine's result columns. An argument or input field is never inferred, nor is a self-referencing key. A
  covered need's path is the claim's, written once its steps reach the target, so it has no row
  until then; a written path's target comes from its steps. From a routine's derived table a
  missing primary-key column is `NO_ROUTE_TO_TARGET` naming the first missing, and nothing is
  ambiguous.
  ** `graphitron_join_path`: the path is joins, with its target table, never null, its start where
     it is empty, referenced through `_ref` copies. `graphitron_join_path_claimed`, keyed by the
     path, names the claim a `WRITTEN` or `INHERITED` one follows, (element coordinate, target
     type), as a nullable copy a swept claim sets null, the origin being the supertype's and so out
     of a check's reach; an
     inherited one is a row per implementation, its claim possibly in another file, so no reader joins
     through implementations. An `INFERRED` one is claimed by nothing: its one step's key is inferred.
  ** `graphitron_accessor_path`: the object side's path.
* `graphitron_join_path_step`: one step a join path takes, keyed by the need's key and the
  position with a cascading foreign key to the need, with its from and
  to table, the place of the element it resolved in its claim's written list (none where inferred,
  and none for the step a `@routine` takes to its `@table`), and the element's `as:` name. Steps
  hang off the need rather than the path: a met need has its path row and its steps, a stopped need
  its steps and its stop row.
* What a step joins by, one subtype per way, each keyed by the step: `graphitron_join_path_step_key`,
  a key the claim names; `_inferred_key`, the one foreign key between the step's two tables, where
  an element names only a table or nothing is written; `_primary_key`, a `{key:}` naming its table's
  own primary key, joining a row to itself, the successor of `graphitron_field_path_primary_key`;
  `_method`, the one condition overload whose two table parameters take the step's from and to
  table; `_routine` and its `_parameter` rows; `_matched_key`, the landing's primary key a step from
  a routine's derived table rode, its columns matched by name. The joined column pairs are
  `graphitron_join_path_step_column` under whichever applies. Inference is a step's, never a path's.
  Every reference the step family makes to the catalog or the classpath is a nullable `_ref` copy
  set null on removal and swept by `touched_at`; only the step's foreign key to its need and each
  child's to its step cascade.
* `graphitron_join_path_stop`: one need whose path stopped short, keyed by the need's key with a
  cascading foreign key to it, with the position
  of the first step not taken, the element's place and the moves it met there. Landing stays a view
  over steps and stops.
* `graphitron_occurrence_binding`, anchoring, G's: one place an occurrence binds. Keyed by (graph,
  coordinate, bound), the place spelled from names: the schema-qualified table for `TABLE`, `COLUMN`
  and `NODE_ID`, one row per forked end; the class and the member read for
  `VALUE`, `no.sikt.fs.EmneNavn#getEn`, the value at an accessor path's end. The kind is an
  attribute, and a subtype per kind carries what the kind needs, keyed by the supertype's key with a
  cascading foreign key to it, as the defect subtypes are, an ordinal where the kind needs one.
  `graphitron_occurrence_column`, G's, is the `COLUMN` subtype and `graphitron_occurrence_node_id`
  the `NODE_ID` one; `graphitron_occurrence_binding_table`, its `sql_table`,
  is written beside the needs, since it reads the same ends; and `graphitron_occurrence_binding_value`,
  the class and member whose value it takes, waits on a producer's value object being bound. Every kind has its
  subtype, so the supertype holds the key and the kind alone (Alf, 2026-11-03).

**Defects**, as `graphitron_entry_defect` rows at the claim's written position: an inferred-key
step whose two tables share no foreign key or several (`NO_ROUTE_TO_TARGET`, `ROUTE_AMBIGUOUS`),
whether nothing was written or a written element names only a table, a need whose members fail
alike giving one row that names them. Where nothing is written, covered by no claim of its own or
inherited, an output field between two table-bound types draws them at its declaration, one row per
field naming the two tables and, for `ROUTE_AMBIGUOUS`, the keys; a field on its parent's own table
takes the table's one self-referencing key, and with none is `NO_ROUTE_TO_TARGET`, narrowed by an
argument or not, its detail naming the primary-key self-join or a self-referencing key as the path
to write, and with several `ROUTE_AMBIGUOUS`. An implementation's field writing nothing
whose type implements two interfaces, neither extending the other, that both claim the field for
one member is `INHERITED_PATH_AMBIGUOUS` at its declaration, naming the two. A written step that resolves to no move or several, which is
the same pair; no overload for a step's pair
(`CONDITION_NO_OVERLOAD_FOR_PAIR`); two overloads taking one pair (`CONDITION_OVERLOAD_TIE`);
a claim on an output field whose target has no one table (`REFERENCE_WITHOUT_TARGET_TABLE`, at
the claim, which absorbed `CHAIN_WITHOUT_TARGET`; a scalar or enum target, a `@pivot` and a path
ending on a `@routine` are not one); two
`@referenceFor` naming one type on one field (`REFERENCE_FOR_DUPLICATE_TYPE`); a scalar path whose
last element names no table (`PATH_LANDING_UNKNOWN`); a `@nodeId` path landing on a table
(`NODE_ID_LANDS_ON_TABLE`); a `$path` name no segment carries, and two segments of one path under
one name (R1099's). Two calls consuming one argument with different record tables belong with the
producer's lift ("Producer, bind and call on the occurrence (draft)"). A binding's own defects are the binding's: a scalar
input with no binding under a parent bound `TABLE` is `COLUMN_UNRESOLVED` or `COLUMN_AMBIGUOUS`, and
`ARGUMENT_WITHOUT_CONSUMER` only where the generator cannot consume it, under `@service` or
`@externalField`, and no parameter does; `PATH_NOT_TAKEN` waits on the consumer.

**What it replaces.**

| Today | Becomes |
| --- | --- |
| `graphitron_field_path_start` and its four kind subtypes | `graphitron_occurrence_source` and its subtypes |
| `graphitron_field_source_table` | the occurrence's source |
| `graphitron_field_target_column` | the `COLUMN` binding, `graphitron_occurrence_column` |
| `graphitron_field_path` and its children | `graphitron_join_path_step` and its children |
| `intent_input_occurrence_path` and its steps, and `graphitron_path_holder` | the occurrence |
| the argument and input-field reference step targets, keyed and keyless | `graphitron_path` |
| `graphitron_field_scope_table`, `graphitron_argument_scope_table`, `graphitron_input_field_resolving_table` | the end of the occurrence's path, forks included |

The element resolutions `graphitron_path_element_*` stay as they are.

**Decided.** A written position, a file's line and column, keys a fact only within that file; a
reference that can cross files is anchored to names or to an element's place in its list, which an
author chose and which do not move when the file around them is edited. So a holder is keyed by its
coordinate spelled from names, a path names the written claim it follows and a step the element's
place, and a path's start is a step's `as:` name, so an inserted step moves nothing. An application
itself is named by its coordinate, directive and ordinal
("An application is named by its coordinate, directive and ordinal"). Inheritance is a row per implementation, its written claim
possibly in another file. The landing type is a key column of a path. A path holder is a coordinate,
and a claim is a coordinate and a target type (2026-11-02). It is the field that is lifted onto a
key row, not its parent, so a lifted start is read off the field's own key row. Every occurrence
binds, and has a path wherever it has a source; a path's target is never null, and a path with no
start or no end is invalid; a root field has a path only through a `@routine` (2026-11-03). The
source is a supertype, a table source and an object source its subtypes; the lift is the
producer's, E's, not a subtype of the source; the binding has a subtype per kind (2026-11-03).
An occurrence has one source, keyed by its coordinate, a fork adding targets and never sources; a
need is keyed by (coordinate, target type), as a claim by (element, target type); a binding by
(coordinate, bound), a value's bound its class and member (2026-11-03). Source and bind are not
SQL's: a path is a join path from a table source, written by `@reference(path:)`, or an accessor
path from an object source, and a lift is the crossing from an object to a table; inference is a
join step's, never a path's (2026-11-03). A step's inferred key is judged once, wherever it is
inferred: no shared foreign key is `NO_ROUTE_TO_TARGET` and several `ROUTE_AMBIGUOUS`, so
`PATH_NO_FOREIGN_KEY` and `PATH_SEVERAL_FOREIGN_KEYS` are never introduced (2026-11-03). A source states what an occurrence's values come from, its
tables or its class, never a pointer to the occurrence it was derived from; a table source is a row
per occurrence and table (2026-11-03). The write payload is what the write produces, a table-bound
payload field a producer lifted from the returned keys (2026-11-03). None holds a table in its key: each
references its tables through nullable copies a vanished table sets null and the sweep removes,
so each follows the anchor rule with no exception. Each relation here is written as a plain
`INSERT .. SELECT` over the occurrence and what the occurrence above it states, the instant bound,
never as a view over views, and its writer gets a sis timing when it is built. The need is general,
keyed by target type with its landing an attribute, and keeps its name; only an occurrence with a
table source has needs; an argument copies its field's needs, starting where the field lands, and
under a root field with no path expands the field's target from its own source rows, an interface
with its own table starting there for each member; an argument of a field that writes a path has
no source, and so no need, until the path is resolved, as a nested field's; and an input field
copies its parent's; every reference a need, a path or a step makes to what it was built from
follows the anchor rule; an argument's or input field's need follows the `@referenceFor` claim
naming its target type, else its element's one claim; `CHAIN_WITHOUT_TARGET` is merged into
`REFERENCE_WITHOUT_TARGET_TABLE`, raised at the claim; a `@mutation(table:)` is accepted where
`graphitron_producer_write` has its row, a disagreement being E's defect to raise (Alf, 2026-11-04).
The claim, the author's statement, and the claim answering a need, which is the path itself, a
`WRITTEN` or `INHERITED` row with its `_claimed` row once the path is walked and reaches the target,
are two facts, and no relation stands between a need and its claim; an argument or input field is
`EMPTY` unless something is written for it, and never inferred; a field on its parent's own table is
`NO_ROUTE_TO_TARGET`, narrowed by an argument or not; an implementation's own claim beats an
inherited one, an extending interface's redeclared claim overrides the one it extends,
`@referenceFor` is inherited member by member, and two unrelated interfaces both claiming the field
are `INHERITED_PATH_AMBIGUOUS`; a routine's derived table has no foreign key and reaches its landing
by the landing's primary key, its columns matched by name, inferred where every one is there and
`NO_ROUTE_TO_TARGET` where one is missing; a child that is its own `@service` handing back table
records starts where its rows come from, the table its producer's result names, so its path is
`EMPTY` (Alf, 2026-11-05).

**Decided** (Alf, 2026-11-05). The walk is a flat per-position rule view over base tables, with its
parameters bound, looped in Java. A reader takes the join path's target rather than writing it back
into the need. A root field's leading `@routine` is the producer's call, so its first step follows
its place. A step from a routine's derived table to a table, joined by that table's primary-key
columns matched by name, is its own subtype, `graphitron_join_path_step_matched_key`, covering an
inferred routine result, a written `{table:}` element from a function and the step after a path
ending on a `@routine`; `_primary_key` stays the `{key:}` self-join. `NODE_ID_LANDS_ON_TABLE` is a
bare `@nodeId` whose walked landing table has no single `@node` type, or a named node whose table
differs from the landing. The old walk ran beside the new one until its readers read the join path.
The claim a need follows is picked by `graphitron_join_path_claimed_rule`, one row per output-field
need a claim covers and walks to: the field's own `@referenceFor` naming the type, then its own
claim where it walks to the type, then exactly one inherited, and it is the rule for
`graphitron_join_path_claimed`, written for the needs whose walk reaches the target. The walk's
defects, `ROUTE_AMBIGUOUS`, `NO_ROUTE_FROM_DEPARTURE`, `ELEMENT_UNRESOLVED`,
`ROUTINE_PARAMETER_UNBOUND`, `NO_ROUTE_TO_TARGET` for a walk ending off its target and the two
condition-overload codes, moved to the new walk when it landed, raised at the written element, an
inherited claim's being the interface's. A stopped need keeps the steps
it took. A condition alone whose second parameter takes any table (`Table<?>`, `TableLike<?>`) says
nowhere to arrive and is not supported: it stays `NO_ROUTE_FROM_DEPARTURE`, its detail naming the
remedy, and sakila's fixtures type both parameters (Alf, 2026-11-05).
A write whose target binds no table, `ID` or `String` from a `DELETE` among them, gives its
arguments the table `@mutation(table:)` names, so they and their input fields have a need starting
and landing there and an `EMPTY` path.

**Decided** (Alf, 2026-11-06). `@nodeId(typeName:)` is a written intention, and an argument's or
input field's path to the node's table is inferred over the one foreign key; none or several is
`NODE_ID_LANDS_ON_TABLE`, and the author chooses with `@reference`. A bare `@nodeId` takes the type
whose table the field's path lands on. An argument's or input field's own written path starts where
its field lands for each type, `as:` waiting; it follows its own `@referenceFor` naming the need's
type, else its own `@reference`, and nothing is inherited at an argument. Every input field under an
argument starts where the argument starts, a `@reference` on one moving none of its children.

**Decided** (Alf, 2026-11-07). A same-table field whose arguments narrow the read, with no path, is
never accepted: "This is crazy wrong and we should never allow this. It makes no sense and is just a
bug waiting to happen." It is the same defect as a field between two types bound to one table:
exactly one key infers, otherwise the author writes the key, or the primary key for the same row.
For output fields, exactly one self-referencing key infers, its direction taken from the field's
cardinality: a single-valued field follows the key forward, to the one row it points at; a list or
connection follows it in reverse, to the rows pointing here; none is `NO_ROUTE_TO_TARGET`, several
`ROUTE_AMBIGUOUS`. At arguments and input fields a self-referencing key is never inferred ("I don't
think the logic works on arguments").

**Decided** (Alf, 2026-11-07). "The @nodeId(typeName:) follows the exact same inference rules as
field: TypeName." An argument's or input field's path to the node's table rides the one foreign key
in either direction, as an output field's does; none or several is `NODE_ID_LANDS_ON_TABLE`. Over a
key the node's table declares, the node's key lands on no column of the slot's table, so the decode
is a correlated `EXISTS` and `NODE_ID_LANDING_TYPE_DISAGREES` has no pair to judge. The generator's
`@nodeId` filter is to read its route from the store rather than the walk, the walk counting both
directions meanwhile: "We're currently moving the
generator from the walk to the store, a potential solution is to move the @nodeId handling to the
store early."

**Open.** A lifted field's start (a `TableRecord` matching its table, or a key or routine type
matching the next step's primary key column by column) is not yet in `scenarios/path-rules`. How a
value object lifts to a derived table is out of scope. An emitter may collapse a join whose key already sits on the row, the
`@nodeId` key lift and the foreign-key mirror among them; that is its reading of the first step's
key, never a path of another kind. How the
generator reads a path per pair at run time is not yet planned; R1132's shared DataLoader is the same
missing source type there.

## Producer, bind and call on the occurrence (draft)

How the occurrence model decides what executes. Source, path and bind say where an occurrence's
values come from and what it takes; this section says which occurrences run something of their own,
and where a method call sits. Every occurrence binds; some also produce. It is a draft for Alf and the
sessions holding the nodes below; nothing in it is built. "Producer" is the word the store already
has, kept until a better one is found.

**Producer.** An occurrence that executes something and produces a result: a query, a write, a
`@service` call or a routine call. Two kinds, and only two:

* A *root producer*: an occurrence with no source, a field of a root type. It executes from nothing:
  a select, a lookup, a write, a `@service`, or a `@routine` with no table before it.
* A *child producer*: a child output field that starts a query of its own, batched per parent, from
  its parent's key row or a routine's table: a query, a `@service` called with the parents' keys, or a
  routine.

The producer knows what it produces (Alf): its *result*, one record, a list of records, a connection
page or a `@service`'s object, each with its class where it has one, is a fact of the producing
occurrence, and the occurrences under it bind into it; the type-keyed `graphitron_result` goes
("The result relations dissolve into the producer's result").

A *lift* is a producer's crossing into the world of tables, and belongs to the producer, never to
the occurrences under it: where a child producer starts, a parent's key row lifted onto the key's
table; or what any producer hands back, a `TableRecord` lifted onto its record class's table, a
routine's result onto its derived table. So a `@service` whose result is a `FilmRecord` is lifted onto
`film`, and its scalar children bind columns of their source, simply the `film` table, read off the
record with no query of their own. A source only ever states tables or a class.

**Bind, not reader.** Every output occurrence binds, and its binding says what it takes: a column at
its path's end (`COLUMN`), the table an inline object or list joins to (`TABLE`), a node identity
(`NODE_ID`), a member's value off its parent's object (`VALUE`), or a value an `@externalField`
method computes in its producer's query (`EXPRESSION`, bound by its method, one row per candidate
overload until the pin). An occurrence that binds and does
not produce executes nothing: its join path continues the query of the producer it belongs to, the
nearest producer up its chain of occurrences, or it reads a member of its parent's object. That needs
no relation of its own: `graphitron_reader`'s `reads` (`COLUMN`, `NESTED`, `EXPRESSION`, `ACCESSOR`)
is the binding's kind said less precisely, so it dissolves into the binding. A connection's `nodes`
and `edges.node` bind and read what the returning producer fetched.

**The join path is what lets a target join an existing query.** A child reached from a table source
by a join path is joined into the query its source already belongs to, and only binds. Where there is
nothing to join along, a parent that is a value rather than rows of a query, the child must start a
query of its own from the parent's key, and is a producer. So a child continues its parent either
by a join path, binding into the producer's query, or from a key row, producing.

**What makes a child a producer.** Measured off the generator's classification (`FieldBuilder`):

* A `@table` child under a record- or class-backed parent: it starts from the parent's key row
  (always split today, `@splitQuery` redundant there).
* A payload's data field under a write, `@service` or `@routine` carrier: it re-fetches the produced
  record by key.
* A child `@service`: called with its parents' keys.
* `@splitQuery`: the author starts from the parent's key a child that could have been joined; this
  is how `@splitQuery` changes the source and makes a producer of a field that would only bind. `@tenantFanOut` does the
  same and is forced in effect, a fanned child running once per tenant.
* Shapes that start a producer although a join path could in theory continue the query, because the generator
  builds no inline form for them: a list or connection of a single-table discriminated interface, a
  list or connection of a multi-table interface or union with a table-bound participant, and a
  connection on a plain `@table` child, which today the author must mark `@splitQuery`. These are
  stated as forced, so each can stop producing when the generator learns to join it.
* A connection's `totalCount` is a producer of its own, a count sharing the returning producer's
  source and argument values, run only when queried. The connection's other fields are sourced from
  its carrier object, whose class graphitron generates, and `nodes` and `edges.node` read what the
  returning producer fetched (Alf, with the connection model).

**Input occurrences never produce.** An argument or input field is not a fetcher. It is bound, at its
path's end, to a column, a table, a node identity or the parameter of a call that consumes it; an
input field under an argument bound to a `TableRecord` parameter starts on that record's table
because a child starts where its parent ends, not because anything lifts.

**Calls.** A method or routine graphitron invokes, held by the occurrence or the join path step it
is written at, never by a written position. Two kinds:

* *Executing calls*: the `@service` of a root or lifted producer, the `@routine` of a root producer
  (one with no table before it), and a `@routine` that is a join path step (one with a table before
  it, after a `@reference` or applied to the parent's row). A `@routine` is the one or the other,
  never both.
* *Calls that are part of a query*: an `@externalField` expression, held by its occurrence's
  `EXPRESSION` binding, an occurrence's `@condition`, a predicate over the rows at its path's end, and
  a join path step's condition. They go into the query
  of the producer the occurrence belongs to, through its join path. Under a producer that composes
  no query over those rows, a `@service` handing back a value object or an `INSERT`, they decide
  nothing, which is a defect at them rather than a silent drop.

A call states its method, the one overload every parameter of which is filled among the
`code_method` rows its class and name find; for an instance `@service`, its holder; and per
parameter, what fills it: an input occurrence's value, the fill naming the occurrence it lands on,
a table at the call's path end or a step's from and to table, the source row's columns
or keys, `$context`, `$session`, `$tenant` or the jOOQ context. Each holder owns its parameter rows,
keyed by the holder, the method and the position.

**A `@condition`'s fills** (Alf, 2026-11-09). The rules are the manual's, maintained in
`reference/directives/condition.adoc` § How the method's parameters are bound, which the flip
reproduces. Where its by-type rule differs from the service's, to be closed by
"A `@condition` and a service pair a parameter with an argument by one rule": the service pairs on list-ness and element
type with each other's only candidate and, failing that, the last open parameter and argument where
type cannot tell them apart, and reads an input object as any non-JDK class; the condition, in three
steps the manual now states (Alf, 2026-11-10), pairs one remaining parameter with one remaining
element only where the element is an input object or an enum and the parameter no scalar's class,
pairs a Java type once on each side by its GraphQL scalar's mapping unless a nested input field has
that type too, then binds a parameter one level into an input object by name and type, skips a
parameter named for a context key, and never looks inside a list.

**Stated so far.** `graphitron_occurrence_producer`, one row per producing occurrence with its kind
and `ROOT` or `CHILD` start, and its subtypes `_key_row` (a child's start off its parent's object),
`_service` (the method and the constructor), `_routine` and `_write`, written for the whole graph
by `occurrence-producers` ahead of the source's loop and read today off the field-keyed producer relations through the
occurrence's field: on the six stores every field producer but a connection's count and facets is a
producer at each of its occurrences and nothing else is. `_result` states what each produces, its
shape (`SINGLE_RECORD`, `RECORD_LIST`, `CONNECTION`, `OBJECT`, `SCALAR`), its class and, for records,
the table they are rows of. A connection's `COUNT` and `FACETS` producers are stated on this
family by the connection model (Alf, 2026-11-04).

**What this replaces.** `graphitron_code_reference`, keyed by a reference's written position,
dissolves into the calls' methods; `graphitron_producer_service` and `graphitron_producer_routine`
are the executing calls of root and child producers. `graphitron_producer`, `graphitron_reader` and
`graphitron_fetcher` go to the occurrence: the producer, its lift included, one row per root or
child producer; the reader into the binding; the fetcher, a producer or not, derived. The source
states only tables or a class. `graphitron_argmapping_landing` becomes the argMapping arm
of parameter matching, keyed by the call and the parameter, and `graphitron_call_context_key` the
call's `$context` fills.

**Open.**

* Owners: the producer with its lift E's, the producer written ahead of the source's loop and the
  lift after it; the source F's; the calls and their parameters E's; the
  connection's `totalCount` unowned, with the connection model.
* Whether a `@condition` under `UPDATE` and `DELETE` applies to the rows they match, and whether the
  defect for a condition that decides nothing exists already or is new.
* Settled (Alf, 2026-11-09): the call holders share a supertype, `graphitron_occurrence_call`, for
  the parameter rows' one foreign key, and a `@condition` is one call per table its occurrence binds,
  pinning an overload for each, as the join path step does per implementation.

## Tests

The obligation this section carried is discharged and not by being measured. It asked the Done gate
to confirm that `MaterializeDependencies.populate` reaches zero rather than assuming it. That class no
longer exists, so the step it timed cannot run at all.

What the gate should still refuse is the inference the obligation was guarding against: a cheaper boot
does not follow from a dissolved register. The DDL half of the boot goes up under this item's own
remedy, which replaces registrations with stored keys and indexes. The figures for both halves are in
`roadmap/audits/2026-09-22-capture-dissolution-measurements.md`.

## For the changelog at Done

The entry is written at Done, the board and the changelog holding an id one place at a time. These
are what it owes a consumer reading it, each with the subject of the commit it landed in, a hash
not surviving the harvest, so they are recorded here as they land rather than found afterwards.

* **Breaking: a same-table `@table` field narrowed by an argument needs its path** ("inference is
  written into the store, and a need nothing covers is a defect"). A field returning a `@table` type
  on its parent's own table, narrowed by an argument and with no `@reference`, was read uncorrelated,
  the same rows under every parent; it is now `NO_ROUTE_TO_TARGET` until it writes its path, and the
  generator refuses it too, whatever its arguments filter. The example schema's
  `Film.sameTableById`, which exercised the uncorrelated read, is removed.

* **New: a same-table field infers its table's one self-referencing key** ("a same-table output
  field rides its table's one self-referencing key by cardinality, and without one is refused
  however narrowed"). An output field on its parent's
  own table with nothing written follows the table's one self-referencing key, forward from a
  single-valued field and in reverse from a list or connection, as `@reference(path: [{key: ...}])`
  naming it would; several such keys are `ROUTE_AMBIGUOUS`. Arguments and input fields never infer one.

* **Breaking: jOOQ 3.20 is the minimum** ("generation refuses a jOOQ below 3.20"). The generated
  `NodeIdEncoder` calls `Record.touched(Field)`, which jOOQ added in 3.20, so a build on 3.19 failed
  in javac, in a file the consumer did not write. `graphitron:generate`, `graphitron:dev` and
  `graphitron:validate` now refuse a jOOQ below 3.20 before writing anything, naming the coordinate and version found, the
  minimum, and what to change: the jOOQ version in the pom and a catalog regenerated with it.
  FS-plattform's kreg, sis, kompetanse, batch and kjerneapi-codegen are on 3.19.18 and must upgrade.

* **Breaking: `@table` cannot name a table-valued function** ("a type is not bound to a function"). A schema binding a type to
  a function's result with `@table` stops building, with a message saying to remove the directive.
  The field's `@routine` gives the type its rows, the routine's result, which binds no table.
* **Breaking: `@routine` loses `columnMapping`** ("breaking: @routine loses columnMapping, unused in
  FS-plattform; argMapping names an argument or a column"; "breaking: a bare argMapping name is an
  argument, $source.<column> binds a column"). A schema writing `columnMapping` stops building.
  An `argMapping` entry binds a routine parameter to an argument, or with `$source.<column>` to a
  column of the row the routine is applied to: the enclosing type's row, or the table the preceding
  `@reference` reached. A bare name never binds a column. An entry that binds nothing draws
  `ROUTINE_PARAMETER_UNBOUND`.
* **Removed: the MCP schema tool's `demand` slot** ("the demand relations go"). A tool reading it gets no slot; it
  restated directives the same entry shows.
* **Changed: `graphitron:capture` never fails over a schema that parses** ("capture captures, and
  nothing else"; "a document that does not parse ends the round at the parse"). A schema that will
  not assemble or classify is still captured, and what refused it is in the store as
  `graphql_schema_problem` rows rather than in the build's exit code. A document that will not
  parse fails the goal, as it does `graphitron:generate` and `graphitron:validate`: the parse
  problems are recorded, the store keeps its last clean reading, and in `graphitron:dev` the round
  ends at the parse, every changed file read again once it parses.
* **Changed: a reactor sibling is read from its own output** ("a workspace module is read from its
  own output, in place"). In a multi-module project every graphitron goal, `graphitron:generate`
  and `graphitron:dev` among them, reads a sibling module from its `target/classes`, else its jar in
  `target`, in the place Maven put the module on the classpath, and falls back to the installed copy
  only where the sibling has no output. A class added to a sibling since its last install is no
  longer refused as being in a module the project does not declare.
* **Changed: `graphitron:dev` compiles its own module before it starts** ("the dev goal compiles its
  module first"). It runs the module's build through `process-classes`, its first round taking the
  place of the module's `graphitron:generate`, and reports a goal that fails as a warning rather
  than stopping. `-Dgraphitron.dev.compileModule=false` turns it off; sibling modules are never
  compiled.
* **Added: `graphitron:dev` says a workspace module it read stale** ("the dev goal says a module it
  read stale"). A module whose sources changed since it compiled, or one read from its installed
  copy for want of output, is named once with the command that compiles it: in the console, in MCP
  `status` and diagnostics, and as an editor message.
* **Added: each capture's time per stage is recorded and reported** ("every stage's time is
  recorded in the store"). `store_graph_write` holds, per capture, the statements, rows and
  milliseconds each gatherer spent on each table, and `store_graph_read` the same for each relation
  it read for a millisecond or more. MCP `status` names the latest capture's five slowest
  statements, writes and reads, as `slowestStatements`, across the store with `allGraphs`, and a
  `graphitron:dev` round that captured prints its three slowest.
* **Faster: the emitted schema's tag narrowing** ("the tag narrowing joins the coinage to its
  minted type before the fields"). The read took 9 to 12.5 s on a large schema and now takes
  under 50 ms.
* **Contributors: a rule's plan is gated** ("a rule's plan probes an index wherever it looks a row
  up"). `RulePlanGateTest` fails a derivation rule whose plan reads a whole partition per row, over
  a scaled fixture whose plans match a large consumer schema's. Its list of known exemptions only
  shrinks.
* **Changed: the fact store runs on H2 2.5.250** ("the fact store runs on H2 2.5.252, which fixed
  its compaction race", then "H2 is held at 2.5.250"). The plugin and the model bring H2 2.5.250 in
  place of 2.4.240. A store an earlier build wrote still opens, the file format being unchanged, and
  compacting it on close is no longer refused intermittently in a JVM running with assertions on.
  2.5.252 was taken first and dropped: its planner made jOOQ's foreign-key read of the fact schema
  take 168 s instead of 2 s (h2database#4523).
* **Breaking: `@sourceRow` is removed** ("breaking: @sourceRow is removed, unused in FS-plattform").
  A schema writing it stops building, the directive being unknown. A class-backed parent gives a
  child its key through a typed accessor returning the child table's record, a record with only its
  primary key set where the parent carries only the key's value; a child `@service` keys off the
  parent being that record or having exactly one accessor returning it. The directive comes back
  once the model is cleaned up.
* **Breaking: a context argument no longer fills a service class's constructor** ("breaking: a
  service class's constructor takes only the jOOQ context, unused in FS-plattform"). An instance
  `@service` method's object is made with a public constructor taking nothing or one `DSLContext`,
  the widest; one whose only public constructor also takes a `contextArguments` value stops
  building, and the value goes on the method instead. FS-plattform: every one of the 37
  `contextArguments` uses across opptak, kompetanse, kreg and sis fills a method parameter, and
  every service class's constructor takes only `DSLContext`.

* **Breaking: an input object a call cannot build is a defect at the field** ("an input object that
  cannot be built is a defect at the field"). A `@service` or `@condition` input object built into a
  class is refused where nothing constructs the class (`BUILT_CLASS_UNBUILDABLE`), a field fills no
  member (`MEMBER_UNKNOWN`, where a setter bean dropped the value in silence), a list of input objects
  cannot group (`GROUPING_LIST`), or two fields fill one member (`MEMBER_FILLED_TWICE`); a
  `@condition` parameter taking a jOOQ record is `CONDITION_PARAMETER_RECORD`. On a jOOQ record a
  field naming no column of the record's table is `COLUMN_UNRESOLVED` or `COLUMN_AMBIGUOUS`, and a
  `@nodeId` field with no route onto that table is `NODE_ID_LANDS_ON_TABLE` under a `@service` too,
  as in utdanningsregisteret's `RegistrerCampusForUtdanningsmulighetInput.organisasjonsId`, which
  writes a `@reference` path through `campus` to build.
* **Breaking: a whole node id never lands on a parameter as a single value** ("a whole node id lands
  only on a record or a tuple"). A `@nodeId` argument a `@service` parameter takes whole, by name or
  through an `argMapping` entry naming the argument, lands only on the node type's generated record, a
  record supertype for an id of several node types, or a `RecordN` or `RowN` of the key's columns'
  types. A parameter of the one key column's type, which used to receive that column's value, is
  `NODE_ID_KEY_ARITY_EXCEEDS_SLOT`; ask for the column with an `argMapping` entry projecting it, as
  `id: key.film_id`. Unused in FS-plattform: its two `@service` fields taking a `@nodeId` argument take
  the node's record.

## Retired vocabulary

For the retirement sweep at the Done gate. Determined by diffing the schema's relation names across
the item's life. Already swept once, with seven survivors found and fixed.

**Relations nobody read** (2026-11-12). `intent_poly_member`; `store_graph_lint_disabled_rule`;
`federation_version_directive_location` and its grain `federation-version-directive-location`;
`graphitron_ast_service_entry`, `graphitron_ast_external_field_entry`, `graphitron_ast_pivot_entry`,
`graphitron_ast_default_order_entry`, `graphitron_ast_default_order_field_entry` and
`graphitron_ast_error_database_handler_entry`; `graphitron_entry_defect_method` with its grain
`entry-defect-method` and its writer `ConditionShapeDefectMethods`, and
`graphitron_entry_defect_parameter` with its writer `EntryDefectParameters`, the rule view
`graphitron_entry_defect_parameter_rule` staying; `graphitron_field_scope_table`,
`graphitron_argument_scope_table` and `graphitron_input_field_resolving_table`, each with its `_rule`
view, its grain (`field-scope-table`, `argument-scope-table`, `input-field-resolving-table`) and its
`DerivationStratum` step and writer (`FieldScopeTables`, `ArgumentScopeTables`,
`InputFieldResolvingTables`), and `intent_field_navigated_type`. With the steps that read one another gone, the derivation
gatherer's analysing cadence went too: `DerivationStratum.analysingCadenceApplies`,
`DerivationStratum.runAnalysing`, `StageProgress.Event.PassStarted.analysing` and its
`, committing and analysing each` line, and `StagePrerequisiteStatisticsTest`, which held it. The fact documents
`undecided/field-scope-table`, `undecided/field-participant-scope` and
`defects/input-field-resolving-table-consuming-field` went with what they claimed;
`scenarios/default-order` stays, the manual's `@defaultOrder` and `@asConnection` examples rendering
from it.

**The field-keyed key row dissolved into the fill** (2026-11-11). `graphitron_field_key_row`,
`graphitron_field_key_row_accessor`, `graphitron_field_key_row_rule`, the `field-key-rows` stage and
its writer `FieldKeyRows`, and `graphitron_occurrence_producer_key_row` with its rule are
`graphitron_occurrence_producer_lift`, `_lift_accessor` and `_lift_candidate`, keyed per occurrence;
the key row's `kind` is the fill's `read`, its `table_*` columns the fill's `key_*`. The pin test
`FieldKeyRowPinTest` is `ProducerFillPinTest`. The fact document `scenarios/field-key-row` keeps its
name.

**Path relations renamed or dropped before they had readers.** `graphitron_path_anchor` is
`graphitron_path_claim`, its writer `PathAnchors` is `PathClaims`, and `anchor_kind`,
`anchor_coordinate` and `anchor_target_type` are `claim_kind`, `claim_coordinate` and
`claim_target_type`; "anchor" stays the anchoring phase and the anchor relations the anchor rule
speaks of, and `graphitron_ast_path_entry`'s `anchor_line` and `anchor_column`. `graphitron_path_holder`
went into `graphitron_occurrence`, a holder being just a coordinate. The anchor's spelling
`Store.people[Staff]` and the argument spelling `Query.people.storeId` are retired with them.

**The walk's catalog decodes went with the store's encoder** (2026-11-11).
`GraphitronSchema.catalogNodeDecodes` and `BuildContext.catalogNodeDecodes()`, the walk's registry of
the decode helpers it minted for `@nodeId` targets that are no node type, are gone: the encoder emits
a helper for each `graphitron_node` row, a stub's included.

**Transitional columns.** `graphitron_occurrence_condition.override_written` copies the `override:` a
`@condition` was written with off `graphql_directive_application_arg`; the consumption-rule node
removes the column.

**The claim conflict moved into the claim supertype.** `AuthoredClaimConflicts`, its
`FieldVerdict`, `FieldCoordinate` and `fieldConflicts`, `FieldClaim`, `AuthoredClaimRejectionRows`
and `intent_authored_claim_rejection` are gone, with the grain `authored-claim-rejection`, the
`producer-claim` and `binding-claim` grains and the defect code `PRODUCER_KINDS_CONFLICT`; the
claims are `graphitron_claim` and its catalogs, the conflict `CLAIMS_CONFLICT` and
`ROUTINE_LOOKUP_DEFERRED` with their detail, read by `ClaimConflicts`.

**Path relations renamed into the join path's names** (2026-11-04). `graphitron_path_claim` is
`graphitron_join_path_claim`; `graphitron_path_step` and its children `graphitron_path_step_column`,
`graphitron_path_step_key`, `graphitron_path_step_primary_key`, `graphitron_path_step_method`,
`graphitron_path_step_routine` and `graphitron_path_step_parameter` are `graphitron_join_path_step`
and `graphitron_join_path_step_*`; `graphitron_path_stop` is `graphitron_join_path_stop`. The need's
columns `landing_kind`, `landing`, `landing_type`, `start_coordinate` and `start_as` are dropped, a
need being keyed by its target type with its landing an attribute. The defect code
`CHAIN_WITHOUT_TARGET` is merged into `REFERENCE_WITHOUT_TARGET_TABLE`, reported at the claim.

**Per-site `@field` and `@nodeId` entries merged** (2026-11-05). `graphitron_ast_field_binding_entry`,
`graphitron_ast_input_value_binding_entry` and `graphitron_ast_enum_value_binding_entry` are
`graphitron_ast_binding_entry`; `graphitron_ast_field_node_id_entry` and
`graphitron_ast_input_value_node_id_entry` are `graphitron_ast_node_id_entry`, both written by
`GraphitronBindingEntries` off every site.

**Dropped, written and never read** (2026-11-05). `graphitron_field_participant_path` and its grain
`field-participant-path`, written by `GraphitronAnchor` and read only by a fact document: a
participant's own path is its `@referenceFor` claim, `graphitron_ast_path_entry`, walked into
`graphitron_join_path_step` like any other claim.

**Dropped, read by nothing but their writer** (2026-11-06). The field-site reference walk,
`graphitron_field_reference_step_target` with `graphitron_field_reference_step_target_keyed`,
`graphitron_field_reference_step_target_keyless`, their grains `reference-step-target`,
`reference-step-keyed-target` and `reference-step-keyless-target`, and its stage
`FieldReferenceStepTargets`: a field's walk is `graphitron_join_path_step`. The old walk's start
subtypes `graphitron_field_path_start_result`, `graphitron_field_path_start_binding` and
`graphitron_field_path_start_lifted`, their kind staying on `graphitron_field_path_start`.

**Input occurrence paths dissolved into the occurrence** (2026-11-06). `InputOccurrencePaths`,
`intent_input_occurrence_path` and `intent_input_occurrence_path_step`, with
`InputOccurrenceShadowTest`: an input field's use site is its `graphitron_occurrence` coordinate,
spelled `Query.films(where.someId:)`, its declared argument the occurrence's `argument_coordinate`
and its descent the parent chain.

**The departing column dropped** (2026-11-06). `graphitron_field_target_column`,
`graphitron_field_target_column_match`, `graphitron_field_target_column_rule`, the grain
`field-departing-column` and the writer `FieldTargetColumns`: a field's column is
`graphitron_occurrence_column`, at the table its occurrence's path lands on.

**Relations retired.** Eight per-site argMapping tables into `graphitron_arg_mapping_pair`:
`graphitron_argument_condition_arg_mapping_pair`,
`graphitron_argument_reference_for_step_arg_mapping_pair`,
`graphitron_argument_reference_step_arg_mapping_pair`, `graphitron_field_condition_arg_mapping_pair`,
`graphitron_field_reference_step_arg_mapping_pair`, `graphitron_reference_for_step_arg_mapping_pair`,
`graphitron_routine_arg_mapping_pair`, `graphitron_service_arg_mapping_pair`. Three type-reference
tables into `jvm_declared_type_ref`: `jvm_method_parameter_type_ref`, `jvm_method_return_type_ref`,
`jvm_record_component_type_ref`. `graphql_implements` and `graphql_union_member` into
`graphql_poly_member`. Also `graphitron_source_row`, `intent_declared_type_ref`,
`graphitron_type_declaration_synthesis` (now `graphitron_minted_type` and
`graphitron_minted_type_site`), `intent_argmapping_pair_live`, `intent_errors_field_live`,
`intent_condition_param_extraction`, `intent_condition_table_parameter`,
`graphitron_argument_path_segment`, `code_condition_method_parameter` (now
`code_method_parameter`), `code_type_slot` (now `code_read_slot`), and
`code_condition_method_parameter_table` (now a join in the resolutions that need it). The demand relations,
dissolved rather than replaced: `intent_field_demand_rule`, `intent_field_exemption_rule`,
`intent_type_demand`, `intent_type_exemption`, `intent_resolved_field_demand` and
`intent_resolved_type_demand`. The deprecated link and federation key decode, its readers having
moved to the `ast_` entries and the directive anchor: `graphitron_link_entry`,
`graphitron_federation_key_entry`, `graphitron_federation_key_field_entry`,
`graphitron_federation_key_field_segment_entry` and `intent_federation_key`. The record of the
link the composition synthesised, its readers having moved to `graphitron_federation_link`:
`graphql_assembly_synthesised_link` and its grain `graph-synthesised-link`. The registry decode's
argMapping relation, its readers having moved to `graphitron_ast_argmapping_pair_entry` and
`graphitron_argmapping_site`: `graphitron_argmapping_entry`, with the indexes
`graphitron_argmapping_entry_site_ix` and `graphitron_argmapping_entry_use_ix`. The registry
decode's condition relations, their readers having moved to `graphql_directive_application` and the
`graphitron_ast_` twins: `graphitron_field_condition_entry`, `graphitron_argument_condition_entry`,
`graphitron_field_condition_context_arg_entry` and `graphitron_argument_condition_context_arg_entry`.
The two `@referenceFor` step relations, their one reader having moved to the step twins:
`graphitron_reference_for_step_entry` and `graphitron_argument_reference_for_step_entry`.

**Renamed, configuration to the `store_` family.** `graphql_source_input` (now
`store_graph_source_input`): a recipe entry matched to a file the graph claims is configuration the
run held, which the `store_` family's charter keeps.

**Renamed, chain to path.** `graphitron_field_chain_link` (now `graphitron_field_path`),
`graphitron_field_chain_link_reading`, `graphitron_field_chain_link_resolution` and its `_rule`,
`_keyed`, `_keyless` and `_open`, `graphitron_field_chain_application`, `intent_field_chain_start`,
`intent_field_chain_node` and `intent_field_chain_terminus`, each now `_path` where it said
`_chain_link` or `_chain`; the grains `field-chain-link`, `field-chain-link-reading`,
`field-chain-link-reach` and its `keyed`, `keyless` and `open` siblings, and
`field-chain-application`; the classes `FieldChainLinkResolutions` and `FieldChainApplications`, now
`FieldPathResolutions` and `FieldPathApplications`.

**Dissolved into the target, the source and the target type.** `graphitron_field_table`, its
grain `field-target-table`, its `target_basis` values `NAMED_TYPE_TABLE`, `PARTICIPANT_TABLE` and
`ROUTINE_RESULT`, `FieldEndpoints` and `FieldEndpointsTest`.

**Removed with `@sourceRow`.** The directive, `graphitron_ast_source_row_entry`, the `SOURCE_ROW`
values of `graphitron_field_key_row.kind`, `graphitron_field_key_row_method.kind`,
`graphitron_field_path_start_lifted.key_kind`, `graphitron_field_source_table.basis` and
`graphitron_method_reference_entry.site`, `SourceRowDirectiveResolver`, `SourceRowDeclaration`,
`LifterMethodResolver`, `LifterRef`, `StaticProducerRef`, `KeyLift.Lifter`,
`ServiceKeySource.FromLifter` and `Rejection.AttemptKind.LIFTER_METHOD`.

**Dissolved into the member join.** A field's read is the member its bound name finds on the class
its parent's result holds, read where it is asked: `graphitron_reader_accessor` and its rule, the
grain `graph-field-class`, `graphitron_field_key_row_method`, `graphitron_carrier_data_channel` and
its rule, and `CarrierDataChannels`.

**Dissolved for want of a reader**, resurrectable from `ab6b49c34`. `graphitron_type_reach`,
`graphitron_type_arrival`, `graphitron_field_source` and their `_rule` views, `TypeReaches`,
`TypeArrivals`, `FieldSources`, the fact documents `type-arrival` and
`type-arrival-through-a-connection`, and `FactExpectationTest`'s source-or-defect gate.

**Dissolved into the reading.** `graphitron_field_routine`, its grain `field-routine-result`,
`FieldRoutines` and `FieldRoutinesTest`.

**Re-keyed, target table to target type.** The `target_source_name`, `target_schema` and
`target_table` columns of `graphitron_field_table_link`, `graphitron_field_table_link_rule`,
`graphitron_field_path_reading`, `graphitron_field_path_resolution`, its `_rule` and its `_keyed`,
`_keyless`, `_open` and `_condition` tables, now `arriving_type_name`.

**Dissolved into a defect.** `intent_field_unlowerable_ordering`, its verdicts
`PARTICIPANT_FAN_OUT` and `KEY_CAPTURE_SCATTER` and its route `PRIMARY_KEY_FALLBACK`,
`intent_field_unlowerable_ordering_rejection`, `UnlowerableOrderingRejectionRows`,
`UnlowerableOrderings.Verdict`, the grains `unlowerable-ordering-route` and
`unlowerable-ordering-rejection`, and the seeded `FieldUnlowerableOrderingTest`.

**Dissolved into the target and its joins.** `intent_field_participant_scope_table` and its
seeded `FieldParticipantScopeTableTest`.

**Renamed, navigation to target.** `graphitron_field_navigation` (now `graphitron_field_target`),
its columns `navigated_type_name` (now `target_type_name`) and `basis` with its values
`CONNECTION_ELEMENT` and `NAMED_TYPE` (now `wrapper_type_name`, the connection's name or null), the
index `graphitron_field_navigation_type_ix`, and the fact document `field-navigation`.
`intent_field_navigated_type` keeps its columns, projected from the target.

The type-backing
cluster, into `graphitron_type_backing`: `intent_type_backing_seed`, `intent_type_backing_class`,
`intent_type_backing` and `intent_field_accessor_hop`, with the grain `type-backing-class` and the
value `BACKING_CLOSURE` (now `PRODUCER` and `ACCESSOR`).
`graphitron_element` gained `ENUM_VALUE`, and `graphitron_enum_value`, `graphitron_federation_link`,
`graphitron_federation_link_import` and `graphitron_ast_federation_link_entry` are new, all added
rather than retired.

**Renamed, by rule rather than by list.** Every relation of the as-written half of `graphitron_`
gained the `_entry` suffix. A sweep for a survivor is a search for a `graphitron_` name that is
neither suffixed nor one of the sixteen in the resolved half, which is cheaper than a list and does
not go stale. Two index names followed: `graphitron_spelled_reference_name_ix`,
`graphitron_method_reference_method_ix`.

**Columns and values.** `store_source.origin` and `store_source.coordinate` (now on
`store_graph_dependency`), `graphitron_field_synthesis.authored_type_sdl`,
`code_external_field_method.table_parameter_type`, `code_condition_method.is_static`,
`graphitron_argmapping_match.segment_position`, `trailing_segments`, `written_path`,
`trailing_name`; `graphitron_argmapping_entry.head_segment`, `head_kind`, `candidate_coordinate`,
`candidate_path`, `type_name`, `field_name`, `argument_name`, and `argument_path` (now
`written_path`); `graphitron_argmapping_candidate.element_name` (now `name`), `type_name`,
`field_name`; `intent_resolved_node_key_projection.trailing_segment_name` (now `trailing_name`);
`code_construction.type_name` and `code_write_slot.type_name` (now `class_name`); the index
`code_type_slot_name_ix` (now `code_read_slot_name_ix`). `site` and `use_site` on
`graphitron_argmapping_match`, `intent_argmapping_bound_parameter_type`,
`intent_argmapping_key_column_candidate`, `intent_resolved_node_key_projection` and
`intent_argmapping_projection_defect`, the mapping string's key and the site's parts in their place.
Values `AUTHORED_EXPRESSION` and `TRAILING_SEGMENTS_BEYOND_ONE`.
`graphitron_field_chain_link_resolution.reach` and its values `TAIL` and `HEAD` as a stored column
(now the payload flags `reached_by_tail` and `reached_by_head`), and `reach` in the
`field-chain-link-reach` grain's key shape. Added rather than retired, and
listed because the grain is the point: `store_graph_source.stamp` and `store_graph_source.read_at`.

**Java.** `CodeCapture.tablesByClass`; `AbstractRewriteMojo.reactorOutputCoordinates`; `DemandRuleTest`, `DemandShadowTest`, `DemandResidue`, `SchemaQueries.Demand` and the
MCP schema tool's `demand` slot; `MacroCapture.expandConnections` (now `expand`); the `Expansions` record, the five
`captureXDirective` callbacks, `captureNavigation`, `connectionElementByType`;
`EntryFamilyFixture.ENTRY_RELATIONS` and `ANCHOR_RELATIONS` (now `entryRelations()`);
`EntryFamilyCoverageTest`'s partition case; `GraphitronFactCapture`'s `schemaDirectives`,
`typeDirectives`, `fieldDirectives`, `argumentDirectives`, `enumValueDirectives`, `argumentsBy`,
`directive`, `parsed`, `location` and the four-argument `undecoded`; `EntryWriterAgreementTest` and
`CapturedStore.entriesDecodedByTheWalk`; `RowChunks` with `ROWS_PER_STATEMENT`, `of` and `execute`,
and `MultiRowWritesAreChunkedTest` (now `WritesBindPerRowTest`); `SdlCapture` with `captureFacts`,
`captureEntries` and `captureGraphitronAnchors`; `SdlAnchor` and `SdlAnchorTest` (now
`GraphQLAnchorTest`); `SourceDocument.parsed()` and its `changed` component; `reclaimVanished` (now
`reclaim`). `TypeBackingRows` and its stratum step; `SeededStore.seedTypeBackingClass` and
`seedTableWithoutRecordClass`; `AccessorHopTest`, `TypeBackingSeedTest` and `TypeBackingTest` (now
the `type-backing*` fact documents and `TypeBackingPartitionTest`). The stated classpath:
`CodeRows.writeStated`, `ClasspathSourceCapture.stated`, `CapturedStore.captureCode` and every
census parameter on `CapturedStore`'s arms; `TestSchemaHelper.classpathCensus` (now
`testClasspath`); the language server fixture's `backingClasses` (now `testClasses`), `jarClass`,
`reactorClass`, `jarRecord`, `reference`, `method`, `parameter`, `producing`, `genericMethod`,
`genericParameter`, `component`, `scalarHolder`, `lifterHolder`, `withScalarConstants` and
`withExternalFieldLifters`; the MCP fixture's `codeFixtureCensus` (now `codeFixtureClasspath`);
`SeededStore.seedScalarConstant` and `seedExternalFieldMethod`. `TagApplier` and `TagApplierTest`, the configured tags being
`graphitron_configured_tag`'s; `DescriptionNoteApplier` and `DescriptionNoteApplierTest`, the notes
minting the part relations' descriptions; `LoadingRewrites.Outcome.Applied.synthesisedLink` and
`GraphQLAssemblyCapture.writeSynthesisedLink`; `EmittedRegistry.applyMintedDirectives` (now `applyUnwritten`).
`CompletionData`, `CatalogBuilder` with `projectTypesByName`, `TypeBackingShape` and its
`byTypeName`, the `rewrite.catalog` package,
`ClasspathCensus`, `ClasspathScanner`, `GraphQLRewriteGenerator.BuildOutput.catalog`,
`DevMojo.rebuildCatalog` (now `onClasspathChange`) and `sessionCensus`, and the tests
`CatalogBuilderTest`, `CatalogBuilderSourceTest`, `ClasspathCensusTest`, `ClasspathScannerTest`,
`JarResidentClassCensusTest`, `TypeBackingProjectionTest`, `TypeBackingClasses`,
`TypeBackingClassesTest`, `TypeBackingShadowTest` and the plugin's `FixtureCatalogTest`; `CatalogRefreshTest` is now
`ClasspathChangeRoundTest`. The specification's built-ins
as constants beside the entries:
`GraphQLAstCapture.SPECIFIED_SCALARS`, `SPECIFIED_DIRECTIVES` with its three column fields and
`specifiedDirectivesUndeclared`, `SdlFactCapture.SPECIFIED_DIRECTIVES`, and the transcription's
filter by built-in name; `GraphQLAstCapture.BEFORE_EVERY_FILE` moved to `GraphQLSourceCapture`,
which now stores it rather than the anchors coalescing to it. Renamed: `SdlEntries` to `GraphQLAstEntries`, `SdlSchemaProblems` to
`GraphQLSchemaProblems`, `GraphitronEntries` to `GraphitronAstEntries`, and with them
`SdlEntriesTest` and `SdlSchemaProblemsTest`. The
gatherer roster row `document` is now `graphql-source`, `graphql-ast`, `graphitron-ast` and
`graphql-assembly`. Remaining `Sdl` names belong to the incumbent walk and go when it does.

**The resolved type binding.** `graphitron_resolved_type_binding`, its writer `ResolvedTypeBindings`,
its index `ix_resolved_type_binding_type` and its grain `resolved-type-binding` went into
`graphitron_tabletype`, which binds a type to its one table and an ambiguous `@table` to none; with
them the `candidates = 1` guard every reader applied, and `TableTypeTest`'s parity check.

**The corpus's worked-example fragments.** `CorpusFragmentRenderer`, `CorpusFragmentRendererTest`,
`CorpusFragmentTest`, `OutcomeBlockRenderer`, `OutcomeBlockRendererTest` and the 34
`docs/architecture/reference/_example-<id>.adoc` fragments, with the code generation triggers page
rewritten over the occurrence model and its examples rendered from fact documents by
`ManualExamples`; `HowToIndexCoverageTest`'s check that the reference section holds fragments went
with them.

**The claim views.** `intent_authored_field_claim`, `intent_authored_type_claim`,
`intent_column_match_claim`, `intent_resolved_field_claim` and `intent_authored_claim_conflict`, with
their classifier and tier vocabularies (`TABLE_COLUMN`, `AUTHORED`, `INFERRED`, the presence arms'
`decoded`), `AuthoredClaim.fromClassifier`, and the MCP field claim's `tier` and `decoded`: the claims
are `graphitron_claim`'s, what took effect the occurrence's.

**The column-match shadow.** `ColumnMatchShadowTest`, which held the walk's column-match arm to the
claim view over the corpus; the column a field binds is pinned by fact documents, and the walk is no
oracle for it.

**The planner's rows in the corpus.** The 61 `plan_launcher_command` blocks, the `plan_` apparatus
prefix with `CorpusExpectations.APPARATUS_PREFIX` and `LAUNCHER_COMMAND_RELATION`,
`CorpusExpectationTest.landTheLauncherCommands` and `theApparatusPopulationStaysDistinguishable`,
`ExemptionRegistry`'s `LAUNCHER_COMMITMENT` obligation with `LAUNCHER_COMMITMENT_GAPS` and
`corpusCommittedLauncherArms`, and `ClassifiedHarness.launchSourceArmSimpleNames` and
`resultShapeArmSimpleNames`: the planner is the walk's and dissolves with it. With them the corpus's
`graphitron_tabletype` and `graphitron_synthesized_node_type_id` blocks, the first pinned by
`scenarios/routine-result`, the second moved to `scenarios/synthesized-node-type-id`, and
`QueryViewRenderer`'s corpus prelude, `render(String, String)` and `touchedCoordinates`, its test now
on fact-document fixtures.

**The producer-method views.** `intent_field_producer_reference` and `intent_field_producer_method`,
with `FieldProducerMethodTest`, now `CodeReferenceClasspathScopeTest` over `graphitron_code_reference`:
a code reference resolves there, and an overload or an absence is a defect of its own.

**The reference-step family** (2026-11-06). `FieldReferenceStepHops` with
`graphitron_field_reference_step_hop`, `graphitron_field_reference_step_hop_keyed` and
`graphitron_field_reference_step_hop_keyless`; `ArgumentReferenceStepTargets` with
`graphitron_argument_reference_step_target`, `graphitron_argument_reference_step_target_keyed` and
`graphitron_argument_reference_step_target_keyless`; `InputFieldReferenceStepTargets` with
`graphitron_input_field_reference_step_target`, `graphitron_input_field_reference_step_target_keyed`
and `graphitron_input_field_reference_step_target_keyless`; `ReferenceStepWalk`;
`intent_argument_reference_step_hop`; `graphitron_condition_method_route`; and the captures
`graphitron_field_reference_step_entry` and `graphitron_argument_reference_step_entry`, a path
element's spelling now read off `graphitron_ast_reference_table_step_entry`,
`graphitron_ast_reference_key_step_entry` and `graphitron_ast_reference_condition_step_entry`. The
grains `reference-step-hop`, `reference-step-keyed-hop`, `reference-step-keyless-hop`,
`argument-reference-step-target`, `argument-reference-step-keyed-target`,
`argument-reference-step-keyless-target`, `input-field-reference-step-target`,
`input-field-reference-step-keyed-target`, `input-field-reference-step-keyless-target` and
`condition-method-route` went with them, and so did `ReferenceStepRecombinationTest`. Every reader
reads `graphitron_join_path_step` and its subtypes at the occurrence.

**The old walk** (2026-11-07). `FieldPaths` with `graphitron_field_path`, `graphitron_field_path_rule`,
`graphitron_field_path_start`, `graphitron_field_path_start_rule`, `graphitron_field_path_start_routine`,
`graphitron_field_path_column`, `graphitron_field_path_key`, `graphitron_field_path_primary_key`,
`graphitron_field_path_method`, `graphitron_field_path_method_candidate`, `graphitron_field_path_routine`,
`graphitron_field_path_parameter`, `graphitron_field_path_parameter_rule`,
`graphitron_field_path_parameter_binding`, `graphitron_field_path_stop` and
`graphitron_field_path_landing`, and the grains `field-path`, `field-path-start`, `field-path-column`,
`field-path-key`, `field-path-method`, `field-path-method-candidate`, `field-path-routine`,
`field-path-parameter` and `field-path-stop`. A field's path is `graphitron_join_path_step` and its
subtypes at the occurrence, its start `graphitron_path_need`, its landing `graphitron_join_path`.

**Relations nobody reads, first group** (2026-11-07). `intent_node_container_member`,
`intent_record_slot_assignable` and `sql_constraint_hop`, with the `constraint-hop` grain.

**Decode entries nobody read** (2026-11-07). `graphitron_split_query_entry`,
`graphitron_tenant_fan_out_entry` and `graphitron_multitable_reference_entry`, presence being
`graphql_directive_application`'s; `graphitron_argument_binding_entry`, served by
`graphitron_ast_binding_entry`; `graphitron_argument_reference_entry`, served by
`graphitron_ast_reference_entry`; `graphitron_pivot_entry`, `graphitron_external_field_entry` and
`graphitron_default_order_field_entry` with the grain `field-order-position`, their `graphitron_ast_`
twins staying; and `graphitron_ast_field_condition_context_arg_entry`,
`graphitron_ast_input_value_condition_context_arg_entry` and
`graphitron_ast_service_context_arg_entry`, a context key being `graphitron_call_context_key_written`'s.
`GraphitronFactCapture`'s marker writer and `FactWrites.graphitronArgumentBinding` went with them.

**The `@error` decode** (2026-11-07). `graphitron_error_entry` and `graphitron_error_handler_entry`;
the language server's type hover reads an `@error` type's handlers off `graphitron_ast_error_entry`'s
family through `graphql_directive_application`.

**The `@node` coordinate copies** (2026-11-07). `graphitron_node_entry`,
`graphitron_node_keycolumn_entry` and `graphitron_node_legacykeycolumn_entry`, with the grain
`node-legacy-key-written-position`, and `GraphitronAnchor`'s `nodes` and `nodeKeyColumns`. Their
readers read the type's first `@node` application off `graphql_directive_application`, its type id
off `graphitron_ast_node_entry` and its key columns off the two `graphitron_ast_` key-column entries,
which carry the application and the index each element was written at.

**The anchor's field-site copies** (2026-11-07). `graphitron_field_binding_entry`,
`graphitron_facet_entry`, `graphitron_connection_entry`, `graphitron_default_order_entry`,
`graphitron_service_entry` and `graphitron_mutation_entry`, with `GraphitronAnchor`'s
`claimedOnField` and `claimedOnInputField` and `FactWrites.graphitronMutation`. Their readers join
the field's first application in `graphql_directive_application` to `graphql_field_element` and to
the `graphitron_ast_` entry at its position; the `@mutation` spelling claim is keyed on
`graphitron_spelled_reference_entry`.

**The anchor's type-site copies** (2026-11-07). `graphitron_table_entry` and
`graphitron_scalar_type_entry`, with the grain `graph-scalar`, `GraphitronAnchor`'s `claimed`, and
`FactWrites.graphitronTable`. Their readers join the type's first `@table` or `@scalarType`
application to `graphitron_ast_table_entry` or `graphitron_ast_scalar_type_entry`, a bare `@table`
resolving against the type's own name; the `@table` spelling claim is keyed on
`graphitron_spelled_reference_entry`, and `meta_family_headline` names `graphitron_ast_table_entry`.

**The `@outbox` copy** (2026-11-07). `graphitron_outbox_entry`, the grain `field-directive-application` and `GraphitronAnchor.outboxes`. A
message is what was written, so its readers (`graphitron_outbox_result_step`,
`graphitron_outbox_leaf`, the `OUTBOX_` rules of `graphitron_entry_defect_rule`, `OutboxDefects`
and `OutboxQuery`) read the field's `@outbox` applications in `graphql_directive_application`,
numbered there in written order, joined to `graphitron_ast_outbox_entry`; an event left out is the
field's coordinate, stated in each reader's own query.

**The `@nodeId` per-site pair** (2026-11-07). `graphitron_field_node_id_entry` and
`graphitron_argument_node_id_entry`, with the decode's `@nodeId` arms. Whether `@nodeId` was
written at a coordinate, and with which `typeName:`, is what was written, so
`graphitron_node_id_instruction_rule` and `graphitron_argmapping_match` read the coordinate's first
`@nodeId` application joined to `graphitron_ast_node_id_entry`.

**The `@referenceFor` per-site pair** (2026-11-07). `graphitron_reference_for_entry` and
`graphitron_argument_reference_for_entry`, and `intent_reference_for_application` with its grain
`reference-for-application`, dissolved into its one reader: `ReferenceForParticipantDefects` reads
the written participant off the `@referenceFor` applications and `graphitron_ast_reference_for_entry`
and anti-joins the consumers offering it off the occurrences and the field targets. The decode's
step method-reference claims are keyed on `graphitron_method_reference_entry`.

**The undecoded-argument quarantine** (2026-11-07). `graphitron_undecoded_argument_entry`, read
only by `meta_family_headline`, which drops it, and the decode's `undecoded` writer: an authored
literal is `graphql_directive_application_arg.value_sdl` and `graphql_ast_value_entry`'s.

**The method-reference supertype** (2026-11-07). `graphitron_method_reference_entry`, its index
`graphitron_method_reference_entry_method_ix`, the decode's `methodReference` and `useSite` and its
`@condition`, `@service` and `@externalField` arms, and `SupertypeSiteReferenceTest`. Its one reader,
the language server's class usages, reads `graphitron_code_reference_site` positioned at the owning
application; the step spelling claims are keyed on `graphitron_spelled_reference_entry`.

**The argument-site marker decodes** (2026-11-07). `graphitron_order_by_entry` and
`graphitron_argument_lookup_key_entry`: their readers, two rules of `graphitron_entry_defect_rule`,
`UnlowerableOrderings` and `RoutineWriteQuery`, read the applications at the field's arguments off
`graphql_directive_application` and `graphql_argument_element`.

**The type binding view.** `intent_bound_table`, with the language server's `BoundTables`, the MCP
tables slot's `candidates` count and `TableBinding.candidates`: a type's table is
`graphitron_tabletype`'s, the tables its fields read the occurrences' source tables, and an
ambiguous `@table`'s candidates rows of `graphitron_entry_defect_table` under its defect. With the
corpus left asserting nothing about the store, `CorpusExpectationTest` went too.

**One survivor flagged rather than corrected.** `authored-connection-type-scope-silence` rests its
premise on `graphitron_field_synthesis.authored_type_sdl` and on `intent_field_scope_table` reading
it. The column is gone and that view reads neither relation now. A dated note says so in its body;
whether the defect it reports still exists is its own author's to re-measure.

**The uncorrelated same-table read.** With a same-table field refused unless a key joins it, the
inline multiset's standalone shape is gone: `CallWrap.Multiset` and `CallWrap.LookupMultiset` take a
non-empty path and a parent correlation, and `ProjectionUnitRenderer` no longer renders a read of the
field's own table under `noCondition()`. A `TableField` takes a non-empty path and a parent
correlation too, and `Presence`'s empty-path check on a table probe went with the shape.

**The old node-id relations** (2026-11-08). `graphitron_node_id_instruction`,
`graphitron_node_id_decode_hop`, `graphitron_node_id_decode_hop_column` and
`graphitron_node_id_decode_column`, each with its `_rule` view, its writer (`NodeIdInstructions`,
`NodeIdDecodeHops`, `NodeIdDecodeHopColumns`, `NodeIdDecodeColumns`) and its `DerivationStratum`
step, and the grains `nodeid-instruction`, `nodeid-decode-hop`, `nodeid-decode-hop-column` and
`nodeid-decode-column`; with them `intent_node_id_decode_endpoint`, and the endpoint's
`navigation` vocabulary (`SAME_TABLE`, `AUTHORED_PATH`, `DISCOVERED_KEY`), the hop's `via` and
`fk_on_from`, and the decode column's `local_column_name` lift. A node id's node type and key are
`graphitron_occurrence_node_id`'s, the route to it the join path's, and the record column a decoded
value loads into is read by `NodeIdDecodeQuery` at emit time. `PartitionSelectivityWorthTest`, whose
two subjects were the instruction and decode-hop rules, went with them, and with it
`StoreStatistics.resetIncludingTheDeclaration`.

**The foreign key column pairing view** (2026-11-08). `intent_foreign_key_column_pair`, whose last
reader was the node-id decode rule. The pairing a path riding a foreign key equates is
`graphitron_join_path_step_column`'s, per step, and the captured pairing it rests on
`sql_referential_constraint_column`'s.

## Owed, not done here

Found while writing the discipline down, verified, and not fixed. Recorded so they are findable
rather than left in a transcript.

* **A declared reactor sibling with no build output is read from a stale installed jar, silently.**
  Run on its own from a checkout where `opptak-service` had never been built, opptak-subgraph's
  generate fell back to an installed `opptak-service-1.0-SNAPSHOT.jar` from an older build that
  lacks the classes the schema names, and refused seven `@error` classes and the services of
  thirteen fields that the sibling's sources declare. No stale-module warning fired, though the sources are newer
  than the jar. In a reactor build the sibling compiles first and nothing is refused.
* **Generation reads a class the schema names only once it is compiled.** Parked rather than
  planned: generation runs before the module compiles, so a class written in the module's own
  sources, or in a declared sibling's that was not built, is not nameable until it is. The case
  first offered for it was the stale sibling above, not a class in the module's own sources, so
  no real schema is known to need it yet. Built once and dropped: the `java_` family capturing each
  file's package and imports and each class's written supertypes, `generate` walking the module's
  own source roots (20 files in 732 ms cold on opptak), nameability taking the module's sources in
  place of its compiled output, and the `@error` check following written supertypes to a class the
  classpath knows. A `@service` in the same module would still need its compiled method signatures.

* **Nothing enforces mark and sweep.** No gate checks that a stored relation's rows are swept. One
  gatherer's clear set is re-derived from its own source text and compared; the other lists are
  unchecked. This is one of the three things a stored relation owes and it has no enforcer.
* **The ownership gate binds on declared views only.** `MetaDeclarationGateTest`'s population is
  `WHERE RELATION_TYPE = 'VIEW'`, and within it a read of a relation still on the undeclared roster
  is skipped. A relation filled by hand-written jOOQ is outside it entirely, because what such a
  producer reads is in no stored definition for a parse to find. The javadoc handed those producers
  to `CaptureCorpusIsolationTest`, whose scope is `graphql_` by prefix plus the graphitron entry
  half, so the catalog, classpath and code families are in neither population. Three sessions
  disproved that sentence independently; the javadoc now states the gap, and closing it is open.
* **Nothing checks that a declared grain is the right grain.** The key gate compares
  `meta_grain.key_shape` against the actual primary key, which is two authored strings agreeing with
  each other, and it excludes views. A relation whose key and whose declaration state the same wrong
  grain passes. The view exclusion is not hypothetical: `graphitron_field_path_reading`
  arrived declaring a grain that did not identify a row, naming the constraint by its own name
  alone where that name is unique per table, and omitting the endpoints entirely, which are all
  that tells apart the two arms carrying no constraint. It was found by reading and fixed by
  reading, which is the part that does not scale.
* **`SchemaIdentifierDriftCheck` can report findings the source no longer holds.** It reads the store
  prose out of a booted H2 store, so the DDL it sees is the compiled resource rather than the file it
  names, and it labels every finding with the `src/main/resources` path. On an incremental build that
  pair means it reports against a stale copy while pointing at a source file that is already fixed.

* **`store_graph_write` grows without bound.** Every finished capture adds its rows, about 250 per
  sis round, and nothing removes the old ones.

## What a reviewer should press on

* **Slice 1's classification is measured at eight relations and asserted at the other 48.** A
  relation the classification gets wrong lands in a gatherer with no store to fall back on, and the
  failure is a missing row rather than an error.
* **Ownership moved in the code and is declared nowhere until slice 7.** So the store's own account
  of who writes the entry half is wrong in the meantime, and the gate that would catch it binds per
  declared row. Press on whether that window is acceptable.
* **One rewritten rule does not entitle the item to a general claim.** Two further plan defects were
  found on the same capture and neither has had its rule examined, only its storage form tested,
  which is the substitution this item exists to name.
* **The exit claim changed late and should be pressed hardest.** The collapse is a stronger claim
  than the ownership argument it replaced.

## Provenance

This item took over the performance narrative from nine dissolved items and ran from 2026-08-28. On
2026-09-22 it was 5315 lines carrying thirty-four dated work-log sections, and had reached the state
where three sessions argued for a day over a question it answered at line 61. The body was cut to
this. What moved:

* Measurements to `roadmap/audits/2026-08-28-derived-read-cost-premise.md` and
  `roadmap/audits/2026-09-22-capture-dissolution-measurements.md`.
* The seeding dissolution's evidence, and the classpath-skip figures, to
  `roadmap/audits/2026-09-27-seeding-dissolution.md`. Added when the arc became a
  branch of the graph rather than an entry in "Owed, not done here".
* Narrative and retired approaches to the September 2026 chapter of
  `docs/history/road-to-the-relational-core.adoc`.
* The discipline itself to `docs/architecture/explanation/modeling-discipline.adoc`, with the procedure in
  `.claude/skills/adding-a-relation` and a short form in `CLAUDE.md`.
* Everything else is in git, which the deletion-at-Done convention already names as the archive.

## Reviewer findings

Rounds 1 to 3 (2026-08-28, Spec to Ready) and round 4 (2026-09-09, In Progress) are addressed and are
in this file's git history. Nothing is open.

**Node-id decode defects moved onto the occurrence** (2026-11-06). `intent_node_id_decode_slot`,
`intent_node_id_decode_defect` and `intent_node_id_polymorphic_decode_defect`, with their verdicts
`KEY_ARITY_EXCEEDS_SLOT`, `KEY_COLUMN_TYPE_DISAGREEMENT`, `SLOT_SHAPE_MISMATCH`,
`CONTAINER_NOT_AT_A_SLOT`, `SINGLE_TABLE_CONTAINER`, `NO_TABLE_MEMBERS`, `MEMBER_NOT_NODE_TYPE` and
`SLOT_NOT_SUPERTYPE_OF_MEMBER` as view values: the slot is `graphitron_node_id_slot`, the verdicts
are entry defect codes with the `NODE_ID_` prefix, and a container defect's members are
`graphitron_entry_defect_member`.
