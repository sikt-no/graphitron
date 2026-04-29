---
id: R24
title: "`NodeIdReferenceField` JOIN-projection form"
status: Backlog
bucket: cleanup
priority: 13
theme: nodeid
depends-on: [lift-nodeid-out-of-model]
---

# `NodeIdReferenceField` JOIN-projection form

The `@nodeId` + `@node` plan shipped the FK-mirror collapse path (single-hop FK whose target columns positionally match the target NodeType's `keyColumns`; the parent's FK source columns encode directly with no JOIN). Composite-FK that doesn't mirror, multi-hop, and condition-join cases emit a runtime `UnsupportedOperationException` stub today (`FetcherEmitter#dataFetcherValue`'s `NodeIdReferenceField` arm).

Lift to a real correlated-subquery emission projecting the target's `nodeKeyColumns` under aliases when a real schema needs it; the test fixture would have to grow a multi-hop or non-mirroring FK shape to exercise it.

Subsumed by R50: once `NodeIdReferenceField` retires in favour of a column-shaped variant with `NodeIdEncodeKeys`, the multi-hop / non-mirroring case is just a column projection over the JOINed target's key columns — no `NodeIdReferenceField` arm to dispatch, no stub to lift. Re-evaluate this item once R50 lands; likely retire it.
