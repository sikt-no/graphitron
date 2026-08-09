---
id: R612
title: "The schema scan and its freshness replay share one typed recipe"
status: Spec
bucket: architecture
priority: 3
theme: classification-model
depends-on: [graph-partition-key-dimension]
created: 2026-08-08
last-updated: 2026-08-09
---

# The schema scan and its freshness replay share one typed recipe

R610 transcribes one slice of resolved Maven configuration into the store (the
`store_graph_schema_input` recipe rows, the effective-extension child, and the build identity
on `store_graph`) so a freshness check can replay a graph's schema-file expansion without
building its module. Fidelity between the build's own scan and that replay decomposes into two
claims. The first, both paths run the *same glob semantics*, is bought by R610 itself: it moves
the walk and the extension filter into a core `SchemaRecipe.expand` primitive, takes
plexus-utils onto `graphitron` with the version pin, and records the reversal of the
filesystem-agnostic javadoc, all argued there and landing first. This item buys the second
claim: both paths run over the *same input value*. The typed recipe is decoded once at the mojo
boundary, carried on the context, transcribed by capture into R610's rows, decoded back by the
replay, and held to identity by a round-trip anchor; the source value it produces is sealed so
capture and the replay switch on source kind instead of re-asking a filesystem predicate at two
unbound sites. The Backlog stub sketched a third shape, routing the build's own scan through
the store rows; it is rejected below, on the record.

## Open: R610 landed more of this than R610's spec described

The second Spec to Ready pass ran after R610's implementation reached trunk, and the overlap the
first pass recorded has recurred and grown. The section below was written against R610's spec
text, which promised a static `SchemaRecipe.expand(baseDir, patterns, extensions)` primitive.
What R610 actually landed is most of "One recipe value, both paths": `SchemaRecipe` is already a
record (`Path buildFile`, `List<Binding> bindings` carrying pattern plus optional tag and note in
configuration order, `List<String> extensions`) with an instance `expand(Path baseDir)`; the
decode already happens at the mojo boundary in `AbstractRewriteMojo.buildSchemaRecipe`, called
from the `buildContext` path; the recipe already rides `RewriteContext` as a component; capture
already transcribes it (`FactCapture.writeRecipe`, `FactCapture.GraphIdentity`); and the replay
already decodes it back and re-expands (the `WarmStartRefreshTest` cases construct recipes
directly). Rewriting the section is the author's scope call, not a reviewer's edit, so the
residue and the new questions are recorded here rather than patched in place.

What is left, and is still worth the item:

- **The duplication is now concrete rather than preventive.** `RewriteContext` carries
  `schemaFileExtensions`, `basedir` *and* `schemaRecipe`, whose `extensions` is the same set,
  which is exactly the "same fact asserted twice with nothing binding the copies" the section
  argues against. The accessor collapse below is a fix to landed code.
- **The sealed source carrier**, `SchemaInput.plain`'s retirement, and the two probes it deletes.
  Both probes are live as landed: `SdlFactCapture.regularFile` is the stamp-time one and
  `StoreRefresh`'s `Files.isRegularFile` is the read-time twin.
- **Literal entries and the `kind` column**, so programmatic runs transcribe at all.
  `SchemaRecipe.Binding.pattern` is a bare `String` today and `buildFile` is null on a
  programmatic run, so the widening still has its target.
- **The shared extension predicate**, whose duplicate pair has moved: it is now the private
  `SchemaRecipe.matchesExtension` against `SchemaProblemDiagnostic.matchesExtension`.
  `SchemaInputExpander.matchesExtension`, named below, no longer exists.
- **The typed rejection** and **the round-trip anchor**.

Three questions the next pass owes an answer to:

