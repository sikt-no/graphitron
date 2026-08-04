# Roadmap staleness audit: 2026-08-04

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `d1e4151`, committed 2026-08-03 17:17, audited 2026-08-04). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-08-03` staleness audit, which has been deleted;
only the latest staleness audit is retained. Five siblings in this directory are
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

`classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
inventory; its "closed and historical" banner is intact. No action; it stays as
lineage.

## Headline: the operation-relation programme kept dissolving (R563 landed slices 5, 6a, 6b, 6c), retiring the lookup triplet, the DML verb split and the routine chain field; a reopened `@table`-on-input deprecation window (R566) and a name removal (R570) each drove fresh drift; four items ran to Done; three new flags enter §C and the prior audit's own lookup re-anchor advice is superseded

The window was dominated by continued generator surgery under the in-progress
operation-relation programme plus four items reaching Done:

- **R563** (`operation-relation`, In Progress) landed **four more slices**: slice
  5 (both halves, the delivery fact and back-half membership re-source), slice 6a
  (the **lookup triplet dissolves**), slice 6b (the **DML verb split dissolves**),
  and slice 6c (the **pivot and routine operation halves dissolve**). Slice 6a is
  this window's principal drift driver.
- **R566** (`table-on-input-reopen-deprecation-window`) went **Spec -> Ready -> In
  Progress -> In Review -> Done**, reopening the `@table`-on-input deprecation
  window: the location is no longer a classify-time rejection; `@table` on an
  `INPUT_OBJECT` is now accepted, ignored, and announced per usage as a
  `BuildWarning.NoRule` advisory. This **inverts** the rejection framing several
  items lean on.
- **R570** (`external-code-reference-name-removal`) went to **Done**, deleting the
  `ExternalCodeReference.name` accessor, the `NamedReferenceBinding` class and the
  `namedReferences` parameter.
- **R564** (`javadoc-gate-output-timestamp`, a `verify`-phase build fix) and
  **R571** (`lsp-request-path-tracing`, the LSP trace seam) also went to **Done**.
  All four Done items were **filed and completed inside this single window**; none
  was a prior flag carrier.

The retirements that drive drift, verified at the symbol:

- **Slice 6a (lookup triplet).** `LookupTableField`, `BatchedLookupTableField`,
  `QueryLookupTableField` (leaf records), `LookupField` (the mixin overlay) and
  `LookupValuesJoinEmitter` (the values-join generator) are **all `grep` = 0** in
  main. They fold into their Fetch siblings (`ChildField.TableField`,
  `ChildField.BatchedTableField`, `QueryField.QueryTableField`) plus a lookup
  member; lookup SQL now renders from `render/LookupRows` + `render/ValuesJoinRowBuilder`
  + `ProjectionUnitRenderer`'s lookup-multiset arm. This retirement **supersedes the
  prior audit's own re-anchor advice**, which told several items to re-point at
  `BatchedLookupTableField`, a name that no longer exists (see §C.1 and the
  cross-cutting note).
- **Slice 6b (DML verb split).** `DeleteRowsField` and `UpdateRowsField` are `grep`
  = 0; the write payload landed on the record carriers. The `DmlTableField` model
  leaf **survives** (`MutationField.java:73`). No external item cites the two
  deleted names (only `changelog.md` and R563's own programme doc), so slice 6b
  produces **no roadmap drift**.
- **Slice 6c (routine chain).** `RoutineChainField` is `grep` = 0. Cited only by
  R563 and `changelog.md`; **no roadmap drift**.
- **R566 (`@table`-on-input).** `TableOnInputRejection` (the deleted test) and
  `buildNonTableInputType` are `grep` = 0; `TypeBuilder.buildInputType` falls
  through to the plain path; `BuildWarning.NoRule` is the new per-usage advisory.
  Drives §C.2.
- **R570 (`ExternalCodeReference.name`).** The `.name` accessor, `namedReferences`
  parameter and `NamedReferenceBinding` class are all `grep` = 0 in main; the only
  surviving mentions are javadoc lines describing the retirement. The two new items
  that name it (R578, R579) cite it correctly as **retired context**, not as a
  live mechanism, so neither is born-stale.

Net: **0 §A / 5 §B / 24 §C**, §D empty. Flag total moves to **29**, up three from
the prior window's 26. Composition: **§B carries R462, R545, R85, R221, R71
unchanged** (all re-verified, premise-targets still gone); **§C carries all 21
prior flags** (none fixed, none resurrected) and **gains R533, R557 and R565** on
this window's retirements, with fresh lookup-dissolution drift layered onto four
already-flagged items (R242, R7, R333, R222).

## Changes since the 2026-08-03 audit

**49 commits** landed between the prior audit's landing (`7bb9794`, 2026-08-03
07:23) and this HEAD (`d1e4151`, 2026-08-03 17:17); the calendar window is under a
day but the commit volume is high. (The prior audit recorded its review HEAD as a
feature-branch SHA, `702c7f8`, that never appeared in trunk; it landed in trunk as
`7bb9794`, which this window measures from.)

