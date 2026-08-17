---
id: R699
title: "graphitron-mcp answers what binds this column, table or method"
status: Backlog
bucket: tooling
priority: 3
theme: tooling
depends-on: []
created: 2026-08-17
last-updated: 2026-08-17
---

# graphitron-mcp answers what binds this column, table or method

An agent about to rename a database column, change a service method's signature or drop a table
needs one answer before it touches anything: which schema coordinates bind that thing. Nothing in
`graphitron-mcp` answers it. The `edges` tool used to, and
`roadmap/catalog-facts-readers-move-to-the-store.md` dropped it rather than migrate it, on the
grounds that its forward direction had become a reformatting of what the `schema` tool answers and
its labels read backwards in the reverse direction. The reverse question itself was never the
problem, and it is the half worth having.

The store makes it a much smaller thing to build than what was dropped. Given a target, which is a
column, a table, a consumer method or a consumer class, the binding relations already carry the
coordinates that reach it, keyed at the target end: the question is a predicate on the other side of
relations `schema` is being written against anyway. So it is one query at one grain rather than a
traversal engine, and the in-memory index that inverted a map per build has no successor because
there is no map to invert.

Two things this item owes a plan. The result vocabulary should be the authored directive that made
the binding (`@field`, `@nodeId`, `@table`, `@node`, `@service`, `@externalField`, `@condition`,
`@reference`), not a label invented at the edge layer, so a reader can go from the answer to the SDL
line that caused it. And the projection should be one nested result set, target row carrying its
binding coordinates, rather than several reads folded together in Java.
