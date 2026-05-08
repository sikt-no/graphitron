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

*Schema and emit machinery.* The four-quadrant report joins seven DuckDB tables, all keyed naturally; surrogate IDs are forbidden unless a fork explicitly justifies why no natural key exists. The schema coordinate `(parent_type, field_name)` is the join PK across the per-coordinate tables; the others are catalogs and relations. Many `fetcher_call` rows pile up at the same coordinate, since a single field gets fetched by many operations across many test runs. That redundancy is exactly where natural keys earn their keep: aggregations on the coordinate (`COUNT(DISTINCT operation) GROUP BY parent_type, field_name`, `COUNT(*) BY parent_type, field_name`, joins to `generated_fetcher` and `classification`) read off the natural identity directly, no join through a surrogate. The same property makes per-coordinate reporting cheap, e.g. "which coordinates are exercised by the most operations" sorts straight off a single GROUP BY.

| table | grain | populated by |
|---|---|---|
| `classification` | one row per sealed-variant kind | code-introspection scan (existing R104, renamed from `leaves`) |
| `roadmap_mention` | one row per (roadmap_id, classification) | regex scan of roadmap markdown (existing R104, renamed from `mentions`) |
| `classifier_call` | one row per classifier invocation at codegen time | `ClassificationTrace` JSONL (existing R104, renamed from the `trace` view) |
| `generated_fetcher` | one row per `(parent_type, field_name)` where codegen produced a fetcher | new codegen JSONL co-emitted by `ClassificationTrace` |
| `fetcher_call` | one row per non-trivial datafetcher invocation at runtime | new runtime JSONL from the `Instrumentation` impl |
| `operation` | one row per `(document_path, operation_name)` in the corpus, with `feature` derived from path | parsed at report time from `src/test/graphql/**/*.graphql` |
| `feature` | one row per user-facing capability graphitron supports | parsed from `graphitron-rewrite/features/*.adoc` |

Schema coordinate is the cross-time natural key. Codegen records facts about coordinates (each visit to `(parent_type, field_or_type_name)` invokes the classifier, producing one or more `classifier_call` rows depending on phase, and when the classification produces a fetcher, emits a `generated_fetcher` row co-keyed on the same coordinate). Runtime records facts about coordinates (each non-trivial datafetcher invocation produces a `fetcher_call` row carrying `parent_type`, `field_name`, and the operation that fired it). The four-quadrant view joins both sides on coordinate. `fetcher_class` is emitted on `fetcher_call` and `generated_fetcher` as a diagnostic column (useful for detecting class-overrides where the runtime class differs from the codegen-generated one at the same coordinate), but the join key is the coordinate, which is stable across codegen-side renames.

Operations join through the runtime side: `fetcher_call.operation` (set by `OperationRunner.execute`) tags each invocation with the operation_key that fired it. The roadmap join is classification-mediated: `roadmap_mention` rows reference classifications, and a classification reaches an operation through `fetcher_call ⋈ generated_fetcher` (on coordinate) filtered by the operation column.

The `feature` axis is orthogonal to classification and joined through `operation`. A feature is a durable user-facing capability graphitron delivers (e.g., paginated relations, polymorphic children, JSON scalars) authored as an AsciiDoctor file in `graphitron-rewrite/features/<feature-slug>.adoc`, slug-keyed, with YAML front-matter for `id` (the slug), `title`, and optional `category` / `depends_on`. The body is prose explaining the capability with worked examples; the AsciiDoctor render emits feature pages into the docs site the same way roadmap items render. Roadmap-tool's report generator parses `features/*.adoc` directly at report time (same pattern as the corpus parsing), one row per feature. The corpus path convention `src/test/graphql/<feature-slug>/<scenario>.graphql` is the join: the leading path component is the feature membership, parsed into `operation.feature` at report time. Roadmap items remain transient implementation work and don't double as a feature catalog; the two axes coexist (a feature is delivered piecewise by zero or more roadmap items over time, and a roadmap item may touch zero or more features).

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
  JOIN generated_fetcher g
    ON g.parent_type = f.parent_type
   AND g.field_name  = f.field_name
)
SELECT 'healthy'    AS quadrant, c.name FROM classification c
  JOIN exercised x ON x.classification = c.name
UNION ALL
SELECT 'dead'       AS quadrant, c.name FROM classification c
  WHERE c.name IN (SELECT classification FROM non_trivial)
    AND c.name NOT IN (SELECT classification FROM exercised)
UNION ALL
SELECT DISTINCT 'blind-spot' AS quadrant,
       f.parent_type || '.' || f.field_name AS name
  FROM fetcher_call f
  LEFT JOIN generated_fetcher g
    ON g.parent_type = f.parent_type
   AND g.field_name  = f.field_name
  WHERE g.parent_type IS NULL;
