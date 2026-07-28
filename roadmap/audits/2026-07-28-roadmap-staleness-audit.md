# Roadmap staleness audit: 2026-07-28

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `cc270f1`, committed 2026-07-27 22:33, audited 2026-07-28). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-27` staleness audit, which has been deleted;
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
  review; left in place. (R549 slice 7 now cites its gap 7; R546, one of its
  companion items, was discarded into R549 this window, but the analysis document
  itself is unaffected.)

`classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
inventory; its "superseded, historical" banner is intact. No action; it stays as
lineage.

## Headline: a roadmap-prose window with one author-swept code landing (R516), leaving the 18-flag set unchanged

This window was overwhelmingly roadmap authoring, not model churn. Of the 47
commits since the prior audit's baseline, all but a handful edit only `roadmap/`
prose: the R549 (`facts-and-commands`) programme and its two command proofs
(R541, R552) accounted for most of the traffic, alongside the `srp` skill
template simplification (R551) and the directive-index consolidation (R548). One
code change touched the generator model, and it repeated the R535 pattern from
last window: **the landing session swept the roadmap prose in the same window, so
it left zero drift.**

**R516 (`service-table-record-pk-only-contract`, Ready -> In Progress) landed its
first code**, narrowing the `@service` `TableRecord` key contract to primary-key
columns only. It **deleted the `TypeClassGenerator.RequiredProjection` record and
its `reservedFullRow` axis**, collapsing the projection walk's return to a plain
`List<ColumnRef>` (`TypeClassGenerator.collectRequiredProjection` survives as the
method, now returning the list; `ChildField.java` and the `SourceKey.Wrap.TableRecord`
arm are untouched). It also **added** `ServiceMethodCallError.SourcesOnPkLessParent`,
a purely additive rejection arm for a `SOURCES` batch parameter on a PK-less
parent. This is a structural removal of a named construct, yet it left **zero**
drift, for the same reason R535 did last window:

- The only active items naming the projection walk (**R333** Ready, **R549** Spec)
  cite the **live method** `collectRequiredProjection`, not the deleted
  `RequiredProjection` record. Those cites still land.
- **R549's** slice table (`:809`) explicitly frames R516 as the item that "deletes
  the `reservedFullRow` axis and the reserved-alias scheme", and records the
  2026-07-27 scope narrowing (PK-plus-node-key down to PK-only). That prose was
  written against the shipped state, not ahead of it.
- R516's **own body** was updated in the same commit. No sibling item cited the
  removed record as a live carrier.

Everything else that reached a terminal state this window was drift-free by
construction:

- **R548 (`directive-index-single-source`) closed to Done.** Its implementation
  (`c44ed95`) collapsed the duplicated directive index in the manual and guarded it
  via `DirectiveDocCoverageTest`; a docs/test consolidation that retired no
  roadmap-cited generator symbol.
- **R551 (srp hand-off template simplification) closed to Done.** It edited only
  the `srp` skill document. No codebase surface, no drift.
- **R546 was discarded, absorbed into R549.** Its sole live citer, **R462**, was
  repointed in the same commit (`c7f2cf5`) from "R546's recompile-graph section" to
  R549 slice 7 and the FCIS-distance analysis's gap 7. R549 carries the lineage
  note (`:804`, "R546 (Discarded 2026-07-27) absorbed here"). No dangling cite
  survives.

Net: **0 §A / 2 §B / 16 §C**, §D empty, **carried forward unchanged from the prior
window**. No flagged item was edited to remove its stale phrase, and no landing
this window closed a flag or opened a new one. R516's removal is the third
consecutive audit (after R51 and R535) where a same-window author sweep neutralised
what would otherwise have been a per-citer flag cost.

## Changes since the 2026-07-27 audit

**47 commits** landed between the prior audit's baseline (`60e6dc5`, 2026-07-26
23:04) and this HEAD (`cc270f1`, 2026-07-27 22:33), a roughly 23-hour window. It
was dominated by the `facts-and-commands` (R549) design programme and its command
proofs, not by model change.

**Two items ran to Done (both self-deleted):** R548 (`directive-index-single-source`)
and R551 (the `srp` hand-off template simplification). Each carries a `changelog.md`
entry.

