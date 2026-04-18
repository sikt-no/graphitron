# Legacy PlatformId

> **Status:** Approved
>
> Input field path + Item 1 (output classification + `$fields` emission + dispatch registration) shipped. Item 2 (pipeline tests) and Item 3 (mutation generator binding) remain; Item 3 is blocked on `InputColumnBinding` from `argument-resolution.md`.


Tables with a composite platform key have no real `id` SQL column. The custom
`KjerneJooqGenerator` instead emits two sets of methods:

- **Record class** (`*Record.java`): `getXId() → String` / `setXId(String) → void`  
  Used by mutation input binding (read/write a platformId string on a record).
- **Table class** (`*.java`): `getXId() → SelectField<String>`  
  Used in SELECT projection to build the computed ID expression.

The input-field classification path (`TypeBuilder` → `InputField.PlatformIdField`)
is fully implemented. The output-field classification path also landed
(see Item 1 below); pipeline tests and the mutation generator binding
are still open.

---

## 1 — Output field classification — SHIPPED

Shipped pieces:

- **`ChildField.PlatformIdField`** record (`model/ChildField.java`) —
  `(parentTypeName, name, location, getterName)`. Matches the design;
  no `ColumnRef` needed.
- **`FieldBuilder` fallback** (~line 1599) — after column resolution
  fails, checks `platformIdMethods` and returns `PlatformIdField(..., getterName)`
  when the derived `"get" + JooqCatalog.sqlToAccessorSuffix(columnName)`
  matches a table-class method; otherwise keeps `UnclassifiedField`
  with the diagnostic hint. Detection gates: scalar `ID`, not a list,
  no `@nodeId`.
- **Validator** (`GraphitronSchemaValidator.validateChildPlatformIdField`)
  — no-op arm; detection already confirmed the method exists.
- **Generator — `$fields` emission** (`TypeClassGenerator`) — emits
  `fields.add(table.<getterName>())` in the SELECT field list for each
  `PlatformIdField` on a type. Read-back uses `record.<getterName>()`;
  same method name on both classes so no separate treatment.
- **Dispatch registration** (`TypeFetcherGenerator`) — `ChildField.PlatformIdField`
  is in `IMPLEMENTED_LEAVES` with a no-op switch arm (the same pattern
  as `ChildField.ColumnField`: handled elsewhere, no per-field fetcher
  method generated). Fixed on 2026-04-18 — previously the variant was
  in `NOT_IMPLEMENTED_REASONS` with `stub(f)`, which caused the P2 #3
  stubbed-variant validator to incorrectly reject valid schemas using
  platform-id output fields.

---

## 2 — Pipeline tests (input and output) — OPEN

The classification pipeline tests were intentionally deferred; still
not written. Unit tests for the validator exist
(`PlatformIdFieldValidationTest`, `ChildPlatformIdFieldValidationTest`),
but `GraphitronSchemaBuilderTest` has zero `PlatformId` cases — the
schema → classified-variant path is untested end-to-end.

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

Blocked on `InputColumnBinding` (see `argument-resolution.md`, item 7).
When that sum type is designed it must include a platform-id variant carrying
no `ColumnRef`, so the mutation generator can dispatch:

```
PlatformIdField  →  record.<setterName>(input.<getterName>())
```

`PlatformIdField` already carries `getterName`/`setterName` pre-resolved, so
no re-derivation is needed at generation time.
