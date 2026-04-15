# Testing Strategy

What's in place, what works well, and concrete improvements for the rewrite pipeline's test suite.

## Current test pyramid

### Level 1 — Builder classification tests (`GraphitronSchemaBuilderTest`)

2,176 lines. Tests the SDL → `GraphitronSchema` classification. Each section is an enum of `(sdl, assertion)` cases; parameterized tests iterate the truth table. Inline SDL strings feed into `GraphitronSchemaBuilder.build()`, and assertions check the resulting model types and field components.

**What works well:**
- Exhaustive — covers every field and type variant
- Enum-based parameterization makes it easy to add cases
- Tests classification outcomes (which variant, which components) without touching generators
- Inline SDL makes each case self-contained

**Problems:**
- The `buildSchema()` helper is duplicated between this test and `FetcherPipelineTest` — both parse directives from a hardcoded relative path to `graphitron-common/src/main/resources/directives.graphqls`.

### Level 2 — Validation tests (`validation/*.java`)

~3,900 lines across 49 test classes. Each test class covers one field or type variant. Uses `ValidatorCase` interface + enum parameterization: each enum constant builds a `GraphitronField` directly (no SDL parsing), declares expected error messages, and a one-line parameterized test asserts them.

**What works well:**
- Clean pattern — `ValidatorCase` + `FieldValidationTestHelper` give every test the same shape
- One class per variant keeps files small and navigable
- Tests construct model objects directly, so they don't depend on the builder being correct
- Error messages are asserted precisely

**Problems:**
- Model objects are constructed with long constructor calls (8-12 args for `ServiceTableField`, `SplitLookupTableField`, etc.). As record components change, every test that constructs that variant must be updated. Could use builder/factory helpers.
- The `FieldValidationTestHelper.validate(field)` shortcut wraps every field in a `RootType` parent. Tests that need a `TableType` parent (e.g., PK validation for service fields) must use the longer `validate(schema(...))` path, which is less discoverable.

### Level 3 — Generator unit tests (`TypeFetcherGeneratorTest`, `TypeClassGeneratorTest`)

~420 + ~165 lines. Tests structural properties of generated `TypeSpec` objects: method names, return types, parameter signatures, whether a method is a stub. Does **not** assert on generated code body strings (per CLAUDE.md instructions), except for checking method-reference names in wiring code.

**What works well:**
- Structural assertions are refactor-resilient
- Tests construct model objects directly (no SDL), so they isolate the generator from the builder
- Helper methods (`method()`, `columnField()`, `queryTableField()`) reduce noise

**Problems:**
- Helper methods for model construction are duplicated between `TypeFetcherGeneratorTest` and validation tests. Both build `TableRef`, `ColumnRef`, `ReturnTypeRef`, `FieldWrapper` objects from scratch. A shared test fixture library would reduce this.
- No tests for `TypeConditionsGenerator` structural output (only tested indirectly through the pipeline test that checks "a conditions class exists with a method named X").

### Level 4 — Pipeline tests (`FetcherPipelineTest`, `TablePipelineTest`)

~297 + ~198 lines. End-to-end SDL → `GraphitronSchema` → generated `TypeSpec` list. Verifies that the full pipeline produces the expected class names, method names, and wiring entries for a given schema.

**What works well:**
- Tests the full stack: SDL parsing → builder classification → generator output
- Inline SDL keeps tests readable
- Catches integration issues between builder and generator

**Problems:**
- `buildSchema()` depends on a relative filesystem path to `directives.graphqls` (`"../graphitron-common/src/main/resources/directives.graphqls"`). Fragile if the module moves. Should load from classpath.
- Some tests duplicate assertions already in the unit tests (e.g., "class name is TypeName plus Fetchers" appears in both `TypeFetcherGeneratorTest` and `FetcherPipelineTest`).

### Level 5 — Compilation test (`graphitron-rewrite-test-spec` compile phase)

The Maven build runs `graphitron-maven-plugin:generate` against a real schema with real jOOQ classes, then `maven-compiler-plugin` compiles the output. If generated code references wrong types, missing columns, or ambiguous overloads, the build fails.

**What works well:**
- Catches real type errors that structural tests can't see
- Runs against actual jOOQ-generated classes from a real database
- No test code needed — the compiler IS the test

**Problems:**
- Silent — a passing build says nothing; only failures are visible. There's no assertion that specific classes were generated or that the class count matches expectations. A generator bug that silently drops a class would pass compilation.
- The schema (`schema.graphqls`) is small. Only `Customer`, `Film`, `Language`, and `Query`. Doesn't exercise mutations, polymorphic types, `@record` types, or most `TableTargetField` variants.

### Level 6 — Execution test (`GraphQLQueryTest`)

264 lines, 16 test methods. Starts a PostgreSQL container, loads schema + seed data, builds a GraphQL engine with the generated wiring, and runs actual queries.

**What works well:**
- True end-to-end: GraphQL query → generated code → jOOQ SQL → PostgreSQL → result
- Tests filtering (boolean, enum, text-enum), ordering, projection, lookup keys
- Catches runtime issues that compilation alone can't find (wrong column names at runtime, incorrect condition logic)

