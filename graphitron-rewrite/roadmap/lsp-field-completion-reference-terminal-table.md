---
id: R233
title: "Scope @field(name:) completion to @reference path terminal table"
status: Backlog
bucket: bug
theme: lsp
depends-on: []
created: 2026-05-22
last-updated: 2026-05-22
---

# Scope @field(name:) completion to @reference path terminal table

## Symptom

R224 fixed `Diagnostics.validateFieldMember` so `@field(name:)` validation on a `@reference(path:)` field resolves the column against the path's terminal table, not the enclosing type's `@table`. The sibling LSP arm at the same SDL coordinate, the completion list for `@field(name: "<cursor>")`, was not touched and still draws candidates from the enclosing type's table. Reproducer (the input-type case carried over from R224):

```graphql
input MaskinbrukerApiTilgangerFilterInputV2 @table(name: "WSBRUKER_APITILGANG") {
    apier: [String!] @field(name: "<cursor>") @reference(path: [{table: "API"}])
    maskinbrukere: [String!] @field(name: "<cursor>") @reference(path: [{table: "WSBRUKER"}])
}
```

At either cursor, the completion dropdown lists `WSBRUKER_APITILGANG` columns. None are valid here; the actual valid column set is `API`'s columns (for `apier`) or `WSBRUKER`'s columns (for `maskinbrukere`). After R224 the user typing `NAVN` no longer trips a diagnostic, but they have to know the column name blind. The output-type mirror (`type Film @table(name: "film") { languageName: String @field(name: "<cursor>") @reference(path: [{table: "language"}]) }`) has the same shape: `film` columns surface instead of `language` columns.

## Trace

`FieldCompletions.completionsFor` (`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/completions/FieldCompletions.java:75`-`107`) reads the enclosing type's backing shape from `LspSchemaSnapshot.Built.typesByName()` at line 92 and dispatches on it; for `TableBacking` / `JooqRecordBacking.WithTable` it calls `tableColumnItems(data, t.tableName(), context)`. The `tableName()` it reads is the enclosing type's own `@table`; the function never consults the field's sibling `@reference(path:)` directive. This is the same shape the pre-R224 `validateFieldMember` had.

The classifier projection R224 reused is already on the snapshot: `LspSchemaSnapshot.Built.fieldClassification(typeName, fieldName)` returns the `FieldClassification` for the `Type.field` coordinate, and for `@reference` path fields that record carries the terminal table on `ColumnReference.tableName()` / `CompositeColumnReference.tableName()` (`CatalogBuilder.projectFieldClassification` at `CatalogBuilder.java:228`-`230`, `407`-`411`, projected via `terminalTableName(joinPath)`). The completions arm just doesn't consult it.

A secondary trace detail: line 63 today calls `TypeContext.enclosingFieldDefinition`, which only walks output-side `field_definition` AST nodes. R224 added `enclosingFieldOrInputValueDefinition` (`TypeContext.java:53`) that covers input-side `input_value_definition` too; the completion arm needs the broader helper for the input-type case to project a non-null `fieldName` into the classification lookup.

## Implementation

Mirror R224's `validateFieldMember` shape on `FieldCompletions.completionsFor`. The classification dispatch lives in front of the existing backing-driven switch, not as a replacement; snapshot-uncertainty (no classification on file) falls through to today's behaviour.

**File:** `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/completions/FieldCompletions.java`

1. Line 63: switch `TypeContext.enclosingFieldDefinition(directive.outer())` to `TypeContext.enclosingFieldOrInputValueDefinition(directive.outer())` so the field-name lookup resolves on input-type fields as well as output-type fields. The `@node(keyColumns:)` site (the other `CatalogColumnBinding` registration at `LspVocabulary.java:781`-`782`) sits on a type-level directive, so the enclosing-field walk returns empty there and the classification lookup is skipped: no behavioural change for `@node`.

