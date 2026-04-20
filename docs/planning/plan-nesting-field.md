# `ChildField.NestingField` emission

> **Status:** Draft
>
> Lift `ChildField.NestingField` out of `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`. Classify the nested type's fields at parse time against the outer parent's table, project them into the parent's `$fields` SELECT list via a recursive switch, and emit a per-field `TypeRuntimeWiring` for the nested type so every leaf resolves the same way it does at top level. First arm of roadmap item #8.

## Current state

`NestingField` classifies at the wrapper level (`FieldBuilder.classifyObjectReturnChildField`) but has no projection data — its nested fields never reach the model. `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` stubs the emission; `GraphitronSchemaValidator.validateNestingField` is empty. Existing coverage: `GraphitronSchemaBuilderTest.NestingFieldCase` (`PLAIN_OBJECT_TYPE`, `LIST_OF_PLAIN_OBJECT_TYPE`).

## Plan

Two commits.

### C1 — Classification + emission

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

Cycle guard: thread `Set<String> expandingTypes` through the recursion; return `UnclassifiedField` on self-reference with message "circular type reference detected while expanding '…'". Mirror the input-side precedent in `TypeBuilder.buildInputField` (`TypeBuilder.java:567–625`).

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
            // Stubbed leaves are rejected by C2's validator walk; this arm is
            // unreachable at build time. GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus
            // catches drift in the PROJECTED_LEAVES partition.
            default -> throw new AssertionError("unreachable: validator rejects " + f);
        }
    }
    code.add("        default -> { } // unhandled GraphQL fields\n");
    code.add("    }\n");
    code.add("}\n");
}
```

The top-level body becomes a single `emitSwitch(code, 0, fields, "sel", "table")` call. `tableVar` is the outer parent's alias at every depth — nesting is transparent, so a `TableField` at nested depth correlates via the outer parent's table the same way it does at top level.

**Emitter (wiring).** Two changes to `TypeFetcherGenerator`:

1. `buildWiringEntry` gains a `NestingField` arm: `CodeBlock.of("\n.dataFetcher($S, env -> env.getSource())", nf.name())`. Hands the outer `Record` to graphql-java, which invokes the nested type's `TypeRuntimeWiring` with that `Record` as the source.

2. For each nested plain-object type, emit an additional `TypeRuntimeWiring.newTypeWiring(nestedTypeName)` builder via the same `buildWiringEntry` walk used at top level. `ColumnField` produces `new ColumnFetcher<>(Tables.X.COL)`; `ColumnFetcher.get` is `((Record) source).get(column)` (`ColumnFetcherClassGenerator.java:56`) — identity-based on the typed `Field`, so `@field(name:)` remap flows through without SQL aliasing. `NodeIdField` needs the outer parent's `TableRef` threaded through for the `Tables.FILM.ID` qualification in the encoder call. Nested `NestingField` entries recurse and emit further `TypeRuntimeWiring`s.

**Aggregator.** The nested wirings are per-`NestingField`, not per-Fetchers-class, so `GraphitronWiringClassGenerator` cannot register them via the existing `<Fetchers>.wiring()` loop. Extend it with a second per-class method — `wiringsNested()` returning `List<TypeRuntimeWiring>` — and iterate in the aggregator: `for (var w : <Fetchers>.wiringsNested()) body.add("\n.type($L)", w);`. Classes with no nested types return `List.of()`. Mirror the `ConnectionWiring` extension pattern.

**Partition.** Move `NestingField` from `NOT_IMPLEMENTED_REASONS` into `PROJECTED_LEAVES`. The `generateTypeSpec` sealed-switch arm becomes `{ /* wiring: outer passthrough + TypeRuntimeWiring for nested type; SELECT projected via parent $fields */ }`.

**Pipeline test.** SDL with a `@table` parent and a plain-object nesting child classifies as a `NestingField` whose `nestedFields` is populated against the outer parent's table; `@field(name:)` on a nested scalar remaps to the correct column; the parent's `$fields` contains a nested switch keyed by the nesting field's name; the generated output includes a `TypeRuntimeWiring.newTypeWiring("<NestedType>")` with per-field `.dataFetcher(…)` entries.

### C2 — Validation + execution tests

**Validator.** `GraphitronSchemaValidator.validateNestingField` gains three checks:

- **`FieldWrapper.List` on `NestingField`.** Source-passthrough has no semantic for list nesting — one parent `Record` in, one list value out. Reject. `GraphitronSchemaBuilderTest.NestingFieldCase.LIST_OF_PLAIN_OBJECT_TYPE` needs its expected outcome flipped to a validation error.
- **Single-parent invariant.** Each plain-object nested type may be referenced from exactly one outer `@table` parent. Two parents with different tables would classify the same GraphQL type into two different `nestedFields` lists with different column bindings; graphql-java keys `TypeRuntimeWiring` by type name and last-write-wins would silently bind the wrong table's columns. Reject at build time; lift when a schema demands it with a defined semantic (likely renaming/namespacing the nested type per parent).
- **Walk `nestedFields`** and apply `validateVariantIsImplemented` to each. Any sealed leaf still in `NOT_IMPLEMENTED_REASONS` surfaces a build-time error at the nested field's location — same mechanism, same error shape, same behaviour as at top level. This is the single integration point that makes "as #8 arms land, nested support follows" hold.

**Classification test coverage** (`GraphitronSchemaBuilderTest`):

- Nested scalar resolves to a parent column (`Film.details.title` → `FILM.TITLE`).
- `@field(name:)` on a nested scalar remaps the column.
- Unmatched nested scalar → `UnclassifiedField` with the nested location.
- Nested field with a stubbed directive (e.g. `@reference`) classifies as the corresponding leaf and is surfaced by the validator walk, not rejected at classify time.
- Multi-level nesting produces a recursive `NestingField.nestedFields()` entry.
- Self-referential type → `UnclassifiedField` with the circular-reference message, no `StackOverflowError`.
- Two `@table` parents referencing the same nested type → single-parent-invariant validation error.

**Execution tests** (`graphitron-rewrite-test-spec`):

- **Scalar nesting.** `Film @table { details: FilmDetails } ; FilmDetails { title, releaseYear }`. Query `film { details { title releaseYear } }` → one SQL round-trip projecting `FILM.TITLE, FILM.RELEASE_YEAR` only; GraphQL response carries correct values.
- **`@field(name:)` remap at nested depth.** Nested scalar with `@field(name: "…")` returns the remapped column's value.
- **Nested `@table` navigation.** `Film.details.category: Category @table`. Confirms `TableField` inside `nestedFields` correlates against the outer parent's table (nesting is transparent to navigation).
- **Multi-level nesting.** `Film { details: FilmDetails } ; FilmDetails { meta: FilmMeta } ; FilmMeta { releaseYear }`. Recursive emitter + recursive wiring.
- **Null-parent short-circuit.** `film(id: <nonexistent>) { details { title } }` → `details: null`, no NPE.

Compile gate: `mvn compile -pl :graphitron-rewrite-test,:graphitron-rewrite-test-fixtures,:graphitron-rewrite-test-spec -Plocal-db`.

## Non-goals

- **Other arms of roadmap #8** — `ColumnReferenceField`, `NodeIdReferenceField`, `ComputedField`, `TableMethodField`, `ServiceRecordField`, `MultitableReferenceField` each get their own plan. Nested support for each falls out of this plan's classifier/validator/wiring reuse once the top-level arm lands. `NodeIdReferenceField` is additionally blocked on Platform-id (Active).
- **List-cardinality nesting** — rejected at validate time. Lift with a defined semantic.
- **Shared nested types** (same plain-object type referenced from multiple `@table` parents) — rejected at validate time. Lift with a namespacing semantic.
- **Nested type as `GraphitronType`** — intentionally skipped. Plain-object nested types have no Fetchers class and no type class; nested wiring is emitted from the outer parent's Fetchers class.
