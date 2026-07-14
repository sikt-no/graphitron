---
id: R112
title: "Operation-driven test corpus, capability catalog, and runtime trace"
status: Spec
bucket: architecture
theme: tooling
depends-on: [capability-catalog]
last-updated: 2026-07-14
---

# Operation-driven test corpus, capability catalog, and runtime trace

This item contributes to the knowledge-base programme framed in R117: the DuckDB tables it lands (`operation`, `fetcher_call`, `generated_fetcher`, `capability`, `capability_coordinate`, `operation_exemplifies`) extend R104's coordinate × classification foundation toward a queryable model of graphitron's operation surface. Naming, joining, and emit conventions follow R117's programme principles (natural keys only, KB as projection, opt-in via build profile). The tables' consumers are build-internal, per R117's Consumers section (the shipped graphitron-mcp module is a live-workspace server, deliberately not a KB consumer); nothing in this item depends on R117 having shipped, since R117 is a navigational programme rather than executable work.

GraphQL documents already carry the structure a test-and-doc pipeline needs: named operations group variants of a query, descriptions narrate intent, and typed variables with defaults define an input grid. Today we use almost none of this. Execution-tier tests are imperative Java that reconstruct queries inline, the leaf-coverage report (R104) joins by test class rather than by operation, and worked examples in the docs site are hand-typed prose disconnected from any running test. The fix is to make three artefacts share one source of truth, joined by stable natural keys: the SDL (with a metadata directive labelling capabilities), an operation corpus (`.graphql` files), and a capability catalog (short prose under `capabilities/<slug>.adoc`). Tests, the runtime trace, and the docs site all compose off the same joins.

The load-bearing decision is the *triangle of natural keys*. Three artefacts, three keys, every join walks one of them:

- **Schema coordinate** `(parent_type, field_name)` joins SDL ↔ codegen ↔ runtime. Every codegen visit and every runtime fetch carries it; it survives renames in generated classes.
- **Operation key** `(document_path, operation_name)` joins corpus ↔ runtime ↔ docs. The JUnit test that loads a document, the runtime fetcher-call rows produced when graphql-java fetches under that operation, and the AsciiDoctor render all reference this tuple.
- **Capability slug** joins SDL ↔ capability catalog ↔ docs. A repeatable `@capability(name:)` directive on coordinates that exemplify a capability links the SDL to a `capabilities/<slug>.adoc` file authored as prose. Two integrity rules close the loop: every directive value resolves to an authored `.adoc`, and every authored slug appears on at least one coordinate. The capability catalog is closed.

Capabilities are durable named surfaces graphitron delivers (pagination, typed errors, polymorphic dispatch, JSON scalars, federation entities). They outlive the roadmap items that incrementally implement them. They are not requirements (no contract is implied), nor user stories (which close on delivery and map to roadmap items), nor features (overloaded with BDD/release-notes connotations); a capability is observably-true and persistent, like a sealed-class arm in the Java side. The directive is shipped in graphitron's `directives.graphqls` so production schemas and test fixtures both use the same spelling. It is *metadata*: read at SDL-parse time by the report tool and the AsciiDoctor extension; codegen ignores it; runtime ignores it. The "no custom directives in V0" non-goal in the prior draft is recast accordingly: no directives that influence codegen or fetching; metadata directives like `@capability` are fine because they cost nothing to ignore.

*Exemplar tagging with transitive closure.* The author writes `@capability(name: "pagination")` once per capability on the coordinate that *exemplifies* it (the relation field that introduces pagination, the union that introduces polymorphism, the `errors` field that introduces typed errors), not on every downstream coordinate that participates. The report computes the participating surface as a transitive closure over SDL traversal: a coordinate is *implicit* under a capability when an exemplar coordinate reaches it through field selections or type membership. `capability_coordinate` distinguishes `tag = 'exemplar' | 'implicit'` so the four-quadrant report can use the exemplar set for headline counts and the implicit set for impact analysis. Authoring stays cheap; the report stays comprehensive.

