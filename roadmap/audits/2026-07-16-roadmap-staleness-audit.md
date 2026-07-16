# Roadmap staleness audit: 2026-07-16

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `75f0b19`, committed 2026-07-15 22:30, audited 2026-07-16). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-15` staleness audit, which has been deleted;
only the latest staleness audit is retained. Three siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  meant to be edited in place as scope iterates. It reads further behind again:
  it still names none of the SourceKey-decomposition (R431), leaf-collapse (R432),
  or cursor/node-id hardening (R479/R477/R478) work that closed or advanced this
  window, so its MUST/SHOULD tables lag further. Refreshing it is out of scope for
  this staleness pass.
- `2026-07-04-r222-r333-conformance-analysis.md` is the R222/R333 conformance
  analysis, a companion to the R314/R333 design session. It is a targeted
  implementation-vs-spec conformance record, not a point-in-time staleness
  review; left in place. (R333 stayed **Ready** this window; the analysis remains
  a sign-off input and stays as lineage.)

`classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
inventory; its "superseded, historical" banner (added 2026-07-14) is intact. No
action; it stays as lineage.

## Headline: R431 closed and R432 landed its merge in-tree, and the same ride-along cluster drifted again

The story of this window is the SourceKey/leaf re-platforming finishing its first
two chapters. Last window's audit flagged the ride-along cluster (R71, R180, R201,
R222) as `FieldBuilder`/`SourceKey` line drift; this window **R431 reached Done**
(the full `SourceKey` decomposition to `(columns, wrap)`) and **R432 reached
In Review** (the four table-leaf variants merged to two). Both are code changes
that are now in the tree, and both moved the same cluster plus a fresh set of
design-body references:

- **R431 (Done)** deleted `SourceKey.target`/`path`/`cardinality`/`reader`,
  relocating the read arms onto the new `KeyLift` fact and `LifterRef` (its own
  file). This did not merely bump line numbers: it **deleted the symbols** R71
  cited (`SourceKey.Reader.SourceRowsCall`), so R71 crossed from reference-drift
  (§C last window) to a target-surface change (**§B** this window).
- **R432 (In Review)** merged `SplitTableField` + `RecordTableField` into
  `BatchedTableField`, and `SplitLookupTableField` + `RecordLookupTableField`
  into `BatchedLookupTableField` (verified: `ChildField.java:523`/`:657`; the four
  old names no longer exist as sealed variants). Seven items still name the old
  variants as live constructs and now carry stale references.

Net: **0 §A / 1 §B / 12 §C**, plus one structural nit (a latent `next-id`
collision) worth fixing before the next `create`. §A is empty for the twelfth
window running; the one item that crossed a threshold this window (R71) is a
target-surface change, not an obsolescence, so it sits in §B.

## Changes since the 2026-07-15 audit

**49 commits** landed between the prior audit (HEAD `69c8a0c`, 2026-07-15 03:19)
and this one (HEAD `75f0b19`, 2026-07-15 22:30) in a dense ~19-hour window. Six
things drove it:

**1. `SourceKey` decomposition (R431) closed.** **R431** (`decompose-sourcekey`)
ran In Progress -> In Review -> **Done** (`cf526de`), landing slices 2-4 in-window:
`path` deleted and `JoinStep.LiftedHop` retired (`9c8261b`), an `Arity` enum split
out (`3229cbe`), `SourceKey` shrunk to the `(columns, wrap)` residue with the read
arms relocated onto the sealed `KeyLift` fact (`2ae8529`), and the bridging-join
emit consolidated into `JoinPathEmitter` (`6c610e7`). Self-deleted on Done.

**2. Table-leaf collapse (R432) went active and landed its merge.** **R432**
(`collapse-split-and-record-table-leaves`) drove Backlog -> Spec -> Ready ->
In Progress -> **In Review** across the window (`f7f009a`..`75f0b19`): the
parent-projection containment check (`3b873c3`), `SplitTableField` +
`RecordTableField` merged into `BatchedTableField` (`9253bca`), the lookup twins
merged into `BatchedLookupTableField` (`f6ebfaf`), and a docs sweep (`75f0b19`).
The merge is in-tree; this is the fresh drift source for the §C block below.

