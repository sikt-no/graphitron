# Roadmap staleness audit: 2026-07-09

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `dc70e62`, committed 2026-07-08, audited 2026-07-09). The goal is to find
items whose premise no longer holds: work already shipped, constructs renamed or
removed, dependencies that have since landed, or specs grown stale enough to
mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-08` staleness audit, which has been deleted;
only the latest staleness audit is retained. Four siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  meant to be edited in place as scope iterates. It reads yet further behind this
  window: it names none of the routine-composition or `@reference`-identity items
  that closed since it was last touched (R435, R444, R445, R422, R440, R441,
  R442, R450), so its MUST/SHOULD tables lag further. Refreshing it is out of
  scope for this staleness pass.
- `2026-07-04-r222-r333-conformance-analysis.md` is the R222/R333 conformance
  analysis, a companion to the R314/R333 design session. It is a targeted
  implementation-vs-spec conformance record, not a point-in-time staleness
  review; left in place.
- `classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
  inventory, doubly stale (against R299/R290 and the R316 corpus recut). It still
  warrants a "superseded, historical" banner; that banner has **not** been added,
  left unedited here per scope (see observation 6).

## Changes since the 2026-07-08 audit

A **routine-composition and `@reference`-identity** window, a marked contrast with
the prior audit's structural-landing pass. Two things dominate:

**1. The routine-composition family landed.** **R435**
(`routine-table-node-composition`), described **Ready** at the prior snapshot,
drove to **Done**: order-significant `@routine` / `@reference` composition, in
which a jOOQ table-valued function is a table *node* on R438's two-axis `Hop`
substrate. It added `TableExpr.RoutineCall`, the positive `On.Lateral` arm,
`On.Keying` (`ForeignKey | NameMatchedKey`), `ParentCorrelation.OnLateralArgs`,
and a `ParamSource.SourceColumn` arm, and re-homed the root
`QueryRoutineTableField` onto the `(start, hops)` chain (R300 desugars to
`hops = []`). It re-homed its own residue into three new items (**R447**,
**R448**, and the classification edges **R449**) and filed the mutation arm
**R451**.

**2. The schema-qualified `@table` bug class closed.** A dense cluster reached
**Done** this window: **R444** (scalar `@reference` terminal column read by
FK-pinned `TableRef` identity), **R445** (participant cross-table `@reference` by
identity), **R422** (return-type identity verdict), and **R440 / R441 / R442**
(FK-join endpoints, typed-accessor match, condition-param match). All decide the
terminal by carried jOOQ class identity instead of re-resolving a bare SQL name
through the catalog. **R450** (split hop-0 filter binds the parent alias via the
parent-anchor arm) also closed.

**Terminal closures this window (Done, all self-deleted their files, none was
under flag, all were current):** **R438** (`materialize-joinpath-facts`, was In
Review; absorbed and closed R16), **R435** (`routine-table-node-composition`),
**R444**, **R445**, **R422**, **R440**, **R441**, **R442**, **R450**. Every one
landed on current/fresh code.

**New items on the board (filed this window, all freshly written, none stale):**

- **R443** (`post-r438-stale-reference-residue`, **Backlog**): R438-review residue,
  a `ConditionResolution` javadoc correction plus a note that the
  `fkjoin-alias-dead-storage` item (**R120**) went stale (see §B).
- **R446** (`array-column-typename-codegen`, **Ready**): fix a codegen crash on
  array-typed columns (lift a `TypeName` at the jOOQ catalog boundary instead of
  `ClassName.bestGuess` on a binary array descriptor); correctly attributes the
  regression to R436 and cites the now-closed `@table` bug class.
- **R447** (`routine-chain-fetch-form-breadth`, **Backlog**): the four typed
  `Deferred` fetch-form extensions R435 left (multi-routine chains, record-backed /
  `TableInterfaceType` parents, `@lookupKey`).
- **R448** (`routine-chain-residue`, **Backlog**): non-gating R435 residue (root
  ordering reconciliation, correlated value-arg `DataType` binding, corpus
  migration).
- **R449** (`routine-chain-classification-edges`, **In Review**): classification
  and validation edges from R435's second-pass review; its minted `planSlug`s all
  resolve (R451 / R447 / R448).
