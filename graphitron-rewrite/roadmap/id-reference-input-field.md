---
title: "`IdReferenceField` input filter variant"
status: Spec
priority: 4
---

# `IdReferenceField`: Input Filter Field via `has*` Accessor

> Classify the `[ID!] @nodeId(typeName: T)` filter pattern that resolves to a
> KjerneJooqGenerator `has*` accessor on the input's resolved table. The FK is inferred
> from `typeName` when unique, or pinned by `@reference(path: [{key: K}])` when
> ambiguous. A directive-synthesis shim translates the legacy
> `[ID!] @field(name: "X_ID")` SDL into the canonical form so existing schemas
> classify without rewriting. Currently all three forms mis-classify the input type as
> `UnclassifiedType` because the rewrite treats `@field(name:)` as a column name,
> not a method-accessor suffix. Code generation is a follow-up.

## Overview

Add one `InputField.IdReferenceField` variant for `[ID!]` (and scalar `ID!`) filter
fields whose predicate is emitted by `KjerneJooqGenerator` as a `has<Name>(s)` method
on the input's resolved-table record class. There is a single classification path; the
SDL surface area is two equivalent forms plus a synthesis shim:

1. **Canonical form, FK-inferred**: `[ID!] @nodeId(typeName: "Studieprogram")`. The FK
   constraint is inferred by walking jOOQ FK metadata from the input's resolved table
   to the table backing `Studieprogram`. Unique-FK case only; ambiguous → fail with a
   "declare `@reference` to disambiguate" diagnostic.
2. **Canonical form, FK-explicit**: `[ID!] @nodeId(typeName: "Studieprogram") @reference(path: [{key: "STUDIERETT_STUDIEPROGRAM_FK"}])`.
   The `key` element names the FK constraint directly. Required when multiple FKs from
   the input's table reach the target type's table.
3. **Synthesis shim, legacy form**: `[ID!] @field(name: "STUDIEPROGRAM_ID")` with
   neither `@nodeId` nor `@reference`. On column-lookup miss, the classifier reverse-maps
   the `@field(name:)` value through `KjerneJooqGenerator.getQualifier` to find the FK
   whose qualifier matches, then synthesizes the canonical form (typeName from the FK's
   target GraphQL type, key from the FK constraint name). Per-site WARN fires; ships
   with a retirement roadmap item from day one. Modelled on the existing scalar-`ID`
   `NodeIdField` synthesis shim (`BuildContext.java:857-869`).

All three forms produce the same `IdReferenceField` shape and emit the same
`tableAlias.has<Name>s(input)` SQL at code-generation time. The rewrite currently tries
to resolve the `@field(name:)` value as a literal column on the resolved table, fails
(the column does not exist as a plain column; the `has*` method is a KjerneJooqGenerator
extension), and incorrectly classifies the entire input type as `UnclassifiedType`.

## Current State

Three branches in `BuildContext.classifyInputField` (`BuildContext.java:721`, used by
both `TypeBuilder` for `@table` inputs and `FieldBuilder` for plain inputs) shape the
relevant behaviour today:

1. `@nodeId` branch (line 737): gated on `"ID".equals(typeName) && !list`. Scalar
   `ID @nodeId(typeName: T)` resolves to `NodeIdReferenceField`; list types are
   explicitly rejected (`NodeIdPipelineTest.java:367` asserts `[ID!]! @nodeId` →
   `UnclassifiedType`). The new variant relaxes this gate for `[ID!] @nodeId`.
2. `@reference` branch (line 792): parses the path, then looks up `@field(name:)` as a
   column in the terminal table. The `@field(name:)` value is NOT a column for
   `[ID!]`-shaped filters; it is a method accessor suffix
   (`STUDIEPROGRAM_ID` → `hasStudieprogramIds`), so the column lookup always fails.
3. Column-miss tail (line 871): when neither directive matches and the column lookup
   on the resolved table misses, the field becomes `Unresolved`. The legacy SDL
   `[ID!] @field(name: "X_ID")` lands here today and propagates as
   `UnclassifiedType` on the containing input. The synthesis shim hooks before this
   tail.

The existing scalar shim at lines 857-869 (bare `ID` on a `@table` whose record class
carries `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` synthesizes `NodeIdField` with a
per-site WARN) is the structural template for the new shim.

### What `KjerneJooqGenerator` emits

`KjerneJooqGenerator` is a custom jOOQ code generator used in the FS platform. It emits
two patterns relevant here:

| Pattern | Generator output | Rewrite variant |
|---|---|---|
| Composite node-key metadata | `__NODE_TYPE_ID` + `__NODE_KEY_COLUMNS` static fields on the record class | `InputField.NodeIdField` / `InputField.NodeIdReferenceField` |
| ID-set filter via foreign key | `has<Name>(String)` / `has<Name>s(Collection<String>)` instance methods on the record class of the FK *source* table | `InputField.IdReferenceField` (this spec) |

The catalog probes the first pattern via static-field reflection
(`JooqCatalog.nodeIdMetadata`, line 246). The second pattern requires a parallel
*method*-reflection probe; that probe does not yet exist (Phase 2 adds it).

The `<Name>` token in the second pattern is `KjerneJooqGenerator.getQualifier(fk)`, a
function of the FK's column structure. For an FK from `STUDIERETT` to `STUDIEPROGRAM`
with no role-distinguishing source-column prefix, the qualifier is `STUDIEPROGRAM_ID`
and the methods emitted on `StudierettRecord` are `setStudieprogramId`,
`getStudieprogramId`, `hasStudieprogramId`, `hasStudieprogramIds`. When multiple FKs
between the same source and target exist, `generateRolePrefix(fk)` derives a role
prefix from the source columns to disambiguate (e.g. `REGISTRAR_STUDIEPROGRAM_ID`).
The qualifier↔FK mapping is therefore 1:1 on a given source table; if it weren't, the
generated record class would have duplicate method names and fail to compile upstream.

