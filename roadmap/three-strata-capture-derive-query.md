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
spend elsewhere. "Tier" is spent three times over on a named axis, and in loose prose besides
(`PolymorphicSelectionSetClassGenerator`'s "the same wire-boundary tier", `GraphitronSchemaBuilder`'s
"the actionable tier of the `@table`-on-input deprecation signal"), which the list below does not try
to enumerate. The three named axes:

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

The count is not the whole argument, though, and the section would be dishonest if it stopped there:
the same collision analysis run on "tier" has to be run on "stratum", and it does not come back empty.
The word is spent on three other things.

- A coarser sectional partition, in the DDL's own section headers: `-- ==== Semantic stratum: the
  decoded graphitron and federation inventory`, `-- ==== Derived stratum: claims`, `-- ==== Diagnostics
  stratum`, and a back-reference to "the transcription strata above". That partition and this item's
  disagree in three places. Semantic and Derived are separate strata there and both are stratum two
  here, and the Semantic header sits directly above the family this item reclassifies as derivation.
  Diagnostics is a stratum there and cuts across all of this item's buckets here: `javac_` and
  `graphql_syntax_error` in stratum one, `lint_` and `intent_authored_claim_conflict` and
  `graphql_schema_error`'s `ASSEMBLY` arm in stratum two, `rejection_` in no stratum. And transcription
  is plural there where stratum one is singular, while stratum three has no section at all.
- A layering *within* a family: `meta_family`'s `intent_` charter has "The stratum has two layers",
  `intent_resolved_field_claim`'s comment has "The stratum's second layer", and three block comments
  inside the `Derived stratum` section say "The stratum's second / third / fourth resident group".
- Two rendered sites on the second page this item already edits: `pipeline-overview.adoc`'s heading
  `== The derived strata: claims and violations as facts` and, above it, the mermaid node
  `E["Derived strata<br/>(intent_ claim views,<br/>violation facts)"]`. Plural, and the
  set they name spans every bucket, since claims are stratum two while the violations beside them are
  `rejection_` (no stratum), `lint_` (two), `javac_` (one), `build_warning_` (per resident) and
  `graphql_`'s two verdict residents (one, and two on the `ASSEMBLY` arm). The section body then uses
  both "the `intent_` stratum" and "the diagnostics stratum" in one paragraph. This is the same
  collision as the DDL's `Diagnostics stratum` header, one page closer to the reader and rendered
  rather than commented, so Implementation takes both sites.

None of that argues for a different word, because the numbered form is the disambiguator and it is
free: **stratum one / two / three** is this axis and only this axis, and an unnumbered "the X stratum"
is a section of the DDL or a layer inside one family. State that rule where the strata are named,
because a reader who meets "the diagnostics stratum" a few paragraphs from "stratum one" will
otherwise count four. What the rule cannot paper over is a name that positively teaches the wrong
partition, and three do: the DDL's `Semantic stratum` header, and `pipeline-overview.adoc`'s `The
derived strata` heading and its mermaid node. Implementation takes all three and argues the rest safe
under the rule. All of it is comment, heading and diagram prose, the same category as the
`meta_family` rows.

One further stratum use needs reconciling rather than adopting, and Implementation takes it too:
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
tree and was misread in this item's own siblings before they were corrected; two more turn out to be
inverted in the schema the way the macro case is, and one of those two splits inside a single relation.
Those are conclusions the assignment reaches rather than premises it starts from, which is the point of
assigning every family rather than the ones the frame was built on.

- Stratum one, transcription: `graphql_`'s declaration relations (including the generic directive
  definitions and applications at all five locations, with argument values), `jvm_`, `sql_`, and
  `java_`. Each is what a walk read from one corpus. `graphql_`'s two verdict residents are the
  exception, and the verdict rule below does not settle them together.
- Stratum two, derivation: `intent_`, and **`graphitron_`**. The `graphitron_` relations are decodes
  of the generic directive applications stratum one transcribes: `graphitron_field_reference_step`
  decomposes a `@reference` path argument, `graphitron_service_arg_mapping_sigil` extracts a sigil
  from an argument value, `graphitron_table` reads a `name:`. Each of those is a function of captured
  rows. One column in the family is not, and it is the macro inversion again rather than a second
  exception: `graphitron_field_synthesis.authored_type_sdl` holds a pre-expansion type expression that
  appears in no `graphql_` row, so a stratum-one fact currently sits inside the stratum-two family.
  Say the family is derivation and name that column as the inversion's residue, rather than letting
  "each is a function of captured rows" stand over every relation the prefix covers; correcting the
  assignment is what removes the exception. `MacroCapture`'s javadoc already uses the right word for
  the decode half, distinguishing what "transcribes into `graphql_type_directive`" from what "decodes
  into `graphitron_federation_key`".
- Stratum three, queries: the reads a consumer shapes to its own goal, which the document already
  covers under "One base, many views". The two-versus-three boundary needs its own sentence, because
  "a view a consumer reads" describes plenty of stratum two: `intent_resolved_field_claim` is a view
  over captured facts and its own comment calls it "what a planning reader eventually joins". The
  discriminator is the roster's own naming rule, which is gate-closed rather than newly minted: a
  relation named for whose vocabulary its rows are written in is a fact the store owns, so stratum
  two, while a relation that exists because one consumer wanted one read has no vocabulary of its own
  to be named for. That is why no family lands in stratum three and why the one relation that does is
  prefix-less. `diagnostic` is that worked case and the roster's one placement exemption, a read
  surface unioning arms from several families' vocabularies; both its own comment and its exemption row
  already call it "the diagnostics stratum's read surface", so the word is in place here too. Cite it
  without an arm count: the two comments already disagree on how many arms there are, which is the
  unguarded-inventory smell rather than anything this item introduces.
- No stratum, scaffolding: `walk_` and `rejection_`, each reifying the legacy walk's answer so a
  derivation can be diffed against it during migration. `rejection_`'s charter already ties itself to
  `walk_`'s clock, "transitional by construction, drained family by family as detections migrate
  store-native", so this is reading the roster rather than deciding against it. This bucket is decided
  before the strata question, and the ordering has to be stated because either test would otherwise
  pull both families into stratum two: `walk_`'s rows are recomputable by construction, that being
  what a differential is for, and the roster titles `rejection_` "The legacy walk's verdicts", so the
  verdict rule reaches it as well. A relation that exists to be diffed against its own replacement has
  no stratum whatever its inputs are; that is what scaffolding means here.
- No stratum, and permanently: `store_` and `meta_`, whose subject is the store itself rather than any
  corpus or any fact about one. `store_` records the run (what it read, what it was built from, which
  graphs it holds) and its charter already disclaims transcription in those words; `meta_` records the
  schema. Both need saying because "no stratum" otherwise reads as "transitional", which is what
  `walk_` and `rejection_` are and these two are not. The `meta_` views are also the reason stratum two
  has to be stated as a derivation *over captured facts* and not merely as "a derivation":
  `meta_relation_family` is a derivation, but over the schema's own catalog rather than over anything a
  walk read, so it sits here and not in stratum two. It is not stratum three either, `meta_` being a
  family with a vocabulary of its own under the discriminator above.

The stratum is decided by what a relation's rows are computed *from*, not by what computes them. A
materialized derivation whose producer is a Java program is stratum two if its inputs are captured
facts; the document already accepts materialization where a view cannot serve. Stating this in the
same breath as the assignment matters, because "`graphitron_` is a derivation" otherwise reads as a
demand that a thousand lines of decoding become SQL, which is not what the stratum claims.

That covers ten of the roster's thirteen families and its one exemption. The remaining three are the
verdict families the scaffolding bucket did not already take; `rejection_` is the fourth family the
roster titles as verdicts, and it is bucketed above. With `graphql_`'s two verdict residents these are
the interesting cases, because a verdict is a conclusion and the recompute test was stated over
declarations. One rule extends it and reaches all five: **a transcribed verdict is stratum one exactly
while the store does not hold the inputs the verdict was computed from, and stratum two once it does.**
That is why they do not all land together:

- `graphql_syntax_error` is stratum one because its input is a document that has not parsed. There is
  no transcription of an unparseable file to recompute a syntax error from, and that is a firmer
  reason for its `graphql_` residency than a family of its own would have; the roster's
  reader-neutrality argument is compatible with this and does not replace it.
- `graphql_schema_error` does not join it, and the rule splits it along a column the relation already
  carries. Its `stage` is a closed `CHECK` over `REGISTRY` and `ASSEMBLY`, and the two differ in
  exactly what the rule asks. A `REGISTRY` refusal is a second base declaration whose loser the
  registry, per `graphql_duplicate_declaration`'s comment, "reports ... as a verdict without
  offering its declaration to capture", so the store does not hold the input: stratum one. An
  `ASSEMBLY` refusal is the opposite case, the same comment saying "the same pass captures both the
  verdict and the retained duplicate this relation holds", and the four checks this relation's own
  comment enumerates are each a predicate over captured rows: that every named type resolves
  (`graphql_field` against `graphql_type`), that an object satisfies the interfaces it claims
  (`graphql_implements`), that a directive sits where its definition permits
  (`graphql_directive_location` against the five application relations, which `graphql_directive_site`
  already unions), and that the schema has a query root (`graphql_root_operation`). So the `ASSEMBLY`
  arm is stratum two, by the same rule and for the same reason as `lint_` below. Stating it is what
  keeps the rule honest: the argument that carries `graphql_syntax_error` covers the parse stage only,
  and applying it to both residents because they share a table is exactly the family-grain rounding
  `build_warning_` is disclosed for. This split has the better shape of the two, being already visible
  data rather than a per-resident judgment. Say plainly what the assignment does and does not demand:
  it says the `ASSEMBLY` verdicts are recomputable from captured facts, not that they should be
  recomputed. The producer here is graphql-java's own validator, so migrating this arm would mean
  restating the specification's structural rules in the store, which is a far larger question than
  `lint_`'s and not one this assignment answers.
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
- The numbering rule, in the same breath as the names: the numbered form is this axis and only this
  axis, and an unnumbered "the X stratum" in the DDL or on this page is a section of the schema file or
  a layer inside one family. This page itself carries five unnumbered uses besides the sentence being
  reworded ("the claim stratum", "the whole `intent_` stratum", "the derived stratum", and "the
  diagnostics stratum" twice), and "the derived stratum" is stratum two under another name, so say
  which of those the numbering replaces and leave the rest reading as what they are. Enumerate them
  from the file rather than from this list; a count in the section that argues against unguarded
  counts is the wrong thing to trust. Without the rule the page names three strata and then uses the
  word five more times for something else.
- The family assignment from "Which stratum each family is in" above: all thirteen families and the
  one placement exemption, the four buckets (transcription, derivation, queries, and the two
  no-stratum reasons), and the verdict rule that a transcribed verdict is stratum one exactly while
  the store does not hold the inputs it was computed from. Write it as prose that *defers to*
  `meta_family` rather than as a second copy of the roster: the roster is gate-closed against the
  observed relations and renders one page per row, where a prose prefix list is an unguarded census.
  Carry the three results a reader will not expect, since they are what the rule earns: `lint_` is a
  derivation whose findings are captured today, `graphql_schema_error` is stratum two on its
  `ASSEMBLY` stage and stratum one on its `REGISTRY` stage, and `build_warning_` is settled per
  resident rather than per family. State the two-versus-three discriminator in the same breath as the
  buckets, since "a view a consumer reads" otherwise reads onto the whole `intent_` family.
- The clarification that a relation's stratum is decided by what its rows are a function of, not by
  what program computes them: a materialization whose producer is a Java walk is stratum two if its
  inputs are captured facts. The sentence later in the same document that currently says the
  stratum "is decided by what produces its rows" (the `intent_class_member_slot` paragraph) is
  reworded to this same test in the same edit, keeping its walk-versus-rule contrast as the
  illustration.

  **The retired rule has a twin in the DDL, and the DDL wins, so correcting the page alone makes
  things worse rather than better.** The block comment above `intent_class_member_slot`, inside the
  `Derived stratum: claims` section, carries the same argument to the same conclusion: "The derived
  stratum is chosen by what produces a row, a rule rather than a transcription, and never by which
  key the row happens to carry." That is the DDL's version of the page's paragraph, not a passing
  echo of it. This page's own preamble says "Where this page and the DDL disagree, the DDL wins", so
  rewording only the page leaves the retired rule standing in the authoritative place and formally
  overriding the new one, which is the exact inversion this item exists to stop. Reword the block
  comment in the same edit, keeping its keying argument (a resolution is keyed by whatever its own
  question is about, which is why not every resident leads with `graph_name`) and replacing only the
  what-produces-a-row conclusion with the recompute test. It is the only twin: that phrasing appears
  nowhere else in the DDL and at no other site in the docs, so this is one comment, in the category
  Implementation already accepts.
- The corollary that the foreign keys from `graphitron_` into `graphql_` are a derivation's edges
  to its inputs, correct and permanent, and no argument for or against separating the families.
  State it without the count (48 today); an unguarded census rots silently. Where a reader wants the
  enumerable answer, `meta_relation_family` and the roster gates are where it lives.
- The enforcer line the page's preamble demands, written per claim rather than as one line for the
  section. This section carries three claims of different enforceability and one enforcer line
  covering all of them would overstate two of them, which on a page whose preamble reads "a rule
  without one is not on this page" is the failure worth avoiding.
  - *A stratum-two decode may not displace the stratum-one transcription.* Gated, partly.
    `FactSchemaGateTest.theDecodeDoesNotReplaceTheTranscription`, the verbatim-transcription twins
    already cited in the executable-form section. State the gap precisely: the gate covers a decode
    that adds rows beside the transcription, and does not cover a macro that rewrites
    `graphql_field.type_sdl` in place, which is exactly the inverted case the macro paragraph names.
  - *The recompute test itself, and the family assignment.* Not gated at all, and say so rather than
    letting the first gate's name cover them by proximity. Nothing in the suite fails when a captured
    relation turns out to be recomputable; that is the whole reason `lint_finding` and
    `authored_type_sdl` sit inverted today with every gate green. The absence is the argument for the
    `meta_family` stratum column in Out of scope, so point at it here rather than leaving the reader
    to wonder what would catch the next inversion.
  - Either way, say why a claim whose enforcer does not close it still belongs on the page: the
    preamble's bar is that a claim *names* its enforcer, and a disclosed gap is the honest form, so a
    later reader should not read it as an oversight. This is the one place the item argues with the
    preamble rather than obeying it, since the preamble asks for the gate "that fails when the claim
    breaks"; make the disagreement visible instead of quietly satisfying the letter.

  Do not cite `FactCaptureAgreementTest`'s registration arms as the stratum's reflection: they file
  `graphitron_` under the containment arm beside `graphql_`, because the arms answer how a relation's
  contents are pinned, not what its rows are computed from.

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

### `pipeline-overview.adoc`: two sentences and a heading

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
- Two sites on this page carry "derived strata" over a set that spans every bucket this item assigns,
  and both are edited. The section heading `== The derived strata: claims and violations as facts`,
  and the mermaid node above it, `E["Derived strata<br/>(intent_ claim views,<br/>violation facts)"]`,
  which folds the two in by name and renders as a diagram, making it the most visible carrier on the
  page and the first one a reader meets. Reword both to what they are about, the claim and violation
  relations the pipeline builds above the base ones, dropping the stratum claim rather than trying to
  make it true; the numbering rule cannot rescue a plural "strata" that names a stratum-two family and
  four diagnostics families at once. The body's own "the `intent_` stratum" and "the diagnostics
  stratum" are the safe unnumbered uses and stay, since one is the tree's name for that family and the
  other for that arm set. No `xref` anywhere carries a `pipeline-overview.adoc#` anchor, so the
  heading rename reaches no link; re-confirm with a grep for the anchor rather than trusting this
  sentence.

  Three other pages use the same phrase and none is edited, which is worth stating so the asymmetry
  does not read as a miss: `architecture/index.adoc` and `explanation/index.adoc` use "the derived
  strata" in navigation blurbs for this very page, and `development-principles.adoc` has "the derived
  strata carry each decision as views". All three mean the derivation layer, which is what this item
  calls stratum two, so they are the frame under another name rather than a competing partition. The
  two sites above are the ones that fold the diagnostics families in, which is what makes them wrong
  where the three are merely coarser.

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

