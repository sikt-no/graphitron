# Plan: Record-Based Output — Infrastructure and Generation

This document covers the generating stream, the Maven plugin wiring, the test infrastructure for generated code, and all remaining deliverables. The companion document [`plan-record-parsing-and-validation.md`](plan-record-parsing-and-validation.md) covers the completed parsing and validation work.

---

## Generator Overview

| Step | Generator | Output | Depends on |
|---|---|---|---|
| 0 | Infrastructure | `GraphitronFetcherFactory`, feature flag, `getTenantId()` | — |
| 1 | `ConditionWrapperClassGenerator` | `<ConditionClassName>Wrapper.java` per condition class | — |
| 2 | `ServiceWrapperClassGenerator` | `<ServiceClassName>Wrapper.java` per service class | — |
| 3 | `FieldsClassGenerator` | `<TypeName>Fields.java` | Steps 1 + 2 |
| 4 | `GraphitronWiringClassGenerator` | `GraphitronWiring.java` | Step 3 |
| 5 | Orchestration | Wire into `GraphQLGenerator` | Steps 0–4 |

`FieldsClassGenerator` is the core generator. It owns both the SQL logic (field methods, scope-establishing methods, DataLoaders) and `wiring()` for each type.

---

## Generator Architecture: Spec Layer

