# Legacy PlatformId Inputs

Design for classifying mutation input fields named `id: ID!` that resolve to a
legacy platform key — a composite SQL key stored as a single string via custom
jOOQ record methods (`getId` / `setId`) rather than a single `id` column.

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
public String getId() { /* encode composite columns → platformId string */ }
public void   setId(String id) { /* decode string → composite columns */ }
```

The legacy codegen (`LookupHelpers.getKeyFieldBlock`) handles this by falling
back to the field's `MethodMapping` when an `id: ID!` field has no `@nodeId` —
emitting `record.getId()` / `record.setId(input)` directly without consulting
the column catalogue.

The rewrite classifies input fields through `TypeBuilder.buildInputField`, which
currently only succeeds when `catalog.findColumn(table, columnName)` returns a
match. An `id: ID!` field on a platformId table therefore fails classification,
the whole `TableInputType` is replaced by `UnclassifiedType`, and the build
fails with "unresolvable fields: 'id'" — even though the jOOQ record provides
the required accessors.

---

## Design

### Classification

Add a third variant to the `InputField` sealed hierarchy, sibling to
`ColumnField` and `ColumnReferenceField`:

```java
/**
 * A field in a {@code @table}-annotated input type that represents a legacy
 * composite platform key. The underlying jOOQ record class exposes
 * {@code getId()} / {@code setId(String)} convenience methods added by a
 * custom jOOQ code generator; the table itself has no {@code id} column.
 *
 * <p>Only classified for GraphQL {@code ID}-typed fields named {@code id}
 * (or with {@code @field(name: "id")}) that cannot be resolved to a real
 * column. Fields with {@code @nodeId} take the Relay NodeID path and never
 * produce this variant.
 */
record PlatformIdField(
    String parentTypeName,
    String name,
    SourceLocation location,
    String typeName,        // always "ID"
    boolean nonNull,
    boolean list            // always false — platformId is scalar
) implements InputField {}
```

No `ColumnRef` is carried. The field's only observable effect is a
`record.setId(input)` call in the mutation input-binding generator, and
`record.getId()` in read-back paths (when those are implemented).

### Detection

Detection runs inside `TypeBuilder.buildInputField`, as a **fallback** after
`catalog.findColumn(table, columnName)` returns empty. The fallback is gated
by two schema-level conditions:

1. The field's GraphQL type (after unwrapping `NonNull`/`List`) is the scalar
   `ID`.
2. The field has no `@nodeId` directive (`@nodeId` fields are handled by the
   Relay classification path, not here).

When both hold, reflect the jOOQ record class for:

- `public String getId()` — zero parameters, returns `java.lang.String`.
- `public void setId(String)` — one `String` parameter.

Both methods must exist. If either is missing, the field remains
unclassified and the caller surfaces the specific error described below.

### Reflection source — `TableRef` vs `JooqCatalog`

`TableRef` is a pure structural record: `(tableName, javaFieldName,
javaClassName, primaryKeyColumns)`. It does not carry the jOOQ `Class<?>` and
should not: most consumers (generators, validators) never need it and widening
the record risks leaking raw jOOQ types past the parse boundary.

Expose the record class via `JooqCatalog` instead:

```java
// on JooqCatalog
public Optional<Class<?>> findRecordClass(String tableSqlName) {
    return findTable(tableSqlName).map(e -> e.table().getRecordType());
}

public boolean hasPlatformIdMethods(String tableSqlName) {
    return findRecordClass(tableSqlName)
        .map(JooqCatalog::recordHasPlatformIdMethods)
        .orElse(false);
}

private static boolean recordHasPlatformIdMethods(Class<?> record) {
    try {
        var get = record.getMethod("getId");
        var set = record.getMethod("setId", String.class);
        return String.class.equals(get.getReturnType())
            && void.class.equals(set.getReturnType());
    } catch (NoSuchMethodException e) {
        return false;
    }
}
```

`hasPlatformIdMethods` keeps the call site in `TypeBuilder` a single-line
check and isolates the reflection inside `JooqCatalog`, which is already the
only class permitted to do jOOQ reflection (see the "Classification belongs at
the parse boundary" principle in [`rewrite-roadmap.md`](rewrite-roadmap.md)).

The narrower `findRecordClass` method is kept as the base primitive in case
future work needs the `Class<?>` itself (for example to reflect additional
record accessors). It mirrors the existing
[`findTableByRecordClass`](../graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/JooqCatalog.java)
method, forming a pair.

### Builder flow

Change `buildInputField` to return a sum type so it can surface an actionable
error when the fallback fails, rather than melting into a bare `Optional.empty()`
whose meaning is derived only from the caller's loop state:

```java
sealed interface InputFieldResolution {
    record Resolved(InputField field) implements InputFieldResolution {}
    record Unresolved(String fieldName, String reason) implements InputFieldResolution {}
}
```

Flow:

1. If `@reference` is set → resolve column via existing path
   (`ColumnReferenceField`), unchanged.
2. Else resolve column via `catalog.findColumn(tableName, columnName)`. If
   found → `ColumnField`, unchanged.
3. Else, if the field is an `ID` scalar, named `id` (or overridden to `id`),
   has no `@nodeId`, and `catalog.hasPlatformIdMethods(tableName)` is true →
   `PlatformIdField`.
4. Else → `Unresolved`. Reason string distinguishes:
   - Plain column miss: `"field '<name>' has no matching column in table
     '<table>'"` (with candidate hint appended by the caller).
   - `id: ID!` with no platformId methods: `"field 'id' has no matching
     column and no platformId methods (getId/setId) found on record class —
     use @nodeId for Relay IDs"`.

