# Roadmap staleness audit: 2026-07-31

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `d047eaa`, committed 2026-07-30 22:54, audited 2026-07-31). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-30` staleness audit, which has been deleted;
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
  analysis, a companion to R549 and the command-layer cluster. It is a
  symbol-anchored snapshot with a stated re-derivation method, not a staleness
  review; left in place. (R549 continued to consume it heavily this window: its
  gap 7 is the argument R549 slice 7 shipped, and its gap 5 the scope R549 slice
  5 folded in. The document itself is not a flag target.)

`classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
inventory; its "superseded, historical" banner is intact. No action; it stays as
lineage.

## Headline: two command-family authoring items ran to Done, discarding a flagged item; the projection programme R549 landed nine more slices and retired eight more model/emit symbols; one flagged item flips as its exemplar was deleted out from under it

The command-layer programme closed two of its three authoring items this window
and pushed the third (R549) most of the way to the finish:

- **R541** (`root-query-unit-seam`) and **R552** (`condition-command`) both went
  **In Review -> Done**, their spec files deleted per the Done transition and
  changelog entries appended (they are the top two entries). R541 is the root
  SELECT launcher family (second proof of the command architecture); R552 is the
  WHERE family (third proof, first to land). Both were the prior audit's live
  In-Review carriers; both cleared their independent-session Done gate.
- **The R552 Done commit (`d047eaa`) resolved a flagged item by discard.**
  **R387** (`type-conditions-test-code-string-migration`, prior audit §B) was
  deleted "as completed by its subject's retirement": the migration's target test
  went with `TypeConditionsGenerator`, and the surviving delegation-assertion
  concern was **re-filed fresh as R561** (`condition-glue-pipeline-body-scans`)
  rather than re-baselined onto the successor test. The prior audit recommended
  "do not discard, re-baseline"; the authoring session instead discarded and
  re-filed, which lands the same concern on a born-current item. The flag is
  resolved either way. The same commit also deleted the parameter-collision item
  (`conditions-method-duplicate-param-names`, R475's residue) "per its own
  instruction."

**R549** (`facts-and-commands`, In Progress) landed **nine slices** this window
(5b, 5c, 5d, 5e, 5f, 6, 7, 7a, 7b) and retired eight more model/emit symbols. Two
of the retirements produce **new consumer drift**:

- **`RowsMethodBody` + `RowsMethodSkeleton`, RETIRED (R549 slice 5c, `e4ea3bf`).**
  Both `grep` = 0. This **inverts a live premise**: **R545**
  (`model-free-of-emit-vocabulary`) built its diagnosis on `RowsMethodBody` as the
  one model type that "holds rendered output", and its second deliverable is
  literally "`RowsMethodBody` moves to `generators/`". That deliverable is now
  moot and the flagship exemplar is gone, so R545 moves from current to **§B
  re-spec** (its first deliverable, replacing `TypeName`/`ClassName` across the
  model, still stands: `ClassName` is in 106 main files).
- **`BatchKeyField.rowsMethodName`, RETIRED (R549 slice 5e, `765e643`).** The
  model-side naming fact is gone. **R333** cited it as a live carrier
  (`model/BatchKeyField.java:42`); that cite is now dead, adding to R333's
  still-unrefreshed projection drift (§C.6).

Net: **0 §A / 4 §B / 20 §C**, §D empty. Flag total holds at **24**, unchanged
from the prior window's `0 / 4 / 20`, but the composition shifted: **R387 left §B
by discard** and **R545 entered §B** by exemplar-deletion; §C carried all 20
items intact, with R333 losing its condition-emitter drift (refreshed by the R552
Done commit) while gaining `rowsMethodName` drift and keeping its untouched
projection/`ColumnField` deep sections.

## Changes since the 2026-07-30 audit

**27 commits** landed between the prior audit's commit (`86cc6b4`, 2026-07-30
03:22) and this HEAD (`d047eaa`, 2026-07-30 22:54), a roughly 20-hour window. It
was dominated by the R549 projection/launcher slices (5b through 7b) and the R541
and R552 Done gates.

