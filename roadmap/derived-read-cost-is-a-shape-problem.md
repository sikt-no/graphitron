---
id: R876
title: "Expensive derived reads are a modelling defect: every rule needs an owner, and once ownership is computed the derivation gatherer is unearned and meta_materialize has no subject"
status: In Progress
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-28
last-updated: 2026-10-15
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

This item is the trial of R979, which proposes that a spec be a graph the work grows rather than a
phase list written before any of it is tried. The conversion is deliberate and R979 is still Backlog.

The goal is the root. A child is a prerequisite discovered by trying. A leaf is doable now; a branch
waits on its children. Each node is `done`, `blocked` or `open`, and a blocked node names what holds
it. A node is a claim and at most a sentence of why: evidence lives in `roadmap/audits/`, the story
in `changelog.md` at Done.

**What the conversion found on its first pass, recorded because it is the point of trying.** The root
this item states, every rule gets an owner and the register dissolves, is `done` and has been since
R955. Five arcs have landed under this number since, and none of them serves that root. They serve
the thesis in the title, which the phase list never made the root: a read is expensive because the
shape is wrong, so fix the shape. The graph is written that way below, with the register dissolution
as the first branch rather than the whole of it. A phase list could hold a finished goal and a
growing plan without the contradiction showing.

**Root: an expensive derived read is a modelling defect, so fix the shape rather than the cost.**

**Flips in flight.** Every add whose flip or subtract has not landed, one line each: what runs twice
meanwhile, and the nodes that end it. **No node adds in an area with an open subtract here unless it
is that subtract's prerequisite, and a node that adds says which line it retires.**

* Entry twins: 15 `graphitron_ast_*_entry` beside the coordinate copies, both written every capture,
  the twins read by nobody. Ends at "The twinned ones flip and subtract".
* Two resolutions of an authored element: `graphitron_field_reference_step_hop` and
  `graphitron_field_path_reading`, both every capture, and beside them since 2026-10-20 what
  replaces them, `graphitron_path_element_key`, `_table` and `_method`, so three run until the
  forward walk reads the new ones and retires the two. Ends at "One resolution of an authored
  element stands, not two" and "The backward walk leaves nothing behind".
* Read-time path views: `intent_field_path_start`, `_node` and `_terminus`, evaluated by every
  reader. Ends at "The read-time path relations are gone".
* The classified model beside the store: `generate` builds `GraphitronSchema`. Ends at "Each
  emitter reads the store" and "The generator builds no schema".
* The emitted schema twice: `EmittedRegistry` in capture, `ConnectionPromoter` in generate, pinned by
  `EmittedRegistryAgreementTest`. Ends at "The generator builds no schema".
* Operation members and delivery twice (R563): the relations and the leaf crosswalk, every generate.
  Ends at "Operation members and delivery are read from their relations alone".
* Command rows beside leaves (R549, R668, R704): `RoutineWriteCommands` and `LauncherCommands` read
  both. Ends at "Routine writes read only the store", "The launcher's routine row reads the facts R704
  captured" and "No emitter reads a leaf".
* Node-id decode: the instruction and decode relations beside the target type. Ends at "Flip: encode
  and decode read the target type and the forwarded key" and "Subtract the node-id instruction and
  decode relations".
* The type dictionary: `code_type` and `code_type_element`. Ends at "`code_type` and
  `code_type_element` are gone".
* Clear-and-recompute steps in `DerivationStratum`. Ends at "The class and its order gate are gone".
* Producers twice: `graphitron_producer` beside `intent_field_payload_producer`, both every capture.
  Ends at "Subtract what the carrier replaces".
* Nesting twice: `graphitron_result_table` beside the `INHERITED` arm of
  `graphitron_field_source_table`. Ends at "A field's source is its parent's result", which joins
  `graphitron_result` instead.
* The per-member fit for a polymorphic id. Ends at "The per-member fit goes".
* Walk and store shadows, both sides in production: `RoutineDirectiveResolver.bindArgs` and
  `graphitron_argmapping_match` (R950), the column match, the input occurrences, and
  `NodeDeclaration.isNodeType` and `graphitron_node`. Ends at "The shadow tests go with their walk
  side".
* The `RECORD_CLASS` and `SOURCE_ROW` interim in `graphitron_field_source_table`. Ends at "A consumed
  key is a source".

### Every rule has an owner, and the register has no subject `open`

A rule with no owner is what a registration compensates for, so ownership leaves the register with
nothing to schedule. Reached by R955: `meta_materialize`, every registration and every `_live` view
are gone.

* The entry migration `done`
* The two hierarchies, one mechanism at two grains `done`
* The macro arc's consumer, the first this arc retired `done`
* The route family `done`
* The reference decode on the field sites `done`
* The argument and input-field sides `done`
* The register itself `done`, by R955, because the gatherer it compensated for stopped existing
* The materialization targets `done` by being moot: the subject was the unkeyed relations, and the
  six stored `intent_` tables that remain are all keyed
* **The declaration pass** `open`. A relation with no `meta_relation` row has been made nobody's, and
  writing one forces a grain to be named. Not a pass that waits its turn but a precondition on every
  move, because a relation whose owner is undeclared is cleared by whatever clear still stands and,
  once its old writer is gone, is not written back. The mechanism is R877's. The declaration is a
  build-time gate and not a runtime one: nothing that runs reads `meta_relation`, and what the gate
  buys is that a new relation cannot arrive without an owner. `MetaDeclarationGateTest` holds the
  ratchet: `undeclared-relations.txt` lists 118 relations on 2026-10-17, and this node is done when
  it is empty.

### A fact is keyed by what it is, not by where it was written

* Directive applications collapse onto the coordinate `done`. Ten relations became two; the five
  sites keyed one fact five ways because each carried its own decomposed key and none carried the
  coordinate.
* `graphql_element` is a table a foreign key can name `done`. A supertype is a table carrying a
  primary key its subtypes reference; a union standing in for one buys the vocabulary and none of the
  integrity.
* The schema block has a coordinate `done`. `$schema` is ours rather than the specification's, and a
  dollar sign is illegal in a GraphQL name, so it cannot collide with a type an author declares.
* The enclosing declaration is a fact `done`. `graphql_ast_element_declaration` walks the parent chain
  once, where the ordering it supplies was about to be a join per site.
* The specification is a document `done`. A built-in with no entry needed a patch for every authored
  form that touched one; bundled as SDL that sorts after every file, it is a declaration the ranks
  already know how to lose, and an `extend scalar String` merges onto it like any other extension.
  * A fact document states an absence and a reading at a stated instant `done`. A redeclaration
    that wins is a row that must not be there, and what a later reading kept is a row still carrying
    an earlier reading's mark.
  * **A redeclared `@skip` or `@include` loses to the specification, and says so** `open`. The
    oldest declaration wins a directive's name, which matches the schema graphql-java builds for
    every built-in but two: it keeps an authored `@deprecated`, `@oneOf` or `@specifiedBy`, and
    discards a redeclared `@skip` or `@include` for its own, which its executor implements, without
    refusing it, so no `graphql_schema_problem` row is written. `redeclared-built-in-directive`
    pins the authored `@include(if: Boolean) on FIELD` as the one holding the name, where the
    schema holds `@include(if: Boolean!)` on `FIELD`, `FRAGMENT_SPREAD` and `INLINE_FRAGMENT`, so
    the store answers where `@include` may go with what graphql-java contradicts. The anchor takes
    the specification's declaration for those two whatever a document says, a redeclaration that
    is discarded is a defect at its own position, as a `type Query` lost to the prelude is, and the
    document's `@include` rows assert the built-in at every instant.
* **An application is identified by where its `@` was written** `blocked`, on the three below. The
  ordinal is matched against numberings other writers compute apart, and agrees only because a field
  is declared once; keyed by position, the anchor is a projection, and the enclosing-declaration walk
  and the position index on `graphql_type_declaration` lose their only reader;
  `roadmap/audits/2026-10-11-directive-application-anchor.md`.
  * **A routine's argMapping finds its site by position** `blocked`, on the two below. Matching
    there and in `intent_argmapping_bound_parameter_type` is a column swap, but the site's ordinal
    flows on through `graphitron_argmapping_match` into the projection relations and their defects.
    * **The argMapping projections carry the application's position** `open`.
      `graphitron_argmapping_match`, `intent_argmapping_key_column_candidate`,
      `intent_argmapping_projection_defect` and `intent_resolved_node_key_projection` pass the
      ordinal on; three of the four are `intent_` and dissolving.
    * **A use site is identified by position** `blocked`, on "No `graphitron_*_entry` survives". A
      routine's use site is spelled `Type.field#ordinal`, by the decode from its own count and by
      `ArgmappingProjectionDefects` from the anchor's, and that spelling keys
      `graphitron_method_reference_entry` and the node-id relations.
  * **The path stops matching a step to its application by ordinal** `blocked`, on "The read-time
    path relations are gone": the match is in `intent_field_path_node`, which retires with
    `graphitron_field_path_application`.
  * **The emitted applications number their own** `open`. A configured or minted application has
    no position, so `graphitron_directive_application` keeps an ordinal, and its authored arm ranks
    by position rather than copying one.

### The classpath family affords what its readers ask `done`

* `code_` reads the classpath it is allowed to read `done`
* `code_` holds every fact a reader of the census read `done`
* The census is gone `done`. Six relations, their gatherer, their roster rows and their fixtures, and
  with them the gatherer ordering constraint that existed only to keep a `code_` arm from reading a
  `jvm_` row.

### A resolution keeps what a diagnostic needs `open`

* The chain resolution is a walk in the catalog `done`, where it was a loop in Java invisible to every
  instrument that parses a rule body.
* The resolution keeps its losers `done`. A link with no reading is a chain that does not resolve and
  one with several resolves ambiguously, and neither was a fact anybody could read.
* **The defects reach the surface** `open`, waits on the second child below. They are stored, because
  the read that matters is the editor's and a per-file predicate cannot push into a recursion behind
  a view boundary. Storing them did not reach the goal: the resolution they read was a view, so the
  stage re-walked every chain once per chain it drove and on a consumer-size schema did not finish.
  Evidence in `roadmap/audits/2026-09-28-chain-resolution-storage.md`.
  * **The resolution is stored once, at the reading grain** `done`. Three arm tables by key shape
    under the existing name, filled from one walk; their hop-relation shape is this branch's vote in
    the survivor question below.
  * **The rule is examined, not only its storage** `open`. `broken` drives a correlated `NOT EXISTS`
    over `routes`; restate it as an anti-join and record the figure beside the storage ones.
  * **The reading's KEY arm is an equi-join** `done`. Three disjoint arms, qualified, SQL name and
    jOOQ name, in place of a three-way `OR`, with the same split in
    `FieldReferenceStepHops.namedKey`; not yet re-timed on sis.
  * **The reading is stored, so the walk reads it once** `open`. The resolution rule names
    `graphitron_field_path_reading` four times, each walk's seed and step, and the entry
    defect rule a fifth, and H2 evaluates a view at every naming. Storing the reading alone took a
    reader of the walk from 30 s to 0.08 s in the storage audit, so the stage's cost is the
    reading's evaluations and not the walk. Convert it the cheap way: the view keeps its text as
    `graphitron_field_path_reading_rule`, a table takes the name both readers spell, and a
    clear-and-insert stage fills it ahead of `FieldPathResolutions`. Two readers, so the write
    is earned; time the stage on the sis store before and after, the KEY arm change above first.
  * **The defects agree with the generator on a consumer schema** `open`. A defect on a schema the
    generator accepts is a regression, and the editor shows each one to the author as an error. On
    the 2026-10-14 stores they are 147 coordinates on sis and 34 on sakila, every code but the
    source's and the target's at zero on sis. Each child is a reading that is wrong rather than a
    rule that is, and evidence is in
    `roadmap/audits/2026-10-01-sis-entry-defect-false-positives.md`.
    * **The reactor's own example is the check** `open`. The sakila example reproduces it in tree:
      made build errors, the defects report 44 coordinates it generates, and fail
      `MethodClosureOracleTest`, `IncrementalCompileHarnessTest` and five corpus fragments with it.
      This node is done when that example draws no error-severity entry defect, which a build can
      check where sis cannot. Evidence in
      `roadmap/audits/2026-10-02-sakila-entry-defect-false-positives.md`.
    * **A chain reaches its field's target** `done`. The eight sakila coordinates reporting
      `NO_ROUTE_TO_TARGET` report nothing on the 2026-10-14 stores, nor does sis; which change
      cleared them was not traced.
    * **A source is inherited through a type with no table of its own** `done`, by
      `graphitron_field_source_table` under "The source is stated", recursive through types with no
      table; `NO_ROUTE_FROM_DEPARTURE` is 0 on sis and sakila.
    * **A scalar `@reference` has a target** `done`, all 48 on sis and 14 on sakila gone.
      `CHAIN_WITHOUT_TARGET` reads `graphitron_field_target` and `graphitron_field_target_table`,
      judging only a target that could bind a table at a field with no `@routine` and no `@pivot`;
      cases in `chain-without-target`.
    * **A `@pivot` projection has a target** `done`, by the same rule, the six sakila coordinates
      (`Film.titleTranslations`, `Film.prices` and siblings) gone.
    * **A condition method over untyped tables resolves** `open`. Two sakila coordinates report
      `ELEMENT_UNRESOLVED`, `Customer.addressByCondition` and `Film.actorsByCondition`: a
      condition-only element whose method takes `Table<?>` names no table in its signature, so the
      reading has no route, where the generator calls it positionally between the source and the
      field's target.
    * **Overloads per participant are one reference** `open`. Two sakila coordinates report
      `CODE_REFERENCE_METHOD_AMBIGUOUS`, `Query.occupantsByTypedNamePrefix(firstName:)` and
      `Query.occupantsByMixedNamePrefix(firstName:)`: a `@condition` on an argument of a multitable
      field names one method with an overload per participant table, and javac selects one per
      branch, so the overloads are the reference's resolution rather than an ambiguity.
    * **A named self-referencing key reads one way** `done`. The reading keeps the hop the field's
      cardinality names, as the generator does: along the key on a single-valued field, against it
      on a list.
    * **A condition-only element resolves from its method** `done`. `CodeCapture` admits a
      `Field<Boolean>` return beside `Condition`; the manual still documents `Condition` alone,
      deliberately.

### The path replaces the walk it was built to retire

The field grain resolves where it was written and says why when it cannot, which is the node above.
What remains is the other two grains, a second resolution of the same elements still standing beside
this one, and the read-time relations neither of them has displaced yet.

`@reference` and `@referenceFor` share an element grammar and nothing else. Repeated `@reference`
applications concatenate, with `@routine`, into one path for a coordinate. Repeated
`@referenceFor` applications are independent, one per participant of a union- or interface-typed
coordinate, each its participant's complete path; a participant not named keeps auto-discovery. So
the model has one reading of an element, the table it joins from and the table it joins to, keyed by where it was
written whichever directive wrote it, and two groupings of elements with the grain the vocabulary
gives each: a coordinate's path keyed by the coordinate and a position, and a participant's path
keyed by the coordinate, the participant and a position. A target is read along its participant's
path where it has one, along the coordinate's path where the coordinate writes one, and is
auto-discovered otherwise, and that choice is made once rather than by each reader. Writing both
directives at one coordinate is an error in the vocabulary, so no relation mixes their elements
either. The same holds at all three grains, a participant's path at an argument or an input field
running from the participant's table toward a `@nodeId` target in key hops alone.

* **A coordinate's path and a participant's path are two relations** `done`, the field grain.
  `graphitron_field_path` holds `@routine` and `@reference`, `graphitron_field_participant_path` one
  element of one participant's path, both anchored and swept by the graphitron anchor; pinned by
  `field-path-and-participant-path`. Its reader is the walk under "A path is resolved by a
  deterministic forward walk"; until then the ten `@referenceFor` fields on sakila get no chain, so
  that walk may surface real defects.
* **Writing `@reference` and `@referenceFor` at one coordinate is a defect** `open`, as is a
  `@referenceFor` on a coordinate whose type is neither a union nor an interface. Both are rejections
  the vocabulary states; as codes they are where the editor learns of them.

* **A routine resolving to nothing says so** `open`. `CHAIN_WITHOUT_TARGET` declines that case on
  purpose, a `@reference`-only chain taking its target from the return binding where a routine chain
  takes it from the catalog, so the same silence means two different faults and only one has an arm.
* **The seat keeps its predicate and loses its vocabulary** `open`, waits on the arm above. Every
  production reader filters to `ADMITTED`, three sites in `RoutineWriteFacts`, so the thirteen
  refusals have no consumer and one moved to `graphitron_entry_defect` gains a reader rather than
  changing one; its `MutationRoutineSeatTest` case travels with it, being the only cover those
  thirteen have.
