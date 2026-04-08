# Rewrite Pipeline: Generation Plan

> **Status: in progress.** The parsing and validation layer is complete (see [`rewrite-schema-classification.md`](rewrite-schema-classification.md)). This document covers the generating stream, Maven plugin wiring, and test infrastructure. The rewrite pipeline is behind the `enableRewrite` flag (default `false`) and is not ready for production use.

---

## Taxonomy-first rule

Generators consume only what the taxonomy provides. They receive `GraphitronField` and `GraphitronType` instances from `GraphitronSchema` and emit code from the data those records carry — nothing more.

If a generator needs a piece of information that is not present in the taxonomy, the correct fix is to add a record component to the appropriate sealed type and extract the value in `GraphitronSchemaBuilder`, not to import or access jOOQ or graphql-java types in generator code. `GraphitronSchemaBuilder` (and `JooqCatalog`) are the only permitted holders of raw jOOQ objects (`Table<?>`, `Field<?>`, `ForeignKey<?,?>`) and raw graphql-java schema objects (`TypeDefinitionRegistry`, `GraphQLSchema`, `GraphQLFieldDefinition`, etc.); all downstream code works from plain strings, booleans, and primitives. This constraint enforces a complete taxonomy: every fact the generators rely on must be explicitly declared, which in turn makes the intermediate representation self-documenting and independently testable.

---

## Package structure

| Subpackage | Contents |
|---|---|
| `<outputPackage>.rewrite` | `GraphitronValues`, `GraphitronFetchers` |
| `<outputPackage>.rewrite.tables` | `<TableName>` — SQL scope methods per table (`Film`, `FilmActor`, …); named after the SQL table, not the GraphQL type |
| `<outputPackage>.rewrite.fields` | `<TypeName>Fields` — GraphQL field wiring per output type; named after the GraphQL type |
| `<outputPackage>.rewrite.resolvers` | `GraphitronWiring`, `<TypeName>Lookup`, `<ParentType><FieldName>DerivedSource` |

---

## Deliverable sequence

### M1 — Maven plugin wiring *(done)*

`enableRewrite` and `disableLegacy` flags added to `GenerateMojo` and wired through `GeneratorConfig` and `GraphQLRewriteGenerator`. When `enableRewrite` is set, the rewrite generators run. Existing generators are unaffected unless `disableLegacy` is also set.

---

### M2 — Test module setup *(done)*

`graphitron-rewrite-test` (at the reactor root) contains two submodules:

- **`graphitron-rewrite-test-fixtures`** — jOOQ class generation from a dedicated test schema via TestContainers, producing `no.sikt.graphitron.rewrite.test.jooq.*`. Contains `init.sql`.
- **`graphitron-rewrite-test-spec`** — runs `graphitron-maven-plugin` in `generate-sources` with `<enableRewrite>true</enableRewrite>` and `<disableLegacy>true</disableLegacy>`, then compiles and tests the generated output against a TestContainers PostgreSQL database.

---

### Generators already done

| Generator | Output | Notes |
|---|---|---|
| `GraphitronValuesClassGenerator` | `GraphitronValues.java` in `rewrite` | Defines `GRAPHITRON_INPUT_IDX` |
| `LookupClassGenerator` | `<TypeName>Lookup.java` in `rewrite.resolvers` | Derived source rows for lookup key batching |
| `SplitSourceClassGenerator` | `<ParentType><FieldName>DerivedSource.java` in `rewrite.resolvers` | Derived source rows for `@splitQuery` DataLoader batching |
| `TableClassGenerator` | `<TableName>.java` in `rewrite.tables` | Scope-establishing stubs (`selectMany`, `selectOne`, `subselectMany`, `subselectOne`); named after the jOOQ table class |

Each `DerivedSource` / `Lookup` class contains a single static `rows` method that maps a list of parent records or input argument maps into typed `List<RowN<Integer, T1, ...>>` rows for use in a jOOQ `DSL.values(...).asTable(...)` derived table.

---

### `GraphitronFetchersClassGenerator`

