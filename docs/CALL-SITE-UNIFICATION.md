# Call-Site Unification

Design document for unifying the three parallel argument-extraction patterns in the rewrite generators into a single `CallParam`-based protocol.

## Problem

The generators have three independent patterns for the same operation — extracting argument values from the GraphQL context and passing them to a method call:

| Pattern | Used for | Dispatch key | Extraction | Enum support |
|---|---|---|---|---|
| `WhereFilter` / `CallParam` / `CallSiteExtraction` | Condition methods | `CallSiteExtraction` (4 variants) | Typed inline expressions | Yes (`EnumValueOf`, `TextMapLookup`) |
| `MethodRef.Param` / `ParamSource` inline switch | Service methods | `ParamSource` (6 variants, 2 handled) | `Object` local variables | No |
| `OrderBySpec` direct switch | OrderBy argument | `OrderBySpec` variant | Stub (not yet implemented) | N/A |

Pattern A (conditions) is clean: the builder pre-resolves every argument into a `CallParam` carrying a `CallSiteExtraction`, and the generator switches once in `buildArgExtraction()`. Pattern B (services) re-implements the same logic by switching on `ParamSource` directly in the generator, erasing everything to `Object`, with no type conversion. Pattern C (orderBy) carries the metadata for extraction (`name`, `sortFieldName`, `directionFieldName`, `namedOrders`) but has no extraction code yet.

### Concrete problems

1. **Service methods cannot receive jOOQ enums.** If a service method declares `MpaaRating rating` as a parameter, the generated code passes `(Object) env.getArgument("rating")` — a raw String at runtime. The `EnumValueOf` conversion that conditions get for free is missing.

2. **Duplicate extraction logic.** `buildArgExtraction()` (line 222) and the `ParamSource` switch in `buildServiceRowsMethod()` (line 443) both emit `env.getArgument()` / `graphitronContext(env).getContextArgument()`. The condition path is tested and handles null guards; the service path does not.

3. **`ParamSource` conflates two concerns.** It mixes *extracted* parameters (values from the GraphQL context: `Arg`, `Context`) with *implicit* parameters (structural: `Table`, `SourceTable`, `DslContext`, `Sources`). The generator must filter and skip the implicit ones — the `default -> {}` at line 451 silently drops them. `CallSiteExtraction` cleanly separates these: it only describes extracted parameters.

## Current architecture

```
                    Condition path                    Service path
                    ──────────────                    ────────────
Builder:     FieldBuilder.buildFilters()        ServiceCatalog.reflectServiceMethod()
                     │                                     │
                     ▼                                     ▼
Model:    GeneratedConditionFilter               MethodRef(params: List<Param>)
          ├── callParams: List<CallParam>         └── Param.Typed(name, typeName, source: ParamSource)
          │     └── CallSiteExtraction
          └── bodyParams: List<BodyParam>
                     │                                     │
                     ▼                                     ▼
Generator:  buildArgExtraction()                 inline switch on ParamSource
            (switch on CallSiteExtraction)       (emits Object locals, no conversion)
```

The bridge between the two is `ConditionFilter.toExtraction()`, which narrows `ParamSource` → `CallSiteExtraction` (only `Direct` and `ContextArg`). This bridge exists because `ConditionFilter` wraps a `MethodRef` but must satisfy the `WhereFilter.callParams()` contract.

## Proposed design

Make `CallParam` the universal unit of "one extracted argument" for all method call types.

### Principle

Every generated method call has two kinds of parameters:

- **Implicit** — structural, not from GraphQL args. Table alias, batch keys, DSLContext. Handled by each call-site's code pattern (the code around the call).
- **Extracted** — pulled from the GraphQL context, possibly with conversion. `CallParam(name, extraction)` describes these uniformly.

The generator uses a single `buildArgExtraction(CallParam)` for all extracted parameters regardless of what kind of method is being called.

### What changes

#### Model

**`MethodRef` gains a `callParams()` derivation.**

```java
public record MethodRef(
    String className,
    String methodName,
    String returnTypeName,
    List<Param> params
) {
    /** Extracted parameters only, in declaration order. Skips implicit params (Table, SourceTable, DslContext, Sources). */
    public List<CallParam> callParams() {
        return params.stream()
            .filter(p -> p.source() instanceof ParamSource.Arg || p.source() instanceof ParamSource.Context)
            .map(p -> new CallParam(p.name(), toExtraction(p)))
            .toList();
    }
}
```

