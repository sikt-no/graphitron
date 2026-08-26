# Roadmap staleness audit: 2026-08-26

A point-in-time review of every active roadmap item under [`roadmap/`](../) against the
**current** state of the codebase on `claude/graphitron-rewrite` (HEAD `b739f5e`, committed
2026-08-25 22:00, audited 2026-08-26). The goal is to find items whose premise no longer holds:
work already shipped, constructs renamed or removed, dependencies that have since landed or been
discarded, or specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a subdirectory so the
roadmap-tool (which scans `roadmap/*.md` non-recursively and requires `id:` front-matter on each)
ignores it, and it is Markdown so the `check-adoc-tables` build step (which scans `.adoc` only)
leaves it alone.

This audit supersedes the `2026-08-25` staleness audit, which has been deleted; only the latest
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

**Window and method caveat.** The prior audit's HEAD `940e05d` (2026-08-24 22:30) does not resolve on
this branch: the working checkout is a shallow graft rooted at `8d18526` (2026-08-25 10:37), so no
commit range spans the audit window and a git-level diff of `roadmap/` is unavailable. The window
is therefore reconstructed from the board itself (`next-id` advanced R828 to R836) and from the
changelog, and **every flag below was re-verified by a fresh `grep` of the main sources at this
HEAD**, not carried on the prior audit's word. Where the prior audit's figures were confirmed
against the current tree they are restated; where the window moved them they are updated. The window
is a short one, roughly a single day (the visible commits all fall on 2026-08-25), so the delta is
correspondingly small.

## Headline: a quiet one-day window; 7 items shipped, none staled an active premise, and the flag set is unchanged in count and composition

The window shipped **7 items** (`next-id` R828 to R836; three of them cleared the In Review set the
prior audit recorded, three cleared its Ready set, and one was filed and shipped inside the window).
Their subject matter is **nodeId decode / materialization** (R673, R784, R826), **condition-join
lowering** (R705), an **LSP find-references surface** (R818), a **store census fold** (R749), and a
**skill-document** update (R828). Each touched only nodeId-relation-internal, condition-render-layer,
LSP-feature, storage-census, or skill-doc constructs. **None staled an active model or emit premise**,
so the prior audits' recurring lesson holds again: a Done item stales the board only by deleting a
construct active items name, and this window none did.

- **The nodeId cluster (R673, R784, R826) shipped without retiring a watched symbol.** R784 partitions
  a straddling cross-table `@nodeId` reference per column on UPDATE, but it **reads** the lifted
  foreign-key columns rather than retiring the carrier: `liftedSourceColumns` is still live (5 files)
  and `FilterBinding` still live (13 files), so R135's re-anchor targets (§C.11) survive untouched.
  R826 makes `intent_node_id_instruction` a **materialized** target rather than a view; that
  **confirms and firms R34's re-anchor target** (§B), which the prior audit had recorded as
  materialized by R826-then-In-Review and is now materialized by R826-Done. R673 (the item the prior
  audit named as pricing R728 in before it landed) reached Done cleanly on its surviving
  `Resolved.FkTarget.DirectFk.liftedSourceColumns()` cite. The reflection reader
  `JooqCatalog.nodeIdMetadata` was **not** retired (4 files live), so the R615 / R273 / R34 / R588
  watch chain still holds.
- **R135 (§C.11) was already fully matured last window on R728's landing, and nothing this window
  moves it further.** `validateLift` remains `grep`=0; the out-of-scope carve-out is still
  definitively stale and the re-anchor still due. Its recommended action is carried verbatim.

**No flag enters and no flag leaves this window.** The 7 new live items (R829 to R835) are
born-current: none cites a classic retired symbol (`TypeClassGenerator`, `LspSchemaSnapshot`,
`CatalogFacts`, `reflectServiceMethod`, `RecordTableField`, `planSlug`, `intent_class_assignable`,
`BatchedLookupTableField`, `validateLift`, `FieldClassification`). R762 (§C.15) still holds:
`intent_class_assignable` remains `grep`=0. **No item was discarded in-window** (the R824 discard the
prior audit recorded is confirmed here inside R749's Done entry, but that discard belongs to the
prior window; its file remains gone and no id `R824` resolves).

