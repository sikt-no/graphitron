# Plan: Phase Out DTO Layer — Record-Based Output

## Context

The current code generation pipeline produces output DTOs and TypeMapper classes that convert jOOQ Records into those DTOs. This is unnecessary: graphql-java's `RuntimeWiring` can resolve fields directly from any Java object, including jOOQ `Record`. Eliminating the DTO/TypeMapper layer reduces generated code volume, removes the selection-set-per-field mapping boilerplate, and unblocks future features (e.g. `@record` output support).

The change is behind a `recordBasedOutput` feature flag (default `false`) and generates new artefacts into a separate package (`<outputPackage>.record.*`) so old and new code can coexist. The existing generators continue to run unchanged — the flag adds new generators alongside.

### Existing codebase facts

The main orchestrator is `GraphQLGenerator.getGenerators()` which runs, in order: 5 DTO generators, `DBClassGenerator` (→ `{TypeName}DBQueries` in package `queries`), transformer + mapper generators, exception generators, `OperationClassGenerator`, `TypeResolverClassGenerator`, `WiringClassGenerator`. All live in `graphitron-codegen-parent/graphitron-java-codegen`.

Generator base class: `AbstractSchemaClassGenerator<T>` — takes a `ProcessedSchema`, produces one `TypeSpec` per `generate(T target)` call.

Existing wiring pattern: data fetcher generators register wiring info → `WiringClassGenerator` assembles in a final pass. The new generators follow the same two-phase pattern: `FieldsClassGenerator` generates `<TypeName>Fields` classes with embedded `wiring()` methods → `GraphitronWiringClassGenerator` aggregates them.

**`GraphitronContext`** is at `no.sikt.graphql.GraphitronContext`:
- `getDslContext(DataFetchingEnvironment env)` — provides jOOQ `DSLContext`
- `getContextArgument(DataFetchingEnvironment env, String name)` — reads named values from `GraphQlContext`

**`DefaultGraphitronContext`** is at `no.sikt.graphql.DefaultGraphitronContext`.

### Schema traversal

`FieldsSpecBuilder` operates on a `GraphQLSchema` (not `TypeDefinitionRegistry` AST). The schema is assembled using the same pattern as `SchemaTransformer.assembleSchema()` in `graphitron-schema-transform`:

```java
RuntimeWiring runtimeWiring = EchoingWiringFactory.newEchoingWiring(wiring -> {
    typeDefinitionRegistry.scalars().forEach((name, v) -> {
        if (!ScalarInfo.isGraphqlSpecifiedScalar(name)) wiring.scalar(fakeScalar(name));
    });
});
GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);
```

`FieldsSpecBuilder` then uses `new SchemaTraverser().depthFirstFullSchema(visitor, schema)` where the visitor tracks parent type from `context.getParentNode()`.

---

## Deliverables

Each deliverable is a self-contained, reviewable change behind the `recordBasedOutput` flag. No deliverable breaks existing behaviour.

| # | Deliverable | Key output |
|---|---|---|
| 1 | `GraphitronField` skeleton | Sealed interface hierarchy compiling; all field types modelled |
| 2 | Infrastructure | `GraphitronFetcherFactory`, `getTenantId()`, feature flag |
| **Parsing stream** | `FieldsSpecBuilder` — schema → `GraphitronField` | Independent of generating stream |
| P1 | Scalar parsing | `ColumnField`, `ColumnReferenceField` from schema |
| P2 | Table child parsing | `TableField`, `TableMethodField`, `NestingField` |
| P3 | Remaining child parsing | All other `ChildField` leaf types |
| P4 | Root field parsing | All `QueryField` and `MutationField` leaf types |
| **Generating stream** | `FieldsCodeGenerator` — `GraphitronField` → Java | Independent of parsing stream |
| G1 | Scalar generating | `ColumnField`, `ColumnReferenceField` → `wiring()` + `fields()` |
| G2 | Table child generating | `TableField` (inline + `@splitQuery`) → field methods + DataLoader |
| G3 | Remaining child generating | All other `ChildField` leaf types → wiring entries |
| G4 | Root field generating | All `QueryField` and `MutationField` leaf types → DataFetcher methods |
| **Integration** | Wire streams together | End-to-end per field category |
| I1 | `FieldsClassGenerator` + `GraphitronWiringClassGenerator` | First working end-to-end output |
| I2 | `@condition` support | `ConditionWrapperClassGenerator` |
| I3 | `@service` root fields | `ServiceWrapperClassGenerator` |
| I4 | Ordering | `@defaultOrder` and `@orderBy` |

