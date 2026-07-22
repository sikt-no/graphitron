---
id: R282
title: "Scope and type the FK candidate hint on the record-FK and synthesis-miss surfaces"
status: Spec
bucket: bug
priority: 5
theme: diagnostics
depends-on: []
created: 2026-06-08
last-updated: 2026-07-22
---

# Scope and type the FK candidate hint on the record-FK and synthesis-miss surfaces

Residual follow-up carved out of R259 (`fk-key-hint-scope-and-namespace`, shipped). R259 made the FK-key "did you mean" candidate hint both **scoped** (to the FKs touching the path source table) and **namespace-aware** (rendered in the SQL-constraint or jOOQ Java-constant `TABLE__CONSTRAINT` namespace the author typed) on the primary surface, `BuildContext.parsePathElement` via `fkCandidateNames` (`BuildContext.java:897`). The sibling surface, `BuildContext.unknownForeignKeyRejection` (`BuildContext.java:1009`), reached from the `@reference(key:)` / `@nodeId` synthesis miss path (call sites `:1115`, `:1347`, `:1376`, `:1889`), got the **namespace** half in the R259 close (mirrors `__` in the attempt) but is still **global**: its candidate set is the whole catalog (`allForeignKeySqlNames()` / `allForeignKeyConstantNames()`), not scoped to the structurally relevant FKs.

The asymmetry is why it was split off: `fkCandidateNames` had `currentSourceSqlName` in scope at the call site, whereas `unknownForeignKeyRejection(String fkName)` receives only the FK name. Scoping it means threading a source table (the enclosing `@reference` path-origin or FK-owning table) through those four-plus call sites so the candidate set can be narrowed via `JooqCatalog.foreignKeysTouchingTable(...)` (the helper R259 already added), falling back to the global list when no source table is in scope. Out of scope, as in R259: the LSP completion/hover arms and the FK-resolution logic itself (only the failure message's candidate list is at issue).

## Spec findings: the item's premise is inverted

Two facts surfaced while reading the current code (line anchors are as of this spec; symbols are the stable reference):

1. **The sibling surface is defensive-only, not an author-typo surface.** Every call site of `unknownForeignKeyRejection` (four in `BuildContext`: the no-directive single-FK synthesis arm in `parsePath`, the `{key:}` and `{table:}` arms of `parsePathElement`, the IdReference synthesis shim; plus `NodeIdLeafResolver.resolveFkJoinPath`) switches over `FkJoinResolution.UnknownForeignKey`, whose sole producer is `synthesizeFkJoin`. That variant fires only when `catalog.findForeignKeyRef(f)` misses for an FK object that came *from* the catalog: a catalog-vs-jar mismatch, documented defensive-only on `synthesizeFkJoin`'s javadoc. The `fkName` it carries is `f.getName()`, a real FK's SQL constraint name, never something the author typed. A Levenshtein "did you mean" over catalog FKs is noise on that path whether scoped or not, and the `__`-namespace detection R259 added there is a no-op by construction (a real FK object's `getName()` is always the SQL form).

2. **The genuine residual author-typo surface is `resolveRecordFkTargetColumns`.** Its explicit `@reference(key:)` NotInCatalog arm builds a hint from `catalog.allForeignKeySqlNames()` inline: still **global** and, unlike the sibling, also still **namespace-blind**, even though `recordTable.tableName()` is in scope right there. Worse, the result flattens to prose: `RecordFkTargets.Rejected` carries a bare `String`, which `InputBeanResolver` wraps into `Rejection.structural(...)`, so the typo never reaches the typed `Rejection.AuthorError.UnknownName` payload (attempt + candidates + `AttemptKind.FOREIGN_KEY`) that the typed-rejection contract (`docs/architecture/explanation/typed-rejection.adoc`) promises for name-against-closed-set failures.

So the value of this item lives at the record-FK surface, and the spec below makes that the spine. The defensive sibling gets scoped too, but as cheap symmetry, not as the point.

## Design

**Slice 1 (the spine): lift the record-FK `@reference(key:)` miss onto the typed, scoped, namespaced path.**

- In `resolveRecordFkTargetColumns`, replace the inline `candidateHint(explicitFkKey.get(), catalog.allForeignKeySqlNames())` construction with the existing `fkCandidateNames(recordTable.tableName(), explicitFkKey.get())` helper. That buys scope (via `JooqCatalog.foreignKeysTouchingTable`, global fallback when nothing touches) and namespace mirroring (`__` detection on the author's attempt) in one move.
- Route the result through `Rejection.unknownForeignKey(summary, attempt, candidates)` instead of a hand-built string: change `RecordFkTargets.Rejected` to carry a `Rejection` rather than a `String` (its other producers, `fkCountMessage` and the ambiguity arm, already have typed or message-shaped rejections to wrap), and have `InputBeanResolver` pass the typed rejection through instead of re-wrapping prose in `Rejection.structural`. This makes the candidates ride as `UnknownName` structured data, the shape an LSP fix-it can consume without parsing prose, and makes the new tests structural instead of substring-matching.
- `fkCandidateNames` is private to `BuildContext`; `resolveRecordFkTargetColumns` is in the same class, so no visibility change.

**Slice 2 (symmetry): single-source the sibling's source table on the variant.**

- Add the source table to the variant: `record UnknownForeignKey(String fkName, String sourceSqlName)`. `synthesizeFkJoin` already receives `sourceSqlName` (non-null by its documented precondition) and is the sole producer, so the pair (FK name, the table it was oriented against) is bound once at the point that knows both, instead of five call sites each re-picking "the source" from local scope. This mirrors the sibling `UnknownTable(requestedName, failure)`, which already carries its diagnostic data on the variant.
- Change `unknownForeignKeyRejection(String fkName)` to `unknownForeignKeyRejection(String fkName, String sourceSqlTable)` and delegate candidate construction to `fkCandidateNames(sourceSqlTable, fkName)`, deleting the duplicated inline namespace detection. Call sites mechanically pass `uf.sourceSqlName()`.
- Update the builder's javadoc: drop the "still the whole catalog / tracked as a follow-up" paragraph, and state plainly that this rejection is reached only from the defensive catalog-vs-jar mismatch, so the hint is best-effort. (A mismatch-specific message that says "rebuild the catalog jar" instead of offering candidates was considered and is out of scope; if that path ever fires in practice, file a separate item.)

## Tests

- **Pipeline tier (slice 1, the wiring):** a fixture with a misspelled record-FK `@reference(key:)` through `InputBeanResolver` asserts the typed `Rejection.AuthorError.UnknownName` variant with `AttemptKind.FOREIGN_KEY`, the attempt string, and a candidate list that is a subset of the FKs touching the record table (and, with a `__`-form attempt, constant-namespace candidates). Asserting the typed variant's fields, not a prose substring, is the reason slice 1 routes onto `UnknownName` at all.
- **Unit tier (slice 2, the derivation):** extend the existing `unknownForeignKeyRejection` section of `JooqCatalogMultiSchemaTest`: with a source table supplied, candidates are limited to FKs touching that table; with a source table that has no touching FKs, the global namespace-matched fallback applies (this is the fallback state actually reachable from the producer; `sourceSqlName` is never null on that path). The two existing namespace tests update for the new parameter.

## Out of scope

Unchanged from the original carve-out: the LSP completion/hover arms and the FK-resolution logic itself. Additionally out of scope: rewriting the defensive path's message semantics (see slice 2), and any change to the `Rejection.unknownForeignKey` factory shape (both slices only change who calls it and with what candidate list).
