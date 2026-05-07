---
id: R102
title: "Batch DataLoader for non-connection ChildField.UnionField / ChildField.InterfaceField"
status: Backlog
bucket: architecture
priority: 2
theme: structural-refactor
depends-on: []
---

# Batch DataLoader for non-connection ChildField.UnionField / ChildField.InterfaceField

`ChildField.UnionField` and `ChildField.InterfaceField` in their non-connection arms emit a synchronous, non-batched per-parent fetcher: every parent invocation runs its own stage-1 UNION ALL plus per-typename stage-2 SELECTs. There is no `DataLoader`, no `parentInput VALUES (idx, parent_pk…)` join, and no scatter array, so a list of N parents fans out to ~N stage-1 unions plus ~N participant SELECTs each, with no dedup for repeated parent PKs across siblings. Observed on `graphitron-sakila-example` against `Address.occupants: [AddressOccupant!]!` (union of `Customer | Staff`): a top-level `customers` query selecting `address.occupants` for 5 customers fires 14 child SQL statements (5 stage-1 unions plus 9 per-typename SELECTs), where the same address PK fires its full chain twice for two pairs of siblings sharing addresses. Expected shape under DataLoader batching: 1 stage-1 UNION ALL with `JOIN parentInput` over the distinct parent PKs, plus 1 per-typename SELECT per participant — so 3 child statements regardless of how many parents share each PK.

The dispatch site is `TypeFetcherGenerator.java:436-461`: both arms pick `MultiTablePolymorphicEmitter.emitMethods` for the non-connection case, which routes to `buildMainFetcher` at `MultiTablePolymorphicEmitter.java:219-309`. `buildMainFetcher` reads `Record parentRecord = (Record) env.getSource()` inline, runs stage 1 with per-branch `WHERE participant_fk = parentRecord.parent_pk`, and dispatches per typename in the same call. The connection arm at the same emitter already does the right thing — `buildBatchedConnectionFetcher` (`:671-748`) registers a path-keyed `DataLoader<RowN<…>, ConnectionResult>` and `buildBatchedConnectionRowsMethod` (`:793-…`) builds the `parentInput VALUES` table, joins it into the per-branch UNION ALL, dispatches per typename, and scatters typed Records into a `result[outerIdx]` array. The list-arm fix is the same shape minus the windowed-CTE pagination: one DataLoader-registering main fetcher, one `rows<Field>(List<RowN<…>>, env)` rows method, returning `List<List<Record>>` indexed 1:1 with the keys list.

Out of scope for the spec body, to revisit when this is picked up:

- Whether to unify the emitter surface (`emitMethods` and `emitConnectionMethods` both grow a `parentTable`-aware list arm and a connection arm) versus keeping the four entry points distinct. The connection arm already takes `parentTable` and switches; the list arm does not.
- Threading `parentTable` into `ChildField.InterfaceField` / `ChildField.UnionField`. The current records do not carry it; `participantJoinPaths` covers per-participant FK chains but the parent's own table is rederived at emit time. Compare to the connection-mode dispatch which threads `parentTable` from the enclosing `TypeFetcherEmissionContext`.
- Test coverage shape: pipeline-tier (assert one DataLoader registration + one rows method emitted) plus execution-tier (the `Address.occupants` query above; pin SQL counts via JOOQ `LoggerListener` so a regression to per-parent fanout fails loudly).
