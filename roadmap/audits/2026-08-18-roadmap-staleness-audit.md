# Roadmap staleness audit: 2026-08-18

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `ad9d068`, committed 2026-08-17 21:05, audited 2026-08-18). The goal is
to find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed or been discarded, or
specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-08-17` staleness audit, which has been deleted;
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

## Headline: one Done item retired a symbol set, and it staled exactly one Backlog reference. R57's FK-target translation dissolved `remoteIfReferenceJoin`/`translatedFkRejection` and replaced the `liftedSourceColumns` carrier slot with the sealed `FilterBinding`; every affected active citer reads the surviving resolver-side symbols except R135, whose two test-plan anchors name the retired carrier accessor. The 13 new items filed this window are born-current, and all 36 prior flags carry forward unrepaired

Where the prior window retired the largest symbol set in three windows and staled
nothing, **this window retired a smaller set and staled precisely one active item**,
because R57's own retirement sweep repointed its live citers but a Backlog test-plan
item two hops downstream was outside its sweep. **One item reached Done since the prior
audit (R57)**, one is newly In Review (R650), and one moved Ready to In Progress (R642).

- **R57 (`nodeid-fk-target-arg-join-translation`, Done)** is the window's staleness
  driver. It made an `@nodeId` argument/filter whose FK targets columns *other than*
  the NodeType's key columns emit a correlated `EXISTS` (the `BodyParam.RemoteColumnPredicate`
  shape) on the read path instead of rejecting at classify time. The structural half named
  an axis that had three implicit spellings and collapsed them onto the sealed **`FilterBinding`**
  (`Local(List<ColumnRef> ownTableColumns)` and a payload-free `Remote`). **Retired:**
  `FieldBuilder.remoteIfReferenceJoin`, `FieldBuilder.translatedFkRejection`, and the
  `liftedSourceColumns` slot on both reference *carriers* (`InputField.ColumnBackedReferenceField`,
  `ArgumentRef.ScalarArg.ColumnBackedArg`), now `FilterBinding binding`. **Verified retired**
  (`grep` = 0 in main real code): `remoteIfReferenceJoin`, `translatedFkRejection`, cited only in
  `changelog.md`. **Survives** and is correctly cited by live items: `liftedSourceColumns` on the
  resolver's `JoinPath` and `Resolved.FkTarget.DirectFk` carrier (the tuple R131's permutation
  reorders), `Resolved.FkTarget.TranslatedFk` / `DirectFk`, `BodyParam.RowEq` / `RowIn`,
  `CallSiteExtraction.NodeIdDecodeKeys` (+ `.ThrowOnMismatch`), `ArgCallEmitter`,
  `CompositeDecodeHelperRegistry`. **One active item drifts: R135** (see §C.11). Filed R691, R692;
  also deferred the write-target translation, multi-hop paths, and the encode-direction emitter.
- **R650 (`root-connection-over-discriminated-interface`, In Review)** landed its
  implementation this window (In Progress to In Review), including the build-enforced
  retirement of the discriminated-interface **cross-table `LEFT JOIN`** wording, replaced by a
  capped correlated subselect and pinned in `RetiredVocabularyGuardTest`. The retired phrasing is
  cited by **no active item but R650 itself** (updated in the same commit); the other five
  `LEFT JOIN` mentions in the roadmap (R333, R242, R697, R393, R112) are the unrelated
  base/detail joined-table use, not the retired discriminated-interface mechanism. **No external
  item drifts.** R650 is In Review, not Done, so its file survives and is correctly current.
- **R642 (`catalog-facts-readers-move-to-the-store`, In Progress)** moved Ready to In Progress and
  churned heavily (slices 1-3 of the fact-store census landed: `CatalogQueries`, `CatalogCorpus`,
  `CorpusTable`, `StoreFixture` added; no source file deleted). Its symbol churn is the live edit
  zone and is correctly **not** flagged.

Net: **1 §A / 10 §B / 26 §C / 0 §D**, flag total **37**, up one from the prior audit's 36 (R135
enters §C.11). **Not one of the 36 carried flags was edited this window**, so every prior flag
holds at its prior line anchors, and every long-standing retired symbol re-verifies `grep` = 0 at
this HEAD. The dissolution drift the prior audits track (`Operation` seal, `TableInputType`, the
lookup triplet, the `Split*`/`Record*` merge, `planSlug`/`SourceKey.Reader`, the condition/projection
emitters, the absent `*Emitter` names, the R589 `UnboundField` reshape, and the R649
`reflectServiceMethod`/`PkLessParent` family) is entirely unchanged. **R333 remains the standing
high-value refresh** and was not touched this window.

## Changes since the 2026-08-17 audit

The prior audit was committed at `ce49fcd` (2026-08-17 08:24) with a stated baseline of next-id
**R686** (220 item files). Current HEAD is `ad9d068` (2026-08-17 21:05), next-id **R702**, **231**
item files. The window spans the R686 -> R701 id allocations, the R57 Done transition, and the
R650 / R642 moves. (As the prior audit noted, its own stated baseline HEAD was rewritten out of
history; the window here is anchored on the audit file's own commit `ce49fcd`, which resolves.)

**Item that reached Done this window, and what it did to the symbol set:**

- **R57 (In Review -> Done, `00e1aac`):** FK-target argument/filter translation. **Retired**
  `remoteIfReferenceJoin`, `translatedFkRejection`, and the `liftedSourceColumns` slot on the two
  reference carriers (now the sealed `FilterBinding`). Successor read-path emission is
  `BodyParam.RemoteColumnPredicate` (a correlated `EXISTS`). Spun out R691
  (`multi-hop-nodeid-filter-single-fk-claim`) and R692 (`inert-element-less-reference-rejection`).

**Verification that R57's retirement staled one item (the window's decisive check):** the retired
names `remoteIfReferenceJoin` and `translatedFkRejection` `grep` = 0 across main real code and the
roadmap (changelog-only). The `liftedSourceColumns` **slot** was retired only on the two reference
carriers; the identically-named component on the resolver's `JoinPath` and `Resolved.FkTarget.DirectFk`
**survives**, so items citing the resolver-side tuple are unaffected. Of the five active items
citing an R57-adjacent symbol, four read the surviving sense: **R136** (`nodeid-fk-permutation-execution-tier`,
resolver-side `DirectFk.liftedSourceColumns` and `BodyParam.RowEq`), **R673** (`nodeid-arg-dispatches-on-typeid`,
`NodeIdDecodeKeys.ThrowOnMismatch`), **R267** (`nodeid-encoder-deprecated-convert`, `NodeIdDecodeKeys`
consumers `ArgCallEmitter` / `CompositeDecodeHelperRegistry`), and the resolver-side anchors of R135
itself. The one drifted item is **R135** (`multi-hop-nodeid-fk-permutation-test`), whose `:17` and
`:23` name the retired `InputField.ColumnBackedReferenceField.liftedSourceColumns()` carrier accessor
(now `FilterBinding.Local`); its `:13`/`:21` resolver-side cites are current.

**Items filed this window (R686 -> R701), all born-current** (every cited current-state symbol
resolves to a live main-source location; no premise found already delivered):
R686 (`error-handler-description-overrides-message`, Spec), R687 (`dml-carrier-errors-field-blocks-return-derived-table`, Spec),
R688 (`nested-backing-class-binary-name-in-emit`), R689 (`phase-varying-index-reads-lack-a-not-built-arm`),
R691 (`multi-hop-nodeid-filter-single-fk-claim`), R692 (`inert-element-less-reference-rejection`),
R693 (`flatten-grouping-input-onto-service-bean`, Spec), R694 (`service-bean-helper-dedup-by-binding-shape`),
R695 (`javabean-unbound-input-field-lint`), R696 (`conflict-message-leaves-the-intent-view`),
R697 (`folded-name-columns-on-base-relations`, Spec), R698 (`views-carry-keys-not-payloads`),
R701 (`capture-declares-the-columns-it-writes`). **R690 (`error-directive-database-handler-reference-drift`)
was discarded** (folded into R686 move 5, `0c4a72b`); **R699 and R700 were filed and discarded
without leaving a file** (`921a025`, `c7e4408`). R691 and R692 are R57's spun-out follow-ups, born
against the new `FilterBinding` model. Next-id is now **R702**.

**Transitions:** R57 to Done; R650 In Progress -> In Review (via a Ready round-trip, `43293b0` ->
`8963951` -> `76a4ad9`); R642 Ready -> In Progress. Actively drafted and correctly **not** flagged
as stale: R642, R666, R680, R682, R684, R685, R693, R697, R686, R687 (the fact-model / service /
error-handler clusters, born-current), R638 and R347 (In Progress), R650 (In Review).

**Board accounting.** **231 item files** today (measured), up from 220: id range grew R686 -> R701
(16 ids allocated), with R57 leaving to Done and R690/R699/R700 discarded without a surviving file,
against thirteen surviving new files. Status distribution: **198 Backlog, 22 Spec, 5 Ready, 3 In
Progress, 1 In Review, 0 Done**. Tombstone-free (`grep` for `status: Done` in `roadmap/*.md` = 0).
No duplicate `id:`; `changelog.md` carries `next-id: R702`, clearing the max allocated id (R701). A
`depends-on:` sweep resolves all **eight** non-empty edges to present files (down one from nine:
R680's second edge to `catalog-facts-readers-move-to-the-store` was dropped when its bookkeeping was
removed this window). The roadmap-tool regenerates `README.md` with **no drift** at this HEAD (the
README diff this window is the ordinary regeneration). The only structural nits are the same four
**legacy** items still missing a `bucket:` key (§D), all pre-dating this window.

## Scope and method

All **231** `R<n>` item files were reviewed. Every driving symbol below was re-checked against a
fresh `grep` of the main sources (`graphitron`, `graphitron-mcp`, `graphitron-lsp`, `graphitron-model`,
`graphitron-maven-plugin`, `graphitron-fixtures-codegen`), not carried on the prior audit's word.
Because this window changed in-scope `src/**/*.java` (additively; **no** Java file was deleted), the
checks were run in full rather than assumed.

**R57 retirements, verified at this HEAD:** `remoteIfReferenceJoin`, `translatedFkRejection` `grep` = 0
in main real code and in the roadmap. The sealed `FilterBinding` (`Local` / `Remote`) is live on
`InputField.ColumnBackedReferenceField` and `ArgumentRef.ScalarArg.ColumnBackedArg`, replacing the
carrier `liftedSourceColumns` slot. The resolver-side `liftedSourceColumns` (on `JoinPath` and
`Resolved.FkTarget.DirectFk`) **survives**, along with `TranslatedFk`, `DirectFk`, `BodyParam.RowEq`
/ `RowIn`, `RemoteColumnPredicate`, `CallSiteExtraction.NodeIdDecodeKeys` (+ `.ThrowOnMismatch`),
`ArgCallEmitter`, `CompositeDecodeHelperRegistry`.

**R650 retirement, verified:** the discriminated-interface cross-table `LEFT JOIN` wording is
build-enforced retired (`RetiredVocabularyGuardTest`), replaced by the capped correlated subselect.
Cited as live by no active item but R650 itself.

**R209 delivery, re-verified (§A):** `AUTHOR_ERROR` `grep` = 0 in `FieldRegistry.java`;
`RejectionKind.of(u.rejection())` emitted on the `Unresolved` arms (`FieldRegistry.java:121,142`).
The `AUTHOR_ERROR` hits elsewhere (`BuildContext`, `FieldBuilder`, the `RejectionKind` enum) are
unrelated. The deliverable is fully shipped by R585.

**Long-standing retirements, re-verified still retired at this HEAD (`grep` = 0 in main real code;
`{@code}`/`{@link}`/comment hits excluded):** the R649 family (`reflectServiceMethod`,
`looksLikeSourcesShape`, `validateRootInvariants`); `CompileDependencyGraphBuilder`; `RowsMethodBody`;
`QueryConditionsGenerator` / `TypeClassGenerator` / `TypeConditionsGenerator`; `buildNonTableInputType`;
`LookupValuesJoinEmitter`; `InlineTableFieldEmitter`; `FkTargetConditionEmitter`;
`ParentProjectionContainmentCheck`; `Rejection.Deferred.planSlug`; the R589 `reduceDirectiveConflict`.
Prefix greps still misjudge the retired service/mutation symbols (`PkLessParent` returns the surviving
`SourcesOnPkLessParent` arm; `resolveInput` returns `RecordBindingResolver.resolveInput`; `Operation`
returns comments and a string-literal fact key); every flag below keys on the fully-qualified member.

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried (6 windows); still Backlog, still fully delivered.** The entire deliverable ("remove the `RejectionKind.AUTHOR_ERROR` default arm and emit `RejectionKind.of(rejection)` consistently with `traceOutput`") is shipped by **R585**. Re-verified at the symbol this window: `AUTHOR_ERROR` `grep` = 0 in `FieldRegistry.java`, `RejectionKind.of(...)` emitted on the `Unresolved` arms (`:121`, `:142`). Nothing remains to do; this window did not touch it. | **Discard**, recording R585 as the delivery vehicle. The symbol check is done and clean; retire to lineage. |

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
| **R213** input-field-rejection-attribution | Backlog | **Escalated, still unactioned (now four windows overdue).** R585 inverted the load-bearing premise (the record now carries a `SourceLocation` the item still says it lacks), and R589's occurrence-path derivation (Done) delivers the attribution split the item asks for. The item's own "re-check after R589 slice 5" note is long due and nobody has run it. | **Re-derive the residue against shipped R585 + R589; most likely close as subsumed.** If a thin residue survives, re-spec onto the shipped record and drop the "grows/has no `SourceLocation`" claims; otherwise discard, recording R585 + R589 as the delivery vehicles. |
| **R66** rejection-string-carrier-widening | Backlog | Carried. Phase **A2** ("widen `Unresolved.reason: String` -> `rejection: Rejection`") was verbatim what **R585** shipped. Phases A1 (`ParsedPath.errorMessage`), A3 (`UnboundArg.reason`), B1 (`EnumValidation.Mismatch`), B2 (`TypeBuilder` aggregations) survive. | **Re-spec:** strike A2 as delivered by R585; re-baseline the four surviving phases onto the shipped record; fix the `:25-30` anchors. |
| **R34** nodeid-migration-quickfix | Backlog | Carried, self-contradictory. The "shim facts" driver names three WARN sites **R473 deleted** and **R27 was discarded**; the discard commit half-reconciled the item, leaving the mechanism sections still naming the deleted sites as the live gap. | **Re-spec:** the "shim facts" driver is void. The migration goal survives, but its source must be re-derived onto R473's landed grammar rejections or the R589 claim relation. Retitle off "shim facts". |
| **R122** compound-entity-mutations | Backlog | Carried. "Design space narrows under R222" leans on R222's discarded recursive `InputUsage` model; R222 left the board 2026-08-06. `TableTargetField` (added by this item) is live. | **Re-spec the "narrows under R222" section**: drop the discarded `InputUsage` carrier; re-express the nested-input model against the captured `intent_`/`applied_` relations. Keep the compound-mutation goal, `@reference(path:)` flattening, and `TableTargetField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Carried. Central target `CompileDependencyGraphBuilder.addFieldEdges` deleted (`grep` = 0); the body's own dissolution condition (`:163`) has occurred. Also cites absent `FkTargetConditionEmitter.emitTerm` (`:45`) and phantom `MutationConditions` (`:57`) as live. | **Re-derive against the plan-projected recompile graph.** Confirm whether the nested-fetcher per-field edge is now modeled under `EmitPlan`; if closed, **discard**, else **re-spec**. In the same pass repoint `FkTargetConditionEmitter.emitTerm` -> `FkTargetConditionFilter` via `ConditionCommands` and drop `MutationConditions`. |
| **R545** model-free-of-emit-vocabulary | Backlog | Carried. `RowsMethodBody` / `RowsMethodSkeleton` deleted, so the diagnosis and second deliverable are gone. First deliverable survives: `ClassName` / `TypeName` are still model-pervasive. | **Re-spec.** Drop the `RowsMethodBody` diagnosis and second deliverable; keep and re-baseline the first (`JavaTypeRef` replacing `TypeName` / `ClassName`) against the post-R549 model. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Carried. Both named edit-target generators deleted: `QueryConditionsGenerator` (R552), `TypeClassGenerator` (R549). Successors `render/ConditionGlueRenderer` / `render/ProjectionUnitRenderer` live. Also names absent `InlineTableFieldEmitter.java:144` / `InlineLookupTableFieldEmitter.java:218` as live host files. | **Re-derive against the new `render/` layer.** Determine whether the renderers still exhibit the duplicated helper-emission problem; drop every dead `QueryConditionsGenerator.java:NNN` cite and the two absent `Inline*Emitter.java:NNN` file cites. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried. R431 (Done) deleted the target surface: the body cites `SourceKey.Reader.SourceRowsCall` as "the live surface", but `SourceKey` is a plain record with no `Reader`. Successors `KeyLift` / `LifterRef` / sealed `Wrap` live; "R431 ... plans to decompose" reads present tense but R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model; drop the `SourceKey.Reader.SourceRowsCall` re-anchor note and the tense error. |

