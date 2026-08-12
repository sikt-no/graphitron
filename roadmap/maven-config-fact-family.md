---
id: R612
title: "The Maven and pom configuration fact family"
status: Spec
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-08
last-updated: 2026-08-12
---

# The Maven and pom configuration fact family

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

The item's extent has widened back to the one its filename always carried. It was filed as the
Maven config fact family and narrowed to the schema recipe under the first-reader principle, which
"The extent cut is wrong" below retires. So this item now carries the whole family: every parameter
the build supplies is transcribed as provenance, not only the two the scan and the replay read. The
schema recipe stays the worked example and lands first, because it is the half with production
readers and five rounds of review behind it, and the rest of the family follows the pattern it
establishes rather than inventing a second one. The family also gains one member that does not
exist yet to transcribe: the supergraph declaration, argued in its own section below, the
parameter that decides the read doctrine's missing axis and the one whose reader the roadmap
already circles.

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
The cost is real and mechanical, and the three-arg sites are enumerated because the production
one is the whole point of the narrowing rather than a footnote to it.
`GraphQLRewriteGenerator.graphIdentity` is that site: it reads `ctx.schemaRecipe()` into the third
component today, and under the narrowing it returns the coordinate while its two callers pass the
recipe to `FactCapture.run` beside the registry and the jOOQ handle. There are two, not one, and
both are production: `captureFacts` on the generate path, and `buildOutput` on the LSP and dev-loop
path, which is the one that runs again on every regeneration. `WarmStartRefreshTest`'s
three-arg sites move their recipe to the call the same way, and `writeGraph` takes the recipe for
its build-file hash. The coordinate-only sites (`DevMojo`'s `CompileFacts` construction,
`DevMojoTest`, `CapturedStore.graph`, `FactSchemaGateTest`, `PersistentStoreTest`,
`FactCaptureAgreementTest`, `CompileFactsTest`) compile untouched, which is the point: they were
never the callers with something to say.

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
already writes; the recipe half's only DDL change is the `kind` column below (the membership
relation in the supergraph section is the item's other one).

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
column, still keyed `(graph_name, ordinal)` with no rekey; two shipped comments stop being true of
every row and are rewritten with the DDL, the column comment that says the value is "the include
pattern as configured" and the table comment that says the relation holds "one row per resolved
`<schemaInputs>` binding", which a literal row from a programmatic run is not). `pattern` records a glob; the two
literal kinds transcribe which door the entry's source came through (the sealed carrier
below): a `file` literal re-expands by identity plus an existence check, so the currency
verdict covers programmatic graphs with no special case, a file literal that no longer
resolves being a lost match exactly as a pattern whose file set shrank; a `named` literal (a
bare programmatic label, of which the applier tests carry several) re-expands to itself and is
excluded from the currency verdict, its row recording that it was an input all the same. The
replay recovers the arm from the row instead of re-asking a filesystem question about a stored
string.

Which layer drops a named entry is a decision rather than an implementation detail, because the
two candidate layers disagree about a verification claim below. The expansion passes a named entry
through unchanged, and the currency reader is what excludes it, switching on the named arm the
entry carries. Putting the skip in the decode or the expansion instead would make the round-trip
anchor unsatisfiable for a programmatic fixture: a re-expansion short by the named entries cannot
reproduce the run's `schemaInputs`, which is precisely what that half of the anchor asserts. The
existence check on a `file` literal belongs to the expansion for the mirror-image reason: a file
that stopped resolving is a lost match, and a lost match is the observation the reader is asking
the expansion for rather than one it can make for itself. Literal
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
capture's stamp decision recovers the arm through a lookup keyed on the canonical rendering,
switching exhaustively on what it finds. That is a switch over a lookup, not a filesystem probe,
and it makes the rendering invariant below load-bearing for capture too, not only for attribution.
The recipe row's `kind` and the source arm remain distinct axes even though the literal kinds
transcribe the arm: a pattern entry is one row that expands to many file arms, so neither axis is
the other's transcription.

