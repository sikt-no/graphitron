---
id: R39
title: "Validate that list fields on tables without a PK require explicit ordering"
status: Backlog
bucket: validation
priority: 2
theme: model-cleanup
depends-on: []
---

# Validate that list fields on tables without a PK require explicit ordering

## Problem

`FieldBuilder.resolveDefaultOrderSpec()` falls back to `OrderBySpec.Fixed([pk ASC])` when a
list field has no `@defaultOrder` or `@orderBy` and the table has a PK. For tables without a
PK, it returns `OrderBySpec.None` instead, which the generators faithfully emit as an empty
`List.of()` — no `ORDER BY` clause. The result is a non-deterministic list every time the
query runs.

The current validator does not catch this. `validateQueryTableField` only calls
`validateCardinality`. The existing "ordering required" checks are narrowly scoped:

- `validatePaginationRequiresOrdering` — only fires when pagination is also present.
- The `SplitTableField` connection check — only fires for connection-cardinality split fields.

Neither covers the plain list case on a no-PK table.

## Impact

Any list-returning `QueryTableField`, `QueryTableInterfaceField`, `TableField`,
`TableInterfaceField`, or `SplitTableField` (non-connection) on a table without a PK silently
produces non-deterministic ordering. Discovered during the interface/union Track A review when
comparing against the legacy generator, which always orders by PK.

## Fix

In `GraphitronSchemaValidator`, add a cross-cutting check alongside
`validatePaginationRequiresOrdering`: for any `SqlGeneratingField` that is list-cardinality and
whose `orderBy` is `OrderBySpec.None`, emit an `AUTHOR_ERROR`:

> "Field 'X.y': list fields must have a deterministic order. Add a primary key to the target
> table, or use @defaultOrder or @orderBy."

This mirrors the pagination check and catches the gap at build time rather than at runtime.
The check can reuse the same `SqlGeneratingField` cast pattern already present at line 120 of
`GraphitronSchemaValidator`.

## Non-goals

- Requiring ordering on single-value fields (ordering is a no-op there).
- Requiring ordering on `@service` or `@tableMethod` fields (the developer's method owns the
  result set; Graphitron doesn't generate the SQL).
