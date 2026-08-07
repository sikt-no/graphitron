---
id: R601
title: "The diagnostic stream unifies"
status: Backlog
bucket: structural
priority: 3
theme: diagnostics
depends-on: []
created: 2026-08-06
last-updated: 2026-08-07
---

# The diagnostic stream unifies

Graphitron's build findings reach consumers through three parallel channels that were meant to
collapse into one and never did. `ValidationReport` carries `errors: List<ValidationError>` and
`warnings: List<BuildWarning>` as separate slots (`ValidationReport.java:24-28`); the LSP-aligned
`Diagnostic` record shipped with the walker foundation slice but the planned `walkerDiagnostics`
slot next to them was never added (zero hits in the tree), and `Diagnostic` still carries no
`tags` component (`Diagnostic.java:23-30`). Every producer that wants to say something must pick
a channel, every reader that wants the whole picture must drain three, and `BuildWarning` is a
shape whose only reason to exist is that the unification never landed.

The deliverable is one located-violation stream: the three slots collapse, `BuildWarning`
retires, `Diagnostic` gains `tags`, and the lsp4j projection boundary stays where it is (no code
below the LSP module sees the lsp4j types; the LSP-side projector maps at the wire). Severity is
a column, not a channel.

This item is cut from the retired dimensional-model umbrella, which owned "the unified
diagnostic surface" without a delivery vehicle. It is deliberately independent of the fact-base
migration: R589 mints violations *into* the existing diagnostics channel and fixes only their
ordering, and the model store leaves diagnostics out of its first iteration by design, so
unifying the report surface neither waits for nor blocks that work. `mcp-aggregated-diagnostics`
(R569) is the waiting consumer, in its bridge loader rather than its aggregate now that the
aggregate is a store query: loading one stream into the store's diagnostic bridge is one load,
loading three channels is three loads kept honest by hand.
