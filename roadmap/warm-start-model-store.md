---
id: R597
title: "Persist the model store under target/ for warm-start surfaces"
status: Backlog
bucket: architecture
theme: classification-model
depends-on: [graphitron-model-captures-facts]
created: 2026-08-06
last-updated: 2026-08-06
---

# Persist the model store under target/ for warm-start surfaces

The model store (R595) is in-memory and per-run: every surface that wants facts pays a full
capture pipeline before it can answer anything, so the LSP and MCP server boot cold. If the
generator persists the populated store to an H2 file under `target/` at the end of each run,
a surface can open the previous run's facts almost as soon as the JVM has booted and serve
completions, schema queries, and the read-only SQL surface immediately, refreshing when its
own run completes (with registry capture, R595, that refresh is per-file incremental after
boot, not a full pipeline); a plain SQL client (an agent) can query the fact base as a build artifact
without booting graphitron at all.

The cache preserves the substrate's invariants rather than bending them: it is persisted
state, never state *of record*. The file is stamped (DDL content hash, generator version, run
identity); any mismatch discards and rebuilds, so no migration ever exists, and `target/`
semantics make deletion always correct. Surfaces label answers with the stamp's run identity
so staleness is visible, not silent. Design questions for the Spec round: persist mechanism
(file-backed store during the run versus end-of-run export), reader concurrency while a build
writes (H2 file locking, copy-on-open, or auto-server mode), and which meta relation carries
the stamp.
