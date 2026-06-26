---
id: R386
title: "MCP catalog.search: semantic fuzzy discovery over CatalogFacts descriptors, content-hash-persisted Lucene index refreshed on the classpath trigger (R118 slice 10)"
status: Backlog
bucket: feature
theme: lsp
depends-on: []
created: 2026-06-26
last-updated: 2026-06-26
---

# MCP catalog.search: semantic fuzzy discovery over CatalogFacts descriptors, content-hash-persisted Lucene index refreshed on the classpath trigger (R118 slice 10)

Slice 10 of the R118 MCP-server programme: the second **semantic** tool, and the one that
directly serves the programme's foundational greenfield-onboarding use case. `catalog.search`
gives an MCP-aware agent **fuzzy discovery over the database catalog** by names and comments,
so a developer pointing graphitron at a large existing database with an empty schema can ask
"where are customer addresses stored?" and find the relevant tables without knowing their exact
SQL names. It is the semantic counterpart to slice 2's exact `catalog.tables` / `catalog.describe`
(R362): structured tools answer "describe this table I named", `catalog.search` answers "find the
table I can only describe".

Unlike the docs corpus (slice 9, the *stable* corpus bundled at build time), the catalog is the
**slow** corpus in R118's stability gradient: it changes when the DB schema is re-captured. So
this slice carries the runtime-refresh machinery slice 9 deliberately avoids, which is why the two
were filed as separate items.

## What it delivers

- **A readable per-table descriptor, embedded.** Compose each table's `CatalogFacts` (R362) into a
  readable descriptor (name + comment + column names/comments), embedded via R372's document-side
  embedding. Name normalization (split snake_case / camelCase into words before embedding; R118 OQ3)
  lifts retrieval regardless of model and is this slice's to apply.
- **A content-hash-persisted Lucene index.** The index is persisted keyed by the **catalog-jar
  content hash**, so it is rebuilt only when the catalog actually changes, and reloaded from disk
  otherwise. This is the large-corpus case R372's Lucene `EmbeddingStore` was committed for (the
  docs slice's in-memory store does not scale here).
- **`catalog.search` tool.** Natural-language query embedded query-side (R372's `embedQuery`),
  hybrid retrieval through the `EmbeddingStore` seam, returns ranked tables by **stable ID** (the
  schema-qualified SQL name R362's `CatalogFacts` is keyed by), so a hit feeds straight into
  `catalog.describe` and the slice-7 (R374) edges. Discovery (semantic) hands off to description
  (exact) by ID.

## Design questions for the Spec phase

- **Refresh decoupled from the LSP catalog swap.** R118 is explicit: async-warm and refresh on the
  **debounced classpath-change trigger, decoupled from the LSP catalog swap**, so re-embedding a
  large catalog never stalls the LSP's own catalog refresh. Settle where the content-hash check and
  the background re-embed live relative to R361/R368's build-output swap.
- **Comment-capture expectation (R118 OQ4).** Semantic catalog search is far weaker without table /
  column remarks, and those exist only if the consumer's jOOQ codegen captured them. Surface this
  expectation to consumer projects and degrade to name-only (still useful via name normalization)
  when comments are absent.
- **Descriptor composition (R118 OQ3).** The exact readable-descriptor format and the
  snake_case/camelCase splitting, shared in spirit with slice 9's heading-path prefixing; both are
  "compose readable text before embedding" and may share a helper.
- **Multilingual (R118 OQ2), inherited from R372.** Norwegian catalog comments (notably Sikt's own)
  are the concrete trigger for R372's documented `multilingual-e5-small` swap; this is the slice
  whose real data may force that decision. Flag it; the swap itself stays seam-local in R372.
- **Persistence location + cache invalidation.** Where the per-hash Lucene index is written
  (build/target vs a cache dir) and how stale indices are reaped.

## Builds on

- **R372** (Done): the RAG foundation; `catalog.search` reads R372's `Embedder` and the **Lucene**
  `EmbeddingStore` (the large-corpus backend it was committed for) and rides its async-warm
  lifecycle. Landed, so a *Builds on*, not a `depends-on`.
- **R362** (Done): catalog tools; `catalog.search` embeds R362's build-time `CatalogFacts`
  descriptor and returns the same schema-qualified-SQL-name IDs `catalog.describe` accepts.
- **R341 / R361** (Done): the `graphitron-mcp` module and the shared-model seam / dev-trigger
  refresh path the re-embed hooks into.

## Related

- **R118** (`graphitron-mcp-server.md`, Backlog programme): slice 10, the catalog-discovery semantic
  tool and the foundational greenfield-onboarding deliverable.
- **R385** (`mcp-docs-search.md`): sibling slice 9. Both consume R372's seams and share the
  "compose readable text before embedding" concern; this slice adds the runtime-refresh,
  content-hash-persistence, and Lucene-scale machinery slice 9 does not need.
- **R374** (Done, slice 7): the cross-reference edges; a `catalog.search` hit's table ID feeds the
  edge traversal, semantic discovery into structured navigation.
- Optional **slice 11** (method search): if filed, it is the same pattern as this slice applied to
  the external-reference method corpus, and would share this slice's descriptor/refresh approach.
