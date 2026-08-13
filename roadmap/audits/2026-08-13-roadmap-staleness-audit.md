# Roadmap staleness audit: 2026-08-13

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `029a727`, committed 2026-08-12 22:38, audited 2026-08-13). The goal is
to find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed or been discarded, or
specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-08-12` staleness audit, which has been deleted;
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
  tracks *architecture* drift. Both halves of the substrate-plus-derivation stack
  the sweep anticipated landed in prior windows (R595/R610/R603 substrate, R589
  derivation), so the sweep is now a record of a completed adoption rather than a plan.
- `2026-08-06-r222-lineage.md`, the absorption ledger and rejected-design record
  preserved when R222 was discarded.
- `2026-08-06-demand-exemption-census.md`, `2026-08-06-directive-consumer-census.md`,
  `2026-08-06-graphql-java-diff-spike.md`, `2026-08-06-structural-classifier-census.md`,
  the four grounding censuses/spikes filed alongside the fact-base work.
- `classification-test-dsl-inventory.md`, the permanent corpus-retirement inventory
  (its "closed and historical" banner is intact).

## Headline: a quiet roadmap-churn window (R630 to Done as additive docs, R635-R643 filed born-current, R640/R641 discarded, heavy Spec churn on R612/R638/R639), no main/test source retired a symbol, every prior staleness driver re-verified grep = 0, and the flag structure carries forward unchanged

Where the prior audit caught a busy five-to-Done window that retired symbols for the
first time in three windows, this one is quiet in exactly the dimension a staleness
audit cares about. **One item reached Done (R630), and it retired nothing an active
item cites.** The rest of the window is roadmap prose: seven item files born
(R635-R643, two of them discarded the same motion), and dense Spec-stage churn on the
LSP/config/session cluster (R612, R638, R639). A `git diff` from the prior audit's
horizon to this HEAD touches **only** `docs/` and `roadmap/` files: no `src/main/java`
or `src/test/java` under any in-scope module changed. That is the decisive fact: with
no code retiring or renaming a symbol, every driving symbol the prior audit flagged
re-verifies `grep` = 0 at this HEAD, and **the flag structure carries forward
identically**, item for item, recommendation for recommendation.

- **R630 (`fact-architecture-docs-home`, Done)** gave the fact architecture a durable
  documentation home: `pipeline-overview.adoc` rewritten to the shipped shape,
  `fact-model.adoc` as the new *why* page, the first axiom of `development-principles.adoc`
  restated, a generated per-family schema reference built from the DDL `COMMENT ON` text,
  and three new build gates (`check-schema-identifiers`, `CommentRenderabilityGateTest`,
  `FactSchemaGateTest`) plus a `meta_` relation family describing the schema. Its new code
  is **additive** (`StoreCatalog` in `graphitron-model`, `SchemaReferencePages` in
  `roadmap-tool`); the classification walk it documents is named **transitional**, not
  retired. It **removed** two roadmap-id citations from `development-principles.adoc` and
  retired **no** symbol any roadmap item names. It filed R635 and R636. Nothing on the
  board goes stale from R630.
- **New items born-current (retired-symbol `grep` = 0 on each):** R635
  (`schema-drift-guard-covers-prefixless-relations`), R636 (`schema-reference-view-column-fidelity`)
  from R630; R637 (`dev-schema-load-failure-classification`); R638 (`lsp-reads-the-fact-store`,
  Spec); R639 (`session-identity-method-hooks`, Spec); R642 (`catalog-facts-readers-move-to-the-store`);
  R643 (`supergraph-peer-surface`). R640 and R641 were filed and **discarded** inside the
  window (R639 absorbed R640; R612 absorbed R641), leaving no file. None cites a retired symbol.
- **Spec churn, no code:** R612 (`maven-config-fact-family`) was reopened, reworked across
  many revision passes, and taken **Spec → Ready**; R638 and R639 were drafted to **Spec**
  through repeated principles and source-verification passes. This is design work on paper;
  it moves no symbol and stales no item. R347 remains the sole **In Progress** item, correctly
  not flagged.

Net: **1 §A / 8 §B / 23 §C / 0 §D**, flag total **32**, unchanged from the prior audit.
The long-standing dissolution drift (`Operation` seal, `TableInputType`, the lookup triplet,
the `Split*`/`Record*` merge, `planSlug`/`SourceKey.Reader`, the condition/projection emitters,
the absent `*Emitter` names, the R589 `UnboundField` reshape) is entirely unchanged: every
driving symbol re-verified `grep` = 0 at this HEAD, and **no flagged item was repointed in
place this window** (R333, the standing high-value refresh, was not touched at all this
window, so it drifts no further but is repaired no closer).

## Changes since the 2026-08-12 audit

The window runs from the prior audit's baseline HEAD (`7ed02f4`, 2026-08-11 23:03) to this
HEAD (`029a727`, 2026-08-12 22:38). The visible git horizon on this shallow clone begins at
`d9687ec` (2026-08-12 06:46, R630 In Review → Done), so R630's Done transition and its final
slices sit in the pre-horizon gap and are read from `changelog.md` and the current pages rather
than from a diff; everything after the horizon is a diff of **49 commits**, of which the file
change set is entirely `docs/` and `roadmap/`.

**The one code item that reached Done, and what it did to the symbol set:**

- **R630 → Done** (`d37e9b6` pipeline overview, `f1b5eea` + `07ac443` fact-model page + first
  axiom, `e75d9dc` forward voice + reviewer taxonomy, `d5d6c32` generated schema reference,
  `c7a7f1e` drift guard): additive documentation home for the fact architecture, plus
  `StoreCatalog` (`graphitron-model`), `SchemaReferencePages` (`roadmap-tool`, off the production
  classpath), a `meta_` relation family, and three build gates. Retired **no** cited symbol;
  *removed* two roadmap-id citations from `development-principles.adoc`. Filed R635, R636.

**Verification that no symbol drift entered the window:** a `git diff` from the horizon to HEAD
shows changes only under `docs/history/road-to-the-relational-core.adoc` and `roadmap/*.md`; no
in-scope module's `src/main/java` or `src/test/java` changed after the horizon, and R630 (before
the horizon) is documented additive. A fresh `grep` of the main sources re-confirms the whole
long-standing retirement set at `grep` = 0 (see Scope and method).

**Items filed this window (born-current, retired-symbol `grep` = 0 on each):** R635
(`schema-drift-guard-covers-prefixless-relations`), R636 (`schema-reference-view-column-fidelity`),
R637 (`dev-schema-load-failure-classification`), R638 (`lsp-reads-the-fact-store`), R639
(`session-identity-method-hooks`), R642 (`catalog-facts-readers-move-to-the-store`), R643
(`supergraph-peer-surface`). R640 and R641 filed-and-discarded (no file). Next-id is now **R644**.

**Transitions:** R630 In Review → Done; R635-R637, R642, R643 filed to Backlog; R638 Backlog →
Spec (through several revision passes); R639 Backlog → Spec (absorbing R640); R612 reopened →
Spec → Ready (absorbing R641); R640, R641 filed → Discarded. Actively drafted and correctly
**not** flagged as stale: R347 (In Progress), R638 and R639 (Spec, born-current), R612, R427,
R467, R555, R617 (Ready).

**Board accounting.** **192 item files** today (measured), up from 186: id range grew R634 → R643,
with R630 leaving to Done, R640/R641 discarded without a file, against seven new files (R635-R639,
R642, R643). Status distribution: **172 Backlog, 13 Spec, 6 Ready, 1 In Progress, 0 In Review,
0 Done**. Tombstone-free (`grep` for `status: Done` in `roadmap/*.md` = 0). No duplicate `id:`;
`changelog.md` carries `next-id: R644`, clearing the max allocated id (R643). A `depends-on:`
sweep resolves all **five** non-empty edges (R98 → `catalog-check-constraint-validation`, R112 →
`capability-catalog`, R298 → `oneof-augment-defeated-by-descriptions`, R170 →
`multi-source-input-validation`, and R643 → `maven-config-fact-family`, the one new edge this
window) to present files. The only structural nits are the same four **legacy** items still missing
a `bucket:` key (§D), all pre-dating this window.

## Scope and method

All **192** `R<n>` item files were reviewed. Every driving symbol below was re-checked against a
fresh `grep` of the main sources (`graphitron`, `graphitron-mcp`, `graphitron-lsp`,
`graphitron-model`, `graphitron-maven-plugin`, `graphitron-fixtures-codegen`), not carried on the
prior audit's word. Because the window changed no in-scope `src/**/*.java`, the expectation was that
the whole retirement set re-verifies unchanged, and it did; the check was run rather than assumed.

**Long-standing retirements, re-verified still retired at this HEAD (`grep` = 0 in main real code;
`{@code}`/`{@link}`/comment hits excluded):** `CompileDependencyGraphBuilder`; `RowsMethodBody` /
`RowsMethodSkeleton`; `QueryConditionsGenerator` / `TypeConditionsGenerator`; `TypeClassGenerator` /
`collectRequiredProjection`; `ParentProjectionContainmentCheck` and the `methodgraph` package;
`GraphitronType.TableInputType` / `buildNonTableInputType`; `MutationInputResolver.resolveInput`
(the class survives; the method and its `TableInputType` routing are gone, and the surviving
`resolveInput`-prefixed hits are unrelated methods such as `resolveInputFields` /
`resolveInputElementJavaType` / `RecordBindingResolver.resolveInput`); the lookup triplet
(`LookupTableField` / `BatchedLookupTableField` / `QueryLookupTableField`), `LookupValuesJoinEmitter`;
the `Split*` / `Record*` leaf names; `Rejection.Deferred.planSlug`; `SourceKey.Reader`; the
`Operation` seal and every `Operation.<Arm>` reference (successor `OperationMember`); the R589
retirements (`PairVerdict` / `pairVerdict` / `reduceDirectiveConflict`, `UnclassifiedField.definition()`);
and the absent projection/condition `*Emitter` family (`InlineColumnReferenceFieldEmitter`,
`InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`, `FkTargetConditionEmitter`, the phantom
`MutationConditions`), all `grep` = 0 in every main tree.

**Retirements this window (new):** none. The one item to reach Done (R630) is additive and named its
documented classification walk *transitional* rather than retiring it. The `UnboundField` reshape and
`FieldClassification.Unclassified` split remain as the prior audit recorded them (R589, prior window):
`ConditionOwnedField` split out and live, `Unresolvable` / `Conflicted` both live, the surviving
`UnclassifiedField` / `UnclassifiedType` leaves what R66 and `rejection-spec-by-example` cite.

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Why obsolete | Recommended action |
|---|---|---|---|
| **R209** field-registry-typed-rejection-trace | Backlog | **Carried (3 windows); still Backlog, still fully delivered.** The entire deliverable ("remove the `RejectionKind.AUTHOR_ERROR` default arm and emit `RejectionKind.of(rejection)` consistently with `traceOutput`") is shipped by **R585**. Re-verified at the symbol this window: `FieldRegistry.java` emits `RejectionKind.of(u.rejection()), u.rejection().message()` on both `Unresolved` arms (`:121`, `:142`), with `AUTHOR_ERROR` `grep` = 0 and `u.reason()` `grep` = 0 in the file. Nothing remains to do; this window did not touch it. | **Discard**, recording R585 as the delivery vehicle. The symbol check (`AUTHOR_ERROR` default and `u.reason()` both `grep` = 0 in `FieldRegistry`) is done and clean; retire to lineage. |

R520 (`table-on-input-removal-housekeeping`) stays a coherent deliberately-deferred docs tail,
not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (8)

All eight carry forward from the prior window unchanged; each re-verified at the symbol this window
(premise-target still `grep` = 0 with a live successor). No new code landed against any of them, so
none gained or lost information. **R213 remains escalated**: its deferred "re-check after R589 slice 5"
gate came due when R589 reached Done (prior window) and is still unactioned, so the recommendation
holds and sharpens with age.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R213** input-field-rejection-attribution | Backlog | **Escalated, still unactioned.** R585 inverted the load-bearing premise (`:43`/`:48`/`:54` still say `Unresolved` "has no `SourceLocation`" / "grows a `SourceLocation` field", but the record now carries one), and R589's occurrence-path derivation (Done, prior window) delivers exactly the attribution split this item asks for (definition-keyed violations locate at the input field's own location, use-keyed ones at the occurrence path). The item's own fact-base note (`:80`) says "Re-check what remains after R589 slice 5; the residue may be empty": that check is due and nobody has run it. | **Re-derive the residue against shipped R585 + R589; most likely close as subsumed.** Both halves the item scoped have landed: R585 gave `Unresolved` the `(SourceLocation, Rejection)` shape, R589 gave the located-attribution split. If a thin residue survives, re-spec it onto the shipped record and drop the "grows/has no `SourceLocation`" claims and the co-design note; otherwise discard, recording R585 + R589 as the delivery vehicles. |
| **R66** rejection-string-carrier-widening | Backlog | Carried. Phase **A2** ("widen `Unresolved.reason: String` → `rejection: Rejection`") was verbatim what **R585** shipped; the `:28` anchor is now `(fieldName, SourceLocation, Rejection)`. Phases A1 (`ParsedPath.errorMessage`), A3 (`UnboundArg.reason`), B1 (`EnumValidation.Mismatch`), B2 (`TypeBuilder` aggregations) survive. Its `UnclassifiedField`/`UnclassifiedType` cites (`:93`-`:106`) are live. | **Re-spec:** strike A2 as delivered by R585; re-baseline the four surviving phases onto the shipped record (B2's prerequisite is now satisfied); fix the `:25-30` anchors. |
| **R34** nodeid-migration-quickfix | Backlog | Carried, self-contradictory. The "shim facts" driver names three WARN sites **R473 deleted** and **R27 was discarded**; the discard commit half-reconciled the item, leaving the mechanism sections still naming the deleted sites as the live gap. | **Re-spec:** the "shim facts" driver is void. The migration goal (ergonomic `@nodeId` fixes across the sis sites) survives, but its source must be re-derived onto R473's landed grammar rejections or the R589 claim relation. Retitle off "shim facts". |
| **R122** compound-entity-mutations | Backlog | Carried. "Design space narrows under R222" leans on R222's discarded recursive `InputUsage` model; R222 left the board 2026-08-06. `TableTargetField` (added by this item) is live; the `:127` `UnboundField` cite is a live variant name (fine). | **Re-spec the "narrows under R222" section**: drop the discarded `InputUsage` carrier; re-express the nested-input model against the captured `intent_`/`applied_` relations the fact-base architecture adopted. Keep the compound-mutation goal, `@reference(path:)` flattening, and `TableTargetField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Carried. Central target `CompileDependencyGraphBuilder.addFieldEdges` deleted (`grep` = 0); the body's own dissolution condition (`:163`) has occurred. Also cites absent `FkTargetConditionEmitter.emitTerm` (`:45`) and phantom `MutationConditions` (`:57`) as live. | **Re-derive against the plan-projected recompile graph.** Confirm at the symbol whether the nested-fetcher per-field edge is now modeled under `EmitPlan`; if closed, **discard**, else **re-spec**. In the same pass repoint `FkTargetConditionEmitter.emitTerm` → `FkTargetConditionFilter` via `ConditionCommands` and drop `MutationConditions`. |
| **R545** model-free-of-emit-vocabulary | Backlog | Carried. `RowsMethodBody` / `RowsMethodSkeleton` deleted, so the diagnosis and second deliverable are gone. First deliverable survives: `ClassName` / `TypeName` are still model-pervasive. | **Re-spec.** Drop the `RowsMethodBody` diagnosis and second deliverable; keep and re-baseline the first (`JavaTypeRef` replacing `TypeName` / `ClassName`) against the post-R549 model. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Carried. Both named edit-target generators deleted: `QueryConditionsGenerator` (R552), `TypeClassGenerator` (R549). Successors `render/ConditionGlueRenderer` / `render/ProjectionUnitRenderer` live. Also names absent `InlineTableFieldEmitter.java:144` / `InlineLookupTableFieldEmitter.java:218` as live host files. | **Re-derive against the new `render/` layer.** Determine whether the renderers still exhibit the duplicated helper-emission problem; drop every dead `QueryConditionsGenerator.java:NNN` cite and the two absent `Inline*Emitter.java:NNN` file cites. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried. R431 (Done) deleted the target surface: the body cites `SourceKey.Reader.SourceRowsCall` as "the live surface", but `SourceKey` is a plain record with no `Reader`. Successors `KeyLift` / `LifterRef` / sealed `Wrap` live; "R431 ... plans to decompose" reads present tense but R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model; drop the `SourceKey.Reader.SourceRowsCall` re-anchor note and the tense error. |

