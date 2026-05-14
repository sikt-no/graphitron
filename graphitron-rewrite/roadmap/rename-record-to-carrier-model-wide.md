---
id: R163
title: "Rename Record to Carrier across the carrier-walk model"
status: Backlog
bucket: cleanup
priority: 3
theme: model-cleanup
depends-on: [mutation-result-field-sealed-on-kind]
created: 2026-05-14
last-updated: 2026-05-14
---

# Rename Record to Carrier across the carrier-walk model

> **Superseded by R164** (`field-model-two-axis-pivot`). The carrier-walk plumbing renamed by this item (`SingleRecordCarrierShape`, `ChildField.SingleRecord*`, `BuildContext#tryResolveSingleRecordCarrier`, etc.) does not survive R164 in its current shape: the carrier-walk family collapses into the `Lift` arm of DataFetcher (with no loader registration for the single-carrier follow-up case) and the surrounding plumbing types get rebuilt as components of the new structure. The rename happens implicitly. Discard once R164 enters Spec.

The word "Record" is overloaded three ways in graphitron-rewrite, and the carrier-walk family carries the only overload we can cheaply fix. **Meaning A** is the `@record` SDL directive plus its payload type (`DataElement.Record`, `MutationServiceRecordField`, the `Result.Record` arm inside `MutationServiceResultField`, the `record:` argument on the directive). **Meaning B** is the post-R161 single-row DML carrier walk (`MutationDmlRecordField`, `MutationBulkDmlRecordField`, `SingleRecordCarrierShape`, `SingleRecordCarrierResolution`, `BuildContext#tryResolveSingleRecordCarrier`, `ChildField.SingleRecordTableField` and its `FromReturning` siblings, `buildSingleRecordTableFetcherValue`, `buildMutationDmlRecordFetcher`, etc.). **Meaning C** is vendor jOOQ (`org.jooq.Record`, `RecordN<...>`, `TableRecord`, plus `Result<Record>` and `Result<RecordN<PK>>` in fetcher signatures). R162 partially fixes meaning B by renaming the four permits to `*ResultField`, but leaves the carrier-walk plumbing (`SingleRecordCarrierShape`, the `ChildField` family, the resolver) on the old name — and "Result" itself fights jOOQ's `Result<>` for the same word. This item lands a coherent rename of meaning B alone, using "Carrier" rather than "Result": vendor-collision-free, already the prose word for this concept (R161's slug, R162's `Result.CarrierWrapper` arm, `CarrierFieldRole`, `SingleRecordCarrierShape`), and naturally drops the redundant "Single" qualifier R162 already dissolves into `tableInputArg.list()`.

## Target rename table