Net: **1 §A / 12 §B / 29 §C / 0 §D**, flag total **42**, unchanged in count and composition from the
prior audit. **No flag's recommended action changed this window.** Every carried flag holds at its
prior anchors, every flagged item is present at the status recorded below, and every long-standing
retired symbol re-verifies `grep`=0 at this HEAD.

## Changes since the 2026-08-25 audit

Measured from the board the prior audit recorded (293 item files, `next-id` R828) to this HEAD (294
item files, `next-id` R836).

**Items that reached Done in the window, and what each did to the symbol set:**

- **R826 (`node-id-instruction-materialization`, Done):** `intent_node_id_instruction` becomes a
  materialized target rather than a view, so the rule is evaluated once per capture instead of four
  times per read. The canonical name every reader spells is unchanged (the item declares no retired
  vocabulary), so no reader was rewired. **Confirms R34's re-anchor target** (`intent_node_id_instruction.basis`),
  now a shipped materialized relation. This item was In Review at the prior audit's boundary (its Done
  entry cites `940e05d`, the prior audit's HEAD).
- **R784 (`straddling-reference-update-partition`, Done):** a cross-table `@nodeId` reference whose
  lifted foreign-key columns straddle the matched key now partitions per column on UPDATE instead of
  rejecting. It **reads** the lifted columns; it did **not** retire `liftedSourceColumns` (5 files) or
  `FilterBinding` (13 files), so R135's re-anchor targets survive. In Review at the prior audit.
- **R673 (`nodeid-arg-dispatches-on-typeid`, Done):** a by-id lookup returning a multitable interface
  or union now classifies clean and accepts ids of one implementation per branch. The item the prior
  audit named as pricing R728 in before it landed; its surviving
  `Resolved.FkTarget.DirectFk.liftedSourceColumns()` cite carried it to Done. Ready at the prior audit.
- **R705 (`condition-join-hops-in-reference-filter-paths`, Done):** a `{condition:}` hop is now legal
  in a `@reference` filter path on both filter surfaces. Additive over the live
  `FkTargetConditionFilter` / condition-render layer; retired nothing an active item names. In Review
  at the prior audit.
- **R818 (LSP `textDocument/references`, Done):** the language server answers find-references. An LSP
  **feature** surface, distinct from the **compile-diagnostic** surface R430 (§B) names, so it does not
  touch R430's premise. Ready at the prior audit.
- **R749 (store census fold, Done):** an alias namespace and type-scoped fold plus a census and the
  `fan_target` fixture. Storage-census-internal; retired no model or emit construct, and did not touch
  `jvm_class_supertype` / `intent_class_assignable` (both still `grep`=0 where the audit expects). Its
  Done commit also reverted an unnumbered routine-write hop-pairing change and re-recorded the prior
  window's R824 discard. Ready at the prior audit.
- **R828 (`store-performance` skill, H2 instruments, Done):** replaces the skill's hand-rolled timing
  with H2's own `QUERY_STATISTICS`. A skill-document change; no code symbol touched. Filed and shipped
  inside this window.

**Discards:** none this window. (R749's Done entry re-records the prior window's **R824** discard, which
remains gone.)

**Items filed this window that remain open (R829 to R835):** 7 live files spanning payload-UPDATE
straddle coverage (R829), fact-schema performance (R830, R831, R832 at Spec), an execution-tier
parallel-run failure (R833), a root `@service` table-return key-refetch gap (R834 at Spec), and the
node-id decode read-cost follow-up (R835). All read born-current against the live relations and are
correctly **not** flagged.

**Transitions of note:** R682 (`planners-read-facts-emitters-read-commands`) remains In Progress
(eighth increment landed); R814 (`architecture-docs-describe-the-destination`) is now In Progress;
R726 (`nodeid-bare-inference-per-participant-divergence`) reached Ready. The three items the prior
audit recorded as In Review (R705, R784, R826) all reached Done, and the In Review set is now empty.

