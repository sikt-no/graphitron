# Roadmap staleness audit: 2026-08-14

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `04fc0f0`, committed 2026-08-13 22:59, audited 2026-08-14). The goal is
to find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed or been discarded, or
specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-08-13` staleness audit, which has been deleted;
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

## Headline: a busy service-cluster window that retired symbols for the first time in two windows. R649's phase split dissolved `reflectServiceMethod` / `ServiceCatalog.PkLessParent` / `validateRootInvariants`, staling four items three of which were never flagged before; R612, R645, R646 also reached Done, none of them staling a cited symbol

Where the prior audit caught a quiet roadmap-churn window that retired nothing, this
one is the opposite in exactly the dimension a staleness audit cares about. **Four
items reached Done (R612, R645, R646, R649), and one of them (R649) retired a symbol
set that four active items name as live.** R649 split the `@service` reflection
boundary into `decodeServiceMethod` / `reduceClaims` / `bindServiceMethod` and
retired the `reflectServiceMethod` entry point, dissolved `ServiceCatalog.PkLessParent`
into a sealed `ParentContext` (`Root` / `TableParent` / `RecordParent`), and deleted
`validateRootInvariants`, `looksLikeSourcesShape`, and `couldBeSourcesShape`. That is
the decisive fact this window: those retirements re-verify `grep` = 0 at this HEAD, and
four items that cite them (**R72, R193, R47, R555**) drift, two of them into re-spec
territory because R649 delivered part of what they asked for.

- **R649 (`service-coordinate-rejection-precedence`, Done)** is the window's staleness
  driver. `ServiceDirectiveResolver.resolve` used to reflect the method before it
  classified the coordinate's return type, so a coordinate problem surfaced as a Java
  signature problem. The fix is a phase split: `ServiceCatalog` decodes to a typed
  `ServiceSignature` (carrying no `java.lang.reflect.Method`), `reduceClaims` mints one
  sealed `ParamRole` per parameter, and a new classify phase decides every rejection
  in one stated order before binding. **Retired:** `ServiceCatalog.reflectServiceMethod`,
  `ServiceCatalog.PkLessParent`, `ServiceDirectiveResolver.validateRootInvariants`,
  `looksLikeSourcesShape`, `couldBeSourcesShape`, and drifted root-batch message text.
  Filed R654, R655.
- **R612 (`maven-config-fact-family`, Done)** landed the Maven/pom configuration fact
  family, retiring `SchemaInput.plain`, `SchemaProblemDiagnostic.normaliseLoaded`,
  `SdlFactCapture.regularFile`, and dissolving `SchemaInputExpander`. **None of those is
  cited by any active item** (the one item that names `SchemaProblemDiagnostic`, R637,
  cites the surviving class, not the deleted `normaliseLoaded` method). Filed R652, R653.
- **R645 (`nested-depth-projected-reference-and-computed-leaves`, Done)** admitted
  projected `@reference`/`@externalField` leaves under a nesting field, renaming
  `validateField`'s body to `validateVariantSpecific` (the outer `validateField` walk
  survives). No active item cites `validateField`, so this rename stales nothing. R645
  also prompted the in-place edit that closed R323's `LookupTableField` sub-question
  (see §C.4).
- **R646 (`externalfield-parent-table-assignability`, Done)** widened `reflectExternalField`'s
  parent-table check to read its previously-unread argument; it retired no symbol. Filed R647.

Net: **1 §A / 10 §B / 25 §C / 0 §D**, flag total **36**, up from 32 (the four new flags
are R72 + R193 to §B and R47 + R555 to §C, all four driven by R649). The long-standing
dissolution drift (`Operation` seal, `TableInputType`, the lookup triplet, the
`Split*`/`Record*` merge, `planSlug`/`SourceKey.Reader`, the condition/projection emitters,
the absent `*Emitter` names, the R589 `UnboundField` reshape) is entirely unchanged: every
driving symbol re-verified `grep` = 0 at this HEAD. **R333 remains the standing high-value
refresh** and was not touched this window, so it drifts no further but is repaired no closer.

## Changes since the 2026-08-13 audit

The window runs from the prior audit's baseline HEAD (`029a727`, 2026-08-12 22:38) to this
HEAD (`04fc0f0`, 2026-08-13 22:59): **130 commits**. Unlike the prior window, this one
touched real source across `graphitron`, `graphitron-lsp`, `graphitron-model`,
`graphitron-maven-plugin`, and `graphitron-mcp` (the R638/R639/R648 in-flight commits all
sit on trunk, plus the four Done items' slices).

**The four items that reached Done, and what each did to the symbol set:**

- **R649 → Done:** the `@service` phase split. **Retired** `reflectServiceMethod`,
  `ServiceCatalog.PkLessParent`, `validateRootInvariants`, `looksLikeSourcesShape`,
  `couldBeSourcesShape`. Successors `decodeServiceMethod` / `reduceClaims` /
  `bindServiceMethod` / `ServiceSignature` / `ParamRole` / `ParentContext` all `grep` > 0
  and live. `SourcesOnPkLessParent` (a `ServiceMethodCallError` arm) **survives** and is a
  prefix collision with the retired `PkLessParent`, not the same symbol.
- **R612 → Done:** the config fact family. Retired `SchemaInput.plain`,
  `SchemaProblemDiagnostic.normaliseLoaded`, `SdlFactCapture.regularFile`, `SchemaInputExpander`;
  reshaped `SchemaRecipe.Binding` → sealed `Entry`, narrowed `GraphIdentity` to `(name, baseDir)`.
  No active item cites any retired name.
- **R645 → Done:** `validateField` body → `validateVariantSpecific`; outer `validateField` walk
  survives. No active citer of `validateField`.
- **R646 → Done:** `reflectExternalField` parent-table check widened; no retirement.

**Verification that the symbol set is as recorded:** a fresh `grep` of the six main source
trees confirms every R649 and R612 retirement at `grep` = 0, every successor live, and the
whole long-standing retirement set unchanged (see Scope and method).

**Items filed this window (born-current, retired-symbol `grep` = 0 on each):** R647
(`condition-table-parameter-anchor-assignability`), R648 (`service-batch-key-author-declared`),
R650 (`root-connection-over-discriminated-interface`), R652 (`store-source-stamp-comment-follows-the-arm`),
R653 (`schema-extension-ordinal-is-iteration-order`), R654 (`resolver-coordinate-verdict-precedence-sweep`),
R655 (`service-phase-split-residue`), R656 (`sourcerow-declared-service-batch-key`),
R657 (`list-cardinality-service-batch-key`), R659 (`routine-chain-order-directive-silent-noop`),
R660 (`routine-write-key-capture-unordered`), R661 (`batched-discriminated-interface-child`),
R662 (`routine-chain-ordering-spans-nodes`), R663 (`split-query-child-list-drops-default-order`),
R664 (`execution-input-staged-builder`). R644, R651, R658 were filed and left no file
(R644 absorbed into R639, R651 absorbed into R650, R658 absorbed into R393). Next-id is now **R665**.

**Transitions:** R612, R645, R646, R649 In Review → Done; R648 to In Review (built on R649, now Done);
R639 In Progress → In Review; R638 Spec → In Progress; R650, R659, R661 drafted to Spec;
fifteen new files filed to Backlog/Spec. Actively drafted and correctly **not** flagged as stale:
R347, R638 (In Progress), R639, R648 (In Review), R650, R659, R661 (Spec, born-current).

**Board accounting.** **206 item files** today (measured), up from 192: id range grew R643 → R664
(21 ids allocated), with R612/R645/R646/R649 leaving to Done and R644/R651/R658 discarded without a
file, against fifteen surviving new files. Status distribution: **182 Backlog, 15 Spec, 5 Ready,
2 In Progress, 2 In Review, 0 Done**. Tombstone-free (`grep` for `status: Done` in `roadmap/*.md` = 0).
No duplicate `id:`; `changelog.md` carries `next-id: R665`, clearing the max allocated id (R664). A
`depends-on:` sweep resolves all **six** non-empty edges (R98 → `catalog-check-constraint-validation`,
R112 → `capability-catalog`, R298 → `oneof-augment-defeated-by-descriptions`, R170 →
`multi-source-input-validation`, R650 → `batched-discriminated-interface-child`, and R662 →
`routine-chain-order-directive-silent-noop`, the two new edges this window) to present files. The
prior window's R643 → `maven-config-fact-family` edge is gone with R612's Done. The only structural
nits are the same four **legacy** items still missing a `bucket:` key (§D), all pre-dating this window.

## Scope and method

All **206** `R<n>` item files were reviewed. Every driving symbol below was re-checked against a
fresh `grep` of the main sources (`graphitron`, `graphitron-mcp`, `graphitron-lsp`,
`graphitron-model`, `graphitron-maven-plugin`, `graphitron-fixtures-codegen`), not carried on the
prior audit's word. Because this window *did* change in-scope `src/**/*.java`, the checks were run
in full rather than assumed.

**R649 retirements, verified retired at this HEAD (`grep` = 0 in main real code):**
`ServiceCatalog.reflectServiceMethod`, `ServiceCatalog.PkLessParent` (the surviving
`SourcesOnPkLessParent` is a distinct `ServiceMethodCallError` arm, not this symbol),
`ServiceDirectiveResolver.validateRootInvariants`, `looksLikeSourcesShape`, `couldBeSourcesShape`.
Successors verified live: `decodeServiceMethod`, `reduceClaims`, `bindServiceMethod`,
`ServiceSignature`, `ParamRole`, `ParentContext`. The sibling reflect helpers `reflectTableMethod`
and `reflectExternalField`, and the validators `validateChildServiceReturnType`,
`validateRootListTableBoundReturnPair`, `computeExpectedServiceReturnType`,
`validateTableRecordSourceParentTable`, `pickMethod`, `dtoSourcesRejectionReason`,
`classifySourcesType` all **survive** (this is the boundary that keeps R47/R555 in §C rather than §B).

**R612 retirements, verified retired:** `SchemaInput.plain`, `SchemaProblemDiagnostic.normaliseLoaded`,
`SdlFactCapture.regularFile`, `SchemaInputExpander`, all `grep` = 0. The `SchemaProblemDiagnostic`
class survives (R637 cites the class, not the method).

**Long-standing retirements, re-verified still retired at this HEAD (`grep` = 0 in main real code;
`{@code}`/`{@link}`/comment hits excluded):** `CompileDependencyGraphBuilder`; `RowsMethodBody` /
`RowsMethodSkeleton`; `QueryConditionsGenerator` / `TypeConditionsGenerator`; `TypeClassGenerator` /
`collectRequiredProjection`; `ParentProjectionContainmentCheck` and the `methodgraph` package;
`GraphitronType.TableInputType` / `buildNonTableInputType`; the lookup triplet
(`LookupTableField` / `BatchedLookupTableField` / `QueryLookupTableField`), `LookupValuesJoinEmitter`;
the `Split*` / `Record*` leaf names; `Rejection.Deferred.planSlug`; `SourceKey.Reader`; the
`Operation` seal and every `Operation.<Arm>` reference (successor `OperationMember`); the R589
retirements (`PairVerdict` / `pairVerdict` / `reduceDirectiveConflict`); and the absent
projection/condition `*Emitter` family (`InlineColumnReferenceFieldEmitter`, `InlineTableFieldEmitter`,
`InlineLookupTableFieldEmitter`, `FkTargetConditionEmitter`, the phantom `MutationConditions`), all
`grep` = 0 in every main tree.

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried (4 windows); still Backlog, still fully delivered.** The entire deliverable ("remove the `RejectionKind.AUTHOR_ERROR` default arm and emit `RejectionKind.of(rejection)` consistently with `traceOutput`") is shipped by **R585**. Re-verified at the symbol this window: `AUTHOR_ERROR` and `u.reason()` both `grep` = 0 in `FieldRegistry.java`, `RejectionKind.of(...)` emitted on the `Unresolved` arms. Nothing remains to do; this window did not touch it. | **Discard**, recording R585 as the delivery vehicle. The symbol check is done and clean; retire to lineage. |

R520 (`table-on-input-removal-housekeeping`) stays a coherent deliberately-deferred docs tail,
not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (10)

The prior eight all carry forward unchanged; each re-verified at the symbol this window with the
premise-target still `grep` = 0 and a live successor. **Two new items join this window, both driven
by R649's phase split** (R72, R193): their premise did not merely drift a name, it partly shipped.
**R213 remains escalated** (its "re-check after R589 slice 5" gate is now overdue).

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R193** service-param-classification-sealed-hierarchy | Backlog | **New this window; R649 shipped the deliverable.** The whole item asks to refactor `reflectServiceMethod`'s `sourcesShape.isEmpty()` predicate chain (anchored at `ServiceCatalog.java:258-329`) into a sealed classifier plus one switch that owns the rejection text. R649 delivered exactly that: `reduceClaims` mints one sealed `ParamRole` per parameter ("candidacy decided once as a carried value instead of by three predicates"), the classify phase owns rejection ordering, and both the anchor (`reflectServiceMethod`) and a named helper (`looksLikeSourcesShape`) are now `grep` = 0. | **Re-derive the residue against shipped R649; most likely close as subsumed** (the R213/R585 pattern). If a thin diagnostic-arm residue survives `ParamRole`, re-spec it onto the shipped classifier and drop the `reflectServiceMethod:258-329` / `looksLikeSourcesShape` anchors; otherwise discard, recording R649 as the delivery vehicle. |
| **R72** slim-servicecatalog-to-lookup | Backlog | **New this window; premise materially changed.** The current-state diagnosis is a line-by-line census of pre-R649 `ServiceCatalog` (`reflectServiceMethod` ~170 lines; duplicated message text at `ServiceCatalog.java:194-196` vs `ServiceDirectiveResolver.java:290-292`; not-found rejection at `:186`/`:375`/`:472`; parameter-names rejection at `:227`/`:408`). R649 dissolved `reflectServiceMethod` into `decodeServiceMethod` / `reduceClaims` / `bindServiceMethod` and moved policy toward the resolver, which is part of what this item wanted. `reflectTableMethod` (~90) and `reflectExternalField` (~50) survive. | **Re-spec.** Re-derive the diagnosis against the post-R649 split: measure how much slimming R649 already accomplished, drop every stale `ServiceCatalog.java:NNN` line cite, and re-baseline the remaining goal (the two surviving reflect helpers and the lookup scaffolding) on the new shape. |
| **R213** input-field-rejection-attribution | Backlog | **Escalated, still unactioned (two windows overdue).** R585 inverted the load-bearing premise (the record now carries a `SourceLocation` the item still says it lacks), and R589's occurrence-path derivation (Done) delivers the attribution split the item asks for. The item's own note ("Re-check what remains after R589 slice 5; the residue may be empty") is due and nobody has run it. | **Re-derive the residue against shipped R585 + R589; most likely close as subsumed.** If a thin residue survives, re-spec onto the shipped record and drop the "grows/has no `SourceLocation`" claims; otherwise discard, recording R585 + R589 as the delivery vehicles. |
| **R66** rejection-string-carrier-widening | Backlog | Carried. Phase **A2** ("widen `Unresolved.reason: String` → `rejection: Rejection`") was verbatim what **R585** shipped. Phases A1 (`ParsedPath.errorMessage`), A3 (`UnboundArg.reason`), B1 (`EnumValidation.Mismatch`), B2 (`TypeBuilder` aggregations) survive. | **Re-spec:** strike A2 as delivered by R585; re-baseline the four surviving phases onto the shipped record; fix the `:25-30` anchors. |
| **R34** nodeid-migration-quickfix | Backlog | Carried, self-contradictory. The "shim facts" driver names three WARN sites **R473 deleted** and **R27 was discarded**; the discard commit half-reconciled the item, leaving the mechanism sections still naming the deleted sites as the live gap. | **Re-spec:** the "shim facts" driver is void. The migration goal survives, but its source must be re-derived onto R473's landed grammar rejections or the R589 claim relation. Retitle off "shim facts". |
| **R122** compound-entity-mutations | Backlog | Carried. "Design space narrows under R222" leans on R222's discarded recursive `InputUsage` model; R222 left the board 2026-08-06. `TableTargetField` (added by this item) is live. | **Re-spec the "narrows under R222" section**: drop the discarded `InputUsage` carrier; re-express the nested-input model against the captured `intent_`/`applied_` relations. Keep the compound-mutation goal, `@reference(path:)` flattening, and `TableTargetField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Carried. Central target `CompileDependencyGraphBuilder.addFieldEdges` deleted (`grep` = 0); the body's own dissolution condition (`:163`) has occurred. Also cites absent `FkTargetConditionEmitter.emitTerm` (`:45`) and phantom `MutationConditions` (`:57`) as live. | **Re-derive against the plan-projected recompile graph.** Confirm whether the nested-fetcher per-field edge is now modeled under `EmitPlan`; if closed, **discard**, else **re-spec**. In the same pass repoint `FkTargetConditionEmitter.emitTerm` → `FkTargetConditionFilter` via `ConditionCommands` and drop `MutationConditions`. |
| **R545** model-free-of-emit-vocabulary | Backlog | Carried. `RowsMethodBody` / `RowsMethodSkeleton` deleted, so the diagnosis and second deliverable are gone. First deliverable survives: `ClassName` / `TypeName` are still model-pervasive. | **Re-spec.** Drop the `RowsMethodBody` diagnosis and second deliverable; keep and re-baseline the first (`JavaTypeRef` replacing `TypeName` / `ClassName`) against the post-R549 model. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Carried. Both named edit-target generators deleted: `QueryConditionsGenerator` (R552), `TypeClassGenerator` (R549). Successors `render/ConditionGlueRenderer` / `render/ProjectionUnitRenderer` live. Also names absent `InlineTableFieldEmitter.java:144` / `InlineLookupTableFieldEmitter.java:218` as live host files. | **Re-derive against the new `render/` layer.** Determine whether the renderers still exhibit the duplicated helper-emission problem; drop every dead `QueryConditionsGenerator.java:NNN` cite and the two absent `Inline*Emitter.java:NNN` file cites. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried. R431 (Done) deleted the target surface: the body cites `SourceKey.Reader.SourceRowsCall` as "the live surface", but `SourceKey` is a plain record with no `Reader`. Successors `KeyLift` / `LifterRef` / sealed `Wrap` live; "R431 ... plans to decompose" reads present tense but R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model; drop the `SourceKey.Reader.SourceRowsCall` re-anchor note and the tense error. |