The map the lookup reads is `SchemaInputAttribution.build`'s, but not the instance the load built,
and the difference is worth a sentence because the cheaper option is not the obvious one.
`loadAttributedRegistry` keeps `bySource` as a local and `AttributedRegistry` does not carry it, so
ferrying the built map to capture would mean widening that record for a passenger. The capture site
rebuilds it from `ctx.schemaInputs()` instead: `SchemaInputAttribution.build` is pure over the input
list, so the rebuilt map is the same map by construction, and the choice costs one call rather than
a component. The parameter still has to reach the walk, so `SdlFactCapture.capture` takes it and
`FactCapture.run` plus the three public `FactCapture.capture` overloads pass it through, beside the
recipe they gain for the same reason. One knock-on that *nothing* will catch is worth naming, since
the instinct is to trust the build here: `WarmStartRefreshTest`'s
`aWarmRefreshOverAMultiPackageCatalogCompletes` carries a `{@link}` naming a `FactCapture.capture`
overload by its full parameter list. That is a method javadoc in a test source, and the reference
gate runs the `javadoc` goal, which reads main sources only, so neither the compiler nor the gate
holds it, exactly like `SchemaRecipe`'s `{@code}` reference above. It is repointed in the same edit
or it rots silently.

The lookup has two legitimate misses, not one, and the count is stated here because getting it
wrong ships a failure the suite cannot see. Both are source names the generator injects itself, so
no `SchemaInput` produced either. The first is `RewriteSchemaLoader.DIRECTIVES_SOURCE_NAME`, the
bundled directive resource the reader surface already excludes. The second is
`TagLinkSynthesiser.SYNTHESISED_SOURCE_NAME`, stamped on the `extend schema @link(import: ["@tag"])`
that synthesiser adds when a binding carries a tag and the author wrote no federation `@link`. That
extension is added above the capture cut, before `loadAttributedRegistry` takes its pre-synthesis
snapshot, and `captureSources` walks `registry.getSchemaExtensionDefinitions()`, so the sentinel is
in the set capture switches over whenever the tag feature is used. Today's filesystem probe absorbs
it silently by returning empty, which is why the second name has never had to be thought about; a
lookup that tolerates one name would not absorb it. The pair is therefore the miss set, named once
where the lookup reads it, and the sentinel's constant widens from package-private to public so
capture can name it.

What the pair is not is a third arm on the sealed source. An arm is a claim a producer makes about a
`SchemaInput`, and there is no `SchemaInput` behind either name, so an arm would be a value no door
could mint and every construction site would have to ignore. The honest shape is that these are
generator-injected names the lookup does not expect to find, which is a property of the lookup and
belongs beside it.

The miss set above is stated over the *production* population, where the map is rebuilt from a real
`ctx.schemaInputs()`. The capture tier's own fixtures are a second population, and what the lookup
does there is decided here because the obvious reading reddens a test that exists today.
`CapturedStore.registryOf` builds a registry straight out of
`RewriteSchemaLoader.load(List.of(path))` with no `RewriteContext` anywhere, and it is how nearly
every capture-tier call reaches capture: `CapturedStore.of`, the `FactCapture.run` and
`FactCapture.capture` sites in `WarmStartRefreshTest` and `PersistentStoreTest`, and
`FactSchemaGateTest`'s two-graph fixture. Their map is empty, so every schema file in them is a
miss, and a stamp decision that stamps only what the map resolves stops stamping them, which
`WarmStartRefreshTest.aSchemaFileStampMatchesUntilTheFileChanges` catches directly: it asserts the
recorded stamp is non-null and equal to a re-hash of the file. So the fixtures move rather than the
rule. `registryOf` hands back the inputs it minted beside the registry (one `SchemaInput.file` for
the file it wrote), and the capture calls pass the map built from them, which is also what makes
those fixtures state the same claim about their sources that a production run does. The alternative,
keeping a filesystem fallback for a name the map does not resolve, is rejected: it is
`SdlFactCapture.regularFile` under another name, and it would leave the two production sentinels
absorbed by a probe instead of named, which is the failure the paragraph above exists to prevent.

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
sealed result: resolved sources beside per-pattern empty-match observations, an
every-pattern-matched-nothing variant, or a scanner-trouble variant carrying the failing entry's
index, its pattern and the cause, each a typed fact. The third arm rests on the same argument as the
second and is named because today's per-entry attribution is an accident of who drives the loop.
`SchemaInputExpander` can wrap a `DirectoryScanner` failure as "scanner error (entry #i)" only
because the mojo iterates the bindings itself; once the instance expansion owns that loop, a
propagated `RuntimeException` arrives with the index gone. No test pins that message, so nothing
would fail, and a walk that blew up is no more a currency verdict than a walk that matched nothing.
The per-pattern static keeps propagating the scanner's own exception, as its javadoc already says it
does; the instance expansion is the layer that catches and types it, which is also the only layer
that knows which entry it was. The mojo renders the aggregate-empty and scanner-trouble variants as
build failures and the per-pattern observations as warnings, preserving today's
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
cross-graph consumer read surface; the surface's full two-axis form (enumeration over the anchor
and membership relations, payload over SDL-derived families only) is stated with the supergraph
declaration below, the first fact to need the enumeration axis spelled out. It lands where the relation's own
comment can carry it, beside the sentence `javac_diagnostic` already sets the precedent for.

