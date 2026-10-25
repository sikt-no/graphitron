---
id: R876
title: "Expensive derived reads are a modelling defect: every rule needs an owner, and once ownership is computed the derivation gatherer is unearned and meta_materialize has no subject"
status: In Progress
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-28
last-updated: 2026-10-21
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

**Destination: a `@service` field is generated from the store alone.** Its call, what fills each
parameter and the result its fields read are store facts the generator emits from, so neither
`ServiceCatalog` nor the walk resolves anything for it. The critical path, by node title:

```text
A service call is emitted from the store
├── A service call is the one overload every parameter of which is filled
│   └── Every call's parameter states what fills it
│       ├── A condition reference names an overload set
│       ├── A parameter states its type's first argument
│       ├── A `Configuration` parameter is the jOOQ context
│       ├── An argument path through input types is stored
│       ├── A parameter's fill states how its value is built
│       │   └── The generated jOOQ classes are read with their members
│       ├── A consumed key is a source
│       │   └── A producer's consumed keys are stated
│       ├── `$context` names a context value
│       └── `$tenant` in argMapping routes a service call
├── A service's holder is its one widest buildable constructor
│   └── A service class's construction is stated
└── A producer's carrier is its one data channel
    └── A field's source is its parent's result
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
* `graphitron_path_element_*` beside the step hops. Ends at "Nothing resolves a path but the walk".
* A routine-ended field's function in `graphitron_routine_result` and in
  `graphitron_field_path_landing`'s row with no place. Ends at "The landing does not restate a routine's function".
* Argument and input-field walks from `graphitron_field_scope_table` beside the target. Ends at
  "The argument readers read the target and its walk".
* `intent_field_producer_method` beside `graphitron_code_reference`. Ends at "Every producer reader reads `graphitron_code_reference`".
* The code reference in the twins' columns beside `graphitron_ast_code_reference_entry`. Ends at
  "The per-site copies and coordinate-keyed entries go".
* The walk's unknown-method rejections beside the stored code-reference defects. Ends at "The walk's unknown-method rejections go".
* The name-match join in `sql_name_matched_key_column` and in
  `BuildContext.synthesizeNameMatchedJoin`. Ends at "`sql_name_matched_key_column` is gone".
* Clear-and-recompute in `DerivationStratum` and the assembly steps beside mark and sweep. Ends at
  "`DerivationStratum` is gone, each step anchored by its owner".
* A field's departure table in `graphitron_field_source_table`'s interim rules and in
  `graphitron_result_table`. Ends at "A consumed key is a source".
* A payload's producer in `graphitron_carrier_data_field` and the `intent_` carrier relations. Ends
  at "A producer's carrier is its one data channel".
* A field's column picked by `intent_column_match_claim` and stated by
  `graphitron_field_target_column`. Ends at "The claims go".
* An `id: ID` without `@nodeId`, a node id to the generator and a column to the store. Ends at "The generator reads an `id: ID` without `@nodeId` as a column".
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
* The coordinate copies and the position-keyed entries of `@node` and `@table`. Ends at "The coordinate copies go".
* `graphitron_node_type`, `graphitron_node` and `NodeDeclaration.isNodeType`, each saying what a
  node is. Ends at "A node is what the manual says takes effect".
* `graphitron_node_id_instruction` with its private route beside the chain. Ends at "Encode and decode read the target type, and the old node-id relations go".
* Walk derivations and their store replacements, pinned by the shadow tests. Ends at "The shadow tests go with their walk side".
* The vocabulary listed twice, by the editor from `graphitron_directive` and by MCP from
  `graphql_directive`. Ends at "The MCP lists the composed vocabulary".
* `SeededStore` and capture, both building test stores. Ends at "`SeededStore` is deleted".

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
* **An application is identified by where its `@` was written** `blocked`, on the three below and
  on "Nothing resolves a path but the walk". `graphitron_directive_application` is keyed by
  position, and the ordinal stays only on applications with no position.
  * **The argMapping match carries the application's position** `open`.
    `graphitron_argmapping_match` and what reads it name the application by position, not ordinal.
  * **A use site is identified by position** `blocked`, on "No `graphitron_*_entry` survives, an entry being the transcription's alone". Nothing spells a use site as `Type.field#ordinal`.
  * **The emitted applications number their own** `open`. A configured or minted application keeps
    an ordinal of its own, and the authored ones rank by position instead of copying one.

### The classpath family affords what its readers ask `done`

`code_` holds every fact its readers ask for, read from the classpath it is allowed to read, and the
census it replaced is gone.

### A resolution keeps what a diagnostic needs `open`

Every entry defect the store reports agrees with what the generator accepts, so an author is never
shown an error on a schema that generates, and validate can then fail on them.

* **A condition method over untyped tables resolves** `open`. A condition-only element whose method takes `Table<?>` steps from where the walk stands,
  as the generator calls it.
* **The sakila example draws no error-severity entry defect** `blocked`, on "A condition method over untyped tables resolves" and "A condition reference names an overload set". A build check
  over the reactor's own example holds it.