### The DDL section headers that also say stratum

The schema file's section headers name three strata of their own. One of them contradicts the assignment
and is reworded; the other two are safe under the numbering rule, and the argument for leaving them is
worth stating so a later reader does not read the asymmetry as an oversight. These are block comments,
the same category of edit as the `Macro synthesis provenance` header below.

- `-- ==== Semantic stratum: the decoded graphitron and federation inventory`, above the `graphitron_`
  family. It names as a stratum in its own right the family this item files under derivation, sitting a
  few thousand lines from `-- ==== Derived stratum: claims`, which is the same numbered stratum. Fold
  the two into one reading rather than renumbering the file: keep the sections, and reword this header
  so it names what the section holds (the decoded graphitron and federation inventory, a derivation over
  the transcription) without claiming a stratum of its own.

  **One comment refers to this header by name and is orphaned by the reword, so it moves in the same
  edit.** `COMMENT ON TABLE graphql_duplicate_declaration` opens "sibling of the semantic stratum's
  undecoded-argument relation", and `graphitron_undecoded_argument` lives inside this section, so the
  phrase is a by-name pointer at the header rather than a loose use of the word. The numbering rule
  does not licence it once the header stops saying stratum, since the rule's licence is for an
  unnumbered use that names a section of the DDL and there would no longer be such a section. Repoint
  it at the relation, which is what the sentence is actually about: name
  `graphitron_undecoded_argument` directly, the two being siblings because each is its family's
  *overflow* relation, holding what the primary write path declined so no authored text is lost. Take
  that from what the two comments already say rather than reaching for a sharper-sounding property:
  each opens on the word (`The tolerant-decode overflow`, `The duplicate-declaration overflow`) and
  each closes the same thought ("the authored value is never lost", "no authored text is lost"). In
  particular do not write that both hold what a *decode* declined. That is true of the `graphitron_`
  side and false of this one, whose own comment names the mechanism as a transcription merge, "Capture
  is first-wins in merge order; the losing occurrence records here", in a relation that lives in the
  `graphql_` transcription family. Attributing a decode to a stratum-one transcription artifact, in a
  rendered comment, is the inversion this item exists to name. These are the only two live sites; the
  word appears nowhere else in the DDL, in the docs, or in Java. Run the same dependency check the `Diagnostics stratum` alternative below gets,
  because a header reword that strands a reference is worse than the header it fixed.

  The `Derived stratum: claims` header is consistent with the assignment as written and needs no edit,
  though its block prose is not: see the twin of the retired keying rule inside that section, which the
  `fact-model.adoc` section above scopes in. Worth checking in the same pass that its "rows derive on
  read from the transcription strata above" still reads right beside a singular stratum one.