*Operations exercise capabilities, statically and at runtime.* Static derivation: parse each `.graphql` document, walk its selection set against the schema, and collect every coordinate it references; intersect with `capability_coordinate` to produce `operation_capability_static`. Runtime derivation: for each `fetcher_call`, look up the coordinate's capabilities; produce `operation_capability_dynamic`. The two should agree. Divergence is a finding: a static reference with no dynamic call usually means no test exercises that selection; a dynamic call without a static reference usually means a graphql-java extension is doing something the static walk can't see. The report surfaces both, separately.

*Examples are curated, distinct from exercising operations, and registered from the operation side.* An exercising operation is anything in the corpus whose selection set touches a tagged coordinate; the report counts it for coverage. An *example* is a curated demonstration: an operation author has decided "this operation illustrates a specific facet of capability X." One capability has many examples covering different facets (`simple-cursor-paging`, `filtered-pagination`, `paging-into-polymorphic-children` are all `pagination`); the same operation can exemplify several capabilities (a paginated polymorphic relation demonstrates both at once); not every exercising operation is an example (most are coverage noise).

Registration lives on the operation, not on the capability. A second graphitron directive `@exemplifies(capability: String!, facet: String!) repeatable on QUERY | MUTATION | SUBSCRIPTION` is shipped in `directives.graphqls`. The operation's description string carries the prose narration that renders alongside it; the docs site uses AsciiDoc for that prose (consistent with the rest of the site, no second renderer). The first paragraph of the description is the example summary; the remainder is long-form narration:

```graphql
"""
Cursor + first/after over a flat list.

When you fetch a paginated relation with `first: N`, graphitron projects only
the requested fields and returns a `Connection` carrying the page plus an
`endCursor` the next request passes as `after`. The cursor is stable across...
"""
query FilmsByYear($year: Int!)
  @exemplifies(capability: "pagination", facet: "simple-cursor-paging") {
  filmsByYear(year: $year, first: 20) { ... }
}
```

This inverts the locality cleanly: the operation knows what it demonstrates, prose lives next to the operation it narrates, and rename/move is one file edit instead of N. Capability `.adoc` files carry only preamble; the worked-examples section on each capability page composes at render time from `@exemplifies` directives across the corpus, grouped by facet. `facet` is a freeform kebab-case string, namespaced per capability (no central facet catalog); the report surfaces "facets used per capability" so authors can see whether names are converging or diverging.

*Runner shape: static helper.* `OperationRunner.load(path, operationName)` parses the document once per file (cached), validates against the schema, and returns a handle bound to one named operation. The handle's `execute(variables)` sets the operation name on a ThreadLocal that the runtime `Instrumentation` reads when emitting `fetcher_call` rows; this is the only place the operation name flows into the trace stream, since classifier-side tracing happens at codegen time, before any operation runs. No `@TestFactory`, no custom JUnit `TestEngine`, no `@ParameterizedTest` machinery: each test method names its operation explicitly, owns its `@BeforeEach`, and asserts in AssertJ on the `ExecutionResult`. Variable resolution is one layer: the caller's map wins over the operation's declared defaults, with no other inputs. The `.graphql` file owns the operation; the JUnit class owns the assertions. A worked test:

```java
class FilmsByYearTest {
  @BeforeEach void setUp() { /* DB fixtures */ }

  @Test
  void filmsByYear_2006() {
    var op = OperationRunner.load("pagination/films-by-year.graphql", "FilmsByYear");
    var result = op.execute(Map.of("year", 2006));
    assertThat(result.<List<?>>getData("filmsByYear")).hasSize(20);
  }
}
```

