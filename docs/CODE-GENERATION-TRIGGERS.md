# Code Generation Triggers

This document is a reference for what triggers code generation in Graphitron. For vocabulary and concepts, see [What Graphitron Generates](WHAT-GRAPHITRON-GENERATES.md).

---

## Trigger Matrix

This table shows what generates what. Look up your schema pattern to see what gets generated.

| Schema Pattern | Trigger | Generated Code | Method Type |
|----------------|---------|----------------|-------------|
| **Schema Location** |
| Field on `Query` type | Schema location | `QueryQueries.fieldName(env)` | Query execution method |
| Field on `Mutation` type | Schema location | `MutationQueries.fieldName(env)` | Query execution method |
| Field on object type (no args, no directives) | Schema location + `@reference` | `<Type>Queries.buildFieldNameMultiset/Row()` | QueryPart helper |
| **Directives** |
| `@table` on type | Directive | Enables QueryPart generation for this type | - |
| `@reference` on field (no `@splitQuery`, no args) | Directive | `buildFieldNameMultiset()` or `buildFieldNameRow()` | QueryPart helper |
| `@reference` + `@splitQuery` on field | Directive | `fieldNameLoader(env)` | Loader method |
| `@splitQuery` on field | Directive | `fieldNameLoader(env)` | Loader method |
| `@service` on field | Directive | Calls user service method | - |
| `@condition` in `@reference` path | Directive | User method returns `Condition` QueryPart | QueryPart extension |
| `@externalField` on field | Directive | User method returns `Field<T>` QueryPart | QueryPart extension |
| `@tableMethod` on field | Directive | User method returns `Table<R>` QueryPart | QueryPart extension |
| `@field(name: "...")` on field | Directive | `TABLE.OTHER_NAME.as("fieldName")` | Field mapping |
| `@lookupKey` on argument | Directive | `WHERE pk IN (?)` lookup logic | Query logic |
| `@orderBy` on argument | Directive | `ORDER BY` with index validation | Query logic |
| `@search` on argument | Directive | Full-text search integration | Query logic |
| `@node` on type | Directive | Node interface resolver + global ID | Type resolver |
| `@discriminate` on type | Directive | Discriminator-based type resolver | Type resolver |
| `@record` on input type | Directive | Java record + transformer to jOOQ | Input mapping |
| `@mutation` on field | Directive | INSERT/UPDATE/DELETE query | Mutation method |
| `@notGenerated` on field | Directive | Skip generation (user implements) | - |
| **Implicit Behaviors** |
| Field has GraphQL arguments (except root) | **Implicit** | `fieldNameLoader(env)` | Loader method |
| Field name matches column name | **Implicit** | `TABLE.COLUMN` | Direct column |
| Return type is `*Connection` | **Implicit** | Relay pagination logic | Query logic |
| Field named `totalCount` on `*Connection` | **Implicit** | Separate `SELECT COUNT(*)` | Count query |
| Field named `edges` on `*Connection` | **Implicit** | Extract from pagination result | Trivial |
| Field named `pageInfo` on `*Connection` | **Implicit** | Extract from pagination result | Trivial |
| Field named `nodes` on `*Connection` | **Implicit** | Extract from pagination result | Trivial |
| GraphQL enum on `@table` field | **Implicit** | Enum to DB string/int mapping | Type mapping |
| Mutation returns object (PostgreSQL) | **Implicit** | `INSERT ... RETURNING *` | Mutation logic |
| Interface with different `@table` per impl | **Implicit** | Type resolver by record type | Type resolver |
| Interface with `@discriminate` on impls | **Implicit** | Type resolver by discriminator | Type resolver |
| Union type | **Implicit** | Type resolver by record type | Type resolver |
| Java record contains `TableRecord` | **Implicit** | Re-entry loader (PK extraction) | Loader method |
| Field returns `Node`, receives global ID | **Implicit** | Decoder + type routing + loader | Loader method |
| `@reference` with multi-hop path | **Implicit** | Nested joins or multi-step loader | QueryPart/Loader |
| `@reference` through junction table | **Implicit** | JOIN through junction | QueryPart/Loader |
| `@reference` with `backwards: true` | **Implicit** | Reversed FK navigation | QueryPart/Loader |
| **Special Cases** |
| Same FK, different `@condition` | Schema pattern | Aliased QueryParts to avoid collision | Multiple QueryParts |
| Self-reference (same table) | Schema pattern | Aliased table references | QueryPart logic |
| Composite key in `@lookupKey` | Schema pattern | Multi-column WHERE clause | Query logic |
| Batched mutation (list input) | Schema pattern | jOOQ batch API | Mutation method |