## The extent cut is wrong

This section reopens a decision five review rounds signed off on, so it states what changed rather
than re-arguing the design. What changed is a direction: all config provenance belongs in the
store, and a module's configuration should be knowable without re-reading its build file unless
that file changed. Under that direction the first-reader principle is the wrong instrument for
cutting this item's extent, and it is withdrawn as the reason. It stays a fine principle for
deciding when to build a *derivation*; it is the wrong test for whether to record a *fact*, because
a fact's readers arrive later than the fact does and a run that has already exited cannot be asked
again.

The asymmetry this item currently ships makes the point on its own. `store_graph.build_file_stamp`
is a content hash of the whole build file, and its comment states the contract in full: "the
remembered recipe is trusted only while the build file still hashes to this, and a mismatch marks
the recipe possibly stale until the module's own next build repairs it." That is a fitness claim
about the *entire* configuration. Behind it this item stores two parameters. A reader can therefore
prove a sibling module's configuration is unchanged and still be unable to see almost any of it,
which is the invalidation mechanism built and the payload withheld.

Two things this does not overturn. The **store-first shape stays rejected** exactly as argued
above: the store is not a channel between two components of one run, the honesty check stays
non-vacuous, and nothing reads config rows before capture writes them. The direction here is
write-side transcription plus a *cross-run, cross-module* read, which is a different shape from the
intra-run channel those three arguments retire. And the **savings claim needs stating honestly**:
on the Maven path there is no parse to skip, because Maven parses the pom and injects the
parameters before any mojo runs. What the transcription buys is the reader that has no build to
run at all: a sibling module's configuration, a non-Maven entry point, a maintenance or LSP surface
answering questions about a cold graph. That is the same reader the schema recipe was already built
for, extended to the rest of the configuration.

What this item does about it: widens to carry the family, which is what its filename always said it
was. The schema recipe is unaffected as a design and lands first; nothing argued across the five
review rounds is reopened by this, because the recipe's shape, its sealed source carrier, its
production decode and its round-trip anchor are exactly the pattern the rest of the family follows.
What is added is the rest of the parameters and the questions their grain raises, below.

## The rest of the family

Every parameter the build supplies is transcribed. `<lint>`, `<sessionState>`, `<tenantColumn>`,
the output package and directory, and whatever the mojo grows next. A run that has exited cannot be
asked again, so the test is whether the build knew it, not whether a reader has asked for it yet.

Four boundaries the plan has to settle, none of which the recipe's own design answers by itself.

* **Grain.** A relation per parameter family, or one discriminated config relation keyed
  `(graph_name, ordinal)` the way `store_graph_schema_input` is. The recipe is the precedent and it
  chose one discriminated relation over several, on the argument that the ordinal is the recipe's
  spine and splitting would shatter the one ordering key. That argument is specific to an ordered
  list of bindings and does not obviously carry to a set of unrelated scalar parameters, so the
  question is open rather than settled by precedent. What the plan must not produce is a
  key-value bag of `VARCHAR`s wearing a relation's clothes. The supergraph declaration below
  works the first instance and states the criterion in the store's own terms: always-present
  partition facts are anchor columns, an optional partition fact is its own graph-keyed relation
  whose row presence is the fact (never a nullable column), and run inputs are family rows.
