---
id: R444
title: "Scalar @reference terminal column read must resolve terminal table by class identity, not bare SQL name"
status: In Progress
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-07-08
last-updated: 2026-07-08
---

# Scalar @reference terminal column read must resolve terminal table by class identity, not bare SQL name

## Problem

This is the sixth site of the schema-qualified `@table` bug class (siblings R396, R440, R441, R442, R422, all Done). R422's changelog claims it "closes the schema-qualified `@table` bug class," but R422 only fixed the object-return-type terminal verdict (`BuildContext.computeTerminalTargetVerdict`). The scalar `@reference` terminal column read is a separate, unaudited path with the identical bare-name-vs-identity defect.

## Mechanism

For a scalar field with `@reference(path:)`, `FieldBuilder` (`FieldBuilder.java:6105`) calls `ServiceCatalog.resolveColumnForReference`, which delegates to `ServiceCatalog.terminalTableSqlName` (`ServiceCatalog.java:92`-100). That method walks the FK path and returns `hop.targetTable().tableName()` (`ServiceCatalog.java:97`), collapsing the FK-pinned `TableRef` (which carries a `tableClass` `ClassName` identity, per R441) down to a bare SQL name string. `resolveColumnInTable` then calls `JooqCatalog.findColumn(String tableSqlName, …)` (`JooqCatalog.java:1064`), which does `findTable(tableSqlName)` → `TableResolution.Ambiguous` when the terminal table name exists in two generated schemas → `.asEntry()` returns empty → `FieldBuilder` (`FieldBuilder.java:6112`) emits `Author error: column '<c>' could not be resolved in the jOOQ table`. The column genuinely exists on both schemas' copies of the terminal table; the lookup just can't pick one by bare name, and there is no author-side workaround (the `@reference` key is `TABLE__CONSTRAINT` on the source table, so there's no syntax to qualify the FK terminal, same as gap A in `opptak/docs/graphitron-qualified-names-gaps.md`).

## Live repro

On the opptak branch `feature/SHIIT-767-opptak-v2-skjema`: type `Opptakshendelsestype @table(name: "opptakshendelsestype_opptakstype")` with two scalar fields, `navn: String` and `kategori: String! @field(name: "opptakshendelsekategori")`, both `@reference(path: [{key: "opptakshendelsestype_opptakstype__opptakshendelsestype_opptakstype_opptakshendelsestype_fk"}])`. That FK's source is the join table `opptakshendelsestype_opptakstype` (opptak-only); its target is `opptakshendelsestype`, which exists in both `opptak` and `opptak_v2`, each carrying `navn` and `opptakshendelsekategori`. These are the last 2 of the original 31 author-errors; R440/R441/R442/R422 cleared the other 29.

## Design

Same pattern as R440/R441/R422 ("decide once, carry the decision as a type"): the FK path's hops already carry identity-resolved `TableRef`s on `targetTable()` (R441 populated `tableClass` on catalog-derived refs; R440 resolves FK endpoints by class identity off the FK itself). The bug is that `ServiceCatalog.terminalTableSqlName` throws that identity away by returning `tableName()` as a bare string, and `resolveColumnInTable` re-resolves the string through `JooqCatalog.findTable(String)`, which is ambiguous on a colliding name. The fix stops the collapse: carry the terminal `TableRef` to the column lookup and never re-resolve a name the path already resolved.

The empty-path case is not broken today (the start table's `tableName()` is the verbatim `@table` echo, which `findTable` resolves qualified), but the fix routes it through the same ref uniformly so there is one resolution story, not two.

## Implementation

All in `graphitron/src/main/java/no/sikt/graphitron/rewrite/`:

- `ServiceCatalog.java`: replace `terminalTableSqlName(List<JoinStep>, String)` and `terminalTableSqlNameForReference(List<JoinStep>, TableBackedType)` with one ref-carrying resolver, `Optional<TableRef> terminalTableForReference(List<JoinStep> path, TableRef start)`: walk the path; any step that is not an FK-derived `JoinStep.Hop` with `On.ColumnPairs` yields empty (same condition-only bail-out as today); otherwise the terminal is the last hop's `targetTable()`; an empty path yields `start`.
- `model/TableRef.java`: add `Optional<ColumnRef> column(String columnName)`, the single model-side home for the column matcher over `allColumns` with `JooqCatalog.findColumn`'s matching order (`javaName` `equalsIgnoreCase` first across all columns, then `sqlName`). One matcher home, not an inlined copy at the call site, so it cannot drift from a second restatement and is available to the sibling item below. (`allColumns` is empty only on refs constructed outside the catalog flow, i.e. hand-built test fixtures; every ref reaching this path is catalog-constructed. The "catalog closed at emit time" rationale in `allColumns`' javadoc is not the driver here — the catalog is still open at classify time — the driver is consuming the already-carried decision instead of re-resolving it.) Alternative considered and rejected: `JooqCatalog.findTableByClassName(ClassName)` bridging javapoet `ClassName` to reflection names and delegating to `findColumn(Table<?>, String)`; also single-sources the matcher but adds a catalog lookup surface and a name-bridge for something the ref already knows.
- `ServiceCatalog.java`: retype `resolveColumnForReference(String columnName, List<JoinStep> path, ...)` to take the start as `TableRef` (drop the `String startSqlTableName` overload; the `TableBackedType` convenience overload delegates via `sourceType.table()`). Resolve the column via `terminalTableForReference(...).flatMap(t -> t.column(columnName))`, no bare-name catalog re-resolve.
- `FieldBuilder.java:6105`: pass `tableType.table()`; the unknown-column diagnostic (`FieldBuilder.java:6107`) switches from `terminalTableSqlNameForReference` + `JooqCatalog.columnJavaNamesOf(bareName)` (also ambiguity-broken: empty candidates on a colliding terminal) to enumerating candidates from the terminal `TableRef.allColumns()` java names.
- `FieldBuilder.java:1373` (argument `@reference` filter) and `BuildContext.java:2206` (input field): both already hold the resolved `TableRef` (`rt` / `resolvedTable`); pass it instead of `.tableName()`. These callers share the fixed resolver, so the argument-filter and input-field shapes are covered by the same change.

Behavioral deltas: a colliding terminal table now resolves (the bug fix); everything else (condition-only paths, unknown columns, empty paths) keeps today's outcomes.

## Scope

Two adjacent bare-name column reads stay out of scope, deliberately:

- The direct non-`@reference` scalar read (`ServiceCatalog.resolveColumn`, `ServiceCatalog.java:73`, consumed at `FieldBuilder.java:6125`) also resolves by string, but the string is the source type's verbatim `@table` echo, which resolves schema-qualified (R396), so the author has a workaround (qualify the `@table(name:)`). It survives R444 by design; do not read "String overloads retired" as "all bare-name column reads retired".
- The participant cross-table `@reference` path (`TypeBuilder.java:845`, `:861`) is a genuine seventh site of the no-workaround class: it holds the FK-pinned `fk.targetTable()` `TableRef` and collapses it to `tableName()` for both the candidate hint and the actual column resolve, and it never routes through `resolveColumnForReference`, so this item's overload retirement cannot catch it. Tracked as its own Backlog item (R445, `participant-cross-table-column-read-identity`), which consumes the `TableRef.column` matcher this item introduces.

## Tests

Pipeline-tier test `QualifiedTerminalReferenceColumnPipelineTest`, sibling to R422's `QualifiedReturnTypeReferencePipelineTest` and R396's `QualifiedSourceReferencePipelineTest`, over the existing multischema fixture. The needed shape already exists, no new DDL and no jOOQ schema version bump: `multischema_a.event_log` carries FK `event_log_event_id_fkey` into `multischema_a.event`; `event` collides across `multischema_a`/`multischema_b`; column `name` exists only on A's copy, `code` only on B's.

SDL shape: `type EventLog @table(name: "event_log")` (bare name, unique to A) with a scalar field `eventName: String @field(name: "name") @reference(path: [{key: "event_log_event_id_fkey"}])`, mirroring the live repro (source table unique, terminal colliding).

1. Green: the `name` read through the FK classifies as `ColumnReferenceField` (impossible today: the bare-name terminal lookup is ambiguous and demotes to `UnclassifiedField`). Assert the resolved column too, so the classification is not vacuous.
2. Schema-pinned, not search-all-schemas: the same FK path reading `code` (exists only on `multischema_b.event`) still rejects with the unknown-column author error. This pins that the fix resolves against the FK-pinned schema A copy rather than scanning every schema for a match.
3. Genuine unknown column (`bogus`) still rejects with the author error, and the diagnostic's candidate list is now non-empty (names A's `event` columns), pinning the diagnostic-path fix.

## Roadmap entries

On Done: delete this file, add a `changelog.md` entry. Do not repeat R422's mistake of claiming the whole bug class closed: the accurate framing is "closes the FK-terminal `@reference` column-read sub-class (scalar output field, argument filter, input field); the participant cross-table path is tracked in R445".
