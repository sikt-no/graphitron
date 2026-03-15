# Plan: Phase Out DTO Layer — Record-Based Output

## Context

The current code generation pipeline produces output DTOs and TypeMapper classes that convert jOOQ Records into those DTOs. This is unnecessary: graphql-java's RuntimeWiring can resolve fields directly from any Java object, including jOOQ `Record`. Eliminating the DTO/TypeMapper layer reduces generated code volume, removes the selection-set-per-field mapping boilerplate, and unblocks future features (e.g. Java record output support).

The change is behind a `recordBasedOutput` feature flag (default `false`) and generates new artefacts into a **separate package** (`<outputPackage>.record.*`) so old and new code can coexist. **The existing generators (`TypeDTOGenerator`, `DBClassGenerator`, `RecordMapperClassGenerator`, etc.) continue to run unchanged** — the flag adds new generators alongside, never removes existing ones in this phase.

### Existing codebase facts

The main orchestrator is `GraphQLGenerator.getGenerators()` which runs, in order: 5 DTO generators, `DBClassGenerator` (→ `{TypeName}DBQueries` in package `queries`), transformer + mapper generators, exception generators, `OperationClassGenerator`, `TypeResolverClassGenerator`, `WiringClassGenerator`. All live in `graphitron-codegen-parent/graphitron-java-codegen`.

Generator base class: `AbstractSchemaClassGenerator<T>` — takes a `ProcessedSchema`, produces one `TypeSpec` per `generate(T target)` call. Method generators produce individual methods; class generators coordinate them.

Existing wiring pattern: data fetcher generators register wiring info → `WiringClassGenerator` assembles in a final pass. The new generators follow the same two-phase pattern: `FieldsClassGenerator` generates `<TypeName>Fields` classes with embedded `wiring()` methods → `GraphitronWiringClassGenerator` aggregates them.

**`GraphitronContext`** is at `no.sikt.graphql.GraphitronContext`:
- `getDslContext(DataFetchingEnvironment env)` — provides jOOQ `DSLContext`
- `getContextArgument(DataFetchingEnvironment env, String name)` — reads named values from `GraphQlContext`
- `getDataLoaderName(DataFetchingEnvironment env)` — existing DTO-based resolvers only; new generators do NOT use this

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

`FieldsSpecBuilder` then uses `new SchemaTraverser().depthFirstFullSchema(visitor, schema)` where the visitor tracks parent type from `context.getParentNode()`. This gives fully resolved types and avoids custom context-stack tracking.

---

## Scope Boundaries

### In scope for initial implementation

| Feature | Notes |
|---|---|
| Simple scalar fields (`@table` + `@field`) | Direct `TABLE.COLUMN` mapping via `GraphitronFetcherFactory.field()` |
| Inline single-object nested fields | FK inferred from schema metadata |
| Inline list nested fields | Same FK inference; rendered as `multiset()` |
| Inline fields with `@reference` (any path depth) | FK-derived via `onKey()` joins |
| `@splitQuery` fields | DataLoader registered on first use via `computeIfAbsent` |
| `@condition` on fields/arguments | Via `<ConditionClassName>Wrapper` |
| `@service` on root (`Query`/`Mutation`) fields | Via `<ServiceClassName>Wrapper`; forwarded through root Fields method |
| `@notGenerated`, `@externalField` | Skipped (not wired) |
| Root DB fields with or without scalar arguments | All root fields are static method references (`QueryFields::fieldName`) |
| Feature flag `recordBasedOutput` in mojo + `GeneratorConfig` | Drives the entire new generator set |
| `@defaultOrder` on fields | Static ORDER BY at codegen time |
| `@orderBy` on arguments | Dynamic ORDER BY driven by GraphQL input argument |

### Deferred (out of scope for this phase)

