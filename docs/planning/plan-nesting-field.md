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

- **Classification reuses the existing machinery.** The builder walks the nested `GraphQLObjectType`'s fields and delegates each one to `FieldBuilder.classifyChildFieldOnTableType(fieldDef, nestedTypeName, outerParentTableType)`. Nested fields produce the standard sealed-leaf `ChildField` subtypes — `ColumnField` (with `@field(name:)` remapping, as at top level), `NestingField` (recursion), and every other leaf the existing classifier knows about. Directive handling is uniform with top-level; no blanket rejection.
- **Emission for projectable leaves is a recursive nested switch** inlined into the parent type's existing `$fields(sel, table, env)` method. One switch per nesting level, keyed by GraphQL field name; arms dispatch the same leaves the top-level `$fields` already handles (`ColumnField`, `PlatformIdField`, `NodeIdField`, `TableField`, `LookupTableField`), plus a `NestingField` arm that recurses.
- **Wiring is passthrough.** `env -> env.getSource()` hands the parent record to graphql-java, which resolves each nested scalar via the default `PropertyDataFetcher` against the jOOQ record's getters (`getTitle()`, `getReleaseYear()`, …). For projectable leaves that is all v1 needs; no fetchers class is emitted for the nested type.
- **Non-projectable nested leaves are deferred to a follow-up plan.** There is no *semantic* restriction on what directives can appear on a nested type — the outer parent's jOOQ record flows through `env.getSource()` unchanged, so anything that consumes `env.getSource()` at top level (`@service`, `@splitQuery`, `@computed`, …) would behave identically at nested depth. The gap is wiring-side: graphql-java routes data fetchers by `(typeName, fieldName)`, so a `@service` on `FilmDetails.foo` needs registration under `("FilmDetails", "foo")` — a second `TypeRuntimeWiring` the v1 emitter does not produce. Classification stays permissive (any leaf can appear in `nestedFields`); v1 rejects non-projectable leaves in the validator with a message pointing at the follow-up plan; the exhaustive sealed switch in the emitter then treats those leaves as unreachable.
- **Stubbed leaves reject at build time as they do today.** If a nested field classifies as a leaf still in `NOT_IMPLEMENTED_REASONS` (`ColumnReferenceField`, `ComputedField`, …), the existing stubbed-variant validator rejects the schema — same mechanism, same error shape as top level. As each roadmap-#8 arm lands for projectable leaves, nested support for that arm falls out with it; non-projectable arms additionally require the follow-up wiring plan.

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
            // Non-projectable leaves (ConstructorField, FetcherField, …) and all
            // stubbed leaves are rejected by C2's validator before we get here;
            // the exhaustive sealed switch makes this unreachable at build time.
            default -> throw new AssertionError(
                "unreachable: validator rejects non-projectable / stubbed nested leaf " + f);
        }
    }
    code.add("        default -> { } // unhandled fields\n");
    code.add("    }\n");
    code.add("}\n");
}
```

Switch on `ChildField` is exhaustive against the sealed hierarchy: the projectable arms cover v1's nested cases, the `NestingField` arm recurses, and the `default` is a build-time tripwire — not a silent skip — because the validator rejects anything else at parse time. The top-level `$fields` body becomes a single `emitSwitch(code, 0, fields, "sel", "table")` call — same helper, same depth semantics at every level. Inline `TableField`/`LookupTableField` emitters already take a `tableVar` parameter for the correlated-subquery join; the outer parent's alias is reused at every depth since nesting never navigates.

**Wiring.** Add a `ChildField.NestingField` arm to `TypeFetcherGenerator.buildWiringEntry`:

```java
if (field instanceof ChildField.NestingField nf) {
    return CodeBlock.of("\n.dataFetcher($S, env -> env.getSource())", nf.name());
}
```

Identical to the existing `ConstructorField` arm. graphql-java's default `PropertyDataFetcher` resolves each nested scalar from the passed-through parent record via the jOOQ getter it already generates.

**Partition.** Move `NestingField` from `NOT_IMPLEMENTED_REASONS` into `PROJECTED_LEAVES` (it contributes to the parent's `$fields` output list and emits no per-field fetcher method). The sealed-switch arm in `generateTypeSpec` becomes `/* wired inline: env -> env.getSource(); projected via parent $fields */`, mirroring `ConstructorField`. `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` enforces the four-way partition and will catch drift.

**Pipeline test.** SDL with a `@table` parent and a plain-object nesting child classifies as a `NestingField` whose `nestedFields` list is populated against the outer parent's table; `@field(name:)` on a nested scalar resolves to the remapped column; the generated fetchers class contains no method for the nesting field; the parent's `$fields` method contains a nested switch keyed by the nesting field's name.

### C2 — Validation + execution tests

Classification failure is most of the validation. C2 adds the two checks classification can't express and locks end-to-end behaviour in tests.

**Validator.** `GraphitronSchemaValidator.validateNestingField` gains three checks:

- **`FieldWrapper.List` on `NestingField`.** Passthrough has no sensible list semantic under source-passthrough wiring — the default fetcher can't multiply rows from a single parent record. Legacy may have supported this; if a real schema needs it we lift the rejection in a follow-up with a defined semantic. Today `GraphitronSchemaBuilderTest.NestingFieldCase.LIST_OF_PLAIN_OBJECT_TYPE` classifies successfully and needs its expected outcome flipped to a validation error.
- **Walk `nestedFields`** and apply `validateVariantIsImplemented` to each. Any sealed leaf still in `NOT_IMPLEMENTED_REASONS` (e.g. `ColumnReferenceField`, `ComputedField`) surfaces as a build-time error at the nested field's location — same mechanism, same error shape, same behaviour as when that leaf appears at the top level. This is the single integration point that makes "as #8 arms land, nested support follows" hold.
- **Projectability gate (v1-only signpost).** For each leaf in `nestedFields`, reject if its class is not in `PROJECTED_LEAVES`. This blocks nested leaves the top-level emitter handles via a dedicated fetcher method on the parent's `TypeRuntimeWiring` — v1's passthrough wiring emits no such method under the nested type's wiring, so those leaves would compile but fetch wrong at runtime. Error message points at the follow-up plan (non-projectable nested wiring). Lift this check when that plan lands; classification remains permissive so the deferred work is a pure wiring-side change.

**Classification test coverage.** Add to `GraphitronSchemaBuilderTest`:

- Nested scalar resolves to a parent column (`Film.details.title` → `FILM.TITLE`).
- `@field(name:)` on a nested scalar remaps the column.
- Unmatched nested scalar → `UnclassifiedField` with the nested location.
- Nested field bearing a currently-stubbed directive (e.g. `@reference`) classifies as the corresponding leaf (`ColumnReferenceField`) and is surfaced by the validator walk, not rejected at classify time.
- Multi-level nesting produces a recursive `NestingField.nestedFields()` entry.
- Cycle guard: self-referential object type → `UnclassifiedField` with the circular-reference message, no `StackOverflowError`.

**Execution tests** (`graphitron-rewrite-test-spec`):

- **Scalar nesting.** `Film @table { details: FilmDetails } ; FilmDetails { title, description, releaseYear }`. Query `film { details { title releaseYear } }` → one SQL round-trip projecting `FILM.TITLE, FILM.RELEASE_YEAR` only. Asserts column-pruning works end-to-end.
- **Multi-level nesting.** `Film { details: FilmDetails } ; FilmDetails { meta: FilmMeta } ; FilmMeta { releaseYear }`. Confirms the recursive emitter produces a working nested switch.
- **Null-parent short-circuit.** `film(id: <nonexistent>) { details { title } }` → `details: null`, no NPE. Confirms `env.getSource()` on a null parent is handled by graphql-java's default property fetcher as expected.

Compile gate: `mvn compile -pl :graphitron-rewrite-test,:graphitron-rewrite-test-fixtures,:graphitron-rewrite-test-spec -Plocal-db`.

## Non-goals

- **Other arms of roadmap #8** — `ColumnReferenceField`, `NodeIdReferenceField`, `ComputedField`, `TableMethodField`, `ServiceRecordField`, `MultitableReferenceField` each get their own plan. Their nested-context support follows automatically from this plan once the top-level emission lands, via the shared `classifyChildFieldOnTableType` classifier and the shared `validateVariantIsImplemented` gate. `NodeIdReferenceField` is additionally blocked on Platform-id (Active).
- **Non-projectable nested leaves** — any leaf that resolves via a dedicated data fetcher on the parent's `TypeRuntimeWiring` at top level (`ConstructorField`, `FetcherField`, and once their #8 plans land, `@service`, `@splitQuery`, `@computed`, …) is rejected by the v1 validator's projectability gate. There is no semantic barrier — the outer parent's jOOQ record flows through `env.getSource()` unchanged, so these directives would behave identically at nested depth — but v1 emits no `TypeRuntimeWiring` for the nested type, so the fetcher has nowhere to register. Follow-up plan: emit a second `TypeRuntimeWiring` keyed by the nested type name and route non-projectable nested leaves to it. Classification already admits them; the deferred work is pure emitter/wiring.
- **List-cardinality nesting** — rejected at validate time. Lift when a concrete use case arrives with a defined semantic.
- **Explicit classification of nested child types as `GraphitronType`** — intentionally skipped. `FilmDetails` never enters the `GraphitronType` map; it has no fetchers class, no type class, no wiring. Its fields are classified via the shared `classifyChildFieldOnTableType` path and hung off the enclosing `NestingField` on the outermost `@table` parent.
