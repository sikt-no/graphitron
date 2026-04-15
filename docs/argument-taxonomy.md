# Argument Taxonomy

Plan for unified argument classification in the builder, replacing the current tangled single-pass processing in `buildFilters()`.

## Problem

Arguments are currently processed in one pass through `buildFilters()` that tangles multiple concerns:
- Skips pagination args by name (`"first"`, `"last"`, `"after"`, `"before"`)
- Skips `@orderBy` args by directive
- Skips input-type args with a TODO
- Rejects `@condition` on arguments
- Flattens the rest into `BodyParam`/`CallParam` on a `GeneratedConditionFilter`

This makes it hard to add new argument types, hard to test individual classification paths, and silently drops input-type arguments.

## Design

### `ArgumentRef` is builder-internal

The builder classifies every argument once into an `ArgumentRef` variant, then projects into generation-ready abstractions. No `ArgumentRef` appears on field records. Generators never see it.

```
GraphQL arguments → classifyArguments() → List<ArgumentRef>  (builder-internal)
                                              ↓
                              project into generation-ready views:
                                → GeneratedConditionFilter  (for column-bound filter args)
                                → OrderBySpec               (for @orderBy args)
                                → PaginationSpec             (for pagination args)
                                → LookupMapping              (for lookup args → VALUES table)
```

This follows the established pattern: just as `GeneratedConditionFilter` lifts filter arguments into a generation-ready abstraction (callParams for extraction, bodyParams for the WHERE body), a `LookupMapping` lifts lookup arguments into a generation-ready abstraction for the VALUES derived table.

### `ArgumentRef` variants

```java
sealed interface ArgumentRef {
    String name();
    String typeName();
    boolean nonNull();
    boolean list();
}

// Scalar mapped to a column — becomes a filter param or lookup column
sealed interface ScalarArg extends ArgumentRef {
    record ColumnArg(name, typeName, nonNull, list, ColumnRef column, CallSiteExtraction extraction) {}
    record UnboundArg(name, typeName, nonNull, list, String attemptedColumnName, String reason) {}
}

// Input type argument
sealed interface InputTypeArg extends ArgumentRef {
    record TableInputArg(name, typeName, nonNull, list) {}
    record PlainInputArg(name, typeName, nonNull, list) {}
}

// Purpose-specific arguments (already handled by other abstractions)
record OrderByArg(name, typeName, nonNull, list, String sortFieldName, String directionFieldName) {}
record PaginationArg(name, typeName, nonNull, PaginationRole role) {}
record UnclassifiedArg(name, typeName, nonNull, list, String reason) {}
```

### Generation-ready projections

| ArgumentRef variant | Projected into | Generator consumes |
|---|---|---|
| `ColumnArg` (on filter field) | `GeneratedConditionFilter.bodyParams/callParams` | `TypeFetcherGenerator.buildConditionCall` |
| `ColumnArg` (on lookup field) | `LookupMapping.columns` | Lookup generator (VALUES builder) |
| `TableInputArg` (on lookup field) | `LookupMapping.inputColumns` | Lookup generator (multi-column VALUES) |
| `OrderByArg` | `OrderBySpec.Argument` | `TypeFetcherGenerator.buildOrderByCode` |
| `PaginationArg` | `PaginationSpec` | `TypeFetcherGenerator.buildQueryConnectionFetcher` |
| `UnboundArg` | Validation error | — |
| `UnclassifiedArg` | Validation error | — |

### `LookupMapping` — the missing generation-ready abstraction

The equivalent of `GeneratedConditionFilter` but for the VALUES-derived-table pattern:

```java
record LookupMapping(
    List<LookupColumn> columns,    // one per argument that maps to a VALUES column
    TableRef targetTable            // the table being looked up
) {
    record LookupColumn(
        String argName,             // GraphQL argument name
        ColumnRef column,           // target table column
        CallSiteExtraction extraction,  // how to extract the value
        boolean list                // list arg → multiple VALUES rows; scalar → broadcast
    ) {}
}
```

The generator iterates `columns` to build the `VALUES(idx, col1, col2, ...)` derived table and the JOIN condition. It uses `extraction` to emit the value extraction code. No argument classification at generation time.

## Relationship to existing model

**`GeneratedConditionFilter`** stays unchanged. It's already a generation-ready projection — `callParams` for the call site, `bodyParams` for the method body. The builder constructs it from `ColumnArg` arguments instead of from raw GraphQL arguments directly.

**`OrderBySpec`** stays unchanged. The builder constructs `OrderBySpec.Argument` from `OrderByArg` arguments. The `namedOrders` population already works.

**`PaginationSpec`** stays unchanged. The builder constructs it from `PaginationArg` arguments instead of string-matching on names.

**`WhereFilter` / `ConditionFilter`** — field-level `@condition` is a separate concern from arguments. The builder reads it after argument classification. `override: true` suppresses the `GeneratedConditionFilter` from column args.

**`SqlGeneratingField`** — no change to its interface. It still exposes `filters()`, `orderBy()`, `pagination()`. Lookup fields additionally expose `LookupMapping` (through a capability interface or as a component on the lookup variants).

## Implementation order

| Step | What | Depends on |
|---|---|---|
| 1 | Define `ArgumentRef` sealed hierarchy (builder-internal) | Nothing |
| 2 | Extract `classifyArguments()` from `buildFilters()` — produces `List<ArgumentRef>` | Step 1 |
| 3 | Rebuild `buildFilters()` to project from classified arguments | Step 2 |
| 4 | Define `LookupMapping` generation-ready abstraction | Step 1 |
| 5 | Build `LookupMapping` from classified arguments for lookup fields | Steps 2, 4 |
| 6 | Read field-level `@condition`, construct `ConditionFilter`, apply `override` | Step 3 |
| 7 | Handle `TableInputArg` in lookup mapping (currently the TODO skip) | Steps 4-5 |
| 8 | Validation: `UnboundArg` → error, `UnclassifiedArg` → error | Step 2 |

Steps 1-3 clean up the builder. Step 4-5 add the lookup abstraction. Step 6 absorbs the `@condition` path. Step 7 unblocks input-type arguments.

## Test strategy

- **Builder classification tests:** SDL with each argument pattern → correct projection into `filters()` / `orderBy()` / `pagination()` (existing tests should pass unchanged)
- **Builder tests for `@condition`:** field-level `@condition(override: true)` → `filters()` contains only `ConditionFilter`
- **Builder tests for lookup mapping:** lookup field with scalar + input-type args → correct `LookupMapping` columns
- **Validation tests:** `UnboundArg` → error with candidate hint; `UnclassifiedArg` → error with reason
