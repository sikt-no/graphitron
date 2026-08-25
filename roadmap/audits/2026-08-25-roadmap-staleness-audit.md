# Roadmap staleness audit: 2026-08-25

A point-in-time review of every active roadmap item under [`roadmap/`](../) against the
**current** state of the codebase on `claude/graphitron-rewrite` (HEAD `940e05d`, committed
2026-08-24 22:30, audited 2026-08-25). The goal is to find items whose premise no longer holds:
work already shipped, constructs renamed or removed, dependencies that have since landed or been
discarded, or specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a subdirectory so the
roadmap-tool (which scans `roadmap/*.md` non-recursively and requires `id:` front-matter on each)
ignores it, and it is Markdown so the `check-adoc-tables` build step (which scans `.adoc` only)
leaves it alone.

This audit supersedes the `2026-08-21` staleness audit, which has been deleted; only the latest
**staleness** audit is retained. The other fifteen files in this directory are **not** staleness
audits and are left in place (deleting them would strand lineage that shipped items and active
items cite by path, and they are provenance I did not author): `2026-06-16-source-operation-target-reframe.md`,
`2026-06-30-release-planning.md`, `2026-07-04-r222-r333-conformance-analysis.md`,
`2026-07-26-fcis-command-layer-distance.md`, `2026-08-05-fact-base-h2-spike.md`,
`2026-08-05-h2-functions-jooq-spike.md`, `2026-08-06-demand-exemption-census.md`,
`2026-08-06-directive-consumer-census.md`, `2026-08-06-fact-base-impact-sweep.md`,
`2026-08-06-graphql-java-diff-spike.md`, `2026-08-06-r222-lineage.md`,
`2026-08-06-structural-classifier-census.md`, `2026-08-19-github-issue-roadmap-linkage.md`,
`2026-08-20-nodeid-relation-impact-sweep.md` (the `@nodeId`-relation architecture-drift companion
whose findings this audit takes as read for the nodeId cluster), and
`classification-test-dsl-inventory.md`.

**Window and method caveat.** The prior audit's HEAD `e9dc149` (2026-08-20) does not resolve on
this branch: the working checkout is a shallow graft rooted at `1cdba54` (2026-08-24 13:40), so no
commit range spans the audit window and a git-level diff of `roadmap/` is unavailable. The window
is therefore reconstructed from the board itself (`next-id` advanced R769 to R828) and from the
changelog, and **every flag below was re-verified by a fresh `grep` of the main sources at this
HEAD**, not carried on the prior audit's word. Where the prior audit's figures were confirmed
against the current tree they are restated; where the window moved them they are updated.

## Headline: 22 items shipped, none staled an active premise; R728 matured one flag and was already correctly anticipated by a Ready item

The window shipped roughly **22 items** (`next-id` R769 to R828, minus one discard and the still-open
new items). Their subject matter is overwhelmingly **documentation** (R770, R771, R791, R800, R803,
R804, R805), **dev-loop / build / workflow infrastructure** (R769, R772, R773, R775, R785, R787),
**LSP store-read diagnostics** (R792, R793, R794, R796, R799), and **fact-schema performance**
(R815, R819). Each retired only doc, test-internal, storage-internal, or LSP-diagnostic-internal
constructs. **None staled an active model or emit premise**, so the prior audits' recurring lesson
holds a fourth time: a Done item stales the board only by deleting a construct active items name.

- **R728 (`@nodeId` encode/decode become fact-store relations, Done)** is the one shipped item that
  touched the model vocabulary the flags care about. It turned the junction-chain rejection into
  absent-local-columns reaching a hop-general `EXISTS`, so **`validateLift` is now `grep`=0**. This
  **matures R135** (§C.11): its 2026-08-20 addendum framed the carve-out re-anchor as pending "once
  R728 stage 3 lands"; R728 has landed, so the re-anchor is due now and the out-of-scope carve-out
  is definitively stale, not conditionally so. R728's re-anchor target for **R34** (§B),
  `intent_node_id_instruction.basis`, is live (a fact-store relation, materialized this window by
  R826), so R34's flag holds unchanged.
