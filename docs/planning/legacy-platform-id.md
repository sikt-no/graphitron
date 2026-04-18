# Legacy PlatformId

> **Status:** Approved
>
> Input field path + Item 1 (output classification + `$fields` emission + dispatch registration) shipped. Item 2 (pipeline tests) shipped via a synthetic `platformidfixture` jOOQ catalog + `PlatformIdPipelineTest` (4 input cases, 4 output cases, positive + negative branches). Item 3 (mutation generator binding) is blocked on argres Phase 3, which owns the `TableInputArg.fieldBindings` population that feeds `InputColumnBinding` entries.


Tables with a composite platform key have no real `id` SQL column. The custom
`KjerneJooqGenerator` instead emits two sets of methods:

- **Record class** (`*Record.java`): `getXId() → String` / `setXId(String) → void`  
  Used by mutation input binding (read/write a platformId string on a record).
- **Table class** (`*.java`): `getXId() → SelectField<String>`  
  Used in SELECT projection to build the computed ID expression.

The input-field classification path (`TypeBuilder` → `InputField.PlatformIdField`)
is fully implemented. The output-field classification path also landed
(see Item 1 below); pipeline tests landed via the synthetic
`platformidfixture` catalog (see Item 2). The mutation generator
binding (Item 3) is still open, blocked on argres Phase 3.

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

## 2 — Pipeline tests (input and output) — SHIPPED

End-to-end SDL → classified-variant coverage lives in
`PlatformIdPipelineTest` alongside a synthetic jOOQ catalog
(`no.sikt.graphitron.rewrite.platformidfixture`). The fixture is
~180 LOC of hand-written `Catalog`/`Schema`/`Tables`/`TableImpl`/
`UpdatableRecordImpl` stubs — enough for `JooqCatalog.loadDefaultCatalog`
to pick it up by reflection when a test points
`RewriteConfig.setProperties(...)` at that package.

Two tables: `bar` (table class exposes `getId()` and `getPersonId()`
returning `SelectField<String>`; record class exposes the matching
`get*Id`/`set*Id(String)` accessors) and `qux` (stock-shaped table with
no platform-id accessors, used for the fallback-miss branch).

Covered cases:

**Input side** (`@table`-annotated input types classify through
`TypeBuilder.resolveInputField`):

| SDL | Expected outcome |
|-----|-----------------|
| `input Foo @table(name: "bar") { id: ID! }` | `PlatformIdField(getterName="getId", setterName="setId")` |
| `input Foo @table(name: "bar") { personId: ID! @field(name: "PERSON_ID") }` | `PlatformIdField(getterName="getPersonId", setterName="setPersonId")` |
| `input Foo @table(name: "qux") { id: ID! }` (no platform-id accessors on record) | `UnclassifiedType` (one unresolved field collapses the `TableInputType`) |
| `input Foo @table(name: "bar") { id: [ID!]! }` | `UnclassifiedType` — list gate short-circuits the ID-scalar fallback |

**Output side** (`FieldBuilder` column lookup + platform-id fallback):

| SDL | Expected outcome |
|-----|-----------------|
| `type Foo @table(name: "bar") { id: ID! }` | `ChildField.PlatformIdField(getterName="getId")` |
| `type Foo @table(name: "bar") { personId: ID! @field(name: "PERSON_ID") }` | `ChildField.PlatformIdField(getterName="getPersonId")` |
| `type Foo @table(name: "qux") { id: ID! }` | `UnclassifiedField` with diagnostic hint |
| `type Foo @table(name: "bar") { id: ID! @nodeId }` (no `@node`) | `UnclassifiedField` — `@nodeId` bypasses the platform-id fallback |

---

## 3 — Mutation generator binding (deferred)

Blocked on argres Phase 3. The `InputColumnBinding` record already
exists in `model/`, but `TableInputArg.fieldBindings` is always
`List.of()` today — Phase 3 populates it by walking the input type's
fields during classification (see `argument-resolution.md#phase-3`).
When that lands, the sum type must include a platform-id variant
carrying no `ColumnRef`, so the mutation generator can dispatch:

```
PlatformIdField  →  record.<setterName>(input.<getterName>())
```

`PlatformIdField` already carries `getterName`/`setterName` pre-resolved, so
no re-derivation is needed at generation time.
