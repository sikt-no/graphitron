---
id: R576
title: "Residual cleanups left by the LSP trace seam"
status: Backlog
bucket: cleanup
priority: 6
theme: lsp
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# Residual cleanups left by the LSP trace seam

Two cosmetic residuals from the trace-seam landing, both found at its Done gate.

`GraphitronTextDocumentService.definition` was wrapped in a span without reflowing the body it
now nests: the `file -> {` lambda passed to `workspace.withView` sits at the same indentation
as the `workspace.withView(` call it belongs to, and the closing `});` is indented past both.
Every sibling handler in the file reflowed correctly, so this one reads as a merge artifact.

`LspTrace`'s class javadoc and `span`'s say the disabled path "allocates nothing". The `Span`
itself does not, which is the claim worth making and the one `LspTraceTest` pins by instance
identity, but `detail(String, Object)` boxes its `int` arguments at the call site whether or
not the seam is on, and those arguments are evaluated either way: `chars`, `bytes`, `files`,
`directives`, `declared`, `diagnostics` and the rest. Per keystroke that is a handful of
`Integer.valueOf` calls against a 13.8 ms type-index walk, so the claim holds everywhere it
matters and nothing needs to get faster. The wording should either soften to say the seam
allocates no span, or an `int`/`long` overload of `detail` should make the absolute version
true.

