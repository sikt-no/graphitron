---
id: R77
title: "Bulk DML mutations: listed @table input arguments"
status: Backlog
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
(Invariant #15: `tia.list()` requires `EncodedList` / `ProjectedList` /
list-`Payload`) so authors don't accidentally write `[FilmInput] → Film`
and lose data silently.

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
- **DELETE** — `dsl.deleteFrom(t).where(DSL.row(k1, k2).in(in.stream()
  .map(r -> DSL.row(DSL.val(r.get("k1"), ...), ...)).toList()))`. Composite
  `ROW IN (...)` keyed on `tia.lookupKeyFields()`. One statement.
- **UPDATE** — emits a PostgreSQL-flavoured `UPDATE t SET c = v.c FROM
  (VALUES (...), (...)) AS v(k, c1, c2, ...) WHERE t.k = v.k` via
  `dsl.update(t).set(c, DSL.field("v.c")).from(values).where(...)`. One
  statement; carries the same `DialectRequirement.RequiresFamily(POSTGRES,
  ...)` lift from R63 (UPDATE...FROM is Postgres-flavoured even though
  jOOQ exposes it cross-dialect).
- **UPSERT** — multi-row INSERT with `ON CONFLICT (keys) DO UPDATE SET
  c = EXCLUDED.c`. Same `.values(...)` loop as INSERT plus the existing
  conflict-key chain; `.doNothing()` branch unchanged. Inherits R63's
  PostgreSQL dialect guard.

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

### Empty-list contract

Define `in.isEmpty()` as a no-op short-circuit: the fetcher returns the
empty list (for `*List` returns) or `null` (for `*Single`, though that
combo is rejected per Invariant #15) without round-tripping to the
database. Emit the guard immediately after `in` is bound. Avoids jOOQ's
empty-`VALUES` rejection and matches the GraphQL contract of
"applied no rows, returned no rows".

### Validator updates

- Lift `MutationInputResolver.resolveInput`'s `foundTia.list()` rejection
  arm; replace with the new Invariant #15 dispatch (list-input requires
  list-return).
- Tighten `FieldBuilder.classifyMutationInput` so the `Payload` arm
  rejects `tia.list()` until list-payload assembly is designed (separate
  follow-up, reuse R75 once it lands).
- Update `GraphitronSchemaBuilderTest.DML_LIST_INPUT_DEFERRED` (currently
  asserts the rejection) to instead assert successful classification, and
  add a new case for the single-return + list-input mismatch.

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
- **Execution** — four PostgreSQL execution-tier tests against Sakila:
  `createFilms_insertsNRowsAndReturnsProjectedList`,
  `deleteFilms_deletesNRowsByLookupKeyTuple`,
  `updateFilms_updatesNRowsViaValuesJoin`, and
  `upsertFilms_writesNRowsAndReturnsProjectedList` (both insert and
  update branches). Each verifies row count, RETURNING / projection
  shape, and the empty-list short-circuit.

## Implementation sites

- [`MutationInputResolver.java`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java) —
  delete the `foundTia.list()` rejection arm; add Invariant #15.
- [`FieldBuilder.classifyMutationInput`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java) —
  thread `tia.list()` into the existing four `case INSERT/UPDATE/DELETE/UPSERT` arms; reject bulk-input + non-list-return.
- [`TypeFetcherGenerator.buildMutationInsertFetcher`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java) (and the three siblings) —
  branch on `tia.list()` to emit the runtime row-loop; share an
  `emitRowExtraction` helper across all four.
- [`TypeFetcherGenerator.buildDmlFetcher`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java) —
  flip the `env.getArgument(...)` cast to `List<Map<?,?>>` and emit the
  empty-list short-circuit when `tia.list()`.
- New unit test cases in `GraphitronSchemaBuilderTest`; new
  `DmlBulkMutationsExecutionTest` in `graphitron-sakila-example`.

## Non-goals and out-of-scope

- *List `@record` payloads.* The `Payload` arm stays single-cardinality;
  list-payload assembly is a separate concern, picked up alongside
  R75 (synthesize-payload-carrier).
- *Per-row error channels.* On bulk writes the existing single
  `errorChannel` arm reports either total success or total failure
  (transactional). Per-row partial-success reporting is a separate
  design question covered by R12's evolution, not this item.
- *Cross-dialect UPDATE-from-VALUES.* The plan emits the PostgreSQL
  `UPDATE ... FROM (VALUES ...)` form and reuses R63's dialect
  requirement; a portable fallback that runs N statements in
  `dsl.batch(...)` is a follow-up, not blocking.
- *Build-time INSERT column-coverage validation.* Same deferral as R22;
  unblocked when jOOQ's catalog reliably exposes NOT-NULL + default
  metadata.
- *Bulk on `@service` mutations.* `MutationServiceTableField` /
  `MutationServiceRecordField` already accept whatever the service
  signature declares; bulk service mutations are an author-side concern,
  not a generator-side gap.
