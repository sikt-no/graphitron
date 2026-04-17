# Legacy PlatformId Inputs

Design for classifying mutation input fields that resolve to a legacy platform
key — a composite SQL key stored as a single string via custom jOOQ record
methods (`getXId` / `setXId`) rather than a real SQL column.

**Status:** Steps 1–5 revised — detection conditions and `PlatformIdField`
shape corrected after reviewing the legacy implementation. The column-name gate
(`"id".equalsIgnoreCase`) was wrong; detection now derives the accessor name
from the column name and checks for its existence on the record class.
`PlatformIdField` now carries pre-resolved `getterName`/`setterName`. Step 6
(mutation generator integration) and the classification pipeline tests remain.

Priority: **low**. New schemas should use `@node` + `@nodeId` and this plan
does not block any in-flight work.

---

## Problem

Several existing mutation input types look like this:

```graphql
input AngiBankkontonummerForPersonProfilInput @table(name: "PERSON") {
  id: ID!
  bankkontonummer: String @field(name: "BANKKONTONR")
}
```

`PERSON` has no column named `id`. Its identity is a composite platform key
encoded to a string at the application layer. A custom jOOQ code generator
(`KjerneJooqGenerator`) emits record-class footers with matching accessors:

```java
public String getId()        { /* encode composite columns → platformId string */ }
public void   setId(String id) { /* decode string → composite columns */ }
```

The accessor names follow jOOQ's naming convention: `get<JavaName>()` /
`set<JavaName>(String)`, where `JavaName` is the column name converted to Java
style (`PERSON_ID` → `PersonId`, `ID` → `Id`). So a field mapped via
`@field(name: "PERSON_ID")` has accessors `getPersonId()` / `setPersonId()`.

The legacy codegen (`LookupHelpers.getKeyFieldBlock`) handles this pattern by
checking `it.isID() && !isNodeIdField(it)` — **no column-name condition** — and
emitting `record.get<FieldOverrideName>()` / `record.set<FieldOverrideName>()`
directly, using the `@field(name: ...)` value (or the GraphQL field name) to
derive the method name.

The rewrite classifies input fields through `TypeBuilder.buildInputField`, which
currently only succeeds when `catalog.findColumn(table, columnName)` returns a
match. An `id: ID!` field on a platformId table therefore fails classification,
the whole `TableInputType` is replaced by `UnclassifiedType`, and the build
fails with "unresolvable fields: 'id'" — even though the jOOQ record provides
the required accessors.

---

## Design

### Classification

Add a third variant to the `InputField` sealed interface (update the `permits`
clause alongside this addition), sibling to `ColumnField` and
`ColumnReferenceField`:

```java
/**
 * A field in a {@code @table}-annotated input type that represents a legacy
 * composite platform key. The underlying jOOQ record class exposes
 * {@code getXId()} / {@code setXId(String)} accessor methods (where {@code X}
 * is derived from the resolved column name) added by a custom jOOQ code
 * generator; the table has no corresponding SQL column.
 *
 * <p>Only classified for scalar GraphQL {@code ID}-typed fields with no
 * {@code @nodeId} directive, when no real column matches the resolved name but
 * the record class exposes the expected accessor pair. Fields with
 * {@code @nodeId} take the Relay NodeID path and never produce this variant;
 * list-typed fields ({@code [ID!]!}) are rejected at the fallback boundary.
 *
 * <p>No {@link ColumnRef} is carried. {@code getterName} and {@code setterName}
 * are pre-resolved by the classifier so the generator emits the correct call
 * without re-deriving method names.
 */
record PlatformIdField(
    String parentTypeName,
    String name,
    SourceLocation location,
    String typeName,     // always "ID" — enforced by classifier
    boolean nonNull,
    String getterName,   // e.g. "getId", "getPersonId" — pre-resolved
    String setterName    // e.g. "setId", "setPersonId"
) implements InputField {}
```