**Two items ran to Done:** R541 and R552, both In Review -> Done, spec files
deleted, changelog entries at the head (`changelog.md` now leads with R541 then
R552, above R551).

**One flagged item discarded:** R387 (`type-conditions-test-code-string-migration`),
deleted in `d047eaa` as completed by `TypeConditionsGenerator`'s retirement. One
non-flagged item discarded: `conditions-method-duplicate-param-names` (R475's
tombstone), deleted per its own instruction in the same commit.

**New items filed** (all Backlog, all born-current, read against the current
model; every anchor verified present at the symbol):

- **R559** (`tenant-connections-recompile-edge`): a recompile-graph edge case in
  tenant connection synthesis. Anchors on live `PlanCompileGraph` (2 files).
- **R560** (`unskip-javapoet-tests`): re-enable disabled javapoet tests. Anchors
  on live `TierAnnotationEnforcementTest` (2 files).
- **R561** (`condition-glue-pipeline-body-scans`): the generated-body string scans
  `ConditionGluePipelineTest` carried through its rename, filed by the R552 Done
  gate to carry R387's surviving concern. Anchors on live `ConditionGluePipelineTest`.

**Other transitions:** R25 (`rebalance-test-pyramid`) Backlog -> Spec (JaCoCo
coverage instrumentation, nine decisions recorded); R543 (`corpus-asserts-fact-set`)
Backlog -> Spec then Spec -> Spec-revise, rewritten against post-slice-7 reality
(**and self-corrected**: its body now states `MethodCommand` and `RowsMethodBody`
are "both since retired", so it is current, not drift); R549 landed slices 5b, 5c,
5d, 5e, 5f, 6, 7, 7a, 7b (still In Progress); R10 self-swept rotted line citations
(`c7ad92f`).

**Terminal-state carriers not yet Done:** R333, R427, R555 (Ready); R347, R549 (In
Progress).

**Board accounting.** **152 item files** today (154 `roadmap/*.md` entries minus
`README.md` and `changelog.md`), down one net from the prior audit's 153: three
new items filed (+3), four left the board (-4: R541 and R552 to Done, R387 and the
parameter-collision item discarded). Status distribution: **134 Backlog, 13 Spec,
3 Ready, 2 In Progress, 0 In Review, 0 Done**. Movement from the prior window's
`135 / 11 / 3 / 2 / 2 / 0`: Backlog -1 (R559/R560/R561 in, R387 + parameter-item +
R25 + R543 out), Spec +2 (R25, R543 in), Ready flat, In Progress flat (R541 out to
Done, no new entry; R347 and R549 carry), In Review -2 (R541, R552 both to Done). A
non-recursive `^status: Done` grep over `roadmap/*.md` returns nothing (tombstone-free
for the twenty-third window running). No duplicate `id:`; max allocated id **R561**,
and `changelog.md` carries `next-id: R562`, clearing it. A `depends-on:` sweep over
all 152 item files resolves every edge (all non-empty edges are slug-based) to a
present file. The board is structurally clean.

**Net effect on flag counts: 24 flagged, 128 current.** 0 §A, 4 §B, 20 §C, 0 §D.

## Scope and method

All **152** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded: no `R<n>`). Model
claims were re-verified at the symbol. Because this window did more generator
surgery (nine R549 slices), every stale-live cite below was checked against a fresh
`grep` of the main sources rather than carried on the prior audit's word.

**The window's symbol changes, verified at the symbol:**

- **`RowsMethodBody` + `RowsMethodSkeleton`, RETIRED (R549 slice 5c, `e4ea3bf`).**
  Both `grep` = 0 in main sources. The service `@service` child fields render
  through `LaunchSource.ServiceCall` / `ServiceTableLift`; `buildServiceRowsMethod`
  and `buildServiceTableLift` retired with them (both `grep` = 0). Drives §B (R545).
- **`MethodCommandRegistry` + `MethodCommand` + `BatchKeyField.rowsMethodName` +
  `DmlTableField.reentryRowsMethodName`, RETIRED (R549 slice 5e, `765e643`).** The
  reentry family joined the launcher relation (`LaunchSource.Reentry`); the
  method-command registry and its model-side naming facts retired. All `grep` = 0.
  `BatchKeyField.rowsMethodName` gone from `BatchKeyField.java`. Drives §C.6 (R333's
  new `rowsMethodName` cite). R543 already cites `MethodCommand` as retired, so it
  is current, not drift.
