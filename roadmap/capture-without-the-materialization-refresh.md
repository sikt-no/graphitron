---
id: R865
title: "The generator owns the fact tier it should merely read"
status: In Progress
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-27
last-updated: 2026-09-02
---

# The generator owns the fact tier it should merely read

Graphitron reads three things about a consumer's project: their GraphQL schema, their jOOQ-generated
database classes, and their compiled Java classes. It writes what it learns into a small database we
call the **fact store**, and then generates code by asking that database questions.

Writing the store is meant to be the bottom layer, and generating code the layer above it. The
bottom layer should not know the top exists.

Right now it does, in three ways:

* The code that fills the store lives in the same module as the code that generates from it, so
  nothing stops one from calling the other except a reviewer noticing.
* The generator creates the store itself, so no one can hand it a store to use.
* The only way to fill a store is to run the generator, so anyone who wants the facts has to run the
  thing that consumes them.

This item fixes all three.

## Words used here

**Capture** is the pass that reads the three sources and writes what it finds into the store.

The **fact tier** is capture plus everything under it: the database schema that says what a fact is,
the queries that say what facts mean, and the code that reads and writes them.

The **generator** is what sits above: the planners that turn facts into a description of the code to
write, and the emitters that write it.

**Store creation** means opening the store file and deciding what to do when that goes wrong: when
the file cannot be opened, or when another checkout of the project is already using it. Today that
decision includes an ownership check, one retry, and a fallback to a temporary store held in memory.

## Why now

Nothing is broken today. This is debt we pay so the work after it is cheaper. Three costs it
removes:

**The layering is kept by hand.** Nothing stops the generator's code from calling into capture, or
capture from calling into the generator, except somebody spotting it in review. Once the two live in
different modules, the compiler refuses it and nobody has to watch for it.

**Two side modules depend on the generator when they should not.** The language server
(`graphitron-lsp`) and the MCP server (`graphitron-mcp`) both read the store. Neither one's shipped
code touches the generator, and both poms say they want to keep it that way. But both depend on the
generator in their tests, for one reason: the only way to build a store to test against today is to
run a generator.

This item removes that reason, and both modules drop the dependency completely. Step 1 counts what
each module actually uses, because two earlier drafts of this plan guessed and both guessed wrong.
The remainder is a handful of tests whose subject is two tiers agreeing with each other; step 8
moves those somewhere they can see both.

**You cannot get a store without generating code.** Anyone who wants to look at the facts, or
measure a query against a realistic store, has to keep a file left behind by some earlier run. That
is why the measurements in R876 rest on one saved file rather than on a store anyone can produce on
demand.

## What changes when this lands

**The fact tier moves into the `graphitron-model` module.** Moving down: `rewrite/capture`,
`rewrite/derive`, `JooqCatalog`, the SDL reader and its input family, the selection parser,
`rewrite/session`, `ClasspathScanner` and `CompletionData`, about 14,000 lines. Staying put: `plan`,
`render` and `command`, about 14,800 lines. After the move, generator code that calls into capture
does not compile.

**The generator is given a store instead of making one.** `GraphQLRewriteGenerator` stops passing a
directory to capture and starts being handed an open store. That is already how every fact reader in
`graphitron-lsp` and `graphitron-mcp` works. Opening the store becomes one entry point in the fact
tier, and the Maven goals call it.

**A new command, `mvn graphitron:capture`, fills a store and stops.** It reads the schema, classifies
it, writes the facts, commits, and does nothing else: no checks, no plan, no generated files. It
works even on a schema that would fail validation, which is the point of having it. Today the closest
thing is `mvn graphitron:validate`, and that command fills a store on its way to failing your build
over your schema. A command whose job is to produce something should not refuse because it disliked
the input.

**A dev session opens one store instead of two.** `DevMojo` already opens a long-lived store for the
language server, the MCP server and the diagnostics writers. Today every generator run inside that
session opens a second connection to it, because nothing can hand the generator the session's own
store. This is not a saving in disk or memory (H2 gives one process one database per file); it is
that the session and the run stop disagreeing about who owns the store.

**Both `graphitron-lsp` and `graphitron-mcp` stop depending on `graphitron` at any scope.** Most of
what their tests use moves down with the fact tier (`FactCapture`, `JooqCatalog`, `ClasspathScanner`,
`CompletionData`, `CompileFacts`, `CompileDiagnostic`, `CompileRound`, `LintConfig`, the rejection
vocabulary, and the `CapturedStore` / `FactWriters` test helpers). What is left is seven tests that
check two tiers against each other, and step 8 relocates them, along with `BuiltStore`, the one
helper that cannot follow the others down because a build is what it runs.

`graphitron-mcp`'s guard test `StoreClientBoundaryTest` widens from checking shipped code to checking
tests as well, and `graphitron-lsp` gets the same guard, which its pom asks for in a comment today.

## How we get there

The order matters, because each step makes the next one smaller. (Round 2's findings below cite the
step numbers as they stood before step 2 was inserted; the census they refer to is still step 1, and
everything they call step 6 or step 7 is now one higher.)

**1. List what moves, and settle the unclear cases.** Most of it is clear. Everything capture uses to
read the three sources moves with it, and so do the plain data types it copies into the store. Three
things have to be split instead of moved:

* `rewrite/catalog`. `CatalogBuilder.buildExternalReferences` reads the classpath, so it moves down.
  `projectTypesByName` and `TypeBackingShape` read the old schema walk, so they stay. Those two will
  disappear when the walk does, under R682, but they still have callers today
  (`TypeBackingProjectionTest`, and `graphitron-lsp`'s `R157PipelineTest`), so "will disappear" does
  not mean delete them now.
* `rewrite/lint`. `LintConfig` is just settings, so it moves down. The rules themselves stay: they
  analyse a schema, which is a job for the layer above.
* The third split was capture's one write that read the walk. R870 has deleted it (shipped at
  `9f50502`, and that item passed its Done gate at `dd8b5e7`), so `FactCapture.detect` writes
  nothing and there is nothing here to split.

**What the move set reads from the code that stays.** This is the census that decides whether the
move compiles, and it is the one an earlier draft replaced with a guess. Every import from the move
set (`rewrite/capture`, `rewrite/derive`, `rewrite/session`, `rewrite/selection`, `rewrite/schema`,
`rewrite/diagnostics`, the named `catalog`, `lint` and `compile` classes, `JooqCatalog` and
`RewriteContext`) that lands in code above the line was counted. **Exclude three files and every
remaining upward edge lands on something already moving down.**

That claim was too strong, and step 3's guard is what showed it: four files carried an edge this
census did not account for, two of them introduced by step 2 itself. The exclusions below are still
right and still the only three; what was wrong was the closing "every remaining". Step 3 records the
four and what each cost, and the move set that satisfies the boundary is 103 files rather than the 89
counted here, because `Rejection` is sealed and its closure has to travel with it. The three
exclusions:

* **`derive/ClaimDomain` and `derive/DemandResidue` stay above, so `rewrite/derive` does not move in
  one piece.** Both read `GraphitronSchema` and switch over `GraphitronType` arms, which is the walk.
  Both are main-source values whose only callers are three tests that stay above
  (`DemandShadowTest`, `TypeBackingClassesTest`, `FactCaptureAgreementTest`), and both say in their
  own javadoc that they retire with the shadow that reads them. Leave them where they are; R682 takes
  them.
* **`schema/federation/EntityResolutionBuilder` stays above, so `rewrite/schema` splits too.** It is
  the one file in `rewrite/schema` that reads the walked model (`TypeRegistry`, `GraphitronType` and
  eight of its arms, `ChildField`, `GraphitronField`, `EntityResolution`, `KeyAlternative`,
  `CallSiteCompaction`), and its one caller is `GraphitronSchemaBuilder`, which is the walk. The other
  three federation files move down: `KeyNodeSynthesiser` reads `NodeDeclaration` and
  `FederationLinkApplier`, and `FederationSpec` is read from below by `schema/input/TagLinkSynthesiser`.
* **`rewrite/model` is not a package that moves or stays as a unit.** The rejection vocabulary and the
  refs go down; the walked model stays. The line inside it is not new, it is just never stated: what
  capture and derive read from `rewrite/model` is exactly the half that is plain data.
* **`rewrite/session` splits, and only `SessionStateConfig` moves.** It is the authored settings, it
  imports nothing at all, and it is what `ConfigurationFactCapture` writes to `store_graph_session_mount`
  from. `SessionHooks` is the reflected signature that the same DDL comment says is never stored back,
  and every one of its readers is above the line, so it stays there with `SessionStateWarnings`. This
  is the exclusion that keeps `MethodRef` and its generic `TypeName` out of the move set, which is
  what makes step 2 tractable.

