---
id: R1074
title: "A multi-table child connection pages and dispatches on the first column of a composite participant key"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-20
last-updated: 2026-10-20
---

# A multi-table child connection pages and dispatches on the first column of a composite participant key

## Goal

A child field (one with a parent row) that returns a multi-table interface or union as a
connection pages, orders and dispatches on its participants' whole primary key, so a participant
keyed on `(k1, k2)` under such a field returns each row once, in key order, and stage 2 hydrates
the row the key names rather than every row sharing its first column. Today the batched
child-connection path (`MultiTablePolymorphicEmitter.buildBatchedConnectionRowsMethod` and
`batchedBranchProjection`) reads only the first key column throughout: projection, the
`(idx, pks)` binding and the sort. A composite-key participant there pages on a truncated key and
can return duplicate or wrong rows, with no build-time diagnostic. The closing comment of
`GraphitronSchemaValidator.validateMultiTableParticipants` declines to reject the shape because a
rejection would break the root `Query.pagedItems`; once R1062 moves the root path to key-column
ordering that reason no longer holds, so either the full-key fix or, in the meantime, a
validate-time rejection of composite participant keys on child connections is open. Split out of
R1062 (Out of scope, first bullet), which rewrites that comment to name this item as the owner.
