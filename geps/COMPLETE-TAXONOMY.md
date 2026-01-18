# Graphitron Taxonomy: Complete Analysis from Tests

## Patterns Found in Tests (Missing from Initial Taxonomy)

### 1. **Implicit Split Query** ⚠️ CRITICAL IMPLICIT BEHAVIOR

**When:** Field has GraphQL arguments (except on root Query/Mutation)

```graphql
type User @table(name: "USERS") {
  posts(status: String): [Post!]! @reference(...)  # ← Implicit @splitQuery!
}
```

**Why:** Can't inline a `multiset()` QueryPart that needs different arguments for each parent.

**Generated:** Automatically becomes a loader method, even without `@splitQuery` directive.

**Test location:** `queries/fetch/output/implicitSplitQuery/`

---

### 2. **@tableMethod Directive** - Table QueryPart Transformation

**What:** Transforms or filters the `Table<R>` QueryPart itself

**Different from `@condition`:**
- `@condition` → adds to WHERE clause (Condition QueryPart)
- `@tableMethod` → transforms the Table QueryPart (e.g., filtering via views, lateral joins)

```graphql
type Query {
  customer(status: String): Customer
    @tableMethod(tableMethodReference: {
      className: "CustomerTableMethod",
      method: "customerTable"
    })
}
```

```java
public class CustomerTableMethod {
    public CustomersTable customerTable(CustomersTable table, String status) {
        // Return transformed table (could be a view, lateral join, etc.)
        return ACTIVE_CUSTOMERS.as(table.getName());
    }
}
```

**Use case:** Multi-tenancy, soft deletes, views, complex table transformations

**Test location:** `queries/fetch/tableMethod/`

---

### 3. **Interface/Union Polymorphism** - Type Resolution

#### Single-Table Interface Pattern

**What:** Multiple GraphQL types backed by same table, discriminated by column

```graphql
interface Animal @table(name: "ANIMALS") {
  id: ID!
  name: String!
}

type Dog implements Animal @discriminate(discriminatorValue: "DOG") {
  id: ID!
  name: String!
  breed: String!
}

type Cat implements Animal @discriminate(discriminatorValue: "CAT") {
  id: ID!
  name: String!
  indoor: Boolean!
}
```

**Generated:** Type resolver checks discriminator column to determine concrete type.

**Test location:** `queries/fetch/interfaces/singleTableInterface/`

#### Multi-Table Interface Pattern

**What:** Multiple GraphQL types backed by different tables

```graphql
interface Node {
  id: ID!
}

type User implements Node @table(name: "USERS") {
  id: ID!
  name: String!
}

type Order implements Node @table(name: "ORDERS") {
  id: ID!
  total: Float!
}
```

**Generated:** Type resolver based on source record type (which jOOQ table).

**Test location:** `queries/fetch/interfaces/multitableInterface/`

#### Multi-Table Union Pattern

Similar to multi-table interface but for unions:

```graphql
union SearchResult = User | Order | Product
```

**Test location:** `queries/fetch/union/multitableUnion/`

---

### 4. **Relay Node Interface** - Global ID Re-entry

**Pattern:** Relay global ID decoding for re-entry to jOOQ-land

```graphql
type Query {
  node(id: ID!): Node  # Decodes "User:123" → query USERS table
}

interface Node @node {
  id: ID!
}

type User implements Node @table(name: "USERS") {
  id: ID!
}
```

**Generated:**
- Node ID decoder
- Type-based routing to appropriate table
- DataLoader batching per type

**Test locations:**
- `queries/fetch/nodeDirective/`
- `queries/fetch/nodeId/`
- `queries/fetch/interfaces/node/`

---

### 5. **Input Type Patterns** - Java Records vs jOOQ Records

#### Input as Java Record

```graphql
input UserInput @record {
  name: String!
  email: String!
}

type Mutation {
  createUser(input: UserInput!): User!
}
```

**Generated:** Maps to Java record, transformer converts to jOOQ Record

#### Input as jOOQ Record (Direct)

