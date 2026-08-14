---
id: R667
title: "Capture runs before the classification walk, so the walk can read the store"
status: Spec
bucket: architecture
priority: 3
theme: classification-model
depends-on: [capture-expands-facet-synthesis]
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

The order also means the run traverses its source material twice. Capture transcribes the parsed
registry; the walk then re-traverses the same registry to classify it. After the reorder that second
traversal has no reason to exist: the store already holds what it would read.

## Why a shim, and not draining the leaf model's consumers directly

The alternative to a store-backed shim is to leave the walk alone and re-source each leaf-model
consumer onto the store until nothing reads the leaf model. Measured against the tree, that is not a
shortcut, it is the whole drain:

[cols="2,1"]
|===
| Population | Count

| Main-source files referencing `GraphitronSchema`
| 89 (87 in `graphitron`, 2 in `graphitron-sakila-service`)

| Pattern-match sites over the sealed type hierarchy (`instanceof`/`case GraphitronType.*`)
| 191

| Pattern-match sites over the sealed field hierarchies
| 224
|===

The dispatch is concentrated in the back half of the generator: `CatalogBuilder` (76 sites, the
LSP/MCP completion catalog), `TypeFetcherGenerator` (37), `TypeUnitCommands` (29),
`GraphitronSchemaValidator` (28), `ProjectionCommands` (25), `FetcherEdgeCommands` (23),
`LauncherCommands` (10). Draining those directly *is*
`roadmap/coordinate-lowers-to-datafetcher-queryparts.md`, and it is the slow path by construction.

The shim is worth doing because it inverts the burden of proof. Today the store has two producers
for the same facts, so every relation must be held equal to the walk forever; that is what
`FactCaptureAgreementTest` and the shadow tests exist to do, and the obligation grows with every
relation added. With the leaf model projected from the store there is one producer, and agreement
stops being a standing obligation and becomes a one-time correctness argument. A consumer still
reading the leaf model is then already reading the store transitively, and its own drain becomes an
optimization (remove the projection hop) rather than a prerequisite for anything.

## Why this is tractable

The change is wide, but it is not deep. Five facts in the tree, each checkable, are why.

**1. The classifying visitor is traversal-context-free.** Every callback on
`GraphitronSchemaBuilder.ClassifyingVisitor` takes a `TraverserContext` and ignores it. The bodies
are `typeBuilder.classifyAndRegister(node)`, plus `classifyFieldsOfObject(...)` on the object arm.
The traversal contributes a *set* of nodes and nothing else; no verdict reads a parent edge from the
traverser. Driving classification from a store worklist instead therefore touches no classification
decision.

**2. The store's reach already equals the walk's reach, under test, today.**
`DemandShadowTest`'s first assertion compares `intent_type_domain` against
`SchemaReachability.reachableTypeNames` with `containsExactlyInAnyOrderElementsOf` over every
`ClassifiedCorpus` example. That is plain equality, not a residue-tolerant diff, with exactly one
named subtraction: the walk's own facet verdicts, because capture records the `@asFacet` marker but
does not synthesize the facet types. Closing that hole is this item's one dependency
(`roadmap/capture-expands-facet-synthesis.md`).

**2b. The read surface the walk needs from graphql-java is narrow, and all of it is captured.**
File size is a poor proxy for coupling here. Across `TypeBuilder` and `FieldBuilder` there are 56
`ctx.schema` read sites, and the accessor profile is short: name, type reference and its unwrapping,
field list, applied directives, arguments, description, interface implementations, union members.
Every one of those is a `graphql_` or `graphitron_` relation today. The walk's own javadoc already
states the property that makes this hold: field classification is registry-read-free, because a
field's output target and argument input types are resolved through the look-ahead and fixed-point
indices rather than a registry lookup.

**3. Base capture is already walk-free and already standalone.** `FactCapture.capture(dsl, ...)` is
public and takes a `DSLContext`; `DemandShadowTest` already calls it that way. `FactCapture.run`
passes `domain = null` and skips detection entirely, which is the arm the failure paths take. So
capture-without-the-walk is an exercised path, not a hypothesis.

**4. The gate flip is not a precondition.** The single walk-dependent step is `detect`, five lines:

```java
if (domain == null) return AuthoredClaimConflicts.Detection.empty();
ClaimDomainRows.write(dsl, graph.name(), domain);
return AuthoredClaimConflicts.detect(dsl, graph.name());
```

Because base capture and `detect` are already separate steps, the reorder can move base capture
above the walk and leave `detect` exactly where capture sits today. `ClaimDomain` stays, unchanged
and still walk-derived. Re-sourcing the gate onto the demand relation remains the follow-up
`ClaimDomain`'s own javadoc names, and it is blocked on `DemandResidue`'s two populations
(`reflectionBound`, `embeddingDecided`), which close only when the structural classifier arms
migrate. This item deliberately does not wait for that.

## Scope

Four deliverables, in order.

### 1. Capture runs above the walk

