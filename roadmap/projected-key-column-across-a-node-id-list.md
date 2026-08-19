---
id: R735
title: "Project a key column across a list of node ids into an array-valued parameter"
status: Backlog
bucket: feature
priority: 4
theme: routine
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# Project a key column across a list of node ids into an array-valued parameter

An `argMapping` path may open a `@nodeId`-carrying `ID` with one of its node type's key columns.
Opening a `[ID!]` carrying the same directive is rejected, and the rejection is a scope limit rather
than a rule: a list of node ids of one type opens into the list of that key column across the decoded
ids, which is a perfectly determinate value. The decode side already has the shape,
`decode<Record>List` materialising one record per element and `CompositeDecodeHelperRegistry`
returning `List<T>` on the list axis, so what is missing is the consuming end.

Two things have to be decided rather than just built. A routine IN parameter taking the projection
would have to be array-typed, so the binding's type gate has to compare the column's Java type against
an array parameter rather than a scalar one, and a mismatch there wants the same message the scalar
gate owes (filed separately). And the emitted read has to say what an absent or malformed element
means: the scalar form decodes to `null` and lets the parameter carry it, while a list has the further
choice between a `null` element, a shorter list, and a request-time error. Pick one and state it,
rather than letting the emitter's shape decide by accident.

Until then the walk rejects the shape with a message that says it does not emit yet, which is honest
but is a rejection standing where a deferral belongs: `ArgBindingMap.of`'s `Result` has no deferral
arm, and adding one would be new vocabulary on the walk surface that the strangler migration is
draining. Worth revisiting here whether the routine resolver's own `Rejection.deferred` should carry
it instead.
