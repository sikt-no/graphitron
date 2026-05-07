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

## Phase B scope (this item)

### 1. Add classifier trace emission to the generator

At the point where each `GraphitronField` / `GraphitronType` is built, emit a
JSONL record to the path set by `-Dgraphitron.classification.trace=<path>`,
gated so the property defaults off and production runs incur no cost. One
record per classified field/type, fields:

- `parent`: parent type name (empty for top-level types)
- `name`: field/type name
- `leaf`: fully-qualified record class, e.g. `ChildField.TableMethodField`
- `source`: best-effort hint at which fixture/SDL drove the classification
  (file path or logical fixture name from the schema-loader context)
- `rejection`: present only on `UnclassifiedField` / `UnclassifiedType`;
  carries the `RejectionKind` plus message so the post-processor can
  separate `[deferred]` (legitimately stub-tagged) from `[author-error]` /
  `[invalid-schema]` (would be a real classification regression if seen on
  a fixture we expected to pass)

Emission point: tail of `FieldBuilder` and the type-builder counterpart,
after the sealed-variant decision is made. Keep it one short helper invoked
from each construction path; do not scatter `if (traceEnabled)` checks.

A Maven profile `-Pleaf-coverage` sets the property to
`${session.executionRootDirectory}/target/leaf-coverage.jsonl`, truncates
the file at the start of the test run, and runs `mvn verify` so the trace
accumulates across all tiers in one file. The aggregate file is the input
to step 2.

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
flags any leaf with zero trace records as a coverage hole. Distinguishing
exec-tier vs pipeline-tier coverage uses the cheap convention: the `source`
field in the trace, joined to a small fixture-to-tier registry maintained
in roadmap-tool. If the registry misclassifies, the fix is one entry per
fixture, not a JUnit extension. A JUnit extension that puts the test class's
tier annotation into the trace via MDC is recorded as a follow-up only if
the registry approach proves insufficient.

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
