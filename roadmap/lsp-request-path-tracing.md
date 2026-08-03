---
id: R571
title: "LSP request-path tracing and phase timing instrumentation"
status: Backlog
bucket: tooling
priority: 3
theme: lsp
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# LSP request-path tracing and phase timing instrumentation

The LSP carries no timing or request-path instrumentation: one logger in the whole main source
tree, no span timings, no record of which thread ran which phase. When an editor session on a
large subgraph stops responding, there is nothing to read afterwards, so the cause has to be
guessed from code inspection. That guessing is expensive and unreliable, because the plausible
mechanisms are structurally different from each other and the fix differs per mechanism:
diagnostics recomputed inline on the lsp4j message-reading thread on every keystroke
(`Workspace.enqueueAndNotify` fires the publish listener synchronously), the whole-workspace
recalculation each build swap triggers (`markAllForRecalculation` queues every open file, and each
file re-scans the entire `ValidationReport`), per-site catalog rescans inside the diagnostics walk,
full-buffer decode plus a full type-index walk per edit in `WorkspaceFile.applyEdit`, and
uncancelled superseded requests (handlers use `CompletableFuture.supplyAsync`, never lsp4j's
`CancelChecker` seam).

What is needed is a trace seam that attributes wall-clock to a named phase and records the thread
that ran it, default-off and allocation-free when off, so an affected session can be asked to turn
it on and produce an attributable log instead of a symptom report. Instrumentation only; this item
changes no behaviour and fixes none of the mechanisms above. Its output is what picks which of
them to fix, and each fix is its own item.

Constraint worth recording: the stdio entry point (`Launcher`) shares `System.out` with the
JSON-RPC stream, so trace output must never reach stdout. Anything the seam emits has to go
through a channel that cannot corrupt the protocol.