- **R451** (`routine-mutation-write`, **Spec**): `@routine` on `Mutation` commits
  the write before the follow-up query, riding the DML two-step;
  `depends-on: [routine-chain-classification-edges]` (R449) resolves.

**Board accounting.** **131 item files** today, up 3 from the prior audit's 128.
Status distribution: **108 Backlog, 17 Spec, 4 Ready, 1 In Progress (R347),
1 In Review (R449); zero Done**. The `131` reconciles as `128 - 3 + 6`: three
older-id closures net off the board (R422, R435, R438), six new items stay on it
(R443, R446, R447, R448, R449, R451), and the remaining new-range closures
(R440, R441, R442, R444, R445, R450) each filed-and-deleted in-window, netting
zero. A non-recursive `^status: Done` grep over `roadmap/*.md` returns nothing
(tombstone-free for the seventh window running; R449's file is present as In
Review, not a Done tombstone). No duplicate `id:`; `next-id: R452` (in
`changelog.md`) with max allocated id **R451**. A `depends-on` sweep over all 131
files found **no dangling slugs**: the eleven items carrying a non-empty
`depends-on` all resolve, and no edge points at a now-deleted slug
(`materialize-joinpath-facts`, `routine-table-node-composition`, or any closed
`@table`-class item). The prior audit's R435 edge onto `materialize-joinpath-facts`
left the board with R435. The board is structurally clean.

**Net effect on flag counts: 21 flagged, 110 current.** The flag count rises by
one from the prior window's 20: every one of the prior 20 flags was re-verified
against current HEAD and **still reproduces** (line anchors re-anchored below,
since `BuildContext.java`, `FieldBuilder.java`, and `TypeFetcherGenerator.java`
all grew materially this window), and **R120** (`fkjoin-alias-dead-storage`) is
newly flagged: R438's cutover deleted the `FkJoin` / `ConditionJoin` types its
whole body names. Flag composition is now **(1 / 7 / 13)**: §A is R263 alone; §B
gains R120 for seven; §C holds its thirteen. **No closure or transition this
window subsumed any flagged item** (verified at the symbol, not inferred from
adjacency): the `@reference`-identity cluster fixed terminal *resolution*, not the
input-field candidate *hint* R236 targets, and R435's routine work did not touch
R24's `STUBBED_VARIANTS` entry or R71's `SourceRowsCall -> Wrap.Row` pin.

## Scope and method

All **131** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded). For each flagged
item the targets it names (classes, directives, methods) were located in the
current tree under `graphitron/src/main/java/no/sikt/graphitron/rewrite/`, the
described problem was checked for whether it still reproduces, and the changelog
was scanned for the item's `R<n>` and key terms to catch work that shipped without
the item being closed.

**The `SourceKey` decomposition still has not landed.** It remains a filed,
sequenced item (**R431**, Backlog), with R432 depending on it and R314 depending
on both. `model/SourceKey.java` still carries `Wrap` (Row / Record / TableRecord)
and `Cardinality` (ONE / MANY) as record components and still pins
`SourceRowsCall -> Wrap.Row` (enforced at `SourceKey.java:124`, throws otherwise).
R435's `ParamSource.SourceColumn` addition did not alter that pin. So the Row-only
asymmetry R71 wants to remove is intact, and R71 / R234 / R314 / R432 all still
need re-checking when R431 lands.

**Line-anchor drift this window: wide.** The routine-composition and
`@reference`-identity work grew the three large parse-boundary files:

- **`BuildContext.java` grew to 3016** (prior audit `2689`). §B/§C rows anchored
  to it were re-anchored below.
