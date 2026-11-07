---
id: R1160
title: "Tests read pre-captured fact documents from a shared store"
status: Spec
bucket: dx
priority: 5
theme: testing
depends-on: []
created: 2026-11-06
last-updated: 2026-11-07
---

# Tests read pre-captured fact documents from a shared store

## Goal

A test that needs a schema captured into a fact store (the H2 database the generator, the language server and the MCP server read their model from) stops capturing inline SDL for itself and reads a fact document that is captured once per test JVM. Fact documents are the SDL files under `graphitron-model/src/test/resources/facts`, each sitting in a folder that states a claim about it (`scenarios` generate, `defects` store a defect) and optionally carrying blocks such as `@assertContains(relation:, rows:)` that pin the rows the store holds for it; `FactExpectationTest` checks every one. Today the reactor's tests run about 630 capture call sites across about 210 files, nearly all per test case rather than per class, and each one boots or borrows a store and runs the whole capture (gatherers plus derivation steps) again for a schema that often differs from its neighbour's by a field. When this lands, a schema a test reads is a document, its store-level facts are claim blocks in that document rather than Java assertions, a test whose subject sits above the store (generated code, an editor answer, an MCP tool) reads the document's graph through a connection that can only select, and every moved schema has its folder claim checked for free.

Baseline, measured over `src/test/java` at `2a1330c` (call sites, not runtime captures; loops and parameterized tests multiply them):

[cols="2,1,1,1,3"]
|===
|Module |Files |Sites |Class-wide |Main harnesses

|graphitron |about 112 |about 334 |21 |`CapturedStore` arms, `GraphitronStore.captured`, `TestSchemaHelper.buildBundle` (a hidden capture per distinct SDL text), `BuiltStore`
|graphitron-model |about 33 |about 92 |10 |`CapturedStore` arms, `FactExpectationTest`'s one `@BeforeAll` over every document
|graphitron-lsp |51 |about 146 |40 |`StoreFixture`, `held()` in `@BeforeAll`
|graphitron-mcp |6 |36 |4 |`StoreFixture`, `held().shared*`
|graphitron-maven-plugin |about 8 |about 22 |0 |`StoreBackedBuild`, `BuiltStoreFixture`, full builds on file-backed stores
|===

## Design

### The population: `FactGraphs`

A new class in graphitron-model's test harness package (`no.sikt.graphitron.model.test`), published in the test-jar that graphitron, graphitron-lsp, graphitron-mcp and graphitron-maven-plugin already consume. It is its own class rather than a member of `FactStores`, because `FactStores` states as its design that it never puts rows in a store, and every harness above it relies on that. `FactGraphs` opens its stores through `FactStores.inMemory(StoreUse.LONG_LIVED)` and captures through `FactDocuments.capture`, so there is still one reading of a document: the one `FactExpectationTest` checks and `GenerateTestSchemas` generates from (catalog from `CatalogInput.of(TestRunContext.of())` unless the document's `@withConfiguration` names another, classpath `ClasspathCorpus.entries()`).

- **Captured once per JVM, lazily, per document.** The first ask for a document captures it as its own graph, named by its id; every later ask, from any class on any thread, reads that graph. The holder is a value in JUnit's root `ExtensionContext` store, which lives as long as the launcher session and is closed at its end as an `AutoCloseable`. `FactGraphs.captures(id)` counts one document's captures and stays at one.
- **Sharded so capture keeps its parallelism.** Each store has one writing connection, and a capture holds it for its whole transaction. One shared store would serialize captures that run on four threads today, so the population holds one store per worker and assigns each document to a shard by its id, with a lock per shard. The shard count is read from `junit.jupiter.execution.parallel.config.fixed.parallelism` through `ExtensionContext.getConfigurationParameter`, defaulting to one, which is four in every module that carries the setting. Several graphs in one store is already sound: `FactExpectationTest` captures every document into one store today and checks each graph's claims there.
- **A stepped document is captured through its last instant.** Stepped documents are the incremental captures: several versions of one graph's files, each read at its own instant (`<graph>.<file>.<epoch>.graphqls` under `incremental-captures`). The population runs the same sequence `FactExpectationTest` runs, through `CapturedStore.andCatalogGraphReadAt`, and hands out the final state.
- **Read-only, enforced by the database.** A reader connects as `GRAPHITRON_READER`, the user every store's DDL creates with `SELECT` on the schema and nothing else (`GraphitronModelStore.reader`), so a write from a test is refused where it is issued.

### The handout: a class-scoped extension, reads in a transaction

A test declares the population as a field, the shape `FactStores.perClass()` and graphitron-mcp's `StoreFixture.held().shared*` already use:

```java
@RegisterExtension
static final FactGraphs.Reader FACTS = FactGraphs.reader();

@Test
void aConnectionFieldPagesThroughItsEdges() {
    var output = FACTS.read("scenarios/as-connection", graph ->
        new GraphQLRewriteGenerator(ctx, graph.handle(), graph.schema()).buildOutput());
    ...
}
```

