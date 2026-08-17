# Roadmap staleness audit: 2026-08-17

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `012a7c8`, committed 2026-08-16 21:38, audited 2026-08-17). The goal is
to find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed or been discarded, or
specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-08-14` staleness audit, which has been deleted;
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
  the two grounding spikes for the fact-base stack.
- `2026-08-06-fact-base-impact-sweep.md`, the **architecture-drift companion** to
  this audit. It records the whole-board sweep of which items the adopted
  fact-base architecture (R595 substrate, R589 derivation) subsumes, reshapes, or
  consumes. This staleness audit tracks *symbol and reference* drift; that sweep
  tracks *architecture* drift.
- `2026-08-06-r222-lineage.md`, the absorption ledger and rejected-design record
  preserved when R222 was discarded.
- `2026-08-06-demand-exemption-census.md`, `2026-08-06-directive-consumer-census.md`,
  `2026-08-06-graphql-java-diff-spike.md`, `2026-08-06-structural-classifier-census.md`,
  the four grounding censuses/spikes filed alongside the fact-base work.
- `classification-test-dsl-inventory.md`, the permanent corpus-retirement inventory
  (its "closed and historical" banner is intact).

## Headline: a window that retired symbols and staled nothing, because the retirement sweep was clean. R639's session-identity rework dissolved the `SessionHook` / `<variables>` / `session-state-convention-fence` family, but every active citer already reads the successor `<mount>` model; R617 and R648 also reached Done retiring nothing cited. The 18 items filed since the prior audit are all born-current, and all 36 prior flags carry forward unrepaired

Where the prior window (R649's `@service` phase split) retired symbols that four active
items named as live and drove them into re-spec, **this window is the disciplined
opposite**: it retired the largest symbol set in three windows, yet staled **zero**
active items, because the Done items ran their retirement sweeps before landing. **Three
items reached Done since the prior audit's review (R617, R639, R648)**, and the heaviest
of them (R639) dissolved the whole generated-`SessionHook` machinery, but a grep of the
board finds no active item citing any retired name as live current-state.

- **R639 (`session-state-method-hooks`, Done)** is the window's would-be staleness
  driver and the demonstration that a clean sweep defuses it. It replaced the
  `<sessionState>` string-routine mechanism (graphitron assembling `{ call fn(?) }`)
  with build-time-reflected `<mount>fqcn#method</mount>` / `<unmount>` method hooks.
  **Retired:** the generated `SessionHook` interface and its no-op, `RuntimeHookProjection`,
  `CLAIMS_KEY`, `GraphitronTransactionProvider.priorAutoCommit` (and its second
  constructor), the `<variables>` Postgres sugar, `<handle>`, `<stateSurvivesTransactions>`
  and the per-settle re-fire, and the `session-state-convention-fence` lint rule (moved to
  `docs/security.adoc`). Absorbs R640. **Every one of these re-greps to 0 in the roadmap,
  or is cited only by the successor `<mount>` model** (see §Scope and method). Filed R664,
  R468 lineage; repointed R469.
- **R617 (`root-lookup-positional-contract`, Done)** made a root lookup return one slot
  per input key, `null` on a miss. It reshaped `RootLauncherRenderer.lookupBody`'s list arm
  (new `scatterLookupByIdx`, launcher value type `List<Record>`) and added the
  `GraphitronSchemaValidator.validateRootLookup` nullable-element rejection. `RootLauncherRenderer`
  and `scatterSingleByIdx` **survive** and are correctly cited by live items; the `List<Record>`
  change touches only the root-lookup list arm and does not stale R242/R109's unrelated
  `Result<Record>` cites. **No active item drifts.** Spun out R669, R670, R679.
- **R648 (`sourcerow-declared-service-batch-key`, Done)** relaxed the child-`@service`
  batch-key contract from "keys carry the parent PK" to "keys carry the named key columns."
  It retired no symbol an active item cites; its follow-ups R656/R657 were already filed
  and audited born-current in the prior window.

Alongside those, the window's bulk was **additive fact-model work** (the R642 backing-class
census: `TypeBackingClass`, `TypeBackingClasses`, `TypeBackingRows`, `WalkReach`,
`CatalogFactCapture`, `ClasspathScanner`, `CompletionData`). No source file was deleted;
the reshaped symbols (`TypeBackingShape`, `ClassMemberSlots`, `CompletionData.RecordComponent`)
all still live, and their only citers are the actively-drafted fact-model cluster
(R642, R666, R680, R682, R684, R685), which churn with the work and are correctly **not**
flagged.

