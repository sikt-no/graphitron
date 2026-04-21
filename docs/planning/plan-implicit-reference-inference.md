# Implicit `@reference` path inference

> **Status:** Draft

## Overview

Make `@reference(path: ...)` optional at every child-field site that can use it. When the directive is absent (or present with an empty `path`), the classifier synthesizes the path from source table to target table using the jOOQ catalog's foreign-key metadata. The rule is uniform: exactly one FK between source and target → one-hop `FkJoin`; zero or multiple FKs → classifier-time `UnclassifiedField` asking the author to write an explicit `@reference`.

## Current state

`BuildContext.parsePath` returns `ParsedPath(List.of(), null)` — empty path, no error — whenever `@reference` is missing or has no `path:` argument (`BuildContext.java:398-403`). Every downstream classifier arm threads that empty list into its field constructor unchanged. The only place the "exactly-one-FK between source and target" rule is implemented today is `GraphitronSchemaValidator.validateNodeIdReferenceField` (lines 333-356), and even there it's validation-only — it never synthesizes an `FkJoin`.

Consequences:

- **Split / Record rows-method emitters** reject four variants at emit time with a runtime-throwing stub whose message the validator surfaces as a build error: `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField` all have an EMPTY_PATH branch in `SplitRowsMethodEmitter.unsupportedReason` (lines 164-168, 207-211, 245-249, 287-291) that fires on empty `joinPath`.
- **`RecordTableField` / `RecordLookupTableField`** additionally fail earlier at classify time because `FieldBuilder.deriveBatchKeyForResultType` returns `null` on an empty path, producing an `UnclassifiedField` with a generic "requires a FK join path" message (`FieldBuilder.java:1599-1608`).
- **`NodeIdReferenceField`** gets the validation-only FK-count rule from `validateNodeIdReferenceField` — a duplicate of logic that belongs in the classifier.

## Desired end state

`parsePath` takes source and target SQL table names and is the single place that resolves a child-field's join path. When the user writes `@reference(path: ...)` it parses and validates that. When the directive is absent or empty and both tables are known, it runs the single-FK inference. On failure the classifier produces an `UnclassifiedField` with the existing NodeId-validator wording ("no foreign key found…" / "multiple foreign keys found…; add a `@reference` directive…"). The four `unsupportedReason` EMPTY_PATH branches and the `validateNodeIdReferenceField` FK-count branches are deleted; they're now unreachable.

Verification: schemas using `[Language.films] @splitQuery` (single FK `film_language_id_fkey`) compile and execute against the test-spec database without `@reference`; schemas using `Film.actors @splitQuery` (no direct FK — junction table) classify as `UnclassifiedField` at build time with the "no foreign key found…" message.

## What we're NOT doing

- **Multi-hop inference.** If zero direct FKs exist between source and target, the classifier errors. Walking junction tables automatically is out of scope.
- **`@reference` on `ColumnReferenceField` / `InputField.ColumnReferenceField`** (`FieldBuilder.java:1771`, `TypeBuilder.java:576`). These sites already require the directive by an outer `if (hasAppliedDirective(DIR_REFERENCE))` guard for classification reasons unrelated to path resolution (presence of `@reference` is what distinguishes a joined-table column from a parent-table scalar). The guards stay.
- **Service reconnect paths** (`FieldBuilder.java:1551, 1675`). These pass `null` source because the path starts from the service return type, not the parent; inference has no anchor and does not fire.
- **`ComputedField` / `TableMethodField` with cross-table `@reference`.** Same-table is the common case (source == target) and inference correctly returns empty for it. Cross-table is rare and if needed users still write `@reference` explicitly.

## Implementation approach

The change is one coherent rule applied at one place (`parsePath`) plus the cleanups that become dead. Implementer decides commit splits.

