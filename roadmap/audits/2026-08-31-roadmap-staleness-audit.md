# Roadmap staleness audit: 2026-08-31

A point-in-time review of every active roadmap item under [`roadmap/`](../) against the **current**
state of the codebase on `claude/graphitron-rewrite` (HEAD `f21760d`, committed 2026-08-30 22:36 UTC,
audited 2026-08-31). The goal is to find items whose premise no longer holds: work already shipped,
constructs renamed or removed, dependencies that have since landed or been discarded, or specs grown
stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a subdirectory so the roadmap-tool
(which scans `roadmap/*.md` non-recursively and requires `id:` front-matter on each) ignores it, and it
is Markdown so the `check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-08-28` staleness audit, which has been deleted; only the latest
**staleness** audit is retained. The other seventeen files in this directory are **not** staleness audits
and are left in place: deleting them would strand lineage that shipped items and active items cite by
path, and they are provenance this audit did not author. The retained companions are
`2026-06-16-source-operation-target-reframe.md`, `2026-06-30-release-planning.md`,
`2026-07-04-r222-r333-conformance-analysis.md`, `2026-07-26-fcis-command-layer-distance.md`,
`2026-08-05-fact-base-h2-spike.md`, `2026-08-05-h2-functions-jooq-spike.md`,
`2026-08-06-demand-exemption-census.md`, `2026-08-06-directive-consumer-census.md`,
`2026-08-06-fact-base-impact-sweep.md`, `2026-08-06-graphql-java-diff-spike.md`,
`2026-08-06-r222-lineage.md`, `2026-08-06-structural-classifier-census.md`,
`2026-08-19-github-issue-roadmap-linkage.md`, `2026-08-20-nodeid-relation-impact-sweep.md`,
`2026-08-27-carrier-filter-redundancy-probe.md`, `2026-08-28-derived-read-cost-premise.md`, and
`classification-test-dsl-inventory.md`.

