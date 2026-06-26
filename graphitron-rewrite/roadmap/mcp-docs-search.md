---
id: R385
title: "MCP docs.search: build-time .adoc chunking + pre-embedded bundled index, async-loaded semantic retrieval over the documentation (R118 slice 9)"
status: In Review
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
load fails. R372 shipped the `Embedder` / `EmbeddingStore` / `AsyncWarm` / `WarmState` seams but
wired none of them into `GraphitronMcpServer`; **this slice is therefore also the first to wire the
async-warm lifecycle into the server and `DevMojo`**, end-to-end against a fixed prebuilt index, with
none of slice 10's runtime-refresh / content-hash-persistence complexity.

## What it delivers

- **Build-time chunking of the `.adoc` docs.** Heading-aware structural chunking is the default
  (split on section boundaries, read straight from the raw `.adoc` heading syntax, no AsciiDoctor
  render); an opt-in `// rag:split` AsciiDoc-comment delimiter overrides where the structure is
  clumsy. Each chunk's heading path is prepended before embedding so a chunk keeps its context
  ("Pagination > Keyset seek > ..." rather than an orphaned paragraph).
- **Pre-embedded, bundled chunk set.** The chunks are embedded at build time via R372's document-side
  embedding (`embedDocuments`, no query prefix) and the resulting `(id, heading-path-prefixed text,
  payload, vector)` tuples are packaged in the jar as a bundled resource (see *Bundle format* for why
  tuples, not a literal Lucene directory).
- **`docs.search` tool.** Takes a natural-language query, embeds it query-side (R372's `embedQuery`
  applies the bge instruction prefix), runs hybrid retrieval through the `EmbeddingStore` seam, and
  returns ranked passages with their heading path, source location, and a deep-link into the rendered
  site. Per R118 the docs-only corpus is small enough that the in-memory store suffices, but it goes
  through the same seam so the backend stays invisible.

## Implementation

Flat file-by-file; nothing here gates the next compile, so no step numbering.

### Chunker (`graphitron-mcp`, `mcp/rag/docs/`)

- `AdocChunker`: pure function `String adoc, String sourcePath -> List<DocChunk>`. Parses the raw
  `.adoc` text line by line, tracking the heading stack from the `=` / `==` / `===` / ... level
  markers. Default boundary is the section heading; a chunk is the heading line plus its body up to
  the next heading at the same-or-shallower level. An `// rag:split` line forces an extra boundary
  within a section. No JRuby, no AsciiDoctor: this reads source syntax, not rendered HTML, which is
  what keeps it off the docs module's render cost.
- `DocChunk` record: `headingPath` (the ordered ancestor headings), `sourcePath` (repo-relative
  `.adoc` path), `anchor` (the section's id/slug for deep-linking), `text` (the chunk body). The
  embed text is `headingPath joined + "\n" + text`; the BM25/display text is the same, so lexical and
  semantic halves see one string (the R372 `Embedding` record bundles them, so they cannot drift).

### Build-time index generator (`graphitron-mcp`)

- `DocsIndexBuilder` with a `main`: read every in-scope `.adoc` under the configured docs root, chunk
  each, embed all chunks via `BgeEmbedder.embedDocuments`, and write the bundled resource described
  below to `${project.build.outputDirectory}/mcp/docs-index/` so it is packaged in the jar.
- Bound via `exec-maven-plugin` (`java` goal) at the `process-classes` phase (after the module's own
  classes compile, before `package`), so the generator runs against compiled `BgeEmbedder` + chunker.
- **Re-embed gate.** Compute a content hash over the in-scope `.adoc` and write it as a stamp beside
  the bundle; skip the embed when the stamp matches, so a plain `mvn install` inner loop does not pay
  the ONNX cost when docs are unchanged. This is a *build-plugin-local* up-to-date check and shares no
  code with slice 10's *runtime* content-hash-persistence (catalog-jar hash, re-embed on the classpath
  trigger); the two live on opposite sides of the build/runtime boundary and are deliberately kept
  separate so a future reader does not try to unify them.

### Bundle format (divergence from "bundle the Lucene index", settled in Spec)

