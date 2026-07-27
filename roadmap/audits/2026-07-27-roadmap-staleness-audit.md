# Roadmap staleness audit: 2026-07-27

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `60e6dc5`, committed 2026-07-26 23:04, audited 2026-07-27). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-24` staleness audit, which has been deleted;
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
  analysis, a companion to R333 and the R543/R545/R546 command-layer cluster. It
  is a symbol-anchored snapshot with a stated re-derivation method, not a
  staleness review; left in place.

`classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
inventory; its "superseded, historical" banner is intact. No action; it stays as
lineage.

## Headline: R519 deleted `TableInputType`, leaving five items that name it as live; R535 removed `@tableMethod` cleanly because every citing item was updated in the same window

Two structural removals landed this window. They diverged in how much drift they
left behind, and the difference is instructive.

**R519 (`table-on-input-removal`, Done) deleted `GraphitronType.TableInputType`
outright**, collapsing eager type-level input classification into per-consumer
resolution. `InputType` now permits only `JavaRecordInputType`, `PojoInputType`,
`JooqRecordInputType`, `JooqTableRecordInputType` (`GraphitronType.java:358`);
`validateTableInputType`, `findReturnTablesForInput`, and
`TypeBuilder.buildNonTableInputType` are gone. Five surviving items name the
deleted symbol as a live mechanism, so this is the window's drift source:

- **R221** (`validator-walks-plain-input-unbound-fields`, Backlog) is the sharpest
  case: its entire premise is that the validator "only walks
  `TableInputType.inputFields()` via `validateTableInputType`", and plain inputs
  escape. R519 replaced that type walk with the per-consumer
  `collectInputFieldRejections` drain (`GraphitronSchemaValidator.java:387`), which
  is essentially the fan-out R221 proposed. Its premise is materially changed and
  may already be closed. **New §B** (re-derive whether the gap survives; discard if
  R519 closed it).
- **R222** (`dimensional-model-pivot`, Spec) names `TableInputType` as "a separate
  sibling root" with "nine consumer sites" to collapse (`:46`). R519 executed that
  collapse: one of the umbrella's three target organs shipped. **New §C.5**
  (re-baseline the input-side section).
- **R213**, **R234**, **R257** each carry a one-clause live cite of the dead symbol.
  **New §C.5.**

**R535 (`remove-tablemethod-directive`, Done) removed the `@tableMethod`
directive, both `TableMethodField` leaves, and the machinery**, a comparable
removal, yet it left **zero** drift. Every item that named the directive was
updated in the same window: R403 (`reintroduce-tablemethod-docs`) was rewritten to
frame the dead names as recoverable design history, R240 gained a 2026-07-25 scope
note, R333 records the residue as "discharged by removal", and R11's scope was
pruned. **R403's prior §C.2 flag is thereby resolved and dropped.**

Everything else stayed current:

- **R46 (`multi-tenant-fanout`) closed to Done drift-free**, as the prior audit
  predicted: additive-only, it retired no cited symbol.
- **R51 (`propertyfield-recordfield-nullable-column`, Done) merged `PropertyField`
  and `RecordField` into `ChildField.RecordReadField`.** This removed two leaf
  names, but no surviving item cites the bare retired leaves (the only `RecordField`
  citations are compound names, `MutationBulkDmlRecordField`,
  `QueryServiceRecordField`, still live). R51's own prior §C.3 flag closed with the
  item. Drift-free.
- **The new items born this window name only live symbols** (see §A and
  cross-cutting #3).

Net: **0 §A / 2 §B / 16 §C**, §D empty. Two removals of comparable size, one
drift-heavy (R519), one drift-free (R535); the difference was same-window author
propagation.

## Changes since the 2026-07-24 audit

**124 commits** landed between the prior audit's baseline (`7d62453`, 2026-07-24
01:05) and this HEAD (`60e6dc5`, 2026-07-26 23:04). The window was dominated by
terminal closures and the input/directive-removal series.

