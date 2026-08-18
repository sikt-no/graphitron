---
id: R712
title: "Name the three strata, and retire authored versus effective"
status: Spec
bucket: architecture
priority: 3
theme: docs
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# Name the three strata, and retire authored versus effective

The store has three strata. Capture transcribes facts from a corpus. Derivation computes further
facts from captured ones. Queries read facts to serve a goal. `fact-model.adoc` already teaches
almost all of the discipline that follows from this, in detail and with the enforcement gates named,
but it never states the three strata as such, and it carries a vocabulary that contradicts them in
one place.

This item is prose and naming only. No relation changes, no code moves.

## The word is stratum, not tier

Naming three things is the whole deliverable, so the word has to be one the tree does not already
spend elsewhere. "Tier" is spent three times over:

- Test tiers. `no.sikt.graphitron.rewrite.test.tier.UnitTier` and its three siblings
  (`PipelineTier`, `CompilationTier`, `ExecutionTier`) annotate roughly four hundred test files,
  and `docs/architecture/how-to/testing.adoc` is the guide for them.
  `development-principles.adoc` uses the word in no other sense, heading a section "Behaviour is
  pinned at the pipeline tier and above" and pointing "Tier names, locations, and the decision
  rubric" at that guide. A contributor reading `docs/architecture/` today resolves "tier" to a test
  tier every time.
- Claim resolution. `intent_resolved_field_claim.tier` carries `AUTHORED` or `INFERRED`, and
  `SchemaQueries.FieldClaim`'s `tier` component republishes it on the MCP wire.
- Name-match precedence. `intent_column_match_claim`'s view comment states a "generated-Java-name
  tier before SQL-name tier" ordering, and its `matched_by` column comment says "which tier matched".

The second of those is the sharpest: its value set is `AUTHORED` and `INFERRED`, so a "tier one" for
capture would land a few paragraphs from a column whose tier literally is `AUTHORED`, in the same
edit that retires "authored" as a name for the store's contents.

"Stratum" is already the tree's word for this axis, roughly two dozen times in the DDL and six in
`fact-model.adoc`, which uses "tier" not once. The sentence this item reworks is one of the six: "the
stratum it lives in is decided by what produces its rows". Rewording that sentence to the recompute
test therefore lands inside the vocabulary already in use rather than beside a second one, which is
the argument for the word and not just an absence of collisions.

One stratum use does need reconciling rather than adopting, and the Implementation section takes it:
`meta_family`'s `intent_` charter calls the three-family stack `graphql_` / `graphitron_` / `intent_`
"the SDL strata stack", with `intent_` as its third layer. That stack is real and it is a depth
ordering, not this item's partition: `graphitron_` and `intent_` are both derivation, so the family
stack has two of its three layers inside stratum two, and stratum three is not a family at all.

## What is already written down

Worth listing, because the frame is not new and the item should read as naming what the document
already argues rather than importing something:

- Stratum two's discipline is thorough. "Derived reads are views, not stored facts"; a derivation
  earns a relation as soon as a second reader asks it; materialization is reserved for closures the
  store's own population can make cyclic (`intent_type_domain` materialized,
  `intent_class_assignable` a view).
- Stratum three's is too. "One base, many views"; code generation is "the narrowest view, not the
  model"; a consumer's answer is one projection at its own grain.
- The cadence law is already the law: "a fact refreshes on the cadence of its own source", with the
  positions case worked through (`java_` on the source cadence rather than columns on `jvm_class`).
- The three SDL stages are already named and distinguished, including that only the first produces
  declarations and the other two contribute verdicts, and that a refusal never cancels the next stage.

What is missing is the stratum vocabulary itself and the mechanical test that goes with it: a row that
can be recomputed from captured facts alone is a derived fact and must not be captured. Stated once,
that test decides every case the document currently argues one at a time, and it decides the cases the
document gets wrong.

## The vocabulary to retire

"Authored versus effective" cannot carry the distinction it is being asked to carry. Everything in the
store is authored by somebody: the DDL behind the jOOQ classes, the service methods, the configuration.
"Authored" therefore partitions nothing. And "effective" is singular where the truth is plural, since a
round-trip emitter, a federation publisher and an editor hover each want a different composition;
privileging one of them in the base relations makes one goal's answer the store's shape, which is the
opposite of "one base, many views".

