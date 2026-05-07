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

The deliverables are a regenerable classification table driven by a
classifier-trace mechanism, one or more sibling Backlog items per RC-blocking
gap, and a consumer-facing migration-guide section. The "if we don't generate
then validation fails" rule means an `@ExecutionTier` test that includes shape
X is the proof that X is fully supported (parses, classifies, validates,
generates, executes). Pipeline-tier or unit-tier coverage alone does not count.

The reproducibility approach: the classifier already does all the work on
every test run, so we make it emit a structured trace gated on a system
property, and a roadmap-tool subcommand post-processes the trace into the
coverage report. New fixtures and new tests then update the data
automatically, with no separate registry to keep in sync.

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

- **Trace emission point:** one walk, not three funnels. The classifier
  has post-classification mutations (federation demotions in
  `EntityResolutionBuilder`, node-id collision demotions in
  `TypeBuilder.validateNodeTypeIdUniqueness`, and synthesized
  connection/edge/pageinfo types in `GraphitronSchemaBuilder.promoteConnectionTypes`)
  that bypass `classifyType` / `classifyField`. Trace at
  `GraphitronSchemaBuilder.buildSchema` immediately before
  `new GraphitronSchema(...)` is constructed (around
  GraphitronSchemaBuilder.java:216), walking `ctx.types` and the final
  `fields` map. That is the single source of truth for what the consumer
  actually receives.
- **Source provenance:** `SourceLocation.getSourceName()` is reliable for
  schemas loaded via `RewriteSchemaLoader` (uses
  `MultiSourceReader.trackData(true)`) and empty for `TestSchemaHelper`
  inline-string fixtures. Spec accepts the asymmetry.
- **Tier of test:** captured per trace record via a JUnit extension (step
  1c) that auto-registers and writes the running test class plus its tier
  annotation into a ThreadLocal context. The trace emitter reads that
  context and tags every record. This avoids both a fragile fixture
  manifest and a brittle naming-convention scan; the runtime is the source
  of truth.
- **Test-to-leaf mapping:** captured implicitly by the JUnit extension
  (every leaf classified during a test's lifecycle is attributed to that
  test). Existing javadoc and naming convention (`TableFieldPipelineTest`
  documents "inline `ChildField.TableField` emission", roughly 80% of
  pipeline tests) remain useful for human navigation but are not the
  machine-readable input. `GraphitronSchemaBuilderTest`'s `ClassificationCase`
  enum and `VariantCoverageTest`'s `NO_CASE_REQUIRED` already give
  classification-tier coverage statically; the trace covers everything
  above classification.

## Phase B scope (this item)

### 1. Add classifier trace emission to the generator

Three sub-pieces: the emitter itself (1a), the property/profile that turns
it on (1b), and a JUnit extension that supplies test-class context (1c).

#### 1a. Emitter

Single emission site: `GraphitronSchemaBuilder.buildSchema` immediately
before `new GraphitronSchema(...)` is invoked (around
GraphitronSchemaBuilder.java:216). At that point both `ctx.types` and the
deduplicated `fields` map are final and complete. The emitter walks both
and writes one JSONL record per entry.

This deliberately *replaces* a three-funnel approach
(`TypeBuilder.classifyType`, `FieldBuilder.classifyField`,
`BuildContext.classifyInputField`) because the funnel approach misses
post-classification mutations:

- `EntityResolutionBuilder.build` (federation pass) demotes types to
  `UnclassifiedType` via direct `types.put` (lines 104, 110, 128).
- `TypeBuilder.validateNodeTypeIdUniqueness` demotes on `@nodeId`
  collisions via direct `types.put` (line 182).
- `GraphitronSchemaBuilder.promoteConnectionTypes` synthesizes
  `ConnectionType` / `EdgeType` / `PageInfoType` entries and writes them
  via direct `types.put` (lines 373, 377, 386, 391).

Walking the final maps captures what the consumer actually receives, which
is what we want to audit.

Emit a JSONL record per entry to the path set by
`-Dgraphitron.classification.trace=<path>`. Default off; production runs
incur no cost. Record fields:

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
  `[invalid-schema]` (would be a real classification regression if seen on
  a fixture we expected to pass)
- `test`: fully-qualified test class name when classification runs inside
  a JUnit lifecycle (populated by 1c). Empty when classification runs
  outside tests (e.g. `graphitron:generate` from a real consumer build).
- `tier`: one of `unit`, `pipeline`, `compilation`, `execution`, or
  `cross-cutting`, derived from the test class's tier annotation by 1c.
  Empty when `test` is empty.

Implementation note: the existing `BuildWarning` mechanism (`ctx.addWarning`)
is for *warnings* surfaced to schema authors and has the wrong semantics for
routine classification trace. Add a dedicated trace emitter (one short
helper, e.g. `ClassificationTrace.dump(types, fields)`) invoked once from
`GraphitronSchemaBuilder.buildSchema` just before the schema record is
constructed. The emitter is a no-op when the trace property is unset.

#### 1b. Property and profile

A Maven profile `-Pleaf-coverage` sets the property to
`${session.executionRootDirectory}/target/leaf-coverage.jsonl`, truncates
the file at the start of the test run, and runs `mvn verify` so the trace
accumulates across all tiers in one file. The aggregate file is the input
to step 2.

#### 1c. JUnit extension for test-class context

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

#### Permits enumeration

The post-processor (step 2) enumerates the universe of leaves by parsing
`permits` clauses from these sources, and reports any leaf that never
appeared in the trace:

- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronField.java`
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/RootField.java`
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ChildField.java`
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/InputField.java`
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronType.java`

`VariantCoverageTest`'s `NO_CASE_REQUIRED` map remains a useful
cross-reference for documented classification gaps; entries there deserve
scrutiny rather than blanket acceptance.

### 2. Build the `roadmap-tool leaf-coverage` post-processor

Subcommand alongside the existing `directive-support`. Inputs: the trace
JSONL emitted by step 1, plus the sealed-permits source files. Reads the
roadmap directory for class-name mentions, same convention as the other
subcommands.

Outputs:

- **Internal report** at `graphitron-rewrite/roadmap/inference-axis-coverage.adoc`,
  regenerated by the standard `roadmap-tool` exec target alongside
  `README.md`. Verify-mode fails CI when the file drifts from current state,
  same guard as the roadmap README. The report carries one row per leaf
  with: hierarchy, intent (one-line javadoc), trace count, distinct
  fixtures observed, classification tier (step 3), and roadmap mentions.
- **`--mode=migration` AsciiDoc fragment** for `include::` from the
  migration guide: a "supported schema shapes" list keyed off the leaf
  classification. Consumer-facing wording, internal columns elided.

The post-processor enumerates leaves from sealed `permits` clauses and
flags any leaf with zero trace records as a coverage hole. Tier
classification per leaf is the *highest* `tier` value across all trace
records for that leaf: a leaf with at least one record at `tier=execution`
is execution-covered, otherwise the highest tier observed determines the
ranking. Records with empty `tier` (classification outside any test, e.g.
real generator runs) count as classification-tier coverage only.

This is exact data: every record carries the test class that produced it,
so the report can list "ChildField.TableField is exercised by
TableFieldPipelineTest (pipeline) and GraphQLQueryTest (execution)" with
no inference.

The classification trace must be regenerated before regenerating the report
(running `mvn -Pleaf-coverage verify` produces the input). Document the
two-step regeneration in `graphitron-rewrite/docs/workflow.adoc` alongside
the existing roadmap README regeneration note.

### 3. Classify each leaf into one of four tiers

- **Covered**: sakila has the shape, an `@ExecutionTier` test asserts the
  generated behaviour, no further action.
- **Trivial gap**: sakila lacks the shape but a small fixture addition
  closes it; pre-RC work, gets a sibling Backlog item.
- **RC-blocker**: substantive work needed (new fixtures, generator changes,
  or design decisions); gets a sibling Backlog item with appropriate
  bucket and a `depends-on: [rc-parity-audit-leaf-coverage]` linkage if
  this item is the source.
- **Defer**: internal-only distinction (siblings collapse to the same
  consumer surface), or stub-tagged for a future feature; documented and
  excluded from RC.

The two-tier vs three-tier output question (must-ship / nice-to-have / post-RC)
is a design decision left for Spec; the classification above is internal
bookkeeping, not the final triage shape.

### 4. Extend `directive-support` with a `--mode=migration` render

Today the tool prints an internal report. Add a second render mode that
emits an AsciiDoc fragment suitable for `include::` from the migration
guide: supported directives and shape-change notes. The "supported schema
shapes" portion comes from `leaf-coverage --mode=migration` (step 2); this
fragment is directive-only, the two combine in the migration guide. The
internal mode (current default) stays unchanged. The roadmap item that owns
the docs build wiring is `R9` (docs site); this item only owns the
rendering.

### 5. Author the migration-guide section

Target: `docs/manual/how-to/migrating-from-legacy.adoc`. The section names
what is supported (driven by both `--mode=migration` fragments: directive
list from `directive-support`, shape list from `leaf-coverage`), what
changed in shape (already drafted; cross-link the existing entries), and
what is removed (`@notGenerated`, `@multitableReference`, anything else
surfaced during classification). Consumer-facing wording; no internal "is
this fixture-covered" detail.

## Done definition

- Classifier-trace emission lands in graphitron, gated on
  `-Dgraphitron.classification.trace=<path>`, with a `-Pleaf-coverage`
  Maven profile that produces the aggregate trace file.
- A JUnit 5 extension auto-registers in the test classpath and tags every
  trace record with the running test class and its tier annotation.
- `roadmap-tool leaf-coverage` reads the trace and renders both an internal
  report at `roadmap/inference-axis-coverage.adoc` and a
  `--mode=migration` AsciiDoc fragment.
- Every sealed leaf of `RootField`, `ChildField`, `InputField`,
  `GraphitronType` has a classification entry in the internal report.
- Every RC-blocker leaf has a corresponding sibling Backlog item.
- `directive-support --mode=migration` renders an AsciiDoc fragment that
  the docs build includes successfully.
- The migration-guide section ships in `migrating-from-legacy.adoc`.
- Verify-mode of `roadmap-tool` fails CI when either the README or the
  internal coverage report drifts from current state.

## Non-goals

- Adding a marker interface to tag surface-level inference leaves. The idea
  is recorded for post-RC; whether to add it is a fresh design decision once
  classification has run and the marker's payoff is concrete.
- Closing every leaf gap. Some are correctly deferred; the audit produces
  the list, the user (with the principles-architect subagent for fork
  judgement) decides per item.
- Validating any axis outside the directive + sealed-leaf surface. Other
  RC-readiness concerns (plugin packaging, `graphitron:dev` polish, error
  message ergonomics) are tracked separately; this item stays scoped to the
  shape-inference and directive-coverage surface.