- **R673 (`nodeid-arg-dispatches-on-typeid`, Ready)** correctly **anticipated** R728: its body states
  "R728 stage 3 stops `validateLift` rejecting ... Checked against that, the conclusion survives," and
  its `liftedSourceColumns` cite is the surviving resolver component
  (`Resolved.FkTarget.DirectFk.liftedSourceColumns()`), not the retired input-field carrier. It is
  **not a staleness flag**; it is the one item that priced R728 in before it landed.

**No flag enters and no flag leaves this window.** The ~36 new live items (R774 to R827) are
born-current: none cites a classic retired symbol (`TypeClassGenerator`, `LspSchemaSnapshot`,
`CatalogFacts`, `reflectServiceMethod`, `RecordTableField`, `planSlug`, `intent_class_assignable`,
`BatchedLookupTableField`). R762 (§C.15, entered last window) still holds: `intent_class_assignable`
remains `grep`=0. **R824 was discarded in-window** (folded back into R733; its file is gone and no id
`R824` resolves).

Net: **1 §A / 12 §B / 29 §C / 0 §D**, flag total **42**, unchanged in count and composition from the
prior audit. The one substantive edit is **R135's recommended action**, which matures on R728's
landing. Every other carried flag holds at its prior anchors, and every long-standing retired symbol
re-verifies `grep`=0 at this HEAD.

## Changes since the 2026-08-21 audit

Measured from the board the prior audit recorded (266 item files, `next-id` R769) to this HEAD (293
item files, `next-id` R828).

**Items that reached Done in the window, and what each did to the symbol set:**

- **R728 (`@nodeId` encode/decode as relations, Done):** the junction-chain rejection became a
  filter; `validateLift` retired (`grep`=0). Created `intent_node_id_instruction` /
  `intent_node_id_decode_defect` in the fact store. Did **not** retire the reflection reader
  `JooqCatalog.nodeIdMetadata` (16 refs, live). Matures R135; strengthens the R34 re-anchor target.
- **R815 (`materialize the fact-schema targets`, Done) and R819 (`carrier read-cost registrations`,
  Done):** fact-schema performance work (indexing, materialization, read-cost registrations). Storage-
  internal; no model or emit construct retired.
- **R792 / R793 / R794 / R796 / R799 (LSP store-read diagnostics, Done):** the out-of-budget warning
  now names the read, the diagnostics drain moved off the request thread, detach stopped spilling
  stack traces. This is the **store-read / `ReadBudget`** diagnostic surface, distinct from the
  **compile-diagnostic** surface R430 (§B) names, so it does not touch R430's premise.
- **R769 / R772 / R773 / R775 / R785 / R787 (dev-loop, build, workflow, `ReadBudget` infra, Done):**
  test-suite, build-watcher, and session-flow constructs. None named by an active item.
- **R770 / R771 / R791 / R800 / R803 / R804 / R805 (documentation, Done):** `fact-model.adoc`,
  `naming-the-row.adoc`, the schema-reference family page, and a new `principles/` docs section.

**Discards:** **R824** (`reactor-wall-clock-budget`), folded back into R733.

**Items filed this window that remain open (R774 to R827):** ~36 live files spanning nodeId
materialization (R826, R827), LSP find-references / pull-diagnostics (R818, R797), diagnostics and
capture residue, docs, and a cluster of concurrency-flake bug items (R808, R809, R822, R823, R825).
All read born-current against the live relations and are correctly **not** flagged.

**Transitions of note:** R682 (`planners-read-facts-emitters-read-commands`) remains In Progress
(slice one measured and landed); R705 (`condition-join-hops-in-reference-filter-paths`) and R784
(`straddling-reference-update-partition`) reached In Review; R826 (`node-id-instruction-materialization`)
reached In Review.

**Board accounting.** **293** item files today (measured, excluding `README.md` and `changelog.md`),
up from 266. Status distribution: **252 Backlog, 25 Spec, 11 Ready, 2 In Progress, 3 In Review, 0
Done**. Tombstone-free (`grep` for `status: Done` in `roadmap/*.md` = 0). No duplicate `id:`;
`changelog.md` carries `next-id: R828`, clearing the max present id (R827). A `depends-on:` sweep
resolves all **six** non-empty edges to present files. The only structural nits are the same four
**legacy** items still missing a `bucket:` key (§D), all pre-dating this window.