## C. Outdated: update references only (work valid, refs stale) (25)

Substance intact; names and line numbers drifted. The prior twenty-three all carry from the prior
audit; **two were edited in place this window without repairing their flagged drift** (R323 in §C.4,
R555 which joins §C new below), and every long-standing driving symbol re-verified still `grep` = 0.
**Two new items join, both from R649** (R47, R555, in §C.10). §C.9 records the absent-`*Emitter`
driver, which hits six already-listed rows and adds no distinct item.

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
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** to `BatchedTableField`. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38`'s R305 lineage note "collapsed it into `RecordTableField`"; `:107`/`:148`/`:150`/`:284` cite `LookupValuesJoinEmitter`. | **Re-anchor** to `BatchedTableField`; repoint `LookupValuesJoinEmitter` to the render values-join family. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34` name `SplitTableField` / `RecordTableField`; `:24` cites the absent `InlineTableFieldEmitter`. `SplitRowsMethodEmitter` fine. | **Re-anchor** the two variant names to `BatchedTableField`; drop the `InlineTableFieldEmitter` cite. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`'s planned case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R7** decompose-typefetchergenerator | Backlog | `:30` proposes a hypothetical `SplitTableFieldEmitter`; `:32` lists `LookupValuesJoinEmitter`; `:31` labels absent `InlineLookupTableFieldEmitter` "Existing". | **Low priority:** refresh to `BatchedTableFieldEmitter`; repoint the `LookupValuesJoinEmitter` cite; drop the "Existing `InlineLookupTableFieldEmitter`" claim. |
| **R323** nestingfield-multiparent-batchkey-leaves | Backlog | **Edited this window, but the flagged drift is untouched.** The R645 cross-link and the closed `LookupTableField` sub-question were added, yet `:18`/`:30` still list `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField` as the planned enum arm. | **Re-anchor** the four names to `BatchedTableField` (lookup twins: **+ lookup member**). The core BatchKey-scope goal is untouched and stays open. |

### C.5 `ColumnBackedField` dissolution drift (carried; R508)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | Retired carriers named live across `:570`, `:694`, `:706`, `:727`, `:747-748`, `:798-799`, `:1112`, `:1140`, `:1145`, `:2009`. Part of the one R333 refresh (§C.7). | **Re-anchor** the carrier names to `ColumnBackedField`. Part of the one R333 pass. |

### C.6 `TableInputType` / `resolveInput` removal drift, plus the `UnboundField` reshape (carried; R519 + R515; R589)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R234** jooq-embedded-and-udt-input-backings | Spec | `:15` cites `TypeBuilder.buildNonTableInputType` (`grep` = 0) as the live dispatch to extend. | **Re-anchor** to the current `TypeBuilder` input-classification path (`buildInputType`). |
| **R257** updaterows-walker-sdl-substrate | Backlog | `:17` calls `resolveInput` "the legacy resolver" (method gone R515); `:15`/`:19` reach carriers "via `TableInputType.inputFields()`" (gone R519). | **Re-anchor** both: `resolveInput` → `admitMutationInputFields`, `TableInputType.inputFields()` → per-consumer input resolution. |
| **R337** input-nesting-projection-classification | Backlog | `:30` reaches an input object's fields "via `TableInputType.inputFields()`" as the LSP-hover mechanism. | **Re-anchor** the one mechanism cite to per-consumer input resolution. |
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot composition "in `MutationInputResolver.resolveInput`" (method gone R515). `:124`'s test bullet "Override `@condition` on an `UnboundField`" names the pre-R589 carrier. | **Re-anchor** `:76` to `admitMutationInputFields`, and `:124` to `ConditionOwnedField`. |

### C.7 Condition + projection emitter dissolution drift (carried; R552 + R549)

Condition emission is `render/ConditionGlueRenderer`; projection `render/ProjectionUnitRenderer`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh still incomplete; not touched at all this window.** Still at the symbol: 5× `TypeClassGenerator`, 5× `collectRequiredProjection`, 5× `methodgraph`, `LookupValuesJoinEmitter`, 2× `ParentProjectionContainmentCheck`, 1× `TypeConditionsGenerator`, 4× `InlineTableFieldEmitter`, plus the §C.0/§C.5 carriers and the §C.9 absent-emitter names. R333's rationale migrated to `docs/architecture/` in a prior window; the body left behind is a pure implementation plan whose class names are wrong, and it is **Ready**, so the stale prose bites the next implementer to pick it up. | **Finish the refresh in one pass** (this row + §C.0 + §C.5 + §C.6 + §C.1 + §C.9): repoint `TypeClassGenerator` / `collectRequiredProjection` → `ProjectionUnitRenderer` / `ProjectionCommands`; drop the `TypeConditionsGenerator`, `ParentProjectionContainmentCheck`, `methodgraph`, `operation()` cites; re-anchor `LookupValuesJoinEmitter` to the render lookup family and the `Operation` seal cites to `OperationMember`; replace the `Inline*Emitter` projection-arm names with the render projection layer and `FkTargetConditionEmitter` with `FkTargetConditionFilter` via `ConditionCommands`. Rows citing `SplitRowsMethodEmitter` stay (live). |
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

### C.10 `reflectServiceMethod` / `PkLessParent` / `validateRootInvariants` removal drift (new; R649)

R649's phase split retired `ServiceCatalog.reflectServiceMethod` (its logic is now
`decodeServiceMethod` + `reduceClaims` + `bindServiceMethod`, decoding to a typed `ServiceSignature`),
`ServiceCatalog.PkLessParent`, and `validateRootInvariants`. The two items below name those as live
current-state symbols; both keep their work valid and need only a repoint. (R72 and R193 land in §B
instead, because for them R649 changed the premise, not just a name.)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R555** deprecate-externalfield-fold-into-service | Ready | Four present-tense cites of `reflectServiceMethod` (`:41` "accepts parameters classified as ...", `:52` "the strict expected-type comparison in `reflectServiceMethod`", `:103` "passes the null into `reflectServiceMethod`", `:106` "`reflectServiceMethod` runs its own `pickMethod`"). Every other symbol the item cites (`reflectExternalField`, `validateRootListTableBoundReturnPair`, `validateChildServiceReturnType`, `pickMethod`) survives. | **Re-anchor.** Repoint `reflectServiceMethod` → `decodeServiceMethod` / `ServiceSignature`. R649's "one entry that picks the method once and reads the raw return type" is exactly the seam this item's Design section wants, so the refresh strengthens it. A **Ready** item; refresh before pickup. |
| **R47** service-short-classname-resolution | Backlog | `:15`/`:29`/`:51` name `ServiceCatalog.reflectServiceMethod` (the `Class.forName(className)` site) and "the three/four reflect* sites" as the live edit targets. The `Class.forName` load now lives in `decodeServiceMethod`; `reflectTableMethod`/`reflectExternalField` survive but `reflectServiceMethod` is gone. | **Re-anchor** the class-load site to `decodeServiceMethod` and re-enumerate the reflect* sites (two, not three: `reflectTableMethod`, `reflectExternalField`, plus the decode boundary). Goal (short class-name resolution) intact. Sequence with R72 as its body already notes. |

## D. Structural: (0)

Empty of blocking defects. `changelog.md` carries `next-id: R665`, clearing the max allocated id
(R664). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and the six non-empty
`depends-on:` edges resolve to present files (R98 → `catalog-check-constraint-validation`, R112 →
`capability-catalog`, R298 → `oneof-augment-defeated-by-descriptions`, R170 →
`multi-source-input-validation`, R650 → `batched-discriminated-interface-child`, R662 →
`routine-chain-order-directive-silent-noop`). R644/R651/R658's discards left **no** dangling edge, and
R612's Done removed the prior window's R643 → `maven-config-fact-family` edge cleanly. The fifteen
surviving items filed this window carry well-formed front-matter and read born-current.

One **pre-existing, non-blocking** hygiene note, surviving unchanged:

1. Four **legacy** items still lack a `bucket:` key: R242 (`dml-payload-positional-alignment`),
   R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
   R180 (`record-parent-column-read-helper`). Build tolerates the omission; fold a `bucket:` in
   whenever each is next edited.

## Cross-cutting observations

1. **This window retired symbols, so the flag total moved for the first time in two windows.**
   R649's phase split is the whole story: it dissolved the `@service` reflection boundary and staled
   four items (R72, R193, R47, R555), two into re-spec because the split *delivered* part of what they
   scoped. The audit-relevant property is whether a window retired or renamed a symbol some active item
   names; this one did, and the three other Done items (R612, R645, R646) did not, which is why only the
   R649 four are new.

2. **R193 is the clearest new subsumption candidate, and R213 is still the overdue one.** R193 asked for
   the sealed parameter classifier that R649's `reduceClaims`/`ParamRole` shipped, the same shape R213
   holds against R585/R589. Running both re-checks, and most likely closing R193 and R213 as subsumed, is
   the cheapest board-cleaning available; deferring only lets the stale prose keep misleading.

3. **R648 is In Review and correctly not flagged, though it carries pre-R649 framing that self-resolves.**
   Its motivation sections still name `reflectServiceMethod` / `PkLessParent` / `validateRootInvariants`
   in present tense as the gap R649 closed, but its Implementation section is already reconciled ("Landed
   early, by the precedence item") and its Retired-vocabulary list names `ServiceCatalog.PkLessParent` as
   "already retired by the precedence item". Because it is actively driven and deletes on Done, its stale
   framing is not board staleness; it is noted here only so a reader knows it was weighed.

4. **The service cluster is the live edit zone, so `grep`-by-prefix would misjudge it.** A bare `grep` for
   `PkLessParent` returns the surviving `SourcesOnPkLessParent` error arm; one for `resolveInput` returns
   unrelated `resolveInputFields`; the specific retired members (`ServiceCatalog.PkLessParent`,
   `MutationInputResolver.resolveInput`) are gone. Every flag here keys on the fully-qualified member, not
   the prefix, and each stands.

5. **The Ready set is where stale prose bites soonest.** R333, R427, R467, R555, R617 are the Ready items
   next in line. R333 (§C.5/§C.7), R427 (its superseded `Operation.Facet` precedent, §C.0) and now
   **R555** (§C.10, `reflectServiceMethod`) carry stale cites; R467 and R617 are clean. Refreshing R333,
   R427, and R555 before pickup remains the highest-value hygiene action on the board.

---

_Review date: 2026-08-14._