- `SchemaRecipe.buildFile` is new, nullable, and Maven-shaped (the module's pom). The
  containment rule below says core assumes no Maven vocabulary, and the sealed-carrier section
  rejects null-shaped defaults on exactly this reasoning. Whether it stays a component, becomes
  an arm, or moves is undecided here.
- The landed instance `expand(Path baseDir)` returns a deduplicated `Set<Path>`: no per-binding
  grouping, no tag or note attribution, no configuration order. The typed result this item wants
  needs all three, so it replaces that method rather than taking "R610's walk and dialect as the
  body", and the per-pattern static is what `SchemaInputExpander` still uses to keep its
  per-binding empty diagnostics.
- The Retired vocabulary entry misdescribes the landed static, which takes one pattern rather
  than a pattern list. Whether it is retired at all depends on the previous question.

## One recipe value, both paths

`SchemaRecipe` graduates from R610's primitive into the typed value, and one name survives the
dependency edge; the stub's `ScanRecipe` is dropped. R610 lands a static
`SchemaRecipe.expand(baseDir, patterns, extensions)` owning the walk and the extension filter;
that loose triple is R610's provisional shape, sanctioned there as this item's "compatible
first slice", and this item retires it. `SchemaRecipe` becomes the record carrying the ordered
entries (each a glob pattern or a literal source, with its optional tag and description note),
the effective schema-file-extension filter, and the base directory; expansion becomes its
instance method, returning the sealed result argued below, with R610's walk and dialect as the
body. R610's re-expansion case, which its Verification writes against the static signature,
retargets to the value in the same edit.

The mojo decodes `<schemaInputs>`, `<schemaFileExtensions>` and the project basedir into the
record inside `AbstractRewriteMojo.buildContext`, exactly where `SchemaInputExpander.expand`
runs today. The plexus-bound `SchemaInputBinding` bean does not cross into core; the decode at
the mojo boundary is the same move `AbstractRewriteMojo.decodeDependencyVersions` already
makes for Maven `Artifact`, and for the same containment reason. What R610 leaves of
`SchemaInputExpander` (the binding read, the empty-pattern diagnostics, the
`MojoExecutionException`) dissolves here: the binding read becomes the decode into
`SchemaRecipe`, and the failure prose becomes the mojo-side rendering of the typed result.
Whether a named decoder class remains or the decode inlines into `buildContext` is the
implementer's judgment. The expansion's callers become: the build mojos (as today, before
`RewriteContext` construction), the dev goal's per-regeneration re-expansion (each pass
rebuilds the context through `buildContext`, so it inherits the seam), and R610's freshness
replay, which decodes a sibling graph's recipe rows back into a `SchemaRecipe` and runs the
same expansion. Capture transcribes the run's recipe into R610's relations, adopted in place
with no rekey.

The recipe rides on `RewriteContext` as the single source of two facts the record carries
today, rather than beside them. A recipe holding its own base directory and extension set next
to the context's existing `basedir` and `schemaFileExtensions` components would be the same
fact asserted twice with nothing binding the copies, the disagreement-at-the-producer this
item exists to abolish, moved up one level. So `basedir` and `schemaFileExtensions` stop being
record components and become accessors reading the recipe; the fact is asserted once and there
is nothing to hold in agreement. The convenience overloads keep their signatures verbatim and
mint a literal recipe internally (each `SchemaInput` one literal entry, extensions defaulted
as today), so their callers compile untouched, and a derived literal recipe cannot disagree
with the list it was derived from. The canonical constructor takes the recipe; the two sites
that pass an extension set explicitly (`buildContext` and `CatalogBuilderSourceTest`) move onto
it, and the five `with*` copy methods follow the compiler onto the same component. Read sites
keep compiling through the accessors. `RewriteContext`
carries the recipe beside the expanded `schemaInputs` list, both produced by the one seam in
`buildContext`, so the pair cannot disagree at the producer.

The extension filter lands in core with R610's move; what this item adds is that the predicate
exists once. `SchemaInputExpander.matchesExtension` is near-duplicated in
`SchemaProblemDiagnostic`, and the two bodies differ only in what they are handed: the
expander's takes a scanner-relative path and strips the directory prefix first, the
diagnostic's takes a bare filename because its caller already called `getFileName`. The shared
predicate takes the filename, the narrower contract of the two, and the expansion does its own
stripping at the call site; the orphan scan keeps its own walk and calls the predicate
unchanged.

The decode seam is cut with one eye on a direction the roadmap does not yet own: R610's
per-user store and this item's core recipe leave graphitron a short step from running as a
standalone workspace process, with everything build-tool-shaped about the scan living in one
decoder. A non-Maven entry point is then a second decoder into the same `SchemaRecipe`, never
a second expansion path, and the extension defaulting it would need already lives in core
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
literal entry records the canonical source-name rendering of the `SchemaInput` a programmatic
caller handed over. The recipe relation is one discriminated relation rather than several,
because the ordinal is the recipe's spine and splitting the relations would shatter the one
ordering key; its `kind` column takes three values under a CHECK constraint (R610's DDL
carries a `pattern` column, and adopting the relation generalises that to `kind` plus a value
column, still keyed `(graph_name, ordinal)` with no rekey). `pattern` records a glob; the two
literal kinds transcribe which door the entry's source came through (the sealed carrier
below): a `file` literal re-expands by identity plus an existence check, so R610's currency
verdict covers programmatic graphs with no special case, a file literal that no longer
resolves being a lost match exactly as a pattern whose file set shrank; a `named` literal (a
bare programmatic label, of which the applier tests carry several) is skipped by the replay
by its kind, and its row records that it was an input all the same. The replay recovers the
arm from the row instead of re-asking a filesystem question about a stored string. Literal
entries bypass the extension filter, as the literal list does today. The bundled
`directives.graphqls` is not a recipe entry under any of this and needs no carve-out:
`RewriteSchemaLoader` hands that resource to the parser directly rather than through a
`SchemaInput`, so it never reaches the expansion, is
not configuration any caller supplied, and is already excluded from the reader surface
downstream (`CatalogBuilder` filters locations bearing
`RewriteSchemaLoader.DIRECTIVES_SOURCE_NAME`).