**Four items ran to Done:** R564, R566, R570, R571, each **filed and completed in
this window** (Backlog/Spec -> In Review -> Done, spec file deleted, changelog
entry appended). None was a flag carrier.

**Two ids born and folded:** R575 (`lsp-trace-clock-and-editor-channel`) and R576
(`lsp-trace-residual-cleanups`) were filed as LSP-trace carve-outs during R571's In
Review, then **folded back into R571** as rework (`bf3d987`); their files were
deleted. The R575/R576 numbering gap is this fold, not a discard or a board
problem.

**No prior flagged item was discarded or resolved this window.** No prior flag's
stale cite was fixed in place, so none leaves §B or §C.

**Eleven new items filed and still on the board** (all read against the current
model; all born-current except R565's inverted lead, below): R565
(`unclassified-input-arg-cascade-diagnostic`), R567 (`lookup-unrealized-co-members`),
R568 (`javadoc-gate-incremental-skip`), R569 (`mcp-aggregated-diagnostics`, Spec),
R572 (`consumer-dependency-version-warning`, Spec), R573 (`natives-module-archiver-override`),
R574 (`table-on-input-deprecation-sweep-residuals`), R577 (`validation-error-coordinate-sealed`),
R578 (`unknown-input-field-diagnostic-severity`), R579 (`parse-external-ref-unused-parent-type`),
R580 (`implicit-node-metadata-missing-node-diagnostic`). R567 and R574 correctly
anchor on the post-dissolution / post-deprecation model. R569 is notably
self-aware: it flags and corrects its own `@table`-on-input-as-rejection example
against the reopened window.

**Other transitions:** R467 (`upgrade-graphql-java-26`) Backlog -> Spec
(graphql-java 26 unblocked by federation 7.0.0); R569 and R572 Backlog -> Spec.
Ready set unchanged (R333, R427, R555); In Progress unchanged in membership (R347,
R563), R563 having landed four slices.

**Board accounting.** **162 item files** today (`roadmap/*.md` carrying `id:`), up
eleven net from the prior audit's 151: eleven new items filed (+11), four filed and
Done inside the window (net 0 on the board), R575/R576 born and folded (net 0).
Status distribution: **143 Backlog, 14 Spec, 3 Ready, 2 In Progress, 0 In Review,
0 Done**. A non-recursive `^status: Done` grep over `roadmap/*.md` returns nothing
(tombstone-free for the twenty-fifth window running). No duplicate `id:`; max
allocated id **R580**, and `changelog.md` carries `next-id: R581`, clearing it. A
`depends-on:` sweep over all 162 item files resolves every edge (all are slug-based)
to a present file. The board is structurally clean; the only nit is four **legacy**
items missing a `bucket:` key (§D), all pre-dating this window.

**Net effect on flag counts: 29 flagged, 133 current.** 0 §A, 5 §B, 24 §C, 0 §D.

## Scope and method

All **162** `R<n>` item files were reviewed (plus the non-item placeholders
`inference-axis-coverage.adoc`, `relevance-ranked-search-howto.adoc`,
`relevance-ranked-search-oracle-howto.adoc` and the permanent `workflow.adoc`, all
correctly excluded: no `R<n>`, and `.adoc` besides). Because this window did
generator surgery (four R563 slices, the R566 deprecation reopen, the R570 name
removal), every stale-live cite below was re-checked against a fresh `grep` of the
main sources rather than carried on the prior audit's word; that re-verification
confirmed the prior audit's own lookup re-anchor target had itself been retired.

**This window's symbol changes, verified at the symbol:**

- **Lookup triplet + `LookupField` + `LookupValuesJoinEmitter`, RETIRED (R563
  slice 6a, `98e3623`/`043f13e`).** All `grep` = 0 in main. Successor Fetch
  siblings `ChildField.TableField` (`:388`), `ChildField.BatchedTableField`
  (`:456`), `QueryField.QueryTableField` (`:71`) carry a lookup member; lookup SQL
  renders from `render/LookupRows`, `render/ValuesJoinRowBuilder`, and
  `ProjectionUnitRenderer`'s lookup-multiset arm (`CallWrap.LookupMultiset`,
  `LauncherCommands.batchedLookupRow`). Drives §C.1 and layers new drift onto §C.3
  / §C.4 / §C.7 carriers.
