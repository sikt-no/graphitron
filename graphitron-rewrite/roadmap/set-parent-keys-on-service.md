---
title: "`Set<T>` parent-keys on `@service` methods → `MappedBatchLoader`"
status: Spec
bucket: architecture
priority: 7
---

# `Set<T>` parent-keys on `@service` methods → `MappedBatchLoader`

## Decision

Support `Set<TableRecord>` as the batch-key sources type on `@service` child fields.
Use `MappedBatchLoader` (key=`RowN<...>`, value=`V`, keys passed as `Set`, results as
`Map`) rather than rejecting and requiring `List`. Rationale: the `Set` carries a
semantic contract that the caller deduplicates keys and doesn't need positional
alignment; `MappedBatchLoader` honours that contract without lossy conversion.

This unblocks ~25 fields in the `opptak-subgraph` production schema that declare their
service methods with `Set<SomeRecord>` keys and receive "unrecognized sources type".

## Root Cause

`ServiceCatalog.classifySourcesType` (line 369) guards on
`pt.getRawType() != java.util.List.class` and returns `Optional.empty()` immediately
for any `Set<?>`. No `Set` branch exists. The downstream error message is
"unrecognized sources type: 'java.util.Set<...RecordName>'", which is emitted as
`[author-error]` — but the schema author is correct; the classifier is too narrow.

`dtoSourcesRejectionReason` has the same `List`-only guard, so `Set<SomeDto>` also
produces "unrecognized sources type" instead of the more helpful "DTO sources not
supported" message. Fix both together.

## Model: `BatchKey.MappedKeyed`

File: `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/BatchKey.java`

