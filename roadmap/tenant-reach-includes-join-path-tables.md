---
id: R995
title: "The cross-scope tenant check sees every table a batched statement joins"
status: Backlog
bucket: architecture
priority: 6
theme: runtime-connection
depends-on: []
created: 2026-10-07
last-updated: 2026-10-07
---

# The cross-scope tenant check sees every table a batched statement joins

## Goal

In a multi-tenant build, a field whose one statement joins through tables of both scopes is refused at build time, naming the tables, instead of compiling and failing at runtime on a database that lacks one of them. Today the cross-scope check (`TenantBindingIndex.Fold.reachedTables`, which feeds the "touches tenant-scoped and global tables in one statement" rejection in `armOf`) reads only a field's return table. So a `@splitQuery` or inline reference whose `@reference` path passes through a global intermediate table on the way to a tenant-scoped one classifies as if it touched only the tenant table. It then runs on that tenant's database, where the global table does not live; the reverse case, a global terminal reached through a tenant-scoped intermediate, runs on the default source. Widening the reach to every hop's target table makes the one existing rejection cover these paths. It changes verdicts for schemas that compile today, which is why it is its own item.

## Provenance

Raised by the principles consult on R992, whose rule reads every hop's target table (its condition 5) while the existing cross-scope rung reads only the return table: two answers to "which tables does this statement touch". R992 keeps its own condition and names the asymmetry; this item gives the build one answer.