Three sites carry it, and they do not agree with each other:

- `MacroCapture`'s javadoc says capture keeps "the store's picture effective rather than authored, and
  keeps the authored picture recoverable as the anti-join against the provenance relations".
- `pipeline-overview.adoc` says the opposite about the same mechanism: "the store records what the
  author wrote, with synthesis recorded as provenance rows rather than silently merged into the
  authored picture". Synthesis *is* merged into the base relations; the provenance rows are what
  un-merge it. This sentence is inaccurate as written, not just awkwardly worded.
- `fact-model.adoc`'s macro paragraph makes a good argument and draws the wrong conclusion from it. The
  argument: a derivation over the written type expression works for any macro that rewrites a type
  expression, "including ones that do not exist yet", while a derivation over the expansion's shape is
  coupled to the one macro. That is correct and it is a stratum argument. The conclusion it reaches,
  "that is why the authored form is captured at all", accepts that the base relation holds the
  expansion and the written form lives in a side table. Under the recompute test the same argument
  says the written type expression *is* the captured fact and the expansion is derived.

The cost of the inverted assignment is visible in the schema and is the sharpest evidence for the
frame. `graphitron_field_synthesis.authored_type_sdl` holds "the type expression as the author wrote
it, pre-expansion" as unparsed text, because a derived value took the captured value's seat in
`graphql_field`, and two views now recover it with nested `REPLACE` calls stripping `[`, `]` and `!`
(`intent_routine_return_binding` and `intent_field_column_scope`). A captured fact is being
reconstructed by string surgery, twice.

## Which stratum each family is in

Naming the strata is only useful if every family is assigned, so this section assigns all thirteen the
roster holds plus its one placement exemption. One family's assignment is currently misread in the
tree and was misread in this item's own siblings before they were corrected; a second turns out to be
inverted in the schema the way the macro case is, which is a conclusion the assignment reaches rather
than a premise it starts from.

- Stratum one, transcription: `graphql_` (including the generic directive definitions and
  applications at all five locations, with argument values), `jvm_`, `sql_`, `java_`, and the verdict
  residents `graphql_syntax_error` / `graphql_schema_error`. Each is what a walk read from one
  corpus.
- Stratum two, derivation: `intent_`, and **`graphitron_`**. The `graphitron_` relations are decodes
  of the generic directive applications stratum one transcribes: `graphitron_field_reference_step`
  decomposes a `@reference` path argument, `graphitron_service_arg_mapping_sigil` extracts a sigil
  from an argument value, `graphitron_table` reads a `name:`. Each is a function of captured rows.
  `MacroCapture`'s javadoc already uses the right word for it, distinguishing what "transcribes into
  `graphql_type_directive`" from what "decodes into `graphitron_federation_key`".
- Stratum three, queries: the views a consumer reads, which the document already covers under "One
  base, many views". `diagnostic` is the worked case and the roster's one placement exemption, a read
  surface unioning arms from several families' vocabularies; both its own comment and its exemption row
  already call it "the diagnostics stratum's read surface", so the word is in place here too. Cite it
  without an arm count: the two comments already disagree on how many arms there are, which is the
  unguarded-inventory smell rather than anything this item introduces.
- No stratum, scaffolding: `walk_` and `rejection_`, each reifying the legacy walk's answer so a
  derivation can be diffed against it during migration. `rejection_`'s charter already ties itself to
  `walk_`'s clock, "transitional by construction, drained family by family as detections migrate
  store-native", so this is reading the roster rather than deciding against it.
- No stratum, and permanently: `store_` and `meta_`, whose subject is the store itself rather than any
  corpus or any fact about one. `store_` records the run (what it read, what it was built from, which
  graphs it holds) and its charter already disclaims transcription in those words; `meta_` records the
  schema. Both need saying because "no stratum" otherwise reads as "transitional", which is what
  `walk_` and `rejection_` are and these two are not. The `meta_` views are also the reason stratum
  three has to be stated as *a view over facts a consumer reads* and not merely as "a view":
  `meta_relation_family` is a derivation, but over the schema's own catalog rather than over captured
  facts, so it sits here and not in stratum two.

