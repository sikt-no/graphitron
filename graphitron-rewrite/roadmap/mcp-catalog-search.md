---
id: R386
title: "MCP catalog.search: semantic fuzzy discovery over CatalogFacts descriptors, content-hash-persisted Lucene index refreshed on the classpath trigger (R118 slice 10)"
status: Ready
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

## Design decisions

### Refresh: lazy self-observe, no new dev trigger

Mirror `ReverseEdgeIndex.Cache` (R374). The semantic index is a pure derived function of
`Workspace.catalogFacts()`, so it needs no `BuildArtifacts` component, no `Workspace` field, and no
new `DevMojo` listener. Two gates:

1. **Reference-identity fast path.** Each `catalog.search` call reads the `volatile`
   `Workspace.catalogFacts()`. If the reference is the same one the live index was built from, serve
   immediately. This is the cheap gate the `Cache` already uses; a no-op recompile that swaps the
   reference but not the content falls through to gate 2, everything else short-circuits here.
2. **Content-hash gate.** On a changed reference, compose the descriptor corpus (cheap string work)
   and hash it. If the hash equals the live index's hash, the catalog content is unchanged: update
   the held reference and serve the existing index. Only a changed hash kicks a re-embed.

The re-embed runs on R372's `AsyncWarm<EmbeddingStore>` background daemon, off the classpath-watcher
thread, so `setBuildOutput`'s catalog swap is never stalled by re-embedding (the R118 decoupling
requirement). The prior `Ready` index keeps serving while the new one builds; on completion the
index + hash + held reference swap atomically. A content change therefore re-enters
`WarmState.Warming` (reusing the existing sealed shape and `degradationMessage`, no new "refreshing"
state); the first search during a rebuild gets the warming degradation message.

Rejected: an eager notify from `setBuildOutput`. It would add a second consumer to the LSP
catalog-swap event (the coupling R118/R361 explicitly forbade) and pay the re-embed cost even when
no agent ever searches.

### Invalidation key: hash of the composed descriptor corpus

The roadmap framed this as a "catalog-jar content hash"; the precise operational key is a hash of
**the exact descriptor strings passed to `embedDocuments`**, not the jar bytes. `CatalogFacts` is a
deterministic projection and the descriptors are what get embedded, so hashing them means two jars
that yield identical facts share an index and an unrelated recompile is a no-op, both of which
jar-byte-hashing gets wrong. The hash is computed over the composer's output (the same artifact
handed to the embedder), never over `CatalogFacts` fields directly, so the hashed thing and the
embedded thing cannot drift when the descriptor format or name normalization changes.

### Validity = corpus hash + embedder identity (close the cross-model trap now)

The persisted index is valid only for the embedder that built it, and bge-small-en plus the deferred
`multilingual-e5-small` swap (R118 OQ2) are **both 384-dim**, so dimension alone cannot tell them
apart: loading one model's index under another is a silent correctness trap. Rather than weaken this
to a "remember to `mvn clean`" prose precondition (which fail-loud and the live-tests-only principle
reject), R386 owns the persisted index and closes the trap in R386: write an **embedder-identity
manifest** beside each index (`embedder.getClass().getName()` + `dimension()`), and the loader
rejects (falls through to rebuild) any index whose recorded identity differs from the live embedder.
This needs no change to R372's frozen `Embedder` seam; the eventual `Embedder.modelId()` refinement
is bundled with the OQ2 swap (the class name is a coarse-but-honest proxy until then).

### Persistence location + reaping

`${project.build.directory}/graphitron-mcp-rag/catalog/<corpusHash>/` (a Lucene `FSDirectory`). It
survives `dev` restarts (the point: avoid re-embedding a large catalog on every restart) and dies on
`mvn clean` (a legitimate rebuild-everything). On a successful warm, reap sibling hash dirs keeping
the current plus one prior (so flipping between two schema states does not re-embed each time). The
cache dir is dev-environment glue, the same kind of thing as the loopback bind address, not
`Workspace` content; it enters through a small `RagConfig(Path cacheDir, ...)` record passed as a
third `GraphitronMcpServer` constructor argument (a back-compat overload defaults it to a temp dir
for the existing tests/callers), with `DevMojo` supplying `${project.build.directory}/graphitron-mcp-rag`.
The record (rather than a third positional `Path`) anticipates slice-11 / OQ2 knobs without
regrowing the signature.

