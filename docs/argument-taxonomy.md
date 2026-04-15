# Argument Taxonomy

Plan for classifying field arguments into a sealed hierarchy, replacing the current flat approach where arguments are processed inline during filter/pagination/orderBy resolution.

## Problem

Arguments are currently handled in three separate places with no unified model:

- **Filter arguments** — `FieldBuilder.buildFilters()` iterates field arguments, skips pagination and orderBy args by name, skips input-type args (TODO), and flattens the rest into `BodyParam`/`CallParam` entries on a `GeneratedConditionFilter`. The argument's identity is lost — the generator sees column refs and extraction strategies, not arguments.
- **Pagination arguments** — `FieldBuilder.buildPaginationSpec()` detects `first`/`last`/`after`/`before` by exact name match and extracts them into `PaginationSpec`. No classification — just string matching.
- **OrderBy arguments** — `FieldBuilder.resolveOrderByArgSpec()` detects `@orderBy` by directive and builds `OrderBySpec.Argument`. The argument metadata (sort field name, direction field name, named orders) lives on the field-level `OrderBySpec`, not on the argument itself.
- **Input-type arguments** — silently skipped in `buildFilters()` with a TODO comment. No classification at all.
- **`@condition` on arguments** — rejected with an error message. The directive is only valid on field definitions.

Each of these is a special case in `buildFilters()`. There's no place to ask "what arguments does this field have and what do they mean?"

## What the argument taxonomy gives us

A `List<ArgumentRef>` on every SQL-generating field, where each argument is classified once at build time into a sealed variant:

```java
public sealed interface ArgumentRef {
    String name();
    String typeName();
    boolean nonNull();
    boolean list();
}
```

The generator switches on the variant to decide what to do. The builder resolves everything — column bindings, input type references, orderBy structure — at classify time. No re-derivation in the generator.

## Proposed variants

### Scalar arguments (column-bound)

Arguments that map to a database column for WHERE clause generation.

```java
sealed interface ScalarArg extends ArgumentRef {
    record ColumnArg(name, typeName, nonNull, list, ColumnRef column, CallSiteExtraction extraction) implements ScalarArg {}
    record UnboundArg(name, typeName, nonNull, list, String attemptedColumnName, String reason) implements ScalarArg {}
}
```

`ColumnArg` carries the resolved `ColumnRef` and `CallSiteExtraction` (Direct, EnumValueOf, TextMapLookup, JooqConvert, ContextArg). This is what `BodyParam` + `CallParam` carry today, but on the argument itself rather than on a `GeneratedConditionFilter`.

`UnboundArg` replaces the current path where an unresolvable column produces an `UnclassifiedField`. The argument itself is unbound; the validator reports the error. The field can still be classified (other arguments may be valid).

### Input-type arguments

Arguments whose GraphQL type is a user-defined input type.

```java
sealed interface InputTypeArg extends ArgumentRef {
    record TableInputArg(name, typeName, nonNull, list) implements InputTypeArg {}
    record PlainInputArg(name, typeName, nonNull, list) implements InputTypeArg {}
}
```

`TableInputArg` — the input type resolves to a `TableInputType` (carries `@table` or inferred from field context). The actual `TableInputType` is accessible from the schema's type map by `typeName`. This is the path `buildFilters()` currently skips with a TODO.

`PlainInputArg` — input type without table binding. Developer-provided, not generated.

### OrderBy argument

```java
record OrderByArg(name, typeName, nonNull, list, String sortFieldName, String directionFieldName) implements ArgumentRef {}
```

Carries the resolved input type structure. The `namedOrders` and `base` ordering live on the field-level `OrderBySpec.Argument` as today — `OrderByArg` captures the argument's own metadata (which input fields are sort vs direction). The builder validates the structure at classify time.

### Pagination arguments

```java
record PaginationArg(name, typeName, nonNull, PaginationRole role) implements ArgumentRef {}

enum PaginationRole { FIRST, LAST, AFTER, BEFORE }
```

Replaces `PaginationSpec` with individual classified arguments. The generator collects them by role. This eliminates the string-matching in `buildPaginationSpec()` and makes the classification explicit.

Alternatively, keep `PaginationSpec` as-is and exclude pagination args from `ArgumentRef`. The current approach works and pagination args are simple enough that a taxonomy adds little value. **Decision point — discuss before implementing.**

