# GEP-002: Simplify Mapping with JooqRecordDataFetcher

**Status:** Draft
**Version:** 1.0

-----

## Goal

Phase out the DTO layer and simplify result mapping by returning jOOQ Records from DataFetchers and using RuntimeWiring with a JooqRecordDataFetcher (equivalent to PropertyDataFetcher) to extract values.

-----

## The Problem with DTOs

Currently, Graphitron generates:
1. **DTOs** - Plain Java classes matching GraphQL types
2. **TypeMappers** - Convert jOOQ Records to DTOs
3. **RecordTransformer** - Coordinates the mapping

This creates unnecessary layers:

```java
// Current flow
jOOQ Record → TypeMapper → DTO → GraphQL-Java traversal
```

**Problems:**
- DTOs duplicate the schema structure in generated code
- Mappers add complexity and maintenance burden
- Selection set awareness happens in mappers, not queries
- Three representations of the same data (Record, DTO, GraphQL response)

-----

## The Solution: Return Records Directly

DataFetchers return jOOQ Records. GraphQL-Java traverses the result using RuntimeWiring that extracts values directly from Records.

```java
// Proposed flow
jOOQ Record → GraphQL-Java traversal with JooqRecordDataFetcher
```

-----

## Architecture

### DataFetchers Return Records

```java
public class UsersQueryDataFetcher implements DataFetcher<Result<Record>> {
    private final DSLContext ctx;

    @Override
    public Result<Record> get(DataFetchingEnvironment env) {
        // Build and execute query
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
    .type("User", builder -> builder
        .dataFetcher("id", new JooqRecordDataFetcher(USERS.ID))
        .dataFetcher("name", new JooqRecordDataFetcher(USERS.NAME))
        .dataFetcher("email", new JooqRecordDataFetcher(USERS.EMAIL_ADDRESS))
        .dataFetcher("orders", new JooqRecordDataFetcher("orders"))
    )
    .build();
```

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

## Generated Code Changes

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
        Result<Record> records = /* execute query */;
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

-----

## Handling Nested Data

For nested fields (like one-to-many relationships), use jOOQ's multiset or JSON aggregation:

```java
public Result<Record> get(DataFetchingEnvironment env) {
    return ctx.select(
        USERS.ID,
        USERS.NAME,
        USERS.EMAIL_ADDRESS,
        multiset(
            select(ORDERS.fields())
            .from(ORDERS)
            .where(ORDERS.USER_ID.eq(USERS.ID))
        ).as("orders")
    )
    .from(USERS)
    .fetch();
}
```

The `"orders"` alias is a string key in the Record that the JooqRecordDataFetcher can extract:

```java
.dataFetcher("orders", new JooqRecordDataFetcher("orders"))
```

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
    .dataFetcher("email", new JooqRecordDataFetcher(USERS.EMAIL_ADDRESS))  // mapped
)
```

-----

## Benefits

| Aspect | Current (DTOs) | Proposed (Records) |
|--------|----------------|-------------------|
| **Generated classes** | DTOs + TypeMappers + RecordTransformer | Only DataFetchers + Wiring |
| **Data flow** | Record → DTO → Response | Record → Response |
| **Mapping logic** | TypeMapper code | Declarative wiring |
| **Code size** | Large (duplicate schema as DTOs) | Small (minimal boilerplate) |
| **Debuggability** | Multiple layers to trace | Direct: query → record → response |
| **Selection set** | Mapper checks selection set | No runtime checking needed |

-----

## Migration Path

1. **Phase 1:** Implement JooqRecordDataFetcher in graphitron-common
2. **Phase 2:** Update code generator to generate wiring instead of mappers
3. **Phase 3:** Remove DTO generation, TypeMapper generation, RecordTransformer
4. **Phase 4:** Update documentation and examples

-----

## Compatibility Notes

This is a **breaking change** for:
- Code that depends on generated DTO classes
- Custom TypeMappers or RecordTransformers
- Code that assumes DataFetchers return DTOs

Migration requires regenerating all code with the new version.

-----

## Summary

| Aspect | Decision |
|--------|----------|
| DataFetcher return type | `Result<Record>` (jOOQ) |
| Value extraction | `JooqRecordDataFetcher` in RuntimeWiring |
| DTOs | Eliminated |
| TypeMappers | Eliminated |
| Mapping logic | Declarative wiring, generated at compile time |
| Nested data | jOOQ multiset/JSON aggregation |

-----

**See also:**
- GEP-001: Parse-and-Validate Architecture
- GEP-003: Selection-Set-Driven Query Generation
