---
id: R414
title: "Split/DataLoader connections omit table+condition from ConnectionResult, so totalCount is always null on nested connections"
status: In Review
bucket: bug
priority: 3
theme: pagination
depends-on: []
created: 2026-07-01
last-updated: 2026-07-02
---

# Split/DataLoader connections omit table+condition from ConnectionResult, so totalCount is always null on nested connections

## Problem

A nested (non-root) connection field advertises `totalCount: Int` in the emitted SDL but always resolves it to `null`, even when the connection has rows. Only root connection fields return a real count.

Reproduced against the utdanningsregisteret consumer schema:

```graphql
# root connection — totalCount = 39
{ utdanningsspesifikasjoner(first: 1) { totalCount } }

# nested connection — totalCount = null (even though nodes are present)
{ utdanningsspesifikasjoner(first: 5) {
    nodes { utdanningsmuligheter { totalCount nodes { kode } } } } }
```

The synthesised connection type (`UtdanningsspesifikasjonUtdanningsmuligheterConnection`) declares `totalCount: Int`, so a client is offered a field that can never carry a value on any split/DataLoader-backed connection. The field fetcher is wired identically to the root case (`<Conn>Fetchers.totalCount` → `ConnectionHelper.totalCount(env)`); the divergence is entirely in how the `ConnectionResult` source is built.

## Root cause (current shape is deliberate; the Decision below settles the client-facing consequence)

`ConnectionResult` has two relevant constructors (`ConnectionResultClassGenerator`):

- **root path** — the `(result, page, table, condition)` constructor (`pageWithSourceConstructor`, `:99`). `TypeFetcherGenerator` binds `(table, condition)` here explicitly "so `ConnectionHelper.totalCount` can issue [the count]" (`:4815`).
- **split path** — the `(result, page)` constructor (`pageConstructor`, `:89`), used by `SplitRowsMethodEmitter`'s `scatterConnectionByIdx` (`:1198`), which the class comment notes "has no single (table, condition)" (`ConnectionResultClassGenerator:54`, `:85`).

`ConnectionHelper.totalCount` then early-returns on the split shape by design (`ConnectionHelperClassGenerator:262-275`):

```java
if (cr.table() == null || cr.condition() == null) return null;
return dsl.selectCount().from(cr.table()).where(cr.condition()).fetchOne(0, Integer.class);
```

So the null is a **known limitation** (the in-code comments acknowledge the split scatter has no bound `(table, condition)`), not an accidental drop. What is unsettled is the *SDL contract*: the synthesised split-connection type still exposes `totalCount`, so the schema promises a field the runtime structurally cannot fulfil.

## Decision: compute it (resolution 1)

Serve a real per-parent `totalCount` on `SplitTableField` + `Connection` fields. Rationale:

- The polymorphic DataLoader-batched connection path (B4c-2, `MultiTablePolymorphicEmitter.buildBatchedConnectionRowsMethod`) already serves a parent-scoped `totalCount`: each per-parent `ConnectionResult` reuses the shared pre-window derived table with a per-parent `__idx__.eq(i)` condition, and `ConnectionHelper.totalCount` runs `SELECT count(*) FROM pages WHERE __idx__ = i` lazily on selection (pinned by `projectItemsConnection_totalCount_isParentScoped`). Stripping the field from single-table split connections (resolution 2) would now be inconsistent with a sibling split path that fulfils it.
- Resolution 2 also makes schema shape depend on inline-vs-split, an operational choice that should stay invisible to clients.
- `SplitRowsMethodEmitter.buildConnectionMethod` is the last *classification path* producing null-`(table, condition)` carriers on reachable queries. (One unreachable producer remains and stays; see Implementation.)

Count semantics match root connections: the count covers the whole per-parent connection, independent of the cursor window. Cost profile matches B4c-2's documented tradeoff: zero count SQL when `totalCount` is unselected (graphql-java only invokes the registered resolver on selection), N count queries for a batch of N parents when selected.

Rejected alternatives:

- **One grouped count query per batch** (`SELECT __idx__, count(*) ... GROUP BY __idx__` gated on `env.getSelectionSet().contains("totalCount")` in the rows method): one query instead of N, but eager (runs at rows time, not resolver time) and introduces selection-set sniffing where the rest of the design gets laziness structurally from resolver dispatch. Divergent from B4c-2, which already accepted N-lazy-queries.
- **Window `count(*) OVER (PARTITION BY __idx__)` column in the page query**: wrong semantics; the seek predicate filters before window computation, so pages after a cursor would undercount.

