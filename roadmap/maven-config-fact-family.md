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

## Open from the gate: R610 has absorbed the move

This item was written against an R610 that left the expander in the plugin. R610's spec
revision no longer does, and the overlap has to be resolved before this item is implementable
as written; it is recorded here rather than patched in place, because what remains of R612 is a
scope decision its author should make, not a reviewer's edit.

R610 now puts a `SchemaRecipe.expand(baseDir, patterns, extensions)` primitive in `graphitron`
owning the walk and the extension filter, has `SchemaInputExpander` delegate to it while
keeping the Maven-shaped work (reading `SchemaInputBinding`, the empty-pattern diagnostics, the
`MojoExecutionException`), moves plexus-utils onto `graphitron` with its version into the root
pom's properties, and rewrites the filesystem-agnostic javadoc as its own recorded reversal.
That is most of the section below, claimed by the item this one depends on and therefore
landing first.

Three consequences the next pass owes an answer to. **What is left** looks like the typed value
and the seams around it rather than the move: `ScanRecipe` replacing R610's loose
`(baseDir, patterns, extensions)` parameter triple, the decode at `buildContext`, the sealed
source carrier, the round-trip anchor, and the orphan scan's call onto the shared predicate.
The move's own paragraphs, the plexus cost, and the javadoc reversal should be struck and cited
to R610 rather than re-argued here. **The names collide**: `ScanRecipe` here against
`SchemaRecipe` there, one concept across a dependency edge, and the successor keeps one name.
**The rejection section loses its premise.** "`MojoExecutionException` cannot cross into core,
so the move forces the failure path to be redesigned" is no longer true, because R610's split
keeps the exception plugin-side by construction. The typed-rejection redesign may well still be
worth doing, but it is now a choice this item argues for on its merits, not a consequence it
inherits.

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
Maven-ecosystem jar arrives on that surface. Its version is pinned inline in the plugin pom
today; arriving on a published module's compile scope is the moment to move that pin into the
root pom's `dependencyManagement` with the rest. The extension post-filter
(`SchemaInputExpander.matchesExtension` today, near-duplicated in `SchemaProblemDiagnostic`)
moves with the expander and exists once. The two bodies differ only in what they are handed:
the expander's takes a scanner-relative path and strips the directory prefix first, the
diagnostic's takes a bare filename because its caller already called `getFileName`. The shared
predicate takes the filename, the narrower contract of the two, and the expander does its own
stripping at the call site; the orphan scan keeps its own walk and calls the predicate
unchanged.

The decode seam is cut with one eye on a direction the roadmap does not yet own: R610's
per-user store and this item's core recipe leave graphitron a short step from running as a
standalone workspace process, with everything build-tool-shaped about the scan living in one
decoder. A non-Maven entry point is then a second decoder into the same `ScanRecipe`, never a
second expansion path, and the extension defaulting it would need already lives in core
(`RewriteContext.DEFAULT_SCHEMA_FILE_EXTENSIONS`). No such decoder ships here, by the same
first-reader principle that cuts the recipe's extent; what the direction is owed is only that
core assumes no Maven vocabulary, which the containment rule above already binds.

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
shrank. A literal source that never resolves to a file at all (a bare programmatic label, of
which the applier tests carry several) is skipped by the replay per R610's rule, and its
literal row records that it was an input all the same. Literal entries bypass the extension
filter, as the literal list does today. The bundled `directives.graphqls` is not a recipe
entry under any of this and needs no carve-out: `RewriteSchemaLoader` hands that resource to
the parser directly rather than through a `SchemaInput`, so it never reaches the expander, is
not configuration any caller supplied, and is already excluded from the reader surface
downstream (`CatalogBuilder` filters locations bearing
`RewriteSchemaLoader.DIRECTIVES_SOURCE_NAME`).

## The source carrier is sealed

`SchemaInput.sourceName` is a raw string that is an absolute normalised path on the Maven path
and an arbitrary label anywhere else, since `SchemaInput.plain` and the canonical constructor
take whatever a programmatic caller hands them (the applier tests pass a bare `t.graphqls`, a
`/a`). R610 has capture asking "does this resolve to a regular file" at stamp time and the
freshness reader asking it again at read time, the same predicate over the same untyped string
at two sites nothing binds together. This item owns the producer, so it takes the lift while it
is cheap: the source is a sealed carrier with a file arm (carrying a `Path`) and a named arm
(carrying the label), decided once where the source enters the system (the expander mints file
arms from real matches; `SchemaInput.plain` resolves its argument once and picks the arm).
Capture stamps the file arm and skips the named arm by exhaustive switch rather than by
predicate, the freshness reader likewise, and a new source kind is a compile error at both.