Add a third variant alongside `RowKeyed` and `RecordKeyed`. The variant carries
identical `keyColumns` (always the parent type's PK) but signals `MappedBatchLoader`
semantics: keys flow in as a `Set`, results come back as a `Map`.

```java
/**
 * Variant produced when the user's service method declares its SOURCES parameter
 * as {@code Set<TableRecord>}, {@code Set<RowN<...>>}, or {@code Set<RecordN<...>>}.
 * The generator emits a {@code MappedBatchLoader} (or its {@code WithContext} variant)
 * so the DataLoader collects a {@code Set<KeyType>} and the rows method returns
 * {@code Map<KeyType, V>} rather than a positional {@code List<V>}.
 *
 * <p>{@code keyColumns} is always the parent type's PK — identical semantics to
 * {@link RowKeyed}. The row extraction at the call site is {@code DSL.row(...)},
 * same as {@link RowKeyed}; only the container type (Set vs List) and the loader
 * factory call change.
 */
record MappedKeyed(List<ColumnRef> keyColumns) implements BatchKey {
    @Override
    public String javaTypeName() {
        if (keyColumns.isEmpty()) return "java.util.Set<?>";
        var typeArgs = keyColumns.stream()
            .map(ColumnRef::columnClass)
            .collect(Collectors.joining(", "));
        return "java.util.Set<org.jooq.Row" + keyColumns.size() + "<" + typeArgs + ">>";
    }
}
```

Update the `permits` clause: `permits BatchKey.RowKeyed, BatchKey.RecordKeyed, BatchKey.MappedKeyed`.

## Classifier: `ServiceCatalog.classifySourcesType`

File: `graphitron/src/main/java/no/sikt/graphitron/rewrite/ServiceCatalog.java`, line 369

Replace the single `List.class` guard with a dual-type check:

```java
if (!(paramType instanceof java.lang.reflect.ParameterizedType pt)) {
    return Optional.empty();
}
boolean isList = pt.getRawType() == java.util.List.class;
boolean isSet  = pt.getRawType() == java.util.Set.class;
if (!isList && !isSet) return Optional.empty();
```

After the three existing element-type arms produce a `BatchKey`, remap for `Set`:

```java
if (isSet) {
    return result.map(bk -> new BatchKey.MappedKeyed(
        switch (bk) {
            case BatchKey.RowKeyed rk    -> rk.keyColumns();
            case BatchKey.RecordKeyed rk -> rk.keyColumns();
            case BatchKey.MappedKeyed mk -> mk.keyColumns(); // unreachable, but exhaustive
        }));
}
return result;
```

`dtoSourcesRejectionReason` (line 410): apply the same `isList`/`isSet` dual check so
`Set<SomeNonRecordClass>` produces "DTO sources not supported" instead of null
(which falls through to the generic "unrecognized sources type" message).

## Codegen: `GeneratorUtils`

File: `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java`

**`keyElementType(BatchKey)`** (line 148): add a `MappedKeyed` arm returning the same
`RowN<...>` TypeName as `RowKeyed` (key element type is identical; only the container
differs):

```java
case BatchKey.MappedKeyed mk -> buildRowKeyType(mk.keyColumns());
```

**`buildKeyExtraction(BatchKey, ...)`** (line 259): add a `MappedKeyed` arm with
identical logic to `RowKeyed` (the per-parent key extraction, `DSL.row(...)`, is the
same regardless of whether the DataLoader is positional or mapped):

```java
case BatchKey.MappedKeyed mk -> {
    // Identical extraction to RowKeyed — key type and DSL.row() call are the same.
    String tableField = parentTable.javaFieldName();
    List<ColumnRef> pkCols = mk.keyColumns();
    var rowArgs = CodeBlock.builder();
    for (int i = 0; i < pkCols.size(); i++) {
        if (i > 0) rowArgs.add(", ");
        rowArgs.add("(($T) env.getSource()).get($T.$L.$L)",
            RECORD, tablesClass, tableField, pkCols.get(i).javaName());
    }
    yield CodeBlock.builder()
        .addStatement("$T key = $T.row($L)", keyType, DSL, rowArgs.build())
        .build();
}
```

`buildKeyExtractionWithNullCheck` is only called for `SplitTableField` (single-cardinality
`@splitQuery`); it does not need a `MappedKeyed` arm and retains its existing
`RowKeyed`-only guard.

## Codegen: `TypeFetcherGenerator`

File: `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`

### `buildServiceDataFetcher` (line 1451)

This method currently emits `DataLoaderFactory.newDataLoaderWithContext(...)`. That
method does not exist on `DataLoaderFactory` (see note in `buildSplitQueryDataFetcher`,
line 1534). Fix both the existing `RowKeyed`/`RecordKeyed` case and the new
`MappedKeyed` case in one pass:

**Existing (`RowKeyed`, `RecordKeyed`) — corrected**:

```java
// (keys, batchEnv) → positional BatchLoaderWithContext overload
"    .computeIfAbsent(name, k -> $T.newDataLoader($L));\n",
loaderType, DATA_LOADER_FACTORY, lambdaBlock
```

The lambda `(keys, batchEnv) -> ...` is still correct; overload resolution binds it to
`BatchLoaderWithContext<K,V>` — the same mechanism the Split path uses (see
`buildSplitQueryDataFetcher`, line 1589).

**New (`MappedKeyed`)**:

Branch on `batchKey instanceof BatchKey.MappedKeyed` before building the loader:

```java
boolean isMapped = batchKey instanceof BatchKey.MappedKeyed;
TypeName valueType  = isList ? ParameterizedTypeName.get(LIST, RECORD) : RECORD;
TypeName mapType    = ParameterizedTypeName.get(MAP, keyType, valueType);
TypeName returnType = ParameterizedTypeName.get(COMPLETABLE_FUTURE, isMapped ? mapType : valueType);
```

For the mapped case the lambda's inferred type is `MappedBatchLoaderWithContext<K,V>`:

```java
// lambda: (Set<KeyType>, BatchLoaderEnvironment) → CompletableFuture<Map<KeyType,V>>
"    .computeIfAbsent(name, k -> $T.newMappedDataLoader($L));\n",
loaderType, DATA_LOADER_FACTORY, lambdaBlock
```

The `loader.load(key, env)` call at the bottom is the same for both variants; the
`DataLoader<K,V>` interface does not change.

Add `MAP` as a constant: `ClassName.get(java.util.Map.class)` (alongside the existing
`LIST` constant).

### `buildServiceRowsMethod` (line 1501)

For `MappedKeyed`, the stub takes `Set<KeyType>` and returns `Map<KeyType, V>`:

```java
boolean isMapped = bkf.batchKey() instanceof BatchKey.MappedKeyed;
TypeName keysContainerType = isMapped
    ? ParameterizedTypeName.get(ClassName.get(java.util.Set.class), keysElementType)
    : ParameterizedTypeName.get(LIST, keysElementType);

TypeName rowsReturnType;
if (isMapped) {
    TypeName singleValue = isList ? ParameterizedTypeName.get(LIST, RECORD) : RECORD;
    rowsReturnType = ParameterizedTypeName.get(MAP, keysElementType, singleValue);
} else {
    rowsReturnType = isList ? ParameterizedTypeName.get(LIST, listOfRecord) : listOfRecord;
}

return MethodSpec.methodBuilder(bkf.rowsMethodName())
    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
    .returns(rowsReturnType)
    .addParameter(keysContainerType, "keys")
    .addParameter(ENV, "env")
    .addParameter(SELECTED_FIELD, "sel")
    .addStatement("throw new $T()", UnsupportedOperationException.class)
    .build();
```

## Tests

### `ServiceCatalogTest` (unit)

Add alongside `reflectServiceMethod_tableRecordSources_classifiedAsRowKeyed`:

- `classifySourcesType_setOfTableRecord_classifiedAsMappedKeyed` — `Set<FilmRecord>` →
  `BatchKey.MappedKeyed(filmPk)`.
- `classifySourcesType_setOfRow1_classifiedAsMappedKeyed` — `Set<Row1<Integer>>` →
  `BatchKey.MappedKeyed(filmPk)`.
- `classifySourcesType_setOfDto_rejectWithDtoMessage` — `Set<SomeNonRecordClass>` →
  failure reason contains "DTO sources" (not "unrecognized sources type").

### `TypeFetcherGeneratorTest` (structural)

Add a `serviceField_mapped_*` fixture mirroring the existing `serviceField()` fixture
with `BatchKey.MappedKeyed`:

- `serviceField_mapped_list_dataFetcherCallsNewMappedDataLoader` — asserts the
  fetcher body contains `newMappedDataLoader` (not `newDataLoaderWithContext`).
- `serviceField_mapped_list_rowsMethodTakesSetAndReturnsMap` — asserts
  `keys` parameter is `Set<Row1<Integer>>` and return is `Map<Row1<Integer>, List<Record>>`.
- `serviceField_mapped_single_rowsMethodReturnsScalarMap` — single cardinality:
  return is `Map<Row1<Integer>, Record>`.

Regression check: the existing `serviceField_*` tests for `RowKeyed` should continue
to pass with the API-call fix (`newDataLoader` instead of `newDataLoaderWithContext`).
Update assertion strings accordingly.

## Out of Scope

- **Executing the rows method** — the rows stub still throws `UnsupportedOperationException`.
  This spec adds correct classification and stub shape; calling the actual service method
  is a separate story (tracked by `service-context-value-registry.md` and the broader
  service-field execution work).
- **`@splitQuery` fields** — use `BatchKey.RowKeyed`/`RecordKeyed` and are generated
  by `buildSplitQueryDataFetcher`, not `buildServiceDataFetcher`. No change.
- **`RecordKeyed` + `Set`** — `Set<RecordN<...>>` maps to `MappedKeyed` by the
  classifier (same keyColumns). The generated key type uses `RowN` because
  `buildRowKeyType` is reused; the `RecordN` variant is not needed for mapped loaders.

## Success Criteria

- [ ] `mvn compile -pl graphitron-rewrite` passes
- [ ] `ServiceCatalogTest` new cases pass
- [ ] `TypeFetcherGeneratorTest` new cases pass; existing `serviceField_*` cases pass
  with updated `newDataLoader` assertion
- [ ] Build of `opptak-subgraph` (or a schema that reproduces the pattern) no longer
  emits "unrecognized sources type" for `Set<...Record>` parameters