* **A path is resolved by a deterministic forward walk over what each element names** `open`. A
  field's source is its parent type's table and its target is its field type's table; its path is
  the join from source to target, and its arguments condition the target. Three shapes:
  ```
  SELECT … FROM T1                                   -- root: no source, the query starts on the target
  SELECT …, multiset(SELECT … FROM T2) FROM T1       -- child in an existing query: the source is T1
  SELECT …, multiset(…) FROM (VALUES …) T1           -- child in a new query: the source's keys as values
  ```
  The reading states every interpretation of every element, both directions of a key and every key
  into a named table, and two walks keep the candidates that line up. That is a second algorithm
  beside the generator's, which carries the table it stands at from element to element and reads
  each element once from there, and it is the cost: on sakila the rule takes 942 ms against 70 ms
  for one evaluation of the reading and 63 ms over a stored copy of it, the reading being expanded
  again in every round of each recursive walk. Instead an element names one thing, and the walk
  reads it from where it stands, one hop or a failure, starting from the field's source. The path
  walked toward a target type is that participant's own `@referenceFor` path where it has one, and
  the coordinate's `@reference` path otherwise; a coordinate writing both is a defect, so no walk
  mixes them.

  The walk's answer is the path itself: each row says where it comes from, where it goes and how
  it joins, column pairs with the key they came from or a join method, so no relation beside the
  path holds the resolution. The pattern is shared and the relations are not: an output field's
  path, a nested field's, a participant's, an argument's and an input field's each start somewhere
  different and are keyed differently, so each is its own relation and only the step from one
  table to the next is shared code. `roadmap/audits/2026-10-18-the-path-is-how-a-field-gets-from-source-to-target.md`
  holds the model and what it dissolves.

  There is one walk, from source to target. Read 2026-10-16: a walk from the target back toward the
  source answers nothing the forward walk does not. Each step has the same inputs either way, where
  the walk stands and what the element names, except that a backward step at a `table` element does
  not yet know the table before it and can only take the one key into the named table from
  anywhere, which ignores the source; so where both walks complete they agree, and where only the
  backward one does it has answered for a field that disregards its source. Nothing needs it for
  want of a source either. No field on sakila or in the seven fs-plattform subgraphs at
  `4d2f79280b` writes `@reference` or `@referenceFor` on a root field without a leading `@routine`,
  and the generator itself only walks forward, a `key` with no source riding from the table that
  declares it and a `table` with none refused. Every child field with a path has a source context;
  the ones `graphitron_field_source_table` misses are under "A consumed key is a source": a `TableRecord`-backed carrier (`FilmDetailsCarrier` on `FilmRecord`), a `@sourceRow`
  lift (`CustomerAddressSummary.address`), and a service result's `TableRecord` member
  (tilgangsstyring's `DeaktiverApplikasjonerPayload.applikasjoner`, a `List<SubjektRecord>`). A root
  field's argument or input field with a path is a filter and walks from the field's target.
  * **What an element names is stated at the entry grain** `done`. Designed with Alf 2026-10-20.
    Resolved from its spelling once, so spelling lives only in the entries, and in the author's
    terms, since a key is what the author named:
    ```sql
    graphitron_path_element_key    (element, constraint PK → sql_referential_constraint,
                                    element → graphitron_ast_reference_key_step_entry)
    graphitron_path_element_table  (element, table PK → sql_table,
                                    element → graphitron_ast_reference_table_step_entry)
    graphitron_path_element_method (element, method PK → code_condition_method,
                                    element → graphitron_ast_reference_condition_step_entry)
    ```
    Written by `PathElements` from a rule view each, marked and swept, at the bottom of the
    reference stratum; pinned by `path-element`. A key resolves as the generator's lookup does: its
    SQL name wherever one in the graph's catalogs answers, the generated Keys constant only where
    none does, and a written schema binding the schema of the table holding the key; a table by its
    name, qualified or not; a method by its class and name. One row per candidate, as the generator
    narrows a name two schemas share by the table its walk stands at, so the walk narrows here too
    (`note_event_fk` names both `multischema_a`'s and `multischema_b`'s). No direction, no source
    and no target, none of them being a fact about what was written, and no site: an element names
    the same thing at a field, an argument or an input field. Measured 2026-10-20 on sakila and the
    four Graphitron 10 subgraphs of fs-plattform: every key, table and condition spelling resolves
    to one candidate, none to several. opptak writes 88 of its 218 keys as schema-qualified jOOQ
    constants (`soknad_v2.SOKNADS_KVITTERING_ALTERNATIV__…_FK`), which the generator and now these
    relations resolve and today's reading, admitting a jOOQ constant only unqualified, does not.
    * **An element naming nothing is a defect at the element** `open`. A spelling with no candidate
      is a code at its position; one several candidates still answer where the walk stands is the
      step's.
    * **The routine reading is read at the element** `done`. A `@routine` element's reading joins
      `graphitron_ast_routine_entry` at its written position, one candidate only, with no target
      check; `graphitron_field_routine` and `FieldRoutines` went. Cases in `field-path-routine`.
  * **Each element kind has a step rule** `open`, as the walk's arms. Designed with Alf
    2026-10-20: a step is one join from the table the walk stands at, over what the element names
    and the catalog's stored relations, so it needs no relation of its own; a relation of every
    move an element could make is what `graphitron_field_reference_step_hop` was. Per kind, "this
    element, from this table":
    * a key: the named key's columns, read from the end the walk stands at to the other, paired by
      position from `sql_constraint_column` and the referenced constraint's columns;
    * a table: the one key between the table the walk stands at and the named one, its columns as
      above, failing on none or several; from a function's result, the named table's key matched
      by column name, which `sql_name_matched_key_column` pairs already;
    * a condition alone: the method, its first parameter admitting the table the walk stands at
      and its second naming where it lands;
    * a condition beside a key or a table: a filter on that step.

    Every site walks the same arms; they differ only in where the walk starts. Today an argument
    steps over `intent_argument_reference_step_hop` and a field and an input field over
    `graphitron_field_reference_step_hop`, which both go.
    * **A self-referencing key is oriented by R1037's rule** `open`, R1037's to do. Both ends of the key are one table, so
      where the walk stands cannot say which way it rides; the way it rides is which of its
      columns is the step's `from_column`. It rides forward unless the element writes
      `inverse: true`; until R1037 lands the step takes the field's cardinality as the generator
      does, and R1037 changes both, with the `@reference` reference page. Nothing in R876
      waits on it; `roadmap/audits/2026-10-14-reference-keys-in-consumer-schemas.md`.
  * **A path carries where each row comes from** `open`, after the step rules and "A field's source
    is its parent's result". Designed with Alf 2026-10-19 and 2026-10-20. Two relations for an output
    field, written by the walk rather than by `GraphitronAnchor`:
    * `graphitron_field_path`, keyed by the field, the table it starts from and the position, the
      start a reference into `graphitron_result_table` for the field's parent type, carrying that
      relation's whole key, the type and the table's source, schema and name: a field is
      walked once per table its parent's row is a row of. A parent bound to a table has one; a
      nested type reached from several tables has one each, so `OccupantLocation.occupantDistrict`
      is two walks, from `customer` and from `store`, the per-owner projection the generator emits,
      and two fields nesting the type on one table share one walk. One relation and not two,
      because a nested field and a bound one start from the same fact keyed the same way.
    * `graphitron_routine_field_path`, keyed by the field and the position, the field a reference
      into `graphitron_producer_routine`: the path of a root field led by `@routine`. Its start is
      the routine's table, a table-valued function being a `FUNCTION` row of `sql_table` that the
      producer's routine shares its key with, so the routine is where the query starts and not a
      step: the rows are the elements written after it, at their written positions, the first
      leaving the function's table. A routine with nothing after it has no rows, its field's type
      being bound to the function's table itself, or a payload whose data field refetches as a
      `LOOKUP`: on sakila the eight mutation routines returning a payload write the routine alone,
      and the six returning a table type (`Mutation.rentFilm` to `Rental`, `rentFilmCustomer` on to
      `Customer`) walk on from the function's table.

    Both are written after `Fetchers`, which writes the results and the routine producers they
    reference. The reference into the result holds across captures, a result being marked and swept
    and keeping its key while a reading still reaches it.

    A path speaks columns, ruled with Alf 2026-10-20: a join is column pairs, and the key it rode is
    where they came from. Each row says where it comes from and where it goes, `from_table` and
    `to_table`, never null, and how it joins, in children keyed by the row:
    ```sql
    graphitron_field_path_column (…, position, ordinal PK, from_column → sql_column,
                                  to_column → sql_column)              -- the join's column pairs
    graphitron_field_path_key    (…, position PK, constraint → sql_referential_constraint)
                                                                        -- the key the columns came from
    graphitron_field_path_method (…, position PK, method → code_condition_method)
    ```
    A foreign key ridden either way and a function's result matched by name are both column pairs,
    so the path has one join form and no direction flag; a step with no column rows joins by its
    method, and a method beside column rows is a filter on them, read from the rows rather than
    stated twice. The key row is there wherever the step rode one, written, inferred from a table or
    discovered where nothing was written, so the editor and the messages can name it; whether the
    author wrote it is whether the element has a `graphitron_path_element_key` row. A match by name
    has no key. The routine path has the same children.

    A row is a step the walk took, ruled with Alf 2026-10-21: an element the walk cannot step
    through has no row, a row with nothing past its `from_table` saying nothing the row before it
    did not. So a broken path is the first written element with no row, read from the entries, and
    the elements after it have none either. Where nothing is written the discovered step is a row.
    A field that wrote a path with nowhere to start, a root not led by `@routine` or an object
    parent no consumed key starts from, has no walk at all. The walk reads the written order from
    the entries, so `GraphitronAnchor`'s `graphitron_field_path`, the chain as written keyed by the
    field before anything resolves, goes and the walk takes its name. Its readers split: those
    asking of the field, whether it wrote a path (`graphitron_field_target_column_match`) and how
    many elements (`FieldInformation`), read the entries; those asking of a path, where it broke and
    whether a written path has a walk (`graphitron_entry_defect_rule`), read the walk beside the
    entries.

    The walk exists in part today:
    `FieldReferenceStepTargets` walks forward over `ReferenceStepWalk` from the parent type's own
    table only, so on sakila it misses 38 fields with a path, read 2026-10-16: the 21 led by
    `@routine`, the fields under types with no table of their own (`FilmDetails`, `FilmInfo`,
    `FilmInlineBundle`, `FilmProjectedLeaves`, `HostOrigin`, `OccupantLocation`), the three
    class-backed ones, and two condition-only paths; the two-sided
    `graphitron_field_path_resolution` misses a different 28. This node retires both lines of
    "Two resolutions of an authored element", the step hop and the reading, with the step target and
    `graphitron_field_table_link`, which is the path resolved per target type beside the path; their
    readers move to the path's columns, under "The walk answers what today's resolution answers,
    measured first".
  * **A broken path is a defect at the position the walk stopped** `open`, after the walk. The
    resolved path toward a target type is the walk where it reaches that type's table; a path that
    stops short is a defect naming the position it stopped at, the first written element with no
    row, and one that completes at another
    table is a defect naming that table. A field with a written path and no source is a defect of
    its own, nothing stating where it reads from, and that one covers a root field's path not led by
    `@routine`, a root having no source to join from. These are what `CHAIN_WITHOUT_TARGET` and the chain-break codes
    narrow to, and the stored resolution's tail and head flags go.
  * **A root field's `@reference` without a leading `@routine` is rejected** `open`. A root has no
    source, so its query starts on its target and a path there has nothing to join. Today a root
    field with one `@reference` and no `@routine` classifies as `QueryTableField`,
    `QueryServiceTableField` or `MutationServiceTableField` with the path dropped, so it reads as a
    constraint and does nothing; found 2026-10-16 by classifying the shapes. A root `@referenceFor`
    and a root chain of two directives not led by `@routine` are refused already. No consumer schema
    writes one. The classifier refuses it, the validator mirrors it and the editor shows it. In the
    store it is no rule of its own: such a field has a path and no source, the defect above.
  * **An argument or input field's path starts at its field's target** `open`. An argument is a
    condition on the field's target, so its path is a filter walked from the target, at a root and
    at a child alike. Generator and manual agree (`FieldBuilder` parses an argument's path from the
    return table, an input field's from the table its consuming argument resolves against, and
    `join-with-references.adoc` says the input is bound to the field's table for the argument site).
    Today the argument and input-field walks start from `graphitron_field_scope_table`, which reads
    `intent_field_navigated_type` and is dissolving; on sakila it holds every target of
    `graphitron_field_target_table` and 22 rows more, the tables of mutation and payload fields like
    `Mutation.deleteFilms`. Both filter walks start from the target relation instead; a mutation
    returning `ID` has a table its rows come from and no target type for that relation to name,
    which this node settles.
    * **Whether a nested input field's path moves its children** `open`, a question. A nested input
      field resolves against its root argument's table at every depth, in the store
      (`graphitron_input_field_resolving_table` reads the root argument's scope) and in the
      generator, which classifies nested fields against the same table and reads a `@reference` on
      an input-object-typed field as a column. If a path moved the table a filter stands at, an input
      field with a path would hand the table it reaches to the fields under it. Nothing asks for it
      yet.
    * **The manual says where hop 0 starts for each site** `open`. `join-with-references.adoc` says
      a condition hop's source is "the field's own table at hop 0"; that holds for a filter, which
      starts at the field's target, while an output field's path starts at its source, the parent
      type's table.
  * **The walk answers what today's resolution answers, measured first** `open`. Old against new
    on the corpus, sakila, sis and the fs-plattform subgraphs before anything is deleted, and every
    difference named. The one expected kind is a table element with several keys from where the
    walk stands, which today's candidate filter can settle by elimination further down the path and
    the step rule refuses, as the generator already does.
  * **The backward walk leaves nothing behind** `open`, after the walk answers what today's
    resolution answers. What the two-sided resolution built for walking from the target goes with
    it: the tail walk in `graphitron_field_path_resolution_rule` and the `reach` it tags;
    `reached_by_tail` and `reached_by_head` on the four arm tables under
    `graphitron_field_path_resolution` and their writes in `FieldPathResolutions`; the arms of
    `graphitron_entry_defect_rule` that read how far each walk got; `arriving_type_name` on
    `graphitron_field_path_reading` and the reading's statement of both directions of a key and
    every key into a named table, which only a walk that could stand at either end needed; and the
    comments, javadoc and test cases that speak of two walks. A sweep for `tail walk`, `head walk`,
    `reached_by_tail` and `reached_by_head` across the reactor finds none left. What stays is the
    reason, once, where an author reads it: the `@reference` reference page says a path is read
    from the field's source to its target, that a root has no source, and that nothing reads a path
    back from the target, because a backward step at a `table` element could only take the one key
    into the named table from anywhere, which ignores the source.

* **Every field has a path, the written one or the one discovery implies** `open`, after the
  walks agree. Ruled with Alf 2026-10-20: moving from a source to a target is a join and the path is
  how the join is done, so a field with a source table and a target table has path rows or it
  cannot join. Its path is the `@routine` and `@reference` elements it wrote, or where it wrote
  none the one key discovery finds between its source and its target type's table, stated as rows
  like the written ones, so the target table is always where a path ends and no reader falls back
  to discovery for itself. The target type's binding is then the anchor discovery reads, and for a
  written path a check: a written path ending anywhere else is a defect. The target relations are
  stated without the path, and the path is derived last. Where nothing is written:
  * no key or several between two tables is a defect, as the generator refuses it today;
  * the source's own table with nothing written is no join, discovery never joining a table to
    itself. A single-valued field with no arguments reads its parent's own row, as R1045 reads it,
    which needs no path any more than a nested field does; any other such field is a defect. A
    self-reference is written, and is a self-join oriented as R1037 rules. On sakila the
    one self-referencing key in use, `category_parent_category_id_fkey`, is written by all four
    `Category` fields that ride it;
  * a field of a union or interface type is discovered per member, into its participant path, as
    `Address.occupants` reaches `customer` and `staff` by a key each.

  Fields with no path at all, each for a reason of its own:
  * a root field: it has no source and the query starts on its target. A root led by `@routine`
    is the exception, its query starting on the routine's result and its path the routine
    producer's own;
  * a `@service` field: it reaches the service's result, and there is no path to a result;
  * a field on a nesting type, which reads the row its entering field's statement stands on;
  * a field returning the table whose record its parent holds: a `LOOKUP` producer that refetches
    the row by its key, a `SELECT` from the keys as values joined to the table. Ruled with Alf
    2026-10-20: moving back into the tablescape always refetches, and a record a service handed
    back is never read as the row; it is an OBJECT, read through the Record API, which nothing
    selected. `FilmServicePayload.film` is one, its payload holding the `FilmRecord` its service
    returned; a `RecordN` or `RowN` matching the table's key and `@sourceRow` are the other two
    ways a result leads back to a table. A field under such a parent reaching another table walks
    its path from the record's table in that new statement, a start no `graphitron_result_table`
    row states, the parent holding an object: it is the consume step's, under "A consumed key is a
    source";
  * a slot of a pivot type: it reads the aggregate its entering `@pivot` field's path reached,
    and a `@reference` there is a defect. The `@pivot` field itself has a source and a
    path, which ends at the attribute table it aggregates rather than at a table of its type.

  * **Whether a same-table field narrowed by its arguments disregards its source** `open`, a
    question. R1045 keeps such a field, a list or one with a `@condition` or `@lookupKey` and no
    `@reference`, as a read of the whole table narrowed by its arguments, the same under every
    parent. That is a field with a source whose query ignores it, which this node has no row
    for: it is neither a join nor the parent's own row.

  Counted on sakila 2026-10-20, the (field, target type) pairs with a source table, a target table
  and nothing written: 27. Nineteen join by one key; six are record-class carriers on their own
  table, `LOOKUP` producers that refetch by key; two are `@service` fields with two keys between
  the tables, which have no path.
* **The path is stated at the argument and input-field grains** `open`, after the two relations at
  the field grain. `@reference` and `@referenceFor` are written at three coordinates and only the
  field grain has a chain relation; both groupings are stated at the other two.
* **One resolution of an authored element stands, not two** `blocked`, on the two grains above.
  `graphitron_field_reference_step_hop` resolves the same elements as
  `graphitron_field_path_reading` from a decode its own comment calls deprecated; measured on a
  fixture reaching every arm the two agree exactly, so what is left is choosing which survives. The
  stored resolution is keyed in the hop's own arm shapes and adds the routine arm and the target
  grain the hop lacks, which is a vote for retiring the hop. A condition step's from and
  to tables then come from the field path's reading alone, which states them per written
  element off the method's signature, so `graphitron_condition_method_route`, keyed by the class and
  method as spelled and read only by the two hops, dissolves with them. Two things move into the
  reading first: the route keeps a step only where every overload's second parameter names one
  table, and the method is the one `graphitron_code_reference` settled rather than a name match.
* **The read-time path relations are gone** `blocked`, on the node above. `intent_field_path_start`,
  `_node` and `_terminus` retire as one and `graphitron_field_path_application` falls out with them,
  its only reader being `_node`.
* **The assembly pass is inside the stage-order gate** `blocked`, on R969. `StageOrderGateTest` models
  the derivation stratum only, so the three chain stages run in a hand-kept order nothing checks.

### A reference resolves where its directive is decoded

Eleven `intent_` views join an authored directive to the classpath, which is a resolution rather than
a derivation over either corpus alone, and `graphitron_` already does this against the catalog:
`FieldReferenceStepHops` reads a spelled step entry and `sql_`, writes owned tables, and reaches
`code_` only through one of these views.

* **The graphitron anchor runs after what it resolves against** `done`. `GraphitronAstCapture` runs
  after `CodeCapture` and `graphitron-ast` declares `graphql-ast`, `jooq` and `code`; its tables
  still copy strings, which the nodes below and every step `DerivationStratum` gives back change.
* **Four of the eleven go** `done`. `intent_condition_context_parameter`,
  `intent_external_field_contract_defect`, `intent_producer_cardinality_conflict` and
  `intent_scalar_java_type` went with their tests, having no other reader.
* **The demand relations go** `done`. `intent_field_demand_rule` and its five siblings went with
  `DemandRuleTest`, `DemandShadowTest` and `DemandResidue`; `intent_type_domain` stays. R740's
  demand slice is delivered by this.
* **The condition route resolves in `graphitron_`** `done`. `graphitron_condition_method_route`,
  renamed and declared to the gatherer that reads it.
* **The producer resolution resolves in `graphitron_`** `open`. `intent_field_producer_method` is the
  resolution the other arms hang on, and four of the eleven read it. `graphitron_service_entry` and
  `graphitron_external_field_entry` are wrong as they stand: keyed by coordinate, holding the
  authored strings the `graphitron_ast_` twins already hold, and joined to nothing.
  * **A code reference is written once** `open`. The signature gate's roster already holds the
    set: seven `graphitron_ast_` twins and the two coordinate-keyed entries carry one class, method
    and argMapping under keys of one shape, so the fact nobody wrote is the reference itself. The
    directive vocabulary is the specification here and the implementation is not, `@routine`,
    `@service`, `@externalField` and `@condition` being meant to take one argMapping one way and
    today sharing no code.
    * **The written reference** `done`. `graphitron_ast_code_reference_entry`: one Java code
      reference as written, class and optional method, keyed by its value's position or
      `@sourceRow`'s directive, found by a walk over the vocabulary's types; pinned by a fact
      document stating twelve sites. `@record` is decoded by nothing.
    * **The argMapping is written once** `done`. `graphitron_ast_argmapping_pair_entry`: one entry
      of one argMapping as written, for `@routine`, `@service`, `@externalField` and `@condition`
      alike, found through `VocabularyWalk` and read by `ArgMappingSigil.entries`. The vocabulary
      still calls argMapping inert on `@externalField`.
    * **The argMapping's readers are answered from the pair relation** `done`. Evidence in
      `roadmap/audits/2026-10-09-argmapping-and-derivation-timings.md`.
      * **The pair relation carries its path decomposition** `done`. Root, head and tail of what an
        entry binds to, generated by the database from the written text as the old entry's are.
      * **Where a mapping sits is an anchor** `done`. `graphitron_argmapping_site`: directive,
        ordinal, element and kind, code reference and path element, written by the graphitron-ast
        anchor from `graphitron_argmapping_site_rule`, marked and swept, a keyed table because a
        view was up to forty times slower. `graphql_directive_application` and its argument relation
        are declared to `graphql-ast`.
      * **Each reader is answered from them** `done`. The chain downstream of the old entry is
        rekeyed to the written mapping, each seeded test converting with the reader it pins.
        * **The candidate stage seeds a sigil from what was written** `done`. A candidate exists
          wherever a sigil is written; `argmapping-pair` states the case.
        * **The chain is keyed by the written mapping** `done`. `graphitron_argmapping_match`, the
          three `intent_argmapping_` views and `intent_resolved_node_key_projection` carry the
          mapping's and entry's positions in place of the site literal, the parameter's method being
          `graphitron_code_reference`'s. Pinned by the fact documents `argmapping-match`,
          `resolved-node-key-projection`, `argmapping-projection-defect` and
          `node-id-decode-mapped-parameter`.
        * **`graphitron_type_backing_rule` reads the pair relation** `done`. A `@service` parameter
          is redirected through the pair's `root_name` via `graphitron_argmapping_site`; a
          `@condition` mapping redirects nothing, stated in `type-backing.graphqls`.
        * **`intent_node_id_decode_slot` and `RoutineWriteFacts` read it** `done`, pinned by
          `node-id-decode-mapped-parameter`. Open on the slot: a mapped `@service` naming two
          overloads reaches it as one untyped row where it drew one row per overload.
      * **The old entry goes** `done`. `graphitron_argmapping_entry`, its writer
        `GraphitronFactCapture` and its tests went; the pair relation's foreign key to the written
        value holds what the orphan scan held.
      * **The registry decode reads no argMapping** `done`. Its unread quarantine rows went;
        `graphitron_undecoded_argument_entry` keeps the `columnMapping` it quarantines.
    * **The resolution** `open`, reopened on a legal schema. `graphitron_code_reference`: the one method a written reference
      names, keyed to it and to `code_method`, written by the graphitron-ast anchor; no match is no
      row. `graphitron_code_reference_site` states once what both it and the defects read: the
      directive and element a reference sits on, and the method it names, the `@externalField`
      default included. One `graphitron_entry_defect` arm reports every site in three codes, and
      says a class is absent only where the graph's classpath was read at all. Pinned by a fact
      document over the classpath corpus. `graphql_element` and `graphql_element_field` are
      declared to `graphql-ast` now, their writer.
      * **A condition reference names an overload set** `open`. A multitable `@condition` names
        one declaration per participant table and javac picks each branch's own, which is the
        feature; keeping only a single match reports `CODE_REFERENCE_METHOD_AMBIGUOUS` on two sakila
        coordinates that generate. So the resolution holds every match, and needing exactly one is
        the rule of a site that calls one method, `@service`, `@externalField` and `@sourceRow`.
    * **`@enum` resolves to a class** `open`, when a consumer asks. A class reference names no
      method, so its resolution is to `code_class` and is its own relation.
    * **The per-site copies go** `blocked`, on each consumer being answered and on the seeding
      branch's code-reference subject, twenty-nine classes seeding them. The twins lose
      `class_name`, `method` and `argmapping`, a twin left with nothing goes, and the gate's set
      goes with them.
  * **The walk's unknown-method rejections go** `blocked`, on entry defects failing validate.
    `Rejection.unknownServiceMethod` and `unknownLifterMethod` are the gate until then, and the
    editor shows a second diagnostic for the one fault until they leave.
  * **The winning application per field** `open`, waits on the resolution. Whether that is
    a relation of its own or a query over the two is for its first consumer to say.
  * **Each consumer is answered from the new model** `open`, waits on the resolution. The views were
    designed before the discipline, so none is flipped: a consumer's query is rewritten against the
    new relations and the view goes with its tests. The oracle is the consumer's own test, stated
    as SDL, which passes before and after; a consumer with none gets one first, and a deviation is
    an edit to that test. One child per consumer, grown as each is tried.
  * **The old shape is gone** `blocked`, on each consumer being answered from the new model and on
    the seeding branch's code-reference subject. The
    two producer views, the unused views and their tests, and the coordinate-keyed entries with
    their writers in `GraphitronAnchor`.
* **A routine result joins its target on the target's key, matched by name** `open`. A routine's
  result declares no key, so the rule is that it is joined to a target by matching the target's
  primary-key columns by name. The rule is real and stated twice, neither time where it belongs.
  `sql_name_matched_key_column` crosses every table-valued function with every primary key in the
  store, across catalogs no graph reads together, for joins nobody wrote, and four readers re-scope
  it by graph. The walk's `BuildContext.synthesizeNameMatchedJoin` derives the same rule again from
  the live catalog. Nothing compares the two.
  * **`@table` cannot name a routine result** `done`. A routine result is bound by the field
    returning it; `@table` on one is refused by the walk and by a `TABLE_NAMES_ROUTINE` entry
    defect, pinned by the example's `Tilgang`, which loses the directive.
    * **The manual says a routine result is bound by its field** `done`. `routine.adoc` and
      `code-generation-triggers.adoc` state the field's binding and the refusal, the routine example
      regenerated from the corpus.
  * **The pairing is the anchor's, where a field joins a routine result** `open`. Matched once, by
    `graphitron`'s anchor, for each join a field asks of a routine result: an authored reference
    joining from one, and a routine write's payload re-reading the rows it returned. Stored at that
    join's grain, keyed to the element and to both `sql_column` rows.
  * **The anchored pairs agree with the walk's** `blocked`, on the pairing. An oracle only this
    order affords, while the walk still synthesizes its own.
  * **Each reader reads the anchored pairs** `blocked`, on the pairing. `FieldReferenceStepHops`'
    `NAME_MATCH` arm and `RoutineWriteFacts.hopPairs`; `intent_argument_reference_step_hop` and
    `intent_mutation_routine_seat` go with their family rather than being flipped.
  * **`sql_name_matched_key_column` is gone** `blocked`, on the readers. With `NameMatchedKeys`,
    which was the jOOQ gatherer's only anchor step.
    * **The fact model stops citing it** `blocked`, on the node above. `fact-model.adoc`'s
      case-fold paragraph names it as a reader joining an owned fold; the anchored pairing takes its
      place there or the citation goes.
* **The type questions follow it** `blocked`, on the producer resolution, because a `graphitron_`
  relation reading `intent_` is the same crossing pointed the other way.
* **Entry defects fail validate** `blocked`, on the defects agreeing with the generator on a consumer
  schema, without which this fails sis on 161 coordinates that generate and the reactor's own
  example on 44. `graphitron_entry_defect`
  is read by the `diagnostic` view alone, so every code in it reaches the editor and none stops a
  build. Not the producer
  resolution's alone: it holds for every arm, and the producer resolution's rejections wait on it.
* **The defects are stored where the diagnostic reads them** `blocked`, on the producer resolution.
  `graphitron_entry_defect` is the precedent, and the `intent_mutation_routine_seat` overlap under
  the chain branch above is the same two-producers shape.

### The graphitron family completes its flip and subtract

The additive half of this family's migration ran and almost nothing came off. Replacements were
built, readers were left where they were, and the predecessors still stand, so the store maintains
both shapes and every consumer is still on the old one. This is the branch that draws that down, and
it is where the root's rule bites hardest: a branch is done when what it replaced is gone.

More branches will hang here than the two below. The entry branch is first because it is the one
with a rule that decides every case without a judgment call; the second is the pass the early anchor
made necessary, and it goes once the anchor no longer runs early.

#### No `graphitron_*_entry` survives, an entry being the transcription's alone

An entry is what a document wrote, at the position it was written, and the graphitron-ast family
holds those. A relation outside it carrying the name is the old decode under the transcription's
word, and renaming would not fix it: what the name is wrong about is which phase writes the row, and an
entry is written by the capture phase alone. Counted in `roadmap/audits/2026-09-28-graphitron-entry-census.md`.

* **The per-site triples collapse onto a coordinate** `open`, and first, because one collapse
  retires several relations where a flip retires one. `graphql_directive_application` is the
  precedent and the same defect is untreated here.
* **The twinned ones flip and subtract** `open`. Their replacement is written on every capture and
  read by nobody, which is the state this item calls the most dangerous one.
  * **The link and federation key entries are gone** `done`. Key synthesis reads
    `graphitron_ast_link_entry` and the `graphitron_ast_federation_key_` entries;
    `graphitron_link_entry`, the three `graphitron_federation_key_` entries and
    `intent_federation_key` went.
  * **The condition entries are gone** `done`. The field, argument and context-argument condition
    entries went; rules read `graphql_directive_application`, and MCP's condition bindings read
    `graphitron_code_reference_site`. Pinned by `read-surface-on-write`.
  * **The reference-step entries are gone** `blocked`, on the steps below and on "One resolution
    of an authored element stands". Four relations the registry decode writes, read for the path's
    shape, for the name a step spells, and by the hops and the read-time chain.
    * **The language server finds a spelled step through the step twins** `done`, for field,
      argument and input value; `graphitron_reference_for_step_entry` and
      `graphitron_argument_reference_for_step_entry` went, though the registry decode still writes
      their method references and spellings.
    * **The language server locates a step at the name it wrote** `done`, the string under `table:`
      or `key:` that the twin's element holds.
    * **Whether a coordinate carries a path is read off its applications** `open`. The node-id
      instruction and column-scope rules and `intent_field_column_table` ask only that, at fields,
      input fields and arguments alike, which `graphql_directive_application` answers once an
      application states a hop: `@reference(path: [])` is the same as no directive, as the
      generator reads it on a field and in effect on an input value, and writes no step, so until
      R692 makes the empty path an author error the question is whether the applications state at
      least one element. Where a path's last step lands and how many steps an application has are
      asked only to find a hop's target, and move with the hops.
    * **The field-site step resolution gives way to the path resolution** `blocked`, for the column
      scope rule and the node-id instruction rule, on "A path carries where each row comes from": a scalar read
      through a path has no table its target type binds, so the path resolution, keyed by that
      table, never walks it. Two derivations
      resolve one path: `graphitron_field_reference_step_hop` and `_target`, from the decoded steps
      per application and element, and `graphitron_field_path_resolution`, from the transcription
      per coordinate. Their readers ask for the first application's terminal hop, where the path
      resolution concatenates every `@routine` and `@reference` on the field, which is what the
      generator does, so each reader says which it meant as it moves: the column scope rule, the
      node-id instruction rule, `intent_field_reference_step_fanout`, `ClaimFacts`, `SchemaQueries`
      and `RoutineWriteFacts`. Then the old one goes, six tables, two views and two writers, with
      the seeded classes pinning it, their cases restated against the path resolution in
      `FieldTableLinksTest` where it does not state them already.
    * **The path's own readers** wait on "The read-time path relations are gone":
      `intent_field_path_node` and `intent_mutation_routine_seat` match a step by ordinal.
    * **The argument-site shape follows the argument path** `blocked`, on "The path is stated at
      the argument and input-field grains".
  * **The routine entries are gone** `open`. `graphitron_routine_entry` is
    `graphitron_ast_routine_entry` copied row for row and keyed by the field and an ordinal, which is
    a coordinate and a count rather than where the `@` was written; the coordinate and the ordinal
    are `graphql_directive_application`'s, and a routine's place in its field's path is
    `graphitron_field_path`'s. Four of its readers ask for the last application by `MAX(ordinal)`,
    which is the `@routine` latest in the path.
    * **The registry decode stops writing routine entries** `open`. Two writers fill the relation,
      `GraphitronAnchor.routines` from the twin and the registry decode through
      `GraphitronFactCapture` and `FactWrites`, so the decode's is redundant and goes first.
    * **The readers move to the twin, the path and the applications** `open`, each measured:
      `graphitron_routine_column_mapping_pair_entry`'s key, `graphitron_field_path_reading`,
      `intent_authored_field_claim`, `intent_field_path_start`, `intent_field_routine_method`,
      `intent_field_payload_producer`, `intent_carrier_routine_hop`,
      `intent_mutation_routine_seat`, three rules of `graphitron_entry_defect_rule`,
      `AuthoredClaimConflicts` and the language server's `ClaimFacts`. Then the relation and the
      anchor's writer go.
* **The untwinned ones get an ast entry first** `open`, and are the longer half.
* **The supertype roster becomes shrink-only** `open`. It already records the duplication and only
  ever gains rows, so making it a ratchet turns the record into the gate rather than adding one.

#### graphitron-ast and graphitron are one gatherer

Two gatherers by name and one by what they do. graphitron-ast captures the documents' graphitron
directives and then, in its anchor phase, writes `graphitron_field`, `graphitron_element` and the
rest, which are declared `graphitron`'s; `GraphitronAssemblyCapture` then writes more of
`graphitron`'s tables in a pass of its own. Decided with Alf 2026-10-19: they are one gatherer, its
capture phase and its anchor phase, and `GraphitronAssemblyCapture` dissolves into the anchor. The
fetcher and its subtypes are written there.

* **What each assembly step reads is known** `done`, 2026-10-19. Of its fifteen steps four move as
  they are (`FieldPathApplications`, `NodeKeyColumns`, `NodeIdCandidates`, `targets`); the other
  eleven read the decoded coordinate-keyed entries `SdlFactCapture.decode` writes, directly or
  through an earlier step. None reads `DerivationStratum`'s tables or `graphql_schema_problem`, and
  nothing in the anchor reads what the assembly writes, so no move makes a first capture read
  stale rows.
* **The decode runs before the anchor** `done`, 2026-10-19. It reads nothing from the store, only the
  documents merged in memory, which need no assembly; once it runs ahead, every step can follow
  the anchor in its present order. It goes altogether with "No `graphitron_*_entry` survives".
* **The two are one in the meta** `done`, 2026-10-20. One `meta_gatherer` row, the
  graphitron-ast owners moved to `graphitron`, its self-edge and duplicate edges gone, and
  `store_graph_progress`, `CaptureCommitsAsItGoesTest` and `GathererIsolationTest` following. The
  gatherer declares the corpora it reads, the SDL, the catalog, the classpath and the
  configuration, rather than none: keeping only the SDL makes the grain-corpus gate flag 34
  relations, and dropping it would claim it reads no corpus.
* **Each `GraphitronAssemblyCapture` step moves into the anchor phase** `done`, 2026-10-20. In
  their present order, as the anchor's last step; the class is the anchor's helper in
  `capture/document` and owns no progress row.
* **The eight steps that clear and refill are marked and swept** `open`. `FieldPathApplications`,
  `NodeKeyColumns`, `NodeIdCandidates`, `SpelledTables`, the two hop steps, `ResolvedTypeBindings`
  and `FieldReferenceStepTargets`; one child per step, grown as each is tried.
* **`GraphitronAssemblyCapture` is gone** `blocked`, on the eight. Its stages become the anchor's
  own lines.

#### `DerivationStratum` is gone, each step anchored by its owner

`DerivationStratum` is a second pass that runs after every gatherer, and it exists because the
graphitron anchor ran too early to resolve anything. Its 28 steps clear their graph's partition and
derive it again, which is not mark and sweep: nothing is marked, so nothing is swept, and a row that
leaves the population is overwritten rather than deleted, so `ON DELETE CASCADE` never has a root. It
is part of the subtractive goal because it is the old shape of the anchor phase standing beside the
new one.

* The graphitron anchor runs after what it resolves against: done, under the reference branch above.
* **Each step moves into its owner's anchor phase, marked and swept** `open`. A step is done when
  it marks what it wrote and sweeps what it did not, in the anchor phase of the gatherer that owns
  its table. One child per step, grown as each is tried.
  * **`CarrierDataFields`** `open`, as "A producer's carrier is its one data channel". Declared owned
    by `graphitron` and written by the stratum, so a reader in the graphitron anchor phase reads the
    previous capture's rows, none on a first one. Found when the error channel tried to read it.
* **The `intent_` writers go to a family or go** `open`. `ClassificationDomainCapture`,
  `InputOccurrencePaths`, `TypeBackingRows` and `AuthoredClaimRejectionRows` write `intent_`
  tables, so each moves to the family whose facts
  it reads, or goes with its readers.
* **The step that reads an executable schema reads a captured fact** `open`.
  `ClassificationDomainCapture` derives `intent_type_domain` from the assembled `GraphQLSchema`, which
  is not a row in the store; the input is captured first and the step then reads it.
* **The class and its order gate are gone** `blocked`, on the steps. `DerivationStratum`,
  `StageProgress` and `StageOrderGateTest` go, the order they kept being the gatherer order and the
  order inside one anchor phase.

### `capture` runs against the model alone `done`

* **The goal captures and nothing else** `done`. The goal opens the store, captures and closes it
  from the plugin, and fails only where it cannot capture.
  * A schema extending a built-in captures `done`, by "The specification is a document": an
    extension merges onto the specification's declaration.
* **A capture commits as it goes** `done`. Each gatherer commits its own transactions and
  `store_graph_progress` records each one's start, completion and time, so a failed capture leaves
  its progress on disk. MCP status reads the `report` row to say a round's report is withheld.

### The classified model dissolves into the store

The store is the model. The walk resolves the same schema a second time into records the validator
and the emitters read, and it goes when nothing reads what it builds.

* **What the store states of R333's facts is counted** `done`. Of the walk's 465 rejection calls,
  411 constrain a fact; evidence in `roadmap/audits/2026-10-08-fact-census.md`.
* **Every coordinate carries R333's facts, and a constraint a fact breaks is a located defect**
  `open`. The facts are what `generate` reads and their constraints are what `validate` reports, so
  neither needs a classification.
  * **The defect model is designed as a whole** `done`. One vocabulary of rules and one relation of
    violations located at an AST entry, where six families stood. Evidence in
    `roadmap/audits/2026-10-08-defect-model.md`.
  * **A defect rests on a basis and sits at an AST entry** `done`. `basis` in place of `fault`, the
    twelve codes `GRAPHITRON`, and the position a foreign key into `graphql_ast_entry`, the element
    `graphitron_entry_defect_site`'s fixed hops away.
  * **Every field has one fetcher, which produces a result or reads one** `open`, the spine, decided
    with Alf 2026-10-19. A producer produces a result and a reader reads from one. A result can be
    nested, so a reader can read what a reader read, and a producer can read a result to produce a
    new one. Each of the walk's field classes falls on one side: the root fields, the split
    children, a child `@service`, `totalCount` and the facets produce, and so does a single-valued
    interface, union or table-interface child, which runs its own statement per parent row; a
    column, an `@externalField`, a record accessor, the ID encoders, a pivot slot, the errors field,
    the connection's edges, nodes and page info, and the inline table and pivot children read, the
    last two a nested result; a `NestingField` reads the whole result. A field neither subtype
    explains is the defect a totality gate finds. The population is the output fields of object
    types: an interface's fields and the input fields are in `graphitron_field` and are not
    fetched. Written in the graphitron anchor phase, once the steps whose tables it
    reads have moved there; see "graphitron-ast and graphitron are one gatherer".
    `graphitron_fetcher`: one row per output field of
    `graphitron_field`, complete over them, the supertype the fetcher's facts reference. R333's
    DataFetcher, one coordinate, one resolver, 1:1 and total, which joins `source` and `target` and
    dispatches the operation set. The row carries the key and the facts stay their own relations,
    so the source, the target and the operations reference it rather than each keying the field
    afresh, and a field no fact explains is an absent subtype row a gate can find. What
    `<Type>Fetchers.<field>` is emitted from. How many source objects arrive, ROOT, ONLY_CHILD or
    CHILD, was dissolved for want of a reader: `graphitron_type_reach`, `graphitron_type_arrival`
    and `graphitron_field_source`, their stages, fact documents and totality gate are in trunk at
    `ab6b49c34`, and this node's reader brings them back from there with the cost measured,
    about 18 s of a sis capture as they were stored.
  * **The producers are stated** `open`, the fetcher's first subtype. A producer is a field where
    something is executed, and
    `graphitron_producer` holds those fields and no others: R333's `operation` is total over every
    coordinate, a bare column read being `Fetch`, so the producers are its verbs that execute and
    not the fact itself; how the two meet is refined as the work goes. Keyed by field, with its kind.
    Neither the root it sits on nor the type it produces is a column: the first is a join to the
    root types and the second is the field's target. What it executes is, a subtype per kind naming
    it resolved: a write its mutated table and `matches`, which is why a produced and a mutated type
    are two facts (a delete produces an id and mutates a table); a `_routine` its first `@routine`
    application and the routine, the applications after it being elements of the reference path;
    a `_service` its application and the method. A root field, by `graphql_root_operation` and not by
    name, a `@service`, a `@splitQuery`, a connection, `@tenantFanOut`, `totalCount` and the facets
    are producers, and so are `node` and `nodes`, by their signature; so are a record-backed
    parent's table-bound child, the polymorphic fan-in and a discriminated interface's list child,
    which the walk fetches apart today without a directive saying so; and so is the re-read on a
    payload's data field, a producer reading the result the write returned. A `@routine` below a
    root that is not split, an `@externalField` and every projected field are not. `_entities`
    waits until it is stated, having no `graphitron_field` row. A field the rule finds two kinds
    for has no row and is a defect at the field; `@lookupKey` with `@routine` is deferred and has
    none either. A write or a service whose target is table-bound re-reads by key in the same
    fetcher; that follows from its kind and its target and is not a second kind. A split `@routine`
    child is `_routine`, splitting being delivery. Where the walk disagrees the store is right and
    the walk is the defect: it decides the roots and `node` by name, so `schema { query: MyQuery }`
    parts them, and it reads a `@service` on an errors field as a reader and drops its method. A
    producer that reads and lands on a
    table-bound type leaves its window open, and every field below it that is not a producer is
    projected into its statement; a write closes it, the lock it takes being returned before anything
    more is read, and so does a `_service`. The hierarchy is by what executes and how it identifies
    its rows:
    * `_query`, SQL graphitron generates against tables:
      * `_select`, the rows a condition matches, consuming its source object's keys where it has one;
        keyset paging and one select per participant of a polymorphic type are properties of it;
      * `_lookup`, the rows for an ordered list of keys, one position per key and null for a miss:
        `@lookupKey`, `node`, `nodes`, `_entities`, and the re-read after a write over the keys it
        returned; arriving batched, a node type or a type carrying `@key`, is this fact, and comes
        from these producers once `node`, `nodes` and `_entities` are stated;
      * `_count` and `_facets`, `totalCount` and a connection's facets, which read the statement
        of the producer whose connection they sit on rather than its rows. A connection type can
        serve several fields, so which producer that is belongs to the source, not to a key here;
      * `_insert`, values only, no row identified, returning the keys the database made;
      * the identifying write, with `matches`, `ONE` or `MANY` as `multiRow` was written, `ONE` by
        default: one row per input, or a filter per input that may match several. `_update` carries
        a predicate and values, `_delete` a predicate alone and returns keys of rows that are gone.
        How a `MANY` update tells its filter from its values is out of scope; the model only records
        the intention, and behaviour does not change;
    * `_routine`, SQL generated around a database routine, keyed to its first application;
    * `_service`, a Java method, keyed to its application.

    Upsert is retired and stays out. What makes a field a producer is read from what was written,
    the decoded entries and, for the bare `@splitQuery`, `graphitron_directive_application`; the
    deprecated coordinate-keyed `graphitron_split_query_entry`, `graphitron_service_entry` and
    `graphitron_routine_entry` are not read. Marked and swept from the start.
    * **The kind is stated** `done`, 2026-10-20. `graphitron_fetcher` on its producer side and
      `graphitron_producer` with its kind, written by `Fetchers` as the anchor's last stage from
      `graphitron_producer_rule`. What the written directives claim is `graphitron_producer_claim`;
      a field claiming two kinds is `PRODUCER_KINDS_CONFLICT` at its declaration. A payload's data
      field is a `_lookup` only where the payload has no table of its own, so a write returning its
      table is one row. Pinned by the `fetcher-producers` fact document, whose prelude root field now
      runs a routine.
    * **A write's re-read runs after its commit, for every write shape** `open`, decided with Alf
      2026-10-20. Whether the re-read is inside the write's own fetcher, as for a write returning
      its table, or is the payload's data field, it reads what was committed and never the open
      transaction. Emitted so today: the keys come out of `transactionResult` and the re-read
      follows. Pinned for the routine carrier by `RoutineCarrierRlsExecutionTest` and for an insert
      returning a table interface by `DmlTableInterfaceReturnExecutionTest`; an update, a delete and
      a service re-reading have no pin. A deferred constraint trigger that rewrites the row at
      commit is one test for all of them: the re-read sees the rewrite only after the commit.
    * **What each kind runs is stated, resolved** `done`, 2026-10-20. `graphitron_producer_write`
      with the `@mutation` application, `matches` and the mutated table, by the generator's
      precedence: the return's own `@table`, read off `graphitron_tabletype` and not the resolved
      binding, which also holds what a `@routine` returning the type lands on, or the one data field
      a payload re-reads by, then `@mutation(table:)`. `graphitron_producer_routine` with the first
      `@routine` and its routine, `graphitron_producer_service` with the reference and its method.
      A row only where the reference resolved; where it did not, the code-reference and table
      defects already say why. On sis every write and every service resolves.

    First of its siblings, decided with Alf 2026-10-19: the payload, the carrier and the error
    channel are all facts about a producer, and each is restated today by a relation that folded the
    producer away. The payload a producer returns is its own field's return type, one join away, so
    it is not a column here; the carrier rule's `family` is this relation's kind. Every producer is
    in the population, not only the mutation-rooted ones: a query-rooted payload has the same shape.
  * **A result flows down from its producer** `done`, 2026-10-20, decided with Alf.
    `graphitron_result`: one row per type a result reaches and its shape, ROW, a row of a statement
    graphitron runs, which a field's read is projected into, or OBJECT, a value handed over, read
    through an accessor. Payload, error and connection were weighed as shapes of their own and fold
    into these two: who built a value is the producer's fact. A table's own record a service hands
    back is an OBJECT, read through the Record API: nothing selected it, and moving from it back into
    the tables always refetches, by a TableRecord, a `RecordN` or `RowN` matching the key, or
    `@sourceRow`; how an accessor reads a value is its class's, `graphitron_result_class`. Its origin
    is a subtype: `graphitron_result_table`, the table whose row it is, `graphitron_result_pivot`, the
    pivot field whose aggregate makes it, `graphitron_result_class`, an object's class. Seeds are each
    producer's result, `node`, `nodes` and `_entities` holding ROW, the latter seeded from the `@key`
    types until it is stated, a connection's wrapper as OBJECT; a reader hands its target its
    parent's shape and origin, except that a table-bound type starts a row of its table, a pivot
    field's type the pivot's row, and an `@error` type holds the exception as OBJECT. Filled by
    `Results` in the anchor, merging `graphitron_result_rule` until a round changes nothing, and
    marked and swept: the rule steps only from rows marked with the reading's own instant,
    `store_graph.last_captured`, so a row a reading reaches again keeps its key and a reference into
    it holds across captures. On sis four rounds, about 1 s, every object type placed but one no field
    returns. One shape per type is the rule: `RESULT_SHAPES_CONFLICT` at the type when broken, none
    on sis. It replaces the dissolved type reach, which asked how many objects arrive and had no
    reader, and is what arrives at every field of the type, under "A field's source is its parent's
    result".
    * **A table with no record class says so** `open`. `sql_table.record_class_fqn` holds
      `org.jooq.Record` for a table jOOQ generated no record class for, so a reader asking which
      table a record class belongs to would answer every such table. The sql family states the
      absence as NULL.
    * **A table child under a handed value deeper than its carrier refetches** `open`. The producer
      rule makes a table-bound field a `LOOKUP` where its parent is a payload or record-backed; a type
      nested beneath such a value with no backing of its own would read its table child as `NESTED`,
      which has no statement to nest into. None on sis, where no type sits beneath the 76 record-backed
      carriers.
  * **The readers are stated** `done`, 2026-10-20, after the results. `graphitron_reader`, every
    field of a type holding one result that runs nothing: under a ROW a COLUMN, an EXPRESSION or a
    NESTED row, under an OBJECT an ACCESSOR, an outcome's errors field among them. A node id's
    encoding and an outcome are facts of the field and of the producer, not ways of reading. A
    query or mutation root field, or a field of a type holding a result, with no fetcher is
    `FIELD_WITHOUT_FETCHER`, none on sis. A connection's wrapper and edges produce only
    `totalCount` and the facets; `nodes` and `Edge.node` read the page, which the producer rule had
    made SELECTs on a polymorphic connection, 48 on sis. A table-bound child of a record-backed parent
    is a `LOOKUP`, the refetch by the keys the object carries, as a payload's is; the producer rule
    had called it a `SELECT`.
  * **A producer's outcome is stated** `done`, 2026-10-20, decided with Alf. A service, a write or
    a routine whose target has exactly one errors field hands what it throws to that field, at a root
    or below one; an errors field on a type no such producer returns is read off the value like any
    other. `graphitron_errors_field`, one row per field whose value is a list of errors, a nullable
    list of a union or an interface whose every member is `@error`, the walk's shape and a fact of the
    field whoever returns its type; a list of one `@error` object is none, which keeps opptak's four
    `[IkkeAutorisertError!]` the value's own. `graphitron_producer_outcome`, a producer subtype naming
    the outcome type and its errors field. The walk's refusals are defects where written:
    `ERRORS_FIELD_MIXED_MEMBERS`, `ERRORS_FIELD_NOT_A_LIST`, `ERRORS_FIELD_NON_NULL`,
    `MULTIPLE_ERRORS_FIELDS`, `OUTCOME_FIELD_NON_NULL`, and `ERRORS_IN_A_ROW`, errors in a row of a
    statement, which the walk read through an accessor that cannot work. On sis 158 errors fields and
    159 outcomes, under a second for the rules and the stage, and no defect; on sakila 23 and 30, and
    no defect. `OutcomeAgreementTest` holds the outcomes to the walk's `Mapped` channels and wrapper
    transports. First stated for root producers alone; widened 2026-10-21, decided with Alf, since the
    generator already catches for a child `@service`, no child producer on sis, sakila or opptak
    returning an errors field.
    * **A child producer catches into its outcome** `done`, 2026-10-21. A child `@service`
      resolves its channel as a root one does, an `ErrorChannel.Mapped` on the `Outcome` wrapper,
      and its batched fetcher wraps each loaded value in `Success` and claims a throw onto
      `ErrorList` in both its asynchronous tail and the synchronous guard around key extraction.
      A list of outcomes is refused, one per element having no carrier. An `ErrorsField` is an
      outcome's alone, so its transport went, and an errors-shaped field on a type no producer
      returns is a `RecordReadField` read off the value. Constructing the developer's payload
      class, `PayloadClass` under `RouterDispatched` with its constructor and setter reflection,
      `PayloadAccessor` and the generated `ErrorRouter.dispatch` went with it.
  * **A payload's fields are stated** `open`, after the producers. `graphitron_payload_channel`: one
    row per field of a type a producer returns, with its role, errors or data, and its element,
    table, record, id or other. Total over the fields, an element no rule recognises being a row of
    kind other and not an absence. The table test and the table a reader fetches read one binding,
    `graphitron_resolved_type_binding`, where today the rule tests `intent_bound_table` and every
    reader fetches from the former. The errors side is `graphitron_errors_field`'s, stated with the
    outcome; its members stay an ordered child tied to `graphitron_ast_error_entry`.
  * **A producer's carrier is its one data channel** `open`, after the payload's fields.
    `graphitron_carrier_data_channel`, keyed by the producer and naming the data field, one row per
    producer, so no reader re-derives the producer from a payload. What stops a payload being a
    carrier is a defect at the site that stops it, with its reason: several data channels, an element
    of kind other, a directive that routes a field out of the mold, the family rules on id fields and
    `@splitQuery`, two kinds on one coordinate. Today each is an absence nobody can explain, which is
    why the walk keeps its own reasons. Written in the graphitron anchor phase, which is
    `CarrierDataFields`' step under "Each step moves into its owner's anchor phase".
  * **The carrier's readers move** `open`, after the carrier: the scope table's payload rung,
    `RoutineWriteQuery`, `intent_mutation_routine_seat` (its refusals reading the defects rather than
    absence), the language server's `$source` site, and the error channel's transport.
  * **Subtract what the carrier replaces** `blocked`, on its readers. `graphitron_carrier_data_field`
    and its rule, with `family` in the key and `data_fields`, a count read only as a verdict;
    `intent_field_payload_producer`; `intent_carrier_routine_hop`'s producer re-join and its
    `candidates` column.
  * **The matched key is stated** `open`, with the identifying writes.
    `graphitron_producer_matched_key`, one row per `ONE` write and key its input columns cover, keyed
    to the producer and to `sql_constraint`. An input covering two keys is two rows, and choosing one
    is a rule over the relation. A `ONE` write with no row is the defect the build reports today; a
    `MANY` write and an insert have none by construction.
  * **Operation's modifiers are keyed to the producer** `blocked`, on the producers. `condition`,
    `paginate` and `orderBy` modify a `_select` or a `_lookup` rather than execute anything, which is
    how the generated code is organised too: one condition method and one ordering method per field.
  * **A scalar constant says why it has no input type** `open`. `code_scalar_constant.input_type`
    is what the walk's `@scalarType` check and the emitted scalar both read, and it folds four
    silences into one NULL: the class does not load, the field is not a scalar, the initialiser
    throws, or the coercing is erased. The walk's `CoercingErased` rejection needs the last told
    apart, with how the coercing was declared, before `validate` can report it from the store.
  * **`source` and `target` are stated for every coordinate** `open`. The two total facts no relation
    states whole, and totality is what the defect model's gate checks.
    * **A field's source says how many objects arrive** `blocked`, on the fetcher, its reader.
      Dissolved with no reader; "Every field has one fetcher" says where it is resurrected from.
      An arrival fact rather than the source: it returns under its own name, the source being
      the parent's result below.
    * **The source object is stated by `graphitron_` relations** `open`. Whether a type is
      table-bound, a record, or a nesting type passing its parent's row through.
      * **A type's backing is anchored by the graphitron gatherer** `done`.
        `graphitron_type_backing`, marked and swept after the table bindings, one recursive
        statement over producers' resolved methods and backed classes' members; an ambiguous
        reference draws `CODE_REFERENCE_METHOD_AMBIGUOUS`.
      * **The readers move and the `intent_` cluster retires** `done`. Every reader in the
        generator, the language server and the MCP server reads `graphitron_type_backing`, a
        grounding being a `PRODUCER` row; the seeded cases are fact documents.
      * **Two backings for one type is a defect** `open`. What `intent_type_backing_conflict`
        reports, as a code at the type's declaration.
      * **A field's source is its parent's result** `open`. Ruled with Alf 2026-10-20: what arrives
        at a field is what its parent type holds, so a field's source is a join on
        `graphitron_result` and no relation of its own; `graphitron_field_source`, designed
        2026-10-19 as a supertype with a kind per field, would restate per field what the result
        states per type, and is not built. The kinds are the joins: nothing at a root type, which
        holds no result; a row or an object, the parent's shape, an object's class
        `graphitron_result_class`'s; a slot one whose parent's row is
        `graphitron_result_pivot`'s, asked first, since a type nested under a pivot type inherits
        the pivot's origin and not a table; a nested field one whose parent holds a row with a
        `graphitron_result_table` row and binds no table; a table's record a service hands back is
        an OBJECT, so no row comes from a record class and none is nested. A type with two shapes is
        none of these, as the reader rule skips it, and a type whose binding is ambiguous is bound,
        not nested. A pivot is not nesting: a nested field reads the row its
        entering field stands on, a slot the aggregate the `@pivot` field's path reached, so a
        `@reference` on a slot is a defect. The target does not join this way: a field's target is
        its field type, stated in `graphitron_field_target`, the result being what arrives and not
        what a field reaches.

        One shape per type is `RESULT_SHAPES_CONFLICT`, ruled with Alf 2026-10-20 over stating the
        shape per entering field, which would make every rule and reader ask under which entering
        field as well and buys nothing a consumer writes. Measured 2026-10-20 on captures of the four
        Graphitron 10 subgraphs at fs-plattform `df42883314` (kompetanse, opptak,
        utdanningsregisteret, tilgangsstyring), no type with no table is reached two ways. Sakila's
        two fixtures that are split into a type per way, with their execution tests:
        `TranslatedTexts`, reached by four `@pivot` fields, by `PivotNestingHost.texts` and by
        `Query.pivotServiceTexts @service`, and `FilmBlurb`, nested by `Film.blurb` and read off
        `FilmBlurbHolder.blurb`'s object. The classifier refuses the shape, the validator mirrors it
        and the editor shows it.
        * **`graphitron_field_source_table` dissolves into `graphitron_result_table`** `open`. Its
          `SOURCE_TYPE`, `INHERITED` and `RECORD_CLASS` arms are the parent type's result table,
          and its readers, the path's starts among them, read that; `SOURCE_ROW` waits for "A
          consumed key is a source".
        * **A slot writing `@reference` is a defect** `open`, a code at the slot.
    * **A field's target is its target type** `done`. `graphitron_field_target` holds one row per
      field: its target type unwrapped from list, non-null and connection wrapping, scalars
      included, with the connection beside it; kind, participants and table are joins on the target
      type. A mutation's target is its payload type; its refetch is the payload's data field, a
      `LOOKUP` producer, `graphitron_producer`'s fact.
    * **The duplicate statements of a field's tables dissolve** `open`. A field's target and its
      source are each stated several times at several grains, and each statement goes when its
      readers have moved:
      * `graphitron_field_table` `done`, its target half to `graphitron_field_target_table` and its
        `from_` half to `graphitron_field_source_table`, each of which has taken over its readers.
        The path family is keyed by the target type: `graphitron_field_table_link`, the path
        reading, the resolution rule and its keyed, keyless, open and condition tables, with their
        foreign keys into `graphitron_field_target_table`, the tail walk checking the last link's
        table against the target type's binding and the entry defects listing chains per
        target type. Old against new, by the target type's table: sis equal (778 links, 3310
        resolutions), sakila equal but for the six `@routine` payloads, whose target is a carrier
        binding no table, and the defects unchanged on both. `CHAIN_WITHOUT_TARGET` moved last, under
        "A scalar `@reference` has a target", and the relation went with `FieldEndpoints` and
        `FieldEndpointsTest`, whose cases the target-table and source-table documents state;
      * `intent_field_participant_scope_table` `done`, to the target and its participants. The scope
        rule's participant rows, `intent_reference_for_application`, `ReferenceForParticipantDefects`
        and `UnlowerableOrderings` read `graphitron_field_target`, `graphql_poly_member` and
        `graphitron_resolved_type_binding`; the `@mutation(table:)` exclusion stays with the scope
        rule, the one reader it separates rows for. Equal both ways on sakila (188 rows) and sis (533)
        with or without that exclusion. The seeded cases are `field-participant-scope`;
      * `intent_reference_for_application` `open`. Whether a `@referenceFor` applies at a consumer
        is whether the participant it names is a member of the consumer's target, one join to
        `graphql_poly_member`, and its table is the member's binding. What is left is the input-field
        pairing through `intent_input_occurrence_path`, which goes with the `@referenceFor` entries;
      * `graphitron_field_scope_table`, the table a field's SQL binds against, checked against the
        source and the targets it restates; it is dissolving. Its participant rows go with the move to the source
        and the target;
      * `graphitron_field_column_scope` and `intent_field_path_terminus`, which state where a path or a
        routine ends, to the path read toward a target;
      * `intent_bound_table`, to `graphitron_resolved_type_binding`.

      Each is measured old against new on sakila and sis before it goes, the rules reading it keep
      their answers, and its readers are named on the commit that moves them.
    * **The unlowerable ordering is a defect** `done`. A declared `@defaultOrder` or `@orderBy` on a
      field read once per participant is `ORDERING_ACROSS_PARTICIPANTS`, resting on `UNSUPPORTED`,
      in `graphitron_entry_defect_rule`; `UnlowerableOrderings.detect` mints the build error from
      the stored rows until entry defects fail the build. The cases are
      `ordering-across-participants`.
    * **A list `@routine` write delivers its order** `open`. Its returned keys are captured and its
      rows re-read by key, and neither step sorts, so the target's primary key orders nothing. The
      one case is graphitron's own example schema, which is why it was a held verdict rather than a
      defect. It becomes a defect, or the re-read sorts, and the example builds either way.
    * **The source's shape and the target's shape** `blocked`, on the node above. A record is a
      class-backed type, and a column read differs from a field read by the source's shape.
    * **The source is stated** `open`. The consume phase: which keys a producer reads from its
      parent's source object and which table they match, one row per field and table with the rule
      that answered: the source type's table; the keys a write returned; or, below a type
      `graphitron_type_backing` backs with a class, a `TableRecord` member, members matching the
      primary key of the first link's table, or `@sourceRow`, which is a forced choice. A root field
      has no source: nothing encloses it, so its query starts on its target.
      * **A field's source is its parent's table, or its parent's sources** `done`.
        `graphitron_field_source_table`, keyed by field and source table: `SOURCE_TYPE` where the
        parent binds a table, `INHERITED` through object types with no table of their own. The head
        walk seeds from it, so `graphitron_field_table.from_*` has no reader; the cases are
        `field-source-table`.
      * **A root field led by `@routine` starts on the routine's result** `open`. Ruled with Alf
        2026-10-20: a root has no source, and a routine is a producer, `graphitron_producer_routine`
        naming the routine it calls; the producer states where its query starts, the routine's
        result table, and its path is `graphitron_routine_field_path`'s, under "A path carries where
        each row comes from". Measured 2026-10-20: tilgangsstyring has eight root fields led by
        `@routine`, seven of them with a `@reference` after it (`Query.mineSynligeOrganisasjoner`
        rides a key from the result to `organisasjon`), and sakila 23, thirteen with elements after
        it, writes among them (`Mutation.rentFilmCustomer` two keys on to `customer`); opptak,
        utdanningsregisteret and kompetanse have none. Where nothing follows the routine the field's
        type is bound to the result itself, as `Query.mineTilganger`'s `Brukertilgang` is to
        `mine_tilganger`, and there is no join.
        * **The sql family states a routine's result table** `done`, already. A table-valued
          function is one database object read two ways, a `FUNCTION` row of `sql_table` and a
          `sql_routine` row, the reading writing the routine from the table, so the two share their
          key, and `sql_routine`'s declaration makes that join the statement on purpose: a routine
          with no `RETURNS TABLE` form has no table, so table-valuedness is a join and not a
          column, and no foreign key holds every routine to a table. On sakila and the fs-plattform
          captures of 2026-10-20 every routine has its table and every `FUNCTION` table its
          routine. A routine producer's result table is therefore the key
          `graphitron_producer_routine` already holds, joined to `sql_table`; nothing is matched by
          name. Read 2026-10-20: the gap this node named was the earlier `FUNCTION` source rule's
          match on spelled names, not the catalog's.
      * **A producer's consume step is stated** `open`, after the parent's result.
        `graphitron_field_source_key`, one row per field that takes keys from its source object,
        keyed by the field, its parent type's object a `graphitron_result_class` row, whose class
        is `graphitron_type_backing`'s string with no reference into the code family: how it takes
        them and the table they match. `SOURCE_ROW`: `@sourceRow`'s method, called on the object,
        returns the source-side columns of the path's first key, the method a reference into the code
        family. `RECORD`: the object is the table's own record, its key read straight off it. Later a
        `TableRecord` member and members matching the first link's primary key; a consumer case for
        the first is tilgangsstyring's `DeaktiverApplikasjonerPayload` and its two siblings, plain
        types a root `@service` returns, whose `applikasjoner` member is a `List<SubjektRecord>`, so
        `subjekt` is its source and each participant's one key reaches its own table. It is the
        consume phase a producer reads its keys by, so the fetcher's DataLoader is generated from
        it, and its field references `graphitron_producer`, which serves two kinds: a split child
        `SELECT`, its keys off the parent's row, and a `LOOKUP` under an OBJECT parent, its keys off
        the handed record, a `RecordN` or `RowN`, or `@sourceRow`. `RECORD` waits on "A table with
        no record class says so": until the sql family states that absence, the record class
        `org.jooq.Record` names every table jOOQ generated no class for.
      * **A consumed key is a source** `open`, after the consume step. The forward walk waits on it.
        Under an OBJECT parent, which has a `graphitron_result_class` row and no
        `graphitron_result_table` row, a path can start only at the table a consumed key matches:
        that start is `graphitron_field_source_key`'s table, read by the walk where it reads the
        parent's result table for a row, and stated in no relation of its own, its source table
        dissolving into `graphitron_result_table`. Until then the two regressions are
        held by an interim, the rules `RECORD_CLASS` and `SOURCE_ROW` stated in the source table's
        own rule, which go here: `FilmDetailsCarrier` from `film` through `FilmRecord`, and
        `CustomerAddressSummary.address` from `customer`. `NO_ROUTE_FROM_DEPARTURE` is 0 on sakila
        and sis with the interim, and stays 0; the cases are `field-source-table-class-backed`.
  * **The candidates are one family** `open`. What a coordinate could have named, asked by the
    build error's message, the language server's completion and the MCP server, and answered today
    in as many shapes as there are askers: two stored relations, a jOOQ query, and completions in
    the language server that work their candidates out themselves. A candidate is one shape: the
    coordinate, what it could name, and the rule that offers it, stated as a rule view and read by
    every asker. Not to be confused with the `candidates` columns elsewhere, which count how many
    resolutions a spelling has.
    * **A `@referenceFor`'s valid participants are a rule view** `open`. The members of each
      consumer's target with a table, which `ReferenceForParticipantDefects.participantsOf` queries
      in jOOQ once per consumer to list in its rejection.
    * **`graphitron_argmapping_candidate` takes the shape** `open`. Every writable path under a
      directive's coordinate, read by `graphitron_argmapping_match`. The language server's
      `ArgMappingCompletions` reads the field's arguments syntactically instead and offers no dotted
      path; reading the relation gives it the paths.
    * **`graphitron_node_id_candidate` takes the shape** `open`. The node types a bare `@nodeId` may
      name, with no reader yet; the rules are the `@nodeId` target node's under "A node id is a
      target type, read as its ID". The language server's `NodeTypeCompletions` reads
      `graphitron_node_entry` and `intent_node_container_member` instead.
    * **The catalog's names are candidates too** `open`. What `@table(name:)` and a path element's
      `table:` and `key:` may name, which `TableCompletions` and `ReferenceCompletions` read off the
      census and the bound tables. Whether these join the family or stay census reads is decided
      here.
    * **The askers read the family** `blocked`, on the nodes above: the rejections' lists of valid
      names, the language server's completions and an MCP surface for them, each named on the
      commit that moves it.
  * **A field's target is a type, and a table or a column beneath it** `open`. `graphitron_field_target`
    is the supertype, one row per field carrying its target type and the wrapper where there is one;
    the subtypes say what the target is. `graphitron_field_target_table` is where the target binds a
    table, or each table-bound participant where it does not, as now. `graphitron_field_target_column`
    is new: where the target is a scalar or an enum, the column the value is read from, keyed to
    `sql_column` so the row carries its table, with how the spelling matched, `@field(name:)` or the
    field's own name, by jOOQ name or SQL name. It is the fact `intent_column_match_claim` picks with
    a window function every time anyone asks. A spelling matching no column or several is a defect at
    the field and no row here: no column is `COLUMN_UNRESOLVED`, which "A field reading a column its
    table lacks is a defect" states over the column scope today and which moves onto this, and
    several is `COLUMN_AMBIGUOUS`.
    * **A scalar without a path reads its source table** `done`. `graphitron_field_target_column`
      holds one row per field and table its read departs from, by `graphitron_field_source_table`'s
      `SOURCE_TYPE`, `INHERITED` and `RECORD_CLASS` rules: a nesting type's scalar reads each table
      it retains, and `FilmDetailsCarrier`, backed by `FilmRecord`, reads `filmId` as
      `film.film_id`, the row emitting turns into `rec.get(FILM.FILM_ID)` through the table's
      `code_class` fact. A `@sourceRow` departure gives none, and a type with no source table reads
      its source object, as does one reached through a `@service` or `@externalField` field, which
      produces an object of its own: `INHERITED` stops there, found on sis and sakila as false
      `COLUMN_UNRESOLVED`. An `id: ID` with no `@field` is a node's identity only where its type
      implements `Node`, a nesting type's too; anywhere else it matches a column. The spelling matches case folded, the SQL name before the generated one;
      none is `COLUMN_UNRESOLVED` and several `COLUMN_AMBIGUOUS`, both read off
      `graphitron_field_target_column_match` so the fact and the defect cannot disagree. A field with
      a path keeps `COLUMN_UNRESOLVED` over the column scope's `PATH_TERMINAL` rule until the node
      below. Pinned by `field-target-column`. When `CONSUMED` replaces the record and source-row
      rules, the column-giving set is "not `CONSUMED`, or the source key's rule is `RECORD`".
    * **The generator reads an `id: ID` without `@nodeId` as a column** `open`. Such a field is a
      column like any other, as the store reads it, with `COLUMN_UNRESOLVED` where none matches.
      The generator reads it as the enclosing node's id instead (the `Node.id` path in
      `FieldBuilder`), so eight sis fields on nesting types under node parents (the "TODO: Ta vekk"
      fields) build while the store refuses them. The generator reads the column, and refuses
      where there is none, as the store does.
    * **A scalar through a path reads where the path lands** `blocked`, on "The forward walk is a
      relation of its own": the path resolution is keyed by a target table, which a scalar does not
      have, so the landing is read off the forward walk once it exists.
    * **The editor shows information, not a classification** `open`, after the target column. A
      field's hover is a block of the facts a user wants, each a key lookup in a stored relation:
      what produces it, read from `graphitron_producer`, its source and the table its read starts
      from, its target type, wrapper and target table or column, its walked `@reference` links, its
      node identity from "The editor and the MCP read the resolved node", and its defects. A type's
      is its binding, its nodehood with type id and key columns, its backing class and its defects.
      The inlay hints, through `InlayFacts`, show the same facts in place of a classification, which
      is also why a `@reference` field drawing no classification hint stops being a question. Found
      2026-10-15: a field hover cost about 2.8 s in a live session over sis, and 224 ms a field over
      a copy of its store, against 18 ms over opptak and 0.05 s for the other hovers, because the
      classification block reads `intent_resolved_field_claim` and `intent_column_match_claim`. The
      column match ends in a `ROW_NUMBER()` that H2 cannot push the hover's coordinate into, the
      authored claim opens with a recursive CTE, and the resolved claim unions them under a
      correlated `NOT EXISTS`; whole, on sis, the two cost 306 ms and 376 ms, and the resolved claim
      43 s. A cost gate holds the block's statement to a bound over a large capture.
      * **A field's hover is its information** `done`. `FieldInformation` reads the signature
        and description, the column with its type and how it matched or "no column", the target
        type, wrapper and table, the source table and its basis in words, the target's node id and
        key columns, the methods its code references resolve to, the directives graphitron adds,
        the written path's length and the defects at the field, one statement over stored tables.
        Its first line is the fetch, what the fetcher runs from `graphitron_producer` or how it
        reads its parent's result from `graphitron_reader`. `EditorReadsNoViewTest` fails if a
        hover's or an inlay request's statement names a view, with go-to-definition, which still
        resolves through `intent_bound_table`, as its control; `FieldInformationHoverTest` pins the
        lines. On a 2026-10-17 sis store the statement takes 4.1 ms at the median and 28 ms at worst
        over 302 fields. Not yet: the walked route, which waits on the forward walk; and a method's
        doc comment, which `java_method_declaration` cannot answer by key.
      * **A type's hover is its information** `done`. `TypeInformation` reads the signature and
        description, the result the type holds from `graphitron_result` with a row of a table and
        its comment or an instance of a class, the node id and key columns, an `@error` type's
        handlers, the directives graphitron adds and the defects at the type's declarations, one
        statement over stored tables. The claim block and the description overlay go, and
        `ClaimFacts` with them; `TypeInformationHoverTest` pins the lines. A class's doc comment is
        not shown, `java_class_declaration` answering by file rather than by class.
      * **The inlay hints show the same facts** `done`. A type is labelled with the table whose
        rows it holds or the class of its instances, a field with its column, the method it calls
        or the table its statement lands on, and `@table` and `@field` fill in from
        `graphitron_resolved_type_binding`, `graphitron_field_target_column` and, for a field with a
        path, `graphitron_field_column_scope`'s terminal table. A type backed by a table's record
        now gets its `@field` name, a gap `InferredFieldHintsTest` had pinned. `LabelHintsTest`
        pins the labels.
      * **An inlay request walks only its window** `done`. The hints' SQL is fast on sis (6.7 ms
        median, 64 ms worst per 60-line window), but `InlayHints` walked the whole document on every
        request, `DeclarationKind.walkAll` and `Directives.findAll` over the root, and filtered to the
        visible range afterwards: a window in sis's 6,000-line `schema_exp.graphql` took 190 ms at
        the median and up to 2.7 s. The declaration walk now descends only into subtrees the range
        overlaps and the directive query is bounded to the range's rows, so on the same 2026-10-21
        sis store a 60-line window takes 10 ms at the median, 20 ms at p95 and 63 ms at worst, over
        489 windows. A request for a whole document still walks all of it. `RangedWalkTest` pins
        that a walk over a region leaves the rest of the tree alone.
      * **An omitted `@reference` path is filled in from the stored path** `blocked`, on the path
        discovery implies being a row of `graphitron_field_path`. The hint read
        `intent_field_reference_discovery` and is dropped until then;
        `InlayHintRendererCoverageTest` names `reference` as waiting.
    * **The claims go** `open`, after the editor. A claim is a field's row in the walk's classified
      vocabulary and its classifier the variant, whose precedence is the fetcher's fact under "Every
      field has one fetcher": `intent_authored_field_claim` restates the producing entries,
      `intent_column_match_claim` is the target column picked at read time, and
      `intent_resolved_field_claim` unions the two. Their readers, `ClaimFacts`, `InlayFacts`,
      `BindingUsages`, `SchemaQueries`, `DiagnosticFacets`, `AuthoredClaimConflicts` and the
      conflict rule, move onto the entries, the producers, the target and its subtypes, which drops
      the authored claim from "The readers move to the twin, the path and the applications"; the
      three go as retired vocabulary, and `AuthoredClaimTest` and `ColumnMatchClaimTest` go with
      what they pin rather than converting. The scope relations go under "The duplicate statements
      of a field's tables dissolve", which already holds them.
  * **The hand-built model tests dissolve** `open`. About fifty-five classes build the walk's
    classified model by hand through `TestFixtures`' made-up table and key refs, a `TableType` over
    `filmTable()` holding a `ColumnBackedField`, and run the walk's validator, renderers or plan
    lowering over it: no schema goes in and nothing is captured. Thirty-three are
    `validation/*ValidationTest`, one per field or type variant. They pin the records this section
    retires, and keep the walk's rules read by something other than production. A validation case
    becomes a fact document, a schema in and `graphitron_entry_defect_site` rows out, when its
    constraint is a located defect, and its class goes with the walk rule it pins; the renderer and
    lowering tests go with the records they build. First, each validation class is mapped to the
    store rule stating its constraint today, or to none.
