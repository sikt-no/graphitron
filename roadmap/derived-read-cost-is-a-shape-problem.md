---
id: R876
title: "Expensive derived reads are a modelling defect: every rule needs an owner, and once ownership is computed the derivation gatherer is unearned and meta_materialize has no subject"
status: In Progress
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-28
last-updated: 2026-09-29
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
  * **The reading's KEY arm is an equi-join** `open`. A three-way `OR` against `sql_constraint` is
    most of the reading's cost; a `UNION ALL` of two equi-joins matched it by `EXCEPT` on a schema
    that never exercised the jOOQ-name branch.

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

* **The graphitron anchor runs after what it resolves against** `open`, and first.
  `GraphitronAstCapture` runs its anchor before `JooqFactCapture` and `CodeCapture`, so every coordinate table it writes
  can only copy strings and every resolution went to a second pass running last, which is
  `DerivationStratum`. Moving it after `CodeCapture` and declaring `jooq` and `code` is the first
  move; nothing between reads a `graphitron_` row. The producer resolution waits on it, and so does
  every step `DerivationStratum` gives back to the graphitron anchor.
* **Four of the eleven go** `open`. `intent_condition_context_parameter`,
  `intent_external_field_contract_defect`, `intent_producer_cardinality_conflict` and
  `intent_scalar_java_type` are read by their own tests and by nothing else, and a view nothing uses
  has not shown it is worth saving, so each goes with its tests. The two reading the producer
  resolution go in that branch's subtraction.
* **The condition route resolves in `graphitron_`** `done`. `graphitron_condition_method_route`,
  renamed and declared to the gatherer that already read it; declaring it is what cost anything,
  the comment being reduced to its grain and example with the argument moved into the declaration.
* **The producer resolution resolves in `graphitron_`** `open`. `intent_field_producer_method` is the
  resolution the other arms hang on, and four of the eleven read it. `graphitron_service_entry` and
  `graphitron_external_field_entry` are wrong as they stand: keyed by coordinate, holding the
  authored strings the `graphitron_ast_` twins already hold, and joined to nothing.
  * The move above comes first; every child below waits on it.
  * **The resolved entries** `open`. `graphitron_service_resolved_entry` and
    `graphitron_external_field_resolved_entry`, at the twin's grain with the coordinate carried,
    keyed to `code_method`; no match is no row, and the anti-join against the twin is a
    `graphitron_entry_defect` arm in the same commit, so the store states what does not resolve.
  * **The walk's unknown-method rejections go** `blocked`, on entry defects failing validate.
    `Rejection.unknownServiceMethod` and `unknownLifterMethod` are the gate until then, and the
    editor shows a second diagnostic for the one fault until they leave.
  * **The coordinate anchors** `open`, waits on the entries. `graphitron_service` and
    `graphitron_external_field` are the winning entry row per field, keyed to it.
  * **Each consumer is answered from the new model** `open`, waits on the anchors. The views were
    designed before the discipline, so none is flipped: a consumer's query is rewritten against the
    new relations and the view goes with its tests. The oracle is the consumer's own test, stated
    as SDL, which passes before and after; a consumer with none gets one first, and a departure is
    an edit to that test. One child per consumer, grown as each is tried.
  * **The old shape is gone** `blocked`, on the move. The two producer views, the unused views and
    their tests, and the coordinate-keyed entries with their writers in `GraphitronAnchor`.
  * **The resolved entries take the entry names** `blocked`, on the subtraction. A rename.
* **The type questions follow it** `blocked`, on the producer resolution, because a `graphitron_`
  relation reading `intent_` is the same crossing pointed the other way.
* **Entry defects fail validate** `open`. `graphitron_entry_defect` is read by the `diagnostic`
  view alone, so every code in it reaches the editor and none stops a build. Not the producer
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

* **The graphitron anchor runs after what it resolves against** `open`, under the reference branch
  above; every step here waits on it.
* **Each step moves into its owner's anchor phase, marked and swept** `open`, waits on the move. A
  step is done when it marks what it wrote and sweeps what it did not, in the anchor phase of the
  gatherer that owns its table. One child per step, grown as each is tried.
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

