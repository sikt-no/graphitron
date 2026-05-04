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

- **INSERT** — `dsl.insertInto(t, c1, c2, ...)` followed by a runtime loop
  `for (var row : in) step = step.values(DSL.val(row.get("c1"), ...), ...)`.
  Single statement, single round trip; jOOQ renders multi-row VALUES.
  Returning is unchanged.
- **DELETE** — keyed on `tia.fieldBindings()` (same primitive
  `buildLookupWhere` walks for the single-row path: each binding pairs
  the input-field name with the target column, so the LHS gets the
  target columns and the RHS reads `r.get(binding.fieldName())`). With
  N ≥ 2 lookup-key columns:
  `dsl.deleteFrom(t).where(DSL.row(<targetCols>).in(in.stream()
  .map(r -> DSL.row(DSL.val(r.get(<inputName>), <colDataType>)...))
  .toList()))`. Degenerate single-key case (the common one): emit
  `<targetCol>.in(<vals>)` instead of `DSL.row(k).in(DSL.row(v)...)` —
  same SQL, simpler emitted Java. One statement either way.
- **UPDATE** — emits a PostgreSQL-flavoured `UPDATE t SET c = v.c FROM
  (VALUES (...), (...)) AS v(k, c1, c2, ...) WHERE t.k = v.k` via
  `dsl.update(t).set(c, vTable.field(t.C)).from(vTable).where(t.K.eq(vTable.field(t.K)))`,
  where `vTable` is `DSL.values(rows...).asTable("v", colNames...)`.
  The alias columns in `asTable` use each target column's SQL name
  verbatim (`Tables.FILM.LANGUAGE_ID.getName()` etc.), so jOOQ's typed
  `Table.field(Field<T>)` overload returns the matching v-column with
  the target column's type — no string lookup, no cast, no
  `DSL.field("v.c")`. The result is a typed `Field<X>` that drops into
  `set(...)` and `where(...)` without further widening. One statement.
  Carries two runtime guards in the emitted fetcher prelude:
  1. *Oracle-dialect guard*, analogous to UPSERT's: jOOQ silently
     emulates `UPDATE...FROM` on non-Postgres dialects with semantics
     drift, and the OSS distribution omits commercial-only dialect enum
     values, so a name-prefix check on `dsl.dialect().name()` matches
     today's UPSERT pattern. Threads through the existing 10-arg
     `buildDmlFetcher(... postDslGuard)` overload at
     [`TypeFetcherGenerator.java:1636-1646`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java).
     R63 will lift this onto the typed `DialectRequirement` slot
     alongside UPSERT's when it ships; until then both arms carry
     their inline guards.
  2. *Duplicate-lookup-key guard*. `UPDATE ... FROM (VALUES …)` joins
     `t` to `v` on the lookup-key columns; if two input rows share
     the same lookup key, PostgreSQL silently picks one v-row's
     columns (implementation-defined join), losing the other row's
     data. That's the same silent-data-loss footgun Invariant #15
     prevents on the return side, so we shed it at runtime here:
     before binding `vTable`, build a `HashSet<List<Object>>` keyed on
     `List.of(row.get("k1"), row.get("k2"), ...)` (value-equal tuple,
     not `Object[]` reference equality) and throw
     `IllegalArgumentException` if `set.size() != in.size()`.
     Message names the field and points at the duplicate-tuple count.
     UPSERT doesn't need the same guard (PostgreSQL hard-errors on
     duplicate `ON CONFLICT` keys with "command cannot affect row a
     second time"), and DELETE doesn't need it (idempotent).
- **UPSERT** — multi-row INSERT with `ON CONFLICT (keys) DO UPDATE SET
  c = EXCLUDED.c`. Same `.values(...)` loop as INSERT plus the existing
  conflict-key chain; `.doNothing()` branch unchanged. Existing inline
  Oracle-dialect runtime guard preserved verbatim; R63 lifts it later.

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

The single-row path (`tia.list() == false`) is unchanged: same
`Map<?,?>` cast, same one-shot `.values(...)` / `.set(...)` chain,
same emitted bytes for any schema that compiled before R77. The bulk
arm is purely additive.

Per-row missing-key semantics: when a row's `Map` omits an optional
field, `row.get("col")` returns `null`, which `DSL.val(null, dataType)`
binds as a typed-null parameter. This inverts the single-row contract
where missing fields are *skipped* from the column list entirely, but
matches what bulk has to do — the column list is fixed at codegen time
across all rows, so every row contributes a value at every column
position. NOT NULL violations therefore surface at execution time
rather than codegen time, same as any other null write under the bulk
arm.

### Empty-list contract

