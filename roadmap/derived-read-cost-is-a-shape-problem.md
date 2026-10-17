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
    under the existing name, filled from one walk, with which walks reached a reading as payload so
    neither reader regroups. The arm tables are the hop relations' shape, which is this branch's
    vote in the survivor question below.
  * **The rule is examined, not only its storage** `open`. `broken` drives a correlated `NOT EXISTS`
    over `routes`; restate it as an anti-join and record the figure beside the storage ones.
  * **The reading's KEY arm is an equi-join** `done`. A three-way `OR` against `sql_constraint` was
    most of the reading's cost; a `UNION ALL` of two equi-joins matched it by `EXCEPT` on a schema
    that never exercised the jOOQ-name branch. Landed as three arms, qualified, SQL name and jOOQ
    name, disjoint by the resolver's precedence, and the same split applied to
    `FieldReferenceStepHops.namedKey`, which carried the identical `OR`. Not yet re-timed on sis:
    the 2026-10-06 `graphitron:dev` profile put the hops and the resolution together at about 25 s
    before it, the resolution alone about 23 s.
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
      `graphitron_field_source_table` under "The source is stated", with the class-backed parents
      beside it; 0 on sis and on sakila. The endpoints
      took the source from the enclosing type's own binding only, so a nested type had none and
      its chains reported `NO_ROUTE_FROM_DEPARTURE`: 99 rows over 60 types on sis, 11
      over 5 on sakila (`FilmDetails`, `FilmInfo`, `FilmInlineBundle` among them). A nested type
      reached from two parents on two tables has two sources, which `graphitron_field_table`'s
      key cannot hold, so the fix is `graphitron_field_source_table` under "The source is
      stated", recursive through the types with no table, and the chain rules reading it.
    * **A scalar `@reference` has a target** `done`, all 48 on sis and 14 on sakila gone, the cases
      in `chain-without-target`; `column-unresolved` stops declaring the two it pinned. The path's last table is where a column read
      through a chain is read, and with no target basis for it `CHAIN_WITHOUT_TARGET` fires: all 48
      on sis and 14 on sakila. The rule reads `graphitron_field_target` and
      `graphitron_field_target_table` instead of `graphitron_field_table`, and judges only a target
      that could bind a table, an object, an interface or a union, at a field carrying no `@routine`
      and no `@pivot`. Shares its question with "a routine resolving to nothing says so" below.
    * **A `@pivot` projection has a target** `done`, by the same rule, the six gone. Six sakila coordinates report
      `CHAIN_WITHOUT_TARGET` (`Film.titleTranslations`, `Film.prices` and their siblings): the target
      is an object binding no table, `TranslatedTexts` or `PriceByCurrency`, read through `@reference`
      and `@pivot`, so the path's last table is where its rows come from.
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
    * **A named self-referencing key reads one way** `done`. Both hops of a self-reference depart
      and arrive at one table, so the reading keeps the one the field's cardinality names, which
      is the generator's rule: along the key on a single-valued field, against it on a list.
    * **A condition-only element resolves from its method** `done`. The method row was the
      missing one, `CodeCapture` admitting only a `Condition` return where the generator checks
      none; a `Field<Boolean>` return is admitted beside it, read off the signature's type
      argument. The manual still documents `Condition` alone, deliberately.

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
  `graphitron_field_path` holds `@routine` and `@reference`, and
  `graphitron_field_participant_path` one element of one participant's path, keyed by the field, the
  participant and a position, both read off the same path elements by the graphitron anchor and
  marked and swept with it. `field-path-and-participant-path` states a field with two participants'
  paths and one left to discovery beside a `@reference` chain. On the sakila store the ten
  chain-break defects on `@referenceFor` fields are gone and the other thirteen are unchanged. Its
  reader is the walk, under "A path is resolved by a deterministic forward walk": a walk toward a
  participant's target type reads that participant's own path where it has one. Until then those
  ten fields get no chain at all, so the walk's first reading of them may surface real defects.
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
  mixes them. `@routine` and `@service` are left out of this branch.

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
  * **What an element names is stated at the entry grain** `open`. One row per written element of
    either directive at a field, an argument or an input field, keyed by its written position: the
    constraint a key spelling resolves to, by SQL name or `Keys` constant, qualified or not; the
    table a table spelling resolves to; the method a condition names and the two tables its
    signature declares; the result a routine reads. No direction, no source and no target, since
    none of them is a fact about what was written. The input-value step entries gain their first
    reader.
    * **The routine reading is read at the element** `done`. A `@routine` element's reading joins
      `graphitron_ast_routine_entry` at its written position and folds the name against the catalog's
      functions in parts, one candidate only; nothing about the target enters it, so no target check
      moves into the walk. `graphitron_field_routine`, which held the same resolution once per target
      keyed by an ordinal, and `FieldRoutines` go. Equal on sakila, 23 readings both ways; sis has no
      `@routine`. The cases are `field-path-routine`.
  * **Each element kind has a step rule** `open`. Small and separate, one per kind, each the answer
    to "this element, from this table":
    * a key, ridden from the table the walk stands at to its other end, one direction only between
      two tables;
    * a table, the one key between the table the walk stands at and the named one, failing on none
      or several;
    * a condition, the two tables its method's signature declares, the first admitting the table
      the walk stands at.

    One step relation for every site. Today an argument steps over
    `intent_argument_reference_step_hop`, from the dissolving `intent_` family, while a field and an
    input field step over `graphitron_field_reference_step_hop`; an element names the same thing
    whichever site it is written at, so the step is one relation and the sites differ only in where
    the walk starts.
    * **A self-referencing key is oriented by R1037's rule** `open`, R1037's to do. Both ends of the key are one table, so
      where the walk stands cannot say which way it rides. It rides forward unless the element
      writes `inverse: true`; until R1037 lands the step takes the field's cardinality as the
      generator does, and R1037 changes both, with the `@reference` reference page. Nothing in R876
      waits on it; `roadmap/audits/2026-10-14-reference-keys-in-consumer-schemas.md`.
  * **The forward walk is a relation of its own** `open`, after the two above and "A consumed key
    is a source", so every child field the walk reads has its source. From each of
    the field's sources in `graphitron_field_source_table` and nothing else; one row per position
    reached, stopping at the first element with no step from where it stands. It exists already in
    part: `FieldReferenceStepTargets` is a forward walk at the field grain over `ReferenceStepWalk`,
    the builder the argument and input-field walks share, read by the editor's `ClaimFacts` and the
    MCP server's `SchemaQueries`. It starts from the parent type's own table only, so on sakila it
    misses 38 fields with a path, read 2026-10-16: the 21 led by `@routine`, the fields under types
    with no table of their own (`FilmDetails`, `FilmInfo`, `FilmInlineBundle`,
    `FilmProjectedLeaves`, `HostOrigin`, `OccupantLocation`), the three class-backed ones, and two
    condition-only paths; the two-sided `graphitron_field_path_resolution` misses a different 28.
    This node starts that walk from the sources, and its readers then see every field.
  * **A broken path is a defect at the position the walk stopped** `open`, after the walk. The
    resolved path toward a target type is the walk where it reaches that type's table; a path that
    stops short is a defect naming the position it stopped at, and one that completes at another
    table is a defect naming that table. A field with a written path and no source is a defect of
    its own, nothing stating where it reads from, and that one covers a root field's path, a root
    having no source to join from. These are what `CHAIN_WITHOUT_TARGET` and the chain-break codes
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
  walks agree. A table-backed field's path is the `@routine` and `@reference` elements it
  wrote, or where it wrote none the key discovery finds between its source and its target type's
  table, or nothing at all for a leaf read on its source's own table. Stated as links like the
  written ones, so the target table is always where a path ends and no reader falls back to
  discovery for itself. The target type's binding is then the anchor discovery reads, and for a
  written path a check: a written path ending anywhere else is a defect.
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

* **The graphitron anchor runs after what it resolves against** `done`. `GraphitronAstCapture`
  runs after `CodeCapture`, and `graphitron-ast` declares `graphql-ast`, `jooq` and `code`. The
  anchor is placed to resolve and does not yet; its tables still copy strings, which is what the
  nodes below and every step `DerivationStratum` gives back to it change.
* **Four of the eleven go** `done`. `intent_condition_context_parameter`,
  `intent_external_field_contract_defect`, `intent_producer_cardinality_conflict` and
  `intent_scalar_java_type` were read by their own tests and by nothing else, and a view nothing
  uses has not shown it is worth saving, so each went with its tests, the scalar view's
  vocabulary gate among them.
* **The demand relations go** `done`. `intent_field_demand_rule` and its five siblings answered
  whether the generator owes a coordinate a verdict: a copy of the walk's registries, kept for a
  store-side classifier that does not exist. The generator never read them, MCP's schema tool
  restated directives it already showed, and the catch-all exemption restated every demand arm as a
  negation that had already drifted, missing `@routine`. They go with `DemandRuleTest`,
  `DemandShadowTest` and `DemandResidue`; `intent_type_domain`, the domain they quantified over,
  stays. R740's demand slice is delivered by this, and R677 is told its precedent is gone.
