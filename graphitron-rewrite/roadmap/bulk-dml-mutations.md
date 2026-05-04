---
id: R77
title: "Bulk DML mutations: listed @table input arguments"
status: Spec
bucket: architecture
priority: 1
theme: mutations-errors
depends-on: []
---

# Bulk DML mutations: listed @table input arguments

R22 shipped single-row INSERT / UPDATE / DELETE / UPSERT and explicitly carved out listed `@table` input arguments (`in: [FilmInput!]!`) as *Out of scope and tracked separately*; the rejection at [`MutationInputResolver.java:250-255`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java) points authors at this roadmap gap. This item lifts the restriction across all four DML verbs so a single mutation invocation can write N rows in one statement, in scope: INSERT, UPDATE, UPSERT, DELETE.

## Motivation

Authors writing experimental schemas (`regelverkMutations_exp.graphqls` is
the immediate prompt) want bulk write semantics, e.g.
`opprettKvotesporsmalPreutfylling(in: [KvotesporsmalInput!]!): [Kvotesporsmal!]!`.
Today this is rejected with a structural error pointing at this roadmap item. The remediation
"declare the argument as a single non-list `@table` input wrapper and
issue one mutation per row" is workable for two-row writes but degenerates
into N round trips and N transactions for any real bulk path; jOOQ
already speaks the multi-row form for every DML verb we emit.

## Design

### Schema shape and return cardinality

The accepted shape for any of the four verbs is

```graphql
field(arg: [InputType!]!): ReturnType
```

where `ReturnType` is one of `[T!]!` (table-bound list, projected), `[ID!]!`
(encoded list), `T!` (single, last-row semantics; see below), `ID!` (single,
last-row), or a `@record` payload wrapping either. The `*List` arms on
[`DmlReturnExpression`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/DmlReturnExpression.java)
(`EncodedList`, `ProjectedList`) already cover list-cardinality returns;
only the input-side cardinality is new.

The bulk-input + single-return combination resolves to "return the last row
in the affected set" by jOOQ default; we should reject it at classify time
(Invariant #15: `tia.list()` requires a list return — `EncodedList` or
`ProjectedList`) so authors don't accidentally write `[FilmInput] → Film`
and lose data silently. The same silent-data-loss concern motivates the
runtime duplicate-lookup-key guard on bulk UPDATE (see UPDATE bullet
below): both checks exist so authors can't shed input rows without an
error.

Invariant #15 covers the structural rule. The `Payload` arm is
list-`@record`-payload territory and stays single-cardinality at this
stage as a *deferred* rejection (separate category from #15: not a
hard structural rule, just "not yet supported"). List-payload assembly
is upstream R75 (Backlog) and lands as a follow-up.

### Model

`TableInputArg` already carries `boolean list`. No new component is
needed; the four DML records do not need a per-arm bulk flag because
`tableInputArg().list()` is the dispatch key.

### Emitter rewrite (per verb)

All four `buildMutationXxxFetcher` methods today assume a single
`Map<?,?> in = (Map<?,?>) env.getArgument(...)` and walk `tia.fields()` /
`tia.setFields()` once at codegen time, baking each `in.get("col")` into
the emitted statement (see e.g. [`TypeFetcherGenerator.java:1419-1437`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java)
for INSERT). The bulk shape inverts the codegen-vs-runtime split: the
field set is still known at codegen time, but the row count is a runtime
fact, so the emitted Java has to *loop over rows* rather than emit one
`.values(...)` clause per field.

Concrete shape per verb (all share the existing `buildDmlFetcher`
skeleton — try/catch envelope, `dsl` bind, optional dialect guard,
projection terminator):

- **INSERT** — `dsl.insertInto(t, c1, c2, ...)` followed by a runtime
  loop that picks `DSL.val(row.get("col"), dataType)` when the row's Map
  contains the field name and `DSL.defaultValue(dataType)` when it
  doesn't (see *Per-field missing-vs-null semantics* below). Single
  statement, single round trip; jOOQ renders multi-row VALUES with mixed
  bind / DEFAULT cells per column. Returning is unchanged.