The Backlog said "bundle the index"; a literal Lucene `FSDirectory` cannot be read from inside a jar,
so bundling one would force temp-dir extraction at warm. Instead bundle the **pre-embedded tuples**:
a small header carrying the embedder `dimension` and a per-chunk stream of `(id, embedText, payloadJson,
vector[384])`. `id` is `sourcePath#anchor` (stable, deep-linkable, the same shape the result returns).
The docs-store warm loader rebuilds an `LuceneEmbeddingStore.inMemory(dimension)` by re-`add()`-ing the
precomputed tuples. This lands on a first-class R372 seam factory (`inMemory` is documented as real
Lucene HNSW, not a drifting fake), re-embeds nothing at runtime (vectors are build-time), and needs no
temp files. The one warm cost it does pay is the HNSW graph rebuild over the (tiny) chunk set; that is
the real docs warm cost and is named here so it is not mistaken for a zero-cost mmap.

### Warm wiring into the server + `DevMojo`

- A shared `AsyncWarm<Embedder>` loading `new BgeEmbedder()` (heavy, off the dev thread), constructed
  and `start()`-ed once and shared with slice 10 later.
- An `AsyncWarm<EmbeddingStore>` for docs whose loader reads the bundled tuples and rebuilds the
  in-memory store. It **does not await the embedder** (it loads a prebuilt set; the `dimension` comes
  from the bundle header, so the store is sized without the embedder, exactly the embedder-free load
  path `AsyncWarm` documents). The embedder is needed only at query time.
- Both warms are owned where the server is (constructed in `GraphitronMcpServer` or handed in from
  `DevMojo`, mirroring how the live `Workspace` is threaded); a RAG warm failure leaves dev
  structured-only and never blocks the bind, per the R118 cross-cutting principle.

### `docs.search` tool (`GraphitronMcpServer` + a `DocsSearchTool` handler)

- Registered alongside the existing tools in the `.tools(...)` list. Input schema: `{ query: string
  (required), k?: integer (default 5) }`.
- Handler: if either warm is not `Ready`, return `WarmState.degradationMessage(...)` (the shared
  R372 wording) and no hits. When both are `Ready`, embed the query (`embedder.embedQuery`), run
  `store.search(query, k)`, and map each `Hit` to a result entry.
- **Dimension source-of-truth guard.** When both warms first reach `Ready`, assert
  `embedder.dimension() == bundledDimension` once; on mismatch, degrade with a clear message rather
  than letting a build/runtime model-version skew surface as an opaque Lucene KNN width error. The
  embedder's `dimension()` stays the source of truth; the bundled value is a build-time copy that this
  guard reconciles.
- **Result shape** (structured content + a text summary, mirroring the other tools): a `passages`
  array, each with `headingPath`, `sourcePath`, `anchor`, `text`, `score`, and `url` (the deep-link
  into the rendered site, `https://graphitron.sikt.no/...` derived from `sourcePath` + `anchor`). The
  text summary names the top hit's heading path so a non-structured client still gets a usable answer.

## Design decisions settled in Spec

- **Corpus scope: the public manual only.** Embed the public site manual under repo-root `/docs/`
  (tutorial, explanation, reference, directives), which is the authoring surface `docs.search` exists
  to serve ("which directive or pattern solves my problem"). **Exclude** the rewrite-internal
  `/graphitron-rewrite/docs/` design docs (contributor surface: "why is the classifier shaped this
  way"), and exclude `roadmap/`, `audits/`, and `changelog.md` (non-authoritative, process-internal).
  This narrows R118 slice 9's unqualified "the bundled documentation" deliberately; the narrowing is
  justified by the authoring-vs-contributor split and reinforced by the deep-link, which only resolves
  for the rendered public manual.
- **Bundle tuples, not a Lucene `FSDirectory`** (rationale above).
- **In-memory store** per the programme's stated "an in-memory store suffices" for the docs corpus.

## Where the build-time embed runs (settled at Spec → Ready sign-off: Option A)

The reviewer confirmed **Option A**: chunk + embed inside `graphitron-mcp`. The deciding principle is
the dependency quarantine the module's own pom documents holding (ONNX Runtime + Lucene on
`graphitron-mcp` alone); Option B would re-spread the heavy ONNX dependency onto a second module's
build classpath, which is exactly what the quarantine exists to prevent. The cross-directory read of
`/docs/` is a build-time-only input, mitigated by the declared `<docs.source.dir>` property and the
content-hash stamp, not a runtime edge. The two shapes are kept below for the implementation record.

The decisive trade-off is the dependency quarantine `graphitron-mcp` exists for. Two shapes:

