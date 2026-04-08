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
| `<outputPackage>.rewrite.fields` | `<TypeName>Fields` — SQL assembly + wiring per output type |
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

Every `@table` type generates a `<TypeName>Fields` class with two kinds of method.

**Static field methods** produce `Field<Result<Record>>` (multiset, one-to-many) or `Field<Record>` (row, one-to-one) expressions composable into any SELECT clause — analogous to jOOQ's own `FILM.FILM_ID` constants. They use graphql-java's native `SelectedField`, which carries both `getSelectionSet()` and `getArguments()`.

```java
// In FilmFields
public static Field<Result<Record>> actors(Film film, SelectedField field) {
    return DSL.multiset(
        DSL.select(ActorFields.fields(ACTOR, field.getSelectionSet()))
           .from(ACTOR)
           .join(FILM_ACTOR).on(FILM_ACTOR.ACTOR_ID.eq(ACTOR.ACTOR_ID))
           .where(FILM_ACTOR.FILM_ID.eq(film.FILM_ID))
           .orderBy(actorOrderBy(field.getArguments()))
    ).as("actors");
}
```

**`fields(table, sel)`** assembles the SELECT list, passing each sub-field's `SelectedField` directly:

```java
List<Field<?>> fields(Film film, DataFetchingFieldSelectionSet sel) {
    var fields = new ArrayList<Field<?>>();
    fields.add(film.TITLE);
    sel.getFields("actors").forEach(f -> fields.add(actors(film, f)));
    sel.getFields("language").forEach(f -> fields.add(language(film, f)));
    return fields;
}
```

Two scope-establishing methods delegate to `fields()`:

```java
// Starts a new SQL statement — used by root queries, DataLoaders (split + record handoff), mutation read-back.
SelectFinalStep<Record> filmSelect(DSLContext ctx, DataFetchingFieldSelectionSet sel,
    Condition condition, List<SortField<?>> orderBy)

// Contributes to an existing statement as a multiset subquery.
Field<Result<Record>> filmNested(DataFetchingFieldSelectionSet sel,
    Condition condition, List<SortField<?>> orderBy)

// @tableMethod overload — developer supplies a pre-filtered table.
Field<Result<Record>> filmNested(Table<FilmRecord> table, ...)
```

Results are jOOQ `Record` instances. Scalars via `record.get(TABLE.FIELD)`; nested via `record.get(nestedField)`.

**Field type to method mapping:**

| Field type | Method |
|---|---|
| `TableQueryField` | `filmSelect` |
| `LookupQueryField` — single | `filmSelect` with key condition |
| `LookupQueryField` — batch DataLoader | positional VALUES join → `filmNested` per row |
| `TableField` — no `@splitQuery` | `filmNested` |
| `TableField` — `@splitQuery` | DataLoader → `filmSelect` (Graphitron controls both sides) |
| `TableField` — record handoff | DataLoader → `filmSelect` with derived source table (from parent `TableRecord` PK) |
| `ServiceField` / `TableMethodField` returning table-mapped type | DataLoader → `filmSelect` with derived source table (from returned `TableRecord` PK) |
| `InterfaceField` | union over each implementor's `filmNested` |
| Mutation read-back | `filmSelect` with derived source table (from returned `TableRecord` PK) |

**`LookupQueryField` batch mapping**: each input key drives one row in a VALUES outer query; the nested multiset produces the matching result. The invariant is that output cardinality and ordering match the input keys. Missing keys produce a null row, preserving positional alignment.

---

> **Plan review in progress.** Deliverables from G3 onwards have not yet been revised in light of current design decisions.

---

### G3 — Scalar child fields (`ColumnField`, `ColumnReferenceField`)

The first `FieldsCodeGenerator` deliverable. Generates `wiring()` entries for scalar fields and their contributions to `fields(table, sel)`.

**Generated `CustomerFields` (scalar-only):**

