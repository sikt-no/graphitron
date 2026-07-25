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

Scope pruned 2026-07-15: this item previously also covered the second caller of the shared reflection path, a directive that let a developer method supply a field's table. That directive has since been removed outright, so `@condition` is now the path's only caller and this item's only scope. The emitter mechanism is unchanged. The file slug (`dslcontext-on-condition-tablemethod`) keeps its historical name.
