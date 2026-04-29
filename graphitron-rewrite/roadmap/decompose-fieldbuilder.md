---
id: R6
title: "Decompose `FieldBuilder`"
status: Backlog
bucket: architecture
priority: 2
theme: structural-refactor
depends-on: []
---

# Decompose `FieldBuilder`

Split the 2,217-line / 56-private-method builder along the field taxonomy. Argument-resolution unification has shipped (Phase 4 landed under Done), so this is no longer blocked. Proposed split: `QueryFieldBuilder`, `MutationFieldBuilder`, `ChildFieldBuilder` plus a shared argument-classification module.

Size figures audited 2026-04-28 against trunk (`FieldBuilder.java`); refresh on each plan revision rather than letting the prose drift.

## Phase 1: Extract semantic-check helpers from `classifyQueryField`

The codebase rejects malformed fields at classifier time by returning `UnclassifiedField`: polymorphic `@service` at `FieldBuilder.java:1305-1306`; single-cardinality `@splitQuery @lookupKey` and multi-hop single-cardinality `@splitQuery` at `FieldBuilder.java:252-257` / `:266-271`; Connection / Sourced-param rejection on root `@service` and `@tableMethod` via `FieldBuilder.validateRootServiceInvariants`; the §3 strict-class and §5 strict-return checks inline in `classifyQueryField` and on `ServiceCatalog`.

The pattern is consistent and better than validator-time rejection for the "emitter sees only well-formed leaves" property, but it means `classifyQueryField` accumulates semantic checks alongside shape dispatch. Phase 1 extracts per-directive helpers like `rejectInvalidService(fieldDef, svcResult) → Optional<UnclassifiedField>` and `rejectInvalidTableMethod(fieldDef, tb) → Optional<UnclassifiedField>`, so each classifier arm reads as "run semantic gates, then dispatch to the leaf". `validateRootServiceInvariants` is a partial example of this pattern; the broader extraction across all directive arms is the rest of Phase 1.

This is the within-arm refactor; Phases 2+ split the file along the field taxonomy. The two are independent in principle — either could land first — but doing Phase 1 first makes the post-split files cleaner because each `classify*Field` method ends with a tight `gates → dispatch` shape rather than dragging inline checks through the move.
