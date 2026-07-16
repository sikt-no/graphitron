---
id: R486
title: "Roadmap concept explainers: an interactive developer-facing explanation space under roadmap/concepts/, plus a skill to author the pages"
status: In Review
bucket: architecture
theme: docs
depends-on: []
created: 2026-07-16
last-updated: 2026-07-16
---

# Roadmap concept explainers: an interactive developer-facing explanation space under roadmap/concepts/, plus a skill to author the pages

Roadmap items are dense by design: they are specs, written for a reader who already holds the model. That density is a barrier to understanding, both for a developer new to an area and for one returning to a concept (nodeID, polymorphic join paths, the classification model) that a dozen items reference in passing. There is no gentler, intuition-first surface that explains the *background* behind an item or a recurring concept, with worked examples and links out to the deeper as-built references. This item creates that surface and the tooling to author it.

## What is settled (design discussion, 2026-07-16)

- **A roadmap-adjacent developer explanation space**, distinct from both Diataxis trees: not the consumer manual (`docs/manual/`) and not the as-built contributor architecture tree (`docs/architecture/`). It is intuition-first scaffolding for reading dense, often forward-looking roadmap work, and it *links into* `docs/architecture/explanation/` (and the manual) wherever a concept is already covered as-built, going deep only where the roadmap context is the value.
- **Home: `roadmap/concepts/`.** Co-located with items so the render + link machinery in `roadmap-tool` is reused and item-to-concept cross-linking stays local. `roadmap-tool`'s `readItems` is non-recursive, so a subdirectory does not disturb README generation or item validation.
- **No migrate-on-ship workflow rule.** The concepts space is durable in its own right. When an as-built design changes, the architecture docs are updated as ordinary maintenance (pulling from the explainer if useful); graduation is not a gated step.
- **Concept-keyed, not item-keyed.** The durable, linkable value is the *concept* ("polymorphic child join paths / `@referenceFor`"), which outlives any single item. An item's perishable status folds into the relevant concept page as a "where it stands" section rather than spawning an orphaned per-item page that dies when the item ships.
- **Format: pure HTML/CSS/JS with shared external assets** (`concepts.css` + `concepts.js`), not AsciiDoc-with-passthrough. Rationale: the primary author is an LLM driven by the skill below, and semantic HTML generates cleanly where adoc passthrough is the worst of both worlds (still hand-written HTML, now fenced in `++++` with escaping, prose unable to reference it); a shared external stylesheet removes the per-page copy-paste that was adoc-with-component's only real advantage; the interactivity ceiling (quizzes, side-by-side panes, and more) is unbounded in HTML and capped in adoc. Interactivity is the point: it is what makes a dense concept land.
- **Cross-boundary links are a first-class requirement.** Item-to-item links routinely cross the Done boundary: shipped items have no plan file (deleted on Done) and live only in `changelog.md`, while active items have a file. The linking must handle both targets.

## Spec refinement of one settled point: copy is not verbatim for hrefs

The Backlog body said "copy `roadmap/concepts/*.html` verbatim into the rendered docs output" on the precedent of the `inference-axis-coverage.adoc` copy in `Main.runRenderAdoc` (`Main.java:468` at the time of writing). That precedent does not carry: the copied `.adoc` is still processed by asciidoctor downstream, so its `xref:` targets resolve after the copy. A raw HTML page gets no downstream pass; verbatim-staged bytes are final bytes, and one set of authored hrefs cannot serve both the repo layout (items at `roadmap/<slug>.md`) and the rendered-site layout (items at `roadmap/plans/<slug>.html`).

Resolution: authors write **repo-relative** hrefs (natural for the authoring LLM, consistent with item bodies, works in local `file://` preview; the R458 sketch already does this and its footer flags the site-layout gap), and `roadmap-tool` rewrites `href` values at staging time. Page *content* is still copied untouched; only link targets are mapped, by the same target taxonomy the tool already owns for the md-to-adoc direction. Principles consult (2026-07-16) confirmed this is the shape the normalization discipline favors and flagged the one hazard: the mapping knowledge must not be duplicated. Hence the classifier extraction below.

## Design

### Page contract

- One page per concept at `roadmap/concepts/<slug>.html`; slug is lowercase kebab-case and names the concept, not an item.
- Shared assets `roadmap/concepts/concepts.css` and `roadmap/concepts/concepts.js`, extracted from the sketch's inline `<style>` / `<script>`. Pages reference them relatively (`href="concepts.css"`). The markup vocabulary is the sketch's class set: `tldr`, `note`, `sxs`/`pane` (side-by-side), `quiz`/`choice`/`explain`, `status`/`pill`, `kicker`, plus the shared footer.
- Shared header: kicker line ("Concept explainer" + theme) and a breadcrumb back-link to `../README.md` (rewritten to `../index.html` in the site), since pure HTML inherits no Antora nav chrome.
- Machine contract: the page's `<h1>` carries `data-concept-title="<plain-text title>"`. The attribute value (not the element's inner HTML, which may carry `<code>` markup) is what listings derive from. A page missing the attribute, or with a blank value, **fails the build** (generate, verify, and render-adoc alike) with a message naming the file and the contract; the enforcer is what turns the scrape from a fragile convention into a pinned invariant.
- Derived regions are fenced with HTML comment markers, `<!-- derived:status -->` ... `<!-- /derived:status -->` around "where it stands" and `<!-- derived:backlinks -->` ... `<!-- /derived:backlinks -->` around the referenced-by list. The skill's refresh mode owns everything inside the fences; authored prose lives outside them. This keeps authored facts and derived facts in separately-owned regions so a later tool-generated form can claim the fenced content without a merge fight.
- House prose rules apply: no em dashes, links out to `docs/architecture/` and `docs/manual/` wherever a concept is covered as-built, roadmap-internal vocabulary (`R<n>`, slice numbers) is fine because the space renders under `/roadmap/`, which is contributor-facing.

