# Roadmap staleness audit: 2026-08-03

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `702c7f8`, committed 2026-08-02 20:50, audited 2026-08-03). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-31` staleness audit, which has been deleted;
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
  analysis, the companion to the command-layer cluster. Its gap 7 ("the recompile
  graph is a second derivation of the same relation") was the argument R549 slice
  7 shipped, closing the `CompileDependencyGraphBuilder` duplication this audit
  now finds invalidating R462 (§B). The document itself is a symbol-anchored
  snapshot with a stated re-derivation method, not a staleness review; left in
  place, not a flag target.

`classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
inventory; its "closed and historical" banner (corrected in `60b2933`) is intact.
No action; it stays as lineage.

## Headline: the facts-and-commands programme closed (R549 Done), two measurement/corpus items shipped alongside it (R25, R543 Done), and a fresh operation-relation programme (R563) opened and landed four slices that retired the `operation()` summary accessor; two Ready items and one Backlog item pick up drift from that retirement, and a prior-audit miss is corrected

The command-layer programme reached its terminus and a successor opened:

- **R549** (`facts-and-commands`) went **In Progress -> In Review -> Done**, its
  eighth and final slice landed (`dfb06d6`), its spec file deleted, its changelog
  entry appended. This is the close of the three-command-relation programme: the
  `command` / `plan` / `render` package triangle, `EmitPlan` as the core step, the
  DML reentry fold, the recompile graph as a typed projection over the plan
  (`CompileDependencyGraphBuilder`, 782 lines, deleted), and the corpus asserting
  each coordinate's launcher commitment. **Its slice-7 deletion of
  `CompileDependencyGraphBuilder` invalidates a flagged item the prior audit
  missed** (R462, now §B).
- **R543** (`corpus-asserts-fact-set`) and **R25** (`rebalance-test-pyramid`) both
  went to **Done** in the same window (R543 was R549 slice 8's corpus half; R25
  shipped JaCoCo coverage instrumentation as an opt-in `-Pcoverage` profile plus a
  published report). Neither was a flag carrier.
- **R563** (`operation-relation`, In Progress) was **filed, specced, made Ready,
  and picked up**, landing **four slices** (1, 2, 3 "the keystone", 4). The
  programme reframes operations as a relation: the new `facts/` visitor package,
  `OperationMember` / `OperationMembers` / `OperationMemberRelation`,
  `ParticipantFilterField`, `ServiceCallCarrier`, and **slice 4's retirement of
  the `operation()` summary accessor** (`OutputField.operation()`, the
  QueryField / MutationField / ChildField leaf `operation()` switches, and the
  `readOperation` / `bareFetch` / `serviceCall` statics, all `grep` = 0). The
  `Operation` sealed interface itself **survives**, re-documented as the corpus's
  summary-verb vocabulary; its arms (`Fetch`, `Facet`, `Insert`, ...) are live.

The `operation()` retirement is this window's drift driver:

- **R382** (`multitable-interface-query-orderby-lowering`, Backlog) and **R427**
  (`relevance-ranked-search`, **Ready**) both cite the retired `operation()`
  accessor as a live mechanism; **neither was flagged before**. Both enter §C.
- **R333** (Ready) picks up a new `OutputField.operation()` cite (`:341`) on top of
  its still-unfinished projection-layer refresh (§C.3/§C.6).

Net: **0 §A / 5 §B / 21 §C**, §D empty. Flag total moves to **26**, up two from the
prior window's 24. Composition: **§B gains R462** (prior-audit correction: its
premise-target `CompileDependencyGraphBuilder` was deleted); **§C loses R462 to
§B, gains R382 and R427** (the R563 slice 4 retirement); §B otherwise carries
R545, R85, R221, R71 unchanged.

## Changes since the 2026-07-31 audit

**35 commits** landed between the prior audit's HEAD (`d047eaa`, 2026-07-30 22:54)
and this HEAD (`702c7f8`, 2026-08-02 20:50), a roughly two-day window. It was
dominated by the R549 close (slice 8 + In Review -> Done), the R25 and R543 Done
gates, and the R563 operation-relation programme (filing through slice 4).

**Three items ran to Done:** R549, R543, R25, all In Progress/In Review -> Done,
spec files deleted, changelog entries appended. None was a flag carrier.