Generates `GraphitronFetchers.java` into `<outputPackage>.rewrite`. The class contains the standard `LightDataFetcher` factory methods used by all generated `wiring()` methods. Generated rather than shipped as a runtime library dependency so that consuming projects have no runtime dependency on Graphitron itself — the same rationale as `GraphitronValues`.

```java
public class GraphitronFetchers {

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

Simple generator analogous to `GraphitronValuesClassGenerator`: emits a fixed class with no schema input.

---

### M3 — `getTenantId()`

Add `getTenantId()` to `GraphitronContext` in `graphitron-common`:

```java
@NotNull Optional<String> getTenantId(DataFetchingEnvironment env);
```

`DefaultGraphitronContext` returns `Optional.empty()`. Used in `loaderName()` for multi-tenant DataLoader key isolation.

---

### Per-type select pattern *(next)*

Every `@table` type generates two classes in different packages:

- **`rewrite.tables.<TableName>`** — SQL namespace, named after the jOOQ table class. Owns `fields()` (SELECT list assembly), `selectMany/One` (execute new statement), `subselectMany/One` (build subquery expression), and batch loader methods.
- **`rewrite.fields.<TypeName>Fields`** — GraphQL namespace, named after the GraphQL type. Owns one static method per GraphQL field — each is a `DataFetcher<T>` by signature — and a `wiring()` method that registers them by method reference.

The type name and table name can differ (e.g. GraphQL type `MovieItem` backed by SQL table `film` yields `Film` + `MovieItemFields`).

**`wiring()` uses method references, not lambdas.** Because each field is a named `static T fieldName(DataFetchingEnvironment env)` method, it satisfies `DataFetcher<T>` and can be referenced directly:

```java
// rewrite.fields.FilmFields
public static TypeRuntimeWiring.Builder wiring() {
    return TypeRuntimeWiring.newTypeWiring("Film")
        .dataFetcher("title",  FilmFields::title)
        .dataFetcher("actors", FilmFields::actors);
}
```

The wiring method is a pure manifest — no logic, no lambdas. Each field method carries the logic and is independently testable.

**`rewrite.tables.Film`** contains the SQL side:

```java
// Assembles the SELECT list for this type's query.
List<Field<?>> fields(Film alias, DataFetchingFieldSelectionSet sel)

// Scope-establishing methods (current stubs):
Result<Record>          selectMany(DataFetchingEnvironment env, Condition condition, List<SortField<?>> orderBy)
Record                  selectOne(DataFetchingEnvironment env, Condition condition)
Field<Result<Record>>   subselectMany(DataFetchingFieldSelectionSet sel, Condition condition, List<SortField<?>> orderBy)
Field<Record>           subselectOne(DataFetchingFieldSelectionSet sel, Condition condition)
```

`selectMany` and `selectOne` obtain a `DSLContext` internally. The jOOQ `XYZ*Step` types are never referenced in generated method signatures because they are mutable, less composable, and binary-incompatible across jOOQ minor releases.

Results are jOOQ `Record` instances. Scalars via `record.get(TABLE.FIELD)`; nested via `record.get(nestedField)`.

**Field type to method mapping:**

| Field type | `*Fields` fetcher method | Delegates to |
|---|---|---|
| `ColumnField` / `ColumnReferenceField` | `static T fieldName(env)` | `record.get(TABLE.COL)` from source |
| `TableQueryField` — list | `static Result<Record> fieldName(env)` | `TableName.selectMany` |
| `TableQueryField` — single | `static Record fieldName(env)` | `TableName.selectOne` |
| `LookupQueryField` — single | `static Record fieldName(env)` | `TableName.selectOne` with key condition |
| `LookupQueryField` — batch DataLoader | `static CompletableFuture<…> fieldName(env)` | `TableName.selectMany` via DataLoader |
| `TableField` — list, no `@splitQuery` | `static Result<Record> fieldName(env)` | extract nested column from source `Record` |
| `TableField` — single, no `@splitQuery` | `static Record fieldName(env)` | extract nested column from source `Record` |
| `TableField` — `@splitQuery` | `static CompletableFuture<…> fieldName(env)` | `TableName.selectMany` via DataLoader |
| `TableField` — record handoff | `static CompletableFuture<…> fieldName(env)` | `TableName.selectMany` via DataLoader + derived source |
| `ServiceField` / `TableMethodField` → table | `static CompletableFuture<…> fieldName(env)` | `TableName.selectMany` via DataLoader + derived source |
| `InterfaceField` | `static Object fieldName(env)` | union over each implementor's `subselectMany` |
| Mutation read-back | *(inside mutation fetcher)* | `TableName.selectMany` with derived source |

**`LookupQueryField` batch mapping**: each input key drives one row in a VALUES outer query; the nested multiset produces the matching result. Missing keys produce a null row, preserving positional alignment.

---

> **Plan review in progress.** Deliverables from G3 onwards have not yet been revised in light of current design decisions.

---

### G3 — Scalar child fields (`ColumnField`, `ColumnReferenceField`)

Generates `fields()` in `rewrite.tables` and one fetcher method + wiring entry in `rewrite.fields` per scalar field.

**`rewrite.tables.Customer`** — SELECT list assembly:
```java
public static List<Field<?>> fields(Customer alias, DataFetchingFieldSelectionSet sel) {
    var fields = new ArrayList<Field<?>>();
    if (sel.contains("id"))    fields.add(alias.CUSTOMER_ID);
    if (sel.contains("email")) fields.add(alias.EMAIL_ADDRESS);
    return fields;
}
```

**`rewrite.fields.CustomerFields`** — one method per field, wiring by method reference:
```java
public static Object id(DataFetchingEnvironment env) {
    return ((Record) env.getSource()).get(CUSTOMER.CUSTOMER_ID);
}