* **`validate` reads only the store** `open`, as "Validate is a query over the store". Second to
  last, a verdict being a narrower read than an emission.
* **`generate` and `dev` read only the store** `blocked`, on the node above. The last step: the
  emitters read rows where they read classified records, and the walk and its model go.

### Validate is a query over the store, and runs against the model alone

`validate` opens the store, captures, reads its verdict from the store and closes it, which is what
`capture` does with a verdict added, and names nothing in the `graphitron` module. The verdict's
types and its formatter are already model-side, so what stands between here and there is the walk.

* **Each rule the walk enforces is a defect** `open`. A rule becomes a `graphitron_defect_type` code
  and an arm of `graphitron_entry_defect_rule`, which reaches the editor and stops no build, so the
  arms land ahead of the flip. The fact census lists the rules per fact. The eleven codes moved so
  far draw nothing on the sakila example; evidence in
  `roadmap/audits/2026-10-08-entry-defect-arms-on-sakila.md`.
  * **A malformed argMapping is one store verdict** `open`. Three parsers judge one grammar:
    `GraphQLSelectionParser` behind `ArgBindingMap`'s rejection, `ArgMappingSigil` around it at
    capture, and the language server's own `ArgMapping` behind `judgeArgMapping`. The parse's
    outcome is captured where the pair relation is, a parse being Java's and no view's to redo, and
    one branch of the defect rule reports a mapping that does not parse and one a parameter named
    twice, at the mapping string; the build's rejection and the editor's checks then read it.
  * **The `operation` rules** `blocked`, on the producers and their modifiers. 170 sites:
    `serviceCall` 52, DML 47, `condition` 28, lookup 16, `paginate` 12, `orderBy` 10, `select` 5. The
    verbs' rules move onto the producer hierarchy and the rest onto the modifiers keyed to it, the
    relation being stated before the rules over it rather than the rules written over the decoded
    entries and moved again.
    * **A list with no ordering source is a defect** `open`. Three anti-joins, no `@defaultOrder`,
      no `@orderBy` and no primary key on the target table; the population, which list fields
      generate SQL, is a generator classification the store does not state yet.
  * **The `joinPath` rules** `open`. 55 sites; three of them already codes.
  * **The `node` rules** `open`. 53 sites.
    * **A `@node` without `Node` is a defect** `done`. `NODE_WITHOUT_NODE_INTERFACE`, at the `@node`.
    * **A `@node` key the table cannot supply is a defect** `done`. `NODE_KEY_COLUMN_UNRESOLVED`
      names the first failing spelling; `NODE_WITHOUT_KEY` is no pinned columns, no metadata and no
      primary key, which are the three tiers `NodeKeyColumns` tries.
    * **Two nodes sharing a type id is a defect** `done`. `NODE_TYPE_ID_SHARED`, at each type's
      `@table`, off `graphitron_node`'s resolved id; stated by `facts/node-type-id.graphqls`.
    * **A pinned key disagreeing with the published one is a defect** `open`, after "Node metadata
      is a catalog fact, and an attempt that fails is a warning" (done): the published key is a
      catalog fact now, so the disagreement is a `graphitron_` rule over it. Nothing states it today.
  * **The `tableExpr` rules** `open`. 24 sites.
  * **The `sourceObject` rules** `open`. 23 sites.
    * **A `@table` naming no table, or one two schemas declare, is a defect** `done`.
      `TABLE_UNRESOLVED` and `TABLE_AMBIGUOUS`, counted off `graphitron_spelled_table`; a graph
      captured with no catalog is judged on nothing, on the code-reference arm's terms.
  * **The `reference` rules** `open`. 20 sites.
  * **The `accessor` rules** `open`. 19 sites.
    * **A field reading a column its table lacks is a defect** `done`. `COLUMN_UNRESOLVED`, over
      `graphitron_field_column_scope`'s parent binding and a `@reference` path's terminal, folded
      the way the walk folds; stated by `facts/column-unresolved.graphqls`.
  * **The `errorGuard` rules** `open`. 16 sites, over a fact only `intent_` states.
  * **The `discrimination`, `source`, `referencedTable`, `target`, `enum` and `resolvedTable`
    rules** `open`. 31 sites between them.
    * **A field on the subscription root is a defect** `done`. `SUBSCRIPTION_FIELD`, resting on
      `UNSUPPORTED`, at the field's own declaration; stated by `facts/subscription-field.graphqls`.
  * **The rules on directives R333's catalog names no fact for** `open`. `@scalarType` binding,
    tenancy and federation, 25 sites; rules all the same, over the relations that state them.
    * **A `@scalarType` naming no constant is a defect** `done`. `SCALAR_TYPE_REFERENCE_MALFORMED`,
      `SCALAR_TYPE_CLASS_NOT_READ` and `SCALAR_TYPE_CONSTANT_NOT_FOUND`, against `code_class` and
      `code_scalar_constant`, stated by `facts/scalar-type-reference.graphqls`.
    * **A `@scalarType` on a specification scalar is a defect** `open`. The case is written as an
      extension of the scalar, which a capture takes since the specification became its base.
  * **A name the generated code needs unique is a rule like any other** `open`. Type names folding
    to one file name, colliding launcher and projection-unit names: `GRAPHITRON`, an author renames.
    * **Two type names differing only in case is a defect** `done`. `TYPE_NAME_CASE_COLLISION`,
      over `graphitron_type` so minted types count, at each authored type's base declaration.
