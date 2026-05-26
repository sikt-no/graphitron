---
id: R243
title: "Per-field direction in @order/@defaultOrder via FieldSort.direction"
status: Backlog
bucket: feature
depends-on: []
created: 2026-05-26
last-updated: 2026-05-26
---

# Per-field direction in @order/@defaultOrder via FieldSort.direction

The `FieldSort` input in `directives.graphqls:263` cannot express direction per field; a sort spec's direction is uniform. `@defaultOrder` has a single top-level `direction: SortDirection = ASC` that applies to every entry in `fields:`, and `@order` (ENUM_VALUE) has no direction surface at all — `OrderByResolver.resolveEnumValueOrderSpec` (`OrderByResolver.java:191`) hardcodes `"ASC"` for every fixed-spec entry it emits. Schemas that need heterogeneous ordering (e.g. `ARSTALL DESC, SORTERINGSNOKKEL ASC` — recent-year first, then natural key within the year) currently have no way to declare that and must fall back to runtime `@orderBy` input, which is the wrong tool when the ordering is fixed by the field's contract rather than chosen by the client. The underlying model already supports this: `OrderBySpec.Fixed` (`OrderBySpec.java:60`) carries `direction: String` per entry, and `jooqMethodName()` already maps it to `asc`/`desc`. The work is purely surface-level — adding `direction: SortDirection` to `FieldSort`, threading it through the resolver as an override of the directive-level default (per-field wins; absent → fall back to `@defaultOrder.direction`; on `@order` enum values, absent → ASC as today), and removing the `"ASC"` hardcode on the `@order` path. Design forks for Spec: (a) does `@order` also gain a sibling top-level `direction` for symmetry, or stay per-field-only? leaning per-field-only, since each enum value is already a discrete spec and a top-level on `@order` adds a second way to say the same thing; (b) interaction with `index:` and `primaryKey:` — `FieldSort.direction` only applies to the `fields:` variant, so the validator (R181 adjacent) should reject direction on the other variants rather than silently ignoring; (c) error shape when the schema also still carries the directive-level `direction` redundantly on every entry — warn, error, or accept silently. No backward-compat concern: `direction:` on `FieldSort` is a new optional field; existing schemas keep their current semantics unchanged.
