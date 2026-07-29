# Roadmap staleness audit: 2026-07-29

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `4d5f48d`, committed 2026-07-28 23:23, audited 2026-07-29). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-28` staleness audit, which has been deleted;
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
  analysis, a companion to R333 and the command-layer cluster. It is a
  symbol-anchored snapshot with a stated re-derivation method, not a staleness
  review; left in place. (It was refreshed this window by the command-layer
  landings; the document itself is not a flag target.)

`classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
inventory; its "superseded, historical" banner is intact. No action; it stays as
lineage.

## Headline: a structurally busy window that deleted `QueryConditionsGenerator`, opening a new drift cluster (three items) while R516 closed drift-free

Unlike the prior prose-only window, this one landed real generator surgery. The
command-layer programme (**R549** `facts-and-commands`, **R552** `condition-command`)
moved from Spec to In Progress and put its first vertical slices in trunk:
**R552 slice 1 deleted `QueryConditionsGenerator`** (381 lines) and cut the root
condition family over to a new package triangle, `command/` (record vocabulary),
`plan/` (`ConditionCommands` producer riding `EmitPlan`), and `render/`
(`ConditionGlueRenderer`, `JoinFragments`). `plan/GeneratedUnits` moved out of
`rewrite/compile/`. **R516** (`service-table-record-pk-only-contract`) ran the rest
of the way to Done.

Two very different drift outcomes, from the same window:

- **R516's landing was drift-free by the now-familiar same-window author sweep.**
  Its `RequiredProjection` record and `reservedFullRow` axis stayed retired
  (`TypeClassGenerator.collectRequiredProjection` survives as the live method
  returning `List<ColumnRef>`), and the only active items naming the projection
  walk (R333, R549) cite the live method, not the deleted record. Its Done entry
  even spawned **R554** for the body-scan-assertion retirement it deferred. Zero
  new drift.
- **R552's `QueryConditionsGenerator` deletion did leave drift**, because the
  cutover shipped in the same commit (`4d5f48d`, HEAD) as, and one commit *after*,
  the sign-offs of the items that consume it. The class is now gone, but
  **R85, R333, and R541** still name it (and its `conditionMethodName` /
  `facetBaseConditionMethodName` formulas) as live, and **R462 / R472** gain a
  second stale layer on top of their carried `Split*` drift. This is the first
  new drift cluster since the R519 window.

Net: **0 §A / 4 §B / 17 §C**, §D empty. Up from the prior window's `0 / 2 / 16`
by **+2 §B** (R541, R85) and **+1 §C** (R333), all traceable to the one
`QueryConditionsGenerator` deletion. Every one of the 18 prior flags was
re-verified still accurate and still literally present.

## Changes since the 2026-07-28 audit

**42 commits** landed between the prior audit's commit (`533a314`, which recorded
HEAD `cc270f1`) and this HEAD (`4d5f48d`, 2026-07-28 23:23), a roughly 24-hour
window. It was dominated by the command-layer programme (R549/R552) and its
companion (R541), plus the R516 endgame and the R555 filing.

**One item ran to Done (self-deleted):** R516 (`service-table-record-pk-only-contract`),
approved at its fifth gate after four rework rounds. Its `changelog.md` entry is at
`:891`; the file self-deleted as expected.

**No items discarded this window.**

**New items filed** (Backlog unless noted): **R554** (`generated-body-string-assertion-helpers`,
Backlog; the body-scan retirement R516 deferred) and **R555**
(`deprecate-externalfield-fold-into-service`, now Ready; deprecates `@externalField`
by folding the computed-field shape into `@service`, cross-linked with R54 as
mutually exclusive resolutions). Both read against the current model and found
**born-current**: R555 anchors on the live `ServiceDirectiveResolver` and the live
`@service` / `@externalField` surface; R554 cites the live guard-test surface. Neither
names a deleted symbol.

**Other transitions:** R549 Spec -> Ready -> In Progress (slices 1-2 landed);
R552 Spec -> Ready -> In Progress (slice 1 landed); R541 Spec -> Ready; R555
Backlog -> Spec -> Ready; R521 gained a mechanized-detection section.