For a field like `studieprogrammer: [ID!] @nodeId(typeName: "Studieprogram")` on
`input ... @table(name: "STUDIERETT")`:
- the FK from `STUDIERETT` to the table backing `Studieprogram` is inferred (or pinned
  by `@reference(path: [{key: ...}])` when ambiguous)
- the qualifier is computed via `getQualifier(fk)`; the method name on
  `StudierettRecord` is `hasStudieprogramIds`
- the generated filter condition is `studierettAlias.hasStudieprogramIds(input)`

## Desired End State

All three SDL forms classify successfully as a `TableInputType` containing
`IdReferenceField` leaves. No `UnclassifiedType` is emitted. Each leaf carries the
target type name, the resolved FK (target table + key), and the predicate method name,
ready for code generation. The shim arm logs a per-site WARN naming the legacy field;
when it is removed (separate roadmap item), the WARN becomes a terminal classifier
error and consumers must have migrated to one of the canonical forms.

### Verification

1. Build succeeds on the FS platform's `sis-graphql-spec` without the
   `STUDIEPROGRAM_ID` / `TERMIN_ID` errors on filter inputs whose predicate is
   single-hop from the input's resolved table.
2. New `GraphitronSchemaBuilderTest.TableInputTypeCase` enum cases pass for all three
   forms (FK-inferred, FK-explicit, shim-synthesized).
3. Existing test suite passes, including a relaxed/split version of
   `NodeIdPipelineTest.java:367` that allows `[ID!] @nodeId` while keeping list-typed
   `@nodeId` failures for fields that don't classify as `IdReferenceField`.

## What We're NOT Doing

- **Code generation** — `IdReferenceField` will not generate any Java code yet; that is
  a separate follow-up.
