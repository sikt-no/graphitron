---
id: R988
title: "The tenant-context fold seeds a @key type no _entities call can dispatch, so its children reject under a bound root"
status: Backlog
bucket: bug
theme: runtime-connection
depends-on: []
created: 2026-10-05
last-updated: 2026-10-05
---

# The tenant-context fold seeds a @key type no _entities call can dispatch, so its children reject under a bound root

## Goal

Under database-per-tenant routing, a `@key` type whose every key alternative is `resolvable: false` stops denying its tenant-scoped children a tenant context. A child field inherits the tenant when every path from a root to its parent passes a field that binds the tenant, and the federation `_entities` lookup counts as one of those paths, entered with no tenant, for an entity it cannot route. `_entities` never dispatches a type with no resolvable alternative, though, so that path does not exist. Today a schema that reaches such a type only through a binding root still has the type's tenant-scoped children rejected with "no ancestor established a tenant context", and that build error stands in for a request-time failure that cannot happen.

## What is true today

`TenantBindingIndex.Fold.unboundDispatchEntry` seeds "no context" for every type in `entitiesByType` with no `EntityRepBound`. `EntityResolutionBuilder` records an entry for every `@key` type, non-resolvable alternatives included, and `classifyEntityDispatch` skips non-resolvable alternatives, so a type whose keys are all `resolvable: false` never gets an `EntityRepBound` and is always seeded. Before the R986 fold this applied to tenant-scoped types only. Now it applies to untenanted ones too. Probe, under the `TenantBindingClassificationTest` fixture with `film_id` as the tenant column:

```graphql
type Film @table(name: "film") {
    title: String
    language: Language @reference(path: [{key: "film_language_id_fkey"}])
}
type Language @table(name: "language") @key(fields: "languageId", resolvable: false) {
    languageId: Int @field(name: "language_id")
    films: [LanguageFilm!]! @reference(path: [{key: "film_language_id_fkey"}])
}
type LanguageFilm @table(name: "film") { title: String }
type Query { films(filmId: Int @field(name: "film_id")): [Film!]! }
```

`Language.films` rejects with `noTenantBinding`. The only way in is `Query.films`, which binds.

The likely fix is to count a dispatch entry only for an entity with at least one resolvable alternative, in both the untenanted and the tenant-scoped half of the seed, and to pin the probe as a test.