* **A consequential rejection is not a rule** `done`, by the defect model. A violation sits once,
  on the fact that broke, and an arm reads only coordinates whose upstream facts hold.
* **A refusal of what capture injected reaches the author** `open`, first: a regression. Reported
  2026-10-15. The emitted schema's assembly, `GraphQLRewriteGenerator.acceptedSchema`, throws
  `ValidationFailedException` on declarations the mint and the configuration injected. The dev
  log prints a count and a stack trace, and nothing reaches `graphql_schema_problem` or
  `diagnostic`, so no surface lists the refusals. They are recorded where the written schema's
  refusals are, and the log names each one.
* **What the generator cannot emit is judged as the schema** `done`, by the defect model's basis. A
  generated name colliding is `GRAPHITRON`, an emission not written yet `UNSUPPORTED`; the generator
  runs only on a schema that validates.
* **Node-id decode coverage is stated from the store** `open`. It is the one verdict that subtracts
  the walk's ledger from the store's census, so it is restated over store facts or it goes.
* **The verdict reads what the editor reads** `blocked`, on the rules being defects and on "Entry
  defects fail validate", without which entry defects fail builds the generator accepts.
* **`validate` reads as its steps** `blocked`, on the verdict. `GraphQLRewriteGenerator.validate`
  and the `VALIDATE` projection go, and with them the plugin's last use of the generator outside
  `generate` and `dev`.

