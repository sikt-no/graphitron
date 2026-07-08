---
id: R445
title: "Participant cross-table @reference column read must resolve the FK terminal by class identity, not bare SQL name"
status: Backlog
bucket: bug
priority: 3
theme: interface-union
depends-on: [terminal-reference-column-read-identity]
created: 2026-07-08
last-updated: 2026-07-08
---

# Participant cross-table @reference column read must resolve the FK terminal by class identity, not bare SQL name

Seventh site of the schema-qualified `@table` bug class (siblings R396, R440, R441, R442, R422 Done; R444 in flight), found by R444's spec-time audit. The participant cross-table `@reference` resolution in `TypeBuilder` (`TypeBuilder.java:845`, `:861`) holds the FK-pinned `fk.targetTable()` `TableRef` (identity-carrying since R441) and collapses it to the bare `tableName()` string for both the detail-only candidate hint and the actual column resolve via `JooqCatalog.findColumn(String, ...)`. When the FK terminal's bare name exists in two generated schemas, `findTable` is `Ambiguous`, the column resolve comes back empty, and the field is silently skipped (`:862`) instead of classifying as a `ParticipantColumnReferenceField`. This path never routes through `ServiceCatalog.resolveColumnForReference`, so R444's overload retirement cannot catch it; like R444 it has no author-side workaround, because the FK terminal is not author-named. Fix shape: consume the terminal `TableRef` directly, resolving the column via the `TableRef.column(String)` matcher R444 introduces and enumerating hint candidates from `allColumns()`; the R388 defect-2 guard's base-table lookups (`:843`, `:844`) use the interface's verbatim `@table` echo and are lower severity (qualifiable per R396), but can ride along for uniformity.