- **`GlobalCommand`, sealed (R549 slice 7a, `4906a0a`).** Live (4 main files,
  defined at `command/GlobalCommand.java`). New construct, no roadmap drift.
- **`SplitRowsMethodEmitter`, still LIVE (7 main files, class defined at
  `SplitRowsMethodEmitter.java`).** Slice 5c narrowed it ("now purely the ...") but
  the class and its `rows<X>`/`scatter*ByIdx` emission survive. **Every item that
  cites it is still correct**; do not repoint it. R333's crosswalk rows 3/4 name it
  and stay.

**Retired symbols from prior windows, re-verified still retired:**

- **`TypeConditionsGenerator`, still RETIRED (R552 slices 2+3).** `grep` = 0.
  Condition emission is `render/ConditionGlueRenderer` + `plan/ConditionCommands`.
  Drives §C.6 (R333's deep prose at `:527`, R35), §C.2 cross-note (R462).
- **`TypeClassGenerator` + `collectRequiredProjection`, still RETIRED (R549 slice
  3.1).** Both `grep` = 0. Projection emission is `render/ProjectionUnitRenderer` +
  `plan/ProjectionCommands`, minted via `plan/GeneratedUnits`. Drives §C.6 (R333's
  deep sections, R231, R35).
- **`QueryConditionsGenerator`, still RETIRED (R552 slice 1).** `grep` = 0.
- **`SplitTableField` / `SplitLookupTableField` / `RecordTableField` /
  `RecordLookupTableField`, still MERGED into `BatchedTableField` /
  `BatchedLookupTableField` (records defined in `ChildField.java:495` / `:622`)
  (R432/R51).** All four old names `grep` = 0 as defined classes. Drives §C.2.
- **`ColumnField` / `CompositeColumnField` / `CompositeColumnReferenceField`,
  still MERGED into `ColumnBackedField` (32 files) (R508).** All old names
  undefined as live classes. Drives §C.3 (R333).
- **`GraphitronType.TableInputType` and `TypeBuilder.buildNonTableInputType`,
  still RETIRED (R519).** Both `grep` = 0. Drives §C.5.
- **`MutationInputResolver.resolveInput`, still RETIRED (R515);
  `admitMutationInputFields` LIVE.** Drives §C.4 and part of §C.5 (R257).
- **`SourceKey.Reader`, still gone; `SourceKey` a plain record (R431).** Drives
  §C.1 and §B (R71).
- **`Rejection.Deferred.planSlug`, still gone (R484).** Drives §C.1.

## A. Obsolete: should leave the active roadmap (0)

Empty. No item's entire premise was invalidated to the point of removal this
window that has not already left by discard. R387 was the one such case and left
the board directly by discard (§ "Changes since"), its surviving concern re-filed
as R561. R545's premise was *partially* invalidated (its second deliverable is
moot) but its first deliverable survives, so it re-specs rather than discards and
sits in §B. R221 remains a re-derivation call at pickup (§B). R520
(`table-on-input-removal-housekeeping`) remains the deliberately-deferred docs/LSP
tail, not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (4)

