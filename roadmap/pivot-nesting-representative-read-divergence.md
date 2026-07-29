---
id: R556
title: "Shared pivot/nesting projection type: fetcher read name diverges under @field(name:) remap"
status: Backlog
bucket: bug
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-07-29
last-updated: 2026-07-29
---

# Shared pivot/nesting projection type: fetcher read name diverges under @field(name:) remap

The fetcher side keys nested-type wiring at bare type grain: `TypeFetcherGenerator.indexNestingByType` and `FetcherRegistrationsEmitter.collectNestedTypes` fold pivot edges and nesting edges into one first-occurrence-wins collection per projection type name, and the emitted `<Type>Fetchers` class serves every edge that reaches the type. The comment justifying the fold claims the read name derives identically on both edges. That holds only without renaming: a pivot edge's `PivotSlotField.readName` is derived from the slot's SDL name and is read by-name (`rec.get(DSL.field(DSL.name(readName)))`), while a nesting edge's `ColumnBackedField` read is the typed column constant (`rec.get(Tables.PARENT.COL)`), whose name is the SQL column name that `@field(name:)` resolution produced. When a projection type is reached by both a pivot edge and a nesting edge, and a field of that type carries `@field(name:)` with SDL name differing from the column name, the two derivations disagree and whichever representative wins emits a read that is wrong for the other edge's rows: a pivot-first representative reads the SDL-derived alias against a nesting parent's row that projected the SQL column name (jOOQ throws "field is not contained in row type"), and a nesting-first representative reads the SQL column name against a pivot record whose projected alias is the SDL name (same failure, other direction).

The guard gap is that `GraphitronSchemaValidator.validateNestingParentCompat`, the only check making the coarse fetcher grain safe, groups `ChildField.NestingField` occurrences exclusively; a pivot wiring never enters a compat group, so the pivot-vs-nesting mix is invisible to it. The projection side is already immune (nesting units are `(anchor, type)` grain and pivot units are their own keyed kind), so this is purely a fetcher-side read-name divergence.

Fix sketch (to be confirmed at Spec): either extend the parent-compat validation to include pivot wirings in the per-type group and reject on read-name mismatch (a deferred rejection, honest about the unsupported mix), or make both edges read by one derivation (e.g. the nesting arm projecting the slot's alias when the type is pivot-shared, or the dual read falling back by both names). Recorded when the projection reshape landed the two-grains observation in the slice log of `roadmap/facts-and-commands.md`; the sakila-example schema has no instance of the mix today, so a reproducing fixture is part of the work.
