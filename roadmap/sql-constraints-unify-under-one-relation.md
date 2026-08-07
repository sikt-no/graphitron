---
id: R607
title: "The SQL constraint families unify under one typed relation"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-07
last-updated: 2026-08-07
---

# The SQL constraint families unify under one typed relation

The fact schema models a table's uniqueness constraints and its foreign keys as two disjoint
relations with two column children, where every real catalog models them as one constraint
relation discriminated by type. Oracle's data dictionary carries `ALL_CONSTRAINTS` with a
`CONSTRAINT_TYPE` of `P` / `U` / `R` / `C` and one `ALL_CONS_COLUMNS` under it; the SQL
standard's `INFORMATION_SCHEMA` carries `TABLE_CONSTRAINTS` with a `constraint_type`,
`KEY_COLUMN_USAGE` for the local columns of every keyed form, and `REFERENTIAL_CONSTRAINTS` as
the foreign-key-only extension. Two independent designs converged on the supertype, which is
evidence about the shape rather than about either vendor.

Our own schema already votes the same way elsewhere. `graphql_type` is a supertype over six
declaration forms with a CHECK-constrained `kind`, the per-form detail living in sibling
relations, and the schema conventions state the pattern outright: closed taxonomies are CHECK
constraints so the schema itself rejects a kind the model does not know. The constraint families
are the one place the schema states a closed taxonomy by having separate relations instead.

The gain is not tidiness. "What constrains this table?" is a union today and one predicate
under the supertype; a detection that ranges over constraints (a `@table` reference resolving
against something that no longer constrains, a `@node(keyColumns:)` naming a column set that is
not unique) has one relation to read; and the forms this iteration does not capture (CHECK, NOT
NULL, deferrability) arrive later as type values rather than as new relations with new anchors.

## Open questions for Spec

1. **Where the foreign-key-only attributes live.** Oracle hangs `R_CONSTRAINT_NAME` and
   `DELETE_RULE` off the supertype, NULL on every primary-key and unique row; the standard puts
   them in `REFERENTIAL_CONSTRAINTS`. The standard's split is the one that matches this schema's
   preference for an absent row over a null column, which would make the shape `sql_constraint`
   plus a `sql_referential_constraint` extension. Worth confirming rather than assuming, since
   it decides whether the column child stays one relation or two.
2. **Whether target columns keep their denormalised pair.** Both catalogs resolve a foreign
   key's target columns by indirection: name the referenced unique constraint, then match its
   own column rows by position. `catalog_foreign_key_column` instead carries `source_column` and
   `target_column` side by side. The recommendation is to keep the pair and diverge from both
   deliberately. `CatalogFacts.OutgoingForeignKey` carries the target table and target columns
   and no referenced-constraint name, so indirection widens `CatalogFacts` and re-cuts the
   equality-arm agreement anchor; and it makes the target columns unreachable rather than merely
   denormalised whenever the referenced unique constraint is not itself reported. Capture total
   and cheap, joins as derivation's business, is the item's doctrine and the pair follows it.
3. **Whether the constraint's index linkage is a fact here.** Oracle's `ALL_CONSTRAINTS` carries
   `INDEX_NAME`, since a unique constraint is backed by an index. The schema keeps `catalog_index`
   separate and captures no linkage. Whether that edge is a captured fact or a derivation over
   column sets should be answered with the first consumer that needs it, not now.

## Timing

Free today and not free later, which is the whole of the scheduling argument. Nothing reads the
store, so the change is DDL plus `CatalogFactCapture` plus the census anchors, and the compiler
finds every call site. The moment a consumer migrates onto `sql_unique_constraint` or
`sql_foreign_key` the cost stops being mechanical. Pulling this into the reopened
`graphitron-model-captures-facts` pass is therefore defensible; it is filed separately because
it is a data-model change with a live design question rather than a rename, and it should take
the Spec gate rather than ride a reviewer's note on an item reopened for a correctness defect.

The prefix rename that pass carries is not wasted work either way: `sql_unique_constraint` is
exactly the standard's `constraint_type IN ('PRIMARY KEY', 'UNIQUE')` slice, so unification
merges two well-named relations rather than renaming one first.

## Relationships

- **`graphitron-model-captures-facts` (R595):** ships the relations this item reshapes, and
  carries the `catalog_` to `sql_` rename whose slice names this item merges.
