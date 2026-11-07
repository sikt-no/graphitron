---
id: R1160
title: "Every schema a test captures is a fact document, and the test strategy is rebuilt on them"
status: In Progress
bucket: dx
priority: 5
theme: testing
depends-on: []
created: 2026-11-06
last-updated: 2026-11-07
---

# Every schema a test captures is a fact document, and the test strategy is rebuilt on them

## Goal

Every schema a test captures into a fact store is a fact document, and the reactor's test strategy is rewritten around that. A fact store is the H2 database the generator, the language server and the MCP server read their model from; a fact document is an SDL example under `graphitron-model/src/test/resources/facts`, sitting in a folder that states a claim about it (a scenario generates, a defect document stores a defect) and carrying claim blocks, `extend schema` applications such as `@expectEquals(relation:, rows:)` that pin the rows the store holds for it, which `FactExpectationTest` checks. The premise is that a schema worth stating to a test is worth stating as an example whose claim is checked, whether a test captures it once per class or once per case, and whether it then only reads the store or goes on to write it.

When this lands, a contributor or an agent adding a test starts from the example: they find the document that states the subject, or add one where the placement guideline below says it belongs, and pin what the store holds for it as claims. A test whose subject sits above the store (generated code, an editor answer, an MCP tool) reads that document's graph from a store captured once per test JVM, through a connection that can only select, and states its expectations beside the document without editing it. Documents compose into larger graphs, worlds, where their subjects share a configuration, so the examples exercise each other the way a real schema does and the generator produces one package per world that execution tests and the manual both use. The contributor guide, the agent-facing skills and CLAUDE.md teach this, and per-module ceilings that only fall hold the reactor to it.

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

### A graph is a set of files

The capture unit generalises to the shape the stepped arm already has: a graph is a set of source files, optionally versioned by instant, captured through `CapturedStore.andCatalogGraphReadAt`, its configuration read across all its texts by `FactDocuments.StatedConfiguration.of` (which already refuses a second `@withConfiguration`), and its claims selected by the file that wrote them (`CorpusExpectations.blocksWrittenIn`). Three readings of one shape:

* a **plain document** is a graph of two files, `_prelude` and the document;
* a **world** is a graph of `_prelude`, a base file and its member documents;
* an **incremental capture** is a graph of several files read at several instants, as today.

Plain documents stop being concatenated onto the prelude. Each claim's source lines become the document's own, which moves the 23 blocks that pin a `source_line` today, and `FactDocuments.captured`, which `GenerateTestSchemas` shares, changes with it in the same commit, so there stays one reading of a document.

### Identity

Settled once, before anything new names a document. A **document** is a file, identified by its path under `facts` without the extension: `scenarios/field-key-row`, `worlds/default/field-key-row`. A **graph** is named by the document's path for a plain document and by the folder for a world (`worlds/default`); a stepped graph keeps its `<graph>` stem. `@factDocument(path:)`, `-Dfacts=<prefix>` and every assertion file use document paths. Ids stay unique across the tree, as `FactDocuments.claimed` already insists.

Moving a document into a world changes its path, and the manual names 13 documents by path. The move updates the references in the same commit; `ManualExampleTest`'s floor and the assertion-file floor below fail on a reference left dangling.

### Worlds

A world is a folder `facts/worlds/<world>/` holding `_world.graphqls` and member documents, captured as one graph.

* **`_world.graphqls`** declares the shared types once (the sakila tables a member needs, `Film`, `Language`, `Actor` and the rest, with the fields most documents use) and the world's `@withConfiguration`, which is the only one in the graph.
* **A member extends** the base types (`extend type Film { ... }`) and names the types and fields it adds for its subject. A member redeclaring a type is a schema problem the store records, which fails the world's claim and names the member.
* **One world per configuration and catalog**: the default sakila world, and one each for the configurations documents state today (outbox, session state, page size, federation, tenant, no catalog). A configuration one document needs is a world of one; a subject whose claims cannot be scoped inside a world stays a plain scenario.
* **Claim**: a world stores no defect and generates. `FactExpectationTest.everyFolderHoldsWhatItClaims` reports inside a world per source file, since `graphitron_entry_defect` and `graphql_schema_problem` both carry `source_name`, so a failure names the member, not just the world.
* **Defects stay plain.** A defect document is a minimal pair (the rows it draws, and beside them a near miss that draws nothing, per `defects-as-facts.adoc`), and one defect in a world would turn the whole world into a defect. A defect document may name a world's base with `@factWorld(name:)` to be captured as `_prelude`, that base and itself, so it reuses the base types without the base's members.
* **Generation.** `GenerateTestSchemas` generates one package per world (`no.sikt.graphitron.generated.facts.<world>`) beside the plain scenarios it generates today. Generating the configured worlds is a scope change, not a restructuring, and lands as its own step with its own tests.

