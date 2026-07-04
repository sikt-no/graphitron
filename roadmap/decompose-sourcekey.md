---
id: R431
title: "Decompose SourceKey onto the model's facts"
status: Backlog
bucket: structural
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-07-04
last-updated: 2026-07-04
---

# Decompose SourceKey onto the model's facts

`SourceKey` is `(target, columns, path, wrap, cardinality, reader)` and bundles three separable
concerns, only one of which is a source key (R222 "What SourceKey decomposes into"; R333 sharpens the
destinations). This item is the eager, mechanical decomposition, sequenced ahead of the reentry emit
re-platforming (R314) so the emit slices land on decomposed facts instead of extending the conflated
record.

Destinations, already settled in R333 (2026-07-04 design session):

- **`target` / `path` leave by deletion.** They are denormalized copies of `returnType.table()` /
  `joinPath` already carried first-class on the leaves; `SourceKey.path()` has zero generator readers.
- **`wrap` / `reader` / backing dissolve into the read-side facts.** The source object's shape is a
  type-level fact (jOOQ record vs Java object); the key lift is N reads through the same field-level
  locator family the ordinary read side uses; `@sourceRow`'s lifter is *provenance* on the member-read
  arm (authored where the catalog cannot infer the mapping), not a third mechanism. Nothing
  key-specific survives in `Reader`'s seven arms, which conflate shape, provenance, and envelope.
- **The residue that stays**: `columns` (the key tuple) and the source-field arity, which is a wrapper
  position of the wrapper algebra, not a free `Cardinality` enum.

Why eager rather than pulled by the emit slices: the surface is being extended in its conflated form
right now. R425 (parent projection omits a `@splitQuery`/`@service` child's key columns) and R426
(TableRecord-sourced `@service` keys are partial records) both pin on `sourceKey().columns()` /
`SourceKey.Wrap.TableRecord`, and R71 / R234 wait on the same decomposition. The 2026-07-03 staleness
audit names this the next load-bearing structural pivot; landing it first gives the bug cluster and
R314 a stable surface. When it lands, re-check R71, R234, R314, R425, R426 (staleness-audit
observation 3).
