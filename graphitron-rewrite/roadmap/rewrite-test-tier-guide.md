---
id: R29
title: Consolidated test-tier guide
status: Spec
bucket: cleanup
priority: 7
theme: testing
depends-on: [docs-site-asciidoc]
---

# Consolidated test-tier guide

The rewrite has four test tiers (unit, pipeline, compilation against real
jOOQ, execution against real PostgreSQL). The conventions for each tier
are known and respected on trunk, but the documentation is scattered and
partially stale, so a new contributor adding a test has to triangulate to
figure out which tier their test goes in, where the file lives, what
shape it takes, and what they can or cannot assert.

The three sources today, with what each one carries and where it is
silent:

- [`rewrite-design-principles.adoc`](../docs/rewrite-design-principles.adoc)
  states the policy ("Pipeline tests are the primary behavioural tier",
  "Compilation against real jOOQ is a test tier") and bans body-string
  assertions on generated method bodies. It does not say where each
  tier's files live, what an assertion looks like, or how to choose
  between tiers when both could apply.
- [`.claude/web-environment.md`](../../.claude/web-environment.md)
  documents build commands and the local-DB setup. It is a runbook, not
  a contributor guide; the only tier it names is the implicit
  compile + execute pair.
- Per-test conventions live in javadoc on individual test classes
  (`GraphitronSchemaBuilderTest`, `TypeFetcherGeneratorTest`,
  `GraphQLQueryTest`, `GeneratedSourcesSmokeTest`,
  `GeneratorDeterminismTest`, `FieldValidationTestHelper`,
  `IdempotentWriterTest`). They are accurate per-class, but they use
  three incompatible naming schemes side-by-side: a "Level 1 / Level 2 /
  Level 5" numbering with gaps (`FieldValidationTestHelper`,
  `GraphitronSchemaBuilderTest`, `GeneratedSourcesSmokeTest`); a
  "pipeline-tier" / "execution-tier" naming
  (`GeneratorDeterminismTest`, `IdempotentWriterTest`); and prose like
  "unit tests for X" (`TypeFetcherGeneratorTest`). A reader walking the
  test tree cannot tell whether "Level 2" and "pipeline-tier" are the
  same thing.

Same shape applies to inbound references from roadmap and design docs —
they freely mix "unit", "pipeline-tier", "behavioural tier",
"classification tier", and "Level N", with no canonical mapping.

The guide closes both gaps: one canonical naming, one place that says
where each tier lives and what it asserts, with the existing javadocs
updated to match.

## Scope

Add `graphitron-rewrite/docs/testing.adoc` (AsciiDoc, to fit the docs
site landing under R9; the file renders into `graphitron.sikt.no` along
with the other rewrite-internal pages). Cross-link from
`docs/README.adoc` ("Detailed reference" list) and from
`rewrite-design-principles.adoc` (the two existing tier sections become
one-liner pointers).

### Canonical names

Adopt **unit / pipeline / compilation / execution** as the four tier
names; drop "Level N" numbering. Names beat numbers because the contract
each tier carries is in the name (compilation-tier = the compile is the
assertion), and the pre-existing numbering already has gaps and
disagreements with the prose tier names. `unit` and `pipeline` are
already in use in javadoc and on trunk; `compilation` and `execution`
match the names already used in `rewrite-design-principles.adoc`. No
runtime artefact carries a Level number, so the rename is doc-only.

### File contents (one section per tier + a dispatcher)

The file's job is dispatch, not duplication of policy. The sections
are: a decision rubric, one section per tier (unit, pipeline,
compilation, execution), a "Cross-cutting tests in `graphitron-test`"
note that classifies the tests living there for module-dependency
reasons, and a build-commands recap.

**Choosing a tier (decision rubric).** A short flowchart for "I have a
new behaviour I want covered, which tier owns it?". The rubric reads
top-down and stops at the first match:

- *The behaviour is "this generated source must compile against the
  real jOOQ catalog" → Compilation.* No test class to write; the
  fixture-driven `mvn compile -pl :graphitron-test -Plocal-db` is the
  assertion. Add a fixture instead of an assertion.