* **The condition route resolves in `graphitron_`** `done`. `graphitron_condition_method_route`,
  renamed and declared to the gatherer that already read it; declaring it is what cost anything,
  the comment being reduced to its grain and example with the argument moved into the declaration.
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
      reference as written, class and optional method, keyed by the `ExternalCodeReference` value's
      position, or `@sourceRow`'s directive, and referencing `graphql_ast_entry`. Written beside
      the twins by a walk over the vocabulary's types, so a site is found rather than listed, and
      pinned by a fact document stating twelve sites from the vocabulary, a multitable path
      condition two input types deep among them. `@record` is declared only so schemas keep
      parsing and is decoded by nothing, its two relations gone with it.
    * **The argMapping is written once** `done`. `graphitron_ast_argmapping_pair_entry`: one entry
      of one argMapping as written, keyed to the string's own `graphql_ast_value_entry` row and the
      entry's position, for `@routine`, `@service`, `@externalField` and `@condition` alike. Found
      by the vocabulary's name for a mapping through `VocabularyWalk`, which the code reference now
      walks on too, and read by `ArgMappingSigil.entries`, which judges no site; a sigil is a row
      as written. `columnMapping` stays `@routine`'s own. The vocabulary still calls argMapping
      inert on `@externalField`, which its reading and the generator's rejection follow until
      changed.
    * **The argMapping's readers are answered from the pair relation** `done`. Measured first: on
      sakila and sis the family is cheap and the pair relation holds the rows the old entry holds,
      in `roadmap/audits/2026-10-09-argmapping-and-derivation-timings.md`; each step is timed on
      the sis store again.
      * **The pair relation carries its path decomposition** `done`. Root, head and tail of what an
        entry binds to, generated by the database from the written text as the old entry's are.
      * **Where a mapping sits is an anchor** `done`. `graphitron_argmapping_site`: the directive,
        its ordinal, the element and its kind, the code reference the mapping belongs to and the
        path element it sits in, each a fixed hop through keys the transcription declares. The site
        literal the old entry carries welds three of those into one value, so the parts are what
        is stated. With the pair relation it holds exactly the old entry's coordinate, parameter
        and path, both ways. First a view; the rekeyed chain over it ran up to forty times the old
        chain's time, and the same rows in a keyed table brought it back level, so the
        graphitron-ast anchor writes it from `graphitron_argmapping_site_rule`, marked and swept.
        `graphql_directive_application` and its argument relation are declared to `graphql-ast`
        now, their writer.
      * **Each reader is answered from them** `done`. The readers downstream of the old entry are
        joined on its site literal, so the chain is rekeyed to the written mapping rather than
        flipped one view at a time, and a seeded test converts with the reader it pins. Each step
        is timed on the sis and sakila stores before and after.
        * **The candidate stage seeds a sigil from what was written** `done`. Admitting a sigil is
          the site's rule, so a candidate exists wherever one is written; on sakila the two seeds
          agree exactly, and `argmapping-pair` states the case.
        * **The chain is keyed by the written mapping** `done`. `graphitron_argmapping_match`, the
          three `intent_argmapping_` views and `intent_resolved_node_key_projection` carry the
          mapping string's position and the entry's in place of the site literal, and the site
          view's parts for what a reader needs to know of the site. `ArgmappingProjectionDefects`,
          `ResolvedKeyProjections` and `NodeIdDecodeCoverageFacts` take a site's wording from those
          parts, a message still naming the use site as it was spelled. `@externalField` and
          `@multitableReference` mappings, which the old decode never wrote, are filtered by
          directive so no consumer's answer moves. The parameter's method is the one
          `graphitron_code_reference` settled rather than a name match, and a defect points at the
          mapping string. On both stores old and new agree row for row, and time level. The seeded
          `ArgmappingMatchTest`, `ResolvedNodeKeyProjectionTest` and
          `ArgmappingProjectionDefectTest` are the fact documents `argmapping-match`,
          `resolved-node-key-projection` and `argmapping-projection-defect`, and
          `NodeIdDecodeDefectTest`'s mapped case is `node-id-decode-mapped-parameter`; the oracle
          is `ArgmappingProjectionDefectsTest` and `ArgmappingProjectionRejectionPipelineTest`.
        * **`graphitron_type_backing_rule` reads the pair relation** `done`. The parameter branch
          redirects a `@service` parameter through the pair's `root_name`, keyed to the reference the
          mapping is a field of through `graphitron_argmapping_site`, `@service` only; a `@condition`
          mapping on the same field redirects nothing, which `type-backing.graphqls` states.
        * **`intent_node_id_decode_slot` and `RoutineWriteFacts` read it** `done`. A `@routine`
          parameter's written path is the pair at the site's field and ordinal, and the slot's
          name-matched exclusion is any pair at the root field but `@externalField`'s and
          `@multitableReference`'s, which the old decode never wrote. Old and new slots agree row
          for row on both stores; sis answers in 302 ms against 1,139, sakila in 154 against 108,
          the difference being the mapped join over the rekeyed chain. The seeded redirect case is
          a field of `node-id-decode-mapped-parameter`. Open on the slot: a mapped `@service`
          naming two overloads reaches it as one untyped row where it drew one row per overload,
          which its comment still describes.
      * **The old entry goes** `done`. `graphitron_argmapping_entry`, its indexes and its writer in
        the registry decode, `GraphitronFactCapture`. `ArgmappingEntryTest`, `argmapping-entry`, the
        seeders and the pair half of `SupertypeSiteReferenceTest` go with it, the pair relation's
        foreign key to the written value holding what that orphan scan held.
      * **The registry decode reads no argMapping** `done`. Its quarantine rows for a mapping the
        grammar rejects had no reader, the build reporting one from `ArgBindingMap` and the editor
        from its own parse, so the reads went; `graphitron_undecoded_argument_entry` keeps the
        `columnMapping` it quarantines.
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
  * **`@table` cannot name a routine result** `done`. A type is bound to a routine's result
    implicitly, by the field returning it; binding one with `@table` is refused, by the walk while it
    is the build's gate and by a `TABLE_NAMES_ROUTINE` entry defect the editor reads. The example's
    `Tilgang @table(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr")` loses the directive and
    keeps working, which is the case that pins both halves.
    * **The manual says a routine result is bound by its field** `done`.
      `routine.adoc` says the `@table` spelling is accepted and changes nothing, and its implicit
      name-matched hop example binds `Brukertilgang` to a function with `@table`; both become the
      field's binding and the refusal. `code-generation-triggers.adoc` says the return type still
      binds a `@table`, and its routine example is generated from the corpus file, which loses the
      directive and is regenerated.
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
    `graphitron_ast_link_entry` and the `graphitron_ast_federation_key_` entries, so
    `graphitron_link_entry` and the three `graphitron_federation_key_` entries lost their last
    reader and went, with the decode arms that wrote them and `intent_federation_key`, whose
    readers read `graphitron_directive_application` instead.
  * **The condition entries are gone** `done`. `graphitron_field_condition_entry`,
    `graphitron_argument_condition_entry` and the two context-argument entries hanging off them,
    which the registry decode wrote and nothing read but rules asking whether a `@condition` sits at
    a coordinate. Those read `graphql_directive_application` there, and the MCP server's condition
    bindings read the written reference off `graphitron_code_reference_site`, still matching the
    census so an ambiguous one reports every candidate. Old and new agree on sakila and sis and time
    level. The seeded read-surface case is `read-surface-on-write`.
  * **The reference-step entries are gone** `blocked`, on the steps below and on "One resolution
    of an authored element stands". Four relations the registry decode writes, read for the path's
    shape, for the name a step spells, and by the hops and the read-time chain.
    * **The language server finds a spelled step through the step twins** `done`. Where a
      `@reference` or `@referenceFor` step names a table or a key, read off the eight table and key
      twins, field and input value alike, and located at the application holding the path, so an
      argument's `@referenceFor` is found as well. `graphitron_reference_for_step_entry` and
      `graphitron_argument_reference_for_step_entry` then had no reader and went; the registry
      decode still writes their method references and spellings.
    * **The language server locates a step at the name it wrote** `done`. A step is located at the
      string written under `table:` or `key:`, the value the twin's element holds, rather than at
      the application holding the path: that string is the reference an editor would change, a
      path naming one table at two steps is two uses, and a step several lines into a path is
      found where it was written. The twin keys the step by its element already, so the climb to
      the holder's parent goes.
    * **Whether a coordinate carries a path is read off its applications** `open`. The node-id
      instruction and column-scope rules and `intent_field_column_table` ask only that, at fields,
      input fields and arguments alike, which `graphql_directive_application` answers once an
      application states a hop: `@reference(path: [])` is the same as no directive, as the
      generator reads it on a field and in effect on an input value, and writes no step, so until
      R692 makes the empty path an author error the question is whether the applications state at
      least one element. Where a path's last step lands and how many steps an application has are
      asked only to find a hop's target, and move with the hops.
    * **The field-site step resolution gives way to the path resolution** `blocked`, for the column
      scope rule and the node-id instruction rule, on "The forward walk is a relation of its own": a scalar read
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

* **The goal captures and nothing else** `done`. A second pass through the generator re-read every
  corpus the gatherers had just written, kept nothing, and could fail the goal over a schema the
  store already held; the goal now opens the store, captures and closes it from the plugin, and
  fails only where it cannot capture.
  * A schema extending a built-in captures `done`, by "The specification is a document": an
    extension merges onto the specification's declaration instead of adding the type twice.
* **A capture commits as it goes** `done`. Each gatherer commits its own work in
  transactions of its own choosing, and `store_graph_progress` holds each one's last start,
  completion and time spent, recorded around the run rather than inside its transactions, with a
  row for the capture as a whole, so a hung or failed capture leaves on disk how far it got; the one outer
  transaction never held, `ANALYZE` committing it mid-capture. Readers do not consult it yet.

### The classified model dissolves into the store

The store is the model. The walk resolves the same schema a second time into records the validator
and the emitters read, and it goes when nothing reads what it builds.

* **What the store states of R333's facts is counted** `done`. Of the walk's 465 rejection calls, 411
  are constraints on a fact, in about 95 rules of which about 15 have a code; per fact, a leaf being
  a denormalized view over them. Evidence in `roadmap/audits/2026-10-08-fact-census.md`.
