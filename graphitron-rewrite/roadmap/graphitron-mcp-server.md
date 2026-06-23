---
id: R118
title: "Graphitron MCP server: live catalog discovery and docs RAG in graphitron:dev"
status: Backlog
bucket: feature
theme: lsp
depends-on: []
---

# Graphitron MCP server: live catalog discovery and docs RAG in graphitron:dev

The foundational use case is greenfield onboarding: a developer points graphitron at a large existing database with an empty (or nearly empty) GraphQL schema and needs to discover what is available before they can author anything. With no schema yet, the jOOQ catalog is the only source of "what exists", so structured and semantic discovery over that catalog is not a nice-to-have, it is the first thing the server must do. Alongside that, the server answers questions against the bundled graphitron documentation so an agent can learn how to express what it discovered. This item delivers an MCP server embedded in the long-running `graphitron:dev` JVM, exposing both capabilities to any MCP-aware client (Claude Code, Cursor, and others) without a hosted service, an external database, or an API key.

This is a rewrite of the earlier R118 framing. The previous version made this server the first consumer of the R117 DuckDB knowledge base and depended on it. That framing presupposed a built schema and generated codebase to introspect, which is the opposite of the greenfield case above. R117 and R118 are both unbuilt, so this item takes over R118 and drops the dependency: the foundational discovery tool should not wait on the more speculative self-knowledge programme. The cross-dimension self-knowledge joins R117 frames remain valuable and can ride the same embedding interface later; they are simply not what gets built first.

## Stability gradient (the design spine)

The four corpora differ in how often they change, and that volatility maps directly onto how each is stored and refreshed:

| Corpus | Stability | Strategy |
|---|---|---|
| Graphitron documentation | Stable; changes only with a graphitron version | Bundled in the jar as a prebuilt index, preloaded at startup |
| Database catalog (jOOQ meta) | Slow; changes when the DB schema is re-captured | Indexed at startup from the project's catalog, cached by catalog-jar content hash, refreshed on the classpath-change trigger |
| Service code | Faster churn | Live refresh if indexed; deferred past the first iteration |
| GraphQL schema | Least stable; it is the work itself | Never embedded; served live and structured from the LSP `Workspace`, always fresh |

The gradient is what lets an embedded store work without fighting itself: the most volatile data never enters the store, so the store only ever holds stable or slow-moving content.

## Architecture: live model for structured, vector store for semantic

Two distinct data shapes, two mechanisms:

- *Structured catalog and schema* (list tables, describe a table, foreign-key neighbours, type backings, classifications) is already held in memory by the existing dev-mode infrastructure: `JooqCatalog`, `CompletionData`, and the LSP `Workspace`. These are exposed as deterministic MCP tools straight over the live Java model. This path needs no store at all and is the freshest, most accurate answer the server can give; because it is exact, the agent cannot be misled by an approximate match.
- *Semantic retrieval* (documentation RAG, and fuzzy catalog discovery over table and column names plus DB comments) is the only part that needs a vector store. Embeddings are computed in-process with no API key (for example the langchain4j in-process ONNX all-MiniLM model, 384-dimensional), and stored behind an embedding-store interface so the backend stays swappable.

Catalog comments are the fuel for semantic catalog discovery: jOOQ Meta only surfaces table and column remarks if the codegen step captured them. The Spec should document the expectation that consumer projects enable comment capture, or the discovery degrades to name-only matching.

## Store choice is a Spec decision

We are greenfield; nothing is built, so there is no committed store to honour. The store choice is deliberately left open and decided in Spec against these criteria:

- *Vector and ANN maturity* favours Apache Lucene HNSW: pure Java, no native dependency beyond the ONNX embedder, battle-tested KNN, and incremental writes that fit the live-refresh model naturally.
- *Single-store reuse with a future self-knowledge KB* favours DuckDB: full relational SQL and a `graphitron.sql` escape hatch for cross-dimension joins, at the cost of adopting the younger VSS extension for the vector layer and a second native library.
- *Native footprint* favours Lucene (ONNX only) over DuckDB (ONNX plus DuckDB JNI).

