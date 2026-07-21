# Roadmap staleness audit: 2026-07-21

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `5364cbf`, committed 2026-07-21 00:22, audited 2026-07-21). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-20` staleness audit, which has been deleted;
only the latest staleness audit is retained. Three siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  meant to be edited in place as scope iterates. Its MUST/SHOULD tables continue
  to lag the post-decomposition model and now also predate the R45 multi-tenant
  landing and the R507 documentation-staleness item; refreshing it stays out of
  scope for this staleness pass.
- `2026-07-04-r222-r333-conformance-analysis.md` is the R222/R333 conformance
  analysis, a companion to the R314/R333 design session. It is a targeted
  implementation-vs-spec conformance record, not a point-in-time staleness
  review; left in place. (This window's `be3be16` R222/R333 spec sync acted on
  the same conformance concerns; the analysis remains a sign-off input and stays
  as lineage.)

`classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
inventory; its "superseded, historical" banner is intact. No action; it stays as
lineage.

## Headline: a high-churn window that resolved one §B by action and left the §C cluster untouched while drifting its line anchors further

Where the prior window was consolidation (0 new drift, one duplicate
self-resolved), this one was **execution**: 46 commits, six terminal closures,
four new items, and the R45 multi-tenant feature landing across six slices into
In Review. Two things stand out for staleness purposes:

- **The prior audit's headline §B recommendation was actioned in-window.** The
  prior audit flagged **R222** (`dimensional-model-pivot`, §B) with a specific
  fix: add a dated supersession banner over the pre-R431 research snapshot,
  refresh the leaf-permit count, and correct the stale slice labels. Commit
  `be3be16` did exactly that (banner dated 2026-07-20, permit count refreshed to
  51 = 13/15/23, `R431`/`R238`/`R279` relabelled to Done, the SourceKey research
  section marked as the executed pre-R431 snapshot, R503's run-time source-shape
  dispatch noted). **R222 is no longer flagged.** The companion `R333`
  (`coordinate-lowers-to-datafetcher-queryparts`, Ready) was re-baselined in the
  same commit (the two "`TableExpr.MethodCall` not yet built" claims flipped to
  past tense, three drifted citations re-pinned); it stays no-flag.

- **The §C cluster did not move, but the model grew underneath it.** None of the
  eleven carried §C items was edited this window, so every retired-leaf-name and
  stale-line citation persists. Meanwhile **R501** (`@pivot`) and **R503**
  (mixed-source nested types) each **added** `ChildField` leaves
  (`PivotField`/`BatchedPivotField`/`PivotSlotField`;
  `ServiceRecordField`/`RecordField`/`RecordCompositeField`), pushing every leaf
  below them down: `BatchedTableField` `:519 -> :538`, `ServiceTableField`
  `:913 -> :1046`. So the §C items that cite raw `ChildField.java` line numbers
  are now **more** stale than three days ago, not less.

Net: **0 §A / 1 §B / 11 §C**, §D empty. §B halves (R222 resolved, R71 carried);
§C is identical to the prior window's set.

## Changes since the 2026-07-20 audit

**46 commits** landed between the prior audit's publish commit (`df6aab4`,
2026-07-20 03:19, "refresh staleness audit to 2026-07-20") and this HEAD
(`5364cbf`, 2026-07-21 00:22). Six things drove the window:

**1. Six items ran to Done (all self-deleted).** **R499**
(`connection-fields-shared-result-key-projection`, occurrence-union `$fields`
projection) and **R500** (`result-key-aware-reference-projection`, aliased
duplicate selections) closed the cross-referenced bug pair the prior audit
tracked as a deliberate split. **R501** (`pivot-projection-directive`, the
`@pivot` discriminator-keyed aggregate feature) shipped, adding the
`PivotField`/`BatchedPivotField`/`PivotSlotField` leaves. **R503**
(`mixed-source-nested-type-reads`) shipped, adding
`ServiceRecordField`/`RecordField`/`RecordCompositeField` and **replacing the
mixed-source nested-type rejection with run-time source-shape dispatch**.
**R504** (`ChildField` leaf-taxonomy doc scrub) was filed and closed in-window,
scrubbing dead leaf names from code comments, javadoc, and test names. **R126**
(`scrub-stale-batchkey-prose`, classification-vocabulary scrub) closed from In
Review.