**Terminal-state carriers not yet Done:** R333, R427, R541, R555 (Ready);
R347, R549, R552 (In Progress). Zero items sit In Review at audit time.

**Board accounting.** **151 item files** today (153 `roadmap/*.md` entries minus
`README.md` and `changelog.md`), up one net from the prior audit's 150: one Done
self-deleted (-1), offset by two new items filed (+2). Status distribution:
**133 Backlog, 11 Spec, 4 Ready, 3 In Progress, 0 In Review, 0 Done**. Movement
from the prior window's `132 / 14 / 2 / 2 / 0 / 0`: Backlog +1 (R554), Spec -3
(R549/R552 out to In Progress, R541 out to Ready), Ready +2 (R541, R555), In
Progress +1 (R549/R552 in, R516 out to Done). A non-recursive `^status: Done` grep
over `roadmap/*.md` returns nothing (tombstone-free for the twenty-first window
running). No duplicate `id:`; max allocated id **R555**, and `changelog.md` carries
`next-id: R556`, clearing it. A `depends-on:` sweep over all 151 item files
resolves every edge, R-number and slug alike, to a present file (the non-empty
edges are all slug-based, e.g. `root-query-unit-seam` ->
`[facts-and-commands, condition-command]`, and all land). The board is
structurally clean.

**Net effect on flag counts: 21 flagged, 130 current.** 0 §A, 4 §B, 17 §C, 0 §D.
The R516 closure was drift-neutral; the R552 `QueryConditionsGenerator` deletion
added the entire delta.

## Scope and method

All **151** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded: no `R<n>`). The
model claims were re-verified at the symbol.

**The window's symbol changes, verified at the symbol:**

- **`QueryConditionsGenerator`, RETIRED (R552 slice 1, `4d5f48d`).** No definition
  today; `grep` over generator main sources finds the class nowhere, and no live
  main source references it. Replaced by `render/ConditionGlueRenderer` (the glue
  renderer, single-layer map-taking bodies), fed by `plan/ConditionCommands` (the
  producer, riding `EmitPlan` as a `ConditionRelation`) over the `command/` record
  vocabulary. The `conditionMethodName` / `facetBaseConditionMethodName` /
  `facetConditionMethodName` name formulas are gone with it (`grep` = 0), folded
  into producer-computed command refs. **This is the window's one drift-bearing
  removal**; see §B (R541, R85) and §C.6 (R333) plus the second layer added to
  R462/R472 in §C.2. **`TypeConditionsGenerator` is LIVE and unchanged** (9 main
  files); it is the pure entity-scoped condition layer QCG used to shim, and every
  item that cites *it* is still correct.
- **`plan/GeneratedUnits`, MOVED out of `rewrite/compile/` (R549 slice 1).** The
  naming vocabulary now lives at `graphitron/.../plan/GeneratedUnits.java`. Cited
  only by its two authoring items (R549, R552), which record the move in their
  slice tables. No other item cites the old `compile/` location. **Drift-free** for
  consumers.
- **`RequiredProjection` record and `reservedFullRow` axis, RETIRED (R516, now
  Done).** Still gone: a word-boundary `grep` finds only the live method
  `collectRequiredProjection` (the substring match is not the record). Cited by no
  active item as a live record. **Drift-free.**
- **`ServiceMethodCallError.SourcesOnPkLessParent`, LIVE (R516).** The additive
  rejection arm the prior window saw added is now shipped and guarded by
  `PkLessParentServiceSourcesRejectionTest`. Additive; creates no flag.

**Retired symbols from prior windows, re-verified still retired:**

- **`GraphitronType.TableInputType`, RETIRED (R519).** `validateTableInputType` and
  `TypeBuilder.buildNonTableInputType` both `grep` = 0. Drives the §C.5 / §B
  input-side flags.
- **`@tableMethod` directive and `TableMethodField` leaves, RETIRED (R535).**
- **`ChildField.PropertyField` / `RecordField`, MERGED into `RecordReadField` (R51).**
  `BatchedTableField` (16 main files) / `BatchedLookupTableField` (15) / the
  `ColumnBackedField` family (29) are the live re-anchor targets; `SplitRowsMethodEmitter`
  (12) is live and correct wherever it appears.
