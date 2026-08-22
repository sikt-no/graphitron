---
id: R537
title: "Pin the DefaultRead-under-Outcome validate-time rejection with a named test"
status: Backlog
bucket: cleanup
priority: 5
theme: classification-model
depends-on: []
created: 2026-07-25
last-updated: 2026-07-25
---

# Pin the DefaultRead-under-Outcome validate-time rejection with a named test

`FetcherEmitter.resolvesViaPropertyDataFetcher` answers true for two dispositions: an
`ErrorsField` on the `PayloadAccessor` transport, and a `ChildField.RecordReadField` whose
`ValueLocator` is `DefaultRead`. The first is pinned by `FetcherPipelineTest` wiring assertions
(its own javadoc says so); the second is not pinned anywhere. The validator consumes the
predicate in `GraphitronSchemaValidator.validateOutcomeChildArmSwitch` to reject a `WrapperArm`
errors transport whose sibling data field would read a property off the `Outcome` source object
instead of unwrapping `Success`, and `OutcomeTypeValidationTest` covers only the
negative direction (`outcomePayloadWithTableDataField_isNotRejected`): no test asserts that the
`DefaultRead` sibling actually rejects. Two claims rest on that unpinned chain. First,
`FetcherEmitter.inlineSuccessRead`'s `DefaultRead` arm throws and documents itself as
unreachable *because* the validator rejects the combination first; nothing fails if the
rejection stops firing, and the failure mode is a generator `IllegalStateException` instead of
a located build error. Second, `isInlineArmSwitchedDataField` excludes `DefaultRead` for the
same reason, also unpinned. Per "Every invariant has an enforcer" in
`docs/architecture/principles/development-principles.adoc`, a claimed pin with no named test
is the review-only label the axiom warns about. Reachability is worth settling as part of the
item: `DefaultRead` arises for `@error`-type parents (which carry no `WrapperArm` errors field,
so not that route) and for class-backed parents whose backing class did not load, so the live
population is a payload result type with an unresolvable backing class. If the combination is
genuinely unreachable, the honest fix is to pin *that* rather than the rejection, and to say so
where the arm claims unreachability.

Filed from the R51 In Review -> Done review; the extension of
`resolvesViaPropertyDataFetcher` was an implementer addition beyond that item's spec, whose
acceptance bar deliberately scoped out per-locator-arm tests.