**2. The R45 multi-tenant feature landed.** **R45**
(`tenant-routing-and-execution-input`) went Ready -> In Progress -> In Review
across six slices: a `tenantColumn` declaration and two-way tenant-scope table
classification, a per-field `TenantBinding` axis with the `noTenantBinding` and
`TenantColumnTypeDisagreement` rejection arms, catalog-typed tenant-keyed runtime
surfaces, `TenantConnections` with per-tenant dispatch grouping, and a
multi-tenant L5 execution fixture. Additive; it re-anchored its own retired
`SourceKey.Reader` references during the Spec -> Ready step (`1613680`).

**3. Four new items filed.** **R505** (`tenant-index-parent-row-routing`,
Backlog) was extracted from R45's tenant-index scope. **R506**
(`roadmap-tool-statechart-driver`, Backlog) proposes the roadmap-tool as a
statechart driver. **R507** (`documentation-staleness-prevention`, Backlog ->
Spec) is the systematic fix for exactly the drift this audit tracks; its "Seed"
list (`:189-191`) deliberately names the R504 retired-leaf family as subject
matter, not as a live claim. **R508** (`composite-column-dissolution`, Backlog)
files the composite-column dissolution.

**4. R222/R333 spec sync.** `be3be16` re-baselined both against the shipped
R431/R432/R314 trilogy (see Headline). R222 was In Review-adjacent Spec work;
R333 stayed Ready.

**5. R487 explainer + docs.** A `parent-holds-fk-correlation` concept explainer
page was added (`11f5e75`, R487), and the divined-tenant-routing explainer was
touched by the R45 arc.

**Terminal closures this window (Done, all self-deleted):** R499, R500, R501,
R503, R504, R126. Each has a `changelog.md` entry. R45 (In Review), R347 (In
Progress), R333/R427 (Ready) carry work but are not Done.

**Board accounting.** **133 item files** today (135 `roadmap/*.md` entries minus
`README.md` and `changelog.md`), down from the prior audit's 134: six closures
self-deleted (R499, R500, R501, R503, R126, and R504 which was also created
in-window for net zero) against four new files (R505, R506, R507, R508). Status
distribution: **117 Backlog, 12 Spec, 2 Ready (R333, R427), 1 In Progress (R347),
1 In Review (R45); zero Done**. A non-recursive `^status: Done` grep over
`roadmap/*.md` returns nothing (tombstone-free for the fifteenth window running).
No duplicate `id:`; max allocated id **R508**, and `next-id: R509` clears it. A
`depends-on:` sweep over all 133 item files resolves every edge to a present file
(edges to Done-and-deleted slugs such as `decompose-sourcekey` were not found;
the live edges all resolve). The board is structurally clean.

**Net effect on flag counts: 12 flagged, 121 current.** 0 §A, 1 §B, 11 §C, 0 §D.

## Scope and method

All **133** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded: no `R<n>`). The
claimed model constants were verified at the symbol. `ChildField.java` declares,
in order: `TableField` (`:466`), `BatchedTableField` (`:538`), `LookupTableField`
(`:644`), `BatchedLookupTableField` (`:672`), `TableMethodField` (`:741`),
`TableInterfaceField` (`:769`), the pivot family `PivotField` (`:928`) /
`BatchedPivotField` (`:960`) / `PivotSlotField` (`:1014`) new from R501,
`ServiceTableField` (**`:1046`**, shifted from `:913` last window),
`ServiceRecordField` (`:1093`) / `RecordField` (`:1175`) / `RecordCompositeField`
(`:1220`) new from R503, with **no `RecordTableMethodField`, `SplitTableField`,
`SplitLookupTableField`, or `RecordLookupTableField`** (a source grep finds the
old names only in `former`/`guarded` lineage prose in `OutputField.java`,
`TypeFetcherGenerator.java`, and `FieldBuilder.java`). `SourceKey.java` is the
plain `public record SourceKey(...)` residue (`:40`) with **no `Reader` interface
and no `SourceRowsCall`**. `KeyLift.java` and `LifterRef.java` are both live (the
§B/§C re-anchor targets exist). `planSlug` remains **gone** from the codebase.
The two sealed types from last window are intact: `BuildContext.ConditionResolution`
(sealed tri-state) and `GraphitronType.JooqRecordCarrier` (`:153`, sealed
`ResultType`, referenced live by R501/R503 emit paths).

