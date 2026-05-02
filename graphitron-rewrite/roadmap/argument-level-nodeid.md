---
id: R40
title: "Argument-level `@nodeId` support"
status: Spec
bucket: architecture
priority: 2
theme: nodeid
depends-on: []
---

# Argument-level `@nodeId` support: architectural tightenings

## Why

The argument-level `@nodeId` machinery (resolver, classifier arms, validator rejection, execution-tier coverage) is in place and shipping correct user-visible behaviour. A design review against the technical principles surfaced three structural seams that don't reflect best shape. None are user-visible bugs; all are drift the principles flag explicitly.

- *Generation-thinking*: when a generator branches on a predicate over pre-resolved data, the fork belongs in the model as a sealed sub-variant. The same predicate evaluated by multiple consumers is a sign the resolver is under-specified, and an opportunity for one site to drift from another.
- *Classifier guarantees shape emitter assumptions*: the producer/consumer pair wears `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` so the audit test catches drift. A guarantee that's load-bearing in practice but unannotated is the failure mode the pattern exists to prevent.
- *Classifier picks the principled value, emitter conforms*: the failure mode (`Skip` vs `Throw`) is a semantic choice that belongs at the classify boundary. Picking the value the existing emitter happens to support is the inversion.

## Resolver: split `Resolved.FkTarget` into `DirectFk` / `TranslatedFk`

`NodeIdLeafResolver.Resolved.FkTarget` collapses two distinct emission shapes into one arm:

- **DirectFk**: FK target columns positionally match the NodeType's key columns. The body emitter binds decoded keys directly against `joinPath[0].sourceColumns()` on the field's own table. No JOIN, no translation. This is the only shape any projection arm emits today.
- **TranslatedFk**: FK target columns differ from the NodeType key columns (the parent_node + child_ref shape, R57). Emission requires JOIN-with-translation; deferred to R57.

The argument-side projection at `FieldBuilder.classifyArgument` recomputes the predicate via `sameColumnsBySqlName(...)` and rejects on miss. The input-field-side at `BuildContext.classifyInputField:920-953` does *not* check: `InputField.ColumnReferenceField` / `CompositeColumnReferenceField` carriers can silently hold the pathological shape. Two consumers, asymmetric gates, same predicate computed locally each time.

**Replace** the `FkTarget` record with a sealed sub-hierarchy:

```java
sealed interface FkTarget extends Resolved {
    record DirectFk(String refTypeName,
                    TableRef targetTable,
                    HelperRef.Decode decodeMethod,
                    List<ColumnRef> keyColumns,
                    List<ColumnRef> fkSourceColumns,
                    List<JoinStep> joinPath)
        implements FkTarget {}
    record TranslatedFk(String refTypeName,
                        TableRef targetTable,
                        HelperRef.Decode decodeMethod,
                        List<ColumnRef> keyColumns,
                        List<JoinStep> joinPath)
        implements FkTarget {}
}
```

The resolver computes the positional-match predicate once and picks the variant. Both call sites then exhaustively switch:

- **Argument side** (`FieldBuilder.classifyArgument`): `DirectFk` projects to `ColumnReferenceArg` / `CompositeColumnReferenceArg`; `TranslatedFk` returns `UnclassifiedArg` with the R57 hint. The inline `sameColumnsBySqlName` check deletes.
- **Input-field side** (`BuildContext.classifyInputField`): `DirectFk` builds `InputField.{Column,CompositeColumn}ReferenceField`; `TranslatedFk` returns `InputFieldResolution.Unresolved` with the same R57 hint. Closes the silent-pathological-shape gap.

`FieldBuilder.sameColumnsBySqlName` deletes; the predicate moves to its single home in the resolver.

### Load-bearing guarantee on `DirectFk`

`DirectFk` *is* the load-bearing shape: the projection arms read `fkSourceColumns` straight into `BodyParam.{Eq,In,RowEq,RowIn}` and assume positional correspondence with the decoded NodeType keys. Relax the producer's gate and the emitted SQL silently mistargets, exactly the fragility `@LoadBearingClassifierCheck` is for.

Annotate the resolver branch that produces `DirectFk` with:

```java
@LoadBearingClassifierCheck(
    key = "nodeid-fk.direct-fk-keys-match",
    description = "FK target columns positionally match NodeType key columns;"
                + " emission can bind decoded keys directly against fkSourceColumns")
```

Annotate consumers with `@DependsOnClassifierCheck(key = "nodeid-fk.direct-fk-keys-match", reliesOn = ...)`:

- `FieldBuilder.projectFilters` arms for `ColumnReferenceArg` / `CompositeColumnReferenceArg`.
- `BuildContext.classifyInputField` arms for `InputField.{Column,CompositeColumn}ReferenceField` (and the matching body emitter consumers).

`LoadBearingGuaranteeAuditTest` enforces the producer/consumer pairing automatically once the annotations land.

## Per-field summary: `NodeIdArgPlan`

Three sites walk a field's args today, each calling `nodeIdLeafResolver.resolve(...)` fresh:

- `FieldBuilder.findSameTableNodeIdUnderAsConnection` (validator rejection in `resolveTableFieldComponents`),
- `FieldBuilder.hasSameTableNodeIdAnywhere` (lookup-promotion gate in `classifyQueryField`),
- per-arg in `FieldBuilder.classifyArgument`.

