---
id: R612
title: "The schema scan and its freshness replay share one typed recipe"
status: Spec
bucket: architecture
priority: 3
theme: classification-model
depends-on: [graph-partition-key-dimension]
created: 2026-08-08
last-updated: 2026-08-08
---

# The schema scan and its freshness replay share one typed recipe

R610 transcribes one slice of resolved Maven configuration into the store (the
`store_graph_schema_input` recipe rows, the effective-extension child, and the build identity
on `store_graph`) so a freshness check can replay a graph's schema-file expansion without
building its module. That leaves two code paths honoring one contract: the build's own scan
runs `SchemaInputExpander.expand` in the plugin, while the checker replays the transcribed
recipe beside it, and R610 argues their fidelity to each other instead of having it by
construction. This item closes that gap. The fidelity win decomposes into two claims, and each
gets its own mechanism: both paths run the *same glob semantics* (bought by one expander
component, moved where both can reach it), and both paths run over the *same input value*
(bought by one typed recipe that the mojo decodes into, the transcription round-trips through
rows, and an agreement anchor holds to identity). The Backlog stub sketched a third shape,
routing the build's own scan through the store rows; it is rejected below, on the record.

## One recipe type, one expander

A core `ScanRecipe` value is the single carrier of resolved scan configuration: the ordered
entries (each a glob pattern or a literal source, with its optional tag and description note),
the effective schema-file-extension filter, and the base directory. The mojo decodes
`<schemaInputs>`, `<schemaFileExtensions>` and the project basedir into it inside
`AbstractRewriteMojo.buildContext`, exactly where `SchemaInputExpander.expand` runs today. The
plexus-bound `SchemaInputBinding` bean does not cross into core; the decode at the mojo
boundary is the same move `AbstractRewriteMojo.decodeDependencyVersions` already makes for
Maven `Artifact`, and for the same containment reason.

The expander moves from the plugin into the `graphitron` core module and takes a `ScanRecipe`
instead of binding beans. Its callers become: the build mojos (as today, before
`RewriteContext` construction), the dev goal's per-regeneration re-expansion (each pass
rebuilds the context through `buildContext`, so it inherits the move), and R610's freshness
replay, which decodes a sibling graph's recipe rows back into a `ScanRecipe` and calls the
same component. Capture transcribes the run's `ScanRecipe` into R610's recipe relations,
adopted in place with no rekey; `RewriteContext` carries the recipe beside the expanded
`schemaInputs` list it already carries, both produced by the one seam in `buildContext`, so
the pair cannot disagree at the producer. Programmatic construction sites do not gain a
parameter: a caller that hands a literal `SchemaInput` list gets its recipe derived from that
list (each element one literal entry), so all 55 `RewriteContext` construction sites compile
untouched.

The move reverses a recorded decision, and the reversal is argued the way R610 argues its
mixed-mode one. `SchemaInputExpander`'s class javadoc says the expansion lives in the plugin
"so rewrite-core stays filesystem-agnostic", and that comment is rewritten with the move
rather than left standing against the code. The argument for reversal: the manual page
`docs/manual/reference/mojo-configuration.adoc` promises consumers Ant-style glob semantics,
so the pattern language is a published contract, and the freshness replay must reproduce it
exactly; reimplementing it over `java.nio.file.PathMatcher` would be a hand-maintained copy of
someone else's documented behaviour, which is the drift smell at library scale. So core takes
the `plexus-utils` dependency (already pinned in the plugin pom) for `DirectoryScanner`. The
cost is named rather than inherited: `graphitron` is a published artifact whose compile scope
`graphitron-lsp`, `graphitron-mcp` and the plugin all inherit, so one small stable
Maven-ecosystem jar arrives on that surface. The extension post-filter
(`SchemaInputExpander.matchesExtension` today, duplicated line for line in
`SchemaProblemDiagnostic`) moves with the expander and exists once; the orphan scan keeps its
own walk but calls the shared predicate.

## The store-first shape is rejected on the record

The stub's sketch had the run write config rows first and the scanner read them back, making
the store the channel between two components of one run. Three arguments retire it. First, it
is an untyped channel: the mojo holds a typed value and would hand the scanner a bag of
`VARCHAR`s to re-normalise on the way out (the expander already collapses empty tag and note
strings to absent, and a row decode has to reproduce that exactly), an encode/decode pair at a
boundary with no cross-process reason to exist. Second, it makes the honesty check vacuous:
under read-your-own-writes the pipeline's inputs are derived from the rows, so "the rows agree
with what the pipeline read" is true by construction, and a transcription bug becomes a build
that consistently reads the wrong thing past an anchor that passes. Third, it puts the store
on the critical path of parsing a schema: no schema file could be located unless an H2 handle
opens and a transaction commits, which either drags store boot into every unit-tier
construction site or forces a second non-store expansion path for the fallback, the very code
path this item exists to delete. Under the typed-recipe shape all three dissolve: nothing
reads the rows before capture writes them, the store stays a shadow on the build path, and the
transcription is verified by an enforcer instead of by reordering the run.

## Literal recipes make the replay uniform