**Anchors that held (re-verified):** the eleven carried §C items were re-checked
against the (unedited) roadmap files; none was re-anchored. The re-anchor
*targets* they should point at are all live (`BatchedTableField` `:538`,
`BatchedLookupTableField` `:672`, `TableMethodField` `:741`, the decomposed
`KeyLift`/`LifterRef`/`Wrap` model). R66 / R7 / R35 remain re-derive-at-pickup by
construction.

## A. Obsolete: should leave the active roadmap (0)

Empty. No item's entire premise was invalidated this window. The R499/R500 bug
pair (which the prior audit had watched for a duplicate-filing collision) both
closed cleanly to Done as a deliberate split, confirming the prior read. No new
duplicate or fully-superseded item surfaced.

## B. Outdated: needs re-spec (premise or targets materially changed) (1)

Down from two: **R222 was resolved this window** by the `be3be16` spec sync
(dated supersession banner added, permit count refreshed, slice labels corrected,
SourceKey snapshot marked as executed pre-R431), which implemented the prior
audit's exact §B recommendation. One item remains, carried unchanged.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | R431 (Done) **deleted the target surface**, not just its line numbers. The body cites `model/SourceKey.java:124-128` (a `SourceRowsCall -> Wrap.Row` compact-ctor pin) and `:288` (`SourceKey.Reader.SourceRowsCall(LifterRef)`); `SourceKey` today is a plain record (`:40`) with **no `Reader` interface and no `SourceRowsCall`**. The body's own 2026-07-13 re-anchor note (`:14`) still names `SourceKey.Reader.SourceRowsCall` / `Wrap` as "the live surface", so that note is itself stale. The mechanism (the lifter contract pin) was relocated by R431 into `KeyLift`/`LifterRef`/`Wrap`, so this is a design-level re-derivation of *where* the work attaches, not a line bump. Not touched this window. | **Re-spec the current-state / approach section** against the decomposed `KeyLift`/`LifterRef`/`Wrap` model. The goal (recordN key parity, non-jOOQ record parents) is intact, so this is a targeted re-derivation of the attachment surface. Drop the "R431 plans to decompose" tense (`:33`); R431 is Done. This is the same fix R222 just received; R71 is the last item still carrying the pre-R431 `SourceKey.Reader` shape as live. |

## C. Outdated: update references only (work valid, refs stale) (11)

Substance intact; names and line numbers drifted. **All eleven of the prior
window's §C items are carried unchanged** (none was edited this window), and the
`ChildField.java` line drift is **worse** than last window because R501 and R503
inserted new leaves above the cited ones (`BatchedTableField` `:519 -> :538`,
`ServiceTableField` `:913 -> :1046`).

### C.1 `planSlug` / `SourceKey.Reader` removal drift (carried)

