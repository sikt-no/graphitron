---
id: R100
title: LSP validation and completions for @node and @nodeId directives
status: Spec
bucket: feature
theme: lsp
depends-on: []
last-updated: 2026-05-12
---

# LSP validation and completions for @node and @nodeId directives

The LSP today covers `@table` / `@reference` / `@field` / `@service` / `@condition` / `@record` but has no awareness of `@node` or `@nodeId`, even though both are central to the Relay-style identification surface and have well-defined argument shapes. `@node(typeId: String, keyColumns: [String!])` carries a column list that must match real columns on the type's `@table`-backed jOOQ class, and `@nodeId(typeName: String)` carries a schema-local type reference that must resolve to a type declared `@node`. Schema authors get no completion when typing these arguments, no hover when reading them, and no diagnostic when they typo a column name or point at a non-`@node` type. The build-time classifier already catches every mistake the LSP surfaces here: `TypeBuilder` rejects unresolved `keyColumns` elements (`TypeBuilder.java:585`) and duplicate `typeId`s (`TypeBuilder.java:221`) as `Rejection.structural`, and `FieldBuilder` rejects `@nodeId(typeName:)` as `Rejection.unknownTypeName` for unknown types and as `Rejection.structural` ("does not have @node") for not-a-`@node` types. R88's rejection-string-carrier widening is the contract the LSP consumes. This item is pure LSP-side surfacing of those existing classifier rejections; no new source of truth is introduced. In-editor feedback is the difference between a tight write-loop and a generate-fail-edit-regenerate cycle.

The work splits cleanly along the existing LSP module shape:

- **`@node(keyColumns:)` completion**: inside the `[String!]` list literal, offer the column names of the jOOQ table backing the surrounding type's `@table`. Mirrors the existing `TableCompletions` pattern; the catalog already carries column names per table, so this is wiring rather than new data.
- **`@node(keyColumns:)` validation**: surface the `TypeBuilder.java:585` `Rejection.structural` ("key column '...' in @node could not be resolved...") as an in-editor diagnostic on the offending list element. The message text comes from the classifier verbatim so build-time and LSP wording stay in sync.
- **`@node(typeId:)` validation**: surface the `TypeBuilder.java:221` duplicate-`typeId` `Rejection.structural` across the whole schema; the rejection already exists in the classifier, so the LSP just paints it. Same priority as the column work, not lower; the rejection is already there.
- **`@nodeId(typeName:)` completion**: inside the string literal, offer the names of all OBJECT types in the schema that declare `@node`. This requires the catalog to track "types with `@node`" alongside the existing classification data; small extension to `CompletionData` (see Catalog scope below).
- **`@nodeId(typeName:)` validation**: surface the two existing `FieldBuilder` rejections, `Rejection.unknownTypeName` for unknown types and `Rejection.structural` ("does not have @node") for not-a-`@node` types. The classifier already emits two distinct rejections; the LSP just routes each to its own diagnostic.
- **Hover** on either directive's argument values: render the resolved target (column list with types for `@node`, the target type's `@node` summary for `@nodeId`).

Scope:

- LSP module: new `NodeKeyColumnsCompletions` and `NodeIdTypeNameCompletions` providers under `graphitron-rewrite/graphitron-lsp/src/main/java/.../completions/`, dispatched from `GraphitronTextDocumentService.completion` alongside the existing per-directive switch arms; matching diagnostic checks in `Diagnostics.java`; matching hover support in `Hovers.java`.
- Catalog: extend `CompletionData` with a `NodeMetadata` carrier record, keyed by GraphQL type name, holding the *author-supplied* `typeId` and `keyColumns` (both nullable, since either axis may be omitted in the SDL). This is pre-deduction data: it captures what the schema author wrote, not the classifier's resolved values. The LSP intentionally operates on author-supplied data only; cases where `typeId` or `keyColumns` are deduced by the classifier (containing-type / unique-table / PK inference) are invisible to in-editor feedback by design. The catalog builder reads this off the same SDL parse the build-time pipeline already runs; no new scan.
- Tests: one wire-shape integration test per LSP surface added (five surfaces: `@node(keyColumns:)` completion, `@node(keyColumns:)` diagnostic, `@nodeId(typeName:)` completion, `@nodeId(typeName:)` diagnostic, hovers for both), under the existing `lsp` integration tests in `graphitron-lsp`. Per-provider unit tests sit alongside the providers.
- Docs: a short subsection in the LSP user-facing docs covering the new directive coverage; no rewrite of the existing material.

Non-goals: validating the `@nodeId` deduction rules (containing-type / unique-table inference), since those are codegen-time decisions and out of scope for in-editor feedback. Validating `@node` placement (must be on OBJECT, must be on a `@table`-backed type) is also out of scope here; the build-time classifier already does it and the LSP would duplicate the rejection vocabulary without adding new coverage. The "@node omits keyColumns but table has no primary key" rejection (`TypeBuilder.java:608`) is similarly out of scope: it fires on argument *absence*, not a misspelled value, and the natural fix path is to add the argument rather than correct an existing one.
