# Taxonomy Improvements

Concrete issues in the `GraphitronSchema` / `GraphitronType` / `GraphitronField` model and their fixes.

## 1. `fieldCoordinates` is split-brained

Some types carry `fieldCoordinates: List<FieldCoordinates>` and others don't:

| Has it | Missing it |
|---|---|
| `TableType`, `NodeType`, `RootType`, all four `ResultType` variants | `TableInterfaceType`, `InterfaceType`, `UnionType`, `ErrorType`, all `InputType` variants |

Meanwhile `GraphitronSchema.fieldsOf(typeName)` does a full linear scan of the fields map filtering by `parentTypeName()`. So there are two parallel mechanisms for "which fields belong to this type" — and they disagree on coverage.

**Fix:** Remove `fieldCoordinates` from individual type records. The schema's field map is the single source of truth. Replace `fieldsOf()` with a pre-grouped lookup (see #2 below). This eliminates the split-brain and removes a graphql-java type (`FieldCoordinates`) from the model records.

If `fieldCoordinates` serves a purpose beyond field lookup (e.g., preserving declaration order when the field map doesn't), that purpose should be documented and the component should be on *all* types, not a subset.

## 2. `fieldsOf()` is O(n) on every call

```java
public List<GraphitronField> fieldsOf(String typeName) {
    return fields.values().stream()
        .filter(f -> f.parentTypeName().equals(typeName))
        .toList();
}
```

Every generator calls this once per type. With N types and M total fields, the full generation pass is O(N*M).

**Fix:** Pre-group fields by `parentTypeName` at construction time:

```java
public record GraphitronSchema(
    Map<String, GraphitronType> types,
    Map<FieldCoordinates, GraphitronField> fields,
    Map<String, List<GraphitronField>> fieldsByType  // pre-grouped, declaration order
) {
    public List<GraphitronField> fieldsOf(String typeName) {
        return fieldsByType.getOrDefault(typeName, List.of());
    }
}
```

Or compute `fieldsByType` lazily in the constructor from the fields map. Either way, `fieldsOf()` becomes O(1).

This also makes removal of `fieldCoordinates` from type records safe — the pre-grouped map preserves the same information with O(1) access.

## 3. Absence handling is inconsistent

| Concept | How absence is represented |
|---|---|
| No PK columns | Empty `List<ColumnRef>` on `TableRef` |
| No filters | Empty `List<WhereFilter>` |
| No join path | Empty `List<JoinStep>` |
| No ordering | `OrderBySpec.None` sealed variant |
| No pagination | **Null** `PaginationSpec` on the field |
| No fallback order | **Null** `base` inside `OrderBySpec.Argument` |

Three patterns: empty collection, sealed `None` variant, and null. `OrderBySpec.None` is the right approach — it uses the type system, requires no null checks, and the compiler enforces exhaustive handling.

**Fix for `PaginationSpec`:** Add a `None` variant or use `Optional`. Since `PaginationSpec` is not a sealed interface today (it's a plain record), the simplest change is to make it a sealed interface with `Paginated` and `None` variants:

```java
public sealed interface PaginationSpec {
    record Paginated(PaginationArg first, PaginationArg last, PaginationArg after, PaginationArg before) implements PaginationSpec {}
    record None() implements PaginationSpec {}
}
```

Fields that currently carry `PaginationSpec pagination` (nullable) would carry `PaginationSpec pagination` (non-null, `None` when absent). This aligns with how `OrderBySpec` already works.

**Fix for `OrderBySpec.Argument.base`:** Replace the nullable `Fixed base` with a non-null `OrderBySpec base` that is `OrderBySpec.None` when no fallback exists. This removes the only nullable field inside any `OrderBySpec` variant.

## 4. `GraphQLFieldDefinition` leaking into the model

`UnclassifiedField` carries `definition: GraphQLFieldDefinition` — a raw graphql-java AST type. The Javadoc says it's "possibly null when constructed outside the schema-building pipeline (e.g. in tests)."

This violates the design principle that only builders hold raw graphql-java types. The `definition` is used for error reporting (source location, field name in messages).

**Fix:** Extract what's needed from the definition at classify time. `UnclassifiedField` already carries `location: SourceLocation` and `name: String`. If no other information from the definition is used downstream, remove the `definition` component entirely.

## 5. `ConditionJoin` lacks `targetTable`

`FkJoin` carries `targetTable: TableRef` but `ConditionJoin` doesn't. The Javadoc says "condition method resolution (P3) will provide it." Any code traversing a join path must handle both variants differently.

**Fix (when P3 lands):** Add `targetTable: TableRef` to `ConditionJoin`. Until then, document the asymmetry explicitly in the `JoinStep` Javadoc so generators don't silently ignore it.

## 6. Minor naming and redundancy

**`QueryTableMethodTableField`** has "Table" twice. Consider `QueryTableMethodField` to match the child-field counterpart `TableMethodField`.

**`NodeIdReferenceField.typeName`** is redundant with `targetType.returnTypeName()`. Remove the raw string component; access the type name through the resolved `ReturnTypeRef`.

## Priority

| Order | Issue | Impact | Effort |
|---|---|---|---|
| 1 | Pre-group `fieldsOf()` (O(1) lookup) | Performance + enables #2 | Small |
| 2 | Remove `fieldCoordinates` from type records | Eliminates split-brain, removes graphql-java leak | Small (after #1) |
| 3 | `PaginationSpec` sealed with `None` variant | Consistent absence handling, no null checks | Small |
| 4 | `OrderBySpec.Argument.base` non-null | Removes nullable field inside sealed variant | Trivial |
| 5 | Remove `UnclassifiedField.definition` | Removes graphql-java AST leak | Small |
| 6 | `ConditionJoin.targetTable` | Unblocks join-path traversal | Deferred to P3 |
| 7 | Rename `QueryTableMethodTableField` | Naming consistency | Trivial |
| 8 | Remove `NodeIdReferenceField.typeName` | Removes redundant component | Trivial |

Items 1-2 should be done together. Items 3-4 are independent and can be done anytime. Items 5-8 are polish.
