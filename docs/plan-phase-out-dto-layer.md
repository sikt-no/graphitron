# Plan: Phase Out DTO Layer — Record-Based Output

## Context

The current code generation pipeline produces output DTOs and TypeMapper classes that convert jOOQ Records into those DTOs. This is unnecessary: graphql-java's RuntimeWiring can resolve fields directly from any Java object, including jOOQ `Record`. Eliminating the DTO/TypeMapper layer reduces generated code volume, removes the selection-set-per-field mapping boilerplate, and unblocks future features (e.g. Java record output support).

The change is behind a `recordBasedOutput` feature flag (default `false`) and generates new artefacts into a **separate package** (`<outputPackage>.record.*`) so old and new code can coexist. **The existing generators (`TypeDTOGenerator`, `DBClassGenerator`, `RecordMapperClassGenerator`, etc.) continue to run unchanged** — the flag adds new generators alongside, never removes existing ones in this phase.

### Existing codebase facts

The main orchestrator is `GraphQLGenerator.getGenerators()` which runs, in order: 5 DTO generators, `DBClassGenerator` (→ `{TypeName}DBQueries` in package `queries`), transformer + mapper generators, exception generators, `OperationClassGenerator`, `TypeResolverClassGenerator`, `WiringClassGenerator`. All live in `graphitron-codegen-parent/graphitron-java-codegen`.

**`GraphitronContext`** is at `no.sikt.graphql.GraphitronContext` (not in a `helpers.resolvers` sub-package — that sub-package does not exist). It has three methods:
- `getDslContext(DataFetchingEnvironment env)` — provides jOOQ `DSLContext`
- `getContextArgument(DataFetchingEnvironment env, String name)` — reads named values from `GraphQlContext`
- `getDataLoaderName(DataFetchingEnvironment env)` — generates DataLoader names (field+type based) **for the existing DTO-based resolvers only**

**`DefaultGraphitronContext`** is at `no.sikt.graphql.DefaultGraphitronContext`.

The real-world schema (Sakila example) uses a wide range of directives: `@table`, `@field`, `@splitQuery`, `@reference` (with multi-hop FK paths), `@condition`, `@orderBy`, `@defaultOrder`, `@asConnection` (Relay pagination), `@service`, `@externalField`, `@notGenerated`, `@tableMethod`, `@lookupKey`, `@node`, `@nodeId`, `@mutation`, `@error`, `@discriminate`, `@discriminator`, plus Apollo Federation directives. The new generators will NOT support all of these immediately — see Scope Boundaries.

### Schema traversal

`FieldsSpecBuilder` and `RuntimeWiringSpecBuilder` both operate on a `GraphQLSchema` (not `TypeDefinitionRegistry` AST directly). The schema is assembled once using the same pattern as `SchemaTransformer.assembleSchema()` in `graphitron-schema-transform`:

```java
RuntimeWiring runtimeWiring = EchoingWiringFactory.newEchoingWiring(wiring -> {
    typeDefinitionRegistry.scalars().forEach((name, v) -> {
        if (!ScalarInfo.isGraphqlSpecifiedScalar(name)) wiring.scalar(fakeScalar(name));
    });
});
GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);
```

Spec builders then use `new SchemaTraverser().depthFirstFullSchema(visitor, schema)` where the `GraphQLTypeVisitorStub` tracks parent type from `context.getParentNode()`. This gives fully resolved types and standard traversal order, replacing the custom ProcessedSchema iteration.

---

## Scope Boundaries

### In scope for initial implementation

| Feature | Notes |
|---|---|
| Simple scalar fields (`@table` + `@field`) | Direct `TABLE.COLUMN` mapping via `GraphitronFetcherFactory.field()` |
| Inline single-object nested fields (no `@reference`) | Simple FK inferred from schema metadata |
| Inline list nested fields (no `@reference`) | Same FK inference; rendered as `multiset()` |
| Inline fields with `@reference` (any path depth) | FK-derived via `onKey()` joins |
| `@splitQuery` fields | Separate DataLoader fetch; DataLoader registered on first use via `computeIfAbsent`; FK inferred or user-specified |
| `@condition` on fields/arguments | Via `<ConditionClassName>Wrapper` |
| `@service` on root (`Query`/`Mutation`) fields | Via `<ServiceClassName>Wrapper`; forwarded through root Fields method |
| `@notGenerated`, `@externalField` | Skipped (not wired) |
| Root DB fields with or without scalar arguments | All root fields use static method references (`QueryFields::fieldName`) |
| Feature flag `recordBasedOutput` in mojo + `GeneratorConfig` | Drives the entire new generator set |
| `@defaultOrder` on fields | Static ORDER BY at codegen time |
| `@orderBy` on arguments | Dynamic ORDER BY driven by GraphQL input argument |

### Deferred (out of scope for this phase)

| Feature | Reason |
|---|---|
| Relay connection types (`XxxConnection`, `XxxEdge`, `PageInfo`) | `@asConnection` is removed by the transformer; generator sees expanded Relay types. Requires ordering to be in place first (done). Planned approach: add `QueryHelper.getOrderByToken()` cursor column to SELECT, `.seek()` + `.limit(pageSize+1)`, wrap result in a `GraphitronConnection` record (non-generic — always wraps `Result<Record>`), add explicit RuntimeWiring DataFetchers for connection/edge/pageInfo types. `@splitQuery` connections return `Map<ParentRecord, GraphitronConnection>` from the DataLoader — study existing code carefully before implementing. |
| `@tableMethod` | Dynamic table lookup via external method |
| `@lookupKey` batch queries | Distinct query shape |
| `@node` / `node(id:)` query | Global Object Identification resolver |
| Mutations (`@mutation`) | Insert/update/delete/upsert flow differs fundamentally |
| TypeResolvers (interfaces/unions with `@discriminate`) | Runtime type resolution — separate concern |
| Apollo Federation entity resolvers | `@key` / `_entities` |
| `@service` on non-root type fields | Non-root `@service` fields are skipped (not wired) in this phase |
| `@externalField` / `FieldWrapperClassGenerator` | Same wrapper pattern as service/condition; deferred until non-root service fields are supported |
| Java record output types (`@record`) | Future feature, builds on this foundation |
| Path joins via `@reference(path: [{method: "..."}])` | `PathJoin` strategy in spec; deferred — all joins use `onKey()` in this phase |

---

## Package Structure

Existing generated code stays under `<outputPackage>` (e.g. `.queries`, `.model`, `.resolvers`, `.wiring`).

