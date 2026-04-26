---
title: "Stub #3: Interface / union fetchers"
status: Backlog
bucket: stubs
priority: 1
---

# Stub #3: Interface / union fetchers

Lift these leaves out of `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`: `QueryField.QueryInterfaceField`, `QueryTableInterfaceField`, `QueryUnionField`, `ChildField.InterfaceField`, `UnionField`, `TableInterfaceField`.

Priority number `#3` is referenced by emitted reason strings and must stay stable.

Companion item under [Cleanup → `TypeResolver` wiring for interface/union types](typeresolver-wiring-interface-union.md): the fetcher stub covers `QueryField` / `ChildField` variants; the `TypeResolver` item covers the `WiringClassGenerator` side.