- *The behaviour is "this generated request must round-trip against
  PostgreSQL and return the right rows / fire the right number of
  queries / honour DataLoader batching" → Execution.* New `@Test` in
  `GraphQLQueryTest` (or one of the federation-/scatter-named
  companions, where the test surface is partitioned).
- *The behaviour is "this SDL pattern classifies into this variant" or
  "this SDL pattern produces a TypeSpec with this method shape" →
  Pipeline.* New case in `GraphitronSchemaBuilderTest` (classification
  truth table) or new `*PipelineTest` file (deeper SDL → TypeSpec).
- *The behaviour is "this builder helper / classifier method / writer
  primitive / validator rule does X on input Y" → Unit.* New case in a
  `*Test` next to the production class, or a new case in a
  `*ValidationTest` for validator rules.

When two tiers could apply, prefer the one that captures the behaviour
most directly. Pipeline beats unit (per-variant structural tests are
bookkeeping; the primary signal that a feature works is that a
realistic SDL produces a realistic `TypeSpec` end-to-end through the
classifier — this is the principles doc's standing rule). Pipeline
also beats compilation and execution where the behaviour can be
asserted on the classified model or `TypeSpec` shape, since pipeline
runs without jOOQ codegen or Postgres. Execution beats compilation
only when SQL behaviour or row content is the contract; pure
type-correctness goes to compilation, which is cheaper.

*Tier is determined by what's asserted, not by what module the file
lives in.* `graphitron-test` hosts tests at every tier — its module
dependency on the post-generator artifacts (compiled output,
generated source files, the fixture jOOQ catalog) is the reason
those tests live there, not a tier signal. A test that imports a
generated class and asserts on it in-memory is unit-tier even if it
lives in `graphitron-test` (`ScatterSingleByIdxTest`); a test that
asserts schema-construction shape is pipeline-tier even if it uses a
fixture-derived generated facade (`FederationBuildSmokeTest`,
`NoFederationRegressionTest`).

**Unit.** Structural invariants on individual classifiers, builders,
emitters, and runtime helpers. Where: `graphitron/src/test/java/...`
next to the production class. Three sub-families share this tier:

- *Generator unit tests* (`TypeFetcherGeneratorTest`,
  `TypeClassGeneratorTest`, `TypeConditionsGeneratorTest`,
  `GeneratorCoverageTest`). Take pre-built model fixtures via
  `TestFixtures`; assert `TypeSpec` shape (method names, return types,
  parameter signatures). Banned: code-string body matching on the
  generated `MethodSpec` body — that is what compilation and execution
  cover.
- *Validator unit tests* (`*ValidationTest`, today described in
  `FieldValidationTestHelper` as "Level 1"). Build a `GraphitronSchema`
  with one parent type and one field at a known coordinate; assert
  `validate()` outcomes by `RejectionKind` and message substring.
- *Builder / catalog / writer unit tests* (`JooqCatalog*Test`,
  `IdempotentWriterTest`, `ArgBindingMapTest`, etc.). Targeted
  constructor or single-method assertions; no full-pipeline plumbing.

**Pipeline.** SDL → classified model → generated `TypeSpec`. Where:
`graphitron/src/test/java/no/sikt/graphitron/rewrite/`. Two shapes share
this tier:

- *Classification truth tables* — `GraphitronSchemaBuilderTest` (today
  "Level 2"). Each variant family is a `// ===== VariantName =====`
  section with an enum where each constant is one
  `(description, SDL, assertion)` triple; one parameterised test
  iterates the table.
- *Deeper SDL → TypeSpec / variant-shape tests* — `*PipelineTest` files
  (`NodeIdPipelineTest`, `SplitTableFieldPipelineTest`,
  `TableFieldPipelineTest`, `LookupTableFieldPipelineTest`, etc.). Build
  a schema with `TestSchemaHelper.buildSchema(sdl)`, assert structural
  shape on the resulting variant or generated `TypeSpec`. Banned:
  code-string body matching.

**Compilation against real jOOQ.** Generated source must compile
against the test catalog. Where: `graphitron-test`, run with
`mvn compile -f graphitron-rewrite/pom.xml -pl :graphitron-test
-Plocal-db`. The compiler is the assertion; no hand-written assertions
are needed for type correctness. Two test classes layer structural
checks on top of the same compile output:

- `GeneratedSourcesSmokeTest` — every expected class is present in the
  emitter's output package (catches a generator that silently drops a
  class; an empty output still compiles).