**Thirteen items ran to Done (all self-deleted):** R46 (`multi-tenant-fanout`), R97
(`consumer-derived-input-tables`, the `@table`-on-input deprecation Phase 2+2b),
R335 (`walk-classifies-input-surface`), R417 (`sakila-readme-app-section` drift),
R494 (`schema-directive-registry-generator-only-sync`), R519
(`table-on-input-removal`, deletes `TableInputType`), R524/R525/R526/R527 (the
javadoc-verbosity/comment-correction sweep and its spin-outs), R535
(`remove-tablemethod-directive`), R542 (`agent-onboarding-surface`), and R51
(`propertyfield-recordfield` merge). Each carries a `changelog.md` entry.

**One item discarded:** R539 (`classified-corpus verdict-row retirement`), found
already-finished; the discard is recorded in `changelog.md`.

**New items filed this window** (Backlog unless noted): R520
(`table-on-input-removal-housekeeping`, R519's tail), R521, R522, R523, R528, R530,
R531, R532, R533, R534, R536, R537, R538 (`onnx-embedder-nondeterminism`), R540
(`upsert-docs-match-dispatch-refusal`), R541 (`root-query-unit-seam`, now Ready),
R543/R544/R545/R546 (the R333 command-layer cluster and its FCIS-distance
companion), R547 (`pom-comment-roadmap-citations`), R548
(`directive-index-single-source`, now Ready). All read against the current model
and found current.

**Terminal-state carriers not yet Done:** R333, R427, R516, R541, R548 (Ready);
R347 (In Progress). Zero items sit In Review at audit time.

**Board accounting.** **148 item files** today (150 `roadmap/*.md` entries minus
`README.md` and `changelog.md`), up sixteen from the prior audit's 132: thirteen
Done and one discarded self-deleted (-14, offset by files filed-and-closed within
the same window), and roughly twenty new items filed net. Status distribution:
**131 Backlog, 11 Spec, 5 Ready, 1 In Progress, 0 In Review, 0 Done**. A
non-recursive `^status: Done` grep over `roadmap/*.md` returns nothing
(tombstone-free for the nineteenth window running). No duplicate `id:`; max
allocated id **R548**, and `changelog.md` carries `next-id: R549`, clearing it. A
`depends-on:` sweep over all 148 item files resolves every edge, R-number and slug
alike, to a present file. The board is structurally clean.

**Net effect on flag counts: 18 flagged, 130 current.** 0 §A, 2 §B, 16 §C, 0 §D.
Up from the prior window's 16 (1 §B + 15 §C): +1 §B (R221), +4 §C (R213, R222,
R234, and R257's expansion) offset by -2 §C (R403 resolved, R51 closed).

## Scope and method

All **148** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded: no `R<n>`). The
model claims were re-verified at the symbol.

**The window's symbol changes, verified at the symbol:**

- **`GraphitronType.TableInputType`, RETIRED (R519).** No definition today;
  `InputType` permits only `JavaRecordInputType`, `PojoInputType`,
  `JooqRecordInputType`, `JooqTableRecordInputType` (`GraphitronType.java:358`).
  `validateTableInputType`, `findReturnTablesForInput`, and
  `TypeBuilder.buildNonTableInputType` are gone. Input-field validation now drains
  the per-consumer `GraphitronSchemaValidator.collectInputFieldRejections` /
  `validateInputFieldRecursive` (`:387`/`:400`), which is live. **Drives the five
  §C.5 / §B input-side flags.**
- **`@tableMethod` directive, `TableMethodField` leaves, `TableExpr.MethodCall`
  arm, `FieldClassification.TableMethod`, RETIRED (R535).** Gone from
  `directives.graphqls` and the model. Every citing roadmap item was updated in the
  same window; **no residual drift.**
