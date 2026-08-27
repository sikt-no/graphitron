# Roadmap staleness audit: 2026-08-27

A point-in-time review of every active roadmap item under [`roadmap/`](../) against the
**current** state of the codebase on `claude/graphitron-rewrite` (HEAD `404dff1`, committed
2026-08-26 21:46 UTC, audited 2026-08-27). The goal is to find items whose premise no longer holds:
work already shipped, constructs renamed or removed, dependencies that have since landed or been
discarded, or specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a subdirectory so the
roadmap-tool (which scans `roadmap/*.md` non-recursively and requires `id:` front-matter on each)
ignores it, and it is Markdown so the `check-adoc-tables` build step (which scans `.adoc` only)
leaves it alone.

This audit supersedes the `2026-08-26` staleness audit, which has been deleted; only the latest
**staleness** audit is retained. The other fifteen files in this directory are **not** staleness
audits and are left in place: deleting them would strand lineage that shipped items and active items
cite by path (verified this pass: `2026-08-06-fact-base-impact-sweep.md` is cited by 50 items,
`2026-07-26-fcis-command-layer-distance.md` by 12, `2026-08-20-nodeid-relation-impact-sweep.md` and
`classification-test-dsl-inventory.md` by 8 each, and eight others by one to four), and they are
provenance this audit did not author. The retained companions are
`2026-06-16-source-operation-target-reframe.md`, `2026-06-30-release-planning.md`,
`2026-07-04-r222-r333-conformance-analysis.md`, `2026-07-26-fcis-command-layer-distance.md`,
`2026-08-05-fact-base-h2-spike.md`, `2026-08-05-h2-functions-jooq-spike.md`,
`2026-08-06-demand-exemption-census.md`, `2026-08-06-directive-consumer-census.md`,
`2026-08-06-fact-base-impact-sweep.md`, `2026-08-06-graphql-java-diff-spike.md`,
`2026-08-06-r222-lineage.md`, `2026-08-06-structural-classifier-census.md`,
`2026-08-19-github-issue-roadmap-linkage.md`, `2026-08-20-nodeid-relation-impact-sweep.md` (the
`@nodeId`-relation architecture-drift companion whose findings this audit takes as read for the
nodeId cluster), and `classification-test-dsl-inventory.md`.

**Window and method caveat.** As with the prior audit, the checkout is a shallow graft, so the prior
audit's stated HEAD does not resolve here and no single git range spans the audit window cleanly. The
window is therefore reconstructed from the board itself (`next-id` advanced R836 to R849) and from the
changelog, and **every flag below was re-verified by a fresh `grep` of the main sources at this HEAD**,
not carried on the prior audit's word. Two background sweeps ran this pass: one re-checked every
retired-symbol `grep`=0 / still-live claim the prior audit made, and one examined every deletion and
rename in the visible window to test whether any Done item retired a construct an active item names.
Both are folded into the findings below. The window is a busy but model-quiet one: the visible commits
fall on 2026-08-26, and while five items reached Done, none retired a construct an open item cites.

## Headline: five items shipped and none staled an active premise; a completeness sweep added two long-standing §C citers the prior tables had missed, lifting the flag total from 42 to 44

Relative to the board the prior audit recorded, **five items reached Done** (R676, R726, R814, R832,
R840). Their subject matter is **nodeId per-participant decode** (R676, R726), an **architecture-docs
reorganization** (R814), a **test-budget re-measurement** (R832), and the **spec-by-example corpus
becoming a folder of documents** (R840). Each touched only nodeId-relation-internal, docs, test-tier,
or corpus-tooling constructs. **None staled an active model or emit premise.** The background deletion
sweep confirms it directly: no production or model construct (Java class/method, sealed variant arm,
`intent_*`/`applied_*` relation, directive) was deleted or renamed anywhere in the visible window; the
only deletions are three test-scope files (R840's retired corpus tests) and each Done item's own spec
file.

- **The nodeId cluster (R676, R726) shipped additively.** R676 widened `@referenceFor` to
  `INPUT_FIELD_DEFINITION | ARGUMENT_DEFINITION`, added the `AuthorOwnedPredicate` arm, the
  `graphitron_argument_reference_for` capture family, and the `derive/`-side
  `ReferenceForParticipantDefects` join; R726 stated `intent_node_id_instruction`'s multitable
  behaviour on the relation and added the `PARTICIPANT_TABLE` arm to `intent_field_scope_table`.
  Neither retired a symbol any open item names, and the reflection reader `JooqCatalog.nodeIdMetadata`
  (4 files, 16 refs) is untouched, so the R615 / R273 / R34 / R588 watch chain still holds.
- **R840 retired the corpus's Java scaffolding cleanly.** `ClassifiedCorpus`, the Java prelude string,
  `ClassifiedDocTest` and `OutcomeBlockDocTest` are gone (`grep`=0), replaced by a folder of
  `corpus/<id>.graphqls` documents loaded through `CorpusDocuments`. The retirement's only roadmap
  citers, R333, R555 and R733, were swept in R840's own Done commit (`ClassifiedCorpus` ->
  `CorpusDocuments`), so no open item is left naming a retired corpus symbol. `ClassifiedHarness` and
  `VariantCoverageTest` survive and their cites remain live.
