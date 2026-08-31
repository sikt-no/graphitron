---
id: R887
title: "ColumnComparison's CatalogColumn overload has no caller, and a doc implies one"
status: Backlog
bucket: cleanup
priority: 4
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# ColumnComparison's CatalogColumn overload has no caller, and a doc implies one

`no.sikt.graphitron.render.ColumnComparison` mints every column-to-column equality the generator
emits, and four of its five entry points have production callers. The fifth, the `equality`
overload taking two `command.CatalogColumn` rather than two `model.ColumnRef`, has none: the only
comparison site in the generator that holds `CatalogColumn` pairs is
`RoutineWriteFetcherRenderer.keysInCondition`, which was deliberately left off the mint because two
of its three spellings (`Field.in(Collection)` and `Row.eq(Row)`) are comparison shapes the mint
does not spell, and because the value's source column is a column of the routine's own result table,
for which the site holds no `TableRef` to spell a `DataType` from. So the overload is scaffolding for
work that was scoped out, reachable today only from `ColumnComparisonTest`.

That would be harmless on its own. What makes it worth an item is that
`docs/architecture/reference/emitter-conventions.adoc` presents it as live, in the
`ColumnComparison` entry-point table: "Overloaded for the walk's `ColumnRef` and the command tier's
`CatalogColumn`, which carries its bound type as a name and is decoded inside the mint." A
contributor reading that page reasonably concludes the command tier's comparisons go through the
mint. They do not, and the one that would is the site with the known divergence hazard, so the
prose points away from the gap rather than at it. This is the drift the "Documentation names only
live tests/code" principle warns about: a claim true in intent, false in the tree, with no enforcer.

Two ways to settle it, and the choice is the point of the item rather than a detail:

* **Delete the overload and the doc clause.** The mint keeps one shape per shape it actually spells,
  and the routine-write site's own item reintroduces the overload when it needs it. Cheapest, and
  consistent with "an invariant exists only while something fails when it breaks".
* **Keep it and make the doc honest.** Say the overload exists for a command-tier caller that does
  not exist yet, and name `RoutineWriteFetcherRenderer.keysInCondition` as the site still emitting
  a raw comparison. Costs nothing but leaves an untested-by-a-caller surface in place.

Recommend the first: the decode the overload owns is one call to
`ColumnRef.decodeBindingType`, so a future caller re-adding it copies nothing. Note the two
overloads also differ in behaviour on a missing type, which is worth a sentence wherever this
lands: `ColumnRef.columnType()` is nullable and the mint's null guard emits an uncoerced comparison,
whereas `decodeBindingType` throws on a blank name by design, so the `CatalogColumn` path has no
null arm at all.

Found at the In Review → Done gate on the item that introduced `ColumnComparison`, recorded as
non-blocking there: the approved change arrived whole and the overload is inert, so it was not
grounds to withhold approval.

