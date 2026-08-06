---
id: R600
title: "Retire the roadmap markdown-to-AsciiDoc translator by authoring plans in AsciiDoc"
status: Backlog
bucket: cleanup
priority: 3
theme: docs
depends-on: []
created: 2026-08-06
last-updated: 2026-08-06
---

# Retire the roadmap markdown-to-AsciiDoc translator by authoring plans in AsciiDoc

Roadmap plans are authored as markdown and published as AsciiDoc, so a mechanical translator sits between
what an author writes and what ships. Its own javadoc calls itself "best-effort mechanical", which is the
honest description and also the problem: it is a lossy restatement with no enforcer, and an author cannot
see the difference between source and published output without a full site render. Authoring plans directly
in AsciiDoc would delete it and leave one markup in the repo instead of two.

## What the translator costs today

Measured, so a Spec pass does not have to re-measure:

* Roughly 355 lines of `Main` render plans to AsciiDoc: heading-level normalisation (markdown tolerates an
  `h1` to `h3` skip, AsciiDoc warns), markdown tables to AsciiDoc table blocks, ordered-list marker
  rewrites, and link rewriting against the staging-tree layout.
* `LinkTarget` (146 lines) plus `MdTableToAdocTest`, `AdocLinkPrefixTest` and `LinkTargetRoundTripTest`
  (about 300 lines) exist only to serve that translation.
* `AdocMarkdownTableCheck` is the two-markup tax surfacing as a build gate: it exists because authors write
  markdown tables into `.adoc` files, a mistake that cannot occur when there is only one table syntax.
* 178 item files, about 19k lines, are in the corpus. The tree is already mixed: `roadmap/` carries four
  authored `.adoc` files today (`workflow.adoc`, `inference-axis-coverage.adoc` and two search how-tos).

GitHub is not an obstacle. It renders `.adoc` natively in the web UI, which `docs/README.adoc` already
states and the whole `docs/` tree already relies on. Item metadata arguably improves: YAML front-matter
renders as a visible table in markdown, whereas AsciiDoc document attributes are hidden by Asciidoctor.

## The cost that must not be lost in the accounting

The translator is also the reason one whole bug class is fixable rather than merely gateable. A
backtick-quoted macro in AsciiDoc is substituted, so quoting a cross-reference in prose publishes a live
link; that is the hazard the cross-file anchor gate was written around, and it cost that item several spec
passes. The fix filed as R587 works *because* a translator exists: markdown code spans are literal by
definition, so the renderer can emit a plus-delimited passthrough and the class disappears without any
author learning AsciiDoc passthrough syntax.

Authoring natively in AsciiDoc gives that lever up. Every author hand-writes the passthrough form forever,
and enforcement drops from "the representation cannot express the bug" to "a gate fails after you wrote
it". A Spec pass has to decide whether deleting the translator is worth making that discipline permanent,
and should not treat the deletion as a free win.

## Sequencing

Not a prerequisite for R587 and must not be sequenced ahead of it. R587 is a single renderer change that
retires the quoted-macro class immediately; this item is a corpus migration whose value is maintenance
reduction. If both land, R587 first, and this item then inherits the decision above as a known regression
to accept deliberately.

## Scope to settle at Spec

* Front-matter to AsciiDoc attributes, and the parser, README generator, `create` / `status` / `next-id`
  subcommands and validator that read it.
* The `roadmap/<slug>` path shape in `check-transient-citations`, and the `roadmap/*.md` glob in the `srp`,
  `roadmap` and `explainer` skill documents plus `CLAUDE.md`.
* Whether the migration bootstraps off the translator itself: run it over all 178 files, commit its output
  as the new authored source, then delete it. The tool being removed is the migration tool.
* What happens to `roadmap/README.md` and `roadmap/changelog.md`, which are cited by path from `CLAUDE.md`
  and named as permanent artifacts there.
* Whether agents author AsciiDoc plans as reliably as markdown ones, since every Backlog item is written by
  a session rather than by hand.
