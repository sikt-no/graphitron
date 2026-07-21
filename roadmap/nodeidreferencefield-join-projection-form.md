---
id: R24
title: "Rooted-at-parent NodeId reference JOIN-projection emitter (ColumnBackedReferenceField, both arities)"
status: Backlog
bucket: cleanup
priority: 13
theme: nodeid
depends-on: []
---

# Rooted-at-parent NodeId reference JOIN-projection emitter

(Title symbol re-anchored 2026-07-13 and again 2026-07-21: the original `NodeIdReferenceField` carrier was renamed by R50 to the column-shaped `ChildField.ColumnReferenceField` / `CompositeColumnReferenceField` pair, and R508 dissolved that pair into the single merged `ChildField.ColumnBackedReferenceField` carrying arity as a column count; the file keeps its slug as its own identity. The in-code deferral no longer carries a slug pointer: it anchors to this shape through its `Rejection.StubKey.VariantClass` key, a live class reference rather than a roadmap path.)

R50 shipped two of the three rooted shapes named in *Variant-by-variant collapse → Single-hop emission, two shapes*: rooted-at-child emission (FK-mirror, no JOIN, parent's FK columns encode directly) and the classifier-side resolution for rooted-at-parent (phase g-B produces `ChildField.ColumnReferenceField` / `CompositeColumnReferenceField` with `compaction = NodeIdEncodeKeys` and a resolved `joinPath`). What did *not* ship is the matching emitter. The rooted-at-parent shape now surfaces as one build-time typed deferral regardless of arity, an improvement over the runtime `UnsupportedOperationException` stubs the original write-up described: since R508 merged the two carriers, `GraphitronSchemaValidator.validateColumnBackedReferenceField` rejects the `NodeIdEncodeKeys` compaction on `ChildField.ColumnBackedReferenceField` as deferred at every arity ahead of generation (the old `TypeFetcherGenerator.STUBBED_VARIANTS` stub entry retired with the composite class; the map is empty today), keyed by the merged class through its `StubKey.VariantClass` anchor.

R24 absorbs the remaining emitter work, expanded beyond its original framing:

- **Rooted-at-parent single-hop JOIN-with-projection** — the FK is one hop, but its source columns differ from the target NodeType's `keyColumns` (e.g. the FK references the parent through a non-PK unique constraint, or the parent's NodeId uses columns the FK doesn't reach). The emitter brings the parent table into scope via JOIN, threads the carrier's `joinPath` through `$fields()`, and projects `encode<TypeName>(parent_alias.k1, ..., parent_alias.kN)`. This shape has a fixture in `nodeidfixture` (R50 phase g-B's `parent_node` + `child_ref` tables) ready to drive execution-tier coverage as soon as the emitter lands.
- **Multi-hop FK and condition-join correlated-subquery emission** — the original R24 scope. Anything past one hop, or where the FK is replaced by a condition-join, wants a correlated subquery rooted at the parent, projecting the parent's `keyColumns` under aliases. No fixture yet; the test surface grows when a real schema reaches the shape.

Both arities route through the one merged carrier (`ChildField.ColumnBackedReferenceField` with `compaction = NodeIdEncodeKeys`, columns 1..N); the differentiator is *how* the target columns are brought into scope (single-hop JOIN vs correlated subquery), not the carrier shape. The emitter switches on the resolved `joinPath`'s shape.

Re-spec when a real schema reaches one of the shapes; pin the load-bearing structure (multi-hop path, condition-join path, non-mirroring FK) here before re-spec'ing. R50 (`lift-nodeid-out-of-model`, shipped) introduced the column-shaped carriers (since merged by R508 into `ChildField.ColumnBackedReferenceField`) with `compaction = CallSiteCompaction.NodeIdEncodeKeys` and the rooted-at-parent fixture in `nodeidfixture` (`parent_node` + `child_ref`); the changelog entry preserves the originating context.

## Coordination with R40 (shipped)

R40 (argument-level `@nodeId`, Done; its item file is deleted, see the changelog) was the symmetric input-side feature: argument-level `@nodeId` decoding for both same-table (lookup) and FK-target (filter) shapes. Its column-backed reference-arg carriers live in `ArgumentRef.java` today (merged by R508 into `ScalarArg.ColumnBackedReferenceArg`). R24 and R40 thread the same `joinPath: List<JoinStep>` (R50 phase g-B) and share the `parent_node` + `child_ref` execution fixture, but they do *not* share emitter code (R24 = output-side encode emitter; R40 = input-side decode + filter projection). When R24 lands its JOIN-with-projection emitter, any `joinPath` threading helper that falls out may be worth lifting to a place the shipped input-side filter projection can also reach. The pathological FK-target input-side case remains separately filed as R57 (`nodeid-fk-target-arg-join-translation`).
