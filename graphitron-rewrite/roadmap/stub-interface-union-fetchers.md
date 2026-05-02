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

Track A is **fully done** (same-table Phase 1 closed 2026-04-27, cross-table Phase 2 closed 2026-04-30). The remaining work is Track B: native multi-table polymorphism for plain `InterfaceType` and `UnionType`. Graphitron generates the SQL itself; no `@service` required. The design is two-stage (narrow UNION ALL of keys plus per-typename batched lookup) and reuses the post-R50 per-typeId VALUES-and-JOIN row-builder. Companion concern: `TypeResolver` wiring for non-`Node` `InterfaceType` / `UnionType`. Track A's `TableInterfaceType` already wires it; Track B closes the gap for the remaining cases.

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

All four carry only `PolymorphicReturnType` plus the participants list, with no inherent table or method reference. The classifier already produces them when the return type is a plain `InterfaceType` (multi-table) or `UnionType`; today they sit in `NOT_IMPLEMENTED_REASONS` as build-time errors. Track B turns them into working leaves: Graphitron generates the SQL natively, no `@service` required.

### Design

Two-stage emission per fetcher:

1. **Stage 1, narrow UNION ALL across participants.** One SQL statement projects `(typename, pk_columns, sort_key)` per branch. The database does `ORDER BY` and `LIMIT` across the union in one shot.
2. **Stage 2, per-typename batched lookup.** Group stage-1 results by `__typename`; for each group, run one typed `WHERE pk IN (<values>)` SELECT against that participant's table using its existing `<Type>.$fields(sel, table, env)`. Reuses the post-R50 per-typeId VALUES-and-JOIN row-builder collapsed in R55.

This keeps stage 1's wire payload narrow (typename plus PK plus sort columns only, fixed width regardless of selection-set depth), and stage 2 returns typed Records that flow through the existing `PropertyDataFetcher` / `ColumnFetcher` machinery unchanged. No JSONB wrapping of data, no NULL-padded superset, no second-stage LEFT-JOIN-per-table dance.

#### Stage 1: narrow union of keys

For each `ParticipantRef.TableBound` participant, emit a SELECT projecting:

- `inline("<Participant>").as("__typename")`: the constant type name, text in every branch.
- The participant's PK columns under stable aliases (`__pk0__`, `__pk1__`, …). Single-PK participants project one column; composite-PK participants project up to `MAX_PK_ARITY` columns. Participants that don't have a PK at every slot NULL-pad with the correct typed cast, but see **scope cuts** below: the v1 plan only supports participants whose PK column lists positionally align without collisions, so most schemas project the same shape per branch with no padding.
- A sort-key column (`__sort__`). Default: the participant's PK (single-column). With an interface-level `@orderBy`, the column expression resolved per participant. **Composite-key sort** projects `DSL.jsonbArray(pk_col_1, pk_col_2, …).as("__sort__")`. JSONB arrays compare element-wise in PostgreSQL, so composite ordering reduces to a single comparable column at no extra Java cost.
- Top-level WHERE clause for `ChildField.InterfaceField` / `UnionField`: the parent's FK condition translated to each branch's WHERE.

Stage 1 is one statement; the planner can use participant-table indexes on the sort key. Pagination (limit / offset, or seek-cursor) attaches to this single statement.

#### Stage 2: typed batched lookup per typename

The Java side groups stage-1 results by `__typename` into `Map<String, List<PkRow>>`. For each non-empty group, the generator emits a per-typename method (or a dispatch table) that runs the existing post-R50 lookup-shape SQL:

```java
var filmRows = dsl.select(Film.$fields(sel, film, env))
    .from(film)
    .where(row(film.FILM_ID).in( /* stage-1 PK rows for "Film" */ ))
    .fetch();
```

Per-typename row-builder is the same shared primitive that R55 collapses across `Query.nodes`, federation `_entities`, and now Track B. The generator reuses (does not duplicate) that emission. Selection-set narrowing works at full strength: only fields under `... on Film { … }` reach `Film.$fields`, and typed reads survive end-to-end.

**Join syntax**: stage-2 emission follows the dispatcher shape `.on(t.PK.eq(input.field("PK", T.class)))`, not `.using(...)`. R55's reviewer pass kept `.on(...)` on the dispatcher to avoid USING collapsing joined columns under `<TypeName>.$fields(env, t, env)` projections (which reference the join key columns themselves). Track B's stage 2 calls the same `<Type>.$fields(...)` per typename and inherits the same constraint, so it reuses `ValuesJoinRowBuilder` with the dispatcher-shape callsite, not the lookup-root `.using(...)` callsite.

#### Merge in stage-1 order

The fetcher iterates stage-1 results in their (sorted, paginated) order; for each `(typename, pk)` it looks up the typed Record from the per-typename result map and assembles the final `Result<Record>`. `__typename` from stage 1 carries through as a column on each typed Record so the TypeResolver routes correctly.

#### TypeResolver wiring