Net: **1 §A / 10 §B / 25 §C / 0 §D**, flag total **36**, unchanged from the prior audit.
**Not one flagged file was edited this window**, so every flag carries forward at its prior
line anchors, and every driving retired symbol re-verifies `grep` = 0 at this HEAD. The
long-standing dissolution drift (`Operation` seal, `TableInputType`, the lookup triplet,
the `Split*`/`Record*` merge, `planSlug`/`SourceKey.Reader`, the condition/projection
emitters, the absent `*Emitter` names, the R589 `UnboundField` reshape, and the R649
`reflectServiceMethod`/`PkLessParent` family) is entirely unchanged. **R333 remains the
standing high-value refresh** and was not touched this window, so it drifts no further but
is repaired no closer.

## Changes since the 2026-08-14 audit

The prior audit reviewed the board at next-id **R665** (206 item files) but landed on a
rewritten history: its stated baseline HEAD `04fc0f0` no longer resolves (the R617
changelog entry records the history rewrite explicitly), and the audit file itself was
committed at `1189fdf` where next-id already read **R684**. So the true "since the prior
review" window is wider than the audit's own commit: it spans the R665 -> R685 id
allocations and the R617/R639/R648 Done transitions. Current HEAD is `012a7c8`
(next-id **R686**, 220 item files).

**Items that reached Done since the prior audit's review, and what each did to the symbol
set:**

- **R617 (Ready -> Done):** the root-lookup positional contract. Reshaped
  `RootLauncherRenderer.lookupBody`, added `scatterLookupByIdx` and the `validateRootLookup`
  nullable-element rejection. No retirement of a cited symbol. Spun out R669/R670/R679.
- **R639 (In Review -> Done):** the session-identity method-hook rework. **Retired**
  `SessionHook` (interface + no-op), `RuntimeHookProjection`, `CLAIMS_KEY`,
  `GraphitronTransactionProvider.priorAutoCommit` + second constructor, `<variables>`,
  `<handle>`, `<stateSurvivesTransactions>`, the `session-state-convention-fence` lint, and
  the `<sessionState>` string-routine assembly. Successor `<mount>`/`<unmount>` model live.
  Absorbs R640.
- **R648 (In Review -> Done):** the source-row-declared child-`@service` batch key. Retired
  no cited symbol.

**Verification that R639's retirement staled nothing (the window's decisive check):** the
retired names `SessionHook`, `RuntimeHookProjection`, `CLAIMS_KEY`, `priorAutoCommit`,
`session-state-convention-fence`, `stateSurvivesTransactions`, `<variables>`, `<handle>`,
and the `R640` id all `grep` = 0 across active roadmap items. The one live term,
`<sessionState>` (5 citers: R469, R468, R664, R638, R626), is cited only in the successor
sense: R468 (`oracle-ras-session-hook-execution-coverage`) describes the new `<mount>`
static-method model verbatim; R469 (`defer-under-owned-connections`) was explicitly
repointed off the deleted re-fire as an R639 review side-item; R664 (`execution-input-staged-builder`)
and R638 (`lsp-reads-the-fact-store`, In Progress) reference the mount's payload parameters
under the new model. None reaches the retired string-routine mechanism.

**Items filed this window (R665 -> R685), all born-current** (every cited current-state
symbol resolves to a live main-source location; no premise found already delivered):
R665 (`service-batch-key-residue-pins`), R666 (`delivery-verdict-derives-from-the-store`),
R668 (`nodeid-key-projection-on-routine-params`), R669 (`renderer-test-code-string-sweep`),
R670 (`root-lookup-connection-diagnostic`), R671 (`domain-return-type-placeholders-false-conflict`),
R672 (`register-referenced-builtin-scalars`), R673 (`nodeid-arg-dispatches-on-typeid`),
R674 (`service-record-return-pk-autofetch`), R675 (`condition-method-overload-selection`),
R676 (`nodeid-filter-per-participant-paths`), R677 (`list-ordering-invariant-enforcement`),
R679 (`child-lookup-positional-rationale`), R680 (`fact-store-test-harness-consolidation`),
R682 (`planners-read-facts-emitters-read-commands`), R683 (`capture-expands-facet-synthesis`),
R684 (`consumers-share-relations-not-queries`), R685 (`census-scans-transitive-closure`).
**R667 and R678 were filed and left no file** (discarded or absorbed without one, as
R644/R651/R658 were in the prior window); **R681 (`mcp-code-tools-read-the-store`) was
absorbed into R642** (`3f7c94f`, "R642 owns the whole module"). Next-id is now **R686**.

**Transitions:** R617/R639/R648 to Done; R650 (`root-connection-over-discriminated-interface`)
Spec -> Ready; R638 stays In Progress; R347 (`lsp-structural-consolidation`) stays In Progress.
Actively drafted and correctly **not** flagged as stale: R642, R666, R680, R682, R684, R685
(the fact-model cluster, Spec, born-current), R638 (In Progress), R347 (In Progress).

