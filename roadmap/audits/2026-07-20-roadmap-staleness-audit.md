# Roadmap staleness audit: 2026-07-20

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `e472187`, committed 2026-07-19 22:36, audited 2026-07-20). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-17` staleness audit, which has been deleted;
only the latest staleness audit is retained. Three siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  meant to be edited in place as scope iterates. It still names none of the
  reference-projection bug pair (R499/R500) or the `@pivot` feature (R501) filed
  this window, and its MUST/SHOULD tables continue to lag the post-decomposition
  model; refreshing it stays out of scope for this staleness pass.
- `2026-07-04-r222-r333-conformance-analysis.md` is the R222/R333 conformance
  analysis, a companion to the R314/R333 design session. It is a targeted
  implementation-vs-spec conformance record, not a point-in-time staleness
  review; left in place. (R333 stayed **Ready** this window; the analysis remains
  a sign-off input and stays as lineage.)

`classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
inventory; its "superseded, historical" banner is intact. No action; it stays as
lineage.

## Headline: a consolidation window, not a churn window: the javadoc-reference arc closed and resolved the prior §A, and the model-decomposition trilogy stayed put

The prior two windows were driven by model re-platforming (R431 `SourceKey`
decomposition, R432 the `Split*`/`Record*` leaf merge, R314 the reentry-leaf
dissolution), which re-drifted the same ride-along cluster three times running.
That trilogy is **complete and did not move this window**: `ChildField`'s variant
set is unchanged (`BatchedTableField` `:519`, `BatchedLookupTableField` `:653`,
`TableMethodField` `:722`, with no `RecordTableMethodField`), so the ten carried
`§C` items sit on the **same** stale anchors as three days ago, none re-anchored,
none freshly broken by a rename.

What moved instead was hygiene and consolidation:

- **The prior §A resolved itself before it could be actioned.** The prior audit's
  one §A finding was the **R491/R493 duplicate** (two independent follow-on
  batches filing the same "purge roadmap ids from generated javadoc" cleanup).
  **R484** (`javadoc-string-literal-roadmap-reference-purge`, Done) **subsumed and
  discarded both** as part of a broader string-literal purge (changelog:
  "Subsumes and discards Backlog stubs R491 ... and R493"). The collision was
  handled correctly; §A is empty again.
- **One fresh reference-drift, from the same R484.** R484 removed the
  `Rejection.Deferred.planSlug` field entirely (deferrals now anchor by
  `StubKey.VariantClass`, a live class, and no longer name a roadmap item). Two
  items still describe deferrals as "typed `Deferred`s whose planSlug points
  here": **R447** (already carried in §C) and **R454** (new to the flagged set).
- **Two additive sealed refactors landed but re-drift nothing.** **R443**
  (`BuildContext.ConditionResolution` -> sealed `Resolved`/`Failed`/`Unresolved`)
  is behaviour-preserving and touches a different condition mechanism than R472
  names; **R502** (`GraphitronType.JooqRecordCarrier` reified as a sealed
  `ResultType` intermediate) is additive and referenced only as live lineage by
  the new R501. Neither made an existing item stale.

Net: **0 §A / 2 §B / 11 §C**, §D empty. §A returns to empty after one window; the
flagged set is nearly identical to the prior window, minus the resolved duplicate
and plus one `planSlug` casualty.

## Changes since the 2026-07-17 audit

**49 commits** landed between the prior audit commit (`0e5ac25`, 2026-07-17 21:59,
which is also the commit that published the prior audit) and this one (HEAD
`e472187`, 2026-07-19 22:36). One nuance about the baseline: the prior audit's
prose narrated HEAD `d4ff640` (2026-07-16 23:23) but was committed two commits
later at `0e5ac25`, by which point **R484 had already reached Done and discarded
R491/R493**. So the prior audit's §A was already stale at its own publish time.
Five things drove the window:

**1. The javadoc-reference cleanup arc closed.** **R484** ran to **Done**
(`0e5ac25`), purging `R<n>`/`roadmap/<slug>` citations from generator string
literals, dropping the `Rejection.Deferred.planSlug` field, and **subsuming
R491/R493**. **R483** (`javadoc-implementation-drift-audit`) reached **Done** (was
In Review last window). Two of its six follow-on pins also closed: **R495**
(`input-record-generator-service-audit-javadoc`) and **R497**
(`federation-spec-url-caller-census`), both Done. The remaining follow-ons
(R494, R496, R498) stay Backlog.

**2. Two sealed refactors shipped.** **R443** (a repurposed `post-r438-*` Backlog
stub) ran Backlog -> **Done**, sealing `BuildContext.ConditionResolution` into a
tri-state (`Resolved`/`Failed`/`Unresolved`); behaviour-preserving. **R502**
(`JooqRecordCarrier`) was **filed and shipped in-window** (Backlog -> Done),
reifying the jOOQ-Record carrier partition as a sealed `ResultType` intermediate
(`GraphitronType.java:153`).