- **R682 (In Progress) advanced but retired nothing this window.** Its nine in-window commits are
  entirely additive: they add fact relations (`intent_mutation_payload_column`,
  `intent_mutation_matched_key`, `intent_mutation_payload_key_membership`,
  `intent_mutation_write_refusal`, `intent_mutation_write_destination`, `intent_mutation_write_agreement`,
  `intent_input_occurrence_descent_order`, `intent_condition_membership`, and supporting relations) plus
  their tests. The `CREATE VIEW`/materialization churn in the diffs is within-commit table/view
  swapping, not retirement; all names verify present at this HEAD. R682's `## Retired vocabulary` list
  is still provisional and its bulk retirement (the walk and its taxonomy: `GraphitronSchemaBuilder`,
  `TypeBuilder`, `FieldBuilder`, the sealed classification hierarchies) waits on the terminal step, so
  the many items that name those walk-tier symbols are **not yet** staled.

**The 42 flags the prior audit carried all hold**, at their recorded status and at their driving
symbols: the retired-symbol re-verification came back clean for every entry (`reflectServiceMethod`,
`TypeClassGenerator`, `validateLift`, `LspSchemaSnapshot`, `CatalogFacts`, `intent_class_assignable`,
`planSlug`, `BatchedLookupTableField`, `RecordTableField` and the rest all `grep`=0; `FilterBinding`,
`liftedSourceColumns`, `ColumnBackedField`, `OperationMember`, `FkTargetConditionFilter`,
`nodeIdMetadata` and the render layer all still live), and every flagged item is present at the status
recorded below.

**But a full-corpus symbol sweep this pass surfaced two pre-existing §C citers the prior audit's
tables did not list.** Neither is new drift: both items predate this window and both name a symbol
retired in an earlier window. **R717** (`routine-carrier-residual-path-correlation`, Backlog) names
`RecordTableField` (`grep`=0, the leaf-merge dissolution) as a live leaf via a cross-reference to
R447; **R733** (`build-wall-clock-guardrail`, Backlog) names the `intent_class_assignable` view in the
present tense though R760 deleted it. Both are "update references only" (§C) drifts. They join §C.4 and
§C.15 respectively, lifting the total from **42 to 44**.

Net: **1 §A / 12 §B / 31 §C / 0 §D**, flag total **44**. The two added flags are the only change to the
flag set; no flag left, and no carried flag's recommended action changed.

## Changes since the 2026-08-26 audit

Measured from the board the prior audit recorded (294 item files, `next-id` R836) to this HEAD (302
item files, `next-id` R849).

**Items that reached Done since the prior audit's board, and what each did to the symbol set:**

- **R840 (`rejection-spec-by-example` corpus, Done):** the spec-by-example corpus became a folder of
  self-describing, fact-first SDL documents; all 57 fixtures live as `corpus/<id>.graphqls` with a
  prelude document beside them. **Retired** `ClassifiedCorpus`, the Java prelude string,
  `ClassifiedDocTest` and `OutcomeBlockDocTest` (all `grep`=0). Swept its only three roadmap citers
  (R333, R555, R733) in the same commit; no open item is left naming a retired corpus symbol.
- **R814 (`architecture-docs-describe-the-destination`, Done):** reorganized
  `docs/architecture/reference/code-generation-triggers.adoc` around the generator's capture-to-render
  chain and landed two guards (`ArchitectureDocSymbolGuardTest`, a widened `TransientCitationCheck`).
  Docs plus test guards; retired no production symbol. The file still exists (content-collapsed, not
  removed), so R845's reference to it resolves.
- **R832 (`catalog-refresh-latch-budget-prices-a-real-trigger`, Done):** split `CatalogRefreshTest`'s
  single `WAIT_MS` into a failure ceiling and a quiescence window and re-measured the ceiling under
  load. Test-only; no production symbol touched.
- **R726 (`nodeid-bare-inference-per-participant-divergence`, Done):** stated
  `intent_node_id_instruction`'s multitable-coordinate behaviour on the relation and pinned it at both
  tiers; added the `PARTICIPANT_TABLE` arm to `intent_field_scope_table`. Additive. Ready at the prior
  audit.
