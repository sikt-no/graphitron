# Code Generation Triggers

This document is a reference for what triggers code generation in Graphitron. For vocabulary and concepts, see [What Graphitron Generates](WHAT-GRAPHITRON-GENERATES.md).

---

## Trigger Matrix

This table shows what generates what. Look up your schema pattern to see what gets generated.

| Schema Pattern | Trigger | Generated Code | Method Type |
|----------------|---------|----------------|-------------|
| **Schema Location** |
| Field on `Query` type | Schema location | `QueryDBQueries.<field>ForQuery(...)` | Query execution method |
| Field on `Mutation` type | Schema location | `MutationDBQueries.<field>ForMutation(...)` | Query execution method |
| Field on object type (no args, no directives) | Schema location + `@reference` | `<Type>DBQueries.fieldNameForType(...)` | QueryPart helper |
| **Directives** |
| `@table` on type | Directive | Enables QueryPart generation for this type | - |
| `@reference` on field (no `@splitQuery`, no args) | Directive | `fieldNameForType()` method | QueryPart helper |
| `@reference` + `@splitQuery` on field | Directive | DataLoader via `DataFetcherHelper` | Loader method |
| `@splitQuery` on field | Directive | DataLoader via `DataFetcherHelper` | Loader method |
| `@service` on field | Directive | Calls user service method | - |
| `@condition` on field/argument | Directive | User method returns `Condition` QueryPart | QueryPart extension |
| `@externalField` on field | Directive | User method returns `Field<T>` QueryPart | QueryPart extension |
| `@tableMethod` on field | Directive | User method returns `Table<R>` QueryPart | QueryPart extension |
| `@field(name: "...")` on field | Directive | `TABLE.OTHER_NAME.as("fieldName")` | Field mapping |
| `@lookupKey` on argument | Directive | `WHERE pk IN (?)` lookup logic | Query logic |
| `@orderBy` on argument | Directive | `ORDER BY` with index validation | Query logic |
| `@node` on type | Directive | Node interface resolver + global ID | Type resolver |
| `@nodeId` on field | Directive | Global ID encoding/decoding for field | ID handling |
| `@discriminate` on interface/union | Directive | Specifies discriminator column | Type resolver config |
| `@discriminator` on type | Directive | Specifies discriminator value for type | Type resolver |
| `@record` on type | Directive | Java record + transformer to jOOQ | Input mapping |
| `@mutation` on field | Directive | INSERT/UPDATE/DELETE/UPSERT query | Mutation method |
| `@notGenerated` on field | Directive | Skip generation (user implements) | - |
| **Implicit Behaviors** |
| Field has GraphQL arguments (except root) | **Implicit** | DataLoader via `DataFetcherHelper` | Loader method |
| Field name matches column name | **Implicit** | `TABLE.COLUMN` | Direct column |
| Return type is `*Connection` | **Implicit** | Relay pagination logic | Query logic |
| Field named `totalCount` on `*Connection` | **Implicit** | Separate `SELECT COUNT(*)` | Count query |
| Field named `edges` on `*Connection` | **Implicit** | Extract from pagination result | Trivial |
| Field named `pageInfo` on `*Connection` | **Implicit** | Extract from pagination result | Trivial |
| Field named `nodes` on `*Connection` | **Implicit** | Extract from pagination result | Trivial |
| GraphQL enum on `@table` field | **Implicit** | Enum to DB string/int mapping | Type mapping |
| Mutation returns object (PostgreSQL) | **Implicit** | `INSERT ... RETURNING *` | Mutation logic |
| Interface with different `@table` per impl | **Implicit** | Type resolver by record type | Type resolver |
| Interface with `@discriminate` | **Implicit** | Type resolver by discriminator | Type resolver |
| Union type | **Implicit** | Type resolver by record type | Type resolver |
| `@reference` with multi-hop path | **Implicit** | Nested joins or multi-step loader | QueryPart/Loader |
| `@reference` through intermediate table | **Implicit** | JOIN through tables | QueryPart/Loader |
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
  # Generates: QueryDBQueries.userForQuery(...)
  user(id: ID!): User

  # Generates: QueryDBQueries.usersForQuery(...)
  users(first: Int, after: String): UserConnection!
}

