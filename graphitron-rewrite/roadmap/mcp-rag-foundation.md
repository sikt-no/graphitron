---
id: R361
title: "Local RAG foundation: in-process ONNX embedder and Lucene store seams in graphitron-mcp"
status: Backlog
bucket: feature
priority: 5
theme: lsp
depends-on: []
created: 2026-06-24
last-updated: 2026-06-24
---

# Local RAG foundation: in-process ONNX embedder and Lucene store seams in graphitron-mcp

This is slice 8 of the R118 MCP server programme (the "RAG foundation" slice); R118 frames the
surface and settles the stack, this item lands the substrate. Exactly two tools in R118's surface
are semantic: `docs.search` (retrieval over the bundled documentation) and `catalog.search` (fuzzy
discovery over table names and comments). Both need a local embedding-and-vector-store layer that
runs inside the `graphitron:dev` JVM with no API key, no network, and no hosted service. None of
that layer exists today: the R341 skeleton serves static content only, and every structured tool
(catalog, code, schema, diagnostics) ships without it. This slice builds the embedder and store as
standalone, content-agnostic infrastructure behind swappable seams, so the one heavy native
dependency lands and is smoke-tested before either consumer is wired.

The stack is settled in the programme and binding here. Embedding is in-process via langchain4j:
`bge-small-en-v1.5-q` (384-dim, MIT-licensed) behind langchain4j's `EmbeddingModel` interface so the
model stays swappable, with bge's asymmetric query-instruction prefix applied on the query side only.
Storage is Lucene HNSW (pure Java; hybrid BM25 + KNN in one index) behind an `EmbeddingStore` seam;
an in-memory store is enough for the first docs corpus and Lucene graduates in for the larger catalog
corpus, both behind the same seam. The native footprint is the embedder, not the store: ONNX Runtime
(JNI plus per-platform natives) is the single heavy dependency, and isolating it is the whole reason
`graphitron-mcp` exists as a quarantine module, so these coordinates land in this module's pom and
stay off `graphitron-maven-plugin`'s compile surface. Licensing is permissive throughout (Lucene and
langchain4j Apache 2.0; ONNX Runtime and bge MIT), safe to redistribute.

Three cross-cutting principles from the programme constrain the wiring, and they are properties of
the foundation rather than of its consumers, so they belong in this slice. Bind sync, warm async,
never block the dev loop: the embedder load and any index build run on a background daemon thread, so
the transport binds and `graphitron:dev` reaches its watch loop without waiting on model
initialization. A RAG failure never takes down dev: if the embedder or store fails to come up, the
server logs it and falls back to structured-only rather than failing the bind. Semantic capability
degrades gracefully ("index warming") until warm rather than erroring.

Scope is the seams and the quarantine, not the content. The deliverable is the `EmbeddingModel` and
`EmbeddingStore` abstractions, the dependency-managed coordinates in the parent pom and
`graphitron-mcp`, the async warm-up lifecycle, and a smoke test that round-trips embed then store then
nearest-neighbour query against a tiny in-test corpus. Out of scope, each staying with its own slice:
chunking and pre-embedding the `.adoc` docs and bundling a prebuilt index (R118 slice 9,
`docs.search`); composing catalog descriptors, persisting the index keyed by catalog-jar content hash,
and the classpath-change refresh (R118 slice 10, `catalog.search`); and surfacing either tool to MCP
clients. Deferred open questions the programme already records: multilingual embedding (swap to
`multilingual-e5-small` only if real Norwegian catalog comments need it, possibly bundling the ONNX
ourselves rather than via a prebuilt langchain4j module) and a DuckDB-backed store (the R117
knowledge-base programme).
