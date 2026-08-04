---
id: R588
title: "Diagnostics for `implements Node @table` over a table with no node metadata"
status: Backlog
bucket: dx
priority: 4
theme: nodeid
depends-on: []
created: 2026-08-04
last-updated: 2026-08-04
---

# Diagnostics for `implements Node @table` over a table with no node metadata

A type declared `implements Node @table(name: "x")` over a table whose jOOQ class publishes no
`__NODE_*` metadata has exactly one working spelling, explicit `@node`, and neither of the two
spellings an author would naturally reach for says so. Bare `id: ID!` rejects with
`column 'id' could not be resolved in the jOOQ table; did you mean: ...`, whose candidate list
pushes the author toward `@field(name:)`, which would satisfy the classifier while publishing a
`Node` contract that cannot round-trip through `Query.node`. Writing `id: ID! @nodeId` instead
rejects with `@nodeId requires the containing type to be a node type (via @node or
KjerneJooqGenerator metadata)`, which is accurate about the rule and silent about the remedy. In
both cases the author has already stated their intent unambiguously by implementing the `Node`
interface, and the build declines to name the one directive that would honour it.

Scope is diagnostics only: no classification changes, no inference. The likely shape is a targeted
rejection at the classifier that fires when a type implements `Node`, carries `@table`, and the
bound table has no metadata, naming `@node` and stating that the table publishes no node identity
to infer from. It should pre-empt the column-lookup message rather than decorating it, since the
did-you-mean list is the actively harmful part.

Sibling to the metadata-*present* branch of the same trap, which is fixed by the inference item
`R580` (`roadmap/infer-node-from-implements-node-and-metadata.md`). That item deliberately leaves
this branch alone to avoid mixing a classification change with a message change, and its Non-goals
section records the three-spelling classification that motivates this one. Worth picking up after
`R580` lands, since the surviving trap is narrower once inference covers the metadata-present case,
and the message can then say "this table publishes no node metadata" as a contrast with a path that
actually exists.
