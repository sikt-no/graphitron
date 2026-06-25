---
id: R372
title: "MCP RAG foundation: in-process ONNX embedder behind a graphitron Embedder seam + Lucene HNSW behind EmbeddingStore (R118 slice 8)"
status: Spec
bucket: feature
theme: lsp
depends-on: []
created: 2026-06-25
last-updated: 2026-06-25
---

# MCP RAG foundation: in-process ONNX embedder behind a graphitron Embedder seam + Lucene HNSW behind EmbeddingStore (R118 slice 8)

Slice 8 of the R118 MCP-server programme: the semantic-layer foundation that the two
**semantic** tools (slice 9 `docs.search`, slice 10 `catalog.search`) sit on. It ships no
agent-facing tool itself; it stands up the embedder, the vector-store seam, and the async-warm
lifecycle, and it is the slice that realises `graphitron-mcp`'s reason for existing as a separate
module, quarantining the heavy native dependency off `graphitron-maven-plugin`'s compile surface.
Unlike the structured slices (R362, R368), it touches neither the live `Workspace` nor the LSP
model, so it is **independent of the structured-tool track and can be implemented in parallel with
all of it**, including before slices 3-7 land.

## What it delivers

Three seams and one lifecycle, all in `graphitron-mcp`, with no consumer-facing tool:

- A graphitron-owned **`Embedder`** seam (D1) with a bge-backed implementation, English-only V0.
- An **`EmbeddingStore`** seam with Lucene HNSW as the sole shipping backend (D2).
- A sealed **`WarmState`** lifecycle + a generic async-warm harness + the shared degradation helper
  that slices 9/10 consume (D3).

## D1 resolved: a graphitron-owned `Embedder` seam, not langchain4j's `EmbeddingModel` directly

The Backlog framing said "behind langchain4j's `EmbeddingModel`" *and* "the seam must distinguish
query-embedding from document-embedding". Those cannot both be literally true: langchain4j's
`EmbeddingModel` is a single-`embed(text)` surface and carries no query/document axis, but bge is
asymmetric, its query-instruction prefix is applied on the query side only. This Spec resolves the
fork by owning a thin graphitron seam:

```
record Embedding(String text, float[] vector) {}  // a document: its BM25 text + KNN vector, produced only here
record Query(String text, float[] vector) {}       // a query:    its BM25 text + KNN vector, produced only here

interface Embedder {
    Query embedQuery(String text);                      // applies the bge query-instruction prefix (to the vector only)
    List<Embedding> embedDocuments(List<String> texts); // no prefix
    int dimension();
}
```

- **The asymmetry lives in the seam, not in the caller.** `embedQuery` prepends bge's instruction
  prefix internally; `embedDocuments` does not. Consumers never know bge is asymmetric, they just
  ask for a query or a document embedding. This is the "if two consumers evaluate the same predicate
  over a field, the branch belongs in the model" rule: the query/document branch belongs in the
  embedder, not re-derived in slices 9 and 10.
- **A raw `float[]` never crosses the store seam, and a vector never travels apart from the text it
  was embedded from.** `embedQuery` / `embedDocuments` return `Query` / `Embedding` records, the only
  producers of either; both bundle the BM25 text with its KNN vector so a hybrid search cannot fuse a
  vector from one string against BM25 text from another. The store consumes these records (D2), so the
  vector-and-its-text pairing is carried by the type, not re-asserted by each of slices 9 and 10. The
  records are graphitron's own (a `float[]` plus its source text); they name no langchain4j type.
- **The swap guarantee attaches to *this* wrapper, not to langchain4j.** R118 OQ2's multilingual
  swap (to `multilingual-e5-small`) stays cheap because the stable contract is `Embedder`. A model
  with no query/document asymmetry is absorbed by making its adapter's `embedQuery` delegate to the
  same path as `embedDocuments` with no prefix; a model that puts the asymmetry elsewhere is still
  hidden behind the two methods. langchain4j's `EmbeddingModel` becomes an implementation detail of
  the bge adapter, not the published seam.
