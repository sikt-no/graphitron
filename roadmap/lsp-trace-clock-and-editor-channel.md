---
id: R575
title: "LSP trace: no clock on the line, and no editor-visible channel"
status: Backlog
bucket: tooling
priority: 3
theme: lsp
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# LSP trace: no clock on the line, and no editor-visible channel

Three gaps in the shipped `LspTrace` seam, all found at its Done gate and none of them
demanded by the contract it delivered against.

**No clock on the line.** `LspTrace.ActiveSpan.emit` writes a monotonic span id, the phase
name, the thread and (on close) an elapsed duration, but no wall-clock timestamp. The seam's
headline signal is an unmatched `>` from a phase that never returned, and an unmatched `>`
with no clock says where the server stuck but not when or for how long: it cannot be
correlated with a user's "it froze around 14:32", with the editor's own log, or with a build
swap in another window, and a log whose tail is an open span cannot be distinguished from a
log that simply ended. It also makes multi-thread interleaving harder to read than it needs
to be, since the span id is the only ordering evidence.

**No editor-visible channel.** The seam writes to a stream it owns (stderr, or a file named
by `graphitron.lsp.trace.file`) for good reason: the stdio launcher speaks JSON-RPC over
stdout, and the runtime classpath usually carries `slf4j-api` with no backend bound. But
`graphitron-lsp` now has two unrelated output stories, this one and the module's lone slf4j
logger in `Definitions`, and neither reaches a user whose editor spawned the server. lsp4j's
own `$/setTrace` plus `window/logMessage` is the channel that does, and
`LspTrace.setEnabled` was written anticipating exactly that handler. Turning the seam on
should not require a relaunch flag from someone who never launches the server by hand.

Worth deciding alongside it: stderr as the *default* sink is the riskiest choice for the
scenario the seam exists to diagnose. `file.reparse` and `file.typeIndex` emit while their
caller holds `Workspace`'s mutator lock, and `PrintStream.println` to a pipe whose reader has
stopped draining blocks, so a client that ignores the server's stderr can hang the LSP inside
its critical section: the tool manufacturing the symptom it was built to attribute.
Mainstream clients do drain stderr, so this is latent rather than live, but the how-to should
recommend the file sink for hang investigation and say why rather than presenting it as an
alternative.

**Three of the four advertised configuration surfaces are untested.** `SLOW_NANOS` is
resolved into a `static final` at class initialisation and `sink` likewise, so nothing
exercises the `SLOW` tag, the `graphitron.lsp.trace.file` path, or the
`GRAPHITRON_LSP_TRACE` env-var enable. `LspTraceTest` covers the paths that got a
package-private seam (`setEnabled`, `sinkForTesting`) and only those. Reading the threshold
through the same kind of seam the sink already has would let all three be pinned.