**3. Two crash-hardening / refactor closures.** **R479**
(`connection-cursor-decode-crashes`) ran Ready -> **Done** (`ffb8c5e`): a malformed
`after`/`before` cursor now returns a clean `GraphitronClientException` instead of
redacting to a 500. **R478** (`keyshape-sealed-variants`) ran In Progress ->
**Done** (`5c64fca`): `KeyAlternative` sealed into `Direct`/`NodeId` variants
(pure model refactor, byte-unchanged output). Both self-deleted.

**4. Two more closures.** **R202** (`honor-field-directive-in-error-type-source-accessors`)
ran Backlog -> Spec -> Ready -> **Done** (`b226544`): honor `@field(name:)` on
`@error` extra-field source accessors (resolving last window's §C flag by
shipping it). **R474** (`mvnd-web-environment`) reached **Done** (`886ea73`):
mvnd adopted in the web dev environment. Both self-deleted.

**5. R427 pivot and two discards.** **R427** (`relevance-ranked-search`) pivoted
to native-index-backed free-text search after the issue #512 design round
(`2ce6c93`..`4d10274`), Backlog -> **Spec**; its file was largely rewritten. Two
Backlog items were **discarded per user decision** (`dcac9b2`, self-deleted):
**R171** (`input-like-type-sealed-parent`, opposite direction to the dimensional
pivot) and **R277** (`tablemethod-under-nested-type`, `@tableMethod` withheld from
v1 per R400). **R288** was narrowed in place to the polymorphic-interface N+1 case,
**R11** pruned to `@condition` only, **R403** parked at low priority.

**6. Five new items filed.** **R481** (`single-cardinality-polymorphic-child-parent-fk-projection`,
Backlog), **R482** (`javadoc-roadmap-reference-purge`, Ready), **R483**
(`javadoc-implementation-drift-audit`, Spec), **R484** (`rejection-message-roadmap-slug-purge`,
Backlog), **R485** (`fk-hop-narrowing-helpers`, Backlog, spun out of R431 slice 4;
renumbered from R484 after a concurrent grab, `9f51858`). All five have
well-formed front-matter and are current.

**Terminal closures this window (Done, all self-deleted):** R431, R202, R479,
R478, R474. R432 landed its merge in-tree but is **In Review**, not yet Done.
**Discards (self-deleted):** R171, R277.

**Board accounting.** **133 item files** today, down from the prior audit's 135.
The delta reconciles as `135 - 5 - 2 + 5`: minus five Done deletions
(R431, R202, R479, R478, R474), minus two discards (R171, R277), plus five new ids
(R481-R485). R477 (last window's create-and-close) is long gone; R288 / R11 /
R403 were narrowed in place (net 0). Status distribution: **113 Backlog, 14 Spec,
3 Ready (R333, R314, R482), 2 In Progress (R347, R458), 1 In Review (R432);
zero Done**. A non-recursive `^status: Done` grep over `roadmap/*.md` returns
nothing (tombstone-free for the twelfth window running). No duplicate `id:`;
max allocated id **R485**. A `depends-on:` sweep over all 133 files found **no
dangling slugs** (all 13 non-empty edges resolve to present files; R71 and R180
both correctly had their R431 edge cleared to `[]` at closure). The board is
structurally clean but for one `next-id` nit (see §D).

**Net effect on flag counts: 13 flagged, 120 current.** 0 §A, 1 §B, 12 §C
(plus one structural `next-id` nit in §D).

## Scope and method

All **133** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded). The two big code
changes were verified at the symbol: `SourceKey.java` now declares
`record SourceKey(List<ColumnRef> columns, Wrap wrap)` with no `Reader` interface;
`ChildField.java` declares `BatchedTableField` (`:523`) and `BatchedLookupTableField`
(`:657`) and none of the four merged names. Every symbol the prior audit's flags
cited was re-located in the current tree. `SplitRowsMethodEmitter` was confirmed
**not** renamed (it is still a live class), `RecordTableMethodField` and
`ServiceTableField` remain live variants (not part of the R432 merge), and
`SplitTableFieldEmitter` never existed as a class; roadmap references to those are
not stale and were excluded from the flag set.