The four types the earlier draft flagged for confirmation come back below the line, as it guessed but
did not check. `ValidationError` and `Rejection` are read by six `derive` files and by
`rewrite/diagnostics`; the sealed interface and its ten arm files import nothing from the tree, so
that is a move rather than a split, and `rejection_validation_error` is already a table with the
language server reading the `diagnostic` view over it. `TableRef` and `ColumnRef` are read by
`JooqCatalog` and two `derive` files. They are not quite "plain data" yet, because they carry javapoet
types; step 2 makes them so before anything moves.

**`rewrite/diagnostics` moves down whole, and that is a correction to this plan rather than a
detail.** `FactCapture` reaches `derive/AuthoredClaimRejectionRows`, which calls
`RejectionFacts.classSpelling`, and "What is out of scope" below used to place `RejectionFacts`
above the line while "Sequencing" said R870 removed the last edge of exactly that kind. The
placement is what was wrong. All three files in the package (`RejectionFacts`, `BuildWarningFacts`,
`OwnedGraphPartition`) import only `FactCapture`, the rejection and warning vocabulary, and
`LintFix`, every one of which moves down; none reads the walk. They are fact writers, so they belong
in the fact tier, and `classSpelling` stays the one site that spells a rejection family. Their
callers stay where they are: `DevMojo` reaches them from `graphitron-maven-plugin` and
`FactCaptureAgreementTest` from `graphitron`, both downward.

The rest of the census is bookkeeping and is listed so the implementer does not rediscover it.
`RewriteContext` moves down rather than staying: it already imports `FactCapture.OutputCoordinates`,
`LintConfig`, `SchemaInput`, `SchemaRecipe`, `SessionStateConfig` and `DependencyVersions`, all of
which move, and it is a parameter of both `CatalogBuilder` methods that move. That also settles the
loose end step 8 leaves open about `FixtureCatalogTest`. Eight plain root-level types come down: `NodeDeclaration`, `ArgMappingSigil`, `RejectionKind`,
`ClasspathEntry`, `ValidationFailedException`, `SchemaParseException`,
`rewrite.dependency.DependencyVersions` and `rewrite.model.ConnectionNaming`. Seven import nothing
of their own; `NodeDeclaration` statically imports four directive-name constants (`DIR_NODE`,
`DIR_TABLE`, `ARG_NAME`, `argString`) from `BuildContext`, which stays. `BuildWarning`,
`LintRule` and `LintFix` come down as the values the round-1 census found them to be.

**What the two side modules actually use.** Neither module's shipped code uses the generator at all,
so this is only about their tests. Counted rather than guessed, because two earlier drafts guessed:

* **The fixture packages are not in `graphitron`.** The lsp tests use `rewrite.test.jooq`,
  `rewrite.test.services`, `rewrite.test.conditions` and `multischemafixture`, which read like
  generator packages and are not: they are generated or written in `graphitron-sakila-db` and
  `graphitron-sakila-service`. They cost nothing here.
* **The lint types the tests use are values, not the rule engine.** No lsp test runs a lint rule.
  They build findings and seed them into a store fixture, using `LintRule` (an enum of rule ids),
  `LintFix` (a record) and `BuildWarning` (a sealed interface of two arms). The same is true of
  `ValidationError`, `ValidationReport` and the rejection vocabulary: constructed, never executed.
  These belong with the diagnostics they describe, which is at or below the store, since
  `rejection_validation_error` is already a table and the language server reads the `diagnostic`
  view over it.
* **`DeprecationRecognizer` is a reader, not a rule.** `SdlDeprecations` uses it to read the
  deprecation markers out of the shipped `directives.graphqls`. It parses a `TypeDefinitionRegistry`
  and touches neither the walk nor the store, so by this plan's own rule it moves down with the
  other source readers. It sits in the `lint` package by naming accident.
* **`CatalogBuilder.build` is the method the splits above forgot.** `CatalogBuilder` has three public
  methods, not two: `buildExternalReferences` (down), `projectTypesByName` (stays), and `build`,
  which projects `CompletionData`. `CompletionData` moves down, so `build` goes with it, and
  `FixtureCatalogTest`'s use of it stops being a generator dependency.
* **Exactly one lsp test runs a real generator.** `StoreFixture.ofBuild` is the helper that calls
  `GraphQLRewriteGenerator.buildOutput()`, and across 75 lsp test classes it has one caller,
  `LintSuppressionDiagnosticsParityTest`. Every other test builds its store through `CapturedStore`,
  which moves down.
* **`graphitron-mcp` is the harder of the two, not the easier one.** Its own build-driven fixture,
  `StoreBackedBuild`, has four users: `GraphitronMcpServerTest`, `DiagnosticsAggregateTest`,
  `ServerInstructionsTest` and its own `LintSuppressionDiagnosticsParityTest`.

So after the moves above, seven test files still need both tiers: four in `graphitron-mcp`, and
three in `graphitron-lsp` (the parity test, plus `R157PipelineTest` and `FixtureCatalogTest`, which
are discussed in step 8 because their case is different). Those seven are the whole of what stands
between this item and a clean detachment of both modules.

**2. Take javapoet out of the fact tier's data, so it does not cross the line.** The refs capture
produces carry javapoet types: `ColumnRef` holds a `TypeName` beside its `String columnClass`,
`TableRef` holds three `ClassName`s, `ForeignKeyRef` holds one. `JooqCatalog` mints them from live
jOOQ reflection and `derive/StoreNodeTables` mints them from stored strings. A sixth carrier is one component of the
rejection vocabulary, `Rejection.AuthorError.TenantColumnTypeDisagreement.TableSite.declared`, a
fully qualified `TypeName` that no import betrays; it is built by `TenantScopeClassifier` above the
line and read only by `Rejection`'s own message rendering. Those six files are the whole of it. Left alone, they would hand `graphitron-model` a compile dependency on the module
that writes Java source, and `roadmap-tool` would inherit it.

**The store already holds these as strings.** Every `class_name` in the DDL is a `VARCHAR`, and the
decoder from that form exists and is already in use: `ColumnRef.decodeBindingType` rebuilds a
`TypeName` from the stored spelling, handling the array descriptor that `ClassName.bestGuess`
rejects. `StoreNodeTables` is the proof by construction, since it reads store rows and mints refs
from them today.

**Nothing below the line reads these values.** Thirty-eight files read the javapoet-typed accessors.
Exactly one of them, `JooqCatalog`, is in the move set, and it is the producer. So the typed
components come off the two records, the strings stay, and the thirty-seven consumers above the
line decode at their own read sites. Where a consumer decodes the same value repeatedly, the decode
belongs at one site above the line, not back on the record: the point is that deciding the Java
type is the emitter's job, and the fact tier's job is to write down the spelling it read.

**The two carriers that would have made this hard are not in the move set, and the census is what
shows it.** `MethodRef.returnType` is a generic `TypeName` from `getGenericReturnType()`, carrying
the parameterised shape (`List<Film>`) that no string in this tree round-trips today, and
`SessionHooks.Handled.handleType` is the same. Neither crosses: capture reaches `rewrite/session`
only through `SessionStateConfig`, which imports nothing at all and is the authored strings the
store holds. So `rewrite/session` splits, `SessionHooks` and `SessionStateWarnings` stay above with
the rest of the reflected model, and `MethodRef` never comes down. `store_graph_session_mount`'s own
DDL comment already draws that line: only the authored string lands there, and the reflected
signature is a build-time model fact never stored back.

This lands before the module move rather than inside it. It is an API change to two records with
thirty-seven read sites, and step 7 promises that the move changes no behaviour; folding the two
together would hide the real change inside the one that is supposed to be mechanical.

*Shipped.* Six carriers stripped, as round 3's corrected count said: `ColumnRef.columnType`,
`TableRef`'s three class names, `ForeignKeyRef.keysClass`, and
`Rejection.AuthorError.TenantColumnTypeDisagreement.TableSite.declared`. `JooqCatalog` carried more
than the refs and lost it too: `TableEntry`'s three accessors now answer names, and `RoutineParam`
and `RoutineResolution.Resolved` carry names rather than types. `FactTierJavapoetBoundaryTest`
replaces `ColumnTypeConstructorArityGuardTest`, whose subject the strip removes, and holds the
invariant over the whole move set rather than over one constructor's arity. The full build is green
and no generated file changed.

Three things the plan did not anticipate, each settled the way the tree already answers the
question rather than by adding a mechanism.

*The lift belongs beside the rows' lift, not beside the refs.* A first cut put it in `rewrite`, and
`PackageImportDirectionTest` refused it at twenty-one sites: `render` may read the borrowed refs and
nothing else of the legacy core. The rule is right and the placement was wrong. `render.CatalogRefs`
already existed as "the one place a captured name becomes something javapoet can spell", for the
command tier's rows, and the refs now carry names for the same reason the rows do, so the two lift
in one place. `TableRef`'s own javadoc had already named the failure mode: do not grow a third
mechanism.

