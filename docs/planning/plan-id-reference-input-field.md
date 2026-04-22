# `IdReferenceField` — Input Filter Field via `has*` Join Plan

> **Status:** Spec
>
> Classify the `[ID!] @reference(path: ...)` filter pattern (KjerneJooqGenerator's `has*` accessor emits). Currently mis-classified as `UnclassifiedType` because the rewrite treats `@field(name:)` as a column name, not a method-accessor suffix. Code generation is a follow-up.

## Overview

Add support for the `[ID!] @reference(path: ...)` filter input pattern. These fields
generate a `has<Name>s(...)` condition call (emitted by `KjerneJooqGenerator`) on the
jOOQ record at the terminal end of a FK join path. The rewrite currently tries to
resolve the `@field(name:)` value as a literal column in the terminal table, fails
(the column does not exist as a plain column — the `has*` method is a KjerneJooqGenerator
extension), and incorrectly classifies the entire input type as `UnclassifiedType`.

## Current State

`TypeBuilder.buildInputField` (lines 468–475) has one `@reference` branch:

```java
if (field.hasAppliedDirective(DIR_REFERENCE)) {
    var path = ctx.parsePath(...);
    if (path.hasError()) return Unresolved(...);
    return svc.resolveColumnForReference(columnName, ...)
        .map(col -> Resolved(new ColumnReferenceField(..., col, path.elements())))
        .orElseGet(() -> Unresolved(name, columnName, "no column '" + columnName + "' reachable via @reference path"));
}
```

This branch handles all `@reference` fields identically: parse path, then look up the
`@field(name:)` value as a column in the terminal table. For `[ID!] @reference` fields,
the `@field(name:)` value is NOT a column — it is a method accessor suffix
(`TERMIN_ID` → `hasTerminIds`), so the column lookup always fails.

### What `KjerneJooqGenerator` emits

`KjerneJooqGenerator` is a custom jOOQ code generator used in the FS platform. It emits
two patterns already known to the rewrite:

| Pattern | Methods | Rewrite variant |
|---|---|---|
| Legacy composite platform key | `getId()` / `setId()` | `InputField.PlatformIdField` |
| ID-set filter via joined table | `has<Name>(String)` / `has<Name>s(Collection<String>)` | **not yet supported** |

For a field like `terminer: [ID!] @field(name: "TERMIN_ID") @reference(path: {table: "SEMESTERREGISTRERINGSTERMIN"})`:
- the join path is resolved to `SEMESTERREGISTRERINGSTERMIN`
- `TERMIN_ID` becomes the method accessor suffix
- the generated filter condition is `joinedAlias.hasTerminIds(input)`

## Desired End State

`StudentStudieprogramISemesterFilterInput` (and any other input type using this pattern)
classifies successfully as a `TableInputType` with one `IdReferenceField`. No
`UnclassifiedType` error is emitted. The classification carries the resolved join path
and the `has*` method name, ready for code generation.

### Verification

1. Build succeeds on the FS platform's `sis-graphql-spec` without the `TERMIN_ID` error.
2. New `GraphitronSchemaBuilderTest.TableInputTypeCase` enum cases pass.
3. Existing test suite has no regressions.

## What We're NOT Doing

- **Code generation** — `IdReferenceField` will not generate any Java code yet; that is
  a separate follow-up.
- **NodeType validation** — we will NOT require that the terminal table's GraphQL type is
  a `Node`. `ctx.types` is `null` during first-pass classification and a reverse lookup
  (table SQL name → `NodeType`) does not exist. This can be added as a second-pass
  enrichment later.
- **Hint fix** — `TypeBuilder:379` uses the source table's columns for "did you mean"
  suggestions even when the failure is a reference-path column lookup. This is a
  separate usability improvement, tracked independently.
- **Singular vs. list distinction** — the `list` boolean on `IdReferenceField` captures
  whether the field is `[ID!]` or `ID!` at model level; the exact pluralisation of the
  method name (`has<Name>` vs `has<Name>s`) is a code-generation concern.

## Key Discoveries

- `buildInputField` runs during the **first pass** of `TypeBuilder.buildTypes()`;
  `ctx.types` is `null` at that point — no type-level lookups are possible
  (`TypeBuilder.java:97–110`).
- `JooqCatalog.hasPlatformIdAccessors` (line 118) uses reflection on the jOOQ record
  class. The same approach works for `has<Name>(s)` methods; the method to add is
  `JooqCatalog.hasIdHasAccessors(tableSqlName, singularName, pluralName)`.
- The legacy checks BOTH capitalisation styles: `hasTERMIN_IDs` (upper) and
  `hasTerminIds` (camel), via `mapping.asHas()` and `mapping.asCamelHas()`. The catalog
  check must look for both.
- `InputField` is sealed — adding a variant will cause compile errors at every exhaustive
  switch, which is the desired behaviour (forces all consumers to handle the new case).

## Implementation Approach

Closely parallel to `PlatformIdField`:

- `PlatformIdField`: type is `ID!` (scalar, non-list), no `@reference`, method
  checked on the **source** table.
- `IdReferenceField`: type is `ID!` or `[ID!]`, has `@reference`, FK path resolves to a
  **terminal** table, method checked on the **terminal** table.

---

## Phase 1 — Model: `InputField.IdReferenceField`

### Overview

Add the new sealed variant. No logic changes yet — just the data carrier.

### Changes

#### `model/InputField.java`

Add after `ColumnReferenceField` (around line 60):

```java
/**
 * A filter field typed {@code ID!} or {@code [ID!]} with a {@code @reference} join path,
 * where the filter condition is expressed as a {@code has<MethodName>(s)} call on the
 * jOOQ record at the terminal end of the path. These methods are emitted by
 * {@code KjerneJooqGenerator} for tables that expose an ID-set predicate.
 *
 * <p>{@code methodName} is the raw accessor suffix taken from {@code @field(name:)},
 * or the GraphQL field name when {@code @field} is absent. For
 * {@code terminer: [ID!] @field(name:"TERMIN_ID")}, {@code methodName} is {@code "TERMIN_ID"};
 * code generation will derive {@code hasTerminIds} from it.
 */
record IdReferenceField(
    String parentTypeName,
    String name,
    SourceLocation location,
    String typeName,
    boolean nonNull,
    boolean list,
    ColumnRef column,     // the resolved column in the terminal table (see note below)
    List<JoinStep> joinPath,
    String methodName
) implements InputField {}
```

> **Note on `column`**: Keep a `ColumnRef` field for structural parity with
> `ColumnReferenceField`. Populate it with the result of the `has*` accessor probe
> (see Phase 2). If the accessor probe is skipped (catalog unavailable), use a minimal
> sentinel `ColumnRef(methodName, accessorSuffix, null)` so code generation always has
> a non-null `column`.

Actually, reconsidering: `PlatformIdField` carries no `ColumnRef`. `IdReferenceField`
also resolves via a method, not a column. Omit `column` to stay parallel. Code
generation will derive method names from `methodName` directly.

**Revised record:**

```java
record IdReferenceField(
    String parentTypeName,
    String name,
    SourceLocation location,
    String typeName,
    boolean nonNull,
    boolean list,
    List<JoinStep> joinPath,
    String methodName
) implements InputField {}
```

#### Exhaustive-switch sites

After adding the variant, any exhaustive `switch` over `InputField` will fail to compile.
Find all such sites with:

```
grep -r "instanceof InputField\|case.*InputField\|InputField\." \
  graphitron-rewrite/src/main/java --include="*.java" -l
```

Add a no-op or `throw new UnsupportedOperationException` arm at each site for now;
Phase 3 will replace them with real handling.

### Success Criteria

- [ ] Project compiles (`mvn compile -pl graphitron-rewrite -Pquick`)
- [ ] Existing tests pass (`mvn test -pl graphitron-rewrite -Pquick`)

---

## Phase 2 — Catalog: `JooqCatalog.hasIdHasAccessors`

### Overview

Add reflection-based method probe for `has<Name>` / `has<Name>s` on a terminal table's
record class, parallel to `hasPlatformIdAccessors`.

### Changes

#### `JooqCatalog.java` — new method after `hasPlatformIdAccessors` (line 122)

```java
/**
 * Returns {@code true} when the jOOQ record class for {@code tableSqlName} exposes
 * {@code public ... <singularName>(String)} OR {@code public ... <pluralName>(Collection)}.
 * These are emitted by {@code KjerneJooqGenerator} for tables with an ID-set filter
 * predicate. Returns {@code false} when the catalog is unavailable, the table is not
 * found, or neither method is present.
 */
public boolean hasIdHasAccessors(String tableSqlName, String singularName, String pluralName) {
    return findRecordClass(tableSqlName)
        .map(cls -> recordHasIdHasAccessors(cls, singularName, pluralName))
        .orElse(false);
}

/** Package-private for direct unit testing against synthetic record classes. */
static boolean recordHasIdHasAccessors(Class<?> record, String singularName, String pluralName) {
    for (var method : record.getMethods()) {
        String n = method.getName();
        if (n.equals(singularName) || n.equals(pluralName)) return true;
    }
    return false;
}
```

The method name variants to derive and probe are determined by the classifier (Phase 3).
The probe does not check return type or parameter types — presence is sufficient, just
as the legacy used `searchTableForMethodWithName` without type checking.

### Success Criteria

- [ ] `mvn compile -pl graphitron-rewrite -Pquick`
- [ ] `mvn test -pl graphitron-rewrite -Pquick`

---

## Phase 3 — Classifier: `TypeBuilder.buildInputField`

### Overview

Add a new branch that intercepts `typeName.equals("ID") && hasAppliedDirective(DIR_REFERENCE)`
**before** the existing `@reference` branch. This ensures `[ID!]` reference fields never
reach `resolveColumnForReference`.

### Changes

#### `TypeBuilder.java` — inside `buildInputField`, before line 468

```java
// IdReferenceField: ID!/[ID!] with @reference — filter condition uses a KjerneJooqGenerator
// has<Name>(s) method on the terminal table record, not a literal column.
if (typeName.equals("ID") && field.hasAppliedDirective(DIR_REFERENCE)) {
    var path = ctx.parsePath(field, name, resolvedTable.tableName());
    if (path.hasError()) return new InputFieldResolution.Unresolved(name, null, path.errorMessage());

    String terminalTable = svc.terminalTableSqlNameForReference(path.elements(), resolvedTable);
    String singular = deriveHasSingular(columnName);   // e.g. "TERMIN_ID" → "hasTerminId" + "hasTERMIN_ID"
    String plural   = deriveHasPlural(columnName);     // e.g. "TERMIN_ID" → "hasTerminIds" + "hasTERMIN_IDs"

    if (terminalTable != null && !ctx.catalog.hasIdHasAccessors(terminalTable, singular, plural)) {
        return new InputFieldResolution.Unresolved(name, null,
            "no has-accessor '" + singular + "' or '" + plural
            + "' found on jOOQ record for table '" + terminalTable + "'");
    }
    return new InputFieldResolution.Resolved(
        new InputField.IdReferenceField(
            parentTypeName, name, locationOf(field), typeName, nonNull, list,
            path.elements(), columnName));
}
```

#### Name derivation helpers (private, in `TypeBuilder`)

The legacy uses `MethodMapping.asHas()` (returns `hasTERMIN_ID`, uppercase after "has")
and `MethodMapping.asCamelHas()` (returns `hasTerminId`, camelCase). For the rewrite,
add two private static helpers or inline the derivation:

```java
// "TERMIN_ID" → "hasTERMIN_ID" and "hasTerminId"
private static String hasUpperName(String accessorSuffix) {
    return "has" + accessorSuffix;  // e.g. hasTERMIN_ID
}

private static String hasCamelName(String accessorSuffix) {
    // convert SQL_UPPER_CASE to camelCase, then prefix "has"
    // "TERMIN_ID" → "terminId" → "hasTerminId"
    String camel = CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, accessorSuffix);
    return "has" + Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
}
```

And for plural (append "s"):
```java
private static String hasUpperNamePlural(String s) { return hasUpperName(s) + "s"; }
private static String hasCamelNamePlural(String s)  { return hasCamelName(s) + "s"; }
```

Pass all four variants to `hasIdHasAccessors`, or adjust the probe to check all four.

> **Fallback when `terminalTable` is null** (condition-only path element — no FK
> metadata available): skip the accessor probe and classify optimistically, same as the
> legacy did when `shouldMakeNodeStrategy()` was false.

### Success Criteria

- [ ] `mvn compile -pl graphitron-rewrite -Pquick`
- [ ] `mvn test -pl graphitron-rewrite -Pquick`

---

## Phase 4 — Tests

### Overview

Add pipeline-level tests in `GraphitronSchemaBuilderTest.TableInputTypeCase` and a
catalog unit test in a new `IdHasAccessorsTest`.

### Changes

#### `GraphitronSchemaBuilderTest.java` — new `TableInputTypeCase` enum constants

The test catalog uses Sakila tables (`film`, `language`, `customer`, etc.). Verify
that `film_language_id_fkey` exists and that the language record class (if any) has
a `has*` method, OR use mock stubs. If no suitable jOOQ record class has the required
method, use `catalog unavailable` mode (the classifier falls back to optimistic
classification when catalog returns false for an unknown table).

**Case 1 — happy path (catalog probe succeeds or catalog unavailable)**:

```java
ID_REFERENCE_FIELD(
    "ID-list field with @reference → IdReferenceField, no error",
    """
    type Film @table(name: "film") { title: String }
    input FilmFilterInput @table(name: "film") {
      languageIds: [ID!] @field(name: "LANGUAGE_ID")
                         @reference(path: {key: "film_language_id_fkey"})
    }
    type Query { film: Film }
    """,
    schema -> {
        var tit = (TableInputType) schema.type("FilmFilterInput");
        var field = tit.inputFields().stream()
            .filter(f -> f instanceof InputField.IdReferenceField)
            .map(f -> (InputField.IdReferenceField) f)
            .findFirst().orElseThrow();
        assertThat(field.name()).isEqualTo("languageIds");
        assertThat(field.list()).isTrue();
        assertThat(field.methodName()).isEqualTo("LANGUAGE_ID");
        assertThat(field.joinPath()).hasSize(1);
    }),
```

**Case 2 — bad reference path → UnclassifiedType**:

```java
ID_REFERENCE_FIELD_BAD_PATH(
    "ID-list field with @reference to nonexistent FK → UnclassifiedType",
    """
    type Film @table(name: "film") { title: String }
    input FilmFilterInput @table(name: "film") {
      fooIds: [ID!] @field(name: "FOO_ID") @reference(path: {key: "no_such_fkey"})
    }
    type Query { film: Film }
    """,
    schema -> assertThat(schema.type("FilmFilterInput")).isInstanceOf(UnclassifiedType.class)),
```

**Case 3 — scalar `ID!` (non-list) → IdReferenceField**:

```java
ID_REFERENCE_SCALAR(
    "scalar ID! with @reference → IdReferenceField with list=false",
    """
    type Film @table(name: "film") { title: String }
    input FilmFilterInput @table(name: "film") {
      languageId: ID! @field(name: "LANGUAGE_ID")
                      @reference(path: {key: "film_language_id_fkey"})
    }
    type Query { film: Film }
    """,
    schema -> {
        var tit = (TableInputType) schema.type("FilmFilterInput");
        var field = tit.inputFields().stream()
            .filter(f -> f instanceof InputField.IdReferenceField irf && !irf.list())
            .findFirst().orElseThrow();
        assertThat(field).isInstanceOf(InputField.IdReferenceField.class);
    }),
```

#### New `JooqCatalogIdHasTest.java` (unit test for the catalog probe)

```java
class JooqCatalogIdHasTest {
    @Test void detectsUpperCaseHasMethod() {
        assertTrue(JooqCatalog.recordHasIdHasAccessors(
            StubWithHasTERMIN_IDs.class, "hasTERMIN_ID", "hasTERMIN_IDs"));
    }
    @Test void detectsCamelCaseHasMethod() {
        assertTrue(JooqCatalog.recordHasIdHasAccessors(
            StubWithHasTerminIds.class, "hasTerminId", "hasTerminIds"));
    }
    @Test void returnsFalseWhenAbsent() {
        assertFalse(JooqCatalog.recordHasIdHasAccessors(
            Object.class, "hasTerminId", "hasTerminIds"));
    }

    static class StubWithHasTERMIN_IDs {
        public void hasTERMIN_IDs(java.util.Collection<String> ids) {}
    }
    static class StubWithHasTerminIds {
        public void hasTerminIds(java.util.Collection<String> ids) {}
    }
}
```

### Success Criteria

- [ ] All new `TableInputTypeCase` constants pass
- [ ] `JooqCatalogIdHasTest` passes
- [ ] `mvn test -pl graphitron-rewrite -Pquick` — zero failures

---

## Testing Strategy

### Unit tests
- `JooqCatalogIdHasTest` — probe detects upper-case, camel-case, and plural variants;
  returns false when absent.

### Pipeline tests
- `GraphitronSchemaBuilderTest` — three new cases: happy path, bad FK path, scalar form.

### Manual verification
- Apply the built snapshot to the FS platform `sis-graphql-spec` and confirm the
  `StudentStudieprogramISemesterFilterInput` validation error is gone.

---

## Open Questions

None. All decisions made.

## References

- Legacy bypass: `TableValidator.java:271` — `field.isID() && !shouldMakeNodeStrategy()`
- Legacy method derivation: `FetchDBMethodGenerator.java:839–843` — `generateHasForID`
- Parallel variant: `InputField.PlatformIdField` — `TypeBuilder.java:523–538`
- Catalog probe pattern: `JooqCatalog.hasPlatformIdAccessors` — `JooqCatalog.java:118`
- Stub examples: `codereferences/dummyreferences/PlatformIdRecord.java`,
  `PersonIdRecord.java`
