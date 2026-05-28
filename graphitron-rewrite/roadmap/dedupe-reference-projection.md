---
id: R255
title: Dedupe duplicate column projection in @reference DBQueries (RC-6 regression)
status: In Review
bucket: bug
priority: 5
theme: structural-refactor
depends-on: []
created: 2026-05-28
last-updated: 2026-05-28
---

# Dedupe duplicate column projection in @reference DBQueries (RC-6 regression)

## Symptom

Every row fetched against a federation-`@override` type whose `@node(keyColumns: [...])` composite key overlaps with a sibling `@field` ColumnField on the same column emits an INFO log from jOOQ for each fetched row:

```
INFO org.jooq.impl.FieldsImpl: Ambiguous match for "<parent_alias>"."<column>".
Both "<parent_alias>"."<column>" and "<parent_alias>"."<column>" match.
```

Same alias, same column, identical-looking `Field` references appearing twice in the projection. The fetched data is correct (jOOQ returns the first match), but the log spam is severe on any list query.

## Repro

The user's failing SDL has the canonical shape:

```graphql
type Opptakshendelsestype implements Node
    @key(fields: "id")
    @node(typeId: "opptakshendelsestype",
          keyColumns: ["opptakshendelsestype_kode", "opptakstype_kode"])
    @table(name: "opptakshendelsestype_opptakstype") {
    id: ID! @nodeId
    kode: OpptakshendelsestypeKode! @field(name: "opptakshendelsestype_kode") @override(from: "admissio")
    # ...other fields, including @reference scalars and a @reference object — incidental to the bug.
}
```

Federation `@key(fields: "id")` causes the entity dispatch path to always project `id`, even when the federated query only asked for `kode` and `navn`. With `id` and `kode` both selected, the generated `Opptakshendelsestype.$fields()` runs both case arms:

```java
case "id" -> {
    fields.add(table.OPPTAKSHENDELSESTYPE_KODE);   // composite NodeId arm: keyColumns[0]
    fields.add(table.OPPTAKSTYPE_KODE);             // composite NodeId arm: keyColumns[1]
}
case "kode" -> fields.add(table.OPPTAKSHENDELSESTYPE_KODE);   // sibling ColumnField
```

The `ArrayList<Field<?>>` accumulator at `TypeClassGenerator.java:206` accepts both, so `table.OPPTAKSHENDELSESTYPE_KODE` lands in the projection twice. (Confirmed by `TypeClassGenerator.generate(...)` on a synthetic SDL with composite `@node(keyColumns)` overlapping a sibling `@field`-ColumnField — both case arms emit `fields.add(table.X)` for the shared column.)

The `@reference` fields (`navn`, `kategori`) are incidental: each is a `ColumnReferenceField` whose case arm emits a single correlated subquery aliased by SDL name (`DSL.field(<subquery>).as("navn")`), no FK-source-column projection on the parent. Removing every `@reference` field would not eliminate the duplicate; the trigger is the composite-NodeId-vs-sibling-ColumnField overlap alone.

## Mechanism

`TypeClassGenerator.build$FieldsMethod` (`TypeClassGenerator.java:182-229`) drives the per-type `$fields()` emission. Two relevant invariants:

- The accumulator is an `ArrayList<Field<?>>` (line 206) — no dedupe.
- The post-switch `requiredProjectionColumns` loop (lines 223-226) dedupes against the accumulator via `if (!fields.contains(table.X)) fields.add(table.X)`. This is the only dedupe in the method, and it only fires for the columns `collectRequiredProjectionColumns` surfaces (`Split*Field` source keys and single-hop `TableMethodField` FK source columns). It does not run between switch arms.

Inside the switch (lines 244-303), each variant's case arm appends without checking the accumulator:

- `ColumnField` → `fields.add(table.X)` (line 261-262)
- `CompositeColumnField` → `fields.add(table.X)` per column (line 266) — the composite NodeId case
- `ColumnReferenceField` / `TableField` / `LookupTableField` → aliased subquery via `DSL.field(...).as(SDL_name)` or `DSL.multiset(...).as(SDL_name)`. The SDL-name alias is unique per arm, so these never collide with each other; they only collide with raw column projections if a future emitter aliases something the same way as a raw column.
- `ComputedField` → method call aliased by SDL name (line 292-293), same isolation as above
- `NestingField` → recurse

So today the only duplicate path is **two case arms emitting the raw `table.X` for the same X** — i.e., `CompositeColumnField` keyColumns overlapping with sibling `ColumnField`s. Tomorrow's emitters might add more.

