# Roadmap staleness audit: 2026-07-13

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `ee7a54a`, committed 2026-07-12, audited 2026-07-13). The goal is to find
items whose premise no longer holds: work already shipped, constructs renamed or
removed, dependencies that have since landed, or specs grown stale enough to
mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-10` staleness audit, which has been deleted;
only the latest staleness audit is retained. Four siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  meant to be edited in place as scope iterates. It now reads further behind
  still: it names none of the connection-lifecycle-ownership, DELETE
  write-target, list-payload-carrier, or scalar-resolution items that closed this
  window, so its MUST/SHOULD tables lag further. Refreshing it is out of scope
  for this staleness pass.
- `2026-07-04-r222-r333-conformance-analysis.md` is the R222/R333 conformance
  analysis, a companion to the R314/R333 design session. It is a targeted
  implementation-vs-spec conformance record, not a point-in-time staleness
  review; left in place.
- `classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
  inventory, doubly stale (against R299/R290 and the R316 corpus recut). It still
  warrants a "superseded, historical" banner; that banner has **not** been added,
  left unedited here per scope (see observation 6).

## Changes since the 2026-07-10 audit

**50 commits** landed between the prior snapshot (HEAD `7d6348a`, 2026-07-09
19:22) and this one (HEAD `ee7a54a`, 2026-07-12 20:43), a three-calendar-day
window dominated by **terminal closures**: nine items reached Done, seven of them
older-id items whose files self-deleted, two filed-and-closed in-window. Four
things drive the window:

**1. Graphitron took ownership of the connection lifecycle.** **R429**
(`connection-transaction-lifecycle`), **In Progress** at the prior snapshot,
reached **Done**: an application-scoped `GraphitronRuntime` owns the consumer's
`DataSource`, every operation pins one connection, caller claims travel as an
opaque `String` to consumer-owned connect/disconnect hooks structurally outside
any transaction, each mutation field commits/rolls back through the emitted
`GraphitronTransactionProvider`, and `<sessionState>` emits function-hook or
Postgres-`<variables>` session identity. It spun off three fresh Backlog items:
**R460** (`query-read-only-enforcement`, split earlier), **R468**
(`oracle-ras-session-hook-execution-coverage`), and **R469**
(`defer-under-owned-connections`), and fed **R45** (tenant substrate, still Spec).

**2. The MCP in-process execute path and scalar-resolution cleanup closed.**
**R428** (`mcp-execute-query-in-process`), **Spec** at the prior snapshot, drove
Spec -> Ready -> In Progress -> In Review -> **Done** across five slices
(`GraphitronDevExecutor`, `DevQueryExecutor`, `<devDatabase>` config, the
conditionally-registered MCP execute tool, deferred-rollback topology). **R464**
(`remove-convention-scalar-resolution`) was filed and closed in-window: it
deleted convention-based scalar resolution from `ScalarTypeResolver` (the file
shrank ~133 lines), now requiring `@scalarType`.

**3. The list-payload carrier verdict and directive-support guard shipped.**
**R308** (`service-list-payload-arrival`), **Spec** and **flagged §C** at the
prior snapshot, reached **Done**: one classify-time `ServiceCarrierShape` verdict
over the (carrier wrapper, producer return shape, data-field wrapper) triple
replaces the uncoordinated wrapper reads, with a new `ServiceCarrierShapeError`
sealed sub-seal of `Rejection.AuthorError`. Its ancestor-product arrival fold was
split out to fresh Spec item **R463** (`ancestor-product-arrival-fold`); R308's
three in-code forward references retargeted from R279/R308 to R463. **R346**
(`supported-directives-regen-guard`, was Ready) reached **Done** (regenerate +
`--verify` guard on the supported-directives fragment).

**4. The completeness-oracle wiring gap and a version bump closed.** **R455**
(`completeness-oracle-reference-walk-blind-spots`, was In Review) reached
**Done**; its remaining fetcher-owning-nesting-type wiring gap, filed last window
as **R459**, itself reached **Done** this window, deferring the nested fetcher's
own outgoing per-field edges to fresh Backlog **R462**
(`nested-fetcher-outgoing-field-edges`). **R465**
(`upgrade-federation-and-extended-scalars`) was filed and closed in-window
(federation 6.0->6.2, extended-scalars 22->24).