public static String email(DataFetchingEnvironment env) {
    return ((Record) env.getSource()).get(CUSTOMER.EMAIL_ADDRESS);
}

public static TypeRuntimeWiring.Builder wiring() {
    return TypeRuntimeWiring.newTypeWiring("Customer")
        .dataFetcher("id",    CustomerFields::id)
        .dataFetcher("email", CustomerFields::email);
}
```

---

### G4 — Root query fields (`TableQueryField`)

Generates one fetcher method in `rewrite.fields.QueryFields` per root field, delegating to `TableName.selectMany/One`.

```java
// rewrite.fields.QueryFields
public static Record customer(DataFetchingEnvironment env) {
    return Customer.selectOne(env, CUSTOMER.CUSTOMER_ID.eq(env.getArgument("id")));
}

public static Result<Record> customers(DataFetchingEnvironment env) {
    return Customer.selectMany(env, DSL.noCondition(), List.of());
}

public static TypeRuntimeWiring.Builder wiring() {
    return TypeRuntimeWiring.newTypeWiring("Query")
        .dataFetcher("customer",  QueryFields::customer)
        .dataFetcher("customers", QueryFields::customers);
}
```

---

### I1 — `FieldsClassGenerator` + `GraphitronWiringClassGenerator` *(TableClassGenerator done)*

`TableClassGenerator` already exists and generates one stub class per SQL table. The remaining work:

- **`FieldsClassGenerator`** — generates one `<TypeName>Fields.java` per GraphQL output type, containing the field fetcher methods and `wiring()`.
- **`GraphitronWiringClassGenerator`** — generates `GraphitronWiring.java` aggregating all `wiring()` calls:

```java
public static RuntimeWiring build() {
    return RuntimeWiring.newRuntimeWiring()
        .type(QueryFields.wiring())
        .type(FilmFields.wiring())
        .type(CustomerFields.wiring())
        .build();
}
```

This is the first deliverable that produces an end-to-end working pipeline for scalar-only types.

---

### G5 — Inline `TableField`

Extends `TableCodeGenerator` with `TableField` in table-mapped source context (no `@splitQuery`). Introduces the static field method pattern.

---

### G6 — `@splitQuery` `TableField`

Generates a DataLoader fetcher method in `rewrite.fields.*Fields` and a batch loader method in `rewrite.tables.*`. The derived source helper (`<ParentType><FieldName>DerivedSource`) is already generated by `SplitSourceClassGenerator`; G6 generates the DataLoader and batch loader that call it.

**`rewrite.fields.CustomerFields`** — DataLoader fetcher, used as `CustomerFields::orders` in `wiring()`:
```java
public static CompletableFuture<Result<Record>> orders(DataFetchingEnvironment env) {
    GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
    String name = loaderName(env.getExecutionStepInfo().getPath(), ctx.getTenantId(env));
    DataLoader<CustomerRecord, Result<Record>> loader = env.getDataLoaderRegistry()
        .computeIfAbsent(name, k -> DataLoaderFactory.newMappedDataLoaderWithContext(
            Order::byCustomerLoader));
    return loader.load(((Record) env.getSource()).into(CUSTOMER), env);
}
```

**`rewrite.tables.Order`** — batch loader (SQL work belongs in the table class):
```java
public static CompletableFuture<Map<CustomerRecord, Result<Record>>> byCustomerLoader(
        List<CustomerRecord> keys, BatchLoaderEnvironment ctx) {
    DataFetchingEnvironment env = (DataFetchingEnvironment) ctx.getKeyContextsList().get(0);
    GraphitronContext gCtx = env.getGraphQlContext().get("graphitronContext");
    Order _a = ORDER.as("order_hash");
    return CompletableFuture.completedFuture(
        gCtx.getDslContext(env)
            .select(Order.fields(_a, env.getSelectionSet()))
            .from(_a)
            .where(_a.CUSTOMER_ID.in(keys.stream().map(CustomerRecord::getCustomerId).toList()))
            .fetch().stream()
            .collect(Collectors.groupingBy(r -> r.into(CUSTOMER)))
    );
}
```

`loaderName` is a private helper in the `*Fields` class (GraphQL path-based, not SQL).

---

### G7 — Remaining child types

`NodeIdField`, `NodeIdReferenceField`, `ComputedField`, `PropertyField`, `TableInterfaceField`, `InterfaceField`, `UnionField`, `NestingField`, `TableMethodField`, `ServiceField`.

One wiring entry style per type. The testing contract requires at least one approval test file per type.

---

### G8 — Remaining root field types

`LookupQueryField`, `TableMethodQueryField`, `NodeQueryField`, `EntityQueryField`, `TableInterfaceQueryField`, `InterfaceQueryField`, `UnionQueryField`, `ServiceQueryField`.

---

### I2 — Ordering

`@defaultOrder` and `@orderBy`. Extends `TableCodeGenerator` to emit ORDER BY clauses in generated queries.

---

### I3 — `@condition` in field wiring

Integrates condition handling directly into the generated WHERE clause. Fields with `@condition` arguments call the user-supplied condition class directly — no generated wrapper class. The `override` property on condition specs controls whether the condition replaces or augments the default WHERE.

---

## Testing Strategy

### Level 4 — Generated code against a real database

**Principle:** Generated code must be tested by compiling it and executing it against a live database as part of the build pipeline. Tests must not mock the database and must not assert on SQL query structure. They assert on whether the correct data is returned from the test database.

**Infrastructure:** `graphitron-rewrite-test-spec` uses:
- `graphitron-maven-plugin` bound to `generate-sources` — generated code is compiled as ordinary Java source by Maven
- TestContainers PostgreSQL, started per test class via `@BeforeAll` / `@Testcontainers`
- jOOQ `DSLContext` constructed directly from the TestContainers JDBC URL — no CDI, no Quarkus

**Test structure:**

```java
@Testcontainers
class FilmFieldsTest {