## C. Outdated: update references only (work valid, refs stale) (26)

Substance intact; names and line numbers drifted. Twenty-five carry forward from the prior audit
(not one edited this window, every long-standing driving symbol re-verified still `grep` = 0), and
**one is new this window: R135** (§C.11, R57's `FilterBinding` reshape). §C.9 records the
absent-`*Emitter` driver, which hits six already-listed rows and adds no distinct item. **The R649
family (§C.10) is unchanged this window** (its retirements re-grep 0), so R47 and R555 hold their
§C.10 rows.

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
| **R323** nestingfield-multiparent-batchkey-leaves | Backlog | `:18`/`:30` still list `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField` as the planned enum arm. | **Re-anchor** the four names to `BatchedTableField` (lookup twins: **+ lookup member**). The core BatchKey-scope goal is untouched and stays open. |

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
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh still incomplete; not touched at all this window.** Still at the symbol: 5× `TypeClassGenerator`, 5× `collectRequiredProjection`, 5× `methodgraph`, `LookupValuesJoinEmitter`, 2× `ParentProjectionContainmentCheck`, 1× `TypeConditionsGenerator`, 4× `InlineTableFieldEmitter`, plus the §C.0/§C.5 carriers and the §C.9 absent-emitter names (21 stale symbol cites). The body is a pure implementation plan whose class names are wrong, and it is **Ready**, so the stale prose bites the next implementer to pick it up. | **Finish the refresh in one pass** (this row + §C.0 + §C.5 + §C.6 + §C.1 + §C.9): repoint `TypeClassGenerator` / `collectRequiredProjection` → `ProjectionUnitRenderer` / `ProjectionCommands`; drop the `TypeConditionsGenerator`, `ParentProjectionContainmentCheck`, `methodgraph`, `operation()` cites; re-anchor `LookupValuesJoinEmitter` to the render lookup family and the `Operation` seal cites to `OperationMember`; replace the `Inline*Emitter` projection-arm names with the render projection layer and `FkTargetConditionEmitter` with `FkTargetConditionFilter` via `ConditionCommands`. Rows citing `SplitRowsMethodEmitter` stay (live). |
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
| **R555** deprecate-externalfield-fold-into-service | Ready | Four present-tense cites of `reflectServiceMethod` (`:41`, `:52`, `:103`, `:106`). Every other symbol the item cites (`reflectExternalField`, `validateRootListTableBoundReturnPair`, `validateChildServiceReturnType`, `pickMethod`) survives. | **Re-anchor.** Repoint `reflectServiceMethod` → `decodeServiceMethod` / `ServiceSignature`. R649's "one entry that picks the method once and reads the raw return type" is exactly the seam this item's Design section wants, so the refresh strengthens it. A **Ready** item; refresh before pickup. |
| **R47** service-short-classname-resolution | Backlog | `:15`/`:29`/`:51` name `ServiceCatalog.reflectServiceMethod` (the `Class.forName(className)` site) and "the three/four reflect* sites" as the live edit targets. The `Class.forName` load now lives in `decodeServiceMethod`; `reflectTableMethod`/`reflectExternalField` survive but `reflectServiceMethod` is gone. | **Re-anchor** the class-load site to `decodeServiceMethod` and re-enumerate the reflect* sites. Goal (short class-name resolution) intact. Sequence with R72 as its body already notes. |

### C.11 `FilterBinding` reshape drift (new this window; R57)

R57 replaced the `liftedSourceColumns` slot on the two reference carriers
(`InputField.ColumnBackedReferenceField`, `ArgumentRef.ScalarArg.ColumnBackedArg`) with the sealed
`FilterBinding` (`Local(List<ColumnRef> ownTableColumns)` / payload-free `Remote`). The identically
named component on the resolver's `JoinPath` and `Resolved.FkTarget.DirectFk` **survives**, so only
the downstream carrier accessor drifted.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R135** multi-hop-nodeid-fk-permutation-test | Backlog | `:17` pins "the composite `InputField.ColumnBackedReferenceField.liftedSourceColumns()` ends in `[k1, k2]` order"; `:23` says "the existing `BodyParam.{RowEq,RowIn}` emission consumes `liftedSourceColumns` positionally". The carrier accessor is gone; the tuple is now `FilterBinding.Local(ownTableColumns)`. The `:13`/`:21` resolver-side cites (`NodeIdLeafResolver.resolve`, `Resolved.FkTarget.DirectFk` picked not `TranslatedFk`, `liftedSourceColumns` in NodeType-keyColumns order) are **current**. | **Re-anchor** the `:17` and `:23` carrier cites onto `FilterBinding.Local` (the emission now reads the `Local` tuple). The test-plan goal (multi-hop permuted terminal hop resolves `DirectFk`, not `TranslatedFk`) is untouched and stays open; R57's translation emission changed only what `TranslatedFk` *does*, not the classification the test pins. |

## D. Structural: (0)

Empty of blocking defects. `changelog.md` carries `next-id: R702`, clearing the max allocated id
(R701). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and the eight non-empty
`depends-on:` edges resolve to present files. R690/R699/R700's discards left **no** dangling edge,
and R57's Done removed no edge that survives. The roadmap-tool regenerates `README.md` with **no
drift** at this HEAD. The thirteen items filed this window carry well-formed front-matter and read
born-current.

One **pre-existing, non-blocking** hygiene note, surviving unchanged:

1. Four **legacy** items still lack a `bucket:` key: R242 (`dml-payload-positional-alignment`),
   R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
   R180 (`record-parent-column-read-helper`). Build tolerates the omission; fold a `bucket:` in
   whenever each is next edited.

## Cross-cutting observations

1. **A Done symbol-retiring window staled one item, and the reason is scope, not process.** R57
   ran a clean retirement sweep and repointed its live citers (it filed R691/R692 against the new
   `FilterBinding` model), but a Backlog *test-plan* item (R135) two hops downstream named the
   retired carrier accessor and was outside R57's sweep radius. The audit-relevant lesson: a
   retirement sweep catches the code and the items the shipping session touches, but a distant
   Backlog item that only *plans* to assert on the retired slot still needs the board sweep to catch
   it. R135's drift is a single §C repoint, not a re-spec.

2. **R193 and R213 are the two overdue subsumption candidates, and neither moved.** R193 asked for
   the sealed parameter classifier that R649's `reduceClaims`/`ParamRole` shipped; R213 holds the
   same shape against R585/R589. Running both re-checks, and most likely closing both as subsumed,
   is the cheapest board-cleaning available; deferring only lets the stale prose keep misleading.

3. **The fact-model / service clusters are the live edit zone, and their symbol churn is correctly
   not flagged.** R642 (In Progress), R666, R680, R682, R684, R685, R693, R697, R686, R687 (Spec)
   reshape `TypeBackingShape`, `ClassMemberSlots`, `CompletionData`, `CatalogCorpus`, `CorpusTable`
   as they draft. Because these are actively driven and cite live symbols, their internal churn is
   not board staleness.

4. **Prefix greps still misjudge the retired service and mutation symbols.** A bare `grep` for
   `PkLessParent` returns the surviving `SourcesOnPkLessParent` error arm; one for `resolveInput`
   returns the unrelated `RecordBindingResolver.resolveInput`; one for `Operation` returns comments
   and a string-literal fact key; one for `liftedSourceColumns` returns the surviving resolver-side
   component. Every flag here keys on the fully-qualified member, not the prefix, and each stands.

5. **The Ready set is where stale prose bites soonest, and R650 leaving it did not change the
   verdict.** The Ready set is now R333, R427, R467, R555, R684 (R650 -> In Review, R684 -> Ready
   this window). R333 (§C.5/§C.7), R427 (§C.0) and R555 (§C.10) carry stale cites; R467
   (`upgrade-graphql-java-26`) and R684 (born-current) are clean. **Refreshing R333, R427, and R555
   before pickup remains the highest-value hygiene action on the board.**

---

_Review date: 2026-08-18._
