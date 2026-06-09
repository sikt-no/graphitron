---
id: R287
title: "Remove DELETE -> @table return path"
status: Backlog
bucket: bug
priority: 4
depends-on: []
created: 2026-06-09
last-updated: 2026-06-09
---

# Remove DELETE -> @table return path

DELETE cannot legitimately return an `@table`. The row is gone after the statement, and since
`RETURNING` carries only the primary key (richer columns must come from a follow-up `SELECT`, which a
deleted row cannot serve), there is no way to project a full `@table` shape. Two model artifacts violate
this: `ChildField.SingleRecordTableFieldFromReturning` projects a full `@table` shape directly off the
PK-only `RETURNING` record (both "more than PK read out of `RETURNING`" and "a from-`RETURNING` `@table`
projection" should not exist); and the DELETE carriers (`MutationDeleteTableField` direct-return plus the
DELETE payload carriers) can carry a `Projected*` `DmlReturnExpression` arm, i.e. an `@table` return.
Both contradict two invariants surfaced during R281 dimensional-model design: (a) `RETURNING` carries
only PK, anything richer is a follow-up query; (b) DELETE tops out at an encoded-ID return.

Fix: remove `ChildField.SingleRecordTableFieldFromReturning`; constrain `MutationDeleteTableField` and
the DELETE payload carriers to reject `Projected*` (`Encoded*` / ID-return only). Keep
`SingleRecordIdFieldFromReturning`: reading the PK off `RETURNING` and encoding it to an ID is legitimate
and needs no follow-up. Discovered during R281 spec design (the `producer x mapping x source`
dimensional model).
