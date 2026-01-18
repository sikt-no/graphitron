# GEP-002: Simplify Mapping with JooqRecordDataFetcher

**Status:** Draft
**Version:** 2.0

-----

## Goal

Phase out the DTO layer and simplify result mapping by returning jOOQ Records from DataFetchers and using RuntimeWiring with a JooqRecordDataFetcher to extract values.

**Strategy:** Simplify the architecture first (GEP-002), then optimize queries (GEP-003). The current architecture is too complex to safely add selection-set-aware queries. Clean up first, then optimize.

-----

## The Problem with Current Architecture

Currently, Graphitron generates multiple layers that duplicate work:

```java
// Current flow
DataFetcher → SQL Query (fetch all columns)
           ↓
TypeMapper (check selection set, map to DTO)
           ↓
DTO → GraphQL-Java traversal
```

**Problems:**
1. **DTOs duplicate the schema** - Generated classes mirror GraphQL types
2. **TypeMapper complexity** - Selection set checking mixed with field mapping
3. **Three representations** - Record → DTO → GraphQL response
4. **Selection set checked twice** - Once in TypeMapper, again if we add query optimization
5. **Generated code size** - DTOs + TypeMappers + RecordTransformer = thousands of lines

**Current statistics:**
- ProcessedSchema: 1,323 lines queried 248 times
- TypeMappers: Check selection set and map fields
- DTOs: Mirror every GraphQL type
- Generated code is too complex to safely optimize

-----

## The Solution: Delegate to GraphQL-Java

DataFetchers return jOOQ Records. GraphQL-Java's execution engine handles traversal using RuntimeWiring.

```java
// Proposed flow
DataFetcher → SQL Query (fetch all columns)
           ↓
Record → GraphQL-Java traversal with JooqRecordDataFetcher
```

**Key insight:** GraphQL-Java's execution engine already handles selection set traversal. It only calls DataFetchers for fields that were requested in the query. We don't need to check the selection set in mapping code - GraphQL-Java does it for us.

-----

## How GraphQL-Java Handles Selection Sets

### Query Example

```graphql
query {
  users {
    id
    name
    # email NOT requested
  }
}
```

### Execution Flow

1. **GraphQL-Java calls root DataFetcher:**
   ```java
   UsersQueryDataFetcher.get(env)
   ```

2. **DataFetcher returns Records:**
   ```java
   public Result<Record> get(DataFetchingEnvironment env) {
       return ctx.select(USERS.fields())  // Fetch all columns
                 .from(USERS)
                 .fetch();
   }
   ```

3. **GraphQL-Java traverses each Record:**
   - Looks at `User` type definition
   - Sees fields: `id`, `name`, `email` with registered DataFetchers
   - **Only calls DataFetchers for fields in selection set**

4. **For each Record, GraphQL-Java calls:**
   ```java
   JooqRecordDataFetcher("id").get(env)   // ✓ Called (in selection)
   JooqRecordDataFetcher("name").get(env) // ✓ Called (in selection)
   // email DataFetcher NOT CALLED (not in selection)
   ```

**Result:** GraphQL-Java naturally handles selection set filtering. No need for TypeMapper to check selection set.

-----

## Architecture

### DataFetchers Return Records

```java
public class UsersQueryDataFetcher implements DataFetcher<Result<Record>> {
    private final DSLContext ctx;

    @Override
    public Result<Record> get(DataFetchingEnvironment env) {
        // Build and execute query
        // For now, fetch all columns (GEP-003 will optimize this)
        return ctx.select(USERS.fields())
                  .from(USERS)
                  .fetch();
    }
}
```

### RuntimeWiring with JooqRecordDataFetcher

Generate wiring that knows how to extract values from Records:

```java
RuntimeWiring.newRuntimeWiring()
    .type("Query", builder -> builder
        .dataFetcher("users", new UsersQueryDataFetcher())
    )
    .type("User", builder -> builder
        .dataFetcher("id", new JooqRecordDataFetcher(USERS.ID))
        .dataFetcher("name", new JooqRecordDataFetcher(USERS.NAME))
        .dataFetcher("email", new JooqRecordDataFetcher(USERS.EMAIL_ADDRESS))
        .dataFetcher("orders", new JooqRecordDataFetcher("orders"))
    )
    .build();
```