**Anchors that held (re-verified exact, no drift this window):** R180's
`recordColumnReadArgs` (`GeneratorUtils.java:290`) and `buildFkRowKey` (`:274`)
are exact; R66 / R7 / R35 remain re-derive-at-pickup by construction; R10 / R236 /
R234 / R240 / R242 (code anchors) / R103 / R24 sit in files untouched by this
window's SourceKey/leaf churn.

## A. Obsolete: should leave the active roadmap (0)

Empty. No shipment this window stranded a surviving construct (verified via the
`depends-on` sweep and a deleted-slug symbol check). The five Done closures (R431,
R202, R479, R478, R474) and the two discards (R171, R277) all self-deleted; the
R427 pivot and the R288/R11/R403 narrowings were rewrites in place, not stranded
shells. The one item whose citations point at deleted symbols (R71) still has a
valid goal, so it is a re-spec (§B), not an obsolescence. The cross-item prose
that narrates shipped items as pending siblings (R431/R202) is a wording cleanup,
folded into the §B/§C rows and cross-cutting note 1, not an obsolete item.

## B. Outdated: needs re-spec (premise or targets materially changed) (1)

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | R431 (Done) **deleted the target surface**, not just its line numbers. The body cites `model/SourceKey.java:124-128` (a `SourceRowsCall -> Wrap.Row` compact-ctor pin) and `:288` (`SourceKey.Reader.SourceRowsCall(LifterRef)`); `SourceKey` no longer has a `Reader` interface or a `SourceRowsCall` arm. `LifterRef` moved to `model/LifterRef.java:25`, carried by `KeyLift.Lifter` (`model/KeyLift.java:57`); the `SourceRowsCall -> Wrap.Row` derivation relocated to `KeyLift#checkResidueAgreement`. `SourceRowsCall` now survives only as prose. The prior audit's "symbol-anchored core (`LifterRef`/`SourceRowsCall`/`Wrap`) is correct" no longer holds. | **Re-spec the current-state / approach section** against the decomposed `KeyLift`/`LifterRef`/`SourceEnvelope` model R431 introduced. The goal (recordN key parity, non-jOOQ record parents) is intact, so this is a targeted re-derivation of *where* the work attaches, not a new item. Also drop the stale "hence the depends-on edge" prose (front-matter already `[]`) and the "R431 plans to decompose" tense (R431 is Done). |

## C. Outdated: update references only (work valid, refs stale) (12)

Substance intact; names/line numbers drifted. Two causes: the R432 leaf-merge
(fresh this window, top block) and carried/re-drifted anchors from R431 and prior
`FieldBuilder` churn (bottom block).

### C.1 R432 leaf-merge rename drift (fresh this window)

