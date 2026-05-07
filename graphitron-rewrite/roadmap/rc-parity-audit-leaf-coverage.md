---
id: R104
title: "RC parity audit: classify GraphitronField/Type leaves and ship coverage gaps"
status: In Review
bucket: validation
depends-on: []
---

# RC parity audit: classify GraphitronField/Type leaves and ship coverage gaps

graphitron's user-facing API is the directive surface plus a long list of
shape-driven inference paths: which sealed leaf of `GraphitronField` /
`GraphitronType` an SDL field/type lands on encodes "what graphitron decided".
A leaf the consumer can reach but that no `@ExecutionTier` test exercises is an
RC risk: validation may pass and codegen may run, but we have no proof that the
emitted code is correct end-to-end. The first release candidate of graphitron
10 needs an explicit, leaf-by-leaf accounting of what is covered, what is not,
and which gaps are RC-blocking versus deferrable.

This item produces the **regenerable coverage table** at
`graphitron-rewrite/roadmap/inference-axis-coverage.adoc`, the tooling that
keeps it in sync with trunk (registries that funnel classification writes,
a JSONL trace, a JUnit extension that tags records with the running test
class and tier, and a `roadmap-tool leaf-coverage` post-processor), and
the consumer-facing migration-guide section that the same tooling feeds.
The *triage* step (classifying each leaf into Covered / Trivial gap /
RC-blocker / Defer and spawning sibling Backlog items per gap) is a
separate item that depends on this one: the table makes the triage trivial
to do once, hard to do up-front.

## Phase A (already shipped)

- `roadmap-tool directive-support` subcommand (commit `db42dbd7`) compares
  legacy vs rewrite directives.graphqls and reports execution-tier coverage
  per directive. Used to filter the directive surface before this audit.
- Directive-level findings already absorbed:
  - `@experimental_constructType` and `@experimental_procedureCall`: no
    adopters, ignored.
  - `@notGenerated`: already deprecated; rewrite classifier rejects.
  - `@multitableReference`: no adopters, deprecated under R44.
  - `@field(javaName:)` and `@externalField(reference:!)` shape changes: noted
    in the migration guide.
- Interface/union axes (table-bound `@discriminate`, multi-table interface,
  multi-table union, error-payload union): all four shapes confirmed in
  sakila with `@ExecutionTier` coverage.

## Resolved design questions

- **Trace emission point:** introduce a `TypeRegistry` and `FieldRegistry`
  that wrap the previously-bare `BuildContext.types` field and the
  `fields` map in `GraphitronSchemaBuilder.buildSchema`. The maps become
  private. Type and output-field writes go through named operations
  (`classify`, `enrich`, `demote`, `synthesize`) that carry trace
  emission internally; the input-field path (`BuildContext.classifyInputField`)
  routes through a sibling `FieldRegistry.classifyInput` operation that
  is trace-only, since input fields are stored embedded in their parent
  type rather than in a central map. This replaces the original "walk
  the final maps" approach with funnel tracing again, but the funnels
  are now enforced architecturally for the central-map paths (the
  federation demotions in `EntityResolutionBuilder`, the node-id
  collision demotion in `TypeBuilder.validateNodeTypeIdUniqueness`, and
  the connection-type synthesis in
  `GraphitronSchemaBuilder.promoteConnectionTypes` each pick a named
  operation, and Java access modifiers prevent new bypass sites). The
  input-field path remains funneled by convention since there is no
  central state to make private; section 1a names the trade-off
  honestly rather than fighting it.
- **Source provenance:** `SourceLocation.getSourceName()` is reliable for
  schemas loaded via `RewriteSchemaLoader` (uses
  `MultiSourceReader.trackData(true)`) and empty for `TestSchemaHelper`
  inline-string fixtures. Spec accepts the asymmetry.
- **Tier of test:** captured per trace record via a JUnit extension (step
  1d) that auto-registers and writes the running test class plus its tier
  annotation into a ThreadLocal context. The registries read that context
  and tag every record. This avoids both a fragile fixture manifest and a
  brittle naming-convention scan; the runtime is the source of truth.