Split the two steps at the pipeline level, not just inside `FactCapture`: base capture moves above
`GraphitronSchemaBuilder.buildBundle`, and `detect` stays ahead of `validateAndLogErrors`, because
the store-backed detections feed the error stream and the LSP path additionally reads
`detection.fieldConflicts()` for its `Conflicted` overlay.

The engineering here is the store-handle lifetime. `FactCapture.runInternal` opens the store in a
try-with-resources and does capture and detect before closing, across four branches: the
already-fell-back-to-memory arm, the `!store.warm() || ownsGraph(...)` arm gated on
`captureWithRetry`, the fall-through, and the final in-memory open. Splitting the steps across the
walk means the handle has to survive the walk. The recommended shape is a callback: `FactCapture`
takes the work to run against the captured store and keeps ownership, preserving the invariant its
javadoc already states ("the store handle never escapes"). The alternative, opening twice, would put
the branch decision at two sites and is how the two arms come to disagree about which store the run
landed in.

Also hoist the LSP path's external references. On `buildOutput()` capture's `extensions` argument
comes from `CatalogBuilder.build(jooq, bundle.assembled(), ctx)`, where the build paths use the
bundle-free `CatalogBuilder.buildExternalReferences(ctx)`. `bundle.assembled()` is the schema handed
*into* `buildBundle` (`read.assembled()`), so the hoist looks cosmetic; confirm rather than assume.

### 2. Reachability belongs to capture, not to the walk

`SchemaReachability` moves into capture and its walk-side form is deleted. Reachability is already a
capture-cadence derivation (`ReachabilityRows` writing `intent_type_domain`); a second Java
computation of the same closure over graphql-java objects is the two-producer anti-pattern in
miniature, and keeping it as a shadow would preserve the very thing this item exists to remove. So
`ReachabilityRows` becomes the sole producer, `SchemaReachability.reachableTypeNames` and
`SchemaReachability.walk` both go, and the seeds (root bindings, `NodeDeclaration` nodehood, `@key`
carriers, survivor directive argument types) live where they are derived rather than in two places
that must be kept equal.

### 3. `GraphitronSchemaBuilder` reads the store and nothing else

The builder loses its `GraphQLSchema` and its `TypeDefinitionRegistry`. Its inputs become the store
and the `RewriteContext`, and it produces the classified model by querying relations. This is the
point of the item rather than an eventual consequence of it: a builder that enumerates relationally
and then reads structure off a `GraphQLSchema` is a worse resting state than either end, because it
adds a store dependency without removing a graphql-java one and leaves the two-producer obligation
fully intact.

Concretely, that means:

* `BuildContext.schema` goes. The narrow read surface measured above is supplied from the store,
  either as a thin reader over `DSLContext` or as store-shaped record inputs, and the 56 read sites
  move onto it.
* The assembled schema stops flowing through `Bundle`. Emitters do read raw type structure off it,
  which is why `Bundle` carries it today, but the pipeline already holds it (`read.assembled()` is
  passed *into* `buildBundle`), so it is handed to the emitters directly instead of laundered through
  the classifier.
* The reductions currently run inside `buildSchema` against `ctx.schema` move out of the builder or
  onto store reads: `ArrivalIndex.compute`, `OperationMemberRelation.compute`,
  `DeliveryFactRelation.compute`, `EntityResolutionBuilder.build`, and
  `recordSdlScalarDirectives`/`validateDirectiveSchema`. `DeliveryFactRelation` is
  `roadmap/delivery-verdict-derives-from-the-store.md`'s subject, so the two items meet here.
* `ConnectionPromoter` is the walk-side twin of `MacroCapture.expandConnections` and retires with the
  schema rebuild it exists to perform. Once capture expands facets too, no synthesis is
  walk-side and there is nothing left for a rebuild to produce.

### 4. The order becomes load-bearing

With the model derived from the store, a build that walks before capturing cannot produce a model at
all. The ordering constraint stops being a comment in `runPipeline` and becomes structural. Add the
test that says so directly, so the reason is legible rather than merely emergent.

## Risks the implementer has to decide, not discover

* **Registration order changes.** The model's `types()` and `fields()` are insertion-ordered maps
  populated in depth-first first-encounter order today, and post-walk folds
  (`MixedSourceReachIndex`, `OperationMemberRelation.compute`, `DeliveryFactRelation.compute`,
  `MappingsConstantNameDedup`) read them in that order. A store-derived worklist has a different
  order. Pick the store's own ordinal, state it, and expect `GeneratorDeterminismTest` plus the
  compile and execution tiers to be the gate. If any emitted output ordering follows registration
  order, that surfaces as a diff, which is the outcome to want.
* **This is a wide diff, and it lands at once.** The builder cannot hold half a schema: as long as
  `BuildContext.schema` exists, every read site is free to use it, so the field's removal is what
  makes the change real and the 56 read sites move together. There is no partial resting state worth
  shipping, which is the honest cost of doing it the right way round rather than in tranches.
* **There is no longer a no-store path.** Today `storeDirectory == null` and the in-memory fallback
  are cost decisions that cannot change verdicts. Once the builder reads the store, a run without a
  store produces no model. The in-memory fallback already covers this, but the consequence has to be
  made explicit rather than inherited.
