# What Graphitron Generates

This document describes the vocabulary and taxonomy of generated code. For what triggers code generation, see [Code Generation Triggers](CODE-GENERATION-TRIGGERS.md).

---

## The Big Picture

```
GraphQL Schema + @directives
         ↓ Graphitron generates
┌─────────────────────────────────────────────────────────────┐
│  Graphitron.java          (entry point)                     │
│       ↓                                                     │
│  Wiring.java              (connects fetchers to schema)     │
│       ↓                                                     │
│  *GeneratedDataFetcher    (one per GraphQL type)            │
│       ↓                                                     │
│  *DBQueries               (SQL query builders)              │
│       ↓                                                     │
│  RecordTransformer        (jOOQ ↔ DTO conversion)           │
│  *TypeMapper              (field-level mapping)             │
└─────────────────────────────────────────────────────────────┘
```

Graphitron generates these main artifacts:

1. **Graphitron.java** - Main entry point for your application
2. **Wiring.java** - RuntimeWiring connecting GraphQL fields to Java methods
3. **\*GeneratedDataFetcher.java** - DataFetcher implementations per GraphQL type
4. **\*DBQueries.java** - jOOQ query builders per GraphQL type
5. **RecordTransformer.java** - Converts between jOOQ Records and DTOs
6. **\*TypeMapper.java** - Maps individual fields between records and DTOs
7. **DTOs** - Java classes matching GraphQL types
8. **TypeRegistry.java** - Loads GraphQL schema from classpath

---

## Generated Class Structure

### Entry Point

**Graphitron.java** - The main entry point:

```java
public class Graphitron {
    public static TypeDefinitionRegistry getTypeRegistry() {
        return TypeRegistry.getTypeRegistry();
    }

    public static RuntimeWiring.Builder getRuntimeWiringBuilder() {
        return Wiring.getRuntimeWiringBuilder();
    }

    public static RuntimeWiring getRuntimeWiring() {
        return getRuntimeWiringBuilder().build();
    }

    public static GraphQLSchema getSchema() {
        var wiring = getRuntimeWiringBuilder();
        var registry = getTypeRegistry();
        return new SchemaGenerator().makeExecutableSchema(registry, wiring.build());
    }
}
```

### RuntimeWiring

**Wiring.java** - Connects GraphQL fields to DataFetchers:

```java
public class Wiring {
    public static RuntimeWiring.Builder getRuntimeWiringBuilder() {
        var wiring = RuntimeWiring.newRuntimeWiring();
        wiring.type(TypeRuntimeWiring.newTypeWiring("Query")
            .dataFetcher("customer", QueryGeneratedDataFetcher.customer()));
        wiring.type(TypeRuntimeWiring.newTypeWiring("Customer")
            .dataFetcher("orders", CustomerGeneratedDataFetcher.orders()));
        return wiring;
    }
}
```

### DataFetchers

**\*GeneratedDataFetcher.java** - One class per GraphQL type with fields that need fetching:

```java
public class QueryGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<Customer>> customer() {
        return _iv_env -> {
            return new DataFetcherHelper(_iv_env).load(
                (_iv_ctx, _iv_selectionSet) ->
                    QueryDBQueries.customerForQuery(_iv_ctx, _iv_selectionSet)
            );
        };
    }
}
```

For mutations with input transformation:

```java
public class MutationGeneratedDataFetcher {
    public static DataFetcher<CompletableFuture<String>> createCustomer() {
        return _iv_env -> {
            CustomerInput _mi_in = ResolverHelpers.transformDTO(
                _iv_env.getArgument("input"), CustomerInput.class);
            var _iv_transform = new RecordTransformer(_iv_env);
            var _mi_inRecord = _iv_transform.customerInputToJOOQRecord(_mi_in, "input");

            return new DataFetcherHelper(_iv_env).load(
                (_iv_ctx, _iv_selectionSet) ->
                    MutationDBQueries.createCustomerForMutation(_iv_ctx, _mi_inRecord, _iv_selectionSet)
            );
        };
    }
}
```

### Database Query Builders

**\*DBQueries.java** - One class per GraphQL type containing jOOQ query logic:

```java
public class QueryDBQueries {
    public static Customer customerForQuery(DSLContext _iv_ctx, SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return _iv_ctx
            .select(customerForQuery_customer())
            .from(_a_customer)
            .fetchOne(_iv_it -> _iv_it.into(Customer.class));
    }

    private static SelectField<Customer> customerForQuery_customer() {
        var _a_customer = CUSTOMER.as("customer_2168032777");
        return DSL.row(_a_customer.getId(), _a_customer.getName())
            .mapping(Functions.nullOnAllNull(Customer::new));
    }
}
```

For split queries (DataLoader pattern):