- **`SourceKey`, plain record, no `Reader`** (R431). The one `SourceKey.Reader`
  string in main sources is a javadoc comment in `SourceEnvelope.java` naming a
  *retired* reader, not a live interface (`SourceKey.java` defines only the `Wrap`
  sealed interface). Re-verified for the carried R71/R180/R505 flags.
- **`MutationInputResolver.resolveInput`, RETIRED (R515); `admitMutationInputFields`
  LIVE.** The two `resolveInput` string hits in that file's neighbourhood are
  comments noting the method is gone; the substring also matches unrelated live
  methods (`RecordBindingResolver.resolveInput`, `resolveInputFields`,
  `resolveInputArgClass`), which are not the retired mutation resolver. Re-verified
  for the R245/R257 flags.
- **`ColumnField` / `CompositeColumnField`, MERGED into `ColumnBackedField` (R508).**
  The remaining `\bColumnField\b` hits are all `{@code ColumnField.filmId}`-style
  illustrative javadoc, not a live variant class. §C.3 stays empty of roadmap items.

**Anchors that held (re-verified):** all 18 prior flags were re-checked against
their roadmap files. Only **R541** among them was edited this window (Spec ->
Ready sign-off), and its edit did **not** touch its `QueryConditionsGenerator`
current-state prose, which R552 slice 1 then invalidated one commit later. Every
carried stale phrase is still literally present.

## A. Obsolete: should leave the active roadmap (0)

Empty. No item's entire premise was invalidated to the point of removal this
window. R221 remains the nearest candidate (R519 may have closed its gap), but
that is a re-derivation call at pickup, so it sits in §B rather than §A. R520
(`table-on-input-removal-housekeeping`) remains the deliberately-deferred docs/LSP
tail, not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (4)

Two carried from the prior window (unchanged, re-verified accurate); two new this
window from the `QueryConditionsGenerator` deletion.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R541** root-query-unit-seam | Ready | **New this window.** Signed off Spec -> Ready at `720469b`, then R552 slice 1 (`4d5f48d`, HEAD, the very next commit) deleted `QueryConditionsGenerator` and cut the root condition family over. R541's entire current-state analysis names it as the live emitter (`:124` "`QueryConditionsGenerator` emits those methods") and its "finish lift" open issue names `QueryConditionsGenerator.conditionMethodName` (`:338-339`) and `QueryConditionsGenerator.facetBaseConditionMethodName` (`:377`). Its fork-1 resolution ("R552 owns condition production wholesale and lands first") has now **happened**: the naming-regime locus it deferred to R552 is dissolved. The item's goal (root SELECT family as launcher commands) is intact and R552 slice 1 did not subsume it. | **Re-baseline the current-state section against the shipped `ConditionGlueRenderer` / `ConditionCommands`.** Retire the `QueryConditionsGenerator` references, mark the "finish lift" open issue as delivered by R552 slice 1, and re-derive the remaining launcher-command scope against the post-cutover model before pickup. Do **not** discard: the launcher reframe is unshipped. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | **New this window.** The item's whole implementation plan is anchored on `QueryConditionsGenerator` by line number (`:32` "`QueryConditionsGenerator.java:107-109`", `:44` "`:109`", `:48` "`:107-108`") and proposes to "Make `QueryConditionsGenerator` and `TypeClassGenerator` instantiate an ..." helper (`:40`). The class is gone; the condition-emission responsibility moved to `render/ConditionGlueRenderer`. The subject (helper emission on non-fetcher hosts) plausibly survives in the new render layer, but the named target surface was deleted wholesale. | **Re-derive against the new `render/` layer.** Determine whether `ConditionGlueRenderer` (and `TypeClassGenerator`) still exhibit the duplicated-helper-emission problem the item targets; if so, re-spec the plan onto the glue renderer. Every `QueryConditionsGenerator.java:NNN` line cite is dead and must be dropped. |
| **R221** validator-walks-plain-input-unbound-fields | Backlog | Carried, unchanged. R519 deleted `TableInputType` and the `validateTableInputType` type walk this item's whole diagnosis rests on, replacing it with per-consumer `collectInputFieldRejections`. The stated gap ("only `TableInputType.inputFields()` is walked, plain inputs escape") describes an architecture that no longer exists; the per-consumer model R519 shipped is close to the fan-out R221 proposed. Both stale phrases still present. | **Re-derive against the post-R519 model.** If per-consumer resolution already validates plain-input `UnboundField + @condition(override:false)` shapes, **discard** (gap closed). If a plain-input arg outside a table write-target path still escapes, **re-spec** around `collectInputFieldRejections`. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried, unchanged. R431 (Done) deleted the target surface: the body cites `SourceKey.java` line ranges and `SourceKey.Reader.SourceRowsCall` as "the live surface", but `SourceKey` is a plain record with no `Reader` (re-verified: the sole main-source `SourceKey.Reader` string is a comment naming a retired reader). The sequencing line still reads "R431 ... plans to decompose"; R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model. The goal (recordN key parity, non-jOOQ record parents) is intact; drop the "R431 plans to decompose" tense. |