**Board accounting.** **220 item files** today (measured), up from 206: id range grew
R664 -> R685 (21 ids allocated), with R617/R639/R648 leaving to Done and R667/R678/R681
discarded-or-absorbed without a surviving file, against eighteen surviving new files.
Status distribution: **190 Backlog, 23 Spec, 5 Ready, 2 In Progress, 0 In Review, 0 Done**.
Tombstone-free (`grep` for `status: Done` in `roadmap/*.md` = 0). No duplicate `id:`;
`changelog.md` carries `next-id: R686`, clearing the max allocated id (R685). A
`depends-on:` sweep resolves all **nine** non-empty edges (up from six) to present files:
`consumers-share-relations-not-queries` -> `catalog-facts-readers-move-to-the-store`,
`fact-store-test-harness-consolidation` -> `lsp-reads-the-fact-store` +
`catalog-facts-readers-move-to-the-store`, `multi-source-input-validation` ->
`catalog-check-constraint-validation`, `operation-driven-test-corpus` -> `capability-catalog`,
`planners-read-facts-emitters-read-commands` -> `delivery-verdict-derives-from-the-store`,
`root-connection-over-discriminated-interface` -> `batched-discriminated-interface-child`,
`routine-chain-ordering-spans-nodes` -> `routine-chain-order-directive-silent-noop`,
`rover-graphos-integration` -> `oneof-augment-defeated-by-descriptions`,
`validator-integration-execute-coverage` -> `multi-source-input-validation`.
The roadmap-tool regenerates `README.md` with **no drift** at this HEAD. The only structural
nits are the same four **legacy** items still missing a `bucket:` key (§D), all pre-dating
this window.

## Scope and method

All **220** `R<n>` item files were reviewed. Every driving symbol below was re-checked
against a fresh `grep` of the main sources (`graphitron`, `graphitron-mcp`, `graphitron-lsp`,
`graphitron-model`, `graphitron-maven-plugin`, `graphitron-fixtures-codegen`), not carried on
the prior audit's word. Because this window changed in-scope `src/**/*.java`, the checks were
run in full rather than assumed.

**R639 retirements, verified retired at this HEAD (`grep` = 0 in main real code and in the
roadmap):** `SessionHook`, `RuntimeHookProjection`, `CLAIMS_KEY`,
`GraphitronTransactionProvider.priorAutoCommit`, `session-state-convention-fence`,
`stateSurvivesTransactions`, `<variables>`, `<handle>`. Successor `<mount>`/`<unmount>`
method-hook model live. The one surviving term `<sessionState>` is cited only in the
successor sense (see Changes since).

**R617 changes, verified:** `RootLauncherRenderer` and `scatterSingleByIdx` **survive**
(live, correctly cited); the new `scatterLookupByIdx` and the `validateRootLookup`
nullable-element rejection are live. The `List<Record>` launcher-type change is scoped to the
root-lookup list arm and does not reach R242's DML-RETURNING `Result<Record>` cite or R109's
untyped-`Record`-accessor cite.

**Additive fact-model symbols, verified live (no retirement of a cited name):**
`TypeBackingShape` (11 files), `RecordBacking` / `PojoBacking` (8 each), `ClassMemberSlots`
(6), `CompletionData` (25), plus the new `TypeBackingClass` / `TypeBackingClasses` /
`TypeBackingRows` / `WalkReach`. The removed `projectTypesByName` is cited by no roadmap item;
the reshaped `CompletionData.RecordComponent` is cited only by `changelog.md`.