- **Separate business logic from API code.** The bge instruction-prefix knowledge is graphitron's
  business logic; keeping it inside the `Embedder` adapter rather than leaking it to every call site
  is the same axis the `graphitron-lsp` / `graphitron-mcp` splits already serve.

**Multilingual (R118 OQ2), decided for V0:** ship English-only `bge-small-en-v1.5-q` (384-dim,
MIT). The swap to `multilingual-e5-small` is documented as a cheap, seam-local change deferred until
real Norwegian catalog data (notably Sikt's own) demands it; it may mean bundling the ONNX ourselves
rather than consuming a prebuilt langchain4j module, which is a slice-of-its-own cost, not V0's.

## D2 resolved: Lucene HNSW is the only shipping store; in-memory is a test double

R118's cross-cutting principle commits Lucene as the V0 backend, while both R118 slice 8 and the
Backlog body hedged toward an in-memory "first cut". This Spec removes the tension by reading the
hedge as a **test double, not a shipping backend**:

- **One shipping backend.** The `EmbeddingStore` seam has exactly one production implementation,
  Lucene HNSW (pure Java, hybrid BM25 + KNN in a single index). The docs corpus (slice 9) loads a
  prebuilt Lucene index bundled in the jar; the catalog corpus (slice 10) persists a Lucene index
  keyed by catalog-jar content hash. Both sit behind the same seam, so neither consumer knows it is
  Lucene. This is "stability through simplicity": one real backend with one lifecycle, not two.
- **In-memory is the seam's fake.** A trivial in-memory `EmbeddingStore` (or langchain4j's
  `InMemoryEmbeddingStore`) exists only to exercise the seam in fast unit tests. It never ships and
  is not a "store choice", which is why it does not contradict R118's committed-Lucene principle.
- **The seam shape must not assume either backend.** The seam exposes write
  (`add(id, Embedding, payload)`), hybrid search (`search(Query, k)` returning scored hits with their
  payloads + stable IDs), and load-from-path; nothing in the signature names Lucene segments, BM25
  tuning, or HNSW parameters. Backend choice never reaches a consumer. It takes the D1 `Embedding` /
  `Query` records rather than bare `float[]` + text pairs, so the store reads the KNN vector and the
  BM25 text off one object instead of trusting two positional arguments to correspond.
- **The dimension invariant is structural, checked once.** The store is constructed with the
  embedder's `dimension()` (the single source of truth for vector width); `add` validates each
  `Embedding`'s vector length against it at that one point and fails loudly on mismatch, rather than
  letting a wrong-width vector reach Lucene as a runtime surprise or a silent wrong-distance bug. The
  embedder that produces the vectors and the index that stores them therefore agree by construction,
  not by prose. (The store needs the embedder's *width*, not the embedder itself, so this does not
  couple the store's warm to the embedder's: a load-only index still loads off disk before the
  embedder is `Ready`, per D3.)
- **Hybrid fusion, decided for V0: reciprocal-rank fusion (RRF).** `LuceneEmbeddingStore` runs the
  KNN vector query and the BM25 text query separately and fuses their two ranked lists by RRF
  (`score = Σ 1/(k + rank)`, fixed `k`), rather than normalizing and linearly combining raw KNN
  distances against BM25 scores. RRF is parameter-light (one constant, no per-corpus score
  calibration) and is the simplest thing that makes the "BM25 hybrid surfaces a lexical match" test
  deterministic. It is a Lucene-internal detail behind the seam; the fusion choice never reaches a
  consumer and can change without touching slices 9/10.

The store is pure Java; the native footprint is the embedder alone (the ONNX Runtime JNI + per-
platform natives). That is precisely the dependency this module was created to quarantine (R341),
and slice 8 is where it finally lands, off the plugin's compile surface.

## D3 resolved: a sealed `WarmState` + a shared degradation helper, owned here

"Bind sync, warm async, never block the dev loop" is an R118 cross-cutting principle; slice 8 owns
the warm-state machine the consuming tools report through. Resolving the Backlog's prose
"loading / ready / failed" into a typed shape, mirroring R361's exhaustive `LspSchemaSnapshot`
switch posture:

