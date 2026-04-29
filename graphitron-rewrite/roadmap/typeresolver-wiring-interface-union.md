---
title: "`TypeResolver` wiring for interface/union types"
status: Backlog
bucket: cleanup
priority: 11
theme: interface-union
depends-on: [stub-interface-union-fetchers]
---

# `TypeResolver` wiring for interface/union types

`WiringClassGenerator` emits `DataFetcher` wiring only; GraphQL interface and union types also require a `TypeResolver` registered via `TypeRuntimeWiring.newTypeWiring("MyInterface").typeResolver(...)`. Currently no `TypeResolver` is wired for any interface or union type, so the runtime would get `Can't resolve type for object` errors.

Companion to [Stub #3: Interface / union fetchers](stub-interface-union-fetchers.md): the fetcher stub covers `QueryField` / `ChildField` variants; this item covers the `WiringClassGenerator` side. The `Node` interface is the exception: `QueryNodeFetcher.registerTypeResolver` is wired today via the `@nodeId` + `@node` plan.
