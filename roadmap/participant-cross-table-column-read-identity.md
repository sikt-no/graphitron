---
id: R445
title: "Participant cross-table @reference column read must resolve the FK terminal by class identity, not bare SQL name"
status: Ready
bucket: bug
priority: 3
theme: interface-union
depends-on: [terminal-reference-column-read-identity]
created: 2026-07-08
last-updated: 2026-07-08
---

# Participant cross-table @reference column read must resolve the FK terminal by class identity, not bare SQL name

## Problem

Seventh site of the schema-qualified `@table` bug class (siblings R396, R440, R441, R442, R422 Done; R444 Ready), found by R444's spec-time audit. The participant cross-table `@reference` extraction in `TypeBuilder.extractCrossTableFields` (`TypeBuilder.java:811`) holds the FK-pinned `fk.targetTable()` `TableRef` (identity-carrying since R441) and collapses it to the bare `tableName()` string for both the detail-only candidate hint (`TypeBuilder.java:845`) and the actual column resolve (`TypeBuilder.java:861`, via `JooqCatalog.findColumn(String, ...)`). When the FK terminal's bare name exists in two generated schemas, `findTable` returns `Ambiguous`, the column resolve comes back empty, and the field is skipped from the participant's cross-table set (`TypeBuilder.java:862`) instead of classifying as a `ParticipantColumnReferenceField`.

Like R444, there is no author-side workaround: the FK terminal is not author-named (the `@reference` key is `TABLE__CONSTRAINT` on the source table; gap A in `opptak/docs/graphitron-qualified-names-gaps.md`). This path never routes through `ServiceCatalog.resolveColumnForReference`, so R444's overload retirement cannot catch it.

## Mechanism and observable

The skip at `TypeBuilder.java:862` is not the end of the story. A field absent from the participant's cross-table set falls through to `FieldBuilder`'s scalar `@reference` path (`lookupParticipantCrossTableField` returns null at `FieldBuilder.java:6095`, execution continues at `:6101`).

- **Before R444's implementation lands:** that fallback path is ambiguity-broken in the same way (`resolveColumnForReference` collapses the terminal to a bare name), so the field lands as `UnclassifiedField` with the author error `column '<c>' could not be resolved in the jOOQ table`. Loud, but pointing at the wrong cause.
- **After R444 lands:** the fallback resolves the terminal by carried identity and succeeds, so the field silently misclassifies as a plain `ColumnReferenceField` instead of `ParticipantColumnReferenceField`. The interface fetcher then emits no conditional LEFT JOIN / alias projection for it, and the classification of a participant field comes to depend on whether an unrelated schema happens to hold a same-named table, which is wrong regardless of the runtime shape the misclassified field produces.

R444 landing therefore converts this defect from a loud author error into a silent misclassification. That is the sequencing rationale: this item consumes R444's `TableRef.column(String)` matcher (hence `depends-on`), and should land promptly once R444 ships.

## Design

Same pattern as R440/R441/R422/R444 ("decide once, carry the decision as a type"): the method already holds identity-resolved `TableRef`s for both tables it queries the catalog about — `fk.targetTable()` (FK-pinned since R441) and `interfaceTable` (the resolved interface table passed in). The bug is that it re-resolves their bare `tableName()` strings through `JooqCatalog`'s string lookups, which are ambiguous on a colliding name. The fix consumes the carried refs directly and never re-resolves a name the parse boundary already resolved:

- The actual column resolve (`TypeBuilder.java:861`) uses `fk.targetTable().column(columnSqlName)`, the matcher R444 introduces on `TableRef` (same matching order as `JooqCatalog.findColumn(Table, String)`: `javaName` case-insensitive first, then `sqlName`). It returns `ColumnRef` directly, so the `ColumnEntry` → `ColumnRef` conversion at `TypeBuilder.java:863` disappears.
- The detail-only candidate hint (`TypeBuilder.java:845`) enumerates `fk.targetTable().allColumns()` sql names instead of `JooqCatalog.columnSqlNamesOf(bareName)` (which is also ambiguity-broken: empty candidates on a colliding terminal, so the R388 rejection prose loses its "did you mean" hint exactly when the schema layout is confusing).
- The R388 defect-2 guard's base-table lookups (`TypeBuilder.java:843`, `:844`) switch to `interfaceTable.column(columnSqlName)` / `interfaceTable.allColumns()` sql names. This is a ride-along for uniformity, not a live bug: those lookups use the interface's verbatim `@table` echo, and an interface whose bare echo were ambiguous would have failed type classification before reaching this method, so the echo always resolves here. Retiring them anyway leaves `extractCrossTableFields` with zero string-keyed catalog reads, one resolution story instead of two.

