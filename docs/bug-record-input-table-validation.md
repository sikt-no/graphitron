# Bug: `@table` + `@record` Input Types Fail Validation in Rewrite

**Status:** Open  
**Affects:** Rewrite classifier/validator — does not affect the legacy code generator

## Problem

An input type annotated with both `@table` and `@record` validates successfully in the legacy code generator but fails with "unresolvable fields" errors in the rewrite.

Example input that triggers the error:

```graphql
input OpprettStudieoppbygningsdelerChildInput
    @table(name: "EMNEKOMB_I_EMNEKOMB")
    @record(record: {className: "...OpprettStudieoppbygningsdelerChildRecord"}) {
  childStudieoppbygningsdelId: ID!
  rekkefolgenummer: Int
  erLukket: Boolean
  # ...
}
```

Error produced by the rewrite:

```
Type 'OpprettStudieoppbygningsdelerChildInput': mapped to table 'EMNEKOMB_I_EMNEKOMB'
— unresolvable fields: 'childStudieoppbygningsdelId': field has no matching column
and no accessor methods (getChildStudieoppbygningsdelId/setChildStudieoppbygningsdelId)
found on record class; 'rekkefolgenummer': no column 'rekkefolgenummer' found in
table 'EMNEKOMB_I_EMNEKOMB'; ...
```

## Root Cause

### Legacy behaviour (`TableValidator.java`)

The legacy validator skips field-level column validation when `@record` is present:

```java
.filter(it -> !it.hasJavaRecordReference())  // skips if @record is on the type
.map(input -> {
    validateTableFieldsExist(input.getTable(), input.getFields(), false);
    ...
```

When a Java record class is provided, the legacy code assumes that the record class will supply all field accessors at runtime and deliberately does not require every GraphQL field to map to a database column.

### Rewrite behaviour (`TypeBuilder.java`)

`buildInputType()` calls `buildTableInputType()` unconditionally whenever `@table` is present, without checking whether `@record` is also present. Inside `buildInputField()`, there is a fallback to accessor methods (`getXxx`/`setXxx`), but **only for `ID`-typed fields** (the legacy platform-id path). Fields of type `Int`, `Boolean`, `String`, etc. have no such fallback and immediately fail as "unresolvable".

## Comparison

| | Legacy | Rewrite |
|---|---|---|
| `@table` + `@record` on input | Skips column validation | Validates all fields unconditionally |
| Accessor-method fallback | Implicitly assumed for all fields | Only for `ID`-typed fields |
| Result | No error | "unresolvable fields" error |

## Proposed Fix

In `TypeBuilder.buildInputType()`, when the input type has both `@table` and `@record`, either:

1. **Skip per-field column validation** — match legacy semantics; treat the record class as the source of truth for field resolution.
2. **Extend the accessor-method fallback** to all field types (not just `ID`), so that any field whose camelCase name matches a `getXxx`/`setXxx` method on the record class is accepted.

Option 1 is simpler and matches the intent of `@record`: the developer is supplying a hand-written record class that already knows how to map the fields.

## Affected Files

- `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java` — `buildInputType()` / `buildInputField()`
- `graphitron-codegen-parent/graphitron-java-codegen/src/main/java/no/sikt/graphitron/validation/TableValidator.java` — reference for legacy behaviour