| Feature | Reason |
|---|---|
| Relay connection types (`XxxConnection`, `XxxEdge`, `PageInfo`) | Planned: `GraphitronConnection` record wrapping `Result<Record>`, seek-based pagination. `@splitQuery` connections return `Map<ParentRecord, GraphitronConnection>` from DataLoader. Study existing code carefully before implementing. |
| `@tableMethod` | Dynamic table lookup via external method |
| `@lookupKey` batch queries | Distinct query shape |
| `@node` / `node(id:)` query | Global Object Identification resolver |
| Mutations (`@mutation`) | Insert/update/delete/upsert flow differs fundamentally |
| TypeResolvers (interfaces/unions with `@discriminate`) | Runtime type resolution |
| Apollo Federation entity resolvers | `@key` / `_entities` |
| `@service` on non-root type fields | Skipped in this phase |
| `@externalField` / `FieldWrapperClassGenerator` | Same wrapper pattern as service/condition; deferred |
| Java record output types (`@record`) | Future feature |
| Path joins via `@reference(path: [{method: "..."}])` | All joins use `onKey()` in this phase |

---

## Package Structure

New record-based code goes under `<outputPackage>.record`:

| Subpackage | Contents |
|---|---|
| `<outputPackage>.record.fields` | `<TypeName>Fields` — one class per output type; contains `select()`, `wiring()`, DataFetcher methods, BatchLoader methods, service forwarding methods |
| `<outputPackage>.record.resolvers` | `GraphitronWiring`, `<ConditionClassName>Wrapper`, `<ServiceClassName>Wrapper` |

---

## Generator Overview

Build and test generators in dependency order. `<TypeName>Fields` is the unified class per type — it contains both the SQL logic (select, nested methods, DataLoader) and the `wiring()` method. `GraphitronWiringClassGenerator` is a thin final-pass aggregator, mirroring the existing `WiringClassGenerator` pattern.

| Step | Generator | Output file(s) | Key content | Depends on |
|---|---|---|---|---|
| 0 | Infrastructure *(not a generator)* | `GraphitronFetcherFactory.java` | `field()`, `nestedRecord()`, `nestedResult()` | — |
| 1 | `ConditionWrapperClassGenerator` | `<ConditionClassName>Wrapper.java` (one per external condition class) | one static method per `@condition` / `ReferenceElement.condition` usage | — |
| 2 | `ServiceWrapperClassGenerator` | `<ServiceClassName>Wrapper.java` (one per `@service` class) | one static method per `@service` field | — |
| 3 | `FieldsClassGenerator` | `<TypeName>Fields.java` (one per output type + root op types) | `select()` (non-root), `wiring()`, inline field methods, DataFetcher methods, BatchLoader methods, service forwarding | Steps 1 + 2 |
| 4 | `GraphitronWiringClassGenerator` | `GraphitronWiring.java` | `getRuntimeWiringBuilder()` — calls `<TypeName>Fields.wiring()` for all types | Step 3 |
| 5 | Orchestration | *(modifies `GraphQLGenerator` only)* | — | Steps 0–4 |

**Why one generator, one class**: Splitting `FieldsClassGenerator` and `RuntimeWiringClassGenerator` would require both to traverse the same schema information, coordinate on method names, and produce two tightly coupled output files per type. Keeping everything in `<TypeName>Fields` mirrors how `DBClassGenerator` produces one class with all DB methods, and lets `wiring()` reference private methods in the same class directly (`CustomerFields::orders`).

**Wrapper naming note**: `<ConditionClassName>Wrapper` and `<ServiceClassName>Wrapper` are named after the **external class** (e.g. `CustomerConditionsWrapper`, `HelloWorldServiceWrapper`), not a GraphQL type. The same wrapper is reused if multiple fields reference the same external class.

---

## Step 0: Infrastructure

### `GraphitronFetcherFactory`

**File:** `graphitron-common/src/main/java/no/sikt/graphql/GraphitronFetcherFactory.java`

```java
public class GraphitronFetcherFactory {

    /**
     * Resolves a scalar column directly from the jOOQ Record in the source position.
     * Use for simple mapped scalar fields on non-root types.
     */
    public static <T> LightDataFetcher<T> field(Field<T> jooqField) {
        return env -> ((Record) env.getSource()).get(jooqField);
    }

    /**
     * Resolves an inline nested single object (many-to-one) from the source Record.
     * The alias must match the alias used when the nested record was SELECTed.
     */
    public static LightDataFetcher<Record> nestedRecord(String alias) {
        return env -> ((Record) env.getSource()).get(alias, Record.class);
    }

    /**
     * Resolves an inline nested list (one-to-many) from the source Record.
     * The alias must match the alias used when the multiset was SELECTed.
     */
    public static LightDataFetcher<Result<Record>> nestedResult(String alias) {
        return env -> ((Record) env.getSource()).get(alias, Result.class);
    }
}
```

