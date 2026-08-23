---
id: R814
title: "The architecture docs describe the surface being drained as if it were the design"
status: Backlog
bucket: architecture
priority: 3
theme: docs
depends-on: []
created: 2026-08-23
last-updated: 2026-08-23
---

# The architecture docs describe the surface being drained as if it were the design

`docs/architecture/` is the contributor-facing entry point: a new contributor reads it to learn
what the generator is and where a change goes. Today most of it answers a different question.
The pages are organized around the classification walk and its sealed leaf taxonomy, which
`explanation/pipeline-overview.adoc` itself calls "a surface being drained, not a place to
extend"; the architecture the walk is being drained *into* (the fact store, the `plan` command
relations, the `render` fold) appears as a caveat on pages about the old shape rather than as the
frame. Layered on top of that is accumulated change narration: sentences whose content is a diff
rather than a fact, roadmap-item ids used as forward pointers, and one page carrying two competing
vocabularies for the same thing at once. The net effect is that the docs teach a reader the shape
we are leaving, and route them into it.

This is a Backlog problem statement. It records what the survey found so a Spec author does not
have to re-survey; it deliberately does not yet decide which pages get rewritten, deleted, or
demoted to history, which is the Spec's call.

## What the survey found

The survey read all 19 pages under `docs/architecture/` (3,757 lines) and cross-checked their
symbol citations against the live sources. Findings group into six kinds.

### 1. The transitional walk is the organizing frame, not a chapter

`reference/code-generation-triggers.adoc` is 756 lines, the largest page in the tree, and almost
all of it enumerates the leaf taxonomy `GraphitronSchemaBuilder` produces: variant tables per
field position, per return shape, per DataLoader category. The store, the plan and the render fold
get one paragraph at the top of the page. `index.adoc` reinforces this by pointing a reader who
wants "the classification taxonomy" at that page as the primary reference and naming the walk
"transitional" in the same sentence, without saying what to read instead.

The sharpest instance is the page's Source Map, which opens "All source lives under
`graphitron/src/main/java/no/sikt/graphitron/rewrite/`". That is false, and it is false in exactly
the direction that matters: `no.sikt.graphitron.plan` (16 files), `no.sikt.graphitron.render` (32),
`no.sikt.graphitron.command` (35) and `no.sikt.graphitron.facts` (18) are top-level siblings of
`rewrite`, and none of the four has a Source Map section. `plan/EmitPlan` and the renderers appear
only as passing mentions inside a paragraph about condition glue. So a contributor who follows the
Source Map to find where a change goes is routed into `rewrite/` and never learns the four
packages that hold the destination.

### 2. Archaeology inside reference prose

Statements whose content is a change rather than a fact, sitting in the tables and paragraphs a
reader consults to learn current behavior:

- "R432 merged the former Split/Record leaf pairs onto these source-gated leaves" and "R432
  collapsed the former Split/Record pairs onto the source-gated batched leaf, and the lookup
  leaves later folded onto their fetch siblings" in `reference/code-generation-triggers.adoc`.
- "the walk no longer tombstones conflicts", three times in the same page's variant tables, as the
  cell value in the `GraphitronType` / `MutationField` / `ChildField` conflict rows.
- "The `Record*` variant names below are reflection-derived classifications, kept stable from
  earlier naming", introducing the class-backed child-field table.
- "R431 retired the transitional `LiftedHop` onto `ParentCorrelation.OnLiftedSlots`" in the
  Source Map's join-path row.
- "the pre-decomposition shape of this chapter is in the git history" in
  `explanation/dispatch-axes.adoc`, a sentence whose only content is where we have been.

A reader learning how classification works today has to parse a diff against a shape they never
saw. Note that this is a distinct failure from the one `RoadmapReferenceGuardTest` already
prevents in Java sources: the guard covers javadoc, comments and main-source string literals, and
`check-transient-citations` covers `CLAUDE.md` and `.claude/web-environment.md`, but nothing covers
`.adoc` under `docs/`.

### 3. Two competing vocabularies on one page

`reference/code-generation-triggers.adoc` carries a 95-line "Classification Vocabulary" section
(source context, target type, scope, derived tables, conditions, structural properties) directly
above the canonical "Field Classification" section, prefaced by a NOTE that reads "*Original
framing.* ... Treat Field Classification as canonical ... the terms below survive as the
historical framing". The superseded model is presented first, at length, with its own state
diagram and four tables. A reader who stops before the NOTE learns the wrong model; a reader who
reads both has to hold two vocabularies for one thing.

### 4. Unguarded symbol drift

