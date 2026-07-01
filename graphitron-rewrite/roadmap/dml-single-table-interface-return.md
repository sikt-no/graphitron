---
id: R406
title: "Support single-table discriminated interface as a DML @mutation return type"
status: Backlog
bucket: feature
priority: 4
theme: mutations-errors
depends-on: []
created: 2026-07-01
last-updated: 2026-07-01
---

# Support single-table discriminated interface as a DML @mutation return type

A DML `@mutation(typeName: INSERT|UPDATE|UPSERT)` cannot return a
single-table discriminated interface (`@table @discriminate(on:)`).
`MutationInputResolver.validateReturnType` rejects any interface/union
return outright (`MutationInputResolver.java:242`, "return type '...'
(interface/union) is not yet supported; use ID or a @table type"). Today
a mutation that writes a row to a shared, discriminated table (e.g.
`content`) has no way to return the concrete subtype
(`FilmContent` / `ShortContent`) the row represents; the author must
return the interface's concrete `@table` implementer explicitly or drop
polymorphism.

Unlike the service path (R405), the return/dispatch half is
straightforward: the write targets one known table, and the row's
discriminator column value selects the subtype, so the emitter can
project the discriminator (aliased as
`MultiTablePolymorphicEmitter.DISCRIMINATOR_COLUMN`) on the RETURNING /
re-fetch and route per row through the discriminated `TypeResolver`,
reusing the read-side single-table mechanism.

The genuinely new design problem is the **input side**. A DML mutation
resolves exactly one `@table` input type
(`MutationInputResolver.resolveInput`). Returning a single-table
interface raises: which implementer's subtype-specific columns is the
client supplying, and where does the discriminator column value come
from, an explicit input field, a per-implementer input type, or inferred?
That subtype-selective input shape is the crux of this item and does not
have an obvious answer yet.

## Scope

- DML verbs only. DELETE is likely out (it returns an encoded ID, not a
  projected row; nothing to discriminate).
- The `@service` single-table interface return is tracked separately by
  R405; this item is only the DML/`@mutation` surface.

## Spec-stage questions

- Input-side subtype selection: single input type carrying an explicit
  discriminator field, one input type per implementer, or discriminator
  inferred from which subtype-specific columns are populated?
- Does the discriminator value get written from the input, or is it a
  fixed database default / trigger concern outside graphitron?
- Interaction with cardinality-safety and bulk DML (R141/R145): does a
  bulk single-table-interface INSERT make sense, and how are per-row
  subtypes expressed?
- Reuse of the R405 read-side dispatch mechanism for the return half so
  the two items share one discriminator-projection path.