## Implementation

All in the generator; `ConnectionHelper.totalCount` needs no behavioural change.

- `SplitRowsMethodEmitter.buildConnectionMethod`:
  - Hoist the WHERE into a local (`Condition where = ...`) and reference it from both queries. `buildWhereCondition` must be called exactly once: it declares FK-target alias locals into the method body as a side effect (`FkTargetConditionEmitter.declareAliases`), so a second call would emit duplicate local declarations.
  - Emit a count-source derived table alongside the existing windowed page query, with no orderBy/seek so the count is cursor-independent (the page query's seek is structurally separate already, attached via `.orderBy().seek()` after the same WHERE):

    ```java
    Table<?> countSource = dsl
        .select(idxField.as("__idx__"))
        .from(<same join topology via emitFromBridgeAndParentJoin>)
        .where(where)
        .asTable("countSource");
    ```
  - Pass `countSource` into `scatterConnectionByIdx`.
- `SplitRowsMethodEmitter.buildScatterConnectionByIdxHelper`: add a `Table<?> countSource` parameter; construct each per-parent carrier with the existing 4-arg convenience constructor:

  ```java
  out.add(new ConnectionResult(buckets.get(i), page, countSource,
      countSource.field("__idx__", Integer.class).eq(DSL.inline(i))));
  ```
- `ConnectionResultClassGenerator`: remove the now-dead two-arg `(result, page)` constructor; `scatterConnectionByIdx` was its only caller (root fetcher, polymorphic batched, and polymorphic empty-participants paths all use the 4-arg form). This is generated-into-consumer source, not published API; no external construction sites exist.
- **Comment re-pointing, not deletion.** The nullable `(table, condition)` shape and the `if (cr.table() == null || cr.condition() == null) return null` guard in the generated `ConnectionHelper.totalCount` both stay: `MultiTablePolymorphicEmitter.buildRootConnectionFetcher` still emits a validator-unreachable defensive empty-participants path constructing `new ConnectionResult(List.of(), page, null, null)`. Rewrite the "Split-Connection scatter passes null for both" comments in `ConnectionResultClassGenerator` (class javadoc, field comment) and the "until per-parent count plumbing lands" comment in `ConnectionHelperClassGenerator.totalCount` to name that remaining producer instead. Do not narrow the components to non-null.
- Sakila fixture: add `totalCount: Int` to `ActorsConnection` in `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls` (shared by `Film.actorsConnection` and `Film.actorsOrderedConnection`, both `@splitQuery`).

### Relation to B4c-2 (stated honestly)

This mirrors B4c-2's count *semantics* (shared derived table, per-parent `__idx__.eq(i)`, N lazy counts) but not its *structure*: B4c-2 materialises one `pagesTable` that feeds both the ranked window and the count, whereas here the join topology is emitted twice (page query and `countSource`). The structural unification, having the split page query's window select from a materialised pre-window table, is a deliberate non-goal: the split path's `.orderBy(page.effectiveOrderBy())` and `.seek()` reference live terminal-alias columns, and re-pointing them at derived-table fields by name would rework well-tested pagination for no user-visible gain. Drift risk between the two emissions is low; both go through the same `emitFromBridgeAndParentJoin` helper and the single hoisted `where` local.

## Tests

Execution tier (`GraphQLQueryTest`, hosted on `Film.actorsConnection`):

- **Per-parent counts**: `{ films { filmId actorsConnection(first: 1) { totalCount } } }` returns totalCount 2 for films 1 and 2, and 1 for films 3, 4, 5 (seeded `film_actor` rows). `first: 1` distinguishes the connection count from the page size; today every value would be null.
- **Cursor independence**: paging film 1's connection with an `after` cursor still reports totalCount 2.
- **Lazy on selection**: `SQL_LOG` shows no `select count` statement when `totalCount` is unselected; when selected, count statements appear (per-parent, matching the B4c-2 cost profile).

Pipeline tier (`SplitTableFieldPipelineTest`): structural assertion only, `scatterConnectionByIdx` carries the `Table<?> countSource` parameter. No body-string assertions on generated method bodies (banned at every tier); the behavioural proof lives in the execution tier.

## Non-goals

- Restructuring the split page query onto a shared materialised pre-window derived table (true B4c-2 structural unification); possible follow-up if the dual topology emission ever drifts.
- Touching the polymorphic emitter or its defensive empty-participants null path.
- Per-parent `totalCount` for any hypothetical future split shapes (`SplitLookupTableField` has no connection arm today; `buildForSplitLookupTable` routes only to the list method).
