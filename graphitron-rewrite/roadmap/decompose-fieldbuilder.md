---
id: R6
title: "Decompose `FieldBuilder`"
status: In Review
bucket: architecture
priority: 2
theme: structural-refactor
depends-on: []
---

# Decompose `FieldBuilder`

`FieldBuilder.java` is 3,077 lines with ~71 member declarations after Phase 1 lifted `@service` resolution into [`ServiceDirectiveResolver`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/ServiceDirectiveResolver.java), Phase 2a lifted `@tableMethod` into [`TableMethodDirectiveResolver`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/TableMethodDirectiveResolver.java), and Phase 2b lifted `@externalField` into [`ExternalFieldDirectiveResolver`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/ExternalFieldDirectiveResolver.java). Argument-resolution unification has shipped (Phase 4 landed under Done), so this is no longer blocked.

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

- `ServiceDirectiveResolver` (`@service`: method lookup, return-type classification, root invariants, errors-channel lift, polymorphic-not-supported rejection). **Phase 1 — shipped.**
- `TableMethodDirectiveResolver` (`@tableMethod`: argMapping, `svc.reflectTableMethod`, expected-return-class strict check, root-only Connection / non-table-bound rejections). **Phase 2a — shipped.**
- `ExternalFieldDirectiveResolver` (`@externalField`: alias-collision check, methodName defaulting, parent-table-class check via `svc.reflectExternalField`). **Phase 2b — shipped.**
- `LookupKeyDirectiveResolver` (`@lookupKey`: target-table check, mapping projection).

The shipped Phase 1 resolver returns a sealed `Resolved` with a three-arm `Success` sub-interface (`TableBound` / `Result` / `Scalar`, each carrying the resolved `MethodRef` and typed `ReturnTypeRef`), an `ErrorsLifted` arm for the polymorphic-of-`@error` lift, and a `Rejected(kind, message)` arm absorbing every error path (parse failure, root-invariants failure, polymorphic-not-supported). Each classify arm projects `Success` into its specific variant (`QueryServiceTableField` / `MutationServiceTableField` / `ServiceTableField`) since variant identity differs across parent contexts. Parent-context-only concerns (join-path parse, the `@record`-typed-parent DEFERRED rejection on result-type parents) stay in the classify arm. Subsequent directive resolvers should follow the same `Resolved`/`Success` shape, keeping per-arm projection out of the resolver.

Phase 2a's `TableMethodDirectiveResolver` follows the same shape with three `Resolved` arms (`TableBound` / `NonTableBound` / `Rejected`), gated by an `isRoot` parameter so the root-only invariants (Connection rejection, non-`TableBound` rejection) fire only at root sites; child sites pass `false` and accept `NonTableBound` returns (today a deferred stub via R43, but the resolver is shape-ready). The root call site asserts `NonTableBound` is unreachable via an `IllegalStateException` arm — sealed exhaustiveness forces the unreachable case to be acknowledged at the call site, which is the idiomatic Java pattern for "this branch is gated upstream." Path-parse for child `@tableMethod` stays in the classify arm (parent-context concern), running before the resolver so a path error surfaces ahead of any reflection failure.

Phase 2b's `ExternalFieldDirectiveResolver` is the simplest of the directive resolvers (single classify site, no `isRoot` axis, no errors-channel lift). It carries a two-arm `Resolved` (`Success(returnType, method)` / `Rejected`) and absorbs the alias-collision check, the external-reference parse + missing-className rejection, and the `svc.reflectExternalField` call against the parent table's Java class. The classify arm shrank from ~45 lines to ~10 lines: it parses the join path (parent-context concern: uses the parent table's name as the join start, and a path error must surface ahead of reflection failure), then switches over `Resolved` to project `Success` into `ComputedField` carrying the parsed path.

Helpers shared by directive resolvers (`parseExternalRef`, `parseContextArguments`, `buildWrapper`, `enrichArgExtractions`, `liftToErrorsField`, `fieldArgumentNames`) are package-private members of `FieldBuilder` today; all three resolvers hold a reference to `FieldBuilder` and call back through them. With three consumers now sharing them, the still-shared helpers (`parseExternalRef`, `fieldArgumentNames`) become candidates to migrate to a common location (likely `BuildContext`) in the final mop-up phase.

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

1. *`ServiceDirectiveResolver`* — **shipped.** Lifted `@service` resolution out of all four arms (`classifyQueryField`, `classifyMutationField`, `classifyChildFieldOnResultType`, `classifyChildFieldOnTableType`); each arm now switches over a sealed `Resolved` to project into its specific variant. The shape (helpers stay package-private on `FieldBuilder`, callback into them through a `FieldBuilder` reference, parent-context concerns stay in the arm) is the template the other directive resolvers should follow.
2. *`TableMethodDirectiveResolver`* — **shipped (Phase 2a).** Lifted `@tableMethod` resolution out of both classify sites (`classifyQueryField`, `classifyChildFieldOnTableType`); each site shrank from ~45 lines to ~10. Sealed `Resolved` carries `TableBound` / `NonTableBound` / `Rejected`, gated by an `isRoot` flag so root-only invariants fire only at root.
3. *`ExternalFieldDirectiveResolver`* — **shipped (Phase 2b).** Lifted `@externalField` resolution out of `classifyChildFieldOnTableType`; the inline block shrank from ~45 lines to ~10. Sealed `Resolved` carries `Success(returnType, method)` / `Rejected`. Absorbs the alias-collision check, external-ref parse, missing-className rejection, and `svc.reflectExternalField`; path-parse stays in the classify arm (it's a parent-context concern and must run ahead of any reflection failure).
4. *`LookupKeyDirectiveResolver`* — remaining directive resolver.
5. *`OrderByResolver`*. Largest single projection concern (~400 lines), highest testability win, and once extracted, `resolveTableFieldComponents` shrinks substantially.
6. The remaining projection resolvers (`Pagination`, `Condition`, `LookupMapping`, `InputField`, `MutationInput`).
7. Final mop-up: helpers move with their nearest resolver, conflict detection extracts if the testability win justifies it.

## Out of scope

The earlier `QueryFieldBuilder` / `MutationFieldBuilder` / `ChildFieldBuilder` file-split idea is dropped. With every cross-cutting concern lifted into its own file, the remaining classify arms are short and genuinely context-specific; a parent-context-axis split would just shuffle small arms across files. If size remains painful afterwards, file a fresh Backlog item with fresh evidence.

## Notes

Size figures audited 2026-05-01 against trunk (`FieldBuilder.java`); refresh on each plan revision rather than letting the prose drift. Phase 1 dropped `FieldBuilder` from 3,301 → 3,163 lines (-138) and added 238 lines in `ServiceDirectiveResolver`; the four classify arms shrank substantially (each `@service` block went from ~25 lines to ~12 lines) and three helpers (`resolveServiceField`, `validateRootServiceInvariants`, `computeExpectedServiceReturnType`) plus the `ServiceResolution` record moved out. Phase 2a dropped `FieldBuilder` from 3,163 → 3,103 lines (-60) and added 146 lines in `TableMethodDirectiveResolver`; the two `@tableMethod` blocks went from ~45 lines to ~10 lines each. Phase 2b dropped `FieldBuilder` from 3,103 → 3,077 lines (-26) and added 117 lines in `ExternalFieldDirectiveResolver`; the single `@externalField` block went from ~45 lines to ~10. Cumulative: `FieldBuilder` is down 224 lines (3,301 → 3,077, -6.8%) over three phases.