---

## Deliverable 1: `GraphitronField` skeleton

The `GraphitronField` sealed interface hierarchy is the Java materialisation of the field taxonomy. It is the foundation everything else targets — the spec builder populates it, the code generators consume it.

**Package:** `no.sikt.graphql.codegen.record.field` (in `graphitron-java-codegen`)

### `GraphitronType`

Every GraphQL named type is classified into a `GraphitronType`. This is where `@table` directive mappings are validated — jOOQ table class exists, discriminator columns are present, etc. — and it is the authoritative source of source context for all fields on that type.

```java
sealed interface GraphitronType
    permits TableType, ResultType, RootType,
            TableInterfaceType, InterfaceType, UnionType {
    GraphQLNamedType definition();
    default String name() { return definition().getName(); }
}

// @table — full SQL generation; @table directive mapping validated here
record TableType(
    GraphQLObjectType definition,
    String tableName,             // SQL name from @table directive — e.g. "film"
    String javaFieldName,         // Java field name from TableReflection — e.g. "FILM"; null if unresolved
    Optional<Table<?>> table      // jOOQ instance; empty if not resolved
) implements GraphitronType {}

// @record — runtime wiring only, no SQL until a new scope starts
record ResultType(GraphQLObjectType definition)
    implements GraphitronType {}

// Query / Mutation — unmapped entry points
record RootType(GraphQLObjectType definition)
    implements GraphitronType {}

// interface with @table + @discriminate; implementing types have @table + @discriminator
record TableInterfaceType(
    GraphQLInterfaceType definition,
    String discriminatorColumn,
    String tableName,             // SQL name from @table directive
    String javaFieldName,         // Java field name from TableReflection; null if unresolved
    Optional<Table<?>> table      // jOOQ instance; empty if not resolved
) implements GraphitronType {}

// interface with no directives; implementing types have @table
record InterfaceType(GraphQLInterfaceType definition)
    implements GraphitronType {}

// union; all member types have @table
record UnionType(GraphQLUnionType definition)
    implements GraphitronType {}
```

### `GraphitronField`

```java
sealed interface GraphitronField
    permits RootField, ChildField, NotGeneratedField, UnclassifiedField {
    GraphQLFieldDefinition definition();
    default String name() { return definition().getName(); }
}

sealed interface RootField extends GraphitronField
    permits QueryField, MutationField {}

sealed interface QueryField extends RootField
    permits LookupQueryField, TableQueryField, TableMethodQueryField,
            NodeQueryField, EntityQueryField,
            TableInterfaceQueryField, InterfaceQueryField, UnionQueryField,
            ServiceQueryField {}

sealed interface MutationField extends RootField
    permits InsertMutationField, UpdateMutationField, DeleteMutationField,
            UpsertMutationField, ServiceMutationField {}

sealed interface ChildField extends GraphitronField
    permits ColumnField, ColumnReferenceField,
            NodeIdField, NodeIdReferenceField,
            TableField, TableMethodField,
            TableInterfaceField, InterfaceField, UnionField,
            NestingField, ConstructorField,
            ServiceField, ComputedField, PropertyField {}
```

`GraphQLFieldDefinition` is available on every leaf via `definition()`, giving access to return type, argument definitions, and directives without duplicating that data into the spec. `name()` is derived from it.

Each leaf type is a Java `record` carrying the properties relevant to code generation (table class, FK key constant, condition wrapper class, etc.). Source context for a `ChildField` is derived from `schema.type(parentTypeName)` — a `TableType` means table-mapped, a `ResultType` means result-mapped.

### `GraphitronSchema` container

`GraphitronSchema` holds both maps. Types are keyed by name (the natural identifier in GraphQL); fields by `FieldCoordinates` — the GraphQL-spec-standardised `(typeName, fieldName)` pair provided by GraphQL Java (`graphql.schema.FieldCoordinates`), the same type used as the key in `GraphQLCodeRegistry`. The two field maps are therefore parallel by construction.