## C. Outdated: update references only (work valid, refs stale) (17)

Substance intact; names and line numbers drifted. Sixteen carried from the prior
window (re-verified this window, model anchor files untouched except the command-layer
additions, so the re-anchor targets below still land); one new (R333) from the
`QueryConditionsGenerator` deletion.

### C.1 `planSlug` / `SourceKey.Reader` removal drift (carried, unchanged)

R484 (Done) removed `Rejection.Deferred.planSlug`; R431 (Done) removed the
`SourceKey.Reader` interface. Deferrals now anchor by `StubKey.VariantClass`; column
reads off a parent row lift via `KeyLift.FkColumns`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R454** routine-write-result-shapes | Backlog | `:18` names the deferred shapes as "typed `Deferred`s pointing at this item's planSlug"; `planSlug` is gone. | **Re-anchor:** deferred shapes surface via `StubKey.VariantClass`, no roadmap pointer. |
| **R447** routine-chain-fetch-form-breadth | Backlog | `:18` "`planSlug` points here"; and `:24`/`:26` name `SplitLookupTableField` / `RecordTableField` as live. | **Re-anchor** both: drop the `planSlug` phrasing; repoint the two variant names to `BatchedLookupTableField` / `BatchedTableField`. |
| **R180** record-parent-column-read-helper | Spec | `:35` says "R431 ... now In Progress" (R431 is **Done**) and names `SourceKey.Reader.AccessorCall` as a live carrier (`SourceKey` has no `Reader`). | **Re-anchor** the `AccessorCall` carrier onto the decomposed model; fix the R431 tense to Done. |
| **R505** tenant-index-parent-row-routing | Backlog | `:21` names "a column read off the parent row (the `SourceKey.Reader` family)" as the carrier for the proposed `ParentRowBound` arm. Born stale (filed after R431). Mechanism is live via `KeyLift.FkColumns`. | **Re-anchor** the one parenthetical: per-row column read lifts via `KeyLift.FkColumns`. |

### C.2 R432 leaf-merge / R314 dissolution drift (carried; two items gain a QCG layer)

