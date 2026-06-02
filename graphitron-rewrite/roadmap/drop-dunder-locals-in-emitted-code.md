---
id: R271
title: "Retire dunder-prefixed locals in emitted generator code"
status: Backlog
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-06-02
last-updated: 2026-06-02
---

# Retire dunder-prefixed locals in emitted generator code

Many emitters spell their generated locals with a `__` (dunder) prefix: `__fetched`, `__byPk`, `__r`, `__src`, `__key`, `__match`, `__ordered` (`FetcherEmitter`), `__elt` / `__k` (`GeneratorUtils.buildAccessorKeySingle` / `buildAccessorKeyMany`), `__t` / `__m` (`ChannelCatchArmEmitter`), and more across `TypeFetcherGenerator`, `MultiTablePolymorphicEmitter`, `SplitRowsMethodEmitter`, and the `util` / `schema` generators (~hundreds of occurrences in ~10 files). This reads against the generated-code-readability direction the project has otherwise committed to (R260, In Review, drops the underscore style from `ArgCallEmitter`'s NodeId decode; R268, Spec, plans the `__elt` / `__k` rename as incidental cleanup; the design-principles doc calls for readable locals with explicit types and ordinary control flow). The dunders are not careless: at least `ChannelCatchArmEmitter` documents them as a collision-avoidance device ("binds `__t` and `__m` to avoid colliding with author-visible names") ; in generated code a local must not clash with a consumer-supplied identifier that can appear in the same scope. So this is not a blind find-and-replace: retiring the `__` prefix requires a single decided replacement convention that still guarantees non-collision (candidates: a single reserved generator prefix that is documented as off-limits to authors; names derived from the emitter's role that are unlikely to collide; or narrowing the rename to scopes where collision is provably impossible because no author-visible name reaches them). The systematic sweep is deliberately *not* R260's or R268's job (both are point fixes on one emitter each); this item is the generator-wide retrofit, and it should pick the replacement convention once, write it into the design-principles doc as the standing rule for emitted locals, and convert the emitters to it. Note `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` (read off jOOQ-generated consumer classes, not emitted by us) and the `federation__` / `link__` SDL scalar names are *not* in scope: those dunders are external, not ours to rename. Pin: compilation tier (`graphitron-sakila-example`) plus the existing execution suite, since the rename is behaviour-preserving and the safety net is "still compiles, still round-trips"; per-emitter generated-body string assertions are banned, so the convention is verified by the code compiling and running, not by grepping local names out of fetcher `toString()`.