Three carried unchanged (R85, R221, R71); one re-classed in this window (R545) as
its exemplar was deleted. The prior window's fourth §B entry, **R387, is resolved**
and removed: it was discarded by the R552 Done gate and its surviving concern
re-filed as R561 (born-current, not flagged).

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R545** model-free-of-emit-vocabulary | Backlog | **Re-classed in this window.** The item's whole diagnosis names `RowsMethodBody` as the one model type "holding rendered output" (its permits "carry an opaque `CodeBlock`"), and its **second deliverable** is "`RowsMethodBody` moves to `generators/` ... with no semantic change." R549 slice 5c **deleted `RowsMethodBody` and `RowsMethodSkeleton` outright** (`grep` = 0 each), so both the exemplar and the move-it deliverable are gone. The **first deliverable survives**: `TypeName`/`ClassName` are still model-pervasive (`ClassName` in 106 main files) and the emit-computing helpers persist (`RowsMethodShape` live in 5 files). | **Re-spec.** Drop the `RowsMethodBody` diagnosis and the second deliverable entirely (the shell-to-shell handoff it fought no longer exists as a misfiled model type). Keep and re-baseline the first deliverable (`JavaTypeRef` replacing `TypeName`/`ClassName`, emit-computing helpers to the shell) against the post-R549 model; verify `RowsMethodShape.strictPerKeyType` / `standardScalarJavaType` / `outerRowsReturnType` are still the live derivations to relocate. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Carried, unchanged this window (deepened last window). Both classes the plan names as edit targets are deleted: `QueryConditionsGenerator` (gone R552 slice 1) and `TypeClassGenerator` (gone R549 slice 3.1). The plan's `QueryConditionsGenerator.java:NNN` line cites are all dead; condition emission moved to `render/ConditionGlueRenderer` and projection emission to `render/ProjectionUnitRenderer`. | **Re-derive against the new `render/` layer.** Determine whether `ConditionGlueRenderer` and `ProjectionUnitRenderer` still exhibit the duplicated helper-emission problem the item targets; if so, re-spec onto them. Every `QueryConditionsGenerator.java:NNN` cite is dead and must be dropped. |
| **R221** validator-walks-plain-input-unbound-fields | Backlog | Carried, unchanged. R519 deleted `TableInputType` and the `validateTableInputType` type walk this item's whole diagnosis rests on, replacing it with per-consumer `collectInputFieldRejections` (live). The stated gap ("only `TableInputType.inputFields()` is walked, plain inputs escape") describes an architecture that no longer exists; the per-consumer model R519 shipped is close to the fan-out R221 proposed. | **Re-derive against the post-R519 model.** If per-consumer resolution already validates plain-input `UnboundField + @condition(override:false)` shapes, **discard** (gap closed). If a plain-input arg outside a table write-target path still escapes, **re-spec** around `collectInputFieldRejections`. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried, unchanged. R431 (Done) deleted the target surface: the body cites `SourceKey.java` line ranges and `SourceKey.Reader.SourceRowsCall` as "the live surface", but `SourceKey` is a plain record with no `Reader`. The sequencing line still reads "R431 ... plans to decompose"; R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model (`KeyLift` live). The goal (recordN key parity, non-jOOQ record parents) is intact; drop the "R431 plans to decompose" tense. |

## C. Outdated: update references only (work valid, refs stale) (20)

Substance intact; names and line numbers drifted. All 20 carried from the prior
window (re-verified at the symbol; every driving symbol was untouched this window
except the two R549 retirements that deepened R333). No §C item left and none
entered this window.

### C.1 `planSlug` / `SourceKey.Reader` removal drift (carried, unchanged)

R484 (Done) removed `Rejection.Deferred.planSlug`; R431 (Done) removed the
`SourceKey.Reader` interface. Deferrals now anchor by `StubKey.VariantClass`; column
reads off a parent row lift via `KeyLift.FkColumns`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R454** routine-write-result-shapes | Backlog | `:18` names the deferred shapes as "typed `Deferred`s pointing at this item's planSlug"; `planSlug` is gone. | **Re-anchor:** deferred shapes surface via `StubKey.VariantClass`, no roadmap pointer. |
| **R447** routine-chain-fetch-form-breadth | Backlog | `:18` "`planSlug` points here"; and `:24`/`:26` name `SplitLookupTableField` / `RecordTableField` as live. | **Re-anchor** both: drop the `planSlug` phrasing; repoint the two variant names to `BatchedLookupTableField` / `BatchedTableField`. |
| **R180** record-parent-column-read-helper | Spec | `:35` says "R431 ... now In Progress" (R431 is **Done**) and names `SourceKey.Reader.AccessorCall` as a live carrier (`SourceKey` has no `Reader`). | **Re-anchor** the `AccessorCall` carrier onto the decomposed model; fix the R431 tense to Done. |
| **R505** tenant-index-parent-row-routing | Backlog | `:21` names "a column read off the parent row (the `SourceKey.Reader` family)" as the carrier for the proposed `ParentRowBound` arm. Mechanism is live via `KeyLift.FkColumns`. | **Re-anchor** the one parenthetical: per-row column read lifts via `KeyLift.FkColumns`. |

