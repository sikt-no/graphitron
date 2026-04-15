# Query Generation Refactor — Type Class as Projection-Only

## Problem

The Type class (`TypeClassGenerator`) currently serves two roles:

1. **Projection** — `fields(sel)` builds the SELECT list from a `DataFetchingFieldSelectionSet`
2. **Execution** — `selectMany`, `selectOne`, `subselectMany`, `subselectOne`, and four batch-key methods run queries or build subquery expressions

This conflation causes several problems:

### Shared methods with fixed signatures don't fit all callers

`selectMany(env, condition, orderBy)` assumes every list query has the same shape. But root queries, paginated queries, lookup queries, and batch queries all have different execution needs. The result is overloads (two `selectMany` variants), special-case parameters (`seekValues`, `limit`, `extraFields`), and "consistency" parameters (`env` on `subselectMany` where it has no structural role).

### Table alias is hidden, blocking recursive composition

`fields(sel)` creates its own `table` local internally. When inline nested fields (G5) need the parent's table alias for correlated join conditions, there is no way to pass it. The parent type class must pass its alias to the child's `$fields` method so the child can construct correlated multiset expressions. The current design makes this impossible without adding yet another parameter to the already-overloaded shared methods.

### Batch-key method names encoded on the Type class

`selectManyByRowKeys`, `selectManyByRecordKeys`, etc. encode the key type in the method name, creating combinatorial growth. Each new `BatchKey` variant forces new methods on every Type class. These are field-specific concerns that belong closer to the field.

### Method count inflated with stubs

Every Type class gets 11 methods. Many are stubs (`UnsupportedOperationException`). Types used only as subselect targets never need `selectMany`; types never accessed via services never need batch-key methods.

### Reuse is the wrong goal for generated code

The shared execution methods attempt to reduce duplication across call sites. But in a code generation context, each call site can generate exactly the SQL it needs — the generator has full knowledge at generation time. Generating targeted code per call site is simpler and more direct than routing through shared parameterized methods.

---

## Design

### Type class: SQL expression construction, no execution

The Type class becomes a recursive projection tree builder. It receives its table alias as a parameter and never executes queries.

```java
class Film {
    public static List<Field<?>> $fields(
            DataFetchingFieldSelectionSet sel,
            FilmTable table,
            DataFetchingEnvironment env) {
        var fields = new ArrayList<Field<?>>();
        for (var entry : sel.getFieldsGroupedByResultKey().entrySet()) {
            var sf = entry.getValue().get(0);
            switch (sf.getName()) {
                case "title"       -> fields.add(table.TITLE);
                case "description" -> fields.add(table.DESCRIPTION);
                // G5: inline nested fields will appear here
                // case "language" -> fields.add(language(sf, table, env));
            }
        }
        return fields;
    }
}
```

**`$fields`** is prefixed with `$` to avoid collision with GraphQL fields named `fields`.

**`table` parameter** — typed as the concrete jOOQ table class (e.g. `no.sikt.jooq.tables.Film`). The caller provides the alias. This is the prerequisite for G5: when `$fields` later handles inline nested fields, it passes this alias to child subselect methods for correlated join conditions.

**`env` parameter** — threaded through for context argument extraction in custom conditions and fields. The Type class uses `env` for argument/context access but never for DSL context or execution.

### Fetchers class: execution entry points

Fetchers own everything that touches execution — DSL context extraction, query building, pagination, DataLoaders. They call `Type.$fields()` for projection and build the query around it.

```java
class QueryFetchers {
    static Result<Record> films(DataFetchingEnvironment env) {
        var dsl = graphitronContext(env).getDslContext(env);
        var table = Tables.FILM;
        var condition = DSL.noCondition();
        List<SortField<?>> orderBy = List.of();
        return dsl.select(Film.$fields(env.getSelectionSet(), table, env))
                  .from(table)
                  .where(condition)
                  .orderBy(orderBy)
                  .fetch();
    }

    static Record film(DataFetchingEnvironment env) {
        var dsl = graphitronContext(env).getDslContext(env);
        var table = Tables.FILM;
        var condition = DSL.noCondition();
        return dsl.select(Film.$fields(env.getSelectionSet(), table, env))
                  .from(table)
                  .where(condition)
                  .fetchOne();
    }
}
```

### Pagination inlined in the fetcher

