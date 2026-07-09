---
id: R118
title: "Graphitron MCP server programme: agent-facing schema, catalog, code, and docs tools in graphitron:dev"
status: Backlog
bucket: feature
theme: dev-loop
depends-on: []
---

# Graphitron MCP server programme: agent-facing schema, catalog, code, and docs tools in graphitron:dev

This item is a *programme*, not a single deliverable. It absorbs the earlier single-feature R118 (live catalog discovery + docs RAG) and widens it into the full agent-facing surface of the MCP server embedded in `graphitron:dev`. The foundational use case is unchanged and still anchors the programme: greenfield onboarding, where a developer points graphitron at a large existing database with an empty (or nearly empty) GraphQL schema and needs to discover what exists before authoring anything. The programme generalises that into a standing capability: any MCP-aware client (Claude Code, Cursor, others) can discover the database catalog, the hand-written Java the schema wires to, the current schema and its classifications, the directive grammar, and the live diagnostics, plus semantic search over the catalog and the bundled documentation, all without a hosted service, an external database, or an API key.

Each section below names a slice that can be filed as its own item and implemented independently. This programme item is the strategic anchor and the home of the cross-cutting principles; it is not itself implemented.

## What the skeleton already ships (R341, Done)

R341 delivered the transport-and-lifecycle seam: a `graphitron-mcp` module hosting the official MCP Java SDK's Streamable HTTP transport in embedded Jetty, loopback-only on `127.0.0.1:8488`, a single `/mcp` endpoint, wired into `DevMojo` as a sibling of the LSP `DevServer` (shared bind/cleanup, fail-fast on a taken port). It serves static content only today: the `initialize` handshake `instructions` plus a single argument-less `about` prompt. The module is the dependency-quarantine seam: the heavy semantic-layer dependencies (in-process embedder, vector store) land here, off `graphitron-maven-plugin`'s compile surface.

## Architecture spine

### Structured vs semantic — two data shapes, two mechanisms

- *Structured* (catalog tables/columns/FKs, service/condition/record signatures, current schema and classifications, diagnostics, directive grammar) is already held in memory by the dev JVM and exposed as deterministic tools straight over the live Java model. No store, no embedder; it is the freshest and most accurate answer the server can give, and because it is exact the agent cannot be misled by an approximate match.
- *Semantic* (documentation RAG, fuzzy catalog discovery over names + comments) is the only part that needs a vector store and an embedder.

Only two tools in the whole surface are semantic. Everything else ships without the RAG stack.

### One model, two views — this is the LSP integration

The MCP server does not speak the LSP wire protocol, and the agent is not wired as an LSP client. Both would be the wrong altitude: LSP is position/document-keyed (caret-centric), agents are concept-keyed. Instead the MCP server reads the *same live model* the LSP reads, the `Workspace` already held by `DevMojo` (its `CompletionData` catalog, the `LspSchemaSnapshot`, the `SourceWalker.Index`, the `ValidationReport`, the `LspVocabulary`). One source of truth, two thin views: LSP for editors (positional), MCP for agents (semantic). The catalog tools additionally read the raw jOOQ catalog (`JooqCatalog`) for unmediated DB truth. The only new wiring is handing `GraphitronMcpServer` the live `Workspace` handle, refreshed on the existing dev triggers.

### Stability gradient

The corpora differ in how often they change, and that volatility maps onto how each is stored and refreshed:

| Corpus | Stability | Strategy |
|---|---|---|
| Graphitron documentation | Stable; changes only with a graphitron version | Bundled in the jar as a prebuilt index, pre-embedded at build time, loaded at startup |
| Database catalog (jOOQ meta) | Slow; changes when the DB schema is re-captured | Indexed at startup from the project's catalog, persisted by catalog-jar content hash, refreshed on the classpath-change trigger |
| Service / condition / record code | Faster churn | Structured tools always live; optional semantic method index deferred |
| GraphQL schema | Least stable; it is the work itself | Never embedded; served live and structured from the `Workspace`, always fresh |

The gradient is what lets an embedded store work without fighting itself: the most volatile data never enters the store.

## Cross-cutting principles (binding on every slice)

- **Bind sync, warm async, never block the dev loop.** The transport bind stays synchronous (fail-fast on a taken port). The embedder load and index build run on a background daemon thread; `graphitron:dev` reaches its watch loop without waiting. Structured tools are ready the instant the transport binds; semantic tools degrade gracefully ("index warming, use the structured tools meanwhile") until ready, and **a RAG failure never takes down dev** — the server falls back to structured-only.
- **Stable cross-tool node IDs = edges.** Every tool emits and accepts stable identifiers (a schema coordinate `Type.field`, a table name, a method ref `Class.method/arity`). A result names its neighbours by ID; the agent traverses by following IDs through tool calls. This is the whole "graph" mechanism: no graph database, no query language.
- **Store choice is Lucene for V0.** The semantic layer sits behind an `EmbeddingStore` seam; Lucene HNSW is the committed V0 backend (pure Java, no native dependency beyond the embedder, BM25 hybrid search in the same index). DuckDB is deferred to the R117 knowledge-base programme.

## Tool & resource surface

Tools (structured unless marked **semantic**):

