---
id: R70
title: "Support TableRecord-keyed Map returns on @service rows methods"
status: Ready
bucket: feature
priority: 5
theme: service
depends-on: [emit-record1-keys-instead-of-row1]
---

# Support TableRecord-keyed Map returns on @service rows methods

## Motivation

A child `@service` whose SOURCES parameter is typed `Set<X>` (X a jOOQ `TableRecord` subtype) classifies as `BatchKey.MappedRowKeyed` today; the variant's docstring (`BatchKey.java:247-251`) admits the asymmetry: "Both `Set<RowN<...>>` and `Set<TableRecord>` classify here; the DataLoader key type stays `RowN` regardless of the user's declared element type." That fold is the only place R61's variant-identity-tracks-shape rule is broken — every other variant's `keyElementType()` reflects the developer's chosen shape exactly. The classifier accepts the typed-record element on the input side, but `RowsMethodShape.outerRowsReturnType` (`RowsMethodShape.java:85-96`) builds the expected outer return type as `Map<RowN<...>, V>` from the variant's `keyElementType()`, so the strict `TypeName.equals` check in `ServiceDirectiveResolver.validateChildServiceReturnType` (`ServiceDirectiveResolver.java:276-293`) rejects the natural counterpart `Map<X, V>` where the developer reused the same `TableRecord` across the parameter and the map key.

The motivating real-world fixture, surfaced from a consumer schema (`regelverk_exp.graphqls`):

```java
public Map<KvotesporsmalRecord, OversatteTekster>
        kvotesporsmalNavn(Set<KvotesporsmalRecord> keys) { ... }
```

is rejected with:

> method `kvotesporsmalNavn` ... must return `Map<Row3<String, String, String>, OversatteTekster>` ... got `Map<KvotesporsmalRecord, OversatteTekster>`

