---
id: R495
title: "Reconcile InputRecordGenerator generated service-reference-audit javadoc with actual enforcement"
status: Backlog
bucket: docs
priority: 6
theme: docs
depends-on: []
created: 2026-07-16
last-updated: 2026-07-16
---

# Reconcile InputRecordGenerator generated service-reference-audit javadoc with actual enforcement

`InputRecordGenerator.buildClassSpec` emits per-input-class javadoc into generated output stating that a build-time audit "enforces this rule" for service-side consumption of the consumer-bean vs `Map.get` path, while the same generator's class-level javadoc says that audit "is deferred as a follow-on". The two contradict, so the "the audit enforces this rule" claim is either stale or was never true. The claim is load-bearing (it tells consumers an enforcement audit exists) and is baked into generated consumer-facing javadoc via a string literal, so neither `RoadmapReferenceGuardTest` nor the R492 reference gate polices it. Establish whether the service-reference audit actually runs, then correct the generated javadoc to match (dropping the transient id per the sibling generated-javadoc cleanup); touching the emitted text needs a golden-output review.

Surfaced by the R483 javadoc drift audit.
