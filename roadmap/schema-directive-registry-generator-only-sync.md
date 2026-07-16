---
id: R494
title: "Reconcile SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES with BuildContext DIR_* (routine, asFacet)"
status: Backlog
bucket: bug
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-07-16
last-updated: 2026-07-16
---

# Reconcile SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES with BuildContext DIR_* (routine, asFacet)

`SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES` documents itself as "kept in sync with the `DIR_*` constants in `BuildContext`", with the invariant that adding a generator-only directive means adding both a `DIR_*` constant and an entry here. `BuildContext` now declares `DIR_ROUTINE` ("routine") and `DIR_AS_FACET` ("asFacet") that are absent from the set, so the stated invariant is already false. This is potentially a correctness bug, not only doc drift: if those two directives are meant to be generator-only, `isSurvivor("routine")` / `isSurvivor("asFacet")` currently return the wrong answer and the directives can leak into the emitted schema; if they are genuinely survivors, the doc overstates the coupling. Resolving it requires deciding the intended classification of `routine` and `asFacet`, then either extending the set (with a mechanical sync check so it cannot silently drift again) or correcting the prose to match reality.

Surfaced by the R483 javadoc drift audit.
