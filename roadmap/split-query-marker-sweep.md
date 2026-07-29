---
id: R557
title: "Completeness sweep for @splitQuery applications: every marker consumed, inert-by-construction, or rejected"
status: Backlog
bucket: validation
priority: 5
theme: diagnostics
depends-on: []
created: 2026-07-29
last-updated: 2026-07-29
---

# Completeness sweep for @splitQuery applications: every marker consumed, inert-by-construction, or rejected

`@splitQuery` has no completeness enforcer: nothing guarantees that every application of the marker either forces a batched delivery, is inert for a stated structural reason, or rejects. `@tenantFanOut` has exactly this in `TenantBindingIndex.sweepUnreachedFanOutMarkers`, whose javadoc names the failure mode ("a marked coordinate the classification never modelled ... would otherwise be silently ignored; the sweep turns it into a validate-time rejection"). The absence for `@splitQuery` is why the marker sat silently ignored on nesting fields until a slice of the projection-command programme stumbled on it empirically instead of a test naming it; that instance is now a classify-time deferred diagnostic at the nesting arm of `FieldBuilder`, but the class stays open: the next inert position is admitted silently again.

The sweep's verdict should be a total switch over the classified leaf, on the `CatalogBuilder.projectFieldClassification` compile-checked-projection seam: consumed (the batched leaves: `BatchedTableField`, `BatchedLookupTableField`, `BatchedPivotField`, the batched service variants, plus the record-parent implicit split), inert-by-construction with the reason derivable from facts the model carries (root fields and argument-bearing fields always get explicit fetchers, class-backed-parent table-bound fields split implicitly; today these are prose bullets in `docs/manual/reference/directives/splitQuery.adoc`'s Constraints list, an unguarded inventory), or rejected. Landing the sweep would make the manual's positional enumeration derivable instead of asserted, and would surface any other surviving inert position at build time.

Related message-quality note discovered while checking the sibling marker: `@tenantFanOut` on a list-wrapped nesting field of a tenant-scoped `@table` parent does reject loudly (no silent hole; `reachedTables` returns empty for a `NestingField`, so the "reaches no tenant-scoped table" rung fires), but the message says "its data is global", which is wrong for that shape: the nesting projection rides the tenant-scoped parent's row. A shape-aware message (or routing nesting returns to the parent-kind rung's wording) would fix the misdirection; it can ride this item or be split out at Spec time.
