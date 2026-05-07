---
id: R104
title: "RC parity audit: classify GraphitronField/Type leaves and ship coverage gaps"
status: Backlog
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

This item is a tracker. The deliverables are a classification table, one or
more sibling Backlog items per RC-blocking gap, an internal regenerable report,
and a consumer-facing migration-guide section. The "if we don't generate then
validation fails" rule means an `@ExecutionTier` test that includes shape X is
the proof that X is fully supported (parses, classifies, validates, generates,
executes). Pipeline-tier or unit-tier coverage alone does not count.

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

## Phase B scope (this item)

### 1. Re-run the leaf-coverage survey against current trunk

A first-pass Explore-agent survey ran in the originating session and produced
a 52-row table over `RootField`, `ChildField`, `InputField`, `GraphitronType`.
That table is not durable input here; the implementer should re-run a fresh
survey as the first step of In Progress so the data reflects current trunk
(R102 / R103 were added after the first pass; the original survey's
backlog-refs column was unreliable and should be re-derived per leaf).

Hierarchy entry points:

- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronField.java`
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/RootField.java`
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ChildField.java`
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/InputField.java`
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronType.java`

Per leaf, the survey collects: hierarchy, intent (one line from the record's
javadoc), SDL trigger shape, whether sakila has the shape, which
`@ExecutionTier` test exercises it, and any roadmap items whose body
mentions the leaf class name. `UnclassifiedField` / `UnclassifiedType` are
rejection buckets and need no fixture coverage. `VariantCoverageTest`'s
`NO_CASE_REQUIRED` map is a useful cross-reference for known classification
gaps; entries there deserve scrutiny rather than blanket acceptance.

Confirmed clusters from the first-pass survey, to validate against trunk:

- **Composite-key paths** (`CompositeColumnField`, `CompositeColumnReferenceField`,
  and the two `InputField` siblings): no fixture; pipeline-tier only.
  Standard sakila has no composite-PK table. RC-blocking iff any consumer
  schema uses composite primary keys.
- **`ErrorsField`**: pipeline-only; awaits Phase C3 of R12 (error-handling-parity).
- **`TableMethodField`**: shape exists in sakila (`Query.popularFilms`) but no
  explicit `@ExecutionTier` assertion against the method body. Weaker proof.
- **`JooqRecordType` / `JooqRecordInputType`**: plain `jOOQ Record<?>` (non-
  TableRecord) has no fixture class. `VariantCoverageTest.NO_CASE_REQUIRED`
  documents the gap.
- **`ParticipantColumnReferenceField`**: shape in sakila, no explicit
  assertion at execution tier; recently shipped, worth confirming.

### 2. Classify each leaf into one of four tiers

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

### 3. Extend `directive-support` with a `--mode=migration` render

Today the tool prints an internal report. Add a second render mode that emits
an AsciiDoc fragment suitable for `include::` from the migration guide:
supported directives, shape-change notes, and a "supported schema shapes" list
keyed off the leaf classification. The internal mode (current default) stays
unchanged. The roadmap item that owns the LSP / parser / migration guide
infrastructure is `R9` (docs site) for the build wiring; this item only owns
the rendering.

### 4. Author the migration-guide section

Target: `docs/manual/how-to/migrating-from-legacy.adoc`. The section names what
is supported (driven by the `--mode=migration` fragment), what changed in
shape (already drafted; cross-link the existing entries), and what is removed
(`@notGenerated`, `@multitableReference`, eventually anything else surfaced
during classification). Consumer-facing wording; no internal "is this fixture-
covered" detail.

### 5. Author the internal coverage report

Target: `graphitron-rewrite/roadmap/inference-axis-coverage.md`, regenerated
by `roadmap-tool` similar to `README.md`. Carries the full leaf table and
classification, kept in sync with trunk by the same regeneration pass.
Verify-mode of the tool fails CI when the file drifts from current state, same
guard as the roadmap README.

## Done definition

- Every sealed leaf of `RootField`, `ChildField`, `InputField`,
  `GraphitronType` has a classification entry.
- Every RC-blocker leaf has a corresponding sibling Backlog item.
- `--mode=migration` renders an AsciiDoc fragment that the docs build
  includes successfully.
- The migration-guide section ships in `migrating-from-legacy.adoc`.
- The internal coverage report regenerates cleanly and verify-mode passes.

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