## Scope and method

All **293** `R<n>` item files were reviewed, and every flagged item's file was confirmed present on
this branch at the status recorded below (no flagged item shipped or was discarded this window).
Every driving symbol below was re-checked against a fresh `grep` of the main sources (`graphitron`,
`graphitron-mcp`, `graphitron-lsp`, `graphitron-model`, `graphitron-maven-plugin`,
`graphitron-fixtures-codegen`), not carried on the prior audit's word.

**This window's retirement, re-verified `grep`=0 in main real code at this HEAD:** `validateLift`
(R728). Its successor path is live: the junction chain now lowers to `FilterBinding.Local` reaching a
hop-general `EXISTS`.

**Long-standing retirements, re-verified still retired at this HEAD (`grep`=0 in main real code):**
`reflectServiceMethod`, `looksLikeSourcesShape`, `RowsMethodBody`, `RowsMethodSkeleton`,
`QueryConditionsGenerator`, `TypeClassGenerator`, `TypeConditionsGenerator`, the `SourceKey.Reader`
interface (one surviving hit is a javadoc mention of the retired symbol, not a live reference),
`planSlug`, `CompileDependencyGraphBuilder`, `FkTargetConditionEmitter`, `MutationConditions`,
`buildNonTableInputType`, `BatchedLookupTableField`, `InlineTableFieldEmitter`, `LookupValuesJoinEmitter`,
`RecordTableField`, `SplitTableField`, `SplitLookupTableField`, `ParentProjectionContainmentCheck`,
`collectRequiredProjection`, `LspSchemaSnapshot`, `CatalogFacts`, `intent_class_assignable`, the
legacy `MutationInputResolver.resolveInput` (the six surviving `resolveInput` hits are
`RecordBindingResolver.resolveInput` / `TypeBuilder.resolveInputFields`, a different method family),
and `AUTHOR_ERROR` `grep`=0 in `FieldRegistry.java` (the three surviving `AUTHOR_ERROR` files are the
general `RejectionKind` enum in `RejectionKind` / `BuildContext` / `FieldBuilder`, not the FieldRegistry
default arm). **Still live, not stale:** `FilterBinding` (13 files), the resolver's
`Resolved.FkTarget.DirectFk.liftedSourceColumns()`, `ChildField.ColumnBackedReferenceField`, and the
reflection reader `JooqCatalog.nodeIdMetadata` (16 refs; the R615 / R273 / R34 / R588 watch chain still
holds it).

**R430 re-confirmed (still §B, not discardable).** `Workspace.compileDiagnostics()` /
`setCompileDiagnostics` / `CompileDiagnostic` are `grep`=0 in `graphitron-lsp` main, and the compile-
diagnostic stream still lives in `graphitron` core (`CompileFacts` / `IncrementalCompileEngine` /
`CompileDiagnostic`, 10 files). This window's LSP diagnostics work (R792 to R799) reshaped the
**store-read / `ReadBudget`** diagnostic surface, a different family, so R430's premise-break is
unchanged and its re-anchor target survives.

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried; still Backlog, still fully delivered.** The entire deliverable ("remove the `RejectionKind.AUTHOR_ERROR` default arm and emit `RejectionKind.of(rejection)` consistently with `traceOutput`") is shipped by **R585**. Re-verified this window: `AUTHOR_ERROR` `grep`=0 in `FieldRegistry.java`. Nothing remains to do; not touched this window. | **Discard**, recording R585 as the delivery vehicle. |

R520 (`table-on-input-removal-housekeeping`) stays a coherent deliberately-deferred docs tail, not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (12)