- **Option A (recommended): chunk + embed inside `graphitron-mcp`, reading `/docs/` by a declared
  docs-root property.** Keeps the heavy ONNX embedder in its one quarantine module. Cost: the build
  reaches across the reactor's directory boundary into the sibling `/docs/` tree, and the reactor
  dependency graph does not encode "the index depends on `/docs/` content". Mitigation: express the
  docs root as a declared `<docs.source.dir>` build property (not a buried `../docs` literal) and let
  the content-hash stamp track recompute. Recommended because keeping ONNX in a single module is the
  stronger principle, and the cross-directory read is a build-time-only input, not a runtime edge.
- **Option B: a docs-module goal (or a dedicated build-only module) owns the `.adoc`→tuples transform
  and publishes the bundle as an artifact `graphitron-mcp` depends on.** Encodes the dependency as a
  real reactor edge. Cost: the ONNX embedder reaches a second module's *build* classpath, re-spreading
  the heavy dependency the quarantine is meant to contain.

Two implementation details the reviewer flagged as non-blocking, for the implementer to settle in
flight: (1) the corpus root resolves to `docs/manual/**` (the tutorial / explanation / reference /
directives subtrees named above); whether the top-level `docs/*.adoc` pages (quick-start, faq) join
the corpus is an open low-stakes call, default to the manual subtree unless they prove useful. (2) The
`sourcePath` + `anchor` → `https://graphitron.sikt.no/...` URL transform is not pinned here; derive it
from the rendered site's path layout when wiring the result shape.

## Tests

- **Unit (chunker), `graphitron-mcp`:** heading-aware boundaries from a fixture `.adoc` (nested
  sections produce the expected heading paths); a `// rag:split` line produces an extra chunk boundary
  within a section (pins the override actually fires); a malformed-looking `rag:`-prefixed comment does
  not silently masquerade as a split (decide warn-vs-ignore here and pin the chosen behaviour).
- **Unit (bundle round-trip):** write tuples → read tuples → assert chunk count, ids, dimension, and
  vector widths survive.
- **Pipeline-tier (production warm path):** bundle resource → `LuceneEmbeddingStore.inMemory` rebuild
  via the real docs-store loader → `search` returns a known passage for a known query. This exercises
  the *production* loader end-to-end rather than implicitly through the seam's unit-test fake.
- **MCP-handler-tier, `GraphitronMcpServerTest`:** drive a real loopback server. Assert (a)
  warming/failed warm → `docs.search` returns the degradation message and no hits; (b) ready warm →
  structured `passages` with `headingPath` / `sourcePath` / `url` / `score`; (c) the dimension-guard
  mismatch path degrades rather than throwing. Structured-content assertions only, no code-string
  assertions (matches the slice-2..7 test posture).
- The build-time ONNX embed itself stays out of the fast suite; the embedder load is already covered
  by R372's `@Tag("slow")` `BgeEmbedderOnnxTest`, and the handler tests use a fake/precomputed bundle.

## User documentation (first-client check)

`docs.search` is an agent-facing tool, surfaced in the MCP client's tool list, so the user-visible
surface is the `getting-started.adoc` MCP subsection R341 introduced: add a short line that
`docs.search` answers natural-language questions over the manual and degrades to the structured tools
while the index warms. No new directive or output format, so the footprint is one paragraph; if it
does not read simply, the result shape is wrong.

## Builds on

- **R372** (Done): the RAG foundation; `docs.search` reads R372's `Embedder` (query/document split)
  and `EmbeddingStore` seams and rides its async-warm / fall-back-to-structured lifecycle. Landed,
  so a *Builds on*, not a `depends-on`. This slice is the first to wire those seams into the server.
- **R341 / R361** (Done): the `graphitron-mcp` module, the live-`Workspace` seam, the `tools`
  capability, and the async-warm posture the dev loop already tolerates.

## Related

- **R118** (`graphitron-mcp-server.md`, Backlog programme): slice 9, the docs-retrieval semantic
  tool.
- **R386** (`mcp-catalog-search.md`): sibling slice 10, the *other* semantic tool. Both consume
  R372's seams and share the "compose readable text before embedding" concern; R386 carries the
  runtime-refresh / content-hash-persistence / Lucene-scale machinery this slice deliberately avoids,
  and reuses the shared `AsyncWarm<Embedder>` this slice stands up.
