---
id: R308
title: "Model carrier arrival on the @service payload seat: one coherent list-payload shape verdict"
status: In Progress
bucket: structural
priority: 4
theme: service
depends-on: []
created: 2026-06-14
last-updated: 2026-07-12
---

# Model carrier arrival on the @service payload seat: one coherent list-payload shape verdict

Re-specced 2026-07-10. The original 2026-06-14 body (titled "Fix the @service list-payload N+1 by
deriving many-arrival for list-returning carriers") predates R316's `(source, operation, target)`
pivot and claimed the payload data field of a list-returning `@service` carrier emits an inline
no-DataLoader fetcher, an unbatched N+1. That premise was verified stale on 2026-07-10 by running
the classifier and generator against every list-carrier sub-shape: post-R305 every
`RecordTableField` emits a `LoaderRegistration` + rows-method (the `RecordTableField` arm in
`TypeFetcherGenerator`), and graphql-java coalesces per-element `load()` calls within a dispatch
cycle, so **no shape produces an N+1 today**. The missing concept the original body named, carrier
arrival cardinality, is real; its live consequences are different and are what this item now fixes.

## The defect, verified

A `@service` carrier field may return a list of payloads (`@service ...: [Payload]`). Whether a
given (carrier wrapper, producer return shape, data field wrapper) combination is admitted, and
what it does at runtime, is decided by uncoordinated wrapper reads that never see the whole triple:

- `FieldBuilder.checkServiceReturnMatchesPayload` reads the **carrier's** wrapper (with an R329
  arm re-levelling to the data field for two-level record composites);
- `RecordBindingResolver.producerBindLevel` compares SDL list-ness to reflected multi-ness and
  silently **NoBinds** on disagreement, dropping the class backing instead of rejecting;
- `RecordBindingResolver.groundServicePayloadBinding` and
  `FieldBuilder.buildPayloadCarrierRecordTableField` key `SourceKey.Cardinality` and
  `LoaderRegistration.Dispatch` off the **data field's** wrapper alone.

Empirical outcomes per sub-shape (scratch classification + emit runs, 2026-07-10; the a1 verdict
re-verified against HEAD with a printed `ResultReturnType`):

- **Silently admitted, broken at runtime:**
  - `[Payload]` + single `@table` data field + producer returning a bare record: the root fetcher
    returns a non-iterable single record for a list SDL type; list coercion fails at runtime.
  - `[Payload]` + **list** data field (`films: [Film!]`) + producer returning `List<Record>`: the
    per-element key extraction casts each payload element's source (one record) to `Iterable<?>`,
    a per-element `ClassCastException`. This is a live instance of the anti-pattern the acceptance
    axiom forbids (a defensive runtime cast failing on a real request after a green build), so
    rejecting it is mandatory hygiene, not just DML symmetry. Beneath the crash sits a semantic
    hole: one flat producer list cannot say which records belong to which payload element.
- **Rejected late or misleadingly:**
  - class-backed `[Payload]` + single-return producer: NoBind drops the backing, classification
    admits, and only `GraphitronSchemaValidator`'s generic dangling-type rule rejects, never
    naming the cardinality mismatch.
  - class-backed `[Payload]` with a `@table` data field + single-return producer: rejected at
    classify time but with the record-handoff message ("must return FilmRecord"), steering the
    author away from the actual fix (`List<Payload>`).
- **Working today, by accident, unpinned:**
  - `[Payload]` + single `@table` data field + producer `List<Record>`: each payload element's
    `LOAD_ONE` load coalesces into one batched rows-method query. Correct and batched, but no
    test exercises it at any tier.
  - class-backed producers returning `List<Payload>` (plain properties, and with a `@table` data
    field): per-element reads/loads, correct.