Every run transcribes its recipe, not just Maven runs. A pattern entry records the glob; a
literal entry records the resolved source name a programmatic caller handed over via
`SchemaInput.plain` or the canonical constructor. The recipe relation carries the two entry
kinds as a discriminated single relation (a `kind` column under a CHECK constraint) rather
than two relations, because the ordinal is the recipe's spine and splitting the relations
would shatter the one ordering key. Re-expansion of a literal entry is identity plus an
existence check, so R610's currency verdict covers programmatic graphs with no special case:
a literal source that no longer resolves is a lost match, exactly as a pattern whose file set
shrank. Sources that never resolve to a file (the bundled `directives.graphqls` resource name,
bare programmatic labels) are skipped by the replay per R610's rule, and their literal rows
record that they were inputs all the same. Literal entries bypass the extension filter, as the
literal list does today.

## The source carrier is sealed

`SchemaInput.sourceName` is a raw string that is an absolute normalised path on the Maven
path, a bundled resource name for `directives.graphqls`, and an arbitrary label from
`SchemaInput.plain`. R610 has capture asking "does this resolve to a regular file" at stamp
time and the freshness reader asking it again at read time, the same predicate over the same
untyped string at two sites nothing binds together. This item owns the producer, so it takes
the lift while it is cheap: the source is a sealed carrier with a file arm (carrying a `Path`)
and a named arm (carrying the label), decided once where the source enters the system (the
expander mints file arms from real matches; `SchemaInput.plain` resolves its argument once and
picks the arm). Capture stamps the file arm and skips the named arm by exhaustive switch
rather than by predicate, the freshness reader likewise, and a new source kind is a compile
error at both. The edit is compiler-led through `sourceName`'s consumers
(`SchemaInputAttribution`, `RewriteSchemaLoader`, `DevMojo.resolveSchemaRoots`, capture).

## Rejection is typed at the new boundary

`MojoExecutionException` cannot cross into core, so the move forces the failure path to be
redesigned, and the redesign follows `docs/architecture/explanation/typed-rejection.adoc`
rather than porting the current shape (a result bag with a warning list plus an exception
whose message is composed at the detection site). The core expander returns a sealed result:
resolved sources beside per-pattern empty-match observations, or an
every-pattern-matched-nothing variant, each a typed fact. The mojo renders the aggregate-empty
variant as the build failure and the per-pattern observations as warnings, preserving today's
author-facing text; the dev goal, the LSP and the freshness driver render the same variants
for their own surfaces instead of re-composing prose.

## The rows stay store_, and the doctrine widens one clause

The recipe rows keep the `store_` prefix, adopting R610's relations in place. The DDL header's
family-naming rule rejects the alternatives by its own words: `config_` is a role name, the
same objection that retired `extension_`, and `maven_` is a vocabulary name that is false for
exactly the literal rows above, which no build tool spelled. But the current `store_` doctrine
sentence ("the store's own record of what it read and what it was built from, the one family
whose rows are not a transcription of anything outside") is falsified by the recipe on both
halves, so the sentence widens by a clause rather than being left to contradict the rows: the
store's own record of what it was told to read, what it read, and what it was built from. The
recipe is the store's record of its instructions; naming it for the tool that supplied them
would fragment the family the first time a caller with no pom writes the same shape.

The read doctrine the stub's third fork asked for, stated here as the config rows' contract: a
run reading its own graph's recipe is an ordinary same-graph read; a reader of *another*
graph's recipe is maintenance machinery under R610's carve-out, and counts as maintenance
exactly while it writes no conclusions anywhere but the `store_` family. The recipe rows never
join the cross-graph consumer read surface, which stays SDL-derived families only.

## Deliberately out of scope

- **Transcribing configuration with no reader.** `<lint>`, `<sessionState>`,
  `<tenantColumn>`, output packages and directories stay untranscribed; the first-reader
  principle cuts the recipe's extent to what the scan and the replay read.
- **Folding the orphan scan onto the recipe component.** `SchemaProblemDiagnostic`'s walk
  answers the recipe's complement (schema-shaped files the recipe did not pick up) and could
  one day be a query over the recipe, but it has no fact-model payoff today; it stays a
  plugin-side walk that now calls the shared extension predicate instead of carrying its own
  copy.
- **The freshness loop's driver.** This item makes the replay's expander exist; where the
  loop runs from stays with R610's orchestration successor.
- **Any consumer-facing read surface over config rows**, per the read doctrine above.

## Verification

Full `mvn install -Plocal-db` green. The round-trip anchor is the item's enforcer and is
non-vacuous by construction: the run's recipe rows, decoded back into a `ScanRecipe` and
re-expanded by the shared expander, reproduce the run's `RewriteContext.schemaInputs` exactly,
two independent derivations (mojo-resolved value against row round-trip) meeting in one
equality, in the same tier as `FactCaptureAgreementTest`. A programmatic run's literal rows
re-expand to its literal list through the same anchor. `SchemaInputExpanderTest` retargets to
the moved core component with its cases intact, and the mojo-side rendering of the
aggregate-empty and per-pattern-empty variants pins today's author-facing text. The sealed
carrier edit is compiler-led and lands with R610's stamp round-trip case unchanged. No
user-visible configuration surface changes (`mojo-configuration.adoc` already documents the
glob semantics this item preserves), so the first-client docs check is exempt.
