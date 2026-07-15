---
id: R11
title: "`DSLContext` on `@condition` methods"
status: Backlog
bucket: architecture
priority: 6
theme: service
depends-on: []
last-updated: 2026-07-15
---

# `DSLContext` on `@condition` methods

Let a `@condition` method take an injected `DSLContext` parameter. `@condition` methods reflect through the shared `ServiceCatalog.reflectTableMethod` path (`ConditionResolver` calls it for both the argument-level and field-level condition forms), whose gate currently blocks the injected context. Lifting it requires `ArgCallEmitter` to walk `MethodRef.params()` instead of `callParams()` so the injected `DSLContext` (`ParamSource.DslContext`, emitted as the `dsl` local) lands at its declaration-index slot.

Scope pruned 2026-07-15: this item previously also covered `@tableMethod` methods, but `@tableMethod` is withheld from the v1 surface and its support work was set aside (see the R277 discard), so the scope is now `@condition` only. The reflection path named above is shared between the two callers, so the emitter mechanism is unchanged; only the directive scope narrows. The file slug (`dslcontext-on-condition-tablemethod`) keeps its historical name.