**Long-standing retirements, re-verified still retired at this HEAD (`grep` = 0 in main real
code; `{@code}`/`{@link}`/comment hits excluded):** the R649 family
(`reflectServiceMethod`, `ServiceCatalog.PkLessParent` (the surviving `SourcesOnPkLessParent`
is a distinct `ServiceMethodCallError` arm, not this symbol), `validateRootInvariants`,
`looksLikeSourcesShape`, `couldBeSourcesShape`); the R612 config family (`SchemaInput.plain`,
`SchemaProblemDiagnostic.normaliseLoaded`, `SdlFactCapture.regularFile`, `SchemaInputExpander`);
`CompileDependencyGraphBuilder`; `RowsMethodBody` / `RowsMethodSkeleton`;
`QueryConditionsGenerator` / `TypeConditionsGenerator`; `TypeClassGenerator` /
`collectRequiredProjection`; `ParentProjectionContainmentCheck` and the `methodgraph` package;
`GraphitronType.TableInputType` / `buildNonTableInputType`; the lookup triplet
(`LookupTableField` / `BatchedLookupTableField` / `QueryLookupTableField`),
`LookupValuesJoinEmitter`; the `Split*` / `Record*` leaf names; `Rejection.Deferred.planSlug`;
`SourceKey.Reader` (the one hit is a `{@code}` comment in `SourceEnvelope.java` naming a
retired symbol); the `Operation` seal and every `Operation.<Arm>` reference (the three word
matches are comments and one string-literal fact key, none the retired seal; successor
`OperationMember`); `MutationInputResolver.resolveInput` (method gone R515; the 29 bare
`resolveInput` hits are the unrelated `RecordBindingResolver.resolveInput` binding lookup);
the R589 retirements (`PairVerdict` / `pairVerdict` / `reduceDirectiveConflict`); and the
absent projection/condition `*Emitter` family (`InlineColumnReferenceFieldEmitter`,
`InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`, `FkTargetConditionEmitter`, the
phantom `MutationConditions`), all `grep` = 0 in every main tree.

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried (5 windows); still Backlog, still fully delivered.** The entire deliverable ("remove the `RejectionKind.AUTHOR_ERROR` default arm and emit `RejectionKind.of(rejection)` consistently with `traceOutput`") is shipped by **R585**. Re-verified at the symbol this window: `AUTHOR_ERROR` `grep` = 0 in `FieldRegistry.java`, `RejectionKind.of(...)` emitted on the `Unresolved` arms. Nothing remains to do; this window did not touch it. | **Discard**, recording R585 as the delivery vehicle. The symbol check is done and clean; retire to lineage. |

R520 (`table-on-input-removal-housekeeping`) stays a coherent deliberately-deferred docs tail,
not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (10)