**No flagged item was discarded or resolved this window.** No `roadmap/*.md` item
file was deleted (only the three Done items' spec files and the prior staleness
audit left the tree). Of the 24 prior flags, only **R333** and **R222** files were
edited: R333's edit refreshed its condition/projection *crosswalk rows* but left
the projection *prose* stale (§C); R222's edit (`0105422`) discharged the
`findReturnTablesForInput` retirement row but is **orthogonal** to its flagged
input-side `TableInputType` drift, which stands (§C.5).

**New items filed** (both born-current, read against the current model):

- **R562** (`synthesised-connection-fields-as-coordinates`, Backlog): whether
  synthesised connection fields should be classified coordinates. Filed by the
  R549/R543 gate; anchors on the live model. R543's Done commit swept a stale
  carrier-fork sentence off its body.
- **R563** (`operation-relation`, In Progress): the operation-relation programme
  (above). Filed, specced, and four slices landed. Its own prose citing the
  symbols it retires (`OutputField.operation`, `readOperation`, `bareFetch`,
  `summaryArmOf`) is the item doing the work and is **not** drift.

**Other transitions:** R25 Spec -> Ready -> In Progress -> In Review -> Done;
R543 Spec-revise -> Ready -> In Progress -> In Review -> Done; R563 Backlog ->
Spec -> Ready -> In Progress; R222 body edit (`0105422`, non-transition).

**Terminal-state carriers not yet Done:** R333, R427, R555 (Ready); R347, R563
(In Progress). R549 left this set to Done.

**Board accounting.** **151 item files** today (153 `roadmap/*.md` entries minus
`README.md` and `changelog.md`), down one net from the prior audit's 152: two new
items filed (+2), three left the board (-3: R549, R543, R25 to Done). Status
distribution: **135 Backlog, 11 Spec, 3 Ready, 2 In Progress, 0 In Review, 0
Done**. Movement from the prior window's `134 / 13 / 3 / 2 / 0 / 0`: Backlog +1
(R562 in; R25/R543 out of Spec had been counted there, R563 cycled through), Spec
-2 (R25, R543 left Spec en route to Done), Ready flat (R563 passed through to In
Progress; R333/R427/R555 hold), In Progress flat (R549 out to Done, R563 in). A
non-recursive `^status: Done` grep over `roadmap/*.md` returns nothing
(tombstone-free for the twenty-fourth window running). No duplicate `id:`; max
allocated id **R563**, and `changelog.md` carries `next-id: R564`, clearing it. A
`depends-on:` sweep over all 151 item files resolves every edge (all non-empty
edges are slug-based) to a present file. The board is structurally clean.

**Net effect on flag counts: 26 flagged, 125 current.** 0 §A, 5 §B, 21 §C, 0 §D.

## Scope and method

All **151** `R<n>` item files were reviewed (plus the non-item placeholders
`inference-axis-coverage.adoc`, `relevance-ranked-search-howto.adoc`,
`relevance-ranked-search-oracle-howto.adoc`, and the permanent `workflow.adoc`,
all correctly excluded: no `R<n>`, and `.adoc` besides). Model claims were
re-verified at the symbol. Because this window did generator surgery (four R563
slices plus the R549 close), every stale-live cite below was checked against a
fresh `grep` of the main sources rather than carried on the prior audit's word;
that re-verification caught the R462 miss.

**The window's symbol changes, verified at the symbol:**

- **`OutputField.operation()` + the leaf `operation()` switches + `readOperation`
  / `bareFetch` / `serviceCall` statics, RETIRED (R563 slice 4, `702c7f8`).** All
  `grep` = 0 in main. The operation summary column left the model; the corpus's
  `operation:` vocabulary now reads the member-derived precedence fold
  (`DimensionTuple.summaryArmOf`). Drives §C (R333 new cite, R382, R427).
- **`Operation` (sealed interface), still LIVE, re-documented (R563).** Defined at
  `model/Operation.java:25`, re-labelled the corpus's summary-verb vocabulary; its
  arms are live (`Fetch` at `:36`, `Facet` at `:72`, the DML arms below). Items
  citing an `Operation.<Arm>` **type** are correct; only the retired `operation()`
  **accessor** and stale **line numbers** are drift (R427 cites `Operation.java:89-93`
  for `Facet`, now `:72`).
- **`OperationMember` / `OperationMembers` / `OperationMemberRelation` /
  `ParticipantFilterField` / `ServiceCallCarrier` + the `facts/` visitor package,
  new and LIVE (R563 slices 1-3).** New constructs, no roadmap drift; only R563's
  own file names them.
