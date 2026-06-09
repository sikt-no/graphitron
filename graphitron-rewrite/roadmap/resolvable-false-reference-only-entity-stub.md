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

**Fix.** In the non-table-bound branch of `EntityResolutionBuilder.build()`, when **every** `@key` directive on the type is `resolvable: false`, skip the type (no demote, no `EntityResolution` entry) rather than rejecting: there is nothing to resolve and no table to require. When at least one key is resolvable, the table requirement stays as-is (the existing R176 diagnostic still fires). This naturally covers both the reported `@record` case and the canonical bare-object reference stub, both of which reach that branch already classified.

**Out of scope.** Reference-only stubs that are *absent from the registry* entirely (the R276 first-loop case) — those are a rarer shape and keep their current diagnostic. Runtime `_Entity` union membership and federation-composition behaviour are owned by federation-jvm's `Federation.transform`, which honours `resolvable: false`; no change needed there.