All ten carry forward from the prior audit unchanged: not one of these files was edited this
window, and each re-verified at the symbol with the premise-target still `grep` = 0 and a live
successor. **R193 and R213 remain the two overdue subsumption candidates** (R649/R585/R589
shipped what they scoped); running both re-checks, most likely closing both as subsumed, is the
cheapest board-cleaning available.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R193** service-param-classification-sealed-hierarchy | Backlog | **R649 shipped the deliverable.** The item asks to refactor `reflectServiceMethod`'s `sourcesShape.isEmpty()` predicate chain (anchored at `ServiceCatalog.java:258-329`) into a sealed classifier plus one switch that owns the rejection text. R649 delivered exactly that: `reduceClaims` mints one sealed `ParamRole` per parameter, the classify phase owns rejection ordering, and both the anchor (`reflectServiceMethod`) and `looksLikeSourcesShape` are now `grep` = 0. | **Re-derive the residue against shipped R649; most likely close as subsumed.** If a thin diagnostic-arm residue survives `ParamRole`, re-spec it onto the shipped classifier and drop the `reflectServiceMethod:258-329` / `looksLikeSourcesShape` anchors; otherwise discard, recording R649 as the delivery vehicle. |
| **R72** slim-servicecatalog-to-lookup | Backlog | **Premise materially changed by R649.** The current-state diagnosis is a line-by-line census of pre-R649 `ServiceCatalog` (`reflectServiceMethod` ~170 lines; duplicated message text; not-found and parameter-names rejections at specific line cites). R649 dissolved `reflectServiceMethod` into `decodeServiceMethod` / `reduceClaims` / `bindServiceMethod` and moved policy toward the resolver, part of what this item wanted. `reflectTableMethod` and `reflectExternalField` survive. | **Re-spec.** Re-derive the diagnosis against the post-R649 split: measure how much slimming R649 already accomplished, drop every stale `ServiceCatalog.java:NNN` line cite, and re-baseline the remaining goal on the new shape. |
| **R213** input-field-rejection-attribution | Backlog | **Escalated, still unactioned (now three windows overdue).** R585 inverted the load-bearing premise (the record now carries a `SourceLocation` the item still says it lacks), and R589's occurrence-path derivation (Done) delivers the attribution split the item asks for. The item's own "re-check after R589 slice 5" note is long due and nobody has run it. | **Re-derive the residue against shipped R585 + R589; most likely close as subsumed.** If a thin residue survives, re-spec onto the shipped record and drop the "grows/has no `SourceLocation`" claims; otherwise discard, recording R585 + R589 as the delivery vehicles. |
| **R66** rejection-string-carrier-widening | Backlog | Carried. Phase **A2** ("widen `Unresolved.reason: String` -> `rejection: Rejection`") was verbatim what **R585** shipped. Phases A1 (`ParsedPath.errorMessage`), A3 (`UnboundArg.reason`), B1 (`EnumValidation.Mismatch`), B2 (`TypeBuilder` aggregations) survive. | **Re-spec:** strike A2 as delivered by R585; re-baseline the four surviving phases onto the shipped record; fix the `:25-30` anchors. |
| **R34** nodeid-migration-quickfix | Backlog | Carried, self-contradictory. The "shim facts" driver names three WARN sites **R473 deleted** and **R27 was discarded**; the discard commit half-reconciled the item, leaving the mechanism sections still naming the deleted sites as the live gap. | **Re-spec:** the "shim facts" driver is void. The migration goal survives, but its source must be re-derived onto R473's landed grammar rejections or the R589 claim relation. Retitle off "shim facts". |
| **R122** compound-entity-mutations | Backlog | Carried. "Design space narrows under R222" leans on R222's discarded recursive `InputUsage` model; R222 left the board 2026-08-06. `TableTargetField` (added by this item) is live. | **Re-spec the "narrows under R222" section**: drop the discarded `InputUsage` carrier; re-express the nested-input model against the captured `intent_`/`applied_` relations. Keep the compound-mutation goal, `@reference(path:)` flattening, and `TableTargetField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Carried. Central target `CompileDependencyGraphBuilder.addFieldEdges` deleted (`grep` = 0); the body's own dissolution condition (`:163`) has occurred. Also cites absent `FkTargetConditionEmitter.emitTerm` (`:45`) and phantom `MutationConditions` (`:57`) as live. | **Re-derive against the plan-projected recompile graph.** Confirm whether the nested-fetcher per-field edge is now modeled under `EmitPlan`; if closed, **discard**, else **re-spec**. In the same pass repoint `FkTargetConditionEmitter.emitTerm` -> `FkTargetConditionFilter` via `ConditionCommands` and drop `MutationConditions`. |
| **R545** model-free-of-emit-vocabulary | Backlog | Carried. `RowsMethodBody` / `RowsMethodSkeleton` deleted, so the diagnosis and second deliverable are gone. First deliverable survives: `ClassName` / `TypeName` are still model-pervasive. | **Re-spec.** Drop the `RowsMethodBody` diagnosis and second deliverable; keep and re-baseline the first (`JavaTypeRef` replacing `TypeName` / `ClassName`) against the post-R549 model. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Carried. Both named edit-target generators deleted: `QueryConditionsGenerator` (R552), `TypeClassGenerator` (R549). Successors `render/ConditionGlueRenderer` / `render/ProjectionUnitRenderer` live. Also names absent `InlineTableFieldEmitter.java:144` / `InlineLookupTableFieldEmitter.java:218` as live host files. | **Re-derive against the new `render/` layer.** Determine whether the renderers still exhibit the duplicated helper-emission problem; drop every dead `QueryConditionsGenerator.java:NNN` cite and the two absent `Inline*Emitter.java:NNN` file cites. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried. R431 (Done) deleted the target surface: the body cites `SourceKey.Reader.SourceRowsCall` as "the live surface", but `SourceKey` is a plain record with no `Reader`. Successors `KeyLift` / `LifterRef` / sealed `Wrap` live; "R431 ... plans to decompose" reads present tense but R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model; drop the `SourceKey.Reader.SourceRowsCall` re-anchor note and the tense error. |

## C. Outdated: update references only (work valid, refs stale) (25)

Substance intact; names and line numbers drifted. All twenty-five carry forward from the prior
audit: not one was edited this window, and every long-standing driving symbol re-verified still
`grep` = 0. §C.9 records the absent-`*Emitter` driver, which hits six already-listed rows and
adds no distinct item. **The R649 family (§C.10) is unchanged this window** (its retirements
re-grep 0), so R47 and R555 hold their §C.10 rows.

### C.0 `Operation` seal fully retired (carried; R563 slice 7)

`Operation` and every `Operation.<Arm>` reference are `grep` = 0. Successor: `OperationMember`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R427** relevance-ranked-search | Ready | `:335` "`operation()` stays `Fetch`" cites the retired accessor; `:339`/`:1162` cite `Operation.Facet` as a live precedent type. | **Re-anchor.** Restate against the member-derived summary fold; repoint `Operation.Facet` onto `OperationMember.Facet`. A **Ready** item; refresh before pickup. |
| **R382** multitable-interface-query-orderby-lowering | Backlog | `:19` cites `operation()` as the live mechanism hardcoding `new OrderBySpec.None()`. | **Re-anchor** to where the hardcoded `OrderBySpec.None` now lives (`OperationMember.OrderBy` sourcing, or `MultiTablePolymorphicEmitter`); verify the ordering gap reproduces. |
| **R562** synthesised-connection-fields-as-coordinates | Backlog | `:17-18` name `Operation.Count`/`Operation.Facet` arms as the observable gap; all three names retired. | **Re-anchor** to `OperationMember.Count`/`Facet`. Model question intact. |

