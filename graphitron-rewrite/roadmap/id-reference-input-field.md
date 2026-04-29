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

R20's intended execution-tier coverage (a query whose filter mixes `[ID!] @nodeId(typeName: T)` with column-equality fields, round-tripping end-to-end through generated code) is fully named in R50's "Test surface" → Pipeline-tier list and "Failure-mode contract" → `SkipMismatchedElement` arm. This file is a tombstone: when R50 reaches Done, delete this file in the same commit. R20 only re-opens if a concrete execution-tier shape surfaces that R50's test surface does not cover; pin that shape here before re-specing.