## The source carrier is sealed

`SchemaInput.sourceName` is a raw string that is an absolute normalised path on the Maven path
and an arbitrary label anywhere else, since `SchemaInput.plain` and the canonical constructor
take whatever a programmatic caller hands them (the applier tests pass a bare `t.graphqls`, a
`/a`). R610 has capture asking "does this resolve to a regular file" at stamp time and the
freshness reader asking it again at read time, the same predicate over the same untyped string
at two sites nothing binds together. This item owns the producer, so it takes the lift while
it is cheap: the source is a sealed carrier with a file arm (carrying a `Path`) and a named
arm (carrying the label), decided once where the source enters the system, and a new source
kind is a compile error at every consumer that switches on it.

Who decides the arm: the producer that knows, never a guess. The expansion mints file arms,
because a `DirectoryScanner` match is a regular file by construction, and its result carries
that in its type rather than as a fact every consumer re-establishes. Programmatic callers
choose a door, `SchemaInput.file(Path)` or `SchemaInput.named(String)`, and the canonical
constructor takes the sealed source, so there is no String-shaped default door to fall
through: `SchemaInput.plain` retires, and every construction site states its claim. The
alternative, a construction-time filesystem probe that resolves the string once and picks the
arm, is rejected because it puts environment-dependent classification inside a value type: the
arm would depend on the working directory for a relative argument and on filesystem state at
construction time, so identical configuration could classify differently across two runs. The
migration this costs is mechanical and compiler-led, almost entirely in tests (the applier
tests construct `SchemaInput` directly and become `named`; the capture-facing fixtures such as
`CapturedStore.ofPipeline` pass real temp files and become `file`). The `file` door is not
optional fidelity for those fixtures: every input that reaches a pipeline run is necessarily
a file, since `RewriteSchemaLoader` refuses a source name that is not an existing file, so a
pipeline-feeding site holding a real path and minting `named` would carry a value whose type
denies a precondition the run enforces anyway. A site that genuinely wants a label keeps one,
visibly, and stays outside freshness coverage by its own declaration.

The arm does not reach every consumer as a field, and the one that has to recover it is named
here rather than discovered during implementation. `SdlFactCapture.captureSources` collects
source names back out of graphql-java's `SourceLocation`s, not out of `SchemaInput`, so
capture's stamp decision recovers the arm through a lookup keyed on the canonical rendering
(the attribution map the run already built, threaded into `SdlFactCapture.capture` as a
parameter, since the walk holds no `SchemaInput` data today),
switching exhaustively on what it finds; the
lookup's one legitimate miss is `RewriteSchemaLoader.DIRECTIVES_SOURCE_NAME`, which appears in
the registry's source set and in no `SchemaInput`, and which the reader surface already
excludes. That is a switch over a lookup, not a filesystem probe, and it makes the rendering
invariant below load-bearing for capture too, not only for attribution. The recipe row's
`kind` and the source arm remain distinct axes even though the literal kinds transcribe the
arm: a pattern entry is one row that expands to many file arms, so neither axis is the other's
transcription.

The carrier pays for itself at the consumers that today re-derive path-ness from the string:
`DevMojo.resolveSchemaRoots` runs `Paths.get(input.sourceName())` and switches to reading the
file arm; `SchemaProblemDiagnostic.normaliseLoaded`'s absolute-or-resolve branch is dead once
the arm carries an absolute normalised `Path`, and is deleted rather than ported; R610's two
probe sites become the stamp-time switch above and the replay's row-kind dispatch.
`RewriteSchemaLoader.load` narrows its parameter from `Collection<String>` to the file arm,
rendering the canonical string internally at the `MultiSourceReader` handoff, and the
generator's projection from `schemaInputs` to loadable sources
(`GraphQLRewriteGenerator.loadAttributedRegistry`, which hands the loader the attribution map's
key set today) becomes an exhaustive switch at that boundary. The switch is checked for
coverage, not for absence, so the named arm's branch is decided here rather than left to the
edit: it throws, keeping today's mid-parse "Schema file not found" `RuntimeException` for a
label that reaches a pipeline run instead of converting it into a silently short schema. No
context in the tree carries a label into that projection, so the branch is a guard rather than
a live path; what the narrowing buys is that the branch has to be written and read, where a
`Collection<String>` parameter let the same value fall through to a parse-time surprise.