- **Test-to-leaf mapping:** captured implicitly by the JUnit extension
  (every leaf classified during a test's lifecycle is attributed to that
  test). Existing javadoc and naming convention (`TableFieldPipelineTest`
  documents "inline `ChildField.TableField` emission", roughly 80% of
  pipeline tests) remain useful for human navigation but are not the
  machine-readable input. `GraphitronSchemaBuilderTest`'s per-shape
  `ClassificationCase` enums (a sealed interface implemented by
  `ColumnFieldCase`, `TableFieldCase`, etc.) and `VariantCoverageTest`'s
  `NO_CASE_REQUIRED` already give classification-tier coverage
  statically; the trace covers everything above classification.
- **Analysis store:** the audit is a multi-source join (trace records ×
  sealed-permits enumeration × roadmap mentions), and the follow-up
  triage item adds at least two more sources (load-bearing keys,
  fixture-tier metadata) that join on `leaf` without changing the
  aggregation shape. The post-processor uses embedded DuckDB so new
  sources land as new staged tables joined into existing queries,
  rather than as new imperative aggregators in Java. The justification
  is extensibility under known follow-on data, not data scale (the
  current data fits in memory trivially). DuckDB is in-process and
  ephemeral: `read_json_auto` reads the JSONL traces directly via a
  glob across all module `target/leaf-coverage.jsonl` files, permits
  and roadmap mentions are inserted into in-memory tables, the queries
  run, the connection closes. The glob is what makes per-module
  emission viable: each rewrite module truncates and writes its own
  file, DuckDB unions them at read time without an aggregation step.
  No persisted `.duckdb` file: an auditor who wants ad-hoc pivots can
  open the JSONL traces themselves with the `duckdb` CLI
  (`duckdb -c "SELECT ... FROM read_json_auto('graphitron-rewrite/**/target/leaf-coverage.jsonl')"`),
  which avoids carrying a staleness vector. SQL does joins, filters,
  and aggregates; Java does AsciiDoc shaping; the boundary stays
  there. Generator-side stays JSONL: append-only emission from forked
  test JVMs is naturally lock-free, and JDBC stays out of the
  production classpath.

## Phase B scope (this item)

**Status: every section below shipped.** Landing commits, by section:

- Section 1a registries + 1b call-site rewrite: `14386cfc8` (production
  registries) + `8ddfb272f` (unit tests for precondition contracts and
  trace-emitter smoke).
- Section 1c `-Pleaf-coverage` profile: `5bab9ca7f`.
- Section 1d JUnit extension for test-class context: `a032c96f3`.
- Section 2 `roadmap-tool leaf-coverage` post-processor: `8fd019605`.
- Section 3 `directive-support --mode=migration` render: `7a6a87e69`.
- Section 4 migration-guide section: `576e7da3a`.

Honest deviation from the spec: the directive-support migration
fragment intentionally has no verify-mode CI binding in roadmap-tool's
verify phase, because the fragment depends on
`graphitron-common/src/main/resources/directives.graphqls` (a legacy
module) which the rewrite reactor explicitly does not resolve (per the
"Rewrite builds independently of legacy modules" principle). The
leaf-coverage report's verify-mode does run in CI and gates the
heavier "classifier covers every leaf" guarantee; the directive-support
fragment regenerates from the docs build instead, where the legacy
path is in scope.

### 1. Add classifier trace emission to the generator

Two sub-pieces of refactor (1a registries, 1b operation rewrite), plus the
property/profile that turns tracing on (1c) and a JUnit extension that
supplies test-class context (1d).

#### 1a. Introduce `TypeRegistry` and `FieldRegistry`

Replace the bare `Map<String, GraphitronType>` currently held as
`BuildContext.types` with a `TypeRegistry` whose backing map is private.
Four named operations replace direct `put` calls, each carrying a clean
semantic assertion:

- `classify(name, type)`: primary classification, asserts no prior entry.
  Used by `TypeBuilder.classifyType`.
- `enrich(name, type)`: replaces an entry with a structurally compatible
  enriched version (e.g. `InterfaceType` with empty participants → with
  resolved participants). Asserts prior entry exists. Used by the
  enrichment pass at `TypeBuilder.buildTypes` (the `replaceAll` over the
  result of the first pass).
- `demote(name, type)`: replaces an entry with `UnclassifiedType` (or
  any classification regression). Asserts prior entry exists. Used by
  `EntityResolutionBuilder.build` for federation validation failures and
  by `TypeBuilder.validateNodeTypeIdUniqueness` for `@nodeId` collisions.