* **Every coordinate carries R333's facts, and a constraint a fact breaks is a located defect**
  `open`. The facts are what `generate` reads and their constraints are what `validate` reports, so
  neither needs a classification.
  * **The defect model is designed as a whole** `done`. One vocabulary of rules and one relation of
    violations located at an AST entry, where six families stood. Evidence in
    `roadmap/audits/2026-10-08-defect-model.md`.
  * **A defect rests on a basis and sits at an AST entry** `done`. `basis` in place of `fault`, the
    twelve codes `GRAPHITRON`, and the position a foreign key into `graphql_ast_entry`, the element
    `graphitron_entry_defect_site`'s fixed hops away.
  * **Every field has one fetcher** `open`. `graphitron_fetcher`: one row per output field of
    `graphitron_field`, complete over them, the supertype the fetcher's facts reference. R333's
    DataFetcher, one coordinate, one resolver, 1:1 and total, which joins `source` and `target` and
    dispatches the operation set. The row carries the key and the facts stay their own relations,
    so `graphitron_field_source`, the target and the operations reference it rather than each
    keying the field afresh, and a field no fact explains is an absent subtype row a gate can
    find. What `<Type>Fetchers.<field>` is emitted from.
  * **The producers are stated** `open`. A producer is a field where something is executed, and
    `graphitron_producer` holds those fields and no others: R333's `operation` is total over every
    coordinate, a bare column read being `Fetch`, so the producers are its verbs that execute and
    not the fact itself; how the two meet is refined as the work goes. Keyed by field, with its kind
    and `mutates`, true on a field of the mutation root type. A root field, a `@service`, a
    `@splitQuery` and a connection are producers; a `@routine` below a root that is not split, an
    `@externalField` and every projected field are not. A producer that reads and lands on a
    table-bound type leaves its window open, and every field below it that is not a producer is
    projected into its statement; a write closes it, the lock it takes being returned before anything
    more is read, and so does a `_service`. The hierarchy is by what executes and how it identifies
    its rows:
    * `_query`, SQL graphitron generates against tables:
      * `_select`, the rows a condition matches, consuming its source object's keys where it has one;
        keyset paging and one select per participant of a polymorphic type are properties of it;
      * `_lookup`, the rows for an ordered list of keys, one position per key and null for a miss:
        `@lookupKey`, `node`, `nodes`, `_entities`, and the re-read after a write over the keys it
        returned; `graphitron_type_reach.batched`, a node type or a type carrying `@key`, is this
        fact read off reach, which counts authored edges only, and comes from these producers once
        `node`, `nodes` and `_entities` are stated;
      * `_insert`, values only, no row identified, returning the keys the database made;
      * the identifying write, with `matches`, `ONE` or `MANY` as `multiRow` was written, `ONE` by
        default: one row per input, or a filter per input that may match several. `_update` carries
        a predicate and values, `_delete` a predicate alone and returns keys of rows that are gone.
        How a `MANY` update tells its filter from its values is out of scope; the model only records
        the intention, and behaviour does not change;
    * `_routine`, SQL generated around a database routine, keyed to the application that made it
      one;
    * `_service`, a Java method, keyed to its application.

    Upsert is retired and stays out. What makes a field a producer is read from what was written,
    the decoded entries and, for the bare `@splitQuery`, `graphitron_directive_application`; the
    deprecated coordinate-keyed `graphitron_split_query_entry`, `graphitron_service_entry` and
    `graphitron_routine_entry` are not read. Marked and swept from the start.
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
    * **A field's source says how many objects arrive** `done`. `graphitron_type_arrival` folds each
      type's arrival as the walk does, over the authored SDL; `graphitron_field_source` names ROOT,
      ONLY_CHILD or CHILD, agrees with the walk on every field it classifies, and a gate over the fact
      examples holds every output field to a source or a defect explaining it.
    * **The source object is stated by `graphitron_` relations** `open`. Whether a type is
      table-bound, a record, or a nesting type passing its parent's row through.
      * **A type's backing is anchored by the graphitron gatherer** `done`. `graphitron_type_backing`,
        marked and swept after the table bindings: one recursive statement over the producers'
        resolved methods and the members each backed class offers, an array path standing in for
        the cycle detection H2 lacks. It agrees with the `intent_` cluster on every capture of the
        model and generator suites, save an ambiguous reference, which grounds nothing here and
        draws `CODE_REFERENCE_METHOD_AMBIGUOUS`. On a consumer schema of 2345 types: 130 backings
        through 136 paths at most four deep.
      * **The readers move and the `intent_` cluster retires** `done`. Every reader in the generator,
        the language server and the MCP server reads `graphitron_type_backing`, a grounding being a
        `PRODUCER` row; the seeded cases are fact documents, plus one capture over classpaths the
        test compiles where the case is the classpath.
      * **Two backings for one type is a defect** `open`. What `intent_type_backing_conflict`
        reports, as a code at the type's declaration.
      * **A field's source object is stated** `blocked`, on "The code family reads the generated
        jOOQ classes", a record backing a type being a class the reading skips today.
        `graphitron_field_source_object`, one row per
        field whose parent arrives as a Java object rather than a row, keyed by the field with the
        class a foreign key into the code family: the parent type's backing, passed down through
        nesting types with neither a table nor a backing of their own. Only the class; its kind, its
        members and whether it is a jOOQ record are joins. A row is
        `graphitron_field_source_table`'s, so the source object says nothing about tables.
    * **A field's target is its target type** `done`. A field has a target type, and a target type
      is what is bound to a table. `graphitron_field_target` holds one row per field: the target
      type unwrapped, the element where the field returns a connection and the named type
      otherwise, scalars included, with the connection it was unwrapped from beside it. List and
      non-null wrapping are cardinality and stay on `graphql_field`. Everything else is a join on
      the target type: its kind to `graphql_type`, its participants to `graphql_poly_member`, its
      table to `graphitron_resolved_type_binding`, so two participants bound to one table are two
      rows of that join and a target with no table is a row with none. It is
      `graphitron_field_navigation` renamed, the wrapper's name in place of the two-value basis,
      in one commit with its readers. A mutation's target is its payload type. The refetch a
      mutation does is the carrier's own fact, `graphitron_carrier_data_field`, whose data field
      has a target of its own; it is not folded in here.
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
    * **The unlowerable ordering is a defect** `done`. A declared `@defaultOrder` or `@orderBy` on
      a field read once per participant is `ORDERING_ACROSS_PARTICIPANTS`, resting on `UNSUPPORTED`,
      at the declaring directive, in `graphitron_entry_defect_rule`. The unstored
      `intent_field_unlowerable_ordering` was evaluated four times a round at 45 to 80 ms on sakila;
      the rule is evaluated once per capture, at 2 to 3 ms on both stores, where both have no row.
      `UnlowerableOrderings.detect` mints the build error from the stored rows until entry defects
      fail the build, naming the target's members, and the editor reads the defect through the
      `diagnostic` view, so the rejection rows and their stage go. The primary-key fallback route
      went with the view, inert on a fan-out. The clause restating the classifier's precedence
      (`@service`, `Node`, `@lookupKey`, `@routine`, the query root, the list shape) moved with the
      rule; which route classifies a field is the fetcher's fact under "Every field has one
      fetcher", and the rule reads it from there once it is stated. The seeded cases are
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
        `graphitron_field_source_table`, keyed by the field and the source table: `SOURCE_TYPE`
        where the parent type binds a table, `INHERITED` through object types with no table of their
        own, from each field reaching them that wrote no path, walked at the type grain with a cycle
        guard. The head walk seeds from it, its only reader of a source, so
        `graphitron_field_table.from_*` has none left. Every source that relation held is here,
        and `NO_ROUTE_FROM_DEPARTURE` went from 99 to 0 on sis and from 11 to 3 on sakila. The cases
        are `field-source-table`.
      * **A root field led by `@routine` reads from the routine's result** `open`, set aside while
        `@routine` and `@service` are left out of the path branch. A root has no source, and a
        leading routine is where its query starts instead of its target: a `ROUTINE` row in
        `graphitron_field_source_table` naming the routine's result, a `sql_table` row of type
        `FUNCTION`, so the foreign key holds. `INHERITED` would narrow with it: it skips a field
        that wrote a path because such a field ends somewhere else, and a field whose only element
        is its leading routine ends at the routine's result. So tilgangsstyring's `Brukertilgang`,
        returned by `Query.mineTilganger @routine` with no `@table` and carrying three `@reference`
        fields, would take the function result as its fields' source, where today it has none; read
        off the rule and the SDL, not a capture. Sakila's `Tilgang` has no field with a path.
      * **A producer's consume step is stated** `open`, after the source object.
        `graphitron_field_source_key`, one row per field that takes keys from its source object,
        keyed by the field with a foreign key into `graphitron_field_source_object`: how it takes
        them and the table they match. `SOURCE_ROW`: `@sourceRow`'s method, called on the object,
        returns the source-side columns of the path's first key, the method a reference into the code
        family. `RECORD`: the object is the table's own record, its key read straight off it. Later a
        `TableRecord` member and members matching the first link's primary key; a consumer case for
        the first is tilgangsstyring's `DeaktiverApplikasjonerPayload` and its two siblings, plain
        types a root `@service` returns, whose `applikasjoner` member is a `List<SubjektRecord>`, so
        `subjekt` is its source and each participant's one key reaches its own table. It is the
        consume phase a `_select` producer reads its keys by, so the fetcher's DataLoader is
        generated from it, and once `graphitron_producer` is stated its field references that.
      * **A consumed key is a source** `open`, after the consume step. The forward walk waits on it.
        `graphitron_field_source_table` gains `CONSUMED`, read off `graphitron_field_source_key`'s
        table, and holds no fact about a record class or a method. Until then the two regressions are
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
    several is a defect of its own.
    * **A scalar without a path reads its source table** `open`. The column is on the field's
      source table, one row per table, so the relation is keyed by the field and the table. A
      nesting type, an object type with no table of its own projected against its owner's, retains
      its owner's source table and source object, which is `graphitron_field_source_table`'s
      `INHERITED` source: a scalar on it has a target column of its own on each table it retains,
      two where two owners on two tables reach the type. A field has a source object, a source
      table, both or neither. Where the source object is a table's own record, its scalars are that
      table's columns, read as `rec.get(TABLE.COLUMN)`: a `CONSUMED` source whose
      `graphitron_field_source_key` rule is `RECORD` gives target columns on the record's table, as
      `FilmDetailsCarrier`, backed by `FilmRecord`, reads `filmId` as `film.film_id`. A `SOURCE_ROW`
      source gives none: a `@sourceRow` parent is a plain class whose scalars are read off the
      object, the source saying only where its batched query starts. A scalar whose type has no
      source table at all reads from its source object, which is no column and no row here.
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
    * **A pinned key disagreeing with the published one is a defect** `blocked`, on node metadata
      being a catalog fact, in "A node is settled with the decode that states it". Only
      `intent_node_metadata_defect` states it today, and a `graphitron_` rule reading `intent_` is
      the crossing this branch removes.
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
    * **A field on the subscription root is a defect** `done`. `SUBSCRIPTION_FIELD`, the first code
      resting on `UNSUPPORTED`, at the field's own declaration and keyed on the root operation; the
      diagnostic surface reports it as not actionable. Stated by `facts/subscription-field.graphqls`.
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
  generated name colliding is `GRAPHITRON`, an emission not written yet `UNSUPPORTED`; nothing is the
  generator's fault, the generator running only on a schema that validates.
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
  * **`AttributedRegistry.load` dissolves** `done`. The refusal cases and the walk's configured-tag
    cases dissolved into what the capture path already pins, the configured tags and notes are read
    off the emitted schema, and `CapturedSchemaAgreementTest` went with the second reading it
    compared. `EmittedRegistry`'s overloads over `AttributedRegistry`, kept alive only by tests, went
    after it, and with them the handle on the composition nothing else read.