* **Entry defects fail validate** `blocked`, on "The sakila example draws no error-severity entry defect". Every error code in `graphitron_entry_defect` stops a build, not only the editor.
* **The column defects read the match once** `open`. `COLUMN_UNRESOLVED` and `COLUMN_AMBIGUOUS`
  are one read of `graphitron_field_target_column_match`, not one each through
  `graphitron_field_path_landing`. On sis, 2026-10-23, the two cost 1.2 s against 0.57 s before
  the match read the landing, and `graphitron_entry_defect` went from 3.2 to 4.0 s.

### The path replaces the walk it was built to retire `open`

A field's path is the join from its source table to its target table. When this is done one forward
walk, starting from the source and reading each written element once, resolves every path at every
grain into rows that say where each step comes from, where it goes and how it joins; the readings,
resolutions and links built beside it are gone.

* **Capture states each field's path in the order it was written** `done`.
* **A path is resolved by a deterministic forward walk over what each element names** `done`.
* **A table-bound type no field returns is walked from its binding** `done`.
* **The landing does not restate a routine's function** `open`. `graphitron_field_path_landing`'s row
  with no place, a path a `@routine` ends with nothing after it, goes, and a reader wanting that
  function reads `graphitron_routine_result`, which states it once before the walk.
* **A round of the walk steps only its newest layer** `open`. Each round re-reads the rule from every
  start and keys, columns and stops read it again after the fixpoint, about 9 rule evaluations per
  capture and 1.4 to 1.6 s on sis at load 5 to 13; a round that steps only the steps the last one
  added, and writes their keys, columns and stops as it goes, reads each step once.
* **A child `@routine` is a lateral step of the path** `open`, E's. Only a root's first `@routine` is
  a producer; any other is a step from the table the walk stands on to its function, called with
  that table's columns and the field's arguments. Each parameter's source is a row of the step,
  `graphitron_field_path_parameter`, read off `graphitron_field_path_parameter_binding`.
  `columnMapping` is gone: an `argMapping` entry names an argument, or with `$source.<column>` a
  column of the standing table, and one that binds nothing is `ROUTINE_PARAMETER_UNBOUND`. A path ending on a `@routine` whose field returns a table-bound type
  takes one more step, from the function's result to that table by name: a walk step at the next
  place with no written element. The MCP server shows the routine step and the inferred one; what
  is left is the editor, whose `argMapping` completion at a path routine offers the field's
  arguments and not yet the standing table's columns. `graphitron_path_element_routine`, what a
  `@routine` element names, sits on the walk's side of "`graphitron_path_element_*` beside the
  step hops", which stays as it is; it retired `graphitron_producer_routine`'s own spelling of the
  same lookup.
* **A split child `@routine` is a path routine** `blocked`, on "A child `@routine` is a lateral step of the path". `graphitron_producer_claim` claims `ROUTINE` for a child `@routine` under
  `@splitQuery`, such as `Actor.filmsSplit`; its producer is the split re-query, and the routine is
  the walk's first step from the parent's keys.
* **A broken or refused path is a defect at its position** `open`. A walk that stops, ends at the wrong
  table or has no source, an element naming nothing, and `@reference` beside `@referenceFor` or
  `@referenceFor` on a non-abstract type each draw a code at their position. No source means
  neither a result table nor a consumed key starts the walk, so `@sourceRow` fields and
  `FilmDetailsCarrier.language` have none until "A consumed key is a source".
  * **A root field's `@reference` with no `@routine` is refused at the `@reference`** `done`.
* **The field grain reads only the walk** `done`.
* **A key element states which name it matched by** `done`.
* **The step targets' readers read the walk** `done`.
* **A scalar with a path has a target column where its path lands** `done`. `graphitron_field_target_column`
  states the table a read arrives at beside the one it departs from, read off `graphitron_field_path_landing`
  where a path is written, so `COLUMN_UNRESOLVED` and the `@field` hint judge the arrival.
  Applications compose in written order, a path the walk stops on has no column and only the path's
  defect, and an object field's directives resolve against `graphitron_field_target_table`.
  `graphitron_field_column_scope` is gone.
* **Nothing resolves a path but the walk** `blocked`, on "Arguments and input fields walk from their field's target". The step hops and `graphitron_condition_method_route` go last, and the `@reference`
  page says a path is read from source to target only.
* **Every field has a path, the written one or the one discovery implies** `open`. Where nothing is written the one key between source and target
  is stated as path rows, so no reader falls back to discovery for itself, and the editor's
  omitted-`@reference` hint fills in from those rows.

### Arguments and input fields are resolved as fields are, from a target model `open`

An argument or input field binds the way a field does: to a target table, the column or key it
names there, and the path from its holder's target to it. Today `graphitron_field_scope_table`,
`intent_input_occurrence_path`, `intent_input_occurrence_path_step`,
`intent_argument_reference_step_hop` and the argument and input-field step targets derive that at
read time; the `intent_argmapping_` relations go with "An argument path through input types is stored". The target model is the path model fields
already have: every path is a row of one supertype, an output field's path is its one named subtype,
and an input field's or argument's path is anonymous, walked by the same rules from the target of
each field that uses it. R1099, the author-facing navigation over output fields, builds on this
branch rather than modelling inputs again. Deferred on 2026-10-22: it starts after the work in hand.

