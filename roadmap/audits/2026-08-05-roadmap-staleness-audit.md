# Roadmap staleness audit: 2026-08-05

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `fda241e`, committed 2026-08-04 21:12, audited 2026-08-05). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-08-04` staleness audit, which has been deleted;
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

`classification-test-dsl-inventory.md` is the permanent corpus-retirement
inventory; its "closed and historical" banner is intact. No action; it stays as
lineage.

## Headline: the operation-relation programme finished (R563 In Progress -> Done, landing slices 7 and 8), retiring the entire 17-arm `Operation` seal and inverting the prior audit's own §C.0 premise that "the `Operation` seal and its arms are live"; three items reached Done and one is In Review, the node-identity work shipped without roadmap drift, one new flag enters §C, and four already-flagged items pick up Operation-seal drift

The window was dominated by **R563 reaching Done**: the operation-relation
programme that had been landing slices for several windows finished, taking its
last two slices with it and retiring the construct several items still cite as
live.

- **R563** (`operation-relation`) went **In Progress -> In Review -> Ready
  (gate rework) -> In Progress -> In Review -> Done**. Slice 7 re-grained the
  corpus voice to member-list assertions and **retired the 17-arm `Operation`
  seal outright** (`@classified(operation:)` became a required arm-grain
  `operations:` multiset); slice 8 re-typed the obligation. Output leaves fell
  51 -> 40. This is the window's principal drift driver: the prior audit's §C.0
  explicitly rested on "the `Operation` seal and its arms are live, so an item
  citing an `Operation.<Arm>` **type** is correct", and that premise is now
  false.
- **R581** (`nodeid-typename-first-decode`) went to **Done**: `@nodeId(typeName:)`
  now resolves off the named type via `BuildContext.resolveDecodeHelperForType`
  instead of reverse-mapping the backing table. Carved out of R473 as phase 1
  under a federation field report.
- **R579** (`parse-external-ref-unused-parent-type`) went to **Done**, dropping
  the dead `parentTypeName` parameter from `FieldBuilder.parseExternalRef` and
  `ExternalFieldDirectiveResolver.resolve`.
- **R580** (`infer-node-from-implements-node-and-metadata`) went **Spec -> Ready
  -> In Progress -> In Review**: node identity is now inferred from `implements
  Node` plus catalog metadata. Still open (In Review), so its file stays on the
  board and its prose is the work, not drift.

The retirement that drives drift, verified at the symbol:

- **Slice 7 (`Operation` seal).** The `Operation` sealed interface and every
  arm reference (`Operation.Facet`, `Operation.Fetch`, `Operation.Count`,
  `Operation.Lookup`, `Operation.Paginate`, ...) are **all `grep` = 0** in main.
  The successor is `OperationMember` (a sealed interface with a per-coordinate
  **multiset** of member arms: `Select`, `Join`, `Condition`, `OrderBy`,
  `Paginate`, `Lookup`, `ServiceCall`, `Count`, `Facet`, `Pivot`, `Reentry`,
  `Write.Dml.{Insert,Upsert,Update,Delete}`, ...), consumed across
  `plan/ConditionCommands`, `plan/ProjectionCommands`, `plan/LauncherCommands`,
  and the model classes. The test-side obligation moved too: `OPERATION_ARMS` ->
  `MEMBER_ARMS`, `OPERATION_KNOWN_GAPS` -> `MEMBER_KNOWN_GAPS`, both keyed on
  `OperationMember.*` classes (`ExemptionRegistry.java:157`, `:384`). This drives
  **§C.0** (rewritten) and layers fresh drift onto **§C.6** (R222) and **§C.7**
  (R333).

The three node-identity Done items produced **no roadmap drift**, verified:

- **R581.** `resolveDecodeHelperForTable` **survives** for the orphan / bare-scalar
  arm; R581 only reordered decode resolution to be name-first and added
  `resolveDecodeHelperForType`. Every item citing `resolveDecodeHelperForTable`
  (R273, R473, R27, R580, R583) treats it correctly, as either the live table-keyed
  helper or a symbol R473's phase 2 is scheduled to delete. No item cites the old
  reverse-map order as the live mechanism.
- **R579.** `parentTypeName` is `grep` = 0 only on `parseExternalRef` /
  `ExternalFieldDirectiveResolver.resolve`; it stays live in
  `ServiceDirectiveResolver.resolve` and `FieldBuilder.classifyChildFieldOnTableType`.
  The four items that mention `parentTypeName` (R333, R222, R462, R54) all cite it
  as an `OutputField` **identity component** or as `addConditionsEdge` context
  (R462's own already-flagged `CompileDependencyGraphBuilder` surface), not as the
  removed `parseExternalRef` parameter. No drift.

Net: **0 §A / 5 §B / 25 §C**, §D empty. Flag total moves to **30**, up one from
the prior window's 29. Composition: **§B carries R462, R545, R85, R221, R71
unchanged** (all re-verified, premise-targets still `grep` = 0 with live successors);
**§C carries all 24 prior flags** (none fixed, none resurrected) and **gains R562**
on the `Operation`-seal retirement, with fresh Operation-seal drift layered onto
four already-flagged items (R427, R382, R333, R222). R427's prior re-anchor
**target** (a line-number fix on `Operation.Facet`) is itself superseded: that arm
type no longer exists.

## Changes since the 2026-08-04 audit

**50 commits** landed between the prior audit's HEAD (`d1e4151`, 2026-08-03 17:17)
and this HEAD (`fda241e`, 2026-08-04 21:12).

**Three items ran to Done:** R563 (`operation-relation`), R581
(`nodeid-typename-first-decode`), R579 (`parse-external-ref-unused-parent-type`).
R563 was the long-running In-Progress programme; R581 and R579 were both filed and
completed inside this window (Backlog/Spec -> In Review -> Done, spec file deleted,
changelog entry appended). None was a flag carrier.

**No prior flagged item was discarded or resolved this window.** No prior flag's
stale cite was fixed in place, so none leaves §B or §C.

**Twelve new items filed and still on the board** (all read against the current,
post-R563 model; all born-current): R582 (`adoc-xref-section-anchor-gate`, Spec),
R583 (`nodeid-target-keys-typeid-axis-coverage`, Spec), R584
(`mcp-server-instruction-routing`, Spec), R585 (`input-field-resolution-typed-rejections`,
Spec), R586 (`exemption-taxonomy-arm-census`), R587 (`md-code-span-passthrough-render`),
R588 (`node-without-metadata-diagnostics`), R589 (`validation-adds-facts`, Spec),
R590 (`leaf-coverage-migration-verify-gate`), R591 (`member-payload-storage-home`),
R592 (`lint-rule-reference-page`), R593 (`ci-init-sql-error-stop`). R590 and R591 are
R563's own filed residuals (migration `--verify` gate; the member-vs-leaf storage
home); R586 is the R563 exemption-taxonomy census carve-out; all three correctly
anchor on the post-dissolution model. R583 and R588 anchor on the shipped node
work (R581 / R580).

**Other transitions:** R580 Spec -> Ready -> In Progress -> In Review; R563 cycled
In Review -> Ready (gate rework) -> In Progress -> In Review -> Done; R473, R569,
R582, R584, R589 saw Spec revisions; R585 and R589 Backlog -> Spec. Ready set
unchanged in membership (R333, R427, R555); In Progress now holds R347 alone
(R563 departed to Done).

**Board accounting.** **172 item files** today (`roadmap/*.md` carrying `id:`), up
ten net from the prior audit's 162: thirteen ids allocated this window (R581-R593),
of which R581 was filed and Done in-window (net 0 on the board) and R582-R593 stay
(+12); R563 and R579 left to Done (-2). Net +10. Status distribution: **147 Backlog,
20 Spec, 3 Ready, 1 In Progress, 1 In Review, 0 Done**. A non-recursive
`^status: Done` grep over `roadmap/*.md` returns nothing (tombstone-free for the
twenty-sixth window running). No duplicate `id:`; max allocated id **R593**, and
`changelog.md` carries `next-id: R594`, clearing it. The R575/R576 (folded prior
window) and R579/R581 (Done this window) numbering gaps are all benign. A
`depends-on:` sweep over all 172 item files resolves every edge (all are slug-based)
to a present file. The board is structurally clean; the only nit is four **legacy**
items missing a `bucket:` key (§D), all pre-dating this window.

**Net effect on flag counts: 30 flagged, 142 current.** 0 §A, 5 §B, 25 §C, 0 §D.

## Scope and method

All **172** `R<n>` item files were reviewed (plus the non-item placeholders
`inference-axis-coverage.adoc`, `relevance-ranked-search-howto.adoc`,
`relevance-ranked-search-oracle-howto.adoc` and the permanent `workflow.adoc`, all
correctly excluded: no `R<n>`, and `.adoc` besides). Because this window retired a
widely-cited construct (the `Operation` seal, R563 slice 7), every stale-live cite
below was re-checked against a fresh `grep` of the main sources rather than carried
on the prior audit's word; that re-verification confirmed the prior audit's own
§C.0 re-anchor **target** (`Operation.Facet` at a fixed line) had itself been
retired.

**This window's symbol changes, verified at the symbol:**

- **`Operation` seal + all arm references, RETIRED (R563 slice 7,
  `850ba5f`/`ea5f9db`).** The sealed interface and every `Operation.<Arm>`
  reference are `grep` = 0 in main. Successor: `OperationMember` (sealed, a
  per-coordinate member multiset). The test-side obligation is `MEMBER_ARMS` /
  `MEMBER_KNOWN_GAPS`, keyed on `OperationMember.*` (`ExemptionRegistry.java:157`,
  `:384`). Drives §C.0 (rewritten) and layers onto §C.6 (R222) and §C.7 (R333).
- **`@nodeId(typeName:)` decode resolution, REORDERED name-first (R581,
  `c87667b`); `resolveDecodeHelperForTable`, still LIVE.** New name-first entry
  `resolveDecodeHelperForType`; the table-keyed helper survives for the orphan /
  bare-scalar arm. No roadmap drift.
- **`parentTypeName` parameter on `parseExternalRef` /
  `ExternalFieldDirectiveResolver.resolve`, REMOVED (R579, `59313d7`).** `grep` = 0
  on those two frames; live on `ServiceDirectiveResolver.resolve` and
  `classifyChildFieldOnTableType`. No item cites the removed parameter as live. No
  roadmap drift.

**Retired symbols from prior windows, re-verified still retired (no
resurrections):** `OutputField.operation()` accessor, the lookup triplet
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
premise-target is still `grep` = 0 in main with a live successor, and none of this
window's changes altered the set. Prior recommended actions all still hold; this
window adds only a confirmation that none of the node-identity retirements
(R581/R579) touched these targets.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R462** nested-fetcher-outgoing-field-edges | Spec | Carried. Title and central mechanism target `CompileDependencyGraphBuilder.addFieldEdges`, deleted by R549 slice 7 (`grep` = 0); the recompile graph is a typed projection over the plan, and the body's own stated dissolution condition (`:163`) has occurred. Its `addConditionsEdge(fetcher, parentTypeName)` cites (`:60`/`:84`/`:98`) are on the deleted builder surface, not R579's live sites. | **Re-derive against the plan-projected recompile graph.** Confirm at the symbol whether the nested-fetcher outgoing per-field edge (the `FilmMeta` case) is now correctly modeled under `EmitPlan`. If closed (likely), **discard** and record it; if a residue survives, **re-spec** onto the plan-projection. |
| **R545** model-free-of-emit-vocabulary | Backlog | Carried. `RowsMethodBody` / `RowsMethodSkeleton` deleted (`grep` = 0 each), so the whole `RowsMethodBody` diagnosis and the **second deliverable** are gone. The **first deliverable survives**: `ClassName` (~1700 lines, 23 model files) and `TypeName` (28 model files) are still model-pervasive. | **Re-spec.** Drop the `RowsMethodBody` diagnosis and the second deliverable entirely. Keep and re-baseline the first deliverable (`JavaTypeRef` replacing `TypeName` / `ClassName`) against the post-R549 model. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Carried. Both named edit-target generators are deleted: `QueryConditionsGenerator` (gone R552) and `TypeClassGenerator` (gone R549 slice 3.1); every `QueryConditionsGenerator.java:NNN` cite is dead. Condition emission moved to `render/ConditionGlueRenderer`, projection to `render/ProjectionUnitRenderer`. | **Re-derive against the new `render/` layer.** Determine whether `ConditionGlueRenderer` / `ProjectionUnitRenderer` still exhibit the duplicated helper-emission problem; if so, re-spec onto them. Drop every dead `QueryConditionsGenerator.java:NNN` cite. |
| **R221** validator-walks-plain-input-unbound-fields | Backlog | Carried. R519 deleted `TableInputType` / `validateTableInputType` (`grep` = 0), replacing them with per-consumer `collectInputFieldRejections` (live, `GraphitronSchemaValidator.java:524`); R566 made `@table`-on-input no longer produce a distinct table-input classification at all, so the item's "a `@table` input routes through the existing walker" framing (`:14`) is doubly obsolete. Unmoved this window. | **Re-derive against the post-R519 model.** If per-consumer resolution already validates plain-input `UnboundField + @condition(override:false)` shapes, **discard** (gap closed); else **re-spec** around `collectInputFieldRejections`. Re-baseline the `@table`-input premise for the accept-and-warn behavior. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried. R431 (Done) deleted the target surface: the body cites `SourceKey.java` line ranges and `SourceKey.Reader.SourceRowsCall` as "the live surface", but `SourceKey` is a plain record with no `Reader`. Successor `KeyLift` is live; `LifterRef` / sealed `Wrap` present. The sequencing line still reads "R431 ... plans to decompose"; R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model; drop the stale `SourceKey.Reader.SourceRowsCall` re-anchor note and the "R431 plans to decompose" tense. The goal (recordN key parity, non-jOOQ record parents) is intact. |

## C. Outdated: update references only (work valid, refs stale) (25)

Substance intact; names and line numbers drifted. All 24 prior flags carried (every
driving symbol re-verified still `grep` = 0, none fixed in place); **R562 enters** on
this window's `Operation`-seal retirement; four carried items (R427, R382, R333,
R222) pick up **additional** Operation-seal drift on top of their existing flag, and
R427's prior re-anchor target is itself superseded.

### C.0 `Operation` seal fully retired (rewritten this window; R563 slice 7)

The prior audit's §C.0 read: "the `operation()` leaf accessor is retired but the
`Operation` seal and its arms are live, so an item citing an `Operation.<Arm>`
**type** is correct". **Slice 7 retired the seal itself:** the `Operation` sealed
interface and every `Operation.<Arm>` reference are `grep` = 0 in main. A cite of
an `Operation` arm **type** is now as stale as the accessor was. The durable
successor is `OperationMember` (a per-coordinate member multiset); the paired
test obligation is `MEMBER_ARMS` / `MEMBER_KNOWN_GAPS`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R427** relevance-ranked-search | Ready | `:335` "`operation()` stays `Fetch`" cites the retired accessor; `:339`/`:1162` cite `Operation.Facet` as a live precedent **type**. The prior audit told this item to fix the `Operation.Facet` **line number** to `:72`; that arm type is now `grep` = 0, so the line-fix advice is void. | **Re-anchor (target changed since prior audit).** Restate "`operation()` stays `Fetch`" against the member-derived summary fold (a `Fetch` coordinate carries no DML/Facet member); repoint the `Operation.Facet` precedent onto `OperationMember.Facet` (or the `MEMBER_KNOWN_GAPS` census that now carries the modeled-but-unpopulated Facet arm). A **Ready** item; refresh before pickup. |
| **R382** multitable-interface-query-orderby-lowering | Backlog | `:19` cites `operation()` as the live mechanism hardcoding `new OrderBySpec.None()` for both interface/union arms. Accessor retired (prior window); the seal that backed it is now gone too. | **Re-anchor** the mechanism cite to where the hardcoded `OrderBySpec.None` now lives (the member/fact layer, `OperationMember.OrderBy` sourcing, or `MultiTablePolymorphicEmitter`); verify the ordering gap still reproduces. |
| **R562** synthesised-connection-fields-as-coordinates | Backlog | **New flag.** `:18` names "the `Operation.Count` and `Operation.Facet` arms of the `OPERATION_ARMS` obligation (`ExemptionRegistry.OPERATION_KNOWN_GAPS`)" as the observable gap. All three names retired this window (arms -> `OperationMember.Count` / `OperationMember.Facet`; obligation `OPERATION_ARMS` -> `MEMBER_ARMS`; map `OPERATION_KNOWN_GAPS` -> `MEMBER_KNOWN_GAPS`). The cite was correct until slice 7. | **Re-anchor** the three names to their `OperationMember` / `MEMBER_ARMS` / `MEMBER_KNOWN_GAPS` successors. The model question (should the synthesis step register the minted connection type's `totalCount`/`facets` as classified coordinates so a fact carries the count/facet) is intact and still owns both exemption reason strings; note the successor `OperationMember.Count` / `Facet` arms are explicitly "modeled-but-unpopulated", which is exactly this item's subject. |

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
| **R222** dimensional-model-pivot | Spec | `:15`/`:60`/`:442` still name `GraphitronType.TableInputType` as a live sibling root that R519 collapsed; `:62` cites the `LookupField` mixin overlay (folded slice 6a). **New this window**, `:157` proposes "the verb: a **sealed interface `Operation` with `record` arms** (replacing the flat...)", a model R563 in fact delivered differently, as the `OperationMember` member multiset, not a single sealed arm. | **Re-baseline the input-side section** as delivered by R519; **re-baseline the operation-axis section** (`:157`) as delivered by R563 (`OperationMember`, arm-grain `operations:` multiset), narrowing the umbrella to the still-open field-side and `Unclassified*` organs; re-anchor the `LookupField` mixin cite. Per the R563 changelog, R222's operation-axis content is discharged, so this section is now a delivered-model rewrite, not open design. |
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
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh still incomplete, and it grew this window.** Crosswalk rows are current, but prose and the baseline table are not: `:529` names `TypeConditionsGenerator`; `:1605`/`:1626`/`:1628`/`:1681`/`:1749`/`:1963`/`:1987` name `TypeClassGenerator` / `collectRequiredProjection`; `:1674` cites `BatchKeyField.rowsMethodName()`; `:341` cites `OutputField.operation()`; `:1791` cites `ParentProjectionContainmentCheck`; `:1826`/`:2054`/`:2058`/`:2064` cite `methodgraph`; `:1754`/`:1775` cite `LookupValuesJoinEmitter`; and **new this window** `:157` names the `Operation` **seal** as a live sealed interface and `:1512` cites "the shipped 17-arm `Operation` seal the classifier computes today". | **Finish the refresh in one pass** (this row + §C.0 + §C.5 + §C.6): repoint `TypeClassGenerator` / `collectRequiredProjection` to `ProjectionUnitRenderer` / `ProjectionCommands`; drop the `TypeConditionsGenerator`, `BatchKeyField.rowsMethodName`, `OutputField.operation()`, `ParentProjectionContainmentCheck` and `methodgraph` cites; re-anchor `LookupValuesJoinEmitter` to the render lookup family; and re-anchor the `Operation` seal / "17-arm" cites to `OperationMember`. Rows citing `SplitRowsMethodEmitter` stay (live). |
| **R231** emit-text-mapped-enum-fields-as-enum-type | Backlog | `:39` locates "the field-type-emit fork (likely in `TypeClassGenerator` or ...)"; deleted by R549 slice 3.1. | **Re-anchor** the investigation locus to `ProjectionUnitRenderer` / `plan/ProjectionCommands`. |
| **R35** source-orientation-javadocs | Backlog | `:42` enumerates classes needing a class-level javadoc sweep, including `TypeClassGenerator` and `TypeConditionsGenerator`, both deleted. | **Re-anchor** the enumeration: drop the two deleted names, optionally adding the successor renderers. Low priority; the sweep scope is illustrative. |

## D. Structural: (0)

Empty of blocking defects. `changelog.md` carries `next-id: R594`, clearing the max
allocated id (R593). No duplicate `id:`, no `status: Done` tombstones in
`roadmap/*.md`, and a `depends-on:` sweep over all 172 item files resolves every
edge (all slug-based) to a present file. The twelve items filed this window carry
well-formed front-matter and were read against the current model.

One **pre-existing, non-blocking** hygiene nit: four **legacy** items lack a
`bucket:` key, all pre-dating this window: R242 (`dml-payload-positional-alignment`),
R109 (`list-valued-external-field-multiset`), R252 (`multi-file-federation-fixture-coverage`),
R180 (`record-parent-column-read-helper`). Each has `id:` and `status:`, and the
roadmap-tool tolerates the omission (the build is green), so this is a backlog-hygiene
note, not a §D blocker; fold a `bucket:` in whenever each item is next edited.

## Cross-cutting observations

1. **A programme completing invalidated an audit premise, not just an item cite.**
   The prior audit's §C.0 was built on a stated invariant, "the `Operation` seal
   and its arms are live, so an item citing an `Operation.<Arm>` **type** is
   correct", and used it to keep several re-anchor targets pointed at live arm
   types. R563 slice 7 then retired the seal itself, so the audit's own framing,
   not just an item's cite, went stale. The lesson repeats last window's
   `BatchedLookupTableField` miss one level up: when a programme is mid-dissolution,
   even an audit's structural assumptions have a shelf life, and a re-anchor should
   name the durable successor axis (`OperationMember` and its member multiset)
   rather than an intermediate construct the same programme is still dismantling.

2. **A finished programme is a bigger drift event than a slice.** Prior windows
   flagged R563 slices individually as they landed; the seal retirement in the
   final slices touched five items at once (R427, R382, R562, R333, R222) because
   the `Operation` arm names had leaked into precedent citations, obligation
   references and design docs across the board. As before, the tell of blast radius
   is not the size of the retirement but how many specs already lean on the retired
   name; the `Operation` seal was a heavily-cited classification primitive.

3. **The node-identity cutovers were self-contained.** R581 (name-first decode) and
   R579 (dead parameter) shipped with **zero** external roadmap drift: R581 kept the
   table-keyed helper live for the cases that need it, so every citing item still
   reads true, and R579's removed parameter was never cited as live anywhere. The
   contrast with the `Operation` seal is instructive: a retirement drifts specs only
   when the retired name was the one the specs held.

4. **R333's mid-programme refresh now spans five drivers and is still partial.** Its
   crosswalk rows track the renderers, but its prose and baseline table lag across
   §C.0 (operation, now the seal itself), §C.5 (column), §C.6 (input), §C.7
   (condition/projection) and §C.1 (lookup). The item is internally inconsistent and
   should be refreshed in a single pass before pickup, not incrementally at each
   neighbouring gate. It is **Ready**, so the refresh is due now.

5. **A programme's own prose is not drift.** R580 (`infer-node-from-implements-node-and-metadata`,
   In Review) and R347 (`lsp-structural-consolidation`, In Progress) describe the
   subjects they mint and consolidate; their prose is the work, not consumer-facing
   stale-live, and they are not flagged. R563's newly-Done retirements surface, as
   expected, only in `changelog.md` and the items that leaned on the retired names.

---

_Review date: 2026-08-05._