- **Multi-hop FK paths** — only single-hop FKs out of the input's resolved table are
  in scope. A filter whose predicate method lives on a *joined* table (i.e. the FK is
  not on the input's resolved table itself) is rejected with a diagnostic that points
  the author at multi-hop `@reference(path:)` chains. Multi-hop is a separate roadmap
  item if it ever materializes; the current sis errors with "not reachable via
  @reference path" wording fall in this bucket and are addressed by explicit
  `@reference` declarations, not by this spec.
- **`@field(name:)` parsing in the canonical forms** — when `@nodeId` is declared, the
  method name is derived from the FK qualifier (`getQualifier(fk)`), not from
  `@field(name:)`. Authors are free to leave `@field(name:)` off the canonical forms;
  if present, it is informational and ignored by classification.
- **Locale- or pluralisation-aware field-name inference** — the bare-field-name shim
  upper-snake-converts the GraphQL field name and looks up FK qualifiers by exact
  match (with a single trailing-`s` strip for plural forms like `languageIds`). It
  does NOT do general English/Norwegian/etc. plural normalisation. A field named
  `studieprogrammer: [ID!]` (Norwegian plural of `studieprogram`) will NOT shim
  because `STUDIEPROGRAMMER` does not match the FK qualifier `STUDIEPROGRAM_ID`. The
  author must declare `@field(name: "STUDIEPROGRAM_ID")` or `@nodeId(typeName:)`
  explicitly. The bare-field-name path covers the well-named subset; it is not a
  universal escape hatch.
- **Hint fix** — `TypeBuilder.buildTableInputType` (`TypeBuilder.java:555-560`) uses the
  source table's columns for "did you mean" suggestions even when the failure is on a
  reference-path or qualifier match. Separate usability improvement, tracked
  independently.
- **Singular vs. list distinction at the model layer** — the `list` boolean on
  `IdReferenceField` captures whether the field is `[ID!]` or `ID!`; the exact
  pluralisation of the method name (`has<Name>` vs `has<Name>s`) is a code-generation
  concern.

## Key Discoveries

- The classifier shared between type-build and argument-classify passes is
  `BuildContext.classifyInputField` (`BuildContext.java:721`), not a method on
  `TypeBuilder`. It runs during the **first pass** of `TypeBuilder.buildTypes()` for
  `@table`-bound inputs; `ctx.types` is `null` at that point, so no type-level lookups
  are possible. Phase 3 reads `@nodeId(typeName:)` via `schema.getType(...)` directly
  (the same approach the existing `@nodeId` branch uses at `BuildContext.java:740-742`)
  and never depends on `ctx.types`.
- `JooqCatalog.findRecordClass(tableSqlName)` (line 88) returns the generated jOOQ
  record class. The new method-reflection probe builds on it the same way
  `nodeIdMetadata` (line 246) builds on a different reflection target.
- The qualifier↔FK mapping is 1:1 on a given source table by construction:
  `KjerneJooqGenerator.getQualifier(fk)` derives a qualifier from the FK's columns plus
  a role prefix from `generateRolePrefix(fk)`; if two FKs collided on the same
  qualifier the generated record class would have duplicate methods and fail to compile
  upstream. The classifier can therefore reverse-map a qualifier (whether from a
  schema author's `@field(name:)` or from internal recomputation) to a unique FK.
- The legacy probes BOTH capitalisation styles: `hasTERMIN_ID(s)` (upper, via
  `MethodMapping.asHas()`) and `hasTerminId(s)` (camel, via `MethodMapping.asCamelHas()`).
  The catalog probe must accept all four candidate names. With the canonical-form
  pipeline computing the qualifier from FK metadata the candidate set collapses, but
  the four-name probe is still the simplest contract for the shim arm and unit tests.
- The legacy classifier never gates on probe presence: `FetchDBMethodGenerator.generateHasForID`
  (line 649) falls through to `asHasCall` even when no method is found. This plan
  classifies optimistically (probe is advisory only) to preserve the legacy contract;
  see *Open Questions*.
- The existing scalar `NodeIdField` synthesis shim at `BuildContext.java:857-869` is
  the structural template for the new shim arm: column-miss → catalog metadata
  recognition → variant synthesis with a per-site WARN naming the offending
  `parentTypeName.fieldName`. Retirement is tracked at
  `roadmap/retire-nodeid-synthesis-shim.md`; an analogous retirement item ships with
  this work.
- `InputField` is sealed. Adding a variant will cause compile errors at every exhaustive
  switch site (Phase 1 enumerates them), and `TypeFetcherGenerator.NOT_DISPATCHED_LEAVES`
  (`TypeFetcherGenerator.java:167`) must learn the new class or
  `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` fails.

## Implementation Approach

Closest existing variants are `InputField.NodeIdReferenceField` (`InputField.java:106`)
and `InputField.NodeIdField`. The new variant is the list-tolerant cousin of the
former and shares the synthesis-shim pattern of the latter:

- `NodeIdReferenceField`: scalar `ID!` with `@nodeId(typeName:)` pointing at a NodeType
  reachable through `joinPath`. Catalog probe uses static fields
  (`__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS`).
- `NodeIdField`: bare scalar `ID` on a `@table` whose record carries node-id metadata,
  synthesized at `BuildContext.java:857-869` with per-site WARN.
- `IdReferenceField` (new): `ID!` or `[ID!]` with `@nodeId(typeName: T)`, optionally
  pinned by `@reference(path: [{key: K}])`. FK is inferred from the input's resolved
  table to the table backing `T` when unique; explicit `@reference` is required when
  ambiguous. Predicate method probe (advisory) checks for `has<Name>(s)` on the
  *resolved* table's record class (FK source). The shim arm at the column-miss tail
  reverse-maps `@field(name:)` to a unique FK and synthesizes the canonical form.

The classifier branches live in `BuildContext.classifyInputField`. The canonical-form
branch sits between the existing scalar-`@nodeId` branch (line 737) and the existing
`@reference` branch (line 792). The shim arm sits at the column-miss tail (line 871),
adjacent to the existing scalar `NodeIdField` shim.

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
     * A filter field typed {@code ID!} or {@code [ID!]} whose predicate is a
     * {@code has<Qualifier>(s)} method on the jOOQ record class of the input's
     * resolved table (i.e. the FK source). The method is emitted by
     * {@code KjerneJooqGenerator} from a single FK out of that table, identified
     * here by {@code fkName}.
     *
     * <p>{@code targetTypeName} is the GraphQL type the IDs encode (from
     * {@code @nodeId(typeName:)} on the canonical forms, or synthesized from the
     * FK's target table on the shim arm). {@code fkName} is the jOOQ FK constraint
     * name (from {@code @reference(path: [{key:}])} when explicit, or inferred by
     * walking FKs from the resolved table to the table backing
     * {@code targetTypeName} when unique). {@code qualifier} is the SQL form of
     * {@code KjerneJooqGenerator.getQualifier(fk)} (e.g. {@code "STUDIEPROGRAM_ID"});
     * code generation derives the candidate method names from it.
     *
     * <p>{@code synthesized} is {@code true} when the variant was emitted by the
     * column-miss shim arm (legacy {@code @field(name:)}-only SDL); the classifier
     * also logs a per-site WARN in that case.
     */
    record IdReferenceField(
        String parentTypeName,
        String name,
        SourceLocation location,
        String typeName,
        boolean nonNull,
        boolean list,
        String targetTypeName,
        String fkName,
        String qualifier,
        boolean synthesized
    ) implements InputField {}
}
```

The record carries no `ColumnRef` and no `JoinStep` list. Like `NodeIdField`, the
predicate resolves via a *method* on the FK source's record class, not a column;
single-hop only, so a join-path list is unnecessary. Code generation derives all
candidate method names from `qualifier` directly.

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

## Phase 2 — Catalog: predicate probe and FK helpers

### Overview

Add three additive helpers on `JooqCatalog`, all reflection- or jOOQ-metadata-based:

1. `hasIdSetPredicateMethod(tableSqlName, candidateNames...)` — advisory presence
   check on the resolved table's record class. Mirrors the legacy
   `searchTableForMethodWithName` contract; result does not gate classification.
2. `findUniqueFkToTable(sourceTableSqlName, targetTableSqlName)` — returns the unique
   FK whose source is `sourceTableSqlName` and whose referenced table is
   `targetTableSqlName`, or empty if zero or more than one such FK exists. Backs the
   FK-inferred canonical form.
3. `findFkByQualifier(sourceTableSqlName, qualifier)` — reverse-maps a qualifier
   (e.g. `"STUDIEPROGRAM_ID"`) to the unique FK on `sourceTableSqlName` whose
   `KjerneJooqGenerator.getQualifier(fk)` equals it. Backs the shim arm. Implemented
   by recomputing the qualifier for each FK (the rewrite reproduces
   `getQualifier`/`generateRolePrefix` rather than depending on KjerneJooqGenerator
   directly, since the generator runs upstream and is not on the rewrite's classpath).

### Changes

#### `JooqCatalog.java` — new methods near `findRecordClass` (line 88)

```java
/**
 * Returns {@code true} when the jOOQ record class for {@code tableSqlName} exposes any
 * public method whose name matches one of {@code candidateNames}. {@code KjerneJooqGenerator}
 * emits these for tables with an ID-set filter predicate (e.g. {@code hasStudieprogramIds},
 * {@code hasSTUDIEPROGRAM_IDs}). Returns {@code false} when the catalog is unavailable,
 * the table is not found, or no candidate matches.
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

/**
 * Returns the FK constraint name when exactly one FK on {@code sourceTableSqlName}
 * references {@code targetTableSqlName}; empty otherwise (zero or many).
 * Used by the FK-inferred canonical form.
 */
public Optional<String> findUniqueFkToTable(String sourceTableSqlName,
                                             String targetTableSqlName) { /* ... */ }

/**
 * Returns the FK constraint name on {@code sourceTableSqlName} whose computed
 * qualifier (per the local reproduction of {@code getQualifier} /
 * {@code generateRolePrefix}) equals {@code qualifier} (case-insensitive). Empty if
 * no FK matches. By the qualifier↔FK 1:1 invariant on a given source table, at
 * most one FK can match. Used by the shim arm.
 */
public Optional<String> findFkByQualifier(String sourceTableSqlName, String qualifier) { /* ... */ }
```

The qualifier computation is pulled into a small static helper on `JooqCatalog` (or a
package-private utility class) and unit-tested directly. Tests assert it produces the
same string as `KjerneJooqGenerator.getQualifier` for a representative set of FK
shapes (single column, composite, with/without role prefix, role == "HAR").

### Success Criteria

- [ ] `mvn compile -pl graphitron-rewrite -Pquick`
- [ ] `mvn test -pl graphitron-rewrite -Pquick`
- [ ] Unit tests for the qualifier reproduction match KjerneJooqGenerator output for
      representative FK shapes (parameterized test with hand-checked expectations).

---

## Phase 3 — Classifier: `BuildContext.classifyInputField`

### Overview

Two changes in `BuildContext.classifyInputField` (`BuildContext.java:721`):

1. **Relax the `@nodeId` `!list` gate** (line 737) so `[ID!] @nodeId(typeName:)` is
   accepted, then add a canonical-form branch that resolves the FK (inferred when
   unique, pinned by `@reference(path: [{key:}])` when explicit) and emits
   `IdReferenceField`. The branch sits between the existing `@nodeId` branch and the
   existing `@reference` branch, and is disjoint from both: the `@nodeId` branch
   above stays singular-only, this one is list-tolerant; the `@reference` branch
   below requires `@reference` *without* `@nodeId`.
2. **Add a synthesis shim arm** at the column-miss tail (line 871, just after the
   existing scalar-`NodeIdField` shim at lines 857-869). When neither directive is
   declared, `@field(name:)` is present, the field type is `ID!` or `[ID!]`, and the
   reverse-mapped FK is unique, synthesize the canonical form and emit
   `IdReferenceField` with `synthesized = true` and a per-site WARN naming the
   `parentTypeName.fieldName`. Modelled on the existing scalar shim.

The catalog probe is **advisory** in both branches: when it returns false but the FK
resolved, the classifier still emits `IdReferenceField` and logs a build warning.
This preserves legacy behaviour (`FetchDBMethodGenerator.generateHasForID` falls
through to `asHasCall` unconditionally) and keeps classification testable against
catalogs whose record classes were not augmented with `has*` methods.

### Changes

#### Relax the `@nodeId` `!list` gate

Today, `BuildContext.java:737` reads:

```java
if ("ID".equals(typeName) && !list && field.hasAppliedDirective(DIR_NODE_ID)) {
    /* NodeIdReferenceField synthesis */
}
```

Change to:

```java
if ("ID".equals(typeName) && field.hasAppliedDirective(DIR_NODE_ID)) {
    if (!list) {
        // existing NodeIdReferenceField handling, unchanged
    } else {
        // delegate to the new IdReferenceField canonical-form branch (below)
    }
}
```

`NodeIdPipelineTest.java:367` (`"list [ID!]! with @nodeId(typeName:) → UnclassifiedType
(list-gate applies before @nodeId check)"`) is updated: `[ID!] @nodeId` to a valid
`Studieprogram`-typed filter now classifies as `IdReferenceField`. List-typed
`@nodeId` cases that don't satisfy the new branch's preconditions (no FK, multiple
FKs without `@reference`, target type is not `@table @node`) still fail.

#### Canonical-form branch

```java
// IdReferenceField (canonical): [ID!] (or ID!) with @nodeId(typeName: T), optionally
// pinned by @reference(path: [{key: K}]). FK is inferred from resolved table to the
// table backing T when unique; explicit @reference disambiguates when not. Emits
// IdReferenceField with synthesized=false. Single-hop only; multi-hop falls through
// to the legacy @reference branch and surfaces "not reachable via @reference path".
if ("ID".equals(typeName) && field.hasAppliedDirective(DIR_NODE_ID) && list) {
    String refTypeName = argString(field, DIR_NODE_ID, ARG_TYPE_NAME).orElse(null);
    if (refTypeName == null) {
        return new InputFieldResolution.Unresolved(name, null,
            "@nodeId on [ID!] requires typeName:");
    }
    var rawGqlType = schema.getType(refTypeName);
    if (!(rawGqlType instanceof GraphQLObjectType targetObj)
            || !targetObj.hasAppliedDirective(DIR_TABLE)) {
        return new InputFieldResolution.Unresolved(name, null,
            "@nodeId(typeName:) type '" + refTypeName + "' is not @table-annotated");
    }
    String targetTable = argString(targetObj, DIR_TABLE, ARG_NAME)
        .orElse(refTypeName.toLowerCase());

    String fkName;
    if (field.hasAppliedDirective(DIR_REFERENCE)) {
        var path = parsePath(field, name, resolvedTable.tableName(), targetTable);
        if (path.hasError()) return new InputFieldResolution.Unresolved(name, null, path.errorMessage());
        if (path.elements().size() != 1) {
            return new InputFieldResolution.Unresolved(name, null,
                "@reference path on [ID!] @nodeId must be single-hop; multi-hop FK"
                + " filters are not supported.");
        }
        fkName = path.elements().get(0).keyName();  // or equivalent accessor
    } else {
        var inferred = catalog.findUniqueFkToTable(resolvedTable.tableName(), targetTable);
        if (inferred.isEmpty()) {
            return new InputFieldResolution.Unresolved(name, null,
                "no unique FK from '" + resolvedTable.tableName() + "' to '"
                + targetTable + "'; declare @reference(path: [{key: ...}]) to disambiguate.");
        }
        fkName = inferred.get();
    }

    String qualifier = catalog.qualifierForFk(resolvedTable.tableName(), fkName)
        .orElseThrow(); // FK is on the source table; qualifier always computable
    String[] candidates = idSetPredicateCandidates(qualifier);
    if (!catalog.hasIdSetPredicateMethod(resolvedTable.tableName(), candidates)) {
        addWarning(new BuildWarning(
            "input field '" + parentTypeName + "." + name + "': no has-accessor among "
            + Arrays.toString(candidates) + " on jOOQ record for table '"
            + resolvedTable.tableName() + "' (FK '" + fkName + "'); generated code"
            + " may not compile until KjerneJooqGenerator emits the predicate.",
            locationOf(field)));
    }
    return new InputFieldResolution.Resolved(new InputField.IdReferenceField(
        parentTypeName, name, locationOf(field), typeName, nonNull, list,
        refTypeName, fkName, qualifier, /* synthesized */ false));
}
```

Notes:
- `parsePath` is called with the target table populated when `@reference` is present,
  unlike the bare `@reference` branch at line 793. The single-hop assertion is
  enforced after parsing; multi-hop falls through with a clear diagnostic.
- `catalog.qualifierForFk(sourceTable, fkName)` is a small additional accessor on
  `JooqCatalog` that runs the local reproduction of `getQualifier` against a named FK.
  Add alongside `findUniqueFkToTable` / `findFkByQualifier` from Phase 2 if not
  already present there.

#### Synthesis shim arm

Place at the column-miss tail (`BuildContext.java:871`), immediately *before* the
existing scalar `NodeIdField` shim. The two are coordinated by ordering: this shim
fires only when the column name (whether explicit `@field(name:)` or defaulted from
the GraphQL field name) reverse-maps to a unique FK qualifier on the resolved table.
The existing scalar `NodeIdField` shim still runs immediately after for bare `id: ID`
on a node-typed table; that case has no matching FK qualifier (qualifiers always
include the target table token like `STUDIEPROGRAM_ID`) and falls through naturally.

The trigger covers all three of the author's mental-model cases:

- `id: ID` → `columnName = "id"` → upper-snake `"ID"` → no FK qualifier matches →
  falls through to existing `NodeIdField` shim, which classifies as the input's
  own table id. Unchanged behaviour.
- `otherId: ID` (no directives) → `columnName = "otherId"` → upper-snake `"OTHER_ID"`
  → if the input's resolved table has exactly one FK whose qualifier is `OTHER_ID`,
  synthesize. Otherwise, fall through.
- `yetAnotherId: [ID!]` (no directives) → same path, with `list = true`.
- `studieprogrammer: [ID!] @field(name: "STUDIEPROGRAM_ID")` (legacy sis form) →
  `columnName = "STUDIEPROGRAM_ID"` (already upper-snake from `@field`) → FK lookup
  by qualifier, synthesize.

```java
// IdReferenceField synthesis shim: ID!/[ID!] on a @table input whose column name
// (from @field(name:) or defaulted from the GraphQL field name) reverse-maps to a
// unique FK qualifier on the resolved table. Synthesizes the canonical @nodeId
// (+ @reference) form internally. Emits a per-site WARN naming
// parentTypeName.fieldName; deprecate-and-retire path same as the scalar NodeIdField
// shim. Retirement: roadmap/retire-id-reference-synthesis-shim.md.
if ("ID".equals(typeName)
        && !field.hasAppliedDirective(DIR_NODE_ID)
        && !field.hasAppliedDirective(DIR_REFERENCE)) {
    // Two qualifier candidates: the upper-snake form as-is, and (for plural list
    // fields) the form with a single trailing `S` stripped. "otherId" → "OTHER_ID";
    // "languageIds" → ["LANGUAGE_IDS", "LANGUAGE_ID"]; "STUDIEPROGRAM_ID" → as-is.
    // Stops at single-S strip; no general pluralisation. See "Open Questions".
    String primary = toUpperSnake(columnName);
    Optional<String> fkOpt = catalog.findFkByQualifier(resolvedTable.tableName(), primary);
    String qualifierCandidate = primary;
    if (fkOpt.isEmpty() && primary.endsWith("S") && primary.length() > 1) {
        String stripped = primary.substring(0, primary.length() - 1);
        fkOpt = catalog.findFkByQualifier(resolvedTable.tableName(), stripped);
        if (fkOpt.isPresent()) qualifierCandidate = stripped;
    }
    if (fkOpt.isPresent()) {
        String fkName = fkOpt.get();
        Optional<String> targetTableOpt = catalog.referencedTable(fkName);
        Optional<String> targetTypeOpt = targetTableOpt.flatMap(this::findGraphQLTypeForTable);
        if (targetTypeOpt.isPresent()) {
            String[] candidates = idSetPredicateCandidates(qualifierCandidate);
            if (!catalog.hasIdSetPredicateMethod(resolvedTable.tableName(), candidates)) {
                addWarning(new BuildWarning(
                    "input field '" + parentTypeName + "." + name + "': no has-accessor on"
                    + " '" + resolvedTable.tableName() + "' for synthesized qualifier '"
                    + qualifierCandidate + "'.", locationOf(field)));
            }
            ID_REF_SHIM_LOGGER.warn("input field '{}.{}' synthesizes IdReferenceField"
                + " from qualifier '{}' (FK '{}') — declare @nodeId(typeName: \"{}\")"
                + " explicitly; synthesis shim will be removed in a future release."
                + " See graphitron-rewrite/roadmap/retire-id-reference-synthesis-shim.md",
                parentTypeName, name, qualifierCandidate, fkName, targetTypeOpt.get());
            return new InputFieldResolution.Resolved(new InputField.IdReferenceField(
                parentTypeName, name, locationOf(field), typeName, nonNull, list,
                targetTypeOpt.get(), fkName, qualifierCandidate, /* synthesized */ true));
        }
    }
}
```

Notes:
- `toUpperSnake(s)` is a small helper that converts `lowerCamelCase` to
  `UPPER_SNAKE_CASE` and leaves already-upper-snake strings untouched. Idempotent
  on the typical `@field(name: "STUDIEPROGRAM_ID")` value. Inverse of the existing
  `toCamelCase` helper (which is used in `idSetPredicateCandidates`).
- `findGraphQLTypeForTable(sqlTableName)` is a new helper on `BuildContext` that
  iterates `schema.getAllTypesAsList()` looking for the unique `GraphQLObjectType`
  with `@table(name:)` matching (case-insensitive). Returns the type name. If zero
  or multiple types match, returns empty and the shim does not fire (the field falls
  through to `Unresolved` or the next shim).
- `ID_REF_SHIM_LOGGER` parallels the existing `NODE_ID_SHIM_LOGGER` declaration near
  the top of `BuildContext.java`.
- The shim does not require `@field(name:)`. Both the explicit form
  (`@field(name: "STUDIEPROGRAM_ID")`) and the implicit form (bare `otherId: ID`)
  enter via the same code path; `columnName` already carries the right value
  whichever form the author chose.

#### Name-derivation helper (private, in `BuildContext`)

```java
// "STUDIEPROGRAM_ID" → ["hasSTUDIEPROGRAM_ID", "hasSTUDIEPROGRAM_IDs",
//                      "hasStudieprogramId", "hasStudieprogramIds"]
// Mirrors legacy MethodMapping.asHas() / asCamelHas(), each in singular and plural form.
private static String[] idSetPredicateCandidates(String qualifier) {
    String upper = "has" + qualifier;
    String camel = "has" + capitalize(toCamelCase(qualifier));
    return new String[] { upper, upper + "s", camel, camel + "s" };
}
```

Reuse the existing `toCamelCase` / `capitalize` helpers if they're already on the
classpath; otherwise inline a small `UPPER_UNDERSCORE → LOWER_CAMEL` conversion.

### Success Criteria

- [ ] `mvn compile -pl graphitron-rewrite -Pquick`
- [ ] `mvn test -pl graphitron-rewrite -Pquick`
- [ ] No `UnclassifiedType` for inputs whose ID-list filter fields use any of the
      three forms (canonical FK-inferred, canonical FK-explicit, shim).
- [ ] `NodeIdPipelineTest.java:367` is updated: `[ID!] @nodeId` to a valid target
      type now classifies; the negative case is preserved for invalid targets.

---

## Phase 4 — Tests

### Overview

Add pipeline-level cases in `GraphitronSchemaBuilderTest.TableInputTypeCase` (Sakila
catalog, exercises FK resolution and the warn-on-missing-method path), a unit test for
the reflection probe, and a unit test for the qualifier reproduction.

Because the predicate-method probe is advisory (Phase 3), classification succeeds
against Sakila even though `FilmRecord` has no `has*` method; the warning fires, the
variant is still emitted. The qualifier reproduction is testable without
`KjerneJooqGenerator` on the classpath, by parameterized hand-checked expectations.

### Changes

#### `GraphitronSchemaBuilderTest.java` — new `TableInputTypeCase` enum constants

**Case 1 — canonical, FK-inferred (`@nodeId` only)**:

```java
ID_REFERENCE_NODEID_INFERRED(
    "[ID!] @nodeId(typeName:) with unique FK → IdReferenceField (FK inferred)",
    """
    type Language @table(name: "language") @node(typeId: "lang") { name: String }
    type Film @table(name: "film") { title: String }
    input FilmFilterInput @table(name: "film") {
      languageIds: [ID!] @nodeId(typeName: "Language")
    }
    type Query { film: Film }
    """,
    schema -> {
        var tit = (TableInputType) schema.type("FilmFilterInput");
        var f = (InputField.IdReferenceField) tit.inputFields().stream()
            .filter(InputField.IdReferenceField.class::isInstance).findFirst().orElseThrow();
        assertThat(f.list()).isTrue();
        assertThat(f.targetTypeName()).isEqualTo("Language");
        assertThat(f.synthesized()).isFalse();
        assertThat(f.fkName()).isNotBlank();  // resolved by findUniqueFkToTable
        assertThat(f.qualifier()).isEqualTo("LANGUAGE_ID");
    }) {
    @Override public Set<Class<?>> variants() { return Set.of(InputField.IdReferenceField.class); }
},
```

**Case 2 — canonical, FK-explicit (`@nodeId` + `@reference`)**:

```java
ID_REFERENCE_NODEID_EXPLICIT(
    "[ID!] @nodeId + @reference(path: [{key:}]) → IdReferenceField (FK explicit)",
    """
    type Language @table(name: "language") @node(typeId: "lang") { name: String }
    type Film @table(name: "film") { title: String }
    input FilmFilterInput @table(name: "film") {
      languageIds: [ID!] @nodeId(typeName: "Language")
                         @reference(path: [{key: "film_language_id_fkey"}])
    }
    type Query { film: Film }
    """,
    schema -> {
        var tit = (TableInputType) schema.type("FilmFilterInput");
        var f = (InputField.IdReferenceField) tit.inputFields().stream()
            .filter(InputField.IdReferenceField.class::isInstance).findFirst().orElseThrow();
        assertThat(f.fkName()).isEqualTo("film_language_id_fkey");
        assertThat(f.synthesized()).isFalse();
    }),
```

**Case 3 — canonical, ambiguous FK without `@reference` → UnclassifiedField**:

```java
ID_REFERENCE_AMBIGUOUS_FK(
    "[ID!] @nodeId on a target with multiple FKs → UnclassifiedField (needs @reference)",
    """
    type Language @table(name: "language") @node(typeId: "lang") { name: String }
    type Film @table(name: "film") { title: String }
    input FilmFilterInput @table(name: "film") {
      languageIds: [ID!] @nodeId(typeName: "Language")  # film has language_id and original_language_id
    }
    type Query { film: Film }
    """,
    schema -> assertThat(schema.type("FilmFilterInput")).isInstanceOf(UnclassifiedType.class)),
```

(Sakila's `film` table has two FKs to `language`: `language_id` and
`original_language_id`. Without `@reference`, inference must reject; the diagnostic
points the author at `@reference(path: [{key: ...}])`.)

**Case 4a — synthesis shim, legacy form with explicit `@field(name:)`**:

```java
ID_REFERENCE_SHIM_EXPLICIT_FIELD(
    "[ID!] @field(name: \"X_ID\") with no @nodeId/@reference → IdReferenceField (synthesized + WARN)",
    """
    type Language @table(name: "language") @node(typeId: "lang") { name: String }
    type Film @table(name: "film") { title: String }
    input FilmFilterInput @table(name: "film") {
      languageIds: [ID!] @field(name: "LANGUAGE_ID")
    }
    type Query { film: Film }
    """,
    schema -> {
        var tit = (TableInputType) schema.type("FilmFilterInput");
        var f = (InputField.IdReferenceField) tit.inputFields().stream()
            .filter(InputField.IdReferenceField.class::isInstance).findFirst().orElseThrow();
        assertThat(f.synthesized()).isTrue();
        assertThat(f.targetTypeName()).isEqualTo("Language");
        assertThat(f.qualifier()).isEqualTo("LANGUAGE_ID");
    }),
```

**Case 4b — synthesis shim, bare field name (no directives at all)**:

```java
ID_REFERENCE_SHIM_BARE_LIST(
    "[ID!] with bare field name (camelCase → upper-snake) → IdReferenceField (synthesized + WARN)",
    """
    type Language @table(name: "language") @node(typeId: "lang") { name: String }
    type Film @table(name: "film") { title: String }
    input FilmFilterInput @table(name: "film") {
      languageIds: [ID!]   # no directives; "languageIds" → "LANGUAGE_ID" qualifier
    }
    type Query { film: Film }
    """,
    schema -> {
        var tit = (TableInputType) schema.type("FilmFilterInput");
        var f = (InputField.IdReferenceField) tit.inputFields().stream()
            .filter(InputField.IdReferenceField.class::isInstance).findFirst().orElseThrow();
        assertThat(f.synthesized()).isTrue();
        assertThat(f.qualifier()).isEqualTo("LANGUAGE_ID");
    }),
```

(Field name is `languageIds`. `toUpperSnake` produces `LANGUAGE_IDS`; the shim's
single-S strip retries with `LANGUAGE_ID`, which matches the FK qualifier. Both
the strip behaviour and its limits are documented under "What We're NOT Doing"
and "Open Questions": the strip is single-S only, no general pluralisation.)

**Case 4c — synthesis shim, scalar bare field name**:

```java
ID_REFERENCE_SHIM_BARE_SCALAR(
    "scalar ID with bare field name → IdReferenceField (synthesized + WARN)",
    """
    type Language @table(name: "language") @node(typeId: "lang") { name: String }
    type Film @table(name: "film") { title: String }
    input FilmFilterInput @table(name: "film") {
      languageId: ID    # "languageId" → "LANGUAGE_ID" qualifier
    }
    type Query { film: Film }
    """,
    schema -> {
        var tit = (TableInputType) schema.type("FilmFilterInput");
        var f = (InputField.IdReferenceField) tit.inputFields().stream()
            .filter(InputField.IdReferenceField.class::isInstance).findFirst().orElseThrow();
        assertThat(f.synthesized()).isTrue();
        assertThat(f.list()).isFalse();
    }),
```

**Case 4d — bare `id: ID` falls through to the existing `NodeIdField` shim, not this one**:

```java
ID_REFERENCE_DOES_NOT_SHIM_OWN_ID(
    "bare id: ID on a node-typed @table → NodeIdField (existing shim), not IdReferenceField",
    """
    type Film @table(name: "film") @node(typeId: "f") { title: String }
    input FilmFilterInput @table(name: "film") {
      id: ID   # qualifier candidate "ID" doesn't match any FK; falls through to NodeIdField
    }
    type Query { film: Film }
    """,
    schema -> {
        var tit = (TableInputType) schema.type("FilmFilterInput");
        var f = tit.inputFields().stream()
            .filter(g -> g.name().equals("id")).findFirst().orElseThrow();
        assertThat(f).isInstanceOf(InputField.NodeIdField.class);
    }),
```

**Case 5 — bad explicit reference path → UnclassifiedField**:

```java
ID_REFERENCE_BAD_KEY(
    "[ID!] @nodeId + @reference to nonexistent FK → UnclassifiedField",
    """
    type Language @table(name: "language") @node(typeId: "lang") { name: String }
    type Film @table(name: "film") { title: String }
    input FilmFilterInput @table(name: "film") {
      languageIds: [ID!] @nodeId(typeName: "Language")
                         @reference(path: [{key: "no_such_fkey"}])
    }
    type Query { film: Film }
    """,
    schema -> assertThat(schema.type("FilmFilterInput")).isInstanceOf(UnclassifiedType.class)),
```

**Case 6 — scalar `ID!` canonical form**:

```java
ID_REFERENCE_SCALAR(
    "scalar ID! with @nodeId(typeName:) → IdReferenceField with list=false",
    """
    type Language @table(name: "language") @node(typeId: "lang") { name: String }
    type Film @table(name: "film") { title: String }
    input FilmFilterInput @table(name: "film") {
      languageId: ID! @nodeId(typeName: "Language")
    }
    type Query { film: Film }
    """,
    schema -> {
        var tit = (TableInputType) schema.type("FilmFilterInput");
        var f = (InputField.IdReferenceField) tit.inputFields().stream()
            .filter(g -> g instanceof InputField.IdReferenceField irf && !irf.list())
            .findFirst().orElseThrow();
        assertThat(f).isInstanceOf(InputField.IdReferenceField.class);
    }),
```

(Scalar `ID! @nodeId` historically resolves to `NodeIdReferenceField`; this case
verifies the new branch's list-tolerant arm picks the list=false case as
`IdReferenceField` only when the relevant SDL distinguishes it from the existing
scalar `NodeIdReferenceField` route. If the scalar case is exclusively
`NodeIdReferenceField`'s territory, drop this case and document the partition
clearly.)

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
- `QualifierForFkTest` (or equivalent) — the local reproduction of
  `KjerneJooqGenerator.getQualifier(fk)` produces the same string for representative
  FK shapes (single column, composite, with/without role prefix, role == "HAR").
  Hand-checked expectations.

### Pipeline tests
- `GraphitronSchemaBuilderTest.TableInputTypeCase` — eight new cases covering the
  three SDL forms (canonical FK-inferred, canonical FK-explicit, shim with explicit
  `@field(name:)`, shim with bare list field name, shim with bare scalar field name,
  bare `id: ID` falls through to `NodeIdField`, ambiguous-FK rejection, bad explicit
  reference key).

### Manual verification
- Apply the built snapshot to the FS platform `sis-graphql-spec` and confirm the
  `STUDIEPROGRAM_ID` / `STUDENTSTATUS_ID` / `START_TERMIN_ID` style errors are gone
  on filter inputs whose predicate is single-hop from the input's resolved table.
  Errors that mention "not reachable via @reference path" persist (multi-hop, out of
  scope for this spec).

## Retirement

A separate roadmap item, `roadmap/retire-id-reference-synthesis-shim.md`, is filed
alongside this work. It tracks promoting the shim's per-site WARN to a terminal
classifier error once consumer schemas (sis primarily) have migrated to the canonical
`@nodeId(typeName: T)` form. Same lifecycle as
`roadmap/retire-nodeid-synthesis-shim.md` for the scalar-`ID` shim.

The retirement item lists:
- The trigger condition the shim fires on (see Phase 3, shim arm).
- The migration recipe for schema authors (replace `@field(name: "X_ID")` and bare
  `xId` field names with `@nodeId(typeName: T)`).
- The flag-day plan: shim demoted to ERROR after sis is fully migrated.

## Open Questions

**Resolved: probe is advisory.** The catalog method probe returns a boolean that the
classifier uses only to emit a build warning; it does not gate classification. Rationale:
the legacy (`FetchDBMethodGenerator.generateHasForID`, line 649) emits the `has*` call
unconditionally, never failing on absence. Strict probing would be a behaviour change
and would require extending `NodeIdFixtureGenerator` to emit `has*` methods on a fixture
table before any happy-path test could pass, which is out of scope for the
classification milestone. Revisit if/when code generation lands and we want a
build-time guarantee that the predicate exists.

**Open: bare list field-name pluralisation rule.** The shim strips a single trailing
`s` from upper-snake-converted field names so that `languageIds` matches qualifier
`LANGUAGE_ID`. This is the minimum rule that handles the well-named English case
without invoking a general pluraliser. Norwegian-plural forms (`studieprogrammer`)
will not match; those cases need explicit `@field(name:)` or `@nodeId(typeName:)`.
The implementation should make the s-strip behaviour explicit in the helper's
javadoc and in the `What We're NOT Doing` documentation. If sis schemas show the
need for richer pluralisation, revisit; current scope is the s-strip rule only.

## References

- Legacy `@nodeId` documentation: `graphitron-codegen-parent/graphitron-java-codegen/README.md:1864-1894`
  — implicit-typeName rules ("uses the node type with the same table (if unambiguous)").
- Legacy `@reference` documentation: `graphitron-codegen-parent/graphitron-java-codegen/README.md:428-449`
  — implicit-key rules ("the key and the reference directive itself are redundant since
  there is only one key between the tables").
- Legacy `@field` documentation: `graphitron-codegen-parent/graphitron-java-codegen/README.md:306-311`
  — defaults to GraphQL field name; useful for column-mapped fields, does not reach
  KjerneJooqGenerator qualifiers.
- Legacy implicit-key implementation: `TableReflection.findImplicitKey` —
  `graphitron-codegen-parent/.../mappings/TableReflection.java:36-45`. The rewrite's
  `JooqCatalog.findUniqueFkToTable` is the same algorithm.
- Legacy `@field(name:)` storage: `AbstractField.java:31-50` — stores
  `unprocessedFieldOverrideInput` as a `MethodMapping` name; never validated against
  the catalog at construction.
- Legacy bypass: `TableValidator.java:271` — `field.isID() && !shouldMakeNodeStrategy()`
- Legacy method derivation: `FetchDBMethodGenerator.java:639-649` — `generateHasForID`
  (probes upper- and camelCase forms but falls through to `asHasCall` unconditionally)
- Legacy name helpers: `MethodMapping.asHas()` / `asCamelHas()` —
  `graphitron-codegen-parent/.../mapping/MethodMapping.java:94, 101`
- Closest rewrite analogue: `InputField.NodeIdReferenceField` — `InputField.java:106`
  (the `@nodeId @reference` companion); catalog probe via
  `JooqCatalog.nodeIdMetadata` — `JooqCatalog.java:246`
- Record-class lookup: `JooqCatalog.findRecordClass` — `JooqCatalog.java:88`
- Existing `@reference` branch the canonical-form code sits next to:
  `BuildContext.classifyInputField` — `BuildContext.java:792`
- Existing scalar `NodeIdField` synthesis shim (template for the new shim arm):
  `BuildContext.classifyInputField` — `BuildContext.java:857-869`
- Retirement plan for the existing scalar shim (template for the new shim's
  retirement): `roadmap/retire-nodeid-synthesis-shim.md`