### `generate` is a function of the store and the emitted schema

Capture builds both schemas: the schema as written, and the emitted schema the minted rows make of
it. `generate` renders the emitted schema as SDL and as the schema classes and wiring, and the
datafetchers and the rest from the store. The emitted schema is what is published, decided in
capture, so an emitter prints or renders it and asks nothing else. The walk is not refitted to read
it: the walk keeps the input it has and goes when nothing reads what it builds.

It is a function onto the output directory rather than a pipeline: after a run the directory is
fixed by the store, the emitted schema and the run's configuration, a file whose content
would not change is left alone and a file no longer produced is swept. Writing, that comparison and
the sweep are how it delivers its result. What it does not do is fetch or build its inputs: it opens
no store and captures nothing, which is the generate mojo's and the test harnesses' orchestration,
reads nothing off the classpath that capture has already read, and builds no schema of its own.

* **The generator reads the documents once** `done`, through capture. `ModelCapture.capture` hands
  back its reading, carrying the parser's failures and the loading rewrites' outcome, and
  `GraphitronStore.captured` returns it beside the store as a `CapturedGraph`.
  * **A dev round whose capture was skipped still has a schema** `done`. The skip yields: the
    session's generator factory captures first.
  * **`AttributedRegistry.load` dissolves** `done`. Its cases dissolved into what the capture path
    pins, configured tags and notes are read off the emitted schema, and
    `CapturedSchemaAgreementTest` and `EmittedRegistry`'s overloads over `AttributedRegistry` went
    with it.
* **Capture hands back the emitted schema** `done`. `EmittedRegistry` runs in capture and
  `CapturedSchema` carries both; the generator renders the emitted one and reads the written one
  only for the walk.
  * **The emitted schema is derived right after the anchor** `done`, the macros being one mint. The
    connection and facet mint, key synthesis and tag inheritance are one mint by `EmittedAnchor`
    into the `graphitron_` anchors; the nodes below state each part.
    * **Which types are nodes is settled in the anchor** `done`. `TableTypes.derive` and
      `Nodes.derive` read nothing written after it, and run there.
    * **The anchors hold directive applications** `done`. `graphitron_directive_application` and its
      arguments, at the coordinate, carrying the authored ones across from `graphql_` beside the
      minted, which carry no position.
    * **The federation key is minted by `EmittedAnchor`** `done`, over the `ast_` link and key entries
      joined to the honoured application, and `EmittedRegistry` renders minted applications without
      knowing which macro wrote them.
    * **Tag and `@shareable` inheritance is minted by `EmittedAnchor`** `done`.
      `graphql_source_input` records what each file's entry configures, `graphitron_configured_tag`
      states the tag rule over it, and `graphitron_inherited_directive` is the intersection over the
      carriers.
      * **The inheritance reads stored carriers** `done`. `graphitron_configured_tag`,
        `graphitron_minted_coinage` and `graphitron_carrier_directive` are stored by `EmittedAnchor`
        from `_rule` views; `roadmap/audits/2026-10-09-federation-chain-hang.md`.
    * **The rule reads no deprecated decode** `done`. The key and link entries it read are retired;
      see "No `graphitron_*_entry` survives".
    * **`EmittedRegistry` renders the anchors and states no rule** `done`. It patches the anchored
      elements and applies the minted applications, and the narrowing it reports reads the rule's
      output.
* **The datafetchers and the rest are generated from the store** `open`, one emitter at a time in
  "Each emitter reads the store"; what stays here is the grains those emitters query at.
  `EmitPlan` reads the store already; the per-type generators still take the walk's model. What they read is a few queries,
  each at the grain the generated code is organised by, found by cataloguing the sakila example's
  977 generated files: a table-bound type's projection is one method reused wherever the type is
  reached, `Film.$project` at 94 call sites with nothing in its signature naming the usage; a
  field's datafetcher is one method per field, and how a type is reached is written once per field
  that reaches it; and nearly every statement is one shape, a type's projection selected for a list
  of keys joined as `VALUES(idx, key)` and scattered back by `idx`.
  * **The type grain: a table-bound type's projection.** One row per type and field that is not a
    producer, saying how the field projects: a column; a node id from key columns; a scalar or an
    object reached through the chain, correlated, and a multiset for a list; a `@lookupKey` list; an
    inline routine; a pivot; an external field; or a nested type without a table of its own,
    projected against its owner's table. Such a type is still one class named for it, its
    `$project` overloaded once per table it is projected against, so the grain stays the type and a
    row names the table as well; for a table-bound type that table is its own. Beside them, the key
    columns each producer field below the type needs, which is the producer's source read from
    the parent's side.
  * **The field grain: a field's datafetcher.** Whether it executes or reads. A reading field names
    the column, the computed value or the property it reads. A producer names its kind, whether it
    mutates, the type it lands on and the cardinality, its source, and the conditions, ordering
    and paging its arguments give.
  * **The keyed selection: a type's projection for a list of keys.** The one shape behind
    `@splitQuery`, `@lookupKey`, `node` and `nodes`, the re-read after a service or a write, a
    payload's data field and the second stage of a polymorphic query. What differs is the key
    source: the parent row's columns, the arguments, decoded node ids, the records a service
    returned, the keys a write returned, a payload's carried record through an accessor or
    `@sourceRow`, or the first stage's keys; which is the source.
  * **How a field projects is stated** `blocked`, on the producers, a projection field being any
    field that is not one.
  * **Subtract what the producers replace** `blocked`, on the emitters that read them. Each relation the new ones replace goes once its readers
    read the replacement:
    * `intent_field_separate_fetch` `done`, 2026-10-20. The editor's inlay marker reads the
      producers below a root, and the view, `SeparateFetchRule` and its vocabulary test went; the
      agreement with the walk's delivery is `ProducerDeliveryTest`, and the fact documents are
      `producer-out-of-the-parent-statement` and `producer-root-by-binding`. A record-handed
      parent's table child is a `LOOKUP` there, the payload's re-read, where the view called it its
      own rule;
    * whatever the generator's own classification stops being asked.

    The producer-method views, `intent_field_producer_method` and its neighbours, are the reference
    branch's to retire, through `graphitron_code_reference`, which the service producer joins to.
* **The generator builds no schema** `blocked`, on every node of "Each emitter reads the store": the walk goes when nothing
  reads what it builds, and `KeyNodeSynthesiser`, `ConnectionPromoter`'s expansion and what is left of
  `AttributedRegistry` go with it. R289, which corrects `KeyNodeSynthesiser`'s javadoc, dissolves
  with them. Handing the walk the emitted schema instead was probed and set aside: the walk infers
  from its own expansion in more places than it names. A hop-less `@routine` read binds its return
  type to the routine's result, and `RecordBindingResolver` binds an accessor's record to a field's
  return type, both reading a carrier's return type, which is the connection in the emitted schema
  and the element in the written one; and the `@asFacet` check reads the walk's own directive-driven
  connection rows, which the emitted schema leaves it none of. Fixing those would fit the walk to an
  input it is about to lose.
* **Its inputs are values** `open`. A `StoreHandle`, the `CapturedSchema` and the run's
  configuration; not `CapturedGraph`, which owns the store and its lifetime, so the constructor that
  takes one goes.
* **The configuration is read from the store** `open`. Capture transcribes the run's configuration
  into the `store_graph_` relations, and that was the add; the flip never happened. The generator
  still reads the tenant column (`BuildContext`), the lint configuration and the session-state
  methods (`GraphQLRewriteGenerator`) and the output coordinates off `RunContext`, and the supergraph
  name is read by nothing. Only `store_graph_lint_excluded_type` has a reader in the store, the lint
  view. The relations without one are held, not dropped: they wait for this flip.
  * **The readers move onto the store** `open`, one setting at a time.
  * **`RunContext` stops carrying what the store states** `blocked`, on the readers.
  * **The configuration family is pinned by fact documents** `done`. A document states its
    configuration with `@withConfiguration`, and `@assertEmpty` says a relation holds no row for its
    graph; `configuration`, `configuration-none` and `configuration-sweep` pin landing, nothing
    declared and the sweep, and `StoreEntriesTest` keeps the recipe and the sibling-graph case.
* **Configured tags have one producer** `done`. `TagApplier` is gone: the emitted schema applies
  `graphitron_configured_tag`'s `CONFIGURED` rows as it applies the `MINTED` ones, at any coordinate
  kind, and the written schema carries no configured tag. Pinned by `ConfiguredTagTest` and the
  federation pipeline test.
  * **Enum values are anchored** `done`. `graphitron_element` admits them and
    `graphitron_enum_value` is their part relation, so the tag rule states their tags and a reader
    reaches an enum value's type and name by a join.
  * **The schema classes read the configured tags off the emitted schema** `done`. The walk's
    connection types inherit only authored tags, so `CarriesObjectForm.formOf` reads the schema's
    type first, and the enum and input classes render the schema's forms.
* **The configuration's rewrites leave the composition** `open`, so the written schema is what was
  written.
  * **`FederationLinkComposer` leaves the composition** `blocked`, on the generator building no
    schema. It replaced `TagLinkSynthesiser`, and rewrites the author's link with the settled imports
    or adds graphitron's own. The written schema needs no `@tag` declaration now; the walk's federation opt-in is the one reader
    left, and the emitted schema carries the link today only because it starts from the composition.
    When it goes, `EmittedRegistry` applies the link and the definitions it imports for a
    `CONFIGURED` row of `graphitron_federation_link`, so the emitted schema keeps saying it is
    federated.
    * **The opt-in is one relation** `done`. `graphitron_federation_link` states whether and how a
      graph is federated (an authored federation `@link`, or a configured tag where none is
      written), `graphitron_federation_link_import` what it imports, both marked and swept; the key
      mint reads it, and `graphql_assembly_synthesised_link` is gone.
      * **The federation link is decoded at capture** `done`. `graphitron_ast_federation_link_entry`
        states which `@link` applications name the federation specification and the version, so no
        view filters urls with a literal.
    * **A configured tag the authored link does not import is a detection** `open`, over the
      relation, where it is a refusal of the composition today.
  * **`DescriptionNoteApplier` follows** `done`. The anchor mints each element's emitted
    description, the document's own in `graphql_` plus the configured note from
    `graphql_source_input`, into the `graphitron_` part relations' `description`;
    `ConfiguredNoteTest` checks the part relations beside the emitted schema.
    * **The part relations are read by their columns** `done`. `EmittedRegistry` reads type, member
      and argument from the four part relations rather than taking coordinates apart, and an
      element's description from its part relation where the anchor minted one.
* **The vocabulary is emitted and minted** `open`. "Synthesis" named code that edited a registry in
  place; what graphitron adds to the schema is minted, by a rule over the store, and the schema
  capture derives is the emitted schema. `CapturedSchema.synthesised` becomes `emitted`, with the
  javadoc and tests that say "post-synthesis", and what is left of the old word goes with the code
  it names.
  * **The federation key is one rule of the mint** `done`. The synthesized-key view dissolved into
    `graphitron_directive_application_minted`: the node types of a federated graph with no authored
    id key, read from `graphitron_node`, so a `@node` that is an error is not keyed; its constant
    arguments are rows of `graphitron_directive_application_arg_minted`.
    * **A configured link mints no key it does not import** `done`, a regression. A configured link
      imported `@tag` alone while the mint put `@key` on every `@node` type, refused as undeclared.
      Closed by "The effective imports are graphitron's rule": the link imports what the mint
      writes.
  * **A link's imported definitions are captured where the link is** `done`. The editor judges
    directives against `graphitron_directive` and `graphitron_directive_argument`, the written
    directives plus the federation definitions the link brings in under the names the library gives
    them, read from `federation_version_definition`.
    * **The MCP lists the composed vocabulary** `open`. `DirectivesResource` still reads
      `graphql_directive`, because it lists locations too and graphitron's vocabulary carries none;
      a `graphitron_directive_location` beside the other two closes it.
  * **`graphitron_synthesized_node_type_id` is named in the mint's vocabulary** `open`, its author's
    to rename.
* **Federation is a gatherer of its own** `open`. Federation's facts are neither the SDL's nor
  graphitron's: what a federation version defines is federation-jvm's, and what a document's `@link`
  and `@key` say is decoded today by graphitron's walk. A `federation` gatherer runs between the
  graphql gatherers and graphitron, in a `federation_` family, reading `graphql-source` and
  `graphql-ast`; `graphitron-ast` reads it. What depends on graphitron's mint, the imports a link
  ends up with, stays graphitron's.
  * **Federation's versions and their imports are captured** `done`. `federation_version` holds one
    row per spec version the library supports, keyed by url, and `federation_version_import` one row
    per name its link may import, marked and swept under one `LIBRARY` `store_source` row stamped by
    the jar's content. Pinned by `federation-versions` (v2.0 to v2.15) and `FederationCaptureTest`.
  * **The effective imports are graphitron's rule** `done`. `graphitron_federation_link_import` is
    the authored imports plus each MINTED or CONFIGURED directive the link's version may import,
    written after `EmittedAnchor`. `FederationLinkComposer` replaces `TagLinkSynthesiser`, rewriting
    the author's `@link` or adding graphitron's own from the settled link, so `ModelCapture` runs
    the assembly after `GraphitronAstCapture`.
  * **A minted directive its version cannot import is a defect** `open`, after "The effective
    imports are graphitron's rule". The rule leaves such a
    directive out of the imports, so the assembly reports it undeclared, which reads as the
    author's fault. It is graphitron's own: a defect row naming the directive and the version.
  * **The link decode and the opt-in are federation's** `open`, after. The `@link` and import
    entries leave `GraphitronSchemaEntries` for the gatherer, `graphitron_federation_link` becomes
    `federation_link` in its anchor, and the configured opt-in keeps reading
    `graphql_source_input`.
  * **The key decode is federation's** `open`, after. The three `@key` entries leave
    `GraphitronTypeEntries`; the mint's authored-id-key condition reads them from upstream.
* **The assembly is a step of the anchor** `open`. `graphql-assembly` is a gatherer by name only. It
  captures no corpus: it reads the documents the source reading already holds, and what it writes,
  the assembly rows of `graphql_schema_problem`, is graphql-java's and federation-jvm's verdict over
  them, which is a derivation. `ModelCapture` runs it before the graphitron decode, but nothing in
  between reads it, and once the link it hands federation-jvm imports what the anchor mints it reads
  the anchor. Nothing found argues for a gatherer of its own.
  * **The assembly runs as the anchor's last step** `open`, after "The effective imports are
    graphitron's rule", which makes the composition read the anchored link. `GraphQLAssemblyCapture.gather`
    becomes the step after `EmittedAnchor` in the graphitron-ast anchor, handing `ModelCapture` the
    same reading. Its `store_graph_progress` row, its `meta_gatherer` row and the edges naming it
    go, and `graphql_schema_problem`'s owner becomes the anchor's gatherer. The comment on the
    `('graphitron', 'graphql-assembly')` edge is stale already: the configured opt-in reads
    `graphql_source_input`, not the composition.
* **Capture's added cost is measured** `open`. Capture now also derives `graphitron_configured_tag`
  and `graphitron_inherited_directive` and builds the emitted schema, `graphitron:capture`
  alone included, and the dev loop's classpath-change and quiet-startup rounds now capture too. The
  builds stayed green and in their usual time; the relations themselves are untimed.
* **The jOOQ catalog is read from the store** `open`. Capture has read it, and the generator loading
  it by reflection is the same second read the documents had. The generator's classpath census goes
  with `CompletionData`, which was all it fed.
* **One entry point** `blocked`, on "Validate is a query over the store" and on the dev loop reading
  what it needs from the store. The four projections over one body collapse into the one function.
* **`graphitron` does no orchestration** `open`. Opening a store and capturing are the generate
  mojo's and the test harnesses'. A harness in `graphitron`'s tests that captures and reads nothing
  of the generator moves to the model's test fixtures, and a test there that exercises capture alone
  moves with it.
  * **`PipelineCapturedStore` folded into `CapturedStore`** `done`. A tagged arm and the two schemas
    each arm hands back, so the walk-versus-store cases derive the walk's input themselves, and
    `TaggedCaptureStampTest` moved to the model.
* **The anchoring marks and sweeps** `open`. A clear and refill deletes before it inserts, so every
  relation keyed into the cleared one with a cascade is emptied each reading and correct only because
  a later stage happens to rebuild it; the element anchors, `GraphitronAnchor` and
  `GraphitronCodeReferences` already stamp each row with the reading and sweep the rest.
  * **Directive applications are marked and swept** `done`. `graphitron_directive_application` and
    its arguments carry the reading's stamp, are upserted on their keys, and the stale arguments then
    the stale applications are swept.
  * **Minted conflicts are marked and swept** `open`. Nothing keys into
    `graphitron_minted_conflict`, so this is the discipline rather than a hazard.
  * Table types, nodes and node key columns: see "Table types, nodes and node key columns are marked
    and swept" under "A node is settled with the decode that states it", which takes the three
    together.

