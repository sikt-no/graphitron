# Graphitron Code Generation Patterns

> **Quick Navigation:**
> [Overview](#overview) • [Quick Reference](#quick-reference) • [Implicit Behaviors](#️-critical-implicit-behaviors) • [Query Patterns](#query-patterns) • [What Graphitron Generates](#what-graphitron-generates) • [Code Generation Strategies](#code-generation-strategies) • [Decision Framework](#decision-framework) • [Common Patterns](#common-patterns) • [QueryPart Taxonomy](#querypart-taxonomy) • [Anti-Patterns](#anti-patterns-to-avoid)

---

## Overview

Graphitron generates jOOQ query code from GraphQL schemas. Core concepts:

- **jOOQ-land vs Java-land:** Stay in jOOQ QueryPart generation; escape to `@service` only when necessary
- **N+1 Rule:** Root fields execute once, nested fields N times (must be trivial or batched)
- **Queries vs QueryParts:** Methods either execute SQL or return composable QueryParts
- **Implicit behaviors:** Fields with arguments auto-become split queries; see [table below](#️-critical-implicit-behaviors)

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
| `@reference` | Declare FK relationship | Inline QueryPart (multiset/row) OR separate query (if `@splitQuery` or has args) |
| `@splitQuery` | Force separate batched query | Generates `fieldNameLoader()` method with DataLoader batching |
| `@service` | Call custom Java method | Escapes SQL generation, calls your service |
| `@condition` | Add WHERE clauses | Returns jOOQ `Condition` QueryPart, added to query WHERE |
| `@tableMethod` | Transform Table QueryPart | Returns jOOQ `Table<R>` QueryPart (views, lateral joins, etc.) |
| `@externalField` | Calculated field | Returns jOOQ `Field<T>` QueryPart |
| `@field` | Map field to column/property | Changes field name mapping |
| `@lookupKey` | Lookup by key(s) | Fetch by PK/unique key, ordered results |
| `@orderBy` | Enable sorting | Order by indexed columns |
| `@node` | Global ID support | Relay node interface implementation |
| `@discriminate` | Single-table inheritance | Type resolution via discriminator column |
| `@record` | Input as Java record | Input type maps to Java record, transformer to jOOQ |
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

### ⚠️ Critical Implicit Behaviors

Graphitron has several **implicit behaviors** that trigger code generation without explicit directives:

| Pattern | Trigger | What Gets Generated | Why |
|---------|---------|---------------------|-----|
| **Implicit split query** | Field has GraphQL arguments (except on root Query/Mutation) | Loader method (`fieldNameLoader()`), even without `@splitQuery` | Can't inline a QueryPart that needs different arguments for each parent |
| **Multi-table interface** | Interface implemented by types with different `@table` | Type resolver based on record type | Each implementation has different backing table |
| **Single-table interface** | Interface with `@discriminate` on implementations | Type resolver checking discriminator column | All implementations share same table, differentiated by column value |
| **TableRecord re-entry** | Java record contains jOOQ `TableRecord` component | Loader extracts PK, batches query | Service returned Java object, need to fetch full record from DB |
| **Node ID re-entry** | Field returns `Node` type, receives global ID | Decoder + type routing + DataLoader batching | Relay global ID must be decoded to type + PK, then fetch |
| **Direct column mapping** | Field name matches column name on `@table` type | Direct `TABLE.COLUMN` Field QueryPart | Default behavior when no `@field` specified |
| **Renamed column** | Field name differs from column, has `@field(name: ...)` | `TABLE.OTHER_NAME.as("fieldName")` | Explicit mapping to different column |
| **Mutation RETURNING** | PostgreSQL dialect, mutation returns object | `INSERT ... RETURNING *` | Database supports RETURNING clause |
| **totalCount** | Field named `totalCount` on `*Connection` type | Separate `SELECT COUNT(*)` query | Pagination needs total count |
| **Enum mapping** | GraphQL enum type on `@table` field | Maps to DB string/int | Automatic enum conversion |

**Example of implicit split query:**
```graphql
type User @table(name: "USERS") {
  # ⚠️ Implicit @splitQuery! Has arguments, automatically becomes loader method
  posts(status: String): [Post!]! @reference(path: [{key: "FK_POSTS_USER"}])
}
```

Generated: `UserQueries.postsLoader(env)` - not an inline QueryPart, because each parent may need different `status` filter.

---

## Query Patterns

See [Quick Reference](#query-pattern-selection-guide) for when to use each pattern.

### Lookup Pattern

Fetch by known keys using `@lookupKey`:

```graphql
type Query {
  users(ids: [ID!]! @lookupKey): [User]!
  user(key: UserKey! @lookupKey): User
}
```

Returns records in order, efficient `WHERE pk IN (?)` query.

### Filter Pattern

Browse with pagination and filters:

```graphql
type Query {
  users(filter: UserFilter, first: Int, after: String): UserConnection!
}
```

### Search Pattern

Text search with `@search` or `query` argument:

```graphql
type Query {
  searchUsers(q: String! @search, first: Int): UserConnection!
}
```

### List Pattern

Small reference data only (< 100 records):

```graphql
type Query {
  countries: [Country!]!
}
```

---

## What Graphitron Generates

See [Quick Reference](#what-gets-generated) for summary.

**`<Type>Queries` classes** - One per GraphQL type with three method types:
- `fieldName(env)` - Root query execution (Query/Mutation fields)
- `fieldNameLoader(env)` - Split query loader (returns `Map<ID, Result>`)
- `buildFieldNameMultiset/Row(field)` - Inline QueryPart helpers

**DSLContext extraction:**
```java
private DSLContext getCtx(DataFetchingEnvironment env) {
    GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
    return ctx.getDslContext(env);
}
```

**DataFetchers** - Only exist as lambdas in RuntimeWiring configuration, not as separate classes.

---

## Code Generation Strategies

See [Quick Reference](#inline-vs-split-query-decision) for decision criteria.

### Inline QueryPart (Default)

Generates `buildFieldNameMultiset/Row()` helpers that return `Field<?>` QueryParts. Single SQL query fetches everything.

```graphql
type User @table(name: "USERS") {
  address: Address @reference(path: [{key: "FK_USERS_ADDRESS"}])  # row()
  orders: [Order!]! @reference(path: [{key: "FK_ORDERS_USER"}])   # multiset()
}
```

### Split Query (@splitQuery or has arguments)

Generates `fieldNameLoader(env)` returning `Map<ID, Result>`. Separate batched query via DataLoader.

```graphql
type User @table(name: "USERS") {
  orders: [Order!]! @splitQuery @reference(...)
  posts(status: String): [Post!]! @reference(...)  # Implicit split!
}
```

### @service (Use Sparingly)

Escapes to custom Java code. ⚠️ Risk N+1 on nested fields. Plan re-entry via TableRecord or Node ID.

```graphql
type User @table(name: "USERS") {
  recommendations: [Recommendation!]! @service(...)
}
```

### QueryPart Extensions

Stay in jOOQ-land while extending queries:

**@condition** - Returns `Condition` for WHERE clauses:
```java
public static Condition isActive(Posts post) {
    return post.STATUS.eq("ACTIVE").and(post.DELETED_AT.isNull());
}
```

**@externalField** - Returns `Field<T>` for calculated columns:
```java
public static Field<String> fullName(Users users) {
    return DSL.concat(users.FIRST_NAME, DSL.val(" "), users.LAST_NAME);
}
```

**@tableMethod** - Returns `Table<R>` for transforming FROM clause (views, multi-tenancy, lateral joins):
```java
public static CustomersTable customerTable(CustomersTable table, String status) {
    return "ACTIVE".equals(status) ? ACTIVE_CUSTOMERS.as(table.getName()) : table;
}
```

### Re-entry

After `@service`, return to jOOQ-land via:
- **TableRecord**: Include jOOQ `TableRecord` component with PK set → automatic batched fetch
- **Node ID**: Include Relay global ID string → automatic decode + batched fetch

Both generate DataLoader-backed queries automatically

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

### Nested relationships

```graphql
type User @table(name: "USERS") {
  address: Address @reference(path: [{key: "FK_USERS_ADDRESS"}])  # row()
  orders: [Order!]! @reference(path: [{key: "FK_ORDERS_USER"}])   # multiset()
}
```

### @condition for filtered relationships

```graphql
type User @table(name: "USERS") {
  activePosts: [Post!]! @reference(path: [{
    key: "FK_POSTS_USER",
    condition: {className: "Conditions", method: "isActive"}
  }])
}
```

### Polymorphism (single-table)

```graphql
interface Animal @table(name: "ANIMALS") { id: ID! }
type Dog implements Animal @discriminate(discriminatorValue: "DOG") { breed: String! }
type Cat implements Animal @discriminate(discriminatorValue: "CAT") { indoor: Boolean! }
```

Type resolver checks discriminator column.

### Polymorphism (multi-table)

```graphql
interface Node { id: ID! }
type User implements Node @table(name: "USERS") { name: String! }
type Order implements Node @table(name: "ORDERS") { total: Float! }
```

Type resolver based on record type.

### Multi-hop references

```graphql
type User @table(name: "USERS") {
  country: Country @reference(path: [
    {key: "FK_USER_ADDRESS"},
    {key: "FK_ADDRESS_COUNTRY"}
  ])
}
```

### Through tables (many-to-many)

```graphql
type User @table(name: "USERS") {
  roles: [Role!]! @reference(path: [
    {table: "USER_ROLES", key: "FK_USER_ROLES_USER"},
    {key: "FK_USER_ROLES_ROLE"}
  ])
}
```

### Self-references

```graphql
type User @table(name: "USERS") {
  manager: User @reference(path: [{key: "FK_USER_MANAGER"}])
  subordinates: [User!]! @reference(path: [{key: "FK_USER_MANAGER", backwards: true}])
}
```

### Input types

```graphql
input UserInput @record { name: String! }              # Java record + transformer
input DirectInput @table(name: "USERS") { name: String! }  # jOOQ TableRecord

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

## QueryPart Taxonomy

Understanding jOOQ QueryPart types:

### Field QueryParts (SELECT clause)

| Pattern | jOOQ Type | When Generated |
|---------|-----------|----------------|
| Column reference | `Field<T>` | Default for `@table` fields |
| Calculated field | `Field<T>` | `@externalField` |
| Nested multiset | `Field<Result<R>>` | List field (inline) |
| Nested row | `Field<Record>` | Singular field (inline) |
| Aggregate | `Field<T>` | `totalCount`, etc. |

### Condition QueryParts (WHERE clause)

| Pattern | When Generated |
|---------|----------------|
| User condition | `@condition` |
| Argument filter | Field with arguments |
| Lookup | `@lookupKey` |

### Table QueryParts (FROM clause)

| Pattern | When Generated |
|---------|----------------|
| Base table | Default from `@table` |
| Aliased table | Multiple refs to same table |
| Transformed table | `@tableMethod` |

### Complete Queries (Execute SQL)

| Pattern | When Generated |
|---------|----------------|
| Root query | Query/Mutation field |
| Split query | `@splitQuery` or has arguments |
| Mutation | Mutation field |
| Count query | `totalCount` field |
| Node lookup | Node interface resolution |

---

## Summary

### Core Model

- **Two Worlds:** jOOQ-land (QueryPart generation, type-safe) vs Java-land (@service, flexible but risky)
- **Goal:** Stay in jOOQ-land as much as possible
- **Escape & Return:** Use `@service` sparingly, re-enter via TableRecord or Node ID

### N+1 Rule

- **Root:** Can do any work, generates query execution methods
- **Non-root:** Trivial extraction or DataLoader-backed loader methods

### Critical Implicit Behaviors

- **Fields with arguments** → Automatic split query (loader method), even without `@splitQuery`
- **Multi-table interface** → Type resolver based on record type
- **Single-table interface** → Type resolver checks discriminator column
- **TableRecord in Java object** → Automatic re-entry loader
- **Node global ID** → Automatic decoder + batching
- **totalCount field** → Separate COUNT(*) query
- See [Implicit Behaviors](#️-critical-implicit-behaviors) for complete list

### Query Patterns

- **Lookup:** Fetch by known key(s) - `@lookupKey`
- **Filter:** Browse with filters - `*Connection`
- **Search:** Text search - `*Connection` + `@search`
- **List:** Small reference data - use sparingly

### What Graphitron Generates

- **`<Type>Queries` classes** - One per GraphQL type
- **Query execution methods** - `fieldName(env)` for root fields
- **Loader methods** - `fieldNameLoader(env)` for @splitQuery fields or fields with args
- **QueryPart helpers** - `buildFieldNameMultiset/Row()` for inline fields
- **RuntimeWiring** - Lambdas that wire fields to methods

### Queries vs QueryParts

- **Queries:** Execute complete SQL (root/@splitQuery methods)
- **QueryParts:** Compose into queries (inline helpers, @condition, @externalField, @tableMethod)

### QueryPart Types

- **Field QueryParts:** Column, calculated, multiset, row, aggregate
- **Condition QueryParts:** User condition, argument filter, lookup
- **Table QueryParts:** Base table, aliased, transformed
- **Complete Queries:** Root, split, mutation, count, node lookup
- See [QueryPart Taxonomy](#querypart-taxonomy) for details

### Decision Framework

1. **Where in schema?** Root vs nested determines method type
2. **What query pattern?** Lookup, Filter, Search, List
3. **Inline or split?** Default inline QueryPart, split to loader only when needed (or has arguments)
4. **Need custom logic?** Prefer `@condition`/`@externalField`/`@tableMethod` QueryParts, minimize `@service`
5. **Using @service?** Plan re-entry before leaving jOOQ-land

### Key Principle

**Default to the simplest approach that works.** Inline QueryParts, simple queries, staying in jOOQ-land. Only add complexity (loader methods, `@service`) when you have a specific need. **Be aware of implicit behaviors** that automatically trigger split queries or other code generation patterns.

---

**See also:**
- [graphitron-java-codegen README](../graphitron-codegen-parent/graphitron-java-codegen/README.md) - Complete directive reference
- [Graphitron Mental Model](../geps/GEP-001.1%20Graphitron%20Mental%20Model.md) - Conceptual foundation
- [Graphitron Principles](GRAPHITRON-PRINCIPLES.md) - Design philosophy
- [Vision and Goals](VISION-AND-GOAL.md) - What Graphitron is and isn't