New record-based code goes under `<outputPackage>.record`:

| Subpackage | Contents |
|---|---|
| `<outputPackage>.record.fields` | `<TypeName>Fields` — one class per GraphQL output type and per root operation type; contains `select()`, DataFetcher methods, BatchLoader methods, service forwarding methods |
| `<outputPackage>.record.resolvers` | `<TypeName>RuntimeWiring`, `GraphitronWiring`; also `<ClassName>Wrapper` classes for service, condition (one per external class referenced in the schema) |

---

## Generator Overview

Build and test generators in dependency order — each step only references outputs from earlier steps.

| Step | Generator | Output file(s) | Key generated methods/classes | Depends on |
|---|---|---|---|---|
| 0 | Infrastructure *(not a generator)* | `GraphitronFetcherFactory.java` | `field()`, `nestedRecord()`, `nestedResult()` | — |
| 1 | `ConditionWrapperClassGenerator` | `<ConditionClassName>Wrapper.java` (one per external condition class) | one static method per `@condition` / `ReferenceElement.condition` usage | — |
| 2 | `ServiceWrapperClassGenerator` | `<ServiceClassName>Wrapper.java` (one per `@service` class) | one static method per `@service` field | — |
| 3 | `FieldsClassGenerator` | `<TypeName>Fields.java` (one per GraphQL type + root ops) | `select()` (non-root), private inline methods, `<fieldName>(DataFetchingEnvironment)` DataFetcher for each `@splitQuery`/`@service` field, private `<fieldName>Loader(List, BatchLoaderEnvironment)` BatchLoader for each `@splitQuery`; root op DataFetcher methods | Steps 1 + 2 |
| 4 | `RuntimeWiringClassGenerator` | `<TypeName>RuntimeWiring.java` (one per output type, including root ops) | `wiring()` → `TypeRuntimeWiring.Builder` | Step 3 |
| 5 | `GraphitronWiringClassGenerator` | `GraphitronWiring.java` | `getRuntimeWiringBuilder()` | Step 4 (all `*RuntimeWiring` classes) |
| 6 | Orchestration | *(modifies `GraphQLGenerator` only)* | — | Steps 0–5 |

**Note on wrapper naming**: `<ConditionClassName>Wrapper` and `<ServiceClassName>Wrapper` are named after the **external class** (e.g. `CustomerConditionsWrapper`, `HelloWorldServiceWrapper`), not after a GraphQL type. The same wrapper class is reused if multiple fields reference the same external class.

---

## Step 0: Infrastructure

Three foundational changes with no generator dependencies. Implement and test these before any generator step.

### `GraphitronFetcherFactory` (new runtime utility)

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

`fetcher()` and `service()` factory methods (and their functional interfaces `RootFetchFunction`, `LoaderFactory`, `ServiceFunction`) are **not present**. All root fields and DataLoader fields use static method references to generated `<TypeName>Fields` methods instead.

### `GraphitronContext` — add `getTenantId()`

**Existing methods are left untouched.** `getDataLoaderName(DataFetchingEnvironment env)` remains on the interface and is still used by the existing DTO-based resolvers. The new `record.*` generators do NOT call `getDataLoaderName()`.

**`GraphitronContext.java`** — add one new method:
```java
@NotNull Optional<String> getTenantId(DataFetchingEnvironment env);
```
`DefaultGraphitronContext` returns `Optional.empty()` (single-tenant default). Multi-tenant implementations return `Optional.of(tenantId)`.

The tenant ID is used in the generated `loaderName()` helper inside each `<TypeName>Fields` class to ensure DataLoader key isolation across tenants.

### Feature flag: `recordBasedOutput`

**`GeneratorConfig.java`**:
```java
private static boolean recordBasedOutput = false;
public static boolean recordBasedOutput() { return recordBasedOutput; }
public static void setRecordBasedOutput(boolean v) { recordBasedOutput = v; }
```
Load from mojo in `loadProperties()`: `recordBasedOutput = mojo.recordBasedOutput();`

**Maven plugin mojo** — add `@Parameter(property = "graphitron.recordBasedOutput", defaultValue = "false") protected boolean recordBasedOutput;`

---

## Step 2: `ServiceWrapperClassGenerator` (in `record.resolvers`)

Service code should have zero GraphQL dependencies so it can be reused from other API implementations. A generated wrapper class bridges the GraphQL layer to each service class.

### Design principles

- **One wrapper class per external service class**, named `<ServiceClassName>Wrapper` (e.g. `HelloWorldServiceWrapper`). Contains one `public static` method per `@service`-annotated field that uses that class.
- The class name is derived from the **external service class name**, not a GraphQL type name. Multiple fields across different types may reference the same external class and will share one wrapper.
- **Static and instance methods are both supported.** Detected via `Modifier.isStatic(method.getModifiers())` at code-generation time.
- **Arguments are matched by name**, not position. At code-generation time, service method parameters are resolved via reflection using `Parameter.getName()`. This requires the service class to be compiled with `javac -parameters`; the generator validates this and fails with a clear error if names are unavailable.
- **`DSLContext` parameters on static methods are matched by type**, not name — so the parameter can be named anything. At most one `DSLContext` parameter is allowed per method; two → error.
- **Context arguments** listed in `contextArguments: [...]` on the directive are extracted from `GraphitronContext` by name rather than by positional convention.
- **Service class has no GraphQL imports**. The wrapper handles all `DataFetchingEnvironment` access.

### `-parameters` validation (at code-generation time)

After resolving the service `Method` via reflection, call `parameter.isNamePresent()` on **all** parameters — including any `DSLContext` parameter on a static method. If any returns `false`, throw with:

> `Service class HelloWorldService must be compiled with 'javac -parameters'. Parameter names are not available on method helloWorldAgain(). Add <compilerArgs><arg>-parameters</arg></compilerArgs> to the maven-compiler-plugin for the module containing this service.`

### Overload resolution + argument matching

The expected non-DSLContext parameter set for a field is: all GraphQL arguments on the field, plus all entries in `contextArguments: [...]` on the directive.

