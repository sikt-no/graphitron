# Code Generation Triggers

Reference for what triggers code generation in Graphitron. Based on actual implementation analysis.

---

## Trigger Matrix

| Schema Pattern | What Gets Generated | Details |
|----------------|---------------------|---------|
| **Schema Location** |
| Field on `Query` type | DataFetcher in `operations` package | Query execution method with jOOQ |
| Field on `Mutation` type | DataFetcher in `operations` package | Mutation execution method (INSERT/UPDATE/DELETE) |
| Field on object type (no args) | Field in entity DataFetcher | Direct property access from parent record |
| Field on object type (with args) | **Implicit**: Separate DataFetcher with correlated subquery | Prevents N+1, batched via resolver keys |
| **Core Mapping Directives** |
| `@table` on type | DTO, DB fetcher class, record mappers | Links GraphQL type to jOOQ table |
| `@table(name: "TABLE")` on type | Same as above | Explicit table name override |
| `@field(name: "COLUMN")` on field | Column mapping with alias | Maps field to different DB column |
| `@field(javaName: "...")` on field | Java record field mapping | Maps to different Java field name |
| `@reference(path: [...])` on field | Join logic in DB fetcher | Explicit FK navigation with jOOQ joins |
| `@multitableReference(routes: [...])` | Type-specific join paths | Different joins per interface/union member |
| **Query Behavior Directives** |
| `@splitQuery` on field | Separate DataFetcher + batched query | Forces separate query with resolver key batching |
| `@condition` on field/reference | External WHERE clause logic | Calls static method returning jOOQ `Condition` |
| `@orderBy` on argument | ORDER BY clause generation | Uses `@index` on enum values for DB index names |
| `@index(name: "IDX_")` on enum value | Links enum to DB index | Used with @orderBy for sorting |
| `@lookupKey` on argument | IN clause batch lookup | Efficient `WHERE pk IN (?)` queries |
| **Mutation Directives** |
| `@mutation(typeName: INSERT)` on field | jOOQ INSERT with RETURNING | Generates insert query |
| `@mutation(typeName: UPDATE)` on field | jOOQ UPDATE with RETURNING | Generates update query |
| `@mutation(typeName: DELETE)` on field | jOOQ DELETE | Generates delete query |
| `@mutation(typeName: UPSERT)` on field | jOOQ MERGE | Generates upsert query |
| **Relay Node Interface Directives** |
| `@node` on type | Node resolution logic + ID encoding | Enables Global Object Identification |
| `@nodeId` on field | ID encoding/decoding in mappers | Opaque Relay-style global IDs |
| `@nodeId(typeName: "Type")` on field | Type-specific ID encoding | For cross-type ID references |
| `Query.node(id: ID!): Node` field | **Implicit**: Node query implementation | Auto-generated when Node interface exists |
| **External Integration Directives** |
| `@service` on field | Service method invocation | Delegates to external Java service class |
| `@externalField` on field | Static method call for field value | Calls external Java method |
| `@tableMethod` on field | Dynamic table selection | Java method determines which jOOQ table to query |
| `@enum` on enum type | Java enum class mapping | Links to external enum via plugin config |
| `@record` on type | Java record wrapping | Wraps in specified Java record class |
| **Type Resolution Directives** |
| `@discriminate(on: "COLUMN")` on interface | Column-based type discrimination | Single-table inheritance |
| `@discriminator(value: "VAL")` on type | Discriminator value for type | Concrete type identification |
| Interface with different `@table` per impl | **Implicit**: Record-type-based resolver | Multi-table interface resolution |
| Union type | **Implicit**: Record-type-based resolver | Union member identification by jOOQ record |
| **Error Handling Directives** |
| `@error` on type | Exception-to-GraphQL-error mapping | Maps Java exceptions to error types |
| `@error` with DATABASE handler | DB error code matching | Matches on SQL error codes |
| `@error` with GENERIC handler | Exception class/message matching | Matches on exception patterns |
| **Other Directives** |
| `@notGenerated` on field/type | Skip code generation | User provides manual implementation |
| **Implicit Pagination (Relay Connection)** |
| Field type ends with `Connection` | Connection handling + pagination | Must have first/after or last/before args |
| Field with `(first: Int, after: String)` | Forward cursor pagination | PageInfo with hasNextPage |
| Field with `(last: Int, before: String)` | Backward cursor pagination | PageInfo with hasPreviousPage |
| `totalCount: Int!` on Connection type | Separate COUNT(*) query | Only executed if selected |
| `edges`, `pageInfo`, `nodes` on Connection | Extract from pagination result | Standard Relay connection fields |
| **Implicit Behaviors** |
| Field references `@table` type (no @reference) | **Implicit**: Join via jOOQ FK metadata | Auto-detects foreign keys |
| Field on non-root type with arguments | **Implicit**: Separate DataFetcher + subquery | Prevents N+1 queries |
| GraphQL enum on field | **Implicit**: Enum to DB string/int mapping | Type conversion |
| PostgreSQL + mutation returns object | **Implicit**: RETURNING clause | Uses DB RETURNING support |

