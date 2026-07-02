---
id: R419
title: "Reject list-valued @nodeId+@reference carriers on INSERT inputs at build time"
status: Backlog
bucket: validation
theme: nodeid
depends-on: []
created: 2026-07-02
last-updated: 2026-07-02
---

# Reject list-valued @nodeId+@reference carriers on INSERT inputs at build time

A list-valued node-id reference field on an INSERT input (e.g. `parentId: [ID!]! @nodeId(typeName: "T") @reference(path: [...])`) passes classification and validation today: `NodeIdLeafResolver` is arity-agnostic, `BuildContext.classifyInputField` just bakes `list=true` into the `ColumnReferenceField` / `CompositeColumnReferenceField` carrier, and `MutationInputResolver.admitMutationInputFields` admits reference carriers for INSERT unconditionally (only the `NestingField` arm rejects lists). The generated code compiles but hardcodes single-value assumptions (`instanceof String` decode guard, `.value1()` bind in `TypeFetcherGenerator`), so any non-empty list value throws `GraphitronClientException` "Decoded NodeId did not match the expected type" at runtime. That is the worst failure mode: it surfaces only when the field is populated. Until fan-out semantics are actually supported (R420), a `list()` guard in `admitMutationInputFields` alongside the existing nesting-field list rejection should turn this into a clear build-time schema error.
