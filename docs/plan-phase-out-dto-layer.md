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

---

## Scope Boundaries

### In scope for initial implementation

| Feature | Notes |
|---|---|
| Simple scalar fields (`@table` + `@field`) | Direct `TABLE.COLUMN` mapping in `field()` fetchers |
| Inline single-object nested fields (no `@reference`) | Simple FK inferred from shared column name on both tables |
| Inline list nested fields (no `@reference`) | Same FK inference; rendered as `multiset()` |
| Inline fields with `@reference` (any path depth) | Via `<TypeName>ReferenceWrapper` — jOOQ path-based joins |
| `@splitQuery` fields | Separate DataLoader fetch; FK inferred, single-key, or multi-hop via `ReferenceWrapper` |
| `@condition` on fields/arguments | Via `<TypeName>ConditionWrapper` — same wrapper pattern as `@service` |
| `@service` on root (`Query`/`Mutation`) fields | Via `ServiceWrapper` pattern |
| `@notGenerated`, `@externalField` | Skipped (not wired) |
| Root DB fields with or without scalar arguments | Wiring generates lambda; JooqQuery method receives extracted arguments explicitly |
| Feature flag `recordBasedOutput` in mojo + `GeneratorConfig` | Drives the entire new generator set |
| `@defaultOrder` on fields | Static ORDER BY at codegen time — index, explicit fields (with optional collation), or primary key. See Step 3 ordering section. |
| `@orderBy` on arguments | Dynamic ORDER BY driven by a GraphQL input argument containing an enum + direction. Generates a switch statement; falls back to `@defaultOrder` (or PK if no default). See Step 3 ordering section. |

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
| `@service` on non-root type fields | Deferred (explicitly noted in Step 2) |
| Java record output types (`@record`) | Future feature, builds on this foundation |

---

## Package Structure

Existing generated code stays under `<outputPackage>` (e.g. `.queries`, `.model`, `.resolvers`, `.wiring`).

New record-based code goes under `<outputPackage>.record`:

| Subpackage | Contents |
|---|---|
| `<outputPackage>.record.queries` | `<TypeName>JooqQuery` — DB query classes |
| `<outputPackage>.record.resolvers` | `<TypeName>RuntimeWiring`, `QueryRuntimeWiring`, `MutationRuntimeWiring`, `RecordWiring` |
| `<outputPackage>.record.dataloaders` | `<TypeName>DataLoader` — generated, one per parent type with `@splitQuery` fields; `@service` DataLoaders are developer-written |

---

## Generator Overview

Build and test generators in dependency order — each step only references outputs from earlier steps. The section heading for each step uses its step number; find it in the file in that order.

| Step | Generator | Output file(s) | Key generated methods/classes | Depends on |
|---|---|---|---|---|
| 0 | Infrastructure *(not a generator)* | `GraphitronFetcherFactory.java` | `field()`, `nestedRecord()`, `nestedResult()`, `fetcher()`, `service()` | — |
| 1 | `ConditionWrapperClassGenerator` | `<ConditionClass>Wrapper.java` (one per external condition class) | one static method per `@condition` / `ReferenceElement.condition` usage | — |
| 2 | `ServiceWrapperClassGenerator` | `<ServiceClass>Wrapper.java` (one per `@service` class) | one static method per `@service` field | — |
| 3 | `JooqQueryClassGenerator` | `<TypeName>JooqQuery.java` (one per GraphQL type + root ops) | `select()`, private inline field methods, `query<Field>()` for `@splitQuery`; root op methods; `orderFields` block for `@orderBy`/`@defaultOrder` | `<ConditionClass>Wrapper` (WHERE/JOIN) |
| 4 | `DataLoaderClassGenerator` | `<TypeName>DataLoader.java` (one per type with `@splitQuery` fields) | `loaderName()`, one static accessor per `@splitQuery` field | Step 3 (`query<Field>()` methods) |
| 5 | `RuntimeWiringClassGenerator` | `<TypeName>RuntimeWiring.java` (one per non-root type) | `wiring()` → `TypeRuntimeWiring.Builder` | Step 4 (`<TypeName>DataLoader`), Step 2 (`<ServiceClass>Wrapper`) |
| 6 | `OperationRuntimeWiringClassGenerator` | `QueryRuntimeWiring.java`, `MutationRuntimeWiring.java` | `wiring()` → `TypeRuntimeWiring.Builder` | Step 3 (`<TypeName>JooqQuery`) |
| 7 | `RecordWiringClassGenerator` | `RecordWiring.java` | `getRuntimeWiringBuilder()` | Steps 5 + 6 (all `*RuntimeWiring` classes) |
| 8 | Orchestration | *(modifies `GraphQLGenerator` only)* | — | Steps 0–7 |

> **File order note**: the `## Step N` sections below are numbered in dependency order (0→8) but appear in the file in a different physical order due to incremental editing. Jump to each section by its `## Step N` heading — the table above is the authoritative implementation sequence.

---

## Step 0: Infrastructure

Three foundational changes with no generator dependencies. Implement and test these before any generator step.

### `GraphitronFetcherFactory` (new runtime utility)

**File:** `graphitron-common/src/main/java/no/sikt/graphql/GraphitronFetcherFactory.java`

> **Package note**: `GraphitronContext` lives at `no.sikt.graphql.GraphitronContext` (package root). `GraphitronFetcherFactory` goes in the same package — there is no `helpers.resolvers` sub-package.

```java
public class GraphitronFetcherFactory {
    // Non-root: resolve a scalar column directly from the source Record
    public static <T> LightDataFetcher<T> field(Field<T> jooqField) {
        return env -> ((Record) env.getSource()).get(jooqField);
    }
    // Non-root: resolve an inline nested single object from the source Record
    public static LightDataFetcher<Record> nestedRecord(String alias) {
        return env -> ((Record) env.getSource()).get(alias, Record.class);
    }
    // Non-root: resolve an inline nested list from the source Record
    public static LightDataFetcher<Result<Record>> nestedResult(String alias) {
        return env -> ((Record) env.getSource()).get(alias, Result.class);
    }

    // Root DB field — no arguments (argument-free fields only; fields with args use a generated lambda, see below)
    public static <V> DataFetcher<CompletableFuture<V>> fetcher(RootFetchFunction<V> fn) {
        return env -> {
            GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
            return CompletableFuture.supplyAsync(() ->
                fn.apply(ctx.getDslContext(env), env.getSelectionSet()));
        };
    }
    // Non-root splitQuery — via DataLoader
    public static <K extends TableRecord<K>, V> DataFetcher<CompletableFuture<V>> fetcher(
            Table<K> table, LoaderFactory<K, V> loaderFactory) {
        return env -> {
            GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
            ResultPath path = env.getExecutionStepInfo().getPath();
            return loaderFactory.create(
                    env.getDataLoaderRegistry(), ctx.getDslContext(env), ctx.getTenantId(env), path)
                .load(((Record) env.getSource()).into(table), env);
        };
    }

    // Root service field (developer-written service, no direct DB access needed)
    public static <V> DataFetcher<CompletableFuture<V>> service(ServiceFunction<V> fn) {
        return env -> fn.apply(env);
    }

    @FunctionalInterface
    public interface RootFetchFunction<V> {
        // Used ONLY for argument-free root fields — enables clean method reference: QueryJooqQuery::films
        V apply(DSLContext ctx, DataFetchingFieldSelectionSet selectionSet);
    }
    @FunctionalInterface
    public interface LoaderFactory<K, V> {
        DataLoader<K, V> create(DataLoaderRegistry registry, DSLContext dslContext,
                                Optional<String> tenantId, ResultPath path);
    }
    @FunctionalInterface
    public interface ServiceFunction<V> {
        CompletableFuture<V> apply(DataFetchingEnvironment env);
    }
}
```

### Root fields with arguments — generated lambda pattern

`RootFetchFunction` only works for argument-free root fields. When a root field has GraphQL arguments (e.g. `customer(id: ID!)`), the generated wiring emits a **lambda** instead of a method reference:

```java
// In QueryRuntimeWiring — field WITH arguments (generated lambda):
.dataFetcher("customer", env -> {
    GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
    String id = env.getArgument("id");
    return CompletableFuture.supplyAsync(() ->
        QueryJooqQuery.customer(ctx.getDslContext(env), id, env.getSelectionSet()));
})

// In QueryRuntimeWiring — argument-free field (method reference via fetcher()):
.dataFetcher("films", GraphitronFetcherFactory.fetcher(QueryJooqQuery::films))
```

