---
id: R815
title: "Answer the step-hop and binding registrations costing three other relations an order of magnitude"
status: Backlog
bucket: store
priority: 2
theme: nodeid
depends-on: []
created: 2026-08-23
last-updated: 2026-08-23
---

# Answer the step-hop and binding registrations costing three other relations an order of magnitude

Three `meta_materialize` registrations make some other relation's read more expensive, and the two
largest grow with the size of the schema rather than sitting at a fixed offset. This is the lever the
item that built `DerivedReadCostTest` handed on rather than pulled: that item's scope was the gate and
the attribution, and each of these is a change to the schema with its own trade to argue.

## What was measured

`DerivedReadCostTest` prices every pair of a registration and a relation whose derivation reaches its
target, in both shapes, over a populated capture fixture. Six of its 102 cells are non-monotonic and
sit in its pinned set. Three are the instrument's per-naming floor and are not this item's business.
The three below are.

[cols="3,2,2,2"]
|===
| Registration and reader | Registered scans | Unregistered scans | Ratio

| `intent_field_reference_step_hop` -> `intent_field_reference_step_target`
| 19260
| 598
| 32x

| `intent_field_reference_step_hop` -> `intent_field_column_scope_live`
| 20779
| 2117
| 10x

| `intent_resolved_type_binding` -> `intent_argument_scope_table_live`
| 2938
| 1269
| 2.3x
|===

The figures are at the gate's own fixture size of twelve node clusters. What separates them from a
fixed artifact is that they scale: at four clusters the same three pairs read -2102, -2102 and -141
scans, so the gap widens roughly with the schema rather than staying put.

## Why this is not simply reverted

The step-hop registration is the one that took a node-id decode read from about fifty seconds to
about thirteen, and the binding registration bought a diagnostics drain going from past seven minutes
to 191 ms and an inlay read from four times over its budget to inside it. Both are surfaces a
developer waits on. So the question is not whether to give those back. It is which term the harmed
readers reach that the helped ones do not.

The shape to look for is named in `docs/architecture/explanation/fact-model.adoc`: reading a
materialized target is a full scan charged once per naming, where the rule it replaced could be
pruned by a predicate the reader applies. A reader that was pruning cheaply pays the whole target
instead. That points the lever at the reader rather than underneath it, which is the opposite of the
depth rule's usual direction, and it is why this needs a spec rather than a patch.

## What a consumer's build pays today

Nothing observable from these three pairs yet, and the spec should not assume otherwise.
`intent_node_id_decode` has no Java reader. The sibling that is read on the build path is
`intent_node_id_decode_defect`, through `NodeIdDecodeDefects.detect` in `FactCapture`'s detection
pass, and at the gate's fixture it costs single-digit milliseconds where the decode itself costs
about 270. All seven registrations refresh together in about 106 ms per graph per capture, which is
the whole of what a registration costs a consumer on the write side. So this is filed as a latent
cost with its measurement attached rather than as a live regression, and the priority says so.

## What the gate will do when this lands

Its pinned set is asserted by equality, so removing a pair that has stopped being non-monotonic is
what makes the build green again. A fix that helps one of the three and not the others fails the test
until the set is edited to match, which is the intended ratchet rather than an obstacle.
