---
id: R529
title: "Reject multi-hop and condition-join @tableMethod paths at build time instead of emitting a runtime throw"
status: Backlog
bucket: correctness
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-07-24
last-updated: 2026-07-24
---

# Reject multi-hop and condition-join @tableMethod paths at build time instead of emitting a runtime throw

`TypeFetcherGenerator`'s `@tableMethod` parent-correlation path has an `unsupportedPath` block that, for a classifier-recognised sub-shape (a multi-hop or condition-joined `@tableMethod` join path), emits a fetcher body throwing `UnsupportedOperationException` at runtime, justified by a comment that this "keeps the failure mode loud and pointable". Per "Rejections: validator mirrors classifier invariants" (`docs/architecture/explanation/development-principles.adoc`), a shape the classifier can recognise must be rejected at build time with a located author-facing diagnostic, not shipped as generated code that detonates on first execution; `TypeFetcherGenerator#STUBBED_VARIANTS` cannot express the gap because it is keyed on leaf class identity and this is a sub-shape of an otherwise-supported variant. Lift the recognition into a classify/validate-time rejection (or a typed deferred verdict) so the emitter arm and its runtime throw disappear. Routed from the R526 investigation (the adjacent comment correction must not leave the impression the site is clean).
