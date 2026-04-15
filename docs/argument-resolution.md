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

---

## Review: execution readiness

Reviewed 2026-04-15 against the codebase at `7a3b180e`. The first-pass findings (three-pass scope, dispatch rule, TableInputArg components, PaginationArgRef naming, override timing) have been incorporated. The items below are deeper issues that emerged from verifying the plan against the actual code paths, generator behavior, test infrastructure, and the old codegen's documented semantics. Each needs a decision before handing this plan to a team.

### 1. `@condition` on arguments and input fields is unaddressed

The directive schema (`directives.graphqls:128-136`) declares `@condition` valid on `FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION`. The plan covers only `FIELD_DEFINITION` (step 4). The old codegen README (`graphitron-java-codegen/README.md:529-670`) documents all three locations with distinct semantics:

- **On FIELD_DEFINITION:** adds a condition method call receiving all argument values; `override: true` suppresses all auto-generated conditions for the field.
- **On ARGUMENT_DEFINITION:** adds a condition method call receiving that argument's value; `override: true` suppresses only that argument's auto-generated condition.
- **On INPUT_FIELD_DEFINITION:** same per-field scoping, but inside nested input types.

The plan's `suppressedByOverride` on `ColumnArg` is a blanket field-level flag — it can't express per-argument override. And `buildFilters()` currently rejects `@condition` on arguments with an explicit error (`FieldBuilder.java:566-570`).

**Decision needed:** Should `classifyArguments()` support `@condition` on individual arguments (matching old codegen behavior), or is that deferred? If deferred, the plan should explicitly state it's out of scope and that the error rejection stays. If supported, `ColumnArg` needs a richer condition model — e.g., `Optional<ConditionFilter> argCondition` alongside `suppressedByOverride`.

### 2. `@condition` contextArguments parameter is unmentioned

The directive has `contextArguments: [String!]` — context values injected as trailing method parameters. The old codegen documents this (`README.md:1070-1088`). The plan's step 4 lists the builder reading `override` but never mentions reading `contextArguments` or passing them to `ServiceCatalog.reflectTableMethod()`. This is the same `contextArguments` pattern already implemented for `@service` and `@tableMethod` — the reflection path exists, but the plan needs to say the builder reads it and passes it through.

### 3. Child field sequencing: lookup unknown at classification time

The plan's dispatch rule says "the builder knows the field classification before projecting." This is true for **query fields** (`classifyQueryField` checks `hasLookupKeyAnywhere()` at line 862 before calling `resolveTableFieldComponents()` at line 869). But for **child fields** (`classifyObjectReturnChildField`), the order is reversed: `resolveTableFieldComponents()` runs at line 231, and `hasLookupKeyAnywhere()` is checked at line 234 — **after** `buildFilters()` has already finished.

This means `classifyArguments()` cannot receive a "this is a lookup field" flag for child fields, because that determination happens later. Options:

- **Option A:** Move lookup detection before component resolution for child fields too. `hasLookupKeyAnywhere()` only reads `@lookupKey` directives — it doesn't depend on filter results. Safe to move up.
- **Option B:** Always classify into filter projection, then re-project to `LookupMapping` when the field variant is determined. This is cleaner (classification and projection are separate steps) but means the `LookupMapping` isn't built during classification — it's a post-hoc transformation.
- **Option C:** Don't pass a flag. Instead, projection happens outside `classifyArguments()` — the caller decides. `classifyArguments()` returns `List<ArgumentRef>`, and a separate `projectForLookup(refs)` / `projectForFilter(refs)` builds the target.

Option C aligns with the plan's architecture (`classifyArguments()` produces `List<ArgumentRef>`, projection is a separate step) and requires no reordering.

### 4. Current lookup generators use GeneratedConditionFilter, not LookupMapping

`buildQueryLookupRowsMethod` (`TypeFetcherGenerator.java:631-663`) iterates `field.filters()`, casts to `GeneratedConditionFilter`, and uses `gcf.bodyParams()` for local variable declarations and `gcf.callParams()` for the condition call. The condition method body (in `TypeConditionsGenerator`) generates `.in()` for list params and `.eq()` for scalar params — each key dimension independently.

The plan replaces this with VALUES+JOIN. This is more than a refactoring — it's a semantic change:

- **Current behavior:** `(customer_id IN (1,2,4)) AND (store_id = 1)` — returns all rows matching ANY customer_id AND the store_id. Composite keys are treated as independent dimensions.
- **VALUES behavior:** `VALUES (1,1), (2,1), (4,1) AS input(customer_id, store_id) JOIN customer ON ...` — returns rows matching specific (customer_id, store_id) tuples. Keys are correlated.

