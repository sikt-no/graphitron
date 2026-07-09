---
id: R263
title: "Add a typeName-first decode-helper entry point so resolveDecodeHelperForTable is not a misuse trap"
status: Backlog
bucket: cleanup
priority: 3
theme: nodeid
depends-on: []
created: 2026-05-30
last-updated: 2026-05-30
---

# Add a typeName-first decode-helper entry point so resolveDecodeHelperForTable is not a misuse trap

`BuildContext.resolveDecodeHelperForTable` (`BuildContext.java:2111-2136`) resolves
the `decode<TypeName>` suffix from `findGraphQLTypeForTable(sqlTableName)` (singular,
`:1992`, `:2118`) and consults its `fallbackTypeNameOrTypeId` argument *only* on the
empty branch (`:2133`). A caller that holds an authoritative `@nodeId(typeName:)` and
passes it expecting it to drive the suffix is silently ignored whenever a `@table`
type backs the table; when several object types share that table the method yields
`decode<firstTypeForTable>`, not `decode<TypeName>`. The argument reads as
suffix-bearing but is not, so the misuse compiles and the wrong helper only surfaces
in the multi-type-per-table configuration, an awkward case to land in a test.

Surfaced during the R195 Spec review, where R195 needs exactly the typeName-first
direction (typeName → table → keys, suffix from typeName). R195 sidesteps the trap by
resolving the suffix from the typeName side directly and not calling this method; this
item is the structural fix so the next typeName-first caller does not have to know to
avoid it.

Shape: give the typeName→table→keys direction a clearly-named entry point distinct
from the table-first shim callers (`:1946`, `:2081`) — either a `resolveDecodeHelper(typeName, keyColumns)`
sibling, or a precedence change that prefers an explicitly-supplied typeName over the
table-derived one. Low priority: the `@nodeId` synthesis shim that drives the current
table-first callers is on a retirement track (`:2131`, see
`graphitron-rewrite/roadmap/retire-synthesis-shims.md`), so the table-first path may
thin out on its own.
