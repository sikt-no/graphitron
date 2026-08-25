---
id: R830
title: "meta_relation_reference costs 153 ms, and it is the key-constraint projection inlined at both namings"
status: Backlog
bucket: model
priority: 4
theme: model-cleanup
depends-on: []
created: 2026-08-25
last-updated: 2026-08-25
---

# meta_relation_reference costs 153 ms, and it is the key-constraint projection inlined at both namings

`meta_relation_reference` is the relation that answers which declared foreign keys cross which
family boundaries: one row per foreign key the schema declares, naming the referencing relation and
the referenced one, each with the family the census places it in. Its readers are the generated
schema reference, the normalization-crossing gate, and the editor tooling's schema surface. One read
of it costs 153 to 166 ms.

Its body reads `INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS` and joins, twice, a
`SELECT DISTINCT constraint_name, LOWER(table_name) FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE`
derived table, once for each end of a foreign key. H2 inlines a derived table at every naming and
eliminates no common subexpression, so both namings pay a whole evaluation of a rule over a virtual
catalog relation. That is the cost. Storing the rows takes the read to about 1 ms.

## Measurements, already taken

These come from a probe run on the sakila example schema over a `CapturedStore`, driving H2's own
query statistics; the work was implemented, measured, and rolled back rather than shipped. Figures
are that run's and are stated as provenance. Anything this item ships re-measures on the tree it
ships against.

- One read of the shipping relation: 153 to 166 ms, 4250 scans, 175 rows.
- Every child in isolation: between 0.2 and 2.1 ms. No expensive child, so the cost is the expansion.
- Floor control, family joins removed from the statement entirely: still 95 to 106 ms. The census
  join is not the term, which two earlier rounds had assumed it was.
- The lever is a conjunction, and neither half alone is the fix. Storing the census alone moved
  nothing. Storing the key-constraint projection alone reached 49.7 ms. Storing both reached 0.7 ms
  with the row count unchanged.
- The rewrite rung failed as the fact-model page predicts. Hoisting the derived table into a `WITH`
  measured 201 ms against 211 for the view, since H2 inlines a non-recursive `WITH` exactly like a
  view. Driving both halves off `INFORMATION_SCHEMA.TABLE_CONSTRAINTS`, which is already at
  constraint grain and needs no `DISTINCT`, reached 121 ms but at 124316 scans.
- Implemented as two relations derived once per booted store, one read went from 153 to 166 ms to
  1.0 ms with a standard deviation of 0.3, scans 4250 to 1752, row count unchanged at 175.

## The cadence is the design question, and it is not the registry

The obvious mechanism is a `meta_materialize` registration, and it is the wrong one. The materializer
refreshes on the capture cadence, while these rows are a function of the DDL alone; more decisively,
the readers include the schema gates and the docs drift guard, which run against a store no capture
has touched, so a capture-cadence refresh would leave those readers reading an empty relation. The
right cadence is boot-time derivation, and `meta_materialize_dependency` is the precedent already in
the tree: `MaterializeDependencies.populate` runs once per created store from
`GraphitronModelStore`, for exactly the reason that its rows are a function of the schema file.

## The measurement was the easy half

The rolled-back implementation passed every `graphitron-model` gate and then failed five others in
the full reactor. Every one was schema discipline rather than cost, and they are the actual work
this item carries:

1. `FactSchemaGateTest`, relations keyed without their partition dimension: a new base table is
   expected to lead with `graph_name`, and neither of these is graph-keyed. Needs an exemption with
   a stated reason, or a different shape.
2. `FactSchemaGateTest`, uncommented columns: the two rule views need column comments.
3. `FactCaptureAgreementTest`: the new relations need a registered agreement source.
4. `SchemaReferencePagesTest`: blank comment text would render empty reference entries.
5. `DerivedReadCostTest`: its pinned view count moves. Re-pin from the current figure rather than
   from this run's, which is already stale.

Worth recording because it is the finding that surprised: `DerivedReadCostTest` did **not** fail on
cost direction. Its directional scan claim held, the change reducing scans rather than raising them.

## A caveat about the instrument this was found with

Scan counts and wall clock disagreed here in both directions. An unindexed snapshot visited twenty
times more rows than the shipping view and ran faster; adding the index removed 96% of those visits
and moved the clock not at all. A scan count weights every visited row equally, and a row of a view
over `INFORMATION_SCHEMA` does not cost what a row of a table costs, so the two instruments diverge
exactly when a change moves rows between a view and a table. Price this item's levers by timing and
use scan counts for shape, which is what the skill's plan step already says.