Module placement: `OperationRunner` lives in `graphitron-fixtures-codegen` alongside other test fixtures; the runtime `Instrumentation` impl lives in `graphitron`, dormant unless `graphitron.execution.trace=<path>` is set, harmless to ship as a built-in part of the generator since it does not affect generated code (parallel to how `ClassificationTrace` is shipped). Attaching the Instrumentation to a `GraphQL` instance is `OperationRunner`'s job; production engine builders are untouched. The `instrumentDataFetcher` hook is where the `TrivialDataFetcher` filter applies: the wrapper checks the fetcher type once, returns the fetcher unchanged for trivial cases, and wraps non-trivial fetchers to time the fetch and emit a JSONL row on completion. `O_APPEND` for fork-parallel safety, same contract as `ClassificationTrace`'s JSONL.

The corpus path is purely organizational; it is not a join key. A flat `src/test/graphql/<scenario>.graphql` works; a folder grouping by schema fixture (`src/test/graphql/sakila/films-by-year.graphql`) helps human navigation when test fixtures multiply. The corpus loader scans recursively. Operations claim no membership beyond their own description and the coordinates their selection sets touch.

*Schema vocabulary.* The DuckDB tables use *classification* where the Java side uses *leaf* (a sealed-class metaphor that reads badly in SQL). Tables and columns are singular: `classification.name`, not `classifications.name`. A header comment in `LeafCoverageReport.java` spells out the bridge.

| table | grain | populated by |
|---|---|---|
| `classification` | one row per sealed-variant kind | code-introspection scan (existing R104, renamed from `leaves`) |
| `roadmap_mention` | one row per `(roadmap_id, classification)` | regex scan of roadmap markdown (existing R104, renamed from `mentions`) |
| `classifier_call` | one row per classifier invocation at codegen time | `ClassificationTrace` JSONL (existing R104, renamed from the `trace` view) |
| `generated_fetcher` | one row per `(parent_type, field_name)` where codegen produced a fetcher | new codegen JSONL co-emitted by `ClassificationTrace` |
| `fetcher_call` | one row per non-trivial datafetcher invocation at runtime | new runtime JSONL from the `Instrumentation` impl |
| `operation` | one row per `(document_path, operation_name)` in the corpus | parsed at report time from `src/test/graphql/**/*.graphql` |
| `capability` | one row per durable capability slug | parsed from `capabilities/<slug>.adoc` |
| `capability_coordinate` | one row per `(parent_type, field_name, capability, tag)` where `tag ∈ {exemplar, implicit}` | SDL parse: exemplars from `@capability` directives, implicit rows from transitive-closure traversal |
| `operation_exemplifies` | one row per `(document_path, operation_name, capability, facet)` | parsed at report time from `@exemplifies` operation directives in the corpus |

Surrogate IDs are forbidden unless a fork explicitly justifies why no natural key exists. Many `fetcher_call` rows pile up at the same coordinate, since a single field gets fetched by many operations across many test runs; that redundancy is exactly where natural keys earn their keep, since aggregations on the coordinate read off the natural identity directly without joining through a surrogate.

Three JSONL streams on disk, two emitted at codegen time and one at runtime:

| path (per module) | emit time | producer |
|---|---|---|
| `target/classifier-call.jsonl` (renamed from `leaf-coverage.jsonl`) | codegen | `ClassificationTrace` |
| `target/generated-fetcher.jsonl` | codegen | `ClassificationTrace` (new emit alongside the existing one) |
| `target/fetcher-call.jsonl` | runtime | `Instrumentation` impl |

The `-Pleaf-coverage` profile is extended to set the runtime-trace property as well: the four-quadrant report needs both axes to be useful, so coupling them under one toggle keeps the user-facing surface small.

*Coordinate quadrant view, all SQL:*

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

*Capability-axis views layer on top of the same joins.* Capability coverage: `capability LEFT JOIN capability_coordinate cc LEFT JOIN fetcher_call f ON f.parent_type = cc.parent_type AND f.field_name = cc.field_name` flags capabilities authored but not exercised. Capability health: capabilities whose exemplar coordinates all map to classifications in the `dead` quadrant of `classification_quadrant` are at risk; capabilities whose exemplars are all `healthy` are verified end-to-end. Capability → classification: which classifications a capability relies on, useful for impact analysis when a classification's hierarchy is reshaped. Operation → capability (static and dynamic) are joinable separately; their divergence is itself a report row.