- **`DeleteRowsField` / `UpdateRowsField`, RETIRED (R563 slice 6b); `DmlTableField`
  model leaf, still LIVE.** Deleted names `grep` = 0; `DmlTableField` survives at
  `MutationField.java:73`. No roadmap drift.
- **`RoutineChainField`, RETIRED (R563 slice 6c).** `grep` = 0. No roadmap drift.
- **`@table`-on-input rejection, RETIRED; deprecation window REOPENED (R566).**
  `TableOnInputRejection` and `buildNonTableInputType` `grep` = 0;
  `TypeBuilder.buildInputType` falls through to the plain path; the directive is
  accepted, ignored and warned via `BuildWarning.NoRule`. Drives §C.2.
- **`ExternalCodeReference.name` accessor + `namedReferences` parameter +
  `NamedReferenceBinding` class, RETIRED (R570).** All `grep` = 0 in main; only
  retirement-note javadoc remains. No item cites it as live; R578/R579 name it as
  retired context. No roadmap drift.

**Retired symbols from prior windows, re-verified still retired (no
resurrections):** `OutputField.operation()` accessor, `CompileDependencyGraphBuilder`,
`RowsMethodBody` / `RowsMethodSkeleton`, `QueryConditionsGenerator` /
`TypeConditionsGenerator`, `TypeClassGenerator` / `collectRequiredProjection`,
`ParentProjectionContainmentCheck` / the `methodgraph` package,
`GraphitronType.TableInputType` / `buildNonTableInputType`,
`MutationInputResolver.resolveInput` (with `admitMutationInputFields` live),
`SourceKey.Reader`, `Rejection.Deferred.planSlug`, the `Split*` / `Record*` leaf
names (merged to `Batched*`), and the `ColumnField` family (merged to
`ColumnBackedField`). All `grep` = 0 in main; each drives a carried §C flag below.

## A. Obsolete: should leave the active roadmap (0)

Empty. No item's entire premise was invalidated to removal this window. R462's
premise **was** invalidated in a prior window (its target class
`CompileDependencyGraphBuilder` is deleted and its stated dissolution condition has
occurred), but the safe call remains re-derivation at pickup rather than a blind
discard, so it sits in §B with discard as the likely outcome. R221 and R71 remain
similar re-derivation calls. R520 (`table-on-input-removal-housekeeping`), re-scoped
this window to the deprecation-not-rejection model, is a coherent
deliberately-deferred docs tail, not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (5)

