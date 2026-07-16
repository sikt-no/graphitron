---
id: R488
title: "Concept explainers declare and cross-link their backing roadmap item(s)"
status: Backlog
bucket: architecture
priority: 5
theme: docs
depends-on: []
created: 2026-07-16
last-updated: 2026-07-16
---

# Concept explainers declare and cross-link their backing roadmap item(s)

R486 shipped the concept explainer space but left the item-to-concept relationship implicit: a page mentions its backing item in prose (the R458 page names R458 in its lede), but nothing on the page *declares* the relationship, and nothing in the roadmap listings links an item to its explainer. A reader on the R458 row of the status board has a `plan` link but no signal that a background explainer exists; a reader on the explainer page has to infer from prose which item it backs. Both directions should be explicit and derived from one declaration.

## Design sketch

The two gaps are one fact declared once. Extend the page contract with a second machine attribute on the `<h1>`, alongside `data-concept-title`:

- `data-concept-items="R458"` (comma-separated for a concept that backs more than one item). Required and non-blank, failing the build with a file-naming message exactly as the title contract does (`ConceptPages.extractTitle` is the precedent).
- The attribute renders visibly in the page header so the page states its relationship (folded into the existing `kicker` line, e.g. `Concept explainer · R458 · theme: interface-union`, unless a louder dedicated line is wanted).
- `ConceptPages` gains a reverse map (item-id to explainer slug) derived by scanning `roadmap/concepts/*.html`; `renderActive`, `renderBacklog`, and `renderAdocStatusBoard` consult it and emit an `explainer` link next to the existing `plan` link when a page backs that item (README: `[plan](slug.md) · [explainer](concepts/eslug.html)`; status board: the `xref:`/`link:` equivalents). No second source of truth; the same directory scan already drives the R486 listings, so `verify` covers drift.

## Decisions carried in from the design discussion (2026-07-16)

- **Stays concept-keyed.** The attribute lists the item(s) a concept currently *backs* (its anchors), not every item it name-checks in prose. This does not reverse R486's concept-keyed principle: the slug still names the concept, the page still outlives any single item, and this is just the page declaring its current anchors, the same way the fenced `derived:status` section already folds item status in.
- **Format-validate only; do not require a live item.** Items are deleted on Done (R452 is already gone), so the tool checks the value is a well-formed `R<n>` but must not fail when it names a shipped item. A relation to a shipped item produces no listing link (the item is no longer listed), which is correct.
- **Multiplicity.** If two explainers both back one item, list both explainer links; unusual but harmless.

## Out of scope

- Backfilling `data-concept-items` beyond the one shipped page (there is only the R458 page today; it gets the attribute as the proving refit).
- The tool-injected backlinks refinement named in R486's out-of-scope; that is the concept-to-item *footer* list, orthogonal to this item-to-concept *listing* link.