### A capture reads only what changed

* A directory can be compared at all `done`
* The skip asks the store rather than the graph `done`, because no relation the classpath feeds
  carries a graph, so the rows are the store's.
* The file is the grain `done`. `store_class_file` is keyed by path under an entry, and `code_class`
  holds which class a reading found in which file.
* **A single changed class re-reads only itself** `blocked`, on `code_type` being re-grained to
  `(source_name, class_name, type_name)`. `CodeCapture.sweep` scopes by entry, so skipping one class
  would sweep its rows. Re-checked after the census dissolution went past the relation without
  changing its grain. Until then `store_class_file.byte_size` and `.mtime` are written and read by
  nobody, which is an arm waiting for its reader.

### The store knows whether an artifact can go stale

Reading a jar is safe for a release and dangerous for a snapshot, and Maven settles which.

* An entry says what it is `done`. Both halves of the classpath corpus were handed to capture as this
  module's own output, which was false twice, and the lie was load-bearing: it removed the reactor
  limit the `@service` arm leans on.
* The store records which repository answered `done`, by consequence.
* **The version is carried** `open`, and the whole of what remains. `store_source.coordinate` is
  `groupId:artifactId` by design, for naming a module in a refusal, and stops one field short of the
  identity question.
* **Three kinds get three treatments** `open`, waits on the version. A release jar needs no content
  stamp, a snapshot jar does, and only a directory needs the stat walk; all three share one path
  today.
* **A stale snapshot is detectable** `open`, waits on both above.

## Tests

The obligation this section carried is discharged and not by being measured. It asked the Done gate
to confirm that `MaterializeDependencies.populate` reaches zero rather than assuming it. That class no
longer exists, so the step it timed cannot run at all.

What the gate should still refuse is the inference the obligation was guarding against: a cheaper boot
does not follow from a dissolved register. The DDL half of the boot goes up under this item's own
remedy, which replaces registrations with stored keys and indexes. The figures for both halves are in
`roadmap/audits/2026-09-22-capture-dissolution-measurements.md`.

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
`graphitron_argument_path_segment`, and `code_condition_method_parameter` (now
`code_method_parameter`).

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
`field_name`; `intent_resolved_node_key_projection.trailing_segment_name` (now `trailing_name`).
Values `AUTHORED_EXPRESSION` and `TRAILING_SEGMENTS_BEYOND_ONE`.
`graphitron_field_chain_link_resolution.reach` and its values `TAIL` and `HEAD` as a stored column
(now the payload flags `reached_by_tail` and `reached_by_head`), and `reach` in the
`field-chain-link-reach` grain's key shape. Added rather than retired, and
listed because the grain is the point: `store_graph_source.stamp` and `store_graph_source.read_at`.

**Java.** `MacroCapture.expandConnections` (now `expand`); the `Expansions` record, the five
`captureXDirective` callbacks, `captureNavigation`, `connectionElementByType`;
`EntryFamilyFixture.ENTRY_RELATIONS` and `ANCHOR_RELATIONS` (now `entryRelations()`);
`EntryFamilyCoverageTest`'s partition case; `GraphitronFactCapture`'s `schemaDirectives`,
`typeDirectives`, `fieldDirectives`, `argumentDirectives`, `enumValueDirectives`, `argumentsBy`,
`directive`, `parsed`, `location` and the four-argument `undecoded`; `EntryWriterAgreementTest` and
`CapturedStore.entriesDecodedByTheWalk`; `RowChunks` with `ROWS_PER_STATEMENT`, `of` and `execute`,
and `MultiRowWritesAreChunkedTest` (now `WritesBindPerRowTest`); `SdlCapture` with `captureFacts`,
`captureEntries` and `captureGraphitronAnchors`; `SdlAnchor` and `SdlAnchorTest` (now
`GraphQLAnchorTest`); `SourceDocument.parsed()` and its `changed` component; `reclaimVanished` (now
`reclaim`). Renamed: `SdlEntries` to `GraphQLAstEntries`, `SdlSchemaProblems` to
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
* **`graphql_element` and `graphql_element_field` are declared to the retired `sdl` gatherer.** That
  roster row points at `SdlFactCapture`, which creates no record and writes no relation, so the
  declaration names a writer that does not write. Repointing it is a one-line change that wants
  checking rather than guessing.