No `fetcher()`, `service()`, `RootFetchFunction`, `LoaderFactory`, or `ServiceFunction` — all root fields and DataLoader fields use static method references to generated `<TypeName>Fields` methods.

### `GraphitronContext` — add `getTenantId()`

**`GraphitronContext.java`** — add one method (existing methods unchanged):
```java
@NotNull Optional<String> getTenantId(DataFetchingEnvironment env);
```
`DefaultGraphitronContext` returns `Optional.empty()`. Used in the generated `loaderName()` helper for multi-tenant DataLoader key isolation.

### Feature flag: `recordBasedOutput`

**`GeneratorConfig.java`**: `private static boolean recordBasedOutput = false;` + getter/setter.
Load from mojo: `recordBasedOutput = mojo.recordBasedOutput();`
**Maven plugin mojo**: `@Parameter(property = "graphitron.recordBasedOutput", defaultValue = "false") protected boolean recordBasedOutput;`

---

## Step 1: `ConditionWrapperClassGenerator`

One `<ConditionClassName>Wrapper` per distinct external condition class referenced anywhere in the schema (via `@condition` or `ReferenceElement.condition`). Same parameter-matching rules as `ServiceWrapper`: matched by name (requires `-parameters`), `DSLContext` matched by type, `contextArguments` from `GraphitronContext`. Additionally, typed table parameters matched by jOOQ table type for `ReferenceElement` conditions.

`override` is NOT a property of the wrapper method — it is in `ConditionMethodSpec` and consumed by `FieldsCodeGenerator`.

```java
// CustomerConditionsWrapper.java
public class CustomerConditionsWrapper {
    public static Condition activeCustomers(DSLContext ctx) {
        return CustomerConditions.activeCustomers(ctx);
    }
    public static Condition inactiveCustomers(DSLContext ctx, String lastNameStartingWith) {
        return CustomerConditions.inactiveCustomers(ctx, lastNameStartingWith);
    }
}

// FilmLanguageConditionsWrapper.java
public class FilmLanguageConditionsWrapper {
    public static Condition selectedLanguage(Film film, Language language, String languageId) {
        return FilmLanguageConditions.selectedLanguage(film, language, languageId);
    }
}
```

Join path execution (non-condition part of `@reference`) is generated inline in `Fields`. No jOOQ path-navigation methods in generated code; all joins use `onKey(Keys.FK_CONSTANT)` derived from FK metadata at codegen time.

**New files:** `spec/ConditionWrapperSpec.java`, `ConditionWrapperSpecBuilder.java`, `ConditionWrapperCodeGenerator.java`, `ConditionWrapperClassGenerator.java` — all in `generators/record/`.

---

## Step 2: `ServiceWrapperClassGenerator`

One `<ServiceClassName>Wrapper` per distinct external service class. Contains one `public static` method per `@service`-annotated field using that class.

- **Arguments matched by name** (requires `-parameters` compilation); validated at codegen time
- **`DSLContext` on static methods**: matched by type, not name
- **Instance methods**: instantiated via `(DSLContext)` constructor or no-arg constructor (validated at codegen)
- Wrapper method signature: `public static CompletableFuture<T> methodName(DataFetchingEnvironment env)`

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

**New files:** `spec/ServiceWrapperSpec.java`, `ServiceWrapperSpecBuilder.java`, `ServiceWrapperCodeGenerator.java`, `ServiceWrapperClassGenerator.java` — all in `generators/record/`.

### Spec types (shared sealed hierarchy)

```java
record ServiceWrapperSpec(String className, String serviceClass, boolean serviceNeedsContext,
                          List<ServiceMethodSpec> methods) {}
record ServiceMethodSpec(String wrapperMethodName, String serviceMethodName, String returnType,
                         boolean isStatic, List<ServiceParamSpec> params) {}
sealed interface ServiceParamSpec permits GraphQLArgParam, ContextParam, DslContextParam {}
record GraphQLArgParam(String paramName, String javaType) implements ServiceParamSpec {}
record ContextParam(String paramName, String contextKey, String javaType) implements ServiceParamSpec {}
record DslContextParam(String paramName) implements ServiceParamSpec {}

// ConditionWrapper extends with table params:
sealed interface ConditionParamSpec
    permits GraphQLArgParam, ContextParam, DslContextParam, ParentTableParam, ChildTableParam {}
record ParentTableParam(String paramName, String jooqTableClass) implements ConditionParamSpec {}
record ChildTableParam(String paramName, String jooqTableClass) implements ConditionParamSpec {}
```

