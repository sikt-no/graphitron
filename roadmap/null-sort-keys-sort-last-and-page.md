---
id: R1112
title: "A NULL sort key sorts last in both directions, and keyset paging returns the rows that carry one"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-27
last-updated: 2026-10-27
---

# A NULL sort key sorts last in both directions, and keyset paging returns the rows that carry one

## Goal

Paging through a connection returns every row exactly once, including rows whose sort key is `NULL`, and those rows always come at the end of the order, whether it runs ascending or descending. A *connection* is a paginated list field. Each page hands out a *cursor* holding the sort values of the page's last row, and the next page *seeks* from it: it keeps only the rows that sort strictly after those values. That comparison is never true for a `NULL`, so today the seek drops rows. Under an ascending order PostgreSQL puts `NULL`s last, and once a page has passed the non-null keys the `NULL` rows never come back. A cursor that itself carries a `NULL` returns an empty page. After this item every order entry renders `NULLS LAST` in both directions, and the seek predicate is null-aware to match: rows after the cursor's value and then the `NULL` rows, or only the `NULL` rows after the cursor's position among them when the cursor's key is `NULL`. Backward paging (`last`/`before`) and a runtime `direction:` flip keep `NULL`s at the end instead of mirroring them to the front.

## Context

- **Who hits it today.** Any `@order` or `@defaultOrder` column on the field's own table that is nullable. The sakila fixtures sort only on `NOT NULL` columns, so no test exercises it. Validity periods are the realistic case: sorting by an end date where `NULL` means "still valid". Graphitron 9 has the same defect (`QueryHelper.getOrderByValues` feeding `.seek(...)` at `v9.3.0`).
- **Who makes it common.** R1083 (`@order` by a column on a to-one related table, or by a method-supplied expression) produces a `NULL` key for every parent with no related row, so it depends on this item.
- **Where it lives today.** The cursor already encodes `NULL` (`ConnectionHelper.encodeCursor` writes it as `\u0001`). The defect is in the seek: `.seek(page.seekFields())` in `RootLauncherRenderer` and `BatchedRowsFragments` renders a plain row-value comparison.
- **Behaviour change.** A descending order on a nullable column puts its `NULL` rows first today, and they move to the end. The release note says so. `DESC NULLS LAST` cannot be served by a plain index scanned backward, so a large table sorted descending on a nullable column may need an index declared `DESC NULLS LAST`; the manual says so.
- **Out of scope.** A per-entry `nulls: FIRST|LAST` argument on `FieldSort`. It can be added later if someone needs the other placement.
