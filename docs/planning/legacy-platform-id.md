# Legacy PlatformId

> **Status:** Draft
>
> Items 1–2 shipped (output classification + `$fields` emission + dispatch registration + synthetic-fixture pipeline tests). Item 3 (mutation generator binding) is blocked on argres Phase 3. Item 4 (argument/filter condition emission — `table.hasId`/`hasIds` for ID-typed args and record-input ID fields) was previously uncaptured; added 2026-04-18 after a review surfaced that schemas filtering platform-id tables by ID currently fail classification with "column 'id' could not be resolved".


Tables with a composite platform key have no real `id` SQL column. The custom
`KjerneJooqGenerator` instead emits three sets of methods:

- **Record class** (`*Record.java`): `getXId() → String` / `setXId(String) → void`  
  Used by mutation input binding (read/write a platformId string on a record).
- **Table class — projection** (`*.java`): `getXId() → SelectField<String>`  
  Used in SELECT projection to build the computed ID expression.
- **Table class — filter** (`*.java`): `hasXId(String) → Condition` /
  `hasXIds(Collection<String>) → Condition`  
  Used in WHERE clauses to filter by the composite ID. Decodes the base64
  string internally and emits `row(keyCols).in(rows)` — the same shape as
  `NodeIdStrategy.hasIds`, but baked into the table.

The input-field classification path (`TypeBuilder` → `InputField.PlatformIdField`)
is fully implemented. The output-field classification path also landed
(see Item 1 below); pipeline tests landed via the synthetic
`platformidfixture` catalog (see Item 2). The mutation generator
binding (Item 3) is still open, blocked on argres Phase 3. The
argument/filter condition emission (Item 4) is still open — no
classification or emitter path exists today.

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

---

## 4 — Argument/filter condition emission (open)

### What's missing

Schemas that filter a platform-id-backed table by an ID argument or an ID
field on a record input have no generator path today. Legacy emits the
following on the table alias (not via `NodeIdStrategy`):

| Schema | Generated condition |
|---|---|
| `query(id: [ID]! @lookupKey): CustomerTable` | `_a_customer.hasIds(_mi_id.stream().collect(Collectors.toSet()))` |
| `query(id: ID): CustomerTable` (non-lookup) | `_mi_id != null ? _a_customer.hasId(_mi_id) : DSL.noCondition()` |
| record-input `id` inside `row(...).in(...)` | `_a_customer.hasId(_mi_inRecordList.get(i).getId())` |

What the rewrite does instead: `FieldBuilder.classifyArgument`
(`FieldBuilder.java:649`) binds scalar args via
`ctx.catalog.findColumn(tableName, argColumnName)`. Platform-id tables have
no real `id` column, so `findColumn` returns empty and the arg becomes
`ArgumentRef.ScalarArg.UnboundArg` with a validation error
(`"column 'id' could not be resolved in table 'customer'"`). The schema is
rejected outright. The VALUES+JOIN emitter at
`LookupValuesJoinEmitter.java:207` is also column-bound unconditionally
(`table.$COL.getDataType()` + `.using(table.$COL)`) — even if classification
surfaced a platform-id arg, the emitter would require a real `TableField`
that doesn't exist.

### Design — sum-type extension

**Argument variant.** Add
`ArgumentRef.ScalarArg.PlatformIdArg(name, typeName, nonNull, list, hasIdMethodName, hasIdsMethodName, extraction, argCondition, isLookupKey)` —
structurally a sibling of `ColumnArg` but carrying the two method names
instead of a `ColumnRef`. Both method names are derived once at classify
time from `"has" + JooqCatalog.sqlToAccessorSuffix(columnName)`.

**Classification fallback.** In `FieldBuilder.classifyArgument`, when
`findColumn` misses AND `typeName == "ID"` AND the target table has
platform-id filter methods (new `JooqCatalog.platformIdFilterMethodNames`
probe checking for `hasId(String) → Condition` and
`hasIds(Collection<String>) → Condition` via reflection — mirror of
`platformIdOutputMethodNames`), emit `PlatformIdArg` instead of
`UnboundArg`. `@nodeId` on the arg bypasses the fallback (symmetric with
the output-side gate in Item 1).

**Lookup mapping — sum-type refactor.** `LookupMapping` becomes sealed
with two records:

