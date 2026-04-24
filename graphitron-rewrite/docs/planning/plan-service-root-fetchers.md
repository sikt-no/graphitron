# Service-backed and method-backed root fetchers

> **Status:** Spec
>
> Lift three root-`Query` leaves out of `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`:
> `QueryField.QueryTableMethodTableField`, `QueryField.QueryServiceTableField`,
> `QueryField.QueryServiceRecordField`. All three dispatch to developer-provided Java —
> no SQL generation for the service variants, a single projection SELECT over a
> developer-returned `Table<?>` for the table-method variant. Closes Backlog item #7.

## Current state

- **Classifier.** All three are already classified by `FieldBuilder.classifyQueryField`:
  - `@service` at the root splits on `ReturnTypeRef` variant: `TableBoundReturnType` → `QueryServiceTableField`; `ResultReturnType` or `ScalarReturnType` → `QueryServiceRecordField`; `PolymorphicReturnType` → `UnclassifiedField` (polymorphic `@service` is explicitly rejected).
  - `@tableMethod` → `QueryTableMethodTableField`, with the return type narrowed to `TableBoundReturnType`. Mismatched return type → `UnclassifiedField`.
  - `MethodRef` is fully resolved by `ServiceCatalog.reflectServiceMethod` / `reflectTableMethod`. Each `Param` is pre-classified into one of six `ParamSource` variants: `Arg` (GraphQL argument), `Context` (context key), `DslContext` (jOOQ `DSLContext` — service only, gated off for `@tableMethod` by the roadmap backlog item), `Table` (jOOQ `Table<?>` — tableMethod only), `SourceTable` (parent table; not relevant at root), and `Sources` (DataLoader batch-key list; not relevant at root — see Invariants §2).
  - `resolveServiceField` is called with `parentPkColumns = List.of()` at the root, so a `Sources`-typed parameter carries an empty key column list by construction.

- **Stubs.** `QueryTableMethodTableField`, `QueryServiceTableField`, and `QueryServiceRecordField` entries in `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`; dispatcher arms route to `stub(f)` which emits a method body that throws `UnsupportedOperationException`. The stubbed-variant validator (`GraphitronSchemaValidator.validateVariantIsImplemented`) already fails any schema that lands on these leaves at build time.

- **Validator.** `validateQueryTableMethodTableField` calls the shared `validateCardinality` — which is currently a no-op (see below). `validateQueryServiceTableField` and `validateQueryServiceRecordField` are empty.

- **Neighbouring reference.** `QueryField.QueryTableField.buildQueryTableFetcher` is the closest existing shape: a synchronous root fetcher that declares a table local, optionally builds a condition, and runs a single `dsl.select(Type.$fields(...)).from(table)...fetch()`.

- **Call-arg emission.** `ArgCallEmitter.buildCallArgs(List<CallParam>, String)` hardcodes `"table"` as the first arg and iterates `callParams()` (Arg + Context only, filtered via `MethodRef.callParams()`). This is a condition-method call shape — it cannot be reused verbatim for service/tableMethod calls, which need declaration-order iteration over `params()` with per-`ParamSource` emission. See "Emission → Call-arg emitter" below.

- **Cardinality validator.** `GraphitronSchemaValidator.validateCardinality` is currently a no-op — its switch enumerates `Single`/`List`/`Connection` with empty arms (see the inline comment: "no per-variant validation is needed here"). Sixteen validators call it today, several of which (e.g. `validateQueryTableField`) legitimately accept `Connection`; its semantics cannot be tightened without breaking those. This plan rejects `Connection` for the three leaves at classifier time instead — see Invariants §1 and Plan → Classifier additions.

- **Jooq table expression.** `GeneratorUtils.ResolvedTableNames` exposes `tablesClass()`, `jooqTableClass()`, and `typeClass()` — no helper today returns a `Tables.FOO` expression directly. The existing pattern (`declareTableLocal`, `GeneratorUtils.java:110-115`) builds it inline from `tablesClass()` + `tableRef.javaFieldName()` via JavaPoet `$T.$L`. This plan reuses that pattern; no new record method is required.

