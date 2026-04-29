---
id: R20
title: "`IdReferenceField` code generation"
status: Backlog
bucket: architecture
priority: 4
theme: nodeid
depends-on: [lift-nodeid-out-of-model]
---

# `IdReferenceField` code generation

`InputField.IdReferenceField` is in `TypeFetcherGenerator.NOT_DISPATCHED_LEAVES`: classification produces the variant but the generator emits no Java for it, so a schema using `[ID!] @nodeId(typeName: T)` builds without `UnclassifiedType` errors but the resulting fetcher does not actually filter by ID.

The previous Spec emitted `tableAlias.has<Qualifier>(s)(decodedIds)`. That shape is invalidated by R50: `NodeIdEncoder.hasIds` is a query-builder helper that does not belong in the encoder, and the column-shaped collapse R50 lands replaces it with standard FK-equality (single FK) or `DSL.row(...).in(...)` (composite FK) over decoded key tuples. R20's emission shape dissolves into R50.

This item stays as a Backlog tracker for the execution-tier coverage gap (a query whose filter mixes `[ID!] @nodeId(typeName: T)` with column-equality fields actually round-trips through generated code) until R50 lands, at which point most of the surface is already covered by R50's test surface and what remains here is mop-up. Re-spec then, or retire the file if R50's coverage subsumes it.
