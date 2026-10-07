---
id: R876
title: "Expensive derived reads are a modelling defect: every rule needs an owner, and once ownership is computed the derivation gatherer is unearned and meta_materialize has no subject"
status: In Progress
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-28
last-updated: 2026-10-06
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

### Every rule has an owner, and the register has no subject `done`

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
  buys is that a new relation cannot arrive without an owner.

### A fact is keyed by what it is, not by where it was written `done`

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

### The classpath family affords what its readers ask `done`

* `code_` reads the classpath it is allowed to read `done`
* `code_` holds every fact a reader of the census read `done`
* The census is gone `done`. Six relations, their gatherer, their roster rows and their fixtures, and
  with them the gatherer ordering constraint that existed only to keep a `code_` arm from reading a
  `jvm_` row.

### A resolution keeps what a diagnostic needs `done`

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
    `graphitron_field_chain_link_reading` four times, each walk's seed and step, and the entry
    defect rule a fifth, and H2 evaluates a view at every naming. Storing the reading alone took a
    reader of the walk from 30 s to 0.08 s in the storage audit, so the stage's cost is the
    reading's evaluations and not the walk. Convert it the cheap way: the view keeps its text as
    `graphitron_field_chain_link_reading_rule`, a table takes the name both readers spell, and a
    clear-and-insert stage fills it ahead of `FieldChainLinkResolutions`. Two readers, so the write
    is earned; time the stage on the sis store before and after, the KEY arm change above first.
  * **The defects agree with the generator on a consumer schema** `open`. On sis the four chain
    codes report 161 coordinates the generator accepts. Each child is a reading that is wrong
    rather than a rule that is, and evidence is in
    `roadmap/audits/2026-10-01-sis-entry-defect-false-positives.md`.
    * **The reactor's own example is the check** `open`. The sakila example reproduces it in tree:
      made build errors, the defects report 44 coordinates it generates, and fail
      `MethodClosureOracleTest`, `IncrementalCompileHarnessTest` and five corpus fragments with it.
      This node is done when that example draws no error-severity entry defect, which a build can
      check where sis cannot. Evidence in
      `roadmap/audits/2026-10-02-sakila-entry-defect-false-positives.md`.
    * **A chain arrives where its field says** `open`. Eight sakila coordinates report
      `NO_ROUTE_TO_TARGET`, a class sis did not show; not yet examined.
    * **A departure is inherited through a type with no table of its own** `open`. The endpoints
      take the departure from the enclosing type's own binding only, so a nested type departs from
      nowhere and its chains report `NO_ROUTE_FROM_DEPARTURE`.
    * **A scalar `@reference` has a target** `open`. The path's last table is where a column read
      through a chain arrives, and with no target basis for it `CHAIN_WITHOUT_TARGET` fires. Shares
      its question with "a routine resolving to nothing says so" below.
    * **A named self-referencing key reads one way** `done`. Both hops of a self-reference depart
      and arrive at one table, so the reading keeps the one the field's cardinality names, which
      is the generator's rule: along the key on a single-valued field, against it on a list.
    * **A condition-only element resolves from its method** `done`. The method row was the
      missing one, `CodeCapture` admitting only a `Condition` return where the generator checks
      none; a `Field<Boolean>` return is admitted beside it, read off the signature's type
      argument. The manual still documents `Condition` alone, deliberately.

### The chain replaces the walk it was built to retire

The field grain resolves where it was written and says why when it cannot, which is the node above.
What remains is the other two grains, a second resolution of the same elements still standing beside
this one, and the read-time relations neither of them has displaced yet.

* **A routine resolving to nothing says so** `open`. `CHAIN_WITHOUT_TARGET` declines that case on
  purpose, a `@reference`-only chain taking its target from the return binding where a routine chain
  takes it from the catalog, so the same silence means two different faults and only one has an arm.
* **The seat keeps its predicate and loses its vocabulary** `open`, waits on the arm above. Every
  production reader filters to `ADMITTED`, three sites in `RoutineWriteFacts` and one arm of
  `intent_field_unlowerable_ordering`, so the thirteen refusals have no consumer and one moved to
  `graphitron_entry_defect` gains a reader rather than changing one; its `MutationRoutineSeatTest`
  case travels with it, being the only cover those thirteen have.