One invariant is load-bearing and the compiler cannot hold it, so it is stated here and given
an enforcer below. `SchemaInput`'s javadoc records that the source name is what
`RewriteSchemaLoader` hands the parser and what comes back as graphql-java's
`SourceLocation.getSourceName()`, so `SchemaInputAttribution`'s map matches byte-for-byte
without renormalisation; `ValidationReport.canonicalUri` and the LSP's URI equality read the
same returned string. Putting a carrier in front of that inserts a rendering step on a round
trip that leaves Java's type system, so the carrier renders exactly one canonical source-name
string, used both at the parser handoff and at every lookup keyed on it: the file arm renders
its `Path` the way the expander composes the string today (resolved against the basedir, made
absolute, normalised), and the named arm renders its label verbatim. A divergence of one
character costs no compile error and no parse failure; it silently stops tags and description
notes from being applied, which is why the enforcer is an end-to-end attribution case rather
than an equality on the carrier. The rest of the edit is genuinely compiler-led, through
`sourceName`'s typed consumers: `SchemaInputAttribution`, `RewriteSchemaLoader`,
`DevMojo.resolveSchemaRoots`, `AbstractRewriteMojo`'s projection into
`SchemaProblemDiagnostic`, and capture.

## Rejection is typed at the new boundary

`MojoExecutionException` cannot cross into core, so the move forces the failure path to be
redesigned, and the redesign follows `docs/architecture/explanation/typed-rejection.adoc`
rather than porting the current shape (a result bag with a warning list plus an exception
whose message is composed at the detection site). The core expander returns a sealed result:
resolved sources beside per-pattern empty-match observations, or an
every-pattern-matched-nothing variant, each a typed fact. The mojo renders the aggregate-empty
variant as the build failure and the per-pattern observations as warnings, preserving today's
author-facing text; the dev goal and the freshness driver render the same variants for their
own surfaces instead of re-composing prose. The LSP is deliberately not on that list: it boots
inside the dev goal's codegen scope, after `buildContext` has already failed or succeeded, so
it has no expansion-failure surface to render and gains none here.

The seal is standalone, not a permit on `Rejection`, and that is a decision rather than an
omission. `Rejection` is the classifier's vocabulary: its arms describe what an author's SDL
said and its consumers are the validator and the LSP fix-its. An unmatched `<schemaInputs>`
pattern is decided before any SDL is parsed, over configuration rather than over a document, so
it has no site to attach to and nothing to fix-it. Staying outside the hierarchy is therefore
the honest modelling and also the cheap one: none of `Rejection`'s registration obligations
attach, so this item owes no paragraph under `SealedHierarchyDocCoverageTest`, no `lspCode()`,
and no `Diagnostics.lspCodeOf` arm. What it takes from
`docs/architecture/explanation/typed-rejection.adoc` is the shape the page argues for, a sealed
result whose failure arms carry structural data instead of a message composed at the detection
site, which is exactly what the current `MojoExecutionException` does wrong.

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
re-expand to its literal list through the same anchor.

The sealed carrier's rendering invariant gets the second enforcer, and it has to be end-to-end
because that is the only altitude at which a divergence shows up. The case expands a temp tree
through the moved expander with a tag and a description note configured, runs the resulting
inputs through the load and the attribution appliers, and asserts the tag and the note landed
on the elements the source declared. That is only possible once the expander is in core, which
is why this item owes it and R610 could not: it closes the loop from a minted file arm, through
the parser, to a lookup keyed on what comes back, so any rendering divergence fails it. The
existing applier tests hold the named arm's half of the same invariant already, passing labels
that are not paths.

`SchemaInputExpanderTest` retargets to the moved core component with its cases intact, and the
mojo-side rendering of the aggregate-empty and per-pattern-empty variants pins today's
author-facing text. The rest of the carrier edit is compiler-led and lands with R610's stamp
round-trip case unchanged. No user-visible configuration surface changes
(`mojo-configuration.adoc` already documents the glob semantics this item preserves), so the
first-client docs check is exempt.
