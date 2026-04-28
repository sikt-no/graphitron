---
title: "Same-table `[ID!] @nodeId` filter → primary-key IN predicate"
status: Ready
priority: 6
---

# Same-table `[ID!] @nodeId` filter → primary-key IN predicate

## Problem

A `[ID!] @nodeId(typeName: "X")` field on a `@table` input type where type X maps to
the **same table** as the input currently hits `findUniqueFkToTable(t, t)` (a self-join
lookup), finds no FK, and fails:

```
unresolvable fields: 'kompetanseregelverkIds': no unique FK from
'kompetanseregelverk' to 'kompetanseregelverk'; declare @reference(path: [{key:
...}]) to disambiguate
```

The error message is wrong: there is no FK to declare because this is not a join. The
author's intent is "filter results to rows whose composite PK matches one of these
node IDs"; a primary-key IN predicate, not a foreign-key join.

The existing `@reference` workaround suggestion in the error message is conceptually
a dead end: there is no FK to point at when source and target are the same table for
this filter pattern, and even if a self-FK existed (e.g. parent_id) the desired
semantics is "filter by my own PK", not "join via that FK". `parsePath`'s
`!startSqlTableName.equalsIgnoreCase(targetSqlTableName)` guard at
`BuildContext.java:463` only skips the *auto-FK-inference* branch in the same-table
case; explicit `@reference(path: [{key: ...}])` would still be walked. The new
classifier branch short-circuits both paths before either is reached, which is the
correct behaviour for this filter pattern regardless of what the user wrote.

## Affected fields (from `opptak-subgraph`)

- `KompetanseregelverkV2FilterInput.kompetanseregelverkIds`
- `RangeringsregelverkFilterInput.rangeringsregelverkIds`
- `RangeringsregelverkV2FilterInput.rangeringsregelverkIds`
- `RegelverksamlingFilterInput.ids`

All four share the same pattern: a filter input for type X has a `[ID!]
@nodeId(typeName: "X")` field referencing the same type.

## Root Cause

In `BuildContext.classifyInputField` (line 801), the canonical `[ID!] @nodeId` branch:

1. Resolves `targetTable` from `@nodeId(typeName: T)`. In this case same as
   `resolvedTable.tableName()`.
2. Calls `catalog.findUniqueFkToTable(resolvedTable.tableName(), targetTable)` with
   identical arguments.
3. `findUniqueFkToTable` returns `Optional.empty()` (self-FKs are rare; even if one
   existed, using it would be wrong semantically).
4. Emits the "no unique FK" `Unresolved`.

The fix is to detect `targetTable.equalsIgnoreCase(resolvedTable.tableName())` before
calling `findUniqueFkToTable` and route to a new classification path.

## New `InputField` Variant: `NodeIdInFilterField`

File: `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/InputField.java`

```java
/**
 * A {@code [ID!]} field on a {@code @table} input type whose {@code @nodeId(typeName:)}
 * references the <em>same</em> table as the input itself. Semantics: "filter results
 * to rows whose composite primary key matches one of these decoded node IDs"; a
 * primary-key IN predicate, not a FK join.
 *
 * <p>The generator decodes each base-64 node ID into its component PK column values,
 * then emits {@code WHERE (pk1, pk2, ...) IN ((v1a, v1b), (v2a, v2b), ...)}.
 * No join path is involved.
 *
 * <p>{@code nodeTypeId} and {@code nodeKeyColumns} come from
 * {@link JooqCatalog#nodeIdMetadata(String)} on the target table.
 */
record NodeIdInFilterField(
    String parentTypeName,
    String name,
    SourceLocation location,
    String nodeTypeId,
    List<ColumnRef> nodeKeyColumns
) implements InputField {}
```

Update the `permits` clause to include `InputField.NodeIdInFilterField`.

Add `InputField.NodeIdInFilterField.class` to `TypeFetcherGenerator.NOT_DISPATCHED_LEAVES`
(the input-field set that never flows through type dispatch). The generic
`case InputField ignored ->` arm at `TypeFetcherGenerator.java:398` already absorbs
the new variant; no edit needed there. Update the two remaining exhaustive switches:

- `GraphitronSchemaValidator.java`: `case InputField.NodeIdInFilterField ignored -> {}`
  (no extra validation: `nodeKeyColumns` non-emptiness is guaranteed by the classifier).
