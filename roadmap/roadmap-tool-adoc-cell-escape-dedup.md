---
id: R604
title: "LeafCoverageReport carries a private escapeAdocCell duplicating Main's"
status: Backlog
bucket: cleanup
priority: 3
theme: docs
depends-on: []
created: 2026-08-07
last-updated: 2026-08-07
---

# LeafCoverageReport carries a private escapeAdocCell duplicating Main's

`LeafCoverageReport` declares a private `escapeAdocCell` that is a character-for-character duplicate of
`Main`'s: both escape a pipe for AsciiDoc table-cell context and nothing else. Two copies of one escaping
fact means the next cell-escaping rule (or the inert-span emission R587 introduces around the same
emitters) lands in one and silently not the other. Fold them into one shared helper in roadmap-tool;
natural to co-locate with the R587 `InertSpans` unit if that item has landed, but not dependent on it.
Surfaced by the R587 Spec review, which flagged it as adjacent new scope rather than part of that item.
