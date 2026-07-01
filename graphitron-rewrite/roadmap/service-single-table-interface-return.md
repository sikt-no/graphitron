---
id: R405
title: "Support single-table discriminated interface as a @service polymorphic return"
status: Backlog
bucket: feature
priority: 5
theme: interface-union
depends-on: []
created: 2026-07-01
last-updated: 2026-07-01
---

# Support single-table discriminated interface as a @service polymorphic return

A `@service` field returning a single-table discriminated interface
(`@table @discriminate(on:)`, with implementers pinned by
`@discriminator(value:)`) is rejected at classify time by
`FieldBuilder.deferServiceTableInterface`
(`FieldBuilder.java:3578`). The deferral fires from both the mutation
classifier (`classifyMutationField`, `FieldBuilder.java:3781`) and the
query classifier (`classifyQueryField`, `FieldBuilder.java:3602`), so it
blocks the single-table variant on the entire `@service` polymorphic
surface. The rejection currently carries an **empty backlog-item slot**
(`Rejection.deferred(..., "")`); closing this item is what fills that
reference in.

Multi-table service polymorphic returns already work (R365 "route (a)":
`MutationServicePolymorphicField` / `QueryServicePolymorphicField`). That
route dispatches each returned record on its **runtime Java class** to
pick `__typename`, then auto-fetches the selected columns by PK. For a
single-table interface every returned record is the *same* jOOQ table
record (all subtypes share one `@table`), so runtime-class dispatch
cannot tell the subtypes apart and would misreport `__typename`. That is
the precise reason the single-table variant was deferred rather than
folded into route (a).

The read side already solves exactly this discrimination problem:
`QueryTableInterfaceField` / `ChildField.TableInterfaceField` project the
discriminator column aliased as
`MultiTablePolymorphicEmitter.DISCRIMINATOR_COLUMN` (`__discriminator__`)
and let the discriminated `TypeResolver` route each row off the
discriminator *value*. The likely shape for this item is to recognise a
single-table participant set in the service route and, instead of
runtime-class dispatch, re-fetch by the service-provided PKs against the
shared table projecting `__discriminator__`, then route per row, that is,
the read-side single-table fetcher fed by a service-provided PK list.

## How discrimination works (reused, not reinvented)

`@discriminate(on: "CONTENT_TYPE")` names a real column on the shared
`@table`; each implementer's `@discriminator(value: "FILM")` pins that
implementer to a literal value of that column. At emit the read-side
fetcher (`buildInterfaceFieldsList`, `TypeFetcherGenerator.java:1037`)
projects that column first and unconditionally, aliased to the synthetic
`__discriminator__` (`MultiTablePolymorphicEmitter.DISCRIMINATOR_COLUMN`),
so the routing read stays unambiguous even when the interface also
exposes the discriminator as a queryable field. One `TypeResolver` per
`TableInterfaceType` is generated
(`GraphitronSchemaClassGenerator.java:126-158`): it reads
`record.get(DSL.field(DSL.name("__discriminator__")), String.class)` and
`switch`es the value to `env.getSchema().getObjectType(<implementer>)`.

The row that reaches the resolver only has to carry `__discriminator__`;
its Java type is irrelevant. That is exactly why the read side handles
single-table fine and route (a) does not, route (a) routes off the
record's runtime *class*, which is identical across all single-table
subtypes.

## Proposed flow

The service already hands back rows of the one shared table,
PK-populated, so class-dispatch is unnecessary; feed the read-side
single-table SELECT from the service's PKs instead:

1. Call the service, normalise into `List<Record>` in input order (the
   same normalisation route (a) does at
   `MultiTablePolymorphicEmitter.java:214-231`).
2. Collect the PKs off those records.
3. One SELECT against the shared table `WHERE pk IN (:servicePks)`,
   projecting `__discriminator__` + the unified participant field set +
   conditional cross-table `LEFT JOIN`s for subtype-specific fields, i.e.
   `buildInterfaceFieldsList` + `buildCrossTableJoinChain` reused with a
   different `WHERE` source.
4. Re-map result rows back to input positions by PK (single return is one
   PK). A PK matching no live row leaves that position null.
5. Return `Record` / `List<Record>`; the existing `TableInterfaceType`
   `TypeResolver` handles per-row typename off `__discriminator__`, no new
   resolver.

So the only genuinely new emit code is "service call, collect PKs, feed
the SELECT, re-map to positions"; discrimination, cross-table joins, and
the resolver are all reused. A distinct field variant
(`MutationServiceTableInterfaceField` + query twin) reads more cleanly
than overloading `MutationServicePolymorphicField`, since the emit is one
SELECT rather than route (a)'s stage-1/stage-2 UNION ALL. The participant
model is already right: single-table participants are
`ParticipantRef.TableBacked` (carry `discriminatorValue`), multi-table are
`ParticipantRef.TableBound`.

## Scope

- Covers the `@service` path only (both query and mutation surfaces,
  since they share `deferServiceTableInterface`). Framed as
  service-path-wide because the emitter work is identical for both;
  splitting query from mutation would be artificial.
- DML `@mutation` single-table interface returns are a separate concern
  (input-side subtype selection); tracked by R406.
- Union returns on the `@service` path stay permanently unsupported
  (`rejectServiceUnionReturn`), unchanged by this item.

## Spec-stage questions

- Does the service contract require each returned record to carry the
  discriminator column populated, or is projecting it during the by-PK
  re-fetch sufficient? (Re-fetch is cleaner and matches the read side.)
- How does this compose with `@asConnection` on a service single-table
  return, if at all in the MVP?
- Drop/`null` contract for records whose PK matches no live row, aligned
  with route (a)'s existing drop contract.