* **Capture hands back the emitted schema** `done`. `EmittedRegistry` runs in capture and
  `CapturedSchema` carries both; the generator renders the emitted one and reads the written one
  only for the walk.
  * **The emitted schema is derived right after the anchor** `done`, the macros being one
    mint. They had diverged: the connection and facet mint was anchored by `EmittedAnchor`, while key
    synthesis was a view over the deprecated decode and the node rule, and tag inheritance was Java
    inside `EmittedRegistry`, the `graphitron_` anchors having no relation for a directive
    application to be minted to.
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
      states the tag rule over it as a third set of the directive anchor, and
      `graphitron_inherited_directive` is the intersection over the carriers.
      * **The inheritance reads stored carriers** `done`. `graphitron_configured_tag`,
        `graphitron_minted_coinage` and `graphitron_carrier_directive` are stored by `EmittedAnchor`
        from `_rule` views, H2 having re-run each view per joined row until a capture of sis never
        finished its directive anchor; `roadmap/audits/2026-10-09-federation-chain-hang.md`.
    * **The rule reads no deprecated decode** `done`. The key and link entries it read are retired;
      see "No `graphitron_*_entry` survives".
    * **`EmittedRegistry` renders the anchors and states no rule** `done`. It patches the anchored
      elements and applies the minted applications, and the narrowing it reports reads the rule's
      output.
* **The schema classes and the SDL read only the emitted schema** `open`. The schema classes take
  their forms from the emitted schema, `CarriesObjectForm.formOf` reading it before a variant's own
  form, so the walk's own forms render nothing; the variants still carry them.
  * **The emitted schema holds only what is published** `open`. `SchemaSdlEmitter` filters two
    things out of it as it prints: the directive support types the walk did not keep, read off the
    walk's types, and graphitron's own directives, by `SchemaDirectiveRegistry.isSurvivor`. Both are
    decisions about what is published, so `EmittedRegistry` makes them and the printer filters
    nothing. The support-type rule is structural and needs no classification: a support type is
    published only in the published tier, `SortDirection` today, and only where an element of a
    type outside the support set references it; a strictly internal one never is.
    * **`CompletionData` dissolves** `done`. `CatalogBuilder` read `@node` off the emitted schema,
      the one reader of a graphitron directive there, for a catalog nothing read: the editor reads
      the store, and the dev loop only logged the catalog's counts. With it went `CatalogBuilder`,
      the generator's classpath census, read only to build the catalog, and `ClasspathCensus` and
      `ClasspathScanner` with it; capture reads the classpath into the `code_` family on its own.
      R1008, a flaky report from that census, dissolved with it.
      * **`TypeBackingShape` dissolves** `done`. What survived of the catalog was the walk's view
        of a type's backing, read off the classified model, in main sources with only tests calling
        it; the store states the backing in `graphitron_type_backing`. The tests that pinned the
        projection went with it. The backing comparison went too, every case it ran being one
        `TypeBackingClassTest` already pins against the store, and the plugin's completion
        pipeline asks the store which class backs a type.
  * **The print form follows the schema's own link** `open`. `SchemaSdlEmitter` prints the
    federation service form on the walk's `federationLink`; the emitted schema carries the
    federation `@link` itself, the configured one included while the composition synthesises it,
    so the form is read off the schema it prints.
  * **The schema class registers what the emitted schema and the store state** `open`.
    `GraphitronSchemaClassGenerator` takes the roots, whether there are nodes, the scalars, the error
    types and the federation entities from the walk's types.
* **The datafetchers and the rest are generated from the store** `open`. `EmitPlan` reads the store
  already; the per-type generators still take the walk's model. What they read is a few queries,
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
  * **Flip: the generator reads the three queries** `blocked`, on the producers, the source, how a
    field projects, and key forwarding in the node-id branch, whose readers the catalogue found: a
    `@nodeId` through a foreign key reads the key column without a join, a batched single object
    forwards the parent's foreign key, and a path of several hops forwards the first hop's. A nested
    type without a table becomes one class with an overload per owning table, where the generator
    emits one class per owner today: `CustomerOccupantLocation` and `StoreOccupantLocation` are one
    body for `OccupantLocation`.
  * **Subtract what the producers replace** `blocked`, on the flip. Each relation the new ones replace goes once its readers
    read the replacement:
    * `intent_field_separate_fetch`, replaced by `graphitron_producer`, read by the editor's
      `ClaimFacts` and `InlayFacts`;
    * whatever the generator's own classification stops being asked.

    The producer-method views, `intent_field_producer_method` and its neighbours, are the reference
    branch's to retire, through `graphitron_code_reference`, which the service producer joins to.
* **The generator builds no schema** `blocked`, on the two nodes above: the walk goes when nothing
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
  * **The configuration family is pinned by fact documents** `done`. A document states the
    configuration it is captured under with `@withConfiguration`, which the fact harness reads off
    its text before the capture, a stepped graph's reading taking it from the files as they then
    stand. `configuration` states every parameter landing, `configuration-none` a run that
    declared nothing, and `configuration-sweep` three readings: parameters taken out are swept,
    and an unchanged configuration is restamped. `@assertEmpty` is how a document says a relation
    holds no row for its graph. `StoreEntriesTest` keeps the recipe, which a document cannot state,
    its recipe being the harness's own, and the sibling-graph case. A broken row in each document
    was run and fails.
* **Configured tags have one producer** `done`. `TagApplier` put them into the written composition
  in Java while `graphitron_configured_tag` stated the same rule in SQL for inheritance to read, so a
  carrier's own tags and the ones its minted types inherit came from two rules nothing compared.
  `TagApplier` is gone: the emitted schema applies the `CONFIGURED` rows as it applies the `MINTED`
  ones, at any coordinate kind, and the written schema carries no configured tag.
  `ConfiguredTagTest` pins every kind the rule names on both schemas, and the federation pipeline
  test compiles the schema classes a run writes and reads the tags off the types they build.
  * **Enum values are anchored** `done`. `graphitron_element` admits them, so the rule states their
    tags, and `graphitron_enum_value` is their part relation beside the other three, so a reader
    reaches an enum value's type and name by a join rather than by taking its coordinate apart.
  * **The schema classes read the configured tags off the emitted schema** `done`. The walk's
    own connection types inherit only authored tags now, so `CarriesObjectForm.formOf` reads the schema's
    type first, and the enum and input classes render the schema's forms rather than the walk's.
* **The configuration's rewrites leave the composition** `open`, so the written schema is what was
  written.
  * **`FederationLinkComposer` leaves the composition** `blocked`, on the generator building no
    schema. It replaced `TagLinkSynthesiser`, and rewrites the author's link with the settled imports
    or adds graphitron's own. The written schema needs no `@tag` declaration now; the walk's federation opt-in is the one reader
    left, and the emitted schema carries the link today only because it starts from the composition.
    When it goes, `EmittedRegistry` applies the link and the definitions it imports for a
    `CONFIGURED` row of `graphitron_federation_link`, so the emitted schema keeps saying it is
    federated.
    * **The opt-in is one relation** `done`. `graphitron_federation_link` states whether a graph is
      federated and how, an authored federation `@link` or a configured tag where none is written,
      and `graphitron_federation_link_import` what the link imports, both anchored and marked and
      swept. The key mint reads it in place of the link entries and
      `graphql_assembly_synthesised_link`, which goes with its writer, nothing else reading it.
      * **The federation link is decoded at capture** `done`. `graphitron_ast_federation_link_entry`
        states which `@link` applications name the federation specification and the version, where
        `FederationSpec` is a constant, so no view filters urls with a literal and the test pinning
        that literal to the constant dissolves.
    * **A configured tag the authored link does not import is a detection** `open`, over the
      relation, where it is a refusal of the composition today.
  * **`DescriptionNoteApplier` follows** `done`. The original description is the document's, in
    `graphql_`; the note is the document's configuration, in `graphql_source_input`; and the
    description the generator emits is minted, the anchor appending each element's note to it in the
    `graphitron_` part relations' own `description`, a type's from the file holding its base
    definition. `ConfiguredNoteTest` pinned the applier's behaviour before it went, and checks the
    part relations beside the emitted schema. An editor knows a description is minted from the
    document's configuration, and can show the minted text as a hint.
    * **The part relations are read by their columns** `done`. `EmittedRegistry` reads the type,
      member and argument from the four part relations, where it took coordinates apart, and sets
      an element's description from its part relation where the anchor minted one.