### Link model: one classification, two emitters

Extract the target-kind knowledge currently inline in `Main.mapAdocTarget` into a single classifier (working name `LinkTarget.classify(String target)`) returning a small sealed taxonomy: sibling item (slug), README, changelog, workflow, architecture doc (quadrant-mapped via `ARCH_QUADRANT`), top-level doc, concept page (slug), deep docs path (a full `docs/manual/**` or `docs/architecture/**` path, which the flat legacy patterns do not match; the adoc emitter passes it through unchanged exactly as the unknown case does today, while the href emitter maps it per point 2 below), legacy module path, external URL, unknown. Two thin formatters consume it:

1. The existing adoc emitter (`mapAdocTarget`'s output grammar, `ChangelogContext`-aware) is refactored to format from the classification; behavior on all existing cases must be unchanged. Note the existing tests pin only the sibling-plan branch (`AdocLinkPrefixTest`, `MdTableToAdocTest`, `RoadmapDateColumnTest`); the other branches (external URL, same-page anchor, README, changelog, workflow redirect, arch quadrant-mapped and non-quadrant, top-level docs, legacy module, web-environment redirect, unknown passthrough) are currently uncovered, so characterization assertions for them are written against the current output *before* the refactor, so the round-trip table pins pre-refactor behavior rather than ratifying whatever the refactor emits.
2. A new HTML href emitter, used when staging concept pages, formats the same classification for the concepts-page context: sibling item to `../plans/<slug>.html`, README to `../index.html`, changelog to `../changelog.html`, `../../docs/manual/**.adoc` and `../../docs/architecture/**.adoc` to `../../manual/**.html` / `../../architecture/**.html`, sibling concept pages and the shared assets untouched, unknown targets passed through untouched.

New classification case for the forward direction: an item body linking `concepts/<slug>.html` renders as `link:../concepts/<slug>.html[...]` in PLAN context and `link:concepts/<slug>.html[...]` in STANDALONE context (`link:`, not `xref:`, because the target is not an adoc page; this also keeps the WARN-fail asciidoctor log handler quiet).

**Cross-Done fallback.** Item-to-concept and concept-to-item links routinely cross the Done boundary: a shipped item has no file (deleted on Done) and lives only in `changelog.md`. The href emitter therefore checks sibling-item targets against the live item files: a `../<slug>.md` href whose item file exists maps to `../plans/<slug>.html`; one whose file is gone maps to `../changelog.html`, the durable record of shipped work. That gives cross-boundary drift a deterministic landing instead of a 404, without making item deletion on Done break the docs build. The skill's refresh mode later rewrites the visible prose to point at the changelog explicitly; the fallback is the always-on safety net between refreshes.

### Staging and site wiring

- `Main.runRenderAdoc` gains a concepts staging step: for each `roadmap/concepts/*.html`, rewrite `href="..."` attribute values through the HTML emitter and write the result to `<out>/concepts/`; copy `concepts.css` / `concepts.js` byte-for-byte. Runs after the existing per-plan loop, next to the `inference-axis-coverage.adoc` copy.
- `docs/pom.xml`, `render-site` execution: add a `<resources>` entry carrying `${project.build.directory}/staging/roadmap/concepts` to `targetPath` `roadmap/concepts`. Asciidoctor renders only `.adoc` sources, and with an explicit `<resources>` list nothing else is copied automatically, so without this entry the staged pages never reach `generated-docs/`.
- `Main.readItems` is non-recursive (`Files.list`), so the subdirectory does not disturb item parsing, README generation, or validation; no change needed there.

### Listings

`Main.render` (the GitHub `README.md` roll-up) and `Main.renderAdocStatusBoard` (the site index) each gain a short "Concept explainers" section listing every `roadmap/concepts/*.html` by its `data-concept-title`, sorted by slug; README entries link `concepts/<slug>.html`, the status board emits `link:concepts/<slug>.html[...]`. The listing is derived by scanning the directory, never hand-maintained, so there is no second source of truth; CI's existing `verify` mode covers listing drift (adding a page without regenerating the README fails the build), and the title-contract enforcer above covers unextractable pages.

### Backlinks

The referenced-by list inside `derived:backlinks` is computed at authoring/refresh time by grepping `roadmap/*.md` for `concepts/<slug>.html`. This is a materialized copy that can go stale when a new item links the concept; the interim enforcer is the skill's refresh mode (manual, human-triggered), and that staleness is accepted deliberately for v1. The drift-free target, tool-injected backlinks at staging time in the same shape as the listing derivation, is named in Out of scope as the follow-up refinement.

### The `explainer` skill

`.claude/skills/explainer/SKILL.md`, matching the house skill shape (front-matter `name:`/`description:`, intent-dispatch body; baseline: the `roadmap` skill). Two intents:

- **generate `<R<n>-or-concept-slug>`**: resolve the entry point (an item ID reads that item plus everything it links: sibling items, changelog entries, architecture and manual pages; a concept slug additionally greps for referencing items), then author `roadmap/concepts/<slug>.html` against the shared assets and page contract. House structure, in order: one-sentence version, the problem concretely (worked example from the actual schema/database domain), side-by-side panes at every contrast worth internalizing, a quiz at each likely misconception, links out to the as-built docs wherever depth already exists, "where it stands" status board (fenced), see-also footer with backlinks (fenced). Then regenerate the roadmap README (the listing changed) and remind about the docs-build integration check.
- **refresh `<slug>`**: recompute only the fenced derived regions of an existing page, from current roadmap state (item statuses, changelog, referencing items), leaving authored prose untouched. Also rewrites prose links whose item shipped since authoring to point at the changelog.

The skill encodes the link convention (repo-relative hrefs; the tool does the site mapping), the title-attribute contract, the derived-region fences, and the writing rules (intuition-first, no em dashes, interactivity at the points where a dense concept actually mislands rather than decoration).

## Implementation

Ordering inside the single landing: plumbing with its enforcers first, then the proving page refit, then the skill; the refit is the integration test of the mechanism.

- `roadmap-tool`: extract the `LinkTarget` classifier from `mapAdocTarget`; refactor the adoc emitter onto it (behavior-identical; the existing tests pin only the sibling-plan branch, so the remaining branches get characterization assertions captured before the refactor per Tests below); add the concept-page classification case; add the HTML href emitter with the cross-Done fallback; add the concepts staging step to `runRenderAdoc`; add title extraction with the fail-loudly contract; add the two listings. New code in a sibling class (working name `ConceptPages`) rather than growing `Main` further, with `Main` delegating.
- `docs/pom.xml`: the `<resources>` entry for staged concepts.
- `roadmap/concepts/`: extract `concepts.css` / `concepts.js` from the sketch; refit `per-participant-child-join-paths.html` onto them, adding the `data-concept-title` attribute, the shared header, the derived-region fences, and sweeping the em dashes out of its text; drop the "sketch for review" footer note.
- `roadmap/README.md`: regenerated (gains the Concept explainers section).
- `.claude/skills/explainer/SKILL.md`: as outlined above.

## Tests

All in `roadmap-tool`'s unit tier (same shape as `AdocLinkPrefixTest` / `MdTableToAdocTest`), plus the reactor docs build as the integration check:

- Classifier round-trip: a table asserted through both emitters, proving the two directions read one classification (the architect-flagged drift risk). The table covers *every* existing `mapAdocTarget` branch, not a representative sample: external URL, same-page anchor, sibling-plan slug, README, changelog, workflow redirect (`../docs/workflow.adoc` mapping to the roadmap sibling), arch quadrant-mapped, arch non-quadrant (slug absent from `ARCH_QUADRANT`), top-level docs, legacy module path, web-environment redirect, and unknown passthrough. These assertions are written against the current output before the emitter is refactored, so they pin pre-refactor behavior rather than the refactor's own output.
- `mapAdocTarget` concepts case in both `ChangelogContext`s.
- Href rewriting: live-item target, shipped-item target (changelog fallback), manual/architecture targets, external URL, unknown passthrough, asset references untouched.
- Title contract: extraction from a well-formed page; build failure with the file-naming message on a missing or blank `data-concept-title`.
- Listings: README and status-board sections render from a fixture concepts dir; `verify` fails on listing drift.
- Integration: `mvn install -Plocal-db` end to end; the WARN-failing asciidoctor render passes with the staged R458 page, and a manual click-through of that page's links in `docs/target/generated-docs/roadmap/concepts/` confirms every href lands (items, changelog, manual, architecture).

## Prior art in this session

A standalone sketch of the R458 concept page (interactive HTML: four quizzes, five side-by-side panes, status board, links out to the real architecture/manual pages) was drafted at `roadmap/concepts/per-participant-child-join-paths.html` to settle the format. It is a reference artifact for the shared-component extraction, not the shipped form.

## Out of scope

- Retrofitting the consumer manual or the architecture tree; this space links into them, it does not replace or reorganize them.
- Full Antora nav-chrome integration for the HTML pages; a shared header breadcrumb is the accepted mitigation.
- Tool-generated backlinks injected at staging time (the drift-free successor to the authored backlink list); deferred as a follow-up refinement, not forgotten.
- Concept pages beyond the R458 proving page; fan-out to other concepts (nodeID, the classification model, ...) happens via the skill once the format is proven, and needs no further roadmap machinery.
