---
title: "Stub #3: Interface / union fetchers"
status: In Progress
bucket: stubs
priority: 1
theme: interface-union
depends-on: []
---

# Stub #3: Interface / union fetchers

Track A (`TableInterfaceType` variants) is **done**, pending the cleanup items listed below. Track B (multi-table polymorphic variants) remains and requires a design decision before any code can be written.

The companion cleanup item (`typeresolver-wiring-interface-union.md`) is absorbed here. Track A closes it for `TableInterfaceType`; Track B closes it for `InterfaceType` / `UnionType`.

Priority number `#3` must stay stable: it is embedded in emitted reason strings consumed by existing schema authors.

---

## Track A cleanup

All six items identified in the post-review are now resolved (2026-04-27).

1. **`orderBy` in child-field list branch** — fixed. `buildTableInterfaceFieldFetcher` now calls `buildOrderByCode` and emits `.orderBy(orderBy)` in the `isList` branch.
2. **Multi-hop / ConditionJoin as classification error** — fixed. `FieldBuilder` now validates single-hop `FkJoin` at classification time via `validateSingleHopFkJoin()` and returns `UnclassifiedField(AUTHOR_ERROR)` instead of a runtime throw.
3. **Execution tests** — added `allContent_returnsAllRowsWithTypeName`, `allContent_typeRouting_filmContentHasLength`, `allContent_onlyReturnsKnownDiscriminatorValues`, `filmContent_singleValue_routesToFilmContent`, `filmContent_filmWithNoContent_returnsNull` to `GraphQLQueryTest`.
4. **Unit tests for the two new fetcher methods** — added structural tests (`_list_returnsResultRecord`, `_single_returnsRecord`, `_hasEnvParameter`, `_isPublicStatic`, `_discriminatorFilter_*`) to `TypeFetcherGeneratorTest`.
5. **Unit tests for TypeResolver emission** — added five tests to `GraphitronSchemaClassGeneratorTest` covering presence, discriminator-value mapping, column name, empty-values skip, and alphabetical order.
6. **Duplicate Javadoc on `buildJoinPathCondition`** — removed.

Two additional bugs found and fixed during testing:

- **Missing discriminator-value WHERE filter** (gap A from SQL comparison): `buildDiscriminatorFilter(col, values)` added to both `buildQueryTableInterfaceFieldFetcher` and `buildTableInterfaceFieldFetcher`. Both records now carry `List<String> knownDiscriminatorValues` extracted from `tit.participants()` at classification time. Test `allContent_onlyReturnsKnownDiscriminatorValues` exercises this path.
- **SQL column name casing** (runtime bug): `TypeBuilder.buildTableInterfaceType` now resolves the `@discriminate(on: ...)` value via `JooqCatalog.findColumn` (case-insensitive) and stores the SQL column name (e.g. `content_type`). `buildJoinPathCondition` now uses `ColumnRef.sqlName()` instead of `javaName()` for FK column references. Both fixes are required for correct PostgreSQL execution.

---

## SQL comparison with the legacy implementation

The legacy single-table interface generator (`FetchSingleTableInterfaceDBMethodGenerator`) was developed with significant care. Comparing its expected output (pinned in `queries/fetch/interfaces/singleTableInterface/*/expected/QueryDBQueries.java`) against the rewrite's generated code reveals three material differences.

### A. Missing discriminator-value filter (correctness gap)

The legacy always emits a WHERE clause restricting to known discriminator values:

```java
// Legacy — always present
.where(_a_address.DISTRICT.in("ONE", "TWO"))
```

This means a row whose discriminator column holds an unknown value (e.g. a future `'TRAILER'` type added to the DB but not yet in the schema) is silently excluded rather than surfacing as a null entry.

The rewrite emits no such guard. A row with an unknown discriminator value hits the TypeResolver's `default -> null` arm, which causes graphql-java to include a `null` element in a list result or return `null` for a single-value field, with no developer-visible error.

