---
id: R667
title: "Capture runs before the classification walk, so the walk can read the store"
status: Backlog
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# Capture runs before the classification walk, so the walk can read the store

## Problem

`runPipeline` runs the classification walk first and capture second:

```
read   = assembleAndCaptureVerdicts(attributed)
bundle = GraphitronSchemaBuilder.buildBundle(attributed, read.assembled(), ctx)
         captureFactsAndDetect(attributed, read.verdicts(), bundle.model())
         validateAndLogErrors(...)
plan   = EmitPlan.produce(schema, ...)
```

That order sets the ceiling on the whole strangler migration. Every consumer that wants to read a
store fact has to sit downstream of the walk, so the walk can only ever be a producer feeding the
store, never a consumer reading it. A classification decision cannot be sourced from a view, however
completely the view is derivable, because at walk time the store is empty. The drain therefore
proceeds one *downstream* consumer at a time, and the walk's own decisions, the largest population
of hand-maintained logic in the reactor, are structurally last in line.

Reversing the order inverts the direction of travel: the walk becomes a consumer of the store like
everything else, and a classification arm can be re-sourced onto a view without waiting for the leaf
zoo to dissolve first.

## Why this is smaller than it looks

Base capture is **already walk-free, on a code path that ships today.** `FactCapture.runInternal`
is two steps on one store handle:

```
capture(store.dsl(), ..., nodes)    // no ClaimDomain parameter
detect(store.dsl(), graph, domain)  // the only walk-dependent step
```

`FactCapture.run` passes `domain = null` and skips `detect` entirely. That is the arm the failure
paths take, and its javadoc states the intent: "The capture with no classified model to gate
detections on ... It writes everything the surviving declarations support plus the stages' verdicts,
which is the whole point of running it here rather than giving up." So capture-without-the-walk is
not a hypothesis to prove; it is an exercised path.

The single coupling is `ClaimDomain`, and `detect` is five lines:

```java
if (domain == null) return AuthoredClaimConflicts.Detection.empty();
ClaimDomainRows.write(dsl, graph.name(), domain);
return AuthoredClaimConflicts.detect(dsl, graph.name());
```

`ClaimDomain.of(schema)` is `schema.types().keySet()` plus `schema.fields().keySet()`, membership
sets and nothing more, reified by `ClaimDomainRows.write` into `walk_claim_domain_type` /
`walk_claim_domain_field` so that `intent_authored_claim_conflict` joins the gate on the store side
rather than in Java.

**The removal criterion is already written down, on `ClaimDomain` itself:** "The gate dissolves when
the detection reads the demand relation instead of the walked model, which is the gate-flip
follow-up's work, not the shadow's." The replacement already exists in the store
(`intent_type_domain`, the demand and exemption rule views, the resolved reductions) and is already
diffed against `ClaimDomain` by `DemandShadowTest`, with `DemandResidue` naming the two populations
the store cannot yet express (`reflectionBound`, `embeddingDecided`).

So this item is not a new investigation. It is the gate-flip follow-up that `ClaimDomain` already
names, plus the reordering that flip unlocks.

## The shape

1. **Split the two steps in the pipeline, not just inside `FactCapture`.** Base capture moves above
   `buildBundle`; `detect` stays where capture is today, ahead of `validateAndLogErrors`, because the
   store-backed detections feed the error stream and the LSP path additionally reads
   `detection.fieldConflicts()` for its `Conflicted` overlay.
2. **Flip the detection's gate onto the demand relation**, which is what lets step 1 drop the
   `ClaimDomain` argument rather than thread it forward. Gated on the demand shadow's residues, per
   the criterion above; this is the item's real precondition and the reason it is not a mechanical
   reorder.
3. **Resolve the store-handle lifetime.** `runInternal` opens the store in a try-with-resources and
   does capture and detect before closing, with `captureWithRetry`'s retry-once-on-concurrency and
   the warm / in-memory-fallback branching inside that block. Splitting the steps across the
   classification walk means either holding the handle open across it or opening twice. This is the
   actual engineering, and it touches the graph-ownership discipline (`ownsGraph`, per-graph
   partition clearing) that `FactCaptureAgreementTest`'s oracle-lifecycle gates pin.
4. **Hoist the LSP path's external references.** On `buildOutput()` capture's `extensions` argument
   comes from `CatalogBuilder.build(jooq, bundle.assembled(), ctx)`, so the LSP path reaches capture
   through a bundle-derived value where the build paths use the bundle-free
   `CatalogBuilder.buildExternalReferences(ctx)`. `bundle.assembled()` is the schema that was handed
   *into* `buildBundle` (`read.assembled()`), so the hoist looks cosmetic; confirm rather than
   assume.

## What it unlocks, and what it does not

Unlocks: a classification arm can read a view. That is the precondition for re-sourcing walk
decisions onto the store at all, and it is why this item is plausibly higher leverage than any single
axis being drained through it.

Does not unlock: anything automatically. This item makes store reads *possible* at walk time; each
arm still migrates on its own evidence, and the leaf zoo still dissolves under
`roadmap/coordinate-lowers-to-datafetcher-queryparts.md`. The value is removing a structural ceiling,
not landing a migration.

Explicitly out of scope: migrating any classification arm onto a view. Doing one as a proof would be
a reasonable separate item, and picking the first arm is a decision for whoever owns the drain
sequence.

## Relationship to items already open

* `roadmap/coordinate-lowers-to-datafetcher-queryparts.md` (R333) owns the drain. This item removes
  a constraint on the order that drain can proceed in, so it is infrastructure for R333 rather than
  a slice of it.
* `roadmap/delivery-verdict-derives-from-the-store.md` (R666) scopes itself around today's ordering:
  it takes the planning-stage consumers, which sit after capture, and declares the classifier's own
  mint decision out of reach. That boundary is a consequence of this item, not a property of
  delivery, and R666's eligibility section says so. If this lands first, R666's out-of-scope line on
  the classifier read stops being a hard limit; R666 still should not absorb it.

## Provenance

Asked directly during R666's Spec pass: why not move `captureFactsAndDetect` above `buildBundle`.
The investigation found no structural obstacle, one isolated coupling, and a removal criterion
already committed to `ClaimDomain`'s javadoc, which made it worth its own item rather than a note.
