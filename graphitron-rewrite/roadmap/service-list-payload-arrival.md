---
id: R308
title: Fix the @service list-payload N+1 by deriving many-arrival for list-returning carriers
status: Spec
bucket: structural
priority: 4
theme: structural-refactor
depends-on: [collapse-singlerecordtablefield-into-recordtablefield]
created: 2026-06-14
last-updated: 2026-06-14
---

# Fix the @service list-payload N+1 by deriving many-arrival for list-returning carriers

The behavioural change that the source-arrival-cardinality axis unlocks, carved out of **R305**
(`collapse-singlerecordtablefield-into-recordtablefield`) so that item's behaviour-preserving collapse
keeps its corpus-byte-invariance proof. R305 names the axis and merges `SingleRecordTableField` into
`RecordTableField` with the emit mechanism derived from arrival; this item depends on that merged leaf
and arrival verdict to fix a live defect R305 deliberately does not touch.

## The defect

A `@service`-carrier mutation may return a **list of payloads** (`@service ...: [Payload]`). The carrier
is accepted at classify time: `FieldBuilder.checkServiceReturnMatchesPayload` explicitly admits
`List<Payload>` when the field's return wrapper is a list. But the payload's data field is still
classified as the no-DataLoader inline-bound shape (`SingleRecordTableField` today, the single-arrival
arm of the merged `RecordTableField` after R305): the `serviceEmittedBinding` arm keys on the payload
type name and computes the `SourceKey` value-cardinality from the *data field's* own wrapper, never from
the **carrier's arrival**. When the carrier arrives as a list, graphql-java iterates it and invokes the
inline-no-loader fetcher once per payload element, each running its own follow-up SELECT: an unbatched
N+1.

The defect is asymmetric, and that asymmetry is the tell that the arrival axis was missing. The DML
carrier path **rejects** the same shape outright (`MutationInputResolver.validateReturnType`, "list of
@record ... is not yet supported"; `GraphitronSchemaBuilderTest.DML_RECORD_PAYLOAD_LIST_REJECTED`),
while the `@service` path accepts it and silently misbatches. Both faces are the single missing concept:
without a way to express many-arrival on a payload carrier, one path had to reject and the other had no
choice but the single-arrival emit.

No current test exercises a list-arriving payload carrier; the only coverage of the shape is the DML
rejection fixture. The `@service` list shape is unguarded, so this is a live gap, not a regression.

## Spec

- The `@service`-carrier classification (`FieldBuilder.classifyObjectReturnChildField`, R275 arm) reads
  the **carrier's arrival** (the producing `@service` field's wrapper, per R305's per-seat derivation),
  not just the data field's own wrapper. A list-arriving carrier derives many-arrival and routes the
  data field to the merged leaf's DataLoader emit (a `LoaderRegistration` + rows-method) instead of the
  inline per-element read. A single-arriving carrier is unchanged (stays the inline in-hand read).
- The arrival verdict and its consumer come from R305; this item supplies the carrier-arrival input on
  the `@service` seat and the loader-emit wiring for the many-arrival case, with
  `GraphitronSchemaValidator` mirroring the verdict (per R305's validator-mirror design).
- The DML carrier path's outright rejection of list-payloads is **left as-is**; lifting it (now that
  the shape is expressible) is a separate future item, noted but out of scope.

## Acceptance

- **Pipeline tier**: a new fixture asserts an `@service` carrier returning `[Payload]` derives
  many-arrival and emits a DataLoader (`LoaderRegistration` + rows-method) for its data field, where the
  pre-R308 emit was the inline per-element read; the single-payload sibling stays inline. Structural
  `TypeSpec` assertions, no `code().toString()` body matches.
- **Execution tier**: a new `graphitron-sakila-example` fixture round-trips a list-returning `@service`
  carrier's payload data field through a single batched query (no N+1), proving the loader path is
  correct end-to-end. Sits alongside `SingleRecordTableFieldServiceProducerExecutionTest`.
- **Corpus tier**: the new list-arriving `@service` example is added to the R281/R299 corpus; existing
  rows stay byte-identical (this item adds a shape, it does not reclassify existing ones).
- Full aggregator green (`mvn install -Plocal-db`), graphitron-lsp included.
