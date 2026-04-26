---
title: "Extract semantic-check helpers from `classifyQueryField`"
status: Backlog
bucket: architecture
priority: 3
---

# Extract semantic-check helpers from `classifyQueryField`

The codebase rejects malformed fields at classifier time by returning `UnclassifiedField` (polymorphic `@service` at `FieldBuilder.java:1305-1306`; single-cardinality `@splitQuery @lookupKey` and multi-hop single-cardinality `@splitQuery` at `FieldBuilder.java:252-257` / `:266-271`; Connection / Sourced-param rejection on root `@service` and `@tableMethod` via `FieldBuilder.validateRootServiceInvariants`, plus the §3 strict-class and §5 strict-return checks inline in `classifyQueryField` and on `ServiceCatalog`).

The pattern is consistent and better than validator-time rejection for the "emitter sees only well-formed leaves" property, but it means `classifyQueryField` accumulates semantic checks alongside shape dispatch. Refactor: extract per-directive helpers like `rejectInvalidService(fieldDef, svcResult) → Optional<UnclassifiedField>` and `rejectInvalidTableMethod(fieldDef, tb) → Optional<UnclassifiedField>`, so each classifier arm reads as "run semantic gates, then dispatch to the leaf". `validateRootServiceInvariants` is a partial example of this pattern; the broader extraction across all directive arms remains outstanding.

Orthogonal to *Decompose `FieldBuilder`* above: that splits by field taxonomy; this refactors within each arm. Not urgent; do it when a new rejection would push the file past a readability threshold.
