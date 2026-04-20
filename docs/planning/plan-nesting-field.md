# `ChildField.NestingField` emission

> **Status:** Draft
>
> Lift `ChildField.NestingField` out of `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` by (a) resolving the nested projection at build time during classification, (b) emitting a recursive nested switch inline in the parent type's `$fields` method, and (c) emitting a per-field `TypeRuntimeWiring` for the nested type so each leaf resolves the same way it does at top level. First arm of roadmap item #8 ("Non-table / scalar / reference child leaves").

## Current state

- `ChildField.NestingField` (`model/ChildField.java`) carries the parent's `TableRef` verbatim in its `ReturnTypeRef.TableBoundReturnType`. It is deliberately excluded from `TableTargetField` because it does not navigate.
- Classification is complete for the wrapper type itself: `FieldBuilder.classifyObjectReturnChildField` fires the `NestingField` arm when the child's return type is a `GraphQLObjectType` with no `GraphitronType` entry (no `@table`, no `@record`). Covered by `GraphitronSchemaBuilderTest.NestingFieldCase` — `PLAIN_OBJECT_TYPE` (`Film.details: FilmDetails`) and `LIST_OF_PLAIN_OBJECT_TYPE` (`Film.tags: [Tag!]!`).
- The *contents* of the nested type are not classified. `NestingField` currently has no projection data — its fields never reach the model.
- `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` routes `NestingField` to `stub(f)`; the sealed-switch arm in `generateTypeSpec` dispatches to the same stub.
- `GraphitronSchemaValidator.validateNestingField` is empty; users see the generic stubbed-variant error from `validateVariantIsImplemented`.

## Design

Nested scalars on a plain (non-`@table`, non-`@record`) object type resolve against the parent's jOOQ record:

- **Classification reuses the existing machinery.** The builder walks the nested `GraphQLObjectType`'s fields and delegates each one to `FieldBuilder.classifyChildFieldOnTableType(fieldDef, nestedTypeName, outerParentTableType)`. Nested fields produce the standard sealed-leaf `ChildField` subtypes — `ColumnField` (with `@field(name:)` remapping, as at top level), `NestingField` (recursion), and every other leaf the existing classifier knows about. Directive handling is uniform with top-level; no blanket rejection.
- **Projection is a recursive nested switch** inlined into the parent type's existing `$fields(sel, table, env)` method. One switch per nesting level, keyed by GraphQL field name; arms dispatch the same leaves the top-level `$fields` already handles (`ColumnField`, `PlatformIdField`, `NodeIdField`, `TableField`, `LookupTableField`), plus a `NestingField` arm that recurses. The SELECT list at the outermost `@table` parent contains every projected column the entire nested selection needs; the outer query materializes one flat jOOQ `Record`.
- **Per-field wiring is emitted under a `TypeRuntimeWiring` keyed by the nested type name.** Each leaf in `nestedFields` gets the same `.dataFetcher(…)` entry `buildWiringEntry` produces at top level — `new ColumnFetcher<>(Tables.X.COL)` for `ColumnField` (identity-based typed `Field` reference, so `@field(name:)` remap is honored without column aliasing), the existing inline `.dataFetcher` for `TableField`/`LookupTableField`/`NodeIdField`, and so on. The outer parent's wiring for the `NestingField` leaf itself is `env -> env.getSource()` — a bare passthrough that hands the outer `Record` to the nested type's fetchers. Recursion: a nested `NestingField` does the same, and another `TypeRuntimeWiring` is emitted for its nested type at that depth.
- **Stubbed leaves reject at build time as they do today.** If a nested field classifies as a leaf still in `NOT_IMPLEMENTED_REASONS` (`ColumnReferenceField`, `ComputedField`, …), the existing stubbed-variant validator rejects the schema — same mechanism, same error shape as top level. As each roadmap-#8 arm lands, nested support for that arm falls out with it.

Design rationale — this keeps `NestingField` consistent with the rewrite's core principles:

- *Classification belongs at the parse boundary* — nested columns are resolved once by `FieldBuilder`, not re-mapped at runtime.
- *Generators receive a model that is already in terms of "what to emit"* — the emitter switches on a pre-resolved tree.
- *Reuse over parallelism* — one classifier, one validator pass, one emitter helper. Nested context threads the outer parent's table through the existing machinery instead of duplicating it.

## Plan

Two commits.

### C1 — Classification + emission

**Model.** Extend `ChildField.NestingField` with one component:

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