- **Test fixtures.** `graphitron-rewrite/graphitron-rewrite/graphitron-rewrite-test/src/main/resources/graphql/schema.graphqls` contains no `@service` or `@tableMethod` usages — fixtures must be added. `TestServiceStub` and `TestTableMethodStub` exist under `graphitron-rewrite/src/test/java/` for unit tests but are not on the test-spec classpath.

## Shape of emitted fetcher per leaf

All three are **synchronous at the root**. No DataLoader, no `CompletableFuture`. Root query fields have no parent-batching context to register a DataLoader against; per-request concurrency is irrelevant at the root level. Projection (`Type.$fields`) is the framework's concern only for the table-method variant, which owns the SELECT; service variants hand back developer-populated records or DTOs and graphql-java traverses them via the registered column fetchers.

### `QueryTableMethodTableField`

The developer method returns a `Table<?>` pre-filtered for this request. The framework runs a projection SELECT over it:

```java
public static Result<Record> films(DataFetchingEnvironment env) {
    Table<?> table = FilmMethods.popularFilms(Tables.FILM, env.getArgument("minRating"));
    var dsl = graphitronContext(env).getDslContext(env);
    return dsl.select(FilmTypes.$fields(env.getSelectionSet(), table, env))
        .from(table)
        .fetch();
}
```

Argument list is emitted by walking `method().params()` in declaration order (see "Call-arg emitter" below). The `ParamSource.Table` slot is filled with a `CodeBlock` of `Tables.FOO` built via JavaPoet `$T.$L` from `names.tablesClass()` and `tableRef.javaFieldName()` (the same pattern used by `GeneratorUtils.declareTableLocal`) — the user may declare it at any position, not necessarily first. `ParamSource.Arg` / `ParamSource.Context` slots are emitted the same way condition-method calls handle them.

`ParamSource.DslContext`, `ParamSource.Sources`, `ParamSource.SourceTable`: unreachable for this leaf today — `reflectTableMethod` rejects the first (backlog-gated) and the other two never apply to tableMethods.

Single cardinality: `fetchOne()` instead of `fetch()`, return type `Record`. List: `Result<Record>`. Connection: rejected at classifier time in `FieldBuilder` — see Invariants §1.

### `QueryServiceTableField`

The developer method returns `Result<Record>` (list) or `Record` (single) already populated for this request. The framework does no projection — the service owns the SQL:

```java
public static Result<Record> activeRentals(DataFetchingEnvironment env) {
    var dsl = graphitronContext(env).getDslContext(env);
    return RentalService.activeRentals(
        dsl,
        env.getArgument("storeId"),
        graphitronContext(env).getContextArgument(env, "viewerId")
    );
}
```

Return type is `Result<Record>` / `Record` based on wrapper cardinality. Argument list is emitted by walking `method().params()` in declaration order, emitting `dsl` for `DslContext` slots, `env.getArgument(name)` (or the `CallSiteExtraction` shape) for `Arg` slots, and `graphitronContext(env).getContextArgument(env, name)` for `Context` slots. The `dsl` local is declared at the top of the emitted method body only if the service method actually takes a `DSLContext` parameter. No `ParamSource.Sources` support at root (see Invariants §2). No projection — graphql-java drives column fetchers over the service-returned `Record`/`Result<Record>`.

### `QueryServiceRecordField`

Same as `QueryServiceTableField` but the return type covers two sub-shapes — both classify to this leaf:

- `ReturnTypeRef.ScalarReturnType` — scalar (e.g. `Int`, `String`), plain DTO, or any non-table non-record Java type. Generator emits `return SomeService.method(...);` with return type `Object` (matches the existing stub signature). graphql-java coerces to the declared SDL type.
- `ReturnTypeRef.ResultReturnType` — a `@record`-annotated GraphQL type backed by a jOOQ `Record` subclass. The service returns the record directly; graphql-java's registered property/record fetchers walk its fields. No projection.

Both shapes share the same argument-list emission (params-walk, with DSLContext / Arg / Context expressions). The only difference is the generated return type — both are compatible with `Object` for the method signature; we keep `Object` and let graphql-java coerce.

## Invariants

