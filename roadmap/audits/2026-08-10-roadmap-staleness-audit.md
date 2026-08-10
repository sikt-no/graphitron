# Roadmap staleness audit: 2026-08-10

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `bbc976a`, committed 2026-08-09 22:31, audited 2026-08-10). The goal is
to find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed or been discarded, or
specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-08-07` staleness audit, which has been deleted;
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
  tracks *architecture* drift. With R595 now shipped (Done this window) the sweep's
  substrate half has landed; its derivation half (R589) is still Spec.
- `2026-08-06-r222-lineage.md`, the absorption ledger and rejected-design record
  preserved when R222 was discarded.
- `2026-08-06-demand-exemption-census.md`, `2026-08-06-directive-consumer-census.md`,
  `2026-08-06-graphql-java-diff-spike.md`, `2026-08-06-structural-classifier-census.md`,
  the four grounding censuses/spikes filed alongside the fact-base work.
- `classification-test-dsl-inventory.md`, the permanent corpus-retirement inventory
  (its "closed and historical" banner is intact).

## Headline: the busiest code window in months (seven items to Done, one Discarded), and R585's shipped record-reshape is the new drift engine, minting one obsolete item (R209), two re-specs (R66, R213), and one reference fix (R569); R473's shim deletion and R27's discard leave R34 stranded on a void premise

This was an unusually active source window. Where the prior two audits watched a
mostly-frozen tree, **seven items ran to Done** (R580, R473, R610, R603, R585,
R595, R611) and **one was Discarded** (R27), and the code they shipped is what
moves the drift set:

- **R585 (`input-field-resolution-typed-rejections`, Done) is the window's drift
  engine.** It reshaped `InputFieldResolution.Unresolved` from a prose carrier
  `(fieldName, lookupColumn, reason: String)` into the **located typed rejection**
  `(fieldName, SourceLocation location, Rejection rejection)` that three separate
  Backlog items had each planned to build. That single reshape:
  - **retires R209 outright** (`field-registry-typed-rejection-trace`): its whole
    deliverable, "remove the `RejectionKind.AUTHOR_ERROR` default arm and emit
    `RejectionKind.of(rejection)`", is now in the tree verbatim
    (`FieldRegistry.classifyInput` emits `RejectionKind.of(u.rejection())`). New **§A**.
  - **re-specs R66** (`rejection-string-carrier-widening`): its Phase A2 was
    *verbatim* this reshape and is delivered; four other phases survive. New **§B**.
  - **escalates R213** (`input-field-rejection-attribution`) from §C to **§B**: R585
    inverted its load-bearing premise ("`Unresolved` has no `SourceLocation`") and
    shipped its first Direction bullet ("grows a `SourceLocation` field").
  - **stales one line in R569** (`mcp-aggregated-diagnostics`, Spec): its claim that
    the third fan-in "carries prose and no `Rejection` at all" is now inverted. New **§C**.
- **R473 (`explicit-nodeid-grammar`, Done) + R27 discard stranded R34.** R473 deleted
  the three nodeId synthesis shims and their symbols (`resolveDecodeHelperForTable`,
  `buildInputNodeIdReference`, `typeNamesByTableKey`, `IdReferenceShim`, both shim
  loggers); R27 (`retire-synthesis-shims`) was then Discarded with an empty deletion
  set. R34 (`nodeid-migration-quickfix`), whose title is literally "driven by shim
  facts" and whose "The gap" / "Shape of the fix" sections convert three shim WARN
  sites, was only **half-reconciled** on discard (its Sequencing bullets acknowledge
  the deletion; its mechanism sections still describe the deleted sites as live). New **§B**.
- **R580's shim retirement resolved on its own.** The prior audit's watch item
  ("if R580 gates to Done, R473's rejection deliverable is pre-delivered") closed
  cleanly: R580 and R473 both shipped and were reconciled in-flight; R273 was
  re-reconciled this window and now names `resolveDecodeHelperForTable` correctly as
  *deleted*, so it is **not** a flag.
- **R595/R610/R603 shipped the fact store, its partition dimension, and its first
  post-capture oracle family.** All three are new substrate; items citing them were
  updated in-window ("catch up to the shipped R610 and R603") and none created a new
  symbol flag. R589's Spec already tracks R585's new record shape correctly (`:99`,
  `:101`), so the fact-base derivation item is current, not stale.

A board-wide safety sweep also surfaced a **previously-untracked driver**: a family
of projection/condition `*Emitter` names (`InlineColumnReferenceFieldEmitter`,
`InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`, `FkTargetConditionEmitter`,
and the phantom `MutationConditions`) that six items cite as live emitter classes,
with file:line coordinates, but which are **`grep` = 0 in every main tree** at this HEAD.
The work they name lives in the render layer today (`ProjectionUnitRenderer` /
`ConditionGlueRenderer` / `ConditionCommands`) and the model record `FkTargetConditionFilter`.
This adds **no new flagged items** (all six, R333/R462/R85/R447/R288/R7, were already
flagged for the tracked dissolution drift), but it is a distinct driver folded into their
recommended actions and recorded as §C.9.

Net: **1 §A / 9 §B / 24 §C / 0 §D.** Flag total moves **30 → 34**: R209 (§A), R66
and R34 (§B) and R569 (§C) are new; R213 moves §C→§B (no count change); R222's
discard row already left last window. The long-standing dissolution drift
(`Operation` seal, `TableInputType`, `ColumnBackedField`, the lookup triplet, the
`Split*`/`Record*` merge, `planSlug`/`SourceKey.Reader`, the condition/projection
emitters) is entirely unchanged: every driving symbol re-verified `grep` = 0 at this
HEAD, and no §C item was repointed in place this window (R333, the worst case, *grew*
again without repointing).

## Changes since the 2026-08-07 audit

The prior audit's stated HEAD (`453b916`, 2026-08-06) predates this shallow history,
so the git-visible window is the audit commit (`8db0651`, 2026-08-08 23:15) to this
HEAD (`bbc976a`, 2026-08-09 22:31): **49 commits**, of which **8 touched main/test
source**. The reconstruction below uses `roadmap/changelog.md` (which records every
Done/Discarded with commit refs) for the transitions that predate the git boundary.

**Code that shipped (the eight source-touching commits):**

- **R585 → Done** (`eb27e57` + `ee9f4af`, rework `bf4971b`): `InputFieldResolution.Unresolved`
  reshaped to `(fieldName, SourceLocation, Rejection)`; three fan-ins collapsed onto the
  typed carrier; `FieldRegistry.classifyInput` now projects `RejectionKind.of(u.rejection())`.
  **The window's most consequential retirement**: the `reason: String` carrier is `grep` = 0.
- **R473 → Done** (`b48b0f8` + `7eb474f`, gate rework `dd77f66`): implicit nodeId reading
  comes from the SDL; the three synthesis shims and their symbols deleted
  (`resolveDecodeHelperForTable`, `buildInputNodeIdReference`, `typeNamesByTableKey`,
  `IdReferenceShim`, both shim loggers; all `grep` = 0). Decode is typeName-first via
  `NodeIndex.forName`. `typeName:` on `Node.id` now rejects.
- **R580 → Done** (`f9c27dc` roadmap; classifier `cefb16a` predates the boundary): node
  inference ships; `implements Node` declares nodehood, `@node` supplies/overrides identity.
- **R610 → Done** (`b534810` core, rework `e344cac`): `graph_name VARCHAR NOT NULL` leads the
  primary key of every `graphql_`/`graphitron_` base relation (83 of them); one fact store
  holds several graphs without fusing them. Touches the capture stack and `GraphitronModelStore`.
- **R603 → Done** (`79b60a2` core, rework `76ac937`): the `javac_diagnostic` family, the store's
  sixth family and first post-capture oracle writer, lands in `CompileFacts`/`CompileDiagnostic`.
- **R595 → Done** (five slices, predates boundary): the `graphitron-model` fact store completed.
- **R611 → Done** (`ae883c6`): the currency nudge watches both the OSS and commercial jOOQ coordinates.
- **R27 → Discarded** (`54540b6`): "its shim-deletion set is empty", R473 having deleted the shims.

**Items filed this window (born-current unless flagged):** the id range R604-R617 was
allocated (next-id now `R618`). On the board and read against the current model:
R604 (`roadmap-tool-adoc-cell-escape-dedup`), R606 (`facets-container-derives-from-the-store`),
R608 (`directive-conflict-directives-contract-sweep`), R609 (`capture-load-residuals`),
R612 (`maven-config-fact-family`, Spec), R613 (`lookup-generated-column-filters`, In Review),
R614 (`lookupkey-per-input-field-doc-claim`), R615 (`idreffixture-purpose-comment-stale`),
R616 (`collapsed-plan-sha-citations`), R617 (`lookup-positional-contract-unimplemented`).
A retired-symbol scan of all ten is clean; the one apparent hit, R615 naming `IdReferenceShim`,
is the item's *subject* (it exists to retire an init.sql comment that cites the deleted shim
tests), so it is born-current and self-aware, not a flag. R605/R607/R610/R611 are allocated but
off-board: R610/R611 → Done (above), R605/R607 were disposed before the shallow boundary and
carry no dangling reference.

**Transitions:** R473 Spec → In Progress → In Review → Done (six revision passes, densest
single-item churn of the window); R580 In Review → Done; R610 Spec → Ready → In Progress →
In Review → Done; R603 the same arc; R585/R595/R611 → Done; R467
(`upgrade-graphql-java-26`) Backlog → Spec → **Ready** (federation 7.0.0 unblocked it);
R613 Spec → **In Review**. Actively drafted and correctly **not** flagged: R589 (Spec),
R613 (In Review), R347 (In Progress).

**Board accounting.** **178 item files** today, unchanged in count from 178 (id range
grew R603→R617; eight items left to Done/Discarded, ten filed). Status distribution:
**159 Backlog, 13 Spec, 4 Ready, 1 In Progress, 1 In Review, 0 Done**. Tombstone-free
(`grep` for `status: Done` in `roadmap/*.md` = 0). No duplicate `id:`; max allocated id
**R617**, `changelog.md` carries `next-id: R618`, clearing it. A `depends-on:` sweep resolves
all four non-empty edges (`catalog-check-constraint-validation`, `capability-catalog`,
`oneof-augment-defeated-by-descriptions`, `multi-source-input-validation`) to present files;
the eight departed items left no dangling edge. The only structural nits are four **legacy**
items still missing a `bucket:` key (§D), all pre-dating this window.

## Scope and method

All **178** `R<n>` item files were reviewed. Because main source moved substantially this
window, every driving symbol below was re-checked against a fresh `grep` of the main sources
(`graphitron`, `graphitron-mcp`, `graphitron-lsp`, `graphitron-model`, `graphitron-maven-plugin`,
`graphitron-fixtures-codegen`), not carried on the prior audit's word.

**Long-standing retirements, re-verified still retired at this HEAD (`grep` = 0 in main
real code; `{@code}`/`{@link}`/comment hits excluded):** `CompileDependencyGraphBuilder`;
`RowsMethodBody` / `RowsMethodSkeleton`; `QueryConditionsGenerator` / `TypeConditionsGenerator`;
`TypeClassGenerator` / `collectRequiredProjection`; `ParentProjectionContainmentCheck` and the
`methodgraph` package; `GraphitronType.TableInputType` / `buildNonTableInputType`;
`MutationInputResolver.resolveInput`; the lookup triplet (`LookupTableField` /
`BatchedLookupTableField` / `QueryLookupTableField`), `LookupField`, `LookupValuesJoinEmitter`;
`TableOnInputRejection`; the `Split*` / `Record*` leaf names (`SplitTableField` /
`RecordTableField` / `SplitLookupTableField` / `RecordLookupTableField`); the `ColumnField`
family (the 7 surviving `ColumnField` real-code hits are `keyColumnFields` substrings, per the
prior audit); `Rejection.Deferred.planSlug`; `SourceKey.Reader` (one javadoc hit noting it
retired); the `Operation` seal and every `Operation.<Arm>` reference including `Operation.Facet`
/ `Operation.Count` and the `operation()` accessor (successor `OperationMember`).

**New retirements this window, verified `grep` = 0 with a live successor named:**
- **R585:** `InputFieldResolution.Unresolved`'s `reason: String` carrier (successor: the record's
  `Rejection rejection` component; `Unresolved` itself is live at
  `InputFieldResolution.java:22-26`). `FieldRegistry`'s `RejectionKind.AUTHOR_ERROR` default arm
  (successor: `RejectionKind.of(u.rejection())`, live at `FieldRegistry.java:120-121`).
- **R473:** `resolveDecodeHelperForTable`, `buildInputNodeIdReference`, `typeNamesByTableKey`,
  `IdReferenceShim`, both shim loggers (successor: SDL-derived decode via `NodeIndex.forName`,
  live). `BuildContext.classifyInputFieldInternal` **survives** (live at `BuildContext.java:2564`),
  so item cites of that method name are current; only the shim *arms* inside it were removed.

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **New this window.** The entire deliverable ("remove the `RejectionKind.AUTHOR_ERROR` default arm and emit `RejectionKind.of(rejection)` consistently with `traceOutput`") is shipped by **R585**. `FieldRegistry.classifyInput` (`:106-123`) now emits `RejectionKind.of(u.rejection()), u.rejection().message()` on the `Unresolved` arm, with no default-`AUTHOR_ERROR` and no `u.reason()`. Fork (a) (the record widening) is R585's landed change; the consumer-side collapse this item scoped is also in the tree. Nothing remains to do. | **Discard**, recording R585 as the delivery vehicle. Verify once at the symbol (`grep` for `AUTHOR_ERROR` default in `FieldRegistry`, `u.reason()`; both `grep` = 0) before retiring to lineage. Its own fact-base note already anticipated "the item collapses to the record widening or retires with the registry." |

R520 (`table-on-input-removal-housekeeping`) stays a coherent deliberately-deferred docs tail,
not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (9)

Six carried from the prior window (each re-verified at the symbol: premise-target still
`grep` = 0 with a live successor, unchanged this window because no code moved on those surfaces);
**three are new or escalated** this window, all driven by R585's reshape or the R473/R27 shim
retirement.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R66** rejection-string-carrier-widening | Backlog | **New this window.** Phase **A2** ("widen `InputFieldResolution.Unresolved.reason: String` → `rejection: Rejection`", `:76-88`) was *verbatim* what **R585** shipped; the `:28` anchor `record Unresolved(..., String reason)` is now `(fieldName, SourceLocation, Rejection)`. The item's own co-design note (`:81-88`) predicted "whichever lands first should carry the combined `(SourceLocation, Rejection)` shape" and R585 did exactly that. Phases A1 (`ParsedPath.errorMessage`), A3 (`UnboundArg.reason`), B1 (`EnumValidation.Mismatch`), B2 (`TypeBuilder` aggregations) survive. | **Re-spec:** strike A2 as delivered by R585; re-baseline the four surviving phases onto the shipped record (B2's "after Phase A2 each `Unresolved` carries a `Rejection`" prerequisite is now satisfied, so B2 can proceed directly); fix the `:25-30` anchors. |
| **R213** input-field-rejection-attribution | Backlog | **Escalated §C→§B this window.** R585 **inverted the load-bearing premise**: `:43`/`:48`/`:69` state `InputFieldResolution.Unresolved` "has no `SourceLocation`" and "missing a location field", but the record now carries one; and the first Direction bullet (`:54`, "`Unresolved` grows a `SourceLocation` field") is **shipped**. The carried §C.6 stale cite also persists (`:64` "route through `TableInputType` classification", `grep` = 0 since R519). | **Re-spec** against the shipped located-typed-rejection record: drop the "grows a `SourceLocation`" / "has no `SourceLocation`" claims and the co-design note (R585 resolved it); re-baseline the surviving deliverable (`condErrors` → `List<LocatedRejection>`, `Resolution.Rejected` list-carrying, `validateUnclassifiedField` fan-out) onto `(fieldName, SourceLocation, Rejection)`; fix the `TableInputType` cite to per-consumer resolution. Per the fact-base note, re-check residue after R589 slice 5. |
| **R34** nodeid-migration-quickfix | Backlog | **New this window.** Title "driven by shim facts" and the "The gap" (`:16-24`) / "Shape of the fix" step 1 (`:30-33`) convert three shim WARN sites to `BuildWarning`s, but **R473 deleted all three sites and their loggers** and **R27 was discarded**. The discard commit half-reconciled the item (Sequencing `:45` and Out-of-scope `:52` now acknowledge deletion), leaving the body **self-contradictory**: the mechanism sections still name the deleted sites as the live gap. | **Re-spec:** the "shim facts" driver is void. The migration goal (ergonomic `@nodeId` fixes across ~250 sis sites) survives, but its source must be re-derived: R473's landed grammar rejections/warnings, or (per the item's own fact-base note) the R589 claim relation once inferred claims carry join witnesses. Retitle off "shim facts". |
| **R122** compound-entity-mutations | Backlog | Carried. "Design space narrows under R222" leans on R222's discarded recursive `InputUsage` model as a live design input; R222 left the board 2026-08-06 and R122 was not edited since. `TableTargetField` (which this item adds) is live. | **Re-spec the "narrows under R222" section**: drop the dependence on the discarded `InputUsage` carrier; re-express the cross-table nested-input model against the captured `intent_`/`applied_` relations the fact-base architecture adopted (`2026-08-06-fact-base-impact-sweep.md` §R222). Keep the compound-mutation goal, `@reference(path:)` flattening, and `TableTargetField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Carried. Central target `CompileDependencyGraphBuilder.addFieldEdges` deleted (`grep` = 0); the body's own stated dissolution condition (`:163`) has occurred. Also cites the absent `FkTargetConditionEmitter.emitTerm` (`:45`) and phantom `MutationConditions` (`:57`) as live (§C.9). | **Re-derive against the plan-projected recompile graph.** Confirm at the symbol whether the nested-fetcher per-field edge is now modeled under `EmitPlan`; if closed, **discard** and record it, else **re-spec** onto the plan-projection. In the same pass, repoint `FkTargetConditionEmitter.emitTerm` → `FkTargetConditionFilter` via `ConditionCommands`, and drop the `MutationConditions` shim name. |
| **R545** model-free-of-emit-vocabulary | Backlog | Carried. `RowsMethodBody` / `RowsMethodSkeleton` deleted, so the diagnosis and second deliverable are gone. First deliverable survives: `ClassName` / `TypeName` are still model-pervasive. | **Re-spec.** Drop the `RowsMethodBody` diagnosis and second deliverable; keep and re-baseline the first (`JavaTypeRef` replacing `TypeName` / `ClassName`) against the post-R549 model. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Carried. Both named edit-target generators deleted: `QueryConditionsGenerator` (R552), `TypeClassGenerator` (R549). Successors `render/ConditionGlueRenderer` / `render/ProjectionUnitRenderer` live. Also names `InlineTableFieldEmitter.java:144` / `InlineLookupTableFieldEmitter.java:218` (`:20-21`, `:45-46`) as live host files to delete; both absent (§C.9). | **Re-derive against the new `render/` layer.** Determine whether the renderers still exhibit the duplicated helper-emission problem; drop every dead `QueryConditionsGenerator.java:NNN` cite and the two absent `Inline*Emitter.java:NNN` file cites (the `$fields` host is now `ProjectionUnitRenderer`). |
| **R221** validator-walks-plain-input-unbound-fields | Backlog | Carried, also fact-base-annotated. R519 deleted `TableInputType` / `validateTableInputType`; successor `collectInputFieldRejections` live; R566 removed `@table`-input classification. | **Close as subsumed or re-derive.** The fact-base sweep names this in R589 slice 4/5. If not building against the store, re-derive around `collectInputFieldRejections`; either way the `validateTableInputType` cites must go. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried. R431 (Done) deleted the target surface: the body cites `SourceKey.Reader.SourceRowsCall` as "the live surface", but `SourceKey` is a plain record with no `Reader`. Successors `KeyLift` / `LifterRef` / sealed `Wrap` live; "R431 ... plans to decompose" reads present tense but R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model; drop the `SourceKey.Reader.SourceRowsCall` re-anchor note and the tense error. The goal is intact. |

## C. Outdated: update references only (work valid, refs stale) (24)

Substance intact; names and line numbers drifted. Twenty-three carry verbatim from the
2026-08-07 audit (minus R213, promoted to §B); **R569 is new** this window (R585 reshape).
Every driving symbol re-verified still `grep` = 0, none repointed in place this window.
§C.9 records a driver (absent `*Emitter` names) that hits six already-listed rows without
adding a distinct item.

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
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh still incomplete, and grew again.** Unchanged from the prior audit at the symbol: still 6× `TypeClassGenerator`, 5× `collectRequiredProjection`, 5× `methodgraph`, 3× `LookupValuesJoinEmitter`, 2× `ParentProjectionContainmentCheck`, 1× `TypeConditionsGenerator`, plus the §C.0/§C.5 carriers. The window's edit caught R333 up to shipped R610/R603 (fact-base content) **without** repointing any retired symbol, so it is now more internally inconsistent than at the prior audit. Its "twenty-two `*Emitter`" current-state inventory (`:1888-1893`) also lists five absent names (§C.9). | **Finish the refresh in one pass** (this row + §C.0 + §C.5 + §C.6 + §C.1 + §C.9): repoint `TypeClassGenerator` / `collectRequiredProjection` → `ProjectionUnitRenderer` / `ProjectionCommands`; drop the `TypeConditionsGenerator`, `ParentProjectionContainmentCheck`, `methodgraph`, `operation()` cites; re-anchor `LookupValuesJoinEmitter` to the render lookup family and the `Operation` seal cites to `OperationMember`; replace the `InlineColumnReferenceFieldEmitter` / `InlineTableFieldEmitter` / `InlineLookupTableFieldEmitter` projection-arm names with the render projection layer and `FkTargetConditionEmitter (R330)` with `FkTargetConditionFilter` via `ConditionCommands`. The fact-base sweep's R333 section maps the same regions; rows citing `SplitRowsMethodEmitter` stay (live). |
| **R231** emit-text-mapped-enum-fields-as-enum-type | Backlog | `:39` locates "the field-type-emit fork (likely in `TypeClassGenerator` or ...)". | **Re-anchor** the investigation locus to `ProjectionUnitRenderer` / `plan/ProjectionCommands`. |
| **R35** source-orientation-javadocs | Backlog | `:42` enumerates classes for a javadoc sweep, including `TypeClassGenerator` and `TypeConditionsGenerator`, both deleted. | **Re-anchor** the enumeration: drop the two deleted names. Low priority; scope illustrative. |

### C.8 `InputFieldResolution.Unresolved` reshape drift (new; R585)

R585 reshaped `Unresolved` from a `reason: String` prose carrier to `(fieldName, SourceLocation,
Rejection)`. R66/R209/R213 are the primary consumers (handled in §A/§B above); one further Spec
item carries a single stale line.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R569** mcp-aggregated-diagnostics | Spec | `:472` states `BuildContext.classifyInputFieldInternal` "returns `InputFieldResolution.Unresolved`, which carries prose and no `Rejection` at all", used as the premise that "the identity cannot move until the record ... changes". R585 **inverted** this: `Unresolved` now carries a typed `Rejection`. The method name itself (`classifyInputFieldInternal`) is still live. The window's edit updated R569 for R610/R603 but not for R585. | **Re-anchor (not full re-spec).** Restate `:472` against the shipped record: the third fan-in already hands a typed `Rejection`; the identity constraint the paragraph rests on is (at least partly) satisfied by R585. Also tidy the departed-slug prose note (`:487` "if `mcp-server-instruction-routing` has not [landed]" — R584 is Done). The MCP-aggregation subject is intact. |

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

Empty of blocking defects. `changelog.md` carries `next-id: R618`, clearing the max allocated id
(R617). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and the four non-empty
`depends-on:` edges resolve to present files. The eight items that left the board this window
(R580, R473, R610, R603, R585, R595, R611, R27) left **no dangling `depends-on` edge**. The ten
items filed this window (R604, R606, R608, R609, R612-R617) carry well-formed front-matter and
were read against the current model.

Two **pre-existing, non-blocking** hygiene notes, surviving unchanged:

1. Four **legacy** items still lack a `bucket:` key: R242 (`dml-payload-positional-alignment`),
   R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
   R180 (`record-parent-column-read-helper`). Build tolerates the omission; fold a `bucket:` in
   whenever each is next edited.
2. R569's `:487` departed-slug prose note (folded into its §C.8 recommended action above).

## Cross-cutting observations

1. **A record reshape is a retirement event for every item that planned to build it.** R585's
   single change to `InputFieldResolution.Unresolved` moved four items at once: one obsolete
   (R209, wholly delivered), one re-spec (R66, one phase delivered), one escalation (R213,
   premise inverted), one reference fix (R569). The three Backlog items had *each* independently
   scoped a piece of the same widening and cross-referenced one another in a "sequence the three
   knowingly" co-design note; R585 landed the union and settled the sequence. The lesson: when a
   shared data structure lands, sweep every item that named it, not just the item that shipped it.

2. **A discard plus a deletion can strand an item on a void premise even after a partial
   reconciliation.** R27's discard commit touched R34 to acknowledge the shims were gone, but only
   in the Sequencing and Out-of-scope bullets; the mechanism sections still describe the deleted
   shim WARN sites as the live gap. Half-reconciliation reads worse than none, because the item now
   contradicts itself. When an item's *title* names a retired construct ("driven by shim facts"),
   that is the tell that the reconciliation was cosmetic.

3. **The prior audit's watch item resolved itself.** R580 gating to Done alongside R473 (both
   reconciled in-flight) closed the R580/R473 spec tension cleanly, and R273 was re-reconciled to
   name `resolveDecodeHelperForTable` as *deleted*. Not every flagged tension needs an audit action;
   some are dissolved by the same window that would have flagged them.

4. **Fact-base substrate landed; the derivation half is still Spec.** R595 (store) and R610/R603
   (partition dimension, first oracle family) shipped, so the companion sweep's substrate premise is
   no longer speculative. R589 (derivation) is Spec and already tracks R585's record shape correctly,
   so it is current, not stale. Items whose fact-base notes defer "re-check after R589 slice N"
   (R213, R221, R565) still wait on that Spec, not on shipped code.

5. **Some drift hides behind names that were never in the tree.** The `Inline*Emitter` /
   `FkTargetConditionEmitter` family (§C.9) is not a retirement the changelog records; the names
   are `grep` = 0 at HEAD and appear only in roadmap prose, cited as live classes with concrete
   file:line coordinates. A symbol-retirement audit keyed only on "what the changelog retired"
   would miss them; the catch was a whole-board reverse sweep of every class-shaped cite against
   the current tree. Worth keeping as a standing check: a cited `*.java:NNN` coordinate that
   resolves to no file is stale regardless of whether the symbol ever shipped.

6. **The Ready set is where stale prose bites soonest, and R333 remains the worst case.** R333,
   R427, R467 and R555 are picked up next. R333 carries the most stale cites on the board (five
   drivers) and grew *again* this window (catching up to R610/R603) without repointing, so it is
   more internally inconsistent than at the prior audit. Its refresh, mapped identically by §C.5,
   §C.7 and the fact-base sweep's R333 section, is overdue and should land in one pass before
   pickup. R427's superseded `Operation.Facet` precedent is the other Ready-set refresh; R467 and
   R555 are current.

---

_Review date: 2026-08-10._
