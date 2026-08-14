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
stops being a standing obligation and becomes a one-time correctness argument per tranche. A
consumer still reading the leaf model is then already reading the store transitively, and its own
drain becomes an optimization (remove the projection hop) rather than a prerequisite for anything.

## Why this is smaller than it looks

Four facts in the tree, each checkable, make the first slice cheap.

**1. The classifying visitor is traversal-context-free.** Every callback on
`GraphitronSchemaBuilder.ClassifyingVisitor` takes a `TraverserContext` and ignores it. The bodies
are `typeBuilder.classifyAndRegister(node)`, plus `classifyFieldsOfObject(...)` on the object arm.
The traversal contributes a *set* of nodes and nothing else; no verdict reads a parent edge from the
traverser. Replacing the traversal with a store-derived worklist therefore touches no classification
decision.

**2. The store's reach already equals the walk's reach, under test, today.**
`DemandShadowTest`'s first assertion compares `intent_type_domain` against
`SchemaReachability.reachableTypeNames` with `containsExactlyInAnyOrderElementsOf` over every
`ClassifiedCorpus` example. That is plain equality, not a residue-tolerant diff, with exactly one
named subtraction: the walk's own facet verdicts, because capture records the `@asFacet` marker but
does not synthesize the facet types. So the population the worklist would iterate is already pinned
against the population the traversal produces.

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

Three deliverables, in order.

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

### 2. The walk's enumeration comes from the store

`SchemaReachability.walk` stops driving a `SchemaTraverser`. The classification worklist becomes a
store query, and the walk iterates it: for each name the store reports in the classification domain,
dispatch on the captured kind to the same `classifyAndRegister` / `classifyFieldsOfObject` calls the
visitor makes today, resolving the `GraphQLObjectType` / `GraphQLInterfaceType` / `GraphQLUnionType`
off the already-assembled schema.

This is the shim's first tranche, and the reason it is the right first tranche: it is the part of the
walk that is literally a second traversal, it is provably equal to what the store already holds, and
it is the part with no classification content. Reads of per-node *structure* (field types, wrapping,
directives) stay on the assembled schema; moving those is a later tranche.

The declared boundary is walk-side synthesis. Connection promotion and facet synthesis mint types
that are not in the authored SDL and therefore not in the store, so they stay walk-side and layer on
top of the store-derived worklist exactly as they layer on the traversal today. That is the same
subtraction the reach agreement already names, so the boundary is stated by an existing test rather
than by prose.

### 3. The order becomes load-bearing

With the worklist coming from the store, a build that walks before capturing cannot produce a model
at all. The ordering constraint stops being a comment in `runPipeline` and becomes structural. Add
the test that says so directly, so the reason is legible rather than merely emergent.

## Risks the implementer has to decide, not discover

* **Registration order changes.** The model's `types()` and `fields()` are insertion-ordered maps
  populated in depth-first first-encounter order today, and post-walk folds
  (`MixedSourceReachIndex`, `OperationMemberRelation.compute`, `DeliveryFactRelation.compute`,
  `MappingsConstantNameDedup`) read them in that order. A store-derived worklist has a different
  order. Pick the store's own ordinal, state it, and expect `GeneratorDeterminismTest` plus the
  compile and execution tiers to be the gate. If any emitted output ordering follows registration
  order, that surfaces as a diff, which is the outcome to want.
* **There is no longer a no-store path.** Today `storeDirectory == null` and the in-memory fallback
  are cost decisions that cannot change verdicts. Once the walk reads the store, a run without a
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
* **Migrating any classification decision onto a view.** The decisions stay in Java, fed by store
  rows. Doing one as a proof is a reasonable separate item; picking the first arm belongs to whoever
  owns the drain sequence.
* **Moving the walk's per-node structure reads** (field types, list wrapping, directive decoding)
  off the assembled schema. That is the shim's second tranche.
* **Capture-side synthesis expansion** (connection and facet types), which is what would close the
  last enumeration hole and retire the reach agreement's named subtraction.
* **Draining any leaf-model consumer.** The census above is the argument for the shim, not work this
  item takes on.

## Open for the implementer

* Whether tranche 1 moves the field-grain enumeration too or only the type grain. The field grain
  has a resolved store relation already (`intent_resolved_field_demand`), but it carries the demand
  residues that the type-grain reach comparison does not, so type-grain-only is the conservative
  read and field-grain is a judgement call on the evidence.
* Whether `SchemaReachability.reachableTypeNames` survives as the shadow side of the reach agreement
  or is deleted along with `walk`. Keeping it costs a traversal per test run and buys a second
  opinion for one release; deleting it makes the store the only answer immediately.
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

* `SchemaReachability.walk`, and the traverser drive behind it (the `SchemaTraverser`, the
  `childrenOf` descent function, and the `ClassifyingVisitor`'s unused `TraverserContext`
  parameters), if deliverable 2 lands as written.
* `reachableTypeNames`, conditionally, per the open question above.

## Coverage

* The reach agreement in `DemandShadowTest` changes character: today it pins a shadow relation
  against the production traversal, and after this it pins the production worklist. Keep the
  assertion and say so at the call site.
* `GeneratorDeterminismTest` plus the compile and execution tiers are the registration-order gate.
* A pipeline-tier test that the store holds this run's rows before the model exists, so the ordering
  is asserted rather than inferred from the fact that the build passes.
* A test covering the warm-store-not-owned arm, which is the branch where a walk could read rows the
  run did not write.

## Provenance

Asked directly during the delivery-verdict item's Spec pass: why not move `captureFactsAndDetect`
above `buildBundle`. The investigation found no structural obstacle, one isolated coupling, and a
removal criterion already committed to `ClaimDomain`'s javadoc. The scope was then set by the item's
owner to include the leaf shim, and narrowed to its first tranche after a census of the leaf model's
consumers showed that draining them directly is the drain itself rather than an alternative to it.