### C.1 Lookup-triplet dissolution drift (carried; R563 slice 6a)

A lookup leaf re-anchors to `BatchedTableField` (or `TableField` / `QueryTableField`) **plus a
lookup member**.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R533** localcontext-guard-predicate-single-source | Backlog | `:15` names `BatchedLookupTableField` as the open edge; `:19` self-corrects but the lead is stale. | **Re-anchor** the `:15` lead to the post-dissolution sibling. |
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
| **R447** routine-chain-fetch-form-breadth | Backlog | `:18`/`:33` "`planSlug` points here"; `:24`/`:26` name `SplitLookupTableField` / `RecordTableField` as live; `:23` names the absent `InlineTableFieldEmitter` as live beside the real `SplitRowsMethodEmitter`. | **Re-anchor:** drop the `planSlug` phrasing; repoint to `BatchedTableField` (lookup twin: **+ lookup member**); drop the `InlineTableFieldEmitter` cite. |
| **R180** record-parent-column-read-helper | Spec | `:35` says "R431 ... now In Progress" (R431 is **Done**) and names `SourceKey.Reader.AccessorCall` as live. | **Re-anchor** the `AccessorCall` carrier onto the decomposed model; fix the R431 tense. |
| **R505** tenant-index-parent-row-routing | Backlog | `:21` names "a column read off the parent row (the `SourceKey.Reader` family)". Live via `KeyLift.FkColumns`. | **Re-anchor** the one parenthetical. |

### C.4 Leaf-merge drift: `Split*` / `Record*` → `Batched*` (carried)

`SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". (The `:92` `Result<Record>` cite is a live untyped-accessor fact, unaffected by R617.) | **Re-anchor** to `BatchedTableField`. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38`'s R305 lineage note "collapsed it into `RecordTableField`"; `:107`/`:148`/`:150`/`:284` cite `LookupValuesJoinEmitter`. (The `:21`/`:194` `Result<Record>` cites are the live DML-RETURNING path, unaffected by R617.) | **Re-anchor** to `BatchedTableField`; repoint `LookupValuesJoinEmitter` to the render values-join family. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34` name `SplitTableField` / `RecordTableField`; `:24` cites the absent `InlineTableFieldEmitter`. `SplitRowsMethodEmitter` fine. | **Re-anchor** the two variant names to `BatchedTableField`; drop the `InlineTableFieldEmitter` cite. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`'s planned case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R7** decompose-typefetchergenerator | Backlog | `:30` proposes a hypothetical `SplitTableFieldEmitter`; `:32` lists `LookupValuesJoinEmitter`; `:31` labels absent `InlineLookupTableFieldEmitter` "Existing". | **Low priority:** refresh to `BatchedTableFieldEmitter`; repoint the `LookupValuesJoinEmitter` cite; drop the "Existing `InlineLookupTableFieldEmitter`" claim. |
| **R323** nestingfield-multiparent-batchkey-leaves | Backlog | `:18`/`:30` still list `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField` as the planned enum arm (the R645 cross-link and the closed `LookupTableField` sub-question were added a prior window, but this drift was left). | **Re-anchor** the four names to `BatchedTableField` (lookup twins: **+ lookup member**). The core BatchKey-scope goal is untouched and stays open. |

### C.5 `ColumnBackedField` dissolution drift (carried; R508)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | Retired carriers named live across `:570`, `:694`, `:706`, `:727`, `:747-748`, `:798-799`, `:1112`, `:1140`, `:1145`, `:2009`. Part of the one R333 refresh (§C.7). | **Re-anchor** the carrier names to `ColumnBackedField`. Part of the one R333 pass. |

### C.6 `TableInputType` / `resolveInput` removal drift, plus the `UnboundField` reshape (carried; R519 + R515; R589)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R234** jooq-embedded-and-udt-input-backings | Spec | `:15` cites `TypeBuilder.buildNonTableInputType` (`grep` = 0) as the live dispatch to extend. | **Re-anchor** to the current `TypeBuilder` input-classification path (`buildInputType`). |
| **R257** updaterows-walker-sdl-substrate | Backlog | `:17` calls `resolveInput` "the legacy resolver" (`MutationInputResolver.resolveInput` gone R515); `:15`/`:19` reach carriers "via `TableInputType.inputFields()`" (gone R519). | **Re-anchor** both: `resolveInput` → `admitMutationInputFields`, `TableInputType.inputFields()` → per-consumer input resolution. |
| **R337** input-nesting-projection-classification | Backlog | `:30` reaches an input object's fields "via `TableInputType.inputFields()`" as the LSP-hover mechanism. | **Re-anchor** the one mechanism cite to per-consumer input resolution. |
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot composition "in `MutationInputResolver.resolveInput`" (method gone R515). `:124`'s test bullet "Override `@condition` on an `UnboundField`" names the pre-R589 carrier. | **Re-anchor** `:76` to `admitMutationInputFields`, and `:124` to `ConditionOwnedField`. |

