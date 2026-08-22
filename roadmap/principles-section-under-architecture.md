---
id: R805
title: "A principles section under docs/architecture"
status: Ready
bucket: docs
priority: 6
theme: docs
depends-on: []
created: 2026-08-22
last-updated: 2026-08-22
---

# A principles section under docs/architecture

The architecture docs have no principles section, and the principles the project actually
runs on are scattered across three homes with three registers: the strategic principles sit
at the docs root (`docs/graphitron-principles.adoc`, outside the architecture tree
entirely), the six development axioms sit under
`docs/architecture/explanation/development-principles.adoc`, and a growing family of
modeling rules lives inside `fact-model.adoc` (each with its enforcer named, a register
neither of the other two uses). A contributor asking "what are this project's principles"
has no one place to start, and a new principle has no obvious home, which is how rules end
up stated only in a roadmap item or a gate's failure message.

The fork is resolved (user decision, 2026-08-22): `docs/architecture/` grows a principles
section beside `explanation/`, `how-to/` and `reference/`, and the architecture principles
migrate into it. This spec turns that decision into a move, its link surgery, and the gate
that proves the surgery complete.

## Deliverable 1: the section

New directory `docs/architecture/principles/` with its own `index.adoc`. The `stage-adoc`
glob already includes `architecture/**/*.adoc`, but the staging pipeline also carries a
hand-maintained per-directory roster in `docs/pom.xml`: one `<copy>` of `css/` and the two
`docinfo-*.html` files per staged directory, every entry `failonerror="false"`. The new
directory needs both entries, and nothing fails when they are missed; the first report of
a miss is a published page with no stylesheet and no site nav, so this spec names the two
entries as deliverables and the implementer eyeballs the rendered page. Deriving that
fan-out from the staged tree instead of listing it is the durable fix and is out of scope
here (see Not in scope).

`development-principles.adoc` moves there keeping its filename, and the reason is the
two-documents argument, not link compatibility: the router and the axiom page have two
jobs and two budgets. The section index routes to three destinations, while the 3,500-word
cap in `DocSizeBudgetTest` is a cap on the axiom page's consult cost; folding the router
into the budgeted page either charges routing prose to the axiom budget or points the
budget test at a router. That the slug also keys roadmap-tool's `ARCH_QUADRANT` map is a
footnote, and Deliverable 3 binds that map to the tree so it stops being load-bearing. The
budget gate itself moves with the file unchanged (a path-constant update in
`DocSizeBudgetTest`). The page's prose is untouched; its own six explanation-sibling
xrefs re-point to `../explanation/`, and its three cross-directory ones
(`../../graphitron-principles.adoc`, `../how-to/testing.adoc`,
`../reference/emitter-conventions.adoc#helper-locality`) are depth-invariant and survive
as written.

The section index is the wayfinding the item exists for: one line per destination, no
axiom roster and no count of six, because a router that summarizes its targets is an
unguarded inventory the moment a seventh axiom lands. Destinations:

* the moved page;
* `docs/graphitron-principles.adoc` (strategic principles), which stays at the docs root,
  and the index says why (register and audience: it is linked from the user manual, the
  FAQ, the top-level README, `docs/index.adoc`, `quick-start.adoc` and the site footer),
  so the section does not read as an incomplete move;