Worlds are gated by measurement before scenarios move into them (see Implementation, phase 2): their cost against the members captured separately, since every derivation step runs per graph and its cost over a larger graph is unmeasured, and their claim stability, since a claim that depends on a sibling is the failure worlds can introduce.

### Claims and their scope

Claim blocks stay in documents, and documents stay the one source of store claims that capture reads back (`CorpusExpectations.blocks`). Two rules keep a claim's truth independent of its neighbours.

* **Inside a world, `@expectEquals` and `@assertEmpty` state their scope.** A claim over a relation's whole population would see every member's rows, and an implicit rule that scopes by `source_name` or by coordinate rots: every member extends `Film`, and no invariant says a derived row's `source_name` names the file that caused it. So in a world those two modes require `where:`, which the runner already supports, and the runner refuses them without it. `@assertContains` and `@assertAbsent` need no scope. 231 of today's 470 whole-population blocks carry no `where:`; a document whose claims cannot take one stays a plain scenario.
* **A store-wide relation is claimed only by what every graph beside it agrees on.** A relation with no `graph_name` (the `sql_`, `code_`, `meta_` families and a few others, 76 in all) ranges over everything its store holds. Two rules make that independent of which graphs share the store. Graphs share a store only when they share a catalog and a classpath (below), so the rows such a relation holds are the same in every store a document can land in. And a document claims a store-wide relation with `@assertContains` and `@assertAbsent` only: `@expectEquals` and `@assertEmpty` there would state the whole store, which no document owns, and today's union of such blocks across documents checks something no single document says. The 8 such blocks, in 6 documents, convert in phase 1.

### Expectations beside a document

A test states expectations about a document without editing it in an **assertion file**: SDL, naming its document with the existing `@factDocument(path:)`, decoded as text and never captured into the graph.

* **Store claims beside a document are additive only.** An assertion file may carry `@assertContains` and `@assertAbsent` blocks, checked by `FactExpectationTest` against the document's graph. `@expectEquals` and `@assertEmpty` state what the document's graph is, which is the document's own statement; they stay in the document, and the runner refuses them in an assertion file. The manual keeps rendering the document's own blocks.
* **Answers above the store live in the module that answers them.** graphitron-model cannot see the generator, the editor or the MCP server, and the language server and the MCP server may not depend on `graphitron` (`StoreClientBoundaryTest`). So each module keeps its assertion files under `src/test/resources/fact-assertions/`, declares the directives it reads in a prelude of its own, and runs them from one `@TestFactory` class, one dynamic test per assertion. A directive no runner declares fails to parse in that module.
* **The kinds**, each added when a module's migration shows a repeated shape, never ahead of it:
  - editor answers (graphitron-lsp): hover, completion, definition, references, inlay hints, diagnostics and code actions at a cursor given as a line and a needle in the document's own file, with an optional edit applied to the buffer for a mid-edit case;
  - MCP answers (graphitron-mcp): a tool or resource with its arguments, answered as a JSON subset, or an error whose first line starts with a prefix;
  - emitted schema (graphitron-model): the description, tags, applied directives and type of a coordinate in the emitted or written schema;
  - generated structure (graphitron): that a generated type or method exists with a signature. Method bodies are out of reach by definition: asserting generated Java text is banned, and a body is pinned by executing it.
