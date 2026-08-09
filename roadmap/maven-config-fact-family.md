---
id: R612
title: "The schema scan and its freshness replay share one typed recipe"
status: Spec
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-08
last-updated: 2026-08-09
---

# The schema scan and its freshness replay share one typed recipe

R610 has shipped, and with it most of the substrate this item was filed to build. The store
holds a graph's SDL recipe (`store_graph_schema_input`, `store_graph_schema_extension`, the
build identity on `store_graph`); `SchemaRecipe` is a core record carrying the resolved
bindings and the effective extension filter, owning the one glob dialect; the mojo decodes the
`<schemaInputs>` bindings into it at `AbstractRewriteMojo.buildSchemaRecipe`; the recipe rides
`RewriteContext` as a component; and capture transcribes it beside the graph. So the first of
the two fidelity claims, that both paths run the *same glob semantics*, is bought and banked.

What is left is the second claim, and it is the one the item was named for: both paths run over
the *same input value*, end to end and in production. Three things stand between the shipped
recipe and that claim. The recipe's entries are glob patterns only, so a programmatic run
records nothing and is not replayable. The row-to-recipe decode exists only inside a test, which
hand-rolls it out of three columns, so nothing in production reads a recipe back. And the value
the expansion produces is a raw `String` source name whose path-ness every consumer re-derives
for itself, capture by a filesystem probe and the rest by `Paths.get` and an absolute-or-resolve
branch. This item closes all three: entries widen to pattern-or-literal so every run transcribes,
the decode lands as production code held to identity by a round-trip anchor, and the source value
becomes a sealed carrier, so a consumer switches on a kind the producer decided instead of asking
the filesystem what the producer already knew.
The Backlog stub sketched a third shape, routing the build's own scan through the store rows; it
is rejected below, on the record.

## One recipe value, both paths

`SchemaRecipe` keeps the name and the shape R610 shipped; the stub's second name, `ScanRecipe`,
was never minted and is not now. Three of its parts change, each for a reason the shipped shape
states but cannot yet honour.

Its entries widen. `SchemaRecipe.Binding` carries a bare glob `String`, so `FactCapture`'s
`GraphIdentity` takes a null recipe for a caller with no resolved `<schemaInputs>` and the
graph, in the record's own words, is "not replayable". A literal entry retires that hole: every
pipeline run has a recipe to transcribe. An entry becomes a glob pattern or a literal source,
keeping the optional tag and description note it already has.

The nullable field goes with it, and where it goes is a decision this item makes rather than
leaves to the edit, because the obvious move is the wrong one. `GraphIdentity` is used in two
roles today. It is the *coordinate*, a graph name plus the base directory ownership is checked
against, which is all `CompileFacts` reads and all `writeGraph`'s ownership check needs; and it
is capture's *subject*, the coordinate plus the recipe capture writes beside it. The nullable
third component is what conflating the two costs, and making the component non-null would bill
the conflation to the wrong callers: `CompileFacts` writes `javac_diagnostic` rows and holds no
`<schemaInputs>` configuration, so it would have to synthesise a recipe it does not have, which
is the derived-fact-that-can-disagree this item exists to abolish. An empty recipe is worse than
a null one, since zero transcribed rows reads as "configured nothing" rather than "not asked".
So `GraphIdentity` narrows back to `(name, baseDir)` and the recipe becomes a parameter of the
capture entry points that write it, beside `registry` and `jooq`. Absence is then expressed by
which entry point a caller reached for, the same way `capture`'s SDL-only overload already
expresses "no catalog in hand", rather than by a field every construction site may leave null.
The cost is real and mechanical: the three-arg `GraphIdentity` sites in `WarmStartRefreshTest`
move their recipe to the call, `writeGraph` takes the recipe for its build-file hash, and the
coordinate-only sites (`DevMojo`'s `CompileFacts` construction, `CapturedStore.graph`,
`FactSchemaGateTest`, `PersistentStoreTest`, `FactCaptureAgreementTest`, `CompileFactsTest`)
compile untouched, which is the point: they were never the callers with something to say.

