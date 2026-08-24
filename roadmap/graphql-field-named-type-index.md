---
id: R820
title: "An authored index on the field census named-type coordinate"
status: Backlog
bucket: store
priority: 3
theme: mutation-write
depends-on: []
created: 2026-08-24
last-updated: 2026-08-24
---

# An authored index on the field census named-type coordinate

Several derivations join `graphql_field` on its `named_type` column, asking which fields return a
given type. No key serves that coordinate (the primary key leads with the field's own coordinate,
and the named-type reference deliberately carries no foreign key), so such a join is a seek by
graph alone followed by a scan of the whole field census per driving row. The carrier read-cost
item measured the fix on the read-cost gate's twelve-unit fixture: `CREATE INDEX ON graphql_field
(graph_name, named_type)` takes `intent_carrier_routine_hop` from 19619 scans to 2137,
`intent_mutation_routine_seat` from 27531 to 10049, `intent_field_error_channel` from 1079 to 215
and the errors-field rule from 916 to 249, and it clears three of the four named-type rows that
item pinned in `DerivedReadCostTest.KNOWN_NON_MONOTONIC` (the fourth shrinks to a 567-scan gap).
The pinned rows are asserted by equality, so landing this index deletes them.

It was filed rather than shipped there because an authored index on a *captured base table* is
new ground the target-index item's doctrine covers only partly, and the parts it does not cover
are this item's tasks:

- The reader axis is the whole derived stratum, not the four relations that motivated it:
  `graphql_field` is named across dozens of view bodies, and `DerivedReadCostTest` is blind to a
  base-table index by construction (it moves both sides of every cell). Measure the largest shape
  under which no reader gets dearer, with the reader list derived from the booted store (the
  parsed-definition walk in `MaterializeDependencies` already collects base-table reads; it needs
  a `viewsReading` entry point rather than a hand-kept list).
- An index on a captured table is a cost on every capture's write path, for every consumer,
  including one with nothing that reads the benefiting relations. Price capture with and without
  it, on a carrier-bearing and a carrier-free schema, and state both numbers in the index comment.
- The index-comment discipline (`MaterializeRegistryGateTest.everyIndexOnATargetStatesItsReader`)
  is scoped to registered targets, so the first index outside the register would carry a comment
  nothing enforces. Broaden that gate from registered targets to every authored index in the
  schema (the `IS_GENERATED = FALSE` predicate already separates authored indexes from
  FK-backing ones).
- Two doc sentences need amending: the key discipline in
  `docs/architecture/explanation/fact-model.adoc` states prefix scans on keys as the schema's
  whole access-path story, and an authored attribute index is the reverse-index case the
  derived-reads section already sanctions, so the amendment is one sentence beside the key
  discipline; and `DerivedReadCostTest`'s pinned-set javadoc names this item's coordinate as the
  lever, which stops being prose the day the rows go.
