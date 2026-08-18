---
id: R708
title: "The $project selection gate fires on names selected at any depth below its unit"
status: Backlog
bucket: correctness
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# The $project selection gate fires on names selected at any depth below its unit

Every generated `$project` unit gates its contributions on a selection map: the emitted body loops
`grouped.entrySet()` and dispatches `switch (sf.getName())`, one arm per contribution, so an
unselected field projects nothing. The map is wrong for that job. It comes from graphql-java's
`DataFetchingFieldSelectionSet.getFieldsGroupedByResultKey()`, either directly
(`ProjectionCall.fromSelectionSet` / `fromEnv`) or through the generated
`SelectionOccurrences.mergeByResultKey`, and that method groups *every* selected field at *every*
depth of the sub-selection, not the unit's immediate children. So a unit's arm fires whenever its
field name appears anywhere below it, however deep and on whatever type.

Observed while adding leaves to the `OccupantLocation` fixture: with a nesting-type leaf named
`district`, the query `{ customers(active: true) { location { address { district } } } }` emitted
the correlated subselect for `OccupantLocation.district` aliased `__rk_district` alongside the
selected `__rk_address` multiset. Nothing selected `location { district }`; the `district` inside
`address` reached the outer unit's map and fired its arm.

The visible cost so far is over-projection, which is the same class of defect the
selection-gating work set out to end, and it is silent: the extra term is aliased on a result key
no fetcher reads. It is not obviously bounded there. Two shapes worth checking before deciding the
fix:

* Two arms on *different* types can share a name and both fire from one selection. On the fixture
  above, `Customer.address` (a multiset child) and `OccupantLocation.address` (the shared nesting
  type's inline leaf) both key on `address`, and both are reachable from the same `Customer`-level
  map. Today they happen to render identical SQL under the same alias, so the emitted
  `LinkedHashSet` dedupes them and the baseline SQL pin stays green. That is an accident of the
  fixture, not a property of the gate: two same-named arms whose SQL differs would emit two terms
  under one `__rk_` alias.
* Arms that read arguments off the canonical `SelectedField` (`requireConsistentArguments`) get
  handed occurrences from a depth they were never meant to serve, so the loud-failure check
  protecting them is measuring the wrong bucket.

The fix is presumably to gate on the immediate children (`getImmediateFields()`, or a
result-key grouping restricted to them) and to make the arm dispatch account for the parent type
rather than the bare field name, but the choice needs the survey above: several call sites build
the map, the pivot and discriminated-table bodies loop it too, and the generated
`SelectionOccurrences` helpers are part of the contract. Filed from R323, which named its fixture
leaves around the collision (`occupantDistrict` / `occupantAddressId`) rather than re-pinning a
baseline onto the spurious column.
