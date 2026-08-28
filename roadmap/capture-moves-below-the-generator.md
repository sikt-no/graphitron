---
id: R864
title: "Capture moves below the generator: the fact tier becomes a module boundary"
status: Spec
bucket: architecture
priority: 2
theme: model-cleanup
depends-on: [capture-stops-reading-the-walk]
created: 2026-08-27
last-updated: 2026-08-27
---

# Capture moves below the generator: the fact tier becomes a module boundary

> **Citation redirect, 2026-08-28.** The R856 citations below name an item dissolved into R876; the
> figures it carried are in `roadmap/audits/2026-08-28-derived-read-cost-premise.md`. R848 is
> unaffected. The 59-second registration this item cites is `intent_carrier_data_field`, priced at
> 50.9 s on the audit's consumer-schema capture.

R682 states the destination in one sentence: capture writes facts, planners read facts and produce
commands, emitters render commands, and validation is questions asked of the facts. Each tier reads
only the tier below it.

That sentence has a module line in it, and the reactor does not draw it. Capture, the derivations,
the SDL reader and the catalog reader all live in `graphitron` beside the planners and the emitters
that are supposed to sit above them. Nothing but review keeps a planner from reaching past the facts
into the thing that produced them.

Move the fact tier into `graphitron-model` and the rule stops being discipline. A planner that
imports a crawler does not compile.

**What that boundary does and does not close, stated precisely, because the item was first written
claiming more.** It closes the read direction: after the move no code above the line can import the
crawlers, the SDL reader or the catalog reader, and javac says so. It does not close the write
direction. `CompileFacts`, `RejectionFacts`, `BuildWarningFacts` and `OwnedGraphPartition` all write
base relations from above the line on the dev-session cadence, and none of them moves down; they
write through the same generated jOOQ surface `graphitron-model` publishes, which a module boundary
cannot refuse. So a tier above the facts writing a base relation stays possible after this item, and
what separates a sanctioned instance from a defect is a cadence argument rather than an import
direction. R857 gestures at where an enforcer for that would live. Naming it here so nobody reads
this item as having solved it.

The consequence for sequencing is that the one write-direction defect the tree actually had, capture
reading the classification walk to fill `walk_type_backing_class`, is closed by R870 and not by this
item. R870 is a dependency because the move cannot carry that edge across, but it is worth being
clear that it earns its keep on its own and would be worth doing if this item never landed.

## Vocabulary

The **fact tier** is everything at or below the store: the DDL that says what a fact is, the
derivations that say what facts mean, the read surface, the write surface, and the capture that
fills it from the three **corpora** (the consumer's SDL, their jOOQ-generated catalog classes, and
their compiled extension classes on the build classpath). The **generator** is what sits above: the
planners that join facts into command rows, and the emitters that render them.

## What changes when this lands

**The generator takes a store.** Not because someone rewrote a signature, but because a module below
it holds the store and there is nothing else it could take. `GraphQLRewriteGenerator` stops calling
`FactCapture.runAndRead` with a lambda and starts receiving a `StoreHandle`, which is the shape every
language server fact reader already has.

**The dev session opens the store once.** Today `DevMojo` opens a long-lived `sessionStore` for the
language server, the MCP server and the diagnostics writers, and every generator pass inside that
session opens a second handle of its own, because `FactCapture.runInternal` is where
`GraphitronModelStore.openAt` gets called and no caller can substitute one. The session then calls
`Materializations.refreshAll` at start, and its own comment says why: it cannot know whether the
pass that just ran captured into the same file. This item removes the ownership split that makes
that question unanswerable; R857 removes the defensive refresh itself.