forcing the consumer to either rewrite the service in terms of `Row3<...>` (losing the typed-record ergonomics: the parent's PK is then a triple of strings rather than a typed `KvotesporsmalRecord`) or drop down to `Set<RowN<...>>` on the parameter and keep the asymmetry consistent. Both options are worse than letting the consumer write the typed-record shape end-to-end.

The emitter side has the same gap, even for the parameter half that classify already accepts. `TypeFetcherGenerator.buildServiceRowsMethod` (`TypeFetcherGenerator.java:2392-2435`) emits

```java
return ServiceClass.method(keys);
```

against a local `keys` whose declared type is `Set<RowN<...>>` (or `Set<RecordN<...>>` for the `MappedRecordKeyed` arm). When the developer's method signature says `Set<TableRecord>` instead of `Set<RowN<...>>`, that emission miscompiles at the Java call site. The R32 changelog entry calls this out as a known follow-up:

> element-shape conversion when the developer's `Sources` is `Set<TableRecord>` / `List<TableRecord>` (deferred until a real schema needs it; builds on top of R61)

The motivating schema is the "real schema" the deferral was waiting on. R70 closes the gap by extending `BatchKey.ParentKeyed` with two new permits — `TableRecordKeyed` and `MappedTableRecordKeyed` — that carry the developer's typed `TableRecord` element class on the variant itself. The classifier reroutes `Set<TableRecord>` / `List<TableRecord>` onto these new permits; `keyElementType()` then returns the typed `TableRecord` class directly, which propagates through `RowsMethodShape.outerRowsReturnType` to make the validator's expected outer return type `Map<KvotesporsmalRecord, V>` automatically. The emitter site needs no conversion: `keys` is already locally typed `Set<KvotesporsmalRecord>`, the developer's signature matches, and the existing `return ServiceClass.method(keys)` line type-checks. The asymmetry collapses by extending the variant taxonomy, not by papering over the mismatch in the emitter.

## Why this depends on R61

R61 (`emit-record1-keys-instead-of-row1.md`) established the design principle that **`BatchKey` variant identity tracks the developer's source shape**. After R61: `RowKeyed` / `MappedRowKeyed` / `LifterRowKeyed` carry `RowN<...>` keys (jOOQ SQL-expression types, no application-side accessors); `RecordKeyed` / `MappedRecordKeyed` / `AccessorKeyedSingle` / `AccessorKeyedMany` carry `RecordN<...>` keys (with `value1()` and `into(Table)`). Both Row and Record source shapes are first-class on `@service`; the developer chooses by writing `Set<Row1<...>>` vs `Set<Record1<...>>`, and `keyElementType()` is the single switch point that propagates the choice through the validator and emitter.

R70 extends that taxonomy with two new permits — `TableRecordKeyed` and `MappedTableRecordKeyed` — for the third source shape `Set<X>` / `List<X>` where `X extends org.jooq.TableRecord`. Today's classifier (`ServiceCatalog.java:627-632`) folds `Set<TableRecord>` onto `MappedRowKeyed`, and the `MappedRowKeyed` docstring (`BatchKey.java:247-251`) admits the asymmetry: "Both `Set<RowN<...>>` and `Set<TableRecord>` classify here; the DataLoader key type stays `RowN`." That fold is the only place R61's variant-identity-tracks-shape rule is broken; R70 closes it by giving the typed-record source its own variants.

R74 (`accessor-row-record-shapes.md:17`) decided the same question for the auto-lift accessor arm: distinct sealed permits per shape, rejecting an enum discriminator and rejecting fold-into-existing because the TableRecord arms carry different data ("an element class for the `__elt.into(...)` cast"). R70 mirrors R74's pattern on the developer-facing source side: each new permit carries `Class<? extends TableRecord> elementClass` alongside `parentKeyColumns`, and `keyElementType()` returns the typed `TableRecord` class directly.

The Record-shape work landed in R61 is the prerequisite specifically because R70 reuses R61's `record.into(Table)` projection at the parent-key extraction site: `GeneratorUtils.buildKeyExtraction`'s new arm emits `((Record) env.getSource()).into(Tables.KVOTESPORSMAL)`, structurally identical to the existing `RecordKeyed` / `MappedRecordKeyed` arm but typed to the developer's element class. Implementing R70 before R61 would have forced inventing the typed-`into` projection from scratch on a `RowN`-only baseline.

## Design

### New `BatchKey` permits

Two new sealed permits on `BatchKey.ParentKeyed`, parallel in structure to the existing `RowKeyed` / `MappedRowKeyed` and `RecordKeyed` / `MappedRecordKeyed` pairs:

```java
sealed interface ParentKeyed extends BatchKey
    permits RowKeyed,        RecordKeyed,
            MappedRowKeyed,  MappedRecordKeyed,
            TableRecordKeyed,         // new — List<X extends TableRecord>
            MappedTableRecordKeyed;   // new — Set<X  extends TableRecord>

record TableRecordKeyed(
        List<ColumnRef> parentKeyColumns,
        Class<? extends TableRecord> elementClass) implements ParentKeyed {
    @Override public TypeName keyElementType() { return ClassName.get(elementClass); }
    @Override public String   javaTypeName()   { return "List<" + elementClass.getSimpleName() + ">"; }
}

record MappedTableRecordKeyed(
        List<ColumnRef> parentKeyColumns,
        Class<? extends TableRecord> elementClass) implements ParentKeyed {
    @Override public TypeName keyElementType() { return ClassName.get(elementClass); }
    @Override public String   javaTypeName()   { return "Set<"  + elementClass.getSimpleName() + ">"; }
}
```

`keyElementType()` returning the typed `TableRecord` class is what makes R70 collapse: `RowsMethodShape.outerRowsReturnType` already builds `Map<keyElementType, V>` (or `List<V>` / `List<List<V>>` for positional) from this single switch, so the validator's expected outer return type and the emitter's parameter / return shapes both come out as `Map<KvotesporsmalRecord, V>` automatically. No multi-shape acceptance, no `RowsCallShape`, no validator widening — the variant carries the shape and every site downstream reads it through the existing single-source-of-truth call.

### Classifier reroute

`ServiceCatalog.classifySourcesType:627-632` today routes the `Set<TableRecord>` / `List<TableRecord>` element branch to `MappedRowKeyed` / `RowKeyed`. The reroute changes those lines to construct `MappedTableRecordKeyed` / `TableRecordKeyed`, threading the `elementClass` local already in hand at line 628. `MappedRowKeyed`'s docstring (`BatchKey.java:247-251`) loses its "Set<TableRecord> also classifies here" sentence; the variant becomes shape-pure, matching its `RecordKeyed` sibling.

The classifier also gains a parent-table consistency check: when the developer's `elementClass` is a `TableRecord` subtype, look up the parent's expected `TableRecord` class (via `BuildContext.tableRecordClass(parentTypeName)`) and reject mismatches with a typed `Rejection.AuthorError.Structural` carrying a candidate-hint pointer at the expected `TableRecord` simple name. Without this check, `Set<FilmRecord>` against a `Kvotesporsmal` parent would compile through R70's typed extraction step and silently produce wrong-typed records.

### Key extraction

`GeneratorUtils.buildKeyExtraction`'s `ParentKeyed` switch grows one new pair of arms — `TableRecordKeyed` / `MappedTableRecordKeyed` both emit:

```java
((Record) env.getSource()).into(Tables.KVOTESPORSMAL)
```

The `Tables.KVOTESPORSMAL` reference is resolved from the variant's `elementClass` at emit time (the `TableRecord` subtype's declaring `Tables` class plus its simple-name field). The shape is structurally identical to the existing `RecordKeyed` / `MappedRecordKeyed` arm (`env.getSource().into(table.col1, ...)`), but projects through `Table<R>.into(...)` instead of column-tuple `into(...)` so the result is a typed `KvotesporsmalRecord` rather than a generic `Record3<String, String, String>`.

