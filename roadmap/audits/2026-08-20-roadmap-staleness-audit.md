# Roadmap staleness audit: 2026-08-20

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `7c07683`, committed 2026-08-19 22:29, audited 2026-08-20). The goal is
to find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed or been discarded, or
specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-08-19` staleness audit, which has been deleted;
only the latest **staleness** audit is retained. The other fourteen files in this
directory are **not** staleness audits and are left in place (deleting them would
strand lineage that shipped items and active items cite by path, and they are
provenance I did not author): `2026-06-16-source-operation-target-reframe.md`,
`2026-06-30-release-planning.md`, `2026-07-04-r222-r333-conformance-analysis.md`,
`2026-07-26-fcis-command-layer-distance.md`, `2026-08-05-fact-base-h2-spike.md`,
`2026-08-05-h2-functions-jooq-spike.md`, `2026-08-06-demand-exemption-census.md`,
`2026-08-06-directive-consumer-census.md`, `2026-08-06-fact-base-impact-sweep.md`
(the architecture-drift companion to this audit), `2026-08-06-graphql-java-diff-spike.md`,
`2026-08-06-r222-lineage.md`, `2026-08-06-structural-classifier-census.md`,
`2026-08-19-github-issue-roadmap-linkage.md`, and `classification-test-dsl-inventory.md`.

## Headline: one big architectural item (R638) reached Done and is the window's whole staleness driver; three other Done items and one discard staled nothing; the prior audit's R709 flag was a phantom, and the prior audit was authored on a divergent history

The prior window shipped two large architectural items; **this window shipped four
Done items (R638, R680, R710, R732) and one discard (R347), but only R638 staled
anything.** The reason is the same lesson the prior audit drew: a Done item stales
the board by *deleting a construct active items name*, and only R638 did that.

- **R638 (`lsp-reads-the-fact-store`, Done)** made the language server a fact-store
  client and deleted a raft of the pre-store projection surface: `LspSchemaSnapshot`,
  `FieldClassification`, the catalog-package `TypeClassification`, `DirectiveShape`,
  `InputValueShape`, `CatalogBuilder.buildSnapshot`, `CatalogBuilder.projectTypeDefinitionLocations`,
  and the whole `@ProjectionFor` coverage apparatus (each re-verified `grep`=0 in main
  real code). **It broke one item's premise and staled six references.** R638's own Done
  changelog names three items its deletions falsified: `lsp-structural-consolidation.md`
  (**already discarded** as R347 this window), `mcp-snapshot-axis-key-naming.md` (**R594**,
  which re-verifies as a reference re-anchor, not a premise break, §C.12), and
  `lsp-compile-diagnostics-publish.md` (**R430**, a genuine premise break, new to §B).
  Two items are newly reference-stale (**R714**, **R236**, new §C.14) and three carried
  §C rows deepen (**R684** §C.12, **R555** §C.10, **R557** §C.1). **The prior audit recorded
  R638 as In Progress**: it actually reached Done in `2173720`, the prior audit's own commit,
  so the prior audit shipped already stale about it.
- **R710 (`jooq-node-metadata-as-stated-facts`, Done)** captured `sql_node_metadata`,
  `sql_node_key_column`, `intent_node_metadata_defect`, and `JooqCatalog.NodeMetadataFacts`,
  but **staled nothing**: unlike R704, it deliberately deferred its reader (that reader is
  R668, In Review, born-current) and did **not** retire the live reflection reader
  `JooqCatalog.nodeIdMetadata`, so items naming it (R615, R273, R34, R588) stay accurate,
  and the nodeid cluster filed against it (R724, R730, R731, R735) is born-current. **The
  prior audit recorded R710 as a clean Ready item**; it reached Done in `2173720` too.
- **R680 (`fact-store-test-harness-consolidation`, Done)** retired
  `no.sikt.graphitron.rewrite.capture.CapturedStore`, `PENDING_MODULE_FLOOR`, `PENDING_SEEDING`;
  **no live item cites any of them** (the new `CapturedStore` test harness is a different class),
  and the roadmap-tool residue it found is tracked as **R737**.
- **R732 (`build-wall-clock-guardrail`, Done)** was a wall-clock recovery (a quadratic sweep,
  a forked javadoc lifecycle, class-level test parallelism) plus moving the H2 materialized-view
  ruling into `fact-model.adoc`; it retired no construct and **staled nothing** (its residue is
  R733, R736).
- **R347 (`lsp-structural-consolidation`, discarded)** left the board this window, superseded by
  R638's deletions, with its residue filed as **R739**. It exits the flag set.

Net: **1 §A / 12 §B / 29 §C / 0 §D**, flag total **42**, up two from the prior audit's 40.
Entering: **R430** (§B, R638 premise break), **R714** and **R236** (§C.14, R638 reference drift).
Leaving: **R709** (dropped, see below). **R594 stays §C** (the prior audit's §C.12 row; its named
surface `McpWire.writeSnapshotAxes` / `SchemaView` / `GraphitronMcpServer.statusResult` /
`snapshotAvailability` all survive, so only the `LspSchemaSnapshot` switch-source and the `edges`
tool count need re-anchoring). **None of the surviving carried flags was edited this window**, so
every carried flag holds at its prior line anchors, and all long-standing retired symbols re-verify
`grep`=0 at this HEAD.

**R709 was a phantom.** The prior audit placed R709 (`catalog-routine-facts`) in §B.11, but
`roadmap/catalog-routine-facts.md` **does not exist on this branch**, no roadmap file carries
`id: R709`, and the id appears in no reachable commit: it lives only inside the prior audit's own
text. The prior audit's stated baseline `f6e9c34` is **not an ancestor of** this HEAD, so it was
authored on a divergent history whose board (243 files, next-id R721) never fully existed here
(this branch is 251 files, next-id R742). R709's premise-target is in any case delivered
(`sql_routine` / `sql_routine_parameter` live, `JooqCatalog.RoutineCallFacts` captures them), so
whatever residue it named is either shipped or absorbed. It is dropped.

## Changes since the 2026-08-19 audit

The prior audit stated a baseline of next-id **R721** (243 item files). Current HEAD is `7c07683`
(2026-08-19 22:29), next-id **R742**, **251** item files (measured, excluding `README.md` and
`changelog.md`). Because the prior audit's baseline commit is not on this branch's history, the
delta below is measured from `2173720` (the commit that landed the prior audit onto this branch)
to HEAD.

**Items that reached Done, and what each did to the symbol set:**

- **R638 (`lsp-reads-the-fact-store`, In Progress -> Done):** the LSP became a fact-store client;
  deleted the projection surface listed in the headline. Recorded as In Progress by the prior audit;
  landed Done in the audit's own commit.
- **R710 (`jooq-node-metadata-as-stated-facts`, Ready -> Done):** node-identity constants captured as
  stated facts (`sql_node_metadata`, `sql_node_key_column`, `intent_node_metadata_defect`,
  `NodeMetadataFacts`); reader deferred to R668, reflection reader `nodeIdMetadata` retained.
- **R680 (`fact-store-test-harness-consolidation`, In Progress -> Done):** four modules' test
  harnesses sorted into five levels across two homes; retired `CapturedStore` (old class),
  `PENDING_MODULE_FLOOR`, `PENDING_SEEDING`; filed R737 for the roadmap-tool store-site residue.
- **R732 (`build-wall-clock-guardrail`, In Review -> Done):** build wall-clock recovered from three
  measured slices; moved the H2 materialized-view ruling into `fact-model.adoc`; residue R733, R736.

**Discards:** **R347** (`lsp-structural-consolidation`, superseded by R638's deletions; residue R739).
No dangling `depends-on:` edge left behind.

**Items filed this window (R721 -> R741, born-current except as noted):** R721
(`service-transaction-demarcation-undocumented`), R722 (`service-opt-in-transaction-wrap`),
R723 (`reference-path-fanout-verdict`), R724 (`stated-key-column-match-states-its-arity`, Ready),
R725 (`carrier-recognizer-conflates-three-scan-verdicts`), R726 (`nodeid-bare-inference-per-participant-divergence`),
R727 (`run-record-families-for-commands-and-emitted-units`), R728 (`nodeid-effective-at-every-coordinate`, Spec),
R729 (`findcolumn-picks-silently-on-a-colliding-table`), R730 (`capture-api-residue-after-nodehood-move`, Ready),
R731 (`resolved-key-column-forwards-a-spelling`), R733 (`build-wall-clock-guardrail`, R732 residue),
R735 (`projected-key-column-across-a-node-id-list`), R736 (`trace-writer-disabled-for-rest-of-fork`, Spec),
R737 (`roadmap-tool-store-site-and-guard-scope`, R680 residue), R739 (`lsp-result-range-and-rewrite-polish`, R347 residue),
R740 (`retire-oracle-diff-shadow-tests`), R741 (`parallelism-figure-in-junit-properties`). **R732 was filed and
reached Done in-window.** **R734 and R738 were each allocated and their files removed inline during the
argMapping/`@nodeId` work** (delivered as a fix or folded away), leaving expected numbering gaps.

**Transitions not already listed:** R668 In Progress -> In Review; R724, R730 to Ready; R666, R705, R728,
R736 in/through Spec. Actively drafted and correctly **not** flagged: the nodeid / routine / lsp / fact-model
clusters (R666, R668, R724, R730, R728, R705, R682, R685, R687, R697, R706, and the R721+ filings), all
born-current against the live relations.

**Board accounting.** **251 item files** today (measured), up from 243. Status distribution:
**213 Backlog, 28 Spec, 8 Ready, 0 In Progress, 2 In Review, 0 Done**. Tombstone-free (`grep` for
`status: Done` in `roadmap/*.md` = 0). No duplicate `id:`; `changelog.md` carries `next-id: R742`,
clearing the max allocated id (R741). A `depends-on:` sweep resolves all **seven** non-empty edges
(down from twelve; discards and Done transitions cleared five) to present files. The roadmap-tool
regenerates `README.md` with **no drift** at this HEAD. The only structural nits are the same four
**legacy** items still missing a `bucket:` key (§D), all pre-dating this window.

## Scope and method

All **251** `R<n>` item files were reviewed. Every driving symbol below was re-checked against a fresh
`grep` of the main sources (`graphitron`, `graphitron-mcp`, `graphitron-lsp`, `graphitron-model`,
`graphitron-maven-plugin`, `graphitron-fixtures-codegen`), not carried on the prior audit's word, and
every flagged item's file was confirmed present on this branch.

**R638 retirements, verified `grep`=0 in main real code at this HEAD** (`{@link}`/`{@code}`/comment/
test-only hits excluded): `LspSchemaSnapshot`, `FieldClassification`, the catalog-package
`TypeClassification` (**note:** an unrelated `TypeClassification` used by the generator core in
`TypeBuilder` / `GraphitronSchemaBuilder` / `BuildContext` **survives** and is not stale),
`DirectiveShape`, `InputValueShape`, `CatalogBuilder.buildSnapshot`, `projectTypeDefinitionLocations`,
`@ProjectionFor`, `projectFieldClassification`. **Still live, not stale:** the `CatalogBuilder` class
itself, `CompletionData`, `TypeBackingShape`, and the model leaf `ChildField.ComputedField`.

**R430 premise-break, verified at this HEAD:** `CompileDiagnostic`, `Workspace.compileDiagnostics()`,
and `setCompileDiagnostics` are all `grep`=0 in `graphitron-lsp` main; R638 moved LSP diagnostics onto
the capture cadence and removed the per-round `Workspace` swap surface the item's current-state rests on.

**R710 / R680 / R732 retirements confirmed to stale no live item:** `sql_node_metadata` /
`NodeMetadataFacts` live and referenced only born-current; `nodeIdMetadata` (reflection reader) still
live; `CapturedStore` (old) / `PENDING_MODULE_FLOOR` / `PENDING_SEEDING` `grep`=0 and cited by no live
item.

**Long-standing retirements, re-verified still retired at this HEAD (`grep`=0 in main real code):**
`remoteIfReferenceJoin`, `translatedFkRejection`, the R649 family (`reflectServiceMethod`,
`looksLikeSourcesShape`, `validateRootInvariants`), `CompileDependencyGraphBuilder`, `RowsMethodBody` /
`RowsMethodSkeleton`, `QueryConditionsGenerator` / `TypeClassGenerator` / `TypeConditionsGenerator`,
`buildNonTableInputType`, `LookupValuesJoinEmitter`, the `Inline*Emitter` family, `FkTargetConditionEmitter`,
`ParentProjectionContainmentCheck`, the `Split*` / `Record*` variant names, `crossTableJoinChain` /
`crossTableAliasDeclarations` / `TABLE_INTERFACE_ROOT_CONNECTION_DEFERRED` (R650), `CatalogFacts` (R642),
`ConceptPages.readTitles` (R488), `TableInputType` / `resolveInput`, the `Operation` seal arms, and the R57
`FilterBinding` reshape. `AUTHOR_ERROR` `grep`=0 in `FieldRegistry.java`.

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried (8 windows); still Backlog, still fully delivered.** The entire deliverable ("remove the `RejectionKind.AUTHOR_ERROR` default arm and emit `RejectionKind.of(rejection)` consistently with `traceOutput`") is shipped by **R585**. Re-verified at the symbol this window: `AUTHOR_ERROR` `grep`=0 in `FieldRegistry.java`. Nothing remains to do; this window did not touch it. | **Discard**, recording R585 as the delivery vehicle. The symbol check is done and clean; retire to lineage. |

R520 (`table-on-input-removal-housekeeping`) stays a coherent deliberately-deferred docs tail, not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (12)

Eleven carry forward from the prior audit unchanged (none edited this window; each re-verified at the
symbol with the premise-target still `grep`=0 and a live successor); **one is new this window: R430**
(R638's LSP-diagnostics move, §B.12). **R709 is dropped** (phantom, no file on this branch, see the
headline). **R193 and R213 remain the two overdue subsumption candidates** (R649 / R585 / R589 shipped
what they scoped); running both re-checks, most likely closing both as subsumed, is the cheapest
board-cleaning available.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R193** service-param-classification-sealed-hierarchy | Backlog | **R649 shipped the deliverable.** The item asks to refactor `reflectServiceMethod`'s `sourcesShape.isEmpty()` predicate chain into a sealed classifier plus one switch that owns the rejection text. Re-verified: `reflectServiceMethod` and `looksLikeSourcesShape` are `grep`=0; `reduceClaims` mints one sealed `ParamRole` per parameter. | **Re-derive the residue against shipped R649; most likely close as subsumed.** If a thin diagnostic-arm residue survives `ParamRole`, re-spec it onto the shipped classifier and drop the `reflectServiceMethod:258-329` / `looksLikeSourcesShape` anchors; otherwise discard, recording R649 as the delivery vehicle. |
| **R72** slim-servicecatalog-to-lookup | Backlog | **Premise materially changed by R649.** The current-state diagnosis is a line-by-line census of pre-R649 `ServiceCatalog`. R649 dissolved `reflectServiceMethod` (`grep`=0) into `decodeServiceMethod` / `reduceClaims` / `bindServiceMethod`; `reflectTableMethod` and `reflectExternalField` survive. | **Re-spec.** Re-derive the diagnosis against the post-R649 split: measure how much slimming R649 already accomplished, drop every stale `ServiceCatalog.java:NNN` line cite, and re-baseline the remaining goal on the new shape. |
| **R213** input-field-rejection-attribution | Backlog | **Escalated, still unactioned (now six windows overdue).** Re-verified: `InputFieldResolution.Unresolved` now carries a `SourceLocation location` the item still says it lacks, and R589's occurrence-path derivation (Done) delivers the attribution split the item asks for. The item's own "re-check after R589 slice 5" note is long due. | **Re-derive the residue against shipped R585 + R589; most likely close as subsumed.** If a thin residue survives, re-spec onto the shipped record and drop the "grows/has no `SourceLocation`" claims; otherwise discard, recording R585 + R589 as the delivery vehicles. |
| **R66** rejection-string-carrier-widening | Backlog | Carried. Phase **A2** ("widen `Unresolved.reason: String` -> `rejection: Rejection`") was verbatim what **R585** shipped (re-verified: `Unresolved` carries `Rejection rejection`, not `reason:String`). Phases A1, A3, B1, B2 survive. | **Re-spec:** strike A2 as delivered by R585; re-baseline the four surviving phases onto the shipped record; fix the `:25-30` anchors. |
| **R34** nodeid-migration-quickfix | Backlog | Carried, self-contradictory. The "shim facts" driver names three WARN sites **R473 deleted** and **R27 was discarded** (both ids absent from the board); the discard commit half-reconciled the item, leaving the mechanism sections still naming the deleted sites as the live gap. (Its `JooqCatalog.NodeIdMetadata` / `__NODE_TYPE_ID` cite is the live classifier arm, untouched by R710.) | **Re-spec:** the "shim facts" driver is void. The migration goal survives, but its source must be re-derived onto R473's landed grammar rejections or the R589 claim relation. Retitle off "shim facts". |
| **R122** compound-entity-mutations | Backlog | Carried. "Design space narrows under R222" leans on R222's discarded recursive `InputUsage` model; R222 left the board 2026-08-06. `ChildField.TableTargetField` (added by this item) is live. | **Re-spec the "narrows under R222" section**: drop the discarded `InputUsage` carrier; re-express the nested-input model against the captured `intent_` / `applied_` relations. Keep the compound-mutation goal, `@reference(path:)` flattening, and `TableTargetField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Carried. Central target `CompileDependencyGraphBuilder.addFieldEdges` deleted (`grep`=0); the body's own dissolution condition (`:163`) has occurred. Also cites absent `FkTargetConditionEmitter.emitTerm` (`:45`) and phantom `MutationConditions` (`:57`) as live. | **Re-derive against the plan-projected recompile graph.** Confirm whether the nested-fetcher per-field edge is now modeled under `EmitPlan`; if closed, **discard**, else **re-spec**. In the same pass repoint `FkTargetConditionEmitter.emitTerm` -> `FkTargetConditionFilter` via `ConditionCommands` and drop `MutationConditions`. |
| **R545** model-free-of-emit-vocabulary | Backlog | Carried. `RowsMethodBody` / `RowsMethodSkeleton` deleted, so the diagnosis and second deliverable are gone; first deliverable survives (`ClassName` / `TypeName` still model-pervasive, 50 uses / 28 model files). **New this window:** the `:18` counter-argument names `rewrite/catalog/`'s "`FieldClassification` / `TypeClassification` / `CompletionData` projections", of which R638 deleted the first two. | **Re-spec.** Drop the `RowsMethodBody` diagnosis and second deliverable; keep and re-baseline the first (`JavaTypeRef` replacing `TypeName` / `ClassName`); update the `:18` counter-argument to the current `rewrite/catalog/` contents (`CatalogBuilder` / `ClasspathScanner` / `CompletionData` / `TypeBackingShape`). |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Carried. Both named edit-target generators deleted: `QueryConditionsGenerator` (R552), `TypeClassGenerator` (R549). Successors `render/ConditionGlueRenderer` / `render/ProjectionUnitRenderer` live. Also names absent `InlineTableFieldEmitter.java:144` / `InlineLookupTableFieldEmitter.java:218` as live host files. | **Re-derive against the new `render/` layer.** Determine whether the renderers still exhibit the duplicated helper-emission problem; drop every dead `QueryConditionsGenerator.java:NNN` cite and the two absent `Inline*Emitter.java:NNN` file cites. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried. R431 (Done) deleted the target surface: the body cites `SourceKey.Reader.SourceRowsCall` as "the live surface", but `SourceKey` is a plain `record` (`SourceKey.java:33`) with no `Reader`. Successors `KeyLift` / `LifterRef` / sealed `Wrap` live; "R431 ... plans to decompose" reads present tense but R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model; drop the `SourceKey.Reader.SourceRowsCall` re-anchor note and the tense error. |
| **R76** participant-fieldsjoin-helpers | Backlog | Carried (entered §B last window). R76's premise is that discriminated-interface fetchers emit conditional **cross-table** joins via `step = step.leftJoin(alias).on(...)` in `DiscriminatedTableFragments`, and it proposes a `$fieldsJoin` helper composing them. R650 converted cross-table participant fields to capped correlated subselects; re-verified: the surviving `step.leftJoin(...)` (`:314`, `:340`) is the joined-detail join only. | **Re-spec.** The step-mutation idiom the item targets now applies only to the joined-detail `LEFT JOIN`; the cross-table-join framing and the per-participant cross-table `$fieldsJoin` design are obsolete. Re-derive whether any join-composition duplication survives on the joined-detail side, or discard if R650's subselect conversion removed the motivating duplication. |
| **R430** lsp-compile-diagnostics-publish | Backlog | **New this window; premise broken by R638 (Done).** R638's own Done changelog names this item as one it "falsified". The item's current-state rests on "the diagnostics already land on `Workspace.compileDiagnostics()` after every round" and "on each `setCompileDiagnostics` swap, publish"; all three of `CompileDiagnostic`, `Workspace.compileDiagnostics()`, `setCompileDiagnostics` are now `grep`=0. R638 moved LSP diagnostics onto the capture cadence and retired the per-round `Workspace` swap surface. `publishDiagnostics` (the feature's output side) survives. | **Re-derive current state against the fact-store LSP.** First establish whether the `graphitron:dev` incremental-compile (javac) diagnostic stream is still collected anywhere post-R638; if it is, re-anchor the publish trigger off the retired `setCompileDiagnostics` swap onto the current cadence and keep the publish-against-generated-URI goal; if the compile stream itself was retired, discard. |

## C. Outdated: update references only (work valid, refs stale) (29)

Substance intact; names and line numbers drifted. Twenty-seven carry forward from the prior audit
(not one edited this window, every long-standing driving symbol re-verified still `grep`=0), and
**two are new this window: R714 and R236** (§C.14, R638's LSP-projection deletions). Three carried
rows **deepen** with an added R638 symbol: **R684** (§C.12), **R555** (§C.10), **R557** (§C.1).
**R594 stays** here (its surface survives; only references drifted), not escalated to §B.

### C.0 `Operation` seal fully retired (carried; R563 slice 7)

`Operation` and every `Operation.<Arm>` reference are `grep`=0. Successor: `OperationMember`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R427** relevance-ranked-search | Ready | `:335` "`operation()` stays `Fetch`" cites the retired accessor; `:339`/`:1162` cite `Operation.Facet` as a live precedent type. | **Re-anchor.** Restate against the member-derived summary fold; repoint `Operation.Facet` onto `OperationMember.Facet`. A **Ready** item; refresh before pickup. |
| **R382** multitable-interface-query-orderby-lowering | Backlog | `:19` cites `operation()` as the live mechanism hardcoding `new OrderBySpec.None()`. | **Re-anchor** to where the hardcoded `OrderBySpec.None` now lives (`OperationMember.OrderBy` sourcing, or `MultiTablePolymorphicEmitter`); verify the ordering gap reproduces. |
| **R562** synthesised-connection-fields-as-coordinates | Backlog | `:17-18` name `Operation.Count` / `Operation.Facet` arms as the observable gap; all three names retired. | **Re-anchor** to `OperationMember.Count` / `Facet`. Model question intact. |

### C.1 Lookup-triplet dissolution drift (carried; R563 slice 6a)

A lookup leaf re-anchors to `BatchedTableField` (or `TableField` / `QueryTableField`) **plus a lookup member**.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R533** localcontext-guard-predicate-single-source | Backlog | `:15` names `BatchedLookupTableField` as the open edge; `:19` self-corrects but the lead is stale. | **Re-anchor** the `:15` lead to the post-dissolution sibling. |
| **R557** split-query-marker-sweep | Backlog | `:17`'s "consumed (the batched leaves)" lists `BatchedLookupTableField` alongside `BatchedTableField` / `BatchedPivotField`. **New this window:** `:17` also anchors the sweep's verdict on the "`CatalogBuilder.projectFieldClassification` compile-checked-projection seam", which R638 deleted (`grep`=0). | **Re-anchor**: drop `BatchedLookupTableField` (now `BatchedTableField` + lookup member); repoint the "compile-checked-projection seam" to the surviving leaf-classification mechanism (`GraphitronSchemaBuilder` / fact store). Batched leaves and `TenantBindingIndex.sweepUnreachedFanOutMarkers` cited are live. |

### C.2 `@table`-on-input rejection -> deprecation drift (carried; R566)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R565** unclassified-input-arg-cascade-diagnostic | Backlog | Title (`:3`, `:13`) still leads with the retired `@table`-on-input **rejection** as the driver; the body already frames it against the current state. | **Re-anchor (not full re-spec).** Retitle/re-lead onto a still-current rejection; demote `@table`-on-input to historical framing. |

### C.3 `planSlug` / `SourceKey.Reader` removal drift (carried)

R484 removed `Rejection.Deferred.planSlug`; R431 removed the `SourceKey.Reader` interface.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R454** routine-write-result-shapes | Backlog | `:18` names deferred shapes as "typed `Deferred`s pointing at this item's planSlug"; gone. | **Re-anchor:** deferred shapes surface via `StubKey.VariantClass`. |
| **R447** routine-chain-fetch-form-breadth | Backlog | `:18`/`:33` "`planSlug` points here"; `:24`/`:26` name `SplitLookupTableField` / `RecordTableField` as live; `:23` names the absent `InlineTableFieldEmitter` as live beside the real `SplitRowsMethodEmitter`. Its parked note on "connection pagination over a chain containing a routine node" softly overlaps R704's shipped routine-chain pagination. | **Re-anchor:** drop the `planSlug` phrasing; repoint to `BatchedTableField` (lookup twin: **+ lookup member**); drop the `InlineTableFieldEmitter` cite. **At Spec, check** whether R704 subsumed the parked pagination note. |
| **R180** record-parent-column-read-helper | Spec | `:35` says "R431 ... now In Progress" (R431 is **Done**) and names `SourceKey.Reader.AccessorCall` as live. | **Re-anchor** the `AccessorCall` carrier onto the decomposed model; fix the R431 tense. |
| **R505** tenant-index-parent-row-routing | Backlog | `:21` names "a column read off the parent row (the `SourceKey.Reader` family)". Live via `KeyLift.FkColumns`. | **Re-anchor** the one parenthetical. |

### C.4 Leaf-merge drift: `Split*` / `Record*` -> `Batched*` (carried)

`SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** to `BatchedTableField`. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38`'s R305 lineage note "collapsed it into `RecordTableField`"; `:107`/`:148`/`:150`/`:284` cite `LookupValuesJoinEmitter`. | **Re-anchor** to `BatchedTableField`; repoint `LookupValuesJoinEmitter` to the render values-join family. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34` name `SplitTableField` / `RecordTableField`; `:24` cites the absent `InlineTableFieldEmitter`. `SplitRowsMethodEmitter` fine. | **Re-anchor** the two variant names to `BatchedTableField`; drop the `InlineTableFieldEmitter` cite. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`'s planned case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |

### C.5 `ColumnBackedField` dissolution drift (carried; R508)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | Retired carriers named live across `:570`, `:694`, `:706`, `:727`, `:747-748`, `:798-799`, `:1112`, `:1140`, `:1145`, `:2009`. Part of the one R333 refresh (§C.7). | **Re-anchor** the carrier names to `ColumnBackedField`. Part of the one R333 pass. |

### C.6 `TableInputType` / `resolveInput` removal drift, plus the `UnboundField` reshape (carried; R519 + R515; R589)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R234** jooq-embedded-and-udt-input-backings | Backlog | `:15` cites `TypeBuilder.buildNonTableInputType` (`grep`=0) as the live dispatch to extend. | **Re-anchor** to the current `TypeBuilder` input-classification path (`buildInputType`). |
| **R257** updaterows-walker-sdl-substrate | Backlog | `:17` calls `resolveInput` "the legacy resolver" (`MutationInputResolver.resolveInput` gone R515); `:15`/`:19` reach carriers "via `TableInputType.inputFields()`" (gone R519). | **Re-anchor** both: `resolveInput` -> `admitMutationInputFields`, `TableInputType.inputFields()` -> per-consumer input resolution. |
| **R337** input-nesting-projection-classification | Backlog | `:30` reaches an input object's fields "via `TableInputType.inputFields()`" as the LSP-hover mechanism. (The `:29` `FieldClassification` mention is explicitly past-tense/historical and needs no edit.) | **Re-anchor** the one live mechanism cite to per-consumer input resolution. |
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot composition "in `MutationInputResolver.resolveInput`" (method gone R515). `:124`'s test bullet "Override `@condition` on an `UnboundField`" names the pre-R589 carrier. | **Re-anchor** `:76` to `admitMutationInputFields`, and `:124` to `ConditionOwnedField`. |

### C.7 Condition + projection emitter dissolution drift (carried; R552 + R549)

Condition emission is `render/ConditionGlueRenderer`; projection `render/ProjectionUnitRenderer`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh still incomplete; not touched at all this window.** Still at the symbol: 5x `TypeClassGenerator`, 5x `collectRequiredProjection`, 5x `methodgraph`, `LookupValuesJoinEmitter`, 2x `ParentProjectionContainmentCheck`, 1x `TypeConditionsGenerator`, 4x `InlineTableFieldEmitter`, plus the §C.0/§C.5 carriers and the §C.9 absent-emitter names. The body is a pure implementation plan whose class names are wrong, and it is **Ready**, so the stale prose bites the next implementer to pick it up. | **Finish the refresh in one pass** (this row + §C.0 + §C.5 + §C.6 + §C.1 + §C.9): repoint `TypeClassGenerator` / `collectRequiredProjection` -> `ProjectionUnitRenderer` / `ProjectionCommands`; drop the `TypeConditionsGenerator`, `ParentProjectionContainmentCheck`, `methodgraph`, `operation()` cites; re-anchor `LookupValuesJoinEmitter` to the render lookup family and the `Operation` seal cites to `OperationMember`; replace the `Inline*Emitter` projection-arm names with the render projection layer and `FkTargetConditionEmitter` with `FkTargetConditionFilter` via `ConditionCommands`. Rows citing `SplitRowsMethodEmitter` stay (live). |
| **R231** emit-text-mapped-enum-fields-as-enum-type | Backlog | `:39` locates "the field-type-emit fork (likely in `TypeClassGenerator` or ...)". | **Re-anchor** the investigation locus to `ProjectionUnitRenderer` / `plan/ProjectionCommands`. |
| **R35** source-orientation-javadocs | Backlog | `:42` enumerates classes for a javadoc sweep, including `TypeClassGenerator` and `TypeConditionsGenerator`, both deleted. | **Re-anchor** the enumeration: drop the two deleted names. Low priority; scope illustrative. |

### C.8 `InputFieldResolution.Unresolved` reshape drift (empty; R585)

R585 reshaped `Unresolved` to `(fieldName, SourceLocation, Rejection)`. Its consumers R66, R213 (§B) and R209 (§A) hold the residue. **No item remains in this subsection.**

### C.9 Absent projection/condition `*Emitter` names (carried; render-layer refactor)

A family of per-arm projection/condition emitter names several items cite as **live** current-state
classes, all **`grep`=0 across every main tree** at this HEAD. The work they name lives today in
`ProjectionUnitRenderer` / `ProjectionCommands`, `ConditionGlueRenderer` / `ConditionCommands`, and
`FkTargetConditionFilter`. This subsection adds **no distinct flagged item**; every citer is already
listed above.

| Absent name | Cited-as-live in | Live successor |
|---|---|---|
| `InlineColumnReferenceFieldEmitter` | R333 (`:1890`) | render projection layer (`ProjectionUnitRenderer` / `ProjectionCommands`) |
| `InlineTableFieldEmitter` | R333 (`:1752`,`:1891`), R85 (`:20`,`:45`), R447 (`:23`), R288 (`:24`) | render projection layer |
| `InlineLookupTableFieldEmitter` | R333 (`:1891`), R85 (`:21`,`:46`) | render projection layer |
| `FkTargetConditionEmitter` | R333 (`:1893`), R462 (`:45`, `.emitTerm`) | `FkTargetConditionFilter` via `ConditionCommands` |
| `MutationConditions` (phantom) | R462 (`:57`) | none; drop the shim name |

### C.10 `reflectServiceMethod` / `PkLessParent` / `validateRootInvariants` removal drift (carried; R649)

R649's phase split retired `ServiceCatalog.reflectServiceMethod` (its logic is now `decodeServiceMethod`
+ `reduceClaims` + `bindServiceMethod`, decoding to a typed `ServiceSignature`), `ServiceCatalog.PkLessParent`,
and `validateRootInvariants`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R555** deprecate-externalfield-fold-into-service | Ready | Four present-tense cites of `reflectServiceMethod` (`:41`, `:52`, `:103`, `:106`); every other symbol the item cites (`reflectExternalField`, `validateRootListTableBoundReturnPair`, `validateChildServiceReturnType`, `pickMethod`) survives. **New this window:** `:226-227`, `:400`, `:405` describe hover switching on `FieldClassification.Computed` vs `FieldClassification.ServiceBacked` (R638-deleted), and `:352` / `:491` are javadoc-respell tasks on `FieldClassification.Computed`. | **Re-anchor.** Repoint `reflectServiceMethod` -> `decodeServiceMethod` / `ServiceSignature`. Re-anchor Deliverable 4's hover onto the surviving leaf classifier (`ChildField.ComputedField`, read from the fact store) and drop the two now-moot `FieldClassification.Computed` javadoc-respell tasks. Core fold into `@service` intact. A **Ready** item; refresh before pickup. |
| **R47** service-short-classname-resolution | Backlog | `:15`/`:29`/`:51` name `ServiceCatalog.reflectServiceMethod` (the `Class.forName(className)` site) and "the three/four reflect* sites" as the live edit targets. The load now lives in `decodeServiceMethod`; `reflectTableMethod` / `reflectExternalField` survive. | **Re-anchor** the class-load site to `decodeServiceMethod` and re-enumerate the reflect* sites. Goal intact. Sequence with R72. |

### C.11 `FilterBinding` reshape drift (carried; R57)

R57 replaced the `liftedSourceColumns` slot on the two reference carriers with the sealed `FilterBinding`
(`Local(List<ColumnRef> ownTableColumns)` / payload-free `Remote`). The identically named component on the
resolver's `JoinPath` and `Resolved.FkTarget.DirectFk` **survives**.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R135** multi-hop-nodeid-fk-permutation-test | Backlog | `:17` pins "the composite `InputField.ColumnBackedReferenceField.liftedSourceColumns()` ends in `[k1, k2]` order"; `:23` says "the existing `BodyParam.{RowEq,RowIn}` emission consumes `liftedSourceColumns` positionally". The carrier accessor is gone; the tuple is now `FilterBinding.Local(ownTableColumns)`. The `:13`/`:21` resolver-side cites are **current**. | **Re-anchor** the `:17` and `:23` carrier cites onto `FilterBinding.Local`. The test-plan goal is untouched and stays open. |

### C.12 `CatalogFacts` / mcp-tool-surface / `LspSchemaSnapshot` drift (carried + deepened; R642 + R638)

R642 cut graphitron-mcp off the generator (`CatalogFacts` `grep`=0, `edges` tool dropped, the three code
tools collapsed to one). R638 then deleted `LspSchemaSnapshot` (`grep`=0) and `FieldClassification`
(`grep`=0). The two items below name retired symbols as live; both keep their work valid and need only a
repoint.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R684** consumers-share-relations-not-queries | Ready | `:94-96` asserts present-tense that "`graphitron-lsp` and `graphitron-mcp` both import generator types from `graphitron` today (`LspSchemaSnapshot`, `CatalogFacts`, `CompletionData`, `FieldClassification`, `TypeBackingShape` ...)". **Three of the five named symbols are now dead** (`CatalogFacts` R642, `LspSchemaSnapshot` + `FieldClassification` R638), and the `graphitron-mcp` half of the claim is doubly false (mcp compiles against `graphitron-model` + jOOQ only post-R642/R638). Only `CompletionData` / `TypeBackingShape` survive, and only in `graphitron-lsp`. The rule the item states is unaffected. | **Re-anchor** the example to `graphitron-lsp` only, keep just the two surviving symbols, and drop `CatalogFacts` / `LspSchemaSnapshot` / `FieldClassification`. A **Ready** item; refresh before pickup. |
| **R594** mcp-snapshot-axis-key-naming | Backlog | `:16-19` premise: "Four MCP tools report the live snapshot's availability ... `diagnostics` and `edges` call it ... `status` and `schema` hand-roll the same exhaustive switch over the `LspSchemaSnapshot` permits". R642 dropped the `edges` tool (so the "four tools" count is wrong) and R638 deleted `LspSchemaSnapshot` (so the "switch over the permits" cannot exist as spelled). But the fix surface **survives**: `McpWire.writeSnapshotAxes`, `snapshotAvailability`, `SchemaView`, `GraphitronMcpServer.statusResult` are all live, and both hand-rolled sites still exist, so the key-spelling cleanup goal survives intact. | **Re-anchor**, not re-spec: drop `edges`, re-count the tools against the post-R642 surface, and repoint the switch-source from the `LspSchemaSnapshot` permits onto the fact-store availability/freshness enum the sites now switch on. |

### C.13 Routine read-surface deferral removal drift (carried; R704)

R704 removed the `@routine` read-surface carve-outs and built the pagination arm.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R662** routine-chain-ordering-spans-nodes | Backlog | The "Notes for whoever picks this up" bullets state as current fact that "`@orderBy` is deferred on routine-backed fields today" and (`:78`) "`@asConnection` over a routine chain is rejected or deferred today"; R704 removed both deferrals. `:84`'s closing "Depends on R704 (`roadmap/routine-composition-surface-from-facts.md`)" still names R704's deleted item-file path (a dangling xref). | **Re-anchor** the two Notes bullets onto R704's shipped read surface. The core multi-node-ordering premise survives. Repoint the dangling `routine-composition-surface-from-facts.md` path onto R704's changelog id. |

### C.14 `LspSchemaSnapshot` / `FieldClassification` removal drift (new this window; R638)

R638 made the LSP a fact-store client and deleted `LspSchemaSnapshot` and `FieldClassification`
(both `grep`=0 in main). The two items below name one of them as live current-state; each keeps its
work valid and needs only a repoint. (R684, R594, R555, R557, R545 carry the same R638 drift and are
listed in their existing rows above; R337, R403, R381 mention a deleted symbol only in past-tense
historical framing and need no edit.)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R714** assembled-schema-owns-the-sdl-census | Backlog | `:96` "The machinery is already there: `LspSchemaSnapshot` carries availability and then current-versus-previous, and the editor 'tolerates the previous snapshot and tags it'." The snapshot is deleted; availability/current-vs-previous now lives on the fact store. | **Re-anchor** the vehicle to the fact-store per-census currency-status surface. The census-ownership argument survives; only the carrier name changed. |
| **R236** validator-reference-candidate-hint-terminal-table | Backlog | `:27` names the terminal table on the projected `FieldClassification.{ColumnReference,CompositeColumnReference}.tableName()` as the right list, and `:31` proposes routing candidate-hint dispatch "through the same `FieldClassification` projection". That projection is deleted, so design option (a) is no longer buildable. The underlying `BuildContext` candidate-hint bug still stands, and the item's own narrower option (b) survives. | **Re-anchor**: repoint the terminal-table source onto the surviving classifier, or adopt option (b) (look up the path's terminal table at the call site). Drop option (a). |

## D. Structural: (0)

Empty of blocking defects. `changelog.md` carries `next-id: R742`, clearing the max allocated id
(R741). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and the seven non-empty
`depends-on:` edges resolve to present files (this window's discard and four Done transitions left no
dangling edge). The roadmap-tool regenerates `README.md` with **no drift** at this HEAD. The items
filed this window carry well-formed front-matter and read born-current.

Two **pre-existing, non-blocking** hygiene notes:

1. Four **legacy** items still lack a `bucket:` key: R242 (`dml-payload-positional-alignment`),
   R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
   R180 (`record-parent-column-read-helper`). Build tolerates the omission; fold a `bucket:` in
   whenever each is next edited.
2. **R734 and R738 are numbering gaps**, each allocated and its file removed inline during the
   argMapping / `@nodeId` work rather than carried to a changelog `Done` entry. Numbers are never
   reused, so the gaps are expected and harmless (`next-id` R742 still clears the max present id R741).

## Cross-cutting observations

1. **A Done item stales the board only by deleting a construct active items name, and this window only
   R638 did.** Four items reached Done (R638, R680, R710, R732) and one was discarded (R347), but the
   whole staleness delta traces to R638's LSP-projection deletions. R710 shipped a large capture yet
   staled nothing because it deliberately deferred its reader and kept the live reflection reader; R680
   and R732 retired only test-internal or perf constructs. The audit-relevant lesson repeats the prior
   window's: read the premise, not just the identifier. Only R594 and R430 among R638's fallout hinge on
   a plain identifier; R430 in particular breaks by premise (the diagnostics-collection surface it
   assumed is gone) rather than by a single dead name.

2. **The prior audit was authored on a divergent history and shipped stale about two of its own Done
   items.** Its baseline `f6e9c34` is not an ancestor of this HEAD; its board figures (243 files, next-id
   R721) never fully held here. It recorded R638 as In Progress and R710 as a clean Ready item, when both
   reached Done in the same commit that landed the audit (`2173720`). And its §B.11 R709 flag names an
   item that has no file and no id on this branch at all: a phantom carried in from the divergent branch.
   When the next staleness audit runs, confirm each flagged slug resolves to a present file before
   carrying its flag forward, as this audit did.

3. **R193 and R213 are the two overdue subsumption candidates, and neither moved.** R193 asked for the
   sealed parameter classifier R649's `reduceClaims` / `ParamRole` shipped; R213 holds the same shape
   against R585 / R589. Running both re-checks, and most likely closing both as subsumed, is the cheapest
   board-cleaning available; deferring only lets the stale prose keep misleading.

4. **The Ready set is where stale prose bites soonest, and it turned over this window.** It is now R333,
   R427, R467, R555, R684, R686, R724, R730 (eight). R710 left it (to Done); R724 and R730 entered
   born-current. R333 (§C.5/§C.7), R427 (§C.0), R555 (§C.10, deepened with R638's `FieldClassification`
   hover cites), and R684 (§C.12, deepened with R638's `LspSchemaSnapshot` + `FieldClassification`) carry
   stale cites; R467 (`upgrade-graphql-java-26`), R686 (`error-handler-description-overrides-message`),
   R724, and R730 are clean. **Refreshing R333, R427, R555, and R684 before pickup remains the
   highest-value hygiene action on the board.**

5. **R615 is a watch item, not yet a flag.** `idreffixture-purpose-comment-stale` (R615, Backlog)
   correctly cites the live reflection reader `JooqCatalog.nodeIdMetadata` and the `NodeProvenance.Origin.METADATA`
   consumer chain. R710 added a parallel fact capture without retiring that reader; R668 (In Review) is the
   item that will rewire the readers off reflection. When R668 reaches Done, re-check R615, R273, R34, and
   R588, whose accuracy depends on the reflection reader surviving.

---

_Review date: 2026-08-20._