**Inverting that ownership moves a policy, not just a lifetime.** `runInternal` does more than open
a store. It owns the graph-ownership check, the retry-then-demote policy and the
`DEMOTED_TO_MEMORY` report, and `ownsGraph`'s javadoc says the check "lives here, where the store is
open and the row readable, rather than in the mojo, which never reads the store." Handing the
generator a `StoreHandle` means that policy either migrates to the mojo, contradicting that javadoc
and giving the mojo a store read it does not have today, or becomes its own fact-tier entry point.
The second is right, and the shape follows the project's standing move: one opener returning a
sealed outcome, `Shared(handle)` or `Demoted(handle, reason)`, so whether a run captured into the
shared file is a decided value carried with its provenance rather than a null plus a log line. This
is the one place the move is not mechanical, and it is worth an implementer knowing that before
starting rather than discovering it.

**Capture becomes re-runnable and re-usable.** Capture once, plan many times against the same rows.
That is what anybody debugging a planner or a producer actually wants, and today every attempt
recaptures. It is also what makes the `graphitron:capture` goal coherent rather than a special case.

**And graphql-java stops being a contaminant in `graphitron-model`.** It becomes constitutive. The
SDL is one of the three corpora the fact model transcribes, and a module that owns what a GraphQL
schema fact *is* while being unable to parse GraphQL is incoherent. The module's description stops
saying "the fact-schema DDL and the H2 bootstrap" and starts saying what it will actually be: the
fact tier.

## Why a module and not a package rule

`PackageImportDirectionTest` already enforces import direction for the command / plan / render /
facts triangle. Adding a `capture` arm to it is the cheap version of this item, and the honest answer
is that it is not a rival but a stage: the arm holds the invariant while the move is scheduled, and
the move deletes the arm.

The first draft rejected the package rule outright, on the grounds that "a dial is a list somebody
maintains, entries go on it as easily as they come off". That objection was aimed at the wrong
shape. It is true of `BORROWED_MODEL_REFS`, the migration dial `command` and `render` read. It is
false of the guard's `facts` arm, which is a blanket "imports nothing else of the tree" with its
graphql-java allowance written as a positive allowance rather than an exception list, and whose
javadoc says as much. A `capture` arm in that shape is not a list of permitted borrowings; it is a
rule with one stated allowance, and a new violation fails rather than being recorded.

So what the module boundary buys over the guard is narrower than the first draft claimed, and worth
stating exactly. Both refuse a planner that imports a crawler. The guard refuses it in a test that
somebody can widen; javac refuses it in a way nobody can, and it survives a package being renamed or
a class being moved, which a prefix scan does not. Neither refuses a write from above the line. That
is the difference, and it is enough to justify the move without overstating it.

## What crosses

| | lines |
|---|---|
| **Moves into `graphitron-model`**: `rewrite/capture` (6,515), `rewrite/derive` (2,493), `JooqCatalog` (1,852), `rewrite/schema/input` (1,503), `rewrite/schema` (776), `rewrite/selection` (610), `rewrite/session` (301), plus `ClasspathScanner` and `CompletionData` | ~14,000 |
| **Stays in `graphitron`**: `plan` (4,817), `render` (6,706), `command` (3,243) | ~14,800 |
| **Dissolves under R682**: `rewrite/model`, the leaf zoo | 15,393 in 138 files |

The two surviving tiers are about the same size and the thing between them today is larger than
either. That is the shape of the cleanup, and it is why the boundary is worth drawing before the
middle is gone rather than after.

## The cut list, and the finding behind it

Capture imports thirty-one symbols from outside its own package. Sorting them by what they actually
are settles the move, and the sort has a result worth stating plainly: **almost nothing capture
imports is generator-shaped.**

Readers of a corpus, which are capture's own business and move with it: `RewriteSchemaLoader`,
`SchemaAssembly`, `SchemaError`, `SdlVerdicts`, the `schema/input` family, the `selection` parser
(a selection set written in a directive argument is SDL), `JooqCatalog`, `ClasspathScanner` and
`CompletionData`.

Values capture transcribes, which move as values: `SchemaRecipe`, `SessionStateConfig`, `LintConfig`,
`ArgMappingSigil`, `NodeDeclaration`, `ConnectionNaming`.