### Emitter: zero conversion

`TypeFetcherGenerator.buildServiceRowsMethod:2392-2435` becomes simpler, not more complex. Today's one-line body emission

```java
return ServiceClass.method(keys);
```

works for the new variants by construction: `keys` is locally typed `Set<KvotesporsmalRecord>` (from `batchKey.keyElementType()`), the developer signs `Set<KvotesporsmalRecord>`, the call type-checks. The deferred-conversion comment block at `TypeFetcherGenerator.java:2423-2428` ("Sources param passes through `keys` directly. Element-shape conversion … is deferred") drops out: there is nothing to convert. The `@DependsOnClassifierCheck` on `buildServiceRowsMethod` stays; its description widens by one phrase ("…and `TableRecordKeyed` / `MappedTableRecordKeyed` for typed jOOQ `TableRecord` sources").

### What stays unchanged

- `MappedRowKeyed` / `RowKeyed`: their docstrings tighten to "only `Set<RowN<...>>` / `List<RowN<...>>` classify here." The variants are otherwise unchanged.
- `MappedRecordKeyed` / `RecordKeyed`: untouched. They never accepted `Set<TableRecord>` and don't start now.
- `LifterRowKeyed`: untouched (R71's surface).
- `AccessorKeyedSingle` / `AccessorKeyedMany`: untouched (R74's surface; auto-derived, no developer-facing source).
- DataLoader factory selection (`buildDataLoaderFactoryCall`'s `isMapped` switch on `MappedRowKeyed || MappedRecordKeyed`): gains one disjunct for `MappedTableRecordKeyed`. No shape change at the registration boundary; `K` becomes the typed `TableRecord` class but the factory call site is K-agnostic.
- Root-level `@service` validation (the `computeExpectedServiceReturnType` arm in `ServiceDirectiveResolver.java:220-249`): unchanged. Root has no DataLoader, no rows method, no parent-keyed source.

## Implementation

### Phase 1 — variants + classifier reroute

- Add `TableRecordKeyed(parentKeyColumns, elementClass)` and `MappedTableRecordKeyed(parentKeyColumns, elementClass)` on `BatchKey.ParentKeyed`. `keyElementType()` returns `ClassName.get(elementClass)`; `javaTypeName()` mirrors the `containerType` helper used by sibling variants.
- Reroute `ServiceCatalog.classifySourcesType` lines 627-632: the TableRecord element branch constructs the new variants, threading `elementClass`.
- Tighten `MappedRowKeyed` / `RowKeyed` docstrings: drop the "Set<TableRecord> also classifies here" sentence on `MappedRowKeyed:247-251` (and any equivalent on `RowKeyed`). The variants are now shape-pure.
- Add parent-table consistency check at the resolver site (`ServiceDirectiveResolver.resolve`, post-classify): the resolver already has `parentTypeName` and routes through `JooqCatalog.findRecordClass(parentTableSqlName)`; reject mismatches with `Rejection.structural` carrying a candidate-hint pointer at the parent's expected `TableRecord` simple name. The variant's `elementClass()` is the readable surface from the resolver. (Doing it at the classifier would force threading the parent record class through `ServiceCatalog.classifySourcesType` / `reflectServiceMethod`; the resolver has it already.)
- Update existing `ServiceCatalogTest` cells: `reflectServiceMethod_tableRecordSources_classifiedAsRowKeyed` (line ~119) flips to assert `TableRecordKeyed`; `reflectServiceMethod_setOfTableRecordSources_classifiedAsMappedRowKeyed` (line ~211) flips to assert `MappedTableRecordKeyed` and renames accordingly.
- **Tests**: L1 (`BatchKeyTest`) — extend the per-variant `keyElementType()` / `javaTypeName()` parameterised cells with `TableRecordKeyed` / `MappedTableRecordKeyed`. L4 (`GraphitronSchemaBuilderTest`) — positive cell for `Set<KvotesporsmalRecord>`-style source classifying as `MappedTableRecordKeyed`; rejection cell for `Set<FilmRecord>` against a non-Film parent.

### Phase 2 — key extraction + emitter sweep

- `GeneratorUtils.buildKeyExtraction`: add the `TableRecordKeyed` / `MappedTableRecordKeyed` arm emitting `((Record) env.getSource()).into(Tables.X)`. Resolve `Tables.X` at emit time from the variant's `elementClass` (declaring `Tables` class + simple-name field).
- `TypeFetcherGenerator.buildServiceRowsMethod`: drop the deferred-conversion comment block at `:2423-2428`; the existing `return ServiceClass.method(keys)` line covers the new variants by construction. Widen the `@DependsOnClassifierCheck` description by one phrase to mention the new permits.
- Walk every `switch(batchKey)` site that pattern-matches on `ParentKeyed` permits and add the two new arms; the seal makes those exhaustive (`javac` lists every site as a missing-arm warning until covered, e.g. `GeneratorUtils.buildKeyExtraction`).
- Three sites are `isMapped` *instanceof* checks, not switches, and must be widened by hand: `RowsMethodShape.outerRowsReturnType` (`:86-87`), `TypeFetcherGenerator.buildServiceDataFetcher` (`:2324-2325`, the DataLoader-factory dispatch), and `TypeFetcherGenerator.buildServiceRowsMethod` (`:2401-2402`). Each gains one disjunct for `MappedTableRecordKeyed`. Locate via `grep -n "instanceof BatchKey.MappedRowKeyed"`.
- **Tests**: L4 (`TypeFetcherGeneratorTest` / `FetcherPipelineTest`) — pipeline assertions for the key-extraction emit (`(Record) env.getSource().into(Tables.KVOTESPORSMAL)`) and the rows-method signature `Map<KvotesporsmalRecord, V> name(Set<KvotesporsmalRecord>, DataFetchingEnvironment)`. L5 (compile spec) — `graphitron-sakila-example` fixture: one `@service` rows method `Map<FilmRecord, String> method(Set<FilmRecord>)`. L6 (execution) — `GraphQLQueryTest` query exercising the typed-record path end-to-end against the sakila PostgreSQL fixture, mirroring `films_titleUppercase_resolvesViaServiceRecordFieldDataLoader`.

Phases 1 and 2 must land in the same trunk-bound push: between Phase 1 (new variants exist, classifier routes onto them) and Phase 2 (emitter handles them), the `switch` exhaustiveness errors break compilation. Treat the two phases as a single landing for review purposes.

### Phase 3 — fixture parity sweep

- R32 changelog entry's "open follow-ups" bullet ("element-shape conversion when the developer's `Sources` is `Set<TableRecord>` / `List<TableRecord>` — deferred until a real schema needs it; builds on top of R61"): closed by R70's landing. Append a new changelog entry for R70 summarising the variant addition + classifier reroute; the R32 follow-up bullet stays for historical accuracy.
- R61's "out of scope" bullet ("Element-shape conversion for `Set<TableRecord>` / `List<TableRecord>` developer signatures") gets a "closed by R70" annotation.
- `TestServiceStub.getFilmsWithSetOfTableRecordSources` currently exists only for parameter-side classification and throws on call. After Phase 2 it becomes a real fixture: add a sibling `Map<FilmRecord, String> filmTitleUppercaseTableRecordKeyed(Set<FilmRecord> keys)` on the same dummy service class to anchor compile- and execution-tier coverage.

## Tests

The phased plan above lists tests inline. Aggregated by tier:

- **Unit (L1)** — `BatchKeyTest`: two new parameterised cells pin `TableRecordKeyed` / `MappedTableRecordKeyed` on `keyElementType() == ClassName.get(elementClass)` and the matching `javaTypeName()` shape, alongside the existing seven-variant cells.
- **Pipeline (L4)** — `GraphitronSchemaBuilderTest`: positive cell per new variant × `(Set, List)` axis; rejection cell for the parent-table-mismatch case (e.g. `Set<FilmRecord>` against a non-Film parent). `TypeFetcherGeneratorTest` / `FetcherPipelineTest`: structural body coverage for the `((Record) env.getSource()).into(Tables.X)` extraction and the typed `Set<X>` rows-method parameter.
- **Compile (L5)** — `graphitron-sakila-example` fixture: one `@service` rows method using `Map<FilmRecord, V> method(Set<FilmRecord>)`; the existing `mvn compile -Plocal-db` gate catches any miscompile.
- **Execution (L6)** — `GraphQLQueryTest`: one query exercising the typed-record path end-to-end against the sakila PostgreSQL fixture.

## Open questions

1. **Single-cardinality `TableRecord` parameter shape (`X method(X parent)`).** R70 covers `Set<X>` (mapped) and `List<X>` (positional). A single-cardinality positional signature would be a different rows-method shape (`X` per parent rather than `List<X>` batched) driven by a `LoaderDispatch.LOAD_ONE` arm. Confirm during Phase 2 whether the same `TableRecordKeyed` permit covers single cardinality cleanly or whether it warrants a third permit.
2. **`TableRecord` hashing / equality in the DataLoader.** `org.jooq.impl.AbstractRecord` implements `equals` / `hashCode` based on the record's value array, which is what the DataLoader needs for map-keyed batching. Confirm during Phase 2 that the sakila execution-tier test exercises duplicate keys (two parents pointing at the same FK target) and that the DataLoader correctly de-duplicates them at the load boundary.
3. **Custom-scalar V-types in the typed-record map.** Same status as in any other variant: `RowsMethodShape.strictPerKeyType` returns `null` for custom GraphQL scalars and enums until R45 lands; the validator skips the strict check on null. R70 inherits this without regression.

## Roadmap entries (siblings / dependencies)

- **Depends on** [`emit-record1-keys-instead-of-row1.md`](emit-record1-keys-instead-of-row1.md) (R61): R61 established the variant-identity-tracks-shape principle and shipped the `Record.into(Table)`-style key construction for the `RecordKeyed` / `MappedRecordKeyed` arms. R70's `((Record) env.getSource()).into(Tables.X)` extraction is a typed sibling of that pattern, and the new permits slot into the seal R61 left open for shape-pure variants.
- **Mirrors design pattern from** [`accessor-row-record-shapes.md`](accessor-row-record-shapes.md) (R74): R74 added distinct sealed permits per shape on the auto-lift accessor side rather than folding shapes onto an enum or onto existing variants. R70 applies the same principle to the developer-facing source side; both items confine the `elementClass` carrier to the variants that actually need it.
- **Closes deferral from** R32 (changelog entry under `service-rows-method-body`, commits `64b8e2c` + `e28540b` + `83bcfdf`): R32's "element-shape conversion when the developer's `Sources` is `Set<TableRecord>` / `List<TableRecord>` (deferred until a real schema needs it; builds on top of R61)" bullet is the open follow-up R70 closes — by removing the conversion entirely, not by emitting it.
- **Coordinates with** [`typed-context-value-registry.md`](typed-context-value-registry.md) (R45): when R45 surfaces typed Java classes for custom GraphQL scalars and enums, the `strictPerKeyType` null arm shrinks; R70 inherits that automatically.
