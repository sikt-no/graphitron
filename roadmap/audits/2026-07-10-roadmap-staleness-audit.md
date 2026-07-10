# Roadmap staleness audit: 2026-07-10

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `7d6348a`, committed 2026-07-09, audited 2026-07-10). The goal is to find
items whose premise no longer holds: work already shipped, constructs renamed or
removed, dependencies that have since landed, or specs grown stale enough to
mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-09` staleness audit, which has been deleted;
only the latest staleness audit is retained. Four siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  meant to be edited in place as scope iterates. It now reads further behind than
  ever: it names none of the routine-composition, `@reference`-identity, DELETE
  write-target, or connection-lifecycle items that have closed or advanced since
  it was last touched, so its MUST/SHOULD tables lag. Refreshing it is out of
  scope for this staleness pass.
- `2026-07-04-r222-r333-conformance-analysis.md` is the R222/R333 conformance
  analysis, a companion to the R314/R333 design session. It is a targeted
  implementation-vs-spec conformance record, not a point-in-time staleness
  review; left in place.
- `classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
  inventory, doubly stale (against R299/R290 and the R316 corpus recut). It still
  warrants a "superseded, historical" banner; that banner has **not** been added,
  left unedited here per scope (see observation 6).

## Changes since the 2026-07-09 audit

A **high-throughput consolidation window**: 49 commits landed the same day the
prior audit was filed (audit committed 10:08, HEAD 19:22 on 2026-07-09), so this
review sits one calendar day later against a board that churned hard. Three
things dominate:

**1. The connection/transaction runtime family moved into flight.** **R429**
(`connection-transaction-lifecycle`), described **Ready** at the prior snapshot,
advanced to **In Progress** across four slices: the emitted connection-lifecycle
runtime + acquisition/release seam (slice 1), operation-typed transactions over
the pinned connection (slice 2), session identity hooks from `<sessionState>`
(slice 3), and the tenant-keyed acquisition seam / multi-tenant runtime map
(slice 4). Its residue was split out cleanly: read-only enforcement became
**R460** (`query-read-only-enforcement`, Backlog); the test-tier boundary was
reconciled to actual schema-invariant coverage.

**2. The DML write-path and interface-child rejection edges closed.** **R457**
(`mutation-table-param-for-delete`) rode from Backlog to **In Review**:
`@mutation(table:)` names the DELETE write-target field-relative and retires the
old `@table`-on-input-for-DELETE overload (R332 wording, docs, and the sakila
fixture were cut over in the same arc). **R452** (reject explicit `@reference` /
same-table participants on multi-table polymorphic child fields) reached **Done**,
re-homing its deferred per-participant capability into **R458**. **R453** (reject
sort-enum values missing `@order`/`@index`) reached **Done**.

**3. The LSP completeness-oracle and concurrency work advanced.** **R456** (guard
`WorkspaceFile` reads against concurrent `didChange` via copy-on-read snapshots)
reached **Done**. **R455** (`completeness-oracle-reference-walk-blind-spots`)
rode to **In Review** across two workstreams (make the reference walk see
nested-`$L`/type-var refs; model type-to-type projection composition edges), and
filed **R459** (`nesting-type-fetcher-wiring-graph-edges`) for the remaining
wiring-edge gap. **R451** (`routine-mutation-write`), **Spec** at the prior
snapshot, drove to **Done** (`@routine` on `Mutation` commits before the
follow-up query).

**Terminal closures this window (Done, all self-deleted their files):** **R446**
(`array-column-typename-codegen`, was Ready), **R449**
(`routine-chain-classification-edges`, was In Review), **R451**
(`routine-mutation-write`, was Spec), **R452**
(`reject non-FK @reference on multitable child`), **R453**
(`sort-enum-missing-order`), and **R456** (`workspacefile-read-race`). R452, R453,
and R456 were both filed and closed inside this window.

**New items on the board (filed this window, all freshly written, none stale):**

- **R454** (`routine-write-result-shapes`, **Backlog**): procedures, scalar/void
  routines, and single-node `Mutation @routine` result shapes; the typed
  follow-on to R451.
- **R455** (`completeness-oracle-reference-walk-blind-spots`, **In Review**):
  fix `TypeSpecReferenceWalk` blind spots that falsify the compile-graph
  completeness oracle superset guarantee.