The stratum is decided by what a relation's rows are computed *from*, not by what computes them. A
materialized derivation whose producer is a Java program is stratum two if its inputs are captured
facts; the document already accepts materialization where a view cannot serve. Stating this in the
same breath as the assignment matters, because "`graphitron_` is a derivation" otherwise reads as a
demand that a thousand lines of decoding become SQL, which is not what the stratum claims.

That covers ten of the roster's thirteen families and its one exemption. The remaining three are the
verdict families, and with `graphql_`'s two verdict residents they are the interesting cases, because
a verdict is a conclusion and the recompute test was stated over declarations. One rule extends it and
assigns all five: **a transcribed verdict is stratum one exactly while the store does not hold the
inputs the verdict was computed from, and stratum two once it does.** That is why they do not all land
together:

- `graphql_syntax_error` and `graphql_schema_error` are stratum one because their input is a document
  that has not parsed yet. There is no transcription of an unparseable file to recompute a syntax
  error from, which is the real reason those two are `graphql_` residents rather than a family of
  their own; the roster's reader-neutrality argument for them is compatible with this and does not
  replace it.
- `javac_` is stratum one: the store holds neither a compiler nor a transcription of the emitted
  sources. Worth one clause, because it is the only stratum-one family whose corpus the run itself
  produced rather than read from the consumer.
- `lint_` is **stratum two**, and this is the assignment that pays for the rule. `LintEngine` is one
  traversal over the parsed graphql-java AST, which is exactly the corpus `graphql_` transcribes, so a
  lint finding is recomputable from captured facts and the recompute test says it must not be
  captured. `lint_finding` is a table today, which makes this the second inverted assignment in the
  store after the macro case, and the family's own charter already names the destination: lint rules
  are "predicates over classified facts that should be free to migrate store-native".
- `build_warning_` is the one family the rule does not settle at family grain, and the prose should
  say so rather than round it to its sibling. The roster pairs it with `lint_` as the other arm of the
  `BuildWarning` hierarchy, but the arm is defined by carrying *no* rule, so its residents share a
  channel rather than an input set, and whether a given advisory is a function of captured facts is a
  per-producer question. Stratum one until a producer's inputs are shown to be captured, decided per
  resident rather than per family. A disclosed gap at one of thirteen is the honest form; the failure
  mode is a clean-looking answer that a reader cannot check.

Only two of the thirteen charters ever disagreed with any of this, and both are edited in
Implementation. The rest are being read, not overruled, which is the strongest evidence available that
the frame is already the store's own.

The corollary worth writing down: the 48 foreign keys from `graphitron_` into `graphql_` are a
derivation's edges to its inputs. They are correct and permanent, and they are not an argument for or
against separating the two families.

## Implementation

Prose and naming only, one commit is fine. Anchors below are symbols and quoted prose; re-find by
search, never by line number. Where a stratum already has a name in the tree, use it rather than
minting a new one; this item is not a renaming of relations.

### `fact-model.adoc`: the new stratum section

A new `== ` section placed immediately ahead of "Derived reads are views, not stored facts", since
that is the discipline it generalises. It carries:

- The stratum statement (capture transcribes facts from a corpus; derivation computes further facts
  from captured ones; queries read facts to serve a goal) and the recompute test, stated once as
  the decision procedure: a row that can be recomputed from captured facts alone is a derived fact
  and must not be captured. Note that the test decides mechanically what the derived-reads section
  argues case by case.
- The family assignment from "Which stratum each family is in" above: all thirteen families and the
  one placement exemption, the four buckets (transcription, derivation, queries, and the two
  no-stratum reasons), and the verdict rule that a transcribed verdict is stratum one exactly while
  the store does not hold the inputs it was computed from. Write it as prose that *defers to*
  `meta_family` rather than as a second copy of the roster: the roster is gate-closed against the
  observed relations and renders one page per row, where a prose prefix list is an unguarded census.
  Carry the two results a reader will not expect, since they are what the rule earns: `lint_` is a
  derivation whose findings are captured today, and `build_warning_` is settled per resident rather
  than per family.
