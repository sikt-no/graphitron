# Roadmap staleness audit: 2026-07-24

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `7d62453`, committed 2026-07-24 01:05, audited 2026-07-24). The goal is to
find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-23` staleness audit, which has been deleted;
only the latest staleness audit is retained. Three siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  meant to be edited in place as scope iterates. Its MUST/SHOULD tables continue
  to lag the post-decomposition model and predate the R45 multi-tenant landing;
  refreshing it stays out of scope for this staleness pass.
- `2026-07-04-r222-r333-conformance-analysis.md` is the R222/R333 conformance
  analysis, a companion to the R314/R333 design session. It is a targeted
  implementation-vs-spec conformance record, not a point-in-time staleness
  review; left in place.

`classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
inventory; its "superseded, historical" banner is intact. No action; it stays as
lineage.

## Headline: a code landing retired a cited symbol; two Backlog items now name a dead method, lifting the flag set from 14 to 16

Unlike the prior, drift-free window, **this window's DML write-target work retired
a symbol two roadmap items cite as live.** R515 (`insert-write-target-from-payload`,
Done) removed `MutationInputResolver.resolveInput` outright, hoisting its
input-field admission set to shared statics. That method existed at the prior
audit's HEAD (`93e2654`, `MutationInputResolver.java:366`) and is gone now, so the
two Backlog items that name it as a live mechanism drifted this window:

- **R245** (`wire-condition-emit-on-mutations`, Backlog) `:76` locates the
  `@condition` slot composition "in `MutationInputResolver.resolveInput`". The
  composition mechanism is fully live, relocated into the shared admission statics
  (`admitMutationInputFields` reads `InputField.condition()` and applies the
  at-most-one-override rule); only the named site is dead. **New §C.**
- **R257** (`updaterows-walker-sdl-substrate`, Backlog) `:17` says the UPDATE-direct
  path "bypasses `resolveInput`/`buildLookupBindings` entirely" and calls
  `resolveInput` "the legacy resolver". `buildLookupBindings`
  (`EnumMappingResolver.java:268`) is still live; `resolveInput` is gone. **New §C.**

Everything else stayed current:

- **R46 (`multi-tenant-fanout`, In Review) is a large landing but drift-free.** The
  new `FanOut()` `TenantBinding` arm, the `TenantConnections` scatter substrate, the
  fan-out fetcher emission, and the `@tenantFanOut` directive are all **additive**:
  R46 renamed or removed no symbol any roadmap item cites. `ChildField.java` leaves
  are byte-unmoved (`ColumnBackedField` `:289`, `BatchedTableField` `:521`,
  `ServiceTableField` `:1029`), `SourceKey` is unchanged, and `TenantBinding` gained
  `FanOut` beside the existing arms without touching them. R505's proposed
  `ParentRowBound` arm is correctly still absent (R505 is Backlog).
- **R514 (Done) is additive grounding**, not a removal: it added
  `MutationInputResolver.resolveDmlWriteTableRef` and
  `RecordBindingResolver.groundDmlMutationField`; no cited symbol was retired by it.
- **R97 (`consumer-derived-input-tables`, Spec -> Ready) is current, not flagged.**
  It was respec'd this window precisely to interlock with the R457/R514/R515
  write-target series, and it names `resolveInput` / `encodedWriteTargetInputTypes`
  explicitly **as retired**, not as live. The clean line between R97 and R245/R257:
  R245/R257 present the dead method as a live mechanism (misleading); R97 presents it
  as retiring (accurate). One residual: R97 `:120` carries a now-resolved conditional
  ("if R515 has not shipped ... treat it as a blocking dependency") that R515's Done
  landing has decided; harmless (it resolves to no action), noted as an observation to
  drop at pickup, not a flag.