The bespoke `walkInputTypeForSameTableNodeId` re-traverses arg input-type subtrees with its own `LinkedHashSet<String>` cycle guard, when `BuildContext.classifyInputField` already walks that tree once during normal classification. Any directive-syntax change between two walks could classify inconsistently.

**Pre-resolve once** per field into a small per-field summary:

```java
record NodeIdArgPlan(
    Map<String, NodeIdLeafResolver.Resolved> byArgName,
    boolean anyArgSameTable,        // top-level args (gates lookup-promotion)
    boolean anyNestedSameTable      // input-field leaves under arg input-types
                                    //   (gates @asConnection rejection alongside
                                    //    R50's existing input-field path)
) {}
```

`classifyTableField` (or its R6-orchestrator successor) builds the plan once and threads it through:

- The `@asConnection` rejection step (consumes `anyArgSameTable || anyNestedSameTable`).
- The lookup-promotion gate (consumes `anyArgSameTable`).
- `classifyArgument` (reads each arg's pre-resolved `Resolved` from `byArgName`).

`findSameTableNodeIdUnderAsConnection`, `walkInputTypeForSameTableNodeId`, and `hasSameTableNodeIdAnywhere` collapse into reads of the plan and delete.

## Emitter: per-row skip in `addRowBuildingCore`

`LookupValuesJoinEmitter.addRowBuildingCore:282-287` hardcodes a throw when a per-row decode returns null:

```java
builder.addStatement("$T $L = ($L instanceof $T _s) ? $T.$L(_s) : null", ...);
builder.beginControlFlow("if ($L == null)", recLocal);
builder.addStatement("throw $T.newErrorException().message($S).build()", ...);
builder.endControlFlow();
```

This forces every consumer to be `ThrowOnMismatch`. Same-table arg `@nodeId` semantically wants `SkipMismatchedElement` (a malformed encoded id should produce no row match, not a 500 to the user). The classifier currently picks `ThrowOnMismatch` only because the emitter wouldn't accommodate `Skip`.

**Branch on the `CallSiteExtraction.NodeIdDecodeKeys` arm** at the per-row decode site:

- `ThrowOnMismatch` (existing `@lookupKey` dispatch path; wrong-type id is a contract violation): keep the throw.
- `SkipMismatchedElement` (same-table arg `@nodeId`; new): emit a `continue` that drops the row from the VALUES set, shrinking `rows[]` accordingly. The `RowN` slot machinery downstream needs to handle a smaller-than-`n` rows array; check whether the existing `rows.length == 0` short-circuit at the call site already covers the empty case or whether the emitter needs to track an effective row count separately.

Then change the same-table arg arm in `FieldBuilder.classifyArgument` from `ThrowOnMismatch` to `SkipMismatchedElement`. The comment block at `FieldBuilder.java:601-605` explaining the deviation also deletes.

## Tests

**Pipeline-tier (`NodeIdPipelineTest`).**

- `ArgumentSameTableNodeIdCase` cases keep their structure; flip the asserted `extraction` from `ThrowOnMismatch` to `SkipMismatchedElement`.
- New case in `InputFieldFkTargetCase` (or a new `InputFieldFkTargetTranslatedRejectionCase`): an input-field `[ID!] @nodeId(typeName: T)` where T's keys differ from the FK targets currently builds silently; assert it now classifies as `InputFieldResolution.Unresolved` with the R57 hint.
- New case asserting the resolver produces `DirectFk` vs `TranslatedFk` directly, exercising the resolver-tier without going through carriers.

**Execution-tier (`NodeIdQueryTest`).**

- Flip `bazByIds_malformedIdSkipped` from "throws" to "drops malformed ids, returns rows for the well-formed subset, no exception".
- Add `bazByIds_allMalformedIdsSkipped_returnsNoRows` to cover the all-skipped → empty-rows edge of the emitter change.
- `bazByIds_emptyList_returnsNoRows` already covers the empty-input edge; verify it still passes after the emitter rework.

**Audit (`LoadBearingGuaranteeAuditTest`).** Picks up `nodeid-fk.direct-fk-keys-match` automatically once the producer and consumer annotations land. No new test code; the existing audit fails if the pairing is incomplete.

## Foundation in place

- R6's resolver pattern with sealed `Resolved` outcomes. `NodeIdLeafResolver` exists.
- R50's input-field `@nodeId` carriers (`InputField.{Column,CompositeColumn}{,Reference}Field`).
- Argument-side carriers (`ArgumentRef.ScalarArg.{Column,CompositeColumn}{,Reference}Arg`).
- `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` and `LoadBearingGuaranteeAuditTest` (existing producers in `ServiceCatalog`, `BatchKeyLifterDirectiveResolver`, `TypeBuilder`, `FieldBuilder`).
- `@asConnection` validator rejection, lookup-promotion gate, full execution coverage. This pass changes how those wire, not what they do.

## Out of scope

- **R57** (FK-target columns ≠ NodeType keys). The new `TranslatedFk` arm rejects pointing at R57; the JOIN-with-translation emitter lands there.
- **Multi-hop FK-target** (input side). Sibling Backlog item, parallel to R24's multi-hop arm.
- **Mutation-key `@nodeId` args.** Separate `classifyMutationArguments` path; `NodeIdLeafResolver` is the seam when it's needed.
- **`Record1` raw-cast templates** at `ArgCallEmitter.java:248` and `LookupValuesJoinEmitter.java:436-450` are correct generated-Java-17 fixes living as two near-identical templates. If a third `@nodeId`-decode site lands, factor a shared helper before the third copy. Not blocking.
