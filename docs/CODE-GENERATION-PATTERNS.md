# Graphitron Code Generation Patterns

This document explains how Graphitron generates code from your GraphQL schema. It provides a conceptual framework for understanding what triggers different code generation patterns and when to use each approach.

**Audience:** Developers who need to understand how schema design decisions affect the generated implementation.

---

## Core Concept: Two Worlds

Graphitron operates in two worlds:

### jOOQ-Land

**Source:** jOOQ `Record` objects from database queries
**Goal:** Stay here as much as possible
**Why:** Type-safe, efficient, validated at build time

When you're in jOOQ-land:
- Graphitron generates optimized SQL queries
- Selection sets drive column selection (no over-fetching)
- Nested data uses `multiset()` and `row()` (no N+1)
- Everything is validated against your database schema

### Java-Land

**Source:** Java objects (POJOs or records) from service methods
**Goal:** Use sparingly, return to jOOQ-land quickly
**Why:** Escape hatch for logic that doesn't fit the declarative model

When you're in Java-land:
- You've called a `@service` method
- Graphitron extracts properties from Java objects
- To query the database again, you need **re-entry** back to jOOQ-land

---

## The N+1 Rule

This is the fundamental constraint that shapes all code generation:

| Schema Location | Times Called | Constraint |
|-----------------|--------------|------------|
| **Root** (Query/Mutation) | Once per request | Can do any database work |
| **Non-root** (nested field) | N times (once per parent) | Must be trivial OR DataLoader-backed |

