---
id: R1160
title: "Every schema a test captures is a fact document, and the test strategy is rebuilt on them"
status: In Progress
bucket: dx
priority: 5
theme: testing
depends-on: []
created: 2026-11-06
last-updated: 2026-11-11
---

# Every schema a test captures is a fact document, and the test strategy is rebuilt on them

## Goal

Every schema a test captures into a fact store is a fact document, and the reactor's test strategy is rewritten around that. A fact store is the H2 database the generator, the language server and the MCP server read their model from; a fact document is an SDL example under `graphitron-model/src/test/resources/facts`, sitting in a folder that states a claim about it (a scenario generates, a defect document stores a defect) and carrying claim blocks, `extend schema` applications such as `@expectEquals(relation:, rows:)` that pin the rows the store holds for it, which `FactExpectationTest` checks. The premise is that a schema worth stating to a test is worth stating as an example whose claim is checked, whether a test captures it once per class or once per case, and whether it then only reads the store or goes on to write it.

When this lands, a contributor or an agent adding a test starts from the example: they find the document that states the subject, or add one where the placement guideline below says it belongs, and pin what the store holds for it as claims. A test whose subject sits above the store (generated code, an editor answer, an MCP tool) reads that document's graph from a store captured once per test JVM, through a connection that can only select, and states its expectations beside the document without editing it. A document is a scenario: a script that sets up a schema directory by pulling in other documents as reusable building blocks, captures it the way a build does, and verifies its claims, with incremental capture a matter of moving a clock and repeating. The reference documentation's examples are the keystone: the scenario a manual page shows is the authoritative test of the behaviour it documents. The contributor guide, the agent-facing skills and CLAUDE.md teach this, and per-module ceilings that only fall hold the reactor to it.

The job is also deduplication. The reactor has accumulated tests that pin the same behaviour several times over, in several documents, at several levels and in several modules, and a behaviour covered twice is a maintenance cost and a source of disagreement, not extra safety. When this lands, each behaviour has one authoritative test, the one place that pins it, sitting where the behaviour is decided, and `testing.adoc` names the concept and says where each kind of behaviour's authoritative test lives. A consumer above it asserts only what it adds, and a test that repeats what an authoritative test already pins is deleted rather than migrated.

What a document looks like today, and stays, is `scenarios/field-key-row.graphqls`: a prose header saying what it pins, the types the subject needs, and the rows it claims.

```graphql
# A field that fetches its own rows under a parent holding an object takes the keys off that object,
# and how is stated per field. [...]
type FilmKeySummary {
  label: String
  keyCensus: String @service(service: {className: "...FilmKeySummaryService", method: "keyCensus"})
}

extend schema @expectEquals(relation: "graphitron_field_key_row", rows: """
  type_name,             field_name, kind,     table_name, arity
  FilmDetailsCarrier,    language,   COLUMNS,  film,       ONE
  ...
  """)
```

Today `FrameQueryTest` states `FilmKeySummary.keyCensus` again inline and `FrameEmitterTest` builds its row by hand. After this item both read it from the document, and an emitter test can no longer state a row no capture produces.

## Where the strategy stands

Measured over `src/test` at `5c41b4e`; call sites, not runtime captures.

[cols="2,1,1,3"]
|===
|Module |Files |Capture sites |What the tests assert after capture (estimated from a sample)

|graphitron |108 |about 520 |store rows 34%, generated source 19%, defects 17% (mostly rendered message text), classification 15%, Java readers over the store 9%
|graphitron-lsp |49 |about 150 |editor answers 85%, of which a quarter diagnostics
|graphitron-model |30 |about 90 |store rows 48%, emitted and written schema 27%, lifecycle, integrity and plans the rest
|graphitron-mcp |6 |36 |MCP tool and resource answers 94%
|graphitron-maven-plugin |6 |about 22 |MCP and editor answers over a real build
|===

Roughly 60 to 65% of these assertions are already plain data (rows, text, a coordinate and a message), and most of that data is not store rows. Each capture costs about a second, about 500 distinct statements whatever the schema, and a boot about 0.4 s.

The documents themselves:

* **219 graphs from 246 files** (78 scenarios, 81 defects, 19 stepped graphs in 46 files, 41 undecided), 13,073 lines, 719 claim blocks, 37% of the lines claim text. 201 of the store's 424 relations are claimed by some document; 223, mostly derived `*_rule` views and the `graphitron_ast_*_entry` tables, by none.
* **Above the store, one consumer.** Only `ChildOutcomeExecutionTest` reads a document's generated code, out of the 67 scenario packages `GenerateTestSchemas` generates; the 11 scenarios that state `@withConfiguration` are not generated. The manual reads 13 documents through `@factDocument(path:)`, as text. The emit tests written alongside their scenarios restate them inline: `FrameQueryTest` against `field-key-row`, `CallPolymorphicExecutionTest` against `multitable-interface`, `FillQueryTest` against `occurrence-call-parameter`.
* **One graph per document, heavily overlapping.** `Film` is declared in 141 graphs with 58 distinct field sets, and 187 of the 219 graphs share a type name with another. 88 pairs of documents differ by three type or field names or fewer.
* **The same behaviour is pinned at several levels.** Default page size above the maximum is claimed as rows in `defects/default-page-size-above-maximum`, asserted again as a rendered message in `DefaultPageSizeDefectsTest` and again as a failed build plus a diagnostic row in `DefaultPageSizeRejectionPipelineTest`. Type backing is asserted in five modules (`TypeBackingClassTest` in graphitron and in graphitron-lsp, `TypeBackingPartitionTest`, `R157PipelineTest`, the MCP `schema` tool). Claim conflicts, argmapping projection, unlowerable orderings, node-id decode and unresolved columns follow the same pattern, and the lint case for `original_language_id` is stated with identical SDL in four tests.
* **Defects are already facts.** 69 defect documents claim `graphitron_entry_defect_site` rows. The prose a defect renders to is built in Java from those rows, and that prose is what most Java defect tests assert.