*AsciiDoctor render is one extension that knows three things.* It can render an operation by name (loads `.graphql`, emits the description as AsciiDoc prose + body + variables table), a coordinate's SDL excerpt with surrounding context, and a capability page composed of four sections in order: *preamble* (the `.adoc` body prose), *worked examples grouped by facet* (one block per `@exemplifies(capability: <slug>)` directive across the corpus, rendering the operation's description as prose + body + variables table), *surface* (auto-rendered exemplar coordinates with SDL excerpts; the participating coordinates via transitive closure live one click away as a "full surface" toggle), and *exercised by* (a coverage-diagnostic block listing operations in the corpus that touch the surface but don't carry an `@exemplifies` directive for this capability — useful as a candidate pool for future curation, kept distinct from worked examples so the doc page reads as authored prose, not as test telemetry). Capability authors write ~50 lines of preamble prose; operation authors write the example narration in the description string they're already authoring. The doc site grows three index axes: `/operations/...`, `/capabilities/...`, `/classifications/...`, all derived.

The user-facing payoff is a gap diagnostic on the inference-axis-coverage page. *Healthy*: the classification produced a fetcher and a documented operation exercises it. *Dead*: the classification produced a fetcher but no documented operation exercises it; prune or document the absence. *Blind spot*: a `fetcher_class` fired that codegen didn't produce; either it's hand-coded (intentional) or it points at a gap in the classifier hierarchy. The fourth quadrant (classifications that don't produce non-trivial fetchers) is intentionally not enumerated. On the capability axis: a capability with zero exercising operations is a documentation gap; a capability whose exemplars all sit in the `dead` quadrant is at risk; a capability whose static and dynamic operation sets diverge points at a test or runtime anomaly.

*Implementation phasing.* Each phase ships to trunk on its own with the build green and named verification before moving on.

| phase | scope | verification |
|---|---|---|
| A — schema & directives | Rename `leaves`→`classification`, `mentions`→`roadmap_mention`, `trace` view→`classifier_call` (R104 DDL and `ClassificationTrace.java` JSONL field names). Co-emit `generated_fetcher.jsonl` from the codegen visitor at every coordinate where codegen produces a fetcher; add the matching DuckDB table. Define `directive @capability(name: String!) repeatable on OBJECT \| FIELD_DEFINITION \| INTERFACE \| UNION` and `directive @exemplifies(capability: String!, facet: String!) repeatable on QUERY \| MUTATION \| SUBSCRIPTION` in graphitron's `directives.graphqls`. Assume `capabilities/` is populated by R115; add the `capability` table populated by a roadmap-tool scanner of those files; add `capability_coordinate` populated from an SDL parse (exemplars from directive scan; implicit rows from transitive-closure traversal); add `operation_exemplifies` populated by a corpus parse. | Existing R104 tests pass post-rename; a known coordinate produces a `generated_fetcher` row in the codegen pipeline test; the R115 catalog parses cleanly into `capability` rows; a test SDL with one `@capability` directive produces one exemplar row and the expected implicit closure; a test corpus operation with one `@exemplifies` directive produces one `operation_exemplifies` row; the integrity-check view "every `@capability` and `@exemplifies` capability value resolves to an authored slug" returns zero violations against the R115 catalog plus the pilot SDL and pilot operation. |
| B — runtime trace | Add the `Instrumentation` impl in `graphitron`, dormant unless `graphitron.execution.trace=<path>` is set. Add `OperationRunner` in `graphitron-fixtures-codegen` that loads a `.graphql` doc, builds a `GraphQL` instance with the Instrumentation attached, sets the operation-name ThreadLocal, and returns a handle with `execute(Map<String, Object>)`. | A unit-tier test exercises a stub `.graphql` end-to-end and asserts a single `fetcher_call.jsonl` row with the right `parent_type`, `field_name`, `fetcher_class`, and `operation`. |
| C — report and pilot | Add `classification_quadrant` view, capability-axis views (capability coverage, capability health, capability → classification, operation → capability static + dynamic), and the integrity-check views (every `@capability` and `@exemplifies` value resolves to an authored slug; every authored slug appears on at least one coordinate). Pilot: tag the films-paginated relation in `graphitron-sakila-example` with `@capability(name: "pagination")`; flesh `capabilities/pagination.adoc` from the R115 stub into preamble prose; convert the films-paginated test method in `GraphQLQueryTest` to use `OperationRunner` against `src/test/graphql/pagination/films-by-year.graphql` (operation `FilmsByYear` carrying `@exemplifies(capability: "pagination", facet: "simple-cursor-paging")` plus a description-string narration; sibling operation for the empty-year case without `@exemplifies`, since it's a coverage scenario, not an example). | Running `mvn install -Plocal-db -Pleaf-coverage` produces non-empty `classification_quadrant` rows (at minimum: a healthy classification for the films `RootField` and the actor/language `ChildField` arms exercised); the `pagination` capability shows as exercised in the capability-coverage view and shows the `FilmsByYear` operation as a worked example under facet `simple-cursor-paging`; static and dynamic operation→capability sets agree for the pilot operation; the integrity-check view returns zero violations. |
| D — docs render | AsciiDoctor extension under `/docs/` that loads `.graphql` files (operations + descriptions + `@exemplifies` directives), the `capabilities/*.adoc` files (preamble), and the SDL, and emits per-operation, per-capability, and per-coordinate pages. Operation descriptions render as AsciiDoc; capability worked-examples sections compose by joining `@exemplifies` directives across the corpus and grouping by facet. | The rendered docs site has the pilot operation as a standalone worked example page (description as AsciiDoc + body + variables table); the pagination capability page renders preamble + worked examples (one entry, faceted, sourced from the operation's `@exemplifies` directive) + surface (exemplar coordinates) + exercised-by (the coverage-diagnostic block, distinct from worked examples). |

Subsequent items convert additional execution-tier tests one at a time (`DmlBulkMutationsExecutionTest`, `FederationEntitiesDispatchTest`, `AddressOccupantsListBatchingTest`, ...) and add capability tags + `.adoc` files as new surfaces become testable; the machinery doesn't change.

Out of scope for this item: changing how classifications are produced at codegen time (this item only adds emission of the `generated_fetcher` co-stream and renames existing schema vocabulary); converting existing imperative execution-tier tests in bulk (an opt-in migration follows once the machinery lands); a `generated_type` table for the type-axis intersection (V0's four-quadrant report only needs the field axis; types are still classified and visible in `classifier_call`, but the codegen-output side is deferred until a report wants it); enumerating the capability-catalog itself (R115 ships the slug list and one-sentence definitions; this item consumes that catalog and adds the directive, tables, views, and render); long-form preamble prose for each capability (R115 produces one-sentence stubs; preamble grows over time as capabilities surface in worked examples); curating worked examples across all capabilities (R112 ships the *machinery* — the `@exemplifies` directive, integrity check, render — and pilots one example for `pagination`; populating examples for the rest of the catalog is an ongoing curation pass that follows in its own item, where each operation author registers their operation as it lands). Explicitly, no GraphQL directives that influence codegen or fetching are introduced; `@capability` is metadata read at SDL-parse time by the report tool and the AsciiDoctor extension only. Future axes that want to log semi-structured payloads (variable bindings per execution, fetcher arguments per call, GraphQL errors) can use DuckDB's `VARIANT` type, so we don't reflexively flatten everything when the time comes. If another future need surfaces (an N+1 budget per operation, a SQL-capture binding, a snapshot helper) it gets its own roadmap item with its own justification rather than riding on this one. This item lays the foundation; subsequent items adopt it scenario by scenario.
