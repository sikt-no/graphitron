---
id: R865
title: "The generator owns the fact tier it should merely read"
status: Spec
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: [capture-stops-reading-the-walk]
created: 2026-08-27
last-updated: 2026-08-31
---

# The generator owns the fact tier it should merely read

The fact store is supposed to sit below the generator: capture writes facts, planners read facts and
produce commands, emitters render commands, and validation is questions asked of the facts. Each
tier reads only the tier below it.

The tree does not draw that line anywhere. Capture lives in the same module as the planners that are
meant to sit above it, so nothing but review stops a planner from reaching into the thing that
produced its facts. The generator creates the store it captures into, so no caller can hand it one.
And because the generator is the only thing that fills a store, obtaining a store means running a
generation.

This item draws the line in the three places it is missing, so that later work stops paying for its
absence.

## Vocabulary

The **fact tier** is everything at or below the store: the DDL that says what a fact is, the
derivations that say what facts mean, the read and write surfaces, and the **capture** that fills
the store from the three **corpora** (the consumer's SDL, their jOOQ-generated catalog classes, and
their compiled extension classes on the build classpath).

The **generator** is what sits above: the planners that join facts into command rows, and the
emitters that render them.

**Store creation** is opening a `GraphitronModelStore` at a directory and deciding what to do when
that fails or when another checkout holds the graph: the open, the graph-ownership check, the retry,
and the fallback to a private in-memory store.

## Why now

This is tech debt, paid so the next changes are cheap rather than because anything is broken today.
Three concrete costs it removes:

**Every fact-tier change is reviewed by hand.** There is no mechanism that refuses a planner
importing a crawler, so the rule survives only as long as reviewers keep noticing. After the module
move, javac refuses it.

**The store clients carry an edge they do not want.** `graphitron-lsp` and `graphitron-mcp` both
depend on `graphitron` at test scope, and both poms apologise for it in their own words: compile
scope "would let a request-path class reach a generator type again without anyone noticing". The
edge exists for one reason, that building a populated store in a test requires driving a generator.
Remove that requirement and both modules drop the dependency in every scope.

**Nobody can get a store without running a generation.** Every instrument built on the store so far
has had to keep a file left behind by a run that happened to produce one, which is why the
derived-read-cost figures in R876 rest on a single kept capture with a recorded SHA rather than on a
store anyone can make.

## What changes when this lands

**The fact tier moves into `graphitron-model`.** `rewrite/capture`, `rewrite/derive`, `JooqCatalog`,
the SDL reader and its input family, the selection parser, `rewrite/session`, `ClasspathScanner` and
`CompletionData` cross the line, about 14,000 lines. `plan`, `render` and `command` stay, about
14,800. A planner that imports a crawler stops compiling.

**The generator is handed a store.** `GraphQLRewriteGenerator` stops passing a directory into
capture and starts receiving an open store, which is the shape every fact reader in `graphitron-lsp`
and `graphitron-mcp` already has. Store creation becomes one fact-tier entry point that the mojos
call.

**`mvn graphitron:capture` fills a store and stops.** Schema loading, attribution, classification and
the capture loads, then commit. No detections, no validation, no lint, no plan, no emission. It
produces a store for a schema that would fail validation, which is what separates it from
`mvn graphitron:validate`: a command that produces an artifact must not refuse to produce it because
it disliked the input.

**The dev session opens one store instead of two.** `DevMojo` already opens a long-lived session
store for the language server, the MCP server and the diagnostics writers, and today every generator
pass inside that session opens a second handle underneath it because no caller can substitute one.
The saving is a handle rather than a database, H2 giving one process one database per file; what
matters is that the session and the pass stop disagreeing about who owns the store.

**Both store clients drop `graphitron` entirely.** What `graphitron-mcp` imports from it at test
scope is `FactCapture`, `JooqCatalog`, `ClasspathScanner`, `CompletionData`, `CompileFacts`,
`CompileDiagnostic`, `CompileRound`, `LintConfig` and the `CapturedStore` harness, all of which move
down, plus `GraphQLRewriteGenerator`, `RewriteContext` and `BuiltStore`, which are there to drive a
generator in order to obtain a store. The capture goal and the moved capture remove both reasons.
`StoreClientBoundaryTest` tightens from main-sources-only to all scopes.

## How we get there

The order matters, because each step makes the next one smaller.