- `-- ==== Diagnostics stratum`, whose section prose already says "nothing reads a base relation of this
  stratum directly". This grouping genuinely spans stratum one, stratum two and no stratum, so it is not
  a wrong claim about any relation, it is the word doing a second job at the widest possible spread.
  Preferred: leave the header alone and let the numbering rule carry it, because "the diagnostics
  stratum" is the tree's established name for that arm set, is load-bearing in the `diagnostic`
  exemption row this item deliberately reads rather than edits, and renaming it reaches further than a
  naming item should. If the author would rather remove the ambiguity at the source, the alternative is
  to say diagnostics is a subject grouping and not a stratum, and that edit reaches the two verdict
  relations' comments ("alone among this stratum's arms", "this stratum's other arms", "this stratum's
  other message columns"), `diagnostic`'s own comment, the exemption row, and `fact-model.adoc` twice.
  Pick one; do not half-apply it.

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
slug are already done in the revision that scoped this in; what remains is the body prose.

**The instruction is `grep -in tier` over each of the three bodies and convert every use on this axis,
not the quotations below.** Because Retired vocabulary deliberately keeps `roadmap/` out of its own
grep, this enumeration is the only mechanism reaching these files, and quotations alone reach a small
fraction of the sites. The counts, so an implementer can tell when a file is done:

- `nodehood-derives-from-two-corpora.md`, fourteen lines, including a section heading (`## A misplaced
  tier, not an inverted polarity`) and the phrases "tier-one relation", "Under tier discipline",
  "across the two tiers", "a tier-three query" and "the tier reading" twice. Its opening restatement
  ("The store has three tiers", concluding "It is tier two") is the visible one and the roll-up quotes
  it, but it is two lines of the fourteen. The file also carries the retired "effective rather than
  authored" phrase quoted from `MacroCapture`, which the same pass corrects.
- `graphitron-decodes-read-rows-not-ast.md`, six lines, not the one previously named: "a derivation
  running in the transcription tier", "A tier is decided by what a relation's rows are computed from",
  "is tier two", "The tier label is a description of what", "the tier-naming item", "the tier reading".
- `assembled-schema-owns-the-sdl-census.md`, one line: "that tier one for SDL should bottom out at".

One exclusion, so the grep is readable: `nodeid-key-projection-on-routine-params.md`'s five "three
tiers" are a node-key-source precedence axis, the reconciled `NodeType` then the table's own generator
metadata then `@node` plus the catalog primary key. Not this axis, and not the name-match precedence
the collision list already covers, so they stay; that file is not a sibling of this item and is not
edited. Its `intent_resolved_node_key_column`, with the `tier` column it would carry, is a relation
that item *proposes* and that exists nowhere in the tree, so name it as planned or not at all rather
than sending a reader to grep for it. It is also quiet support for the word: a fourth tier axis is
arriving in a sibling plan, which is one more reason the numbered form has to be stratum.