- **`CompileDependencyGraphBuilder`, RETIRED (R549 slice 7, landed in the prior
  window but its roadmap fallout was missed).** `grep` = 0 in main; the recompile
  graph is now a typed projection over the plan. Drives §B (R462).

**Retired symbols from prior windows, re-verified still retired (no
resurrections):**

- **`TypeConditionsGenerator` / `QueryConditionsGenerator` / `FkTargetConditionEmitter`,
  still RETIRED (R552).** All `grep` = 0. Condition emission is
  `render/ConditionGlueRenderer` + `plan/ConditionCommands`. Drives §C.6 (R333),
  §C.2 (R462).
- **`TypeClassGenerator` + `collectRequiredProjection`, still RETIRED (R549 slice
  3.1).** Both `grep` = 0. Projection emission is `render/ProjectionUnitRenderer` +
  `plan/ProjectionCommands`. Drives §C.6 (R333, R231, R35).
- **`RowsMethodBody` / `RowsMethodSkeleton`, still RETIRED; `BatchKeyField.rowsMethodName`
  model accessor still RETIRED (R549 slices 5c/5e).** All model-side `grep` = 0;
  `rowsMethodName` survives only as a generator-local (`RowsMethodCall`,
  `TypeFetcherGenerator`), so R333's model-accessor cite is stale but its
  naming-convention cites are correct. Drives §B (R545), §C.6 (R333).
- **`SplitTableField` / `SplitLookupTableField` / `RecordTableField` /
  `RecordLookupTableField`, still MERGED into `BatchedTableField` /
  `BatchedLookupTableField` (R432/R51).** All four old names `grep` = 0 as defined
  classes. `SplitRowsMethodEmitter` is **not** renamed (still 5 files) and is
  correct wherever cited. Drives §C.2.
- **`ColumnField` / `CompositeColumnField` / `CompositeColumnReferenceField`,
  still MERGED into `ColumnBackedField` (R508).** All old names `grep` = 0 as live
  classes; `ColumnBackedField` is now defined at `ChildField.java:231` (the record
  moved again this window; the prior audit's `:265` and R333's `:275` are both
  stale). Drives §C.3 (R333).
- **`GraphitronType.TableInputType` + `TypeBuilder.buildNonTableInputType`, still
  RETIRED (R519).** Both `grep` = 0. Drives §C.5.
- **`MutationInputResolver.resolveInput`, still RETIRED (R515);
  `admitMutationInputFields` LIVE.** The `MutationInputResolver` class survives
  (11 files) and a distinct `RecordBindingResolver.resolveInput` is live, so the
  6 `resolveInput` hits are not a resurrection of the retired method. Drives §C.4
  and part of §C.5 (R257).
- **`SourceKey.Reader`, still gone (R431).** The one `grep` hit is a javadoc line
  in `SourceEnvelope.java` describing the *retired* reader. Drives §C.1 and §B (R71).
- **`Rejection.Deferred.planSlug`, still gone (R484).** Drives §C.1.

## A. Obsolete: should leave the active roadmap (0)

Empty. No item's entire premise was invalidated to the point of removal this
window that has not already left by discard. R462's premise **was** invalidated
(its target class `CompileDependencyGraphBuilder` is deleted and its own stated
dissolution condition has occurred), but the safe call is re-derivation at pickup
rather than a blind discard: the missing-outgoing-edge bug it targets may or may
not survive under the plan-projected recompile graph, so it sits in §B with
discard as the likely outcome. R221 remains a similar re-derivation call. R520
(`table-on-input-removal-housekeeping`) remains the deliberately-deferred docs/LSP
tail, not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (5)

