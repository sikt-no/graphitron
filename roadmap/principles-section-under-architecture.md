---
id: R805
title: "A principles section under docs/architecture"
status: Spec
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

New directory `docs/architecture/principles/` with its own `index.adoc`. No `docs/pom.xml`
change: the `stage-adoc` step already globs `architecture/**/*.adoc`.

`development-principles.adoc` moves there keeping its filename. The slug
`development-principles` is a key in roadmap-tool's `LinkTarget.ARCH_QUADRANT`, the map
that resolves legacy flat links in roadmap bodies to their Diataxis home; renaming the file
to `index.adoc` would break that slug mapping and buy nothing. The page's content is
untouched: this item is a move, and splitting the six axioms into per-axiom pages is out of
scope. The word-budget gate moves with the file unchanged (a path-constant update in
`DocSizeBudgetTest`), since the budget is about consult-time context cost, not location.

The section index is the wayfinding the item exists for. It routes to the moved page and to
the principles that deliberately live elsewhere:

* `docs/graphitron-principles.adoc` (strategic principles) stays at the docs root. It is
  linked from the user manual, the FAQ, the top-level README, `docs/index.adoc`,
  `quick-start.adoc` and the site footer; a consumer-facing blast radius this
  contributor-side move should not touch. The index links it.
* `explanation/fact-model.adoc` (the store's modeling discipline) stays in `explanation/`;
  its reference web and register are explanation. The index links it.

`architecture/index.adoc` gains the section as a nav entry (the quadrant grid already
carries a non-Diataxis "Ongoing work" cell, so a fifth entry has precedent; exact layout is
the implementer's) and repoints its "deeper reference" line. `explanation/index.adoc` drops
its development-principles entry in favour of a pointer to the section.

## Deliverable 2: the link surgery, by mechanism

* **roadmap-tool**: the `ARCH_QUADRANT` entry for `development-principles` flips to
  `principles` (and the map's "Diataxis quadrant" comment loosens to "section", since
  principles is not a Diataxis quadrant); the three emit sites in `Main.java` (the roadmap
  index adoc header, two README.md sentences) repoint; `LinkTargetRoundTripTest`'s
  expectations follow; the README regenerates.
* **docs xrefs**: roughly ten pages, including the anchored xrefs from
  `typed-rejection.adoc` and `dispatch-axes.adoc` into the page's four explicit block
  anchors, which become `../principles/` cross-directory xrefs, and the moved page's own
  ten outbound xrefs, which become `../explanation/` and `../../` forms.
* **prose paths outside docs**: `CLAUDE.md`, the `srp` and `reviewer-prompt` skills, the
  `principles-architect` agent definition, and about fourteen javadoc prose mentions across
  main and test sources. These are prose, not `{@link}`s, so the javadoc reference gate
  does not see them; a grep sweep at implementation and the retirement sweep at the Done
  gate are the coverage.
* **roadmap bodies**: transient items deep-linking the old path get a mechanical sed so
  active items' rendered links stay live; no gate owed.

## Deliverable 3: the gate the move exposes the need for

A dangling xref between architecture pages currently has no enforcer.
`ManualXrefIntegrityTest` walks `docs/manual/` only, and roadmap-tool's
`AdocXrefAnchorCheck` fails on a wrong anchor but deliberately reports-without-failing a
wrong path (its own javadoc: a 404 is self-reporting). The move's primary failure mode is
exactly this class, so the gate lands with the move: widen `ManualXrefIntegrityTest` to
walk the authored docs tree (`manual/`, `architecture/`, `history/`, the root-level
`.adoc` files; skip `target/` and `_theme/`), same rule as today (the target file exists;
anchors stay `AdocXrefAnchorCheck`'s business), renamed to match its widened scope.
Pre-existing danglers the widening surfaces get fixed in the same change; that is the
gate doing its job on arrival, not scope creep. The two checkers stay complementary, not
overlapping: the widened test owns paths at the source tree, the anchor check owns anchors
at staging.

## Tests

* The widened xref-integrity test is Deliverable 3's own gate and the regression pin for
  the whole surgery: any missed docs-side xref fails it.
* `ReadmeLinkIntegrityTest` (walks every README.md repo-wide) fails if the `Main.java`
  emit sites are missed, via the regenerated roadmap README.
* `LinkTargetRoundTripTest` pins the `ARCH_QUADRANT` flip.
* `DocSizeBudgetTest` keeps enforcing the budget at the new path; it failing to find the
  file is the tripwire for a half-done move.
* The verification build's docs render covers the AsciiDoctor side.

## Not in scope

* Splitting `development-principles.adoc` into per-axiom pages; the content is untouched.
* Old-URL redirects on the docs site: the page is contributor-facing, the site has no
  redirect mechanism, and the break is accepted.
* Moving `docs/graphitron-principles.adoc`.

## Retired vocabulary

* `docs/architecture/explanation/development-principles.adoc` as a path
