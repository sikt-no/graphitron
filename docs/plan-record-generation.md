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
| `<outputPackage>.rewrite.types` | `<TypeName>Fields` — GraphQL field wiring per output type; named after the GraphQL type |
| `<outputPackage>.rewrite.resolvers` | `GraphitronWiring` |

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

**`TypeSpec` and file output**

`TypeSpec` (from `graphitron-javapoet`, Graphitron's fork of Square's JavaPoet) is an in-memory model of a Java class, interface, or enum declaration. It holds the class name, modifiers, fields (`FieldSpec`), methods (`MethodSpec`), and nested types — everything needed to render a `.java` file. Generators build `TypeSpec` values using a fluent builder API (`TypeSpec.classBuilder("Film").addMethod(…).build()`); no string concatenation or template files are involved.

`JavaFile` wraps a `TypeSpec` with a package name. Calling `.writeTo(File outputDir)` on a built `JavaFile` derives the directory path from the package name (dots → path separators), creates any missing directories, and writes the rendered Java source to `<outputDir>/<package/path>/<ClassName>.java`. Imports are resolved and emitted automatically.

In `GraphQLRewriteGenerator.write()`:
```java
JavaFile.builder(packageName, spec).indent("    ").build()
    .writeTo(new File(GeneratorConfig.outputDirectory()));
```
`packageName` is `GeneratorConfig.outputPackage() + "." + subPackage` (e.g. `no.example.rewrite.tables`). The output directory is Maven's `target/generated-sources/graphitron` (or equivalent), which is on the compile source root so the generated `.java` files are compiled as ordinary source.

Each generator is a utility class with a single `public static List<TypeSpec> generate(GraphitronSchema)` method (or no-arg for schema-independent generators). There is no shared base class or interface. `GraphQLRewriteGenerator` calls each generator explicitly and owns the sub-package routing and file I/O.

| Generator | Output | Notes |
|---|---|---|
| `GraphitronValuesClassGenerator` | `GraphitronValues.java` in `rewrite` | No schema parameter — generates a fixed constant class. Defines `GRAPHITRON_INPUT_IDX`. |
| `LookupClassGenerator` | *(transitional)* | Generates `<TypeName>Lookup::toInputRows`; superseded by DataLoader pattern — to be removed when DataLoader generation is implemented |
| `SplitSourceClassGenerator` | *(transitional)* | Generates `<ParentType><FieldName>DerivedSource::rows`; superseded by DataLoader pattern — to be removed when DataLoader generation is implemented |
| `TableClassGenerator` | `<TableName>.java` in `rewrite.tables` | Projection stubs only (`selectMany`, `selectOne`, `subselectMany`, `subselectOne`); named after the jOOQ table class. DataLoader batch methods are bespoke per-field and live in `rewrite.types` alongside their data fetchers. |
| `FieldsClassGenerator` | `<TypeName>Fields.java` in `rewrite.types` | One static stub per GraphQL field + `wiring()` by method reference; named after the GraphQL type |

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
- **`rewrite.types.<TypeName>Fields`** — GraphQL namespace, named after the GraphQL type. Owns one static method per GraphQL field — each is a `DataFetcher<T>` by signature — and a `wiring()` method that registers them by method reference.

The type name and table name can differ (e.g. GraphQL type `MovieItem` backed by SQL table `film` yields `Film` + `MovieItemFields`).

**`wiring()` uses method references, not lambdas.** Because each field is a named `static T fieldName(DataFetchingEnvironment env)` method, it satisfies `DataFetcher<T>` and can be referenced directly:

```java
// rewrite.types.FilmFields
public static TypeRuntimeWiring.Builder wiring() {
    return TypeRuntimeWiring.newTypeWiring("Film")
        .dataFetcher("title",  FilmFields::title)
        .dataFetcher("actors", FilmFields::actors);
}
```

The wiring method is a pure manifest — no logic, no lambdas. Each field method carries the logic and is independently testable.

**`rewrite.tables.Film`** contains the SQL projection side only:

```java
// Assembles the SELECT list for this type's query.
List<Field<?>> fields(Film alias, DataFetchingFieldSelectionSet sel)

// Scope-establishing methods (current stubs):
Result<Record>          selectMany(DataFetchingEnvironment env, Condition condition, List<SortField<?>> orderBy)
Record                  selectOne(DataFetchingEnvironment env, Condition condition)
Field<Result<Record>>   subselectMany(DataFetchingFieldSelectionSet sel, Condition condition, List<SortField<?>> orderBy)
Field<Record>           subselectOne(DataFetchingFieldSelectionSet sel, Condition condition)
```

These projection methods may eventually move to `rewrite.types.<TypeName>` but are in `rewrite.tables` for now.