### C.7 Condition + projection emitter dissolution drift (carried; R552 + R549)

Condition emission is `render/ConditionGlueRenderer`; projection `render/ProjectionUnitRenderer`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh still incomplete; not touched at all this window.** Still at the symbol: 5× `TypeClassGenerator`, 5× `collectRequiredProjection`, 5× `methodgraph`, `LookupValuesJoinEmitter`, 2× `ParentProjectionContainmentCheck`, 1× `TypeConditionsGenerator`, 4× `InlineTableFieldEmitter`, plus the §C.0/§C.5 carriers and the §C.9 absent-emitter names (21 stale symbol cites confirmed present this window). The body is a pure implementation plan whose class names are wrong, and it is **Ready**, so the stale prose bites the next implementer to pick it up. | **Finish the refresh in one pass** (this row + §C.0 + §C.5 + §C.6 + §C.1 + §C.9): repoint `TypeClassGenerator` / `collectRequiredProjection` → `ProjectionUnitRenderer` / `ProjectionCommands`; drop the `TypeConditionsGenerator`, `ParentProjectionContainmentCheck`, `methodgraph`, `operation()` cites; re-anchor `LookupValuesJoinEmitter` to the render lookup family and the `Operation` seal cites to `OperationMember`; replace the `Inline*Emitter` projection-arm names with the render projection layer and `FkTargetConditionEmitter` with `FkTargetConditionFilter` via `ConditionCommands`. Rows citing `SplitRowsMethodEmitter` stay (live). |
| **R231** emit-text-mapped-enum-fields-as-enum-type | Backlog | `:39` locates "the field-type-emit fork (likely in `TypeClassGenerator` or ...)". | **Re-anchor** the investigation locus to `ProjectionUnitRenderer` / `plan/ProjectionCommands`. |
| **R35** source-orientation-javadocs | Backlog | `:42` enumerates classes for a javadoc sweep, including `TypeClassGenerator` and `TypeConditionsGenerator`, both deleted. | **Re-anchor** the enumeration: drop the two deleted names. Low priority; scope illustrative. |

### C.8 `InputFieldResolution.Unresolved` reshape drift (empty; R585)

R585 reshaped `Unresolved` to `(fieldName, SourceLocation, Rejection)`. Its consumers R66, R213 (§B)
and R209 (§A) hold the residue. **No item remains in this subsection.**

### C.9 Absent projection/condition `*Emitter` names (carried; render-layer refactor)

A family of per-arm projection/condition emitter names six items cite as **live** current-state
classes, all **`grep` = 0 across every main tree** at this HEAD. The work they name lives today in
`ProjectionUnitRenderer` / `ProjectionCommands`, `ConditionGlueRenderer` / `ConditionCommands`, and
`FkTargetConditionFilter`. This subsection adds **no distinct flagged item**; every citer is already
listed above.

| Absent name | Cited-as-live in | Live successor |
|---|---|---|
| `InlineColumnReferenceFieldEmitter` | R333 (`:1890`) | render projection layer (`ProjectionUnitRenderer` / `ProjectionCommands`) |
| `InlineTableFieldEmitter` | R333 (`:1752`,`:1891`), R85 (`:20`,`:45`), R447 (`:23`), R288 (`:24`) | render projection layer |
| `InlineLookupTableFieldEmitter` | R333 (`:1891`), R85 (`:21`,`:46`), R7 (`:31`) | render projection layer |
| `FkTargetConditionEmitter` | R333 (`:1893`), R462 (`:45`, `.emitTerm`) | `FkTargetConditionFilter` via `ConditionCommands` |
| `MutationConditions` (phantom) | R462 (`:57`) | none; drop the shim name |

### C.10 `reflectServiceMethod` / `PkLessParent` / `validateRootInvariants` removal drift (carried; R649)

