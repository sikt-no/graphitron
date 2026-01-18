# GEP-003: Selection-Set-Driven Query Generation

**Status:** Draft
**Version:** 1.0

-----

## Goal

Use GraphQL's selection set to generate jOOQ queries that fetch only the columns the client requested. This eliminates over-fetching at the database level.

-----

## Current Behavior

Graphitron currently fetches all columns regardless of what the client requested:

```graphql
query {
  users {
    id
    name
  }
}
```

Generates:

```sql
SELECT users.id, users.name, users.email, users.created_at, users.updated_at, ...
FROM users
```

**Problem:** The database returns columns that are never used in the response.

-----

## Proposed Behavior

Parse the selection set at runtime and build queries that fetch only requested columns:

```graphql
query {
  users {
    id
    name
  }
}
```

Generates:

```sql
SELECT users.id, users.name
FROM users
```

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
public class UsersQueryDataFetcher implements DataFetcher<Result<Record>> {
    private final DSLContext ctx;

    @Override
    public Result<Record> get(DataFetchingEnvironment env) {
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
public Result<Record> get(DataFetchingEnvironment env) {
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

## Field Name Mapping

The code generator resolves GraphQL field names to jOOQ columns at generation time:

```graphql
type User {
  id: ID!
  name: String!
  email: String! @field(name: "EMAIL_ADDRESS")
}
```

Generates:

```java
if (selection.contains("email")) columns.add(USERS.EMAIL_ADDRESS);
```

The mapping from `"email"` to `USERS.EMAIL_ADDRESS` is hardcoded in the generated DataFetcher.

-----

## Handling @splitQuery

For fields marked with `@splitQuery`, generate separate DataFetchers that use DataLoader batching:

```graphql
type User {
  id: ID!
  orders: [Order!]! @splitQuery
}
```

The `orders` field gets its own DataFetcher:

```java
public class UserOrdersDataFetcher implements DataFetcher<CompletableFuture<List<Record>>> {
    @Override
    public CompletableFuture<List<Record>> get(DataFetchingEnvironment env) {
        Record user = env.getSource();
        Integer userId = user.get(USERS.ID);

        DataLoader<Integer, List<Record>> loader = env.getDataLoader("UserOrders");
        return loader.load(userId);
    }
}
```

The DataLoader implementation:

```java
public class UserOrdersDataLoader implements BatchLoader<Integer, List<Record>> {
    @Override
    public CompletableFuture<List<List<Record>>> load(List<Integer> userIds) {
        var selection = /* extract from context */;
        var columns = buildOrderColumns(selection);

        // Batch query
        Map<Integer, List<Record>> ordersByUserId = ctx
            .select(columns)
            .select(ORDERS.USER_ID)
            .from(ORDERS)
            .where(ORDERS.USER_ID.in(userIds))
            .fetch()
            .intoGroups(ORDERS.USER_ID);

        // Return in same order as input
        return CompletableFuture.completedFuture(
            userIds.stream()
                .map(id -> ordersByUserId.getOrDefault(id, List.of()))
                .toList()
        );
    }

    private List<Field<?>> buildOrderColumns(DataFetchingFieldSelectionSet selection) {
        var columns = new ArrayList<Field<?>>();
        if (selection.contains("id")) columns.add(ORDERS.ID);
        if (selection.contains("status")) columns.add(ORDERS.STATUS);
        if (selection.contains("total")) columns.add(ORDERS.TOTAL);
        return columns;
    }
}
```

-----

## Benefits

| Aspect | Current | Proposed |
|--------|---------|----------|
| **Database** | Fetches all columns | Fetches only requested columns |
| **Network** | Full rows to app server | Minimal rows to app server |
| **Memory** | Full records in memory | Minimal records in memory |
| **Performance** | Constant overhead | Scales with query complexity |

**When it matters most:**
- Tables with many columns (50+ fields)
- Large text/JSON columns that are rarely requested
- High-traffic endpoints where bandwidth matters
- Queries that request few fields from wide tables

-----

## Trade-offs

**Costs:**
- More complex generated code (selection set parsing)
- Slightly slower DataFetcher execution (runtime selection checking)
- Cannot rely on database query plan caching (queries vary by selection)

**When to use:**
- Wide tables where most fields are optional
- Large blob/text columns
- High-volume APIs where bandwidth costs matter

**When NOT to use:**
- Narrow tables (5-10 columns) where overhead exceeds benefit
- Queries that always request most fields anyway
- Development environments where simplicity > optimization

-----

## Implementation Phases

1. **Phase 1:** Generate selection-aware DataFetchers for simple queries (no joins)
2. **Phase 2:** Handle nested selections with multiset
3. **Phase 3:** Integrate with @splitQuery DataLoaders
4. **Phase 4:** Add configuration flag to enable/disable per-type or globally

-----

## Configuration

Add schema directive to enable per-type:

```graphql
type User @selectiveQuery {
  id: ID!
  name: String!
  email: String!
  biography: String  # Large text field - good candidate
  orders: [Order!]!
}
```

Or enable globally via Maven configuration:

```xml
<configuration>
  <selectiveQueryGeneration>true</selectiveQueryGeneration>
</configuration>
```

-----

## Summary

| Aspect | Decision |
|--------|----------|
| Code organization | Inline in DataFetcher, no reuse |
| Type complexity | Type-erased helper methods (`Field<?>`) |
| GraphQL-Java | Use interfaces directly, don't wrap |
| Mappings | Resolved at generation time, inlined |
| Selection parsing | Runtime, per-request |
| Configuration | Per-type directive or global flag |

-----

**See also:**
- GEP-001: Parse-and-Validate Architecture
- GEP-002: Simplify Mapping with JooqRecordDataFetcher