### Unclassified argument

```java
record UnclassifiedArg(name, typeName, nonNull, list, String reason) implements ArgumentRef {}
```

Unsupported directive on argument, or classification failure. The validator reports the reason.

## Where `ArgumentRef` lives on the model

`SqlGeneratingField` gains `arguments()`:

```java
public interface SqlGeneratingField {
    ReturnTypeRef.TableBoundReturnType returnType();
    List<WhereFilter> filters();
    List<ArgumentRef> arguments();   // NEW
    OrderBySpec orderBy();
    PaginationSpec pagination();
}
```

Every `SqlGeneratingField` variant (all 11) carries the classified argument list. The builder populates it during field classification, after argument resolution.

## Relationship to existing model types

### `GeneratedConditionFilter` / `BodyParam` / `CallParam`

These stay. `GeneratedConditionFilter` is the generator-facing view of "one condition method to call with these parameters." It's built FROM the classified arguments:

```
ColumnArg arguments → GeneratedConditionFilter(callParams from ColumnArg.extraction, bodyParams from ColumnArg.column)
```

The difference: today `buildFilters()` does argument classification AND filter construction in one pass. With `ArgumentRef`, classification happens first (producing `List<ArgumentRef>`), then filter construction reads the classified arguments. Two clean passes instead of one tangled one.

### `WhereFilter` / `ConditionFilter`

`ConditionFilter` is a field-level concern (`@condition` on the field definition), not an argument concern. It stays on `filters()`. `ArgumentRef` doesn't replace it — the two are orthogonal.

### `OrderBySpec`

`OrderBySpec.Argument` stays on the field. `OrderByArg` on the argument list captures which argument carries `@orderBy` and its input structure. The builder uses `OrderByArg` to populate `OrderBySpec.Argument.namedOrders` — same data, resolved from the argument taxonomy rather than from inline directive reading.

## `@condition` builder path (absorbed from condition-filter-builder.md)

The field-level `@condition` directive reading is a separate concern from argument classification, but they're done in the same builder method today (`buildFilters` / `resolveTableFieldComponents`). With the argument taxonomy:

1. **Arguments** are classified into `ArgumentRef` variants (including `UnclassifiedArg` for `@condition` on arguments)
2. **Field-level `@condition`** is read separately — reflects the method via `ServiceCatalog`, constructs `ConditionFilter(className, methodName, params)`, checks `override` flag
3. **Filter list** is assembled: `ConditionFilter` from field-level `@condition` + `GeneratedConditionFilter` from `ColumnArg` arguments (suppressed when `override: true`)

This is the same plan as `condition-filter-builder.md` but now positioned after argument classification rather than independently.

## Implementation order

| Step | What | Depends on |
|---|---|---|
| 1 | Define `ArgumentRef` sealed hierarchy in `model/` | Nothing |
| 2 | Add `arguments()` to `SqlGeneratingField` and all 11 implementing variants | Step 1 |
| 3 | Build `ArgumentRef` classification in `FieldBuilder` — extract from `buildFilters()` into a dedicated `classifyArguments()` method | Steps 1-2 |
| 4 | Rebuild `buildFilters()` to read classified `ArgumentRef` list instead of raw GraphQL arguments | Step 3 |
| 5 | Read field-level `@condition` directive, construct `ConditionFilter` | Step 4 |
| 6 | Handle `TableInputArg` in filter/lookup generation (currently the TODO skip) | Steps 3-4 |
| 7 | Validate: `UnboundArg` → error, `UnclassifiedArg` → error | Step 3 |

Steps 1-4 are the core refactoring. Step 5 absorbs the `@condition` builder path. Step 6 unblocks input-type argument handling. Step 7 is validation.

## Test strategy

- **Builder tests:** SDL with each argument pattern → correct `ArgumentRef` variant on the field's `arguments()` list
- **Builder tests:** `@condition(override: true)` on field → `filters()` contains only `ConditionFilter`, no `GeneratedConditionFilter`
- **Validation tests:** `UnboundArg` → error with candidate hint. `UnclassifiedArg` → error with reason.
- **Pipeline test:** SDL with `@condition` field → generated fetcher includes the condition call
- **Existing tests:** `GeneratedConditionFilter` construction should work unchanged — it's built from `ColumnArg` arguments, same data, different source