```
sealed interface WarmState<T> permits Warming, Ready, Failed {}
record Warming<T>()                implements WarmState<T> {}
record Ready<T>(T handle)          implements WarmState<T> {}   // the warmed handle
record Failed<T>(Throwable cause)  implements WarmState<T> {}   // dev keeps running
```

The type is **generic over the warmed handle** because slice 8 drives two distinct warms with the
same machinery (see the async-warm harness below): the shared embedder warm is a
`WarmState<Embedder>` and each per-corpus index warm is a `WarmState<EmbeddingStore>`. A non-generic
`Ready(EmbeddingStore)` would have no slot for the warmed `Embedder` and would force the embedder
warm into a second, drifting type; the type parameter is what lets one sealed shape + one harness
cover both. The degradation helper reads any `WarmState<?>` and only branches on Warming / Failed,
so it is handle-agnostic.

- **The state carries exactly its own data.** `Ready` carries the warmed handle (`T` = the `Embedder`
  for the shared warm, the `EmbeddingStore` for an index warm); `Failed` carries the cause for
  diagnostics; `Warming` carries nothing. A bare enum / `isReady()` would force each consumer to
  re-fetch the handle and re-derive the degradation message, and they would drift.
- **One shared degradation helper.** Slice 8 owns the single method that produces the standard
  "index warming, use the structured tools meanwhile" payload from a non-`Ready` state. Slices 9/10
  call it; they do not each re-author the wording.
- **Ownership boundary, stated so an implementer does not over-reach:** slice 8 owns the `WarmState`
  type, the async-warm harness, and the degradation-payload helper. Slices 9/10 own the per-tool
  response shape (how `docs.search` vs `catalog.search` frames their own result) and call the helper
  for the not-ready case. No `docs.search`-shaped response builder belongs in slice 8.
- **The async-warm harness.** A generic `AsyncWarm<T>` takes a loader callable returning `T` (load
  the embedder; build or load a store) and runs it on a background daemon thread, transitioning
  `Warming → Ready(handle)` on success and `Warming → Failed(cause)` on any throwable, exposing the
  current `WarmState<T>` via a `volatile` read (the per-field visibility posture R361 already uses).
  The **embedder load is one shared warm** (`AsyncWarm<Embedder>`; the bge ONNX load is heavy and
  shared across docs + catalog); each **per-corpus index is its own warm** (`AsyncWarm<EmbeddingStore>`,
  same harness), so the embedder loads once and each index warms independently.
