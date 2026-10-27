---
id: R1106
title: "A single-table connection whose order is not unique gets the primary key as tiebreaker, so keyset pages stop dropping rows"
status: Backlog
bucket: bug
priority: 2
theme: codegen-correctness
depends-on: []
created: 2026-10-27
last-updated: 2026-10-27
---

# A single-table connection whose order is not unique gets the primary key as tiebreaker, so keyset pages stop dropping rows

## Goal

Paging through a single-table `@asConnection` field returns every row exactly once, even when the configured order is not unique. Today the order a single-table connection seeks on is exactly the listed `@defaultOrder` / `@order` columns (`OrderByResolver.resolveColumnOrderSpec`, then `effectiveOrderBy` in the generated `ConnectionHelper`), so rows that tie with the last row of a page on the sort key are skipped by the next page's `seek`, and `hasNextPage` turns false early. In the report, `totalCount` was 52 while the pages returned 49; in fs-admin 83 of 107 roles came back. The multitable path already appends tiebreakers; the single-table path has none, and the deterministic-order validator only rejects a missing order, not a non-unique one. Reported against 10.0.0-RC41 as [GitHub issue #559](https://github.com/sikt-no/graphitron/issues/559).

## Relation to the related-table ordering item

`order-by-related-table-column.md` writes down the rule this item needs ("append the primary key, ascending, unless the configured own-table columns cover the primary key or a unique constraint") and its plan step 4 assumes the mechanism may already exist. It does not: `resolveOrderEntries` appends nothing. The general own-table rule belongs here, so the related-table item only has to teach the coverage test to ignore path columns. Open questions for Spec: append in the last field's direction (the reporter's suggestion) or ascending; whether a `@node(keyColumns:)` key or a synthetic key of a view counts as the key to append; and what a table with neither a primary key nor a covering unique key gets (a build error is the candidate).
