---
id: R286
title: "Allow @key(resolvable: false) on non-table-bound types (reference-only federation entity stubs)"
status: In Review
bucket: bug
depends-on: []
created: 2026-06-09
last-updated: 2026-06-09
---

# Allow @key(resolvable: false) on non-table-bound types (reference-only federation entity stubs)

`EntityResolutionBuilder.build()` rejects any `@key`-bearing type that is not classified as `TableType`/`NodeType` with the R176 diagnostic `@key on type '<T>' requires a table-bound type, but '<T>' is classified as <kind> — federation entities need a @table directive.` That rule is correct only for *resolvable* keys: the dispatcher SELECTs from the backing table. A federation `@key(fields: ..., resolvable: false)` is a **reference-only entity stub** — the subgraph declares the type for the supergraph composer but does not own its resolution, emits no `_entities` handler, and so needs no backing table. The dispatcher already honours this (`EntityFetcherDispatchClassGenerator` skips non-resolvable alternatives), and `KeyNodeSynthesiser` already documents the same contract for `@node` types ("`resolvable: false` keeps it out of `_Entity`"). The bug: a consumer who writes a reference-only stub on a `@record` type (or any non-table-bound type) hits a hard author error even though nothing needs a table. Concrete failing case:

```graphql
type URegOrganisasjon @key(fields: "id", resolvable: false)
    @record(record: {className: "no.sikt.fs.opptak.opptak.records.URegOrganisasjonId"}) {
    id: ID! @field(name: "id")
}
```

**Fix (as shipped).** In the non-table-bound branch of `EntityResolutionBuilder.build()`'s **second loop** (the classified-type loop), when **every** `@key` directive on the type is `resolvable: false`, skip the type: no demote, no `EntityResolution`. There is nothing to resolve and no table to require. When at least one key is resolvable, the table requirement stays and the R176 diagnostic still fires. The decision turns on the federation `resolvable` flag alone, never on `@record` (or any other) classification. A reference-only stub the consumer actually uses is *reachable* (some local field returns it), so it is classified and present in the registry and hits this loop; it then rides the ordinary classified-type emission path into the served `_service.sdl` carrying its `@key(... resolvable: false)`.

Shipped at `12a9f88` (core change + unit tests); reachability/SDL-emission pinned end-to-end at `77362d3` (execution-tier `FederationBuildSmokeTest`).

**Out of scope.**

- **The R276 first-loop (orphan) case.** A `@key` object type the type pass left *absent from the registry* (no local field references it) keeps its existing R276 rejection. An earlier pass (`01696e1`) tried to also relax this loop on the theory that reference stubs are typically orphans; that was reverted at `77362d3`. The theory was wrong on two counts: (1) a reference stub the subgraph uses is reachable and goes through the second loop; (2) even if left untouched, an orphan absent from the registry is never emitted to the runtime `_service.sdl` — `GraphitronSchemaClassGenerator.planFor` builds the runtime schema from `GraphitronSchema#types()` (the registry), *"the assembled schema is no longer consulted here"*, so the relaxation only suppressed the error without surfacing the type to the composer. If a genuine absent-from-registry reference stub ever needs to reach the composer SDL, that is its own design problem (how an unregistered type enters runtime emission; interacts with R247's codegen-artifact-vs-runtime SDL divergence) and earns its own item.
- Runtime `_Entity` union membership and federation-composition behaviour are owned by federation-jvm's `Federation.transform`, which honours `resolvable: false`; no change needed there.

**Coverage.**

- Unit (`EntityResolutionBuilderTest`, full classification pipeline via `TestSchemaHelper`): `resolvableFalseKeyOnRecordType_isAcceptedAsReferenceOnlyStub` (accept path, no demote, no `EntityResolution`); `mixedResolvableAndNonResolvableKeysOnRecordType_stillRejects` (one resolvable key among many still rejects with the R176 diagnostic).
- Execution (`graphitron-sakila-example` `FederationBuildSmokeTest.serviceSdlExposesNonTableBoundResolvableFalseStub`): the federated fixture gains `FilmRefStub`, a non-table-bound service-bound record carrier with `@key(fields: "filmId", resolvable: false)`, reachable via `Query.filmRefStubs`. Asserts the type and its `@key(... resolvable: false)` reach the served `{ _service { sdl } }`. The existing `resultEntityUnionContainsAllFixtureEntities` (exact `_Entity` membership) was updated to expect `FilmRefStub`: `Federation.transform` includes every `@key` type in `_Entity` regardless of resolvability (the table-bound `Language` stub already behaves this way), and `resolvable: false` in the served SDL is what tells the composer not to route entity queries here, so the membership is benign. The full build (including `FederationEntitiesDispatchTest`) is green with the stub present, confirming a resolution-less `_Entity` member does not break dispatch construction.