- **DELETE** — keyed on `tia.fieldBindings()` (same primitive
  `buildLookupWhere` walks for the single-row path: each binding pairs
  the input-field name with the target column, so the LHS gets the
  target columns and the RHS reads `r.get(binding.fieldName())`).
  Always row-tuple form, regardless of key arity:
  `dsl.deleteFrom(t).where(DSL.row(<targetCols>).in(in.stream()
  .map(r -> DSL.row(DSL.val(r.get(<inputName>), <colDataType>)...))
  .toList()))`. PostgreSQL renders 1-key `(col) IN ((v1), (v2))`
  identically to `col IN (v1, v2)`, so a 1-key fast path adds emitter
  branching without paying for itself; one shape, one execution-tier
  test. One statement.
- **UPDATE** — emits a PostgreSQL-flavoured `UPDATE t SET c = v.c FROM
  (VALUES ...) AS v(k, c1, c2, ...) WHERE t.k = v.k`, but the SET
  column list and the v-table column list are **derived per-call at
  runtime** from the input rows' present-key set rather than baked
  from `tia.setFields()` at codegen. This is what makes PATCH
  semantics correct: a field omitted from the input drops out of the
  SET clause entirely ("leave the column alone"), and a field set to
  explicit `null` binds typed null. INSERT/UPSERT can mix `DEFAULT`
  and bound-null per cell because each VALUES row is independent;
  UPDATE can't (one SET clause is applied to every joined row), so
  the variability shifts from per-cell to per-call.

  The bulk arm requires all rows to share the same present-key set —
  the uniformity guard below. Rows that diverge can't share one SET
  clause, so they're rejected at runtime; authors split into
  multiple bulk calls or pad rows to a uniform shape. Auto-grouping
  divergent shapes into multiple statements is option 3 in the
  design discussion; R77 takes option 1 and defers grouping.

  Three runtime guards fire from the `postInGuard` slot before the
  chain executes, in order:
  1. *Empty-list short-circuit* — covered in *Empty-list contract
     and typed value generic* below.
  2. *Uniform-shape guard* — `firstKeys = in.get(0).keySet()`,
     validate every later row's `keySet().equals(firstKeys)`. Reject
     `IllegalArgumentException` on divergence with a message naming
     the offending row index, its key set, and `firstKeys`.
  3. *Duplicate-lookup-key guard*. `UPDATE ... FROM (VALUES …)`
     joins `t` to `v` on the lookup-key columns; if two input rows
     share the same lookup key, PostgreSQL silently picks one v-row
     (implementation-defined join), losing the other row's data —
     the same silent-data-loss footgun Invariant #15 prevents on
     the return side. Build a `HashSet<List<Object>>` keyed on
     `List.of(row.get(b1.fieldName()), row.get(b2.fieldName()), ...)`
     for each `binding` in `tia.fieldBindings()` (value-equal tuple,
     not `Object[]` reference equality) and throw
     `IllegalArgumentException` if `set.size() != in.size()`. The
     guard runs *after* `in` is bound and *before* the chain
     executes, so it can't piggyback on `postDslGuard` (emitted
     before the `in` cast); `postInGuard` is the right slot,
     threaded the same way as `postDslGuard` and emitted between
     the `in` cast and `tableLocal`. UPSERT doesn't need this guard
     (PostgreSQL hard-errors on duplicate `ON CONFLICT` keys with
     "command cannot affect row a second time"), and DELETE doesn't
     need it (idempotent).

  Once the guards pass, build the chain programmatically: walk
  `tia.setFields()` in declaration order and emit a codegen-time
  `if (firstKeys.contains(cf.name())) { ... }` for each `cf` on
  three parallel sequences — the SET clause
  (`stmt = stmt.set(Tables.X.COL, vTable.field(Tables.X.COL))`),
  the v-table column-name list (`colNames.add(Tables.X.COL.getName())`),
  and the per-row v-table cell list
  (`cells.add(DSL.val(row.get("col"), Tables.X.COL.getDataType()))`).
  The lookup-key columns are unconditional (always present, validated
  upstream) and emitted outside the conditional. The alias names in
  `asTable("v", colNames...)` use each target column's SQL name
  verbatim (`Tables.FILM.LANGUAGE_ID.getName()` etc.), so jOOQ's
  typed `Table.field(Field<T>)` overload returns the matching
  v-column with the target column's type — no string lookup, no
  cast.

  Single-row UPDATE shares the dynamic-SET emit: same three
  parallel walks, but reading `in.keySet()` instead of `firstKeys`
  and skipping the uniformity guard (one Map, nothing to compare).
  Single-row UPDATE keeps `dsl.update(t).set(...).where(<lookupWhere>)`
  shape (no v-table, no `FROM`) — the per-cell value-bind is
  `DSL.val(in.get("col"), dataType)` directly. This fixes the
  legacy missing-vs-null bug on single-row UPDATE in the same pass.

  Both arms share a fourth runtime check, evaluated immediately
  before chain construction: if every `tia.setFields()` entry is
  absent from the present-key set, there's nothing to update —
  throw `IllegalArgumentException` ("@mutation(typeName: UPDATE)
  call has no settable fields present; only @lookupKey fields were
  provided"). Without this, the dynamic SET clause would emit zero
  `.set(...)` calls and jOOQ would render an invalid `UPDATE t FROM
  v WHERE ...` with an empty SET.

  Existing inline Oracle-dialect runtime guard preserved verbatim
  on the bulk arm via `postDslGuard`, emitted between `dsl` bind
  and the `in` cast. R63 will lift it onto typed `DialectRequirement`
  later; until then UPDATE and UPSERT both carry their inline guards.
- **UPSERT** — multi-row INSERT with `ON CONFLICT (keys) DO UPDATE SET
  c = EXCLUDED.c`. Same `.values(...)` loop as INSERT (so the same
  per-row `containsKey` dispatch on missing-vs-null applies; see
  below) plus the existing conflict-key chain; `.doNothing()` branch
  unchanged. Existing inline Oracle-dialect runtime guard preserved
  verbatim; R63 lifts it later.

### Per-field missing-vs-null semantics

Today's single-row INSERT/UPDATE/UPSERT walk `tia.fields()` /
`tia.setFields()` and emit `DSL.val(in.get("col"), dataType)`
unconditionally. `Map.get` returns `null` for absent keys, so the
emit conflates two distinct GraphQL inputs:

- *field omitted* — author wrote `{title: "X"}`, leaving `description`
  unset. Author intent: "use the column's default" (insert) or "leave
  the column alone" (update).
- *field explicitly null* — author wrote `{title: "X", description:
  null}`. Author intent: "write SQL NULL".

Legacy graphitron has had bugs from collapsing these two cases; the
rewrite has inherited the same conflation today (verified against
`buildMutationInsertFetcher`/`buildMutationUpdateFetcher` at
[`TypeFetcherGenerator.java:1421-1432`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java)
and 1471-1477). R77 fixes this for all three verbs (INSERT, UPDATE,
UPSERT) in the same pass that lifts the bulk arm, with two
mechanisms:

- *INSERT / UPSERT* — emit `row.containsKey("col") ? DSL.val(row.get("col"),
  dataType) : DSL.defaultValue(dataType)` per column, per row. jOOQ
  renders `DEFAULT` as the `VALUES` cell, so omitted fields take the
  column's DB-side default (NOT NULL + no default still surfaces as a
  runtime constraint violation, same as today). Single-row INSERT/
  UPSERT collapse to one row of the same dispatch. graphql-java's
  argument coercion preserves the absent-vs-null distinction in the
  resulting Map (omitted fields are absent keys, explicit nulls are
  present-with-null), so `containsKey` is the right discriminator.

