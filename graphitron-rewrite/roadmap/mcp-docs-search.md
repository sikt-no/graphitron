---
id: R385
title: "MCP docs.search: build-time .adoc chunking + pre-embedded bundled index, async-loaded semantic retrieval over the documentation (R118 slice 9)"
status: Backlog
bucket: feature
theme: lsp
depends-on: []
created: 2026-06-26
last-updated: 2026-06-26
---

# MCP docs.search: build-time .adoc chunking + pre-embedded bundled index, async-loaded semantic retrieval over the documentation (R118 slice 9)

Slice 9 of the R118 MCP-server programme: the first of the two **semantic** tools, and the
simplest consumer of the RAG foundation R372 landed. `docs.search` gives an MCP-aware agent
retrieval over the bundled graphitron documentation, so an author who does not know which
directive or pattern solves their problem can ask in natural language and get the relevant
doc passages, rather than needing to already know the vocabulary to grep for it.

It is the simplest RAG consumer because the docs corpus is the **most stable** corpus in R118's
stability gradient: it changes only with a graphitron version, so the index is built and embedded
**at build time** and bundled in the jar as a prebuilt artifact, never re-embedded at runtime. The
server async-loads it at startup (R372's warm lifecycle) and falls back to structured-only if the
load fails. This is also why it is the natural *first* RAG consumer: it exercises R372's `Embedder`
+ `EmbeddingStore` seams end-to-end against a fixed, prebuilt index, with none of slice 10's
runtime-refresh / content-hash-persistence complexity.

## What it delivers

- **Build-time chunking of the `.adoc` docs.** Heading-aware structural chunking is the default
  (split on section boundaries); an opt-in `// rag:split` AsciiDoc-comment delimiter overrides where
  the structure is clumsy. Each chunk's heading path is prepended before embedding so a chunk keeps
  its context ("Pagination > Keyset seek > ..." rather than an orphaned paragraph).
- **Pre-embedded, bundled index.** The chunks are embedded at build time via R372's document-side
  embedding (`embedDocuments`, no query prefix) and the resulting index is packaged in the jar.
- **`docs.search` tool.** Takes a natural-language query, embeds it query-side (R372's `embedQuery`
  applies the bge instruction prefix), runs hybrid retrieval through the `EmbeddingStore` seam, and
  returns ranked passages with their heading path and source location. Per R118, the docs-only
  corpus is small enough that an in-memory store suffices, but it goes through the same seam so the
  backend stays invisible.

## Design questions for the Spec phase

- **Where build-time chunking + embedding runs.** Which module/build step produces the bundled
  index (a `graphitron-mcp` build-time generator vs a docs-module step), and how it stays in sync
  with the `.adoc` sources the docs site already renders (`/docs/`, `/graphitron-rewrite/docs/`).
  The index must rebuild when docs change, without paying JRuby/AsciiDoctor cost on every dev build.
- **Chunk granularity + heading-path prefix format.** Confirm the structural-default boundary
  (section vs sub-section) and the exact prepended heading-path string, since it materially affects
  retrieval quality (R118 OQ / name-normalization sibling concern).
- **Which docs are in scope.** The public site sources plus the rewrite-internal docs, or a curated
  subset; oversized or non-authoritative pages (audits, changelog) likely excluded.
- **Result shape.** Passage text + heading path + source path/anchor, and whether to return a
  deep-link into the rendered site (`graphitron.sikt.no`) alongside the raw chunk.

## Builds on

- **R372** (Done): the RAG foundation; `docs.search` reads R372's `Embedder` (query/document split)
  and `EmbeddingStore` seams and rides its async-warm / fall-back-to-structured lifecycle. Landed,
  so a *Builds on*, not a `depends-on`.
- **R341 / R361** (Done): the `graphitron-mcp` module, the `tools` capability, and the async-warm
  posture the dev loop already tolerates.

## Related

- **R118** (`graphitron-mcp-server.md`, Backlog programme): slice 9, the docs-retrieval semantic
  tool.
- **R386** (`mcp-catalog-search.md`): sibling slice 10, the *other* semantic tool. Both consume
  R372's seams, but R386 carries the runtime-refresh / content-hash-persistence complexity this
  slice deliberately avoids; filed separately for exactly that reason.
