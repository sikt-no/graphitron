---
id: R532
title: "Route surfaceMultiProducerRejections through bindingRejectionVerdict"
status: Backlog
bucket: cleanup
priority: 5
theme: classification-model
depends-on: []
created: 2026-07-24
last-updated: 2026-07-24
---

# Route surfaceMultiProducerRejections through bindingRejectionVerdict

`TypeBuilder.bindingRejectionVerdict` documents itself as "the single producer of the
rejection-first precedence", and the walk's equals-idempotent re-register of a seeded
multi-producer demotion is claimed to hold "by construction rather than by mirrored bodies
staying in sync". The consumers (`lookAheadVerdict` via `classifyAndRegister`, and
`participantClassification`) do route through it, but the seed site itself,
`TypeBuilder.surfaceMultiProducerRejections`, still constructs the
`UnclassifiedType(name, location, rejection)` demotion inline: the same kind scoping
(object / input object), the same `locationOf` dispatch, the same rejection payload,
duplicated as a mirrored body. Equality holds today and is pinned by
`R96RecordBindingPipelineTest.multiProducerInput_reachableThroughTheWalk_keepsTypedRejection`
(drift would fire `TypeRegistry.register`'s incompatible-classes demote arm and clobber the
typed payload, failing that test), so there is no behavioral defect; but the javadoc's
"by construction" claim is only true among the consumers, and the next edit to the seed's
scoping or location computation must be caught by the test rather than being unrepresentable.
The fix is small: have `surfaceMultiProducerRejections` call `bindingRejectionVerdict` per
declared type and register the non-null results, deleting the duplicated construction so the
producer genuinely is single and the javadoc is accurate as written.
