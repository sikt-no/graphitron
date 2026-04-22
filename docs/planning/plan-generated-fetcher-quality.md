# Plan — Generated-fetcher quality pass

> **Status:** Spec
>
> Four independent cleanups to the code `TypeFetcherGenerator` emits, motivated by the
> current `filmsOrderedConnection` shape in `graphitron-rewrite-test-spec`. None is a
> behaviour change — each trims boilerplate, removes a name collision, or tightens
> generated-code style. Items can land in any order; the pagination-helper and
> QueryConditions items are the biggest wins, the other two are smaller-scoped.

## Reference snippet

Current emission for a connection fetcher (elided to the parts this plan touches):

```java
public static ConnectionResult filmsOrderedConnection(DataFetchingEnvironment env) {
    no.sikt.graphitron.rewrite.test.jooq.tables.Film table = Tables.FILM;
    Condition condition = DSL.noCondition();
    condition = condition.and(FilmConditions.filmsOrderedConnectionCondition(
        table, env.getArgument("rating") != null ? MpaaRating.valueOf(env.<String>getArgument("rating")) : null));
    var ordering = filmsOrderedConnectionOrderBy(env);
    List<SortField<?>> orderBy = ordering.sortFields();
    List<Field<?>> extraFields = ordering.columns();
    Integer first  = env.getArgument("first");
    Integer last   = env.getArgument("last");
    String  after  = env.getArgument("after");
    String  before = env.getArgument("before");
    if (first != null && last != null)
        throw new IllegalArgumentException("first and last must not both be specified");
    boolean backward = last != null;
    int pageSize = backward ? last : (first != null ? first : 100);
    String cursor = backward ? before : after;
    Field[] seekFields = ConnectionHelper.decodeCursor(cursor, extraFields);
    List<SortField<?>> effectiveOrderBy = backward ? reverseOrderBy(orderBy) : orderBy;
    var dsl = graphitronContext(env).getDslContext(env);
    var fields = new ArrayList<>(Film.$fields(env.getSelectionSet(), table, env));
    var selectedNames = fields.stream().map(Field::getName).collect(Collectors.toSet());
    for (var extra : extraFields) {
        if (!selectedNames.contains(extra.getName())) fields.add(extra);
    }
    var result = dsl.select(fields).from(table).where(condition)
        .orderBy(effectiveOrderBy.toArray(new SortField[0]))
        .seek(seekFields).limit(pageSize + 1).fetch();
    return new ConnectionResult(result, pageSize, after, before, backward, extraFields);
}
```

Every connection fetcher follows this shape. The boilerplate is ~20 of 28 lines.

---

## 1. Extract pagination boilerplate into `ConnectionHelper.pageRequest(...)`

**Current state.** `TypeFetcherGenerator.buildQueryConnectionFetcher` (`TypeFetcherGenerator.java:518`) inlines the full pagination dance:
- Arg extraction: lines 546–549
- Both-set validation + `backward`/`pageSize`/`cursor` derivation: lines 552–557
- Cursor decode: line 560
- Reverse order for backward pagination: line 563
- Extra-field merge into select list: lines 568–574

Every line reappears verbatim in every connection fetcher. The only per-field variance
is `defaultPageSize` (line 556, already parameterised off `FieldWrapper.Connection`) and
the four pagination-arg names (lines 540–549, which can be customised off `qtf.pagination()`).

**Change.** Introduce a runtime-side record + helper on `ConnectionHelperClassGenerator`:

```java
public record PageRequest(
    int limit,                            // pageSize + 1
    int pageSize,
    boolean backward,
    String after,
    String before,
    List<SortField<?>> effectiveOrderBy,
    Field<?>[] seekFields,
    List<Field<?>> selectFields           // selection ∪ extraFields (name-deduped)
) { }

public static PageRequest pageRequest(
    Integer first, Integer last, String after, String before,
    int defaultPageSize,
    List<SortField<?>> orderBy,
    List<Field<?>> extraFields,
    List<Field<?>> selection
) { … }
```

Fetcher-side emission collapses to:

```java
Integer first  = env.getArgument("first");
Integer last   = env.getArgument("last");
String  after  = env.getArgument("after");
String  before = env.getArgument("before");
ConnectionHelper.PageRequest page = ConnectionHelper.pageRequest(
    first, last, after, before, 100, ordering.sortFields(), ordering.columns(),
    FilmTypes.$fields(env.getSelectionSet(), filmTable, env));
```

The four `env.getArgument` calls stay on the fetcher side so the helper has no
graphql-java dependency, matching the existing split for `*Conditions` classes
(`TypeConditionsGenerator.java:30–32`). `ConnectionResult` construction takes the
`PageRequest` directly (`new ConnectionResult(result, page)`), deleting three of its
six parameters — they already live on the record.