**Terminal closures this window (Done, all self-deleted their files):** **R308**
(`service-list-payload-arrival`, was Spec, **§C-flagged**), **R346**
(`supported-directives-regen-guard`, was Ready), **R428**
(`mcp-execute-query-in-process`, was Spec), **R429**
(`connection-transaction-lifecycle`, was In Progress), **R455**
(`completeness-oracle-reference-walk-blind-spots`, was In Review), **R457**
(`mutation-table-param-for-delete`, was In Review), **R459**
(`nesting-type-fetcher-wiring-graph-edges`, was Backlog), plus **R464**
(`remove-convention-scalar-resolution`) and **R465**
(`upgrade-federation-and-extended-scalars`), both filed and closed inside this
window.

**New items on the board (filed this window, all freshly written, none stale):**

- **R461** (`unify-sdl-field-accessor-resolution`, **Backlog**): consolidate the
  four divergent SDL-field-to-Java-accessor resolvers behind one; the follow-up
  R180's Non-goals explicitly deferred.
- **R462** (`nested-fetcher-outgoing-field-edges`, **Backlog**): the nested
  fetcher's own outgoing per-field edges, deferred from R459.
- **R463** (`ancestor-product-arrival-fold`, **Spec**): the ancestor-product
  arrival fold split out of R308.
- **R466** (`upgrade-jooq-3-21`, **Backlog**), **R467** (`upgrade-graphql-java-26`,
  **Backlog**): the next-major dependency bumps beyond R465.
- **R468** (`oracle-ras-session-hook-execution-coverage`, **Backlog**), **R469**
  (`defer-under-owned-connections`, **Backlog**): R429 residue.

**Board accounting.** **134 item files** today, flat with the prior audit's 134.
Status distribution: **116 Backlog, 15 Spec, 2 Ready (R13, R458), 1 In Progress
(R347); zero In Review, zero Done**. The `134` reconciles as `134 - 7 + 7`: seven
older-id closures net off the board (R308, R346, R428, R429, R455, R457, R459),
seven new items stay on it (R461, R462, R463, R466, R467, R468, R469), and the two
closures that filed and deleted in-window (R464, R465) net zero. A non-recursive
`^status: Done` grep over `roadmap/*.md` returns nothing (tombstone-free for the
ninth window running). No duplicate `id:`; `next-id: R470` (in `changelog.md`)
with max allocated id **R469**. A `depends-on` sweep over all 134 files found **no
dangling slugs**: the ten inline `depends-on` edges all resolve to present files,
and none points at a now-deleted slug (`service-list-payload-arrival`,
`connection-transaction-lifecycle`, `mcp-execute-query-in-process`,
`mutation-table-param-for-delete`, `supported-directives-regen-guard`,
`completeness-oracle-reference-walk-blind-spots`,
`nesting-type-fetcher-wiring-graph-edges`, `remove-convention-scalar-resolution`,
or `upgrade-federation-and-extended-scalars`). The board is structurally clean.

**Net effect on flag counts: 20 flagged, 114 current.** The flag count fell by
one from the prior window's 21: **R308** (the sole §C row that closed this window)
is Done and drops off; every other prior flag was re-verified against current HEAD
and **still reproduces**. **No item was newly stranded**: this window's deletions
retired connection-lifecycle, DELETE-write-target, list-payload-carrier,
scalar-convention, directive-support, and completeness-oracle-wiring behavior, and
a `depends-on` + symbol sweep confirms none of the deleted constructs is named by a
flagged or unflagged Backlog item. **No closure this window subsumed any surviving
flagged item** (verified at the symbol, not inferred from adjacency): R308's
list-carrier verdict touched `checkServiceReturnMatchesPayload` and added
`ServiceCarrierShapeError`, not the `@field`-read gaps R201/R202 target (both still
build accessors off the raw SDL name); R464's scalar-convention removal did not
touch R263's `resolveDecodeHelperForTable`; R429's runtime-connection work left
R71's `SourceRowsCall -> Wrap.Row` pin and R24's `STUBBED_VARIANTS` entry
untouched. Flag composition: **(1 / 7 / 12)**: §A is R263 alone; §B holds its
seven; §C falls to twelve (R308 retired).

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
on both (`depends-on: [coordinate-lowers-to-datafetcher-queryparts,
decompose-sourcekey, collapse-split-and-record-table-leaves]`). `model/SourceKey.java`
is **unchanged at 360** and still pins `SourceRowsCall -> Wrap.Row`
(`SourceKey.java:124-126`, throws otherwise). So the Row-only asymmetry R71 wants
to remove is intact, and R71 / R234 / R314 / R432 all still need re-checking when
R431 lands.