R484 (Done, prior window) removed `Rejection.Deferred.planSlug`; R431 (Done)
removed the `SourceKey.Reader` interface. Deferrals now anchor by
`StubKey.VariantClass` (a live class) and carry no roadmap-item pointer.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R454** routine-write-result-shapes | Backlog | Line 18 describes the deferred shapes as "typed `Deferred`s pointing at this item's planSlug". The `planSlug` field no longer exists, and deferrals no longer carry a roadmap-item pointer. | **Re-anchor** the mechanism sentence: the deferred shapes surface at build time via `StubKey.VariantClass` naming the variant, with no roadmap pointer. Substance (which write result shapes stay deferred) is valid. |
| **R447** routine-chain-fetch-form-breadth | Backlog | Two causes. (a) Lines 18 and 33 name a "typed `Deferred` landings whose `planSlug` points here" / "empty planSlug in R435"; `planSlug` is gone. (b) Line 24 (`SplitLookupTableField` composition) and line 26 (`RecordTableField` seam) name merged leaves as live. | **Re-anchor** both: drop the `planSlug` pointer phrasing (deferrals anchor by `StubKey.VariantClass` now), and repoint the two variant names to the `Batched*` leaves. |
| **R180** record-parent-column-read-helper | Spec | Prose (`:35`) says "R431 ... now In Progress" -> R431 is **Done**, and names `SourceKey.Reader.AccessorCall` as a live carrier -> `SourceKey` has no `Reader` interface today. The substance (consume the R461 `ClassAccessorResolver.enumerate` / `AccessorProbe` surface for record-parent column reads) is valid and its machinery is live (`:30`). | **Re-anchor** the `SourceKey.Reader.AccessorCall` carrier onto the decomposed model, and fix the R431 tense to Done. Borderline with §B (the carrier is fully gone, not renamed), but the core machinery it consumes (R461) is live, so a reference repoint suffices. |

### C.2 R432 leaf-merge / R314 dissolution / R431 anchor drift (carried, unchanged)

`SplitTableField`/`RecordTableField` -> `BatchedTableField`;
`SplitLookupTableField`/`RecordLookupTableField` -> `BatchedLookupTableField`;
`RecordTableMethodField` dissolved onto the record-sourced `BatchedTableField`
carrying `TableExpr.MethodCall`; the surviving `@tableMethod` leaf is
`TableMethodField`. `SplitRowsMethodEmitter` is **not** renamed and is correct
wherever it appears. **R504 (Done) scrubbed these dead names from code comments,
javadoc, and test names, but not from roadmap prose**, so the gap between clean
code and stale roadmap text widened this window; R507 (Spec) is the systematic
fix for it.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R403** reintroduce-tablemethod-docs | Backlog | Line 44 names `ChildField.RecordTableMethodField` as the live construct the reintroduced docs would describe. That leaf no longer exists; the DTO-parent `@tableMethod` shape now classifies to a **record-sourced `BatchedTableField`** carrying `TableExpr.MethodCall`. | **Re-anchor** the construct description to the post-R314 shape. |
| **R109** list-valued-external-field-multiset | Spec | Line 51's planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** the planned test assertion to `BatchedTableField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Lines 21, 28, 39, 41-42, 127 name `SplitTableField` / `SplitLookupTableField` as live. `SplitRowsMethodEmitter` in the same passages is correct. | **Re-anchor** the variant names; leave `SplitRowsMethodEmitter` untouched. |
| **R242** dml-payload-positional-alignment | Spec | Lines 37-38's R305 lineage note "collapsed it into `RecordTableField`, `ChildField.java:912`" is **doubly stale** and worse than last window: the name is now `BatchedTableField` (`:538`, shifted from `:519`), and the cite `:912` today lands inside `NestingField`/`PivotField` territory (`:892`/`:928`), not on any merged leaf; `ServiceTableField` (which `:912` was near last window) has moved to `:1046`. | **Re-anchor** that one name + cite (`BatchedTableField`, `:538`); leave the surrounding R305/R287 history. |
| **R288** inline-interface-and-tablemethod-children | Backlog | Line 35: "a keyed batch (DataLoader, as `SplitTableField` / `RecordTableField` do via `SplitRowsMethodEmitter`)". Variant names stale; emitter fine. | **Re-anchor** the two variant names. |
| **R472** nested-generated-condition-filters-never-emitted | Backlog | Lines 20-21: classifier attaches `GeneratedConditionFilter` to a nested `SplitTableField` / `SplitLookupTableField` / inline `TableField` / `LookupTableField`. (`TableField` / `LookupTableField` are still live.) The R443 `ConditionResolution` refactor (prior window) touches a *different* condition mechanism and does not change this item's emit-gap premise. | **Re-anchor** the two `Split*` names. |
| **R116** composite-key-row2-source-row-coverage | Backlog | Line 15: the planned `COMPOSITE_KEY_ROW2_PATH_KEYED` case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R7** decompose-typefetchergenerator | Backlog | Line 30 proposes a hypothetical decomposed emitter `SplitTableFieldEmitter`; a decomposition done today would name it `BatchedTableFieldEmitter`. Illustrative "etc." naming; the list of *existing* emitters (incl. `SplitRowsMethodEmitter` `:32`) is correct. | **Low priority:** refresh the illustrative name at pickup. Not blocking. |