- **The three items born or advanced this window are current.** R516
  (`service-table-record-pk-only-contract`, new, Backlog -> Spec) and R517
  (`tenant-fanout-argument-narrowing`, new, Backlog) were vetted against the live
  model and name only live symbols (`SourceKey.Wrap.TableRecord`,
  `GeneratorUtils.buildKeyExtraction`, `ChildField.SingleRecordIdField` for R516;
  `ArgumentBound`, `FanOut` for R517). R516 in particular is filed *against* R511's
  just-landed fork, which it intends to revert. R510 was discarded cleanly.

Net: **0 §A / 1 §B / 15 §C**, §D empty. **Two new §C flags** (R245, R257) added by
R515's `resolveInput` retirement; the prior 14 (1 §B + 13 §C) all carry unchanged.

## Changes since the 2026-07-23 audit

**38 commits** landed between the prior audit's publish commit (`6d8dfe0`,
"refresh staleness audit to 2026-07-23") and this HEAD (`7d62453`, 2026-07-24
01:05). Five things drove the window:

**1. R46 (`multi-tenant-fanout`) ran Backlog -> In Review**, landing all slices
(`ae079f2` .. `7d62453`). The file was **renamed** `service-multi-tenant-fanout.md`
-> `multi-tenant-fanout.md` (dependents R505 and R47 had their link updated; R47's
edit was link-only). It shipped the bounded scatter substrate (absorbed from
discarded R510) as leading slices, the `FanOut()` classification arm and its
rejection ladder, the fanned fetcher with per-element tenant stamping, and the bare
`@tenantFanOut` directive. In Review, not yet Done; no `changelog.md` entry yet.

**2. Two items ran to Done (both self-deleted).** **R514**
(`dml-emitted-mutation-table-grounding`, `76e8cdf` .. `a54d3b3`) grounds
`ProducerBinding.DmlEmitted` from `@mutation(table:)` so payload DELETEs survive
`@table`-on-input removal (additive). **R515** (`insert-write-target-from-payload`,
`63f6efa` .. `1d5e2e0`) gives INSERT a return-derived write target so it no longer
needs `@table`, **and retires `MutationInputResolver.resolveInput` and the
`encodedWriteTargetInputTypes` carve-out** (the one drift source this window; see
Headline and §C.4). Each has a `changelog.md` entry.

**3. R97 (`consumer-derived-input-tables`) was respec'd and advanced Backlog ->
Spec -> Ready** (`47f6765` respec, `8682ef0` Spec, `0993d11` Ready). The respec
re-baselines the `@table`-on-input deprecation against the shipped call-site
resolution and the R457/R514/R515 write-target series. R97 -> Ready (`0993d11`)
landed *after* R515 -> Done (`1d5e2e0`); the sign-off accepted its resolveInput
framing (correct: framed as retired).

**4. Two new items filed and specced/parked.** **R516**
(`service-table-record-pk-only-contract`, `ef1f6fc` Backlog, `2e3a615` Spec,
`c264e23` revise) narrows the `SourceKey.Wrap.TableRecord` contract to PK-only,
reverting R426/R436/R511's full-row projection. **R517**
(`tenant-fanout-argument-narrowing`, `f83a4dc` Backlog) files client-driven fan-out
domain narrowing, depending on R46. Both well-formed, current references.

**5. R510 (`tenant-fanout-parallel-execution`) went Spec -> Discarded** (`307abdc`),
its substrate absorbed wholesale into R46 as R46's leading slices; the file was
deleted and the discard recorded in `changelog.md`.

**Terminal closures this window:** R514, R515 (Done, self-deleted); R510
(Discarded, self-deleted). R333/R427/R97 (Ready), R347 (In Progress), R46 (In
Review) carry work but are not Done.

