---
id: R24
title: "`NodeIdReferenceField` JOIN-projection form"
status: Backlog
bucket: cleanup
priority: 13
theme: nodeid
depends-on: []
---

# `NodeIdReferenceField` JOIN-projection form

R50 shipped two of the three rooted shapes named in *Variant-by-variant collapse → Single-hop emission, two shapes*: rooted-at-child emission (FK-mirror, no JOIN, parent's FK columns encode directly) and the classifier-side resolution for rooted-at-parent (phase g-B produces `ChildField.ColumnReferenceField` / `CompositeColumnReferenceField` with `compaction = NodeIdEncodeKeys` and a resolved `joinPath`). What did *not* ship is the matching emitter: `FetcherEmitter#dataFetcherValue` carries runtime `UnsupportedOperationException` stubs for both arms (lines 140-162), so a schema that reaches one of the rooted-at-parent shapes builds without a validator-side rejection but throws at runtime.

R24 absorbs the remaining emitter work, expanded beyond its original framing:

- **Rooted-at-parent single-hop JOIN-with-projection** — the FK is one hop, but its source columns differ from the target NodeType's `keyColumns` (e.g. the FK references the parent through a non-PK unique constraint, or the parent's NodeId uses columns the FK doesn't reach). The emitter brings the parent table into scope via JOIN, threads the carrier's `joinPath` through `$fields()`, and projects `encode<TypeName>(parent_alias.k1, ..., parent_alias.kN)`. This shape has a fixture in `nodeidfixture` (R50 phase g-B's `parent_node` + `child_ref` tables) ready to drive execution-tier coverage as soon as the emitter lands.
- **Multi-hop FK and condition-join correlated-subquery emission** — the original R24 scope. Anything past one hop, or where the FK is replaced by a condition-join, wants a correlated subquery rooted at the parent, projecting the parent's `keyColumns` under aliases. No fixture yet; the test surface grows when a real schema reaches the shape.

Both arms route through the same column-shaped carriers R50 introduced (`ChildField.ColumnReferenceField` for arity-1, `ChildField.CompositeColumnReferenceField` for arity > 1, both narrowed to `compaction = NodeIdEncodeKeys`); the differentiator is *how* the target columns are brought into scope (single-hop JOIN vs correlated subquery), not the carrier shape. The emitter switches on the resolved `joinPath`'s shape.

Re-spec when a real schema reaches one of the shapes; pin the load-bearing structure (multi-hop path, condition-join path, non-mirroring FK) here before re-spec'ing. R50 (`lift-nodeid-out-of-model`, shipped) introduced the `ChildField.ColumnReferenceField` / `CompositeColumnReferenceField` carriers with `compaction = CallSiteCompaction.NodeIdEncodeKeys` and the rooted-at-parent fixture in `nodeidfixture` (`parent_node` + `child_ref`); the changelog entry preserves the originating context.
