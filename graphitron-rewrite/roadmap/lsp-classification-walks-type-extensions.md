---
id: R216
title: LSP classification + inferred-directive arms walk type extensions, not just definitions
status: Ready
bucket: bug
theme: lsp
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# LSP classification + inferred-directive arms walk type extensions, not just definitions

R160's inlay-hint and classification-hover surfaces filter the tree-sitter walk on the six `*_type_definition` kinds only; tree-sitter-graphql emits a separate family of `*_type_extension` kinds for `extend type X { ... }` blocks, and those nodes are invisible to every surface that consumes the enclosing-type walk. Practical effect: a schema author who organises root fields under `extend type Query { ... }` (the dominant pattern when the root schema is split across files) sees no classification labels, no classification hovers, no inferred-directive ghost annotations, no column completions, no go-to-definition, and no `@field(name:)` member-validation diagnostics on anything inside that block. The build pipeline classifies the fields correctly; the snapshot's `fieldClassificationsByCoord` carries them under the same `ParentType.fieldName` key the inlay arm already looks up. The gap is purely in the LSP-side AST walk.

Two latent inconsistencies pile on top of the missing-extensions bug, and we treat both as part of this fix:

- `TypeContext.TYPE_DEFINITION_KINDS` (`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/parsing/TypeContext.java:22-28`) lacks `union_type_definition` (5 entries) while `InlayHints.TYPE_DEFINITION_KINDS` (`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/inlay/InlayHints.java:49-56`) carries all 6. Every walk over "things that can carry classification" should consult the same kind set; the divergence is a bug, not a feature.
- `DeclarationHovers.findContaining` (`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/hover/DeclarationHovers.java:69-75`) and the `fieldHover` ancestor-walk (`DeclarationHovers.java:81-83`) re-list type-kind strings locally instead of routing through `TypeContext`. Same kind set, three sources of truth.

## Shape of the fix

### A `DeclarationKind` enum in `parsing/`

The divergence the bug surfaced is one source-of-truth question ("what tree-sitter kinds count as a type declaration?") evaluated three times with three different answers. The natural axis isn't a `Set<String>` — it's a closed family of kinds with structural predicates (`isExtension()`, `isCarrier()`). Pick the closed-family shape now so the next consumer ("kinds that admit `@table`", "kinds with `directives` children", etc.) lands as an enum predicate instead of yet another `Set`.

Introduce `DeclarationKind` in `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/parsing/DeclarationKind.java` (new file), a Java `enum` with 12 constants — one per tree-sitter kind — each carrying its tree-sitter string and the two predicates the LSP currently splits on:

```java
public enum DeclarationKind {
    OBJECT_DEF      ("object_type_definition",       /*carrier*/ true,  /*extension*/ false),
    INTERFACE_DEF   ("interface_type_definition",    true,  false),
    INPUT_OBJECT_DEF("input_object_type_definition", true,  false),
    UNION_DEF       ("union_type_definition",        false, false),
    SCALAR_DEF      ("scalar_type_definition",       false, false),
    ENUM_DEF        ("enum_type_definition",         false, false),
    OBJECT_EXT      ("object_type_extension",        true,  true),
    INTERFACE_EXT   ("interface_type_extension",     true,  true),
    INPUT_OBJECT_EXT("input_object_type_extension",  true,  true),
    UNION_EXT       ("union_type_extension",         false, true),
    SCALAR_EXT      ("scalar_type_extension",        false, true),
    ENUM_EXT        ("enum_type_extension",          false, true);

    public boolean isCarrier()   { /* admits fields_definition / input_fields_definition */ }
    public boolean isExtension() { /* belongs to the _type_extension family */ }

    public static Optional<DeclarationKind> of(String treeSitterKind) { /* lookup */ }
    public static Optional<DeclarationKind> of(Node node) { return of(node.getType()); }
}
```

Co-locate the two walks the LSP needs on the same module so callers don't reach for `Set.contains` themselves:

- `DeclarationKind.enclosing(Node inner) -> Optional<Node>` — walks ancestors and returns the nearest node `n` with `DeclarationKind.of(n).isPresent()`. Replaces `TypeContext.enclosingTypeDefinition`.
- `DeclarationKind.walkAll(Node root, Consumer<Node>)` — replaces `InlayHints.walkTypeDefinitions`; visits every descendant whose kind resolves.

Tree-sitter-graphql produces each `_type_extension` kind with the same `name`, `fields_definition` / `input_fields_definition`, and `directives` children as its definition sibling, so a single walker and a single `childOfKind(...)` reader work for both shapes.

Rename consequence: `TypeContext.enclosingTypeDefinition` is replaced at every call site by `DeclarationKind.enclosing`. The local `TYPE_DEFINITION_KINDS` sets in `InlayHints` and `TypeContext`, plus the inlined string lists in `DeclarationHovers`, are deleted.

### Sites that consume the enum

| # | Site | File:line | Today | After |
|---|---|---|---|---|
| 1 | `walkTypeDefinitions` | `inlay/InlayHints.java:49-56`, 294-301 | 6 definition kinds in a private `Set<String>` | `DeclarationKind.walkAll(root, ...)` |
| 2 | `enclosingTypeDefinition` | `parsing/TypeContext.java:22-50` | 5 definition kinds (no union) in a private `Set<String>` | `DeclarationKind.enclosing(node)` |
| 3 | type-name switch in `findContaining` | `hover/DeclarationHovers.java:69-75` | hard-coded 6-arm string switch | `DeclarationKind.of(parent).isPresent()` |
| 4 | `fieldHover` ancestor-walk | `hover/DeclarationHovers.java:78-96` (kind check at 81-83) | inline `equals` on 3 carrier definition kinds | `DeclarationKind.of(parent).filter(DeclarationKind::isCarrier).isPresent()` |

### Downstream callers that ride the broadened helper

These are not new edit sites — they call the renamed helper as-is — but each one's behaviour changes inside `extend type X { ... }` blocks and the implementer should expect to touch each one at least to verify:

- `completions/ReferenceCompletions.java:44` — `@reference` key completion.
- `completions/FieldCompletions.java:54` — `@field(name:)` column completion.
- `completions/ScalarTypeCompletions.java:50` — keeps its `.filter(n -> "scalar_type_definition".equals(...))` step, which preserves the existing scalar-only behaviour and ignores any `scalar_type_extension` ancestor. No new behaviour from this caller; called out so a future reader doesn't read the filter as dead code.
- `definition/Definitions.java:64` — go-to-definition for `@field(name:)` / `@reference`.
- `hover/Hovers.java:267` — column hover for cursor on a `@field(name:)` arg value.
- `diagnostics/Diagnostics.java:509` — `@field(name:)` member validation.
- `inlay/InlayHints.java:167, 187, 214` — three inferred-directive renderers (`@table`, `@field`, `@reference`).

### Snapshot-routed `tableNameOf`

Today `TypeContext.tableNameOf(typeDef, source)` reads `@table(name:)` from `typeDef`'s own `directives` child (`TypeContext.java:89-101`). Once `DeclarationKind.enclosing` can return an extension node, two callers will receive an extension whose own `directives` child has no `@table`, even when the matching `type Foo @table(...)` definition lives in another file — and the helper would return empty for both completion and go-to-definition.

Rework the helper to resolve by *type name* against the classification snapshot:

```java
public static Optional<String> tableNameOf(
    Node typeDecl, byte[] source, LspSchemaSnapshot.Built built
) {
    return declaredNameOf(typeDecl, source)
        .map(name -> built.typeClassificationsByName().get(name))
        .flatMap(TypeContext::tableNameFromClassification);
}
```

`tableNameFromClassification` switches over the four `TypeClassification` arms that carry a tableName (`Table`, `Node`, `TableInterface`, `TableInput`) — the exact set already lifted in `InlayHints.tableNameOf(TypeClassification)` (`InlayHints.java:244-252`). Lift that private helper into `TypeContext` so both call sites share a single switch; the dual `@DependsOnClassifierCheck(key = "type-classification-payload-faithful", ...)` already on `InlayHints.compute` and on `DeclarationHovers.compute` carries forward to the new home unchanged.