- **`ChildField.PropertyField` / `ChildField.RecordField`, MERGED into
  `ChildField.RecordReadField` (R51)** (`ChildField.java:1064`). No surviving item
  cites the bare retired leaves.
- **`ChildField` leaf anchors shifted** (R51/R519 churn): `ColumnBackedField`
  (`:265`), `TableField` (`:422`), `BatchedTableField` (`:488`), `LookupTableField`
  (`:589`), `BatchedLookupTableField` (`:615`). The §C re-anchor targets below use
  the current lines.
- **`SourceKey`, plain record (`:33`), no `Reader` interface, no `SourceRowsCall`**
  (unchanged since R431; re-verified for the carried R71/R180/R505 flags).
- **`InputFieldResolver` / `InputFieldResolution`, LIVE** (R213/R337 core cites
  survive at the file level).

**Anchors that held (re-verified):** the carried flags were re-checked against
their roadmap files. Five of the carried-flag files were edited this window (R71,
R462, R288, R505 link/prose touches; R403 a full rewrite), but only R403's edit
removed its stale phrase. Every other carried flag's cited stale phrase is still
literally present, so every carried flag except R403 (resolved) and R51 (closed)
remains accurate.

## A. Obsolete: should leave the active roadmap (0)

Empty. No item's entire premise was invalidated to the point of removal this
window. R221 is the nearest candidate (R519 may have closed its gap), but that is
a re-derivation call at pickup, so it sits in §B rather than §A: if the reviewer
confirms `collectInputFieldRejections` already covers plain-input
`@condition(override:false)` shapes, R221 becomes a discard, but that verdict is
not yet established. R520 (`table-on-input-removal-housekeeping`) is **not**
obsolete despite R519 landing: it is the deliberately-deferred docs/LSP tail, now
unblocked.

## B. Outdated: needs re-spec (premise or targets materially changed) (2)

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R221** validator-walks-plain-input-unbound-fields | Backlog | R519 deleted `TableInputType` and the `validateTableInputType` type walk this item's whole diagnosis rests on, replacing it with per-consumer `collectInputFieldRejections` (`GraphitronSchemaValidator.java:387`). The item's stated gap ("only `TableInputType.inputFields()` is walked, plain inputs escape") describes an architecture that no longer exists, and the per-consumer model R519 shipped is close to the fan-out R221 proposed. `PlainInputArg.fields()` and `validateInputFieldRecursive` survive; `validateTableInputType` does not. | **Re-derive against the post-R519 model.** Determine whether per-consumer resolution already validates plain-input `UnboundField + @condition(override:false)` shapes. If yes, **discard** (gap closed). If a plain-input arg outside a table write-target path still escapes, **re-spec** the implementation around `collectInputFieldRejections` rather than the deleted type walk. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | Carried from last window, unchanged. R431 (Done) **deleted the target surface**: the body cites `SourceKey.java:124-128` / `:288` and `SourceKey.Reader.SourceRowsCall` as "the live surface" (`:14`, `:23`, `:25`), but `SourceKey` is a plain record (`:33`) with no `Reader`. The sequencing line (`:33`) still reads "R431 ... plans to decompose"; R431 is Done. | **Re-spec the current-state / approach section** against the decomposed `KeyLift` / `LifterRef` / `Wrap` model. The goal (recordN key parity, non-jOOQ record parents) is intact; this is a targeted re-derivation of the attachment surface. Drop the "R431 plans to decompose" tense. |

## C. Outdated: update references only (work valid, refs stale) (16)

Substance intact; names and line numbers drifted.

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

### C.2 R432 leaf-merge / R314 dissolution drift (carried; R403 dropped, resolved)