* **The chain is stated at the argument and input-field grains** `open`. `@reference` is written at
  three coordinates and only the field grain has a chain relation.
* **One resolution of an authored element stands, not two** `blocked`, on the two grains above.
  `graphitron_field_reference_step_hop` resolves the same elements as
  `graphitron_field_chain_link_reading` from a decode its own comment calls deprecated; measured on a
  fixture reaching every arm the two agree exactly, so what is left is choosing which survives. The
  stored resolution is keyed in the hop's own arm shapes and adds the routine arm and the target
  grain the hop lacks, which is a vote for retiring the hop.
* **The read-time chain relations are gone** `blocked`, on the node above. `intent_field_chain_start`,
  `_node` and `_terminus` retire as one and `graphitron_field_chain_application` falls out with them,
  its only reader being `_node`; `graphitron_condition_method_route` is not among
  them, having been rehomed rather than retired.
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
    as SDL, which passes before and after; a consumer with none gets one first, and a departure is
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
    departing one, and a routine write's payload re-reading the rows it returned. Stored at that
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

* **The graphitron anchor runs after what it resolves against** `done`, under the reference branch
  above.
* **Each step moves into its owner's anchor phase, marked and swept** `open`. A step is done when
  it marks what it wrote and sweeps what it did not, in the anchor phase of the gatherer that owns
  its table. One child per step, grown as each is tried.
* **The `intent_` writers go to a family or go** `open`. `ClassificationDomainCapture`,
  `InputOccurrencePaths`, `TypeBackingRows`, `AuthoredClaimRejectionRows` and
  `UnlowerableOrderingRejectionRows` write `intent_` tables, so each moves to the family whose facts
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
* **A slot's parse is read by what replaces the accessor hop** `open`. No query reads the new
  columns. Their reader was `intent_field_accessor_hop`, which peels `slot_type` through
  `code_type_element` to say which class a member of a class delivers, the edge the type-backing
  walk steps along, and which is dissolving. Its successor reads `element_class` off the slot with
  no peel; if the hop's dissolution needs no such edge, the columns are unearned and go.
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
point in. Today the call order, the roster and the corpora disagree. `ModelCapture` runs the documents
first, then the configuration, then the catalog, then the classpath. `code` declares `jooq` and not
`store`. And the classpath reaches its reading as a method argument, so the configuration is
transcribed beside the reading rather than read by it. The order the corpora stand in is the
configuration, then the classpath and the catalog, then the documents, then what resolves the
documents against the rest.

* **The configuration runs first** `done`. Every `store_graph_*` relation keys `store_graph` and
  nothing else, and no gatherer reads them before they are written, so it was a move in
  `ModelCapture` and changed no row.
* **The documents run after the classpath and the catalog** `done`. The three `graphql-` gatherers
  declare no upstream, and the first gatherer reading them after the corpora is `graphitron-ast`, so
  this move changed no row either. `ModelCapture` comments only what the code cannot say: the
  narration it carried did not keep the order right, and the gate below is what will.
* **The classpath is configuration** `open`. What a module depends on is something its build
  declares. A reactor dependency is a class directory the build writes. A repository dependency is
  a jar, a release that cannot change under its coordinate and version or a snapshot that can. The
  configuration gatherer transcribes one row per dependency per graph, stating which of the three it
  is as the producer resolved it. The point is the row and not reading it back: the Java already
  holds what it just wrote, but a graph's claim on an entry can only reference a dependency that is
  a row, and that reference is what makes a dependency the build stops declaring release its claim
  by cascade rather than by a hand-written delete. The producer already
  knows more than the store keeps: `ClasspathEntry.suppliedStamp` carries a repository's identity
  for a jar it resolved, which the session census trusts and the persisted reading ignores, hashing
  every jar on every capture. This is also what the version node in the staleness branch below is
  waiting for.