* **An argument's and an input field's target is modelled first, as tables** `open`.
  `graphitron_path`, one row per path keyed by its holder's definition site with a kind (field,
  input field, argument), and one subtype naming a path by the output field it is rooted in, the
  only way a path is reused or addressed by name; declared with keys and grains before anything
  writes them. Retires, through the nodes below, the argument and input-field walks from
  `graphitron_field_scope_table`.
* **The walk is keyed by the path** `blocked`, on "An argument's and an input field's target is modelled first, as tables". Start, steps, keys, columns, methods, routines, parameters and
  stops move from (type, field) to the path, their readers with them, with a sis timing before and
  after.
* **The corpus is written against the target model** `blocked`, on "The walk is keyed by the path". Fact documents state each occurrence's walk:
  an input type reused across fields and targets, nested inputs, interface and union targets, and
  defects at the writing site naming the using field.
* **Arguments and input fields walk from their field's target** `blocked`, on "The corpus is written against the target model". An input field's or argument's `@reference` is
  captured as an anonymous path and started at the target table of each field that uses it, one
  walk per occurrence, with no step relations of its own.
* **The argument readers read the target and its walk** `blocked`, on "Arguments and input fields walk from their field's target". The condition, lookup-key, node-id and `argMapping`
  readers move, and `graphitron_field_scope_table`, the argument and input-field step targets and
  the `intent_` relations above go.

### A reference resolves where its directive is decoded `open`

A directive naming Java code or a routine is resolved once, by the graphitron anchor against the
classpath and catalog, and every reader reads that resolution rather than a view of its own.

* **A condition reference names an overload set** `open`. `graphitron_code_reference` holds every
  matching overload, and needing exactly one is the rule of `@externalField` and `@sourceRow` alone;
  a service picks among its overloads by what binds.
* **Every producer reader reads `graphitron_code_reference`** `open`. `SchemaQueries`,
  `DeclarationFacts` and the other readers of `intent_field_producer_method` are rewritten against
  the resolution, each with an SDL test passing before and after, and the view goes.
* **The per-site copies and coordinate-keyed entries go** `blocked`, on "Every producer reader reads `graphitron_code_reference`" and "The code-reference subject converts". The twins lose their
  class, method and argMapping columns, and `graphitron_service_entry` and
  `graphitron_external_field_entry` go with their writers.
* **The walk's unknown-method rejections go** `blocked`, on "Entry defects fail validate".
  `Rejection.unknownServiceMethod` and `unknownLifterMethod` give way to the stored defects.
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

* **The generated jOOQ classes are read with their members** `open`. The census's header-only
  package goes, so tables, records, `Routines`, package classes and DAOs are read like any other
  class, with a sis capture timed before and after.
* **A service class's construction is stated** `open`. `code_construction` reaches the declaring
  class of every non-static `code_service_method` row, the code gatherer's own candidate set, and
  states its public constructors as candidates of a `CONTEXT` shape with each parameter's role, kept
  apart from the methods as the census keeps them.
* **A parameter states its type's first argument** `open`. `code_method_parameter` carries the class
  the census reads at type-argument path `0`, so `Set<FilmRecord>` is told from
  `Set<List<FilmRecord>>` without parsing a spelling.
* **A `Configuration` parameter is the jOOQ context** `open`. `code_method_parameter.role` gains
  `CONFIGURATION` beside `DSL_CONTEXT`, both decided by exact type rather than by being a subtype.
* **A service's holder is its one widest buildable constructor** `blocked`, on "A service class's construction is stated" and "A `Configuration` parameter is the jOOQ context". Every
  parameter is jOOQ context or a context key; two of the widest, or none buildable, is a defect.
* **An argument path through input types is stored** `open`. This is R1099's step 2,
  `graphitron_argmapping_binding`, and is done when that step lands; R1099 owns what it retires.
* **A parameter's fill states how its value is built** `blocked`, on "An argument path through input types is stored" and "The generated jOOQ classes are read with their members". A scalar, a list, or a Java record,
  bean or jOOQ record built from an input object's fields, as the parameter's delivered class asks,
  replacing the delivery `intent_argmapping_bound_parameter_type` derives.
* **`$context` names a context value** `open`. A context argument is an argMapping source as
  `$session` is, so `contextArguments` reads as the pairs it implies and a parameter row names the key.
* **Every call's parameter states what fills it** `blocked`, on "A parameter states its type's first argument", "A `Configuration` parameter is the jOOQ context", "A condition reference names an overload set", "An argument path through input types is stored", "A parameter's fill states how its value is built", "A consumed key is a source", "`$context` names a context value" and "`$tenant` in argMapping routes a service call". One row per call site, callee and position for every directive that calls a method or routine (`@service`,
  `@routine` at a root or on a path, `@condition`, `@tableMethod`, `@externalField`, `@record`): jOOQ
  context, `$session`, `$context`, `$tenant`, `$source` or the parent's keys, or an argument path,
  claimed by argMapping, by name, by type or as the one unclaimed pair. A routine step's
  `graphitron_field_path_parameter` rows become its rows.