* **Defects are claimed as rows, and their prose is pinned once per defect type.** A Java test that asserts a rendered defect message becomes rows of `graphitron_entry_defect_site` and its detail relations in a defect document. The renderer is pinned by a coverage gate over `graphitron_defect_type`: every defect type renders, from a document that draws it, to a message pinned once. Defect types no document draws yet are counted under a ceiling that only falls, so the gate lands without waiting for every type to have its document. Message prose stops being copied across hundreds of cases.
* **Floors**: an assertion file naming no existing document fails, and so does one whose document has moved.

What stays a Java test, reading a document through the population: generated behaviour (compile, run against PostgreSQL), statement counts, plans, timing and read budgets, store integrity (a write the schema refuses), lifecycle (recapture, warm start, sweep), deep classification navigation, and cases that inject synthetic diagnostics. Invariants that hold of any captured graph (dense ordinals, agreement with the parsed SDL, every field navigating as one type) run over the whole population, as `FactExpectationTest` already does, rather than per document.

### The population: `FactGraphs`

A class in graphitron-model's test harness, published in the test-jar that graphitron, graphitron-lsp, graphitron-mcp and graphitron-maven-plugin consume. Its own class rather than a member of `FactStores`, whose stated design is that it never puts rows in a store. It opens stores through `FactStores.inMemory(StoreUse.LONG_LIVED)` and captures through `FactDocuments`, the one reading `FactExpectationTest` checks and `GenerateTestSchemas` generates from.

* **Captured once per JVM, lazily, per graph**, in JUnit's root `ExtensionContext` store as an `AutoCloseable` closed at the end of the run. `FactGraphs.captures(graph)` stays at one however many classes ask.
* **Sharded within catalog and classpath.** Graphs share a store only when they share a catalog and a classpath, so a store-wide relation holds the same rows in every store a graph can land in. Within each such key there are four long-lived stores, a graph assigned to one by its name, each with its own lock, so captures keep the four-way parallelism they have today; nearly every document shares the default catalog and classpath. Four is a constant of `FactGraphs`, not read from `junit-platform.properties`, so it does not depend on which properties file a module sees. Configuration is graph-keyed (`store_graph_*` rows), so graphs of different configurations may share a store.
* **Read-only, by the database**: readers connect as `GRAPHITRON_READER`, which every store's DDL creates with `SELECT` and nothing else.
* **Handed out in a transaction.** A test declares `@RegisterExtension static final FactGraphs.Reader FACTS = FactGraphs.reader();`, the shape `FactStores.perClass()` and graphitron-mcp's `StoreFixture.held()` already have, and reads with `FACTS.read("worlds/default/field-key-row", graph -> ...)`. The body gets a `FactGraph`: the `StoreHandle` scoped to the graph and the `CapturedSchema` the capture built, which a generating run needs beside the store. There is no parameter resolver handing out a bare handle: `StoreReader` makes its transaction the only way onto a reader, so an answer built from several queries sees one commit, and production readers take the same shape (`CatalogQueries`, `CatalogCorpus`).
* **`FactExpectationTest` reads its plain graphs and worlds from the population**, warming it with one thread per shard, so in graphitron-model the graphs it checks and the graphs other tests read are captured once between them. Stepped graphs keep their own capture: it checks each instant as that instant is read, an intermediate state the population does not keep.

### Authoritative tests

Every behaviour has one **authoritative test**: the one place that pins it, which a reader asked "what makes this true, and what proves it" can name without searching. The authoritative test sits where the behaviour is decided:

* **a store fact** (a captured or derived relation) is a claim in the document that states the example;
* **a defect** is rows of its defect relations in a defect document, as a minimal pair, and its prose is pinned once per defect type by the rendering gate;
* **a translation above the store**, the way the editor turns a defect row into a diagnostic or the MCP server turns a type's rows into a `schema` answer, is pinned once per shape of translation, not once per defect or type it carries. A diagnostic test for every defect type restates the defect relations through the editor;
* **generated behaviour** is pinned by executing the generated package of the world that states it.

Every other test that touches the behaviour relies on its authoritative test rather than restating it. A test whose assertions are all pinned elsewhere is deleted, naming the authoritative test that already covers each one. A test that pins something nobody else does keeps that and loses the rest. Documents follow the same rule: two documents stating the same example merge into one, and a document's near-twin either states a different subject in its header or merges.

