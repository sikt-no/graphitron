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

The file is factored along the wrong axis. Today the top-level dispatch is parent-context-first (`classifyField` routes through `classifyRootField` to `classifyQueryField`, `classifyMutationField`, `classifyChildFieldOnResultType`, `classifyChildFieldOnTableType`, `classifyChildFieldOnErrorType`, `classifyObjectReturnChildField`). Each arm then handles every cross-cutting concern itself: it resolves directives, computes orderBy specs, builds condition filters, derives pagination, projects lookup mappings, classifies inputs.

That factoring leaves every cross-cutting concern in one of two suboptimal states:

1. *Resolved inline within each parent-context arm*, producing duplication. The four directives (`@service`, `@tableMethod`, `@externalField`, `@lookupKey`) are in this state.
2. *Factored as a private method of `FieldBuilder`*, avoiding duplication but bundling unrelated concerns into one class with one test surface. OrderBy, pagination, conditions, lookup-mapping projection, input-field classification, mutation-input classification, and conflict detection are in this state.

`ArgumentRef` is the one cross-cutting concern in the file's surroundings that has been factored along the right axis: each argument is classified once into a sealed variant, then projected exhaustively into `GeneratedConditionFilter`, `LookupMapping`, `OrderBySpec`, `PaginationSpec`. Principle 7 ("Builder-internal sealed hierarchies for multi-target classification") in `rewrite-design-principles.adoc` endorses exactly this shape. `FieldBuilder` as a whole is not in it.

## Cross-cutting concerns visible in the file

Each of these concerns is homologous to `ArgumentRef`: a focused input, a sealed result, no genuine dependency on `FieldBuilder` instance state beyond `BuildContext` and `ServiceCatalog`.

### Directive resolution (state 1: inline duplication)

- `@service` resolution + validation + construction in four arms: `classifyQueryField`, `classifyMutationField`, `classifyChildFieldOnResultType`, `classifyChildFieldOnTableType`. Each arm carries its own `resolveServiceField` call, error chain, and `switch (svcResult.returnType())` dispatch with a polymorphic-not-supported `default` rejection. The rejection string is byte-identical across all four arms; a sibling `@record type returning a polymorphic type is not yet supported` rejection in the same shape lives in the result-type arm.
- `@tableMethod` is handled in `classifyQueryField` and `classifyChildFieldOnTableType`; each calls `parseExternalRef` + `svc.reflectTableMethod` with the same shape and similar follow-on validation.
- `@externalField` is handled in `classifyChildFieldOnTableType`; `@lookupKey` is handled in two distinct arms (`classifyQueryField` and the lookup-key path inside `classifyChildFieldOnTableType`).

### Projection concerns (state 2: bundled monolith)

- *OrderBy resolution* (~400 lines across `resolveOrderByArgSpec`, `resolveDefaultOrderSpec`, `resolveColumnOrderSpec`, `resolveIndexColumns`, `resolveOrderEntries`).
- *Pagination resolution* (`projectPaginationSpec`, `isPaginationArg`, `resolveDefaultFirstValue`).
- *Condition resolution* (~200 lines across `buildArgCondition`, `buildFieldCondition`, `rewrapForNested`, plus the enum sub-helpers `buildTextEnumMapping` / `validateEnumFilter`).
- *Lookup-mapping projection* (`projectForLookup`).
- *Input-field classification* (`classifyPlainInputFields`).
- *Mutation-input classification* (~120 lines across `classifyMutationInput`, `validateMutationReturnType`, `getMutationTypeName`).
- *Conflict detection* (`detectQueryFieldConflict`, `detectChildFieldConflict`).

## The pivot

Factor each cross-cutting concern as its own resolver returning a sealed `Resolved<X>`. The classify arms shrink to orchestrators: given a (fieldDef, parentType), call the relevant resolvers and project the resolutions into the appropriate variant.

Likely shape:

*Directive resolvers* (eliminate inline duplication and byte-identical rejection strings):

- `ServiceDirectiveResolver` (`@service`: method lookup, return-type classification, polymorphic-not-supported rejection).
- `TableMethodDirectiveResolver` (`@tableMethod`: argMapping, `svc.reflectTableMethod`, expected-return-class strict check).
- `ExternalFieldDirectiveResolver` (`@externalField`: methodName defaulting, parent-table-class check).
- `LookupKeyDirectiveResolver` (`@lookupKey`: target-table check, mapping projection).

*Projection resolvers* (lift bundled bulk into focused units, each independently testable):

- `OrderByResolver`
- `PaginationResolver`
- `ConditionResolver`
- `LookupMappingResolver`
- `InputFieldResolver`
- `MutationInputResolver`

*Cross-arm validators* (light): conflict detection extracts as `ConflictDetector` if testability justifies it; otherwise stays as static helpers.

After the extraction, `FieldBuilder` is a small coordinator. Each parent-context arm calls a fixed pipeline of resolvers, then projects into the correct variant. Aligning the arms structurally also surfaces shared shape for free: any arm that builds a `TableField`-shaped variant always needs OrderBy + Pagination + Condition resolutions, so that combination can be packaged once. Today the same combination is open-coded across `resolveTableFieldComponents` callers; after the pivot, it's one named pipeline.

## Approach

Land one resolver at a time. Each is its own commit, mergeable independently:

1. *`ServiceDirectiveResolver`*. Highest duplication (4 inline resolution sites with 4 byte-identical rejection sites, plus the `@record` sibling), so the proof-of-pattern diff is largest and easiest to argue. After this lands, the shape is established and the rest is mechanical.
2. The other directive resolvers (`@tableMethod`, `@externalField`, `@lookupKey`), in any order.
3. *`OrderByResolver`*. Largest single projection concern (~400 lines), highest testability win, and once extracted, `resolveTableFieldComponents` shrinks substantially.
4. The remaining projection resolvers (`Pagination`, `Condition`, `LookupMapping`, `InputField`, `MutationInput`).
5. Final mop-up: helpers move with their nearest resolver, conflict detection extracts if the testability win justifies it.

## Out of scope

The earlier `QueryFieldBuilder` / `MutationFieldBuilder` / `ChildFieldBuilder` file-split idea is dropped. With every cross-cutting concern lifted into its own file, the remaining classify arms are short and genuinely context-specific; a parent-context-axis split would just shuffle small arms across files. If size remains painful afterwards, file a fresh Backlog item with fresh evidence.

## Notes

Size figures audited 2026-05-01 against trunk (`FieldBuilder.java`); refresh on each plan revision rather than letting the prose drift.