The two AST-only callers gain the snapshot in their signatures:

- `ReferenceCompletions.generate(vocab, data, context, directive, source)` → `... generate(vocab, data, snapshot, context, directive, source)`. The wiring is already there in the server: `GraphitronTextDocumentService` already passes `workspace.snapshot()` to `FieldCompletions.generate` two lines above (`server/GraphitronTextDocumentService.java:211`), so the change is mechanical.
- `Definitions.compute(file, catalog, pos)` → `... compute(file, catalog, snapshot, pos)`. Same wiring source.

Both bail when the snapshot isn't `LspSchemaSnapshot.Built` (stale-snapshot behaviour matches the existing inlay/hover arms — silent under `Unavailable`, indistinguishable under `Current` vs. `Previous`) or the type has no Table-bearing classification.

This collapses the surprise the original spec called out: `tableNameOf` returns the classifier's authoritative answer regardless of whether the AST node is a definition, an extension, or — under multi-file split — an extension whose definition lives elsewhere. The helper now reflects the rewrite-design-principles' "validator-mirrors-classifier" posture: the LSP doesn't re-derive `@table` resolution from the local AST; it asks the classifier.

### Inlay on the extension's own type-name token

`collectClassificationHints` renders a hint on each enclosing-type node's `name` child (`InlayHints.java:97-106`). After broadening, that path fires for extension nodes too. Decided: yes, render the hint. Visual parity with the definition; the user reading `extend type Foo { ... }` sees the same "table type" label they'd see on `type Foo @table(...)` elsewhere. The cost is harmless duplication when both files are open in the editor; the benefit is the common case where only the extension file is open.

## Test surface

Three tiers carry parity coverage for the extension case. Each is named below; each names a test class that already exists today (`InlayHintsTest`, `DeclarationHoversTest`, `DiagnosticsTest`), and adds methods to that class.

- **`InlayHintsTest`** — add a fixture with `extend type Query { ... }` containing fields with bare `@field` / `@reference`, pinning that (a) the classification label renders on the extension's type-name token, (b) field-level classification labels render on each field inside, (c) the inferred-directive ghost annotations resolve. A second fixture exercises `extend type Customer { newColumn: String @field }` where `Customer` is `@table`-classified, pinning that field-level classification + inferred `@field(name:)` resolve through name-keyed snapshot lookup even when the AST node passed to `DeclarationKind.enclosing` is the extension.
- **`DeclarationHoversTest`** — classification-hover parity inside an extension: cursor on the extension's type-name token (yields the type-classification card), cursor on a field name inside the extension (yields the field-classification card).
- **`DiagnosticsTest`** — `@field(name:)` member validation inside `extend type Customer { bad: String @field(name: "no_such_col") }` produces the canonical "unknown column" diagnostic; a parallel positive case (valid column) stays silent. Catches the silent-regression vector across the wider `DeclarationKind.enclosing` consumer set — diagnostics are the most likely path to surface a downstream consumer that doesn't tolerate an extension node.

## Out of scope (called out, not regressed)

- **Generator-side admission of `extend type Foo @table(name:"x") { ... }`**. Today the classifier does not see `@table` declared on an extension; the LSP's snapshot-routed `tableNameOf` therefore stays silent on an extension that declares `@table` without a corresponding `@table`-bearing definition. Lifting that constraint is a classifier-side change with its own roadmap item if anyone wants it; the LSP-walk fix here intentionally does not pre-empt that decision.
- Tree-sitter kinds that don't correspond to declaration coordinates (e.g. `schema_extension`); those carry no classification today.
- Completion / go-to-def fixture expansion beyond what `DiagnosticsTest` covers. The diagnostic test is the canary; completions and go-to-def share the same `DeclarationKind.enclosing` + snapshot-routed `tableNameOf` plumbing, so a green diagnostic test is reasonable evidence the other consumers work too. The implementer may add focused cases if they hit a surprise during implementation.