Two gates hold it, both of the kind that only fall. `FactExpectationTest` refuses two documents whose text is equal once comments, whitespace and claim blocks are removed. And it counts pairs of documents whose type-and-field names overlap by nine tenths or more (21 pairs today) under a ceiling, so that near-twins are merged or told apart rather than added to. Duplication between Java tests has no mechanical detector, since equal assertions are spelled differently; it is found by the pipeline's triage, which clusters capture sites by the behaviour they pin before it rules on any one of them, and checked by its coverage review.

### What keeps a store of its own

A test that writes after capture (a recapture, a second graph, hand DML, a fact writer, DDL), reads an intermediate state no claim states, has a file-backed store or warm start as its subject, exercises a capture's failure path, counts or times captures, statements or boots, or tests one capture stage in isolation. It still takes its schema from a document, captured into a store it owns through `FactDocuments`. So does a test of the same document under a configuration or classpath its world does not have (lint configuration, page size, `TypeBackingPartitionTest`'s four classpaths). Examples from the inventory: `WarmStartRefreshTest`, `PersistentStoreTest`, `CaptureCommitsAsItGoesTest`, `WriteTimesTest`, `CoordinateSpellingGateTest`, `DiagnosticFactsTest`, `BrokenSourceStillCapturesPipelineTest`, `StoreBootCountTest`, the `*StatementCountTest` and `StoreOutOfBudgetTest` classes, and the stage-capture tests over `ThreadConfinedStore.run`. The ceilings are set with these counted.

### Out of scope

The classification walk's tests, `GraphitronSchemaBuilderTest`'s truth tables and the roughly 160 `TestSchemaHelper.buildSchema` tests, capture nothing and retire with the walk under its own item. The classification corpus and `CorpusStore` retire with it too; nothing here builds on them. `SeededStore` dissolves under its own gate, `SeedingDissolutionGateTest`.

## Placement and evolution guideline

The first client of the design: this is the section `testing.adoc` gains, drafted here so that if it does not read simply, the design changes before implementation.

> **Where an example goes.** The folder states what the store does with it: a world or a plain scenario generates and stores no defect, a defect document stores one, an incremental capture is read in steps, and `undecided` only shrinks. Within the claim, choose by configuration: a scenario joins the world whose configuration it needs, and a new world is made only for a configuration no world has. Within the world, choose by subject: a document pins one store fact, defect type or behaviour, is named for it (`field-key-row`, `node-id-decode-unresolved-type`), and opens with a header saying what it pins and why each type is there.
>
> **Authoritative tests.** Each behaviour has one authoritative test, at the place the behaviour is decided: a store fact's is a claim in the document that states the example; a defect's is its rows in a defect document, its prose pinned once per defect type; a translation above the store (an editor diagnostic, an MCP answer) has one per shape of translation; generated behaviour's is an execution test over the generated package. A test elsewhere relies on the authoritative test instead of restating it, and asserts only what its own layer adds.
>
> **Look before you add.** Find the behaviour's authoritative test first. If it exists, extend it or rely on it; a second test of the same behaviour is a defect in the suite, and the newer one is deleted. If it does not, the test you are writing is it, and belongs where the behaviour is decided.
>
> **Extend or add.** Add to an existing document when the case is the same subject and agrees with its claim; add a document when the subject differs. In a world, extend the base types and name what you add for your subject; a redeclared type fails the world. A defect document is a minimal pair: the rows it draws, and a near miss that draws nothing.
>
> **Claim it in the document.** What the store holds for the example is claimed in the document. Inside a world, a claim over a relation's whole population states its scope with `where:`. Expectations that are not store rows, or that a test adds without changing the document, go in an assertion file in the module that answers them, naming the document with `@factDocument(path:)`.
>
> **Improve as you go.** Split a document that pins two subjects; merge documents that state the same example; move a document out of `undecided` into the folder whose claim it meets; never weaken a claim to make room for a case, give the case its own document instead. A document's path is referenced by the manual and by assertion files: a move updates them in the same commit.
>
> **Keep a store of your own only when the store is the subject.** A test that writes, times or fails a store captures its document into a store it owns. Everything else reads the shared population.

## Implementation

Four phases, each landing on its own and keeping the build green. Phase 1 stands on its own if worlds are dropped.

