# Generator Building Blocks

Design document for extracting common abstractions from the rewrite generators ahead of the remaining stub implementations.

## Motivation

The three main generators (`TypeFetcherGenerator`, `TypeClassGenerator`, `TypeConditionsGenerator`) share structural patterns that are currently copy-pasted across methods. As more stubs get implemented (inline `TableField`, split/lookup fields, mutations, batch select methods), each will need the same patterns. Extracting them now — while the code is still small — makes the remaining work compositional rather than duplicative.

Every pattern identified below already exists in at least two places in the current code. Nothing here is speculative.

## Pattern 1: Table name resolution

**Problem:** Every SQL-touching method resolves the same trio of ClassNames from a `TableRef`:

```java
var tablesClass    = ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Tables");
var jooqTableClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage() + ".tables", tableRef.javaClassName());
var typeClass      = ClassName.get(RewriteConfig.outputPackage() + ".rewrite.types", returnTypeName);
```

This appears in `buildQueryTableFetcher`, `buildQueryLookupRowsMethod`, `buildServiceDataFetcher`, `buildServiceRowsMethod`, `TypeClassGenerator.buildSelectManyMethod`, `buildSelectOneMethod`, `buildSubselectManyMethod`, `buildSubselectOneMethod`, `buildFieldsMethod`, and `TypeConditionsGenerator.buildConditionMethod`.

**Proposal:** A record that resolves these once:

```java
record ResolvedTableNames(ClassName tablesClass, ClassName jooqTableClass, ClassName typeClass) {
    static ResolvedTableNames of(TableRef tableRef, String returnTypeName) {
        return new ResolvedTableNames(
            ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Tables"),
            ClassName.get(RewriteConfig.getGeneratedJooqPackage() + ".tables", tableRef.javaClassName()),
            ClassName.get(RewriteConfig.outputPackage() + ".rewrite.types", returnTypeName));
    }
}
```

Every generator method receives this instead of recomputing. The `table` local variable declaration (pattern 5 below) also uses it.

## Pattern 2: Table local variable declaration

**Problem:** Nearly every SQL method opens with:

```java
builder.addStatement("$T table = $T.$L", jooqTableClass, tablesClass, tableRef.javaFieldName());
```

Appears in `buildQueryTableFetcher`, `buildQueryLookupRowsMethod`, and all five SQL methods in `TypeClassGenerator`. Always named `table`, always using the same resolved ClassNames.

**Proposal:** A shared method:

```java
static CodeBlock declareTableLocal(ResolvedTableNames names, TableRef tableRef) {
    return CodeBlock.of("$T table = $T.$L", names.jooqTableClass(), names.tablesClass(), tableRef.javaFieldName());
}
```

Or, since it always appears as a statement on a `MethodSpec.Builder`, a one-liner that adds it directly.

## Pattern 3: Key type construction

**Problem:** `keyElementType`, `buildRowKeyType`, `buildRecordNKeyType` convert `BatchKey` → JavaPoet `TypeName`. They're private to `TypeFetcherGenerator` but used by `buildServiceDataFetcher`, `buildServiceRowsMethod`, `buildSplitRowsMethod`, and will be needed by every future DataLoader-backed field type.

They're pure functions: `BatchKey` → `TypeName`, no generator state.

**Proposal:** Move to a shared utility (e.g., `GeneratorUtils`) or onto `BatchKey` itself as a model-level concern (since `BatchKey` already carries `javaTypeName()` as a string — this would be the JavaPoet equivalent).

## Pattern 4: Sourced param lookup

**Problem:** The same stream chain appears in both `buildServiceDataFetcher` (line 382) and `buildServiceRowsMethod` (line 461):

```java
var sourcesParam = smr.params().stream()
    .filter(p -> p instanceof MethodRef.Param.Sourced)
    .map(p -> (MethodRef.Param.Sourced) p)
    .findFirst()
    .orElseThrow();
```

**Proposal:** Add to `MethodRef`:

```java
public Param.Sourced sourcedParam() {
    return params.stream()
        .filter(p -> p instanceof Param.Sourced)
        .map(p -> (Param.Sourced) p)
        .findFirst()
        .orElseThrow();
}
```

Analogous to the existing `callParams()` — a derived accessor for a specific subset of params.

## Pattern 5: Condition call-args builders

