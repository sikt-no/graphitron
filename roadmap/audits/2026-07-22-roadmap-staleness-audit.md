# Roadmap staleness audit: 2026-07-22

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `b2d3d07`, committed 2026-07-21 23:05, audited 2026-07-22). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-21` staleness audit, which has been deleted;
only the latest staleness audit is retained. Three siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  meant to be edited in place as scope iterates. Its MUST/SHOULD tables continue
  to lag the post-decomposition model and now also predate the R45 multi-tenant
  landing (Done this window) and the R507 documentation-staleness item; refreshing
  it stays out of scope for this staleness pass.
- `2026-07-04-r222-r333-conformance-analysis.md` is the R222/R333 conformance
  analysis, a companion to the R314/R333 design session. It is a targeted
  implementation-vs-spec conformance record, not a point-in-time staleness
  review; left in place.

`classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
inventory; its "superseded, historical" banner is intact. No action; it stays as
lineage.

## Headline: three features ran to Done, R507 landed but does NOT cover the §C cluster, and R508 shifted the leaf anchors a second time

Another **execution** window: 37 commits, three terminal closures (R45, R489,
R508 all Done and self-deleted), one item (R507) landed to In Review, and one new
item (R509) filed. Four things matter for staleness:

- **The prior audit's central prediction about R507 is wrong, and must be
  corrected.** The `2026-07-21` audit's cross-cutting observation #2 asserted that
  landing R507 (`documentation-staleness-prevention`) "would retire this recurring
  §C cluster at the source rather than one re-anchor at a time." R507's
  implementation landed this window (`999cc6c`, In Review), and its spec is
  **explicit that roadmap-item body currency is out of scope** ("owned by the
  periodic staleness audits under `roadmap/audits/`", body `:242-243`) and that its
  `RetiredVocabularyGuardTest` **deliberately excludes `roadmap/`** from its scan
  ("items are transient; the changelog and staleness audits must be able to name
  retired terms", body `:199-201`). R507 guards code comments, javadoc, main-source
  string literals, authored `.adoc`, and fixture SDL, and it shipped its allowlist
  empty. It does **not**, and by design will never, touch the §C roadmap-prose
  cluster this audit tracks. The cluster remains fully the responsibility of these
  audits. R507 is a real, valuable drift-prevention landing for *code and doc*
  prose; it is simply orthogonal to §C.

- **R508 renamed a second leaf family and re-shifted the line anchors, this time
  downward.** **R508** (`composite-column-dissolution`, Done) dissolved the
  composite-column pairs on all three classification axes:
  `CompositeColumnField`/`CompositeColumnReferenceField` (on `ChildField` and
  `InputField`) and `CompositeColumnArg`/`CompositeColumnReferenceArg` (on
  `ScalarArg`) merged with their arity-1 siblings (`ColumnField` /
  `ColumnReferenceField` etc.) into fresh single carriers `ColumnBackedField` /
  `ColumnBackedReferenceField` / `ColumnBackedArg` / `ColumnBackedReferenceArg`,
  arity now a `columns` count read off `isComposite()`. Six leaves retired. Because
  the net effect **removed** leaves above the drift-cited ones, every downstream
  `ChildField` leaf shifted *up*: `BatchedTableField` `:538 -> :521`,
  `ServiceTableField` `:1046 -> :1029`. So the items that cite raw
  `ChildField.java:<n>` line numbers (notably R242) drift **again**, in the
  opposite direction from last window, confirming the standing convention: prefer
  symbol anchors over bare line numbers.

- **R508's re-anchor sweep was targeted but incomplete.** R508's author re-anchored
  five items to the merged carriers (`R24`/`R27`/`R419`/`R462`/`R333`, per its
  `changelog.md` entry). R462's composite-name mentions were fixed in-window
  (`ColumnField`/`CompositeColumnField` -> `ColumnBackedField`), but R462 still
  carries the older `Split*` names (a *different*, R432-era rename R508 was not
  responsible for), so it stays §C. Two composite-name mentions were **missed** and
  are new drift: **R51** names the now-retired `ColumnField`/`ColumnReferenceField`
  in an R50 lineage clause.

- **A pre-existing born-stale item surfaced.** **R505**
  (`tenant-index-parent-row-routing`, filed 2026-07-20, one window before the prior
  audit) names "the `SourceKey.Reader` family" as a live carrier at body `:21`;
  `SourceKey.Reader` was removed by R431 (Done) *before* R505 was filed. The prior
  audit noted R505's filing but did not vet its body; this pass flags it.

Net: **0 §A / 1 §B / 13 §C**, §D empty. §B is unchanged (R71 carried). §C grew
from 11 to 13: the eleven prior items all carry unchanged, plus two additions
(R505's born-stale `SourceKey.Reader`, R51's R508-retired composite names). R462
shed its composite-name drift but retains its `Split*` drift.

## Changes since the 2026-07-21 audit

**37 commits** landed between the prior audit's publish commit (`4a3ad38`,
2026-07-21 00:35, "refresh staleness audit to 2026-07-21") and this HEAD
(`b2d3d07`, 2026-07-21 23:05). Five things drove the window:

**1. Three items ran to Done (all self-deleted).** **R45**
(`tenant-routing-and-execution-input`) closed In Review -> Ready -> In Progress ->
In Review -> Done (a rework loop on honest-reach classification and carrier-borne
connection routing). **R489** (`dml-reentry-values-join-rendering`) shipped the
VALUES-join reentry normalization through two review passes, pinning the PK-less
DML-return rejection at the model tier. **R508** (`composite-column-dissolution`)
dissolved the composite-column leaf family across all three axes in four slices,
retiring six leaves. Each has a `changelog.md` entry.

**2. R507 landed to In Review.** **R507**
(`documentation-staleness-prevention`) went Spec -> Ready -> In Progress -> In
Review (`023e2bf`, `6a2da6a`, `999cc6c`, `b2d3d07`). It shipped the prose-truth
principle rewrite, the retirement-sweep workflow check, the CLAUDE.md trigger
line, and `RetiredVocabularyGuardTest` (allowlist empty; the 14 surviving lineage
mentions were deleted or rewritten). **It does not touch roadmap-item bodies**
(see Headline). Not Done: no `changelog.md` entry yet.

**3. One new item filed.** **R509** (`bulk-dml-payload-input-order`, Backlog,
`4501767`/`bf425d4`) files the bulk-DML payload input-order guarantee (match input
order, warn where unguaranteed), extracted from R489's execution-tested ordering
contract. Well-formed front-matter; `next-id` bumped to R510 in the same arc.

**4. R508 re-anchor pass.** Slice 4 (`5a84b88`) re-anchored trigger rows and five
roadmap items (R24/R27/R419/R462/R333) to the merged `ColumnBacked*` carriers.
R222 was re-baselined in the same axis (`InputField` family names updated with an
in-line "dissolved ... in R508" note).

**5. R489 doc reconciliation.** R333's DML-correlation residue note was updated
from "owned by R489" to "shipped 2026-07-21 (R489)", and the user manual
(`mutation.adoc`, tutorial 05) was reconciled to the shipped two-step emit.

**Terminal closures this window (Done, all self-deleted):** R45, R489, R508. R507
(In Review), R347 (In Progress), R333/R427 (Ready) carry work but are not Done.

**Board accounting.** **131 item files** today (133 `roadmap/*.md` entries minus
`README.md` and `changelog.md`), down from the prior audit's 133: three closures
self-deleted (R45, R489, R508) against one new file (R509). Status distribution:
**116 Backlog, 11 Spec, 2 Ready (R333, R427), 1 In Progress (R347), 1 In Review
(R507); zero Done**. A non-recursive `^status: Done` grep over `roadmap/*.md`
returns nothing (tombstone-free for the sixteenth window running). No duplicate
`id:`; max allocated id **R509** (R508's file deleted at Done), and `next-id:
R510` clears it. A `depends-on:` sweep over all 131 item files resolves every edge
to a present file. The board is structurally clean.

**Net effect on flag counts: 14 flagged, 117 current.** 0 §A, 1 §B, 13 §C, 0 §D.

## Scope and method

All **131** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded: no `R<n>`). The
claimed model constants were verified at the symbol. `ChildField.java` declares,
in order, the composite-dissolution survivors and the shifted leaves:
`ColumnBackedField` (`:289`, R508) / `ColumnBackedReferenceField` (`:341`, R508),
`TableField` (`:449`), `BatchedTableField` (**`:521`**, shifted up from `:538` last
window by R508's net leaf removal), `LookupTableField` (`:627`),
`BatchedLookupTableField` (`:655`), `TableMethodField` (`:724`), the pivot family
`PivotField` (`:911`) / `BatchedPivotField` (`:943`) / `PivotSlotField` (`:997`),
`ServiceTableField` (**`:1029`**, shifted up from `:1046`), `ServiceRecordField`
(`:1076`) / `RecordField` (`:1158`) / `RecordCompositeField` (`:1203`), with **no
`CompositeColumnField`, `ColumnField`, `RecordTableMethodField`, `SplitTableField`,
`SplitLookupTableField`, or `RecordLookupTableField`** as live leaves (a source
grep finds the old names only in `former`/`guarded`/`dissolved` lineage prose;
R507's guard confirms this by passing with an empty allowlist). `SourceKey.java`
is the plain `public record SourceKey(...)` residue (`:40`) with **no `Reader`
interface and no `SourceRowsCall`**. `KeyLift.java` and `LifterRef.java` are both
live (the §B/§C re-anchor targets exist). `planSlug` remains **gone** from the
codebase. The two sealed types tracked across prior windows are intact:
`BuildContext.ConditionResolution` (`:2250`, sealed tri-state) and
`GraphitronType.JooqRecordCarrier` (`:153`, sealed `ResultType`).

**Anchors that held (re-verified):** the eleven carried §C items were re-checked
against the (mostly unedited) roadmap files; only R462 was partially re-anchored
(composite names, by R508). The re-anchor *targets* they should point at are all
live (`BatchedTableField` `:521`, `BatchedLookupTableField` `:655`,
`TableMethodField` `:724`, `ColumnBackedField` `:289`, the decomposed
`KeyLift`/`LifterRef`/`Wrap` model). R66 / R7 / R35 remain re-derive-at-pickup by
construction.

## A. Obsolete: should leave the active roadmap (0)

Empty. No item's entire premise was invalidated this window. R45, R489, and R508
all closed cleanly to Done as designed; no item they touched was left orphaned. No
new duplicate or fully-superseded item surfaced.

## B. Outdated: needs re-spec (premise or targets materially changed) (1)

Unchanged from last window: one item, carried untouched.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | R431 (Done) **deleted the target surface**, not just its line numbers. The body cites `model/SourceKey.java:124-128` (a `SourceRowsCall -> Wrap.Row` compact-ctor pin) and `:288` (`SourceKey.Reader.SourceRowsCall(LifterRef)`); `SourceKey` today is a plain record (`:40`) with **no `Reader` interface and no `SourceRowsCall`**. The body's own 2026-07-13 re-anchor note (`:14`) still names `SourceKey.Reader.SourceRowsCall` / `Wrap` as "the live surface", so that note is itself stale. The sequencing line (`:33`) still reads "R431 ... plans to decompose"; R431 is Done. The mechanism (the lifter contract pin) was relocated by R431 into `KeyLift`/`LifterRef`/`Wrap`, so this is a design-level re-derivation of *where* the work attaches, not a line bump. Not touched this window. | **Re-spec the current-state / approach section** against the decomposed `KeyLift`/`LifterRef`/`Wrap` model. The goal (recordN key parity, non-jOOQ record parents) is intact, so this is a targeted re-derivation of the attachment surface. Drop the "R431 plans to decompose" tense (`:33`); R431 is Done. This is the same shape of fix R222 received last window; R71 remains the last item carrying the pre-R431 `SourceKey.Reader` shape as a *detailed live approach* rather than a passing mention. |

## C. Outdated: update references only (work valid, refs stale) (13)

Substance intact; names and line numbers drifted. The eleven prior-window §C items
all carry (R462 partially re-anchored by R508 but still `Split*`-stale), plus two
additions this window: R505 (born-stale `SourceKey.Reader`) and R51 (R508-retired
composite names).

### C.1 `planSlug` / `SourceKey.Reader` removal drift (carried + R505)

R484 (Done) removed `Rejection.Deferred.planSlug`; R431 (Done) removed the
`SourceKey.Reader` interface. Deferrals now anchor by `StubKey.VariantClass` (a
live class) and carry no roadmap-item pointer; column reads off a parent row lift
via `KeyLift.FkColumns`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R454** routine-write-result-shapes | Backlog | Line 18 describes the deferred shapes as "typed `Deferred`s pointing at this item's planSlug". The `planSlug` field no longer exists, and deferrals no longer carry a roadmap-item pointer. | **Re-anchor** the mechanism sentence: the deferred shapes surface at build time via `StubKey.VariantClass` naming the variant, with no roadmap pointer. Substance (which write result shapes stay deferred) is valid. |
| **R447** routine-chain-fetch-form-breadth | Backlog | Two causes. (a) Lines 18 and 33 name "typed `Deferred` landings whose `planSlug` points here" / "empty planSlug in R435"; `planSlug` is gone. (b) Line 24 (`SplitLookupTableField` composition) and line 26 (`RecordTableField` seam) name merged leaves as live. | **Re-anchor** both: drop the `planSlug` pointer phrasing (deferrals anchor by `StubKey.VariantClass` now), and repoint the two variant names to the `Batched*` leaves. |
| **R180** record-parent-column-read-helper | Spec | Prose (`:35`) says "R431 ... now In Progress" -> R431 is **Done**, and names `SourceKey.Reader.AccessorCall` as a live carrier -> `SourceKey` has no `Reader` interface today. The substance (consume the R461 `ClassAccessorResolver.enumerate` / `AccessorProbe` surface for record-parent column reads) is valid and its machinery is live (`:30`). | **Re-anchor** the `SourceKey.Reader.AccessorCall` carrier onto the decomposed model, and fix the R431 tense to Done. Borderline with §B (the carrier is fully gone, not renamed), but the core machinery it consumes (R461) is live, so a reference repoint suffices. |
| **R505** tenant-index-parent-row-routing | Backlog | **New this pass.** Line 21 names "a column read off the parent row (the `SourceKey.Reader` family)" as the carrier for the proposed `ParentRowBound` `TenantBinding` arm. `SourceKey.Reader` was removed by R431, *before* R505 was filed (2026-07-20), so it was born stale. The mechanism (per-row tenant read off a tenant-index parent) is live via `KeyLift.FkColumns`; only the one-phrase carrier name is wrong. | **Re-anchor** the parenthetical: a per-row column read lifts via `KeyLift.FkColumns` (there is no `SourceKey.Reader` family). One-phrase fix; the item's design intent is intact. |

### C.2 R432 leaf-merge / R314 dissolution / R431 anchor drift (carried, unchanged)

`SplitTableField`/`RecordTableField` -> `BatchedTableField`;
`SplitLookupTableField`/`RecordLookupTableField` -> `BatchedLookupTableField`;
`RecordTableMethodField` dissolved onto the record-sourced `BatchedTableField`
carrying `TableExpr.MethodCall`; the surviving `@tableMethod` leaf is
`TableMethodField`. `SplitRowsMethodEmitter` is **not** renamed and is correct
wherever it appears. R504 (Done, prior window) and R507 (In Review, this window)
scrubbed and now guard these dead names in code, javadoc, and `.adoc`, **but both
explicitly leave `roadmap/` prose untouched** (R507 excludes `roadmap/` from its
guard scan by design), so this cluster stays an audit responsibility.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R403** reintroduce-tablemethod-docs | Backlog | Line 44 names `ChildField.RecordTableMethodField` as the live construct the reintroduced docs would describe. That leaf no longer exists; the DTO-parent `@tableMethod` shape now classifies to a **record-sourced `BatchedTableField`** carrying `TableExpr.MethodCall`. | **Re-anchor** the construct description to the post-R314 shape. |
| **R109** list-valued-external-field-multiset | Spec | Line 51's planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** the planned test assertion to `BatchedTableField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Lines 37, 45, 128 name `SplitTableField` / `SplitLookupTableField` as live. `SplitRowsMethodEmitter` in the same passages is correct. R508 re-anchored this item's *composite*-name mentions (`ColumnField`/`CompositeColumnField` -> `ColumnBackedField`, "any arity") this window, but the `Split*` names it did not own remain. | **Re-anchor** the surviving `Split*` variant names; leave `SplitRowsMethodEmitter` and the already-fixed `ColumnBackedField` mentions untouched. |
| **R242** dml-payload-positional-alignment | Spec | Lines 37-38's R305 lineage note "collapsed it into `RecordTableField`, `ChildField.java:912`" is **doubly stale and worse than last window in the opposite direction**: the name is now `BatchedTableField` (`:521`, shifted *up* from `:538` by R508), and the cite `:912` today lands inside `PivotField`/`NestingField` territory (`:911`/`:875`), not on any merged leaf. | **Re-anchor** that one name + cite (`BatchedTableField`, `:521`); leave the surrounding R305/R287 history. |
| **R288** inline-interface-and-tablemethod-children | Backlog | Line 35: "a keyed batch (DataLoader, as `SplitTableField` / `RecordTableField` do via `SplitRowsMethodEmitter`)". Variant names stale; emitter fine. | **Re-anchor** the two variant names. |
| **R472** nested-generated-condition-filters-never-emitted | Backlog | Lines 20-21: classifier attaches `GeneratedConditionFilter` to a nested `SplitTableField` / `SplitLookupTableField` / inline `TableField` / `LookupTableField`. (`TableField` / `LookupTableField` are still live.) | **Re-anchor** the two `Split*` names. |
| **R116** composite-key-row2-source-row-coverage | Backlog | Line 15: the planned `COMPOSITE_KEY_ROW2_PATH_KEYED` case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R7** decompose-typefetchergenerator | Backlog | Line 30 proposes a hypothetical decomposed emitter `SplitTableFieldEmitter`; a decomposition done today would name it `BatchedTableFieldEmitter`. Illustrative "etc." naming; the list of *existing* emitters (incl. `SplitRowsMethodEmitter` `:32`) is correct. | **Low priority:** refresh the illustrative name at pickup. Not blocking. |

### C.3 R508 composite-column dissolution drift (new)

R508 (Done, this window) merged `ColumnField`/`CompositeColumnField` ->
`ColumnBackedField` and `ColumnReferenceField`/`CompositeColumnReferenceField` ->
`ColumnBackedReferenceField`. Its re-anchor pass caught five items but missed one.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R51** propertyfield-recordfield-nullable-column | Backlog | **New this pass.** Line 13's R50 lineage clause names `ChildField.ColumnField` / `ColumnReferenceField` as the carriers R50 retired `columnName` on; both were merged into `ColumnBackedField` / `ColumnBackedReferenceField` by R508. Namespaced, live-looking cites (not a bare prose mention). The item's own subject (`PropertyField`/`RecordField`, both live) is unaffected. | **Re-anchor** the two lineage names to `ColumnBackedField` / `ColumnBackedReferenceField`; leave the surrounding R50 history. Low priority (lineage-precedent clause, no line cite). |

**No flag (verified lineage / self-noted / re-baselined this window):** **R222**
(Spec) was re-baselined again by R508 (`InputField` family names updated with an
in-line "dissolved ... in R508" note); its prior-window supersession banner is
intact. **R333** (Ready) had its "What dissolves" section annotated with the R508
landing and its DML-residue note updated to the shipped R489; its many
`ColumnField`/`SplitTableField`/`:275` mentions are Discovery and "what dissolves"
design substrate, not live claims, consistent with the prior audit's read (R508's
author re-anchored the live-claim spots). **R24 / R27 / R419** (all Backlog) were
re-anchored to `ColumnBacked*` by R508 this window. **R323**
(`nestingfield-multiparent-batchkey-leaves`, Backlog) self-flagged with a
"Re-anchor at pickup (added 2026-07-15)" note naming the old `Split*`/`Record*`
variant names; pre-declared, `depends-on` cleared. **R302** and
`rename-childfield-to-sourcefield` mention `SingleRecordTableField` only in R305
lineage clauses. **R507** (In Review) names the retired leaf families deliberately
as its guard's seed subject matter, not as a live claim.

## D. Structural: (0)

Empty. `changelog.md` carries `next-id: R510`, clearing the max allocated id
(R509). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and a
`depends-on:` sweep over all 131 item files resolves every live edge to a present
file. The one new item (R509) carries well-formed front-matter (`id`, `status`,
`bucket`, `created`, `last-updated`).

## Cross-cutting observations

1. **R507 landed but does not, and will not, retire the §C cluster; the prior
   audit's prediction was wrong.** R507's `RetiredVocabularyGuardTest` scans code
   comments, javadoc, main-source string literals, authored `.adoc`, and fixture
   SDL, and it explicitly excludes `roadmap/` (item bodies are transient; the
   changelog and these audits must be free to name retired terms). Its spec names
   "roadmap item body currency" as out of scope, "owned by the periodic staleness
   audits". So the recurring §C re-anchor work stays with these audits by design.
   R507 is a genuine drift-prevention win for *code and doc* prose, and its empty
   allowlist proves the R126/R504 code scrubs held; it is simply orthogonal to the
   roadmap-prose cluster. **Correct the standing expectation: the §C cluster will
   not self-retire when R507 reaches Done.**
2. **Additive/subtractive model growth is a two-way line-anchor drift source.**
   Last window R501/R503 *added* leaves and shifted anchors down (`BatchedTableField`
   `:519 -> :538`); this window R508 *removed* leaves and shifted them back up
   (`:538 -> :521`, `ServiceTableField` `:1046 -> :1029`). Items citing raw
   `ChildField.java:<n>` line numbers (notably R242) drift on **any** leaf-count
   change, in either direction, even when their own subject is untouched. The
   convention holds and is reinforced: prefer symbol-anchored references over bare
   line numbers.
3. **A targeted re-anchor sweep is not a substitute for the audit.** R508's slice-4
   re-anchor caught five items (R24/R27/R419/R462/R333) but missed R51's
   composite-name lineage clause, and it only touched the *composite* axis, leaving
   R462's older `Split*` names in place. Feature-author re-anchoring is a useful
   partial mitigation but is scoped to the names that author is thinking about; the
   audit remains the only pass that reviews every item against the whole current
   model.
4. **Born-stale items exist and need vetting at filing, not just at renumber.**
   R505 was filed 2026-07-20 naming `SourceKey.Reader`, a construct R431 had
   already deleted. The prior audit logged R505's *existence* (new file, well-formed
   front-matter) but did not read its body against the current model. A newly filed
   item is not automatically current; the audit should read new-item bodies, not
   just count them.
5. **The re-platforming trilogy stayed complete and stable.** R431/R432/R314 did
   not reopen; the retired leaf names exist in code only as
   `former`/`guarded`/`dissolved` lineage prose, now guard-enforced by R507. The
   churn source stayed on emit/feature work (R45, R489, R508), not model
   decomposition, as the prior audits predicted.
6. **Carried cosmetic front-matter nit persists.** **R97**
   (`consumer-derived-input-tables`) still lacks `created:` / `last-updated:`. Not a
   build or dependency risk.
7. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-22._