**Baseline correction (the same drift the prior audit named in its own predecessor, and then repeated).**
The `2026-08-28` staleness audit measured "from `1f7ef5e` ... to this HEAD (`ef7edc9`, `next-id: R870`,
318 item files)". Neither commit is in the current trunk history: the branch was rebased, and the audit
was committed at `088ae37` (2026-08-29), whose **true tree read `next-id: R876` and 310 item files**, not
`R870` and 318. So the prior audit under-reached its own board by six allocated ids in one direction and
over-counted its file total in the other: it never reviewed the `R870` through `R875` intake, and its
transition snapshot (R848 In Progress; R675/R839/R858/R859 In Review; R860 Ready) had already advanced by
the time it landed, several of those items reaching Done in the gap between its ghost baseline and its
real commit. This is precisely the "audit committed against a tree it did not re-read" failure the
`2026-08-28` audit recorded against **its** predecessor, recurring one window later. This audit therefore
measures the delta from the **true tree at the prior audit's landing commit** (`088ae37`, `next-id:
R876`, 310 files) rather than from that audit's stated snapshot, and additionally reviews R870 through
R875, which the prior audit's text never reached. **Every flag below was re-verified by a fresh `grep` of
the main sources at this HEAD**, not carried on the prior audit's word.

## Headline: a quiet window; one item shipped (R878, additive to the node-id decode family), no discards, and all 44 carried flags hold at their recorded status

Relative to the true tree the prior audit was committed into (`088ae37`), **one item reached Done
(R878)**, **nothing was discarded**, and the model-level work in flight (R876, R877 In Progress) stayed
additive. No flag joined the set, no flag left it, and no flag changed section. The flag total holds at
**44**: **1 §A / 13 §B / 30 §C / 0 §D**.

- **R878 (`node-id-decode-hop-column-grain`, Done) shipped additively and corrective.** It re-keyed every
  relation below `intent_node_id_decode_endpoint` on the consuming coordinate with the polymorphic branch
  removed, so a multi-table `@nodeId` slot no longer reports one branch's rows as another's: the endpoint's
  departure (`origin_source_name` / `origin_schema` / `origin_table`) now rides down through the hop, the
  hop column, the key column and the decode, and `intent_node_id_decode.arity` states the node key's real
  width. Delivery is confined to `graphitron-model.sql` (the node-id decode relations) plus two model tests
  (`NodeIdDecodeBranchTest`, a `MaterializeRegistryGateTest` roster row). It deleted one index
  (`ix_node_id_decode_column_use_site`) whose two jobs both dissolved. **It renames or removes no symbol any
  open item names**, and its target relations are disjoint from the nodeId-cluster flags' re-anchor targets
  (R34 -> `intent_node_id_instruction.basis`, R135 -> `FilterBinding.Local`, both re-verified live). It
  stales nothing.

- **R762 stays in §B, unchanged.** The prior audit moved it there when R682 grew `intent_jvm_ancestor`,
  the first live reader of `jvm_class_supertype`. Both symbols re-verify present at this HEAD
  (`graphitron-model.sql` defines them; `JvmAncestorTest` and `FactCaptureAgreementTest` consume the
  closure), so R762's "read by nothing" premise remains false and its recommended action (re-spec the
  `jvm_class_supertype` subsection) still stands.

The new intake this window (R876, R877, R879) and the previously-unreviewed R870 through R875 all read
born-current and are correctly **not** flagged.

## Changes since the prior audit's true landing commit (`088ae37`)

Measured from `088ae37` (`next-id: R876`, 310 item files, 2026-08-29) to this HEAD (`f21760d`, `next-id:
R880`, 311 item files, 2026-08-30).

**Items that reached Done, and what each did to the symbol set:**

- **R878 (`node-id-decode-hop-column-grain`, Done):** filed and closed within this window (`8e3bc2f`
  Backlog, `d43d267` Done). Re-keyed the node-id decode family to the per-branch grain, deleted one index,
  added `NodeIdDecodeBranchTest`. Additive/corrective; retired no symbol any active item names.

**Discards:** none this window.

**In Progress model-level activity (additive to the tree, no retirements landed):** R876
(`derived-read-cost-is-a-shape-problem`) advanced through roughly twenty measurement slices over the
derived-read register and the intent rules; R877 (`graphitron-model-house-cleaning`) moved Backlog ->
Spec -> In Progress, making grain and owner declared data on every model relation. Neither has reached a
retirement step. The window's additive growth shows in the live-symbol file counts: `ChildField` 59 -> 67,
`KeyLift` 14 -> 21, `ComputedField` 14 -> 17, `ConditionOwnedField` 12 -> 13, `ColumnBackedField` 30 ->
31. R682's bulk retirement (the walk and its taxonomy: `GraphitronSchemaBuilder`, `TypeBuilder`,
`FieldBuilder`, the sealed classification hierarchies) still waits on its terminal step, so the many items
that name those walk-tier symbols are **not yet** staled.

**New items filed this window that remain open:** R876 (`derived-read-cost-is-a-shape-problem`, In
Progress) and R877 (`graphitron-model-house-cleaning`, In Progress). R879 was allocated (clearing the
`next-id` to R880) and left no file, a born-and-folded number. All born-current; none cites a retired
symbol as a live mechanism.

**Items the prior audit's ghost baseline hid (R870 to R875), reviewed this pass:** R870
(`capture-stops-reading-the-walk`, Spec), R872 (`warm-capture-empties-unpartitioned-catalog-relations`,
Backlog), R873 (`outcome-payload-polymorphic-child-arm-unwrap`, In Review), R874
(`nodeid-decode-helper-for-condition-methods`, In Review). R871 and R875 are numbering gaps (allocated,
no file). All present files read born-current; none cites a retired symbol as live.

**Transitions of note:** R878 Backlog -> Done within the window. R873 and R874 reached In Review. R876 and
R877 reached In Progress. R682 remains In Progress. R848
(`materialization-cut-set-is-accreted-not-designed`), R675 (`condition-method-overload-selection`), R839,
R858, R859, R860, and R867 (`cold-refresh-plans-without-statistics`) all left the active board in the gap
before the prior audit's landing and are gone now (shipped Done); none was a flagged item, so none stales
a flag. The In Progress set holds four (R682, R851, R876, R877); the In Review set holds three (R834,
R873, R874).

**Board accounting.** **311** item files today (measured, excluding `README.md` and `changelog.md`), up
from 310 at the prior landing commit. Status distribution: **265 Backlog, 30 Spec, 9 Ready, 4 In
Progress, 3 In Review, 0 Done**. Tombstone-free (`grep` for `status: Done` in `roadmap/*.md` = 0). No
duplicate `id:`; `changelog.md` carries `next-id: R880`, clearing the max present id (R877). A
`depends-on:` sweep resolves all **nine** non-empty edges to present files (eleven distinct targets, all
present). The only structural nits are the same four **legacy** items still missing a `bucket:` key (§D),
all pre-dating this window. `README.md` is current (regenerates with no drift via `mvn -pl roadmap-tool
exec:java -q`).

## Scope and method

All **311** `R<n>` item files were reviewed, and every flagged item's file was confirmed present on this
branch at the status recorded below (no flagged item shipped or was discarded this window). Every driving
symbol below was re-checked against a fresh `grep` of the main sources (`graphitron`, `graphitron-mcp`,
`graphitron-lsp`, `graphitron-model`, `graphitron-maven-plugin`, `graphitron-fixtures-codegen`,
`graphitron-javapoet`), not carried on the prior audit's word.

**No Done item this window stales an open item.** The single item that reached Done (R878) retired no
symbol any active item names: it is additive/corrective to the node-id decode relations and deleted only
an index. A full-window deletion/rename sweep over the main sources found no removed declaration that any
open item names as live.

**Long-standing retirements, re-verified still retired at this HEAD (`grep`=0 in main real code):**
`reflectServiceMethod`, `looksLikeSourcesShape`, `validateLift`, `CompileDependencyGraphBuilder`,
`FkTargetConditionEmitter`, `MutationConditions`, `RowsMethodBody`, `RowsMethodSkeleton`,
`QueryConditionsGenerator`, `TypeClassGenerator`, `TypeConditionsGenerator`, `buildNonTableInputType`,
`BatchedLookupTableField`, `InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`,
`InlineColumnReferenceFieldEmitter`, `LookupValuesJoinEmitter`, `RecordTableField`, `SplitTableField`,
`SplitLookupTableField`, `ParentProjectionContainmentCheck`, `collectRequiredProjection`,
`LspSchemaSnapshot`, `CatalogFacts`, `intent_class_assignable`, `planSlug`, `FieldClassification`,
`TableInputType`, `resolveInput` (as the retired `MutationInputResolver` dispatch), the retired
`SourceKey.Reader` interface, `ClassifiedCorpus`, `ClassifiedDocTest`, `OutcomeBlockDocTest`, and
`ReEvaluationMetric`. The `AUTHOR_ERROR` default arm is `grep`=0 in `FieldRegistry.java` (R209's residue,
§A). `CompileDiagnostic` is `grep`=0 in `graphitron-lsp` main (R430's flag), surviving only in
`graphitron` core (`CompileFacts` / `IncrementalCompileEngine` / `CompileDiagnostic`), exactly as R430
records. Two substring false positives were cleared and are recorded so a later pass does not re-flag
them: `InlineTableFieldEmitter` returns one hit, a comment in `graphitron-sakila-example`'s
`schema.graphqls` fixture, not a live symbol; `RecordTableField` returns one hit in the same file, the
substring of the test name `SingleRecordTableFieldServiceProducerPipelineTest`, not the retired variant.
A third, `SourceKey.Reader` in `SourceEnvelope.java`, is a javadoc provenance note to the retired reader
("riding on the retired ... reader"), not a live interface.

**Still live, not stale (re-verified with hits):** `FilterBinding` (13 files), `liftedSourceColumns`
(5 files), `ColumnBackedField` (31 files), `OperationMember` (25 files), `FkTargetConditionFilter`
(6 files), `ConditionGlueRenderer` (8 files) / `ProjectionUnitRenderer` (3 files),
`admitMutationInputFields` (5 files), `ConditionOwnedField` (13 files), `ChildField` (67 files),
`ComputedField` (17 files), `StubKey` (4 files), `KeyLift` (21 files) / `LifterRef` (7 files),
`ReachPath` (7 files), and both sides of the R762 premise: `jvm_class_supertype` (defined in
`graphitron-model.sql`) with its live reader `intent_jvm_ancestor` (`JvmAncestorTest`,
`FactCaptureAgreementTest`).

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried; still Backlog, still fully delivered.** The entire deliverable (remove the `RejectionKind.AUTHOR_ERROR` default arm and emit `RejectionKind.of(rejection)` consistently with `traceOutput`) is shipped by **R585**. Re-verified this window: `AUTHOR_ERROR` `grep`=0 in `FieldRegistry.java`. Nothing remains to do; not touched this window. | **Discard**, recording R585 as the delivery vehicle. |

R520 (`table-on-input-removal-housekeeping`) stays a coherent deliberately-deferred docs tail, not
obsolete. R846 (`authored-connection-type-scope-silence`) is a self-declared Backlog tombstone whose
fifth site shipped as R850; it is a deliberate redirect that its own body schedules for deletion when
R682 reaches Done, so it is functioning as intended and is not flagged.

## B. Outdated: needs re-spec (premise or targets materially changed) (13)

All thirteen carry forward from the prior audit with their recommended actions; every premise-target
re-verified `grep`=0 (or, for R34, its re-anchor relation re-verified materialized and live) at this HEAD.
**R762 remains here**, its `jvm_class_supertype` consumer (`intent_jvm_ancestor`) re-verified live. **R135
(§C.11, cross-listed here) and R34 both sit on the nodeId cluster,** whose re-anchor targets survive
(`FilterBinding.Local` / `liftedSourceColumns` live; `intent_node_id_instruction.basis` a shipped
materialized relation, undisturbed by R878's re-keying of the disjoint `intent_node_id_decode` family).
**R193 and R213 remain the two overdue subsumption candidates** (R649 / R585 / R589 shipped what they
scoped); running both re-checks, most likely closing both as subsumed, is the cheapest board-cleaning
available.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R762** census-stores-members-it-reads-by-name | Backlog | R682 added `intent_jvm_ancestor` (`247d387`), the first live reader of `jvm_class_supertype`, consumed by `intent_condition_table_parameter`; re-verified live this window. The item's subsection "`jvm_class_supertype` is written every capture and read by nothing", its table row "not read at all", and its "only consumer is `intent_class_assignable`" claim are now all false. The closure seeds from captured signatures, so it is an argument for capturing supertypes, not against. The item's broader lever (store class names, resolve the seven by-name relations on demand) survives for those relations. | **Re-spec the `jvm_class_supertype` subsection:** it now feeds a live derivation, so re-derive whether it is still droppable, and re-baseline the row-count split. The on-demand thesis for the other by-name relations is untouched; the tense-fix on the R760-deleted `intent_class_assignable` folds into this re-spec. |
| **R193** service-param-classification-sealed-hierarchy | Backlog | **R649 shipped the deliverable.** `reflectServiceMethod` / `looksLikeSourcesShape` `grep`=0; `reduceClaims` mints one sealed `ParamRole` per parameter. | **Re-derive the residue against shipped R649; most likely close as subsumed**, recording R649 as the delivery vehicle if nothing survives. |
| **R72** slim-servicecatalog-to-lookup | Backlog | **Premise materially changed by R649.** R649 dissolved `reflectServiceMethod` (`grep`=0) into `decodeServiceMethod` / `reduceClaims` / `bindServiceMethod`; `reflectTableMethod` / `reflectExternalField` survive. | **Re-spec** against the post-R649 split; drop every stale `ServiceCatalog.java:NNN` line cite. |
| **R213** input-field-rejection-attribution | Backlog | **Escalated, still unactioned.** `InputFieldResolution.Unresolved` now carries a `SourceLocation`; R589's occurrence-path derivation (Done) delivers the attribution split. | **Re-derive the residue against shipped R585 + R589; most likely close as subsumed.** |
| **R66** rejection-string-carrier-widening | Backlog | Phase **A2** was verbatim what **R585** shipped (`Unresolved` carries `Rejection rejection`, not `reason:String`). Phases A1, A3, B1, B2 survive. | **Re-spec:** strike A2 as delivered; re-baseline the four surviving phases; fix the `:25-30` anchors. |
| **R34** nodeid-migration-quickfix | Backlog | Carried, self-contradictory. The "shim facts" driver names three WARN sites deleted by R473; the re-anchor addendum (2026-08-20) confirms the driver is void and names `intent_node_id_instruction.basis` as the re-derivation target, a shipped materialized relation (re-verified live; R878 re-keyed the sibling `intent_node_id_decode` family, not this relation). Body still leads with the void driver. | **Re-spec** onto `intent_node_id_instruction` (`basis` + `node_type_name` + location); retitle off "shim facts". |
| **R122** compound-entity-mutations | Backlog | "Design space narrows under R222" leans on R222's discarded recursive `InputUsage` model (R222 left the board 2026-08-06). `ChildField.TableTargetField` (added by this item) is live. | **Re-spec the "narrows under R222" section**; re-express against the captured `intent_` / `applied_` relations. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Central target `CompileDependencyGraphBuilder.addFieldEdges` deleted (`grep`=0); the body's own dissolution condition has occurred. Cites absent `FkTargetConditionEmitter.emitTerm` and phantom `MutationConditions` as live. | **Re-derive against the plan-projected recompile graph.** If closed, discard, else re-spec; repoint `FkTargetConditionEmitter.emitTerm` to `FkTargetConditionFilter` and drop `MutationConditions`. |
| **R545** model-free-of-emit-vocabulary | Backlog | `RowsMethodBody` / `RowsMethodSkeleton` deleted (diagnosis + second deliverable gone); first deliverable survives (`ClassName` / `TypeName` model-pervasive). The `:18` counter-argument names R638-deleted `FieldClassification` / `TypeClassification`. | **Re-spec.** Drop the `RowsMethodBody` diagnosis; keep the first deliverable; update the `:18` counter-argument to the current `rewrite/catalog/` contents. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Both named edit-target generators deleted: `QueryConditionsGenerator` (R552), `TypeClassGenerator` (R549). Successors `ConditionGlueRenderer` / `ProjectionUnitRenderer` live. Names absent `Inline*Emitter` host files. | **Re-derive against the new `render/` layer**; drop every dead cite. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | R431 (Done) deleted the target surface: `SourceKey` is a plain `record` with no `Reader`. Successors `KeyLift` / `LifterRef` / sealed `Wrap` live; the "R431 ... plans to" tense is wrong (R431 is Done). | **Re-spec the current-state / approach section** against the decomposed model; fix the tense. |
| **R76** participant-fieldsjoin-helpers | Backlog | R650 converted cross-table participant fields to capped correlated subselects; the surviving `step.leftJoin(...)` is the joined-detail join only, so the cross-table `$fieldsJoin` framing is obsolete. | **Re-spec** onto the joined-detail `LEFT JOIN`, or discard if R650's subselect conversion removed the motivating duplication. |
| **R430** lsp-compile-diagnostics-publish | Backlog | **Premise broken by R638 (Done).** `CompileDiagnostic` / `Workspace.compileDiagnostics()` / `setCompileDiagnostics` are `grep`=0 in `graphitron-lsp` main (re-verified); R638 moved LSP diagnostics onto the capture cadence. `publishDiagnostics` survives. The compile-diagnostic stream still lives in `graphitron` core (`CompileFacts` / `IncrementalCompileEngine` / `CompileDiagnostic`); no window work touched this surface. | **Re-derive current state against the fact-store LSP**; re-anchor the publish trigger onto the surviving `graphitron`-core compile stream and keep the publish-against-generated-URI goal. Discard only if that stream is later retired. |

## C. Outdated: update references only (work valid, refs stale) (30)

Substance intact; names and line numbers drifted. Every long-standing driving symbol re-verified still
`grep`=0. **None of these was substantively refreshed this window.**

### C.0 `Operation` seal fully retired (carried; R563 slice 7)

`Operation` and every `Operation.<Arm>` reference are `grep`=0. Successor: `OperationMember`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R427** relevance-ranked-search | Ready | `:335` "`operation()` stays `Fetch`"; `:339`/`:1162` cite `Operation.Facet` as a live type. | **Re-anchor** onto the member-derived summary fold; repoint `Operation.Facet` to `OperationMember.Facet`. A **Ready** item; refresh before pickup. |
| **R382** multitable-interface-query-orderby-lowering | Backlog | `:19` cites `operation()` as the live mechanism hardcoding `new OrderBySpec.None()`. | **Re-anchor** to where the hardcoded `OrderBySpec.None` now lives; verify the ordering gap reproduces. |
| **R562** synthesised-connection-fields-as-coordinates | Backlog | `:17-18` name `Operation.Count` / `Operation.Facet`; all retired. | **Re-anchor** to `OperationMember.Count` / `Facet`. Model question intact. |

### C.1 Lookup-triplet dissolution drift (carried; R563 slice 6a)

A lookup leaf re-anchors to `BatchedTableField` (or `TableField` / `QueryTableField`) **plus a lookup member**.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R533** localcontext-guard-predicate-single-source | Backlog | `:15` names `BatchedLookupTableField` as the open edge; `:19` self-corrects but the lead is stale. | **Re-anchor** the `:15` lead to the post-dissolution sibling. |
| **R557** split-query-marker-sweep | Backlog | `:17`'s "consumed (the batched leaves)" lists `BatchedLookupTableField`; `:17` also anchors on the R638-deleted `CatalogBuilder.projectFieldClassification` seam. | **Re-anchor**: drop `BatchedLookupTableField`; repoint the "compile-checked-projection seam" onto the surviving leaf-classification mechanism (`GraphitronSchemaBuilder` / fact store). |

### C.2 `@table`-on-input rejection to deprecation drift (carried; R566)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R565** unclassified-input-arg-cascade-diagnostic | Backlog | Title (`:3`, `:13`) still leads with the retired `@table`-on-input **rejection**; the body already frames it against the current state. | **Re-anchor (not full re-spec).** Retitle/re-lead onto a still-current rejection; demote `@table`-on-input to historical framing. |

### C.3 `planSlug` / `SourceKey.Reader` removal drift (carried)

R484 removed `Rejection.Deferred.planSlug`; R431 removed the `SourceKey.Reader` interface.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R454** routine-write-result-shapes | Backlog | `:18` names deferred shapes as "typed `Deferred`s pointing at this item's planSlug"; gone. | **Re-anchor:** deferred shapes surface via `StubKey.VariantClass`. |
| **R447** routine-chain-fetch-form-breadth | Backlog | `:18`/`:33` "`planSlug` points here"; `:24`/`:26` name `SplitLookupTableField` / `RecordTableField`; `:23` names absent `InlineTableFieldEmitter`. Parked pagination note softly overlaps R704. | **Re-anchor:** drop `planSlug`; repoint to `BatchedTableField` (+ lookup member); drop `InlineTableFieldEmitter`. **At Spec, check** R704 subsumption. |
| **R180** record-parent-column-read-helper | Spec | `:35` "R431 ... now In Progress" (R431 is **Done**) and names `SourceKey.Reader.AccessorCall` as live. | **Re-anchor** the `AccessorCall` carrier onto the decomposed model; fix the R431 tense. |
| **R505** tenant-index-parent-row-routing | Backlog | `:21` names "a column read off the parent row (the `SourceKey.Reader` family)". Live via `KeyLift.FkColumns`. | **Re-anchor** the one parenthetical. |

### C.4 Leaf-merge drift: `Split*` / `Record*` to `Batched*` (carried; R717)

`SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** to `BatchedTableField`. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38` R305 lineage "collapsed it into `RecordTableField`"; `:107`/`:148`/`:150`/`:284` cite `LookupValuesJoinEmitter`. | **Re-anchor** to `BatchedTableField`; repoint `LookupValuesJoinEmitter` to the render values-join family. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34` name `SplitTableField` / `RecordTableField`; `:24` cites absent `InlineTableFieldEmitter`. | **Re-anchor** the two variant names to `BatchedTableField`; drop the emitter cite. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`'s planned case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R717** routine-carrier-residual-path-correlation | Backlog | `:51` names "R447's `RecordTableField`, which correlates from a handed record" as a live leaf; `RecordTableField` `grep`=0. A soft forward-looking cross-reference to R447's own (stale) vocabulary. | **Re-anchor** the cross-reference to `BatchedTableField` when R447 (§C.3) is refreshed; travels with that pass. Low priority; the open question (whether the arm generalises) is intact. |

### C.5 `ColumnBackedField` dissolution drift (carried; R508)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | Retired carriers named live across `:570`, `:694`, `:706`, `:727`, `:747-748`, `:798-799`, `:1112`, `:1140`, `:1145`, `:2009`. Part of the one R333 refresh (§C.7). | **Re-anchor** the carrier names to `ColumnBackedField`. Part of the one R333 pass. |

### C.6 `TableInputType` / `resolveInput` removal drift, plus the `UnboundField` reshape (carried; R519 + R515; R589)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R234** jooq-embedded-and-udt-input-backings | Backlog | `:15` cites `TypeBuilder.buildNonTableInputType` (`grep`=0) as the live dispatch. | **Re-anchor** to the current `TypeBuilder` input-classification path (`buildInputType`). |
| **R257** updaterows-walker-sdl-substrate | Backlog | `:17` calls `resolveInput` "the legacy resolver" (gone R515); `:15`/`:19` reach via `TableInputType.inputFields()` (gone R519). | **Re-anchor** both: `resolveInput` to `admitMutationInputFields`, `TableInputType.inputFields()` to per-consumer input resolution. |
| **R337** input-nesting-projection-classification | Backlog | `:30` reaches an input object's fields "via `TableInputType.inputFields()`" as the LSP-hover mechanism. | **Re-anchor** the one live mechanism cite to per-consumer input resolution. |
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot in `MutationInputResolver.resolveInput` (gone R515; one surviving hit is a historical comment); `:124` names the pre-R589 `UnboundField` carrier. | **Re-anchor** `:76` to `admitMutationInputFields`, `:124` to `ConditionOwnedField`. |

### C.7 Condition + projection emitter dissolution drift (carried; R552 + R549)

Condition emission is `render/ConditionGlueRenderer`; projection `render/ProjectionUnitRenderer`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh still incomplete; not refreshed this window.** Still at the symbol: 5x `TypeClassGenerator`, 5x `collectRequiredProjection`, `methodgraph`, `LookupValuesJoinEmitter`, 2x `ParentProjectionContainmentCheck`, 1x `TypeConditionsGenerator`, 4x `InlineTableFieldEmitter`, plus the §C.0/§C.5 carriers and §C.9 absent-emitter names. A pure implementation plan whose class names are wrong, and **Ready**, so it bites the next implementer. | **Finish the refresh in one pass** (this row + §C.0 + §C.5 + §C.6 + §C.1 + §C.9): repoint `TypeClassGenerator` / `collectRequiredProjection` to `ProjectionUnitRenderer` / `ProjectionCommands`; drop `TypeConditionsGenerator` / `ParentProjectionContainmentCheck` / `methodgraph` / `operation()`; re-anchor `LookupValuesJoinEmitter` and the `Operation` seal cites; replace `Inline*Emitter` names and `FkTargetConditionEmitter` to `FkTargetConditionFilter`. `SplitRowsMethodEmitter` rows stay (live). |
| **R231** emit-text-mapped-enum-fields-as-enum-type | Backlog | `:39` locates "the field-type-emit fork (likely in `TypeClassGenerator` or ...)". | **Re-anchor** the investigation locus to `ProjectionUnitRenderer` / `plan/ProjectionCommands`. |
| **R35** source-orientation-javadocs | Backlog | `:42` enumerates classes for a javadoc sweep, including deleted `TypeClassGenerator` and `TypeConditionsGenerator`. | **Re-anchor** the enumeration: drop the two deleted names. Low priority. |

### C.8 `InputFieldResolution.Unresolved` reshape drift (empty; R585)

R585 reshaped `Unresolved` to `(fieldName, SourceLocation, Rejection)`. Its residue lives in R66, R213
(§B) and R209 (§A). **No item remains in this subsection.**

### C.9 Absent projection/condition `*Emitter` names (carried; render-layer refactor)

A family of per-arm emitter names several items cite as **live** current-state, all **`grep`=0** at this
HEAD. Their work lives in `ProjectionUnitRenderer` / `ProjectionCommands`, `ConditionGlueRenderer` /
`ConditionCommands`, and `FkTargetConditionFilter`. **No distinct flagged item**; every citer is listed
above.

| Absent name | Cited-as-live in | Live successor |
|---|---|---|
| `InlineColumnReferenceFieldEmitter` | R333 (`:1890`) | render projection layer |
| `InlineTableFieldEmitter` | R333 (`:1752`,`:1891`), R85 (`:20`,`:45`), R447 (`:23`), R288 (`:24`) | render projection layer |
| `InlineLookupTableFieldEmitter` | R333 (`:1891`), R85 (`:21`,`:46`) | render projection layer |
| `FkTargetConditionEmitter` | R333 (`:1893`), R462 (`:45`, `.emitTerm`) | `FkTargetConditionFilter` via `ConditionCommands` |
| `MutationConditions` (phantom) | R462 (`:57`) | none; drop the shim name |

### C.10 `reflectServiceMethod` / `PkLessParent` / `validateRootInvariants` removal drift (carried; R649)

R649's phase split retired `ServiceCatalog.reflectServiceMethod` (now `decodeServiceMethod` +
`reduceClaims` + `bindServiceMethod`), `ServiceCatalog.PkLessParent`, and `validateRootInvariants`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R555** deprecate-externalfield-fold-into-service | Ready | Four present-tense cites of `reflectServiceMethod` (`:41`, `:52`, `:103`, `:106`); `:226-227`/`:400`/`:405` describe hover switching on R638-deleted `FieldClassification.Computed` vs `.ServiceBacked`; `:352`/`:491` are javadoc-respell tasks on `FieldClassification.Computed`. | **Re-anchor.** Repoint `reflectServiceMethod` to `decodeServiceMethod` / `ServiceSignature`; re-anchor Deliverable 4's hover onto `ChildField.ComputedField` (read from the fact store); drop the moot `FieldClassification.Computed` respell tasks. A **Ready** item; refresh before pickup. |
| **R47** service-short-classname-resolution | Backlog | `:15`/`:29`/`:51` name `ServiceCatalog.reflectServiceMethod` and "the three/four reflect* sites" as live edit targets. The load now lives in `decodeServiceMethod`. | **Re-anchor** the class-load site to `decodeServiceMethod` and re-enumerate the reflect* sites. Sequence with R72. |

### C.11 `FilterBinding` reshape drift + R728 junction-chain move (carried; R57; matured earlier)

R57 replaced the `liftedSourceColumns` slot on the two reference carriers with the sealed `FilterBinding`
(`Local(List<ColumnRef> ownTableColumns)` / payload-free `Remote`). The identically named component on the
resolver's `JoinPath` and `Resolved.FkTarget.DirectFk` **survives**. **R728 removed `validateLift`**
(`grep`=0), turning the junction-chain rejection into absent-local-columns reaching a hop-general
`EXISTS`. `liftedSourceColumns` (5 files) and `FilterBinding` (13 files) re-verify live.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R135** multi-hop-nodeid-fk-permutation-test | Backlog | `:17` pins the composite `InputField.ColumnBackedReferenceField.liftedSourceColumns()` ending in `[k1, k2]`; `:23` the `BodyParam.{RowEq,RowIn}` positional consumption. Carrier gone; tuple now `FilterBinding.Local`. The dated addendum (2026-08-20) records both this re-anchor and the `validateLift` carve-out; R728 has landed (`validateLift` `grep`=0), so the second re-anchor is due now. Body still carries the stale cites. | **Re-anchor** the `:17`/`:23` carriers onto `FilterBinding.Local`, and **restate the out-of-scope carve-out now** (it declined "relax the per-hop `validateLift` predicate", but R728 already removed that predicate). The test-plan goal is untouched. |

### C.12 `CatalogFacts` / mcp-tool-surface / `LspSchemaSnapshot` drift (carried; R642 + R638)

R642 cut graphitron-mcp off the generator (`CatalogFacts` `grep`=0, `edges` tool dropped). R638 deleted
`LspSchemaSnapshot` and `FieldClassification` (both `grep`=0). Both items keep their work valid.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R684** consumers-share-relations-not-queries | Ready | `:94-96` asserts `graphitron-lsp` and `graphitron-mcp` both import `LspSchemaSnapshot`, `CatalogFacts`, `CompletionData`, `FieldClassification`, `TypeBackingShape` today. Three of five are dead; the mcp half is doubly false post-R642/R638. Only `CompletionData` / `TypeBackingShape` survive, in `graphitron-lsp` only. | **Re-anchor** the example to `graphitron-lsp` only, keep the two surviving symbols, drop `CatalogFacts` / `LspSchemaSnapshot` / `FieldClassification`. A **Ready** item; refresh before pickup. |
| **R594** mcp-snapshot-axis-key-naming | Backlog | `:16-19` premise names "four MCP tools" (R642 dropped `edges`) and "the same exhaustive switch over the `LspSchemaSnapshot` permits" (R638 deleted the type). Fix surface survives: `McpWire.writeSnapshotAxes`, `snapshotAvailability`, `SchemaView`, `GraphitronMcpServer.statusResult` all live. | **Re-anchor**, not re-spec: drop `edges`, re-count the tools, repoint the switch-source from the `LspSchemaSnapshot` permits onto the fact-store availability/freshness enum. |

### C.13 Routine read-surface deferral removal drift (carried; R704)

R704 removed the `@routine` read-surface carve-outs and built the pagination arm.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R662** routine-chain-ordering-spans-nodes | Backlog | The "Notes for whoever picks this up" bullets state as current fact that "`@orderBy` is deferred on routine-backed fields today" and (`:78`) "`@asConnection` over a routine chain is rejected or deferred today"; R704 removed both. `:84` names R704's deleted item-file path (dangling xref). | **Re-anchor** the two Notes bullets onto R704's shipped read surface; repoint the dangling `routine-composition-surface-from-facts.md` path onto R704's changelog id. Multi-node-ordering premise survives. |

### C.14 `LspSchemaSnapshot` / `FieldClassification` removal drift (carried; R638)

R638 deleted `LspSchemaSnapshot` and `FieldClassification`. Only R236 remains.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R236** validator-reference-candidate-hint-terminal-table | Backlog | `:27` names the terminal table on the projected `FieldClassification.{ColumnReference,CompositeColumnReference}.tableName()`, and `:31` proposes routing candidate-hint dispatch "through the same `FieldClassification` projection". That projection is deleted (`grep`=0), so design option (a) is no longer buildable. The underlying `BuildContext` candidate-hint bug still stands, and option (b) survives. | **Re-anchor**: repoint the terminal-table source onto the surviving classifier, or adopt option (b). Drop option (a). |

### C.15 `intent_class_assignable` deletion drift (carried; R760)

R760 (Done) deleted `intent_class_assignable` (`grep`=0, re-verified this window). R733 remains, with one
item naming the deleted view in the present tense.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R733** build-wall-clock-guardrail | Backlog | `:347` names "the census's transitive closure view `intent_class_assignable`" present-tense ("does not return on a real census ... nothing reads it"), knowingly citing R760's filed rewrite, but R760 has since **deleted** the view. Additionally, a working closure now exists: R682's `intent_jvm_ancestor` reads `jvm_class_supertype` and terminates, so the census read-side caveat should note that supertypes now feed a live derivation. | **Re-anchor the tense**: the view was deleted by R760, so "nothing reads it" is moot; update the census-measurement caveat to note `intent_jvm_ancestor` (R682) now reads the supertype rows. Not a slice of this item, as the body already says. Low priority. |

## D. Structural: (0)

Empty of blocking defects. `changelog.md` carries `next-id: R880`, clearing the max present id (R877).
No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and the nine non-empty `depends-on:`
edges resolve to eleven distinct present target files (`carrier-recognizer-conflates-three-scan-verdicts`
-> `dml-carrier-errors-field-blocks-return-derived-table`; `condition-table-parameter-anchor-assignability`
-> `condition-method-overload-selection`; `corpus-directives-to-expect-equals` ->
`planners-read-facts-emitters-read-commands`; `dev-start-refreshes-the-register-twice` ->
`capture-moves-below-the-generator`, `capture-without-the-materialization-refresh`, and
`warm-capture-empties-unpartitioned-catalog-relations`; `multi-source-input-validation` ->
`catalog-check-constraint-validation`; `operation-driven-test-corpus` -> `capability-catalog`;
`rover-graphos-integration` -> `oneof-augment-defeated-by-descriptions`; `preserve-enum-extraction-through-condition-rewrap`
target present; `validator-integration-execute-coverage` -> `multi-source-input-validation`). The
`dev-start-refreshes-the-register-twice` edge gained a third target this window
(`warm-capture-empties-unpartitioned-catalog-relations`, R872), which resolves.

Two **pre-existing, non-blocking** hygiene notes, unchanged this window:

1. Four **legacy** items still lack a `bucket:` key: R242 (`dml-payload-positional-alignment`),
   R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
   R180 (`record-parent-column-read-helper`). Build tolerates the omission; fold a `bucket:` in whenever
   each is next edited.
2. **Numbering gaps** among the item files (each allocated id either folded away inline or born-and-closed
   within a window, R871/R875/R878/R879 this pass among them) are harmless: numbers are never reused, and
   `next-id` R880 clears the max present id R877.

## Cross-cutting observations

1. **This was a quiet consolidation window: one item shipped, nothing was discarded, and no flag moved.**
   R878 (the node-id decode grain re-key) is additive and corrective, confined to `graphitron-model.sql`
   and its tests, and retires nothing an active item names. The two In Progress items (R876, R877) grew
   the tree additively (live-symbol file counts rose; no retirement landed). The flag set is byte-for-byte
   the same 44 the prior audit recorded, each re-verified by fresh grep at this HEAD.

2. **The prior audit drifted from its own commit tree, and this pass corrects the baseline.** The
   `2026-08-28` staleness audit described `next-id: R870` and 318 files while it was committed at
   `next-id: R876` and 310 files, so it never reviewed R870 through R875 and its transition snapshot had
   already advanced by landing. This is the identical failure that audit recorded against its own
   predecessor. The lesson has now recurred twice: an audit must re-read the tree at the commit it is
   actually landing in, not the (possibly rebased-away) snapshot it was drafted against. The fix this pass
   ran is the honest baseline (`088ae37`, the true landing tree) plus a fresh full-corpus grep.

3. **R682 is still the model-level activity whose terminal step is the next audit's likely headline.** Its
   in-window history remains additive (the supertype closure `intent_jvm_ancestor`, the condition-table
   arm, the enum-class capture); it retires nothing yet. The bulk retirement, the walk and its taxonomy
   (`GraphitronSchemaBuilder`, `TypeBuilder`, `FieldBuilder`, the sealed classification hierarchies),
   waits on the terminal step. When it lands it will stale the many Backlog items that name those
   walk-tier symbols at once. Nothing to do now beyond noting it.

4. **R193 and R213 remain the two overdue subsumption candidates, and neither moved.** R193 asked for the
   sealed parameter classifier R649's `reduceClaims` / `ParamRole` shipped; R213 holds the same shape
   against R585 / R589. Running both re-checks and most likely closing both as subsumed is the cheapest
   board-cleaning available. R209 (§A) is the third mechanical close (fully delivered by R585).

5. **The Ready set is where stale prose bites soonest.** It is now R333, R427, R467, R555, R663, R684,
   R724, R730, R838 (nine; R860 shipped this cycle). Of these, **R333 (§C.5/§C.7), R427 (§C.0), R555
   (§C.10), and R684 (§C.12) carry stale cites**, none refreshed this window; R467, R663, R724, R730 are
   clean, and R838 is born-current. **Refreshing R333, R427, R555, and R684 before pickup remains the
   highest-value hygiene action on the board.**

6. **The new-item intake is born-current, and so is the previously-unreviewed R870 to R875 span.** The
   items filed this window (R876, R877) and the six the prior audit's ghost baseline hid (R870 to R875)
   cite no retired symbol as a live mechanism. Several are follow-ups to the capture, dev-loop, and
   derived-read-cost work in flight, and read against the live relations.

---

_Review date: 2026-08-31._