All twelve carry forward. **R135 (§C.11, cross-listed here for its maturation) and R34 both sit on the
nodeId cluster R728 moved this window;** the other ten were not touched and re-verified at the symbol
with the premise-target still `grep`=0. **R193 and R213 remain the two overdue subsumption candidates**
(R649 / R585 / R589 shipped what they scoped); running both re-checks, most likely closing both as
subsumed, is the cheapest board-cleaning available.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R193** service-param-classification-sealed-hierarchy | Backlog | **R649 shipped the deliverable.** `reflectServiceMethod` / `looksLikeSourcesShape` `grep`=0; `reduceClaims` mints one sealed `ParamRole` per parameter. | **Re-derive the residue against shipped R649; most likely close as subsumed**, recording R649 as the delivery vehicle if nothing survives. |
| **R72** slim-servicecatalog-to-lookup | Backlog | **Premise materially changed by R649.** R649 dissolved `reflectServiceMethod` (`grep`=0) into `decodeServiceMethod` / `reduceClaims` / `bindServiceMethod`; `reflectTableMethod` / `reflectExternalField` survive. | **Re-spec** against the post-R649 split; drop every stale `ServiceCatalog.java:NNN` line cite. |
| **R213** input-field-rejection-attribution | Backlog | **Escalated, still unactioned.** `InputFieldResolution.Unresolved` now carries a `SourceLocation`; R589's occurrence-path derivation (Done) delivers the attribution split. | **Re-derive the residue against shipped R585 + R589; most likely close as subsumed.** |
| **R66** rejection-string-carrier-widening | Backlog | Phase **A2** was verbatim what **R585** shipped (`Unresolved` carries `Rejection rejection`, not `reason:String`). Phases A1, A3, B1, B2 survive. | **Re-spec:** strike A2 as delivered; re-baseline the four surviving phases; fix the `:25-30` anchors. |
| **R34** nodeid-migration-quickfix | Backlog | Carried, self-contradictory. The "shim facts" driver names three WARN sites deleted by R473; the re-anchor addendum (dated 2026-08-20) confirms the driver is void and names `intent_node_id_instruction.basis` as the re-derivation target, **now materialized live by R826.** Body still leads with the void driver. | **Re-spec** onto `intent_node_id_instruction` (`basis` + `node_type_name` + location); retitle off "shim facts". The addendum already records this and its target is now a materialized relation. |
| **R122** compound-entity-mutations | Backlog | "Design space narrows under R222" leans on R222's discarded recursive `InputUsage` model (R222 left the board 2026-08-06). `ChildField.TableTargetField` (added by this item) is live. | **Re-spec the "narrows under R222" section**; re-express against the captured `intent_` / `applied_` relations. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Central target `CompileDependencyGraphBuilder.addFieldEdges` deleted (`grep`=0); the body's own dissolution condition has occurred. Cites absent `FkTargetConditionEmitter.emitTerm` and phantom `MutationConditions` as live. | **Re-derive against the plan-projected recompile graph.** If closed, discard, else re-spec; repoint `FkTargetConditionEmitter.emitTerm` -> `FkTargetConditionFilter` and drop `MutationConditions`. |
| **R545** model-free-of-emit-vocabulary | Backlog | `RowsMethodBody` / `RowsMethodSkeleton` deleted (diagnosis + second deliverable gone); first deliverable survives (`ClassName` / `TypeName` model-pervasive). The `:18` counter-argument names R638-deleted `FieldClassification` / `TypeClassification`. | **Re-spec.** Drop the `RowsMethodBody` diagnosis; keep the first deliverable; update the `:18` counter-argument to the current `rewrite/catalog/` contents. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Both named edit-target generators deleted: `QueryConditionsGenerator` (R552), `TypeClassGenerator` (R549). Successors `ConditionGlueRenderer` / `ProjectionUnitRenderer` live. Names absent `Inline*Emitter` host files. | **Re-derive against the new `render/` layer**; drop every dead cite. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | R431 (Done) deleted the target surface: `SourceKey` is a plain `record` with no `Reader`. Successors `KeyLift` / `LifterRef` / sealed `Wrap` live; the "R431 ... plans to" tense is wrong (R431 is Done). | **Re-spec the current-state / approach section** against the decomposed model; fix the tense. |
| **R76** participant-fieldsjoin-helpers | Backlog | R650 converted cross-table participant fields to capped correlated subselects; the surviving `step.leftJoin(...)` is the joined-detail join only, so the cross-table `$fieldsJoin` framing is obsolete. | **Re-spec** onto the joined-detail `LEFT JOIN`, or discard if R650's subselect conversion removed the motivating duplication. |
| **R430** lsp-compile-diagnostics-publish | Backlog | **Premise broken by R638 (Done).** `CompileDiagnostic` / `Workspace.compileDiagnostics()` / `setCompileDiagnostics` are `grep`=0 in `graphitron-lsp` main; R638 moved LSP diagnostics onto the capture cadence. `publishDiagnostics` survives. The compile-diagnostic stream still lives in `graphitron` core (`CompileFacts` / `IncrementalCompileEngine` / `CompileDiagnostic`); this window's LSP diagnostics work touched the distinct store-read surface, not this one. | **Re-derive current state against the fact-store LSP**; re-anchor the publish trigger onto the surviving `graphitron`-core compile stream and keep the publish-against-generated-URI goal. Discard only if that stream is later retired. |