### C.2 R432 leaf-merge drift (carried, unchanged)

`SplitTableField` / `RecordTableField` merged to `BatchedTableField`;
`SplitLookupTableField` / `RecordLookupTableField` to `BatchedLookupTableField`.
`SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears.
R507's guard excludes `roadmap/`, so this cluster stays an audit responsibility.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** the planned assertion to `BatchedTableField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | `:21`, `:28`, `:39`, `:41`, `:42` name `SplitTableField` / `SplitLookupTableField` as live; `SplitRowsMethodEmitter` in the same passages is correct. **Plus the condition-emitter layer:** `:57`, `:99` name `QueryConditionsGenerator` as live and `:145` names `TypeConditionsGenerator`'s walk; both classes are deleted. | **Re-anchor** the surviving `Split*` names to `Batched*`; **and** repoint the `QueryConditionsGenerator` **and** `TypeConditionsGenerator` cites to `ConditionGlueRenderer` / `ConditionCommands`. Leave `SplitRowsMethodEmitter` untouched. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38`'s R305 lineage note "collapsed it into `RecordTableField`, `ChildField.java:912`" is doubly stale: the name is `BatchedTableField`, and `:912` no longer lands there. | **Re-anchor** that one name + cite; leave the surrounding R305/R287 history. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34`: "a keyed batch (DataLoader, as `SplitTableField` / `RecordTableField` do via `SplitRowsMethodEmitter`)". Variant names stale; emitter fine. | **Re-anchor** the two variant names. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`: the planned `COMPOSITE_KEY_ROW2_PATH_KEYED` case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R7** decompose-typefetchergenerator | Backlog | `:30` proposes a hypothetical decomposed emitter `SplitTableFieldEmitter`; a decomposition today would name it `BatchedTableFieldEmitter`. Illustrative "etc." naming. | **Low priority:** refresh the illustrative name at pickup. |
| **R323** nestingfield-multiparent-batchkey-leaves | Backlog | `:18` lists `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField` as the live classification options; `:30` names `SplitTableField` + `RecordTableField` (and "the lookup twins") as the leaves to fold. All four merged into `Batched*`. | **Re-anchor** the four variant names to `BatchedTableField` / `BatchedLookupTableField`. Substance (multi-parent batch-key leaves) intact. |

### C.3 R508 composite-column dissolution drift (carried, unchanged)

R508 (Done) merged `ColumnField` / `CompositeColumnField` /
`CompositeColumnReferenceField` into `ColumnBackedField` (32 files, defined in
`ChildField.java:265`).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | The design doc names the retired carriers as live in several deep sections: `:692` "a plain `ColumnField` carry none", `:745` "`ColumnField` (`ChildField.java:275`)" (a concrete class+line cite; the record is now `ColumnBackedField` at `ChildField.java:265`), `:796` "`source.table` for `ColumnField`", `:1110` "the present `ColumnField`-read", `:1138` "`ColumnField.compaction`", `:568`/`:1143` "`CompositeColumnField` / `CompositeColumnReferenceField`" (`:1545` names them too but is explicitly annotated "Shipped by R508", so it stays). | **Re-anchor** the carrier names to `ColumnBackedField` (and its `columns`/`compaction` components) and fix the `ChildField.java:275` cite to `:265`. Part of the same R333 refresh as §C.6; the design substance is intact. |

### C.4 `resolveInput` retirement drift (carried; R515)

R515 (Done) removed `MutationInputResolver.resolveInput`, hoisting its admission
set to `admitMutationInputFields` (live).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot composition "in `MutationInputResolver.resolveInput`"; the method is gone, the composition is live in `admitMutationInputFields`. Design intact. | **Re-anchor** the one sentence to `admitMutationInputFields`. |

### C.5 R519 `TableInputType` removal drift (carried, unchanged)

