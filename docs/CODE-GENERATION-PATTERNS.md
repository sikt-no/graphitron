# Graphitron Code Generation Patterns

> **Quick Navigation:**
> [Overview](#overview) • [Quick Reference](#quick-reference) • [Two Worlds](#the-two-worlds-jooq-land-vs-java-land) • [N+1 Rule](#the-n1-rule) • [Query Patterns](#query-patterns-detailed) • [What Graphitron Generates](#what-graphitron-generates) • [Decision Framework](#decision-framework) • [Common Patterns](#common-patterns) • [Anti-Patterns](#anti-patterns-to-avoid)

---

## Overview

Graphitron generates jOOQ-based query code from your GraphQL schema. Understanding code generation requires grasping three core concepts:

**The Two Worlds:** Graphitron operates in either **jOOQ-land** (generating SQL queries via jOOQ QueryParts) or **Java-land** (custom service methods). The goal is to stay in jOOQ-land as much as possible because it generates optimized SQL with selection-set-driven column selection and built-in N+1 prevention. Use Java-land sparingly as an escape hatch, and return to jOOQ-land via **re-entry** as quickly as possible.

**The N+1 Rule:** Fields on the root Query/Mutation type execute once and can do any work. Nested fields execute N times (once per parent) and must either extract data already fetched by the parent (trivial) or use DataLoader batching (`@splitQuery`). This constraint shapes all code generation decisions.

**Queries vs QueryParts:** Graphitron generates `<Type>Queries` classes that contain methods which either **execute complete SQL queries** (root fields and `@splitQuery`) or **generate jOOQ QueryParts** (inline nested fields). Most of the time, the default inline approach works. Use `@splitQuery` only for large collections or conditional fetching needs.

---

## Quick Reference

### "How do I..." Quick Lookup

| Task | Solution | Details |
|------|----------|---------|
| **Fetch records by known ID(s)** | `@lookupKey` on argument | [Lookup Pattern](#lookup-pattern) |
| **Browse/filter large datasets** | Return `*Connection`, add filter args | [Filter Pattern](#filter-pattern) |
| **Implement text search** | Return `*Connection`, use `@search` | [Search Pattern](#search-pattern) |
| **Fetch small reference data** | Return list directly | [List Pattern](#list-pattern) |
| **Add conditional WHERE clauses** | `@condition` directive | [Conditional Filtering](#pattern-conditional-filtering-with-condition) |
| **Calculate derived fields** | `@externalField` directive | [Calculated Fields](#pattern-calculated-field-with-externalfield) |
| **Break up large nested queries** | `@splitQuery` on field | [Split Query Strategy](#split-query-dataloader-batching) |
| **Call custom Java logic** | `@service` directive (use sparingly) | [Service Call](#service-call-escape-hatch) |
| **Return to DB after service** | Include re-entry fields | [Re-entry](#re-entry-return-to-jooq-land) |

### Directive Quick Reference

| Directive | Purpose | Generated Code Effect |
|-----------|---------|------------------------|
| `@table` | Link type to jOOQ table | Enables jOOQ query generation for this type |
| `@reference` | Declare FK relationship | Inline QueryPart (multiset/row) OR separate query (if `@splitQuery`) |
| `@splitQuery` | Force separate batched query | Generates `fieldNameLoader()` method with DataLoader batching |
| `@service` | Call custom Java method | Escapes SQL generation, calls your service |
| `@condition` | Add WHERE clauses | Returns jOOQ `Condition` QueryPart |
| `@externalField` | Calculated field | Returns jOOQ `Field<T>` QueryPart |
| `@field` | Map field to column/property | Changes field name mapping |
| `@lookupKey` | Lookup by key(s) | Fetch by PK/unique key, ordered results |
| `@orderBy` | Enable sorting | Order by indexed columns |
| `@node` | Global ID support | Relay node interface implementation |
| `@discriminate` | Single-table inheritance | Type resolution via discriminator column |
| `@notGenerated` | Skip generation | You provide custom implementation |

### Query Pattern Selection Guide

| Use Case | Pattern | Return Type | Key Feature |
|----------|---------|-------------|-------------|
| Client has specific IDs | **Lookup** | `[Type]` or `Type` | `@lookupKey`, ordered results |
| Browse with filters | **Filter** | `TypeConnection` | Paginated, start with all |
| Text/relevance search | **Search** | `TypeConnection` | Paginated, `@search` arg |
| Small reference data | **List** | `[Type]` | Simple fetch, use sparingly |

### What Gets Generated

| Schema Element | Class | Method | Executes SQL? | Purpose |
|----------------|-------|--------|---------------|---------|
| Root field `Query.user` | `QueryQueries` | `user(env)` | ✅ Yes | Complete query execution |
| Root field `Mutation.createUser` | `MutationQueries` | `createUser(env)` | ✅ Yes | Complete mutation execution |
| `@splitQuery` field `User.orders` | `UserQueries` | `ordersLoader(env)` | ✅ Yes (batched) | Separate batched query |
| Inline field `User.address` | `UserQueries` | `buildAddressRow(field)` | ❌ No | QueryPart (composes into parent) |
| `@condition` | User class | `isActive(table)` | ❌ No | Returns `Condition` QueryPart |
| `@externalField` | User class | `fullName(table)` | ❌ No | Returns `Field<T>` QueryPart |
| `@service` | User service | `recommendations(user)` | ❌ No | Escapes SQL generation |

### Inline vs Split Query Decision

| Factor | Inline (Default) | Split (`@splitQuery`) |
|--------|------------------|------------------------|
| **Query complexity** | Simple, keeps parent query lean | Breaks up complex queries |
| **Data size** | Small nested collections | Large nested collections |
| **Fetch frequency** | Always needed | Conditionally requested |
| **Round trips** | Single query | Additional round-trip |
| **Method signature** | Helper returning QueryPart | `fieldNameLoader()` returning Map |
| **When to use** | Default choice | Only when needed |

**Default to inline. Use `@splitQuery` when:**
- Nested collections are large and would bloat parent query
- Nested data is rarely requested (conditional fetching)
- Parent query is becoming too complex

---

## The Two Worlds: jOOQ-Land vs Java-Land

### jOOQ-Land (Query and QueryPart Generation)

**What:** Graphitron generates jOOQ queries and QueryParts that compose into SQL
**Why stay here:** Type-safe, efficient, validated at build time

**Characteristics:**
- Graphitron generates optimized SQL queries
- Selection sets drive column selection (no over-fetching)
- Nested data uses `multiset()` and `row()` QueryParts (no N+1 queries)
- Everything validated against your database schema
- Compile-time errors if tables/columns don't exist

**What gets generated:**
- `<Type>Queries` classes with query execution methods
- QueryPart helper methods for inline nested data
- References to external QueryParts (`@condition`, `@externalField`)

**How to stay in jOOQ-land:**
- Use `@table` on types to tie them to jOOQ tables
- Use `@reference` for relationships between tables
- Use `@condition` for filtering logic (returns jOOQ `Condition`)
- Use `@externalField` for calculated fields (returns jOOQ `Field<T>`)

### Java-Land (No SQL Generation)

**What:** Custom Java code, not generating SQL
**Why use sparingly:** Leaves type-safety, risks N+1, limited build-time validation

**Characteristics:**
- You've called a `@service` method with custom Java code
- Graphitron extracts properties from Java objects
- No automatic SQL query generation
- To query the database again, you need **re-entry** back to jOOQ-land

**When to use Java-land:**
- Logic truly doesn't fit the declarative model
- Need to call external services/APIs
- Complex calculations that can't be expressed in SQL

**Re-entry back to jOOQ-land:**
Include fields that enable re-entry in your Java record:
- jOOQ `TableRecord` components (with PK set)
- Relay global ID strings (`String` that decodes to type + PK)

---

## The N+1 Rule

This fundamental constraint shapes all code generation:

| Schema Location | Times Called | Constraint |
|-----------------|--------------|------------|
| **Root** (Query/Mutation) | Once per request | Can do any database work |
| **Non-root** (nested field) | N times (once per parent) | Must be trivial OR DataLoader-backed |

**Implication:** Non-root fields have three options:

1. **Trivial extraction** - Pull data already fetched by parent (no I/O)
   - Direct column mapping
   - Nested `multiset()` or `row()` from inline relationships
   - Properties from Java objects after `@service`

2. **DataLoader batching** - Use `@splitQuery` for separate batched query
   - Generates `fieldNameLoader()` method
   - Additional round-trip to database
   - Batches requests across all parents
   - Only executes if field is selected

3. **Service call** - Escape to `@service` (⚠️ risks N+1 unless careful)
   - Must ensure service batches properly
   - Or accept the N+1 for rarely-called operations

---

## Query Patterns (Detailed)

Query patterns describe *how* data is conceptually retrieved. The pattern affects which arguments make sense and how the query is structured.

### Lookup Pattern

**Concept:** Fetch specific records by known key(s)
**Trigger:** `@lookupKey` directive on argument
**Use when:** Client knows exactly which records they want

```graphql
type Query {
  users(ids: [ID!]! @lookupKey): [User]!
  user(key: UserKey! @lookupKey): User

  # Composite key lookup
  usersByTenant(keys: [TenantUserKey!]! @lookupKey): [User]!
}

input TenantUserKey {
  tenantId: ID!
  userId: ID!
}
```

**Behavior:**
- Returns records in same order as requested keys (1:1 correspondence)
- Returns `null` if key doesn't exist
- Efficient for known-key access patterns
- Generated SQL: `WHERE pk IN (?, ?, ?)`

**When to use:**
- Client has specific IDs from previous query
- Batch loading related entities
- Refetching known records

---

### Filter Pattern

**Concept:** Start with all records, narrow down via structured filters
**Trigger:** Returns `*Connection` type, no required search argument
**Use when:** Browsing, exploring, applying multiple criteria

```graphql
type Query {
  users(
    filter: UserFilter,
    first: Int = 100,
    after: String
  ): UserConnection!
}

input UserFilter {
  status: UserStatus
  department: String
  createdAfter: DateTime
}
```

**Behavior:**
- Always paginated (Relay cursor connections)
- Applies filters as SQL WHERE clauses
- Good for browsing UIs
- Generated SQL: `WHERE status = ? AND department = ? ORDER BY ... LIMIT ?`

**When to use:**
- Browsing interfaces with filters
- Admin dashboards
- List views with multiple filter criteria

---

### Search Pattern

**Concept:** Start with nothing, find matches via search query
**Trigger:** Returns `*Connection` type, has `@search` directive or required `query`/`search` argument
**Use when:** Full-text search, relevance-based results

```graphql
type Query {
  # Explicit @search directive
  searchUsers(
    q: String! @search,
    filter: UserFilter,
    first: Int = 100
  ): UserConnection!

  # Implicit by naming convention
  searchProducts(
    query: String!,
    filter: ProductFilter,
    first: Int = 100
  ): ProductConnection!
}
```

**Behavior:**
- Always paginated
- Search argument drives primary query
- Filters can further narrow results
- Results typically relevance-ranked

**When to use:**
- Search bars
- "Find as you type" features
- Discovery interfaces

---

### List Pattern

**Concept:** Simple fetch of all records
**Trigger:** Returns list (not Connection), typically no arguments
**Use when:** Small, bounded, slowly-changing datasets

```graphql
type Query {
  countries: [Country!]!
  orderStatuses: [OrderStatus!]!
  timezones: [Timezone!]!
}
```

**⚠️ Warning:** Use sparingly. Prefer Filter pattern for most cases.

**When to use:**
- Small reference data (< 100 records)
- Enum-like data loaded into select boxes
- Data that changes rarely and is always needed

**When NOT to use:**
- Large datasets
- Data that grows over time
- Anything that should be paginated

---

## What Graphitron Generates

### `<Type>Queries` Classes

Graphitron generates one `Queries` class per GraphQL type. Each class contains methods that either execute complete SQL queries or generate jOOQ QueryParts.

**Naming convention:**
- `QueryQueries` - For fields on the `Query` type
- `MutationQueries` - For fields on the `Mutation` type
- `UserQueries` - For fields on the `User` type
- `OrderQueries` - For fields on the `Order` type

**Method types:**

1. **Query execution methods** - `fieldName(env)`
   - For root Query/Mutation fields
   - Executes complete SQL query
   - Called once per request

2. **Loader methods** - `fieldNameLoader(env)`
   - For `@splitQuery` fields
   - Executes batched SQL query via DataLoader
   - Returns `Map<ID, Result>`

3. **QueryPart helper methods** - `buildFieldNameMultiset(field)`, `buildFieldNameRow(field)`
   - For inline nested relationships
   - Returns jOOQ QueryParts (`Field<?>`)
   - Composes into parent query

**Example structure:**

```java
public class UserQueries {
    // Extracts DSLContext from GraphitronContext
    private DSLContext getCtx(DataFetchingEnvironment env) {
        GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
        return ctx.getDslContext(env);
    }

    // Root field: Query.user(id: ID!)
    // Executes complete SQL query
    public UserRecord user(DataFetchingEnvironment env) {
        var ctx = getCtx(env);
        var id = env.getArgument("id");
        var selection = env.getSelectionSet();

        return ctx.select(buildColumns(selection))
            .from(USERS)
            .where(USERS.ID.eq(id))
            .fetchOne();
    }

    // @splitQuery field: User.orders @splitQuery
    // Executes batched SQL query via DataLoader
    public Map<Integer, Result<OrdersRecord>> ordersLoader(DataFetchingEnvironment env) {
        var ctx = getCtx(env);
        var userIds = (List<Integer>) env.getSource();  // Batched user IDs
        var selection = env.getSelectionSet();

        return ctx.select(buildOrderColumns(selection))
            .from(ORDERS)
            .where(ORDERS.USER_ID.in(userIds))
            .fetch()
            .intoGroups(ORDERS.USER_ID);
    }

    // Inline field: User.address (default)
    // Returns QueryPart that composes into parent query
    private Field<?> buildAddressRow(SelectedField field) {
        var selection = field.getSelectionSet();
        return row(
            select(buildAddressColumns(selection))
            .from(ADDRESS)
            .where(ADDRESS.ID.eq(USERS.ADDRESS_ID))
        );
    }

    // Inline field: User.posts (default)
    // Returns QueryPart that composes into parent query
    private Field<?> buildPostsMultiset(SelectedField field) {
        var selection = field.getSelectionSet();
        return multiset(
            select(buildPostColumns(selection))
            .from(POSTS)
            .where(POSTS.USER_ID.eq(USERS.ID))
        );
    }

    // Helper: Build column list based on selection
    private List<Field<?>> buildColumns(SelectionSet selection) {
        var columns = new ArrayList<Field<?>>();

        if (selection.contains("id")) columns.add(USERS.ID);
        if (selection.contains("name")) columns.add(USERS.NAME);
        if (selection.contains("address")) {
            columns.add(buildAddressRow(selection.getField("address")));
        }
        if (selection.contains("posts")) {
            columns.add(buildPostsMultiset(selection.getField("posts")));
        }

        return columns;
    }
}
```

### RuntimeWiring

DataFetchers exist only as lambdas in the RuntimeWiring:

```java
RuntimeWiring.newRuntimeWiring()
    .type("Query", builder -> builder
        .dataFetcher("user", env -> userQueries.user(env))
        .dataFetcher("users", env -> queryQueries.users(env))
    )
    .type("User", builder -> builder
        // @splitQuery - calls loader method
        .dataFetcher("orders", env -> userQueries.ordersLoader(env))
        // Inline - trivial extraction from parent result
        .dataFetcher("address", env -> ((UsersRecord) env.getSource()).get("address"))
        .dataFetcher("posts", env -> ((UsersRecord) env.getSource()).get("posts"))
    )
    .build();
```

**Key insight:** DataFetcher is just the wiring. The valuable code is in the `Queries` classes.

---

## Code Generation Strategies (Detailed)

### Inline QueryPart Composition (Default)

**What:** Nested data fetched in parent query using `multiset()` or `row()` QueryParts
**When:** Default for relationships without `@splitQuery`
**Generated:** Helper methods returning `Field<?>` QueryParts

```graphql
type User @table(name: "USERS") {
  id: ID!
  name: String!
  # Default: inline row QueryPart in parent query
  address: Address @reference(path: [{key: "FK_USERS_ADDRESS"}])
  # Default: inline multiset QueryPart in parent query
  orders: [Order!]! @reference(path: [{key: "FK_ORDERS_USER"}])
}
```

**Generated code:**

```java
public class UserQueries {
    // Root query method
    public Result<UsersRecord> users(DataFetchingEnvironment env) {
        var ctx = getCtx(env);
        return ctx.select(buildColumns(env.getSelectionSet()))
            .from(USERS)
            .fetch();
    }

    // QueryPart helper for address (many-to-one)
    private Field<?> buildAddressRow(SelectedField field) {
        return row(
            select(/* address columns */)
            .from(ADDRESS)
            .where(ADDRESS.ID.eq(USERS.ADDRESS_ID))
        );
    }

    // QueryPart helper for orders (one-to-many)
    private Field<?> buildOrdersMultiset(SelectedField field) {
        return multiset(
            select(/* order columns */)
            .from(ORDERS)
            .where(ORDERS.USER_ID.eq(USERS.ID))
        );
    }
}
```

**Generated SQL concept:**
```sql
SELECT
  users.id,
  users.name,
  -- Many-to-one: row QueryPart
  (SELECT row(address.*)
   FROM address
   WHERE address.id = users.address_id) as address,
  -- One-to-many: multiset QueryPart
  (SELECT json_agg(orders.*)
   FROM orders
   WHERE orders.user_id = users.id) as orders
FROM users
```

**Trade-offs:**
- ✅ Single round-trip to database
- ✅ Simple, efficient for most cases
- ✅ All data fetched together, consistent view
- ❌ Can bloat parent query if nested data is large
- ❌ Fetches nested data even if not requested (though column selection still applies)

**When to use:** Default choice for most relationships.

---

### Split Query (DataLoader Batching)

**What:** Separate batched query using DataLoader
**When:** `@splitQuery` directive on field
**Generated:** `fieldNameLoader(env)` method returning `Map<ID, Result>`

```graphql
type User @table(name: "USERS") {
  id: ID!
  name: String!
  # Explicit: separate batched query
  orders: [Order!]! @reference(path: [{key: "FK_ORDERS_USER"}]) @splitQuery
  # Large collection, often not needed
  activityLog: [Activity!]! @splitQuery @reference(path: [{key: "FK_ACTIVITY_USER"}])
}
```

**Generated code:**

```java
public class UserQueries {
    // Loader method for @splitQuery field
    public Map<Integer, Result<OrdersRecord>> ordersLoader(DataFetchingEnvironment env) {
        var ctx = getCtx(env);
        var userIds = (List<Integer>) env.getSource();  // Batched from DataLoader

        return ctx.select(buildOrderColumns(env.getSelectionSet()))
            .from(ORDERS)
            .where(ORDERS.USER_ID.in(userIds))
            .fetch()
            .intoGroups(ORDERS.USER_ID);  // Map: userId -> orders
    }
}
```

**RuntimeWiring:**
```java
.type("User", builder -> builder
    .dataFetcher("orders", createDataLoader(userQueries::ordersLoader))
)
```

**Behavior:**
1. Parent query fetches Users with their PKs
2. DataLoader collects PKs from all parents
3. Calls `ordersLoader()` with batched PKs
4. Single batched query: `SELECT * FROM orders WHERE user_id IN (?, ?, ?, ...)`
5. Results mapped back to each parent by FK

**Trade-offs:**
- ✅ Only fetches if field is selected in query
- ✅ Keeps parent query simple and fast
- ✅ Good for large or conditional nested data
- ✅ Can batch across multiple parents
- ❌ Additional round-trip to database
- ❌ More complex to debug
- ❌ Results may be from different snapshot if DB changes between queries

**When to use:**
- Large nested collections that would bloat parent query
- Nested data rarely requested (conditional fetching)
- Parent query becoming too complex
- Breaking up for maintainability

**When NOT to use:**
- Small nested collections
- Data always needed together
- When single query is fast enough

---

### Service Call (Escape Hatch)

**What:** Call custom Java method, leave jOOQ-land
**When:** `@service` directive on field
**Generated:** Calls user-provided service method

```graphql
type User @table(name: "USERS") {
  id: ID!
  name: String!
  recommendations: [Recommendation!]!
    @service(service: {className: "RecommendationService", method: "forUser"})
}

type Recommendation {  # No @table - this is a Java record
  score: Float!
  reason: String!
  product: Product!  # Re-entry field back to jOOQ-land
}
```

**User-provided service:**

```java
public class RecommendationService {
    public List<RecommendationResult> forUser(UsersRecord user) {
        // Custom recommendation logic (ML, external service, etc.)
        var recommendations = getRecommendations(user.getId());

        return recommendations.stream().map(rec -> {
            var productRecord = new ProductsRecord();
            productRecord.setId(rec.getProductId());  // Set PK for re-entry
            return new RecommendationResult(
                rec.getScore(),
                rec.getReason(),
                productRecord
            );
        }).toList();
    }
}

public record RecommendationResult(
    float score,
    String reason,
    ProductsRecord productRecord  // jOOQ TableRecord with PK set for re-entry
) {}
```

**Trade-offs:**
- ✅ Full flexibility for custom logic
- ✅ Can integrate external services/APIs
- ✅ Complex calculations outside SQL
- ❌ Leaves type-safe jOOQ-land
- ❌ Risk of N+1 if not careful (service called N times for nested fields)
- ❌ Build-time validation is limited
- ❌ Must manually handle batching if needed

**When to use:**
- Logic truly doesn't fit declarative model
- Need to call external service/API
- Complex recommendation/ML logic
- Data aggregation across multiple sources

**When NOT to use (use alternatives instead):**
- ❌ Simple filtering → Use `@condition`
- ❌ Calculated fields → Use `@externalField`
- ❌ Complex queries → Use jOOQ DSL directly
- ❌ "I just prefer imperative code" → This isn't the right tool

**⚠️ N+1 Warning:** If `@service` is on a nested field, it executes N times. Your service must:
- Return quickly (cache, batch, etc.)
- Or accept the N+1 for rarely-called operations
- Or be on root Query/Mutation only

---

### QueryPart Extensions (@condition and @externalField)

**What:** User-provided methods that return jOOQ QueryParts
**When:** `@condition` or `@externalField` directives
**Generated:** Generated code references these methods

#### @condition - WHERE clause QueryParts

Returns `Condition` QueryPart for filtering:

```graphql
type User @table(name: "USERS") {
  activePosts: [Post!]!
    @reference(path: [{
      table: "POSTS",
      key: "FK_POSTS_USER",
      condition: {className: "Conditions", method: "isActive"}
    }])
}
```

```java
public class Conditions {
    public static Condition isActive(Posts post) {
        return post.STATUS.eq("ACTIVE")
            .and(post.DELETED_AT.isNull());
    }
}
```

**Generated code uses it:**
```java
private Field<?> buildActivePostsMultiset(SelectedField field) {
    return multiset(
        select(/* columns */)
        .from(POSTS)
        .where(POSTS.USER_ID.eq(USERS.ID))
        .and(Conditions.isActive(POSTS))  // Adds condition QueryPart
    );
}
```

#### @externalField - Calculated field QueryParts

Returns `Field<T>` QueryPart for calculated columns:

```graphql
type User @table(name: "USERS") {
  firstName: String!
  lastName: String!
  fullName: String! @externalField
}
```

```java
public class UserFields {
    public static Field<String> fullName(Users users) {
        return DSL.concat(users.FIRST_NAME, DSL.val(" "), users.LAST_NAME);
    }
}
```

**Generated code uses it:**
```java
private List<Field<?>> buildColumns(SelectionSet selection) {
    var columns = new ArrayList<Field<?>>();

    if (selection.contains("firstName")) columns.add(USERS.FIRST_NAME);
    if (selection.contains("lastName")) columns.add(USERS.LAST_NAME);
    if (selection.contains("fullName")) {
        columns.add(UserFields.fullName(USERS));  // Adds calculated field QueryPart
    }

    return columns;
}
```

**Key advantage:** Stays in jOOQ-land, generates SQL, type-safe.

---

### Re-entry (Return to jOOQ-Land)

**What:** From Java object back to database query
**When:** Field on Java-land type returns jOOQ-land type
**How:** DataLoader-backed, always

After escaping to `@service`, you often want to get back to jOOQ-land to query related database entities. Re-entry enables this.

#### TableRecord Re-entry

Java record contains jOOQ `TableRecord` with PK populated:

```graphql
type Recommendation {  # Java record from @service
  score: Float!
  product: Product! @field(name: "productRecord")
}

type Product @table(name: "PRODUCTS") {
  id: ID!
  name: String!
}
```

```java
public record RecommendationResult(
    float score,
    String reason,
    ProductsRecord productRecord  // jOOQ TableRecord - only PK populated
) {}
```

**Graphitron behavior:**
1. Extracts PK from each `productRecord`
2. Batches all PKs: `[123, 456, 789]`
3. Single query: `SELECT * FROM products WHERE id IN (123, 456, 789)`
4. Returns full jOOQ Record for each PK
5. Child fields now in jOOQ-land, can use normal generation

#### Node ID Re-entry

Java record contains Relay global ID string:

```graphql
type Recommendation {  # Java record from @service
  score: Float!
  relatedItem: Node! @field(name: "relatedItemId")
}
```

```java
public record RecommendationResult(
    float score,
    String relatedItemId  // Relay global ID: "Product:123"
) {}
```

**Graphitron behavior:**
1. Extracts global ID string: `"Product:123"`
2. Decodes: `type="Product"`, `pk=123`
3. DataLoader groups by type, batches PKs within each type
4. Queries: `SELECT * FROM products WHERE id IN (?pks)`
5. Returns appropriate jOOQ Record
6. Child fields now in jOOQ-land again

---

## Schema Location Effects

Where you put a field in your schema determines what gets generated.

### On Query/Mutation Type (Root)

**Called:** Once per request
**Can do:** Any database work
**Generated:** Query execution method in `<Type>Queries` class

```graphql
type Query {
  # Generates QueryQueries.user(env)
  user(id: ID! @lookupKey): User

  # Generates QueryQueries.users(env)
  users(filter: UserFilter, first: Int, after: String): UserConnection!
}

type Mutation {
  # Generates MutationQueries.createUser(env)
  createUser(input: CreateUserInput!): User!
}
```

**Generated:** Full query execution method, no N+1 concerns.

---

### On Object Type (Nested)

**Called:** N times (once per parent)
**Must be:** Trivial OR DataLoader-backed
**Default:** Inline QueryPart (trivial extraction)
**Opt-in:** Loader method (`@splitQuery`)

```graphql
type User @table(name: "USERS") {
  # Trivial: extract column
  id: ID!
  name: String!

  # Trivial: extract inline row QueryPart
  address: Address @reference(path: [{key: "FK_USERS_ADDRESS"}])

  # Trivial: extract inline multiset QueryPart
  posts: [Post!]! @reference(path: [{key: "FK_POSTS_USER"}])

  # DataLoader: generates ordersLoader() method
  orders: [Order!]! @splitQuery @reference(...)

  # Service: calls user service (risk N+1)
  recommendations: [Recommendation!]! @service(...)
}
```

**Key principle:** Non-root fields must respect the N+1 rule.

---

### On Connection Type

**Special fields:**

```graphql
type UserConnection {
  edges: [UserEdge!]!    # Trivial: extract from pagination result
  pageInfo: PageInfo!     # Trivial: extract from pagination result
  nodes: [User!]!         # Trivial: extract from pagination result
  totalCount: Int!        # NOT trivial: separate COUNT(*) query
}
```

**Note on `totalCount`:**
- Generates separate `SELECT COUNT(*)` query in `Queries` class
- Only executes if field is selected in query
- Uses same WHERE conditions as main query
- This is acceptable because it's conditional

---

## Decision Framework

When designing your schema, ask these questions in order:

### 1. Where am I in the schema?

- **Root (Query/Mutation)?**
  ✅ You can do any work. Generates query execution method.

- **Nested field?**
  ⚠️ Default to inline QueryPart. Use `@splitQuery` loader only if needed.

### 2. What query pattern fits the use case?

- **Client has known keys?** → **Lookup** (`@lookupKey`)
- **Browsing/filtering many criteria?** → **Filter** (`*Connection` + filter args)
- **Text search?** → **Search** (`*Connection` + `@search`)
- **Small reference data?** → **List** (use sparingly)

### 3. Should this be inline or split?

**Default to inline.** Use `@splitQuery` only if:
- ✅ Large nested collections that bloat parent query
- ✅ Nested data rarely requested (conditional fetching)
- ✅ Parent query becoming too complex
- ✅ Breaking up for maintainability

**Stay inline if:**
- ✅ Small nested collections
- ✅ Data usually needed together
- ✅ Single query is fast enough

### 4. Do I need custom logic?

**Ask: Can I stay in jOOQ-land?**

- **Need filtering?** → `@condition` (stays in jOOQ-land) ✅
- **Need calculated field?** → `@externalField` (stays in jOOQ-land) ✅
- **Need external service?** → `@service` (leaves jOOQ-land) ⚠️

### 5. If using @service, how do I get back to jOOQ-land?

Include re-entry fields in your Java record:
- jOOQ `TableRecord` components (with PK set)
- Relay global ID strings

**Plan your return before you leave.**

---

## Common Patterns

### Pattern: Simple CRUD on root type

```graphql
type Query {
  # Filter pattern - generates QueryQueries.users(env)
  users(filter: UserFilter, first: Int = 100, after: String): UserConnection!

  # Lookup pattern - generates QueryQueries.user(env)
  user(id: ID! @lookupKey): User
}

type Mutation {
  # Generates MutationQueries.createUser(env)
  createUser(input: CreateUserInput!): User! @mutation(typeName: INSERT)
  # Generates MutationQueries.updateUser(env)
  updateUser(id: ID!, input: UpdateUserInput!): User! @mutation(typeName: UPDATE)
  # Generates MutationQueries.deleteUser(env)
  deleteUser(id: ID!): Boolean! @mutation(typeName: DELETE)
}

type User @table(name: "USERS") {
  id: ID!
  name: String!
  email: String!
}
```

---

### Pattern: Nested relationships (default inline)

```graphql
type User @table(name: "USERS") {
  id: ID!
  name: String!
  # Many-to-one: UserQueries.buildAddressRow() returns row QueryPart
  address: Address @reference(path: [{key: "FK_USERS_ADDRESS"}])
  # One-to-many: UserQueries.buildOrdersMultiset() returns multiset QueryPart
  orders: [Order!]! @reference(path: [{key: "FK_ORDERS_USER"}])
}

type Address @table(name: "ADDRESSES") {
  id: ID!
  street: String!
  city: String!
}

type Order @table(name: "ORDERS") {
  id: ID!
  status: String!
  total: Float!
}
```

All nested data fetched in one query using QueryParts. Child fields trivially extract.

---

### Pattern: Large nested collection (split query)

```graphql
type User @table(name: "USERS") {
  id: ID!
  name: String!
  # Small, frequently needed: keep inline QueryPart
  address: Address @reference(path: [{key: "FK_USERS_ADDRESS"}])
  # Large, often not requested: generates UserQueries.activityLogLoader()
  activityLog: [Activity!]!
    @splitQuery
    @reference(path: [{key: "FK_ACTIVITY_USER"}])
}
```

Separate loader method with DataLoader batching, only executes if field selected.

---

### Pattern: Conditional filtering with @condition

```graphql
type User @table(name: "USERS") {
  activePosts: [Post!]!
    @reference(path: [{
      table: "POSTS",
      key: "FK_POSTS_USER",
      condition: {className: "Conditions", method: "isActive"}
    }])

  draftPosts: [Post!]!
    @reference(path: [{
      table: "POSTS",
      key: "FK_POSTS_USER",
      condition: {className: "Conditions", method: "isDraft"}
    }])
}
```

```java
public class Conditions {
    public static Condition isActive(Posts post) {
        return post.STATUS.eq("ACTIVE")
            .and(post.DELETED_AT.isNull());
    }

    public static Condition isDraft(Posts post) {
        return post.STATUS.eq("DRAFT");
    }
}
```

Stays in jOOQ-land, generates QueryParts, adds WHERE clauses to generated SQL.

---

### Pattern: Calculated field with @externalField

```graphql
type User @table(name: "USERS") {
  firstName: String!
  lastName: String!
  fullName: String! @externalField

  # Calculated from database function
  age: Int! @externalField
}
```

```java
public class UserFields {
    public static Field<String> fullName(Users users) {
        return DSL.concat(users.FIRST_NAME, DSL.val(" "), users.LAST_NAME);
    }

    public static Field<Integer> age(Users users) {
        return DSL.field("EXTRACT(YEAR FROM age({0}))", Integer.class, users.BIRTH_DATE);
    }
}
```

Stays in jOOQ-land, generates QueryParts, adds calculated columns to SELECT.

---

### Pattern: Service with re-entry

```graphql
type User @table(name: "USERS") {
  id: ID!
  name: String!
  # Calls RecommendationService.forUser()
  recommendations: [Recommendation!]!
    @service(service: {className: "RecService", method: "forUser"})
}

type Recommendation {
  score: Float!
  reason: String!
  # Re-entry: generates ProductQueries loader
  product: Product!
}

type Product @table(name: "PRODUCTS") {
  id: ID!
  name: String!
  price: Float!
}
```

```java
public record RecommendationResult(
    float score,
    String reason,
    ProductsRecord productRecord  // jOOQ TableRecord with PK set
) {}

public class RecService {
    public List<RecommendationResult> forUser(UsersRecord user) {
        // Custom recommendation logic (ML, external service, etc.)
        var recommendations = getRecommendations(user.getId());

        return recommendations.stream().map(rec -> {
            var productRecord = new ProductsRecord();
            productRecord.setId(rec.getProductId());  // Set PK for re-entry
            return new RecommendationResult(
                rec.getScore(),
                rec.getReason(),
                productRecord
            );
        }).toList();
    }
}
```

Escapes to Java-land, then re-enters jOOQ-land via TableRecord. Product fields use normal QueryPart generation.

---

## Anti-Patterns to Avoid

### ❌ Excessive @service usage

**Problem:** Overusing `@service` defeats Graphitron's value proposition.

**Why it's bad:**
- Leaves type-safe jOOQ QueryPart generation
- Loses automatic SQL generation
- Risks N+1 queries
- More code to maintain

**What to do instead:**
- Use `@condition` for filtering (returns Condition QueryPart)
- Use `@externalField` for calculated fields (returns Field<T> QueryPart)
- Use jOOQ DSL directly in generated Queries classes
- Only use `@service` when truly necessary

---

### ❌ @splitQuery everywhere

**Problem:** Using `@splitQuery` on every relationship.

**Why it's bad:**
- Additional round-trips for every field
- More loader methods to maintain
- Defeats default inline QueryPart efficiency
- Usually slower than single query

**What to do instead:**
- Default to inline QueryParts
- Only split when you have a specific reason:
  - Large collections bloating parent query
  - Conditional fetching needs
  - Breaking up for maintainability

---

### ❌ Ignoring the N+1 rule

**Problem:** Putting expensive operations on nested fields without DataLoader.

```graphql
# DON'T DO THIS
type User @table(name: "USERS") {
  orders: [Order!]! @service(...)  # ⚠️ Service called N times!
}
```

**Why it's bad:**
- Service called once per user
- Queries inside service execute N times
- Performance degrades with more users

**What to do instead:**
- Move `@service` to root Query field (called once)
- Or use `@splitQuery` with `@reference` (generates loader method)
- Or accept N+1 for rarely-called operations

---

### ❌ Fighting the framework

**Problem:** Constantly using `@notGenerated` and writing manual code.

**Why it's bad:**
- You're not using Graphitron's strengths
- Writing query code Graphitron should generate
- Probably using the wrong tool

**What to do instead:**
- Learn the declarative patterns (`@condition`, `@externalField`, `@reference`)
- Embrace jOOQ QueryPart generation, minimize Java-land
- If most of your API is manual code, consider a different framework

**Remember:** Graphitron excels at generating jOOQ queries and QueryParts from declarative schemas. If your domain doesn't fit that model, that's okay—but Graphitron may not be the right choice.

---

### ❌ List pattern for large/growing datasets

**Problem:** Using list return types for data that should be paginated.

```graphql
# DON'T DO THIS
type Query {
  users: [User!]!  # ⚠️ Could be thousands of records
}
```

**Why it's bad:**
- No pagination
- Fetches all records
- Slow, memory-intensive
- Doesn't scale

**What to do instead:**
- Use Filter pattern: `users(first: Int, after: String): UserConnection!`
- Reserve list return types for small reference data (< 100 records)

---

## Summary

### Core Model

- **Two Worlds:** jOOQ-land (QueryPart generation, type-safe) vs Java-land (@service, flexible but risky)
- **Goal:** Stay in jOOQ-land as much as possible
- **Escape & Return:** Use `@service` sparingly, re-enter via TableRecord or Node ID

### N+1 Rule

- **Root:** Can do any work, generates query execution methods
- **Non-root:** Trivial extraction or DataLoader-backed loader methods

### Query Patterns

- **Lookup:** Fetch by known key(s) - `@lookupKey`
- **Filter:** Browse with filters - `*Connection`
- **Search:** Text search - `*Connection` + `@search`
- **List:** Small reference data - use sparingly

### What Graphitron Generates

- **`<Type>Queries` classes** - One per GraphQL type
- **Query execution methods** - `fieldName(env)` for root fields
- **Loader methods** - `fieldNameLoader(env)` for @splitQuery fields
- **QueryPart helpers** - `buildFieldNameMultiset/Row()` for inline fields
- **RuntimeWiring** - Lambdas that wire fields to methods

### Queries vs QueryParts

- **Queries:** Execute complete SQL (root/@splitQuery methods)
- **QueryParts:** Compose into queries (inline helpers, @condition, @externalField)

### Decision Framework

1. **Where in schema?** Root vs nested determines method type
2. **What query pattern?** Lookup, Filter, Search, List
3. **Inline or split?** Default inline QueryPart, split to loader only when needed
4. **Need custom logic?** Prefer `@condition`/`@externalField` QueryParts, minimize `@service`
5. **Using @service?** Plan re-entry before leaving jOOQ-land

### Key Principle

**Default to the simplest approach that works.** Inline QueryParts, simple queries, staying in jOOQ-land. Only add complexity (loader methods, `@service`) when you have a specific need.

---

**See also:**
- [graphitron-java-codegen README](../graphitron-codegen-parent/graphitron-java-codegen/README.md) - Complete directive reference
- [Graphitron Mental Model](../geps/GEP-001.1%20Graphitron%20Mental%20Model.md) - Conceptual foundation
- [Graphitron Principles](GRAPHITRON-PRINCIPLES.md) - Design philosophy
- [Vision and Goals](VISION-AND-GOAL.md) - What Graphitron is and isn't