## C. Outdated: update references only (work valid, refs stale) (29)

Substance intact; names and line numbers drifted. Every long-standing driving symbol re-verified
still `grep`=0. **R135 (§C.11) matures this window on R728's landing; the rest are untouched.** R762
(§C.15) holds.

### C.0 `Operation` seal fully retired (carried; R563 slice 7)

`Operation` and every `Operation.<Arm>` reference are `grep`=0. Successor: `OperationMember`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R427** relevance-ranked-search | Ready | `:335` "`operation()` stays `Fetch`"; `:339`/`:1162` cite `Operation.Facet` as a live type (re-confirmed still present, last-updated 2026-08-06). | **Re-anchor** onto the member-derived summary fold; repoint `Operation.Facet` -> `OperationMember.Facet`. A **Ready** item; refresh before pickup. |
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

### C.4 Leaf-merge drift: `Split*` / `Record*` -> `Batched*` (carried)

`SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** to `BatchedTableField`. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38` R305 lineage "collapsed it into `RecordTableField`"; `:107`/`:148`/`:150`/`:284` cite `LookupValuesJoinEmitter`. | **Re-anchor** to `BatchedTableField`; repoint `LookupValuesJoinEmitter` to the render values-join family. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34` name `SplitTableField` / `RecordTableField`; `:24` cites absent `InlineTableFieldEmitter`. | **Re-anchor** the two variant names to `BatchedTableField`; drop the emitter cite. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`'s planned case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |

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
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh still incomplete; not touched this window (last-updated 2026-08-11).** Still at the symbol: 5x `TypeClassGenerator`, 5x `collectRequiredProjection`, `methodgraph`, `LookupValuesJoinEmitter`, 2x `ParentProjectionContainmentCheck`, 1x `TypeConditionsGenerator`, 4x `InlineTableFieldEmitter`, plus the §C.0/§C.5 carriers and §C.9 absent-emitter names. A pure implementation plan whose class names are wrong, and **Ready**, so it bites the next implementer. | **Finish the refresh in one pass** (this row + §C.0 + §C.5 + §C.6 + §C.1 + §C.9): repoint `TypeClassGenerator` / `collectRequiredProjection` -> `ProjectionUnitRenderer` / `ProjectionCommands`; drop `TypeConditionsGenerator` / `ParentProjectionContainmentCheck` / `methodgraph` / `operation()`; re-anchor `LookupValuesJoinEmitter` and the `Operation` seal cites; replace `Inline*Emitter` names and `FkTargetConditionEmitter` -> `FkTargetConditionFilter`. `SplitRowsMethodEmitter` rows stay (live). |
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
| **R555** deprecate-externalfield-fold-into-service | Ready | Four present-tense cites of `reflectServiceMethod` (`:41`, `:52`, `:103`, `:106`, re-confirmed present, last-updated 2026-08-06); `:226-227`/`:400`/`:405` describe hover switching on R638-deleted `FieldClassification.Computed` vs `.ServiceBacked`; `:352`/`:491` are javadoc-respell tasks on `FieldClassification.Computed`. | **Re-anchor.** Repoint `reflectServiceMethod` -> `decodeServiceMethod` / `ServiceSignature`; re-anchor Deliverable 4's hover onto `ChildField.ComputedField` (read from the fact store); drop the moot `FieldClassification.Computed` respell tasks. A **Ready** item; refresh before pickup. |
| **R47** service-short-classname-resolution | Backlog | `:15`/`:29`/`:51` name `ServiceCatalog.reflectServiceMethod` and "the three/four reflect* sites" as live edit targets. The load now lives in `decodeServiceMethod`. | **Re-anchor** the class-load site to `decodeServiceMethod` and re-enumerate the reflect* sites. Sequence with R72. |