```java
public class CustomerDBQueries {
    public static Map<Row1<Long>, List<Order>> ordersForCustomer(
            DSLContext _iv_ctx,
            Set<Row1<Long>> _rk_customer,
            SelectionSet _iv_select) {
        var _a_customer = CUSTOMER.as("customer_123");
        var _a_order = _a_customer.order().as("order_456");
        return _iv_ctx
            .select(
                DSL.row(_a_customer.CUSTOMER_ID),
                DSL.multiset(
                    DSL.select(ordersForCustomer_order())
                        .from(_a_order)
                        .orderBy(_a_order.ORDER_ID)
                )
            )
            .from(_a_customer)
            .where(DSL.row(_a_customer.CUSTOMER_ID).in(_rk_customer))
            .fetchMap(
                _iv_r -> _iv_r.value1().valuesRow(),
                _iv_r -> _iv_r.value2().map(Record1::value1)
            );
    }
}
```

### Record Transformation

**RecordTransformer.java** - Converts between jOOQ Records and DTOs:

```java
public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment _iv_env) {
        super(_iv_env);
    }

    // jOOQ Record → DTO
    public List<Customer> customerRecordToGraphType(
            List<CustomerRecord> _mi_input, String _iv_path) {
        return CustomerTypeMapper.recordToGraphType(_mi_input, _iv_path, this);
    }

    // DTO → jOOQ Record
    public List<CustomerRecord> customerInputToJOOQRecord(
            List<CustomerInput> _mi_input, String _iv_path) {
        return CustomerInputJOOQMapper.toJOOQRecord(_mi_input, _iv_path, this);
    }
}
```

**\*TypeMapper.java** - Field-level mapping with selection-set awareness:

```java
public class CustomerTypeMapper {
    public static List<Customer> recordToGraphType(
            List<CustomerRecord> _mi_customerRecord,
            String _iv_path,
            RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _iv_select = _iv_transform.getSelect();
        var _mlo_customer = new ArrayList<Customer>();

        if (_mi_customerRecord != null) {
            for (var _nit_customerRecord : _mi_customerRecord) {
                if (_nit_customerRecord == null) continue;
                var _mo_customer = new Customer();

                // Only map fields that were requested
                if (_iv_select.contains(_iv_pathHere + "id")) {
                    _mo_customer.setId(_nit_customerRecord.getId());
                }
                if (_iv_select.contains(_iv_pathHere + "name")) {
                    _mo_customer.setName(_nit_customerRecord.getName());
                }

                _mlo_customer.add(_mo_customer);
            }
        }
        return _mlo_customer;
    }
}
```

---

## Naming Conventions

### Class Names

| Generated Class | Naming Pattern | Example |
|-----------------|----------------|---------|
| Entry point | `Graphitron` | `Graphitron.java` |
| Wiring | `Wiring` | `Wiring.java` |
| Type registry | `TypeRegistry` | `TypeRegistry.java` |
| DataFetcher | `{Type}GeneratedDataFetcher` | `QueryGeneratedDataFetcher.java` |
| DB Queries | `{Type}DBQueries` | `CustomerDBQueries.java` |
| Transformer | `RecordTransformer` | `RecordTransformer.java` |
| Type Mapper | `{Type}TypeMapper` | `CustomerTypeMapper.java` |
| JOOQ Mapper | `{Type}JOOQMapper` | `CustomerInputJOOQMapper.java` |
| Type Resolver | `{Type}TypeResolver` | `AnimalTypeResolver.java` |
| DTO | `{GraphQLTypeName}` | `Customer.java` |

### Method Names

| Method Type | Pattern | Example |
|-------------|---------|---------|
| Query method | `{field}For{Container}` | `customerForQuery` |
| Count method | `count{Field}For{Container}` | `countCustomersForQuery` |
| Entity method | `{type}AsEntity` | `customerAsEntity` |
| Type resolver | `{type}TypeResolver` | `animalTypeResolver` |
| Record → DTO | `{type}RecordToGraphType` | `customerRecordToGraphType` |
| DTO → Record | `{type}ToJOOQRecord` | `customerInputToJOOQRecord` |

### Variable Prefixes

Generated code uses consistent prefixes for clarity:

| Prefix | Meaning | Example |
|--------|---------|---------|
| `_iv_` | Internal variable | `_iv_ctx`, `_iv_env`, `_iv_select` |
| `_mi_` | Method input | `_mi_input`, `_mi_after` |
| `_mo_` | Method output (single) | `_mo_customer` |
| `_mlo_` | Method list output | `_mlo_customers` |
| `_a_` | Table alias | `_a_customer`, `_a_order` |
| `_rk_` | Row key (for batching) | `_rk_customer` |
| `_nit_` | Named iterator | `_nit_customerRecord` |

---

## Query Patterns

### Inline Nested Data (row and multiset)