- **R457** (`mutation-table-param-for-delete`, **In Review**): see above.
- **R458** (`per-participant-multitable-child-join-paths`, **Ready**): the
  per-participant explicit-join-path capability R452 deferred.
- **R459** (`nesting-type-fetcher-wiring-graph-edges`, **Backlog**): the
  schema-shape to fetcher wiring edge for fetcher-owning nesting types, from
  R455's review.
- **R460** (`query-read-only-enforcement`, **Backlog**): targeted read-only
  enforcement for query paths graphitron does not control (`@routine`,
  `@service`), split out of R429.

**Board-wide metadata retag (not a flag).** Commit `7d6348a` reclassified all
**134** active items from the drifted 11-theme taxonomy onto an 18-theme one
(`classification-model`, `diagnostics`, `routine`, `nodeid`, `interface-union`,
`service`, `mutation-write`, `error-channel`, `pagination`, `runtime-connection`,
`lsp`, `dev-loop`, `codegen-correctness`, `model-cleanup`, `legacy-migration`,
`docs`, `tooling`, `testing`), updated `VALID_THEMES` in roadmap-tool's
`Main.java`, and regenerated `README.md`. This was housekeeping, short-circuited
past the workflow gate at the user's explicit direction (metadata retag, not
generator behavior). It changed no item's premise and creates no flag.

**Board accounting.** **134 item files** today, up 3 from the prior audit's 131.
Status distribution: **111 Backlog, 16 Spec, 3 Ready, 2 In Progress (R347, R429),
2 In Review (R455, R457); zero Done**. The `134` reconciles as `131 - 3 + 6`:
three older-id closures net off the board (R446, R449, R451), six new items stay
on it (R454, R455, R457, R458, R459, R460), and the three closures that filed and
deleted in-window (R452, R453, R456) net zero. A non-recursive `^status: Done`
grep over `roadmap/*.md` returns nothing (tombstone-free for the eighth window
running). No duplicate `id:`; `next-id: R461` (in `changelog.md`) with max
allocated id **R460**. A `depends-on` sweep over all 134 files found **no dangling
slugs**: the nine distinct `depends-on` edges all resolve to present files, and
none points at a now-deleted slug (`array-column-typename-codegen`,
`routine-chain-classification-edges`, `routine-mutation-write`, or any closed
rejection-edge item). The board is structurally clean.

**Net effect on flag counts: 21 flagged, 113 current.** The flag count holds
exactly at the prior window's 21: every one of the prior 21 flags was re-verified
against current HEAD and **still reproduces** (line anchors re-anchored below,
since `FieldBuilder.java` and `TypeFetcherGenerator.java` both grew again this
window), and **no item was newly stranded** (this window's deletions retired
input/rejection-edge behavior and the `array-column` codegen crash, none of which
names a construct a flagged or unflagged Backlog item depends on). Flag
composition is unchanged: **(1 / 7 / 13)**: §A is R263 alone; §B holds its seven;
§C holds its thirteen. **No closure or transition this window subsumed any flagged
item** (verified at the symbol, not inferred from adjacency): R452's multitable
`@reference` rejection touched the reject arm, not the candidate-*hint* site R236
targets (still reads the path-origin table at `BuildContext.java:2452`); R457's
DELETE write-target retirement did not touch R242's positional-`null` layer or
R245's emit-side condition gate; R451's routine-mutation work did not touch R24's
`STUBBED_VARIANTS` entry or R71's `SourceRowsCall -> Wrap.Row` pin.

## Scope and method

All **134** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded). For each flagged
item the targets it names (classes, directives, methods) were located in the
current tree under `graphitron/src/main/java/no/sikt/graphitron/rewrite/`, the
described problem was checked for whether it still reproduces, and the changelog
was scanned for the item's `R<n>` and key terms to catch work that shipped without
the item being closed.

**The `SourceKey` decomposition still has not landed.** It remains a filed,
sequenced item (**R431**, Backlog), with R432 depending on it and R314 depending
on both. `model/SourceKey.java` still carries `Wrap` and `Cardinality` as record
components and still pins `SourceRowsCall -> Wrap.Row` (enforced at
`SourceKey.java:124-126`, throws otherwise). Nothing this window touched that pin.
So the Row-only asymmetry R71 wants to remove is intact, and R71 / R234 / R314 /
R432 all still need re-checking when R431 lands.

**Line-anchor drift this window: moderate, concentrated in two files.**