**Line-anchor drift this window: moderate, concentrated in two files.**

- **`FieldBuilder.java` grew to 7276** (prior `7149`), all in the R308
  list-carrier region; the §C rows above that region (R201 at `:518`, R202 at
  `:3174`) barely moved. Rows re-anchored below.
- **`BuildContext.java` grew to 3061** (prior `3016`); §A (R263) and §B/§C rows on
  it (R66, R120, R236) shifted down and were re-anchored below.
- **`ServiceCatalog.java` (1484)**, **`GraphitronType.java` (553)**, and
  **`ChildField.java` (1194)** changed by at most one line net; their anchors
  carry over verbatim (R240, R180/R234, R24).
- **`TypeFetcherGenerator.java` (6900)**, **`FetcherEmitter.java` (763)**,
  **`MutationInputResolver.java` (695)**, **`GeneratorUtils.java` (578)**,
  **`ConnectionPromoter.java` (498)**, and **`SourceKey.java` (360)** are all
  **unchanged this window**; their anchors (R7, R35, R92, R103, R240; R242; R245;
  R180; R10; R71) carry over verbatim.

Refreshed anchors (2026-07-13 line numbers) appear inline in the §A/§B/§C tables.

**Result: 20 items flagged, 114 current.**

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Action | Why |
|---|---|---|---|
| **R263** decode-helper-typename-first-resolution | Backlog | **Discard as speculative** (re-open only if a MUST-route `@nodeId(typeName:)` caller appears) | Re-verified on current HEAD, still valid in substance. `resolveDecodeHelperForTable` is now defined `BuildContext.java:2815` (was `:2770`); it resolves through the `@node`-only `NodeIndex`, returns `null` on multi-node ambiguity (a validate-time rejection, not the silent `decode<firstType>` the finding described), and falls back to the `typeId` suffix only when no `@node` backs the table. Its callers (`:2627` bare-`@nodeId`, `:2765` `@nodeId(typeName:)` input field) both pass the table name as primary; **none** routes an authoritative `@nodeId(typeName:)`. R377 settled the open question the discard hedged on; R464's scalar-convention removal did not touch this method. The premise survives only in spirit and no consumer needs it. Re-anchor onto the R377 body if capturing the finding in `changelog.md`, then discard. |

