---
id: R402
title: "Retire the ValueShape to synthetic CallSiteExtraction.InputBean round-trip in the bean-helper queue"
status: Backlog
bucket: structural
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-06-30
last-updated: 2026-06-30
---

# Retire the ValueShape to synthetic CallSiteExtraction.InputBean round-trip in the bean-helper queue

## Problem

Carved out of R256 (`service-walker-substrate-absorption`), whose deliverable 4 named two
transitional-cruft cleanups. R256 landed the first (widening `ConflictSite.site` to a sealed
`MethodRef | ServiceMethodCall` and retiring `ContextArgumentClassifier.syntheticServiceMethodRef`)
and explicitly authorised splitting out this second, more opaque one if it grew.

`TypeFetcherGenerator`'s per-`*Fetchers`-class bean-helper queue (the `create<Bean>` /
`create<Bean>List` instantiation helpers, plus the jOOQ-record and `@nodeId`-record decode helpers)
is fed from two coordinates: the legacy `MethodBackedField.method().callParams()` walk, which carries
`CallSiteExtraction.InputBean` arms directly, and the R238 `ServiceField` carrier walk
(`collectBeanHelpersFromCarrier`), which walks the carrier's `ValueShape` composites
(`RecordInput` / `JavaBeanInput` / `ListOf` / `JooqRecordInput`) and **re-wraps each as a synthetic
`CallSiteExtraction.InputBean`** (`registerBeanHelper` / `leafForFieldBinding` /
`convertNestedFieldBindings` / `innerElementTypeNameOf`) so the existing
`InputBeanInstantiationEmitter` — which only consumes `CallSiteExtraction.InputBean` — can drive off
the same dedup map.

The synthetic round-trip is the cruft: a `ValueShape` is the carrier's own composite model, and
re-encoding it back into the upstream classifier's `CallSiteExtraction.InputBean` shape just to reach
the emitter means two parallel composite encodings are kept structurally in sync by hand
(`registerBeanHelper`'s field-binding reconstruction mirrors what `CallSiteExtraction.InputBean`
carries). The emitter should consume the `ValueShape` directly.

## Sketch

Rework `InputBeanInstantiationEmitter` (and the jOOQ-record / record-decode helper emitters) to accept
the `ValueShape` composite as their input shape, then drop `collectBeanHelpersFromCarrier`'s synthetic
`CallSiteExtraction.InputBean` construction in favour of feeding `ValueShape` straight through. The
legacy `MethodBackedField.callParams()` walk either keeps its `CallSiteExtraction.InputBean` feed
(with a thin adapter at the emitter boundary) or is migrated in the same change; confirm during Spec
which is cleaner. Dedup stays keyed on the bean `ClassName`. The compilation tier
(`graphitron-sakila-example`) and the existing `@service`-bean fixtures are the regression backstop —
the emitted helper bodies must be byte-identical across the cutover.

## Out of scope

The R238 `MethodRef.Service` → `ServiceMethodCall` double model itself (R256's out-of-scope note); this
item only removes the duplicate composite *encoding* at the bean-helper queue, not the carrier model.
