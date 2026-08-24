---
id: R818
title: "textDocument/references: find every SDL site that uses a name"
status: Spec
bucket: feature
priority: 4
theme: lsp
depends-on: []
created: 2026-08-24
last-updated: 2026-08-24
---

# textDocument/references: find every SDL site that uses a name

> The language server answers `textDocument/definition`: put the cursor on a
> name in a `.graphqls` file and the editor jumps to what that name denotes. It
> does not answer `textDocument/references`, the request behind "Find Usages" in
> every editor, so the opposite question has no answer at all. An author who is
> about to rename a table binding, retire a `@service` class, or change a type
> cannot ask "what in this schema uses it?" and has to fall back to text search,
> which cannot tell a `@table(name: "film")` binding from the word `film` in a
> description. This item adds the surface and fills it from the fact store, which
> already holds every fact the answer needs.

The naming here is worth pinning before anything else, because "reverse of jump
to definition" admits two readings and only one of them is this item.

Jump-to-definition leaves SDL: from `@table(name: "film")` it lands in
`FilmTable.java`. The literal reverse of that trip would start in a `.java`
buffer and come back to SDL, and that is **not** this item: the server answers
for `.graphqls` documents, and a Java-side cursor is a different feature with a
different client registration. What editors actually mean by the reverse is
`textDocument/references`: the cursor stays on the same SDL name, and the answer
is every *other* site in the schema that uses that name. So the trip is
SDL-to-SDL, and it fans out where definition converges: one cursor, many
locations, `List<Location>` rather than `Optional<Location>`.

## What exists today

- `GraphitronLanguageServer.initialize` registers five request capabilities
  (`hoverProvider`, `completionProvider`, `definitionProvider`,
  `codeActionProvider`, `inlayHintProvider`) plus pushed diagnostics.
  `referencesProvider` is not among them, and
  `GraphitronTextDocumentService` does not override `references`.
- The definition surface chains three providers with `.or()` inside one store
  read (`StoreRead.DEFINITION`): `Definitions` (cursor inside a directive
  argument, resolving into the consumer's Java tree), `IntraSchemaDefinitions`
  (cursor on an SDL type reference, resolving to the declaring type), and
  `DeclarationDefinitions` (cursor on an SDL declaration name, resolving to the
  Java the model bound it to). Each returns at most one `Location`.
- `LspSurface` enumerates the answering surfaces, and `TriggerDispatch.MATRIX`
  states, per `Trigger` leaf, which surfaces answer it and where the gaps are.
  Cursor classification is therefore already shared: `LspVocabulary.locateAt` +
  `behaviorAt` resolve a cursor to a coordinate and a `Behavior`, and every
  surface switches over the same sealed set.
- The store already holds the reverse index, in two families:
  - **SDL type usage.** `graphql_field` and `graphql_argument` each carry
    `named_type` alongside `source_name` / `source_line` / `source_column`;
    `graphql_implements` and `graphql_union_member` carry the rest of the
    type-reference population. "Who references `Film`?" is a filter on
    `named_type`, not a walk.
  - **Directive-target usage.** The decoded `graphitron_` family is keyed by SDL
    coordinate with the bound target as payload: `graphitron_table`
    (coordinate to table name), `graphitron_field_binding` (to column),
    `graphitron_service` / `graphitron_external_field` / `graphitron_enum` (to
    class and method), `graphitron_field_reference_step` (to FK key). Asking
    "which coordinates bind table `film`?" is a filter on the payload column.
    `graphql_directive_site` gives the position of any application, whatever
    site kind it sits on.

So the work is a surface and a set of reverse-direction reads, not new facts.

## The one asymmetry the facts impose

`graphql_*_directive_arg` carries `value_sdl` but **no** source position; the
position lives on the owning `graphql_*_directive` application row. The store can
therefore say "this `@table` application at line 42 binds `film`" but not "the
`film` literal spans columns 19 to 25". Definition never met this, because it
reads positions out of the *target's* `.java` parse, not out of SDL.

Two ways to close it, and the choice is the item's main fork:

1. **Point at the application.** Every result is the directive application's
   position, uniformly, open buffer or not. Editors highlight the line and the
   author sees the site.
2. **Refine from the buffer.** Where the file is an open buffer, re-read the
   tree-sitter parse for the exact argument-value span, and fall back to the
   application position otherwise.

Recommendation: ship (1) first. (2) makes the same site report a different range
depending on whether the author happens to have the file open, which is a skew a
reader cannot explain, and the open-buffer-authoritative pattern
`IntraSchemaDefinitions` uses is justified there by buffers being *fresher* than
the store, not more precise than it. The honest fix for precision is capture-side:
give the `_directive_arg` relations their own source position, which upgrades
every result including closed files. That is Slice C, deliberately last, because
it is a model change across five relations and the surface is useful without it.

## Slices

### Slice A: the surface plus the SDL type arm

The reverse of `IntraSchemaDefinitions`, and the arm that needs no new facts.

- `capabilities.setReferencesProvider(true)`; override
  `references(ReferenceParams)` on `GraphitronTextDocumentService`, mirroring the
  definition handler's shape (one span, one store read, one `StoreAnswer` switch,
  empty list when out of budget).