**One item discarded:** R546 (the `MethodCommand` shape question), absorbed into
R549; the discard is recorded in `changelog.md` and in R549's slice table.

**New items filed this window** (Backlog unless noted): R549 (`facts-and-commands`,
now Spec), R550 (`quarkus-arc-removal-flake`), R551 (filed and closed to Done in
the same window), R552 (`condition-command`, now Spec, `depends-on:
[facts-and-commands]`), R553 (`srp-disqualified-session-set`). Each was read
against the current model and found current: R549/R552 cite the live
`collectRequiredProjection` method and the live command vocabulary, and R550 is a
CI-flake note with no model surface.

**Other transitions:** R516 Ready -> In Progress (then its first code landed);
R541 Ready -> Spec (reopened, "Spec review finds revisions"); R552 Backlog -> Spec.

**Terminal-state carriers not yet Done:** R333, R427 (Ready); R347, R516 (In
Progress). Zero items sit In Review at audit time.

**Board accounting.** **150 item files** today (152 `roadmap/*.md` entries minus
`README.md` and `changelog.md`), up two net from the prior audit's 148: two Done
and one discarded self-deleted (-3), offset by roughly five new items filed (one
of which, R551, was filed and closed within the window). Status distribution:
**132 Backlog, 14 Spec, 2 Ready, 2 In Progress, 0 In Review, 0 Done**. Movement
from the prior window's `131 / 11 / 5 / 1 / 0 / 0`: Backlog +1, Spec +3, Ready -3,
In Progress +1, tracking R516's promotion, R541's reopen, and the R549/R552 Spec
intake. A non-recursive `^status: Done` grep over `roadmap/*.md` returns nothing
(tombstone-free for the twentieth window running). No duplicate `id:`; max
allocated id **R553**, and `changelog.md` carries `next-id: R554`, clearing it. A
`depends-on:` sweep over all 150 item files resolves every edge, R-number and slug
alike, to a present file (the non-empty edges are all slug-based, e.g.
`condition-command` -> `[facts-and-commands]`, and all land). The board is
structurally clean.

**Net effect on flag counts: 18 flagged, 132 current.** 0 §A, 2 §B, 16 §C, 0 §D,
identical to the prior window's flag set. R516's projection-record removal, R548's
and R551's closures, and R546's discard were all drift-neutral, and no carried
flag's cited stale phrase was edited away.

## Scope and method

All **150** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded: no `R<n>`). The
model claims were re-verified at the symbol.

**The window's symbol changes, verified at the symbol:**

- **`TypeClassGenerator.RequiredProjection` record and its `reservedFullRow` axis,
  RETIRED (R516, In Progress).** No definition today; `grep` over generator main
  sources finds `reservedFullRow` nowhere. The projection walk
  `TypeClassGenerator.collectRequiredProjection` survives, now returning
  `List<ColumnRef>` (`TypeClassGenerator.java:490`). Cited by **no active item as a
  live record**: R333 and R549 cite the live method. **Drift-free.**
- **`ServiceMethodCallError.SourcesOnPkLessParent`, ADDED (R516).** A new sealed arm
  alongside `UnrecognizedSourcesType`; additive, so it retires nothing and creates
  no flag.
- **`SourceKey.Wrap.TableRecord`, LIVE and unchanged** (`SourceKey.java:54`). R516
  narrowed its key *contract* but did not touch the arm; R71's §B flag depends on
  `SourceKey` having no `Reader` interface, and it still has none (`grep` = 0).