For single-key lookups, the results are identical. For composite keys with one scalar key broadcast across a list key, the results are also identical. But the VALUES approach additionally preserves input ordering (via `ORDER BY input.idx`) and enables true correlated tuple matching if multiple list keys are ever needed.

The plan should state this semantic difference explicitly, because:
- The existing execution test (`GraphQLQueryTest:257`) uses `containsExactlyInAnyOrder` — it doesn't verify ordering. A new test must verify ordering.
- The migration from `GeneratedConditionFilter` to `LookupMapping` on lookup fields must update both the builder (stop producing `GeneratedConditionFilter` for lookup args) and the generator (`buildQueryLookupRowsMethod` reads `LookupMapping` instead of `filters()`). Step 8 says "replace condition-based lookup" but doesn't note that `QueryLookupTableField.filters()` changes from `List<WhereFilter>` containing lookup args to an empty list (or is removed).

### 5. Where does LookupMapping live on the model?

The plan defines `LookupMapping` as a generation-ready projection (like `GeneratedConditionFilter`) but never says which field record component carries it. Options:

- **On `QueryLookupTableField` directly:** Add `LookupMapping lookupMapping` as a component. Clean, type-safe, follows narrow-component-types principle. But also needed on child variants (`LookupTableField`, `SplitLookupTableField`, `RecordLookupTableField`).
- **In `SqlGeneratingField`:** Add `Optional<LookupMapping> lookupMapping()` alongside `filters()`. Every SQL-generating field can answer whether it has lookup semantics.
- **Separate from the field, in the generator:** The builder returns it alongside `TableFieldComponents`. Generator reads it.

The plan should specify this. The test strategy depends on it — "lookup mapping tests" need to know where to find the `LookupMapping` on the built model.

### 6. PlainInputArg has no projection target

`PlainInputArg` (non-table input type) appears in the variant list but the plan never says what it projects to. Currently at `FieldBuilder.java:575-578`, non-table input types with entries in `ctx.types` are silently skipped. The plan should state one of:
- `PlainInputArg` → validation error (input types without `@table` can't be auto-classified)
- `PlainInputArg` → silently ignored (preserving current behavior, documented as deferred)
- `PlainInputArg` with `@condition` → `ConditionFilter` (per old codegen semantics)

### 7. InputColumnBinding doesn't exist

`TableInputArg` references `List<InputColumnBinding> fieldBindings`, but `InputColumnBinding` is not defined in the plan or the codebase. Before implementation, specify at minimum:

```java
record InputColumnBinding(
    String inputFieldName,      // field name on the GraphQL input type
    ColumnRef targetColumn,     // resolved column on the target table
    CallSiteExtraction extraction
)
```

This mirrors `LookupColumn` closely. Consider whether they should share a common interface or be the same type.

### 8. Test infrastructure gaps

The plan says "existing tests pass unchanged." This is accurate for pipeline tests and execution tests (which go through the full builder). But:

- **`GraphitronSchemaBuilderTest.ArgumentParsingCase`** — 6 cases directly assert on `GeneratedConditionFilter` structure from `buildFilters()`. These test the internal builder output, not the public model. If `classifyArguments()` replaces `buildFilters()` and the internal types change, these cases need updating. They won't break silently — they'll fail to compile.
- **No `@condition` pipeline tests exist.** The builder has `CONDITION_IS_ALWAYS_NULL` (`GraphitronSchemaBuilderTest.java:604`) that documents the gap. Step 4 needs new SDL pipeline tests.
- **No lookup ordering test exists.** `GraphQLQueryTest:257` uses `containsExactlyInAnyOrder`. The plan's "verify result ordering matches input" test strategy is new — add it to the test spec schema and test file.
- **graphitron-rewrite-test-spec has no `@orderBy` or `@asConnection` fields.** If steps 1-3 touch `buildOrderBySpec`/`buildPaginationSpec`, the test spec should cover these patterns to catch regressions. Consider adding a connection field before starting step 2.

### Summary: decisions before implementation

| # | Decision | Default if not decided |
|---|---|---|
| 1 | `@condition` on ARGUMENT_DEFINITION — support or defer? | Defer (keep error rejection) |
| 2 | `@condition` contextArguments — support in step 4? | Support (pattern exists in @service) |
| 3 | Child field sequencing — Option A, B, or C? | C (project separately from classification) |
| 4 | Semantic difference of VALUES vs IN — document? | Document, add ordering test |
| 5 | Where LookupMapping lives on the model | On lookup field variants directly |
| 6 | PlainInputArg — error, ignore, or support? | Ignore (preserve current silent skip) |
| 7 | InputColumnBinding — define record components | Mirror LookupColumn |
| 8 | Add `@orderBy`/`@asConnection` to test-spec schema | Yes, before step 2 |
