---
id: R897
title: "typeId uniqueness is enforced per graph and claimed at supergraph scope"
status: Backlog
bucket: validation
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# typeId uniqueness is enforced per graph and claimed at supergraph scope

`graphitron_node.type_id` is captured exactly as written; its column comment says "as written" and
nothing more. No constraint in the fact schema states that a typeId is unique, at any scope.

**Decoding a node id depends on it being unique across whatever scope ids are exchanged in.** A node
id carries its typeId so a decode can find the type it opens into. If two types claim the same typeId
within that scope, the decode has no way to choose and the ambiguity surfaces as a wrong row rather
than as an error.

**The scope is the part that is unsettled, and it is not the graph.** Uniqueness within one graph is
what a per-graph constraint would give and it is not what correctness needs: ids cross graph
boundaries in a federated supergraph, and a type in one subgraph claiming the same typeId as a type in
another is exactly the collision that matters. So a constraint on the captured relation is scoped
wrong by construction, because capture sees one graph at a time.

Three things this item owes. What the true scope is, stated as a rule rather than as a table.
Whether that rule can be checked at all at capture time, or whether it is inherently a composition-time
check that belongs beside whatever assembles a supergraph. And what the failure looks like today, which
should be established against a fixture with a deliberate collision before anything is built, because
a silent wrong row and a clear refusal are very different starting points.

