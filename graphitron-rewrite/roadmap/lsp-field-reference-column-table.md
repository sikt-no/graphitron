---
id: R224
title: "Validate @field(name:) against @reference path destination table on input fields"
status: Backlog
bucket: bug
priority: 5
theme: lsp
depends-on: []
created: 2026-05-22
last-updated: 2026-05-22
---

# Validate @field(name:) against @reference path destination table on input fields

The LSP's `validateFieldMember` in `Diagnostics.java` validates `@field(name:)` on input-type fields against the enclosing input's `@table`-bound table, ignoring any sibling `@reference(path:)` directive that retargets the column lookup at a different table. For an input type `MaskinbrukerApiTilgangerFilterInputV2` bound to `WSBRUKER_APITILGANG` with a field `maskinbrukere: [String!] @field(name: "NAVN") @reference(path: [{table: "WSBRUKER"}])`, the runtime (`BuildContext.classifyInputFieldInternal` → `ServiceCatalog.resolveColumnForReference`) correctly resolves `NAVN` against the path's terminal table `WSBRUKER`, but the LSP emits a false-positive "Unknown column 'NAVN' on table 'WSBRUKER_APITILGANG'" diagnostic. The classifier's projection already records the resolved table on `FieldClassification.ColumnReference.tableName()` (and `CompositeColumnReference.tableName()`) as the terminal table per `CatalogBuilder.projectFieldClassification`; the LSP just needs to read that projection through `LspSchemaSnapshot.Built.fieldClassification(typeName, fieldName)` before falling back to the type-backing-derived table name. Sibling to R152 (which scopes `@nodeId` hover column lookups to the correct table).
