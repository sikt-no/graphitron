# Query Generation Refactor — Type Class as Projection-Only

## Problem

The Type class (`TypeClassGenerator`) currently serves two roles:

1. **Projection** — `fields(sel)` builds the SELECT list from a `DataFetchingFieldSelectionSet`
2. **Execution** — `selectMany`, `selectOne`, `subselectMany`, `subselectOne`, and four batch-key
   methods run queries or build subquery expressions

This conflation causes several problems:

### Shared methods with fixed signatures don't fit all callers

`selectMany(env, condition, orderBy)` assumes every list query has the same shape. But root
queries, paginated queries, lookup queries, and batch queries all have different execution needs.
The result is overloads (two `selectMany` variants), special-case parameters (`seekValues`,
`limit`, `extraFields`), and "consistency" parameters (`env` on `subselectMany` where it has no
structural role).

### Table alias is hidden, blocking recursive composition

`fields(sel)` creates its own `table` local internally. When inline nested fields (G5) need the
parent's table alias for correlated join conditions, there is no way to pass it. The parent type
class must pass its alias to the child's `$fields` method so the child can construct correlated
multiset expressions. The current design makes this impossible without adding yet another parameter
to the already-overloaded shared methods.

### Batch-key method names encoded on the Type class

`selectManyByRowKeys`, `selectManyByRecordKeys`, etc. encode the key type in the method name,
creating combinatorial growth. Each new `BatchKey` variant forces new methods on every Type class.
These are field-specific concerns that belong closer to the field.

### Method count inflated with stubs

Every Type class gets 11 methods. Many are stubs (`UnsupportedOperationException`). Types used
only as subselect targets never need `selectMany`; types never accessed via services never need
batch-key methods.

### Reuse is the wrong goal for generated code

The shared execution methods attempt to reduce duplication across call sites. But in a code
generation context, each call site can generate exactly the SQL it needs — the generator has full
knowledge at generation time. Generating targeted code per call site is simpler and more direct
than routing through shared parameterised methods.

---

## Design

### Type class: SQL expression construction, no execution

The Type class becomes a recursive projection tree builder. It receives its table alias as a
parameter and never executes queries.

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

**`$fields` is `public static`.** Fetchers classes (e.g. `QueryFetchers`) call it cross-class, so
`public` is required. The `$` prefix is chosen because the GraphQL specification defines field
names as `/[_A-Za-z][_0-9A-Za-z]*/` — a name starting with `$` is impossible by spec, so
`$fields` can never collide with a GraphQL field name regardless of the schema.

**`table` parameter** — typed as the concrete jOOQ table class (e.g. `FilmTable`). The caller
provides the alias. This is the prerequisite for G5: when `$fields` later handles inline nested
fields, it passes this alias to per-field methods for correlated join conditions.

**`env` parameter** — included now rather than deferred to G5. G5 is the immediate next roadmap
item; omitting `env` here would require migrating this signature across every generated type class
again one step later. The parameter is unused in this refactor but costs only one extra argument
per `$fields` call.

### Fetchers class: execution entry points

Fetchers own everything that touches execution — DSL context extraction, query building,
pagination, DataLoaders. They call `Type.$fields()` for projection and build the query around it.

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

