---
id: R282
title: "Scope unknownForeignKeyRejection FK candidate hint to the structurally relevant FKs"
status: Backlog
bucket: bug
priority: 5
theme: diagnostics
depends-on: []
created: 2026-06-08
last-updated: 2026-06-08
---

# Scope unknownForeignKeyRejection FK candidate hint to the structurally relevant FKs

Residual follow-up carved out of R259 (`fk-key-hint-scope-and-namespace`, shipped). R259 made the FK-key "did you mean" candidate hint both **scoped** (to the FKs touching the path source table) and **namespace-aware** (rendered in the SQL-constraint or jOOQ Java-constant `TABLE__CONSTRAINT` namespace the author typed) on the primary surface, `BuildContext.parsePathElement` via `fkCandidateNames` (`BuildContext.java:897`). The sibling surface, `BuildContext.unknownForeignKeyRejection` (`BuildContext.java:1009`), reached from the `@reference(key:)` / `@nodeId` synthesis miss path (call sites `:1115`, `:1347`, `:1376`, `:1889`), got the **namespace** half in the R259 close (mirrors `__` in the attempt) but is still **global**: its candidate set is the whole catalog (`allForeignKeySqlNames()` / `allForeignKeyConstantNames()`), not scoped to the structurally relevant FKs.

The asymmetry is why it was split off: `fkCandidateNames` had `currentSourceSqlName` in scope at the call site, whereas `unknownForeignKeyRejection(String fkName)` receives only the FK name. Scoping it means threading a source table (the enclosing `@reference` path-origin or FK-owning table) through those four-plus call sites so the candidate set can be narrowed via `JooqCatalog.foreignKeysTouchingTable(...)` (the helper R259 already added), falling back to the global list when no source table is in scope. Out of scope, as in R259: the LSP completion/hover arms and the FK-resolution logic itself (only the failure message's candidate list is at issue).