The `QueryJooqQuery` method signature for a field with arguments is:
```java
public static Record customer(DSLContext ctx, String id, DataFetchingFieldSelectionSet select) { ... }
```

`OperationRuntimeWiringClassGenerator` detects whether a root field has arguments and emits either the method-ref form or the lambda form accordingly. The generated `OperationMethod` spec includes `List<GraphQLArgumentSpec> arguments` to drive this decision.

---

### `GraphitronContext` — add `getTenantId()`

**Existing methods are left untouched.** `getDataLoaderName(DataFetchingEnvironment env)` remains on the interface and is still used by the existing DTO-based resolvers. The new `record.*` generators do NOT call `getDataLoaderName()` — they use path-based naming self-contained in the generated `DataLoader` classes.

**`GraphitronContext.java`** — add one new method:
```java
@NotNull Optional<String> getTenantId(DataFetchingEnvironment env);
```
`DefaultGraphitronContext` returns `Optional.empty()` (single-tenant default). Multi-tenant implementations return `Optional.of(tenantId)`.

The `Optional<String>` is passed to generated DataLoader `loaderName(path, tenantId)` — it is never unwrapped to `null`. This is the only change to `GraphitronContext`; the existing method set is otherwise unchanged.

---

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

## Step 5: `RuntimeWiringClassGenerator`

### New generator class:
- `generators/record/RuntimeWiringClassGenerator.java` — one `<TypeName>RuntimeWiring` per table-bound output type

### Generated `CustomerRuntimeWiring` (in `record.resolvers`):
```java
public class CustomerRuntimeWiring {
    public static TypeRuntimeWiring.Builder wiring() {
        return TypeRuntimeWiring.newTypeWiring("Customer")
            .dataFetcher("id", GraphitronFetcherFactory.field(CUSTOMER.CUSTOMER_ID))
            .dataFetcher("email", GraphitronFetcherFactory.field(CUSTOMER.EMAIL_ADDRESS))
            .dataFetcher("payments", GraphitronFetcherFactory.nestedResult("payments"))
            .dataFetcher("address", GraphitronFetcherFactory.nestedRecord("address"))
            .dataFetcher("orders", GraphitronFetcherFactory.fetcher(
                Tables.CUSTOMER, CustomerDataLoader::orders))
            .dataFetcher("externalData", GraphitronFetcherFactory.service(
                Tables.CUSTOMER, CustomerServiceDataLoader::externalData));
    }
}
```

### Field categorization (in `RuntimeWiringClassGenerator`):
1. `@notGenerated` or `@externalField` → skip
2. `@splitQuery` → `GraphitronFetcherFactory.fetcher(Tables.TABLE, <ParentType>DataLoader::<fieldName>)`
3. `@service` on non-root → `GraphitronFetcherFactory.service(Tables.TABLE, <ServiceDataLoaderClass>::<fieldName>)` (**deferred** — non-root `@service` with batching is out of scope for initial implementation)
4. Inline list relation (with or without `@reference`) → `GraphitronFetcherFactory.nestedResult("alias")`
5. Inline single relation (with or without `@reference`) → `GraphitronFetcherFactory.nestedRecord("alias")`
6. Simple DB-backed scalar field → `GraphitronFetcherFactory.field(TABLE.COLUMN)` (respects `@field(name:)`)

Note: `@reference` affects the **JooqQuery** (join generation via `ReferenceWrapper`), not the RuntimeWiring fetcher. A `@reference`-annotated inline field still uses `nestedRecord`/`nestedResult` — the source `Record` will contain the expected nested data because JooqQuery fetched it using the correct join path. `@condition` similarly affects JooqQuery only.

---

## Step 6: `OperationRuntimeWiringClassGenerator`

Root operations also get `XxxRuntimeWiring` classes (where `Xxx` = `Query` / `Mutation`). These replace the existing `QueryDataFetcher` / `MutationDataFetcher` and call `XxxJooqQuery` directly.

### Generator:
- `generators/record/OperationRuntimeWiringClassGenerator.java` — generates `QueryRuntimeWiring` and `MutationRuntimeWiring` in `record.resolvers`

### Generated `QueryRuntimeWiring` (in `record.resolvers`):
```java
public class QueryRuntimeWiring {
    public static TypeRuntimeWiring.Builder wiring() {
        return TypeRuntimeWiring.newTypeWiring("Query")
            // Argument-free: method reference via fetcher()
            .dataFetcher("films", GraphitronFetcherFactory.fetcher(QueryJooqQuery::films))
            // With arguments: inline lambda — arguments extracted before calling JooqQuery
            .dataFetcher("customer", env -> {
                GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
                String id = env.getArgument("id");
                return CompletableFuture.supplyAsync(() ->
                    QueryJooqQuery.customer(ctx.getDslContext(env), id, env.getSelectionSet()));
            })
            // Service field: generated wrapper via service()
            .dataFetcher("search", GraphitronFetcherFactory.service(SearchServiceWrapper::search));
    }
}
```

**Argument-free root DB fields** use `GraphitronFetcherFactory.fetcher(QueryJooqQuery::method)` — clean method reference.

**Root DB fields with arguments** generate a full inline lambda: arguments are extracted from `env` and passed positionally to the `QueryJooqQuery` method (which takes `(DSLContext, <arg1Type>, ..., DataFetchingFieldSelectionSet)`). `OperationRuntimeWiringClassGenerator` inspects the field's argument list to decide which form to emit.

**Root service fields** use `service(ServiceFunction)` — the method reference points to the **generated wrapper** (see Step 2), not the developer's service class directly.

---

## Step 7: `RecordWiringClassGenerator`

Thin aggregator — one class that collects all `TypeRuntimeWiring.wiring()` and `QueryRuntimeWiring.wiring()` calls into a single `getRuntimeWiringBuilder()`. No spec layer needed; the generator iterates the list of generated types.

```java
public class RecordWiring {
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

`RecordWiringClassGenerator` collects all `RuntimeWiringClassGenerator` + `OperationRuntimeWiringClassGenerator` outputs and registers them. It has no `SpecBuilder` — it only needs the list of type names.

---

## Step 2: `ServiceWrapperClassGenerator` (in `record.resolvers`)

Service code should have zero GraphQL dependencies so it can be reused from other API implementations. A generated wrapper class bridges the GraphQL layer to each service class.

### Design principles

- **One wrapper class per service class**, named `<ServiceClassName>Wrapper` (e.g. `HelloWorldServiceWrapper`). Contains one `public static` method per `@service`-annotated field that uses that class.
- **Static and instance methods are both supported.** Detected via `Modifier.isStatic(method.getModifiers())` at code-generation time.
- **Arguments are matched by name**, not position. At code-generation time, service method parameters are resolved via reflection using `Parameter.getName()`. This requires the service class to be compiled with `javac -parameters`; the generator validates this and fails with a clear error if names are unavailable.
- **`DSLContext` parameters on static methods are matched by type**, not name — so the parameter can be named anything. At most one `DSLContext` parameter is allowed per method; two → error.
- **Context arguments** listed in `contextArguments: [...]` on the directive are extracted from `GraphitronContext` by name rather than by positional convention (removing the current "last N params" hack).
- **Service class has no GraphQL imports**. The wrapper handles all `DataFetchingEnvironment` access.

### `-parameters` validation (at code-generation time)

After resolving the service `Method` via reflection, call `parameter.isNamePresent()` on **all** parameters — including any `DSLContext` parameter on a static method. The name is needed for clear error messages throughout the matching and validation steps. If any returns `false`, throw with:

> `Service class HelloWorldService must be compiled with 'javac -parameters'. Parameter names are not available on method helloWorldAgain(). Add <compilerArgs><arg>-parameters</arg></compilerArgs> to the maven-compiler-plugin for the module containing this service.`

There are no exemptions — parameter names are required for all parameters so that error messages can always refer to parameters by name.

### Overload resolution + argument matching

The expected non-DSLContext parameter set for a field is: all GraphQL arguments on the field, plus all entries in `contextArguments: [...]` on the directive.

For each candidate overload (methods whose simple name matches the directive's `method` or the field name):
1. If static: count parameters of type `DSLContext`. If > 1 → reject with an error immediately (not allowed). If exactly 1, set aside as `DslContextParam`; remove it from further matching.
2. Reject if the remaining parameter count differs from the expected parameter set size
3. Reject if any remaining parameter name (from `-parameters`) doesn't map to a GraphQL argument name or a `contextArguments` entry
4. Reject if any remaining parameter **type** is incompatible with its matched argument type (same type-compatibility rules as the existing validator)
5. Accept if all conditions pass

After filtering:
- **Exactly one match** → use it
- **Zero matches** → fail at code-generation time listing all overloads found and what was expected:
  > `No overload of HelloWorldService.helloWorldAgain matches field 'helloWorldAgain'. Expected parameters: [String name]. Found:\n  helloWorldAgain(String name)\n  helloWorldAgain(CustomerRecord input)`
- **Multiple matches** → fail with an ambiguity error listing the conflicting candidates

For each parameter of the selected method:
- Type is `DSLContext` (static only) → inject `ctx.getDslContext(env)` at the parameter's position
- Name matches a GraphQL argument → extract from `env.getArgument(name)`, cast to the parameter type
- Name is in `contextArguments` → extract from `GraphitronContext`

### Service instantiation (instance methods only)

Inspect the service class's constructors via reflection:
- If a `(DSLContext)` constructor exists → instantiate with `ctx.getDslContext(env)`
- If a no-arg constructor exists → instantiate with `new ServiceClass()`
- If neither → fail at code-generation time with a clear message

For static methods no instantiation is generated.

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
    // ctx declared only because a DslContextParam exists; DSLContext injected at its declared position
    return CompletableFuture.supplyAsync(() -> HelloWorldService.greetWithDb(ctx.getDslContext(env), name));
}
```