The three leaves share one invariant and two variant-specific ones:

1. **Cardinality (all three).** Wrapper must be `Single` or `List`, not `Connection`. Reject at **classifier time** in `FieldBuilder.classifyQueryField` by returning `UnclassifiedField` — consistent with the existing polymorphic `@service` rejection at `:1305-1306` and the single-cardinality-`@splitQuery` plan's §1b/§1c pattern. `UnclassifiedField` surfaces through `GraphitronSchemaValidator.validateUnclassifiedField` (`:639-644`) as a build-time error. Sites:
   - `@service` arm at `:1298-1307`: before the `switch (svcResult.returnType())`, check the resolved wrapper (`svcResult.returnType().wrapper()` — `wrapper()` is defined on all four `ReturnTypeRef` variants). If `Connection`, return `UnclassifiedField` with `"@service at the root does not support Connection return types — use [T] or T instead"`.
   - `@tableMethod` arm at `:1333-1356`: after the `TableBoundReturnType tb` check but before `reflectTableMethod`, if `tb.wrapper() instanceof FieldWrapper.Connection` return `UnclassifiedField` with `"@tableMethod at the root does not support Connection return types — use [T] or T instead"`.

   The existing no-op `validateCardinality(...)` call in `validateQueryTableMethodTableField` is load-bearing on nothing; delete it as part of this plan. `validateCardinality` itself stays (its other ~15 callers legitimately accept `Connection`).

2. **No `Sourced` parameter at root (both service variants).** `ServiceCatalog.reflectServiceMethod` admits a `ParamSource.Sources` parameter when the method takes `List<RowN<?>>` / `List<RecordN<?>>` / `List<SomeClass>`. At the root, `parentPkColumns` is `List.of()`, so a `RowKeyed`/`RecordKeyed` param carries an empty key column list — the DataLoader batching semantics that shape presumes are not available. Reject at classifier time, same site as §1's `@service` check (before the `switch`), using `svcResult.method().params().stream().anyMatch(p -> p.source() instanceof ParamSource.Sources)` → return `UnclassifiedField` with `"@service at the root does not support List<Row>/List<Record>/List<Object> batch parameters — the root has no parent context to batch against"`. This prevents the classifier from ever producing a `QueryServiceTableField`/`QueryServiceRecordField` with a Sourced param, so the emitter's `Sources` arm in `buildMethodBackedCallArgs` remains genuinely unreachable.

3. **`@tableMethod` signature (already enforced).** `ServiceCatalog.reflectTableMethod` enforces exactly one `Table<?>` parameter at reflection time; no classifier branch produces `QueryTableMethodTableField` without it. It also currently rejects `DSLContext` parameters on `@tableMethod` methods — that's tracked as the separate Backlog item "`DSLContext` on `@condition` / `@tableMethod` methods". This plan does not lift that gate; `QueryTableMethodTableField` params are limited to `Table` / `Arg` / `Context`.

4. **`DslContext` parameter supported only on `@service` (not `@tableMethod`).** `reflectServiceMethod` admits a `DSLContext` parameter (classified as `ParamSource.DslContext`). The emitter for `QueryServiceTableField` / `QueryServiceRecordField` must thread `graphitronContext(env).getDslContext(env)` into the call at the parameter's declaration-index slot.

## Plan

### Emission

The three fetchers share a single argument-list emitter that walks the developer method's parameter list in declaration order. Per-leaf fetcher methods wrap it with the surrounding SQL (tableMethod) or `return` statement (services).

#### Call-arg emitter

Add a new helper alongside the existing `ArgCallEmitter.buildCallArgs`:

```java
public static CodeBlock buildMethodBackedCallArgs(
    MethodRef method,
    CodeBlock tableExpression,   // expression emitted at the ParamSource.Table slot; null for service calls
    String conditionsClassName   // target for TextMapLookup — see §Open decisions
);
```

`CodeBlock` (not `String`) because the expression at the `Table` slot is `Tables.FOO` with a class reference — JavaPoet needs to see the `ClassName` to emit the import. Passing a string would force the caller to construct an untyped literal.

