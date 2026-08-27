---
id: R864
title: "Capture moves below the generator: the fact tier becomes a module boundary"
status: Spec
bucket: architecture
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# Capture moves below the generator: the fact tier becomes a module boundary

R682 states the destination in one sentence: capture writes facts, planners read facts and produce
commands, emitters render commands, and validation is questions asked of the facts. Each tier reads
only the tier below it.

That sentence has a module line in it, and the reactor does not draw it. Capture, the derivations,
the SDL reader and the catalog reader all live in `graphitron` beside the planners and the emitters
that are supposed to sit above them. Nothing but review keeps a planner from reaching past the facts
into the thing that produced them, and the one time that discipline slipped it produced exactly the
defect this item is named after: `FactCapture.detect` writes `walk_type_backing_class` from the
classification walk, so the fact tier reads its consumer.

Move the fact tier into `graphitron-model` and the rule stops being discipline. A planner that
imports a crawler does not compile.

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
session opens a second store of its own, because `FactCapture.runInternal` is where
`GraphitronModelStore.openAt` gets called and no caller can substitute a handle. The session then
calls `Materializations.refreshAll` at start, and its own comment says why: it cannot know whether
the pass that just ran captured into the same file. That defensive refresh goes away with the
ownership split that caused it.

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
facts triangle, with a named dial for the legacy imports that have not moved yet. Adding `capture` to
that guard is the cheap version of this item and it is not enough, for a reason the walk-side write
demonstrates: a dial is a list somebody maintains, entries go on it as easily as they come off, and
the guard cannot stop a new relation from being written from the walk, only record that it was. The
module boundary is not a list. It is javac.

The second reason is that the dial's endpoint *is* this item. Every entry on it would have to be
resolved before the guard read clean, and resolving them is the move. Running the guard first buys a
progress meter on work that has to happen anyway, at the cost of a migration dial that outlives its
purpose.

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
  down; `CatalogBuilder.projectTypesByName` and `TypeBackingShape` read the walked model and die
  with it.
* `rewrite/lint` splits. `LintConfig` is a value in `SubjectConfig` and goes down; the rule engine
  is analysis over a read schema and stays above.
* `ClassifiedRun`, `TypeBackingClasses` and `TypeBackingClassRows` neither move nor split. They are
  the walk-side write, and they are inverted: see below.

## The one edge that must be inverted first

`FactCapture.detect` does two unrelated things in one arm: it writes `walk_type_backing_class` from
the run's `ClassifiedRun`, and it runs the store-backed detections. That write is the only edge from
the generator into capture, and it cannot survive the move, because capture in a module below the
generator cannot import the walk that produces it.

**Inverted, not deleted, and the distinction matters.** The relation is a declared-transitional
shadow: it exists so the store-native backing derivation has something inside the store to diff
against, and it earns its keep until that derivation is trusted. Deleting it here would discard a
live instrument to make a module boundary hold. Inverting it costs nothing and holds the boundary
just as well: the walk keeps writing its own shadow rows, calling a writer that lives below the line,
which is a dependency in the legal direction. Capture stops knowing the walk exists. The relation
retires later on its own criterion, which is R682's business and not this item's.

The lift is in two steps and only the first is a prerequisite here. Separating
`TypeBackingClassRows.write` from the detections inside `detect` is what R865 also needs, since a
capture-only run wants neither the detections nor a walk it did not run; whichever item lands first
pays for it. Moving the call site up to the walk is this item's, and it is what makes the edge point
downward.

That sequencing is the argument for doing this before the shadow retires rather than after. The
boundary is what stops the *next* walk-side write from being added, and a boundary that arrives after
the last one is removed has prevented nothing.

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

Before, not after. Moving capture down forces the back-edge to be cut, which is the cheapest
available enforcement of "the walk does not capture" and is available now. Waiting for R682 means
waiting for a large in-flight item to finish before the boundary that would have protected it
exists, and every relation added in the meantime is one more thing the boundary has to be talked
past.

R682 is not blocked by this. The leaf zoo dissolves above the line either way; this item decides
where the line is, not what is left standing on the far side of it.

## Decisions this spec makes

* **`rewrite/derive` sits below the line.** The rules are derivations producing rows, and R682 says
  validation is questions asked of the facts. The generator reads the answers through
  `StoreDetections` and never sees a rule, so a rule cannot come to depend on a plan.
* **One module, not two.** A separate `graphitron-capture` between the model and the generator was
  the alternative, and the only argument for it was keeping `graphitron-model` small enough for
  `roadmap-tool` to depend on. That is out of scope (below), so the argument goes with it. Capture's
  contract is total transcription of what the DDL declares, which makes the schema and its only
  writer one unit; splitting them lets a DDL change and its capture change land in two modules and
  skew.
* **The move is mechanical, not a redesign.** No relation changes shape, no generated output
  changes, and no store answers differently. A commit that moves a class and a commit that changes
  what it does are separate commits.

## Out of scope

`roadmap-tool` depends on `graphitron-model` alone, booting the store through
`GraphitronModelStore.open()` and reading the live catalog and `COMMENT ON` prose to render the
schema reference pages and check identifier drift. It will inherit graphql-java, slf4j and the javac
Tree API from the widened module. That is an accepted build-time cost on a build tool, and untangling
`roadmap-tool` is its own problem, deliberately not solved here and deliberately not allowed to
shape this boundary.

## How we will know it is delivered

* `graphitron-model` compiles with `rewrite/capture` inside it and no dependency on `graphitron`.
  The reactor's module order is the proof: a cycle does not build.
* `GraphQLRewriteGenerator` has no `FactCapture` import and takes a `StoreHandle` on the entry
  points that read facts. Grep is the check, and it is the item's central claim.
* `DevMojo` opens one store, and the `refreshAll` at session start is either removed or carries a
  reason that is not "we cannot know whether the pass captured".
* `ClassifiedRun` is gone from capture's signatures and `walk_type_backing_class` still holds the
  same rows, written from the walk's own side. The shadow surviving the move intact is what says the
  edge was inverted rather than the instrument discarded.
* The full verification build is green with no generated-output diff in `graphitron-sakila-example`.
  A move that changes an emitted file is a move that did something else too.