Its expansion returns a value rather than a file set. `SchemaRecipe.expand(Path baseDir)`
returns a deduplicated `Set<Path>`, which is exactly right for the currency question it was
built for and cannot serve the build's own scan: it drops configuration order, drops which binding
matched what, and drops the tag and note that binding carries, all three of which the expanded
`SchemaInput` list needs. The instance expansion therefore returns the sealed result argued
below, carrying ordered per-entry matches with their attribution. The per-pattern static
`SchemaRecipe.expand(baseDir, pattern, extensions)` is what makes the per-binding
empty-pattern diagnostics expressible, so it stays as the dialect's one primitive; the instance
method is layered over it, as it already is.

Its decode lands in production. Nothing outside a test reads a recipe back out of the store
today: `WarmStartRefreshTest` hand-rolls the row-to-recipe decode inline, selecting pattern, tag
and note from `store_graph_schema_input` and the extension rows from
`store_graph_schema_extension` and rebuilding a `SchemaRecipe` from them. That is the freshness
replay's decode, written once in a test and owned by nobody, and a hand-rolled decode is
precisely what drifts from its encoder. The decode becomes a core function beside `expand`, the
test reads it, and the round-trip anchor holds it to the writer.

`baseDir` stays where R610 put it, and the reasoning is worth stating because an earlier draft
of this item moved it. The recipe carries no base directory: `expand` takes one, `GraphIdentity`
holds one, and `store_graph.base_dir` records one, which is what lets a freshness reader replay
a *sibling's* recipe against a directory the reader resolved. A recipe that carried its own
base directory could only ever be replayed where it was written. `SchemaRecipe.buildFile` stays
too, for the same division: it is the recipe's trust anchor rather than an input to the walk,
and its nullability says "no build file", which is a fact about the run and not a default door
of the kind the source carrier below refuses.

The mojo-side decode is shipped (`AbstractRewriteMojo.buildSchemaRecipe`, reading the
plexus-bound `SchemaInputBinding` beans, collapsing empty tag and note strings to absent), and
this item does not move it; the plexus bean still does not cross into core, for the same
containment reason `AbstractRewriteMojo.decodeDependencyVersions` already answers for Maven
`Artifact`. What changes is that the decode and the expansion stop being two seams. Today
`buildContext` builds the recipe with `buildSchemaRecipe` and expands the same `<schemaInputs>`
list separately through `SchemaInputExpander`, which re-reads the same beans and re-collapses
the same empty strings; the two agree because two pieces of code were written to agree.
`SchemaInputExpander` dissolves: the binding read is the decode that already exists, the
expansion runs off the decoded recipe, and the empty-pattern diagnostics and the
`MojoExecutionException` become the mojo-side rendering of the typed result. Whether a named
decoder class remains or the decode stays a private method on the mojo is the implementer's
judgment. `SchemaRecipe`'s own class javadoc names the dissolving class ("the Maven plugin's
`SchemaInputExpander` delegates to it") and is repointed at the mojo-side decode in the same
edit; it is a `{@code}` reference, so neither the compiler nor the Javadoc reference gate will
catch it going stale. The expansion's callers become: the build mojos (as today, before `RewriteContext`
construction), the dev goal's per-regeneration re-expansion (each pass rebuilds the context
through `buildContext`, so it inherits the seam), and the freshness replay, which decodes a
sibling graph's recipe rows and runs the same expansion. Capture keeps writing the relations it
already writes, with the `kind` column below the only DDL change.

The recipe rides on `RewriteContext` already, and it landed *beside* the fact it duplicates
rather than in place of it: the context carries `schemaFileExtensions` and `schemaRecipe`, whose
`extensions()` is the same set, two components with nothing binding the copies. That is the
disagreement-at-the-producer this item exists to abolish, one level up, and it is landed code
rather than a design to prevent. So `schemaFileExtensions` stops being a record component and
becomes an accessor reading the recipe; the fact is asserted once and there is nothing to hold
in agreement. `basedir` stays a component, per the division above: the recipe carries no base
directory, so there is no second copy to collapse. The convenience overloads keep their
signatures verbatim and mint a literal recipe internally (each `SchemaInput` one literal entry,
extensions defaulted as today), so their callers compile untouched, and a derived literal recipe
cannot disagree with the list it was derived from. The two sites that pass an extension set
explicitly (`buildContext` and `CatalogBuilderSourceTest`) move onto the recipe, and the four
`with*` copy methods (`withLintConfig`, `withSessionStateConfig`, `withTenantColumn`,
`withDependencyVersions`) follow the compiler. Read sites keep compiling through the accessor.
`RewriteContext` carries the recipe beside the expanded `schemaInputs` list, both produced by
the one seam in `buildContext`, so the pair cannot disagree at the producer.

