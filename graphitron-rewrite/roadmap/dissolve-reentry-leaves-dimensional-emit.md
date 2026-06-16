---
id: R314
title: "Dissolve the re-fetch (reentry) leaf fields: emit reentry by switching on the dimensional model"
status: Backlog
bucket: architecture
priority: 4
theme: structural-refactor
depends-on: [dimensional-model-pivot]
created: 2026-06-15
last-updated: 2026-06-15
---

# Dissolve the re-fetch (reentry) leaf fields: emit reentry by switching on the dimensional model

A vertical slice of the **R222** dimensional-model pivot, scoped to the *reentry* (re-fetch) emit
family. Today the generator emits reentry by switching on **leaf identity** — `RecordTableField`
(incl. the R305 source=target carrier), `RecordLookupTableField`, `RecordTableMethodField`,
`ServiceTableField`, the root `QueryServiceTableField` / `MutationServiceTableField`, and projected
`DmlTableField`. The dimensional axes (`carrier` / `intent` / `mapping`) are a *derived view* over
those leaves, not the emit substrate, and `GraphitronSchemaValidator.dispatchPerformsReFetch` is a
hand-kept leaf-switch *mirror* of `OutputField.requiresReFetch()` precisely because the emit
re-decides re-fetch per leaf instead of reading the model.

## The insight

The reentry SQL is **uniform** across the family: re-project the `@table` from keys held at the
source — `VALUES(idx, key…) JOIN target ON pk ORDER BY idx`, scatter. The per-member variation is
exactly what the three dimensions should own:

- **Carrier** carries the *source context*: how the key is lifted off `env.getSource()` (the
  source-read — today the source side of `SourceKey`: reader, envelope, key columns — plus the
  `SourceShape` / `SourceCardinality` R305 already moved onto `Carrier.Source`). `Query` / `Mutation`
  carriers are payload-less because they have no source.
- **Intent** carries *what's needed to carry out the operation*: `Fetch` a plain read, `Lookup` the
  positional correspondence (today `LookupMapping`), `QueryService` the service-lift / method ref.
  `Intent` becomes a sealed payload-carrying hierarchy, not a bare enum.
- **Mapping** carries the *output projection*: `Table` the target `@table`, `TableConnection` the
  pagination.

So `emit(reentry) = f(carrier.sourceContext, intent.operation, mapping.target)` with no `instanceof`.

## Goal

1. Move the reentry emit slots onto their owning dimension (source-read → `Carrier.Source`; lookup
   correspondence / service-lift → `Intent`; target table → `Mapping`).
2. Re-platform the reentry emit in `TypeFetcherGenerator` / `SplitRowsMethodEmitter` to switch on the
   dimensions instead of the re-fetch leaf classes.
3. Dissolve the re-fetch leaf fields (reduce to thin records or remove) once their distinguishing
   data lives on the dimensions.
4. Retire `dispatchPerformsReFetch` and its mirror test: the generator consults `requiresReFetch()`
   directly, so the derivation and the emit cannot drift by construction (the design principle
   "if two consumers evaluate the same predicate over a model field, the branch belongs in the
   model").

## Scope

The reentry/re-fetch emit family only — not all of generation. Other emit families (inline
`TableField` projection, plain `@splitQuery`, polymorphic) stay leaf-dispatched until their own R222
slices. The R305 reentry execution tier (`SingleRecordPayloadDmlTest`,
`SingleRecordTableFieldServiceProducerExecutionTest`, the LocalContext error path) plus the
`@classified` corpus are the ready-made acceptance gates: same rows / same order, now emitted off
the dimensional model.

## Lineage

Follows **R305** (dissolved `SingleRecordTableField`, materialised `Source{shape, cardinality}` on
the carrier, and made `requiresReFetch` an axis-derived predicate — but kept the reentry emit
leaf-dispatched, with the source-read on the leaf's `SourceKey` rather than on `Carrier.Source`).
This item carries the source context onto the carrier and makes the model *drive* the reentry emit.