- New `StoreRead.REFERENCES` constant, so the out-of-budget warning names this
  read rather than borrowing the definition one. A references answer fans out
  over several relations where definition does a point lookup, so which
  `StoreAccess` door it goes through (interactive or bulk) is a Slice A decision,
  not an afterthought.
- New `LspSurface.REFERENCES`, and reach stated for it in every
  `TriggerDispatch.MATRIX` row. **Note the trap:** the matrix's guard pins the
  trigger *leaf* set, and any surface a row does not name defaults to
  `NO_ANSWER`, so adding the enum constant alone would silently record "declines
  everything" for all 21 rows without failing a single test. Every row is
  reviewed in this slice, and the Spec review checks that all 21 were considered
  rather than defaulted. If reviewers would rather have a mechanical guard, the
  cheaper version is making `Reach` require every surface to be named, which
  turns each omission into a build failure; worth deciding at the Ready gate.
- The arm itself: cursor on a type declaration name (`type Film`) or on a type
  reference (`films: [Film!]!`) yields every field and argument whose
  `named_type` is `Film`, every `implements` naming it, and every union member.
  Honour `ReferenceContext.isIncludeDeclaration` for whether the declaration
  sites join the list.
- Tests in the LSP tier, per population, plus one that a type nothing references
  answers with an empty list rather than declining.

### Slice B: the directive-target arms

The reverse of `Definitions`, one arm per `Behavior` leaf, each a filter on the
decoded family's payload column:

- `CatalogTableBinding`: every coordinate bound to the same table.
- `CatalogColumnBinding`: every coordinate bound to the same column.
- `ClassNameBinding` / `MethodNameBinding`: every coordinate naming the same
  class, or the same method on it.
- `CatalogFkBinding`: every `@reference` path step using the same key.
- `NodeTypeBinding`: every `@nodeId(typeName:)` naming the same type, which folds
  into Slice A's type population rather than standing alone.
- `ArgMappingBinding` / `ScalarTypeBinding`: state the verdict, gap or decline,
  in the matrix rather than resolving to nothing silently.

Dispatch is the existing exhaustive switch over `Behavior`, so a new binding arm
forces a references decision at compile time, the property `Definitions` already
has. There is no `SourceAbsent` analogue here: the reverse direction never asks
whether a declaration was positioned, so an empty answer means "nothing uses
this", full stop.

### Slice C (optional, separable): argument-value positions in capture

Add source position to the `graphql_*_directive_arg` relations and populate it in
capture, then narrow every Slice B result from the application to the value span.
Independently useful: diagnostics on directive argument values want the same
column, and it removes the open-versus-closed precision question entirely.

## Open questions for the Spec review

1. Position fork above: uniform application positions (recommended), or
   buffer-refined spans?
2. Should `Reach` be tightened to require every surface to be named per row, so
   a future surface cannot default into silence?
3. Does a references answer belong on the interactive `StoreAccess` door, given
   it fans out over more relations than any other cursor-keyed read?
4. Scope check: field-name and enum-value declaration names as reference
   subjects (who uses this field?) are deferred out of Slice A and B. Confirm
   that is the right cut, or fold the field case into Slice B.