`SplitTableField`/`RecordTableField` -> `BatchedTableField`;
`SplitLookupTableField`/`RecordLookupTableField` -> `BatchedLookupTableField`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R314** dissolve-reentry-leaves-dimensional-emit | Ready | Lines 22-23 and 75-76 list the reentry leaves and even try to account for R432, but use the pre-merge name: "`RecordTableField` (the merged source-gated leaf's record arm after R432)". Post-R432 there is no `RecordTableField` and no separately-named "record arm"; it is `BatchedTableField`. `RecordLookupTableField` -> `BatchedLookupTableField`. (`RecordTableMethodField` / `ServiceTableField` are still live and correct.) | **Re-anchor** to the Batched names. Highest priority of this block: R314 is **Ready** and next to be implemented. |
| **R109** list-valued-external-field-multiset | Spec | Line 51: the planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`"; the record-sourced classification now produces `BatchedTableField`. | **Re-anchor** the planned test assertion to `BatchedTableField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Lines 22, 28, 38-41 (the `NESTED_WIREABLE_LEAVES` enumeration), 127-131 (unit-tier test descriptions) name `SplitTableField` / `SplitLookupTableField` as live. `SplitRowsMethodEmitter` references in the same passages are correct. | **Re-anchor** the variant names; leave `SplitRowsMethodEmitter` untouched. |
| **R288** inline-interface-and-tablemethod-children | Backlog | Lines 35-36: "a keyed batch (DataLoader, as `SplitTableField` / `RecordTableField` do via `SplitRowsMethodEmitter`)". Variant names stale; emitter fine. (Scope already correctly narrowed to the interface N+1 case this window.) | **Re-anchor** the two variant names. |
| **R472** nested-generated-condition-filters-never-emitted | Backlog | Lines 20-23: classifier attaches `GeneratedConditionFilter` to a nested `SplitTableField` / `SplitLookupTableField` / inline `TableField` / `LookupTableField`. (`TableField` / `LookupTableField` are still live.) | **Re-anchor** the two Split* names. |
| **R447** routine-chain-fetch-form-breadth | Backlog | Line 26 (`SplitLookupTableField` landing), line 28 (`RecordTableField` seam) as live seam references for planned fetch-form extensions. | **Re-anchor** both to the Batched names. |
| **R116** composite-key-row2-source-row-coverage | Backlog | Line 15(b): planned `COMPOSITE_KEY_ROW2_PATH_KEYED` case "classifies as `RecordTableField`". | **Re-anchor** to `BatchedTableField`. |
| **R242** dml-payload-positional-alignment | Spec | Line 38's R305 lineage note "collapsed it into `RecordTableField`, `ChildField.java:912`" is doubly stale: the name is now `BatchedTableField` and the cite is wrong (`BatchedTableField` is at `ChildField.java:523`). The rest of the R305/R287 lineage (`SingleRecordTableField`) is clean history. | **Re-anchor** that one name + cite; leave the R305-framed history. |
| **R7** decompose-typefetchergenerator | Backlog | Line 30 proposes a hypothetical decomposed emitter `SplitTableFieldEmitter`; a decomposition done today would name it `BatchedTableFieldEmitter`. Illustrative "etc." naming; the list of *existing* emitters (incl. `SplitRowsMethodEmitter`) is correct. | **Low priority:** refresh the illustrative name at pickup. Not blocking. |

**No flag (verified lineage / self-noted):** **R333** (Ready) already carries an
explicit "Shipped (R432, 2026-07-15)" note at `:1757`; all other old-name mentions
are inside its Discovery threads as the evidence that motivated R432. **R323**
(Backlog) self-flagged with a "Re-anchor at pickup (added 2026-07-15)" note naming
R432 and a `depends-on` edge (its variant-name staleness is pre-declared; only its
R431 status label is stale, see cross-cutting 1). **R302** (Backlog) mentions the
old name only in an R305 lineage clause.