* **The vocabulary is emitted and minted** `open`. "Synthesis" named code that edited a registry in
  place; what graphitron adds to the schema is minted, by a rule over the store, and the schema
  capture derives is the emitted schema. `CapturedSchema.synthesised` becomes `emitted`, with the
  javadoc and tests that say "post-synthesis", and what is left of the old word goes with the code
  it names.
  * **The federation key is one rule of the mint** `done`. The synthesized-key view dissolved into
    `graphitron_directive_application_minted`: the node types of a federated graph with no authored
    id key, read from `graphitron_node`, so a `@node` that is an error is not keyed. Its constant
    arguments are rows of `graphitron_directive_application_arg_minted`, and its readers read the
    minted applications.
    * **A configured link mints no key it does not import** `done`, a regression. Reported
      2026-10-15 from a live session. With the author's federation `@link` and `@key`s removed and
      the inputs still tagged, `TagLinkSynthesiser`'s link imports `@tag` alone, yet the opt-in
      reads it as `CONFIGURED` and the mint puts `@key(fields: "id")` on every `@node` type: twelve
      "Federation directive '@key' is not declared" refusals of graphitron's own injection, which
      the author cannot fix, so no author can leave federation while tags are configured. Decided:
      the link imports what the mint writes, authored and configured alike, and an author's own
      declaration of an imported directive collides as it does today. Closed by "The effective
      imports are graphitron's rule".
  * **A link's imported definitions are captured where the link is** `open`, a regression.
    Reported 2026-10-15 from a live session. With the authored federation `@link` in place,
    generate accepts `@tag` and `@key` but the editor flags each as "Unknown directive":
    `DiagnosticFacts.directiveName` judges against `graphql_directive`, which holds only
    definitions written in a document, while `FederationLinkApplier` adds the imported ones to the
    generator's registry alone. The store has the link (`graphitron_federation_link`) and not what
    it imports. The imported definitions are captured as base facts, sourced to the link, so the
    editor and the generator read one vocabulary; the sibling node above reads the same fact.
    Federation's side is "Federation is a gatherer of its own"; the editor reading the effective
    vocabulary is the emitted directive anchors. After "The effective imports are graphitron's
    rule" (done), whose imports are the vocabulary the editor reads.
  * **`graphitron_synthesized_node_type_id` is named in the mint's vocabulary** `open`, its author's
    to rename.
* **Federation is a gatherer of its own** `open`. Federation's facts are neither the SDL's nor
  graphitron's: what a federation version defines is federation-jvm's, and what a document's `@link`
  and `@key` say is decoded today by graphitron's walk. A `federation` gatherer runs between the
  graphql gatherers and graphitron, in a `federation_` family, reading `graphql-source` and
  `graphql-ast`; `graphitron-ast` reads it. What depends on graphitron's mint, the imports a link
  ends up with, stays graphitron's.
  * **Federation's versions and their imports are captured** `done`. `federation_version`
    is one row per spec version the library supports, keyed by its url; `federation_version_import`
    one row per name that version's link may import, spelled as imports spell it (`@key`,
    `FieldSet`). Read through the library's public API: versions probed upward from v2.0 until
    `loadFederationSpecDefinitions` refuses, a test pinning the set, and each name in a version's
    definitions tried as an import, since several versions share one definition file and the
    library gates some of a file below a directive's minimum version. Marked and swept under one
    `store_source` row for the library, kind `LIBRARY`, named by group and artifact and stamped by
    the jar's content, the jar carrying no Maven metadata to name its release by; a changed stamp
    is read whole. Needs no SDL, so it moves nothing. `FederationCapture` runs after the graphql
    gatherers; federation-versions pins v2.0 to v2.15 and the imports graphitron mints, and
    `FederationCaptureTest` the read-once and the re-read.
  * **The effective imports are graphitron's rule** `done`. `graphitron_federation_link_import`
    is the authored imports plus each MINTED or CONFIGURED directive the link's version may import
    and the link does not, written after `EmittedAnchor`; the configured link names the generator's
    version and its hard-coded `@tag` is gone. `FederationLinkComposer` replaces
    `TagLinkSynthesiser`: `LoadingRewrites` takes the settled link and rewrites the author's
    `@link` in place, or adds graphitron's own, and `TagNotImported` is gone. The assembly reads
    the anchor, so `ModelCapture` runs it after `GraphitronAstCapture`. An author's own declaration
    of an imported directive collides at the declaration. Closed "A configured link mints no key it
    does not import"; the manual's tag sections say so.
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

### A capture reads only what changed

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
* **An entry gone from disk can be deleted** `done`. It could not: `code_type` keyed `store_source`
  with no `ON DELETE`, so `reclaim` deleting a vanished entry was refused and the whole capture
  threw. `CodeCaptureTest` now removes an entry from disk between two readings and holds every
  classpath relation empty for it afterwards, against a control entry that stays. The dictionary's
  key and the positions' keys into it cascade; `sql_schema` and `sql_enum_binding` key
  `store_source` the same way and are the catalog's to look at.
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
  is what an emitter declares a type from. `element_class` answers which class backs a GraphQL type,
  through `intent_type_backing_seed` to five language-server surfaces and `TypeBackingRows`.
  `delivery` answers whether a field declared a list is backed by a method that delivers one, which
  a cardinality-conflict view stated until it was retired for having no reader, so the column has
  none now. `erased_class` is what a
  binding compares against, at argument mapping and at a `@nodeId` decode's landing.
* **A use site carries its own parse** `done`. `code_method` and `code_method_parameter` each hold
  their own erasure, delivered class and delivery. Nothing new is computed: the reading reached all
  three at the position already and deduplicated them into the dictionary, so siting them is
  declining to deduplicate. Held against the dictionary in both directions while both exist, which
  is an oracle only this order affords.
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
* **A slot carries its own parse** `done`. `code_read_slot` and `code_write_slot` each hold the
  erasure, delivered class and delivery of their own type, beside the spelling they already had,
  and `CodeCaptureTest` holds both against the dictionary as it holds the method positions. The
  node read the other way round first, the slot reaching its type through its accessor's
  `code_method` row, and inheritance is what turned it: what a member yields is a fact about the
  class offering it and not about the method behind it. An inherited accessor has no row under the
  offering class and may sit in an entry the reading did not read; a constructor has no
  `code_method` row at all, so a POSITIONAL write slot would have nothing to reach; and a base class
  declaring `K getId()` offers `Integer` to a subclass extending it at `Integer`, which only the
  offering class can say. That last case is a known limit rather than a fixed one: the spelling is
  the accessor's declared result, type variables unsubstituted, so it reads `K`. The slot is where
  the substituted type goes when the reading states it.
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
* **`code_construction` keys on the class it is about** `done`. It states how a value of one class is
  made and was keyed by a type spelling, a class fact filed under a type key. It keys into
  `code_class` now and `code_write_slot` follows it, so a class the next reading does not find takes
  its construction and its write slots with it.
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
* **The documents run after the classpath and the catalog** `done`. The three `graphql-` gatherers
  declare no upstream, and the first gatherer reading them after the corpora is `graphitron-ast`, so
  this move changed no row either. `ModelCapture` comments only what the code cannot say: the
  narration it carried did not keep the order right, and the gate below is what will.
* **The classpath is configuration** `done`. `store_graph_dependency` holds one row per entry of
  the classpath the build handed a graph's run, written by the configuration gatherer with the
  rest of the `store_graph_*` rows and swept with them. Each row says what the producer resolved
  the entry as: a directory the build writes, a release jar, or a snapshot jar, the plugin telling
  the two jars apart by the artifact's version and a producer that resolved nothing handing over
  snapshots. Origin and coordinate moved there from `store_source`, being facts about one module's
  build rather than about the bytes: one jar is declared by one module and dragged in by another.
  `classpath-source` declares `configuration` as a corpus beside `classpath`, and the reading is
  handed the classpath rather than reading these rows back, on `graphql-source`'s terms. A claim
  does not reference the dependency: the reading already lets go of an entry the build stopped
  declaring, so a cascade would replace a delete that works and buy nothing.
