# OrderBy Implementation Plan

## Current state

`resolveOrderByArgSpec` in `FieldBuilder` builds an `OrderBySpec.Argument` with:

- `name`, `typeName`, `nonNull`, `list`, `sortFieldName`, `directionFieldName` — all resolved
- `namedOrders` — always `List.of()` (**deferred**)
- `base` — a `Fixed` fallback or **null** (no PK and no `@defaultOrder`)

`buildOrderByCode(OrderBySpec orderBy)` in `TypeFetcherGenerator` handles the `Argument` case by
falling back to `base` (or `List.of()` when base is null). It never reads the GraphQL argument.

There is also a **design principle violation** to fix before the orderBy work begins. The roadmap
states: "`ServiceCatalog` is the only place that reads the reflection type tree to classify
parameters." A recent change to `MethodRef.callParams()` broke this by adding `Class.forName` to
the model layer for jOOQ enum detection. Step 0 corrects this.

---

## Step 0 — Move extraction classification to the parse boundary

### 0a — Store `CallSiteExtraction` in `ParamSource.Arg`

Change `ParamSource.Arg` from a marker record to one that carries the pre-resolved extraction
strategy:

```java
// Before:
record Arg() implements ParamSource {}

// After:
record Arg(CallSiteExtraction extraction) implements ParamSource {}
```

`MethodRef.callParams()` becomes a pure mapping with no side effects:

```java
private static CallSiteExtraction toCallSiteExtraction(Param p) {
    return switch (p.source()) {
        case ParamSource.Context ignored  -> new CallSiteExtraction.ContextArg();
        case ParamSource.Arg arg          -> arg.extraction();
        default                           -> new CallSiteExtraction.Direct();
    };
}
```

Three construction sites change mechanically:
- `ServiceCatalog.reflectServiceMethod()` (line 162)
- `ServiceCatalog.reflectTableMethod()` (line 242)
- `TypeFetcherGeneratorTest` (line 384)

### 0b — Detect jOOQ enum params in `ServiceCatalog`

`ServiceCatalog` already has the class loaded (it called `Class.forName` to reach the method).
Check `isEnum()` when classifying `Arg` params and set the extraction accordingly:

```java
// In reflectServiceMethod and reflectTableMethod, replace:
params.add(new MethodRef.Param.Typed(displayName, typeName, new ParamSource.Arg()));

// With:
CallSiteExtraction extraction;
try {
    extraction = Class.forName(typeName).isEnum()
        ? new CallSiteExtraction.EnumValueOf(typeName)
        : new CallSiteExtraction.Direct();
} catch (ClassNotFoundException ignored) {
    extraction = new CallSiteExtraction.Direct();
}
params.add(new MethodRef.Param.Typed(displayName, typeName, new ParamSource.Arg(extraction)));
```

This moves the `Class.forName` check to where the class is already being loaded, and removes it
from `MethodRef` entirely.

### 0c — Detect text-mapped enum params in `FieldBuilder`

`ServiceCatalog` does not have GraphQL schema access, so it cannot detect text-mapped enums
(String Java type + GraphQL enum arg with string value mappings). `FieldBuilder` does.

After `resolveServiceField` gets a `MethodRef` back from `ServiceCatalog`, post-process its `Arg`
params: for each param whose Java type is `String` and whose GraphQL argument type is a
text-mapped enum, rebuild that param's `ParamSource.Arg` with a `TextMapLookup` extraction.