`FactGraphs.Reader` mints one `StoreReader` per shard the class touches, lazily, with `ReadBudget.Unbounded` as every fixture reader in the reactor does, and closes them in `afterAll`, before the root store closes the shards. Methods within a class run sequentially under every module's `junit-platform.properties`, so one reader per class and shard never serializes two classes behind each other.

`read(id, body)` runs `body` inside one `StoreReader.read` transaction and hands it a `FactGraph`: the `StoreHandle` (a `DSLContext` plus the graph name a read is scoped to) and the `CapturedSchema` the capture built, which a generating run needs beside the store and which is an immutable value safe to share across threads. There is no parameter resolver that hands a bare `StoreHandle` to a test method: `StoreReader` makes its transaction the only way onto a reader connection, so that an answer built from several queries sees one commit, and a handle that outlived the transaction would bypass that. Production readers take exactly this shape (`CatalogCorpus`, `CatalogQueries`: `reader.read(dsl -> ... new StoreHandle(dsl, graphName))`).

A test reading the store directly scopes every query through the handle, filtering on `graph_name` or `StoreHandle.reads(source_name)`. A query that forgets this reads sibling graphs in the shard: it fails loudly when an assertion counts rows, and silently when it asserts existence. That is why the migration below moves store-level assertions into claim blocks rather than into handle-scoped Java, and keeps handle reads for the consumers above the store, which already scope through the handle in production.

### `FactExpectationTest` reads the population

Its plain documents are read from `FactGraphs` instead of captured into a store it borrows from `ThreadConfinedStore`, so in graphitron-model the documents it checks and the documents other tests read are captured once between them. `-Dfacts=<prefix>` keeps working, since only the documents it names get asked for. Its stepped graphs keep their own capture: the test checks each instant's claims as that instant is read, which is an intermediate state the population does not keep. To keep its single class from capturing every document serially on one thread, `FactGraphs.warm(ids)` captures a list of documents with one thread per shard.

## Implementation

The seams below are real: each step lands on its own, keeps the build green, and is measured on its own (see Evidence).

1. **The population and the first readers.**
   - `FactGraphs`, `FactGraphs.Reader` and `FactGraph` in graphitron-model's test harness, with `FactGraphsTest` (see Tests).
   - `FactExpectationTest` reads its plain documents through the population, as above.
   - The emit tests in `no.sikt.graphitron.emit`. The 10 query and execution tests that capture an inline `SDL` in `@BeforeAll` (`BuildQueryTest`, `CallQueryTest`, `FillQueryTest`, `FrameQueryTest`, `LiftQueryTest`, `NodeIdDecodeQueryTest`, `LiftEmitterExecutionTest`, `LiftEmitterPipelineTest`, `CallLiftExecutionTest`, `CallPolymorphicExecutionTest`) move their SDL into scenario documents and read their rows through `FACTS.read`. The 7 emitter tests that build query rows by hand (`BuildEmitterTest`, `CallEmitterTest`, `FillEmitterTest`, `FrameEmitterTest`, `NodeIdBuildEmitterTest`, `NodeIdFillEmitterTest`, `RecordBuildEmitterTest`, constructing `FillQuery.Row`, `BuildQuery.Build` and the like directly) read those rows from the documents their cases live in, through the matching `*Query.read`, so an emitter test can no longer state a row no capture produces.
   - `GenerateTestSchemas` generates its scenarios on a fixed pool of four threads. Each scenario already captures into its own store, so nothing is shared between them. Every scenario keeps being generated and compiled, since that is what checks the scenario claim from the generator's side ("a scenario this refuses is a defect the store does not record"); the pool is what keeps the growing folder from growing `generate-test-schemas` on one core.
   - `InlineCaptureGateTest` in each of the five modules: a count of inline-SDL capture call sites in the module's test sources, held under a ceiling that may be stale on the low side and may not rise, the shape `SeedingDissolutionGateTest` has. It counts calls of the capturing arms of `CapturedStore`, `GraphitronStore.captured`, `TestSchemaHelper.buildBundle`, `BuiltStore`, and the lsp and mcp `StoreFixture` arms, and excludes the harness files themselves.
2. **graphitron-model, then graphitron, then graphitron-lsp, then graphitron-mcp and graphitron-maven-plugin**, one step per module, each lowering that module's ceiling. Within a module each capture site is sorted by the rules below.

### Which tests move, and where