## Design

### Scenarios: setup, execute, verify

A fact document is a **scenario**: a script that sets up a schema directory, captures it as a build would, and verifies what the store holds. Three phases, of which only the first is new machinery.

* **Setup is expansion.** A small engine renders the document into an ordinary schema directory, its files dated by a clock, and knows nothing about the store. Its output is a directory one can open, diff, or point the dev loop at.
* **Execute is a normal capture.** The rendered directory is captured the way a build reads a schema directory, through the production capture with the clock's time as the reading's instant. No capture arm exists for tests alone.
* **Verify is the claims**, checked against the store as that capture left it.

Two directives drive the setup, beside the claim directives:

```graphql
extend schema @withFacts(docs: [
  {path: "layouts/sakila-core"},
  {path: "shapes/film-key-summary"}])

type FilmKeySummary { keyCensus: String @service(service: {className: "...FilmKeySummaryService", method: "keyCensus"}) }

extend schema @expectEquals(relation: "graphitron_field_key_row", rows: """ ... """)

extend schema @tick(ms: 60000)
extend schema @withFacts(docs: [{path: "shapes/film-key-summary-renamed", as: "shapes/film-key-summary"}])

extend schema @assertAbsent(relation: "graphitron_field_key_row", rows: """ ... """)
```

* **`@withFacts(docs: [{path:, as:}])`**, repeatable, writes fact documents into the directory as files of the graph. `path` names the document; `as` names the file it is written as and defaults to the path, so a later step writing the same `as` is a new version of that file, which is how an edit, a rename or an emptied file is stated. The list lets a scenario pull in its whole setup in one directive.
* **Expansion strips claims, ignores `@tick` and follows `@withFacts`.** A document pulled in contributes its example and never its claims, and its own `@withFacts` are expanded in turn. Its timeline collapses: its `@tick`s are not read, so pulling in a document never moves the clock, and where it writes one file more than once the last version is the one written, so a scenario with steps pulled in contributes its final version. Only the scenario being run moves the clock. Pulled-in documents compose: a reusable layout is a document that pulls in others, and any document can be a building block: a keystone scenario pulled in as the base of a variant among them. Expansion is transitive, refuses a cycle, and writes each file once however many paths reach it.
* **A clock starts at t0 for every scenario, and `@tick(ms:)` moves it.** Every file a step writes is dated at the clock's time, a microsecond after the file before it at the same time, so reading order follows writing order and the mark columns are a function of the script. The scenario's own definitions are a step of their own, rendered where the first of them is written, so a `@withFacts` above them is in the folder first and the scenario's SDL sees what it pulled in.
* **Every render is followed by a capture** at the clock's time, warm after the first, and each claim is verified once, as it arrives, against the capture before it: its rows become a derived table, set operations against the relation decide it, and what they return is the divergence. Setup, execute and verify interleave as the script reads, and incremental capture is that loop. The sweep sees what an edit and a rebuild produce, because that is what it is. A scenario is one graph, named by its path.
* **The engine owns its directives, and the prelude dissolves.** `@withFacts`, `@tick`, `@withConfiguration` and the claim directives are never rendered: the engine blanks them in place, so a defect at line 17 of a document is at line 17 of its rendered file, and the store holds the example alone. Their declarations stay in `_prelude.graphqls` for an editor and the documentation renderer; nothing is prepended to a document any more. A document states its own example whole, `type Query { ... }` rather than extending an invisible root, and the few with no query field of their own (catalog and configuration readings) pull in `layouts/anchor`. `@withConfiguration` is a step of the scenario's own script, repeatable: each capture runs under the last one stated above it, so a scenario can change its configuration between readings but never its catalog, and in a pulled-in document it is ignored. A document with nothing to render, claims or configuration alone or an emptied version, writes no file and takes away its earlier version, its step still a reading of the files that stand. A claim in a document and a claim in an assertion file are the same input, decoded as text.
* **Duplicates compose.** A type the folder already defines, defined again by another document compatibly, is rendered as an extension: its keyword gains `extend`, and the fields and directive applications it repeats identically are blanked, so documents can each state `type Film @table(name: "film")` and still compose. A definition that disagrees is rendered as written, for the capture to see; that is how a scenario states a conflict on purpose, and an unintended one fails the scenario's folder claim. A type repeated within one document is that document's statement and is never composed.
* **One reading, by construction.** The population, `FactExpectationTest` and `GenerateTestSchemas` all execute the same rendered directory.
* **A folder's claim is judged on the final reading.** A scenario generates and stores no defect at its end; a defect document stores one at its end. A defect at an intermediate step is stated by a claim at that step. The population runs each scenario once, verifying its claims as it goes and recording what they found, and hands out the final reading; `FactExpectationTest` asserts the record.
* **Rendered directories live under `target/`**, one per scenario, rewritten in place per step so a capture sees one corpus changing over time, and kept after a failure so the failing scenario's expanded schema can be read.

This replaces the stepped files (`<graph>.<file>.<epoch>.graphqls` under `incremental-captures/`), whose 19 graphs become scenarios sequencing their versions with `@tick`, and it is how larger realistic schemas are built: a scenario pulling in many layouts is one generated package for execution tests and the manual, and it still owns its whole graph, so a whole-population claim means what it says.

Folders: `scenarios/` and `defects/` hold scenarios, judged on their final reading; `undecided/` makes no claim and only shrinks; `shapes/` and `layouts/` hold building blocks no folder claim applies to; a building block nothing pulls in fails a floor, and so does one that states a claim, since expansion would strip it unread.

### Identity