```java
static ConnectionResult filmsConnection(DataFetchingEnvironment env) {
    var dsl = graphitronContext(env).getDslContext(env);
    var table = Tables.FILM;
    var condition = DSL.noCondition();
    var ordering = filmsOrderBy(env);
    var orderBy = ordering.sortFields();
    var extraFields = ordering.columns();
    Integer first = env.getArgument("first");
    String after = env.getArgument("after");
    int pageSize = first != null ? first : 100;
    Object[] seekValues = after != null ? ConnectionHelper.decodeCursor(after) : null;

    var fields = new ArrayList<>(Film.$fields(env.getSelectionSet(), table, env));
    for (var extra : extraFields) {
        if (!fields.contains(extra)) fields.add(extra);
    }

    var query = dsl.select(fields).from(table)
                   .where(condition).orderBy(orderBy);
    if (seekValues != null && seekValues.length > 0) query = query.seek(seekValues);
    return new ConnectionResult(
        query.limit(pageSize + 1).fetch(), pageSize, after, extraFields);
}
```

### Lookup queries inlined in the fetcher

```java
static Result<Record> lookupFilmById(DataFetchingEnvironment env) {
    var dsl = graphitronContext(env).getDslContext(env);
    var table = Tables.FILM;
    List<String> filmIdKeys = env.getArgument("film_id");
    var condition = FilmConditions.filmByIdCondition(
        table, filmIdKeys.stream().map(table.FILM_ID.getDataType()::convert).toList());
    List<SortField<?>> orderBy = List.of();
    return dsl.select(Film.$fields(env.getSelectionSet(), table, env))
              .from(table)
              .where(condition)
              .orderBy(orderBy)
              .fetch();
}
```

### Split query / service batch fields stay on the Fetchers class

Batch rows methods inline their own SQL. The Type class is involved only for projection:

```java
// Split query rows method (currently a stub — body TBD)
static List<List<Record>> rowsActors(
        List<Row1<Integer>> keys, DataFetchingEnvironment env, SelectedField sel) {
    throw new UnsupportedOperationException();
}

// Service field rows method (currently a stub — body TBD)
static List<List<Record>> loadRecommendations(
        List<Row1<Integer>> keys, DataFetchingEnvironment env, SelectedField sel) {
    var serviceResult = RecommendationService.recommend(keys, ...);
    var dsl = graphitronContext(env).getDslContext(env);
    var table = Tables.FILM;
    return dsl.select(Film.$fields(sel.getSelectionSet(), table, env))
              .from(table)
              .where(...)
              .fetch()
              .intoGroups(...);
}
```

### BatchKey simplification

`BatchKey.selectManyMethodName()` and `selectOneMethodName()` are removed — they existed solely to dispatch to Type class methods that no longer exist. `BatchKey` retains `javaTypeName()` for DataLoader key types and `keyColumns` for key extraction.

---

## What this sets up for G5

After this refactor, the Type class has one method (`$fields`) that receives its table alias as a parameter. Adding inline nested fields means:

1. Per-field methods on the Type class: `Film.language(sf, table, env)` returns a `Field<?>` (multiset expression with correlated join condition using `table`).

2. `$fields` switch dispatches to these methods alongside scalar column fields.

The recursive pattern:

```java
class Film {
    static List<Field<?>> $fields(SelectionSet sel, FilmTable table,
                                   DataFetchingEnvironment env) {
        // ...
        switch (sf.getName()) {
            case "title"    -> fields.add(table.TITLE);
            case "language" -> fields.add(language(sf, table, env));
        }
        // ...
    }

    static Field<?> language(SelectedField sf, FilmTable table,
                             DataFetchingEnvironment env) {
        var lang = Tables.LANGUAGE;
        return DSL.multiset(
            DSL.select(Language.$fields(sf.getSelectionSet(), lang, env))
               .from(lang)
               .where(table.LANGUAGE_ID.eq(lang.LANGUAGE_ID))
        ).as(sf.getResultKey())
         .convertFrom(r -> r.isEmpty() ? null : r.get(0));
    }
}
```

Each type class mirrors the GraphQL type. Each method mirrors a field. The table alias threads through the recursion, enabling correct correlated subqueries at any nesting depth.

---

## Implementation Steps

### Step 1: Rename and re-sign `fields()` in TypeClassGenerator

**File:** `graphitron-rewrite/.../generators/TypeClassGenerator.java`

- Rename `fields` to `$fields`
- Add `table` parameter (concrete jOOQ table class) — replaces the internal `table` local
- Add `env` parameter (`DataFetchingEnvironment`)
- Remove the `fields(sel, extraFields)` overload — the fetcher handles extra-field merging

### Step 2: Remove execution methods from TypeClassGenerator