Define `in.isEmpty()` as a no-op short-circuit: the fetcher returns
the empty list without round-tripping to the database (the only
admitted return shapes for bulk are `*List` per Invariant #15). Avoids
jOOQ's empty-`VALUES` rejection and matches the GraphQL contract of
"applied no rows, returned no rows".

The guard lands inside `buildDmlFetcher` gated on `tia.list()`,
immediately after `in` is bound and before the `tableLocal` declaration
that feeds `emitDmlReturnExpression`. It bypasses the projection
terminator entirely with its own early return:

```java
if (in.isEmpty()) {
    return DataFetcherResult.<List<T>>newResult().data(List.of()).build();
}
```

i.e. the same `DataFetcherResult` shape `returnSyncSuccess` would have
emitted, but binding `data(List.of())` directly rather than walking
through `emitDmlReturnExpression` with an empty `payload` local. The
boxed value type comes from the same `boxed(valueType)` call that
`returnSyncSuccess` already uses.

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
- Add a parallel *deferred* rejection on the `Payload` arm in the
  same widened `validateReturnType` (or a sibling step in
  `FieldBuilder.buildDmlField` after `resolveDmlPayloadAssembly`,
  whichever reads cleaner): `tia.list()` paired with a `ResultReturnType`
  is "list-`@record` payloads are not yet supported". Use
  `Rejection.deferred("…", "synthesize-payload-carrier")`
  ([factory at `Rejection.java:239`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/Rejection.java))
  so the deferred message anchors to R75. Distinct category from #15,
  will lift when R75 lands.
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
- **Pipeline** — emitted method bodies for each of the four verbs read
  `List<Map<?,?>>` and contain a row-loop (no code-string assertions on
  the generated SQL chain itself; structural assertions only, per
  rewrite-design-principles).
- **Compilation** — the four bulk fetchers compile under the
  `graphitron-sakila-example` Java 17 target.
- **Execution** — PostgreSQL execution-tier tests against Sakila:
  `createFilms_insertsNRowsAndReturnsProjectedList`,
  `deleteFilms_deletesNRowsByLookupKeyTuple`,
  `updateFilms_updatesNRowsViaValuesJoin`, and
  `upsertFilms_writesNRowsAndReturnsProjectedList` (both insert and
  update branches). Each verifies row count and RETURNING / projection
  shape. Plus one
  `bulkDml_emptyListInput_doesNotRoundTripToDatabase` that exercises
  the `in.isEmpty()` short-circuit on at least one verb (assert via
  `QUERY_COUNT == 0`, the same pattern other execution tests use), and
  `updateFilms_duplicateLookupKeys_throwsBeforeStatement` that asserts
  the bulk-UPDATE duplicate-tuple guard fires (assert via
  `IllegalArgumentException` plus `QUERY_COUNT == 0`).

## Implementation sites

- [`MutationInputResolver.resolveInput`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java) —
  delete the `foundTia.list()` rejection arm (line 250-255).
- [`MutationInputResolver.validateReturnType`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java) —
  widen the signature to `(returnType, kind, boolean listInput)` and
  add Invariant #15 (bulk-input + single-return rejection) plus the
  deferred Payload+list rejection
  (`Rejection.deferred("…", "synthesize-payload-carrier")`).
- [`FieldBuilder.classifyMutationField`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java) —
  pass `tia.list()` through to the widened `validateReturnType` call
  at line 2374. No changes inside the four `case INSERT/UPDATE/DELETE/UPSERT`
  dispatch arms; the new invariant is verb-neutral.
- [`TypeFetcherGenerator.buildMutationInsertFetcher`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java) (and the three siblings) —
  branch on `tia.list()` to emit the runtime row-loop. The four verbs
  consume per-row data in three idiomatic shapes (INSERT/UPSERT
  accumulate `.values(...)` clauses; DELETE collects `DSL.row(...)` for
  the IN tuple; UPDATE collects rows for `DSL.values(...).asTable(...)`),
  so the shared primitive is a `buildRowValuesBlock(tia, columns)`
  helper that emits the `for (var row : in)` loop body producing a
  `List<? extends Row>` at runtime — each verb wraps that list in its
  own surrounding chain. Don't try to share a single helper that also
  emits the chain.
- [`TypeFetcherGenerator.buildMutationUpdateFetcher`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java) —
  on the `tia.list()` arm, emit the duplicate-lookup-key runtime guard
  before the DML chain (build a `Set` of lookup-key tuples; throw
  `IllegalArgumentException` if `set.size() != in.size()`). Threads
  through `postDslGuard` alongside the existing Oracle-dialect guard.
- [`TypeFetcherGenerator.buildDmlFetcher`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java) —
  flip the `env.getArgument(...)` cast to `List<Map<?,?>>` and emit the
  empty-list short-circuit when `tia.list()`.
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
`*List` arm)". UPDATE's annotation also gains the duplicate-tuple
guard line on the bulk arm.

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
