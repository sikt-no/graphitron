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
- **`@reference` on `ColumnReferenceField` / `InputField.ColumnReferenceField`** (`FieldBuilder.java:1771`, `TypeBuilder.java:578`). These sites already require the directive by an outer `if (hasAppliedDirective(DIR_REFERENCE))` guard for classification reasons unrelated to path resolution (presence of `@reference` is what distinguishes a joined-table column from a parent-table scalar). The guards stay.
- **Service reconnect paths** (`FieldBuilder.java:1551, 1675`). These pass `null` source because the path starts from the service return type, not the parent; inference has no anchor and does not fire.
- **`ComputedField` / `TableMethodField` with cross-table `@reference`.** Same-table is the common case (source == target) and inference correctly returns empty for it. Cross-table is rare and if needed users still write `@reference` explicitly.

## Implementation approach

The change is one coherent rule applied at one place (`parsePath`) plus the cleanups that become dead. Implementer decides commit splits.

### 1. Classifier — inference-aware `parsePath`

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java`

Add a `targetSqlTableName` parameter to `parsePath` and **delete the two early returns at lines 400 and 403** (`if (directive == null) return …` and `if (pathArg == null) return …`). Let all cases flow through a single exit with `resolvedElements` initialized empty; both "no `@reference` directive" and "`@reference` with no `path:` arg" now reach the new inference branch with an empty list. The new branch runs **after** the explicit-path error early-return (lines 424-426) so a broken `@reference(path: [...])` surfaces its own error rather than getting masked by an inference attempt:

```
// directive == null → elements stays empty; flow through.
// pathArg  == null → elements stays empty; flow through.
// pathArg present → parse into resolvedElements / errors as today.

if (!errors.isEmpty()) {
    return new ParsedPath(List.of(), String.join("; ", errors));
}
// resolvedElements.isEmpty() covers both triggers uniformly: @reference absent (elements
// stays empty so the parse loop is a no-op) and @reference(path: []) (explicit empty list
// leaves it empty the same way). The equalsIgnoreCase guard skips inference when source == target — the
// ComputedField / TableMethodField same-table case (see §"What we're NOT doing"); case-
// insensitive because JooqCatalog.findForeignKeysBetweenTables itself compares names that
// way (lines 205-223), so SDL vs catalog casing drift is benign.
if (resolvedElements.isEmpty()
        && startSqlTableName != null
        && targetSqlTableName != null
        && !startSqlTableName.equalsIgnoreCase(targetSqlTableName)) {
    var fks = catalog.findForeignKeysBetweenTables(startSqlTableName, targetSqlTableName);
    if (fks.size() == 1) {
        resolvedElements.add(synthesizeFkJoin(fks.get(0), startSqlTableName, fieldName, 0));
    } else {
        return new ParsedPath(List.of(),
            fkCountMessage(startSqlTableName, targetSqlTableName, fks, /*directiveAbsent=*/true));
    }
}
return new ParsedPath(List.copyOf(resolvedElements), null);
```

Lift the `FkJoin` construction from the `tableName.isPresent()` branch of `parsePathElement` (lines 490-517) into a shared helper `synthesizeFkJoin(ForeignKey, String sourceSqlName, String fieldName, int stepIndex)` and call it from both places. The helper resolves source/target `TableRef`s, FK column lists, and the step alias. No `whereFilter` — implicit inference doesn't carry a condition.

**Alias form must match the explicit path's position-0 shape.** Today `parsePathElement` computes the alias as `fieldName + "_" + stepIndex` (see `BuildContext.java:456`), so position 0 of an explicit one-step `@reference` on `Language.films` is `films_0`. The helper must use the same formula. Inline emitters (`JoinPathEmitter.generateAliases`) derive runtime aliases from target-table class names independently, so there's no immediate collision risk today — but keeping `FkJoin.alias` identical between explicit and inferred paths preserves record equality for equivalent shapes and shields any future emitter that consumes `FkJoin.alias()` directly.

Also lift the "0 FKs" / "multiple FKs" error string into a shared helper `fkCountMessage(String source, String target, List<ForeignKey> fks, boolean directiveAbsent)` and call it from **both** inference call sites — the new empty-elements branch in `parsePath` and the existing `tableName.isPresent()` branch in `parsePathElement` (lines 496-504). Both are now the same inference by different triggers and should produce identical user-facing errors. The `directiveAbsent` flag only toggles the "; add a @reference directive…" suffix (redundant when the user already wrote `{table: "..."}`):

- `directiveAbsent = true` (new empty-elements branch): append "; add a @reference directive to specify the join path".
- `directiveAbsent = false` (existing `tableName.isPresent()` branch): omit the suffix; when multiple FKs, keep the existing "— use 'key' to specify which: …" hint that enumerates candidate FK names.

The existing `parsePathElement` call site at line 495 changes from its own inline string-building to a call to `fkCountMessage(...)` with the FK-list parameter so the "use 'key' to specify which: …" enumeration is preserved.

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
| `:1749` — `@nodeId(typeName:)` | NodeIdReferenceField | `targetNodeType.table().tableName()` (line 1744 — `NodeType` is a `TableBackedType`; `.table()` is non-null by `NodeType`'s classification contract) |
| `:1772` — `@reference` scalar | ColumnReferenceField / MultitableReferenceField | `null` (scalar return — no target table to infer TO; the outer `hasAppliedDirective(DIR_REFERENCE)` guard keeps the classifier routing independent of whether a path is supplied) |

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java`

