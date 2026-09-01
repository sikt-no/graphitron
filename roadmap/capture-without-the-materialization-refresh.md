---
id: R865
title: "The generator owns the fact tier it should merely read"
status: Spec
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-27
last-updated: 2026-09-01
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
The remainder is a handful of tests whose subject is two tiers agreeing with each other; step 7
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
vocabulary, and the `BuiltStore` / `CapturedStore` / `FactWriters` test helpers). What is left is
seven tests that check two tiers against each other, and step 7 relocates them.

`graphitron-mcp`'s guard test `StoreClientBoundaryTest` widens from checking shipped code to checking
tests as well, and `graphitron-lsp` gets the same guard, which its pom asks for in a comment today.

## How we get there

The order matters, because each step makes the next one smaller.

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

**One thing to confirm before starting.** Six files in `rewrite/derive` use `ValidationError` and
`Rejection`, and two use `TableRef` and `ColumnRef`, all of which currently sit above the line. The
likely answer is that they belong below it: rejections are already a table in the store's schema and
the language server reads them, and `TableRef` and `ColumnRef` are plain data that both sides use.
Check this rather than assume it, because it decides whether `rewrite/derive` moves in one piece.

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
  `GraphQLRewriteGenerator.buildOutput()`, and across 67 lsp test files it has one caller,
  `LintSuppressionDiagnosticsParityTest`. Every other test builds its store through `CapturedStore`,
  which moves down.
* **`graphitron-mcp` is the harder of the two, not the easier one.** Its own build-driven fixture,
  `StoreBackedBuild`, has four users: `GraphitronMcpServerTest`, `DiagnosticsAggregateTest`,
  `ServerInstructionsTest` and its own `LintSuppressionDiagnosticsParityTest`.

So after the moves above, seven test files still need both tiers: four in `graphitron-mcp`, and
three in `graphitron-lsp` (the parity test, plus `R157PipelineTest` and `FixtureCatalogTest`, which
are discussed in step 7 because their case is different). Those seven are the whole of what stands
between this item and a clean detachment of both modules.

**2. Add a temporary import rule, so the layering holds until the move happens.** Give
`PackageImportDirectionTest` a `capture` rule, written like the `facts` rule it already has: capture
may import nothing else from the tree, with its one allowance for graphql-java written as an
allowance rather than a list of exceptions. This is a stand-in for the module boundary, not a rival
to it, and step 6 deletes it.

**3. Take store creation out of capture.** `FactCapture.runInternal` today opens the store, reports
what the cleanup sweep deleted, checks whether this project may write under its graph name, retries
once if the write fails, and falls back to a temporary in-memory store with a warning. All of that
becomes its own entry point that returns one of two answers: `Shared(handle)`, meaning the run got
the real store, or `Demoted(handle, reason)`, meaning it got a temporary one and here is why. The
caller then has a plain answer to work with instead of a value that might be null and a log line to
match it against.

The ownership check stays in the fact tier rather than moving up to the Maven goal, for the reason
`ownsGraph` already gives in its own javadoc: it needs the store open and the row readable, and the
goal never reads the store. The retry logic moves with it, for the same reason.

**4. Hand the store to the generator.** `captureAndRead` and `captureFacts` take the store the entry
point returned, instead of the directory in `ctx.storeDirectory()`. `RewriteContext` keeps the
directory, because the Maven goals still need it as a setting. What goes away is the generator
running with no store at all: that stops being possible.

**5. Add the command.** `CaptureMojo` copies the shape `ValidateMojo` already has: 34 lines whose
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

**6. Move the modules.** Nothing changes behaviour here: no table changes shape, no generated file
changes, no query answers differently. Keep moves and behaviour changes in separate commits. The
test schemas in `graphitron/src/test/resources/corpus` move with capture and are shared back up as a
test-jar, for the planner and emitter tests that use them. The store-building test helpers
(`BuiltStore`, `CapturedStore`, `FactWriters`) move the same way, and for a stronger reason: they are
how a test gets a filled store, which is the thing being moved. Leaving them behind would keep
`graphitron-mcp` depending on the generator for a test fixture after every other reason had gone.
`FactCaptureAgreementTest` stays where it is: it compares capture's output against the old walk, and
a test comparing two layers belongs in the upper one.

**7. Rehome the tests that need both tiers.** Seven files, in two kinds.

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

*Two that are about the retiring walk.* `R157PipelineTest` runs a real schema through the real
classifier and then checks the editor's completions, hovers and diagnostics against what the
classifier decided, so that a classifier that quietly widened would be caught by an editor
assertion. `FixtureCatalogTest` does the catalog half. Both depend on machinery R682 removes, so
they have an end date the parity tests do not. Move them by the same rule as the five, and expect
them to shrink rather than to be maintained: once `CatalogBuilder.build` is below the line,
`FixtureCatalogTest`'s remaining tie is `RewriteContext`, which is worth settling as part of that
move rather than separately.

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

## What is out of scope

**Writes from above.** The module boundary stops the upper layer from *reading* the lower one's code.
It does not stop the upper layer from *writing* to the store: `CompileFacts`, `RejectionFacts`,
`BuildWarningFacts` and `OwnedGraphPartition` all write tables during a dev session, through the
same generated jOOQ code `graphitron-model` publishes to everyone. That stays possible afterwards.
Whether a given write is fine or a mistake is a question about when it runs, not about who imports
whom, and this item does not answer it. Any documentation that lands with the move should say the
compiler-enforced rule is about imports.

**`roadmap-tool`'s dependencies.** It depends on `graphitron-model` only, and will pick up
graphql-java, slf4j and the javac Tree API when that module grows. That is a build-time cost on a
build tool, and we accept it. Untangling it is a separate problem and should not shape where this
line goes.

**The dev session's extra refresh at startup.** R857 removes that call. This item removes the reason
it was needed, which is not the same thing as removing it.

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
