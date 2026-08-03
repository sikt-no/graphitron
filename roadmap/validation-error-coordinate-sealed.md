---
id: R577
title: "Sealed Coordinate component on ValidationError"
status: Backlog
bucket: architecture
priority: 5
theme: diagnostics
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# Sealed Coordinate component on ValidationError

`ValidationError.coordinate` is a nullable `String` whose grain consumers re-derive by dot-splitting.
`ValidationError.forType` and `forField` know the grain at construction and collapse it to a string
plus `null` for schema-wide, and then `WatchErrorFormatter` reconstructs it with `isTypeLevel` /
`typeOf` predicates. A third fact about the same slot, that warnings carry no coordinate so a
coordinate filter excludes them by construction, lives only as a comment in `DiagnosticsTool`. A
sealed `Coordinate { SchemaWide | TypeLevel | FieldLevel }` component would make the grain a read
slot instead of a parse, delete the formatter's predicates, and turn the warnings invariant into a
type fact.

Carved out of the aggregated-diagnostics MCP work at Spec review, which surfaced the lift but does
not depend on it: with `coordinate` still a string, that item's coordinate-reading dimensions do the
dot-split in exactly one extractor, and one site is not the duplication this lift exists to remove.

Blast radius, measured at carve-out (re-measure at pickup): `WatchErrorFormatter` (the `isTypeLevel`
/ `typeOf` deletes), `DiagnosticsTool`'s filter comparison and wire `putIfNotNull`, and six
`graphitron` test files asserting on coordinate strings (`GraphitronSchemaBuilderTest`,
`ConditionCommandsPipelineTest`, `ConnectionTypeValidationTest`, `TenantScopeValidationTest`,
`NodeIdPipelineTest`, and the typed-rejection pipeline test). Spans `graphitron`,
`graphitron-maven-plugin`, and `graphitron-mcp`.
