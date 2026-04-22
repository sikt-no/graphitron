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

- **Validator.** `validateQueryTableMethodTableField` checks cardinality. `validateQueryServiceTableField` and `validateQueryServiceRecordField` are empty.

- **Neighbouring reference.** `QueryField.QueryTableField.buildQueryTableFetcher` is the closest existing shape: a synchronous root fetcher that declares a table local, optionally builds a condition, and runs a single `dsl.select(Type.$fields(...)).from(table)...fetch()`.

- **Call-arg emission.** `ArgCallEmitter.buildCallArgs(List<CallParam>, String)` hardcodes `"table"` as the first arg and iterates `callParams()` (Arg + Context only, filtered via `MethodRef.callParams()`). This is a condition-method call shape — it cannot be reused verbatim for service/tableMethod calls, which need declaration-order iteration over `params()` with per-`ParamSource` emission. See "Emission → Call-arg emitter" below.

- **Test fixtures.** `graphitron-rewrite-test-spec/src/main/resources/graphql/schema.graphqls` contains no `@service` or `@tableMethod` usages — fixtures must be added. `TestServiceStub` and `TestTableMethodStub` exist under `graphitron-rewrite/src/test/java/` for unit tests but are not on the test-spec classpath.

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

Argument list is emitted by walking `method().params()` in declaration order (see "Call-arg emitter" below). The `ParamSource.Table` slot is filled with the resolved table reference supplied by `GeneratorUtils.ResolvedTableNames.of(tableRef, returnTypeName).jooqTableField()` — the user may declare it at any position, not necessarily first. `ParamSource.Arg` / `ParamSource.Context` slots are emitted the same way condition-method calls handle them.

`ParamSource.DslContext`, `ParamSource.Sources`, `ParamSource.SourceTable`: unreachable for this leaf today — `reflectTableMethod` rejects the first (backlog-gated) and the other two never apply to tableMethods.