`allColumns()` is populated on every catalog-constructed ref (empty only on hand-built fixture refs, which do not reach this path), same argument as R444's design note.

Out of scope, consistent with R444's scope section: `ctx.parsePath` at `TypeBuilder.java:819` receives the interface's `tableName()` echo as the path start; the start table is author-named and qualifiable per R396, so the author has a workaround there. Do not read this item as retiring every bare-name read in `TypeBuilder`.

## Implementation

Single method, `graphitron/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java` (`extractCrossTableFields`):

- `:843` guard predicate: `ctx.catalog.findColumn(interfaceTable.tableName(), columnSqlName).isPresent()` → `interfaceTable.column(columnSqlName).isPresent()`.
- `:844` base column set: `ctx.catalog.columnSqlNamesOf(interfaceTable.tableName())` → `interfaceTable.allColumns()` mapped to `sqlName()`.
- `:845` detail-only candidates: `ctx.catalog.columnSqlNamesOf(fk.targetTable().tableName())` → `fk.targetTable().allColumns()` mapped to `sqlName()`.
- `:861`-`:863` column resolve: `ctx.catalog.findColumn(fk.targetTable().tableName(), columnSqlName)` + manual `ColumnRef` construction → `fk.targetTable().column(columnSqlName)`, consuming the returned `ColumnRef` directly.

Behavioral deltas: a colliding FK terminal now yields a `ParticipantColumnReferenceField` (the bug fix), and the R388 rejection's candidate hint is non-empty when the detail table collides; everything else (unknown columns skip to the field-level classifier, guard fires on base-resident columns) keeps today's outcomes.

## Tests

Pipeline-tier `QualifiedParticipantCrossTableReferencePipelineTest`, sibling to R444's `QualifiedTerminalReferenceColumnPipelineTest` and R422's `QualifiedReturnTypeReferencePipelineTest`, over the existing multischema fixture. No new DDL and no jOOQ schema bump: `multischema_a.event_log` (bare name unique to A; columns `event_log_id`, `event_id`, `note`) carries FK `event_log_event_id_fkey` into `multischema_a.event`; `event` collides across `multischema_a`/`multischema_b`; column `name` exists only on A's copy, `code` only on B's.

SDL shape, mirroring `DiscriminatorReferenceContradictionPipelineTest`: a single-table discriminated interface over `event_log` using `note` as the discriminator column, with a participant carrying the cross-table `@reference`:

```graphql
interface Entry @table(name: "event_log") @discriminate(on: "note") {
    entryId: Int! @field(name: "event_log_id")
}
type AlertEntry implements Entry @table(name: "event_log") @discriminator(value: "ALERT") {
    entryId:   Int!   @field(name: "event_log_id")
    eventName: String @field(name: "name") @reference(path: [{key: "event_log_event_id_fkey"}])
}
type Query { allEntries: [Entry!]! }
```

1. Green: `AlertEntry.eventName` classifies as `ParticipantColumnReferenceField` (impossible today: the bare-name terminal lookup is ambiguous, the field is skipped from the cross-table set, and the fallback path either author-errors or, post-R444, misclassifies as `ColumnReferenceField`). Assert the resolved column too, so the classification is not vacuous.
2. Schema-pinned, not search-all-schemas: the same shape reading `code` (exists only on `multischema_b.event`) still rejects with the unknown-column author error, and the diagnostic's candidates name A's `event` columns, not B's. This pins that the fix resolves against the FK-pinned schema A copy rather than scanning every schema for a match. (The rejection surfaces through the `FieldBuilder` fallback path, which is R444's fixed diagnostic; the pipeline observable is the author error with A-side candidates.) This test is load-bearing, not a nicety: the silent skip at `TypeBuilder.java:862` stays silent after the fix, and its correctness then rests entirely on `fk.targetTable().column()` being identity-exact, so the fall-through carries only genuine unknowns; test 2 is the enforcer of that story.
3. Guard ride-along: the same shape reading `event_id` (exists on both `event_log` and A's `event`) still trips the R388 contradiction rejection, and its detail-only candidate hint is non-empty (names `name`, A's only detail-resident column). This pins the `:843`-`:845` changes: before the fix the colliding detail table produced an empty candidate list.

## Roadmap entries

On Done: delete this file, add a `changelog.md` entry. Accurate framing, continuing R444's discipline: "closes the participant cross-table `@reference` sub-class, the seventh audited site of the schema-qualified `@table` class; the author-qualifiable bare-name reads (`ServiceCatalog.resolveColumn`, path-start echoes) remain by design because the author can qualify `@table(name:)`". Do not claim the whole bug class closed; claim the audited no-workaround FK-terminal sub-class (R444 + R445) closed.
