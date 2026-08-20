---
id: R152
title: "Pin the @nodeId(typeName:) hover's column scoping against two tables sharing a column name"
status: Backlog
bucket: validation
theme: lsp
depends-on: []
created: 2026-05-13
last-updated: 2026-08-20
---

# Pin the @nodeId(typeName:) hover's column scoping against two tables sharing a column name

**Re-scoped 2026-08-20; the bug this item was filed for is fixed and only its test pin survives.
The original body is in git history.** As filed on 2026-05-13, the item reported that
`Hovers.formatNodeType` (the hover for `@nodeId(typeName: "X")`) typed X's key columns through a
catalog-wide linear scan that returned the first name match, so two tables holding a same-named
column with diverging `graphqlType` projections rendered whichever the catalog enumerated first.
It prescribed carrying the `@table` name on `CompletionData.NodeMetadata` and scoping the lookup
through `catalog.getTable`.

The LSP's move to the fact store delivered the behaviour and deleted the prescription's whole
vocabulary. `columnGraphqlType`, `CompletionData.NodeMetadata` in the LSP main sources, and
`TypeContext.enclosingTypeDefinition` (the sibling shape the fix was to mirror) are all absent
from the tree. The scoping now lives in `Hovers.nodeColumns`, which resolves the node type's own
binding out of `graphitron_table` and returns that table's columns; its javadoc states the old
defect in the past tense as its own reason for existing.

## What is left

The falsifiability. Nothing in `graphitron-lsp`'s tests distinguishes the scoped lookup from the
catalog-wide one, so the fix rests on reading `nodeColumns` rather than on a failing assertion.
Sakila cannot supply the case: its recurring column names project to identical jOOQ-generated
graphql types, which is why the original bug was latent there.

The pin: a hover test over two tables that both carry a column of one name whose projected types
diverge (one mapped through a custom scalar via `@scalarType` and one not is the cheapest
divergence), asserting the hover for a `@nodeId(typeName:)` naming the node over the first table
renders that table's projection. Reverting `nodeColumns` to a catalog-wide first-hit lookup
should turn it red, and saying so in the commit is the non-vacuity statement.

Discarding this item outright is the reasonable alternative if the pin is judged not to earn its
keep: the deliverable it was filed for is in the tree either way.

Provenance: surfaced during R100's In Review -> Done review; re-scoped from the sweep in
`roadmap/audits/2026-08-20-nodeid-relation-impact-sweep.md`.