- **Cross-warm ordering, stated so the implementer does not invent it.** An index warm that must
  *build* an index by embedding documents (slice 10's catalog corpus) depends on the embedder being
  `Ready`; its loader callable awaits the shared embedder warm **on its own background thread** before
  embedding, so the dependency never reaches the dev thread. An index warm that only *loads* a
  prebuilt index (slice 9's bundled docs index) needs the embedder only at query time, so it does not
  block on the embedder warm at build time. Slice 8 exposes the shared embedder warm's
  `WarmState<Embedder>` (the readable handle + an await affordance) for the consumers to sequence
  against; it does not own which consumer awaits.
- **The await affordance returns the terminal `WarmState<Embedder>`, it does not only block until
  `Ready`.** This is the one genuinely concurrent decision in the slice, so its contract is pinned
  here rather than left to the slice-10 author: `AsyncWarm<T>` exposes a blocking await that returns
  the warm's terminal value, either `Ready(Embedder)` or `Failed(cause)` (never `Warming`). A
  dependent build-warm switches exhaustively over that result and, on `Failed(cause)`, maps it into
  *its own* `Failed(cause)` rather than blocking forever or dereferencing a missing handle, so an
  embedder-load failure propagates into a clean per-index `Failed` whose degradation message the helper
  still produces. The await has **no timeout** (it runs on a daemon thread that never touches the dev
  loop); the only terminal states are the two the sealed type already permits. `graphitron:dev` reaches
  its watch loop without waiting on any warm, and a RAG failure sets `Failed` and leaves dev running
  structured-only, it never takes down the dev loop.

## Dependency quarantine, the payoff this slice exercises

R341 created `graphitron-mcp` explicitly as the quarantine seam, and R361 noted the `graphitron-lsp`
edge it added is orthogonal to that purpose. This slice is where the purpose is finally exercised:
ONNX Runtime (JNI + per-platform natives) is the one genuinely heavy dependency in the whole
programme, and it lands here. The new runtime dependencies (langchain4j embedding module + ONNX
Runtime, Lucene core) are added to `graphitron-mcp/pom.xml` only; the plugin's compile surface is
untouched. Licensing is permissive throughout (Lucene / langchain4j Apache 2.0; ONNX Runtime / bge
MIT), safe to redistribute. Surefire on this module gains `--enable-native-access=ALL-UNNAMED` in its
`argLine` so the slow ONNX-load test runs without the JDK native-access warning escalating (mirroring
the tree-sitter note R361 flagged for the `lsp` edge); the module has no surefire config today. The
"CI runs everything" claim below is pinned by *this same* added config: the new surefire block sets no
`excludedGroups`, so nothing inherited turns off the `slow` group; the `-DexcludedGroups=slow` opt-out
is a command-line affordance for a developer's inner loop, never a default.

## Implementation

All new code under `graphitron-mcp/src/main/java/no/sikt/graphitron/mcp/rag/`:

- **`Embedder.java` (new).** The two-method seam of D1 plus `dimension()`, and the `Query` /
  `Embedding` records it is the sole producer of (each bundling BM25 text + KNN vector, graphitron's
  own types, no langchain4j surface).
- **`BgeEmbedder.java` (new).** The bge-backed implementation wrapping the langchain4j ONNX
  embedding model; owns the query-instruction prefix applied in `embedQuery`. Constructed by the
  async-warm loader so its model load happens off the dev thread.
- **`EmbeddingStore.java` (new).** The write / hybrid-search / load-from-path seam of D2, consuming
  `Embedding` / `Query` and returning payloads + stable IDs only, no backend detail in the signature.
- **`LuceneEmbeddingStore.java` (new).** The Lucene HNSW implementation (BM25 + KNN hybrid in one
  index, fused by RRF per D2), the sole shipping backend. Constructed with the embedder's
  `dimension()` and validates each added `Embedding`'s vector width against it.
- **`WarmState.java` (new).** The generic sealed lifecycle of D3 (`WarmState<T>` over
  `Warming` / `Ready` / `Failed`) and the handle-agnostic degradation-payload helper.
- **`AsyncWarm.java` (new).** The generic background-daemon-thread harness (`AsyncWarm<T>`) that
  drives a loader callable returning `T` to a `WarmState<T>`, instantiated once as
  `AsyncWarm<Embedder>` for the shared embedder and once per consumer index as
  `AsyncWarm<EmbeddingStore>`.

`GraphitronMcpServer` is **not** modified by this slice: it registers no tool here. The seams are
constructed and warmed by the consuming slices (9/10) when they register their tools; slice 8 only
ships the seams and the lifecycle they plug into. (If a wiring point is needed for the shared
embedder warm to start at server construction, it is a single field + a `startWarm()` call, but it
exposes no tool and no capability change.)

## Tests

Per the tiers in `rewrite-design-principles.adoc`. **Test posture (Backlog OQ), decided as layered,
not either/or:**

- **Seam tier (primary), fast, no ONNX.** Against a fake `Embedder` and the real
  `LuceneEmbeddingStore` (Lucene HNSW over a handful of vectors is cheap):
  - **Asymmetry routing:** a fake `Embedder` records its calls; assert the bge query-instruction
    prefix is applied on the `embedQuery` path only and never on `embedDocuments`, and that the
    returned `Query` / `Embedding` records carry the source text alongside the vector.
  - **Store round-trip:** add a few `Embedding` records with planted vectors, then `search` returns the
    nearest by KNN and the BM25 hybrid surfaces a lexical match, each carrying its stable ID + payload.
  - **Dimension guard:** adding an `Embedding` whose vector width disagrees with the store's
    configured `dimension()` fails loudly at `add`, not later inside Lucene.
  - **WarmState transitions:** `AsyncWarm<T>` drives `Warming → Ready(handle)` on a succeeding loader
    and `Warming → Failed(cause)` on a throwing loader, covering both instantiations (a
    `WarmState<Embedder>` and a `WarmState<EmbeddingStore>`) so the type parameter is exercised, not
    just asserted; a read during warm sees `Warming`; the degradation helper returns the standard
    payload for both non-`Ready` states. Exhaustive switch over the sealed permits with no `default`,
    so a new arm forces a compile-time choice.
  - **Await propagation:** the await affordance on a loader that throws returns the terminal
    `Failed(cause)` (never `Warming`), and a dependent warm that awaits a `Failed` upstream resolves to
    its own `Failed(cause)` rather than hanging, exercising the cross-warm failure path of D3.
- **Infrastructure tier (real ONNX), runs in CI.** One test that actually loads `bge-small-en-v1.5-q`
  and embeds a string, asserting the dimension is 384 and that two related sentences score closer than
  two unrelated ones. The semantic half is the only check that the native binding produces *meaningful*
  output, so it is written to fail for the right reason and not flake on a model-version bump or
  cross-arch ONNX numerics: pick sentence pairs whose related/unrelated separation is large for any
  sane embedding (not a near-tie) and assert a strict inequality with a comfortable margin, not a
  hand-tuned epsilon. It **runs in CI's default `mvn verify -Plocal-db`** as the native-binding
  backstop, the analogue of the sakila-example compile that also runs on every CI build; this is the
  cross-check that the real native binding + `--enable-native-access=ALL-UNNAMED` works, so it has to
  execute somewhere, and CI is that somewhere. The model ships bundled in the langchain4j bge jar (no
  per-test network fetch), so the cost is a one-time dependency download plus a ~1-3s ONNX load on a
  build that already runs Testcontainers Postgres and builds tree-sitter from source; the slow test is
  noise against that. It additionally carries a plain `@Tag("slow")` (not a tier meta-annotation, which
  the four-tier `TierAnnotationEnforcementTest` does not police outside `graphitron` /
  `graphitron-sakila-example` anyway) purely so a developer's fast inner loop can exclude it with
  `-DexcludedGroups=slow`, mirroring how `-DexcludedGroups=execution` skips Postgres locally. The tag
  is a local-loop convenience, not a CI skip: CI runs everything.

## What this slice does *not* change

No classification, no generator branch, no validator arm: this is module-local infrastructure for
the semantic tools, not a pipeline change. **Validator-mirrors-classifier does not apply** here, and
the dispatch / `Rejection` taxonomies are untouched. Named explicitly so a reviewer does not flag a
missing validator arm. `GraphitronMcpServer`'s capability set and tool surface are unchanged by this
slice (the semantic tools arrive in 9/10).

## Out of scope

- **The docs index build** (chunking `.adoc`, the `// rag:split` delimiter, heading-path prepend,
  pre-embedding, jar bundling) and `docs.search` itself, slice 9.
- **The catalog descriptor composition** (snake_case / camelCase name normalization, readable
  per-table descriptor) and the persist-by-content-hash + classpath-trigger refresh, and
  `catalog.search` itself, slice 10.
- **Any agent-facing tool or MCP capability change.**
- **The multilingual model swap** (deferred per D1; V0 is English-only).
- **DuckDB** and the R117 knowledge-base single-store case.

## Builds on

- **R341** (Done): `graphitron-mcp` exists as the quarantine module; this slice is the dependency it
  was created to hold.
- **R361** (Done): the `tools` capability and the async-warm posture the dev loop already tolerates,
  plus the `volatile`-field visibility posture the `WarmState` read reuses.

Both landed on trunk, so *Builds on*, not `depends-on`.

## Related

- **R118** (`graphitron-mcp-server.md`, Backlog programme): slice 8, the only slice that is pure
  semantic-layer infrastructure with no agent-facing tool.
- Blocks **slice 9** (`docs.search`) and **slice 10** (`catalog.search`), and the optional slice 11
  (method search): each consumes the `Embedder` + `EmbeddingStore` + `WarmState` seam and should
  carry `depends-on: [R372]` when filed.
- **R117** (knowledge-base programme): the internal-model counterpart that can later reuse this same
  `Embedder` / `EmbeddingStore` seam; DuckDB is deferred there, not here.