## C. Outdated: update references only (work valid, refs stale) (23)

Substance intact; names and line numbers drifted. All twenty-three carry from the prior audit
**unchanged**; **none was repointed in place this window** (no flagged item was edited at all this
window). Every driving symbol re-verified still `grep` = 0. §C.9 records the absent-`*Emitter`
driver, which hits six already-listed rows and adds no distinct item.

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

### C.6 `TableInputType` / `resolveInput` removal drift, plus the `UnboundField` reshape (carried; R519 + R515; R589)

R222 left this subsection via discard; R213 left it (promoted to §B). R589's `UnboundField`
reshape (prior window) added a stale line to R245; it is unchanged this window.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R234** jooq-embedded-and-udt-input-backings | Spec | `:15` cites `TypeBuilder.buildNonTableInputType` (`grep` = 0) as the live dispatch to extend. | **Re-anchor** to the current `TypeBuilder` input-classification path (`buildInputType`). |
| **R257** updaterows-walker-sdl-substrate | Backlog | `:17` calls `resolveInput` "the legacy resolver" (method gone R515); `:15`/`:19` reach carriers "via `TableInputType.inputFields()`" (gone R519). | **Re-anchor** both: `resolveInput` → `admitMutationInputFields`, `TableInputType.inputFields()` → per-consumer input resolution. |
| **R337** input-nesting-projection-classification | Backlog | `:30` reaches an input object's fields "via `TableInputType.inputFields()`" as the LSP-hover mechanism. | **Re-anchor** the one mechanism cite to per-consumer input resolution. |
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot composition "in `MutationInputResolver.resolveInput`" (method gone R515). `:124`'s test bullet "Override `@condition` on an `UnboundField`" names the pre-R589 carrier; R589 split the `@condition`-owned input case into `InputField.ConditionOwnedField`. | **Re-anchor** `:76` to `admitMutationInputFields`, and `:124` to `ConditionOwnedField`. |