*The decode was missing a case the boundary had for free.* `TypeName.get(col.getType())` handles a
primitive column because a live `Class` knows it is one; the captured name is the string `"int"`,
which `ClassName.bestGuess` rejects, so a first cut typed a primitive tenant column as null and
`TenantAcquisitionFragmentsTest` caught it. The decode now reads the primitive spellings
`Class.getName()` produces. Worth stating because it is the one real hazard in this shape of change:
a decode from a name has to accept every name the reflection form can produce, and a gap in it is
silent until something reads the type.

*`ForeignKeyRef` never needed a lift.* Its only consumer sits in `render/JoinFragments`, beside a
line that already lifted the command row's spelling of the same fact through `className`. Adding a
`keysClass` overload would have meant widening the borrow dial for a method that hides a field read,
so the overload went and the two adjacent lines now read alike.

*Two findings for step 7, recorded here because the strip is where they surfaced.* Neither changes
step 2, and step 3 settled both: the eight same-package edges resolved into the `Rejection` closure
coming down whole, and the `TypeConflict` arm took the third of the three options below.

* **An import-based census cannot see a same-package reference, and `rewrite/model` splits.** Both
  round 2's census and the author's ran over import statements, so every edge between the fifteen
  files placed below the line and the hundred and twenty-five staying above is invisible to them.
  Counted over code with comments stripped, there are eight: `Rejection` reaches `ConflictSite`,
  `DomainReturnType`, `GraphitronField` and `ProducerBinding`; `DeleteRowsError` and
  `UpdateRowsError` reach `MatchedKey`; `ReflectionError` reaches `On`; `ServiceCarrierShapeError`
  reaches `Arity`. Step 7 settles each the way step 1 settled the rest.
* **`Rejection` reaches the generic-`TypeName` family through `ConflictSite`.** `ConflictSite`
  carries a `TypeName declared` built from `MethodRef.Param.Typed`, which is the family step 1 keeps
  above the line for the reason step 2 restates: no string in this tree round-trips `List<Film>`. So
  the `TypeConflict` arm is the one place where the plan's two halves meet, and step 7 has to say
  whether the arm moves, the site moves, or the declared type becomes a name of its own.

**3. Add a temporary boundary guard, so the layering holds until the move happens.** Assert over the
move set that nothing in it reaches upward into the generator, with graphql-java written as an
allowance rather than a list of exceptions: the consumer's schema is one of the three things capture
reads, so a module that defines what a schema fact is cannot sensibly be unable to parse a schema.
This is a stand-in for the module boundary, not a rival to it, and step 7 deletes it.

*Shipped, and larger than this step was written to be.* Two departures from the plan, both because
running the check is what showed the shape.

*It is three properties, not one, and only the first is visible to a reader scanning imports.* No
javapoet (step 2's rule, already enforced); no upward import; and no sealed type that moves while one
of its arms stays behind. The third is not a style question. A sealed type's permitted subclasses
must share its package when the declaring class is in an unnamed module, which every module here is,
so a straddling seal is a compile error at move time. An import scan cannot see it, because the arms
sit in the declaring type's own package and need no import. That check is why the move set now
carries the whole `Rejection` closure rather than `Rejection` alone.

*The guard lives with the move set, not in `PackageImportDirectionTest`.* This step was written
before step 2 created the manifest. `PackageImportDirectionTest`'s rules scan a top-level package
root and take an import predicate; the fact tier is a file set spanning eight packages, half of them
split. Satisfying the letter of this step would mean copying a hundred-file manifest into a second
test so two tests could disagree about what moves. The manifest has one home, and the boundary guard
reads it.

*What the guard found, which step 2 had missed.* Step 1's census closed on the claim that excluding
three files leaves no unaccounted upward edge. That was false, and the guard names the four files it
was false for. Two were step 2's own: `ColumnRef` and `JooqCatalog` were left importing
`render.CatalogRefs` for javadoc that refers to it in `{@code}`, so the imports were dead and the
upward compile edge was real. The other two were the plan's.

*The rejection vocabulary now carries names, on the same rule as the refs.* `RejectionFacts` reached
ten `Rejection.AuthorError` arms above the line, and `Rejection` is sealed over them, so the closure
had to come down: eleven files, plus the six types its arms carry. Three of those six carried
javapoet or an upward edge, which is where step 2's decision and step 1's move set met. The finding
recorded under step 2 posed the question as "whether the arm moves, the site moves, or the declared
type becomes a name of its own", and the answer is the third, four times over:

* `ConflictSite` carried a `Site` sealed over `MethodRef` and `ServiceMethodCall` purely to project
  two strings, which its own javadoc said ("so the renderer need not switch on the arm"), and
  `Rejection`'s message renderer was the only reader. It is now the coordinate and the declared
  type's spelling, three names. The sealed `Site` stays above the line on
  `ResolvedContextArg`, which is the consumer that genuinely wants the model value, for LSP
  navigation.
* `MultiProducerDomainTypeDisagreement.Participant` carried a `DomainReturnType` and used it only as
  `toString()`. It carries the claim's own statement, so `DomainReturnType` (157 lines, javapoet on
  two arms) never moves.
* `RecordBindingMultiProducer` carried `ProducerBinding` and used only `describe()` and
  `reflectedClass().getName()`. It carries those two as a named `Binding`, so `ProducerBinding` (265
  lines) never moves. Its `reflectedClass` is a live `Class`, which could not have survived a stored
  row in any case.
* `StubKey.VariantClass` carried a `Class<? extends GraphitronField>` and every reader wanted the
  spelling, which is what `intent_authored_claim_rejection.variant` holds. It carries the spelling,
  and `classSpelling` moves to `Rejection` where the hierarchy it spells lives. `GraphitronField` is
  sealed over `OutputField` and `InputField`, so this is what keeps the entire field taxonomy from
  being dragged down behind a rejection.

Each of the four is the same shape as step 2's lift, and each was found by asking what the consumer
actually reads rather than what the component is declared as. The pattern is worth stating for step
7: a rejection is a fact the store holds, so it carries what was found spelled out, never the model
value it was found on.

*The directive vocabulary follows the reader, which the tree already does.* `NodeDeclaration` read
`DIR_NODE`, `DIR_TABLE`, `ARG_NAME` and `argString` from `BuildContext`, 3403 lines above the line.
`BuildContext` already delegates seven directive names to the `facts` visitor that reads each one,
with the comment "each name has one home on its visitor", so the rule was in the tree and this is
one more application of it: the three names have their home on `NodeDeclaration`, the one place that
reads them, and `BuildContext` re-exports. `argString` and `argStringList` move to a `DirectiveArgs`
in the move set, with `BuildContext` delegating so its ten callers above the line do not change.

The move set is 103 files, and clean on all three properties. `rewrite/model` contributes 18 rather
than the 5 step 1 counted.

*One blind spot left for step 7, measured rather than guessed.* A javadoc `{@link}` to a
same-package type needs no import, so it is invisible to the import check for the same reason a
`permits` clause is, and the two split packages make it reachable: 22 move-set files carry 42 link
targets that name a type staying above the line. `Rejection` alone has eight (`DomainReturnType`,
`GraphitronField`, `GraphitronType`, `MethodRef`, `OrderBySpec`, `OutputField`, `ParamSource`,
`ProducerBinding`), which is the residue of the four lifts above: the components left, and the prose
explaining why still names them. These compile today and break at move time, when the javadoc
reference gate runs against a module that cannot see `graphitron`. Enumerating them is mechanical
(resolve each link's leading simple name against the declaring file's own package directory, and
flag it when that sibling is not in the move set); deciding each one is not, so step 7 owns it. A
link whose target is genuinely another module's internals after the move is the one case
`CLAUDE.md` allows downgrading to `{@code}`, and most of these are that case; the rest want
repointing or restating.

No guard for it here, deliberately. The only shape that could pass today is a ratchet on the count,
and this file's siblings are written to be checkable from the first file rather than ratcheted.

**4. Take store creation out of capture.** `FactCapture.runInternal` today opens the store, reports
what the cleanup sweep deleted, checks whether this project may write under its graph name, retries
once if the write fails, and falls back to a temporary in-memory store with a warning. All of that
becomes its own entry point that returns one of two answers: `Shared(handle)`, meaning the run got
the real store, or `Demoted(handle, reason)`, meaning it got a temporary one and here is why. The
caller then has a plain answer to work with instead of a value that might be null and a log line to
match it against.

The ownership check stays in the fact tier rather than moving up to the Maven goal, for the reason
`ownsGraph` already gives in its own javadoc: it needs the store open and the row readable, and the
goal never reads the store. The retry logic moves with it, for the same reason.