**Classifier.** In `FieldBuilder.classifyObjectReturnChildField`, when the `NestingField` arm fires, walk the nested `GraphQLObjectType`'s field definitions and delegate each one to the existing `classifyChildFieldOnTableType(fieldDef, nestedTypeName, outerParentTableType)` at `FieldBuilder.java:1634`. This gives nested fields the same classification path as top-level fields on the outer parent: `@field(name:)` remaps columns, `@reference(path:)` produces `ColumnReferenceField`, `@computed` produces `ComputedField`, nested plain-object types recurse back through the same arm producing another `NestingField`, etc. `UnclassifiedField` returns surface in the nested list verbatim — the standard validator path catches them.

Cycle guard: GraphQL schemas can express object-type cycles (`type A { b: B }` + `type B { a: A }`), and this classifier walks the nested type's fields eagerly. Thread a `Set<String> expandingTypes` through the recursion and return `UnclassifiedField` on self-reference. Mirror the input-side precedent in `TypeBuilder.buildInputField` (`graphitron-rewrite/.../TypeBuilder.java:567–625`) with the same shape — "circular type reference detected while expanding '…'".

**Emitter.** In `TypeClassGenerator`, refactor `build$FieldsMethod`'s inner switch-emission into a static helper that takes a depth counter and a single `List<ChildField>`, suffixes the loop variables (`entry0`/`sf0`, `entry1`/`sf1`, …) to avoid Java's block-scoped local-variable shadowing, dispatches each sealed leaf the top-level switch already handles, and adds a `NestingField` arm that recurses:

```java
private static void emitSwitch(CodeBlock.Builder code,
        int depth,
        List<ChildField> fields,
        String selExpr,
        String tableVar) {
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
            // (identical to the existing top-level emission)
            case ChildField.NestingField nf -> {
                code.add("        case $S -> {\n", nf.name());
                emitSwitch(code, depth + 1, nf.nestedFields(),
                           sfVar + ".getSelectionSet()", tableVar);
                code.add("        }\n");
            }
            // Stubbed leaves (ColumnReferenceField, ComputedField, …) are rejected by
            // C2's validator walk before we get here; the exhaustive sealed switch makes
            // this unreachable at build time.
            default -> throw new AssertionError(
                "unreachable: validator rejects stubbed nested leaf " + f);
        }
    }
    code.add("        default -> { } // unhandled fields\n");
    code.add("    }\n");
    code.add("}\n");
}
```

Switch on `ChildField` is exhaustive against the sealed hierarchy: every leaf the top-level `$fields` already handles gets an arm, the `NestingField` arm recurses, and the `default` is a build-time tripwire — not a silent skip — because the validator rejects anything else at parse time. The top-level `$fields` body becomes a single `emitSwitch(code, 0, fields, "sel", "table")` call — same helper, same depth semantics at every level. Inline `TableField`/`LookupTableField` emitters already take a `tableVar` parameter for the correlated-subquery join; the outer parent's alias is reused at every depth since nesting never navigates.

**Wiring.** Two changes to `TypeFetcherGenerator`:

1. `buildWiringEntry` gains a `ChildField.NestingField` arm that passes the outer `Record` down:

   ```java
   if (field instanceof ChildField.NestingField nf) {
       return CodeBlock.of("\n.dataFetcher($S, env -> env.getSource())", nf.name());
   }
   ```

2. The wiring emitter produces an additional `TypeRuntimeWiring.newTypeWiring(nestedTypeName)` builder for each nested plain-object type, walking `nestedFields` and calling the existing `buildWiringEntry` for each leaf. Each leaf gets the same wiring it would at top level — `new ColumnFetcher<>(Tables.X.COL)` for `ColumnField` (the typed `Field` reference is identity-based, so `@field(name:)` remap flows through with no aliasing in the SQL), the existing inline `.dataFetcher(…)` for `TableField`/`LookupTableField`/`NodeIdField`, etc. `NodeIdField` wiring additionally needs the outer parent's `TableRef` (for `Tables.FILM.ID` qualification in the node-id encode call); thread that through the nested emission the same way it is passed at top level. Recursion: a nested `NestingField` entry inside `nestedFields` emits both the outer passthrough wiring and, via recursive descent, a further `TypeRuntimeWiring` for its own nested type. These additional builders flow into the schema-wiring setup alongside top-level builders — simplest placement is a sibling method on the outer parent's Fetchers class (e.g. `wiringNested_FilmDetails()`) collected by the same glue that today collects one `wiring()` per Fetchers class.

**Partition.** Move `NestingField` from `NOT_IMPLEMENTED_REASONS` into `PROJECTED_LEAVES` — at the outer parent it contributes to `$fields` and emits no per-field fetcher *method*. Nested-type wiring lives on the outer parent's Fetchers class (or equivalent collection point), not as per-field methods on a Fetchers class for the nested type, so the four-way partition invariant is unaffected. The sealed-switch arm in `generateTypeSpec` becomes `{ /* wiring: outer passthrough + per-field TypeRuntimeWiring for nested type; SELECT projected via parent $fields */ }`. `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` enforces the four-way partition and will catch drift.