* **A claim references the dependency it resolves** `blocked`, on the node above.
  `store_graph_source` for a classpath entry keys the dependency row, `classpath-source` declares
  `store`, and the code family has the configuration as its one upstream.
* **Code does not depend on jOOQ** `open`. The two families are parallel: each reads its own
  corpus under the configuration, and neither keys the other. The reading skips the generated jOOQ
  package, and that is right, the generated classes being the catalog's to describe and no part of
  the service layer. Two reads cross today, one child each.
  * **The table a parameter is bound to is the graphitron anchor's** `open`.
    `code_condition_method_parameter_table` keys a parameter to the `sql_table` its class names, so
    the reading runs after the catalog, and it matches by class name in Java with a hand-kept
    ambiguity check. The binding crosses two families, so it belongs to the gatherer downstream of
    both: matched by name once, at `graphitron`'s anchor, and stored as a table keying both sides,
    which is the shape the relation already has under the wrong family. Readers join on its keys.
    `code_method_parameter.role` keeps `TABLE_CONCRETE`, which is a fact about the signature alone.
  * **The reading's loader comes from the classpath it read** `open`. `CodeCapture` and
    `ClassAncestry` are handed `jooq.codegenLoader()`, to load scalar constants and to ask whether a
    position is a `Table` or an enum. That is a loader and not a family, but reaching it through
    the catalog makes the catalog look upstream of code when it is not.
* **The jOOQ exclusion is pinned where services share its package** `open`. Services are commonly
  written in the module jOOQ generates into, so one directory holds both, and the exclusion is the
  package and every package under it. A service package beneath the generated one is dropped
  silently, every arm reading as a classpath that does not carry it. A case first, then either the
  exclusion stops at the package or the layout is refused out loud.
  * **The manual says what the jOOQ package keeps out of the reading** `open`, lands with the node
    above. The `jooqPackage` row of `mojo-configuration.adoc` says the catalog is rooted there and
    not that the classpath reading leaves it out, which is what an author whose services share the
    module needs to know.
* **A gate holds the call order to the roster** `open`. Nothing checks that `ModelCapture` calls a
  gatherer after every gatherer it declares, which is how the two drifted apart. `java-source` and
  `compile` run in the dev loop on their own cadence rather than in this sequence, and `sdl` names a
  class that writes nothing, so the gate's population is the gatherers `ModelCapture` calls.
* **The pipeline overview states the order and what holds it** `blocked`, on the gate.
  `pipeline-overview.adoc` § Capture transcribes says the gatherers run in the order their
  declared read edges require, which was false until the order moved and is held by nothing until
  the gate lands. The sentence names the order and the gate; the vocabulary around it is R983's.
* **The pipeline overview describes a dependency's row** `blocked`, on a claim referencing its
  dependency. The same section's `store_` bookkeeping sentence names the graph, its sources and
  their stamps, and gains the dependencies and what each kind lets a capture skip.

### The store knows whether an artifact can go stale

Reading a jar is safe for a release and dangerous for a snapshot, and Maven settles which.

* An entry says what it is `done`. Both halves of the classpath corpus were handed to capture as this
  module's own output, which was false twice, and the lie was load-bearing: it removed the reactor
  limit the `@service` arm leans on.
* The store records which repository answered `done`, by consequence.
* **The version is carried** `blocked`, on the classpath being configuration. `store_source.coordinate`
  is `groupId:artifactId` by design, for naming a module in a refusal, and stops one field short of
  the identity question. The version is a fact the build declares, so it arrives with the dependency
  rather than as another column on the entry.
* **Three kinds get three treatments** `open`, waits on the version. A release jar needs no content
  stamp, a snapshot jar does, and only a directory needs the stat walk; all three share one path
  today.
* **A stale snapshot is detectable** `open`, waits on both above.
* **A directory's stamp covers what the reading reads** `open`. The stamp walks every file under the
  directory, the generated jOOQ files included, so where services share a module with the catalog a
  migration regenerating jOOQ moves the stamp and every service class is read again though none
  changed. The stamp covers the class files the reading takes, or the file-grain skip in the capture
  branch makes the directory's stamp matter less; either removes the false invalidation.