- **R676 (`nodeid-filter-per-participant-paths`, Done):** a `@nodeId` filter input on a multitable
  query can state a per-participant join path, and `@condition(override: true)` takes the predicate
  where no route resolves. Widened `@referenceFor`, added the `AuthorOwnedPredicate` arm, the
  `graphitron_argument_reference_for` capture family, and `ReferenceForParticipantDefects`. Retired
  nothing. Ready at the prior audit.

**Discards:** none this window.

**Items filed this window that remain open (R836 to R848):** thirteen live files. R836
(`fact-schema-prose-plain-language`, Spec), R837 (`table-less-jooq-record-input`, Spec), R838
(`declarative-comment-style`, Ready), R839 (`carrier-refresh-inlined-producer-cte`, Spec, re-spec of
the retired `catalog-refresh-latch-budget-prices-a-real-trigger`), R841 (`write-payload-column-read-cost`),
R842 (`refused-patterns-gather-in-one-section`, Spec), R843 (`parallel-execution-tier-shares-sakila-rows`),
R844 (`semicolon-substitutions-read-as-punctuation-errors`), R845 (`live-trigger-rows-become-worked-examples`),
R846 (`authored-connection-type-scope-silence`), R847 (`reference-path-condition-terminal-column-scope`),
and R848 (`materialization-cut-set-is-accreted-not-designed`). All read **born-current** and are
correctly **not** flagged. The one apparent exception confirms the rule: R842 names
`FieldClassification.Conflicted`, but does so knowingly, as vocabulary "being dissolved" by R682, not
as a live mechanism, which is the item's whole point.

**Transitions of note:** R682 (`planners-read-facts-emitters-read-commands`) remains In Progress (the
only In Progress item now that R814 is Done); R834 (`root-service-table-return-skips-key-refetch`) is
now In Review; R838 entered Ready. The In Review set holds one item (R834); the In Progress set holds
one (R682).

**Board accounting.** **302** item files today (measured, excluding `README.md` and `changelog.md`),
up from 294. Status distribution: **263 Backlog, 28 Spec, 9 Ready, 1 In Progress, 1 In Review, 0
Done**. Tombstone-free (`grep` for `status: Done` in `roadmap/*.md` = 0). No duplicate `id:`;
`changelog.md` carries `next-id: R849`, clearing the max present id (R848). A `depends-on:` sweep
resolves all **six** non-empty edges to present files. The only structural nits are the same four
**legacy** items still missing a `bucket:` key (§D), all pre-dating this window.

## Scope and method

All **302** `R<n>` item files were reviewed, and every flagged item's file was confirmed present on
this branch at the status recorded below (no flagged item shipped or was discarded this window). Every
driving symbol below was re-checked against a fresh `grep` of the main sources (`graphitron`,
`graphitron-mcp`, `graphitron-lsp`, `graphitron-model`, `graphitron-maven-plugin`,
`graphitron-fixtures-codegen`, `graphitron-javapoet`), not carried on the prior audit's word.

**No retirement this window that stales an open item.** The five items that reached Done (R676, R726,
R814, R832, R840) retired no symbol any active item names, and R682 was additive. A full-window
deletion/rename sweep over the main sources found zero removed production declarations; the only
retirements are R840's three corpus test files (`ClassifiedCorpus.java`, `ClassifiedDocTest.java`,
`OutcomeBlockDocTest.java`), each `grep`=0 outside `roadmap/` and named by no open item, and each Done
item's own spec file.

**Long-standing retirements, re-verified still retired at this HEAD (`grep`=0 in main real code):**
`reflectServiceMethod`, `looksLikeSourcesShape`, `validateLift`, `CompileDependencyGraphBuilder`,
`FkTargetConditionEmitter`, `MutationConditions`, `RowsMethodBody`, `RowsMethodSkeleton`,
`QueryConditionsGenerator`, `TypeClassGenerator`, `TypeConditionsGenerator`, `buildNonTableInputType`,
`BatchedLookupTableField`, `InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`,
`InlineColumnReferenceFieldEmitter`, `LookupValuesJoinEmitter`, `RecordTableField`, `SplitTableField`,
`SplitLookupTableField`, `ParentProjectionContainmentCheck`, `collectRequiredProjection`,
`LspSchemaSnapshot`, `CatalogFacts`, `intent_class_assignable`, `planSlug`, `FieldClassification`,
`TypeClassification`, the `Operation` seal and every `Operation.<Arm>` member, and the
`SourceKey.Reader` interface (one surviving hit is a javadoc mention of the retired symbol in
`SourceEnvelope.java`, not a live reference). Also `AUTHOR_ERROR` `grep`=0 in `FieldRegistry.java` (the
surviving `AUTHOR_ERROR` hits are the general `RejectionKind` enum in `RejectionKind` / `BuildContext`
/ `FieldBuilder`, not the FieldRegistry default arm).