---

## Details by Category

### Schema Location Triggers

**Root Query/Mutation fields** → Query execution method

Where a field appears in your schema determines what gets generated:

```graphql
type Query {
  # Generates: QueryQueries.user(env)
  user(id: ID!): User

  # Generates: QueryQueries.users(env)
  users(first: Int, after: String): UserConnection!
}

type Mutation {
  # Generates: MutationQueries.createUser(env)
  createUser(input: CreateUserInput!): User!
}
```

**Nested fields (no args, no directives)** → QueryPart helper

```graphql
type User @table(name: "USERS") {
  # Generates: UserQueries.buildAddressRow(field)
  address: Address @reference(path: [{key: "FK_USERS_ADDRESS"}])

  # Generates: UserQueries.buildOrdersMultiset(field)
  orders: [Order!]! @reference(path: [{key: "FK_ORDERS_USER"}])
}
```

---

### Directive Triggers

#### @table

**Trigger:** `@table(name: "TABLE_NAME")` on object type
**Generated:** Enables all QueryPart generation for this type
**Effect:** Fields can now use `@reference`, direct column mapping, etc.

```graphql
type User @table(name: "USERS") {
  id: ID!  # Maps to USERS.ID
  name: String!  # Maps to USERS.NAME
}
```

#### @reference

**Trigger:** `@reference(path: [...])` on field
**Generated:** Depends on other factors:
- No `@splitQuery`, no args → `buildFieldNameMultiset/Row()` (QueryPart helper)
- With `@splitQuery` → `fieldNameLoader(env)` (Loader method)
- With args → `fieldNameLoader(env)` (Loader method, implicit split)

```graphql
type User @table(name: "USERS") {
  # Inline QueryPart
  address: Address @reference(path: [{key: "FK_USERS_ADDRESS"}])

  # Split query loader
  orders: [Order!]! @splitQuery @reference(path: [{key: "FK_ORDERS_USER"}])

  # Implicit split (has arguments)
  posts(status: String): [Post!]! @reference(path: [{key: "FK_POSTS_USER"}])
}
```

#### @splitQuery

**Trigger:** `@splitQuery` on field
**Generated:** `fieldNameLoader(env)` returning `Map<ID, Result>`
**Purpose:** Force separate batched query via DataLoader

```graphql
type User @table(name: "USERS") {
  # Large collection, rarely requested
  activityLog: [Activity!]! @splitQuery @reference(...)
}
```

#### @service

**Trigger:** `@service(service: {className: "...", method: "..."})` on field
**Generated:** Call to user-provided service method
**Effect:** Escapes jOOQ QueryPart generation, calls custom Java code

```graphql
type User @table(name: "USERS") {
  recommendations: [Recommendation!]!
    @service(service: {className: "RecService", method: "forUser"})
}
```

#### @condition

**Trigger:** `@condition` in `@reference` path
**Generated:** User method returns `Condition` QueryPart
**Usage:** Added to WHERE clause of generated query

```graphql
type User @table(name: "USERS") {
  activePosts: [Post!]! @reference(path: [{
    key: "FK_POSTS_USER",
    condition: {className: "Conditions", method: "isActive"}
  }])
}
```

**User provides:**
```java
public static Condition isActive(Posts post) {
    return post.STATUS.eq("ACTIVE").and(post.DELETED_AT.isNull());
}
```

#### @externalField

**Trigger:** `@externalField` on field
**Generated:** User method returns `Field<T>` QueryPart
**Usage:** Added to SELECT clause of generated query

```graphql
type User @table(name: "USERS") {
  firstName: String!
  lastName: String!
  fullName: String! @externalField
}
```

**User provides:**
```java
public static Field<String> fullName(Users users) {
    return DSL.concat(users.FIRST_NAME, DSL.val(" "), users.LAST_NAME);
}
```

#### @tableMethod

**Trigger:** `@tableMethod` on field
**Generated:** User method returns `Table<R>` QueryPart
**Usage:** Transforms the FROM clause table