**3. R488 closed.** **R488** (`concept-explainer-item-crosslinks`) reached **Done**
(was In Review last window).

**4. Two new bug items and one feature filed.** **R499**
(`connection-fields-shared-result-key-projection`, In Review) and **R500**
(`result-key-aware-reference-projection`, Ready) are a **cross-referenced bug
pair** on `$fields`-by-name-vs-result-key projection (R499 mentions R500, R500
mentions R499 16 times); distinct scopes, not a duplicate. **R501**
(`pivot-projection-directive`, Ready) is the `@pivot` discriminator-keyed
aggregate feature, drafted and driven through several spec revisions to Ready.

**5. Prose scrubs.** **R126** (`scrub-stale-batchkey-prose`) ran Backlog ->
**In Review**, scrubbing retired classification vocabulary from **code** comments
and test-source strings (not roadmap items). **R24** had its body re-anchored off
the removed `planSlug` pointer onto `Rejection.StubKey.VariantClass`
(`f926109`), so R24 is **not** flagged.

**Terminal closures this window (Done, all self-deleted):** R484, R483, R488,
R443, R495, R497, R502. Each has a `changelog.md` entry. R126 (In Review), R347
(In Progress), R427/R500/R501 (Ready), R333 (Ready), R499 (In Review) carry work
but are not Done.

**Board accounting.** **134 item files** today, down from the prior audit's
narrated 138 (the count reflects seven closures against three new files, plus the
prior audit's own baseline drift). Status distribution: **115 Backlog, 12 Spec,
4 Ready (R333, R427, R500, R501), 1 In Progress (R347), 2 In Review (R126,
R499); zero Done**. A non-recursive `^status: Done` grep over `roadmap/*.md`
returns nothing (tombstone-free for the fourteenth window running). No duplicate
`id:`; max allocated id **R503**, and `next-id: R504` clears it. A `depends-on:`
sweep over all 134 files found **no dangling slugs**. New ids filed this window
and still live: **R499, R500, R501, R503** (all well-formed front-matter). The
board is structurally clean.

**Net effect on flag counts: 13 flagged, 121 current.** 0 §A, 2 §B, 11 §C, 0 §D.

## Scope and method

All **134** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded). The claimed
model constants were verified at the symbol: `ChildField.java` declares
`TableField` (`:447`), `BatchedTableField` (`:519`), `LookupTableField` (`:625`),
`BatchedLookupTableField` (`:653`), `TableMethodField` (`:722`), and
`ServiceTableField` (**`:913`**, shifted from `:911` last window), with **no
`RecordTableMethodField`** (a source grep finds it only in `retired`/`dissolved`
prose in `CatalogBuilder.java` and `TableExpr.java`). `SourceKey.java` is the
plain `public record SourceKey(...)` residue (`:40`) with **no `Reader`
interface and no `SourceRowsCall`**. `KeyLift.java` and `LifterRef.java` are both
live (the §B/§C re-anchor targets exist). The two new sealed types were confirmed:
`BuildContext.ConditionResolution` (`:2237`, sealed tri-state) and
`GraphitronType.JooqRecordCarrier` (`:153`, sealed `ResultType`). `planSlug` is
**gone** from the codebase; `Rejection.deferred(...)` now takes `(summary)` /
`(summary, fieldClass)` and anchors via `StubKey.VariantClass`.