Regenerate `roadmap/README.md` after editing the bodies, since the rendered roll-up quotes each
item's opening paragraph.

## Sweep fences

The Retired vocabulary section below defines the Done-gate greps. Five families of hits are a
different sense of the same words and stay:

- "the effective schema-file-extension filter" (`SchemaRecipe`, `AbstractRewriteMojo`, the
  `store_graph_schema_extension` table comment, `FactCaptureAgreementTest`'s round-trip
  assertion): "effective" as the filter in effect, even though "the effective schema"
  substring-matches it. The same in-effect sense reaches one site the filter wording does not
  cover, so read this fence as the sense rather than as its list: `SdlFactCapture`'s
  `captureSchema` javadoc, "the relation is total over the effective roots", meaning the roots in
  effect after the name convention fills in for a missing schema definition.
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
  synthesized row shares its synthesized site's inherited position"), the two comments saying a
  synthesized `@key` hangs off the type's "causing authored site"
  (`graphql_type_directive.declaration_line` and `graphitron_federation_key.source_name`),
  `graphitron_undecoded_argument.source_name`'s "authored applications always have one",
  `graphitron_connection`'s "the macro's spec, as authored", `SdlFactCapture`'s ordinal-counter
  javadoc ("place a synthesized application after every authored one"), and `MacroCaptureTest`'s
  "indistinguishable from an authored one" (which the class-javadoc rewrite above reaches anyway).
  This fence is the reason the `Macro synthesis provenance` header is a *sweep* target while its
  neighbours are not: that header uses the pair for both kinds in one sentence, and only the
  rewritten half is retired.