Settled once, before anything new names a document. A **document** is a file, identified by its path under `facts` without the extension: `scenarios/field-key-row`, `shapes/film-key-summary`. A scenario's graph is named by its path. `@factDocument(path:)`, `@withFacts`, `-Dfacts=<prefix>` and every assertion file use paths, all resolved by `FactDocuments.at(path)`, and names stay unique across the tree, as `FactDocuments.claimed` already insists. A document a move renames is updated wherever it is named in the same commit; the floors of `ManualExampleTest`, `@withFacts` and the assertion files fail on a reference left dangling.

### Claims and their scope

A scenario owns its whole graph, its own SDL and everything it pulls in, so a claim over a relation's whole population is well defined. One rule keeps a claim independent of its neighbours in the population: **a store-wide relation is claimed additively.** A relation with no `graph_name` (the `sql_`, `code_`, `meta_` families and a few others, 76 in all) holds what every capture into its store wrote, so a document may say a row is there or is not, never what all of it is. Graphs share a store only when they share a catalog and a classpath, so those rows are the same in every store a graph can land in.

### Expectations beside a document

A test states expectations about a document without editing it in an **assertion file**: SDL naming its document with `@factDocument(path:)`, decoded as text, verified like the document's own claims.

* **Store claims beside a document are additive only.** `@assertContains` and `@assertAbsent`; `@expectEquals` and `@assertEmpty` state what the document's graph is, which is the document's own statement, and the manual renders a document's own blocks.
* **Answers above the store live in the module that answers them.** graphitron-model cannot see the generator, the editor or the MCP server, and the language server and the MCP server may not depend on `graphitron` (`StoreClientBoundaryTest`). Each module keeps its files under `src/test/resources/fact-assertions/`, declares the directives it reads, and runs them from one `@TestFactory` class.
* **The kinds**, each added when a module's migration shows a repeated shape, never ahead of it:
  - editor answers (graphitron-lsp): hover, completion, definition, references, inlay hints, diagnostics and code actions at a cursor given as a line and a needle in the document's rendered file, with an optional edit for a mid-edit case;
  - MCP answers (graphitron-mcp): a tool or resource with its arguments, answered as a JSON subset, or an error whose first line starts with a prefix;
  - emitted schema (graphitron-model): the description, tags, applied directives and type of a coordinate;
  - generated structure (graphitron): that a generated type or method exists with a signature. Method bodies are out of reach by definition: asserting generated Java text is banned, and a body is pinned by executing it.
* **Defects are claimed as rows, and their prose is pinned once per defect type**, by a coverage gate over `graphitron_defect_type` with a falling ceiling for types no document draws yet.
* **Floors**: an assertion file naming no existing document fails.

What stays a Java test, reading a document through the population: generated behaviour, statement counts, plans, timing and read budgets, store integrity, lifecycle beyond what a scenario's steps state, deep navigation, and cases that inject synthetic diagnostics. Invariants that hold of any captured graph run over the whole population.

### The population: `FactGraphs`

A class in graphitron-model's test harness, published in the test-jar that graphitron, graphitron-lsp, graphitron-mcp and graphitron-maven-plugin consume. Its own class rather than a member of `FactStores`, whose stated design is that it never puts rows in a store. It opens stores through `FactStores.inMemory(StoreUse.LONG_LIVED)` and captures through `FactDocuments`, the one reading `FactExpectationTest` checks and `GenerateTestSchemas` generates from.

* **Captured once per JVM, lazily, per graph**, in JUnit's root `ExtensionContext` store as an `AutoCloseable` closed at the end of the run. `FACTS.captures(path)` stays at one however many classes ask.
* **Sharded within catalog and classpath.** Graphs share a store only when they share a catalog and a classpath, so a store-wide relation holds the same rows in every store a graph can land in. Within each such key there are four long-lived stores, a graph assigned to one by its name, each with its own lock, so captures keep the four-way parallelism they have today; nearly every document shares the default catalog and classpath. Four is a constant of `FactGraphs`, not read from `junit-platform.properties`, so it does not depend on which properties file a module sees. Configuration is graph-keyed (`store_graph_*` rows), so graphs of different configurations may share a store.
* **Read-only, by the database**: readers connect as `GRAPHITRON_READER`, which every store's DDL creates with `SELECT` and nothing else.
* **Handed out in a transaction.** A test declares `@RegisterExtension static final FactGraphs.Reader FACTS = FactGraphs.reader();`, the shape `FactStores.perClass()` and graphitron-mcp's `StoreFixture.held()` already have, and reads with `FACTS.read("scenarios/field-key-row", graph -> ...)`. The body gets a `FactGraph`: the `StoreHandle` scoped to the graph and the `CapturedSchema` the capture built, which a generating run needs beside the store. There is no parameter resolver handing out a bare handle: `StoreReader` makes its transaction the only way onto a reader, so an answer built from several queries sees one commit, and production readers take the same shape (`CatalogQueries`, `CatalogCorpus`).
* **`FactExpectationTest` reads every scenario's final reading from the population**, warming it with one thread per shard, so in graphitron-model the graphs it checks and the graphs other tests read are captured once between them. A scenario with steps is replayed in a store of the test's own to verify the claims at each step, an intermediate state the population does not keep.

### Authoritative tests

Every behaviour has one **authoritative test**: the one place that pins it, which a reader asked "what makes this true, and what proves it" can name without searching. The behaviours are what the model says an example means: the **claims** elements make (`graphitron_claim`, its kinds in `graphitron_claim_kind`: `SERVICE`, `LOOKUP`, `NODE_ID`, `TABLE_TYPE` and the rest), the **defects** a reading finds (`graphitron_defect_type`), and the **occurrences** an element has along a chain (`graphitron_occurrence`). The rest of the store is how the generator gets there.