The `ctx` variable is only emitted when needed (DSLContext constructor for instance methods, or a `DslContextParam` for static methods).

The wiring uses a method reference either way:
```java
.dataFetcher("helloWorldAgain", GraphitronFetcherFactory.service(HelloWorldServiceWrapper::helloWorldAgain))
```

### `ServiceEntry` in `RuntimeWiringSpec`

Updated to reference the generated wrapper class and method name:
```java
// wrapperClass = generated "HelloWorldServiceWrapper"; wrapperMethod = "helloWorldAgain"
record ServiceEntry(String graphqlField, String wrapperClass, String wrapperMethod) implements FieldWiringEntry {}
```

### Non-root `@service` fields

Fields on non-root types with `@service` that require batching use the DataLoader pattern but the batch method calls the service wrapper instead of a JooqQuery method. This is out of scope for the initial implementation — non-root service fields fall back to the existing wiring path until explicitly supported.

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
    boolean isStatic,              // true if method is static
    List<ServiceParamSpec> params  // in declaration order; includes DslContextParam if present
) {}
sealed interface ServiceParamSpec permits GraphQLArgParam, ContextParam, DslContextParam {}
// name-matched to a GraphQL argument (param name == graphql arg name, validated at codegen)
record GraphQLArgParam(String paramName, String javaType) implements ServiceParamSpec {}
// listed in contextArguments directive; extracted from GraphitronContext by contextKey
record ContextParam(String paramName, String contextKey, String javaType) implements ServiceParamSpec {}
// static methods only; matched by type (DSLContext), not name; injected from ctx.getDslContext(env)
record DslContextParam(String paramName) implements ServiceParamSpec {}
```

### New files

| File | Purpose |
|------|---------|
| `generators/record/spec/ServiceWrapperSpec.java` | Spec types above |
| `generators/record/ServiceWrapperSpecBuilder.java` | Reflection-based: schema + Method → `ServiceWrapperSpec`; performs all `-parameters` and name-matching validation |
| `generators/record/ServiceWrapperCodeGenerator.java` | `ServiceWrapperSpec` → `TypeSpec` |
| `generators/record/ServiceWrapperClassGenerator.java` | Coordinator; one class per distinct service class referenced in the schema |

---

## Step 1: `ConditionWrapperClassGenerator`

`@reference` and `@condition` both reference external condition methods. These are handled by a single wrapper pattern, identical to `ServiceWrapper`: one generated `<ConditionClassName>Wrapper` class per distinct external condition class referenced anywhere in the schema, containing one method per referenced condition method.

Sources that contribute methods to the wrapper:
- `@condition(condition: {className: "X", method: "m"})` on any field or argument
- `condition: {className: "X", method: "m"}` on any `ReferenceElement` within a `@reference` path

Argument mapping rules extend the `ServiceWrapper` pattern: parameters matched by name to GraphQL args; `DSLContext` matched by type; `contextArguments` extracted from `GraphitronContext`; requires `-parameters` compilation. **Additionally**, when the condition is on a `ReferenceElement` (or any context where parent and child tables are available), the external method may declare typed table parameters — these are matched by jOOQ table type and injected as the path-navigated or aliased table instances. `override` is **not** a property of the wrapper method — it is recorded in the `ConditionMethodSpec` and consumed by `JooqQueryCodeGenerator` when deciding whether to AND or replace the generated WHERE.

```java
// Generated: CustomerConditionsWrapper.java
public class CustomerConditionsWrapper {

    // From: activeCustomers @condition — root field, no table context
    public static Condition activeCustomers(DSLContext ctx) {
        return CustomerConditions.activeCustomers(ctx);
    }

    // From: inactiveCustomers(lastNameStartingWith: String) @condition — root field with arg
    public static Condition inactiveCustomers(DSLContext ctx, String lastNameStartingWith) {
        return CustomerConditions.inactiveCustomers(ctx, lastNameStartingWith);
    }
}

// Generated: FilmLanguageConditionsWrapper.java
// Source: @reference on Film.language — condition on a ReferenceElement; parent=Film, child=Language
public class FilmLanguageConditionsWrapper {

    // Parent and child table references passed through — external method may use column refs
    public static Condition selectedLanguage(Film film, Language language, String languageId) {
        return FilmLanguageConditions.selectedLanguage(film, language, languageId);
    }
}
```

The `JooqQuery` calls the wrapper, passing the actual table aliases in use:
```java
// onKey variant (user-specified FK key):
.where(FilmLanguageConditionsWrapper.selectedLanguage(film, LANGUAGE, selectedLanguageId))

// Path variant (auto-inferred FK):
.where(FilmLanguageConditionsWrapper.selectedLanguage(film, film.language(), selectedLanguageId))
```

**Join path execution** (the non-condition part of `@reference`) is **not** wrapped — it is generated inline in `JooqQuery`. No jOOQ table path-navigation methods (e.g. `film.language(FK)`) are called in generated code; **no Java reflection on generated table classes is used**. Instead, all join conditions are derived from jOOQ FK metadata at codegen time:
- Single-hop: FK-derived WHERE predicate in a correlated subquery (e.g. `_lang.LANGUAGE_ID.eq(film.LANGUAGE_ID)`)
- Multi-hop intermediate hops: `onKey(Keys.FK_NAME)` JOIN within the subquery (or outer query for `@splitQuery`)

When a path element also has a `condition`, the `<ConditionClass>Wrapper.<method>(ctx)` result is AND-ed into the WHERE clause inline.

The generated `QueryJooqQuery` calls the wrapper:
```java
// override: true → wrapper result replaces generated WHERE
public static Result<Record> activeCustomers(DSLContext ctx, DataFetchingFieldSelectionSet select) {
    var _a = CUSTOMER.as("customer_hash");
    return ctx.select(CustomerJooqQuery.select(_a, select))
        .from(_a)
        .where(CustomerConditionsWrapper.activeCustomers(ctx))
        .fetch();
}

// override: false (default) → wrapper result AND-ed with generated WHERE
public static Result<Record> inactiveCustomers(
        DSLContext ctx, String lastNameStartingWith, DataFetchingFieldSelectionSet select) {
    var _a = CUSTOMER.as("customer_hash");
    Condition generated = DSL.noCondition();  // or schema-inferred FK condition
    return ctx.select(CustomerJooqQuery.select(_a, select))
        .from(_a)
        .where(generated.and(CustomerConditionsWrapper.inactiveCustomers(ctx, lastNameStartingWith)))
        .fetch();
}
```

### New files

| File | Purpose |
|------|---------|
| `generators/record/spec/ConditionWrapperSpec.java` | `ConditionWrapperSpec`, `ConditionMethodSpec` (reuses `ServiceParamSpec` sealed hierarchy; adds `boolean override`) |
| `generators/record/ConditionWrapperSpecBuilder.java` | Schema-wide scan for all `@condition` usages and `ReferenceElement.condition` fields; groups by external class name; same `-parameters` validation as `ServiceWrapperSpecBuilder` |
| `generators/record/ConditionWrapperCodeGenerator.java` | `ConditionWrapperSpec` → `TypeSpec` |
| `generators/record/ConditionWrapperClassGenerator.java` | Coordinator; one class per distinct external condition class in the schema |

---

## Step 4: `DataLoaderClassGenerator`

One class per parent type that has at least one `@splitQuery` field, named `{ParentType}DataLoader`. Each `@splitQuery` field becomes a static method named after the field.

### Argument homogeneity invariant

A DataLoader batches all keys registered under the same name within one GraphQL execution tick. For the batch to be correct, every key must have been registered with the same arguments — otherwise the DB query would use one set of arguments for keys that actually requested different ones.

**Why path-based naming guarantees this:** All field invocations at the same normalised path come from the same position in the query document and therefore have identical arguments. Different argument sets always correspond to different positions (via aliases), which produce different paths. Tenant isolation is also needed, so the final name is `{tenantId}/{normalizedPath}` (or just `{normalizedPath}` for single-tenant).

Path normalisation strips list-index segments from `ResultPath` so that `customers[0]/orders` and `customers[1]/orders` both normalise to `customers/orders` and batch correctly.

```java
public class CustomerDataLoader {

