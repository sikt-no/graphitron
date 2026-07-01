---
id: R414
title: "Split/DataLoader connections omit table+condition from ConnectionResult, so totalCount is always null on nested connections"
status: Backlog
bucket: bug
priority: 3
theme: pagination
depends-on: []
created: 2026-07-01
last-updated: 2026-07-01
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

## Root cause (current shape is deliberate, but its client-facing consequence is not settled)

`ConnectionResult` has two relevant constructors (`ConnectionResultClassGenerator`):

- **root path** — the `(result, page, table, condition)` constructor (`pageWithSourceConstructor`, `:99`). `TypeFetcherGenerator` binds `(table, condition)` here explicitly "so `ConnectionHelper.totalCount` can issue [the count]" (`:4815`).
- **split path** — the `(result, page)` constructor (`pageConstructor`, `:89`), used by `SplitRowsMethodEmitter`'s `scatterConnectionByIdx` (`:1198`), which the class comment notes "has no single (table, condition)" (`ConnectionResultClassGenerator:54`, `:85`).

`ConnectionHelper.totalCount` then early-returns on the split shape by design (`ConnectionHelperClassGenerator:262-275`):

```java
if (cr.table() == null || cr.condition() == null) return null;
return dsl.selectCount().from(cr.table()).where(cr.condition()).fetchOne(0, Integer.class);
```

So the null is a **known limitation** (the in-code comments acknowledge the split scatter has no bound `(table, condition)`), not an accidental drop. What is unsettled is the *SDL contract*: the synthesised split-connection type still exposes `totalCount`, so the schema promises a field the runtime structurally cannot fulfil.

## Direction (for Spec)

Two coherent resolutions; pick deliberately:

1. **Compute it.** Give the split scatter a `(table, condition)` (or a per-parent count aggregate) so `totalCount` returns the real per-parent count. The split fetcher already knows the child table and the parent-correlation predicate it joins on; the count is `selectCount` over that same correlation, grouped/scattered by parent — analogous to how the rows are scattered by `__idx__`.
2. **Don't advertise it.** If per-parent `totalCount` is out of scope, strip `totalCount` from the synthesised connection type when the carrier resolves to a split/DataLoader connection, so the SDL stops promising a dead field.

Resolution (1) keeps the connection contract uniform across root and nested; (2) is cheaper but makes `totalCount` presence depend on inline-vs-split, which is otherwise an operational choice invisible in the schema.

## Regression coverage

Add an execution-tier assertion that a nested connection with known cardinality returns the correct `totalCount` (resolution 1) or that the field is absent from the split-connection type (resolution 2). The Sakila fixtures have nested connections (e.g. a customer's rentals) that can host the check; today such a field would return `null` and no test pins it.