Fact writers and rules, which are the fact tier by definition: `CompileFacts`, `StoreDetections`,
`ResolvedKeyProjections` and the rest of `rewrite/derive`.

That leaves exactly three things that do not move, and each is a split rather than a blocker:

* `rewrite/catalog` splits. `CatalogBuilder.buildExternalReferences` is a classpath read and goes
  down; `CatalogBuilder.projectTypesByName` and `TypeBackingShape` read the walked model and stay
  above, retiring with it under R682 rather than with this item. They have live callers in
  `TypeBackingProjectionTest` and in `graphitron-lsp`'s `R157PipelineTest`, so an implementer who
  reads "dies with the walk" as "delete now" breaks two modules' tests.
* `rewrite/lint` splits. `LintConfig` is a value in `SubjectConfig` and goes down; the rule engine
  is analysis over a read schema and stays above.
* The walk-side write neither moves nor splits, because R870 has already deleted it. That item is a
  dependency for exactly this reason: capture cannot cross the line while it projects a
  `GraphitronSchema`.

**One limit on the sort above: it was run over `capture`'s imports and not over `derive`'s.** Six
files in `rewrite/derive` import `ValidationError` and `Rejection`, and two more import `TableRef`
and `ColumnRef` from `rewrite/model`, the leaf-zoo package this item says stays above. None of the
four is on the cut list. The likely answer is that the rejection vocabulary belongs at or below the
fact tier anyway, since `rejection_validation_error` is a DDL relation and the `diagnostic` view is
what the language server reads; and `TableRef` and `ColumnRef` landing below the line is precisely
the "shared pure-data floor" that `PackageImportDirectionTest`'s borrow dial names as its own
endpoint, which would be a payoff rather than a cost. But this item asserted "`rewrite/derive` sits
below the line" without checking, and the census owes a second pass before Ready.

## The edge that had to go first, now its own item

`FactCapture.detect` wrote `walk_type_backing_class` from the run's `ClassifiedRun`, which made
capture read the classification walk that sits above it. That write could not survive the move,
because capture in a module below the generator cannot import the walk that produces it.

**R870 deletes it, and this item depends on that rather than owning it.** The split is not
bookkeeping. The deletion turns out to rest on nothing this item establishes: the relation has no
production reader, and the comparison it served already computes both sides in the test's own JVM,
so it stands or falls on its own evidence and lands without a module moving. It also happens to be
the piece that closes the write-direction defect, which the boundary does not, as stated at the top.
Bundling the two made a small correct change wait on a large one.

What this item still owns is the consequence for its own scope: after R870, `FactCapture` imports
nothing that projects a `GraphitronSchema`, and the cut list has no entry that neither moves nor
splits.

## Refresh cadence is the caller's, not capture's

The store has two kinds of consumer and they need opposite things from the materialization register.
The language server and the MCP server open a store they did not write, so they have to ask for the
registered targets to be made current. A run that captures does not: currency is implied by its own
write. `Materializations` already states this as two cadences and calls the difference "a real
contract, not a convenience", and `refreshAll`'s javadoc names its caller as "a reader that opens a
store it did not capture into".

The API supports both. **Capture does not.** `FactCapture.capture` ends its transaction with
`Materializations.refresh` unconditionally, so the writer cadence is welded in and no caller can
express the other. That is the same ownership defect this item is about, in a second place: the
cadence belongs to whoever opened the store, and today it belongs to whoever filled it.

So the constraint on the moved API is explicit: **a consumer that needs current targets asks; a
consumer that does not, does not pay.** After the move, capture does not decide the cadence. The
caller that opened the store does, which for a generating run means it keeps asking for exactly what
it asks for now, and for a dev session means it asks once instead of being handed a refresh it did
not want on top of one it did (which is R857).

