---
id: R985
title: "The capture assembles the schema without the federation @link, so every imported directive is reported undeclared"
status: Spec
bucket: dx
theme: diagnostics
depends-on: []
created: 2026-10-01
last-updated: 2026-10-02
---

# The capture assembles the schema without the federation @link, so every imported directive is reported undeclared

## Goal

A federated schema reads as clean in the editor and over MCP whenever the generator finds it clean, and the store's
defect checks run over it just as they do over a schema that is not federated. A federation `@link` is the schema
extension that imports the Apollo federation spec's directives (`@key`, `@shareable`, `@tag`, `@inaccessible`,
`@override`) into an author's schema. The generator honours it. The capture does not. The capture is the pass that
records the corpus into the fact store, the database the language server and MCP read. So every use of an imported
directive is reported as `tried to use an undeclared directive`, the store calls its own facts stale, and the derived
defect checks see no schema at all. When this item lands, the generator and the capture make the corpus into a schema
with the same code, so a directive the generator accepts can never be reported as undeclared by the store.

The schema shape is the one the sakila example's `federated-schema.graphqls` writes:

```graphql
extend schema
  @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])

type Film @key(fields: "filmId") { ... }
```

Today the generator builds this schema cleanly. The store instead records the `@link` as undeclared, plus every `@key`
application, and calls its facts stale. After the fix the store records nothing for this schema.

## What is true today

The **loading rewrites** are the four in-place edits that stand between the files on disk and the registry the rest of
a run sees. They run in this order:

- `TagLinkSynthesiser` adds an `@link` importing `@tag` when `<schemaInput tag>` is configured and the author wrote no
  `@link`.
- `FederationLinkApplier` adds the federation spec's directive and type declarations that the `@link` imports.
- `TagApplier` applies the configured tags.
- `DescriptionNoteApplier` appends the configured description notes.

The generator reads through `AttributedRegistry.load`. It runs all four rewrites, then cuts the `preSynthesisRegistry`
handle, then lets synthesis run. **Synthesis** is graphitron's own macro expansion, which adds declarations the author
never wrote, such as `KeyNodeSynthesiser`'s federation keys. `GraphQLRewriteGenerator.assembleAndCaptureVerdicts`
judges the author's document by assembling that pre-synthesis handle. **Assembly** is graphql-java's
`makeExecutableSchema`, the toolchain pass that checks the specification's structural rules.

The capture runs none of the four rewrites. `ModelCapture.capture` hands `GraphQLAssemblyCapture.capture` the
documents. That method reduces them with `SchemaLoader.merge` and assembles the raw result with `SchemaAssembly.of`.
Each `UndeclaredDirectiveError` that raises becomes a `graphql_schema_problem` row at stage `ASSEMBLY`. The `diagnostic`
view surfaces every such row. Observed on sis (2026-10-01): 108 undeclared-directive diagnostics, across `@tag`,
`@shareable`, `@key`, `@inaccessible` and `@link`, from one federation `@link`. The generator's own run reports none of
them. `@link` is reported too because its own definition is one of the declarations `FederationLinkApplier` adds.

The rejected assembly costs more than the noise:

- **Freshness is stuck.** `SchemaLifecycle.read` in `graphitron-mcp` reports `Previous` whenever the graph has any
  `graphql_schema_problem` row. `Previous` means the tools are answering from the last clean facts. So every federated
  consumer reads `Previous` on every capture, however clean its schema is.
- **Defect checks go silent.** `FactCapture.derive` hands the derivation steps a null schema when the assembly was
  rejected. `ClassificationDomainCapture.derive` returns early on null and leaves `intent_type_domain` empty. That
  relation is the set of types classification reaches. `NodeIdDecodeDefects`, `NodeIdLandingDefects`,
  `NodeIdPolymorphicDecodeDefects`, `NodeIdDecodeCoverageFacts`, `ReferenceForParticipantDefects`,
  `AuthoredClaimConflicts` and `UnlowerableOrderings` all read it. On a federated consumer they report nothing,
  whatever the schema holds. Confirm per derivation at pickup whether it scopes by the domain or only joins it; the test
  below pins the domain being populated either way.