* **Structured values.** `<lint>` and `<sessionState>` are not scalars. Whether the transcription
  holds a typed decomposition or a rendered form is decided per parameter and stated, since a
  rendered form is a string a later reader has to re-parse, which is the shape this item exists to
  remove for source names.
* **Absent versus empty.** "Configured nothing" and "not asked" are different facts and a nullable
  column conflates them. The recipe met this and answered it with the `kind` discriminator; the
  general case needs its own answer, and a parameter carrying a mojo default needs to say whether
  the default or the absence is what it records. The supergraph declaration below settles the
  no-default instance only; a parameter that does carry a default still owes its own answer.
* **Decode location.** Unchanged from the recipe's rule: the plexus-bound beans stay plugin-side,
  core assumes no Maven vocabulary, and a non-Maven entry point is a second decoder into the same
  typed value rather than a second capture path. The wider the family, the more this rule is
  load-bearing, since it is what keeps `maven_` from being the honest prefix for these rows.

What the family does *not* change: the store-first rejection, the read doctrine, and the honest
statement of what transcription buys. On the Maven path there is no parse to skip, because Maven
parses the pom and injects the parameters before any mojo runs. The reader served is the one with
no build to run at all.

## The supergraph declaration scopes the peer set

Everything above transcribes configuration that already exists. One parameter the family needs
does not exist yet, and this item adds it rather than waiting to transcribe it: which supergraph,
if any, this graph is a subgraph of. The direction is the workspace store's own. A dev session
already holds every sibling graph's rows and reads none of them, and the reader the store is owed
("a maintenance or LSP surface answering questions about a cold graph", above) needs an answer to
"which graphs are this graph's business" before it can range over anything. Today no layer can
answer that. Federation-ness is SDL-derived and per-graph, a predicate over the `@link` url as
`graphitron_link`'s own comment words it, and it answers "did this graph opt into federation",
never "with whom": two subgraphs of two different supergraphs carry indistinguishable federation
SDL. The workspace store's colocation is filesystem inference, and its stated justification is
already federation-flavoured (`AbstractRewriteMojo.workspaceRoot`: two subgraph modules of one
checkout "have to land in one store to be composable at all"); colocation is false in both
directions a reader cares about, since one checkout can hold two supergraphs plus standalone
graphs, and one supergraph can span checkouts. So the fact is declared, not derived: a new
optional `<supergraph>` mojo parameter, no default, an empty element collapsed to absent by the
decode exactly as the recipe's tag and note are, riding `RewriteContext` beside `graphName` and
transcribed to a membership relation, `store_graph_supergraph`, keyed `(graph_name)` with an FK
to the anchor and one non-null `supergraph_name` column. Only graphs with a declared supergraph
are registered: the row's presence is the fact, and a standalone graph has no row, which is the
same structural expression of absence the `GraphIdentity` narrowing above insists on for the
recipe ("expressed by which entry point a caller reached for... rather than by a field every
construction site may leave null"). A nullable column on the anchor would be that refused field
under another name. The pair `(supergraph_name, graph_name)` is then the store's rendering of
the addressing federation already uses, which is why `<graphName>`'s javadoc speaks of "the
subgraph's published name"; a parent pom shared by one supergraph's modules declares the value
once in `pluginManagement` and every subgraph module inherits it.

What the declaration asserts is grouping, not federation, and the relation's comment says so in
so many words, because "supergraph" would otherwise be read as the federation claim by every
future reader. Declaring membership does not make a graph federated and is not policed against the
SDL's opt-in: the store already holds the derivable fact (`graphitron_link`, whose comment calls
the opt-in a predicate over `url`), and a declared fact beside a derivable one is only the
disagreement this item abolishes when the two claim the same thing, which these do not. The
grouping is deliberately usable before federation SDL lands, since a subgraph under development
may declare its home before its first `@key` is written, and the dev surface below exists for
exactly that time. A graph declaring a supergraph while carrying no federation `@link` is
therefore not an error anywhere in this item; if a later diagnostics item wants the one-join
advisory ("declared a supergraph, never opted into federation"), both facts it joins are in the
store and the detection is that item's to argue. Standalone needs no declaring: not every graph
is federated, a graph with no `<supergraph>` element is standalone, and standalone is the default
rather than a state an author spells.