---

## Step 3: `FieldsClassGenerator`

**The core generator.** One `<TypeName>Fields` class per GraphQL output type. Each class is self-contained: it owns the SQL logic for its type AND the `wiring()` method. This mirrors `DBClassGenerator`'s pattern of producing one class with all DB methods.

The spec is **field-centric**: each `FieldSpec` carries all information needed to generate both the wiring entry and any additional methods (DataFetcher, BatchLoader, etc.) for that field. The code generator iterates fields and emits appropriate code for each.

### Field categorization (in `FieldsSpecBuilder`)

| Field type | Wiring entry | Additional methods |
|---|---|---|
| `@notGenerated`, `@externalField` | skip | none |
| Scalar DB field | `GraphitronFetcherFactory.field(TABLE.COLUMN)` | none (entry in `select()` only) |
| Inline list | `GraphitronFetcherFactory.nestedResult("alias")` | private method in `select()` |
| Inline single | `GraphitronFetcherFactory.nestedRecord("alias")` | private method in `select()` |
| `@splitQuery` | `CustomerFields::fieldName` (same class) | `public fieldName(DataFetchingEnvironment)` + `private fieldNameLoader(List, BatchLoaderEnvironment)` |
| `@service` root | `QueryFields::fieldName` (same class) | `public fieldName(DataFetchingEnvironment)` forwarding to wrapper |
| Root DB field | `QueryFields::fieldName` (same class) | `public fieldName(DataFetchingEnvironment)` with inline jOOQ query |

Note: `@reference` and `@condition` affect the SQL methods only, not the wiring entry. An inline field with `@reference` still uses `nestedRecord`/`nestedResult` in wiring.

### Generated `CustomerFields` (non-root type)

```java
public class CustomerFields {

    // --- Wiring ---

    public static TypeRuntimeWiring.Builder wiring() {
        return TypeRuntimeWiring.newTypeWiring("Customer")
            .dataFetcher("id",       GraphitronFetcherFactory.field(CUSTOMER.CUSTOMER_ID))
            .dataFetcher("email",    GraphitronFetcherFactory.field(CUSTOMER.EMAIL_ADDRESS))
            .dataFetcher("payments", GraphitronFetcherFactory.nestedResult("payments"))
            .dataFetcher("address",  GraphitronFetcherFactory.nestedRecord("address"))
            .dataFetcher("orders",   CustomerFields::orders);  // same-class reference
    }

    // --- SELECT list (called from parent type's Fields) ---

    public static List<SelectField<?>> select(Customer customer, DataFetchingFieldSelectionSet select) {
        var fields = new ArrayList<SelectField<?>>();
        fields.add(customer.CUSTOMER_ID);
        fields.add(customer.EMAIL_ADDRESS);
        if (select.contains("payments")) {
            fields.add(payments(customer, select.getFields("payments").get(0).getSelectionSet()));
        }
        if (select.contains("address")) {
            fields.add(address(customer, select.getFields("address").get(0).getSelectionSet()));
        }
        return fields;
    }

    // --- Inline nested methods ---

    private static Field<Result<Record>> payments(Customer customer, DataFetchingFieldSelectionSet select) {
        return DSL.multiset(
            DSL.select(PaymentFields.select(PAYMENT, select))
                .from(PAYMENT)
                .join(customer).onKey(Keys.PAYMENT__PAYMENT_CUSTOMER_ID_FKEY)
        ).as("payments");
    }

    private static Field<Record> address(Customer customer, DataFetchingFieldSelectionSet select) {
        return DSL.field(
            DSL.select(AddressFields.select(ADDRESS, select))
                .from(ADDRESS)
                .join(customer).onKey(Keys.CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
        ).as("address");
    }

    // --- splitQuery DataFetcher (DataLoader registered on first use) ---

    public static CompletableFuture<Result<Record>> orders(DataFetchingEnvironment env) {
        GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
        String name = loaderName(env.getExecutionStepInfo().getPath(), ctx.getTenantId(env));
        DataLoader<CustomerRecord, Result<Record>> loader = env.getDataLoaderRegistry()
            .computeIfAbsent(name, k -> DataLoaderFactory.newMappedDataLoaderWithContext(
                CustomerFields::ordersLoader));
        return loader.load(((Record) env.getSource()).into(CUSTOMER), env);
    }

    // --- splitQuery BatchLoader ---

    private static CompletableFuture<Map<CustomerRecord, Result<Record>>> ordersLoader(
            List<CustomerRecord> keys, BatchLoaderEnvironment ctx) {
        DataFetchingEnvironment env = (DataFetchingEnvironment) ctx.getKeyContextsList().get(0);
        GraphitronContext gCtx = env.getGraphQlContext().get("graphitronContext");
        return CompletableFuture.supplyAsync(() -> {
            Order _a = ORDER.as("order_hash");
            return gCtx.getDslContext(env)
                .select(OrderFields.select(_a, env.getSelectionSet()))
                .from(_a)
                .where(_a.CUSTOMER_ID.in(keys.stream().map(CustomerRecord::getCustomerId).toList()))
                .fetch().stream()
                .collect(Collectors.groupingBy(r -> r.into(CUSTOMER)));
        });
    }

    // --- Utility ---

    private static String loaderName(ResultPath path, Optional<String> tenantId) {
        String normalized = path.toList().stream()
            .filter(seg -> !(seg instanceof Integer))
            .map(Object::toString).collect(Collectors.joining("/"));
        return tenantId.map(id -> id + "/" + normalized).orElse(normalized);
    }
}
```