- `GeneratedSourcesLintTest` — generator-hygiene rules over emitted
  source text (e.g. no `var` in emitted code, so the source stays
  searchable by type name).

**Execution against PostgreSQL.** Full request → SQL → row round-trip.
Where: `graphitron-test`, run with `mvn test -f
graphitron-rewrite/pom.xml -pl :graphitron-test -Plocal-db`. Canonical
classes: `GraphQLQueryTest` (and its split companions on the shared
fixture); `FederationEntitiesDispatchTest` on the federated fixture.
Patterns:

- JDBC round-trip count via the `QUERY_COUNT` listener
  (`AtomicInteger` reset per test to assert DataLoader batching or
  lazy-on-selection).
- Returned-row-id sets and field-value assertions against the Sakila
  fixture catalog.
- Structural SQL-shape assertions via the same `ExecuteListener`-based
  `SQL_LOG` (e.g. that no `select count` ran when `totalCount` was not
  selected).

**Cross-cutting tests in `graphitron-test`.** Several tests live in
`graphitron-test` for module-dependency reasons but classify by
assertion, not by module:

- `GeneratedSourcesSmokeTest`, `GeneratedSourcesLintTest` — compilation
  tier (consume the compile output; see the compilation section above).
- `FederationBuildSmokeTest`, `NoFederationRegressionTest` —
  pipeline-tier shape (schema-construction assertions on the
  fixture-derived generated facade; no SQL). They live in
  `graphitron-test` because they import the generated `Graphitron`
  facade.
- `ScatterSingleByIdxTest` — unit-tier (its own javadoc: "Direct unit
  coverage ... fully in-memory"; lives in `graphitron-test` because it
  reflects against a generated `*Fetchers` class).
- `GeneratorDeterminismTest` — system-level ratchet for the
  three-clause writer contract (determinism + minimal-change writes +
  clean orphan removal). Runs the generator against the fixture schema
  and asserts on the written file tree. It is the full-system
  counterpart to the unit-level `IdempotentWriterTest`; it does not
  fit "pipeline" (no classifier-to-TypeSpec assertion), "compilation"
  (no compile happens), or "execution" (no SQL). Today's javadoc
  labels it "Pipeline-tier ratchet"; replace with "Generator
  end-to-end ratchet (companion to `IdempotentWriterTest`)" — that
  description is accurate and avoids forcing it into the four tier
  names.

**Build commands.** A four-line summary lifted from
`.claude/web-environment.md` for tier-by-tier convenience, each
preserving the `-Plocal-db` flag so the fixtures-jar clobber footgun
does not strike. The detailed runbook stays in `web-environment.md`;
the testing guide points there for the database-setup prerequisites.

### Cross-link adjustments

To make the new file the dispatcher, the existing three sources lose
their tier-naming responsibility:

- `rewrite-design-principles.adoc` — the "Pipeline tests are the
  primary behavioural tier" and "Compilation against real jOOQ is a
  test tier" sections shrink to one-liner pointers at the new file
  (the policies they encode — "pipeline is the primary behavioural
  tier", "code-string body matching is banned" — stay there because
  they are design principles; the *tier dispatch* moves to the new
  file).
- `web-environment.md` — keeps the database setup, build commands, and
  the fixtures-jar clobber recovery as is. Adds a one-line "for what
  each tier asserts and where its files live, see
  `docs/testing.adoc`".
