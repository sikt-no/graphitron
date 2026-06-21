---
id: R350
title: "LSP goto-definition for GraphQL types only resolves declarations in open buffers; fall back to the TDR/snapshot for workspace-wide jumps"
status: Ready
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
2. Thread the snapshot into `IntraSchemaDefinitions.compute` as a parameter (see the
   **Wiring** note), keep the open-buffer scan first (it yields a precise tree-sitter name
   span and reflects unsaved edits), then fall back to the snapshot map when no open buffer
   declares the type, returning a `Location` pointing at the on-disk file.

Open-buffer-first preserves correctness for files mid-edit; the snapshot is the
workspace-wide fallback. Distinct from R349, which is the `@service`/class-ref
(`CompletionData`) feed, not intra-schema type jumps.

## Design notes

- **Key and source.** Key the map on the SDL type name. Populate from
  `TypeDefinitionRegistry.types()` (objects, interfaces, unions, enums, inputs; these are
  canonical definitions, not extensions) plus `registry.scalars()`. Skip the bundled
  `directives.graphqls` source (its `getSourceName()` is the classpath resource name, not a
  file a consumer can open) and the built-in scalars; an entry whose `SourceLocation` has a
  null `sourceName` is dropped rather than emitted as a dead `file://` URI.
- **Reuse `CompletionData.SourceLocation`.** It already models `(uri, line, column)` and
  lives in the same `catalog` package as the snapshot; the scalar helper at
  `CatalogBuilder.java:1055` already produces `"file://" + loc.getSourceName()` from a
  definition's `SourceLocation`. Reuse both rather than introducing a parallel location type.
- **Snapshot constructor fan-out.** `LspSchemaSnapshot.Built` has two leaf records
  (`Current`, `Previous`), each with a canonical constructor and a convenience constructor,
  plus `Workspace.demoteSnapshot` which copies `Current` → `Previous`. Adding the map means
  threading it through all of these; the convenience constructors (used by LSP unit-test
  fixtures) default it to `Map.of()`, matching how they already default the R160 maps.
- **Coordinate conversion.** graphql-java `SourceLocation` is 1-based line/column; LSP
  `Position` is 0-based. The fallback `Location` points at the definition's start (the
  `type`/`scalar` keyword), not the name node, since the precise name span is only available
  from the tree-sitter open-buffer path. Reuse whatever 1-based→0-based adjustment the
  existing definition arms apply so the convention stays in one place.
- **Precedence.** Open-buffer scan stays first and authoritative; the snapshot fallback runs
  only when no open buffer declares the type. A type declared in an open buffer that the
  user is mid-editing must still resolve to the live tree-sitter span, not the last-built
  snapshot position.
- **Wiring (test seam).** `IntraSchemaDefinitions.compute(Workspace, String, Point)` is today
  the only definition provider that takes the whole `Workspace` and reads only open buffers;
  every sibling takes the snapshot as a parameter. `Definitions.compute(WorkspaceFile,
  CompletionData, LspSchemaSnapshot, Point)` is the convention, and `DefinitionsTest` exercises
  it by passing a `Built.Current` fixture (`fooFilmSnapshot()`) straight in. `IntraSchemaDefinitionTest`
  has no equivalent seam: every case builds a bare `new Workspace()` and the only way to install
  a snapshot on a live `Workspace` is `setBuildOutput`, which demands full `BuildArtifacts`. So
  the snapshot must be a `compute` parameter, not reached through `Workspace`. New signature:
  `IntraSchemaDefinitions.compute(Workspace workspace, LspSchemaSnapshot snapshot, String cursorUri,
  Point pos)` (the `Workspace` stays, since the open-buffer scan still needs `openUris()`). The
  production call site at `GraphitronTextDocumentService.java:149` already holds
  `workspace.snapshot()` and passes it, mirroring the `Definitions.compute` call on the line above.

## Acceptance

LSP-tier test in `IntraSchemaDefinitionTest`, authored against the new `compute(Workspace,
LspSchemaSnapshot, String, Point)` signature so the snapshot is injected as a parameter (a
`Built.Current` fixture carrying the type-location map), matching how `DefinitionsTest` injects
`fooFilmSnapshot()`:

- **Snapshot fallback.** A type reference whose declaration is *not* in any open buffer, with a
  `Built.Current` snapshot mapping that type to a file URI + position, resolves to that URI and
  position. Asserting against the injected fixture, no `setBuildOutput`/`BuildArtifacts` needed.
- **Open-buffer precedence.** When the declaring file *is* open, the open-buffer path still wins:
  the result is the live tree-sitter name span, not the snapshot position, even if both are present.
- **Neither source.** Unknown type with an `Unavailable` (or non-matching `Built.Current`) snapshot
  and no open declaration returns empty, preserving the current no-op behaviour.

## Reviewer note (Spec → Ready, 2026-06-21) — resolved

The reviewer requested that the spec pin *how* `IntraSchemaDefinitions.compute` obtains the
snapshot, since the named acceptance test was not authorable against the current harness
(the provider took the whole `Workspace` and read only open buffers, with no seam to inject
a snapshot). Resolved in this spec: the **Wiring (test seam)** design note pins the
`compute(Workspace, LspSchemaSnapshot, String, Point)` signature and the
`GraphitronTextDocumentService.java:149` call site passing `workspace.snapshot()`, and the
Acceptance arms above inject a `Built.Current` fixture directly, matching
`DefinitionsTest.fooFilmSnapshot()`.
