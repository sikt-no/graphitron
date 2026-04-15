# ConditionFilter Builder Path

Plan for reading `@condition` directives in `FieldBuilder` and cleaning up the surrounding test coverage.

## The gap

`FieldBuilder.buildFilters()` ignores `@condition` on field definitions entirely. It builds `GeneratedConditionFilter` entries from field arguments (auto-generated conditions), but the developer-supplied `ConditionFilter` path is unimplemented. The directive is read nowhere; the `override` flag is never checked; `contextArguments` is never passed through.

All tests that use `ConditionFilter` construct it directly, bypassing the builder. No test verifies that a schema with `@condition` produces a `ConditionFilter` in the field's `filters()` list.

## What the model already supports

`ConditionFilter` implements both `WhereFilter` and `MethodRef`. Construction: `new ConditionFilter(className, methodName, params)`. `callParams()` is derived automatically via `MethodRef.super.callParams()`, which reads `ParamSource.Arg.extraction` for enum/text-map detection. The generator's `buildArgExtraction` handles all `CallSiteExtraction` variants. No generator changes needed.

The `override` semantic is designed: when `override: true`, the builder omits the `GeneratedConditionFilter`, leaving only the `ConditionFilter` in the `filters()` list. `ConditionFilter` itself never carries an override flag — the suppression is expressed by the absence of `GeneratedConditionFilter`.

## Steps

### 1. Read `@condition` in `buildFilters()`

At the top of `buildFilters()`, before iterating arguments, check `fieldDef.hasAppliedDirective(DIR_CONDITION)`. If present:

- Extract the `ExternalCodeReference` from the directive's `condition` argument (`className`, `method`)
- Read `contextArguments: [String!]` from the directive (defaults to empty)
- Read `override: Boolean` (defaults to false)
- Reflect the method via `ServiceCatalog` — the condition method signature is `Condition method(Table<?> target, arg1, arg2, ...)`, which matches the existing `reflectTableMethod()` classification logic (first param is `Table`, rest are `Arg`/`Context`)
- Construct a `ConditionFilter(className, methodName, params)` from the reflected `MethodRef`

`ServiceCatalog.reflectTableMethod()` already classifies `Table`-first methods with `Arg`/`Context` params. It also runs through `enrichArgExtractions` in `FieldBuilder`, so enum/text-map detection applies to condition params too.

### 2. Apply `override` flag

If `override: true`, skip the argument-driven `GeneratedConditionFilter` construction that follows. The field's `filters()` list contains only the `ConditionFilter`.

If `override: false` (default), both the `ConditionFilter` and the `GeneratedConditionFilter` appear in `filters()`. The fetcher generator emits both as `condition = condition.and(...)` calls — the developer's condition AND the auto-generated argument filter.

### 3. Pass `contextArguments` to reflection

The `contextArguments` list names parameters that should be classified as `ParamSource.Context` rather than `ParamSource.Arg`. `ServiceCatalog.reflectTableMethod()` already accepts a `ctxKeys` set for this purpose — pass the directive's `contextArguments` list.

### 4. Tests

**Builder classification test** (in `GraphitronSchemaBuilderTest` or a new focused file):
- Schema with `@condition(condition: {className: "...", method: "..."})` on a query field → field's `filters()` contains a `ConditionFilter` with the correct className/methodName
- Same with `override: true` → `filters()` contains only the `ConditionFilter`, no `GeneratedConditionFilter`
- Same with `override: false` and a filterable argument → `filters()` contains both
- With `contextArguments: ["tenantId"]` → the reflected param has `ParamSource.Context`

**Validation tests** — the existing `TableFieldValidationTest`, `SplitTableFieldValidationTest`, `RecordTableFieldValidationTest`, `ArgumentValidationTest` already construct `ConditionFilter` directly and validate successfully. These remain valid — they test the validator, not the builder.

**Pipeline test** — add a case in `FetcherPipelineTest` with an SDL schema using `@condition`. Verify the generated fetcher's condition call includes the developer-supplied method alongside (or instead of) the generated condition.

**Test-spec execution test** — add a `@condition` field to the test-spec schema with a real Java condition method. Verify the query returns correctly filtered results.

### 5. Expose the builder test gap explicitly

The existing test `GraphitronSchemaBuilderTest.CONDITION_IS_ALWAYS_NULL` documents the gap. Once the builder path is implemented, this test case becomes a positive assertion: the field's `filters()` should contain a `ConditionFilter`. Rename or replace the test case.

## Out of scope

**Join-level `condition:` in `@reference` path elements** — `BuildContext.resolveConditionRef()` returns null. This is a separate deferred deliverable (the condition becomes a JOIN ON clause, not a WHERE clause). The field-level `@condition` is independent.

**`@condition` on `ARGUMENT_DEFINITION`** — the builder already rejects this with an error message. No change needed.

## Implementation order

| Step | What | Depends on |
|---|---|---|
| 1 | Read `@condition` + reflect method in `buildFilters()` | Nothing |
| 2 | Apply `override` flag | Step 1 |
| 3 | Pass `contextArguments` to reflection | Step 1 |
| 4 | Builder classification tests | Step 1 |
| 5 | Pipeline test + execution test | Steps 1-3 |

Steps 1-3 are one change in `buildFilters()`. Step 4 validates it. Step 5 is end-to-end.