**Test hook.** Existing `filmsOrderedConnection` / `filmsConnection` execution tests in
`graphitron-rewrite-test-spec` must continue to pass unchanged — same SQL, same
round-trip count, same cursor encoding. Pipeline test gains a check that the emitted
body contains exactly one `ConnectionHelper.pageRequest(...)` call and no
`backward ?`/`seek(`/`reverseOrderBy(` literals.

---

## 2. Extract condition orchestration into generated `QueryConditions`

**Current state.** `buildConditionCall` (`TypeFetcherGenerator.java:493–508`) emits the
`DSL.noCondition()` seed, any per-filter arg unpacking, and the `.and(...)` composition
inline in every fetcher. The called methods (`FilmConditions.<query>Condition`) are
generated by `TypeConditionsGenerator` and are explicitly scoped as "pure functions …
no dependency on graphql-java runtime types" (javadoc at `TypeConditionsGenerator.java:30`).

The env-aware shim layer — seed the condition, pull args off `env`, coerce enums,
compose the pure fragments — currently lives in the fetcher.

**Change.** Introduce a parallel generator `QueryConditionsGenerator` emitting one
class per root `Query` type holding one method per condition-bearing query field:

```java
public final class QueryConditions {
    public static Condition filmsOrderedConnectionCondition(Film filmTable, DataFetchingEnvironment env) {
        Condition condition = DSL.noCondition();
        String ratingArg = env.getArgument("rating");
        MpaaRating rating = ratingArg != null ? MpaaRating.valueOf(ratingArg) : null;
        condition = condition.and(FilmConditions.filmsOrderedConnectionCondition(filmTable, rating));
        return condition;
    }
}
```

Fetcher-side emission collapses to one line:

```java
Condition condition = QueryConditions.filmsOrderedConnectionCondition(filmTable, env);
```

Layering:
- `FilmConditions` (and siblings) stay entity-scoped and pure — same javadoc contract.
- `QueryConditions` is query-scoped and env-aware — owns arg extraction + coercion +
  composition across however many `FilmConditions` / `LanguageConditions` fragments
  feed one query.

**Move target for arg-coercion emission.** The ternary at `TypeFetcherGenerator.java:
494–505` (via `ArgCallEmitter.buildCallArgs`) moves to the `QueryConditionsGenerator`
emitter. The fetcher no longer touches `CallParam.extraction()` for filter args.

**Test hook.** Structural test asserting a `QueryConditions` class exists per root-query
type that has any `@condition`-bearing field, and that its method signature is
`(Table, DataFetchingEnvironment) → Condition`. Existing execution tests unchanged.

---

## 3. Never emit `var` in generated code

**Rationale.** Generated code is read in review and by developers debugging resolver
output. Explicit types give grep-ability and make type-inference surprises visible at
emission time rather than compile time. The generator always knows the type — writing
it out costs nothing.

**Sites** (from grep over `graphitron-rewrite/src/main/java`):
- `TypeFetcherGenerator.java:466, 478, 567, 568, 570, 572, 661, 852, 853`
- `SplitRowsMethodEmitter.java:338`
- `LookupValuesJoinEmitter.java:320`
- `ConnectionResultClassGenerator.java:105, 107`
- `NodeIdEncoderClassGenerator.java:47`
- `ConnectionHelperClassGenerator.java:90, 134, 135, 162, 165`

Each is a hardcoded `"var "` literal inside an `addStatement`/`addCode` call. Replace
with the JavaPoet `$T` substitution using the known type.

**Test hook.** Add a simple lint-style check to the generator test module:
recursively scan emitted `.java` files in the test-spec `target/generated-sources`
directory and fail on any `\bvar\s+\w+\s*=` match. Cheaper than auditing sites
one-by-one over time.

**Scope.** Restricted to code emitted *into user projects*. Generator-implementation
`var` usage (in `graphitron-rewrite/src/main/java` itself) is unaffected — Java 21 is
the generator target per `CLAUDE.md`.

---

## 4. Rename local `table` to `<entity>Table`

**Current state.** `GeneratorUtils.declareTableLocal` (`GeneratorUtils.java:110–114`)
and the inlined statement at `TypeFetcherGenerator.java:533` both emit the local name
`table`. When the generated mapper class (`Film`) and the jOOQ table class (`Film`)
share a simple name, the importer cannot import both — the declaration falls back to
the fully-qualified `no.sikt.graphitron.rewrite.test.jooq.tables.Film table = Tables.FILM`.

