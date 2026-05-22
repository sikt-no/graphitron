---
id: R224
title: Scope @field(name:) validation to the @reference path destination table
status: In Review
bucket: bug
priority: 5
theme: lsp
depends-on: []
created: 2026-05-22
last-updated: 2026-05-22
---

# Scope @field(name:) validation to the @reference path destination table

## Symptom

A `@table`-backed input (or output) type with a field that carries both `@field(name:)` and `@reference(path:)` produces a false-positive LSP diagnostic. The user-reported case:

```graphql
input MaskinbrukerApiTilgangerFilterInputV2 @table(name: "WSBRUKER_APITILGANG") {
    apier: [String!] @field(name: "APIKODE") @reference(path: [{table: "API"}])
    maskinbrukere: [String!] @field(name: "NAVN") @reference(path: [{table: "WSBRUKER"}])
}
```

The LSP emits `Unknown column 'NAVN' on table 'WSBRUKER_APITILGANG'.` even though `NAVN` is a valid column on `WSBRUKER`, the path's terminal table. The runtime (`BuildContext.classifyInputFieldInternal`, `BuildContext.java:1623`-`1639`, delegating to `ServiceCatalog.resolveColumnForReference`, `ServiceCatalog.java:79`-`83`) walks the path and resolves the column against the terminal table, so the schema builds clean. Only the LSP diagnostic surfaces the false positive.

## Trace

`Diagnostics.validateFieldMember` (`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/diagnostics/Diagnostics.java:503`-`549`) reads the enclosing type's backing shape from `LspSchemaSnapshot.Built.typesByName()` and, for `TableBacking` / `JooqRecordBacking.WithTable`, calls `validateColumnOnTable(catalog, t.tableName(), memberName, …)`. The `tableName()` it reads is the enclosing type's own `@table` name; the function never consults the field's sibling `@reference(path:)` directive, so `WSBRUKER_APITILGANG` is checked instead of the path's terminal `WSBRUKER`. The same false positive applies on output `type` declarations with `@table` + `@reference` fields (`ChildField.ColumnReferenceField` shape) — `TableBacking` is shared across `TableType`, `NodeType`, `TableInterfaceType`, and `TableInputType`.

## Implementation

The classifier already projects the resolved terminal table onto `FieldClassification.ColumnReference.tableName()` and `CompositeColumnReference.tableName()`:

- `CatalogBuilder.projectFieldClassification` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/CatalogBuilder.java:228`-`230`, `407`-`411`) constructs both records via `terminalTableName(joinPath)`, which walks the FK chain to the destination table.
- The projection is keyed on `typeName + "." + fieldName` and reachable via `LspSchemaSnapshot.Built.fieldClassification(typeName, fieldName)`.

Have `validateFieldMember` consult the classification before falling back to the type-backing table:

1. Resolve `typeName` and `fieldName` as today (lines 510-516).
2. Look up `built.fieldClassification(typeName, fieldName)`.
3. Dispatch on the classification arm:
   - `Column(table, col)` / `CompositeColumn(table, cols)` — validate `memberName` against `table` (matches today's behaviour for non-`@reference` fields).
   - `ColumnReference(table, col, joinPath)` / `CompositeColumnReference(table, cols, joinPath)` — validate against `table` (the path's terminal). This is the fix.
   - `InputUnbound` / `Unclassified` — silent. The validator already emits a precise message ("plain input type 'T': input field 'X': no column 'Y' reachable via @reference path") via `ValidationReport`; emitting a second LSP-side diagnostic with the wrong table would be noise.
   - Anything else (`Nesting`, output-side arms unrelated to columns, …) — fall through to the existing backing-driven dispatch (the field has no `@field(name:)` semantics here today, but the fall-through keeps the existing $source-sigil / record / pojo arms intact).
4. When `built.fieldClassification(…)` returns empty (snapshot freshness gap, parent type not yet classified), keep the existing backing-driven dispatch as the fallback. The $source-sigil short-circuit at lines 525-534 stays at the top of the function (sigil semantics don't depend on path resolution).

The new dispatch lives at the column-binding arm of the existing `switch (backing)` block, not as a separate top-level branch. Concretely: pull the table lookup out of the `TableBacking` / `JooqRecordBacking.WithTable` arms into a helper that prefers the classification's `tableName()` when present and falls back to the backing's table.

## Tests

`graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/DiagnosticsTest.java` already exercises `@reference(path:)` arms (see lines 256, 270, 286, 299 and 550). Add two regression cases there:

- **Input @table with @reference path retargets column lookup.** Schema fixture: an input type `@table`-bound to one table with a `[String!] @field(name: "<col-on-other-table>") @reference(path: [{table: "<other-table>"}])` field. Sakila offers concrete pairs to mirror the production case (e.g. an input type bound to `film_actor` with a field referencing into `actor` for a column on `actor`). Assert no diagnostic is emitted on the `@field(name:)` value.
- **Output @table with @reference path retargets column lookup.** Mirror on an output `type` declaration to cover the `ChildField.ColumnReferenceField` projection arm of `projectFieldClassification`.

Optionally pin the silence-on-unresolved arm: a third case with a typo'd column on a `@reference` field that asserts the LSP emits no duplicate diagnostic for `@field(name:)` (the validator's `ValidationReport` diagnostic is allowed; the assertion is on the LSP-emitted "Unknown column" line, which today incorrectly fires against the type's own table).

## Scope notes

- The fix is LSP-only. The runtime resolver already walks the path correctly; no generator-side changes.
- `R152` (`lsp-nodetype-hover-column-scoping.md`) is the sibling fix for `@nodeId` hover column lookups; same shape (scope a column lookup to the right table), independent surface.
- The validator-emitted message `InputFieldResolver` produces for an unresolvable `@reference` column miss lists candidates from the input's own table (`ctx.catalog.columnJavaNamesOf(rt.tableName())` at `InputFieldResolver.java:95`), which is also the wrong table for `@reference` fields. Out of scope here — file a follow-up if the candidate list matters to the LSP fix-it surface.

## References

- `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/diagnostics/Diagnostics.java:503`-`568` — `validateFieldMember` and `validateColumnOnTable`, the buggy dispatch.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/CatalogBuilder.java:228`-`230`, `407`-`411` — `ColumnReference` / `CompositeColumnReference` projection via `terminalTableName(joinPath)`.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/LspSchemaSnapshot.java:105`-`107` — `fieldClassification(typeName, fieldName)` lookup.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java:1623`-`1639` — runtime classifier for `@reference` input fields.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/ServiceCatalog.java:79`-`97` — `resolveColumnForReference` + `terminalTableSqlName` (the runtime's terminal-table walk).
- `graphitron-rewrite/roadmap/lsp-nodetype-hover-column-scoping.md` (R152) — sibling LSP scoping bug.
