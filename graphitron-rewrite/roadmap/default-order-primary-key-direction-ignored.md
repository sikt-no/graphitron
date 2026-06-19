---
id: R339
title: "@defaultOrder(primaryKey: true) ignores direction and always sorts ASC"
status: Spec
bucket: bug
depends-on: []
created: 2026-06-19
last-updated: 2026-06-19
---

# @defaultOrder(primaryKey: true) ignores direction and always sorts ASC

`@defaultOrder(primaryKey: true, direction: DESC)` emits ascending sort fields regardless of the `direction:` argument, so cursor pagination and default ordering run ASC when the schema asked for DESC. The `fields:` and `index:` variants of the same directive honour `direction:` correctly; only the `primaryKey: true` branch drops it.

## Root cause

`OrderByResolver.resolveOrderEntries` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/OrderByResolver.java:249`) hardcodes `OrderBySpec.SortDirection.ASC` for every PK column:

```java
if (primaryKey) {
    var pkCols = ctx.catalog.findPkColumns(tableSqlName);
    if (pkCols.isEmpty()) return null;
    return pkCols.stream()
        .map(ce -> new OrderBySpec.ColumnOrderEntry(
            new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()),
            null,
            OrderBySpec.SortDirection.ASC))   // ← ignores defaultDirection
        .toList();
}
```

The method already receives the resolved `defaultDirection` (computed by `resolveColumnOrderSpec` via `readDirectionArg(dir, ASC)`) and threads it through the `fields:` branch (`parseDirection(map.get(ARG_DIRECTION), defaultDirection)`, line 272). The `primaryKey:` branch simply does not use it.

## Direction

Use `defaultDirection` instead of the hardcoded `ASC` for the PK sort entries. The downstream `uniformAsc` flag in `resolveColumnOrderSpec` is already derived from the entries' directions, so it follows automatically. Leave the directive-absent implicit-PK fallback in `resolveDefaultOrderSpec` (line 138) ASC, since no direction was specified there. Pin with a fixture asserting `@defaultOrder(primaryKey: true, direction: DESC)` emits `.desc()` on the PK sort field(s), mirroring the existing `fields: … direction: DESC` coverage.

## Reproduction

Observed against `10.0.0-RC17`: `@defaultOrder(primaryKey: true, direction: DESC)` generated `orderBy = List.of(e0.EVENT_ID.asc())` where `.desc()` was expected; the sibling `@defaultOrder(fields: [{name: "VEDTAK_TIDSPUNKT"}], direction: DESC)` generated `.desc()` correctly. Consumer workaround: switch to `@defaultOrder(fields: […], direction: DESC)`.
