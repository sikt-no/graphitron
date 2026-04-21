# `ChildField.NestingField` emission

> **Status:** Draft
>
> Lift `ChildField.NestingField` out of `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`. Classify the nested type's fields at parse time against the outer parent's table, project them into the parent's `$fields` SELECT list via a recursive switch, and emit a per-field `TypeRuntimeWiring` for the nested type so every leaf resolves the same way it does at top level. Supports sharing a nested type across multiple `@table` parents (e.g. reusable value types like `Money`) when columns exist on every parent with matching Java types. First arm of roadmap item #8.

## Current state

`NestingField` classifies at the wrapper level (`FieldBuilder.classifyObjectReturnChildField`) but has no projection data — its nested fields never reach the model. `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` stubs the emission; `GraphitronSchemaValidator.validateNestingField` is empty. Existing coverage: `GraphitronSchemaBuilderTest.NestingFieldCase` (`PLAIN_OBJECT_TYPE`, `LIST_OF_PLAIN_OBJECT_TYPE`).

## Why classify nested fields (vs. default property fetching)

A minimal alternative: source-passthrough fetcher on the outer, then rely on graphql-java's `PropertyDataFetcher` against the jOOQ `Record`. Rejected because:

1. **`@field(name:)` remap breaks silently.** `PropertyDataFetcher` resolves `title` via `getTitle()` / `get("title")` on the source. For a nested scalar declared `title: String @field(name: "original_title")`, the default fetcher still calls `getTitle()` and returns the wrong column's value with no error. Top-level `ColumnField` is immune: `ColumnFetcher<>(Tables.FILM.ORIGINAL_TITLE)` is identity-bound to the typed `Field`. Nested should inherit the same guarantee.
2. **Asymmetry with `$fields` projection.** Selection-aware `$fields` already needs the nested-field → column binding at parse time (otherwise we over-project). If we have that binding for SELECT, not reusing it on the fetcher side is the anomaly — classify once, use for both.
3. **Future arms.** `@reference(path:)`, `@computed`, nested `@table` navigation, `NodeIdField` — none of these resolve via property names. Once any lands at nested depth, classification is required anyway.

## Plan

Classification + emission and validation + tests form one logical unit: the emitter's recursion assumes the validator rejects stubbed leaves at nested depth (see the `default` arm below). Commit-seam within that unit is the implementer's call.

### Classification + emission

**Model.** Extend the record:

```java
record NestingField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef.TableBoundReturnType returnType,
    List<ChildField> nestedFields
) implements ChildField {}
```

`nestedFields` is the classified list of the nested type's fields, each resolved against the outer parent's table. Any sealed-leaf `ChildField` subtype can appear.

**Classifier.** In `FieldBuilder.classifyObjectReturnChildField`, when the `NestingField` arm fires, walk the nested `GraphQLObjectType`'s field definitions and delegate each to the existing `classifyChildFieldOnTableType(fieldDef, nestedTypeName, outerParentTableType)` (`FieldBuilder.java:1634`). Nested fields classify identically to top-level fields on the outer parent — `@field(name:)` remaps columns, `@reference(path:)` produces `ColumnReferenceField`, `@computed` produces `ComputedField`, nested plain-object types recurse, etc. `UnclassifiedField` returns flow through to the existing validator path.

When multiple `@table` parents reference the same nested type, each parent's traversal independently classifies `nestedFields` against that parent's `TableRef` and attaches the result to that parent's `NestingField` instance — no prepass is needed. The cross-parent compatibility assertion (see validator below) runs against these independently-classified lists; their shapes must match.

Cycle guard: thread `Set<String> expandingTypes` through the recursion; return `UnclassifiedField` on self-reference with message "circular type reference detected while expanding '…'". Mirror the input-side precedent in `TypeBuilder.buildInputField` (`TypeBuilder.java:567–625`). Implementer's choice whether this is a bare signature change (all call sites updated to pass `Set.of()`) or an overload that leaves non-nesting call sites untouched.

**Emitter (projection).** Refactor `TypeClassGenerator.build$FieldsMethod`'s inner switch-emission into a static helper taking a depth counter and a `List<ChildField>`. Suffix loop variables (`entry0`/`sf0`, `entry1`/`sf1`, …) to avoid JLS §14.4.2 block-scoped local-variable shadowing. The helper handles each projectable leaf the top-level switch already handles, plus a `NestingField` arm that recurses:

```java
private static void emitSwitch(CodeBlock.Builder code, int depth,
        List<ChildField> fields, String selExpr, String tableVar) {
    var entryVar = "entry" + depth;
    var sfVar = "sf" + depth;
    code.add("for ($T $L : $L.getFieldsGroupedByResultKey().entrySet()) {\n",
             entryType, entryVar, selExpr);
    code.add("    $T $L = $L.getValue().get(0);\n", SELECTED_FIELD, sfVar, entryVar);
    code.add("    switch ($L.getName()) {\n", sfVar);
    for (var f : fields) {
        switch (f) {
            case ChildField.ColumnField cf ->
                code.add("        case $S -> fields.add($L.$L);\n",
                         cf.name(), tableVar, cf.column().javaName());
            // ... PlatformIdField, NodeIdField, TableField, LookupTableField arms
            case ChildField.NestingField nf -> {
                code.add("        case $S -> {\n", nf.name());
                emitSwitch(code, depth + 1, nf.nestedFields(),
                           sfVar + ".getSelectionSet()", tableVar);
                code.add("        }\n");
            }
            // Stubbed leaves are rejected by the validator walk below; this arm is
            // unreachable at build time. GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus
            // catches drift in the PROJECTED_LEAVES partition.
            default -> throw new AssertionError("unreachable: validator rejects " + f);
        }
    }
    code.add("        default -> { } // unknown names already rejected by query validator\n");
    code.add("    }\n");
    code.add("}\n");
}
```

The top-level body becomes a single `emitSwitch(code, 0, fields, "sel", "table")` call. `tableVar` is the outer parent's alias at every depth — nesting is transparent, so a `TableField` at nested depth correlates via the outer parent's table the same way it does at top level.

**Emitter (wiring).** Two changes to `TypeFetcherGenerator`:

1. `buildWiringEntry` gains a `NestingField` arm: `CodeBlock.of("\n.dataFetcher($S, env -> env.getSource())", nf.name())`. Hands the outer `Record` to graphql-java, which invokes the nested type's `TypeRuntimeWiring` with that `Record` as the source.

2. For each nested plain-object type, emit an additional `TypeRuntimeWiring.newTypeWiring(nestedTypeName)` builder via the same `buildWiringEntry` walk used at top level. `ColumnField` produces `new ColumnFetcher<>(Tables.X.COL)`; `ColumnFetcher.get` is `((Record) source).get(column)` (`ColumnFetcherClassGenerator.java:56`) — identity-based on the typed `Field`, so `@field(name:)` remap flows through without SQL aliasing. `buildWiringEntry` already takes `parentTable` (see `TypeFetcherGenerator.java:1346`); nested emission passes the outer parent's `TableRef` in that arg, so `NodeIdField`'s `Tables.FILM.ID` qualification in the encoder call falls out with no signature change. Nested `NestingField` entries recurse and emit further `TypeRuntimeWiring`s.

**Aggregator.** Each nested type is wired exactly once regardless of how many outer parents reference it, so nested wiring belongs at the global aggregator rather than on any parent's Fetchers class. Extend `GraphitronWiringClassGenerator.generate(...)` with a `List<NestedTypeWiring>` channel mirroring the existing `connectionWirings` channel; each `NestedTypeWiring` carries the nested type name, the representative parent's `TableRef`, and the classified `nestedFields`. The aggregator emits one `.type(TypeRuntimeWiring.newTypeWiring(ntw.typeName()) … )` block per entry, calling a static helper exposed from `TypeFetcherGenerator` (refactor `buildWiringEntry` into a package-visible static so the aggregator can reuse it without duplicating per-leaf logic). `ColumnFetcher`'s `((Record) source).get(column)` (`ColumnFetcherClassGenerator.java:56`) relies on jOOQ's name-based fallback in `Record.get(Field)` — representative parent's `Tables.FILM.TITLE` works against any incoming `Record` whose metadata carries a compatibly-typed `TITLE` column, which the parent-compatibility check has guaranteed. Lock the fallback behaviour in with an execution test.

**Partition.** Move `NestingField` from `NOT_IMPLEMENTED_REASONS` into `PROJECTED_LEAVES`. `NestingField` doesn't itself contribute a column — it drives projection by recursion into `nestedFields` — so `PROJECTED_LEAVES` is the partition for "contributes to the parent's SELECT list," not "adds a single column." The `generateTypeSpec` sealed-switch arm becomes `{ /* wiring: outer passthrough + TypeRuntimeWiring for nested type; SELECT projected via parent $fields */ }`.

**Pipeline test.** SDL with a `@table` parent and a plain-object nesting child classifies as a `NestingField` whose `nestedFields` is populated against the outer parent's table; `@field(name:)` on a nested scalar produces a nested `ColumnField` bound to the remapped column; the generated `GraphitronWiring.build()` body contains a `TypeRuntimeWiring.newTypeWiring("<NestedType>")` block with per-field `.dataFetcher(…)` entries. Assertions target the classified model and `TypeSpec` surface — body contents are covered by compilation + execution.

### Validation + execution tests

**Validator.** `GraphitronSchemaValidator.validateNestingField` gains three checks. The schema-level parent-compatibility check runs first; per-field checks short-circuit for nested types that already failed it, so a single incompatible field doesn't produce duplicate errors per parent.