**Implication:** Non-root fields can either:
1. Extract data already fetched by the parent (trivial)
2. Use DataLoader batching to avoid N+1 (`@splitQuery`)
3. Escape to a service (risks N+1 unless you're careful)

---

## Query Patterns

Query patterns describe *how* data is conceptually retrieved. The pattern affects which arguments make sense and how the query is structured.

### Lookup Pattern

**Concept:** Fetch specific records by known key(s)
**Trigger:** `@lookupKey` on argument
**Use when:** Client knows exactly which records they want

```graphql
type Query {
  users(ids: [ID!]! @lookupKey): [User]!
  user(key: UserKey! @lookupKey): User
}
```

**Behavior:**
- Returns records in same order as requested keys
- 1:1 correspondence (null if key doesn't exist)
- Efficient for known-key access patterns

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
```

**Behavior:**
- Always paginated (Relay cursor connections)
- Applies filters as SQL WHERE clauses
- Good for browsing UIs

---

### Search Pattern

**Concept:** Start with nothing, find matches via search query
**Trigger:** Returns `*Connection` type, has `@search` or required `query`/`search` argument
**Use when:** Full-text search, relevance-based results

```graphql
type Query {
  searchUsers(
    query: String! @search,
    filter: UserFilter,
    first: Int = 100
  ): UserConnection!
}
```

**Behavior:**
- Always paginated
- Search argument drives primary query
- Filters can further narrow results
- Results typically relevance-ranked

---

### List Pattern

**Concept:** Simple fetch of all records
**Trigger:** Returns list (not Connection), typically no arguments
**Use when:** Small, bounded, slowly-changing datasets

```graphql
type Query {
  countries: [Country!]!
  orderStatuses: [OrderStatus!]!
}
```

**Warning:** Use sparingly. Prefer FilterDataFetcher for most cases. Only appropriate for small reference data.

---

## Code Generation Strategies

Code generation strategies describe *what kind of code* Graphitron produces. This is the mechanical implementation, independent of the query pattern.

### Inline jOOQ (Default)

**What:** Nested data fetched in parent query using `multiset()` or `row()`
**When:** Default for relationships without `@splitQuery`
**Extraction:** Child DataFetcher just extracts the nested data (trivial, no I/O)

```graphql
type User @table(name: "USERS") {
  orders: [Order!]! @reference(path: [{key: "FK_ORDERS_USER"}])
  # ↑ Default: inline multiset in parent query
}
```

**Generated SQL concept:**
```sql
SELECT
  users.id,
  users.name,
  (SELECT json_agg(orders.*)
   FROM orders
   WHERE orders.user_id = users.id) as orders
FROM users
```

**Trade-off:**
- ✅ Single round-trip to database
- ✅ Simple, efficient for most cases
- ❌ Can bloat parent query if nested data is large
- ❌ Fetches nested data even if not always needed

---

### Split Query (DataLoader Batching)

**What:** Separate batched query using DataLoader
**When:** `@splitQuery` directive on field
**Why:** Break up complex queries, conditional fetching, large nested collections

```graphql
type User @table(name: "USERS") {
  orders: [Order!]! @reference(path: [{key: "FK_ORDERS_USER"}]) @splitQuery
  # ↑ Explicit: separate batched query
}
```

**Generated behavior:**
1. Parent query fetches Users with PKs
2. Child DataFetcher extracts PKs, queues them in DataLoader
3. DataLoader batches: `SELECT * FROM orders WHERE user_id IN (?pks)`
4. Results mapped back to parents

**Trade-off:**
- ✅ Only fetches if field is selected
- ✅ Keeps parent query simple
- ✅ Good for large or conditional nested data
- ❌ Additional round-trip to database
- ❌ More complex to debug

**When to use:**
- Large nested collections that would bloat parent query
- Nested data rarely requested (conditional fetching)
- Need to break up overly complex queries

---

### Service Call (Escape Hatch)

**What:** Call custom Java method, leave jOOQ-land
**When:** `@service` directive on field
**Why:** Custom logic, external APIs, complex calculations

```graphql
type User @table(name: "USERS") {
  recommendations: [Recommendation!]!
    @service(service: {className: "RecommendationService", method: "forUser"})
}

type Recommendation {  # No @table - this is a Java record
  score: Float!
  product: Product!  # Re-entry field
}
```

**Generated behavior:**
1. Calls your service method
2. Receives Java objects (POJOs/records)
3. Child fields extract from Java objects (Java-land)
4. Re-entry fields query database again (back to jOOQ-land)

**Trade-off:**
- ✅ Full flexibility for custom logic
- ✅ Can integrate external services
- ❌ Leaves type-safe jOOQ-land
- ❌ Risk of N+1 if not careful
- ❌ Build-time validation is limited

**When to use:** Minimize usage. Only for logic that truly doesn't fit the declarative model. Get back to jOOQ-land via re-entry as quickly as possible.

---

### Re-entry (Return to jOOQ-Land)

**What:** From Java object back to database query
**When:** Field on Java-land type returns jOOQ-land type
**How:** DataLoader-backed, always

Two re-entry mechanisms:

#### TableRecord Re-entry

Java record contains jOOQ `TableRecord` with PK populated:

```java
public record RecommendationResult(
    float score,
    ProductsRecord productRecord  // jOOQ TableRecord, only PK set
) {}
```

Graphitron extracts PK, batches all PKs, queries: `SELECT * FROM products WHERE id IN (?)`

#### Node ID Re-entry

Java record contains Relay global ID string:

```java
public record RecommendationResult(
    float score,
    String relatedItemId  // "Product:123"
) {}
```

Graphitron decodes global ID, groups by type, batches PKs per type, queries each table.

---

### Trivial Extraction

**What:** Pull value from parent result, no I/O
**When:** Data already fetched by parent DataFetcher
**Examples:**
- Direct column mapping
- Nested `multiset()` or `row()` from inline relationships
- Properties from Java objects after `@service`

**Why it matters:** Safe to call N times (implements `TrivialDataFetcher` marker interface)

---

## Schema Location Effects

Where you put a field in your schema determines what gets generated.

### On Query/Mutation Type (Root)

**Called:** Once per request
**Can do:** Any database work
**Patterns available:** Lookup, Filter, Search, List

```graphql
type Query {
  # Any of these is fine at root level
  users: [User!]!
  users(filter: UserFilter): UserConnection!
  user(id: ID! @lookupKey): User
}

type Mutation {
  createUser(input: CreateUserInput!): User!
  updateUser(id: ID!, input: UpdateUserInput!): User!
}
```

**Generated:** Full DataFetcher with database query

---

### On Object Type (Nested)

**Called:** N times (once per parent)
**Must be:** Trivial OR DataLoader-backed
**Default:** Inline (trivial extraction of nested data)
**Opt-in:** Split query (DataLoader batching)

```graphql
type User @table(name: "USERS") {
  # Trivial: extract column
  id: ID!

  # Trivial: extract nested multiset from parent query
  orders: [Order!]! @reference(path: [{key: "FK_ORDERS_USER"}])

  # DataLoader: separate batched query
  largeCollection: [Item!]! @splitQuery @reference(...)

  # Service: escape hatch (risk N+1)
  customData: Data! @service(...)
}
```

---

### On Connection Type

**Special fields:**
- `edges`, `pageInfo`, `nodes` - Trivial extraction from pagination result
- `totalCount` - Separate `COUNT(*)` query, only if field selected

```graphql
type UserConnection {
  edges: [UserEdge!]!
  pageInfo: PageInfo!
  totalCount: Int!  # Separate query, conditional
}
```

---

## Directive Effects Summary

| Directive | Effect | Code Generated |
|-----------|--------|----------------|
| `@table` | Links type to jOOQ table | Enables jOOQ queries for this type |
| `@reference` | Declares FK relationship | Inline join/multiset OR split query |
| `@splitQuery` | Force separate query | DataLoader-backed query |
| `@service` | Call Java method | Escape to Java-land |
| `@condition` | Add WHERE clause | jOOQ `Condition` in query |
| `@externalField` | Calculated field | jOOQ `Field<T>` in SELECT |
| `@field` | Map field to column/property | Changes mapping, affects extraction |
| `@lookupKey` | Lookup by key(s) | Fetch by PK/unique key, ordered results |
| `@orderBy` | Enable sorting | Order by indexed columns |
| `@node` | Global ID support | Relay node interface implementation |
| `@discriminate` | Single-table inheritance | Type resolution via discriminator column |
| `@notGenerated` | Skip generation | You must provide DataFetcher manually |

---

## Decision Framework

When designing your schema, ask these questions:

### 1. Where am I in the schema?

- **Root (Query/Mutation)?** You can do any work. Choose appropriate query pattern.
- **Nested field?** Default to inline. Use `@splitQuery` only if needed.

### 2. What query pattern fits the use case?

- Known keys? → **Lookup** (`@lookupKey`)
- Browsing/filtering? → **Filter** (`*Connection`)
- Text search? → **Search** (`*Connection` + `@search`)
- Small reference data? → **List**

### 3. Should this be inline or split?

Default to inline. Use `@splitQuery` if:
- Large nested collections
- Nested data rarely requested
- Parent query becoming too complex

### 4. Do I need custom logic?

- Filtering? → `@condition` (stays in jOOQ-land) ✅
- Calculated field? → `@externalField` (stays in jOOQ-land) ✅
- External service? → `@service` (leaves jOOQ-land) ⚠️

### 5. If using @service, how do I get back to jOOQ-land?

Include fields that enable re-entry:
- jOOQ `TableRecord` components (with PK set)
- Relay global ID strings

---

## Common Patterns

### Pattern: Simple CRUD on root type

```graphql
type Query {
  users(filter: UserFilter, first: Int = 100, after: String): UserConnection!
  user(id: ID! @lookupKey): User
}

type Mutation {
  createUser(input: CreateUserInput!): User! @mutation(typeName: INSERT)
  updateUser(id: ID!, input: UpdateUserInput!): User! @mutation(typeName: UPDATE)
  deleteUser(id: ID!): Boolean! @mutation(typeName: DELETE)
}
```

### Pattern: Nested relationships (default inline)

```graphql
type User @table(name: "USERS") {
  id: ID!
  name: String!
  address: Address @reference(path: [{key: "FK_USERS_ADDRESS"}])
  orders: [Order!]! @reference(path: [{key: "FK_ORDERS_USER"}])
}
```

All nested data fetched in one query using `multiset()` and `row()`.

### Pattern: Large nested collection (split query)

```graphql
type User @table(name: "USERS") {
  id: ID!
  name: String!
  # Large collection, often not requested
  activityLog: [Activity!]! @splitQuery @reference(path: [{key: "FK_ACTIVITY_USER"}])
}
```

Separate DataLoader-backed query, only if field selected.

### Pattern: Conditional filtering with @condition

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

### Pattern: Calculated field with @externalField

```graphql
type User @table(name: "USERS") {
  firstName: String!
  lastName: String!
  fullName: String! @externalField
}
```

```java
public static Field<String> fullName(Users users) {
    return DSL.concat(users.FIRST_NAME, DSL.val(" "), users.LAST_NAME);
}
```

### Pattern: Service with re-entry

```graphql
type User @table(name: "USERS") {
  recommendations: [Recommendation!]!
    @service(service: {className: "RecService", method: "forUser"})
}

type Recommendation {
  score: Float!
  reason: String!
  product: Product!  # Re-entry to jOOQ-land
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
        // Custom recommendation logic
        // Return jOOQ TableRecords with PKs set for re-entry
    }
}
```

---

## Anti-Patterns to Avoid

### ❌ Excessive @service usage

`@service` should be rare. Most logic can be expressed with `@condition` and `@externalField`, which stay in jOOQ-land.

### ❌ @splitQuery everywhere

Default to inline. Only split when you have a specific reason (large collections, conditional fetching).

### ❌ Ignoring the N+1 rule

Non-root fields must be trivial or DataLoader-backed. Don't put expensive operations on nested fields.

### ❌ Fighting the framework

If you find yourself constantly reaching for `@service` and `@notGenerated`, you might be using the wrong tool. Graphitron excels at generating jOOQ queries. If your API is mostly service calls, consider a different framework.

---

## Summary

**Core Model:**
- jOOQ-land (type-safe, efficient) vs Java-land (flexible, risky)
- Goal: Stay in jOOQ-land

**N+1 Rule:**
- Root: Can do any work
- Non-root: Trivial or DataLoader-backed

**Query Patterns:**
- Lookup, Filter, Search, List

**Code Generation Strategies:**
- Inline jOOQ (default)
- Split query (DataLoader batching)
- Service call (escape hatch)
- Re-entry (return to jOOQ-land)
- Trivial extraction (no I/O)

**Decision Framework:**
1. Where in schema?
2. What query pattern?
3. Inline or split?
4. Need custom logic?
5. How to re-enter jOOQ-land?

---

**See also:**
- [graphitron-java-codegen README](../graphitron-codegen-parent/graphitron-java-codegen/README.md) - Complete directive reference
- [Graphitron Mental Model](../geps/GEP-001.1%20Graphitron%20Mental%20Model.md) - Conceptual foundation
- [Graphitron Principles](GRAPHITRON-PRINCIPLES.md) - Design philosophy
- [Vision and Goals](VISION-AND-GOAL.md) - What Graphitron is and isn't