Each non-`Node` `InterfaceType` and `UnionType` gets a TypeResolver in `GraphitronSchemaClassGenerator`. The resolver reads `record.get(DSL.field(DSL.name("__typename")), String.class)` and returns `env.getSchema().getObjectType(value)`, the same `__typename` convention `QueryNodeFetcher.registerTypeResolver` already uses for the `Node` interface. Track A's `TableInterfaceType` resolver path stays unchanged; this lands the missing wiring for the plain-interface and union cases.

### Validation

`GraphitronSchemaValidator.validateInterfaceType` / `validateUnionType` reject schemas that v1 cannot align in stage 1. Defer the more elaborate alignment cases to follow-ups; a clear validation error keeps schema authors from hitting confusing runtime behaviour.

1. **Participants without a PK.** Stage 1 needs row identity per branch. A participant whose `TableRef.primaryKeyColumns` is empty cannot be unioned. Reject with a pointer to declaring `@node(keyColumns: …)` or a real PK on the underlying table.
2. **PK column-name collisions across participants.** When two participants in the same interface or union project PK columns with the same Java name but different SQL types (e.g. one declares `id int4`, another declares `id varchar`), positional alignment in stage 1 silently casts on PostgreSQL's union-resolution rules and produces surprises. Detect at validation, reject with a clear message naming the colliding pair. Collision-free participant sets cover the vast majority of schemas; collision handling (per-slot `cast(... as text)` with stage-2 parsing, or jsonbArray packing) lands as a follow-up.

### Fixture