All five carried from the prior window, re-verified at the symbol: each item's
premise-target is still `grep` = 0 in main with a live successor, and none of this
window's retirements altered the set. Prior recommended actions all still hold; the
only addition is a supplementary note on R221.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R462** nested-fetcher-outgoing-field-edges | Spec | Carried. Title and central mechanism target `CompileDependencyGraphBuilder.addFieldEdges`, deleted by R549 slice 7 (`grep` = 0); the recompile graph is a typed projection over the plan, and the body's own stated dissolution condition (`:163`) has occurred. (Its `LookupTableField` cite at the leaf-classification level also drifted this window, but folds into the re-spec.) | **Re-derive against the plan-projected recompile graph.** Confirm at the symbol whether the nested-fetcher outgoing per-field edge (the `FilmMeta` case) is now correctly modeled under `EmitPlan`. If closed (likely), **discard** and record it; if a residue survives, **re-spec** onto the plan-projection. |
| **R545** model-free-of-emit-vocabulary | Backlog | Carried. `RowsMethodBody` / `RowsMethodSkeleton` deleted (`grep` = 0 each), so the whole `RowsMethodBody` diagnosis and the **second deliverable** are gone. The **first deliverable survives**: `ClassName` (~1700 lines, 23 model files) and `TypeName` (28 model files) are still model-pervasive. | **Re-spec.** Drop the `RowsMethodBody` diagnosis and the second deliverable entirely. Keep and re-baseline the first deliverable (`JavaTypeRef` replacing `TypeName` / `ClassName`) against the post-R549 model. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Carried. Both named edit-target generators are deleted: `QueryConditionsGenerator` (gone R552) and `TypeClassGenerator` (gone R549 slice 3.1); every `QueryConditionsGenerator.java:NNN` cite is dead. Condition emission moved to `render/ConditionGlueRenderer`, projection to `render/ProjectionUnitRenderer`. | **Re-derive against the new `render/` layer.** Determine whether `ConditionGlueRenderer` / `ProjectionUnitRenderer` still exhibit the duplicated helper-emission problem; if so, re-spec onto them. Drop every dead `QueryConditionsGenerator.java:NNN` cite. |
| **R221** validator-walks-plain-input-unbound-fields | Backlog | Carried, with a **new reinforcing driver**. R519 deleted `TableInputType` / `validateTableInputType` (`grep` = 0), replacing them with per-consumer `collectInputFieldRejections` (live, `GraphitronSchemaValidator.java:524`). **This window compounds it:** R566 made `@table`-on-input no longer produce a distinct table-input classification at all, so the item's "a `@table` input routes through the existing walker" framing (`:14`) is now doubly obsolete. | **Re-derive against the post-R519 model.** If per-consumer resolution already validates plain-input `UnboundField + @condition(override:false)` shapes, **discard** (gap closed); else **re-spec** around `collectInputFieldRejections`. Also re-baseline the `@table`-input premise for the accept-and-warn behavior. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried. R431 (Done) deleted the target surface: the body cites `SourceKey.java` line ranges and `SourceKey.Reader.SourceRowsCall` as "the live surface", but `SourceKey` is a plain record with no `Reader` (the one `grep` hit is a retirement comment). Successor `KeyLift` is live (~140 hits); `LifterRef` / sealed `Wrap` present. The sequencing line still reads "R431 ... plans to decompose"; R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model; drop the stale `SourceKey.Reader.SourceRowsCall` re-anchor note and the "R431 plans to decompose" tense. The goal (recordN key parity, non-jOOQ record parents) is intact. |

## C. Outdated: update references only (work valid, refs stale) (24)

Substance intact; names and line numbers drifted. All 21 prior flags carried (every
driving symbol re-verified still `grep` = 0, none fixed in place); **R533, R557 and
R565 enter** on this window's retirements; four carried items (R242, R7, R333, R222)
pick up **additional** lookup-dissolution drift on top of their existing flag.

### C.0 `operation()` summary-accessor drift (carried; R563 slice 4, prior window)

The `operation()` leaf accessor is still `grep` = 0; the `Operation` seal and its
arms are live, so an item citing an `Operation.<Arm>` **type** is correct and only
the retired **accessor** and stale line numbers drift.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R382** multitable-interface-query-orderby-lowering | Backlog | `:19` cites the retired `operation()` leaf accessor as the live mechanism hardcoding `OrderBySpec.None` for both interface/union arms. | **Re-anchor** the mechanism cite to where the hardcoded `OrderBySpec.None` now lives (the member/fact layer or `MultiTablePolymorphicEmitter`); verify the ordering gap still reproduces. |
| **R427** relevance-ranked-search | Ready | `:335` "`operation()` stays `Fetch`" cites the retired accessor; `:340-341` cites `Operation.Facet` at `Operation.java:89-93` (arm live but now `:72`). | **Re-anchor:** restate the "stays `Fetch`" point against the member-derived summary fold (`DimensionTuple.summaryArmOf`); fix the `Operation.Facet` line cite to `:72`. A Ready item; refresh before pickup. |

### C.1 Lookup-triplet dissolution drift (new this window; R563 slice 6a)