### An emitter reaches a fact one way, absorbed from R682

R682 ("planners read facts, emitters read commands") is absorbed here, 2026-10-17. Today a fact can
reach an emitter two ways, joined onto a command row or read off the leaf the emitter holds, and
nothing forbids the wrong one. The gate is behaviour: the execution tests stay green, a change that
reaches something they do not cover adds the coverage first, and the validator's message, location
and severity stay unchanged. Generated output is not held byte-identical, and no test reads generated
Java or SQL as text. Its validator half is "Validate is a query over the store" and its terminal step
is "The generator builds no schema".

* **The emitter architecture is examined against the store** `done`, decided with Alf 2026-10-17.
  An emitter owns its query and its emission: it reads the store with jOOQ at the grain of what it
  emits, one query per emitted unit rather than per field, and renders from the result rows. There
  is no command layer between them. Evidence in `roadmap/audits/2026-10-17-emitter-owns-its-query.md`.
  * The command was a Java value, never a store row, and the planner that already reads the store
    copies its fact rows field for field into a near-identical record; that copy is the layer.
  * Planning splits by the nature test: a verdict (which rule fired, which table stands for a type)
    is a fact anchored at capture, and joining and shaping for one emitter stay in that emitter's
    query.
  * R549's `command` and `plan` packages dissolve into the emitters that read them, and `render`
    merges with its queries. `CommandSeamRatchetTest` keeps its leaf pins until the leaves go with
    the walk; its pin on `plan` goes with `plan`.
  * Testability is held by behaviour, not by a seam: execution against PostgreSQL, the in-process
    compile harness, and fact documents to reach a rare state, which replace a hand-built command.
* **No test reads generated SQL as text** `done`, 2026-10-18. A test asserts the rows returned and
  their order, the database effect, and the round trips of a batched child; a root field's round
  trips are not pinned. Of the 66 baseline tests, 39 went against a covering test and 27 became
  behavioural checks in classes named for what they test; about 45 stray reads went the same way.
  Three fixture rows make observable what only the text had shown: a discriminator value outside
  the type, a key differing in one column, a default against an explicit null. What no caller can
  observe went with nothing in its place.
* **No test reads generated Java as text** `open`, ahead of each conversion for the emitter it
  converts. About 495 assertion lines in 30 `graphitron` test files read generated bodies as text,
  most in `TypeFetcherGeneratorTest`, `GraphitronSchemaClassGeneratorTest` and
  `FetcherPipelineTest`; the compile and execution tiers replace them. `testing.adoc`'s "Renderer
  arm tests" section, which still names hand-built command tests the preferred home, is rewritten
  with it.
* **The recompile graph has a source once commands are gone** `open`. `PlanCompileGraph` is the only
  reader of `FetcherEdgeRelation` and is how the dev loop knows which file to rebuild; it needs the
  same answer from the store.
* **Routine writes read only the store** `open`, in two steps: the command layer goes, then the
  last two facts it takes from `GraphitronSchema` move to the store.
  * **The command layer goes** `done`. `RoutineWriteEmitter` in `no.sikt.graphitron.emit` renders from
    `RoutineWriteQuery`'s rows, still three statements for the run, and is handed the error channel
    and the tenancy as values; the command, its relation, the copy and the dead tenant routings went,
    about 1,500 lines net. An import rule keeps `emit` off `GraphitronSchema`, the leaf model and the
    command triangle; nine named `render` helpers are its only exceptions, each a shared spelling.
    * **What only the command tests describe runs against PostgreSQL** `done`. A chain over two
      hops and one narrowed by a hop `condition:`, a single-valued chain, a list-valued carrier, a
      composite captured key in both forms, a multi-tenant routine write to a global table, and one
      coordinate mixing an authored and an inferred node-id projection, each a sakila routine.
    * **No test reads its generated Java as text** `done`, but for `TenantAcquisitionFragmentsTest`,
      which goes with the fragments it reads. Each deleted case was covered by an execution test,
      two of them only once the node above landed.
  * **`emit` imports no `render` class** `open`. The import rule's nine exceptions,
    `ArgPathHelperRegistry`, `ProjectedKeyReads`, `RoutineCallEmitter`, `CatalogRefs`,
    `ColumnComparison`, `ErrorDispatchFragments`, `FetcherResult`, `TenantHandDown` and
    `ProjectionCall`, are one-spelling helpers old and new emitters share. They move into `emit`, the
    old renderers import them from there, and the rule's exception list is empty, so `emit` stops
    depending on a package that is emptying.
  * **The error-mapping constant is a store fact** `open`, after the channel below. The store states
    which producer catches and into which errors field, not the constant's name: the name deduplicates
    handler lists and the plain name goes to whichever channel was classified first, an order the
    store does not state. `ErrorMappingsClassGenerator` mints the same names, so both read one rule.
    * **The channel is a producer's, and its types tie to their applications** `done` for the
      channel, 2026-10-21: `graphitron_producer_outcome`, keyed by the producer, naming its
      `graphitron_errors_field`, whose container's members in order are the `@error` types, under
      "A producer's outcome is stated"; `intent_field_error_channel` and `intent_errors_field_member`
      went with it, and `intent_errors_field` with the carrier rule reading the errors field.
      Tying each type to its `graphitron_ast_error_entry` is open: a type with two `@error`
      applications, which assembly refuses but capture still sees, puts no channel through that type.
    * **The constant is named by its producer, and the dedup goes** `open`, after the channel. A
      channel's constant is `concat` over its types' `ByType` arrays, and the dedup exists only to
      share that between channels of one shape; naming by producer drops it, the hash and the
      first-classified rule.
  * **The routine write's tenancy is a store fact** `open`, after. No relation binds a coordinate to
    its tenant or states the tenant key's type. A tenant-scoped routine write is rejected, so of the
    renderer's four routings only the unrouted one and the default source are reachable; the
    inherited and argument-bound ones are dead.
* **The launcher's routine row reads the facts R704 captured** `open`.
  `LauncherCommands.routineRow` reads the walk; R704's facts are in the store and nothing here reads
  them.
* **Operation members and delivery are read from their relations alone** `open`. R563 added `OperationMemberRelation` and `DeliveryFactRelation`, computed in
  `GraphitronSchemaBuilder`, beside the leaf crosswalk `OperationMembers.membersOf` and
  `DeliveryFact.leafDerivedOf`; `OutputField`, `ColumnBindingLedger`, `TenantBindingIndex`,
  `ConditionCommands` and `ProjectionCommands` still read the crosswalk, and
  `LauncherCommands.produceWithoutSchema` is a second launcher production beside `produce`. The
  readers move and the productions merge, then the crosswalk and `OperationMemberMintPinTest` go.
* **No emitter reads a leaf** `blocked`, on the three above and the families after them.
  `CommandSeamRatchetTest` counts 66 leaf `instanceof` sites and 57 case patterns in the generator
  and 140 leaf references in `plan`; done at zero, which is what "The generator builds no schema"
  needs from this branch.

### Each emitter reads the store

The generator turns over one emitter at a time. An emitter owns its query and its emission, decided
in "The emitter architecture is examined against the store", so each moves on its own, held to
behaviour: the execution tests stay green, and where they are thin for what it emits, coverage comes
first. Each node names what the emitter reads today; the tests that cover it are written into the
node when it is taken up. A conversion waits on "No test reads generated SQL as text", and on "No
test reads generated Java as text" for the emitter it converts. Retires "The classified model beside
the store" and "Command rows beside leaves" in the register, one emitter at a time.

* **The generated code is tested where it is generated** `open`, decided with Alf 2026-10-21. Each
  emitter's flip is held to behaviour, so the behaviour of what it emits must be cheap to pin and
  quick to rerun from the generator module, against a real database. Today it is pinned in
  `graphitron-sakila-example`, behind the Maven plugin and a full reactor hop, and the generator
  module's own tests stop at the emitted source.
  * A launcher in `graphitron` main captures a store and generates for one schema, the three calls
    `CapturedGenerator` makes, without the plugin and so without the language server or the MCP
    server.
  * `graphitron`'s `generate-test-sources` runs it over the fixture schemas into
    `target/generated-test-sources`, one output package each: the model's fact documents, read by
    path, and any schema the module keeps for itself. Directives graphitron does not recognise pass
    through, so a fact document's expectations need no handling.
  * The tests are plain JUnit calling the generated fetchers and rows methods directly, typed, and
    checked by the compiler against what was emitted, tagged `@ExecutionTier`.
  * One PostgreSQL per test JVM: the one `test.db.url` names, else `postgres:18-alpine` in
    Testcontainers seeded from the sakila `init.sql`. Never mocked. The tests are resilient rather
    than isolated: no rollback, a test that writes reads back only rows under keys it chose, and
    nothing asserts on a table's row count.
  * First consumer: a child `@service` catching into its outcome, success, a mapped throw, an
    unmapped throw and a throw during key extraction, over film rows from the database.

  Generating inside `graphitron` makes its test compilation all or nothing: an emission that does
  not compile stops every test in the module. Accepted for the tighter loop; if it bites, the
  generation moves to a lean sibling module that runs the same launcher.
* **The fixed runtime classes** `done`, by construction: they read nothing from the model.
  `GraphitronValues`, `LightFetcher`, `OneOfDirectiveSdl`, `PolymorphicSelectionSet`,
  `SelectionOccurrences`, `OrderByResult`, `GraphitronContextInterface`,
  `GraphitronTransactionProvider`, `ConstraintViolations`, `GraphitronClientException`,
  `ErrorRouter`, `Outcome`.
* **The configuration-read runtime classes** `blocked`, on "The configuration is read from the
  store". `ConnectionResult`, `ConnectionHelper`, `ConnectionRuntime` and
  `GraphitronConnectionInstrumentation` read only the tenancy and the session hooks, both taken off
  `GraphitronSchema`.
* **`NodeIdEncoderClassGenerator`** `open`. Reads `GraphitronSchema`.
* **`EntityFetcherDispatchClassGenerator`** `open`. Reads `GraphitronSchema`.
* **`QueryNodeFetcherClassGenerator`** `open`. Reads `GraphitronSchema`.
* **`ErrorMappingsClassGenerator`** `open`. Reads `GraphitronSchema`.
* **`GraphitronFacadeGenerator`** `open`. Reads `GraphitronSchema` and the federation link.
* **`GraphitronDevExecutorGenerator`** `open`. Reads `GraphitronSchema` and the session hooks.
* **`FetcherRegistrationsEmitter`** `open`. Reads `GraphitronSchema` and the schema-shape rows.
* **`InputRecordGenerator`** `open`. Reads the walk's input type for each row `InputRecordPlanner`
  selects from the store.
* **`ConnectionFetcherClassGenerator`** `open`. Reads the walk's connection type.
* **The type generators read the emitted schema** `open`. `EnumTypeGenerator`, `InputTypeGenerator`
  and `ObjectTypeGenerator` take their forms from the emitted schema, `CarriesObjectForm.formOf`
  reading it before a variant's own form, so the walk's own forms render nothing; the variants still
  carry them, and `ObjectTypeGenerator` still takes `GraphitronSchema`.
* **`SchemaSdlEmitter` prints only the emitted schema** `done`. It takes the emitted schema alone
  and prints it whole, its form following the schema's own federation `@link`.
  * **The emitted schema holds only what is published** `done`. `EmittedRegistry` takes out the
    directives the bundled vocabulary defines, definitions and applications, and the support types
    no type outside the support set references, so the printer filters nothing.
    * **`CompletionData` dissolves** `done`. The editor reads the store, and capture reads the
      classpath into the `code_` family.
      * **`TypeBackingShape` dissolves** `done`. The store states a type's backing in
        `graphitron_type_backing`.
  * **The print form follows the schema's own link** `done`. `SchemaSdlEmitter` prints the service
    form where the schema applies the federation `@link`, authored or added for a configured tag.
  * **The SDL emitter's tests read the printed schema, not its text** `done`. `SchemaSdlEmitterTest`
    and `SchemaSdlEmissionTest` re-parse the file and assert on its definitions.
* **`GraphitronSchemaClassGenerator`** `open`. Reads `GraphitronSchema`, the emitted schema and the
  schema-shape rows.
  * **The schema class registers what the emitted schema and the store state** `open`.
    `GraphitronSchemaClassGenerator` takes the roots, whether there are nodes, the scalars, the error
    types and the federation entities from the walk's types.
* **`ConditionGlueRenderer`** `open`. Reads `ConditionCommands`' rows, which read `GraphitronSchema`.
* **`ProjectionUnitRenderer`** `open`. Reads `ProjectionCommands`' rows, which read `GraphitronSchema`.
* **`TypeFetcherGenerator`, by family of field** `open`. Reads `GraphitronSchema` and the launcher,
  fetcher-edge, routine-write and key-projection commands, and dispatches on about forty kinds of
  field. The kinds are the walk's vocabulary, so the children are families of field rather than
  kinds, each querying at the grains "The datafetchers and the rest are generated from the store"
  catalogues.
  * **Root queries over tables** `open`. `QueryTableField`, `QueryNodeField`, `QueryNodesField`.
  * **Services** `open`, at the root and below it. `QueryService*`, `MutationService*`,
    `ServiceTableField`, `ServiceRecordField`.
  * **DML writes** `open`. `DmlTableField`, `MutationDmlRecordField`, `MutationBulkDmlRecordField`.
  * **Routine writes** `open`, as "Routine writes read only the store".
  * **Projected child fields** `open`. `ColumnBackedField`, `ColumnBackedReferenceField`,
    `TableField`, `ParticipantColumnReferenceField`, `SingleRecordIdField` and its returning form,
    `NestingField`, `RecordReadField`, `RecordCompositeField`, `ComputedField`. A nested type without
    a table becomes one class with an overload per owning table, where the generator emits one class
    per owner today: `CustomerOccupantLocation` and `StoreOccupantLocation` are one body for
    `OccupantLocation`.
  * **Batched fetches** `open`. `BatchedTableField`, `BatchedTableInterfaceField`,
    `BatchedInterfaceField`, `BatchedUnionField`. Key forwarding is read here: a `@nodeId` through a
    foreign key reads the key column without a join, a batched single object forwards the parent's
    foreign key, and a path of several hops forwards the first hop's.
  * **Polymorphic fields** `open`. `QueryTableInterfaceField`, `QueryInterfaceField`,
    `QueryUnionField`, `TableInterfaceField`, `InterfaceField`, `UnionField`.
  * **Pivots** `open`. `PivotField`, `PivotSlotField`, `BatchedPivotField`.
  * **The errors field** `open`. `ErrorsField`.

### A dev round costs what its reads' shapes ask

Profiled on sis in `roadmap/audits/2026-10-19-where-a-dev-round-spends-its-time-on-sis.md`.

* **The federation link's added imports are driven from the version** `done`. The rule met every
  directive application against every claimed source before the version filtered them, minutes a
  round on sis; `FederationLinkTest` pins its answer.
* **A rule's plan probes an index wherever it looks a row up** `done`. `RulePlanGateTest` reads
  every rule's plan as its stage reads it, over a scaled capture, and fails on a lookup reading a
  whole partition per row: a table scanned in a join, or an index probed with only `graph_name` or
  `source_name` bound. A missing index never shows as a scan, every table having one that leads with
  its partition. Small tables are exempt by name; dropping either index that fixed the coordinate
  and `sql_constraint` regressions fails it. The federation imports' join order does not show in a
  plan and is the scaling gate's.
  * **The partition reads known when the gate landed are indexed or exempted** `open`. Eleven, listed
    in `RulePlanGateTest`, in the entry defects, the source and path tables, the carrier and coinage
    directives, and the federation imports.
* **A capture's time grows in proportion to its schema** `open`, after the plan gate. A grouped
  table rebuilt per row, the producer rule's 172 s, shows in no plan. A synthetic schema at two
  sizes, the same shapes at n and 4n fields, is captured and each stage timed; a stage whose time
  grows more than about six times fails as superlinear. A ratio rather than a budget, so machine
  load does not move the verdict.
* **Every stage's time is recorded in the store** `open`. `store_graph_progress` records each
  gatherer; each stage's insert or merge is recorded beside it, so every capture and dev round
  shows its slowest stages, and MCP status names them. Not a gate, but a regression is seen on the
  next sis run instead of when someone profiles.
* **A stage table's graph filter cannot spoil its rule's plan** `open`.
  `graphitron_field_source_table`'s `MERGE` filters its rule to the graph, and the pushed-down
  filter turns 0.7 s into 24.
* **The tag narrowing joins the coinage to its minted type before the fields** `done`. In any other
  order the four-way read in `EmittedRegistry.narrowings` takes seconds for a few hundred rows; the
  pair is fenced by `OFFSET 0`, and `EmittedRegistryTest` pins its answer.
* **The walk's catalog lookups are not paid every round** `blocked`, on "The classified model
  dissolves into the store". `JooqCatalog` scans every table by reflection per lookup, about 8 s a
  round on sis.

### A capture reads only what changed

* **A schema edit re-anchors only what the edited document feeds** `open`. One comment line reruns
  every gatherer, the catalog's and the classpath's included.
* **A classpath change reruns only the gatherers that read the classpath** `open`, and a round that
  succeeds says so.
* **A document that does not parse ends the round at the parse** `open`, not after anchoring and
  derivation have run on the rest.
* A directory can be compared at all `done`
* The skip asks the store rather than the graph `done`, because no relation the classpath feeds
  carries a graph, so the rows are the store's.
* The file is the grain `done`. `store_class_file` is keyed by path under an entry, and `code_class`
  holds which class a reading found in which file.
* **A single changed class re-reads only itself** `blocked`, on the node below. The blocker was
  written here as re-graining `code_type`, and trying it found the relation rather than its key is
  what holds this: a row shared across classes by construction has no per-class mark that can decide
  its fate. Until this lands `store_class_file.byte_size` and `.mtime` are written and read by
  nobody, which is an arm waiting for its reader.
* **An entry gone from disk can be deleted** `done`. `code_type`'s key into `store_source` cascades,
  as do the dictionary's and the positions' keys, so `reclaim` deletes a vanished entry;
  `CodeCaptureTest` pins it. `sql_schema` and `sql_enum_binding` key `store_source` the same way and
  are the catalog's to look at.
* **Every `code_` row hangs off a class** `open`. `code_throwable` and `code_throwable_supertype`
  key the entry, though every throwable is a class the reading read; the dictionary pair is the
  other two, and is going.
* **One sweep, at the class file** `blocked`, on both above. `CodeCapture` sweeps all sixteen
  relations by instant beside the class-file sweep, whose cascade already reaches every class-keyed
  one. What only the second sweep reaches is the four above.

### A signature's type is a fact about the position that writes it

`code_type` is the parse of a generic type expression, memoised once per distinct spelling and so
shared by every class mentioning it. Generics are the whole of why a position's type is an
expression rather than a name; the memo is what makes the parse cheap; and giving the memo a primary
key is what made it look like a thing. Shared across classes by construction, it is what the leaf
above cannot invalidate per class.

The position carries the parse instead, four columns and no relation:

```
result_type          -- the spelling, the transcription
result_erased_class  -- NULL where it erases to no class
result_element_class -- NULL where nothing resolves
result_delivery      -- DIRECT | WRAPPED | MANY, NULL with element_class
```

* **Nothing anchors to it** `done`, by inspection. Every reader joins in from a site already holding
  the spelling and none enumerates the relation, so a row's existence asserts nothing.
* **The peel is a function of the spelling and a stored vocabulary** `done`, by inspection.
  `intent_delivery_container` supplies the container set, so siting the parse moves columns rather
  than moving a resolution.
* **Each column is earned by a named reader** `done`, by tracing them to the queries. The spelling
  declares a type; `element_class` answers which class backs a type, through
  `intent_type_backing_seed` to the language server and `TypeBackingRows`; `erased_class` is what a
  binding compares against; `delivery` has no reader now.
* **A use site carries its own parse** `done`. `code_method` and `code_method_parameter` each hold
  their own erasure, delivered class and delivery, held against the dictionary in both directions
  while both exist.
* **The readers move off the dictionary** `blocked`, on the seeded cases being gathered. Read at the
  queries rather than the views, what is left reading it through a position is the rendering and
  nothing else: `ClassMemberSlots` in the language server, and `SchemaQueries.members` and
  `CodeQueries`' methods, parameters and components in the MCP server, each joining `code_type` on
  a spelling the row already carries to fetch `display_name`. Each moves by formatting that
  spelling, which also retires an inner join that would drop a member whose spelling had no row.
  The two parameter-side readers of the parse flip cleanly; flipping them turned seventeen `@nodeId` cases red because
  `SeededStore` writes the dictionary half of a claim and not the position half, so a seeded store
  states that one signature parses two ways. Teaching the seeder both halves is work on a fixture
  whose conversion makes the question disappear, a capture writing both from one reading. So the
  flip waits for those cases rather than paying for them twice.
* **A slot carries its own parse** `done`. `code_read_slot` and `code_write_slot` each hold their
  type's erasure, delivered class and delivery, pinned against the dictionary by `CodeCaptureTest`;
  a type variable inherited from a base class still reads unsubstituted, a known limit.
* **The read slot is named for its axis** `done`. `code_type_slot` became `code_read_slot`: keyed by
  the offering class and its accessor, it has no type in its key, and the store already called it
  the read side of the pair `code_write_slot` is the other half of.
* **A delivered class the reading holds is referenced by its key** `open`. `element_class` is a class
  name, a part of `code_class`'s key and not the whole of it, so a reader joining on it can meet
  the wrong copy or several, and a class that goes takes nothing with it. Where the reading holds
  the class a position delivers, the position carries that class's entry beside it and the pair
  keys `code_class`; where it does not, as for `java.lang.String` or a generated jOOQ class, the
  entry is NULL and the name is a name for the compiler to resolve. NULL then says the class was not
  gathered, which a join on names cannot say at all.
* **A slot's parse is read by what replaces the accessor hop** `done`. `graphitron_type_backing_rule`
  reads `element_class` off the slot, and off the producer's method and parameter, with no peel
  through `code_type_element`; what blocked it was a stated classpath leaving the columns empty.
* **`code_construction` keys on the class it is about** `done`. It keys into `code_class` and
  `code_write_slot` follows it, so a class the next reading does not find takes its construction and
  write slots with it.
* **`code_type` and `code_type_element` are gone** `blocked`, on the readers moving off them.
  * **The modeling discipline's worked example follows them out** `blocked`, on the node above.
    `modeling-discipline.adoc` names `code_type` as a fact read off the classfiles and
    `code_type_element` as the family's derived peel, so the example is restated over the
    positions that carry the parse.

**Why this unblocks the leaf above it, stated because the graph does not say it.** Of the
sixteen `code_` relations, all but the dictionary pair now carry `class_name`, and `code_method`,
`code_read_slot`, `code_method_parameter`, `code_construction` and `code_write_slot` cascade from
`code_class`. The ones that could not be attributed to a class were exactly the four this node
removes or re-keys: `code_type` hangs off the entry and `code_type_element` inherits its grain, and
the construction pair did too until it was re-keyed. One relation's
grain sets the floor for the family's sweep, which is why the sweep scopes by entry and why the
remodel is the cascade fix rather than a foreign key being missing.