**5. Hand the store to the generator.** `captureAndRead` and `captureFacts` take the store the entry
point returned, instead of the directory in `ctx.storeDirectory()`. `RewriteContext` keeps the
directory, because the Maven goals still need it as a setting. What goes away is the generator
running with no store at all: that stops being possible.

**6. Add the command.** `CaptureMojo` copies the shape `ValidateMojo` already has: 34 lines whose
body is a single `runGenerator` call, with `AbstractRewriteMojo.runGenerator` doing the setup. Like
`validate`, it does not require the output and jOOQ package settings (`packagesRequired()` returns
`false`). When it falls back to a placeholder package, it warns, because such a run writes no `sql_`
rows and a store with no database facts in it is not much use.

Inside the generator, capture-only is a fifth `Projection` of the existing `runPipeline`, not a
second copy of the pipeline. The class javadoc asks for exactly that, and says a second copy is the
mistake the design exists to prevent. The existing stage order makes it cheap: everything the
command needs already runs before the capture, and everything it does not need runs after. Lint is
the one exception, since it runs before the capture today, so the projection needs a switch for it.
Validation needs no switch, because it runs after the capture and the projection simply returns
first.

**7. Move the modules.** Nothing changes behaviour here: no table changes shape, no generated file
changes, no query answers differently. Keep moves and behaviour changes in separate commits. The
test schemas in `graphitron/src/test/resources/corpus` move with capture and are shared back up as a
test-jar, for the planner and emitter tests that use them. `CapturedStore` and `FactWriters` move the
same way, and for a stronger reason: they are how a test gets a filled store, which is the thing
being moved. Leaving them behind would keep `graphitron-mcp` depending on the generator for a test
fixture after every other reason had gone.

`BuiltStore` is the exception, and an earlier draft had it wrong: it runs
`GraphQLRewriteGenerator.buildOutput()`, so it cannot live below the generator any more than the
generator can. It stays in `graphitron`'s test-jar, where the build is. That is not a leak, because a
store filled by a build is a two-tier object and belongs with the two-tier tests; step 8 is where its
users end up.
`FactCaptureAgreementTest` stays where it is: it compares capture's output against the old walk, and
a test comparing two layers belongs in the upper one.


**Where the moved files land.** The move set leaves `no.sikt.graphitron.rewrite` behind and lands
under `no.sikt.graphitron.model`, the destination module's existing root. Dropping the old root is
not cosmetic. Eight of the source packages split across the line rather than moving whole: `model`
(5 of 140), `derive` (14 of 16), `compile` (3 of 12), `lint` (4 of 10), `catalog` (2 of 4),
`dependency` (1 of 4), `schema/federation` (3 of 4), `session` (1 of 3). Keeping the root would
declare each of those eight in both modules, and the files left behind would hold same-package
access to the ones that moved, so the compiler would enforce nothing. That is the boundary this item
exists to create. Renaming the root makes `no.sikt.graphitron.rewrite.derive` and
`no.sikt.graphitron.model.derive` distinct packages, and the split disappears by construction.

`graphitron-model` has no files loose in its root; all six of its existing packages are job-named.
The move honours that, so the ten types currently loose in `rewrite/` each acquire a job package.

**One package per gatherer.** The capture body's own comment already calls them gatherers. Each gets
a package, and a file sits inside that package when no other gatherer reaches it and nothing outside
the fact tier names it. Everything else is tier vocabulary and lands in a package that belongs to no
gatherer. The rule is mechanically checkable, and the move brings its own guard to check it: a test
in `graphitron-model`'s own test sources, asserting that no file in a gatherer package is named from
another gatherer package or from outside the fact tier. It does not extend step 3's rule, which is
about the tier boundary and lives in `graphitron`; a test up there walking this module's internals is
the cross-module source read this item exists to end.

| package | gatherer | private helpers it takes |
|---|---|---|
| `model.capture` | `FactCapture`, the orchestrator | `FactSink`, `FactWrites`, `ClasspathSources`, `GraphSourceMembership`, `StoreRefresh` |
| `model.capture.graphitron` | `GraphitronFactCapture` | `FieldSetGrammar` |
| `model.capture.sdl` | `SdlFactCapture` | `SdlCoordinates`, `SchemaInputException` |
| `model.capture.config` | `ConfigurationFactCapture` | `StoredRecipe` |
| `model.capture.catalog` | `CatalogFactCapture` | none |
| `model.capture.verdict` | `SdlVerdictCapture` | none |
| `model.capture.java` | `JavaSourceFacts` | none; `SourceWalker` has two readers above the line |
| `model.capture.compile` | `CompileFacts` | none; `CompileRound` has four, `CompileDiagnostic` two |
| `model.capture.macro` | `MacroCapture` | none; `ConnectionNaming` is read by `ConnectionPromoter` |

Four of the nine hold only the gatherer itself. That is the rule's honest result and not a defect in
it: those four have no private helpers, because every type they touch is read above the line too. The
same census locates the fact tier's public surface, which is `JooqCatalog` plus the three refs, the
rejection vocabulary, and `schema/`.

The vocabulary packages take the rest. `model.jooq` gets `JooqCatalog`, `ColumnRef`, `TableRef` and
`ForeignKeyRef`; the jOOQ reader is deliberately not put beside `model.catalog`, which is the fact
store's own catalog, because the two catalogs are the confusion this layout exists to clear.
`model.diagnostics` gets the rejection vocabulary (`Rejection`, `RejectionKind`, `ValidationError`,
`BuildWarning`, `ValidationFailedException`, `SchemaParseException`) alongside `RejectionFacts`,
`BuildWarningFacts` and `OwnedGraphPartition`. `model.schema`, `model.schema.input` and
`model.schema.federation` keep their leaf names. `model.derive` merges with the five files already
there, which is a reunion rather than a collision: `ArgMappingCandidates` and
`MaterializeDependencies` are already described as capture-cadence writers of derived rows, which is
what `StoreDetections`, `TypeBackingRows` and `ResolvedKeyProjections` are. `model.grammar` gets
`NodeDeclaration`, `ArgMappingSigil` and `ConnectionNaming`, joining the sigil and name grammars
already in it. `model.config` is the one new package: `RunContext`, `SessionStateConfig`,
`DependencyVersions` and `ClasspathEntry`, which is what stops `session` and `dependency` from
arriving as one-file packages. `model.selection` and `model.lint` keep their leaf names, and
`ClasspathScanner` with `CompletionData` go to `model.classpath`.

**What the census changed about this step.** Two things a package-by-package reading of the move set
does not show:

* `schema/`, `schema/input/` and `schema/federation/` are not capture machinery. No gatherer reaches
  most of them. `DirectiveSupportTypes` is read by `InputTypeGenerator`, `SchemaSdlEmitter` and
  `TypeBuilder`; `OneOfDirectiveSdl` by `EmitPlan` and three generators; `KeyNodeSynthesiser` by
  `SchemaReachability`, `AttributedRegistry` and the generator entry point; all three appliers by the
  generator entry point. They still move down, because the generator reading downward is the
  direction this item wants, but they are shared vocabulary and belong in no gatherer's package.
* The nine-file selection parser has exactly one consumer outside `GraphitronFactCapture`, and it is
  a single file above the line, `ArgBindingMap`. Under the rule that makes it `model.selection`
  rather than gatherer-private. Whether `ArgBindingMap` should be parsing selections at all is a
  separate question and not this item's.

`no.sikt.graphitron.facts` already exists above the line, and its eighteen `*FactVisitor` classes are
described as gathering facts, so "gatherer" is live vocabulary on both sides of the line. Keeping
capture's under `model.capture.*` keeps the two readable apart.

**Two renames travel with the move.** `RewriteContext` becomes `RunContext` and `RewriteSchemaLoader`
becomes `SchemaLoader`. Both move down, so their package declaration is being rewritten anyway and
the rename is free here. The `rewrite` vocabulary that stays above the line is a pure rename with no
module boundary in it: `GraphQLRewriteGenerator`, `AbstractRewriteMojo`, `RewriteResult`, the
`preRewriteSchema` parameters, and the 296 files that keep `no.sikt.graphitron.rewrite.*`. That is
filed separately. Doing it here would put a reactor-wide rename in the same commit as a module move
and make both unreviewable.

**8. Rehome the tests that need both tiers.** Seven files, in two kinds.

*Five that check the build and a client agree.* `LintSuppressionDiagnosticsParityTest` exists twice,
once in each client, and asks the same question: if you switch a lint rule off in your build
settings, does the editor stop squiggling it, and does a rule you did not switch off still show?
Three more mcp tests use the same build-backed fixture (`GraphitronMcpServerTest`,
`DiagnosticsAggregateTest`, `ServerInstructionsTest`) to check what the server reports against real
build output. None of them can be faked from either side: a lint finding only exists once a build
has run the rules, and the squiggle only exists once the client has read it back. These tests are
worth keeping for as long as there is a build and an editor.