R519 (Done) deleted `GraphitronType.TableInputType`, its type walk, and
`TypeBuilder.buildNonTableInputType`, moving input classification to per-consumer
resolution. (R221's deeper premise change is in §B.)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R222** dimensional-model-pivot | Spec | `:46` names `GraphitronType.TableInputType` as "a separate sibling root ... Nine consumer sites discriminate by permit identity"; `:15`/`:428` echo it. R519 executed exactly that collapse, so one of R222's three target organs (input-side classification) has **shipped**. | **Re-baseline the input-side section:** record the input-side pivot as delivered by R519 and narrow the umbrella's remaining scope to the field-side and `Unclassified*` organs. |
| **R213** input-field-rejection-attribution | Backlog | `:64` scope note: "`@table` input types route through `TableInputType` classification ... this item is plain-input-only." That routing is gone (all inputs resolve per-consumer now). Core subject and its `InputFieldResolver` cites are valid. | **Re-anchor** the one scope-note sentence; verify the `InputFieldResolver` line cite still lands. |
| **R234** jooq-embedded-and-udt-input-backings | Spec | `:15` cites `TypeBuilder.buildNonTableInputType` as the live dispatch to extend; that method is gone. The sibling arms it extends (`JooqRecordInputType` / `JooqTableRecordInputType`) are live. | **Re-anchor** the dispatch site to the current `TypeBuilder` input-classification path; the design is intact. |
| **R257** updaterows-walker-sdl-substrate | Backlog | Carried §C.4 plus §C.5 drift. `:17` calls `resolveInput` "the legacy resolver" (gone since R515); `:15`/`:19` reach the admitted column carriers "via `TableInputType.inputFields()`" (gone since R519). | **Re-anchor** both dead names: `resolveInput` to `admitMutationInputFields`, `TableInputType.inputFields()` to the per-consumer input resolution. Substance intact. |
| **R337** input-nesting-projection-classification | Backlog | `:30` reaches an input object's own fields "via ... `TableInputType.inputFields()`" as the mechanism backing LSP hover / goto / inlay; that walk is gone (R519). | **Re-anchor** the one mechanism cite to the per-consumer input resolution; the LSP feature scope is intact. |

### C.6 Condition + projection emitter dissolution drift (carried; R552 + R549 slice 3.1, deepened this window)

R552 slices 2+3 deleted `TypeConditionsGenerator`; R549 slice 3.1 deleted
`TypeClassGenerator` + `collectRequiredProjection`. This window, R549 slice 5e also
retired `BatchKeyField.rowsMethodName`. Condition emission is now
`render/ConditionGlueRenderer` (fed by `plan/ConditionCommands`); projection
emission is `render/ProjectionUnitRenderer` (fed by `plan/ProjectionCommands`,
minted via `plan/GeneratedUnits`). R462 also carries the condition-emitter drift and
is cross-noted in §C.2.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh advanced this window but is still incomplete.** The R552 Done commit (`d047eaa`) updated the **condition layer**: crosswalk row 5 (`:1458`) now names `ConditionGlueRenderer`, the "half-migrated seam" note is marked closed, and the seam census table's Condition row is repointed. **What remains stale:** the **projection layer** is untouched, `:1596`/`:1617`/`:1672` (`TypeClassGenerator.java:216`)/`:1740`/`:1946` name `TypeClassGenerator` as the live `$fields` emitter and `:1619`/`:1791`/`:1946`/`:1970` cite `collectRequiredProjection`; the deep condition prose at `:527` still names `TypeConditionsGenerator`; and **new this window**, `:1665` cites `BatchKeyField.rowsMethodName()` (`model/BatchKeyField.java:42`, retired slice 5e) and crosswalk row 3 (`:1458`) reads "settled (`rowsMethodName`)". All these classes/facts are deleted. | **Finish the refresh.** Repoint `TypeClassGenerator` / `collectRequiredProjection` to `ProjectionUnitRenderer` / `ProjectionCommands`; drop the `:527` `TypeConditionsGenerator` prose or repoint to `ConditionGlueRenderer`; drop the dead `BatchKeyField.rowsMethodName` cite (the naming convention survives in the generators, but the model-side fact is gone). Do this in one pass with §C.3. Rows 3/4 (`SplitRowsMethodEmitter`) stay: that class is live. |
| **R231** emit-text-mapped-enum-fields-as-enum-type | Backlog | `:39` locates "the field-type-emit fork (likely in `TypeClassGenerator` or ...)"; `TypeClassGenerator` was deleted by R549 slice 3.1. The item is an investigation whose named starting point is gone. | **Re-anchor** the investigation locus to `ProjectionUnitRenderer` / the projection command family (`plan/ProjectionCommands`), where the field-type-emit fork now lives. |
| **R35** source-orientation-javadocs | Backlog | `:42` enumerates classes needing a class-level javadoc sweep: "`TypeFetcherGenerator`, `TypeClassGenerator`, `TypeConditionsGenerator`, ...". Two of the five names are now deleted classes. | **Re-anchor** the enumeration: drop `TypeClassGenerator` and `TypeConditionsGenerator`, optionally adding the successor renderers (`ProjectionUnitRenderer`, `ConditionGlueRenderer`) if the sweep should cover them. Low priority; the sweep scope is illustrative. |

## D. Structural: (0)

Empty. `changelog.md` carries `next-id: R562`, clearing the max allocated id
(R561). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and a
`depends-on:` sweep over all 152 item files resolves every edge, R-number and slug
alike, to a present file. The three items filed this window (R559, R560, R561)
carry well-formed front-matter and were read against the current model as
born-current.

## Cross-cutting observations

1. **The fix-then-discard path is now the programme's default for absorbed flags.**
   R387 is the second flagged item to leave the board by discard since the audit
   series began (R472 was the first, last window). Both were closed by the item
   doing the surgery: R472 by R549 slice 3.1, R387 by the R552 Done gate. But the
   two took opposite routes to the *residue*: R472's bug simply vanished, while
   R387's surviving concern (delegation-body string scans) was **re-filed fresh as
   R561** rather than re-baselined in place. Re-filing born-current is cleaner than
   an in-place re-anchor and lands the same subject; the prior audit's "do not
   discard" recommendation was superseded correctly.

2. **A generator retirement still leaves sibling items stale, and a partial refresh
   still under-covers.** R549 slices 5c/5e retired `RowsMethodBody`/`RowsMethodSkeleton`
   and `BatchKeyField.rowsMethodName`; the authoring item R549 swept its own body and
   R543 self-corrected, but R545 (an unrelated FCIS-hygiene item) and R333's deep
   projection sections were left stale. The R552 Done gate refreshed R333's *condition*
   crosswalk row and seam note but left its *projection* sections and picked up new
   `rowsMethodName` drift, so R333 is again internally inconsistent (row 2/5 current,
   the later seam table still `TypeClassGenerator`). The lesson from the prior two
   windows holds: a cutover that deletes a symbol must sweep the *sibling* items, and
   a mid-programme refresh should complete or scope itself explicitly.

3. **An exemplar deletion can invert an item's premise, not just its references.**
   R545 is the sharp case this window: it is not stale-live drift (a name that moved)
   but a diagnosis whose subject was *deleted* (`RowsMethodBody`, the one model type
   holding rendered output, is gone). This lands it in §B, not §C: no re-anchor
   recovers a deliverable whose target no longer exists. The tell is that a `grep`
   for the cited symbol returns zero *and* the item's action depends on that symbol
   existing, versus §C where the symbol was merely renamed.

4. **Symbol-level re-verification continues to pay in a busy window.** Re-running
   `grep` over every stale-live cite (rather than carrying the prior audit's word)
   caught the two new R549 retirements, confirmed `SplitRowsMethodEmitter` survives
   (so its many correct cites stay), and confirmed R543's self-correction (so it is
   not double-flagged). No carried §C item's driving symbol regressed.

5. **An In-Progress programme's own prose is not drift.** R549 (In Progress) and R347
   (`lsp-structural-consolidation`, In Progress) describe their own subject matter,
   including retired generators in R549's slice ledgers. These are the items doing the
   work; their retrospective and forward-looking prose is not consumer-facing
   stale-live, and they are not flagged (the same rule prior audits applied).

6. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-31._