One invariant is load-bearing and the compiler cannot hold it, so it is stated here and given
an enforcer below. `SchemaInput`'s javadoc records that the source name is what
`RewriteSchemaLoader` hands the parser and what comes back as graphql-java's
`SourceLocation.getSourceName()`, so `SchemaInputAttribution`'s map matches byte-for-byte
without renormalisation; `ValidationReport.canonicalUri` and the LSP's URI equality read the
same returned string. Putting a carrier in front of that inserts a rendering step on a round
trip that leaves Java's type system, so the carrier renders exactly one canonical source-name
string, used both at the parser handoff and at every lookup keyed on it: the file door
normalises at mint (absolute, normalised, exactly the string the expander composes today from
a scanner match), the file arm renders that `Path`, and the named arm renders its label
verbatim. A divergence of one character costs no compile error and no parse failure; it
silently stops tags and description notes from being applied, and silently unmatches capture's
stamp lookup, which is why the enforcer is an end-to-end attribution case rather than an
equality on the carrier. The rest of the edit is compiler-led, through `sourceName`'s typed
consumers: `SchemaInputAttribution`, `RewriteSchemaLoader`, `DevMojo.resolveSchemaRoots`,
`AbstractRewriteMojo`'s projection into `SchemaProblemDiagnostic`, and capture.

## Rejection is typed at the new boundary

This redesign is argued on its merits, not forced by the move: R610's split already keeps
`MojoExecutionException` plugin-side by construction, so the plugin could keep composing its
failure prose over the core primitive's raw output. The merit is the consumer count. Once
expansion is a core seam, its failure vocabulary has three render surfaces (the build mojos,
the dev goal's regeneration loop, and the freshness replay's driver), and today's shape (a
result bag with a warning list plus an exception whose message is composed at the detection
site) is renderable by exactly one of them; the other two would re-compose prose from
half-structured parts. The redesign follows
`docs/architecture/explanation/typed-rejection.adoc` instead. The core expansion returns a
sealed result: resolved sources beside per-pattern empty-match observations, or an
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
site, which is exactly what the current `MojoExecutionException` path does wrong.

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
- **The freshness loop's driver.** This item makes the replay's expansion exist; where the
  loop runs from stays with R610's orchestration successor.
- **Any consumer-facing read surface over config rows**, per the read doctrine above.

## Verification

Full `mvn install -Plocal-db` green. The round-trip anchor is the item's enforcer: the run's
recipe rows, decoded back into a `SchemaRecipe` and re-expanded by the shared expansion,
reproduce the run's `RewriteContext.schemaInputs` exactly, the context's own value against that
value round-tripped through the rows, in the same tier as `FactCaptureAgreementTest`. Both sides
run the one `expand`, so what the equality pins is transcription fidelity plus glob determinism
rather than two independent expansions, which is exactly the residue a single expansion path
leaves to verify. The tier has no mojo, so the fixture mints the recipe directly and builds the
context from its expansion, the same pairing `buildContext` makes. Non-vacuity is a requirement
on the case, not a property of the
shape: a literal entry re-expands by identity, so the anchor's fixture must include at least
one pattern entry for the equality to test anything. A programmatic run's literal rows
re-expanding to its literal list goes through the same anchor, beside the pattern case rather
than instead of it, and pins something narrower stated honestly: literal re-expansion is
identity, so that half verifies row encode/decode fidelity (the empty-tag collapse, the kind
dispatch), not a second independent derivation.

The sealed carrier's rendering invariant gets the second enforcer, and it has to be end-to-end
because that is the only altitude at which a divergence shows up. The case expands a temp tree
through the core expansion with a tag and a description note configured, runs the resulting
inputs through the load and the attribution appliers, and asserts the tag and the note landed
on the elements the source declared. It closes the loop from a minted file arm, through the
parser, to a lookup keyed on what comes back, so any rendering divergence fails it; it lives
in `graphitron`, which is only possible once the expansion does. The existing applier tests
hold the named arm's half of the same invariant already, passing labels that are not paths.

`SchemaInputExpanderTest` retargets to the core expansion with its cases intact, except the
two that assert `MojoExecutionException` (`singlePatternEmpty_throwsAggregateEmpty` and
`expand_zeroMatchAfterExtensionFilter_throwsMojoExecutionException`), which become mojo-side
rendering pins holding today's author-facing text for the aggregate-empty and
per-pattern-empty variants. No user-visible configuration surface changes
(`mojo-configuration.adoc` already documents the glob semantics this item preserves), so the
first-client docs check is exempt.

## Retired vocabulary

- `SchemaInput.plain`, replaced by the explicit `SchemaInput.file` / `SchemaInput.named`
  doors.
- The static `SchemaRecipe.expand(baseDir, patterns, extensions)` signature over a loose
  parameter triple, R610's provisional shape, replaced by the record's instance expansion.
- `SchemaInputExpander` as a class name, dissolved into the mojo-side decode and the core
  expansion.
