---
id: R812
title: "The defect readers ask a window-function view for one node type's key columns, once per refused row"
status: Backlog
bucket: architecture
priority: 3
theme: diagnostics
depends-on: []
created: 2026-08-23
last-updated: 2026-08-23
---

# The defect readers ask a window-function view for one node type's key columns, once per refused row

`NodeIdMessages.keyColumnsOf` is called from inside a fetch lambda in one reader, so it issues one
statement per row of the answer:

* `ArgmappingProjectionDefects.authorDefects`, once per refused projection, at the identical line
  shape.

What it reads is `intent_resolved_node_key_column`, and that relation's whole body is a
`DENSE_RANK() OVER (PARTITION BY graph_name, type_name)` picking the winning tier across three union
arms, two of which join `intent_resolved_type_binding`. A window sees its whole partition whatever an
outside predicate says, so filtering to one type prunes nothing: each refusal re-evaluates the tier
pick for the entire graph to learn one node type's key columns, and the message it feeds is a remedy
sentence listing those columns.

That is the one shape the fact model names as never correlatable per driving row, under "Derived
reads are views, not stored facts". The page's rule and its measured case are both there; this item
does not restate the number.

## The fix has no design fork, which is why it is worth slicing alone

The usual question at a child grain is `MULTISET` or paired statement. Here the relation answers it:
a correlated `MULTISET` on `node_type_name` would be the same per-row evaluation spelled in SQL, so
the only correct shape is to read the key columns once, filtered to the graph, and pair them on
`(graph_name, type_name)`, which is the key the relation already declares. That is the shape
`AuthoredClaimConflicts.typeGrain` and `SchemaQueries` both use, and the nested-jOOQ discipline names
as the exemplar.

Both call sites move together. They share one helper, and fixing one while leaving its twin is how a
tree acquires two spellings of one read.

Two things to settle while implementing, neither of them a fork in the shape:

* `keyColumnsOf` returns an empty list for a null node type today. Paired on the key, an absent type
  simply matches nothing, so the null guard should disappear rather than be carried across.
* The helper is also called from a test in `graphitron-model` (`ArgmappingProjectionDefectTest` has
  its own local copy of the query, not a call), so the production signature is free to change shape.

## What is not claimed

No measurement, and the direction is not assumed. Defects are the empty case on a healthy schema (the
sakila capture returns zero rows for the argmapping defect relation), so this costs nothing today, and
everything it costs arrives with the failure mode, on the build of an author already being told their
schema is wrong. The item should price the correlated shape at a realistic defect count rather than
assert it is slow: what is established is that the shape is doctrinally wrong, not what it costs.

Nothing prices it either, which is why an N+1 against a window-function view is invisible here. Every
statement-count enforcer in the tree is in `graphitron-lsp`, over language server surfaces; no pin
covers what the build's detection pass asks the store.

## Adjacent, and deliberately not in this item

The authored-claim conflicts had the same disease, a per-conflict correlated read of a recursive
view, and no longer do: R876 states every claim in `graphitron_claim` and reads a conflict's claims as
rows under the defect, so nothing of that family is left for this item to touch.