- The clarification that a relation's stratum is decided by what its rows are a function of, not by
  what program computes them: a materialization whose producer is a Java walk is stratum two if its
  inputs are captured facts. The sentence later in the same document that currently says the
  stratum "is decided by what produces its rows" (the `intent_class_member_slot` paragraph) is
  reworded to this same test in the same edit, keeping its walk-versus-rule contrast as the
  illustration, so the two sections state one rule instead of two.
- The corollary that the foreign keys from `graphitron_` into `graphql_` are a derivation's edges
  to its inputs, correct and permanent, and no argument for or against separating the families.
  State it without the count (48 today); an unguarded census rots silently. Where a reader wants the
  enumerable answer, `meta_relation_family` and the roster gates are where it lives.
- The enforcer line the page's preamble demands. The live gate for the decode half is
  `FactSchemaGateTest.theDecodeDoesNotReplaceTheTranscription`, the verbatim-transcription twins
  already cited in the executable-form section: a stratum-two decode may not displace the
  stratum-one transcription. Name it, and state the gap precisely: the gate covers a decode that
  adds rows beside the transcription, and does not cover a macro that rewrites
  `graphql_field.type_sdl` in place, which is exactly the inverted case the macro paragraph names.
  Say in the same clause why a half-covered rule still belongs on the page: the preamble's bar is
  that a claim *names* its enforcer, not that the enforcer closes it, so a disclosed gap is the
  honest form and a later reader should not read it as an oversight. Do not cite
  `FactCaptureAgreementTest`'s registration arms as the stratum's reflection: they file
  `graphitron_` under the containment arm beside `graphql_`, because the arms answer how a
  relation's contents are pinned, not what its rows are computed from.

### `fact-model.adoc`: the macro paragraph

The paragraph beginning "Where a macro rewrote a fact, read the authored form rather than walking
the expansion" keeps its argument and inverts its conclusion. Keep: a derivation over the written
type expression works for any macro that rewrites a type expression, including ones that do not
exist yet, while a derivation over the expansion's shape is coupled to the one macro. Correct:
under the recompute test the written type expression is the captured fact and the expansion is the
derivation; today's shape, the expansion sitting in `graphql_field.type_sdl` and the written form
held as text in `graphitron_field_synthesis.authored_type_sdl`, is the inverted assignment, stated
as the transitional present rather than defended. State the prediction (correcting the assignment
makes the side column unnecessary) without citing item ids; roadmap ids do not belong in docs
prose.

### `pipeline-overview.adoc`: two sentences, not one

- In "Parse and attribute", the sentence "the store records what the author wrote, with synthesis
  recorded as provenance rows rather than silently merged into the authored picture" is factually
  wrong and is replaced with what capture does: it transcribes the pre-synthesis snapshot, then
  runs the expansions itself (`MacroCapture`), writing expansion rows through the same doors with
  the `graphitron_*_synthesis` provenance rows marking what an expansion contributed. Say the
  recoverability honestly, split by kind: for the two relations that mark rows an expansion
  *added* (`graphitron_type_declaration_synthesis`, `graphitron_type_directive_synthesis`) the
  transcription is the anti-join; the one relation that marks a row an expansion *rewrote*
  (`graphitron_field_synthesis`) leaves the written expression only in its own text column, and no
  anti-join recovers it. The blanket "recoverable as the anti-join" claim is itself part of the
  retired defence; it must not survive the correction anywhere it is restated.
- In "Capture transcribes: the fact store", "transcribes everything the run read in one
  transaction: ... the decoded graphitron and federation directives into the `graphitron_`
  relations" files a stratum-two decode under transcription. Correct to the decode being a
  derivation whose producer runs at capture cadence; the paragraph's own closing sentence ("Two
  derivations materialize at capture cadence inside the same transaction") already has the
  vocabulary, so this is one clause.

### `meta_family`: the roster is the one home, so it cannot disagree

