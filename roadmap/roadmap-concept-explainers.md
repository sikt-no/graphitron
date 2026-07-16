---
id: R486
title: "Roadmap concept explainers: an interactive developer-facing explanation space under roadmap/concepts/, plus a skill to author the pages"
status: Backlog
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

## Deliverables (to be sliced at Spec)

1. **The `roadmap/concepts/` space** with shared `concepts.css` / `concepts.js` assets and a page template (shared header: breadcrumb + back-to-roadmap link, since pure HTML does not inherit Antora nav chrome).
2. **`roadmap-tool` plumbing**: copy `roadmap/concepts/*.html` and the shared assets verbatim into the rendered docs output (precedent: the `inference-axis-coverage.adoc` verbatim copy at `Main.java:468`); extend `mapAdocTarget` so item bodies can link to concept pages and resolve at the rendered target.
3. **Backlinks ("referenced by these items")** computed by grepping items for links to the concept, written at authoring time with a refresh mode; tool-generated backlinks can be a later refinement.
4. **A project skill** (`.claude/skills/`, working name `explainer`) invoked with a roadmap item ID or concept slug: reads the item and its linked context and generates an HTML explainer into `roadmap/concepts/<slug>.html` against the shared template, following the house structure (one-sentence version -> concrete example -> side-by-side panes at contrasts -> quizzes at misconception points -> links out to architecture/manual -> backlinks -> "where it stands"). A refresh mode re-computes status/backlinks on an existing page.
5. **First concept page**: polymorphic child join paths / `@referenceFor` (seeded from R458), proving the format and the linking end to end before fanning out to other concepts.

## Prior art in this session

A standalone sketch of the R458 concept page (interactive HTML: four quizzes, five side-by-side panes, status board, links out to the real architecture/manual pages) was drafted at `roadmap/concepts/per-participant-child-join-paths.html` to settle the format. It is a reference artifact for the shared-component extraction, not the shipped form.

## Out of scope

- Retrofitting the consumer manual or the architecture tree; this space links into them, it does not replace or reorganize them.
- Full Antora nav-chrome integration for the HTML pages; a shared header breadcrumb is the accepted mitigation.