```graphql
input UserInput @table(name: "USERS") {
  name: String! @field(name: "USER_NAME")
  email: String!
}
```

**Generated:** Maps directly to jOOQ TableRecord, no transformation needed

**Test locations:**
- `queries/fetch/records/inputJavaRecord/`
- `queries/fetch/records/inputJOOQRecord/`

---

### 6. **Return Types Without @table** - Java Records

```graphql
type RecommendationResult {
  score: Float!
  reason: String!
  product: Product!  # Re-entry field
}

type Query {
  recommendations: [RecommendationResult!]!
    @service(service: {className: "RecService", method: "get"})
}
```

**Pattern:** Type has no `@table` → must come from `@service`, represents Java record

**Test locations:**
- `queries/fetch/records/returningTypeWithoutTable/`
- `queries/fetch/records/returningListedTypeWithoutTable/`

---

### 7. **Condition Argument Patterns** - Complex Parameter Passing

#### Condition with Field Override

```graphql
type Query {
  users(status: String @field(name: "USER_STATUS")): [User!]!
}

type User @table(name: "USERS") {
  id: ID!
  posts(postStatus: PostStatus): [Post!]!
    @reference(path: [{
      condition: {className: "Conditions", method: "byStatus", parameterBindings: [{argumentName: "postStatus", parameterName: "status"}]}
    }])
}
```

```java
public static Condition byStatus(Posts post, PostStatus status) {
    return status != null ? post.STATUS.eq(status.name()) : DSL.trueCondition();
}
```

**Test location:** `queries/fetch/conditions/onFieldOverride/`

#### Condition with Context Arguments

```graphql
type Query {
  users: [User!]!
}

type User @table(name: "USERS") {
  posts: [Post!]!
    @reference(path: [{
      condition: {className: "Conditions", method: "forTenant", parameterBindings: [{contextArgumentName: "tenantId"}]}
    }])
}
```

**Context arguments:** Values from GraphQL context (not query arguments)

**Test location:** `queries/fetch/conditions/onFieldWithContextField/`

---

### 8. **Sorting and Ordering** - @orderBy with Indexes

```graphql
type Query {
  users(orderBy: [UserOrderBy!]): [User!]!
}

type User @table(name: "USERS") {
  id: ID! @orderBy(index: "IDX_USER_ID")
  name: String! @orderBy(index: "IDX_USER_NAME")
  createdAt: DateTime! @orderBy(index: "IDX_USER_CREATED")
}

enum UserOrderBy {
  ID_ASC
  ID_DESC
  NAME_ASC
  NAME_DESC
  CREATED_AT_ASC
  CREATED_AT_DESC
}
```

**Generated:** ORDER BY clause using specified indexes

**Test locations:**
- `queries/fetch/orderby/`
- `queries/fetch/sorting/`

---

### 9. **Connection Pagination** - totalCount Query

```graphql
type UserConnection {
  edges: [UserEdge!]!
  pageInfo: PageInfo!
  totalCount: Int!  # ← Separate COUNT(*) query
}
```

**Implicit behavior:** `totalCount` generates separate query with same WHERE conditions

**Test location:** `queries/fetch/count/`

---

### 10. **Nested References** - Multi-hop Paths

```graphql
type User @table(name: "USERS") {
  # Direct reference
  address: Address @reference(path: [{key: "FK_USER_ADDRESS"}])

  # Multi-hop reference
  country: Country @reference(path: [
    {key: "FK_USER_ADDRESS"},
    {key: "FK_ADDRESS_COUNTRY"}
  ])
}
```

**Generated:** Nested joins in inline QueryPart or multi-hop in split query

**Test locations:**
- `queries/fetch/references/subquery/keyWithMultiplePaths/`
- `queries/fetch/references/splitQuery/keyWithMultiplePaths/`

---

### 11. **Through Tables** - Junction Table Navigation

```graphql
type User @table(name: "USERS") {
  # Through junction table
  roles: [Role!]! @reference(path: [
    {table: "USER_ROLES", key: "FK_USER_ROLES_USER"},
    {key: "FK_USER_ROLES_ROLE"}
  ])
}
```

