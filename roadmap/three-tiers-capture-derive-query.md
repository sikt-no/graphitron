---
id: R712
title: "Name the three tiers, and retire authored versus effective"
status: Spec
bucket: architecture
priority: 3
theme: docs
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# Name the three tiers, and retire authored versus effective

The store has three tiers. Capture transcribes facts from a corpus. Derivation computes further facts
from captured ones. Queries read facts to serve a goal. `fact-model.adoc` already teaches almost all
of the discipline that follows from this, in detail and with the enforcement gates named, but it never
states the three tiers as such, and it carries a vocabulary that contradicts them in one place.

This item is prose and naming only. No relation changes, no code moves.

## What is already written down

Worth listing, because the frame is not new and the item should read as naming what the document
already argues rather than importing something:

- Tier two's discipline is thorough. "Derived reads are views, not stored facts"; a derivation earns a
  relation as soon as a second reader asks it; materialization is reserved for closures the store's own
  population can make cyclic (`intent_type_domain` materialized, `intent_class_assignable` a view).
- Tier three's is too. "One base, many views"; code generation is "the narrowest view, not the model";
  a consumer's answer is one projection at its own grain.
- The cadence law is already the law: "a fact refreshes on the cadence of its own source", with the
  positions case worked through (`java_` on the source cadence rather than columns on `jvm_class`).
- The three SDL stages are already named and distinguished, including that only the first produces
  declarations and the other two contribute verdicts, and that a refusal never cancels the next stage.

What is missing is the tier vocabulary itself and the mechanical test that goes with it: a row that can
be recomputed from captured facts alone is a derived fact and must not be captured. Stated once, that
test decides every case the document currently argues one at a time, and it decides the cases the
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
  coupled to the one macro. That is correct and it is a tier argument. The conclusion it reaches,
  "that is why the authored form is captured at all", accepts that the base relation holds the
  expansion and the written form lives in a side table. Under the tier test the same argument says the
  written type expression *is* the captured fact and the expansion is derived.

The cost of the inverted assignment is visible in the schema and is the sharpest evidence for the
frame. `graphitron_field_synthesis.authored_type_sdl` holds "the type expression as the author wrote
it, pre-expansion" as unparsed text, because a derived value took the captured value's seat in
`graphql_field`, and two views now recover it with nested `REPLACE` calls stripping `[`, `]` and `!`
(`intent_routine_return_binding` and `intent_field_column_scope`). A captured fact is being
reconstructed by string surgery, twice.

## Which tier each family is in

Naming the tiers is only useful if the families are assigned, and one assignment is currently
misread in the tree and was misread in this item's own siblings before they were corrected.

- Tier one, transcription: `graphql_` (including the generic directive definitions and applications
  at all five locations, with argument values), `jvm_`, `sql_`, `java_`, and the verdict residents
  `graphql_syntax_error` / `graphql_schema_error`. Each is what a walk read from one corpus.
- Tier two, derivation: `intent_`, and **`graphitron_`**. The `graphitron_` relations are decodes of
  the generic directive applications tier one transcribes: `graphitron_field_reference_step`
  decomposes a `@reference` path argument, `graphitron_service_arg_mapping_sigil` extracts a sigil
  from an argument value, `graphitron_table` reads a `name:`. Each is a function of captured rows.
  `MacroCapture`'s javadoc already uses the right word for it, distinguishing what "transcribes into
  `graphql_type_directive`" from what "decodes into `graphitron_federation_key`".
- Tier three, queries: the views a consumer reads, which the document already covers under "One base,
  many views".
- Scaffolding, in neither tier: `walk_`, which reifies the Java pipeline's answer so a derivation can
  be diffed against it during migration.

The tier is decided by what a relation's rows are computed *from*, not by what computes them. A
materialized derivation whose producer is a Java program is tier two if its inputs are captured facts;
the document already accepts materialization where a view cannot serve. Stating this in the same
breath as the assignment matters, because "`graphitron_` is a derivation" otherwise reads as a demand
that a thousand lines of decoding become SQL, which is not what the tier claims.

The corollary worth writing down: the 48 foreign keys from `graphitron_` into `graphql_` are a
derivation's edges to its inputs. They are correct and permanent, and they are not an argument for or
against separating the two families.

## Implementation

Prose and naming only, one commit is fine. Anchors below are symbols and quoted prose; re-find by
search, never by line number. Where the tiers already have names in the tree, use those rather than
minting new ones; this item is not a renaming of relations.

### `fact-model.adoc`: the new tier section

A new `== ` section placed immediately ahead of "Derived reads are views, not stored facts", since
that is the discipline it generalises. It carries:

- The tier statement (capture transcribes facts from a corpus; derivation computes further facts
  from captured ones; queries read facts to serve a goal) and the recompute test, stated once as
  the decision procedure: a row that can be recomputed from captured facts alone is a derived fact
  and must not be captured. Note that the test decides mechanically what the derived-reads section
  argues case by case.
- The family assignment from "Which tier each family is in" above, including the two verdict
  residents in tier one, `graphitron_` in tier two, and `walk_` as scaffolding in neither tier.
