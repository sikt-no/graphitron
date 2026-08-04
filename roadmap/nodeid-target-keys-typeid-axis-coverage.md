---
id: R583
title: "Pin the typeId axis of name-first resolveTargetKeys on the jOOQ-record input-bean path"
status: Backlog
bucket: cleanup
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-04
last-updated: 2026-08-04
---

# Pin the typeId axis of name-first resolveTargetKeys on the jOOQ-record input-bean path

`BuildContext.resolveTargetKeys` reads the `NodeIndex` by-name entry ahead of the backing table's
`KjerneJooqGenerator` metadata, so a `@nodeId(typeName:)` leaf takes both `typeId` and `keyColumns` from
the named type's own reconciled `@node`. The `keyColumns` axis is pinned
(`NodeIdPipelineTest.InputCase.EXPLICIT_TYPENAME_TAKES_KEY_ORDER_FROM_THE_NAMED_NODE`). The `typeId` axis
is not: reverting the read order and running the whole `graphitron` module (3120 tests) breaks only the
key-order case, so nothing would notice the regression.

The unpinned axis is reachable and observable. `resolveNodeIdRecordDecode` feeds `keys.typeId()` straight
into the emitted `decodeValues(typeId, …)` call on the jOOQ-record `@service` input-bean path, and that is
the wire-format prefix, so a metadata-first read emits the wrong prefix in two shapes: two `@node` types
over one table both publishing the table's `typeId` instead of their own, and (even with a single `@node`)
a `@node(typeId:)` SDL override being discarded in favour of the table's `__NODE_TYPE_ID`. Add a
`NodeIdRecordInputBeanPipelineTest` case with `@node(typeId:)` overriding a metadata-carrying table's
`__NODE_TYPE_ID` and assert the resolved `typeId`, which pins the SDL-wins reconciliation
`TypeBuilder.classifyNode` performs; a sibling two-`@node` case pins the multi-node half. Model-level
assertion on the resolved record, not a code-string assertion on the generated body.
