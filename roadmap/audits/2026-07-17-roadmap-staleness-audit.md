# Roadmap staleness audit: 2026-07-17

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `d4ff640`, committed 2026-07-16 23:23, audited 2026-07-17). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-16` staleness audit, which has been deleted;
only the latest staleness audit is retained. Three siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  meant to be edited in place as scope iterates. It reads further behind again:
  it still names none of the reentry-leaf dissolution (R314) or the polymorphic
  parent-holds-FK closure (R481) that landed this window, so its MUST/SHOULD
  tables lag further. Refreshing it is out of scope for this staleness pass.
- `2026-07-04-r222-r333-conformance-analysis.md` is the R222/R333 conformance
  analysis, a companion to the R314/R333 design session. It is a targeted
  implementation-vs-spec conformance record, not a point-in-time staleness
  review; left in place. (R333 stayed **Ready** this window; the analysis remains
  a sign-off input and stays as lineage. Note R314, the emit consumer it named as
  pending, reached **Done** this window.)

`classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
inventory; its "superseded, historical" banner (added 2026-07-14) is intact. No
action; it stays as lineage.

## Headline: R314 dissolved the reentry leaves, re-drifting the same ride-along cluster a third time; and a fresh duplicate slipped onto the board

The story of this window is the SourceKey/leaf re-platforming finishing its third
chapter. The prior two windows landed R431 (`SourceKey` decomposition) and R432
(the `Split*`/`Record*` table-leaf merge into `Batched*`). This window **R314
reached Done** (`d8f5094`), dissolving the reentry family onto the model: it
**dissolved `RecordTableMethodField`** onto the merged batched leaf (`4abde9e`),
**retired `dispatchPerformsReFetch`** (`1158c14`), and renamed the surviving
`@tableMethod` leaf to `TableMethodField`. That deleted one more construct the
ride-along cluster names, so the same cluster (R109, R462, R288, R472, R447,
R116, R242, R7, R180, R222) drifted a third consecutive window, and one carried
Backlog item (**R403**) picked up fresh drift because it cites the now-dissolved
`RecordTableMethodField` as a live construct.

Two structural notes distinguish this window from the last:

- **The prior audit's one structural finding was fixed.** `changelog.md` now
  carries `next-id: R499` (was `R485`, colliding with the already-allocated
  `fk-hop-narrowing-helpers`); the counter climbed cleanly past every allocation
  this window. §D is empty.
