# Call-Site Unification

## What's in place

`CallParam` is the universal unit for extracted arguments across all method call types. `MethodRef.callParams()` derives the extracted-parameter list by filtering out implicit params (`Table`, `SourceTable`, `DslContext`, `Sources`) and mapping each `ParamSource` to a `CallSiteExtraction`. `ConditionFilter` implements `MethodRef` directly. The generator uses a single `buildArgExtraction(CallParam)` for conditions, service calls, and lookup calls.

`CallParam` carries `typeName` — the generator emits typed locals (`String filter = ...`) instead of erased `Object`.

## What's next

### 1. Enum/text-map detection for service method parameters

Today `MethodRef.callParams()` maps every `ParamSource.Arg` to `CallSiteExtraction.Direct`. If a service method declares `MpaaRating rating` as a parameter, the generated code passes a raw String — the `EnumValueOf` conversion that conditions get for free is missing.

**Fix:** `ServiceCatalog.reflectServiceMethod()` or a post-processing step in `FieldBuilder` inspects each `Arg` param's Java type:
- If the type is a jOOQ-generated enum → `CallSiteExtraction.EnumValueOf(enumClassName)`
- If the type is `String` but the GraphQL argument is an enum with a text mapping → `CallSiteExtraction.TextMapLookup(mapFieldName, valueMapping)`

This reuses the detection logic already in `FieldBuilder.buildFilters()`. Once the extraction is set at build time, the generator requires no changes — `buildArgExtraction` already handles all `CallSiteExtraction` variants.

### 2. OrderBy argument extraction via CallParam

When `OrderBySpec.Argument` extraction is implemented, the `@orderBy` argument should be extracted from the GraphQL context via `CallParam` (a `Direct` extraction). The extracted value is then mapped through `namedOrders` to produce `SortField<?>` instances. The extraction step is a `CallParam`; the mapping step is orderBy-specific logic that runs after extraction.

This depends on the orderBy stub work in the [rewrite roadmap](rewrite-roadmap.md).