- **`FieldBuilder.java` grew to 6819** (prior `6373`). §B/§C rows re-anchored.
- **`TypeFetcherGenerator.java` grew to 6725** (prior `6673`). Rows re-anchored.
- **`ServiceCatalog.java`** changed (R240's `reflectTableMethod` region moved).
- **`GeneratorUtils.java` (578), `FetcherEmitter.java` (763),
  `MutationInputResolver.java` (679), `ConnectionPromoter.java` (498)** are
  unchanged this window; their anchors carry over verbatim.

Refreshed anchors (2026-07-09 line numbers) appear inline in the §A/§B/§C tables.

**Result: 21 items flagged, 110 current.**

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Action | Why |
|---|---|---|---|
| **R263** decode-helper-typename-first-resolution | Backlog | **Discard as speculative** (re-open only if a MUST-route `@nodeId(typeName:)` caller appears) | Re-verified on current HEAD, unchanged in substance. `resolveDecodeHelperForTable` is now defined `BuildContext.java:2770`; it resolves through the `@node`-only `NodeIndex` (`nodes.forTable`), returns `null` on multi-node ambiguity (`:2783-2788`, a validate-time rejection, not the silent `decode<firstType>` the finding described), and falls back to the `typeId` suffix only when no `@node` backs the table. Its callers (`BuildContext.java:2582` bare-`@nodeId`, `:2720` `@nodeId(typeName:)` input field) both pass the table name as primary; **none** routes an authoritative `@nodeId(typeName:)`. R377 settled the open question the discard hedged on. The premise survives only in spirit and no consumer needs it. Re-anchor onto the R377 body if capturing the finding in `changelog.md`, then discard. |

## B. Outdated: needs re-spec (premise or targets materially changed) (7)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. Six carried over
from the prior audit and were re-verified this window; **every one still
reproduces**. **R120 is new this window**, rendered stale by R438's flat-variant
deletion.

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; still the sharpest.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm R276 deleted: `ResultType` is a 4-arm seal (`permits JavaRecordType, PojoResultType, JooqRecordType, JooqTableRecordType`, `GraphitronType.java:93-95`), and `PojoResultType` permits only `Backed` (`:119-120`). Its primary migration target `FetcherEmitter.propertyOrRecordValue` **still does not exist** (zero hits); only the `GeneratorUtils.buildFkRowKey` half of the duplication survives, at **`generators/GeneratorUtils.java:274`, called `:245`** (that file unchanged this window). Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. |
| **R120** fkjoin-alias-dead-storage | Backlog | **New flag: R438 deleted the types this item names.** The body targets `FkJoin.alias` and the sibling `ConditionJoin.alias`, populated by `BuildContext.synthesizeFkJoin` at a cited `BuildContext.java:694`. R438's cutover **deleted the flat `FkJoin` / `ConditionJoin` variants** (only a javadoc mention survives, `model/JoinStep.java:89`); the alias concern moved onto `JoinStep.Hop.alias` (`:82`, component `:122`) and `LiftedHop.alias` (`:181`). `synthesizeFkJoin` now lives at `BuildContext.java:1501` and returns a `FkJoinResolution`; `BuildContext.java:694` is now an unrelated R310 probe; `JoinPathEmitter.generateAliases` is at `:44` (cited `:41`). The dead-storage question survives the reshape, but under new components. **R443** (Backlog) was filed to remediate exactly this; fold R120 into R443, or re-spec R120's prose onto `Hop.alias` / `LiftedHop.alias`, or retire it if the alias is now consumed. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | **Substance confirmed still future; symbols need re-anchor.** Every symbol the mechanism names is dead: no `BatchKeyLifterDirectiveResolver`, `LifterRowKeyed`, or `RowKeyed` in main src (the only textual hit is prose in an error string, `FieldBuilder.java:6124`). The live surface is `model/LifterRef.java`, routed through `@sourceRow` and `SourceKey.Reader.SourceRowsCall(LifterRef)`; the `SourceRowsCall -> Wrap.Row` pin (`SourceKey.java:124`) is the exact Row-only asymmetry R71 wants to remove, and R438/R435 did not touch it. Re-anchor on `LifterRef` / `SourceRowsCall` / `Wrap`. The decomposition R71 rides is filed as **R431**; wire R71's `depends-on` / prose to R431 on its next touch. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (Done), so the "R58 is currently *In Review* ... if it reverts" hedge is false and should be struck. Carriers are still String-flattened: `record ParsedPath(..., String errorMessage, ...)` at `BuildContext.java:992`, and `record Unresolved(..., String reason)` at `InputFieldResolution.java:20-23`; the carrier-audit body stays valid, `depends-on: []` correct. Lightest re-spec in this section: strike the reversion hedge. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)" (front-matter `title:` `:3`, heading `:13`, body `:18-25`); **R94 shipped** (Done, slug `emit-input-records`, file absent). Blocker cleared, drop the blocked framing. R94's Done note handed the real remaining dependency to **R98** (`multi-source-input-validation`, Backlog, file present), so re-point the dependency there and change the title "(R94-blocked)" to "(R98-blocked)". |
| **R24** nodeidreferencefield-join-projection-form | Backlog | Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **still does not exist** (dispatch is `FetcherEmitter.bind` `:179` / `bindRaw` `:359`; `dataFetcherValue` survives only in stale javadoc, `ChildField.java:1127`/`1154`), and the carrier in the **item title**, `NodeIdReferenceField`, was renamed by R50 (zero hits in main src). The surviving "not yet implemented" stub is `ChildField.CompositeColumnReferenceField`, the **single** entry in `TypeFetcherGenerator.STUBBED_VARIANTS` (declared `:301`, the `Map.entry` `:309-314`), whose `planSlug` is R24's own slug. R435 did not touch it. Work is valid and unbuilt, but the title symbol and method names need a full re-anchor. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines" and "~30 private methods"; `TypeFetcherGenerator.java` is **6 725** this window (~4.08x) with roughly **189** private-method declarations. Lightest-substance re-spec in this section: refresh the LOC/method-count figures and re-survey the cut against the real file, or better, stop hard-coding a count and state "measure at execution time." |

## C. Outdated: update references only (work valid, refs stale) (13)

Substance intact; only paths, line numbers, or dependency tense drifted. **This
window the drift widened**: the routine-composition and `@reference`-identity work
touched `BuildContext.java` (3016), `FieldBuilder.java` (6819),
`TypeFetcherGenerator.java` (6725) and `ServiceCatalog.java`, so rows on those
files were re-anchored. The recurring code-side root cause is unchanged: the three
large generators standing at ~2.6 to 4.08x the sizes the older specs cite.

| Item | Stale reference |
|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Premise live and unbuilt: `resolvePayloadConstructionShape` on the PayloadClass arm (`FieldBuilder.java:516`; called `:2923`); the mutable-bean logic in `tryMutableBean` (`:581`), whose setter-match arm builds `SetterBinding`s off the raw SDL field name (`sdlFieldNames` from `getName()` at `:2920-2922`, `SetterBinding(sdlFieldName, ...)` at `:619-620`) with **no `@field` read**. The emitter halves are `TypeFetcherGenerator.payloadFactoryLambda` (`:6592`) and `payloadFactoryLambdaSetters` (`:6609`). Body already frames it as "the output-side mirror of R200"; all anchors drifted this window. |
| **R308** service-list-payload-arrival | The `@service` list-payload N+1 is real and unbuilt (`checkServiceReturnMatchesPayload` `FieldBuilder.java:3385`, called `:3346`; still admits `List<Payload>` via the `equals`-and-return-null path at `:3416`). The body still describes the no-DataLoader shape via `SingleRecordTableField`, which R305 **deleted** (every occurrence is now "former SingleRecordTableField" prose); the live carrier `RecordTableField` is `model/ChildField.java:905`. Strip the "today" framing; bump `last-updated`. (`depends-on` already `[]`.) |
| **R202** honor-field-directive-in-error-type-source-accessors | Premise live and unbuilt: `checkErrorTypeSourceAccessors` (`FieldBuilder.java:3127`, called `:2902`) calls `ClassAccessorResolver.resolve(...)` (`:3146`) with the raw SDL name (`sdlField.getName()` `:3148`) and **no `@field` read**, exactly the gap R202 describes. R200's Done note names R202 as a remaining `@field`-symmetry sibling. (A sibling `HandlerAccessorCheck.java` under `walker/internal/` documents an absorbed variant, but the live call site still uses the raw name.) Re-anchor at the symbol. |
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck as "Obsolete (R287)"; body re-based onto R305's re-fetch substrate; `depends-on` `[]`. `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` (`FetcherEmitter.java:631`, called `:373`, **unchanged this window**) and the `[ID]`-reject scoped to `CarrierFamily.DML` in `BuildContext.java` (guard `:796`, message `:801`, vs the SERVICE admit at `:791-793`) still need re-anchoring at the symbol; drop the stale "3 sites" count. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` (**679**, unchanged this window) substance holds (emit-half still unbuilt). The non-`@table` arg rejection (`foundTia.argCondition().isPresent()`, `:436`) and the `@condition(override:true)` admission gate (`ARG_OVERRIDE`, imported `:26`, consumed `:493`) hold. Body cites older ranges; re-anchor at the symbols (drift is minimal here). |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`, under the **Diataxis `docs/architecture/` tree** R182 introduced, not the old flat `docs/` dir. The two cited sibling docs (`docs-as-index-into-tests.md`, `rewrite-docs-entrypoint.md`) do not exist under any extension or location. Relocate onto the Diataxis layout or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (absent) and stale `ConnectionSynthesis` naming. The live seam is `ConnectionPromoter.synthesiseForField` (`ConnectionPromoter.java:122`) / `.rebuildAssembledForConnections` (`:196`) (file directly under `rewrite/`, 498 lines, unchanged this window); re-anchor there and fix the dangling doc link. |
| **R92** catalog-check-constraint-validation | `validatorPreStep` is defined **`TypeFetcherGenerator.java:2036`** (seam call site **`:1904`**); `DefaultValidatorHolder` / `getValidator` live in `generators/util/GraphitronContextInterfaceGenerator.java:84`/`:95`. No `CheckRecognizer` / `findCheckConstraints` exists; the R12 validator seam this builds on is present as described. `depends-on: [multi-source-input-validation]` (R98) resolves. |
| **R103** lift-jooq-column-defaults-onto-inputs | `DSL.defaultValue` emission is clustered in `buildPerCellValueList` (**`TypeFetcherGenerator.java:2457`**; sibling `buildPerCellValueListDeduped` **`:2630`**). The contract-side gap (no SDL default lift) is unbuilt: the runtime absent-key emission there is VALUES-cell only, and the schema-generator `defaultValueProgrammatic` calls emit existing SDL defaults rather than lifting jOOQ column defaults onto inputs. R413 (Done a prior window) bound parent-input VALUES cells through the column Converter DataType in this cluster but did **not** address the SDL-default lift R103 targets. |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog.reflectTableMethod` (**base def `ServiceCatalog.java:563`, slot-types overload `:577`**) with the strict `ClassName.equals` return-type gate following (`:598-599`); `buildQueryTableMethodFetcher` **`TypeFetcherGenerator.java:1439`** (called **`:471`**). Neither `MethodRef.StaticOnly` (`model/MethodRef.java:170`, `returnType` a bare `ClassName`) nor `ReturnTypeRef.TableBoundReturnType` (`model/ReturnTypeRef.java:44`, carries only a `returnTypeName` String) carries a type token yet. Re-anchor at the symbols. |
| **R236** validator-reference-candidate-hint-terminal-table | (Anchors are in the **item body**.) `classifyInputFieldInternal` (**`BuildContext.java:2354`**) and the candidate-hint failure-aggregation (`candidateHint(c, catalog.columnSqlNamesOf(resolvedTable.tableName()))` at **`:2452`**, in the nested-input branch `:2444-2455`) still draw the hint from the path-origin `resolvedTable.tableName()`, not the `@reference` terminal table. **Explicitly not subsumed by R444/R445:** those touched the `@reference` arm (`:2391-2418`), which carries no candidate hint at all (the bare message at `:2416`), so the terminal-table enumeration never reached this site. R380's Done note still lists R236 as an open sibling. Re-anchor. |
| **R234** jooq-embedded-and-udt-input-backings | Valid, dormant Backlog stub gated behind the R222 collapse (Stages 5-7). Anchors to keep fresh: `JooqRecordInputType` is a live populated input arm (`GraphitronType.java:342`); `JooqTableRecordInputType` is a separate sibling (`:357`); the proposed `BackingClass` family is still unbuilt (no `BackingClass.java`; `parentBackingClass` exists only as a component on `AccessorRef.java:41`). `depends-on: []` correct (R222 is a prose gate, not a hard edge). **Watch:** R431/R432 sit on the same `SourceKey`/leaf surface; re-check R234 when R431 lands. |
| **R35** source-orientation-javadocs | LOC counts grossly stale (FieldBuilder cited "2 172", now **6 819**; TypeFetcherGenerator "1 646", now **6 725**); no `package-info.java` exists in the rewrite tree (sweep undone, re-confirmed). The architecture docs are the Diataxis `docs/architecture/` tree (`.adoc`), and `selection-parser-audit` no longer exists (R30 swept it). Refresh the figures and re-point the doc links onto the Diataxis layout. |

**Re-confirmed Current (not flagged):** **R449** (`routine-chain-classification-edges`,
**In Review** this window), the routine-composition classification/validation edges;
its `planSlug`s (R451/R447/R448) all resolve, and its deferred-to-R450
`SplitRowsMethodEmitter` javadoc carve-out is now satisfied (R450 Done). **R446**
(`array-column-typename-codegen`, **Ready**), fresh. **R451**
(`routine-mutation-write`, **Spec**), fresh, `depends-on` R449. **R447 / R448 / R443**
(**Backlog**), fresh residue/cleanup items. **R429**
(`connection-transaction-lifecycle`, **Ready**), **R428**
(`mcp-execute-query-in-process`, **Spec**), **R381** (`lsp-reference-path-authoring`,
**Spec**), all unchanged. **R347** (`lsp-structural-consolidation`) holds at **In
Progress**. **R222** (`dimensional-model-pivot`, Spec) stays the umbrella
(`depends-on: []`, Stages 5-7 outstanding), with its dedicated conformance analysis;
**R333** (`coordinate-lowers-to-datafetcher-queryparts`, Spec); **R314**
(`dissolve-reentry-leaves-dimensional-emit`, Spec, `depends-on` R333/R431/R432);
**R13** (`faceted-search`, Ready) on the live `ConnectionPromoter` seam; **R45** /
**R46** (tenant substrate); **R346**, **R430**, **R431**, **R432** all re-verified
spec-forward, none under flag.

## Cross-cutting observations

1. **A routine-composition and identity-fix window.** After the prior structural
   landing, this window shipped R435's order-significant `@routine` / `@reference`
   composition to Done and closed the schema-qualified `@table` bug class
   (R422/R440/R441/R442/R444/R445), plus R450. Net flag count rose **20 -> 21**
   (all 20 prior flags survive; R120 is new); current rose **108 -> 110** on a board
   that grew 128 -> 131. Zero Done tombstones for the seventh window running.
2. **Line-anchor drift was wide.** `BuildContext.java` (to 3016), `FieldBuilder.java`
   (to 6819), and `TypeFetcherGenerator.java` (to 6725) all grew; every §B/§C row
   anchored to them was re-anchored above. No flag's *substance* moved, only line
   numbers. Rows on the unchanged `GeneratorUtils.java` (578), `FetcherEmitter.java`
   (763), `MutationInputResolver.java` (679) and `ConnectionPromoter.java` (498)
   carry over verbatim.
3. **The join-path and routine models landed; the source-side twin has not.** With
   R438 (join-path) and R435 (routine composition) both Done, the source-side
   decomposition **R431** (`decompose-sourcekey`) is still Backlog; the `SourceKey`
   surface itself is unchanged (Wrap + Cardinality still record components; the
   documented mapping still pins `SourceRowsCall -> Wrap.Row`). When R431 lands,
   **R71, R234, R314, R432** all need re-checking.
4. **No closure this window subsumed a flagged item.** The `@reference`-identity
   cluster fixed terminal *resolution* by carrying jOOQ class identity; it did not
   touch the input-field candidate *hint* R236 targets (verified at
   `BuildContext.java:2452`, which still reads the path-origin table). R435's
   routine work left R24's single `STUBBED_VARIANTS` entry and R71's
   `SourceRowsCall -> Wrap.Row` pin untouched. Flags on the touched files (R201,
   R202, R236, R308, R92, R103, R240) are distinct defects on distinct methods,
   verified at the symbol.
5. **R120 is the one new flag, and it is already tracked.** R438's flat-variant
   deletion stranded `fkjoin-alias-dead-storage` (R120), whose whole body names the
   deleted `FkJoin` / `ConditionJoin` types; **R443** was filed in the same review to
   remediate it (refresh onto `Hop.alias` / `LiftedHop.alias`, or retire). Fold or
   re-spec on next touch.
6. **`classification-test-dsl-inventory.md`** is doubly stale (R299/R290 + the R316
   corpus recut) and still warrants the "superseded, historical" banner; it has
   **not** been added (left unedited here per scope).
7. **Cosmetic front-matter nits, none flag-worthy (carried over).** **R97** lacks
   `created:` / `last-updated:` header fields (an older item predating that
   convention). **R92** renders no `last-updated:` in the README rollup (blank in
   the generated table). Neither is a build or dependency risk.
8. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-09._