- **The tag rewrites are missing too.** A consumer with `<schemaInput tag>` gets neither the synthesised `@link` nor the
  tag applications or notes in the registry the capture assembles and decodes. The `AttributedRegistry.load` comment
  saying capture has to see `TagApplier` and `DescriptionNoteApplier` is not true on the current path.

The decode does not need the rewrites to find `@key` applications. The decode is `SdlFactCapture`, which walks the
registry the assembly judged and records each graphitron directive application it finds. Applications are in the raw
registry whether or not anything declares them. That is why `FederationKeyDerivationTest` and the federated fixture in
`FactCaptureAgreementTest` pass today, and why neither notices the problem rows.

## Implementation

**One composition, two callers.** Lift the four rewrites out of `AttributedRegistry.load` into one function in
`no.sikt.graphitron.model.schema.input`, working name `LoadingRewrites.apply(merged, bySource)`. It runs them in
today's order on a **copy** of the merged registry and returns a sealed outcome:

- `Applied`, carrying the composed registry and the injected names.
- `Refused`, carrying one typed refusal and the exception today's code throws for it.

Working on a copy has two consequences. A refusal leaves nothing half-applied: `FederationLinkApplier.apply` adds
definitions one at a time and can throw partway through. And the merged registry survives untouched for the decode,
below. Check at pickup whether `TypeDefinitionRegistry.readOnly()` copies or only wraps. `AttributedRegistry` already
leans on it as a snapshot before `KeyNodeSynthesiser`, so the answer matters there too.

The refusal is a sealed type with one variant per way a rewrite refuses:

- `MultipleFederationLinks`
- `UnsupportedFederationVersion`
- `DeclarationCollision`, for an author's own declaration of a definition the `@link` imports. This is the
  `buildCollisionMessage` arm that has a source file.
- `LibraryDuplicateDeclaration`, for `federation-graphql-java-support` returning one definition twice. This is the
  v2.6/v2.7 arm.
- `TagNotImported`, for `TagLinkSynthesiser`'s refusal.

Whatever `TagLinkSynthesiser`'s own `registry.add` can refuse gets typed at pickup. Each variant carries the location
it points at and the message today's code builds.

The line between loading rewrites and synthesis stays where `AttributedRegistry.load` draws it. Synthesis is not part
of this function. It stays on the generator path, and the capture derives its own synthesised keys
(`graphitron_synthesized_federation_key`).

- **The generator.** `AttributedRegistry.load` calls the function, takes the composed registry on `Applied`, and on
  `Refused` throws the carried exception. Build failures keep their exact exception types and messages, so the mojo
  catch arms and the federation recipe text do not change: `IllegalStateException` and
  `UnsupportedFederationVersionException` from `FederationLinkApplier`, and `ValidationFailedException` from
  `TagLinkSynthesiser`.
- **The capture.** `GraphQLAssemblyCapture.capture` reduces with `SchemaLoader.merge` as now, calls the function, and
  assembles the composed registry. On `Refused` it assembles the merged registry instead, so the `Refused` arm says
  exactly what was judged. The `ASSEMBLY` rows that follow, such as undeclared directives after a `@link` that could
  not be applied, are then true statements about the corpus as written.

  A refusal never throws in the capture. If it threw, a corpus mid-edit with two `@link`s would lose every fact beside
  them in the editor.
- **The decode walks the merged registry, not the composed one.** `GraphQLAssemblyCapture.capture` hands back both:
  the merged registry for the decode, and the assembly over the composition. `ModelCapture` gives `SdlFactCapture` the
  merged one.

  The decode is the as-written half of the store, a function of the documents and nothing else, and it has no use for
  the rewrites; see the last paragraph of "What is true today". Keeping the composition out of its input makes "the
  decode does not vary with tag configuration or the federation library" true by construction rather than by test.
  That matters because `TagApplier` replaces definitions with `remove` and `add`, which can reorder extension sites.
  `SdlFactCapture.sitesByType` numbers sites in iteration order, and its claims are first-wins. And
  `TagLinkSynthesiser`'s sentinel-sourced schema extension would otherwise reach `captureSchema`.

  Restate the `ModelCapture` comment ("the registry it visits is the registry the assembly judged") as: the decode
  walks the merged corpus that the assembly's composition started from. `SchemaAssembly.registry()` keeps meaning
  "what was assembled", which is now the composition.