### Generated `QueryFields` (root operation type)

No `select()` — only `wiring()` and DataFetcher methods.

```java
public class QueryFields {

    public static TypeRuntimeWiring.Builder wiring() {
        return TypeRuntimeWiring.newTypeWiring("Query")
            .dataFetcher("films",          QueryFields::films)
            .dataFetcher("customer",       QueryFields::customer)
            .dataFetcher("helloWorldAgain", QueryFields::helloWorldAgain);
    }

    // Root DB field — jOOQ query inline, args extracted from env
    public static CompletableFuture<Result<Record>> films(DataFetchingEnvironment env) {
        GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
        DataFetchingFieldSelectionSet select = env.getSelectionSet();
        return CompletableFuture.supplyAsync(() -> {
            var _a = FILM.as("film_hash");
            return ctx.getDslContext(env).select(FilmFields.select(_a, select)).from(_a).fetch();
        });
    }

    public static CompletableFuture<Record> customer(DataFetchingEnvironment env) {
        GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
        String id = env.getArgument("id");
        DataFetchingFieldSelectionSet select = env.getSelectionSet();
        return CompletableFuture.supplyAsync(() -> {
            var _a = CUSTOMER.as("customer_hash");
            return ctx.getDslContext(env).select(CustomerFields.select(_a, select))
                .from(_a).where(_a.CUSTOMER_ID.eq(UInteger.valueOf(id))).fetchOne();
        });
    }

    // Root service field — delegates to wrapper
    public static CompletableFuture<HelloWorldRecord> helloWorldAgain(DataFetchingEnvironment env) {
        return HelloWorldServiceWrapper.helloWorldAgain(env);
    }
}
```

### FK auto-inference

Every inline nested and `@splitQuery` field requires a join condition:
- **Auto-inferred**: enumerate `ForeignKey` objects between tables at codegen time
  - Exactly one FK → `OnKeyJoin(fkKeyConstant)`
  - Zero or multiple → fail at codegen with a clear error
- **User-specified** (`@reference(path: {key: "FK_NAME"})`): use directly as `Keys.FK_NAME`

Both cases emit `from(CHILD).join(parent).onKey(Keys.FK_CONSTANT)`. No manual `col = col` conditions.

### Ordering — `@defaultOrder` and `@orderBy`

All ordering logic goes into the root DB field methods (or BatchLoader methods for `@splitQuery`). Arguments extracted from `env` inside the method.

