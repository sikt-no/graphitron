---
id: R538
title: "BgeEmbedderOnnxTest fails nondeterministically under full-reactor parallel load"
status: Backlog
bucket: testing
priority: 4
theme: tooling
depends-on: []
created: 2026-07-25
last-updated: 2026-07-25
---

# BgeEmbedderOnnxTest fails nondeterministically under full-reactor parallel load

`BgeEmbedderOnnxTest.loadsTheRealModelAndEmbedsAtTheExpectedDimensionWithMeaningfulSimilarity` fails intermittently in `mvnd install -Plocal-db`: 2 of 4 full-reactor runs observed failing, while 3 of 3 isolated `-pl graphitron-mcp` runs passed. The test is the native-binding backstop, deliberately written to run in CI's default build, and its assertion has a generous margin (`related > unrelated + 0.1`) precisely so it cannot flake on numerics. Both failures blew through that margin in the wrong direction, `related` scoring *below* `unrelated` (0.371 vs 0.458, then 0.285 vs 0.347), so the embeddings are degraded rather than merely noisy, and the two runs report different values, so the ONNX path is varying run to run. The 384-dimension assertion passes both times and the model jar's checksum matches its recorded `.sha1`, so this is not a truncated or wrong model artifact; the leading hypothesis is CPU/thread contention under mvnd's parallel module execution driving ONNX Runtime onto a degraded compute path, which would make the "cannot flake" premise in the test's own javadoc false on a loaded machine. This matters because the test gates every `-Plocal-db` build: a ~50% failure rate under the documented build command means every item's Done gate intermittently reports red for a reason unrelated to that item, which is exactly what happened during the R527 review (`graphitron-mcp` was untouched across that item's whole range). Investigation should first establish whether the nondeterminism is contention-driven (pin ONNX Runtime's intra-op thread count and re-run under load) or inherent to the quantized model's kernels on this arch; the fix is then either a deterministic session configuration or moving the semantic assertion to a load-independent tier, not widening the margin, which would forfeit what the test exists to catch.