### A node id's candidates are modelled from what was written

The current model is flawed. `graphitron_node_id_instruction` blends what the author wrote with what
was inferred from it, so its readers cannot tell a written `typeName`, which applies to the whole
coordinate, from a type inferred per member of an interface or a union, which applies to that
member alone. They cross every candidate with every table at the coordinate: capture fails on the
carrier role's key, and the decode destination, the slot's candidate count and the filter roles
answer wrongly without failing. Unions and interfaces were not taken into account when it was
built, and the seeded cases could not show it. It is modelled again from scratch as two relations,
reading nothing from `intent_`, and replaced by adding, flipping and subtracting. Evidence in
`roadmap/audits/2026-09-30-node-id-instruction-grain.md`.

The sites are symmetric: an output field needs every candidate to encode, an argument or an input
field every candidate to decode. A node is a type, so the tables do not decide a candidate, with one
exception: a path lands on a table, so a bare `@nodeId` with `@reference` takes the one node type
over the table the path lands on. A union is never a node, and `@node` on an interface is not
supported and stays out, a possible future addition that would give its `@nodeId` fields one
candidate, the interface's.

* **The model comes first, as tables** `open`. The entry and the candidates relation are declared
  with their keys, foreign keys and grain sentences before anything writes them, so a document can
  name them.
* **The corpus is written against the model** `open`, as fact documents asserting with
  `@expectEquals` the rows each schema should give the two new relations: an output field, an
  argument and an input field, at a node type, an interface and a union, by a written `typeName`,
  a bare `@nodeId`, the id-name forms and `@reference`. Written before the gatherer, because a
  document is an example of the model and writing them is where the cases nobody thought of turn
  up, while no code depends on the answer. Each is committed with the gatherer step that satisfies
  it, so every commit stays green.
* **Add: the entry states what was written** `open`. One row per `@nodeId` application at its site,
  with `typeName` where the author wrote one. The existing entry relations are checked against that
  sentence rather than assumed to meet it.
* **Add: the candidates relation states what may be legal** `open`, one row per use site and node
  type. A written `typeName` is its type. A bare `@nodeId` is the coordinate's node type, or each
  member of an interface or a union that is a node. With `@reference` it is the one node type over
  the table the path lands on. The id-name forms are the coordinate's node type.
* **Add: an input field's use site** `open`. A use site is a definition joined to its consumer, and
  the model holds it today only as an `intent_` view, so it is modelled here rather than read.
* **Flip: the readers depart per candidate** `blocked`, on the three adds. A written candidate
  departs from every participant of the coordinate and an inferred one from its member's table. The
  encode, the decode, the filter and carrier roles, the hops and the coverage census read the new
  relations.
* **Subtract** `blocked`, on the flip: `graphitron_node_id_instruction`, the endpoint that crosses,
  and whatever in the chain stops being read.

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
* The count only falls `done`. `SeedingDissolutionGateTest` holds it at 83, because a seeded case
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
* **The node-id subject converts** `blocked`, on the candidates branch above: every class in it
  reads the instruction, and its first conversion crashed capture. Eight classes of nine. Its gaps
  are one classpath shape
  and three catalog shapes, and its six sibling-graph cases need no replacement: the runner captures
  every document into one store as its own graph and compares each block against that graph alone,
  so a derivation that drops `graph_name` fails whichever document it leaks into.
  `PolymorphicNodeIdDecodeTest` waits on the branch above, because a document written now would pin
  the rule that branch replaces.
* **There is no residue, and no partial conversion** `open`. A class converts whole or it has not
  converted, which is what the caller count measures and why it is the right count. A case that
  fabricates a state no capture reaches is not a case the fixture has to be kept for: it is either
  a property test, which is the tool for a pattern nobody writes on purpose, or it is a test of a
  strange shape nobody cares about and it goes. `CarrierDataFieldPopulationTest` was named here as
  the clearest instance and was not one: every state it seeded is SDL, and it is the fact document
  `carrier-data-field.graphqls`, its four cases one expectation over the whole graph. A class is
  read for what it fabricates before it is called residue.
