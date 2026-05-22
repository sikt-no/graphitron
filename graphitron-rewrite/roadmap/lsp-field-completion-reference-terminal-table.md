---
id: R233
title: "Scope @field(name:) completion to @reference path terminal table"
status: Backlog
bucket: bug
theme: lsp
depends-on: []
created: 2026-05-22
last-updated: 2026-05-22
---

# Scope @field(name:) completion to @reference path terminal table

R224 fixed `Diagnostics.validateFieldMember` so `@field(name:)` validation on a `@reference(path:)` field resolves the column against the path's terminal table rather than the enclosing type's `@table`. The sibling LSP arm at the same coordinate, `FieldCompletions.completionsFor`, still reads `built.typesByName().get(typeName)` only, so when the cursor sits inside `@field(name: "")` on a `@reference` path field the completion list offers the **enclosing** type's table columns instead of the path's terminal-table columns. Concretely, on `input FilmInput @table(name: "film") { languageName: String @field(name: "<here>") @reference(path: [{table: "language"}]) }`, the dropdown shows `film` columns, none of which (`FILM_ID`, `TITLE`, ...) are valid in this position; the user has to type `NAME` blind, then R224's diagnostic confirms it. The fix mirrors R224: dispatch on `built.fieldClassification(typeName, fieldName)` first and route `Column` / `ColumnReference` / `CompositeColumn` / `CompositeColumnReference` to `tableColumnItems(data, c.tableName(), context)`; `InputUnbound` / `Unclassified` return empty (no candidates to offer when the classifier could not pin a table); other arms fall through to today's backing-driven dispatch. Belongs in `graphitron-rewrite/graphitron-lsp/.../completions/FieldCompletions.java`. The new emitter site should carry the same `@DependsOnClassifierCheck(key = "field-classification-payload-faithful")` annotation R224 attached to `Diagnostics.validateFieldMember`, since both arms now rely on the same `CatalogBuilder.terminalTableName` projection. Tests: extend `FieldCompletionsTest` with input + output regressions parallel to `DiagnosticsTest.{input,output}TableWithReferencePathValidatesAgainstTerminalTable` (synthetic `LspSchemaSnapshot.Built.Current` with `FieldClassification.ColumnReference` at `Type.field`; assert the completion list contains terminal-table columns, not enclosing-table columns).