**One fact this does not license, and it needs stating so nobody plans on it.** "The generator does
not need refreshing" is true of the *call* and not yet of the *rows*. Three registered targets are
read from main sources today: `RoutineWriteFacts` in `plan` reads `intent_carrier_data_field`, which
is one of the two most expensive registrations in R856's consumer-schema price list at 59 seconds;
`ArgmappingProjectionDefects` reads `intent_argmapping_pair`; `StoreNodeTables` reads
`intent_resolved_type_binding`. A generating run depends on those rows being current and gets that
from its own capture.

Whether it would be cheaper for those three reads to go to the `_live` views and for the register to
exist only for interactive readers is a real question with a real prize, since a consumer capture
would stop paying a register that serves the editor. It is also a measurement rather than an
argument: the registrations exist because H2 inlines a view at every naming and eliminates no common
subexpression, and that cost lands inside a single read as much as across many. R848 owns that
measurement. This item makes the cadence expressible; it does not decide what any consumer should
ask for.

## The corpus

`graphitron/src/test/resources/corpus` moves with capture. In the destination it is spec-by-example
for what the facts say about a schema, which is a fact-tier artifact; it reads as a classification
corpus today only because the walk is still the thing being specified.

Two mechanical consequences. The planner and emitter tests that need SDL fixtures consume it across
the boundary, so it ships as a test-jar. And `FactCaptureAgreementTest`, which diffs capture's rows
against `GraphitronSchemaBuilder`, does not move: it is scaffolding for the walk's retirement and it
retires with the walk. Until then it lives above the boundary, testing the module below from the
module above, which is where an agreement test between two tiers belongs anyway.

## Sequencing against R682

Before, not after. Waiting for R682 means waiting for a large in-flight item to finish before the
boundary that would have protected it exists, and every relation added in the meantime is one more
thing the boundary has to be talked past.

The first draft gave a second reason that has since moved out: that the move forces the back-edge to
be cut. It does force it, but R870 cuts it without waiting for anything, so that is no longer an
argument for doing this early. It is an argument for doing R870 early, which is why it is a separate
item.

R682 is not blocked by this. The leaf zoo dissolves above the line either way; this item decides
where the line is, not what is left standing on the far side of it.

## Decisions this spec makes

* **`rewrite/derive` sits below the line.** The rules are derivations producing rows, and R682 says
  validation is questions asked of the facts. The generator reads the answers through
  `StoreDetections` and never sees a rule, so a rule cannot come to depend on a plan.
* **One module, not two.** A separate `graphitron-capture` between the model and the generator is
  the alternative, and it is rejected on the schema's terms rather than on any consumer's. The DDL
  and the capture that fills it change together: a relation added to `graphitron-model.sql` is
  inert until capture writes it, and capture writing a column the DDL does not declare does not
  compile. Two modules let those two halves of one change land separately and skew, and buy nothing
  back, because the thing they would separate is not separable. The model without its capture is a
  schema nobody fills.

  This decision is not the earlier draft's. That one rejected two modules because the only argument
  for them was keeping `graphitron-model` small enough for `roadmap-tool`, and dismissed that as out
  of scope. There is a second and much better argument, which the next decision answers on its
  merits.