- `synthesize(name, type)`: graphitron-generated type with no SDL origin,
  asserts no prior entry. Used by
  `GraphitronSchemaBuilder.promoteConnectionTypes` for `ConnectionType` /
  `EdgeType` / `PageInfoType`.

`FieldRegistry` covers both output-field and input-field classification;
the asymmetry between "central map" (output) and "embedded in parent"
(input) is honoured by two named operations rather than fought:

- `classify(coordinates, GraphitronField)`: output-field path. Writes
  to a private `Map<FieldCoordinates, GraphitronField>` (the existing
  `fields` map in `GraphitronSchemaBuilder.buildSchema`, lifted into
  the registry) and emits a trace record. Asserts no prior entry.
  Used at every call site of `FieldBuilder.classifyField`.
- `classifyInput(parent, name, location, InputFieldResolution)`:
  input-field path. Emits a trace record only — input fields are
  embedded in the parent `InputType` / `TableInputType.inputFields`
  (or `ArgumentRef.PlainInputArg.fields()`) by their owner, not stored
  in a central map, so the registry doesn't own their persistence.
  Accepts the sealed `InputFieldResolution` so both `Resolved` (leaf
  is the contained `InputField`'s record class) and `Unresolved` (leaf
  empty, `rejection` populated from the carried message) emit one
  record per call. Used at every call site of
  `BuildContext.classifyInputField`.

Research found no demote/enrich/synthesize sites for either path; if
the refactor surfaces one, add it symmetrically.

Trace emission is uniform across operations:
1. Validate the prior-entry precondition (`classify` / `synthesize`:
   no prior; `enrich` / `demote`: prior must exist; `classifyInput`:
   no precondition, the input-field path has no central state).
2. Write to the private map (skipped by `classifyInput`, which has no
   backing map).
3. Emit a JSONL record to the path set by
   `-Dgraphitron.classification.trace=<path>` if the property is set.
   Default off; production runs incur no cost.

Architectural enforcement is honestly asymmetric. For types and output
fields, the private maps prevent a bypass without adding a public
method on the registry (visible in code review). For input fields,
the registry is the canonical trace point but `classifyInputField`
remains the sole producer by convention; a future bypass would have
to introduce a parallel input-classifier path that doesn't route
through the registry. The asymmetry follows the underlying storage
model rather than fighting it.

Trace record fields:

- `op`: one of `classify`, `enrich`, `demote`, `synthesize`. The audit
  consumer filters on these differently: `enrich` is benign by
  construction (prior entry replaced by a structurally compatible richer
  version, e.g. an `InterfaceType` gaining its resolved participants);
  `demote` is always worth scrutinising (prior entry replaced by a
  classification regression, typically to `UnclassifiedType` /
  `UnclassifiedField`); `classify` is the primary case; `synthesize` is
  a graphitron-generated entry with no SDL origin
  (connection/edge/pageinfo).
- `parent`: parent type name (empty for top-level types)
- `name`: field/type name
- `leaf`: fully-qualified record class, e.g. `ChildField.TableMethodField`
- `source`: `SourceLocation.getSourceName()` from the field/type's SDL
  location. Populated for schemas loaded via `RewriteSchemaLoader` (sakila,
  federated, real generator runs); null/empty for `TestSchemaHelper`
  inline-string fixtures used in unit and pipeline tests. Acceptable
  asymmetry: the leaf still appears in the trace, it just lacks fixture
  provenance.
- `rejection`: present only on `UnclassifiedField` / `UnclassifiedType`;
  carries the `RejectionKind` plus message so the post-processor can
  separate `[deferred]` (legitimately stub-tagged) from `[author-error]` /
  `[invalid-schema]` (which would be a real classification regression if
  seen on a fixture we expected to pass).
- `test`: fully-qualified test class name when classification runs inside
  a JUnit lifecycle (populated by 1d). Empty when classification runs
  outside tests (e.g. `graphitron:generate` from a real consumer build).
