# Roadmap staleness audit: 2026-07-30

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `4d5081e`, committed 2026-07-30 00:00, audited 2026-07-30). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-29` staleness audit, which has been deleted;
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
  review; left in place. (It was refreshed heavily this window by the
  command-layer landings; the document itself is not a flag target.)

`classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
inventory; its "superseded, historical" banner is intact. No action; it stays as
lineage.

## Headline: the busiest generator-surgery window yet deleted two more generators (`TypeConditionsGenerator`, `TypeClassGenerator`), resolved one flagged item by discard, and moved two authoring items to In Review

The command-layer programme accelerated hard this window. Where the prior window
landed one generator deletion (`QueryConditionsGenerator`, R552 slice 1), this one
landed **two more**, both consumer-visible:

- **`TypeConditionsGenerator`, RETIRED (R552 slices 2+3, "entity-layer
  retirement").** The pure entity-scoped condition layer (`<ReturnType>Conditions`
  per-participant classes) folded into `render/ConditionGlueRenderer` /
  `plan/ConditionCommands`. `grep` finds the class in **zero** main or test files.
  **This directly inverts the prior audit's cross-cutting observation #4**, which
  told every re-anchor to *keep* the `TypeConditionsGenerator` cites and repoint
  only `QueryConditionsGenerator`; both condition emitters are now gone and every
  such cite must be repointed.
- **`TypeClassGenerator` + `collectRequiredProjection`, RETIRED (R549 slice 3.1,
  "the projection command").** The type-bound `$fields`/`$fieldsGrouped` fold
  became one `$project(grouped, table, env)` per projection unit, produced by
  `plan/ProjectionCommands` and interpreted by `render/ProjectionUnitRenderer`;
  the four inline arm emitters (`InlineColumnReferenceFieldEmitter`,
  `InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`) retired with it.
  `TypeClassGenerator` and `collectRequiredProjection` both `grep` = 0. **The prior
  audit leaned on `TypeClassGenerator.collectRequiredProjection` as the live method
  proving R516 drift-free; that method no longer exists** (R516 is Done and gone, so
  no active item breaks on that specifically, but the projection-emitter cites do).

The same slice 3.1 commit (`0e7fcd2`) **resolved a flagged item by discard**:
**R472** (`nested-generated-condition-filters-never-emitted`, prior §C.2) was
deleted because its bug is fixed. The commit message is explicit: "The
nested-coordinate walk closes the nested-generated-filter gap: the deferred
rejection deletes and its pinned fixture flips to producing the row." This is the
first flagged item to leave the board by fix-then-discard since the audit series
began.

Two authoring items reached **In Review** with their work landed:

- **R552** (`condition-command`) In Progress -> In Review, all four slices in
  trunk. It is the authoring item for the whole condition cutover; its body names
  `QueryConditionsGenerator` / `TypeConditionsGenerator` throughout as the
  pre-cutover state it migrated away from. That is the authoring item's own record,
  not consumer drift (cross-cutting obs #3), so R552 is **not flagged**.
- **R541** (`root-query-unit-seam`) Ready -> In Progress -> In Review, all six
  slices landed. **The prior audit's §B "re-spec R541" flag is now resolved by the
  work shipping**: the launcher reframe the flag worried was baselined on a
  since-deleted `QueryConditionsGenerator` is done, so there is no stale baseline
  left to implement against. R541 leaves the flag list.

Net: **0 §A / 4 §B / 20 §C**, §D empty. Up from the prior window's `0 / 4 / 17` by
**+3 §C net**. The movement is larger than the number suggests: R472 left by
discard and R541 left by shipping, while five items entered (R387 re-classed into
§B; R231, R323, R337 and the R333 `ColumnField` layer into §C). Two of the entries
(R231, R35 in the enumeration cluster) are **new** drift from this window's two
deletions; three (R323, R337, and R333's `ColumnField` layer) are **carried drift
the prior audit did not enumerate**, surfaced by the symbol-level re-verification
this window's surgery forced (see §C.2, §C.3, §C.5).

## Changes since the 2026-07-29 audit

**27 commits** landed between the prior audit's HEAD (`4d5f48d`, 2026-07-28 23:23)
and this HEAD (`4d5081e`, 2026-07-30 00:00), a roughly 25-hour window. It was
dominated by the R549 projection/launcher slices (3.1, 3.2, 3.3, 3b, 4, 5, 5a, 5b)
and the R541 and R552 endgames.

**No items ran to Done this window.** R541 and R552 both reached In Review but
await their independent-session Done gate; the `changelog.md` head is unchanged
from the prior window (R551 at top, R516 at `:891`).

**One item discarded:** R472 (`nested-generated-condition-filters-never-emitted`),
deleted in `0e7fcd2` (R549 slice 3.1) because the projection command's
nested-coordinate walk fixed the bug. No changelog entry is expected for a discard;
the item file simply left the board.

**New items filed** (all Backlog, all born-current, read against the current model):

- **R556** (`pivot-nesting-representative-read-divergence`): a fetcher-side
  read-name divergence bug when a projection type is reached by both a pivot edge
  and a nesting edge under `@field(name:)` remap. Anchors on live
  `TypeFetcherGenerator.indexNestingByType`,
  `FetcherRegistrationsEmitter.collectNestedTypes`, `PivotSlotField.readName`,
  `ColumnBackedField`, and `GraphitronSchemaValidator.validateNestingParentCompat`.
- **R557** (`split-query-marker-sweep`): a completeness enforcer for `@splitQuery`
  mirroring `TenantBindingIndex.sweepUnreachedFanOutMarkers`. Anchors on live
  `CatalogBuilder.projectFieldClassification`, `BatchedTableField`,
  `BatchedLookupTableField`, `BatchedPivotField`, and `FieldBuilder`.
- **R558** (`root-family-validator-mirror-gaps`): two classifier invariants with no
  validate-time twin, surfaced by the launcher migration. Anchors on live
  `SqlGeneratingField`, `ResultShape.RecordList`,
  `GraphitronType.TableInterfaceType`, `render/DiscriminatedTableFragments`,
  `GraphitronSchemaValidator.validateJoinedTableReprojection`, and
  `TypeBuilder.buildParticipantList`.

Every anchor of all three items was verified present at the symbol. None names a
deleted construct.

**Other transitions:** R541 Ready -> In Progress -> In Review (six slices); R552 In
Progress -> In Review (slices 2-4); R549 landed slices 3.1, 3.2, 3.3, 3b, 4, 5, 5a
and 5b prep (still In Progress); R333 gained a partial refresh (its crosswalk table
rows 2, 7, 12-14 were updated for the R549 landings, but the deeper analysis
tables and prose were not; see §C.6).

**Terminal-state carriers not yet Done:** R333, R427, R555 (Ready); R347, R549 (In
Progress); R541, R552 (In Review).

**Board accounting.** **153 item files** today (155 `roadmap/*.md` entries minus
`README.md` and `changelog.md`), up two net from the prior audit's 151: three new
items filed (+3), one discarded (-1). Status distribution: **135 Backlog, 11 Spec,
3 Ready, 2 In Progress, 2 In Review, 0 Done**. Movement from the prior window's
`133 / 11 / 4 / 3 / 0 / 0`: Backlog +2 (R556/R557/R558 in, R472 out), Spec flat,
Ready -1 (R541 out to In Progress), In Progress -1 (R541 in, R552 out; R541 then
continued to In Review), In Review +2 (R541, R552). A non-recursive `^status: Done`
grep over `roadmap/*.md` returns nothing (tombstone-free for the twenty-second
window running). No duplicate `id:`; max allocated id **R558**, and `changelog.md`
carries `next-id: R559`, clearing it. A `depends-on:` sweep over all 153 item files
resolves every edge (all non-empty edges are slug-based, e.g.
`root-query-unit-seam -> [facts-and-commands, condition-command]`) to a present
file. The board is structurally clean.

**Net effect on flag counts: 24 flagged, 129 current.** 0 §A, 4 §B, 20 §C, 0 §D.

## Scope and method

All **153** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded: no `R<n>`). Model
claims were re-verified at the symbol. Because this window did real generator
surgery, every stale-live cite below was checked against a fresh `grep` of the main
sources rather than carried on the prior audit's word.

**The window's symbol changes, verified at the symbol:**

- **`TypeConditionsGenerator`, RETIRED (R552 slices 2+3, `7cac278`).** No definition
  in main or test sources (`grep` = 0). Folded into `render/ConditionGlueRenderer`
  (6 main files) + `plan/ConditionCommands` (6 main files). Its
  `TypeConditionsGeneratorTest` deleted with it; the successor pipeline-tier tests
  are `ConditionCommandsPipelineTest` and `ConditionGluePipelineTest`. Drives §B
  (R387), §C.6 (R333, R462) and the enumeration cluster (R35).
- **`TypeClassGenerator` + `collectRequiredProjection`, RETIRED (R549 slice 3.1,
  `0e7fcd2`).** Both `grep` = 0. Replaced by `plan/ProjectionCommands` +
  `render/ProjectionUnitRenderer`, minted through `plan/GeneratedUnits`. The four
  inline arm emitters (`Inline{ColumnReference,Table,LookupTable}FieldEmitter`)
  retired with them. Drives the projection-emitter drift in §C.6 (R333) plus the
  enumeration cluster (R35, R231) and deepens §B R85.
- **`QueryConditionsGenerator`, still RETIRED (R552 slice 1, prior window).** `grep`
  = 0 confirmed again. Carried §B (R85) and §C.6 (R333, R462).
- **`SplitRowsMethodEmitter`, still LIVE (7 main files, class defined at
  `SplitRowsMethodEmitter.java:64`).** Reduced from 12 files as R549 slice 5a
  retired one legacy arm, but the class and its `rows<X>`/`scatter*ByIdx` emission
  survive. **Every item that cites it is still correct**; do not repoint it.

**Retired symbols from prior windows, re-verified still retired:**

- **`SplitTableField` / `SplitLookupTableField` / `RecordTableField` /
  `RecordLookupTableField`, MERGED into `BatchedTableField` (17 files) /
  `BatchedLookupTableField` (16 files) (R432/R51).** All four old names `grep` = 0
  as defined classes. Drives §C.2.
- **`ColumnField` / `CompositeColumnField` / `CompositeColumnReferenceField`, MERGED
  into `ColumnBackedField` (30 files, defined in `ChildField.java:265`) (R508).**
  All old names undefined; the 6 remaining `\bColumnField\b` hits in main sources
  are `{@code ColumnField.filmId}`-style illustrative javadoc, not a live class.
  §C.3 is **no longer empty**: R333 cites the retired carriers as live (see §C.3).
- **`GraphitronType.TableInputType` and `TypeBuilder.buildNonTableInputType`,
  RETIRED (R519).** Both `grep` = 0. Drives §C.5.
- **`MutationInputResolver.resolveInput`, RETIRED (R515); `admitMutationInputFields`
  LIVE (2 files).** Drives §C.4 and part of §C.5 (R257).
- **`SourceKey.Reader`, still gone; `SourceKey` a plain record (R431).** Drives §C.1
  and §B (R71).
- **`Rejection.Deferred.planSlug`, still gone (R484).** Drives §C.1.

## A. Obsolete: should leave the active roadmap (0)

Empty. No item's entire premise was invalidated to the point of removal this
window that has not already left by discard. R472 was the one such case and left the
board directly (§ "Changes since"). R387 was the nearest §A candidate (its named
target file was deleted), but the anti-pattern it fights survives in the successor
test, so it re-specs rather than discards and sits in §B. R221 remains a
re-derivation call at pickup (§B). R520 (`table-on-input-removal-housekeeping`)
remains the deliberately-deferred docs/LSP tail, not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (4)

Two carried unchanged (R221, R71); one carried and deepened by this window's second
deletion (R85); one re-classed in from the §A boundary this window (R387). The
prior window's fourth §B entry, **R541, is resolved** and removed: its launcher
reframe shipped (In Review, six slices), so the stale-baseline concern the flag
raised no longer has anything to implement against.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R85** helper-emission-non-fetcher-hosts | Backlog | **Deepened this window.** Both classes the item's plan names as its edit targets are now deleted: `QueryConditionsGenerator` (gone R552 slice 1) and `TypeClassGenerator` (gone R549 slice 3.1). The plan proposes to "Make `QueryConditionsGenerator` and `TypeClassGenerator` instantiate an ..." helper (`:40`), anchored on `QueryConditionsGenerator.java:107-109` line cites (`:32`, `:44`, `:48`). Both surfaces were deleted wholesale; condition emission moved to `render/ConditionGlueRenderer` and projection emission to `render/ProjectionUnitRenderer`. | **Re-derive against the new `render/` layer.** Determine whether `ConditionGlueRenderer` and `ProjectionUnitRenderer` still exhibit the duplicated helper-emission problem the item targets; if so, re-spec onto them. Every `QueryConditionsGenerator.java:NNN` cite is dead and must be dropped. |
| **R387** type-conditions-test-code-string-migration | Backlog | **Re-classed from §A this window.** The item's named target, `TypeConditionsGeneratorTest`, was deleted with the entity layer (R552 slices 2+3), so the migration as written cannot run. But the code-string body-assertion pattern it fights **survived into the successor test**: `ConditionGluePipelineTest` asserts on `method.code().toString()` + `contains("decodeBarRowsOrThrow(")` (`:70-73` via the `bodyOf` helper `:143-147`), a milder delegation-call form than the retired test's raw jOOQ-expression asserts. So the concern is live but relocated. | **Re-baseline onto `ConditionGluePipelineTest` / `ConditionCommandsPipelineTest`.** Reassess whether the surviving `contains("decode...(")` delegation assertions are the same maintenance burden (behaviour already pinned at execution + compilation tiers) or an acceptable structural pin, and re-spec the migration scope accordingly. Do **not** discard: the anti-pattern reappeared, so the item still has a subject. |
| **R221** validator-walks-plain-input-unbound-fields | Backlog | Carried, unchanged. R519 deleted `TableInputType` and the `validateTableInputType` type walk this item's whole diagnosis rests on, replacing it with per-consumer `collectInputFieldRejections` (2 files, live). The stated gap ("only `TableInputType.inputFields()` is walked, plain inputs escape") describes an architecture that no longer exists; the per-consumer model R519 shipped is close to the fan-out R221 proposed. | **Re-derive against the post-R519 model.** If per-consumer resolution already validates plain-input `UnboundField + @condition(override:false)` shapes, **discard** (gap closed). If a plain-input arg outside a table write-target path still escapes, **re-spec** around `collectInputFieldRejections`. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried, unchanged. R431 (Done) deleted the target surface: the body cites `SourceKey.java` line ranges and `SourceKey.Reader.SourceRowsCall` as "the live surface", but `SourceKey` is a plain record with no `Reader`. The sequencing line still reads "R431 ... plans to decompose"; R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model (`KeyLift` 15 files, live). The goal (recordN key parity, non-jOOQ record parents) is intact; drop the "R431 plans to decompose" tense. |

## C. Outdated: update references only (work valid, refs stale) (20)

Substance intact; names and line numbers drifted. Fourteen carried from the prior
window (re-verified, model anchors untouched except this window's command-layer
additions, so the re-anchor targets still land); R472 left by discard; three items
are **carried drift the prior audit did not enumerate** (R323 in §C.2, R337 in
§C.5, and R333's `ColumnField` layer in §C.3); and two are **new drift** from this
window's two deletions (R231, R35 in §C.6, plus a deeper layer added to R333 and
R462).

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

### C.2 R432 leaf-merge drift (carried; one item added this window as an audit correction)

`SplitTableField` / `RecordTableField` merged to `BatchedTableField`;
`SplitLookupTableField` / `RecordLookupTableField` to `BatchedLookupTableField`.
`SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears.
R507's guard excludes `roadmap/`, so this cluster stays an audit responsibility.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** the planned assertion to `BatchedTableField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | `:21`, `:28`, `:39`, `:41`, `:42` name `SplitTableField` / `SplitLookupTableField` as live; `SplitRowsMethodEmitter` in the same passages is correct. **Plus the condition/entity-emitter layer:** `:57`, `:99` name `QueryConditionsGenerator` as live and `:145` names `TypeConditionsGenerator`'s walk; both classes are deleted. | **Re-anchor** the surviving `Split*` names to `Batched*`; **and** repoint the `QueryConditionsGenerator` **and** `TypeConditionsGenerator` cites to `ConditionGlueRenderer` / `ConditionCommands`. Leave `SplitRowsMethodEmitter` untouched. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38`'s R305 lineage note "collapsed it into `RecordTableField`, `ChildField.java:912`" is doubly stale: the name is `BatchedTableField`, and `:912` no longer lands there. | **Re-anchor** that one name + cite; leave the surrounding R305/R287 history. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34`: "a keyed batch (DataLoader, as `SplitTableField` / `RecordTableField` do via `SplitRowsMethodEmitter`)". Variant names stale; emitter fine. | **Re-anchor** the two variant names. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`: the planned `COMPOSITE_KEY_ROW2_PATH_KEYED` case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R7** decompose-typefetchergenerator | Backlog | `:30` proposes a hypothetical decomposed emitter `SplitTableFieldEmitter`; a decomposition today would name it `BatchedTableFieldEmitter`. Illustrative "etc." naming. | **Low priority:** refresh the illustrative name at pickup. |
| **R323** nestingfield-multiparent-batchkey-leaves | Backlog | **Audit correction (not new this window).** `:18` lists `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField` as the live classification options; `:30` names `SplitTableField` + `RecordTableField` (and "the lookup twins") as the leaves to fold. All four merged into `Batched*` in a prior window; the prior audit did not enumerate this item. | **Re-anchor** the four variant names to `BatchedTableField` / `BatchedLookupTableField`. Substance (multi-parent batch-key leaves) intact. |

### C.3 R508 composite-column dissolution drift (newly populated this window as an audit correction)

R508 (Done) merged `ColumnField` / `CompositeColumnField` /
`CompositeColumnReferenceField` into `ColumnBackedField`. The prior audit declared
this cluster "empty of roadmap items"; the symbol-level re-verification this window
forced shows that was inaccurate for R333.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | The design doc names the retired carriers as live in several deep sections: `:692` "a plain `ColumnField` carry none", `:745` "`ColumnField` (`ChildField.java:275`)" (a concrete class+line cite; the record is now `ColumnBackedField` at `ChildField.java:265`), `:796` "`source.table` for `ColumnField`", `:1110` "the present `ColumnField`-read", `:1138` "`ColumnField.compaction`", `:568`/`:1143`/`:1545` "`CompositeColumnField` / `CompositeColumnReferenceField`". | **Re-anchor** the carrier names to `ColumnBackedField` (and its `columns`/`compaction` components) and fix the `ChildField.java:275` cite to `:265`. Part of the same R333 refresh as §C.6; the design substance is intact. |

### C.4 `resolveInput` retirement drift (carried; R515)

R515 (Done) removed `MutationInputResolver.resolveInput`, hoisting its admission
set to `admitMutationInputFields` (2 files, live).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot composition "in `MutationInputResolver.resolveInput`"; the method is gone, the composition is live in `admitMutationInputFields`. Design intact. | **Re-anchor** the one sentence to `admitMutationInputFields`. |

### C.5 R519 `TableInputType` removal drift (carried; one item added this window as an audit correction)

R519 (Done) deleted `GraphitronType.TableInputType`, its type walk, and
`TypeBuilder.buildNonTableInputType`, moving input classification to per-consumer
resolution. (R221's deeper premise change is in §B.)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R222** dimensional-model-pivot | Spec | `:46` names `GraphitronType.TableInputType` as "a separate sibling root ... Nine consumer sites discriminate by permit identity"; `:15`/`:428` echo it. R519 executed exactly that collapse, so one of R222's three target organs (input-side classification) has **shipped**. | **Re-baseline the input-side section:** record the input-side pivot as delivered by R519 and narrow the umbrella's remaining scope to the field-side and `Unclassified*` organs. |
| **R213** input-field-rejection-attribution | Backlog | `:64` scope note: "`@table` input types route through `TableInputType` classification ... this item is plain-input-only." That routing is gone (all inputs resolve per-consumer now). Core subject and its `InputFieldResolver` cites are valid. | **Re-anchor** the one scope-note sentence; verify the `InputFieldResolver` line cite still lands. |
| **R234** jooq-embedded-and-udt-input-backings | Spec | `:15` cites `TypeBuilder.buildNonTableInputType` as the live dispatch to extend; that method is gone. The sibling arms it extends (`JooqRecordInputType` / `JooqTableRecordInputType`) are live. | **Re-anchor** the dispatch site to the current `TypeBuilder` input-classification path; the design is intact. |
| **R257** updaterows-walker-sdl-substrate | Backlog | Carried §C.4 plus §C.5 drift. `:17` calls `resolveInput` "the legacy resolver" (gone since R515); `:15`/`:19` reach the admitted column carriers "via `TableInputType.inputFields()`" (gone since R519). | **Re-anchor** both dead names: `resolveInput` to `admitMutationInputFields`, `TableInputType.inputFields()` to the per-consumer input resolution. Substance intact. |
| **R337** input-nesting-projection-classification | Backlog | **Audit correction (not new this window).** `:30` reaches an input object's own fields "via ... `TableInputType.inputFields()`" as the mechanism backing LSP hover / goto / inlay; that walk is gone (R519, before the prior audit, which did not enumerate this item). | **Re-anchor** the one mechanism cite to the per-consumer input resolution; the LSP feature scope is intact. |

### C.6 Condition + projection emitter dissolution drift (new this window; R552 + R549 slice 3.1)

R552 slices 2+3 deleted `TypeConditionsGenerator`; R549 slice 3.1 deleted
`TypeClassGenerator` + `collectRequiredProjection` and the four inline arm emitters.
Condition emission is now `render/ConditionGlueRenderer` (fed by
`plan/ConditionCommands`); projection emission is `render/ProjectionUnitRenderer`
(fed by `plan/ProjectionCommands`, minted via `plan/GeneratedUnits`). R462 also
carries this drift and is cross-noted in §C.2. R333 carries both the condition and
the projection layer plus the §C.3 `ColumnField` layer; treat its refresh as one
pass. R85's deeper hit is in §B.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Partially refreshed, inconsistently.** The crosswalk table (`:1454`) was updated this window (row 2 -> `ProjectionUnitRenderer`, rows 7/12-14 to command data), **but row 5 was not**: `:1458` still names `TypeConditionsGenerator`, `QueryConditionsGenerator` as live for `<field>Condition` with open issue "finish lift (`QueryConditionsGenerator` end)". The deeper analysis untouched: `:1596`/`:1617`/`:1672` (`TypeClassGenerator.java:216`)/`:1739` (a second seam table) name `TypeClassGenerator` as the live `$fields` emitter, and `:1945` cites `collectRequiredProjection in TypeClassGenerator`. All four classes are deleted. | **Finish the refresh.** Row 5: repoint both condition emitters to `ConditionGlueRenderer` / `ConditionCommands`, mark "finish lift" delivered by R552. Deep sections: repoint `TypeClassGenerator` / `collectRequiredProjection` to `ProjectionUnitRenderer` / `ProjectionCommands`. Do this in one pass with §C.3. Rows 3/4 (`SplitRowsMethodEmitter`) stay: that class is live. |
| **R231** emit-text-mapped-enum-fields-as-enum-type | Backlog | **New this window.** `:39` locates "the field-type-emit fork (likely in `TypeClassGenerator` or ...)"; `TypeClassGenerator` was deleted by R549 slice 3.1. The item is an investigation whose named starting point is gone. | **Re-anchor** the investigation locus to `ProjectionUnitRenderer` / the projection command family (`plan/ProjectionCommands`), where the field-type-emit fork now lives. |
| **R35** source-orientation-javadocs | Backlog | **New this window.** `:42` enumerates classes needing a class-level javadoc sweep: "`TypeFetcherGenerator`, `TypeClassGenerator`, `TypeConditionsGenerator`, ...". Two of the five names are now deleted classes. | **Re-anchor** the enumeration: drop `TypeClassGenerator` and `TypeConditionsGenerator`, optionally adding the successor renderers (`ProjectionUnitRenderer`, `ConditionGlueRenderer`) if the sweep should cover them. Low priority; the sweep scope is illustrative. |

## D. Structural: (0)

Empty. `changelog.md` carries `next-id: R559`, clearing the max allocated id
(R558). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and a
`depends-on:` sweep over all 153 item files resolves every edge, R-number and slug
alike, to a present file. The three items filed this window (R556, R557, R558) carry
well-formed front-matter and were read against the current model as born-current.

## Cross-cutting observations

1. **Correction to the prior audit's observation #4.** The 2026-07-29 audit stated
   "`TypeConditionsGenerator` survived the cutover; only `QueryConditionsGenerator`
   went", and instructed every re-anchor to keep the `TypeConditionsGenerator`
   cites. R552 slices 2+3 then deleted `TypeConditionsGenerator` this window. Any
   re-anchor in §C.2 / §C.6 must now repoint **both** condition emitters; the "keep
   `TypeConditionsGenerator`" guidance is retired.
2. **The same-window author sweep held for the authoring items and left neighbours
   stale, again.** R472 was fixed and discarded cleanly by the item doing the work
   (R549 slice 3.1). R541 and R552 swept their own bodies as they shipped. But the
   two deletions (`TypeConditionsGenerator`, `TypeClassGenerator`) left non-programme
   neighbours stale: R35 and R231 (unrelated cleanup/investigation items) and the
   deep half of R333 that this window's own partial refresh skipped. The lesson from
   the prior window stands and sharpened: a cutover that deletes a generator must
   sweep the *sibling* items (here R333, R462, R85, plus the incidental citers R35,
   R231), not only the authoring item's body.
3. **A partial refresh is its own hazard.** R333's crosswalk table was updated for
   the R549 landings while its deeper analysis tables and row 5 were not, leaving the
   single document internally inconsistent (row 2 says `ProjectionUnitRenderer`, the
   later seam table still says `TypeClassGenerator`). When an item is refreshed
   mid-programme, the refresh should either complete or be scoped explicitly, so a
   reader cannot mistake the updated region for the whole.
4. **Symbol-level re-verification pays for itself in a busy window and surfaces
   carried misses.** Re-running `grep` over every stale-live cite (rather than
   carrying the prior audit's word) caught the two new deletions and also surfaced
   three carried drifts the prior prose-window audits never enumerated: R323
   (`Split*`/`Record*`), R337 (`TableInputType`), and R333's `ColumnField` layer.
   These predate this window; they were found because the surgery forced a full
   re-probe.
5. **An In-Progress/In-Review programme's own prose is not drift.** R549 (In
   Progress), R541 and R552 (In Review) describe the pre-cutover generators in their
   own analysis and slice ledgers. These are the items doing the work; their
   retrospective and forward-looking prose is not consumer-facing stale-live, and
   they are not flagged (the same rule the prior audit applied).
6. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-30._
