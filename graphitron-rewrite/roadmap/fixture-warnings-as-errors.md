---
id: R294
title: Treat generator warnings in test fixtures as errors unless asserted
status: Ready
bucket: testing
priority: 7
depends-on: []
created: 2026-06-10
last-updated: 2026-06-10
---

# Treat generator warnings in test fixtures as errors unless asserted

## Motivation

The sakila-example build emits 13 generator warnings from its fixture schema, repeated once per generator execution (the pom runs three: regular, federated, multischema), so a full build prints the same advisories many times over. The 13: 11 `Type 'X' carries @record(...); the directive is redundant; remove it` warnings, one redundant `@splitQuery` on `FilmsCarrierWithErrorsPayload.films`, and the same-table required-`@nodeId` `@asConnection` warning on `filmsConnectionByRequiredIds`. Nothing distinguishes a deliberate warning fixture from an accidental one, so a fixture edit that starts triggering a new warning passes silently, and the standing expected warnings train everyone to ignore the generator's warning channel.

The policy this item establishes: **fixture builds treat generator warnings as errors, unless the point of the fixture is to assert that the warning is emitted.** The same stance covers discouraged and deprecated functionality generally: it is bad form for fixtures to exercise functionality the generator warns about (or deprecates) unless the fixture's purpose is that warning path, in which case the expectation must be pinned by a live test, not tolerated as log noise.

This item was split out of R293 (build-warning-cleanup), which keeps the non-generator warning categories (javac warnings in generated code, duplicate `junit-platform.properties`, handwritten-source warnings, build infrastructure).

## Design

The gap is narrow, so the mechanism is narrow. Most of the 13 warnings are stale fixtures that should simply go away; exactly one is a deliberate shape the example must keep for execution coverage; and the thing actually missing is a test that fails when a *new* warning appears. The design is cleanup plus one assertion, not a strict-mode engine.

### One warning channel

