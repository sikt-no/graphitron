---
id: R372
title: "MCP RAG foundation: in-process ONNX embedder behind EmbeddingModel + Lucene HNSW behind EmbeddingStore seam (R118 slice 8)"
status: Backlog
bucket: feature
theme: lsp
depends-on: []
created: 2026-06-25
last-updated: 2026-06-25
---

# MCP RAG foundation: in-process ONNX embedder behind EmbeddingModel + Lucene HNSW behind EmbeddingStore seam (R118 slice 8)

Slice 8 of the R118 MCP-server programme: the semantic-layer foundation that the two
**semantic** tools (slice 9 `docs.search`, slice 10 `catalog.search`) sit on. It ships no
agent-facing tool itself; it stands up the embedder and the vector-store seam, and it is the
slice that realises `graphitron-mcp`'s reason for existing as a separate module, quarantining
the heavy native dependency off `graphitron-maven-plugin`'s compile surface. Unlike the
structured slices (R362, R368), it touches neither the live `Workspace` nor the LSP model, so it
is **independent of the structured-tool track and can be implemented in parallel with all of it**,
including before slices 3-7 land.

## What it delivers

- **Embedding behind a seam.** `bge-small-en-v1.5-q` (384-dim, MIT), in-process via langchain4j,
  behind langchain4j's `EmbeddingModel` interface so the model stays swappable. No API key, no
  network. bge is asymmetric: the query-instruction prefix is applied on the query side only, so the
  seam must distinguish query-embedding from document-embedding rather than expose a single
  `embed(text)`.
- **Storage behind a seam.** An `EmbeddingStore` abstraction with Lucene HNSW (pure Java, hybrid
  BM25 + KNN in one index) as the committed V0 backend. An in-memory store suffices for the
  docs-only first consumer (slice 9) and is the simplest thing that exercises the seam; the
  large catalog corpus (slice 10) graduates to Lucene. Both sit behind the same seam so the
  consumers do not know which backend they hit. DuckDB stays deferred to the R117 KB programme.
- **The async-load lifecycle the cross-cutting principle requires.** R118 binds every slice to
  "bind sync, warm async, never block the dev loop": the embedder load and any index build run on a
  background daemon thread, `graphitron:dev` reaches its watch loop without waiting, and **a RAG
  failure never takes down dev**, the server falls back to structured-only. This slice owns the
  warm-state lifecycle (loading / ready / failed) and the degradation contract that slices 9/10
  report through ("index warming, use the structured tools meanwhile").

## Why this is the dependency-quarantine payoff

R341 created `graphitron-mcp` explicitly as the quarantine seam, and R361 noted the `graphitron-lsp`
edge it added is "orthogonal to the module's dependency-quarantine purpose". This slice is where
that purpose is finally exercised: ONNX Runtime (JNI + per-platform natives) is the one genuinely
heavy dependency in the whole programme, and it lands here, off the plugin's compile surface. The
store is pure Java (Lucene); the native footprint is the embedder alone. Surefire on this module
will likely need `--enable-native-access=ALL-UNNAMED` once the ONNX load runs under test (mirroring
the tree-sitter note R361 flagged for the `lsp` edge).

## Open questions for the Spec phase

- **Multilingual embedding (R118 OQ2).** `bge-small-en` is English-only; consumer DBs (notably
  Sikt's own) may carry Norwegian comments. The `EmbeddingModel` seam is precisely what keeps the
  swap to `multilingual-e5-small` cheap; decide whether V0 ships English-only with the swap
  documented, or pays the multilingual cost up front (may mean bundling the ONNX ourselves rather
  than a prebuilt langchain4j module).
- **In-memory vs Lucene for the seam's first cut.** Whether slice 8 stands up Lucene immediately or
  ships the in-memory store and lets slice 10 graduate it. The seam shape must not assume either.
- **Test posture.** The embedder load is heavy; decide the tier (a slow-tagged infrastructure test
  that actually loads the model vs a seam-level test against a fake `EmbeddingModel`), so CI does
  not pay an ONNX load on every run.

## Builds on

- **R341** (Done): `graphitron-mcp` exists as the quarantine module; this slice is the dependency
  it was created to hold.
- **R361** (Done): the `tools` capability and the async-warm posture the dev loop already tolerates.

Both landed, so *Builds on*, not `depends-on`.

## Related

- **R118** (`graphitron-mcp-server.md`, Backlog programme): slice 8, the only slice that is pure
  semantic-layer infrastructure with no agent-facing tool.
- Blocks **slice 9** (`docs.search`) and **slice 10** (`catalog.search`), and the optional slice 11
  (method search): each is a consumer of this embedder + store seam and should carry
  `depends-on: [R372]` when filed.
- **R117** (knowledge-base programme): the internal-model counterpart that can later reuse this same
  `EmbeddingModel` / `EmbeddingStore` seam; DuckDB is deferred there, not here.
