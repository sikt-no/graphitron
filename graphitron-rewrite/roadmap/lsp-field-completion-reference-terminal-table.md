---
id: R233
title: Lift LSP @field(name:) column arms onto a sealed FieldClassification dispatch (closes the completion-arm @reference path bug)
status: Spec
bucket: bug
theme: lsp
depends-on: []
created: 2026-05-22
last-updated: 2026-05-23
---

# Lift LSP @field(name:) column arms onto a sealed FieldClassification dispatch

## Symptom

R224 fixed `Diagnostics.validateFieldMember` so `@field(name:)` validation on a `@reference(path:)` field resolves the column against the path's terminal table, not the enclosing type's `@table`. Two sibling LSP arms at the same SDL coordinate were not touched:

* `FieldCompletions.completionsFor` — the completion dropdown at `@field(name: "<cursor>")` lists the enclosing type's columns.
* `Hovers.columnHover` — hovering over a column literal under a `@reference` path field resolves the column on the enclosing type's table; the metadata pop-up either renders the wrong column or silently fails to find one.

Reproducer (the input-type case carried over from R224):

```graphql
input MaskinbrukerApiTilgangerFilterInputV2 @table(name: "WSBRUKER_APITILGANG") {
    apier: [String!] @field(name: "<cursor>") @reference(path: [{table: "API"}])
    maskinbrukere: [String!] @field(name: "<cursor>") @reference(path: [{table: "WSBRUKER"}])
}
```

After R224, the diagnostic is correct; the completion dropdown and hover at the same cursor remain wrong. In the same editor session the user observes: completion suggests `WSBRUKER_APITILGANG` columns (wrong), validator stays silent on the user's manual entry of `NAVN` (correct), hovering over `NAVN` shows no doc-string (silent failure: lookup ran against `WSBRUKER_APITILGANG`, where `NAVN` does not exist). Three arms, three behaviours, two of them wrong.

## Trace

The three buggy arms all read `built.typesByName().get(typeName)` and dispatch on the enclosing-type backing without consulting `built.fieldClassification(typeName, fieldName)`:

* `FieldCompletions.completionsFor` (`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/completions/FieldCompletions.java:75`-`107`) — line 92.
* `Hovers.columnHover` (`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/hover/Hovers.java:262`-`284`) — line 272.

R224 has the precedent in `Diagnostics.validateFieldMember` (`graphitron-lsp/.../diagnostics/Diagnostics.java:520`-`605`). Its dispatch uses a `switch (classification.get())` block with explicit arms for `Column` / `ColumnReference` / `CompositeColumn` / `CompositeColumnReference` (route to `validateColumnOnTable(catalog, c.tableName(), ...)`), explicit arms for `InputUnbound` / `Unclassified` (return silently), and a `default -> /* fall through */` sentinel for every other permit, routing back to the enclosing-type-backing switch.

The classifier projection R224 introduced is `LspSchemaSnapshot.Built.fieldClassification(typeName, fieldName)`; for `@reference` path fields it carries the terminal table on `ColumnReference.tableName()` / `CompositeColumnReference.tableName()` (`CatalogBuilder.projectFieldClassification` at `:228`-`230`, `:407`-`411`, via `terminalTableName(joinPath)`). The load-bearing key `field-classification-payload-faithful` already enumerates the three LSP arms in its producer-side description (`CatalogBuilder.java:110`: "*FieldCompletions / Diagnostics / Hovers*"); R224 wired one of the three, R233 wires the other two.

A secondary trace detail at line 63 of `FieldCompletions`: today it calls `TypeContext.enclosingFieldDefinition`, which only walks output-side `field_definition` AST nodes. R224 added `enclosingFieldOrInputValueDefinition` (`TypeContext.java:53`) covering input-side `input_value_definition` too; both new consumer sites need the broader helper.

## Design

### The shape decision: sealed dispatch projection vs. duplicated sentinel

R224's `default -> /* fall through */` shape works but defeats sealed-exhaustiveness on the 30-permit `FieldClassification` at every consumer site. With one consumer (R224) the fragility cost was modest; with three consumers it compounds. When a future R-item adds a column-bearing permit (a new `@reference`-shaped composite arm, say), none of the three LSP switches will fail to compile — the new permit silently routes through `default` and the @field(name:) arm at that coordinate goes back to lying about the table, which is the exact bug class R224 was filed to fix.

Three shapes considered:

1. **Mirror R224's `default ->` shape at the two new sites** (the narrow path). Replicates the fragility three times. No code churn at R224's site.
2. **Make every consumer's switch exhaustive over all 30 permits** (the brute-force fix). Compiler-checked but the three near-identical 30-arm switches drift independently; a new permit forces three coordinated edits in any item that adds it.
3. **Lift the dispatch onto a sealed projection on `FieldClassification`** (the structural fix). One exhaustive switch at the producer side; three trivial 3-arm switches at the consumer sites. New permits force exactly one edit, in the projection, and the LSP arms inherit the decision.

This spec recommends shape 3 and refactors `Diagnostics.validateFieldMember` (R224's site) onto the lifted projection in the same commit so the three LSP arms stay symmetric. Shape 1 stays available as a Spec → Ready reviewer redirect.

### The lifted projection

A new sealed result on `FieldClassification`:

```java
sealed interface LspColumnDispatch
    permits LspColumnDispatch.Resolve, LspColumnDispatch.Silent, LspColumnDispatch.FallThrough {

    record Resolve(String tableName) implements LspColumnDispatch {}
    record Silent() implements LspColumnDispatch {}
    record FallThrough() implements LspColumnDispatch {}
}

default LspColumnDispatch lspColumnDispatch() {
    return switch (this) {
        case Column c                       -> new LspColumnDispatch.Resolve(c.tableName());
        case ColumnReference c              -> new LspColumnDispatch.Resolve(c.tableName());
        case CompositeColumn c              -> new LspColumnDispatch.Resolve(c.tableName());
        case CompositeColumnReference c     -> new LspColumnDispatch.Resolve(c.tableName());
        case InputUnbound ignored           -> new LspColumnDispatch.Silent();
        case Unclassified ignored           -> new LspColumnDispatch.Silent();
        case ParticipantCrossTable ignored,
             TableTarget ignored,
             RecordTableTarget ignored,
             TableMethod ignored,
             TableInterface ignored,
             Polymorphic ignored,
             Nesting ignored,
             Constructor ignored,
             ServiceBacked ignored,
             RecordOrProperty ignored,
             Computed ignored,
             Errors ignored,
             SingleRecordTable ignored,
             SingleRecordIdFromReturning ignored,
             SingleRecordTableFromReturning ignored,
             QueryTable ignored,
             QueryTableMethod ignored,
             QueryNode ignored,
             QueryTableInterface ignored,
             QueryPolymorphic ignored,
             QueryService ignored,
             DmlMutation ignored,
             MutationService ignored,
             DmlRecord ignored               -> new LspColumnDispatch.FallThrough();
    };
}
```

Exhaustive over the sealed permit list — no `default`. A new permit added later fails this switch to compile; the implementer chooses one of three semantically-meaningful arms before merging, in one place. The three consumer arms (`Resolve` / `Silent` / `FallThrough`) carry the LSP-shaped semantics R224's `validateFieldMember` already coded by hand.

Consumer-side dispatch shrinks to a 3-arm exhaustive switch:

```java
switch (clf.lspColumnDispatch()) {
    case LspColumnDispatch.Resolve(var tableName) -> /* dispatch on terminal table */;
    case LspColumnDispatch.Silent ignored         -> /* return empty / no-op */;
    case LspColumnDispatch.FallThrough ignored    -> /* fall through to backing dispatch */;
}
```

### Why this lift sits on `FieldClassification` and not in a utility class

The sealed root is the natural home: the dispatch decision is a function of the classification's variant identity. Pushing it into a static utility (`LspColumnArms.dispatchFor(FieldClassification c)`) keeps the same compiler-checked switch but loses the variant-method invocation form; consumers would have to import the utility and the sealed type stays mute about its LSP-arm semantics. Sealed roots already host similar dispatch defaults elsewhere in the model (see `JoinStep.targetTable()` in R232's spec for the pattern).

Naming: `lspColumnDispatch()` is preferred over `columnBoundTableName()` because the dispatch shape encodes three audience-specific arms (Resolve / Silent / FallThrough), not just "is there a table". The audience is the LSP's `@field(name:)`-shaped `CatalogColumnBinding` coordinates; the name commits to that audience so non-LSP consumers (any future runtime arm) don't accidentally route through the LSP-shaped silence semantics.

## Implementation