`meta_family` is the schema's own family roster, thirteen constant rows stated as a view. Its
`definition` column carries each family's charter, and its comment says those charters were
"migrated out of this file's header so the roster has one home". `SchemaReferencePages` renders each
`definition` as its family page's preamble and `FactSchemaGateTest` closes the roster against the
observed relations in both directions, so this is the highest-authority statement of family identity
in the tree, and `fact-model.adoc`'s own preamble says that where the page and the DDL disagree, the
DDL wins. Two rows currently teach against this item and both are comment-grade prose on a `VALUES`
row, the same category of edit as the table comments below:

- The `graphitron_` row says "A row here is **still a transcription, not a conclusion**: it says
  what a directive application spelled, in graphitron's vocabulary instead of the document's."
  Correct the first clause and keep the second, which is the useful part: a `graphitron_` row is a
  decode of a captured application rather than a conclusion drawn about the schema, which makes it a
  derivation whose producer runs at capture cadence, not a second transcription. The distinction the
  sentence is reaching for is derivation-versus-judgement, and naming that gets it right without
  losing what it was protecting against.
- The `intent_` row opens "The SDL strata stack's third layer, `graphql_` under `graphitron_` under
  this name". Keep the stack, since a depth ordering over the three SDL families is a real and
  useful thing to state, and stop calling its layers strata: reword so the stack is a depth ordering
  whose lower two layers are both derivation over what `graphql_` captured. Its later "The stratum
  has two layers" sentence, about base derivations under reductions, is a layering *within*
  derivation and is consistent as written; leave it, and let the reworded opening make clear which
  of the two layerings each sentence means.

The other eleven rows are read rather than overruled and none is edited. Three are worth naming
because they carry the argument the assignment leans on: `graphql_`'s ("The family is a total
transcription", with both verdict residents argued in on the reader-neutrality test), `rejection_`'s
(the retirement clock it shares with `walk_`, which is what files it as scaffolding), and `lint_`'s
(rules as "predicates over classified facts that should be free to migrate store-native", which names
stratum two as its destination without this item having to argue it in). This item adds no stratum
word to a charter that manages without one; the roster keeps its own voice.

### `MacroCapture` and `SdlFactCapture`

- `MacroCapture`'s class javadoc drops "keeps the store's picture effective rather than authored"
  and states the stratum honestly: macro expansion is a derivation running inside the capture walk,
  writing through capture's doors with provenance rows. Apply the added-versus-rewritten split from
  above rather than the blanket anti-join claim. Keep the agreement-suite pinning sentence and the
  "nothing here rejects" paragraph. That the expansion is expected to move out of the capture walk
  is a sibling item's business and stays out of the javadoc: a forward note about a move is exactly
  the unanchored prose the documentation principle rejects.
- `MacroCapture.effectiveFieldType`'s javadoc: same treatment.
- Rename `effectiveFieldType` to `expandedFieldType` (package-private, one call site, in
  `SdlFactCapture.captureFields`). The Retired vocabulary section declines to rename
  `authored_type_sdl` and the `intent_authored_*` views because the stratum reading predicts they
  become unnecessary; the same prediction deletes this method, so lifetime does not discriminate.
  Cost and reach do: the method rename costs two lines and reaches no consumer, where a column
  rename costs a DDL edit and every reader of it. Rename the method, leave the relations.