DataLoader batch methods are **not** in the table class. They are generated bespoke per-field and live in `rewrite.types.<TypeName>Fields` alongside the corresponding data fetcher (see G6).

`selectMany` and `selectOne` obtain a `DSLContext` internally. The jOOQ `XYZ*Step` types are never referenced in generated method signatures because they are mutable, less composable, and binary-incompatible across jOOQ minor releases.

Results are jOOQ `Record` instances. Scalars via `record.get(TABLE.FIELD)`; nested via `record.get(nestedField)`.

**Field type to method mapping:**

| Field type | `*Fields` fetcher method | Delegates to |
|---|---|---|
| `ColumnField` / `ColumnReferenceField` | `static T fieldName(env)` | `record.get(TABLE.COL)` from source |
| `TableQueryField` — list | `static Result<Record> fieldName(env)` | `TableName.selectMany` |
| `TableQueryField` — single | `static Record fieldName(env)` | `TableName.selectOne` |
| `LookupQueryField` *(lookup field)* | `static CompletableFuture<List<Record>> fieldName(env)` | `completedFuture(lookupFieldName(env, selectedField))` — synchronous DB call, no DataLoader |
| `LookupTableField` — table-mapped parent | `static CompletableFuture<Result<Record>> fieldName(env)` | `completedFuture(…)` wrapping source `Record` extraction; subquery built by `subselect<FieldName>` during parent query |
| `TableField` — list, no `@splitQuery` | `static Result<Record> fieldName(env)` | extract nested column from source `Record` |
| `TableField` — single, no `@splitQuery` | `static Record fieldName(env)` | extract nested column from source `Record` |
| `TableField` — `@splitQuery`, no `@lookupKey` *(result mapped TableField)*, returns `[T]` | `static CompletableFuture<List<Record>> fieldName(env)` | `loadFieldName(sourceRows, env, selectedField)` via DataLoader |
| `TableField` — `@splitQuery`, no `@lookupKey` *(result mapped TableField)*, returns `T` | `static CompletableFuture<Record> fieldName(env)` | `loadFieldName(sourceRows, env, selectedField)` via DataLoader, take first |
| `TableField` — `@splitQuery`, no `@lookupKey` *(result mapped TableField)*, paginated | `static CompletableFuture<List<Record>> fieldName(env)` | `loadFieldNamePage(sourceRows, env, selectedField)` via DataLoader |
| `TableField` — `@splitQuery` + `@lookupKey` *(result mapped LookupTableField)* | `static CompletableFuture<List<Record>> fieldName(env)` | `lookupFieldName(sourceRows, env, selectedField)` via DataLoader |
| `InterfaceField` | `static Object fieldName(env)` | union over each implementor's `subselectMany` |
| Mutation read-back | *(inside mutation fetcher)* | `TableName.selectMany` with derived source |

---

> **Plan review in progress.** Deliverables from G3 onwards have not yet been revised in light of current design decisions.

---

### G3 — Scalar child fields (`ColumnField`, `ColumnReferenceField`)

Generates `fields()` in `rewrite.tables` and one fetcher method + wiring entry in `rewrite.types` per scalar field.

**`rewrite.tables.Customer`** — SELECT list assembly:
```java
public static List<Field<?>> fields(Customer alias, DataFetchingFieldSelectionSet sel) {
    var fields = new ArrayList<Field<?>>();
    if (sel.contains("id"))    fields.add(alias.CUSTOMER_ID);
    if (sel.contains("email")) fields.add(alias.EMAIL_ADDRESS);
    return fields;
}
```

**`rewrite.types.CustomerFields`** — one method per field, wiring by method reference:
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

Generates one fetcher method in `rewrite.types.QueryFields` per root field, delegating to `TableName.selectMany/One`.

