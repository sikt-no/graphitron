# `ChildField.NestingField` emission

> **Status:** Draft
>
> Lift `ChildField.NestingField` out of `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` by (a) resolving the nested projection at build time during classification and (b) emitting a recursive nested switch inline in the parent type's `$fields` method. Wiring is a source-passthrough data fetcher (same pattern as `ConstructorField`). First arm of roadmap item #8 ("Non-table / scalar / reference child leaves").

## Current state

- `ChildField.NestingField` (`model/ChildField.java`) carries the parent's `TableRef` verbatim in its `ReturnTypeRef.TableBoundReturnType`. It is deliberately excluded from `TableTargetField` because it does not navigate.
- Classification is complete for the wrapper type itself: `FieldBuilder.classifyObjectReturnChildField` fires the `NestingField` arm when the child's return type is a `GraphQLObjectType` with no `GraphitronType` entry (no `@table`, no `@record`). Covered by `GraphitronSchemaBuilderTest.NestingFieldCase` — `PLAIN_OBJECT_TYPE` (`Film.details: FilmDetails`) and `LIST_OF_PLAIN_OBJECT_TYPE` (`Film.tags: [Tag!]!`).
- The *contents* of the nested type are not classified. `NestingField` currently has no projection data — its fields never reach the model.
- `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` routes `NestingField` to `stub(f)`; the sealed-switch arm in `generateTypeSpec` dispatches to the same stub.
- `GraphitronSchemaValidator.validateNestingField` is empty; users see the generic stubbed-variant error from `validateVariantIsImplemented`.

## Design

Nested scalars on a plain (non-`@table`, non-`@record`) object type resolve against the parent's jOOQ record:

- **Column-name mapping happens at classification time.** The builder walks the nested `GraphQLObjectType`'s fields and resolves each scalar to a `ColumnRef` on the parent's table using the same naming convention that drives `ColumnField`. No runtime name lookup.
- **Emission is a recursive nested switch** inlined into the parent type's existing `$fields(sel, table, env)` method. One switch per nesting level, keyed by GraphQL field name; arms for scalars call `fields.add(table.COLUMN)`; arms for nested `NestingField`s open an inner `for (var sub : sf.getSelectionSet().getImmediateFields())` + nested switch.
- **Wiring is passthrough.** `env -> env.getSource()` hands the parent record to graphql-java, which resolves each nested scalar via the default `PropertyDataFetcher` against the jOOQ record's getters (`getTitle()`, `getReleaseYear()`, …). No fetchers class is emitted for the nested type.

Design rationale — this keeps `NestingField` consistent with the rewrite's core principles:

- *Classification belongs at the parse boundary* — nested columns are resolved once by `FieldBuilder`, not re-mapped at runtime.
- *Generators receive a model that is already in terms of "what to emit"* — the emitter switches on a pre-resolved tree.
- *Narrow component types* — the projection carries `ColumnField` and `NestingField` directly; no new parallel type.

## Plan

Two commits.

### C1 — Classification + emission

**Model.** Extend `ChildField.NestingField` with two components:

```java
record NestingField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef.TableBoundReturnType returnType,
    List<ChildField.ColumnField> columnFields,
    List<ChildField.NestingField> nestingFields
) implements ChildField {}
```

`columnFields` are nested scalars resolved to columns on the parent's table. `nestingFields` are recursive inner nesting children (multi-level). Both are fully populated at classification time.

**Classifier.** In `FieldBuilder.classifyObjectReturnChildField`, when the `NestingField` arm fires, walk the nested `GraphQLObjectType`'s field definitions. All error outcomes produce a `GraphitronField.UnclassifiedField` return value with the nested field's location (the existing output-side convention used by the surrounding `classifyObjectReturnChildField` arms). For each field:

- Scalar field → resolve to a `ColumnRef` on the parent's `TableRef` via the existing naming convention (camelCase → `UPPER_SNAKE_CASE`). Unmatched names → `UnclassifiedField`. Directives on the nested field → `UnclassifiedField`.
- Object-type field that is itself a plain object (no `@table`, no `@record`) → recurse, producing a nested `NestingField` whose `returnType.table()` is still the outermost parent's table.
- Anything else (another `@table` type, a `@record` type, an interface, a union, a list) → `UnclassifiedField` naming the unsupported shape.

Cycle guard: GraphQL schemas can express object-type cycles (`type A { b: B }` + `type B { a: A }`), and this classifier walks the nested type's fields eagerly. Track visited type names on the way down and produce an `UnclassifiedField` on self-reference. Mirror the input-side precedent in `TypeBuilder.buildInputField` (`graphitron-rewrite/.../TypeBuilder.java:567–625`): thread a `Set<String> expandingTypes` through the recursion and reject with the same shape — "circular type reference detected while expanding '…'".

**Emitter.** In `TypeClassGenerator`, refactor `build$FieldsMethod`'s inner switch-emission into a static helper that takes a depth counter, suffixes the loop variables (`entry0`/`sf0`, `entry1`/`sf1`, …) to avoid Java's block-scoped local-variable shadowing, and recurses into `NestingField` arms:

```java
private static void emitSwitch(CodeBlock.Builder code,
        int depth,
        List<ChildField.ColumnField> columns,
        List<ChildField.NestingField> nestings,
        List<ChildField.PlatformIdField> platformIds,
        List<ChildField.NodeIdField> nodeIds,
        List<ChildField.TableField> tables,
        List<ChildField.LookupTableField> lookups,
        String selExpr,
        String tableVar) {
    var entryVar = "entry" + depth;
    var sfVar = "sf" + depth;
    code.add("for ($T $L : $L.getFieldsGroupedByResultKey().entrySet()) {\n",
             entryType, entryVar, selExpr);
    code.add("    $T $L = $L.getValue().get(0);\n", SELECTED_FIELD, sfVar, entryVar);
    code.add("    switch ($L.getName()) {\n", sfVar);
    for (var cf : columns) {
        code.add("        case $S -> fields.add($L.$L);\n",
                 cf.name(), tableVar, cf.column().javaName());
    }
    // ... existing arms for platformIds, nodeIds, tables, lookups (top level only)
    for (var nf : nestings) {
        code.add("        case $S -> {\n", nf.name());
        emitSwitch(code, depth + 1,
                   nf.columnFields(), nf.nestingFields(),
                   List.of(), List.of(), List.of(), List.of(),
                   sfVar + ".getSelectionSet()", tableVar);
        code.add("        }\n");
    }
    code.add("        default -> { } // unhandled fields\n");
    code.add("    }\n");
    code.add("}\n");
}
```

The top-level `$fields` body becomes a single `emitSwitch(code, 0, …, "sel", "table")` call. Recursive invocations pass empty lists for the slots that only apply at the top level (table-backed subqueries, lookup tables, platform ids, node ids — none of these are valid inside a `NestingField` in v1); the classifier has already rejected anything else.

**Wiring.** Add a `ChildField.NestingField` arm to `TypeFetcherGenerator.buildWiringEntry`:

```java
if (field instanceof ChildField.NestingField nf) {
    return CodeBlock.of("\n.dataFetcher($S, env -> env.getSource())", nf.name());
}
```

Identical to the existing `ConstructorField` arm. graphql-java's default `PropertyDataFetcher` resolves each nested scalar from the passed-through parent record via the jOOQ getter it already generates.

**Partition.** Move `NestingField` from `NOT_IMPLEMENTED_REASONS` into `PROJECTED_LEAVES` (it contributes to the parent's `$fields` output list and emits no per-field fetcher method). The sealed-switch arm in `generateTypeSpec` becomes `/* wired inline: env -> env.getSource(); projected via parent $fields */`, mirroring `ConstructorField`. `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` enforces the four-way partition and will catch drift.

**Pipeline test.** SDL with a `@table` parent and a plain-object nesting child classifies as a `NestingField` whose `columnFields` and `nestingFields` are populated against the parent table; the generated fetchers class contains no method for the nesting field; the parent's `$fields` method contains a nested switch keyed by the nesting field's name.

### C2 — Validation + execution tests

No new validator walk is needed — classification failure is the validation. C2 exists purely to lock the behaviour in tests.

**Validator.** `GraphitronSchemaValidator.validateNestingField` gains one check that classification cannot express: reject `FieldWrapper.List` on `NestingField`. Passthrough has no sensible list semantic under source-passthrough wiring — the default fetcher can't multiply rows from a single parent record. Legacy may have supported this; if a real schema needs it we lift the rejection in a follow-up with a defined semantic. Today `GraphitronSchemaBuilderTest.NestingFieldCase.LIST_OF_PLAIN_OBJECT_TYPE` classifies successfully and needs its expected outcome flipped to a validation error.

**Classification test coverage.** Add to `GraphitronSchemaBuilderTest`:

- Nested scalar resolves to a parent column (`Film.details.title` → `FILM.TITLE`).
- Unmatched nested scalar → `UnclassifiedField` with the nested location.
- Directive-bearing nested scalar (e.g. `@reference`) → `UnclassifiedField`.
- Multi-level nesting produces a recursive `NestingField.nestingFields()` entry.
- Cycle guard: self-referential object type → classification error, no `StackOverflowError`.

**Execution tests** (`graphitron-rewrite-test-spec`):

- **Scalar nesting.** `Film @table { details: FilmDetails } ; FilmDetails { title, description, releaseYear }`. Query `film { details { title releaseYear } }` → one SQL round-trip projecting `FILM.TITLE, FILM.RELEASE_YEAR` only. Asserts column-pruning works end-to-end.
- **Multi-level nesting.** `Film { details: FilmDetails } ; FilmDetails { meta: FilmMeta } ; FilmMeta { releaseYear }`. Confirms the recursive emitter produces a working nested switch.
- **Null-parent short-circuit.** `film(id: <nonexistent>) { details { title } }` → `details: null`, no NPE. Confirms `env.getSource()` on a null parent is handled by graphql-java's default property fetcher as expected.

Compile gate: `mvn compile -pl :graphitron-rewrite-test,:graphitron-rewrite-test-fixtures,:graphitron-rewrite-test-spec -Plocal-db`.

## Non-goals

- **Other arms of roadmap #8** — `ColumnReferenceField`, `NodeIdReferenceField`, `ComputedField`, `TableMethodField`, `ServiceRecordField`, `MultitableReferenceField` each get their own plan. `NodeIdReferenceField` is additionally blocked on Platform-id (Active).
- **Directive-bearing fields on nested types** — rejected at classification time in v1. When a schema needs `FilmDetails.externalRef: Something @reference(path: …)` we extend the classifier + emitter in a follow-up.
- **List-cardinality nesting** — rejected at validate time. Lift when a concrete use case arrives with a defined semantic.
- **Explicit classification of nested child types as `GraphitronType`** — intentionally skipped. `FilmDetails` never enters the `GraphitronType` map; it has no fetchers class, no type class, no wiring. Only its scalar fields are classified, and they are hung off the enclosing `NestingField` on the outermost `@table` parent.
- **Schema-field-name to column-name overrides** — v1 relies on the standard naming convention. If a nested field needs a custom mapping we either reject it (forcing the author to rename) or add a narrow override mechanism in a follow-up.
- **Inline table fields, lookup tables, platform ids, node ids inside a nesting child** — the recursive `emitSwitch` takes empty lists for these slots at inner levels; classifier rejects such directives on nested fields.