| Today | Target |
|---|---|
| `MutationDmlRecordField` | `MutationInsertField` / `MutationUpdateField` / … (R162's verb-on-identity, suffix dropped) |
| `MutationBulkDmlRecordField` | (folded; bulk lives on `tableInputArg.list()` per R162) |
| `MutationServiceRecordField` | `MutationServiceField` (R162's consolidation; arm-level `Result.Record` still names meaning A and stays) |
| `SingleRecordCarrierShape` | `CarrierShape` |
| `SingleRecordCarrierResolution` | `CarrierResolution` |
| `BuildContext#tryResolveSingleRecordCarrier` | `tryResolveCarrier` |
| `ChildField.SingleRecordTableField` | `ChildField.CarrierTableField` |
| `ChildField.SingleRecordIdFieldFromReturning` | `ChildField.CarrierIdFieldFromReturning` |
| `ChildField.SingleRecordTableFieldFromReturning` | `ChildField.CarrierTableFieldFromReturning` |
| `buildSingleRecordTableFetcherValue` | `buildCarrierTableFetcherValue` |
| R162's `Result.CarrierWrapper` arm | `Carrier` (redundant suffix drops) |

Explicit non-targets (do not rename, even though they contain "Record"):

- `@record` SDL directive and its `record:` argument
- `DataElement.Record` (arm of the data-element seal — meaning A)
- `MutationServiceResultField.Result.Record` discriminator arm (R162's keep — meaning A)
- `SourceKey.Wrap.Record` / `SourceKey.Wrap.TableRecord` (jOOQ vendor naming — meaning C)
- `SourceKey.Reader.ServiceTableRecord` / `ServiceUntypedRecord` (jOOQ vendor — meaning C)
- `GraphitronType.JooqRecordType` / `JooqTableRecordType` / `JavaRecordType` (vendor / Java language — meaning C and "Java record" the language feature)
- Any `Result<Record>` / `Result<RecordN<...>>` prose or type expression
- R162's `Result` discriminator name itself (jOOQ collision is contained inside `MutationInsertField.Result`; rename to `ReturnShape` only if import-site readability bites in practice)

## Why "Carrier" beats "Result"

- **No vendor collision.** jOOQ owns `Record`, `RecordN`, `TableRecord`, `Row`, `RowN`, `Result`. "Carrier" appears nowhere in jOOQ, graphql-java, or the Java stdlib.
- **Names the concept, not the byproduct.** The family is defined by *how* it produces output (walk a wrapped RETURNING row per field), not by *having* output. "Result" is true of every mutation; "Carrier" is true of exactly this family.
- **Already in use prose-side.** "Carrier walk" is the established term for the post-R161 scheme. The rename *drops* "Record" rather than swapping it; reads as cleanup, not invention.
- **"Single" qualifier drops out.** R162 dissolves the single-vs-bulk axis into `tableInputArg.list()`. `CarrierShape` (no qualifier) is the honest post-R162 name.

## Why not "Row"

Precise ("one DB row") and short, but collides with `SourceKey.Wrap.Row` (jOOQ `RowN<>`) and `RowsMethodBody` / `RowsMethodShape` (the per-row read plumbing). Both already mean "row-shaped" things via jOOQ vocabulary. Carrier wins on collision-freedom.

## Spec must address

- **Discriminator name retention.** Keep R162's outer `Result` discriminator (jOOQ collision is contained inside `MutationInsertField.Result.<arm>`), or rename to `ReturnShape` / `Emit`. Default is keep; flip if import-site reads ambiguously.
- **Doc surface audit.** `rewrite-design-principles.adoc`, `runtime-extension-points.adoc`, `dispatch-axes.adoc`, `code-generation-triggers.adoc`, the rewrite README, and `getting-started.adoc` all carry "Record" in mixed senses. Each occurrence needs tagging by meaning (A / B / C) before edit. This is the slowest part of the work.
- **Capability catalog audit.** Any `@capability(name: ...)` slugs containing "record" or "single-record" need renaming in lockstep; capability slugs are part of the SDL author-facing contract.
- **Branch-name fossils.** The working branch `claude/rename-record-to-result-DOlSi` carries the obsolete "result" target; keep as a historical artifact or rename. R161's slug `unify-record-dml-on-carrier-walk` is mid-flight Ready/In-Progress; do not retroactively rename — treat all existing roadmap slugs as fossils.
- **Test fixture impact.** `GraphitronSchemaBuilderTest` and other classification-fixture tests reference the renamed permit types by name across many cases. R162 already enumerates this surface; this item inherits and extends it to the carrier-walk plumbing.
- **`MutationField` switch arm count.** Verify the switch in `TypeFetcherGenerator` reads cleanly after both R162's verb-on-identity rename and this item's plumbing rename land. Seven verb-named arms (`MutationInsertField`, `MutationUpdateField`, `MutationMultiRowUpdateField`, `MutationDeleteField`, `MutationMultiRowDeleteField`, `MutationUpsertField`, `MutationServiceField`) each dispatching internally on `Result` / `ReturnShape`.

## Dependencies

- **R162** (`mutation-result-field-sealed-on-kind`) must land first. The permit-level renames are R162's structural payload; this item rides on the same touch surface for the plumbing but does not duplicate R162's verb-on-identity work. Hard dependency.
- **R161** (`unify-record-dml-on-carrier-walk`) is inherited transitively via R162. The carrier-walk family must be unified before its plumbing is renamed.