The membership is single-valued, and that is a claim made here rather than an accident of shape:
one module build declares at most one home supergraph, the singular `<supergraph>` element is the
claim's configuration-side enforcer, and the relation's `(graph_name)` primary key is its
structural one. Federation practice does admit a subgraph published into more than one
supergraph; if that day comes, the widening is the key growing to
`(graph_name, supergraph_name)`, and its cost is a store-stamp roll rather than a data
migration, since the DDL hash names the store's directory segment and an upgraded store opens a
different file. The claim is cheap to make and cheap to retire; silence would be the one costly
option.

Where the fact lands answers the family's grain bullet with a criterion stated in the store's
own terms rather than a reader's. A fact that is always present for every graph, single-valued,
run-owned, and *about the partition* (how it groups, where it lives, what it was built from) is
a column on the partition anchor, which is `base_dir` and `last_captured`'s shape. A fact that
is *optional* is its own graph-keyed relation whose row presence is the fact, because the store
does not spell absence as a nullable column; and an ordered or multi-valued fact is its own
relation with an ordinal, which is the recipe's shape. That the peer reader keys enumeration on
the relation (a self-join on `supergraph_name`) is a consequence of the grain, not its
justification, this item having just retired reader-first reasoning as an instrument. The "about
the partition" clause is what the grain rule must not erode, so the rest of the family does not
follow membership into anchor-adjacent relations of its own invention: `<tenantColumn>` is
single-valued and run-owned too, and it is generation payload rather than a partition fact;
beside the anchor it would be the first brick of the key-value bag the family bullet warns
about. Partition facts land on or beside the anchor; run inputs land as family rows.

The membership relation is graph-keyed precisely so its ownership stays with the graph
partition, and the shape it must not drift toward is named: a `store_supergraph` *entity*
relation, one row per supergraph that graphs point at, is rejected on ownership. Every store
relation today is owned by exactly one graph partition or is store-global bookkeeping; a
supergraph entity row would be the first that is neither: no single run mints it, no run may
clear it, and `StoreRefresh.graphScoped` derives the ownership-scoped clear set from the
presence of a `graph_name` column, which an entity relation would not have. Every answer to "who
writes the row" either breaks the `aRunWritesOnlyUnderItsOwnGraph` gate or invents a
co-ownership rule the store has never needed. The supergraph exists in the store as a value
graphs declare, never as an entity anything owns; `store_graph_supergraph` is each graph's own
declaration, minted and cleared by the graph's own run like every other graph-keyed row.

No row means standalone, and the conflation the family bullet warns about is accepted here on
the record, in all three of its meanings rather than two. A graph whose author declared nothing
has no row; a programmatic run that was never asked writes none; and a graph whose anchor was
minted by the diagnostics preambles (`OwnedGraphPartition.prepare` and `CompileFacts`, both
`onDuplicateKeyIgnore`) has none because capture has not run yet. The three collapse because
every reader's safe answer is identical: the declaration is opt-in and visibility never guesses,
so "declared nothing", "not asked" and "not yet captured" all read "not a peer". A discriminator
recording which absence this is would buy nothing any reader forks on.

Capture is the relation's one writer, and removal propagates structurally rather than by upsert
care: the relation is graph-keyed, so `StoreRefresh.graphScoped` ownership-scopes it by default
(the clear set is derived from the presence of a `graph_name` column), a warm run clears the
graph's row with the rest of its partition, and capture rewrites it only when a declaration is
in hand, exactly as the recipe rows are "written fresh by every run". A pom that drops the
element therefore leaves no row on the next capture, with no both-arms upsert subtlety to get
wrong. The preambles never touch the relation, so a compile-facts run can neither erase nor
invent a declaration; the verification case pins both directions. The relation's FK to
`store_graph` and its `graph_name` key also put it under the shipped partition gates by default
(the FK-closure and leading-key dimension gates run in exemption polarity), so its coverage is
inherited rather than owed.