- Identifiers. The retirement is a vocabulary sweep over prose, and the only identifier it renames is
  `effectiveFieldType`, argued above on cost and reach. Every other `effective` or `authored` in a
  name stays, including the paired locals `var effective` / `var authored` in `MacroCaptureTest`'s
  carrier-rewrite test, which sit in the very method whose `@DisplayName` this item edits and which
  the grep therefore hits. Renaming those two would be churn in a test whose assertions the item does
  not touch; the sweep stops at the display name and the class javadoc. This fence is why the
  `authored_type_sdl` column and the `intent_authored_*` views are declined in Retired vocabulary
  rather than merely deferred.

The third and fourth fences together are what "scoped to authored-versus-synthesized as names for
the store's contents" means: the retirement takes the *pre-expansion-value* sense and leaves the
*which-rows* sense, and neither fence is a licence to sweep the other's sites. The fifth keeps the
whole sweep on the prose side of the line the method rename is the single stated exception to.

No `RetiredVocabularyGuardTest.PHRASE_REGISTRY` entry now: the registry's own bar is a term that
survives a sweep, and this item is the first sweep. Registration is the escalation if the phrase
recurs.

## Verification

- `mvn install -Plocal-db`: the docs render, the javadoc reference gate over the edited javadoc,
  `RoadmapReferenceGuardTest` over the edited prose, `FactSchemaGateTest`'s comment-coverage and
  roster gates over the edited `meta_family` rows and the edited `graphql_duplicate_declaration` and
  `graphitron_field_synthesis` comments, and the full suite over the `effectiveFieldType` rename. The
  DDL section headers are block comments and no gate reads them, which is why the orphaned-reference
  check on the `Semantic stratum` reword is a manual grep rather than something the build catches.