* **The store clients get a better boundary, not a worse one.** This is the argument for two modules
  that deserved answering. `graphitron-model` is a compile-scope dependency of `graphitron-lsp` and
  `graphitron-mcp`, and both poms state a rule in their own words: `graphitron` is test scope only,
  because, as the language server's pom puts it, compile scope "would let a request-path class reach
  a generator type again without anyone noticing." `StoreClientBoundaryTest` pins the MCP half. Read
  quickly, this item widens the compile floor under both of them and blinds that guard, whose scan
  is for the literal prefix `no.sikt.graphitron.rewrite.`.

  The tree says otherwise. Sort what those two modules import from `graphitron` at test scope
  against this item's cut list. The MCP's list is `FactCapture`, `JooqCatalog`, `ClasspathScanner`,
  `CompletionData`, `CompileFacts`, `CompileDiagnostic`, `CompileRound`, `LintConfig` and the
  `CapturedStore` harness. Every one of those moves down. What is left over is
  `GraphQLRewriteGenerator`, `RewriteContext` and `BuiltStore`, which is to say: drive a generator
  pass in order to obtain a populated store. **The test-scope edge on the generator exists because
  producing a store today requires the generator.** Move capture below the line and the reason for
  it evaporates. `StoreClientBoundaryTest`'s own javadoc reads as an apology for that edge rather
  than a defence of it: "the generator is a test-scope fixture dependency and nothing more."

  So the deliverable is the opposite of the fear. Both poms drop `graphitron` in every scope, and
  the guard is tightened from main-sources-only to all-scopes, which it cannot be today precisely
  because tests must reach past it to build a fixture. The prefix stops being ambiguous for the same
  reason: `no.sikt.graphitron.rewrite.` today names the generator and the fact tier sitting beside
  it, and after the move it names the generator, which is what the guard was always trying to say.
  A capture that keeps its package spelling would leave the guard firing on legitimate imports, so
  the move carries a repackaging and the guard's tightening is how that is verified rather than
  assumed.
* **The move is mechanical, not a redesign.** No relation changes shape, no generated output
  changes, and no store answers differently. A commit that moves a class and a commit that changes
  what it does are separate commits.

## Out of scope

`roadmap-tool` depends on `graphitron-model` alone, booting the store through
`GraphitronModelStore.open()` and reading the live catalog and `COMMENT ON` prose to render the
schema reference pages and check identifier drift. It will inherit graphql-java, slf4j and the javac
Tree API from the widened module. That is an accepted build-time cost on a build tool, and untangling
`roadmap-tool` is its own problem, deliberately not solved here and deliberately not allowed to
shape this boundary. Unlike the language server and the MCP server, `roadmap-tool` states no rule
about what it may compile against, so there is no boundary here to preserve or improve, only a
classpath that grows.

The write-direction enforcer named at the top is out of scope too. `CompileFacts` and the three
`rewrite/diagnostics` writers keep writing base relations from above the line, and this item neither
moves them nor constrains them. What it must not do is leave the tree reading as though it had:
whatever the boundary's documentation says on landing, it says that the rule javac now enforces is
about imports.

## How we will know it is delivered

* `graphitron-model` compiles with `rewrite/capture` inside it and no dependency on `graphitron`.
  The reactor's module order is the proof: a cycle does not build.
* `GraphQLRewriteGenerator` has no `FactCapture` import and takes a `StoreHandle` on the entry
  points that read facts. This is the item's central claim, and it needs an enforcer rather than a
  grep at review time: a rule in `PackageImportDirectionTest`, written like the existing `facts`
  arm, which is a blanket "imports nothing else of the tree" with its graphql-java allowance stated
  positively rather than as an exception list. The earlier draft rejected a package rule on the
  grounds that a dial is a list somebody maintains. That is true of `BORROWED_MODEL_REFS` and false
  of the `facts` arm, so the objection was to the wrong shape. The arm is deleted by the module move
  that supersedes it, which is the point: it holds the invariant for however long the move takes.
* `graphitron-lsp` and `graphitron-mcp` declare no dependency on `graphitron` in any scope, and
  `StoreClientBoundaryTest.noGeneratorReferenceInMainSources` scans test sources too. This is the
  store-client decision above, made checkable.
* `DevMojo` opens one store rather than one per generator pass. Two things this bullet does not
  claim, because the first draft did and both are wrong. The saving is a handle and not a database:
  DevMojo's own comment records that capture's per-pass opens "reach the same database, H2 giving
  one process one database per file". And the defensive `refreshAll` at session start is R857's to
  remove, on the strength of its claim relation; what this item removes is the ownership split that
  made the defence necessary, which is a reason and not a line of code.
* The full verification build is green with no generated-output diff in `graphitron-sakila-example`.
  A move that changes an emitted file is a move that did something else too.