```java
// @defaultOrder only — static ORDER BY
public static CompletableFuture<Result<Record>> films(DataFetchingEnvironment env) {
    ...
    return CompletableFuture.supplyAsync(() -> {
        var orderFields = QueryHelper.getSortFields(_a, "IDX_TITLE", "ASC");
        return ctx.getDslContext(env).select(FilmFields.select(_a, select))
            .from(_a).orderBy(orderFields).fetch();
    });
}

// @orderBy + @defaultOrder fallback — switch statement at runtime
public static CompletableFuture<Result<Record>> films(DataFetchingEnvironment env) {
    GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
    FilmsOrderByInput orderBy = env.getArgument("orderBy");
    DataFetchingFieldSelectionSet select = env.getSelectionSet();
    return CompletableFuture.supplyAsync(() -> {
        var _a = FILM.as("film_hash");
        var orderFields = orderBy == null
            ? QueryHelper.getSortFields(_a, "IDX_TITLE", "ASC")
            : switch (orderBy.getOrderByField().toString()) {
                case "TITLE"  -> QueryHelper.getSortFields(_a, "IDX_TITLE", orderBy.getDirection().toString());
                case "ID"     -> _a.getPrimaryKey().getFieldsArray();
                default -> throw new IllegalArgumentException("Unknown orderBy: " + orderBy.getOrderByField());
            };
        return ctx.getDslContext(env).select(FilmFields.select(_a, select))
            .from(_a).orderBy(orderFields).fetch();
    });
}
```

Three `@defaultOrder` modes: `index` → `QueryHelper.getSortFields(table, indexName, dir)`, `fields` → explicit `SortField[]`, `primaryKey` → `table.getPrimaryKey().getFieldsArray()`.

`@orderBy` on `@splitQuery` fields: `orderBy` argument extracted from `DataFetchingEnvironment` inside the BatchLoader (`(DataFetchingEnvironment) ctx.getKeyContextsList().get(0)`).

### Spec types

```java
// Root operation type (Query/Mutation)
record RootFieldsSpec(String className, String graphqlType, List<RootFieldSpec> fields) {}

record RootFieldSpec(
    String name,                  // GraphQL field name; also the DataFetcher method name
    String targetFieldsClass,     // "CustomerFields" — whose select() to call
    String jooqTableClass,        // "Customer" (concrete jOOQ class)
    String tableAlias,            // hash-based stable alias
    List<GraphQLArgumentSpec> arguments,
    boolean isList,
    OrderingSpec ordering,        // null = no ordering
    boolean isServiceField,       // true → delegate to serviceWrapperClass.serviceWrapperMethod
    String serviceWrapperClass,
    String serviceWrapperMethod
) {}

record GraphQLArgumentSpec(String paramName, String javaType) {}

// Non-root type
record TypeFieldsSpec(
    String className,             // "CustomerFields"
    String graphqlType,           // "Customer"
    String jooqTableClass,        // "Customer"
    String jooqTableParam,        // "customer"
    List<FieldSpec> fields        // ordered; code generator iterates to produce select() + wiring()
) {}

sealed interface FieldSpec permits ScalarFieldSpec, InlineFieldSpec, SplitQueryFieldSpec {}

record ScalarFieldSpec(
    String graphqlField,
    String jooqColumn             // e.g. "CUSTOMER_ID" — customer.CUSTOMER_ID
) implements FieldSpec {}

record InlineFieldSpec(
    String graphqlField,
    String alias,                 // same value used in .as() and in nestedRecord/nestedResult
    String childJooqTableClass,
    String childFieldsClass,
    boolean isList,
    List<HopSpec> hops,
    String conditionWrapperClass, // null unless @condition
    String conditionWrapperMethod
) implements FieldSpec {}

record SplitQueryFieldSpec(
    String fieldName,             // DataFetcher method = fieldName; BatchLoader = fieldNameLoader
    String childJooqTableClass,
    String childFieldsClass,
    String parentTableClass,      // e.g. "CUSTOMER"
    String keyRecord,             // "CustomerRecord"
    String keyColumn,             // FK column for WHERE IN filter
    List<HopSpec> hops,
    OrderingSpec ordering
) implements FieldSpec {}

// Ordering spec
record OrderingSpec(DefaultOrderSpec defaultOrder, OrderByArgSpec orderByArg) {}
record DefaultOrderSpec(SortMode mode, String indexName, List<FieldSortSpec> sortFields, String direction) {}
record OrderByArgSpec(String argName, String javaType, List<OrderByCase> cases) {}
record OrderByCase(String enumLabel, SortMode mode, String indexName, List<FieldSortSpec> sortFields) {}
enum SortMode { INDEX, FIELDS, PRIMARY_KEY }
record FieldSortSpec(String columnName, String collation) {}  // collation null if absent

// Join chain
record HopSpec(
    HopJoinStrategy strategy,
    String intermediateTableClass,   // null for final hop
    String intermediateTableAlias,
    String conditionWrapperClass,    // null unless ReferenceElement has condition
    String conditionWrapperMethod
) {}
sealed interface HopJoinStrategy permits OnKeyJoin, PathJoin {}
record OnKeyJoin(String fkKeyConstant) implements HopJoinStrategy {}
record PathJoin(String pathMethodName) implements HopJoinStrategy {}  // DEFERRED
```

