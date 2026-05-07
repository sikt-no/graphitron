---
id: R100
title: "LSP validation and completions for @node and @nodeId directives"
status: Backlog
bucket: feature
depends-on: []
---

# LSP validation and completions for @node and @nodeId directives

The LSP today covers `@table` / `@reference` / `@field` / `@service` / `@condition` / `@record` but has no awareness of `@node` or `@nodeId`, even though both are central to the Relay-style identification surface and have well-defined argument shapes. `@node(typeId: String, keyColumns: [String!])` carries a column list that must match real columns on the type's `@table`-backed jOOQ class, and `@nodeId(typeName: String)` carries a schema-local type reference that must resolve to a type declared `@node`. Schema authors get no completion when typing these arguments, no hover when reading them, and no diagnostic when they typo a column name or point at a non-`@node` type. The build-time validators catch the mistakes eventually (R88's rejection-string-carrier-widening covers `unknownTypeName` for `@nodeId` and `keyColumns` misses for `@node`), but in-editor feedback is the difference between a tight write-loop and a generate-fail-edit-regenerate cycle.

The work splits cleanly along the existing LSP module shape:

- **`@node(keyColumns:)` completion**: inside the `[String!]` list literal, offer the column names of the jOOQ table backing the surrounding type's `@table`. Mirrors the existing `TableCompletions` pattern; the catalog already carries column names per table, so this is wiring rather than new data.
- **`@node(keyColumns:)` validation**: each list element that doesn't match a column on the resolved table is a diagnostic. Reuse R88's `Rejection` carrier so the message shape matches the build-time error.
- **`@node(typeId:)` validation**: optional, but a duplicate-`typeId` diagnostic across the whole schema would catch a real class of bugs (two types silently sharing an ID). Lower priority than the column work.
- **`@nodeId(typeName:)` completion**: inside the string literal, offer the names of all OBJECT types in the schema that declare `@node`. This requires the catalog to track "types with `@node`" alongside the existing classification data; small extension to `CompletionData`.
- **`@nodeId(typeName:)` validation**: an explicit `typeName` that doesn't match any schema type, or matches a type without `@node`, is a diagnostic with two distinct messages (the unknown-type case and the not-a-node-type case point at different fixes).
- **Hover** on either directive's argument values: render the resolved target (column list with types for `@node`, the target type's `@node` summary for `@nodeId`).

Scope:

- LSP module: new `NodeKeyColumnsCompletions` and `NodeIdTypeNameCompletions` providers under `graphitron-rewrite/graphitron-lsp/src/main/java/.../completions/`, dispatched from `GraphitronTextDocumentService.completion` alongside the existing per-directive switch arms; matching diagnostic checks in `Diagnostics.java`; matching hover support in `Hovers.java`.
- Catalog: extend `CompletionData` to carry the set of types that declare `@node` (with their `typeId` if explicit, and their `keyColumns` if explicit). The catalog builder reads this off the same SDL parse the build-time pipeline already runs; no new scan.
- Tests: per-provider unit tests for completion / diagnostic / hover, plus at least one end-to-end LSP test asserting the wire shape (the existing `lsp` integration tests under `graphitron-lsp` are the right home).
- Docs: a short subsection in the LSP user-facing docs covering the new directive coverage; no rewrite of the existing material.

Non-goals: validating the `@nodeId` deduction rules (containing-type / unique-table inference) — those are codegen-time decisions and out of scope for in-editor feedback. Validating `@node` placement (must be on OBJECT, must be on a `@table`-backed type) is also out of scope here; the build-time classifier already does it and surfacing it twice would duplicate the rejection vocabulary.
