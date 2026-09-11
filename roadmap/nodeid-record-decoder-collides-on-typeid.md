---
id: R947
title: "Two @nodeId input fields over one jOOQ record collapse onto one decoder and decode with the wrong typeId"
status: Backlog
bucket: bug
priority: 1
theme: nodeid
depends-on: []
created: 2026-09-11
last-updated: 2026-09-11
---

# Two @nodeId input fields over one jOOQ record collapse onto one decoder and decode with the wrong typeId

## Goal

Two input fields carrying `@nodeId(typeName:)` for two different node types that sit over the same
table each get their own decoder, so each one accepts the ids of the type it names and rejects the
other's. Today both collapse onto one generated helper that hardcodes whichever node typeId was
collected first: the second field rejects its own valid ids with "Decoded NodeId did not match the
expected type for this argument", and silently accepts the first type's ids instead. A node type is
a GraphQL type carrying `@node(typeId:)`, whose opaque global id is the typeId plus the key columns;
nothing stops two of them from naming the same `@table`, and consumers do exactly that while
renaming a type with the old name still exposed.

## What is wrong

`InputBeanInstantiationEmitter.collectRecordDecoders` dedups the `NodeIdDecodeRecord` leaves into
`Map<ClassName, ...>` keyed by `CatalogRefs.recordClass(rec.table())`, the jOOQ record class alone.
The leaf carries a `typeId` of its own, and the record's compact constructor insists it be non-empty,
but the dedup throws it away: two leaves differing only in typeId are one map entry, and the entry
that survives is `putIfAbsent`'s first, so which of the two types works is collection order.

`recordDecodeHelperName` mints the helper name the same way, off the record class alone
(`decode<RecordType>`). So the key and the name are the same defect twice, and a fix that widens
only the map key emits two methods named `decodePersonRecord` on one class: a compile error at the
consumer rather than the mistyped decode. Both halves move together or neither does.

The encoding side is unaffected; output fields over the two types already emit distinct ids, which
is what makes the failure visible as an id that graphitron itself minted and then refuses.

## Reported from

github.com/sikt-no/graphitron issue 548. Hit in fs-plattform/opptak renaming `Person` to
`OpptakPerson` over `@table(name: "person")`: the output fields migrated, and the mutation input
field had to be held back because its `opptakPersonId` argument would not take an `OpptakPerson` id.
A migration that exposes an old and a new name side by side is the shape that reaches this, and it is
the shape a rename needs, so the defect blocks the normal way out of a type rename.

## What a spec should settle

Whether the key is `(record class, typeId)` or the typeId alone, and what the second helper is
called. A name minted from the node type rather than the record class (`decodePersonNodeId` beside
`decodeOpptakPersonNodeId`) reads better than a disambiguating ordinal, but it renames the helper in
the single-type case too, which is every existing fixture's generated output. Whether that churn is
worth it, or whether the one-type case keeps `decode<RecordType>` and only a collision mints the
longer name, is the fork.

The list variant (`decode<RecordType>List`) and its `listOut` map key the same way and need the same
treatment, as does the polymorphic sibling's naming: `collectPolymorphicDecoders` keys on container
name and already documents an unreached collision of its own, which is a different case (one
container dispatching over many types) but shares the naming surface on the emitted class.

A test wants two node types over one table with distinct typeIds, both reachable as input fields on
one mutation, asserted at least at the generated-source tier so the two helper bodies are visible,
and ideally at the execution tier so the round trip of a minted id back through its own argument is
pinned.
