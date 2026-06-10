---
id: R294
title: Treat generator warnings in test fixtures as errors unless asserted
status: Spec
bucket: testing
priority: 7
depends-on: []
created: 2026-06-10
last-updated: 2026-06-10
---

# Treat generator warnings in test fixtures as errors unless asserted

## Motivation

The sakila-example build emits 13 generator warnings from its fixture schema, each repeated per generator execution (regular + federated), roughly 26 log lines per full build: 11 `Type 'X' carries @record(...); the directive is redundant; remove it` warnings, one redundant `@splitQuery` on `FilmsCarrierWithErrorsPayload.films`, and the same-table required-`@nodeId` `@asConnection` warning on `filmsConnectionByRequiredIds`. Nothing distinguishes a deliberate warning fixture from an accidental one, so a fixture edit that starts triggering a new warning passes silently, and the expected warnings train everyone to ignore the generator's warning channel.

The policy this item establishes: **fixture builds treat generator warnings as errors, unless the point of the fixture is to assert that the warning is emitted.** The same stance covers discouraged and deprecated functionality generally: it is bad form for tests to exercise functionality the generator warns about (or deprecates) unless the test's purpose is that warning path, in which case the expectation must be pinned by a live test, not tolerated as log noise.

This item was split out of R293 (build-warning-cleanup), which keeps the non-generator warning categories (javac warnings in generated code, duplicate `junit-platform.properties`, handwritten-source warnings, build infrastructure).

## Design

### One warning channel