```

The trivial-classification case (classifications that don't generate non-trivial fetchers) is intentionally absent from the view: such classifications never appear in `generated_fetcher` by construction, so the report doesn't expect them in `fetcher_call` and they don't end up flagged as dead.

Feature-axis views layer on top of the same joins. *Feature coverage*: `feature LEFT JOIN operation ON operation.feature = feature.slug` flags features with no test. *Feature health*: features whose operations all map to classifications in the `dead` quadrant of `classification_quadrant` are at risk; features whose operations all map to `healthy` are verified end-to-end. *Feature → classification*: which classifications support each feature, useful for impact analysis when a classification's hierarchy is reshaped.

The AsciiDoctor extension consumes the `.graphql` files directly (no DB dependency): one section per named operation, description as prose, body as syntax-highlighted code, variables as a typed parameter table.

The user-facing payoff is a gap diagnostic on the inference-axis-coverage page. *Healthy*: the classification produced a fetcher and a documented operation exercises it. *Dead*: the classification produced a fetcher but no documented operation exercises it; prune or document the absence. *Blind spot*: a `fetcher_class` fired that codegen didn't produce; either it's hand-coded (intentional) or it points at a gap in the classifier hierarchy. The fourth quadrant (classifications that don't produce non-trivial fetchers) is intentionally not enumerated; nothing to look at. Each cell maps to a named action; the inference-axis-coverage report turns from a coverage matrix into a gap diagnostic with its own queue of work.

*Implementation phasing.* Each phase ships to trunk on its own with the build green and named verification before moving on.

| phase | scope | verification |
|---|---|---|
| A — codegen schema | Rename `leaves`→`classification`, `mentions`→`roadmap_mention`, `trace` view→`classifier_call` (R104 DDL and `ClassificationTrace.java` JSONL field names). Co-emit `generated_fetcher.jsonl` from the codegen visitor at every coordinate where codegen produces a fetcher; add the matching DuckDB table. Add the empty `graphitron-rewrite/features/` directory plus the `feature` table populated by a roadmap-tool feature scanner; add the `feature` column on `operation` derived at parse time from the corpus path's leading component. | Existing R104 tests pass post-rename; a known coordinate produces a `generated_fetcher` row in the codegen pipeline test; an empty `features/` parses to zero `feature` rows without error. |
| B — runtime trace | Add the `Instrumentation` impl in `graphitron`, dormant unless `graphitron.execution.trace=<path>` is set. Add `OperationRunner` in `graphitron-fixtures-codegen` that loads a `.graphql` doc, builds a `GraphQL` instance with the Instrumentation attached, sets the operation-name ThreadLocal, and returns a handle with `execute(Map<String, Object>)`. | A unit-tier test exercises a stub `.graphql` end-to-end and asserts a single `fetcher_call.jsonl` row with the right `parent_type`, `field_name`, `fetcher_class`, and `operation`. |
| C — report and pilot | Add `classification_quadrant` view + feature-axis views (feature coverage, feature health, feature → classification impact) to `LeafCoverageReport`. Pilot: convert the films-paginated test method in `graphitron-sakila-example`'s `GraphQLQueryTest` to use `OperationRunner` against a new `src/test/graphql/pagination/films-by-year.graphql` (operation name `FilmsByYear`, plus a sibling operation for the empty-year case). Author `graphitron-rewrite/features/pagination.adoc` so the feature axis isn't empty for the pilot. | Running `mvn install -Plocal-db -Pleaf-coverage` produces non-empty `classification_quadrant` rows (at minimum: a healthy classification for the films `RootField` and the actor/language `ChildField` arms exercised; classifications absent from `generated_fetcher` correctly excluded from the dead quadrant); the `pagination` feature shows as exercised in the feature-coverage view. |
| D — docs render | AsciiDoctor extension under `/docs/` that loads `.graphql` files and emits one section per named operation. | The rendered docs site has the pilot scenario as a worked example with description, body, and variables table. |

Subsequent items convert additional execution-tier tests one at a time (`DmlBulkMutationsExecutionTest`, `FederationEntitiesDispatchTest`, `AddressOccupantsListBatchingTest`, ...), each adding a feature `.adoc` and a `src/test/graphql/<feature-slug>/` directory; the machinery doesn't change.

Out of scope for this item: changing how classifications are produced at codegen time (this item only adds emission of the `generated_fetcher` co-stream and renames existing schema vocabulary); converting existing imperative execution-tier tests in bulk (an opt-in migration follows once the machinery lands); a `generated_type` table for the type-axis intersection (V0's four-quadrant report only needs the field axis; types are still classified and visible in `classifier_call`, but the codegen-output side is deferred until a report wants it); a static `operation × classification` reference table for trivial-classification visibility (with `generated_fetcher` as the bridge, classifications without non-trivial fetchers don't appear in the bridge by construction, so the report doesn't expect them in `fetcher_call` and the gap is closed without the static walk); authoring the feature-catalog content itself (R112 ships the scaffolding — the `graphitron-rewrite/features/` directory, the front-matter format, the `feature` table, the `feature` column on `operation`, and the feature-axis views — but the catalog content gets written into over time as capabilities are documented; an empty corpus is correct, just unilluminating, and the report calls out features-without-tests once any get written). Explicitly, no custom GraphQL directives are introduced for V0. The `.graphql` files are stock GraphQL: operations, variables, fragments, descriptions. graphql-java's stock parsing and validation is the only schema-level guard. Future axes that want to log semi-structured payloads (variable bindings per execution, fetcher arguments per call, GraphQL errors) can use DuckDB's `VARIANT` type, so we don't reflexively flatten everything when the time comes. If another future need surfaces (an N+1 budget per operation, a SQL-capture binding, a snapshot helper) it gets its own roadmap item with its own justification rather than riding on this one. This item lays the foundation; subsequent items adopt it scenario by scenario.