They need a home that can see the generator and the client at once. `graphitron-maven-plugin` is
one, today, for free: it already depends on `graphitron`, `graphitron-lsp` and `graphitron-mcp`,
because `DevMojo` is what wires them together, which is also the thing these tests are really about.
The alternative is a new module that exists only for cross-tier tests, which is cleaner and is scope
of its own. **This spec picks the maven plugin, and a reviewer who prefers the new module should say
so.** Either way the rule is the same and worth stating once: a test whose subject is two tiers
agreeing belongs above both of them, never inside one of them reaching up.

**What travels with them.** Each of these tests sits on a fixture that is private to its own module's
test sources, and neither client publishes a test-jar today. Split each fixture at the seam it
already has rather than publishing two new test-jars: the build-driving half moves to the plugin
(`StoreFixture.ofBuild` and `StoreBackedBuild`, both of which are already a `BuiltStore` run behind a
client-shaped front), and the store-only half stays behind, serving the tests that are not moving.
`StoreFixture` is built for this: it holds `captured` and `built` as two fields rather than one
flagged field, precisely because a build-filled store and a capture-filled one are different objects.
The client-side support a relocated test still needs (`WorkspaceFileTestSupport` is the one on the
lsp side) travels with it. Publishing a client test-jar would work too and is the implementer's call
if the split turns out to cost more than it saves; what must not happen is a client keeping a
generator edge alive to serve a test that no longer lives there.

*Two that are about the retiring walk.* `R157PipelineTest` runs a real schema through the real
classifier and then checks the editor's completions, hovers and diagnostics against what the
classifier decided, so that a classifier that quietly widened would be caught by an editor
assertion. `FixtureCatalogTest` does the catalog half. Both depend on machinery R682 removes, so
they have an end date the parity tests do not. Move them by the same rule as the five, and expect
them to shrink rather than to be maintained: once `CatalogBuilder.build` is below the line,
`FixtureCatalogTest`'s remaining tie is `RewriteContext`, and step 1's census settles that one by
moving `RewriteContext` down too, so the test may not need rehoming at all.

## Decisions this spec makes

**One module, not two.** The alternative is a new `graphitron-capture` module between the store and
the generator. The database schema settles it: a table added to `graphitron-model.sql` does nothing
until capture writes to it, and capture writing to a column the schema does not declare does not
compile. The two halves always change together, so splitting them only lets one half land without
the other.

**The refresh is left alone.** After capture writes facts, it refreshes the pre-computed tables that
readers use. That keeps working exactly as it does now, and nothing here gives anyone a way to get a
store whose pre-computed tables are out of date. If a refresh is slow enough to want to skip, the
pre-computed table should not exist in the first place, which is R876's question and R899's after it.

**`graphitron-model` gains a GraphQL parser, and that is correct.** The consumer's schema is one of
the three things capture reads, so a module that defines what a schema fact is cannot sensibly be
unable to parse a schema. Its description changes from "the fact database and its bootstrap" to what
it will be: the whole fact tier.

**`graphitron-javapoet` does not follow the fact tier down, and step 2 is what makes that true.**
The parser is one thing and a code writer is another. The fact tier reads a consumer's sources and
writes down what it found, so it needs to parse a schema; it does not need to model Java syntax, and
a module named for writing Java source has no business under a fact database. The store agrees:
every class name in the DDL is a `VARCHAR`.

What made this a decision rather than an observation is that five files in the move set carry
javapoet types today, so the dependency would come down with them unless something stops it. Step 2
stops it, and the count is what makes it cheap: nothing below the line reads those values, thirty-
seven of the thirty-eight readers are above it, and the decoder from the stored string already
exists. The alternative was to carry the dependency and file the strip as a follow-up, which was
rejected: a follow-up that removes a dependency the spec has just finished justifying is a
follow-up that does not happen.

**The move set leaves the `rewrite` package name behind, and the files that stay keep it.** Eight
source packages split across the line, so keeping the root would declare each of them in both
modules and hand the leftovers same-package access to what moved. The compiler would then enforce
nothing, which is the boundary this item exists to create; step 7 carries the count. The same
reasoning does not reach the 296 files that stay above the line: renaming those is a reactor-wide
rename with no module boundary in it, and it is filed separately rather than ridden along.

**Each gatherer gets a package, and what only it uses goes in that package.** The alternative is one
flat `model.capture` holding eighteen files, which is what exists today and which hides who reads
what. The rule (a file sits in a gatherer's package when no other gatherer reaches it and nothing
outside the fact tier names it) is worth more than the tidiness: it is checkable, step 3's import
test checks it, and running it over the move set is what surfaced that `schema/` is not capture
machinery at all. Four of the nine packages come out holding only their gatherer, which the spec
accepts rather than papers over.

## What is out of scope

**Writes from above.** The module boundary stops the upper layer from *reading* the lower one's code.
It does not stop the upper layer from *calling down* to write to the store: `DevMojo` drives
`CompileFacts`, `RejectionFacts`, `BuildWarningFacts` and `OwnedGraphPartition` during a dev session,
and those four writers move into the fact tier with everything else that writes a table. What stays
possible afterwards is the call, which is downward and so is not what the boundary is about.
Whether a given write is fine or a mistake is a question about when it runs, not about who imports
whom, and this item does not answer it. Any documentation that lands with the move should say the
compiler-enforced rule is about imports.

**`roadmap-tool`'s dependencies.** It depends on `graphitron-model` only, and will pick up
graphql-java, slf4j and the javac Tree API when that module grows. That is a build-time cost on a
build tool, and we accept it. Untangling it is a separate problem and should not shape where this
line goes.

**The dev session's extra refresh at startup.** R857 removes that call. This item removes the reason
it was needed, which is not the same thing as removing it.

**The `rewrite` package name above the line.** Step 7 drops it from the 89 files that move, because
keeping it would cost the boundary. The 296 that stay keep `no.sikt.graphitron.rewrite.*`, and so do
`GraphQLRewriteGenerator`, `AbstractRewriteMojo`, `RewriteResult` and the `preRewriteSchema`
parameters. That is R911, and it is separate on purpose: it is a reactor-wide rename with no module
boundary in it, and putting it in the same commit as a module move would make both unreviewable.

## Sequencing

**R870 is Done, and the dependency is discharged.** Capture used to write one table,
`walk_type_backing_class`, from the schema walk above it, and that call could not have survived the
module move. R870 deleted the table and the write on its own merits, so the edge is gone from the
tree and `depends-on` is empty. Nothing here waits on it any more.

**R876's work should land before the move.** It is adding code to the very packages this relocates.
Nothing actually clashes, since this move does not change what any file does, but the two will
collide as edits. Ordering them is cheaper than coordinating them. Whoever starts the move while one
of R876's slices is in flight should say so rather than rebase through it.

**Do this before R682, not after.** R682 is a large clean-up of the middle layer, still in progress.
Waiting for it means the boundary that would protect it does not exist while it happens, and every
new table added meanwhile is one more thing to argue past the line later. R682 is not blocked by
this: it clears out the middle either way, and this decides where the line sits, not what stands
above it.

## How we will know it is delivered

* **`mvn graphitron:capture` on `graphitron-sakila-example` produces a store and nothing else.** No
  generated files, no validation report, no plan. Open the store afterwards and find a non-zero
  number of graphs and fields in it.
* **The command works on a schema `validate` rejects.** Point it at a test schema that fails
  validation, and find that schema's facts in the store along with the recorded reasons it was
  rejected.
* **The command's store matches a normal run's.** Capture the same test schema both ways and check
  that every table capture writes holds the same rows, pre-computed tables included.
* **`graphitron-model` compiles with the fact tier in it and no dependency on `graphitron`.** The
  build proves this by itself: a circular dependency between modules does not build.
* **`graphitron-model` declares no dependency on `graphitron-javapoet`**, and no file under it
  imports one. The pom is the check, and it is the whole of what step 2 is for.
* **`GraphQLRewriteGenerator` no longer imports `FactCapture`**, and nothing in `graphitron`'s
  shipped code opens a store. Both are tests, not something a reviewer has to check. Keep the second
  test scoped to that one module: `graphitron-model` legitimately keeps two ways of opening a store
  that this item does not touch, `ModelCodegenDriver` and the store's own startup code.
* **Neither `graphitron-lsp` nor `graphitron-mcp` declares a dependency on `graphitron`, at any
  scope.** Both poms lose it entirely. `graphitron-mcp`'s guard test
  `StoreClientBoundaryTest.noGeneratorReferenceInMainSources` widens to cover tests, and
  `graphitron-lsp` gains the same guard.