```java
// rewrite.types.QueryFields
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

### I1 — `GraphitronWiringClassGenerator` *(TableClassGenerator and FieldsClassGenerator done)*

`TableClassGenerator` generates one stub class per SQL table. `FieldsClassGenerator` generates one `<TypeName>Fields.java` per GraphQL output type with one static stub per field and a `wiring()` method. The remaining work:

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

### G6 — Split fields: `LookupQueryField`, table mapped `LookupTableField`, result mapped `TableField`, result mapped `LookupTableField`

G6 generates, per affected field, a pair of methods in `rewrite.types.<TypeName>Fields`:
1. A **data fetcher** — `static CompletableFuture<T> fieldName(DataFetchingEnvironment env)` — always async. For `LookupQueryField` and table-mapped `LookupTableField`, wraps a synchronous DB call in `CompletableFuture.completedFuture`. For result mapped fields, registers/retrieves a DataLoader.
2. A **bespoke DB method** — named `lookup<FieldName>`, `subselect<FieldName>`, or `load<FieldName>` — always synchronous. Contains all SQL logic specific to this field.

---

#### G6 field categories

| Category | DataLoader | Derived tables present | `@condition` / non-`@lookupKey` args | Pagination |
|---|---|---|---|---|
| **Lookup field** (`LookupQueryField`) | No — synchronous | Derived target only | Blocked (lookup invariant) | Never — result count = M exactly |
| **Table mapped `LookupTableField`** (`@splitQuery` + `@lookupKey`, table-mapped parent) | No — correlated subquery | Derived target only + correlated parent join | Blocked (lookup invariant) | Never |
| **Result mapped `TableField`** (`@splitQuery`, no `@lookupKey` args) | Yes | Derived source only | Allowed — become WHERE on target | Allowed |
| **Result mapped `LookupTableField`** (`@splitQuery` + `@lookupKey` args, result-mapped parent) | Yes | Both | Blocked (lookup invariant) | Never — result count = N × M |

**Derived source table** — a SQL `VALUES(…)` derived table built from parent source records. Contains the FK-relevant columns from the parent: the parent's PK/unique-key columns when the FK is on the child side, or the parent's FK columns when the FK is on the parent side.

**Derived target table** — a SQL `VALUES(…)` derived table built from `@lookupKey` argument values, read from `SelectedField`. Each argument value (or list element) is one row. Arguments without `@lookupKey` are never part of this table.

**The derived target table is identical for every source in a batch.** The DataLoader is keyed to the GraphQL execution path, so all N parent records dispatched in the same batch share the same request arguments. M — the number of lookup rows — is therefore a constant for the entire batch. The derived target table is built once, not once per source.

**Lookup invariant**: because the derived target table is constant (M is fixed) and `@condition` is blocked, the result count is always exactly N × M. Positional alignment is unambiguous: result at position `(i, j)` corresponds to source row `i` and lookup row `j`.

---

#### Bespoke method signatures

Arguments are unpacked from `SelectedField` inside the bespoke method — they are never passed as separate parameters. `DataFetchingEnvironment` is used only for context (`DSLContext`, `contextArgument`, tenant ID, etc.). `SelectedField` drives both SELECT list assembly and argument unpacking.

| Category | Bespoke method signature | Return type |
|---|---|---|
| Lookup field (`LookupQueryField`) | `static List<Record> lookup<FieldName>(DataFetchingEnvironment env, SelectedField sel)` | `List<Record>` — M results total |
| Table mapped LookupTableField | `static Field<Result<Record>> subselect<FieldName>(<ParentAlias> parentAlias, SelectedField sel)` | `Field<Result<Record>>` — jOOQ multiset subquery expression, embedded in parent SELECT |
| Result mapped TableField — returns `[T]` | `static List<List<Record>> load<FieldName>(List<Row> sourceRows, DataFetchingEnvironment env, SelectedField sel)` | `List<List<Record>>` — one inner list per source |
| Result mapped TableField — returns `T` | `static List<Record> load<FieldName>(List<Row> sourceRows, DataFetchingEnvironment env, SelectedField sel)` | `List<Record>` — one Record per source |
| Result mapped TableField — paginated | `static List<List<Record>> load<FieldName>Page(List<Row> sourceRows, DataFetchingEnvironment env, SelectedField sel)` | `List<List<Record>>` — one page per source |
| Result mapped LookupTableField | `static List<List<Record>> lookup<FieldName>(List<Row> sourceRows, DataFetchingEnvironment env, SelectedField sel)` | `List<List<Record>>` — N inner lists, each up to M records |

---

#### SQL structure

Each bespoke method builds an indexed `VALUES(…)` derived table (prepending a 1-based `idx` to each row), JOINs it against the target table, and partitions results back to one entry per input row using `idx`.

- **Derived target only (LookupQueryField)**: one `JOIN` — derived target ↔ target table. One `idx` column. Results are M rows total, ordered by `idx`.
- **Derived target + correlated parent join (table-mapped LookupTableField)**: built as a `DSL.multiset(…)` correlated subquery. The FK join condition back to the parent row is baked into the generated method. The derived target table (`VALUES(…)`) is built from `sel.getArguments()` at execution time. No `idx` needed — multiset returns all matching rows per parent naturally.
- **Derived source only (result mapped TableField)**: one `JOIN` — derived source ↔ target. One `idx` column.
- **Both derived tables (result mapped LookupTableField)**: two separate `JOIN`s — derived source ↔ target, derived target ↔ target. Two `idx` columns (`src_idx`, `tgt_idx`). NOT a pre-join of derived source × derived target (that would produce an N×M intermediate before hitting the target). Result at position `(i, j)` is retrieved by `src_idx = i+1 AND tgt_idx = j+1`.

---

#### Example sketches

**`rewrite.types.LanguageFields`** — `LookupTableField`, table-mapped parent (correlated subquery, no DataLoader):
```java
// Data fetcher — async wrapper around source Record extraction; no DataLoader
public static CompletableFuture<Result<Record>> filmsByTitle(DataFetchingEnvironment env) {
    return CompletableFuture.completedFuture(
        ((Record) env.getSource()).get("filmsByTitle", Result.class));
}

