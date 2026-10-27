---
id: R1110
title: "A single-valued field over a to-many path returns an arbitrary row, and @defaultOrder on it is silently ignored"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-27
last-updated: 2026-10-27
---

# A single-valued field over a to-many path returns an arbitrary row, and @defaultOrder on it is silently ignored

## Goal

A single-valued output field whose `@reference` path reaches many rows per parent either returns a row the author chose or fails the build, instead of returning whichever row the database happens to produce first. Today such a field builds with no error or warning: inline delivery renders the read as a correlated subquery capped at `.limit(1)` with no `ORDER BY` (`ProjectionUnitRenderer`, the `Arity.SINGLE` arm), and `@defaultOrder` on the field, which the SDL accepts, never reaches it, because `OrderByResolver.resolve` returns `OrderBySpec.None` for every non-list field before reading the directive. The same field with `@splitQuery` builds too and throws `IllegalStateException` at runtime when two rows match (`SplitRowsMethodEmitter`, "single-cardinality @splitQuery contract requires ≤1 terminal row per key"). Scalar column references through a to-many path (`PathFragments.scalarInnerSelect`) take the same unordered `.limit(1)`. The `reference-path-fans-out` warning covers list fields only. Found while working out GitHub issue #562.

## Open for Spec

- **Which fix.** Honour `@defaultOrder` on a single-valued field as "the first row in this order" (the emitter already places an `ORDER BY` before the `LIMIT 1`; only the resolver gate needs to admit it), or reject a single-valued field over a to-many path and point at a modelled alternative (a view with a unique key plus a synthetic foreign key, or an `@externalField`). The first keeps one directive meaning "sort a list" on list fields and "choose a row" here; the second keeps `@defaultOrder` list-only. Either way the order must be deterministic (append the landing table's primary key unless a key is covered, as for connections).
- **What the build says.** Schemas that build today return arbitrary rows already, so a hard error breaks them only where the answer is already wrong; a warning first is the gentler step. The to-many test is the same "target-side columns cover a candidate key" primitive the related-table ordering item names.
- **`@splitQuery` parity.** Whatever the inline arm does, the batched single-row arm must agree instead of throwing at runtime.

