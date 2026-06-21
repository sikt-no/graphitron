---
id: R350
title: "LSP goto-definition for GraphQL types only resolves declarations in open buffers; fall back to the TDR/snapshot for workspace-wide jumps"
status: Backlog
bucket: bug
priority: 5
theme: lsp
depends-on: []
created: 2026-06-21
last-updated: 2026-06-21
---

# LSP goto-definition for GraphQL types only resolves declarations in open buffers; fall back to the TDR/snapshot for workspace-wide jumps

Goto-definition on a GraphQL type reference (the `Film` in `films: [Film!]!`, an
`implements` interface, or a union member) only resolves when the file that *declares*
the target type is currently open in the editor. `IntraSchemaDefinitions.compute`
(`graphitron-lsp/.../definition/IntraSchemaDefinitions.java:53`) walks
`workspace.openUris()` and checks each open file's `declaredTypes()`; if no open buffer
declares the type, it returns empty and the server responds with `[]` ("No definitions
found"). In a real multi-file schema the declaring file is frequently not open, so the
jump silently fails. This contradicts the data we already hold: the graphql-java
`TypeDefinitionRegistry` (the "TDR", built in `RewriteSchemaLoader.load` with
`MultiSourceReader.trackData(true)`) carries every type from every schema file, and each
`TypeDefinition.getSourceLocation()` exposes `getSourceName()` (the file path) plus
line/column. That registry already reaches the LSP: `CatalogBuilder.buildSnapshot`
projects it into the `LspSchemaSnapshot` held by `Workspace.snapshot()`. We should not be
limited to open buffers.

## Proposed fix

1. Add a `Map<String, SourceLocation>` (SDL type name -> canonical declaration location)
   to `LspSchemaSnapshot.Built`, populated in `CatalogBuilder.buildSnapshot` by iterating
   the registry's type definitions (objects, interfaces, unions, enums, inputs, scalars)
   and reading each definition's `SourceLocation`. The existing scalar helper
   (`CatalogBuilder.java:1055`, `"file://" + loc.getSourceName()`) is the pattern to reuse.
2. In `IntraSchemaDefinitions.compute`, keep the open-buffer scan first (it yields a precise
   tree-sitter name span and reflects unsaved edits), then fall back to the snapshot map
   when no open buffer declares the type, returning a `Location` pointing at the on-disk file.

Open-buffer-first preserves correctness for files mid-edit; the snapshot is the
workspace-wide fallback. Distinct from R349, which is the `@service`/class-ref
(`CompletionData`) feed, not intra-schema type jumps.

## Acceptance

LSP-tier test: goto-definition on a type reference whose declaration lives in a file that
is *not* open resolves to that file's URI and the declaration position via the snapshot
fallback, while the open-buffer path still wins (precise span, live edits) when the
declaring file is open.