The extension filter is in core; what this item adds is that the predicate exists once. R610's
move left the duplicate pair straddling the module edge: the private
`SchemaRecipe.matchesExtension` in core and `SchemaProblemDiagnostic.matchesExtension` in the
plugin, two bodies that differ only in what they are handed. The recipe's takes a
scanner-relative path and strips the directory prefix first; the diagnostic's takes a bare
filename because its caller already called `getFileName`. The shared predicate takes the
filename, the narrower contract of the two, and the expansion does its own stripping at the call
site; the orphan scan keeps its own walk and calls the predicate unchanged. Exposing it means
the recipe's private helper becomes the published one, which is the direction the dialect
argument already points: one implementation, reachable by everyone who must agree with it.

The decode seam is cut with one eye on a direction the roadmap does not yet own: the shipped
per-user store and the core recipe leave graphitron a short step from running as a standalone
workspace process, with everything build-tool-shaped about the scan living in one decoder. A non-Maven entry point is then a second decoder into the same `SchemaRecipe`, never
a second expansion path, and the extension defaulting it would need already lives in core
(`RewriteContext.DEFAULT_SCHEMA_FILE_EXTENSIONS`). No such decoder ships here, by the same
first-reader principle that cuts the recipe's extent; what the direction is owed is only that
core assumes no Maven vocabulary, which the containment rule above already binds.

## The store-first shape is rejected on the record

The stub's sketch had the run write config rows first and the scanner read them back, making
the store the channel between two components of one run. Three arguments retire it. First, it
is an untyped channel: the mojo holds a typed value and would hand the scanner a bag of
`VARCHAR`s to re-normalise on the way out (`buildSchemaRecipe` already collapses empty tag and
note strings to absent, and a row decode has to reproduce that exactly), an encode/decode pair at a
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
ordering key; its `kind` column takes three values under a CHECK constraint (the shipped
`store_graph_schema_input` carries a `pattern` column, which generalises to `kind` plus a value
column, still keyed `(graph_name, ordinal)` with no rekey; the shipped column comment, which
says the value is "the include pattern as configured", stops being true of every row and is
rewritten with the DDL). `pattern` records a glob; the two
literal kinds transcribe which door the entry's source came through (the sealed carrier
below): a `file` literal re-expands by identity plus an existence check, so the currency
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
`/a`). Exactly one consumer asks the filesystem what that string is: `SdlFactCapture.regularFile`
asks "does this resolve to a regular file" at stamp time, because the walk is handed source
*names* by graphql-java rather than the `SchemaInput` that produced them. That count is worth
being precise about, since the capture package holds a second `Files.isRegularFile` that looks
like its twin and is not: `StoreRefresh.freshSources` probes the classpath census's entries on
the capture write path, and its own javadoc records the invariant that no schema-file path can
reach that set. The two range over disjoint populations, so there is no duplicate pair here to
collapse.