A multi-table interface fixture using existing tables: `interface Searchable` implemented by `Film @table` and `Actor @table` (single int PKs, no collision). Add `Query.search: [Searchable!]!` for the root case and a child-field fixture for `ChildField.InterfaceField`. A parallel union fixture (`union Document = Film | Actor`) covers `UnionField`. Composite-key sort exercise piggybacks on the existing `bar` table with two key columns (used by the rewrite's NodeId tests); declare a third participant in the search interface with composite PK to force the jsonbArray sort path.

### Tests

Unit (`TypeFetcherGeneratorTest`):

- `_emitsTwoStageStructure`: body contains both a UNION ALL stage-1 emission and a per-typename stage-2 lookup call.
- `_stage1ProjectsTypenameAndPks`: stage-1 SELECT shape, `__typename` constant per branch, `__pk0__`..`__pkN__` typed.
- `_stage1SortKeyForCompositePk_emitsJsonbArray`: composite-PK participant projects `DSL.jsonbArray(...).as("__sort__")`.
- `_perTypenameLookup_callsParticipantFields`: stage-2 calls each participant's `$fields(sel, <participantTable>, env)`.
- `_childInterfaceField_emitsParentFkConditionPerBranch`: `ChildField.InterfaceField` adds the parent FK to each branch's WHERE.

Classification (`GraphitronSchemaBuilderTest`):

- New cases asserting `QueryField.QueryInterfaceField`, `QueryField.QueryUnionField`, `ChildField.InterfaceField`, `ChildField.UnionField` are produced and lifted out of `NOT_IMPLEMENTED_REASONS`.
- Validation rejection cases: PK-less participant, PK column-name collision.

Schema-class (`GraphitronSchemaClassGeneratorTest`): TypeResolver presence and `__typename` resolution path for non-`Node` `InterfaceType` and `UnionType`.

Execution (`GraphQLQueryTest`):

- `search_returnsAllParticipantTypes`: stage 1 + stage 2 produces concatenated rows; TypeResolver routes per row.
- `search_orderedByPkAcrossParticipants`: ORDER BY runs DB-side; results interleave correctly.
- `search_compositePkParticipant_jsonbArraySorts`: a participant with composite PK orders correctly alongside single-PK participants.
- `search_paginatedByLimit`: LIMIT N applies database-side; exactly N rows returned.
- `search_emptyResult_noStage2Roundtrips`: stage 1 returns 0 rows; no stage-2 queries fire (SQL_LOG capture).

### Order and sub-phases

- **B1, TypeResolver wiring** for non-`Node` `InterfaceType` / `UnionType` — shipped 2026-05-01.
- **B2a, structural emitter** — shipped 2026-05-01. Stage-1 narrow UNION ALL projecting `(__typename, __pk0..N, __sort)` per branch; stage-2 per-typename dispatch via `ValuesJoinRowBuilder` with the dispatcher-shape `.on(...)` join. Includes field-level PK-presence and PK-arity-match validation, lift from `NOT_IMPLEMENTED_REASONS` to `IMPLEMENTED_LEAVES`, and unit tests pinning the two-stage shape. Composite-PK jsonbArray sort emitted but not yet exercised through fixture.
- **B2b, multi-table fixture and execution tests** — shipped 2026-05-01. `interface Searchable` (Film/Actor) plus `union Document = Film | Actor`; both wired to root `Query.search` and `Query.documents`. The synthetic `Searchable.name` interface field remaps to `FILM.TITLE` / `ACTOR.FIRST_NAME` per implementer so each row's typed Record carries the right column under the shared interface contract. `GraphQLQueryTest` covers row counts, `__typename` routing, inline-fragment per-type field resolution, the union-variant equivalence, and ORDER BY-by-sort-key interleaving. Composite-PK third participant (jsonbArray sort exercise via `bar` table) deferred to a follow-up.
- **B3, `ChildField.InterfaceField` / `ChildField.UnionField`** — shipped 2026-05-01. `MultiTablePolymorphicEmitter` gains a child-fetcher overload accepting a typename-keyed `Map<String, List<JoinStep>>` of per-participant FK chains (auto-discovered by `FieldBuilder` via `ctx.parsePath(field, name, parentTable, participantTable)`); the main fetcher opens with `Record parentRecord = (Record) env.getSource()` and each stage-1 branch carries `.where(<participant>.<fk>.eq(parentRecord.get(DSL.name("<parent_pk>"), <Type>.class)))`. The typed `parentRecord.get(Name, Class)` form is required so jOOQ's typed `Field<T>.eq(T)` overload selects without an unchecked cast. Fixture: `union AddressOccupant = Customer | Staff` plus `Address.occupants: [AddressOccupant!]!`. Execution tests pin row counts, mixed-type return, homogeneous-address-skips-other-branch, and per-parent FK scoping across multiple addresses.
- **B4a, root-case connection pagination** — shipped 2026-05-01. `MultiTablePolymorphicEmitter` gains `emitConnectionMethods` plus a private `buildConnectionFetcher` overload returning `DataFetcherResult<ConnectionResult>`. Stage 1 wraps the per-branch UNION ALL in a derived table {@code pages} so the outer query can apply `.orderBy(page.effectiveOrderBy()).seek(page.seekFields()).limit(page.limit())` from a `ConnectionHelper.PageRequest`; stage 2's per-typename helper additionally projects the participant PK aliased as `__sort__` so `ConnectionHelper.encodeCursor` can read the sort key off each typed Record. Composite ordering uses `(sortField ASC, tieField ASC)` where `tieField = DSL.field(DSL.name("__typename"), String.class)` so rows tied on PK across participants (e.g. Film(1) and Actor(1)) order deterministically and cursor seek resolves boundary ties consistently across pages. `TypeFetcherGenerator` switch arms for `QueryInterfaceField` and `QueryUnionField` dispatch to `emitConnectionMethods` when the field's wrapper is `FieldWrapper.Connection`. Validator `validateMultiTableConnectionConstraints` rejects participants with PK arity != 1 in connection mode (the synthetic `__sort__` column is typed as the participant PK class and composite-PK cursor encoding via JSONB is not yet covered). `ConnectionResult` is constructed with `(table, condition) = (null, null)` so `totalCount` returns null on this path until B4c lands the polymorphic count query. Fixture: `Query.searchConnection: [Searchable!]! @asConnection(defaultFirstValue: 5)` plus the union-variant `Query.documentsConnection`. Tests: 8 new unit tests in `TypeFetcherGeneratorTest` (return type, pageRequest call, derived-table wrapping, seek/limit chain, per-typename `__sort__` projection, `ConnectionResult` wrapping with null table/condition, typename tiebreaker, union-variant parity); 7 new execution tests in `GraphQLQueryTest` (first-page, default page size of 5 of 8, after-cursor next page, last-page hasNextPage=false, inline-fragment per-type resolution on connection path, union variant equivalence, totalCount returns null until B4c).
- **B4b, totalCount via UNION-ALL count subquery** — shipped 2026-05-02. The connection emitter lifts the per-branch UNION ALL to a local `Table<?> pagesTable` variable so the same derived-table reference backs both the page query (`.from(pagesTable).orderBy().seek().limit()`) and `ConnectionResult.table()`. The carrier now passes `(pagesTable, DSL.noCondition())` instead of `(null, null)`, and the existing `ConnectionHelper.totalCount` resolver runs `dsl.selectCount().from(cr.table()).where(cr.condition()).fetchOne(0, Integer.class)` unchanged — emitting `SELECT count(*) FROM (UNION ALL Film + Actor) AS pages`. The resolver remains lazy on selection: graphql-java only invokes it when the client picks `totalCount`. Per-branch WHERE predicates (B4c child case, when wired) live inside the union, so the outer carrier condition is a no-op. The empty-participants defensive path keeps `(null, null)` since there is no derived table to count. Tests: 2 updated unit tests in `TypeFetcherGeneratorTest` (`_wrapsResultInConnectionResult` and `_emitsSameShapeAsInterfaceField` now assert `(payload, page, pagesTable, org.jooq.impl.DSL.noCondition())`); 1 new unit test (`_liftsUnionAllAsTableLocal`) pinning the `Table<?> pagesTable` materialization plus `.from(pagesTable)` reference shape; 4 new execution tests in `GraphQLQueryTest` (totalCount returns 8 across Film+Actor; independence from after-cursor; lazy on selection — no count SQL when unselected; one count SQL when selected; union-variant `documentsConnection` parity). The renamed `searchConnection_totalCount_returnsNullUntilB4c` test (B4a's pinned-null contract) becomes `searchConnection_totalCount_returnsTotalRowCountAcrossParticipants`.
- **B4c-1, per-parent inline child connection** — shipped 2026-05-02, then promoted to B4c-2. `ChildField.InterfaceField` / `ChildField.UnionField` + `@asConnection` originally landed as per-parent inline (each parent invocation ran its own polymorphic UNION ALL with `pagesTable`, `.orderBy/.seek/.limit`, and a per-parent `totalCount`). Superseded by B4c-2 the same day; the B4c-1 emission path is no longer wired but the validator constraints (single-PK participants for connection mode) and fixture (`Address.occupantsConnection`) remain.
- **B4c-2, DataLoader-batched windowed CTE** — shipped 2026-05-02. `MultiTablePolymorphicEmitter.emitConnectionMethods` now takes a `TableRef parentTable` parameter; when non-null and `participantJoinPaths` is non-empty, dispatches to `buildBatchedConnectionFetcher` (DataLoader-registering main fetcher) plus `buildBatchedConnectionRowsMethod` (windowed CTE rows method returning `List<ConnectionResult>`). The rows method builds a typed `parentInput VALUES (idx, parent_pk)` table, runs ONE polymorphic UNION-ALL with `JOIN parentInput` per branch projecting `(typename, pk0, sort, idx)`, wraps it in a `ranked` CTE with `ROW_NUMBER() OVER (PARTITION BY __idx__ ORDER BY page.effectiveOrderBy())`, and filters `__rn__ <= page.limit()` on the outer SELECT for per-partition limit. Stage 2 dispatches per typename (reusing `buildPerTypenameSelect`) and writes typed Records to `result[outerIdx]`; a parallel `int[] parentIdxByOuter` populated from each stage-1 row's `__idx__` scatters typed Records into per-parent `List<Record>` buckets. Each per-parent `ConnectionResult` re-uses the shared `pagesTable` reference with a per-parent condition `idxField.eq(i)` so `totalCount` resolves to the parent's UNION-ALL row count via `SELECT count(*) FROM pagesTable WHERE __idx__ = :i` — preserves B4c-1's parent-scoped totalCount while batching the page-rows query (1 query for all parents instead of N). v1 supports single-column parent PK (`Row1<PkClass>`); multi-column widens to `RowN`. `TypeFetcherGenerator` dispatch passes the enclosing type's `parentTable` through to `emitConnectionMethods`. Tests: 6 unit tests in `TypeFetcherGeneratorTest` rewritten and extended (DataLoader registration, batched rows method shape, per-branch JOIN parentInput, ranked CTE + ROW_NUMBER, per-parent ConnectionResult with `idxField.eq(i)`, union variant parity); existing 4 execution tests in `GraphQLQueryTest` (`addressOccupantsConnection_*`) continue to pass unchanged — pinning that the B4c-1 result contract (parent-scoped rows + parent-scoped totalCount + cursor pagination + inline-fragment routing) survives the batched promotion.

R55 (`entityfetcherdispatch-lookup-pipeline-collapse`) shipped 2026-05-01, extracting `ValuesJoinRowBuilder` from the two existing duplicate sites (`LookupValuesJoinEmitter` and `EntityFetcherDispatchClassGenerator`); B2's stage-2 emission is the third consumer and reuses (does not fork) that primitive.

### Non-goals (Track B v1)

- Mixed PK arity / type across participants. Validation rejects; collision-handling follow-up.
- Per-participant SQL-level filtering directives beyond what the schema model already exposes (`@condition` on the field carries through trivially via stage-1 WHERE).
- Stage-1-as-CTE optimisation (rendering `WITH keys AS (...)`). The straight UNION ALL form is sufficient until profiling shows otherwise.
- Mixing this story with NodeId encoding for relay round-trip. Track B does not produce relay nodeIds inside stage 1. Per-field `@nodeId` projections on participants encode in stage 2's typed Record path using the existing `NodeIdEncoder.encode(typeId, pkValues...)`. No JSON-row-identity helper.

---

## Order and gating

Track A is fully shipped (same-table Phase 1 plus cross-table Phase 2). R55's shared row-builder collapse shipped 2026-05-01 and unblocks B2. Track B's sub-phases B1-B4 are listed in the Track B section above; B1 is the smallest standalone deliverable and lands first.

---

## Non-goals

- `NodeIdReferenceField` JOIN-projection form (tracked separately under R50 follow-ups).
- TypeResolver for the built-in `Node` interface (already wired via `QueryNodeFetcher.registerTypeResolver`).
- Track A's same-table and cross-table emission, closed and shipped; this plan now covers only Track B's multi-table polymorphism.
- NodeId-from-JSON-encoded-row-identity helper. Earlier drafts of this plan listed it as a Track B prerequisite mirroring legacy's `jsonbArray('Customer', CUSTOMER_ID)` row-identity column. The two-stage design (narrow union of typed PKs plus per-typename batched lookup) makes it unnecessary: identity flows as typed PK columns through stage 1 and as typed Records through stage 2; relay nodeId encoding for individual rows happens at the per-field level using the same `NodeIdEncoder.encode(typeId, pkValues...)` everything else uses.

---

## Changelog

- **2026-04-27** — Track A complete. `QueryTableInterfaceField` and `ChildField.TableInterfaceField` lifted from `NOT_IMPLEMENTED_REASONS` to `IMPLEMENTED_LEAVES`. `discriminatorColumn` added to both records; classifier, `QueryConditionsGenerator`, `TypeFetcherGenerator` (new `buildQueryTableInterfaceFieldFetcher`, `buildTableInterfaceFieldFetcher`, `buildJoinPathCondition`), and `GraphitronSchemaClassGenerator` (TypeResolver wiring) updated. Fixtures: `content` table, `allContent` root query, `Film.filmContent` child field, `Content` / `FilmContent` / `ShortContent` SDL. Review identified six cleanup items (see Track A cleanup section above).
- **2026-04-27** — Track A cleanup complete. All six review items resolved. Added discriminator-value WHERE filter (`buildDiscriminatorFilter`), `knownDiscriminatorValues` on both model records, SQL column name resolution via `JooqCatalog.findColumn` in `TypeBuilder`, FK column SQL-name fix in `buildJoinPathCondition`. Added 18 new unit tests (TypeFetcherGeneratorTest, GraphitronSchemaClassGeneratorTest) and 5 execution tests (GraphQLQueryTest). Same-table Track A is fully closed.
- **2026-04-30** — Track A Phase 2 (cross-table participant fields) shipped. New `ChildField.ParticipantColumnReferenceField` leaf classified by `FieldBuilder` for single-hop `@reference` fields on `TableInterfaceType` participants whose target differs from the participant's own table. `ParticipantRef.TableBound` extended with `crossTableFields: List<CrossTableField>` populated by `TypeBuilder.extractCrossTableFields`. `TypeFetcherGenerator` emits per-occurrence discriminator-gated LEFT JOINs via two new helpers (`buildCrossTableAliasDeclarations`, `buildCrossTableJoinChain`) and lifts the SQL chain into a `SelectJoinStep<Record> step` variable so JOINs apply between `.from` and `.where`. Per-field DataFetcher value: `ColumnFetcher<>(DSL.field(DSL.name(alias), <columnClass>.class))` reading by alias from the parent record. Selection-set gating uses graphql-java 25's `Type.field` (dot, not slash) form. Fixtures: `short_description` column on `content`, `ShortContent.description`, `FilmContent.rating` via `content_film_id_fkey`. Tests: 5 new unit tests in `TypeFetcherGeneratorTest`, 1 new classification enum (2 cases) in `GraphitronSchemaBuilderTest`, 5 new execution tests in `GraphQLQueryTest`.
- **2026-05-01** — Track B design pivot. The previous "Option 1 (reject without `@service`) vs. Option 2 (better stub message)" framing assumed Graphitron could not generate SQL across heterogeneous participant tables without developer-supplied dispatch. That premise was wrong: per-participant `<Type>.$fields(sel, table, env)` plus typed `TableRef.primaryKeyColumns` give us everything we need to emit native SQL. Track B's design is now two-stage: (1) narrow UNION ALL across participants projecting `(typename, pk_columns, sort_key)` so PostgreSQL handles ORDER BY and LIMIT in one statement; (2) per-typename batched lookup using the post-R50 VALUES-and-JOIN row-builder (shared with `Query.nodes` and federation `_entities`, collapsed in R55). Composite-key sort uses `DSL.jsonbArray(...)` for element-wise comparison. NodeId-from-JSON-row-identity is no longer a prerequisite: typed PKs flow through stage 1, typed Records through stage 2, relay nodeId encoding remains at the per-field level. Validation rejects PK collisions and PK-less participants for v1; collision-handling deferred. Sub-phases B1 (TypeResolver wiring), B2 (root case), B3 (child case), B4 (connection pagination).
- **2026-05-01** — B1 shipped. TypeResolver wiring for non-`Node` `InterfaceType` / `UnionType` lands in `GraphitronSchemaClassGenerator` after the existing `TableInterfaceType` block; iterates `schema.types()` for the two plain variants, sorts alphabetically, skips `Node` (delegated to `QueryNodeFetcher.registerTypeResolver`). Reads the synthetic `__typename` column projected by R36 Track B's stage-1 emitter via `record.get(DSL.field(DSL.name("__typename")), String.class)`. Tests: 7 new in `GraphitronSchemaClassGeneratorTest` covering presence, DSL.field-based read shape, union variant, Node no-double-register, no-Node-type skip, and alphabetical ordering relative to `schemaBuilder.build()`.
- **2026-05-01** — B2a shipped. New `MultiTablePolymorphicEmitter` emits the two-stage fetcher methods for `QueryField.QueryInterfaceField` and `QueryField.QueryUnionField`: stage-1 builds `dsl.select(DSL.inline("<TypeName>").as("__typename"), <table>.<pk>.as("__pk0__"), ..., <sortKey>.as("__sort__")).from(<table>)` per branch glued by `.unionAll(...)` and `.orderBy(DSL.field(DSL.name("__sort__"))).fetch()`; stage-2 emits one `select<Participant>For<Field>` private static helper per typename mirroring `SelectMethodBody`'s dispatcher shape (typed `Row<N+1>[]` array via `ValuesJoinRowBuilder.emitRowArrayDecl`/`cellsCode`/`aliasArgs`, explicit per-PK-slot `t.<col>.eq(input.field("<col>", T.class))` ON predicate, idx-ordered scatter into the shared `Object[] result`). Single-PK participants project the PK column directly as `__sort__`; composite-PK participants project `DSL.jsonbArray(...)` so PostgreSQL element-wise comparison handles the sort. Records carry `DSL.inline("<TypeName>").as("__typename")` so the schema-class TypeResolver routes each row back to its concrete GraphQL type. `QueryField.QueryInterfaceField` and `QueryField.QueryUnionField` records extended with `List<ParticipantRef> participants` (populated by `FieldBuilder` from the resolved `InterfaceType` / `UnionType`). Validator gains field-level (not type-level) PK-presence and PK-arity rejection in `validateMultiTableParticipants`, scoped to the field's own qualified name so the `Node` interface (heterogeneous PK arities, dispatched via `QueryNodeFetcher`) does not trip false rejections. `QueryInterfaceField` and `QueryUnionField` lifted from `NOT_IMPLEMENTED_REASONS` to `IMPLEMENTED_LEAVES`. Tests: 12 new in `TypeFetcherGeneratorTest` (return type, two-stage structure, per-branch projection, dispatcher-shape `.on(...)`, jsonbArray sort, parity between interface and union variants, partition membership); 6 in `QueryInterfaceFieldValidationTest` and `QueryUnionFieldValidationTest` (well-formed cases plus PK-presence and PK-arity rejection cases). The multi-table fixture and `GraphQLQueryTest` execution cases land in B2b.
- **2026-05-01** — B3 shipped. `ChildField.InterfaceField` and `ChildField.UnionField` lifted from `NOT_IMPLEMENTED_REASONS` to `IMPLEMENTED_LEAVES`. Records extended with `participants: List<ParticipantRef>` and `participantJoinPaths: Map<String, List<JoinStep>>`. `FieldBuilder` resolves the per-participant join paths via `ctx.parsePath` per `(parentTable, participantTable)` pair so heterogeneous participants each pick up their own FK auto-discovery; explicit `@reference` paths would apply ambiguously across the participant set and are not supported in v1. `MultiTablePolymorphicEmitter` gains a second `emitMethods` overload taking `participantJoinPaths`; when non-empty the main fetcher reads `parentRecord` from `env.getSource()` and each stage-1 branch carries a `.where(<participant>.<fk_col>.eq(parentRecord.get(DSL.name("<parent_pk_col>"), <ColumnClass>.class)))` predicate. Validator's existing `validateMultiTableParticipants` now wires for child variants too. Fixture: `union AddressOccupant = Customer | Staff` plus `Address.occupants: [AddressOccupant!]!` exercising auto-discovered FKs `customer_address_id_fkey` and `staff_address_id_fkey`. Tests: 4 new in `TypeFetcherGeneratorTest` (parentRecord read shape, per-branch parentFk WHERE, child interface/union parity, partition membership), 4 in `InterfaceFieldValidationTest` and `UnionFieldValidationTest` (well-formed plus PK-presence rejection cases), 3 in `GraphQLQueryTest` (mixed-type return, homogeneous-address-other-branch-skipped, per-parent-FK scoping). Two existing classifier tests in `GraphitronSchemaBuilderTest` updated to use unambiguous-FK SDL (Customer→Address single FK; FilmActor→{Film,Actor} via film_actor's pair of FKs) so the now-required FK auto-discovery resolves cleanly.
- **2026-05-01** — B2b shipped. Multi-table fixture lands in `graphitron-test`: `interface Searchable { name: String! }` implemented by Film and Actor, plus `union Document = Film | Actor`. Both back `Query.search: [Searchable!]!` and `Query.documents: [Document!]!`. The interface field `Searchable.name` remaps to each implementer's distinct concrete column (`FILM.TITLE` for Film, `ACTOR.FIRST_NAME` for Actor) so the per-typename stage-2 SELECT projects the right column per branch. Stage-1 union-of-typed-projections required widening the declared `Result` type from `Result<Record>` to `Result<? extends Record>`: jOOQ infers `Result<Record3<...>>` (one type-arg per projected column) from the `dsl.select(typename, pk, sort)` shape, and the wildcard upper bound is the loosest assignment that still exposes Record-typed `size()`/`get(int)` for the dispatch loop. Five new `GraphQLQueryTest` execution cases drive the path against PostgreSQL: row counts, `__typename` routing, inline-fragment per-type field resolution, union-variant equivalence, and ORDER BY-by-sort-key interleaving. Composite-PK third participant (jsonbArray sort exercise) deferred to a follow-up; the emission path is already in place under `MultiTablePolymorphicEmitter`'s `branchProjection` (size > 1 branch) but no fixture exercises it end-to-end yet.
- **2026-05-01** — B4a shipped. Root-case connection pagination on `QueryInterfaceField` / `QueryUnionField`. `MultiTablePolymorphicEmitter.emitConnectionMethods` plus a private `buildConnectionFetcher` overload return `DataFetcherResult<ConnectionResult>`: stage 1 wraps the per-branch UNION ALL in a derived table {@code pages} so `.orderBy(page.effectiveOrderBy()).seek(page.seekFields()).limit(page.limit())` (from a `ConnectionHelper.PageRequest`) apply uniformly across the union. Stage 2's per-typename helper additionally projects the participant PK aliased as `__sort__` on each typed Record so `ConnectionHelper.encodeCursor`'s `record.get(orderByColumns)` lookup finds the sort key. The emitter appends `__typename ASC` as a secondary ORDER BY and seek tiebreaker so rows tied on PK across participants order deterministically and cursor seek resolves boundary ties consistently. `TypeFetcherGenerator` dispatches to `emitConnectionMethods` when the field wrapper is `FieldWrapper.Connection`. `GraphitronSchemaValidator.validateMultiTableConnectionConstraints` rejects connection mode for composite-PK participants in v1 (single-PK only — the synthetic `__sort__` column is typed as the participant PK class and JSONB cursor round-trip is not yet covered). `ConnectionResult` is constructed with `(table, condition) = (null, null)` so the `totalCount` resolver short-circuits to null until B4b lands the polymorphic count query. Fixture: `Query.searchConnection: [Searchable!]! @asConnection(defaultFirstValue: 5)` and `Query.documentsConnection` for the union variant. Tests: 8 new unit tests in `TypeFetcherGeneratorTest` (return type, ConnectionHelper.pageRequest call, .asTable("pages") wrapping, .seek/.limit chain, per-typename `__sort__` projection, `new ConnectionResult(payload, page, null, null)` shape, typename tiebreaker, union-variant parity); 7 new execution tests in `GraphQLQueryTest` (first-page-N-rows, default page size 5 of 8 total, after-cursor returns next page, full-page over-fetch hasNextPage=false, inline-fragment per-type resolution on connection path, union variant equivalence, totalCount returns null until B4b).
- **2026-05-02** — B4c-1 shipped. Per-parent inline child connection on `ChildField.InterfaceField` / `ChildField.UnionField` + `@asConnection`. New `MultiTablePolymorphicEmitter.emitConnectionMethods` overload taking `Map<String, List<JoinStep>> participantJoinPaths`; `buildConnectionFetcher` opens with `Record parentRecord = (Record) env.getSource()` when the map is non-empty, and `buildStage1ConnectionBlock` applies a per-branch `.where(<participant>.<fk>.eq(parentRecord.get(DSL.name("<parent_pk>"), <Type>.class)))` predicate inside each UNION ALL branch (same shape as B3's list-mode child case via `branchParentFkWhere`). The outer `pagesTable` + `.seek/.limit` + `(pagesTable, DSL.noCondition())` ConnectionResult carrier are reused unchanged from B4a/B4b, so `totalCount` runs `SELECT count(*) FROM (per-parent-scoped UNION ALL) AS pages` and returns only the carrier parent's occupants. `TypeFetcherGenerator` dispatches `ChildField.InterfaceField` / `ChildField.UnionField` with `FieldWrapper.Connection` to the new overload. Validator's existing `validateMultiTableConnectionConstraints` (B4a) already covers child fields. Fixture: `Address.occupantsConnection: [AddressOccupant!]! @asConnection(defaultFirstValue: 5)` reusing the B3 union and auto-discovered FKs (`customer_address_id_fkey`, `staff_address_id_fkey`). Tests: 4 new unit tests in `TypeFetcherGeneratorTest` (parentRecord read shape, per-branch parent-FK WHERE, connection scaffolding parity with B4a/B4b, child interface/union variant equivalence); 4 new execution tests in `GraphQLQueryTest` (`addressOccupantsConnection_returnsParentScopedRows`, `_totalCount_isParentScoped` asserting Address(2) → 3 vs Address(3) → 2, `_paginationWithAfterCursor` exercising `first: 2` then `after`-cursor on Address 2's three occupants, `_inlineFragmentResolvesPerType`). DataLoader-batched windowed CTE form (one query per request) deferred as B4c-2.
- **2026-05-02** — B4c-2 follow-ups (round 1) shipped. (a) Validator-time rejection for multi-column parent PK on child interface/union connection fields: `validateInterfaceField` / `validateUnionField` now take the schema's `Map<String, GraphitronType> types` so the new `validateChildConnectionParentPk` helper can look up the enclosing parent type and reject `@asConnection` when the parent table's PK arity > 1 with an AUTHOR_ERROR (file:line). The codegen-time `IllegalStateException` in `MultiTablePolymorphicEmitter.buildBatchedConnectionFetcher` is downgraded to a defensive tripwire (validator should have caught it). Tests: 2 new in `InterfaceFieldValidationTest` (composite-PK parent rejected; single-PK parent accepted) and 1 new in `UnionFieldValidationTest` (composite-PK parent rejected, union variant). (b) Multi-parent batching ratchet on `addressOccupantsConnection`: new execution test `addressOccupantsConnection_dataLoaderBatchesAcrossParents` queries `customers(active: true) { address { occupantsConnection(first: 1) { ... } } }` and asserts that `SQL_LOG` contains exactly one statement matching `as "parentInput"` — proving the DataLoader collapses the per-branch UNION-ALL page-rows query to ONE invocation regardless of how many parent addresses are involved. Pins the whole point of the B4c-1 → B4c-2 promotion.
- **2026-05-02** — B4c-2 shipped. DataLoader-batched windowed CTE child connection on `ChildField.InterfaceField` / `ChildField.UnionField` + `@asConnection`, promoting B4c-1's per-parent inline shape to one batched query per request. `MultiTablePolymorphicEmitter.emitConnectionMethods` gains a 7-arg overload taking `TableRef parentTable`; when non-null and `participantJoinPaths` is non-empty, dispatches to `buildBatchedConnectionFetcher` (registers a `DataLoader<Row1<PK>, ConnectionResult>` keyed on the parent table's PK and delegates to a `rows<Field>(keys, env)` batch loader) and `buildBatchedConnectionRowsMethod` (windowed CTE rows method). The rows method builds typed `parentInput VALUES (idx, parent_pk)` (`Row2<Integer, ParentPkClass>`), emits a per-branch UNION ALL projecting `(typename, pk0, sort, parentInput.idx as __idx__)` with `JOIN parentInput ON <participant>.<fk> = parentInput.<parent_pk>`, wraps it in `ranked = SELECT *, ROW_NUMBER() OVER (PARTITION BY __idx__ ORDER BY page.effectiveOrderBy()) AS __rn__ FROM pagesTable ORDER BY effectiveOrderBy SEEK seekFields`, and filters `__rn__ <= page.limit()` on the outer SELECT. Stage 2 dispatches per typename via the existing `buildPerTypenameSelect` helper (writes typed Records to `result[outerIdx]`); a parallel `int[] parentIdxByOuter` populated from each stage-1 row's `__idx__` column scatters typed Records into per-parent `List<Record>` buckets. Each per-parent `ConnectionResult` shares the `pagesTable` derived-table reference with a per-parent condition `idxField.eq(i)` (`SELECT count(*) FROM pagesTable WHERE __idx__ = :i`), preserving B4c-1's per-parent totalCount while collapsing the page-rows query from N to 1. `TypeFetcherGenerator`'s `ChildField.InterfaceField` / `ChildField.UnionField` dispatch passes the enclosing type's `parentTable` through; the legacy 5-arg/6-arg overloads delegate with `parentTable=null` so existing callers stay on the per-parent inline path. v1 batched scope: single-column parent PK (`Row1<PkClass>` key element); multi-column widens to `RowN` as a follow-up. Tests: 6 unit tests in `TypeFetcherGeneratorTest` (`childInterfaceField_connection_emitsDataLoaderRegisteringFetcher`, `_emitsBatchedRowsMethod`, `_appliesParentFkJoinPerBranch`, `_emitsRankedCteWithRowNumber`, `_perParentConnectionResultsCarryIdxCondition`, plus union-variant parity) — replaces the four B4c-1 unit tests; existing 4 execution tests in `GraphQLQueryTest` (`addressOccupantsConnection_*`) all still pass unchanged, pinning that the parent-scoped row count, parent-scoped totalCount, after-cursor pagination, and per-type inline-fragment routing all survive the per-parent → batched promotion. Generated SQL verified against PostgreSQL on the Address(2)/Address(3) fixture.
- **2026-05-02** — B4b shipped. Polymorphic `totalCount` for connection-mode multi-table interface and union fetchers. `MultiTablePolymorphicEmitter.buildStage1ConnectionBlock` lifts the per-branch UNION ALL to a local `Table<?> pagesTable` variable so the same derived-table reference backs both the page query (`dsl.select(...).from(pagesTable).orderBy().seek().limit().fetch()`) and the carrier passed onto `ConnectionResult`. The fetcher now constructs `new ConnectionResult(payload, page, pagesTable, DSL.noCondition())` instead of `(null, null)`; the existing `ConnectionHelper.totalCount` resolver runs `dsl.selectCount().from(cr.table()).where(cr.condition()).fetchOne(0, Integer.class)` unchanged, emitting `SELECT count(*) FROM (UNION ALL Film + Actor) AS pages`. The resolver remains lazy on selection — graphql-java only invokes it when the client picks `totalCount`, ratcheted by an SQL_LOG capture test. Per-branch WHEREs (B4c child case, when wired) live inside the union, so the outer carrier condition is a no-op. The empty-participants defensive path keeps `(null, null)` since there is no derived table to count. Tests: 2 updated unit tests (`_wrapsResultInConnectionResult`, `_emitsSameShapeAsInterfaceField`) now assert `(payload, page, pagesTable, org.jooq.impl.DSL.noCondition())`; 1 new unit test (`_liftsUnionAllAsTableLocal`) pins the `Table<?> pagesTable` materialization plus `.from(pagesTable)` reference in the page query; 4 new execution tests in `GraphQLQueryTest` (totalCount returns 8 across Film+Actor, independence from after-cursor, lazy-on-selection no-count-SQL when unselected, exactly-one-count-SQL when selected, union-variant `documentsConnection` parity). The B4a `searchConnection_totalCount_returnsNullUntilB4c` test renamed to `searchConnection_totalCount_returnsTotalRowCountAcrossParticipants` and asserts the new contract.
