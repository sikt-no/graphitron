---
title: "Same-table `[ID!] @nodeId` filter → primary-key IN predicate"
status: Spec
bucket: architecture
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
node IDs" — a primary-key IN predicate, not a foreign-key join.

The existing `@reference` workaround suggestion in the error message is a dead end;
`parsePath` rejects same-table paths via its `!startSqlTableName.equalsIgnoreCase(targetSqlTableName)`
guard.

## Affected fields (from `opptak-subgraph`)

- `KompetanseregelverkV2FilterInput.kompetanseregelverkIds`
- `RangeringsregelverkFilterInput.rangeringsregelverkIds`
- `RangeringsregelverkV2FilterInput.rangeringsregelverkIds`
- `RegelverksamlingFilterInput.ids`

All four share the same pattern: a filter input for type X has a `[ID!]
@nodeId(typeName: "X")` field referencing the same type.

## Root Cause

In `BuildContext.classifyInputField` (line 801), the canonical `[ID!] @nodeId` branch:

1. Resolves `targetTable` from `@nodeId(typeName: T)` — in this case same as
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
 * to rows whose composite primary key matches one of these decoded node IDs" —
 * a primary-key IN predicate, not a FK join.
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
(the input-field set that never flows through type dispatch). Add a no-op arm to every
exhaustive switch site:

- `GraphitronSchemaValidator.java` — `case InputField.NodeIdInFilterField ignored -> {}`
- `FieldBuilder.walkInputFieldConditions` — `case InputField.NodeIdInFilterField ignored -> {}`

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

## Codegen (deferred)

Filter predicate generation for `NodeIdInFilterField` — emitting
`WHERE (pk_col1, pk_col2) IN (...)` — is a follow-up, the same way `IdReferenceField`
classification landed before its `has*()` codegen. Classification succeeds; the field
appears in the built model; code generation for the IN predicate is added when the
filter codegen layer is implemented for this variant.

The generated SQL shape when codegen lands:

```sql
WHERE (organisasjonskode, regelverksamling_kode, kompetanseregelverk_kode)
    IN (('ORG1', 'R1', 'K1'), ('ORG2', 'R2', 'K2'), ...)
```

Each tuple is decoded from a base64 node ID using `NodeIdStrategy.unpackIdValues` (the
same decoder used by `NodeIdField` for scalar `id: ID` filters).

## Tests

### Classification (pipeline tests, `GraphitronSchemaBuilderTest.TableInputTypeCase`)

**Positive — same-table `[ID!] @nodeId` → `NodeIdInFilterField`**:

Use `kompetanseregelverk` or the Sakila `film` table (which is `@node`-typed) as the
filter target. Verify:
- Input type classifies as `TableInputType`, not `UnclassifiedType`.
- The `[ID!] @nodeId` field resolves as `NodeIdInFilterField`.
- `nodeKeyColumns` matches the target type's `@node(keyColumns:)`.

**Negative — target type not `@node` → `Unresolved` with clear message**:

Same-table reference where the target type has `@table` but no `@node` metadata.
Assert `UnclassifiedType` with message containing "no @node key metadata".

**No change to cross-table cases**: existing `IdReferenceField` test cases must
continue to pass (same-table guard is tested before FK inference; guard is
case-insensitive comparison of table names).

### `ServiceCatalogTest` / `BuildContextTest`

Unit test `classifyInputField_sameTableNodeId_producesNodeIdInFilterField`:
build a minimal schema and catalog where the filter input's table and `@nodeId(typeName:)`
both resolve to the same table name; assert the resulting field is
`InputField.NodeIdInFilterField` with correct `nodeKeyColumns`.

## Success Criteria

- [ ] `mvn compile -pl graphitron-rewrite` passes
- [ ] `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` passes
- [ ] New `TableInputTypeCase` constants pass
- [ ] All four `opptak-subgraph` filter inputs (`KompetanseregelverkV2FilterInput`,
  `RangeringsregelverkFilterInput`, `RangeringsregelverkV2FilterInput`,
  `RegelverksamlingFilterInput`) no longer emit `[author-error]` for their `[ID!]` fields
- [ ] Existing cross-table `IdReferenceField` tests pass unchanged
