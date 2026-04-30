---
id: R36
title: "Stub #3: Interface / union fetchers"
status: In Progress
bucket: stubs
priority: 1
theme: interface-union
depends-on: []
---

# Stub #3: Interface / union fetchers

Track A is **fully done** (same-table Phase 1 closed 2026-04-27, cross-table Phase 2 closed 2026-04-30). The remaining work is Track B (multi-table polymorphic variants, which requires a design decision before any code can be written), and the supporting prerequisite *NodeId from JSON-encoded row identity*. `TypeResolver` wiring for non-`Node` interface and union types is also covered here as a companion concern: Track A's `TableInterfaceType` already wires it; Track B closes the gap for plain `InterfaceType` / `UnionType`.

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

## Track A — Phase 2: cross-table participant fields

**Shipped 2026-04-30.** Cross-table participant fields land via a new
`ChildField.ParticipantColumnReferenceField` leaf plus a
`ParticipantRef.TableBound.CrossTableField` carrier; the interface fetcher
emits a discriminator-gated LEFT JOIN per occurrence, and the per-field
DataFetcher reads the projected value back from the result `Record` by alias.
See the changelog at the bottom for the file-level breakdown.

### Design (as shipped)

`TypeBuilder.buildParticipantList` walks each participant's GraphQL field
definitions, identifies single-hop `@reference` fields whose terminal table
differs from the interface's own, and stores them on
`ParticipantRef.TableBound.crossTableFields`. `FieldBuilder` then classifies
those fields as `ChildField.ParticipantColumnReferenceField` (carrying the
target column, target table, FK join, and a unique alias name); the field is
listed in `IMPLEMENTED_LEAVES` with no per-field method. Its DataFetcher value
is `new ColumnFetcher<>(DSL.field(DSL.name(aliasName), <columnClass>.class))`,
read off the parent record by alias.

`TypeFetcherGenerator` adds two emission helpers
(`buildCrossTableAliasDeclarations`, `buildCrossTableJoinChain`) reused by
both `buildQueryTableInterfaceFieldFetcher` and `buildTableInterfaceFieldFetcher`.
The chain emission was lifted from a single fluent `dsl.select(...).from(...).where(...).fetch()`
to an intermediate `SelectJoinStep<Record> step` so conditional LEFT JOINs can
be applied between `.from` and `.where`.

```java
Film FilmContent_rating_alias = null;
if (env.getSelectionSet().contains("FilmContent.rating")) {
    FilmContent_rating_alias = Tables.FILM.as("FilmContent_rating");
    fields.add(FilmContent_rating_alias.RATING.as("FilmContent_rating"));
}
SelectJoinStep<Record> step = dsl.select(new ArrayList<>(fields)).from(contentTable);
if (FilmContent_rating_alias != null) {
    step = step.leftJoin(FilmContent_rating_alias).on(
        FilmContent_rating_alias.FILM_ID.eq(contentTable.FILM_ID).and(
            DSL.field(DSL.name("content_type")).eq("FILM")));
}
Result<Record> payload = step.where(condition).orderBy(orderBy).fetch();
```

The selection-set check uses a dot (`Type.field`), not a slash:
graphql-java 25's `DataFetchingFieldSelectionSet` flattens type-conditioned
inline-fragment fields under that key (the slash separator is reserved for
parent/child path nesting). The LEFT JOIN ON clause includes both the FK
column equality and the participant's discriminator value, so non-matching
rows project NULL for the cross-table column and the TypeResolver routes them
back to the right concrete type unaffected.

### Fixture additions (shipped)

1. `short_description varchar(255)` column on `content` (NULL on FILM rows,
   populated on SHORT rows); `ShortContent.description: String
   @field(name: "SHORT_DESCRIPTION")` added to schema.
2. `FilmContent.rating: MpaaRating @reference(path: [{key: "content_film_id_fkey"}])
   @field(name: "RATING")` — the cross-table participant field. (Typed as
   `MpaaRating` rather than the spec's `String`: matches `Film.rating`'s SDL
   shape and exercises the converter path through `ColumnFetcher`'s
   `Field<T>` typing.)

### Tests (shipped)

Unit (`TypeFetcherGeneratorTest`):

- `queryTableInterfaceField_crossTableField_emitsTypeScopedSelectionGuard`:
  body contains the dot-form `env.getSelectionSet().contains("FilmContent.rating")`.