- **`FieldBuilder.java` grew to 7149** (prior audit `6819`). §B/§C rows anchored
  to it were re-anchored below.
- **`TypeFetcherGenerator.java` grew to 6900** (prior `6725`). Rows re-anchored.
- **`MutationInputResolver.java` grew to 695** (prior `679`). R245 re-anchored.
- **`BuildContext.java` is unchanged at 3016**, so §A (R263) and the R236/R66
  anchors on it carry over verbatim.
- **`GeneratorUtils.java` (578), `FetcherEmitter.java` (763),
  `ConnectionPromoter.java` (498), `ServiceCatalog.java` (1485),
  `SourceKey.java` (360), `GraphitronType.java` (554)** are unchanged this
  window; their anchors carry over verbatim.

Refreshed anchors (2026-07-10 line numbers) appear inline in the §A/§B/§C tables.

**Result: 21 items flagged, 113 current.**

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Action | Why |
|---|---|---|---|
| **R263** decode-helper-typename-first-resolution | Backlog | **Discard as speculative** (re-open only if a MUST-route `@nodeId(typeName:)` caller appears) | Re-verified on current HEAD (`BuildContext.java` unchanged this window), still valid in substance. `resolveDecodeHelperForTable` is defined `BuildContext.java:2770`; it resolves through the `@node`-only `NodeIndex`, returns `null` on multi-node ambiguity (a validate-time rejection, not the silent `decode<firstType>` the finding described), and falls back to the `typeId` suffix only when no `@node` backs the table. Its callers (`BuildContext.java:2582` bare-`@nodeId`, `:2720` `@nodeId(typeName:)` input field) both pass the table name as primary; **none** routes an authoritative `@nodeId(typeName:)`. R377 settled the open question the discard hedged on. The premise survives only in spirit and no consumer needs it. Re-anchor onto the R377 body if capturing the finding in `changelog.md`, then discard. |

## B. Outdated: needs re-spec (premise or targets materially changed) (7)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. All seven carried
over from the prior audit and were re-verified this window; **every one still
reproduces**.

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; still the sharpest.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm R276 deleted: `ResultType` is a 4-arm seal (`permits JavaRecordType, PojoResultType, JooqRecordType, JooqTableRecordType`, `GraphitronType.java:94-95`), and `PojoResultType` permits only `Backed` (`:119-120`, `record Backed` `:126`). Its primary migration target `FetcherEmitter.propertyOrRecordValue` **still does not exist** (zero hits); only the `GeneratorUtils.buildFkRowKey` half of the duplication survives, at **`generators/GeneratorUtils.java:274`, called `:245`** (that file unchanged this window). Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. |
| **R120** fkjoin-alias-dead-storage | Backlog | **R438 deleted the types this item names; still unremediated.** The body targets `FkJoin.alias` and the sibling `ConditionJoin.alias`; R438's cutover **deleted the flat `FkJoin` / `ConditionJoin` variants** (only javadoc mentions of `synthesizeFkJoin` survive). The alias concern moved onto `JoinStep.Hop.alias` (`model/JoinStep.java:82`, component `:122`) and `JoinStep.LiftedHop` (`:178`, alias component within). `synthesizeFkJoin` now lives at `BuildContext.java:1501` (returns a `FkJoinResolution`; called at `:1277`, `:1802`, `:1848`, `:2515`). The dead-storage question survives the reshape, but under new components. **R443** (Backlog) was filed to remediate exactly this; fold R120 into R443, or re-spec R120's prose onto `Hop.alias` / `LiftedHop`, or retire it if the alias is now consumed. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | **Substance confirmed still future; symbols need re-anchor.** Every symbol the mechanism names is dead: no `BatchKeyLifterDirectiveResolver`, `LifterRowKeyed`, or `RowKeyed` type in main src (the only textual hit is prose in an error string, now `FieldBuilder.java:6367`, drifted from the prior `:6124`). The live surface is `model/LifterRef.java`, routed through `@sourceRow` and `SourceKey.Reader.SourceRowsCall(LifterRef)`; the `SourceRowsCall -> Wrap.Row` pin (`SourceKey.java:124-126`) is the exact Row-only asymmetry R71 wants to remove, and no work this window touched it. Re-anchor on `LifterRef` / `SourceRowsCall` / `Wrap`. The decomposition R71 rides is filed as **R431**; wire R71's `depends-on` / prose to R431 on its next touch. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (Done), so the "R58 is currently *In Review* ... if it reverts" hedge is false and should be struck. Carriers are still String-flattened: `record ParsedPath(..., String errorMessage, ...)` at `BuildContext.java:992` (sibling `record ChainSegment(..., String errorMessage)` at `:1358`), and `record Unresolved(..., String reason)` at `InputFieldResolution.java:20-23`; the carrier-audit body stays valid, `depends-on: []` correct. Lightest re-spec in this section: strike the reversion hedge. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)" (front-matter `title:` `:3`, heading `:13`); **R94 shipped** (Done, slug `emit-input-records`, file absent). Blocker cleared, drop the blocked framing. R94's Done note handed the real remaining dependency to **R98** (`multi-source-input-validation`, Backlog, file present), so re-point the dependency there and change the title "(R94-blocked)" to "(R98-blocked)". |
| **R24** nodeidreferencefield-join-projection-form | Backlog | Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **still does not exist** (survives only in stale javadoc, `ChildField.java:1130`/`1157`), and the carrier in the **item title**, `NodeIdReferenceField`, was renamed by R50 (zero hits in main src). The surviving "not yet implemented" stub is `ChildField.CompositeColumnReferenceField`, an entry in `TypeFetcherGenerator.STUBBED_VARIANTS` (map declared `:302`, the `Map.entry` `:310-312`), whose `planSlug` is R24's own slug. Nothing this window touched it. Work is valid and unbuilt, but the title symbol and method names need a full re-anchor. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale, and worse this window: body says "1 646 lines" and "~30 private methods" (`:13-14`); `TypeFetcherGenerator.java` is **6 900** this window (~4.19x, up from 6725). Lightest-substance re-spec in this section: refresh the LOC/method-count figures and re-survey the cut against the real file, or better, stop hard-coding a count and state "measure at execution time." |

