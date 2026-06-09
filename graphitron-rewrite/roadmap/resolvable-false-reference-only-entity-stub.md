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

**Fix.** `EntityResolutionBuilder.build()` rejects the stub in *two* places, and both need the guard. The decision turns on the federation `resolvable` flag alone, never on `@record` (or any other) classification:

1. **Second loop** (the type is classified, e.g. a `@record` type or a bare `NestingField` plain object): in the non-table-bound branch, when **every** `@key` is `resolvable: false`, skip the type (no demote, no `EntityResolution`). When at least one key is resolvable, the table requirement stays and the R176 diagnostic still fires.
2. **First loop** (R276; the type is *absent from the registry* — the common case for a federation reference stub, which is an orphan in this subgraph: referenced by the supergraph, not by any local field): when every `@key` is `resolvable: false`, leave it untouched rather than demoting to `UnclassifiedType`. graphql-java still carries the type in the assembled schema, so it is emitted to the subgraph SDL for the composer.

The two arms cover the reachable-classified stub and the orphan stub respectively; the reported `URegOrganisasjon` (an explicit-`@record` orphan) lands in the first loop.

**Out of scope.** Runtime `_Entity` union membership and federation-composition behaviour are owned by federation-jvm's `Federation.transform`, which honours `resolvable: false`; no change needed there.
