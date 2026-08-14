---
id: R676
title: "A @nodeId filter input on a multitable query cannot state a per-participant join path"
status: Backlog
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# A @nodeId filter input on a multitable query cannot state a per-participant join path

A filter input field carrying `miljoId: ID @nodeId(typeName: "Miljo")`, on a query returning a multitable interface whose participants each reach `miljo` through a differently-named FK column, cannot be authored at all. The build fails with:

> [author-error] input field 'miljoId': no unique FK from 'feide_applikasjon' to 'miljo'; declare @reference(path: [{key: ...}]) to disambiguate

and the remedy the message names does not exist for this shape: a `@reference(path:)` on an input field is stated once and applies to every participant, so one path cannot describe three differently-keyed tables. Reported against 10.0.0-RC30. The author's fallback is to decode the node id by hand inside a condition method, reimplementing the wire format the generator already owns.

## Why it happens

`NodeIdLeafResolver`'s join-path resolution takes the containing table, and with no `@reference` present falls back to single-hop FK auto-discovery via `ctx.catalog.findUniqueFkToTable(containingTable, targetTable)`. On a multitable query that resolution runs once per participant, each with its own table, so any participant lacking a unique single-hop FK to the target rejects and fails the build for the whole field.

The per-participant escape hatch exists but is out of reach at this coordinate. `@referenceFor(type:, path:)` is exactly the surface for "this participant's complete path from the parent's table", and its own documentation says participants not named keep automatic discovery, which is the semantics this case wants. It is declared `repeatable on FIELD_DEFINITION`. Meanwhile `@reference` is declared on `FIELD_DEFINITION | ARGUMENT_DEFINITION | INPUT_FIELD_DEFINITION` and `@condition` on all three. So the general per-participant path surface stops one location short of the coordinate that needs it, and the surfaces that do reach input fields cannot express per-participant variation.

## The rider: `@condition(override: true)` does not silence the gate

The reporter also observes that the FK-path demand fires even under `@condition(override: true)`, where the authored method owns the predicate and the generator's default column mapping is never used. That is the same shape already settled once for the column-miss arm, where `override: true` suppresses the miss because the author has taken responsibility for the predicate. Extending it to the `@nodeId` FK-path resolution is a separable and much cheaper change than per-participant paths, and may be the whole of what an author in this position needs.

Whether the two ship together is the Spec's call. They are independent: the override relaxation unblocks the reported schema without making per-participant paths expressible, and per-participant paths fix the general case without touching the override interaction.

## Already available, worth telling the reporter

The hand-rolled base64 in the workaround is unnecessary. `NodeIdEncoder` is emitted into `<outputPackage>.schema` whenever any type carries `@node` and exposes `peekTypeId(String)` plus a per-type `decode<TypeName>(String)` returning a typed jOOQ `RecordN` (null on malformed input or a typeId mismatch). `peekTypeId` in particular reads the discriminator without committing to a type, which is what a condition method serving several participants needs.

## Notes for whoever specs this

- Widening `@referenceFor` to `INPUT_FIELD_DEFINITION` and `ARGUMENT_DEFINITION` looks like the small move, but the directive's semantics are written against a field's participants. Whether an input field can name participants of the field that consumes it, and what happens when the same input type is reused across two fields with different participant sets, is the real design question.
- The failing message should not name a remedy that cannot work at the coordinate it fires on. Even if per-participant paths stay unbuilt, the multitable arm of this rejection wants its own wording.

Reported at https://github.com/sikt-no/graphitron/issues/525 (second half; the `@condition` overload half is its own item).
