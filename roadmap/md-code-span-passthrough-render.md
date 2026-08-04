---
id: R587
title: "Roadmap markdown code spans render as substituting AsciiDoc spans, so quoted macros go live"
status: Backlog
bucket: cleanup
priority: 3
theme: docs
depends-on: []
created: 2026-08-04
last-updated: 2026-08-04
---

# Roadmap markdown code spans render as substituting AsciiDoc spans, so quoted macros go live

A markdown inline code span is literal by definition; an AsciiDoc one is not, because a single-backtick
span applies the normal substitution group with macros included. The roadmap md-to-adoc render passes
backticks straight through (`LeafCoverageReport` notes "AsciiDoc handles backticks for code spans"), so a
roadmap author who quotes a macro in backticks publishes a live one. A backticked xref yields a real link
on the rendered roadmap page, and a backticked attribute reference resolves, or warns and fails the docs
build under its `failIf severity=WARN` setting, rather than showing as typed. Writing this item's own
problem statement hit the second case. The author cannot see this from the markdown source, and the
workaround is to know AsciiDoc passthrough syntax while writing markdown, which defeats the point of
authoring items in markdown. Emitting a passthrough span instead of a bare backtick span would retire the
class for every item at once. Surfaced while reviewing the dangling-cross-file-xref-anchor gate, whose own
body is the first instance: that item hand-passthroughs its three quoted xrefs rather than waiting on this.