For each candidate overload (methods whose simple name matches the directive's `method` or the field name):
1. If static: count parameters of type `DSLContext`. If > 1 → reject with an error immediately. If exactly 1, set aside as `DslContextParam`; remove it from further matching.
2. Reject if the remaining parameter count differs from the expected parameter set size
3. Reject if any remaining parameter name (from `-parameters`) doesn't map to a GraphQL argument name or a `contextArguments` entry
4. Reject if any remaining parameter **type** is incompatible with its matched argument type
5. Accept if all conditions pass

After filtering:
- **Exactly one match** → use it
- **Zero matches** → fail at code-generation time listing all overloads found and what was expected
- **Multiple matches** → fail with an ambiguity error listing the conflicting candidates

### Service instantiation (instance methods only)

Inspect the service class's constructors via reflection:
- If a `(DSLContext)` constructor exists → instantiate with `ctx.getDslContext(env)`
- If a no-arg constructor exists → instantiate with `new ServiceClass()`
- If neither → fail at code-generation time

### Generated `HelloWorldServiceWrapper` — examples

**Instance method with DSLContext constructor:**
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

**Static method without DSLContext:**
```java
public static CompletableFuture<HelloWorldRecord> greet(DataFetchingEnvironment env) {
    String name = env.getArgument("name");
    return CompletableFuture.supplyAsync(() -> HelloWorldService.greet(name));
}
```

**Static method with DSLContext parameter (matched by type, named anything):**
```java
public static CompletableFuture<HelloWorldRecord> greetWithDb(DataFetchingEnvironment env) {
    String name = env.getArgument("name");
    GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
    return CompletableFuture.supplyAsync(() -> HelloWorldService.greetWithDb(ctx.getDslContext(env), name));
}
```

The `ctx` variable is only emitted when needed.

### Spec types

```java
record ServiceWrapperSpec(
    String className,              // "HelloWorldServiceWrapper"
    String serviceClass,           // "no.sikt...HelloWorldService" (fully qualified)
    boolean serviceNeedsContext,   // true if (DSLContext) constructor exists; instance methods only
    List<ServiceMethodSpec> methods
) {}
record ServiceMethodSpec(
    String wrapperMethodName,      // "helloWorldAgain"
    String serviceMethodName,      // "helloWorldAgain"
    String returnType,             // "HelloWorldRecord"
    boolean isStatic,
    List<ServiceParamSpec> params  // in declaration order; includes DslContextParam if present
) {}
sealed interface ServiceParamSpec permits GraphQLArgParam, ContextParam, DslContextParam {}
record GraphQLArgParam(String paramName, String javaType) implements ServiceParamSpec {}
record ContextParam(String paramName, String contextKey, String javaType) implements ServiceParamSpec {}
record DslContextParam(String paramName) implements ServiceParamSpec {}
```

### New files

| File | Purpose |
|------|---------|
| `generators/record/spec/ServiceWrapperSpec.java` | Spec types above |
| `generators/record/ServiceWrapperSpecBuilder.java` | Reflection-based: schema + Method → `ServiceWrapperSpec`; validates `-parameters` and name-matches params |
| `generators/record/ServiceWrapperCodeGenerator.java` | `ServiceWrapperSpec` → `TypeSpec` |
| `generators/record/ServiceWrapperClassGenerator.java` | Coordinator; one class per distinct service class referenced in the schema |

---

## Step 1: `ConditionWrapperClassGenerator`

`@reference` and `@condition` both reference external condition methods. These are handled by a single wrapper pattern, identical to `ServiceWrapper`: one generated `<ConditionClassName>Wrapper` class per distinct external condition class referenced anywhere in the schema, containing one method per referenced condition method.

Sources that contribute methods to the wrapper:
- `@condition(condition: {className: "X", method: "m"})` on any field or argument
- `condition: {className: "X", method: "m"}` on any `ReferenceElement` within a `@reference` path

Argument mapping rules extend the `ServiceWrapper` pattern: parameters matched by name to GraphQL args; `DSLContext` matched by type; `contextArguments` extracted from `GraphitronContext`; requires `-parameters` compilation. **Additionally**, when the condition is on a `ReferenceElement`, the external method may declare typed table parameters — these are matched by jOOQ table type and injected as the path-navigated or aliased table instances. `override` is **not** a property of the wrapper method — it is recorded in the `ConditionMethodSpec` and consumed by `FieldsCodeGenerator` when deciding whether to AND or replace the generated WHERE.

```java
// Generated: CustomerConditionsWrapper.java
public class CustomerConditionsWrapper {
    public static Condition activeCustomers(DSLContext ctx) {
        return CustomerConditions.activeCustomers(ctx);
    }
    public static Condition inactiveCustomers(DSLContext ctx, String lastNameStartingWith) {
        return CustomerConditions.inactiveCustomers(ctx, lastNameStartingWith);
    }
}

// Generated: FilmLanguageConditionsWrapper.java
public class FilmLanguageConditionsWrapper {
    public static Condition selectedLanguage(Film film, Language language, String languageId) {
        return FilmLanguageConditions.selectedLanguage(film, language, languageId);
    }
}
```

The `Fields` class calls the wrapper:
```java
// override: true → wrapper result replaces generated WHERE
.where(CustomerConditionsWrapper.activeCustomers(ctx))

// override: false (default) → wrapper result AND-ed with generated WHERE
.where(generated.and(CustomerConditionsWrapper.inactiveCustomers(ctx, lastNameStartingWith)))
```

**Join path execution** (the non-condition part of `@reference`) is generated inline in `Fields`. All join conditions are derived from jOOQ FK metadata at codegen time via `onKey()`. No Java reflection on generated table classes is used.

### New files

| File | Purpose |
|------|---------|
| `generators/record/spec/ConditionWrapperSpec.java` | `ConditionWrapperSpec`, `ConditionMethodSpec` (reuses `ServiceParamSpec` hierarchy; adds `boolean override`) |
| `generators/record/ConditionWrapperSpecBuilder.java` | Schema-wide scan for all `@condition` usages and `ReferenceElement.condition` fields; groups by external class name |
| `generators/record/ConditionWrapperCodeGenerator.java` | `ConditionWrapperSpec` → `TypeSpec` |
| `generators/record/ConditionWrapperClassGenerator.java` | Coordinator; one class per distinct external condition class in the schema |

---

## Step 3: `FieldsClassGenerator`

One class is generated per GraphQL output type. `<TypeName>Fields` is the single source of truth for everything related to data fetching for that type.

**Responsibilities by type category:**

- **Root operation type** (`Query`, `Mutation`): One DataFetcher static method per field. DB-backed fields contain the jOOQ query inline. Service-backed fields call through to the appropriate `<ServiceClassName>Wrapper` method.
- **Non-root type** (`Customer`, `Film`, etc.): The `select()` method builds the SELECT list (scalars + inline nested fields). Private methods handle each inline nested field. DataFetcher static methods are generated for each `@splitQuery` field (registers DataLoader on first use via `computeIfAbsent`). Private BatchLoader static methods handle the actual batch SQL.

### Generated method signatures

All DataFetcher methods take `DataFetchingEnvironment env` and are used as static method references in the wiring:

```java
// Root DB field — wired as QueryFields::customers
public static CompletableFuture<Result<Record>> customers(DataFetchingEnvironment env) { ... }

// Root service field — calls wrapper; wired as QueryFields::helloWorldAgain
public static CompletableFuture<HelloWorldRecord> helloWorldAgain(DataFetchingEnvironment env) {
    return HelloWorldServiceWrapper.helloWorldAgain(env);
}

// Non-root splitQuery DataFetcher — wired as CustomerFields::payments
public static CompletableFuture<Result<Record>> payments(DataFetchingEnvironment env) { ... }

// Non-root splitQuery BatchLoader — registered via computeIfAbsent
private static CompletableFuture<Map<CustomerRecord, Result<Record>>> paymentsLoader(
        List<CustomerRecord> keys, BatchLoaderEnvironment ctx) { ... }
```

### FK auto-inference and resolution

Every inline nested field and `@splitQuery` field requires a join condition. Two sources:

**Auto-inferred** (no `@reference`, or a `{table: "X"}` hop):
- Enumerate all jOOQ `ForeignKey` objects between the two tables from schema metadata
- **Exactly one FK found**: record the FK constant name as an `OnKeyJoin`
- **Zero or multiple FKs found**: fail at codegen time with a clear error

**User-specified key** (`@reference(path: {key: "FK_NAME"})`):
- Use the key name directly: `Keys.FK_NAME`

Both produce an `OnKeyJoin` in the spec. Generated join: `from(CHILD_TABLE).join(parent).onKey(Keys.FK_CONSTANT)`.

### Root operation class — `QueryFields`

Root field DataFetcher methods contain the jOOQ query inline. Arguments are extracted from `env` inside the method:

```java
public class QueryFields {

    // Argument-free root field
    public static CompletableFuture<Result<Record>> films(DataFetchingEnvironment env) {
        GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
        DataFetchingFieldSelectionSet select = env.getSelectionSet();
        return CompletableFuture.supplyAsync(() -> {
            var _a = FILM.as("film_hash");
            return ctx.getDslContext(env).select(FilmFields.select(_a, select))
                .from(_a)
                .fetch();
        });
    }

    // Root field with arguments — extracted from env inside the method
    public static CompletableFuture<Record> customer(DataFetchingEnvironment env) {
        GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
        String id = env.getArgument("id");
        DataFetchingFieldSelectionSet select = env.getSelectionSet();
        return CompletableFuture.supplyAsync(() -> {
            var _a = CUSTOMER.as("customer_hash");
            return ctx.getDslContext(env).select(CustomerFields.select(_a, select))
                .from(_a)
                .where(_a.CUSTOMER_ID.eq(UInteger.valueOf(id)))
                .fetchOne();
        });
    }

    // Root service field — delegates to wrapper
    public static CompletableFuture<HelloWorldRecord> helloWorldAgain(DataFetchingEnvironment env) {
        return HelloWorldServiceWrapper.helloWorldAgain(env);
    }
}
```

`QueryFields` has **no `select()` method** — it only contains DataFetcher methods.

Note: `QueryFields.customer()` contains no field names from `Customer` — it only knows the table name and structural query. `CustomerFields.select()` owns the SELECT list.

> **`@condition` on root fields**: `QueryFields` calls `<ConditionClassName>Wrapper.<method>(ctx[, args...])` in the WHERE clause. `override: true` replaces the generated WHERE; `override: false` ANDs with it.

> **`@orderBy`, `@defaultOrder` on root fields**: See the Ordering section below.

### Type-owned class — `CustomerFields`

```java
public class CustomerFields {

    // Called from QueryFields.customers() — returns the SELECT list
    public static List<SelectField<?>> select(Customer customer, DataFetchingFieldSelectionSet select) {
        var fields = new ArrayList<SelectField<?>>();
        // Scalar fields — always included
        fields.add(customer.CUSTOMER_ID);
        fields.add(customer.FIRST_NAME);
        fields.add(customer.EMAIL_ADDRESS);
        // Inline nested fields — conditionally included based on sub-selection
        if (select.contains("payments")) {
            fields.add(payments(customer, select.getFields("payments").get(0).getSelectionSet()));
        }
        if (select.contains("address")) {
            fields.add(address(customer, select.getFields("address").get(0).getSelectionSet()));
        }
        return fields;
    }

    // Nested list (one-to-many) → multiset with onKey() correlated join
    private static Field<Result<Record>> payments(Customer customer, DataFetchingFieldSelectionSet select) {
        return DSL.multiset(
            DSL.select(PaymentFields.select(PAYMENT, select))
                .from(PAYMENT)
                .join(customer).onKey(Keys.PAYMENT__PAYMENT_CUSTOMER_ID_FKEY)
        ).as("payments");
    }

    // Nested single (many-to-one) → correlated field() with onKey() join
    private static Field<Record> address(Customer customer, DataFetchingFieldSelectionSet select) {
        return DSL.field(
            DSL.select(AddressFields.select(ADDRESS, select))
                .from(ADDRESS)
                .join(customer).onKey(Keys.CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
        ).as("address");
    }

    // splitQuery DataFetcher — DataLoader registered on first use
    public static CompletableFuture<Result<Record>> orders(DataFetchingEnvironment env) {
        ResultPath path = env.getExecutionStepInfo().getPath();
        GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
        String name = loaderName(path, ctx.getTenantId(env));
        DataLoader<CustomerRecord, Result<Record>> loader = env.getDataLoaderRegistry()
            .computeIfAbsent(name, k -> DataLoaderFactory.newMappedDataLoaderWithContext(
                CustomerFields::ordersLoader));
        return loader.load(((Record) env.getSource()).into(CUSTOMER), env);
    }

    private static CompletableFuture<Map<CustomerRecord, Result<Record>>> ordersLoader(
            List<CustomerRecord> keys, BatchLoaderEnvironment ctx) {
        DataFetchingEnvironment env = (DataFetchingEnvironment) ctx.getKeyContextsList().get(0);
        GraphitronContext gCtx = env.getGraphQlContext().get("graphitronContext");
        DataFetchingFieldSelectionSet select = env.getSelectionSet();
        return CompletableFuture.supplyAsync(() -> {
            Order _a = ORDER.as("order_hash");
            return gCtx.getDslContext(env)
                .select(OrderFields.select(_a, select))
                .from(_a)
                .where(_a.CUSTOMER_ID.in(keys.stream().map(CustomerRecord::getCustomerId).toList()))
                .fetch()
                .stream()
                .collect(Collectors.groupingBy(r -> r.into(CUSTOMER)));
        });
    }

    private static String loaderName(ResultPath path, Optional<String> tenantId) {
        String normalizedPath = path.toList().stream()
            .filter(seg -> !(seg instanceof Integer))
            .map(Object::toString)
            .collect(Collectors.joining("/"));
        return tenantId.map(id -> id + "/" + normalizedPath).orElse(normalizedPath);
    }
}
```

Key rules for `select()`:
- **Scalar fields**: always added, no selection guard.
- **Inline nested fields**: guarded by `select.contains(fieldName)`.
- **Join strategy**: always `onKey(Keys.FK_CONSTANT)` — jOOQ generates the FK predicate. No manual `col = col` conditions.
- **`@splitQuery` fields**: not included in `select()` — fetched via DataLoader.
- **`@condition`**: appended as `.where(ConditionWrapper.method(...))` after the join.

### `@splitQuery` batch query with `@reference` multi-hop

```java
// In FilmFields — @splitQuery + @reference multi-hop
public static Map<FilmRecord, Result<Record>> storesThatHaveThisFilmLoader(
        List<FilmRecord> keys, BatchLoaderEnvironment ctx) {
    DataFetchingEnvironment env = (DataFetchingEnvironment) ctx.getKeyContextsList().get(0);
    GraphitronContext gCtx = env.getGraphQlContext().get("graphitronContext");
    Film _film = FILM.as("film_hash");
    Inventory _inv = INVENTORY.as("inv_hash");
    Store _s = STORE.as("store_hash");
    return CompletableFuture.supplyAsync(() ->
        gCtx.getDslContext(env)
            .select(StoreFields.select(_s, env.getSelectionSet()))
            .from(_film)
            .join(_inv).onKey(Keys.INVENTORY__INVENTORY_FILM_ID_FKEY)
            .join(_s).onKey(Keys.INVENTORY__INVENTORY_STORE_ID_FKEY)
            .where(_film.FILM_ID.in(keys.stream().map(FilmRecord::getFilmId).toList()))
            .fetch()
            .stream()
            .collect(Collectors.groupingBy(r -> r.into(FILM)))
    );
}
```

### Ordering — `@defaultOrder` and `@orderBy` (part of Step 3)

Ordering must be generated before connection/pagination support. Both directives resolve to a `SortField<?>[]` at some point — either fully at codegen time (`@defaultOrder`) or as a runtime switch statement (`@orderBy`).

### Three modes

| Mode | Source | Generated code |
|---|---|---|
| `index` | `@defaultOrder(index: "IDX_TITLE")` | `QueryHelper.getSortFields(_a, "IDX_TITLE", "ASC")` |
| `fields` | `@defaultOrder(fields: [{name: "LAST_NAME", collate: "xdanish_ai"}])` | `new SortField[]{_a.LAST_NAME.collate("xdanish_ai").sort(SortOrder.ASC)}` |
| `primaryKey` | `@defaultOrder(primaryKey: true)` | `_a.getPrimaryKey().getFieldsArray()` (mapped with sort direction) |

### `@defaultOrder` only

Static ORDER BY baked into the method body:

```java
public static CompletableFuture<Result<Record>> films(DataFetchingEnvironment env) {
    GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
    DataFetchingFieldSelectionSet select = env.getSelectionSet();
    return CompletableFuture.supplyAsync(() -> {
        var _a = FILM.as("film_hash");
        var orderFields = QueryHelper.getSortFields(_a, "IDX_TITLE", "ASC");
        return ctx.getDslContext(env)
            .select(FilmFields.select(_a, select))
            .from(_a)
            .orderBy(orderFields)
            .fetch();
    });
}
```

No extra argument — the field remains argument-free for ordering purposes, so the method reference `QueryFields::films` is still clean.

### `@orderBy` argument (with optional `@defaultOrder` fallback)

Dynamic ORDER BY — generates a null-check ternary + switch statement. Arguments are extracted from `env` inside the method:

```java
public static CompletableFuture<Result<Record>> films(DataFetchingEnvironment env) {
    GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
    FilmsOrderByInput orderBy = env.getArgument("orderBy");  // null if not supplied
    DataFetchingFieldSelectionSet select = env.getSelectionSet();
    return CompletableFuture.supplyAsync(() -> {
        var _a = FILM.as("film_hash");
        var orderFields = orderBy == null
            ? QueryHelper.getSortFields(_a, "IDX_TITLE", "ASC")   // @defaultOrder fallback
            : switch (orderBy.getOrderByField().toString()) {
                case "TITLE"    -> QueryHelper.getSortFields(_a, "IDX_TITLE",
                                       orderBy.getDirection().toString());
                case "LANGUAGE" -> QueryHelper.getSortFields(_a, "IDX_FK_LANGUAGE_ID",
                                       orderBy.getDirection().toString());
                case "LAST_NAME" -> new SortField[]{
                    _a.LAST_NAME.sort(
                        orderBy.getDirection().toString().equalsIgnoreCase("ASC")
                            ? SortOrder.ASC : SortOrder.DESC)
                };
                case "ID" -> _a.getPrimaryKey().getFieldsArray();
                default -> throw new IllegalArgumentException(
                    "Unknown orderBy: " + orderBy.getOrderByField());
            };
        return ctx.getDslContext(env)
            .select(FilmFields.select(_a, select))
            .from(_a)
            .orderBy(orderFields)
            .fetch();
    });
}
```

When there is no `@defaultOrder` and `orderBy` is null, falls back to primary key ordering (fails at codegen if no PK exists).

### Ordering on `@splitQuery` fields

`@defaultOrder` and `@orderBy` can appear on non-root `@splitQuery` fields. The generated BatchLoader method extracts the `orderBy` argument from the `DataFetchingEnvironment` obtained from `BatchLoaderEnvironment`:

```java
private static CompletableFuture<Map<CustomerRecord, Result<Record>>> paymentsLoader(
        List<CustomerRecord> keys, BatchLoaderEnvironment ctx) {
    DataFetchingEnvironment env = (DataFetchingEnvironment) ctx.getKeyContextsList().get(0);
    GraphitronContext gCtx = env.getGraphQlContext().get("graphitronContext");
    PaymentsOrderByInput orderBy = env.getArgument("orderBy");
    // ... orderFields switch as above, then query with .orderBy(orderFields)
}
```

### Spec types for `FieldsClassGenerator`

```java
// Root operation type (Query/Mutation)
record RootFieldsSpec(
    String className,                     // "QueryFields"
    String graphqlType,                   // "Query"
    List<RootFieldMethodSpec> methods
) {}

record RootFieldMethodSpec(
    String methodName,                    // "customers" (GraphQL field name)
    String typeFieldsClass,               // "CustomerFields"
    String jooqTableClass,                // "Customer" (concrete jOOQ table class)
    String tableAlias,                    // hash-based stable alias
    List<GraphQLArgumentSpec> arguments,  // explicit params from GraphQL field args
    boolean isList,                       // true → fetch(), false → fetchOne()
    OrderingSpec ordering,                // null if no ordering directives
    boolean isServiceField,               // true → delegates to <ServiceClassName>Wrapper
    String serviceWrapperClass,           // non-null when isServiceField=true
    String serviceWrapperMethod           // non-null when isServiceField=true
) {}

// One entry per GraphQL argument on the root field (excluding the @orderBy arg, in OrderingSpec)
record GraphQLArgumentSpec(String paramName, String javaType) {}

// Non-root type
record TypeFieldsSpec(
    String className,                     // "CustomerFields"
    String graphqlType,                   // "Customer"
    String jooqTableClass,                // "Customer"
    String jooqTableParam,                // "customer"
    List<ScalarFieldSpec> scalars,
    List<InlineFieldSpec> inlines,
    List<SplitQueryFieldSpec> splitQueries
) {}

record ScalarFieldSpec(String column) {}  // e.g. "CUSTOMER_ID"

record InlineFieldSpec(
    String graphqlField,
    String childJooqTableClass,
    String childFieldsClass,
    boolean isList,
    List<HopSpec> hops,
    String conditionWrapperClass,         // null unless @condition on this field
    String conditionWrapperMethod         // null unless @condition on this field
) {}

record SplitQueryFieldSpec(
    String fieldName,                     // "orders" — used for both DataFetcher and loader method names
    String childJooqTableClass,           // "Order"
    String childFieldsClass,              // "OrderFields"
    String keyRecord,                     // "CustomerRecord"
    String keyColumn,                     // FK column for WHERE IN filter
    List<HopSpec> hops,
    OrderingSpec ordering
) {}

// Ordering spec
record OrderingSpec(DefaultOrderSpec defaultOrder, OrderByArgSpec orderByArg) {}
record DefaultOrderSpec(SortMode mode, String indexName, List<FieldSortSpec> fields, String direction) {}
record OrderByArgSpec(String argName, String javaType, List<OrderByCase> cases) {}
record OrderByCase(String enumLabel, SortMode mode, String indexName, List<FieldSortSpec> fields) {}
enum SortMode { INDEX, FIELDS, PRIMARY_KEY }
record FieldSortSpec(String columnName, String collation) {}

// One hop in the join chain (shared with RuntimeWiringSpec)
record HopSpec(
    HopJoinStrategy strategy,
    String intermediateTableClass,        // non-null for intermediate hops only
    String intermediateTableAlias,
    String conditionWrapperClass,         // null unless ReferenceElement has a condition
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
| `generators/record/FieldsSpecBuilder.java` | `GraphQLSchema` → `RootFieldsSpec` / `TypeFieldsSpec`; uses `SchemaTraverser`; performs FK auto-inference from jOOQ metadata; builds `OrderingSpec`; validates `@condition` and `@reference` paths |
| `generators/record/FieldsCodeGenerator.java` | `RootFieldsSpec` / `TypeFieldsSpec` → `TypeSpec` |
| `generators/record/FieldsClassGenerator.java` | Coordinator; one class per GraphQL output type |

---

## Step 4: `RuntimeWiringClassGenerator`

One `<TypeName>RuntimeWiring` class per GraphQL output type, including root operation types (`Query`, `Mutation`). A single generator handles all types — root types are detected by the presence of `isRootType = true` in the spec.

For root types, all wiring entries are `FieldsMethodEntry` (method references to `QueryFields`/`MutationFields`).

For non-root types: scalar fields use `GraphitronFetcherFactory.field()`, inline fields use `nestedResult()`/`nestedRecord()`, and DataLoader/service fields use `FieldsMethodEntry`.

### Generated `QueryRuntimeWiring` (root type):

```java
public class QueryRuntimeWiring {
    public static TypeRuntimeWiring.Builder wiring() {
        return TypeRuntimeWiring.newTypeWiring("Query")
            .dataFetcher("films", QueryFields::films)
            .dataFetcher("customer", QueryFields::customer)
            .dataFetcher("helloWorldAgain", QueryFields::helloWorldAgain);
    }
}
```

No lambdas, no factory calls — just method references.

### Generated `CustomerRuntimeWiring` (non-root type):

```java
public class CustomerRuntimeWiring {
    public static TypeRuntimeWiring.Builder wiring() {
        return TypeRuntimeWiring.newTypeWiring("Customer")
            .dataFetcher("id",       GraphitronFetcherFactory.field(CUSTOMER.CUSTOMER_ID))
            .dataFetcher("email",    GraphitronFetcherFactory.field(CUSTOMER.EMAIL_ADDRESS))
            .dataFetcher("payments", GraphitronFetcherFactory.nestedResult("payments"))
            .dataFetcher("address",  GraphitronFetcherFactory.nestedRecord("address"))
            .dataFetcher("orders",   CustomerFields::orders);
    }
}
```

### Field categorization

1. `@notGenerated` or `@externalField` → skip (not wired)
2. Root DB field → `FieldsMethodEntry` pointing to root Fields method
3. Root `@service` field → `FieldsMethodEntry` pointing to root Fields method (which delegates to wrapper)
4. Non-root `@splitQuery` → `FieldsMethodEntry` pointing to `<TypeName>Fields::<fieldName>`
5. Non-root `@service` → **deferred** (skipped in this phase)
6. Inline list → `GraphitronFetcherFactory.nestedResult("alias")`
7. Inline single → `GraphitronFetcherFactory.nestedRecord("alias")`
8. Simple scalar → `GraphitronFetcherFactory.field(TABLE.COLUMN)` (respects `@field(name:)`)

Note: `@reference` and `@condition` affect the **Fields class** (join/WHERE generation), not the RuntimeWiring entry. A `@reference`-annotated inline field still uses `nestedRecord`/`nestedResult` — the source `Record` contains the expected nested data because `Fields.select()` fetched it with the correct join.

### Spec types

```java
record RuntimeWiringSpec(
    String graphqlType,
    String className,
    boolean isRootType,
    List<FieldWiringEntry> fields
) {}

sealed interface FieldWiringEntry permits
        JooqFieldEntry, InlineResultEntry, InlineRecordEntry, FieldsMethodEntry {
    String graphqlField();
}
// Scalar field — GraphitronFetcherFactory.field(TABLE.COLUMN)
record JooqFieldEntry(String graphqlField, String jooqTable, String jooqColumn) implements FieldWiringEntry {}
// Inline list — GraphitronFetcherFactory.nestedResult("alias")
record InlineResultEntry(String graphqlField, String alias) implements FieldWiringEntry {}
// Inline single — GraphitronFetcherFactory.nestedRecord("alias")
record InlineRecordEntry(String graphqlField, String alias) implements FieldWiringEntry {}
// Method reference to <TypeName>Fields::<methodName> — covers root DB, root service, splitQuery
record FieldsMethodEntry(String graphqlField, String fieldsClass, String methodName) implements FieldWiringEntry {}
```

### New files

| File | Purpose |
|------|---------|
| `generators/record/spec/RuntimeWiringSpec.java` | `RuntimeWiringSpec`, sealed `FieldWiringEntry` hierarchy |
| `generators/record/RuntimeWiringSpecBuilder.java` | `GraphQLSchema` → `RuntimeWiringSpec`; uses `SchemaTraverser`; applies field categorization rules |
| `generators/record/RuntimeWiringCodeGenerator.java` | `RuntimeWiringSpec` → `TypeSpec` |
| `generators/record/RuntimeWiringClassGenerator.java` | Coordinator; one class per output type |

---

## Step 5: `GraphitronWiringClassGenerator`

**`GraphitronWiring`** (formerly `RecordWiring`) is the runtime entry point for the application. It aggregates all generated `*RuntimeWiring.wiring()` calls into a single `getRuntimeWiringBuilder()`.

This class is the bridge between generated code and the application's graphql-java setup. The developer calls `GraphitronWiring.getRuntimeWiringBuilder()` to obtain the `RuntimeWiring.Builder` to pass to `SchemaGenerator.makeExecutableSchema()`.

DataLoader registration is handled by the generated `<TypeName>Fields` DataFetcher methods themselves (via `computeIfAbsent` on first use). `GraphitronWiring` has no `configureDataLoaders()` method.

```java
public class GraphitronWiring {
    public static RuntimeWiring.Builder getRuntimeWiringBuilder() {
        return RuntimeWiring.newRuntimeWiring()
            .type(QueryRuntimeWiring.wiring())
            .type(CustomerRuntimeWiring.wiring())
            .type(FilmRuntimeWiring.wiring())
            // ... all generated types
            ;
    }
}
```

`GraphitronWiringClassGenerator` collects all `RuntimeWiringClassGenerator` outputs and registers them. It has no spec layer — it only needs the list of generated type names.

### New files

| File | Purpose |
|------|---------|
| `generators/record/GraphitronWiringClassGenerator.java` | Coordinator; generates `GraphitronWiring.java` |

---

## Step 6: Orchestration

**`GraphQLGenerator.getGenerators()`** — when `recordBasedOutput = true`, **add** to the generator list (in dependency order):

```
ConditionWrapperClassGenerator   → record.resolvers/<ConditionClassName>Wrapper
ServiceWrapperClassGenerator     → record.resolvers/<ServiceClassName>Wrapper
FieldsClassGenerator             → record.fields/<TypeName>Fields
RuntimeWiringClassGenerator      → record.resolvers/<TypeName>RuntimeWiring
GraphitronWiringClassGenerator   → record.resolvers/GraphitronWiring
```

Existing generators (`TypeDTOGenerator`, `RecordMapperClassGenerator`, `DBClassGenerator`, etc.) continue to run unchanged.

---

## Critical Files to Modify/Create

| File | Change |
|------|--------|
| `graphitron-common/src/main/java/no/sikt/graphql/GraphitronContext.java` | Add `getTenantId()` — existing methods unchanged |
| `graphitron-common/src/main/java/no/sikt/graphql/DefaultGraphitronContext.java` | Implement `getTenantId()` returning `Optional.empty()` |
| `graphitron-common/src/main/java/no/sikt/graphql/GraphitronFetcherFactory.java` | **New** — `field()`, `nestedRecord()`, `nestedResult()` with javadoc |
| `graphitron-java-codegen/.../configuration/GeneratorConfig.java` | Add `recordBasedOutput` static flag |
| `graphitron-java-codegen/.../generate/Generator.java` | Add `boolean recordBasedOutput()` |
| `graphitron-maven-plugin/.../mojo/GenerateMojo.java` | Add `@Parameter recordBasedOutput` |
| `graphitron-java-codegen/.../generate/GraphQLGenerator.java` | Add new generators when flag is set |
| `graphitron-java-codegen/.../mappings/JavaPoetClassName.java` | Add `JOOQ_RECORD`, `JOOQ_RESULT`, `LIGHT_DATA_FETCHER`, `GRAPHITRON_FETCHER_FACTORY` |
| `graphitron-java-codegen/.../generators/record/spec/RuntimeWiringSpec.java` | **New** — sealed types + `FieldWiringEntry` variants |
| `graphitron-java-codegen/.../generators/record/spec/FieldsSpec.java` | **New** — `RootFieldsSpec`, `TypeFieldsSpec`, `RootFieldMethodSpec`, `SplitQueryFieldSpec`, `InlineFieldSpec`, `ScalarFieldSpec`, `HopSpec`, `HopJoinStrategy`, `OrderingSpec`, `DefaultOrderSpec`, `OrderByArgSpec`, `OrderByCase`, `SortMode`, `FieldSortSpec`, `GraphQLArgumentSpec` |
| `graphitron-java-codegen/.../generators/record/RuntimeWiringSpecBuilder.java` | **New** — `GraphQLSchema` → `RuntimeWiringSpec` via `SchemaTraverser` |
| `graphitron-java-codegen/.../generators/record/RuntimeWiringCodeGenerator.java` | **New** — `RuntimeWiringSpec` → TypeSpec |
| `graphitron-java-codegen/.../generators/record/RuntimeWiringClassGenerator.java` | **New** — coordinator; all output types including root ops |
| `graphitron-java-codegen/.../generators/record/GraphitronWiringClassGenerator.java` | **New** — top-level `GraphitronWiring` entry point |
| `graphitron-java-codegen/.../generators/record/FieldsSpecBuilder.java` | **New** — `GraphQLSchema` → `RootFieldsSpec`/`TypeFieldsSpec` via `SchemaTraverser`; FK inference; ordering spec |
| `graphitron-java-codegen/.../generators/record/FieldsCodeGenerator.java` | **New** — `RootFieldsSpec`/`TypeFieldsSpec` → TypeSpec |
| `graphitron-java-codegen/.../generators/record/FieldsClassGenerator.java` | **New** — coordinator; one class per output type |
| `graphitron-java-codegen/.../generators/record/spec/ServiceWrapperSpec.java` | **New** |
| `graphitron-java-codegen/.../generators/record/ServiceWrapperSpecBuilder.java` | **New** |
| `graphitron-java-codegen/.../generators/record/ServiceWrapperCodeGenerator.java` | **New** |
| `graphitron-java-codegen/.../generators/record/ServiceWrapperClassGenerator.java` | **New** |
| `graphitron-java-codegen/.../generators/record/spec/ConditionWrapperSpec.java` | **New** |
| `graphitron-java-codegen/.../generators/record/ConditionWrapperSpecBuilder.java` | **New** |
| `graphitron-java-codegen/.../generators/record/ConditionWrapperCodeGenerator.java` | **New** |
| `graphitron-java-codegen/.../generators/record/ConditionWrapperClassGenerator.java` | **New** |

---

## Generator Architecture: Spec Layer

The root problem with existing generators is that schema-analysis decisions and JavaPoet calls are interleaved in a single `generate()` method, making tests brittle.

Each new generator in `generators/record/` is split into **two pure functions** plus a thin coordinator:

```
GraphQLSchema
  │
  ▼  <TypeName>SpecBuilder (schema logic, zero JavaPoet imports; uses SchemaTraverser)
  │
  ▼  <TypeName>Spec (plain Java records / sealed interfaces)
  │
  ▼  <TypeName>CodeGenerator (trivial JavaPoet mapping, zero schema logic)
  │
  ▼  TypeSpec → .java file
```

The `<TypeName>ClassGenerator` coordinator:
```java
public class RuntimeWiringClassGenerator extends AbstractSchemaClassGenerator {
    @Override
    public TypeSpec generate(GraphQLObjectType type) {
        RuntimeWiringSpec spec = RuntimeWiringSpecBuilder.build(type, schema);
        return RuntimeWiringCodeGenerator.generate(spec);
    }
}
```

### Condition wrapper spec types

```java
record ConditionWrapperSpec(String className, List<ConditionMethodSpec> methods) {}
record ConditionMethodSpec(
    String methodName,
    String externalClass,
    String externalMethod,
    boolean override,
    List<ConditionParamSpec> params
) {}
sealed interface ConditionParamSpec
    permits GraphQLArgParam, ContextParam, DslContextParam, ParentTableParam, ChildTableParam {}
record GraphQLArgParam(String paramName, String javaType) implements ConditionParamSpec {}
record ContextParam(String paramName, String contextKey, String javaType) implements ConditionParamSpec {}
record DslContextParam(String paramName) implements ConditionParamSpec {}
record ParentTableParam(String paramName, String jooqTableClass) implements ConditionParamSpec {}
record ChildTableParam(String paramName, String jooqTableClass) implements ConditionParamSpec {}
```

---

## Testing Strategy

Two independent levels of tests, each simple to write.

### Level 1 — SpecBuilder tests (AssertJ, no string comparison)

```java
// RuntimeWiringSpecBuilderTest.java
@Test
void customerWithSplitQuery() {
    // Build GraphQLSchema from inline schema string (using SchemaTransformer.assembleSchema pattern)
    GraphQLSchema schema = assembleSchema("""
        type Customer @table { id: ID!, email: String, orders: [Order] @splitQuery }
        type Order @table { id: ID! }
        type Query { customers: [Customer] }
        """);
    RuntimeWiringSpec spec = RuntimeWiringSpecBuilder.build(
        (GraphQLObjectType) schema.getType("Customer"), schema);

    assertThat(spec.graphqlType()).isEqualTo("Customer");
    assertThat(spec.fields()).containsExactly(
        new JooqFieldEntry("id", "CUSTOMER", "CUSTOMER_ID"),
        new JooqFieldEntry("email", "CUSTOMER", "EMAIL_ADDRESS"),
        new FieldsMethodEntry("orders", "CustomerFields", "orders")
    );
}
```

These tests exercise all categorization logic as **data assertions**, not string matching.

### Level 2 — CodeGenerator tests (approval, hand-crafted Spec as input)

```java
// RuntimeWiringCodeGeneratorTest.java
@Test
void customerRuntimeWiring() {
    var spec = new RuntimeWiringSpec("Customer", "CustomerRuntimeWiring", false, List.of(
        new JooqFieldEntry("id", "CUSTOMER", "CUSTOMER_ID"),
        new JooqFieldEntry("email", "CUSTOMER", "EMAIL_ADDRESS"),
        new InlineResultEntry("payments", "payments"),
        new InlineRecordEntry("address", "address"),
        new FieldsMethodEntry("orders", "CustomerFields", "orders")
    ));
    assertGeneratedContentMatches(RuntimeWiringCodeGenerator.generate(spec), "CustomerRuntimeWiring");
}
```

The input is a hand-crafted `Spec` — focused on code shape only.

### Level 3 — Integration (sparse, schema → Java string)

Only for a small set of end-to-end scenarios. Reuses existing `assertGeneratedContentMatches(schema, expectedFile)` infrastructure unchanged.

### Test file layout

```
src/test/java/.../record/
  spec/
    RuntimeWiringSpecBuilderTest.java
    FieldsSpecBuilderTest.java
  codegen/
    RuntimeWiringCodeGeneratorTest.java
    FieldsCodeGeneratorTest.java
  integration/
    RecordOutputIntegrationTest.java

src/test/resources/record/
  codegen/CustomerRuntimeWiring.java
  codegen/QueryRuntimeWiring.java
  codegen/CustomerFields.java
  codegen/QueryFields.java
```

```bash
mvn test -pl :graphitron-java-codegen
mvn test -pl :graphitron-common
mise r build-all
```