### Descriptor composition + name normalization (R118 OQ3)

One composer function turns a `CatalogFacts.Table` into one readable descriptor string. It carries
both the raw SQL tokens (so BM25 still matches `film_actor` exactly) and the normalized words (so the
embedder sees readable language):

----
Table film_actor (film actor)
Comment: <table comment, omitted when absent>
Columns: actor_id (actor id), film_id (film id), last_update (last update)
----

Name normalization splits snake_case and camelCase into lowercased words (`film_actor` ->
"film actor", `lastUpdate` -> "last update", `customerID` -> "customer id"). It applies to the table
name and every column name and is the model-agnostic retrieval lift R118 OQ3 calls for. When jOOQ
captured no comments the comment line and per-column comment parentheticals are omitted; the
descriptor degrades to names-only, still useful via normalization (OQ4). The splitter is defined in
R386; if R385 lands a sibling "compose readable text before embedding" helper, factor the splitter
into the shared `mcp.rag` helper then.

### Comment-capture expectation (R118 OQ4)

Semantic catalog search is materially weaker without table/column remarks, which exist only if the
consumer's jOOQ codegen captured them. Surface this in the `catalog.search` tool description and a
`getting-started.adoc` note (enable jOOQ's comment generation); degrade silently to name-only
otherwise. No new diagnostic surface in V0.

### Multilingual (R118 OQ2): flagged, not done

Norwegian catalog comments (notably Sikt's own) are the concrete trigger for R372's documented
`multilingual-e5-small` swap. R386 ships the inherited English bge V0 and leaves the swap seam-local
in R372; the embedder-identity manifest above ensures a later swap invalidates the persisted English
index loudly rather than silently. Flag only.

### Tool surface (minimal)

`catalog.search` takes `query` (required string) and `limit` (optional int, default 10). No schema
filter, no cursor paging: semantic discovery is top-k "find the table I can't name", while
schema-scoping and exhaustive enumeration are the structured `catalog.tables` / `catalog.describe`
tools' job. Each hit returns `{id: "<schema>.<table>", schema, name, comment?, score}`, where `id` is
the same `McpWire` `schema.table` stable grammar `catalog.describe` and the R374 edges already accept
(discovery hands off to description by ID). `score` is the fused RRF score `EmbeddingStore.Hit`
already carries, surfaced verbatim (the fusion strategy stays a backend detail). When the index is
warming or failed, return `WarmState.degradationMessage` as text plus `{status: "warming"|"failed"}`
structured content.

## Implementation

All new code is module-local to `graphitron-mcp`; no change to R372's `rag` seams, `CatalogFacts`, or
`Workspace`.

- **`mcp/rag/CatalogDescriptors.java` (new).** Pure functions: `descriptor(CatalogFacts.Table)
  -> String` (the composition above) and `splitWords(String) -> String` (snake_case / camelCase
  normalization). Stateless, unit-testable without ONNX.
- **`mcp/rag/RagConfig.java` (new).** `record RagConfig(Path cacheDir)`, room to grow.
- **`mcp/rag/CatalogSearchIndex.java` (new).** Owns the warm-managed, self-observing index. Holds the
  shared `AsyncWarm<Embedder>`, the current `EmbeddingStore` + its corpus hash + the `CatalogFacts`
  reference it was built from, and the `RagConfig` cache dir. `search(String query, int limit)`
  applies the two-gate check against `workspace.catalogFacts()`, kicks an `AsyncWarm<EmbeddingStore>`
  re-embed on a changed hash, and returns hits or a `WarmState` degradation. The re-embed callable:
  `await()` the embedder warm (mapping `Failed` -> `Failed`), compose descriptors, hash,
  `load`-or-`building` the `FSDirectory` for `<hash>/` (rejecting on embedder-identity-manifest
  mismatch), `embedDocuments` + `add` when building, write the manifest, reap stale sibling dirs.
  Single-flight: one re-embed in flight; on completion, if the live hash moved again, re-kick.
- **`GraphitronMcpServer`.** Widen the constructor to `(InetSocketAddress, Workspace, RagConfig)` with
  a back-compat `(InetSocketAddress, Workspace)` overload defaulting `RagConfig` to a temp dir (keeps
  existing tests/callers compiling). Construct a `CatalogSearchIndex`, start its embedder + initial
  index warm at build time (R118 bind-sync / warm-async), and register
  `catalogSearchTool(workspace, catalogSearchIndex)` alongside the existing tools. `close()` closes
  the index (closes the live `EmbeddingStore`). A test-seam constructor injects a fake `Embedder` so
  the handler tier runs without ONNX.
- **`McpWire`.** Reuse `stringArg` / `intArg`. The `schema.table` id is already the `CatalogFacts`
  key; no new grammar (split a qualified id back into `{schema, name}` for the result shape with a
  tiny helper if one isn't already present).
- **`DevMojo.bindServer`.** Pass `new RagConfig(<build-dir>/graphitron-mcp-rag)` to the widened
  constructor.

## Tests

Per the design-principles test tiers; the RAG seams already ship a `FakeEmbedder` seam fake and
RAM / `FSDirectory` stores, so the new invariants pin deterministically without ONNX.

- **Unit (fast, no ONNX), the three silent-staleness / lifecycle invariants:**
  - `CatalogDescriptorsTest`: snake_case / camelCase / acronym / single-word splitting; comment
    present vs absent (name-only degradation); raw + normalized tokens both present.
  - `CatalogSearchIndexTest` over `FakeEmbedder` + `LuceneEmbeddingStore.inMemory` / `FSDirectory`:
    - *Hash covers the embedded corpus:* a changed column / comment changes the hash; recomposing the
      same facts yields the same hash; the hash is computed over the exact strings passed to
      `embedDocuments`.
    - *Warming-on-change re-entry:* a changed-hash observation drops to `Warming` and serves
      `degradationMessage`; the prior `Ready` index keeps serving until swap; a same-hash reference
      change is a no-op (no re-embed, asserted via an embedder call-count spy); a same-reference call
      short-circuits.
    - *Embedder-identity rejection:* an index persisted under one embedder identity is rejected
      (rebuilt) when loaded under a different identity; accepted when identical.
    - Persistence round-trip: `building` -> close -> `load` returns the same hits without re-embedding
      (call-count spy); sibling-dir reaping keeps current + one prior.
    - Cross-warm failure propagation: a `Failed` embedder warm yields a `Failed` index (reuses R372's
      `await()` contract).
- **MCP handler tier (`GraphitronMcpServerTest`):** drive the real loopback server with the SDK client
  through the test constructor that injects a `FakeEmbedder` + tiny `CatalogFacts`. Assert
  `catalog.search` is advertised in `tools/list`; a ready-arm call returns ranked `schema.table` ids
  in `structuredContent` whose top id feeds a follow-on `catalog.describe`; the warming-arm call
  (search before warm completes) returns the degradation message + `status: warming`.
  Structured-content assertions only.
- **Infrastructure tier (`@Tag("slow")`, real bge ONNX), `CatalogSearchOnnxTest`:** embed the real
  Sakila `CatalogFacts`, query "where are customer addresses stored?" and "movie rental payments",
  assert the expected tables (`public.address`, `public.payment`) rank in the top-k. The
  retrieval-quality pin and the name-normalization payoff demonstration; mirrors R372's
  `BgeEmbedderOnnxTest`, runs in CI under `--enable-native-access`.

## User documentation (first-client check)

`catalog.search` is a new agent-facing tool, so the user docs are the first client of the design. In
`getting-started.adoc`'s MCP tools subsection, add (final home; no roadmap-internal markers in the
rendered prose):

[quote]
____
**`catalog.search`**, semantic, fuzzy discovery over your database catalog. Ask in natural language
("where are customer addresses stored?") and get back the most relevant tables ranked by similarity,
by their schema-qualified SQL name. Feed a hit straight into `catalog.describe` for the full
column / key / FK detail. The index warms in the background when `graphitron:dev` starts and
refreshes after a schema change; the first search during a refresh reports that it is warming and
points you at the structured `catalog.*` tools meanwhile.

Semantic search is much stronger when your jOOQ-generated catalog carries table and column comments.
Enable comment generation in your jOOQ codegen configuration so the database's own documentation
feeds the search; without comments, search still works on table and column names alone.
____

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