* **The seven cross-tier tests still run, still prove the same things, and live above both tiers.**
  In particular both `LintSuppressionDiagnosticsParityTest` cases still fail if a build-suppressed
  lint rule reaches the editor or the MCP diagnostics tool. Relocating a test must not quietly weaken
  it: if a test cannot be moved without dropping an assertion, that is a finding, not a detail.
* **A generation runs against a store its caller opened**, and the fallback case is tested too:
  pointed at a store another project owns, the run gets the temporary store with a stated reason,
  finishes normally, and leaves the shared file untouched.
* **The full build is green and `graphitron-sakila-example` generates identical files.** If an
  emitted file changed, the move did something more than move.

* **Each gatherer's package holds only what that gatherer uses.** The guard test in
  `graphitron-model` fails if a file in a gatherer package is named from another gatherer package or
  from outside the fact tier. Four of the nine packages are expected to hold only their gatherer;
  that is the census result, not a gap to close, and a reviewer should not read a thin package as an
  unfinished one.
* **No package is declared in both `graphitron` and `graphitron-model`.** Eight of the source
  packages split across the line, so this is the check that the rename actually bought the boundary
  rather than just moving files. A single split package would give the leftovers same-package access
  to what moved.

## Retired vocabulary

Declared for the retirement sweep at the Done gate. Retired by step 2:

* `ColumnRef.columnType`, `ColumnRef.decodeBindingType`, `ColumnRef.bestGuessScalarTypeOrNull`
* `JooqCatalog.ColumnEntry.columnType` and its four-argument auxiliary constructor
* `TableRef.tableClass` / `recordClass` / `constantsClass`, now `...ClassName`
* `JooqCatalog.TableEntry.tableClass` / `recordClass` / `constantsClass`, now `...ClassName`
* `ForeignKeyRef.keysClass`, now `keysClassName`
* `JooqCatalog.RoutineParam.type`, now `typeName`; `RoutineResolution.Resolved.routinesClass`, now
  `routinesClassName`
* `ColumnTypeConstructorArityGuardTest`, whose subject the strip removes, replaced by
  `FactTierBoundaryTest`

Retired by step 3:

* `ConflictSite.Site` and its three arms, and both `ConflictSite.of` factories; the sealed
  coordinate now lives on `ResolvedContextArg.Site`
* `ConflictSite.declared` as a `TypeName`, now the spelling; `ConflictSite.site()`, now
  `className()` / `methodName()`
* `Rejection.AuthorError.MultiProducerDomainTypeDisagreement.Participant.domainReturnType` as a
  `DomainReturnType`, now the claim's own statement
* `Rejection.AuthorError.RecordBindingMultiProducer.bindings` as `List<ProducerBinding>`, now
  `List<RecordBindingMultiProducer.Binding>`
* `Rejection.StubKey.VariantClass.fieldClass`, now `variant` carrying the spelling
* `RejectionFacts.classSpelling`, now `Rejection.classSpelling`
* `BuildContext.DIR_TABLE` / `DIR_NODE` / `ARG_NAME` as the names' home, now re-exports of
  `NodeDeclaration`'s; `BuildContext.argString` / `argStringList` as the decoding, now delegations
  to `DirectiveArgs`
* `FactTierJavapoetBoundaryTest`, renamed `FactTierBoundaryTest` when it grew the other two
  properties

* `RewriteContext`, now `RunContext`; `RewriteSchemaLoader`, now `SchemaLoader`
* every `no.sikt.graphitron.rewrite.*` package name for the 89 files that move, now
  `no.sikt.graphitron.model.*`

## Reviewer findings

### Round 1 (2026-08-31, Spec -> Ready, reviewer session 018HhYy8H1gBKaAXg17ZbvmS)

Verdict: withhold. One blocking finding on question one. Question two is clean.

*What was checked and holds.* Every symbol the plan names exists under the name it gives.
`FactCapture.runInternal`, `captureWithRetry`, `reconciles` and `ownsGraph` are all there and do
what the plan says they do, including the fallback to a private in-memory store and the per-attempt
`reconciles` call. `GraphQLRewriteGenerator.captureAndRead` and `captureFacts` take
`ctx.storeDirectory()`, `RewriteContext` carries `storeDirectory` as a component, and `Projection` is
a private record with exactly four constants (`GENERATE`, `VALIDATE`, `BUILD_OUTPUT`, `PASS`), so
"a fifth `Projection`, not a second pipeline body" is both available and the thing the class javadoc
already asks for in those words. The stage-order claim checks out against `runPipeline`:
`withLintFindings` is computed above the capture and does need a switch, `CatalogBuilder.build` is
already projection-gated, and `GraphitronSchemaValidator` runs inside the capture window's
continuation where a capture-only projection can return ahead of it. `ValidateMojo` is 34 lines over
one `runGenerator` call with `packagesRequired()` returning false, and `AbstractRewriteMojo.runGenerator`
owns the context build, so the `CaptureMojo` sketch is the shape claimed. `DevMojo` opens `sessionStore`
at line 299. `CatalogBuilder.buildExternalReferences` and `projectTypesByName`/`TypeBackingShape` exist
and split as described, with the live callers named. `StoreClientBoundaryTest` is main-sources-only with
an artifact-and-scope allowlist carrying `graphitron` at test and test-jar, so "tightens to all scopes"
is a real edit to a real guard. `PackageImportDirectionTest` has the `facts` arm and the borrow dial the
plan writes the new arm against. `ModelCodegenDriver` and `GraphitronModelStore.open`/`openAt` are the
two openers the guard must be scoped around. The move and stay line counts are in the neighbourhood
claimed. Every roadmap item cited exists: R870 (Spec), R876 (In Progress), R857 (Spec), R682
(In Progress), R899 (Backlog). The mcp import census is accurate.

The diagnosis is good and the shape is right. Three deliverables that are one ownership inversion,
sequenced so each step shrinks the next, extending mechanisms already in the tree (a projection, a
mojo shape, a package-rule arm, a sealed outcome) rather than standing anything parallel beside them.
The "one module, not two" decision is argued from the DDL rather than from taste, and the write
direction is correctly scoped out with a reason rather than an omission. I would hand this to an
implementer once the finding below is settled.