**File:** `graphitron-rewrite/.../generators/TypeClassGenerator.java`

Remove from `buildTypeSpec` and delete the builder methods:
- `buildSelectManyMethod`, `buildSelectManyPaginatedMethod`
- `buildSelectOneMethod`
- `buildSubselectManyMethod`, `buildSubselectOneMethod`
- `buildSelectManyFromRowServiceMethod`, `buildSelectOneFromRowServiceMethod`
- `buildSelectManyFromRecordServiceMethod`, `buildSelectOneFromRecordServiceMethod`
- `sortFieldList()` helper

### Step 3: Inline query execution in TypeFetcherGenerator

**File:** `graphitron-rewrite/.../generators/TypeFetcherGenerator.java`

| Method | Current delegation | New pattern |
|---|---|---|
| `buildQueryTableFetcher` (list) | `Type.selectMany(env, condition, orderBy)` | `dsl.select(Type.$fields(sel, table, env)).from(table).where(condition).orderBy(orderBy).fetch()` |
| `buildQueryTableFetcher` (single) | `Type.selectOne(env, condition)` | Same chain with `.fetchOne()` |
| `buildQueryConnectionFetcher` | `Type.selectMany(env, condition, orderBy, extraFields, seekValues, limit)` | Inlined paginated query with extra-field merge, seek, limit |
| `buildQueryLookupRowsMethod` | `Type.selectMany(env, condition, orderBy)` | Same as list pattern |
| `buildServiceRowsMethod` | `Type.selectManyByRowKeys(...)` / `Type.selectOneByRowKeys(...)` | Stub throws `UnsupportedOperationException` directly in the rows method |

Expand `needsGraphitronContextHelper` to be `true` whenever any query-executing field exists (not just context-arg fields), since all fetchers now need `graphitronContext(env).getDslContext(env)`.

### Step 4: Clean up BatchKey

**File:** `graphitron-rewrite/.../model/BatchKey.java`

Remove `selectManyMethodName()` and `selectOneMethodName()` from the sealed interface and all implementations.

### Step 5: Update tests

**`TypeClassGeneratorTest`:**
- `generate_allMethodsArePresent` → assert only `"$fields"`
- Remove signature tests for all removed methods (9 tests)
- Update `fields_signature` → verify `$fields` with 3 parameters `[sel, table, env]`
- Remove `fieldsWithExtra_signature`

**`TablePipelineTest`:**
- Remove `subselectMany_usesMultiset`, `subselectOne_usesMultisetWithLimit`, `subselectMany_tableRefIsCorrectForSchema`
- Update `fieldsMethod_*` tests for `$fields` name and parameter-based table

**`TypeFetcherGeneratorTest`:**
- Update body assertions: `contains("selectMany")` → `contains("$fields")` + `contains(".fetch()")`
- Service field tests: rows method no longer delegates to batch-key methods
- Connection/pagination: update for inlined query pattern

**`FetcherPipelineTest`:**
- `queryTableField_list_delegatesToSelectMany` → assert `contains("$fields")` and `contains(".fetch()")`
- `queryTableField_single_delegatesToSelectOne` → assert `contains("$fields")` and `contains(".fetchOne()")`

### Step 6: Verify

```
mvn test -pl :graphitron-rewrite                    # unit + pipeline tests
mvn compile -pl :graphitron-rewrite-test-spec       # generated code compiles
mvn test -pl :graphitron-rewrite-test-spec          # execution tests pass
```

---

## What gets removed

| Component | Removed |
|---|---|
| `TypeClassGenerator` | 9 builder methods, 10 generated methods per type class |
| `BatchKey` | `selectManyMethodName()`, `selectOneMethodName()` on interface + 3 implementations |
| `TypeFetcherGenerator` | All `$T.selectMany(...)` / `$T.selectOne(...)` delegation statements |
| Tests | ~12 tests for removed method signatures and delegation patterns |

## What stays unchanged

| Component | Status |
|---|---|
| `TypeConditionsGenerator` | Unchanged — still generates pure-function WHERE predicates |
| `GraphitronWiringClassGenerator` | Unchanged |
| `ConnectionHelper`, `ConnectionResult`, `OrderByResult` | Unchanged — still generated utility classes |
| `ColumnFetcherClassGenerator` | Unchanged |
| DataLoader structure in fetchers | Unchanged (async fetchers, batch key extraction) |
| OrderBy helper methods on fetchers | Unchanged |
| Field classification model (`GraphitronField`, `ChildField`, `QueryField`) | Unchanged |
