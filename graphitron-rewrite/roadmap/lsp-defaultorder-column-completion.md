---
id: R343
title: "LSP column-name completion for @defaultOrder(fields[].name)"
status: Spec
bucket: feature
theme: lsp
depends-on: []
created: 2026-06-19
last-updated: 2026-06-19
---

# LSP column-name completion for @defaultOrder(fields[].name)

## Problem

`@defaultOrder(fields: [{name: "..."}])` names a database column on the list/connection field's *target* table to sort by, but the LSP offers no value completion at that site. The `LspVocabulary` canonical overlay (`LspVocabulary.CanonicalOverlay.overlay()`) binds `@field(name:)`, `@node(keyColumns:)`, `@table(name:)`, `@reference(path:[{key:}])`, etc. to catalog-aware behaviors, but has no entry for the `FieldSort.name` coordinate, so typing inside `@defaultOrder(fields: [{name: "prio…"}])` falls through to the generic `ArgNameCompletions` path: argument names only, no column suggestions.

The cost shows up in practice: when authors cannot discover that `@defaultOrder` accepts an arbitrary column (not just `primaryKey: true`), they drop out of the declarative model and hand-write a condition resolver purely to control ordering (e.g. a `selectFrom(...).orderBy(TABLE.PRIORITET.asc()).fetchGroups(...)` batch loader). `@defaultOrder(fields: [{name: "prioritet"}])` already expresses that ordering end-to-end (`OrderByResolver.resolveOrderEntries` resolves any column via `ctx.catalog.findColumn(tableSqlName, colName)`, not just PK columns), so closing the completion gap removes the discovery friction that motivates the hand-written escape hatch.

## Scope

In scope: column-name completion for the `FieldSort.name` coordinate, i.e. inside `@defaultOrder(fields: [{name: ▮}])`, scoped to the columns of the field's **target** (element-type) table.

Out of scope: `@orderBy` (its argument references an author-defined `@order` enum/input type, not catalog columns directly) and `@splitQuery` (no value to complete). `FieldSort.collate` and `FieldSort.direction` completion are also out of scope here; `direction` is a closed `SortDirection` enum that the registry already exposes for a generic enum-value completion and is better handled by a separate, general enum-value completion item if desired.

## Mechanism and the crux

Adding the overlay entry itself is the documented one-line additive change:

```java
out.put(new SchemaCoordinate.InputField("FieldSort", "name"),
        new Behavior.CatalogColumnBinding());
```

The structural invariant in the `LspVocabulary` constructor already requires `FieldSort.name` to resolve against the parsed `directives.graphqls` (it does: `input FieldSort { name: String! ... }`), and the nested-object cursor walk in `locateAt` / `collectObjectFieldChain` already keys a cursor inside `fields: [{name: "…"}]` to `InputField("FieldSort", "name")` (same shape as the working `@reference(path:[{condition:{className:}}])` descent).

The crux is **which table's columns to offer.** `FieldCompletions.generate` resolves the candidate set from the *enclosing* GraphQL type + field (`typeName`, `fieldName`), not from the coordinate. For `@field(name:)` and `@node(keyColumns:)` the columns belong to the enclosing type's own `@table`, which is exactly what the enclosing-type backing lookup returns. But `@defaultOrder` sits on a list/connection field (e.g. `Soknadskladd.utdanningstilbud: [Utdanningstilbud!]!`) whose ordering columns live on the **element type's** table (`Utdanningstilbud`), not the enclosing type's (`Soknadskladd`). A naive overlay entry would therefore offer the wrong table's columns via the `built.typesByName().get(typeName)` backing branch.

The R233 path in `FieldCompletions.completionsFor` already exists for exactly this terminal-table problem: `built.fieldClassification(typeName, fieldName).lspColumnDispatch()` returns `Resolve(tableName)` with the field's *projected terminal table* for `@reference` path fields, short-circuiting before the enclosing-type backing branch. The implementation question this Spec must answer: does `lspColumnDispatch()` already resolve the terminal/element table for a `@defaultOrder`-bearing list/connection field (including the plain-`@reference`+`@splitQuery` shape in the motivating example), or does it return `FallThrough` for fields whose element table is reached through the GraphQL type system rather than a `@reference` key? If the latter, the work extends the classification's `lspColumnDispatch()` (in `FieldClassification`, main `graphitron` module) so that any list/connection field accepting `@defaultOrder` resolves its element table, rather than patching table resolution inside the LSP.

## Approach

1. Add the `InputField("FieldSort", "name") -> CatalogColumnBinding` overlay entry.
2. Audit `FieldClassification.lspColumnDispatch()` against `@defaultOrder` field shapes (plain list field, `@asConnection`, `@splitQuery`, `@reference`-backed). Where it does not already `Resolve` the element table, extend it so it does. Keep table resolution in the classification, not in `FieldCompletions`, so the build-tier and edit-tier agree on the terminal table.
3. No new completion provider: `FieldCompletions` is the consumer for `CatalogColumnBinding` and needs no structural change beyond what step 2 feeds it.

## Testing

- `FieldCompletionsTest`: a `@defaultOrder(fields: [{name: ▮}])` field on a type whose element type differs from the enclosing type, asserting the **element** table's columns are offered and the enclosing type's are not. Cover the motivating shape (`@reference` + `@splitQuery` list field) plus a plain list field and a connection field.
- A vocabulary/overlay test that `FieldSort.name` resolves (guarded by the existing startup invariant) and dispatches to the column behavior.
- Negative: `@defaultOrder(primaryKey: true)` / `index:` sites are unaffected (no `fields` object, no `name` coordinate).

## Risks / notes

- If `lspColumnDispatch()` returns `FallThrough` for these fields today, the element-table extension is the substantive part and touches the main module's classification, not just the LSP overlay; it should reuse the same terminal-table resolution the generator's `OrderByResolver` keys off, so build and editor never disagree on which table `@defaultOrder` columns are validated against.
- Relies on the catalog snapshot being populated (the standing multi-module classpath gap, R99, applies here as everywhere: in a repo where the element type's `@table` is not on the LSP's scanned classpath, completion falls back to empty, matching today's behavior for the other catalog-aware sites).
- Builds directly on R119 (coordinate-driven completions) and R233 (terminal-table column dispatch); no new architecture.