```java
public class CustomerFields {

    public static TypeRuntimeWiring.Builder wiring() {
        return TypeRuntimeWiring.newTypeWiring("Customer")
            .dataFetcher("id",    GraphitronFetchers.field(CUSTOMER.CUSTOMER_ID))
            .dataFetcher("email", GraphitronFetchers.field(CUSTOMER.EMAIL_ADDRESS));
    }

    public static List<Field<?>> fields(Customer customer, DataFetchingFieldSelectionSet sel) {
        var fields = new ArrayList<Field<?>>();
        fields.add(customer.CUSTOMER_ID);
        fields.add(customer.EMAIL_ADDRESS);
        return fields;
    }
}
```

---

### G4 — Root query fields (`TableQueryField`)

The simplest root field type: queries that return a `@table` type. Generates the DataFetcher method on `QueryFields`.

```java
public static CompletableFuture<Record> customer(DataFetchingEnvironment env) {
    GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
    String id = env.getArgument("id");
    var _a = CUSTOMER.as("customer_hash");
    return CompletableFuture.completedFuture(
        ctx.getDslContext(env)
            .select(CustomerFields.fields(_a, env.getSelectionSet()))
            .from(_a)
            .where(_a.CUSTOMER_ID.eq(UInteger.valueOf(id)))
            .fetchOne()
    );
}
```

---

### I1 — `FieldsClassGenerator` + `GraphitronWiringClassGenerator`

Wires G3 and G4 into runnable classes. `FieldsClassGenerator` produces one `<TypeName>Fields.java` per output type. `GraphitronWiringClassGenerator` produces `GraphitronWiring.java` aggregating all `wiring()` calls.

This is the first deliverable that produces an end-to-end working pipeline for scalar-only types.

---

### G5 — Inline `TableField`

Extends `FieldsCodeGenerator` with `TableField` in table-mapped source context (no `@splitQuery`). Introduces the static field method pattern.

---

### G6 — `@splitQuery` `TableField`

Extends `FieldsCodeGenerator` with `TableField` where `@splitQuery` is set. Adds DataLoader + BatchLoader generation and the `loaderName()` helper. The derived source helper class (`<ParentType><FieldName>DerivedSource`) is already generated by `SplitSourceClassGenerator`; G6 generates the DataLoader and BatchLoader methods that call it.

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
    Order _a = ORDER.as("order_hash");
    return CompletableFuture.completedFuture(
        gCtx.getDslContext(env)
            .select(OrderFields.fields(_a, env.getSelectionSet()))
            .from(_a)
            .where(_a.CUSTOMER_ID.in(keys.stream().map(CustomerRecord::getCustomerId).toList()))
            .fetch().stream()
            .collect(Collectors.groupingBy(r -> r.into(CUSTOMER)))
    );
}

private static String loaderName(ResultPath path, Optional<String> tenantId) {
    String normalized = path.toList().stream()
        .filter(seg -> !(seg instanceof Integer))
        .map(Object::toString).collect(Collectors.joining("/"));
    return tenantId.map(id -> id + "/" + normalized).orElse(normalized);
}
```

---

### G7 — Remaining child types

`NodeIdField`, `NodeIdReferenceField`, `ComputedField`, `PropertyField`, `TableInterfaceField`, `InterfaceField`, `UnionField`, `NestingField`, `TableMethodField`, `ServiceField`.

One wiring entry style per type. The testing contract requires at least one approval test file per type.

---

### G8 — Remaining root field types

`LookupQueryField`, `TableMethodQueryField`, `NodeQueryField`, `EntityQueryField`, `TableInterfaceQueryField`, `InterfaceQueryField`, `UnionQueryField`, `ServiceQueryField`.

---

### I2 — Ordering

`@defaultOrder` and `@orderBy`. Extends `FieldsCodeGenerator` to emit ORDER BY clauses in generated queries.

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

    static DSLContext ctx;

    @BeforeAll
    static void setupDsl() {
        ctx = DSL.using(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    }

    @Test
    void fields_returnsExpectedScalars() {
        var result = ctx
            .select(FilmFields.fields(FILM, /* sel */))
            .from(FILM)
            .where(FILM.FILM_ID.eq(1))
            .fetchOne();

        assertThat(result.get(FILM.TITLE)).isEqualTo("ACADEMY DINOSAUR");
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
| `rewrite/FieldsCodeGenerator.java` | **New** |
| `rewrite/FieldsClassGenerator.java` | **New** |
| `rewrite/GraphitronWiringClassGenerator.java` | **New** |