The structured catalog and schema tools do not require either store (they are served from the live Java model), so the store decision is really only about the semantic layer. Whichever backend is chosen sits behind the embedding-store interface, so a wrong first guess is cheap to revise.

## Transport: Streamable HTTP on a loopback port in the graphitron:dev JVM

The server is hosted in the long-running `graphitron:dev` JVM, the same process that binds the LSP and runs the schema and classpath watchers. That process (Maven, and under `mvn quarkus:dev` Quarkus too) writes freely to stdout and stderr, so the MCP stdio transport is unusable here for the same reason the LSP avoided stdio and chose TCP: stdio requires that nothing but JSON-RPC ever touches stdout.

The server therefore uses the MCP Streamable HTTP transport (the current spec transport, successor to the older HTTP+SSE transport), provided by the official MCP Java SDK without an external web framework, bound on:

- a loopback address (`127.0.0.1`), matching the LSP's posture;
- a dedicated port, distinct from the LSP's TCP port, exposed as a config property (for example `graphitron.dev.mcpPort`) with its own default.

Because the host is the graphitron:dev / LSP JVM and not the user's Quarkus app, the server binds the SDK's Streamable HTTP transport on a lightweight embedded HTTP listener inside that JVM. Clients that can only spawn a stdio subprocess are still supported through a thin stdio-to-HTTP proxy: the proxy is a clean dedicated process, so the noisy host process stays isolated behind the port.

## Live refresh and persistence

The store is opened with a persistent connection in the dev JVM and both read and written from that one process; within a single process this is fully supported (concurrent reads and writes), and there is no cross-process write contention because the dev JVM owns its working store, separate from the read-only docs index shipped in the jar. Refreshes are batched on the existing debounced watcher trigger (the classpath watcher already fires when the jOOQ catalog is re-captured), not per keystroke, which suits an analytical store. The catalog index is persisted between runs, keyed by catalog-jar content hash, so a large database is not re-embedded on every startup.

## Tools sketched

Catalog discovery first; documentation second. The self-knowledge lookups from the previous R118 draft are deferred to the later KB track.

| tool | input | output |
|---|---|---|
| `graphitron.catalog.search` | free-text query | tables and columns ranked by semantic similarity over names and DB comments; the foundational discovery tool for a large DB with an empty schema |
| `graphitron.catalog.tables` | optional schema or name filter | structured list of tables, served live from the jOOQ catalog |
| `graphitron.catalog.describe` | table name | columns, types, nullability, primary key, foreign keys in and out; served live from the jOOQ catalog |
| `graphitron.schema` | optional coordinate | current GraphQL types, fields, directives and their classifications, served live from the LSP `Workspace` |
| `graphitron.docs.search` | free-text query | semantic retrieval over the bundled graphitron documentation |

## Open questions for the Spec phase

1. *Store backend.* Lucene versus DuckDB for the semantic layer, against the criteria above. The structured tools are store-independent, so this question is scoped to the vector layer alone.
2. *Vector-index refresh strategy.* When the catalog changes, incrementally update the ANN index or rebuild it. At catalog scale a full rebuild is fast and runs off the debounced trigger, so either is acceptable; pick one in Spec.
3. *Comment-capture expectation.* How to surface to consumer projects that catalog semantic search depends on jOOQ codegen capturing table and column remarks.
4. *Result paging.* Discovery queries over a large catalog can return many rows, and MCP caps response size. Pick a default page size and a cursor tool.
5. *Embedding-model swap.* The interface keeps the in-process no-key model swappable for a hosted model later; confirm the interface boundary in Spec.

## Out of scope for this item

The R117 DuckDB self-knowledge KB and its cross-dimension joins (a later track that can reuse the embedding interface); service-code and generated-code embedding (deferred past the first iteration); a hosted or SaaS deployment model (the local, in-process model is V0); authentication beyond loopback binding and filesystem permissions; any API-key-dependent embedding or generation in this first iteration.