Extra ordering columns (cursor fields) are merged into the select list by name, not by reference,
to avoid dependence on jOOQ `Field.equals()` semantics.

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
    var selectedNames = fields.stream().map(Field::getName).collect(toSet());
    for (var extra : extraFields) {
        if (!selectedNames.contains(extra.getName())) fields.add(extra);
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

### Split query / service batch fields

Split query and service rows methods remain stubs (`UnsupportedOperationException`). The only
change is that service rows methods no longer delegate to batch-key method names on the type class
— they throw directly, exactly like split query rows methods already do. The eventual implemented
bodies will call `Type.$fields(sel.getSelectionSet(), table, env)` for projection, but that work
is out of scope here.

### BatchKey simplification

`BatchKey.selectManyMethodName()` and `selectOneMethodName()` are removed — they existed solely
to dispatch to Type class methods that no longer exist. `BatchKey` retains `javaTypeName()` for
DataLoader key types and `keyColumns` for key extraction. `ObjectBased` currently throws
`UnsupportedOperationException` on both removed methods; its removal is compatible with both
roadmap options for ObjectBased batch loading (collapse into `RecordKeyed` or implement a new
rows method), since neither option requires the removed dispatch interface.

---

## What this sets up for G5

After this refactor, the Type class has one method (`$fields`) that receives its table alias and
env as parameters. Adding inline nested fields means:

1. Per-field methods on the Type class: `Film.language(sf, table, env)` returns a `Field<?>`
   (multiset expression with correlated join condition using `table`).

2. `$fields` switch dispatches to these methods alongside scalar column fields.

The recursive pattern:

```java
class Film {
    public static List<Field<?>> $fields(DataFetchingFieldSelectionSet sel,
                                          FilmTable table,
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

Each type class mirrors the GraphQL type. Each method mirrors a field. The table alias threads
through the recursion, enabling correct correlated subqueries at any nesting depth.

---

## Implementation Steps

Steps are ordered so the codebase compiles and tests pass at every intermediate state.

### Step 1: Inline query execution in TypeFetcherGenerator

**File:** `graphitron-rewrite/.../generators/TypeFetcherGenerator.java`

Stop delegating to the Type class for execution. Build the query inline at each call site using
the table local and `Type.$fields(sel, table, env)` for projection. The Type class still has the
old `fields(sel)` signature at this point — update the calls to `$fields(sel, table, env)` once
the Type class is updated in step 2.

| Method | Current delegation | New pattern |
|---|---|---|
| `buildQueryTableFetcher` (list) | `Type.selectMany(env, condition, orderBy)` | `dsl.select(Type.$fields(sel, table, env)).from(table).where(condition).orderBy(orderBy).fetch()` |
| `buildQueryTableFetcher` (single) | `Type.selectOne(env, condition)` | Same chain with `.fetchOne()` |
| `buildQueryConnectionFetcher` | `Type.selectMany(env, condition, orderBy, extraFields, seekValues, limit)` | Inlined paginated query with name-based extra-field merge, seek, limit |
| `buildQueryLookupRowsMethod` | `Type.selectMany(env, condition, orderBy)` | Same as list pattern |
| `buildServiceRowsMethod` | `Type.selectManyByRowKeys(...)` / `Type.selectOneByRowKeys(...)` | Throw `UnsupportedOperationException` directly — no type class delegation |

Update `needsGraphitronContextHelper` to emit the helper whenever the fetchers class contains at
least one `SqlGeneratingField` — i.e., whenever there is a field that executes inline SQL rather
than stubbing. All such fetchers now need `graphitronContext(env).getDslContext(env)` directly.

### Step 2: Reshape TypeClassGenerator

**File:** `graphitron-rewrite/.../generators/TypeClassGenerator.java`

- Rename `buildFieldsMethod` → generates `$fields` (public static, 3 parameters: `sel`, `table`,
  `env`) — replaces the internal `table` local with the parameter
- Remove `buildFieldsWithExtraMethod` — the fetcher now handles extra-field merging
- Remove execution builder methods and their `sortFieldList()` helper:
  - `buildSelectManyMethod`, `buildSelectManyPaginatedMethod`
  - `buildSelectOneMethod`
  - `buildSubselectManyMethod`, `buildSubselectOneMethod` — these were never called from
    `TypeFetcherGenerator` and are dead code; their removal is safe
  - `buildSelectManyFromRowServiceMethod`, `buildSelectOneFromRowServiceMethod`
  - `buildSelectManyFromRecordServiceMethod`, `buildSelectOneFromRecordServiceMethod`

### Step 3: Clean up BatchKey

**File:** `graphitron-rewrite/.../model/BatchKey.java`

Remove `selectManyMethodName()` and `selectOneMethodName()` from the sealed interface and all
three implementations (`RowKeyed`, `RecordKeyed`, `ObjectBased`).

### Step 4: Remove dead tests

Tests that verified the now-removed execution methods and delegation patterns should be **deleted**,
not updated with different body-string assertions. Body-content assertions test implementation
details that break on every refactor; correctness coverage transfers to the compilation and
execution tests in step 5.

**`TypeClassGeneratorTest` — delete:**
- `fieldsWithExtra_signature`
- `selectMany_signature`, `selectManyPaginated_signature`
- `selectOne_signature`
- `subselectMany_signature`, `subselectOne_signature`
- `selectManyByRowKeys_signature`, `selectOneByRowKeys_signature`
- `selectManyByRecordKeys_signature`, `selectOneByRecordKeys_signature`

**`TypeClassGeneratorTest` — update:**
- `generate_allMethodsArePresent` → assert only `"$fields"` is present
- `fields_signature` → rename to `$fields_signature`; verify `public static`, name `"$fields"`,
  parameters `[sel: DataFetchingFieldSelectionSet, table: <ConcreteTable>, env: DataFetchingEnvironment]`

**`TablePipelineTest` — delete:**
- `subselectMany_usesMultiset`, `subselectOne_usesMultisetWithLimit`,
  `subselectMany_tableRefIsCorrectForSchema` — these tested dead code

**`TablePipelineTest` — update:**
- `fieldsMethod_*` tests: update for `$fields` name, `public static` modifier, and
  parameter-based table (no internal local)

**`TypeFetcherGeneratorTest` — delete:**
- Any test asserting `contains("selectMany")`, `contains("selectOne")`,
  `contains("selectManyByRowKeys")`, or equivalent delegation strings
- These include the service field rows tests that verified dispatch to batch-key method names

**`TypeFetcherGeneratorTest` — keep / add:**
- Structural tests (return types, parameter signatures, method presence) are unaffected
- OrderBy helper method tests are unaffected

**`FetcherPipelineTest` — delete:**
- `queryTableField_list_delegatesToSelectMany`
- `queryTableField_single_delegatesToSelectOne`
- Any further pipeline tests asserting `contains("selectMany")` / `contains("selectOne")`

### Step 5: Verify

```
mvn test -pl :graphitron-rewrite                    # unit + pipeline tests
mvn compile -pl :graphitron-rewrite-test-spec       # generated code compiles against real jOOQ
mvn test -pl :graphitron-rewrite-test-spec          # execution tests pass against real database
```

---

## What gets removed

| Component | Removed |
|---|---|
| `TypeClassGenerator` | 10 builder methods (`buildFieldsWithExtraMethod` + 9 execution builders including `sortFieldList()`); 10 generated methods per type class (the old `fields(sel, extra)` overload + 9 execution methods; `fields(sel)` is replaced by `$fields(sel, table, env)`, not removed) |
| `BatchKey` | `selectManyMethodName()`, `selectOneMethodName()` on interface + all 3 implementations |
| `TypeFetcherGenerator` | All `$T.selectMany(...)` / `$T.selectOne(...)` / `$T.selectManyByRowKeys(...)` delegation statements |
| Tests | ~14 tests for removed method signatures and body-delegation assertions |

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