### C.11 `FilterBinding` reshape drift + R728 junction-chain move (carried; R57; matured this window)

R57 replaced the `liftedSourceColumns` slot on the two reference carriers with the sealed
`FilterBinding` (`Local(List<ColumnRef> ownTableColumns)` / payload-free `Remote`). The identically
named component on the resolver's `JoinPath` and `Resolved.FkTarget.DirectFk` **survives**. **R728
(Done this window) removed `validateLift`** (`grep`=0), turning the junction-chain rejection into
absent-local-columns reaching a hop-general `EXISTS`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R135** multi-hop-nodeid-fk-permutation-test | Backlog | `:17` pins the composite `InputField.ColumnBackedReferenceField.liftedSourceColumns()` ending in `[k1, k2]`; `:23` the `BodyParam.{RowEq,RowIn}` positional consumption. Carrier gone; tuple now `FilterBinding.Local`. The dated addendum (2026-08-20) records both this re-anchor and the `validateLift` carve-out. **R728 has now landed** (`validateLift` `grep`=0), so the second re-anchor is due now, not pending. Body still carries the stale cites. | **Re-anchor** the `:17`/`:23` carriers onto `FilterBinding.Local`, and **restate the out-of-scope carve-out now** (it declined "relax the per-hop `validateLift` predicate", but R728 already removed that predicate, so the carve-out no longer describes a live alternative). The test-plan goal is untouched. |

### C.12 `CatalogFacts` / mcp-tool-surface / `LspSchemaSnapshot` drift (carried; R642 + R638)

R642 cut graphitron-mcp off the generator (`CatalogFacts` `grep`=0, `edges` tool dropped). R638 deleted
`LspSchemaSnapshot` and `FieldClassification` (both `grep`=0). Both items keep their work valid.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R684** consumers-share-relations-not-queries | Ready | `:94-96` asserts `graphitron-lsp` and `graphitron-mcp` both import `LspSchemaSnapshot`, `CatalogFacts`, `CompletionData`, `FieldClassification`, `TypeBackingShape` today (re-confirmed present, last-updated 2026-08-17). Three of five are dead; the mcp half is doubly false post-R642/R638. Only `CompletionData` / `TypeBackingShape` survive, in `graphitron-lsp` only. | **Re-anchor** the example to `graphitron-lsp` only, keep the two surviving symbols, drop `CatalogFacts` / `LspSchemaSnapshot` / `FieldClassification`. A **Ready** item; refresh before pickup. |
| **R594** mcp-snapshot-axis-key-naming | Backlog | `:16-19` premise names "four MCP tools" (R642 dropped `edges`) and "the same exhaustive switch over the `LspSchemaSnapshot` permits" (R638 deleted the type). Fix surface survives: `McpWire.writeSnapshotAxes`, `snapshotAvailability`, `SchemaView`, `GraphitronMcpServer.statusResult` all live. | **Re-anchor**, not re-spec: drop `edges`, re-count the tools, repoint the switch-source from the `LspSchemaSnapshot` permits onto the fact-store availability/freshness enum. |

### C.13 Routine read-surface deferral removal drift (carried; R704)

R704 removed the `@routine` read-surface carve-outs and built the pagination arm.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R662** routine-chain-ordering-spans-nodes | Backlog | The "Notes for whoever picks this up" bullets state as current fact that "`@orderBy` is deferred on routine-backed fields today" and (`:78`) "`@asConnection` over a routine chain is rejected or deferred today"; R704 removed both. `:84` names R704's deleted item-file path (dangling xref). | **Re-anchor** the two Notes bullets onto R704's shipped read surface; repoint the dangling `routine-composition-surface-from-facts.md` path onto R704's changelog id. Multi-node-ordering premise survives. |

### C.14 `LspSchemaSnapshot` / `FieldClassification` removal drift (carried; R638)