- **A test that asserts store rows** (a raw `DSLContext` query over a fact or derived relation after capture, about 68 files in graphitron and most of graphitron-model) becomes a document. Its assertions become claim blocks in the document, and the Java test is deleted. A schema the store records no defect for goes under `scenarios`; one it records a defect for goes under `defects`. An assertion no claim block can state stays a Java test and reads the document through the handle, scoped as above.
- **A test whose subject sits above the store** (a generator run over `GraphQLRewriteGenerator`, a builder bundle from `TestSchemaHelper`, an emit query, an editor provider fed a `StoreHandle`, an MCP tool) moves its SDL into a document and reads it through `FACTS.read`. A schema several tests in one class state with one field changed becomes one document where the cases agree, or two where they do not.
- **A test whose generator rejection the store does not yet record as a defect** keeps its inline setup. Such a document has no folder whose claim it meets, and `undecided` only shrinks. It moves once the defect is stored.

### What keeps its own setup

The inventory names these, and the ceilings are set with them counted in.

- A test that writes after capture: a recapture or a second graph (`CapturedStoreTest`, `ClassificationDomainTest`, `TypeBackingPartitionTest`, `NodeGraphScopeTest`, lsp `HoversTest`), hand DML (`WarmStartRefreshTest`, `PersistentStoreTest`, `CoordinateSpellingGateTest`, `RulePlanGateTest`), fact writers (`DiagnosticFactsTest`, the lsp `withBuildWarnings` / `withValidationErrors` / `withJavaSource` users, the maven-plugin `FactWriters` users), or DDL (`CaptureCommitsAsItGoesTest`, `makeRunaway` in the `StoreOutOfBudgetTest`s).
- A test that reads an intermediate state not stated as a claim: `CaptureCommitsAsItGoesTest`, `WriteTimesTest`, `SourceMtimeTest`, `EmittedAnchorLifecycleTest`, `GraphitronStoreTest`.
- A test whose subject is a file-backed store or warm start: `PersistentStoreTest`, `WarmStartRefreshTest`, the `BuiltStore` users in the maven-plugin.
- A test whose subject is a capture's failure path rather than its stored outcome: `BrokenSourceStillCapturesPipelineTest`, `SchemaParseExceptionPropagationTest`, the `ofRefusedSchema` users.
- A gate that counts or times capture, statements or boots: `ThreadConfinedStoreTest`, `StoreBootCountTest`, `CorpusCapturedOnceTest`, the `*StatementCountTest` classes, `PartitionSelectivityWorthTest`, `StagePrerequisiteStatisticsTest`, `CaptureStatementTextTest`.
- A test of one capture stage in isolation (the `GraphQLSourceCapture` to `GraphQLAssemblyCapture` chains over `ThreadConfinedStore.run`, lsp `SdlDeprecations`), and the `SeededStore` callers, which `SeedingDissolutionGateTest` already owns.
- The corpus sweeps over `CorpusStore`, which retire with the walk they compare against.

## Tests

- `FactGraphsTest` in graphitron-model:
  - a document asked for from several classes on several threads is captured once (`captures(id)` is one);
  - an `INSERT`, `UPDATE`, `DELETE` or DDL statement inside `read` is refused by the database;
  - a document read before and after a sibling is captured into the same shard reads the same rows, which is the property sharding rests on;
  - a stepped document hands out its last instant's state;
  - a reader closes in `afterAll`, and the shards close when the root store does.
- `FactExpectationTest` stays green over the population, with every moved document's folder claim and claim blocks checked by its existing cases.
- The five `InlineCaptureGateTest`s hold each module's ceiling; the item is complete when every remaining site falls under a rule in "What keeps its own setup".

## Evidence

Each module step reports, in its commit message, the module's test phase before and after (interleaved pairs, measured the way the `build-profile` skill describes) and `FactStores.captures()` at the end of the module's run. Step 1 also reports `generate-test-schemas` before and after.

## Retired vocabulary

`CapturedStore` arms and `TestSchemaHelper` methods that the migration leaves without callers are deleted in the step that empties them, and listed here as they go, for the sweep at Done. `TestSchemaHelper`'s `EMITTED` memo, which exists so that per-text captures stay under `ThreadConfinedStore`'s boot budget, is expected to be one of them.

## Other solutions we've considered

- **A JUnit parameter resolver that hands a test method a `StoreHandle`.** Rejected: a handle outside a `StoreReader.read` transaction needs a bare reader connection, which `StoreReader` exists to withhold.
- **Generalising `CorpusStore`**, which already captures a population once per JVM and hands out select-only readers. Rejected: it is retiring with the corpus sweeps, and its unwritten-store check compares a row census taken once, which does not hold for a population that fills lazily. The select-only user is the enforcer it relies on too.
- **One shared store for every document.** Rejected: it serializes every capture in the module on one writing connection.
- **One store per document.** Rejected: a long-lived store with a full statement cache costs about 30 MB, and a few hundred of them in one test JVM is the heap cost the capped pool exists to avoid.
- **Generating only the scenarios a test imports**, to keep `generate-test-schemas` flat as the folder grows. Rejected: generating every scenario is the generator-side check of the scenario claim.