The helper iterates `method.params()` in **declaration order** and emits a comma-separated `CodeBlock` of per-slot expressions. One row per `ParamSource` variant:

| `ParamSource`     | Emitted expression |
|-------------------|--------------------|
| `Arg(extraction)` | Delegates to the existing `buildArgExtraction(new CallParam(p.name(), extraction, false, p.typeName()), conditionsClassName)` — re-uses the five-way `CallSiteExtraction` switch (`Direct`, `EnumValueOf`, `TextMapLookup`, `ContextArg`, `JooqConvert`). |
| `Context()`       | `graphitronContext(env).getContextArgument(env, "<p.name()>")` |
| `DslContext()`    | Literal `dsl` — the per-leaf fetcher is responsible for declaring the local before calling the helper. |
| `Table()`         | The supplied `tableExpression` `CodeBlock` (e.g. `CodeBlock.of("$T.$L", names.tablesClass(), tableRef.javaFieldName())` → emits `Tables.FILM`). |
| `Sources(_)`      | `throw new IllegalStateException(…)` at emission time — caller must have validated this out via Invariants §2 before calling the helper. |
| `SourceTable()`   | `throw new IllegalStateException(…)` at emission time — `SourceTable` is a child-field concept (join conditions) and is unreachable for root leaves. |

Two guarantees this contract preserves:

- **Declaration-order correctness.** The user controls the Java method signature; the helper reproduces it verbatim. A user who declares `(Float minRating, Table<?> t)` gets `(env.getArgument("minRating"), table)` — no hidden reordering.
- **No hidden side effects.** The helper never emits a `dsl` local declaration, a projection, or a return statement — only the comma-separated argument expressions. All enclosing code is the per-leaf fetcher's responsibility.

Unlike the existing `buildCallArgs`, there is no implicit `"table"` first argument. The existing `buildCallArgs` (condition-method shape — hardcoded `"table"` + `callParams()` iteration, which filters to `Arg`+`Context`) remains untouched; it serves filter/where composition in fetcher bodies and inline table-field emitters, where the call shape `<Conditions>.<method>(table, arg1, ...)` is fixed.

#### Per-leaf fetchers

Implement three new emitter methods in `TypeFetcherGenerator`, modelled on `buildQueryTableFetcher`:

- **`buildQueryTableMethodFetcher(QueryTableMethodTableField)`** — the most involved of the three. Emits:
  1. `var table = <MethodClass>.<methodName>(<buildMethodBackedCallArgs(method, tableExpression, conditionsClass)>);` where `tableExpression` is `CodeBlock.of("$T.$L", names.tablesClass(), tableRef.javaFieldName())` — the same `Tables.FOO` expression `GeneratorUtils.declareTableLocal` uses. `names` comes from `GeneratorUtils.ResolvedTableNames.of(tableRef, returnTypeName)`. The `ParamSource.Table` slot resolves to this expression wherever the user declared it in the signature.
  2. `var dsl = graphitronContext(env).getDslContext(env);`
  3. `return dsl.select(<TargetType>.$fields(env.getSelectionSet(), table, env)).from(table).<fetchOne()|fetch()>;`

  `conditionsClassName` is the `*Conditions` class of the field's return type (same resolution as `QueryTableField.filters()` uses). `TextMapLookup` on tableMethod args is naturally supported via that class.

- **`buildQueryServiceTableFetcher(QueryServiceTableField)`** — shape depends on whether the method declares a `DslContext` parameter:
  - If yes: emit `var dsl = graphitronContext(env).getDslContext(env);`, then `return <ServiceClass>.<methodName>(<buildMethodBackedCallArgs(method, null, conditionsClass)>);`.
  - If no: single statement `return <ServiceClass>.<methodName>(<buildMethodBackedCallArgs(method, null, conditionsClass)>);`.

  The `tableExpression` argument is `null` — the helper's `Table()` arm is unreachable here because `reflectServiceMethod` never produces a `ParamSource.Table`. Return type: `Result<Record>` for list, `Record` for single.

- **`buildQueryServiceRecordFetcher(QueryServiceRecordField)`** — same body shape as the service-table variant (DslContext local conditional on method signature). Return type `Object` to cover both the `ScalarReturnType` and `ResultReturnType` sub-cases (graphql-java coerces).

