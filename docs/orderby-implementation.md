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

`ServiceCatalog` already has the class loaded. Check `isEnum()` when classifying `Arg` params:

```java
// In both reflectServiceMethod and reflectTableMethod, replace:
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

### 0c — Detect text-mapped enum params in `FieldBuilder`

Service and table-method code must not know about GraphQL — a `String` param that corresponds to
a text-mapped enum should receive the DB string, not the GraphQL enum name. `ServiceCatalog` cannot
detect this because it has no GraphQL schema access; `FieldBuilder` does.

After `resolveServiceField` (and the equivalent table-method resolution) gets a `MethodRef` back,
post-process its `Arg` params:

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
        return new MethodRef.Param.Typed(p.name(), p.typeName(),
            new ParamSource.Arg(new CallSiteExtraction.TextMapLookup(mapFieldName, textMapping)));
    }).toList();
    return new MethodRef.Basic(method.className(), method.methodName(),
        method.returnTypeName(), newParams);
}
```

**Map generation:** For service and table-method params, the static `Map<String,String>` field
lives in the generated `*Fetchers` class (not `*Conditions`). `buildArgExtraction` already
accepts a `conditionsClassName` parameter; pass the `*Fetchers` class name at service call sites
so the reference resolves correctly. `TypeFetcherGenerator` emits the static map fields into the
`*Fetchers` class alongside the data fetchers that reference them.

### 0d — Revert `MethodRef.callParams()` and its test

The `Class.forName` in `callParams()` and the `MethodRefCallParamsTest` were added in a prior
commit. Both should be reverted as part of this step. Coverage moves to `ServiceCatalog` and
`FieldBuilder` unit tests (see Step 5).

---

## Step 1 — Fix `OrderBySpec.Argument.base` nullability

`base: Fixed` (nullable) is the only nullable field inside any `OrderBySpec` variant. Change it to
`base: OrderBySpec` (non-null; `OrderBySpec.None` when no fallback exists).

**Why this is safe:** `resolveDefaultOrderSpec` already returns `OrderBySpec` (never null) — it
returns `Fixed` from the PK or `None` when the table has no PK and no `@defaultOrder`. The
current downcast `baseSpec instanceof Fixed f ? f : null` incorrectly maps `None` to `null`. Step
1 removes the downcast; `None` passes through correctly.

The one case where `resolveDefaultOrderSpec` can return null today is when `@defaultOrder` is
present but `resolveColumnOrderSpec` fails (index not found, etc.) — this is a pre-existing
error-propagation gap. When it's fixed, `resolveDefaultOrderSpec` should call `errors.add(...)` and
return `null` as a signal for the caller to abort, the same as every other resolution failure. Step
1 is not responsible for fixing that gap, but `resolveOrderByArgSpec` should guard against it:

```java
OrderBySpec baseSpec = resolveDefaultOrderSpec(fieldDef, tableSqlName);
if (baseSpec == null) return null;   // error already appended by resolveColumnOrderSpec
return new OrderBySpec.Argument(..., List.of(), baseSpec);
```

**Changes:**

`OrderBySpec.Argument`:
```java
record Argument(
    ...
    List<NamedOrder> namedOrders,
    OrderBySpec base          // was: Fixed base (nullable)
) implements OrderBySpec {}
```

`TypeFetcherGenerator.buildOrderByCode`: replace the null guard with a recursive call —
`buildOrderByCode(arg.base())` handles `Fixed`, `None`, and (transitionally) another `Argument`:
```java
case OrderBySpec.Argument arg ->
    code.add(buildOrderByCode(arg.base()));
```

Any test constructing `OrderBySpec.Argument` with a null base must pass `new OrderBySpec.None()`.

---

## Step 2 — Populate `namedOrders` in `resolveOrderByArgSpec`

Each value in the sort enum carries a `@order` directive specifying the column(s). Populating
`namedOrders` means resolving each `@order` directive into a `Fixed` order.

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
value using the same index/fields/primaryKey resolution logic as `resolveColumnOrderSpec`. Extract
the shared column-list resolution to a helper to avoid duplication.