1. **The unit, the identity and the population.**
   - `FactDocuments` captures a plain document as `_prelude` plus the document, two files of one graph; `GenerateTestSchemas` reads it the same way; the 23 line-pinned blocks move with it. One commit, both consumers.
   - Document and graph identity as above; `-Dfacts` and `@factDocument(path:)` read paths.
   - `FactGraphs`, `FactGraphs.Reader`, `FactGraph`, sharded within catalog and classpath, and `FactExpectationTest` over it; the 8 whole-store blocks on store-wide relations become `@assertContains` and `@assertAbsent`, and the runner refuses the whole-population modes there.
   - Assertion files for additive store claims, read by `FactExpectationTest`, with their floor.
   - Guards: `StoreBootCountTest`'s equation gains `FactGraphs.boots()`; `StoreFixtureGuardTest`'s `HOMES` names `FactGraphs` if it spells the store type; `ThreadConfinedStore.BOOT_BUDGET`'s account mentions it. An `InlineCaptureGateTest` in each of the five modules counts capture call sites whose SDL is not read from a document (the capturing arms of `CapturedStore`, `GraphitronStore.captured`, `TestSchemaHelper.buildBundle`, `BuiltStore`, the lsp and mcp `StoreFixture` arms; harness files excluded), at a ceiling that may be stale on the low side and may not rise.
   - The emit tests move: the query and execution tests read their documents through `FACTS`, the emitter tests read their rows from the documents their cases live in, and the stub classes they name move into `graphitron-sakila-service` beside the services documents already name, so a capture can produce those rows. `testing.adoc`'s unit-tier blessing of record literals narrows to inputs with no store origin.
   - Docs and skills, first version (see Surfaces): the guideline above, the harness table, the skill that carries the guideline and the pipeline, and the two capability skills deleted.
2. **Worlds.**
   - The measurement first, on the default configuration: a world of the scenarios that fit it against the same documents captured as plain graphs, in interleaved pairs per the `build-profile` skill, and the population checked in two class orders with the divergences compared. Proceed when the world's claims are stable across orders; the cost result decides how far worlds go, not whether a world is correct.
   - The world folder, `_world.graphqls`, membership, `@factWorld(name:)` for defect documents, the `where:` rule for whole-population modes inside a world, and per-source folder claims.
   - The default world, then the configured ones, each a move of documents with their manual references.
   - `GenerateTestSchemas` generates per world; generating configured worlds is its own commit, with an execution test over each newly generated package.
3. **The migration, module by module**: graphitron-model, graphitron, graphitron-lsp, graphitron-mcp, graphitron-maven-plugin. Each module step adds the assertion kinds its tests show a repeated shape for, moves its tests through the pipeline below, and lowers its ceiling. The defect-type rendering gate lands with graphitron's step.
4. **Retirement**: the `CapturedStore` arms and `TestSchemaHelper` methods the migration empties, and `TestSchemaHelper`'s `EMITTED` memo, deleted in the step that empties them and listed under Retired vocabulary.

### The migration pipeline

The migration runs as a pipeline of agents, carried by one skill (`fact-documents`, in `.claude/skills/`, where agent procedure already lives) that also holds the placement guideline. Per batch of test classes, each stage a different agent:

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
  - "Execution tier": the folder claims move into the guideline; worlds as the generated packages execution tests read;
  - "Where a store-backed test gets its store": `FactGraphs` as the default row, `CapturedStore` narrowed to a store of one's own, `SeededStore`'s row gone, the lsp and mcp `StoreFixture` row restated.
