---
id: R100
title: LSP validation and completions for @node and @nodeId directives
status: In Review
bucket: feature
theme: lsp
depends-on: []
last-updated: 2026-05-12
---

# LSP validation and completions for @node and @nodeId directives

The LSP today covers `@table` / `@reference` / `@field` / `@service` / `@condition` / `@record` but has no awareness of `@node` or `@nodeId`, even though both are central to the Relay-style identification surface and have well-defined argument shapes. `@node(typeId: String, keyColumns: [String!])` carries a column list that must match real columns on the type's `@table`-backed jOOQ class, and `@nodeId(typeName: String)` carries a schema-local type reference that must resolve to a type declared `@node`. Schema authors get no completion when typing these arguments, no hover when reading them, and no per-keystroke diagnostic when they typo a column name or point at a non-`@node` type. The build-time classifier already catches every mistake the LSP surfaces here: `TypeBuilder` rejects unresolved `keyColumns` elements and duplicate `typeId`s as `Rejection.structural`, and `FieldBuilder` rejects `@nodeId(typeName:)` as `Rejection.unknownTypeName` for unknown types and as `Rejection.structural` ("does not have @node") for not-a-`@node` types. R88's rejection-string-carrier widening is the contract the LSP consumes. This item is pure LSP-side surfacing of those existing classifier rejections; no new source of truth is introduced. In-editor feedback is the difference between a tight write-loop and a generate-fail-edit-regenerate cycle.

## Two paint paths

The LSP already paints classifier rejections from a fresh build through `Diagnostics.validatorDiagnostics`, which consumes `ValidationReport` entries with their `SourceLocation` and emits matching `Diagnostic`s. All four classifier rejections above carry usable locations and paint via this path after each rebuild today; the validator-report path is the canonical per-classifier-rejection paint surface. This item adds a *per-keystroke responsiveness layer* on top of it for the high-frequency edits — column-name typos in `keyColumns:` and type-name typos in `typeName:` — where waiting for a rebuild is the user-experience cost. The two paths are layered, not duplicates: `validatorDiagnostics` keeps the full coverage at the rebuild tier; the coordinate-driven walk in this spec covers the three high-frequency surfaces on every keystroke. Cross-schema invariants (typeId uniqueness) stay on the rebuild path; see *Out of scope* for the rationale.

## Dispatch model: overlay deltas, no per-directive switch

LSP completion and diagnostic dispatch is `Behavior`-driven: `LspVocabulary.CanonicalOverlay.overlay()` maps each `SchemaCoordinate` to a `Behavior` record, and each completion provider / diagnostic arm gates on that record. The work splits as canonical-overlay additions plus one new `Behavior` arm:

- **`@node(keyColumns:)` completion + diagnostic.** Register `DirectiveArg("node", "keyColumns") → CatalogColumnBinding` in the canonical overlay. `FieldCompletions` then auto-fires for keyColumns completion (it reads the enclosing type's `@table` and offers that table's columns); `Diagnostics.validateCatalogColumn` auto-fires for the unresolved-column diagnostic. No new completion provider class; this surface is pure overlay-delta.

- **`@nodeId(typeName:)` completion + diagnostic.** Introduce a new `Behavior.NodeTypeBinding()` record, register `DirectiveArg("nodeId", "typeName") → NodeTypeBinding`, and add one new completion provider (`NodeTypeCompletions`) under `completions/`, plus one new arm in `Diagnostics.dispatch`. Both read the new `NodeMetadata` map on `CompletionData` (see *Catalog scope*). `NodeTypeBinding` is a sibling-by-keyset to `CatalogColumnBinding` and `CatalogTableBinding`: same dispatch shape, different keyset (the SDL type set narrowed to `@node`-bearing types). If a second SDL-type-name binding lands later (`@interface(name:)`, etc.), the refactor trigger is to collapse the siblings into a predicate-shaped `GraphQLTypeBinding(predicate)`; we don't pre-empt the abstraction with one consumer.