Of 226 distinct backtick-quoted CamelCase symbols cited in
`reference/code-generation-triggers.adoc`, three resolve to nothing anywhere in the Java sources:
`ColumnFetcherClassGenerator`, `InputDirectiveInputTypes`, and `FetchRelated` (the last named as
one of the four members of the derived layer). The same page's "Runtime helpers (`util/`)" row
lists nine generators and omits exactly nine live ones: `ConnectionFetcherClassGenerator`,
`ConnectionRuntimeClassGenerator`, `ErrorTypeFetcherClassGenerator`,
`GraphitronConnectionInstrumentationGenerator`, `GraphitronTransactionProviderGenerator`,
`LightFetcherClassGenerator`, `OneOfDirectiveSdlGenerator`,
`PolymorphicSelectionSetClassGenerator` and `SelectionOccurrencesClassGenerator`.

There is a direct precedent for the fix shape. `SchemaIdentifierDriftCheck` exists because store
identifier citations drifted silently, and its own javadoc names the consequence: "which is
exactly how the old pipeline overview came to describe a retired architecture as current". No
equivalent guard covers Java symbols cited in the architecture pages, which is why these three
went stale unnoticed. Whether this item adds that guard or only fixes the current drift is a
Spec decision, but the survey's position is that a cleanup with no gate will re-rot.

### 5. Roadmap ids as forward pointers

63 `R<n>` citations across 8 of the 19 pages (`explanation/typed-rejection.adoc` 16,
`principles/development-principles.adoc` 9, `explanation/dispatch-axes.adoc` 5,
`reference/code-generation-triggers.adoc` 4, `reference/runtime-extension-points.adoc` 3, plus
`reference/modules.adoc`, `how-to/dev-loop-internals.adoc`, `how-to/release-natives.adoc`). Some
are provenance ("R50 is the worked example"), which the changelog can carry; some are live forward
pointers that will rot silently when the item ships or is discarded, notably "UPSERT generation
gated pending R145", "covered by a follow-up Mojo configuration item (R192)", "the owned-connection
path (R429, recommended)", and "the schema-side inference that divines the tenant key is R45's
tenant-column work". The published site renders these pages, so a reader outside the repo gets an
id with no directory to resolve it in.

### 6. Comparison against the retired generator as a load-bearing section

`reference/argument-resolution.adoc` carries "Legacy behavior reference (and intentional
divergence)", roughly 90 lines comparing rewrite semantics against the `graphitron-parent`
generator that `principles/development-principles.adoc` declares retired, with 14 uses of
"legacy" across the page. The divergence rationale is worth keeping somewhere; a reference page
for how arguments resolve today is probably not it.

## Pages the survey found sound

Not everything needs work, and the Spec should not treat the tree as uniformly rotten.
`explanation/pipeline-overview.adoc` is the model of the orientation this item wants: it names the
destination first, marks the transitional stage explicitly as transitional, and has a "strangler
frame" section that says what is true today versus what is being drained.
`explanation/fact-model.adoc` and `reference/emitter-conventions.adoc` are likewise oriented on
the design rather than its history. `docs/history/road-to-the-relational-core.adoc` already exists
as the place where "how the architecture got here" belongs, which gives the Spec somewhere to move
narration to rather than only deleting it.

## Relation to existing items

- R207 (Audit design-doc claims for implementation conformance) is adjacent but distinct: it
  covers claims that are *wrong* (doc says X, code does Y). This item covers claims that are
  *true but about the wrong thing*, plus citation rot. The two overlap on
  `reference/argument-resolution.adoc`, which R207 already names; whichever runs second should
  read the other's findings first.
- R758 (The fact model page never learns the materialization registry) is a specific gap on one of
  the pages this survey found sound. No conflict, but a Spec touching `explanation/fact-model.adoc`
  should check R758's state.

## Open questions for the Spec

- Rewrite versus demote. For each page carrying archaeology, is the fix to delete the narration,
  or to move it to `docs/history/`? A blanket answer is probably wrong.
- What replaces `reference/code-generation-triggers.adoc` as the answer to "what does the
  generator do with my schema". The corpus-rendered worked examples in it are gated by
  `ClassifiedDocTest` and are the page's strongest content; the ungated variant tables around them
  are the weakest. That asymmetry likely suggests the shape of the successor.
- Whether the Source Map should exist at all in its current form, or be replaced by a package-level
  map that starts from the five top-level packages (`command`, `facts`, `plan`, `render`, `rewrite`)
  and says which are the destination and which is draining.
- Whether to add a doc-side symbol drift gate modeled on `SchemaIdentifierDriftCheck`, and a
  transient-citation gate over `docs/**.adoc` modeled on `check-transient-citations`. Without at
  least one of them the cleanup has no defense against recurrence.
