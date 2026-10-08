---
id: R1006
title: "MCP test suite: share read-only store fixtures, time only the read under test, cache the docs index across clean"
status: Ready
bucket: bug
priority: 1
theme: testing
depends-on: []
created: 2026-10-08
last-updated: 2026-10-08
---

# MCP test suite: share read-only store fixtures, time only the read under test, cache the docs index across clean

## Goal

Trunk CI goes green again and stays green under load, and `graphitron-mcp` stops being one of the slow stages of a local `mvn clean install`. Today every trunk run since 2026-10-06 fails on one test, `StoreOutOfBudgetTest.theCatalogDescribeToolFailsRatherThanReportingNotFound`, which runs out of its 60-second hang guard on CI while still building its fixture. Locally the module's clean install takes about 67 s, almost all of it in two places that repeat work: `GraphitronMcpServerTest` captures a fresh store for each of its 54 cases, and the docs index (the embedded copy of the manual behind the MCP `docs.search` tool) is re-embedded on every `clean`. Nothing changes for consumers; this is test and build cost only.

## Evidence

Measured on a 4-core web sandbox at `b018bc0`, and on trunk CI (4 vCPU, `mvn install -T 1C -Pcoverage`).

- **The failing test is a fixture-cost problem, not a hang.** Locally the case takes 5.3 s (7.3 s with the JaCoCo agent) and its derivation stratum 0.4 s. On CI the stack at timeout is inside `StoreFixture.ofCatalog` in three of four red runs, before the read under test starts. The last green run (6394, `ff39052`) already spent 60.12 s on the class. Since then every module that ran in both runs is about 30% slower on CI, roadmap-tool included with an identical 303 tests, so the trigger is runner contention rather than a commit. On CI graphitron, graphitron-model, graphitron-lsp, graphitron-mcp and roadmap-tool run their tests concurrently, and `graphitron-mcp` runs four classes at once because `graphitron-model`'s test-jar carries its `junit-platform.properties`.
- **`GraphitronMcpServerTest` sets the module's test wall clock.** Its 54 cases each open a fixture through try-with-resources (11 `ofSchema(SCHEMA_SDL)`, 11 `ofCatalog`, 7 `ofCodeFixtures`, 6 others), at about 0.7 s per capture. Priced by removal, two interleaved pairs, no `clean`: module tests 44.1 / 42.8 s with the class, 22.1 / 21.6 s without.
- **`build-docs-index` is 19 s of a 67 s clean install.** `DocsIndexBuilder` skips the embed when its content-hash stamp matches, but stamp and bundle live under `target/classes/mcp/docs-index`, so `clean` discards both.
- **One budget escape, out of this item's scope.** In one red run (6442, `cb9e443`) the fixture finished and the 500 ms `ReadBudget` did not stop the runaway read: the thread sat in H2 `RecursiveIndex.find` under `CatalogQueries.resolve` until the 60 s guard fired. The runaway cases issue DDL (`StoreFixture.makeRunaway`) on a borrowed thread-confined store, which the LSP fixture's `Lifetime` javadoc forbids; the change below moves them onto an owned store. If the escape reproduces after this lands, it gets its own item.

## Implementation

**Shared fixtures in `GraphitronMcpServerTest`, as a class-lifetime extension that owns its stores.** The mcp `StoreFixture` borrows the per-thread store (`CapturedStore.of*`, backed by `ThreadConfinedStore`), which the next capture on that thread empties and refills, so a fixture that outlives one case must own its store. Two separate facts are in play: ownership (whether the store can be cleared under you) and sharing (whether other cases see what a case did to it). The plan makes sharing imply ownership by construction rather than by convention.

