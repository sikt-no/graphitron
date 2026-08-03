---
id: R571
title: "LSP request-path tracing and phase timing instrumentation"
status: Ready
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

That is what happened. The In Review to Done review (independent session) approved the
delivery against the contract, and the approval was then reversed at the user's direction in
favour of rework: the improvements the review had surfaced were routed to two fresh Backlog
items, and folding them back into this still-open item is both cheaper and faster, since
rework lands in Ready and is implementable immediately while a Backlog item has to walk
Backlog to Spec to Ready first. The approval commit and its two Backlog items are reverted
on trunk; `R575` and `R576` were allocated and stay burned as gaps, so the reverted commit's
message keeps its meaning. The design was not re-opened, only extended: everything under
"What shipped" stands as delivered and stays on trunk while the second pass lands.

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

## Rework requested at the Done gate

The first pass honours its contract: full reactor green under `-Plocal-db`, 471
`graphitron-lsp` tests green, no behaviour change site by site, and no deferred fix leaked
in. None of the following is a contract failure, which is why the delivery stays on trunk
rather than being reverted; they are the gap between an instrument that works and one worth
keeping permanently, and the decision to keep it permanently is what makes them this item's
business rather than a later one's.

**A wall-clock timestamp on every line.** The seam's headline signal is an unmatched `>` from
a phase that never returned, and an unmatched `>` with no clock says where the server stuck
but not when or for how long. It cannot be correlated with a user's "it froze around 14:32",
with the editor's own log, or with a build swap in another window, and a log whose tail is an
open span cannot be distinguished from a log that merely ended. The monotonic span id is
currently the only ordering evidence when lines from several threads interleave. This is the
highest-value item in the list and the cheapest.

**Recommend the file sink over stderr for hang investigation, and say why.** `file.reparse`
and `file.typeIndex` emit while their caller holds `Workspace`'s mutator lock, and
`PrintStream.println` to a pipe whose reader has stopped draining blocks. A client that
ignores the server's stderr can therefore hang the LSP inside its critical section: the
instrument manufacturing the symptom it was built to attribute. Mainstream clients do drain
stderr into an output channel, so this is latent rather than live, and the mitigation already
exists and is documented. What is missing is that
`docs/architecture/how-to/dev-loop-internals.adoc` presents `graphitron.lsp.trace.file` as an
alternative rather than as the recommendation for the hang case, and gives none of this
reasoning. Consider also whether the lock-held sites should buffer and emit after the lock
releases, which would remove the hazard rather than document around it; that is a design
question for the implementer, not a settled requirement.

**Cover the three advertised configuration surfaces that no test reaches.** `SLOW_NANOS`
resolves into a `static final` at class initialisation and `sink` likewise, so nothing
exercises the `SLOW` tag, the `graphitron.lsp.trace.file` path, or the `GRAPHITRON_LSP_TRACE`
env-var enable. `LspTraceTest` covers exactly the paths that were given a package-private
seam (`setEnabled`, `sinkForTesting`). Reading the threshold through the same kind of seam the
sink already has would let all three be pinned. Keep `sinkForTesting` package-private: a
public runtime-swappable sink is a way to aim trace lines at the JSON-RPC stream by accident,
and that reasoning still holds.

**An editor-visible channel, or an explicit decision to defer it.** `graphitron-lsp` now has
two unrelated output stories, this seam and the module's lone slf4j logger in `Definitions`,
and neither reaches a user whose editor spawned the server. lsp4j's `$/setTrace` plus
`window/logMessage` is the channel that does, and `setEnabled` was written anticipating
exactly that handler. Turning tracing on should not require a relaunch flag from someone who
never launches the server by hand. This is the largest of the four and the only one that
could reasonably be carved back out to its own item; if the implementer defers it, the
deferral belongs in this body with its reasoning, not in silence.

**Two cosmetics.** `GraphitronTextDocumentService.definition` was wrapped in a span without
reflowing the body it now nests: the `file -> {` lambda passed to `workspace.withView` sits at
the same indentation as the `workspace.withView(` call it belongs to, and the closing `});`
is indented past both. Every sibling handler in the file reflowed correctly. Separately,
`LspTrace`'s class javadoc and `span`'s say the disabled path "allocates nothing"; the `Span`
does not, which is the claim worth making and the one `LspTraceTest` pins by instance
identity, but `detail(String, Object)` boxes its `int` arguments at the call site whether or
not the seam is on, and those arguments are evaluated either way (`chars`, `bytes`, `files`,
`directives`, `declared`, `diagnostics`). Per keystroke that is a handful of `Integer.valueOf`
against a 13.8 ms type-index walk, so nothing needs to get faster: either soften the wording
to say the seam allocates no span, or add an `int`/`long` overload of `detail` and make the
absolute version true.

Noted and deliberately not requested: the diagnostics walk carries one span for the whole
directive loop plus a `directives=` count, so the ranked hypothesis about per-site catalog
rescans is attributable only by dividing. A per-directive span would emit hundreds of lines
per file and be worse. If anything is done here, it is a sentence in the how-to naming the
subtraction (`diagnostics.compute` minus `diagnostics.validatorReport`, over `directives=`)
rather than a finer span.

## Out of scope

Every fix, still, including through the rework pass. The second pass changes the instrument,
never the thing being measured: it deliberately does not debounce `didChange`,
move the recalculation off the dispatch thread, memoise the per-site catalog scans, index the
validator report by URI, or adopt lsp4j's `CancelChecker`. Which of those is worth doing is
what the trace output decides, and each is its own item. Also out of scope: the scale-fixture
test harness that would let those fixes be measured in-process rather than in an editor.

