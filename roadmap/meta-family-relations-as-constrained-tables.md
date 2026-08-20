---
id: R751
title: "The meta_ family states its rows as VALUES views, which take no constraints"
status: Backlog
bucket: dx
priority: 5
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# The meta_ family states its rows as VALUES views, which take no constraints

The `meta_` family is the schema describing itself: the family roster, the placement of the relations
no prefix covers, and the census that closes both against what the store actually declares. Two of
its three relations are authored rosters, and both are stated as `CREATE VIEW ... AS VALUES (...)`.

A view takes no constraints. No primary key, no `NOT NULL`, no `CHECK`, and column types inferred
from the literals rather than declared, which is why `meta_prefixless_relation` writes
`CAST(NULL AS VARCHAR)` to obtain a nullable column. For a roster that other code joins against and
that build gates read, those are the constraints one would most want.

This item asks whether the family should be tables with constraints, populated by `INSERT` in the
same DDL file, and converts them if the answer is yes.

## What the current form buys, stated fairly

The DDL header makes the claim: the rows are "authored as constant rows stated as views, so the
description is versioned with the DDL it describes and can never be refreshed apart from it".

That is a real property and it is stronger than it first looks. A view's rows are not data, they are
part of the definition, so nothing can `UPDATE` or `DELETE` them, no run can half-populate them, and
there is no question of whether a load step ran. The description cannot drift from the file because
there is no separate thing to drift.

A table populated by an `INSERT` in the same file keeps most of that. The rows still ship with the
DDL, the store's boot executes the file's statements in order, and a fresh store therefore always
has them. What it gives up is only the guarantee against runtime mutation, and the store's writers
are all enumerated: none of them touches `meta_`. So the loss is a hypothetical, and the gain is a
key, three `NOT NULL`s and declared types.

## Why it is being asked now

R742 adds a fourth `meta_` relation, `meta_materialize`, a registry pairing a view with the table its
rows are materialized into. Its `reason` column has to be `NOT NULL`: the registry is where that item
moves "why this is not simply a view" from a per-table comment, and a registration that cannot say it
is not a registration. Under the current family form there is no way to require it, and the first
draft of R742 proposed a build gate instead, which is the wrong instrument and a clean symptom of the
missing constraint.

R742 therefore lands `meta_materialize` as a constrained table and leaves the older three alone,
recording the divergence as a tracked simplification rather than silently forking the family. This
item is what converges it.

## What conversion touches

* **`meta_family`** and **`meta_prefixless_relation`** become tables with keys, `NOT NULL` where the
  column is required, and an `INSERT` carrying the rows they state today. `meta_prefixless_relation`
  loses its `CAST(NULL AS VARCHAR)` in favour of a declared nullable column.
* **`meta_relation_family`** stays a view. It is not a roster; it is the census that joins the
  rosters against `INFORMATION_SCHEMA.TABLES`, and a census must be evaluated rather than stored.
* **`FactSchemaGateTest.everyRelationLeadsWithItsPartitionDimension`** walks every base relation
  carrying a primary key and expects `graph_name` unless the name matches `sql_`, `jvm_`, `java_` or
  an enumerated `store_` case. A keyed `meta_` table falls into the `else` and is reported. The gate
  needs a `meta_` arm expecting the relation's own key, which is in keeping with its own javadoc that
  `store_` "answers the question per relation rather than per prefix". R742 opens this hole with the
  first keyed `meta_` relation; this item either inherits the arm it added or adds it.
* **The DDL header's `meta_` charter**, which states the `VALUES` rationale quoted above and would
  need to state the new one.
* **Nothing in Java**, if jOOQ's generated types are unchanged in name. Worth confirming rather than
  assuming: codegen reads a live store, and a view and a table both generate a `TableImpl`, but a
  table additionally generates key metadata and may render column types differently where the view's
  were inferred.

## What would make the answer no

Worth stating so the item can be closed rather than lingering. If a `meta_` roster ever needs to be
read *before* its `INSERT` could have run, the view form is not a preference but a requirement. The
store's boot executes statements in file order and the `meta_` block is at the tail, so nothing today
is in that position; a future relation that participates in the boot itself might be.

The other honest no is churn: three relations, a gate arm, a header paragraph and a codegen check,
against a benefit that is real but not urgent for the two existing rosters, whose rows are short,
stable and reviewed in the diff. That is why this is filed rather than folded into R742.
