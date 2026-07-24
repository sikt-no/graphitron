---
id: R494
title: "Reconcile SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES with BuildContext DIR_* (routine, asFacet, pivot)"
status: Backlog
bucket: bug
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-07-16
last-updated: 2026-07-24
---

# Reconcile SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES with BuildContext DIR_* (routine, asFacet, pivot)

`SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES` documents itself as "kept in sync with the `DIR_*` constants in `BuildContext`", with the invariant that adding a generator-only directive means adding both a `DIR_*` constant and an entry here. `BuildContext` now declares `DIR_ROUTINE` ("routine") and `DIR_AS_FACET` ("asFacet") that are absent from the set, so the stated invariant is already false. This is potentially a correctness bug, not only doc drift: if those two directives are meant to be generator-only, `isSurvivor("routine")` / `isSurvivor("asFacet")` currently return the wrong answer and the directives can leak into the emitted schema; if they are genuinely survivors, the doc overstates the coupling. Resolving it requires deciding the intended classification of `routine` and `asFacet`, then either extending the set (with a mechanical sync check so it cannot silently drift again) or correcting the prose to match reality.

Surfaced by the R483 javadoc drift audit.

## Assessment (2026-07-24): confirmed real bug, three directives leak

Verified against the current build outputs; this is an actual leak, not only doc drift. Three
`DIR_*` names are missing from `GENERATOR_ONLY_DIRECTIVES`: `routine`, `asFacet`, and `pivot`
(`pivot` was added after this item was filed and drifted the same way). All three are declared in
`directives.graphqls`, are pure build-time directives with no runtime meaning, and `isSurvivor`
returns true for each, so both emission arms pass them through:

- The emitted `schema.graphqls` (sakila example, `target/generated-resources/.../schema.graphqls`)
  contains the `directive @routine(...)`, `directive @asFacet ...`, and `directive @pivot(...)`
  definitions plus the applications, e.g.
  `films(minLength: Int!): [ActorFilm!] @routine(argMapping : "pMinLength: minLength", columnMapping : "pActorId: actor_id", name : "films_for_actor")`.
- The generated programmatic schema classes carry the same leak:
  `GraphitronSchema.java` registers all three via `additionalDirective(...)`, and per-type
  emitters (`ActorType`, `FilmType`, `FilmFacetFilterType`) attach the applications via
  `withAppliedDirective(...)`.

Impact beyond cosmetics: the applications expose build-time database internals (routine names,
column mappings such as `pActorId: actor_id`) to any consumer that introspects the schema or reads
the published SDL. The `SchemaDirectiveRegistryTest` sync test only spot-checks a hardcoded name
list, so it could not catch the drift; the fix should add all three names and replace the
spot-check with a mechanical comparison against the `BuildContext` `DIR_*` constants (minus the
documented SDL-only extras) so the set cannot silently drift again.