    @Container
    static final PostgreSQLContainer<?> DB =
        new PostgreSQLContainer<>("postgres:15")
            .withInitScript("init.sql");

    @BeforeAll
    static void setup() { /* bind DSLContext into GraphitronContext */ }

    @Test
    void title_returnsExpectedScalar() {
        // Use the generated FilmFields.selectOne via a mock DataFetchingEnvironment
        // or exercise it end-to-end via a real GraphQL execution.
        var record = Film.selectOne(mockEnv, FILM.FILM_ID.eq(1));
        assertThat(FilmFields.title(envWithSource(record))).isEqualTo("ACADEMY DINOSAUR");
    }
}
```

**What these tests do NOT check:** the SQL text, the number of queries issued, query plans, column ordering in SELECT lists. Those details are covered by approval tests in `graphitron-java-codegen`.

**What these tests DO check:** that the generated method compiles and returns the correct data from a database with known rows.

### Existing levels (unchanged)

- **Level 1** — Validator unit tests (direct field/type construction, no DB)
- **Level 2** — Classification tests (inline schema → `GraphitronSchemaBuilder`, no DB)
- **Level 3** — Error message and source location tests (no DB)
- **Approval tests** — hand-crafted `GraphitronField` instances compared against expected `.java` files (no DB)

### Open gaps in parsing/validation layer

| Gap | Severity | Recommendation |
|---|---|---|
| **No `ErrorTypeValidationTest`** | Low | Construct an `ErrorType` and assert zero validation errors. Documents the intentional validator no-op and prevents accidental regression. |
| **`hasLookupKeyAnywhere()` depth guard** | Low | Add a test confirming the guard prevents infinite recursion on circular input type references (depth limit is 10 levels). |
| **`JooqCatalog` direct unit test with Sakila** | Low | `JooqCatalog` is tested indirectly via `GraphitronSchemaBuilderTest`; a direct test against the Sakila jOOQ classes would catch reflection edge cases (unusual table naming, composite FKs). |

---

## Threading model

All generated fetchers execute their JDBC work **synchronously on the calling thread** and return `CompletableFuture.completedFuture(result)`.

1. **graphql-java `AsyncExecutionStrategy` is not a thread pool.** It calls each `DataFetcher.get()` sequentially and collects the returned `CompletableFuture<Object>` values, then waits via `CompletableFuture.allOf()`. The "async" refers to future composition, not concurrent dispatch.

2. **The host application is responsible for thread context.** Any conforming host that issues blocking JDBC calls must already route GraphQL execution onto a thread where blocking is safe. Generated code inherits that contract.

3. **`supplyAsync(supplier, executor)` would add parallelism between sibling root fields**, but most queries have one root field; extra thread switches add latency in the common case; and the N+1 problem is solved by the DataLoader pattern, not by sibling parallelism.

4. **`supplyAsync()` without an explicit executor is unconditionally wrong.** It defaults to `ForkJoinPool.commonPool()`, which is CPU-sized and not designed for blocking I/O.

DataLoader batch functions follow the same pattern: synchronous bulk SQL, returned as `completedFuture(result)`.

---

## Scope and Future Work

### Mutations

The sealed hierarchy models all five mutation field types. None of G3–G8 cover generating them. Mutation generation is deferred to a follow-on phase.

### Service wrappers

Condition handling and service calls are inlined in the generated code. If generated files become unwieldy in practice, extracting service call delegation into generated wrapper classes is a straightforward follow-on.

### Removing the DTO layer

Once the record-based pipeline achieves full feature parity and the example server passes all approval tests under the flag:
1. Delete DTO generator classes
2. Delete TypeMapper generator classes
3. Remove the `enableRewrite` / `disableLegacy` flags — record-based output becomes the only path
4. Update `GraphQLGenerator.getGenerators()`

---

## Critical Files

| File | Change |
|---|---|
| `graphitron-common/.../GraphitronContext.java` | Add `getTenantId()` |
| `graphitron-common/.../DefaultGraphitronContext.java` | Implement `getTenantId()` → `Optional.empty()` |
| `graphitron-java-codegen/.../mappings/JavaPoetClassName.java` | Add `JOOQ_RECORD`, `JOOQ_RESULT`, `LIGHT_DATA_FETCHER`, `GRAPHITRON_FETCHERS` |
| `rewrite/generators/util/GraphitronFetchersClassGenerator.java` | **New** |
| `rewrite/generators/fields/TableCodeGenerator.java` | **Done** — scope-establishing stubs only |
| `rewrite/generators/fields/TableClassGenerator.java` | **Done** — iterates `TableType`s, uses `javaClassName` |
| `rewrite/GraphitronWiringClassGenerator.java` | **New** |