### C.7 Condition + projection emitter dissolution drift (carried; R552 + R549)

Condition emission is `render/ConditionGlueRenderer`; projection `render/ProjectionUnitRenderer`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh still incomplete; not touched at all this window.** Still at the symbol: 5× `TypeClassGenerator`, 5× `collectRequiredProjection`, 5× `methodgraph`, `LookupValuesJoinEmitter`, 2× `ParentProjectionContainmentCheck`, 1× `TypeConditionsGenerator`, 4× `InlineTableFieldEmitter`, plus the §C.0/§C.5 carriers and the §C.9 absent-emitter names. R333's rationale was migrated to `docs/architecture/` in the prior window; the body left behind is a pure implementation plan whose class names are wrong, and it is a **Ready** item, so the stale prose bites the next implementer to pick it up. | **Finish the refresh in one pass** (this row + §C.0 + §C.5 + §C.6 + §C.1 + §C.9): repoint `TypeClassGenerator` / `collectRequiredProjection` → `ProjectionUnitRenderer` / `ProjectionCommands`; drop the `TypeConditionsGenerator`, `ParentProjectionContainmentCheck`, `methodgraph`, `operation()` cites; re-anchor `LookupValuesJoinEmitter` to the render lookup family and the `Operation` seal cites to `OperationMember`; replace the `Inline*Emitter` projection-arm names with the render projection layer and `FkTargetConditionEmitter` with `FkTargetConditionFilter` via `ConditionCommands`. Rows citing `SplitRowsMethodEmitter` stay (live). |
| **R231** emit-text-mapped-enum-fields-as-enum-type | Backlog | `:39` locates "the field-type-emit fork (likely in `TypeClassGenerator` or ...)". | **Re-anchor** the investigation locus to `ProjectionUnitRenderer` / `plan/ProjectionCommands`. |
| **R35** source-orientation-javadocs | Backlog | `:42` enumerates classes for a javadoc sweep, including `TypeClassGenerator` and `TypeConditionsGenerator`, both deleted. | **Re-anchor** the enumeration: drop the two deleted names. Low priority; scope illustrative. |