* `explanation/fact-model.adoc` (the store's modeling discipline), which stays in
  `explanation/` with its reference web.

`architecture/index.adoc`: leave the 2x2 Diataxis grid alone and introduce principles as a
short lead-in band above it. Principles govern all three quadrants, which is exactly why
they are not one of them; and a fifth cell in the `cols="1,1"` grid would yield a
three-row grid with a lone trailing cell whose mobile collapse keys on `td:last-child`.
The page's `:description:` and its "pick the quadrant" sentence, plus the section
enumerations in `docs/index.adoc` and `explanation/index.adoc`, update in the same change:
prose that enumerates the sections is falsified by the new one. `explanation/index.adoc`
drops its development-principles entry in favour of a pointer to the section.

## Deliverable 2: the link surgery, by mechanism

Grep the path prefix `architecture/explanation/development-principles`, not the filename:
because the filename survives the move, bare-filename mentions (`TypeFetcherGenerator`,
`ServiceCatalog`, `FieldBuilder` and their siblings' javadoc) stay correct, and churning
them is noise. What changes is every path-qualified mention:

* **roadmap-tool**: the `ARCH_QUADRANT` entry for `development-principles` flips to
  `principles` (and the map's "Diataxis quadrant" comment loosens to "section", since
  principles is not a Diataxis quadrant); the `Main.java` emit site in the staged roadmap
  index header repoints, as do the two README.md emit sentences (those two are already
  gated: `ReadmeLinkIntegrityTest` walks every README.md repo-wide, the regenerated
  `roadmap/README.md` included); `LinkTargetRoundTripTest`'s expectations follow; the
  README regenerates.
* **docs xrefs**: the anchored xrefs from `typed-rejection.adoc` and `dispatch-axes.adoc`
  into the page's four explicit block anchors become `../principles/` cross-directory
  xrefs; `how-to/testing.adoc`, the `reference/` pages that cite the path,
  `architecture/index.adoc`, the history page and the manual's `asConnection.adoc`
  repoint.
* **the concept page**: `roadmap/concepts/flattened-selection-result-keys.html` carries an
  authored href to the old path, staged through `LinkTarget.DeepDocsPath` into a site URL.
  Concept pages outlive the items that back them, so this belongs to the permanent sweep,
  and nothing checks that a `DeepDocsPath` target exists, so a miss is a silent site 404.
* **prose paths outside docs**: `CLAUDE.md`, the skills that cite the path (`srp` and
  `reviewer-prompt`; confirm `explain`, `nested-jooq` and `store-performance` at pickup),
  the `principles-architect` agent definition, and the path-qualified javadoc mentions
  (`ClassAccessorResolver`, `ArgPathHelperRegistry`). These are prose, not `{@link}`s, so
  the javadoc reference gate does not see them; the grep sweep at implementation and the
  retirement sweep at the Done gate are the coverage.
* **roadmap bodies**: transient items deep-linking the old path get a mechanical sed so
  active items' rendered links stay live; no gate owed.

## Deliverable 3: the gates, landed additive-then-cutover

Land the gates first, green over the tree as it stands (fixing any pre-existing danglers
they surface), then land the move against gates already trusted. A gate authored in the
same breath as the change it validates gets its scope tuned to exactly what the change
touched; the sequencing is what prevents that.

* **Path integrity has one owner, at staging.** A dangling xref between architecture pages
  currently has no enforcer: `ManualXrefIntegrityTest` walks `docs/manual/` only, and
  roadmap-tool's `AdocXrefAnchorCheck` fails on a wrong anchor but deliberately
  reports-without-failing a wrong path. Widening the sakila-example walker was the first
  sketch and is the wrong shape twice over: it would stand up a second enforcer giving the
  opposite verdict on the same defect the check's own javadoc argues must not fail, and an
  authored-tree walker structurally cannot see the staged roadmap index header, a
  generated page with no authored `.adoc`. Instead, extend `AdocXrefAnchorCheck`: cover
  unanchored `xref:` targets, and make the path verdict a function of provenance, failing
  when the source page is authored under `docs/` and staying report-only where the source
  is roadmap prose, which is the population its self-reporting argument was written for.
  One enforcer, one verdict per population, and it sees generated pages. Pin an
  anti-vacuity floor on the widened population, per the check's own "N references" line
  and `RetiredVocabularyGuardTest`'s minimum-scan precedent.
* **The layout map binds to the tree.** `ARCH_QUADRANT` is roadmap-tool's private copy of
  the docs layout, repaired by hand on every move. Add a roadmap-tool test that every
  entry resolves to an existing `docs/architecture/<section>/<slug>.adoc`, so the next
  move fails the build instead of shipping a link that renders live and 404s.
* **Section indexes are exhaustive.** A page that lands in a section and is never listed
  in its index is invisible with every gate green, the exact failure this item's problem
  statement complains about. `HowToIndexCoverageTest` already pins index coverage
  bidirectionally for `docs/manual/how-to`; parameterize its rule over the section
  directories (`architecture/explanation`, `architecture/reference`,
  `architecture/how-to`, `architecture/principles`, `manual/how-to`) so an unlisted page
  fails the build. This gate is what proves the section real, not just the move complete.

## Tests

* The three gates above are their own tests and the regression pins for the surgery.
* `ReadmeLinkIntegrityTest` covers the README emit sites via the regenerated roadmap
  README.
* `ManualXrefIntegrityTest` keeps covering the manual's outbound xref into the moved page,
  unchanged.
* `DocSizeBudgetTest` keeps enforcing the budget at the new path; it failing to find the
  file is the tripwire for a half-done move.
* The verification build's docs render covers the AsciiDoctor side.

## Not in scope

* Splitting `development-principles.adoc` into per-axiom pages; the prose is untouched.
* Old-URL redirects on the docs site: the page is contributor-facing, the site has no
  redirect mechanism, and the break is accepted.
* Moving `docs/graphitron-principles.adoc`.
* Deriving the `docs/pom.xml` css/docinfo fan-out from the staged tree instead of the
  hand-maintained per-directory roster. That is the durable fix for a roster this item
  can only append to (`SchemaIdentifierDriftCheck` is the read-the-tree precedent), and it
  is its own Backlog item.

## Retired vocabulary

* `docs/architecture/explanation/development-principles.adoc` as a path. A slash-bearing
  path cannot be a `RetiredVocabularyGuardTest` registry entry, so this line is the
  Done-gate reviewer's grep query per the retirement sweep, not a guard entry.