* **`development-principles.adoc`**, "Behaviour is pinned at the pipeline tier and above": a new feature earns a fact document first. The file is at its 3,500-word budget (`DocSizeBudgetTest`), so the change is net zero.
* **`fact-model.adoc`**'s "Enforced by" paragraph and `defects-as-facts.adoc`'s closing step link the guideline; `index.adoc` and `how-to/index.adoc` describe the page's wider scope.
* **CLAUDE.md**, "Building and testing": one line, a store-backed test starts from a fact document, with the pointer to the guideline.
* **Skills**: `nested-jooq` (it teaches `SeededStore` three times, against `SeedingDissolutionGateTest`), `store-performance` (its `SeededStore` mentions), `explain` (a corpus path that no longer exists), `adding-a-relation` (a step that pins the relation in a document), `reviewer-prompt` (a schema stated inline is a finding), `build` (`-Dfacts`), and the new `fact-documents` skill. The `capability-catalog` and `capability-interview` skills are deleted in phase 1: they served the capability catalog, discarded with its only consumer because this item carries its intent, and they describe a catalog and two directives (`@capability`, `@exemplifies`) that will not exist. Nothing else in the tree reads them; the roadmap audit and the changelog that mention them are records.
* **Guard bookkeeping**: `ArchitectureDocSymbolGuardTest` resolves every class the architecture docs name, so a doc names `FactGraphs` or `InlineCaptureGateTest` only in the commit that adds it, and dropping the last citation of one of its 12 exempted names deletes the exemption in the same commit. `SchemaIdentifierDriftCheck` holds relation names in examples live. `RetiredVocabularyGuardTest` reads documents and docs alike.

### Coordination with other items

* **R876** deletes `SeededStore` and the classification corpus, and plans emitters "unit-tested from captured fact documents". This item delivers that half and describes neither deletion as done.
* **R983** rewrites `fact-model.adoc`'s "Enforced by" paragraph; whichever lands second rebases on the other's wording.
* **R1131** step 7 would move suites into separate test modules and update `testing.adoc`. Where both are open, this item owns `testing.adoc`'s structure and step 7 restates module locations into it.
* **R764** settles which `junit-platform.properties` each module sees. `FactGraphs` reads nothing from it, so the two are independent.
* **R851** retires the corpus's verdict directives and is affected by the corpus retiring, not by this item.

## Tests

* `FactGraphsTest` in graphitron-model:
  - a graph asked for from several classes on several threads is captured once;
  - an `INSERT`, `UPDATE`, `DELETE` or DDL statement inside `read` is refused by the database;
  - two documents of different catalogs never share a store, a store-wide relation reads the same rows in every shard of one catalog and classpath, and a whole-population claim on a store-wide relation is refused;
  - a stepped graph hands out its last instant;
  - readers close in `afterAll` and shards at the end of the run.
* `FactExpectationTest` over the population, with: the assertion-file floor; refusal of `@expectEquals` and `@assertEmpty` in an assertion file; refusal of those modes without `where:` inside a world; per-source folder claims; a member redeclaring a base type failing with the member named.
* The world measurement's two-order comparison, recorded in the phase 2 commit.
* The duplicate-document refusal and the near-twin ceiling in `FactExpectationTest`, each with a negative case.
* The defect-type rendering gate: every row of `graphitron_defect_type` renders from a document that draws it.
* Each module's assertion runner, with one negative case per kind.
* The five `InlineCaptureGateTest`s. The item is complete when every remaining inline capture is a schema whose defect the store does not yet record, named beside its ceiling, and every test listed under "What keeps a store of its own" captures a document.

## Evidence

Each phase and module step reports in its commit message the affected test phases before and after, in interleaved pairs measured the way the `build-profile` skill describes, and `FactStores.captures()` at the end of each module's run, and counts the tests deleted as duplicates and the documents merged. Phase 2 also reports `generate-test-schemas` before and after.

## Retired vocabulary

Listed as the migration empties them, for the sweep at Done: the `CapturedStore` arms and `TestSchemaHelper` methods left without callers, and `TestSchemaHelper`'s `EMITTED` memo, which exists so per-text captures stay under `ThreadConfinedStore`'s boot budget. "Plain document captured as one file" goes in phase 1: a plain graph is two files from then on. So do the `capability-catalog` and `capability-interview` skills, and with them "capability catalog", `@capability(name:)` and `@exemplifies(capability:)` outside the changelog and audits.

## Other solutions we've considered

* **Worlds as a fourth capture arm beside plain and stepped documents.** Rejected: a world is the stepped arm's multi-file graph read at one instant, so the graph unit generalises instead.
* **Implicit scoping of whole-population claims inside a world**, by `source_name` where a relation has one and by declared coordinates otherwise. Rejected: members share the base types, and nothing states that a derived row's `source_name` names the file that caused it. `where:` states the scope.
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