- *UPDATE* — different mechanism, same intent. UPDATE's one SET
  clause is shared across all matched rows, so per-cell `DEFAULT` /
  `val` mixing isn't expressible. Instead, the SET column list is
  derived from the present-key set at runtime: an absent field
  drops out of the SET clause entirely (= "leave the column
  alone"), and a present-null field binds typed null. Bulk UPDATE
  adds a uniformity guard requiring all rows to share the same
  present-key set (see UPDATE bullet above); single-row UPDATE has
  no analogue and just reads `in.keySet()`. Both arms gain a
  "no-set-fields-present" runtime check that throws when only
  `@lookupKey` fields are provided. This fixes the legacy
  missing-vs-null bug on single-row UPDATE alongside the bulk lift.

- *DELETE* — has no value columns; only the lookup-key bindings, which
  must be present (validated upstream). N/A.

### Input parsing

`buildDmlFetcher`'s prelude flips from

```java
Map<?, ?> in = (Map<?, ?>) env.getArgument(inputArgName);
```

to

```java
List<Map<?, ?>> in = (List<Map<?, ?>>) env.getArgument(inputArgName);
```

when `tia.list()`. Per-row field access (`row.get("col")`) replaces
`in.get("col")` inside the runtime loop. The verb-neutral skeleton
dispatches once on `tia.list()` and threads the chosen `in` shape
through `dmlChain`.

The single-row path (`tia.list() == false`) keeps the same
`Map<?,?>` cast and the same outer chain shape (`.values(...)` for
INSERT/UPSERT, `.set(...).where(...)` for UPDATE,
`.where(...)` for DELETE). Three differences from R22 bytes:

1. INSERT and UPSERT emit the per-cell missing-vs-null dispatch
   described above (`containsKey` → `defaultValue` vs `val`) instead
   of the unconditional `DSL.val(in.get(...), ...)` walk.
2. UPDATE emits the dynamic SET clause described in the UPDATE
   bullet — a codegen-time `if (in.containsKey(cf.name()))` chain
   walking `tia.setFields()`. Absent fields drop out of the SET
   clause; the lookup-key WHERE is unchanged. Both single-row and
   bulk arms share the no-set-fields-present runtime check.
3. The fetcher's declared return generic narrows from `Object` to a
   per-arm typed value (see *Empty-list contract* below for the typed
   shape). The bulk arm and the single-row arm share this lift.

Per-row on the INSERT/UPSERT bulk arm, the column list is fixed at
codegen time across all rows, so every row contributes a cell at
every column position — typed value-bind (present, possibly null)
or typed `DEFAULT` (absent). NOT NULL violations on a present-null
cell surface at execution time, same as any other null write. On
the UPDATE bulk arm the column list is fixed *per-call* (uniform
present-key set across rows) but varies between calls; the
uniformity guard makes that variability legible.

### Empty-list contract and typed value generic

Define `in.isEmpty()` as a no-op short-circuit: the fetcher returns
the empty list without round-tripping to the database (the only
admitted return shapes for bulk are `*List` per Invariant #15). Avoids
jOOQ's empty-`VALUES` rejection and matches the GraphQL contract of
"applied no rows, returned no rows".

The guard rides the `postInGuard` slot introduced for the UPDATE
duplicate-tuple guard (see UPDATE bullet above) — same injection
point, same `tia.list()` gate, two emissions sharing one slot. It
lands after the `in` cast and before `tableLocal`, bypassing the
projection terminator entirely with its own early return.

The current `buildDmlFetcher` derives `valueType` as `ClassName.OBJECT`
for every non-`Payload` arm (`TypeFetcherGenerator.java:1648-1651`),
so the fetcher's declared return is `DataFetcherResult<Object>` and
the empty-list short-circuit can't produce a typed `List<X>` literal
without losing the generic. R77 lifts `valueType` per arm:

| arm             | typed `valueType`           | runtime payload       |
|-----------------|-----------------------------|------------------------|
| `EncodedSingle` | `String`                    | encoded Node ID        |
| `EncodedList`   | `List<String>`              | encoded Node IDs       |
| `ProjectedSingle` | `org.jooq.Record`         | jOOQ row               |
| `ProjectedList` | `List<org.jooq.Record>`     | jOOQ rows              |
| `Payload`       | `assembly.payloadClass()`   | (unchanged)            |

The encoder helpers all return `String` (see
`NodeIdEncoderClassGenerator`'s `static String encode<TypeName>(...)`
emit), and the projection terminators use `.fetch(r -> r)` /
`.fetchOne(r -> r)` against `returningResult(SelectField<?>[])`, which
in jOOQ produces `Result<Record>` / `Record`. Both types are nameable
at codegen time; neither requires reflective fishing.

The empty-list short-circuit then emits exactly the shape
`returnSyncSuccess` produces, with `List.of()` substituted for the
`payload` local:

```java
if (in.isEmpty()) {
    return DataFetcherResult.<List<String>>newResult().data(List.of()).build();
    //                       ^^^^^^^^^^^^ EncodedList; ProjectedList swaps in List<Record>
}
```

The lift narrows the `DataFetcherResult` parameter for *every* DML
fetcher (single-row and bulk), so any consumer that treated the
fetcher's declared return as `DataFetcherResult<Object>` re-types
implicitly. Inside graphql-java the field-resolver layer takes the
payload as `Object` regardless, so this is a generator-internal
sharpening.

### Validator updates

- Lift `MutationInputResolver.resolveInput`'s `foundTia.list()`
  blanket rejection ([line 250-255](../graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java)).
- Add new structural Invariant #15 by widening
  `MutationInputResolver.validateReturnType` to take a third arg
  `boolean listInput` and rejecting `listInput && !returnType.wrapper().isList()`
  alongside today's #14 cases. That's the existing #14 home, called
  from one site in `FieldBuilder.classifyMutationField`
  ([line 2374](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java));
  pass `tia.list()` through. The rule is verb-neutral, so it lives
  upstream of the four `case INSERT/UPDATE/DELETE/UPSERT` arms rather
  than threaded into each.
- Add a parallel *deferred* rejection on the `Payload` arm in
  `FieldBuilder.buildDmlField` after `resolveDmlPayloadAssembly`:
  `tia.list()` paired with a `ResultReturnType` is "list-`@record`
  payloads are not yet supported". Use
  `Rejection.deferred("…", "synthesize-payload-carrier")`
  ([factory at `Rejection.java:239`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/Rejection.java))
  so the deferred message anchors to R75. Kept out of
  `validateReturnType` so that function stays purely structural
  (Invariant #14 + new #15); the deferred Payload+list rule is "not
  yet supported", a distinct category that lifts when R75 lands.
- Update `GraphitronSchemaBuilderTest.DML_LIST_INPUT_DEFERRED`
  (currently asserts the blanket rejection; its description string
  mislabels it `(Invariant #11)` — #11 is unrelated, the original
  rejection was unnumbered). Rename / split into one case per new
  disposition; the rejected-arm cases label `(Invariant #15)` and the
  Payload arm labels `(deferred, R75)`. At minimum:
  - `DML_INSERT_LIST_LIST_OK` (input `[FilmInput!]!`, return `[Film!]!` —
    classifies cleanly).
  - `DML_UPDATE_LIST_LIST_OK`, `DML_DELETE_LIST_LIST_OK`,
    `DML_UPSERT_LIST_LIST_OK` (one OK case per remaining verb so
    four-verb coverage is visible at a glance).
  - `DML_INSERT_LIST_SINGLE_T_REJECTED` (input `[FilmInput!]!`, return
    `Film!` — Invariant #15, projected-single arm).
  - `DML_INSERT_LIST_SINGLE_ID_REJECTED` (input `[FilmInput!]!`, return
    `ID!` — Invariant #15, encoded-single arm; same footgun,
    different return shape).
  - `DML_INSERT_LIST_PAYLOAD_DEFERRED` (input `[FilmInput!]!`, return
    `FilmPayload` (`@record`) — the R75 gate).

## Tests

- **Unit** — classifier produces `MutationInsertTableField` /
  `MutationDeleteTableField` / etc. with `tableInputArg().list() == true`
  for each verb; `Invariant #15` rejects bulk-input + single-return.
- **Pipeline** — emitted method bodies for each of the four verbs
  read `List<Map<?,?>>` on the bulk arm and contain a row-loop; the
  typed `valueType` lift narrows the fetcher's declared return generic
  away from `Object`; INSERT/UPSERT emit a `containsKey`-gated value
  cell (DEFAULT vs typed-null); and UPDATE emits a codegen-time
  `if (firstKeys.contains(name))` (bulk) or `if (in.containsKey(name))`
  (single-row) walk over `tia.setFields()` for the SET clause, the
  v-table column-name list, and the per-row v-table cells (no
  code-string assertions on the generated SQL chain itself; structural
  assertions only, per rewrite-design-principles).
- **Compilation** — the four bulk fetchers compile under the
  `graphitron-sakila-example` Java 17 target.
- **Execution** — PostgreSQL execution-tier tests against Sakila:
  - `createFilms_insertsNRowsAndReturnsProjectedList`,
    `deleteFilms_deletesNRowsByLookupKeyTuple`,
    `updateFilms_updatesNRowsViaValuesJoin`, and
    `upsertFilms_writesNRowsAndReturnsProjectedList` (both insert and
    update branches). Each verifies row count and RETURNING /
    projection shape.
  - `createFilms_omittedFieldUsesColumnDefault_explicitNullWritesNull`
    — one bulk INSERT call with two rows: row A omits a column with a
    DB default (e.g. `rentalDuration`), row B sets the same column to
    explicit `null`. Asserts row A reads back the DB default and row B
    reads back NULL (or surfaces the NOT NULL violation if the column
    is NOT NULL without a default). The single-row analogue
    `createFilm_omittedFieldUsesColumnDefault_explicitNullWritesNull`
    runs the same shape on the single-row INSERT to lock in the
    legacy-bug fix.
  - `updateFilm_omittedFieldLeavesColumnAlone_explicitNullWritesNull`
    — one single-row UPDATE call: row sets `title` to `"X"` and
    omits `description`; asserts post-state `title == "X"` and
    `description` is unchanged from the pre-state. A sibling case
    sets `description: null` and asserts the column reads back NULL.
    Locks in the dynamic-SET legacy-bug fix.
  - `updateFilms_omittedFieldLeavesColumnAlone_explicitNullWritesNull`
    — bulk analogue with two rows sharing the same uniform shape;
    same per-row assertions.
  - `updateFilms_divergentInputShapes_throwsBeforeStatement` —
    bulk UPDATE with row A having `{filmId, title}` and row B having
    `{filmId, title, description}`; asserts `IllegalArgumentException`
    plus `QUERY_COUNT == 0`. Message contains the offending row index
    and both key sets.
  - `updateFilms_onlyLookupKeyFields_throwsBeforeStatement` (and the
    single-row analogue `updateFilm_onlyLookupKeyFields_throws`) —
    asserts the no-set-fields-present runtime check fires when the
    input contains only `@lookupKey` fields.
  - `bulkDml_emptyListInput_doesNotRoundTripToDatabase` — exercises
    the `in.isEmpty()` short-circuit on at least one verb (assert via
    `QUERY_COUNT == 0`, the same pattern other execution tests use).
  - `updateFilms_duplicateLookupKeys_throwsBeforeStatement` — asserts
    the bulk-UPDATE duplicate-tuple guard fires (assert via
    `IllegalArgumentException` plus `QUERY_COUNT == 0`, so the throw
    is observed *before* any SQL is dispatched).

## Implementation sites

- [`MutationInputResolver.resolveInput`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java) —
  delete the `foundTia.list()` rejection arm (line 250-255).
- [`MutationInputResolver.validateReturnType`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java) —
  widen the signature to `(returnType, kind, boolean listInput)` and
  add Invariant #15 (bulk-input + single-return rejection). Stays
  purely structural; the deferred Payload+list rejection lands in
  `FieldBuilder.buildDmlField` instead (see below).
- [`FieldBuilder.classifyMutationField`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java) —
  pass `tia.list()` through to the widened `validateReturnType` call
  at line 2374. No changes inside the four `case INSERT/UPDATE/DELETE/UPSERT`
  dispatch arms; the new invariant is verb-neutral.
- [`FieldBuilder.buildDmlField`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java) —
  after `resolveDmlPayloadAssembly`, emit the deferred Payload+list
  rejection via
  `Rejection.deferred("list @record payload returns are not yet supported", "synthesize-payload-carrier")`
  when `tia.list() && returnType instanceof ResultReturnType`.
- [`TypeFetcherGenerator.buildMutationInsertFetcher`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java) (and `buildMutationUpsertFetcher`) —
  on the `tia.list()` arm, emit a `for (var row : in)` row-loop that
  accumulates `.values(...)` calls on the running `step` local. On
  *both* arms (single-row and bulk), replace the unconditional
  `DSL.val(in.get("col"), dataType)` cell with the
  `containsKey`-gated `DEFAULT vs val` dispatch (see *Per-field
  missing-vs-null semantics*); for single-row, the "row" is `in`
  itself.
- [`TypeFetcherGenerator.buildMutationDeleteFetcher`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java) —
  on the `tia.list()` arm, emit
  `dsl.deleteFrom(t).where(DSL.row(<targetCols>).in(in.stream().map(r -> DSL.row(...)).toList()))`.
  Always row-form, regardless of `tia.fieldBindings().size()`. The
  single-row arm is unchanged.
- [`TypeFetcherGenerator.buildMutationUpdateFetcher`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java) —
  rewrite both arms to emit the dynamic SET clause. Walk
  `tia.setFields()` in declaration order and for each `cf` emit a
  codegen-time `if (presentKeys.contains("name")) { ... }` block on
  three parallel sequences: the SET clause, the v-table column-name
  list (bulk arm only), and the per-row v-table cell list (bulk arm
  only). `presentKeys` is `firstKeys` on the bulk arm (after the
  uniformity guard runs) and `in.keySet()` on the single-row arm.
  Lookup-key columns are unconditional (validated upstream).
  Both arms emit the no-set-fields-present runtime check
  immediately before chain construction. The bulk arm additionally
  emits the empty-list short-circuit, the uniformity guard, and the
  duplicate-lookup-key guard via the new `postInGuard` slot (see
  `buildDmlFetcher` below). Existing inline Oracle-dialect guard
  continues to ride `postDslGuard` on the bulk arm.
- [`TypeFetcherGenerator.buildDmlFetcher`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java) —
  three changes:
  1. Cast: emit `Map<?,?> in = ...` when `tia.list() == false`,
     `List<Map<?,?>> in = ...` when `tia.list() == true`.
  2. Add a `postInGuard` `CodeBlock` parameter alongside the existing
     `postDslGuard`, emitted between the `in` cast and the
     `tableLocal` declaration. Carries (a) the bulk arm's empty-list
     short-circuit, (b) UPDATE's uniform-shape guard, (c) UPDATE's
     dup-tuple guard, and (d) UPDATE's no-set-fields-present check
     (the last one fires on both arms, so it isn't `tia.list()`-gated).
  3. Lift `valueType` from `ClassName.OBJECT` to a per-arm typed
     value: `ClassName.get(String.class)` for `EncodedSingle`,
     `ParameterizedTypeName.get(List.class, String.class)` for
     `EncodedList`, jOOQ's `Record` (single) / `List<Record>` (list)
     for the projected arms, and the existing
     `assembly.payloadClass()` for `Payload`. The fetcher's declared
     return type and the inner `payload` local re-type implicitly.
- [`graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`](../graphitron-sakila-example/src/main/resources/graphql/schema.graphqls) —
  add four bulk-variant `Mutation` fields alongside the existing
  single-row `createFilm` / `updateFilm` / `upsertFilm`:
  - `createFilms(in: [FilmCreateInput!]!): [Film!]! @mutation(typeName: INSERT)`
  - `updateFilms(in: [FilmUpdateInput!]!): [Film!]! @mutation(typeName: UPDATE)`
  - `upsertFilms(in: [FilmUpsertInput!]!): [Film!]! @mutation(typeName: UPSERT)`
  - `deleteFilms(in: [FilmDeleteInput!]!): [ID!]! @mutation(typeName: DELETE)`

  `FilmDeleteInput` is new — `@table(name: "film")` carrying a single
  `filmId: ID! @lookupKey` field. (Sakila has no single-row DELETE
  mutation today; this surface is the first DELETE test fixture.)
  The four return shapes deliberately mix `[Film!]!` (`ProjectedList`)
  and `[ID!]!` (`EncodedList`) so the execution tests cover both
  terminator arms.
- New unit test cases in `GraphitronSchemaBuilderTest`; new
  `DmlBulkMutationsExecutionTest` in `graphitron-sakila-example`.
- `graphitron-fixtures` needs no new fixtures: the existing
  `Film` / `FilmCreateInput` etc. surface already covers the listed
  input-arg shape (the change is in argument cardinality, not field
  shape). Pipeline tests reuse those fixtures and assert structural
  shape on the bulk arm.

The `@DependsOnClassifierCheck` annotations on the four
`buildMutation*Fetcher` methods are the canonical home for invariant
guarantees the emitter relies on (the R22-era `roadmap/mutations.md`
plan file was deleted on Done; cite the changelog and these
annotations rather than the missing file). The four annotations'
existing `reliesOn` strings each carry a monolithic clause like
"casts `env.getArgument(tia.name())` to `Map<?,?>` with no guard";
rewrite each as a `tia.list()` conditional — "casts to `Map<?,?>`
when `tia.list() == false`, `List<Map<?,?>>` when `tia.list() ==
true` (Invariant #15 then guarantees the return shape is
list-cardinality, so the projection terminator binds the matching
`*List` arm)". INSERT and UPSERT also gain a clause stating that the
value-cell emit dispatches on `row.containsKey("col")`
(`DSL.defaultValue(dataType)` when absent, `DSL.val(value, dataType)`
when present) — the classifier guarantees `tia.fields()` is the
canonical column list and never widens at runtime. UPDATE's
annotation gains three clauses: (a) the SET clause is built from a
codegen-time `if (presentKeys.contains(name))` walk over
`tia.setFields()` rather than baked unconditionally, (b) the bulk
arm's uniform-shape guard makes `firstKeys` a stable witness for the
present-key set across all rows, and (c) the bulk arm's
duplicate-tuple guard rejects same-lookup-key inputs before the
chain executes.

## Non-goals and out-of-scope

- *List `@record` payloads.* The `Payload` arm stays single-cardinality;
  list-payload assembly is a separate concern, picked up alongside
  R75 (synthesize-payload-carrier).
- *Per-row error channels.* On bulk writes the existing single
  `errorChannel` arm reports either total success or total failure
  (transactional). Per-row partial-success reporting is a separate
  design question covered by R12's evolution, not this item.
- *Cross-dialect UPDATE-from-VALUES.* The plan emits the PostgreSQL
  `UPDATE ... FROM (VALUES ...)` form and gates non-Postgres dialects
  with an inline runtime guard (lifted onto typed `DialectRequirement`
  by R63 later). A portable fallback that runs N statements in
  `dsl.batch(...)` is a follow-up, not blocking.
- *Build-time INSERT column-coverage validation.* Same deferral as R22;
  unblocked when jOOQ's catalog reliably exposes NOT-NULL + default
  metadata.
- *Bulk on `@service` mutations.* `MutationServiceTableField` /
  `MutationServiceRecordField` already accept whatever the service
  signature declares; bulk service mutations are an author-side concern,
  not a generator-side gap.
- *Auto-grouping divergent UPDATE shapes.* Bulk UPDATE rejects rows
  with non-uniform present-key sets at runtime; authors split into
  multiple bulk calls or pad rows. Auto-grouping by present-key set
  and emitting one statement per group (option 3 in the design
  discussion) is a follow-up if the rejection proves painful in
  practice; it's the same dynamic-SET primitive applied N times,
  with a partition step in front.