* **`SchemaIdentifierDriftCheck` can report findings the source no longer holds.** It reads the store
  prose out of a booted H2 store, so the DDL it sees is the compiled resource rather than the file it
  names, and it labels every finding with the `src/main/resources` path. On an incremental build that
  pair means it reports against a stale copy while pointing at a source file that is already fixed.

* **Seeding is the fallout of a module boundary this item already moved.** `SeededStore` exists
  because the gatherers lived in `graphitron`, where a test had no way to run a capture: writing
  rows was the only way to put facts in front of a rule. The gatherers are in `graphitron-model`
  now and the corpus and its `@expectEquals` runner moved with them, so a test can state SDL and
  read the relations back. What is left is the fallout: a seeding helper of a few thousand lines and
  most of the test classes in the module reaching for it, because seeding was once the only option.

  The cost is measurable now that the alternative exists. Converting the macro expansion to a
  derivation broke six seeded fixtures, and each was asserting over a state capture cannot reach:
  five seeded a carrier whose *authored* field was already the connection type, which no
  transcription holds because the rewrite is derived, and one expected a carrier's row without the
  `nodes` and `node` its expansion mints. A seventh counted `graphql_field` to assert a per-field
  invariant a relation states over the emitted population. None of them could fail before, because
  seeding writes both halves of a claim and nothing checks the halves against each other.

* **The classpath half has its corpus already; it is the seeding that has no excuse.** `SeededStore`
  seeds `code_` too, and that arrived on 2026-09-20 with the commits introducing the family, so it is
  not even old fallout. What makes it worse than the SDL half is not that a corpus is missing: the
  three strata all have one, and two of them are modules the tests already depend on. The SDL corpus
  is the fact documents; the catalog corpus is `graphitron-sakila-db`, a real jOOQ-generated catalog;
  the classpath corpus is `graphitron-sakila-service`, ninety-six real Java classes. Extending any of
  them is adding a class, a table or a converter to a module that already compiles, not designing
  anything. A seeded `code_method` row is a claim about a method; a method in `sakila-service` is
  one. The failure mode is the worst available, generated code calling a method that does not exist,
  failing in a consumer's build rather than ours, and it is self-confirming: a fixture seeds a method
  name and asserts the emitted text contains it, so the test cannot fail for the reason it exists.
  `graphitron-model` depends on `graphitron-sakila-db` only, so the conversion owes it one test
  dependency on a module that is already in the reactor.

* **The fixtures are static, which is what makes a warm store possible.** No `sql_`, `jvm_` or
  `code_` relation carries `graph_name`: the catalog and the classpath are store-wide by
  construction, while every `graphql_` and `graphitron_` relation is partitioned by graph. So one
  store can hold many graphs against one shared reading of the catalog and the classpath, and the
  reading that costs the most happens once rather than per document. The second half is the part
  that is not an optimisation: varying which SDL loads into a warm store is the only thing that
  exercises refresh at all. A fresh store per test means every refresh runs against an empty store,
  which is the one case where sweeping, re-anchoring and incremental invalidation are all trivially
  correct, so the cheap path and the untested path are currently the same path.

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
* Narrative and retired approaches to the September 2026 chapter of
  `docs/history/road-to-the-relational-core.adoc`.
* The discipline itself to `docs/architecture/explanation/modeling-discipline.adoc`, with the procedure in
  `.claude/skills/adding-a-relation` and a short form in `CLAUDE.md`.
* Everything else is in git, which the deletion-at-Done convention already names as the archive.

## Reviewer findings

Rounds 1 to 3 (2026-08-28, Spec to Ready) and round 4 (2026-09-09, In Progress) are addressed and are
in this file's git history. Nothing is open.