`SplitTableField` / `RecordTableField` merged to `BatchedTableField`;
`SplitLookupTableField` / `RecordLookupTableField` to `BatchedLookupTableField`.
`SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears.
R507's guard excludes `roadmap/`, so this cluster stays an audit responsibility.
**R403 left this cluster this window:** its full rewrite now frames the dead leaf
names as recoverable design history, so it is current.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R109** list-valued-external-field-multiset | Spec | `:51`'s planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** the planned assertion to `BatchedTableField` (`:488`). |
| **R462** nested-fetcher-outgoing-field-edges | Spec | `:21`, `:28`, `:39`, `:41`, `:42`, `:127` name `SplitTableField` / `SplitLookupTableField` as live; `SplitRowsMethodEmitter` in the same passages is correct. | **Re-anchor** the surviving `Split*` variant names; leave `SplitRowsMethodEmitter` untouched. |
| **R242** dml-payload-positional-alignment | Spec | `:37-38`'s R305 lineage note "collapsed it into `RecordTableField`, `ChildField.java:912`" is doubly stale: the name is `BatchedTableField` (`:488`), and `:912` now lands in `PivotSlotField` territory. | **Re-anchor** that one name + cite; leave the surrounding R305/R287 history. |
| **R288** inline-interface-and-tablemethod-children | Backlog | `:33-34`: "a keyed batch (DataLoader, as `SplitTableField` / `RecordTableField` do via `SplitRowsMethodEmitter`)". Variant names stale; emitter fine. | **Re-anchor** the two variant names. |
| **R472** nested-generated-condition-filters-never-emitted | Backlog | `:20-21`: classifier attaches `GeneratedConditionFilter` to a nested `SplitTableField` / `SplitLookupTableField` / inline `TableField` / `LookupTableField` (the latter two live). | **Re-anchor** the two `Split*` names. |
| **R116** composite-key-row2-source-row-coverage | Backlog | `:15`: the planned `COMPOSITE_KEY_ROW2_PATH_KEYED` case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R7** decompose-typefetchergenerator | Backlog | `:30` proposes a hypothetical decomposed emitter `SplitTableFieldEmitter`; a decomposition today would name it `BatchedTableFieldEmitter`. Illustrative "etc." naming. | **Low priority:** refresh the illustrative name at pickup. |

### C.3 R508 composite-column dissolution drift (carried)

R508 (Done) merged `ColumnField` / `CompositeColumnField` to `ColumnBackedField`.

**Now empty.** The last carrier in this sub-cluster was R51's own lineage clause,
which left the roadmap when R51 closed to Done this window. No carried item remains
here; the surviving leaf-name drift is tracked in §C.2. Retained as a header so the
next audit can re-populate it if a future landing reopens the composite-column
names.

### C.4 `resolveInput` retirement drift (carried; R515)

R515 (Done, prior window) removed `MutationInputResolver.resolveInput`, hoisting
its admission set to `admitMutationInputFields`. The sibling
`EnumMappingResolver.buildLookupBindings` is still live.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R245** wire-condition-emit-on-mutations | Backlog | `:76` locates the `@condition` slot composition "in `MutationInputResolver.resolveInput`"; the method is gone, the composition is live in `admitMutationInputFields` (reads `InputField.condition()`, applies the override rule). Design intact. | **Re-anchor** the one sentence to `admitMutationInputFields`. |

### C.5 R519 `TableInputType` removal drift (NEW this window)

R519 (Done) deleted `GraphitronType.TableInputType`, its type walk, and
`TypeBuilder.buildNonTableInputType`, moving input classification to per-consumer
resolution. Four items name the deleted symbol as live (R221's deeper premise
change is in §B; R337 was updated this window and is current).

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R222** dimensional-model-pivot | Spec | `:46` names `GraphitronType.TableInputType` as "a separate sibling root ... Nine consumer sites discriminate by permit identity"; `:15`/`:428` echo it. R519 executed exactly that collapse, so one of R222's three target organs (input-side classification) has **shipped**. The field-side and failure-encoding organs survive; the umbrella is not obsolete. | **Re-baseline the input-side section:** `TableInputType` is merged into `InputType`; record the input-side pivot as delivered by R519 and narrow the umbrella's remaining scope to the field-side and `Unclassified*` organs. |
| **R213** input-field-rejection-attribution | Backlog | `:64` scope note: "`@table` input types route through `TableInputType` classification at type-build time and already attribute ... via `UnclassifiedType`; this item is plain-input-only." That routing is gone (all inputs resolve per-consumer now). Core subject (plain-input rejection loses source location) and its `InputFieldResolver` cites are valid. | **Re-anchor** the one scope-note sentence; verify the `InputFieldResolver.java:60-97` line cite still lands, given R519's per-consumer rework. |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | `:15` cites `TypeBuilder.buildNonTableInputType` (`:1688`, dispatch `:1702-1709`) as the live dispatch to extend; that method is gone. The sibling arms it extends (`JooqRecordInputType` / `JooqTableRecordInputType`) are live (`GraphitronType.java:399`/`:414`), and `TypeBuilder` still mints them (`:1679`/`:1682`). | **Re-anchor** the dispatch site to the current `TypeBuilder` input-classification path; the design (dedicated embeddable/UDT arms) is intact. |
| **R257** updaterows-walker-sdl-substrate | Backlog | Carried §C.4 plus new drift. `:17` calls `resolveInput` "the legacy resolver" (gone since R515); `:15`/`:19` reach the admitted column carriers "via `TableInputType.inputFields()`" (gone since R519). `buildLookupBindings` is still live; the two-places duplication the item targets survives (walker vs shared statics). | **Re-anchor** both dead names: `resolveInput` to `admitMutationInputFields`, `TableInputType.inputFields()` to the per-consumer input resolution. Substance intact. |

## D. Structural: (0)

Empty. `changelog.md` carries `next-id: R549`, clearing the max allocated id
(R548). No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and a
`depends-on:` sweep over all 148 item files resolves every edge, R-number and slug
alike, to a present file. The new items filed this window carry well-formed
front-matter.

## Cross-cutting observations

1. **Two comparable removals, opposite drift, and the difference was author
   propagation.** R519 (delete `TableInputType`) and R535 (remove `@tableMethod`)
   were both structural deletions of a named, roadmap-cited mechanism. R535 left
   zero drift because every citing item (R11, R240, R333, R403) was updated in the
   same window; R519 left five flags because its citers were not. The lesson the
   prior audits keep drawing holds and sharpens: a removal costs a flag per citing
   item **unless** the landing session sweeps the roadmap prose too. R535 shows the
   sweep is achievable within the same window.
2. **A rewrite that reframes a dead symbol as history is current; a passing mention
   that leans on it as live is stale.** R403 named `@tableMethod` and its leaves,
   yet is current, because its rewrite frames them as removed and git-recoverable.
   R213/R234/R257 name `TableInputType` as live ambient mechanism and are stale. The
   flag line is the framing, not the mention.
3. **Born-stale vetting at entry held for a large intake.** Roughly twenty items
   were filed this window (R520-R548 span); each was read against the current model
   and found current, including R520 (correctly framed as R519's deferred tail) and
   R540/R548 (filed after the input-directive series, so born against the shipped
   state). Continue reading new-item bodies, not just counting them.
4. **An umbrella item can partially ship without becoming obsolete.** R222's
   input-side organ was delivered by R519, but its field-side and failure-encoding
   organs remain, so it stays a live Spec with a re-baselined input section, not a
   discard. Partial delivery of a multi-organ umbrella is a §C re-baseline, not a
   §A retirement.
5. **The re-platforming trilogy stayed complete and stable.** R431/R432/R314 did
   not reopen; the retired leaf names survive in `roadmap/` prose only, guard-
   excluded by R507. The churn source this window was input-side classification
   (R519), the `@tableMethod` removal (R535), and the `PropertyField`/`RecordField`
   merge (R51), not model decomposition.
6. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-27._