- `FieldBuilder.walkInputFieldConditions`: emits a `BodyParam.NodeIdIn` (see "Codegen"
  below), not a no-op.

## Classifier Change: `BuildContext.classifyInputField`

In the canonical `[ID!] @nodeId` branch (line 801 area), immediately after resolving
`targetTable` and before the `@reference` / `findUniqueFkToTable` block, insert:

```java
// Same-table case: the referenced type maps to this filter's own table.
// Semantics are "filter by own primary key", not a FK join. Route to
// NodeIdInFilterField instead of attempting findUniqueFkToTable(t, t).
if (targetTable.equalsIgnoreCase(resolvedTable.tableName())) {
    var nodeIdMeta = catalog.nodeIdMetadata(targetTable);
    if (nodeIdMeta.isEmpty()) {
        return new InputFieldResolution.Unresolved(name, null,
            "@nodeId(typeName: '" + refTypeName + "') targets table '" + targetTable
            + "' which has no @node key metadata; cannot generate primary-key IN filter");
    }
    return new InputFieldResolution.Resolved(new InputField.NodeIdInFilterField(
        parentTypeName, name, locationOf(field),
        nodeIdMeta.get().typeId(), nodeIdMeta.get().keyColumns()));
}
```

This check is placed before both the `@reference` block and the `findUniqueFkToTable`
call, so neither path is reached for the same-table case.

## Codegen: end-to-end

The runtime helper already exists: `NodeIdEncoder.hasIds(typeId, ids, table.col1, ..., table.colN)`
returns a jOOQ `Condition` that decodes each base64 ID, splits it into N values, and
emits `(table.col1, ..., table.colN) IN ((v1a, ...), (v2a, ...), ...)`. Generated
today by `NodeIdEncoderClassGenerator`; called inline by `LookupValuesJoinEmitter.buildNodeIdFetcherBody`
for top-level lookup args. We reuse the same helper here.

The generator pipeline for input-field predicates flows
`walkInputFieldConditions` → `BodyParam` → `TypeConditionsGenerator.buildConditionMethod`
→ generated `<Type>Conditions.<field>Condition(table, args...)`, called from the fetcher
via `condition.and(<Type>Conditions.<field>Condition(table, args...))`. To slot
`NodeIdInFilterField` into that pipeline cleanly, extend `BodyParam` to a sealed
interface so the existing call-site machinery (`CallParam`, nested-input-field
extraction) keeps working unchanged, and only the body-emission step branches.

### Model: split `BodyParam`

File: `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/BodyParam.java`

```java
public sealed interface BodyParam permits BodyParam.ColumnEq, BodyParam.NodeIdIn {

    /** Parameter name (matches the GraphQL input field name). */
    String name();
    /** Whether the parameter is a list (drives the call-site extraction shape too). */
    boolean list();
    /** Whether a runtime null guard is needed. */
    boolean nonNull();
    /** Java type of the method parameter (e.g. {@code "java.lang.String"}). */
    String javaType();
    /** How to extract the value at the fetcher call site (NestedInputField for input-type fields). */
    CallSiteExtraction extraction();

    /** The current single-column equality / IN predicate. Body emits {@code table.col.eq(arg)} or {@code table.col.in(arg)}. */
    record ColumnEq(
        String name,
        ColumnRef column,
        String javaType,
        boolean nonNull,
        boolean list,
        CallSiteExtraction extraction
    ) implements BodyParam {}

    /**
     * A {@code [ID!]} list of base64 node IDs that translates to a composite-PK IN
     * predicate. Body emits {@code NodeIdEncoder.hasIds("typeId", arg, table.col1, ..., table.colN)}.
     * {@code javaType} is always {@code "java.lang.String"} and {@code list} is always {@code true}.
     */
    record NodeIdIn(
        String name,
        String nodeTypeId,
        List<ColumnRef> nodeKeyColumns,
        boolean nonNull,
        CallSiteExtraction extraction
    ) implements BodyParam {
        public String javaType() { return String.class.getName(); }
        public boolean list() { return true; }
    }
}
```

Migrate every `new BodyParam(...)` site to `new BodyParam.ColumnEq(...)`:
- `FieldBuilder.implicitBodyParam` (returns `ColumnEq`)
- `FieldBuilder.projectFilters` (the `ScalarArg.ColumnArg` case): switch to
  `BodyParam.ColumnEq`