- Per-test javadocs — seven javadocs to update, in two groups:
  - *Three with explicit "Level N" numbering to drop:*
    `FieldValidationTestHelper` ("Level 1"),
    `GraphitronSchemaBuilderTest` ("Level 2"),
    `GeneratedSourcesSmokeTest` ("Level 5").
  - *Four with mismatched tier prose to align:* `IdempotentWriterTest`
    ("pipeline-tier" cross-reference), `TypeFetcherGeneratorTest`
    ("Unit tests for X"; also "execution-tier" cross-reference, fine
    as is), `TestExternalFieldStub` ("classifier-tier"),
    `GeneratorDeterminismTest` ("Pipeline-tier ratchet" → "Generator
    end-to-end ratchet"; see Cross-cutting tests above for rationale).

  The replacement reads "Pipeline-tier" / "Unit-tier" /
  "Compilation-tier" / "Execution-tier" (or the one cross-cutting
  label for `GeneratorDeterminismTest`) with no Level number, and
  points at `docs/testing.adoc` once at the file head rather than
  re-explaining the tier in each class.

### Implementation

One commit lands the new file plus the cross-link adjustments. Order
inside the commit:

1. Add `graphitron-rewrite/docs/testing.adoc` with the five sections
   above. Each tier section names canonical example classes (link, not
   inline body) so a contributor can read one example to learn the
   shape.
2. Trim the two tier-naming sections in
   `rewrite-design-principles.adoc` to one-liner pointers; keep the
   policy claims in place (pipeline is primary, code-string body
   matching is banned).
3. Add a one-line pointer in `.claude/web-environment.md`.
4. Sweep the seven affected javadocs (three with Level N, four with
   mismatched tier prose; specific list under "Cross-link
   adjustments" above). Reword `GeneratorDeterminismTest` from
   "Pipeline-tier ratchet" to "Generator end-to-end ratchet"; this
   test is cross-cutting and does not classify into the four tier
   names.
5. Add the new file to `docs/README.adoc`'s "Detailed reference" list
   (between `rewrite-design-principles.adoc` and `argument-resolution.adoc`,
   since it is principles-adjacent).
6. Regenerate the roadmap README to clear this item via the standard
   `mvn -f graphitron-rewrite/pom.xml -pl roadmap-tool exec:java -q`
   when status flips to In Review.

### Out of scope

- *Auto-generating the build commands from the poms.* The four-command
  summary is stable enough that a hand-written copy is cheaper than a
  template.
- *Renaming any test classes or packages.* Names are doc-only changes
  in the javadoc preambles; class and method names stay.
- *New test infrastructure or `TestSchemaHelper` changes.* The plan
  documents the existing infrastructure; if the rubric exposes a real
  gap (e.g. no shared helper for `QUERY_COUNT` reset patterns at the
  execution tier), file a follow-up rather than expanding this plan.
- *Test-investment policy.* That is `R25 (rebalance-test-pyramid)`'s
  scope. The test-tier guide is *what is*; R25 is *what should grow*.

## Coordinates with

- [`R25 rebalance-test-pyramid`](rebalance-test-pyramid.md) (Backlog)
  depends on this item — its policy ("shift new investment toward
  pipeline tests keyed off `graphitron-fixtures`") needs the canonical
  tier names this guide establishes. Land R29 first.
- [`R8 docs-as-index-into-tests`](docs-as-index-into-tests.md) (Ready,
  deferred) is about `code-generation-triggers.adoc` pointing at
  specific test cases. Different file, complementary axis.
- [`R9 docs-site-asciidoc`](docs-site-asciidoc.md) (In Progress) — this
  plan adds an AsciiDoc file under `graphitron-rewrite/docs/`, so it
  picks up the docs-site rendering for free. No coordination needed
  beyond authoring the file as `.adoc` from the start.
- [`R28 rewrite-docs-entrypoint`](rewrite-docs-entrypoint.md) (Spec) —
  the new file gets added to the "Detailed reference" list R28
  established in `docs/README.adoc`. No conflict; the lists merge
  cleanly.
