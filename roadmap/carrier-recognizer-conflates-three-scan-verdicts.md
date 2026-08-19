---
id: R725
title: "The carrier recognizer publishes one scan verdict for a payload three scans judge differently"
status: Backlog
bucket: architecture
priority: 6
theme: classification-model
depends-on: [dml-carrier-errors-field-blocks-return-derived-table]
created: 2026-08-19
last-updated: 2026-08-19
---

# The carrier recognizer publishes one scan verdict for a payload three scans judge differently

`TypeBuilder.carrierBinding` asks three structural scans in sequence whether an SDL payload type
is carrier-shaped: `BuildContext.scanStructuralDmlPayload`, then
`scanStructuralRoutineCarrierPayload`, then `scanStructuralServiceCarrierPayload`. It returns the
first one that both admits *and* has a grounded producer binding. What it cannot express is which
scans admitted, so a consumer that needs one specific family's verdict has to go ask that scan
again itself.

The three scans are not interchangeable. They share a structural walk but differ on which
directives are forbidden on the payload's data field: `@splitQuery` disqualifies a DML carrier's
data field and is merely redundant on an `@service` one. So for one payload the DML scan can admit
where the service scan rejects, and the reverse. "The scan admitted" is therefore not a single
fact about a payload; it is three facts, and the recognizer publishes at most one of them.

The consumer that already pays for this is the orphan-`@service`-carrier diagnostic in
`FieldBuilder`'s `@service` mutation arm (the guard behind `orphanServiceCarrierReason`). It needs
the *service* scan's `Admit`, both to decide whether to fire and to name the offending data field
through `Admit.dataField()`. Because the recognizer cannot hand it that, it re-spells the
predicate: `carrierBinding(...) instanceof NotACarrier` **and** a fresh
`scanStructuralServiceCarrierPayload` probe. That is one derivation with two spellings, the smell
the recognizer exists to remove, and it is the arrangement the tree ships today.

A second consumer is arriving. The DML-carrier grounding fix adds an ungrounded-carrier arm to
`CarrierBinding` so `MutationInputResolver.validateReturnType` can fork its wording on why a
structurally well-formed payload never earned a carrier verdict, instead of probing the scan a
fourth time. That arm records which scan admitted alongside the scan result, which is enough for
its own DML seat but deliberately does not subsume the `@service` site: for a `@service` payload
the DML scan runs first and can admit, so a "which scan admitted" field reports DML for a payload
the `@service` site is asking about. Folding the two together was considered during that item's
Spec review and left out on purpose, because it needs the design decision below rather than a
mechanical edit.

## The question to answer

How should a multi-family recognizer publish per-family facts? Candidate shapes, none chosen:

* The arm carries every admitting scan's result, keyed by family, and each consumer reads its own.
* `carrierBinding` gains a family parameter, so a consumer asks the question it actually has.
* The per-family scan verdict becomes a separate published fact from the carrier binding, and
  `CarrierBinding` keeps answering only the coarse question.

Whichever shape wins, the payoff is that the `@service` site drops its second spelling and the
next family added to the recognizer inherits the fact instead of re-probing.

## Cost of doing it

The `@service` orphan message is user-facing and pinned in
`SingleRecordTableFieldServiceProducerPipelineTest` and `GraphitronSchemaBuilderTest`. The
conversion should leave the message text byte-identical, so those pins are the regression net
rather than something to rewrite. `CarrierFamily` is currently private to `BuildContext`; any shape
that names a family in a published fact has to widen it or introduce a public equivalent.

Nothing rots while this waits. The ungrounded-carrier arm is a sealed subtype of `NotACarrier`, so
the `@service` site's `instanceof NotACarrier` guard keeps matching and keeps working untouched.

