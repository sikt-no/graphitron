---
id: R11
title: "`DSLContext` on `@condition` / `@tableMethod` methods"
status: Backlog
bucket: architecture
priority: 6
theme: service
depends-on: []
---

# `DSLContext` on `@condition` / `@tableMethod` methods

Lift the `reflectTableMethod` gate. Requires `ArgCallEmitter` to walk `params()` instead of `callParams()` so the injected `DSLContext` lands at its declaration-index slot.
