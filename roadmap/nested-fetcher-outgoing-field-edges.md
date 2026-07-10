---
id: R462
title: "Model the nested fetcher own outgoing per-field precise edges in CompileDependencyGraphBuilder"
status: Backlog
bucket: bug
priority: 3
theme: dev-loop
depends-on: []
created: 2026-07-10
last-updated: 2026-07-10
---

# Model the nested fetcher own outgoing per-field precise edges in CompileDependencyGraphBuilder

## Problem

R459 registered the `<Type>Fetchers` *node* for a fetcher-owning plain-object nesting type and got the
schema-shape-to-fetcher wiring edge (`FilmMetaType → FilmMetaFetchers`) for free. It deliberately did
not model the nested fetcher's own *outgoing* per-field edges: a nested `SplitTableField`'s DataLoader
methods reference the target type's projection class; a nested composite / `@nodeId` read references
`NodeIdEncoder`. `CompileDependencyGraphBuilder.addFieldEdges` never sees these fields because they are
absent from `schema.fields()` (and `schema.fieldsOf(nestedType)` is empty for a coordinate-less nesting
type). The `TypeSpecReferenceWalk` completeness oracle will flag any such edge as a superset gap once a
harness fixture exercises it.

This is not a routing one-liner. Folding a nested `SplitTableField` through `addFieldEdges`'
`TableTargetField` arm would call `addConditionsEdge(fetcher, "FilmMeta")`, whose `hasSqlGeneratingField`
reads the empty `schema.fieldsOf("FilmMeta")` and produces the wrong answer, the same `fieldsOf`
blindness over coordinate-less nested types that caused the R459 bug. Conditions attribution (and, more
broadly, per-field edge sourcing) over nested types needs its own design that does not depend on the
nested type's SDL coordinates being populated.

## Failing pin to close against

Extend `IncrementalCompileHarnessTest`'s `SCHEMA` / `SCHEMA_EDITED` with a nested field that turns the
completeness oracle red once the R459 registration is in place, for example a nested `@splitQuery`
(DataLoader-backed `SplitTableField`) or a nested composite-`@nodeId` read on the fetcher-owning nesting
type. R459's own fixture (`Film.meta: FilmMeta { language: Language @reference }`) uses a single-valued
inline `TableField` precisely so it does *not* exercise this gap; verified while implementing R459 that
disabling the R459 walk left exactly the one `FilmMetaType → FilmMetaFetchers` gap and nothing else. This
item's fixture should produce a fetcher-outgoing gap (e.g. `FilmMetaFetchers → types.<Target>` or
`FilmMetaFetchers → util.NodeIdEncoder`) that the oracle catches.

## Notes

Filed by R459 as its scope-boundary follow-up (see the "Scope boundary" section of
`nesting-type-fetcher-wiring-graph-edges.md`). Related collapse target R459 also names: exposing nested
fetcher-owning types and their fields as a derived view on `GraphitronSchema` so emitter, projection
walk, and builder project off one seam; a shared nested-field-with-coordinates view would give this
item's per-field edge sourcing a non-blind `fieldsOf` to read.