**Finding 1 (question one: is the stated outcome reachable). The plan promises that `graphitron-lsp`
drops its `graphitron` edge in every scope, and its own splits keep that edge alive.** The promise
appears twice, as the second of the three costs under "Why now" ("Remove that requirement and both
modules drop the dependency in every scope") and as a delivery criterion ("`graphitron-lsp` and
`graphitron-mcp` declare no dependency on `graphitron` in any scope"). Both rest on a census of what
`graphitron-mcp` imports. No equivalent census is offered for `graphitron-lsp`, and the one in the
tree does not support the claim.

Ten test files under `graphitron-lsp/src/test` import types this plan explicitly leaves above the
line. `LintQuickFixTest` imports `no.sikt.graphitron.rewrite.lint.LintRule` and `LintFix`, and
`SdlDeprecations` imports `DeprecationRecognizer`: that is the lint rule engine, which the census in
step 1 decides "stays above as analysis over a read schema". `FixtureCatalogTest` imports
`no.sikt.graphitron.rewrite.catalog.CatalogBuilder`, and `R157PipelineTest` imports `CatalogBuilder`
and `TypeBackingShape`: those are the half of `rewrite/catalog` that step 1 keeps above, and step 1
names `R157PipelineTest` itself as the live caller that is the reason not to delete them now. Also
above, or unassigned by the plan either way: `GraphitronSchemaBuilder` (`R157PipelineTest`),
`ValidationReport` (`DiagnosticsTest`, `FixtureCatalogTest`), `BuildWarning` (`StoreFixture`,
`LintQuickFixTest`) and `GraphQLRewriteGenerator`.

So this is an internal contradiction rather than an unchecked claim: step 1 argues for keeping symbols
above the line partly because `graphitron-lsp` calls them, and the delivery section then asserts that
`graphitron-lsp` will name nothing in `graphitron`. An implementer reaching the last two criteria has
to choose between moving the lint rule engine down (which step 1 forbids), relocating or deleting those
`graphitron-lsp` tests (which the plan never mentions and which is scope of its own), and weakening the
criterion. That choice is design, and it is the author's rather than the implementer's.

What would satisfy the finding: run the same census over `graphitron-lsp` that "What changes when this
lands" runs over `graphitron-mcp`, and state the outcome. Either name the additional work that makes the
edge droppable, or say plainly that `graphitron-lsp` keeps a test-scope edge for the tests that read the
lint engine and the type-backing projection, revise the second "Why now" cost to the mcp half plus
whatever the lsp census actually yields, and reword the delivery criterion to what the item will deliver.
Any of those is fine. What cannot stand is the criterion as written, since it is a gate the item fails on
its own terms.

*Author's response.* Accepted, and the census was run rather than the wording softened. Running it
changed the answer twice, so the plan changed with it. The lsp count says the edge is droppable
without moving the rule engine: the fixture packages that read as generator packages
(`rewrite.test.jooq`, `rewrite.test.services`, `rewrite.test.conditions`, `multischemafixture`) are
generated or written in `graphitron-sakila-db` and `graphitron-sakila-service`; the lint types the
tests name are values they construct and never execute, so they belong with the diagnostics they
describe, at or below the store; `DeprecationRecognizer` parses a `TypeDefinitionRegistry` and
touches neither walk nor store, so this plan's own rule puts it below; and `CatalogBuilder.build`,
which the earlier splits missed, projects `CompletionData` and goes down with it, which is what
`FixtureCatalogTest` was actually reaching for. Across 67 lsp test files exactly one drives a real
generator. The correction that matters more is in the other direction, and round 1 accepted the
claim it corrects: `graphitron-mcp` is the harder of the two, because `StoreBackedBuild` has four
users. So the criterion stands as written, and the residue it rests on is stated as a count rather
than a hope: seven test files whose subject is two tiers agreeing with each other. Step 7 is new and
rehomes them, picking `graphitron-maven-plugin` (which already depends on all three modules, because
`DevMojo` is what wires them together) and naming the cross-tier-test module as the alternative a
reviewer may prefer. `R157PipelineTest` and `FixtureCatalogTest` are called out there as the two that
retire with the walk under R682 rather than being maintained.

*Non-blocking, question one, traceability only.* The mcp import list under "Both store clients drop
`graphitron` entirely" omits three of the imports actually present in `graphitron-mcp/src/test`:
`no.sikt.graphitron.rewrite.FactWriters`, `rewrite.model.Rejection` and `rewrite.ValidationError`.
The latter two are covered in substance by the step 1 census gap on the rejection vocabulary, so they
are a wording matter. `FactWriters` is not named anywhere in the plan and is not obviously inside any
of the move-list packages, which makes it one more file whose side of the line is unsettled. It is
also imported by `graphitron-lsp`'s `StoreFixture`, so it will surface again when the census above is
run.

*Author's response.* Taken. All three are now placed. `FactWriters` is named with `BuiltStore` and
`CapturedStore` under "What changes when this lands" and again in step 6, which moves the three
store-building test helpers down together and gives the reason: they are how a test gets a filled
store, which is the thing being moved, so leaving them behind would keep `graphitron-mcp` depending
on the generator for a fixture after every other reason had gone. `Rejection` and `ValidationError`
are covered by the rejection-vocabulary line in the same list and by the step 1 census, which argues
them below the line from the store's own schema: `rejection_validation_error` is a table and the
language server reads the `diagnostic` view over it.

### Round 2 (2026-09-01, Spec -> Ready, reviewer session 01ACqTfeZXHnE3pRXyUPbyA1)

Verdict: withhold. One blocking finding on question one. Question two is clean, and round 1's
reasons for saying so still hold.

*What was checked and holds.* The round 1 finding is answered by a census, not a rewording, and
the census is accurate where I could check it: the four fixture packages the lsp tests import are
jOOQ output of `graphitron-sakila-db` (`rewrite.test.jooq`, `rewrite.multischemafixture`) or
hand-written in `graphitron-sakila-service` (`rewrite.test.services`, `rewrite.test.conditions`);
`LintRule` is an enum with no imports, `LintFix` a record, `BuildWarning` a sealed interface
permitting exactly `NoRule` and `LintFinding`; `DeprecationRecognizer` imports graphql-java and
nothing else from the tree; `CatalogBuilder` has three public method names and `build` reads
`NodeDeclaration` and the assembled `GraphQLSchema`, not the walk, so it splits from
`projectTypesByName` as the plan says; `StoreFixture.ofBuild` has the one caller named;
`StoreBackedBuild` has the four users named. The seven-file residue is the right count. The
`Rejection` vocabulary is self-contained: the sealed interface and its ten top-level arm files
(about 2,100 lines) import nothing from the tree, so pulling it below the line is a move, not a
split. The module graph admits the move: `graphitron-sakila-db` and `graphitron-sakila-service`
depend on nothing in `graphitron-model`, so capture's pipeline tests can follow capture down
without a cycle, and `graphitron-maven-plugin` already depends on `graphitron`, `graphitron-lsp`
and `graphitron-mcp` at compile scope with both test-jars at test scope, so step 7's home exists
as claimed. `rejection_validation_error` is a table and `diagnostic` a view in the store's DDL, and
three lsp main classes read `DIAGNOSTIC`. `walk_type_backing_class` is gone from the tree.
`StoreClientBoundaryTest.noGeneratorReferenceInMainSources` scans main sources only while its
sibling `noLanguageServerReferenceInEitherTree` already scans both, so "widens to tests" is a
one-line change with a template beside it. `R157PipelineTest` and `FixtureCatalogTest` import what
the plan says they import. The lsp test-class count was 75, not 67, and is corrected in this
commit.

**Finding 1 (question one: is the outcome reachable as described). Step 1's census runs over what
the two clients import from the generator, but not over what the move set imports from the code
that stays, and that second census turns up decisions the plan has not made.** The plan says
"most of it is clear" and names one thing to confirm: that `ValidationError`, `Rejection`,
`TableRef` and `ColumnRef` are "plain data that both sides use". I grepped every import from the
move set (`rewrite/capture`, `rewrite/derive`, `rewrite/session`, `rewrite/selection`,
`rewrite/schema`, the named `catalog`, `lint` and `compile` classes, `JooqCatalog`,
`RewriteContext`) that lands in code the plan keeps above. Three of the results change what the
implementer builds.

* *`TableRef` and `ColumnRef` are not plain data, and the fact tier takes on `graphitron-javapoet`
  with them.* `ColumnRef` is a record whose fourth component is a javapoet `TypeName`; `TableRef`
  imports `ClassName`; so do `ForeignKeyRef` and `MethodRef` (through `SessionHooks`), and so do
  `JooqCatalog` itself and `derive/StoreNodeTables`. Nothing cycles, since `graphitron-javapoet`
  depends on nothing in the tree, but `graphitron-model` gains a compile dependency on the
  emitter's type-name library, and `roadmap-tool` picks it up transitively. The plan treats this
  kind of growth as its own to decide: it argues the graphql-java dependency in "Decisions this
  spec makes" and lists what `roadmap-tool` will inherit ("graphql-java, slf4j and the javac Tree
  API") in "What is out of scope". Javapoet is in neither list. Decide it: either the fact tier
  carries javapoet and both paragraphs say so, or the refs are re-encoded without it, which
  changes what `command` and `render` borrow through the `PackageImportDirectionTest` dial and is
  a larger item than this one. I recommend the first and think it needs one sentence of
  justification, not a redesign, but it is the author's sentence.
* *Capture runs one edge into code the plan places above the line, and the plan says there are
  none left.* `FactCapture` calls `derive/AuthoredClaimRejectionRows`, which calls
  `RejectionFacts.classSpelling` in `rewrite/diagnostics`. "What is out of scope" places
  `RejectionFacts` above, as one of the dev session's writers from above, and "Sequencing" says
  R870 removed the one edge from the generator into capture. One of the two placements has to
  move. `RejectionFacts` itself imports `FactCapture` and the rejection vocabulary and nothing
  from the walk, so moving it down is available and is probably right; alternatively
  `classSpelling` becomes a method on the vocabulary it spells. Either way the plan should say
  which, because the "writes from above" paragraph is what documents the boundary afterwards and
  it currently names a class that cannot stay where it says.
* *`rewrite/derive` does not move in one piece, for a reason other than the one the plan offers.*
  `ClaimDomain.of(GraphitronSchema)` and `DemandResidue.of(GraphitronSchema)` read the walked
  schema and switch over `GraphitronType` arms. Both are main-source values whose only callers
  are three tests (`DemandShadowTest`, `TypeBackingClassesTest`, `FactCaptureAgreementTest`), and
  `ClaimDomain`'s own javadoc says it retires with the shadow that reads it. They stay above (or
  become test sources), which step 1 should say, since the plan's "one thing to confirm" would
  come back clean on the four types it names and leave these two unmentioned.

The rest of that census is detail the implementer settles under step 1's own instruction, listed
here so the author can fold it in rather than rediscover it: `rewrite/schema` splits too, since
`schema/federation/EntityResolutionBuilder` imports `TypeRegistry` and the `GraphitronType`
family and nothing in capture reaches `schema.federation`; `RewriteContext` moves down rather
than staying, because it already imports `FactCapture.OutputCoordinates` and is a parameter of
both `CatalogBuilder.build` and `buildExternalReferences`, so the "worth settling" note under step
7 is already settled by the imports; and eight plain root-level types come down with no imports
of their own to worry about (`NodeDeclaration`, `ArgMappingSigil`, `RejectionKind`,
`ClasspathEntry`, `ValidationFailedException`, `SchemaParseException`,
`rewrite.dependency.DependencyVersions`, `rewrite.model.ConnectionNaming`).