- `tier`: one of `unit`, `pipeline`, `compilation`, `execution`, or
  `cross-cutting` (the last covers tests carrying `@Tag("cross-cutting")`
  instead of a tier annotation), derived from the test class by 1d.
  Empty when `test` is empty. The four-arm tier ordering used by the
  post-processor's "highest tier observed" aggregation (step 2) is
  `unit < pipeline < compilation < execution`; `cross-cutting`
  records do not participate in the ordering and surface in a
  separate report column (`cross-cutting: yes/no`) so a leaf
  exercised only by cross-cutting tests is visibly distinct from a
  leaf with no test coverage at all.

Implementation note: the existing `BuildWarning` mechanism (`ctx.addWarning`)
is for *warnings* surfaced to schema authors and has the wrong semantics
for routine classification trace. Keep the registries' emitter separate.
The emitter is a no-op when the trace property is unset, so production
generator runs (where the property is never set) pay nothing.

#### 1b. Rewrite call sites to use named operations

Touch points discovered in research:

- `TypeBuilder.classifyType` → `registry.classify` (return value still
  flows back to caller for use within `buildTypes`).
- `TypeBuilder.buildTypes` enrichment pass (TypeBuilder.java:141-158, the
  `replaceAll` over participant-enriched interface/union variants) →
  `registry.enrich`.
- `TypeBuilder.validateNodeTypeIdUniqueness` (TypeBuilder.java:182) →
  `registry.demote`.
- `EntityResolutionBuilder.build` (lines 104, 110, 128) →
  `registry.demote`.
- `GraphitronSchemaBuilder.promoteConnectionTypes` (lines 373, 377, 386,
  391) → `registry.synthesize`.
- `FieldBuilder.classifyField` callers → `fieldRegistry.classify`.
- `BuildContext.classifyInputField` (every call site:
  `TypeBuilder.buildTypes` for `@table` / plain input types,
  `InputFieldResolver.resolve` for plain-input arguments, and the
  recursive nested-input call within `classifyInputField` itself) →
  `fieldRegistry.classifyInput`.

#### 1c. Property and profile

A Maven profile `-Pleaf-coverage` sets the property to
`${project.build.directory}/leaf-coverage.jsonl` (per-module) and runs
`mvn verify` so each module emits its own trace file under its own
`target/`. The post-processor (step 2) reads them as a glob via
DuckDB's `read_json_auto`, which unions records across files
transparently — there is no reactor-shared file to coordinate.

Each module's pre-test phase deletes its own file (a `maven-antrun`
binding in `process-test-resources` for surefire-classifying modules,
`pre-integration-test` for failsafe-classifying ones) so a re-run
doesn't append on top of stale records. Forked JVMs within one
module append concurrently and lock-free; per-module scoping means
one module's truncation never wipes another's records, which is the
failure mode a reactor-shared file would have to avoid.

#### 1d. JUnit extension for test-class context

A JUnit 5 extension auto-registered via
`META-INF/services/org.junit.jupiter.api.extension.Extension` (plus
`junit.jupiter.extensions.autodetection.enabled=true` in the test module's
`junit-platform.properties`) so no test class needs `@ExtendWith`.

`BeforeAllCallback`: read the test class's tier annotation
(`@UnitTier` / `@PipelineTier` / `@CompilationTier` / `@ExecutionTier`, or
the `@Tag("cross-cutting")` exemption) and set
`ClassificationTrace.currentContext = (className, tier)` in a ThreadLocal.
`AfterAllCallback`: clear it.

`@BeforeAll` schema-building (e.g. `GraphQLQueryTest` spinning up
PostgreSQL and loading sakila) runs inside the active context, so its
classification records get tagged. Per-`@Test` granularity is unnecessary;
class-level is enough.

Lives in the test sources of the graphitron module so the production jar
stays free of JUnit dependencies. The `ClassificationTrace` emitter sits
in main sources with a no-op default context and a setter the extension
calls.

### 2. Build the `roadmap-tool leaf-coverage` post-processor

Subcommand alongside the existing `directive-support`, backed by embedded
DuckDB (`org.duckdb:duckdb_jdbc`, roadmap-tool dependency only). DuckDB
runs in-process and ephemeral: open an in-memory connection, register
the JSONL traces as a view, stage the small parsed tables, run the
queries, render, close. No persisted `.duckdb` file. An auditor who
wants ad-hoc pivots can point `duckdb` at the JSONL files directly.