**Retired symbols from prior windows, re-verified still retired** (the model files
were untouched this window except R516's edits above):

- **`GraphitronType.TableInputType`, RETIRED (R519).** `InputType` still permits
  only `JavaRecordInputType`, `PojoInputType`, `JooqRecordInputType`,
  `JooqTableRecordInputType` (`GraphitronType.java:358`). Drives the §C.5 / §B
  input-side flags.
- **`@tableMethod` directive and `TableMethodField` leaves, RETIRED (R535).** Still
  gone; no residual drift.
- **`ChildField.PropertyField` / `RecordField`, MERGED into `RecordReadField` (R51).**
  `ChildField` leaf anchors unchanged this window: `BatchedTableField`
  (`ChildField.java:488`), and the `ColumnBackedField` / `TableField` /
  `LookupTableField` / `BatchedLookupTableField` family at their prior lines. The §C
  re-anchor targets below still land.
- **`SourceKey`, plain record, no `Reader`, no `SourceRowsCall`** (unchanged since
  R431; re-verified for the carried R71/R180/R505 flags).
- **`MutationInputResolver.resolveInput`, RETIRED (R515); `admitMutationInputFields`
  LIVE** (re-verified for the R245/R257 flags).

**Anchors that held (re-verified):** the carried flags were re-checked against
their roadmap files. Only **R462** among the 18 was edited this window, and its
edit touched only the R546 -> R549/gap-7 cross-reference, not its stale `Split*`
variant-name phrases, which remain literally present (`grep` finds six
`SplitTableField` / `SplitLookupTableField` occurrences). Every carried flag's
cited stale phrase is still literally present, so all 18 remain accurate.

## A. Obsolete: should leave the active roadmap (0)

Empty. No item's entire premise was invalidated to the point of removal this
window. R221 remains the nearest candidate (R519 may have closed its gap), but
that is a re-derivation call at pickup, so it sits in §B rather than §A. R520
(`table-on-input-removal-housekeeping`) remains the deliberately-deferred docs/LSP
tail, not obsolete.

## B. Outdated: needs re-spec (premise or targets materially changed) (2)

Both carried from the prior window, unchanged; re-verified accurate this window.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R221** validator-walks-plain-input-unbound-fields | Backlog | R519 deleted `TableInputType` and the `validateTableInputType` type walk this item's whole diagnosis rests on, replacing it with per-consumer `collectInputFieldRejections` (`GraphitronSchemaValidator.java:387`). The item's stated gap ("only `TableInputType.inputFields()` is walked, plain inputs escape") describes an architecture that no longer exists, and the per-consumer model R519 shipped is close to the fan-out R221 proposed. `PlainInputArg.fields()` and `validateInputFieldRecursive` survive; `validateTableInputType` does not. Both stale phrases still present in the item body. | **Re-derive against the post-R519 model.** Determine whether per-consumer resolution already validates plain-input `UnboundField + @condition(override:false)` shapes. If yes, **discard** (gap closed). If a plain-input arg outside a table write-target path still escapes, **re-spec** the implementation around `collectInputFieldRejections` rather than the deleted type walk. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried, unchanged. R431 (Done) **deleted the target surface**: the body cites `SourceKey.java:124-128` / `:288` and `SourceKey.Reader.SourceRowsCall` as "the live surface" (`:14`, `:23`, `:25`), but `SourceKey` is a plain record (`:33`) with no `Reader` (re-verified: `grep` = 0 after R516's unrelated touch elsewhere). The sequencing line (`:33`) still reads "R431 ... plans to decompose"; R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model. The goal (recordN key parity, non-jOOQ record parents) is intact; this is a targeted re-derivation of the attachment surface. Drop the "R431 plans to decompose" tense. |

## C. Outdated: update references only (work valid, refs stale) (16)

Substance intact; names and line numbers drifted. All carried from the prior
window; re-verified this window (model anchor files untouched, so the re-anchor
targets below still land at their cited lines).

### C.1 `planSlug` / `SourceKey.Reader` removal drift (carried, unchanged)

R484 (Done) removed `Rejection.Deferred.planSlug`; R431 (Done) removed the
`SourceKey.Reader` interface. Deferrals now anchor by `StubKey.VariantClass`; column
reads off a parent row lift via `KeyLift.FkColumns`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R454** routine-write-result-shapes | Backlog | `:18` names the deferred shapes as "typed `Deferred`s pointing at this item's planSlug"; `planSlug` is gone. | **Re-anchor:** deferred shapes surface via `StubKey.VariantClass`, no roadmap pointer. |
| **R447** routine-chain-fetch-form-breadth | Backlog | `:18` "`planSlug` points here"; and `:24`/`:26` name `SplitLookupTableField` / `RecordTableField` as live. | **Re-anchor** both: drop the `planSlug` phrasing; repoint the two variant names to `BatchedLookupTableField` / `BatchedTableField`. |
| **R180** record-parent-column-read-helper | Spec | `:35` says "R431 ... now In Progress" (R431 is **Done**) and names `SourceKey.Reader.AccessorCall` as a live carrier (`SourceKey` has no `Reader`). Core machinery it consumes (R461 accessor surface) is live. | **Re-anchor** the `AccessorCall` carrier onto the decomposed model; fix the R431 tense to Done. |
| **R505** tenant-index-parent-row-routing | Backlog | `:21` names "a column read off the parent row (the `SourceKey.Reader` family)" as the carrier for the proposed `ParentRowBound` arm. Born stale (filed after R431). Mechanism is live via `KeyLift.FkColumns`. | **Re-anchor** the one parenthetical: per-row column read lifts via `KeyLift.FkColumns`. |

### C.2 R432 leaf-merge / R314 dissolution drift (carried)

`SplitTableField` / `RecordTableField` merged to `BatchedTableField`;
`SplitLookupTableField` / `RecordLookupTableField` to `BatchedLookupTableField`.
`SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears.
R507's guard excludes `roadmap/`, so this cluster stays an audit responsibility.
R403 remains current (its rewrite frames the dead leaf names as recoverable design
history).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** the planned assertion to `BatchedTableField` (`:488`). |
| **R462** nested-fetcher-outgoing-field-edges | Spec | `:21`, `:28`, `:39`, `:41`, `:42`, `:127` name `SplitTableField` / `SplitLookupTableField` as live; `SplitRowsMethodEmitter` in the same passages is correct. (Edited this window only to repoint its R546 cross-reference to R549; the `Split*` phrases are untouched and still present.) | **Re-anchor** the surviving `Split*` variant names; leave `SplitRowsMethodEmitter` untouched. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38`'s R305 lineage note "collapsed it into `RecordTableField`, `ChildField.java:912`" is doubly stale: the name is `BatchedTableField` (`:488`), and `:912` now lands in `PivotSlotField` territory. | **Re-anchor** that one name + cite; leave the surrounding R305/R287 history. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34`: "a keyed batch (DataLoader, as `SplitTableField` / `RecordTableField` do via `SplitRowsMethodEmitter`)". Variant names stale; emitter fine. | **Re-anchor** the two variant names. |
| **R472** nested-generated-condition-filters-never-emitted | Backlog | `:20-21`: classifier attaches `GeneratedConditionFilter` to a nested `SplitTableField` / `SplitLookupTableField` / inline `TableField` / `LookupTableField` (the latter two live). | **Re-anchor** the two `Split*` names. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`: the planned `COMPOSITE_KEY_ROW2_PATH_KEYED` case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R7** decompose-typefetchergenerator | Backlog | `:30` proposes a hypothetical decomposed emitter `SplitTableFieldEmitter`; a decomposition today would name it `BatchedTableFieldEmitter`. Illustrative "etc." naming. | **Low priority:** refresh the illustrative name at pickup. |

### C.3 R508 composite-column dissolution drift (carried)

R508 (Done) merged `ColumnField` / `CompositeColumnField` to `ColumnBackedField`.

**Now empty.** The last carrier in this sub-cluster left the roadmap when R51
closed to Done in the prior window. Retained as a header so the next audit can
re-populate it if a future landing reopens the composite-column names.

### C.4 `resolveInput` retirement drift (carried; R515)

R515 (Done) removed `MutationInputResolver.resolveInput`, hoisting its admission
set to `admitMutationInputFields`. The sibling
`EnumMappingResolver.buildLookupBindings` is still live.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot composition "in `MutationInputResolver.resolveInput`"; the method is gone, the composition is live in `admitMutationInputFields` (reads `InputField.condition()`, applies the override rule). Design intact. | **Re-anchor** the one sentence to `admitMutationInputFields`. |

### C.5 R519 `TableInputType` removal drift (carried)

R519 (Done) deleted `GraphitronType.TableInputType`, its type walk, and
`TypeBuilder.buildNonTableInputType`, moving input classification to per-consumer
resolution. Four items name the deleted symbol as live (R221's deeper premise
change is in §B; R337 was updated last window and is current).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R222** dimensional-model-pivot | Spec | `:46` names `GraphitronType.TableInputType` as "a separate sibling root ... Nine consumer sites discriminate by permit identity"; `:15`/`:428` echo it. R519 executed exactly that collapse, so one of R222's three target organs (input-side classification) has **shipped**. The field-side and failure-encoding organs survive; the umbrella is not obsolete. | **Re-baseline the input-side section:** `TableInputType` is merged into `InputType`; record the input-side pivot as delivered by R519 and narrow the umbrella's remaining scope to the field-side and `Unclassified*` organs. |
| **R213** input-field-rejection-attribution | Backlog | `:64` scope note: "`@table` input types route through `TableInputType` classification at type-build time and already attribute ... via `UnclassifiedType`; this item is plain-input-only." That routing is gone (all inputs resolve per-consumer now). Core subject (plain-input rejection loses source location) and its `InputFieldResolver` cites are valid. | **Re-anchor** the one scope-note sentence; verify the `InputFieldResolver.java:60-97` line cite still lands, given R519's per-consumer rework. |
| **R234** jooq-embedded-and-udt-input-backings | Spec | `:15` cites `TypeBuilder.buildNonTableInputType` (`:1688`, dispatch `:1702-1709`) as the live dispatch to extend; that method is gone. The sibling arms it extends (`JooqRecordInputType` / `JooqTableRecordInputType`) are live (`GraphitronType.java:399`/`:414`), and `TypeBuilder` still mints them. | **Re-anchor** the dispatch site to the current `TypeBuilder` input-classification path; the design (dedicated embeddable/UDT arms) is intact. |
| **R257** updaterows-walker-sdl-substrate | Backlog | Carried §C.4 plus §C.5 drift. `:17` calls `resolveInput` "the legacy resolver" (gone since R515); `:15`/`:19` reach the admitted column carriers "via `TableInputType.inputFields()`" (gone since R519). `buildLookupBindings` is still live; the two-places duplication the item targets survives (walker vs shared statics). | **Re-anchor** both dead names: `resolveInput` to `admitMutationInputFields`, `TableInputType.inputFields()` to the per-consumer input resolution. Substance intact. |

## D. Structural: (0)

Empty. `changelog.md` carries `next-id: R554`, clearing the max allocated id
(R553). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and a
`depends-on:` sweep over all 150 item files resolves every edge, R-number and slug
alike, to a present file. The new items filed this window carry well-formed
front-matter.

## Cross-cutting observations

1. **A third consecutive same-window author sweep neutralised a structural
   removal.** R51 (prior-prior window), R535 (prior window), and now R516 each
   deleted a named, roadmap-relevant construct, and each left zero drift because the
   landing session updated the citing prose in the same window. The pattern is no
   longer an exception worth remarking; it is becoming the house style, and the
   drift-heavy R519 last window reads as the outlier. Continue expecting the sweep.
2. **A prose-authoring window still needs the full item-body read.** The bulk of
   this window's 47 commits were R549/R541/R552 design prose. None retired a symbol,
   but the born-stale risk lives in the new bodies: R549 and R552 were filed against
   the shipped `collectRequiredProjection` method and the live command vocabulary,
   and read current. Counting commits would have missed that the risk surface was
   the five new item bodies, not the one code diff.
3. **An In-Progress item's own removals are not drift.** R516 is In Progress and its
   deletion of `RequiredProjection` is committed but not Done. The audit treats its
   partially-shipped state as expected, not stale: the flag test is whether *other*
   items cite the retired construct as live (none do), not whether R516's own body
   is ahead of or behind its code.
4. **The flag set is stable across a quiet window.** Twenty windows of tombstone-free
   accounting, an unchanged 18-flag set, and every carried stale phrase still
   literally present. The re-platforming trilogy (R431/R432/R314), the input-side
   collapse (R519), the directive removal (R535), and now the projection-record
   removal (R516) have all settled; the surviving drift is roadmap-prose-only leaf
   names guard-excluded by R507, which is an audit responsibility by design.
5. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-28._
