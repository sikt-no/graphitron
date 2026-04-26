---
title: "`NodeIdReferenceField` JOIN-projection form"
status: Backlog
bucket: cleanup
priority: 13
---

# `NodeIdReferenceField` JOIN-projection form

The `@nodeId` + `@node` plan shipped the FK-mirror collapse path (single-hop FK whose target columns positionally match the target NodeType's `keyColumns`; the parent's FK source columns encode directly with no JOIN). Composite-FK that doesn't mirror, multi-hop, and condition-join cases emit a runtime `UnsupportedOperationException` stub today (`FetcherEmitter#dataFetcherValue`'s `NodeIdReferenceField` arm).

Lift to a real correlated-subquery emission projecting the target's `nodeKeyColumns` under aliases when a real schema needs it; the test fixture would have to grow a multi-hop or non-mirroring FK shape to exercise it.
