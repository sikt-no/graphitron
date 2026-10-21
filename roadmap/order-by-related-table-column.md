---
id: R1083
title: "@order: sort by a column on a related table via path"
status: Backlog
bucket: architecture
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-10-21
last-updated: 2026-10-21
---

# @order: sort by a column on a related table via path

## Goal

An `@order` enum value can sort by a column that lives on a related table, so a connection of `Utdanningsinstans` can be ordered by `Utdanningsinstansnavn.navn_spesifikasjon` without denormalizing the value into the parent table. The author names the related table with a `path:` argument that uses the same `ReferenceElement` shape as `@reference(path:)` (GitHub issue #562).

## The gap

`@order(fields: [{name: ...}])` resolves every `FieldSort.name` against the field's own table (`OrderByResolver.resolveOrderEntries` calls `ctx.catalog.findColumn(tableSqlName, colName)`), so a column on a joined table is a build rejection. The only workaround today is copying the value into the main table.

## Proposed surface

Add an optional `path: [ReferenceElement!]` to `FieldSort`, reusing the existing input type and its `table:` / `key:` / `condition:` semantics, so there is one way to say "walk from here to that table" across the schema:

```graphql
enum QueryUtdanningsinstanserOrderByFieldInput {
    FRA_ARSTALL @order(fields: [{name: "ARSTALL_FRA"}])
    NAVN        @order(fields: [{
        name: "NAVN_SPESIFIKASJON",
        path: [{key: "utdanningsinstansnavn_utdanningsinstans_fkey"}]
    }])
}
```

`name` is resolved against the table the path lands on; with no `path:` nothing changes. The same argument belongs on `@defaultOrder` since both directives share `FieldSort`. Path inference follows `@reference`: when exactly one FK joins the two tables the path may be omitted from a hop-less form only if we decide to allow `path: []`; the first cut should require an explicit path so a column name never silently changes table.

## Plan

1. SDL and docs: add `path` to `FieldSort` in `directives.graphqls`; update `docs/manual/reference/directives/order.adoc` and `defaultOrder.adoc`, cross-linking `reference.adoc`.
2. Resolution: in `OrderByResolver.resolveOrderEntries`, when `path` is present, resolve it through the shared `BuildContext.parsePath` (start = field's table, no fixed target, terminal table taken from the last hop), then look the column up on the terminal table. Reuse its error messages rather than a parallel parser.
3. Model: widen `OrderBySpec.ColumnOrderEntry` with the resolved join steps (empty for own-table columns) so the emitter knows which table alias the column belongs to.
4. Emission: join the path in the generated select and order by the aliased column, including the keyset-pagination cursor and the backward-flip, which today assume columns of the root table.
5. Validation: reject at classification, with an author-facing message, anything that cannot give a deterministic order (see below).

## Design forks to settle in Spec

- **Cardinality.** `Utdanningsinstans` to `Utdanningsinstansnavn` looks one-to-many; ordering by a to-many column duplicates parent rows or needs an aggregate. Recommendation: first cut accepts only paths whose every hop is to-one (FK on the source side, or a `condition:` the author vouches selects one row is out of scope), and rejects to-many with a message naming the hop. Whether the real consumer case is to-one (for example via a `condition:` on a name type) must be confirmed with the reporter before Ready.
- **Join kind.** LEFT JOIN so parents without a related row stay in the result; nulls placement then needs a stated rule and must be part of the cursor.
- **Tiebreaker.** The primary-key tiebreaker in `OrderBySpec.Fixed` must still apply so pagination stays stable.
- **`index:` and `primaryKey:`** are table-scoped and stay own-table only.

## Tests

Pipeline-tier classification cases (accepted path, unknown column on landing table, to-many rejection) and an execution-tier connection test against the sakila fixtures ordering a connection by a column on a to-one related table, forward and backward pagination.