**The reference documentation's examples are the keystone.** A manual page selects a scenario with `@factDocument(path:)` and shows its claimed rows with `@factRows(relation:)`, and `FactExpectationTest` holds those rows to a real capture, so for a behaviour the chain runs from the page that says what it is, to the scenario it shows, to the scenario's claims, to the generated package executed. The keystone scenario is the behaviour's authoritative test; a translation above the store (an editor diagnostic, an MCP answer) has one authoritative test per shape of translation, reading the keystone; generated behaviour's is an execution test over the scenario's generated package. Every other document pinning the behaviour is a different case of it, which its header names, or it merges into the keystone, usually by pulling the keystone in with `@withFacts` and stating only its variation. Today the manual names 14 documents from 10 of its 35 directive pages, so most behaviours have no keystone yet, and writing a page's example is part of deduplicating toward one.

**Duplication is found by asking, per family, who pins what.** A document pins a behaviour when its claims assert rows of it; containing a behaviour is not pinning it, since every scenario contains `Film`'s `TABLE_TYPE` claim. One query per family, each returning `(behaviour, document)` for the documents whose claims assert it, run per store through the reader and united across stores:

1. **claims pinned:** the claim kinds a document's claims on the claim relations assert;
2. **defects pinned:** the defect codes, with the kind of site, a document's claims on the defect relations assert;
3. **occurrences pinned:** the occurrence kinds and depths a document's claims on `graphitron_occurrence` and its derivatives assert.

From those, two gates of the kind that only fall and one report:

* **duplicates:** a behaviour pinned in the same shape by two documents outside its keystone, counted under a ceiling and reported by behaviour, naming the keystone where there is one and the documents to merge;
* **the same example:** two scenarios whose rendered graphs hold the same declared shape (types, fields and arguments at their types, directives applied, backing, result shape, defects, configuration), a scenario with nothing beyond what every scenario holds exempt;
* **behaviours without a keystone**, reported rather than gated, ranked by how many documents pin each, so documentation work goes where examples pile up without a page.

A whole-graph similarity measure is not one of them: it answers how alike two documents are, which is not the rule, and its threshold would be a number nobody decided.

Every other test that touches a behaviour relies on its authoritative test rather than restating it. A test whose assertions are all pinned elsewhere is deleted, naming the authoritative test that covers each one. Duplication between Java tests has no mechanical detector, since equal assertions are spelled differently; it is found by the pipeline's triage, which clusters capture sites by the behaviour they pin before it rules on any one of them, and checked by its coverage review.

### What keeps a store of its own

A test that writes after capture (a recapture, a second graph, hand DML, a fact writer, DDL), reads an intermediate state no claim states, has a file-backed store or warm start as its subject, exercises a capture's failure path, counts or times captures or boots, or tests one capture stage in isolation. It still takes its schema from a document, run by the scenario engine into a store it owns (`CapturedStore.captureFolder`). A test counting the statements a read issues writes nothing, so it reads the shared population through a handle whose configuration counts them, as `RoutineWriteQueryStatementCountTest` does. So does a test of the same document under a configuration or classpath its scenario does not state (lint configuration, page size, `TypeBackingPartitionTest`'s four classpaths). Examples from the inventory: `WarmStartRefreshTest`, `PersistentStoreTest`, `CaptureCommitsAsItGoesTest`, `WriteTimesTest`, `DiagnosticFactsTest`, `BrokenSourceStillCapturesPipelineTest`, `StoreBootCountTest`, the LSP's `*StatementCountTest` classes until each counts over the population, the `StoreOutOfBudgetTest` classes, and the stage-capture tests over `ThreadConfinedStore.run`. The ceilings are set with these counted.

### Out of scope

The classification walk's tests, `GraphitronSchemaBuilderTest`'s truth tables and the roughly 160 `TestSchemaHelper.buildSchema` tests, capture nothing and retire with the walk under its own item. The classification corpus and `CorpusStore` retire with it too; nothing here builds on them. `SeededStore` dissolves under its own gate, `SeedingDissolutionGateTest`.

## Placement and evolution guideline

The first client of the design: this is the section `testing.adoc` gains, drafted here so that if it does not read simply, the design changes before implementation.

> **Where an example goes.** The folder states what the store does with a scenario at its end: under `scenarios` it generates and stores no defect, under `defects` it stores one, and `undecided` only shrinks. `shapes` and `layouts` hold building blocks other documents pull in, which no folder claim applies to. Within the folder, choose by subject: a scenario pins one claim, defect or occurrence behaviour, is named for it (`field-key-row`, `node-id-decode-unresolved-type`), and opens with a header saying what it pins and why each type is there.
>
> **Authoritative tests.** Each behaviour has one authoritative test. The reference documentation's examples are the keystone: the scenario a manual page shows for a behaviour is its authoritative test, its claims the rows the page displays. A translation above the store (an editor diagnostic, an MCP answer) has one authoritative test per shape of translation; generated behaviour's is an execution test over the scenario's generated package. A test elsewhere relies on the authoritative test instead of restating it, and asserts only what its own layer adds.
>
> **Look before you add.** Find the behaviour's keystone first, from the reference page. If it exists, extend it or rely on it, or state a different case of it by pulling the keystone in with `@withFacts` and adding only the variation; a second test of the same behaviour is a defect in the suite, and the newer one is deleted. If there is no keystone, the scenario you are writing is the candidate, and the page's example is where it belongs.
>
> **Set up with building blocks.** Open a scenario with `@withFacts(docs: [...])` pulling in the layouts and shapes it builds on, then state only what its subject adds, extending what it pulled in. A setup repeated across scenarios is a shape waiting to be extracted. A defect document is a minimal pair: the rows it draws, and a near miss that draws nothing. To read a change, move the clock with `@tick(ms:)`, write the next version with `@withFacts`, and claim what the store holds after it.
>
> **Claim it in the document.** What the store holds for the example is claimed in the document, after the step it is about. Expectations that are not store rows, or that a test adds without changing the document, go in an assertion file in the module that answers them, naming the document with `@factDocument(path:)`.
>
> **Improve as you go.** Split a document that pins two subjects; merge documents that state the same example; move a document out of `undecided` into the folder whose claim it meets; never weaken a claim to make room for a case, give the case its own document instead. A document's path is referenced by the manual and by assertion files: a move updates them in the same commit.
>
> **Keep a store of your own only when the store is the subject.** A test that writes, times or fails a store captures its document into a store it owns. Everything else reads the shared population.