* **The warm-store arm can skip capture.** `runInternal` runs capture only when
  `!store.warm() || ownsGraph(dsl, graph)`, and otherwise falls through. A walk reading a store that
  this run did not fill would classify against another graph's rows. Resolving this is part of
  deliverable 1, not a detail of it, and it touches the graph-ownership discipline
  `FactCaptureAgreementTest`'s oracle-lifecycle gates pin.

## Out of scope

* **Re-sourcing the conflict detection's gate** onto the demand relation. Blocked on
  `DemandResidue`; needs its own item.
* **Migrating any classification decision onto a view.** The decisions stay in Java; only their
  inputs change. Sourcing a verdict from SQL is a reasonable separate item, and this is what makes
  one possible; picking the first arm belongs to whoever owns the drain sequence.
* **Capture-side facet expansion**, which is `roadmap/capture-expands-facet-synthesis.md` and lands
  first.
* **Draining any leaf-model consumer.** The census above is the argument for projecting the leaf
  model from the store, not work this item takes on.
* **The emitters' reads of the assembled schema.** They keep reading it; this item only stops routing
  it through the classifier.

## Open for the implementer

* The shape of the store read surface: a thin reader over `DSLContext` that the walk queries as it
  goes, or store-shaped record inputs assembled up front and handed in. The first keeps the query
  close to the decision, the second keeps the walk testable without a store.
* Whether the field-grain worklist comes from `intent_resolved_field_demand` or from `graphql_field`
  under the type-grain domain. The resolved relation is the more derived answer but carries the
  demand residues that the type-grain reach comparison does not.
* Whether the callback shape or an explicitly-scoped handle reads better at the `runPipeline` call
  site, given that the pipeline between capture and detect is most of the generator.

## Relationship to items already open

* `roadmap/coordinate-lowers-to-datafetcher-queryparts.md` owns the drain. This item removes a
  constraint on the order that drain can proceed in, so it is infrastructure for the drain rather
  than a slice of it.
* `roadmap/delivery-verdict-derives-from-the-store.md` scopes itself around today's ordering: it
  takes the planning-stage consumers, which sit after capture, and declares the classifier's own
  mint decision out of reach. That boundary is a consequence of this item, not a property of
  delivery, and that item's eligibility section says so. If this lands first, its out-of-scope line
  on the classifier read stops being a hard limit; it still should not absorb it.

## Retired vocabulary

* `SchemaReachability`, whole: both `walk` and `reachableTypeNames`, the `SchemaTraverser` drive, the
  `childrenOf` descent function, the seed scan, and the `ClassifyingVisitor` with its unused
  `TraverserContext` parameters. Reachability's only home becomes `ReachabilityRows`.
* `BuildContext.schema`, and `GraphitronSchemaBuilder`'s `TypeDefinitionRegistry` parameter and
  `AttributedRegistry` overloads.
* `GraphitronSchemaBuilder.Bundle`'s `assembled` component, once the pipeline hands the assembled
  schema to the emitters directly.
* `ConnectionPromoter`, including `rebuildAssembledForConnections`, once `roadmap/capture-expands-facet-synthesis.md` puts facet expansion
  in capture beside `MacroCapture.expandConnections`.

## Coverage

* The reach agreement in `DemandShadowTest` loses its second side. Today it diffs
  `intent_type_domain` against a walk-side traversal; when the traversal is gone there is nothing to
  diff, and the agreement becomes the corpus-wide assertion that the domain relation is the
  population the classifier actually classified. Restate it in those terms rather than deleting it.
* `GeneratorDeterminismTest` plus the compile and execution tiers are the registration-order gate,
  and the broadest signal that a store-sourced read surface returns what the graphql-java one did.
* A pipeline-tier test that the store holds this run's rows before the model exists, so the ordering
  is asserted rather than inferred from the fact that the build passes.
* A test covering the warm-store-not-owned arm, which is the branch where the builder could read rows
  the run did not write.
* The unit-tier classifier tests currently hand-craft a `TypeDefinitionRegistry` through
  `GraphitronSchemaBuilder.build(TypeDefinitionRegistry, RewriteContext)`. That overload retires with
  the registry parameter, so those tests need a store-backed equivalent; whichever seam they get is
  also the seam that answers the read-surface question above.

## Provenance

Asked directly during the delivery-verdict item's Spec pass: why not move `captureFactsAndDetect`
above `buildBundle`. The investigation found no structural obstacle, one isolated coupling, and a
removal criterion already committed to `ClaimDomain`'s javadoc.

The scope was then set by the item's owner: capture runs first, and the classified model is built by
querying the store rather than by a second walk. A census of the leaf model's consumers ruled out the
alternative of re-sourcing them directly, which is the drain itself rather than a way around it. A
first draft of this plan proposed taking only the enumeration onto the store while structure reads
stayed on the assembled schema; the owner rejected that split on the grounds that reachability
belongs to capture and the builder should hold no schema at all. That is the version specified here,
and it is the stronger target: the intermediate state would have added a store dependency without
removing a graphql-java one, and left the two-producer obligation fully intact.