For nested relationships without `@splitQuery`, Graphitron generates correlated subqueries:

**Many-to-one (single record)** uses `DSL.row()`:

```java
// User.address (User has one Address)
DSL.row(
    DSL.field(
        DSL.select(buildAddress())
            .from(_a_address)
    )
)
```

**One-to-many (list)** uses `DSL.multiset()`:

```java
// User.orders (User has many Orders)
DSL.multiset(
    DSL.select(buildOrder())
        .from(_a_order)
        .orderBy(_a_order.ORDER_ID)
)
```

### Split Queries (DataLoader Pattern)

For fields with `@splitQuery` or GraphQL arguments, Graphitron generates batched queries:

```java
// Returns Map<ParentKey, List<ChildRecord>>
public static Map<Row1<Long>, List<Order>> ordersForCustomer(
        DSLContext _iv_ctx,
        Set<Row1<Long>> _rk_customer,  // Batched parent keys
        SelectionSet _iv_select) {
    // ...
    .where(DSL.row(_a_customer.CUSTOMER_ID).in(_rk_customer))
    .fetchMap(...)
}
```

### Pagination

Paginated queries include cursor handling:

```java
public static Map<Row1<Long>, List<Pair<String, Customer>>> customersForQuery(
        DSLContext _iv_ctx,
        Set<Row1<Long>> _rk_query,
        Integer _iv_pageSize,
        String _mi_after,           // Cursor
        SelectionSet _iv_select) {
    // ...
    .orderBy(_iv_orderFields)
    .seek(QueryHelper.getOrderByValues(_iv_ctx, _iv_orderFields, _mi_after))
    .limit(_iv_pageSize + 1)        // +1 to detect hasNextPage
    // ...
}
```

---

## Package Structure

Generated code is organized into packages:

```
generated/
├── graphitron/
│   └── Graphitron.java           # Entry point
├── wiring/
│   └── Wiring.java               # RuntimeWiring
├── typeregistry/
│   └── TypeRegistry.java         # Schema loading
├── resolvers/
│   ├── operations/
│   │   ├── QueryGeneratedDataFetcher.java
│   │   └── MutationGeneratedDataFetcher.java
│   └── typeresolver/
│       └── AnimalTypeResolver.java
├── queries/
│   ├── QueryDBQueries.java
│   ├── CustomerDBQueries.java
│   └── OrderDBQueries.java
├── transform/
│   └── RecordTransformer.java
├── mappers/
│   ├── CustomerTypeMapper.java
│   └── CustomerInputJOOQMapper.java
├── model/
│   ├── Customer.java             # DTOs
│   ├── Order.java
│   └── CustomerInput.java
└── exceptions/
    ├── GeneratedExceptionStrategyConfiguration.java
    └── GeneratedExceptionToErrorMappingProvider.java
```

---

## Key Concepts

### Selection-Set-Driven Projection

Generated code respects the GraphQL selection set - only fetching and mapping fields that were actually requested:

```java
// In TypeMapper
if (_iv_select.contains(_iv_pathHere + "email")) {
    _mo_customer.setEmail(_nit_customerRecord.getEmail());
}
```

### DataFetcherHelper

The `DataFetcherHelper` class handles:
- DSLContext acquisition from environment
- Async execution wrapping
- DataLoader integration for batched queries

```java
new DataFetcherHelper(_iv_env).load(
    (_iv_ctx, _iv_selectionSet) -> QueryDBQueries.customerForQuery(_iv_ctx, _iv_selectionSet)
);
```

### Table Aliasing

All table references are aliased with deterministic names to avoid collisions:

```java
var _a_customer = CUSTOMER.as("customer_2168032777");
var _a_customer_order = _a_customer.order().as("order_1234567");
```

---

## Vocabulary Summary

| Term | What It Is |
|------|------------|
| **Graphitron** | Entry point class providing schema and wiring |
| **Wiring** | RuntimeWiring connecting fields to fetchers |
| **GeneratedDataFetcher** | DataFetcher implementation for a GraphQL type |
| **DBQueries** | jOOQ query builder methods for a GraphQL type |
| **RecordTransformer** | Converts jOOQ Records to/from DTOs |
| **TypeMapper** | Field-level mapping with selection awareness |
| **DataFetcherHelper** | Helper for async execution and batching |
| **row()** | jOOQ correlated subquery for single record |
| **multiset()** | jOOQ correlated subquery for list of records |
| **SelectionSet** | GraphQL fields that were requested |

---

**See also:**
- [Code Generation Triggers](CODE-GENERATION-TRIGGERS.md) - What schema patterns trigger what code
- [graphitron-java-codegen README](../graphitron-codegen-parent/graphitron-java-codegen/README.md) - Complete directive reference
- [Graphitron Principles](GRAPHITRON-PRINCIPLES.md) - Design philosophy
