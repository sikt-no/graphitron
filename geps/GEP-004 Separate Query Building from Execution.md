# GEP-004: Separate Query Building from Execution

**Status:** Draft
**Version:** 1.0

-----

## Goal

Separate query building from query execution by introducing a `GraphitronDataFetcher` that executes queries built by pure query builder classes. This enables centralized handling of sorting, pagination, multi-tenancy, and other cross-cutting query modifications.

-----

## The Problem

Currently (after GEP-002), DataFetchers build AND execute queries:

```java
public class UsersQueryDataFetcher implements DataFetcher<Result<Record>> {
    private final DSLContext ctx;

    @Override
    public Result<Record> get(DataFetchingEnvironment env) {
        // Build query
        var query = ctx.select(USERS.fields())
                       .from(USERS);

        // Apply ordering
        if (env.containsArgument("orderBy")) {
            query = applyOrdering(query, env.getArgument("orderBy"));
        }

        // Apply pagination
        if (env.containsArgument("first")) {
            query = query.limit(env.getArgument("first"));
        }

        // Execute
        return query.fetch();
    }
}
```

**Problems:**
1. **Ordering logic duplicated** - Every query that supports ordering repeats this logic
2. **Pagination logic duplicated** - Forward/backward pagination code repeated
3. **Cross-cutting concerns scattered** - Multi-tenancy, security filters spread across queries
4. **Hard to add global modifications** - Want to add logging? Touch every query
5. **DSLContext lifecycle** - Need to inject context into every DataFetcher class

-----

## The Solution

Separate query building (pure function) from execution (cross-cutting concerns):

```java
// Query builder - pure function, no execution
public class UsersQuery {
    public static ResultQuery<Record> build(DataFetchingEnvironment env) {
        return DSL.select(USERS.fields())
                  .from(USERS);
        // Just builds base query, doesn't apply ordering/pagination/execution
    }
}

// Generic execution - handles all cross-cutting concerns
public class GraphitronDataFetcher implements DataFetcher<Result<Record>> {
    private final Function<DataFetchingEnvironment, ResultQuery<Record>> queryBuilder;

    @Override
    public Result<Record> get(DataFetchingEnvironment env) {
        // Extract context from environment
        DSLContext ctx = env.getGraphQlContext().get("dslContext");

        // Build base query (pure function)
        ResultQuery<Record> query = queryBuilder.apply(env);

        // Apply cross-cutting modifications
        query = applyOrdering(query, env);
        query = applyPagination(query, env);
        query = applyTenantFilter(query, env);

        // Execute
        return query.fetch();
    }
}
```

**Key insight:** Query builders produce the base query. GraphitronDataFetcher applies modifications and executes.

-----

## Architecture

### Query Builders (Pure Functions)

```java
public class UsersQuery {
    public static ResultQuery<Record> build(DataFetchingEnvironment env) {
        // Build base query using static DSL methods
        SelectJoinStep<Record> query = DSL.select(USERS.fields())
                                          .from(USERS);

        // Apply filters from arguments
        if (env.containsArgument("status")) {
            query = query.where(USERS.STATUS.eq(env.getArgument("status")));
        }

        return query;
    }
}
```

**Characteristics:**
- Static method (no state)
- Input: `DataFetchingEnvironment`
- Output: jOOQ `ResultQuery<Record>`
- Uses static `DSL.*` methods (no DSLContext needed)
- Applies argument-based filters
- Does NOT apply ordering, pagination, or execute

### GraphitronDataFetcher (Execution Handler)

```java
public class GraphitronDataFetcher implements DataFetcher<Result<Record>> {
    private final Function<DataFetchingEnvironment, ResultQuery<Record>> queryBuilder;

    public GraphitronDataFetcher(
        Function<DataFetchingEnvironment, ResultQuery<Record>> queryBuilder
    ) {
        this.queryBuilder = queryBuilder;
    }

    @Override
    public Result<Record> get(DataFetchingEnvironment env) {
        // 1. Extract DSLContext
        DSLContext ctx = env.getGraphQlContext().get("dslContext");

        // 2. Build base query
        ResultQuery<Record> query = queryBuilder.apply(env);

        // 3. Apply cross-cutting modifications
        query = applyOrdering(query, env);
        query = applyPagination(query, env);
        query = applyMultiTenancy(query, env);

        // 4. Execute
        return query.fetch();
    }
}
```

**Responsibilities:**
- Extract DSLContext from environment
- Call query builder to get base query
- Apply ordering (if `orderBy` argument present)
- Apply pagination (if `first`/`last`/`after`/`before` present)
- Apply multi-tenancy filters
- Execute query
- Handle any execution-level concerns

-----

## Sorting Implementation

### Schema with @orderBy