**Still live, not stale (re-verified with hits):** `FilterBinding` (13 files), `liftedSourceColumns`
(5 files), the resolver's `Resolved.FkTarget.DirectFk.liftedSourceColumns()`, `ColumnBackedField` (30
files), `ChildField.ColumnBackedReferenceField` (28 files), the reflection reader
`JooqCatalog.nodeIdMetadata` (4 files, 16 refs), `OperationMember` (25 files), `FkTargetConditionFilter`
(6 files), `ConditionGlueRenderer` / `ProjectionUnitRenderer` (the render layer), and R649's successors
`decodeServiceMethod` / `reduceClaims` / `bindServiceMethod`. `jvm_class_supertype` survives as a
fact-model declaration in `graphitron-model.sql` with zero Java consumers (as §C.15 expects).

**R430 re-confirmed (still §B, not discardable).** `Workspace.compileDiagnostics()` /
`setCompileDiagnostics` / `CompileDiagnostic` are `grep`=0 in `graphitron-lsp` main, and the
compile-diagnostic stream still lives in `graphitron` core (`CompileDiagnostic` / `CompileFacts` /
`IncrementalCompileEngine` / `CompileRound`, 4 files). No window work touched either surface, so
R430's premise-break is unchanged and its re-anchor target survives.

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried; still Backlog, still fully delivered.** The entire deliverable ("remove the `RejectionKind.AUTHOR_ERROR` default arm and emit `RejectionKind.of(rejection)` consistently with `traceOutput`") is shipped by **R585**. Re-verified this window: `AUTHOR_ERROR` `grep`=0 in `FieldRegistry.java`. Nothing remains to do; not touched this window. | **Discard**, recording R585 as the delivery vehicle. |

R520 (`table-on-input-removal-housekeeping`) stays a coherent deliberately-deferred docs tail, not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (12)