### 1. Classifier — inference-aware `parsePath`

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java`

Add a `targetSqlTableName` parameter to `parsePath`. After the existing directive-parse block (lines 398-427), if the resolved `elements` list is empty:

```
if (resolvedElements.isEmpty()
        && startSqlTableName != null
        && targetSqlTableName != null
        && !startSqlTableName.equalsIgnoreCase(targetSqlTableName)) {
    var fks = catalog.findForeignKeysBetweenTables(startSqlTableName, targetSqlTableName);
    if (fks.size() == 1) {
        resolvedElements.add(synthesizeFkJoin(fks.get(0), startSqlTableName, fieldName));
    } else if (fks.isEmpty()) {
        return new ParsedPath(List.of(),
            "no foreign key found between tables '" + startSqlTableName + "' and '"
                + targetSqlTableName + "'; add a @reference directive to specify the join path");
    } else {
        return new ParsedPath(List.of(),
            "multiple foreign keys found between tables '" + startSqlTableName + "' and '"
                + targetSqlTableName + "'; add a @reference directive to specify the join path");
    }
}
```

Lift the `FkJoin` construction from the `tableName.isPresent()` branch of `parsePathElement` (lines 490-517) into a shared helper `synthesizeFkJoin(ForeignKey, String sourceSqlName, String fieldName)` and call it from both places. The helper resolves source/target `TableRef`s, FK column lists, and the step alias `fieldName + "_0"`. No `whereFilter` — implicit inference doesn't carry a condition.

### 2. Classifier — call-site updates

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java`

Update every `parsePath` call to pass a target. Six sites pass the resolved target table; four pass `null`:

| Call site | Variant(s) | Target arg |
|---|---|---|
| `:242` — object return, table-backed parent | TableField / SplitTableField / LookupTableField / SplitLookupTableField | `returnType.table().tableName()` |
| `:293` — object return, table-interface parent | TableInterfaceField | `tableInterfaceType.table().tableName()` |
| `:1551` — `@service` on result parent | ServiceTableField / ServiceRecordField | `null` (service reconnect) |
| `:1589` — object return, result parent | RecordTableField / RecordLookupTableField / RecordField | see below |
| `:1675` — `@service` on table parent | ServiceTableField / ServiceRecordField | `null` (service reconnect) |
| `:1694` — `@externalField` | ComputedField | `tableType.table().tableName()` (same-table → inference returns empty) |
| `:1708` — `@tableMethod` | TableMethodField | `tableType.table().tableName()` (same-table) |
| `:1749` — `@nodeId(typeName:)` | NodeIdReferenceField | `targetType.table().tableName()` |
| `:1772` — `@reference` scalar | ColumnReferenceField / MultitableReferenceField | `null` (guarded by outer `hasAppliedDirective`; empty-path never fires here) |

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java`

| Call site | Variant | Target arg |
|---|---|---|
| `:579` — input-object `@reference` field | InputField.ColumnReferenceField | `null` (guarded) |

**Site :1589 needs a restructure.** The call currently happens before `ctx.resolveReturnType(elementTypeName, buildWrapper(fieldDef))`, so the target is unknown. Resolve the return type first, pass `tb.table().tableName()` for `TableBoundReturnType` and `null` for scalar/result/polymorphic arms. `RecordField` classification (non-table return from a `@record` parent) is unaffected because `null` target means inference doesn't fire.

### 3. Validator cleanup

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java`

Delete the zero-FK / multi-FK branches from `validateNodeIdReferenceField` (lines 333-356). Keep the `validateReferencePath` / `validateReferenceLeadsToType` calls (lines 358-361) for the explicit-path case — those are independent checks.

