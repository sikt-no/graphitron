---
id: R216
title: LSP classification + inferred-directive arms walk type extensions, not just definitions
status: Spec
bucket: bug
theme: lsp
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# LSP classification + inferred-directive arms walk type extensions, not just definitions

R160's inlay-hint and classification-hover surfaces filter the tree-sitter walk on the six `*_type_definition` kinds only; tree-sitter-graphql emits a separate family of `*_type_extension` kinds for `extend type X { ... }` blocks, and those nodes are invisible to every surface. Practical effect: a schema author who organises query fields under `extend type Query { ... }` (the dominant pattern in user code that splits the root schema across files) sees no classification labels, no classification hovers, and no inferred-directive ghost annotations on anything inside that block. The fields are classified correctly by the build pipeline; the snapshot's `fieldClassificationsByCoord` carries them under the same `ParentType.fieldName` key the inlay arm already looks up. The gap is purely in the LSP-side AST walk.

## Affected sites

Three filter points in `graphitron-lsp`, all keyed on the same `*_type_definition` whitelist:

1. **`InlayHints.TYPE_DEFINITION_KINDS`** (`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/inlay/InlayHints.java:49-56`). `walkTypeDefinitions` (line 294) collects only these nodes; `collectClassificationHints` then iterates `fields_definition` / `input_fields_definition` children. The whole classification arm skips extensions.
2. **`TypeContext.TYPE_DEFINITION_KINDS`** (`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/parsing/TypeContext.java:22-28`). `enclosingTypeDefinition` is the shared "what type does this cursor / directive sit inside?" helper. It's called by `InlayHints.renderInferredTableNameHint` / `renderInferredFieldNameHint` / `renderInferredReferencePathHint` to resolve the parent type for a bare `@table` / `@field` / `@reference`, so the inferred-directive arm also blanks out inside extensions. Same helper backs `DeclarationHovers` (the classification-hover dispatch in `hover/Hovers.java`), so hover-on-field-name inside an extension returns no classification card either.
3. **`TypeContext.tableNameOf`** (same file, line 89+). Reads `@table(name:)` only from the type's own `directives` child. Extension nodes have their own `directives` child; if we ever support `extend type X @table(...)` carrying the classification directive at the extension line, this needs to look there too. Today the classification snapshot is keyed by type *name*, so the main definition's classification already covers an extension whose name matches — but the helper would surprise anyone who reads it expecting symmetric behaviour.

## Shape of the fix (Spec to confirm)

Tree-sitter-graphql produces these extension kinds, each shaped the same as its definition sibling (same `name` child, same `fields_definition` / `input_fields_definition` child, same `directives` child):

- `object_type_extension`
- `interface_type_extension`
- `input_object_type_extension`
- `union_type_extension`
- `scalar_type_extension`
- `enum_type_extension`

The cheap fix is broadening the two `TYPE_DEFINITION_KINDS` sets to include the extension variants and renaming the constant + the `walkTypeDefinitions` helper to reflect that they cover both. The snapshot lookups (`typeClassificationsByName.get(typeName)`, `fieldClassificationsByCoord.get(parentTypeName + "." + fieldName)`) are name-keyed and need no change — multiple AST nodes (one definition + N extensions) carrying the same type name all resolve to the same classification entry.

Two judgement calls for the Spec phase:

- **Should the extension's own type-declaration name token carry a classification inlay?** Arguments for: visual parity with the definition; the user reading `extend type Foo { ... }` sees the same "table type" label they'd see on `type Foo @table(...)` elsewhere. Arguments against: clutter, since the same label already renders on the main definition in another file; the extension is a continuation, not a re-classification. Default proposal: yes, render it — the consistency outweighs the duplication, and an editor user often has only the extension file open.
- **`@table` on the extension line.** Today graphitron does not (afaict) admit `extend type Foo @table(name:"foo") { ... }` — `@table` belongs on the definition. The inferred-directive arm doesn't need to read from the extension's directive list because the inferred *target* table is on the definition's classification, looked up by type name. The Spec should confirm this and pin the rationale in a comment so a future change to `TypeContext.tableNameOf` doesn't accidentally start reading both lists and double-count.

## Test surface

- `InlayHintsTest` adds a fixture with `extend type Query { ... }` plus bare `@field` / `@reference` directives inside, pinning that both classification labels and inferred-directive ghost annotations render under the extension.
- A fixture also covering `extend type` on a `@table`-classified type (a few `extend type Customer { newColumn: String @field }` style cases), pinning that the field-level classification + inferred `@field(name:)` resolve through the snapshot's name keying.
- `DeclarationHoversTest` parallel case for classification-hover on a field-name token inside an extension.

## Out of scope (called out, not regressed)

- Allowing `@table` on the extension line; if a Spec round decides to admit it, that's a generator-side change with its own item, not part of this LSP-walk fix.
- Repeating the same broadening for tree-sitter kinds that don't correspond to declaration coordinates (e.g. `schema_extension`); those carry no classification today.
