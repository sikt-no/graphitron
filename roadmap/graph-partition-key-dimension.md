---
id: R610
title: "SDL fact keys carry a graph partition dimension"
status: Backlog
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-08
last-updated: 2026-08-08
---

# SDL fact keys carry a graph partition dimension

The fact store's SDL families key every row on the type name alone (`graphql_type` is
`PRIMARY KEY (type_name)` and everything downstream inherits that coordinate), which encodes an
assumption the store should not make: that it will only ever hold one GraphQL document universe.
A plausible future direction has one long-lived store serving several Apollo federation subgraphs
at once (shared fact gathering, cross-subgraph composition detections in the LSP, one
`graphitron:dev` process per workspace instead of per subgraph), and under federation that
assumption is false by design: entities are deliberately declared in multiple subgraphs, so
`User` in one subgraph and `User` in another are distinct facts with different fields, keys, and
ownership. Under today's keys, first-wins merge would silently fuse them, turning valid input
into either a fictional merged type or a primary-key violation, and the constraint split
(violations are capture bugs, never author errors) breaks. Directive definitions partition the
same way, since each subgraph carries its own `@link` with possibly aliased imports. The store's
own design (R595, shipped; `roadmap/changelog.md`) states the governing rule for source
partitionability, and it applies verbatim here: a partition dimension a schema is written
without cannot be acquired later without rekeying. That rekey is cheap now (no persisted state
of record, the warm cache is stamp-invalidated rather than migrated, and changing the model is
editing the DDL and following the compiler) and grows more expensive with every consumer the
fact-base migration (`validation-adds-facts`, R589) moves onto the relations.

The work: add a graph partition key part (name to be settled at Spec; `graph_name` avoids baking
federation vocabulary into the store) to the natural keys of the `graphql_` and `graphitron_`
families, cascading through their foreign keys and the applied-directive union view, plus a
`store_` membership relation making a graph a set of sources. Single-graph runs write one
constant value, so capture and every existing consumer see one more key column and nothing else.
Deliberately out of scope: the `jvm_` and `sql_` families keep their keys, because a subgraph's
classpath scope is a membership question and per-graph name resolution is derivation, not
capture (version skew between two subgraphs' classpaths is a real case, but it is a different
item, as are the multi-subgraph capture orchestration and any composition-detection stratum).
This item buys only the property that is expensive to retrofit: the key shape.