**1. Census the cut, and settle `rewrite/derive`.** Capture's imports from outside its own package
sort cleanly: corpus readers and fact writers move with it, values move as values, and three things
split rather than move. `rewrite/catalog` splits, `CatalogBuilder.buildExternalReferences` going
down as a classpath read while `projectTypesByName` and `TypeBackingShape` stay above and retire
with the walk under R682; they have live callers in `TypeBackingProjectionTest` and in
`graphitron-lsp`'s `R157PipelineTest`, so "dies with the walk" does not mean delete now.
`rewrite/lint` splits, `LintConfig` going down as a value in `SubjectConfig` while the rule engine
stays above as analysis over a read schema. The third is the walk-side write, which R870 deletes and
which this item therefore does not carry.

The one census gap to close first: six files in `rewrite/derive` import `ValidationError` and
`Rejection`, and two import `TableRef` and `ColumnRef` from the leaf zoo that stays above. The
likely answer is that the rejection vocabulary belongs at or below the fact tier anyway, since
`rejection_validation_error` is a DDL relation and the `diagnostic` view is what the language server
reads, and that `TableRef` and `ColumnRef` landing below is the shared pure-data floor
`PackageImportDirectionTest`'s borrow dial already names as its endpoint. Confirm it rather than
assume it, because it decides whether `rewrite/derive` crosses whole.

**2. Hold the invariant with a package rule while the move is scheduled.** Add a `capture` arm to
`PackageImportDirectionTest`, written like the existing `facts` arm: a blanket "imports nothing else
of the tree" with its graphql-java allowance stated as a positive allowance rather than an exception
list. This is not a rival to the module boundary, it is a stage of it, and the move deletes the arm.

**3. Lift store creation out of capture.** `FactCapture.runInternal` currently opens the store,
reports what the reaper released, checks graph ownership, retries once, and falls back to a private
in-memory store with a warning. That becomes an entry point of its own returning a sealed outcome,
`Shared(handle)` or `Demoted(handle, reason)`, so whether a run captured into the shared file is a
decided value carried with its provenance rather than a null plus a log line. The check stays in the
fact tier rather than moving to the mojo, for the reason `ownsGraph`'s javadoc already gives: it
lives where the store is open and the row readable, and the mojo never reads the store.

The retry policy goes with it. `captureWithRetry` distinguishes a lock timeout from a probable
capture bug and reports them differently, and it asks `reconciles` per attempt because the
first-graph refresh cadence commits mid-capture. That is store-lifetime policy, and it belongs
beside the opener.

**4. Hand the store to the generator.** `captureAndRead` and `captureFacts` take what the opener
returned instead of `ctx.storeDirectory()`. `RewriteContext` keeps the directory, because a path is
configuration the mojos still need; it stops standing in for a store the generator will mint later.
A generation with no store stops being a state the generator can be in.

**5. Add the goal.** `CaptureMojo` is the shape `ValidateMojo` already has, thirty-four lines whose
body is one `runGenerator` call, and `AbstractRewriteMojo.runGenerator` already owns the context
build, the codegen classloader scope and the error wrapping. Its `packagesRequired()` returns
`false` as `validate`'s does, and it warns when the sentinel substitutes, because a run that fell
back on the sentinel writes no `sql_` rows and a store without the catalog answers few of the
questions people open one for.

The capture-only entry point is a fifth `Projection` of `runPipeline`, not a second pipeline body;
the class's javadoc names a fifth entry point growing a front half of its own as the regression that
shape exists to prevent. The pipeline's stage order makes it cheap: everything the goal wants runs
above the capture and everything it does not runs below, with lint the one exception, computed above
the capture today and needing a switch. Validation needs none, because it runs inside the capture
window's continuation and the projection returns before it.

**6. Move the modules.** No relation changes shape, no generated output changes, no store answers
differently. A commit that moves a class and a commit that changes what it does are separate
commits. The corpus at `graphitron/src/test/resources/corpus` moves with capture and ships as a
test-jar for the planner and emitter tests that consume it. `FactCaptureAgreementTest` does not
move: it is scaffolding for the walk's retirement, and an agreement test between two tiers belongs
above the line anyway.

## Decisions this spec makes

**One module, not two.** A separate `graphitron-capture` between the model and the generator is the
alternative, and the DDL settles it: a relation added to `graphitron-model.sql` is inert until
capture writes it, and capture writing a column the DDL does not declare does not compile. Two
modules let two halves of one change land separately and skew, and buy nothing back. The model
without its capture is a schema nobody fills.

