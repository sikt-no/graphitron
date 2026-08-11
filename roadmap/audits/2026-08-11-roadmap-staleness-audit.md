# Roadmap staleness audit: 2026-08-11

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `f088d28`, committed 2026-08-10 21:44, audited 2026-08-11). The goal is
to find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed or been discarded, or
specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-08-10` staleness audit, which has been deleted;
only the latest **staleness** audit is retained. The other thirteen files in this
directory are **not** staleness audits and are left in place (deleting them would
strand lineage that shipped items and active items cite by path, and they are
provenance I did not author). They are:

- `2026-06-16-source-operation-target-reframe.md`, the permanent lineage document
  for R316 (Done).
- `2026-06-30-release-planning.md`, the first-release scoping working document,
  edited in place as scope iterates.
- `2026-07-04-r222-r333-conformance-analysis.md`, the R222/R333 conformance record
  (R222 discarded 2026-08-06; this is a historical conformance snapshot).
- `2026-07-26-fcis-command-layer-distance.md`, the FCIS command-layer distance
  analysis (symbol-anchored snapshot with a stated re-derivation method).
- `2026-08-05-fact-base-h2-spike.md` and `2026-08-05-h2-functions-jooq-spike.md`,
  the two grounding spikes for the fact-base stack that R595's body cites by path.
- `2026-08-06-fact-base-impact-sweep.md`, the **architecture-drift companion** to
  this audit. It records the whole-board sweep of which items the adopted
  fact-base architecture (R595 substrate, R589 derivation) subsumes, reshapes, or
  consumes. This staleness audit tracks *symbol and reference* drift; that sweep
  tracks *architecture* drift. With R595/R610/R603 shipped (prior window) the sweep's
  substrate half has landed; its derivation half (R589) is now **In Progress** with
  slices 1-4 landed this window (slices 5-6 remain), so the derivation is actively
  reifying rather than speculative.
- `2026-08-06-r222-lineage.md`, the absorption ledger and rejected-design record
  preserved when R222 was discarded.
- `2026-08-06-demand-exemption-census.md`, `2026-08-06-directive-consumer-census.md`,
  `2026-08-06-graphql-java-diff-spike.md`, `2026-08-06-structural-classifier-census.md`,
  the four grounding censuses/spikes filed alongside the fact-base work.
- `classification-test-dsl-inventory.md`, the permanent corpus-retirement inventory
  (its "closed and historical" banner is intact).

## Headline: a quieter ~23-hour window (R613 and R618 to Done, R589 into In Progress), no code retired anything and none of the 33 carried flags gained a driver; R569 resolves itself off the board (re-anchored on shipped R585), R333 took another partial catch-up without repointing its retired symbols, and the fact-base derivation half is now landing rather than speculative

Where the prior audit caught the busiest code window in months, this one is a
short consolidation window (HEAD moved `bbc976a` → `f088d28`, ~23 hours, 44 commits
of which 10 touched main/test source). **Two items ran to Done** (R613, R618) and
**R589 moved Spec → In Progress** with four derivation slices landing. The decisive
fact for a staleness audit is what the shipped code did *not* do: **none of it
retired or renamed a symbol**, so no carried flag gained a new driver and no new
stranding was minted.

- **R613 (`lookup-generated-column-filters`, Done)** shipped generated column filters
  beside the lookup VALUES join as two deletions and no emitter change:
  `ConditionCommands.requireNoGeneratedFilterOnLookup` (with the `lookup` boolean that
  existed only to reach it) and the lookup arm of
  `GraphitronSchemaValidator.validateConditionEmitImplemented` (the method survives for
  the single-table interface-child deferral). A board-wide `grep` confirms **no active
  roadmap item cites either deleted symbol** (only `changelog.md`), so R613's retirement
  strands nothing. Not a flag driver.
- **R618 (`routine-mutation-payload-carrier-return`, Done)** gave the `@routine` write
  the DML payload carrier: new `MutationField.MutationRoutineWriteRecordField` leaf, the
  `EmittedCarrierBinding` reification over three carrier arms, `ProducerBinding.RoutineEmitted`,
  `CarrierFamily.ROUTINE`, and the pure `BuildContext.deriveRoutineCarrierPairs`. Its own
  changelog entry states "retirement sweep not applicable" and a symbol scan confirms it
  **added** vocabulary without removing any, so it strands nothing. It filed two follow-ups,
  R619 (`emitted-carrier-binding-consumer-consolidation`) and R622
  (`routine-carrier-explicit-data-field-path`); both are born-current (retired-symbol scan
  clean) and R622's `depends-on: [validation-adds-facts]` edge resolves.
- **R589 (`validation-adds-facts`, In Progress) landed slices 1-4.** Slice 1 amended the
  R333 umbrella's current-state prose to the render layer; slices 2-4 shipped the authored
  claim views and the conflict rule that reads them, the witness-bearing column-match
  classifier, and the demand/exemption shadow rows (`AuthoredClaim`, `AuthoredClaimConflicts`,
  `ClaimDomain`, `ColumnMatchClaim`, `GraphSourceMembership`, `CatalogFactCapture`,
  `DemandResidue`, `ReachabilityRows`). All new derivation vocabulary; nothing retired.
  Slices 5-6 remain. The consequence for the board: items whose fact-base notes deferred
  "re-check after R589 slice 5" (R221 explicitly, R213's residue) still wait on slice 5,
  but the derivation they wait on is now actively reifying, not a Spec on paper.
- **R617 (`lookup-positional-contract-unimplemented`, Ready)** shipped its code during
  In Review (`RootLauncherRenderer`, `ReservedAliases`, `GraphitronSchemaValidator`,
  `SplitRowsMethodEmitter`) and sat back to Ready for one Done-gate finding. Born-current;
  not a flag.

**R569 (`mcp-aggregated-diagnostics`) resolved itself off the flag list.** The prior
audit's only new §C.8 flag was R569's `:472` claim that
`BuildContext.classifyInputFieldInternal` "carries prose and no `Rejection` at all". This
window re-anchored R569 on the shipped R585 and R584 (`a9f06ef`, `630daec`): its body now
correctly states `InputFieldResolution.Unresolved` carries `(fieldName, SourceLocation,
Rejection)` and the method mints `Rejection.directiveConflict` directly (`:508-511`), and
the departed-slug note (`mcp-server-instruction-routing`, R584 now Done) is gone. **§C.8
empties and the flag total drops by one.** This is the model outcome: a flagged item that
the owning programme repointed in place before the next audit had to chase it.

The **previously-untracked `*Emitter` driver** (§C.9: `InlineColumnReferenceFieldEmitter`,
`InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`, `FkTargetConditionEmitter`, the
phantom `MutationConditions`) is re-verified **`grep` = 0 in every main tree** at this HEAD
and carries forward unchanged. The work they name still lives in `ProjectionUnitRenderer` /
`ConditionGlueRenderer` / `ConditionCommands` and the model record `FkTargetConditionFilter`.
No new item cites them; the six citers (R333/R462/R85/R447/R288/R7) are all already flagged.

Net: **1 §A / 9 §B / 23 §C / 0 §D.** Flag total moves **34 → 33**: the only change is
R569 leaving §C.8 (resolved in place). The long-standing dissolution drift (`Operation`
seal, `TableInputType`, `ColumnBackedField`, the lookup triplet, the `Split*`/`Record*`
merge, `planSlug`/`SourceKey.Reader`, the condition/projection emitters) is entirely
unchanged: every driving symbol re-verified `grep` = 0 at this HEAD, and no other §C item
was repointed in place this window. R333, the worst case, took **another partial catch-up**
(slice 1 renamed `$fields` → `$project` and refreshed the stage vocabulary) **without
repointing any retired class name**, so it stays exactly as inconsistent as at the prior
audit.

## Changes since the 2026-08-10 audit

The window runs from the prior audit's HEAD (`bbc976a`, 2026-08-09 22:31) to this
HEAD (`f088d28`, 2026-08-10 21:44): **44 commits**, of which **10 touched main/test
source**, grouped into three programmes (R589 slices 2-4, R618, R617). The prior
audit's own commits (`bb14bd1` refresh + `7f73296` C.9 addendum) are the git boundary.

**Code that shipped (the source-touching commits), and what each did to the symbol set:**

- **R613 → Done** (`19dec9a` capability, `be7c128` docs): generated column filters compose
  beside the lookup VALUES join. Two deletions, no emitter change:
  `ConditionCommands.requireNoGeneratedFilterOnLookup` (with the `lookup` boolean that only
  reached it) and the lookup arm of `GraphitronSchemaValidator.validateConditionEmitImplemented`
  (the method survives). **Neither deleted symbol is cited by any active roadmap item** (a
  board-wide `grep` hits only `changelog.md`), so this retirement strands nothing.
- **R618 → Done** (`a82f59a` carrier end-to-end, `b2b065f` execution proof, `726586a` docs +
  coverage, `2929b84` write membership): the `@routine` write gains the DML payload carrier.
  **Added** vocabulary only (`MutationField.MutationRoutineWriteRecordField`, `EmittedCarrierBinding`,
  `ProducerBinding.RoutineEmitted`, `CarrierFamily.ROUTINE`, `BuildContext.deriveRoutineCarrierPairs`);
  its changelog entry states the retirement sweep was not applicable. Filed R619 and R622 as typed
  follow-ups.
- **R589 slices 2-4** (`882c037` authored claim views + conflict rule, `fac7704` witness-bearing
  column-match classifier, `15d44b8` demand and exemption shadow rows; slice 1 `b97b1cb` amended
  the R333 umbrella prose): the fact-base derivation half begins landing. All new derivation
  vocabulary (`AuthoredClaim`, `AuthoredClaimConflicts`, `ClaimDomain`, `ColumnMatchClaim`,
  `GraphSourceMembership`, `CatalogFactCapture`, `DemandResidue`, `ReachabilityRows`); nothing retired.
- **R617 code** (`1f249c0` core, `ed79266` + `fc93d75` + `bce87ad` rework/docs): the lookup
  positional contract, shipped during In Review across `RootLauncherRenderer`, `ReservedAliases`,
  `GraphitronSchemaValidator`, `SplitRowsMethodEmitter`. Additive; nothing retired.

**Items filed this window (born-current unless flagged):** R618-R623 were allocated
(next-id now `R624`). Read against the current model: R619 (`emitted-carrier-binding-consumer-consolidation`,
Backlog), R620 (`dev-loop-duplicate-classpath-scan`, Backlog), R621 (`legacy-exception-provider-method-size`,
Spec), R622 (`routine-carrier-explicit-data-field-path`, Backlog), R623 (`web-session-maven-build-log`,
Backlog); R618 was filed and ran to Done inside the window. A retired-symbol scan of all six is
clean.

**Transitions:** R613 In Review → Done; R618 Backlog → Spec → Ready → In Progress → In Review →
Done (filed and shipped inside the window); R589 Ready → In Progress (slices 1-4 committed);
R617 Backlog → In Review, its positional-contract code landing and then bouncing back to Ready
twice on Done-gate findings (currently Ready); R612 Spec → Ready. Actively drafted and correctly **not** flagged as stale: R589 (In Progress),
R347 (In Progress), R612 and R617 (Ready).

**Board accounting.** **182 item files** today, up from 178 (id range grew R617→R623; R613 and
R618 left to Done, R618-R623 filed = net +4). Status distribution: **162 Backlog, 12 Spec,
6 Ready, 2 In Progress, 0 In Review, 0 Done**. Tombstone-free (`grep` for `status: Done` in
`roadmap/*.md` = 0). No duplicate `id:`; max allocated id **R623**, `changelog.md` carries
`next-id: R624`, clearing it. A `depends-on:` sweep resolves all **five** non-empty edges
(`catalog-check-constraint-validation`, `capability-catalog`, `oneof-augment-defeated-by-descriptions`,
`multi-source-input-validation`, and R622's new `validation-adds-facts` edge) to present files; the
two departed items left no dangling edge. The only structural nits are the same four **legacy**
items still missing a `bucket:` key (§D), all pre-dating this window.

## Scope and method

All **182** `R<n>` item files were reviewed. Every driving symbol below was re-checked against a
fresh `grep` of the main sources (`graphitron`, `graphitron-mcp`, `graphitron-lsp`,
`graphitron-model`, `graphitron-maven-plugin`, `graphitron-fixtures-codegen`), not carried on the
prior audit's word. Because the window's code retired nothing, the whole long-standing retirement
set carries forward re-verified rather than reshaped.

**Long-standing retirements, re-verified still retired at this HEAD (`grep` = 0 in main
real code; `{@code}`/`{@link}`/comment hits excluded):** `CompileDependencyGraphBuilder`;
`RowsMethodBody` / `RowsMethodSkeleton`; `QueryConditionsGenerator` / `TypeConditionsGenerator`;
`TypeClassGenerator` / `collectRequiredProjection`; `ParentProjectionContainmentCheck` and the
`methodgraph` package; `GraphitronType.TableInputType` / `buildNonTableInputType`;
`MutationInputResolver.resolveInput`; the lookup triplet (`LookupTableField` /
`BatchedLookupTableField` / `QueryLookupTableField`), `LookupField`, `LookupValuesJoinEmitter`;
`TableOnInputRejection`; the `Split*` / `Record*` leaf names (`SplitTableField` /
`RecordTableField` / `SplitLookupTableField` / `RecordLookupTableField`); the `ColumnField`
family (the surviving `ColumnField` real-code hits are `keyColumnFields` substrings, per the
prior audit); `Rejection.Deferred.planSlug`; `SourceKey.Reader`; the `Operation` seal and every
`Operation.<Arm>` reference including `Operation.Facet` / `Operation.Count` and the `operation()`
accessor (successor `OperationMember`). The prior window's `InputFieldResolution.Unresolved`
`reason: String` carrier and R473's three nodeId synthesis shims (`resolveDecodeHelperForTable`,
`buildInputNodeIdReference`, `typeNamesByTableKey`, `IdReferenceShim`, both shim loggers) are
likewise re-verified `grep` = 0; their live successors (`Unresolved`'s `Rejection` component,
`NodeIndex.forName`) are unchanged.

**Retirements this window:** R613's `ConditionCommands.requireNoGeneratedFilterOnLookup` and the
`validateConditionEmitImplemented` lookup arm, both re-verified `grep` = 0 with the method surviving
for the single-table interface-child deferral. **No active roadmap item cites either**, so unlike
the prior window's `Unresolved` reshape this retirement mints no flag.

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried (new prior window); still Backlog, still fully delivered.** The entire deliverable ("remove the `RejectionKind.AUTHOR_ERROR` default arm and emit `RejectionKind.of(rejection)` consistently with `traceOutput`") is shipped by **R585**. `FieldRegistry.classifyInput` (`:106-123`) now emits `RejectionKind.of(u.rejection()), u.rejection().message()` on the `Unresolved` arm, with no default-`AUTHOR_ERROR` and no `u.reason()`. Fork (a) (the record widening) is R585's landed change; the consumer-side collapse this item scoped is also in the tree. Nothing remains to do. | **Discard**, recording R585 as the delivery vehicle. Verify once at the symbol (`grep` for `AUTHOR_ERROR` default in `FieldRegistry`, `u.reason()`; both `grep` = 0) before retiring to lineage. Its own fact-base note already anticipated "the item collapses to the record widening or retires with the registry." |

R520 (`table-on-input-removal-housekeeping`) stays a coherent deliberately-deferred docs tail,
not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (9)

All nine carry from the prior window, each re-verified at the symbol this window: premise-target
still `grep` = 0 with a live successor, and none was edited this window (the R589/R618/R617 code
did not touch these surfaces). Nothing new entered §B this window; the recommended actions stand
as written. The three R585/R473-driven rows (R66, R213, R34) that were new to the prior audit are
now settled flags awaiting re-spec.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R66** rejection-string-carrier-widening | Backlog | **Carried (new prior window).** Phase **A2** ("widen `InputFieldResolution.Unresolved.reason: String` → `rejection: Rejection`", `:76-88`) was *verbatim* what **R585** shipped; the `:28` anchor `record Unresolved(..., String reason)` is now `(fieldName, SourceLocation, Rejection)`. The item's own co-design note (`:81-88`) predicted "whichever lands first should carry the combined `(SourceLocation, Rejection)` shape" and R585 did exactly that. Phases A1 (`ParsedPath.errorMessage`), A3 (`UnboundArg.reason`), B1 (`EnumValidation.Mismatch`), B2 (`TypeBuilder` aggregations) survive. | **Re-spec:** strike A2 as delivered by R585; re-baseline the four surviving phases onto the shipped record (B2's "after Phase A2 each `Unresolved` carries a `Rejection`" prerequisite is now satisfied, so B2 can proceed directly); fix the `:25-30` anchors. |
| **R213** input-field-rejection-attribution | Backlog | **Carried (escalated §C→§B prior window).** R585 **inverted the load-bearing premise**: `:43`/`:48`/`:69` state `InputFieldResolution.Unresolved` "has no `SourceLocation`" and "missing a location field", but the record now carries one; and the first Direction bullet (`:54`, "`Unresolved` grows a `SourceLocation` field") is **shipped**. The carried §C.6 stale cite also persists (`:64` "route through `TableInputType` classification", `grep` = 0 since R519). | **Re-spec** against the shipped located-typed-rejection record: drop the "grows a `SourceLocation`" / "has no `SourceLocation`" claims and the co-design note (R585 resolved it); re-baseline the surviving deliverable (`condErrors` → `List<LocatedRejection>`, `Resolution.Rejected` list-carrying, `validateUnclassifiedField` fan-out) onto `(fieldName, SourceLocation, Rejection)`; fix the `TableInputType` cite to per-consumer resolution. Per the fact-base note, re-check residue after R589 slice 5. |
| **R34** nodeid-migration-quickfix | Backlog | **Carried (new prior window).** Title "driven by shim facts" and the "The gap" (`:16-24`) / "Shape of the fix" step 1 (`:30-33`) convert three shim WARN sites to `BuildWarning`s, but **R473 deleted all three sites and their loggers** and **R27 was discarded**. The discard commit half-reconciled the item (Sequencing `:45` and Out-of-scope `:52` now acknowledge deletion), leaving the body **self-contradictory**: the mechanism sections still name the deleted sites as the live gap. | **Re-spec:** the "shim facts" driver is void. The migration goal (ergonomic `@nodeId` fixes across ~250 sis sites) survives, but its source must be re-derived: R473's landed grammar rejections/warnings, or (per the item's own fact-base note) the R589 claim relation once inferred claims carry join witnesses. Retitle off "shim facts". |
| **R122** compound-entity-mutations | Backlog | Carried. "Design space narrows under R222" leans on R222's discarded recursive `InputUsage` model as a live design input; R222 left the board 2026-08-06 and R122 was not edited since. `TableTargetField` (which this item adds) is live. | **Re-spec the "narrows under R222" section**: drop the dependence on the discarded `InputUsage` carrier; re-express the cross-table nested-input model against the captured `intent_`/`applied_` relations the fact-base architecture adopted (`2026-08-06-fact-base-impact-sweep.md` §R222). Keep the compound-mutation goal, `@reference(path:)` flattening, and `TableTargetField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Carried. Central target `CompileDependencyGraphBuilder.addFieldEdges` deleted (`grep` = 0); the body's own stated dissolution condition (`:163`) has occurred. Also cites the absent `FkTargetConditionEmitter.emitTerm` (`:45`) and phantom `MutationConditions` (`:57`) as live (§C.9). | **Re-derive against the plan-projected recompile graph.** Confirm at the symbol whether the nested-fetcher per-field edge is now modeled under `EmitPlan`; if closed, **discard** and record it, else **re-spec** onto the plan-projection. In the same pass, repoint `FkTargetConditionEmitter.emitTerm` → `FkTargetConditionFilter` via `ConditionCommands`, and drop the `MutationConditions` shim name. |
| **R545** model-free-of-emit-vocabulary | Backlog | Carried. `RowsMethodBody` / `RowsMethodSkeleton` deleted, so the diagnosis and second deliverable are gone. First deliverable survives: `ClassName` / `TypeName` are still model-pervasive. | **Re-spec.** Drop the `RowsMethodBody` diagnosis and second deliverable; keep and re-baseline the first (`JavaTypeRef` replacing `TypeName` / `ClassName`) against the post-R549 model. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Carried. Both named edit-target generators deleted: `QueryConditionsGenerator` (R552), `TypeClassGenerator` (R549). Successors `render/ConditionGlueRenderer` / `render/ProjectionUnitRenderer` live. Also names `InlineTableFieldEmitter.java:144` / `InlineLookupTableFieldEmitter.java:218` (`:20-21`, `:45-46`) as live host files to delete; both absent (§C.9). | **Re-derive against the new `render/` layer.** Determine whether the renderers still exhibit the duplicated helper-emission problem; drop every dead `QueryConditionsGenerator.java:NNN` cite and the two absent `Inline*Emitter.java:NNN` file cites (the `$fields` host is now `ProjectionUnitRenderer`). |
| **R221** validator-walks-plain-input-unbound-fields | Backlog | Carried, also fact-base-annotated. R519 deleted `TableInputType` / `validateTableInputType`; successor `collectInputFieldRejections` live; R566 removed `@table`-input classification. | **Close as subsumed or re-derive.** R589's body names this item as "subsumed or narrowed by slice 5"; slices 1-4 landed this window (In Progress), slice 5 has not, so the subsumption decision is now near but not yet actionable. If not building against the store, re-derive around `collectInputFieldRejections`; either way the `validateTableInputType` cites must go. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried. R431 (Done) deleted the target surface: the body cites `SourceKey.Reader.SourceRowsCall` as "the live surface", but `SourceKey` is a plain record with no `Reader`. Successors `KeyLift` / `LifterRef` / sealed `Wrap` live; "R431 ... plans to decompose" reads present tense but R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model; drop the `SourceKey.Reader.SourceRowsCall` re-anchor note and the tense error. The goal is intact. |

## C. Outdated: update references only (work valid, refs stale) (23)

Substance intact; names and line numbers drifted. All twenty-three carry from the prior audit;
**R569 left this section this window** (its §C.8 flag was repointed in place, see §C.8 below).
Every remaining driving symbol re-verified still `grep` = 0, and none of the twenty-three was
repointed this window (R333 took another partial catch-up without touching its retired class
names, see §C.7). §C.9 records a driver (absent `*Emitter` names) that hits six already-listed
rows without adding a distinct item.

### C.0 `Operation` seal fully retired (carried; R563 slice 7)

`Operation` and every `Operation.<Arm>` reference are `grep` = 0. Successor: `OperationMember`;
obligation `MEMBER_ARMS` / `MEMBER_KNOWN_GAPS`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R427** relevance-ranked-search | Ready | `:335` "`operation()` stays `Fetch`" cites the retired accessor; `:339`/`:1162` cite `Operation.Facet` as a live precedent type. | **Re-anchor.** Restate "`operation()` stays `Fetch`" against the member-derived summary fold; repoint `Operation.Facet` onto `OperationMember.Facet` (or the `MEMBER_KNOWN_GAPS` census). A **Ready** item; refresh before pickup. |
| **R382** multitable-interface-query-orderby-lowering | Backlog | `:19` cites `operation()` as the live mechanism hardcoding `new OrderBySpec.None()`. Accessor and seal both retired. | **Re-anchor** to where the hardcoded `OrderBySpec.None` now lives (`OperationMember.OrderBy` sourcing, or `MultiTablePolymorphicEmitter`); verify the ordering gap reproduces. |
| **R562** synthesised-connection-fields-as-coordinates | Backlog | `:17-18` name `Operation.Count`/`Operation.Facet` arms of the `OPERATION_ARMS` obligation (`OPERATION_KNOWN_GAPS`) as the observable gap; all three names retired. | **Re-anchor** the three names to `OperationMember.Count`/`Facet` / `MEMBER_ARMS` / `MEMBER_KNOWN_GAPS`. Model question intact. |

### C.1 Lookup-triplet dissolution drift (carried; R563 slice 6a)

A lookup leaf re-anchors to `BatchedTableField` (or `TableField` / `QueryTableField`) **plus a
lookup member**. (Note: R613, In Review, adds generated-column filters *beside* the lookup VALUES
join; it does not restore the retired leaf names, so these cites stay stale.)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R533** localcontext-guard-predicate-single-source | Backlog | `:15` names `BatchedLookupTableField` as the open edge; `:19` self-corrects but the lead is stale. | **Re-anchor** the `:15` lead to the post-dissolution sibling. Fact-base sweep: the single-sourcing subject becomes a derivation view both consumers read. |
| **R557** split-query-marker-sweep | Backlog | `:17`'s "consumed (the batched leaves)" lists `BatchedLookupTableField` alongside `BatchedTableField` / `BatchedPivotField`. | **Re-anchor**: drop `BatchedLookupTableField` (now `BatchedTableField` + lookup member). |

### C.2 `@table`-on-input rejection → deprecation drift (carried; R566)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R565** unclassified-input-arg-cascade-diagnostic | Backlog | Title (`:3`, `:13`) still leads with the retired `@table`-on-input **rejection** as the driver; the body already frames it against the current state. | **Re-anchor (not full re-spec).** Retitle/re-lead onto a still-current rejection; demote `@table`-on-input to historical framing. |

### C.3 `planSlug` / `SourceKey.Reader` removal drift (carried)

R484 removed `Rejection.Deferred.planSlug`; R431 removed the `SourceKey.Reader` interface.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R454** routine-write-result-shapes | Backlog | `:18` names deferred shapes as "typed `Deferred`s pointing at this item's planSlug"; gone. | **Re-anchor:** deferred shapes surface via `StubKey.VariantClass`. |
| **R447** routine-chain-fetch-form-breadth | Backlog | `:18`/`:33` "`planSlug` points here"; `:24`/`:26` name `SplitLookupTableField` / `RecordTableField` as live; `:23` names the absent `InlineTableFieldEmitter` as live beside the real `SplitRowsMethodEmitter` (§C.9). | **Re-anchor:** drop the `planSlug` phrasing; repoint to `BatchedTableField` (lookup twin: **+ lookup member**); drop the `InlineTableFieldEmitter` cite (render projection layer). |
| **R180** record-parent-column-read-helper | Spec | `:35` says "R431 ... now In Progress" (R431 is **Done**) and names `SourceKey.Reader.AccessorCall` as live. | **Re-anchor** the `AccessorCall` carrier onto the decomposed model; fix the R431 tense. |
| **R505** tenant-index-parent-row-routing | Backlog | `:21` names "a column read off the parent row (the `SourceKey.Reader` family)". Live via `KeyLift.FkColumns`. | **Re-anchor** the one parenthetical. |

### C.4 Leaf-merge drift: `Split*` / `Record*` → `Batched*` (carried)

`SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** to `BatchedTableField`. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38`'s R305 lineage note "collapsed it into `RecordTableField`"; `:107`/`:148`/`:150`/`:284` cite `LookupValuesJoinEmitter`. | **Re-anchor** to `BatchedTableField`; repoint `LookupValuesJoinEmitter` to the render values-join family. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34` name `SplitTableField` / `RecordTableField`; `:24` cites the absent `InlineTableFieldEmitter` as the child-field emitter (§C.9). `SplitRowsMethodEmitter` fine. | **Re-anchor** the two variant names to `BatchedTableField`; drop the `InlineTableFieldEmitter` cite (render projection layer). |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`'s planned case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R7** decompose-typefetchergenerator | Backlog | `:30` proposes a hypothetical `SplitTableFieldEmitter`; `:32` lists `LookupValuesJoinEmitter`, and `:31` labels the absent `InlineLookupTableFieldEmitter` as "Existing" (§C.9). | **Low priority:** refresh to `BatchedTableFieldEmitter`; repoint the `LookupValuesJoinEmitter` cite; drop the "Existing `InlineLookupTableFieldEmitter`" claim (render projection layer). |
| **R323** nestingfield-multiparent-batchkey-leaves | Backlog | `:18`/`:30` list `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`. | **Re-anchor** the four names (lookup twins: **+ lookup member**). |

### C.5 `ColumnBackedField` dissolution drift (carried; R508)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | Retired carriers named live across `:570`, `:694`, `:706`, `:727`, `:747-748`, `:798-799`, `:1112`, `:1140`, `:1145`, `:2009`. Part of the one R333 refresh (§C.7). | **Re-anchor** the carrier names to `ColumnBackedField`. Part of the one R333 pass. |

### C.6 `TableInputType` / `resolveInput` removal drift (carried; R519 + R515)

R222 left this subsection via discard (2026-08-06); R213 leaves it this window (promoted to §B).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R234** jooq-embedded-and-udt-input-backings | Spec | `:15` cites `TypeBuilder.buildNonTableInputType` (`grep` = 0) as the live dispatch to extend. | **Re-anchor** to the current `TypeBuilder` input-classification path (`buildInputType`). |
| **R257** updaterows-walker-sdl-substrate | Backlog | `:17` calls `resolveInput` "the legacy resolver" (gone R515); `:15`/`:19` reach carriers "via `TableInputType.inputFields()`" (gone R519). | **Re-anchor** both: `resolveInput` → `admitMutationInputFields`, `TableInputType.inputFields()` → per-consumer input resolution. |
| **R337** input-nesting-projection-classification | Backlog | `:30` reaches an input object's fields "via `TableInputType.inputFields()`" as the LSP-hover mechanism. | **Re-anchor** the one mechanism cite to per-consumer input resolution. |
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot composition "in `MutationInputResolver.resolveInput`". | **Re-anchor** the one sentence to `admitMutationInputFields`. |

### C.7 Condition + projection emitter dissolution drift (carried; R552 + R549)

Condition emission is `render/ConditionGlueRenderer`; projection `render/ProjectionUnitRenderer`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh still incomplete after a second partial catch-up.** Still at the symbol: 5× `TypeClassGenerator`, 5× `collectRequiredProjection`, 5× `methodgraph`, `LookupValuesJoinEmitter`, 2× `ParentProjectionContainmentCheck`, 1× `TypeConditionsGenerator`, plus the §C.0/§C.5 carriers and the §C.9 absent-emitter names. This window R589 slice 1 (`b97b1cb`) amended R333 again, renaming `$fields` → `$project` and refreshing the stage vocabulary and `sourceLocation` row, but **repointed no retired class name**, so the drift is essentially unchanged: two partial catch-ups (R610/R603 last window, the render-method rename this window) have both skipped the retired class-name refresh. | **Finish the refresh in one pass** (this row + §C.0 + §C.5 + §C.6 + §C.1 + §C.9): repoint `TypeClassGenerator` / `collectRequiredProjection` → `ProjectionUnitRenderer` / `ProjectionCommands`; drop the `TypeConditionsGenerator`, `ParentProjectionContainmentCheck`, `methodgraph`, `operation()` cites; re-anchor `LookupValuesJoinEmitter` to the render lookup family and the `Operation` seal cites to `OperationMember`; replace the `InlineColumnReferenceFieldEmitter` / `InlineTableFieldEmitter` / `InlineLookupTableFieldEmitter` projection-arm names with the render projection layer and `FkTargetConditionEmitter (R330)` with `FkTargetConditionFilter` via `ConditionCommands`. The fact-base sweep's R333 section maps the same regions; rows citing `SplitRowsMethodEmitter` stay (live). |
| **R231** emit-text-mapped-enum-fields-as-enum-type | Backlog | `:39` locates "the field-type-emit fork (likely in `TypeClassGenerator` or ...)". | **Re-anchor** the investigation locus to `ProjectionUnitRenderer` / `plan/ProjectionCommands`. |
| **R35** source-orientation-javadocs | Backlog | `:42` enumerates classes for a javadoc sweep, including `TypeClassGenerator` and `TypeConditionsGenerator`, both deleted. | **Re-anchor** the enumeration: drop the two deleted names. Low priority; scope illustrative. |

### C.8 `InputFieldResolution.Unresolved` reshape drift (resolved this window; R585)

R585 (prior window) reshaped `Unresolved` from a `reason: String` prose carrier to
`(fieldName, SourceLocation, Rejection)`. R66/R209/R213 remain the primary consumers (handled in
§A/§B above). The one further Spec item that carried a single stale line, **R569, was repointed in
place this window and leaves this section.**

| Item | Status | Resolution | Verified |
|---|---|---|---|
| **R569** mcp-aggregated-diagnostics | Spec | **Resolved (no action).** The prior audit's flag (`:472` "carries prose and no `Rejection` at all", used as the premise that the identity cannot move) was re-anchored on the shipped R585/R584 this window (`a9f06ef`, `630daec`). The body now states `Unresolved` carries `(fieldName, SourceLocation, Rejection)` and `BuildContext.classifyInputFieldInternal` mints `Rejection.directiveConflict` directly (`:508-511`), records the dependency as discharged, and the departed-slug note (`mcp-server-instruction-routing`, R584 Done) is gone. | `grep` for "carries prose and no `Rejection`" as a live premise = 0; the phrase survives only as history of what R585 changed. No further action. |

### C.9 Absent projection/condition `*Emitter` names (new driver; render-layer refactor)

A board-wide sweep surfaced a driver no prior audit tracked: a family of per-arm
projection/condition emitter names that six items cite as **live** current-state classes,
several with file:line coordinates and one (`FkTargetConditionEmitter (R330)`) attributed as
shipped, but which are **`grep` = 0 across every main tree** at this HEAD. Whether they were
retired by the R549/R563 render-layer refactor or were never more than planned decomposition
names, the projection and condition work they describe lives today in `ProjectionUnitRenderer`
/ `ProjectionCommands` (SQL projection arms), `ConditionGlueRenderer` / `ConditionCommands`
(condition emission), and the model record `FkTargetConditionFilter` (FK-target conditions).
The phantom `MutationConditions` env-shim name has zero hits anywhere (its sibling
`QueryConditions` exists only as generated output).

This subsection adds **no distinct flagged item**: every citer is already listed above for the
tracked dissolution drift. It is recorded here so the repoint is not lost, and folded into each
row's recommended action.

| Absent name | Cited-as-live in | Live successor |
|---|---|---|
| `InlineColumnReferenceFieldEmitter` | R333 (`:1890`) | render projection layer (`ProjectionUnitRenderer` / `ProjectionCommands`) |
| `InlineTableFieldEmitter` | R333 (`:1752`,`:1891`), R85 (`:20`,`:45`, with `.java:144`), R447 (`:23`), R288 (`:24`) | render projection layer |
| `InlineLookupTableFieldEmitter` | R333 (`:1891`), R85 (`:21`,`:46`, with `.java:218`), R7 (`:31`) | render projection layer |
| `FkTargetConditionEmitter` | R333 (`:1893`), R462 (`:45`, `.emitTerm`) | `FkTargetConditionFilter` via `ConditionCommands` |
| `MutationConditions` (phantom) | R462 (`:57`) | none; drop the shim name |

## D. Structural: (0)

Empty of blocking defects. `changelog.md` carries `next-id: R624`, clearing the max allocated id
(R623). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and the five non-empty
`depends-on:` edges resolve to present files (`catalog-check-constraint-validation`,
`capability-catalog`, `oneof-augment-defeated-by-descriptions`, `multi-source-input-validation`,
and R622's new `validation-adds-facts`). The two items that left the board this window (R613, R618)
left **no dangling `depends-on` edge**. The six items filed this window (R618-R623) carry
well-formed front-matter and were read against the current model.

One **pre-existing, non-blocking** hygiene note, surviving unchanged:

1. Four **legacy** items still lack a `bucket:` key: R242 (`dml-payload-positional-alignment`),
   R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
   R180 (`record-parent-column-read-helper`). Build tolerates the omission; fold a `bucket:` in
   whenever each is next edited.

## Cross-cutting observations

1. **A window can ship a lot of code and move no flags.** Two items to Done, four fact-base
   derivation slices, and the lookup positional-contract code all landed this window, yet the
   flag total *fell* by one. The reason is that all of it was **additive at the symbol level**:
   R618 and R589 introduced new vocabulary, R617 added a renderer path, and R613's only two
   deletions were symbols no roadmap item cited. Staleness is minted by *retirement and rename*,
   not by volume of change, so the audit-relevant question about any window is narrow: what did
   the code delete or rename, and who cited it. Here the answer was "two symbols, nobody."

2. **The best flag outcome is the owning programme repointing in place before the audit chases
   it.** R569 was the prior audit's only genuinely new §C flag; this window its own programme
   re-anchored it on the shipped R585/R584 while doing unrelated design work (`a9f06ef`,
   `630daec`), so it leaves §C.8 with no audit action required. That is the intended lifecycle
   of a reference-only flag: the item that owns the surface refreshes its own prose. The audit's
   job is to notice when that *doesn't* happen, which is the standing R333 problem below.

3. **R333 is the counter-example, and it is now a repeat offender.** For the second consecutive
   window R333 was edited (this time R589 slice 1's render-method rename) **without** repointing
   any of its retired class names. It carries the most stale cites on the board (`TypeClassGenerator`,
   `collectRequiredProjection`, `methodgraph`, `LookupValuesJoinEmitter`, `ParentProjectionContainmentCheck`,
   `TypeConditionsGenerator`, the `Operation`-seal cites, and the §C.9 absent-emitter names) and it
   is a **Ready** item, so the stale prose bites the next implementer to pick it up. Its refresh is
   mapped identically by §C.0/§C.5/§C.6/§C.7/§C.9 and the fact-base sweep's R333 section, and should
   land in one pass before pickup. The pattern to watch: an item that gets *touched* every window for
   content catch-up but never for symbol repointing drifts further with each edit, not less.

4. **The fact-base derivation half is now landing, so the deferred items are near their decision
   point.** Last audit R589 was Spec and the "re-check after R589 slice N" notes (R213, R221, R565)
   waited on paper. This window R589 moved to In Progress with slices 1-4 committed; R221's
   subsumption gate is R589 slice 5, which has not landed, so the notes still wait, but on a
   programme now actively reifying rather than on a Spec. Next window's audit should re-check whether
   slice 5 landed and, if so, whether R221 can be closed as subsumed.

5. **Some drift hides behind names that were never in the tree.** The `Inline*Emitter` /
   `FkTargetConditionEmitter` family (§C.9) re-verified `grep` = 0 across every main tree this
   window; the names appear only in roadmap prose, cited as live classes with concrete file:line
   coordinates. A symbol-retirement audit keyed only on "what the changelog retired" would miss
   them; the catch is a whole-board reverse sweep of every class-shaped cite against the current
   tree. Worth keeping as a standing check: a cited `*.java:NNN` coordinate that resolves to no
   file is stale regardless of whether the symbol ever shipped.

6. **The Ready set is where stale prose bites soonest.** R333, R427, R467, R555, R612 and R617
   are the six Ready items picked up next. R333 (above) and R427 (its superseded `Operation.Facet`
   precedent, §C.0) are the two carrying stale cites; R467, R555, and the two born-current fact-base
   items R612 and R617 are clean. Refreshing R333 and R427 before pickup is the highest-value
   hygiene action on the board.

---

_Review date: 2026-08-11._