```graphql
type Query {
  users(orderBy: UserOrder): [User!]!
}

input UserOrder @orderBy(on: "User") {
  name: SortDirection
  createdAt: SortDirection
}

enum SortDirection {
  ASC
  DESC
}
```

### Query Builder (Ignores Ordering)

```java
public class UsersQuery {
    public static ResultQuery<Record> build(DataFetchingEnvironment env) {
        return DSL.select(USERS.fields())
                  .from(USERS);
        // No .orderBy() applied here
    }
}
```

### GraphitronDataFetcher Applies Ordering

```java
private ResultQuery<Record> applyOrdering(ResultQuery<Record> query, DataFetchingEnvironment env) {
    if (!env.containsArgument("orderBy")) {
        return query;
    }

    Map<String, String> orderBy = env.getArgument("orderBy");

    // Convert to jOOQ order by clauses
    List<OrderField<?>> orderFields = orderBy.entrySet().stream()
        .map(entry -> {
            TableField<?, ?> field = resolveField(entry.getKey());
            SortOrder order = entry.getValue().equals("ASC") ? SortOrder.ASC : SortOrder.DESC;
            return field.sort(order);
        })
        .collect(Collectors.toList());

    return query.orderBy(orderFields);
}
```

**Benefits:**
- Ordering logic in ONE place
- All queries get ordering support automatically
- Easy to change ordering behavior globally
- Query builders stay simple

-----

## Pagination Implementation

### Schema with Relay Pagination

```graphql
type Query {
  users(first: Int, after: String, last: Int, before: String): UserConnection!
}

type UserConnection {
  edges: [UserEdge!]!
  pageInfo: PageInfo!
}
```

### Query Builder (Ignores Pagination)

```java
public class UsersQuery {
    public static ResultQuery<Record> build(DataFetchingEnvironment env) {
        return DSL.select(USERS.fields())
                  .from(USERS);
        // No LIMIT/OFFSET here
    }
}
```

### GraphitronDataFetcher Applies Pagination

```java
private ResultQuery<Record> applyPagination(ResultQuery<Record> query, DataFetchingEnvironment env) {
    // Forward pagination
    if (env.containsArgument("first")) {
        int limit = env.getArgument("first");
        query = query.limit(limit + 1);  // +1 to detect hasNextPage

        if (env.containsArgument("after")) {
            String cursor = env.getArgument("after");
            Object offset = decodeCursor(cursor);
            query = query.offset(offset);
        }
    }

    // Backward pagination
    if (env.containsArgument("last")) {
        int limit = env.getArgument("last");
        query = query.limit(limit + 1);  // +1 to detect hasPreviousPage

        if (env.containsArgument("before")) {
            String cursor = env.getArgument("before");
            Object offset = decodeCursor(cursor);
            query = query.offset(offset);
        }
    }

    return query;
}
```

**Benefits:**
- Pagination logic in ONE place
- Cursor encoding/decoding centralized
- `hasNextPage`/`hasPreviousPage` calculation consistent
- Easy to switch pagination strategies

-----

## Multi-Tenancy

### Automatic Tenant Filtering

```java
private ResultQuery<Record> applyMultiTenancy(ResultQuery<Record> query, DataFetchingEnvironment env) {
    // Extract tenant ID from context
    String tenantId = env.getGraphQlContext().get("tenantId");

    if (tenantId == null) {
        throw new UnauthorizedException("No tenant context");
    }

    // Detect if query has TENANT_ID field
    // (Could use metadata from GEP-001 config to know which tables have tenant_id)
    if (queryHasTenantColumn(query)) {
        return query.where(TENANT_ID.eq(tenantId));
    }

    return query;
}
```

**Benefits:**
- Multi-tenancy enforced in ONE place
- Impossible to forget tenant filter
- Works for all queries automatically
- Can be disabled for admin queries if needed

-----

## Field-Level Security

### Row-Level Security Based on Permissions

```java
private ResultQuery<Record> applySecurityFilters(ResultQuery<Record> query, DataFetchingEnvironment env) {
    User user = env.getGraphQlContext().get("user");

    // Example: Users can only see their own data unless admin
    if (!user.isAdmin() && queryIsForUserTable(query)) {
        return query.where(USERS.ID.eq(user.getId()));
    }

    return query;
}
```

**Use cases:**
- Users see only their own orders
- Managers see their department's data
- Admins see everything
- All enforced in one place

-----

## Generated Code Comparison

### Before (GEP-002)

**Generated per query:**
```java
public class UsersQueryDataFetcher implements DataFetcher<Result<Record>> {
    private final DSLContext ctx;

    public UsersQueryDataFetcher(DSLContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public Result<Record> get(DataFetchingEnvironment env) {
        var query = ctx.select(USERS.fields()).from(USERS);

        // Ordering logic (duplicated in every query)
        if (env.containsArgument("orderBy")) {
            query = applyOrdering(query, env.getArgument("orderBy"));
        }

        // Pagination logic (duplicated in every query)
        if (env.containsArgument("first")) {
            query = query.limit(env.getArgument("first"));
        }

        return query.fetch();
    }
}
```