All twelve carry forward with their prior recommended actions verbatim; every premise-target
re-verified `grep`=0 (or, for R34, its re-anchor relation re-verified materialized and live) at this
HEAD. **R135 (§C.11, cross-listed here) and R34 both sit on the nodeId cluster,** which this window
advanced (R676, R726 reached Done) without retiring either item's re-anchor target: R135's
`FilterBinding.Local` / `liftedSourceColumns` survive, and R34's `intent_node_id_instruction.basis`
remains a shipped materialized relation. **R193 and R213 remain the two overdue subsumption
candidates** (R649 / R585 / R589 shipped what they scoped); running both re-checks, most likely
closing both as subsumed, is the cheapest board-cleaning available.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R193** service-param-classification-sealed-hierarchy | Backlog | **R649 shipped the deliverable.** `reflectServiceMethod` / `looksLikeSourcesShape` `grep`=0; `reduceClaims` mints one sealed `ParamRole` per parameter. | **Re-derive the residue against shipped R649; most likely close as subsumed**, recording R649 as the delivery vehicle if nothing survives. |
| **R72** slim-servicecatalog-to-lookup | Backlog | **Premise materially changed by R649.** R649 dissolved `reflectServiceMethod` (`grep`=0) into `decodeServiceMethod` / `reduceClaims` / `bindServiceMethod`; `reflectTableMethod` / `reflectExternalField` survive. | **Re-spec** against the post-R649 split; drop every stale `ServiceCatalog.java:NNN` line cite. |
| **R213** input-field-rejection-attribution | Backlog | **Escalated, still unactioned.** `InputFieldResolution.Unresolved` now carries a `SourceLocation`; R589's occurrence-path derivation (Done) delivers the attribution split. | **Re-derive the residue against shipped R585 + R589; most likely close as subsumed.** |
| **R66** rejection-string-carrier-widening | Backlog | Phase **A2** was verbatim what **R585** shipped (`Unresolved` carries `Rejection rejection`, not `reason:String`). Phases A1, A3, B1, B2 survive. | **Re-spec:** strike A2 as delivered; re-baseline the four surviving phases; fix the `:25-30` anchors. |
| **R34** nodeid-migration-quickfix | Backlog | Carried, self-contradictory. The "shim facts" driver names three WARN sites deleted by R473; the re-anchor addendum (dated 2026-08-20) confirms the driver is void and names `intent_node_id_instruction.basis` as the re-derivation target, a shipped materialized relation. Body still leads with the void driver. | **Re-spec** onto `intent_node_id_instruction` (`basis` + `node_type_name` + location); retitle off "shim facts". The addendum already records this and its target is a shipped materialized relation. |
| **R122** compound-entity-mutations | Backlog | "Design space narrows under R222" leans on R222's discarded recursive `InputUsage` model (R222 left the board 2026-08-06). `ChildField.TableTargetField` (added by this item) is live. | **Re-spec the "narrows under R222" section**; re-express against the captured `intent_` / `applied_` relations. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Central target `CompileDependencyGraphBuilder.addFieldEdges` deleted (`grep`=0); the body's own dissolution condition has occurred. Cites absent `FkTargetConditionEmitter.emitTerm` and phantom `MutationConditions` as live. | **Re-derive against the plan-projected recompile graph.** If closed, discard, else re-spec; repoint `FkTargetConditionEmitter.emitTerm` -> `FkTargetConditionFilter` and drop `MutationConditions`. |
| **R545** model-free-of-emit-vocabulary | Backlog | `RowsMethodBody` / `RowsMethodSkeleton` deleted (diagnosis + second deliverable gone); first deliverable survives (`ClassName` / `TypeName` model-pervasive). The `:18` counter-argument names R638-deleted `FieldClassification` / `TypeClassification`. | **Re-spec.** Drop the `RowsMethodBody` diagnosis; keep the first deliverable; update the `:18` counter-argument to the current `rewrite/catalog/` contents. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Both named edit-target generators deleted: `QueryConditionsGenerator` (R552), `TypeClassGenerator` (R549). Successors `ConditionGlueRenderer` / `ProjectionUnitRenderer` live. Names absent `Inline*Emitter` host files. | **Re-derive against the new `render/` layer**; drop every dead cite. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | R431 (Done) deleted the target surface: `SourceKey` is a plain `record` with no `Reader`. Successors `KeyLift` / `LifterRef` / sealed `Wrap` live; the "R431 ... plans to" tense is wrong (R431 is Done). | **Re-spec the current-state / approach section** against the decomposed model; fix the tense. |
| **R76** participant-fieldsjoin-helpers | Backlog | R650 converted cross-table participant fields to capped correlated subselects; the surviving `step.leftJoin(...)` is the joined-detail join only, so the cross-table `$fieldsJoin` framing is obsolete. | **Re-spec** onto the joined-detail `LEFT JOIN`, or discard if R650's subselect conversion removed the motivating duplication. |
| **R430** lsp-compile-diagnostics-publish | Backlog | **Premise broken by R638 (Done).** `CompileDiagnostic` / `Workspace.compileDiagnostics()` / `setCompileDiagnostics` are `grep`=0 in `graphitron-lsp` main; R638 moved LSP diagnostics onto the capture cadence. `publishDiagnostics` survives. The compile-diagnostic stream still lives in `graphitron` core (`CompileFacts` / `IncrementalCompileEngine` / `CompileDiagnostic`); no window work touched this surface or the store-read one. | **Re-derive current state against the fact-store LSP**; re-anchor the publish trigger onto the surviving `graphitron`-core compile stream and keep the publish-against-generated-URI goal. Discard only if that stream is later retired. |

## C. Outdated: update references only (work valid, refs stale) (31)

Substance intact; names and line numbers drifted. Every long-standing driving symbol re-verified
still `grep`=0. **R135 (§C.11) was matured on R728's landing and holds unchanged; none of these were
substantively refreshed this window** (R840's Done commit swept the `ClassifiedCorpus` rename through
R333 and R555 but touched none of their other stale cites). **Two entries are new to the tables this
pass**, both pre-existing drift a full-corpus sweep surfaced: R717 (§C.4) and R733 (§C.15).

### C.0 `Operation` seal fully retired (carried; R563 slice 7)

`Operation` and every `Operation.<Arm>` reference are `grep`=0. Successor: `OperationMember`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R427** relevance-ranked-search | Ready | `:335` "`operation()` stays `Fetch`"; `:339`/`:1162` cite `Operation.Facet` as a live type. | **Re-anchor** onto the member-derived summary fold; repoint `Operation.Facet` -> `OperationMember.Facet`. A **Ready** item; refresh before pickup. |
| **R382** multitable-interface-query-orderby-lowering | Backlog | `:19` cites `operation()` as the live mechanism hardcoding `new OrderBySpec.None()`. | **Re-anchor** to where the hardcoded `OrderBySpec.None` now lives; verify the ordering gap reproduces. |
| **R562** synthesised-connection-fields-as-coordinates | Backlog | `:17-18` name `Operation.Count` / `Operation.Facet`; all retired. | **Re-anchor** to `OperationMember.Count` / `Facet`. Model question intact. |