The `org.duckdb:duckdb_jdbc` artifact bundles JNI binaries for the
platforms graphitron-rewrite is built on. Confirm linux-amd64,
linux-arm64, and macOS-arm64 (the CI and common-developer platforms)
resolve out-of-the-box during implementation; if a platform needs a
classifier-jar, add it to the `roadmap-tool` dependency block.

Inputs and staging:

- The per-module JSONL traces from step 1, exposed as a view via
  `CREATE VIEW trace AS SELECT * FROM read_json_auto('graphitron-rewrite/**/target/leaf-coverage.jsonl', union_by_name = true)`.
  The glob covers every module's `target/leaf-coverage.jsonl`;
  `union_by_name` makes the view robust if a future module adds a
  trace field the others don't.
- Sealed `permits` clauses parsed from the model sources, inserted into a
  `leaves(hierarchy, leaf, intent)` table. Source files:
  - `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronField.java`
  - `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/RootField.java`
  - `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ChildField.java`
  - `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/InputField.java`
  - `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronType.java`
- Roadmap mentions (greps `roadmap/*.md` for leaf class names), inserted
  into a `mentions(leaf, roadmap_id)` table.

The follow-up triage item (and any later audits) add new staged tables
(e.g. `LoadBearingGuaranteeAuditTest` keys, fixture-tier metadata) that
join on `leaf` without rewriting the post-processor's aggregation
shape. SQL absorbs the join logic; Java shapes the result rows into
AsciiDoc. Keep the boundary clean: SQL does joins, filters, aggregates;
Java does rendering.

Outputs:

- **Internal report** at `graphitron-rewrite/roadmap/inference-axis-coverage.adoc`,
  regenerated by the standard `roadmap-tool` exec target alongside
  `README.md`. Verify-mode fails CI when the file drifts from current
  state, same guard as the roadmap README. One row per leaf: hierarchy,
  intent (one-line javadoc), trace count, distinct fixtures observed,
  highest test tier observed, the test classes that exercised the leaf,
  and roadmap mentions of the leaf class name. Triage (Covered /
  Trivial gap / RC-blocker / Defer) is *not* in this report; that lands
  in the follow-up triage item below.
- **`--mode=migration` AsciiDoc fragment** for `include::` from the
  migration guide: a "supported schema shapes" list keyed off the leaf
  data. Consumer-facing wording, internal columns elided.

