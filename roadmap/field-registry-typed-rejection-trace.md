---
id: R209
title: "FieldRegistry classify-input trace loses typed Rejection payload"
status: Backlog
theme: diagnostics
bucket: Typed rejection chain
depends-on: []
created: 2026-05-21
last-updated: 2026-07-15
---

# FieldRegistry classify-input trace loses typed Rejection payload

`FieldRegistry.classifyInput` at `graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldRegistry.java:108-110` emits the trace record for an `InputFieldResolution.Unresolved` outcome by defaulting to `RejectionKind.AUTHOR_ERROR` with `u.reason()` (a String), with the rationale comment "Unresolved carries no Rejection variant ... default to AUTHOR_ERROR per the kind-of-thumb rule". This is the last place in the input-classification path where the typed-rejection chain breaks: the trace consumers (watch-mode formatter, LSP fix-its) lose the structured `attempt + candidates` payload they would otherwise consume on a column-miss `Unresolved`, and on non-column-miss `Unresolved` they get an `AUTHOR_ERROR` label that may not match the actual rejection kind. R205 closed the gap one layer up (`InputFieldResolver.resolve` now lifts to typed `Rejection.unknownColumn` / `Rejection.structural`); the corresponding lift inside `FieldRegistry.classifyInput` was flagged in the R205 self-review and deferred. Two design forks worth thinking through during Spec: (a) widen `InputFieldResolution.Unresolved` to carry a `Rejection` (touches every Unresolved construction site in the classifier; some sites lack catalog/candidates context to build `unknownColumn`), or (b) thread `TableRef rt` into `FieldRegistry.classifyInput` and lift to `Rejection` there. (a) keeps the lift co-located with classification; (b) keeps `Unresolved` transient by design. Either way the deliverable is removing the `RejectionKind.AUTHOR_ERROR` default arm and emitting `RejectionKind.of(rejection)` consistently with `traceOutput` at `FieldRegistry.java:127-130`.

Cross-reference (2026-07-15): fork (a) is verbatim R66's Phase A2 (`rejection-string-carrier-widening`, item 2: widen `InputFieldResolution.Unresolved.reason: String` to `rejection: Rejection`). If R66 A2 ships first this item collapses to the consumer-side update (remove the default arm, emit `RejectionKind.of(rejection)`); if this item ships first it carries A2's record change. R213 (`input-field-rejection-attribution`) grows the same record a `SourceLocation`; co-design so the record widening happens once. Prefer fork (a) sequenced against R66 A2 over duplicating the record change here.
