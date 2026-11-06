---
id: R1160
title: "Tests read pre-captured fact documents from a shared store"
status: Backlog
bucket: dx
priority: 5
theme: testing
depends-on: []
created: 2026-11-06
last-updated: 2026-11-06
---

# Tests read pre-captured fact documents from a shared store

## Goal

Tests stop capturing their own store at setup and read pre-captured fact documents from one shared store per test run. Today about 140 setup captures across roughly 100 test files (graphitron 65, graphitron-model 35, graphitron-lsp, graphitron-mcp and graphitron-maven-plugin 4) each capture inline SDL in `@BeforeAll`, paying the capture again for schemas that differ by a field. When this lands, the schemas those tests state live as fact documents (the SDL-plus-claims files under `graphitron-model/src/test/resources/facts` that `FactExpectationTest` checks), each captured once per run, and a test reads its document's graph as the select-only `GRAPHITRON_READER` user, so a test cannot write the store it shares.

## Plan

1. **The extension.** A `FactStores` JUnit extension in graphitron-model's test-jar. It captures each fact document once per run into the root `ExtensionContext` store, as an `AutoCloseable` closed at the end of the run. A stepped document (the incremental captures, several instants of one corpus) is captured through its last instant. A parameter resolver hands a test a `StoreHandle` on the document's graph, connected as `GRAPHITRON_READER`, which every store already creates.
2. **Migrate module by module.** Inline SDL moves into fact documents under `scenarios/` or `defects/`, which widens what `FactExpectationTest` checks, since every moved schema now states its claims. Incremental tests move into stepped documents under `incremental-captures/`. Whole-schema pins (the sakila-example `schema.graphqls`) become shared documents the same way.
3. **What keeps its own setup.** A test that reads an intermediate instant not stated as a claim, a deliberately broken corpus, and a gate that times or counts capture.
4. **Evidence.** Each module step reports the module's test time before and after.

The extension comes first and is this item's, as is the whole migration, the emitter tests in `no.sikt.graphitron.emit` included: those build their query rows by hand today and move onto the fact documents their cases live in.
