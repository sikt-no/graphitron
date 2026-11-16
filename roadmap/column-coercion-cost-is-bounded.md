---
id: R1188
title: "Bound the cost of coercing key and tenant values, and the number of keys _entities and nodes take"
status: Backlog
bucket: bug
priority: 1
theme: nodeid
depends-on: []
created: 2026-11-16
last-updated: 2026-11-16
---

# Bound the cost of coercing key and tenant values, and the number of keys _entities and nodes take

## Goal

The emitted `ColumnValues` (the runtime class that decides whether a client value is a value of its column) checks a numeric value's digit count and magnitude against what the column type can represent before doing any arithmetic on it, so coercing a federation `@key` value or a tenant value costs a small constant amount for any input. `_entities` and `Query.nodes` refuse a request carrying more representations or ids than a configurable limit, as a client error and before any key is decoded.

## Notes

Details are held outside the public tree; ask the maintainers.

* The bound applies on every numeric column type, converter-backed ones included, and in `exact`, `fromDatabase` and `fitsPrecision` as well as `coerce`. The documented coercions are kept: `"1.5"` binds as 1, a padded number is accepted, and a wrap is still refused.
* The representation limit applies on the direct `resolveByReps` path (`EntityFetcherDispatchClassGenerator`) as well as to DataLoader batches.
* Code involved: `ColumnValuesClassGenerator`, `HandleMethodBody` (the `_entities` key arm), `EntityFetcherDispatchClassGenerator`, `ConnectionRuntimeClassGenerator` (`tenantKeyOrNull`, `tenantKeyOf`), and `ColumnValuesTest` in `graphitron-sakila-example`.
* Adjacent: `derived-read-cost-is-a-shape-problem` changes the `exact` and `folded` signatures, so whichever lands second rebases onto the other.
* Open for Spec: where the limit is configured (a Maven plugin parameter baked into the generated code, or a runtime setting on the generated wiring) and its default; and whether the magnitude bound comes from the jOOQ `DataType` or from a fixed ceiling, since a `NUMERIC` with no declared precision has no column bound to read.
* `R1190` is the sibling at the `graphitron-jakarta-rest` HTTP boundary; neither depends on the other.
* Progress: the coercion bound has landed (the user waived the Spec and Ready gates for it), as `ColumnValues.inRange`, which `exact`, `coerce`, `folded` and `fromDatabase` and the release-candidate cursor reading ask first. The representation limit is still open; the principles-architect leaned toward a build-time parameter captured as its own `store_graph_*` relation, two limits rather than one, and noted that a finite default refuses requests that succeed today.