```java
sealed interface LookupMapping permits ColumnMapping, PlatformIdMapping {
    TableRef targetTable();
}
record ColumnMapping(List<LookupColumn> columns, TableRef targetTable) implements LookupMapping {}
record PlatformIdMapping(String hasIdsMethodName, CallSiteExtraction extraction, TableRef targetTable) implements LookupMapping {}
```

This eliminates the implicit invariant "columns empty iff platform-id set";
violating it becomes a compile error rather than a classifier bug.
`projectForLookup` produces `PlatformIdMapping` when the single lookup arg
is `PlatformIdArg`, otherwise `ColumnMapping` as today.

**Lookup emitter branch.** `LookupValuesJoinEmitter` dispatches on the
mapping variant. The platform-id arm skips VALUES+JOIN entirely and emits:

```java
return dsl
    .select(<typeFieldsCall>)
    .from(table)
    .where(idsList == null || idsList.isEmpty()
        ? DSL.noCondition()
        : table.<hasIdsMethodName>(new java.util.HashSet<>(idsList)))
    .fetch();
```

No derived VALUES table, no JOIN USING, no `idx`-ordered `orderBy` —
input-order preservation is not meaningful for `hasIds` (the table's
composite-key column ordering wins). Legacy accepts this; we match legacy.

**Non-lookup filter path.** The same `PlatformIdArg` variant must flow
through the field-level `@condition` / plain-WHERE path that
`projectFilters` feeds. Single-value args emit
`arg != null ? table.<hasIdMethodName>(arg) : DSL.noCondition()`; list
args use `hasIds` with the empty-or-null guard. The classifier variant is
the same; only the emitter branch differs between lookup and filter.

**Record-input ID field.** When a `@record`-backed input type carries a
`PlatformIdField`, the `row(...).in(...)` emission calls
`table.hasId(record.<getterName>())` per row. This reuses
`InputField.PlatformIdField(getterName, setterName)` already shipped —
the emitter derives the matching `hasId` method name from the getter
suffix at generation time, or the model gains a `hasIdMethodName` field
up front. Prefer the latter: classify-once, no re-derivation.

### What Item 4 is NOT

- **`@nodeId` relay IDs.** Handled separately via `NodeIdStrategy.hasId/hasIds`
  — those go through the node-ID strategy call site, not the table alias.
- **Composite non-ID lookup keys on platform-id tables.** Out of scope;
  those already resolve to real columns and flow through the standard
  `ColumnMapping` path.
- **Decoding the composite ID on the Java side.** The `hasId/hasIds`
  methods on the generated table class do this internally — no rewrite
  code unpacks the base64 string.

### Scheduling

Lands in two commits, neither gated on argres:

1. **`LookupMapping` sum-type refactor** — mechanical; no behavioural
   change. All existing lookup fields classify as `ColumnMapping`; emitter
   stays identical.
2. **`PlatformIdArg` classification + emitter branches** — the
   `JooqCatalog.platformIdFilterMethodNames` probe, the classifier
   fallback, the lookup-emitter platform-id arm, and the non-lookup filter
   arm. Pipeline tests extend `PlatformIdPipelineTest` with four cases:
   single-value lookup, list-value lookup, single-value non-lookup filter,
   record-input ID field. Execution tests require a sakila-based or
   platform-id-fixture-based table exposing `hasId/hasIds`.

### Open questions

- **Single-arg invariant for `PlatformIdMapping`.** Legacy never emits
  `table.hasIds(...)` composed with other column keys — `hasIds` is the
  only WHERE clause. Enforce at classify time: if a field has a
  `PlatformIdArg` AND any other `@lookupKey` arg, reject as
  `"@lookupKey on platform-id id cannot combine with additional keys"`.
- **Where the `hasId` on a record-input ID field is resolved.** Model
  side (pre-resolved on `InputField.PlatformIdField`) vs. generator side
  (look up at emission time). Classify-once is the project default;
  extending the model is the proposed path.

### History

- **2026-04-18 (initial Draft)** — captured after review surfaced the
  gap: legacy emits `table.hasId/hasIds` on platform-id tables for
  ID-argument WHERE clauses (see `queries/fetch/lookup/default`,
  `inputs/optional/default`, `records/withListedInputConditions`
  legacy expected fixtures); the rewrite classifies ID args against
  real columns only and rejects the schema. Neither
  `legacy-platform-id.md` nor `argument-resolution.md` addressed this.