* **A service call is the one overload every parameter of which is filled** `blocked`, on "Every call's parameter states what fills it". Every argMapping left side names one of its parameters;
  none is an unclaimed parameter and several an ambiguous call, defects beside facts in the corpus.
* **`$tenant` in argMapping routes a service call** `open`. The sigil on a pair's left side names
  the value whose tenant the call runs on, beside `@tenant`, with the same type and same-tenant rules.
  * **`@tenant` is gone** `blocked`, on "`$tenant` in argMapping routes a service call" and sis
    moving its ten uses. The directive, the tenant index's reading of it and its placement refusals go.
* **A service call is emitted from the store** `blocked`, on "A service call is the one overload every parameter of which is filled" and "A service's holder is its one widest buildable constructor". `ServiceMethodCallEmitter` reads the call, its fills and its holder, and
  `ServiceCatalog`'s decode, claim and bind phases go.
* **A table class's column fields are stated** `blocked`, on "The generated jOOQ classes are read with their members". The code family keeps each generated table's public column fields, which
  the census reads and the capture drops.
* **The catalog says which columns jOOQ computes** `open`. `sql_column` states a column's computed
  flag and where it is computed, so a client-side computed column is told from a stored one.

### The graphitron family completes its flip and subtract `open`

Every replacement this family built has had its readers moved and its predecessor deleted, so the
store keeps one shape of each fact. That means no coordinate-keyed entries, no separate assembly
pass and no stratum that clears and recomputes.

* **No `graphitron_*_entry` survives, an entry being the transcription's alone** `blocked`, on the
  five below. Only the `graphitron_ast_` family holds entries, and the registry decode that wrote
  the others goes.
  * **The per-site triples collapse onto a coordinate** `open`. As `graphql_directive_application`
    did, so one collapse retires several relations.
  * **`graphitron_routine_entry` is gone** `open`. The registry decode stops writing it, and its
    readers move to `graphitron_ast_routine_entry`, the path and the applications.
  * **The reference-step entries are gone** `blocked`, on "Nothing resolves a path but the walk".
    Whether a coordinate writes a path is read off its applications, and nothing else reads them.
  * **The untwinned entries get an ast entry first** `open`. Each entry with no `graphitron_ast_`
    twin gets one, its readers move, and the old one goes.
  * **A field's producer claim is an entry, what the author wrote, one per field** `open`. The
    transcription states, for each field whose own directives claim a producer, the kind it claims
    (`SERVICE`, `ROUTINE`, `INSERT`, `UPDATE`, `DELETE`) and the application that claims it, read
    from the field and its directives alone; two claims on one field are a defect at the entry.
    `graphitron_producer_claim` stops assembling claims from the per-directive entries and reads
    this one. The kinds no author writes stay the anchor's, decided by position: a root's `SELECT`,
    a connection's `COUNT` and `FACETS`, and the `LOOKUP` a result type's table child re-reads by.
* **The supertype roster becomes shrink-only** `open`. The roster of duplicated entries is a
  ratchet that can only lose rows.
* **`GraphitronAssemblyCapture` is gone, its steps the anchor's own lines** `blocked`, on the child
  below and on "Nothing resolves a path but the walk".
  * **The assembly steps that clear and refill are marked and swept** `open`. `NodeKeyColumns`,
    `NodeIdCandidates`, `SpelledTables` and `ResolvedTypeBindings`; the path steps go with the walk.
* **`DerivationStratum` is gone, each step anchored by its owner** `blocked`, on the two below.
  With `StageProgress` and `StageOrderGateTest`, the order they kept being the gatherer order.
  * **Each stratum step moves into its owner's anchor phase, marked and swept** `open`.
    `CarrierDataFields` first, since a graphitron anchor reader sees the previous capture's rows.
  * **The stratum's `intent_` writers go** `open`. `ClassificationDomainCapture`,
    `InputOccurrencePaths` and `AuthoredClaimRejectionRows` go with the relations they write.

### `capture` runs against the model alone `done`

The goal opens the store, captures and closes it, and each gatherer commits as it goes, so a failed
capture leaves its progress on disk.

### The classified model dissolves into the store `open`

The store is the model. For every field it states what fetches it, what arrives at it and what it
lands on, and the payloads, the candidates and the editor read those facts, so the walk's
classified records have no reader left and can go with the walk.

* **A write's re-read reads what was committed, for every write shape** `open`. An execution test
  pins the update, delete and service re-reads, as the routine and insert ones already are.
* **A producer's carrier is its one data channel** `open`. `graphitron_payload_channel` and
  `graphitron_carrier_data_channel` state each payload field's role and the one data field per
  producer, what stops a payload being a carrier is a defect at its site, and the carrier's
  readers move onto them while `graphitron_carrier_data_field` goes.
* **The matched key is stated** `open`. `graphitron_producer_matched_key` holds one row per `ONE`
  write and each key its input columns cover, keyed to the producer and `sql_constraint`.