Fetchers amplify, but don't cause, the symptom. They use a mix of identity reads (`r.get(Tables.X.COL)` from `FetcherEmitter.java:126-128, 144-145`) and SDL-name reads (`DSL.field(SDL_name)` for `ColumnReferenceField` / `TableField` / `LookupTableField` / `ComputedField`, `FetcherEmitter.java:162, 167, 174, 197`). In a deduplicated projection both styles resolve unambiguously; in a duplicated projection jOOQ's `FieldsImpl.indexOf` finds two matches, logs INFO, returns the first.

## Why "regression"

The composite-NodeId arm has emitted `fields.add(table.X)` per keyColumn since the `CompositeColumnField`/`NodeIdEncodeKeys` model landed (R50). The bug surfaces only when a sibling `ColumnField` overlaps a keyColumn, which is exactly what federation `@key` + `@override` forces in the user's case (entity dispatch always selects `id`, so the overlap activates on every fetched row). RC-6 is likely the first release shipping the federation `@override` entity dispatch with this combination of directives on real schemas; the structural bug predates it.

## Fix

Switch the accumulator from `ArrayList<Field<?>>` to `LinkedHashSet<Field<?>>` in the generated `$fields()` method (`TypeClassGenerator.java:206`). jOOQ caches `TableField` references per aliased table instance, so two case arms emitting `table.X` produce the same `Field` object — `LinkedHashSet` dedupes by identity. Insertion order is preserved, so SQL projection ordering and result-Record layout are stable. The existing `requiredProjectionColumns` `fields.contains(...)` check becomes redundant (Set semantics already dedupe on `add`) but harmless; leave it as-is for readability or drop it as a cleanup line.

The return type stays `List<Field<?>>` — callers (`SelectMethodBody`, polymorphic emitter, etc.) consume via `new ArrayList<>(...)` or pass to jOOQ's `dsl.select(Collection<? extends SelectFieldOrAsterisk>)` overload, both of which accept any `Collection`. Change: `return fields` → `return new ArrayList<>(fields)`, with `fields` typed as `LinkedHashSet<Field<?>>`. Cheapest surface-preserving fix.

Aliased subquery fields (`DSL.field(subquery).as(SDL_name)`) are NOT `.equals()` across distinct arm executions even when emitting the same subquery shape, because `DSL.field(<Select>).as(<name>)` produces a fresh `Field` per call. The Set won't dedupe these — which is correct: two arms with distinct SDL names shouldn't collapse. The dedupe target is specifically raw `TableField` references, which are stable per `(table-alias, column)` pair.

Pipeline test: a `@PipelineTier` test that builds a schema with composite `@node(keyColumns: ["a", "b"])` + a sibling `@field(name: "a")` ColumnField, generates `$fields()`, and asserts the emitted switch + final accumulator type. Place under `graphitron/src/test/java/no/sikt/graphitron/rewrite/`, name `DuplicateProjectionDedupePipelineTest` or similar. The check fans out: the switch arms still emit their `fields.add(table.X)` calls (arm-local emit is unchanged); the accumulator declaration line is now `LinkedHashSet<Field<?>>`; the return statement wraps to `ArrayList`. A `containsOnce` assertion on a parsed `MethodSpec` body is the cleanest read, mirroring `TypeSpecAssertions.hasFieldsArm`.

Execution-tier test: a `@ExecutionTier` test running a federated query against `graphitron-sakila-example` with a composite-NodeId type whose keyColumns overlap a sibling SDL field, asserting no `FieldsImpl` INFO logs appear during fetch. This catches future emitters that introduce new duplicate-projection shapes (e.g. a future variant emitting `table.X` unaliased outside the current set of case arms).

## Out of scope

- Audit of all fetcher name-based lookups (`DSL.field(SDL_name)`) for identity/position correctness. The dedupe fix removes the trigger; the fetcher-side resolution style is orthogonal and not load-bearing for this bug. File a separate roadmap item if the team wants to migrate fetchers to identity-based reads broadly.
- The `requiredProjectionColumns` injection path for `Split*Field` / `TableMethodField` — unchanged. Its `fields.contains(...)` check becomes redundant under Set semantics; either drop it as cleanup or leave for readability.
- Other variants of duplicate projection (e.g. `requiredProjectionColumns` overlapping with switch arms in shapes not covered today). The Set accumulator catches those too, transparently.
