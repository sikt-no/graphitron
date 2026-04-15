# OrderBy Implementation Plan

## Current state

`resolveOrderByArgSpec` in `FieldBuilder` builds an `OrderBySpec.Argument` with:

- `name`, `typeName`, `nonNull`, `list`, `sortFieldName`, `directionFieldName` — all resolved
- `namedOrders` — always `List.of()` (**deferred**)
- `base` — a `Fixed` fallback or **null** (no PK and no `@defaultOrder`)

`buildOrderByCode(OrderBySpec orderBy)` in `TypeFetcherGenerator` handles the `Argument` case by
falling back to `base` (or `List.of()` when base is null). It never reads the GraphQL argument.

---

## Step 1 — Fix `OrderBySpec.Argument.base` nullability

`base: Fixed` (nullable) is the only nullable field inside any `OrderBySpec` variant. Change it to
`base: OrderBySpec` (non-null; `OrderBySpec.None` when no fallback exists). This aligns with how
every other "absent" concept is modelled in the sealed hierarchy.

**Changes:**

`OrderBySpec.Argument`:
```java
record Argument(
    ...
    List<NamedOrder> namedOrders,
    OrderBySpec base          // was: Fixed base (nullable)
) implements OrderBySpec {}
```

`FieldBuilder.resolveOrderByArgSpec`: remove the downcast — `baseSpec` is already `OrderBySpec`:
```java
// Before:
OrderBySpec.Fixed base = baseSpec instanceof OrderBySpec.Fixed f ? f : null;
return new OrderBySpec.Argument(..., List.of(), base);

// After:
return new OrderBySpec.Argument(..., List.of(), baseSpec);
```

`TypeFetcherGenerator.buildOrderByCode`: replace the null guard with a recursive switch call —
`buildOrderByCode(arg.base())` already handles `Fixed` and `None` correctly:
```java
case OrderBySpec.Argument arg ->
    code.add(buildOrderByCode(arg.base()));
```

Any test constructing `OrderBySpec.Argument` with a null base must pass `new OrderBySpec.None()`
instead.

---

## Step 2 — Populate `namedOrders` in `resolveOrderByArgSpec`

Each value in the `@orderBy` input type's sort enum carries a `@order` directive that specifies
the column(s) to sort by. Populating `namedOrders` means resolving each `@order` directive into a
`Fixed` order.

**Location:** `FieldBuilder.resolveOrderByArgSpec`, after identifying `sortFieldName`.

```java
// After resolving sortFieldName:
GraphQLEnumType sortEnum = (GraphQLEnumType) GraphQLTypeUtil.unwrapNonNull(
    inputType.getFieldDefinition(sortFieldName).getType());
var namedOrders = new ArrayList<OrderBySpec.NamedOrder>();
for (var value : sortEnum.getValues()) {
    if (!value.hasAppliedDirective("order")) continue;
    OrderBySpec.Fixed order = resolveEnumValueOrderSpec(value, tableSqlName, errors);
    if (order == null) return null;   // error already appended
    namedOrders.add(new OrderBySpec.NamedOrder(value.getName(), order));
}
```

`resolveEnumValueOrderSpec` is a new private method that reads the `@order` directive on an enum
value using the same logic as `resolveColumnOrderSpec`/`resolveDefaultOrderSpec` (index, fields,
primaryKey). Extract the shared resolution logic to avoid duplication.

---

## Step 3 — Implement `buildOrderByCode` for `Argument`

With `namedOrders` populated and `base` non-null, the generator can emit real code.

### Generated code shape (single-value, non-list arg)

```java
Map<String, Object> orderByArg = env.getArgument("orderBy");
List<SortField<?>> orderBy;
if (orderByArg == null) {
    orderBy = List.of(table.TITLE.asc());   // from base
} else {
    String orderByField = (String) orderByArg.get("field");
    String orderByDir   = (String) orderByArg.get("direction");
    orderBy = switch (orderByField) {
        case "TITLE"       -> List.of(orderByDir.equals("DESC") ? table.TITLE.desc() : table.TITLE.asc());
        case "RELEASE_YEAR" -> List.of(orderByDir.equals("DESC") ? table.RELEASE_YEAR.desc() : table.RELEASE_YEAR.asc());
        default -> List.of(table.TITLE.asc());  // base fallback
    };
}
```

### Generated code shape (list arg)

```java
List<Map<String, Object>> orderByArgs = env.getArgument("orderBy");
List<SortField<?>> orderBy;
if (orderByArgs == null || orderByArgs.isEmpty()) {
    orderBy = List.of(table.TITLE.asc());   // from base
} else {
    var parts = new ArrayList<SortField<?>>();
    for (var entry : orderByArgs) {
        String f = (String) entry.get("field");
        String d = (String) entry.get("direction");
        switch (f) {
            case "TITLE"  -> parts.add(d.equals("DESC") ? table.TITLE.desc() : table.TITLE.asc());
            // ...
        }
    }
    orderBy = parts;
}
```

### CallParam connection

`buildArgExtraction` is for method parameters. For the inline `orderBy` variable, call
`env.getArgument(arg.name())` directly — no `CallParam` indirection needed. This is consistent
with how `condition` variables are emitted inline rather than through a call-site extraction.

The "extraction step as a CallParam" formulation from earlier planning was an over-abstraction for
this case: the value isn't being passed to a user method, so the unified extraction infrastructure
doesn't apply. The principle still holds for service/condition/table-method call sites.

---

## Step 4 — Direction field handling

`directionFieldName` is always present on the input type (validated in `resolveOrderByArgSpec`).
At generation time, the direction value is a raw GraphQL enum value — a String like `"ASC"` or
`"DESC"`. The simplest approach is `"DESC".equals(dir) ? column.desc() : column.asc()`, applied
per `NamedOrder.order.jooqMethodName()`.

For multi-column `Fixed` orders inside a `NamedOrder`, apply the same direction to all columns
(override `jooqMethodName()`).

---

## Step 5 — Testing

- **Validation test**: `FieldBuilder` test with a schema containing `@orderBy` verifies that
  `namedOrders` is populated correctly (size, names, column references).
- **Generator test**: `TypeFetcherGeneratorTest` verifies that a field with `OrderBySpec.Argument`
  produces a fetcher method with the correct parameter signature and `orderBy` variable declared.
  Do not assert on the switch body — that is verified by compilation and execution tests.
- **Compilation test**: `graphitron-rewrite-test-spec mvn compile` catches type errors in the
  generated `Map<String, Object>` access and `SortField<?>` construction.

---

## Order of implementation

1. Step 1 (base non-null) — independent, small; do first to unblock clean recursion
2. Step 2 (namedOrders population) — builder-only change; no generator impact yet
3. Step 3+4 (generator) — depends on steps 1 and 2
4. Step 5 (tests) — alongside each step
