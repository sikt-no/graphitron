---
id: R447
title: "Routine chains: remaining fetch-form breadth"
status: Backlog
bucket: feature
theme: routine
depends-on: []
created: 2026-07-08
last-updated: 2026-07-08
---

# Routine chains: remaining fetch-form breadth

R435 shipped order-significant `@routine` / `@reference` composition end to end for the core
surface: every chain shape at root and child positions, the inline correlated multiset, and the
`@splitQuery` batched keyed re-query on table-backed parents (batch key = the routine's
column-bound inputs). Four fetch-form extensions were left as typed `Deferred` landings whose
`planSlug` points here; each falls on an existing model seam, so they slice independently:

* **Chains with more than one routine node** (root and child alike): the multi-lateral emit.
  Classification and validation already handle the shape; the emitters need the second (and
  later) `CROSS JOIN LATERAL` positions, which the forward join walks in
  `InlineTableFieldEmitter` and `SplitRowsMethodEmitter` are already shaped for.
* **`@lookupKey` composition** on routine-backed children (the `SplitLookupTableField`
  landing).
* **Record-backed parents** (`RecordTableField` seam): a routine child under a `@record` /
  service-produced parent, where an `argMapping` name would bind one of the record's accessors
  rather than a column of a catalog table (`RoutineDirectiveResolver`'s no-standing-table
  `Deferred` covers this today).
* **`TableInterfaceType` parents** (the `InterfaceField` seam).

Connection pagination is not parked here any more: a root chain paginates on either terminus
kind, and the child position is R1169 (`routine-child-connection`).
