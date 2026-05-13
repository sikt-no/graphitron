---
id: R153
title: Set explicit TextEdit range on every completion item so clients do not concatenate the prefix with the candidate
status: Spec
bucket: bug
theme: lsp
depends-on: []
created: 2026-05-13
last-updated: 2026-05-13
---

# Set explicit TextEdit range on every completion item so clients do not concatenate the prefix with the candidate

Every completion provider under `graphitron-lsp/.../completions/` builds its `CompletionItem` as `new CompletionItem(label); item.setKind(...);` and returns; no `textEdit`, no `insertText`, no `filterText`. Per LSP, when an item omits `textEdit`/`insertText` the client decides which span to replace from its own word-boundary rules, and those rules vary by client. The user reported the concrete failure mode in eglot: with `com.example.FilmServ|` at the cursor inside `@service(service: {className: "com.example.FilmServ"})`, eglot's `bounds-of-thing-at-point` with `'symbol` is governed by `graphql-mode`'s syntax table, which does not include `.` as a symbol constituent, so the "word" at point is `FilmServ`. The server returns the candidate label `com.example.FilmService`. Eglot replaces just `FilmServ` with the full label, yielding `com.example.com.example.FilmService`. VS Code happens to mask this for dotted FQNs because its default word definition is broader, but the LSP spec puts the responsibility on the server and relying on client heuristics is a portability footgun. The fix is to attach an explicit `TextEdit` to every completion item whose range covers the value (or partial value) at the cursor.

**Scope.** All eight `CompletionItem` construction sites across the providers in `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/completions/`: the six string-literal value providers (`ClassName`, `Method`, `Table`, `Field`, `Reference`, `ScalarType`), `NodeTypeCompletions`, and `ArgNameCompletions` (both the user-directive and bundled-directive paths). The bug is acute for value providers with dots in the candidate (`ClassName`/`ScalarType` FQNs, `Method` `Class#method`, dotted `table.column` in `Field`/`Reference`/`Table`); `NodeType` and `ArgName` write single tokens where eglot's default heuristic happens to land correctly, but the fix should be uniform so the provider contract is the same across the dispatch.

**Design.** Today the dispatch site in `GraphitronTextDocumentService.coordinateBasedCompletions` hands `(directive, pos, source)` to each provider and every provider re-runs the same descent through the directive's argument tree (`enclosing arg` → `object_field` chain → leaf value) via `LspVocabulary.coordinateAt`. The replaceable-range computation is a sibling output of that same descent: the leaf value node is the thing whose span we want to replace. Computing the range inside each provider would duplicate the walk in eight places and let it drift from `coordinateAt`; computing it at the dispatch site while leaving `coordinateAt` per-provider would land a half-applied hoist. Hoist both.

Add `LspVocabulary.locateAt(directive, pos, source) -> Optional<CursorLocation>` returning the existing coordinate alongside the leaf value/identifier node, and convert `coordinateAt` into a thin wrapper that extracts the coordinate from `locateAt`'s result. The one non-completion caller (`Hovers`) keeps the simpler `coordinateAt` signature; only the completion dispatch site upgrades to `locateAt`. The new record:

```java
record CursorLocation(SchemaCoordinate coordinate, Node leafNode) {}
```

The dispatch site calls `locateAt` once. If empty (cursor outside any known directive arg), the catalog-driven providers all return no items; the dispatch then tries the `ArgNameCompletions` fallback, which has its own walk because it handles cursor positions that aren't on any value (partial arg-name identifiers, whitespace inside directive parens) and isn't coordinate-driven. If non-empty, the dispatch site builds a `CompletionContext`:

```java
record CompletionContext(SchemaCoordinate coordinate, Range replaceRange) {}
```

`replaceRange` is computed from the leaf node by inspecting its tree-sitter kind: `string_value` → inner range `[startByte + 1, endByte - 1]`, collapsing to a zero-width range when the literal is empty (`""`); `block_string` → inner range `[startByte + 3, endByte - 3]` (triple-quote strip); `enum_value` and bare `name` → full span. Cursor positions that don't sit on any value/identifier node (whitespace between args, inside the directive parens before the first arg) yield `Optional.empty()` from `locateAt` itself; those flow into the `ArgNameCompletions` fallback rather than producing a degenerate range. Byte spans go through the existing `Positions.toLspPosition` for UTF-8↔UTF-16 conversion. Block strings are legal GraphQL syntax for any String-typed arg, so the helper handles them by computation, not by bailing; the `directives.graphqls` SDL declares every value-completion arg as `String`, which is the binding we'd be breaking if we returned no-`textEdit` for block-string content.

Provider signatures split into two shapes. The six coordinate-only value providers (`ClassNameCompletions`, `TableCompletions`, `FieldCompletions`, `ReferenceCompletions`, `ScalarTypeCompletions`, `NodeTypeCompletions`) drop `(LspVocabulary, Directive, Point, byte[])` and become `(CompletionData, CompletionContext)`; the context carries both the coordinate (no need to redo `coordinateAt`/`behaviorAt`) and the replace range. `MethodCompletions` keeps `(LspVocabulary, CompletionData, CompletionContext, Directive, Point, byte[])` because `siblingStringAt(directive, pos, classNameCoord, source)` still needs all four to walk to the sibling `className` slot. `ArgNameCompletions` builds its own `Range` from the partial-identifier or zero-width span at the cursor without going through `CompletionContext`, because it runs even when `locateAt` returned empty. Each value-provider's `toCompletionItem` builds the item with `setTextEdit(Either.forLeft(new TextEdit(ctx.replaceRange(), label)))`; lsp4j 0.24's `CompletionItem.setTextEdit` takes `Either<TextEdit, InsertReplaceEdit>` and the plain-`TextEdit` form is enough since insert position and replace position coincide.

**Test coverage.** One parameterized test in a new `CompletionTextEditTest` over the eight providers, asserting the shared invariant: "the returned item's `TextEdit.getRange()` covers the whole value at the cursor, not just the suffix the client would otherwise pick." Rows:

- Eight non-trivial-prefix rows, one per provider (seven value providers + `ArgNameCompletions`), each with a cursor placed mid-value/mid-identifier and an expected `Range` constructed from the test source. These are the eglot-bug regression pins.
- Eight empty-value rows, one per provider, asserting a zero-width `Range` at `pos` (for value providers: cursor inside `""`; for `ArgNameCompletions`: cursor at the bare-parens position with no partial identifier).
- One block-string row at a `className: """com.example.|"""` cursor asserting the range covers the triple-quote-stripped inner span. Pins that block strings stay supported, not deferred.
- One cursor-on-quote row per value provider asserting we still resolve to the inner content range (cursor-on-quote is the boundary case `Nodes.contains` resolves inclusively).

Direct unit tests on `LspVocabulary.locateAt` cover the node-kind dispatch (string_value / block_string / enum_value / name) without going through provider plumbing. The existing per-provider `*CompletionsTest` files stay focused on label/kind/keyset assertions; the wire-format invariant lives in one place so a new completion provider can opt into it by adding a row, not by copying assertion shape.

**Out of scope.** This item does not change which coordinates are completable, what the candidate set is, or the providers' dispatch order. `filterText` for partial-match scoring and `insertText` snippet syntax remain separate concerns; both would compose cleanly on top of this item's `setTextEdit` change but neither is forced by it.