No `ColumnRef` is carried. The field's only observable effect is
`record.<setterName>(input.<getterName>())` in the mutation input-binding
generator (deferred until the mutation generator is implemented). `list` is
intentionally absent: the classifier rejects non-scalar inputs at the fallback
boundary.

### Detection

Detection runs inside `TypeBuilder.buildInputField`, as a **fallback** after
`catalog.findColumn(table, columnName)` returns empty. The fallback is gated
by three conditions — all three must hold:

1. The field's GraphQL type (after unwrapping `NonNull`) has named type `ID`.
2. The field is scalar — a `[ID!]!` list input is rejected. Accessor methods
   have no list form.
3. The field has no `@nodeId` directive (`@nodeId` fields are handled by the
   Relay classification path, not here).

When all three hold, the expected accessor names are derived from the resolved
column name using `JooqCatalog.sqlToAccessorSuffix`:

```java
String suffix    = JooqCatalog.sqlToAccessorSuffix(columnName); // e.g. "Id", "PersonId"
String getterName = "get" + suffix;   // e.g. "getId", "getPersonId"
String setterName = "set" + suffix;   // e.g. "setId", "setPersonId"
```

`sqlToAccessorSuffix` handles both input forms:
- **SQL-style** (`ALL_CAPS`, `UPPER_CASE_WITH_UNDERSCORES`): lowercases,
  splits on `_`, capitalizes each word, joins — `PERSON_ID` → `PersonId`,
  `ID` → `Id`.
- **camelCase** (GraphQL field name or `@field(name: "camelCase")`): just
  capitalizes the first letter — `id` → `Id`, `personId` → `PersonId`.

The discriminant is `columnName.equals(columnName.toUpperCase())`: if the
entire name is already uppercase (including all-caps single words like `ID`),
apply the SQL conversion; otherwise just capitalize.

`catalog.hasPlatformIdAccessors(tableName, getterName, setterName)` is then
checked:

- If true → `Resolved(PlatformIdField(..., getterName, setterName))`.
- If false → `Unresolved` with the targeted error message (see "Error quality"
  below).

### Reflection source — `TableRef` vs `JooqCatalog`

`TableRef` is a pure structural record: `(tableName, javaFieldName,
javaClassName, primaryKeyColumns)`. It does not carry the jOOQ `Class<?>` and
should not: most consumers (generators, validators) never need it and widening
the record risks leaking raw jOOQ types past the parse boundary.

Expose the record class via `JooqCatalog` instead:

```java
// on JooqCatalog

/** Converts a resolved column name to the Java accessor suffix used by jOOQ. */
static String sqlToAccessorSuffix(String columnName) {
    if (columnName.equals(columnName.toUpperCase())) {
        // SQL-style (ALL_CAPS / UPPER_CASE_WITH_UNDERSCORES): PERSON_ID → PersonId
        StringBuilder sb = new StringBuilder();
        for (String part : columnName.toLowerCase().split("_+")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }
    // camelCase: id → Id, personId → PersonId
    return Character.toUpperCase(columnName.charAt(0)) + columnName.substring(1);
}

public Optional<Class<?>> findRecordClass(String tableSqlName) {
    return findTable(tableSqlName).map(e -> e.table().getRecordType());
}

public boolean hasPlatformIdAccessors(String tableSqlName, String getterName, String setterName) {
    return findRecordClass(tableSqlName)
        .map(cls -> recordHasPlatformIdAccessors(cls, getterName, setterName))
        .orElse(false);
}

/** Package-private for direct unit testing. */
static boolean recordHasPlatformIdAccessors(Class<?> record, String getterName, String setterName) {
    try {
        var get = record.getMethod(getterName);
        var set = record.getMethod(setterName, String.class);
        return String.class.equals(get.getReturnType())
            && void.class.equals(set.getReturnType());
    } catch (NoSuchMethodException e) {
        return false;
    }
}
```

