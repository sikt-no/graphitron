# Roadmap staleness audit: 2026-08-12

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `7ed02f4`, committed 2026-08-11 23:03, audited 2026-08-12). The goal is
to find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed or been discarded, or
specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-08-11` staleness audit, which has been deleted;
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
  tracks *architecture* drift. With R595/R610/R603 shipped in prior windows and
  **R589 now Done this window**, both halves of the substrate-plus-derivation
  stack the sweep anticipated have landed; the sweep is now a record of a
  completed adoption rather than a plan.
- `2026-08-06-r222-lineage.md`, the absorption ledger and rejected-design record
  preserved when R222 was discarded.
- `2026-08-06-demand-exemption-census.md`, `2026-08-06-directive-consumer-census.md`,
  `2026-08-06-graphql-java-diff-spike.md`, `2026-08-06-structural-classifier-census.md`,
  the four grounding censuses/spikes filed alongside the fact-base work.
- `classification-test-dsl-inventory.md`, the permanent corpus-retirement inventory
  (its "closed and historical" banner is intact).

## Headline: a busy ~26-hour window (R589, R624, R569, R629, R621 to Done; R634 shipped-and-discarded), the fact-base derivation half completed and closed R221 as subsumed, its symbol retirements strand nothing on active items, no flagged item was repointed in place, and R333 drifted a third consecutive window

Where the prior audit caught a quiet consolidation window, this one is busy:
**five items ran to Done** (R589, R624, R569, R629, R621) and a sixth (R634) was
filed and discarded the same day after shipping its docs page. The decisive event
for a staleness audit is **R589 (`validation-adds-facts`) reaching Done**: the
fact-base derivation half the last three audits tracked as "landing" completed,
and unlike the prior window's additive-only code, R589 **retired symbols**. The
audit-relevant question is therefore the sharp one: what did it retire, and who
cited it. The answer is that its retirements strand **nothing** on an active item,
so the flag total falls rather than rises, and the one active-item reference that
did go stale sits on an item already flagged.

- **R589 (`validation-adds-facts`, Done)** completed the `intent_` derived stratum:
  the authored-claim conflict view replaces four walk-side conflict sites, the
  demand/exemption rules land as rows, and the input classification reshaped.
  **Retired** at the symbol: `PairVerdict` / `pairVerdict` / `reduceDirectiveConflict`
  and the per-position detector sites (`grep` = 0 in main), `UnclassifiedField.definition()`,
  the three-cases-in-one `UnboundField` reading (split so `InputField.ConditionOwnedField`
  carries the `@condition`-owned case and `UnboundField` becomes the genuine-miss carrier),
  and `FieldClassification.Unclassified` (split into `Unresolvable` and `Conflicted`). A
  board-wide `grep` confirms the fully-deleted names (`PairVerdict`, `pairVerdict`,
  `reduceDirectiveConflict`, `UnclassifiedField.definition`) are cited by **`changelog.md`
  only**, so they mint no flag. The one active-item reference the reshape stales is
  **R245** `:124` ("Override `@condition` on an `UnboundField`"), now a `ConditionOwnedField`;
  R245 is already a §C item, so this folds into its existing repoint. **R589 also closed
  R221** (`validator-walks-plain-input-unbound-fields`) as subsumed, deleting its file: the
  malformed `@condition(override:false)` shape now mints at the classification funnel. R221
  was a §B flag in the prior audit; **it leaves the board and §B drops by one.**
- **R624 (`unify-argmapping-resolution-seam`, Done)** routed every directive's `argMapping`
  through one resolution seam and deleted a dead `fieldArgumentNames` overload. The surviving
  `TypeContext.fieldArgumentNames` (LSP) and `parseArgMapping` are **live** (`grep` > 0), so the
  two new items that cite them (R626, and `nested-argmapping-syntax`) are correct. Its internal
  emitter/param-source refactors touch no name any active item cites (`grep` = 0 across the
  roadmap for the removed method set). Filed R625-R628, all born-current.
- **R569 (`mcp-aggregated-diagnostics`, Done)** built the diagnostics stratum and its faceted
  aggregate as the store's first reader. Additive; it explicitly **reversed and dropped** three
  planned sealed-`Rejection` lifts (recorded as a reversal, since that hierarchy is retiring
  vocabulary), so it added no interface an item could come to depend on. Filed R631-R633,
  all born-current. R569 was the prior audit's resolved-in-place §C.8 item; it is now Done and
  off the board entirely.
- **R629 (mountable GraphQL-over-HTTP delegate, Done)** and **R621
  (`legacy-exception-provider-method-size`, Done, gates bypassed at user direction)** shipped
  additive feature/refactor work retiring no cited symbol.
- **R630 (`fact-architecture-docs-home`, In Progress)** landed slices 1-4, migrating the fact
  pipeline overview and **R333's "stable why"** into `docs/architecture/`. This is the third
  consecutive window R333's file was edited (see §C.7); as before, the edit refreshed content
  and **repointed no retired class name**, so R333 stays exactly as inconsistent as it was.

Net: **1 §A / 8 §B / 23 §C / 0 §D.** Flag total moves **33 → 32**: the only change is R221
leaving §B (subsumed by R589). The long-standing dissolution drift (`Operation` seal,
`TableInputType`, the lookup triplet, the `Split*`/`Record*` merge, `planSlug`/`SourceKey.Reader`,
the condition/projection emitters, the absent `*Emitter` names) is entirely unchanged: every
driving symbol re-verified `grep` = 0 at this HEAD, and **no flagged item was repointed in place
this window** (contrast the prior window's R569). R213's deferred subsumption re-check became
**actionable** this window because R589 slice 5 landed; its recommendation is sharpened below.

## Changes since the 2026-08-11 audit

The window runs from the prior audit's baseline HEAD (`f088d28`, 2026-08-10 21:44) to this
HEAD (`7ed02f4`, 2026-08-11 23:03). The visible git horizon on this shallow clone begins at
the prior audit's own commit (`dbb2079`, 2026-08-11 10:27), giving **49 commits** of which
**~10 touched main/test source**, grouped into five Done programmes (R589 tail, R624, R569,
R629, R621) plus R630's in-progress slices and R634's discarded docs page. Effects landing in
the pre-horizon gap (`f088d28`..`dbb2079`, principally R589's Done transition and R221's deletion)
are read from `changelog.md` and the current item files rather than from a diff.

**Code that shipped, and what each did to the symbol set:**

- **R589 → Done** (`beafe4a` carrier split + funnel mints, `d20e9cc` Conflicted projection,
  `d5cec11` + `3f53bab` + `7aefee2` review fixes, over slices 1-4 from the prior window):
  the derivation stratum completed. **Retired** `PairVerdict` / `pairVerdict` /
  `reduceDirectiveConflict`, the per-position detector sites, `UnclassifiedField.definition()`;
  **reshaped** `UnboundField` (split `InputField.ConditionOwnedField`) and `FieldClassification.Unclassified`
  (split `Unresolvable` / `Conflicted`). **Subsumed and deleted R221.** Every fully-deleted
  name is `changelog.md`-only on the board; the reshape stales exactly one active-item line (R245).
- **R624 → Done** (`01d6dd4` the one seam, `9c9d824` four-tier coverage, `a7394c1` rework +
  dead-`fieldArgumentNames` delete): one `argMapping` resolution seam across every directive.
  The deleted `fieldArgumentNames` was a dead overload, not the live `TypeContext.fieldArgumentNames`;
  `parseArgMapping` survives. No name any active item cites was retired. Filed R625-R628.
- **R569 → Done** (`5b0614f` pilot substrate, `10f0711` cutover, `c41f244` stratum DDL + loaders,
  `5abd97b` aggregate + widened filters): additive diagnostics stratum. Dropped three planned
  sealed-`Rejection` lifts as a recorded reversal. Filed R631-R633.
- **R629 → Done** (`fe5f35b` mountable delegate) and **R621 → Done** (docs/roadmap): additive.
- **R630 slices 1-4** (`d37e9b6` slice 1, `f1b5eea` + `07ac443` slice 2, `e75d9dc` slice 3,
  `d5d6c32` slice 4 schema reference from DDL): fact-architecture docs home; slice 2 migrated
  R333's stable rationale. Additive doc + generator work; no cited symbol retired.
- **R634** (`a887dd0` file, `7ed02f4` + `d5c...` history page): filed and discarded the same day
  at user direction; the page lives at `docs/history/road-to-the-relational-core.adoc`.

**Items filed this window (born-current, retired-symbol `grep` = 0 on each):** R625
(`routine-coercing-arg-extractions`), R626 (`lsp-argmapping-routine-coordinate`), R627
(`routine-arg-leaf-cardinality-gate`), R628 (`producer-probe-dotpath-misgrounding`) from R624;
R631 (`graph-ownership-preamble-one-site`), R632 (`residue-drainage-declaration-bound`), R633
(`diagnostics-aggregate-argument-validation`) from R569; R634 filed-and-discarded. Next-id is now
**R635**.

**Transitions:** R589 In Progress → Done (subsuming R221); R624 Spec → Ready → In Progress →
In Review → Done (with a rework bounce); R569 Spec → Ready → In Progress → In Review → Done;
R629 Backlog → Spec → Ready → In Progress → In Review → Done (filed and shipped inside the
window); R621 Spec → Done (gates bypassed at user direction); R630 Backlog → Spec → Ready →
In Progress; R634 filed → Discarded. Actively drafted and correctly **not** flagged as stale:
R630 and R347 (In Progress), R612, R617, R427, R467, R555 (Ready).

**Board accounting.** **186 item files** today (measured), up from 182: id range grew R623 → R634,
with R589/R624/R569/R629/R621 leaving to Done and R221 to subsumption, against seven new files
(R625-R628, R631-R633). Status distribution: **167 Backlog, 11 Spec, 6 Ready, 2 In Progress,
0 In Review, 0 Done**. Tombstone-free (`grep` for `status: Done` in `roadmap/*.md` = 0). No
duplicate `id:`; max allocated id **R634** (discarded, no file), `changelog.md` carries
`next-id: R635`, clearing it. A `depends-on:` sweep resolves all **four** non-empty edges
(R98 → `catalog-check-constraint-validation`, R112 → `capability-catalog`, R298 →
`oneof-augment-defeated-by-descriptions`, R170 → `multi-source-input-validation`) to present
files; **R622's prior `validation-adds-facts` edge was correctly cleared to `[]` when R589
shipped**, so R589's departure leaves no dangling edge. The only structural nits are the same
four **legacy** items still missing a `bucket:` key (§D), all pre-dating this window.

## Scope and method

All **186** `R<n>` item files were reviewed. Every driving symbol below was re-checked against a
fresh `grep` of the main sources (`graphitron`, `graphitron-mcp`, `graphitron-lsp`,
`graphitron-model`, `graphitron-maven-plugin`, `graphitron-fixtures-codegen`), not carried on the
prior audit's word. This window's code retired symbols for the first time in three windows, so the
new retirements were swept against the whole board; the long-standing retirement set was
re-verified rather than assumed.

**Long-standing retirements, re-verified still retired at this HEAD (`grep` = 0 in main real code;
`{@code}`/`{@link}`/comment hits excluded):** `CompileDependencyGraphBuilder`; `RowsMethodBody` /
`RowsMethodSkeleton`; `QueryConditionsGenerator` / `TypeConditionsGenerator`; `TypeClassGenerator` /
`collectRequiredProjection`; `ParentProjectionContainmentCheck` and the `methodgraph` package;
`GraphitronType.TableInputType` / `buildNonTableInputType`; `MutationInputResolver.resolveInput`
(the class survives; the method and its `TableInputType` routing are gone, and the 16 surviving
`resolveInput`-prefixed hits are unrelated methods such as `resolveInputFields` /
`resolveInputElementJavaType` / `RecordBindingResolver.resolveInput`); the lookup triplet
(`LookupTableField` / `BatchedLookupTableField` / `QueryLookupTableField`), `LookupValuesJoinEmitter`;
the `Split*` / `Record*` leaf names; `Rejection.Deferred.planSlug`; `SourceKey.Reader`; the
`Operation` seal and every `Operation.<Arm>` reference (successor `OperationMember`); and the absent
projection/condition `*Emitter` family (`InlineColumnReferenceFieldEmitter`, `InlineTableFieldEmitter`,
`InlineLookupTableFieldEmitter`, `FkTargetConditionEmitter`, the phantom `MutationConditions`), all
`grep` = 0 in every main tree.

**Retirements this window (new):** `PairVerdict`, `pairVerdict`, `reduceDirectiveConflict`,
`UnclassifiedField.definition()` all re-verified `grep` = 0 in main; **cited by `changelog.md` only**,
so each mints no flag. `UnboundField` was **reshaped, not deleted** (16 main files; `ConditionOwnedField`
split out, 11 files), and `FieldClassification.Unclassified` **split** into `Unresolvable` / `Conflicted`
(both live). The reshape stales one active-item line (R245 `:124`); the split is anticipated correctly by
R347 `:294` and touches no other active item, since the surviving `UnclassifiedField` / `UnclassifiedType`
leaves (33 files) are what R66 and `rejection-spec-by-example` cite. The deleted `fieldArgumentNames` was
a dead overload; the live LSP method of the same name and `parseArgMapping` both survive.

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried (2 windows); still Backlog, still fully delivered.** The entire deliverable ("remove the `RejectionKind.AUTHOR_ERROR` default arm and emit `RejectionKind.of(rejection)` consistently with `traceOutput`") is shipped by **R585**. `FieldRegistry.classifyInput` now emits `RejectionKind.of(u.rejection()), u.rejection().message()` on the `Unresolved` arm, with no default-`AUTHOR_ERROR` and no `u.reason()`. Nothing remains to do; this window did not touch it. | **Discard**, recording R585 as the delivery vehicle. Verify once at the symbol (`grep` for `AUTHOR_ERROR` default in `FieldRegistry` and `u.reason()`; both `grep` = 0) before retiring to lineage. |

R520 (`table-on-input-removal-housekeeping`) stays a coherent deliberately-deferred docs tail,
not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (8)

Eight of the prior window's nine carry forward, each re-verified at the symbol this window:
premise-target still `grep` = 0 with a live successor, and none but R213 gained new information.
**R221 left this section** (subsumed by R589, file deleted). **R213 is escalated**: its deferred
"re-check after R589 slice 5" gate came due this window because R589 reached Done, and its own body
already predicts the residue may be empty.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R213** input-field-rejection-attribution | Backlog | **Escalated this window.** R585 (prior window) already inverted the load-bearing premise (`:43`/`:48`/`:54` still say `Unresolved` "has no `SourceLocation`" / "grows a `SourceLocation` field", but the record now carries one). **This window R589 reached Done**, and its occurrence-path derivation delivers exactly the attribution split this item asks for (definition-keyed violations locate at the input field's own location, use-keyed ones at the occurrence path). The item's own fact-base note (`:80`) says "Re-check what remains after R589 slice 5; the residue may be empty": that check is now due. | **Re-derive the residue against shipped R585 + R589; most likely close as subsumed.** Both halves the item scoped have landed: R585 gave `Unresolved` the `(SourceLocation, Rejection)` shape, R589 gave the located-attribution split. If a thin residue survives, re-spec it onto the shipped record and drop the "grows/has no `SourceLocation`" claims and the co-design note; otherwise discard, recording R585 + R589 as the delivery vehicles. |
| **R66** rejection-string-carrier-widening | Backlog | Carried. Phase **A2** ("widen `Unresolved.reason: String` → `rejection: Rejection`") was verbatim what **R585** shipped; the `:28` anchor is now `(fieldName, SourceLocation, Rejection)`. Phases A1 (`ParsedPath.errorMessage`), A3 (`UnboundArg.reason`), B1 (`EnumValidation.Mismatch`), B2 (`TypeBuilder` aggregations) survive. Its `UnclassifiedField`/`UnclassifiedType` cites (`:93`-`:106`) are live. | **Re-spec:** strike A2 as delivered by R585; re-baseline the four surviving phases onto the shipped record (B2's prerequisite is now satisfied); fix the `:25-30` anchors. |
| **R34** nodeid-migration-quickfix | Backlog | Carried, self-contradictory. The "shim facts" driver names three WARN sites **R473 deleted** and **R27 was discarded**; the discard commit half-reconciled the item, leaving the mechanism sections still naming the deleted sites as the live gap. | **Re-spec:** the "shim facts" driver is void. The migration goal (ergonomic `@nodeId` fixes across the sis sites) survives, but its source must be re-derived onto R473's landed grammar rejections or the R589 claim relation. Retitle off "shim facts". |
| **R122** compound-entity-mutations | Backlog | Carried. "Design space narrows under R222" leans on R222's discarded recursive `InputUsage` model; R222 left the board 2026-08-06. `TableTargetField` (added by this item) is live; the `:127` `UnboundField` cite is a live variant name (fine). | **Re-spec the "narrows under R222" section**: drop the discarded `InputUsage` carrier; re-express the nested-input model against the captured `intent_`/`applied_` relations the fact-base architecture adopted. Keep the compound-mutation goal, `@reference(path:)` flattening, and `TableTargetField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Carried. Central target `CompileDependencyGraphBuilder.addFieldEdges` deleted (`grep` = 0); the body's own dissolution condition (`:163`) has occurred. Also cites absent `FkTargetConditionEmitter.emitTerm` (`:45`) and phantom `MutationConditions` (`:57`) as live. | **Re-derive against the plan-projected recompile graph.** Confirm at the symbol whether the nested-fetcher per-field edge is now modeled under `EmitPlan`; if closed, **discard**, else **re-spec**. In the same pass repoint `FkTargetConditionEmitter.emitTerm` → `FkTargetConditionFilter` via `ConditionCommands` and drop `MutationConditions`. |
| **R545** model-free-of-emit-vocabulary | Backlog | Carried. `RowsMethodBody` / `RowsMethodSkeleton` deleted, so the diagnosis and second deliverable are gone. First deliverable survives: `ClassName` / `TypeName` are still model-pervasive. | **Re-spec.** Drop the `RowsMethodBody` diagnosis and second deliverable; keep and re-baseline the first (`JavaTypeRef` replacing `TypeName` / `ClassName`) against the post-R549 model. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Carried. Both named edit-target generators deleted: `QueryConditionsGenerator` (R552), `TypeClassGenerator` (R549). Successors `render/ConditionGlueRenderer` / `render/ProjectionUnitRenderer` live. Also names absent `InlineTableFieldEmitter.java:144` / `InlineLookupTableFieldEmitter.java:218` as live host files. | **Re-derive against the new `render/` layer.** Determine whether the renderers still exhibit the duplicated helper-emission problem; drop every dead `QueryConditionsGenerator.java:NNN` cite and the two absent `Inline*Emitter.java:NNN` file cites. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried. R431 (Done) deleted the target surface: the body cites `SourceKey.Reader.SourceRowsCall` as "the live surface", but `SourceKey` is a plain record with no `Reader`. Successors `KeyLift` / `LifterRef` / sealed `Wrap` live; "R431 ... plans to decompose" reads present tense but R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model; drop the `SourceKey.Reader.SourceRowsCall` re-anchor note and the tense error. |

## C. Outdated: update references only (work valid, refs stale) (23)

Substance intact; names and line numbers drifted. All twenty-three carry from the prior audit;
**none was repointed in place this window** (the only flagged item edited was R333, and its edit
did not touch a retired class name, see §C.7). Every driving symbol re-verified still `grep` = 0.
The one change is additive: **R245 gains a second stale line** this window (§C.6), the
`UnboundField` → `ConditionOwnedField` reshape from R589; it adds no distinct item. §C.9 records
the absent-`*Emitter` driver, which hits six already-listed rows.

### C.0 `Operation` seal fully retired (carried; R563 slice 7)

`Operation` and every `Operation.<Arm>` reference are `grep` = 0. Successor: `OperationMember`;
obligation `MEMBER_ARMS` / `MEMBER_KNOWN_GAPS`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R427** relevance-ranked-search | Ready | `:335` "`operation()` stays `Fetch`" cites the retired accessor; `:339`/`:1162` cite `Operation.Facet` as a live precedent type. | **Re-anchor.** Restate against the member-derived summary fold; repoint `Operation.Facet` onto `OperationMember.Facet`. A **Ready** item; refresh before pickup. |
| **R382** multitable-interface-query-orderby-lowering | Backlog | `:19` cites `operation()` as the live mechanism hardcoding `new OrderBySpec.None()`. | **Re-anchor** to where the hardcoded `OrderBySpec.None` now lives (`OperationMember.OrderBy` sourcing, or `MultiTablePolymorphicEmitter`); verify the ordering gap reproduces. |
| **R562** synthesised-connection-fields-as-coordinates | Backlog | `:17-18` name `Operation.Count`/`Operation.Facet` arms of the `OPERATION_ARMS` obligation as the observable gap; all three names retired. | **Re-anchor** to `OperationMember.Count`/`Facet` / `MEMBER_ARMS` / `MEMBER_KNOWN_GAPS`. Model question intact. |

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
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** to `BatchedTableField`. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38`'s R305 lineage note "collapsed it into `RecordTableField`"; `:107`/`:148`/`:150`/`:284` cite `LookupValuesJoinEmitter`. | **Re-anchor** to `BatchedTableField`; repoint `LookupValuesJoinEmitter` to the render values-join family. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34` name `SplitTableField` / `RecordTableField`; `:24` cites the absent `InlineTableFieldEmitter`. `SplitRowsMethodEmitter` fine. | **Re-anchor** the two variant names to `BatchedTableField`; drop the `InlineTableFieldEmitter` cite. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`'s planned case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R7** decompose-typefetchergenerator | Backlog | `:30` proposes a hypothetical `SplitTableFieldEmitter`; `:32` lists `LookupValuesJoinEmitter`; `:31` labels absent `InlineLookupTableFieldEmitter` "Existing". | **Low priority:** refresh to `BatchedTableFieldEmitter`; repoint the `LookupValuesJoinEmitter` cite; drop the "Existing `InlineLookupTableFieldEmitter`" claim. |
| **R323** nestingfield-multiparent-batchkey-leaves | Backlog | `:18`/`:30` list `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField`. | **Re-anchor** the four names (lookup twins: **+ lookup member**). |

### C.5 `ColumnBackedField` dissolution drift (carried; R508)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | Retired carriers named live across `:570`, `:694`, `:706`, `:727`, `:747-748`, `:798-799`, `:1112`, `:1140`, `:1145`, `:2009`. Part of the one R333 refresh (§C.7). | **Re-anchor** the carrier names to `ColumnBackedField`. Part of the one R333 pass. |

### C.6 `TableInputType` / `resolveInput` removal drift, plus the `UnboundField` reshape (carried; R519 + R515; R589 new this window)

R222 left this subsection via discard; R213 left it (promoted to §B). R589's `UnboundField`
reshape adds one stale line to R245 this window.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R234** jooq-embedded-and-udt-input-backings | Spec | `:15` cites `TypeBuilder.buildNonTableInputType` (`grep` = 0) as the live dispatch to extend. | **Re-anchor** to the current `TypeBuilder` input-classification path (`buildInputType`). |
| **R257** updaterows-walker-sdl-substrate | Backlog | `:17` calls `resolveInput` "the legacy resolver" (method gone R515); `:15`/`:19` reach carriers "via `TableInputType.inputFields()`" (gone R519). | **Re-anchor** both: `resolveInput` → `admitMutationInputFields`, `TableInputType.inputFields()` → per-consumer input resolution. |
| **R337** input-nesting-projection-classification | Backlog | `:30` reaches an input object's fields "via `TableInputType.inputFields()`" as the LSP-hover mechanism. | **Re-anchor** the one mechanism cite to per-consumer input resolution. |
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot composition "in `MutationInputResolver.resolveInput`" (method gone R515). **New this window:** `:124`'s test bullet "Override `@condition` on an `UnboundField`" names the pre-R589 carrier; R589 split the `@condition`-owned input case into `InputField.ConditionOwnedField`. | **Re-anchor** `:76` to `admitMutationInputFields`, and `:124` to `ConditionOwnedField`. |

### C.7 Condition + projection emitter dissolution drift (carried; R552 + R549)

Condition emission is `render/ConditionGlueRenderer`; projection `render/ProjectionUnitRenderer`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh still incomplete after a third consecutive touch-without-repoint.** Still at the symbol: 5× `TypeClassGenerator`, 5× `collectRequiredProjection`, 5× `methodgraph`, `LookupValuesJoinEmitter`, 2× `ParentProjectionContainmentCheck`, 1× `TypeConditionsGenerator`, 4× `InlineTableFieldEmitter`, plus the §C.0/§C.5 carriers and the §C.9 absent-emitter names. This window R630 slice 2 (`f1b5eea`) edited R333 to **migrate its "stable why" into `docs/architecture/`**, refreshing content but **repointing no retired class name**, the third window running that R333 is touched for content and not for symbols. | **Finish the refresh in one pass** (this row + §C.0 + §C.5 + §C.6 + §C.1 + §C.9): repoint `TypeClassGenerator` / `collectRequiredProjection` → `ProjectionUnitRenderer` / `ProjectionCommands`; drop the `TypeConditionsGenerator`, `ParentProjectionContainmentCheck`, `methodgraph`, `operation()` cites; re-anchor `LookupValuesJoinEmitter` to the render lookup family and the `Operation` seal cites to `OperationMember`; replace the `Inline*Emitter` projection-arm names with the render projection layer and `FkTargetConditionEmitter` with `FkTargetConditionFilter` via `ConditionCommands`. Rows citing `SplitRowsMethodEmitter` stay (live). |
| **R231** emit-text-mapped-enum-fields-as-enum-type | Backlog | `:39` locates "the field-type-emit fork (likely in `TypeClassGenerator` or ...)". | **Re-anchor** the investigation locus to `ProjectionUnitRenderer` / `plan/ProjectionCommands`. |
| **R35** source-orientation-javadocs | Backlog | `:42` enumerates classes for a javadoc sweep, including `TypeClassGenerator` and `TypeConditionsGenerator`, both deleted. | **Re-anchor** the enumeration: drop the two deleted names. Low priority; scope illustrative. |

### C.8 `InputFieldResolution.Unresolved` reshape drift (empty this window; R585)

R585 (prior window) reshaped `Unresolved` to `(fieldName, SourceLocation, Rejection)`. Its
consumers R66, R213 (§B above) and R209 (§A) hold the residue. R569, the prior window's one
Spec item here, reached Done this window and left the board. **No item remains in this
subsection.**

### C.9 Absent projection/condition `*Emitter` names (carried; render-layer refactor)

A family of per-arm projection/condition emitter names six items cite as **live** current-state
classes, several with file:line coordinates, all **`grep` = 0 across every main tree** at this
HEAD. The work they name lives today in `ProjectionUnitRenderer` / `ProjectionCommands`,
`ConditionGlueRenderer` / `ConditionCommands`, and the model record `FkTargetConditionFilter`.
This subsection adds **no distinct flagged item**; every citer is already listed above. It is
recorded so the repoint is not lost.

| Absent name | Cited-as-live in | Live successor |
|---|---|---|
| `InlineColumnReferenceFieldEmitter` | R333 (`:1890`) | render projection layer (`ProjectionUnitRenderer` / `ProjectionCommands`) |
| `InlineTableFieldEmitter` | R333 (`:1752`,`:1891`), R85 (`:20`,`:45`), R447 (`:23`), R288 (`:24`) | render projection layer |
| `InlineLookupTableFieldEmitter` | R333 (`:1891`), R85 (`:21`,`:46`), R7 (`:31`) | render projection layer |
| `FkTargetConditionEmitter` | R333 (`:1893`), R462 (`:45`, `.emitTerm`) | `FkTargetConditionFilter` via `ConditionCommands` |
| `MutationConditions` (phantom) | R462 (`:57`) | none; drop the shim name |

## D. Structural: (0)

Empty of blocking defects. `changelog.md` carries `next-id: R635`, clearing the max allocated id
(R634, discarded). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and the four
non-empty `depends-on:` edges resolve to present files (R98 → `catalog-check-constraint-validation`,
R112 → `capability-catalog`, R298 → `oneof-augment-defeated-by-descriptions`, R170 →
`multi-source-input-validation`). R589's departure left **no** dangling `depends-on` edge: R622's
`validation-adds-facts` edge had already been cleared to `[]` when R589 shipped. The seven items
filed this window (R625-R628, R631-R633) carry well-formed front-matter and read born-current.

One **pre-existing, non-blocking** hygiene note, surviving unchanged:

1. Four **legacy** items still lack a `bucket:` key: R242 (`dml-payload-positional-alignment`),
   R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
   R180 (`record-parent-column-read-helper`). Build tolerates the omission; fold a `bucket:` in
   whenever each is next edited.

## Cross-cutting observations

1. **A window that retires symbols can still move the flag total down, if the retirements are
   internal.** R589 deleted `PairVerdict` / `pairVerdict` / `reduceDirectiveConflict` and
   `UnclassifiedField.definition()`, reshaped `UnboundField`, and split `FieldClassification.Unclassified`;
   yet the flag total *fell*, because the fully-deleted names were cited only by `changelog.md` and
   the reshape stales exactly one line on an item already flagged. The audit-relevant property is not
   whether a window retires anything but whether what it retired was load-bearing in some active item's
   prose. Here the fact-base programme retired its own scaffolding, which no roadmap item had reason to
   name.

2. **The fact-base derivation half is done, and it took R221 with it.** For three audits the deferred
   items (R213, R221, R565) waited on "R589 slice N". This window R589 reached Done: R221 was subsumed
   and deleted in the same motion (the model outcome, a flag the owning programme closed before the audit
   had to chase it), and R213's re-check came due. The remaining derivation-deferred item to watch is
   R565, whose `@table`-on-input re-lead is a §C.2 refresh independent of the store.

3. **R333 is now a three-window repeat offender.** It was edited again this window (R630 slice 2 migrated
   its "stable why" into `docs/architecture/`) and again **no retired class name was repointed**. It
   carries the most stale cites on the board and it is a **Ready** item, so the stale prose bites the next
   implementer to pick it up. The pattern holds: an item touched every window for content but never for
   symbols drifts further with each edit. Migrating its rationale to the docs page is good, but it leaves
   the item body a pure implementation plan whose class names are wrong; the one-pass refresh mapped by
   §C.0/§C.5/§C.6/§C.7/§C.9 is now the single highest-value hygiene action on the board.

4. **`resolveInput` is a cautionary count.** A bare `grep` for `resolveInput` returns 16 main hits, which
   would read as "not retired" and quietly invalidate the §C.6 R257/R245 flags. All sixteen are unrelated
   methods (`resolveInputFields`, `resolveInputElementJavaType`, `RecordBindingResolver.resolveInput`,
   `TypeBuilder.resolveInput`); the specific `MutationInputResolver.resolveInput` those items cite is gone,
   surviving only in two explanatory comments. A retirement audit that keys on symbol *prefixes* rather than
   the fully-qualified member will misjudge; the flag stands.

5. **The Ready set is where stale prose bites soonest.** R333, R427, R467, R555, R612, R617 are the six
   Ready items picked up next. R333 (§C.7) and R427 (its superseded `Operation.Facet` precedent, §C.0) are
   the two carrying stale cites; R467, R555, R612, R617 are clean. Refreshing R333 and R427 before pickup
   remains the highest-value hygiene action on the board.

---

_Review date: 2026-08-12._