### C.1 Lookup-triplet dissolution drift (carried; R563 slice 6a)

A lookup leaf re-anchors to `BatchedTableField` (or `TableField` / `QueryTableField`) **plus a lookup member**.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R533** localcontext-guard-predicate-single-source | Backlog | `:15` names `BatchedLookupTableField` as the open edge; `:19` self-corrects but the lead is stale. | **Re-anchor** the `:15` lead to the post-dissolution sibling. |
| **R557** split-query-marker-sweep | Backlog | `:17`'s "consumed (the batched leaves)" lists `BatchedLookupTableField`; `:17` also anchors on the R638-deleted `CatalogBuilder.projectFieldClassification` seam. | **Re-anchor**: drop `BatchedLookupTableField`; repoint the "compile-checked-projection seam" onto the surviving leaf-classification mechanism (`GraphitronSchemaBuilder` / fact store). |

### C.2 `@table`-on-input rejection -> deprecation drift (carried; R566)

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

### C.4 Leaf-merge drift: `Split*` / `Record*` -> `Batched*` (carried; +R717 new)

`SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** to `BatchedTableField`. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38` R305 lineage "collapsed it into `RecordTableField`"; `:107`/`:148`/`:150`/`:284` cite `LookupValuesJoinEmitter`. | **Re-anchor** to `BatchedTableField`; repoint `LookupValuesJoinEmitter` to the render values-join family. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34` name `SplitTableField` / `RecordTableField`; `:24` cites absent `InlineTableFieldEmitter`. | **Re-anchor** the two variant names to `BatchedTableField`; drop the emitter cite. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`'s planned case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R717** routine-carrier-residual-path-correlation | Backlog | **New to the tables this pass (pre-existing drift).** `:51` names "R447's `RecordTableField`, which correlates from a handed record" as a live leaf; `RecordTableField` `grep`=0. A soft forward-looking cross-reference to R447's own (stale) vocabulary. | **Re-anchor** the cross-reference to `BatchedTableField` when R447 (§C.3) is refreshed; travels with that pass. Low priority; the open question (whether the arm generalises) is intact. |

### C.5 `ColumnBackedField` dissolution drift (carried; R508)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | Retired carriers named live across `:570`, `:694`, `:706`, `:727`, `:747-748`, `:798-799`, `:1112`, `:1140`, `:1145`, `:2009`. Part of the one R333 refresh (§C.7). | **Re-anchor** the carrier names to `ColumnBackedField`. Part of the one R333 pass. |

### C.6 `TableInputType` / `resolveInput` removal drift, plus the `UnboundField` reshape (carried; R519 + R515; R589)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R234** jooq-embedded-and-udt-input-backings | Backlog | `:15` cites `TypeBuilder.buildNonTableInputType` (`grep`=0) as the live dispatch. | **Re-anchor** to the current `TypeBuilder` input-classification path (`buildInputType`). |
| **R257** updaterows-walker-sdl-substrate | Backlog | `:17` calls `resolveInput` "the legacy resolver" (gone R515); `:15`/`:19` reach via `TableInputType.inputFields()` (gone R519). | **Re-anchor** both: `resolveInput` -> `admitMutationInputFields`, `TableInputType.inputFields()` -> per-consumer input resolution. |
| **R337** input-nesting-projection-classification | Backlog | `:30` reaches an input object's fields "via `TableInputType.inputFields()`" as the LSP-hover mechanism. | **Re-anchor** the one live mechanism cite to per-consumer input resolution. |
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot in `MutationInputResolver.resolveInput` (gone R515; one surviving hit is a historical comment); `:124` names the pre-R589 `UnboundField` carrier. | **Re-anchor** `:76` to `admitMutationInputFields`, `:124` to `ConditionOwnedField`. |

### C.7 Condition + projection emitter dissolution drift (carried; R552 + R549)