Slice 6a folded `LookupTableField` / `BatchedLookupTableField` / `QueryLookupTableField`
(and the `LookupField` mixin and `LookupValuesJoinEmitter`) into their Fetch
siblings plus a lookup member (all five `grep` = 0). **This supersedes the prior
audit's re-anchor advice**, which pointed the lookup half of the leaf-merge cluster
at `BatchedLookupTableField`: that name is now itself retired, so a lookup leaf
re-anchors to `ChildField.BatchedTableField` (or `TableField` / `QueryTableField`)
**plus a lookup member**, not to `BatchedLookupTableField`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R533** localcontext-guard-predicate-single-source | Backlog | `:15` names `BatchedLookupTableField` as the open edge that "routes through the same guarded builder arms but is not admitted by the validator". The name dissolved this window. | **Re-anchor** to the post-dissolution Fetch sibling (`BatchedTableField` + lookup member). The single-sourcing subject (lift the guarded-data-channel verdict into a classifier fact) is intact. |
| **R557** split-query-marker-sweep | Backlog | `:17`'s "consumed (the batched leaves)" enumeration lists `BatchedLookupTableField` alongside `BatchedTableField` / `BatchedPivotField`. The lookup leaf folded in. | **Re-anchor** the enumeration: drop `BatchedLookupTableField` (now `BatchedTableField` + lookup member). The total-switch-over-classified-leaf design is intact. |

### C.2 `@table`-on-input rejection -> deprecation drift (new this window; R566)

R566 made `@table` on an `INPUT_OBJECT` accepted, ignored and warned rather than a
classify-time rejection (`TableOnInputRejection` / `buildNonTableInputType` `grep`
= 0; `BuildWarning.NoRule` per usage). Items describing the old rejection as live
drift. (R520 was correctly re-scoped to this model and needs no action; R569's own
stale example is already self-corrected in its body; R213 / R234's `TableInputType`
cites are carried under §C.6.)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R565** unclassified-input-arg-cascade-diagnostic | Backlog | Its title and lead (`:13`, `:15-16`, `:30`) present the retired `@table`-on-input **rejection** as "today's live case" driving the misleading `@mutation` arg-shape error. The directive no longer rejects. **The body already anticipates this** (`:34-38`: the conflation survives on its own merits once the trigger is gone). | **Re-anchor (not full re-spec).** The underlying bug (`resolveDmlWalkerInputArg` conflating "not an input object" with "input did not classify") is real and reachable via any other type-level input rejection. Retitle and re-lead onto a still-current rejection; demote the `@table`-on-input case to historical framing consistent with `:34-38`. |

### C.3 `planSlug` / `SourceKey.Reader` removal drift (carried)

R484 (Done) removed `Rejection.Deferred.planSlug`; R431 (Done) removed the
`SourceKey.Reader` interface. Deferrals anchor by `StubKey.VariantClass`; column
reads off a parent row lift via `KeyLift.FkColumns`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R454** routine-write-result-shapes | Backlog | `:18` names the deferred shapes as "typed `Deferred`s pointing at this item's planSlug"; `planSlug` is gone. | **Re-anchor:** deferred shapes surface via `StubKey.VariantClass`, no roadmap pointer. |
| **R447** routine-chain-fetch-form-breadth | Backlog | `:18`/`:33` "`planSlug` points here"; `:24`/`:26` name `SplitLookupTableField` / `RecordTableField` as live. | **Re-anchor:** drop the `planSlug` phrasing; repoint `RecordTableField` to `BatchedTableField`, and `SplitLookupTableField` to `BatchedTableField` **+ lookup member** (not `BatchedLookupTableField`, which dissolved this window). |
| **R180** record-parent-column-read-helper | Spec | `:35` says "R431 ... now In Progress" (R431 is **Done**) and names `SourceKey.Reader.AccessorCall` as a live carrier. | **Re-anchor** the `AccessorCall` carrier onto the decomposed model; fix the R431 tense to Done. |
| **R505** tenant-index-parent-row-routing | Backlog | `:21` names "a column read off the parent row (the `SourceKey.Reader` family)" as the carrier for `ParentRowBound`. Mechanism is live via `KeyLift.FkColumns`. | **Re-anchor** the one parenthetical: per-row column read lifts via `KeyLift.FkColumns`. |

### C.4 Leaf-merge drift: `Split*` / `Record*` -> `Batched*` (carried; lookup half re-corrected)

