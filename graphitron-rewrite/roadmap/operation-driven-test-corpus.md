---
id: R112
title: "Operation-driven test corpus driving tests, runtime trace, and docs"
status: Spec
bucket: architecture
theme: structural-refactor
depends-on: []
---

# Operation-driven test corpus driving tests, runtime trace, and docs

GraphQL documents already carry the structure a test-and-doc pipeline needs: named operations group variants of a query, descriptions narrate intent, and typed variables with defaults define an input grid. Today we use almost none of this. Execution-tier tests are imperative Java that reconstruct queries inline, the leaf-coverage report (R104) joins by test class rather than by operation, and worked examples in the docs site are hand-typed prose disconnected from any running test. One artefact, one shape: a `.graphql` document under `src/test/graphql/<feature>/<scenario>.graphql` becomes the source of truth for (a) the operations the test battery runs, (b) the coverage matrix, and (c) the doc render. Same file, three outputs.

The load-bearing design choice is `operation_key` as the natural join across runtime and docs outputs. The `(document_path, operation_name)` tuple ties together (a) the JUnit test that exercises the operation, (b) the runtime fetcher-call row produced when graphql-java fetches a non-trivial datafetcher under the operation, and (c) the AsciiDoctor render that emits the operation's description and body as a doc section. Same key, three outputs, no DSL on top of GraphQL.

The `.graphql` corpus is the operation source-of-truth, not the test source-of-truth. Operations describe what to fetch; assertions describe what we expect about the result. Those are different concerns and they live in different places. The JUnit test that binds a document owns the imperative setup (`@BeforeEach`), the variable bindings, the assertions (AssertJ on the `ExecutionResult`), and the `OperationRunner.load(path, operationName)` call that picks one operation out of the document. The `.graphql` file owns the operation: name, variables (with descriptions and defaults), selection set, fragments, and the operation description that becomes doc prose. The two are co-located via the test class loading the document, not via metadata on the operation. Variable resolution is one layer: the caller's map wins over the operation's declared defaults, with no other inputs (no sidecar JSON files, no provider abstractions, no directive overrides).

*Runner shape: static helper.* `OperationRunner.load(path, operationName)` parses the document once per file (cached), validates against the schema, and returns a handle bound to one named operation. The handle's `execute(variables)` sets the operation name on a ThreadLocal that the runtime `Instrumentation` reads when emitting `fetcher_call` rows; this is the only place the operation name flows into the trace stream, since classifier-side tracing happens at codegen time, before any operation runs. No `@TestFactory`, no custom JUnit `TestEngine`, no `@ParameterizedTest` machinery: each test method names its operation explicitly, owns its `@BeforeEach`, and asserts in AssertJ. Per-operation reporting falls out of stock JUnit (test method name is the test ID). Corpus-coverage enforcement (every operation exercised by at least one test) doesn't live in the test machinery; it's a report query. Any operation present in the `operation` catalog (loaded from the corpus) with no `fetcher_call` rows is unbound. A worked test:

```java
class FilmsByYearTest {
  @BeforeEach void setUp() { /* DB fixtures */ }

  @Test
  void filmsByYear_2006() {
    var op = OperationRunner.load("films/by-year.graphql", "FilmsByYear");
    var result = op.execute(Map.of("year", 2006));
    assertThat(result.<List<?>>getData("filmsByYear")).hasSize(20);
  }
}
```

Module placement is pinned: `OperationRunner` lives in `graphitron-fixtures-codegen` alongside other test fixtures; the runtime `Instrumentation` impl lives in `graphitron`, dormant unless its system property is set, parallel to how `ClassificationTrace` is shipped.

*Schema and emit machinery.* The four-quadrant report joins six DuckDB tables, all keyed naturally; surrogate IDs are forbidden unless a fork explicitly justifies why no natural key exists. The schema coordinate `(parent_type, field_name)` is the join PK across the per-coordinate tables; the others are catalogs and relations.

| table | grain | populated by |
|---|---|---|
| `classification` | one row per sealed-variant kind | code-introspection scan (existing R104, renamed from `leaves`) |
| `roadmap_mention` | one row per (roadmap_id, classification) | regex scan of roadmap markdown (existing R104, renamed from `mentions`) |
| `classifier_call` | one row per classifier invocation at codegen time | `ClassificationTrace` JSONL (existing R104, renamed from the `trace` view) |
| `generated_fetcher` | one row per `(parent_type, field_name)` where codegen produced a fetcher | new codegen JSONL co-emitted by `ClassificationTrace` |
| `fetcher_call` | one row per non-trivial datafetcher invocation at runtime | new runtime JSONL from the `Instrumentation` impl |
| `operation` | one row per `(document_path, operation_name)` in the corpus | parsed at report time from `src/test/graphql/**/*.graphql` |

The two intersection points the spec orbits around:

- *Codegen-side, by coordinate.* Each codegen visit to `(parent_type, field_or_type_name)` invokes the classifier (one or more `classifier_call` rows depending on phase) and, when the classification produces a fetcher, emits a `generated_fetcher` row. Same coordinate, same pass, two co-emitted facts about the same act of classification.
- *Runtime-side, by `fetcher_class`.* The runtime fires fetcher classes that codegen produced. The bridge to the codegen-side facts is the fetcher class name; via `generated_fetcher`, that resolves to the classification.

