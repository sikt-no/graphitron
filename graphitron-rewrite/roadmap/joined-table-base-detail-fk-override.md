---
id: R393
title: "Author-declared base-to-detail FK override for joined-table inheritance (ambiguous-FK case)"
status: Backlog
bucket: feature
priority: 7
theme: interface-union
depends-on: [discriminated-joined-table-inheritance]
created: 2026-06-26
last-updated: 2026-06-26
---

# Author-declared base-to-detail FK override for joined-table inheritance (ambiguous-FK case)

R389 ships first-class discriminated joined-table inheritance with the base-to-detail join
**inferred** from the catalog: it picks the unique foreign key between a participant's detail table and
the discriminated base. When more than one FK connects the two tables, inference is ambiguous; R389
handles that by **rejecting** the schema at validate time with a candidate-FK hint (a hard
`INVALID_SCHEMA` author error), so the ambiguous-FK author has no way to express which key to join on.

This item adds the escape hatch: an **author-declared override** that names the base-to-detail join path
explicitly, lifting the ambiguous case from "rejected" to "supported". The constraint carried over from
R389's design review is that the override must resolve, at the parse boundary, into the existing
`JoinStep` / `JoinSlot` vocabulary (the same family `@reference` paths and DTO-parent batching speak)
through the `ctx.parsePath` mechanism `@reference` already uses; the emitter must keep receiving an
already-classified hop and never a raw `ForeignKey<?,?>` or a re-parsed string. It is deferred out of
R389 because the inference path plus the rejection already make joined-table inheritance usable for the
common shape (the detail table's primary key *is* its foreign key to the base, a single unambiguous FK);
the override only matters for the rarer multi-FK schema and carries its own user-visible directive-surface
design (this was R389's open "fork 2").

Open question for Spec: the override directive surface. Candidates include a path argument on the
participant's `@table` / `@discriminator`, or a dedicated participant-level reference directive. The Spec
must draft the user docs for whichever surface it picks (first-client check), since this adds a
user-visible authoring directive.