**Board accounting.** **294** item files today (measured, excluding `README.md` and `changelog.md`),
up from 293. Status distribution: **256 Backlog, 26 Spec, 10 Ready, 2 In Progress, 0 In Review, 0
Done**. Tombstone-free (`grep` for `status: Done` in `roadmap/*.md` = 0). No duplicate `id:`;
`changelog.md` carries `next-id: R836`, clearing the max present id (R835). A `depends-on:` sweep
resolves all **six** non-empty edges to present files. The only structural nits are the same four
**legacy** items still missing a `bucket:` key (§D), all pre-dating this window.

## Scope and method

All **294** `R<n>` item files were reviewed, and every flagged item's file was confirmed present on
this branch at the status recorded below (no flagged item shipped or was discarded this window).
Every driving symbol below was re-checked against a fresh `grep` of the main sources (`graphitron`,
`graphitron-mcp`, `graphitron-lsp`, `graphitron-model`, `graphitron-maven-plugin`,
`graphitron-fixtures-codegen`), not carried on the prior audit's word.

**No retirement this window.** The 7 items that shipped (R673, R705, R749, R784, R818, R826, R828)
retired no symbol any active item names: the nodeId trio read the lift carriers rather than removing
them, R705 was additive over the live condition-render layer, R818 was an LSP feature, R749 was
storage-census-internal, and R828 touched only a skill document. `validateLift` (retired last window
by R728) re-verifies `grep`=0, its successor path still live: the junction chain lowers to
`FilterBinding.Local` reaching a hop-general `EXISTS`.

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
`CompileDiagnostic`, 10 files). This window's LSP work landed a find-references feature (R818), a
surface distinct from both the compile-diagnostic and the store-read diagnostic families, so R430's
premise-break is unchanged and its re-anchor target survives.

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried; still Backlog, still fully delivered.** The entire deliverable ("remove the `RejectionKind.AUTHOR_ERROR` default arm and emit `RejectionKind.of(rejection)` consistently with `traceOutput`") is shipped by **R585**. Re-verified this window: `AUTHOR_ERROR` `grep`=0 in `FieldRegistry.java`. Nothing remains to do; not touched this window. | **Discard**, recording R585 as the delivery vehicle. |

R520 (`table-on-input-removal-housekeeping`) stays a coherent deliberately-deferred docs tail, not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (12)