`SplitTableField` / `RecordTableField` merged to `BatchedTableField` (R432);
`SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears. The
lookup twins that R432 merged to `BatchedLookupTableField` dissolved a second time
this window (slice 6a), so a lookup re-anchor now lands on `BatchedTableField` + a
lookup member.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** the planned assertion to `BatchedTableField`. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38`'s R305 lineage note "collapsed it into `RecordTableField`, `ChildField.java:912`" is doubly stale; **new this window**, `:107`/`:148`/`:150`/`:284` cite `LookupValuesJoinEmitter` as the live values-join edit/fork target, deleted in slice 6a. | **Re-anchor** the `RecordTableField` name to `BatchedTableField`, and repoint the `LookupValuesJoinEmitter` cites to the render values-join family (`render/LookupRows`, `render/ValuesJoinRowBuilder`, `ProjectionUnitRenderer`'s lookup arm). The positional-alignment subject is intact. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34`: "a keyed batch ... as `SplitTableField` / `RecordTableField` do via `SplitRowsMethodEmitter`". Variant names stale; emitter fine. | **Re-anchor** the two variant names to `BatchedTableField`. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`: the planned `COMPOSITE_KEY_ROW2_PATH_KEYED` case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R7** decompose-typefetchergenerator | Backlog | `:30` proposes a hypothetical `SplitTableFieldEmitter`; **new this window**, `:32` lists `LookupValuesJoinEmitter` as an emitter that already follows the target pattern, deleted in slice 6a. | **Low priority:** refresh the illustrative name to `BatchedTableFieldEmitter`; repoint the `LookupValuesJoinEmitter` cite to the render lookup family. |
| **R323** nestingfield-multiparent-batchkey-leaves | Backlog | `:18`/`:30` list `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField` as live classification options / fold leaves. | **Re-anchor** the four names: the plain twins to `BatchedTableField`; the lookup twins to `BatchedTableField` **+ lookup member** (not `BatchedLookupTableField`). Multi-parent batch-key substance intact. |

### C.5 `ColumnBackedField` dissolution drift (carried; R508)