## B. Outdated: needs re-spec (premise or targets materially changed) (7)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. All seven carried
over from the prior audit and were re-verified this window; **every one still
reproduces**.

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; still the sharpest.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm R276 deleted: `ResultType` is a 4-arm seal (`sealed interface ResultType`, `GraphitronType.java:93`), and `PojoResultType` permits only `Backed`. Its primary migration target `FetcherEmitter.propertyOrRecordValue` **still does not exist** (zero hits); only the `GeneratorUtils.buildFkRowKey` half of the duplication survives, at **`generators/GeneratorUtils.java:274`, called `:245`** (that file unchanged this window). **New this window:** R461 (`unify-sdl-field-accessor-resolution`, Backlog) was filed as the accessor-lift follow-up R180's Non-goals defer; wire R180's prose to it. Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. |
| **R120** fkjoin-alias-dead-storage | Backlog | **R438 deleted the types this item names; still unremediated.** The body targets `FkJoin.alias` and the sibling `ConditionJoin.alias`; the flat `FkJoin` / `ConditionJoin` variants remain **deleted** (no `record FkJoin` / `record ConditionJoin` in main src). The alias concern moved onto `JoinStep.Hop.alias` (`model/JoinStep.java:122`) and `JoinStep.LiftedHop` (`:181`). `synthesizeFkJoin` now lives at **`BuildContext.java:1546`** (was `:1501`; returns a `FkJoinResolution`; called at `:1322`, `:1847`, `:1893`, `:2560`). The dead-storage question survives the reshape, but under new components. **R443** (Backlog) was filed to remediate exactly this; fold R120 into R443, or re-spec R120's prose onto `Hop.alias` / `LiftedHop`, or retire it if the alias is now consumed. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | **Substance confirmed still future; symbols need re-anchor.** Every symbol the mechanism names is dead: no `BatchKeyLifterDirectiveResolver`, `LifterRowKeyed`, or `RowKeyed` type in main src (the only textual hit is prose in an error string, now **`FieldBuilder.java:6494`**, drifted from the prior `:6367`). The live surface is `LifterRef`, routed through `@sourceRow` and `SourceKey.Reader.SourceRowsCall(LifterRef)`; the `SourceRowsCall -> Wrap.Row` pin (`SourceKey.java:124-126`, file unchanged) is the exact Row-only asymmetry R71 wants to remove, and no work this window touched it. Re-anchor on `LifterRef` / `SourceRowsCall` / `Wrap`. The decomposition R71 rides is filed as **R431**; wire R71's `depends-on` / prose to R431 on its next touch. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (Done), so the "R58 is currently *In Review* ... if it reverts" hedge is false and should be struck. Carriers are still String-flattened: `record ParsedPath(..., String errorMessage, ...)` at **`BuildContext.java:1037`** (was `:992`; sibling `record ChainSegment(..., String errorMessage)` at **`:1403`**, was `:1358`), and `record Unresolved(..., String reason)` at `InputFieldResolution.java:20-23` (file unchanged, directly under `rewrite/`); the carrier-audit body stays valid, `depends-on: []` correct. Lightest re-spec in this section: strike the reversion hedge. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)"; **R94 shipped** (Done, slug `emit-input-records`, file absent). Blocker cleared, drop the blocked framing. R94's Done note handed the real remaining dependency to **R98** (`multi-source-input-validation`, Backlog, file present), so re-point the dependency there and change the title "(R94-blocked)" to "(R98-blocked)". Unchanged this window (no code touched). |
| **R24** nodeidreferencefield-join-projection-form | Backlog | Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **still does not exist** (survives only in stale javadoc, `ChildField.java:1130`/`1157`), and the carrier in the **item title**, `NodeIdReferenceField`, was renamed by R50 (zero hits in main src). The surviving "not yet implemented" stub is `ChildField.CompositeColumnReferenceField`, an entry in `TypeFetcherGenerator.STUBBED_VARIANTS` (map declared `:302`, the `Map.entry` `:310-312`, both **unchanged this window**), whose `planSlug` is R24's own slug. Nothing this window touched it. Work is valid and unbuilt, but the title symbol and method names need a full re-anchor. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines" and "~30 private methods"; `TypeFetcherGenerator.java` is **6 900** this window (**unchanged**, ~4.19x). Lightest-substance re-spec in this section: refresh the LOC/method-count figures and re-survey the cut against the real file, or better, stop hard-coding a count and state "measure at execution time." |

## C. Outdated: update references only (work valid, refs stale) (12)

Substance intact; only paths, line numbers, or dependency tense drifted. This
window the drift was moderate: `FieldBuilder.java` (7276) and `BuildContext.java`
(3061) grew, so rows on those files were re-anchored; the six large generators
(`TypeFetcherGenerator.java` 6900, `FetcherEmitter.java` 763,
`MutationInputResolver.java` 695, `GeneratorUtils.java` 578,
`ConnectionPromoter.java` 498) and `ServiceCatalog.java` (1484) were unchanged and
carry over verbatim. **R308 dropped from this section (Done this window.)**