* **Code does not depend on jOOQ** `done`. The two families are parallel: each reads its own
  corpus under the configuration, and neither keys the other. The reading skipped the generated
  jOOQ package as the catalog's to describe, which "The code family reads the generated jOOQ
  classes" reverses. Two reads cross today, one child each.
  * **The table a parameter is bound to is the graphitron anchor's** `done`. No relation states
    it: the resolution that needs it joins the parameter's erased class to `sql_table.class_fqn`
    among the graph's own sources, and stores what it resolved keyed to both sides. A chain link a
    condition method routes is `graphitron_field_path_resolution_condition`, keyed to
    `code_condition_method` and to the tables, so a method that changes signature takes its
    readings with it. The code gatherer no longer reads the catalog, `code` no longer declares
    `jooq`, and a table added to the catalog binds on the next capture where it used to wait for
    the code entry to change. `code_method_parameter.role` keeps `TABLE_CONCRETE`, a fact about
    the signature alone.
  * **The reading's loader comes from the classpath it read** `done`. Capture takes the
    configuration, the classpath and the loader over it, and each gatherer reads its own corpus
    from those: the jOOQ gatherer builds its catalog from the configured package and the loader,
    and nothing hands capture a built catalog. Fixtures state the same two things a run does, a
    package and a loader, so `JooqCatalog` is no longer what a test starts from and is free to
    change. The sentinel package a goal tolerating none substitutes is no package.
  * **Ancestry is read from class headers** `done`. `ClassHeaders` reads the header of a class
    the census does not hold, superclass, interfaces and flags, from the classpath's entries in
    classpath order and then the platform's modules, so whether a type is a `Table`, a
    `DSLContext`, an enum or a `Throwable` is answered without loading anything. The census
    answers for its own classes, enums included, where the loader used to be asked. What still
    loads is a scalar constant's coercing, which only running its initialiser can say. Fixtures
    whose loader had been answering from the test JVM's classpath now carry what a run's
    classpath carries: the generated catalog and the jOOQ runtime, as transitive entries.
* **The code family reads the generated jOOQ classes** `open`. The reading's skip of the generated
  package was the wrong call: a class the model reasons about, a record backing a type or a table
  class a parameter takes, has to be a `code_class` fact a graphitron relation can key into, and
  describing it only through the catalog's name columns leaves no key to hold. The two families
  stay parallel and complete each: the catalog keeps `record_class_fqn`, `class_fqn` and the record
  supertype closure as names, the code family captures the classes from its own corpus, and
  neither keys the other. Measured on sis first, the generated package's class count and the
  capture's time before and after.
  * **A service beside the generated classes is read** `open`, with the node above. Services are
    commonly written in the module jOOQ generates into, and the exclusion took the package and
    every package under it, so a service package beneath the generated one was dropped silently.
    With no exclusion there is nothing to pin; a case states that such a service is read.
  * **The manual stops saying the reading leaves the jOOQ package out** `open`, with the node
    above, if it says so anywhere; the `jooqPackage` row of `mojo-configuration.adoc` roots the
    catalog there and nothing more.
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

* **How a sibling module reaches a run is decided** `done`. The Quarkus model, for every goal, so
  there is one answer to learn. A workspace module resolves to its own output before the repository,
  `target/classes` first, then its jar in `target/`, and the repository last; graphitron's goals do
  not compile siblings, those being the IDE's or the build's to compile. The dev goal compiles its own
  module before it starts, running the plan itself rather than forking a lifecycle, because a dev
  tool needs to control what runs. Evidence:
  `roadmap/audits/2026-10-12-how-a-sibling-module-reaches-a-run.md`.
  * **A workspace module resolves to its own output, in place** `done`. The workspace is every
    module under the outermost aggregator, `AbstractRewriteMojo.workspaceModules`, each coordinate
    read from its pom with the parent's `groupId` inherited, the projects Maven loaded answering
    first. A classpath element that is one of a module's outputs or carries its coordinate, an
    installed copy among them, is replaced at its own position by the module's `target/classes`,
    else its jar in `target/`, else left as Maven resolved it, and classifies `REACTOR` or
    `SIBLING` under the module's coordinate. A module the classpath does not hold is appended
    behind as `SIBLING`, named by coordinate where the scan used to name it by directory, and the
    source-root walk covers the same modules, so completion and goto-definition agree.
    * **The sibling scan's classification is pinned** `done`. `InstalledSiblingTest` stated first
      what a goal run inside one module made of a declared sibling: the installed jar `DECLARED`,
      the sibling's `target/classes` `SIBLING` behind it, and a class added since the install
      refused as the build's own undeclared module, the opptak symptom told wrongly. The same case
      now states the replacement: the sibling's classes in the jar's place, `REACTOR`, and the
      added class nameable.
  * **The dev goal runs its module's plan through compile** `done`. Before its first round the goal
    asks Maven for its own module's plan through `process-classes` and runs it, the first round
    taking the place of the module's `graphitron:generate`, or running before the first `compile`
    goal where none is bound, so the module generates once and compiles what the round wrote
    (`ModulePlan`). A session that already compiled runs the round alone, and so does
    `graphitron.dev.compileModule` off. A goal that fails is a warning and the loop starts anyway,
    the loop being what shows the developer what is wrong.
    * **The module's plan leaves the goal able to start its MCP server** `done`, a regression
      reported 2026-10-15 and reproduced on `graphitron-sakila-example`. Running the module's plan
      left the module's project realm as the thread's context classloader, which exists where the
      module declares a build extension (`quarkus-maven-plugin` with `<extensions>true</extensions>`
      there; sis declares none, which is why it ran clean), and the MCP SDK finds its JSON mapper
      through the context classloader. The dev goal now puts its own loader back on the thread
      before each step of the plan and after the last, the first round included;
      `ModulePlanRunTest` runs a goal that leaves another loader behind.
  * **The manual says where a sibling's classes come from** `done`. `dev-loop-internals.adoc`
    § Multi-module projects and the codegen classpath section and `compileModule` row of
    `mojo-configuration.adoc`.
* **A reactor entry is never a release** `done`. A workspace module's entry takes its kind from the
  path the run reads, a directory or a snapshot jar, whatever version it builds at; release is
  trusted only for a jar a repository resolved.
* An entry says what it is `done`. Both halves of the classpath corpus were handed to capture as this
  module's own output, which was false twice, and the lie was load-bearing: it removed the reactor
  limit the `@service` arm leans on.
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
  * **What stale compares is decided** `done`. The unit is a workspace module, and it is stale when
    the run read its installed copy because it has no output, or when its newest source is newer than
    the newest output the run reads: its `target/classes` class files, or its jar. Sources are its
    Java sources. The output half is captured already, every class file's
    time in `store_class_file`; the module and its sources are not, so the rule waits on the two
    nodes below. Whether a rebuild while dev runs clears the warning live is the parent's to settle.
    * **A workspace module is a configuration row** `done`. `store_graph_module`, one row per
      workspace module the run reads, keyed by graph and coordinate, with where it lives and where
      its build writes; `store_graph_module_entry`, which classpath entry is a form of which module,
      so neither relation carries a column empty on most rows. Written by the configuration gatherer
      from the entries, each `ClasspathEntry` carrying the `WorkspaceModule` the plugin resolved it
      to, the project's own output included.
    * **A module's newest source is captured** `done`. `java_source_root`, one row per root the
      `java-source` gatherer walks with the newest modification time among its `.java` files,
      written and swept in the refresh that writes `java_file`. A time and not a stamp, because
      content cannot say whether a compile came after an edit; the family's refusal to trust times
      stands for what it governs, whether a file is read again. Java sources only: the names behind
      a "not found" are classes, methods and tables, and a resource-only change is the rare miss an
      advisory warning can afford.
  * **The stale modules are a rule** `done`. `build_warning_stale_module`, one row per workspace
    module a run read stale, with its reason and both times: `NO_OUTPUT` where the entry the run
    read is neither the module's classes directory nor under its build directory, so an installed
    copy, and `SOURCES_NEWER` where the module's own output's newest class time predates its
    `src/main/java` root's newest file. A view evaluated when read, its source half being written by
    the dev loop's walk after a capture's derivations have run, so `SOURCES_NEWER` exists in a dev
    session and `NO_OUTPUT` in any capture. The derivation gatherer reads `classpath-source` for the
    class times.
  * **A stale module is said where the developer looks** `done`. One sentence, the view's
    `message`: the module, what is old, and `mvn -pl :<artifactId> compile` from the reactor root.
    In the `diagnostic` union as a warning of a third source, `classpath`, which is MCP's
    diagnostics; in MCP `status` as `staleModules`; in the dev goal's console after each source walk
    and classpath round; and in the editor as a `window/showMessage`, LSP's channel for a problem
    with no place, a stale module being located in no schema file. Each reader says a module once
    when it goes stale and once when it is compiled again (`StaleModules`), which settles whether a
    rebuild while dev runs clears the warning live: it does.
  * **The not-found diagnostics point at their cause** `done`. While a graph reads a workspace
    module stale, each error it may have caused, a rejection, a claim conflict or an entry defect,
    ends with `build_warning_stale_module_hint`'s clause: how many modules were read stale, that
    this may be why, and where the warnings naming them are, so many errors read as one cause; the
    count rather than the names, the schema folding no set into one string; the clause goes when the
    modules
    compile. Per graph and not per error, because which module an error needed is not recorded: an
    unknown class or condition reaches the store as a sentence, and an unknown method names no class.
    Joining an error to the one module that would carry its name needs the rejections to say what
    they looked for, which is its own capture. Cost on sis with four stale modules and a thousand
    error rows: the LSP's read of `diagnostic` 456 ms with the message against 366 to 396 ms
    without, the clause's view 0.6 ms.
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
  gatherer records a class's node metadata only where `__NODE_TYPE_ID` and `__NODE_KEY_COLUMNS` are
  a well-formed pair: a non-empty string and a non-empty array of columns the table has. Anything
  else publishes nothing, and graphitron falls back to its other tiers as it does today. A class
  declaring either field without the pair being usable is a row of `sql_node_metadata_unreadable`,
  reported as one warning, `NODE_METADATA_UNREADABLE`, at the `@table` of each type bound to it:
  that it was noticed and failed, not why, the fallback changing the ids the type encodes. The ten
  forms the crawler recorded, `intent_node_metadata_defect` and the filter `Nodes` and
  `NodeKeyColumns` applied over it are gone, the crawler and the walk reading one probe. Stated by
  `facts/node-metadata-unreadable.graphqls`, over a sakila table whose class publishes half the pair.
