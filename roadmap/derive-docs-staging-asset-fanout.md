---
id: R806
title: "Derive the docs staging css/docinfo fan-out from the staged tree"
status: Backlog
bucket: dx
priority: 7
theme: tooling
depends-on: []
created: 2026-08-22
last-updated: 2026-08-22
---

# Derive the docs staging css/docinfo fan-out from the staged tree

`docs/pom.xml` carries a hand-maintained per-directory roster for the staged site's
assets: one `<copy>` of `css/` and the two `docinfo-*.html` theme files per staged
directory, every entry `failonerror="false"`. The roster is a copy of a fact the staged
tree already states (which directories contain rendered pages), and a missed entry fails
nothing: the AsciiDoctor error handler does not see it, the ant-level signal is
suppressed, and the first report is a human noticing a published page with no stylesheet
and no site nav. Every new docs directory (the principles section, per the
principles-section-under-architecture item, is the occasion that exposed this) must
remember to append two entries by hand.

The fix is to derive the fan-out: a build step that walks the staged tree and copies
`css/` and the docinfo files into every staged directory containing a `.adoc`, deleting
the roster. `SchemaIdentifierDriftCheck` reading the booted store instead of the DDL is
the in-repo precedent for reading the tree rather than listing it; the roadmap-tool
staging step already runs Java over the same tree, so it is a natural host.