**Fix**: `buildQueryTableInterfaceFieldFetcher` and `buildTableInterfaceFieldFetcher` should emit an additional WHERE condition filtering to the set of known discriminator values extracted from `tit.participants()`. Concretely, collect the discriminator values from all `ParticipantRef.TableBound` entries and emit:

```java
.where(condition.and(DSL.field(DSL.name(discriminatorColumn)).in(knownValues)))
```

This parallels what the legacy does and makes the "unknown discriminator" case fail loudly (no rows returned) rather than silently (null in result).

The TypeResolver's `default -> null` arm should be changed to throw a `RuntimeException` (matching the legacy's behavior), or the above WHERE clause makes it unreachable for well-formed data.

### B. Child-field list variant silently discards `orderBy` (non-determinism)

The rewrite's PK-based default ordering is correct for root queries. `FieldBuilder.resolveDefaultOrderSpec()` falls back to `OrderBySpec.Fixed([pk ASC])` when no `@defaultOrder` or `@orderBy` is present, and `buildQueryTableInterfaceFieldFetcher` calls `buildOrderByCode()` so the ORDER BY is emitted — matching the legacy.

The bug is in `buildTableInterfaceFieldFetcher` only: it never calls `buildOrderByCode` at all, so `tif.orderBy()` is silently discarded even when it carries a non-empty `Fixed` spec. A list-cardinality `TableInterfaceField` will produce an unordered result regardless of what the classifier resolved. The current fixture avoids this only because `filmContent` is declared as a single-value field; a list-cardinality interface child field would be non-deterministic.