Condition emission is `render/ConditionGlueRenderer`; projection `render/ProjectionUnitRenderer`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh still incomplete; not refreshed this window.** Still at the symbol: 5x `TypeClassGenerator`, 5x `collectRequiredProjection`, `methodgraph`, `LookupValuesJoinEmitter`, 2x `ParentProjectionContainmentCheck`, 1x `TypeConditionsGenerator`, 4x `InlineTableFieldEmitter`, plus the §C.0/§C.5 carriers and §C.9 absent-emitter names. A pure implementation plan whose class names are wrong, and **Ready**, so it bites the next implementer. | **Finish the refresh in one pass** (this row + §C.0 + §C.5 + §C.6 + §C.1 + §C.9): repoint `TypeClassGenerator` / `collectRequiredProjection` -> `ProjectionUnitRenderer` / `ProjectionCommands`; drop `TypeConditionsGenerator` / `ParentProjectionContainmentCheck` / `methodgraph` / `operation()`; re-anchor `LookupValuesJoinEmitter` and the `Operation` seal cites; replace `Inline*Emitter` names and `FkTargetConditionEmitter` -> `FkTargetConditionFilter`. `SplitRowsMethodEmitter` rows stay (live). |
| **R231** emit-text-mapped-enum-fields-as-enum-type | Backlog | `:39` locates "the field-type-emit fork (likely in `TypeClassGenerator` or ...)". | **Re-anchor** the investigation locus to `ProjectionUnitRenderer` / `plan/ProjectionCommands`. |
| **R35** source-orientation-javadocs | Backlog | `:42` enumerates classes for a javadoc sweep, including deleted `TypeClassGenerator` and `TypeConditionsGenerator`. | **Re-anchor** the enumeration: drop the two deleted names. Low priority. |

### C.8 `InputFieldResolution.Unresolved` reshape drift (empty; R585)

R585 reshaped `Unresolved` to `(fieldName, SourceLocation, Rejection)`. Its residue lives in R66, R213 (§B) and R209 (§A). **No item remains in this subsection.**

### C.9 Absent projection/condition `*Emitter` names (carried; render-layer refactor)

A family of per-arm emitter names several items cite as **live** current-state, all **`grep`=0** at
this HEAD. Their work lives in `ProjectionUnitRenderer` / `ProjectionCommands`, `ConditionGlueRenderer`
/ `ConditionCommands`, and `FkTargetConditionFilter`. **No distinct flagged item**; every citer is
listed above.

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
| **R555** deprecate-externalfield-fold-into-service | Ready | Four present-tense cites of `reflectServiceMethod` (`:41`, `:52`, `:103`, `:106`); `:226-227`/`:400`/`:405` describe hover switching on R638-deleted `FieldClassification.Computed` vs `.ServiceBacked`; `:352`/`:491` are javadoc-respell tasks on `FieldClassification.Computed`. | **Re-anchor.** Repoint `reflectServiceMethod` -> `decodeServiceMethod` / `ServiceSignature`; re-anchor Deliverable 4's hover onto `ChildField.ComputedField` (read from the fact store); drop the moot `FieldClassification.Computed` respell tasks. A **Ready** item; refresh before pickup. |
| **R47** service-short-classname-resolution | Backlog | `:15`/`:29`/`:51` name `ServiceCatalog.reflectServiceMethod` and "the three/four reflect* sites" as live edit targets. The load now lives in `decodeServiceMethod`. | **Re-anchor** the class-load site to `decodeServiceMethod` and re-enumerate the reflect* sites. Sequence with R72. (R655, `service-phase-split-residue`, Backlog, tracks the paired 41 test-method renames and is born-current: it names `reflectServiceMethod` only as the dead prefix to strip, not as a live mechanism, so it is correctly unflagged.) |

### C.11 `FilterBinding` reshape drift + R728 junction-chain move (carried; R57; matured earlier)

R57 replaced the `liftedSourceColumns` slot on the two reference carriers with the sealed
`FilterBinding` (`Local(List<ColumnRef> ownTableColumns)` / payload-free `Remote`). The identically
named component on the resolver's `JoinPath` and `Resolved.FkTarget.DirectFk` **survives**. **R728
removed `validateLift`** (`grep`=0), turning the junction-chain rejection into absent-local-columns
reaching a hop-general `EXISTS`. This window's nodeId work (R676, R726) advanced the cluster but
retired neither carrier: `liftedSourceColumns` (5 files) and `FilterBinding` (13 files) re-verify live.

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

### C.15 `intent_class_assignable` deletion drift (carried; R760; +R733 new)

R760 (Done) deleted `intent_class_assignable` (`grep`=0, re-verified this window). Two items name it
in the present tense; both keep valid work and need only a tense fix.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R762** census-stores-members-it-reads-by-name | Backlog | `:78` "Its only consumer is `intent_class_assignable`, which nothing reads and which does not terminate on a real census (R760)." R760 deleted that view, so the present-tense "is" is stale. R762 cites R760 knowingly, so the deletion **strengthens** its argument (`jvm_class_supertype` now has literally zero consumers), it does not falsify it. | **Re-anchor the tense**: "its only consumer *was* `intent_class_assignable`, deleted by R760; `jvm_class_supertype` now has no consumer at all." The census-depth argument is untouched. |
| **R733** build-wall-clock-guardrail | Backlog | **New to the tables this pass (pre-existing drift).** `:347` names "the census's transitive closure view `intent_class_assignable`" present-tense ("does not return on a real census ... nothing reads it"), knowingly citing R760's filed rewrite, but R760 has since **deleted** the view. The R840 sweep touched this file (the `ClassifiedCorpus` -> `CorpusDocuments` rename) but not this line. | **Re-anchor the tense**: the view was deleted by R760, so "nothing reads it" is moot; keep the census-measurement caveat (a later pass measuring the census should not be surprised). Not a slice of this item, as the body already says. Low priority. |