### C.8 `InputFieldResolution.Unresolved` reshape drift (empty; R585)

R585 (two windows back) reshaped `Unresolved` to `(fieldName, SourceLocation, Rejection)`. Its
consumers R66, R213 (§B above) and R209 (§A) hold the residue. **No item remains in this subsection.**

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

Empty of blocking defects. `changelog.md` carries `next-id: R644`, clearing the max allocated id
(R643). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and the five non-empty
`depends-on:` edges resolve to present files (R98 → `catalog-check-constraint-validation`, R112 →
`capability-catalog`, R298 → `oneof-augment-defeated-by-descriptions`, R170 →
`multi-source-input-validation`, R643 → `maven-config-fact-family`). R640/R641's discards left **no**
dangling edge. The seven items filed this window (R635-R639, R642, R643) carry well-formed
front-matter and read born-current.

One **pre-existing, non-blocking** hygiene note, surviving unchanged:

1. Four **legacy** items still lack a `bucket:` key: R242 (`dml-payload-positional-alignment`),
   R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
   R180 (`record-parent-column-read-helper`). Build tolerates the omission; fold a `bucket:` in
   whenever each is next edited.

## Cross-cutting observations

1. **A quiet window changes no flag, because staleness tracks code, not activity.** Forty-nine
   commits landed after the horizon and seven item files were born, yet the flag total held at 32,
   because every change was roadmap prose or additive documentation. The audit-relevant property is
   whether a window *retired or renamed a symbol some active item names*; this one did neither. When
   the only Done item (R630) is additive and even *removes* citations rather than adding retirements,
   the board's staleness is exactly what it was the day before.

