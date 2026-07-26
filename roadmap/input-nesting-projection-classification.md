---
id: R337
title: "Surface input-field projections honestly on the lowered coordinate"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-06-19
last-updated: 2026-07-25
---

# Surface input-field projections honestly on the lowered coordinate

Re-scoped 2026-07-25 at the R519 cutover (the tombstone's guard condition fired: R97 is
Done and the `@table`-on-input removal shipped the per-coordinate model without covering
this surfacing). The original mechanism this file proposed, a new per-type
`GraphitronType` variant mirroring the output `NestingType`, stays rejected: input
classification is contextual, a function of the consuming field/coordinate, never a
global property of the type (see the permanent explainer
`roadmap/concepts/consumer-derived-input-tables.html`).

## Problem

Input-object field declarations have no author-facing classification surface. Two gaps,
one cause (no per-coordinate projection):

- **Input-field coordinates are dark in the LSP.** R519 deleted the last per-input-field
  `FieldClassification` projection (the `CatalogBuilder` walk over the retired
  `TableInputType.inputFields()`), so hover / goto / inlay on an input object's own field
  declarations render nothing. The fields *are* classified, per consuming field, into
  `ArgumentRef.InputTypeArg.PlainInputArg.fields()` and the DML write-target paths; the
  catalog projection just never surfaces those per-coordinate verdicts.
- **A nested grouping input still labels as `PojoInput` with a null backing.** A
  directiveless input nested under a table-bound parent is a projection of columns on the
  consumer's table; calling it a "POJO" leaks the reflection fallback into the type-level
  hover. The type-declaration hover survives via `PojoInput.resolvedTables` (the
  consumer-derived table list), but the label itself is still the contextless artifact.

## Direction

Surface the projection on the lowered coordinate, not on the type: the catalog gains
per-(consumer, input-field-path) entries derived from the consuming field's resolved
carriers, and the LSP renders an input-field hover that names the consumer(s) and the
column(s) each consumer resolves the field to. An input reused across consumers on
different tables shows one entry per consumer, which is the honest per-coordinate answer
the old type-level surface could never give. No new `GraphitronType` permit; no
whole-type table verdict.

## Scope notes for the spec pass

- The wire shape is the open question: keyed per input-type field with a consumer list
  payload, or keyed per (consumer coordinate × arg path). The LSP addresses by SDL
  position (the input type's declaration), so the projection must be reachable from the
  input-type coordinate either way.
- `LspColumnDispatchProjectionTest` and `FieldClassificationProjectionTest` pin the
  current deliberate absence; this item flips those pins to the new surface.
- Out of scope: how input fields resolve to columns (settled, consumer-derived), the
  output-side `NestingType`, and any `GraphitronType` surface change.
