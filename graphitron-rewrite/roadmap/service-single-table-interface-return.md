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