| Call site | Variant | Target arg |
|---|---|---|
| `:579` — input-object `@reference` field | InputField.ColumnReferenceField | `null` (same rationale as `FieldBuilder:1772` — scalar input-object field, no target table) |

**Site :1589 needs a restructure.** The `parsePath` call precedes `ctx.resolveReturnType(elementTypeName, buildWrapper(fieldDef))` at line 1593, so the return-type kind isn't yet classified — we don't know whether to pass a table name or `null`. Fix: swap the order — resolve the return type first, then invoke `parsePath` once with `tb.table().tableName()` when the resolved return type is a `TableBoundReturnType` and `null` otherwise. This is a single-call restructure, not per-arm duplication. `RecordField` classification (non-table return from a `@record` parent) is unaffected because `null` target means inference doesn't fire.

**`NestingField` is covered transitively.** NestingField itself has no join path, but nested `TableField` / `LookupTableField` arms inside a NestingField re-enter the classifier through site :242 (`classifyChildFieldOnTableType`), so they pick up inference automatically — no separate call-site update needed.

### 3. Validator cleanup

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java`

Delete the zero-FK / multi-FK branches from `validateNodeIdReferenceField` (lines 333-356). Keep the `validateReferencePath` / `validateReferenceLeadsToType` calls (lines 358-361) for the explicit-path case — those are independent checks.

After the deletion, `jooqCatalog` is unreferenced. Remove the field and the one-arg constructor; collapse to the existing no-arg constructor. Updates: `GraphQLRewriteGenerator.java:55` (drops the `jooqCatalog` arg) and `FieldValidationTestHelper.java:30` (drops the `new JooqCatalog(...)` arg). `ValidateMojo.java:72` and `StubbedVariantPipelineTest.java:66` already use the no-arg form — no change. Verifies the success criterion "`GraphitronSchemaValidator` no longer references `findForeignKeysBetweenTables`" at the type level, not just the call site.

`NodeIdReferenceField.parentTable` becomes unread after this deletion (grep confirms a single reader in the deleted block). Optional follow-on: drop the record component and the arg at `FieldBuilder.java:1753`. Opportunistic, not required — flag for a separate pass if the implementer doesn't roll it in.

### 4. Emitter cleanup

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java`

Delete the EMPTY_PATH branch in each of the four `unsupportedReason(...)` methods:

- `SplitTableField` — lines 164-168
- `SplitLookupTableField` — lines 207-211
- `RecordTableField` — lines 245-249
- `RecordLookupTableField` — lines 287-291

The CARDINALITY and CONDITION_JOIN branches stay. Post-change, `path.get(0)` in `buildListMethod` (line 343) is guaranteed to be a non-null `FkJoin` by the classifier contract: empty paths are rejected in `parsePath` (inference failure → `UnclassifiedField`), and `CONDITION_JOIN`-first paths are rejected upstream via `JoinPathEmitter.hasConditionJoin` in the same `unsupportedReason` method. Add a one-line invariant comment where `path.get(0)` is dereferenced so a future refactor that introduces a new `JoinStep` subtype doesn't silently break the cast.