```graphql
type Query {
  customer(status: String): Customer
    @tableMethod(tableMethodReference: {
      className: "CustomerTableMethod",
      method: "customerTable"
    })
}
```

**User provides:**
```java
public static CustomersTable customerTable(CustomersTable table, String status) {
    return "ACTIVE".equals(status) ? ACTIVE_CUSTOMERS.as(table.getName()) : table;
}
```

#### @field

**Trigger:** `@field(name: "COLUMN_NAME")` on field
**Generated:** Column mapping with alias
**Usage:** Map field to different column name

```graphql
type User @table(name: "USERS") {
  email: String! @field(name: "EMAIL_ADDRESS")
}
```

**Generated:** `USERS.EMAIL_ADDRESS.as("email")`

#### @lookupKey

**Trigger:** `@lookupKey` on argument
**Generated:** Primary/unique key lookup with ordered results
**Usage:** Efficient `WHERE pk IN (?)` lookup

```graphql
type Query {
  users(ids: [ID!]! @lookupKey): [User]!
  user(key: UserKey! @lookupKey): User
}
```

**Generated:** Returns records in same order as input keys

#### @orderBy

**Trigger:** `@orderBy` on argument
**Generated:** ORDER BY clause with index validation
**Usage:** Enable sorting on indexed columns

```graphql
type Query {
  users(orderBy: UserOrderBy): [User]!
}

input UserOrderBy {
  name: SortDirection @orderBy(index: "IDX_USERS_NAME")
  createdAt: SortDirection @orderBy(index: "IDX_USERS_CREATED")
}
```

#### @search

**Trigger:** `@search` on argument
**Generated:** Full-text search integration
**Usage:** Text search queries

```graphql
type Query {
  searchUsers(q: String! @search, first: Int): UserConnection!
}
```

#### @node

**Trigger:** `@node` on type
**Generated:** Node interface resolver + global ID encoding/decoding
**Usage:** Relay Node interface support

```graphql
type User implements Node @node @table(name: "USERS") {
  id: ID!  # Global ID
  name: String!
}
```

#### @discriminate

**Trigger:** `@discriminate(discriminatorValue: "...")` on type
**Generated:** Type resolver using discriminator column
**Usage:** Single-table inheritance

```graphql
interface Animal @table(name: "ANIMALS") { id: ID! }
type Dog implements Animal @discriminate(discriminatorValue: "DOG") { breed: String! }
type Cat implements Animal @discriminate(discriminatorValue: "CAT") { indoor: Boolean! }
```

**Generated:** Type resolver checks `ANIMALS.TYPE` column

#### @record

**Trigger:** `@record` on input type
**Generated:** Java record + transformer to jOOQ Record
**Usage:** Input type doesn't match table structure

```graphql
input UserInput @record {
  name: String!
  email: String!
}
```

**Alternative:** Use `@table` for direct jOOQ Record mapping

#### @mutation

**Trigger:** `@mutation(typeName: INSERT|UPDATE|DELETE)` on Mutation field
**Generated:** INSERT/UPDATE/DELETE query
**Usage:** CRUD mutations

```graphql
type Mutation {
  createUser(input: CreateUserInput!): User! @mutation(typeName: INSERT)
  updateUser(id: ID!, input: UpdateUserInput!): User! @mutation(typeName: UPDATE)
  deleteUser(id: ID!): Boolean! @mutation(typeName: DELETE)
}
```

#### @notGenerated

**Trigger:** `@notGenerated` on field
**Generated:** Nothing (user provides implementation)
**Usage:** Custom implementation needed

```graphql
type User @table(name: "USERS") {
  complexCalculation: Float! @notGenerated
}
```

---

### Implicit Triggers

These triggers happen **without any directive** - just based on schema structure.

#### Field Has Arguments (Non-Root)

**Trigger:** Field has GraphQL arguments, not on Query/Mutation
**Generated:** `fieldNameLoader(env)` (Loader method)
**Why:** Can't inline a QueryPart that needs different arguments for each parent

```graphql
type User @table(name: "USERS") {
  # ⚠️ Implicit @splitQuery! Has arguments
  posts(status: String): [Post!]! @reference(path: [{key: "FK_POSTS_USER"}])
}
```

**Generated:** `UserQueries.postsLoader(env)` - NOT an inline QueryPart