### 4. Emitter cleanup

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java`

Delete the EMPTY_PATH branch in each of the four `unsupportedReason(...)` methods:

- `SplitTableField` — lines 164-168
- `SplitLookupTableField` — lines 207-211
- `RecordTableField` — lines 245-249
- `RecordLookupTableField` — lines 287-291

The CARDINALITY and CONDITION_JOIN branches stay. Post-change, `path.get(0)` in `buildListMethod` (line 343) is guaranteed to be a non-null `FkJoin` by the classifier contract; no defensive check needed.

Also delete `FieldBuilder.deriveBatchKeyForResultType`'s `joinPath.isEmpty()` arm (line 1656). Path emptiness at that point would mean the classifier contract was violated; an `IllegalStateException` is more honest than a user-facing `UnclassifiedField` message.

### 5. Tests

**Validation tests — delete EMPTY_PATH cases:**

- `SplitTableFieldValidationTest.Case.LIST_NO_PATH`
- `SplitLookupTableFieldValidationTest.Case.LIST_EMPTY_PATH_STUBBED`
- `SplitLookupTableFieldValidationTest.Case.CONNECTION_BLOCKED` — remove the EMPTY_PATH expectation, keep the connection-error expectation
- `RecordTableFieldValidationTest.Case.LIST_NO_PATH`
- `RecordTableFieldValidationTest.Case.LIST_WITH_FIELD_CONDITION_EMPTY_PATH`
- `RecordLookupTableFieldValidationTest.Case.LIST_NO_PATH`
- `RecordLookupTableFieldValidationTest.Case.LIST_WITH_FIELD_CONDITION_EMPTY_PATH`
- `RecordLookupTableFieldValidationTest.Case.CONNECTION_BLOCKED` — remove the EMPTY_PATH expectation
- `NodeIdReferenceFieldValidationTest.Case.IMPLICIT_NO_FK` and `IMPLICIT_MULTIPLE_FKS` — delete; classifier-level replacements in pipeline tests.

**Pipeline tests — add new `GraphitronSchemaBuilderTest` cases:**

- `IMPLICIT_REFERENCE_SPLIT_TABLE` — `[Language.films] @splitQuery` (source `language`, target `film`, single FK `film_language_id_fkey`) → `SplitTableField` with one-element `joinPath`.
- `IMPLICIT_REFERENCE_RECORD_TABLE` — same shape but on a `@record` parent producing `RecordTableField`.
- `IMPLICIT_REFERENCE_NODE_ID_REFERENCE` — replaces `NodeIdReferenceFieldValidationTest.IMPLICIT_SINGLE_FK` at the pipeline level.
- `IMPLICIT_REFERENCE_ZERO_FK` — `Film.actors @splitQuery` (no direct FK between `film` and `actor`) → `UnclassifiedField` with "no foreign key found between tables 'film' and 'actor'…".
- `IMPLICIT_REFERENCE_MULTIPLE_FK` — `Film.languages` (two FKs `film.language_id` and `film.original_language_id` both point at `language`) → `UnclassifiedField` with "multiple foreign keys found…".

**Pipeline tests — fix existing fixtures that rely on the old empty-path-is-OK behavior:**

- `GraphitronSchemaBuilderTest.Case.SPLIT_QUERY` (line 598) — change to a direct-FK pair, e.g. `type Language @table(name: "language") { films: [Film!]! @splitQuery }`. Alternatively keep `Film.actors` and add the two-hop `@reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])`. Preference: the direct-FK form — it's what the new `IMPLICIT_REFERENCE_SPLIT_TABLE` case tests, and `SplitTableFieldPipelineTest` already covers the two-hop junction shape.
- `GraphitronSchemaBuilderTest.Case.SPLIT_LOOKUP_TABLE_FIELD` (line 609) — same treatment. Pattern: `type Film @table(name: "film") { language(language_id: ID! @lookupKey): Language @splitQuery }` with the single FK `film_language_id_fkey` inferred.

**Execution test — add a path-less `@splitQuery` fixture:**

- `graphitron-rewrite-test/graphitron-rewrite-test-spec/src/main/resources/graphql/schema.graphqls` — drop the `@reference(path: [{key: "film_language_id_fkey"}])` on `Language.films` at line 166. Add or adjust the corresponding execution test (`graphitron-rewrite-test/graphitron-rewrite-test-spec/src/test/...`) to confirm the query returns identical results to the explicit-`@reference` baseline. The existing fixture at line 116 (`actorsBySplitLookup` via two-hop junction) stays explicit — it's the legitimate multi-hop case.

## Success criteria

### Automated

- `mvn test -pl :graphitron-rewrite` passes.
- `mvn test -pl :graphitron-rewrite-test,:graphitron-rewrite-test-fixtures,:graphitron-rewrite-test-spec` passes (execution test with path-less `@splitQuery` returns matching rows).
- Grepping the codebase for the EMPTY_PATH stub messages (`"requires a @reference path"`) returns zero hits.
- `GraphitronSchemaValidator` no longer references `findForeignKeysBetweenTables`.

### Manual

- Running the generator against `sis-graphql-spec` surfaces any schemas that were previously masked by the EMPTY_PATH runtime-stub error but now fail at classify time with the new error messages. Expected outcome: fields that relied on accidental single-FK proximity start working; fields that were silently wrong (multi-FK ambiguity) surface as actionable classifier errors rather than runtime surprises.

## References

- Error messages reused verbatim from `GraphitronSchemaValidator.validateNodeIdReferenceField` (lines 340-354).
- Canonical explicit-`@reference` fixtures (for patterning new tests): `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/SplitTableFieldPipelineTest.java`, `graphitron-rewrite-test/graphitron-rewrite-test-spec/src/main/resources/graphql/schema.graphqls:116`.
- Closes the empty-`joinPath` stub arms in `SplitRowsMethodEmitter` — the last remaining gap in Phase 2b C1/C2 scope beyond `CARDINALITY` (single-cardinality) and `CONDITION_JOIN` (classification-vocabulary item 5).
