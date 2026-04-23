# Plan — Generated-fetcher quality pass

> **Status:** Done
>
> §1 (pagination helper), §2 (QueryConditions), §3 (no `var`), §4 (table-local
> rename), and the smaller cleanups (DEFAULT_PAGE_SIZE, Field<?>[] tightening) all
> shipped. Three lint ratchets over emitted source
> (`GeneratedSourcesLintTest.emittedSourcesDoNotUseVar` / `fetcherBodiesDoNotFullyQualifyJooqTables`
> / `entityConditionsClassesHaveNoGraphqlJavaImports`) promote the plan's structural
> invariants from javadoc to test failures.
>
> Four cleanups to the code `TypeFetcherGenerator` emits, motivated by the current
> `filmsOrderedConnection` shape in `graphitron-rewrite-test`. Emitted SQL,
> round-trip count, and cursor encoding are unchanged; items 1–2 add new surfaces
> (`ConnectionHelper.pageRequest` on the runtime side, `QueryConditions` as a parallel
> generated class) but preserve fetcher behaviour. Items can land in any order; the
> pagination-helper and QueryConditions items are the biggest wins, the other two are
> smaller-scoped.
>
> **Why this is more than boilerplate reduction.** Each change moves logic out of
> generated fetcher bodies into surfaces that are individually unit-testable: a
> hand-written helper for pagination, an isolated generated class for condition
> orchestration, and explicit emitted types that keep grep-based structural assertions
> reliable. This is a concrete instance of the **"Rebalance test pyramid"** backlog
> item — coverage for pagination branching and condition composition stops scaling
> with the number of `@asConnection` fields once their logic lives in one place, and
> invariants currently defended by javadoc (e.g. "`*Conditions` has no graphql-java
> dependency") become structurally enforceable.

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

**Current state.** `TypeFetcherGenerator.buildQueryConnectionFetcher` inlines the full
pagination dance:
- Pagination-arg extraction (`first`/`last`/`after`/`before` via `env.getArgument`)
- Both-set validation (`first` and `last` must not both be set) +
  `backward`/`pageSize`/`cursor` derivation
- Cursor decode via `ConnectionHelper.decodeCursor`
- Reverse ordering for backward pagination (`reverseOrderBy`)
- Extra-field merge into the select list (`extraFields` name-deduped against the
  selection)

Every line reappears verbatim in every connection fetcher. The only per-field variance
is `defaultPageSize` (already parameterised off `FieldWrapper.Connection`) and the four
pagination-arg names (customisable off `qtf.pagination()`).

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
    List<Field<?>> selectFields,          // selection ∪ extraFields (name-deduped)
    List<Field<?>> extraFields            // preserved for ConnectionResult/cursor encoding
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
graphql-java dependency, matching the existing split for `*Conditions` classes (see the
purity contract in `TypeConditionsGenerator`'s javadoc). `ConnectionResult` construction
takes the `PageRequest` directly (`new ConnectionResult(result, page)`), replacing four
of its six constructor parameters (`pageSize`, `afterCursor`, `beforeCursor`, `backward`)
with the single `page` reference. The remaining constructor params are `result` (the
jOOQ fetch result, not on the record) and `orderByColumns` (sourced from
`page.extraFields()` — kept distinct from `selectFields` on the record because
`ConnectionResult` needs the pure extra-ordering list for cursor encoding, not the
selection-merged list).

**Shape: record, not builder.** The record form keeps the fetcher's terminal
`dsl.select(...)...fetch()` chain readable and lets `ConnectionResult` accept the request
by value. An alternative (`pageRequest` takes a consumer/builder applied against a jOOQ
`SelectQuery`) was considered and rejected on readability grounds. Revisit only if the
record form forces awkward temporaries in the fetcher body.

**Test hooks.**
- **Regression floor.** Existing `filmsOrderedConnection` / `filmsConnection` execution
  tests in `graphitron-rewrite-test` must continue to pass unchanged — same SQL,
  same round-trip count, same cursor encoding.
- **Pipeline assertion.** Emitted body contains exactly one
  `ConnectionHelper.pageRequest(...)` call and no `backward ?`/`seek(`/
  `reverseOrderBy(` literals.
- **New unit seam.** `ConnectionHelper.pageRequest(...)` becomes directly unit-testable
  as a plain Java method: first/last mutual exclusion, default-page-size fallback,
  cursor routing (`after` on forward vs. `before` on backward), reverse-ordering, and
  extra-field dedup each get one test on the helper. Today these branches are only
  exercisable per-fetcher through the execution tier; after item 1, per-variant
  execution tests no longer need to re-prove pagination branching.

---

## 2. Extract condition orchestration into generated `QueryConditions`

**Current state.** `TypeFetcherGenerator.buildConditionCall` emits the
`DSL.noCondition()` seed, any per-filter arg unpacking, and the `.and(...)` composition
inline in every fetcher. The called methods (`FilmConditions.<query>Condition`) are
generated by `TypeConditionsGenerator` and are explicitly scoped as "pure functions …
no dependency on graphql-java runtime types" (javadoc on `TypeConditionsGenerator`).

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

**Partitioning: one class per root type.** The plan emits one `QueryConditions` class
per root operation type (`Query`, later `Mutation`, etc.) rather than one umbrella
class spanning all root types. Per-root-type matches how the fetcher generators
already partition emission and keeps each file scoped to a single schema root.
Implementer should flag during implementation if anything in the root-field grouping
logic resists this split.

**Move target for arg-coercion emission.** The arg-coercion ternary inside
`buildConditionCall` (currently emitted via `ArgCallEmitter.buildCallArgs` /
`buildArgExtraction`) moves to the new `QueryConditionsGenerator` emitter. The fetcher
no longer touches `CallParam.extraction()` for filter args.

**Test hooks.**
- **Structural.** A `QueryConditions` class exists per root-query type that has any
  `@condition`-bearing field, with method signature
  `(Table, DataFetchingEnvironment) → Condition`.
- **Fetcher-body assertion.** Emitted fetcher contains
  `QueryConditions.<name>Condition(<tableLocal>, env)` and does NOT contain
  `DSL.noCondition()` or `env.getArgument(...)` for any condition-bound filter arg
  name (those extractions now live inside `QueryConditions`). Pagination-arg
  extraction (`env.getArgument("first" | "last" | "after" | "before")` or the
  custom names from `qtf.pagination()`) correctly remains in the fetcher body and
  must not trip this assertion — scope the grep to filter-arg names pulled from
  `qtf.filters()`, not to `env.getArgument` in general.
- **New unit seam.** `QueryConditions.<name>Condition(table, env)` is directly
  unit-testable without running a DataLoader or round-trip: construct a
  `DataFetchingEnvironment` with known arg values, invoke, inspect the returned
  `Condition`. Today the env-aware composition is only reachable through execution
  tests.
- **Invariant enforcement.** Pipeline lint over
  `graphitron-rewrite/graphitron-rewrite-test/target/generated-sources/**/*Conditions.java` (excluding
  `QueryConditions.java`) — must not import `graphql.schema.DataFetchingEnvironment` nor
  any other `graphql.*` type. Today this contract lives only in the
  `TypeConditionsGenerator` javadoc; with the env-aware layer isolated in
  `QueryConditions`, violations become a test failure.
- **Regression floor.** Existing execution tests unchanged.

---

## 3. Never emit `var` in generated code

**Rationale.** Generated code is read in review and by developers debugging resolver
output. Explicit types give grep-ability and make type-inference surprises visible at
emission time rather than compile time. The generator always knows the type — writing
it out costs nothing. Also: pipeline-level structural assertions over emitted source
(grep for a concrete type name, or for the absence of one) become reliable — a `var`
on the left-hand side otherwise hides the type from the assertion.

**Sites** (from grep over `graphitron-rewrite/src/main/java`; re-grep before
implementing — line numbers drift with refactors):
- `TypeFetcherGenerator.java:466, 478, 567, 568, 570, 572, 578, 610, 661, 852, 853, 854`
- `SplitRowsMethodEmitter.java:338`
- `LookupValuesJoinEmitter.java:320`
- `ConnectionResultClassGenerator.java:105, 107`
- `NodeIdEncoderClassGenerator.java:47`
- `ConnectionHelperClassGenerator.java:90, 134, 135, 162, 165`

The pipeline lint test hook below is the durable coverage — the audit list is a
one-shot checklist for the initial sweep.

Each is a hardcoded `"var "` literal inside an `addStatement`/`addCode` call. Replace
with the JavaPoet `$T` substitution using the known type.

**Test hook.** Add a simple lint-style check to the generator test module:
recursively scan emitted `.java` files in the test-spec `target/generated-sources`
directory and fail on any `\bvar\b\s+\w+` match (the loosened form —
`\bvar\s+\w+\s*=` would miss `for (var extra : extraFields)` at
`TypeFetcherGenerator.java:572`, which is a for-loop variable with no `=`).
The initial sweep must convert that site along with the assignment-LHS sites.
Cheaper than auditing sites one-by-one over time.

**Scope.** Restricted to code emitted *into user projects*. Generator-implementation
`var` usage (in `graphitron-rewrite/src/main/java` itself) is unaffected — Java 21 is
the generator target per `CLAUDE.md`.

---

## 4. Rename local `table` to `<entity>Table`

**Current state.** `GeneratorUtils.declareTableLocal` and the inlined table declaration
inside `TypeFetcherGenerator.buildQueryConnectionFetcher` both emit the local name
`table`. When the generated mapper class (`Film`) and the jOOQ table class (`Film`)
share a simple name, the importer cannot import both — the declaration falls back to
the fully-qualified `no.sikt.graphitron.rewrite.test.jooq.tables.Film table = Tables.FILM`.

**Change.** Rename the emitted local from `"table"` to `<entityName>Table`, derived
from `ResolvedTableNames.jooqTableClass().simpleName()` (lowercased first letter):
`filmTable`, `languageTable`, `categoryTable`. `jooqTableClass` — not `typeClass` — is
the source of truth: the local variable *is* a jOOQ table alias and
`declareTableLocal` already uses `jooqTableClass` as the declaration's static type,
so both ends of the declaration draw from the same field. Their simple names coincide
by construction (the rename exists precisely because of this collision), so the choice
of source is about which field the implementation depends on, not the emitted value.
Threads through every fetcher body that references the local:

- Every `"table"` literal inside `TypeFetcherGenerator.buildQueryConnectionFetcher`
  (declaration, `.from(table)`, `.$fields(..., table, env)`, condition-call wiring)
- `GeneratorUtils.declareTableLocal` — shared helper used by the `QueryTableField`,
  `QueryConnectionField`, and service/method-table arms

**`ArgCallEmitter` signature changes — owned by this item.** Both public entry points
hardcode `"table"`:

- `buildCallArgs` at `ArgCallEmitter.java:31` starts the arg list with a literal
  `args.add("table")`.
- `buildArgExtraction` at `:55-59` uses `table.$L.getDataType()` inside the
  `JooqConvert` branch (both the list and scalar variants).

Thread `srcAlias` through both signatures; `buildCallArgs` passes its alias down to
each `buildArgExtraction` call:

```java
public static CodeBlock buildCallArgs(List<CallParam> params, String conditionsClassName, String srcAlias)
public static CodeBlock buildArgExtraction(CallParam param, String conditionsClassName, String srcAlias)
```

Missing the `buildArgExtraction` side would leave `JooqConvert`-extracted filter args
referencing a non-existent `table` local after the fetcher rename — the `JooqConvert`
branch only fires on filter args that are list-of-keys or jOOQ-converted scalars, so
the failure mode is silent on the simpler schemas and visible only once the call path
exercises that extraction shape.

Fetcher-path callers pass the new `<entity>Table` local. Caller enumeration —
both `ArgCallEmitter.buildCallArgs` callers need the new alias parameter:

- `TypeFetcherGenerator` fetcher-building path — passes the new `<entity>Table` local.
- `InlineTableFieldEmitter.buildInnerSelect` at `:151` — currently passes through
  without an alias, which is the latent inline-subquery bug also tracked as item 7 of
  [plan-classification-vocabulary-followups.md](plan-classification-vocabulary-followups.md).
  That item rides on top of this one independently; for this plan, pass the inner
  subquery's `terminalAlias`.

**Companion lift at `TypeFetcherGenerator.java:498-501`.** Independent of the §2
`QueryConditions` extraction, the pre-call-args emission for `JooqConvert && list`
params (emits `List<String> <name>Keys = env.getArgument(...)` before the call-args)
uses the arg-name but not `table` — so this site doesn't break from §4 on its own.
However, item 2 moves arg-unpacking into `QueryConditions`, and this pre-lift is the
one piece of call-args emission that §2 does not otherwise touch. Move it together
with the rest of the arg-coercion emission into `QueryConditionsGenerator`.

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

- **Named constant for default page size.** The literal `100` appears at four sites
  in `FieldBuilder`, all the fallback when no `@asConnection(defaultPageSize:)` is set:
  three return sites inside `resolveDefaultFirstValue` (`:1212`, `:1214`, `:1218`) plus
  one outside it at `:401` (`new FieldWrapper.Connection(..., 100, typeName)`). Extract
  to a `public static final int DEFAULT_PAGE_SIZE = 100` on a suitable rewrite-side
  class (`FieldWrapper` or a new `PaginationDefaults`) and reference symbolically at
  all four sites.

- **Raw `Field[]` → `Field<?>[]`.** The declared return type of
  `ConnectionHelper.decodeCursor` is already `Field<?>[]` (verified at
  `ConnectionHelperClassGenerator.java:104` — `.returns(ArrayTypeName.of(fieldWildcard))`
  where `fieldWildcard = Field<? extends Object>`). Two sites remain raw:

  - Fetcher-side local: `TypeFetcherGenerator.java:560` emits
    `Field[] seekFields = $T.decodeCursor(...)` using the raw `JOOQ_FIELD` constant.
  - `decodeCursor` body at `ConnectionHelperClassGenerator.java:107`:
    `Field[] seekFields = new Field[...]`.

  Tighten both to `Field<?>[]`.

---

## Investigate separately — not mechanical

- **Double `env` on `getDslContext`.** The emitted
  `graphitronContext(env).getDslContext(env)` pattern passes `env` twice. This may or
  may not be redundant in `GraphitronContext`'s current contract — unlike the items
  above, the answer requires reading that contract, not a mechanical rewrite. **Fold
  into the fetcher-quality implementation only if the investigation confirms one
  `env` is clearly redundant.** If both are required, leave the pattern in place and
  document the reason in `GraphitronContext`'s javadoc as a separate commit.

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

Plan-level shape questions are resolved above (record for `PageRequest`, one class per
root type for `QueryConditions`). Remaining open items are implementer-scope:

- **Selection handling in `PageRequest`.** Whether `pageRequest` takes the already-built
  `$fields(...)` selection as input (proposed) or returns a `Function<List<Field<?>>,
  List<Field<?>>>` the fetcher applies to `$fields(...)`. The former keeps the fetcher
  declarative; the latter avoids passing the selection through a helper that mostly
  cares about pagination. Decide during implementation.

- **`var` lint vs. emit-time enforcement.** The §3 audit lists all current sites;
  replacing them is mechanical. The lint check is a ratchet to prevent regression. An
  alternative is a `CodeBlock` wrapper that rejects `"var "` at emit time — cheaper
  to write, noisier to debug. Lint is the default; revisit if regressions recur.