Switch arms in `generateTypeSpec` change from `stub(f)` to the new emitter calls for all three leaves. The three leaf classes move from `NOT_IMPLEMENTED_REASONS` to `IMPLEMENTED_LEAVES`.

#### `conditionsClassName` resolution

`TextMapLookup` is the only `CallSiteExtraction` variant that uses `conditionsClassName`. Per leaf:

- **TableMethod / service-table**: the field's return type is a `@table` type with a well-defined `*Conditions` class. Use it.
- **Service-record with `ResultReturnType`**: the return type is `@record`-backed; it does not own a `*Conditions` class. Defer to §Open decisions.
- **Service-record with `ScalarReturnType`**: same as above — no target class. Defer to §Open decisions.

### Classifier additions

Rejection lives in `FieldBuilder.classifyQueryField` — consistent with the existing polymorphic `@service` rejection at `:1305-1306` and the single-cardinality-`@splitQuery` plan's §1b/§1c rejections. `UnclassifiedField` routes through `GraphitronSchemaValidator.validateUnclassifiedField` (`:639-644`) which surfaces `field.reason()` as a build error.

- `@service` arm at `:1298-1307`. Before the `switch (svcResult.returnType())`:
  - Wrapper check (Invariants §1). If `svcResult.returnType().wrapper() instanceof FieldWrapper.Connection` → return `UnclassifiedField` with the message in Invariants §1.
  - Sourced-param check (Invariants §2). If any `svcResult.method().params()` has `source() instanceof ParamSource.Sources` → return `UnclassifiedField` with the message in Invariants §2.

- `@tableMethod` arm at `:1333-1356`. After the `TableBoundReturnType tb` check (`:1337-1340`) but before the `parseExternalRef`/`reflectTableMethod` calls:
  - Wrapper check (Invariants §1). If `tb.wrapper() instanceof FieldWrapper.Connection` → return `UnclassifiedField` with the message in Invariants §1.

### Validator additions

`GraphitronSchemaValidator.validateQueryTableMethodTableField` currently calls the shared `validateCardinality`, which is a no-op (see Current state → Cardinality validator). Delete the call — the Connection rejection now happens at classifier time (see Classifier additions above). `validateQueryServiceTableField` and `validateQueryServiceRecordField` stay empty. `validateCardinality` itself stays untouched; its ~15 other callers legitimately accept `Connection`.

### Structural tests

`TypeFetcherGeneratorTest` gains three cases — one per leaf — asserting:

- Emitted method signature (`public static <ReturnType> <fieldName>(DataFetchingEnvironment env)`).
- For `QueryTableMethodTableField`: a `$fields` call is emitted, a call to the method's fully-qualified name is emitted in the `from` position.
- For the two service variants: a direct call to the method is emitted, `$fields` is NOT emitted.

Body-string assertions stay minimal — structural properties only, per the test-tier convention.

### Pipeline test

`GraphitronSchemaBuilderTest`: add an SDL case for each of the three leaves; assert the classifier produces the expected leaf and the generator emits a fetcher method (not a stub). Leverages existing `TypeSpecAssertions.wiringFor(field)`-style helpers where applicable. Negative cases: (a) `@service` returning a connection type (e.g. `FilmConnection`) classifies to `UnclassifiedField` with the Invariants §1 message; (b) `@tableMethod` returning a connection type classifies to `UnclassifiedField` with the Invariants §1 message; (c) `@service` method with a `List<RowN<…>>` parameter classifies to `UnclassifiedField` with the Invariants §2 message. All three surface through `validateUnclassifiedField` as build errors.

### Compile gate

`mvn compile -pl :graphitron-rewrite-test -Plocal-db` must succeed with the new fixtures present. This catches argument-type mismatches, wrong package references, and generic-bound errors against real jOOQ classes.

### Execution tests

Fixture additions to `graphitron-rewrite-test/graphitron-rewrite-test`:

- **Java service class** (new file under `src/main/java`): e.g. `SampleQueryService` with three methods — one returning `Table<?>` (for `@tableMethod`), one returning `Result<FilmRecord>` (for service-table), one returning a scalar (for service-record).

- **SDL additions** (`schema.graphqls`):
  ```graphql
  type Query {
    popularFilms(minRating: Float!): [Film!]! @tableMethod(ref: "…SampleQueryService.popularFilms")
    filmsByService(ids: [Int!]!): [Film!]! @service(ref: "…SampleQueryService.filmsByIds")
    filmCount: Int! @service(ref: "…SampleQueryService.filmCount")
  }
  ```

- **`GraphQLQueryTest` cases**:
  - `queryTableMethod_returnsFilteredFilms_projectsOnlySelectedColumns` — calls `popularFilms(minRating: 4.0) { title }`, asserts projection (`title` populated) and that the method's filter shaped the `Table<?>`.
  - `queryServiceTable_returnsFilmsByIds` — calls `filmsByService(ids: [1, 2])`, asserts the service-returned records flow through column fetchers.
  - `queryServiceRecord_returnsScalar` — calls `filmCount`, asserts scalar returned as-is.
  - Round-trip count: tableMethod = 1 query (the projection SELECT), service variants = whatever the service itself issues.

## Non-goals

- **`ChildField.ServiceTableField.buildServiceRowsMethod` body** — the child service variant currently partitions as `IMPLEMENTED_LEAVES` but its rows method still throws `UnsupportedOperationException` at runtime. That's a separate fix — the child variant lives inside a DataLoader batch and has the batch-key semantics this plan's root variants deliberately don't — tracking as a follow-up once this plan lands, or as a parallel plan.

- **`MutationField.MutationServiceTableField` / `MutationServiceRecordField`** — analogous shapes on the mutation side, but with write semantics and transaction handling. Covered by the Mutation bodies stub (#4).

- **Federation `_service` / `_entities`** — covered by the federation-jvm transform plan.

- **Polymorphic return types** — `@service` returning an interface/union is explicitly rejected at classify time (see `FieldBuilder.classifyQueryField:1305-1306`, `@service` + `PolymorphicReturnType` arm) and surfaces as `UnclassifiedField` with the reason `"@service returning a polymorphic type is not yet supported"`. The existing `UnclassifiedField` error path handles reporting — no new validator code is needed for this case. Deferred to the interface/union stubs item (#3).

- **Stub reason-string drift fix** — `QueryTableMethodTableField`'s reason string currently references `#1`, but the leaf now belongs under `#7` in the roadmap's "Generator stubs" list. This plan removes the entry from `NOT_IMPLEMENTED_REASONS` entirely as part of landing the fetcher, so the stale string is deleted outright — no separate drift fix needed.

- **Lifting the `DSLContext`-on-`@tableMethod` gate.** Tracked as a separate Backlog item. Once lifted, `QueryTableMethodTableField`'s call-arg emission will fall out of the same `buildMethodBackedCallArgs` helper introduced here without additional changes — the `DslContext()` arm is already specified.

## Open decisions

- **`TextMapLookup` on service-method args without a return-type `*Conditions` class.** Referenced from the `conditionsClassName resolution` subsection above. For `QueryServiceRecordField` (both `ScalarReturnType` and `ResultReturnType` sub-cases), the return type has no associated `*Conditions` class and thus no natural home for a text-map lookup field. Two options: (a) synthesise the map reference against the nearest `*Conditions` class the schema already has; (b) validate-time reject `TextMapLookup` on service-record args entirely and require the service to accept the raw enum. The service-table and tableMethod variants are unaffected — they resolve to the return type's `*Conditions` class. **Tentative default: (b)** — rejecting at validate time is cheap, gives a clear error, and avoids guessing which `*Conditions` class is "nearest". Revisit when a fixture forces option (a). Implementer is free to escalate if the fixture set surfaces a concrete motivating case.

- **Stub-reason-string reference for #7.** When the three entries leave `NOT_IMPLEMENTED_REASONS`, no other stub entry currently references `#7` in its message — so `#7`'s number in the roadmap stops being load-bearing. Nothing to do unless a future stub falls under #7.