All twelve carry forward with their prior recommended actions verbatim. **R135 (§C.11, cross-listed
here) and R34 both sit on the nodeId cluster,** which this window advanced (R673, R784, R826 all
reached Done) without retiring either item's re-anchor target: R135's `FilterBinding.Local` /
`liftedSourceColumns` survive, and R34's `intent_node_id_instruction.basis` is now a shipped
materialized relation rather than an In-Review one. The other ten were not touched and re-verified at
the symbol with the premise-target still `grep`=0. **R193 and R213 remain the two overdue subsumption
candidates** (R649 / R585 / R589 shipped what they scoped); running both re-checks, most likely
closing both as subsumed, is the cheapest board-cleaning available.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R193** service-param-classification-sealed-hierarchy | Backlog | **R649 shipped the deliverable.** `reflectServiceMethod` / `looksLikeSourcesShape` `grep`=0; `reduceClaims` mints one sealed `ParamRole` per parameter. | **Re-derive the residue against shipped R649; most likely close as subsumed**, recording R649 as the delivery vehicle if nothing survives. |
| **R72** slim-servicecatalog-to-lookup | Backlog | **Premise materially changed by R649.** R649 dissolved `reflectServiceMethod` (`grep`=0) into `decodeServiceMethod` / `reduceClaims` / `bindServiceMethod`; `reflectTableMethod` / `reflectExternalField` survive. | **Re-spec** against the post-R649 split; drop every stale `ServiceCatalog.java:NNN` line cite. |
| **R213** input-field-rejection-attribution | Backlog | **Escalated, still unactioned.** `InputFieldResolution.Unresolved` now carries a `SourceLocation`; R589's occurrence-path derivation (Done) delivers the attribution split. | **Re-derive the residue against shipped R585 + R589; most likely close as subsumed.** |
| **R66** rejection-string-carrier-widening | Backlog | Phase **A2** was verbatim what **R585** shipped (`Unresolved` carries `Rejection rejection`, not `reason:String`). Phases A1, A3, B1, B2 survive. | **Re-spec:** strike A2 as delivered; re-baseline the four surviving phases; fix the `:25-30` anchors. |
| **R34** nodeid-migration-quickfix | Backlog | Carried, self-contradictory. The "shim facts" driver names three WARN sites deleted by R473; the re-anchor addendum (dated 2026-08-20) confirms the driver is void and names `intent_node_id_instruction.basis` as the re-derivation target, **now materialized live by R826 (Done this window).** Body still leads with the void driver. | **Re-spec** onto `intent_node_id_instruction` (`basis` + `node_type_name` + location); retitle off "shim facts". The addendum already records this and its target is now a shipped materialized relation. |
| **R122** compound-entity-mutations | Backlog | "Design space narrows under R222" leans on R222's discarded recursive `InputUsage` model (R222 left the board 2026-08-06). `ChildField.TableTargetField` (added by this item) is live. | **Re-spec the "narrows under R222" section**; re-express against the captured `intent_` / `applied_` relations. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Central target `CompileDependencyGraphBuilder.addFieldEdges` deleted (`grep`=0); the body's own dissolution condition has occurred. Cites absent `FkTargetConditionEmitter.emitTerm` and phantom `MutationConditions` as live. | **Re-derive against the plan-projected recompile graph.** If closed, discard, else re-spec; repoint `FkTargetConditionEmitter.emitTerm` -> `FkTargetConditionFilter` and drop `MutationConditions`. |
| **R545** model-free-of-emit-vocabulary | Backlog | `RowsMethodBody` / `RowsMethodSkeleton` deleted (diagnosis + second deliverable gone); first deliverable survives (`ClassName` / `TypeName` model-pervasive). The `:18` counter-argument names R638-deleted `FieldClassification` / `TypeClassification`. | **Re-spec.** Drop the `RowsMethodBody` diagnosis; keep the first deliverable; update the `:18` counter-argument to the current `rewrite/catalog/` contents. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Both named edit-target generators deleted: `QueryConditionsGenerator` (R552), `TypeClassGenerator` (R549). Successors `ConditionGlueRenderer` / `ProjectionUnitRenderer` live. Names absent `Inline*Emitter` host files. | **Re-derive against the new `render/` layer**; drop every dead cite. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | R431 (Done) deleted the target surface: `SourceKey` is a plain `record` with no `Reader`. Successors `KeyLift` / `LifterRef` / sealed `Wrap` live; the "R431 ... plans to" tense is wrong (R431 is Done). | **Re-spec the current-state / approach section** against the decomposed model; fix the tense. |
| **R76** participant-fieldsjoin-helpers | Backlog | R650 converted cross-table participant fields to capped correlated subselects; the surviving `step.leftJoin(...)` is the joined-detail join only, so the cross-table `$fieldsJoin` framing is obsolete. | **Re-spec** onto the joined-detail `LEFT JOIN`, or discard if R650's subselect conversion removed the motivating duplication. |
| **R430** lsp-compile-diagnostics-publish | Backlog | **Premise broken by R638 (Done).** `CompileDiagnostic` / `Workspace.compileDiagnostics()` / `setCompileDiagnostics` are `grep`=0 in `graphitron-lsp` main; R638 moved LSP diagnostics onto the capture cadence. `publishDiagnostics` survives. The compile-diagnostic stream still lives in `graphitron` core (`CompileFacts` / `IncrementalCompileEngine` / `CompileDiagnostic`); this window's LSP work (R818 find-references) touched neither this surface nor the store-read one. | **Re-derive current state against the fact-store LSP**; re-anchor the publish trigger onto the surviving `graphitron`-core compile stream and keep the publish-against-generated-URI goal. Discard only if that stream is later retired. |

## C. Outdated: update references only (work valid, refs stale) (29)

