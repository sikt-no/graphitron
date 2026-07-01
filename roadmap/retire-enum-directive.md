---
id: R360
title: "Retire the @enum directive; infer enum Java backing from producers"
status: Backlog
bucket: cleanup
priority: 3
theme: legacy-migration
depends-on: []
created: 2026-06-23
last-updated: 2026-06-23
---

# Retire the @enum directive; infer enum Java backing from producers

The `@enum(enumReference:)` directive links an SDL enum to a Java enum class, but that backing is
inferable authoritatively from the use site: an enum-typed coordinate resolves a producer (a column
`javaType`, a `@service` signature type, an accessor return type) whose Java type *is* the backing.
This is the same producer-reflection that retired `@record`. So an authored `@enum` value adds no
information graphitron cannot derive and can only contradict the inferred truth, a pure misconfiguration
surface. Retire it the way `@record` / `@notGenerated` / `@multitableReference` are retired: keep the
declaration so the parser does not choke, reject any application at classify time with a migration
message, and derive the enum's backing (the `EnumBacking` roll-up in R333's *Enum facts*) from producers
instead. The remaining failure mode is genuine, not a config artifact: two use sites of one enum resolving
to different backings (one-enum-one-type), which surfaces as a typed rejection. Note the backing need not
be a Java enum: an SDL enum may map to a `String` (varchar) or numeric (integer) column, so the inference
generalizes over `backingType`, not just jOOQ-generated enum classes.

Model decided in R333 (*Enum facts*); this item is the code retirement and the producer-inference path
that replaces the directive. Coordinate with R261 (wire-coercion cast guard), which already owns the
enum-name-divergence rejection at the column-binding site.