2. **R213's re-check is now overdue, not merely due.** Both halves it scoped (R585's record reshape,
   R589's located-attribution split) shipped in prior windows; the item's own body predicts its
   residue "may be empty". It sat unactioned another window. Running that re-check, and most likely
   closing R213 as subsumed, is the single cheapest board-cleaning move available and gets cheaper to
   defer only in the sense that the prose keeps misleading.

3. **R333 is the standing high-value refresh, now a Ready item drifting on inertia.** It carries the
   most stale cites on the board (spanning §C.0/§C.5/§C.6/§C.7/§C.9), its rationale already migrated to
   `docs/architecture/` so the body is a pure implementation plan, and it is **Ready** to be picked up
   with wrong class names throughout. It was not touched this window, so it neither drifted further nor
   got repaired; the mapped one-pass refresh remains the highest-value hygiene action on the board.

4. **`resolveInput` stays the cautionary count.** A bare `grep` for `resolveInput` returns unrelated
   `resolveInputFields` / `resolveInputElementJavaType` / `RecordBindingResolver.resolveInput` hits; the
   specific `MutationInputResolver.resolveInput` the §C.6 R257/R245 flags cite is gone. A retirement
   audit that keys on symbol *prefixes* rather than the fully-qualified member would misjudge these
   flags; both stand.

5. **The Ready set is where stale prose bites soonest.** R333, R427, R467, R555, R612, R617 are the six
   Ready items next in line. R333 (§C.7) and R427 (its superseded `Operation.Facet` precedent, §C.0) are
   the two carrying stale cites; R467, R555, R612, R617 are clean (R612 was reworked to Ready this window
   and reads born-current). Refreshing R333 and R427 before pickup remains the highest-value hygiene
   action on the board.

---

_Review date: 2026-08-13._