- `mvn -pl roadmap-tool exec:java -q` after the sibling bodies are edited, since the roll-up quotes
  each item's opening paragraph and `nodehood-derives-from-two-corpora.md`'s opening is one of the
  lines the `tier` sweep rewrites. The file rename and the three `depends-on:` keys landed in an
  earlier revision, so there is no rename left to perform here.
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

`roadmap/` is deliberately outside that grep, the three sibling bodies being reached by Implementation
instruction instead. Four other items use the retired pre-expansion sense in plan prose
(`delivery-verdict-derives-from-the-store.md`, `lsp-reads-the-fact-store.md` at two sites, and
`name-matching-stratum.md`), and one of those sites quotes the `intent_field_reference_discovery`
comment this item rewrites, so the quotation goes stale here. They stay: a plan body is re-read when
its own item runs, and sweeping plan prose for a vocabulary each item's implementation will restate
anyway is churn rather than adoption. The three siblings are in because they restate *the frame* in
the retired word, which is a different thing from using the retired noun.

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
  is what such a column would have to encode, including the three cases a plain per-family column
  cannot hold: `build_warning_`, which the rule settles per resident; `graphql_schema_error`, which it
  settles per `stage` value; and a family whose stratum is the destination its charter names rather
  than where its rows sit today.
- Correcting `lint_finding` from a captured table to a derivation. The assignment above concludes that
  `lint_` is stratum two and that capturing its findings is the inverted assignment; acting on that
  moves rows, so it is a separate item exactly as the `CONNECTION` macro's correction is. Naming the
  inversion is this item's job and fixing it is not.
- Renaming the `tier` column on `intent_resolved_field_claim`, or the test-tier annotations. Adopting
  "stratum" for this axis leaves both alone: they name different axes and neither becomes wrong.