* **A key column is the column it is, not the name it spells** `done`. `__NODE_KEY_COLUMNS` holds
  the table's own field instances, and so does each index's sort, so capture resolves a key column
  and an index column by identity against the table's fields, falling back to the one field spelled
  exactly the same, through `JooqCatalog.columnOf`; the walk's node metadata probe reads it too. A
  field of another table, whatever it is named, makes the node metadata an attempt that failed.
  Capture no longer reaches `JooqCatalog.findColumn`, case-insensitive and taking the first match,
  and the silent pick R724 described goes with it; `findColumn` is deleted when its last walk caller
  goes. Stated by
  `facts/node-metadata-foreign-key-column.graphqls`, over a sakila table whose array names
  `film.film_id` beside a `film_id` of its own.
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
  reach marked batched, a `@nodeId` resolved to a node that does not exist. Its three readers,
  `graphitron_type_reach_rule`, `graphitron_node_id_instruction_rule` and
  `intent_node_container_member`, read `graphitron_node`, and the view goes. A node is what the
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
* **The entry states what was written** `done`. A row per `@nodeId` application, which is what says
  the directive was there, and `node_type_ref` nullable, NULL on a bare one. Both entries' columns
  are `NOT NULL` today and the input-value one writes no row for a bare `@nodeId`, so the columns,
  both writers and that comment change.
* **Measure the move from table to type** `done`. Today the walk's
  `BuildContext.inferNodeTypeOverTable` and the instruction relation take every bare `@nodeId` as
  the one node over a table: the return type's, the containing one, or `@mutation(table:)`'s. For
  every bare `@nodeId` in the fixtures and in a tagged sis capture, the type answer below and the
  table answer side by side, with every place an `id` is read as a node id by its name alone, so
  the switch is known to be a fix or a break before it is made. Measured over the sakila
  example, a sis store and opptak, 465 coordinates. Every bare output field is a `Node.id`; the type
  and table answers differ only where a union return names its container rather than its members,
  and where a payload-returning mutation leaves the table rung to answer, as it does today; and the
  by-name `id` carries 83 mutation input fields in sis, as the reference documents.
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
    entries and capture reads their classfiles as a run does; `CodeRows.writeStated`,
    `ClasspathSourceCapture.stated` and the census parameter are gone, and a real read of the
    largest fixture root costs what the projection did.
    * **A scanned census becomes the root it was scanned from** `done`. The language server's and
      MCP server's fixtures, `TestSchemaHelper`, `CorpusStore` and the derive tests pass the root,
      with no expectation moving.
    * **A hand-built census becomes real classes** `done`. In the service corpus: the backing
      graph, a package compiled without `-parameters`, and the shapes the editor tests named,
      `ClasspathCorpus` declaring the module as the consumer's code and extended-scalars as a
      library. One case moved its claim: a type grounded on a generated record jumps to the
      record's table, so the member-less class is one no entry declares.
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
* **The fixture catalog is one catalog over every schema** `done`. `graphitron-sakila-db` generates
  four catalogs from one database: `public`, `nodeidfixture` and `idreffixture` each pinned to one
  `<inputSchema>`, and the multischema fixture over `multischema_a` and `multischema_b`, which omits
  it to get jOOQ's per-schema sub-package layout, the one consumers with several schemas have. The
  split is history rather than design: the two node-id fixtures were catalogs of their own for small
  tests, and the generator they needed now runs on `public` too. A fact document is captured against
  `public` alone, so the shapes the other fixtures hold, metadata keyed off the primary key or one
  table name in two schemas, are gaps to every document though the database has them.
  * The layout is `JooqCatalog`'s alone. A pair of its tests, one over the combined catalog and one
    over a small single-schema fixture (a few tables with a foreign key, a composite key and node
    metadata), ask both the same questions: a table found bare and qualified, its columns, keys,
    indexes and node metadata, its routines, and the classes it names. Nothing outside
    `JooqCatalog` reads a layout.
    * **The single-schema fixture is its own schema** `done`. `singleschema`, generated by a codegen
      pinned to it into the root package and left out of the fixture catalog: a node table, a child
      with a composite key and a foreign key to it, an index and a table-valued function.
      `JooqCatalogLayoutTest` asks it and the fixture catalog the same seven questions. Its other
      readers want a generated model the fixture catalog shares no table with, not a layout: the
      MCP cases whose subject is a census that changed, or two graphs whose censuses differ.
    * **A table's source package is the catalog's** `done`. Capture took it from the table class's
      package with `.tables` cut off; `JooqCatalog.schemaPackageOf` reads it off the schema class,
      which sits in it under either layout, and the values are today's. `FetchersHelperNames`
      prefixes a colliding class with its own package by design, for beans as for records, and
      `TestFixtures` makes names up for tests no catalog reaches; neither reads a layout, and the
      constant moves with the paths.
  * **One codegen over every schema is the fixture catalog** `done`. Under the root package
    `public` had, so the documents, the pipeline tests and the example are captured and generated
    against every schema; its readers' paths moved to `public_`, code compiled against generated
    classes as a consumer's is, not layout logic. A bare `@table` naming `event` or `note` is
    ambiguous everywhere, as it is to a consumer with those schemas. The wider catalog found a test
    joining a cross-schema foreign key's referenced constraint on the referencing side's source,
    where the store states `referenced_source_name` for it; a tenant column borrowed from a
    composite key that other schemas spell with another type, now `tenant_id`; and a census past
    the MCP table page, which the listing tests now ask for whole.
  * The three other fixtures' readers then move onto it one fixture at a time, each execution going
    with its last reader. `NodeIdFixtureGenerator` keeps keying on bare table
    names, qualifying only where two schemas share one. `nodeidfixture`'s `collide_a` and
    `collide_b` share a type id on purpose and keep it, unwarned: two classes sharing a type id
    matter only to a schema binding both.
    * **The multischema fixture is part of the fixture catalog** `done`. Its readers name
      `<root>.multischema_a` and `<root>.multischema_b` of the fixture catalog, which jOOQ lays out
      as the fixture codegen did, and the codegen is gone. The fixtures' second multi-schema
      catalog constants and constructors fold into the default; the MCP cases that wanted a
      catalog other than the default had moved to the single-schema fixture first.
    * **The node-id fixtures are part of the fixture catalog** `done`. `nodeidfixture` and
      `idreffixture` are read as `<root>.nodeidfixture` and `<root>.idreffixture`, and their
      codegens are gone. Nothing failed: their tables are bare-unique across the catalog, and the
      metadata `NodeIdFixtureGenerator` keys on a bare table name reaches them as before.
* **The table-binding and node subject converts** `open`, and frees "The node is settled from the
  position-keyed entries". Its conversions waited on the fixture catalog above, the nodes subject's
  cases wanting metadata keyed off the primary key and one table name in two schemas, which every
  fact document now reaches. Forty-five classes seed `@table` and `@node` through `seedTableBinding`,
  `seedNode` and their siblings, which write the coordinate copies. Read for a production reader
  first, on this branch's terms: fifteen of them had none, and four pinned a dead relation beside a
  live one.
  * **What nothing read goes** `done`. A cluster of seventeen relations with no production reader,
    every chain ending at one: the mutation write family (`graphitron_mutation_write_payload`,
    `_write_destination`, `_payload_column`, `_payload_refusal`, `_payload_key_membership`,
    `intent_mutation_matched_key`, `intent_mutation_write_refusal`,
    `intent_mutation_write_agreement`), the input-field column family
    (`graphitron_input_field_column_match`, `_filter_role`, `_carrier_role`,
    `intent_input_field_column_scope`), the argument column pair (`graphitron_argument_column_scope`,
    `_column_match`) and three node-id views (`intent_node_id_decode`, `intent_node_id_encode`,
    `intent_node_id_candidate_node_type`). With them their ten rule views, the two views only they
    read (`intent_input_occurrence_descent_order`, `intent_table_key_candidate`), ten stages and
    their `DerivationStratum` steps, four grains, nineteen test classes, the dead halves of four
    more and two document blocks. Built again from the model, as the producers and the matched key,
    when a reader asks.
  * **The rest convert by subject** `open`. Twenty-six classes with a production reader: nodes
    four, fields six, references four, claims three, arguments and input fields four, and five
    node-id classes that wait with the node-id subject.
    * **Nodes** `done`. `NodesTest`, `NodeKeyColumnTest`, `NodeTypeTest` and
      `ResolvedNodeKeyColumnTest`, 44 cases, are `facts/node-population.graphqls` and
      `facts/node-key-columns.graphqls` over the fixture catalog, plus `NodeGraphScopeTest` for the
      two claims a single graph cannot state: a sibling's pinned list does not answer here, and a
      table in a catalog the graph did not read makes no node. The fixture catalog gains the shapes
      the seeds invented: `nodeidfixture.published_elsewhere`, whose three tiers name three
      columns, and `nodeidfixture.case_twins`, with columns `"Code"` and `"code"` that its codegen
      tells apart by a matcher strategy, so a pinned spelling folding onto both is real. The
      membership view's assertions were not carried over, the view going next. A `@node` over
      `@table` without `implements Node` is a node answering to its own name, beside its
      `NODE_WITHOUT_NODE_INTERFACE`, which the population document now states.
    * **Fields** `done`. `FieldScopeTableTest`, `FieldNavigatedTypeTest`, `FieldColumnTableTest`
      and `FieldRoutineMethodTest`, 62 cases, are `facts/field-scope-table.graphqls`,
      `facts/field-navigated-type.graphqls`, `facts/field-column-table.graphqls` and
      `facts/field-routine-method.graphqls`, plus `FieldGraphScopeTest` for the graph partition.
      "Every field has exactly one navigation" is a gate over every document's capture beside
      "every output field has a source". The fixture catalog gains `ms_ping()` in both
      multischema schemas, a routine name two schemas declare and one taking no parameters. One
      case did not convert: a table-valued function whose generated model exposes no call
      surface, which jOOQ does not produce for any fixture the catalog can hold; capture's null
      reading for it stands untested.
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
    `condition-method-route-departures` write each case as a condition-only path element naming a
    method of `ConditionMethodRouteFixtures` in the sakila service, a real signature per case. A
    wildcard source reaches the whole catalog, so its document states a sample and the targets
    it must not have rather than the list; the sibling-graph case is the harness's own.
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
  `SeparateFetchRuleTest` are fact documents; the second keeps one Java case, a contested binding
  that needs the multischema catalog, which no document can vary. Of what no capture reaches, one
  type at two root slots went, the specification forbidding it; an input field of an output type
  became an input field of a `@table` input, the legal shape of the same guard; and the sibling-graph
  case is the harness's own, every document being its own graph checked alone.