R649's phase split retired `ServiceCatalog.reflectServiceMethod` (its logic is now
`decodeServiceMethod` + `reduceClaims` + `bindServiceMethod`, decoding to a typed `ServiceSignature`),
`ServiceCatalog.PkLessParent`, and `validateRootInvariants`. The two items below name those as live
current-state symbols; both keep their work valid and need only a repoint. (R72 and R193 land in §B
instead, because for them R649 changed the premise, not just a name.)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R555** deprecate-externalfield-fold-into-service | Ready | Four present-tense cites of `reflectServiceMethod` (`:41`, `:52`, `:103`, `:106`), confirmed still present this window. Every other symbol the item cites (`reflectExternalField`, `validateRootListTableBoundReturnPair`, `validateChildServiceReturnType`, `pickMethod`) survives. | **Re-anchor.** Repoint `reflectServiceMethod` → `decodeServiceMethod` / `ServiceSignature`. R649's "one entry that picks the method once and reads the raw return type" is exactly the seam this item's Design section wants, so the refresh strengthens it. A **Ready** item; refresh before pickup. |
| **R47** service-short-classname-resolution | Backlog | `:15`/`:29`/`:51` name `ServiceCatalog.reflectServiceMethod` (the `Class.forName(className)` site) and "the three/four reflect* sites" as the live edit targets. The `Class.forName` load now lives in `decodeServiceMethod`; `reflectTableMethod`/`reflectExternalField` survive but `reflectServiceMethod` is gone. | **Re-anchor** the class-load site to `decodeServiceMethod` and re-enumerate the reflect* sites (two, not three: `reflectTableMethod`, `reflectExternalField`, plus the decode boundary). Goal (short class-name resolution) intact. Sequence with R72 as its body already notes. |

## D. Structural: (0)

Empty of blocking defects. `changelog.md` carries `next-id: R686`, clearing the max allocated id
(R685). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and the nine non-empty
`depends-on:` edges resolve to present files (enumerated in Changes since). R667/R678's discards and
R681's absorption into R642 left **no** dangling edge, and R617/R639/R648's Done removed no edge that
survives. The roadmap-tool regenerates `README.md` with **no drift** at this HEAD. The eighteen items
filed this window carry well-formed front-matter and read born-current.

One **pre-existing, non-blocking** hygiene note, surviving unchanged:

1. Four **legacy** items still lack a `bucket:` key: R242 (`dml-payload-positional-alignment`),
   R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
   R180 (`record-parent-column-read-helper`). Build tolerates the omission; fold a `bucket:` in
   whenever each is next edited.

## Cross-cutting observations

1. **This window retired the largest symbol set in three windows and staled nothing.** R639's
   session-identity rework dissolved the whole generated-`SessionHook` machinery, `<variables>`,
   `<handle>`, `<stateSurvivesTransactions>`, and the `session-state-convention-fence` lint, but a
   grep of the board finds no active item citing any of them as live. The difference from the R649
   window is process, not luck: R639's team repointed R469, filed R468/R664 against the new `<mount>`
   model, and ran the retirement sweep before Done. The audit-relevant lesson is that a clean
   retirement sweep is what keeps a symbol-retiring window from generating flags.

2. **R193 and R213 are the two overdue subsumption candidates, and neither moved.** R193 asked for
   the sealed parameter classifier that R649's `reduceClaims`/`ParamRole` shipped; R213 holds the
   same shape against R585/R589. Running both re-checks, and most likely closing both as subsumed,
   is the cheapest board-cleaning available; deferring only lets the stale prose keep misleading.

3. **The fact-model cluster is the live edit zone, and its symbol churn is correctly not flagged.**
   R642, R666, R680, R682, R684, R685 (Spec) and R638 (In Progress) reshape `TypeBackingShape`,
   `ClassMemberSlots`, `CompletionData`, `CatalogFactCapture`, `ClasspathScanner` as they draft.
   Because these are actively driven and cite live symbols, their internal churn is not board
   staleness. R681 (`mcp-code-tools-read-the-store`) was correctly absorbed into R642 rather than
   left to rot.

4. **Prefix greps still misjudge the retired service and mutation symbols.** A bare `grep` for
   `PkLessParent` returns the surviving `SourcesOnPkLessParent` error arm; one for `resolveInput`
   returns the unrelated `RecordBindingResolver.resolveInput`; one for `Operation` returns comments
   and a string-literal fact key. Every flag here keys on the fully-qualified member, not the prefix,
   and each stands.

5. **The Ready set is where stale prose bites soonest, and R617 leaving it did not change that.**
   The Ready set is now R333, R427, R467, R555, R650 (R617 -> Done, R650 -> Ready this window). R333
   (§C.5/§C.7), R427 (§C.0) and R555 (§C.10) carry stale cites; R467 (`upgrade-graphql-java-26`) and
   R650 (born-current) are clean. **Refreshing R333, R427, and R555 before pickup remains the
   highest-value hygiene action on the board.**

---

_Review date: 2026-08-17._
