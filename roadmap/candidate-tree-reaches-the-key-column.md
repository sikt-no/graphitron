---
id: R898
title: "The argMapping candidate tree stops one level above the key column"
status: Backlog
bucket: architecture
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# The argMapping candidate tree stops one level above the key column

The argMapping candidate tree resolves a written right-hand side by matching its deepest prefix
against a candidate path. It models the descent through input-object-typed fields, and it stops
there.

**A written path routinely spells one more level than the tree has.** Where the leftover is a single
segment, the reading is a key-column projection: the path reached a candidate that is a node, and the
last name selects a column of that node's key. `intent_argmapping_binding_leaf` carries that case as
`trailing_segments = 1` and hands it on unresolved, so every reader that cares about it re-derives what
that leftover name refers to instead of joining to a row.

**Which is the same defect this whole line of work has been finding, one level lower down.** The tree
exists because resolving a path by walking a positional segment list was being done a hundred times
per statement; making the tree reach the key column would mean the projection resolves by the same
prefix match as everything above it, rather than by a rule spelled separately in each reader.

**What has to be settled first is whether the key column belongs in the same relation.** A candidate is
a point in the SDL's input surface; a key column is a catalog fact reached through the node's bound
table. Putting both in one tree means one relation spanning two corpora, which the schema's own
ownership rule says belongs to a gatherer running after both. That is now possible, since the
graphitron gatherer reads the catalog, but possible is not the same as correct: the alternative is a
second keyed relation the leaf joins to, and the choice should be made on which one a reader can state
its question against, not on which is fewer tables.