- The inline comment above that call site ("The effective type, not the authored one ... the
  store's picture is the schema consumers see and the authored one is the anti-join") carries the
  retired claim more explicitly than the method name does, and its anti-join half is false at this
  very site: the rewritten row is in no anti-join. Rewrite it.

### The `graphitron_field_synthesis` table comment, and its section header

`COMMENT ON TABLE graphitron_field_synthesis` says "the authored expression survives here while
the field's `graphql_field` row holds the effective one". DDL comments render into the published
schema reference, so this is the highest-visibility carrier of the retired contrast. Rewrite it in
stratum vocabulary (the written expression beside the expansion's result), leaving the column name
untouched per Out of scope. A comment edit is not a relation change. Its own column comment ("the
type expression as the author wrote it, pre-expansion") is already right and stays.

The `-- ==== Macro synthesis provenance` block header above it says the relations record "which
`graphql_` rows a macro added, and **the authored text** where the macro rewrote it". The
added-versus-rewritten split it draws is exactly the one this item wants and it stays; only "the
authored text" goes, because for the rewritten kind that phrase is precisely the retired name for
the pre-expansion fact. Say the written expression.

### The `intent_` view comments that read the synthesis column

The same argument reaches three consumers of that column. View comments render into the published
schema reference beside the table comments (`SchemaReferencePages` marks a view and prints its
comment the same way), so these carry the retired contrast just as far:

- `intent_routine_return_binding`'s view comment, "The type read is the authored named type with
  its wrappers stripped, taken off `graphitron_field_synthesis` where a macro rewrote the field's
  type expression", and its `type_name` column comment, "the authored named type with its list and
  non-null wrappers stripped".
- `intent_field_column_scope`'s view comment, "The two read the named type at different stages,
  this rule the field's current one and that rule the authored one". The neighbouring "the named
  type read is the one the author wrote" is already stratum vocabulary and stays.
- `intent_field_reference_discovery`'s view comment, "which reads the authored type expression
  through `graphitron_field_synthesis`".

Each says the written type expression, or drops the qualifier where the
`graphitron_field_synthesis` join in the same clause already says which of the two it means. No
relation changes; comment edits only, as above.

### Test prose carrying the vocabulary

- `MacroCaptureTest`'s class javadoc: "The store's picture is the effective schema".
- `MacroCaptureTest`'s `@DisplayName` ending "and the authored type survives": say the written
  type expression.
- `FactCaptureAgreementTest`: the comment "The authored picture is the anti-join, so the macro has
  to be what put the unauthored rows there" and the javadoc "the store keeps the authored
  expression".

### The sibling items that restate the frame

Three siblings restate this frame in the retired word, so the vocabulary would otherwise ship
half-adopted across `roadmap/`. The file rename and the three `depends-on:` keys that named the old
slug are already done in the revision that scoped this in; what remains is the body prose:

- `nodehood-derives-from-two-corpora.md` opens its own restatement with "The store has three tiers"
  and concludes "It is tier two"; it also still carries the retired "effective rather than authored"
  phrase quoted from `MacroCapture`, which the same edit corrects.
- `graphitron-decodes-read-rows-not-ast.md`: "so a Java-computed derivation over captured rows is
  tier two".
- `assembled-schema-owns-the-sdl-census.md`: "that tier one for SDL should bottom out at".

Regenerate `roadmap/README.md` after editing the bodies, since the rendered roll-up quotes each
item's opening paragraph.

## Sweep fences

The Retired vocabulary section below defines the Done-gate greps. Four families of hits are a
different sense of the same words and stay:

- "the effective schema-file-extension filter" (`SchemaRecipe`, `AbstractRewriteMojo`, the
  `store_graph_schema_extension` table comment, `FactCaptureAgreementTest`'s round-trip
  assertion): "effective" as the filter in effect, even though "the effective schema"
  substring-matches it.
- "effective type" meaning the base declaration merged with its extensions
  (`graphql_field.ordinal`'s comment, `ArgNameCompletions`' field-order javadoc).
- "authored" contrasting author input with structural inference or with generator output: the
  claim stratum's `intent_authored_*` vocabulary, and in `fact-model.adoc` both the provenance
  section and "the authored form behind each resolved value" under "One base, many views",
  plus "authored condition", "authored filter", "authored coordinates" and kin across the tree.
- "authored" versus "synthesized" naming *which rows an expansion contributed*, as opposed to naming
  the pre-expansion value of a row it rewrote. For rows an expansion added, the provenance relation
  genuinely partitions and the anti-join genuinely recovers the transcription, so the pair is
  accurate there and the sites that use it are load-bearing. They stay:
  `graphitron_type_directive_synthesis`'s table comment ("synthesized rather than authored"),
  `graphql_field.declaration_line`'s comment ("an authored row sits lexically inside its site; a
  synthesized row shares its synthesis site"), the two comments saying a synthesized `@key` hangs off
  the type's "causing authored site" (`graphql_type_directive.declaration_line` and
  `graphitron_federation_key.source_name`),
  `graphitron_undecoded_argument.source_name`'s "authored applications always have one",
  `graphitron_connection`'s "the macro's spec, as authored", `SdlFactCapture`'s ordinal-counter
  javadoc ("place a synthesized application after every authored one"), and `MacroCaptureTest`'s
  "indistinguishable from an authored one" (which the class-javadoc rewrite above reaches anyway).
  This fence is the reason the `Macro synthesis provenance` header is a *sweep* target while its
  neighbours are not: that header uses the pair for both kinds in one sentence, and only the
  rewritten half is retired.

The third and fourth fences together are what "scoped to authored-versus-synthesized as names for
the store's contents" means: the retirement takes the *pre-expansion-value* sense and leaves the
*which-rows* sense, and neither fence is a licence to sweep the other's sites.

No `RetiredVocabularyGuardTest.PHRASE_REGISTRY` entry now: the registry's own bar is a term that
survives a sweep, and this item is the first sweep. Registration is the escalation if the phrase
recurs.

## Verification

- `mvn install -Plocal-db`: the docs render, the javadoc reference gate over the edited javadoc,
  `RoadmapReferenceGuardTest` over the edited prose, `FactSchemaGateTest`'s comment-coverage and
  roster gates over the edited `meta_family` rows, and the full suite over the `effectiveFieldType`
  rename.
- `mvn -pl roadmap-tool exec:java -q` after the file rename, so `roadmap/README.md` carries the new
  title and the three siblings' dependency links resolve.
- Pre-run the Retired vocabulary greps before requesting In Review; the fences above say what a
  hit that stays looks like. Every hit is either edited per Implementation or matches a stated
  fence: a hit that matches neither means the fences are wrong, not that the hit is fine.

## Retired vocabulary

- "effective rather than authored", "the authored picture", "the effective picture", "the effective
  schema" as names for a stratum or for the store's contents.
- "authored form" / "authored type" / "authored named type" / "authored type expression" /
  "authored expression", and a bare "the authored one" contrasted with an expansion's result, as
  names for the pre-expansion fact. The captured fact needs no qualifier; the derived one is named
  by its derivation.

The grep is the word `authored` across the DDL, the two `.adoc` pages and the four Java files
Implementation names, not the fixed spellings above: the carriers vary the noun, and three of the
four `intent_` sites named in Implementation match none of the fixed phrases. Run the same grep for
`effective` over those files. The fences above are what makes the wider grep readable, and the fourth
one carries the bulk of the DDL's hits.

Deliberately *not* retired here, because they are relation and column names with their own scope: the
three `intent_authored_*` views, and `graphitron_field_synthesis.authored_type_sdl`. The stratum reading
predicts all four become unnecessary rather than merely misnamed, so renaming them now would be work
thrown away. They are listed in the sibling items that would retire them.

## Out of scope

- Every code move. Nothing about capture, derivation or any relation changes here.
- Renaming or retiring the `intent_authored_*` views, the three synthesis relations, or
  `authored_type_sdl`.
- The `CONNECTION` macro's stratum correction, which is the change that would actually delete
  `authored_type_sdl`.
- A stratum column on `meta_family`. Making each family's stratum queryable data rather than charter
  prose is the shape the roster's own design argues for, and it would let a gate close the assignment
  the way the roster already closes the family list. It is a DDL change and a gate, so it is not this
  item; the prose carries the assignment meanwhile. Worth filing separately, and the assignment above
  is what such a column would have to encode, including the two cases a plain per-family column cannot
  hold: `build_warning_`, which the rule settles per resident, and a family whose stratum is the
  destination its charter names rather than where its rows sit today.
- Correcting `lint_finding` from a captured table to a derivation. The assignment above concludes that
  `lint_` is stratum two and that capturing its findings is the inverted assignment; acting on that
  moves rows, so it is a separate item exactly as the `CONNECTION` macro's correction is. Naming the
  inversion is this item's job and fixing it is not.
- Renaming the `tier` column on `intent_resolved_field_claim`, or the test-tier annotations. Adopting
  "stratum" for this axis leaves both alone: they name different axes and neither becomes wrong.
