---
id: R112
title: "Operation-driven test corpus driving tests, runtime trace, and docs"
status: Spec
bucket: architecture
theme: structural-refactor
depends-on: []
---

# Operation-driven test corpus driving tests, runtime trace, and docs

GraphQL documents already carry every piece of metadata a test-and-doc pipeline needs: named operations group variants of a query, descriptions narrate intent, typed variables with defaults define an input grid, and directives attach arbitrary structured metadata to operations and fields. Today we use almost none of this. Execution-tier tests are imperative Java that reconstruct queries inline, the leaf-coverage report (R104) joins by test class rather than by operation, and worked examples in the docs site are hand-typed prose disconnected from any running test. One artefact, one shape: a `.graphql` document under `src/test/graphql/<feature>/<scenario>.graphql` becomes the source of truth for (a) the test battery, (b) the coverage matrix, and (c) the doc render. Same file, three outputs.

The load-bearing design choice is a sealed directive vocabulary, e.g. `TestDirective.{Tier, Feature, ExpectedRows, ExpectError, AssertSql, Snapshot, Skip, …}`, classified at schema-load time by a directive-classifier that mirrors the leaf classifier. Unknown directive becomes a compile error, not a runtime ignore. Validator mirrors classifier, per the rewrite design principles. Adding a directive is a deliberate hierarchy edit, not a string-matching kludge, and each directive's contract is exhaustive at the type level.

Three machinery extensions, each small on its own:

1. *Per-operation column on R104's trace.* `ClassificationTrace.Context` already tags rows with test class and tier; add `operationName`, set by the runner from `OperationDefinition.getName()`. The leaf-coverage matrix becomes per-operation rather than per-test-class, and roadmap-item coverage falls out via `@Feature("R45")` directives joining on operation name.

2. *New `ExecutionTrace` mirroring `ClassificationTrace`.* A `graphql.execution.instrumentation.Instrumentation` impl, gated by a `graphitron.execution.trace=<path>` system property, emits JSONL one row per non-trivial `beginFieldFetch`. Filter is `instanceof TrivialDataFetcher == false`, the marker interface graphql-java already uses for query optimisation, so any custom fetcher that wants to opt out of the trace just implements it. Row shape: `{operation, parentType, fieldName, fetcherClass, parentRowIndex, durationNanos, resultRowCount}`. `parentRowIndex` is the load-bearing column: `COUNT(*) GROUP BY operation, fieldName` greatly exceeding 1 on a child datafetcher reveals an N+1 with no extra instrumentation.

3. *AsciiDoctor extension consuming the same `.graphql` files.* Loads the document, emits one section per named operation: description as prose, body as syntax-highlighted code, variables as a typed parameter table, captured snapshot (when `@Snapshot` is set) below.

The user-facing payoff is a four-quadrant gap diagnostic on the inference-axis-coverage page, joining classification (R104) and execution (this item) through the operation key. Classified leaf with a fired non-trivial datafetcher: healthy. Classified leaf with no fired datafetcher in any documented operation: dead generation, prune or document why. Unclassified leaf with a fired datafetcher: classifier blind spot, extend the hierarchy. Unclassified leaf with no fired datafetcher: nothing to look at. Each cell maps to a named action; the inference-axis-coverage report turns from a coverage matrix into a gap diagnostic with its own queue of work.

Imperative setup is unchanged. A sibling JUnit `@BeforeEach` in the companion test class does whatever it needs to, then the runner replays the named operations from the `.graphql` document. Setup stays in Java where it belongs; the assumptions get a `"""…"""` description on the operation so they appear in the rendered docs alongside the query.

Out of scope for this item: changing R104's existing per-test-class coverage rows (the new `operationName` column is additive); changing how leaves are classified at codegen time; converting existing imperative execution-tier tests in bulk (an opt-in migration follows once the machinery lands). This item lays the foundation; subsequent items adopt it scenario by scenario.