    public static String loaderName(ResultPath path, Optional<String> tenantId) {
        String normalizedPath = path.toList().stream()
            .filter(seg -> !(seg instanceof Integer))
            .map(Object::toString)
            .collect(Collectors.joining("/"));
        return tenantId.map(id -> id + "/" + normalizedPath).orElse(normalizedPath);
    }

    public static DataLoader<CustomerRecord, Result<Record>> orders(
            DataLoaderRegistry registry, DSLContext dslContext,
            Optional<String> tenantId, ResultPath path) {
        return registry.computeIfAbsent(loaderName(path, tenantId),
            name -> DataLoaderFactory.newMappedDataLoader(ordersLoader(dslContext)));
    }

    private static MappedBatchLoaderWithContext<CustomerRecord, Result<Record>> ordersLoader(DSLContext dslContext) {
        return (keys, batchEnv) -> {
            DataFetchingEnvironment env =
                (DataFetchingEnvironment) batchEnv.getKeyContextsList().get(0);
            return CustomerJooqQuery.queryOrders(dslContext, keys, env);
        };
    }
}
```

---

## Step 3: `JooqQueryClassGenerator`

One class is generated per GraphQL type. Responsibility is split:

- **Root operation type** (`Query`, `Mutation`): The generated class has one method per root field. Each method provides the structural query — FROM, WHERE (from field arguments), ORDER BY, LIMIT — and delegates field selection to the target type's own `JooqQuery.select()`.
- **Non-root type** (`Customer`, `Film`, etc.): The generated class owns the SELECT list for that type. It has a `select()` method plus private methods for each inline nested field.

### FK auto-inference and resolution

Every inline nested field and `@splitQuery` field requires a join condition between parent and child tables. There are two sources:

**Auto-inferred** (no `@reference` on the field, or a `{table: "X"}` hop in the reference path):
- At codegen time, enumerate all jOOQ `ForeignKey` objects between the two tables using the jOOQ schema metadata already loaded by `JooqQuerySpecBuilder`
- **Exactly one FK found**: record the FK constant name in the spec as an `OnKeyJoin`
- **Zero or multiple FKs found**: fail at codegen time with a clear error:
  - Zero: `"No foreign key found between tables X and Y for field 'fieldName'. Add @reference(path: {key: \"...\"})"`
  - Multiple: `"Multiple foreign keys found between tables X and Y for field 'fieldName'. Specify @reference(path: {key: \"...\"}) to select one: [FK1, FK2, ...]"`

Generated join: **`onKey()` correlated join** — `from(CHILD_TABLE).join(parent).onKey(Keys.FK_CONSTANT)`.

**User-specified key** (`@reference(path: {key: "FK_NAME"})`):
- The key name is used directly as the FK constant: `Keys.FK_NAME`
- No reflection on generated table classes

Generated join: **`onKey()` correlated join** — identical pattern to auto-inferred.

Both cases produce an `OnKeyJoin` in the spec with the resolved FK constant. We never emit `childTable.COLUMN.eq(parent.COLUMN)` ourselves, and we never derive path method names from table names — jOOQ's naming strategy is user-configurable and cannot be assumed.

### Additional conditions (`@condition` on a `ReferenceElement` or `@condition` on a field)

When an additional condition exists, it is appended as `.where(ConditionWrapper.method(parent, child, ...))`. The condition wrapper method receives:
- The parent table reference (typed, e.g. `Film film`)
- The child table reference (typed, e.g. `Language language`) — will be a path-navigated instance when path joins are used in future
- Any extracted GraphQL arguments and context arguments (same matching rules as `ServiceWrapper`)
- `DSLContext` if declared (matched by type)

This allows the external condition to reference columns from both tables directly.

### Path joins (future)

A future extension will allow users to specify the jOOQ path method name directly in `@reference` (e.g. `path: [{method: "language"}]`). When provided, the generated code uses `from(parent.method())` instead of `onKey()` — this is the `PathJoin` strategy in `HopJoinStrategy`. At codegen time the path method must be validated against the jOOQ-generated table class; if it does not exist, a clear error is emitted. This works correctly regardless of the user's jOOQ naming strategy. **Deferred** — not part of this implementation.

### Root operation class — `QueryJooqQuery`

Root field methods receive **extracted arguments as explicit parameters** (extracted by the wiring lambda before calling). This keeps `QueryJooqQuery` free of `DataFetchingEnvironment` but still argument-aware:

```java
public class QueryJooqQuery {

    // Argument-free root field
    public static Result<Record> films(DSLContext ctx, DataFetchingFieldSelectionSet select) {
        var _a = FILM.as("film_hash");
        return ctx.select(FilmJooqQuery.select(_a, select))
            .from(_a)
            .fetch();
    }

    // Root field with arguments — id is passed in explicitly (extracted in the wiring lambda)
    public static Record customer(DSLContext ctx, String id, DataFetchingFieldSelectionSet select) {
        var _a = CUSTOMER.as("customer_hash");
        return ctx.select(CustomerJooqQuery.select(_a, select))
            .from(_a)
            .where(_a.CUSTOMER_ID.eq(UInteger.valueOf(id)))
            .fetchOne();
    }
}
```

The wiring lambda extracts arguments with `env.getArgument("id")` before calling the JooqQuery method (see the lambda pattern in Step 6). There is no hidden argument extraction inside `fetcher()`.

Note: `QueryJooqQuery` contains **no field names from `Customer`**. It only knows the table name and how to construct the structural query.

> **`@condition` on root fields**: Supported — `QueryJooqQuery` calls `<ConditionClass>Wrapper.<method>(ctx[, args...])` in the WHERE clause. `override: true` replaces the generated WHERE; `override: false` ANDs with it.

> **`@orderBy`, `@defaultOrder` on root fields**: Supported — see the Ordering section within Step 3. Non-connection fields append `.orderBy(orderFields)` when either directive is present; without them no ORDER BY is emitted.

### Type-owned class — `CustomerJooqQuery`

Responsibility: knows which fields `Customer` exposes and how to select them, including inline nested fields.

The concrete jOOQ table class (e.g., `Customer`, not `Table<?>`) is used in method signatures so fields can be accessed directly (e.g., `customer.CUSTOMER_ID` instead of `alias.field(CUSTOMER.CUSTOMER_ID)`).

```java
public class CustomerJooqQuery {

    // Called from QueryJooqQuery.customers() — returns the SELECT list
    public static List<SelectField<?>> select(Customer customer, DataFetchingFieldSelectionSet select) {
        var fields = new ArrayList<SelectField<?>>();
        // Scalar fields — always included
        fields.add(customer.CUSTOMER_ID);
        fields.add(customer.FIRST_NAME);
        fields.add(customer.EMAIL_ADDRESS);
        // Inline nested fields — conditionally included based on sub-selection
        if (select.contains("payments")) {
            var sub = select.getFields("payments").get(0).getSelectionSet();
            fields.add(payments(customer, sub));
        }
        if (select.contains("address")) {
            var sub = select.getFields("address").get(0).getSelectionSet();
            fields.add(address(customer, sub));
        }
        return fields;
    }

    // Nested list (one-to-many) → multiset with onKey() correlated join.
    // Auto-inferred single FK resolved at codegen → onKey(Keys.FK_CONSTANT).
    // jOOQ generates the FK predicate; no manual condition emitted.
    private static Field<Result<Record>> payments(Customer customer, DataFetchingFieldSelectionSet select) {
        return DSL.multiset(
            DSL.select(PaymentJooqQuery.select(PAYMENT, select))
                .from(PAYMENT)
                .join(customer).onKey(Keys.PAYMENT__PAYMENT_CUSTOMER_ID_FKEY)
        ).as("payments");
    }