### New files

| File | Purpose |
|------|---------|
| `generators/record/spec/FieldsSpec.java` | All spec types above |
| `generators/record/FieldsSpecBuilder.java` | `GraphQLSchema` → `RootFieldsSpec`/`TypeFieldsSpec` via `SchemaTraverser`; FK auto-inference; `OrderingSpec` building |
| `generators/record/FieldsCodeGenerator.java` | `RootFieldsSpec`/`TypeFieldsSpec` → `TypeSpec` (all methods: wiring, select, inline, DataFetcher, BatchLoader) |
| `generators/record/FieldsClassGenerator.java` | Coordinator; one class per output type |

---

## Step 4: `GraphitronWiringClassGenerator`

**`GraphitronWiring`** aggregates all `<TypeName>Fields.wiring()` calls. No `configureDataLoaders()` — DataLoaders register on first use via `computeIfAbsent`.

```java
public class GraphitronWiring {
    public static RuntimeWiring.Builder getRuntimeWiringBuilder() {
        return RuntimeWiring.newRuntimeWiring()
            .type(QueryFields.wiring())
            .type(CustomerFields.wiring())
            .type(FilmFields.wiring())
            // ... all generated types
            ;
    }
}
```

No spec layer — the generator only needs the list of generated type names from Step 3.

**New files:** `generators/record/GraphitronWiringClassGenerator.java`

---

## Step 5: Orchestration

**`GraphQLGenerator.getGenerators()`** — when `recordBasedOutput = true`, add:

```
ConditionWrapperClassGenerator   → record.resolvers/<ConditionClassName>Wrapper
ServiceWrapperClassGenerator     → record.resolvers/<ServiceClassName>Wrapper
FieldsClassGenerator             → record.fields/<TypeName>Fields
GraphitronWiringClassGenerator   → record.resolvers/GraphitronWiring (last, after FieldsClassGenerator)
```

Mirrors existing pattern: data fetcher generators run first, wiring aggregator runs last.

---

## Critical Files

| File | Change |
|------|--------|
| `graphitron-common/.../GraphitronContext.java` | Add `getTenantId()` |
| `graphitron-common/.../DefaultGraphitronContext.java` | Implement `getTenantId()` → `Optional.empty()` |
| `graphitron-common/.../GraphitronFetcherFactory.java` | **New** — `field()`, `nestedRecord()`, `nestedResult()` with javadoc |
| `graphitron-java-codegen/.../configuration/GeneratorConfig.java` | Add `recordBasedOutput` |
| `graphitron-java-codegen/.../generate/Generator.java` | Add `boolean recordBasedOutput()` |
| `graphitron-maven-plugin/.../mojo/GenerateMojo.java` | Add `@Parameter recordBasedOutput` |
| `graphitron-java-codegen/.../generate/GraphQLGenerator.java` | Add new generators when flag is set |
| `graphitron-java-codegen/.../mappings/JavaPoetClassName.java` | Add `JOOQ_RECORD`, `JOOQ_RESULT`, `LIGHT_DATA_FETCHER`, `GRAPHITRON_FETCHER_FACTORY` |
| `generators/record/spec/FieldsSpec.java` | **New** |
| `generators/record/FieldsSpecBuilder.java` | **New** |
| `generators/record/FieldsCodeGenerator.java` | **New** |
| `generators/record/FieldsClassGenerator.java` | **New** |
| `generators/record/GraphitronWiringClassGenerator.java` | **New** |
| `generators/record/spec/ServiceWrapperSpec.java` | **New** |
| `generators/record/ServiceWrapperSpecBuilder.java` | **New** |
| `generators/record/ServiceWrapperCodeGenerator.java` | **New** |
| `generators/record/ServiceWrapperClassGenerator.java` | **New** |
| `generators/record/spec/ConditionWrapperSpec.java` | **New** |
| `generators/record/ConditionWrapperSpecBuilder.java` | **New** |
| `generators/record/ConditionWrapperCodeGenerator.java` | **New** |
| `generators/record/ConditionWrapperClassGenerator.java` | **New** |