```
GraphQLSchema
  │
  ▼  GraphitronSchemaBuilder  (schema traversal + FK inference; zero JavaPoet)
  │
  ▼  GraphitronSchema  (Map<String, GraphitronType> + Map<FieldCoordinates, GraphitronField>)
  │
  ▼  FieldsCodeGenerator  (iterates fields; looks up by name / FieldCoordinates; JavaPoet)
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

Wrapper generators follow the same spec → codegen split.

---

## Threading model

All generated fetchers execute their JDBC work **synchronously on the calling thread** and return `CompletableFuture.completedFuture(result)`.

**Why synchronous-on-caller is correct:**

1. **graphql-java `AsyncExecutionStrategy` is not a thread pool.** It calls each `DataFetcher.get()` sequentially on its own execution thread and collects the returned `CompletableFuture<Object>` values. It then waits for all sibling futures via `CompletableFuture.allOf()`. The "async" refers to the ability to compose futures — not to concurrent dispatch. A fetcher that returns `completedFuture(x)` resolves immediately, with no thread switch.

2. **The host application is responsible for thread context.** graphql-java's execution engine is invoked by the application on whatever thread the application chooses. Any conforming host that issues blocking JDBC calls must already route GraphQL execution onto a thread where blocking is safe (a managed worker pool, virtual thread, etc.). Generated code inheriting that contract needs no additional dispatch.

3. **`supplyAsync(supplier, executor)` would add parallelism between sibling root fields**, but the cost outweighs the benefit: most queries have one root field; an extra thread switch adds latency for the common case; the executor must be managed and injected into context. The N+1 problem — the real threat — is solved by the DataLoader pattern, which batches many loads into one bulk query regardless of how many concurrent parents there are.

4. **`supplyAsync()` without an explicit executor is unconditionally wrong.** It defaults to `ForkJoinPool.commonPool()`, which is CPU-sized and not designed for blocking I/O.

DataLoader batch functions follow the same pattern: synchronous bulk SQL, returned as `completedFuture(result)`. The DataLoader framework itself handles dispatch timing.

---

## Prefetch-with-fallback pattern (`TableField` wiring)

Every inline `TableField` generates a `private static final` typed field constant used as the key both when embedding the subquery in `fields()` and when reading back the result in `wiring()`.

`fields()` pre-fetches all in-scope child fields via the parent SELECT, including any fields inside client-sent `@defer` fragments. The `wiring()` resolver checks the parent record first; if the data is already there it short-circuits and returns it without a second query. If not (e.g. the parent was fetched via a path that bypassed `fields()`), it falls back to a separate query.

`DeferBehaviorTest` pins the graphql-java behaviour: deferred child fields remain visible in the parent DataFetcher's `getSelectionSet()`, so the parent can pre-fetch them eagerly. When the deferred child DataFetcher eventually runs, the null-check short-circuits — zero extra SQL for deferred fields. Full incremental delivery support (streaming `@defer` payloads to the client) is tracked as a separate upstream issue.

---

## `@defer` (incremental delivery)

graphql-java 25.0 supports `@defer` behind `GraphQL.unusualConfiguration(...).incrementalSupport().enableIncrementalSupport(true)`. Without enabling incremental support, `@defer` is silently ignored and all fields resolve eagerly.

Generated DataFetchers are compatible with incremental delivery because of the prefetch-with-fallback pattern: the parent pre-fetches deferred child data inline, and the deferred child fetcher finds it already present on the source record. Wire-level streaming of incremental payloads is outside the scope of this plan.

---

## Package Structure

| Subpackage | Contents |
|---|---|
| `<outputPackage>.rewrite.fields` | `<TypeName>Fields` — SQL logic + `wiring()` per output type |
| `<outputPackage>.rewrite.resolvers` | `GraphitronWiring`, `<ConditionClassName>Wrapper`, `<ServiceClassName>Wrapper` |

---

## Deliverable sequence

### M1 — Maven plugin wiring (prerequisite for all generators)

Add `rewriteBasedOutput` to the plugin and generator pipeline. **Off by default; non-intrusive.**

**`GeneratorConfig.java`**:
```java
private static boolean rewriteBasedOutput = false;  // getter + setter
```

**`GenerateMojo.java`**:
```java
@Parameter(property = "graphitron.rewriteBasedOutput", defaultValue = "false")
protected boolean rewriteBasedOutput;
```

**`GraphQLGenerator.getGenerators()`**: when `rewriteBasedOutput` is enabled, append the new generators after the existing ones. Existing generators are unaffected regardless of flag value.

This is the only change to the Maven plugin for now. No new configuration parameters beyond the flag.

---

### M2 — Test module setup (prerequisite for all generator tests)

A new Maven module, `graphitron-codegen-parent/graphitron-record-test`, provides the infrastructure for compiling and running generated code against a real database.

**Build position:** after `graphitron-maven-plugin` in the reactor, so the plugin is available to use in `generate-sources`.

**Module structure:**

```
graphitron-record-test/
  src/
    main/
      java/
        no/sikt/graphitron/rewrite/test/
          conditions/     ← hand-written condition classes (user-side fixtures)
          service/        ← hand-written service classes (for service wrapper tests)
      resources/
        graphql/
          schema.graphqls ← minimal test schema referencing the fixtures above
    test/
      java/
        no/sikt/graphitron/rewrite/test/
          # tests for generated code
      resources/
        init.sql          ← database init script for testcontainers
  pom.xml
```

**`pom.xml` key points:**
- `graphitron-maven-plugin` bound to `generate-sources` with `rewriteBasedOutput = true`
- Plugin classpath includes this module itself (so it can see the hand-written condition/service classes)
- jOOQ dependency for the same test database that `graphitron-java-codegen` already uses (Sakila)
- testcontainers PostgreSQL for test execution (no Quarkus, no mocking)
- `graphitron-common` dependency (for `GraphitronContext`, `DSLContext` helpers)

**Why `src/main/java/` for fixtures:** the Maven plugin runs during `generate-sources` and resolves external class references by scanning the plugin's classpath. Hand-written condition and service classes must be compiled and on the classpath before the plugin runs, which means they go in `src/main/java/` (compiled in the `compile` phase, before `generate-sources` would run in a downstream module — but since the plugin has this module as a `<dependency>`, they are available to it).

---

### G1 — `ConditionWrapperClassGenerator`

First generator. Self-contained: no dependency on `FieldsClassGenerator` or field-level code generation. Each distinct condition class referenced in the schema (via `@condition` on an argument definition) produces one wrapper class.

**Generated output:**

```java
package <outputPackage>.rewrite.resolvers;