```java
record GraphitronSchema(
    Map<String, GraphitronType> types,
    Map<FieldCoordinates, GraphitronField> fields
) {
    GraphitronType type(String name) { return types.get(name); }

    GraphitronField field(FieldCoordinates coordinates) {
        return fields.get(coordinates);
    }

    GraphitronField field(String typeName, String fieldName) {
        return fields.get(FieldCoordinates.coordinates(typeName, fieldName));
    }
}
```

`FieldsSpecBuilder` populates both maps during schema traversal. For `TableType` and `TableInterfaceType`, the existing `TableReflection` class is used to resolve the jOOQ table. `TableReflection` loads `DefaultCatalog` via reflection, iterates the schema stream to find the generated `Tables` class, and indexes all `Table<?>` static fields in both directions — SQL name → Java field name, and Java field name → `Table<?>` instance.

The `@table` directive carries the SQL table name (e.g. `"film"`), which is what the schema author writes. The Java field name (e.g. `"FILM"`) is what appears in generated code (`FILM.FILM_ID`, `FilmRecord`, etc.). Both are needed and may differ, so both are stored:

```java
String tableName    = getDirectiveArg(objectType, "table", "name");
String javaName     = TableReflection.getTableJavaFieldNameByTableName(tableName).orElse(null);
Optional<Table<?>> table = TableReflection.getTableByJavaFieldName(javaName);

types.put(typeName, new TableType(objectType, tableName, javaName, table));
fields.put(
    FieldCoordinates.coordinates(typeName, fieldDef.getName()),
    classifyField(fieldDef, parentType, graphqlSchema)
);
```

When `table` is present, it provides columns, primary key, and FK metadata — used directly by FK auto-inference. When absent, validation downstream reports the unresolved table name.

The generator drives iteration from `TypeDefinitionRegistry` (types with `@table`) and looks up by coordinates:

```java
typeDef.getFieldDefinitions().forEach(fieldDef -> {
    GraphitronField gField = schema.field(typeDef.getName(), fieldDef.getName());
    // switch on gField
});
```

This deliverable is complete when the hierarchies and `GraphitronSchema` compile, and a simple pattern-match over all permits exhaustively covers every leaf of both `GraphitronType` and `GraphitronField`.

Once D1 is merged, two streams open up that are fully independent of each other:

- **Parsing stream** (`FieldsSpecBuilder`): reads a `GraphQLSchema` and produces `GraphitronField` instances. Zero JavaPoet. Tests assert which concrete type is produced and that its properties are populated correctly — one test per leaf type minimum.
- **Generating stream** (`FieldsCodeGenerator`): consumes hand-crafted `GraphitronField` instances and produces `TypeSpec` via JavaPoet. Zero schema logic. Tests use approval files — one approved file per leaf type minimum.

Neither stream depends on the other. Integration (`FieldsClassGenerator`) connects them.

---

## Deliverable 2: Infrastructure

### `GraphitronFetcherFactory`

**File:** `graphitron-common/src/main/java/no/sikt/graphql/GraphitronFetcherFactory.java`

```java
public class GraphitronFetcherFactory {

    /** Resolves a scalar column directly from the jOOQ Record in source position. */
    public static <T> LightDataFetcher<T> field(Field<T> jooqField) {
        return env -> ((Record) env.getSource()).get(jooqField);
    }

    /** Resolves an inline nested single object (many-to-one) from the source Record. */
    public static LightDataFetcher<Record> nestedRecord(String alias) {
        return env -> ((Record) env.getSource()).get(alias, Record.class);
    }

    /** Resolves an inline nested list (one-to-many) from the source Record. */
    public static LightDataFetcher<Result<Record>> nestedResult(String alias) {
        return env -> ((Record) env.getSource()).get(alias, Result.class);
    }
}
```

### `GraphitronContext` — add `getTenantId()`

```java
@NotNull Optional<String> getTenantId(DataFetchingEnvironment env);
```

`DefaultGraphitronContext` returns `Optional.empty()`. Used in `loaderName()` for multi-tenant DataLoader key isolation.

### Feature flag

**`GeneratorConfig.java`**: `private static boolean recordBasedOutput = false;` + getter/setter.
**Maven plugin mojo**: `@Parameter(property = "graphitron.recordBasedOutput", defaultValue = "false") protected boolean recordBasedOutput;`

---

## Deliverable 3: Scalar fields end-to-end