**Key points:**
- Root query DataFetchers are custom (execute SQL)
- Type field DataFetchers use JooqRecordDataFetcher (extract from Record)
- GraphQL-Java only calls field DataFetchers for requested fields

### JooqRecordDataFetcher Implementation

Similar to GraphQL-Java's `PropertyDataFetcher`, but knows about jOOQ Records:

```java
public class JooqRecordDataFetcher implements DataFetcher<Object> {
    private final TableField<?, ?> field;
    private final String alias;

    // Constructor for jOOQ fields
    public JooqRecordDataFetcher(TableField<?, ?> field) {
        this.field = field;
        this.alias = null;
    }

    // Constructor for aliased fields (like multisets)
    public JooqRecordDataFetcher(String alias) {
        this.field = null;
        this.alias = alias;
    }

    @Override
    public Object get(DataFetchingEnvironment env) {
        Record source = (Record) env.getSource();

        if (field != null) {
            return source.get(field);
        } else {
            return source.get(alias);
        }
    }
}
```

-----

## Generated Code Comparison

### Before (Current)

```java
// Generated DTO
public class User {
    private Integer id;
    private String name;
    private String email;
    private List<Order> orders;
    // getters/setters
}

// Generated TypeMapper
public class UserTypeMapper {
    public User map(Record record, SelectionSet selection) {
        User user = new User();
        if (selection.contains("id")) user.setId(record.get(USERS.ID));
        if (selection.contains("name")) user.setName(record.get(USERS.NAME));
        if (selection.contains("email")) user.setEmail(record.get(USERS.EMAIL_ADDRESS));
        // ...
        return user;
    }
}

// Generated DataFetcher
public class UsersQueryDataFetcher implements DataFetcher<List<User>> {
    public List<User> get(DataFetchingEnvironment env) {
        Result<Record> records = ctx.select(USERS.fields()).from(USERS).fetch();
        return records.stream()
            .map(r -> mapper.map(r, env.getSelectionSet()))
            .collect(Collectors.toList());
    }
}

// Generated Wiring
RuntimeWiring.newRuntimeWiring()
    .type("Query", builder -> builder
        .dataFetcher("users", new UsersQueryDataFetcher())
    )
    .build();
```

### After (Proposed)

```java
// No DTO class generated

// No TypeMapper generated

// Generated DataFetcher
public class UsersQueryDataFetcher implements DataFetcher<Result<Record>> {
    @Override
    public Result<Record> get(DataFetchingEnvironment env) {
        return ctx.select(USERS.fields())
                  .from(USERS)
                  .fetch();
    }
}

// Generated Wiring
RuntimeWiring.newRuntimeWiring()
    .type("Query", builder -> builder
        .dataFetcher("users", new UsersQueryDataFetcher())
    )
    .type("User", builder -> builder
        .dataFetcher("id", new JooqRecordDataFetcher(USERS.ID))
        .dataFetcher("name", new JooqRecordDataFetcher(USERS.NAME))
        .dataFetcher("email", new JooqRecordDataFetcher(USERS.EMAIL_ADDRESS))
        .dataFetcher("orders", new JooqRecordDataFetcher("orders"))
    )
    .build();
```

**Lines of code reduction:**
- Before: ~100 lines (DTO + TypeMapper + DataFetcher + Wiring)
- After: ~25 lines (DataFetcher + Wiring)
- **75% reduction in generated code**

-----

## Handling @splitQuery

Split queries work the same way - parent fetches FK/PK columns, child DataLoader uses them for lookup.

### Schema

```graphql
type User {
  id: ID!
  name: String!
  orders: [Order!]! @splitQuery
}
```

### Parent DataFetcher

```java
public Result<Record> getUsersDataFetcher() {
    return ctx.select(
        USERS.ID,          // ← PK needed for DataLoader lookup
        USERS.NAME
        // No ORDERS fetched here
    ).from(USERS).fetch();
}
```

**Key point:** We know `orders` is a split query, so we ensure `USERS.ID` (PK) is fetched. The Orders DataLoader will use these IDs.

### Child DataLoader

