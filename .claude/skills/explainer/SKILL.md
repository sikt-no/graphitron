---
name: explainer
description: Author and refresh roadmap concept explainer pages at `roadmap/concepts/<slug>.html` (R486) — interactive, intuition-first HTML background pages for dense or recurring roadmap concepts. Use when the user asks to "write an explainer", "generate a concept page for R24", "explain the nodeID concept as a page", "refresh the join-paths explainer", or any phrase about authoring or updating `roadmap/concepts/` pages. Two intents: generate (author a new page from an item ID or concept slug) and refresh (recompute only the derived regions of an existing page).
---

# Concept explainers

Operates on `roadmap/concepts/<slug>.html`: a roadmap-adjacent developer explanation space, distinct from both Diataxis trees. Not the consumer manual (`docs/manual/`), not the as-built architecture tree (`docs/architecture/`); it is intuition-first scaffolding for reading dense roadmap work, and it *links into* those trees wherever a concept is already covered as-built, going deep only where the roadmap context is the value.

Pages are concept-keyed, not item-keyed: the slug names the concept ("polymorphic child join paths"), which outlives any single item. An item's perishable status folds into the page as a fenced "where it stands" section rather than spawning a per-item page that dies when the item ships.

## The page contract (both intents must preserve it)

- One page per concept at `roadmap/concepts/<slug>.html`; slug is lowercase kebab-case and names the concept, not an item.
- Shared assets: `<link rel="stylesheet" href="concepts.css">` and `<script src="concepts.js"></script>`. Never inline styles or scripts that duplicate them. The markup vocabulary is the shared class set: `tldr`, `note`, `sxs`/`pane` (side-by-side), `quiz`/`choice`/`explain` (a `.choice` button carries `data-correct` and `onclick="choose(this)"`), `status`/`pill`, `kicker`, `crumb`, plus the shared footer.
- Shared header, in order: a `crumb` breadcrumb back-link to `../README.md`, a `kicker` line (`Concept explainer · R<n>[ · R<m>...] · theme: <theme>`, naming the backing item(s) between the label and the theme), then the `<h1>`.
- **Title contract:** the `<h1>` carries `data-concept-title="<plain-text title>"`. The attribute value (not the element's inner HTML, which may carry `<code>` markup) is what the README and status-board listings derive from. A missing or blank attribute fails the build.
- **Items contract (R488):** the same `<h1>` carries `data-concept-items="R<n>[, R<m>...]"` naming the roadmap item(s) the concept *backs* (its anchors, not every item it name-checks in prose). Comma-separated `R<n>` list, whitespace around commas tolerated. Required and non-blank; a missing, blank, or malformed value, an id that was never allocated (at or above `changelog.md`'s `next-id:`), or a declared id absent from the header `kicker` line each fails the build naming the file. The relation drives the cross-links both ways: an `explainer` link follows the item's plan link in the README and status board, and the Concept explainers listing annotates each page with `(backs R<n>)`. A shipped item (file deleted on Done) is a legal anchor: it renders as plain id text with no item-side link. This is distinct from the `derived:backlinks` region (items that *mention* the page); the two relations must not be unified.
- **Derived-region fences:** `<!-- derived:status -->` ... `<!-- /derived:status -->` around the "where it stands" section, and `<!-- derived:backlinks -->` ... `<!-- /derived:backlinks -->` around the referenced-by list in the footer. Refresh owns everything inside the fences; authored prose lives outside them.
- **Link convention: repo-relative hrefs.** Write links exactly as a file at `roadmap/concepts/` would resolve them on disk: sibling items `../<slug>.md`, the README `../README.md`, the changelog `../changelog.md`, docs pages `../../docs/manual/**.adoc` / `../../docs/architecture/**.adoc`, sibling concept pages `<slug>.html`, assets `concepts.css`. This works in local `file://` preview; `roadmap-tool` rewrites hrefs to the rendered-site layout at staging time, including a fallback that points links to shipped (deleted) items at the changelog. Do not hand-author site-layout links.
- House prose rules: no em dashes; roadmap-internal vocabulary (`R<n>`, slice numbers) is fine because the space renders under `/roadmap/`, which is contributor-facing.

## generate `<R<n>-or-concept-slug>`

1. **Resolve the entry point.** An item ID: read that item plus everything it links (sibling items, changelog entries, architecture and manual pages). A concept slug: additionally grep `roadmap/*.md` for items referencing the concept. Either way, settle the backing item(s) the page will declare in `data-concept-items` (and name in the kicker): the item the request names, or the item(s) that own the concept as-built.
2. **Author the page** against the contract above. House structure, in order:
   - one-sentence version (`tldr` block);
   - the problem concretely, with a worked example from the actual schema/database domain (Sakila tables beat abstract `Foo`/`Bar`);
   - side-by-side `sxs` panes at every contrast worth internalizing;
   - a quiz at each likely misconception (interactivity goes where a dense concept actually mislands, not decoration);
   - links out to `docs/architecture/` and `docs/manual/` wherever depth already exists as-built;
   - "where it stands" status board, fenced `derived:status`;
   - see-also footer with the referenced-by list, fenced `derived:backlinks` (compute it by grepping `roadmap/*.md` for `concepts/<slug>.html`).
3. **Regenerate the roadmap README** (the listing changed): `mvn -pl roadmap-tool exec:java -q`.
4. **Remind about the docs-build integration check:** `mvn install -Plocal-db` runs the WARN-failing asciidoctor render with the staged page; a click-through of the page's links under `docs/target/generated-docs/roadmap/concepts/` confirms every href lands.

## refresh `<slug>`

Recompute only the fenced derived regions of `roadmap/concepts/<slug>.html`, from current roadmap state; leave authored prose outside the fences untouched.

- `derived:status`: re-read the relevant items' front-matter and `changelog.md`; update pills (`done`/`pending`/`spun`), the as-of date, and slice states.
- `derived:backlinks`: re-grep `roadmap/*.md` for `concepts/<slug>.html` and rewrite the referenced-by list.
- Additionally, rewrite prose links whose item shipped since authoring (the `.md` file is gone) to point at `../changelog.md` explicitly; the staging-time fallback already lands them at the changelog in the rendered site, but the visible prose should say so.
- **Preserve `data-concept-items`.** The backing-item declaration is authored, not a derived region: leave it as written unless the page's anchors genuinely changed, and keep the header kicker naming the same id(s). An item shipping does not change the anchor (a shipped item is a legal anchor that renders as plain text); only re-target the concept genuinely backs a different item. The tool's items/kicker enforcer is the backstop if the two fall out of sync.

The backlinks list is a materialized copy that goes stale when a new item links the concept; this manual refresh is the interim enforcer (tool-injected backlinks at staging time are the named follow-up refinement in R486's landing).