### C.2 R431-Done and FieldBuilder anchor drift (carried / re-drifted)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Backlog | Uniform ~-28 drift in `FieldBuilder.java`: `resolvePayloadConstructionShape` `:521` -> **`:493`**, `tryMutableBean` `:586` -> **`:558`**, `SetterBinding` `:624` -> **`:596`**, PayloadClass call `:2998` -> **`:2980`**, `javaBeanSetterName` def `:633` -> **`:605`**. All symbols present; premise (payload-construction mirror of R200, no `@field` read) unchanged. | **Re-anchor** the ranges -28. Also drop the "R202 is unshipped, ship the two together" prose (`:15`): **R202 shipped this window** (Done); the read/construct asymmetry R201 closes is now real, not hypothetical. |
| **R180** record-parent-column-read-helper | Spec | 2 of 3 anchors exact (`recordColumnReadArgs` `:290`, `buildFkRowKey` `:274`); `buildRecordParentKeyExtraction` cited `:238` is inside a javadoc block, the two overloads are at **`:224`** / **`:249`**. Also prose says "R431 now In Progress" -> R431 is **Done**. | **Re-anchor** the one method to `:224`/`:249`; fix the R431 tense to Done. Substance (consume the R461 accessor surface for record-parent column reads) valid. |
| **R222** dimensional-model-pivot | Spec | The "researched 2026-06-16" inventory still describes `SourceKey` as the 6-component `(target, columns, path, wrap, cardinality, reader)` with "four `SourceKey.target()` readers" (`:241`, `:251-252`, `:315`). R431 (Done) reduced it to `(columns, wrap)`, so the divergence is now **maximal**: `target`, `path`, `cardinality`, and `reader` are all gone. Two live permit refs (`:35`, `:415`) name `RecordLookupTableField` (now `BatchedLookupTableField`). Line `:243` still labels R431 "Spec". No shipped-note. | **Update the inventory** with a "since executed by R431 (Done) and R432 (In Review)" note (keep the dated research snapshot), re-anchor the two permit names, and fix the R431 status label. Umbrella direction valid; this is a reference/status update, not a premise change. |

## D. Structural: latent `next-id` collision (1)

`changelog.md` carries `next-id: R485`, but **R485 is already allocated**
(`fk-hop-narrowing-helpers.md`, id `R485`, renumbered there in `9f51858` after a
concurrent grab of R484). The next `roadmap-tool create` would hand out R485 a
second time and collide. **Recommended action:** bump `next-id:` to **R486**. This
is the one non-cosmetic structural finding; unlike a bare line-number drift it
would corrupt an allocation.

## Cross-cutting observations

1. **Shipped items narrated as pending siblings (prose cleanup).** Five surviving
   items treat a now-Done item as live: **R431** ("Spec"/"In Progress"/"plans to
   decompose") in **R323** (`:28`), **R71** (`:33`), **R180** (`:35`), **R222**
   (`:243`); **R202** (unshipped) in **R201** (`:15`). Front-matter `depends-on`
   was correctly cleared at each closure (no dangling edges), so this is prose
   only, not a build risk, but it misleads a picker. Fold the tense fixes into the
   §B/§C re-anchors above; the R323 one is a standalone one-line touch.
2. **Re-anchoring stayed perishable, twice over.** The prior window's drain re-set
   the R201/R71/R180/R222 anchors; this window R431's Done cutover and R432's
   in-tree merge moved them again, and R71's cited symbols were **deleted**, not
   just shifted. The items that did not re-drift are the re-derive-at-pickup ones
   (R7, R35, R66) and those in untouched files. **Convention reinforced:** prefer
   symbol-anchored references; treat a bare line number as stale the moment its
   file is edited, and re-anchor ride-along items **each slice**, not at the end.
3. **`SplitRowsMethodEmitter` is a false-positive magnet.** It was **not** renamed
   by R432 and remains a live class; several §C items reference it correctly
   alongside the stale variant names. When re-anchoring, change only the four
   merged variant names, not the emitter.
4. **R482 self-irony (not a defect).** R482 (`javadoc-roadmap-reference-purge`)
   and R201 themselves still carry the transient roadmap-reference clutter R482
   exists to purge. Expected pre-implementation; noted so it is not mistaken for
   drift.
5. **No closure this window subsumed a surviving flagged item** (verified at the
   symbol). R431's decomposition is the *substrate* R71/R180/R222/R314 build on, not
   a replacement; R478's `KeyAlternative` seal is a distinct path from R479's
   cursor-decode guard; R202 shipped the read half, leaving R201's construct half
   genuinely open.
6. **Carried cosmetic front-matter nit.** **R97** (`consumer-derived-input-tables`)
   still lacks `created:` / `last-updated:`. Not a build or dependency risk.
7. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-16._
