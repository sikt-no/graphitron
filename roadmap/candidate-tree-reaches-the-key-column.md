---
id: R898
title: "The argMapping candidate tree stops one level above the key column"
status: Backlog
bucket: architecture
priority: 5
theme: model-cleanup
depends-on: [derived-read-cost-is-a-shape-problem]
created: 2026-08-31
last-updated: 2026-09-03
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

## What R876 settled, and the one thing it found in the way

The candidate tree is now keyed by the schema coordinate the directive sits on and holds every legal
spelling at it, so "one more level than the tree has" is no longer a ranked prefix probe. A written
path either names a candidate or its head does, and the leftover name is one column,
`graphitron_argmapping_match.trailing_name`. The trailing count is gone and with it the sixth
verdict, `TRAILING_SEGMENTS_BEYOND_ONE`: a path spelling two names past what it opened is a spelling
nothing at the coordinate has, so it is refused with every other unresolvable spelling rather than
carrying a count. That leaves this item one job, which is the one it was always about: making
`trailing_name` resolve to a row instead of being handed on.

**The obstacle is ordering, not ownership.** `meta_gatherer_dependency` declares
`('graphitron', 'catalog')`, and `CatalogFactCapture` does flush before `ArgMappingCandidates.derive`
inside the same transaction, so the catalog is readable there. What is not readable is
`intent_resolved_node_key_column`, because two of the relations under it, `intent_spelled_table` and
`intent_resolved_type_binding`, are materialized tables that `Materializations.refresh` refills
*after* the hand-written producers run. A candidate tree seeded from that view at capture cadence
reads the previous run's key columns, or on a fresh store no key columns at all.

Three ways out, and the choice is this item's:

* read the `_live` views instead, which is correct and evaluates the whole binding rule once per
  candidate seed;
* move `ArgMappingCandidates.derive` after the refresh, which makes a hand-written producer depend
  on the registered order that R876 is trying to remove;
* register the candidate relation in `meta_materialize`, which is lever four and the one R876's
  lever order puts last.

None is obviously right, which is why this stayed its own item rather than riding along.