R508 (Done) merged `ColumnField` / `CompositeColumnField` /
`CompositeColumnReferenceField` into `ColumnBackedField` (`ChildField.java:231`).

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
| **R222** dimensional-model-pivot | Spec | `:15`/`:60`/`:442` still name `GraphitronType.TableInputType` as a live sibling root that R519 in fact collapsed (line numbers drifted from the prior audit's `:46`/`:428`; content intact). **New this window**, `:62` cites the `LookupField` mixin overlay as a live cross-cutting trait, folded in slice 6a. | **Re-baseline the input-side section** as delivered by R519; narrow the umbrella to the field-side and `Unclassified*` organs. **Also** re-anchor the `LookupField` mixin cite (the lookup trait folded into the operation-relation retirement). |
| **R213** input-field-rejection-attribution | Backlog | `:64` scope note: "`@table` input types route through `TableInputType` classification ... `UnclassifiedType`". Both gone (and, per R566, `@table`-input no longer rejects at all). | **Re-anchor** the one scope-note sentence to per-consumer resolution; state the `@table`-input out-of-scope boundary without the retired path. Core subject and `InputFieldResolver` cites valid. |
| **R234** jooq-embedded-and-udt-input-backings | Spec | `:15` cites `TypeBuilder.buildNonTableInputType` (`grep` = 0) as the live dispatch to extend; the sibling arms it extends (`JooqRecordInputType` / `JooqTableRecordInputType`) are live. | **Re-anchor** the dispatch site to the current `TypeBuilder` input-classification path (`buildInputType`, `:1587`); the design is intact. |
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
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh still incomplete.** Crosswalk rows are current, but prose and the baseline table are not: `:529` names `TypeConditionsGenerator`; `:1605`/`:1626`/`:1628`/`:1681`/`:1749`/`:1963`/`:1987` name `TypeClassGenerator` / `collectRequiredProjection`; `:1674` cites the retired `BatchKeyField.rowsMethodName()`; `:341` cites `OutputField.operation()`; `:1791` cites `ParentProjectionContainmentCheck`; `:1826`/`:2054`/`:2058`/`:2064` cite `methodgraph`; and **new this window** `:1754`/`:1775` cite `LookupValuesJoinEmitter`. | **Finish the refresh in one pass** (this row + §C.0 + §C.5): repoint `TypeClassGenerator` / `collectRequiredProjection` to `ProjectionUnitRenderer` / `ProjectionCommands`; drop the `TypeConditionsGenerator`, `BatchKeyField.rowsMethodName`, `OutputField.operation()`, `ParentProjectionContainmentCheck` and `methodgraph` cites; re-anchor `LookupValuesJoinEmitter` to the render lookup family. Rows citing `SplitRowsMethodEmitter` stay (live). |
| **R231** emit-text-mapped-enum-fields-as-enum-type | Backlog | `:39` locates "the field-type-emit fork (likely in `TypeClassGenerator` or ...)"; deleted by R549 slice 3.1. | **Re-anchor** the investigation locus to `ProjectionUnitRenderer` / `plan/ProjectionCommands`. |
| **R35** source-orientation-javadocs | Backlog | `:42` enumerates classes needing a class-level javadoc sweep, including `TypeClassGenerator` and `TypeConditionsGenerator`, both deleted. | **Re-anchor** the enumeration: drop the two deleted names, optionally adding the successor renderers. Low priority; the sweep scope is illustrative. |

## D. Structural: (0)

Empty of blocking defects. `changelog.md` carries `next-id: R581`, clearing the max
allocated id (R580). No duplicate `id:`, no `status: Done` tombstones in
`roadmap/*.md`, and a `depends-on:` sweep over all 162 item files resolves every
edge (all slug-based) to a present file. The eleven items filed this window carry
well-formed front-matter and were read against the current model.

One **pre-existing, non-blocking** hygiene nit: four **legacy** items lack a
`bucket:` key, all pre-dating this window: R242 (`dml-payload-positional-alignment`),
R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
R180 (`record-parent-column-read-helper`). Each has `id:` and `status:`, and the
roadmap-tool tolerates the omission (the build is green), so this is a backlog-hygiene
note, not a §D blocker; fold a `bucket:` in whenever each item is next edited.

## Cross-cutting observations

1. **A fast-moving programme invalidated the prior audit's own fix instructions.**
   The prior audit told the lookup half of the leaf-merge cluster (R447, R323, and
   by implication R533/R557 had they been flagged) to "re-anchor to
   `BatchedLookupTableField`". R563 slice 6a then dissolved `BatchedLookupTableField`
   itself, so that instruction now points at a retired name. The lesson mirrors the
   prior window's R462 miss, one level up: when a programme is mid-dissolution, an
   audit's re-anchor **targets** can go stale as fast as the item cites they
   correct, so a re-anchor should name the durable successor axis (a Fetch sibling
   plus a lookup member) rather than the intermediate merged name.

2. **A retirement inverted a born-this-window item's own premise.** R565 was filed
   naming the `@table`-on-input **rejection** as its motivating example, and R566
   removed that rejection **in the same window**. The item survives only because its
   body already anticipated the reopen and re-grounded the bug on its own merits.
   This is the counterpart to last window's R427 lesson: a cutover that deletes a
   widely-cited trigger should sweep not just the Ready set but any item filed
   against the old behavior earlier in the same window.

3. **The DML and routine dissolutions were self-contained; the lookup dissolution
   was not.** Slices 6b and 6c retired `DeleteRowsField`, `UpdateRowsField` and
   `RoutineChainField` with **zero** external roadmap drift (only R563 and the
   changelog cite them), because those names never leaked into sibling item specs.
   Slice 6a's lookup names had leaked widely (nine items), so the same kind of
   surgery produced six §C touch-points. The tell of blast radius is not the size
   of the retirement but how many specs already lean on the retired name.

4. **R333's mid-programme refresh now spans four drivers and is still partial.** Its
   crosswalk rows track the renderers, but its prose, baseline table, and now a
   fresh `LookupValuesJoinEmitter` cite lag across §C.0 (operation), §C.5 (column),
   §C.7 (condition/projection) and §C.1 (lookup). The item is internally
   inconsistent and should be refreshed in a single pass before pickup, not
   incrementally at each neighbouring gate.

5. **A programme's own prose is not drift.** R563 (In Progress) and R347
   (`lsp-structural-consolidation`, In Progress) describe the very subjects they
   retire and mint; their prose is the work, not consumer-facing stale-live, and
   they are not flagged. The retired `DeleteRowsField` / `UpdateRowsField` /
   `RoutineChainField` names surface only in R563's file and `changelog.md`,
   exactly the expected non-drift habitat.

---

_Review date: 2026-08-04._
