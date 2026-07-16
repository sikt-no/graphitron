---
id: R488
title: "Concept explainers declare and cross-link their backing roadmap item(s)"
status: In Review
bucket: architecture
priority: 5
theme: docs
depends-on: []
created: 2026-07-16
last-updated: 2026-07-16
---

# Concept explainers declare and cross-link their backing roadmap item(s)

R486 shipped the concept explainer space but left the item-to-concept relationship implicit: a page mentions its backing item in prose (the R458 page names R458 in its lede), but nothing on the page *declares* the relationship, and nothing in the roadmap listings links an item to its explainer. A reader on a backed item's row of the status board has a `plan` link but no signal that a background explainer exists; a reader on the explainer page has to infer from prose which item it backs. Both directions should be explicit and derived from one declaration.

## Design

The two gaps are one fact declared once: the page carries a machine attribute naming its anchor item(s), and every other surface (visible page header, README listings, status-board listings, the Concept explainers sections) derives from that declaration. No second source of truth; the same `roadmap/concepts/*.html` directory scan that already drives the R486 listings drives the new links, so `verify`'s README byte-compare covers drift with no new machinery.

### Page contract: `data-concept-items`

Extend the page contract with a second machine attribute on the `<h1>`, alongside `data-concept-title`:

```html
<h1 data-concept-title="Per-participant join paths for polymorphic child fields"
    data-concept-items="R458">...</h1>
```

- Comma-separated `R<n>` list (whitespace around commas tolerated and trimmed) for a concept that backs more than one item. Required and non-blank; a missing, blank, or malformed value (any token not matching `R[1-9][0-9]*`) fails the build with a message naming the file and the contract, exactly as the title contract does (`ConceptPages.extractTitle` is the precedent). Two flat attributes on the `<h1>` is the right grain; the element is the site and title/items are orthogonal facts, so no structured block is warranted. The one packed-list parse (split, trim, per-token check) stays inside the `ConceptPages` read boundary.
- **Allocated-ID bound.** Beyond well-formedness, every declared ID must be numerically below the `next-id:` counter in `roadmap/changelog.md`'s front-matter (already parseable via `parseFrontMatter`). IDs are allocated monotonically, so `n < next-id` is exactly "this ID has ever existed": a typo like `R999` fails at build time naming the file, while a shipped item (file deleted on Done) and a discarded item both pass. A changelog *entry* scan (`^- R<n> `) was considered and rejected: Discarded items may carry no changelog entry, so the entry scan would false-fail a legal anchor; the counter is the true ever-allocated invariant.
- **Kicker enforcer.** The declaration renders visibly in the page header by folding into the existing `kicker` line: `Concept explainer · R458 · theme: interface-union`. The kicker is authored prose, but a restated fact needs an enforcer: `ConceptPages` checks that the header kicker (the first `class="kicker"` element) names each declared ID, failing with the same file-naming message style when one is missing. Deriving the kicker at stage time was considered and rejected; the repo copy and local `file://` preview would then lack the visible statement that is the point of the line.

### Read model

`ConceptPages.readTitles` (slug to title) is replaced by a richer single read:

```java
record ConceptPage(String slug, String title, List<String> itemIds) {}
static Map<String, ConceptPage> readPages(Path roadmapDir) throws IOException
```

`readPages` takes the roadmap directory rather than the concepts directory so it can read both `concepts/*.html` and the changelog front-matter for the allocated-ID bound. Every existing caller (generate, verify, `render-adoc` staging) routes through it, so all three contracts (title, items, kicker) are enforced everywhere pages are read, same as the title contract today.

The live-vs-shipped question is resolved once, not at each render site. A small index is built where items and pages meet:

```java
// ConceptIndex.of(List<Item> items, Map<String, ConceptPage> pages)
sealed interface ItemAnchor permits Live, Shipped {}
record Live(String itemId, String itemSlug) implements ItemAnchor {}
record Shipped(String itemId) implements ItemAnchor {}
```

`ConceptIndex` exposes the reverse index (item ID to slug-sorted explainer slugs) for the item-side listings, and each page's declared IDs resolved to `ItemAnchor` outcomes for the concept-side annotations. Render sites consume the resolved outcome and never re-derive liveness; this mirrors the `ConceptPages.mapHref` discipline where the sealed `LinkTarget.SiblingItem` fallback resolves the same question in one place for hrefs.

### Item-side links (item to explainer)

Everywhere the README and status board render a plan link, an `explainer` link follows when the reverse index has an entry for that item:

- README Active table, Plan column: `[plan](slug.md) · [explainer](concepts/eslug.html)`.
- README Backlog and Deferred lines: the title link is followed by ` ([explainer](concepts/eslug.html))` before the description.
- Status board (AsciiDoc) equivalents: `xref:plans/slug.adoc[plan] · link:concepts/eslug.html[explainer]` in the Active table, and `(link:concepts/eslug.html[explainer])` on Backlog/Deferred lines. Concept pages are raw HTML, so `link:` not `xref:`, the direction `mapAdocTarget` already encodes for `LinkTarget.ConceptPage`.
- Multiplicity: one `explainer` link per backing page, slug order, `·`-separated (recorded decision: unusual but harmless).

A `Shipped` anchor emits nothing here, which is correct: the item is no longer listed.

### Concept-side annotation (explainer to item)

The derived Concept explainers sections annotate each page with its anchors, from the same declaration:

- README: `- [Title](concepts/slug.html) (backs [R458](item-slug.md))` when live; plain `R458` text when shipped.
- Status board: `* link:concepts/slug.html[Title] (backs xref:plans/item-slug.adoc[R458])` when live; plain when shipped.

Clarity note for future readers: `data-concept-items` (the items a concept *backs*, its anchors) and the R486 `derived:backlinks` fenced region (items that *mention* the page) are two distinct relations and must not be unified.

### Explainer skill contract update

`.claude/skills/explainer/SKILL.md`'s page-contract section gains the items contract: the header kicker format becomes `Concept explainer · R<n>[ · R<m>...] · theme: <theme>`, the title-contract paragraph documents `data-concept-items` alongside `data-concept-title`, the generate intent derives the backing item(s) from the request, and the refresh intent preserves the attribute (it is authored, not a derived region) and keeps the kicker in sync, with the tool enforcer as the backstop.

### Proving refit

The one shipped page, `roadmap/concepts/per-participant-child-join-paths.html`, gains `data-concept-items="R458"` and the kicker update. R458 went Done on 2026-07-16 (file deleted, changelog entry present), so the refit proves the `Shipped` arm live: the declaration passes the allocated-ID bound, no item-side listing link is emitted, and the Concept explainers annotation shows plain `R458` text. The `Live` arm has no in-repo example at refit time and is covered by the tests.

## Tests

`ConceptPagesTest` extensions, following its existing shape:

- Items parsing: single ID; multiple with whitespace; missing, blank, and malformed values fail the build naming the file.
- Allocated-ID bound: an ID at or above `next-id:` fails; an ID below it with no live item file passes (the shipped case).
- Kicker enforcer: a declared ID absent from the header kicker fails naming the file.
- `readPages` returns slug-sorted `ConceptPage`s carrying title and item IDs; absent directory yields empty.
- `ConceptIndex`: reverse index is slug-sorted per item; declared IDs resolve to `Live` when an item file exists and `Shipped` when not.
- README rendering: Active row Plan cell gains the `· [explainer](...)` link when backed and is unchanged when not; Backlog line gains the parenthesized link; two pages backing one item emit both links in slug order; Concept explainers line carries the `(backs ...)` annotation, linked when live and plain when shipped.
- Status board rendering: the `link:concepts/...[explainer]` equivalents of the above.
- Drift property: `Main.render` output changes when a page's declaration changes, so `verify`'s byte comparison fails on unregenerated listings (same assertion style as the existing title-drift test).
- `stage` still enforces all contracts on every staged page.

## Decisions carried in from the design discussion (2026-07-16)

- **Stays concept-keyed.** The attribute lists the item(s) a concept currently *backs* (its anchors), not every item it name-checks in prose. This does not reverse R486's concept-keyed principle: the slug still names the concept, the page still outlives any single item, and this is just the page declaring its current anchors, the same way the fenced `derived:status` section already folds item status in.
- **Do not require a live item.** Items are deleted on Done (R452 is already gone), so the tool must not fail when the value names a shipped item. A relation to a shipped item produces no listing link (the item is no longer listed), which is correct. *Refined at Spec time (2026-07-16, principles consult):* the original wording said "format-validate only", but pure format validation collapses "shipped" and "never existed" into one silent bucket; a plausible typo like `R999` would pass and quietly produce no link. The allocated-ID bound above (`n < next-id`) catches the typo while preserving this decision's actual rationale, that shipped (and discarded) items are legal anchors. No liveness is required.
- **Multiplicity.** If two explainers both back one item, list both explainer links; unusual but harmless.

## Out of scope

- Backfilling `data-concept-items` beyond the one shipped page (there is only the R458 page today; it gets the attribute as the proving refit).
- The tool-injected backlinks refinement named in R486's out-of-scope; that is the concept-to-item *footer* list (items that mention the page), orthogonal to this item's anchor relation and its listing links.
- The by-theme views (`renderByTheme` / `renderAdocByTheme`). They link the item title directly with no separate plan-link slot, and their lines are already dense one-liners; adding explainer links there can ride a later item if wanted.
