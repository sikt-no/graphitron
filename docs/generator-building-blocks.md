# Generator Building Blocks

What's been extracted, what's next, and how it connects to the remaining stub work.

## What's in place

**`GeneratorUtils.ResolvedTableNames`** — resolves the three ClassNames (tablesClass, jooqTableClass, typeClass) from a `TableRef`. Used across all three generators. Two factory methods: `of(tableRef, returnTypeName)` for the full triple, `ofTable(tableRef)` when typeClass isn't needed.

**`MethodRef` as sealed interface** — `callParams()` and `sourcedParam()` are default methods. `MethodRef.Basic` is the concrete record. `ConditionFilter` implements both `WhereFilter` and `MethodRef` directly.

**`MethodBackedField` capability interface** — 8 field variants implement it. Orthogonal to the sealed hierarchy, like `SqlGeneratingField`.

**`CallParam` with `typeName`** — the generator emits typed locals (`String filter = ...`) instead of erased `Object`.

**Unified `buildCallArgs(List<CallParam>, String)`** — one method for both condition and lookup call-arg construction.

## What's next

### 1. `BatchKeyField` interface and batchKey promotion

The second capability interface. Fields that need DataLoader setup all have a batch key, but it's accessed differently:
- `SplitTableField`, `SplitLookupTableField` — direct `batchKey()` component
- `ServiceTableField` — `method().sourcedParam().batchKey()`
- `RecordTableField`, `RecordLookupTableField` — not yet classified

```java
public interface BatchKeyField {
    BatchKey batchKey();
    String rowsMethodName();
}
```

**Design commitment: `RecordTableField` is always DataLoader-backed.** A field on a `@record` parent returning a `@table` type always starts a new scope via DataLoader, keyed by the parent's PK. It is never an inline subquery. This means the builder classifies it with a `BatchKey.RowKeyed` derived from the parent type's primary key columns, and it implements `BatchKeyField`.

**`rowsMethodName()` naming.** Service fields use `"load..."`, split fields use `"rows..."`. The interface doesn't prescribe the naming convention — each variant returns whatever name it computes. The contract is simply: "the DataLoader fetcher and the rows method agree on this name." The fetcher references `bkf.rowsMethodName()` as a method-reference target.

**Work:** Promote `batchKey` as a direct component on `ServiceTableField` (builder extracts it from `MethodRef` at classify time — `sourcedParam()` is still needed for the service call argument list, but no longer for key type resolution). Add `batchKey` to `RecordTableField` / `RecordLookupTableField` in the builder (derived from parent PK). All five variants implement `BatchKeyField`.

### 2. Key type and key extraction → GeneratorUtils

`keyElementType`, `buildRowKeyType`, `buildRecordNKeyType` are pure functions (`BatchKey` → `TypeName`) private to `TypeFetcherGenerator`. Move to `GeneratorUtils`.

The BatchKey switch that builds key extraction from `env.getSource()` (lines 413-438 of `buildServiceDataFetcher`) is a pure function of `(BatchKey, TableRef parentTable, TypeName keyType)` → `CodeBlock`. Extract to `GeneratorUtils.buildKeyExtraction(...)`.

### 3. Refactor generator dispatch to use capability interfaces

The N-way `instanceof` chain in `generateTypeSpec` (lines 103-125) doesn't yet use `MethodBackedField` or `SqlGeneratingField`. The refactoring collapses it:

```java
// DataLoader-backed fields (ServiceTableField, SplitTableField, SplitLookupTableField, RecordTableField, ...)
if (field instanceof BatchKeyField bkf && field instanceof SqlGeneratingField sgf) {
    builder.addMethod(buildDataLoaderFetcher(bkf, sgf, parentTable, className));
    if (field instanceof MethodBackedField mbf) {
        builder.addMethod(buildServiceRowsMethod(bkf, mbf, sgf, parentTable));
    } else {
        builder.addMethod(buildSqlRowsMethod(bkf, sgf, parentTable));  // split/record fields
    }
}
```

The service vs non-service distinction is the only branch within the DataLoader path — service fields call a user method then delegate to the type class; non-service fields do SQL directly. The DataLoader setup itself (key type, key extraction, computeIfAbsent, loader.load) is identical for all.

**Prerequisite:** Steps 1 and 2 must be done first.

Step 3 also includes moving shared `ClassName` constants (`RECORD`, `CONDITION`, `DSL`, `ENV`, `SORT_FIELD`, `LIST`, etc.) to `GeneratorUtils`. Currently `TypeFetcherGenerator` and `TypeClassGenerator` each declare 8+ identical constants. Once the dispatch refactoring shares methods across both generators' conceptual scope, drift in these constants becomes a real risk.

### 4. Table local declaration

Minor polish. `GeneratorUtils.declareTableLocal(names, tableRef)` eliminates the repeated `$T table = $T.$L` statement across ~7 methods. Low priority — do alongside other work.

## Relationship to other design docs

**[call-site-unification.md](call-site-unification.md)** — Steps 1-3 are done (`MethodRef.callParams()`, service rows method uses it, `CallParam` has `typeName`). Remaining: enum/text-map detection for service params in the builder (step 4 in that doc). This is independent of the work above.

**[rewrite-roadmap.md](rewrite-roadmap.md)** — The remaining stubs (inline TableField, split/lookup rows methods, mutations, batch select) become simpler once steps 1-3 here are done. Each stub composes the building blocks rather than duplicating patterns.
