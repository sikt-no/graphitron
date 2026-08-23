---
id: R814
title: "The architecture docs describe the surface being drained as if it were the design"
status: Ready
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

## Goal

When this lands, a contributor or agent session opening `docs/architecture/` learns the
architecture the generator is being built into, and is routed to the package where a change
belongs. Concretely, four things become true that are false today:

1. `reference/code-generation-triggers.adoc` answers its original question, "I wrote this schema
   pattern; what does graphitron generate, and where does it land", in a vocabulary that survives
   R682's deletion of the classification walk and the sealed leaf taxonomy. The page traces the
   chain the architecture actually runs (captured facts, then per-coordinate verdicts derived as
   `intent_` views, then command rows, then rendered units) and takes its closed vocabulary from
   the verdict layer, the one tier of that chain that is permanent, many-consumer, and only added
   to by R682.
2. The pages describe the present tense. Narration of how a shape changed lives in
   `docs/history/`, which already declares the precedence rule this needs ("where the two
   disagree, Architecture wins"), or is deleted where it carries nothing.
3. Every enumerable claim on a page renders from a gated source, or is not on the page. The
   variant tables, the dispatch-path column and the Source Map are hand-maintained censuses of
   things the build already knows; they are replaced by build-generated fragments and by worked
   examples whose blocks a guard holds verbatim.
4. A citation cannot rot silently. A dangling symbol or a roadmap id in an architecture page
   fails the build, the way a dangling `{@link}` and a stale store identifier already do.

The measurable form of (4): the survey's three dangling symbols and 63 roadmap-id citations go to
zero, and a planted regression of each fails the build.

## Design

The project already holds the principle this item applies, in
`principles/development-principles.adoc`: an unguarded census drifts silently, and a consumer
should receive "what to do", never "what to interpret". The architecture pages are a consumer of
the model that violates both. They hand the reader a hand-maintained census (the variant tables,
the Source Map) of a surface that is draining, and they ask the reader to interpret which half of
a two-vocabulary page is current.

The fix has two halves. Tree-wide, each class of claim moves onto a source that already exists
and is already gated, and what is left over is deleted. On the one page whose organizing frame
*is* the draining surface, `reference/code-generation-triggers.adoc`, that is not enough: cleaning
its leaf tables would still leave a reference page about the thing R682 deletes. That page is
rebuilt around the fact-based chain instead, and this section owns the design of the rebuild.
Both halves take sessions to land, so the first slice does neither: it marks the outdated content
as outdated, because the marking's value is exactly the window while the rest runs.

### The replacement page

The page keeps its slug (it is pinned in `ClassifiedDocTest.PAGE_CANDIDATES`,
`LinkTarget.ARCH_QUADRANT`, xrefs from `index.adoc`, and roadmap items; a rename buys nothing the
rebuild does not). Its question stays the original one: given a schema pattern, what does
graphitron generate, and where does each piece land. The answer is organized as the chain the
pipeline runs, in six sections:

1. **The chain**, short, one diagram: capture transcribes the schema into facts, derivation
   resolves each coordinate's verdict as views over those facts, planning joins verdicts into
   command rows, the render shell folds each row into a generated unit. Links carry the depth:
   `explanation/pipeline-overview.adoc` for stage order, `explanation/fact-model.adoc` for the
   modeling discipline, the generated schema reference for per-relation detail.
2. **How a coordinate gets its verdict**, the page's closed vocabulary and the successor of the
   "Classification Vocabulary" and three-axis sections. Independent questions asked of the facts,
   each naming its `intent_` relation and linking its generated schema-reference page rather than
   restating it: what the author claimed (`intent_authored_field_claim`,
   `intent_authored_type_claim`, one arm per claiming directive), which table a type binds
   (`intent_bound_table`), what the catalog matches (`intent_column_match_claim`), what won
   (`intent_resolved_field_claim`), and what contradicts (`intent_authored_claim_conflict`, a
   build error). The old axes survive here as separate facts composed by joins, never as leaf
   names.
3. **What one generated thing is**: the command relations, one section rather than the spine. A
   build-generated table lists each relation with the grain sentence its own javadoc states ("one
   row per migrated root SELECT coordinate", "one row per projection unit", and so on), and the
   prose states the closure per relation with the enforcer that actually holds it, disclosing the
   gaps the gates themselves disclose (the batched polymorphic pair's rows methods are the one
   decided emitted-and-uncommitted population, per `LauncherRelationClosureTest`'s own javadoc).
   The single-mint naming rule (`GeneratedUnits`, held by `PackageImportDirectionTest`) is stated
   here too, since it is what makes a unit name in an outcome block trustworthy.
4. **Worked examples**, the bulk, where the variant tables dissolve. The corpus stays the source
   of truth and the page stays a view rendered over it, per the `classified-corpus` skill's own
   framing. Each example is a minimal pair: the corpus-rendered SDL block, verbatim and
   drift-guarded by `ClassifiedDocTest` as today, followed by a machine-rendered **outcome block**
   (design below) stating the coordinate's verdicts and the emitted unit and method names.
5. **When nothing is generated**: a failing pattern produces diagnostic rows, not command rows,
   rendered through the same worked-example machinery; links `explanation/typed-rejection.adoc`.
6. **Where the code lives**: the top-level packages (`command`, `facts`, `plan`, `render`, and
   `rewrite` marked as draining), replacing the Source Map whose opening claim the survey found
   false. The map states ownership, not destiny: R682 records `rewrite/derive` as misnamed with
   its split filed separately, so the map marks it transitional rather than naming a destination.

### The spine is the verdict layer, not the command relations

An earlier draft of this design took the command relations as the page's closed vocabulary, on
the claim that R682 changes what the plan reads, not what it produces. Both halves of that were
wrong, and the correction is load-bearing enough to record. The command tier is the narrowest
view of the model, run-scoped and single-consumer by the fact model's own account, and its
populations are literally migration-scoped today: `LauncherRelation`'s javadoc says "one row per
*migrated* root SELECT coordinate", with the membership enforcer landing only with the closing
slice, and `FetcherEdgeRelation` covers "the covered non-launcher families". And R682 reshapes
the command tier by name: the fetcher family's per-coordinate command relation is yet to be
minted, `RoutineChain` is on its retired-vocabulary list, `EmitPlan.produce`'s `GraphitronSchema`
parameter retires, and the completeness gates re-key onto declared arm sets the relations do not
carry yet. Organizing the page around that tier would relocate the item's own defect, documenting
the transitional surface, one tier over. The layer R682 only adds to is the fact and verdict
layer, so the chain is the page's structure, the verdict layer is its closed vocabulary, and the
command relations are one honest section of the back half.

### The outcome block

The mechanism this item adds. For each doc example, a renderer runs the fixture through the
pipeline and renders, beside the SDL block, what came out: the coordinate's verdict rows spelled
in the `intent_` views' closed verdict vocabularies, and the emitted unit and method names
(signatures at most, never bodies). A drift guard asserts the block verbatim in the page, the way
`ClassifiedDocTest` asserts the SDL half today, so the "what gets generated" half of every
example becomes drift-guarded by construction. Corpus fixtures classify against the standard
Sakila catalog and capture is total, so the machinery to put a fixture's facts in a store exists;
what is new is the rendering and the guard.

Two refusals define the block's content, both inherited from R682's gates. It renders no command
rows: R682 explicitly retired row identity as a shipped obligation (a row diff is a debugging aid
while converting, never a test), and a doc-guarded verbatim command-row block would reinstate
that obligation over vocabulary the same item is dismantling. And it renders no generated bodies,
per the tier rule that code-string assertions on bodies are banned everywhere
(`LauncherRelationClosureTest`'s "signature structure only" is the precedent). Verdict rows and
emitted names are both invariant across the R682 cutover by that item's own gates: verdicts land
in the store as views, and output identity holds. The block is also clean against the oracle
rule: it compares this run's own output against a checked-in expectation, never a store-derived
answer against a walk-derived one, so the fact that the walk transitively feeds the plan until
R682 lands is immaterial to it, and worth exactly one transitional sentence on the page.

One dependency is named now so the doc machinery never blocks the deletion:
`QueryViewRenderer.render` reaches the schema through `GraphitronSchemaBuilder.buildBundle`, and
the builder is on R682's terminal-deletion list. The successor is the gatherer's own assembly
stage (stage 3 of the fact model's five-stage gathering pipeline), and the cutover belongs to
whichever increment retires the builder; this item only avoids deepening the dependency.

### The command-relation fragment renders itself

The table in the page's section 3 is not hand-written and not enumeration-gated. An enumeration
meta-test would close membership while the load-bearing columns (what one row asserts, which gate
holds it) rot silently. The grain sentence is already stated once per relation, in its own
javadoc, the plan tier's analogue of the DDL's `COMMENT ON`; the fragment renders from the
relation types and those sentences, in the generated schema reference's shape: generated at
build, never committed, included by the page. A fragment that is never committed cannot drift and
needs no verify guard, which sidesteps the R348 trap by construction. The universe comes from the
types themselves, and one of them keeps the triangle honest: `KeyProjectionRelation` lives in
`no.sikt.graphitron.command`, not `plan`, so the scan covers both packages rather than assuming
the geography.

### The narration has an established home

`docs/history/` exists, is rendered into the site, and states its own precedence rule. Demoting
the legacy-divergence section and the change narration there is a move the tree already supports.

### Ownership boundary with R682

R682's terminal step already claims a doc sweep over this page ("`code-generation-triggers` with
`index.adoc`'s pointer to it"), so the split is stated here to keep one page from being owned
twice. This item rebuilds the page onto the surviving vocabulary now, while the walk is live;
R682's terminal sweep then deletes the page's one transitional sentence and whatever names retire
with the walk, and restructures nothing. The corpus's own `@classified` three-axis assertions,
which pin sealed-leaf dimensional verdicts, retire with the zoo under R682, not here; this item's
obligation is the page-side half of the same pre-deletion rule, applied per example in slice 3.

### What the gates must cover

The survey's rot happened in the two habitats nothing scans.
`RoadmapReferenceGuardTest` parses Java comment and string-literal regions;
`TransientCitationCheck` scans `CLAUDE.md` and `.claude/web-environment.md`, and its own javadoc
notes the habitats it does not reach. `.adoc` under `docs/` is in neither. Likewise
`SchemaIdentifierDriftCheck` resolves store identifiers cited in these very pages against the
booted store, and its javadoc names the consequence of not doing so: "which is exactly how the old
pipeline overview came to describe a retired architecture as current." No sibling resolves the
Java symbols the same pages cite, which is why `ColumnFetcherClassGenerator` survived its own
deletion.

On placement: the symbol gate belongs in the `graphitron` test tier beside
`RoadmapReferenceGuardTest`, not in `roadmap-tool`. `roadmap-tool` depends on `graphitron-model`
and not on `graphitron`, so a check living there cannot see the generator classes it would need to
resolve against, and adding that dependency to make a docs check work is the wrong direction. The
citation gate is the opposite case: `TransientCitationCheck` is pure text matching with no
classpath need, so extending its existing habitat list is right, and cheap by its own admission
("Adding a document is one line").

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
pointers, notably "UPSERT generation gated pending R145", "covered by a follow-up Mojo
configuration item (R192)", "the owned-connection path (R429, recommended)", and "the schema-side
inference that divines the tenant key is R45's tenant-column work".

This has already rotted rather than being at risk of rotting. Of 35 distinct ids sampled across
the pages, 29 have no item file left: they shipped, and the file was deleted on Done as the
workflow prescribes, leaving the numbering gap the convention intends. The roadmap renders into
the published site, so those 29 citations point at nothing a reader can open. All 35 do appear in
`roadmap/changelog.md`, which is why the changelog is the viable redirect: it is one of the three
permanent artifacts the citation rule already allows to be cited by path.

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

## Implementation

Five slices, 0 through 4. The ordering between them is load-bearing in three places: slice 0
lands first because its value is the window while the rest runs, the gates land before the prose
cleanup, so the cleanup is verified by the mechanism that will hold it rather than by a reviewer
reading 3,757 lines, and the renderers land before the page sections that include their output.
Within that constraint each slice is independently committable and independently pushable to
trunk.

### Slice 0: mark the outdated content as outdated

The rebuild's value arrives at the end; until then every reader the survey describes is still
routed into the draining surface with nothing on the section saying so. So the first commit
changes no behavior and fixes nothing: each section a later slice replaces gains one admonition,
in present tense, saying what the section describes and where the current statement lives. Two
kinds of mark, because the survey found two kinds of content:

- **Transitional but accurate.** The variant tables and the leaf taxonomy describe live behavior
  of the surface being drained; their mark says so and points at
  `explanation/pipeline-overview.adoc` for the destination chain. The mark uses the form R810
  owns (transitional, accurate today, where the rationale lives); page-grain and entry-point
  marking is R810's charter, not this slice's. The "Classification Vocabulary" section already
  carries its superseded-framing NOTE and is only normalized to the shared shape.
- **Known stale.** The Source Map's opening claim is false and its generator lists have drifted;
  its mark says the map covers the draining `rewrite/` tree only and does not name the
  destination packages. The mark states the incompleteness, the replacement map is slice 3's.
  `reference/argument-resolution.adoc`'s legacy section gains a mark naming its comparison target
  as the retired generator.

Rules the marks obey: present tense only, no roadmap ids in the mark text (the pages render to
the public site, and the slice-1 gates must not fail on text this slice added), one uniform shape
rather than per-section improvisation, and each mark is deleted by the slice that rebuilds its
section; a mark surviving its section's rebuild is a defect. A mark is never a substitute for the
rebuild: this slice fixes nothing, so slice 1's guards still fail on the tree it leaves.

### Slice 1: the gates

Nothing in this slice touches a page. It ends with two guards that fail on today's tree, which is
the point: a guard that passes on arrival proves nothing.

- Extend `TransientCitationCheck` to the architecture pages. Its `SCANNED_DOCS` is a fixed list of
  two paths with an "every entry must exist" floor; architecture pages are a tree, so add a walked
  habitat beside the list rather than enumerating 19 paths that a page move invalidates. Keep the
  anti-vacuous-pass discipline the existing code and `ArchQuadrantBindingTest` both apply: a walk
  that finds no pages fails, rather than passing on an empty set.
- Add a doc-symbol drift gate as a `graphitron` test-tier meta-test beside
  `RoadmapReferenceGuardTest`. It reads every `docs/architecture/**.adoc` (locating the tree
  robustly against the working directory, as `ClassifiedDocTest.PAGE_CANDIDATES` already does with
  its two candidate relative paths), extracts backtick-delimited CamelCase spans,
  and fails on a span that resolves to no type on the reactor classpath. The universe must come
  from the classpath, not a regex over source files, for the same reason `SchemaIdentifierDriftCheck`
  boots the store rather than parsing the DDL: two mechanisms of different fidelity answering
  "what exists" is the defect, not the fix.
- The extractor needs an ignore rule, and getting it wrong in either direction is the risk in this
  slice. A backticked CamelCase span is not always a type: `SelectedField.getArguments()`,
  `PageInfo`, `LEFT JOIN`, and generated-output names a consumer sees but the reactor never
  declares are all legitimate. Recommend an explicit, commented exemption set in the guard (the
  shape `ExemptionRegistry.CORPUS_NO_CASE_REQUIRED` already uses) over a clever heuristic, so each
  exemption is a reviewable claim rather than a silent miss.

### Slice 2: the two renderers

Nothing in this slice deletes prose yet; it ends with the machinery the rebuild consumes.

- The command-relation fragment: rendered at build from the `*Relation` types (across `plan` and
  `command`) and their grain javadoc, in the generated schema reference's shape, never committed,
  included by the page. The earlier draft's leaf-to-emission-path appendix rendered from
  `GeneratorCoverageTest`'s partition is dropped: it would gate a table about the surface R682
  deletes, and the closed vocabulary worth rendering is the one that survives.
- The outcome-block renderer and its drift guard: run a doc example's fixture through the
  pipeline, render verdict rows plus emitted unit and method names as AsciiDoc, and assert the
  block verbatim in the page with the same failure UX `ClassifiedDocTest` has (the message prints
  the exact block to paste).

### Slice 3: rebuild the page, one example per commit

The bulk of the item's value, and the slice most likely to split across sessions. First the
skeleton: the chain intro, the verdict-layer section, the command-relation section (the fragment
include plus the per-relation closure prose), the rejection section and the package map land, and
the "Classification Vocabulary" section (95 lines, self-declared historical) and the Source Map
are deleted with them. Then the examples, per the `classified-corpus` loop adapted to the new
assertion. Only 2 of the 55 corpus examples carry a projection `query` today (`catalog` and
`paginated-joined-table-interface`), which is what makes an example render; the other 53 are
tested and invisible while the tables restate their verdicts in ungated prose beside them. The
page's other six GraphQL blocks are hand-written, not corpus-rendered, so they carry no guard
either.

- Promoting an example means, in one commit: add a projection `query` where the example lacks
  one, paste the rendered SDL and outcome blocks under prose, and delete the leaf-named table
  rows and prose the example subsumes. The verdict moves onto a surviving vocabulary in the
  promoting commit, which is this item's instance of R682's pre-deletion obligation (re-key
  before the last leaf reader moves, never with the deletion commit); prose stating a verdict as
  a leaf name does not survive the commit that renders its example.
- Where a row's verdict has no corpus example, author one rather than keeping the row. Where a
  row is not corpus material by the skill's own bucketing (rejection rows, input-side rows, slot
  assertions), it is either restated as a rejection worked example (rejection rows render through
  the same machinery) or kept with its gate named on the page, so an ungated-looking table is
  visibly not ungated. A verdict with no spelling in a surviving vocabulary yet marks a missing
  relation, R682's to land, and its row keeps its gate note until then.
- The DataLoader-category table and the "Implicit Classification Rules" table go through the same
  test: render it or state its gate.
- The page's archaeology (the survey's list: the three "no longer tombstones" cells, the two
  merged-leaf-pair sentences, "kept stable from earlier naming", the retired-`LiftedHop` row) and
  its roadmap-id citations are stripped as the sections carrying them are rebuilt.

### Slice 4: the rest of the tree

- Fix `index.adoc`, which is the entry point and currently routes a reader wanting "the
  classification taxonomy" at the draining surface while calling it transitional in the same
  sentence. Lead with `explanation/pipeline-overview.adoc` and the rebuilt triggers page.
- Move `reference/argument-resolution.adoc`'s "Legacy behavior reference (and intentional
  divergence)" section (roughly 90 lines) to `docs/history/`, leaving the current rule stated in
  present tense plus an xref. Coordinate with R207, which already names this page.
- Remove the remaining roadmap-id citations across the other pages (the survey counts 63 over 8
  pages). Most are pure decoration and cost nothing to delete: "`AuthorError.TypeConflict` is the
  fifth `AuthorError` arm, surfacing R190's cross-site `contextArgument` type-agreement check"
  names both the arm and the check, so the id carries no information.
  `explanation/typed-rejection.adoc` is the densest page at 16 and also the cheapest. For the few
  live forward pointers ("UPSERT generation gated pending R145", "covered by a follow-up Mojo
  configuration item (R192)"), state the limitation as a present-tense fact without the id; a
  reader needs to know UPSERT is rejected today, not which item will change that.
- Strip the archaeology the survey names on the other pages, such as `dispatch-axes.adoc`'s "the
  pre-decomposition shape of this chapter is in the git history".
- Add the two missing `LinkTarget.ARCH_QUADRANT` entries, `fact-model` and `naming-the-row`. They
  are absent today, so roadmap items linking those slugs render flat; any page move in this item
  touches that map anyway.

## Tests

- `TransientCitationCheckTest` gains cases for the walked habitat: a planted `R<n>` in an
  architecture page fails, a `roadmap/changelog.md` path cite passes (it is already a
  `PERMANENT_ARTIFACTS` entry), and an empty walk fails rather than passing vacuously.
- The new symbol gate needs its own planted-regression test, in both directions: a backticked name
  that resolves passes, a deleted name fails, and an exempted span passes for a stated reason. The
  three names the survey found (`ColumnFetcherClassGenerator`, `InputDirectiveInputTypes`,
  `FetchRelated`) are the natural first fixtures.
- The outcome-block guard covers each promoted example the way `ClassifiedDocTest` covers the SDL
  half, with its own planted regression in both directions: an edited block fails, a matching
  block passes. `ClassifiedDocTest` itself keeps guarding the SDL blocks unchanged.
- The command-relation fragment needs no verify guard (never committed), but the docs build must
  fail when the fragment is absent or the include dangles, and the fragment's own render asserts
  it found every `*Relation` type its scan claims, so an added relation appears and a renamed one
  fails the render rather than vanishing.
- `VariantCoverageTest` and `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`
  keep their coverage obligations unchanged; this item neither widens nor narrows what the corpus
  must demonstrate, it changes what the page renders about it.
- Full `mvn install -Plocal-db`, since `graphitron-docs` renders the tree and a broken xref or a
  markdown-shaped table fails the build.

## Constraints the plan must respect

- `ClassifiedDocTest.PAGE_CANDIDATES` hardcodes
  `docs/architecture/reference/code-generation-triggers.adoc`. The rebuild keeps that path, and
  the rendered blocks must land on whichever page the list names.
- The `command` / `plan` / `render` triangle must be stated correctly on the page that teaches
  it: `KeyProjectionRelation` lives in `command`, not `plan`, so no prose asserts "the command
  relations in `plan`".
- No outcome block asserts command rows or generated bodies; verdict rows and unit and method
  names are the ceiling (the design's two refusals).
- The package map marks `rewrite/derive` transitional rather than naming a destination; its split
  and rename are filed separately.
- `LinkTarget.ARCH_QUADRANT` is the roadmap tool's private copy of the docs layout, gated by
  `ArchQuadrantBindingTest`. Any page add, move or rename updates it in the same commit.
- Roadmap items and the changelog xref these pages by slug. `LinkTargetRoundTripTest` pins the
  emitter direction; prefer keeping slugs stable over renaming for tidiness.
- `docs/architecture/` renders to the public site, so no roadmap-internal vocabulary survives in
  the prose. This is the workflow's own user-facing-doc check applied to the pages it is about.
- Per `CLAUDE.md`, generated fragments must use AsciiDoc table syntax; the roadmap-tool
  `check-adoc-tables` step fails the build on a markdown-shaped row.

## Relation to existing items

- R682 (Planners read facts, emitters read commands) is the item this design leans on, at three
  points. The spine choice rests on which layer R682 reshapes (the command tier) versus only adds
  to (facts and verdicts). The per-example re-key in slice 3 is this item's instance of R682's
  pre-deletion obligation. And R682's terminal step claims a doc sweep over this same page; the
  ownership split in the design keeps that sweep a deletion of transitional sentences rather than
  a second restructure. The corpus's `@classified` leaf assertions retire with R682, not here.
- R810 (Transitional surfaces say so where a reader arrives, and say why) owns the page-grain and
  entry-point markers and the marker convention itself; slice 0 here is the section-grain
  application of that form to the sections this item's later slices replace. No ordering between
  the two items: whichever lands first, the other reuses its stated form rather than minting a
  second one. R810's own non-goals defer the taxonomy page's fate to another item; this item is
  that item, and the lead paragraph R810 adds to the page is subsumed by the rebuilt chain intro
  in slice 3.
- R207 (Audit design-doc claims for implementation conformance) is adjacent but distinct: it
  covers claims that are *wrong* (doc says X, code does Y). This item covers claims that are
  *true but about the wrong thing*, plus citation rot. The overlap is on
  `reference/argument-resolution.adoc`; whichever runs second reads the other's findings first,
  since R207 checks whether a claim is true and this item checks whether it is about the current
  design.
- R758 (The fact model page never learns the materialization registry) is a specific gap on one of
  the pages this survey found sound. No conflict, but work touching `explanation/fact-model.adoc`
  should check R758's state.
- R348 (Regenerate and guard the generated supported-schema-shapes migration doc against drift):
  this item's fragments sidestep the defect R348 names by being generated at build and never
  committed, so there is no checked-in copy to drift and no verify guard to forget. If R348 wants
  the same shape for the manual's fragments, this item's renderer is the precedent; nothing here
  waits on it.

## Open forks for the reviewer

The original spec's first fork, whether `code-generation-triggers.adoc` stays one page, is
settled by this revision: it stays one page under its slug, rebuilt around the chain with the
verdict layer as its closed vocabulary, for the reasons the design states. Three forks remain
open.

- **How much of the verdict layer does an outcome block spell?** The lean form renders the
  resolved verdict (`intent_resolved_field_claim` and kin) plus the emitted names; the teaching
  form also renders the authored claims that produced the resolution, which shows the chain but
  doubles the block. The plan starts lean and lets a worked example that needs the chain (the
  conflict example, say) opt into the fuller form; a reviewer preferring one form everywhere
  should say so.
- **How wide does the symbol gate scan?** The plan scopes it to `docs/architecture/**.adoc`, which
  is where the survey found the rot. `docs/manual/` cites symbols too, and the same guard would
  cover it for nearly free, but the manual is author-facing and cites consumer-visible generated
  names the reactor never declares, so the exemption set would grow in a way that weakens the
  guard. Recommend keeping it narrow now and widening deliberately.
- **Is the roadmap-id rule absolute in `.adoc`?** The plan says yes, matching the Java-source rule,
  with provenance redirected to `roadmap/changelog.md` (a permanent artifact that may be cited by
  path). The survey's evidence supports it: of 35 ids sampled across the pages, 29 have no item
  file left, so those citations are already unresolvable for a reader, and the pages render to a
  public site where the roadmap directory is not the reader's to search. A reviewer who wants
  provenance preserved should say whether the changelog redirect is sufficient or whether some
  narrower allowance belongs in the gate.
