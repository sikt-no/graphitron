# Graphitron Code Generation Patterns

> **Quick Navigation:**
> [Overview](#overview) • [Quick Reference](#quick-reference) • [Two Worlds](#the-two-worlds-jooq-land-vs-java-land) • [N+1 Rule](#the-n1-rule) • [Query Patterns](#query-patterns-detailed) • [Code Generation Strategies](#code-generation-strategies-detailed) • [Decision Framework](#decision-framework) • [Common Patterns](#common-patterns) • [Anti-Patterns](#anti-patterns-to-avoid)

---

## Overview

Graphitron generates GraphQL DataFetchers by tying your schema to jOOQ database tables. Understanding code generation requires grasping three core concepts:

**The Two Worlds:** Graphitron operates in either **jOOQ-land** (type-safe database queries) or **Java-land** (custom service methods). The goal is to stay in jOOQ-land as much as possible because it generates optimized SQL with selection-set-driven column selection and built-in N+1 prevention. Use Java-land sparingly as an escape hatch, and return to jOOQ-land via **re-entry** as quickly as possible.

**The N+1 Rule:** Fields on the root Query/Mutation type execute once and can do any work. Nested fields execute N times (once per parent) and must either extract data already fetched by the parent (trivial) or use DataLoader batching (`@splitQuery`). This constraint shapes all code generation decisions.

**Schema Structure Drives Generation:** Where you place a field (root vs nested), what it returns (list vs Connection), and which directives you use (`@table`, `@splitQuery`, `@service`, `@condition`) determines what code Graphitron generates. Most of the time, the default inline approach works. Use `@splitQuery` only for large collections or conditional fetching needs.

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
| **Break up large nested queries** | `@splitQuery` on field | [Split Query](#split-query-dataloader-batching) |
| **Call custom Java logic** | `@service` directive (use sparingly) | [Service Call](#service-call-escape-hatch) |
| **Return to DB after service** | Include re-entry fields | [Re-entry](#re-entry-return-to-jooq-land) |

### Directive Quick Reference

| Directive | Purpose | Generated Code Effect |
|-----------|---------|------------------------|
| `@table` | Link type to jOOQ table | Enables jOOQ query generation for this type |
| `@reference` | Declare FK relationship | Inline join/multiset OR split query (if with `@splitQuery`) |
| `@splitQuery` | Force separate batched query | DataLoader-backed query instead of inline |
| `@service` | Call custom Java method | Escape to Java-land, call your code |
| `@condition` | Add WHERE clauses | Includes jOOQ `Condition` in query |
| `@externalField` | Calculated field | Includes jOOQ `Field<T>` in SELECT |
| `@field` | Map field to column/property | Changes field name mapping |
| `@lookupKey` | Lookup by key(s) | Fetch by PK/unique key, ordered results |
| `@orderBy` | Enable sorting | Order by indexed columns |
| `@node` | Global ID support | Relay node interface implementation |
| `@discriminate` | Single-table inheritance | Type resolution via discriminator column |
| `@notGenerated` | Skip generation | You provide DataFetcher manually |

### Query Pattern Selection Guide

| Use Case | Pattern | Return Type | Key Feature |
|----------|---------|-------------|-------------|
| Client has specific IDs | **Lookup** | `[Type]` or `Type` | `@lookupKey`, ordered results |
| Browse with filters | **Filter** | `TypeConnection` | Paginated, start with all |
| Text/relevance search | **Search** | `TypeConnection` | Paginated, `@search` arg |
| Small reference data | **List** | `[Type]` | Simple fetch, use sparingly |

### Inline vs Split Query Decision

| Factor | Inline (Default) | Split (`@splitQuery`) |
|--------|------------------|------------------------|
| **Query complexity** | Simple, keeps parent query lean | Breaks up complex queries |
| **Data size** | Small nested collections | Large nested collections |
| **Fetch frequency** | Always needed | Conditionally requested |
| **Round trips** | Single query | Additional round-trip |
| **When to use** | Default choice | Only when needed |

**Default to inline. Use `@splitQuery` when:**
- Nested collections are large and would bloat parent query
- Nested data is rarely requested (conditional fetching)
- Parent query is becoming too complex

---

## The Two Worlds: jOOQ-Land vs Java-Land

### jOOQ-Land (The Goal)

**What:** Source is jOOQ `Record` objects from database queries
**Why stay here:** Type-safe, efficient, validated at build time

**Characteristics:**
- Graphitron generates optimized SQL queries
- Selection sets drive column selection (no over-fetching)
- Nested data uses `multiset()` and `row()` (no N+1 queries)
- Everything validated against your database schema
- Compile-time errors if tables/columns don't exist

**How to stay in jOOQ-land:**
- Use `@table` on types to tie them to jOOQ tables
- Use `@reference` for relationships between tables
- Use `@condition` for filtering logic (returns jOOQ `Condition`)
- Use `@externalField` for calculated fields (returns jOOQ `Field<T>`)

### Java-Land (The Escape Hatch)

**What:** Source is Java objects (POJOs/records) from service methods
**Why use sparingly:** Leaves type-safety, risks N+1, limited build-time validation

**Characteristics:**
- You've called a `@service` method with custom Java code
- Graphitron extracts properties from Java objects
- No automatic database query generation
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

## Code Generation Strategies (Detailed)

Code generation strategies describe *what kind of code* Graphitron produces. This is the mechanical implementation.

### Inline jOOQ (Default)

**What:** Nested data fetched in parent query using `multiset()` or `row()`
**When:** Default for relationships without `@splitQuery`
**Extraction:** Child DataFetcher just extracts the nested data (trivial, no I/O)

```graphql
type User @table(name: "USERS") {
  id: ID!
  name: String!
  # Default: inline multiset in parent query
  orders: [Order!]! @reference(path: [{key: "FK_ORDERS_USER"}])
  # Default: inline row in parent query
  address: Address @reference(path: [{key: "FK_USERS_ADDRESS"}])
}
```

**Generated SQL concept:**
```sql
SELECT
  users.id,
  users.name,
  -- One-to-many: multiset
  (SELECT json_agg(orders.*)
   FROM orders
   WHERE orders.user_id = users.id) as orders,
  -- Many-to-one: row
  (SELECT row(address.*)
   FROM address
   WHERE address.id = users.address_id) as address
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
**Why:** Break up complex queries, conditional fetching, large nested collections

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

**Generated behavior:**
1. Parent query fetches Users with their PKs
2. Child DataFetcher extracts PKs from all parents
3. DataLoader queues PKs, then batches them
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
**Why:** Custom logic, external APIs, complex calculations

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

**Generated behavior:**
1. Calls your service method with parent record
2. Service returns Java objects (POJOs/records)
3. Child fields extract from Java objects (Java-land)
4. Re-entry fields trigger new database queries (back to jOOQ-land)

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
// Service returns this
public record RecommendationResult(
    float score,
    String reason,
    ProductsRecord productRecord  // jOOQ TableRecord - only PK populated
) {}

public class RecommendationService {
    public List<RecommendationResult> forUser(UsersRecord user) {
        // Your recommendation logic
        var results = new ArrayList<RecommendationResult>();

        for (Product p : getRecommendedProducts(user)) {
            var productRecord = new ProductsRecord();
            productRecord.setId(p.getId());  // Set PK for re-entry
            results.add(new RecommendationResult(p.score, p.reason, productRecord));
        }

        return results;
    }
}
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

### Trivial Extraction

**What:** Pull value from parent result, no I/O
**When:** Data already fetched by parent DataFetcher
**Marker:** Implements GraphQL-Java's `TrivialDataFetcher` interface

**Examples:**
- Direct column mapping from jOOQ Record
- Nested `multiset()` or `row()` from inline relationships
- Properties from Java objects after `@service`

**Why it matters:**
- Safe to call N times (no performance impact)
- No database query
- Instant extraction

**This is what makes the default inline approach efficient:** The parent query fetches everything, child DataFetchers just extract.

---

## Schema Location Effects

Where you put a field in your schema determines what gets generated.

### On Query/Mutation Type (Root)

**Called:** Once per request
**Can do:** Any database work
**Patterns available:** Lookup, Filter, Search, List

```graphql
type Query {
  # Lookup pattern
  user(id: ID! @lookupKey): User

  # Filter pattern
  users(filter: UserFilter, first: Int, after: String): UserConnection!

  # Search pattern
  searchUsers(query: String! @search, first: Int): UserConnection!

  # List pattern
  countries: [Country!]!
}

type Mutation {
  createUser(input: CreateUserInput!): User!
  updateUser(id: ID!, input: UpdateUserInput!): User!
  deleteUser(id: ID!): Boolean!
}
```

**Generated:** Full DataFetcher with database query, no N+1 concerns.

---

### On Object Type (Nested)

**Called:** N times (once per parent)
**Must be:** Trivial OR DataLoader-backed
**Default:** Inline (trivial extraction of nested data)
**Opt-in:** Split query (DataLoader batching)

```graphql
type User @table(name: "USERS") {
  # Trivial: extract column from parent Record
  id: ID!
  name: String!
  email: String! @field(name: "EMAIL_ADDRESS")

  # Trivial: extract nested multiset from parent query
  orders: [Order!]! @reference(path: [{key: "FK_ORDERS_USER"}])

  # Trivial: extract nested row from parent query
  address: Address @reference(path: [{key: "FK_USERS_ADDRESS"}])

  # DataLoader: separate batched query
  activityLog: [Activity!]! @splitQuery @reference(...)

  # Service: escape hatch (risk N+1)
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
- Generates separate `SELECT COUNT(*)` query
- Only executes if field is selected in query
- Uses same WHERE conditions as main query
- This is acceptable because it's conditional

---

## Decision Framework

When designing your schema, ask these questions in order:

### 1. Where am I in the schema?

- **Root (Query/Mutation)?**
  ✅ You can do any work. Choose appropriate query pattern.

- **Nested field?**
  ⚠️ Default to inline. Use `@splitQuery` only if needed.

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
  # Filter pattern for browsing
  users(filter: UserFilter, first: Int = 100, after: String): UserConnection!

  # Lookup pattern for specific record
  user(id: ID! @lookupKey): User
}

type Mutation {
  createUser(input: CreateUserInput!): User! @mutation(typeName: INSERT)
  updateUser(id: ID!, input: UpdateUserInput!): User! @mutation(typeName: UPDATE)
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
  # Many-to-one: inline row
  address: Address @reference(path: [{key: "FK_USERS_ADDRESS"}])
  # One-to-many: inline multiset
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

All nested data fetched in one query using `multiset()` and `row()`. Child DataFetchers trivially extract.

---

### Pattern: Large nested collection (split query)

```graphql
type User @table(name: "USERS") {
  id: ID!
  name: String!
  # Small, frequently needed: keep inline
  address: Address @reference(path: [{key: "FK_USERS_ADDRESS"}])
  # Large, often not requested: split
  activityLog: [Activity!]!
    @splitQuery
    @reference(path: [{key: "FK_ACTIVITY_USER"}])
}
```

Separate DataLoader-backed query, only executes if field selected.

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

Stays in jOOQ-land, adds WHERE clause to generated SQL.

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

Stays in jOOQ-land, adds calculated column to SELECT.

---

### Pattern: Service with re-entry

```graphql
type User @table(name: "USERS") {
  id: ID!
  name: String!
  # Escape to Java-land for recommendation logic
  recommendations: [Recommendation!]!
    @service(service: {className: "RecService", method: "forUser"})
}

type Recommendation {
  score: Float!
  reason: String!
  # Re-entry back to jOOQ-land
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

Escapes to Java-land, then re-enters jOOQ-land via TableRecord. Product fields use normal jOOQ generation.

---

## Anti-Patterns to Avoid

### ❌ Excessive @service usage

**Problem:** Overusing `@service` defeats Graphitron's value proposition.

**Why it's bad:**
- Leaves type-safe jOOQ-land
- Loses automatic query generation
- Risks N+1 queries
- More code to maintain

**What to do instead:**
- Use `@condition` for filtering (stays in jOOQ-land)
- Use `@externalField` for calculated fields (stays in jOOQ-land)
- Use jOOQ DSL for complex queries
- Only use `@service` when truly necessary

---

### ❌ @splitQuery everywhere

**Problem:** Using `@splitQuery` on every relationship.

**Why it's bad:**
- Additional round-trips for every field
- More complex to debug
- Defeats default inline efficiency
- Usually slower than single query

**What to do instead:**
- Default to inline
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
  orders: [Order!]! @service(...)  # ⚠️ Called N times!
}
```

**Why it's bad:**
- Service called once per user
- Queries inside service execute N times
- Performance degrades with more users

**What to do instead:**
- Move `@service` to root Query field (called once)
- Or use `@splitQuery` with `@reference` (batched)
- Or accept N+1 for rarely-called operations

---

### ❌ Fighting the framework

**Problem:** Constantly using `@notGenerated` and writing manual DataFetchers.

**Why it's bad:**
- You're not using Graphitron's strengths
- Writing code Graphitron should generate
- Probably using the wrong tool

**What to do instead:**
- Learn the declarative patterns (`@condition`, `@externalField`, `@reference`)
- Embrace jOOQ-land, minimize Java-land
- If most of your API is manual code, consider a different framework

**Remember:** Graphitron excels at generating jOOQ queries from declarative schemas. If your domain doesn't fit that model, that's okay—but Graphitron may not be the right choice.

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

- **Two Worlds:** jOOQ-land (type-safe, efficient) vs Java-land (flexible, risky)
- **Goal:** Stay in jOOQ-land as much as possible
- **Escape & Return:** Use `@service` sparingly, re-enter via TableRecord or Node ID

### N+1 Rule

- **Root:** Can do any work
- **Non-root:** Trivial or DataLoader-backed

### Query Patterns

- **Lookup:** Fetch by known key(s) - `@lookupKey`
- **Filter:** Browse with filters - `*Connection`
- **Search:** Text search - `*Connection` + `@search`
- **List:** Small reference data - use sparingly

### Code Generation Strategies

- **Inline jOOQ:** Default, single query, trivial extraction
- **Split Query:** DataLoader batching, `@splitQuery` when needed
- **Service Call:** Escape hatch, use sparingly
- **Re-entry:** Return to jOOQ-land via TableRecord or Node ID
- **Trivial Extraction:** No I/O, safe for N times

### Decision Framework

1. **Where in schema?** Root vs nested
2. **What query pattern?** Lookup, Filter, Search, List
3. **Inline or split?** Default inline, split when needed
4. **Need custom logic?** Prefer `@condition` / `@externalField`, minimize `@service`
5. **Using @service?** Plan re-entry before leaving jOOQ-land

### Key Principle

**Default to the simplest approach that works.** Inline relationships, simple queries, staying in jOOQ-land. Only add complexity (`@splitQuery`, `@service`) when you have a specific need.

---

**See also:**
- [graphitron-java-codegen README](../graphitron-codegen-parent/graphitron-java-codegen/README.md) - Complete directive reference
- [Graphitron Mental Model](../geps/GEP-001.1%20Graphitron%20Mental%20Model.md) - Conceptual foundation
- [Graphitron Principles](GRAPHITRON-PRINCIPLES.md) - Design philosophy
- [Vision and Goals](VISION-AND-GOAL.md) - What Graphitron is and isn't