// Bespoke subquery-building method — called from Language.fields() during parent SELECT assembly.
// parentAlias is the Language table alias already in scope.
// sel carries both sub-field selection AND @lookupKey argument values.
// FK condition (film.language_id = parentAlias.language_id) is baked in.
// Derived target table: VALUES(title_1), (title_2), … built from sel.getArguments().
public static Field<Result<Record>> subselectFilmsByTitle(Language parentAlias, SelectedField sel) {
    throw new UnsupportedOperationException();
}
```

Called from `Language.fields(Language alias, DataFetchingFieldSelectionSet sel)`:
```java
if (sel.contains("filmsByTitle")) {
    SelectedField filmsByTitleSel = sel.getField("filmsByTitle");
    fields.add(LanguageFields.subselectFilmsByTitle(alias, filmsByTitleSel).as("filmsByTitle"));
}
```

**`rewrite.types.LanguageFields`** — result mapped TableField, FK on child, returns `[T]`:
```java
// Data fetcher
public static CompletableFuture<List<Record>> films(DataFetchingEnvironment env) {
    String name = loaderName(env.getExecutionStepInfo().getPath(),
        graphitronContext(env).getTenantId(env));
    DataLoader<Row, List<Record>> loader = env.getDataLoaderRegistry()
        .computeIfAbsent(name, k -> DataLoaderFactory.newDataLoaderWithContext(
            (keys, batchEnv) -> {
                DataFetchingEnvironment dfe = (DataFetchingEnvironment) batchEnv.getKeyContextsList().get(0);
                SelectedField sel = dfe.getSelectionSet().getField("films");
                return CompletableFuture.completedFuture(loadFilms(keys, dfe, sel));
            }));
    Row key = DSL.row(((Record) env.getSource()).get(LANGUAGE.LANGUAGE_ID));
    return loader.load(key, env);
}

// Bespoke batch method — joins derived source (language IDs) against film table
public static List<List<Record>> loadFilms(List<Row> sourceRows, DataFetchingEnvironment env, SelectedField sel) {
    throw new UnsupportedOperationException();
}
```

**`rewrite.types.FilmFields`** — result mapped TableField, FK on parent, returns `T`:
```java
public static CompletableFuture<Record> language(DataFetchingEnvironment env) { … }

public static List<Record> loadLanguage(List<Row> sourceRows, DataFetchingEnvironment env, SelectedField sel) {
    throw new UnsupportedOperationException();
}
```

**`rewrite.types.QueryFields`** — `LookupQueryField` (synchronous, no DataLoader):
```java
// Data fetcher — async wrapper around synchronous DB call; no DataLoader
public static CompletableFuture<List<Record>> filmById(DataFetchingEnvironment env) {
    SelectedField sel = env.getSelectionSet().getField("filmById");
    return CompletableFuture.completedFuture(lookupFilmById(env, sel));
}

// Bespoke method — builds derived target table from sel.getArguments() @lookupKey values,
// JOINs against film table; returns M rows positionally aligned with lookup rows.
public static List<Record> lookupFilmById(DataFetchingEnvironment env, SelectedField sel) {
    throw new UnsupportedOperationException();
}
```

**`rewrite.types.LanguageFields`** — result mapped LookupTableField (`@splitQuery` + `@lookupKey`):
```java
public static CompletableFuture<List<Record>> filmsByTitle(DataFetchingEnvironment env) { … }

// Unpacks @lookupKey args from sel internally; N inner lists, each up to M records
public static List<List<Record>> lookupFilmsByTitle(List<Row> sourceRows, DataFetchingEnvironment env, SelectedField sel) {
    throw new UnsupportedOperationException();
}
```

`loaderName` is a private static helper in the `*Fields` class — GraphQL path-based, not SQL.

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
| `rewrite/generators/fields/TableCodeGenerator.java` | **Done** — four projection stubs (`selectMany`, `selectOne`, `subselectMany`, `subselectOne`); DataLoader batch methods are bespoke per-field in `FieldsCodeGenerator` |
| `rewrite/generators/fields/TableClassGenerator.java` | **Done** — iterates `TableType`s, uses `javaClassName` |
| `rewrite/generators/fields/FieldsCodeGenerator.java` | **Done** — one stub per field + `wiring()` by method reference |
| `rewrite/generators/fields/FieldsClassGenerator.java` | **Done** — iterates `TableType`s and `RootType`s |
| `rewrite/GraphitronWiringClassGenerator.java` | **New** |
