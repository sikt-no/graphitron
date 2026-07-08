---
id: R449
title: "Routine chains: classification edges from the R435 second-pass review"
status: Spec
bucket: validation
theme: service
depends-on: []
created: 2026-07-08
last-updated: 2026-07-08
---

# Routine chains: classification edges from the R435 second-pass review

A second-pass In Review review of R435 (post-approval, 2026-07-08) surfaced classification
edges and polish items that do not break the shipped surface but leave invariants without
enforcers. None of them gates R435. The findings below are the problem statement; the Design
section that follows is the plan.

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

## Design

**D1: Query-only gate on the root-chain interception.** The interception's root arm (the
root-head rule and `classifyRootRoutineChain`) applies only when the parent is `Query`. A
Mutation root chain carrying `@routine` gets a typed `AuthorError.Structural` minted in the
interception with a routine-specific message: a `@routine` table chain is a read surface,
procedure-write routines are R300's deferred fork, and Mutation fields need `@service` /
`@mutation`; the generic "both absent" fallback in `classifyMutationField` would bury the
actual cause. A Subscription root chain falls through to `classifyRootField`'s existing
generic Subscription `Deferred` (consistency: everything on Subscription lands there). A
repeated-`@reference` chain with no routine node on Mutation also falls through, so it gets
the Mutation story rather than the Query-oriented "move @routine first" root-head rejection.

**D2: `@routine` joins the conflict detectors, with per-pair verdicts.** The verdict table,
uniform across positions: `@routine` × `@service` / `@externalField` / `@tableMethod` /
`@nodeId` is `InvalidSchema.DirectiveConflict` (two source-claiming directives); `@routine` ×
`@lookupKey` is typed `Deferred` with planSlug `routine-chain-fetch-form-breadth`, extending
R435's shipped child verdict to root (a capability gap per R447, not a contradiction);
`@routine` × `@splitQuery` composes (shipped R435). Mechanically: `detectChildFieldConflict`
gains the routine slot (it already runs before the interception, and its slot set carries no
`@lookupKey`, so the child position needs nothing else); `detectQueryFieldConflict` gains the
routine slot against `@service` / `@tableMethod` while the `@routine` × `@lookupKey` pair
short-circuits to the `Deferred` before slot counting; the query-side check also runs at the
top of the interception's Query-root branch so multi-node chains reach it (single-node roots
keep the existing call inside `classifyQueryField`; the detector is pure over the field
definition, so two call sites cannot disagree).

**D3: test tightening.** The three text-only rejection fixtures in
`GraphitronSchemaBuilderTest`'s R435 block gain `isInstanceOf` arm assertions
(`InvalidSchema.DirectiveConflict` for repeated `@reference` on `ARGUMENT_DEFINITION`;
`AuthorError.Structural` for the input-field and element-less-application cases, the
structural channel both route today); a new fixture asserts the R300 single-node root desugar
lands `QueryRoutineTableField` with empty `hops` (the re-home fact currently pinned only
indirectly).

**D4: comment repairs.** Point `BuildContext.computeTerminalTargetVerdict`'s `On.Lateral`
throw arm at `FieldBuilder.routineChainVerdict`; align the three stale pre-flip topology
comments in `SplitRowsMethodEmitter` with the shipped `parentInput`-anchored start-first walk.
Sequencing note: R450 (in Spec) reworks the same file's correlation arms; whichever lands
second rebases trivially, no real coupling.

**D5: root-fetcher emission consolidation.** Route the root routine call through
`RoutineCallEmitter.emitCall` by adding a payload-free `PreviousNodeRef.None` arm (the root
chain head has no previous node); `argExpression`'s `SourceColumn` × `None` combination throws
classifier-unreachable, citing the `QueryRoutineTableField` compact-constructor pin that root
bindings are `ParamSource.Arg`. Delete the duplicated `nonRoutineParamSource` helper; route
the root hop alias-declaration loop through the shared `JoinPathEmitter.emitTableExpression`
switch. Behaviour-identical; the existing pipeline and execution tests pin it.

## Tests

Pipeline tier in `GraphitronSchemaBuilderTest`'s R435 block, matching its fixture style; no
code-string assertions.

* D1: a Mutation multi-node routine chain asserts `AuthorError.Structural` and the
  routine-specific message; a Subscription routine chain asserts the generic Subscription
  `Deferred`; a Mutation repeated-`@reference` chain asserts it lands the Mutation fallback,
  not the root-head rejection.
* D2: `@service @routine` on a child field, a root single-node field, and a root multi-node
  chain all assert `InvalidSchema.DirectiveConflict` naming both directives (the three
  positions that today disagree silently); `@routine @lookupKey` at root asserts the typed
  `Deferred` with the R447 planSlug.
* D3 is itself test work (arm tightening + the `hops = []` desugar fixture).
* D4/D5 need no new tests: comments have no runtime surface, and the consolidation is
  behaviour-identical under the existing R435 pipeline + execution suite.

## Out of scope

* The R447 capabilities (multi-routine chains, `@lookupKey` landings, record-backed and
  interface parents) and R448 residue (ordering, `DataType` lift, corpus migration).
* R450's parent-anchor correlation rework in `SplitRowsMethodEmitter` (D4 touches only
  comments there).