* **A producer's consumed keys are stated** `open`. `graphitron_field_source_key` holds, per field
  taking keys from its parent's object, how it takes them and the table they match, and the
  fetcher's DataLoader is generated from it.
  * **A table with no record class says so** `open`. `sql_table.record_class_fqn` is NULL where jOOQ
    generated no record class, so the `RECORD` way of taking keys names only real records.
* **A table child under a handed value deeper than its carrier refetches** `open`. A table-bound
  field below a value a producer handed down, deeper than its carrier, fetches its rows itself.
* **A field's source is its parent's result** `open`. A field's source is a join on its parent
  type's `graphitron_result` row, nothing at a root, and its readers read that join where they read
  `graphitron_field_source_table`'s inherited rule today.
* **A consumed key is a source** `blocked`, on "A producer's consumed keys are stated". Under an
  object parent a path starts at the consumed key's table, and `graphitron_field_source_table`
  dissolves into `graphitron_result_table` with its interim record and source-row rules.
* **The candidates are one family** `open`. What a coordinate could name, from the argMapping,
  node id, `@referenceFor` participant and catalog-name askers, is one rule view read by the
  rejection messages, the language server's completions and the MCP server.
  * **`graphitron_node_id_candidate` takes the shape** `blocked`, on "The target type of a `@nodeId` coordinate is the entry's", until the two are merged.
* **The claims go** `open`. `BindingUsages`, `SchemaQueries`, `DiagnosticFacets` and
  `AuthoredClaimConflicts` read the producers, the target and its column instead, and
  `intent_authored_field_claim`, `intent_column_match_claim` and `intent_resolved_field_claim`
  dissolve.
* **Go-to-definition reads the resolved binding** `open`. `DeclarationFacts`, `BoundTables` and
  `TypeMemberScope` read `graphitron_resolved_type_binding` or `graphitron_result_table` instead of
  `intent_bound_table`, and `EditorReadsNoViewTest` takes another control.
* **The directive-argument hover reads a stored relation** `open`. `FieldColumnTable`, behind the
  completion, hover and member diagnostic at a `@field(name:)` or `@defaultOrder` site, reads a
  relation a capture writes, and `intent_field_column_table` dissolves. Evaluated on demand it
  takes about 60 s per graph on sis, 2026-10-23, on trunk and after the column scope went alike.

### Validate is a query over the store, and runs against the model alone `open`

`validate` opens the store, captures, reads its verdict from the store and closes it, naming
nothing in the `graphitron` module. A verdict is a narrower read than an emission, so this flips
before `generate` does.

* **A refusal of what capture injected reaches the author** `open`. A declaration the mint or the
  configuration injected that the emitted schema's assembly refuses is recorded where the written
  schema's refusals are, and the log names it.
* **Each rule the walk enforces is a located defect** `open`. Every walk rejection becomes a code
  and a branch of `graphitron_entry_defect_rule`, and the walk's hand-built validation test for it
  becomes a fact document or goes with its rule. The `errorGuard` rules are writable now, over
  `graphitron_errors_field` and `graphitron_producer_outcome`.
  * **A select's condition, paging and ordering are keyed to its producer** `open`. The
    modifiers are stated over `graphitron_producer`, so the operation rules are written over them
    once.
  * **A scalar constant says why it has no input type** `open`. `code_scalar_constant` tells an
    erased coercing apart from the other silences, so the erased-coercing rule reads the store.
* **The generator reads an `id: ID` without `@nodeId` as a column** `open`. The generator refuses
  such a field where no column matches, as the store does, instead of reading it as the node's id,
  unless its own type implements `Node`, where it is the node's identity, nesting types included.
* **`validate` reads its verdict from the store** `blocked`, on "Each rule the walk enforces is a located defect", "The generator reads an `id: ID` without `@nodeId` as a column" and "Entry defects fail validate". Node-id decode coverage is restated over store facts or goes, and
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
  * **A generating harness captures the run's output coordinates** `done`.
  * **The output package is read from the store** `done`.
  * **The tenant column and the session hook signature are captured** `blocked`, on "An argument's and an input field's target is modelled first, as tables" and "The generator builds no schema".
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
* **The error-mapping constant is named by its outcome type, and the dedup goes** `done`.
* **Routine writes read only the store** `blocked`, on "The tenant column and the session hook signature are captured". The write's tenancy is read from the tenant table that node captures, not decided a second
  way, so `RoutineWriteEmitter` takes nothing from `GraphitronSchema`, and its inherited and
  argument-bound routings, which a rejected tenant-scoped write never reaches, go.
* **Operation members and delivery are read from their relations alone** `open`. The readers of
  `OperationMembers.membersOf` and `DeliveryFact.leafDerivedOf` move to the relations,
  `LauncherCommands`' two productions merge, and the crosswalk goes.
* **No emitter reads a leaf** `blocked`, on "`TypeFetcherGenerator` reads the store, one family of field at a time". `CommandSeamRatchetTest` counts no leaf `instanceof`, case pattern or `plan`
  reference left in the generator.

### Each emitter reads the store `open`

