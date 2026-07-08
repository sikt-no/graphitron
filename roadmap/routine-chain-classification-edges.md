---
id: R449
title: "Routine chains: classification edges from the R435 second-pass review"
status: Backlog
bucket: validation
theme: service
depends-on: []
created: 2026-07-08
last-updated: 2026-07-08
---

# Routine chains: classification edges from the R435 second-pass review

A second-pass In Review review of R435 (post-approval, 2026-07-08) surfaced classification
edges and polish items that do not break the shipped surface but leave invariants without
enforcers. None of them gates R435; recorded here so they are not lost.

Classification gaps (both are validation edges on shapes no fixture authors today):

* **Multi-node routine chains on `Mutation` / `Subscription` misclassify as Query reads.** The
  R435 interception in `FieldBuilder.classify` gates on `parentType instanceof RootType` and
  routes root multi-node chains to `classifyRootRoutineChain`, which never consults the parent
  type name; `type Mutation { x: [Film!] @routine(...) @reference(...) }` therefore lands
  `QueryField.QueryRoutineTableField` (whose `source()` is `Source.Root.Query`) instead of a
  typed rejection, while the adjacent single-node `@routine` on Mutation correctly rejects
  through `classifyRootField`'s Mutation fork. Fix: check the parent type name before
  `classifyRootRoutineChain` (procedure-write routines are explicitly out of scope per R300 /
  R435) and add the rejection fixture.
* **`@routine` is missing from both directive-conflict detectors.** `detectChildFieldConflict`
  and `detectQueryFieldConflict` count `@service` / `@externalField` / `@tableMethod` /
  `@nodeId` / `@lookupKey` slots but not `@routine`, and the R435 chain interception runs
  before both, so `@service @routine` co-occurrence is silently swallowed with
  position-dependent precedence (`@service` wins on a root single-node field, the routine
  chain wins on child fields and multi-node chains). Fix: add the `@routine` slot to both
  detectors plus fixtures; this is exactly the silent-swallow case
  `InvalidSchema.DirectiveConflict` exists for.

Test tightening (behaviour verified correct in source; the assertions just would not catch an
arm regression):

* Three rejection fixtures in `GraphitronSchemaBuilderTest`'s R435 block assert diagnostic
  text only, with no `isInstanceOf` on the `Rejection` arm: repeated `@reference` on
  `ARGUMENT_DEFINITION` (should pin `InvalidSchema.DirectiveConflict`), repeated `@reference`
  on `INPUT_FIELD_DEFINITION` (structural channel), and the element-less `@reference`
  application in a multi-application chain (structural).
* No test asserts the R300 single-node desugar lands `QueryRoutineTableField` with
  `hops = []`; the shape is pinned only indirectly (R300 projection test plus execution tier).

Doc-rot and consolidation:

* `BuildContext.computeTerminalTargetVerdict`'s `On.Lateral` throw arm says the routine-chain
  terminus check "lives in `RoutineDirectiveResolver.bindArgs`"; it moved to
  `FieldBuilder.routineChainVerdict` when the resolver went position-agnostic. Point the
  comment there.
* `SplitRowsMethodEmitter` carries pre-flip topology comments contradicting the R435
  start-first walk: `emitFromBridgeAndParentJoin`'s javadoc opens with "`.from(terminalAlias)`,
  the bridging-hop chain back to step 0" while its own R435 paragraph below describes the
  `parentInput`-anchored forward walk, and `buildSingleMethod`'s javadoc still says it
  "projects and FROMs off terminalAlias".
* `TypeFetcherGenerator`'s root routine fetcher hand-builds the routine-call argument list
  (with its own `ParamSource` switch and a duplicated `nonRoutineParamSource` helper mirroring
  `RoutineCallEmitter`) and emits hop aliases directly instead of through
  `JoinPathEmitter.emitTableExpression`. Safe today because `QueryRoutineTableField`'s compact
  constructor pins hops to catalog targets and root bindings to `Arg`, but it is a second
  emission site for the routine-call surface; R448's correlated value-arg `DataType` lift
  would have to change both. Route the root fetcher through the shared emitters.