What would satisfy the finding: add the move-set-upward census to step 1 beside the client census
that is already there, decide the javapoet dependency in "Decisions this spec makes" (and adjust
the `roadmap-tool` sentence to match), place `RejectionFacts` on one side of the line and make
"Writes from above" agree with it, and name `ClaimDomain` and `DemandResidue` as the two `derive`
files that stay. None of this changes the shape of the plan; it changes what step 1 hands to step
6, which is the part an implementer cannot decide alone.

*Author's response.* Accepted in full, and the census was run over the whole move set rather than
over the three cases the finding names. It is now in step 1 under "What the move set reads from the
code that stays", and it closes: **exclude three files and every remaining upward edge lands on
something the plan already moves down.** The three are the two the finding names (`ClaimDomain` and
`DemandResidue`, which stay, so `rewrite/derive` does not move in one piece) and
`schema/federation/EntityResolutionBuilder`, which the finding recorded as detail and which is the
same kind of case: it is the one file in `rewrite/schema` that reads the walked model, and its one
caller is the walk. The three decisions the finding asked for are made rather than deferred.

Javapoet: the fact tier carries it, argued in "Decisions this spec makes" beside the graphql-java
decision, and the `roadmap-tool` sentence now lists it. The argument is the one the finding expected
plus the reason it is not merely acceptable: javapoet here is a model of type names and not a writer
of source, and `ColumnRef.columnType` is a `TypeName` because the live `Class` exists only at the
catalog boundary, which is the fact tier deciding a fact where it can be decided. Re-encoding would
mint a second type-name model for the emitters to convert back out of.

`RejectionFacts`: it moves down, and so does the rest of `rewrite/diagnostics`, which is a better
answer than placing one class. All three files in the package import only `FactCapture`, the
rejection and warning vocabulary and `LintFix`, all of which move; none reads the walk; all three
write tables. "Writes from above" is rewritten to agree: the writers live in the fact tier and
`DevMojo` calls down to them, which is a downward call and not what the boundary is about.

*Found while running the census, and it is this plan's own error rather than the finding's.*
`BuiltStore` was listed among the three store-building test helpers that move down with capture. It
cannot: it runs `GraphQLRewriteGenerator.buildOutput()`. It stays in `graphitron`'s test-jar, which
`graphitron-maven-plugin` already consumes at test scope, so step 7's home takes it for free. Both
"What changes when this lands" and step 6 are corrected. This is the same class of mistake the
finding caught, in the one part of the move list that had not been counted either.

*Author's response, second pass: the javapoet decision above is reversed.* The finding was right
that the plan owed a decision here, and the first answer to it was wrong. Carrying the dependency
rested on the claim that `ColumnRef.columnType` has to be a `TypeName` because the live `Class`
exists only at the catalog boundary. That does not hold: `ColumnRef.decodeBindingType` rebuilds the
`TypeName` from the stored string, arrays included, and `StoreNodeTables` already reads store rows
and mints refs from them. The string round-trips, so the boundary argument was decoration on a
convenience.

With that gone the count decides it. Thirty-eight files read the javapoet-typed accessors and
exactly one, the producer, is below the line, so the fact tier mints these values purely for the
layer above. **Javapoet does not go into `graphitron-model`**, "Decisions this spec makes" now says
so, the `roadmap-tool` inheritance list drops it again, and a delivery criterion checks the pom.
Stripping it is step 2, inside this item rather than a follow-up.

Two things the strip census turned up that are now in step 1. `rewrite/session` splits: only
`SessionStateConfig` moves, `SessionHooks` stays above with the reflected model, and the DDL comment
on `store_graph_session_mount` had already drawn that line. That exclusion is what keeps `MethodRef`
out of the move set, and with it the one genuinely hard case, a generic `TypeName` carrying
`List<Film>` that no string in this tree round-trips today. Had `MethodRef` come down, this decision
would have been a real fork rather than a miscount.

*Non-blocking, question two, for step 7.* Both parity tests lean on a `StoreFixture` helper that
is private to its own module's test sources, and neither `graphitron-lsp` nor `graphitron-mcp`
publishes a test-jar today. Relocating the tests means either those two modules start publishing
test-jars for the plugin to consume, or the build-driving halves (`StoreFixture.ofBuild`,
`StoreBackedBuild`) move with the tests and the store-only halves stay. The second keeps the
detachment honest and is what I would expect the implementer to do; a sentence in step 7 saying so
would save them the fork.

*Author's response.* Taken, and step 7 says so under "What travels with them", with the seam named:
`StoreFixture` already holds `captured` and `built` as two fields rather than one flagged field,
so the build-driving half has a place to be cut. The `BuiltStore` correction above is what makes the
recommendation cheap, since the plugin already has the build-level helper on its test classpath. The
test-jar route stays available as the implementer's fallback, with the one thing that must not happen
stated instead of the one thing they must do.

### Round 3 (2026-09-02, Spec -> Ready, reviewer session 01ACqTfeZXHnE3pRXyUPbyA1)

Verdict: sign off. Both questions pass. Round 2's finding is answered by decisions, not by wording:
javapoet stays out of the fact tier and step 2 is the work that makes it true; `rewrite/diagnostics`
moves down whole and "Writes from above" now agrees with the code; `ClaimDomain` and `DemandResidue`
are named as the two `derive` files that stay. The `BuiltStore` correction the author found on the
way is right: it runs `GraphQLRewriteGenerator.buildOutput()`, so it cannot go below the generator.

*What was checked and holds.* Re-running the move-set census against the revised move set (with
`rewrite/diagnostics` in, `SessionHooks` and `SessionStateWarnings` out, and the three excluded
files excluded) leaves every remaining upward edge on something the plan moves, with the one static
import noted below. `ColumnRef` carries one `TypeName`, `TableRef` three `ClassName`s, `ForeignKeyRef`
one; `JooqCatalog` mints them from reflection and `StoreNodeTables` from store rows via
`ClassName.bestGuess`. `ColumnRef.decodeBindingType` handles the array descriptor by recursion.
Every `class_name` column in the DDL (23 of them) is a `VARCHAR`. Inside the move set only
`JooqCatalog` and `TableRef` itself read the typed accessors; capture writes class names without
touching them. `MethodRef.returnType` is the `getGenericReturnType()` `TypeName` and
`SessionHooks.Handled.handleType` the same, and neither is in the move set: `SessionStateConfig`
imports nothing, `ConfigurationFactCapture` writes `store_graph_session_mount` from it, every reader
of `SessionHooks` is the walk, the plan or the emitters, and the table comment says the reflected
signature is never stored back. `rewrite/schema/federation` has four files; `EntityResolutionBuilder`
is the one that reads the walk and `GraphitronSchemaBuilder` its one code caller, while
`FederationKeyFieldsParser` and `FederationSpec` import nothing and `KeyNodeSynthesiser` imports
`NodeDeclaration` and `FederationLinkApplier`. `rewrite/diagnostics` has three files importing
`FactCapture`, the rejection and warning vocabulary and `LintFix` only. `RewriteContext`'s six
imports all move. `CapturedStore` and `FactWriters` import nothing from the generator. The lsp
`StoreFixture` holds `captured` and `built` as two fields, and `WorkspaceFileTestSupport` exists
where step 8 says.

*Corrected in this commit, count-level, both in step 1 and step 2's census.* Two entries my own
round-2 census got wrong, which the author reproduced in good faith. `Rejection`'s ten arm files
import nothing, as I said, but one nested record,
`AuthorError.TenantColumnTypeDisagreement.TableSite`, holds a `TypeName` by its fully qualified
name, so step 2 has six carriers, not five; the component is built by `TenantScopeClassifier` above
the line and read only through `toString()` in `Rejection`'s own message rendering, so it strips
the same way as the others. And `NodeDeclaration` is not import-free: it statically imports four
directive-name string constants from `BuildContext`, which stays. Both are settled by the operations
step 1 and step 2 already prescribe, so neither changes the plan, and the body now states the fact.

*Non-blocking, for the implementer.* Two `{@link}`s will dangle when their files cross the line and
the Javadoc reference gate will say so: `FederationKeyFieldsParser` links `EntityResolutionBuilder`,
and `GraphitronType` links it by fully qualified name from the other direction (that one stays
above and keeps resolving). Downgrade the moving one to `{@code}`, per CLAUDE.md's rule for a
target that is genuinely another module's internals.