The generator turns over one emitter at a time, each held to behaviour by tests that run the
generated classes against PostgreSQL from the fact scenarios. The fixed runtime classes and the
output package they are written to are settled.

* **The schema package's fixed classes are written without a plan** `done`.
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
* **The node and entity generators read the store** `open`. `NodeIdEncoderClassGenerator`,
  `EntityFetcherDispatchClassGenerator` and `QueryNodeFetcherClassGenerator` read node types and
  keys from `graphitron_node` instead of `GraphitronSchema`.
* **The facade, dev executor, registrations and schema class read the store** `open`.
  `GraphitronFacadeGenerator`, `GraphitronDevExecutorGenerator`, `FetcherRegistrationsEmitter` and
  `GraphitronSchemaClassGenerator` take roots, nodes, scalars, error types and entities from the
  emitted schema and the store, once the facts below exist.
  * **An enum's runtime value is stated by coordinate** `open`.
  * **A scalar's resolution is stated** `open`.
  * **A discriminated interface's type resolver is stated** `open`.
  * **The `@error` handlers are stated** `open`.
  * **Entity resolution is stated** `open`.
  * **Each field's wiring is stated** `open`. The cases `FetcherEmitter.bind` switches on, read with
    the families of "`TypeFetcherGenerator` reads the store, one family of field at a time".
* **The type and input generators read the emitted schema and the store** `open`. The enum, input
  and object type generators, `InputRecordGenerator` and `ConnectionFetcherClassGenerator` stop
  taking the walk's types, and the variants stop carrying forms nothing renders.
  * **An absent emitted form is an invariant failure** `open`. `CarriesObjectForm.formOf`, the enum
    value source and the schema class's scalar form fail rather than fall back, ahead of the variants
    losing their forms.
* **`TypeFetcherGenerator` reads the store, one family of field at a time** `open`. Root queries,
  services, DML writes, routine writes, projected child fields, batched fetches, polymorphic
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
  * **A stage's time includes its reads** `done`. A stage that fetches a rule into Java and inserts
    the rows spent its time in the read, where no one looked: one such rule went from 7.7 to 37.9 s
    on a large schema unseen. `store_graph_read` records, per capture, each relation a gatherer
    read for a millisecond or more, beside `store_graph_write`, and MCP `status`, the `graphitron:dev`
    line and the backstop rank both.
  * **The entry defect rule reads one graph's rows** `open`, unowned. After the chain-break fix the
    whole rule filtered to one graph takes 54 ms with one graph in the store and 98 ms with eight;
    what still grows is its two column-match parts, 2 to 32 ms, which read
    `graphitron_field_target_column_match`. Inside the backstop's slack, so not on its known list.
  * **The carrier data fields and the element declarations do not grow with the graphs in the
    store** `open`, unowned. `graphitron_carrier_data_field` 10 to 59 ms and
    `graphql_ast_element_declaration` 12 to 57 ms from one graph to eight at 20 units; both pass
    the backstop's slack.
  * **An upsert finds the row it updates through one key** `done`.
  * **An upsert into a table with an independent unique key is decided per table** `open`.
    `graphitron_federation_definition` is unique by local name, `graphitron_field_participant_path`
    by where the element was written, and `graphitron_ast_reference_element_entry` and
    `graphitron_ast_error_handler_entry` by the application's position and ordinal, none derived
    from the primary key, so naming the primary key turns a clash on the unique key from an update
    into a capture failure. `UpsertKeyGateTest` lists them.
* **A stage's statement reads tables, not a chain of views** `open`. A gate holds the cost of
  preparing each stage's statement, since on a fresh store most of a capture's time goes to H2
  expanding views rather than executing them.
* **A dev session's store is compacted when it ends** `open`. Compaction at close runs before H2's
  own shutdown hook closes the database, so a long session's store does not keep growing.

### A capture reads only what changed `open`

A round re-reads, re-anchors and re-derives only what an edit can have moved, so a dev round costs
what the edit touched rather than what the project holds. Deletion rides on the same grain: a
class, a document or an entry that goes takes what hung off it by cascade. What a sis round does
today is in `roadmap/audits/2026-10-19-where-a-dev-round-spends-its-time-on-sis.md`.

* **A schema edit re-anchors only what the edited document feeds** `blocked`, on the children below. The graphql family's only inputs are the documents and the bundled ones, so its rows are kept by key: the foreign keys say what a change reaches, and a round with no change reaches nothing.
  * **A changed document's entries are written as a diff** `open`. The entry write compares a changed source's rows with the stored ones under its key and reports which rows came, went and changed, instead of upserting every row and sweeping by instant; a dropped source is the diff in which every row went.
  * **The anchors re-derive only the coordinates a change reaches** `blocked`, on "A changed document's entries are written as a diff". The closure follows the foreign keys from the diff before anything is deleted, and the cascade then unwinds what was derived from the gone rows; an anchor, which may hold for other sources, references what built it ON DELETE SET NULL through its own nullable copy of the site key, graph column included, held equal to the identity columns by a check, as `source_ref` beside `source_name` does on the `graphql_ast_` declaration entries, so the null touches the copy and never the key; it re-derives the coordinates the closure touched, takes a source whose modification time moved as touching its own, and ends the closure where its row comes out the same.
  * **An edit round costs what the edited document reaches** `blocked`, on "The anchors re-derive only the coordinates a change reaches". A test edits one document of several and asserts the other documents' rows keep their earlier reading's instant, and a sis edit round is timed beside a no-op one.
