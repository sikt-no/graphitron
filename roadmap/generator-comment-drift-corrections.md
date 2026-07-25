---
id: R526
title: "Correct stale generator and model comment claims routed out of the javadoc sweep"
status: In Progress
bucket: cleanup
priority: 4
theme: model-cleanup
depends-on: []
created: 2026-07-24
last-updated: 2026-07-25
---

# Correct stale generator and model comment claims routed out of the javadoc sweep

The comment-trimming sweep (the R524 changelog entry records it) found comment claims that are factually wrong or reference dead code, and routed them here instead of rewriting them, because each needs a code-level decision (fix the claim, fix the code, or delete the dead API) that a comment-only sweep must not make. Spec-time investigation verified every site and overturned two of the routed premises; the item stays a per-site corrections item (taxonomy changes it uncovered are filed as R528/R529 and named per claim), and where a claim's real fix is owned elsewhere the resolution here is the hand-off, never a parallel rewrite.

## 1. `GraphitronType.ResultType#fqClassName` — shipped

Corrected as planned: `JooqTableRecordType`'s arm javadoc now names both populations (reflected class name vs the stand-in mints that assert only a projected-row source), points at `PojoResultType`'s permit-identity split as the intended carrier of the distinction, and does not bless the null; the interface caveat stands. Same-edit rule honoured: `ReturnTypeRef.ResultReturnType#fqClassName` now states the copy contract (null exactly when the source component is, i.e. the stand-in population), and `TypeClassification.JooqTableRecord`'s third restatement of the fact was aligned in the same edit. The shape fix stays R528's.

## 2. `GraphitronType` InputType — shipped, one premise corrected

The garbled sentence is reconstructed, with one correction to this spec's own reconstruction: implementation-time investigation showed an input carrying `@table` always classifies as `TableInputType` (`TypeBuilder.buildInputType` short-circuits before the binding resolver is consulted), so `InputType`'s backing never comes from a `@table` directive; the `RootTable` input observation in `RecordBindingResolver` serves cross-producer agreement, not `InputType` backing. The corrected doc states the two real sources (reflected method parameter the input flows into; enclosing bound input's accessor for nested inputs) and names the nullable-slot-instead-of-permit-identity axis without blessing it. `TypeClassification.PojoInput` and the `PojoInputType` record doc ("not specified in the directive", another false attribution) were corrected in the same edit. The `Backed`/`Unbacked` split stays R528's.

## 3. `TypeFetcherGenerator#buildTableMethodParentCorrelation`: routed to R527

The comment claims the empty-slot fallback's `DSL.noCondition()` is "runtime-throwing"; it is a silent no-op. This is the fifth restatement of the empty-slot `On.ColumnPairs` state whose resolution R527 owns (a compact-constructor invariant making empty slots unrepresentable, all restatement sites reconciled in one commit); R527's inventory has been amended to include this site. This item does not touch it: a local comment fix here would add a differently-worded fifth statement of a state R527 is about to delete. Provenance stays recorded by this section.

Adjacent finding, filed as R529: the same method's `unsupportedPath` block emits a runtime `UnsupportedOperationException` for a classifier-recognised sub-shape (multi-hop / condition-joined `@tableMethod`), which "Rejections: validator mirrors classifier invariants" says must be a build-time rejection. Out of scope here; named so the corrected surroundings do not read as clean.

## 4. `TypeFetcherGenerator` channel-partition — shipped via `ErrorChannel.RouterDispatched`

Reachability first, as planned: `ErrorChannel.Mapped` is minted only by `FieldBuilder.buildServiceField` (root `@service` variants), and those route exclusively through `buildServiceFetcherCommon`'s wrap fork; every legacy `catchArm`/`asyncRouterCall` site receives channels from `resolveErrorChannel` (`PayloadClass`) or the structural DML detection (`LocalContext`). No live bug.

The partition is now compile-checked: a sealed sub-interface `ErrorChannel.RouterDispatched` (permits `PayloadClass`, `LocalContext`) partitions the hierarchy against `Mapped`; `WithErrorChannel.errorChannel()` widened to `Optional<? extends ErrorChannel>` so each field variant declares its narrowest partition as the record component (`Mapped` on the four root-service variants per side, `RouterDispatched` everywhere else); the legacy seams take `Optional<ErrorChannel.RouterDispatched>` and `ChannelCatchArmEmitter.emit` takes `ErrorChannel.Mapped` directly. All three runtime throws and both "Additive window" comments deleted together: `catchArm`'s and `asyncRouterCall`'s `Mapped` arms, and `ChannelCatchArmEmitter`'s `PayloadClass` throw (plus its unreachable `LocalContext` arm and sentinel parameter — the emitter's one live caller is the wrap fork). The narrowing did not pull in the cutover; the legacy seams keep their emission, typed to what they emit. R521's entry for the deleted "slice-1 commit 4" string was removed (the string is gone, not reworded). The acceptance grep also surfaced `ReflectTypeResolver`'s "slice-1 commit 3" comment; restated present-tense in the same commit.

## 5. `RecordBindingResolver#fromAnyProducer` — shipped

Deleted: the method, the `reachable` set, its population in `addObservation`, and the field javadoc, in one edit. No missed-consultation site surfaced: the one live reachability read (`TypeBuilder`'s record-directive lint gate) already derives the fact at the read site from `resolveInput`/`resolveResult`.

## 6. Dead fetcher-builder cites — shipped, follow-on filed as R533

All five sites resolved per the three-way plan: `MultiTablePolymorphicEmitter`'s two mirror-claims restated at intent altitude naming the carve-out from the unified `DataLoaderFetcherEmitter` seam; `RowsMethodCall`'s provenance paragraph deleted; `GraphitronSchemaValidator`'s guard rationale repointed to the live `buildBatchedDataFetcher` source-shape arms with every guard claim truth-verified against the emitter (Record-arm null-source prelude, Outcome-arm `instanceof Success` narrowing, Table-arm empty prelude, `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` guard — all hold). One rationale updated for honesty: `BatchedLookupTableField` now routes through the same guarded builder arms, so its exclusion is stated as a not-yet-made validator behavior decision rather than the stale "not audited". The single-source-the-predicate follow-on is filed as R533 (`localcontext-guard-predicate-single-source`), which also owns that admission decision.

## Acceptance

- Full reactor green under `mvn install -Plocal-db` with the `{@link}` reference gate and `RoadmapReferenceGuardTest` active.
- Claims 1, 2: both carriers' docs corrected coherently; no doc blesses a nullable-backing slot as design; R528 named as the shape owner.
- Claim 3: untouched here; R527's amended inventory covers the site (verified at its Done, not this item's).
- Claim 4: no "later commit" / "seam landed" future-promises remain in `TypeFetcherGenerator` (self-verifying: `grep -rn 'seam landed\|later commit\|slice-1 commit' graphitron/src/main/java` is empty or R521-owned string literals only); the arm partition is compile-checked or the cutover is filed.
- Claim 5: `fromAnyProducer`, the `reachable` set, and its population are gone.
- Claim 6: `grep -rn 'buildSplitQueryDataFetcher\|buildRecordBasedDataFetcher' graphitron*/src/main/java` returns nothing; the validator's guard claims are truth-verified, not just renamed.
- Follow-ons filed for everything routed (single-source predicate; any cutover item claim 4 surfaces).
