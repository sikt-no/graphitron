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

`FieldBuilder.java` is 2,825 lines with ~67 member declarations, and growing fast (the file added ~600 lines in the two days between the 2026-04-28 audit and the 2026-04-30 refresh). Argument-resolution unification has shipped (Phase 4 landed under Done), so this is no longer blocked.

The dispatch is already taxonomy-shaped: `classifyField` routes through `classifyRootField` to `classifyQueryField`, `classifyMutationField`, and the `classifyChildField*` arms, along the same lines as the proposed `QueryFieldBuilder` / `MutationFieldBuilder` / `ChildFieldBuilder` split. Most of the bulk lives in two places: long classify-arm bodies (e.g. `classifyChildFieldOnTableType` ~300 lines, `resolveOrderByArgSpec` ~250 lines, `classifyQueryField` ~140 lines, `classifyMutationField` ~90 lines), and shared infrastructure (orderBy specs, pagination, condition filters, input classification, enum mappings) that all three taxonomy branches consume. A taxonomy-based file split would relocate the classify methods and force a shared-helpers module without changing the structural picture; the win is navigational, not architectural.

Phase 1 (the within-arm refactor) is the value-bearing piece and should land regardless. Phase 2+ (the file split) is deferred: revisit once Phase 1 lands and we can see whether the remaining file size still justifies the relocation cost.

Size figures audited 2026-04-30 against trunk (`FieldBuilder.java`); refresh on each plan revision rather than letting the prose drift.

## Phase 1: Extract semantic-check helpers from `classifyQueryField`

The codebase rejects malformed fields at classifier time by returning `UnclassifiedField`: polymorphic `@service` at `FieldBuilder.java:1787` (with sibling sites at `:1931`, `:2295`, `:2356`, `:2457` covering mutation and child-field arms); single-cardinality `@splitQuery @lookupKey` at `FieldBuilder.java:350` and multi-hop single-cardinality `@splitQuery` at `:376`; Connection / Sourced-param rejection on root `@service` and `@tableMethod` via `FieldBuilder.validateRootServiceInvariants` (defined at `:275`, called from `:1772` and `:1916`); the §3 strict-class and §5 strict-return checks inline in `classifyQueryField` and on `ServiceCatalog`.

The pattern is consistent and better than validator-time rejection for the "emitter sees only well-formed leaves" property, but it means `classifyQueryField` and the other classify arms accumulate semantic checks alongside shape dispatch. Phase 1 extracts per-directive helpers like `rejectInvalidService(fieldDef, svcResult) → Optional<UnclassifiedField>` and `rejectInvalidTableMethod(fieldDef, tb) → Optional<UnclassifiedField>`, so each classifier arm reads as "run semantic gates, then dispatch to the leaf". `validateRootServiceInvariants` is a partial example of this pattern; the broader extraction across all directive arms is the rest of Phase 1.

This phase stands on its own. It is independent of the file split, and worth doing whether or not Phase 2+ ever happens.

## Phase 2+ (deferred): Split the file along the field taxonomy

Proposed split: `QueryFieldBuilder`, `MutationFieldBuilder`, `ChildFieldBuilder`, plus a shared module for the cross-cutting helpers (orderBy, pagination, conditions, input classification, enum mappings). Revisit once Phase 1 lands. If the post-Phase-1 classify arms read cleanly and the file size feels manageable, Phase 2+ may not be worth doing at all; if pain remains, the post-Phase-1 shape (tight `gates → dispatch` per arm) makes the relocation mechanical.