The DML twin (`MutationInputResolver.validateReturnType`, "list of record-backed payloads is not
yet supported"; `GraphitronSchemaBuilderTest.DML_RECORD_PAYLOAD_LIST_REJECTED`) rejects the whole
family outright. The asymmetry stands, with the corrected reading: the `@service` seat admits a
superset it can only partially honour, and cannot say which subset it honours because carrier
arrival is not a modelled input to the verdict.

### Mechanism note: which read to change (a1 silent admit)

The a1 silent admit is subtle and cost one review round-trip to pin, so the exact chain is recorded
here. There are **two independent bindings on the payload**, answering to different gates:

- The payload **data field's** `ServiceEmitted` grounding (`RecordBindingResolver.groundServicePayloadBinding`)
  peels one container level off the method return and matches the data field's record class,
  **regardless of the carrier wrapper**; it grounds in both a1 and the coherent a2, and only drives
  the data field's `RecordTableField`.
- The payload **type's** result-axis binding (`RecordBindingResolver.groundProducerResult` →
  `addResultObservation` → `resultMemo`, consumed at `TypeBuilder.buildResultType` via
  `resolveResult`) is what populates the payload `ResultType`'s `fqClassName`, and it **is** gated
  by `producerBindLevel`: `BindsWrapper` grounds it, `NoBind` grounds nothing.

For a1 (`[Payload]` carrier, single-record producer), `producerBindLevel` returns `NoBind` because
the carrier's list-ness disagrees with the producer's single return, so `resultMemo` stays absent
and the payload's `ResultType.fqClassName` is **null**. `checkServiceReturnMatchesPayload` is
correctly gated and simply returns early on the null `fqClassName` (it never reaches its
carrier-wrapper comparison), so it is **not** the read to change. **The mechanism to fix is the
`producerBindLevel` `NoBind` arm dropping the backing silently** on a carrier-vs-producer
cardinality disagreement: that silent drop is what lets an incoherent shape through with no strict
check ever firing. The coherent a2 case takes the `BindsWrapper` arm (cardinalities agree), grounds
`fqClassName`, and the strict check passes. So the shape verdict below replaces the `NoBind`-silent-drop
decision, not the `checkServiceReturnMatchesPayload` gate.

## Spec

- **Carrier arrival gets one home.** The `@service` payload seat computes the carrier's arrival
  cardinality once, from the carrier field's wrapper, and carries it as a typed fact; the shape
  verdict below and every downstream consumer read that fact rather than re-deriving the wrapper
  ("decide once, carry the decision as a type"). `ChildField.source()`'s conservative hard-coded
  `Source.Child` stays, but its javadoc (and the `Source.OnlyChild` / `WrapperAlgebraTest` pin
  comments) are retargeted from "R279 / R308 compute the fold" to **R463**
  (`ancestor-product-arrival-fold`), which now owns consuming R279's ancestor-cardinality rider;
  this item models arrival on the `@service` payload seat only.
- **One classify-time shape verdict** over the full triple (carrier wrapper, producer return
  shape, data field wrapper) replaces the uncoordinated reads as the decision point. The verdict
  is a sealed type in the style of `BuildContext.DmlPayloadScan` but with **typed reject variants
  carrying the disagreeing axes** (and stable LSP codes), not reason strings composed at the
  detection site; `DmlPayloadScan.Reject(String)` is the debt not to replicate. The wrapper and
  reflection reads stay inside `FieldBuilder` / `RecordBindingResolver` / `BuildContext` (the
  permitted classification boundary); only the typed verdict flows downstream.
- **Coherent shapes keep their current classification and emit, now pinned:** list carrier +
  single `@table` data field + `List<Record>` producer; class-backed `List<Payload>` producers.
  No emitter changes.
- **Incoherent shapes reject at classify time** with diagnostics naming the
  carrier-vs-producer(-vs-data-field) cardinality mismatch and the concrete fix. The two silent
  admits above become explicit rejections (parity with the DML twin's decision, not its
  string-return style); the two misleading rejections are re-worded off the same verdict arms.
  Because every incoherent shape rejects before assembly, **no new validator mirror is added**: a
  `dispatchPerformsReFetch` sibling would be a second dispatch set landing directly on R314's
  retirement list (its goal 3 deletes that mirror family in favour of by-construction non-drift).
  If implementation finds a transitional mirror genuinely necessary, it must be registered on
  R314's retirement list in the same commit.
