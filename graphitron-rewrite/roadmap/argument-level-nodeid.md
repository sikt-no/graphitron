---
id: R40
title: "Argument-level `@nodeId` support"
status: In Review
bucket: architecture
priority: 2
theme: nodeid
depends-on: []
---

# Argument-level `@nodeId` support: architectural tightenings

All three architectural seams shipped. Pending review before deletion on Done.

## Why

The argument-level `@nodeId` machinery (resolver, classifier arms, validator rejection, execution-tier coverage) was in place and shipping correct user-visible behaviour. A design review against the technical principles surfaced three structural seams that didn't reflect best shape. Two were pure refactorings; the third (Skip vs Throw) changed observable behaviour on malformed-id inputs (before: 500, after: partial result), restoring the originally-specified `Skip` semantics over the first pass's expedient `Throw`.

- *Generation-thinking*: when a generator branches on a predicate over pre-resolved data, the fork belongs in the model as a sealed sub-variant. The same predicate evaluated by multiple consumers is a sign the resolver is under-specified.
- *Classifier guarantees shape emitter assumptions*: the producer/consumer pair wears `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` so the audit test catches drift.
- *Classifier picks the principled value, emitter conforms*: the failure mode (`Skip` vs `Throw`) is a semantic choice that belongs at the classify boundary.

## Phase 1: split `Resolved.FkTarget` into `DirectFk` / `TranslatedFk`

Shipped at `5064a16`. The resolver computes the positional-match predicate once and picks the variant; both call-site projections (argument-side and input-field-side) consume the variant and the inline `sameColumnsBySqlName` check deletes from `FieldBuilder.classifyArgument`. The R57 hint message lives once on the shared `FieldBuilder.translatedFkRejectionReason`. Closes the asymmetric-gating gap where `BuildContext.classifyInputField` previously did not check the predicate and silently let the pathological shape through. Load-bearing key `nodeid-fk.direct-fk-keys-match` annotates `NodeIdLeafResolver.resolve` and the consumers (`FieldBuilder.projectFilters`, `FieldBuilder.walkInputFieldConditions`, `BuildContext.classifyInputField`); `LoadBearingGuaranteeAuditTest` picks up the pairing automatically.

## Phase 2: per-row Skip / Throw branch in `LookupValuesJoinEmitter.addRowBuildingCore`

Shipped at `9192bf7`. The per-row decode site branches on `CallSiteExtraction.NodeIdDecodeKeys`: `ThrowOnMismatch` keeps the existing GraphqlErrorException throw (synthesised lookup-key paths where wrong-type id is a contract violation); `SkipMismatchedElement` emits `continue` and the loop tracks `effective` row count, returning `Arrays.copyOf(rows, effective)` when shrunk. The hoisted-decode mechanism that `DecodedRecord` (composite-PK) already used now also covers arity-1 `ScalarLookupArg` with `NodeIdDecodeKeys` extraction; the inline ThrowOnMismatch ternary in `slotValueExpr` deletes. `LookupMapping.LookupArg.DecodedRecord` retypes its decode slot from `HelperRef.Decode` to `CallSiteExtraction.NodeIdDecodeKeys` so the model carries the failure-mode arm. `FieldBuilder.classifyArgument` flips the same-table arm from Throw to Skip; the implicit scalar-ID arm (no `@nodeId`, NodeId-backed table) stays Throw for the synthesised lookup-key path.

## Phase 3: lift `NodeIdArgPlan`

Shipped at `5891293`. `NodeIdArgPlan` pre-resolves every `@nodeId`-decorated leaf reachable from a table-bound field's argument set: top-level args land in `byArgName -> NodeIdLeafResolver.Resolved`; same-table hits anywhere flip `anyArgSameTable` / `anyNestedSameTable` and seed the `SameTableHit` triple for the `@asConnection` rejection message. Built once per field by `buildNodeIdArgPlan`, threaded through `resolveTableFieldComponents -> classifyArguments -> classifyArgument`. The three previous walks (`findSameTableNodeIdUnderAsConnection`, `walkInputTypeForSameTableNodeId`, `hasSameTableNodeIdAnywhere`) collapse into reads of the plan and delete. `classifyQueryField` builds the lookup-promotion plan once and reuses it for both the gate and the `resolveTableFieldComponents` call; the six `classifyChildField*` paths build their own plan per-field at the `resolveTableFieldComponents` site.

## Phase 4: tests

Shipped in this final pass.

- Pipeline-tier (`NodeIdPipelineTest`): `ArgumentSameTableNodeIdCase` extraction assertions flipped from `ThrowOnMismatch` to `SkipMismatchedElement`; `DecodedRecord` assertions re-routed through `extraction().decodeMethod()`. New `InputFieldFkTargetNodeIdCase` enum covers the input-field-side asymmetric-gating closure (`FK_TARGET_PATHOLOGICAL_KEY_MISMATCH_DEFERRED_INPUT`).
- Resolver-tier (new `NodeIdLeafResolverTest`, first resolver-tier unit test for an R6 resolver): three cases assert `DirectFk` for matching-keys, `TranslatedFk` for the parent_node + child_ref reproducer, and `DirectFk` again on the input-field-side input. Test seam `GraphitronSchemaBuilder.buildContextForTests` exposes the wired `BuildContext` after type classification, without running field classification.
- Execution-tier (`GraphQLQueryTest` triplet): `filmsByNodeIdArg_malformedIdMixedWithWellFormed_returnsWellFormedSubset` (the partial-decode skip path; SQL inspection confirms `(values (0, 2))` with the malformed row dropped), `filmsByNodeIdArg_allMalformedIds_returnsNoRows` (all-skipped → empty short-circuit at the call site), `filmsByNodeIdArg_emptyList_returnsNoRows` (empty-input edge).
- Audit (`LoadBearingGuaranteeAuditTest`): picks up `nodeid-fk.direct-fk-keys-match` automatically from the producer/consumer annotation pair.

## Foundation in place

- R6's resolver pattern with sealed `Resolved` outcomes. `NodeIdLeafResolver` exists.
- R50's input-field `@nodeId` carriers (`InputField.{Column,CompositeColumn}{,Reference}Field`).
- Argument-side carriers (`ArgumentRef.ScalarArg.{Column,CompositeColumn}{,Reference}Arg`).
- `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` and `LoadBearingGuaranteeAuditTest`.
- `@asConnection` validator rejection, lookup-promotion gate, full execution coverage.

## Out of scope

- **R57** (FK-target columns ≠ NodeType keys). The new `TranslatedFk` arm rejects pointing at R57; the JOIN-with-translation emitter lands there.
- **Multi-hop FK-target** (input side). Sibling Backlog item, parallel to R24's multi-hop arm.
- **Mutation-key `@nodeId` args.** Separate `classifyMutationArguments` path; `NodeIdLeafResolver` is the seam when it's needed.
- **`Record1` raw-cast templates** at `ArgCallEmitter.java:248` and the now-deleted ScalarLookupArg inline path. If a third `@nodeId`-decode site lands, factor a shared helper before the third copy.
