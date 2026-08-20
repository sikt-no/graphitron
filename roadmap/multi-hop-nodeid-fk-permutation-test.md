---
id: R135
title: "Multi-hop @nodeId pipeline test for FK-target/NodeType-keyColumns permutation"
status: Backlog
bucket: validation
priority: 4
theme: testing
depends-on: []
---

# Multi-hop @nodeId pipeline test for FK-target/NodeType-keyColumns permutation

R131's permutation relaxation in `NodeIdLeafResolver.resolve` accepts set-equality between the terminal hop's target columns and the NodeType's `@node(keyColumns:)`, then permutes `liftedSourceColumns` into NodeType-keyColumns order before constructing `Resolved.FkTarget.DirectFk`. The pipeline-tier test pinning this lands on the single-hop `reordered_pk_parent` fixture (`InputFieldFkTargetNodeIdCase.FK_TARGET_REORDERED_KEY_PERMUTATION_DIRECT_FK{,_SINGULAR}`).

R131's commit message asserts the same logic works for multi-hop `@reference` paths where the terminal hop's target is a permutation of the NodeType keys: the per-hop `validateLift` invariant still requires positional alignment at each intermediate step, the lift back-propagation runs in terminal-source-side order, and the final permute step re-orders into NodeType-keyColumns order. The reasoning is sound but no fixture currently exercises it.

The gap to close: add a multi-hop `nodeidfixture` chain where the terminal hop's `REFERENCES <parent>(<cols>)` declares the parent's PK columns in a permuted order. A natural extension of the `level_a`/`level_b`/`level_c` chain works: add `level_a_alt`-style intermediate(s) whose declared FK target order against `level_a` is `(k2, k1)` rather than `(k1, k2)`, then pin that the composite `InputField.ColumnBackedReferenceField.liftedSourceColumns()` ends in `[k1, k2]` order on the parent's own table (matching the NodeType's `[K1, K2]` declaration).

Acceptance:

- One new pipeline-tier case in `NodeIdPipelineTest` (input-field side) using a 2-hop chain with a permuted terminal hop, asserting `Resolved.FkTarget.DirectFk` is picked (not `TranslatedFk`) and `liftedSourceColumns` is in NodeType-keyColumns order.
- Optionally a mirror argument-side case in `ArgumentFkTargetNodeIdCase`.
- No new emitter work — the existing `BodyParam.{RowEq,RowIn}` emission consumes `liftedSourceColumns` positionally and is unaffected by where the permutation entered.

Out of scope:

- Relaxing the per-hop `validateLift` predicate to allow intra-chain permutations. Restated below: the predicate stops being a rejection under the relation move, so this carve-out no longer describes the alternative it was declining.

## Two re-anchors (2026-08-20)

Both are to the framing; the test this item asks for is unaffected and stays open.

**The carrier accessor moved.** `InputField.ColumnBackedReferenceField.liftedSourceColumns()` is
gone; `liftedSourceColumns` survives only on `NodeIdLeafResolver.Resolved.FkTarget.DirectFk`, and
the emitted tuple is now `FilterBinding.Local`. So the acceptance bullet's "the composite
`InputField.ColumnBackedReferenceField.liftedSourceColumns()` ends in `[k1, k2]` order" and the
`BodyParam.{RowEq,RowIn}` sentence want re-pointing at `FilterBinding.Local`. The two
resolver-side cites in the body are current. (From the same-day staleness audit.)

**`validateLift` stops being a rejection.** This item leans twice on the per-hop lift invariant
being a gate that fails a misaligned chain: once in the body and once in the carve-out above.
The `@nodeId` relation move relaxes it, so an absent lift becomes absent local columns and the
chain binds remotely through the hop-general `EXISTS` instead of rejecting. The fixture this item
specifies is built to *satisfy* the lift at every intermediate hop, so its acceptance criterion
survives intact and still discriminates `DirectFk` from the remote arm; what stops being true is
the description of what happens to a chain that does not satisfy it, which is what the carve-out
was declining to change. Restate the carve-out as "intra-chain permutation still is not
recognized, it now binds remotely rather than rejecting" once that lands.

Detail: `roadmap/audits/2026-08-20-nodeid-relation-impact-sweep.md`, Finding 4.