| Item | Stale reference |
|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Premise live and unbuilt: `resolvePayloadConstructionShape` on the PayloadClass arm (`FieldBuilder.java:518`; called `:2970`); the mutable-bean logic in `tryMutableBean` (`:583`), whose setter-match arm builds `SetterBinding`s off the raw SDL field name (`SetterBinding(sdlFieldName, ...)` at `:621`) with **no `@field` read** (verified this window). Body already frames it as "the output-side mirror of R200"; anchors barely drifted (the R308 growth is below them). |
| **R202** honor-field-directive-in-error-type-source-accessors | Premise live and unbuilt: `checkErrorTypeSourceAccessors` (`FieldBuilder.java:3174`, called `:2949`) resolves the accessor with the raw SDL name (`sdlField.getName()`) and **no `@field` read** (verified this window), exactly the gap R202 describes. R200's Done note names R202 as a remaining `@field`-symmetry sibling. R308's list-carrier verdict did not touch this method. |
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck as "Obsolete (R287)"; body re-based onto R305's re-fetch substrate; `depends-on` `[]`. `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` (`FetcherEmitter.java:631`, called `:373`, **file unchanged this window**) still needs re-anchoring at the symbol. **Stale anchor persists:** the body cites the DELETE `Id`-arm iterate at `FetcherEmitter.java:944`, but the file is only **763 lines**, so that line reference is dead; re-anchor it and drop the stale "3 sites" count. R457's `@mutation(table:)` DELETE work (Done this window) did not touch this positional-`null` layer. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` (**695, unchanged this window**) substance holds (emit-half still unbuilt). The non-`@table` arg rejection (`foundTia.argCondition().isPresent()`, `:452`) and the `@condition(override:true)` admission gate (`ARG_OVERRIDE`, imported `:26`, consumed `:509`) hold. Body cites older ranges; re-anchor at the symbols. |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`, under the **Diataxis `docs/architecture/` tree** R182 introduced, not the old flat `docs/` dir. The two cited sibling docs (`docs-as-index-into-tests.md`, `rewrite-docs-entrypoint.md`) do not exist under any extension or location. Relocate onto the Diataxis layout or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (absent) and stale `ConnectionSynthesis` naming. The live seam is `ConnectionPromoter.synthesiseForField` (`ConnectionPromoter.java:122`) / `.rebuildAssembledForConnections` (`:196`) (file directly under `rewrite/`, 498 lines, unchanged this window); re-anchor there and fix the dangling doc link. |
| **R92** catalog-check-constraint-validation | `validatorPreStep` is defined **`TypeFetcherGenerator.java:2201`** (seam call site **`:2069`**, file unchanged this window). No `CheckRecognizer` / `findCheckConstraints` exists; the R12 validator seam this builds on is present as described. `depends-on: [multi-source-input-validation]` (R98) resolves. |
| **R103** lift-jooq-column-defaults-onto-inputs | `DSL.defaultValue` emission is clustered in `buildPerCellValueList` (**`TypeFetcherGenerator.java:2622`**; sibling `buildPerCellValueListDeduped` **`:2795`**, file unchanged). The contract-side gap (no SDL default lift) is unbuilt: the runtime absent-key emission there is VALUES-cell only. R413 (Done a prior window) bound parent-input VALUES cells through the column Converter DataType in this cluster but did **not** address the SDL-default lift R103 targets. |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog.reflectTableMethod` (**base def `ServiceCatalog.java:563`, slot-types overload `:577`**, file unchanged) with the strict return-type gate following, and the stricter-return-rule sibling at `:681`; `buildQueryTableMethodFetcher` in `TypeFetcherGenerator.java` (`:1441`, called `:472`). Neither `MethodRef.StaticOnly` nor `ReturnTypeRef.TableBoundReturnType` carries a type token yet. Re-anchor the `TypeFetcherGenerator` call site at the symbol. |
| **R236** validator-reference-candidate-hint-terminal-table | (Anchors are in the **item body**.) `classifyInputFieldInternal` (**`BuildContext.java:2399`**, was `:2354`) and the candidate-hint failure-aggregation (`candidateHint(c, catalog.columnSqlNamesOf(resolvedTable.tableName()))` at **`:2497`**, was `:2452`) still draw the hint from the path-origin `resolvedTable.tableName()`, not the `@reference` terminal table. R380's Done note still lists R236 as an open sibling. Re-anchor. |
| **R234** jooq-embedded-and-udt-input-backings | Valid, dormant Backlog stub gated behind the R222 collapse (Stages 5-7). Anchors to keep fresh: `JooqRecordInputType` and `JooqTableRecordInputType` are live sibling input arms in `GraphitronType.java` (553, unchanged); the proposed `BackingClass` family is still unbuilt (no `BackingClass.java`). `depends-on: []` correct (R222 is a prose gate, not a hard edge). **Watch:** R431/R432 sit on the same `SourceKey`/leaf surface; re-check R234 when R431 lands. |
| **R35** source-orientation-javadocs | LOC counts grossly stale (FieldBuilder cited "2 172", now **7 276**; TypeFetcherGenerator "1 646", now **6 900**); no `package-info.java` exists in the rewrite tree (sweep undone, re-confirmed). The architecture docs are the Diataxis `docs/architecture/` tree (`.adoc`), and `selection-parser-audit` no longer exists (R30 swept it). Refresh the figures and re-point the doc links onto the Diataxis layout. |

**Re-confirmed Current (not flagged):** **R463** (`ancestor-product-arrival-fold`,
**Spec**, R308's fold residue), **R461** / **R462** / **R466** / **R467** / **R468**
/ **R469** (**Backlog**) all fresh residue/split/upgrade items filed this window.
**R458** (`per-participant-multitable-child-join-paths`, **Ready**), **R443** /
**R447** / **R448** / **R460** (**Backlog**), fresh routine/join/enforcement residue
from prior windows. **R346 / R428 / R429 / R455 / R457 / R459 / R464 / R465** all
reached **Done** this window and self-deleted (verified absent). **R13**
(`faceted-search`, Ready) on the live `ConnectionPromoter` seam, **R381**
(`lsp-reference-path-authoring`, Spec), **R347** (`lsp-structural-consolidation`,
**In Progress**) unchanged. **R222** (`dimensional-model-pivot`, Spec) stays the
umbrella (`depends-on: []`, Stages 5-7 outstanding), with its dedicated
conformance analysis; **R333** (`coordinate-lowers-to-datafetcher-queryparts`,
Spec); **R314** (`dissolve-reentry-leaves-dimensional-emit`, Spec, `depends-on`
R333/R431/R432); **R45** / **R46** (tenant substrate, R45 now fed by R429's
`TenantConnections` carrier); **R430**, **R431**, **R432** all re-verified
spec-forward, none under flag.

## Cross-cutting observations

1. **A closure-heavy window.** 50 commits over three calendar days; the board held
   flat at 134 (`134 - 7 + 7`). Nine items reached Done (R308, R346, R428, R429,
   R455, R457, R459, plus R464 and R465 filed-and-closed in-window); seven new ones
   (R461, R462, R463, R466, R467, R468, R469) stayed. Flag count fell **21 -> 20**
   (R308 retired from §C, no new flag), current rose **113 -> 114**. Zero Done
   tombstones for the ninth window running.
2. **Line-anchor drift was moderate and two-file.** `FieldBuilder.java` (to 7276,
   all in the R308 region) and `BuildContext.java` (to 3061) grew; every §A/§B/§C
   row anchored to them was re-anchored above. The six large generators and
   `ServiceCatalog` / `GraphitronType` / `ChildField` were effectively unchanged,
   so R7/R24/R35/R92/R103/R180/R240/R242/R245 anchors carry over verbatim. No
   flag's *substance* moved, only line numbers.
3. **The runtime-connection and DML-write models fully landed; the source-side
   twin has not.** With R429 (connection/transaction lifecycle) and R457 (DELETE
   write-target) both **Done** this window, the source-side decomposition **R431**
   (`decompose-sourcekey`) is still Backlog; the `SourceKey` surface itself is
   unchanged (Wrap + Cardinality still record components; the documented mapping
   still pins `SourceRowsCall -> Wrap.Row`). When R431 lands, **R71, R234, R314,
   R432** all need re-checking.
4. **No closure this window subsumed a surviving flagged item** (verified at the
   symbol). R308's list-carrier verdict touched `checkServiceReturnMatchesPayload`
   and added `ServiceCarrierShapeError`, not the `@field`-read gaps R201/R202
   target (both still build accessors off the raw SDL name). R464's
   scalar-convention removal did not touch R263's `resolveDecodeHelperForTable`.
   R429's runtime-connection work left R71's `SourceRowsCall -> Wrap.Row` pin and
   R24's `STUBBED_VARIANTS` entry untouched.
5. **No new flag this window, and one flag retired.** R308 (§C) reached Done and
   drops off. This window's deletions retired connection-lifecycle,
   DELETE-write-target, list-payload-carrier, scalar-convention,
   directive-support, and completeness-oracle-wiring behavior; a `depends-on` and
   symbol sweep confirms none of the deleted constructs is named by a flagged or
   unflagged Backlog item. R120 stays flagged from prior windows and R443 remains
   its filed remediation.
6. **`classification-test-dsl-inventory.md`** is doubly stale (R299/R290 + the
   R316 corpus recut) and still warrants the "superseded, historical" banner; it
   has **not** been added (left unedited here per scope).
7. **Cosmetic front-matter nits, none flag-worthy (carried over).** **R97** lacks
   `created:` / `last-updated:` header fields (an older item predating that
   convention). **R92** renders no `last-updated:` in the README rollup. Neither is
   a build or dependency risk.
8. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-13._
