---
id: R340
title: "LSP goto-definition for intra-schema type references"
status: In Review
bucket: feature
theme: lsp
depends-on: []
created: 2026-06-19
last-updated: 2026-06-19
---

# LSP goto-definition for intra-schema type references

Goto-definition today (`definition/Definitions.java`) only resolves the
cursor when it sits on a *directive argument* that points into the
jOOQ-generated Java tree: `@table(name:)`, `@field(name:)`, and the
`@reference(path:)` `key`/`table` slots. It does nothing for the most
basic GraphQL navigation: putting the cursor on a *type reference*
inside the schema (the `Film` in `films: [Film!]!`, an `implements`
interface, a union member, an input-field type) and jumping to that
type's `type Film { ... }` declaration. Schema authors expect this; its
absence makes multi-file schemas hard to navigate. Unlike the
JavaParser-gated per-line refinement of the jOOQ path (R90 Phase 2),
this target lives in the tree-sitter-parsed workspace files, so real
per-declaration ranges are available now with no new dependency.

## Plan

Add intra-schema resolution as a **separate provider** that the
definition handler chains after the existing directive-arg path. This
mirrors the hover module's existing split (`DeclarationHovers` beside
`Hovers`): the directive-arg path keys on the cursor sitting *inside a
directive*, the intra-schema path keys on the cursor sitting on a
*`named_type` reference name* outside any directive. A `named_type`
reference never sits inside a directive, so the two paths never contend;
chaining named providers with `.or()` keeps that disjointness readable.

1. **`DeclarationKind.findDefinition(root, source, typeName)`** — model
   helper beside the existing `walkAll` / `enclosing`. Returns the
   `name` node of the canonical (non-extension) declaration of
   `typeName` in a tree, filtering out `isExtension()` kinds so
   navigation lands on `type Foo`, not an `extend type Foo`. Reusable
   by any future "find the declaration of N" consumer rather than
   re-spelling the `isExtension()` filter inline.
2. **`IntraSchemaDefinitions.compute(workspace, cursorUri, pos)`** — new
   provider in the `definition` package, parallel to `DeclarationHovers`.
   Resolves the leaf node at `pos` in the cursor file; proceeds only
   when it is a `name` whose parent is `named_type` (the uniform
   reference shape the `TypeNames` `@ref` query already keys on,
   covering field/arg/input types, `implements`, and union members).
   Skips `TypeNames.BUILTIN_SCALARS` (reuse the existing constant, do
   not re-list). Resolves the declaration across open files via
   `workspace.openUris()` + the per-URI lock-guarded `workspace.get()`
   (the same access the current handler already uses for the cursor
   file; no new live-`WorkspaceFile` map accessor, keeping the locked
   surface as narrow as `openUris()` made it), fast-skipping files whose
   immutable `declaredTypes()` lacks the name, then calling
   `DeclarationKind.findDefinition`. Builds the `Location` from the
   declaration name node's byte range via `Positions.toLspPosition`, so
   the range is real (not the jOOQ path's `0:0`).
3. **Wire into `GraphitronTextDocumentService.definition()`** — chain
   `Definitions.compute(...).or(() -> IntraSchemaDefinitions.compute(...))`.
4. **Tests** — a new `IntraSchemaDefinitionTest` driven through a real
   `Workspace` (`didOpen` the files, issue the request, assert the
   returned `Location` URI + `Range`, not walk internals): same-file
   reference, cross-file reference, `implements` interface, union
   member, input field type, built-in scalar (empty), unknown type
   (empty), cursor on the declaration name itself (empty), and
   definition-wins-over-extension.

Out of scope: navigating from a type reference to its `extend` blocks
(goto-definition lands on the canonical definition only; find-references
is a separate feature); the JavaParser-gated jOOQ per-line refinement
(R90).
