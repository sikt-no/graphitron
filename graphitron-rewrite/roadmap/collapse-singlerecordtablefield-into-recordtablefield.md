---
id: R305
title: "Collapse SingleRecordTableField into RecordTableField (derive the DataLoader-skip from single-source cardinality)"
status: Backlog
bucket: structural
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-06-14
last-updated: 2026-06-14
---

# Collapse SingleRecordTableField into RecordTableField (derive the DataLoader-skip from single-source cardinality)

Split out of **R290** (`datafetcher-field-dimensional-slots`). R290 retired one of its two planned
leaves (`ConstructorField`) and landed the slot materialisation + the re-fetch derivation, but
deferred the second: collapsing `ChildField.SingleRecordTableField` into `ChildField.RecordTableField`.
R290's appendix inventory of **47** live leaves is the post-collapse target; with this item still open
the live leaf set is **48**.

## Why this is not a mechanical leaf merge

R290 framed the collapse as "the single-source-object DataLoader-skip becomes a derived detail
(computed from single-source cardinality off the slot)." On implementation the two leaves turned out
to use **different emit mechanisms**, not one mechanism with a skip flag:

- `SingleRecordTableField` is an **inline-bound** data field. `FetcherEmitter.bind` /
  `buildSingleRecordTableFetcherValue` (Wrap.Record and Wrap.TableRecord arms, ~340 lines) emit a
  DataFetcher that reads the producer's in-hand record(s) off `env.getSource()` and runs a follow-up
  SELECT inline. No DataLoader, no rows-method, no `loaderRegistration` / `parentCorrelation`; it is
  `OrderingOwnedByProducer`, and it carries the R141 / R158 / R275 payload-carrier execution coverage
  (the delete-then-echo `fjernSakTagg` shapes).
- `RecordTableField` is a **DataLoader / method-backed** field: it registers a loader, has a generated
  rows-method, batches across parent keys, and implements `BatchKeyField`.

Collapsing means teaching `RecordTableField`'s emit a second in-hand-source mode (no loader) selected
by a derived single-source predicate, and threading that mode through the registration emitter
(`FetcherRegistrationsEmitter`), the `TypeFetcherGenerator` dispatch + `IMPLEMENTED_LEAVES` partition,
the `OrderingOwnedByProducer` marker, `RecordBindingResolver` / `ProducerBinding`, and the validator.
That is a fetcher-emit refactor with execution-tier risk; it also overlaps R222 Stage 5's permit
consolidation, so it earns its own gated item rather than riding R290's diff.

## Scope

- The two construction sites in `FieldBuilder.classifyObjectReturnChildField` (the R178 DML-carrier arm
  and the R275 `@service`-carrier arm, both today building `new ChildField.SingleRecordTableField(...)`)
  reclassify to `RecordTableField`, with the single-source / in-hand-source case encoded so the emitter
  can select the no-loader mode. The `(carrier, intent, mapping)` tuple is unchanged
  (`Source`/`Fetch`/`Table`), so the R281/R299 corpus stays byte-identical for the reclassified field.
- `RecordTableField`'s emitter gains the in-hand-source (no-DataLoader) follow-up-SELECT mode, derived
  from single-source cardinality off the `SourceKey` slot rather than from leaf identity.
- `SingleRecordTableField` is deleted: the leaf record, its `ChildField.intent()` / `mapping()` switch
  arms, its `OrderingOwnedByProducer` membership, its `TypeFetcherGenerator` / `FetcherEmitter` /
  validator dispatch, and the `SingleRecordPayloadPipelineTest` arm asserting it has a dedicated
  dispatch arm. Live leaves 48 -> **47** (R290's appendix target).

## Acceptance

- **Corpus tier**: byte-identical. The `FilmPayload.film` (Source/Fetch/Table) coverage that
  classifies as `SingleRecordTableField` today reclassifies to `RecordTableField` with its tuple
  unchanged and stays green without edit. `everyGraphitronFieldLeafHasAKnownDispatchStatus` stays green
  with one fewer partition entry; R290's appendix updates 48 -> 47 in the same change.
- **Execution tier**: the R141 / R158 / R275 payload-carrier behaviour (single + bulk, DIRECT +
  OUTCOME_SUCCESS envelopes, the delete-then-echo shapes) is preserved end-to-end against PostgreSQL
  via the existing `SingleRecordPayloadDmlTest` / `SingleRecordTableFieldServiceProducerExecutionTest`
  fixtures. This is the load-bearing gate: the collapse must not change emitted SQL or runtime results.
- **Pipeline / TypeSpec tier**: the reclassified field's emit-shape (no-loader inline follow-up SELECT
  for the single-source case, DataLoader path otherwise) is pinned by structural `TypeSpec` assertions.
- Full aggregator green (`mvn install -Plocal-db`), graphitron-lsp included.

## Design note (consult principles-architect before Spec -> Ready)

Whether to genuinely unify the two emit mechanisms behind a derived single-source predicate (as R290
framed it) or to keep the inline-source body as a `RecordTableField` sub-mode is the open fork; both
keep the leaf set at 47. The unification's blast radius (registration emitter, dispatch partition,
ordering marker) should be sized against R222 Stage 5's permit consolidation, which may subsume part
of it. Consult the `principles-architect` subagent on the emit-mode shape during the Backlog -> Spec
draft.