`hasPlatformIdAccessors` keeps the call site in `TypeBuilder` a single-line
check and isolates the reflection inside `JooqCatalog`, which is already the
only class permitted to do jOOQ reflection (see "Classification belongs at the
parse boundary" in [`rewrite-roadmap.md`](rewrite-roadmap.md)).

`findRecordClass` is kept as the base primitive for future work that may need
the `Class<?>` directly. `sqlToAccessorSuffix` is package-private so it can be
unit-tested independently of the catalog.

### Builder flow

Change `buildInputField` to return a resolution type so it can surface an
actionable error when the fallback fails, rather than returning a bare
`Optional.empty()` whose meaning is derived only from the caller's loop state.

This is a **private static nested type inside `TypeBuilder`** — it is
builder-internal and never reaches generators or validators:

```java
// private, nested inside TypeBuilder
private sealed interface InputFieldResolution {
    record Resolved(InputField field) implements InputFieldResolution {}
    /** {@code lookupColumn} is the SQL column name attempted, or {@code null}
     *  when the failure is not a column miss. Used to emit a single candidate
     *  hint per {@code TableInputType} rather than one per failing field. */
    record Unresolved(String fieldName, String lookupColumn, String reason)
            implements InputFieldResolution {}
}
```

Flow inside `buildInputField`:

1. If `@reference` is set → resolve column via existing path
   (`ColumnReferenceField`), unchanged. Failed `@reference` paths emit
   `Unresolved(name, columnName, "no column ... reachable via @reference path")`.
2. Else resolve column via `catalog.findColumn(tableName, columnName)`. If
   found → `Resolved(ColumnField(...))`, unchanged.
3. Else check the three detection conditions (base type = `ID`, scalar, no
   `@nodeId`). If all hold, derive accessor names from `columnName` and call
   `catalog.hasPlatformIdAccessors(tableName, getterName, setterName)`. If
   true → `Resolved(PlatformIdField(..., getterName, setterName))`.
4. Else → `Unresolved(name, columnName, reason)`. The reason is constructed
   here without a candidate hint:
   - Plain column miss (conditions 1–3 fail): `"no column '<columnName>'
     found in table '<tableName>'"`.
   - `ID`-typed field where `hasPlatformIdAccessors` returned false:
     `"field '<name>' has no matching column and no accessor methods
     (<getterName>/<setterName>) found on record class"` — with
     `lookupColumn = null` so this case does not contribute to the hint.

`buildTableInputType` collects per-field `Unresolved` entries and composes
the `UnclassifiedType` message as `"mapped to table '<t>' — unresolvable
fields: <per-field reasons>" + hint`, where the hint is emitted **once** per
table — based on the first `Unresolved` with a non-null `lookupColumn`. This
matches the existing single-hint format used by the 14 other jOOQ existence
checks in the builder, while still naming every failing field with its
specific cause.

### Validator

`GraphitronSchemaValidator.validateField` already switches on every
`InputField` variant. Add one arm to the exhaustive switch:

```java
case InputField.PlatformIdField ignored -> {}
```

No structural checks are needed: reflection on the record class was the
classification precondition, and re-reflecting here would be redundant.

### Generator

`PlatformIdField` becomes relevant when the mutation generator (rewrite
roadmap item 4 under "Stubs to complete") is implemented. The switch in the
input-binding section of that generator emits, for each input field:

- `ColumnField`            → `record.set<ColumnJavaName>(input.<getter>())`
- `ColumnReferenceField`   → build join-pathed WHERE predicate (read paths);
  write paths currently do not support `@reference` on inputs.
- `PlatformIdField`        → `record.<setterName>(input.<getterName>())` using the pre-resolved names from the model

Read-back (returning the newly written record through a query field) happens
through ordinary column projection and does not touch `PlatformIdField`.

Until the mutation generator lands, `PlatformIdField` is classified but not
consumed — the model carries the information so that once the generator is
written, no further classification work is required.

### Error quality

The targeted message `"field '<name>' has no matching column and no accessor
methods (<getter>/<setter>) found on record class"` names the exact methods
that were checked, making it actionable. It is produced only when the three
detection conditions hold but `hasPlatformIdAccessors` returns false — column
missing **and** the expected accessor pair absent from the record class. When
the accessors exist, classification succeeds silently — the legacy schema keeps
working.

For ordinary column misses (a detection condition fails), `candidateHint` is
applied once at the `UnclassifiedType` level by `buildTableInputType`, keyed
off the first failing column — consistent with the 14 other jOOQ existence
checks in the builder.

---

## Scope and non-goals

### In scope

- Any scalar `ID` (or `ID!`) field on a `@table` input type with no `@nodeId`
  and no `@reference`, where the jOOQ record exposes a matching
  `get<JavaName>()`/`set<JavaName>(String)` accessor pair.
- Both plain field names (`id: ID!` → `getId`/`setId`) and `@field`-overridden
  names (`personId: ID! @field(name: "PERSON_ID")` → `getPersonId`/`setPersonId`).

### Out of scope

- **FK-qualified platformId accessors.** `KjerneJooqGenerator` also emits
  getters/setters for foreign-key-referenced platform IDs (e.g.
  `get<Role><Table>Id()` / `set<Role><Table>Id(String)`). Handling those would
  require either a naming convention on input field names or a new input-level
  directive. Not needed for the common case and not tackled here.
- **Table-class-level `get<qualifier>()`** (returning `SelectField<String>`)
  used in projection. These appear on the jOOQ `Table<?>` class (not the
  record) and are consumed by read paths. Read paths for platformId are a
  separate concern and not currently broken — the `id: ID!` classification
  failure only manifests for **input** types, which is what this plan fixes.
- **Composite-key inputs without platformId methods.** If a schema author
  wants to express the composite key as multiple scalar fields, they already
  can via ordinary `@field(name: ...)` mappings on each component column.
- **Service-method mutations.** `MutationServiceTableField` binds arguments
  via `MethodRef`; it does not go through `InputField` classification.

---

## Implementation order

| Step | What | Status |
|---|---|---|
| 1 | Add `JooqCatalog.findRecordClass`, `hasPlatformIdAccessors(tableName, getter, setter)`, `recordHasPlatformIdAccessors` (package-private), and `sqlToAccessorSuffix` (package-private). | ⬆️ Revised |
| 2 | Add `InputField.PlatformIdField` with `getterName`/`setterName` components; update `permits` clause. | ⬆️ Revised |
| 3 | `InputFieldResolution` nested sealed type with `(fieldName, lookupColumn, reason)`; `buildInputField` returns it; `buildTableInputType` aggregates per-field reasons with one `candidateHint` per table. | ✅ unchanged |
| 4 | Fallback branch: three conditions (scalar, `ID` type, no `@nodeId`) + accessor derivation via `sqlToAccessorSuffix` + `hasPlatformIdAccessors` check; construct `PlatformIdField` with getter/setter names. | ⬆️ Revised |
| 5 | Exhaustive-switch arm in `GraphitronSchemaValidator.validateField`. | ✅ unchanged |
| 6 | (Deferred — picked up with mutation generator) Emit `record.<setterName>(input.<getterName>())` using the pre-resolved names on `PlatformIdField`. Requires `InputColumnBinding` (defined in `argument-resolution.md`) to accommodate `PlatformIdField` bindings — see "Interaction with existing work" below. | ⏳ Deferred |

Steps 1–5 are done. Step 6 happens naturally when someone implements the
mutation generator (see [`rewrite-roadmap.md`](rewrite-roadmap.md),
"Stubs to complete" item 4).

---

## Test strategy

### Reflection helper (direct unit coverage)

- **`JooqCatalogTest`** — update to use `recordHasPlatformIdAccessors(cls, getter, setter)`.
  Add cases for `sqlToAccessorSuffix`: `"id"` → `"Id"`, `"personId"` → `"PersonId"`,
  `"ID"` → `"Id"`, `"PERSON_ID"` → `"PersonId"`. Add a positive case with a
  non-`id` accessor pair (e.g. `getPersonId`/`setPersonId`).

### Builder / classification

These pipeline tests are **not yet added** (step 6 is the right time, alongside
fixture work):

- **Pipeline test**: `input Foo @table(name: "bar") { id: ID! }` against a jOOQ
  catalog whose `bar` record exposes `getId()`/`setId(String)` → `PlatformIdField`
  with `getterName = "getId"`, `setterName = "setId"`.
- **Pipeline test**: same SDL against a catalog whose record does **not** expose
  those methods → `UnclassifiedType` with the targeted error string.
- **Pipeline test**: same SDL with `@nodeId` on the `id` field → existing `@nodeId`
  path; `PlatformIdField` not produced.
- **Pipeline test**: `personId: ID! @field(name: "PERSON_ID")` against a catalog
  whose record exposes `getPersonId()`/`setPersonId(String)` → `PlatformIdField`
  with `getterName = "getPersonId"`, `setterName = "setPersonId"` (covers
  `@field` override + non-`id` accessor derivation).
- **Pipeline test**: `id: [ID!]!` on a platformId record → list-type gate fires,
  `PlatformIdField` not produced.

### Validator

- **Structural test** (`PlatformIdFieldValidationTest`) ✅ added in `6bc2d95`.

### Execution

Execution tests require a jOOQ record class with `getId`/`setId` present on
the classpath. The `graphitron-rewrite-test-fixtures` schema does not model
platformId tables. Two options, in preference order:

1. **Defer** execution coverage until the mutation generator exists (step 6).
   The mutation generator's own execution tests will need a platformId
   fixture anyway; adding it earlier duplicates effort.
2. **Fixture expansion** — add a small table to `init.sql` plus a custom jOOQ
   record-class hook emitting `getId`/`setId` (matching
   `KjerneJooqGenerator`). This is only worthwhile if mutation generation
   lands in the same iteration.

The classification pipeline test above is sufficient to verify the feature
in isolation; execution coverage is tied to mutation-generator readiness.

---

## Interaction with existing work

- **`@nodeId` / Relay path.** `NodeIdField` classification (on `ChildField`)
  is distinct from input-field classification. This plan only touches
  `InputField` and does not alter how Relay IDs are decoded or how
  `@nodeId` directives are read.

- **Argument resolution** ([argument-resolution.md](argument-resolution.md)).
  Argument classification (`ArgumentRef`) is orthogonal — `PlatformIdField`
  exists on **input types**, not **arguments**. However, `TableInputArg`
  (the `ArgumentRef` variant for `@table` input-type arguments) carries
  `List<InputColumnBinding> fieldBindings` — one binding per resolved input
  field. `InputColumnBinding` does not yet exist (flagged as item 7 in the
  argument-resolution review). When it is designed, it must be a **sum type**
  with at minimum:
  - A column-bound variant carrying `ColumnRef` (for `ColumnField` inputs)
  - A platformId variant carrying no column reference (for `PlatformIdField`
    inputs)
  so that the mutation generator can dispatch uniformly across both. **The
  argument-resolution plan owns the `InputColumnBinding` definition.** This
  plan's step 6 cannot land until that definition exists and accommodates
  `PlatformIdField`. Tracked in
  [`rewrite-roadmap.md`](rewrite-roadmap.md#architecture-review-priorities-2026-04-17)
  as cross-plan ownership item P2 #6.

- **`TableRef`.** Unchanged. The task description lists widening `TableRef`
  as one option; this plan rejects it in favour of `JooqCatalog` lookup so
  the record-class information stays contained at the reflection boundary.

---

## Risk and reversibility

Low risk: the change is additive (one new sealed variant, one new catalog
method, one fallback branch). Nothing that currently classifies as
`ColumnField` or `ColumnReferenceField` changes behaviour; the fallback
only activates on a path that currently yields an `UnclassifiedType` error.

Reversible: removing the variant deletes three files' worth of code and
restores the prior "unresolvable field 'id'" error message. No migration
cost on consumers because nothing ships that relies on `PlatformIdField` until
step 6 lands.
