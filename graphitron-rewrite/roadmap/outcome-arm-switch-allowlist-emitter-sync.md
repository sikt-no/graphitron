---
id: R270
title: "Reconcile Outcome arm-switch allow-list with emitter capability for service/tableMethod child variants"
status: Backlog
bucket: structural
depends-on: []
created: 2026-06-01
last-updated: 2026-06-01
---

# Reconcile Outcome arm-switch allow-list with emitter capability for service/tableMethod child variants

R244's arm-switch allow-list `GraphitronSchemaValidator.OUTCOME_TYPE_ARM_SWITCHED_DATA_CHANNEL_VARIANTS` (lines 1143-1153) lists nine data-channel variants, but the emitter `FetcherEmitter.armSwitchValueExpr` (lines 88-115) only implements four (`PropertyField`, `RecordField`, `ConstructorField`, `NestingField`) and throws `IllegalStateException` on the rest. Four allow-listed variants have no emitter site: `ServiceTableField`, `ServiceRecordField`, `TableMethodField`, `RecordTableMethodField`. A schema with one of those as a data-channel sibling of a `WrapperArm` errors field under a flipped `@service` outcome type would pass `validateOutcomeChildArmSwitch` and then crash code generation at `FetcherEmitter.java:111`, violating R244's own lockstep invariant ("Adding a variant to this set requires the matching emitter site to arm-switch"). R244's commit-3b inventory found no in-scope fixture exercising these, so the gap is latent. Scope: bring allow-list and emitter into sync, either by implementing the four nested-method arm-switch emitter sites (parent-key read off `success.value()` then method/loader dispatch, `null` on the `ErrorList` arm) or by narrowing the allow-list to the implemented set and letting the validator reject the unimplemented variants with a clear message until each is supported. Decide direction at Spec; pin whichever with a fixture. Sibling of R268 (which adds the `RecordTableField` / `RecordLookupTableField` DataLoader variants in lockstep) and R244 (which owns the allow-list).