### 1. `FieldClassification.lspColumnDispatch()` lift

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/FieldClassification.java`

Add the `LspColumnDispatch` sealed nested type and the `lspColumnDispatch()` default method as in the Design section. The switch lists all 30 permits explicitly; no `default` arm. The `@LoadBearingClassifierCheck(key = "field-classification-payload-faithful", ...)` at `CatalogBuilder.java:114`-`124` is updated to reference the new method as the canonical consumer route (the description already names `FieldCompletions / Diagnostics / Hovers` as the LSP-arm audience; no rename needed).

### 2. Refactor `Diagnostics.validateFieldMember` onto `lspColumnDispatch()`

**File:** `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/diagnostics/Diagnostics.java`

R224's `switch (classification.get())` block at `:562`-`589` collapses to a 3-arm switch on the projection:

```java
if (fieldName != null) {
    var classification = built.fieldClassification(typeName.get(), fieldName);
    if (classification.isPresent()) {
        switch (classification.get().lspColumnDispatch()) {
            case FieldClassification.LspColumnDispatch.Resolve(var tableName) -> {
                validateColumnOnTable(catalog, tableName, memberName, valueNode, file, out);
                return;
            }
            case FieldClassification.LspColumnDispatch.Silent ignored -> { return; }
            case FieldClassification.LspColumnDispatch.FallThrough ignored -> { /* fall through */ }
        }
    }
}
// existing backing-driven switch unchanged
```

The R224 `@DependsOnClassifierCheck(key = "field-classification-payload-faithful", ...)` annotation on `validateFieldMember` stays; its `reliesOn` text updates to point at the lifted method (`FieldClassification.lspColumnDispatch()` as the load-bearing consumer route, instead of inlined per-arm dispatch). The other annotations on the method (the `java-record-type-backs-record-class` annotation) are untouched.

Behaviour is identical to R224's just-shipped code: the same four `Resolve`-equivalent arms route to `validateColumnOnTable`, `InputUnbound` and `Unclassified` are silent, every other permit falls through. The three regression tests R224 added (`DiagnosticsTest.{inputTableWithReferencePathValidatesAgainstTerminalTable, outputTableWithReferencePathValidatesAgainstTerminalTable, unresolvedReferencePathColumnSilentOnLspSide}`) stay green without modification.

### 3. Apply the dispatch to `FieldCompletions.completionsFor`

**File:** `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/completions/FieldCompletions.java`

a. Line 63: switch `TypeContext.enclosingFieldDefinition(directive.outer())` to `TypeContext.enclosingFieldOrInputValueDefinition(directive.outer())`. The `@node(keyColumns:)` site (the other `CatalogColumnBinding` registration at `LspVocabulary.java:781`-`782`) sits on a type-level directive, so the enclosing-field walk returns empty there and the classification lookup is skipped; no behavioural change for `@node`.

b. Inside `completionsFor`, between the `$source`-sigil block (lines 88-91) and the backing-driven switch (line 92), insert the 3-arm dispatch gated on `fieldName != null`:

```java
if (fieldName != null) {
    var classification = built.fieldClassification(typeName, fieldName);
    if (classification.isPresent()) {
        var dispatched = switch (classification.get().lspColumnDispatch()) {
            case FieldClassification.LspColumnDispatch.Resolve(var tableName)
                -> Optional.of(tableColumnItems(data, tableName, context));
            case FieldClassification.LspColumnDispatch.Silent ignored
                -> Optional.<List<CompletionItem>>of(List.of());
            case FieldClassification.LspColumnDispatch.FallThrough ignored
                -> Optional.<List<CompletionItem>>empty();
        };
        if (dispatched.isPresent()) {
            return mergeWithSigil(sigilItems, dispatched.get());
        }
    }
}
// existing backing-driven switch unchanged
```

`Optional.empty()` on the `FallThrough` arm carries the "fall through to backing-driven dispatch" signal; the post-switch `dispatched.isPresent()` guard is the same exhaustive-then-fall-through shape R232's spec calls `WithTarget` / `ConditionJoin` dispatch (sealed switch returns a result; consumer reads it; exhaustive at every level). No null sentinel.

Extract a small `mergeWithSigil(List<CompletionItem> sigilItems, List<CompletionItem> rest)` helper so the merge code at the tail of the existing function and the new arm share one definition.

c. Attach `@DependsOnClassifierCheck(key = "field-classification-payload-faithful", reliesOn = "...")` on `completionsFor` referencing the lifted projection. The `reliesOn` body names `FieldClassification.lspColumnDispatch` as the route, mirroring step 2.

### 4. Apply the dispatch to `Hovers.columnHover`

**File:** `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/hover/Hovers.java`

Symmetric edit at `:262`-`284`. The `fieldName` lookup uses `TypeContext.enclosingFieldOrInputValueDefinition`; the 3-arm switch ahead of the backing-driven dispatch returns `Optional<Hover>` directly:

```java
if (fieldName != null) {
    var classification = built.fieldClassification(typeName.get(), fieldName);
    if (classification.isPresent()) {
        var hover = switch (classification.get().lspColumnDispatch()) {
            case FieldClassification.LspColumnDispatch.Resolve(var tableName)
                -> Optional.of(tableColumnHover(catalog, tableName, memberName, file, valueNode));
            case FieldClassification.LspColumnDispatch.Silent ignored
                -> Optional.<Optional<Hover>>of(Optional.empty());
            case FieldClassification.LspColumnDispatch.FallThrough ignored
                -> Optional.<Optional<Hover>>empty();
        };
        if (hover.isPresent()) {
            return hover.get();
        }
    }
}
// existing backing-driven switch unchanged
```

(The double-`Optional` carries "we have a decision, the decision is empty hover" vs. "no decision, fall through to backing dispatch"; same shape as step 3.) Attach the same `@DependsOnClassifierCheck` annotation.

### 5. Annotation hygiene

`field-classification-payload-faithful` now has five consumer sites: the three pre-existing (`Diagnostics.validateFieldMember`, `InlayHints.compute`, `DeclarationHovers.compute`) plus the two this commit adds (`FieldCompletions.completionsFor`, `Hovers.columnHover`). `LoadBearingGuaranteeAuditTest` auto-discovers via the key; no manual registration needed beyond the per-site `@DependsOnClassifierCheck`.

## Tests

### Test-tier note

LSP arms are unit-tier by substrate. The synthetic `LspSchemaSnapshot.Built.Current` fixture is the primary behavioural assertion shape for `@field(name:)` LSP arms; there's no SDL → TypeSpec pipeline to assert against (the SDL → projection step is covered by `LspSchemaSnapshotProjectionTest`, separately from the consumer-side LSP arms). This mirrors R224's tier choice.

### New regression coverage

#### FieldCompletionsTest

Two cases parallel to R224's `DiagnosticsTest` regressions:

* **`inputTableWithReferencePathCompletesTerminalTableColumns`** — schema fixture:

  ```graphql
  input FilmInput @table(name: "FILM") {
      languageName: String @field(name: "") @reference(path: [{table: "LANGUAGE"}])
  }
  ```

  Snapshot: `LspSchemaSnapshot.Built.Current` with `FilmInput` typed as `TableBacking("FILM")`, `fieldClassificationsByCoord` populated with `"FilmInput.languageName" -> ColumnReference("LANGUAGE", "NAME", List.of())`. Catalog fixture supplies both `FILM` and `LANGUAGE`, the latter with a `NAME` column. Assert: completion labels contain `NAME` and the other `LANGUAGE` columns; do NOT contain `FILM_ID` / `TITLE`.

* **`outputTableWithReferencePathCompletesTerminalTableColumns`** — mirror on an output `type` declaration.

* **`unresolvedReferencePathCompletionSilentOnLspSide`** (third case, pins the `Silent` arm) — same fixture as the input case but with `FieldClassification.Unclassified("synthetic test reason")` at the coordinate. Assert: completion list is empty (no candidates leak from the enclosing-type backing).

#### HoversTest

Three symmetric cases:

* **`inputTableWithReferencePathHoversOnTerminalTableColumn`** — same input fixture, hover over the `"NAME"` literal, assert the hover contains the `LANGUAGE.NAME` column metadata (not `FILM`).
* **`outputTableWithReferencePathHoversOnTerminalTableColumn`** — output-side mirror.
* **`unresolvedReferencePathHoverSilentOnLspSide`** — `Unclassified` classification, hover returns empty.

#### DiagnosticsTest

R224's three regression cases stay green without modification. Add one extension assertion: after the refactor, the dispatch routes through `lspColumnDispatch()`, so a test that probes the projection directly (`LspColumnDispatchProjectionTest`) guards the producer-side switch — see below.

#### LspColumnDispatchProjectionTest (new)

Pipeline-tier producer-side projection test under `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/catalog/`. Drives the full classifier on a small synthetic schema (one `@reference` path field, one plain `@field` field, one `Nesting` field, one `InputUnbound` field) and asserts:

* `ColumnReference / Column / CompositeColumn / CompositeColumnReference` arms produce `LspColumnDispatch.Resolve(<table>)`.
* `InputUnbound / Unclassified` arms produce `LspColumnDispatch.Silent`.
* `Nesting / Constructor / ServiceBacked / DmlMutation` (representative non-column arms) produce `LspColumnDispatch.FallThrough`.

This is the load-bearing meta-assertion: it covers the producer-side decision in one place so the three LSP consumer-side regressions can stay focused on their own surface.

### Pre-existing-test impact

Two `FieldCompletionsTest` cases (`recordBackingCompletionReturnsRecordComponents`, `pojoBackingCompletionReturnsBeanAccessors`) project a non-null `fieldName` after step 3a's `enclosingFieldDefinition → enclosingFieldOrInputValueDefinition` switch. Their snapshots populate only `typesByName` (no `fieldClassificationsByCoord`), so `built.fieldClassification(typeName, fieldName)` returns `Optional.empty()`, the new arm is skipped, and the existing backing-driven dispatch fires verbatim. Assertions stay byte-identical; no fixture edit needed. (Not "sanity-verify on first run" — the empty-map fall-through is the load-bearing argument and the implementer asserts it in the implementation commit.)

The analogous `HoversTest` cases (record-backed / pojo-backed hovers, if present) follow the same empty-map fall-through argument. The `@node(keyColumns:)` test `FieldCompletionsTest.nodeKeyColumnsCompletionInsideListLiteralReturnsTableColumns` (`:134`) regression-guards the type-level-directive case: enclosing field walk returns empty, fieldName null, new arm skipped, existing dispatch fires.

## Scope notes

* The fix is LSP-only on the load-bearing surfaces. No runtime / classifier behaviour changes. The classifier projection was already correct; this lift just routes three consumer arms through it cleanly.
* **Runtime sibling: `BuildContext.classifyInputFieldInternal` candidate hint at `BuildContext.java:1673`.** The nested-input "Did you mean" hint builds its candidate list from `catalog.columnSqlNamesOf(resolvedTable.tableName())` where `resolvedTable` is the path-*origin* enclosing input's `@table`, not the path's terminal table. For an unreachable column under a `@reference`, the suggestions are drawn from the wrong table and lead the user away from the fix. This is a different surface (compile-time validator error message vs. LSP arm) and a different consumer audience (the user sees it once at build time, not interactively per keystroke), so it does not fold into this LSP-scoped item. Filed as a sibling Backlog stub in the same session — `validator-reference-candidate-hint-terminal-table.md` (or equivalent). The implementer adds the cross-link before opening this item's PR.
* R152 (`lsp-nodetype-hover-column-scoping.md`) is a different LSP coordinate (`@nodeId(typeName:)` hover) in the same family of "scope a column lookup to the right table" fixes; out of scope here.

## References

* `graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/FieldClassification.java` — sealed root that gains `lspColumnDispatch()`.
* `graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/CatalogBuilder.java:114`-`124` — `field-classification-payload-faithful` producer annotation.
* `graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/LspSchemaSnapshot.java:105`-`107` — `fieldClassification(typeName, fieldName)` lookup.
* `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/diagnostics/Diagnostics.java:520`-`605` — R224's site, refactored in step 2.
* `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/completions/FieldCompletions.java:63`, `:75`-`107` — the completion arm, step 3.
* `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/hover/Hovers.java:262`-`284` — the hover arm, step 4.
* `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/parsing/TypeContext.java:53`-`67` — `enclosingFieldOrInputValueDefinition`.
* `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/parsing/LspVocabulary.java:773`-`774`, `:781`-`782` — the two `CatalogColumnBinding` registrations.
* `graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/DiagnosticsTest.java:309`-`384` — R224's regression cases, structural template.
* `graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java:1665`-`1677` — the runtime-side candidate-hint sibling (out of scope, sibling Backlog stub).

## Forks to surface for the Spec → Ready reviewer

1. **Adopt the sealed `LspColumnDispatch` lift, or stay narrow on R224's `default ->` precedent?** This spec recommends the lift (rationale: three consumer arms, compounding fragility, the projection's exhaustive switch is the load-bearing producer the consumers all read off). Narrow alternative: replicate R224's per-site `default ->` shape at the two new sites, leave R224's site untouched, accept the compounded fragility, file a follow-up to lift later. Spec → Ready reviewer redirect: the narrow option is mechanically smaller and ships strictly less code.
2. **Fold `Hovers.columnHover` into this commit, or split into a sibling item?** This spec folds it in (one annotation site, one projection, three regressions; cost of a third tiny item is real). Split alternative: R234 in same session, identical shape, ships after R233. Spec → Ready reviewer redirect: split is cleaner-attributed in commit history; fold is fewer round-trips.
3. **Refactor R224's `validateFieldMember` onto the lifted projection in the same commit, or leave it on the per-arm `switch` shape it shipped with?** This spec refactors (rationale: keep the three LSP arms symmetric; the lift is pointless if R224's site stays asymmetric and a new permit forces edits in two flavors of switch). No-refactor alternative: lift the projection, route the two new sites through it, leave R224's site as-is; cost is one site permanently inconsistent with two others. Spec → Ready reviewer redirect: probably not worth taking; the asymmetry is the principal cost.
