---
id: R876
title: "Expensive derived reads are a modelling defect: every rule needs an owner, and once ownership is computed the derivation gatherer is unearned and meta_materialize has no subject"
status: In Progress
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-28
last-updated: 2026-09-25
---

# Expensive derived reads are a modelling defect: every rule needs an owner, and once ownership is computed the derivation gatherer is unearned and meta_materialize has no subject

## Goal

**Every rule gets an owner, and `meta_materialize` dissolves.** A registration is not a thing to be
justified or retired one at a time; it is what a rule with no owner gets given, so that something
somewhere refreshes it. Give every rule an owner and there is nothing left for a register to
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

**Status against the thesis: confirmed, and the scope reservation was wrong.** This item said
emptying the register outright was not reachable. R955 reached it. `meta_materialize` is gone, no
registration is left and no `_live` view is left, so the thesis holds in full rather than in shape
only. The claim about which lever to reach for first also has a direct measurement behind it: the
workload's worst reader was fixed by restating its rule, register untouched, every relation
returning identical rows.

## The plan

Ten slices. Seven have landed; what they moved is in `roadmap/changelog.md` and in the September 2026
chapter of `docs/history/road-to-the-relational-core.adoc`.

1. The entry migration. Landed 2026-09-07.
2. The two hierarchies, one mechanism at two grains. Landed from 2026-09-08.
3. The macro arc's consumer, the first this arc retired.
4. The route family.
5. The reference decode on the field sites.
6. The argument and input-field sides.

**7. The declaration pass.** A relation with no `meta_relation` row has not been made anybody's, and
writing one forces a grain to be named, after which the existing gate checks the primary key against
it. Ordered after the dissolutions above: `intent_` is the largest share of the undeclared roster and
most of those are views the slices above delete, so declaring them first would be writing rationales
for relations about to go. The mechanism is R877's. Two corrections from running it. Declaring is a
precondition on every move rather than a pass that waits its turn, because a relation whose owner is
not declared is cleared by whatever clear still stands and, once its old writer is gone, is not
written back. And the declaration is a build-time gate, not a runtime mechanism: nothing that runs
reads `meta_relation`, and what the gate buys is that a new relation cannot arrive without an owner.

**8. The register. Done, by R955.** Not retired row by row and not shrunk to a defensible core: the
gatherer it compensated for stopped existing and the mechanism was left with no work, which is the
shape this item argued for. `meta_materialize`, every registration and every `_live` view are gone.
The credit is R955's; what this item contributed is the argument that the register had no subject
once ownership was computed.

**9. The materialization targets. Moot.** The subject was the registered targets, which were the
relations carrying no primary key. Six stored `intent_` tables remain and every one of them is keyed,
so there is nothing left to key and no vacuous grain check to fix. Slice 8 took the rest with it,
which is what this slice being deliberately last was betting on.

**10. The directive applications collapse onto the coordinate. Landed.** Ten relations became two,
and the two key into `graphql_element`, which is a table.

The five `graphql_*_directive` relations stated one fact, a directive applied at a site, and keyed it
five different ways because each decomposed its own site: `(graph_name, directive_name, ordinal)` at
the schema, seven columns at a field argument. The five `graphql_*_directive_arg` relations repeated
the split one level down. They did not key differently because the fact differed. They keyed
differently because each carried its site's decomposed key rather than the site's coordinate.
`graphql_directive_application` carries the coordinate and keys into `graphql_element`;
`graphql_directive_application_arg` takes the same key plus `directive_argument_name`.

Two rulings it rested on, both the architect's. A supertype is a table carrying a primary key its
subtypes reference, because a foreign key cannot name a view. And the schema block gets the
coordinate `$schema`: the specification has no such coordinate, but we own ours, and `$` is illegal
in a GraphQL name so it can never collide with a type an author writes. That is what let the schema
arm stop being the exception.

Four things the work found that the design did not have.

The coordinate needed no per-site join at all. `graphql_ast_directive_application_entry` was already
the supertype over all five application entry kinds and `graphql_ast_element_entry` already mapped a
written position to its coordinate, so the five arms were one join. What the five arms did not share
was the *ordering*: a repeat is numbered in the merge order of the type declaration it sits inside,
one to three parent hops up depending on the site, and the schema block sits inside none. That is
`graphql_ast_element_declaration`, a third relation and a recursive walk up the parent chain the
entry supertype already carries. It is stated once rather than climbed at each consumer, and the
schema block is a stop alongside the type declaration so the fallback to file age is the same ORDER
BY rather than an arm of its own.

`graphql_type_directive` was not only the same fact keyed differently. It carried
`declaration_line`, `declaration_column` and a second foreign key into `graphql_type_declaration`.
Nothing read those columns; only the seeding harness wrote them. They went, and the cascade they
provided is covered by mark and sweep, a directive on a removed `extend type` not being rewritten
and so being swept.

The collapse gained a site. A formal argument of a directive definition is a coordinate the
specification spells and `graphql_element` already anchored, but no relation was shaped to hold an
application on one, so those reached nothing: graphitron's own `@deprecated` on
`@asConnection(connectionName:)` was captured nowhere. The old javadoc said minting a spelling for
it would put a coordinate in the store that the specification does not have, which was simply wrong.
Both producers now write it.

`graphitron_field_chain_application` was the one relation keying into the ten by foreign key. It
carries the coordinate now beside the type and field that are its own grain, and the cascade on that
reference is still its sweep.

What it touched, as it turned out: four views, the family headline, `FieldChainApplications`,
`SdlFactCapture`'s claims, and eight tests. Ten rows left the undeclared roster and three
declarations arrived, which is the discipline's own trade.

**The rule that keeps the default from coming back is that an owner is computed, not chosen.** A
relation's owner is the latest, in gatherer dependency order, of the owners of the relations it
reads. That is a function of the schema, so a gate can check it, and it makes the default impossible
to take: a rule reading only `jvm_` facts cannot be owned by a gatherer that runs after `catalog`.
The same rule is what says the derivation gatherer is unearned, so the gate and the collapse are one
check rather than two changes.

**Two prerequisites, neither this item's.** R877's declarations, because computed ownership cannot be
checked over undeclared relations. And per-gatherer transaction control, which the fact model names
and the store does not have: `FactCapture` runs every gatherer in one transaction, so no gatherer can
commit its family and then refresh against statistics reflecting what it just wrote. The collapse
makes this easier rather than harder, one boundary instead of two.

No count is stated in this section. Every figure the item carried about the register and the roster
went stale during the arc, one of them twice inside a working day.

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
Values `AUTHORED_EXPRESSION` and `TRAILING_SEGMENTS_BEYOND_ONE`. Added rather than retired, and
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
* **`graphitron_entry_defect` states what `intent_mutation_routine_seat` still answers.** Three of
  the seat's refusals are arms of the new view and the rest are not, so the seat stands and
  `RoutineWriteFacts` still reads it. Two producers of one fact with nothing comparing them, which
  is the shape this item exists to remove; it is a migration in flight rather than a resting
  place, and the remaining arms are what finish it.
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