- `catalog.tables` — list tables (optional schema/name filter), raw jOOQ
- `catalog.describe` — one table: columns, types, nullability, PK, FKs in/out, raw jOOQ
- `catalog.search` — **semantic** — fuzzy discovery over names + comments
- `services` — `@service`-referenceable classes + method signatures + source locations
- `conditions` — `@condition`-referenceable condition methods (return type `Condition`)
- `records` — consumer record/POJO backings + components/types + source locations
- `schema` — current types/fields, classifications, backing shapes, `@node` metadata, definition locations
- `diagnostics` — current validation errors/warnings (closes the authoring loop)
- `docs.search` — **semantic** — retrieval over the bundled documentation
- *(optional)* method search — **semantic** — per-category fuzzy discovery over external-reference methods

Resources:

- `directives` — directive vocabulary cheat-sheet (arguments, applicable locations, descriptions) from `LspVocabulary` + user-declared directives; static-ish standing context, pinned rather than invoked

## Slices

1. **Shared-model seam (foundational).** Hand the live `Workspace` + jOOQ catalog into `GraphitronMcpServer`, refreshed on the existing dev triggers; declare the `tools` capability. Every structured tool depends on this.
2. **Catalog tools.** `catalog.tables`, `catalog.describe` over raw jOOQ. SQL names for discovery; comments only if jOOQ codegen captured them (degrade to name-only).
3. **Code tools.** `services` / `conditions` / `records` over the `ExternalReference` scan, categorised by return type (`Condition` → condition, record components → record, else service); source locations via the `SourceWalker.Index` join.
4. **Schema tool.** `schema` over the `LspSchemaSnapshot`.
5. **Diagnostics tool.** `diagnostics` over `Workspace.validationReport()`.
6. **Directives resource.** Generated cheat-sheet from `LspVocabulary` (+ snapshot user directives), exposed as an MCP resource.
7. **Cross-reference edges.** Forward edges inline + stable IDs (nearly free); then a reverse-edge index (impact analysis: "what schema fields break if I touch this column/method?") and an optional `neighborhood` subgraph tool as a follow-on, only if round-trip count proves painful.
8. **RAG foundation.** `bge-small-en-v1.5-q` via langchain4j in-process ONNX behind a swappable `EmbeddingModel`; Lucene HNSW behind an `EmbeddingStore` seam (an in-memory store suffices for the small docs corpus). The dependency-quarantine payoff for `graphitron-mcp`.
9. **Docs search.** `docs.search`: chunk the `.adoc` docs at build time (heading-aware structural default; an opt-in `// rag:split` AsciiDoc-comment delimiter overrides where structure is clumsy; prepend each chunk's heading path before embedding), pre-embed, bundle the index in the jar, async-load at startup.
10. **Catalog search.** `catalog.search`: compose a readable per-table descriptor (name + comment + columns), embed, Lucene HNSW, persist keyed by catalog-jar content hash, async-warm and refresh on the debounced classpath trigger decoupled from the LSP catalog swap.
11. *(optional)* **Method search.** Per-category semantic corpus over external-reference methods.

## Local RAG stack (settled decisions)

- **Embedding:** in-process, no API key, no network. `bge-small-en-v1.5-q` (384-dim, MIT) bundled via langchain4j, behind langchain4j's `EmbeddingModel` so the model stays swappable. bge is asymmetric: apply its query-instruction prefix on the query side.
- **The native footprint is the embedder, not the store.** ONNX Runtime (JNI + per-platform natives) is the one heavy dependency; this is exactly why `graphitron-mcp` exists as a quarantine module.
- **Storage:** Lucene HNSW (pure Java, hybrid BM25 + KNN in one index). In-memory store for the docs-only first slice; graduate to Lucene for the large catalog corpus. Both behind the `EmbeddingStore` seam.
- Licensing is permissive throughout (Lucene / langchain4j Apache 2.0; ONNX Runtime / bge MIT), safe to redistribute.

## Transport (settled; the skeleton implements it)

Streamable HTTP (the current spec transport) on a loopback port in the long-running `graphitron:dev` JVM. stdio is unusable here because the host process writes freely to stdout; loopback HTTP matches the LSP's posture. Clients that can only spawn a stdio subprocess are supported through a thin stdio-to-HTTP proxy. A configurable MCP port is deferred (8488 is fixed in the skeleton).

## Open questions for the Spec phase (per slice)

1. *Store backend* beyond V0: Lucene is committed; revisit only if the R117 KB single-store case forces DuckDB.
2. *Multilingual embedding.* Consumer DBs (notably Sikt's own) may carry Norwegian comments; bge-small-en is English-only. Swap to `multilingual-e5-small` if real catalog data needs it (may mean bundling the ONNX ourselves rather than a prebuilt langchain4j module).
3. *Name normalization.* Split snake_case / camelCase and compose readable descriptors before embedding; model-agnostic, lifts catalog retrieval regardless of model choice.
4. *Comment-capture expectation.* Surface to consumer projects that catalog semantic search depends on jOOQ codegen capturing table / column remarks; degrade to name-only otherwise.
5. *Result paging.* Large catalog queries can exceed MCP response limits; pick a default page size and a cursor.
6. *Edge sequencing.* Ship forward edges first; materialise reverse edges / neighborhood only for traversals agents actually walk.

## Out of scope

The R117 DuckDB self-knowledge KB and its cross-dimension joins (a separate programme that can reuse the same embedding interface); service-code and generated-code embedding beyond the optional method-search slice; a hosted or SaaS deployment model (local, in-process is V0); authentication beyond loopback binding and filesystem permissions; any API-key-dependent embedding or generation.

## Related

R117 (knowledge-base programme) is the internal-model counterpart; this MCP programme is its consumer surface and can later expose R117 dimensions (for example a `@capability` lookup tied to R112 and the capability catalog) through the same tool and embedding seams.
