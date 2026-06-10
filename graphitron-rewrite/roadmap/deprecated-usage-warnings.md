---
id: R296
title: "Emit BuildWarnings when a schema uses deprecated directives or arguments"
status: Backlog
bucket: validation
priority: 7
depends-on: []
created: 2026-06-10
last-updated: 2026-06-10
---

# Emit BuildWarnings when a schema uses deprecated directives or arguments

Deprecations are currently SDL `@deprecated` markers on `graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls` with doc-coverage enforcement (`DeprecationsDocCoverageTest` keeps `docs/manual/reference/deprecations.adoc` in sync), but *using* a deprecated directive or argument in a consumer schema produces no diagnostic at all. It is bad form for tests (and consumers) to rely on deprecated functionality without noticing; fixtures especially should never exercise a deprecated path unless the test's point is that path. Add a classifier arm that detects deprecated-directive/argument usage at the parse boundary and emits a `BuildWarning` through the unified warning channel R294 establishes (`ctx.addWarning` → `schema.warnings()`). Once it fires, R294's fixture gate (the sakila-example test asserting the exact `schema.warnings()` multiset) catches any new deprecated usage in the example as an unexpected warning, unless it is deliberately declared and asserted there. A consumer-facing build that fails on warnings, and any typed warning identity an allowlist would need, are the separable strict-mode feature R294 lists as out of scope, not part of this item; with that feature in place, deprecated usage would fail those builds too and consumers would get the advisory by default. Scope includes an audit of existing fixture schemas for deprecated usage once the warning fires.
