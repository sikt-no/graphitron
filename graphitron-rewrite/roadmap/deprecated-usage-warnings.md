---
id: R295
title: "Emit BuildWarnings when a schema uses deprecated directives or arguments"
status: Backlog
bucket: validation
priority: 7
depends-on: [fixture-warnings-as-errors]
created: 2026-06-10
last-updated: 2026-06-10
---

# Emit BuildWarnings when a schema uses deprecated directives or arguments

Deprecations are currently SDL `@deprecated` markers on `graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls` with doc-coverage enforcement (`DeprecationsDocCoverageTest` keeps `docs/manual/reference/deprecations.adoc` in sync), but *using* a deprecated directive or argument in a consumer schema produces no diagnostic at all. It is bad form for tests (and consumers) to rely on deprecated functionality without noticing; fixtures especially should never exercise a deprecated path unless the test's point is that path. Add a classifier arm that detects deprecated-directive/argument usage at the parse boundary and emits a `BuildWarning` with its own `WarningKind` variant through the unified warning channel R294 establishes. With R294's strict mode enabled on fixture builds, deprecated usage then fails the build unless explicitly declared and asserted, and consumers get an advisory warning by default. Scope includes an audit of existing fixture schemas for deprecated usage once the warning fires.