**Two facts recorded so their absence is not read as an oversight.** The rendering is not stored:
`display_name` is a formatting of the spelling, both its readers are Java, and a store should not
carry a presentation string. And the type is not captured as a tree: no consumer asks which
container it was or how deep it nested, and the classpath is re-read on every capture with the whole
signature in hand, so that fact is a line in the reading whenever something earns it. The one thing
that would earn it is the emitters moving off the walk and wanting a structured type rather than a
string to parse, which is the decision of whoever moves them.

### Gatherers run in the order their corpora stand in

A gatherer's anchor phase reads upstream gatherers only, and a row can only reference a row that is
already there, so the order gatherers run in is part of the model: it is the order foreign keys can
point in. Today the call order, the roster and the corpora disagree. `ModelCapture` runs the
documents first, then the configuration, then the catalog, then the classpath. And the classpath
reading reads the configuration without the roster saying so. A crawler reads the configuration as a
corpus handed to it, the way `graphql-source` reads the recipe, and carries no edge to the gatherer
that transcribes it; what holds the order is the roster's corpus rows and the gate below. The order
the corpora stand in is the configuration, then the classpath and the catalog, then the documents,
then what resolves the documents against the rest.

* **The configuration runs first** `done`. Every `store_graph_*` relation keys `store_graph` and
  nothing else, and no gatherer reads them before they are written, so it was a move in
  `ModelCapture` and changed no row.
* **The documents run after the classpath and the catalog** `done`. A move in `ModelCapture` that
  changed no row; the gate below is what will hold the order.
* **The classpath is configuration** `done`. `store_graph_dependency` holds one row per classpath
  entry of a graph's run, written and swept by the configuration gatherer, saying whether the entry
  is a build directory, a release jar or a snapshot jar, with origin and `coordinate`
  (`groupId:artifactId`) moved there from `store_source`.
* **Code does not depend on jOOQ** `done`. The two families are parallel, each reading its own
  corpus under the configuration and neither keying the other; the children below removed the two
  reads that crossed. "The code family reads the generated jOOQ classes" reverses the skip of the
  generated package.
  * **The table a parameter is bound to is the graphitron anchor's** `done`. The resolution joins
    the parameter's erased class to `sql_table.class_fqn` and stores what it resolved, a condition
    method's chain link in `graphitron_field_path_resolution_condition`; `code` no longer declares
    `jooq`, and `code_method_parameter.role` keeps `TABLE_CONCRETE`.
  * **The reading's loader comes from the classpath it read** `done`. Capture takes the
    configuration, the classpath and a loader over it, the jOOQ gatherer building its own catalog
    from the configured package; fixtures state a package and a loader, so `JooqCatalog` is no
    longer what a test starts from.
  * **Ancestry is read from class headers** `done`. `ClassHeaders` reads a class's superclass,
    interfaces and flags from the classpath and the platform modules without loading it; only a
    scalar constant's coercing still loads, and fixtures carry the generated catalog and jOOQ
    runtime as transitive entries.
* **The code family reads the generated jOOQ classes** `done`. The configured package's classes
  are `code_class` facts at the class grain, kind and supertypes without members, pinned by
  `CodeCaptureTest`: on sis 823 classes join the code family and the code gatherer stays at 0.7 s,
  where reading them whole cost 4 s more for 32844 generated members no reader asks for.
* **A service beside the generated classes is read** `open`, after "The code family reads the
  generated jOOQ classes". Services are commonly written in
  the module jOOQ generates into, and the class-grain reading takes the package and every package
  under it, so a service class beneath the generated one is a `code_class` row whose methods are
  left out with the generated members. A case first, then either the narrowing stops at the
  generated package or the layout is refused out loud.
* **The manual says the jOOQ package is read without its members** `open`, after the same. The `jooqPackage`
  row of `mojo-configuration.adoc` roots the catalog there and says nothing of the classpath
  reading, which an author whose services share the package needs to know.
* **A gate holds the call order to the roster** `open`. Nothing checks that `ModelCapture` calls a
  gatherer after every gatherer it declares, which is how the two drifted apart. `java-source` and
  `compile` run in the dev loop on their own cadence rather than in this sequence, and `sdl` names a
  class that writes nothing, so the gate's population is the gatherers `ModelCapture` calls.
* **The pipeline overview states the order and what holds it** `blocked`, on the gate.
  `pipeline-overview.adoc` § Capture transcribes says the gatherers run in the order their
  declared read edges require, which was false until the order moved and is held by nothing until
  the gate lands. The sentence names the order and the gate; the vocabulary around it is R983's.
* **The pipeline overview describes a dependency's row** `open`. The same section's `store_` bookkeeping sentence names the graph, its sources and
  their stamps, and gains the dependencies and what each kind lets a capture skip.

### The store knows whether an artifact can go stale

Reading a jar is safe for a release and dangerous for a snapshot, and Maven settles which.

* **How a sibling module reaches a run is decided** `done`. The Quarkus model for every goal: a
  workspace module resolves to its `target/classes`, then its jar in `target/`, then the repository;
  the dev goal compiles its own module first. Evidence:
  `roadmap/audits/2026-10-12-how-a-sibling-module-reaches-a-run.md`.
  * **A workspace module resolves to its own output, in place** `done`.
    `AbstractRewriteMojo.workspaceModules` finds every module under the outermost aggregator, and a
    classpath element that is a module's output or coordinate is replaced in place by its output,
    classified `REACTOR` or `SIBLING`; the source-root walk covers the same modules.
    * **The sibling scan's classification is pinned** `done`. `InstalledSiblingTest` states that a
      declared sibling's classes take the installed jar's place as `REACTOR` and a class added since
      the install is nameable.
  * **The dev goal runs its module's plan through compile** `done`. Before its first round the goal
    runs its module's plan through `process-classes`, the round replacing `graphitron:generate`
    (`ModulePlan`); `graphitron.dev.compileModule` off skips it, and a failing goal is a warning.
    * **The module's plan leaves the goal able to start its MCP server** `done`, a regression. The
      dev goal puts its own context classloader back before each plan step and after the last,
      pinned by `ModulePlanRunTest`.
  * **The manual says where a sibling's classes come from** `done`. `dev-loop-internals.adoc`
    § Multi-module projects and the codegen classpath section and `compileModule` row of
    `mojo-configuration.adoc`.
* **A reactor entry is never a release** `done`. A workspace module's entry takes its kind from the
  path the run reads, a directory or a snapshot jar; release is trusted only for a jar a repository
  resolved.
* An entry says what it is `done`. Capture is no longer told both halves of the classpath are this
  module's own output, which restores the reactor limit the `@service` arm leans on.
* The store records which repository answered `done`, by consequence.
* **The version is carried** `blocked`, on a workspace module being a configuration row, which
  is the coordinate-keyed half arriving first with a reader. `store_graph_dependency.coordinate`
  is `groupId:artifactId` by design, for naming a module in a refusal, and stops one field short of
  the identity question. The version is a fact the build declares, so it arrives with the dependency
  rather than as another column on the entry. That is also where the dependency stops being keyed
  by the entry's path: a dependency is a coordinate and a version, the project's own output and an
  unattributed path are sources no dependency provides, and one reactor module provides its output
  directory, its packaged jar or its installed jar depending on how the run was invoked.
* **Three kinds get three treatments** `open`, waits on the version. A release jar needs no content
  stamp, a snapshot jar does, and only a directory needs the stat walk; all three share one path
  today.
* **A stale compiled input is said once, plainly** `open`. When what a run reads is older than
  what it was built from, the schema is checked against old classes and every name added since
  reads as an author error: on opptak, 98 of them against a correct schema (the audit above). The
  developer sees one warning first, naming what is stale (a module or the catalog artifact), both
  dates, and the fix as a command to copy, which under the decision above is compiling the module
  rather than installing it. Once per stale input, in the console at startup, in the MCP `status`
  and `diagnostics` answers and as an LSP diagnostic; the "could not be resolved" diagnostics it
  explains point back to it, so 98 read as one cause; a warning that never blocks generation, a
  false positive costing one line. Absorbed R987.
  * **What stale compares is decided** `done`. A workspace module is stale when the run read its
    installed copy for lack of output, or when its newest Java source is newer than the newest class
    file or jar the run reads.
    * **A workspace module is a configuration row** `done`. `store_graph_module`, one row per
      workspace module keyed by graph and coordinate, and `store_graph_module_entry`, which
      classpath entry is a form of which module, written by the configuration gatherer.
    * **A module's newest source is captured** `done`. `java_source_root`, one row per root the
      `java-source` gatherer walks with the newest modification time among its `.java` files, swept
      with `java_file`.
  * **The stale modules are a rule** `done`. `build_warning_stale_module`, a view with one row per
    workspace module read stale, reason `NO_OUTPUT` (in any capture) or `SOURCES_NEWER` (in a dev
    session), and both times.
  * **A stale module is said where the developer looks** `done`. One sentence with the fix command,
    in the `diagnostic` union as a `classpath` warning, in MCP `status` as `staleModules`, on the
    dev console and as an LSP `window/showMessage`; each reader says it once when stale and once
    when compiled again (`StaleModules`).
  * **The not-found diagnostics point at their cause** `done`. While a graph reads a module stale,
    each error it may have caused ends with `build_warning_stale_module_hint`'s clause, a count of
    stale modules per graph; tying an error to one module would need the rejections to say what they
    looked for.
  * **A catalog is compared with what generated it** `open`. The catalog module's sources are
    generated, so a stale catalog shows only against the migrations or DDL its codegen read, which
    only the codegen configuration names. Its own fact first, then a condition beside the module's.
* **A directory's stamp covers what the reading reads** `open`. The stamp walks every file under the
  directory, the generated jOOQ files included, so where services share a module with the catalog a
  migration regenerating jOOQ moves the stamp and every service class is read again though none
  changed. The stamp covers the class files the reading takes, or the file-grain skip in the capture
  branch makes the directory's stamp matter less; either removes the false invalidation.

### A node is settled with the decode that states it

Which types are nodes, the id each answers to and the key it is encoded from are settled in the
`graphitron-ast` anchor, but from the wrong inputs and not all in one place. `Nodes` reads
`graphitron_node_entry` and `NodeKeyColumns` reads `graphitron_node_keycolumn_entry`, coordinate
copies of `@node` beside the position-keyed `graphitron_ast_node_entry` and
`graphitron_ast_node_keycolumn_entry` that nothing reads, and `TableTypes` reads the coordinate copy
of `@table`. Both read `intent_node_metadata_defect`, a view over the catalog alone, so a
`graphitron_` stage depends on `intent_`. `NodeKeyColumns` runs a gatherer later than the nodes it
keys into. All three clear and refill, emptying what hangs off them by cascade each reading. And
`graphitron_node_type` unions the authored entry with the resolved node, which its five readers
take as "is a node" whichever they meant. The editor reads the authored copy where it means the
resolved fact, so a type that is a node through catalog metadata alone gets no completion, a
squiggle on a `typeName:` the build accepts, and a hover showing the authored id rather than the
resolved one.

* **Node metadata is a catalog fact, and an attempt that fails is a warning** `done`. The jOOQ
  gatherer records node metadata only for a well-formed `__NODE_TYPE_ID` and `__NODE_KEY_COLUMNS`
  pair; a malformed one is a row of `sql_node_metadata_unreadable` and the warning
  `NODE_METADATA_UNREADABLE` at each bound `@table`. `intent_node_metadata_defect` is gone. Stated
  by `facts/node-metadata-unreadable.graphqls`.
* **A key column is the column it is, not the name it spells** `done`. Capture resolves key and
  index columns by identity, else exact spelling, through `JooqCatalog.columnOf`; a field of another
  table makes the metadata a failed attempt. `findColumn` goes when its last walk caller does.
  Stated by `facts/node-metadata-foreign-key-column.graphqls`.
* **The node is settled from the position-keyed entries, in one place** `blocked`, on "The
  table-binding and node subject converts" in the seeding branch: 45 seeded classes build their
  bindings and nodes through the coordinate copies this node retires, so moving the stages off them
  leaves those classes with no binding and no node. Written once and rolled back; the work is the
  claimed application, `graphql_directive_application` at ordinal zero on the type's coordinate,
  which numbers repeats in the anchor's own merge order, with the decode joined by position. `TableTypes`, `Nodes` and `NodeKeyColumns` read `graphitron_ast_table_entry`,
  `graphitron_ast_node_entry` and `graphitron_ast_node_keycolumn_entry`, reaching the type through
  `graphitron_directive_application` and the pinned key's order through its value positions, and
  `NodeKeyColumns` moves into the anchor beside `Nodes`. The `NODE_*` rules of
  `graphitron_entry_defect_rule` read the same entries at the `@node`. `graphitron_table_entry`
  keeps its other readers, which are "The twinned ones flip and subtract".
* **Table types, nodes and node key columns are marked and swept** `open`, after the move. A binding
  that changed is deleted first, taking that type's node and key columns with it; the rest are
  upserted on their keys and swept, and an unchanged binding keeps everything hanging off it.
* **"Is a node" is the resolved node** `blocked`, on the node-id subject replacing
  `graphitron_node_id_instruction` and the `intent_node_id_decode*` views, whose eight seeded classes
  seed `@node` without `implements Node`. `graphitron_node_type` is `graphitron_node` and every
  written `@node` besides, and the rows it adds are only ever wrong answers to its readers: a
  reach marked batched, a `@nodeId` resolved to a node that does not exist. Its two readers,
  `graphitron_node_id_instruction_rule` and `intent_node_container_member`, read `graphitron_node`, and the view goes. A node is what the
  manual says takes effect: `@node`, a `@table` the catalog resolves and `implements Node`, or the
  last two over published metadata. Written once and set aside, which found the rest of it:
  * the walk spells the same membership in `NodeDeclaration.isNodeType`, read by
    `SchemaReachability`, `ArrivalIndex`, `KeyNodeSynthesiser` and `BuildContext.namesANodeType`,
    and `NodeTypeShadowTest` binds the two, so the predicate moves in the same commit: a `@node`
    with no table, one over a table the catalog does not resolve, and one implementing nothing are
    nodes on neither spelling;
  * `Nodes` itself admits a `@node` over `@table` implementing nothing, which
    `facts/node-population.graphqls` states today and the move removes;
  * an interface or union carrying `@node` is never a node, so `@nodeId(typeName:)` naming one
    takes the container path, which the store's rule already does;
  * seven real-schema test classes write those shapes and lean on the old answer, among them
    `FederationKeyDerivationTest`, `NodeTypeCompletionsTest` and
    `facts/argmapping-projection-defect.graphqls`; their schemas say what they mean in the move;
  * `NodeCaptureTest` is redundant once the documents run capture, and goes with it.
* **A `@node` on a type without `@table` is a defect** `done`. `NODE_WITHOUT_TABLE`, at the `@node`:
  the anti-join between the entry and the node, a type with no table binding entry. A `@table` that
  does not resolve is that directive's own defect and draws nothing here.
* **The editor and the MCP read the resolved node** `open`. Completion of `typeName:`, the legality
  behind its squiggle, the hover and `SchemaQueries` read `graphitron_node` and
  `graphitron_node_keycolumn`, so a node through catalog metadata alone is completed, accepted and
  shown with the id and key it encodes.
* **The coordinate copies go** `blocked`, on the nodes above. `graphitron_node_entry`,
  `graphitron_node_keycolumn_entry`, their writer in `GraphitronAnchor`, and the agreement and
  seeding uses of them.

### A node id is a target type, read as its ID

A field has a target type, a `@reference` says how to reach it where the way is not obvious, and
`@nodeId` says the value wanted is the target's ID rather than the target. The field returns `ID`
and its target is still a type, the node type: a written `typeName` names it, and a bare `@nodeId`
leaves it to be inferred. An argument or an input field is the same fact read the other way, naming
a target row by its ID. So what is particular to a node id is two facts, which type the target is and
that its representation is the ID, and the route to it is the ordinary chain.

The current model is flawed. `graphitron_node_id_instruction` blends what the author wrote with what
was inferred, and the node-id family keeps a second, private model of the route beside the chain.
Its endpoint crosses every candidate with every table at the coordinate, so capture fails on the
carrier role's key and the decode destination, the slot's candidate count and the filter roles
answer wrongly without failing. Unions and interfaces were not taken into account when it was built,
and the seeded cases could not show it. It is modelled again from scratch, reading nothing from
`intent_`, and replaced by adding, flipping and subtracting. Evidence in
`roadmap/audits/2026-09-30-node-id-instruction-grain.md`.

A node is a type, and only a node type can be the target. A union is never a node. `@node` on an
interface is not supported and stays out, a possible future addition that would give its `@nodeId`
fields one target, the interface's.

* **The model comes first, as tables** `open`, declared with keys, foreign keys and grain sentences
  before anything writes them, so a fact document can name them.
* **The corpus is written against the model** `open`, as fact documents asserting with
  `@expectEquals` what each schema should give the new relations: an output field, an argument and
  an input field, at a node type, an interface and a union, by a written `typeName`, a bare
  `@nodeId`, the id-name forms and `@reference`. Written before the gatherer, because a document is
  an example of the model and writing them is where the cases nobody thought of turn up, while no
  code depends on the answer. Each is committed with the step that satisfies it, so every commit
  stays green.
* **The entry states what was written** `done`. Both `@nodeId` entries write a row per application,
  with `node_type_ref` nullable, NULL on a bare one.
* **Measure the move from table to type** `done`. Over sakila, a sis store and opptak, 465
  coordinates: every bare output field is a `Node.id`; the type and table answers differ only where a
  union return names its container and where a payload-returning mutation leaves the table rung to
  answer; the by-name `id` carries 83 mutation input fields in sis.
* **The target type of a `@nodeId` coordinate is the entry's** `open`. Beside `node_type_ref`, what
  was written, the entry carries the type the id names and how it came to:
  * `WRITTEN`, the `typeName:` as written;
  * `IMPLIED`, at a field implementing `Node.id`, which is a `@nodeId` whose `typeName` is the
    implementing type whether or not the directive is written; a written one naming another type
    is a defect;
  * `BY_NAME`, at an input field or argument named `id` with no `@nodeId`, which the reference
    documents as reading as the target node's ID; its type is inferred by the rungs below, as a
    bare `@nodeId`'s is;
  * `RETURN_TYPE`, at an argument the field's return type, and at an input field the root field's
    return type per occurrence, occurrences that disagree being a defect at the entry; an
    interface or union return is the container, which a `typeName:` may name;
  * `TABLE_NODE`, where no type answers: the one node over the table the value is consumed
    against, the jOOQ record's, the one `@mutation(table:)` names or the one a `@reference` path
    lands on, written in the anchor after `graphitron_node`; a table several node types share
    infers nothing. An input field is this rung in practice, being consumed against a table.
  The written, implied and return-type rungs read the `graphql_` family alone, which is anchored before the entry is written.
  Nodehood plays no part in the inference: the type is joined to `graphitron_node` afterwards, and a
  type that is no node is a defect, as for a written name. `graphitron_node_id_candidate`, which
  filtered by nodehood and took a container's members, goes, and with it the binding of any bare
  output field to the type declaring it: across the fixtures, sis and opptak every bare output field
  is a `Node.id`, so the narrowing breaks nothing in use.
  The reference changes with the flip, not before: its inference rule (a) narrows to `Node.id`;
  rule (b) at an argument takes the return type first and the table only where no type answers,
  which relaxes two node types over one return table from refused to answered; and the inference is
  stated once, as this ladder, saying in so many words that a shared table infers nothing.
  Every shape the manual refuses is a defect row rather than an absence, and a nested input field
  under an interface or union field is one of them today. Capture records such a schema and
  validation refuses it; it never fails capture, which is what the reproduction in the audit did.
* **A bare node id with `@reference` takes the type its path lands on** `blocked`, on the forward
  walk being anchored, in the chain branch. Where no table-bound type answers, which is an output field
  carrying `@reference` or an argument on a field whose return binds no table, the type is the
  one node type over the table the path lands on, `TABLE_NODE`, a path landing on a table and not a type. The
  landing is read off the chain rather than resolved here, which would be a second model of a route
  beside it.
  Where a type does answer, a written name or a bound return, the target is authoritative and the
  chain is checked against it: landing elsewhere is a route defect, not a different target.
* **A node id under an interface or a union, everywhere** `open`, not first. An argument or an input
  field on a field whose type is an interface or a union can carry `@nodeId`, which means a
  `typeName:` may name an interface or a union at any such coordinate and not only where the value
  reaches a `@service` slot. The decode then switches over the container's node members, and the corpus
  grows by those shapes when this is taken.
* **An input field's use site** `open`. A use is a definition joined to its consumer, and the model
  holds it today only as an `intent_` view, so it is modelled here rather than read.
* **The chain reaches the target type** `blocked`, on the forward walk being anchored and the
  argument and input-field grains in the chain branch. A `@nodeId` field returns `ID`, so its chain is the
  forward walk from its source. Where its target type is written or bound, the table the walk ends at
  is checked against it, keeps its losing readings and reports its route defects like any field's;
  where it is bare, that table is what its `TABLE_NODE` type is read from. A multitable bare
  `@nodeId` is one chain per member, to that member's own type.
* **Key forwarding over the chain** `blocked`, on the chain reaching the target. One row per key
  column of the target and position along the winning chain where a source column already
  carries it, so encoding reads the key from the earliest table that has it and decoding compares
  or fills there, with no further join. General to every chain rather than a node-id relation, and
  the first reader of `graphitron_field_table_link`. It replaces the `lifted` CTE in
  `graphitron_node_id_decode_column_rule` and the generator's own copy in
  `NodeIdLeafResolver.landKeyColumns`.
* **Flip: encode and decode read the target type and the forwarded key** `blocked`, on the two
  above, and so do the filter and carrier roles and the coverage census.
* **Subtract the node-id instruction and decode relations** `blocked`, on the flip: `graphitron_node_id_instruction`, the endpoint that crosses,
  `graphitron_node_id_decode_hop` and `_hop_column`, `graphitron_node_id_decode_column`, and
  whatever in the chain stops being read.

### A polymorphic id resolves per member, to the overload that accepts it

A union's members are unrelated results rather than subtypes, and the schema declares all of them,
so the decode switches on the id's type and builds that member's record. The best slot for it is one
overload per member, `assign(CustomerRecord)` beside `assign(StaffRecord)`, which hands each call a
concrete record, and it must be supported. The store modelled the slot as general subtyping instead:
every member's captured supertype chain joined against one parameter type, so that an interface a
consumer's jOOQ `recordImplements` option puts on the records could be that type. That option is not
supported, and without it the chain carries nothing a member does not already say.

* **An overload accepts a member by two spellings** `open`: its parameter is the member's own record,
  or one of the four types every node's record is, `Object`, `Record`, `TableRecord<?>` and
  `UpdatableRecord<?>`, because a node has a primary key by definition. One method taking
  `UpdatableRecord<?>` is the case where one overload accepts every member, and a mix resolves the
  way javac resolves it, most specific first.
* **The destination names the overload** `open`. One row per member already stands; it carries
  which overload that member lands in, and the refusal is a member's: no overload of the named
  method accepts it.
* **The overloads differ only in the polymorphic parameter** `open`. Anything else, another
  parameter or an argument mapping that differs between them, is refused, so every call is spelled
  from one argument list.
* **The generator dispatches from these rows** `blocked`, on R984, which owns the dispatch and
  the widening of each arm's result; it reads the three nodes above and waits on them.
