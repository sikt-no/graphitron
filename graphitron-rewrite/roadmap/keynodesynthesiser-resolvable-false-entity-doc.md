---
id: R289
title: "Correct KeyNodeSynthesiser opt-out javadoc: @key(resolvable: false) does not keep a type out of _Entity"
status: Backlog
bucket: bug
depends-on: []
created: 2026-06-09
last-updated: 2026-06-09
---

# Correct KeyNodeSynthesiser opt-out javadoc: @key(resolvable: false) does not keep a type out of _Entity

`KeyNodeSynthesiser`'s class javadoc carries an "Opt-out" paragraph claiming that a consumer who writes `@key(fields: "id", resolvable: false)` on a `@node` type "keeps it out of `_Entity`". That is false for the pinned federation-jvm version: `Federation.transform` injects every `@key`-bearing type into the `_Entity` union regardless of `resolvable:`. The behaviour is empirically pinned by `FederationBuildSmokeTest.resultEntityUnionContainsAllFixtureEntities`, which asserts both the table-bound `Language` stub and (since R286) the non-table-bound `FilmRefStub`, both `@key(resolvable: false)`, are present in the served `_Entity` union. `resolvable: false` does not suppress union membership; what it does is tell the supergraph composer not to route entity-resolution queries to this subgraph for that type. This is the "broader failure mode" the *Documentation names only live tests/code* principle warns about: a doc claim that a live test directly contradicts, surfaced during the R286 (`12a9f88` + `77362d3`) In Review → Done review. Surface flagged, not introduced by R286 (the test's prior javadoc already noted "federation still includes them in the union"). Fix: rewrite the opt-out paragraph to describe what `resolvable: false` actually does (composer routing, not union membership), and audit `KeyNodeSynthesiser:22` ("surfaces them in `_Entity`") for the same precision. No code change; doc-only, but verify no other javadoc/spec prose repeats the false claim.