2. Inside `completionsFor`, between the `$source`-sigil block (lines 88-91) and the backing dispatch (line 92), insert a classification-first arm gated on `fieldName != null`:

   ```java
   if (fieldName != null) {
       var classification = built.fieldClassification(typeName, fieldName);
       if (classification.isPresent()) {
           var classified = switch (classification.get()) {
               case FieldClassification.Column c            -> tableColumnItems(data, c.tableName(), context);
               case FieldClassification.ColumnReference c   -> tableColumnItems(data, c.tableName(), context);
               case FieldClassification.CompositeColumn c   -> tableColumnItems(data, c.tableName(), context);
               case FieldClassification.CompositeColumnReference c
                                                            -> tableColumnItems(data, c.tableName(), context);
               case FieldClassification.InputUnbound ignored  -> List.<CompletionItem>of();
               case FieldClassification.Unclassified ignored  -> List.<CompletionItem>of();
               default -> null;
           };
           if (classified != null) {
               return mergeWithSigil(sigilItems, classified);
           }
       }
   }
   ```

   The `default -> null` sentinel falls through to the existing backing-driven dispatch (today's `switch (backing)` block); the typed arms return a final list. Arms that match return list values get merged with `sigilItems` the same way the existing tail merges (extract a `mergeWithSigil(sigil, rest)` helper if it tightens the code; otherwise inline).

   `InputUnbound` and `Unclassified` returning empty mirrors R224's silence: the runtime validator already names the right candidate set in its prose, so offering candidates from a table the classifier could not pin would lead the user away from the fix.

   `Column` / `CompositeColumn` are not strictly necessary to fix the reported bug (the enclosing-type's backing produces the same table for those arms today), but covering them keeps the projection's `tableName()` as the single source of truth for column-binding arms and is consistent with R224's `validateFieldMember` switch.

3. The existing backing-driven switch (lines 94-101) stays verbatim as the fall-through. Snapshot-uncertainty rule from R224 carries: when no classification is on file, the LSP behaves exactly as before.

4. Carry the same `@DependsOnClassifierCheck(key = "field-classification-payload-faithful", reliesOn = "...")` annotation R224 attached on `Diagnostics.validateFieldMember`. The two arms now share the same load-bearing dependency on `CatalogBuilder.terminalTableName`; the annotation pair lets the dependency surface in the cross-reference grep.

The fix is LSP-only. The classifier already projects the terminal table; the runtime resolver (`ServiceCatalog.resolveColumnForReference`) already walks the path. No generator-side changes.

## Tests

`graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/FieldCompletionsTest.java` parallels `DiagnosticsTest`. Add two regression cases mirroring R224's `{input,output}TableWithReferencePathValidatesAgainstTerminalTable`:

- **`inputTableWithReferencePathCompletesTerminalTableColumns`.** Schema fixture:

  ```graphql
  input FilmInput @table(name: "FILM") {
      languageName: String @field(name: "") @reference(path: [{table: "LANGUAGE"}])
  }
  ```

  Snapshot fixture: `LspSchemaSnapshot.Built.Current` with `FilmInput` typed as `TableBacking("FILM")` in `typesByName`, and `fieldClassificationsByCoord` populated with `"FilmInput.languageName" -> ColumnReference("LANGUAGE", "NAME", List.of())`. Catalog fixture supplies both `FILM` and `LANGUAGE` tables, the latter with a `NAME` column. Assert the completion labels contain `NAME` (and the other `LANGUAGE` columns) and do **not** contain `FILM_ID` / `TITLE` (the enclosing table's columns).

- **`outputTableWithReferencePathCompletesTerminalTableColumns`.** Mirror on an output `type` declaration; same snapshot wiring but the classification arm is `ColumnReference` projected from `ChildField.ColumnReferenceField` (the projection arm covers both input and output uniformly).

Optionally a third case pins the silence-on-unresolved arm: a snapshot with `FieldClassification.Unclassified("synthetic test reason")` at the coordinate, assert the completion list is empty (no candidates from the enclosing table). Symmetric with `DiagnosticsTest.unresolvedReferencePathColumnSilentOnLspSide`.

The existing `recordBackingCompletionReturnsRecordComponents` and `pojoBackingCompletionReturnsBeanAccessors` tests (`FieldCompletionsTest.java:152`, `:176`) need a tweak: they currently rely on the fact that `fieldName` is null on input-side declarations (because `enclosingFieldDefinition` only matches output-side `field_definition` nodes), so the classification lookup is skipped and the backing-driven dispatch fires. After step 1 of the implementation switches to `enclosingFieldOrInputValueDefinition`, those tests start projecting a non-null fieldName; with no `fieldClassificationsByCoord` entry in their snapshot, the new arm sees an empty optional and falls through to the same backing-driven dispatch. The assertions stay the same; the test setup needs no changes. (Sanity-verify on first run.)

## Scope notes

- LSP-only. No runtime / classifier / validator changes; the classifier projection already carries the terminal table.
- `@node(keyColumns:)` (the other `CatalogColumnBinding` coordinate) is unaffected by construction: type-level directive, no enclosing field, classification lookup skipped, falls through to the enclosing type's backing as today. The `nodeKeyColumnsCompletionInsideListLiteralReturnsTableColumns` test (`FieldCompletionsTest.java:134`) is a regression guard that this stays true.
- The `Hovers` arm for `@field(name:)` (`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/hover/Hovers.java`) is a separate code path. If hover suffers the same enclosing-table mismatch, file a follow-up; do not fold it into this item.
- R152 (`lsp-nodetype-hover-column-scoping.md`) is a different surface (hover, `@nodeId` typeName) but the same family of "scope a column lookup to the right table" fixes; out of scope here.

## References

- `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/completions/FieldCompletions.java:63`, `:75`-`107` — the buggy emitter site.
- `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/diagnostics/Diagnostics.java:520`-`605` — R224's `validateFieldMember`, the template for the dispatch shape.
- `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/parsing/TypeContext.java:53`-`67` — `enclosingFieldOrInputValueDefinition`, the R224 helper covering input-side fields.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/CatalogBuilder.java:228`-`230`, `407`-`411` — `ColumnReference` / `CompositeColumnReference` terminal-table projection.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/LspSchemaSnapshot.java:105`-`107` — `fieldClassification(typeName, fieldName)` lookup.
- `graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/DiagnosticsTest.java:309`-`384` — R224's three regression cases, structural template for this item's tests.
- `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/parsing/LspVocabulary.java:773`-`774`, `:781`-`782` — the two `CatalogColumnBinding` registrations the spec scopes against.