R638 deleted `LspSchemaSnapshot` and `FieldClassification`. R714 left this subsection at the prior
audit (discarded into R743). Only R236 remains.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R236** validator-reference-candidate-hint-terminal-table | Backlog | `:27` names the terminal table on the projected `FieldClassification.{ColumnReference,CompositeColumnReference}.tableName()`, and `:31` proposes routing candidate-hint dispatch "through the same `FieldClassification` projection". That projection is deleted (`grep`=0), so design option (a) is no longer buildable. The underlying `BuildContext` candidate-hint bug still stands, and option (b) survives. | **Re-anchor**: repoint the terminal-table source onto the surviving classifier, or adopt option (b). Drop option (a). |

### C.15 `intent_class_assignable` deletion drift (carried; R760)

R760 (Done) deleted `intent_class_assignable` (`grep`=0, re-verified this window). One item names it
in the present tense; its work is valid and needs only a tense fix.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R762** census-stores-members-it-reads-by-name | Backlog | `:78` "Its only consumer is `intent_class_assignable`, which nothing reads and which does not terminate on a real census (R760)." R760 deleted that view, so the present-tense "is" is stale. R762 cites R760 knowingly, so the deletion **strengthens** its argument (`jvm_class_supertype` now has literally zero consumers), it does not falsify it. | **Re-anchor the tense**: "its only consumer *was* `intent_class_assignable`, deleted by R760; `jvm_class_supertype` now has no consumer at all." The census-depth argument is untouched. |

## D. Structural: (0)

Empty of blocking defects. `changelog.md` carries `next-id: R828`, clearing the max present id
(R827). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and the six non-empty
`depends-on:` edges resolve to present files.

Two **pre-existing, non-blocking** hygiene notes:

1. Four **legacy** items still lack a `bucket:` key: R242 (`dml-payload-positional-alignment`),
   R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
   R180 (`record-parent-column-read-helper`). Build tolerates the omission; fold a `bucket:` in
   whenever each is next edited.
2. **Numbering gaps** among the item files (each allocated id either folded away inline or born-and-
   closed within a window, including this window's R824 discard) are harmless: numbers are never
   reused, and `next-id` R828 clears the max present id R827.

## Cross-cutting observations

1. **A Done item stales the board only by deleting a construct active items name, and this window
   none did.** The 22 shipped items retired doc, test-internal, storage-internal, or
   LSP-diagnostic-internal constructs. R728 removed `validateLift`, but the only item leaning on it
   (R135) had already recorded the re-anchor, and R673 explicitly priced R728 in before it landed.
   Read the premise, not the identifier.

2. **R728's landing is the window's one model-level event, and it broke no new premise.** It matured
   R135 (the `validateLift` carve-out is now definitively stale) and confirmed R34's re-anchor target
   (`intent_node_id_instruction`, now materialized by R826). It did **not** retire the reflection
   reader `JooqCatalog.nodeIdMetadata` (16 refs, live), so the R615 / R273 / R34 / R588 watch chain
   still holds: the reader that rewires readers off reflection is still pending.

3. **R193 and R213 remain the two overdue subsumption candidates, and neither moved.** R193 asked for
   the sealed parameter classifier R649's `reduceClaims` / `ParamRole` shipped; R213 holds the same
   shape against R585 / R589. Running both re-checks and most likely closing both as subsumed is the
   cheapest board-cleaning available. R209 (§A) is the third mechanical close (fully delivered by R585).

4. **The Ready set turned over again and is where stale prose bites soonest.** It is now R730, R684,
   R333, R555, R818, R673, R749, R427, R663, R724, R467 (eleven). Of these, **R333 (§C.5/§C.7), R427
   (§C.0), R555 (§C.10), and R684 (§C.12) carry stale cites** (all re-confirmed present, none refreshed
   this window). **R673** is clean and post-R728-verified; R730, R749, R818, R663, R724, R467 are clean.
   **Refreshing R333, R427, R555, and R684 before pickup remains the highest-value hygiene action on
   the board.**

5. **The new-item intake is born-current.** The ~36 items filed this window (R774 to R827) cite no
   retired symbol, and the nodeId-cluster newcomers (R826, R827) read against the live materialized
   `intent_node_id_instruction` relation rather than the reflection reader. R824 was discarded into
   R733 in-window and its file is correctly gone.

---

_Review date: 2026-08-25._