**No flag (verified lineage / self-noted / re-baselined this window):** **R222**
(Spec) was re-baselined by `be3be16` (banner, permit count, labels) and is now
current. **R333** (Ready) was re-baselined in the same commit and carries
explicit shipped-notes for R432 and R314; its old-name mentions are
Discovery/lineage evidence. **R323** (`nestingfield-multiparent-batchkey-leaves`,
Backlog) self-flagged with a "Re-anchor at pickup" note naming the old variant
names; pre-declared, `depends-on` cleared. **R302** and
`rename-childfield-to-sourcefield` mention `SingleRecordTableField` only in R305
lineage clauses. **R507** (Spec) names the retired leaf family deliberately as
its subject matter, not as a live claim. **R24** stays current (re-anchored off
`planSlug` last window).

## D. Structural: (0)

Empty. `changelog.md` carries `next-id: R509`, clearing the max allocated id
(R508). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and a
`depends-on:` sweep over all 133 item files resolves every live edge to a present
file. The four new items (R505, R506, R507, R508) all carry well-formed
front-matter (`id`, `status`, `bucket`, `created`, `last-updated`).

## Cross-cutting observations

1. **An audit recommendation was actioned in-window, and it worked.** R222's §B
   fix (the dated supersession banner over a research snapshot, rather than an
   in-place edit that would destroy the dated evidence) was implemented verbatim
   by `be3be16` and cleanly resolved the flag. The same shape of fix is the
   recommendation for R71, the last remaining pre-R431 `SourceKey.Reader` holder.
2. **Code scrubs outran roadmap prose, widening the gap.** R504 (Done) scrubbed
   the dead `ChildField` leaf taxonomy from code comments, javadoc, and test
   names; R126 (Done) scrubbed retired classification vocabulary from code. Both
   left the eleven §C roadmap items describing the same retired names. The clean
   code now contrasts sharply with stale roadmap text. **R507**
   (`documentation-staleness-prevention`, Spec) is the systematic fix and seeds
   directly off the R504 leaf family; landing it would retire this recurring §C
   cluster at the source rather than one re-anchor at a time.
3. **Additive model growth is a silent line-anchor drift source.** R501 and R503
   each inserted new `ChildField` leaves above existing ones, shifting every
   downstream leaf's line number (`BatchedTableField` `:519 -> :538`,
   `ServiceTableField` `:913 -> :1046`) without renaming anything. Items that
   cite raw `ChildField.java:<n>` line numbers (notably R242) drift every time a
   leaf is added, even in windows where their own subject is untouched. The
   standing convention holds and is reinforced: prefer symbol-anchored references
   over bare line numbers.
4. **The re-platforming trilogy stayed complete and stable.** R431/R432/R314 did
   not reopen; the retired leaf names exist in code only as `former`/`guarded`
   lineage prose. As the prior two audits predicted, the churn source shifted to
   emit/feature work (R45, R501, R503), not model decomposition.
5. **Carried cosmetic front-matter nit persists.** **R97**
   (`consumer-derived-input-tables`) still lacks `created:` / `last-updated:`.
   Not a build or dependency risk.
6. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-21._