```java
public class UserOrdersDataLoader implements BatchLoader<Integer, List<Record>> {
    @Override
    public CompletableFuture<List<List<Record>>> load(List<Integer> userIds) {
        // Batch query using parent IDs
        Map<Integer, List<Record>> ordersByUserId = ctx
            .select(ORDERS.fields())
            .from(ORDERS)
            .where(ORDERS.USER_ID.in(userIds))
            .fetch()
            .intoGroups(ORDERS.USER_ID);

        return CompletableFuture.completedFuture(
            userIds.stream()
                .map(id -> ordersByUserId.getOrDefault(id, List.of()))
                .toList()
        );
    }
}
```

### Wiring

```java
.type("User", builder -> builder
    .dataFetcher("orders", env -> {
        Record user = (Record) env.getSource();
        Integer userId = user.get(USERS.ID);  // ← Get PK from parent Record

        DataLoader<Integer, List<Record>> loader = env.getDataLoader("UserOrders");
        return loader.load(userId);
    })
)
```

**Flow:**
1. Parent query fetches Users with PK columns
2. GraphQL-Java traverses to `orders` field (if requested)
3. Orders DataFetcher extracts PK from parent Record
4. DataLoader batches lookups
5. Returns Records, GraphQL-Java traverses Order fields

-----

## Handling Nested Inline Data

For inline relationships (no @splitQuery), use jOOQ multiset:

### Schema

```graphql
type User {
  id: ID!
  address: Address
}

type Address {
  street: String
  city: String
}
```

### DataFetcher with Multiset

```java
public Result<Record> get(DataFetchingEnvironment env) {
    return ctx.select(
        USERS.ID,
        USERS.NAME,
        multiset(
            select(ADDRESS.fields())
            .from(ADDRESS)
            .where(ADDRESS.ID.eq(USERS.ADDRESS_ID))
        ).as("address")  // ← Returns Result<Record>
    ).from(USERS).fetch();
}
```

**What multiset returns:**
- `Result<Record>` - A list of Address records
- For single relationships: list with 0 or 1 element
- For multi relationships: list with N elements

### Wiring

```java
.type("User", builder -> builder
    .dataFetcher("id", new JooqRecordDataFetcher(USERS.ID))
    .dataFetcher("address", new JooqRecordDataFetcher("address"))  // Extracts Result<Record>
)
.type("Address", builder -> builder
    .dataFetcher("street", new JooqRecordDataFetcher(ADDRESS.STREET))
    .dataFetcher("city", new JooqRecordDataFetcher(ADDRESS.CITY))
)
```

**Flow:**
1. User DataFetcher returns Records with "address" field containing `Result<Record>`
2. GraphQL-Java calls `JooqRecordDataFetcher("address")` → extracts `Result<Record>`
3. GraphQL-Java sees list, iterates each Address Record
4. For each Address Record, calls Address field DataFetchers (street, city)

**Key insight:** jOOQ multiset returns `Result<Record>`, which GraphQL-Java traverses naturally using the nested type's wiring. No special handling needed.

-----

## Field Name Mapping

The wiring generation handles GraphQL field name → jOOQ column mapping at code generation time:

```graphql
type User {
  id: ID!
  name: String!
  email: String! @field(name: "EMAIL_ADDRESS")
}
```

Generates:

```java
.type("User", builder -> builder
    .dataFetcher("id", new JooqRecordDataFetcher(USERS.ID))
    .dataFetcher("name", new JooqRecordDataFetcher(USERS.NAME))
    .dataFetcher("email", new JooqRecordDataFetcher(USERS.EMAIL_ADDRESS))  // ← Mapped
)
```

**Mapping happens at generation time, not runtime.**

-----

## The Simplify-First Strategy

### Why Simplify Before Optimize?

**Current state:**
```
ProcessedSchema (1,323 lines, 248 queries)
      ↓
DataFetchers (query logic)
      ↓
TypeMappers (selection set checking + field mapping)
      ↓
DTOs (duplicate schema structure)
      ↓
Too complex to safely add query optimization
```

**Problem:** Adding selection-set-aware queries (GEP-003) to this architecture is risky:
- Would need to modify DataFetchers AND TypeMappers
- Selection set logic would be split between query building and mapping
- Hard to test and validate correctness
- Easy to introduce subtle bugs

**Strategy:**
1. **GEP-002: Simplify first** - Remove layers, delegate to GraphQL-Java
2. **GEP-003: Optimize later** - Add selection-aware queries from stable base