#### Field Name Matches Column Name

**Trigger:** Field name matches database column name (on `@table` type)
**Generated:** Direct `TABLE.COLUMN` mapping
**Why:** Default behavior

```graphql
type User @table(name: "USERS") {
  id: ID!      # Maps to USERS.ID
  name: String!  # Maps to USERS.NAME
  email: String! # Maps to USERS.EMAIL
}
```

#### Return Type is *Connection

**Trigger:** Field returns `TypeConnection`
**Generated:** Relay pagination logic (cursor-based)
**Why:** Convention for pagination

```graphql
type Query {
  users(first: Int, after: String): UserConnection!
}
```

**Generated:** Pagination with `edges`, `pageInfo`, `nodes`

#### Field Named totalCount on *Connection

**Trigger:** Field named `totalCount` on Connection type
**Generated:** Separate `SELECT COUNT(*)` query
**Why:** Pagination needs total count

```graphql
type UserConnection {
  edges: [UserEdge!]!
  pageInfo: PageInfo!
  totalCount: Int!  # ← Generates separate count query
}
```

#### Connection Fields (edges, pageInfo, nodes)

**Trigger:** Standard Connection fields
**Generated:** Trivial extraction from pagination result
**Why:** Standard Relay pagination

```graphql
type UserConnection {
  edges: [UserEdge!]!    # Extract from result
  pageInfo: PageInfo!     # Extract from result
  nodes: [User!]!         # Extract from result
}
```

#### GraphQL Enum on @table Field

**Trigger:** GraphQL enum type on `@table` field
**Generated:** Automatic enum to DB string/int mapping
**Why:** Type-safe enum handling

```graphql
enum UserStatus { ACTIVE, INACTIVE, SUSPENDED }

type User @table(name: "USERS") {
  status: UserStatus!  # Maps to USERS.STATUS (string)
}
```

#### Mutation Returns Object (PostgreSQL)

**Trigger:** PostgreSQL dialect + mutation returns object type
**Generated:** `INSERT ... RETURNING *`
**Why:** Database supports RETURNING clause

```graphql
type Mutation {
  createUser(input: CreateUserInput!): User!
}
```

**Generated SQL:** `INSERT INTO users (...) VALUES (...) RETURNING *`

#### Interface with Different @table Per Implementation

**Trigger:** Interface implemented by types with different `@table` directives
**Generated:** Type resolver based on record type (which jOOQ table)
**Why:** Each implementation has different backing table

```graphql
interface Node { id: ID! }
type User implements Node @table(name: "USERS") { name: String! }
type Order implements Node @table(name: "ORDERS") { total: Float! }
```

**Generated:** Type resolver checks record type (`UsersRecord` vs `OrdersRecord`)

#### Interface with @discriminate on Implementations

**Trigger:** Interface with `@discriminate` on all implementations
**Generated:** Type resolver checking discriminator column
**Why:** Single-table inheritance

```graphql
interface Animal @table(name: "ANIMALS") { id: ID! }
type Dog implements Animal @discriminate(discriminatorValue: "DOG") { breed: String! }
```

**Generated:** Type resolver checks `ANIMALS.TYPE` column value

#### Union Type

**Trigger:** GraphQL union type
**Generated:** Type resolver based on record type
**Why:** Heterogeneous results from different tables

```graphql
union SearchResult = User | Order | Product
```

**Generated:** Type resolver checks record type

#### Java Record Contains TableRecord

**Trigger:** `@service` returns Java record containing jOOQ `TableRecord` component
**Generated:** Re-entry loader that extracts PK and batches query
**Why:** Need to fetch full record from database

```java
public record RecommendationResult(
    float score,
    ProductsRecord productRecord  // ← Has PK set
) {}
```

**Generated:** Automatic loader to fetch full `Product` records

#### Field Returns Node, Receives Global ID

**Trigger:** Field returns `Node` interface, receives global ID string
**Generated:** Decoder + type routing + DataLoader batching
**Why:** Relay Node interface re-entry

```graphql
type Recommendation {
  relatedItem: Node!  # Receives "Product:123" global ID
}
```

**Generated:** Decodes ID, routes to correct table, batches query

#### Multi-Hop Reference Path

**Trigger:** `@reference` with multiple path entries
**Generated:** Nested joins or multi-step loader
**Why:** Navigate through intermediate tables