Four carried unchanged (R545, R85, R221, R71); one re-classed in this window (R462)
as its premise-target was deleted and the prior audit's §C placement is corrected.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R462** nested-fetcher-outgoing-field-edges | Spec | **Re-classed this window (prior-audit correction).** The item's title and central mechanism target `CompileDependencyGraphBuilder.addFieldEdges` (`:3`, `:13`, `:23`, `:127`, `:163`), which R549 slice 7 **deleted** (`grep` = 0); the recompile graph is now a typed projection over the plan. The body's own stated dissolution condition ("that whole class dissolves if the dependency graph becomes a projection over a core-produced command relation", `:163`) **has occurred**, and R549's changelog records "two real missing-edge classes closed". The prior audit flagged only the item's `Split*` / condition-generator ref drift (§C.2) and missed the premise inversion. | **Re-derive against the plan-projected recompile graph.** Confirm at the symbol whether the nested-fetcher outgoing per-field edge (the `FilmMeta` case) is now correctly modeled under `EmitPlan`'s fetcher-edge relation. If the missing-edge bug is closed (likely), **discard** and record the close in the changelog. If a residue survives, **re-spec** onto the plan-projection; the `Split*` ref drift folds into that re-spec. |
| **R545** model-free-of-emit-vocabulary | Backlog | Carried, unchanged. R549 slice 5c deleted `RowsMethodBody` / `RowsMethodSkeleton` outright (`grep` = 0 each), so the item's whole `RowsMethodBody` diagnosis and its **second deliverable** ("`RowsMethodBody` moves to `generators/`") are gone. The **first deliverable survives**: `TypeName`/`ClassName` are still model-pervasive (`ClassName` in 100+ main files). | **Re-spec.** Drop the `RowsMethodBody` diagnosis and the second deliverable entirely. Keep and re-baseline the first deliverable (`JavaTypeRef` replacing `TypeName`/`ClassName`, emit-computing helpers to the shell) against the post-R549 model. |
| **R85** helper-emission-non-fetcher-hosts | Backlog | Carried, unchanged. Both classes the plan names as edit targets are deleted: `QueryConditionsGenerator` (gone R552) and `TypeClassGenerator` (gone R549 slice 3.1). The `QueryConditionsGenerator.java:NNN` line cites are all dead; condition emission moved to `render/ConditionGlueRenderer`, projection to `render/ProjectionUnitRenderer`. | **Re-derive against the new `render/` layer.** Determine whether `ConditionGlueRenderer` and `ProjectionUnitRenderer` still exhibit the duplicated helper-emission problem the item targets; if so, re-spec onto them. Every `QueryConditionsGenerator.java:NNN` cite is dead and must be dropped. |
| **R221** validator-walks-plain-input-unbound-fields | Backlog | Carried, unchanged. R519 deleted `TableInputType` and the `validateTableInputType` type walk the item's whole diagnosis rests on, replacing it with per-consumer `collectInputFieldRejections` (live). The stated gap describes an architecture that no longer exists. | **Re-derive against the post-R519 model.** If per-consumer resolution already validates plain-input `UnboundField + @condition(override:false)` shapes, **discard** (gap closed). If a plain-input arg outside a table write-target path still escapes, **re-spec** around `collectInputFieldRejections`. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried, unchanged. R431 (Done) deleted the target surface: the body cites `SourceKey.java` line ranges and `SourceKey.Reader.SourceRowsCall` as "the live surface", but `SourceKey` is a plain record with no `Reader`. The sequencing line still reads "R431 ... plans to decompose"; R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model (`KeyLift` live). The goal (recordN key parity, non-jOOQ record parents) is intact; drop the "R431 plans to decompose" tense. |

## C. Outdated: update references only (work valid, refs stale) (21)

Substance intact; names and line numbers drifted. Nineteen carried from the prior
window (re-verified at the symbol; every driving symbol untouched or deepened, none
resurrected); R462 left this section for §B; **R382 and R427 entered** on the R563
slice 4 `operation()` retirement.

### C.0 `operation()` summary-accessor retirement drift (new this window; R563 slice 4)

R563 slice 4 deleted `OutputField.operation()`, the leaf `operation()` switches,
and the `readOperation`/`bareFetch`/`serviceCall` statics (all `grep` = 0). The
`Operation` sealed interface and its arms survive; only the accessor and stale
line numbers drift.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R382** multitable-interface-query-orderby-lowering | Backlog | `:19` "`operation()` hardcodes `new OrderBySpec.None()` for both arms" cites the retired leaf accessor as the live mechanism denying interface/union queries a user ordering. The subject (lower orderBy onto multitable-interface/union) is intact. | **Re-anchor** the `operation()` mechanism cite to where the hardcoded `OrderBySpec.None` now lives (the member/fact layer or `MultiTablePolymorphicEmitter`); verify the ordering gap still reproduces post-R563. |
| **R427** relevance-ranked-search | Ready | `:335` "`operation()` stays `Fetch`" cites the retired accessor; `:340-341` cites `Operation.Facet` at `Operation.java:89-93` (the arm is live but now at `:72`). The typeahead/relevance design is intact. | **Re-anchor:** restate the "stays `Fetch`" design point against the member-derived summary fold (`DimensionTuple.summaryArmOf`); fix the `Operation.Facet` line cite to the current `:72`. A Ready item carrying newly-created drift; refresh before pickup. |