- Out of scope: lifting the DML list rejection (separate future item, unchanged from the original
  body); the general ancestor-product arrival fold and `Source.OnlyChild` population (**R463**);
  any emit re-platforming (the R431 -> R432 -> R314 chain owns that surface; this item is
  classification + validation + tests only).

## Acceptance

- **Pipeline tier:** fixtures for every sub-shape above; the coherent shapes assert the data
  field's `RecordTableField` model (`SourceKey.Cardinality`, `LoaderRegistration.Dispatch`) and
  loader emit structurally (`TypeSpec` assertions, no `code().toString()` body matches); the
  incoherent shapes assert classify-time rejection with the mismatch-naming message and typed
  variant.
- **Execution tier:** a `graphitron-sakila-example` fixture round-trips a list-returning
  `@service` carrier (single `@table` data field, `List<Record>` producer) end-to-end against
  PostgreSQL and asserts the payload data fields resolve through **one batched query**, proving
  the DataLoader coalescing the pipeline tier cannot see. Sits alongside
  `SingleRecordTableFieldServiceProducerExecutionTest`.
- **Corpus tier:** existing rows stay byte-identical. The coherent list-carrier shape joins the
  R281/R299 corpus only if it demonstrates a new dimension tuple; otherwise the rejection
  fixtures live with the `GraphitronSchemaBuilderTest` verdicts / rejection side.
- The three in-code forward references naming R308 for the arrival fold (`ChildField.source()`
  javadoc, `Source.OnlyChild` javadoc, the `WrapperAlgebraTest` pin) point at R463 after this
  item lands.
- Full aggregator green (`mvn install -Plocal-db`), graphitron-lsp included.

## Review 2026-07-12: rework requested (one coverage gap)

Independent In Review pass (session_01KR4kWDyVPexp2oC5aekHCW) over 1481592 + 0803628. The shape
verdict, the typed `ServiceCarrierShapeError` sub-seal, the LSP wiring, the forward-reference
retargets to R463, and the execution-tier two-SELECT batching proof all deliver the spec; the full
reactor is green (unpiped exit 0). One acceptance item did not ship:

- **The coherent class-backed list carrier is unpinned.** "Fixtures for every sub-shape above"
  includes the working shape "class-backed producers returning `List<Payload>` (plain properties,
  and with a `@table` data field)". No fixture at any tier drives a `[Payload]` carrier with a
  `List<Dto>` producer through the verdict's Coherent arm: the pipeline pins cover the `@table`
  data-field coherent shape (`serviceProducer_listCarrier_singleTableDataField_admitsBatchedLoadOne`,
  plus the sakila `serviceFilmsByIdsAsPayloads` execution fixture) and all reject arms, but the only
  class-backed list-carrier test is the *reject* (`listCarrier_classBacked_singleProducer_rejectsProducerArrivalMismatch`).
  This matters beyond completeness: commit 0803628 itself fixed a false *reject* of a coherent list
  carrier that only surfaced because the `@table` variant had a fixture; the class-backed sibling
  has no such tripwire, so a future verdict change could false-reject it with a green build.
  Fix shape: one pipeline test in `ServiceRecordCompositeCarrierPipelineTest` with
  `createFilms: [CreateFilmsPayload]` over `TestServiceStub.createFilmsWithActors`
  (`List<TestFilmWithActorsDto>`, producer arrival MANY -> Coherent), asserting the field classifies
  (`MutationServiceRecordField`), the payload/data-field model is unchanged from the single-carrier
  sibling (`JavaRecordType` / `RecordCompositeField`), and `diagnostics()` is empty. A plain-properties
  variant (no `@table` children) completes the sub-shape pair if a suitable stub exists or is trivial
  to add.

No other rework: rejection coverage, corpus byte-identity, and the R463 retargets are complete.