## C. Outdated: update references only (work valid, refs stale) (13)

Substance intact; only paths, line numbers, or dependency tense drifted. This
window the drift was moderate: `FieldBuilder.java` (7149) and
`TypeFetcherGenerator.java` (6900) grew, so rows on those files were re-anchored;
`BuildContext.java` (3016), `MutationInputResolver.java` (695 — R245 re-anchored),
and the smaller generators carry over. The recurring code-side root cause is
unchanged: the two large generators standing at ~2.6 to 4.19x the sizes the older
specs cite.

| Item | Stale reference |
|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Premise live and unbuilt: `resolvePayloadConstructionShape` on the PayloadClass arm (`FieldBuilder.java:517`; called `:2969`); the mutable-bean logic in `tryMutableBean` (`:582`), whose setter-match arm builds `SetterBinding`s off the raw SDL field name (`SetterBinding(...)` at `:620`) with **no `@field` read**. Body already frames it as "the output-side mirror of R200"; all anchors drifted up this window. |
| **R308** service-list-payload-arrival | The `@service` list-payload N+1 is real and unbuilt (`checkServiceReturnMatchesPayload` `FieldBuilder.java:3431`, called `:3392`). The body still describes the no-DataLoader shape via `SingleRecordTableField`, which R305 **deleted** (every occurrence is now "former SingleRecordTableField" prose, e.g. `TypeFetcherGenerator.java:6573`, `FetcherRegistrationsEmitter.java:75`); the live carrier `RecordTableField` is dispatched at `model/ChildField.java:80`/`:105`. Strip the "today" framing; bump `last-updated`. (`depends-on` already `[]`.) |
| **R202** honor-field-directive-in-error-type-source-accessors | Premise live and unbuilt: `checkErrorTypeSourceAccessors` (`FieldBuilder.java:3173`, called `:2948`) resolves the accessor with the raw SDL name and **no `@field` read**, exactly the gap R202 describes. R200's Done note names R202 as a remaining `@field`-symmetry sibling. Re-anchor at the symbol (drifted up this window). |
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck as "Obsolete (R287)"; body re-based onto R305's re-fetch substrate; `depends-on` `[]`. `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` (`FetcherEmitter.java:631`, called `:373`, **file unchanged this window**) still needs re-anchoring at the symbol. **Additional stale anchor:** the body cites the DELETE `Id`-arm iterate at `FetcherEmitter.java:944`, but the file is only **763 lines**, so that line reference is dead; re-anchor it and drop the stale "3 sites" count. R457's `@mutation(table:)` DELETE work did not touch this positional-`null` layer. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` (**695**, grew +16 this window) substance holds (emit-half still unbuilt). The non-`@table` arg rejection (`foundTia.argCondition().isPresent()`, `:452`) and the `@condition(override:true)` admission gate (`ARG_OVERRIDE`, imported `:26`, consumed `:509`) hold. Body cites older ranges; re-anchor at the symbols. |
| **R17** generated-output-walkthrough | Proposes a `.md` target (`:21`) + `.md` siblings; should be `.adoc`, under the **Diataxis `docs/architecture/` tree** R182 introduced, not the old flat `docs/` dir. The two cited sibling docs (`docs-as-index-into-tests.md` `:54`, `rewrite-docs-entrypoint.md` `:57`) do not exist under any extension or location. Relocate onto the Diataxis layout or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (absent) and stale `ConnectionSynthesis` naming. The live seam is `ConnectionPromoter.synthesiseForField` (`ConnectionPromoter.java:122`) / `.rebuildAssembledForConnections` (`:196`) (file directly under `rewrite/`, 498 lines, unchanged this window); re-anchor there and fix the dangling doc link. |
| **R92** catalog-check-constraint-validation | `validatorPreStep` is defined **`TypeFetcherGenerator.java:2201`** (seam call site **`:2069`**, both drifted up this window). No `CheckRecognizer` / `findCheckConstraints` exists; the R12 validator seam this builds on is present as described. `depends-on: [multi-source-input-validation]` (R98) resolves. |
| **R103** lift-jooq-column-defaults-onto-inputs | `DSL.defaultValue` emission is clustered in `buildPerCellValueList` (**`TypeFetcherGenerator.java:2622`**; sibling `buildPerCellValueListDeduped` **`:2795`**, both drifted up). The contract-side gap (no SDL default lift) is unbuilt: the runtime absent-key emission there is VALUES-cell only. R413 (Done a prior window) bound parent-input VALUES cells through the column Converter DataType in this cluster but did **not** address the SDL-default lift R103 targets. |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog.reflectTableMethod` (**base def `ServiceCatalog.java:563`, slot-types overload `:577`**, file unchanged this window) with the strict return-type gate following, and the stricter-return-rule sibling at `:681`; `buildQueryTableMethodFetcher` in `TypeFetcherGenerator.java`. Neither `MethodRef.StaticOnly` nor `ReturnTypeRef.TableBoundReturnType` carries a type token yet. Re-anchor the `TypeFetcherGenerator` call site at the symbol. |
| **R236** validator-reference-candidate-hint-terminal-table | (Anchors are in the **item body**.) `classifyInputFieldInternal` (**`BuildContext.java:2354`**, file unchanged this window) and the candidate-hint failure-aggregation (`candidateHint(c, catalog.columnSqlNamesOf(resolvedTable.tableName()))` at **`:2452`**) still draw the hint from the path-origin `resolvedTable.tableName()`, not the `@reference` terminal table. **Explicitly not subsumed by R452:** R452's multi-table `@reference` rejection touched the reject arm, not this candidate-hint site, which carries no `@reference`-terminal enumeration. R380's Done note still lists R236 as an open sibling. Re-anchor. |
| **R234** jooq-embedded-and-udt-input-backings | Valid, dormant Backlog stub gated behind the R222 collapse (Stages 5-7). Anchors to keep fresh: `JooqRecordInputType` is a live populated input arm (`GraphitronType.java:342`); `JooqTableRecordInputType` is a separate sibling (`:357`); the proposed `BackingClass` family is still unbuilt (no `BackingClass.java`). `depends-on: []` correct (R222 is a prose gate, not a hard edge). **Watch:** R431/R432 sit on the same `SourceKey`/leaf surface; re-check R234 when R431 lands. |
| **R35** source-orientation-javadocs | LOC counts grossly stale (FieldBuilder cited "2 172", now **7 149**; TypeFetcherGenerator "1 646", now **6 900**, `:13-14`); no `package-info.java` exists in the rewrite tree (sweep undone, re-confirmed). The architecture docs are the Diataxis `docs/architecture/` tree (`.adoc`), and `selection-parser-audit` no longer exists (R30 swept it). Refresh the figures and re-point the doc links onto the Diataxis layout. |