- `queryTableInterfaceField_crossTableField_emitsLeftJoinWithDiscriminatorGate`:
  ON clause has both the FK equality and the discriminator equality.
- `queryTableInterfaceField_crossTableField_aliasedColumnAddedToSelect`:
  `fields.add(...)` projects with the unique alias.
- `queryTableInterfaceField_noCrossTableFields_noLeftJoinEmitted`: regression
  guard for the no-cross-table case.
- `tableInterfaceField_crossTableField_emitsLeftJoinAtChildSite`: same wiring
  applies under `ChildField.TableInterfaceField`.

Classification (`GraphitronSchemaBuilderTest`): new
`ParticipantColumnReferenceFieldCase` enum with two cases — the participant
cross-table field lift, and the same-table fallthrough negative.

Execution (`GraphQLQueryTest`):

- `allContent_crossTableField_joinsFilmAndReturnsRatingForFilmContent`:
  inline-fragment `... on FilmContent { rating }` returns a non-null value for
  every FilmContent row.
- `allContent_crossTableField_leftJoinOmittedWhenNotRequested`,
  `allContent_crossTableField_leftJoinPresentWhenRequested`: SQL_LOG capture
  asserts the LEFT JOIN is gated by the selection set.
- `allContent_filmContentOnly_isolatesLengthFromShortDescription`:
  per-participant column isolation.
- `allContent_allParticipantFieldsTogether_routePerType`: triple-axis projection
  (same-table FilmContent.length, same-table ShortContent.description,
  cross-table FilmContent.rating) all populated correctly.

The implementation is structured so Track B is an extension (same
per-participant LEFT JOIN pattern, different table argument) rather than a
rework.

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