**Problems:**
- Two separate `init.sql` files (fixtures: 225 lines, test-spec: 189 lines) that are nearly but not exactly identical. They drift. The fixtures version has `actor`/`film_actor` tables the test version lacks; the test version has `text_rating` data the fixtures version doesn't. Should share a single source of truth.
- Different PostgreSQL versions: fixtures uses 18, execution test uses 16. This can mask version-specific behavior.
- `GraphitronContext` is mocked inline with an anonymous class. `getContextArgument()` returns `null` unconditionally — context arguments are never actually tested end-to-end.
- No execution tests for DataLoader/batch loading paths (service fields, split queries). The `@splitQuery` on `Language.films` is in the schema but there's no test that exercises it — it throws `UnsupportedOperationException` at runtime.

## Concrete improvements

### 1. Unify `init.sql` and align PostgreSQL versions

The two init.sql files should be one. The fixtures module defines the schema for jOOQ code generation; the test-spec module should reuse the same SQL at test time. The test-spec already depends on fixtures at compile scope — extend this to cover the SQL resource too.

Also align PostgreSQL versions: fixtures uses 18, execution test uses 16. Both should use the same major version. One-line fix in `GraphQLQueryTest`.

### 2. Shared `buildSchema()` via classpath

Both `FetcherPipelineTest` and `GraphitronSchemaBuilderTest` load `directives.graphqls` via a fragile relative path. The directives file should be on the test classpath (it's already available as a resource in `graphitron-common`, which is a dependency). Replace:

```java
String directives = SchemaReadingHelper.fileAsString(
    Paths.get("../graphitron-common/src/main/resources/directives.graphqls"));
```

with a classpath load from the test helper.

### 3. Shared model fixture factories

`TypeFetcherGeneratorTest`, `TypeClassGeneratorTest`, and the validation tests all construct the same model objects (`TableRef`, `ColumnRef`, `ReturnTypeRef.TableBoundReturnType`, `FieldWrapper`, etc.) with slightly different values. Extract a `TestFixtures` class with factory methods:

```java
static TableRef filmTable() { ... }
static TableRef filmTable(List<ColumnRef> pkColumns) { ... }
static ReturnTypeRef.TableBoundReturnType tableBound(String typeName, TableRef table, boolean isList) { ... }
static ChildField.ColumnField columnField(String name, String columnName) { ... }
```

This reduces the 8-12 arg constructor calls to one-liners, and when record components change, only the factories need updating.

### 4. Add a compilation smoke-test assertion

Add a test in `graphitron-rewrite-test-spec` that runs after code generation and asserts the generated source directory contains the expected files:

```java
@Test
void generatedSourcesContainExpectedClasses() {
    assertThat(Paths.get("target/generated-sources/.../rewrite/types"))
        .isDirectoryContaining("glob:**FilmFetchers.java")
        .isDirectoryContaining("glob:**CustomerFetchers.java")
        .isDirectoryContaining("glob:**Film.java");
}
```

This catches the "silently dropped class" failure mode that compilation alone misses.

### 5. Expand test-spec schema coverage

The current schema exercises only `QueryTableField`, `QueryLookupTableField`, `ColumnField`, and `SplitTableField` (as a stub). As generator stubs are implemented, the test-spec schema should grow to cover:

- `@service` field with a real service class (execution test with DataLoader)
- `@record` type with `RecordTableField`
- Mutation fields (INSERT/UPDATE/DELETE)
- `@condition` with a developer-supplied method
- Inline `TableField` (subquery via `@reference`, once G5 is implemented)

Each addition extends the schema, adds seed data to `init.sql`, and adds a test method in `GraphQLQueryTest`. The compilation test comes for free.

### 6. Add `TypeConditionsGenerator` unit tests

The conditions generator has no dedicated unit tests. Its output is only tested indirectly (pipeline test checks a method name exists; execution test verifies filter results). Add structural tests parallel to `TypeFetcherGeneratorTest`:
- Method names and signatures per `GeneratedConditionFilter`
- Parameter types match column types
- Nullable params get null-guard in the body
- `TextMapLookup` generates a static map field with correct entries

### 7. Test context arguments end-to-end

`GraphQLQueryTest`'s mock `GraphitronContext.getContextArgument()` returns `null` unconditionally. Add a test that:
- Defines a `@condition` or `@service` method that reads a context argument
- The test's `GraphitronContext` returns a real value
- The query result reflects the context-filtered data

This covers the `CallSiteExtraction.ContextArg` path in the generated code.

### 8. Split `GraphitronSchemaBuilderTest`

The file is 2,176 lines but it's well-sectioned internally (23 `// ===== SectionName =====` blocks, each with its own enum). It reads more like 23 small test classes sharing a file and a `build()` helper. Splitting would create ~23 new files with import boilerplate — mechanical busywork with limited navigation payoff given the clear internal structure. Nice to have, not urgent.

## Priority

| Order | Improvement | Impact | Effort |
|---|---|---|---|
| 1 | Unify `init.sql` + align PG versions | Eliminates drift risk, one-line PG fix rides along | Small |
| 2 | Shared `buildSchema()` via classpath | Removes fragile path, enables module relocation | Small |
| 3 | Shared model fixture factories | Reduces constructor noise across 49+ test files | Medium |
| 4 | Compilation smoke-test assertion | Tiny effort, catches silent class drops | Small |
| 5 | Expand test-spec schema (ongoing) | Tracks generator implementation | Ongoing |
| 6 | `TypeConditionsGenerator` unit tests | Fills structural test gap | Small |
| 7 | Test context arguments end-to-end | Covers untested extraction path | Small |
| 8 | Split `GraphitronSchemaBuilderTest` | Nice to have — file is already well-sectioned | Medium |