## D. Structural: (0)

Empty of blocking defects. `changelog.md` carries `next-id: R849`, clearing the max present id
(R848). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and the six non-empty
`depends-on:` edges resolve to present files (`carrier-recognizer-conflates-three-scan-verdicts` ->
`dml-carrier-errors-field-blocks-return-derived-table`; `condition-table-parameter-anchor-assignability`
-> `condition-method-overload-selection`; `multi-source-input-validation` ->
`catalog-check-constraint-validation`; `operation-driven-test-corpus` -> `capability-catalog`;
`rover-graphos-integration` -> `oneof-augment-defeated-by-descriptions`;
`validator-integration-execute-coverage` -> `multi-source-input-validation`).

Two **pre-existing, non-blocking** hygiene notes, unchanged this window:

1. Four **legacy** items still lack a `bucket:` key: R242 (`dml-payload-positional-alignment`),
   R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
   R180 (`record-parent-column-read-helper`). Build tolerates the omission; fold a `bucket:` in
   whenever each is next edited.
2. **Numbering gaps** among the item files (each allocated id either folded away inline or
   born-and-closed within a window) are harmless: numbers are never reused, and `next-id` R849 clears
   the max present id R848.

## Cross-cutting observations

1. **A Done item stales the board only by deleting a construct active items name, and this window
   none did.** The five shipped items touched only nodeId-relation-internal, docs, test-tier, or
   corpus-tooling constructs. R840's corpus-scaffolding retirements (`ClassifiedCorpus` and kin) were
   swept through their only three citers in the same commit. Read the premise, not the identifier.

2. **R682 is the window's model-level activity, and it is additive so far.** Its nine in-window
   commits add fact relations (the mutation-payload and condition-membership families) and their
   tests; they retire nothing. R682's bulk retirement, the walk and its taxonomy (`GraphitronSchemaBuilder`,
   `TypeBuilder`, `FieldBuilder`, the sealed classification hierarchies), waits on its terminal step.
   **When that step lands it will be the single largest staling event on record**: the many Backlog
   items that name `FieldBuilder`, `GraphitronSchemaBuilder`, the `intent_`/`rejection_`/`walk_`
   relations, and the sealed leaf zoo will need re-anchoring at once. Nothing to do now; flagged here
   as the next audit's likely headline.

3. **R193 and R213 remain the two overdue subsumption candidates, and neither moved.** R193 asked for
   the sealed parameter classifier R649's `reduceClaims` / `ParamRole` shipped; R213 holds the same
   shape against R585 / R589. Running both re-checks and most likely closing both as subsumed is the
   cheapest board-cleaning available. R209 (§A) is the third mechanical close (fully delivered by
   R585).

4. **The Ready set is where stale prose bites soonest.** It is now R333, R427, R467, R555, R663, R684,
   R724, R730, R838 (nine; R676 and R726 left it for Done, R838 entered). Of the nine, **R333
   (§C.5/§C.7), R427 (§C.0), R555 (§C.10), and R684 (§C.12) carry stale cites**, none refreshed this
   window; R467, R663, R724, R730 are clean, and R838 is born-current. **Refreshing R333, R427, R555,
   and R684 before pickup remains the highest-value hygiene action on the board.**

5. **The new-item intake is born-current.** The thirteen items filed this window (R836 to R848) cite
   no retired symbol as live; R842's `FieldClassification.Conflicted` mention is a deliberate reference
   to vocabulary R682 is dissolving, not a stale claim. Several are follow-ups to the fact-schema,
   corpus, and nodeId work that shipped, and read against the live relations.

6. **The two flag additions this pass came from a full-corpus symbol sweep, not from window drift.**
   R717 and R733 both predate this window and both name a symbol retired in an earlier one; a fresh
   grep of every roadmap item against the retired-symbol set (rather than carrying the prior audit's
   citer tables) surfaced them. The same sweep confirmed the prior tables' other citers are complete
   and cleared two substring false positives (R725's `SingleRecordTableFieldServiceProducerPipelineTest`
   is a live test class; R302's `SingleRecordTableField` is historical lineage prose, not a live claim).

---

_Review date: 2026-08-27._

