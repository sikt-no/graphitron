---
id: R120
title: "Drop or wire FkJoin.alias dead storage"
status: Backlog
bucket: cleanup
priority: 8
theme: model-cleanup
depends-on: []
---

# Drop or wire FkJoin.alias dead storage

`BuildContext.synthesizeFkJoin` (`BuildContext.java:694`) populates `FkJoin.alias` as `fieldName + "_" + stepIndex` (e.g. `"language_0"`) while resolving a `@reference` path. No code reads it: `JoinPathEmitter.generateAliases` (`JoinPathEmitter.java:41`) derives its own per-hop aliases from the target table's `javaClassName()` + hop index, and emitters layer their own runtime prefixes for self-ref recursion uniqueness on top. The stored value is never consulted. Same applies to the sibling `ConditionJoin.alias` slot.

Pick one when this item moves to Spec:

- **Drop it.** Remove `alias` from `FkJoin` and `ConditionJoin`, the construction sites in `BuildContext.synthesizeFkJoin`, and the test fixtures that pass it positionally. Smaller change, no behavioral effect.
- **Use it.** Make `JoinPathEmitter.generateAliases` consume `FkJoin.alias()` when present and fall back to the `javaClassName()`-derived form only when empty. Keeps the builder's claim on aliasing honest and aligns with the original "builder owns alias identity" intent.

Cosmetic; no generated-code difference either way. Surfaced during R3's classification-vocabulary follow-ups review (originally item 5 of `classification-vocabulary-followups`); split out so R3 could focus on the user-visible silent-acceptance footgun.