Tier classification per leaf is computed in SQL as the *highest* `tier`
value across that leaf's trace records (`unit < pipeline < compilation <
execution`). `cross-cutting` records are excluded from the `MAX()` and
contribute to a separate boolean column (`cross-cutting`) in the report,
so a leaf exercised only by cross-cutting tests reads as
`tier: -, cross-cutting: yes` rather than collapsing into the
no-coverage row. Records with empty `tier` (classification outside any
test, e.g. real generator runs) count as classification-tier coverage
only. `VariantCoverageTest`'s `NO_CASE_REQUIRED` map remains a useful
cross-reference: a leaf there with non-zero trace records still warrants
scrutiny.

Every trace record carries the test class that produced it, so the
report can list "ChildField.TableField is exercised by
TableFieldPipelineTest (pipeline) and GraphQLQueryTest (execution)" with
no inference required.

The classification trace must be regenerated before regenerating the
report (running `mvn -Pleaf-coverage verify` produces the input).
Document the two-step regeneration in
`graphitron-rewrite/docs/workflow.adoc` alongside the existing roadmap
README regeneration note.

### 3. Extend `directive-support` with a `--mode=migration` render

Today the tool prints an internal report. Add a second render mode that
emits an AsciiDoc fragment suitable for `include::` from the migration
guide: supported directives and shape-change notes. The "supported schema
shapes" portion comes from `leaf-coverage --mode=migration` (step 2); this
fragment is directive-only, the two combine in the migration guide. The
internal mode (current default) stays unchanged. The roadmap item that owns
the docs build wiring is `R9` (docs site); this item only owns the
rendering.

### 4. Author the migration-guide section

Target: `docs/manual/how-to/migrating-from-legacy.adoc`. The section names
what is supported (driven by both `--mode=migration` fragments: directive
list from `directive-support`, shape list from `leaf-coverage`), what
changed in shape (already drafted; cross-link the existing entries), and
what is removed (`@notGenerated`, `@multitableReference`, anything else
surfaced during classification). Consumer-facing wording; no internal "is
this fixture-covered" detail.

## Follow-up items (spawned by this one)

- **Triage and gap-closure** (separate Backlog item, depends on this).
  Once `inference-axis-coverage.adoc` is regenerable, classify each leaf
  as Covered / Trivial gap / RC-blocker / Defer and spawn one sibling
  Backlog item per RC-blocker. Doing this up-front (before the data
  exists) requires re-deriving the table by hand; doing it once the
  table is in front of you is a read-and-bucket exercise, hence the
  split. The triage item's scope should also cross-reference the table
  against `LoadBearingGuaranteeAuditTest`'s nine keys (consumer sites
  that depend on a load-bearing classifier check) so load-bearing keys
  without execution-tier evidence are caught alongside bare
  leaf-coverage holes.

## Done definition

- `TypeRegistry` and `FieldRegistry` replace the bare maps that previously
  held classification results; type / output-field writes go through
  `classify` / `enrich` / `demote` / `synthesize` against private maps,
  and the input-field path goes through `classifyInput` (trace-only,
  no backing map). All known bypass sites (`TypeBuilder.buildTypes`
  enrichment pass, `EntityResolutionBuilder`,
  `validateNodeTypeIdUniqueness`, `promoteConnectionTypes`,
  every call site of `BuildContext.classifyInputField`) call the
  appropriate named operation.
- Unit-tier `TypeRegistryTest` and `FieldRegistryTest` cover the
  precondition contracts: `classify` / `synthesize` reject duplicate
  entries; `enrich` / `demote` reject when the prior entry is missing;
  `classifyInput` accepts both `Resolved` and `Unresolved` resolutions
  without requiring central storage. A focused emitter smoke test
  sets `-Dgraphitron.classification.trace` to a temp file, drives one
  operation per arm, parses the JSONL output, and asserts the
  documented field set (`op`, `parent`, `name`, `leaf`, `source`,
  `rejection` where applicable, `test`, `tier`) is present.
- Classifier-trace emission lives inside the registries, gated on
  `-Dgraphitron.classification.trace=<path>`, with a `-Pleaf-coverage`
  Maven profile that produces a per-module trace file at each
  module's `target/leaf-coverage.jsonl`.
- A JUnit 5 extension auto-registers in the test classpath and tags every
  trace record with the running test class and its tier annotation.
- `roadmap-tool leaf-coverage` reads the per-module JSONL traces via
  DuckDB `read_json_auto` over a `**/target/leaf-coverage.jsonl`
  glob, stages parsed `leaves` and `mentions` tables in an in-memory
  connection, and renders both an internal report at
  `roadmap/inference-axis-coverage.adoc` and a `--mode=migration`
  AsciiDoc fragment from SQL queries.
- Every sealed leaf of `RootField`, `ChildField`, `InputField`,
  `GraphitronType` has an entry in the internal report (as data, not
  triage).
- `directive-support --mode=migration` renders an AsciiDoc fragment that
  the docs build includes successfully.
- The migration-guide section ships in `migrating-from-legacy.adoc`.
- Verify-mode of `roadmap-tool` fails CI when either the README or the
  internal coverage report drifts from current state. The CI workflow
  runs `mvn -Pleaf-coverage verify` before the verify-mode check so the
  per-module JSONL traces exist; verify-mode of `leaf-coverage`
  short-circuits with a clear "no trace files found, skipping" diagnostic
  (rather than failing) when the glob matches nothing, so a contributor
  running `roadmap-tool` locally without the profile sees a useful
  message instead of a confusing drift report. README verify-mode
  remains unconditional; the coverage report's verify-mode is the only
  one that depends on a prior trace step.

## Non-goals

- Adding a marker interface to tag surface-level inference leaves. The idea
  is recorded for post-RC; whether to add it is a fresh design decision once
  classification has run and the marker's payoff is concrete.
- Triaging the table or closing the gaps. Both happen in the follow-up
  Backlog item; this one ships the regenerable data.
- Validating any axis outside the directive + sealed-leaf surface. Other
  RC-readiness concerns (plugin packaging, `graphitron:dev` polish, error
  message ergonomics) are tracked separately; this item stays scoped to
  the shape-inference and directive-coverage surface.