### After GEP-002

```
DataFetchers (query logic only)
      ↓
Records
      ↓
GraphQL-Java traversal (handles selection naturally)
      ↓
Simple, stable base for optimization
```

**Then:** GEP-003 adds selection-aware query building in DataFetchers only. All complexity in one place.

-----

## Over-fetching Until GEP-003

**Acknowledgment:** GEP-002 accepts over-fetching at the database level.

```graphql
query {
  users {
    id
    name
  }
}
```

**Query generated:**
```sql
SELECT users.id, users.name, users.email, users.created_at, ...
FROM users
```

**Why this is acceptable:**

1. **Correctness over optimization** - Get the architecture right first
2. **Current behavior** - System already over-fetches, not a regression
3. **Clean base for optimization** - GEP-003 can focus solely on query building
4. **GraphQL-Java handles response** - Only requested fields go to client
5. **Most tables are narrow** - Over-fetching 10-15 columns is negligible

**When it matters:**
- Tables with 50+ columns
- Large TEXT/BLOB fields
- High-traffic APIs with bandwidth costs

**Solution:** GEP-003 adds selection-aware queries for these cases.

-----

## Benefits

| Aspect | Current (DTOs) | After GEP-002 |
|--------|----------------|---------------|
| **Generated code** | ~100 lines per type | ~25 lines per type |
| **Layers** | DataFetcher → TypeMapper → DTO | DataFetcher → Record |
| **Selection set handling** | TypeMapper checks | GraphQL-Java handles |
| **Mapping logic** | Imperative (if/else) | Declarative (wiring) |
| **Code complexity** | High (multiple layers) | Low (single layer) |
| **Debuggability** | Multiple layers to trace | Direct: query → record |
| **Testability** | Mock TypeMapper, DTO | Mock JooqRecordDataFetcher |
| **Foundation for GEP-003** | Complex, risky | Simple, safe |

-----

## Migration Path

1. **Phase 1:** Implement JooqRecordDataFetcher in graphitron-common (1 week)
2. **Phase 2:** Update code generator to generate wiring instead of mappers (2-3 weeks)
3. **Phase 3:** Remove DTO generation, TypeMapper generation, RecordTransformer (1 week)
4. **Phase 4:** Update documentation and examples (1 week)

**Total: 5-7 weeks**

-----

## Compatibility Notes

This is a **breaking change** for:
- Code that depends on generated DTO classes
- Custom TypeMappers or RecordTransformers
- Code that assumes DataFetchers return DTOs

**Migration:**
- Regenerate all code with new version
- Update any custom code that uses DTOs
- No migration path (can't support both)

**Recommendation:** Major version bump (e.g., 1.x → 2.0)

-----

## Trade-offs

### Accept: Over-fetching (Temporary)

**Cost:** Database fetches all columns
**Duration:** Until GEP-003 implementation
**Mitigation:**
- Most tables are narrow (10-15 columns)
- Current system already over-fetches
- Not a regression, just deferred optimization

### Accept: Generic Return Type

**Cost:** `Result<Record>` instead of `List<User>`
**Impact:** Less compile-time type safety
**Mitigation:**
- Tests catch issues
- Generated code is correct by construction
- Simpler code = fewer places for bugs

### Gain: Architectural Simplicity

**Benefit:** 75% reduction in generated code
**Impact:**
- Easier to understand
- Fewer layers to debug
- Stable base for optimization
- Foundation for GEP-003

-----

## Summary

| Aspect | Decision |
|--------|----------|
| **DataFetcher return type** | `Result<Record>` (jOOQ) |
| **Value extraction** | `JooqRecordDataFetcher` in RuntimeWiring |
| **Selection set handling** | Delegated to GraphQL-Java execution engine |
| **DTOs** | Eliminated |
| **TypeMappers** | Eliminated |
| **Mapping logic** | Declarative wiring, generated at compile time |
| **Over-fetching** | Accepted until GEP-003 |
| **Nested data** | jOOQ multiset returns `Result<Record>` |
| **@splitQuery** | Fetch PK/FK columns for DataLoader lookup |

-----

**See also:**
- GEP-001: Parse-and-Validate Architecture
- GEP-003: Selection-Set-Driven Query Generation