**Problem:** `buildCallArgs(WhereFilter)` and `buildLookupCallArgs(GeneratedConditionFilter)` are near-duplicates. Both produce `table, arg1, arg2, ...`. The only difference: one iterates `callParams()` directly, the other iterates `bodyParams()` and wraps each in `new CallParam(...)`.

**Proposal:** Unify into one method that takes `List<CallParam>`:

```java
static CodeBlock buildCallArgs(List<CallParam> params, String conditionsClassName) {
    var args = CodeBlock.builder();
    args.add("table");
    for (var param : params) {
        args.add(", $L", buildArgExtraction(param, conditionsClassName));
    }
    return args.build();
}
```

The lookup path constructs its `List<CallParam>` from `bodyParams()` before calling. This also makes `buildArgExtraction` usable without the intermediate `buildCallArgs` wrapper.

## Pattern 6: DataLoader setup template

**Problem:** `buildServiceDataFetcher` (70 lines) follows a rigid template that will be needed by every DataLoader-backed field type. The template has five fixed steps:

1. Resolve key type from `BatchKey` (pattern 3)
2. Resolve value type from wrapper (`isList` → `List<Record>` or `Record`)
3. Build lambda: `(keys, batchEnv) -> { extract dfe, extract sel, call rows method }`
4. Emit `computeIfAbsent(name, k -> DataLoaderFactory.newDataLoaderWithContext(lambda))`
5. Extract key from `env.getSource()` via `BatchKey` switch (RowKeyed → `DSL.row(...)`, RecordKeyed → `into(...)`, ObjectBased → cast)
6. Emit `loader.load(key, env)`

Steps 1, 3-6 are identical for every DataLoader-backed field. Step 2 varies only by wrapper. Step 5 is a pure function of `(BatchKey, parentTable)` → `CodeBlock`.

**Proposal:** Extract step 5 (key extraction from source) as a standalone building block:

```java
static CodeBlock buildKeyExtraction(BatchKey batchKey, TableRef parentTable, TypeName keyType) { ... }
```

The full DataLoader setup can then be composed from: key type (pattern 3) + key extraction + a rows-method-name string. Whether we extract the full template or just the key extraction piece depends on how much the lambda body varies across field types — service fields call a service then delegate to the type class; split fields will do SQL directly. The key extraction is the clearly reusable part.

## Pattern 7: Shared ClassName constants

**Problem:** `TypeFetcherGenerator` and `TypeClassGenerator` both declare private `static final ClassName` constants for the same jOOQ and GraphQL types (`RECORD`, `RESULT`, `CONDITION`, `DSL`, `ENV`, `SORT_FIELD`, `LIST`, etc.). `TypeConditionsGenerator` has its own subset.

**Proposal:** A shared constants class (e.g., `GeneratorTypes` or placed in a `GeneratorUtils` class alongside patterns 1-3):

```java
static final ClassName RECORD    = ClassName.get("org.jooq", "Record");
static final ClassName RESULT    = ClassName.get("org.jooq", "Result");
static final ClassName CONDITION = ClassName.get("org.jooq", "Condition");
static final ClassName DSL       = ClassName.get("org.jooq.impl", "DSL");
static final ClassName ENV       = ClassName.get("graphql.schema", "DataFetchingEnvironment");
// ...
```