Introduces `FieldsSpecBuilder`, `FieldsCodeGenerator`, `FieldsClassGenerator`, and `GraphitronWiringClassGenerator`. At this stage only `ColumnField` and simple root `TableQueryField` are handled — enough to produce a working end-to-end pipeline for types with no nested fields.

### Generated `CustomerFields` (scalar-only, non-root)

```java
public class CustomerFields {

    public static TypeRuntimeWiring.Builder wiring() {
        return TypeRuntimeWiring.newTypeWiring("Customer")
            .dataFetcher("id",    GraphitronFetcherFactory.field(CUSTOMER.CUSTOMER_ID))
            .dataFetcher("email", GraphitronFetcherFactory.field(CUSTOMER.EMAIL_ADDRESS));
    }

    public static List<Field<?>> fields(Customer customer, DataFetchingFieldSelectionSet sel) {
        var fields = new ArrayList<Field<?>>();
        fields.add(customer.CUSTOMER_ID);
        fields.add(customer.EMAIL_ADDRESS);
        return fields;
    }
}
```

### Generated `QueryFields` (root, scalar return)

```java
public class QueryFields {

    public static TypeRuntimeWiring.Builder wiring() {
        return TypeRuntimeWiring.newTypeWiring("Query")
            .dataFetcher("customer", QueryFields::customer);
    }

    public static CompletableFuture<Record> customer(DataFetchingEnvironment env) {
        GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
        String id = env.getArgument("id");
        return CompletableFuture.supplyAsync(() -> {
            var _a = CUSTOMER.as("customer_hash");
            return ctx.getDslContext(env)
                .select(CustomerFields.fields(_a, env.getSelectionSet()))
                .from(_a)
                .where(_a.CUSTOMER_ID.eq(UInteger.valueOf(id)))
                .fetchOne();
        });
    }
}
```

### Generated `GraphitronWiring`

```java
public class GraphitronWiring {
    public static RuntimeWiring.Builder getRuntimeWiringBuilder() {
        return RuntimeWiring.newRuntimeWiring()
            .type(QueryFields.wiring())
            .type(CustomerFields.wiring());
    }
}
```

---

## Deliverable 4: Inline `TableField`

Extends `FieldsCodeGenerator` with `TableField` in table-mapped source context (no `@splitQuery`). Introduces the static field method pattern and the `@defer` check-then-fetch resolver.

### Static field methods

Each inline `TableField` generates a `public static` method on `CustomerFields`, analogous to jOOQ's static column fields. The method takes the parent table alias (needed for correlated joins) and a `SelectedField` (carries both the nested selection set and any arguments).

```java
public static Field<Result<Record>> payments(Customer customer, SelectedField field) {
    return DSL.multiset(
        DSL.select(PaymentFields.fields(PAYMENT, field.getSelectionSet()))
            .from(PAYMENT)
            .join(customer).onKey(Keys.PAYMENT__PAYMENT_CUSTOMER_ID_FKEY)
    ).as("payments");
}

public static Field<Record> address(Customer customer, SelectedField field) {
    return DSL.field(
        DSL.select(AddressFields.fields(ADDRESS, field.getSelectionSet()))
            .from(ADDRESS)
            .join(customer).onKey(Keys.CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
    ).as("address");
}
```

`fields()` adds them conditionally:

```java
public static List<Field<?>> fields(Customer customer, DataFetchingFieldSelectionSet sel) {
    var fields = new ArrayList<Field<?>>();
    fields.add(customer.CUSTOMER_ID);
    fields.add(customer.EMAIL_ADDRESS);
    sel.getFields("payments").forEach(f -> fields.add(payments(customer, f)));
    sel.getFields("address").forEach(f -> fields.add(address(customer, f)));
    return fields;
}
```

### `@defer` check-then-fetch wiring

Every inline `TableField` resolver checks whether the parent already fetched the data before issuing a fallback query:

