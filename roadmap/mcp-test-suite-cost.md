---
id: R1006
title: "MCP test suite: share read-only store fixtures, time only the read under test, cache the docs index across clean"
status: Spec
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

**Shared, owned, read-only fixtures in `GraphitronMcpServerTest`.** The mcp `StoreFixture` borrows the per-thread store (`CapturedStore.of*`, backed by `ThreadConfinedStore`), which the next capture on that thread empties and refills, so a fixture that outlives one case must own its store. Give `StoreFixture` the owning arms the LSP fixture already has (`held()`, delegating to `CapturedStore.ownStore` / `ownStoreOfCatalog`) for the three shapes the class repeats: `ofCatalog`, `ofSchema(sdl)` and `ofCodeFixtures`. `ofCodeFixtures` runs `CapturedStore.captureCode` and `FactWriters.refreshJavaSources` after the capture; those apply to the owned store unchanged.

The class captures each of the three once, in `@BeforeAll` under a static `@TempDir`, and closes them in `@AfterAll`. A shared fixture is handed to cases as a read-only view exposing only what the class calls today: `handle()`, `handleFor(String)`, `reader()` and `graphName()`. `makeRunaway`, `recaptureCatalog` and `andGraph` are absent from the view, so a case that wants to change a store cannot do it to a shared one and keeps opening its own. Methods within a class run sequentially (`mode.default=same_thread`), so one memoized `reader()` per shared fixture is not contended. Cases with one-off shapes (the two small SDLs, `ofRefusedSchema`, `ofMultiSchemaCatalog`, the inline SDL) are unchanged.

**Time only the read under test in the out-of-budget cases.** In `graphitron-mcp`'s and `graphitron-lsp`'s `StoreOutOfBudgetTest`, the 60 s guard exists to catch a runaway read escaping its budget. Move it off the method (`@Timeout`) and onto the statements after fixture setup and `makeRunaway`, with `assertTimeoutPreemptively`, so fixture cost under a loaded runner no longer counts against it. The mcp cases also move onto the owned arm, since `makeRunaway` issues DDL. The guard value stays 60 s; no case asserts a duration.

**Cache the docs index across `clean`.** `DocsIndexBuilder` takes a third argument, a cache directory, defaulting from a Maven property `graphitron.docsIndex.cacheDir` to `${user.home}/.cache/graphitron/docs-index`. The cache key is the existing content hash over the in-scope `.adoc` files extended with a builder version: a digest of the class files of `DocsIndexBuilder`, `AdocChunker`, `DocsBundle` and `BgeEmbedder` read from the build classpath, so a change to how the manual is chunked or embedded misses the cache instead of serving a stale bundle. On a hit the cached bundle is copied to the output directory and the stamp written; on a miss the embed runs as today and the bundle is also written to the cache. The cache keeps the most recent few bundles (each about 1 MB) and prunes the rest. The in-`target` stamp check stays as the first, cheapest gate.

## Tests

- `GraphitronMcpServerTest` passes unchanged in its assertions, now over three shared captures.
- A test-tier check that the read-only view offers no mutating method, so the compile-time guarantee above is pinned rather than incidental.
- Both `StoreOutOfBudgetTest` classes pass with the guard around the read only.
- `DocsIndexBuilder`: a unit test that a second build into an emptied output directory with the same docs and a populated cache does not embed (observed through a counting embedder seam or the builder's own skip line), and that a changed builder-version component misses.
- Acceptance: the module's clean install and test wall clock, priced the way the build-profile skill prescribes (interleaved arms, no single-run figures), before and after; and the next trunk CI run green.

## Other solutions we've considered

- **Raise the timeout.** Treats the symptom and leaves the guard timing fixture cost, so the next slowdown re-breaks it.
- **Lower CI concurrency (`-T`) or stop the test-jar leaking `junit-platform.properties` into mcp.** Both bear on the contention, and the leak is worth its own decision, but neither fixes a guard that times setup, and either changes the whole reactor's wall clock.
- **A gitignored cache directory inside the module, or excluding the docs-index directory from `maven-clean-plugin`.** The first survives `clean` only by accident of location; the second makes `clean` mean less than it says. A cache keyed by content under the user cache directory is the conventional shape and is shared across worktrees.