```java
private MethodRef enrichArgExtractions(MethodRef method, GraphQLFieldDefinition fieldDef) {
    var argTypes = fieldDef.getArguments().stream()
        .collect(toMap(GraphQLArgument::getName,
            a -> ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(a.getType())).getName()));
    var newParams = method.params().stream().map(p -> {
        if (!(p.source() instanceof ParamSource.Arg arg)) return p;
        if (!(arg.extraction() instanceof CallSiteExtraction.Direct)) return p;
        if (!String.class.getName().equals(p.typeName())) return p;
        String graphqlTypeName = argTypes.get(p.name());
        if (graphqlTypeName == null) return p;
        var textMapping = buildTextEnumMapping(graphqlTypeName);
        if (textMapping == null) return p;
        String mapFieldName = fieldDef.getName().toUpperCase() + "_"
            + p.name().toUpperCase() + "_MAP";
        var enriched = new CallSiteExtraction.TextMapLookup(mapFieldName, textMapping);
        return new MethodRef.Param.Typed(p.name(), p.typeName(), new ParamSource.Arg(enriched));
    }).toList();
    return new MethodRef.Basic(method.className(), method.methodName(),
        method.returnTypeName(), newParams);
}
```

Call this from `resolveServiceField` (and the equivalent table-method resolution) after a
successful reflection result.

**Note on `TextMapLookup` for service params:** The extraction emits
`ConditionsClass.MAP_FIELD.get(env.getArgument("name"))` — the DB string, not the GraphQL name.
Service methods that take a `String` parameter for a text-mapped enum are expected to receive the
DB string. If a service method wants the raw GraphQL value instead, the developer should declare
the param type as something other than `String` to avoid the conversion.

### 0d — Revert `MethodRef.callParams()` and its test

The `Class.forName` in `callParams()` and the `MethodRefCallParamsTest` were added in the
previous commit. Both should be reverted as part of this step. The test coverage for enum
detection moves to `ServiceCatalog` and `FieldBuilder` unit tests.

---

## Step 1 — Fix `OrderBySpec.Argument.base` nullability

`base: Fixed` (nullable) is the only nullable field inside any `OrderBySpec` variant. Change it to
`base: OrderBySpec` (non-null; `OrderBySpec.None` when no fallback exists). This aligns with how
every other "absent" concept is modelled in the sealed hierarchy.

**Changes:**

`OrderBySpec.Argument`:
```java
record Argument(
    ...
    List<NamedOrder> namedOrders,
    OrderBySpec base          // was: Fixed base (nullable)
) implements OrderBySpec {}
```

`FieldBuilder.resolveOrderByArgSpec`: remove the downcast — `baseSpec` is already `OrderBySpec`:
```java
// Before:
OrderBySpec.Fixed base = baseSpec instanceof OrderBySpec.Fixed f ? f : null;
return new OrderBySpec.Argument(..., List.of(), base);

// After:
return new OrderBySpec.Argument(..., List.of(), baseSpec);
```

`TypeFetcherGenerator.buildOrderByCode`: replace the null guard with a recursive switch call —
`buildOrderByCode(arg.base())` already handles `Fixed` and `None` correctly:
```java
case OrderBySpec.Argument arg ->
    code.add(buildOrderByCode(arg.base()));
```

Any test constructing `OrderBySpec.Argument` with a null base must pass `new OrderBySpec.None()`
instead.

---

## Step 2 — Populate `namedOrders` in `resolveOrderByArgSpec`

Each value in the `@orderBy` input type's sort enum carries a `@order` directive that specifies
the column(s) to sort by. Populating `namedOrders` means resolving each `@order` directive into a
`Fixed` order.

**Location:** `FieldBuilder.resolveOrderByArgSpec`, after identifying `sortFieldName`.

```java
GraphQLEnumType sortEnum = (GraphQLEnumType) GraphQLTypeUtil.unwrapNonNull(
    inputType.getFieldDefinition(sortFieldName).getType());
var namedOrders = new ArrayList<OrderBySpec.NamedOrder>();
for (var value : sortEnum.getValues()) {
    if (!value.hasAppliedDirective("order")) continue;
    OrderBySpec.Fixed order = resolveEnumValueOrderSpec(value, tableSqlName, errors);
    if (order == null) return null;   // error already appended
    namedOrders.add(new OrderBySpec.NamedOrder(value.getName(), order));
}
```