**Change.** Rename the emitted local from `"table"` to `<entityName>Table`, derived
from `ResolvedTableNames.typeClass().simpleName()` (lowercased first letter):
`filmTable`, `languageTable`, `categoryTable`. Threads through every fetcher body that
references the local:

- `TypeFetcherGenerator.java:533, 560, 568, 581` — the connection fetcher
- `GeneratorUtils.declareTableLocal` (`GeneratorUtils.java:110`) — shared helper used
  by the `QueryTableField`, `QueryConnectionField`, and service/method-table arms

Also thread an alias parameter through `ArgCallEmitter.buildCallArgs`
(`ArgCallEmitter.java:29–36`) — it currently hardcodes `"table"` as the first argument
string. This is already tracked as item 7 of
[plan-classification-vocabulary-followups.md](plan-classification-vocabulary-followups.md);
the two items land together or item 7 is folded in here. Either is fine — flag the
coupling in the implementation commit message.

**Import hygiene follow-through.** With the mapper/table name collision broken, the
importer for the fetcher class should import both `Film` (mapper) and the jOOQ-side
`Film` (table) with one aliased and the other simple-named. Confirm during
implementation whether JavaPoet handles this automatically once the local name is
disambiguated, or whether we need to explicitly qualify one of the two uses.

**Test hook.** Pipeline test: assert no emitted fetcher body contains the string
`no.sikt.graphitron.rewrite.test.jooq.tables.` (full-package jOOQ qualification in a
fetcher body is always an import-hygiene bug).

---

## Smaller clean-ups folded in

These are cheap to carry in the same implementation cycle:

- **Named constant for default page size.** The literal `100` at
  `FieldBuilder.java:1212–1218` is the fallback when no `@asConnection(defaultPageSize:)`
  is set. Extract to a `public static final int DEFAULT_PAGE_SIZE = 100` on a suitable
  rewrite-side class (`FieldWrapper` or a new `PaginationDefaults`) and reference
  symbolically at emit time.

- **Raw `Field[]` → `Field<?>[]`.** The cursor-decode return type at
  `TypeFetcherGenerator.java:560` uses a raw array type; tighten to `Field<?>[]` both
  in the emitted code and in `ConnectionHelper.decodeCursor`'s signature.

- **Double `env` on `getDslContext`.** `graphitronContext(env).getDslContext(env)`
  passes `env` twice. Investigate whether one is redundant; if so, drop it from the
  emitter. If both are required, leave it and document why in `GraphitronContext`.

---

## Non-goals

- **Rewriting the `*Conditions` naming convention.** `FilmConditions` stays
  entity-scoped; this plan only adds `QueryConditions` alongside. Renaming the existing
  class family is not in scope.
- **Changing the `ConnectionResult` public shape externally.** The constructor-arg
  reduction in §1 is an internal simplification; any external consumer that
  `new`s `ConnectionResult` directly is not expected to exist, but confirm at
  implementation time.
- **Touching non-connection fetchers (`buildQueryTableFetcher`, service variants).**
  The `table` rename in §4 reaches them because `declareTableLocal` is shared, but the
  pagination-helper and QueryConditions changes are connection-only. Non-connection
  fetchers already have a cleaner shape.
- **Mutation fetchers.** Out of scope; the Mutation stubs are tracked separately
  under roadmap item #4.

---

## Open decisions

- **`PageRequest` vs. per-value signature.** `ConnectionHelper.pageRequest(...)` could
  return a record (proposed) or take a consumer/builder and apply against a jOOQ
  `SelectQuery`. The record form keeps the fetcher's terminal `dsl.select(...)...fetch()`
  chain readable. Implementer's call.

- **Selection handling in `PageRequest`.** Whether `pageRequest` takes the already-built
  `$fields(...)` selection as input (proposed) or returns a `Function<List<Field<?>>,
  List<Field<?>>>` the fetcher applies to `$fields(...)`. The former keeps the fetcher
  declarative; the latter avoids passing the selection through a helper that mostly
  cares about pagination. Decide during implementation.

- **QueryConditions per-root-type vs. single class.** A schema with multiple root
  types (`Query`, future `Mutation`, federation's `_entities`) could get one
  conditions-orchestration class per root type (`QueryConditions`, `MutationConditions`)
  or one umbrella class. Per-root-type is cleaner and matches how the fetcher
  generators already partition; confirm nothing in the grouping logic resists it.

- **`var` lint vs. emit-time enforcement.** The §3 audit lists all current sites;
  replacing them is mechanical. The lint check is a ratchet to prevent regression. An
  alternative is a `CodeBlock` wrapper that rejects `"var "` at emit time — cheaper
  to write, noisier to debug. Lint is the default; revisit if regressions recur.