---

## Generator Architecture: Spec Layer

Each generator in `generators/record/` follows the same two-layer pattern:

```
GraphQLSchema
  │
  ▼  <TypeName>SpecBuilder  (schema logic + FK inference; uses SchemaTraverser; zero JavaPoet)
  │
  ▼  <TypeName>Spec  (plain Java records / sealed interfaces)
  │
  ▼  <TypeName>CodeGenerator  (JavaPoet mapping; zero schema logic)
  │
  ▼  TypeSpec → .java file
```

FieldsCodeGenerator iterates the field specs and emits, per class:
1. `wiring()` method — one `.dataFetcher(...)` per field (factory call or method reference)
2. `select()` method — one entry per scalar, one guarded block per inline (non-root only)
3. Private inline methods — one per `InlineFieldSpec`
4. `public <name>(DataFetchingEnvironment)` — one per `SplitQueryFieldSpec` and root/service fields
5. `private <name>Loader(List, BatchLoaderEnvironment)` — one per `SplitQueryFieldSpec`
6. `private loaderName(ResultPath, Optional<String>)` — if any `SplitQueryFieldSpec` exists

---

## Testing Strategy

### Level 1 — SpecBuilder tests (AssertJ, no string comparison)

```java
@Test
void customerWithSplitQuery() {
    GraphQLSchema schema = assembleSchema("""
        type Customer @table { id: ID!, email: String, orders: [Order] @splitQuery }
        type Order @table { id: ID! }
        type Query { customers: [Customer] }
        """);
    TypeFieldsSpec spec = FieldsSpecBuilder.buildType(
        (GraphQLObjectType) schema.getType("Customer"), schema);

    assertThat(spec.graphqlType()).isEqualTo("Customer");
    assertThat(spec.fields()).containsExactly(
        new ScalarFieldSpec("id", "CUSTOMER_ID"),
        new ScalarFieldSpec("email", "EMAIL"),
        new SplitQueryFieldSpec("orders", "Order", "OrderFields", "CUSTOMER", "CustomerRecord", "CUSTOMER_ID", List.of(...), null)
    );
}
```

### Level 2 — CodeGenerator tests (approval, hand-crafted Spec)

```java
@Test
void customerFields() {
    var spec = new TypeFieldsSpec("CustomerFields", "Customer", "Customer", "customer", List.of(
        new ScalarFieldSpec("id", "CUSTOMER_ID"),
        new ScalarFieldSpec("email", "EMAIL_ADDRESS"),
        new InlineFieldSpec("payments", "payments", "Payment", "PaymentFields", true, List.of(...), null, null),
        new SplitQueryFieldSpec("orders", "Order", "OrderFields", "CUSTOMER", "CustomerRecord", "CUSTOMER_ID", List.of(...), null)
    ));
    assertGeneratedContentMatches(FieldsCodeGenerator.generate(spec), "CustomerFields");
}
```

### Test file layout

```
src/test/java/.../record/
  spec/
    FieldsSpecBuilderTest.java
  codegen/
    FieldsCodeGeneratorTest.java
  integration/
    RecordOutputIntegrationTest.java

src/test/resources/record/
  codegen/CustomerFields.java
  codegen/QueryFields.java
```

```bash
mvn test -pl :graphitron-java-codegen
mvn test -pl :graphitron-common
mise r build-all
```