**Test location:** `queries/fetch/references/subquery/throughTable/`

---

### 12. **Self-References** - Recursive Relationships

```graphql
type User @table(name: "USERS") {
  manager: User @reference(path: [{key: "FK_USER_MANAGER"}])
  subordinates: [User!]! @reference(path: [{key: "FK_USER_MANAGER", backwards: true}])
}
```

**Test locations:**
- `queries/fetch/references/subquery/selfKeyReference/`
- `queries/fetch/references/splitQuery/selfKeyReference/`

---

### 13. **Batched Mutations** - Bulk Operations

```graphql
type Mutation {
  createUsers(inputs: [UserInput!]!): [User!]!
}
```

**Generated:** Batch insert using jOOQ's batch API

**Test location:** `queries/edit/withBatching/`

---

### 14. **Mutation Return Patterns**

#### RETURNING clause (PostgreSQL)

```graphql
type Mutation {
  createUser(input: UserInput!): User!
}
```

**Generated:** `INSERT ... RETURNING *` to get full record back

**Test location:** `queries/edit/returningResult/`

#### Without RETURNING

**Generated:** `INSERT` then separate `SELECT` by PK

---

### 15. **Field Aliases** - Multiple Fields to Same Relationship

```graphql
type User @table(name: "USERS") {
  recentOrders: [Order!]! @reference(path: [{key: "FK_ORDER_USER", condition: ...}])
  allOrders: [Order!]! @reference(path: [{key: "FK_ORDER_USER"}])
}
```

**Challenge:** Both map to same FK, need different aliases in generated SQL

**Test location:** `queries/fetch/references/alias/`

---

### 16. **Correlated Subquery Optimization**

**When:** Nested references that need to correlate with outer query

**Test location:** `queries/fetch/helperMethods/correlatedSubqueryReferences/`

---

### 17. **Enum Handling**

```graphql
enum Status {
  ACTIVE
  INACTIVE
  PENDING
}

type User @table(name: "USERS") {
  status: Status!
}
```

**Generated:** Maps GraphQL enum to database string/int

**Test location:** `queries/fetch/enums/`

---

### 18. **Apollo Federation @key and @external**

```graphql
type User @key(fields: "id") @table(name: "USERS") {
  id: ID!
  name: String!
  email: String! @external
}
```

**Test location:** `queries/fetch/entity/`

---

## Complete Implicit Behavior Matrix

| Pattern | Directive Needed? | Implicit Trigger | Generated |
|---------|-------------------|------------------|-----------|
| **Inline nested** | No | Field returns `@table` type, no args | `multiset()`/`row()` QueryPart helper |
| **Split query (explicit)** | Yes: `@splitQuery` | Developer choice | Loader method |
| **Split query (implicit)** | No | Field has arguments | Loader method |
| **Root query** | No | Field on Query/Mutation type | Query execution method |
| **TableRecord re-entry** | No | Java record contains jOOQ TableRecord | Loader method extracts PK, batches |
| **Node ID re-entry** | No | Field returns Node, receives global ID | Decoder + type routing + batching |
| **Single-table interface** | Yes: `@discriminate` | Interface with discriminatorValue | Type resolver checks column |
| **Multi-table interface** | No | Interface, types have different `@table` | Type resolver checks record type |
| **Direct column mapping** | No | Field name = column name on `@table` | Direct `TABLE.COLUMN` Field QueryPart |
| **Renamed column** | Yes: `@field` | Field name ≠ column name | `TABLE.OTHER_NAME.as("fieldName")` |
| **Calculated field** | Yes: `@externalField` | Needs SQL expression | Calls user method returning `Field<T>` |
| **Filtered relationship** | Yes: `@condition` | Needs WHERE clause on nested query | Calls user method returning `Condition` |
| **Transformed table** | Yes: `@tableMethod` | Needs table transformation | Calls user method returning `Table<R>` |
| **Service escape** | Yes: `@service` | Needs non-SQL logic | Calls user service method |
| **Mutation RETURNING** | No | PostgreSQL available | `INSERT ... RETURNING *` |
| **totalCount** | No | Field on Connection type | Separate `COUNT(*)` query |
| **Enum mapping** | No | GraphQL enum type | Maps to DB string/int |
| **Java record input** | Yes: `@record` | Input type is Java record | Transformer to jOOQ Record |
| **jOOQ record input** | Yes: `@table` on input | Input type is jOOQ Record | Direct usage |