    // Nested single (many-to-one) → correlated field() with onKey() correlated join.
    // Auto-inferred single FK resolved at codegen → onKey(Keys.FK_CONSTANT).
    // jOOQ generates the FK predicate; no manual condition emitted.
    private static Field<Record> address(Customer customer, DataFetchingFieldSelectionSet select) {
        return DSL.field(
            DSL.select(AddressJooqQuery.select(ADDRESS, select))
                .from(ADDRESS)
                .join(customer).onKey(Keys.CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
        ).as("address");
    }
}
```

Key rules:
- **Scalar fields**: always added, no selection guard needed.
- **Inline nested fields**: guarded by `select.contains(fieldName)`; sub-selection extracted and passed to the nested method.
- **Join strategy (auto-inferred or `@reference` with `key:`)**: always `onKey(Keys.FK_CONSTANT)` — `from(CHILD).join(parent).onKey(Keys.FK)`. jOOQ generates the FK predicate; no manual `WHERE col = col` is emitted. Path method names are **not** derived from table names since jOOQ naming strategy is user-configurable.
- **Additional `@condition`**: appended as `.where(ConditionWrapper.method(parent, child, ...))` after the join. Receives typed parent and child table references plus extracted args. Never replaces the join — it adds an extra predicate.
- **`@condition` on fields with `override: true`**: the wrapper result replaces the auto-generated WHERE entirely (relevant for root fields with `@condition`, not for join conditions).
- **`@splitQuery` fields**: not included in `select()` — fetched via DataLoader.
- **`@asConnection` fields** (after transformer expansion: `XxxConnection` return type): **deferred** — the inline `select()` for the node type is already correct; only the enclosing paginated query method needs to be added later.

**Variant with `@condition`** (extra predicate appended after the `onKey()` join):
```java
// Film.language with @reference(path: {key: "FK_FILM_LANGUAGE"}) and @condition
// onKey() handles the FK join; ConditionWrapper adds the extra predicate
private static Field<Record> language(Film film, DataFetchingFieldSelectionSet select,
                                       String selectedLanguageId) {
    return DSL.field(
        DSL.select(LanguageJooqQuery.select(LANGUAGE, select))
            .from(LANGUAGE)
            .join(film).onKey(Keys.FK_FILM_LANGUAGE)
            .where(FilmLanguageConditionsWrapper.selectedLanguage(film, LANGUAGE, selectedLanguageId))
    ).as("language");
}
```

> **Path join variant (future)**: when the user provides `path: [{method: "language"}]`, the generated code becomes `from(film.language()).where(FilmLanguageConditionsWrapper.selectedLanguage(film, film.language(), selectedLanguageId))` — the `onKey()` join is replaced by the path, and the child table reference passed to the wrapper becomes `film.language()`.

**Multi-hop `@reference`** (inline field, all hops use `onKey()`):
```java
// Film.storesWithInventory: [Store]
// @reference(path: [{key: "INVENTORY__INVENTORY_FILM_ID_FKEY"}, {table: "STORE"}])
// Hop 1: user-specified key → onKey(INVENTORY__INVENTORY_FILM_ID_FKEY) correlates to outer film
// Hop 2: auto-inferred single FK between INVENTORY and STORE → onKey(FK_CONSTANT) resolved at codegen
private static Field<Result<Record>> storesWithInventory(Film film, DataFetchingFieldSelectionSet select) {
    Inventory _inv = INVENTORY.as("inv_hash");
    Store _s = STORE.as("store_hash");
    return DSL.multiset(
        DSL.select(StoreJooqQuery.select(_s, select))
            .from(_s)
            .join(_inv).onKey(Keys.INVENTORY__INVENTORY_STORE_ID_FKEY)  // hop 2: auto-inferred
            .join(film).onKey(Keys.INVENTORY__INVENTORY_FILM_ID_FKEY)   // hop 1: correlates to outer film
    ).as("storesWithInventory");
}
```

> Note: the exact SQL jOOQ generates for chained `join().onKey()` calls inside a correlated subquery, particularly where one join targets the outer-scope table, is an implementation-time detail to verify against jOOQ's behaviour.

The `select()` signature (`List<SelectField<?>>`) is **unchanged**.

### `@splitQuery` method (keyed by parent `TableRecord`):

For `@splitQuery`, the batch fetch is an explicit FROM query (not a correlated subquery), so `onKey()` and explicit JOINs apply directly. Path joins also work here for auto-inferred hops.

```java
// In CustomerJooqQuery — auto-inferred single FK (no @reference)
// Path join: CUSTOMER → PAYMENT, path method customer.payments()
// For the batch query we need the FK column to filter by parent keys — use the path alias
public static Map<CustomerRecord, Result<Record>> queryPayments(
        DSLContext ctx, Set<CustomerRecord> keys, DataFetchingFieldSelectionSet select) {
    // customer.payments() is a path alias — we need a concrete alias to filter by FK column
    // Since auto-inferred FK is known at codegen, we reference the FK column explicitly here
    Payment _a = PAYMENT.as("payment_hash");
    return ctx.select(PaymentJooqQuery.select(_a, select))
        .from(_a)
        .where(_a.CUSTOMER_ID.in(keys.stream().map(CustomerRecord::getCustomerId).toList()))
        .fetch()
        .stream()
        .collect(Collectors.groupingBy(r -> r.into(CUSTOMER)));
}

// In FilmJooqQuery — @splitQuery + @reference multi-hop
// storesThatHaveThisFilm @reference(path: [{key: "INVENTORY__INVENTORY_FILM_ID_FKEY"}, {table: "STORE"}])
// Hop 1 (user key): onKey() join Film ← Inventory, filter by film keys
// Hop 2 (auto-inferred): path join Inventory → Store
public static Map<FilmRecord, Result<Record>> queryStoresThatHaveThisFilm(
        DSLContext ctx, Set<FilmRecord> keys, DataFetchingFieldSelectionSet select) {
    Film _film = FILM.as("film_hash");
    Inventory _inv = INVENTORY.as("inv_hash");
    return ctx.select(StoreJooqQuery.select(_inv.store(), select))
        .from(_film)
        .join(_inv).onKey(Keys.INVENTORY__INVENTORY_FILM_ID_FKEY)  // hop 1: user-specified key
        .join(_inv.store())                                         // hop 2: auto-inferred path join
        .where(_film.FILM_ID.in(keys.stream().map(FilmRecord::getFilmId).toList()))
        .fetch()
        .stream()
        .collect(Collectors.groupingBy(r -> r.into(FILM)));
}

// In CityJooqQuery — @splitQuery + @reference with condition on a path element
// customers @reference(path: [{table: "ADDRESS"}, {key: "CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY",
//   condition: {className: "CityConditions", method: "customersForCityViaAddresses"}}])
// Hop 1 (auto-inferred): path join City → Address
// Hop 2 (user key): onKey() join Address → Customer + ReferenceElement condition
public static Map<CityRecord, Result<Record>> queryCustomers(
        DSLContext ctx, Set<CityRecord> keys, DataFetchingFieldSelectionSet select) {
    City _city = CITY.as("city_hash");
    Address _addr = ADDRESS.as("addr_hash");
    Customer _c = CUSTOMER.as("cust_hash");
    return ctx.select(CustomerJooqQuery.select(_c, select))
        .from(_city)
        .join(_addr).onKey(/* auto-inferred FK City→Address, but we're in explicit join context —
                              use onKey with the resolved FK constant */)
        .join(_c).onKey(Keys.CUSTOMER__CUSTOMER_ADDRESS_ID_FKEY)
        .where(_city.CITY_ID.in(keys.stream().map(CityRecord::getCityId).toList())
            .and(CityConditionsWrapper.customersForCityViaAddresses(_city, _c, ctx)))
        .fetch()
        .stream()
        .collect(Collectors.groupingBy(r -> r.into(CITY)));
}
```

> **Note on auto-inferred hops in `@splitQuery` context**: path joins (`city.addresses()`) can also be used in the explicit FROM chain if preferred. The auto-inferred FK constant is still resolved at codegen and can be used with `onKey()` — the path style and `onKey()` style are interchangeable here. Implementation should pick one consistently.
```

> **`@asConnection` on `@splitQuery` fields:** deferred. All generated split-query methods return `Map<ParentRecord, Result<Record>>` in this phase.

---

### Ordering — `@defaultOrder` and `@orderBy` (part of Step 3)

Ordering must be generated before connection/pagination support can be added. Both directives resolve to a `SortField<?>[]` (a jOOQ `OrderField<?>[]`) at some point — either fully at codegen time (`@defaultOrder`) or as a runtime switch statement (`@orderBy`). The generated query then appends `.orderBy(orderFields)`.

### Three modes (identical for both directives)

| Mode | Source | Generated code |
|---|---|---|
| `index` | `@defaultOrder(index: "IDX_TITLE")` | `QueryHelper.getSortFields(_a, "IDX_TITLE", "ASC")` |
| `fields` | `@defaultOrder(fields: [{name: "LAST_NAME", collate: "xdanish_ai"}])` | `new SortField[]{_a.LAST_NAME.collate("xdanish_ai").sort(SortOrder.ASC)}` |
| `primaryKey` | `@defaultOrder(primaryKey: true)` | `_a.getPrimaryKey().getFieldsArray()` (mapped with sort direction) |

`QueryHelper.getSortFields(table, indexName, direction)` looks up the named index on the table and returns its fields in the requested direction. Index names must match the database exactly.

### `@defaultOrder` only

Static ORDER BY — the order fields are baked into the generated method body:

```java
// films: [Film] @defaultOrder(index: "IDX_TITLE")
public static Result<Record> films(DSLContext ctx, DataFetchingFieldSelectionSet select) {
    var _a = FILM.as("film_hash");
    var orderFields = QueryHelper.getSortFields(_a, "IDX_TITLE", "ASC");
    return ctx.select(FilmJooqQuery.select(_a, select))
        .from(_a)
        .orderBy(orderFields)
        .fetch();
}
```

No extra argument in the wiring lambda — the field remains argument-free for ordering purposes, so the method-reference form (`GraphitronFetcherFactory.fetcher(QueryJooqQuery::films)`) is still valid unless the field has other arguments.

### `@orderBy` argument (with optional `@defaultOrder` fallback)

Dynamic ORDER BY — generates a null-check ternary + switch statement. The `@orderBy` directive sits on the **argument**, not the field; the argument type is an input type with `orderByField` (enum) and `direction` fields.

```java
// films(orderBy: FilmsOrderByInput @orderBy): [Film] @defaultOrder(index: "IDX_TITLE")
public static Result<Record> films(
        DSLContext ctx, FilmsOrderByInput orderBy, DataFetchingFieldSelectionSet select) {
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
            case "ID" -> _a.getPrimaryKey().getFieldsArray()
                // map each field with the runtime direction
                ;
            default -> throw new IllegalArgumentException(
                "Unknown orderBy: " + orderBy.getOrderByField());
        };
    return ctx.select(FilmJooqQuery.select(_a, select))
        .from(_a)
        .orderBy(orderFields)
        .fetch();
}
```

When there is no `@defaultOrder` and `orderBy` is null, the null branch falls back to primary key ordering (same validation as existing code — fails at codegen if no PK exists).

The wiring lambda extracts the `orderBy` argument by name (nullable — the field argument in the schema is optional):

```java
.dataFetcher("films", env -> {
    GraphitronContext ctx = env.getGraphQlContext().get("graphitronContext");
    FilmsOrderByInput orderBy = env.getArgument("orderBy");  // null if not supplied
    return CompletableFuture.supplyAsync(() ->
        QueryJooqQuery.films(ctx.getDslContext(env), orderBy, env.getSelectionSet()));
})
```

### Ordering on `@splitQuery` fields

`@defaultOrder` and `@orderBy` can also appear on non-root fields with `@splitQuery`. The generated batch method gains the same `orderFields` block and `.orderBy(...)`. The `orderBy` argument (if present) is extracted from `DataFetchingEnvironment` inside the DataLoader batch lambda:

```java
private static MappedBatchLoaderWithContext<CustomerRecord, Result<Record>> paymentsLoader(
        DSLContext dslContext) {
    return (keys, batchEnv) -> {
        DataFetchingEnvironment env =
            (DataFetchingEnvironment) batchEnv.getKeyContextsList().get(0);
        PaymentsOrderByInput orderBy = env.getArgument("orderBy");  // null if not supplied
        return CustomerJooqQuery.queryPayments(dslContext, keys, orderBy, env.getSelectionSet());
    };
}

// In CustomerJooqQuery
public static Map<CustomerRecord, Result<Record>> queryPayments(
        DSLContext ctx, Set<CustomerRecord> keys, PaymentsOrderByInput orderBy,
        DataFetchingFieldSelectionSet select) {
    Payment _a = PAYMENT.as("payment_hash");
    var orderFields = orderBy == null
        ? QueryHelper.getSortFields(_a, "IDX_DEFAULT", "ASC")   // @defaultOrder
        : switch (orderBy.getOrderByField().toString()) { /* cases */ };
    return ctx.select(PaymentJooqQuery.select(_a, select))
        .from(_a)
        .where(_a.CUSTOMER_ID.in(keys.stream().map(CustomerRecord::getCustomerId).toList()))
        .orderBy(orderFields)
        .fetch()
        .stream()
        .collect(Collectors.groupingBy(r -> r.into(CUSTOMER)));
}
```

When a `@splitQuery` field has no ordering directives, no `orderBy` parameter is added and no `.orderBy()` is emitted.

### Spec additions for ordering

```java
// Ordering spec — attached to OperationMethod and SplitQueryMethodSpec when either directive is present.
// null on that field means: no ordering (no .orderBy() emitted).
record OrderingSpec(
    DefaultOrderSpec defaultOrder,  // null if no @defaultOrder
    OrderByArgSpec   orderByArg     // null if no @orderBy argument
) {}

// Static @defaultOrder
record DefaultOrderSpec(SortMode mode, String indexName, List<FieldSortSpec> fields, String direction) {}

// Dynamic @orderBy — one entry per enum value in the orderByField enum
record OrderByArgSpec(
    String argName,              // e.g. "orderBy"
    String javaType,             // e.g. "FilmsOrderByInput" — Java type of the argument
    List<OrderByCase> cases
) {}
record OrderByCase(
    String enumLabel,            // exact toString() value matched in switch case
    SortMode mode,               // INDEX | FIELDS | PRIMARY_KEY
    String indexName,            // non-null when mode == INDEX
    List<FieldSortSpec> fields   // non-null when mode == FIELDS
    // direction: resolved at runtime from orderBy.getDirection()
) {}

enum SortMode { INDEX, FIELDS, PRIMARY_KEY }
record FieldSortSpec(String columnName, String collation) {}  // collation null if absent
```

Updated `OperationMethod` and `SplitQueryMethodSpec`:

```java
record OperationMethod(
    String methodName,
    String typeQueryClass,
    String jooqTableClass,
    String tableAlias,
    List<GraphQLArgumentSpec> arguments,
    boolean isList,
    OrderingSpec ordering          // null if no ordering directives on this field
) {}

record SplitQueryMethodSpec(
    String methodName,
    String childJooqTableClass,
    String childTypeQueryClass,
    String keyRecord,
    String keyColumn,
    List<HopSpec> hops,
    OrderingSpec ordering          // null if no ordering directives on this field
) {}
```

`JooqQuerySpecBuilder` detects `@defaultOrder` on the field definition and `@orderBy` on any argument; `OrderingSpecBuilder` (a small helper) converts these into `OrderingSpec`. `JooqQueryCodeGenerator` emits the `orderFields` block and `.orderBy()` call when `ordering` is non-null.

---

## Step 8: Orchestration

**`GraphQLGenerator.getGenerators()`** — when `recordBasedOutput = true`, **add** to the generator list:
All in `generators/record/`:
- `RuntimeWiringClassGenerator` → `<TypeName>RuntimeWiring` per table-bound output type into `record.resolvers`
- `OperationRuntimeWiringClassGenerator` → `QueryRuntimeWiring` / `MutationRuntimeWiring` into `record.resolvers`
- `RecordWiringClassGenerator` → `RecordWiring` top-level entry point into `record.resolvers`
- `DataLoaderClassGenerator` → `<TypeName>DataLoader` into `record.dataloaders`
- `JooqQueryClassGenerator` → `<TypeName>JooqQuery` into `record.queries`
- `ServiceWrapperClassGenerator` → `<ServiceClass>Wrapper` into `record.resolvers` (one per distinct service class)
- `ConditionWrapperClassGenerator` → `<ConditionClass>Wrapper` into `record.resolvers` (one per distinct external condition class; covers both `@condition` and `ReferenceElement.condition`)

Existing generators (`TypeDTOGenerator`, `RecordMapperClassGenerator`, `DBClassGenerator`, etc.) continue to run unchanged — the flag adds new generators, it does not remove old ones (yet).

---

## Critical Files to Modify/Create

> Paths use `...` as shorthand for the common prefix. `GraphitronFetcherFactory` lives in `no.sikt.graphql` (same package as `GraphitronContext`) — there is no `helpers.resolvers` sub-package.

| File | Change |
|------|--------|
| `graphitron-common/src/main/java/no/sikt/graphql/GraphitronContext.java` | Add `getTenantId()` — existing methods unchanged |
| `graphitron-common/src/main/java/no/sikt/graphql/DefaultGraphitronContext.java` | Implement `getTenantId()` returning `Optional.empty()` |
| `graphitron-common/src/main/java/no/sikt/graphql/GraphitronFetcherFactory.java` | **New** |
| `graphitron-java-codegen/.../configuration/GeneratorConfig.java` | Add `recordBasedOutput` static flag |
| `graphitron-java-codegen/.../generate/Generator.java` | Add `boolean recordBasedOutput()` |
| `graphitron-maven-plugin/.../mojo/GenerateMojo.java` | Add `@Parameter recordBasedOutput` |
| `graphitron-java-codegen/.../generate/GraphQLGenerator.java` | Add new generators when flag is set |
| `graphitron-java-codegen/.../mappings/JavaPoetClassName.java` | Add `JOOQ_RECORD`, `JOOQ_RESULT`, `LIGHT_DATA_FETCHER`, `GRAPHITRON_FETCHER_FACTORY` |
| `graphitron-java-codegen/.../generators/record/spec/RuntimeWiringSpec.java` | **New** — sealed types + FieldWiringEntry variants |
| `graphitron-java-codegen/.../generators/record/spec/DataLoaderSpec.java` | **New** |
| `graphitron-java-codegen/.../generators/record/spec/JooqQuerySpec.java` | **New** — `RootQuerySpec`, `OperationMethod` (with `List<GraphQLArgumentSpec> arguments`, `OrderingSpec ordering`), `TypeQuerySpec`, `ScalarFieldSpec`, `InlineFieldSpec`, `SplitQueryMethodSpec` (with `OrderingSpec ordering`), `OrderingSpec`, `DefaultOrderSpec`, `OrderByArgSpec`, `OrderByCase`, `SortMode`, `FieldSortSpec` |
| `graphitron-java-codegen/.../generators/record/RuntimeWiringSpecBuilder.java` | **New** — schema → `RuntimeWiringSpec` |
| `graphitron-java-codegen/.../generators/record/RuntimeWiringCodeGenerator.java` | **New** — `RuntimeWiringSpec` → TypeSpec |
| `graphitron-java-codegen/.../generators/record/RuntimeWiringClassGenerator.java` | **New** — coordinator (thin) |
| `graphitron-java-codegen/.../generators/record/OperationRuntimeWiringClassGenerator.java` | **New** — root Query/Mutation wiring |
| `graphitron-java-codegen/.../generators/record/RecordWiringClassGenerator.java` | **New** — top-level `RecordWiring` entry point |
| `graphitron-java-codegen/.../generators/record/DataLoaderSpecBuilder.java` | **New** |
| `graphitron-java-codegen/.../generators/record/DataLoaderCodeGenerator.java` | **New** |
| `graphitron-java-codegen/.../generators/record/DataLoaderClassGenerator.java` | **New** — coordinator |
| `graphitron-java-codegen/.../generators/record/JooqQuerySpecBuilder.java` | **New** — builds `RootQuerySpec` (for Query/Mutation) or `TypeQuerySpec` (for other types) |
| `graphitron-java-codegen/.../generators/record/JooqQueryCodeGenerator.java` | **New** — `RootQuerySpec`/`TypeQuerySpec` → TypeSpec |
| `graphitron-java-codegen/.../generators/record/JooqQueryClassGenerator.java` | **New** — coordinator; one class per GraphQL type |
| `graphitron-java-codegen/.../generators/record/spec/ServiceWrapperSpec.java` | **New** — `ServiceWrapperSpec`, `ServiceMethodSpec`, `ServiceParamSpec` sealed types |
| `graphitron-java-codegen/.../generators/record/ServiceWrapperSpecBuilder.java` | **New** — reflection-based; validates `-parameters`, name-matches params; one spec per service class |
| `graphitron-java-codegen/.../generators/record/ServiceWrapperCodeGenerator.java` | **New** — `ServiceWrapperSpec` → `TypeSpec` |
| `graphitron-java-codegen/.../generators/record/ServiceWrapperClassGenerator.java` | **New** — coordinator; one class per distinct service class in schema |
| `graphitron-java-codegen/.../generators/record/spec/ConditionWrapperSpec.java` | **New** — `ConditionWrapperSpec`, `ConditionMethodSpec` (reuses `ServiceParamSpec` hierarchy; adds `boolean override`) |
| `graphitron-java-codegen/.../generators/record/ConditionWrapperSpecBuilder.java` | **New** — schema-wide scan for `@condition` and `ReferenceElement.condition`; groups by external class name; same `-parameters` validation as `ServiceWrapperSpecBuilder` |
| `graphitron-java-codegen/.../generators/record/ConditionWrapperCodeGenerator.java` | **New** — `ConditionWrapperSpec` → `TypeSpec` |
| `graphitron-java-codegen/.../generators/record/ConditionWrapperClassGenerator.java` | **New** — coordinator; one class per distinct external condition class in the schema |

---

## Generator Architecture: Spec Layer

The root problem with existing generators is that schema-analysis decisions and JavaPoet calls are interleaved in a single `generate()` method, so tests can only compare full Java strings against a schema — making them brittle and hard to write.

Each new generator in `generators/record/` is split into **two pure functions** plus a thin coordinator:

```
Schema AST (ObjectDefinition)
  │
  ▼  <TypeName>SpecBuilder (schema logic, zero JavaPoet imports)
  │
  ▼  <TypeName>Spec (plain Java records / sealed interfaces)
  │
  ▼  <TypeName>CodeGenerator (trivial JavaPoet mapping, zero schema logic)
  │
  ▼  TypeSpec → .java file
```

The `<TypeName>ClassGenerator` is the coordinator:
```java
public class RuntimeWiringClassGenerator extends AbstractSchemaClassGenerator {
    @Override
    public TypeSpec generate(ObjectDefinition typeDef) {
        RuntimeWiringSpec spec = RuntimeWiringSpecBuilder.build(typeDef);
        return RuntimeWiringCodeGenerator.generate(spec);
    }
}
```

### Spec types (no JavaPoet imports, live in `generators/record/spec/`)

```java
// RuntimeWiring
record RuntimeWiringSpec(String graphqlType, String className, List<FieldWiringEntry> fields) {}

sealed interface FieldWiringEntry permits
        JooqFieldEntry, InlineResultEntry, InlineRecordEntry, SplitQueryEntry, ServiceEntry {
    String graphqlField();
}
record JooqFieldEntry(String graphqlField, String jooqTable, String jooqColumn) implements FieldWiringEntry {}
record InlineResultEntry(String graphqlField, String alias) implements FieldWiringEntry {}
record InlineRecordEntry(String graphqlField, String alias) implements FieldWiringEntry {}
// generated DataLoader class (e.g. "CustomerDataLoader"); jooqTable for key extraction
record SplitQueryEntry(String graphqlField, String jooqTable, String dataLoaderClass) implements FieldWiringEntry {}
// generated wrapper class (e.g. "HelloWorldServiceWrapper"); method name on that wrapper
record ServiceEntry(String graphqlField, String wrapperClass, String wrapperMethod) implements FieldWiringEntry {}

// DataLoader — names are path-derived at runtime via loaderName(ResultPath, Optional<String>)
record DataLoaderSpec(String className, String keyRecord, String queryClass, String queryMethod) {}

// JooqQuery — root operation type (Query/Mutation)
record RootQuerySpec(String className, List<OperationMethod> operations) {}
record OperationMethod(
    String methodName,              // "customers" (GraphQL field name)
    String typeQueryClass,          // "CustomerJooqQuery"
    String jooqTableClass,          // "Customer" (concrete jOOQ table class)
    String tableAlias,              // hash-based stable alias
    List<GraphQLArgumentSpec> arguments,  // explicit params: (type, paramName) extracted from GraphQL field args; drives both JooqQuery method sig and wiring lambda
    boolean isList,                 // true → fetch(), false → fetchOne()
    OrderingSpec ordering           // null if no @defaultOrder / @orderBy on this field
) {}
// One entry per GraphQL argument on the root field (excluding the @orderBy arg, which is in OrderingSpec)
record GraphQLArgumentSpec(String paramName, String javaType) {}

// Ordering spec — see Step 3 Ordering subsection for full description
record OrderingSpec(DefaultOrderSpec defaultOrder, OrderByArgSpec orderByArg) {}
record DefaultOrderSpec(SortMode mode, String indexName, List<FieldSortSpec> fields, String direction) {}
record OrderByArgSpec(String argName, String javaType, List<OrderByCase> cases) {}
record OrderByCase(String enumLabel, SortMode mode, String indexName, List<FieldSortSpec> fields) {}
enum SortMode { INDEX, FIELDS, PRIMARY_KEY }
record FieldSortSpec(String columnName, String collation) {}

// JooqQuery — non-root type: owns SELECT list + nested inline field methods
record TypeQuerySpec(
    String className,         // "CustomerJooqQuery"
    String jooqTableClass,    // "Customer" (concrete jOOQ class, used for method param)
    String jooqTableParam,    // "customer" (parameter name)
    List<ScalarFieldSpec> scalars,
    List<InlineFieldSpec> inlines,
    List<SplitQueryMethodSpec> splitQueries
) {}
record ScalarFieldSpec(String column) {}  // e.g. "CUSTOMER_ID" → customer.CUSTOMER_ID
record InlineFieldSpec(
    String graphqlField,          // "payments"
    String childJooqTableClass,   // "Payment"
    String childTypeQueryClass,   // "PaymentJooqQuery"
    boolean isList,               // true → multiset, false → correlated field()
    List<HopSpec> hops            // always at least one entry; single-hop for most fields
) {}

record SplitQueryMethodSpec(
    String methodName,            // "queryPayments"
    String childJooqTableClass,   // "Payment"
    String childTypeQueryClass,   // "PaymentJooqQuery"
    String keyRecord,             // "CustomerRecord"
    String keyColumn,             // the FK column to filter by parent keys (e.g. "CUSTOMER_ID")
    List<HopSpec> hops,           // always at least one
    OrderingSpec ordering         // null if no @defaultOrder / @orderBy on this field
) {}

// One hop in the join chain. The list is traversed outermost-first.
// JooqQueryCodeGenerator emits path or onKey join based on HopJoinStrategy.
record HopSpec(
    HopJoinStrategy strategy,
    String intermediateTableClass,  // non-null for intermediate hops only
    String intermediateTableAlias,  // hash-based alias; null for the final hop (child table alias is on InlineFieldSpec/SplitQueryMethodSpec)
    String conditionWrapperClass,   // null unless ReferenceElement has a condition
    String conditionWrapperMethod   // null unless ReferenceElement has a condition
) {}

// How jOOQ generates the join for this hop — decided at SpecBuilder time, not CodeGenerator time.
sealed interface HopJoinStrategy permits OnKeyJoin, PathJoin {}
// Used for both auto-inferred (FK constant resolved by scanning schema metadata) and user-specified key.
// jOOQ handles the FK predicate via onKey() — no manual condition emitted.
record OnKeyJoin(String fkKeyConstant) implements HopJoinStrategy {}  // e.g. "INVENTORY__INVENTORY_FILM_ID_FKEY"
// DEFERRED — used only when the user explicitly provides a path method name in @reference.
// Path method names are NOT derived from table names (naming strategy is user-configurable).
// Codegen validates the method exists on the table class and emits a clear error if not.
record PathJoin(String pathMethodName) implements HopJoinStrategy {}  // e.g. "language", "payments"

// ConditionWrapper spec — one per distinct external condition class; covers @condition and
// ReferenceElement.condition entries from anywhere in the schema
record ConditionWrapperSpec(String className, List<ConditionMethodSpec> methods) {}
record ConditionMethodSpec(
    String methodName,            // "activeCustomers"
    String externalClass,         // "CustomerConditions"
    String externalMethod,        // "activeCustomers"
    boolean override,             // only meaningful for @condition usages; drives AND vs replace in JooqQuery
    List<ConditionParamSpec> params
) {}
// Extends ServiceParamSpec with two additional variants for table references:
sealed interface ConditionParamSpec
    permits GraphQLArgParam, ContextParam, DslContextParam, ParentTableParam, ChildTableParam {}
// Inherited from ServiceWrapper param hierarchy (same matching rules):
// record GraphQLArgParam, ContextParam, DslContextParam — as before
// Matched by jOOQ table type; injected as the parent table alias (e.g. Film film)
record ParentTableParam(String paramName, String jooqTableClass) implements ConditionParamSpec {}
// Matched by jOOQ table type; injected as the child/path table alias (e.g. Language language or film.language())
record ChildTableParam(String paramName, String jooqTableClass) implements ConditionParamSpec {}

// ServiceWrapper — one record per service class referenced in the schema
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
    boolean isStatic,              // true if method is static
    List<ServiceParamSpec> params  // in declaration order; includes DslContextParam if present
) {}
sealed interface ServiceParamSpec permits GraphQLArgParam, ContextParam, DslContextParam {}
// name-matched to a GraphQL argument (param name == graphql arg name, validated at codegen)
record GraphQLArgParam(String paramName, String javaType) implements ServiceParamSpec {}
// listed in contextArguments directive; extracted from GraphitronContext by contextKey
record ContextParam(String paramName, String contextKey, String javaType) implements ServiceParamSpec {}
// static methods only; matched by type (DSLContext), not name; injected from ctx.getDslContext(env)
record DslContextParam(String paramName) implements ServiceParamSpec {}
```

---

## Testing Strategy

Two independent levels of tests, each simple to write.

### Level 1 — SpecBuilder tests (AssertJ, no string comparison)

```java
// RuntimeWiringSpecBuilderTest.java
@Test
void customerWithSplitQuery() {
    ObjectDefinition customer = schemaWith("type Customer { id: ID!, email: String, orders: [Order] @splitQuery }");
    RuntimeWiringSpec spec = RuntimeWiringSpecBuilder.build(customer);

    assertThat(spec.graphqlType()).isEqualTo("Customer");
    assertThat(spec.fields()).containsExactly(
        new JooqFieldEntry("id", "CUSTOMER", "CUSTOMER_ID"),
        new JooqFieldEntry("email", "CUSTOMER", "EMAIL_ADDRESS"),
        new SplitQueryEntry("orders", "CUSTOMER", "CustomerDataLoader")
    );
}
```

These tests exercise all the categorization logic (field 1–6 in the field categorization rules) as **data assertions**, not string matching. Fast, no file I/O.

### Level 2 — CodeGenerator tests (approval, hand-crafted Spec as input)

```java
// RuntimeWiringCodeGeneratorTest.java
@Test
void customerRuntimeWiring() {
    var spec = new RuntimeWiringSpec("Customer", "CustomerRuntimeWiring", List.of(
        new JooqFieldEntry("id", "CUSTOMER", "CUSTOMER_ID"),
        new JooqFieldEntry("email", "CUSTOMER", "EMAIL_ADDRESS"),
        new InlineResultEntry("payments", "payments"),
        new InlineRecordEntry("address", "address"),
        new SplitQueryEntry("orders", "CUSTOMER", "CustomerDataLoader")
    ));
    assertGeneratedContentMatches(RuntimeWiringCodeGenerator.generate(spec), "CustomerRuntimeWiring");
}
```

The input is a hand-crafted `Spec`, not a parsed schema. This makes the test focused on code shape only, not on field-categorization decisions.

### Level 3 — Integration (sparse, schema → Java string, reuse existing `GeneratorTest` infra)

Only needed for a small set of end-to-end scenarios (e.g., `@splitQuery` field on a real schema fragment). Reuses the existing `assertGeneratedContentMatches(schema, expectedFile)` infrastructure unchanged.

### Test file layout

```
src/test/java/.../record/
  spec/
    RuntimeWiringSpecBuilderTest.java
    DataLoaderSpecBuilderTest.java
    JooqQuerySpecBuilderTest.java
  codegen/
    RuntimeWiringCodeGeneratorTest.java
    DataLoaderCodeGeneratorTest.java
    JooqQueryCodeGeneratorTest.java
  integration/
    RecordOutputIntegrationTest.java   ← one or two end-to-end scenarios

src/test/resources/record/
  codegen/CustomerRuntimeWiring.java   ← approved output
  codegen/CustomerDataLoader.java
  codegen/CustomerJooqQuery.java
```

```bash
mvn test -pl :graphitron-java-codegen
mvn test -pl :graphitron-common
mise r build-all
```