**Board accounting.** **132 item files** today (134 `roadmap/*.md` entries minus
`README.md` and `changelog.md`), up one from the prior audit's 131: R510 discarded
and deleted (-1), R516 and R517 filed (+2), R46 renamed (net 0), and R514/R515 filed
and closed within the same window (net 0). Status distribution: **114 Backlog, 13
Spec, 3 Ready (R333, R427, R97), 1 In Progress (R347), 1 In Review (R46), 0 Done**.
A non-recursive `^status: Done` grep over `roadmap/*.md` returns nothing
(tombstone-free for the eighteenth window running). No duplicate `id:`; max
allocated id **R517**, and `next-id: R518` clears it. A `depends-on:` sweep over all
132 item files resolves every edge (10 edges), R-number and slug alike, to a present
file, including R517's new slug edge to `multi-tenant-fanout`. The board is
structurally clean.

**Net effect on flag counts: 16 flagged, 116 current.** 0 §A, 1 §B, 15 §C, 0 §D.
Up two §C from the prior window's 14.

## Scope and method

All **132** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded: no `R<n>`). The
claimed model constants were re-verified at the symbol. `ChildField.java` declares
its leaves at the anchors R508 last set, **unmoved this window** (R46/R514/R515 do
not touch `ChildField`): `ColumnBackedField` (`:289`), `BatchedTableField`
(`:521`), `BatchedLookupTableField` (`:655`), `TableMethodField` (`:724`),
`NestingField` (`:875`), `PivotField` (`:911`), `ServiceTableField` (`:1029`), with
**no `CompositeColumnField`, `ColumnField`, `RecordTableField`, `SplitTableField`,
or `SplitLookupTableField`** as live leaves. `SourceKey.java` is the plain `public
record SourceKey(...)` residue (`:40`) with **no `Reader` interface and no
`SourceRowsCall`**; its `Wrap` permits (`Row`, `Record`, `TableRecord`) are live.
`KeyLift.java` and `LifterRef.java` are both live (the §B/§C re-anchor targets
exist).

**The window's symbol changes, verified at the symbol:**

- **`MutationInputResolver.resolveInput` — RETIRED (R515).** Present at the prior
  HEAD (`93e2654:MutationInputResolver.java:366`, `Resolved resolveInput(...)`), no
  definition today; a single comment residue at `:398` names it as the retired
  upstream. The admission set is now shared statics (`admitMutationInputFields`
  `:515`, `rejectInputFieldDirectives` `:471`, `rejectPlainColumnCollision`), each
  reading `InputField.condition()` directly. **Drives the two new §C flags.**
- **`RecordBindingResolver.resolveInput(String)` — LIVE** (`:173`), a *different*
  method (SDL-type-name -> `Class<?>` lookup) consumed by `TypeBuilder`. Items citing
  `bindings.resolveInput(name)` (e.g. R337 `input-nesting-projection-classification`
  `:19`) are **not** stale; the name collision is real but the cited method is live.
- **`EnumMappingResolver.buildLookupBindings` — LIVE** (`:268`). R257's second cite is
  fine; only its `resolveInput` cite drifted.
- **`FanOut()` `TenantBinding` arm — NEW, LIVE (R46)** (`TenantBinding.java:105`),
  additive beside `ArgumentBound`, `NodeIdBound`, `EntityRepBound`, `Inherited`,
  `Untenanted`.
- **`resolveDmlWriteTableRef` / `groundDmlMutationField` — NEW, LIVE (R514/R515)**,
  additive.
- `SplitRowsMethodEmitter` is **not** renamed and is correct wherever it appears.