---

## Step 3 — Implement `buildOrderByCode` for `Argument`

With `namedOrders` populated and `base` non-null, extract the orderBy logic into a private static
helper method on the `*Fetchers` class rather than inlining it in the fetcher body. Fetcher bodies
should stay readable; the switch over named orders is the kind of detail that belongs in a
well-named helper.

**Generated helper (single-value arg):**
```java
private static List<SortField<?>> filmOrderBy(DataFetchingEnvironment env) {
    Map<String, Object> arg = env.getArgument("orderBy");
    if (arg == null) return List.of(FILM.TITLE.asc());   // base
    String field = (String) arg.get("field");
    String dir   = (String) arg.get("direction");
    return switch (field) {
        case "TITLE"        -> List.of("DESC".equals(dir) ? FILM.TITLE.desc() : FILM.TITLE.asc());
        case "RELEASE_YEAR" -> List.of("DESC".equals(dir) ? FILM.RELEASE_YEAR.desc() : FILM.RELEASE_YEAR.asc());
        default -> List.of(FILM.TITLE.asc());   // base fallback
    };
}
```

**Generated helper (list arg):**
```java
private static List<SortField<?>> filmOrderBy(DataFetchingEnvironment env) {
    List<Map<String, Object>> args = env.getArgument("orderBy");
    if (args == null || args.isEmpty()) return List.of(FILM.TITLE.asc());   // base
    var parts = new ArrayList<SortField<?>>();
    for (var entry : args) {
        String f = (String) entry.get("field");
        String d = (String) entry.get("direction");
        switch (f) {
            case "TITLE" -> parts.add("DESC".equals(d) ? FILM.TITLE.desc() : FILM.TITLE.asc());
        }
    }
    return parts;
}
```

**Fetcher body** (unchanged from the Fixed case):
```java
List<SortField<?>> orderBy = filmOrderBy(env);
return Film.selectMany(env, condition, orderBy);
```

The helper name is `<fieldName>OrderBy` — e.g., `filmsOrderBy` for a field named `films`. The
table alias is obtained via `declareTableLocal` as in every other table method.

### Extraction approach

Call `env.getArgument(arg.name())` directly inside the helper — no `CallParam` indirection.
`buildArgExtraction` is for arguments forwarded to user-provided methods. The orderBy variable is
built inline from the input object; the design principle (one extraction mechanism per call site)
applies at method call sites, not here.

---

## Step 4 — Direction handling

The direction value arrives as a GraphQL enum string (`"ASC"` or `"DESC"`). Apply
`"DESC".equals(dir)` to choose `.desc()` or `.asc()` per column in the `NamedOrder.order` column
list. For multi-column `Fixed` orders the same direction override applies to every column.

`Fixed.direction` inside a `NamedOrder` records the natural/default direction for that named order
(the value from the `@order` directive). When the caller supplies a direction override via the
input's `directionFieldName` field, the override takes precedence. If the direction field is
absent from the input object at runtime (only possible if it is declared optional in the schema),
fall back to `NamedOrder.order.jooqMethodName()`.

---

## Step 5 — Testing

- **`ServiceCatalog` test**: verify that a method with a jOOQ enum parameter produces
  `CallSiteExtraction.EnumValueOf`, and a `String` parameter produces `CallSiteExtraction.Direct`.
- **`FieldBuilder` test**: verify that a `String` `Arg` param whose GraphQL argument is a
  text-mapped enum produces `CallSiteExtraction.TextMapLookup` after enrichment.
- **`FieldBuilder` test**: verify that `namedOrders` is populated correctly from a schema
  containing `@orderBy` (correct size, enum value names, column references).
- **Generator test**: verify that a field with `OrderBySpec.Argument` causes `TypeFetcherGenerator`
  to emit a `<fieldName>OrderBy` helper method with the correct signature. Do not assert on the
  switch body.
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