`SplitTableField` / `RecordTableField` merged to `BatchedTableField`;
`SplitLookupTableField` / `RecordLookupTableField` to `BatchedLookupTableField`.
`SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears.
R507's guard excludes `roadmap/`, so this cluster stays an audit responsibility.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** the planned assertion to `BatchedTableField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | `:21`, `:28`, `:39`, `:41`, `:42`, `:127` name `SplitTableField` / `SplitLookupTableField` as live; `SplitRowsMethodEmitter` in the same passages is correct. **Plus a new QCG layer this window:** `:57` and `:99` name `QueryConditionsGenerator` (the "env-shim layer") as live; it is deleted. | **Re-anchor** the surviving `Split*` names to `Batched*`; **and** re-anchor the two `QueryConditionsGenerator` cites to `ConditionGlueRenderer` / `ConditionCommands`. Leave `SplitRowsMethodEmitter` untouched. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38`'s R305 lineage note "collapsed it into `RecordTableField`, `ChildField.java:912`" is doubly stale: the name is `BatchedTableField`, and `:912` now lands in `PivotSlotField` territory. | **Re-anchor** that one name + cite; leave the surrounding R305/R287 history. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34`: "a keyed batch (DataLoader, as `SplitTableField` / `RecordTableField` do via `SplitRowsMethodEmitter`)". Variant names stale; emitter fine. | **Re-anchor** the two variant names. |
| **R472** nested-generated-condition-filters-never-emitted | Backlog | `:20-21`: classifier attaches `GeneratedConditionFilter` to a nested `SplitTableField` / `SplitLookupTableField` / inline `TableField` / `LookupTableField` (the latter two live). **Plus a new QCG layer this window:** `:42` proposes a "`QueryConditionsGenerator`-style extraction"; that class is deleted. | **Re-anchor** the two `Split*` names; **and** re-point the extraction pattern to `ConditionCommands` / `ConditionGlueRenderer`. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`: the planned `COMPOSITE_KEY_ROW2_PATH_KEYED` case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R7** decompose-typefetchergenerator | Backlog | `:30` proposes a hypothetical decomposed emitter `SplitTableFieldEmitter`; a decomposition today would name it `BatchedTableFieldEmitter`. Illustrative "etc." naming. | **Low priority:** refresh the illustrative name at pickup. |

### C.3 R508 composite-column dissolution drift (carried)

R508 (Done) merged `ColumnField` / `CompositeColumnField` to `ColumnBackedField`.

**Still empty of roadmap items.** Retained as a header so the next audit can
re-populate it if a future landing reopens the composite-column names.

### C.4 `resolveInput` retirement drift (carried; R515)

R515 (Done) removed `MutationInputResolver.resolveInput`, hoisting its admission
set to `admitMutationInputFields`. The sibling
`EnumMappingResolver.buildLookupBindings` is still live.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot composition "in `MutationInputResolver.resolveInput`"; the method is gone, the composition is live in `admitMutationInputFields`. Design intact. | **Re-anchor** the one sentence to `admitMutationInputFields`. |

### C.5 R519 `TableInputType` removal drift (carried)

R519 (Done) deleted `GraphitronType.TableInputType`, its type walk, and
`TypeBuilder.buildNonTableInputType`, moving input classification to per-consumer
resolution. Four items name the deleted symbol as live (R221's deeper premise
change is in §B).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R222** dimensional-model-pivot | Spec | `:46` names `GraphitronType.TableInputType` as "a separate sibling root ... Nine consumer sites discriminate by permit identity"; `:15`/`:428` echo it. R519 executed exactly that collapse, so one of R222's three target organs (input-side classification) has **shipped**. The field-side and failure-encoding organs survive. | **Re-baseline the input-side section:** `TableInputType` is merged into `InputType`; record the input-side pivot as delivered by R519 and narrow the umbrella's remaining scope to the field-side and `Unclassified*` organs. |
| **R213** input-field-rejection-attribution | Backlog | `:64` scope note: "`@table` input types route through `TableInputType` classification ... this item is plain-input-only." That routing is gone (all inputs resolve per-consumer now). Core subject and its `InputFieldResolver` cites are valid. | **Re-anchor** the one scope-note sentence; verify the `InputFieldResolver` line cite still lands. |
| **R234** jooq-embedded-and-udt-input-backings | Spec | `:15` cites `TypeBuilder.buildNonTableInputType` as the live dispatch to extend; that method is gone. The sibling arms it extends (`JooqRecordInputType` / `JooqTableRecordInputType`) are live. | **Re-anchor** the dispatch site to the current `TypeBuilder` input-classification path; the design is intact. |
| **R257** updaterows-walker-sdl-substrate | Backlog | Carried §C.4 plus §C.5 drift. `:17` calls `resolveInput` "the legacy resolver" (gone since R515); `:15`/`:19` reach the admitted column carriers "via `TableInputType.inputFields()`" (gone since R519). `buildLookupBindings` still live; the two-places duplication the item targets survives. | **Re-anchor** both dead names: `resolveInput` to `admitMutationInputFields`, `TableInputType.inputFields()` to the per-consumer input resolution. Substance intact. |

### C.6 `QueryConditionsGenerator` dissolution drift (new this window; R552 slice 1)

R552 slice 1 (`4d5f48d`) deleted `QueryConditionsGenerator` and cut the root
condition family over to `render/ConditionGlueRenderer` (fed by
`plan/ConditionCommands`). The `conditionMethodName` / `facetBaseConditionMethodName`
name formulas retired with it. `TypeConditionsGenerator` (the pure entity-scoped
layer) is untouched and every cite of *it* remains correct. Two already-flagged
items in §C.2 (R462, R472) also carry this drift, cross-noted there. The deeper-hit
items R541 and R85 are in §B; the single §C-only carrier is R333.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | The seam-taxonomy table's row 5 (`:1460`, echoed `:1679`, `:1742`) names `QueryConditionsGenerator` as a live emitter for `<field>Condition` and records its open issue as "finish lift (`QueryConditionsGenerator` end)". The class is deleted; `TypeConditionsGenerator` (the co-cited emitter in the same row) is live and correct. R552 slice 1 **delivered** that row's "finish lift". Rows 1-4 and 6 and the item's core migration analysis are intact. | **Re-anchor row 5:** repoint `QueryConditionsGenerator` to `ConditionGlueRenderer` / `ConditionCommands`, keep `TypeConditionsGenerator`, and mark the "finish lift" open issue as delivered by R552 slice 1. |

## D. Structural: (0)

Empty. `changelog.md` carries `next-id: R556`, clearing the max allocated id
(R555). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and a
`depends-on:` sweep over all 151 item files resolves every edge, R-number and slug
alike, to a present file. The two items filed this window (R554, R555) carry
well-formed front-matter and were read against the current model as born-current.

## Cross-cutting observations

1. **The same-window author sweep is reliable for a landing session's own item, and
   unreliable for its neighbours.** R516 deleted a named construct and swept its own
   body and every citer in the same window: zero drift, the fourth consecutive such
   landing (R51, R535, R516). But R552 slice 1's `QueryConditionsGenerator` deletion
   left three neighbours (R85, R333, R541) stale, because those items were authored
   or signed off in the *same* window against the pre-deletion state, and no sweep
   reached across item boundaries. R541 is the sharpest case: signed off Spec ->
   Ready one commit before the deletion invalidated its baseline. The lesson for the
   command-layer programme's remaining slices: each cutover that deletes a generator
   must sweep the *sibling* items in the same programme (R333, R462, R472, R85), not
   only the authoring item's own body.
2. **A structurally busy window needs the symbol re-verification the prose windows
   let you skip.** This window's 42 commits included the first real generator surgery
   in three windows (a 381-line class deleted, a three-package triangle added, a
   vocabulary class relocated). Counting transitions would have caught R516's closure
   but missed that R552's cutover opened a fresh drift cluster; only re-verifying
   `QueryConditionsGenerator` at the symbol surfaced the three stale neighbours.
3. **An In-Progress programme's own forward-looking prose is not drift.** R549 (In
   Progress) still describes `plan/GeneratedUnits` as living in `compile/` in its
   analysis sections while its slice ledger records the move to `plan/`; it also calls
   the `QueryConditionsGenerator` end "R2 today" in a slice-planning note its own
   sibling slice just overtook. These are intra-programme snapshots the programme's
   next slice resolves, on the item doing the work, not consumer-facing stale-live
   cites. Not flagged, by the same rule the prior audit applied to R516's own body.
4. **`TypeConditionsGenerator` survived the cutover; only `QueryConditionsGenerator`
   went.** The two are easy to conflate (both emit `<field>Condition`), and the drift
   items co-cite them in the same rows. Any re-anchor must keep the
   `TypeConditionsGenerator` cites and repoint only the `QueryConditionsGenerator`
   ones.
5. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-29._