Even under Option 1, `InterfaceType` and `UnionType` exist in the schema (e.g. the `Node` interface, or user types whose fields are all `@service`-backed). Their TypeResolvers still need wiring; today none are emitted, so a runtime resolution against any non-`Node` interface or union would fail with `Can't resolve type for object`. (Track A's `TableInterfaceType` already gets a `TypeResolver` via the `__typename` convention; the gap is for the multi-table cases.)

Pattern: emit `codeRegistry.typeResolver("<Name>", env -> { ... })` for each non-`Node` `InterfaceType` and `UnionType` in `schema.types()`. Use the `__typename` convention: the resolver reads `record.get("__typename")` (same contract as `QueryNodeFetcher.registerTypeResolver`) and calls `env.getSchema().getObjectType(value)`. Document this as a required contract for `@service` methods returning multi-table interface / union types (see `graphitroncontext-extension-point-docs.md`).

### Prerequisite: NodeId from JSON-encoded row identity

A multi-table interface/union fetcher needs to produce a relay nodeId for each
row, but the row's identity arrives as a JSON value rather than a fully
materialised `TableRecord`. The shape mirrors what the legacy multi-table
interface fetcher emits:
`DSL.jsonbArray(DSL.inline("Customer"), CUSTOMER.CUSTOMER_ID)` produces a
`JSONB` value whose first element is the GraphQL type name and whose
remaining elements are the primary-key columns of the row's source table.

Track B (and federation `_entities` over a `@node` type — see
[`federation-via-federation-jvm.md`](federation-via-federation-jvm.md)) need to
lift that JSON value into the corresponding `TableRecord<?>` (using the
typeName to pick the record class, and the remaining JSON elements as
positional column values), then run the lifted record through
`NodeIdEncoder.encode(typeId, pkValues...)`. End result: a fully-formed relay
nodeId that round-trips back through `Query.node` to the same row.

The current rewrite nodeId path
(`generators/util/NodeIdEncoderClassGenerator.java`,
`generators/util/QueryNodeFetcherClassGenerator.java`) assumes the caller
already has `(typeId, pkColumnValues...)` in hand; that works for
fixed-table fetchers but not for polymorphic-at-SQL-time row identity.

Sketch: a new helper on `NodeIdEncoder` (or a sibling `NodeIdJsonRowLifter`)
accepts the JSONB value and returns the encoded ID:

```java
public static String encodeFromJsonRow(JSONB jsonRow) {
    var arr = parseJsonArray(jsonRow);                  // ["Customer", "42"]
    String typeName = arr.getFirst();
    Class<? extends TableRecord<?>> recordClass = registry.recordClassFor(typeName);
    TableRecord<?> rec = lift(recordClass, arr.tail()); // populate PK columns positionally
    return encode(typeIdFor(typeName), pkValuesOf(rec));
}
```

Open questions to resolve when this prerequisite is specced (separately from
Track B's classifier decision):

- **Where does the registry live?** Inline static fields on `NodeIdEncoder`, or a separate generated class so the encoder stays usable in contexts with no jOOQ-record dependency.
- **Type-name vs. type-id in the JSON head.** Legacy uses the GraphQL type name (`"Customer"`); rewrite's `NodeIdEncoder.encode` takes the `typeId` (`__NODE_TYPE_ID`). Confirm direction with the federation plan before locking it in.
- **Null-safety contract.** Match `NodeIdEncoder.encode`: a JSON null in any PK slot returns a null ID, no exception.
- **Composite-key correctness.** Verify the lifter handles tables whose `nodeKeyColumns()` are not a single column; preserve column order in the JSON-array-tail mapping.

Legacy reference shape:
`FetchMultiTableDBMethodGenerator.getPrimaryKeyFieldsArray` —
`graphitron-codegen-parent/.../db/FetchMultiTableDBMethodGenerator.java:411`.

---

## Order and gating

Track A is fully shipped (same-table Phase 1 plus cross-table Phase 2). What remains: Track B's design decision (Option 1 vs. Option 2 above) is a prerequisite for any Track B code; the JSON-row-identity prerequisite ships alongside Track B (or earlier as an independent foundation if federation needs it first).

---

## Non-goals

- Per-participant sub-queries for multi-table interface fetchers without `@service` (requires a new directive or classification path).
- `NodeIdReferenceField` JOIN-projection form (tracked separately under Cleanup).
- TypeResolver for the built-in `Node` interface (already wired via `QueryNodeFetcher.registerTypeResolver`).
- Track A's same-table selection-set projection — closed and shipped; this plan covers only the remaining cross-table follow-up.

---

## Changelog

- **2026-04-27** — Track A complete. `QueryTableInterfaceField` and `ChildField.TableInterfaceField` lifted from `NOT_IMPLEMENTED_REASONS` to `IMPLEMENTED_LEAVES`. `discriminatorColumn` added to both records; classifier, `QueryConditionsGenerator`, `TypeFetcherGenerator` (new `buildQueryTableInterfaceFieldFetcher`, `buildTableInterfaceFieldFetcher`, `buildJoinPathCondition`), and `GraphitronSchemaClassGenerator` (TypeResolver wiring) updated. Fixtures: `content` table, `allContent` root query, `Film.filmContent` child field, `Content` / `FilmContent` / `ShortContent` SDL. Review identified six cleanup items (see Track A cleanup section above).
- **2026-04-27** — Track A cleanup complete. All six review items resolved. Added discriminator-value WHERE filter (`buildDiscriminatorFilter`), `knownDiscriminatorValues` on both model records, SQL column name resolution via `JooqCatalog.findColumn` in `TypeBuilder`, FK column SQL-name fix in `buildJoinPathCondition`. Added 18 new unit tests (TypeFetcherGeneratorTest, GraphitronSchemaClassGeneratorTest) and 5 execution tests (GraphQLQueryTest). Same-table Track A is fully closed.
- **2026-04-30** — Track A Phase 2 (cross-table participant fields) shipped. New `ChildField.ParticipantColumnReferenceField` leaf classified by `FieldBuilder` for single-hop `@reference` fields on `TableInterfaceType` participants whose target differs from the participant's own table. `ParticipantRef.TableBound` extended with `crossTableFields: List<CrossTableField>` populated by `TypeBuilder.extractCrossTableFields`. `TypeFetcherGenerator` emits per-occurrence discriminator-gated LEFT JOINs via two new helpers (`buildCrossTableAliasDeclarations`, `buildCrossTableJoinChain`) and lifts the SQL chain into a `SelectJoinStep<Record> step` variable so JOINs apply between `.from` and `.where`. Per-field DataFetcher value: `ColumnFetcher<>(DSL.field(DSL.name(alias), <columnClass>.class))` reading by alias from the parent record. Selection-set gating uses graphql-java 25's `Type.field` (dot, not slash) form. Fixtures: `short_description` column on `content`, `ShortContent.description`, `FilmContent.rating` via `content_film_id_fkey`. Tests: 5 new unit tests in `TypeFetcherGeneratorTest`, 1 new classification enum (2 cases) in `GraphitronSchemaBuilderTest`, 5 new execution tests in `GraphQLQueryTest`.