- **A duplicate pair reached the board.** **R491**
  (`generated-javadoc-roadmap-slug-purge`, filed by R482's follow-on at 14:53) and
  **R493** (`strip-roadmap-ids-from-generated-javadoc`, filed by the R483 audit at
  23:19) describe the **same scope** (purge transient roadmap ids from generator
  `addJavadoc` string literals in generated output) with no cross-reference. This
  is the one item that should leave the active roadmap (§A).

Net: **1 §A / 2 §B / 11 §C**, §D empty. §A is non-empty for the first time in
thirteen windows, driven not by a stranded construct but by a filing collision:
two independent follow-on batches filed the same cleanup.

## Changes since the 2026-07-16 audit

**49 commits** landed between the prior audit commit (`caf8911`, 2026-07-16 11:06)
and this one (HEAD `d4ff640`, 2026-07-16 23:23) in a dense ~12-hour window. (The
prior audit's analysis narrated HEAD `75f0b19`, since rebased away; its published
board table is the baseline used here.) Five things drove it:

**1. Reentry-leaf dissolution (R314) closed.** **R314**
(`dissolve-reentry-leaves-dimensional-emit`) ran Ready -> In Progress -> In Review
-> **Done** (`50460a8`..`d8f5094`) across six slices: a site-level reentry fact +
command registry (`d1f13a2`), one batched-field DataFetcher builder for both
source shapes (`7137d1e`), **`RecordTableMethodField` dissolved onto the merged
batched leaf** via a new `TableExpr.MethodCall` arm (`4abde9e`), the service and
DML reentry rows named (`4e04345`, `11122a4`), **`dispatchPerformsReFetch`
retired** for an implementedness guard (`1158c14`), and a docs sweep (`88ddf90`).
Self-deleted on Done. This is the fresh drift source for the §C block below.

**2. The `{@link}`-reference build gate (R492) shipped and closed.** **R492**
(`javadoc-reference-validity-build-gate`) ran the full Backlog -> **Done**
lifecycle in-window (`0a0d118`..`731aac8`): a `maven-javadoc-plugin` `reference`-
doclint gate bound to `verify`, driving 41 pre-existing broken `{@link}`s to zero.
Self-deleted. It is the prerequisite R483's relink work builds on.

**3. The javadoc drift audit (R483) ran its fan-out.** **R483**
(`javadoc-implementation-drift-audit`) drove Spec -> Ready -> In Progress ->
**In Review** (`bd38cf3`..`d4ff640`): an eleven-partition javadoc-vs-implementation
drift pass across every in-scope module, recording its outcome and **filing six
follow-on pins** (R493-R498). Still In Review, not yet Done.

**4. Four more closures.** **R201** (`honor-field-directive-in-payload-
construction-shape`) reached **Done** (`fb3e67a`), resolving last window's §C.2
flag by shipping it. **R481** (`single-cardinality-polymorphic-child-parent-fk-
projection`) reached **Done** (`ac52f7b`), spawning R487. **R482** (`javadoc-
roadmap-reference-purge`) reached **Done** (`c95a887`), spawning R490/R491.
**R486** (`roadmap-concept-explainer-space`, the `roadmap/concepts/` HTML pages)
reached **Done** (`8aab6a6`..), absorbing the R458 sketch; **R458** (In Progress
last window) closed with it. All self-deleted. **R488** (`concept-explainer-item-
crosslinks`) ran Spec -> **In Review** (`3b6ba97`..`1432ff1`).

**5. R427 pivot continued.** **R427** (`relevance-ranked-search`) ran several
design rounds (`169ceba`..`724550e`, Oracle-docs, collation, grain, PostgreSQL-
floor) and reached **Ready**; v1 is `@typeahead`, prose search requester-gated.
Heavily rewritten but internally current.

**Terminal closures this window (Done, all self-deleted):** R314, R492, R201,
R481, R482, R486 (+ R458 absorbed). R483 and R488 landed work but are **In
Review**, not yet Done.

**Board accounting.** **138 item files** today, up from the prior audit's 133.
Status distribution: **121 Backlog, 12 Spec, 2 Ready (R333, R427), 1 In Progress
(R347), 2 In Review (R483, R488); zero Done**. A non-recursive `^status: Done`
grep over `roadmap/*.md` returns nothing (tombstone-free for the thirteenth window
running). No duplicate `id:`; max allocated id **R498**, and `next-id: R499` now
clears it (the prior window's collision is fixed). A `depends-on:` sweep over all
138 files found **no dangling slugs** (every edge resolves to a present file; the
R431/R432 edges cleared at closure stayed cleared). New ids filed this window and
still live: **R487, R488, R489, R490, R491, R493, R494, R495, R496, R497, R498**
(R488 In Review, the rest Backlog); all have well-formed front-matter. The board
is structurally clean; the one non-cosmetic finding is the R491/R493 duplicate
(§A), which is a content collision, not a front-matter defect.

**Net effect on flag counts: 14 flagged, 124 current.** 1 §A, 2 §B, 11 §C, 0 §D.

## Scope and method

All **138** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded). The big code
change was verified at the symbol: `ChildField.java` declares `BatchedTableField`
(`:519`), `BatchedLookupTableField` (`:653`), and `TableMethodField` (`:722`),
with **no `RecordTableMethodField`** (a source-wide grep finds it only in
`retired`/`dissolved` prose in `TableExpr.java`, `CatalogBuilder.java`, and test
javadoc). `SourceKey.java` remains the `(columns, wrap)` residue with no `Reader`
interface. `SplitRowsMethodEmitter` was re-confirmed **live** (not renamed by
R314/R432); `ServiceTableField` (`:911`), `TableField` (`:447`), and
`LookupTableField` (`:625`) remain live variants and their roadmap references are
**not** stale. `FieldBuilder`'s payload-construction anchors moved again with
R201's own shipment (`resolvePayloadConstructionShape` now `:522`), but R201 is
Done, so that drift is moot.

**Anchors that held (re-verified exact, no drift this window):** R180's
`recordColumnReadArgs` (`GeneratorUtils.java:290`) and `buildFkRowKey` (`:274`)
are exact; R66 / R7 / R35 remain re-derive-at-pickup by construction; R10 / R236 /
R234 / R240 / R242 (code anchors other than the leaf name) / R103 / R24 sit in
files untouched by this window's reentry churn.

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R493** strip-roadmap-ids-from-generated-javadoc | Backlog | **Duplicate of R491** (`generated-javadoc-roadmap-slug-purge`). Both were filed this window, eight hours apart, by different follow-on batches (R491 by R482's follow-on `da0cc2c`; R493 by the R483 audit `2d4b6c2`), and **neither cross-references the other**. Both scope the identical target: transient roadmap ids baked into generated output via generator `addJavadoc` string literals, out of reach of the R482 comment-guard and the R492 reference gate by construction. R491 is the broader umbrella ("all documentation-emitting generator string literals", example `ConnectionRuntimeClassGenerator`); R493 is the concrete instance list (`ErrorRouterClassGenerator`, `GraphitronDevExecutorGenerator`, `GraphitronFacadeGenerator`) plus a useful golden-test caveat. R493 ⊆ R491. | **Consolidate and discard one.** Fold R493's concrete generator list and its golden-output-test caveat into R491 (the first-filed umbrella) as the worked target set, add the "surfaced by R483 audit" provenance, and **discard R493**. Both are Backlog and unstarted, so the merge is cheap. (Direction is reversible: keep whichever slug the user prefers, but they must not both execute.) Also relates to R495 (`input-record-generator-service-audit-javadoc`), which is **distinct** (a contradiction-reconciliation, not an id purge) and should stay. |

## B. Outdated: needs re-spec (premise or targets materially changed) (2)

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried from last window's §B. R431 (Done) **deleted the target surface**, not just its line numbers. The body cites `model/SourceKey.java:124-128` (a `SourceRowsCall -> Wrap.Row` compact-ctor pin) and `:288` (`SourceKey.Reader.SourceRowsCall(LifterRef)`); `SourceKey` has no `Reader` interface or `SourceRowsCall` arm today. `LifterRef` is its own file (`model/LifterRef.java`), carried by `KeyLift.Lifter`; the pin relocated to `KeyLift`. `SourceRowsCall` survives only as prose. Unchanged since last window (the audit only recommends; the body was not edited). | **Re-spec the current-state / approach section** against the decomposed `KeyLift`/`LifterRef`/`Wrap` model R431 introduced. The goal (recordN key parity, non-jOOQ record parents) is intact, so this is a targeted re-derivation of *where* the work attaches, not a new item. Drop the "R431 plans to decompose" tense (`:33`); R431 is Done. |
| **R222** dimensional-model-pivot | Spec | Escalated from last window's §C.2 to **re-spec**. The "researched 2026-06-16" inventory describes the current model as it stood before three separate items executed against it: it still calls `SourceKey` the 6-component `(target, columns, path, wrap, cardinality, reader)` (`:267` reader-mechanism list), names `SingleRecordTableField`/`RecordTableField`/`RecordTableMethodField`/`RecordLookupTableField` as live permits (`:35`, `:281-293`, `:415`), and labels R431 "Spec" (`:243`). Since then **R431, R432, and R314 all reached Done**: `target`/`path`/`cardinality`/`reader` are gone, the `Record*`/`Split*` leaves are `Batched*`, and `RecordTableMethodField` is dissolved. The entire "current state" section is now historical, so this is no longer a token swap. | **Re-baseline the current-state inventory.** Add a dated supersession banner ("current-state inventory below is the 2026-06-16 snapshot; since executed by R431, R432, and R314, all Done") over the research snapshot rather than editing it in place (preserve the dated evidence), then re-derive the "as of now" picture against the post-decomposition model. The **umbrella direction (the dimensional pivot) survives intact and is more clearly warranted now**, which is why this is a re-spec of the starting-point description, not an obsolescence. Also re-anchor the two `Record*` permit names and fix the R431 status label. |

## C. Outdated: update references only (work valid, refs stale) (11)

Substance intact; names/line numbers drifted. Two causes: R314's reentry-leaf
dissolution (fresh this window, top block) and carried R432 leaf-merge / R431
anchors that re-drifted (bottom block).

### C.1 R314 dissolution drift (fresh this window)

`RecordTableMethodField` dissolved onto the merged batched leaf (record-sourced
`BatchedTableField` whose terminal hop carries `TableExpr.MethodCall`); the
surviving `@tableMethod` leaf is `TableMethodField`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R403** reintroduce-tablemethod-docs | Backlog | Line 44 names `ChildField.RecordTableMethodField` ("DTO/`@service` parent, DataLoader-batched") as the live construct the reintroduced docs would describe. That leaf no longer exists; the DTO-parent `@tableMethod` shape now classifies to a **record-sourced `BatchedTableField`** carrying `TableExpr.MethodCall`. | **Re-anchor** the construct description to the post-R314 shape (record-sourced `BatchedTableField` + `TableExpr.MethodCall` terminal hop). Fresh drift this window. |

### C.2 R432 leaf-merge and R431 anchor drift (carried / re-drifted)

`SplitTableField`/`RecordTableField` -> `BatchedTableField`;
`SplitLookupTableField`/`RecordLookupTableField` -> `BatchedLookupTableField`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | Line 51: the planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`"; the record-sourced classification now produces `BatchedTableField`. | **Re-anchor** the planned test assertion to `BatchedTableField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Lines 21, 28, 39, 41-42, 127 name `SplitTableField` / `SplitLookupTableField` as live. `SplitRowsMethodEmitter` in the same passages is correct. | **Re-anchor** the variant names; leave `SplitRowsMethodEmitter` untouched. |
| **R242** dml-payload-positional-alignment | Spec | Lines 37-38's R305 lineage note "collapsed it into `RecordTableField`, `ChildField.java:912`" is **doubly stale**: the name is now `BatchedTableField` and the cite `:912` today lands on `ServiceTableField` (a different, live variant), not the merged leaf (`BatchedTableField` is at `:519`). The rest of the R305/R287 `SingleRecordTableField` history is clean. | **Re-anchor** that one name + cite (`BatchedTableField`, `:519`); leave the R305-framed history. |
| **R288** inline-interface-and-tablemethod-children | Backlog | Lines 35-36: "a keyed batch (DataLoader, as `SplitTableField` / `RecordTableField` do via `SplitRowsMethodEmitter`)". Variant names stale; emitter fine. (Scope correctly narrowed to the interface N+1 case in a prior window.) | **Re-anchor** the two variant names. |
| **R472** nested-generated-condition-filters-never-emitted | Backlog | Lines 20-21: classifier attaches `GeneratedConditionFilter` to a nested `SplitTableField` / `SplitLookupTableField` / inline `TableField` / `LookupTableField`. (`TableField` / `LookupTableField` are still live.) | **Re-anchor** the two Split* names. |
| **R447** routine-chain-fetch-form-breadth | Backlog | Line 24 (`SplitLookupTableField` composition), line 26 (`RecordTableField` seam) as live seam references for planned fetch-form extensions. | **Re-anchor** both to the Batched names. |
| **R116** composite-key-row2-source-row-coverage | Backlog | Line 15(b): the planned `COMPOSITE_KEY_ROW2_PATH_KEYED` case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R180** record-parent-column-read-helper | Spec | 2 of 3 anchors exact (`recordColumnReadArgs` `:290`, `buildFkRowKey` `:274`); `buildRecordParentKeyExtraction` cited `:238` is inside a javadoc block, the two overloads are at **`:224`** / **`:249`**. Prose says "R431 now In Progress" -> R431 is **Done**. | **Re-anchor** the one method to `:224`/`:249`; fix the R431 tense to Done. Substance (consume the R461 accessor surface for record-parent column reads) valid. |
| **R7** decompose-typefetchergenerator | Backlog | Line 30 proposes a hypothetical decomposed emitter `SplitTableFieldEmitter`; a decomposition done today would name it `BatchedTableFieldEmitter`. Illustrative "etc." naming; the list of *existing* emitters (incl. `SplitRowsMethodEmitter`) is correct. | **Low priority:** refresh the illustrative name at pickup. Not blocking. |

**No flag (verified lineage / self-noted):** **R333** (Ready) carries explicit
shipped-notes for **both** R432 (`:1757`) and R314 (`:1788`); all other old-name
mentions are inside its Discovery/lineage threads as the evidence that motivated
R432. The `RecordTableMethodField` / `dispatchPerformsReFetch` mentions sit under
the R314 shipped-note documenting their retirement. Its only residue is
future-tense "R314 owns the emit" prose (`:40`, `:322`, `:495`) now that R314 is
Done, folded into cross-cutting 1. **R323** (Backlog) self-flagged with a
"Re-anchor at pickup (added 2026-07-15)" note (`:27`) naming the four old variant
names; pre-declared, `depends-on` cleared to `[]`. **R302** (Backlog) mentions
`SingleRecordTableField` only in an R305 lineage clause.

## D. Structural: (0)

Empty. The prior window's one structural finding, a `next-id: R485` collision with
the already-allocated `fk-hop-narrowing-helpers`, was **fixed**: `changelog.md`
now carries `next-id: R499`, clearing the max allocated id (R498). No duplicate
`id:`, no `status: Done` tombstones, no dangling `depends-on` edges.

## Cross-cutting observations

1. **Shipped items narrated as pending siblings (prose cleanup).** Surviving
   items still treat a now-Done item as live: **R314** ("R314 owns the emit") in
   **R333** (`:40`, `:322`, `:495`) and **R222** (`:147`); **R431** ("Spec"/
   "plans to decompose") in **R71** (`:33`), **R180** (`:35`), **R222** (`:243`,
   `:285`). Front-matter `depends-on` was correctly cleared at each closure (no
   dangling edges), so this is prose only, not a build risk, but it misleads a
   picker. Fold the tense fixes into the §B/§C re-anchors above.
2. **Re-anchoring stayed perishable, three windows running.** R431's decomposition,
   R432's leaf merge, and now R314's reentry dissolution each re-moved the same
   ride-along cluster's anchors, and each deleted (not merely shifted) a construct
   some item named. The items that did not re-drift are the re-derive-at-pickup
   ones (R7, R35, R66) and those in untouched files. **Convention reinforced:**
   prefer symbol-anchored references over bare line numbers, and re-anchor
   ride-along items **each slice**, not at the end. The re-platforming's model
   trilogy (R431/R432/R314) is now complete, so this cluster should finally stop
   re-drifting from this source; the next churn source would be R333/R222 emit
   work.
3. **`SplitRowsMethodEmitter` is a false-positive magnet.** It was **not** renamed
   by R314 or R432 and remains a live class; several §C items reference it
   correctly alongside stale variant names. When re-anchoring, change only the
   merged/dissolved variant names, not the emitter.
4. **A duplicate reached the board via parallel follow-on filing (§A).** R491 and
   R493 are the same cleanup filed twice, hours apart, by R482's and R483's
   independent follow-on batches, neither aware of the other. This is the failure
   mode of concurrent follow-on filing: when two closing items each spin out a
   "purge roadmap ids from generated javadoc" pin, they collide. Worth a
   dedupe check in the `create` path or the roadmap skill when a new item's title
   closely matches a recent sibling.
5. **No closure this window subsumed a surviving flagged item** (verified at the
   symbol). R314's reentry dissolution is the *substrate* R109/R116/R242/R403 name,
   not a replacement; R201 shipped the payload-construction read half, and its
   In Review sibling R201-vintage construct work is Done, so nothing flagged was
   orphaned; R481's polymorphic-projection closure spawned R487 (a genuine
   follow-on, not a duplicate).
6. **Carried cosmetic front-matter nit persists.** **R97** (`consumer-derived-
   input-tables`) still lacks `created:` / `last-updated:`. Not a build or
   dependency risk.
7. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-17._
