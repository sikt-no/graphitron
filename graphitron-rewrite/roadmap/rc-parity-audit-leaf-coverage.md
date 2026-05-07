---
id: R104
title: "RC parity audit: classify GraphitronField/Type leaves and ship coverage gaps"
status: Spec
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
  private. All writes go through named operations (`classify`, `enrich`,
  `demote`, `synthesize`) that carry the trace emission internally.
  This replaces the original "walk the final maps" approach with funnel
  tracing again, but the funnels are now enforced architecturally rather
  than by convention: the federation demotions in `EntityResolutionBuilder`,
  the node-id collision demotion in
  `TypeBuilder.validateNodeTypeIdUniqueness`, and the connection-type
  synthesis in `GraphitronSchemaBuilder.promoteConnectionTypes` each pick
  a named operation. Java access modifiers prevent new bypass sites; no
  separate enforcement test is needed.
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
  machine-readable input. `GraphitronSchemaBuilderTest`'s `ClassificationCase`
  enum and `VariantCoverageTest`'s `NO_CASE_REQUIRED` already give
  classification-tier coverage statically; the trace covers everything
  above classification.
- **Analysis store:** the audit is a multi-source join (trace records ×
  sealed-permits enumeration × roadmap mentions, with more sources to
  come from the follow-up triage item). The post-processor uses
  embedded DuckDB to query across these rather than imperative
  aggregation in Java. DuckDB reads the JSONL trace directly via
  `read_json_auto`; permits and roadmap mentions are parsed in Java and
  inserted as tables. Renders are SQL-result-to-AsciiDoc. The persisted
  `.duckdb` file is a browsable artefact for ad-hoc auditor queries.
  Generator-side stays JSONL: append-only emission from many forked
  JVMs is naturally lock-free, and we avoid pulling JDBC into the
  production classpath.

## Phase B scope (this item)

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

Same pattern for the local `fields` map in
`GraphitronSchemaBuilder.buildSchema`, lifted into a `FieldRegistry`.
Initial scope is just `classify`; research found no demotion, enrichment,
or synthesis of fields outside `classifyField`. If the refactor surfaces a
site that needs another operation, add it symmetrically.

The registries take ownership of trace emission. Each operation:
1. Validates the prior-entry precondition.
2. Writes to the private map.
3. Emits a JSONL record to the path set by
   `-Dgraphitron.classification.trace=<path>` if the property is set.
   Default off; production runs incur no cost.

Java access modifiers do the architectural enforcement: with the maps
private, a new bypass requires either adding a public method to the
registry (visible in code review) or breaking encapsulation in a way that
fails a basic visibility check.

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
  Empty when `test` is empty.

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
- `FieldBuilder.classifyField` and the input-field path through
  `BuildContext.classifyInputField` → `fieldRegistry.classify`.

#### 1c. Property and profile

A Maven profile `-Pleaf-coverage` sets the property to
`${session.executionRootDirectory}/target/leaf-coverage.jsonl`, truncates
the file at the start of the test run, and runs `mvn verify` so the trace
accumulates across all tiers in one file. The aggregate file is the input
to step 2.

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
DuckDB (`org.duckdb:duckdb_jdbc`, roadmap-tool dependency only).

Inputs and staging:

- The JSONL trace from step 1 (`target/leaf-coverage.jsonl`), exposed as
  a view via `CREATE VIEW trace AS SELECT * FROM read_json_auto(...)`.
- Sealed `permits` clauses parsed from the model sources, inserted into a
  `leaves(hierarchy, leaf, intent)` table. Source files:
  - `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronField.java`
  - `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/RootField.java`
  - `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ChildField.java`
  - `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/InputField.java`
  - `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronType.java`
- Roadmap mentions (greps `roadmap/*.md` for leaf class names), inserted
  into a `mentions(leaf, roadmap_id)` table.

The database persists at `target/leaf-coverage.duckdb` and is itself a
deliverable artefact: an auditor with the `duckdb` CLI can run ad-hoc
pivots without touching roadmap-tool. The follow-up triage item (and any
later audits) can add new staged tables (e.g. `LoadBearingGuaranteeAuditTest`
keys, fixture-tier metadata) that join on `leaf` without rewriting the
post-processor.

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
execution`). Records with empty `tier` (classification outside any test,
e.g. real generator runs) count as classification-tier coverage only.
`VariantCoverageTest`'s `NO_CASE_REQUIRED` map remains a useful
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
  held classification results; all writes go through `classify` / `enrich`
  / `demote` / `synthesize` operations and the underlying maps are
  private. All known bypass sites (`TypeBuilder.buildTypes` enrichment
  pass, `EntityResolutionBuilder`, `validateNodeTypeIdUniqueness`,
  `promoteConnectionTypes`) call the appropriate named operation.
- Classifier-trace emission lives inside the registries, gated on
  `-Dgraphitron.classification.trace=<path>`, with a `-Pleaf-coverage`
  Maven profile that produces the aggregate trace file.
- A JUnit 5 extension auto-registers in the test classpath and tags every
  trace record with the running test class and its tier annotation.
- `roadmap-tool leaf-coverage` ingests the trace into embedded DuckDB at
  `target/leaf-coverage.duckdb` (alongside staged `leaves` and `mentions`
  tables) and renders both an internal report at
  `roadmap/inference-axis-coverage.adoc` and a `--mode=migration`
  AsciiDoc fragment from SQL queries against that store.
- Every sealed leaf of `RootField`, `ChildField`, `InputField`,
  `GraphitronType` has an entry in the internal report (as data, not
  triage).
- `directive-support --mode=migration` renders an AsciiDoc fragment that
  the docs build includes successfully.
- The migration-guide section ships in `migrating-from-legacy.adoc`.
- Verify-mode of `roadmap-tool` fails CI when either the README or the
  internal coverage report drifts from current state.

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
