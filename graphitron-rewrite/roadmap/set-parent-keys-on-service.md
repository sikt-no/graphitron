---
title: "`Set<T>` parent-keys on `@service` methods → `MappedBatchLoader`"
status: Ready
bucket: architecture
priority: 7
---

# `Set<T>` parent-keys on `@service` methods → `MappedBatchLoader`

## Decision

Support `Set<TableRecord>`, `Set<RowN<...>>`, and `Set<RecordN<...>>` as the batch-key
sources type on `@service` child fields. Use `MappedBatchLoader` (key=`RowN<...>` or
`RecordN<...>`, value=`V`, keys passed as `Set`, results as `Map`) rather than rejecting
and requiring `List`. Rationale: the `Set` carries a semantic contract that the caller
deduplicates keys and doesn't need positional alignment; `MappedBatchLoader` honours
that contract without lossy conversion.

This unblocks ~25 fields in the `opptak-subgraph` production schema that declare their
service methods with `Set<SomeRecord>` keys and receive "unrecognized sources type".

The four-way design (positional Row, positional Record, mapped Row, mapped Record)
mirrors the existing two-way (Row, Record) split exactly: container axis (`List` vs
`Set`) × key-shape axis (`Row` vs `Record`). Splitting now, while we're already
extending the hierarchy, keeps every downstream switch shape-aware so the rows-method
body story (calling the user's service with an element-typed Set) doesn't have to
re-discriminate at codegen time.

## Root Cause

`ServiceCatalog.classifySourcesType` (line 369) guards on
`pt.getRawType() != java.util.List.class` and returns `Optional.empty()` immediately
for any `Set<?>`. No `Set` branch exists. The downstream error message is
"unrecognized sources type: 'java.util.Set<...RecordName>'", which is emitted as
`[author-error]`, but the schema author is correct; the classifier is too narrow.

`dtoSourcesRejectionReason` has the same `List`-only guard, so `Set<SomeDto>` also
produces "unrecognized sources type" instead of the more helpful "DTO sources not
supported" message. Fix both together.

## Model: split `BatchKey` into four variants

File: `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/BatchKey.java`

The four variants are the cross-product of two independent axes:

| key shape ↓ / container → | `List<...>` (positional) | `Set<...>` (mapped) |
|----------------------------|--------------------------|-------------------------|
| `RowN<...>`                | `RowKeyed` (existing)    | `MappedRowKeyed` (new)  |
| `RecordN<...>`             | `RecordKeyed` (existing) | `MappedRecordKeyed` (new) |

Lift `keyColumns()` to the sealed interface so switches can group by shape via
multi-pattern arms (`case RowKeyed _, MappedRowKeyed _ -> bk.keyColumns()`) without
re-binding identifiers.

```java
public sealed interface BatchKey
        permits BatchKey.RowKeyed, BatchKey.RecordKeyed,
                BatchKey.MappedRowKeyed, BatchKey.MappedRecordKeyed {

    /** PK columns from the parent table; may be empty when the parent has no backing table. */
    List<ColumnRef> keyColumns();

    /**
     * Fully qualified generic Java type name for the SOURCES parameter:
     * {@code List<RowN<...>>}, {@code List<RecordN<...>>}, {@code Set<RowN<...>>},
     * or {@code Set<RecordN<...>>}.
     */
    String javaTypeName();

    record RowKeyed(List<ColumnRef> keyColumns) implements BatchKey {
        @Override public String javaTypeName() { return containerType("List", "Row", keyColumns); }
    }
    record RecordKeyed(List<ColumnRef> keyColumns) implements BatchKey {
        @Override public String javaTypeName() { return containerType("List", "Record", keyColumns); }
    }
    record MappedRowKeyed(List<ColumnRef> keyColumns) implements BatchKey {
        @Override public String javaTypeName() { return containerType("Set", "Row", keyColumns); }
    }
    record MappedRecordKeyed(List<ColumnRef> keyColumns) implements BatchKey {
        @Override public String javaTypeName() { return containerType("Set", "Record", keyColumns); }
    }

    private static String containerType(String container, String shape, List<ColumnRef> cols) {
        if (cols.isEmpty()) return "java.util." + container + "<?>";
        var typeArgs = cols.stream().map(ColumnRef::columnClass).collect(Collectors.joining(", "));
        return "java.util." + container + "<org.jooq." + shape + cols.size() + "<" + typeArgs + ">>";
    }
}
```

Note: `MappedRowKeyed.javaTypeName()` always renders the `Row` shape regardless of
whether the user wrote `Set<RowN<...>>` or `Set<TableRecord>`; both classify as
`MappedRowKeyed`. This mirrors `RowKeyed`'s existing behaviour for `List<RowN<...>>` vs
`List<TableRecord>`. Element-shape (`RowN` vs `TableRecord`) is **not** preserved on the
variant: `MethodRef.Param.Sourced.typeName()` derives from `BatchKey.javaTypeName()`, so
the user's original type is gone after classification. The future rows-method body
emitter (see Out of Scope and `service-rows-method-body.md`) recovers it by re-reflecting
the service method or by adding a `BatchKey.elementShape()` accessor at that time;
element-shape only affects one site (the optional conversion before invoking the user's
service), so deferring is genuinely cheap.

## Classifier: `ServiceCatalog.classifySourcesType`

File: `graphitron/src/main/java/no/sikt/graphitron/rewrite/ServiceCatalog.java`, line 369

Replace the single `List.class` guard with a dual-type check, then pick the variant
based on both axes (container × element shape):

```java
if (!(paramType instanceof java.lang.reflect.ParameterizedType pt)) {
    return Optional.empty();
}
boolean isList = pt.getRawType() == java.util.List.class;
boolean isSet  = pt.getRawType() == java.util.Set.class;
if (!isList && !isSet) return Optional.empty();

java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
if (typeArgs.length != 1) return Optional.empty();
java.lang.reflect.Type elementType = typeArgs[0];

if (elementType instanceof java.lang.reflect.ParameterizedType ept
        && ept.getRawType() instanceof Class<?> rawClass) {
    String rawName = rawClass.getName();
    if (rawName.startsWith("org.jooq.Row") && rawName.substring("org.jooq.Row".length()).matches("\\d+")) {
        return Optional.of(isSet
            ? new BatchKey.MappedRowKeyed(parentPkColumns)
            : new BatchKey.RowKeyed(parentPkColumns));
    }
    if (rawName.startsWith("org.jooq.Record") && rawName.substring("org.jooq.Record".length()).matches("\\d+")) {
        return Optional.of(isSet
            ? new BatchKey.MappedRecordKeyed(parentPkColumns)
            : new BatchKey.RecordKeyed(parentPkColumns));
    }
} else if (elementType instanceof Class<?> elementClass
        && org.jooq.TableRecord.class.isAssignableFrom(elementClass)) {
    return Optional.of(isSet
        ? new BatchKey.MappedRowKeyed(parentPkColumns)   // TableRecord uses Row-shape keys
        : new BatchKey.RowKeyed(parentPkColumns));
}

return Optional.empty();
```

`dtoSourcesRejectionReason` (line 410): apply the same `isList`/`isSet` dual check so
`Set<SomeNonRecordClass>` produces "DTO sources not supported" instead of null
(which falls through to the generic "unrecognized sources type" message).

## Codegen: `GeneratorUtils`

File: `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/GeneratorUtils.java`

**`keyElementType(BatchKey)`** (line 148): group by key shape using multi-pattern
arms. The key element type is the same for positional and mapped within a shape;
only the container (List vs Set) differs, and the container is set by the lambda /
rows-method shape, not by `keyElementType`.

```java
static TypeName keyElementType(BatchKey batchKey) {
    return switch (batchKey) {
        case BatchKey.RowKeyed _, BatchKey.MappedRowKeyed _       -> buildRowKeyType(batchKey.keyColumns());
        case BatchKey.RecordKeyed _, BatchKey.MappedRecordKeyed _ -> buildRecordNKeyType(batchKey.keyColumns());
    };
}
```

**`buildKeyExtraction(BatchKey, ...)`** (line 259): same shape-based grouping. The
per-parent key extraction (`DSL.row(...)` for Row-shape, `record.into(...)` for
Record-shape) is identical regardless of mapped vs positional.

```java
static CodeBlock buildKeyExtraction(BatchKey batchKey, TableRef parentTable, String jooqPackage) {
    TypeName keyType = keyElementType(batchKey);
    var tablesClass = ResolvedTableNames.ofTable(parentTable, jooqPackage).tablesClass();
    String tableField = parentTable.javaFieldName();
    List<ColumnRef> pkCols = batchKey.keyColumns();
    return switch (batchKey) {
        case BatchKey.RowKeyed _, BatchKey.MappedRowKeyed _ -> {
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
        case BatchKey.RecordKeyed _, BatchKey.MappedRecordKeyed _ -> {
            var intoArgs = CodeBlock.builder();
            for (int i = 0; i < pkCols.size(); i++) {
                if (i > 0) intoArgs.add(", ");
                intoArgs.add("$T.$L.$L", tablesClass, tableField, pkCols.get(i).javaName());
            }
            yield CodeBlock.builder()
                .addStatement("$T key = (($T) env.getSource()).into($L)", keyType, RECORD, intoArgs.build())
                .build();
        }
    };
}
```

`buildKeyExtractionWithNullCheck` is only called for `SplitTableField` (single-cardinality
`@splitQuery`); it does not need a mapped arm and retains its existing
`RowKeyed`-only guard. The error message can be tightened to mention all three
unsupported variants for clarity, but functionally nothing changes (split-query never
produces a mapped variant).

## Codegen: `TypeFetcherGenerator`

File: `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`

### `buildServiceDataFetcher` (line 1451)

Two corrections in one pass:

1. **API typo on the existing path.** The current code emits
   `DataLoaderFactory.newDataLoaderWithContext(...)`, which does not exist on
   `DataLoaderFactory` (see note in `buildSplitQueryDataFetcher`, line 1534). The
   correct call for both `RowKeyed` and `RecordKeyed` is `newDataLoader(...)`; overload
   resolution binds the `(keys, batchEnv) -> ...` lambda to
   `BatchLoaderWithContext<K, V>`. This is what `buildSplitQueryDataFetcher` already
   does at line 1589.

2. **Mapped variant path.** For `MappedRowKeyed` / `MappedRecordKeyed`, swap to
   `newMappedDataLoader(...)`, which the lambda binds to
   `MappedBatchLoaderWithContext<K, V>`.

The data fetcher's return type is `CompletableFuture<V>` in **all four cases**:
`loader.load(key, env)` returns `CompletableFuture<V>` regardless of whether the
underlying batch loader is positional (returns `List<V>`) or mapped (returns
`Map<K, V>`); the DataLoader unwraps both shapes internally and fulfills each
per-key promise. Do not parameterize the data fetcher's return type with `Map<K, V>`.

```java
boolean isMapped = batchKey instanceof BatchKey.MappedRowKeyed
                   || batchKey instanceof BatchKey.MappedRecordKeyed;

TypeName valueType  = isList ? ParameterizedTypeName.get(LIST, RECORD) : RECORD;
TypeName returnType = ParameterizedTypeName.get(COMPLETABLE_FUTURE, valueType);
TypeName keyType    = GeneratorUtils.keyElementType(batchKey);
TypeName loaderType = ParameterizedTypeName.get(DATA_LOADER, keyType, valueType);

String factoryMethod = isMapped ? "newMappedDataLoader" : "newDataLoader";
TypeName lambdaKeysType = ParameterizedTypeName.get(isMapped ? SET : LIST, keyType);

var lambdaBlock = CodeBlock.builder()
    .add("($T keys, $T batchEnv) -> {\n", lambdaKeysType, BATCH_LOADER_ENV)
    .indent()
    .addStatement("$T dfe = ($T) batchEnv.getKeyContextsList().get(0)", ENV, ENV)
    .addStatement("$T sel = dfe.getSelectionSet().getField($S)", SELECTED_FIELD, fieldName)
    .addStatement("return $T.completedFuture($L(keys, dfe, sel))", COMPLETABLE_FUTURE, rowsMethodName)
    .unindent()
    .add("}")
    .build();

methodBuilder.addCode(
    "$T loader = env.getDataLoaderRegistry()\n" +
    "    .computeIfAbsent(name, k -> $T.$L($L));\n",
    loaderType, DATA_LOADER_FACTORY, factoryMethod, lambdaBlock);
```

The `loader.load(key, env)` call at the bottom and the surrounding key-extraction
prologue are unchanged across all four variants; `keyElementType` and
`buildKeyExtraction` already pick the right shape.

### `buildServiceRowsMethod` (line 1501)

The rows method's signature follows the batch-loader contract:

| BatchKey variant      | `keys` parameter         | return type                                     |
|-----------------------|--------------------------|-------------------------------------------------|
| `RowKeyed`/`RecordKeyed`     | `List<KeyType>`     | list field: `List<List<Record>>`; single: `List<Record>` |
| `MappedRowKeyed`/`MappedRecordKeyed` | `Set<KeyType>` | list field: `Map<KeyType, List<Record>>`; single: `Map<KeyType, Record>` |

```java
boolean isMapped = bkf.batchKey() instanceof BatchKey.MappedRowKeyed
                   || bkf.batchKey() instanceof BatchKey.MappedRecordKeyed;
TypeName keysElementType = GeneratorUtils.keyElementType(bkf.batchKey());

TypeName keysContainerType = ParameterizedTypeName.get(isMapped ? SET : LIST, keysElementType);

TypeName valuePerKey = isList ? ParameterizedTypeName.get(LIST, RECORD) : RECORD;
TypeName rowsReturnType = isMapped
    ? ParameterizedTypeName.get(MAP, keysElementType, valuePerKey)
    : (isList ? ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(LIST, RECORD)) : ParameterizedTypeName.get(LIST, RECORD));

return MethodSpec.methodBuilder(bkf.rowsMethodName())
    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
    .returns(rowsReturnType)
    .addParameter(keysContainerType, "keys")
    .addParameter(ENV, "env")
    .addParameter(SELECTED_FIELD, "sel")
    .addStatement("throw new $T()", UnsupportedOperationException.class)
    .build();
```

`MAP` is already declared at `TypeFetcherGenerator.java:132`. Add a `SET` constant
(`ClassName.get(java.util.Set.class)`) alongside it so the `Set<KeyType>` lambda /
rows-method parameter doesn't need an inline `ClassName.get(...)` at each use site.

## Tests

### `ServiceCatalogTest` (unit)

Add four `Set<...>`-shaped stub methods to `TestServiceStub` mirroring the existing
`getFilmsWithTableRecordSources` / `getFilmsWithDtoSources` shapes
(`getFilmsWithSetOfTableRecordSources(Set<FilmRecord>)`,
`getFilmsWithSetOfRow1Sources(Set<Row1<Integer>>)`,
`getFilmsWithSetOfRecord1Sources(Set<Record1<Integer>>)`,
`getFilmsWithSetOfDtoSources(Set<TestDtoStub>)`); reflect off them via the existing
`reflectServiceMethod` entry point so the new tests follow the existing
`reflectServiceMethod_*` naming convention rather than hitting the package-private
`classifySourcesType` directly. The new cases (named per the existing convention):

- `reflectServiceMethod_setOfTableRecordSources_classifiedAsMappedRowKeyed`:
  `Set<FilmRecord>` → `BatchKey.MappedRowKeyed(filmPk)`.
- `reflectServiceMethod_setOfRow1Sources_classifiedAsMappedRowKeyed`:
  `Set<Row1<Integer>>` → `BatchKey.MappedRowKeyed(filmPk)`.
- `reflectServiceMethod_setOfRecord1Sources_classifiedAsMappedRecordKeyed`:
  `Set<Record1<Integer>>` → `BatchKey.MappedRecordKeyed(filmPk)`.
- `reflectServiceMethod_setOfDtoSources_rejectedWithDtoMessage`:
  `Set<TestDtoStub>` → failure reason contains "not backed by a jOOQ TableRecord"
  (not "unrecognized sources type").
- `reflectServiceMethod_listOfRecord1Sources_classifiedAsRecordKeyed` (regression):
  confirms the existing `RecordKeyed` arm still fires for `List<Record1<...>>`. Add
  a corresponding `getFilmsWithListOfRecord1Sources(List<Record1<Integer>>)` stub if
  none of the existing stubs already exercises this shape.

### `TypeFetcherGeneratorTest` (structural)

Add `serviceField_mapped_*` fixtures mirroring the existing `serviceField()` fixture
across the new variants:

- `serviceField_mappedRow_list_dataFetcherCallsNewMappedDataLoader`: asserts the
  fetcher body contains `newMappedDataLoader` (not `newDataLoaderWithContext`) and the
  lambda's `keys` parameter is `Set<Row1<Integer>>`.
- `serviceField_mappedRow_list_rowsMethodTakesSetAndReturnsMap`: asserts
  `keys` parameter is `Set<Row1<Integer>>` and return is `Map<Row1<Integer>, List<Record>>`.
- `serviceField_mappedRow_single_rowsMethodReturnsScalarMap`: single cardinality,
  return is `Map<Row1<Integer>, Record>`.
- `serviceField_mappedRecord_list_keyTypeIsRecordN`: asserts the key type uses
  `RecordN<...>` rather than `RowN<...>` and key extraction uses `record.into(...)`.
- `serviceField_dataFetcherReturnTypeIsCompletableFutureV` (covers all four variants
  via parametrized inputs): asserts the data fetcher's return type is
  `CompletableFuture<List<Record>>` / `CompletableFuture<Record>` and never
  `CompletableFuture<Map<...>>`.

Regression: existing `serviceField_*` tests for `RowKeyed` must continue to pass with
the API-call fix (`newDataLoader` instead of `newDataLoaderWithContext`). Update
assertion strings accordingly.

## Out of Scope

- **Executing the rows method.** The rows stub still throws `UnsupportedOperationException`.
  This spec adds correct classification, the new variants, and the stub shape; calling
  the actual service method is tracked separately by `service-rows-method-body.md`
  (Backlog), with `service-context-value-registry.md` covering the typed context-arg
  surface that body will consume. The split into `MappedRowKeyed` /
  `MappedRecordKeyed` gives that follow-up container × key-shape discrimination
  directly on the variant. Element-shape (`RowN` vs `TableRecord`, `RecordN`) is **not**
  carried on the variant; the future emitter recovers it by re-reflecting
  `MethodRef.className()` + `methodName()`, or by adding `BatchKey.elementShape()` at
  that time. Element-shape only affects one site in the body — the optional conversion
  before invoking the user's service — since DataLoader keys stay `RowN` / `RecordN`
  for hashing reasons regardless.
- **`@splitQuery` fields.** Use `BatchKey.RowKeyed`/`RecordKeyed` and are generated
  by `buildSplitQueryDataFetcher`, not `buildServiceDataFetcher`. No change. The
  `SplitRowsMethodEmitter` cast at line 131 to `BatchKey.RowKeyed` remains safe; split
  queries never produce a mapped variant.
- **`Record*TableField` (record-parent batched fields).** `buildRecordBasedDataFetcher`
  at line 1635 hard-casts to `BatchKey.RowKeyed`. These fields don't go through
  `classifySourcesType` and never see a mapped variant. No change.

## Success Criteria

- [ ] `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes
- [ ] `ServiceCatalogTest` new cases pass; existing `RowKeyed`/`RecordKeyed` cases pass
- [ ] `TypeFetcherGeneratorTest` new `serviceField_mapped*` cases pass; existing
  `serviceField_*` cases pass with the `newDataLoader` assertion (not
  `newDataLoaderWithContext`)
- [ ] Data-fetcher return type is `CompletableFuture<V>` for all four BatchKey
  variants (asserted by parametrized test)
- [ ] Build of `opptak-subgraph` (or a schema that reproduces the pattern) no longer
  emits "unrecognized sources type" for `Set<...Record>` parameters; the generated
  fetcher class compiles with `<release>17</release>` (verified by
  `graphitron-test`)
