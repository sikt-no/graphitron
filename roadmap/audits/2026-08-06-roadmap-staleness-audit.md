# Roadmap staleness audit: 2026-08-06

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `96346c7`, committed 2026-08-05, audited 2026-08-06). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-08-05` staleness audit, which has been deleted;
only the latest staleness audit is retained. Six siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  meant to be edited in place as scope iterates. Its MUST/SHOULD tables continue
  to lag the post-decomposition model; refreshing it stays out of scope for this
  staleness pass.
- `2026-07-04-r222-r333-conformance-analysis.md` is the R222/R333 conformance
  analysis, a targeted implementation-vs-spec conformance record, not a
  point-in-time staleness review; left in place.
- `2026-07-26-fcis-command-layer-distance.md` is the FCIS command-layer distance
  analysis, a symbol-anchored snapshot with a stated re-derivation method, not a
  staleness review; left in place, not a flag target.
- `2026-08-05-fact-base-h2-spike.md` and `2026-08-05-h2-functions-jooq-spike.md`
  are the two grounding spikes for the fact-base architecture (R589 / R595): the
  H2 in-memory store, and the H2 function surface + jOOQ codegen path. Both are
  spike-execution records that R595's body cites by path, not point-in-time
  staleness reviews; left in place, not flag targets.

`classification-test-dsl-inventory.md` is the permanent corpus-retirement
inventory; its "closed and historical" banner is intact. No action; it stays as
lineage.

## Headline: a roadmap-only window (22 commits, zero main-source change), so the entire drift set is frozen; the 30 flags from the prior audit carry verbatim and were re-verified at the symbol, and the window's activity is board churn (three born-current items filed, R589's fact-base spec matured, R584 -> Ready) plus two grounding spike-audit records

Every one of the **22 commits** between the prior audit's HEAD (`fda241e`,
2026-08-04 21:12) and this HEAD (`96346c7`, 2026-08-05) touched **only files
under `roadmap/`**. A `git diff --name-only fda241e..HEAD` filtered to exclude
`roadmap/` returns nothing: no generator source, no test source, no fixture, no
build config moved. That single fact determines this audit's shape.

- **The staleness drift set is frozen.** Every §B / §C flag is a claim about a
  main-source symbol being retired with a live successor. No main source moved,
  so no such claim can have changed. The 30 flags carry **verbatim** from the
  2026-08-05 audit; the tables below are reproduced unchanged, with each driving
  symbol re-verified `grep` = 0 in main this pass (see Scope and method).
- **No prior flag was fixed or resolved.** None of the flagged item files was
  edited this window, so no stale-live cite was repointed in place and none
  leaves §B or §C.
- **The board grew by three.** R594 (`mcp-snapshot-axis-key-naming`, Backlog),
  R595 (`graphitron-model-captures-facts`, Spec) and R596
  (`dangling-plan-page-xref-paths`, Backlog) were filed. All three are
  **born-current** (read against the post-R563 model; scanned clean of every
  retired symbol) and **none is flagged**. R595 is the fact-base substrate item
  (a new `graphitron-model` module) that R589's architecture calls for; R594 and
  R596 are small self-contained cleanups (MCP snapshot-axis key naming; 9 dangling
  cross-file `xref:` paths in the published docs).
- **R589's fact-base spec matured in place.** R589 (`validation-adds-facts`,
  Spec) took the largest single-item revision of the window (+192 lines):
  materialization decided, the store adopted in a `graphitron-model` module, the
  strangler frame and capture/derive stratification recorded. It is still Spec
  and actively being drafted, so its prose **is the work**, not consumer-facing
  stale-live; not a flag target. R582 (`adoc-xref-section-anchor-gate`) saw two
  Spec revisions; R584 (`mcp-server-instruction-routing`) went **Spec -> Ready**.
- **Two grounding spikes were recorded.** `2026-08-05-fact-base-h2-spike.md` and
  `2026-08-05-h2-functions-jooq-spike.md` were added as spike-execution records
  for the fact-base stack; R595's body cites both by path. Non-staleness
  siblings, left in place.

Net: **0 §A / 5 §B / 25 §C**, §D empty. Flag total holds at **30**, unchanged
from the prior window. Composition: **§B carries R462, R545, R85, R221, R71
unchanged** (all re-verified, premise-targets still `grep` = 0 with live successors);
**§C carries all 25 prior flags** unchanged (none fixed, none resurrected, none
added: no construct was retired this window because no code moved). The
`Operation`-seal drift that drove the prior audit (R427, R382, R562, R333, R222)
is unchanged and still open, because the successors (`OperationMember`,
`MEMBER_ARMS`, `MEMBER_KNOWN_GAPS`) are exactly as the prior audit left them.

## Changes since the 2026-08-05 audit

**22 commits** landed between the prior audit's HEAD (`fda241e`, 2026-08-04 21:12)
and this HEAD (`96346c7`, 2026-08-05). One of those commits (`d222512`) is the
prior audit itself; the other 21 are roadmap edits. **Every commit touched only
files under `roadmap/`** (verified: `git diff --name-only fda241e..HEAD` filtered
against `roadmap/` is empty).

**No item ran to Done.** In Progress holds R347 alone and In Review holds R580
alone, both unchanged from the prior audit. The non-recursive `^status: Done`
grep over `roadmap/*.md` still returns nothing.

**No prior flagged item was discarded, resolved, or edited this window.** None of
the 30 flag-carrying files appears in the window's diff, so no stale cite was
fixed in place and none leaves §B or §C.

**Three new items filed and still on the board** (all read against the current,
post-R563 model; all born-current; all scanned clean of every retired symbol):

- **R594** (`mcp-snapshot-axis-key-naming`, Backlog, `cleanup`): four snapshot-reporting
  MCP tools spell the availability/freshness axis keys two ways (`snapshotAvailability`
  / `snapshotFreshness` via `McpWire.writeSnapshotAxes` vs bare `availability` /
  `freshness` hand-rolled in `status` / `schema`); consistency cleanup.
- **R595** (`graphitron-model-captures-facts`, Spec, `architecture`): the fact-base
  substrate R589 needs, a new `graphitron-model` reactor module holding the
  fact-schema DDL, jOOQ codegen over it, an H2 bootstrap, and two infallible capture
  loads that change no behavior. Cites the two new spike audits by path.
- **R596** (`dangling-plan-page-xref-paths`, Backlog, `cleanup`): 9 cross-file `xref:`
  links in the published site name deleted plan pages and 404 on click; Asciidoctor
  never resolves a cross-document target, so the miss is build-silent. Docs cleanup.

**Other transitions:** R584 (`mcp-server-instruction-routing`) **Spec -> Ready**
(independent sign-off); R582 (`adoc-xref-section-anchor-gate`) and R589
(`validation-adds-facts`) saw Spec revisions (R589's the window's largest single
edit, +192 lines, folding in the materialization decision and strangler frame).
Ready set gains R584 (now R333, R427, R555, R584); Spec set holds at 20 in count
(R584 departs to Ready, R595 enters).

**Board accounting.** **175 item files** today (`roadmap/*.md` carrying `id:`), up
three net from the prior audit's 172: three ids allocated this window (R594-R596),
all still on the board (+3); none left to Done. Status distribution: **149 Backlog,
20 Spec, 4 Ready, 1 In Progress, 1 In Review, 0 Done**. Tombstone-free for the
twenty-seventh window running. No duplicate `id:`; max allocated id **R596**, and
`changelog.md` carries `next-id: R597`, clearing it. The numbering gaps from prior
windows (R575/R576 folded; R563/R579/R581 Done) are all benign. A `depends-on:`
sweep over all 175 item files resolves every edge (all are slug-based) to a present
file, including the one new edge introduced this window (`graphitron-model-captures-facts`,
R595's slug, cited by a dependent). The board is structurally clean; the only nit
is four **legacy** items missing a `bucket:` key (§D), all pre-dating this window.

**Net effect on flag counts: 30 flagged, 145 current.** 0 §A, 5 §B, 25 §C, 0 §D.

## Scope and method

All **175** `R<n>` item files were reviewed (plus the non-item placeholders
`inference-axis-coverage.adoc`, `relevance-ranked-search-howto.adoc`,
`relevance-ranked-search-oracle-howto.adoc` and the permanent `workflow.adoc`, all
correctly excluded: no `R<n>`, and `.adoc` besides). Because this window changed
**no main source**, no construct was retired or renamed, so the driving symbols of
the carried flags cannot have moved; nonetheless every stale-live cite below was
re-checked against a fresh `grep` of the main sources this pass rather than carried
on the prior audit's word, and each was confirmed `grep` = 0 with its successor
live. The three new items (R594-R596) and the three edited items (R582, R584, R589)
were additionally scanned for any retired-symbol cite; all six are clean.

**This window's symbol changes: none.** The generator sources, tests, and fixtures
are byte-identical to the prior audit's HEAD. Every symbol the carried flags
depend on is exactly as the prior audit recorded it, re-verified below.

**Retired symbols, re-verified still retired at this HEAD (no resurrections, no
new retirements):** the `Operation` seal and every `Operation.<Arm>` reference
(successor `OperationMember`; obligation `MEMBER_ARMS` / `MEMBER_KNOWN_GAPS` keyed
on `OperationMember.*`, `ExemptionRegistry.java:157`, `:384`); the `parentTypeName`
parameter on `parseExternalRef` / `ExternalFieldDirectiveResolver.resolve` (live on
`ServiceDirectiveResolver.resolve` / `classifyChildFieldOnTableType`);
`resolveDecodeHelperForTable` still **live** for the orphan / bare-scalar arm
alongside the name-first `resolveDecodeHelperForType`; the `OutputField.operation()`
accessor, the lookup triplet
(`LookupTableField` / `BatchedLookupTableField` / `QueryLookupTableField`),
`LookupField` / `LookupValuesJoinEmitter`, `DeleteRowsField` / `UpdateRowsField`,
`RoutineChainField`, `TableOnInputRejection` / `buildNonTableInputType`,
`ExternalCodeReference.name` / `NamedReferenceBinding`, `CompileDependencyGraphBuilder`,
`RowsMethodBody` / `RowsMethodSkeleton`, `QueryConditionsGenerator` /
`TypeConditionsGenerator`, `TypeClassGenerator` / `collectRequiredProjection`,
`ParentProjectionContainmentCheck` / the `methodgraph` package,
`GraphitronType.TableInputType` / `validateTableInputType`,
`MutationInputResolver.resolveInput` (with `admitMutationInputFields` live),
`SourceKey.Reader`, `Rejection.Deferred.planSlug`, the `Split*` / `Record*` leaf
names (merged to `Batched*`), and the `ColumnField` family (merged to
`ColumnBackedField`). All `grep` = 0 in main (the four `ColumnField` hits are
`{@code}` javadoc, the `resolveInput` hits are `RecordBindingResolver`'s unrelated
same-named method); each drives a carried flag below.

## A. Obsolete: should leave the active roadmap (0)

Empty. No item's entire premise was invalidated to removal this window. R462's
premise **was** invalidated in a prior window (its target class
`CompileDependencyGraphBuilder` is deleted and its stated dissolution condition has
occurred), but the safe call remains re-derivation at pickup rather than a blind
discard, so it sits in §B with discard as the likely outcome. R221 and R71 remain
similar re-derivation calls. R520 (`table-on-input-removal-housekeeping`) stays a
coherent deliberately-deferred docs tail, not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (5)

All five carried from the prior window, re-verified at the symbol: each item's
premise-target is still `grep` = 0 in main with a live successor. No main source
moved this window, so the set is unchanged and every prior recommended action
still holds verbatim; the re-verification below is a confirmation, not a revision.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R462** nested-fetcher-outgoing-field-edges | Spec | Carried. Title and central mechanism target `CompileDependencyGraphBuilder.addFieldEdges`, deleted by R549 slice 7 (`grep` = 0); the recompile graph is a typed projection over the plan, and the body's own stated dissolution condition (`:163`) has occurred. Its `addConditionsEdge(fetcher, parentTypeName)` cites (`:60`/`:84`/`:98`) are on the deleted builder surface, not R579's live sites. | **Re-derive against the plan-projected recompile graph.** Confirm at the symbol whether the nested-fetcher outgoing per-field edge (the `FilmMeta` case) is now correctly modeled under `EmitPlan`. If closed (likely), **discard** and record it; if a residue survives, **re-spec** onto the plan-projection. |
| **R545** model-free-of-emit-vocabulary | Backlog | Carried. `RowsMethodBody` / `RowsMethodSkeleton` deleted (`grep` = 0 each), so the whole `RowsMethodBody` diagnosis and the **second deliverable** are gone. The **first deliverable survives**: `ClassName` (~1700 lines, 23 model files) and `TypeName` (28 model files) are still model-pervasive. | **Re-spec.** Drop the `RowsMethodBody` diagnosis and the second deliverable entirely. Keep and re-baseline the first deliverable (`JavaTypeRef` replacing `TypeName` / `ClassName`) against the post-R549 model. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Carried. Both named edit-target generators are deleted: `QueryConditionsGenerator` (gone R552) and `TypeClassGenerator` (gone R549 slice 3.1); every `QueryConditionsGenerator.java:NNN` cite is dead. Condition emission moved to `render/ConditionGlueRenderer`, projection to `render/ProjectionUnitRenderer`. | **Re-derive against the new `render/` layer.** Determine whether `ConditionGlueRenderer` / `ProjectionUnitRenderer` still exhibit the duplicated helper-emission problem; if so, re-spec onto them. Drop every dead `QueryConditionsGenerator.java:NNN` cite. |
| **R221** validator-walks-plain-input-unbound-fields | Backlog | Carried. R519 deleted `TableInputType` / `validateTableInputType` (`grep` = 0), replacing them with per-consumer `collectInputFieldRejections` (live, `GraphitronSchemaValidator.java:524`); R566 made `@table`-on-input no longer produce a distinct table-input classification at all, so the item's "a `@table` input routes through the existing walker" framing (`:14`) is doubly obsolete. Unmoved this window. | **Re-derive against the post-R519 model.** If per-consumer resolution already validates plain-input `UnboundField + @condition(override:false)` shapes, **discard** (gap closed); else **re-spec** around `collectInputFieldRejections`. Re-baseline the `@table`-input premise for the accept-and-warn behavior. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried. R431 (Done) deleted the target surface: the body cites `SourceKey.java` line ranges and `SourceKey.Reader.SourceRowsCall` as "the live surface", but `SourceKey` is a plain record with no `Reader`. Successor `KeyLift` is live; `LifterRef` / sealed `Wrap` present. The sequencing line still reads "R431 ... plans to decompose"; R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model; drop the stale `SourceKey.Reader.SourceRowsCall` re-anchor note and the "R431 plans to decompose" tense. The goal (recordN key parity, non-jOOQ record parents) is intact. |

## C. Outdated: update references only (work valid, refs stale) (25)

Substance intact; names and line numbers drifted. All 25 flags carried verbatim
from the 2026-08-05 audit (every driving symbol re-verified still `grep` = 0, none
fixed in place, none added: no construct was retired this window). The
`Operation`-seal group (R427, R382, R562) and the two carried items that additionally
lean on the seal (R333, R222) are unchanged, because no code moved.

### C.0 `Operation` seal fully retired (carried; R563 slice 7, retired the prior window)

The `Operation` seal was retired one window before this one (R563 slice 7): the
`Operation` sealed interface and every `Operation.<Arm>` reference are `grep` = 0
in main, re-confirmed at this HEAD. A cite of an `Operation` arm **type** is stale.
The durable successor is `OperationMember` (a per-coordinate member multiset); the
paired test obligation is `MEMBER_ARMS` / `MEMBER_KNOWN_GAPS`
(`ExemptionRegistry.java:157`, `:384`). These three flags are unchanged from the
prior audit.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R427** relevance-ranked-search | Ready | `:335` "`operation()` stays `Fetch`" cites the retired accessor; `:339`/`:1162` cite `Operation.Facet` as a live precedent **type**. The prior audit told this item to fix the `Operation.Facet` **line number** to `:72`; that arm type is now `grep` = 0, so the line-fix advice is void. | **Re-anchor (target changed since prior audit).** Restate "`operation()` stays `Fetch`" against the member-derived summary fold (a `Fetch` coordinate carries no DML/Facet member); repoint the `Operation.Facet` precedent onto `OperationMember.Facet` (or the `MEMBER_KNOWN_GAPS` census that now carries the modeled-but-unpopulated Facet arm). A **Ready** item; refresh before pickup. |
| **R382** multitable-interface-query-orderby-lowering | Backlog | `:19` cites `operation()` as the live mechanism hardcoding `new OrderBySpec.None()` for both interface/union arms. Accessor retired (prior window); the seal that backed it is now gone too. | **Re-anchor** the mechanism cite to where the hardcoded `OrderBySpec.None` now lives (the member/fact layer, `OperationMember.OrderBy` sourcing, or `MultiTablePolymorphicEmitter`); verify the ordering gap still reproduces. |
| **R562** synthesised-connection-fields-as-coordinates | Backlog | Carried. `:18` names "the `Operation.Count` and `Operation.Facet` arms of the `OPERATION_ARMS` obligation (`ExemptionRegistry.OPERATION_KNOWN_GAPS`)" as the observable gap. All three names were retired the prior window (arms -> `OperationMember.Count` / `OperationMember.Facet`; obligation `OPERATION_ARMS` -> `MEMBER_ARMS`; map `OPERATION_KNOWN_GAPS` -> `MEMBER_KNOWN_GAPS`), and the item file is unedited since. | **Re-anchor** the three names to their `OperationMember` / `MEMBER_ARMS` / `MEMBER_KNOWN_GAPS` successors. The model question (should the synthesis step register the minted connection type's `totalCount`/`facets` as classified coordinates so a fact carries the count/facet) is intact and still owns both exemption reason strings; note the successor `OperationMember.Count` / `Facet` arms are explicitly "modeled-but-unpopulated", which is exactly this item's subject. |

### C.1 Lookup-triplet dissolution drift (carried; R563 slice 6a)

Slice 6a folded `LookupTableField` / `BatchedLookupTableField` / `QueryLookupTableField`
(and the `LookupField` mixin and `LookupValuesJoinEmitter`) into their Fetch
siblings plus a lookup member (all `grep` = 0). A lookup leaf re-anchors to
`ChildField.BatchedTableField` (or `TableField` / `QueryTableField`) **plus a lookup
member**, not to `BatchedLookupTableField` (itself retired).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R533** localcontext-guard-predicate-single-source | Backlog | `:15` names `BatchedLookupTableField` as the open edge that "routes through the same guarded builder arms but is not admitted by the validator". | **Re-anchor** to the post-dissolution Fetch sibling (`BatchedTableField` + lookup member). The single-sourcing subject (lift the guarded-data-channel verdict into a classifier fact) is intact. |
| **R557** split-query-marker-sweep | Backlog | `:17`'s "consumed (the batched leaves)" enumeration lists `BatchedLookupTableField` alongside `BatchedTableField` / `BatchedPivotField`. | **Re-anchor** the enumeration: drop `BatchedLookupTableField` (now `BatchedTableField` + lookup member). The total-switch-over-classified-leaf design is intact. |

### C.2 `@table`-on-input rejection -> deprecation drift (carried; R566)

R566 made `@table` on an `INPUT_OBJECT` accepted, ignored and warned rather than a
classify-time rejection (`TableOnInputRejection` / `buildNonTableInputType` `grep`
= 0; `BuildWarning.NoRule` per usage). Items describing the old rejection as live
drift.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R565** unclassified-input-arg-cascade-diagnostic | Backlog | Its title and lead (`:13`, `:15-16`, `:30`) present the retired `@table`-on-input **rejection** as "today's live case" driving the misleading `@mutation` arg-shape error. The body already anticipates this (`:34-38`). | **Re-anchor (not full re-spec).** The underlying bug (`resolveDmlWalkerInputArg` conflating "not an input object" with "input did not classify") is real and reachable via any other type-level input rejection. Retitle and re-lead onto a still-current rejection; demote the `@table`-on-input case to historical framing consistent with `:34-38`. |

### C.3 `planSlug` / `SourceKey.Reader` removal drift (carried)

R484 (Done) removed `Rejection.Deferred.planSlug`; R431 (Done) removed the
`SourceKey.Reader` interface. Deferrals anchor by `StubKey.VariantClass`; column
reads off a parent row lift via `KeyLift.FkColumns`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R454** routine-write-result-shapes | Backlog | `:18` names the deferred shapes as "typed `Deferred`s pointing at this item's planSlug"; `planSlug` is gone. | **Re-anchor:** deferred shapes surface via `StubKey.VariantClass`, no roadmap pointer. |
| **R447** routine-chain-fetch-form-breadth | Backlog | `:18`/`:33` "`planSlug` points here"; `:24`/`:26` name `SplitLookupTableField` / `RecordTableField` as live. | **Re-anchor:** drop the `planSlug` phrasing; repoint `RecordTableField` to `BatchedTableField`, and `SplitLookupTableField` to `BatchedTableField` **+ lookup member**. |
| **R180** record-parent-column-read-helper | Spec | `:35` says "R431 ... now In Progress" (R431 is **Done**) and names `SourceKey.Reader.AccessorCall` as a live carrier. | **Re-anchor** the `AccessorCall` carrier onto the decomposed model; fix the R431 tense to Done. |
| **R505** tenant-index-parent-row-routing | Backlog | `:21` names "a column read off the parent row (the `SourceKey.Reader` family)" as the carrier for `ParentRowBound`. Mechanism is live via `KeyLift.FkColumns`. | **Re-anchor** the one parenthetical: per-row column read lifts via `KeyLift.FkColumns`. |

### C.4 Leaf-merge drift: `Split*` / `Record*` -> `Batched*` (carried)

`SplitTableField` / `RecordTableField` merged to `BatchedTableField` (R432);
`SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears. The
lookup twins re-anchor to `BatchedTableField` + a lookup member (slice 6a).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** the planned assertion to `BatchedTableField`. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38`'s R305 lineage note "collapsed it into `RecordTableField`, `ChildField.java:912`" is stale; `:107`/`:148`/`:150`/`:284` cite `LookupValuesJoinEmitter` as the live values-join edit/fork target (deleted slice 6a). | **Re-anchor** the `RecordTableField` name to `BatchedTableField`, and repoint the `LookupValuesJoinEmitter` cites to the render values-join family (`render/LookupRows`, `render/ValuesJoinRowBuilder`, `ProjectionUnitRenderer`'s lookup arm). The positional-alignment subject is intact. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34`: "a keyed batch ... as `SplitTableField` / `RecordTableField` do via `SplitRowsMethodEmitter`". Variant names stale; emitter fine. | **Re-anchor** the two variant names to `BatchedTableField`. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`: the planned `COMPOSITE_KEY_ROW2_PATH_KEYED` case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R7** decompose-typefetchergenerator | Backlog | `:30` proposes a hypothetical `SplitTableFieldEmitter`; `:32` lists `LookupValuesJoinEmitter` as an emitter that already follows the target pattern (deleted slice 6a). | **Low priority:** refresh the illustrative name to `BatchedTableFieldEmitter`; repoint the `LookupValuesJoinEmitter` cite to the render lookup family. |
| **R323** nestingfield-multiparent-batchkey-leaves | Backlog | `:18`/`:30` list `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField` as live classification options / fold leaves. | **Re-anchor** the four names: the plain twins to `BatchedTableField`; the lookup twins to `BatchedTableField` **+ lookup member**. Multi-parent batch-key substance intact. |

### C.5 `ColumnBackedField` dissolution drift (carried; R508)

R508 (Done) merged `ColumnField` / `CompositeColumnField` /
`CompositeColumnReferenceField` into `ColumnBackedField` (`ChildField.java:231`).
The surviving `ColumnField` string hits in main are all `{@code}` javadoc.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | The design doc names the retired carriers as live across many prose regions (`:570`, `:694`, `:706`, `:727`, `:747-748` "`ColumnField` (`ChildField.java:275`)", `:798-799`, `:1112`, `:1140`, `:1145`, `:2009`); only `:1554-1560` self-corrects. | **Re-anchor** the carrier names to `ColumnBackedField` (and its `columns` / `compaction` components) and fix `ChildField.java:275` to `:231`. Part of the single R333 refresh (see §C.7). |

### C.6 `TableInputType` / `resolveInput` removal drift (carried; R519 + R515)

R519 (Done) deleted `GraphitronType.TableInputType`, its type walk and
`buildNonTableInputType`, moving input classification to per-consumer resolution;
R515 (Done) removed `MutationInputResolver.resolveInput`, hoisting its admission set
to `admitMutationInputFields` (live).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R222** dimensional-model-pivot | Spec | `:15`/`:60`/`:442` still name `GraphitronType.TableInputType` as a live sibling root that R519 collapsed; `:62` cites the `LookupField` mixin overlay (folded slice 6a). `:157` proposes "the verb: a **sealed interface `Operation` with `record` arms** (replacing the flat...)", a model R563 in fact delivered differently, as the `OperationMember` member multiset, not a single sealed arm. | **Re-baseline the input-side section** as delivered by R519; **re-baseline the operation-axis section** (`:157`) as delivered by R563 (`OperationMember`, arm-grain `operations:` multiset), narrowing the umbrella to the still-open field-side and `Unclassified*` organs; re-anchor the `LookupField` mixin cite. Per the R563 changelog, R222's operation-axis content is discharged, so this section is now a delivered-model rewrite, not open design. |
| **R213** input-field-rejection-attribution | Backlog | `:64` scope note: "`@table` input types route through `TableInputType` classification ... `UnclassifiedType`". Both gone (and, per R566, `@table`-input no longer rejects at all). | **Re-anchor** the one scope-note sentence to per-consumer resolution; state the `@table`-input out-of-scope boundary without the retired path. Core subject and `InputFieldResolver` cites valid. |
| **R234** jooq-embedded-and-udt-input-backings | Spec | `:15` cites `TypeBuilder.buildNonTableInputType` (`grep` = 0) as the live dispatch to extend; the sibling arms it extends (`JooqRecordInputType` / `JooqTableRecordInputType`) are live. | **Re-anchor** the dispatch site to the current `TypeBuilder` input-classification path (`buildInputType`); the design is intact. |
| **R257** updaterows-walker-sdl-substrate | Backlog | `:17` calls `resolveInput` "the legacy resolver" (gone R515); `:15`/`:19` reach the admitted carriers "via `TableInputType.inputFields()`" (gone R519). | **Re-anchor** both dead names: `resolveInput` to `admitMutationInputFields`, `TableInputType.inputFields()` to per-consumer input resolution. |
| **R337** input-nesting-projection-classification | Backlog | `:30` reaches an input object's own fields "via ... `TableInputType.inputFields()`" as the LSP-hover mechanism; that walk is gone (R519). | **Re-anchor** the one mechanism cite to per-consumer input resolution; the LSP feature scope is intact. |
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot composition "in `MutationInputResolver.resolveInput`"; the method is gone, the composition is live in `admitMutationInputFields`. | **Re-anchor** the one sentence to `admitMutationInputFields`. |

### C.7 Condition + projection emitter dissolution drift (carried; R552 + R549)

R552 deleted `TypeConditionsGenerator`; R549 slice 3.1 deleted `TypeClassGenerator`
+ `collectRequiredProjection`; R549 slice 7 deleted `ParentProjectionContainmentCheck`
and the `methodgraph` package. Condition emission is `render/ConditionGlueRenderer`;
projection is `render/ProjectionUnitRenderer`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh still incomplete** (unchanged from the prior audit; the item file was not edited this window). Crosswalk rows are current, but prose and the baseline table are not: `:529` names `TypeConditionsGenerator`; `:1605`/`:1626`/`:1628`/`:1681`/`:1749`/`:1963`/`:1987` name `TypeClassGenerator` / `collectRequiredProjection`; `:1674` cites `BatchKeyField.rowsMethodName()`; `:341` cites `OutputField.operation()`; `:1791` cites `ParentProjectionContainmentCheck`; `:1826`/`:2054`/`:2058`/`:2064` cite `methodgraph`; `:1754`/`:1775` cite `LookupValuesJoinEmitter`; `:157` names the `Operation` **seal** as a live sealed interface and `:1512` cites "the shipped 17-arm `Operation` seal the classifier computes today". | **Finish the refresh in one pass** (this row + §C.0 + §C.5 + §C.6): repoint `TypeClassGenerator` / `collectRequiredProjection` to `ProjectionUnitRenderer` / `ProjectionCommands`; drop the `TypeConditionsGenerator`, `BatchKeyField.rowsMethodName`, `OutputField.operation()`, `ParentProjectionContainmentCheck` and `methodgraph` cites; re-anchor `LookupValuesJoinEmitter` to the render lookup family; and re-anchor the `Operation` seal / "17-arm" cites to `OperationMember`. Rows citing `SplitRowsMethodEmitter` stay (live). |
| **R231** emit-text-mapped-enum-fields-as-enum-type | Backlog | `:39` locates "the field-type-emit fork (likely in `TypeClassGenerator` or ...)"; deleted by R549 slice 3.1. | **Re-anchor** the investigation locus to `ProjectionUnitRenderer` / `plan/ProjectionCommands`. |
| **R35** source-orientation-javadocs | Backlog | `:42` enumerates classes needing a class-level javadoc sweep, including `TypeClassGenerator` and `TypeConditionsGenerator`, both deleted. | **Re-anchor** the enumeration: drop the two deleted names, optionally adding the successor renderers. Low priority; the sweep scope is illustrative. |

## D. Structural: (0)

Empty of blocking defects. `changelog.md` carries `next-id: R597`, clearing the max
allocated id (R596). No duplicate `id:`, no `status: Done` tombstones in
`roadmap/*.md`, and a `depends-on:` sweep over all 175 item files resolves every
edge (all slug-based) to a present file. The three items filed this window (R594-R596)
carry well-formed front-matter and were read against the current model.

One **pre-existing, non-blocking** hygiene nit: four **legacy** items lack a
`bucket:` key, all pre-dating this window: R242 (`dml-payload-positional-alignment`),
R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
R180 (`record-parent-column-read-helper`). Each has `id:` and `status:`, and the
roadmap-tool tolerates the omission (the build is green), so this is a backlog-hygiene
note, not a §D blocker; fold a `bucket:` in whenever each item is next edited.

## Cross-cutting observations

1. **A roadmap-only window freezes the drift set, and the audit's job narrows to
   proving it.** The single determining fact this window is that no main source
   moved: `git diff --name-only fda241e..HEAD` is entirely under `roadmap/`. Every
   §B / §C flag is a claim about a retired main-source symbol, so none can have
   changed, and the audit reduces to (a) confirming each driving symbol is still
   `grep` = 0 with its successor live, (b) confirming no flagged item file was
   edited into or out of a fix, and (c) classifying what the window *did* add:
   new items and in-place spec revisions. All three were done; the 30 flags carry
   verbatim. The value of a quiet window is that it is cheap to certify and lets
   the next active window start from a known-clean baseline.

2. **Born-current items are the cheap case, and this window produced three of
   them.** R594, R595 and R596 were all filed after the R563 dissolution, read
   against the post-dissolution model, and scanned clean of every retired symbol.
   A born-current item costs the audit only a classification pass (none flagged);
   the expensive items are always the ones filed *before* a programme retired the
   name they hold. The lesson from the prior window's `Operation`-seal blast radius
   is the mirror of this: drift is a function of when an item was written relative
   to the retirements it cites, so items written into a stable model stay clean.

3. **A spec maturing in place is not drift, even a large one.** R589
   (`validation-adds-facts`, Spec) took the window's largest single edit (+192
   lines) and spun out its substrate as R595, yet neither is flagged: R589 is
   actively-drafted Spec prose describing the fact-base it is designing, and R595
   describes the module it mints. Their prose **is the work**, the same reason
   R580 (In Review) and R347 (In Progress) are never flagged. The two spike-audit
   siblings they cite are grounding records, not staleness reviews, and stay in
   place.

4. **R333's mid-programme refresh spans five drivers and is still partial,
   unchanged from the prior audit.** Its crosswalk rows track the renderers, but
   its prose and baseline table lag across §C.0 (the `Operation` seal), §C.5
   (column), §C.6 (input), §C.7 (condition/projection) and §C.1 (lookup). Nothing
   this window touched it, so it is exactly as internally inconsistent as it was,
   and should be refreshed in a single pass before pickup. It is **Ready**, so the
   refresh is overdue.

5. **The Ready set is where stale prose bites soonest.** R333, R427, R555 and
   now R584 are the items an implementer picks up next, so their stale cites (R333
   across five drivers, R427's superseded `Operation.Facet` precedent) are the
   highest-leverage refreshes on the board even in a window that added no new
   drift. R584 arrived at Ready this window born-current, so it does not add to
   that debt; R333 and R427 remain the two Ready items carrying flags.

---

_Review date: 2026-08-06._