* **A classpath change reruns only the gatherers that read the classpath** `open`. A round that
  succeeds says which gatherers it ran and which it skipped.
* **A document that does not parse ends the round at the parse** `done`.
* **Every `code_` row hangs off a class** `open`. `code_throwable` and `code_throwable_supertype`
  key into `code_class` rather than the entry, so a class that goes takes them with it.
* **A single changed class re-reads only itself, swept at the class file** `blocked`, on "Every `code_` row hangs off a class" and "`code_type` and `code_type_element` are gone". `CodeCapture`
  sweeps once, at `store_class_file`, and reads `byte_size` and `mtime` to skip an unchanged class.
* **A warm store is refreshed in a test** `open`. `WarmStartRefreshTest` gains a case capturing a
  different schema on the second capture and asserting the store states the second, so sweeping
  and re-anchoring are exercised somewhere other than against an empty store.

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

* **The node is settled from the position-keyed entries, in one place** `blocked`, on "The table-binding and node subject converts". `TableTypes`, `Nodes` and `NodeKeyColumns` read
  `graphitron_ast_table_entry`, `graphitron_ast_node_entry` and
  `graphitron_ast_node_keycolumn_entry`, and `NodeKeyColumns` runs in the anchor beside `Nodes`.
* **Table types, nodes and node key columns are marked and swept** `blocked`, on "The node is settled from the position-keyed entries, in one place". An unchanged binding keeps its node and
  key columns rather than losing them to a clear and refill.
* **A node is what the manual says takes effect** `open`.
  `graphitron_node_type` goes, its readers read `graphitron_node`, and
  `NodeDeclaration.isNodeType` moves to the same predicate in the same commit.
* **The editor and the MCP read the resolved node** `open`. Completion of `typeName:`, its
  squiggle and `SchemaQueries` read `graphitron_node` and `graphitron_node_keycolumn`, as the hover
  already does.
* **The coordinate copies go** `blocked`, on "The node is settled from the position-keyed entries, in one place" and "The editor and the MCP read the resolved node". `graphitron_node_entry`,
  `graphitron_node_keycolumn_entry` and their writer in `GraphitronAnchor` are deleted.

### A node id is a target type, read as its ID `open`

A `@nodeId` field, argument or input field has a target type like any other, the node type, and
says only that the value is that type's ID; the route to it is the ordinary forward walk. Today
`graphitron_node_id_instruction` blends what was written with what was inferred and the node-id
relations keep a private model of the route; both are replaced from scratch, reading nothing from
`intent_`.

* **The model comes first, as tables** `open`. The new node-id relations are declared with keys,
  foreign keys and grain sentences before anything writes them.
* **The corpus is written against the model** `open`. Fact documents state what each schema gives
  the new relations at a node type, an interface and a union, for every written and inferred form.
* **The target type of a `@nodeId` coordinate is the entry's** `blocked`, on
  "`graphitron_node_id_candidate` takes the shape", until the two are merged. The entry carries the type
  the id names and the rung it came from (`WRITTEN`, `IMPLIED`, `BY_NAME`, `RETURN_TYPE`,
  `TABLE_NODE`), and a shape the manual refuses is a defect row, never a capture failure.
* **An input field's use site is a graphitron_ relation** `open`. A definition joined to the
  consumer that uses it is modelled in the store rather than read from an `intent_` view.
* **The chain reaches the target type** `blocked`, on "Arguments and input fields walk from their field's target". A `@nodeId` coordinate's forward walk ends at a table checked against its target type,
  or, when bare, the table its `TABLE_NODE` type is read from.
* **Encode and decode read the target type, and the old node-id relations go** `blocked`, on "The chain reaches the target type". The generator, filter and carrier roles read the new relations,
  and `graphitron_node_id_instruction` and the `graphitron_node_id_decode_*` relations are deleted.

### A polymorphic id resolves per member, to the overload that accepts it `open`

A union's members are unrelated results, so the decode switches on the id's type and hands each
member's record to the overload that accepts it, `assign(CustomerRecord)` beside
`assign(StaffRecord)`. The store stops modelling this as general subtyping over captured record
supertypes, which only an unsupported jOOQ option would make meaningful.

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

* **The code-reference subject converts** `open`. The classes seeding code references through
  `seedService`, `seedFieldCondition` and their siblings become documents, each corpus gap added
  to the sakila catalog or service module first.
* **The table-binding and node subject converts** `open`. The classes seeding `@table` and `@node`
  through `seedTableBinding` and `seedNode` become documents.
* **Every other seeded class converts by its subject** `open`. Fields, mutations, input fields and
  arguments convert whole, a subject listing its corpus gaps once before its first conversion.