## Implementation

Four phases, each landing on its own and keeping the build green.

1. **The population, the identity and the gates.**
   - Shipped: a plain document captured as its prelude and itself, two files of one graph, at `2d2b05a`; documents identified by path at `ebf9d64`; the `FactGraphs` population, sharded within catalog and classpath, `FactExpectationTest` over it and the whole-store claims made additive, at `6fe6604`; assertion files at `a9871b7`; the five `InlineCaptureGateTest` ceilings (graphitron 330, graphitron-lsp 146, graphitron-model 89, graphitron-mcp 29, graphitron-maven-plugin 19) at `aeaa76b`. The name-based duplicate gates of `b32a8e6` are replaced by the same-example gate below.
   - Shipped: the same-example gate over the declared shape, as one query, replacing `b32a8e6`'s name comparison and its near-twin ceiling, at `28ec6f1`.
   - Shipped: the emit tests move onto scenarios, in the batches the triage set, at `89e3f5c`, `097c131`, `25d025c` and `e446f4c`, lowering graphitron's ceiling from 330 to 294: the query and execution tests reading `field-key-row`, `occurrence-call-parameter`, `occurrence-producer`, `multitable-interface` and the new `service-lift`, `service-input-build` and `node-id-decode-record`; the emitter tests reading their rows from those, the stub classes they name moving into `graphitron-sakila-service` where a capture can name them; the routine-write tests. The first batch has landed: its tests read the documents through `FactGraphs.reader()`, the two that only restated a claim dropped it, the node-id refusals that stand on recorded defects read `defects/polymorphic-node-id-decode-containers` beside the new scenario, and `LiftEmitterPipelineTest` stays as the lift check the no-PostgreSQL inner loop runs; no batch-one setup was shared enough to be a shape. The second batch's emitter tests read their frames, fills and builds the same way, the classes a document names moving into `graphitron-sakila-service` (`FillShapeService`, `FilmMood`, `FilmSurvey`). Where the model or the generator refuses a shape the emitter handles, the input stays a literal and the test says why: a node id decoded into a service parameter other than the node's own record is a defect in the store, the own record and three ids filling one member are refused by the generator, and the decodes those tests use are still read from `node-id-decode-record`. `CallEmitterTest` reads `service-call-shapes`, its services moving into `graphitron-sakila-service` as `CallShapeService` and the two holders; a checked exception passed through stays stated, since the generator refuses one no `@error` handler covers. `ServiceEmissionCensusTest` keeps both captures: the census's subject is the sakila example's own schema, and its call refused under a table is one the store records no defect for while the generator refuses it, so it fits neither `scenarios` nor `defects`. `testing.adoc`'s unit-tier blessing of record literals narrows to inputs with no store origin. The routine-write tests waited on the store stating a routine write's refusals itself, which it now does, and `RoutineWriteQuery` admits a write on the re-read its occurrence states: `RoutineWriteQueryTest` and `RoutineWriteAdmissionTest` read `scenarios/routine-write`, the refusals each pinned by a defect document the admission test names, `RoutineWriteQueryStatementCountTest` counts over the shared population, and one inline capture remains, for the shapes the store admits, the walk refuses and the emitter does not render yet.
   - Shipped: docs and skills, first version, and the two capability skills deleted. `testing.adoc` carries the placement and evolution guideline, the claim vocabulary and the store-harness table with `FactGraphs` its default; the walk's truth tables, the classification corpus, `TestSchemaHelper` pipeline tests and the validator family are closed to new cases there rather than taught, and `SeededStore` is stated as closed and dissolving. The guideline's wording moved from this item's draft where it met what shipped: a document has one subject, what one page example shows, rather than one behaviour, since a keystone stands for several; a second document of a case names its case or merges into the keystone; and a store of one's own is kept also for a document captured under a configuration or classpath its scenario does not state. The `fact-documents` skill holds the six-stage procedure and links the page for every rule.
2. **The scenario engine.**
   - Shipped: the renderer (`@withFacts(docs:)` and `@tick(ms:)` declared in the prelude, expansion that strips claims and follows `@withFacts`, the clock, blanking that keeps lines, the rendered directory under `target/`), at `6db3b0c`; every document runs as a scenario through the production capture, its claims verified from text at the step they follow and its folder claim on the final reading, and the prelude dissolves, at `1f03a21`; the 19 stepped graphs become scenarios and the stepped arm goes (`FactDocuments.write`, `readAt`, the `STEP` pattern, `incremental-captures`), at `6857668`; the floors for a building block nothing pulls in and one that states a claim, at `4c125d2`.
   - `CapturedStore`'s multi-file arms (`ofFiles`, `ownStoreOfFiles`) no longer carry any document, but inline tests still call them; they retire with the module steps of phase 4.
   - Layouts are extracted where a conversion shares setup, not ahead of a consumer. None has yet: `layouts/` holds only `anchor`, and no emit batch shared enough setup to be one. `Film` is declared in 141 graphs, in 58 field sets, so the pressure is there for phase 4.
3. **Keystones and pins.**
   - Shipped: each positive claim pins the store rows it matched at its own step, read back to its family's anchor, and a family is the relations `meta_relation` declares at the anchor's grain that spell its key as the anchor does; the duplicate gate holds the pins beyond one per behaviour, at `f40c894`. The keystone report, which names the behaviours each page shows, those none does ranked by how many documents pin them, and the documents pinning no family, with the "Authoritative tests" section of `testing.adoc`, at `2da1140`. A schema problem pins into the defect family from its own anchor, and the defect documents claim the defect they are about, at `4d970f6`; four documents whose only defect was scaffolding are scenarios, at `cb99c92` and `9a98968`. Path claims pin nothing until the report shows whether path documents repeat one another (Alf, 2026-11-10).
   - The keystone for each behaviour the manual already shows, read from `@factDocument(path:)` and `@factRows(relation:)`: 15 of 197 behaviours (2026-11-11), all occurrences, from `scenarios/occurrence-model` and `scenarios/producer-kinds`.
   - Left: 130 documents pin no family (2026-11-11). Two defect documents state their verdict in `intent_argmapping_projection_defect` rather than as an entry defect, and five keep a fault that is their case or whose removal would change what they record.