This is the same derivation `ConditionFilter.callParams()` does today, but on `MethodRef` itself. `ConditionFilter.callParams()` becomes a delegation to `method.callParams()`.

**The `toExtraction()` mapping moves to the builder** and becomes richer. Today `ConditionFilter.toExtraction()` only produces `Direct` and `ContextArg`. Once the builder resolves extraction strategies for all method types, the mapping covers `EnumValueOf` and `TextMapLookup` too — when the builder can detect that a service method parameter is a jOOQ enum, it sets the same `CallSiteExtraction.EnumValueOf` that conditions already use.

**Migration option**: start with the simple derivation on `MethodRef` (matching today's `ConditionFilter.toExtraction()` logic). Enum/text-map detection for service params is a separate, later enhancement that enriches the `CallSiteExtraction` assigned to each param.

#### Builder

**`ServiceCatalog`** continues producing `MethodRef` with `ParamSource`-tagged params. No change needed for the initial unification.

**Later enhancement**: `ServiceCatalog.reflectServiceMethod()` or a post-processing step in `FieldBuilder` inspects each `ParamSource.Arg` param's Java type. If the type is a jOOQ-generated enum → `CallSiteExtraction.EnumValueOf(enumClassName)`. If the type is `String` but the GraphQL argument is an enum with a text mapping → `CallSiteExtraction.TextMapLookup(...)`. This reuses the same detection logic already in `FieldBuilder.buildFilters()`.

#### Generator

**`buildServiceRowsMethod()`** replaces the inline `ParamSource` switch with:

```java
// Extract arguments using the same path as conditions
for (var param : smr.callParams()) {
    builder.addStatement("var $L = $L", param.name(), buildArgExtraction(param, smr.className()));
}
```

The implicit parameters (`Sources` → `keys`, `DslContext`, `Table`, `SourceTable`) continue to be handled by the structural code around the call — they're not `CallParam`s.

**`buildArgExtraction()`** is already correct — it switches on `CallSiteExtraction` and handles all four variants. No changes needed. It just gains more callers.

#### OrderBy (future)

When `OrderBySpec.Argument` extraction is implemented, the argument-unpacking code can use `CallParam` too. The `@orderBy` argument is extracted from the GraphQL context (a `Direct` extraction), then its value is mapped through `namedOrders` to produce `SortField<?>` instances. The extraction step is a `CallParam`; the mapping step is orderBy-specific logic that runs after extraction.

## Migration order

1. **Add `MethodRef.callParams()`** — simple derivation, `Context` → `ContextArg`, everything else → `Direct`. Move `ConditionFilter.toExtraction()` logic there. Make `ConditionFilter.callParams()` delegate to `method.callParams()`.

2. **Refactor `buildServiceRowsMethod()`** — replace the `ParamSource` switch with a loop over `smr.callParams()` + `buildArgExtraction()`. Keep implicit param handling as-is.

3. **Apply to table method calls** — when `TableMethodField` stubs are implemented, use the same `methodRef.callParams()` + `buildArgExtraction()` path.

4. **Enrich extraction for service params** (optional, separate PR) — add enum/text-map detection for service method parameters in the builder. This makes `EnumValueOf` and `TextMapLookup` available for service calls without any generator changes.

5. **Apply to orderBy** — when `OrderBySpec.Argument` is implemented, extract the orderBy argument value via `CallParam`, then map through `namedOrders`.

Steps 1-3 are mechanical and can be done together. Step 4 is an enhancement. Step 5 depends on the orderBy stub work.

## Files involved

| File | Change |
|---|---|
| `model/MethodRef.java` | Add `callParams()` method |
| `model/ConditionFilter.java` | Delegate `callParams()` to `method.callParams()`, remove `toExtraction()` |
| `generators/TypeFieldsGenerator.java` | Refactor `buildServiceRowsMethod()` to use `callParams()` + `buildArgExtraction()` |
| `ServiceCatalog.java` | No change for steps 1-3; enum detection in step 4 |
| `FieldBuilder.java` | No change for steps 1-3; extraction-strategy resolution for service params in step 4 |
