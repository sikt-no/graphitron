---
id: R6
title: "Decompose `FieldBuilder`"
status: Spec
bucket: architecture
priority: 2
theme: structural-refactor
depends-on: []
---

# Decompose `FieldBuilder`

`FieldBuilder.java` is 3,101 lines with ~76 member declarations, and growing fast (the file added ~880 lines between the 2026-04-28 audit and the 2026-05-01 refresh). Argument-resolution unification has shipped (Phase 4 landed under Done), so this is no longer blocked.

## The wrong axis

The file is factored along the wrong axis. Today the dispatch is parent-context-first (`classifyQueryField`, `classifyMutationField`, `classifyChildField*`), with directive resolution and validation duplicated inside each arm:

- `@service` resolution + validation + construction in four arms: `classifyQueryField`, `classifyMutationField`, `classifyChildFieldOnResultType`, `classifyChildFieldOnTableType`. Each arm carries its own `resolveServiceField` call, error chain, and `switch (svcResult.returnType())` dispatch with a polymorphic-not-supported `default` rejection. The rejection string is byte-identical across all four arms; a sibling `@record type returning a polymorphic type is not yet supported` rejection in the same shape lives in the result-type arm.
- `@tableMethod` is handled in `classifyQueryField` and `classifyChildFieldOnTableType`; each calls `parseExternalRef` + `svc.reflectTableMethod` with the same shape and similar follow-on validation.
- `@externalField` is handled in `classifyChildFieldOnTableType`; `@lookupKey` is handled in two distinct arms (`classifyQueryField` and the lookup-key path inside `classifyChildFieldOnTableType`).

The arms genuinely differ on parent-PK columns, root invariants, the service-reconnect path, and which target variant gets built, but those are context-specific *projections* over a shared *resolution*. The current factoring buries the resolution inside each context, which is the pattern principle 7 ("Builder-internal sealed hierarchies for multi-target classification") rejects.

`ArgumentRef` is the canonical example of the right shape: each argument is classified once into a sealed variant, then projected exhaustively into `GeneratedConditionFilter`, `LookupMapping`, `OrderBySpec`, `PaginationSpec`. `FieldBuilder` is in the pre-`ArgumentRef` shape: multiple independent passes that implicitly coordinate by skipping each other's inputs.

## The pivot

Factor along the directive axis. Each domain directive gets one resolver returning a sealed `Resolved<X>` that captures success, failure, and not-yet-supported uniformly. Likely set:

- `ServiceDirectiveResolver` (resolves `@service`: method lookup, return-type classification, polymorphic-not-supported rejection).
- `TableMethodDirectiveResolver` (resolves `@tableMethod`: argMapping, `svc.reflectTableMethod`, expected-return-class strict check).
- `ExternalFieldDirectiveResolver` (resolves `@externalField`: methodName defaulting, parent-table-class check).
- `LookupKeyDirectiveResolver` (resolves `@lookupKey`: target-table check, mapping projection).

Each parent-context arm shrinks to projection: given a `ResolvedService` and a parent context, build the variant the arm is responsible for. The duplicated rejections collapse structurally to one site each. New directives become single-site additions instead of N parallel edits.

Suggested order: start with `@service`. It has the most duplication (4 resolution sites and 4 byte-identical rejection sites, plus a sibling `@record` rejection sharing the shape), so the proof-of-pattern diff is the largest and easiest to argue. The other resolvers follow once the shape is established.

## Out of scope

The earlier `QueryFieldBuilder` / `MutationFieldBuilder` / `ChildFieldBuilder` file-split idea is dropped. Once directive resolvers are extracted, the remaining classify arms are short and genuinely context-specific; the file-split question goes away on its own. If size is still painful afterwards, file a fresh Backlog item with fresh evidence.

## Notes

Size figures audited 2026-05-01 against trunk (`FieldBuilder.java`); refresh on each plan revision rather than letting the prose drift.
