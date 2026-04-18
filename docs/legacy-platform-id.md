# Legacy PlatformId — Remaining Work

> **Status:** Items 1 and 2 are done. Item 3 (mutation generator binding) is
> the only open task; it's blocked on `InputColumnBinding` in `planning/argument-resolution.md`.


Tables with a composite platform key have no real `id` SQL column. The custom
`KjerneJooqGenerator` instead emits two sets of methods:

- **Record class** (`*Record.java`): `getXId() → String` / `setXId(String) → void`  
  Used by mutation input binding (read/write a platformId string on a record).
- **Table class** (`*.java`): `getXId() → SelectField<String>`  
  Used in SELECT projection to build the computed ID expression.

The input-field classification path (`TypeBuilder` → `InputField.PlatformIdField`)
is fully implemented. The output-field classification path and the mutation
generator binding are still open.

---

## 1 — Output field classification (blocking)

`FieldBuilder` currently fails with "column 'id' could not be resolved in the
jOOQ table" for any `id: ID!` field on a `@table` output type whose table uses
the platform-id pattern. The error now includes a diagnostic hint listing
`get*Id()` table-class methods (added in the last commit) so the problem is
visible, but the field is still `UnclassifiedField`.

### What to add

**`ChildField.PlatformIdField`** — new variant in the `ChildField` sealed
interface (update the `permits` clause):

```java
record PlatformIdField(
    String parentTypeName,
    String name,
    SourceLocation location,
    String getterName    // e.g. "getId" — the table-class method returning SelectField<String>
) implements ChildField {}
```

No `ColumnRef` needed. The generator calls `table.<getterName>()` in the
SELECT; the record-class getter `record.<getterName>()` is used for read-back.
Both have the same name.

**`FieldBuilder` fallback** — after `svc.resolveColumn(columnName, tableType)`
returns empty and before the `UnclassifiedField` return, add a check parallel
to the input-field path:

Detection conditions (all must hold):
1. Scalar `ID` type (after unwrapping NonNull).
2. Not a list.
3. No `@nodeId` directive (those are already handled by `NodeIdField`).

Derive method name: `"get" + JooqCatalog.sqlToAccessorSuffix(columnName)`.

Check on the **table class** (not the record class) using
`JooqCatalog.platformIdOutputMethodNames(tableSqlName)`. If the derived name is
present → `ChildField.PlatformIdField(..., getterName)`. If absent → keep
current `UnclassifiedField` with the diagnostic hint.

**Validator** — add an arm to the exhaustive switch in
`GraphitronSchemaValidator.validateField` for `ChildField.PlatformIdField`.
No structural checks needed; detection already confirmed the method exists.

### Generator

The type-class generator must include `table.<getterName>()` in the SELECT
field list for a `PlatformIdField`, rather than a column reference. The
read-back path (mapping the result row back to a GraphQL value) reads
`record.<getterName>()` — the record-class getter with the same name. No
separate treatment needed there because `getterName` is the same method name
on both the table class and the record class.

---

## 2 — Pipeline tests (input and output)

The classification pipeline tests were intentionally deferred but should land
before or alongside the output-field work.

**Input side** (table `bar`, record exposes `getId()`/`setId(String)`):

| SDL | Expected outcome |
|-----|-----------------|
| `input Foo @table(name: "bar") { id: ID! }` | `PlatformIdField(getterName="getId", setterName="setId")` |
| Same, accessor missing from record | `UnclassifiedType` with targeted error |
| Same, `id: ID! @nodeId` | `NodeIdField` — platform-id path not taken |
| `personId: ID! @field(name: "PERSON_ID")`, record has `getPersonId`/`setPersonId` | `PlatformIdField(getterName="getPersonId", setterName="setPersonId")` |
| `id: [ID!]!` on platform-id record | List gate fires, `PlatformIdField` not produced |

**Output side** (same table, table class exposes `getId() → SelectField<String>`):

| SDL | Expected outcome |
|-----|-----------------|
| `type Foo @table(name: "bar") { id: ID! }` | `ChildField.PlatformIdField(getterName="getId")` |
| Same, method missing from table class | `UnclassifiedField` with diagnostic hint |
| Same, `id: ID! @nodeId` | `NodeIdField` |

---

## 3 — Mutation generator binding (deferred)

Blocked on `InputColumnBinding` (see `planning/argument-resolution.md`, item 7).
When that sum type is designed it must include a platform-id variant carrying
no `ColumnRef`, so the mutation generator can dispatch:

```
PlatformIdField  →  record.<setterName>(input.<getterName>())
```

`PlatformIdField` already carries `getterName`/`setterName` pre-resolved, so
no re-derivation is needed at generation time.