* **The code-reference subject's conversions so far** `open`. `CarrierDataFieldPopulationTest` and
  `SeparateFetchRuleTest` are fact documents; the second keeps one Java case, a contested binding
  that needs the multischema catalog, which no document can vary. Of what no capture reaches, one
  type at two root slots went, the specification forbidding it; an input field of an output type
  became an input field of a `@table` input, the legal shape of the same guard; and the sibling-graph
  case is the harness's own, every document being its own graph checked alone.
* **A fresh store per test stops being the only shape** `open`, and it is not tidiness: every
  `sql_` and `code_` relation is store-wide, so one store holds many graphs against one reading of
  the catalog and the classpath, and varying which SDL loads into a warm store is the only thing
  that exercises refresh at all. A fresh store means every refresh runs against an empty one, which
  is the case where sweeping, re-anchoring and invalidation are all trivially correct.
* **`SeededStore` is deleted** `blocked`, on the five above. Not shrunk and not kept for a
  remainder: the fixture goes. The subtractive commit is what makes this branch done, and until it
  lands the root is not done either.

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
`code_method_parameter`), and `code_type_slot` (now `code_read_slot`). The demand relations,
dissolved rather than replaced: `intent_field_demand_rule`, `intent_field_exemption_rule`,
`intent_type_demand`, `intent_type_exemption`, `intent_resolved_field_demand` and
`intent_resolved_type_demand`.

**Renamed, by rule rather than by list.** Every relation of the as-written half of `graphitron_`
gained the `_entry` suffix. A sweep for a survivor is a search for a `graphitron_` name that is
neither suffixed nor one of the sixteen in the resolved half, which is cheaper than a list and does
not go stale. Two index names followed: `graphitron_spelled_reference_name_ix`,
`graphitron_method_reference_method_ix`.

**Columns and values.** `graphitron_field_synthesis.authored_type_sdl`,
`code_external_field_method.table_parameter_type`, `code_condition_method.is_static`,
`graphitron_argmapping_match.segment_position`, `trailing_segments`, `written_path`,
`trailing_name`; `graphitron_argmapping_entry.head_segment`, `head_kind`, `candidate_coordinate`,
`candidate_path`, `type_name`, `field_name`, `argument_name`, and `argument_path` (now
`written_path`); `graphitron_argmapping_candidate.element_name` (now `name`), `type_name`,
`field_name`; `intent_resolved_node_key_projection.trailing_segment_name` (now `trailing_name`);
`code_construction.type_name` and `code_write_slot.type_name` (now `class_name`); the index
`code_type_slot_name_ix` (now `code_read_slot_name_ix`).
Values `AUTHORED_EXPRESSION` and `TRAILING_SEGMENTS_BEYOND_ONE`.
`graphitron_field_chain_link_resolution.reach` and its values `TAIL` and `HEAD` as a stored column
(now the payload flags `reached_by_tail` and `reached_by_head`), and `reach` in the
`field-chain-link-reach` grain's key shape. Added rather than retired, and
listed because the grain is the point: `store_graph_source.stamp` and `store_graph_source.read_at`.

**Java.** `DemandRuleTest`, `DemandShadowTest`, `DemandResidue`, `SchemaQueries.Demand` and the
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
`reclaim`). The specification's built-ins as constants beside the entries:
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
  grain passes. The view exclusion is not hypothetical: `graphitron_field_chain_link_reading`
  arrived declaring a grain that did not identify a row, naming the constraint by its own name
  alone where that name is unique per table, and omitting the endpoints entirely, which are all
  that tells apart the two arms carrying no constraint. It was found by reading and fixed by
  reading, which is the part that does not scale.
* **`graphitron_field_navigation` is a stored derivation with no rule view.** Its grain is
  `graphitron_field`'s, unchanged, and it is stored so a reader meets an indexed column. That is a
  real reason, but the rule is stated once in jOOQ with nothing to diff it against, where
  `FieldColumnScopes` ten lines away keeps its rule in a view so an `EXCEPT` against the target stays
  runnable. Worth an item.
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