### C.1 `planSlug` / `SourceKey.Reader` removal drift (carried, unchanged)

R484 (Done) removed `Rejection.Deferred.planSlug`; R431 (Done) removed the
`SourceKey.Reader` interface. Deferrals now anchor by `StubKey.VariantClass`; column
reads off a parent row lift via `KeyLift.FkColumns`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R454** routine-write-result-shapes | Backlog | `:18` names the deferred shapes as "typed `Deferred`s pointing at this item's planSlug"; `planSlug` is gone. | **Re-anchor:** deferred shapes surface via `StubKey.VariantClass`, no roadmap pointer. |
| **R447** routine-chain-fetch-form-breadth | Backlog | `:18` "`planSlug` points here"; `:24`/`:26` name `SplitLookupTableField` / `RecordTableField` as live. | **Re-anchor** both: drop the `planSlug` phrasing; repoint the two variant names to `BatchedLookupTableField` / `BatchedTableField`. |
| **R180** record-parent-column-read-helper | Spec | `:35` says "R431 ... now In Progress" (R431 is **Done**) and names `SourceKey.Reader.AccessorCall` as a live carrier. | **Re-anchor** the `AccessorCall` carrier onto the decomposed model; fix the R431 tense to Done. |
| **R505** tenant-index-parent-row-routing | Backlog | `:21` names "a column read off the parent row (the `SourceKey.Reader` family)" as the carrier for the proposed `ParentRowBound` arm. Mechanism is live via `KeyLift.FkColumns`. | **Re-anchor** the one parenthetical: per-row column read lifts via `KeyLift.FkColumns`. |

### C.2 R432 leaf-merge drift (carried, unchanged)

`SplitTableField` / `RecordTableField` merged to `BatchedTableField`;
`SplitLookupTableField` / `RecordLookupTableField` to `BatchedLookupTableField`.
`SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears.
R507's guard excludes `roadmap/`, so this cluster stays an audit responsibility.
(R462 carried the condition-emitter half of this cluster but has moved to §B, where
its ref drift folds into the re-spec.)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** the planned assertion to `BatchedTableField`. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38`'s R305 lineage note "collapsed it into `RecordTableField`, `ChildField.java:912`" is doubly stale: the name is `BatchedTableField`, and `:912` no longer lands there. | **Re-anchor** that one name + cite; leave the surrounding R305/R287 history. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34`: "a keyed batch (DataLoader, as `SplitTableField` / `RecordTableField` do via `SplitRowsMethodEmitter`)". Variant names stale; emitter fine. | **Re-anchor** the two variant names. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`: the planned `COMPOSITE_KEY_ROW2_PATH_KEYED` case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R7** decompose-typefetchergenerator | Backlog | `:30` proposes a hypothetical decomposed emitter `SplitTableFieldEmitter`; a decomposition today would name it `BatchedTableFieldEmitter`. Illustrative "etc." naming. | **Low priority:** refresh the illustrative name at pickup. |
| **R323** nestingfield-multiparent-batchkey-leaves | Backlog | `:18` lists `SplitTableField`, `SplitLookupTableField`, `RecordTableField`, `RecordLookupTableField` as the live classification options; `:30` names two of them (and "the lookup twins") as the leaves to fold. All four merged into `Batched*`. | **Re-anchor** the four variant names to `BatchedTableField` / `BatchedLookupTableField`. Substance (multi-parent batch-key leaves) intact. |

### C.3 R508 composite-column dissolution drift (carried; deepened by the moved `ColumnBackedField` line)

R508 (Done) merged `ColumnField` / `CompositeColumnField` /
`CompositeColumnReferenceField` into `ColumnBackedField`, now defined at
`ChildField.java:231` (moved from `:265` this window).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | The design doc names the retired carriers as live across many prose regions: `:570`, `:694`, `:706`, `:727`, `:747-748` ("`ColumnField` (`ChildField.java:275`)"), `:798-799`, `:1112`, `:1140`, `:1145`, `:2009`. Only `:1554-1560` self-corrects (annotated "Shipped by R508"). | **Re-anchor** the carrier names to `ColumnBackedField` (and its `columns`/`compaction` components) and fix the `ChildField.java:275` cite to the current `:231`. Part of the same R333 refresh as §C.6. |