- The clarification that a relation's tier is decided by what its rows are a function of, not by
  what program computes them: a materialization whose producer is a Java walk is tier two if its
  inputs are captured facts. The sentence later in the same document that currently says the
  stratum "is decided by what produces its rows" (the `intent_class_member_slot` paragraph) is
  reworded to this same test in the same edit, keeping its walk-versus-rule contrast as the
  illustration, so the two sections state one rule instead of two.
- The corollary that the foreign keys from `graphitron_` into `graphql_` are a derivation's edges
  to its inputs, correct and permanent, and no argument for or against separating the families.
  State it without the count (48 today); an unguarded census rots silently.
- The enforcer line the page's preamble demands. The live gate for the decode half is
  `FactSchemaGateTest.theDecodeDoesNotReplaceTheTranscription`, the verbatim-transcription twins
  already cited in the executable-form section: a tier-two decode may not displace the tier-one
  transcription. Name it, and state the gap precisely: the gate covers a decode that adds rows
  beside the transcription, and does not cover a macro that rewrites `graphql_field.type_sdl` in
  place, which is exactly the inverted case the macro paragraph names. Do not cite
  `FactCaptureAgreementTest`'s registration arms as the tier's reflection: they file `graphitron_`
  under the containment arm beside `graphql_`, because the arms answer how a relation's contents
  are pinned, not what its rows are computed from.

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
  relations" files a tier-two decode under transcription. Correct to the decode being a derivation
  whose producer runs at capture cadence; the paragraph's own closing sentence ("Two derivations
  materialize at capture cadence inside the same transaction") already has the vocabulary, so this
  is one clause.

### `MacroCapture` and `SdlFactCapture`

- `MacroCapture`'s class javadoc drops "keeps the store's picture effective rather than authored"
  and states the tier honestly: macro expansion is a derivation running inside the capture walk
  (the sibling items move it), writing through capture's doors with provenance rows. Apply the
  added-versus-rewritten split from above rather than the blanket anti-join claim. Keep the
  agreement-suite pinning sentence and the "nothing here rejects" paragraph.
- `MacroCapture.effectiveFieldType`'s javadoc: same treatment.
- Rename `effectiveFieldType` to `expandedFieldType` (package-private, one call site, in
  `SdlFactCapture.captureFields`). The Retired vocabulary section declines to rename
  `authored_type_sdl` and the `intent_authored_*` views because the tier reading predicts they
  become unnecessary; the same prediction deletes this method, so lifetime does not discriminate.
  Cost and reach do: the method rename costs two lines and reaches no consumer, where a column
  rename costs a DDL edit and every reader of it. Rename the method, leave the relations.
- The inline comment above that call site ("The effective type, not the authored one ... the
  store's picture is the schema consumers see and the authored one is the anti-join") carries the
  retired claim more explicitly than the method name does, and its anti-join half is false at this
  very site: the rewritten row is in no anti-join. Rewrite it.

### The `graphitron_field_synthesis` table comment

`COMMENT ON TABLE graphitron_field_synthesis` says "the authored expression survives here while
the field's `graphql_field` row holds the effective one". DDL comments render into the published
schema reference, so this is the highest-visibility carrier of the retired contrast. Rewrite it in
tier vocabulary (the written expression beside the expansion's result), leaving the column name
untouched per Out of scope. A comment edit is not a relation change.

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
  type read is the one the author wrote" is already tier vocabulary and stays.
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

## Sweep fences

The Retired vocabulary section below defines the Done-gate greps. Three families of hits are a
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
  The retirement is scoped to authored-versus-synthesized as names for the store's contents.

No `RetiredVocabularyGuardTest.PHRASE_REGISTRY` entry now: the registry's own bar is a term that
survives a sweep, and this item is the first sweep. Registration is the escalation if the phrase
recurs.

## Verification

- `mvn install -Plocal-db`: the docs render, the javadoc reference gate over the edited javadoc,
  `RoadmapReferenceGuardTest` over the edited prose, and the full suite over the
  `effectiveFieldType` rename.
- Pre-run the Retired vocabulary greps before requesting In Review; the fences above say what a
  hit that stays looks like.

## Retired vocabulary

- "effective rather than authored", "the authored picture", "the effective picture", "the effective
  schema" as names for a tier or for the store's contents.
- "authored form" / "authored type" / "authored named type" / "authored type expression" /
  "authored expression", and a bare "the authored one" contrasted with an expansion's result, as
  names for the pre-expansion fact. The captured fact needs no qualifier; the derived one is named
  by its derivation.

The grep is the word `authored` across the DDL and the two `.adoc` pages, not the fixed spellings
above: the carriers vary the noun, and three of the four `intent_` sites named in Implementation
match none of the fixed phrases. The fences below are what makes the wider grep readable.

Deliberately *not* retired here, because they are relation and column names with their own scope: the
three `intent_authored_*` views, and `graphitron_field_synthesis.authored_type_sdl`. The tier reading
predicts all four become unnecessary rather than merely misnamed, so renaming them now would be work
thrown away. They are listed in the sibling items that would retire them.

## Out of scope

- Every code move. Nothing about capture, derivation or any relation changes here.
- Renaming or retiring the `intent_authored_*` views, the three synthesis relations, or
  `authored_type_sdl`.
- The `CONNECTION` macro's tier correction, which is the change that would actually delete
  `authored_type_sdl`.