```graphql
type User @table(name: "USERS") {
  country: Country @reference(path: [
    {key: "FK_USER_ADDRESS"},
    {key: "FK_ADDRESS_COUNTRY"}
  ])
}
```

**Generated:** `User → Address → Country` navigation

#### Reference Through Junction Table

**Trigger:** `@reference` with explicit junction table
**Generated:** JOIN through junction table
**Why:** Many-to-many relationship

```graphql
type User @table(name: "USERS") {
  roles: [Role!]! @reference(path: [
    {table: "USER_ROLES", key: "FK_USER_ROLES_USER"},
    {key: "FK_USER_ROLES_ROLE"}
  ])
}
```

**Generated:** `User → USER_ROLES → Role` joins

#### Backwards Reference

**Trigger:** `@reference` with `backwards: true`
**Generated:** Reversed FK navigation
**Why:** Navigate FK in reverse direction

```graphql
type User @table(name: "USERS") {
  subordinates: [User!]! @reference(path: [{
    key: "FK_USER_MANAGER",
    backwards: true
  }])
}
```

**Generated:** Find users where `manager_id = current_user.id`

---

### Special Cases

#### Same FK, Different Conditions

**Trigger:** Multiple fields using same FK with different `@condition`
**Generated:** Aliased QueryParts to avoid naming collisions
**Why:** Same relationship, different filters

```graphql
type User @table(name: "USERS") {
  activePosts: [Post!]! @reference(path: [{
    key: "FK_POSTS_USER",
    condition: {className: "Conditions", method: "isActive"}
  }])

  draftPosts: [Post!]! @reference(path: [{
    key: "FK_POSTS_USER",
    condition: {className: "Conditions", method: "isDraft"}
  }])
}
```

**Generated:** Properly aliased to avoid collision

#### Self-Reference

**Trigger:** `@reference` to same table
**Generated:** Aliased table references
**Why:** Avoid naming collisions

```graphql
type User @table(name: "USERS") {
  manager: User @reference(path: [{key: "FK_USER_MANAGER"}])
  subordinates: [User!]! @reference(path: [{
    key: "FK_USER_MANAGER",
    backwards: true
  }])
}
```

**Generated:** `USERS.as("managers")` to avoid collision

#### Composite Key Lookup

**Trigger:** `@lookupKey` with composite key input type
**Generated:** Multi-column WHERE clause
**Why:** Lookup by multiple columns

```graphql
type Query {
  usersByTenant(keys: [TenantUserKey!]! @lookupKey): [User]!
}

input TenantUserKey {
  tenantId: ID!
  userId: ID!
}
```

**Generated:** `WHERE (tenant_id, user_id) IN ((?, ?), (?, ?), ...)`

#### Batched Mutation

**Trigger:** Mutation with list input
**Generated:** jOOQ batch API
**Why:** Bulk operations

```graphql
type Mutation {
  createUsers(inputs: [CreateUserInput!]!): [User!]!
}
```

**Generated:** Single batch INSERT

---

## Quick Lookup by Schema Element

**If you have...** | **Then Graphitron generates...**
---|---
Root Query field | Query execution method
Root Mutation field | Mutation execution method
Nested field with no args, no directives | QueryPart helper (inline)
Nested field with `@splitQuery` | Loader method
Nested field with arguments | Loader method (implicit split)
Field with `@service` | Service call
Type with `@table` | QueryPart generation enabled
Field with `@reference` (default) | QueryPart helper
Field with `@condition` | User QueryPart extension
Field with `@externalField` | User QueryPart extension
Field with `@tableMethod` | User QueryPart extension
Argument with `@lookupKey` | Lookup logic
Return type `*Connection` | Pagination logic
Field named `totalCount` | Count query
Interface with different tables | Type resolver (record type)
Interface with `@discriminate` | Type resolver (discriminator)
Union type | Type resolver (record type)
GraphQL enum field | Enum mapping
Java record with `TableRecord` | Re-entry loader

---

**See also:**
- [What Graphitron Generates](WHAT-GRAPHITRON-GENERATES.md) - Vocabulary and concepts
- [graphitron-java-codegen README](../graphitron-codegen-parent/graphitron-java-codegen/README.md) - Complete directive reference
- [Graphitron Principles](GRAPHITRON-PRINCIPLES.md) - Design philosophy