### C.4 `resolveInput` retirement drift (carried; R515)

R515 (Done) removed `MutationInputResolver.resolveInput`, hoisting its admission
set to `admitMutationInputFields` (live).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot composition "in `MutationInputResolver.resolveInput`"; the method is gone, the composition is live in `admitMutationInputFields`. Design intact. | **Re-anchor** the one sentence to `admitMutationInputFields`. |

### C.5 R519 `TableInputType` removal drift (carried; R222 touched but flag stands)

R519 (Done) deleted `GraphitronType.TableInputType`, its type walk, and
`TypeBuilder.buildNonTableInputType`, moving input classification to per-consumer
resolution. (R221's deeper premise change is in §B.)

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R222** dimensional-model-pivot | Spec | `:46` still names `GraphitronType.TableInputType` as "a separate sibling root ... Nine consumer sites discriminate by permit identity"; `:15`/`:428` echo it. R519 executed exactly that collapse, so one of R222's three target organs (input-side classification) has **shipped**. The `0105422` edit discharged the `findReturnTablesForInput` row but is orthogonal to this drift. | **Re-baseline the input-side section:** record the input-side pivot as delivered by R519 and narrow the umbrella's remaining scope to the field-side and `Unclassified*` organs. |
| **R213** input-field-rejection-attribution | Backlog | `:64` scope note: "`@table` input types route through `TableInputType` classification ... this item is plain-input-only." That routing is gone (all inputs resolve per-consumer now). Core subject and its `InputFieldResolver` cites are valid. | **Re-anchor** the one scope-note sentence; verify the `InputFieldResolver` line cite still lands. |
| **R234** jooq-embedded-and-udt-input-backings | Spec | `:15` cites `TypeBuilder.buildNonTableInputType` as the live dispatch to extend; that method is gone. The sibling arms it extends (`JooqRecordInputType` / `JooqTableRecordInputType`) are live. | **Re-anchor** the dispatch site to the current `TypeBuilder` input-classification path; the design is intact. |
| **R257** updaterows-walker-sdl-substrate | Backlog | Carried §C.4 plus §C.5 drift. `:17` calls `resolveInput` "the legacy resolver" (gone since R515); `:15`/`:19` reach the admitted column carriers "via `TableInputType.inputFields()`" (gone since R519). | **Re-anchor** both dead names: `resolveInput` to `admitMutationInputFields`, `TableInputType.inputFields()` to the per-consumer input resolution. Substance intact. |
| **R337** input-nesting-projection-classification | Backlog | `:30` reaches an input object's own fields "via ... `TableInputType.inputFields()`" as the mechanism backing LSP hover / goto / inlay; that walk is gone (R519). | **Re-anchor** the one mechanism cite to the per-consumer input resolution; the LSP feature scope is intact. |

### C.6 Condition + projection emitter dissolution drift (carried; R552 + R549 slice 3.1)

R552 deleted `TypeConditionsGenerator`; R549 slice 3.1 deleted `TypeClassGenerator`
+ `collectRequiredProjection`; R549 slice 7 deleted `ParentProjectionContainmentCheck`
and the `methodgraph` package. Condition emission is `render/ConditionGlueRenderer`
(fed by `plan/ConditionCommands`); projection emission is `render/ProjectionUnitRenderer`
(fed by `plan/ProjectionCommands`).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R333** coordinate-lowers-to-datafetcher-queryparts | Ready | **Refresh is still incomplete.** The member-to-seam **crosswalk rows are current** (projection `:1460` → `ProjectionUnitRenderer`, condition `:1463` → `ConditionGlueRenderer`), but the **prose and baseline table are not**: `:529` names `TypeConditionsGenerator`; `:1605`/`:1626`/`:1628`/`:1681`/`:1749` (baseline table Projection row)/`:1800`/`:1963`/`:1987` name `TypeClassGenerator` / `collectRequiredProjection` as live; `:1674` cites the retired `BatchKeyField.rowsMethodName()` model accessor (`model/BatchKeyField.java:42`); and **new this window** `:341` cites `OutputField.operation()` as a live harness input, plus `ParentProjectionContainmentCheck` and `methodgraph` cites (both `grep` = 0). | **Finish the refresh.** Repoint `TypeClassGenerator` / `collectRequiredProjection` to `ProjectionUnitRenderer` / `ProjectionCommands`; drop the `:529` `TypeConditionsGenerator` prose; drop the dead `BatchKeyField.rowsMethodName` and `OutputField.operation()` cites and the `ParentProjectionContainmentCheck` / `methodgraph` cites. Do this in one pass with §C.0 and §C.3. Rows citing `SplitRowsMethodEmitter` stay: that class is live. |
| **R231** emit-text-mapped-enum-fields-as-enum-type | Backlog | `:39` locates "the field-type-emit fork (likely in `TypeClassGenerator` or ...)"; `TypeClassGenerator` was deleted by R549 slice 3.1. The item is an investigation whose named starting point is gone. | **Re-anchor** the investigation locus to `ProjectionUnitRenderer` / the projection command family (`plan/ProjectionCommands`). |
| **R35** source-orientation-javadocs | Backlog | `:42` enumerates classes needing a class-level javadoc sweep: "`TypeFetcherGenerator`, `TypeClassGenerator`, `TypeConditionsGenerator`, ...". Two of the five names are now deleted classes. | **Re-anchor** the enumeration: drop `TypeClassGenerator` and `TypeConditionsGenerator`, optionally adding the successor renderers. Low priority; the sweep scope is illustrative. |

## D. Structural: (0)

Empty. `changelog.md` carries `next-id: R564`, clearing the max allocated id
(R563). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and a
`depends-on:` sweep over all 151 item files resolves every edge, R-number and slug
alike, to a present file. The two items filed this window (R562, R563) carry
well-formed front-matter and were read against the current model as born-current.

## Cross-cutting observations

1. **Symbol-level re-verification caught a prior-audit miss.** R462's premise-target
   `CompileDependencyGraphBuilder` was deleted by R549 slice 7, which landed *inside
   the prior audit's window*, yet the prior audit flagged only R462's `Split*` /
   condition-generator ref drift and left it in §C. Re-running `grep` over every
   stale-live cite this window (rather than carrying the prior audit's classification)
   surfaced that the item's title, central mechanism, and *its own stated dissolution
   condition* were invalidated. The lesson: a busy window's own retirements must be
   swept against sibling items, and the next audit must re-classify, not just
   re-verify, the carried flags.

2. **A retirement can invert a Ready item's premise-adjacent references.** R427 is
   the sharp case: a **Ready** item (past its Spec gate, awaiting implementation)
   picked up drift *this window* because R563 slice 4 deleted the `operation()`
   accessor its Spec leaned on. This is the second consecutive window a Ready
   terminal-carrier absorbed fresh drift (R333 last window, R427 now). A cutover
   that deletes a widely-cited accessor should sweep the Ready set specifically:
   those items are closest to pickup and least tolerant of a stale mechanism cite.

3. **R333's mid-programme refresh remains partial and now spans three drivers.** The
   R552 gate refreshed its condition crosswalk row, the R549 gate its naming-regime
   column, but the projection prose, the baseline seam table's Projection row, and now
   the `operation()` accessor cite all lag. The item is internally inconsistent (its
   crosswalk says `ProjectionUnitRenderer`, its prose says `TypeClassGenerator`). It
   should be refreshed in a single pass covering §C.0, §C.3, and §C.6 before it is
   picked up, not incrementally at each neighbouring gate.

4. **A programme's own prose is not drift.** R563 (In Progress) describes its own
   subject matter, including the `operation()` accessor it retires and the members it
   mints; R347 (`lsp-structural-consolidation`, In Progress) likewise. These are the
   items doing the work; their prose is not consumer-facing stale-live and they are
   not flagged (the rule prior audits applied).

5. **The `Operation` seal survived a retirement its accessor did not.** The tell that
   `operation()` is §C (refs) and not §B (premise) is that `Operation` and its arms are
   still live: an item citing `Operation.Facet` the **type** is correct; only the
   retired `operation()` **accessor** and stale line numbers drift. Contrast R462 in
   §B, where the whole `CompileDependencyGraphBuilder` class is gone and the action
   depends on it existing.

6. **`inference-axis-coverage.adoc`** and R25's new `source-coverage.adoc` (never
   committed; CI-regenerated per trunk push) remain intentional non-item artifacts,
   correctly excluded. The two `relevance-ranked-search-*-howto.adoc` companions to
   R427 are `.adoc`, not `R<n>` items, and are excluded too.

---

_Review date: 2026-08-03._
