---
id: R712
title: "Name the three tiers, and retire authored versus effective"
status: Backlog
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
`graphql_field`, and a view now recovers it with nested `REPLACE` calls stripping `[`, `]` and `!`. A
captured fact is being reconstructed by string surgery.

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

## Shape

- `fact-model.adoc` gains a short section naming the three tiers and the recompute test, placed ahead
  of the derived-reads section it generalises, and its macro paragraph is rewritten to keep its
  argument and correct its conclusion.
- `pipeline-overview.adoc`'s sentence is corrected to describe what capture actually does today, in
  tier vocabulary, so the two documents stop disagreeing.
- `MacroCapture`'s javadoc drops the authored/effective defence and states its tier honestly: an
  expansion running inside capture, which the sibling items move.
- Where the tiers already have names in the tree, use those rather than minting new ones. The
  transcription families, the derived stratum and the consumer views are all named in the document
  already; this item is not a renaming of relations.

## Retired vocabulary

- "effective rather than authored", "the authored picture", "the effective picture", "the effective
  schema" as names for a tier or for the store's contents.
- "authored form" / "authored type" as a contrast with a synthesized one. The captured fact needs no
  qualifier; the derived one is named by its derivation.

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