4. **The migration, module by module**, through the pipeline below: graphitron-model, graphitron, graphitron-lsp, graphitron-mcp, graphitron-maven-plugin. Each module step adds the assertion kinds its tests show a repeated shape for, lowers its ceiling, and retires the `CapturedStore` arms and `TestSchemaHelper` methods it empties. The defect-type rendering gate lands with graphitron's step.
   - graphitron-model, batch 1 (`StageAnswerAgreementTest`, `CoordinateSpellingGateTest`, `EmittedRegistryTest`, `GraphitronStoreTest`, `TypeBackingPartitionTest`, `DirectiveAnchorKeyTest`), ceiling 89 to 69:
     - `StageAnswerAgreementTest` compares every stage table with its rule view over the whole population rather than four fixtures, and its non-vacuity floor holds every scope basis as a set, which replaces its three per-basis cases. Only the configured-tag reading, which no document can state, keeps a store of its own. Over the population the defect stages proved to share one table, so each defect rule's rows are held and every held row of their owners' codes is one some rule computes; that the graphitron rule emits route codes another stage owns is filed as R1174. Negative-tested: a field-scope writer dropping its participant rows and a defect writer dropping one code each fail it.
     - `DirectiveAnchorKeyTest`'s hand-delete cascade cases are replaced by `scenarios/removed-directive-definition`, which drops an argument and then the directive across readings. Without the three `ON DELETE CASCADE` clauses, it fails at the sweep, while the rest of graphitron-model's 569 tests passed without them.
     - `TypeBackingPartitionTest` stops reading `intent_type_backing_conflict`. Its "both" rows already show the contest.
     - `EmittedRegistryTest` reads its graphs from documents: `scenarios/connection-mint`, moved out of `undecided/` once a stray field that drew a defect none of its claims were about was removed; new scenarios for a carrier on an extension, a tag beside a declared `PageInfo`, and tags two carriers share; and existing scenarios and defect documents for the federation keys and the unpublished directives. Its registry is read from the graph's own capture, and the boot-budget workaround is gone.
     - `GraphitronStoreTest` stays whole: its subject is the run's entry point, which the population does not go through.
     - Constraint tests are not moved to a SQL spec tier (a pgTAP-shaped runner over a bare store was weighed). Its largest customer, `CoordinateSpellingGateTest`, dissolves instead under R1173, which makes the coordinate a generated column the database computes. The remaining few refusal cases (`DirectiveAnchorKeyTest`'s two foreign-key refusals, `OccurrenceSourceConstraintTest`) test that H2 enforces a declared constraint. Whether a constraint no schema can reach owes a test at all is open for Alf and Filip.
   - graphitron-model, batch 2, ceiling 69 to 41. Alf ruled that store mechanics are not by themselves a reason to keep an inline schema (2026-11-12): timings, statement shape, plans, a failing capture, several graphs in a store, a classpath and a tag are each either facts a document claims, checks over the population, or a store of a test's own fed from documents (`CapturedStore.ownStoreOfDocument`).
     - `CapturedGraphInvariantsTest` holds what is true of every captured graph, per graph over the whole population: anchors pairing with their attributes, source rows and mtimes, writes timed under their owner gatherer, reads timed, every gatherer's progress completed. Six tables are timed under a gatherer other than the one `meta_relation` names; they are listed both ways until the owners rule which side moves.
     - The population's stores record H2 query statistics, so `UpsertKeyGateTest` and `CaptureStatementTextTest` gate every capture in the run, at about 3.5% of the module's time.
     - Graph isolation (`FieldGraphScopeTest`, `NodeGraphScopeTest`, `ModelCaptureMembershipTest`) and `GraphCountGrowthGateTest` keep stores holding chosen graphs, run from documents. The population does not cover isolation: it divides graphs between stores by catalog and name hash, so an uncatalogued graph never shares a store with a catalogued one, and which graphs meet is luck.
     - Duplicates deleted: `GraphitronSiteAnchorAgreementTest`, `GraphQLAnchorTest`'s redeclared `@oneOf` case. `MacroExpansionLifecycleTest` became claims and `scenarios/minted-element-sweep`.
     - Found and filed: R1175 (a rewritten file storing one directive application twice, which keeps `GraphQLAnchorTest`'s count case inline) and R1176 (an edge naming an item type a later file declares fails the capture on a foreign key).
     - Scenario engine gap: a step that pulls in a document defining a type while rewriting another file to drop that type composes the pulled-in definition as an extension, against the file's previous version, and so leaves an extension with no base. The engine should decide composition against the folder as the step leaves it. `scenarios/minted-element-sweep` pulls in `layouts/anchor` from its first reading to stay clear of it.
     - Left at batch 2: plan gates keep the scaled fixture until a plan over the population is shown to choose the same index; the tag and note tests wait on per-file schema input in `@withConfiguration(schemaInputs: [{file:, tag:, note:}])`; the classpath tests wait on classpath fixtures built by Maven and named in `@withConfiguration`; `CoordinateSpellingGateTest` is gone, the columns it checked being generated (R1173).
   - graphitron-model, batch 3, ceiling 41 to 19 with R1173, which removed `CoordinateSpellingGateTest` (25 on its own). A document gives a rendered file a configured tag or note with `@withConfiguration(fileAnnotations: [{file:, tag:, note:}])`, which moved the tag and note tests onto `configured-tag`, `configured-note` and `federation-authored-tag-configured`. A document names a classpath with `@withConfiguration(classpath: "<fixture>")`: the fixtures are compiled by Maven in `graphitron-sakila-service` (the library twice, with and without `-parameters`, each jarred), `ClasspathFixtures` only arranges their roots, and a store's key is its catalog, its fixture and its shard. `CodeReferenceClasspathScopeTest` and `TypeBackingPartitionTest` read documents, and the latter no longer compiles a library with javac.
     - The population costs memory: about 95 MB a store, every store held for the fork, and the four fixture stores tipped the module over the default heap of a 16 GB machine. graphitron-model's surefire pins `-Xmx5g`. Fewer, smaller stores is the lever when this comes back.
     - Found: a graph's type backings depend on what was captured into its store before it, filed as R1182 and absorbed into R876's node "A graph's `code_` answers are a function of its own entries". `TypeBackingPartitionTest`'s old assertion held only because of that history; the document claims the answer the graph gives alone. Alf ruled (2026-11-12) that graphs on different classpaths share entries and stores, so the classpath shard key, the fixture compiled twice and the heap pin are a stopgap that node's last child removes.
     - Left: plan gates keep the scaled fixture until a plan over the population is shown to choose the same index; `GraphQLAnchorTest`'s count case waits on R1175.

### The migration pipeline

The migration runs as a pipeline of agents, carried by one skill (`fact-documents`, in `.claude/skills/`, where agent procedure already lives) that links the placement guideline in `testing.adoc` rather than holding a second copy of it. Per batch of test classes, each stage a different agent:

1. **Cluster**, read-only: the module's capture sites, and the documents and tests in other modules that pin the same behaviours, grouped by behaviour, so a duplicate is seen as one before any site is ruled on.
2. **Triage**, read-only and parallel: one row per capture site with its verdict (claims in a document, an assertion file, a Java test reading the population, a store of its own, inline because its defect is not yet stored, or deleted as a duplicate, naming the authoritative test that already covers it, by `file:line`) and its target document. The session rules on the table before anything changes.
3. **Author**: documents and assertion files, grouped by target document so no two agents edit one file, each checked with `FactExpectationTest -Dfacts=<prefix>`.
4. **Convert**: test classes moved onto the population; Java assertions that became claims deleted; every new claim negative-tested by breaking it, seeing it fail and restoring it.
5. **Coverage review**: a different agent compares each deleted assertion with what replaced it or with the cover triage named for it. This is review, not a gate: each new claim's two-way check and its negative test are enforced, the completeness of the replacement is judged.
6. **Verify**: one build-running agent at a time, per the `delegate-to-subagents` skill; the module's ceiling drops when the batch lands.

### Surfaces this rewrite touches

* **`docs/architecture/how-to/testing.adoc`**, keeping its path (linked from 12 roadmap items, the manual and `ArchQuadrantBindingTest`) and the heading "Where a store-backed test gets its store" (cited by the `nested-jooq` and `store-performance` skills):
  - "Choosing a tier": the pipeline answer becomes a document and a reader of it;
  - "Unit tier": the record-literal blessing narrows; "Three sub-families" lists four;
  - a new section, "Authoritative tests", stating the concept, where each kind of behaviour's authoritative test sits, and what a test at another layer may still assert; "Choosing a tier" refers to it, since tier answers how far a test runs and the authoritative test answers whether it should exist;
  - "What a test is built out of": becomes the guideline above, and loses the claim that the classification corpus renders into the site, which no page does any more;
  - "Pipeline tier": the full claim vocabulary (`@expectEquals`, `@assertContains`, `@assertAbsent`, `@assertEmpty`, `where:`, `@withConfiguration`), `-Dfacts`, assertion files;
  - "Execution tier": the folder claims move into the guideline; a scenario's generated package is what execution tests read;
  - "Where a store-backed test gets its store": `FactGraphs` as the default row, `CapturedStore` narrowed to a store of one's own, `SeededStore`'s row gone, the lsp and mcp `StoreFixture` row restated.
* **`development-principles.adoc`**, "Behaviour is pinned at the pipeline tier and above": a new feature earns a fact document first. The file is at its 3,500-word budget (`DocSizeBudgetTest`), so the change is net zero.
* **`fact-model.adoc`**'s "Enforced by" paragraph and `defects-as-facts.adoc`'s closing step link the guideline; `index.adoc` and `how-to/index.adoc` describe the page's wider scope.
* **CLAUDE.md**, "Building and testing": one line, a store-backed test starts from a fact document, with the pointer to the guideline.
* **Skills**: `nested-jooq` (it teaches `SeededStore` three times, against `SeedingDissolutionGateTest`), `store-performance` (its `SeededStore` mentions), `explain` (a corpus path that no longer exists), `adding-a-relation` (a step that pins the relation in a document), `reviewer-prompt` (a schema stated inline is a finding), `build` (`-Dfacts`), and the new `fact-documents` skill. The `capability-catalog` and `capability-interview` skills are deleted in phase 1: they served the capability catalog, discarded with its only consumer because this item carries its intent, and they describe a catalog and two directives (`@capability`, `@exemplifies`) that will not exist. Nothing else in the tree reads them; the roadmap audit and the changelog that mention them are records.
* **Guard bookkeeping**: `ArchitectureDocSymbolGuardTest` resolves every class the architecture docs name, so a doc names `FactGraphs` or `InlineCaptureGateTest` only in the commit that adds it, and dropping the last citation of one of its 12 exempted names deletes the exemption in the same commit. `SchemaIdentifierDriftCheck` holds relation names in examples live. `RetiredVocabularyGuardTest` reads documents and docs alike.

### Coordination with other items

* **R876** deletes `SeededStore` and the classification corpus, and plans emitters "unit-tested from captured fact documents". This item delivers that half and describes neither deletion as done.
* **R1163** rewrites `fact-model.adoc`'s "Enforced by" paragraph; whichever lands second rebases on the other's wording.
* **R1131** step 7 would move suites into separate test modules and update `testing.adoc`. Where both are open, this item owns `testing.adoc`'s structure and step 7 restates module locations into it.
* **R764** settles which `junit-platform.properties` each module sees. `FactGraphs` reads nothing from it, so the two are independent.
* **R851** retires the corpus's verdict directives and is affected by the corpus retiring, not by this item.

## Tests

* `FactGraphsTest` in graphitron-model:
  - a graph asked for from several classes on several threads is captured once;
  - an `INSERT`, `UPDATE`, `DELETE` or DDL statement inside `read` is refused by the database;
  - two documents of different catalogs never share a store, a store-wide relation reads the same rows in every shard of one catalog and classpath, and a whole-population claim on a store-wide relation is refused;
  - a path naming no document is refused, naming the shape a path takes.
* `FactExpectationTest` over the population, with: the assertion-file floor; refusal of `@expectEquals` and `@assertEmpty` in an assertion file; the same-example gate, with a negative case.
* The scenario engine: expansion pulls in documents transitively, refuses a cycle, writes a file once, strips claims and blanks them so lines are kept; `@tick` dates files, and a pulled-in document's `@tick` is not read and only its last version of each file is written; a step's claims see that step's reading and folder claims the final one; a building block nothing pulls in, or one that states a claim, fails its floor; the 19 former stepped graphs pass as scenarios.
* The pin queries and the duplicate gate, with a negative case per family, and the keystone report.
* The defect-type rendering gate: every row of `graphitron_defect_type` renders from a document that draws it.
* Each module's assertion runner, with one negative case per kind.
* The five `InlineCaptureGateTest`s. The item is complete when every remaining inline capture is a schema whose defect the store does not yet record, named beside its ceiling, and every test listed under "What keeps a store of its own" captures a document.

## Evidence

Each phase and module step reports in its commit message the affected test phases before and after, in interleaved pairs measured the way the `build-profile` skill describes, and `FactStores.captures()` at the end of each module's run, and counts the tests deleted as duplicates and the documents merged. Phase 2 also reports `generate-test-schemas` before and after.

## Retired vocabulary

Listed as the migration empties them, for the sweep at Done: the `CapturedStore` arms and `TestSchemaHelper` methods left without callers, and `TestSchemaHelper`'s `EMITTED` memo, which exists so per-text captures stay under `ThreadConfinedStore`'s boot budget. "Plain document captured as one file" goes in phase 1: a plain graph is two files from then on. So do the `capability-catalog` and `capability-interview` skills, and with them "capability catalog", `@capability(name:)` and `@exemplifies(capability:)` outside the changelog and audits. Phase 2 retires the stepped arm: the `incremental-captures` folder, the `STEP` file-name pattern, "stepped graph", `CapturedStore.readAt` and `andCatalogGraphReadAt`, and `FactDocuments.write`, the render taking their place.

## Other solutions we've considered

* **Worlds**, folders of member documents captured as one graph over a shared base. Rejected: a member's claims would see its siblings' rows, so whole-population claims needed a `where:` scope and claim stability became a risk; scenarios pulling in building blocks give the same larger schemas while each scenario owns its whole graph.
* **An `@include` directive.** Rejected for its name: `@include(if:)` is a built-in GraphQL directive and graphql-java refuses a redefinition. `@withFacts` also carries the setup's file naming and sits in a script with `@tick`.
* **Stepped files named `<graph>.<file>.<epoch>.graphqls`.** Rejected: a step's claims were found by the file that wrote them rather than stated beside the step, and the time sat in a filename. A clock moved by `@tick` in the script states both.
* **Claims captured into the graph they are about.** Rejected: they put rows about the claims themselves into the store a fingerprint and a claim read, and a claim in a document and one in an assertion file were two different inputs. Verified from text, both are one.
* **A whole-graph similarity measure for near-twins.** Rejected: it answers how alike two documents are rather than whether a behaviour is pinned twice, it names no reason an author can act on, and its threshold is a number nobody decided. Pins per family state the rule.
* **Store claims of every mode in assertion files.** Rejected: an `@expectEquals` beside the document would be a second statement of what the document's graph is, union silently with the document's own, and vanish from the manual, which renders the document's blocks.
* **A declarative rendered-defect kind per document.** Rejected: it copies the renderer's prose across every case. Defects are claimed as rows and the renderer is pinned once per defect type.
* **A fixed number of shards across all documents, by name alone.** Rejected: which catalog rows sit beside a document would depend on test order, and store-wide claims with it.
* **One shard per configuration and catalog.** Rejected: nearly every document shares the default, so its captures would queue behind one writer where four threads capture today.
* **A parameter resolver handing a test a bare `StoreHandle`.** Rejected: a handle outside a `StoreReader.read` transaction needs a bare reader connection, which `StoreReader` exists to withhold.
* **One store for every document, or one store per document.** Rejected: the first serializes every capture on one writing connection; the second costs about 30 MB per long-lived store for a few hundred stores in one JVM.
* **Generalising `CorpusStore`.** Rejected: it retires with the classification corpus, and its unwritten-store check compares a census taken once, which a lazily filled population does not keep.
* **A saved workflow script for the migration pipeline.** Rejected: agent procedure lives in skills, and two homes drift.
* **Migrating every test and deduplicating afterwards.** Rejected: a duplicate migrated is paid for twice and becomes harder to see once both copies read the same document. Triage clusters first.
* **A mechanical detector for duplicate Java tests.** Rejected: equal assertions are spelled in too many ways for a gate to see them without flagging honest neighbours. Documents can be compared mechanically, and are.
* **Capability as the placement axis.** Rejected: the directives and catalog it needs do not exist. Directive cannot partition either, since 184 documents use `@table`; it stays a cross-index the manual already uses.