**Anchors that held (re-verified):** the fourteen carried flags were re-checked
against their (unedited, save R505's link-only retense) roadmap files; every cited
stale phrase is still literally present, so every carried flag remains accurate. The
re-anchor *targets* are all live (`BatchedTableField` `:521`, `BatchedLookupTableField`
`:655`, `TableMethodField` `:724`, `ColumnBackedField` `:289`, the decomposed
`KeyLift`/`LifterRef`/`Wrap` model). R66 / R7 / R35 remain re-derive-at-pickup by
construction.

## A. Obsolete: should leave the active roadmap (0)

Empty. No item's entire premise was invalidated this window. R514 and R515 closed
cleanly to Done; R510 was discarded (its substance absorbed into R46, not lost). No
item they touched was left orphaned, and no Backlog item requesting INSERT
write-target derivation or fan-out was subsumed as a whole (R517 is a distinct
follow-on to R46, not a duplicate). No new duplicate or fully-superseded item
surfaced.

## B. Outdated: needs re-spec (premise or targets materially changed) (1)

Unchanged from last window: one item, carried untouched.

| Item | Status | What changed | Recommended action |
|---|---|---|---|
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | R431 (Done) **deleted the target surface**, not just its line numbers. The body cites `model/SourceKey.java:124-128` (a `SourceRowsCall -> Wrap.Row` compact-ctor pin) and `:288` (`SourceKey.Reader.SourceRowsCall(LifterRef)`); `SourceKey` today is a plain record (`:40`) with **no `Reader` interface and no `SourceRowsCall`**. The body's own 2026-07-13 re-anchor note (`:14`) still names `SourceKey.Reader.SourceRowsCall` / `Wrap` as "the live surface", so that note is itself stale. The sequencing line (`:33`) still reads "R431 ... plans to decompose"; R431 is Done. The mechanism (the lifter contract pin) was relocated by R431 into `KeyLift`/`LifterRef`/`Wrap`. Not touched this window. | **Re-spec the current-state / approach section** against the decomposed `KeyLift`/`LifterRef`/`Wrap` model. The goal (recordN key parity, non-jOOQ record parents) is intact, so this is a targeted re-derivation of the attachment surface. Drop the "R431 plans to decompose" tense (`:33`); R431 is Done. R71 remains the last item carrying the pre-R431 `SourceKey.Reader` shape as a *detailed live approach* rather than a passing mention. |

## C. Outdated: update references only (work valid, refs stale) (15)

Substance intact; names and line numbers drifted. Thirteen carry from the prior
window unchanged; two are new this window (§C.4), added by R515's `resolveInput`
retirement.

### C.1 `planSlug` / `SourceKey.Reader` removal drift (carried)

R484 (Done) removed `Rejection.Deferred.planSlug`; R431 (Done) removed the
`SourceKey.Reader` interface. Deferrals now anchor by `StubKey.VariantClass` (a
live class) and carry no roadmap-item pointer; column reads off a parent row lift
via `KeyLift.FkColumns`.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R454** routine-write-result-shapes | Backlog | Line 18 describes the deferred shapes as "typed `Deferred`s pointing at this item's planSlug". The `planSlug` field no longer exists, and deferrals no longer carry a roadmap-item pointer. | **Re-anchor** the mechanism sentence: the deferred shapes surface at build time via `StubKey.VariantClass` naming the variant, with no roadmap pointer. Substance valid. |
| **R447** routine-chain-fetch-form-breadth | Backlog | Two causes. (a) Lines 18 and 33 name "typed `Deferred` landings whose `planSlug` points here" / "empty planSlug in R435"; `planSlug` is gone. (b) Line 24 (`SplitLookupTableField` composition) and line 26 (`RecordTableField` seam) name merged leaves as live. | **Re-anchor** both: drop the `planSlug` pointer phrasing (deferrals anchor by `StubKey.VariantClass` now), and repoint the two variant names to the `Batched*` leaves. |
| **R180** record-parent-column-read-helper | Spec | Prose (`:35`) says "R431 ... now In Progress" -> R431 is **Done**, and names `SourceKey.Reader.AccessorCall` as a live carrier -> `SourceKey` has no `Reader` interface today. The substance (consume the R461 `ClassAccessorResolver.enumerate` / `AccessorProbe` surface for record-parent column reads) is valid and its machinery is live (`:30`). | **Re-anchor** the `SourceKey.Reader.AccessorCall` carrier onto the decomposed model, and fix the R431 tense to Done. Borderline with §B (the carrier is fully gone), but the core machinery it consumes (R461) is live, so a reference repoint suffices. |
| **R505** tenant-index-parent-row-routing | Backlog | Line 21 names "a column read off the parent row (the `SourceKey.Reader` family)" as the carrier for the proposed `ParentRowBound` `TenantBinding` arm. `SourceKey.Reader` was removed by R431, *before* R505 was filed (2026-07-20), so it was born stale. This window's edit was link-only (the R46 filename rename); the flagged phrase is untouched. The mechanism (per-row tenant read off a tenant-index parent) is live via `KeyLift.FkColumns`; only the one-phrase carrier name is wrong. | **Re-anchor** the parenthetical: a per-row column read lifts via `KeyLift.FkColumns` (there is no `SourceKey.Reader` family). One-phrase fix; the item's design intent is intact. |

### C.2 R432 leaf-merge / R314 dissolution / R431 anchor drift (carried, unchanged)

`SplitTableField`/`RecordTableField` -> `BatchedTableField`;
`SplitLookupTableField`/`RecordLookupTableField` -> `BatchedLookupTableField`;
`RecordTableMethodField` dissolved onto the record-sourced `BatchedTableField`
carrying `TableExpr.MethodCall`; the surviving `@tableMethod` leaf is
`TableMethodField`. `SplitRowsMethodEmitter` is **not** renamed and is correct
wherever it appears. R504 (Done) and R507 (Done) scrubbed and now guard these dead
names in code, javadoc, and `.adoc`, **but both explicitly leave `roadmap/` prose
untouched** (R507 excludes `roadmap/` from its guard scan by design), so this cluster
stays an audit responsibility.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R403** reintroduce-tablemethod-docs | Backlog | Line 44 names `ChildField.RecordTableMethodField` as the live construct the reintroduced docs would describe. That leaf no longer exists; the DTO-parent `@tableMethod` shape now classifies to a **record-sourced `BatchedTableField`** carrying `TableExpr.MethodCall`. | **Re-anchor** the construct description to the post-R314 shape. |
| **R109** list-valued-external-field-multiset | Spec | Line 51's planned enum arm asserts "`RecordTableField` with `BatchKey.AccessorKeyedMany`". | **Re-anchor** the planned test assertion to `BatchedTableField`. |
| **R462** nested-fetcher-outgoing-field-edges | Spec | Lines 37, 45, 128 name `SplitTableField` / `SplitLookupTableField` as live. `SplitRowsMethodEmitter` in the same passages is correct. R508 re-anchored this item's *composite*-name mentions previously, but the `Split*` names it did not own remain. | **Re-anchor** the surviving `Split*` variant names; leave `SplitRowsMethodEmitter` and the already-fixed `ColumnBackedField` mentions untouched. |
| **R242** dml-payload-positional-alignment | Spec | Lines 37-38's R305 lineage note "collapsed it into `RecordTableField`, `ChildField.java:912`" is doubly stale: the name is now `BatchedTableField` (`:521`), and the cite `:912` today lands inside `PivotField`/`NestingField` territory (`:911`/`:875`), not on any merged leaf. | **Re-anchor** that one name + cite (`BatchedTableField`, `:521`); leave the surrounding R305/R287 history. |
| **R288** inline-interface-and-tablemethod-children | Backlog | Line 35: "a keyed batch (DataLoader, as `SplitTableField` / `RecordTableField` do via `SplitRowsMethodEmitter`)". Variant names stale; emitter fine. | **Re-anchor** the two variant names. |
| **R472** nested-generated-condition-filters-never-emitted | Backlog | Lines 20-21: classifier attaches `GeneratedConditionFilter` to a nested `SplitTableField` / `SplitLookupTableField` / inline `TableField` / `LookupTableField`. (`TableField` / `LookupTableField` are still live.) | **Re-anchor** the two `Split*` names. |
| **R116** composite-key-row2-source-row-coverage | Backlog | Line 15: the planned `COMPOSITE_KEY_ROW2_PATH_KEYED` case "classifies as `RecordTableField` with a `LifterPathKeyed`". | **Re-anchor** to `BatchedTableField`. |
| **R7** decompose-typefetchergenerator | Backlog | Line 30 proposes a hypothetical decomposed emitter `SplitTableFieldEmitter`; a decomposition done today would name it `BatchedTableFieldEmitter`. Illustrative "etc." naming; the list of *existing* emitters (incl. `SplitRowsMethodEmitter` `:32`) is correct. | **Low priority:** refresh the illustrative name at pickup. Not blocking. |

### C.3 R508 composite-column dissolution drift (carried)

R508 (Done) merged `ColumnField`/`CompositeColumnField` -> `ColumnBackedField` and
`ColumnReferenceField`/`CompositeColumnReferenceField` -> `ColumnBackedReferenceField`.
Its re-anchor pass caught five items but missed one.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R51** propertyfield-recordfield-nullable-column | Backlog | Line 13's R50 lineage clause names `ChildField.ColumnField` / `ColumnReferenceField` as the carriers R50 retired `columnName` on; both were merged into `ColumnBackedField` / `ColumnBackedReferenceField` by R508. Namespaced, live-looking cites. The item's own subject (`PropertyField`/`RecordField`, both live) is unaffected. | **Re-anchor** the two lineage names to `ColumnBackedField` / `ColumnBackedReferenceField`; leave the surrounding R50 history. Low priority (lineage-precedent clause, no line cite). |

### C.4 R515 `resolveInput` retirement drift (NEW this window)

R515 (Done) removed `MutationInputResolver.resolveInput`, hoisting its
input-field admission set to the shared statics `admitMutationInputFields` /
`rejectInputFieldDirectives` / `rejectPlainColumnCollision` (each reading
`InputField.condition()`). Two Backlog items name the retired method as a live
mechanism. Both substance-valid; the sibling `EnumMappingResolver.buildLookupBindings`
they also cite is still live.

| Item | Status | Stale reference | Recommended action |
|---|---|---|---|
| **R245** wire-condition-emit-on-mutations | Backlog | Line 76 locates the `@condition` slot composition "in `MutationInputResolver.resolveInput`", which "reads input-field-level `@condition` annotations off `InputField.condition()` ... applies the at-most-one-override rule, and writes the two slots". The method is gone; the composition mechanism is live in `admitMutationInputFields` (which reads `InputField.condition()` and the `override()` rule). The item's design (wire `@condition` through to mutation WHERE, emit half) is fully intact. | **Re-anchor** the one sentence: the composition now lives in `MutationInputResolver.admitMutationInputFields` (the hoisted admission statics), not `resolveInput`. |
| **R257** updaterows-walker-sdl-substrate | Backlog | Line 17: the UPDATE-direct path "bypasses `resolveInput`/`buildLookupBindings` entirely", with the rules living "in two places (the legacy resolver for INSERT/DELETE/UPSERT/payload-UPDATE, and the walker...)". `buildLookupBindings` is live; `resolveInput` ("the legacy resolver") is gone, its admission set hoisted to shared statics. The two-places duplication R257 targets still exists (walker vs shared statics), so the substance is intact. | **Re-anchor** the `resolveInput` name to the shared admission statics; the "two places" framing survives, now walker vs `admitMutationInputFields`. Low priority. |

**No flag (verified live / current / re-baselined this window):** **R97** (Ready) was
respec'd against the R457/R514/R515 series and names `resolveInput` /
`encodedWriteTargetInputTypes` explicitly *as retired*, not as live; its one residual
is a now-resolved "if R515 has not shipped" conditional (`:120`), an observation, not a
flag (see cross-cutting #2). **R516** (Spec) and **R517** (Backlog), both new this
window, name only live symbols. **R337** (`input-nesting-projection-classification`,
Backlog) cites `bindings.resolveInput(name)` = `RecordBindingResolver.resolveInput`,
which is **live** (a distinct method from the retired one). **R222** (Spec) keeps its
R508 supersession banner. **R333** (Ready) carries its `ColumnField`/`SplitTableField`
mentions as Discovery/design substrate, not live claims. **R24 / R27 / R419** (Backlog)
were re-anchored to `ColumnBacked*` by R508. **R323** self-flagged its old `Split*`/
`Record*` names with a pre-declared "Re-anchor at pickup" note. **R302** and
`rename-childfield-to-sourcefield` mention `SingleRecordTableField` only in R305
lineage clauses.

## D. Structural: (0)

Empty. `changelog.md` carries `next-id: R518`, clearing the max allocated id (R517).
No duplicate `id:`, no `status: Done` tombstones in `roadmap/*.md`, and a
`depends-on:` sweep over all 132 item files resolves every edge (10 edges), R-number
and slug alike, to a present file (including R517's new slug edge to
`multi-tenant-fanout`, and R505/R47's updated links to the renamed file). The two new
items (R516, R517) carry well-formed front-matter.

## Cross-cutting observations

1. **A code landing retired a cited symbol, breaking the prior window's drift-free
   streak.** R515 removed `MutationInputResolver.resolveInput`, and two Backlog items
   named it. This is the ordinary cost of churning a public-ish method name the roadmap
   references: unlike last window's additive-only landings (R511/R512/R513), a removal
   shifts an anchor. The observation from prior audits holds: additive-or-internal-refactor
   buys the roadmap zero drift; removing or renaming a named mechanism costs a §C entry per
   citing item. R46, by contrast, landed large and additive-only, and cost nothing.
2. **A respec that tracks a landing series stays current where a passing mention drifts.**
   R97 names `resolveInput` too, but *as retired*, because it was respecced this window to
   interlock with R514/R515; R245/R257 name it as live because they predate the retirement
   and describe it as ambient mechanism. The distinction is the flag line: an item that
   frames a symbol as retiring is current even after it retires; an item that leans on it as
   live is stale the moment it goes. R97's one residual is a now-decided "if R515 has not
   shipped" conditional (`:120`) that should drop at implementation; harmless (resolves to no
   action), so it is an observation, not a §C flag.
3. **Born-stale vetting at Spec/Backlog entry worked again.** R516 (new Spec) and R517 (new
   Backlog) were read against the current model and found current; R516 is filed *against*
   R511's just-landed fork, which it intends to revert, so it is deliberately coupled to the
   live state. Continue reading new-item bodies, not just counting them.
4. **A file rename propagates to dependents' links, and that is not a re-anchor.** R46's
   `service-multi-tenant-fanout.md` -> `multi-tenant-fanout.md` rename correctly updated the
   links in R505 and R47, but R505's born-stale `SourceKey.Reader` phrase (§C.1) rode along
   untouched, exactly the partial-mitigation pattern prior audits named. The author fixes the
   link they are editing; the audit remains the only pass reviewing every phrase.
5. **The re-platforming trilogy stayed complete and stable.** R431/R432/R314 did not reopen;
   the retired leaf names exist in code only as `former`/`guarded`/`dissolved` lineage prose,
   guard-enforced by R507. The churn source this window was DML write-target work (R514/R515)
   and tenant fan-out (R46), not model decomposition.
6. **Carried cosmetic front-matter nit persists (partially resolved).** **R97**
   (`consumer-derived-input-tables`) now carries `last-updated: 2026-07-23` (gained at its
   respec) but still lacks `created:`. Not a build or dependency risk. No other item lacks
   `created:`/`last-updated:` beyond the documented pre-R143 render behavior.
7. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated placeholder, not a
   roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-24._