---

## Generated Code Structure

For each `@table` type, Graphitron generates:

| Generated Class | Package | Purpose |
|-----------------|---------|---------|
| `TypeDTO.java` | `models` | Java record with all fields |
| `TypeInputDTO.java` | `models` | Input DTO if input type exists |
| `TypeDBFetcher.java` | `db` | jOOQ database query methods |
| `TypeDataFetcher.java` | `entities` | GraphQL DataFetchers for entity fields |
| `TypeRecordMapper.java` | `mappers` | jOOQ record ↔ DTO transformation |
| `TypeJavaRecordMapper.java` | `mappers` | Java record transformation (if `@record`) |

For Query/Mutation fields:
- DataFetchers in `operations` package
- One class per root type (QueryOperations, MutationOperations)

Global generators:
- `TypeRegistry.java` - Type registration
- `Wiring.java` - Connects DataFetchers to schema
- `ExceptionStrategyConfiguration.java` - Error handling
- `ExceptionToErrorMappingProvider.java` - Exception mapping
- Type resolvers for interfaces/unions

---

## Selection-Set-Driven Queries

Graphitron generates queries that only fetch requested fields.

**GraphQL query:**
```graphql
{
  customers {
    id
    name
  }
}
```

**Generated SQL:**
```sql
SELECT id, name FROM customers
```

**With nested fields:**
```graphql
{
  customers {
    id
    name
    address {
      city
    }
  }
}
```

**Generated SQL:**
```sql
SELECT
  customers.id,
  customers.name,
  (SELECT city FROM address WHERE address.id = customers.address_id) as address
FROM customers
```

Only joins/subqueries for selected fields are generated.

---

## Schema Transform vs Code Generation

**Schema Transform** (`@asConnection`, `@feature`, Federation directives):
- Runs BEFORE code generation
- Transforms GraphQL schema
- `@asConnection` expands `[Type]` to full Relay Connection types
- Output schema is then passed to code generator

**Code Generation** (all other directives):
- Runs AFTER schema transformation
- Generates Java classes from final schema
- Uses transformed schema with full Connection types

**Example:**

Input schema:
```graphql
type Query {
  users: [User] @asConnection
}
```

After transform:
```graphql
type Query {
  users(first: Int = 100, after: String): QueryUsersConnection
}

type QueryUsersConnection {
  edges: [QueryUsersConnectionEdge]
  pageInfo: PageInfo
  nodes: [User]
  totalCount: Int
}
```

Code generator sees the transformed schema.

---

## Quick Directive Lookup

**Want to...** | **Use...**
---|---
Map type to table | `@table` or `@table(name: "TABLE")`
Map field to column | `@field(name: "COLUMN")`
Join to related table | `@reference(path: [...])`
Create separate query | `@splitQuery`
Filter results | `@condition`
Sort results | `@orderBy` + `@index`
Batch lookup | `@lookupKey`
Insert data | `@mutation(typeName: INSERT)`
Update data | `@mutation(typeName: UPDATE)`
Delete data | `@mutation(typeName: DELETE)`
Use global IDs | `@node` + `@nodeId`
Call custom code | `@service`
Transform table | `@tableMethod`
Skip generation | `@notGenerated`
Add pagination | Return `*Connection` type (or use `@asConnection` in schema)
Handle errors | `@error`
Single-table inheritance | `@discriminate` + `@discriminator`

---

**See also:**
- [graphitron-java-codegen README](../graphitron-codegen-parent/graphitron-java-codegen/README.md) - Complete directive reference
- [Graphitron Principles](GRAPHITRON-PRINCIPLES.md) - Design philosophy
- [Example Schema](../graphitron-example/graphitron-example-spec/src/main/resources/graphql/schema.graphqls) - Real usage examples