The case for the carrier is therefore not deduplication but the second probe never written. The
freshness replay is a reader that would otherwise have to ask the same question again, over a
string it read back out of a row, at a site with even less context than capture has. This item
builds that reader, so it is the item that decides whether the question gets asked a second time,
and the honest moment to fix a producer is before its second consumer exists. The source becomes
a sealed carrier with a file arm (carrying a `Path`) and a named arm (carrying the label),
decided once where the source enters the system; a new source kind is then a compile error at
every consumer that switches on it. What the carrier buys today is the consumers that re-derive
path-ness from the untyped string, listed below; what it buys tomorrow is the probe the replay
never writes.

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
the arm carries an absolute normalised `Path`, and is deleted rather than ported;
and `SdlFactCapture.regularFile` is replaced by the stamp-time switch above and deleted.
`StoreRefresh` is deliberately untouched, for the reason given above: its probe is over the
classpath census, not over schema source names, and the two only look alike.
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
same returned string, and R603 widened that reader set by normalising the compile side through
the same function at the javac boundary. Putting a carrier in front of that inserts a rendering
step on a round trip that leaves Java's type system, so the carrier renders exactly one
canonical source-name string, used both at the parser handoff and at every lookup keyed on it:
the file door normalises at mint (absolute, normalised, exactly the string
`SchemaRecipe.expand` composes today from a scanner match), the file arm renders that `Path`,
and the named arm renders its label
verbatim. A divergence of one character costs no compile error and no parse failure; it
silently stops tags and description notes from being applied, and silently unmatches capture's
stamp lookup, which is why the enforcer is an end-to-end attribution case rather than an
equality on the carrier. The rest of the edit is compiler-led, through `sourceName`'s typed
consumers: `SchemaInputAttribution`, `RewriteSchemaLoader`, `DevMojo.resolveSchemaRoots`,
`AbstractRewriteMojo`'s projection into `SchemaProblemDiagnostic`, and capture.

## Rejection is typed at the new boundary

This redesign is argued on its merits, not forced by anything above: R610's split already keeps
`MojoExecutionException` plugin-side by construction, so the plugin could keep composing its
failure prose over the core primitive's raw output. The merit is that the expansion acquires a
reader that cannot use prose. Today's shape is a result bag with a warning list plus an
exception whose message is composed at the detection site, and exactly one consumer can render
it: the build mojo that composes it. The dev goal is not a second such consumer and this item
does not pretend otherwise, since it reaches expansion through `buildContext` and
`DevMojo.regenerate` simply logs the `MojoExecutionException` it catches. The freshness replay
is the reader that breaks the shape. It runs the same expansion to answer a currency question,
so "every pattern matched nothing" is a verdict it has to *decide on*, not a message it can
print, and recovering that from a composed string means parsing prose the mojo wrote for a human.
A typed result is what lets one expansion serve a renderer and a decider at once. The redesign
follows
`docs/architecture/explanation/typed-rejection.adoc` instead. The core expansion returns a
sealed result: resolved sources beside per-pattern empty-match observations, or an
every-pattern-matched-nothing variant, each a typed fact. The mojo renders the aggregate-empty
variant as the build failure and the per-pattern observations as warnings, preserving today's
author-facing text; the freshness driver switches on the same variants to reach a verdict. The
dev goal keeps rendering what it renders today, through the mojo, and the LSP is deliberately
untouched: it boots inside the dev goal's codegen scope, after `buildContext` has already
failed or succeeded, so it has no expansion-failure surface to render and gains none here.

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

## The rows stay store_, and the read doctrine lands

The naming question this item was filed to settle is settled: R610 landed the rows under
`store_` and rewrote the family's doctrine sentence to carry them, so the header now reads the
store's own record as what it read, what it was built from, and which graphs it holds, with the
recipe rows named in the sentence as configuration the run held in hand. The alternatives stay
rejected on the same words that rejected them before, and are recorded here because the
literal rows below are the case that would tempt a re-litigation: `config_` is a role name, the
objection that retired `extension_`, and `maven_` is a vocabulary name that is false for exactly
those rows, which no build tool spelled. R603's cadence axis does not reach them either, since
capture writes the recipe on capture's own cadence rather than after it.

What has not landed is the read doctrine, and the `kind` widening is the moment to state it,
because a literal row is the first recipe row a reader could mistake for a source census. A run
reading its own graph's recipe is an ordinary same-graph read; a reader of *another* graph's
recipe is maintenance machinery, and counts as maintenance exactly while
it writes no conclusions anywhere but the `store_` family. The recipe rows never join the
cross-graph consumer read surface, which stays SDL-derived families only. It lands where the
relation's own comment can carry it, beside the sentence `javac_diagnostic` already sets the
precedent for.

## Deliberately out of scope

- **Transcribing configuration with no reader.** `<lint>`, `<sessionState>`,
  `<tenantColumn>`, output packages and directories stay untranscribed; the first-reader
  principle cuts the recipe's extent to what the scan and the replay read.
