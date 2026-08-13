---
id: R656
title: "@sourceRow-declared batch key for @service children on scalar-only parents"
status: Backlog
bucket: feature
priority: 4
theme: service
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# @sourceRow-declared batch key for @service children on scalar-only parents

A batched child `@service` on a class-backed parent keys on the table its SOURCES element type names, and the parent must be able to produce a record of it: either it *is* that record, or it exposes exactly one zero-arg accessor returning one. A parent carrying only scalar key columns satisfies neither, so it still has no route, and the classify-time rejection says so outright rather than implying one exists.

`@sourceRow` is the mechanism that solves the analogous case on the table-child path, and it does not transfer as it stands. `SourceRowDirectiveResolver` derives the expected column tuple from `@reference`'s first hop or the leaf target's PK and validates the lifter's `RowN` arity and per-position column classes against it; on a `@service` child the service's own SQL decides what the key means, so the catalog has nothing to arbitrate and the only surviving check would be one author-written signature against another. `MethodRef.Param.Sourced` also requires a non-empty `List<ColumnRef>` and derives its Java parameter type from `ColumnRef::columnClass`, so admitting a lifter additionally means making the columns axis optional, which is a model widening rather than a threading exercise.

So this needs a design pass: either a column-naming surface the catalog can ground, or an optional columns axis on `Sourced` with the consequences for `SourceKey.keyElementType()` worked through. Reported alongside the parent case that shipped.