- **Hover** on either directive's argument values. `Hovers` pattern-matches on coordinate today; add cases that read `CompletionData.Column` for `@node(keyColumns:)` element values (column name + `graphqlType`) and the target type's `NodeMetadata` entry for `@nodeId(typeName:)` (rendered as the resolved `typeId` plus the key-column list, with each column's type pulled from `CompletionData.Column`). No hover-specific data.

## List-element fan-out in `leafCoordinates`

`LspVocabulary.leafCoordinates` today emits one `Leaf` per top-level directive argument; the leaf's `valueNode` is whatever the user wrote, including a `list_value` for `keyColumns: ["a", "b"]`. The dispatch arms (`validateCatalogColumn`, the new `NodeTypeBinding` arm) treat `valueNode` as a single scalar string, so a list-shaped value does not fan out per element today. Extend `leafCoordinates` / `descendLeaves` to descend into raw `list_value` nodes the same way they descend into `object_field`s: emit one `Leaf` per scalar element, carrying the same outer `SchemaCoordinate` and the element value node as `valueNode`. Pin the contract explicitly: **`Leaf.valueNode` is the scalar value node, never an enclosing `list_value`**. Existing consumers that already assume a scalar node are unchanged; the new contract just makes that assumption universal. This generalises the leaf walk rather than splitting `CatalogColumnBinding` by arity: any future list-of-scalars directive arg falls in for free, and the dispatch keeps one arm per concept.

## Catalog scope

Extend `CompletionData` with a `NodeMetadata` carrier record, keyed by GraphQL type name. **An entry exists for every type whose SDL carries `@node`, regardless of which axes the author filled**; presence in the map is the predicate `NodeTypeCompletions` and the `NodeTypeBinding` diagnostic both read. Each entry holds the *author-supplied* `typeId` (nullable) and `keyColumns` (nullable list of column names); the two axes are independent nullable fields since the LSP doesn't fork on the combination — completion reads the keyset, hover reads whichever axis it renders. This is pre-deduction data: it captures what the schema author wrote, not the classifier's resolved values. The LSP intentionally operates on author-supplied data only; cases where `typeId` or `keyColumns` are deduced by the classifier (containing-type / unique-table / PK inference) are invisible to in-editor feedback by design. The catalog builder reads this off the same SDL parse the build-time pipeline already runs; no new scan.

## Tests

One wire-shape integration test per LSP surface added, five surfaces total, under the existing `lsp` integration tests in `graphitron-lsp`:

1. `@node(keyColumns:)` completion (cursor inside the list literal, one column already typed; assert the offered set is the table's column list).
2. `@node(keyColumns:)` diagnostic (one valid element, one typo'd element; assert exactly one diagnostic, on the typo'd element node).
3. `@nodeId(typeName:)` completion (cursor inside the string literal; assert the offered set is the `@node`-bearing type names).
4. `@nodeId(typeName:)` diagnostic (one case: type doesn't exist → unknown-type-name diagnostic; second case: type exists without `@node` → not-a-node diagnostic).
5. Hover (one case per directive; assert the rendered content includes the resolved column / typeId).

Per-provider unit tests sit alongside the new provider (`NodeTypeCompletionsTest`) and the new diagnostic arm picks up unit cases in the existing `DiagnosticsTest`. The leaf-walk extension lands with a unit case in `LspVocabularyTest` covering `list_value` fan-out (one list with two elements → two leaves, same coordinate, distinct element value nodes).

## Docs

A short subsection in the LSP user-facing docs covering the new directive coverage; no rewrite of the existing material.

## Out of scope

- *`@node(typeId:)` duplicate validation (cross-schema).* The classifier's typeId-duplicate `Rejection.structural` is a cross-file invariant; doing it per-keystroke means either re-walking `Workspace` per edit (cost scales with open-file count) or maintaining a precomputed typeId index on `CompletionData`. Both are real surface area for what `validatorDiagnostics` already paints with the correct location at the rebuild tier. The "Validator mirrors classifier invariants" principle requires the validator to catch the rejection at build time, which is already the case; per-keystroke responsiveness is a UX choice, and the cross-file shape doesn't earn it the way per-element column / type-name typos do.
- *`@nodeId(typeName:)` deduction rules (containing-type / unique-table inference).* Codegen-time decisions on the classifier's resolved values, not surfacings of author-supplied SDL.
- *`@node` placement validation (must be on OBJECT, must be on a `@table`-backed type, must `implements Node`).* The build-time classifier already does it and `validatorDiagnostics` already paints it; the per-keystroke layer would add no responsiveness win for one-time structural setup mistakes.
- *`@node` omits keyColumns + table has no PK.* The classifier's `Rejection.structural` here fires on argument *absence*, not a misspelled value; the natural fix path is to add the argument rather than correct an existing one, and the rebuild-tier paint is the right surface.