`resolveEnumValueOrderSpec` is a new private method that reads the `@order` directive on an enum
value. It uses the same index/fields/primaryKey resolution logic as `resolveColumnOrderSpec` and
`resolveDefaultOrderSpec`. Extract the shared column-resolution core to avoid duplication.

---

## Step 3 — Implement `buildOrderByCode` for `Argument`

With `namedOrders` populated and `base` non-null, the generator can emit real code.

### Generated code shape (single-value, non-list arg)

```java
Map<String, Object> orderByArg = env.getArgument("orderBy");
List<SortField<?>> orderBy;
if (orderByArg == null) {
    orderBy = List.of(table.TITLE.asc());   // from base
} else {
    String orderByField = (String) orderByArg.get("field");
    String orderByDir   = (String) orderByArg.get("direction");
    orderBy = switch (orderByField) {
        case "TITLE"        -> List.of("DESC".equals(orderByDir) ? table.TITLE.desc()        : table.TITLE.asc());
        case "RELEASE_YEAR" -> List.of("DESC".equals(orderByDir) ? table.RELEASE_YEAR.desc() : table.RELEASE_YEAR.asc());
        default -> List.of(table.TITLE.asc());  // base fallback
    };
}
```

### Generated code shape (list arg)

```java
List<Map<String, Object>> orderByArgs = env.getArgument("orderBy");
List<SortField<?>> orderBy;
if (orderByArgs == null || orderByArgs.isEmpty()) {
    orderBy = List.of(table.TITLE.asc());   // from base
} else {
    var parts = new ArrayList<SortField<?>>();
    for (var entry : orderByArgs) {
        String f = (String) entry.get("field");
        String d = (String) entry.get("direction");
        switch (f) {
            case "TITLE" -> parts.add("DESC".equals(d) ? table.TITLE.desc() : table.TITLE.asc());
            // ...
        }
    }
    orderBy = parts;
}
```

### Extraction

Call `env.getArgument(arg.name())` directly in `buildOrderByCode` — no `CallParam` indirection.
`buildArgExtraction` is for arguments passed to user-provided methods; the `orderBy` variable is
built inline, not forwarded to a method call. The design principle (one extraction mechanism)
applies at method call sites, not here.

---

## Step 4 — Direction handling

`directionFieldName` is always present (validated in `resolveOrderByArgSpec`). The direction value
arrives as a GraphQL enum string (`"ASC"` or `"DESC"`). Apply `"DESC".equals(dir)` per column in
the `NamedOrder.order` and call `.desc()` or `.asc()` accordingly.

For multi-column `Fixed` orders inside a `NamedOrder`, apply the same direction override to every
column in the list.

---

## Step 5 — Testing

- **`ServiceCatalog` test**: verify that a method with an enum parameter produces
  `CallSiteExtraction.EnumValueOf`, and a String parameter produces `CallSiteExtraction.Direct`.
- **`FieldBuilder` test**: verify that a String `Arg` param whose GraphQL argument is a
  text-mapped enum produces `CallSiteExtraction.TextMapLookup` after enrichment.
- **`FieldBuilder` test**: verify that `namedOrders` is populated correctly from a schema
  containing `@orderBy` (size, enum value names, column references).
- **Generator test**: verify that a field with `OrderBySpec.Argument` produces a fetcher with an
  `orderBy` variable declared (structural check only — no body assertions).
- **Compilation test**: `graphitron-rewrite-test-spec mvn compile` catches type errors in the
  generated `Map<String, Object>` access and `SortField<?>` construction.

---

## Order of implementation

1. Step 0 (parse-boundary fix) — do first; corrects the design violation and unlocks text-map
   detection as a bonus
2. Step 1 (base non-null) — independent of step 0; small, do alongside or immediately after
3. Step 2 (namedOrders population) — builder-only; no generator change yet
4. Step 3+4 (generator body) — depends on steps 1 and 2
5. Step 5 (tests) — alongside each step