* **A document the toolchain refuses fails the runner** `done`. Capture never refuses a schema,
  recording the problem in `graphql_schema_problem`, and neither runner read it, so a document that
  redeclared a prelude type lost that declaration and everything in it to the older one in silence.
  Both runners now fail on a recorded problem unless the document states the relation. It found
  three documents losing what they wrote, all now fixed: `enum-value` and `federation-link` declared
  `type Query` beside the prelude's at every instant, and `spelled-reference`'s `@mutation` used an
  argument the directive no longer has on a `Mutation` nothing declared, so the spelling it claims
  to show was never captured. Four state the problem they are about: `base-less-extension` and the
  three `federation-key` documents.
* **A definition a document repeats within itself is recorded** `open`, a regression found
  2026-10-15. A plain document is captured as one source with the prelude before it, so a
  `type Query` beside the prelude's is a definition that one source repeats, and that refusal is
  recorded nowhere: `SchemaLoader.parsePerSource` keeps it on the source's own reading, source
  capture writes only syntax failures, and assembly merges the readings again, the repeat already
  gone, so `graphql_schema_problem` holds nothing and the runner's refusal check passes a document
  whose own definition was never captured. The generator's own load throws on it, so a build fails;
  the editor shows nothing. Each source's own refusals are written as `REGISTRY` problems, and a
  fact document repeating a type within itself states the row it gets. The prelude's assertion that
  every document's graph derives the prelude's own field guards the case of a graph deriving
  nothing at all, which this is not.
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

* **The walk's comparisons go** `done`. Nine cases took `GraphitronSchemaBuilder.build` or
  `AttributedRegistry` as the expected value: the type census, federation keys, connection
  provenance, source kind against the arrival fold, directive-driven carriers, scalar payloads,
  ordered child rows, code references and enum literals. Deleted with no accounting, on R740's
  terms: a difference from the walk is a walk bug or an unmoved consumer, never a debt. Each
  store-side claim was already pinned by a fact document or the store's own tests but two: the
  `@service` and `@mutation` payload columns the editor's claim surface reads, now
  `field-site-payloads.graphqls`. The connection provenance case was the named gate on the Edge
  name, which the promoter and the mint state twice; a one-sided change was run and fails
  `EmittedRegistryAgreementTest` and the execution tier without it.
* **The catalog cases become fact documents** `done`. Thirteen cases pinned `sql_` against jOOQ's
  own API, each computing its expectation with the calls the gatherer makes, so a fault the two
  shared passed. Five documents over the fixture catalog state the rows instead, captured by the
  real run, each naming the shape a table is there for: `catalog-tables` (kinds, class names, the
  per-schema `Keys` and `Tables` classes), `catalog-columns` (ordinals, SQL and binding types),
  `catalog-constraints` (forms, `Keys` constants, a cross-schema key, positional pairing on a
  reordered composite key, table pairs), `catalog-routines` (call surface, reflected parameter
  names) and `catalog-node-metadata`. A broken row in each was run and fails. The fixture catalog
  has no constraint without a `Keys` constant, so the null case the old one named was never
  exercised and is not stated.
  * **A primary key names a primary-key constraint, and the database refuses otherwise** `done`.
    The old case checked it with a query over every row. `sql_primary_key` now carries its form,
    fixed and defaulted, and its foreign key into `sql_constraint` includes it, so a row naming a
    unique key or a foreign key cannot be written.
* **The source and membership cases move to their gatherers' tests** `done`. The partition by
  schema package is the fact document `catalog-partition.graphqls`, over the fixture catalog's
  package per schema and `event` in two of them. The stamp went to `GraphitronModelStoreTest`, the partition by classpath
  entry to `CodeCaptureTest` over the model's own classpath corpus, schema-file sources to
  `GraphQLSourceCaptureTest`, the per-coordinate directive application counts against the SDL to
  `GraphQLAnchorTest`, and per-graph membership to a test of its own, `ModelCaptureMembershipTest`,
  being a claim about the whole run's read-set.
* **The configuration cases move beside the configuration gatherer** `done`, as fact documents
  rather than as cases: see "The configuration family is pinned by fact documents".
* **The emitted family's union moves beside `EmittedRegistry`** `done`, as fact documents rather
  than beside it. `emitted-anchors` states the type, field, argument and element anchors holding
  both the authored rows and the ones one `@asConnection` mints, the carrier once with its
  rewritten expression; `federation-sound` states a federated schema that imports `@key` without
  declaring it recording no problem and handing the derivations a domain. An assertion now takes a
  `where`, a SQL condition over the relation's own columns applied as written before the
  comparison, so an equality can be stated over part of a relation that holds every element of the
  graph. A broken row in each was run and fails.
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

* **A failed round's report rows say they are stale** `done`, a regression reported 2026-10-15.
  A dev round's report, the walk's rejections, lint findings and advisories, is written through
  `ReportFacts`, which records the round in `store_graph_progress` as the `report` gatherer. A round
  that fails before it can report withholds it: the rows are swept rather than left on lines the
  edit has moved, and its progress row is started and not completed, which persists across a restart.
  `status` reads that row and says `report` is `Withheld`, with why, where it said `Current`.
* **The dev executor runs on a federated schema** `done`. `execute` was off for every federated
  subgraph: `EmitPlan` emitted `DEV_EXECUTOR` only without a federation link, and
  `GraphitronDevExecutorGenerator.generate` returned early, both for want of an entity fetcher the
  schema class has since wired (`resolveEntityType` and `fetchEntities` inside
  `Federation.transform`). Both gates are gone; the facade's one-arg `buildSchema` already builds
  the federation-wrapped schema, so the executor needed no other change. Every schema now gets an
  executor, so the tool's refusal for a missing one names its one cause, a regeneration or compile
  that has not succeeded, and points at the diagnostics tool. `DevExecuteExecutionTest` resolves a
  `Customer` through the federated fixture's executor's `_entities`.
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
  * **A connection's teardown lets its drains finish** `done`. `DevServer.stopDrains` shuts the
    drain executor down without interrupting, so a drain inside the store runs to its end and a
    queued one runs rather than being dropped, its publish landing on a client that is gone.
    `DevServerTest` holds a drain in flight across the teardown and fails if it is interrupted. The
    comment that said an interrupted store read runs to its budget is corrected.
  * **The store survives an interrupt anywhere else** `done`. The store opens through H2's
    `retry:` file system, which keeps the plain `FileChannel` and reopens it when an interrupt closed
    it, so an interrupt reaching a thread inside the store's I/O costs that thread nothing and the
    rest of the store nothing. `GraphitronModelStoreTest` reopens a captured store cold and reads
    `graphql_directive`, the relation the live session failed on, with the thread's interrupt flag
    set. Under `file:` that read fails with `ClosedByInterruptException` and the next one with
    `ClosedChannelException`; under `retry:` both answer. `DebounceExecutor.close()` lets a
    regeneration in flight finish rather than interrupting it, and `DebounceExecutorTest` fails if it
    is interrupted.

    Read 2026-10-15 against the dev goal's threads, nothing interrupts a thread inside the store
    while a session runs, so the guard is for shutdown and for code the session hosts:
    * LSP requests run on `CompletableFuture.supplyAsync`, and lsp4j's `$/cancelRequest` cancels
      the future, which never interrupts.
    * A read's budget is `SET QUERY_TIMEOUT`, H2's own cancel rather than an interrupt.
    * An MCP tool runs on a Reactor `boundedElastic` worker while a Jetty thread blocks on its
      response, and Reactor's `WorkerTask.dispose()` interrupts that worker. Nothing disposes it
      during a call: SDK 2.0's one timeout is on requests the server sends the client, and it does
      not handle `notifications/cancelled`. It is disposed only when Jetty stops.
    * `DevServer.close()` calls `shutdownNow()` on its connection threads at shutdown.

    Timed 2026-10-16 on sakila, a fresh file store per run, `file:`, `retry:` and `async:`
    interleaved over eight rounds. The machine was at load 17 to 32, so thread CPU time is the
    measure: a capture took 3.7 to 4.5 s of it on the quiet rounds, and `retry:` was at or below
    `file:` in every quiet round, medians 4,049 ms against 4,483 ms, inside the noise. Cold reads
    were 3 ms under both. `async:` survives the interrupt too and timed the same, but puts every read
    through `AsynchronousFileChannel` to guard against something `retry:` already covers.
  * **A store whose file is gone fails loudly** `open`, rather than answering from page cache until
    a read misses it. `retry:` reopens a file that is still there, not one deleted or moved. The
    read path treats `ClosedChannelException` as a dead store, closing it and reopening cold, keyed
    on H2's error code the way `StoreReader` already recognises the query timeout.

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