type Mutation {
  # Generates: MutationDBQueries.createUserForMutation(...)
  createUser(input: CreateUserInput!): User!
}
```

**Nested fields (no args, no directives)** → QueryPart helper

```graphql
type User @table(name: "USERS") {
  # Generates: UserDBQueries.addressForUser(...)
  address: Address @reference(path: [{key: "FK_USERS_ADDRESS"}])

  # Generates: UserDBQueries.ordersForUser(...)
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
- No `@splitQuery`, no args → `fieldNameForType()` (QueryPart helper)
- With `@splitQuery` → DataLoader via `DataFetcherHelper`
- With args → DataLoader via `DataFetcherHelper` (implicit split)

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

**Reference path elements** can contain:
- `table`: The jOOQ table to connect to
- `key`: The key to use to create this reference
- `condition`: Extra condition for this reference (ExternalCodeReference)

#### @splitQuery

**Trigger:** `@splitQuery` on field
**Generated:** DataLoader via `DataFetcherHelper.load()` or `DataFetcherHelper.loadPaginated()`
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

**Trigger:** `@condition` on field, argument, or input field
**Generated:** User method returns `Condition` QueryPart
**Usage:** Added to WHERE clause of generated query

The `@condition` directive can be used:
1. Standalone on a field/argument
2. Inside a `@reference` path element

```graphql
type Query {
  # Standalone @condition with override
  activeCustomers: [Customer]
    @condition(condition: {className: "CustomerConditions", method: "isActive"}, override: true)
}

type User @table(name: "USERS") {
  # @condition inside reference path
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

**Trigger:** `@tableMethod(tableMethodReference: {...})` on field
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

The directive also supports `javaName` for Java record field mapping.

#### @lookupKey

**Trigger:** `@lookupKey` on argument or input field
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

Uses the `@index` directive on enum values to specify which database index to use:

```graphql
type Query {
  users(orderBy: UserOrderByInput @orderBy): [User]!
}

input UserOrderByInput {
  orderByField: UserOrderByFields!
  direction: OrderDirection!
}

enum UserOrderByFields {
  NAME @index(name: "IDX_USERS_NAME")
  CREATED_AT @index(name: "IDX_USERS_CREATED")
}
```

#### @node

**Trigger:** `@node` on type
**Generated:** Node interface resolver + global ID encoding/decoding
**Usage:** Relay Node interface support

```graphql
type User implements Node @node @table(name: "USERS") {
  id: ID! @nodeId  # Global ID
  name: String!
}
```

Optional parameters:
- `typeId`: Custom type identifier embedded in the ID
- `keyColumns`: Defines the order of primary/unique key columns

#### @nodeId

**Trigger:** `@nodeId` on field, input field, or argument
**Generated:** Global ID encoding/decoding for the field
**Usage:** Mark a field as a globally unique ID per Relay specification

```graphql
type User implements Node @node @table(name: "USERS") {
  id: ID! @nodeId
}

input UserInput @table(name: "USERS") {
  userId: ID! @nodeId(typeName: "User")  # Reference to User's global ID
}
```

Optional parameter:
- `typeName`: The node type the ID belongs to (if not inferrable)

#### @discriminate and @discriminator

For single-table inheritance patterns, use two directives together:

**@discriminate** on interface/union:
- Specifies which column contains the discriminator value
- Syntax: `@discriminate(on: "COLUMN_NAME")`

**@discriminator** on implementing types:
- Specifies the value that identifies this subtype
- Syntax: `@discriminator(value: "VALUE")`

```graphql
interface Animal @table(name: "ANIMALS") @discriminate(on: "TYPE") {
  id: ID!
}

type Dog implements Animal @table(name: "ANIMALS") @discriminator(value: "DOG") {
  breed: String!
}

type Cat implements Animal @table(name: "ANIMALS") @discriminator(value: "CAT") {
  indoor: Boolean!
}
```

**Generated:** Type resolver checks `ANIMALS.TYPE` column value

#### @record

**Trigger:** `@record(record: {...})` on object or input type
**Generated:** Java record + transformer to jOOQ Record
**Usage:** Input type uses a custom Java record class

```graphql
input UserInput @record(record: {className: "UserJavaInput"}) {
  name: String!
  email: String!
}
```

**Alternative:** Use `@table` for direct jOOQ Record mapping

#### @mutation

**Trigger:** `@mutation(typeName: INSERT|UPDATE|DELETE|UPSERT)` on Mutation field
**Generated:** INSERT/UPDATE/DELETE/UPSERT query
**Usage:** CRUD mutations

```graphql
type Mutation {
  createUser(input: CreateUserInput!): User! @mutation(typeName: INSERT)
  updateUser(id: ID!, input: UpdateUserInput!): User! @mutation(typeName: UPDATE)
  deleteUser(id: ID!): Boolean! @mutation(typeName: DELETE)
}
```

#### @notGenerated

**Trigger:** `@notGenerated` on field, argument, input field, interface, or union
**Generated:** Nothing (user provides implementation)
**Usage:** Custom implementation needed

```graphql
type User @table(name: "USERS") {
  complexCalculation: Float! @notGenerated
}
```

---

### Schema Transformation Directives

These directives are processed by `graphitron-schema-transform` before code generation:

#### @asConnection

**Trigger:** `@asConnection` on list field
**Effect:** Transforms the field to return a Relay Connection type
**Usage:** Convert list fields to paginated connections

```graphql
type Query {
  # Before transformation: returns [Customer]
  # After transformation: returns CustomerConnection with edges, pageInfo, etc.
  customers: [Customer] @asConnection
}
```

Optional parameters:
- `defaultFirstValue`: Default page size (default: 100)
- `connectionName`: Custom connection type name

---

### Implicit Triggers

These triggers happen **without any directive** - just based on schema structure.

#### Field Has Arguments (Non-Root)

**Trigger:** Field has GraphQL arguments, not on Query/Mutation
**Generated:** DataLoader via `DataFetcherHelper`
**Why:** Can't inline a QueryPart that needs different arguments for each parent

```graphql
type User @table(name: "USERS") {
  # Implicit split! Has arguments - generates DataLoader
  posts(status: String): [Post!]! @reference(path: [{key: "FK_POSTS_USER"}])
}
```

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
  totalCount: Int!  # Generates separate count query
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

#### Interface with @discriminate

**Trigger:** Interface with `@discriminate` directive
**Generated:** Type resolver checking discriminator column
**Why:** Single-table inheritance

```graphql
interface Animal @table(name: "ANIMALS") @discriminate(on: "TYPE") { id: ID! }
type Dog implements Animal @discriminator(value: "DOG") { breed: String! }
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

#### Multi-Hop Reference Path

**Trigger:** `@reference` with multiple path entries
**Generated:** Nested joins or multi-step loader
**Why:** Navigate through intermediate tables

```graphql
type User @table(name: "USERS") {
  # Navigate: User → Address → Country
  country: Country @reference(path: [
    {key: "FK_USER_ADDRESS"},
    {table: "COUNTRY"}
  ])
}
```

#### Reference Through Intermediate Tables

**Trigger:** `@reference` with path through multiple tables
**Generated:** JOIN through intermediate tables
**Why:** Navigate relationships

```graphql
type Film @table(name: "FILM") {
  # Film → Inventory → Store → Address → City
  citiesWhereStocked: [City] @splitQuery @reference(path: [
    {key: "INVENTORY__INVENTORY_FILM_ID_FKEY"},
    {table: "STORE"},
    {table: "ADDRESS"},
    {table: "CITY"}
  ])
}
```

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
}
```

**Generated:** `USERS.as("manager_...")` to avoid collision

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
Root Query field | Query execution method in `QueryDBQueries`
Root Mutation field | Mutation execution method in `MutationDBQueries`
Nested field with no args, no directives | QueryPart helper (inline)
Nested field with `@splitQuery` | DataLoader via `DataFetcherHelper`
Nested field with arguments | DataLoader (implicit split)
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
Interface with `@discriminate` | Type resolver (discriminator column)
Type with `@discriminator` | Discriminator value for type
Union type | Type resolver (record type)
GraphQL enum field | Enum mapping
Field with `@nodeId` | Global ID handling

---

**See also:**
- [What Graphitron Generates](WHAT-GRAPHITRON-GENERATES.md) - Vocabulary and concepts
- [graphitron-java-codegen README](../graphitron-codegen-parent/graphitron-java-codegen/README.md) - Complete directive reference
- [Graphitron Principles](GRAPHITRON-PRINCIPLES.md) - Design philosophy
