---
id: R648
title: "Author-declared batch key for child @service on record-backed parents"
status: Backlog
bucket: feature
priority: 3
theme: service
depends-on: [service-coordinate-rejection-precedence]
created: 2026-08-13
last-updated: 2026-08-13
---

# Author-declared batch key for child @service on record-backed parents

A child `@service` field can only be batched when its parent type maps a table: the batch key is hardwired to the parent's primary key. `FieldBuilder.classifyChildFieldOnResultType` passes `List.of()` for `parentPkColumns` on every record-backed parent, `ServiceCatalog` reads the empty list as "root coordinate" and discards any recognised SOURCES shape, and the parameter falls through to name-based argument binding. Schemas that aggregate a type in Java and want one child resolved by a service have no route, and the two mechanisms that already lift a batch key out of a record-backed parent (typed-accessor inference and `@sourceRow`) feed only the table-child path.

## Why this is a gap and not a stance

Graphitron already accepts every premise the feature needs. Record-backed parents are supported, including author-declared key lifting via `@sourceRow`. The service contract already holds for a service-returned parent, not just a framework SELECT, so "the parent did not come from a generated query" is settled. And the key an author wants here is still a catalog key: a real column on a real table, reached through hand-written jOOQ, which is the documented extension point. What is missing is a way to say *which* key, not permission to have one.

The database-first stance says data semantics live in the database. It does not say every batch key must be the parent's primary key. Meanwhile "separate business logic from API code" argues the other way: a type aggregated in Java is aggregated there because that is where the business logic belongs, and today's rule pushes authors to add a database view purely to satisfy a code-generation constraint.

## Desired outcome

One key-provenance mechanism, consumed by both child paths. Today they diverge:

[cols="1,2"]
|===
| Child path | Batch key source

| Table child
| Catalog FK, typed-accessor inference, or `@sourceRow`

| `@service` child
| Parent table primary key, and nothing else
|===

Accessor inference and `@sourceRow` should feed the `@service` SOURCES path too. The parent declares how to produce the key record; the `Set<KeyRecord>` parameter binds; the DataLoader dedups across the request as it does for a table parent.

The substantive change underneath: the SOURCES contract relaxes from "keys carry the parent's primary key" to "keys carry the columns the lift produced". This makes the feature more database-first, not less. The key stops being an accident of the parent's PK and becomes a named set of catalog columns the author pointed at. `ServiceDirectiveResolver.validateTableRecordSourceParentTable` then checks the declared element type against the lift's table rather than the parent's, which is the check it always wanted to be.

## Why the table-bound workaround does not substitute

Making the parent table-bound (a view) does not unblock the shape. `validateTableRecordSourceParentTable` requires the `Set<X>` element to be the parent type's own backing record class, so a service keyed on some other table's record is rejected on a view exactly as it is on a POJO. The author has to rewrite the service to take the parent's record and re-derive the real key inside it, which turns a reusable service method into a per-caller variant. The workaround costs the schema change and the reuse.

## Scope notes

* Both halves exist on opposite sides of a seam: `@sourceRow` knows how to lift a key from a record-backed parent, and the service path knows how to dispatch a `Set<Key>` batch. The work is joining them and threading the lift through the sites that pass `parentPkColumns` today.
* The diagnostic surface belongs to this item. The current deferred rejection prescribes the wrong fix ("the batch key must be lifted through the parent chain to the rooted `@table`"); the replacement is the declare-a-key decision tree, shaped like the existing table-child rejection documented in the result-types how-to.
* Reaching that diagnostic at all requires the rejection-precedence fix tracked separately in R649, which is a prerequisite rather than a duplicate: parameter-binding rejections currently mask coordinate-level ones on this path.
* Reported twice by the same consumer team, most recently on a type aggregated in Java whose child is produced by a shared translated-text service.