**Re-confirmed Current (not flagged):** **R455** (`completeness-oracle-reference-walk-blind-spots`,
**In Review** this window) and **R457** (`mutation-table-param-for-delete`, **In
Review**), both fresh. **R429** (`connection-transaction-lifecycle`) advanced to
**In Progress** (slices 1-4), fresh. **R458** (`per-participant-multitable-child-join-paths`,
**Ready**), **R454** / **R459** / **R460** (**Backlog**) all fresh residue/split
items. **R443 / R447 / R448** (**Backlog**), fresh routine/join residue from prior
windows. **R346** (`supported-directives-regen-guard`, **Ready**), **R13**
(`faceted-search`, Ready) on the live `ConnectionPromoter` seam, **R428**
(`mcp-execute-query-in-process`, Spec), **R381** (`lsp-reference-path-authoring`,
Spec), all unchanged. **R347** (`lsp-structural-consolidation`) holds at **In
Progress**. **R222** (`dimensional-model-pivot`, Spec) stays the umbrella
(`depends-on: []`, Stages 5-7 outstanding), with its dedicated conformance
analysis; **R333** (`coordinate-lowers-to-datafetcher-queryparts`, Spec);
**R314** (`dissolve-reentry-leaves-dimensional-emit`, Spec, `depends-on`
R333/R431/R432); **R45** / **R46** (tenant substrate); **R430**, **R431**,
**R432** all re-verified spec-forward, none under flag.