`buildTableInputType` is updated to preserve per-field `reason` strings when
aggregating into `UnclassifiedType`, so the composite error message names
each failure with its specific cause.

### Validator

`GraphitronSchemaValidator.validateField` already switches on every
`InputField` variant. Add one line to the switch:

```java
case no.sikt.graphitron.rewrite.model.InputField.PlatformIdField ignored -> {}
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
- `PlatformIdField`        → `record.setId(input.getId())`

Read-back (returning the newly written record through a query field) happens
through ordinary column projection and does not touch `PlatformIdField`.

Until the mutation generator lands, `PlatformIdField` is classified but not
consumed — the model carries the information so that once the generator is
written, no further classification work is required.

### Error quality

The targeted message `"field 'id' has no matching column and no platformId
methods (getId/setId) found on record class — use @nodeId for Relay IDs"`
steers users toward the forward path (`@nodeId`) while still identifying the
legacy mechanism they might have expected. It is produced only when both
conditions fail: column missing **and** record missing platformId methods.
When platformId methods exist, classification succeeds silently — the
legacy schema keeps working.

---

## Scope and non-goals

### In scope

- `id: ID!` (or `id: ID`) on `@table` input types, no `@nodeId`, no
  `@reference`, no `@field(name: ...)` override pointing to something other
  than `id`.
- The record-level `getId()` / `setId(String)` pair only.

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

| Step | What | Depends on |
|---|---|---|
| 1 | Add `JooqCatalog.findRecordClass(String)` + `hasPlatformIdMethods(String)` | Nothing |
| 2 | Add `InputField.PlatformIdField` variant to the sealed interface | Nothing |
| 3 | Change `TypeBuilder.buildInputField` to return an `InputFieldResolution` sum; update `buildTableInputType` to carry per-field reasons | Step 2 |
| 4 | Add platformId fallback branch in `buildInputField` using the new catalog method | Steps 1, 3 |
| 5 | Add exhaustive-switch case in `GraphitronSchemaValidator.validateField` | Step 2 |
| 6 | (Deferred — picked up with mutation generator) Emit `record.setId(input.getId())` for `PlatformIdField` in the input-binding section | Step 2 + mutation generator |

Steps 1–5 are the self-contained classification work. Step 6 happens
naturally when someone implements the mutation generator (see
[`rewrite-roadmap.md`](rewrite-roadmap.md), "Stubs to complete" item 4).

---

## Test strategy

### Builder / classification

- **Pipeline test** (new): SDL with `input Foo @table(name: "bar") { id: ID! }`
  against a jOOQ catalog whose `bar` record exposes `getId()`/`setId(String)`
  classifies the field as `PlatformIdField` — asserted via the resolved
  `GraphitronType.TableInputType.fields()` list.
- **Pipeline test** (new): same SDL against a catalog whose record does **not**
  expose those methods → `UnclassifiedType` with the exact failure-mode error
  string.
- **Pipeline test** (new): same SDL with `@nodeId` on the `id` field → should
  reach the existing `@nodeId` classification path; `PlatformIdField` is not
  produced.

### Validator

- **Structural test** (new, under `rewrite/validation/`): constructing an
  `InputField.PlatformIdField` and running the validator produces zero errors.

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

The classification pipeline test (above) is sufficient to verify the feature
in isolation; execution coverage is tied to mutation-generator readiness.

---

## Interaction with existing work

- **`@nodeId` / Relay path.** `NodeIdField` classification (on `ChildField`)
  is distinct from input-field classification. This plan only touches
  `InputField` and does not alter how Relay IDs are decoded or how
  `@nodeId` directives are read.
- **Argument resolution** ([argument-resolution.md](argument-resolution.md)).
  Argument classification (`ArgumentRef`) is orthogonal — `PlatformIdField`
  exists on **input types**, not **arguments**. When a mutation receives a
  `TableInputArg` (the argument-resolution variant for `@table` input types),
  its `fieldBindings` carry an `InputColumnBinding` per resolved column; the
  argument-resolution plan's `InputColumnBinding` stub should be widened
  into a sum that accepts `PlatformIdField` as well as column-bound fields,
  so the mutation generator can dispatch uniformly.
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