`BuildWarning(message, location)` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildWarning.java`) is the advisory channel: builders call `ctx.addWarning(...)`, `GraphitronSchema.warnings()` accumulates, `ValidationReport` pairs warnings with `ValidationError`s, and `GraphQLRewriteGenerator.logWarnings` (GraphQLRewriteGenerator.java:266) prints them. But the same-table `@asConnection` hygiene warning bypasses this channel entirely: FieldBuilder.java:644 logs through the dedicated SLF4J logger `ASCONNECTION_HYGIENE_LOG` (declared at FieldBuilder.java:145) and never calls `ctx.addWarning`, so it is invisible to `schema.warnings()`, to `ValidationReport`, and to any strict mode built over them.

Step one is channel unification: route the asConnection hygiene advisory through `ctx.addWarning(new BuildWarning(...))` like the redundant-`@record` (TypeBuilder.java:367-392) and redundant-`@splitQuery` (FieldBuilder.java:4431) warnings, retire the dedicated logger category, and audit the generator for any other `LOG.warn` calls that are really classifier outputs. `AsConnectionSameTableWarnFormatTest` currently asserts the logger output; it migrates to asserting the `BuildWarning` on `schema.warnings()`.

### Typed warning identity: `WarningKind`

An allowlist matched by message fragment would make configuration parse prose: the moment someone rewords a warning, every fragment silently re-matches or stops matching, and the message text becomes load-bearing in multiple consumers (the LSP already consumes `report.warnings()`). Instead, `BuildWarning` gains a `kind` component: a sealed `WarningKind` hierarchy with one variant per warning family (redundant `@record`, redundant `@splitQuery`, asConnection same-table `@nodeId`, and the shadowed `@record`-with-`@table` variant that R96RecordBindingPipelineTest already covers), populated by the classifier at each `addWarning` site. Expectations match on kind plus coordinate; the prose stays free to change.

### Reconciliation lives in the core, surfaced by both generate and validate

The strict check is not a post-generation pass in the Mojo. The established pattern ("validator mirrors classifier invariants") is that what fails the build is decided where `ValidationReport` already pairs errors and warnings, and surfaces identically through the `generate()` and `validate()` paths in `GraphQLRewriteGenerator` (the two `logWarnings` / `ValidationFailedException` sites at lines 131-141 and 173-177). The Mojo only carries configuration into the context.

Semantics with strict mode on: every `BuildWarning` must match an allowed `WarningKind`; any unmatched warning fails the build listing kind, coordinate, and message. The allowlist is deliberately coarse (kinds allowed to appear for this schema, shared across executions), because the three sakila executions classify different subsets and a per-occurrence pom ledger would force hand-maintained near-duplicates that drift. Exactness lives in a test instead (see Tests): a sakila-example test asserts the full expected multiset of (kind, coordinate) per schema, giving symmetric drift protection (a stale expectation fails the test, an unexpected warning fails the build) without a pom cross-product.

### Mojo surface

Two new `@Parameter`s on `AbstractRewriteMojo`: `failOnWarning` (boolean, default `false`) and `expectedWarningKinds` (list, default empty). Consumer-facing default stays advisory; warnings must never break an end-user build by default. All three sakila-example executions (pom.xml lines 269-336) enable strict mode through shared plugin-level configuration. `MojoDocCoverageTest` requires a doc row for each new parameter.

### Logging policy: downgrade, never suppress

Default mode logging is unchanged. In strict mode, a warning that matched an expectation is downgraded to info (`expected: <kind> at <coordinate>`); unmatched warnings fail the build. A green fixture build therefore has an empty warning channel, but the fact that a fixture deliberately sits on a discouraged shape remains visible on the console. Full suppression would hide the very signal this item makes trustworthy.

### Audit of the current 13 warnings

- **11 redundant `@record` directives: stale, remove them.** schema.graphqls lines 367, 383, 395, 430, 458, 466, 480, 627, 642, 656, 680. The "matches" branch (TypeBuilder.java:380-393) confirms the generator derives the same backing class without the directive; fixture behavior is unchanged by removal. The warning path itself stays covered by R96RecordBindingPipelineTest on minimal SDL.
- **Redundant `@splitQuery` (schema.graphqls:1526): deliberate, keep and declare.** R275 fixture for the tolerated-redundant carrier shape; already asserted in `GraphitronSchemaBuilderTest` and `SingleRecordTableFieldServiceProducerPipelineTest`.
- **Same-table `@asConnection` (schema.graphqls:170): deliberate, keep and declare.** R113 fixture mirroring a production shape; assertion migrates with `AsConnectionSameTableWarnFormatTest` to the unified channel.

## Tests

- **Pipeline-tier:** each `WarningKind` variant has a test asserting the warning fires on minimal SDL with the right kind (extend R96RecordBindingPipelineTest-style assertions from message matching to kind matching; migrate `AsConnectionSameTableWarnFormatTest` off logger capture). Reconciliation logic gets direct coverage: unexpected kind fails, expected kind passes and downgrades.
- **Fixture exactness:** a sakila-example test builds the main (and federated, if it differs) schema via `GraphQLRewriteGenerator.buildOutput()` and asserts the exact expected multiset of (kind, coordinate), in the style of `BuildOutputReportPipelineTest`. This is the canonical declaration of which warnings the fixture intentionally carries.
- **Mojo:** `MojoDocCoverageTest` rows for `failOnWarning` and `expectedWarningKinds`.
- **Build proof:** a full `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` emits zero warning-level generator lines from sakila-example.

## Phasing

1. **Channel unification + `WarningKind`.** Route the asConnection advisory through `BuildWarning`, add the kind component, populate at every `addWarning` site, migrate existing warning assertions.
2. **Strict mode.** Reconciliation in the core surfaced through generate and validate, the two Mojo parameters with doc rows, downgrade logging.
3. **Sakila audit.** Remove the 11 stale `@record` directives, declare the two deliberate kinds, add the exactness test, enable `failOnWarning` on all three executions.

## Out of scope

- **Deprecated-usage warnings.** Making use of `@deprecated`-marked directives or arguments emit a `BuildWarning` (a new `WarningKind` variant), so strict mode automatically keeps fixtures off deprecated functionality. That is a generator behavior change with its own classifier arm and audit; filed separately as R295 (deprecated-usage-warnings), which depends on this item's channel and kind work.
- **`@expectWarning` schema directive.** An author-facing schema-level expectation declaration would ship into the consumer directive vocabulary; only worth its own item if expectations should become a schema-author feature.
- **R293's remaining categories.** Generated-code javac warnings, duplicate `junit-platform.properties`, handwritten-source warnings, and build-infrastructure warnings stay in R293.