public class CustomerConditionsWrapper {
    public static Condition activeCustomers(DSLContext ctx) {
        return CustomerConditions.activeCustomers(ctx);
    }

    public static Condition premiumCustomers(DSLContext ctx) {
        return CustomerConditions.premiumCustomers(ctx);
    }
}
```

One public static method per condition method referenced from the schema. Arguments matched by name to the condition class's method signature; `DSLContext` matched by type.

**`override`** is a property of the condition spec consumed by `FieldsCodeGenerator`, not the wrapper. The wrapper is a pure delegation layer.

**Spec layer:** `ConditionWrapperSpec` carries the condition class reference and the list of method signatures. `ConditionWrapperSpecBuilder` extracts these from `GraphitronSchema`. `ConditionWrapperCodeGenerator` emits the `TypeSpec`. `ConditionWrapperClassGenerator` orchestrates the three.

**Tests:**
- Approval test in `graphitron-java-codegen`: verifies generated source text
- DB integration test in `graphitron-record-test`: calls the generated wrapper method, uses the returned `Condition` in a jOOQ query, asserts correct results from the test database

---

### G2 — `ServiceWrapperClassGenerator`

Analogous to G1 but for service classes. Each distinct service class referenced via `@service` on a root or child field produces one wrapper class.

```java
public class HelloWorldServiceWrapper {
    public static CompletableFuture<HelloWorldRecord> helloWorldAgain(DataFetchingEnvironment env) {
        String name = env.getArgument("name");
        GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
        HelloWorldService service = new HelloWorldService(ctx.getDslContext(env));
        return CompletableFuture.completedFuture(service.helloWorldAgain(name));
    }
}
```

Arguments matched by name; `DSLContext` matched by type; instance service classes via `(DSLContext)` or no-arg constructor.

**New files:** `ServiceWrapperSpec.java`, `ServiceWrapperSpecBuilder.java`, `ServiceWrapperCodeGenerator.java`, `ServiceWrapperClassGenerator.java`

---

### M3 — Infrastructure classes

**`GraphitronFetcherFactory`** (in `graphitron-common`):

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

**`GraphitronContext` — add `getTenantId()`:**

```java
@NotNull Optional<String> getTenantId(DataFetchingEnvironment env);
```

`DefaultGraphitronContext` returns `Optional.empty()`. Used in `loaderName()` for multi-tenant DataLoader key isolation.

---

### G3 — Scalar child fields (`ColumnField`, `ColumnReferenceField`)

The first `FieldsCodeGenerator` deliverable. Generates `wiring()` entries for scalar fields and their contributions to `fields(table, sel)`.

**Generated `CustomerFields` (scalar-only):**

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

Extends `FieldsCodeGenerator` with `TableField` in table-mapped source context (no `@splitQuery`). Introduces the static field method pattern and the prefetch-with-fallback resolver (see above).

---

### G6 — `@splitQuery` `TableField`

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

### G7 — Remaining child types (G3 from original plan)

`NodeIdField`, `NodeIdReferenceField`, `ComputedField`, `PropertyField`, `TableInterfaceField`, `InterfaceField`, `UnionField`, `NestingField`, `TableMethodField`, `ServiceField`.

One wiring entry style per type. The testing contract requires at least one approval test file per type.

---

### G8 — Remaining root field types (G4 from original plan)

`LookupQueryField`, `TableMethodQueryField`, `NodeQueryField`, `EntityQueryField`, `TableInterfaceQueryField`, `InterfaceQueryField`, `UnionQueryField`, `ServiceQueryField`.

---

### I2 — Ordering

`@defaultOrder` and `@orderBy`. Extends `FieldsCodeGenerator` to emit ORDER BY clauses in generated queries.

---

### I3 — `@condition` in field wiring

Integrates condition wrappers into `FieldsCodeGenerator`. Fields with `@condition` args pass the condition result to the WHERE clause. The `override` property on condition specs controls whether the condition replaces or augments the default WHERE.

---

## Testing Strategy

### Level 4 — Generated code against a real database

**Principle:** Generated code must be tested by compiling it and executing it against a live database as part of the build pipeline. Tests must not mock the database and must not assert on SQL query structure. They assert on whether the correct data is returned from the test database.

**Infrastructure:** the `graphitron-record-test` module (see M2 above) uses:
- `graphitron-maven-plugin` bound to `generate-sources` — generated code is compiled as ordinary Java source by Maven
- testcontainers PostgreSQL, started per test class via `@BeforeAll` / `@Testcontainers`
- jOOQ `DSLContext` constructed directly from the testcontainers JDBC URL — no CDI, no Quarkus

**Test structure:**

```java
@Testcontainers
class CustomerConditionsWrapperTest {

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
    void activeCustomers_returnsOnlyActiveRows() {
        var condition = CustomerConditionsWrapper.activeCustomers(ctx);
        var result = ctx.select(CUSTOMER.CUSTOMER_ID)
            .from(CUSTOMER)
            .where(condition)
            .fetch();

        assertThat(result).isNotEmpty();
        assertThat(result.map(r -> r.get(CUSTOMER.ACTIVE))).containsOnly(true);
    }
}
```

**What these tests do NOT check:** the SQL text, the number of queries issued, query plans, column ordering in SELECT lists. Those details are internal to the generator and covered by approval tests in `graphitron-java-codegen`.

**What these tests DO check:** that the generated method compiles, that it produces a correct jOOQ `Condition` or result when called against a database with known data, and that the result set matches what the test data expects.

### Existing levels (unchanged)

- **Level 1** — Validator unit tests (direct field/type construction, no DB)
- **Level 2** — Classification tests (inline schema → `GraphitronSchemaBuilder`, no DB)
- **Level 3** — Error message and source location tests (no DB)
- **Approval tests** — `FieldsCodeGeneratorTest` hand-crafts `GraphitronField` instances and compares generated text against expected `.java` files (no DB)

---

## Scope and Future Work

### Mutations

The sealed hierarchy models all five mutation field types. None of G1–G8 cover generating them. Mutation generation is deferred to a follow-on phase.

### Removing the DTO layer

Once the record-based pipeline achieves full feature parity and the example server passes all approval tests under the flag:
1. Delete DTO generator classes
2. Delete TypeMapper generator classes
3. Remove the `rewriteBasedOutput` flag — record-based output becomes the only path
4. Update `GraphQLGenerator.getGenerators()`

---

## Critical Files

| File | Change |
|---|---|
| `graphitron-common/.../GraphitronFetcherFactory.java` | **New** |
| `graphitron-common/.../GraphitronContext.java` | Add `getTenantId()` |
| `graphitron-common/.../DefaultGraphitronContext.java` | Implement `getTenantId()` → `Optional.empty()` |
| `graphitron-java-codegen/.../configuration/GeneratorConfig.java` | Add `rewriteBasedOutput` flag |
| `graphitron-java-codegen/.../generate/Generator.java` | Add `boolean rewriteBasedOutput()` |
| `graphitron-maven-plugin/.../mojo/GenerateMojo.java` | Add `@Parameter rewriteBasedOutput` |
| `graphitron-java-codegen/.../generate/GraphQLGenerator.java` | Add new generators when flag is set |
| `graphitron-java-codegen/.../mappings/JavaPoetClassName.java` | Add `JOOQ_RECORD`, `JOOQ_RESULT`, `LIGHT_DATA_FETCHER`, `GRAPHITRON_FETCHER_FACTORY` |
| `graphitron-record-test/pom.xml` | **New module** |
| `rewrite/ConditionWrapper*.java` (4 files) | **New** |
| `rewrite/ServiceWrapper*.java` (4 files) | **New** |
| `rewrite/FieldsCodeGenerator.java` | **New** |
| `rewrite/FieldsClassGenerator.java` | **New** |
| `rewrite/GraphitronWiringClassGenerator.java` | **New** |
