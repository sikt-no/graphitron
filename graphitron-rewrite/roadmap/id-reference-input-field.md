---
title: "`IdReferenceField` input filter variant"
status: Spec
priority: 4
---

# `IdReferenceField`: Input Filter Field via `has*` Join Plan

> Classify the `[ID!] @reference(path: ...)` filter pattern (KjerneJooqGenerator's `has*` accessor emits). Currently mis-classified as `UnclassifiedType` because the rewrite treats `@field(name:)` as a column name, not a method-accessor suffix. Code generation is a follow-up.

## Overview

Add support for the `[ID!] @reference(path: ...)` filter input pattern. These fields
generate a `has<Name>s(...)` condition call (emitted by `KjerneJooqGenerator`) on the
jOOQ record at the terminal end of a FK join path. The rewrite currently tries to
resolve the `@field(name:)` value as a literal column in the terminal table, fails
(the column does not exist as a plain column — the `has*` method is a KjerneJooqGenerator
extension), and incorrectly classifies the entire input type as `UnclassifiedType`.

## Current State

The shared input-field classifier `BuildContext.classifyInputField` (`BuildContext.java:721`,
used by both `TypeBuilder` for `@table` inputs and `FieldBuilder` for plain inputs) has one
`@reference` branch at line 792:

```java
if (field.hasAppliedDirective(DIR_REFERENCE)) {
    var path = parsePath(field, name, resolvedTable.tableName(), null);
    if (path.hasError()) return new InputFieldResolution.Unresolved(name, null, path.errorMessage());
    return svc.resolveColumnForReference(columnName, path.elements(), resolvedTable.tableName())
        .<InputFieldResolution>map(col -> ...)
        .orElseGet(() -> new InputFieldResolution.Unresolved(name, columnName,
            "no column '" + columnName + "' reachable via @reference path"));
}
```

This branch handles all `@reference` fields identically: parse path, then look up the
`@field(name:)` value as a column in the terminal table. For `[ID!] @reference` fields,
the `@field(name:)` value is NOT a column — it is a method accessor suffix
(`TERMIN_ID` → `hasTerminIds`), so the column lookup always fails.

### What `KjerneJooqGenerator` emits

`KjerneJooqGenerator` is a custom jOOQ code generator used in the FS platform. It emits
two patterns relevant here:

| Pattern | Generator output | Rewrite variant |
|---|---|---|
| Composite node-key metadata | `__NODE_TYPE_ID` + `__NODE_KEY_COLUMNS` static fields on the record class | `InputField.NodeIdField` / `InputField.NodeIdReferenceField` |
| ID-set filter via joined table | `has<Name>(String)` / `has<Name>s(Collection<String>)` instance methods on the record class | **not yet supported** |

The catalog probes the first pattern via static-field reflection
(`JooqCatalog.nodeIdMetadata`, line 246). The second pattern requires a parallel
*method*-reflection probe; that probe does not yet exist (Phase 2 adds it).

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
- **Hint fix** — `TypeBuilder.buildTableInputType` (`TypeBuilder.java:555-560`) uses the
  source table's columns for "did you mean" suggestions even when the failure is a
  reference-path column lookup. Separate usability improvement, tracked independently.
- **Singular vs. list distinction** — the `list` boolean on `IdReferenceField` captures
  whether the field is `[ID!]` or `ID!` at model level; the exact pluralisation of the
  method name (`has<Name>` vs `has<Name>s`) is a code-generation concern.

## Key Discoveries

- The classifier shared between type-build and argument-classify passes is
  `BuildContext.classifyInputField` (`BuildContext.java:721`), not a method on
  `TypeBuilder`. It runs during the **first pass** of `TypeBuilder.buildTypes()` for
  `@table`-bound inputs; `ctx.types` is `null` at that point, so no type-level lookups
  are possible. The new branch needs only `parsePath`, `terminalTableSqlName`, and the
  catalog — none of which depend on `ctx.types`.
- `JooqCatalog.findRecordClass(tableSqlName)` (line 88) returns the generated jOOQ
  record class. The new method-reflection probe builds on it the same way
  `nodeIdMetadata` (line 246) builds on a different reflection target.
- The legacy probes BOTH capitalisation styles: `hasTERMIN_ID(s)` (upper, via
  `MethodMapping.asHas()`) and `hasTerminId(s)` (camel, via `MethodMapping.asCamelHas()`).
  The catalog probe must accept all four candidate names.
- The legacy classifier never gates on probe presence: `FetchDBMethodGenerator.generateHasForID`
  (line 649) falls through to `asHasCall` even when no method is found. This plan
  classifies optimistically (probe is advisory only) to preserve the legacy contract;
  see *Open Questions*.
- `InputField` is sealed. Adding a variant will cause compile errors at every exhaustive
  switch site (Phase 1 enumerates them), and `TypeFetcherGenerator.NOT_DISPATCHED_LEAVES`
  (`TypeFetcherGenerator.java:167`) must learn the new class or
  `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` fails.

## Implementation Approach

Closest existing variant is `InputField.NodeIdReferenceField` (`InputField.java:106`):

- `NodeIdReferenceField`: scalar `ID!` with `@nodeId(typeName:)` pointing at a NodeType
  reachable through `joinPath`. Catalog probe uses static fields
  (`__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS`).
- `IdReferenceField` (new): `ID!` or `[ID!]` with `@reference` (no `@nodeId`), FK path
  resolves to a **terminal** table, method probe (advisory) checks for `has<Name>(s)` on
  the terminal table's record class.

Both branches live in `BuildContext.classifyInputField`. The new branch sits between the
existing `@nodeId` branch (line 737) and the existing `@reference` branch (line 792).

---

## Phase 1 — Model: `InputField.IdReferenceField`

### Overview

Add the new sealed variant and update every site that switches on `InputField` leaves.
No logic changes yet — just the data carrier and stub arms.

### Changes

#### `model/InputField.java`

Update the `permits` clause and add the new record after `NodeIdReferenceField`:

```java
public sealed interface InputField extends GraphitronField
        permits InputField.ColumnField, InputField.ColumnReferenceField,
                InputField.NodeIdField, InputField.NodeIdReferenceField,
                InputField.IdReferenceField,
                InputField.NestingField {

    // ... existing variants ...

    /**
     * A filter field typed {@code ID!} or {@code [ID!]} with a {@code @reference} join path,
     * where the filter condition is expressed as a {@code has<MethodName>(s)} call on the
     * jOOQ record at the terminal end of the path. These methods are emitted by
     * {@code KjerneJooqGenerator} for tables that expose an ID-set predicate.
     *
     * <p>{@code methodName} is the raw accessor suffix taken from {@code @field(name:)},
     * or the GraphQL field name when {@code @field} is absent. For
     * {@code terminer: [ID!] @field(name:"TERMIN_ID")}, {@code methodName} is {@code "TERMIN_ID"};
     * code generation derives the four candidate names (`hasTERMIN_ID`, `hasTERMIN_IDs`,
     * `hasTerminId`, `hasTerminIds`) from it.
     */
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
}
```

The record carries no `ColumnRef` — like `NodeIdField`, the predicate resolves via a
*method* on the jOOQ record, not a column. Code generation derives all four candidate
method names from `methodName` directly.

#### Exhaustive-switch arms

`InputField` is sealed; the compiler will flag every site that switches over its leaves.
Add a no-op `case InputField.IdReferenceField ignored -> {}` arm at each site below;
Phase 3 will replace the validator arm with a real implementation.

- `GraphitronSchemaValidator.java:104-108` — switch in the input-field validator.
- `FieldBuilder.java:1262-1288` — switch in the condition-propagation walker.

#### Generator-coverage registration

`TypeFetcherGenerator.NOT_DISPATCHED_LEAVES` (`TypeFetcherGenerator.java:167`) is the
static set that records which leaves never reach the fetcher dispatch (input fields are
attached to input-object types and don't flow through `generateForType`).
`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` enforces that
every sealed leaf belongs to exactly one of four sets. Add `InputField.IdReferenceField.class`
to `NOT_DISPATCHED_LEAVES` alongside the other `InputField` entries.

### Success Criteria

- [ ] Project compiles (`mvn compile -pl graphitron-rewrite -Pquick`)
- [ ] `GeneratorCoverageTest` passes (variant registered in `NOT_DISPATCHED_LEAVES`)
- [ ] Existing tests pass (`mvn test -pl graphitron-rewrite -Pquick`)

---

## Phase 2 — Catalog: `JooqCatalog.hasIdSetPredicateMethod`

### Overview

Add a reflection-based method probe on the terminal table's record class. The probe is
**advisory only** — it checks whether `KjerneJooqGenerator` emitted an ID-set predicate
method for the column, but its result does not gate classification (see Phase 3 and
*Open Questions* for the rationale). It exists so the build can warn when the predicate
is missing and so unit tests can assert detection.

### Changes

#### `JooqCatalog.java` — new method near `findRecordClass` (line 88)

```java
/**
 * Returns {@code true} when the jOOQ record class for {@code tableSqlName} exposes any
 * public method whose name matches one of {@code candidateNames}. {@code KjerneJooqGenerator}
 * emits these for tables with an ID-set filter predicate (e.g. {@code hasTerminIds},
 * {@code hasTERMIN_IDs}). Returns {@code false} when the catalog is unavailable, the table
 * is not found, or no candidate matches.
 *
 * <p>Presence-only check; return type and parameter types are not validated. The legacy
 * uses the same approach via {@code searchTableForMethodWithName}.
 */
public boolean hasIdSetPredicateMethod(String tableSqlName, String... candidateNames) {
    return findRecordClass(tableSqlName)
        .map(cls -> recordHasIdSetPredicateMethod(cls, candidateNames))
        .orElse(false);
}

/** Package-private for direct unit testing against synthetic record classes. */
static boolean recordHasIdSetPredicateMethod(Class<?> recordClass, String... candidateNames) {
    var names = Set.of(candidateNames);
    for (var method : recordClass.getMethods()) {
        if (names.contains(method.getName())) return true;
    }
    return false;
}
```

Varargs lets the classifier pass all four candidate names (`hasTERMIN_ID`,
`hasTERMIN_IDs`, `hasTerminId`, `hasTerminIds`) in a single call.

### Success Criteria

- [ ] `mvn compile -pl graphitron-rewrite -Pquick`
- [ ] `mvn test -pl graphitron-rewrite -Pquick`

---

## Phase 3 — Classifier: `BuildContext.classifyInputField`

### Overview

Add a new branch in `BuildContext.classifyInputField` (`BuildContext.java:721`) that
intercepts `ID`-typed `@reference` fields *without* `@nodeId`. Place it between the
existing `@nodeId` branch (line 737) and the existing `@reference` branch (line 792)
so the new branch is disjoint from both.

The catalog probe is **advisory**: when it returns false but the FK path resolved, the
classifier still emits `IdReferenceField` and logs a build warning. This preserves
legacy behaviour (`FetchDBMethodGenerator.generateHasForID` falls through to `asHasCall`
unconditionally) and keeps classification testable against catalogs whose record classes
were not augmented with `has*` methods.

### Changes

#### `BuildContext.java` — new branch in `classifyInputField`, between line 791 and 792

```java
// IdReferenceField: ID!/[ID!] with @reference (and no @nodeId, which the branch above
// already handles). The filter condition resolves to a KjerneJooqGenerator has<Name>(s)
// method on the terminal table's record class, not a literal column. The probe is
// advisory; classification proceeds even on probe-miss (with a warning) to match legacy.
if ("ID".equals(typeName)
        && field.hasAppliedDirective(DIR_REFERENCE)
        && !field.hasAppliedDirective(DIR_NODE_ID)) {
    var path = parsePath(field, name, resolvedTable.tableName(), null);
    if (path.hasError()) {
        return new InputFieldResolution.Unresolved(name, null, path.errorMessage());
    }
    String terminalTable = svc.terminalTableSqlName(path.elements(), resolvedTable.tableName());
    if (terminalTable != null) {
        String[] candidates = idSetPredicateCandidates(columnName);
        if (!catalog.hasIdSetPredicateMethod(terminalTable, candidates)) {
            addWarning(new BuildWarning(
                "input field '" + parentTypeName + "." + name + "': no has-accessor among "
                + Arrays.toString(candidates) + " on jOOQ record for table '"
                + terminalTable + "' — generated code may not compile until the table"
                + " gains a KjerneJooqGenerator has<Name>(s) method.",
                locationOf(field)));
        }
    }
    return new InputFieldResolution.Resolved(new InputField.IdReferenceField(
        parentTypeName, name, locationOf(field), typeName, nonNull, list,
        path.elements(), columnName));
}
```

Notes:
- `parsePath` is the 4-arg form (`container, fieldName, startSqlTableName,
  targetSqlTableName`); pass `null` for the target, mirroring the existing `@reference`
  branch at line 793. Implicit single-FK inference does not apply to this branch.
- `terminalTableSqlName` is on `ServiceCatalog` (`ServiceCatalog.java:89`); it returns
  `null` when any path step is condition-only. In that case the probe is skipped — the
  classifier still emits `IdReferenceField` (legacy never failed there either).
- The `@nodeId` guard keeps the new branch disjoint from the `@nodeId` branch above
  even when `[ID!] @nodeId` is encountered (which the existing branch's `!list` check
  would otherwise drop into here).

#### Name-derivation helper (private, in `BuildContext`)

```java
// "TERMIN_ID" → ["hasTERMIN_ID", "hasTERMIN_IDs", "hasTerminId", "hasTerminIds"]
// Mirrors legacy MethodMapping.asHas() / asCamelHas(), each in singular and plural form.
private static String[] idSetPredicateCandidates(String accessorSuffix) {
    String upper = "has" + accessorSuffix;
    String camel = "has" + capitalize(toCamelCase(accessorSuffix));
    return new String[] { upper, upper + "s", camel, camel + "s" };
}
```

Reuse the existing `toCamelCase` / `capitalize` helpers if they're already on the
classpath; otherwise inline a small `UPPER_UNDERSCORE → LOWER_CAMEL` conversion.

### Success Criteria

- [ ] `mvn compile -pl graphitron-rewrite -Pquick`
- [ ] `mvn test -pl graphitron-rewrite -Pquick`
- [ ] No `UnclassifiedType` for inputs containing `[ID!] @reference` filter fields.

---

## Phase 4 — Tests

### Overview

Add pipeline-level cases in `GraphitronSchemaBuilderTest.TableInputTypeCase` (Sakila
catalog, exercises FK resolution and the warn-on-missing-method path) and a unit test
for the reflection probe itself.

Because the probe is advisory (Phase 3), classification succeeds against Sakila even
though `LanguageRecord` has no `has*` method — the warning fires, the variant is still
emitted. No fixture-generator extension is required for this milestone; a future test
that wants to assert the no-warning path can add a record class with a `has*` method
via `NodeIdFixtureGenerator`
(`graphitron-fixtures-codegen/.../NodeIdFixtureGenerator.java`).

### Changes

#### `GraphitronSchemaBuilderTest.java` — new `TableInputTypeCase` enum constants

**Case 1 — happy path (FK resolves; advisory probe may miss but classification succeeds)**:

```java
ID_REFERENCE_FIELD(
    "ID-list field with @reference → IdReferenceField with resolved join path",
    """
    type Film @table(name: "film") { title: String }
    input FilmFilterInput @table(name: "film") {
      languageIds: [ID!] @field(name: "LANGUAGE_ID")
                         @reference(path: [{key: "film_language_id_fkey"}])
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
    }) {
    @Override public Set<Class<?>> variants() { return Set.of(InputField.IdReferenceField.class); }
},
```

The `variants()` override registers the new leaf with `VariantCoverageTest` (every
sealed leaf must have at least one classification case or a `NO_CASE_REQUIRED` entry).

**Case 2 — bad reference path → UnclassifiedType**:

```java
ID_REFERENCE_FIELD_BAD_PATH(
    "ID-list field with @reference to nonexistent FK → UnclassifiedType",
    """
    type Film @table(name: "film") { title: String }
    input FilmFilterInput @table(name: "film") {
      fooIds: [ID!] @field(name: "FOO_ID") @reference(path: [{key: "no_such_fkey"}])
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
                      @reference(path: [{key: "film_language_id_fkey"}])
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

#### New `JooqCatalogIdSetPredicateTest.java` (unit test for the catalog probe)

Place under `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/`,
alongside `JooqCatalogNodeIdMetadataTest`.

```java
class JooqCatalogIdSetPredicateTest {
    @Test void detectsUpperCaseHasMethod() {
        assertTrue(JooqCatalog.recordHasIdSetPredicateMethod(
            StubWithUpperCase.class,
            "hasTERMIN_ID", "hasTERMIN_IDs", "hasTerminId", "hasTerminIds"));
    }
    @Test void detectsCamelCaseHasMethod() {
        assertTrue(JooqCatalog.recordHasIdSetPredicateMethod(
            StubWithCamelCase.class,
            "hasTERMIN_ID", "hasTERMIN_IDs", "hasTerminId", "hasTerminIds"));
    }
    @Test void returnsFalseWhenAbsent() {
        assertFalse(JooqCatalog.recordHasIdSetPredicateMethod(
            Object.class, "hasTerminId", "hasTerminIds"));
    }

    static class StubWithUpperCase {
        public void hasTERMIN_IDs(java.util.Collection<String> ids) {}
    }
    static class StubWithCamelCase {
        public void hasTerminIds(java.util.Collection<String> ids) {}
    }
}
```

### Success Criteria

- [ ] All new `TableInputTypeCase` constants pass
- [ ] `JooqCatalogIdSetPredicateTest` passes
- [ ] `VariantCoverageTest.everySealedLeafHasAClassificationCase` passes
  (case 1's `variants()` override registers the new leaf)
- [ ] `mvn test -pl graphitron-rewrite -Pquick` — zero failures

---

## Testing Strategy

### Unit tests
- `JooqCatalogIdSetPredicateTest` — probe detects upper-case, camel-case, and plural
  variants; returns false when absent.

### Pipeline tests
- `GraphitronSchemaBuilderTest` — three new cases: happy path, bad FK path, scalar form.

### Manual verification
- Apply the built snapshot to the FS platform `sis-graphql-spec` and confirm the
  `StudentStudieprogramISemesterFilterInput` validation error is gone.

---

## Open Questions

**Resolved: probe is advisory.** The catalog method probe returns a boolean that the
classifier uses only to emit a build warning; it does not gate classification. Rationale:
the legacy (`FetchDBMethodGenerator.generateHasForID`, line 649) emits the `has*` call
unconditionally, never failing on absence. Strict probing would be a behaviour change
and would require extending `NodeIdFixtureGenerator` to emit `has*` methods on a fixture
table before any happy-path test could pass, which is out of scope for the
classification milestone. Revisit if/when code generation lands and we want a
build-time guarantee that the predicate exists.

## References

- Legacy bypass: `TableValidator.java:271` — `field.isID() && !shouldMakeNodeStrategy()`
- Legacy method derivation: `FetchDBMethodGenerator.java:639-649` — `generateHasForID`
  (probes upper- and camelCase forms but falls through to `asHasCall` unconditionally)
- Legacy name helpers: `MethodMapping.asHas()` / `asCamelHas()` —
  `graphitron-codegen-parent/.../mapping/MethodMapping.java:94, 101`
- Closest rewrite analogue: `InputField.NodeIdReferenceField` — `InputField.java:106`
  (the `@nodeId @reference` companion); catalog probe via
  `JooqCatalog.nodeIdMetadata` — `JooqCatalog.java:246`
- Record-class lookup: `JooqCatalog.findRecordClass` — `JooqCatalog.java:88`
- Existing `@reference` branch the new code sits next to:
  `BuildContext.classifyInputField` — `BuildContext.java:792`