* **The per-member fit goes** `blocked`, on the dispatch: `intent_record_slot_assignable`, the
  `SLOT_NOT_SUPERTYPE_OF_MEMBER` verdict, and `sql_table_record_supertype` with the capture walk that
  climbs the record class, which has no other reader.

### Seeding dissolves, and the fixture goes with it

Seeding is the fallout of the module boundary this item already moved, and the fixtures are the
shape the root condemns, still in the tree. Evidence in
`roadmap/audits/2026-09-27-seeding-dissolution.md`.

It is also what holds every subtraction in the graph. A seeded test writes a relation's columns, so
a relation cannot go while a test seeds it, and a reshape breaks every test that wrote the old
shape whether or not the behaviour it asserts changed. So this branch is a prerequisite of the
subtraction nodes elsewhere, not a cleanup beside them, and a subject is taken first when its
conversion frees one.

* The corpora are reachable `done`. All three families have one and two are modules the tests already
  depend on: the fact documents, `graphitron-sakila-db`, `graphitron-sakila-service`.
* A document states what a fixture stated `done`. Seven documents replaced four test classes, at
  forty fewer `@Test` methods and no lost coverage.
* Deleting coverage cannot be silent `done`. `theDocumentsDeclareBlocks` fails if the folder stops
  resolving, a glob stops matching, or a block declares no rows, which was the one way this arc
  could have deleted its own coverage without saying so.
* The count only falls `done`. `SeedingDissolutionGateTest` holds it at 46, because a seeded case
  is written by copying one and the file it copies never gets opened.
* The count counts seeding `done`. A quarter of it did not: 26 of 109 callers wrote no fabricated
  row, 22 of them borrowing the fixture as a store factory and four naming it in prose. The only
  row any of them planted was `store_graph`, a second spelling of `ModelCapture.writeGraph`, whose
  own javadoc asks a fixture to call it instead. They open `ThreadConfinedStore` and anchor their
  graph through capture now, so what the gate counts is cases that fabricate facts, plus
  `CodeRows`, which is the fixture's own machinery and goes with it.
* **The code-reference subject converts** `open`, and first, because it frees the producer
  branch's subtraction. Twenty-nine classes seed the per-site copies of a code reference, through
  `seedService`, `seedFieldCondition`, `seedArgumentCondition`, `seedExternalField` and their
  argMapping variants; three of them are the node-id subject's and wait with it. Of the other
  twenty-six, seventeen need no corpus change, and two of those pinned a view nothing read, so they
  went with `intent_condition_slot` and `intent_condition_param_decode` rather than converting, as
  did three more with `intent_condition_membership`, `intent_argument_filter_role` and
  `intent_input_occurrence_override`, which read only each other, and with them the gap
  `ArgumentFilterRoleTest` held. A class's pinned view is checked for a production reader, through
  every view between, before the class is converted. The rest wait on a gap each, eight catalog shapes
  and ten classpath ones, two of which want a second classpath entry rather than a class. Every
  class and gap, and the cases no capture reaches, in
  `roadmap/audits/2026-10-03-code-reference-subject-gaps.md`.
* **The code half converts** `open`, and is the larger half: 54 classes and 717 tests against the
  SDL half's 50 and 354. Taken first because the prerequisites are here. A seeded `code_method` row
  is a claim about a method; a method in `graphitron-sakila-service` is one.
  * **A fixture's classpath is read, never stated** `done`. Every capturing fixture names classpath
    entries that capture reads as a run does; `CodeRows.writeStated` and the stated census are gone.
    * **A scanned census becomes the root it was scanned from** `done`. The language server's and
      MCP server's fixtures, `TestSchemaHelper`, `CorpusStore` and the derive tests pass the root,
      with no expectation moving.
    * **A hand-built census becomes real classes** `done`. The service corpus holds them, with
      `ClasspathCorpus` declaring the module as the consumer's code and extended-scalars a library.
* **The library carries the shapes a conversion needs** `open`, and grows by one shape per
  conversion attempt. The first attempt wanted an overload and the service module had none across
  ninety-seven classes, so `FilmService.topRated` is now a pair. That is the expected cost, a method
  added to a module that already compiles; what is not knowable in advance is which shape the next
  attempt wants. The node-id subject enumerated its own once, and they went in together:
  `NodeIdSlotService`, an overload whose halves name their parameter alike and a method taking a
  primitive, and in the catalog a second plain-`bigint` referrer of `converter_org`, a composite
  referrer of `converter_campus_term` whose first column escapes the converter, and two branch
  tables that reach one node table through repeated constraint names.
* **The SDL half converts** `open`. 50 classes, 354 tests.
* **A case that resists a document may still not need seeding** `open`, and the difference decides
  the node below. Nine of `FieldProducerMethodTest`'s ten cases are a document now; the tenth wants
  two graphs whose classpath entries differ, which one shared corpus cannot give. That makes it a
  Java case rather than a seeded one: capture takes the classpath as an argument, so the case hands
  in two and runs the same gatherers, and the class now seeds nothing. Not being expressible as a
  document and needing a fixture are two different claims, and only the second is residue.
* **Conversion goes by subject, not by class** `open`. A hundred and nine classes converted one
  at a time is not a plan, and the relation is the wrong unit to batch by: they assert on 178
  relations and 57 of those have one class each. The subject is the unit that compresses, because
  one document writes rows into every relation its schema touches at once. Node ids are nine classes
  and 131 tests, fields nine and 108, mutations eight and 100, nodes six and 77, input fields six
  and 71: five schema families carry 38 classes and 487 tests. What a subject owes up front is its
  corpus gaps, enumerated once, rather than one missing shape found per class.
* **The node-id subject converts** `blocked`, on the node-id branch above: every class in it
  reads the instruction, and its first conversion crashed capture. Eight classes of nine. Its gaps
  are one classpath shape
  and three catalog shapes, and its six sibling-graph cases need no replacement: the runner captures
  every document into one store as its own graph and compares each block against that graph alone,
  so a derivation that drops `graph_name` fails whichever document it leaks into.
  `PolymorphicNodeIdDecodeTest` waits on the branch above, because a document written now would pin
  the rule that branch replaces.
* **The fixture catalog is one catalog over every schema** `done`. `graphitron-sakila-db`
  generates one catalog over every schema, so fact documents see every shape the database has;
  only `JooqCatalog` reads a layout, pinned by `JooqCatalogLayoutTest`.
    * **The single-schema fixture is its own schema** `done`. `singleschema`, left out of the
      fixture catalog, is what `JooqCatalogLayoutTest` asks beside it, and serves the MCP cases that
      want a generated model sharing no table with the fixture catalog.
    * **A table's source package is the catalog's** `done`. `JooqCatalog.schemaPackageOf` reads it
      off the schema class, which sits in it under either layout.
  * **One codegen over every schema is the fixture catalog** `done`. Documents, pipeline tests and
    the example are captured and generated against every schema, `public`'s readers at `public_`; a
    bare `@table` naming `event` or `note` is ambiguous, as to a consumer with those schemas.
  * The three other fixtures' readers then move onto it one fixture at a time.
    `nodeidfixture`'s `collide_a` and `collide_b` keep their shared type id, unwarned.
    * **The multischema fixture is part of the fixture catalog** `done`. Read as
      `<root>.multischema_a` and `<root>.multischema_b`; its codegen and constants are gone.
    * **The node-id fixtures are part of the fixture catalog** `done`. Read as
      `<root>.nodeidfixture` and `<root>.idreffixture`; their codegens are gone.
* **The table-binding and node subject converts** `open`, and frees "The node is settled from the
  position-keyed entries". Its conversions waited on the fixture catalog above, the nodes subject's
  cases wanting metadata keyed off the primary key and one table name in two schemas, which every
  fact document now reaches. Forty-five classes seed `@table` and `@node` through `seedTableBinding`,
  `seedNode` and their siblings, which write the coordinate copies. Read for a production reader
  first, on this branch's terms: fifteen of them had none, and four pinned a dead relation beside a
  live one.
  * **What nothing read goes** `done`. Seventeen relations with no production reader (the mutation
    write family, the input-field and argument column families, three node-id views) went with their
    rule views, stages, grains and tests; they are built again from the model when a reader asks.
  * **The rest convert by subject** `open`. Twenty-six classes with a production reader: nodes
    four, fields six, references four, claims three, arguments and input fields four, and five
    node-id classes that wait with the node-id subject.
    * **Nodes** `done`. The node tests are `facts/node-population.graphqls` and
      `facts/node-key-columns.graphqls` over the fixture catalog, plus `NodeGraphScopeTest`; the
      population document states that a `@node` over `@table` without `implements Node` is a node.
    * **Fields** `done`. The field tests are `facts/field-scope-table.graphqls`,
      `field-navigated-type`, `field-column-table` and `field-routine-method`, plus
      `FieldGraphScopeTest`; "every field has exactly one navigation" is a gate over every capture.
    * **Claims** `open`, not by converting: `AuthoredClaimTest` and `ColumnMatchClaimTest` pin the
      claim relations, which go under "The claims go", and they go with them.
* **There is no residue, and no partial conversion** `open`. A class converts whole or it has not
  converted, which is what the caller count measures and why it is the right count. A case that
  fabricates a state no capture reaches is not a case the fixture has to be kept for: it is either
  a property test, which is the tool for a pattern nobody writes on purpose, or it is a test of a
  strange shape nobody cares about and it goes. `CarrierDataFieldPopulationTest` was named here as
  the clearest instance and was not one: every state it seeded is SDL, and it is the fact document
  `carrier-data-field.graphqls`, its four cases one expectation over the whole graph. A class is
  read for what it fabricates before it is called residue.
* **The reference-step subject converts** `open`, and it blocks the reference-step entries' flip.
  Five classes build `@reference` paths by seeding decoded steps through `seedFieldReferenceCall`,
  `seedArgumentReferenceCall` and the element variants: `ConditionMethodRouteTest`,
  `ReferenceStepTargetTest`, `ArgumentReferenceStepTargetTest`, `ReferenceStepFanoutTest` and
  `ReferenceStepHopArmConstraintTest`. They pin the readers the flip moves, and a seeded step writes
  no ast row, so a reader moved to the transcription reads nothing under them.
  * **`ConditionMethodRouteTest` is two fact documents** `done`. `condition-method-route` and
    `condition-method-route-departures`, each case naming a real method of
    `ConditionMethodRouteFixtures` in the sakila service.
  * **The old resolution's tests go with it** `open`. `ReferenceStepHopArmConstraintTest`,
    `ReferenceStepTargetTest` and `ReferenceStepFanoutTest` pin the step hop, the step target and the
    fanout, which the flip deletes rather than moves; they convert by going, each case
    `FieldTableLinksTest` does not already state against the path resolution added there,
    captured. Its seventeen cases cover the key directions, the namespaces, table discovery, the
    condition routes and a broken path already.
  * **The spelled table's cases convert on their own** `open`. Seven cases in
    `ReferenceStepTargetTest` pin `graphitron_spelled_table` over seeded spellings, a relation the
    flip keeps.
  * **`ArgumentReferenceStepTargetTest`** waits on "The path is stated at the argument and
    input-field grains".
* **The code-reference subject's conversions so far** `open`. `CarrierDataFieldPopulationTest` and
  `SeparateFetchRuleTest` are fact documents; the second's one Java case, a contested binding
  that needed the multischema catalog, went with the view it pinned, a relation keyed by the field
  answering once by construction. A document can now vary its catalog, `@withConfiguration`'s
  `jooqPackage`. Of what no capture reaches, one
  type at two root slots went, the specification forbidding it; an input field of an output type
  became an input field of a `@table` input, the legal shape of the same guard; and the sibling-graph
  case is the harness's own, every document being its own graph checked alone.
* **A document the toolchain refuses fails the runner** `done`. Both runners fail on a
  `graphql_schema_problem` row unless the document states the relation; the documents that lost
  what they wrote are fixed.
* **A definition a document repeats within itself is recorded** `done`. Source capture carries each
  source's own refusals on its reading and the assembly writes them as `REGISTRY` problems beside
  the merge's; it found `federation-versions` declaring `type Query` beside the prelude's, now an
  extension, and `definition-repeated-within-a-document` states the row.
* **A fresh store per test stops being the only shape** `open`, and it is not tidiness: every
  `sql_` and `code_` relation is store-wide, so one store holds many graphs against one reading of
  the catalog and the classpath, and varying which SDL loads into a warm store is the only thing
  that exercises refresh at all. A fresh store means every refresh runs against an empty one, which
  is the case where sweeping, re-anchoring and invalidation are all trivially correct.
* **`SeededStore` is deleted** `blocked`, on the five above. Not shrunk and not kept for a
  remainder: the fixture goes. The subtractive commit is what makes this branch done, and until it
  lands the root is not done either.

### The agreement test dissolves, each case to the corpus it pins

`FactCaptureAgreementTest` was the shadow period's honesty check, capture beside a walk nobody read
the store for. Its registration gate already left for `graphitron-model`. What stays is four kinds
of case in one file: nine that compare the store against the walk, and the rest pinning capture
against its own corpus, filed here because the walk's fixtures were.

* **The walk's comparisons go** `done`. The nine cases taking the walk as expected value are
  deleted; the one claim not already pinned, the `@service` and `@mutation` payload columns, is
  `field-site-payloads.graphqls`.
* **The catalog cases become fact documents** `done`. `sql_` is pinned by `catalog-tables`,
  `catalog-columns`, `catalog-constraints`, `catalog-routines` and `catalog-node-metadata` over the
  fixture catalog, captured by the real run.
  * **A primary key names a primary-key constraint, and the database refuses otherwise** `done`.
    `sql_primary_key` carries its form, and its foreign key into `sql_constraint` includes it.
* **The source and membership cases move to their gatherers' tests** `done`. The partition by
  schema package is `catalog-partition.graphqls`; the rest sit in `GraphitronModelStoreTest`,
  `CodeCaptureTest`, `GraphQLSourceCaptureTest`, `GraphQLAnchorTest` and
  `ModelCaptureMembershipTest`.
* **The configuration cases move beside the configuration gatherer** `done`, as fact documents
  rather than as cases: see "The configuration family is pinned by fact documents".
* **The emitted family's union moves beside `EmittedRegistry`** `done`, as fact documents
  `emitted-anchors` and `federation-sound`; an assertion takes a `where`, a SQL condition over the
  relation's own columns, so an equality can be stated over part of a relation.
* **The oracle writers' lifecycle cases stay with their writers** `open`. The javac partition and
  the loaded diagnostics are the generator's writes, so they stay in `graphitron`, in a test named
  for what it pins.
* **A registration says what pins a relation, not what it shadows** `open`. About sixty `graphql_`
  and `graphitron_` relations are registered as the store containing `GraphitronSchema`, a claim
  with nothing behind it once the comparisons go. The arms become what an anchor stands on:
  corpus, derivation, oracle writer. A relation the rename leaves with no anchor is a finding,
  not a silent loss. This is R740's gate half.
* **A relation is closed-world within its scope, so any of them can be asserted empty** `open`.
  The corpus refuses an empty block on a relation whose comment does not say what its silence
  means, found by matching phrases like "no row" in the comment, so that "not reached" is never
  read as "resolves to nothing". In a relational store that distinction belongs in the data: where
  knowledge is incomplete the store states it as a fact a reader joins to, an attempt that failed
  (`sql_node_metadata_unreadable`, the defect relations), a reading that stopped part way
  (`store_graph_progress`) or a corpus never read (`store_graph_source`), and every relation is
  read as complete for its graph, source or reading. The silence check goes from the corpus;
  `@assertEmpty` applies to any relation that carries the graph. A relation a document trips over
  is a finding against that relation, the dissolving `intent_` views being the likely ones.
* **The fact model cites what pins it** `blocked`, on the registration. `fact-model.adoc` names the
  class six times, and already credits it with `everyRelationIsRegistered`, which moved.
* **`FactCaptureAgreementTest` is gone** `blocked`, on all above.
* **The shadow tests go with their walk side** `blocked`, each on its walk side losing its last
  production reader. Each pins a walk derivation against the store relation that replaces it, both
  running in production: `RoutineSpentInputShadowTest`, `RoutineDirectiveResolver.bindArgs` against
  `graphitron_argmapping_match`, added by R950 and owned by no item before this;
  `ColumnMatchShadowTest` and `InputOccurrenceShadowTest`, taken from R740; and
  `NodeTypeShadowTest`, which goes with `NodeDeclaration.isNodeType` when "is a node" becomes the
  resolved node, where R740 had kept both spellings.

### The editor's answers are tested where the editor gets them

Reported on 2026-10-15: in a real editor session, hover and inlay hints no longer show. Every test
of both passes, and none of them goes where a session does. The provider tests hand `Hovers` and
`InlayHints` a store handle directly; `TextDocumentServiceTest` makes one hover round trip over
JSON-RPC against a fixture store; `DevServerTest`, the one test through the dev goal's socket,
asks for goto-definition precisely because the workspace it builds has no store. So the path a
session takes, the dev goal's own capture read through `Workspace`, is in no test. On that path
hover and hints pass three gates, the file being open, the store naming a graph that read the file
(`StoreAccess.sourceNameOf` and `store_graph_source`), and the read's budget, and each answers
nothing when it fails: the budget warns, the other two say nothing at all.

* **A dev round answers hover and inlay hints over the wire** `open`, first. A plugin-tier test
  runs a real dev round over a schema file on disk, attaches a client to the dev server, opens that
  file and asserts hover and inlay hints are not empty. It either reproduces the loss or shows the
  session that lost them differs from it, and it is the guard either way.
* **A read that answers nothing says why** `open`. No store, a document no graph read, and a read
  out of budget each leave the editor blank. A blank answer is correct for a coordinate the store
  has nothing to say about and wrong for a session that is not connected, and only the server can
  tell the two apart, so the two silent gates say so once per document where the budget already
  warns.

* **A failed round's report rows say they are stale** `done`, a regression. A round that fails
  before reporting sweeps its report rows and leaves its `store_graph_progress` row started, not
  completed, so `status` says `report` is `Withheld`, with why.
* **The dev executor runs on a federated schema** `done`. Every schema gets a dev executor, so a
  missing one means a regeneration or compile has not succeeded; `DevExecuteExecutionTest` resolves
  a `Customer` through the federated fixture's `_entities`.
* **The session's hovers and hints, as found** `open`, evidence for the nodes above, measured
  2026-10-15 in a live session: a field hover takes about 2.8 s where directive, key and
  declaration hovers take about 0.05 s; a hover on a type name written as a field's type answers
  nothing; and a `@reference` field has a path but no classification claim, so it draws no
  classification hint.
* **An editor that disconnects leaves the store open** `open`, a regression. Reported 2026-10-15
  from a live session: capture's stamp read failed with H2's `ClosedChannelException`, and the
  store console read no rows of `graphql_directive` while `count(*)` and the hot relations still
  answered. `DevServer`'s per-connection teardown calls `shutdownNow()` on the drain executor, and
  an interrupt during file I/O closes H2's file channel for every user of the in-process store; the
  session then serves from page cache until a read misses it. A connection's teardown cancels
  without interrupting, the store survives an interrupt anywhere else, and a store whose file is
  gone fails loudly rather than half-serving.
  * **A connection's teardown lets its drains finish** `done`. `DevServer.stopDrains` shuts down
    without interrupting; `DevServerTest` fails if a drain in flight is interrupted.
  * **The store survives an interrupt anywhere else** `done`. The store opens through H2's `retry:`
    file system, which reopens a `FileChannel` an interrupt closed; `GraphitronModelStoreTest` reads
    a cold store with the interrupt flag set, and `DebounceExecutorTest` fails if a regeneration in
    flight is interrupted.
  * **A store whose file is gone fails loudly** `open`, rather than answering from page cache until
    a read misses it. `retry:` reopens a file that is still there, not one deleted or moved. The
    read path treats `ClosedChannelException` as a dead store, closing it and reopening cold, keyed
    on H2's error code the way `StoreReader` already recognises the query timeout.

### The data model is written down as it now stands

* **R333's data model is an architecture page, revised by what this item learned** `open`, after
  "The emitter architecture is examined against the store", whose answer the page states. R333 is
  absorbed here, 2026-10-17: the coordinate, its small independent facts and the leaf zoo they
  dissolve move to `docs/architecture/explanation/` beside `fact-model.adoc` and
  `modeling-discipline.adoc`, rewritten rather than copied. What it learned: the store is the model,
  with capture and anchor phases per gatherer and no strata; facts are keyed by where they were
  written; the emitted schema is decided in capture; a path is one forward walk from source to
  target; and the generator's shape is whatever that first node decides. R983 rewrites the same
  pages' vocabulary and lands with it or first. R333's body is in git history.

### The build cannot hang or fail on its own fixtures `open`

A build that hangs or fails for reasons no change caused costs every session a rerun and makes a
real failure look like noise.

* **Class initialization cannot deadlock in javapoet** `open`, first: a build hang. Found
  2026-10-20: a `graphitron` module run hung for ten hours, five test threads waiting on class
  initialization monitors. `TypeName`'s static initializer builds `ClassName` instances (`OBJECT`
  and the `BOXED_*` constants), and `ClassName` extends `TypeName`, so a thread that starts with
  `ClassName` holds it while waiting for `TypeName`, and one that starts with `TypeName` holds it
  while waiting for `ClassName`. `CatalogRefs` and `ScalarTypeResolver` queue behind them.
  Parallel test classes make the order a race; a generator or editor initializing these on two
  threads can meet the same cycle. The fix breaks the cycle in the fork: no subclass is built in
  `TypeName`'s initializer. A test that initializes the two from opposite ends on two threads pins
  it, a test-only warm-up being the symptom's cure rather than the cycle's.
* **A refused compaction does not depend on luck** `open`. H2 2.4's `SHUTDOWN COMPACT` copies the
  store on several threads and commits partway once the copy outgrows its buffer, and a commit
  landing mid-copy trips H2's own page bookkeeping, an assert, so only under `-ea`. Neither the
  buffer nor the threads can be set from outside, and compacting in place leaves six times what the
  copy does. `compactIfLast` retries once and reports both refusals, which a module run at a load
  near 19 showed clearing three refusals of five. What closes it is a compaction that cannot meet
  the race: H2's asserts off in the test JVM, which is how a build runs them, or an H2 that fixes
  it.
* **A forked LSP test JVM does not crash** `open`. Seen once, 2026-10-21: a `graphitron-lsp`
  module run lost its forked JVM to a SIGSEGV in G1's concurrent root-region scan, a collector
  thread faulting on an address that is not one, while `NodeTypeCompletionsTest` ran; the rerun and
  the full build were green. No Java frame is on the crashing stack. The LSP loads tree-sitter
  natively, so native-memory corruption is the first suspect if it recurs. The report's header and
  the crashing thread are in `roadmap/audits/2026-10-21-lsp-test-jvm-sigsegv-in-g1.md`. It did not
  reproduce: ten further runs the same day, two of them under G1's heap verification, were green,
  one crash in thirteen runs of the suite; the attempt, what the report says about parallel test
  workers and shared-arena closes before the fault, and what to keep if it happens again are in
  `roadmap/audits/2026-10-21-lsp-test-jvm-sigsegv-not-reproduced.md`.

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
  The field carrying `@routine` binds the type, which is what the directive used to repeat.
* **Removed: the MCP schema tool's `demand` slot** ("the demand relations go"). A tool reading it gets no slot; it
  restated directives the same entry shows.
* **Changed: `graphitron:capture` never fails over the schema it read** ("capture captures, and
  nothing else"). A schema that will not parse, assemble or classify is still captured, and what
  refused it is in the store as `graphql_schema_problem` rows rather than in the build's exit code.
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
