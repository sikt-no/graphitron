---
id: R430
title: "LSP publishes graphitron:dev compile diagnostics against generated-file URIs"
status: Backlog
bucket: feature
priority: 3
theme: lsp
depends-on: []
created: 2026-07-03
last-updated: 2026-07-03
---

# LSP publishes graphitron:dev compile diagnostics against generated-file URIs

R410's *Surfacing compile diagnostics* section named three consumers for the incremental-compile
round's diagnostics: the console dev-loop block, the MCP `diagnostics` tool, and the LSP publishing
them against the generated file's URI (best-effort, so an editor with that generated `.java` open
shows the javac error inline). R410 shipped the first two; the diagnostics already land on
`Workspace.compileDiagnostics()` (in the LSP module) after every round, but no LSP
`textDocument/publishDiagnostics` is emitted for them. Close the gap: on each
`setCompileDiagnostics` swap, publish the round's error diagnostics against the generated-file URIs
(resolving `CompileDiagnostic.file()` under the generated-sources root) and clear diagnostics that
resolved. Best-effort per the R410 spec: an unresolvable path is skipped, not an error.
