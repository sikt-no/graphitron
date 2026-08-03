---
id: R571
title: "LSP request-path tracing and phase timing instrumentation"
status: In Review
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

## Sequencing note

This item was filed and implemented in one session at the user's explicit direction, so it
reached In Review without passing a Spec to Ready sign-off. The gate that still applies is
In Review to Done, and the reviewer for it is a different party than the implementer. The
skipped gate is recorded here rather than papered over: a reviewer who wants the design
re-opened should send the item back to Ready.

## What shipped

`LspTrace` in a new `no.sikt.graphitron.lsp.trace` package: a span seam that emits one line
when a phase opens and one when it closes, carrying the phase name, the thread that ran it,
the elapsed time, and caller-attached key/value context.

Three design decisions carry the weight:

**Open and close are separate lines.** A phase that never returns emits an unmatched `>`.
That is the signal separating "stuck here" from "slow everywhere", and a duration-only
format would show nothing at all for the hang this exists to diagnose.

**Output bypasses slf4j, going to `System.err` or to a file named by
`graphitron.lsp.trace.file`.** Two independent hazards point the same way. A logging backend
configured with a console appender writes to `System.out`, which in the stdio deployment is
the JSON-RPC stream, so a single stray byte desynchronises the framing. In that same
deployment the runtime classpath typically carries `slf4j-api` with no backend bound, so an
slf4j-based seam would emit nothing at all. A stream the class owns is safe against the
first and immune to the second. The sink is captured at class initialisation so a later
`System.setErr` cannot redirect trace output onto the protocol stream; the test seam
(`sinkForTesting`) is package-private for the same reason.

**Off is genuinely free.** `span()` returns a shared no-op singleton when disabled, so the
seam allocates nothing per call and can sit inside a per-keystroke edit. `LspTraceTest`
asserts instance identity, not merely absence of output, so a future refactor that starts
allocating fails the test.

Enabled by `graphitron.lsp.trace` or `GRAPHITRON_LSP_TRACE`; `graphitron.lsp.trace.slowMs`
(default 100) tags slower phases `SLOW`. `setEnabled` allows a runtime flip, anticipating a
`$/setTrace` handler.

Instrumented sites, chosen to cover the mechanisms listed above rather than for uniform
coverage: the document-service notifications and the four request handlers; the diagnostic
drain, with the queued-file count on the outer span and the per-file diagnostic count on the
inner; the workspace lock-held regions in `withView` / `withAllViews` (scoped to the lock,
not to the caller's lambda, so the duration reads as lock-wait) and the mutate/notify split
in `enqueueAndNotify`; the per-edit reparse and type-index refresh in `WorkspaceFile`; the
diagnostics document walk and, separately, its whole-report validator projection; and
`LspVocabulary.load`.

Documented under "Tracing the LSP request path" in
`docs/architecture/how-to/dev-loop-internals.adoc`, including the three signals worth
reading off a trace (a shared `thread=` between a request and a notification, a
`workspace.notify` dwarfing its `workspace.mutate`, and a non-trivial
`workspace.snapshot`).

## First measurement

A synthetic 400-type, 24 KB buffer driven through open-then-edit already corrects one of the
ranked hypotheses that motivated the item. The dominant per-edit cost is the full-tree
`TypeNames` walk in `refreshTypeIndex` at 13.8 ms, not the whole-buffer decode handed to
tree-sitter, whose incremental reparse comes in at 1.1 ms. Both sit inside the workspace
lock. `diagnostics.compute` runs 100 ms for 400 directives, and a cold `vocabulary.load`
costs 270 ms.

## Out of scope

Every fix. This item changes no behaviour, and deliberately does not debounce `didChange`,
move the recalculation off the dispatch thread, memoise the per-site catalog scans, index the
validator report by URI, or adopt lsp4j's `CancelChecker`. Which of those is worth doing is
what the trace output decides, and each is its own item. Also out of scope: the scale-fixture
test harness that would let those fixes be measured in-process rather than in an editor.