**Anchors that held (re-verified, no drift this window):** the ten carried §C
items were re-checked against unchanged files; none was re-anchored and none was
freshly broken beyond the `ServiceTableField` `:911`->`:913` shift (which nudges
R242's already-stale cite). R66 / R7 / R35 remain re-derive-at-pickup by
construction.

## A. Obsolete: should leave the active roadmap (0)

Empty. The prior window's one §A finding, the **R491/R493 duplicate**, was
resolved: **R484 (Done) subsumed and discarded both** as part of its
string-literal purge, so the entire generated-output javadoc-id cleanup shipped
rather than lingering as a colliding pair. No new obsolete item surfaced this
window; the R499/R500 bug pair was checked for a repeat of that collision and is
a deliberately cross-referenced split, not a duplicate.

## B. Outdated: needs re-spec (premise or targets materially changed) (2)

Both carried from the prior window, bodies **unedited** since, premises still
obsoleted by the now-complete R431/R432/R314 trilogy.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | R431 (Done) **deleted the target surface**, not just its line numbers. The body cites `model/SourceKey.java:124-128` (a `SourceRowsCall -> Wrap.Row` compact-ctor pin) and `:288` (`SourceKey.Reader.SourceRowsCall(LifterRef)`); `SourceKey` today is a plain record (`:40`) with **no `Reader` interface and no `SourceRowsCall`**. The body's own 2026-07-13 re-anchor note (`:14`) still names `SourceKey.Reader.SourceRowsCall` / `Wrap` as "the live surface", so that note is itself stale. `LifterRef` is its own file, carried by `KeyLift.Lifter`; the pin relocated to `KeyLift`. | **Re-spec the current-state / approach section** against the decomposed `KeyLift`/`LifterRef`/`Wrap` model R431 introduced. The goal (recordN key parity, non-jOOQ record parents) is intact, so this is a targeted re-derivation of *where* the work attaches, not a new item. Drop the "R431 plans to decompose" tense (`:33`); R431 is Done. |
| **R222** dimensional-model-pivot | Spec | The "researched 2026-06-16" current-state inventory describes the model as it stood before three items executed against it: it still calls out `SingleRecordTableField`/`RecordTableField`/`RecordLookupTableField`/`RecordTableMethodField` as live permits (`:281-293`, `:35`, `:289`, `:415`) and labels R431 "Spec" (`:243`). Since then **R431, R432, and R314 all reached Done**: the `Record*`/`Split*` leaves are `Batched*`, and `RecordTableMethodField` is dissolved. The entire "current state" section is historical. | **Re-baseline the current-state inventory.** Add a dated supersession banner ("current-state inventory below is the 2026-06-16 snapshot; since executed by R431, R432, and R314, all Done") over the research snapshot rather than editing it in place (preserve the dated evidence), then re-derive the "as of now" picture. The **umbrella direction (the dimensional pivot) survives intact and is more clearly warranted now**, so this is a re-spec of the starting-point description, not an obsolescence. Also re-anchor the `Record*` permit names and fix the R431 status label. |

## C. Outdated: update references only (work valid, refs stale) (11)

Substance intact; names/line numbers drifted. All ten of the prior window's §C
items are carried **unchanged** (no re-anchor landed this window); one item
(**R454**) is added, and **R447** picks up a second stale-ref cause, both from
R484's `planSlug` removal.

### C.1 `planSlug` removal drift (fresh this window)

R484 (Done) removed `Rejection.Deferred.planSlug`; deferrals now anchor by
`StubKey.VariantClass` (a live class) and no longer name a roadmap item.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R454** routine-write-result-shapes | Backlog | Line 18 describes the deferred shapes as "typed `Deferred`s pointing at this item's planSlug". The `planSlug` field no longer exists, and deferrals no longer carry a roadmap-item pointer at all. | **Re-anchor** the mechanism sentence: the deferred shapes surface at build time via `StubKey.VariantClass` naming the variant, with no roadmap pointer. Substance (which write result shapes stay deferred) is valid. Fresh drift this window. |
| **R447** routine-chain-fetch-form-breadth | Backlog | Two causes now. (a) *Fresh:* lines 18 and 33 name a "typed `Deferred` landings whose `planSlug` points here" / "empty planSlug in R435"; `planSlug` is gone. (b) *Carried:* line 24 (`SplitLookupTableField` composition) and line 26 (`RecordTableField` seam) name merged leaves as live. | **Re-anchor** both: drop the `planSlug` pointer phrasing (deferrals anchor by `StubKey.VariantClass` now), and repoint the two variant names to the `Batched*` leaves. |

### C.2 R432 leaf-merge / R314 dissolution / R431 anchor drift (carried, unchanged)

`SplitTableField`/`RecordTableField` -> `BatchedTableField`;
`SplitLookupTableField`/`RecordLookupTableField` -> `BatchedLookupTableField`;
`RecordTableMethodField` dissolved onto the record-sourced `BatchedTableField`
carrying `TableExpr.MethodCall`; the surviving `@tableMethod` leaf is
`TableMethodField`. `SplitRowsMethodEmitter` is **not** renamed and is correct
wherever it appears.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R403** reintroduce-tablemethod-docs | Backlog | Line 44 names `ChildField.RecordTableMethodField` as the live construct the reintroduced docs would describe. That leaf no longer exists; the DTO-parent `@tableMethod` shape now classifies to a **record-sourced `BatchedTableField`** carrying `TableExpr.MethodCall`. | **Re-anchor** the construct description to the post-R314 shape. |
| **R109** list-valued-external-field-multiset | Spec | Line 51's planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". (The file was touched this window only for a neighbouring test-name fix, `:57`; the `:51` assertion was not re-anchored.) | **Re-anchor** the planned test assertion to `BatchedTableField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Lines 21, 28, 39, 41-42, 127 name `SplitTableField` / `SplitLookupTableField` as live. `SplitRowsMethodEmitter` in the same passages is correct. | **Re-anchor** the variant names; leave `SplitRowsMethodEmitter` untouched. |
| **R242** dml-payload-positional-alignment | Spec | Lines 37-38's R305 lineage note "collapsed it into `RecordTableField`, `ChildField.java:912`" is **doubly stale**: the name is now `BatchedTableField` (`:519`), and the cite `:912` today lands on `ServiceTableField` (now `:913`, a different live variant), not the merged leaf. | **Re-anchor** that one name + cite (`BatchedTableField`, `:519`); leave the surrounding R305/R287 history. |
| **R288** inline-interface-and-tablemethod-children | Backlog | Line 35: "a keyed batch (DataLoader, as `SplitTableField` / `RecordTableField` do via `SplitRowsMethodEmitter`)". Variant names stale; emitter fine. | **Re-anchor** the two variant names. |
| **R472** nested-generated-condition-filters-never-emitted | Backlog | Lines 20-21: classifier attaches `GeneratedConditionFilter` to a nested `SplitTableField` / `SplitLookupTableField` / inline `TableField` / `LookupTableField`. (`TableField` / `LookupTableField` are still live.) R443's `ConditionResolution` refactor touches a *different* condition mechanism (path-element resolution) and does **not** change this item's emit-gap premise. | **Re-anchor** the two `Split*` names. |
| **R116** composite-key-row2-source-row-coverage | Backlog | Line 15: the planned `COMPOSITE_KEY_ROW2_PATH_KEYED` case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R180** record-parent-column-read-helper | Spec | Prose (`:35`) says "R431 ... now In Progress" -> R431 is **Done**, and names `SourceKey.Reader.AccessorCall` as a live carrier -> `SourceKey` has no `Reader` interface today. Substance (consume the accessor surface for record-parent column reads) valid. | **Re-anchor** the `SourceKey.Reader.AccessorCall` carrier onto the decomposed model, and fix the R431 tense to Done. |
| **R7** decompose-typefetchergenerator | Backlog | Line 30 proposes a hypothetical decomposed emitter `SplitTableFieldEmitter`; a decomposition done today would name it `BatchedTableFieldEmitter`. Illustrative "etc." naming; the list of *existing* emitters (incl. `SplitRowsMethodEmitter`) is correct. | **Low priority:** refresh the illustrative name at pickup. Not blocking. |

**No flag (verified lineage / self-noted):** **R333** (Ready) carries explicit
shipped-notes for both R432 and R314; its old-name mentions are Discovery/lineage
evidence. **R323** (Backlog) self-flagged with a "Re-anchor at pickup (added
2026-07-15)" note (`:27`) naming the old variant names; pre-declared, `depends-on`
cleared. **R302** and `rename-childfield-to-sourcefield` mention
`SingleRecordTableField` only in R305 lineage clauses. **R24** was re-anchored off
the removed `planSlug` this window and is current. `coordinate-lowers-*` (R333)
and `nestingfield-multiparent-batchkey-leaves` (R323) carry the merged names as
lineage/self-noted, not as fresh stale claims.