```java
public static TypeRuntimeWiring.Builder wiring() {
    return TypeRuntimeWiring.newTypeWiring("Customer")
        .dataFetcher("id",       GraphitronFetcherFactory.field(CUSTOMER.CUSTOMER_ID))
        .dataFetcher("email",    GraphitronFetcherFactory.field(CUSTOMER.EMAIL_ADDRESS))
        .dataFetcher("payments", env -> {
            Record source = env.getSource();
            Result<Record> result = source.get("payments", Result.class);
            if (result != null) return result;
            GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
            return ctx.getDslContext(env)
                .select(PaymentFields.fields(PAYMENT, env.getSelectionSet()))
                .from(PAYMENT)
                .where(PAYMENT.CUSTOMER_ID.eq(source.get(CUSTOMER.CUSTOMER_ID)))
                .fetch();
        })
        .dataFetcher("address", env -> {
            Record source = env.getSource();
            Record result = source.get("address", Record.class);
            if (result != null) return result;
            GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
            return ctx.getDslContext(env)
                .select(AddressFields.fields(ADDRESS, env.getSelectionSet()))
                .from(ADDRESS)
                .where(ADDRESS.ADDRESS_ID.eq(source.get(CUSTOMER.ADDRESS_ID)))
                .fetchOne();
        });
}
```

`fields()` is an optimisation — pre-fetching via parent SELECT. The check-then-fetch resolver is the correctness guarantee.

### FK auto-inference

- Exactly one FK between two tables → `onKey(Keys.FK_CONSTANT)`
- Zero or multiple → codegen error with clear message
- User override: `@reference(path: {key: "FK_NAME"})` → `Keys.FK_NAME`

---

## Deliverable 5: `@splitQuery` `TableField`

Extends `FieldsCodeGenerator` with `TableField` where `@splitQuery` is set. Adds DataLoader + BatchLoader generation and the `loaderName()` helper.

```java
public static CompletableFuture<Result<Record>> orders(DataFetchingEnvironment env) {
    GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
    String name = loaderName(env.getExecutionStepInfo().getPath(), ctx.getTenantId(env));
    DataLoader<CustomerRecord, Result<Record>> loader = env.getDataLoaderRegistry()
        .computeIfAbsent(name, k -> DataLoaderFactory.newMappedDataLoaderWithContext(
            CustomerFields::ordersLoader));
    return loader.load(((Record) env.getSource()).into(CUSTOMER), env);
}

private static CompletableFuture<Map<CustomerRecord, Result<Record>>> ordersLoader(
        List<CustomerRecord> keys, BatchLoaderEnvironment ctx) {
    DataFetchingEnvironment env = (DataFetchingEnvironment) ctx.getKeyContextsList().get(0);
    GraphitronContext gCtx = env.getGraphQlContext().get("graphitronContext");
    return CompletableFuture.supplyAsync(() -> {
        Order _a = ORDER.as("order_hash");
        return gCtx.getDslContext(env)
            .select(OrderFields.fields(_a, env.getSelectionSet()))
            .from(_a)
            .where(_a.CUSTOMER_ID.in(keys.stream().map(CustomerRecord::getCustomerId).toList()))
            .fetch().stream()
            .collect(Collectors.groupingBy(r -> r.into(CUSTOMER)));
    });
}

private static String loaderName(ResultPath path, Optional<String> tenantId) {
    String normalized = path.toList().stream()
        .filter(seg -> !(seg instanceof Integer))
        .map(Object::toString).collect(Collectors.joining("/"));
    return tenantId.map(id -> id + "/" + normalized).orElse(normalized);
}
```

---

## Deliverable 6: `@condition` support

One `<ConditionClassName>Wrapper` per distinct external condition class referenced anywhere in the schema (via `@condition` or `ReferenceElement.condition`). Arguments matched by name (requires `-parameters`); `DSLContext` matched by type.

```java
public class CustomerConditionsWrapper {
    public static Condition activeCustomers(DSLContext ctx) {
        return CustomerConditions.activeCustomers(ctx);
    }
}
```

`override` is a property of the condition spec consumed by `FieldsCodeGenerator`, not the wrapper.

**New files:** `record/ConditionWrapperSpec.java`, `ConditionWrapperSpecBuilder.java`, `ConditionWrapperCodeGenerator.java`, `ConditionWrapperClassGenerator.java`

---

## Deliverable 7: `@service` root fields

One `<ServiceClassName>Wrapper` per distinct external service class. Arguments matched by name; `DSLContext` matched by type; instance service classes via `(DSLContext)` or no-arg constructor.

```java
public class HelloWorldServiceWrapper {
    public static CompletableFuture<HelloWorldRecord> helloWorldAgain(DataFetchingEnvironment env) {
        String name = env.getArgument("name");
        GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
        HelloWorldService service = new HelloWorldService(ctx.getDslContext(env));
        return CompletableFuture.supplyAsync(() -> service.helloWorldAgain(name));
    }
}
```