**Repeated for:**
- `OrdersQueryDataFetcher`
- `ProductsQueryDataFetcher`
- `CustomersQueryDataFetcher`
- ... every query

### After (GEP-004)

**Generated per query (much simpler):**
```java
public class UsersQuery {
    public static ResultQuery<Record> build(DataFetchingEnvironment env) {
        return DSL.select(USERS.fields())
                  .from(USERS);
    }
}
```

**ONE shared execution handler:**
```java
public class GraphitronDataFetcher implements DataFetcher<Result<Record>> {
    // Handles ordering, pagination, multi-tenancy for ALL queries
}
```

**Wiring:**
```java
.type("Query", builder -> builder
    .dataFetcher("users", new GraphitronDataFetcher(UsersQuery::build))
    .dataFetcher("orders", new GraphitronDataFetcher(OrdersQuery::build))
    .dataFetcher("products", new GraphitronDataFetcher(ProductsQuery::build))
)
```

**Lines of code reduction:**
- Before: ~50 lines per query (including ordering/pagination logic)
- After: ~10 lines per query (just base query building)
- **80% reduction per query**

-----

## Advanced: Query Inspection

### SQL Logging

```java
@Override
public Result<Record> get(DataFetchingEnvironment env) {
    DSLContext ctx = env.getGraphQlContext().get("dslContext");
    ResultQuery<Record> query = queryBuilder.apply(env);

    query = applyOrdering(query, env);
    query = applyPagination(query, env);

    // Log actual SQL before execution
    logger.debug("Executing SQL: {}", query.getSQL());
    logger.debug("Bind values: {}", query.getBindValues());

    return query.fetch();
}
```

### Query Metrics

```java
@Override
public Result<Record> get(DataFetchingEnvironment env) {
    var timer = metrics.timer("graphql.query.execution").start();

    try {
        DSLContext ctx = env.getGraphQlContext().get("dslContext");
        ResultQuery<Record> query = queryBuilder.apply(env);

        query = applyOrdering(query, env);
        query = applyPagination(query, env);

        Result<Record> result = query.fetch();

        metrics.counter("graphql.query.rows").increment(result.size());

        return result;
    } finally {
        timer.stop();
    }
}
```

### EXPLAIN Plans

```java
@Override
public Result<Record> get(DataFetchingEnvironment env) {
    DSLContext ctx = env.getGraphQlContext().get("dslContext");
    ResultQuery<Record> query = queryBuilder.apply(env);

    query = applyOrdering(query, env);
    query = applyPagination(query, env);

    // Optionally explain query for slow query analysis
    if (shouldExplain(env)) {
        logger.info("EXPLAIN: {}", ctx.explain(query));
    }

    return query.fetch();
}
```

-----

## Integration with @condition and @reference

### Query Builders Handle @condition

```graphql
type User {
  activeOrders: [Order!]! @reference(path: "orders") @condition(method: "isActive")
}
```

**Generated query builder includes condition:**
```java
public class UserActiveOrdersQuery {
    public static ResultQuery<Record> build(DataFetchingEnvironment env) {
        Record user = (Record) env.getSource();
        Integer userId = user.get(USERS.ID);

        return DSL.select(ORDERS.fields())
                  .from(ORDERS)
                  .where(ORDERS.USER_ID.eq(userId))
                  .and(OrderConditions.isActive());  // @condition applied
    }
}
```

**GraphitronDataFetcher still applies ordering/pagination:**
```java
.type("User", builder -> builder
    .dataFetcher("activeOrders", new GraphitronDataFetcher(UserActiveOrdersQuery::build))
)
```

**Result:** Base query includes business logic (@condition), execution adds ordering/pagination.

-----

## DSLContext Lifecycle

### Context Provided via GraphQL Context

**Application setup:**
```java
GraphQL graphQL = GraphQL.newGraphQL(schema)
    .instrumentation(new ContextInstrumentation())
    .build();

// Per-request execution
ExecutionInput input = ExecutionInput.newExecutionInput()
    .query(queryString)
    .graphQLContext(builder -> builder
        .put("dslContext", dslContext)
        .put("tenantId", tenantId)
        .put("user", currentUser)
    )
    .build();

graphQL.execute(input);
```

**GraphitronDataFetcher extracts it:**
```java
DSLContext ctx = env.getGraphQlContext().get("dslContext");
```

**Benefits:**
- Per-request context (can include transaction)
- No constructor injection needed
- Easy to mock in tests
- All execution context in one place

-----

## Mutations

Mutations work the same way:

```java
public class CreateUserMutation {
    public static InsertResultStep<UserRecord> build(DataFetchingEnvironment env) {
        Map<String, Object> input = env.getArgument("input");

        return DSL.insertInto(USERS)
                  .set(USERS.NAME, input.get("name"))
                  .set(USERS.EMAIL, input.get("email"));
    }
}
```

```java
public class GraphitronMutationDataFetcher implements DataFetcher<Result<Record>> {
    @Override
    public Result<Record> get(DataFetchingEnvironment env) {
        DSLContext ctx = env.getGraphQlContext().get("dslContext");
        InsertResultStep<UserRecord> mutation = mutationBuilder.apply(env);

        // Apply tenant ID automatically
        String tenantId = env.getGraphQlContext().get("tenantId");
        mutation = mutation.set(USERS.TENANT_ID, tenantId);

        return mutation.returning().fetch();
    }
}
```

**Benefits:**
- Tenant ID added automatically to all inserts
- Audit fields (created_by, created_at) in one place
- Transaction management centralized

-----

## Benefits Summary

| Aspect | Before (GEP-002) | After (GEP-004) |
|--------|------------------|-----------------|
| **Ordering logic** | Duplicated per query | One place |
| **Pagination logic** | Duplicated per query | One place |
| **Multi-tenancy** | Manual per query | Automatic |
| **Field security** | Manual per query | Centralized |
| **SQL logging** | Add to each query | One place |
| **Query inspection** | Scattered | Centralized |
| **DSLContext injection** | Constructor per query | From environment |
| **Lines per query** | ~50 | ~10 (80% reduction) |

-----

## Implementation Strategy

### Phase 1: Introduce GraphitronDataFetcher (2-3 weeks)

1. Create `GraphitronDataFetcher` class
2. Implement basic execution (no modifications yet)
3. Update one query to use it
4. Verify it works

### Phase 2: Add Ordering Support (1-2 weeks)

1. Implement `applyOrdering()` in GraphitronDataFetcher
2. Remove ordering logic from query builders
3. Test with queries that have `@orderBy`

### Phase 3: Add Pagination Support (2-3 weeks)

1. Implement `applyPagination()` for forward/backward
2. Handle cursor encoding/decoding
3. Test with Relay connections

### Phase 4: Add Cross-Cutting Concerns (1-2 weeks)

1. Implement multi-tenancy filtering
2. Add SQL logging
3. Add metrics/instrumentation

### Phase 5: Migrate All Queries (2-3 weeks)

1. Update generator to produce query builders
2. Use GraphitronDataFetcher in wiring
3. Remove old DataFetcher generation

**Total: 8-13 weeks**

-----

## Trade-offs

### Accept: Additional Indirection

**Cost:** Query → GraphitronDataFetcher → execute (one extra step)
**Impact:** Negligible runtime cost
**Benefit:** Massive simplification, centralized logic

### Accept: jOOQ Query Modification Limitations

**Challenge:** Not all jOOQ query types support all modifications
**Example:** `InsertQuery` doesn't have `.orderBy()`
**Mitigation:** Type-safe builder pattern, different DataFetchers for different query types

### Gain: Architectural Simplicity

**Query builders:**
- ~10 lines each
- Pure functions
- Easy to test
- No duplication

**Execution:**
- One place for all cross-cutting concerns
- Easy to modify globally
- Instrumentation built-in

-----

## Compatibility with GEP-002 and GEP-003

### GEP-002: Simplified Mapping
- Still returns `Result<Record>`
- Still uses RuntimeWiring
- Still delegates to GraphQL-Java traversal
- **GEP-004 just makes query generation cleaner**

### GEP-003: Selection-Set-Driven Queries
- Query builders can still parse selection set
- Build column lists based on requested fields
- GraphitronDataFetcher executes the optimized query
- **GEP-004 makes selection-aware query building easier**

```java
public static ResultQuery<Record> build(DataFetchingEnvironment env) {
    var selection = env.getSelectionSet();
    var columns = new ArrayList<Field<?>>();

    if (selection.contains("id")) columns.add(USERS.ID);
    if (selection.contains("name")) columns.add(USERS.NAME);

    return DSL.select(columns).from(USERS);
}
```

-----

## Summary

| Aspect | Decision |
|--------|----------|
| **Query builders** | Pure functions, no DSLContext, no execution |
| **Execution** | Centralized in GraphitronDataFetcher |
| **Ordering** | Applied by GraphitronDataFetcher |
| **Pagination** | Applied by GraphitronDataFetcher |
| **Multi-tenancy** | Applied by GraphitronDataFetcher |
| **DSLContext** | Extracted from GraphQL context |
| **Code reduction** | 80% per query (50 lines → 10 lines) |

-----

**See also:**
- GEP-001: Parse-and-Validate Architecture
- GEP-002: Simplify Mapping with JooqRecordDataFetcher
- GEP-003: Selection-Set-Driven Query Generation