**Fix**: add `buildOrderByCode(tif.orderBy(), tif.name(), tableLocal)` before the DSL chain in the `isList` branch, and `.orderBy(orderBy)` in the query (already documented as cleanup item #1 above).

### C. `asterisk()` instead of `$fields` — an anti-pattern, not a minor style difference

The regular `buildQueryTableFetcher` uses selection-set-aware projection:

```java
// Regular table fetcher — selection-set aware, only fetches requested columns
.select(Film.$fields(env.getSelectionSet(), table, env))
```

`TypeClassGenerator.generate()` produces a `$fields(sel, table, env)` method for every `TableType` and `NodeType` in the schema. Each method inspects the GraphQL selection set at runtime and returns only the columns actually requested. A query for `{ films { title } }` fetches only the `title` column; a query for `{ films { title rentalRate } }` fetches both.

The interface fetchers ignore the selection set entirely:

```java
// Interface fetcher — asterisk, fetches every column unconditionally
.select(contentTable.asterisk(), DSL.field(DSL.name("CONTENT_TYPE")))
```

Every request for `allContent` or `filmContent` fetches all six columns of the `content` table regardless of which fields the GraphQL client requested. On tables with many or wide columns this is a serious over-fetch. The explicit `DSL.field(DSL.name("CONTENT_TYPE"))` also duplicates the discriminator column, which is already included in `asterisk()`.

The root cause is that `TypeClassGenerator` does not generate a `$fields` method for `TableInterfaceType` entries. The concrete participants (`FilmContent`, `ShortContent`) each get their own `$fields`, but the interface fetcher doesn't know at code-generation time which participant will be returned for any given row, so it cannot call a single participant's `$fields`.

**Fix options (choose one):**

1. **Emit a combined `$fields` on the interface type class.** Add `TableInterfaceType` to `TypeClassGenerator.generate()`. The generated `$fields` method takes the union of all participant field sets based on the selection set, including concrete-type inline fragments (`... on FilmContent { length }`). This mirrors the legacy's explicit select-list approach and fully restores selection-set awareness.

2. **Union participant `$fields` calls at the call site.** At code-generation time, enumerate the participant types and emit code that calls each participant's `$fields` and unions the results, deduplicating on column identity. More verbose but avoids adding a new generated class.

Either option must also ensure the discriminator column is always included in the SELECT list (it is not necessarily covered by the selection set).

---

## Track B: Multi-table polymorphic

**Variants:** `QueryField.QueryInterfaceField`, `QueryField.QueryUnionField`, `ChildField.InterfaceField`, `ChildField.UnionField`

All four carry only `PolymorphicReturnType` — no table, no `MethodRef`. The classifier produces them when the return type is a multi-table `InterfaceType` or `UnionType` and no `@service` / `@tableMethod` is present.

### Design decision (resolve before coding)

Without a table binding or a method reference, there is no SQL for Graphitron to generate. Two options:

**Option 1 — Reject at classification.** These variants become `UnclassifiedField` when the field has no `@service`. The error message should say: "Multi-table interface/union fields require `@service` for developer-supplied dispatch, or `@table @discriminate` on the interface type for single-table polymorphism." The four variants disappear from the model; their `NOT_IMPLEMENTED_REASONS` entries and `stub(f)` arms are removed.

**Option 2 — Keep variants, improve the stub message.** The fetcher body emits a better `UnsupportedOperationException` message pointing to the two options above. Structurally the same as today but more actionable.

Recommendation: **Option 1.** Consistent with how the classifier already rejects other unsupported patterns. The model variants (`QueryInterfaceField`, etc.) may survive as separate classified paths if `@service` + interface-return becomes an explicit classification in the future.

### TypeResolver wiring for `InterfaceType` / `UnionType`

Even under Option 1, `InterfaceType` and `UnionType` exist in the schema (e.g. the `Node` interface, or user types whose fields are all `@service`-backed). Their TypeResolvers still need wiring.

Pattern: emit `codeRegistry.typeResolver("<Name>", env -> { ... })` for each non-`Node` `InterfaceType` and `UnionType` in `schema.types()`. Use the `__typename` convention: the resolver reads `record.get("__typename")` (same contract as `QueryNodeFetcher.registerTypeResolver`) and calls `env.getSchema().getObjectType(value)`. Document this as a required contract for `@service` methods returning multi-table interface / union types (see `graphitroncontext-extension-point-docs.md`).

---

## Order and gating

Track A is done. Track B's design decision (Option 1 vs. Option 2 above) is a prerequisite for any Track B code.

---

## Non-goals

- Per-participant sub-queries for multi-table interface fetchers without `@service` (requires a new directive or classification path).
- `NodeIdReferenceField` JOIN-projection form (tracked separately under Cleanup).
- TypeResolver for the built-in `Node` interface (already wired via `QueryNodeFetcher.registerTypeResolver`).

---

## Changelog

- **2026-04-27** — Track A complete. `QueryTableInterfaceField` and `ChildField.TableInterfaceField` lifted from `NOT_IMPLEMENTED_REASONS` to `IMPLEMENTED_LEAVES`. `discriminatorColumn` added to both records; classifier, `QueryConditionsGenerator`, `TypeFetcherGenerator` (new `buildQueryTableInterfaceFieldFetcher`, `buildTableInterfaceFieldFetcher`, `buildJoinPathCondition`), and `GraphitronSchemaClassGenerator` (TypeResolver wiring) updated. Fixtures: `content` table, `allContent` root query, `Film.filmContent` child field, `Content` / `FilmContent` / `ShortContent` SDL. Review identified six cleanup items (see Track A cleanup section above).
- **2026-04-27** — Track A cleanup complete. All six review items resolved. Added discriminator-value WHERE filter (`buildDiscriminatorFilter`), `knownDiscriminatorValues` on both model records, SQL column name resolution via `JooqCatalog.findColumn` in `TypeBuilder`, FK column SQL-name fix in `buildJoinPathCondition`. Added 18 new unit tests (TypeFetcherGeneratorTest, GraphitronSchemaClassGeneratorTest) and 5 execution tests (GraphQLQueryTest). Track A is fully closed.