Migrate every consumer:
- `GeneratedConditionFilter.bodyParams()` typing is unchanged (`List<BodyParam>`).
- `TypeConditionsGenerator.buildConditionMethod` switches on the variant for body
  emission (see below) but uses interface methods (`name`, `list`, `javaType`) for
  the parameter list.
- `TypeConditionsGenerator.generateConditionsClass` filters
  `bp.extraction() instanceof CallSiteExtraction.TextMapLookup` via the interface
  method; no change.

### Classifier emits a `BodyParam.NodeIdIn`

File: `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java`,
`walkInputFieldConditions` (line 1262)

Replace the `case InputField.NodeIdInFilterField ignored -> {}` no-op with an
`implicitBodyParams.add(...)` call:

```java
case InputField.NodeIdInFilterField nf -> {
    if (implicitBodyParams != null && !enclosingOverride
            && !lookupBoundNames.contains(nf.name())) {
        implicitBodyParams.add(new BodyParam.NodeIdIn(
            nf.name(),
            nf.nodeTypeId(),
            nf.nodeKeyColumns(),
            /* nonNull */ false,  // body always guards `arg == null || arg.isEmpty()`, so the
                                  // outer-list nullability ([ID!] vs [ID!]!) does not matter here
            new CallSiteExtraction.NestedInputField(outerArgName, leafPath)));
    }
}
```

The `lookupBoundNames` guard mirrors the `ColumnField`/`ColumnReferenceField` arms;
a `NodeIdInFilterField` could in principle also be `@lookupKey`-bound, in which case
the existing `LookupMapping.NodeIdMapping` path takes over (see the `NodeIdArg`
branch at FieldBuilder.java:1098). The two paths must not double-emit. (Today no
schema combines them, but the guard is cheap insurance.)

### Body emitter: `TypeConditionsGenerator`

File: `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeConditionsGenerator.java`,
`buildConditionMethod` (line 92)

Group the parameter list and body emission by `BodyParam` variant. The method
parameters and the `condition.and(...)` lines now branch:

```java
for (var bp : gcf.bodyParams()) {
    var paramType = bp.list()
        ? ParameterizedTypeName.get(LIST, ClassName.bestGuess(bp.javaType()))
        : ClassName.bestGuess(bp.javaType());
    builder.addParameter(paramType, bp.name());
}

builder.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);
ClassName nodeIdEncoder = ClassName.get(outputPackage + ".util",
    NodeIdEncoderClassGenerator.CLASS_NAME);
for (var bp : gcf.bodyParams()) {
    switch (bp) {
        case BodyParam.ColumnEq ce -> {
            // existing body emission, lifted unchanged
        }
        case BodyParam.NodeIdIn ni -> {
            // condition = condition.and(arg == null || arg.isEmpty()
            //     ? DSL.noCondition()
            //     : NodeIdEncoder.hasIds("Type", arg, table.COL1, ..., table.COLN));
            var keyColArgs = CodeBlock.builder();
            for (int i = 0; i < ni.nodeKeyColumns().size(); i++) {
                if (i > 0) keyColArgs.add(", ");
                keyColArgs.add("table.$L", ni.nodeKeyColumns().get(i).javaName());
            }
            builder.addStatement(
                "condition = condition.and($L == null || $L.isEmpty() ? $T.noCondition() : $T.hasIds($S, $L, $L))",
                ni.name(), ni.name(), DSL,
                nodeIdEncoder, ni.nodeTypeId(), ni.name(), keyColArgs.build());
        }
    }
}
```

`TypeConditionsGenerator.generate` and `generateConditionsClass` need
`outputPackage` plumbed through to find `NodeIdEncoder`'s fully qualified class name
(parallel to how `LookupValuesJoinEmitter.buildNodeIdFetcherBody` already takes it).

### Call-site (no change required)

`QueryConditionsGenerator.buildConditionMethod` builds `<field>Condition(table, env)`
shims that pull each arg out of `env` via `ArgCallEmitter.buildArgExtraction` →
`CallSiteExtraction.NestedInputField` → `buildNestedInputFieldExtraction`. That
nested-traversal already returns a `List<String>` for `[ID!]` (the leaf type from
graphql-java for ID is `String`), which is exactly what `NodeIdEncoder.hasIds`
takes as its second argument. No call-site code changes; the value just flows
through the pipeline that `ColumnArg` already uses.

### Generated SQL shape