Single cardinality: `fetchOne()` instead of `fetch()`, return type `Record`. List: `Result<Record>`. Connection: rejected at validate time (services/tableMethods don't produce connections; cardinality validator already catches this via `validateCardinality` for the table-method variant — extend to the two service variants).

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

1. **Cardinality (all three).** Wrapper must be `Single` or `List`, not `Connection`. `validateCardinality` is already called for `QueryTableMethodTableField`; add the same call to the two service validators.

2. **No `Sourced` parameter at root (both service variants).** `ServiceCatalog.reflectServiceMethod` admits a `ParamSource.Sources` parameter when the method takes `List<RowN<?>>` / `List<RecordN<?>>` / `List<SomeClass>`. At the root, `parentPkColumns` is `List.of()`, so a `RowKeyed`/`RecordKeyed` param carries an empty key column list — the DataLoader batching semantics that shape presumes are not available. Reject at validate time with a message pointing at the batching requirement, rather than generating a method call with nonsensical arguments. Check: `field.method().params().stream().anyMatch(p -> p.source() instanceof ParamSource.Sources)` → validation error.

3. **`@tableMethod` signature (already enforced).** `ServiceCatalog.reflectTableMethod` enforces exactly one `Table<?>` parameter at reflection time; no classifier branch produces `QueryTableMethodTableField` without it. It also currently rejects `DSLContext` parameters on `@tableMethod` methods — that's tracked as the separate Backlog item "`DSLContext` on `@condition` / `@tableMethod` methods". This plan does not lift that gate; `QueryTableMethodTableField` params are limited to `Table` / `Arg` / `Context`.

4. **`DslContext` parameter supported only on `@service` (not `@tableMethod`).** `reflectServiceMethod` admits a `DSLContext` parameter (classified as `ParamSource.DslContext`). The emitter for `QueryServiceTableField` / `QueryServiceRecordField` must thread `graphitronContext(env).getDslContext(env)` into the call at the parameter's declaration-index slot.

## Plan

### Emission

Implement three new emitter methods in `TypeFetcherGenerator`, modelled on `buildQueryTableFetcher`:

- **`buildQueryTableMethodFetcher(QueryTableMethodTableField)`** — the most involved of the three. Emits:
  1. Local `Table<?> table = <MethodClass>.<methodName>(<Tables>.<FOO>, <extracted args...>);` where the `ParamSource.Table` parameter is filled by the resolved table reference and the remaining `callParams()` are expanded via `ArgCallEmitter.buildArgExtraction`. The `conditionsClassName` argument needed by `TextMapLookup` is the target type's `*Conditions` class (same resolution as `QueryTableField.filters()` uses).
  2. `var dsl = graphitronContext(env).getDslContext(env);`
  3. `return dsl.select(<TargetType>.$fields(env.getSelectionSet(), table, env)).from(table).<fetchOne()|fetch()>;`

- **`buildQueryServiceTableFetcher(QueryServiceTableField)`** — single statement: `return <ServiceClass>.<methodName>(<extracted args...>);` Return type `Result<Record>` for list, `Record` for single.

- **`buildQueryServiceRecordFetcher(QueryServiceRecordField)`** — single statement: `return <ServiceClass>.<methodName>(<extracted args...>);` Return type `Object` (matches the existing stub shape; graphql-java coerces).

Switch arms at `TypeFetcherGenerator.java:311/317/318` change from `stub(f)` to the new emitter calls. The three leaf classes move from `NOT_IMPLEMENTED_REASONS` to `IMPLEMENTED_LEAVES`.

**Call-arg emission.** `ArgCallEmitter.buildArgExtraction(CallParam, conditionsClassName)` handles all five `CallSiteExtraction` variants. For service methods whose GraphQL args include text-mapped enums, the `conditionsClassName` lookup target is the `*Conditions` class of the **field's return type** (service-table variant) or a synthesised class if the return type has none (service-record variant returning a scalar). Thread a `conditionsClassName` through the emitter; for `QueryServiceRecordField` with a scalar return, fall back to the Query-level conditions class or refuse `TextMapLookup` at validate time. Decide during implementation — may collapse to "validate-time reject `TextMapLookup` on service-record methods" if no fixture naturally produces it.

### Validator additions

`GraphitronSchemaValidator`:

- `validateQueryServiceTableField` + `validateQueryServiceRecordField`: add `validateCardinality(...)` call and the `Sourced`-parameter rejection described in Invariants #2. Message: `"@service at the root does not support List<Row>/List<Record>/List<Object> batch parameters — the root has no parent context to batch against"`.

- `validateQueryTableMethodTableField`: unchanged beyond what it already does.

### Structural tests

`TypeFetcherGeneratorTest` gains three cases — one per leaf — asserting:

- Emitted method signature (`public static <ReturnType> <fieldName>(DataFetchingEnvironment env)`).
- For `QueryTableMethodTableField`: a `$fields` call is emitted, a call to the method's fully-qualified name is emitted in the `from` position.
- For the two service variants: a direct call to the method is emitted, `$fields` is NOT emitted.

Body-string assertions stay minimal — structural properties only, per the test-tier convention.

### Pipeline test

`GraphitronSchemaBuilderTest`: add an SDL case for each of the three leaves; assert the classifier produces the expected leaf and the generator emits a fetcher method (not a stub). Leverages existing `TypeSpecAssertions.wiringFor(field)`-style helpers where applicable.

### Compile gate

`mvn compile -pl :graphitron-rewrite-test-spec -Plocal-db` must succeed with the new fixtures present. This catches argument-type mismatches, wrong package references, and generic-bound errors against real jOOQ classes.

### Execution tests

Fixture additions to `graphitron-rewrite-test-spec`:

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

- **`ChildField.ServiceTableField.buildServiceRowsMethod` body** — the child service variant currently partitions as `IMPLEMENTED_LEAVES` but its rows method still throws `UnsupportedOperationException` at runtime (`TypeFetcherGenerator.java:1042–1060`). That's a separate fix — the child variant lives inside a DataLoader batch and has the batch-key semantics this plan's root variants deliberately don't — tracking as a follow-up once this plan lands, or as a parallel plan.

- **`MutationField.MutationServiceTableField` / `MutationServiceRecordField`** — analogous shapes on the mutation side, but with write semantics and transaction handling. Covered by the Mutation bodies stub (#4).

- **Federation `_service` / `_entities`** — covered by the federation-jvm transform plan.

- **Polymorphic return types** — `@service` returning an interface/union is explicitly rejected at classify time (`FieldBuilder.java:1197`). Deferred to the interface/union stubs item (#3).

- **Stub reason-string drift fix** — `QueryTableMethodTableField`'s reason string references `#1` (`TypeFetcherGenerator.java:181`) but the leaf belongs under `#7`. Roadmap already flags this as "drift to fix when editing the generator next"; this plan naturally removes the entry, so the drift dissolves.

## Open decisions

- **`TextMapLookup` on service-method args without a return-type `*Conditions` class.** If an execution-test fixture would naturally exercise a service method receiving a text-mapped enum as an arg, we need a resolution target for the map field. Two options: (a) synthesise the map reference against the nearest `*Conditions` class the schema already has; (b) validate-time reject `TextMapLookup` on service/tableMethod args entirely and require the service to accept the raw enum. Defer the call to implementation time when a fixture forces the question.

- **Stub-reason-string reference for #7.** When the three entries leave `NOT_IMPLEMENTED_REASONS`, no other stub entry currently references `#7` in its message — so `#7`'s number in the roadmap stops being load-bearing. Nothing to do unless a future stub falls under #7.
