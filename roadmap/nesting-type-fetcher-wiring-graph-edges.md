---
id: R459
title: "Model the schema-shape to fetcher wiring edge for fetcher-owning nesting types in CompileDependencyGraphBuilder"
status: Backlog
bucket: bug
priority: 3
theme: structural-refactor
depends-on: []
created: 2026-07-09
last-updated: 2026-07-09
---

# Model the schema-shape to fetcher wiring edge for fetcher-owning nesting types in CompileDependencyGraphBuilder

## Problem

A plain-object nesting type that owns a fetcher (`FetcherEmitter.nestedTypeOwnsFetchers` is true
for any nested type with at least one classified field, per R303) emits a `<Type>Fetchers` class,
and its `<Type>Type` schema-shape wiring class references that fetcher (`FilmMetaType → FilmMetaFetchers`).
`CompileDependencyGraphBuilder` does not model this: `addTypeNodes`' `NestingType` arm registers only
the schema-shape node, never the nested fetcher, so the wiring loop in `addBlanketAndWiringEdges`
(gated on `fetcherNodes.contains(ownFetcher)`) never adds the `schemaShape → fetcher` edge. The
`TypeSpecReferenceWalk` completeness oracle (working correctly) flags this as a real superset gap:
the walk sees the edge; the model misses it.

Surfaced during R455 while extending the incremental-compile harness corpus with a fetcher-owning
nesting type. It is orthogonal to R455 (that item closed the walk's own blind spots and the inline
projection/condition/NodeIdEncoder edges); this is a separate model-completeness gap in the nesting
wiring, deferred out of R455 to keep that item scoped.

## Sketch

The nested fields live only on the `ChildField.NestingField` (they are not registered under the
nesting type's coordinates: `schema.fieldsOf("FilmMeta")` is empty), so the fetcher-node registration
must happen where the `NestingField` is reachable, not in `addTypeNodes`. The projection walk added
in R455 (`addProjectionChildEdges`) already recurses NestingField sub-trees keyed off the
`TableType`/`NodeType` roots, matching `TypeFetcherGenerator.collectNestedFetcherClasses`' reachability;
registering `units.fetchers(nestingType)` there (gated on the same `nestedTypeOwnsFetchers` predicate,
mirrored rather than imported to preserve the builder's no-generator-coupling discipline) lets the
existing wiring loop add the `schemaShape → fetcher` edge and the blanket frozen-scaffold edges for
free. Add harness corpus coverage (a nesting type carrying an inline table field) and a
`CompileDependencyGraphBuilderTest` case; verify the oracle goes green with the nesting case present.