- **Folding the orphan scan onto the recipe component.** `SchemaProblemDiagnostic`'s walk
  answers the recipe's complement (schema-shaped files the recipe did not pick up) and could
  one day be a query over the recipe, but it has no fact-model payoff today; it stays a
  plugin-side walk that now calls the shared extension predicate instead of carrying its own
  copy.
- **The freshness loop's driver.** This item makes the replay's expansion and its row decode
  exist; where the loop runs from (the dev goal's watcher, the LSP, or a store maintenance
  command) stays with whichever item picks the orchestration up.
- **Any consumer-facing read surface over config rows**, per the read doctrine above.

## Verification

Full `mvn install -Plocal-db` green. The round-trip anchor is the item's enforcer, and it has a
registered home already: `FactCaptureAgreementTest` registers `store_graph_schema_input` and
`store_graph_schema_extension` under its `EQUALITY` arm, so the anchor is the content half that
registration promises rather than a new seam. The run's recipe rows, decoded back into a
`SchemaRecipe` by the production decoder and re-expanded, reproduce the run's
`RewriteContext.schemaInputs` exactly: the context's own value against that value round-tripped
through the rows. Both sides run the one `expand`, so what the equality pins is transcription
fidelity plus glob determinism rather than two independent expansions, which is exactly the
residue a single expansion path leaves to verify. The tier has no mojo, so the fixture mints the
recipe directly and builds the context from its expansion, the same pairing `buildContext`
makes. `WarmStartRefreshTest`'s hand-rolled decode in
`aRecipeReExpansionDiscoversAnAddedFile` reads the production decoder in the same edit, which is
what stops the two from drifting. Non-vacuity is a requirement
on the case, not a property of the
shape: a literal entry re-expands by identity, so a fixture of literals alone would satisfy the
equality while testing nothing. The fixture therefore carries at least one pattern entry, and
the case asserts that it does before it asserts the round trip, so a later edit that trims the
fixture to literals fails the anchor instead of hollowing it out silently. A programmatic run's literal rows
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
in `graphitron`, beside the expansion it exercises. The existing applier tests
hold the named arm's half of the same invariant already, passing labels that are not paths.

`SchemaInputExpanderTest` retargets to the core expansion with its cases intact, except the
three that assert `MojoExecutionException`, which become mojo-side rendering pins holding
today's author-facing text: `singlePatternEmpty_throwsAggregateEmpty` and
`allPatternsEmpty_throwsAggregateEmpty` for the aggregate-empty variant, and
`expand_zeroMatchAfterExtensionFilter_throwsMojoExecutionException` for the per-pattern-empty
one. The multi-binding case is the load-bearing one of the three and must not be dropped as a
duplicate of the single-binding case: its assertions are the only place the per-entry rendering
(`entry #0` and `entry #1` with their patterns, one line each) is pinned, and that enumeration
is the whole of what an author reads when several patterns miss at once. No user-visible configuration surface changes
(`mojo-configuration.adoc` already documents the glob semantics this item preserves), so the
first-client docs check is exempt.

## Retired vocabulary

- `SchemaInput.plain`, replaced by the explicit `SchemaInput.file` / `SchemaInput.named`
  doors.
- `SchemaInputExpander` as a class name, dissolved into the shipped mojo-side decode and the
  core expansion.
- "not replayable" said of a graph whose caller supplied no `<schemaInputs>` configuration, and
  the null `SchemaRecipe` on `FactCapture.GraphIdentity` that the phrase describes. Every
  pipeline run records a recipe once literal entries exist, and the recipe stops being a
  component of `GraphIdentity` at all. The phrase appears in `FactCapture.GraphIdentity`'s
  javadoc and again on `RewriteContext.schemaRecipe`'s; the sweep covers both.
- `SdlFactCapture.regularFile`, and the "does this string resolve to a regular file" question it
  asks of a `SchemaInput` source name. The file arm answers it at mint. This retires the method,
  not the predicate: `StoreRefresh.freshSources` and `ClasspathSources` keep asking it of
  classpath entries, which are not `SchemaInput` sources and gain no arm here.
