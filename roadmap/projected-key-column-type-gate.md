---
id: R734
title: "Reject an argMapping key-column projection whose column type the consuming parameter cannot take"
status: Backlog
bucket: bug
priority: 3
theme: routine
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# Reject an argMapping key-column projection whose column type the consuming parameter cannot take

An `argMapping` path may open a `@nodeId` and name one of the node type's key columns, and the
emitted read is that column off a decoded record: `keyInputCustomerId.get(Tables.CUSTOMER.CUSTOMER_ID)`.
Whether the column's Java type is one the consuming parameter can take is not checked anywhere. The
shared coercion gate cannot ask: it reads the path's SDL leaf type, and a path that descends past a
scalar resolves no leaf at all, so `WireCoercionResolver.checkScalar` takes its null arm and passes
through. Nothing downstream asks either, the projection resolving a column rather than a type.

A mismatch is therefore a javac error in the consumer's generated sources rather than a graphitron
rejection. Loud, but wrongly worded: the author sees "incompatible types" pointing at emitted code
they did not write, instead of a message naming the entry, the column, and the parameter. Both types
are available where the projection is produced, `ColumnRef.columnType()` on the model side and the
consuming parameter's declared type on the command row, so the check has both operands in hand; what
it needs is a home that can mint a `Rejection` rather than a generator invariant throw, and a decision
about which widenings to admit (a `SMALLINT` key column into an `Integer` routine parameter is a
mismatch a reasonable author would not expect to be told about).