**New files:** `record/ServiceWrapperSpec.java`, `ServiceWrapperSpecBuilder.java`, `ServiceWrapperCodeGenerator.java`, `ServiceWrapperClassGenerator.java`

---

## Deliverable 8: Ordering

`@defaultOrder` and `@orderBy` logic added to root field methods and `@splitQuery` BatchLoaders.

```java
// @defaultOrder only
var orderFields = QueryHelper.getSortFields(_a, "IDX_TITLE", "ASC");

// @orderBy with @defaultOrder fallback
var orderFields = orderBy == null
    ? QueryHelper.getSortFields(_a, "IDX_TITLE", "ASC")
    : switch (orderBy.getOrderByField().toString()) {
        case "TITLE" -> QueryHelper.getSortFields(_a, "IDX_TITLE", orderBy.getDirection().toString());
        case "ID"    -> _a.getPrimaryKey().getFieldsArray();
        default      -> throw new IllegalArgumentException("Unknown orderBy: " + orderBy.getOrderByField());
    };
```

Three `@defaultOrder` modes: `index` → `QueryHelper.getSortFields(table, indexName, dir)`, `fields` → explicit `SortField[]`, `primaryKey` → `table.getPrimaryKey().getFieldsArray()`.

---

## Package Structure

| Subpackage | Contents |
|---|---|
| `<outputPackage>.record.fields` | `<TypeName>Fields` — SQL logic + `wiring()` per output type |
| `<outputPackage>.record.resolvers` | `GraphitronWiring`, `<ConditionClassName>Wrapper`, `<ServiceClassName>Wrapper` |

---

## Generator Overview

| Step | Generator | Output | Depends on |
|---|---|---|---|
| 0 | Infrastructure | `GraphitronFetcherFactory`, feature flag, `getTenantId()` | — |
| 1 | `ConditionWrapperClassGenerator` | `<ConditionClassName>Wrapper.java` | — |
| 2 | `ServiceWrapperClassGenerator` | `<ServiceClassName>Wrapper.java` | — |
| 3 | `FieldsClassGenerator` | `<TypeName>Fields.java` | Steps 1 + 2 |
| 4 | `GraphitronWiringClassGenerator` | `GraphitronWiring.java` | Step 3 |
| 5 | Orchestration | Modify `GraphQLGenerator` | Steps 0–4 |

`FieldsClassGenerator` is the core generator. It owns both the SQL logic (field methods, scope-establishing methods, DataLoaders) and `wiring()` for each type. This mirrors how `DBClassGenerator` produces one self-contained class with all DB methods, and lets `wiring()` reference methods in the same class directly (`CustomerFields::orders`).

---

## Generator Architecture: Spec Layer

```
GraphQLSchema
  │
  ▼  FieldsSpecBuilder  (schema traversal + FK inference + @table validation; zero JavaPoet)
  │
  ▼  GraphitronSchema  (Map<String, GraphitronType> + Map<FieldCoordinates, GraphitronField>)
  │
  ▼  FieldsCodeGenerator  (iterates TypeDefinitionRegistry; looks up by name / FieldCoordinates; JavaPoet)
  │
  ▼  TypeSpec → .java file
```

`FieldsCodeGenerator` iterates `GraphitronField` instances and emits, per class:
1. `wiring()` — one `.dataFetcher(...)` per field
2. `fields(table, sel)` — scalars unconditional; inline fields guarded by `sel.getFields(...)` (non-root only)
3. Public static field methods — one per inline `TableField`
4. `public <name>(DataFetchingEnvironment)` — one per `@splitQuery` field and root fields
5. `private <name>Loader(List, BatchLoaderEnvironment)` — one per `@splitQuery` field
6. `private loaderName(ResultPath, Optional<String>)` — if any `@splitQuery` fields exist

Wrapper generators (`ConditionWrapperClassGenerator`, `ServiceWrapperClassGenerator`) follow the same spec → codegen split.

---

## Testing Strategy

The two streams have distinct testing approaches. Both must exhaustively cover every `GraphitronField` leaf type — a leaf type with no test in its stream is a gap.

### Parsing stream — `FieldsSpecBuilderTest`

Tests are schema-in, `GraphitronField`-out assertions. No string comparison, no JavaPoet. Every leaf type needs at least one test that:
1. Assembles a minimal schema containing a field that should produce that leaf type
2. Runs `FieldsSpecBuilder`
3. Asserts the correct concrete type was produced and its properties match

