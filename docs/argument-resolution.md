# Argument Resolution

Plan for unified argument classification in the builder, `@condition` directive support, and lookup field generation with the VALUES derived table pattern.

## Problem

Arguments are currently processed in **three independent passes** that each iterate all arguments:

1. `buildFilters()` — skips `@orderBy`, pagination args, `@condition`, and input-type args; classifies the rest into `BodyParam`/`CallParam` on a `GeneratedConditionFilter`
2. `buildOrderBySpec()` — scans for `@orderBy` directive
3. `buildPaginationSpec()` — scans for `first`/`last`/`after`/`before` by hardcoded name

Coordination between the three is implicit: `buildFilters()` skips pagination args using the same `isPaginationArg()` check that `buildPaginationSpec()` uses. Input-type arguments are silently dropped (TODO). Field-level `@condition` is unimplemented.

## Design

### `ArgumentRef` — builder-internal classification

A single `classifyArguments()` method replaces all three passes. It classifies every argument once into an `ArgumentRef` variant, then projects into generation-ready abstractions. `ArgumentRef` never appears on field records. Generators never see it.

```
GraphQL arguments → classifyArguments() → List<ArgumentRef>  (builder-internal)
                                              ↓
                              project into generation-ready views:
                                → GeneratedConditionFilter  (column-bound filter args)
                                → LookupMapping             (lookup args → VALUES table)
                                → OrderBySpec               (@orderBy args)
                                → PaginationSpec             (pagination args)
```

### `ArgumentRef` variants

```java
sealed interface ArgumentRef {
    String name();
    String typeName();
    boolean nonNull();
    boolean list();
}

sealed interface ScalarArg extends ArgumentRef {
    record ColumnArg(..., ColumnRef column, CallSiteExtraction extraction, boolean suppressedByOverride) {}
    record UnboundArg(..., String attemptedColumnName, String reason) {}
}

sealed interface InputTypeArg extends ArgumentRef {
    record TableInputArg(..., TableRef inputTable, List<InputColumnBinding> fieldBindings) {}
    record PlainInputArg(...) {}
}

record OrderByArg(..., String sortFieldName, String directionFieldName) {}
record PaginationArgRef(..., PaginationRole role) {}  // "Ref" suffix avoids collision with PaginationSpec.PaginationArg
record UnclassifiedArg(..., String reason) {}
```

`ColumnArg.suppressedByOverride` is set when the field has `@condition(override: true)` — these args are classified but not projected into `GeneratedConditionFilter`. The flag is set during classification (step 2), not post-hoc.

`TableInputArg` carries the resolved input table and its field→column bindings, giving the lookup generator what it needs for composite key VALUES construction.

### Dispatch rule: lookup vs filter

When the field is classified as a lookup field (`QueryLookupTableField` or any `LookupTableField` variant), `ColumnArg` arguments project to `LookupMapping`. When it's a filter field, they project to `GeneratedConditionFilter`. The builder knows the field classification before projecting — `classifyArguments()` receives the field variant (or a flag) to determine the projection target.

### `LookupMapping`

```java
record LookupMapping(
    List<LookupColumn> columns,
    TableRef targetTable
) {
    record LookupColumn(
        String argName,
        ColumnRef targetColumn,         // JOIN condition: input.col = target.col
        CallSiteExtraction extraction,  // how to extract the argument value
        boolean list                    // list → multiple VALUES rows; scalar → broadcast
    ) {}
}
```

The generator builds `VALUES(idx, col1, col2, ...)`, the JOIN condition (`input.col1 = target.col1 AND ...`), and `ORDER BY input.idx`.

### `@condition` on field definitions

Read during classification. The builder:
1. Checks `fieldDef.hasAppliedDirective(DIR_CONDITION)` and reads `override` flag
2. If `override: true`, sets `suppressedByOverride` on all `ColumnArg` entries during classification
3. Reflects the condition method via `ServiceCatalog.reflectTableMethod()`
4. Constructs `ConditionFilter(className, methodName, params)`
5. Adds it to the field's `filters()` list

## Implementation order

| Step | What | Depends on |
|---|---|---|
| 1 | Define `ArgumentRef` sealed hierarchy (builder-internal) | Nothing |
| 2 | Extract `classifyArguments()` replacing all three passes (`buildFilters`, `buildOrderBySpec`, `buildPaginationSpec`) | Step 1 |
| 3 | Project classified arguments into `GeneratedConditionFilter` / `OrderBySpec` / `PaginationSpec` | Step 2 |
| 4 | Read field-level `@condition`, apply `override` suppression during classification | Steps 2-3 |
| 5 | Define `LookupMapping` + `LookupColumn` in the model | Step 1 |
| 6 | Build `LookupMapping` from classified arguments for lookup fields | Steps 2, 5 |
| 7 | VALUES + JOIN builder in `GeneratorUtils` | Step 5 |
| 8 | Replace condition-based lookup in `buildQueryLookupRowsMethod` with VALUES + JOIN | Steps 6-7 |
| 9 | Handle `TableInputArg` in lookup mapping (composite keys) | Steps 6-8 |
| 10 | Validation: `UnboundArg` → error, `UnclassifiedArg` → error | Step 2 |

Steps 1-3 unify the three passes. Step 4 unblocks `@condition`. Steps 5-9 implement lookup generation.

## Test strategy

- **Builder tests:** SDL with each argument pattern → correct projection into `filters()` / `orderBy()` / `pagination()` (existing tests pass unchanged)
- **`@condition` tests:** `@condition(override: true)` → `filters()` contains only `ConditionFilter`; pipeline test with SDL
- **Lookup mapping tests:** lookup field with scalar + input-type args → correct `LookupMapping` columns and target table
- **Lookup execution test:** scalar key, composite key, verify result ordering matches input
- **Validation:** `UnboundArg` → error with candidate hint; `UnclassifiedArg` → error with reason

## Out of scope

- **Child-level lookup fields** — same VALUES pattern in subquery or DataLoader context. The VALUES builder (step 7) is reusable; integration is roadmap G6 work.
- **Mutations** — input-type arguments for DML use different mapping. Separate concern.