The capture signature is where the family would otherwise accumulate, and this item stops that
here. The narrowing above moves the recipe from `GraphIdentity` to the capture entry points, the
attribution map travels beside it, and the supergraph declaration would be a third loose
parameter, with the rest of the family keeping the pattern going, one nullable positional
argument each; that is the untyped default door the sealed-carrier section refuses, rebuilt at
the seam the narrowing cleaned. So the entry points take one typed value for capture's *subject*
configuration, carrying the recipe and the supergraph declaration as components with explicit
absence and growing a component per family parameter as the rest lands; `GraphIdentity` stays the
coordinate exactly as argued, and the coordinate-versus-subject split above is the criterion that
produces this shape. The name is the implementer's, though "membership" is taken in the capture
package (`GraphSourceMembership` is about graph-to-source rows, not this); what is bound is the
shape, one value with typed absence per component. The attribution map stays its own parameter,
being derived from the inputs rather than declared. The call-site enumeration above is unchanged
in who passes what; only the carrier consolidates.

What the relation buys is the axis the read doctrine above was missing, and the doctrine's
sentence is completed rather than contradicted, since read narrowly it forbade every cross-graph
read outside SDL-derived families while the peer enumeration reads the membership rows. The
surface has two axes and the doctrine binds both. The enumeration axis is a closed two-relation
set, named so the next reader cannot widen it by finding something useful: a consumer surface
may range cross-graph over `store_graph` and `store_graph_supergraph`, and nothing else
configuration-shaped. The payload axis is unchanged: what a surface reads *about* a peer stays
SDL-derived families only, `javac_diagnostic` stays graph-private by its own comment, and the
recipe and family rows never join. The peer set itself is the enumeration axis's one derivation,
and it needs no null vocabulary at all: a graph's peers are the graphs its membership row joins
to over `supergraph_name`, a self-join between non-null values. A standalone graph has no row to
join, so its peer set is empty and two standalone graphs never group by accident; two
supergraphs in one workspace store coexist mutually invisible, which is what lets one checkout
carry both without either becoming the other's noise. The store file stays the physical
boundary: peers are found in the store the session opened, and a supergraph spanning checkouts
is invisible across store files. The per-user cache root holds every workspace segment, so a
cross-store reader is conceivable later machinery; it is not this item's, and the doctrine as
stated governs one store.

The reader this scope was shaped for is the dev loop's. `DevMojo` already opens the workspace
store, so a dev session physically holds every sibling graph's rows; the MCP diagnostics tools
lead every query with the session's own `graph_name`. A peer surface (which subgraphs share my
supergraph, what does a peer's SDL declare where I extend its entity) is what turns the store's
colocation into the composability `workspaceRoot`'s javadoc promises, and building it is not this
item's work; R643 (`supergraph-peer-surface.md`) carries it. Two things are owed to that surface
here so its first spelling is not a rediscovery. The peer predicate is deferred deliberately: no
view lands ahead of its reader, because a view owes an anchor and a registration under
`FactCaptureAgreementTest`'s derived arm while its projection is the reader item's design space;
what is pinned instead is the presence semantics, at the SQL level, by the verification case
below, and the reader item mints the *first* production spelling of the peer set and should mint
exactly one. And the peer answer's fitness caveat is stated rather than left to be discovered: a peer row
is a claim about the peer's last capture, not its current pom, and `store_graph.build_file_stamp`
already owns that contract ("trusted only while the build file still hashes to this").
Enumeration is deliberately not stamp-gated, a stale peer being still a peer and a flickering
peer set being worse than an honestly stale one; the stamp rides on the anchor row the peer's
membership row points at, for the surface to render beside the answer.

Deriving the declaration from Apollo's composition config is rejected on the record.
`supergraph.yaml` enumerates subgraphs from the composition's side and lives where composition
runs; a subgraph module does not generally hold one, so absence is the common case, and parsing
it would make another tool's dialect a load-bearing input to the store, against the same
containment rule that keeps plexus beans out of core. If a checkout holds one, a later decoder
may transcribe agreement between the two declarations, a second decoder into the same fact,
exactly the shape the decode-location rule anticipates.

## Deliberately out of scope

- **Transcribing configuration with no reader** — *retired.* This exclusion, and the first-reader
  argument behind it, is withdrawn; see "The extent cut is wrong" and "The rest of the family"
  above. The parameters it excluded are now in scope.
