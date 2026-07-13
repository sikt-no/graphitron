---
id: R470
title: "Multilingual embedding for catalog.search (Norwegian catalog comments)"
status: Backlog
bucket: feature
priority: 6
theme: dev-loop
depends-on: []
created: 2026-07-13
last-updated: 2026-07-13
---

# Multilingual embedding for catalog.search (Norwegian catalog comments)

Carried out of the R118 MCP-server programme (discarded 2026-07-13 with slices 1-10 shipped) as its one live unresolved open question. The MCP semantic tools (`catalog.search`, `docs.search`) embed with `bge-small-en-v1.5-q` (`graphitron-mcp/.../rag/BgeEmbedder.java`, chosen in R372 D1), which is English-only. Consumer databases, notably Sikt's own, may carry Norwegian table and column comments; against such a catalog the semantic half of `catalog.search` degrades to whatever the English model happens to extract from Norwegian text, and retrieval quality on the comment signal is unquantified. The docs corpus is English, so `docs.search` is unaffected.

Direction sketched in R118: swap to a multilingual model such as `multilingual-e5-small` behind the existing langchain4j `EmbeddingModel` seam (`rag/Embedder.java` keeps the model swappable by design). That may mean bundling the ONNX model ourselves rather than using a prebuilt langchain4j module, and e5 has its own asymmetric query-prefix convention to encode where `BgeEmbedder` encodes bge's today. Gate the work on evidence: before swapping, measure retrieval quality of the current model against a real Norwegian-commented catalog (an `opptak`-style schema) to confirm the degradation is material. The hybrid BM25 half of the Lucene index already matches Norwegian tokens verbatim, which may be good enough for name-plus-comment discovery in practice.
