# GEP-002: Selection-Set-Driven Query Generation

**Status:** Draft
**Version:** 2.0

-----

## Goal

Use GraphQL's selection set to generate jOOQ queries that fetch only the columns the client requested. This eliminates over-fetching at the database level.

-----

## Architecture Decision: Inline Everything

Generate the jOOQ code directly in the DataFetcher. No separate service layer. No reusable column-builder classes. Each DataFetcher is self-contained.

**Why no code reuse?**

- It's generated code, not hand-maintained—duplication costs nothing
- Each DataFetcher reads top-to-bottom without jumping to other files
- Discourages developers from depending on generated internals
- Easier to debug: what you see is what runs

-----

## Generated DataFetcher Structure

```java
public class UsersQueryDataFetcher implements DataFetcher<Result<?>> {
    private final DSLContext ctx;
    
    @Override
    public Result<?> get(DataFetchingEnvironment env) {
        var selection = env.getSelectionSet();
        var columns = new ArrayList<Field<?>>();
        
        if (selection.contains("id")) columns.add(USERS.ID);
        if (selection.contains("name")) columns.add(USERS.NAME);
        if (selection.contains("email")) columns.add(USERS.EMAIL_ADDRESS);
        if (selection.contains("orders")) {
            columns.add(buildOrdersMultiset(selection.getField("orders")));
        }
        
        return ctx.select(columns).from(USERS).fetch();
    }
    
    private Field<?> buildOrdersMultiset(SelectedField ordersField) {
        var selection = ordersField.getSelectionSet();
        var columns = new ArrayList<Field<?>>();
        
        if (selection.contains("id")) columns.add(ORDERS.ID);
        if (selection.contains("status")) columns.add(ORDERS.STATUS);
        if (selection.contains("total")) columns.add(ORDERS.TOTAL);
        
        return multiset(
            select(columns)
            .from(ORDERS)
            .where(ORDERS.USER_ID.eq(USERS.ID))
        ).as("orders");
    }
}
```

**Key points:**

- Mappings are inlined: `"email"` → `USERS.EMAIL_ADDRESS` is resolved at generation time
- Helper methods return `Field<?>` (type-erased) to avoid jOOQ generic type complexity
- Each nesting level gets its own helper method
- GraphQL-Java's `SelectedField` carries arguments and nested selection

-----

## Handling Depth with Type Erasure

jOOQ's generic types compound with nesting depth, causing slow compile times. Break the chain at method boundaries:

```java
// Type-erased: returns Field<?>
private Field<?> buildOrdersMultiset(SelectedField ordersField) { ... }

// Type-erased: returns List<Field<?>>
private List<Field<?>> buildColumns(DataFetchingFieldSelectionSet selection) { ... }
```

Each helper method is private to the DataFetcher. The DataFetcher owns its query logic entirely.

-----

## Use GraphQL-Java Directly

Don't wrap GraphQL-Java's interfaces. Use them as-is:

| Interface | Where Used |
|-----------|------------|
| `DataFetchingEnvironment` | Root DataFetcher `get()` method |
| `DataFetchingFieldSelectionSet` | Top-level selection |
| `SelectedField` | Nested fields, carries arguments |

These interfaces are well-designed, easy to mock, and maintained by the GraphQL-Java team.

```java
// Root receives DataFetchingEnvironment
public Result<?> get(DataFetchingEnvironment env) {
    var selection = env.getSelectionSet();
    // ...
}

// Nested receives SelectedField
private Field<?> buildOrdersMultiset(SelectedField ordersField) {
    var selection = ordersField.getSelectionSet();
    var status = ordersField.getArguments().get("status");
    // ...
}
```

-----

## Result Traversal

The DataFetcher returns jOOQ Records. GraphQL-Java traverses the result. Generated wiring extracts values:

```java
RuntimeWiring.newRuntimeWiring()
    .type("User", builder -> builder
        .dataFetcher("id", env -> ((Record) env.getSource()).get(USERS.ID))
        .dataFetcher("name", env -> ((Record) env.getSource()).get(USERS.NAME))
        .dataFetcher("email", env -> ((Record) env.getSource()).get(USERS.EMAIL_ADDRESS))
        .dataFetcher("orders", env -> ((Record) env.getSource()).get("orders", Result.class))
    )
    .build();
```

No mapping logic in the DataFetcher. It builds the query, executes it, returns Records. The wiring handles extraction.

-----

## Summary

| Aspect | Decision |
|--------|----------|
| Code organization | Inline in DataFetcher, no reuse |
| Type complexity | Type-erased helper methods (`Field<?>`) |
| GraphQL-Java | Use interfaces directly, don't wrap |
| Mappings | Resolved at generation time, inlined |
| Result handling | Return Records, wiring extracts values |

-----

**See also:**
- GEP-001: Graphitron Mental Model
- GEP-002: Parse-and-Validate Architecture
