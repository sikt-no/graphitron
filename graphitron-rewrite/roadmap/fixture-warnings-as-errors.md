---
id: R294
title: "Treat generator warnings in test fixtures as errors unless asserted"
status: Backlog
bucket: testing
priority: 7
depends-on: []
created: 2026-06-10
last-updated: 2026-06-10
---

# Treat generator warnings in test fixtures as errors unless asserted

The sakila-example build currently emits 13 generator warnings from its fixture schema, each twice because the generator runs for both the regular and the federated schema (~26 log lines per build): 11 `Type 'X' carries @record(...); the directive is redundant; remove it` warnings, one redundant `@splitQuery` on `FilmsCarrierWithErrorsPayload.films`, and the `asConnectionSameTableHygiene` warning on `filmsConnectionByRequiredIds`. Nothing distinguishes a deliberate warning fixture from an accidental one: the same-table `@nodeId` case sits next to R88 fixture comments and is plausibly intentional, while several redundant `@record` directives look simply stale. Because the build tolerates all of them, a fixture edit that starts triggering a new warning passes silently, and the expected warnings train everyone to ignore the generator's warning channel.

The policy this item establishes: **fixture builds treat generator warnings as errors, unless the point of the fixture is to assert that the warning is emitted.** Concretely:

- Add a strict mode to the generator invocation used by fixture builds (sakila-example and the pipeline-tier fixtures) that fails on any unexpected generator warning.
- For each warning a fixture intentionally exercises, declare the expectation explicitly (an allowlist next to the fixture, or a test that asserts the warning text and location) so it is both documented and excluded from the failure check. The hygiene warnings, like `asConnectionSameTableHygiene`, should each have a test asserting they fire.
- Audit the current 13 warnings against that split: keep and assert the deliberate ones, fix the schema for the stale ones (likely most of the redundant `@record` directives).
- Expected warnings should not appear in build output at all once asserted; the warning channel in a green build should be empty.

This is the "intentional fixture warnings" group split out of R293 (build-warning-cleanup), which keeps the remaining categories (generated-code javac warnings, duplicate `junit-platform.properties`, handwritten-source warnings, build infrastructure).