---

## QueryPart Taxonomy (Complete)

### Field QueryParts

| Pattern | jOOQ Type | Purpose | When Generated |
|---------|-----------|---------|----------------|
| **Column reference** | `Field<T>` | Direct column | Default for fields on `@table` |
| **Calculated field** | `Field<T>` | SQL expression | `@externalField` |
| **Nested multiset** | `Field<Result<R>>` | One-to-many inline | List field, inline |
| **Nested row** | `Field<Record>` | Many-to-one inline | Singular field, inline |
| **Aggregate** | `Field<T>` | COUNT, SUM, etc. | totalCount, aggregates |

### Condition QueryParts

| Pattern | Purpose | When Generated |
|---------|---------|----------------|
| **User condition** | Filter nested query | `@condition` directive |
| **Argument condition** | Filter by argument | Field with filter arguments |
| **Lookup condition** | BY PK/unique key | `@lookupKey` directive |

### Table QueryParts

| Pattern | Purpose | When Generated |
|---------|---------|----------------|
| **Base table** | FROM clause | Default from `@table` |
| **Aliased table** | Avoid naming collision | Multiple refs to same table |
| **Transformed table** | View, lateral join, etc. | `@tableMethod` directive |

### Complete Queries

| Pattern | Executes | When Generated |
|---------|----------|----------------|
| **Root query** | `SELECT ... FROM ... WHERE ...` | Query/Mutation field |
| **Split query** | Batched `SELECT ... WHERE pk IN (?)` | `@splitQuery` or field with args |
| **Mutation** | `INSERT/UPDATE/DELETE` | Mutation field |
| **Count query** | `SELECT COUNT(*)` | totalCount field |
| **Node lookup** | `SELECT ... WHERE pk = ?` | Node interface resolution |

---

## Critical Gaps in Initial Taxonomy

1. ✅ **Implicit split query** - Fields with arguments auto-split
2. ✅ **@tableMethod** - Table QueryPart transformation
3. ✅ **Polymorphism patterns** - Single-table vs multi-table
4. ✅ **Relay Node re-entry** - Global ID decoding
5. ✅ **Input type variants** - Java records vs jOOQ Records
6. ✅ **Condition parameter binding** - Context args, field overrides
7. ✅ **Multi-hop references** - Path navigation
8. ✅ **Through tables** - Junction tables
9. ✅ **Self-references** - Recursive relationships
10. ✅ **Batched mutations** - Bulk operations
11. ✅ **RETURNING vs SELECT** - Mutation return patterns
12. ✅ **Field aliases** - Multiple fields to same FK
13. ✅ **Enum mapping** - GraphQL to DB
14. ✅ **totalCount implicit query** - Separate COUNT(*)

---

## Summary: What Graphitron Actually Does

**Graphitron is a QueryPart composition engine that:**

1. **Analyzes your GraphQL schema** to understand data requirements
2. **Maps to jOOQ tables** via `@table` and related directives
3. **Generates QueryParts** (Field, Condition, Table) that compose into complete queries
4. **Respects the N+1 rule** by defaulting to inline composition, requiring explicit split
5. **Provides escape hatches** (`@service`, `@tableMethod`, `@condition`, `@externalField`) for custom logic while staying in SQL generation when possible
6. **Handles implicit behaviors** like auto-splitting fields with arguments, enum mapping, polymorphism patterns
7. **Generates DataLoader-backed loaders** for any query that can't be inlined (split, re-entry, implicit split)
8. **Validates everything at build time** against your jOOQ catalog

**The key insight:** It's all about composing jOOQ QueryParts into efficient SQL, driven by GraphQL selection sets.