## Cross-cutting observations

1. **A high-throughput consolidation window.** 49 commits landed the same day the
   prior audit was filed. The board grew 131 -> 134; six items closed (R446,
   R449, R451, R452, R453, R456, three of them filed-and-closed in-window) and six
   new ones (R454, R455, R457, R458, R459, R460) stayed. Flag count held at **21**
   (all 21 prior flags survive; no new flag), current rose **110 -> 113**. Zero
   Done tombstones for the eighth window running.
2. **Line-anchor drift was moderate and two-file.** `FieldBuilder.java` (to 7149)
   and `TypeFetcherGenerator.java` (to 6900) grew; every §B/§C row anchored to them
   was re-anchored above. `BuildContext.java` was **unchanged** this window (3016),
   so §A (R263) and the R66/R236 anchors on it carry over verbatim. No flag's
   *substance* moved, only line numbers.
3. **The runtime-connection and DML-write models moved; the source-side twin has
   not.** With R429 (connection/transaction lifecycle) In Progress and R457
   (DELETE write-target) In Review, the source-side decomposition **R431**
   (`decompose-sourcekey`) is still Backlog; the `SourceKey` surface itself is
   unchanged (Wrap + Cardinality still record components; the documented mapping
   still pins `SourceRowsCall -> Wrap.Row`). When R431 lands, **R71, R234, R314,
   R432** all need re-checking.
4. **No closure this window subsumed a flagged item** (verified at the symbol).
   R452's multitable `@reference` rejection fixed the reject arm, not the
   candidate-*hint* site R236 targets (still reads the path-origin table at
   `BuildContext.java:2452`). R457's DELETE write-target retirement did not touch
   R242's positional-`null` layer or R245's emit-side condition gate. R451's
   routine-mutation work left R24's `STUBBED_VARIANTS` entry and R71's
   `SourceRowsCall -> Wrap.Row` pin untouched.
5. **No new flag this window.** Unlike the prior window (which added R120 when
   R438 deleted `FkJoin`/`ConditionJoin`), this window's deletions retired
   input/rejection-edge behavior (R452, R453, R457) and an array-column codegen
   crash (R446); a `depends-on` and symbol sweep confirms none of the deleted
   constructs is named by a flagged or unflagged Backlog item. R120 stays flagged
   from last window and R443 remains its filed remediation.
6. **`classification-test-dsl-inventory.md`** is doubly stale (R299/R290 + the
   R316 corpus recut) and still warrants the "superseded, historical" banner; it
   has **not** been added (left unedited here per scope).
7. **Board-wide theme retag is metadata-only.** Commit `7d6348a` reclassified all
   134 items onto an 18-theme taxonomy and updated `VALID_THEMES`; it changed no
   item premise and creates no flag. It was short-circuited past the workflow gate
   at the user's explicit direction.
8. **Cosmetic front-matter nits, none flag-worthy (carried over).** **R97** lacks
   `created:` / `last-updated:` header fields (an older item predating that
   convention). **R92** renders no `last-updated:` in the README rollup. Neither is
   a build or dependency risk.
9. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-10._