- **Folding the orphan scan onto the recipe component.** `SchemaProblemDiagnostic`'s walk
  answers the recipe's complement (schema-shaped files the recipe did not pick up) and could
  one day be a query over the recipe, but it has no fact-model payoff today; it stays a
  plugin-side walk that now calls the shared extension predicate instead of carrying its own
  copy.
- **The freshness loop's driver.** This item makes the replay's expansion and its row decode
  exist; where the loop runs from (the dev goal's watcher, the LSP, or a store maintenance
  command) stays with whichever item picks the orchestration up.
- **Any consumer-facing read surface over config rows**, per the read doctrine above. The
  enumeration axis is the one carve-out, and it is exactly `store_graph` and
  `store_graph_supergraph`, per the supergraph section.
- **The peer surface itself.** The dev-loop reader that enumerates same-supergraph peers and
  answers over them is R643 (`supergraph-peer-surface.md`); this item owes it the fact, the
  doctrine, and the pinned presence semantics, nothing more.
- **Cross-checkout supergraphs.** The doctrine governs one workspace store; a reader spanning
  store files under the per-user cache root is later machinery.
- **Policing the declaration against the SDL's federation opt-in.** Both facts are in the store;
  whether their disagreement is worth an advisory is a later diagnostics item's argument.

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

The lookup's miss set gets the third enforcer, and it is the one this item would otherwise ship
uncovered. No capture-running fixture configures a tag today: the two tagged pipeline cases
(`TaggedInputsPipelineTest`, `ConnectionFederationTagPipelineTest`) stop at
`loadAttributedRegistry` and never reach capture, `CapturedStore.ofPipeline` mints its one input
untagged, and no pom in the tree sets a `<schemaInput>` tag. So the synthesised-`@link` sentinel
reaches the stamp lookup only on a consumer's build, and a miss set that named one sentinel would
ship green and fail at the first consumer to use tags. The case is a tagged capture: a pipeline
capture whose input carries a tag, so `TagLinkSynthesiser` fires and its sentinel enters the
captured registry's source set, asserting the capture completes and stamps the tagged file. It
belongs beside `CapturedStore.ofPipeline`, which already runs the production load and captures the
pre-synthesis handle, so the fixture change is the input's tag and nothing else.

`SchemaInputExpanderTest` retargets to the core expansion with its cases intact, except the
three that assert `MojoExecutionException`, which become mojo-side rendering pins holding
today's author-facing text: `singlePatternEmpty_throwsAggregateEmpty` and
`allPatternsEmpty_throwsAggregateEmpty` for the aggregate-empty variant, and
`expand_zeroMatchAfterExtensionFilter_throwsMojoExecutionException` for the per-pattern-empty
one. The multi-binding case is the load-bearing one of the three and must not be dropped as a
duplicate of the single-binding case: its assertions are the only place the per-entry rendering
(`entry #0` and `entry #1` with their patterns, one line each) is pinned, and that enumeration
is the whole of what an author reads when several patterns miss at once.

The supergraph declaration gets the fourth enforcer, beside the two-graph fusion gate whose
fixture it extends. A store captures three graphs, two declaring one supergraph and one declaring
nothing: the membership rows round-trip through capture (exactly two, carrying the declared
name), the peer question asked at the SQL level, the self-join over `supergraph_name`, returns
exactly the declared sibling for each of the two and nothing for the third, and the presence
semantics are pinned where the join holds them, a graph without a row grouping with nothing. The
write path's two directions are the same case's second half: a preamble mint after capture
(`OwnedGraphPartition.prepare` against the captured graph) leaves the membership row standing,
and a warm recapture without the declaration leaves none, so removal propagates through the
ownership-scoped clear and a compile-facts run can neither erase nor invent membership. It lives
beside `aRunWritesOnlyUnderItsOwnGraph` in `FactSchemaGateTest`.

The recipe half changes no user-visible configuration surface (`mojo-configuration.adoc` already
documents the glob semantics this item preserves), so the docs exemption covers it; the
supergraph declaration does change the surface, and `<supergraph>` lands in
`mojo-configuration.adoc` beside `<graphName>`, whose entry already speaks the subgraph
vocabulary.

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