* **`SeededStore` is deleted** `blocked`, on "The code-reference subject converts", "The table-binding and node subject converts" and "Every other seeded class converts by its subject". The fixture goes, and `SeedingDissolutionGateTest` goes with them.

### What the converted seeded cases turned up is fixed `open`

Stating the seeded cases as documents put real schemas in front of rules that had only met
hand-written rows, and six of those rules answered wrongly, threw, or never fired. Each is fixed
here with a document that shows it.

* **A chain of input types costs its occurrences, not its paths** `open`.
  `InputOccurrencePaths.copyParentSteps` enumerates every path through an input type chain, so
  twenty levels of two fields each is a million paths and a capture that ran past fourteen minutes.
  A document holding that chain stands guard.
* **The claim views keep only the presence branches capture reaches** `open`. The decodes of
  `@service`, `@externalField`, `@nodeId`, `@table` and `@error` always write an entry, so the
  branches of `intent_authored_field_claim` and `intent_authored_type_claim` reading a raw
  application with no entry beside it never return a row, and they go. `@routine` and `@mutation`
  decline what they cannot read, so theirs stay.
* **What the manual refuses of a `@nodeId` is a defect row** `blocked`, on "The target type of a `@nodeId` coordinate is the entry's". The generator refuses these and the store records nothing:
  a bare `@nodeId` on a field of a type that is not a node, a `typeName:` naming a type without
  `@node`, arguments of a node-returning field matched to columns its table lacks, a `typeName:`
  with no route from the slot's table, and a bare `@nodeId` on an input field under a multi-table
  field whose members disagree. A bare `@nodeId` argument names its field's return type, so
  `films(filmId: ID @nodeId): [Film]` is a `Film` id even where a second node type shares `film`;
  today the store resolves nothing there and the generator refuses it as ambiguous. A document
  `node-id-instruction-unresolved` under `defects` states all of these.
* **A `@nodeId` argument walking its own path decodes the type it arrives at** `blocked`, on
  "Encode and decode read the target type, and the old node-id relations go". On the field below
  the store resolves `Actor` from the table the path arrives at, while the generator infers `Film`
  from the field's table and emits `actor.FILM_ID`, which does not compile. The generator never
  supported the shape and is not patched for it: reading the store's target type is the fix. When
  it lands, the fragment goes into the scenario `node-id-instruction-bases`, which already declares
  `Film` and `Actor` as nodes: the field into its `extend type Query`, the row into its
  `graphitron_node_id_instruction` expectation.

  ```graphql
  extend type Query {
    filmsByActor(byActor: ID @nodeId @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])): [Film]
  }

  extend schema @expectEquals(relation: "graphitron_node_id_instruction", rows: """
    site,     use_site,                    basis,                  resolved_type_name
    ARGUMENT, Query.filmsByActor(byActor), TARGET_TABLE_NODE_TYPE, Actor
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
* **The shadow tests go with their walk side** `blocked`, on "A node is what the manual says takes effect" and "The generator builds no schema". `RoutineSpentInputShadowTest`,
  `ColumnMatchShadowTest`, `InputOccurrenceShadowTest` and `NodeTypeShadowTest` are deleted.

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
  rewritten from R333 rather than copied, landing with or after R983's vocabulary.
* **A view's column prose lives where the store keeps it** `open`. H2 2.5 refuses `COMMENT ON
  COLUMN` on a view, and 2.4 already dropped those comments when a file store was reopened, so the
  1202 are SQL comments in `graphitron-model.sql` for now. They move to the view's own comment, to
  a `meta_view_column` relation, or go for a rule view whose stored table states the same columns;
  `FactSchemaGateTest`, `CommentRenderabilityGateTest` and the schema reference pages widen back.

### The build cannot hang or fail on its own fixtures `open`

A build that hangs or fails for reasons no change caused costs every session a rerun and makes a
real failure look like noise. The LSP test JVM crash seen once is in the two
`roadmap/audits/2026-10-21-lsp-test-jvm-sigsegv-*` audits, for a recurrence to be read against.

* **Class initialization cannot deadlock in javapoet** `open`. `TypeName`'s initializer builds no
  `ClassName`, and a test initializing the two from opposite ends on two threads pins it.
* **A refused compaction does not depend on luck** `done`.

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
* **Changed: the fact store runs on H2 2.5.252** ("the fact store runs on H2 2.5.252, which fixed
  its compaction race"). The plugin and the model bring H2 2.5.252 in place of 2.4.240. A store an
  earlier build wrote still opens, the file format being unchanged, and compacting it on close is
  no longer refused intermittently in a JVM running with assertions on.

## Retired vocabulary

For the retirement sweep at the Done gate. Determined by diffing the schema's relation names across
the item's life. Already swept once, with seven survivors found and fixed.

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

**One survivor flagged rather than corrected.** `authored-connection-type-scope-silence` rests its
premise on `graphitron_field_synthesis.authored_type_sdl` and on `intent_field_scope_table` reading
it. The column is gone and that view reads neither relation now. A dated note says so in its body;
whether the defect it reports still exists is its own author's to re-measure.

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