**Pipeline test.** SDL with a `@table` parent and a plain-object nesting child classifies as a `NestingField` whose `nestedFields` list is populated against the outer parent's table; `@field(name:)` on a nested scalar resolves to the remapped column; the generated Fetchers class contains no per-field method for the nesting field; the parent's `$fields` method contains a nested switch keyed by the nesting field's name; the generated wiring includes an additional `TypeRuntimeWiring.newTypeWiring("<NestedType>")` with per-field `.dataFetcher(…)` entries.

### C2 — Validation + execution tests

Classification failure is most of the validation. C2 adds the two checks classification can't express and locks end-to-end behaviour in tests.

**Validator.** `GraphitronSchemaValidator.validateNestingField` gains two checks:

- **`FieldWrapper.List` on `NestingField`.** A list-valued nested wrapper has no sensible semantic under a single parent `Record` — the outer query produced one row per outer parent, and the passthrough wiring hands that single `Record` down with no way to multiply rows. Legacy may have supported this; if a real schema needs it we lift the rejection in a follow-up with a defined semantic. Today `GraphitronSchemaBuilderTest.NestingFieldCase.LIST_OF_PLAIN_OBJECT_TYPE` classifies successfully and needs its expected outcome flipped to a validation error.
- **Walk `nestedFields`** and apply `validateVariantIsImplemented` to each. Any sealed leaf still in `NOT_IMPLEMENTED_REASONS` (e.g. `ColumnReferenceField`, `ComputedField`) surfaces as a build-time error at the nested field's location — same mechanism, same error shape, same behaviour as when that leaf appears at the top level. This is the single integration point that makes "as #8 arms land, nested support follows" hold.

**Classification test coverage.** Add to `GraphitronSchemaBuilderTest`:

- Nested scalar resolves to a parent column (`Film.details.title` → `FILM.TITLE`).
- `@field(name:)` on a nested scalar remaps the column.
- Unmatched nested scalar → `UnclassifiedField` with the nested location.
- Nested field bearing a currently-stubbed directive (e.g. `@reference`) classifies as the corresponding leaf (`ColumnReferenceField`) and is surfaced by the validator walk, not rejected at classify time.
- Multi-level nesting produces a recursive `NestingField.nestedFields()` entry.
- Cycle guard: self-referential object type → `UnclassifiedField` with the circular-reference message, no `StackOverflowError`.

**Execution tests** (`graphitron-rewrite-test-spec`):

- **Scalar nesting.** `Film @table { details: FilmDetails } ; FilmDetails { title, description, releaseYear }`. Query `film { details { title releaseYear } }` → one SQL round-trip projecting `FILM.TITLE, FILM.RELEASE_YEAR` only, and the returned GraphQL document contains the correct values. Asserts column-pruning works end-to-end *and* the nested `TypeRuntimeWiring` resolves scalars correctly.
- **`@field(name:)` remap at nested depth.** Nested scalar with `@field(name: "…")` returns the remapped column's value in the GraphQL response — proves the `ColumnFetcher`'s typed `Field` reference path works identically at nested depth as at top level.
- **Multi-level nesting.** `Film { details: FilmDetails } ; FilmDetails { meta: FilmMeta } ; FilmMeta { releaseYear }`. Confirms the recursive emitter produces a working nested switch and the recursive wiring emission produces working per-level `TypeRuntimeWiring`s.
- **Null-parent short-circuit.** `film(id: <nonexistent>) { details { title } }` → `details: null`, no NPE. Confirms the nested wiring does not dereference a null source.

Compile gate: `mvn compile -pl :graphitron-rewrite-test,:graphitron-rewrite-test-fixtures,:graphitron-rewrite-test-spec -Plocal-db`.

## Non-goals

- **Other arms of roadmap #8** — `ColumnReferenceField`, `NodeIdReferenceField`, `ComputedField`, `TableMethodField`, `ServiceRecordField`, `MultitableReferenceField` each get their own plan. Their nested-context support follows automatically from this plan once the top-level emission lands, via the shared `classifyChildFieldOnTableType` classifier, the shared `validateVariantIsImplemented` gate, and the shared `buildWiringEntry` call on the emitted nested `TypeRuntimeWiring`. `NodeIdReferenceField` is additionally blocked on Platform-id (Active).
- **List-cardinality nesting** — rejected at validate time. Lift when a concrete use case arrives with a defined semantic.
- **Explicit classification of nested child types as `GraphitronType`** — intentionally skipped. `FilmDetails` never enters the `GraphitronType` map; it has no Fetchers class (the nested `TypeRuntimeWiring` is emitted from the outer parent's Fetchers class) and no type class. Its fields are classified via the shared `classifyChildFieldOnTableType` path and hung off the enclosing `NestingField` on the outermost `@table` parent.