`BuildWarning(message, location)` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildWarning.java`) is the advisory channel: builders call `ctx.addWarning(...)` (BuildContext.java:250), `GraphitronSchema.warnings()` accumulates, `ValidationReport` pairs warnings with `ValidationError`s, and `GraphQLRewriteGenerator.logWarnings` (GraphQLRewriteGenerator.java:266) prints them. But the same-table `@asConnection` hygiene warning bypasses this channel entirely: FieldBuilder.java:644 logs through the dedicated SLF4J logger `ASCONNECTION_HYGIENE_LOG` (declared at FieldBuilder.java:145) and never calls `ctx.addWarning`, so it is invisible to `schema.warnings()`, to `ValidationReport`, and to any check built over them.

Step one is channel unification: route the asConnection hygiene advisory through `ctx.addWarning(new BuildWarning(...))` like the redundant-`@record` (TypeBuilder.java:367-393) and redundant-`@splitQuery` (FieldBuilder.java:4431) warnings, retire the dedicated logger category, and audit the generator for any other `LOG.warn` calls that are really classifier outputs and should ride the same channel. `AsConnectionSameTableWarnFormatTest` currently asserts the logger output; it migrates to asserting the `BuildWarning` on `schema.warnings()`. Once every advisory rides one channel, `schema.warnings()` is a complete, inspectable record of what a build flagged.

### The gate is a test over `schema.warnings()`, not a Mojo flag

The motivation is "keep our own fixtures honest", and the example build already runs `schema.warnings()` through `buildOutput()`. So the gate is a single sakila-example test that builds the example schema and asserts the warning set is *exactly* the expected multiset, one `(message, coordinate)` entry, in the style of `BuildOutputReportPipelineTest`. A new accidental warning fails the assertion (an unexpected entry); a vanished expected warning fails it too (a missing entry). That is the symmetric drift protection the gate needs, and a failing test in the example module's `mvn install` *is* the fixture build going red on a new warning.

No core reconciliation engine, no new Mojo parameters, no typed warning-kind allowlist, no downgrade-logging path. The test reads the same `warnings()` list the core already exposes; there is no classifier decision going unchecked that a "validator mirrors classifier" mechanism would need to cover. Matching on message substring plus coordinate is the established pattern for these warning tests (`R96RecordBindingPipelineTest` already does it); the one in-repo assertion is normal test maintenance if a message is reworded, not configuration that parses prose.

A consumer-facing `failOnWarning` Mojo feature, with a typed allowlist so end users can opt into strict builds, is real but separable scope this item does not need (see Out of scope).

### Audit of the current 13 warnings

- **11 redundant `@record` directives: stale, remove them.** schema.graphqls lines 367, 383, 395, 430, 458, 466, 480, 627, 642, 656, 680. The "matches" branch (TypeBuilder.java:380-385) confirms the generator derives the same backing class without the directive; fixture behavior is unchanged by removal. The warning path itself stays covered by `R96RecordBindingPipelineTest` on minimal SDL (its `matches`, `disagrees`, and `shadowedByTable` cases pin all three message variants).
- **Redundant `@splitQuery` (schema.graphqls:1526): also stale here, remove it.** The directive is *redundant* on a `@record`-parent field, the split happens regardless, so removal leaves generation behavior unchanged (confirm via the existing execute coverage of `serviceFilmsByIdsWithErrors`). The warning path is already pinned on minimal SDL at pipeline tier by `SingleRecordTableFieldServiceProducerPipelineTest.serviceProducer_splitQueryListCarrier_withErrors_admitsAndWarnsRedundantDirective` and by `GraphitronSchemaBuilderTest`, so the broad example fixture does not need to carry it. (This reverses the earlier "keep and declare" call: a warning whose only role here is to fire has no reason to sit in the example when a minimal-SDL test owns it.)
- **Same-table `@asConnection` (schema.graphqls:170): deliberate, keep.** `filmsConnectionByRequiredIds` has execution-tier coverage (`GraphQLQueryTest.filmsConnectionByRequiredIds_idsSupplied_paginatesBoundedSet` exercises it against PostgreSQL), so the shape must stay in the example, and it warns. This is the single entry the gate test declares as expected; its assertion migrates with `AsConnectionSameTableWarnFormatTest` to the unified channel.

After the two removals the example emits exactly one warning, the asConnection advisory the gate test pins.

### Open check before cleanup

Verify whether the execution coverage of `filmsConnectionByRequiredIds` intrinsically needs the warned-about combination (same-table connection with required `@nodeId`), or whether an equivalent shape exercises the same code path without tripping the hygiene advisory. If the warning can be avoided without losing R113's "mirrors a production shape" intent or the bounded-connection execute coverage, drive the example to **zero** warnings and the gate test asserts an empty `warnings()` set, which is strictly simpler. If it cannot, one expected-warning entry is the floor. Resolve this in phase 1 before writing the gate assertion.

## Tests

- **Pipeline-tier:** migrate `AsConnectionSameTableWarnFormatTest` off logger capture onto `schema.warnings()`. The `@record` and `@splitQuery` warning paths already have pipeline coverage (`R96RecordBindingPipelineTest`, `SingleRecordTableFieldServiceProducerPipelineTest`, `GraphitronSchemaBuilderTest`) and are unchanged.
- **Example gate:** a sakila-example test builds the example schema via `GraphQLRewriteGenerator.buildOutput()` and asserts the exact expected `(message, coordinate)` multiset on `schema.warnings()` (one entry today, or empty if the open check clears the asConnection warning). This is the canonical declaration of which warnings the fixture intentionally carries and the regression gate for new ones.
- **Build proof:** a full `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` emits no unexpected warning-level generator lines from sakila-example; the single expected advisory (if it remains) stays visible on the console rather than being suppressed.

## Phasing

1. **Channel unification + open check.** Route the asConnection advisory through `BuildWarning`, retire `ASCONNECTION_HYGIENE_LOG`, migrate `AsConnectionSameTableWarnFormatTest`, and resolve whether the asConnection execute fixture can avoid the warning.
2. **Cleanup + gate.** Remove the 11 stale `@record` directives and the redundant `@splitQuery` from the example, then add the example gate test asserting the exact remaining warning set.

## Out of scope

- **Consumer-facing strict-mode Mojo feature.** A `failOnWarning` `@Parameter` on `AbstractRewriteMojo` (default off; warnings must never break an end-user build by default) plus a typed allowlist so consumers can opt into strict builds without message-fragment matching. That allowlist is where a typed warning identity (a sealed `WarningKind` or enum, decided against a concrete second consumer rather than speculatively) earns its place. The fixture-honesty goal here does not need it; file it separately if strict builds become a shipped consumer feature.
- **Deprecated-usage warnings.** Making use of `@deprecated`-marked directives or arguments emit a `BuildWarning`, so a strict build automatically keeps fixtures off deprecated functionality. That is a generator behavior change with its own classifier arm and audit; filed separately as R296 (deprecated-usage-warnings), which depends on this item's channel unification (and, for the strict-build half, on the consumer-facing feature above).
- **`@expectWarning` schema directive.** An author-facing schema-level expectation declaration would ship into the consumer directive vocabulary; only worth its own item if expectations should become a schema-author feature.
- **R293's remaining categories.** Generated-code javac warnings, duplicate `junit-platform.properties`, handwritten-source warnings, and build-infrastructure warnings stay in R293.
