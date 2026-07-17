---
id: R484
title: "Drop the transient roadmap slug from the deferred-rejection channel and message literals"
status: Spec
bucket: cleanup
priority: 14
theme: diagnostics
depends-on: []
created: 2026-07-15
last-updated: 2026-07-17
---

# Drop the transient roadmap slug from the deferred-rejection channel and message literals

## What this is (revised from the Backlog framing)

The Backlog stub framed this as purging roadmap-slug citations from a handful of rejection/deprecation string literals, a sibling to R482 (comment/javadoc purge) in a habitat R482's comment-only lexical guard deliberately skips. Investigation showed the dominant case is not stray literals but a designed typed channel.

`Rejection.Deferred` (`graphitron/.../rewrite/model/Rejection.java`) carries a typed `planSlug` field, and its `message()` renders `summary + " — see roadmap/" + planSlug + ".md"`. So every deferred rejection is built to surface a transient `roadmap/<slug>.md` path on the build-time log surface `GraphitronSchemaValidator` writes to whoever runs the generator. That reader is the SDL author, a graphitron consumer with no `roadmap/` directory, for whom the slug is unreadable and rots the moment the deferred item ships, is renumbered, or is discarded. About 25 `Rejection.deferred(...)` call sites hand-write a slug into this channel; two `GraphitronSchemaValidator` sites bypass the channel and inline the path into the summary prose with an empty `planSlug`; three synthesis-shim deprecation `LOG.warn` diagnostics (not `Rejection` values) point the author at `roadmap/retire-synthesis-shims.md` and kin.

## Decision: drop `planSlug`, do not merely hide it

Two shapes were considered: (a) keep `planSlug` as internal metadata but stop rendering it, or (b) drop the field. The field has no production consumer: only an internal thread in `FieldBuilder`'s DML-pair classifier carries it forward to the render, plus test assertions. The javadoc claim that "LSP fix-its read the slug as a typed value and offer 'open the roadmap item'" is a false invariant; no LSP code reads `.planSlug()`. Retaining an unconsumed, unenforced transient pointer is precisely the drift smell this sweep exists to remove: a hand-declared marker nothing binds to the base facts it should track. "Earning" it (landing an LSP/watch consumer) would bank the rot liability rather than remove it, since the 25 slug literals still point at files deleted when items ship.

So `planSlug` is dropped. `stubKey` (the load-bearing `VariantClass` anchor the generator's `STUBBED_VARIANTS` stubbing uses) stays: it is a live class reference, not a slug, so it does not rot the same way. `Deferred.message()` becomes a pure function of `summary`, which structurally cannot render a roadmap path. This serves the project principle that rejections are facts rendered into views, never prose composed at the detection site (`docs/architecture/explanation/typed-rejection.adoc`).

## Scope

- **Model.** Remove `planSlug` from `Rejection.Deferred`; collapse the `deferred(summary, slug)` and `deferred(summary, slug, class)` factories to `deferred(summary)` and `deferred(summary, class)`; `message()` returns `summary`. Remove the false LSP-fix-it javadoc claim and the `planSlug` field doc. The em-dash separator disappears with the suffix.
- **Call sites.** The ~25 `Rejection.deferred(...)` sites drop the slug argument; each summary must stand alone as a fact once the suffix is gone (most already say "... is not yet supported"). Remove the `FieldBuilder` DML-pair slug-threading (`PairVerdict.Deferred(planSlug)`, the `deferredSlug` fold).
- **Channel-bypass sites.** The two `GraphitronSchemaValidator` `@service`/`@externalField` `@reference`-path deferrals collapse to a plain summary with no path.
- **Deprecation warnings.** The three synthesis-shim `LOG.warn` sites (`FieldBuilder`, `BuildContext` x2) state the replacement action ("declare `@nodeId` explicitly; the shim will be removed in a future release") with no roadmap path. These are not `Rejection` values and the build proceeds; lifting them into a typed deprecation channel is out of scope.
- **Tests.** Rework the assertions that pin the transient slug or the rendered suffix (`RejectionRenderingTest.deferredWithSlugAppendsRoadmapPath`, `ColumnReferenceFieldValidationTest`, `GraphitronSchemaBuilderTest` two sites, `MultiTablePolymorphicParentHoldsFkPipelineTest` three sites). Where a test needs to discriminate which deferral fired, it asserts on `stubKey` or `summary`, not a slug.
- **Docs.** In `typed-rejection.adoc`, remove the "LSP fix-its ... open the roadmap item" line and the `planSlug` description. While in that paragraph, the chapter still names a `StubKey.EmitBlock` arm that no longer exists in the sealed permit list (only `VariantClass` remains); correct that stale sentence in the same edit.

## Enforcement: leave an enforcer behind

Primary pin is **structural**: with `planSlug` gone, `Deferred.message()` is a pure function of `summary` and cannot leak a roadmap path; the compiler carries it. Backstop is a **cross-arm render-guard test** asserting that no `Rejection` sealed leaf's `message()` render contains the substring `roadmap/`, built over representative instances of every arm. That backstop catches a future arm that composes a path into prose, which the structural fix for `Deferred` alone does not cover. A test scanning a view's rendered prose is a build-time guard, not a consumer re-parsing text, so it does not reintroduce the anti-pattern the typed-rejection design avoids; it lives in exactly the string-literal-render habitat R482's comment-only guard skips.

## Done

- `planSlug` removed from the model and every call site; `message()` renders `summary` only.
- The five channel-bypass and deprecation sites state facts or replacement actions with no roadmap path.
- The cross-arm render-guard test is green; the structural pin holds.
- `typed-rejection.adoc` reconciled (LSP claim and `planSlug` removed, `StubKey.EmitBlock` sentence corrected).
- Full `mvn install -Plocal-db` green. The change is log-surface only: no generated source shifts, so `compile-spec`/`execute-spec` are unaffected.

## Out of scope

- Lifting the deprecation `LOG.warn` diagnostics into a typed deprecation channel.
- Whether deferral anchors more broadly should be non-transient; `stubKey` is a live class reference and does not rot the way a slug does.