Also narrow `FieldBuilder.deriveBatchKeyForResultType`'s compound null-return check (line 1656). The current guard `joinPath.isEmpty() || !(joinPath.get(0) instanceof JoinStep.FkJoin ...)` folds two distinct conditions: empty path (now impossible — classifier contract violated) and condition-first explicit path (still reachable via `@reference(path: [{condition: ...}, ...])` on a `RecordTableField`/`RecordLookupTableField` parent). Split them: throw `IllegalStateException` on empty, keep the `!FkJoin` return-null arm so condition-first paths still produce `UnclassifiedField` at the caller.

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
- `IMPLICIT_REFERENCE_SPLIT_LOOKUP_TABLE` — same shape with `@lookupKey` → `SplitLookupTableField` with one-element `joinPath`. Coverage parity with the other lookup-capable variant; exercises the EMPTY_PATH deletion in `unsupportedReason(SplitLookupTableField)` through the classifier rather than a fixture swap.
- `IMPLICIT_REFERENCE_RECORD_TABLE` — same shape but on a `@record` parent producing `RecordTableField`.
- `IMPLICIT_REFERENCE_RECORD_LOOKUP_TABLE` — same shape with `@lookupKey` on a `@record` parent producing `RecordLookupTableField`. Same parity rationale.
- `IMPLICIT_REFERENCE_NODE_ID_REFERENCE` — replaces `NodeIdReferenceFieldValidationTest.IMPLICIT_SINGLE_FK` at the pipeline level.
- `IMPLICIT_REFERENCE_ZERO_FK` — `Film.actors @splitQuery` (no direct FK between `film` and `actor`) → `UnclassifiedField` with "no foreign key found between tables 'film' and 'actor'…".
- `IMPLICIT_REFERENCE_MULTIPLE_FK` — SDL-only synthetic field `Film.languages: [Language!]!` (the field doesn't exist on the real fixture schema; it's declared in the pipeline test's inline SDL only). The test-fixtures DB already has the two FKs `film.language_id` and `film.original_language_id` both pointing at `language`, so the classifier sees ambiguity → `UnclassifiedField` with "multiple foreign keys found…".

All new pipeline cases assert the error message via substring `contains`, matching the existing `GraphitronSchemaBuilderTest` convention — keeps tests robust to tail-end wording tweaks (the "; add a @reference directive…" suffix) without loosening the signal.

**Pipeline tests — fix existing fixtures that rely on the old empty-path-is-OK behavior:**

- `GraphitronSchemaBuilderTest.Case.SPLIT_QUERY` (line 598) — change to a direct-FK pair, e.g. `type Language @table(name: "language") { films: [Film!]! @splitQuery }`. Alternatively keep `Film.actors` and add the two-hop `@reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])`. Preference: the direct-FK form — it's what the new `IMPLICIT_REFERENCE_SPLIT_TABLE` case tests, and `SplitTableFieldPipelineTest` already covers the two-hop junction shape.
- `GraphitronSchemaBuilderTest.Case.SPLIT_LOOKUP_TABLE_FIELD` (line 609) — same treatment. Pattern: `type Film @table(name: "film") { language(language_id: ID! @lookupKey): Language @splitQuery }` with the single FK `film_language_id_fkey` inferred.

**Execution test — add a path-less `@splitQuery` fixture:**

- `graphitron-rewrite-test/graphitron-rewrite-test-spec/src/main/resources/graphql/schema.graphqls` — drop the `@reference(path: [{key: "film_language_id_fkey"}])` on `Language.films` at line 185. Add or adjust the corresponding execution test (`graphitron-rewrite-test/graphitron-rewrite-test-spec/src/test/...`) to confirm the query returns identical results to the explicit-`@reference` baseline. The existing fixture at line 122 (`actorsBySplitLookup` via two-hop junction) stays explicit — it's the legitimate multi-hop case.

## Success criteria

### Automated

- `mvn test -pl :graphitron-rewrite` passes.
- `mvn test -pl :graphitron-rewrite-test,:graphitron-rewrite-test-fixtures,:graphitron-rewrite-test-spec -Plocal-db` passes (execution test with path-less `@splitQuery` returns matching rows). `-Plocal-db` is required — see CLAUDE.md's fixtures-clobber note; without it the fixtures jar is re-emitted with an empty jOOQ catalog and a cascade of unrelated failures follows.
- Grepping the codebase for the EMPTY_PATH stub messages (`"requires a @reference path"`) returns zero hits.
- `GraphitronSchemaValidator` no longer references `findForeignKeysBetweenTables`.

### Manual

- Running the generator against `sis-graphql-spec` surfaces any schemas that were previously masked by the EMPTY_PATH runtime-stub error but now fail at classify time with the new error messages. This is a breaking change for schemas whose `@reference` was omitted in ambiguous (zero-FK or multi-FK) cases — those now fail classification and require an explicit directive. Fields that relied on accidental single-FK proximity start working; fields that were silently wrong (multi-FK ambiguity) surface as actionable classifier errors rather than runtime surprises.

## References

- Error messages reused verbatim from `GraphitronSchemaValidator.validateNodeIdReferenceField` (lines 340-354).
- Canonical explicit-`@reference` fixtures (for patterning new tests): `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/SplitTableFieldPipelineTest.java`, `graphitron-rewrite-test/graphitron-rewrite-test-spec/src/main/resources/graphql/schema.graphqls:115-118` (Film.actors — two-hop junction) and `:122-125` (Film.actorsBySplitLookup — two-hop split).
- Closes the empty-`joinPath` stub arms in `SplitRowsMethodEmitter` — the last remaining gap in Phase 2b C1/C2 scope beyond `CARDINALITY` (single-cardinality) and `CONDITION_JOIN` (classification-vocabulary item 5).