`StoreFixture` gains owning arms for the three shapes the class repeats, `ofCatalog`, `ofSchema(sdl)` and `ofCodeFixtures`, delegating to `CapturedStore.ownStoreOfCatalog` and `CapturedStore.ownStore` (the arms the LSP fixture's `held()` uses). `ofCodeFixtures` runs `CapturedStore.captureCode` and `FactWriters.refreshJavaSources` after the capture; both apply to the owned store unchanged. The shared form is reachable only from the owning side: a `BeforeAllCallback` / `AfterAllCallback` extension, registered as a `@RegisterExtension static final` field, on the model of `FactStores.perClass()` / `FactStores.ClassStore`. It captures in `beforeAll` and closes in `afterAll`, so no case spells the store type and the close cannot be forgotten. Each shared fixture captures into its own subdirectory of a class-owned temporary directory: all three capture under `GRAPH`, so in one directory they would overwrite each other's `fixture.graphqls` (`CapturedStore.fixtureFile`).

Cases take the shared fixture through a narrow view, `handle()`, `handleFor(String)`, `reader()` and `graphName()`, which keeps the obvious mutators (`makeRunaway`, `recaptureCatalog`, `andGraph`) out of reach. That is a convenience, not the guarantee: `StoreHandle.dsl()` hands back a writable `DSLContext`. The guarantee is an end-of-class check in the extension's `afterAll`: the base-table set and per-table row counts snapshotted after capture must be unchanged when the class ends, which catches an insert, a delete and the table rename `RunawayRelation.install` performs alike. The snapshot reuses `ThreadConfinedStore`'s own `baseTables` / `census` / `counts` (the instruments behind `verifyCleared`), widened from package-private for the purpose.

Within a class, methods run sequentially (`mode.default=same_thread`). Server cases read through the memoized `reader()` from Jetty request threads as well, which holds because readers serialize and `CatalogSearchIndex.observe` reads the corpus on the request thread before its warm daemon starts. Cases with one-off shapes (the two small SDLs, `ofRefusedSchema`, `ofMultiSchemaCatalog`, the inline SDL) are unchanged.

**Time only the read under test in the out-of-budget cases.** In `graphitron-mcp`'s and `graphitron-lsp`'s `StoreOutOfBudgetTest`, the 60 s guard exists to catch a runaway read escaping its budget. It moves off the method (`@Timeout`) and onto the statements after fixture setup and `makeRunaway`, with `assertTimeoutPreemptively`, so fixture cost on a loaded runner no longer counts against it. The guard value stays 60 s, and no case asserts a duration.

`assertTimeoutPreemptively` runs its body on another thread, so the fixture must be owned: a borrowed `CapturedStore` checks its thread's generation on every `dsl()` / `reader()` (`CapturedStore.mine()`), and on the executor thread that boots a fresh thread store and fails the check. Today's `@Timeout(threadMode = SEPARATE_THREAD)` runs the whole method, capture included, on a throwaway thread, which likely boots a thread store per case that is never released, adding to the fixture cost above. The mcp runaway cases therefore move to the owned arm, and mcp's `StoreFixture.makeRunaway` gains the guard the LSP fixture already has (it throws unless the fixture owns its store), so a borrowed runaway case cannot come back silently.

**Cache the docs index across `clean`.** `DocsIndexBuilder` takes a third argument, a cache directory, defaulting from a Maven property `graphitron.docsIndex.cacheDir` to `graphitron/docs-index` under the platform cache root that `DevMojo.userCacheRoot` already resolves (`$XDG_CACHE_HOME` or `~/.cache`, `~/Library/Caches`, `%LOCALAPPDATA%`); the resolution is shared rather than restated. The key is derived mechanically, with no list to keep current: the existing content hash over the in-scope `.adoc` files, plus a digest of every class file under `no/sikt/graphitron/mcp/rag/docs/` and of `BgeEmbedder`, plus the identity of the bge model artifact (`langchain4j-embeddings-bge-small-en-v15-q`, by its resolved version), so a change to chunking, bundling, the embedder or the model weights misses the cache instead of serving stale vectors.

On a hit the cached bundle is copied to the output directory and the stamp written. On a miss the embed runs as today and the bundle is published to the cache by temp file plus atomic move. Concurrent builds share the cache (worktrees, parallel sessions), so any IO failure reading it is treated as a miss, and pruning (keep the most recent few bundles, each about 1 MB) tolerates a concurrent reader. The in-`target` stamp check stays as the first, cheapest gate. `DocsIndexBuilder.build` constructs `BgeEmbedder` inline today; the plan adds an embedder seam so a test can observe whether an embed ran.

## Tests

- `GraphitronMcpServerTest` passes with its assertions unchanged, over three shared captures, and its extension's end-of-class check passes.
- A test that the end-of-class check fails when a case writes to a shared store (an insert, and a `makeRunaway`), so the guarantee is pinned rather than assumed.
- mcp `StoreFixture.makeRunaway` refuses a borrowed fixture.
- Both `StoreOutOfBudgetTest` classes pass with the guard around the read only.
- `DocsIndexBuilder`, through the embedder seam: a second build into an emptied output directory with the same inputs and a populated cache does not embed; a changed docs file, a changed class under `rag/docs`, and a changed model identity each miss; an unreadable cache entry is a miss, not a failure.
- Acceptance: `StoreCostExtension`'s `GRAPHITRON_STORE_COST_REPORT` line for `GraphitronMcpServerTest`, captures down from about 35 to 3 plus the one-offs with boots up by about 3, as the deterministic evidence; the module's clean-install and test wall clock priced the way the build-profile skill prescribes (interleaved arms, no single-run figures), before and after; and the next trunk CI run green.

## Other solutions we've considered

- **Raise the timeout.** Treats the symptom and leaves the guard timing fixture cost, so the next slowdown re-breaks it.
- **Lower CI concurrency (`-T`) or stop the test-jar leaking `junit-platform.properties` into mcp.** Both bear on the contention, and the leak is worth its own decision, but neither fixes a guard that times setup, and either changes the whole reactor's wall clock.
- **Hand-written `@BeforeAll` / `@AfterAll` fields.** Works, but leaves "shared implies owned" and the close to each class's author; `FactStores.perClass()` is the reactor's declared shape for a class-lifetime store and states why.
- **Keep the docs index under `target/`, as the build's other state is.** `mojo-configuration.adoc` § "Where the fact store lives" keeps build-goal state under `target/` so `mvn clean` removes it, for hermetic CI and containers that discard `$HOME`, and `RagConfig` says the same of the runtime catalog index. This case differs: it is the repo's own build rather than a consumer's, the bundle is a pure function of inputs the key names, and losing the cache costs only the embed it saves. A CI runner that discards `$HOME` simply misses. A gitignored directory in the module, or excluding the directory from `maven-clean-plugin`, would survive `clean` by accident of location or make `clean` mean less than it says.
