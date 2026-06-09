---
id: R286
title: "Allow @key(resolvable: false) on non-table-bound types (reference-only federation entity stubs)"
status: Ready
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

**Fix.** `EntityResolutionBuilder.build()` rejects the stub in *two* places, and both need the guard. The decision turns on the federation `resolvable` flag alone, never on `@record` (or any other) classification:

1. **Second loop** (the type is classified, e.g. a `@record` type or a bare `NestingField` plain object): in the non-table-bound branch, when **every** `@key` is `resolvable: false`, skip the type (no demote, no `EntityResolution`). When at least one key is resolvable, the table requirement stays and the R176 diagnostic still fires.
2. **First loop** (R276; the type is *absent from the registry* — the common case for a federation reference stub, which is an orphan in this subgraph: referenced by the supergraph, not by any local field): when every `@key` is `resolvable: false`, leave it untouched rather than demoting to `UnclassifiedType`. graphql-java still carries the type in the assembled schema, so it is emitted to the subgraph SDL for the composer.

The two arms cover the reachable-classified stub and the orphan stub respectively; the reported `URegOrganisasjon` (an explicit-`@record` orphan) lands in the first loop.

**Out of scope.** Runtime `_Entity` union membership and federation-composition behaviour are owned by federation-jvm's `Federation.transform`, which honours `resolvable: false`; no change needed there.

## Shipped so far

- Second-loop (classified type) guard: shipped at `12a9f88`. Sound: a reachable `resolvable: false` stub stays in the registry classified as whatever it is (e.g. a `@record` type), gets a generated `<Name>Type` class, and so survives into both the codegen `schema.graphqls` and the runtime `_service.sdl`. Unit coverage (`resolvableFalseKeyOnRecordType_isAcceptedAsReferenceOnlyStub`, `mixedResolvableAndNonResolvableKeysOnRecordType_stillRejects`) is adequate, including the mixed-key boundary.
- First-loop (R276 orphan) guard: shipped at `01696e1`. **This is the part sent back; see review feedback.**

## Review feedback (In Review → Ready, reviewer session_01RV2zL7tuUG79rWytdQoYCz)

Verdict: **rework**. The second-loop arm is good; the first-loop (orphan) arm does not deliver the SDL emission the spec promises, and the coverage masks it.

1. **Orphan stub never reaches the runtime `_service.sdl` (the surface the composer introspects).** The first-loop guard (`EntityResolutionBuilder.java:115-119`) leaves an orphan `resolvable: false` stub absent from the `TypeRegistry` (`TypeBuilder` already drops unreachable types at the `if (!reachable) continue;` gate). But the runtime schema is assembled by `GraphitronSchemaClassGenerator.planFor`, which iterates `GraphitronSchema#types()` (the registry) and whose javadoc states *"The assembled schema is no longer consulted here."* An orphan absent from the registry therefore gets no generated `<Name>Type` class and is absent from the runtime `GraphitronSchema`, hence absent from `{ _service { sdl } }`. The spec's claim "graphql-java still carries the type in the assembled schema, so it is emitted to the subgraph SDL for the composer" is true only for the codegen *file artifact* (`SchemaSdlEmitter` prints from `assembled`), not the runtime introspection surface, and R247 already documents that these two surfaces diverge. Net effect for the reported case (an explicit-`@record` orphan, by the spec's own account): the hard author error is converted into a *silent omission* on the runtime composition surface. Bears on rewrite-design-principles.adoc § "Pipeline tests are the primary behavioural tier" and § "Documentation names only live tests/code" (unpinned, and here contradicted, invariant). Fix: pin the behaviour with a pipeline/execution test that asserts a non-table-bound `resolvable: false` stub appears in `{ _service { sdl } }`, then make the emission actually hold (register the stub as an additional type on the runtime build, or route it so `planFor` emits it) — or, if only the file-artifact surface is intended, state which surface the spec targets and reconcile with R247's known divergence.

2. **The first-loop repro test passes under the failure mode it should catch.** `EntityResolutionBuilderTest.resolvableFalseKeyViaExplicitRecordDirective_isAcceptedAsReferenceOnlyStub` (`:357-361`) asserts `stub == null || !(stub instanceof UnclassifiedType)`. The `stub == null` disjunct is satisfied precisely when the orphan type has vanished from the schema entirely, which is the central failure mode of finding 1. The assertion thus cannot distinguish "stub survives to the composer" (feature works) from "stub silently dropped" (feature is a no-op that only suppresses the error). Per § "Pipeline tests are the primary behavioural tier", this new behaviour earns a pipeline/execution test first; `graphitron-sakila-example` `FederationBuildSmokeTest` already extracts `{ _service { sdl } }` and is the natural home, but its fixture has no non-table-bound `resolvable: false` stub (its only `resolvable: false` type, `Language`, is table-bound). Add such a fixture type and assert it (and its `@key(... resolvable: false)`) round-trips into the served SDL.

3. **Plan housekeeping.** The body above ("Shipped so far") was added at review time; on the next pass collapse the `**Fix.**` phases into the shipped-at notes rather than leaving them as forward-looking prose.

Not blocking, for the record: the full `mvn install -Plocal-db` could not be run fully green in the review sandbox because `graphitron-lsp` fails on a missing `libtree-sitter.so` (environment setup, unrelated to R286); the `graphitron` module and `EntityResolutionBuilderTest` (18/18) pass.