```java
@Test
void columnField() {
    GraphQLSchema schema = assembleSchema("""
        type Customer @table { email: String }
        type Query { customer: Customer }
        """);
    GraphitronSchema result = FieldsSpecBuilder.build(schema);
    assertThat(result.get("Customer", "email"))
        .isInstanceOf(ColumnField.class)
        .extracting("jooqColumn").isEqualTo("EMAIL_ADDRESS");
}

@Test
void tableFieldInline() {
    GraphQLSchema schema = assembleSchema("""
        type Customer @table { payments: [Payment] }
        type Payment @table { id: ID! }
        type Query { customer: Customer }
        """);
    GraphitronSchema result = FieldsSpecBuilder.build(schema);
    assertThat(result.get("Customer", "payments"))
        .isInstanceOf(TableField.class)
        .extracting("splitQuery").isEqualTo(false);
}

@Test
void tableFieldSplitQuery() { ... }

@Test
void serviceQueryField() { ... }

// One test per leaf type in the hierarchy
```

### Generating stream — `FieldsCodeGeneratorTest`

Tests use hand-crafted `GraphitronField` instances — no schema, no `FieldsSpecBuilder`. Output is compared against approved `.java` files. Every leaf type needs at least one approved file.

```java
@Test
void columnField() {
    var fields = List.of(new ColumnField("email", "EMAIL_ADDRESS"));
    assertGeneratedContentMatches(FieldsCodeGenerator.generate("Customer", fields), "CustomerFields_scalar");
}

@Test
void tableFieldInline() {
    var fields = List.of(new TableField("payments", "Payment", "PaymentFields",
        List.of(new OnKeyJoin("PAYMENT__FK_CUSTOMER_ID")), false, ...));
    assertGeneratedContentMatches(FieldsCodeGenerator.generate("Customer", fields), "CustomerFields_inline");
}

@Test
void tableFieldSplitQuery() { ... }

@Test
void queryFieldRoot() { ... }

// One approval file per leaf type (or combination that exercises new code paths)
```

### Integration — `RecordOutputIntegrationTest`

End-to-end: schema in, generated `.java` source out (or running Quarkus test). Validates that parsing + generating agree. Added per integration deliverable (I1–I4).

### Test file layout

```
src/test/java/.../record/
  FieldsSpecBuilderTest.java        ← parsing stream, one test per leaf type
  FieldsCodeGeneratorTest.java      ← generating stream, one approval per leaf type
  integration/
    RecordOutputIntegrationTest.java

src/test/resources/record/
  CustomerFields_scalar.java
  CustomerFields_inline.java
  CustomerFields_splitQuery.java
  QueryFields.java
  // one file per leaf type tested in generating stream
```

---

## Critical Files

| File | Change |
|---|---|
| `graphitron-common/.../GraphitronContext.java` | Add `getTenantId()` |
| `graphitron-common/.../DefaultGraphitronContext.java` | Implement `getTenantId()` → `Optional.empty()` |
| `graphitron-common/.../GraphitronFetcherFactory.java` | **New** |
| `graphitron-java-codegen/.../configuration/GeneratorConfig.java` | Add `recordBasedOutput` |
| `graphitron-java-codegen/.../generate/Generator.java` | Add `boolean recordBasedOutput()` |
| `graphitron-maven-plugin/.../mojo/GenerateMojo.java` | Add `@Parameter recordBasedOutput` |
| `graphitron-java-codegen/.../generate/GraphQLGenerator.java` | Add new generators when flag is set |
| `graphitron-java-codegen/.../mappings/JavaPoetClassName.java` | Add `JOOQ_RECORD`, `JOOQ_RESULT`, `LIGHT_DATA_FETCHER`, `GRAPHITRON_FETCHER_FACTORY` |
| `record/field/GraphitronField.java` + leaf types | **New** — Deliverable 1 |
| `record/FieldsSpecBuilder.java` | **New** |
| `record/FieldsCodeGenerator.java` | **New** |
| `record/FieldsClassGenerator.java` | **New** |
| `record/GraphitronWiringClassGenerator.java` | **New** |
| `record/ConditionWrapper*.java` (4 files) | **New** |
| `record/ServiceWrapper*.java` (4 files) | **New** |