```sql
SELECT ...
FROM kompetanseregelverk
WHERE (organisasjonskode, regelverksamling_kode, kompetanseregelverk_kode)
    IN (('ORG1', 'R1', 'K1'), ('ORG2', 'R2', 'K2'), ...)
```

The tuples are decoded from each base64 node ID by `NodeIdEncoder.hasIds` at runtime
(reusing the same decoder path as `LookupValuesJoinEmitter`).

## Tests

### Classification (pipeline tests, `GraphitronSchemaBuilderTest.TableInputTypeCase`)

**Positive: same-table `[ID!] @nodeId` → `NodeIdInFilterField`**:

Use `kompetanseregelverk` or the Sakila `film` table (which is `@node`-typed) as the
filter target. Verify:
- Input type classifies as `TableInputType`, not `UnclassifiedType`.
- The `[ID!] @nodeId` field resolves as `NodeIdInFilterField`.
- `nodeKeyColumns` matches the target type's `@node(keyColumns:)`.

**Negative: target type not `@node` → `Unresolved` with clear message**:

Same-table reference where the target type has `@table` but no `@node` metadata.
Assert `UnclassifiedType` with message containing "no @node key metadata".

**No change to cross-table cases**: existing `IdReferenceField` test cases must
continue to pass (same-table guard is tested before FK inference; guard is
case-insensitive comparison of table names).

### `BuildContextTest` (unit)

`classifyInputField_sameTableNodeId_producesNodeIdInFilterField`: build a minimal
schema and catalog where the filter input's table and `@nodeId(typeName:)` both
resolve to the same table name; assert the resulting field is
`InputField.NodeIdInFilterField` with correct `nodeTypeId` and `nodeKeyColumns`.

### `TypeConditionsGeneratorTest` (structural)

- `nodeIdInFilter_singleColumn_emitsHasIdsWithOneCol`: a `BodyParam.NodeIdIn` with
  one key column emits `condition.and(ids == null || ids.isEmpty() ? DSL.noCondition() : NodeIdEncoder.hasIds("Foo", ids, table.ID))`
  in the generated condition method.
- `nodeIdInFilter_compositeColumns_emitsHasIdsWithAllCols`: multi-column
  composite PK includes every column in declaration order.
- `nodeIdInFilter_methodParamIsListOfString`: generated method signature has
  `List<String> ids` (not `List<Integer>` or any column-type-specific class).
- `mixedFilter_columnEqAndNodeIdIn_bothEmitted`: a single condition method with
  both a `ColumnEq` and a `NodeIdIn` `BodyParam` emits both `.and(...)` clauses,
  in declaration order.

### Compilation tier (`graphitron-test`)

Add a fixture schema with a same-table `[ID!] @nodeId` filter input on a `@node`
table. Verify the generated `<Type>Conditions.java` compiles under `<release>17</release>`
and that `<Type>Fetchers.java` calls `<field>Condition(table, env)` as today.

### Execution tier (`graphitron-test`, integration with Testcontainers)

`sameTableNodeIdFilter_returnsRowsMatchingDecodedIds`: insert N rows, encode K of
their PKs as base64 node IDs (via `NodeIdEncoder.encode`), pass the K IDs into the
filter input, assert the result is exactly those K rows. Cover both single-column
and composite-PK tables.

`sameTableNodeIdFilter_emptyListReturnsAllRows` (sanity): an empty `ids` list (or
`null`) routes through `DSL.noCondition()` and does not silently filter to zero
rows. (This is the *existing* `hasIds` semantics; the test pins it so a future
refactor doesn't change it accidentally.)

## Success Criteria

- [ ] `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes
- [ ] `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` passes
- [ ] New `TableInputTypeCase` constants pass
- [ ] All four `opptak-subgraph` filter inputs (`KompetanseregelverkV2FilterInput`,
  `RangeringsregelverkFilterInput`, `RangeringsregelverkV2FilterInput`,
  `RegelverksamlingFilterInput`) no longer emit `[author-error]` for their `[ID!]` fields
- [ ] `TypeConditionsGeneratorTest` new cases pass; existing cases pass with
  `BodyParam.ColumnEq` rename
- [ ] Compilation tier: generated `<Type>Conditions.java` compiles under Java 17
- [ ] Execution tier: same-table `[ID!] @nodeId` filter end-to-end test passes
  against PostgreSQL (Testcontainers); the SQL produced contains the expected
  `(pk1, pk2, ...) IN (...)` predicate
- [ ] Existing cross-table `IdReferenceField` tests pass unchanged