Operations join through the runtime side: `fetcher_call.operation` (set by `OperationRunner.execute`) tags each invocation with the operation_key that fired it. The roadmap join is classification-mediated: `roadmap_mention` rows reference classifications, and a classification reaches an operation through `fetcher_call ⋈ generated_fetcher` filtered by the operation column.

Three JSONL streams on disk, two emitted at codegen time and one at runtime:

| path (per module) | emit time | producer |
|---|---|---|
| `target/classifier-call.jsonl` (renamed from `leaf-coverage.jsonl`) | codegen | `ClassificationTrace` |
| `target/generated-fetcher.jsonl` | codegen | `ClassificationTrace` (new emit alongside the existing one) |
| `target/fetcher-call.jsonl` | runtime | `Instrumentation` impl |

The `Instrumentation` impl lives in `graphitron`, dormant unless `graphitron.execution.trace=<path>` is set, harmless to ship as a built-in part of the generator since it does not affect generated code (parallel to how `ClassificationTrace` is shipped). Attaching it to a `GraphQL` instance is `OperationRunner`'s job in `graphitron-fixtures-codegen`, so production engine builders are untouched. The `instrumentDataFetcher` hook is where the `TrivialDataFetcher` filter applies: the wrapper checks the fetcher type once, returns the fetcher unchanged for trivial cases, and wraps non-trivial fetchers to time the fetch and emit a JSONL row on completion. `O_APPEND` for fork-parallel safety, same contract as `ClassificationTrace`'s JSONL. The `-Pleaf-coverage` profile is extended to set the runtime-trace property as well: the four-quadrant report needs both axes to be useful, so coupling them under one toggle keeps the user-facing surface small.

*Naming.* The schema vocabulary uses *classification* where the Java side uses *leaf* (a sealed-class metaphor that reads badly in SQL). Tables and columns are singular: `classification.name`, not `classifications.name`. A header comment in `LeafCoverageReport.java` spells out the bridge so future readers don't reverse-engineer the term.

Four-quadrant view, all SQL:

```sql
CREATE VIEW classification_quadrant AS
WITH non_trivial AS (
  SELECT classification FROM generated_fetcher
),
exercised AS (
  SELECT DISTINCT g.classification
  FROM fetcher_call f
  JOIN generated_fetcher g ON g.fetcher_class = f.fetcher_class
)
SELECT 'healthy'    AS quadrant, c.name FROM classification c
  JOIN exercised x ON x.classification = c.name
UNION ALL
SELECT 'dead'       AS quadrant, c.name FROM classification c
  WHERE c.name IN (SELECT classification FROM non_trivial)
    AND c.name NOT IN (SELECT classification FROM exercised)
UNION ALL
SELECT DISTINCT 'blind-spot' AS quadrant, f.fetcher_class AS name
  FROM fetcher_call f
  LEFT JOIN generated_fetcher g ON g.fetcher_class = f.fetcher_class
  WHERE g.fetcher_class IS NULL;
```

The trivial-classification case (classifications that don't generate non-trivial fetchers) is intentionally absent from the view: such classifications never appear in `generated_fetcher` by construction, so the report doesn't expect them in `fetcher_call` and they don't end up flagged as dead.

The AsciiDoctor extension consumes the `.graphql` files directly (no DB dependency): one section per named operation, description as prose, body as syntax-highlighted code, variables as a typed parameter table.

The user-facing payoff is a gap diagnostic on the inference-axis-coverage page. *Healthy*: the classification produced a fetcher and a documented operation exercises it. *Dead*: the classification produced a fetcher but no documented operation exercises it; prune or document the absence. *Blind spot*: a `fetcher_class` fired that codegen didn't produce; either it's hand-coded (intentional) or it points at a gap in the classifier hierarchy. The fourth quadrant (classifications that don't produce non-trivial fetchers) is intentionally not enumerated; nothing to look at. Each cell maps to a named action; the inference-axis-coverage report turns from a coverage matrix into a gap diagnostic with its own queue of work.

Out of scope for this item: changing how classifications are produced at codegen time (this item only adds emission of the `generated_fetcher` co-stream and renames existing schema vocabulary); converting existing imperative execution-tier tests in bulk (an opt-in migration follows once the machinery lands); a `generated_type` table for the type-axis intersection (V0's four-quadrant report only needs the field axis; types are still classified and visible in `classifier_call`, but the codegen-output side is deferred until a report wants it); a static `operation × classification` reference table for trivial-classification visibility (with `generated_fetcher` as the bridge, classifications without non-trivial fetchers don't appear in the bridge by construction, so the report doesn't expect them in `fetcher_call` and the gap is closed without the static walk). Explicitly, no custom GraphQL directives are introduced for V0. The `.graphql` files are stock GraphQL: operations, variables, fragments, descriptions. graphql-java's stock parsing and validation is the only schema-level guard. Future axes that want to log semi-structured payloads (variable bindings per execution, fetcher arguments per call, GraphQL errors) can use DuckDB's `VARIANT` type, so we don't reflexively flatten everything when the time comes. If another future need surfaces (an N+1 budget per operation, a SQL-capture binding, a snapshot helper) it gets its own roadmap item with its own justification rather than riding on this one. This item lays the foundation; subsequent items adopt it scenario by scenario.