**Refusals get a stage of their own.** Add `REWRITE` to `graphql_schema_problem.stage`'s CHECK, between `REGISTRY` and
`ASSEMBLY`. Rows are numbered within the stage and written in the order the stages ran. Do not reuse `REGISTRY`. A row
there is one of the errors graphql-java raised combining the documents, recomputable from the entries alone. A
rewrite refusal is neither:

- It depends on configuration and on the federation library's version support.
- Its message is graphitron's own fix-it prose, not graphql-java's sentence kept verbatim.

`error_class` is the refusal variant's name. That stays a bare name, so it cannot collide with the dotted rejection-leaf
names `diagnostic.variant` shares a column with. The refusal does not go through `SchemaError`, whose `cause` is a
`GraphQLError`. Give `GraphQLSchemaProblems.writeAssembled` an input for the refusal rather than inventing a
`GraphQLError` to fit it.

Comments and docs that change with the new stage:

- the `graphql_schema_problem` table comment ("one of the errors graphql-java raised, whichever of the three
  stages") and its `stage`, `error_class` and `message` column comments
- the relation's `meta_relation` row
- `SchemaError.Stage`'s javadoc
- `SchemaLifecycle`'s javadoc, which counts three stages
- the `docs/architecture/explanation/fact-model.adoc` passages on the verdict being one relation ("three error
  classes", "as graphql-java stated it") and on the `REGISTRY` stratum

`SchemaLifecycle.read` needs no code change: any row of any stage is a refusal, which is what freshness should say for
a `@link` that could not be applied.

**The capture now reads configuration, and says so.** After this change, `graphql-assembly`'s rows vary with
`<schemaInput tag>` and with the federation library. The store's definition of a crawler, in the `meta_gatherer_corpus`
comment, is a pass whose rows about its own corpus may not vary with any other corpus. So declare the configuration read
on the `graphql-assembly` roster in `meta_gatherer_corpus`, and argue it in fact-model's stratum section. The argument:
the assembly verdict is about the corpus as the generator composes it, and the composition is configured. The `sdl`
gatherer's roster does not change, because the decode keeps reading the documents alone.

**Attribution comes with the documents.** `GraphQLSourceCapture` already expands the recipe, through
`SubjectConfig.schemaFiles`, which throws away the `SchemaInput` each file came from. That input is what carries the
tag and the description note. Have the source capture expand the recipe once and keep the inputs: each
`SourceDocument.Stated` carries its `SchemaInput`, and `GraphQLAssemblyCapture` builds the attribution map from the
documents it is handed. `schemaFiles` either becomes a projection of a new `SubjectConfig.schemaInputs(baseDir)` or
gives way to it. No second expansion of the recipe happens anywhere in a capture.

The test callers of `GraphQLAssemblyCapture.capture` (about a dozen under `graphitron-model/src/test` and one in
`graphitron-lsp`) build their documents with an explicit untagged input. No overload drops the attribution quietly,
because a quiet drop is this defect again.

**Javadoc that the plan must not leave looking satisfied.**

- `GraphQLAssemblyCapture`'s class javadoc describes two stages, reduce and assemble. It now has three.
- `AttributedRegistry`'s description of `preSynthesisRegistry` as "the handle the fact-capture loads take" was never
  literal, since the capture never took that handle. Reword it to say the capture composes the same way.
- `PipelineCapturedStore.registry()`'s "the registry capture actually walked" is false on the current path. Repoint it
  at the merged registry.
- The `AttributedRegistry.load` comment saying `TagApplier` and `DescriptionNoteApplier` run above the cut because "the
  store owes a round trip, so capture has to see them" stays false after this change. Tags and notes reach the
  capture's assembly, but still no relation. The `store_graph_schema_input.tag` column comment leans on the same
  claim. Reword both to what is true: the generator's emitted schema carries them, the capture's assembly judges them,
  and no relation transcribes them.

## Tests

Model tier, in `GraphQLSchemaProblemsTest` (it already drives `GraphQLAssemblyCapture.capture`):

- **The regression.** A document with `extend schema @link(url: ".../federation/v2.10", import: ["@key",
  "@shareable", "@tag", "@inaccessible"])` that applies each directive writes no `graphql_schema_problem` row.
- **One test per refusal variant:** two federation `@link`s; an unsupported federation version; an author's own
  `directive @key` beside an `@link` importing it; and a tagged input whose author `@link` does not import `@tag`. Each
  pins three things:
  - the capture completes;
  - exactly one `REWRITE` row is written, with the variant's name as `error_class`;
  - the `ASSEMBLY` rows that follow are those of the merged registry, not of a partial composition.
- **No `@link`, tag configured.** A tagged input with no `@link` in the documents writes no row. This is
  `TagLinkSynthesiser`'s synthesis path.
- **The decode is independent of configuration.** In the style of `CaptureCorpusIsolationTest`: capture one federated
  document twice, once untagged and once tagged, and require identical `graphql_` and `graphitron_` entry rows.

Generator tier:

- `AttributedRegistry.load` over each refusal input still throws today's exception type with today's message.

Full capture path, on the federated fixture `FederationKeyDerivationTest` or `FactCaptureAgreementTest` already
captures through `ModelCapture`:

- `graphql_schema_problem` is empty for the graph, so freshness reads `Current`.
- `intent_type_domain` has rows for the graph.
- **An observable test of the attribution wiring.** A tagged input whose author `@link` lacks `@tag`, captured through
  `ModelCapture` from a recipe, writes the `TagNotImported` row. The model-tier tests above build their documents by
  hand. This is the test that fails if the tag never reaches the capture.

## Other solutions we've considered

- **Make the federation and tag refusals detections over captured rows.** Multiple federation `@link`s and a missing
  `@tag` import are questions over rows the store already holds: the `@link` application entries and
  `store_graph_schema_input.tag`. They could be views rather than capture-time rows. Not done here, because the
  rewrites have to run to assemble the schema at all, and they refuse while running. A view would state the same
  verdict a second time, beside the refusal that actually stopped the composition. Revisit if the assembly ever stops
  needing a registry.

- **Filter the undeclared-directive rows out.** Drop the problem rows whose message names a federation directive. The
  store would go quiet, but the capture would still assemble a different schema from the one that gets generated.
  The null schema would still reach the derivations, so the defect checks would stay silent. And the store would hold
  two readings of one corpus that can disagree with nothing comparing them. `GraphitronSchemaBuilder` offers no
  precedent for this: `buildRecipeErrors` rewrites an undeclared federation directive into a how-to message, and only
  when the generator's assembly fails, which it does not on a linked schema.
- **Compose only the federation `@link`.** Smaller, but it leaves the tag rewrites out of the capture. A third reading
  would then grow back the first time `<schemaInput tag>` meets a capture. `TagLinkSynthesiser` and
  `FederationLinkApplier` also have to run together, since the first writes the `@link` the second reads. Moving all
  four costs the same as moving two.
- **Have the capture call `AttributedRegistry.load`.** One function, but `load` parses the files itself. It also runs
  synthesis, needs a `RunContext` and a jOOQ catalog, and throws on refusal. The capture has already read the documents
  once through `GraphQLSourceCapture`, which owns the store's record of what was read, so parsing twice would bring back
  the two readings `ModelCapture` was shaped to remove.

## Reviewer findings

### Round 1 (Spec → Ready): request revisions

Session `014Qa3HaM9vJbDTdGYH8zevQ`, 2026-10-05.

Question 1 mostly passes. The reader can tell what changes: a federated schema the generator accepts stops showing up as 108 undeclared directives, freshness reads `Current`, and the domain-scoped checks run again. The claims about the tree check out: the four rewrites and their order, the `preSynthesisRegistry` cut, `GraphQLAssemblyCapture`'s reduce and assemble, the null schema reaching `ClassificationDomainCapture`, `SchemaLifecycle`'s any-row read, the twelve model-test callers plus the one in `graphitron-lsp`, and every javadoc the plan lists as stale. The core shape fits question 2: one composition with two callers, a sealed outcome rather than a throw, and a decode kept on the as-written registry. That is the right move. Findings 1 and 2 are about what the goal and the store's charter owe. Findings 3 to 7 are gaps the implementer would otherwise hit.

1. **The goal leaves out that build errors come back, not only editor diagnostics.** `GraphQLRewriteGenerator` feeds `StoreDetections.over` into the build's error stream (`GraphQLRewriteGenerator.java:589`). `AuthoredClaimConflicts` limits its build-error population to `intent_type_domain` ("only the emitted surface can fail a build"), and the NodeId defects join the same relation. So today every federated consumer's build silently drops those errors, and this item turns them back on in `mvn` builds as well as in the editor. That is the point of the item, but it also means a consumer whose build passes today can fail once it lands. The audit beside R876 already shows the store's defects disagreeing with the generator on sis, which is the federated consumer this item's evidence comes from. *To satisfy:* say in the goal that the domain-scoped build errors return for federated consumers. Add an acceptance step: a sis run on the built jar, reporting what the newly live checks raise. Say whether landing waits on that run and what happens if it finds false positives.

2. **A `REWRITE` row does not meet the `graphql_` family's charter, and the plan does not address the charter.** The family's `meta_family` row (`graphitron-model.sql`, the `'graphql_'` insert) says its rows are what "any SDL reader could produce from the document without knowing graphitron exists". It says its verdict resident holds "the SDL toolchain's own verdicts". A refusal in graphitron's own words, which varies with `<schemaInput tag>` and with the federation library, is neither. The plan rewords the table and column comments but not three other places:
   - the family charter itself;
   - `diagnostic.variant`'s comment ("graphql-java's own error class name ... at every one of the three stages it records");
   - the stratum assignment in `fact-model.adoc`'s verdict paragraph.

   That paragraph puts `ASSEMBLY` in stratum two because every check it runs "is a predicate over captured rows". That stops being true once the assembled registry holds the federation library's definitions, which the store does not capture. `REWRITE` also needs a stratum of its own. By the page's own rule ("stratum one exactly while the store does not hold the inputs"), `MultipleFederationLinks` and `TagNotImported` are stratum two, since they can be recomputed from the `@link` entries and `store_graph_schema_input.tag`. `UnsupportedFederationVersion` and `LibraryDuplicateDeclaration` are stratum one. "Other solutions" turns down the view alternative without squaring that with the rule. *To satisfy:* pick one of two options and argue it. Either keep the refusals in `graphql_schema_problem` and restate the charter, the `diagnostic.variant` comment and the stratum paragraph, or give the refusals a relation in a family whose charter fits. I recommend the first, because `SchemaLifecycle` and the `diagnostic` arm then need no change, but the argument has to be in the plan.

3. **Nothing sweeps a `REWRITE` row once its refusal is fixed.** `GraphQLSchemaProblems.writeAssembled` sweeps the stages in `SchemaError.Stage.values()`. The plan sends the refusal around `SchemaError`, and it lists `SchemaError.Stage`'s javadoc for rewording without saying whether `REWRITE` becomes a constant there. If it does not, the row is never swept: an author who merges two `@link`s keeps the `REWRITE` row and reads `Previous` forever, which is this item's symptom coming back. *To satisfy:* state which stages the sweep covers after the change. Add a test where a corpus is captured with a refusal, the refusal is fixed, the corpus is captured again, and the `REWRITE` row is gone.

4. **The capture can still throw while it builds the attribution, and one document has no input.**
   - `SchemaInputAttribution.build` throws `SchemaInputException` when two inputs name one source. `SchemaRecipe.expand` does not remove duplicates, so a recipe whose two patterns overlap produces exactly that. Today only the generator builds this map. Building it in the capture moves the throw into the capture, which breaks "a refusal never throws in the capture". It is a sixth refusal the variant list does not name.
   - The bundled directives document (`SchemaLoader.DIRECTIVES_SOURCE_NAME`) is a `Stated` document that comes from no `SchemaInput`, so "each `SourceDocument.Stated` carries its `SchemaInput`" has no input to give it.

   *To satisfy:* say what the capture does with a source two bindings claim (a typed refusal, or a rule in `GraphQLSourceCapture` that settles it). Say what `Stated` carries for the bundled document.

5. **`readOnly()` does not give the copy the plan needs** (a fact for the "check at pickup" question). `ImmutableTypeDefinitionRegistry` copies the top-level maps with `ImmutableMap.copyOf`. It shares the extension maps' `List` values and the `SchemaParseOrder` with the source, and it cannot be mutated, so the rewrites cannot run on it. Running the rewrites on the original and keeping a `readOnly()` handle would not protect the decode either: `TagApplier` and `DescriptionNoteApplier` remove extensions from those shared lists. The copy has to be a fresh registry, for example a second `SchemaLoader.merge` over the same documents. The existing `preSynthesisRegistry` snapshot is safe today only because `KeyNodeSynthesiser` touches `types()` alone. *To satisfy:* name how the copy is built.

6. **Several capture fixtures hand-declare the federation directives, and the plan's full-path test picks one of them.** `FederationKeyDerivationTest`, `FieldSetDecodeTest`, `CaptureCorpusIsolationTest`, `EntryFamilyFixture` and `GraphitronSchemaEntriesTest` declare `directive @link` and/or `directive @key` beside a federation `@link`. That is the workaround this defect forced. After the change, each of them is a `DeclarationCollision` refusal that quietly exercises the fallback arm. The plan runs its full-path test on "the federated fixture `FederationKeyDerivationTest` or `FactCaptureAgreementTest` already captures". On `FederationKeyDerivationTest`'s fixture, "`graphql_schema_problem` is empty" fails. *To satisfy:* name `FactCaptureAgreementTest`'s `FEDERATED_FIXTURE`, which declares nothing by hand. Add a step that removes the hand-written declarations from the fixtures above, or say which ones keep them on purpose as collision cases.

7. **The tagged fixture never tags the capture, and the account of the test callers is inaccurate.** `PipelineCapturedStore.of(directory, sdl, tag)` puts the tag on the generator's `RunContext` but captures through `corpusOf`, an untagged `Binding.literal`. So `TaggedCaptureStampTest`'s assertion that "the tag put the synthesiser's @link extension in the registry capture walks" reads the generator's handle, not the capture's. Once the tag reaches the capture only through the recipe, `corpusOf` has to carry the tag, and that test's premise needs restating. Separately, the plan says the test callers "build their documents with an explicit untagged input" and that the model-tier tests "build their documents by hand". Neither is true. All thirteen callers get their documents from `GraphQLSourceCapture.capture` over a `SchemaRecipe`, and only `GraphQLSourceCapture` constructs a `SourceDocument`. So the callers need no change, and a model-tier `TagNotImported` case can be tagged through a `Binding`. *To satisfy:* correct the caller paragraph, add `PipelineCapturedStore.corpusOf` and `TaggedCaptureStampTest` to the plan, and restate why the full-path attribution test exists.

Non-blocking:

- `GraphQLRewriteGenerator.assembleAndCaptureVerdicts`'s comment ("the registry the store transcribes", `GraphQLRewriteGenerator.java:365`) belongs on the list of stale javadoc next to `AttributedRegistry`'s.
- `GraphQLAssemblyCapture.merge` is public and has no caller outside its class. Its javadoc argues it exists for the decode's driver. That driver now receives the merged registry from `capture`, so say whether `merge` stays.
