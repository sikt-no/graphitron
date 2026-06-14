---
id: R284
title: "Reversed source/target alias order in bridging-hop @reference ConditionJoin emission"
status: In Review
bucket: bug
priority: 2
depends-on: []
created: 2026-06-08
last-updated: 2026-06-14
---

# Reversed source/target alias order in bridging-hop @reference ConditionJoin emission

A `@reference` path whose terminal hop is a `@condition` bridging hop (an FK first hop into a junction table, then a condition hop to the leaf) emits the two-argument condition-method call with its alias arguments reversed. The generated resolver passes `(targetAlias, sourceAlias)` where the documented convention is `(srcAlias, tgtAlias)`.

## Symptom

`opptak-subgraph`: `samordnaOrganisasjoner` navigates an FK first hop into a junction, then a terminal `@condition` hop to the leaf organisation table. The condition method's parameters are the two concrete, mutually incompatible jOOQ table types (junction and leaf). With the arguments reversed, the generated resolver does not type-check and fails to compile.

## Root cause

The bridging-hop `JoinStep.ConditionJoin` arm built its join as:

```
.join(prevAlias).on(JoinPathEmitter.emitTwoArgMethodCall(cj.condition(), aliases.get(i), prevAlias))
```

`prevAlias` is the source (the previous table in the chain) and `aliases.get(i)` is the target (the current hop's table), so this passes `(target, source)`. The fixed two-argument convention for join-condition methods is `(srcAlias, tgtAlias)`, documented on R16 (`fkjoin-model-cleanup`): "the generator calls them with a fixed `(srcAlias, tgtAlias)` two-argument convention via `JoinPathEmitter.emitTwoArgMethodCall`." The bridging-hop arm violated that order.

The same reversed call was duplicated across five emission sites:

* `InlineColumnReferenceFieldEmitter` (inline reference projection)
* `InlineTableFieldEmitter` (inline table-field projection)
* `SplitRowsMethodEmitter`, split rows method
* `SplitRowsMethodEmitter`, connection rows method
* `InlineLookupTableFieldEmitter` (inline lookup-table-field keyset projection)

The first four were caught and fixed in the initial pass; `InlineLookupTableFieldEmitter`
carried the same bridging-hop `ConditionJoin` arm (byte-for-byte the same terminal-back-to-step-0
join chain) and was missed, so it shipped still reversed and unguarded. It was fixed in the In
Review follow-up below.

## Why it shipped silently

Every existing condition-join fixture declares its method with generic `Table<?>` parameters. With both parameters the same type, a reversed `(target, source)` call still compiles, so generation and compile-spec both passed and no test failed. The defect is only observable when the condition method takes concrete, mutually incompatible jOOQ table types, which is exactly the opptak `samordnaOrganisasjoner` shape (junction type versus leaf type). The first-hop `Table<?>` fixtures structurally could not catch it.

## Fix

Swap the two aliases to `(prevAlias, aliases.get(i))` (source, target) at all five bridging-hop `ConditionJoin` emission sites, restoring the R16 convention. (The initial pass swapped four; the In Review follow-up swapped the fifth, `InlineLookupTableFieldEmitter`.)

## Tests (as-built)

* Regression guard with concrete incompatible types: `ReferencePathConditionFixtures.filmActorJunctionToActor(FilmActor, Actor)`, the terminal hop of a new `Film.actorsViaJunctionCondition` field whose path is an FK hop (`film` to `film_actor`) then a bridging `@condition` hop (`film_actor` to `actor`). Because the two parameter types are mutually incompatible, any future re-reversal of the aliases makes the generated resolver fail to compile in compile-spec. The pre-existing `Table<?>`-typed first-hop fixtures could not provide this guard.
* Execution: `GraphQLQueryTest.splitTableField_bridgingConditionJoin_returnsActorsPerFilm` round-trips the bridging join, asserting the same per-film actor sets as the FK and condition-only navigations, and the 1 root query plus 1 batched DataLoader (2 query) shape.
* Inline-lookup guard (In Review follow-up): `FilmInlineBundle.actorsByKeyViaJunctionCondition`, an inline `@lookupKey` field whose `@reference` path is an FK first hop (`film` to `film_actor`) then a terminal bridging `@condition` hop (`film_actor` to `actor`) reusing `ReferencePathConditionFixtures.filmActorJunctionToActor(FilmActor, Actor)`. This routes through `InlineLookupTableFieldEmitter` (the fifth site) rather than the split-rows emitter; because the two parameter types are mutually incompatible, a re-reversal of the aliases makes the generated resolver fail to compile in compile-spec. No prior fixture exercised the bridging-hop argument order on the inline-lookup path.

## References

* R16 `fkjoin-model-cleanup`: documents the `(srcAlias, tgtAlias)` two-argument convention this bug violated.