This is the lowest-value extraction (it's just deduplication, not an abstraction), but it eliminates the drift risk when a new generator needs the same constants.

## Extraction order

| Order | Pattern | Complexity | Impact | Files touched |
|---|---|---|---|---|
| 1 | Pattern 4: `MethodRef.sourcedParam()` | Trivial | Eliminates duplicate stream chain | `MethodRef.java`, `TypeFetcherGenerator.java` |
| 2 | Pattern 1: `ResolvedTableNames` | Small | Eliminates ~30 repeated lines across all generators | New record, `TypeFetcherGenerator.java`, `TypeClassGenerator.java`, `TypeConditionsGenerator.java` |
| 3 | Pattern 5: Unified `buildCallArgs` | Small | Collapses two near-duplicate methods | `TypeFetcherGenerator.java` |
| 4 | Pattern 3: Key type utility | Small | Unblocks all DataLoader field implementations | Extract from `TypeFetcherGenerator.java` to shared utility |
| 5 | Pattern 6: Key extraction from source | Medium | Unblocks split/lookup field implementations | Extract from `TypeFetcherGenerator.java` to shared utility |
| 6 | Pattern 2: Table local declaration | Trivial | Minor cleanup, follows naturally from pattern 1 | All generators |
| 7 | Pattern 7: Shared constants | Trivial | Drift prevention | All generators |

Patterns 1-5 are worth doing now — they directly unblock or simplify the remaining stub work. Patterns 6-7 are polish that can happen alongside or after.

## Capability interfaces on the model

The building blocks above are shared *functions*. But the dispatch in `generateTypeSpec` is an N-way `instanceof` chain that grows with each field variant. Capability interfaces on the sealed hierarchy let the generator match on *what a field can do* rather than *which specific variant it is*.

`SqlGeneratingField` already proves the pattern: 11 variants across `QueryField` and `ChildField.TableTargetField` implement it, and the generator accesses `filters()`, `orderBy()`, `pagination()` uniformly.

### Precedent: MethodRef as interface

The `MethodRef` refactoring (commit `dfb6917`) converted `MethodRef` from a record to a sealed interface. `ConditionFilter` now implements both `WhereFilter` and `MethodRef` directly — a condition method IS a method reference. `MethodRef.Basic` is the concrete record for service/table method references resolved from reflection.

This establishes the pattern: an interface defines a capability contract (`className()`, `methodName()`, `params()`, `callParams()`), and types implement it directly when they ARE that thing, or carry an instance when they HAVE one.

### Proposed interfaces

**`MethodBackedField`** — "this field delegates to a user-provided Java method"

```java
public interface MethodBackedField {
    MethodRef method();
}
```

7 variants carry `method: MethodRef` today: `ServiceTableField`, `TableMethodField`, `ServiceRecordField`, `QueryTableMethodTableField`, `QueryServiceTableField`, `QueryServiceRecordField`, `MutationServiceTableField`, `MutationServiceRecordField`.

The generator uses `mbf.method().callParams()` for argument extraction — one code path for all 7.

**`BatchKeyField`** — "this field uses a DataLoader with a batch key"

```java
public interface BatchKeyField {
    BatchKey batchKey();
    String rowsMethodName();
}
```

Currently `batchKey` is explicit on `SplitTableField` and `SplitLookupTableField`. `ServiceTableField` accesses it via `method().sourcedParam().batchKey()`. `RecordTableField` and `RecordLookupTableField` will need it once their stubs are implemented (keyed by parent PK).

The builder should promote `batchKey` to a direct component on all DataLoader-backed variants, so the interface contract is clean and uniform.

### How interfaces compose with building blocks

The building blocks become methods that take interface types:

```java
// Before: specific types, N branches
if (field instanceof ChildField.ServiceTableField sf) {
    buildServiceDataFetcher(sf, sf.method(), sf.returnType(), parentTable, className);
}

// After: capability interface, 1 branch for all DataLoader fields
if (field instanceof BatchKeyField bkf && field instanceof SqlGeneratingField sgf) {
    builder.addMethod(buildDataLoaderFetcher(bkf, sgf, parentTable, className));
    builder.addMethod(buildRowsMethod(bkf, sgf, parentTable));
}
```

A `ServiceTableField` implements `BatchKeyField`, `SqlGeneratingField`, and `MethodBackedField`. The generator composes: DataLoader setup (from `BatchKeyField`) + service call (from `MethodBackedField`) + condition/orderBy (from `SqlGeneratingField`).

### Order relative to building blocks

Interfaces come first:
1. Add `MethodBackedField` (trivial — 7 records add `implements MethodBackedField`)
2. Promote `batchKey` onto DataLoader-backed variants, add `BatchKeyField`
3. Extract building blocks that take interface types
4. Refactor `generateTypeSpec` dispatch to match on capabilities

## Non-goals

- **No base class or framework.** The generators are static utility classes and should stay that way. The building blocks are shared functions, not an inheritance hierarchy.
- **No "template engine."** The generated code varies enough between field types that a rigid template would fight the domain. Composable building blocks that each generator method assembles freely is the right level.
- **No speculative abstractions.** Every pattern listed here exists in 2+ places today. Patterns that might emerge from future stub implementations are not pre-extracted.