## D. Structural: (0)

Empty. `changelog.md` carries `next-id: R504`, clearing the max allocated id
(R503). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and a
`depends-on:` sweep over all 134 files resolves every edge to a present file.

## Cross-cutting observations

1. **The re-platforming trilogy is complete and the cluster stopped re-drifting
   from that source.** R431/R432/R314 did not move this window, and `ChildField`'s
   variant set is unchanged, so the ten carried §C items sit on the same anchors
   as three days ago. As the prior audit predicted, the next churn source is
   R333/R222 emit work, not model decomposition. The standing convention holds:
   prefer symbol-anchored references over bare line numbers, and re-anchor
   ride-along items each slice.
2. **String-literal / comment cleanup is itself a drift source.** R484 removing
   `planSlug` fixed R24 (re-anchored in the same arc) but left R447 and R454
   describing the retired pointer. When a cleanup removes a *field that items
   describe as a mechanism* (not just a symbol name), sweep the roadmap for prose
   naming that mechanism, not only for `{@link}` targets.
3. **The parallel-follow-on-filing failure mode did not recur, and its prior
   instance self-resolved.** The prior §A (R491/R493) was closed by R484
   subsuming both. The new bug pair R499/R500 was filed the same day by related
   work but cross-references itself explicitly, so it is a deliberate split, not a
   collision. The dedupe-on-create check the prior audit suggested is still worth
   having, but there was nothing to catch this window.
4. **Additive sealed refactors did not create staleness.** R443
   (`ConditionResolution`) is behaviour-preserving and touches a different
   condition path than R472 names; R502 (`JooqRecordCarrier`) is additive and
   referenced only as live lineage by R501. Neither re-drifted an existing item.
5. **Carried cosmetic front-matter nit persists.** **R97**
   (`consumer-derived-input-tables`) still lacks `created:` / `last-updated:`.
   Not a build or dependency risk.
6. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-20._
