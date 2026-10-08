---
id: R999
title: "Minted Connection, Edge and PageInfo lose the carrier field's @shareable in the published schema"
status: Backlog
bucket: bug
priority: 2
theme: pagination
depends-on: []
created: 2026-10-08
last-updated: 2026-10-08
---

# Minted Connection, Edge and PageInfo lose the carrier field's @shareable in the published schema

## Goal

A federated subgraph whose `@asConnection` field carries `@shareable` publishes the Connection, Edge and `PageInfo` types graphitron generates for that field as `@shareable` again, as the manual promises ("Federation directives on the field (`@shareable`, `@tag`) propagate onto the synthesised Connection, Edge, and PageInfo types"). Since the published schema has been built from the fact store (`EmittedRegistry`), the generated types carry the carrier's `@tag`s but never its `@shareable`, so a `PageInfo` that two subgraphs both generate no longer composes unless the author declares it.

Reproduced on trunk at `1fa7915` through the generator's own output (the `StoreEmittedFederationSchemaPipelineTest` harness): with

```graphql
extend type Query {
  stableFilms: [Film!]! @asConnection @defaultOrder(primaryKey: true) @shareable
}
```

the emitted `Query.stableFilms` keeps `@shareable`, while `QueryStableFilmsConnection`, `QueryStableFilmsConnectionEdge` and `PageInfo` are emitted with `@tag` only.

## Notes for Spec

- **Where it goes missing.** `EmittedRegistry.objectType` builds a minted definition with no applied directives, and the only later additions are `applyInheritedTags` and `applySynthesisedKeys`. The walk (`ConnectionPromoter`, `TypeRegistry.mergeSynthesisedTags`) still ORs `shareable` across carriers, so the two producers disagree; `EmittedRegistryAgreementTest` cannot see it because no corpus document has a `@shareable` carrier.
- **The rule to keep.** Any shareable carrier makes the generated type shareable. `@shareable` is a composition requirement, not a contract filter, so it does not follow the tag rule R997 settles; the fix should sit beside `applyInheritedTags` without sharing its set operation.
- **Same source question as tags.** Whether `@shareable` reaches the store as a fact a view can read, or is read off the patched registry the way `applyInheritedTags` reads tags, is the Spec's call; a corpus document with a `@shareable` carrier belongs in the agreement test either way.