Substance intact; names and line numbers drifted. Every long-standing driving symbol re-verified
still `grep`=0. **R135 (§C.11) was matured last window on R728's landing and holds unchanged; none of
these were touched this window.** R762 (§C.15) holds.

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

### C.11 `FilterBinding` reshape drift + R728 junction-chain move (carried; R57; matured last window)

R57 replaced the `liftedSourceColumns` slot on the two reference carriers with the sealed
`FilterBinding` (`Local(List<ColumnRef> ownTableColumns)` / payload-free `Remote`). The identically
named component on the resolver's `JoinPath` and `Resolved.FkTarget.DirectFk` **survives**. **R728
(Done last window) removed `validateLift`** (`grep`=0), turning the junction-chain rejection into
absent-local-columns reaching a hop-general `EXISTS`. This window's nodeId trio (R673, R784, R826)
advanced the cluster but retired neither carrier: `liftedSourceColumns` (5 files) and `FilterBinding`
(13 files) re-verify live.

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

Empty of blocking defects. `changelog.md` carries `next-id: R836`, clearing the max present id
(R835). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and the six non-empty
`depends-on:` edges resolve to present files.

Two **pre-existing, non-blocking** hygiene notes:

1. Four **legacy** items still lack a `bucket:` key: R242 (`dml-payload-positional-alignment`),
   R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
   R180 (`record-parent-column-read-helper`). Build tolerates the omission; fold a `bucket:` in
   whenever each is next edited.
2. **Numbering gaps** among the item files (each allocated id either folded away inline or born-and-
   closed within a window) are harmless: numbers are never reused, and `next-id` R836 clears the max
   present id R835.

## Cross-cutting observations

1. **A Done item stales the board only by deleting a construct active items name, and this window
   none did.** The 7 shipped items touched only nodeId-relation-internal, condition-render-layer,
   LSP-feature, storage-census, or skill-doc constructs. The nodeId trio (R673, R784, R826) read the
   lift carriers rather than removing them, so R135's and R34's targets survive. Read the premise, not
   the identifier.

2. **The nodeId cluster is the window's model-level activity, and it broke no premise.** R826
   materialized `intent_node_id_instruction` (confirming R34's re-anchor target as a shipped relation),
   R784 partitioned a straddling reference per column on UPDATE (reading `liftedSourceColumns`, not
   retiring it), and R673 shipped by-id lookups over multitable interfaces. None retired the reflection
   reader `JooqCatalog.nodeIdMetadata` (4 files, live), so the R615 / R273 / R34 / R588 watch chain
   still holds: the reader that rewires readers off reflection is still pending. R135 was already
   matured last window on R728 and holds unchanged.

3. **R193 and R213 remain the two overdue subsumption candidates, and neither moved.** R193 asked for
   the sealed parameter classifier R649's `reduceClaims` / `ParamRole` shipped; R213 holds the same
   shape against R585 / R589. Running both re-checks and most likely closing both as subsumed is the
   cheapest board-cleaning available. R209 (§A) is the third mechanical close (fully delivered by R585).

4. **The Ready set turned over and is where stale prose bites soonest.** It is now R730, R684, R333,
   R555, R726, R676, R427, R663, R724, R467 (ten). Three prior Ready items reached Done (R673, R749,
   R818) and two nodeId items entered (R726, R676, both re-verified clean of tracked retired symbols).
   Of the ten, **R333 (§C.5/§C.7), R427 (§C.0), R555 (§C.10), and R684 (§C.12) carry stale cites** (all
   re-confirmed present, none refreshed this window); R730, R726, R676, R663, R724, R467 are clean.
   **Refreshing R333, R427, R555, and R684 before pickup remains the highest-value hygiene action on
   the board.**

5. **The new-item intake is born-current.** The 7 items filed this window (R829 to R835) cite no
   retired symbol; several (R829, R830, R831, R835) are performance / coverage follow-ups to the
   fact-schema and nodeId work that shipped, and read against the live materialized relations. No item
   was discarded this window.

---

_Review date: 2026-08-26._
