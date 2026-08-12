---
id: R643
title: "Supergraph peer surface for the dev loop"
status: Backlog
bucket: feature
priority: 3
theme: tooling
depends-on: [maven-config-fact-family]
created: 2026-08-12
last-updated: 2026-08-12
---

# Supergraph peer surface for the dev loop

A dev session already sits on every answer it refuses to give. `DevMojo` opens the workspace
store, so the sibling subgraphs' SDL-derived facts are physically in the same file, and every
read the session makes leads with its own `graph_name`; a developer (or a dev agent on the MCP
handle) working on one subgraph of a federated supergraph cannot ask which peer defines the
entity they extend, what a peer's `@key` fields are, or which subgraphs even exist. R612 lands
the two prerequisites: the declared membership fact (`store_graph.supergraph_name`) and the read
doctrine's two-axis scope rule (enumeration over `store_graph` alone; payload over SDL-derived
families only, scoped to same-supergraph peers; standalone graphs, whose `supergraph_name` is
null, have an empty peer set, and two supergraphs in one workspace store stay mutually
invisible). This item builds the reader: MCP tools first (peer enumeration, peer schema
questions), the LSP's cross-subgraph navigation later or separately. Constraints inherited from
R612's doctrine, restated so they are not rediscovered: this item mints the *first* production
spelling of the peer-set predicate and should mint exactly one (a view or one shared derivation,
not a per-tool `WHERE` clause); enumeration is not stamp-gated, but `store_graph`'s
`build_file_stamp` fitness caveat rides beside every peer answer so staleness is rendered, not
hidden; and peer answers never range over graph-private families (`javac_diagnostic`) or the
config rows.