**The refresh is untouched.** Capture refreshes the registered targets exactly as it does now, on
both of its cadences, and nothing here gives any caller a way to obtain a store whose targets are
stale. A refresh worth declining is a registration worth retiring, and that is R876's question and
R899's after it, not a switch this item adds.

**graphql-java becomes constitutive rather than a contaminant.** The SDL is one of the three corpora
the fact model transcribes, and a module that owns what a GraphQL schema fact *is* while being
unable to parse GraphQL is incoherent. The module's description stops saying "the fact-schema DDL
and the H2 bootstrap" and starts saying it is the fact tier.

## What is out of scope

**The write direction.** This closes the read direction only. `CompileFacts`, `RejectionFacts`,
`BuildWarningFacts` and `OwnedGraphPartition` write base relations from above the line on the
dev-session cadence, through the same generated jOOQ surface `graphitron-model` publishes, which a
module boundary cannot refuse. A tier above the facts writing a base relation stays possible after
this, and what separates a sanctioned instance from a defect is a cadence argument rather than an
import direction. Whatever documentation lands with the boundary says that the rule javac now
enforces is about imports.

**`roadmap-tool`'s classpath.** It depends on `graphitron-model` alone and will inherit graphql-java,
slf4j and the javac Tree API from the widened module. That is an accepted build-time cost on a build
tool; untangling it is its own problem and is not allowed to shape this boundary.

**The dev session's defensive `refreshAll`.** R857 removes the call. This removes the ownership split
that made it necessary, which is a reason rather than a line of code.

## Sequencing

**R870 first, as a hard dependency.** Capture writes `walk_type_backing_class` from the
classification walk, so the fact tier reads its own consumer. That edge cannot cross the module line,
and R870 deletes it on its own evidence without waiting for anything here.

**R876's slices before the move.** Its slices land new code in exactly the packages this relocates.
Nothing conflicts in substance, since this moves files without changing what they do, but it
conflicts in mechanics, and ordering is the cheaper resolution than coordination. An implementer
starting the move while a supertype slice is in flight should say so rather than rebase through it.

**Before R682, not after.** Waiting for the leaf zoo to dissolve means waiting for a large in-flight
item to finish before the boundary that would have protected it exists, and every relation added in
the meantime is one more thing the boundary has to be talked past. R682 is not blocked by this: it
dissolves the middle either way, and this decides where the line is rather than what stands above it.

## How we will know it is delivered

* **`mvn graphitron:capture` on `graphitron-sakila-example` produces a store and nothing else.** No
  emitted file, no validation report, no plan. Reopen the store and find graphs and fields non-zero.
* **The goal produces a store for a schema `validate` rejects.** Point it at a fixture whose schema
  fails validation and find that schema's captured rows and the stage verdicts that refused it.
* **The goal's store equals a generating run's.** Capture one fixture graph both ways and assert the
  two stores hold the same rows in every relation capture writes, refreshed targets included.
* **`graphitron-model` compiles with the fact tier inside it and no dependency on `graphitron`.** The
  reactor's module order is the proof: a cycle does not build.
* **`GraphQLRewriteGenerator` has no `FactCapture` import**, and `graphitron`'s main sources name no
  store opener. Both are guard tests rather than review-time greps. Scope the opener guard to that
  module: `graphitron-model` keeps two openers this item does not touch, the build-time
  `ModelCodegenDriver` and the store's own boot surface.
* **`graphitron-lsp` and `graphitron-mcp` declare no dependency on `graphitron` in any scope**, and
  `StoreClientBoundaryTest.noGeneratorReferenceInMainSources` scans test sources too.
* **A generation drives against a store its caller opened**, and the demotion arm is covered: a
  store directory the run does not own yields the demoted outcome with its reason, the generation
  completes, and the shared file is untouched.
* **The full verification build is green with no generated-output diff in
  `graphitron-sakila-example`.** A move that changes an emitted file did something else too.

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

*Non-blocking, question one, traceability only.* The mcp import list under "Both store clients drop
`graphitron` entirely" omits three of the imports actually present in `graphitron-mcp/src/test`:
`no.sikt.graphitron.rewrite.FactWriters`, `rewrite.model.Rejection` and `rewrite.ValidationError`.
The latter two are covered in substance by the step 1 census gap on the rejection vocabulary, so they
are a wording matter. `FactWriters` is not named anywhere in the plan and is not obviously inside any
of the move-list packages, which makes it one more file whose side of the line is unsettled. It is
also imported by `graphitron-lsp`'s `StoreFixture`, so it will surface again when the census above is
run.
