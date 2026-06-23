---
id: R358
title: "Guard table-name comparisons against case-sensitivity drift"
status: Backlog
bucket: cleanup
priority: 5
theme: structural-refactor
depends-on: []
created: 2026-06-23
last-updated: 2026-06-23
---

# Guard table-name comparisons against case-sensitivity drift

`TableRef.tableName()` is deliberately **not** case-canonical: it is preserved verbatim from the `@table(name:)` directive value so author-facing diagnostics echo what the user wrote (`TableRef.java:14-16`, `JooqCatalog.java:984-987`). But the same `TableRef` can also be built from the jOOQ `Table.getName()` (`ServiceCatalog.resolveTableByRecordClass` → `toTableRef(e.table().getName())`), so two `TableRef`s denoting the same table can carry different casing. Comparison correctness therefore depends on **every** comparison site remembering to use `equalsIgnoreCase` rather than `equals`. The codebase mostly does (eight sites: `TypeBuilder.java:799`, `GraphitronSchemaValidator.java:669`, `NodeIdLeafResolver.java:292/323`, `FieldBuilder.java:5720`, `BuildContext.java:2393`, …), but R357 was a real misclassification caused by the one site (`FieldBuilder.java:5114`) that drifted to case-sensitive `equals`. That drift is invisible until a schema with Oracle-style UPPERCASE `@table(name:)` over a lowercase jOOQ catalog hits the exact construction pair that mixes the two casing sources.

This item makes the idiom enforceable instead of remembered. Two shapes, lead with the first:

- **Guard test (cheap, high-signal).** A unit-tier meta-test over the builder sources that fails on any `\.tableName\(\)\s*\.equals\(` (with an allowlist only if a genuinely case-sensitive table comparison ever exists; today there are none). This pins the idiom mechanically: a violating site fails the build rather than silently misclassifying. The remaining case-sensitive site after R357 lands is `FieldBuilder.java:3105` (not a live bug — both operands flow from the same verbatim `@table` path so they cannot diverge — but it must be converted to `equalsIgnoreCase` to pass the guard).
- **Typed same-table comparison (the real lift).** Give `TableRef` a `boolean denotesSameTableAs(TableRef)` / `sameTable(String)` that owns the canonical comparison, and migrate the comparison sites onto it. This puts the "are these the same table" predicate once on the type instead of re-deriving it at nine call sites (the model-carries-the-predicate shape from `rewrite-design-principles.adoc`). Heavier: touches `TableRef`'s public surface and every consumer, so it earns its own pipeline coverage.

Out of scope: canonicalizing `tableName()` at construction (would change author-facing diagnostic casing — the invariant this trap exists to preserve). Surfaced by R357 (`FieldBuilder.java:5114`); the guard test would have caught that drift at build time.