- **`FieldWrapper.List` on `NestingField`.** Source-passthrough has no semantic for list nesting — one parent `Record` in, one list value out. Reject. `GraphitronSchemaBuilderTest.NestingFieldCase.LIST_OF_PLAIN_OBJECT_TYPE` needs its expected outcome flipped to a validation error.
- **Parent compatibility.** A plain-object nested type may be referenced from any number of outer `@table` parents. For each nested type, pick a representative parent (first in deterministic ordering), classify `nestedFields` against it, then re-classify against every other parent and assert each ColumnField's `columnName` + `ColumnRef.columnClass` (Java type) matches the representative's, field-by-field. Mismatches reject with a pointed error: "nested field `FilmDetails.releaseYear` requires column `RELEASE_YEAR` on `ADVERTISEMENT`, not found" / "… resolves to `Short` on `FILM` but `Integer` on `ADVERTISEMENT`". Non-column leaves (ColumnReferenceField, ComputedField, …) reject at nested depth via the existing stubbed-variant check; multi-parent support for those lands with their own roadmap arms.
- **Recursively walk `nestedFields`** and apply `validateVariantIsImplemented` to each leaf, descending into nested `NestingField` entries. Any sealed leaf still in `NOT_IMPLEMENTED_REASONS` surfaces a build-time error at the nested field's location — same mechanism, same error shape, same behaviour as at top level. This is the single integration point that makes "as #8 arms land, nested support follows" hold, and is what the emitter's `default -> throw new AssertionError(...)` relies on for unreachability.

**Classification test coverage** (`GraphitronSchemaBuilderTest`):

- Nested scalar resolves to a parent column (`Film.details.title` → `FILM.TITLE`).
- `@field(name:)` on a nested scalar remaps the column.
- Unmatched nested scalar → `UnclassifiedField` with the nested location.
- Nested field with a stubbed directive (e.g. `@reference`) classifies as the corresponding leaf and is surfaced by the validator walk, not rejected at classify time.
- Multi-level nesting produces a recursive `NestingField.nestedFields()` entry.
- Self-referential type → `UnclassifiedField` with the circular-reference message, no `StackOverflowError`.
- Two `@table` parents referencing the same nested type, columns compatible → single classified `NestingField` instance per parent, both with matching `nestedFields` shape.
- Two `@table` parents, nested `ColumnField` exists on one but not the other → parent-compatibility validation error naming the failing parent + column.
- Two `@table` parents, nested `ColumnField` column type differs (e.g. `Short` vs `Integer`) → parent-compatibility validation error naming the divergent Java types.

**Execution tests** (`graphitron-rewrite-test-spec`):

- **Scalar nesting.** `Film @table { details: FilmDetails } ; FilmDetails { title, releaseYear }`. Query `film { details { title releaseYear } }` → one SQL round-trip projecting `FILM.TITLE, FILM.RELEASE_YEAR` only; GraphQL response carries correct values.
- **`@field(name:)` remap at nested depth.** Nested scalar with `@field(name: "…")` returns the remapped column's value.
- **Nested `@table` navigation.** `Film.details.category: Category @table`. Confirms `TableField` inside `nestedFields` correlates against the outer parent's table (nesting is transparent to navigation).
- **Multi-level nesting.** `Film { details: FilmDetails } ; FilmDetails { meta: FilmMeta } ; FilmMeta { releaseYear }`. Recursive emitter + recursive wiring.
- **Null-parent short-circuit.** `film(id: <nonexistent>) { details { title } }` → `details: null`, no NPE.
- **Shared nested type across parents.** Two `@table` parents (e.g. `Film @table` and `Advertisement @table`, both with a `title` column) each declare `details: FilmDetails`. Query `{ film { details { title } } advertisement { details { title } } }` returns each parent's own `TITLE` value via the shared `TypeRuntimeWiring("FilmDetails")`. Locks jOOQ's name-based `Record.get(Field)` fallback as the mechanism.

Compile gate: `mvn compile -pl :graphitron-rewrite-test,:graphitron-rewrite-test-fixtures,:graphitron-rewrite-test-spec -Plocal-db`.

## Non-goals

- **Other arms of roadmap #8** — `ColumnReferenceField`, `NodeIdReferenceField`, `ComputedField`, `TableMethodField`, `ServiceRecordField`, `MultitableReferenceField` each get their own plan. Nested support for each falls out of this plan's classifier/validator/wiring reuse once the top-level arm lands. `NodeIdReferenceField` is additionally blocked on Platform-id (Active).
- **List-cardinality nesting** — rejected at validate time. Lift with a defined semantic.
- **Non-column leaves in shared nested types.** Multi-parent support covers `ColumnField` (and any future leaf whose resolution is column-name + Java-type only). Leaves whose resolution depends on per-parent join paths or FK metadata (`ColumnReferenceField`, `NodeIdField` with parent-specific keys, nested `@table` navigation) are rejected at validate time when their nesting type is shared across parents — they'd need their shape validated across parents, and the shape check is leaf-specific. Lands with the corresponding roadmap #8 arm.
- **Nested type as `GraphitronType`** — intentionally skipped. Plain-object nested types have no Fetchers class and no type class; nested wiring is emitted once globally via `GraphitronWiringClassGenerator`, not under any outer parent's Fetchers class.
