# Roadmap staleness audit: 2026-07-01

A point-in-time review of every active roadmap item under
[`graphitron-rewrite/roadmap/`](../) against the **current** state of the
codebase on `claude/graphitron-rewrite` (HEAD `885928d7`, 2026-06-30 22:38). The
goal is to find items whose premise no longer holds: work already shipped,
constructs renamed or removed, dependencies that have since landed, or specs
grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-06-29` staleness audit, which has been deleted;
only the latest staleness audit is retained. Three siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
  It is a closed-work lineage record, not a point-in-time staleness review.
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  a companion to this audit. It is meant to be edited in place as scope iterates.
  Note it predates several closures this window (R269, R99, R262, R400 have since
  reached Done), so its MUST/SHOULD tables read one step behind; refreshing it is
  out of scope for this staleness pass.
- `classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
  inventory, doubly stale (against R299/R290 and against the R316 corpus recut).
  It still warrants a "superseded, historical" banner; that banner has **not**
  been added, left unedited here per scope (see observation 6).

## Changes since the 2026-06-29 audit

**106 commits** landed between the prior audit's review point (`5a03de2d`,
committed 2026-06-28 08:15) and this one (HEAD `885928d7`, 2026-06-30 22:38). This
window did what the prior audit projected would eventually happen but had not yet:
**the entire §A "obsolete" section was cleared by real transitions, and three more
flagged items left the flagged set** (two closed, one cleared by re-spec).

**§A cleared in full (the roadmap-hygiene backlog got worked):**

- **R30** `selection-parser-audit` (the stranded Done tombstone) was **swept and
  its file deleted** (`b0760590`, "R30 sweep"). A non-recursive `^status: Done`
  grep over `roadmap/*.md` now returns **nothing**: zero stranded Done tombstones
  for the first time in this audit series.
- **R146** `mutation-cardinality-safety-unique-index` and **R52**
  `lift-operation-taxonomy` both went **Backlog → Discarded** and their files were
  deleted (`502d800c`). Both closures match the prior audit's §A recommendation
  exactly (R146 absorbed by R246; R52 obsoleted-as-carrier-build-item by R316).

**Flagged items resolved this window (four left the flagged set):**

- **R269** `nullable-to-one-record-into-npe` (was §B) → **Done, file deleted**
  (`077f8f3e` implementation, `03308378` In Review → Done). The success-arm NPE
  the prior audit noted as "still unguarded" is now guarded in
  `GeneratorUtils.buildAccessorKeySingle`/`buildAccessorKeyMany`.
- **R121** `lsp-diagnostic-redundant-splitquery-on-record` (was §B/§C) and **R296**
  (deprecated-directive BuildWarning) were **retired as superseded by R398** and
  their files deleted (`56f90b91`); their intent folds into R398's starter
  lint-visitor set (visitors 9 and 8). IDs retired, not reused.
- **R99** `lsp-submodule-sibling-classpath` (was §C) → **Done, file deleted**
  (`e6df34d2` implementation, `36e0a9d8` In Review → Done). The sibling-module
  LSP scan/walk widening the §C row tracked has shipped.
- **R261** `wire-coercion-cast-guard` (was §B) → **resolved out of the flagged
  set** by a targeted re-spec (Backlog → Spec, `8fba923c` + reviewer revision
  `0d9c194c`). The rewrite re-anchors the R58/R195 coordination to past tense
  ("already guarded, R195/R311/R315 Done"), lays out deferred sites B–E on
  accurate current symbols (`InputBeanResolver.bindField:743`,
  `ServiceCatalog.argExtraction:795`, the correctly-spelled
  `InputBeanInstantiationEmitter`), and folds in the R360 enum-name-divergence
  edge as designed. **The spec no longer misleads, R261 is now a current Spec
  item, the second flagged item in this series cleared by re-spec rather than by
  code shipping (R13 was the first).**

**Tier move (one downgrade):**

- **R234** `jooq-embedded-and-udt-input-backings` was **downgraded §B → §C.** The
  prior audit flagged it because its body claimed R276 had already collapsed
  `JooqRecordInputType`; the body has since been rewritten to correctly attribute
  the collapse to **R222** (still Spec, Stages 5–7 outstanding) and to describe
  `JooqRecordInputType` as the live legacy arm it in fact is (`GraphitronType.java:342`,
  permitted at `:303-304`; `JooqTableRecordInputType` sibling `:357`; no
  `BackingClass.java`). The "premise factually inverted" defect is gone; what
  remains is a valid, dormant item gated behind the R222 collapse, with only
  freshening needed.

**Terminal closures this window (Done, all self-deleted their files, none
flagged, all were current):** R256 (`service-walker-substrate`), R262
(`@nodeId`-on-non-ID rejection), R378 (`nodeid-filter-malformed-vs-mismatched`),
R389 (`discriminated-joined-table-inheritance`), R395
(`discriminator-column-from-clause-qualification`), R399
(`graphitron-jakarta-rest` GraphQL-over-HTTP library), R400
(`withhold-not-in-use-directives`), R401 (`bundle-tree-sitter-runtime`). R389
grew `TypeFetcherGenerator.java` the most (its joined-table-inheritance emitter is
the dominant cause of the **+191** growth this window, to **6 387**).

**New items still on the board (four filed this window, all current/fresh):**
R397 (`error-directive-on-query-fields`, Backlog), R402
(`retire-bean-helper-queue-valueshape-roundtrip`, Backlog), R403
(`reintroduce-tablemethod-docs`, Backlog, deferred recovery ticket for R400), R404
(`reintroduce-sourcerow-docs`, Backlog, deferred recovery ticket for R400). All
re-verified spec-forward on fresh code; every named anchor resolves; no dangling
`depends-on`.

**Board accounting.** Prior window closed at 130 items; this window deleted eight
files by closure/transition (R30, R146, R52, R269, R99, R121, R296 as flagged
departures, plus R256/R262/R378/R389/R395/R399/R400/R401 Done closures), filed
four new (R397, R402, R403, R404), for **124** item files today. Status
distribution: **97 Backlog, 19 Spec, 5 Ready, 3 In Progress; zero Done**
(tombstone-free). A full `depends-on` sweep across all 124 files found **no
dangling slugs** (every non-empty edge, `dimensional-model-pivot` ×2,
`consumer-derived-input-tables`, `catalog-check-constraint-validation`,
`capability-catalog`, `sis-rewrite-migration`, `tenant-routing-and-execution-input`,
`retire-maven-plugin`, resolves to a live file). No duplicate `id:`; `next-id:
R405` with max allocated id R404. The board is structurally clean.

**Net effect on flag counts: 23 flagged, 101 current** (prior window: 30/100).
Flag composition moved from (3 / 14 / 13) to **(2 / 8 / 13)**: §A cleared in full
and was repopulated by two items whose recommended action is now firmly "leave the
board" (R19, superseded by R182; R263, discard-as-speculative). §B shrank from 14
to 8 (R269/R121 closed, R261 cleared, R234 → §C, R19/R263 → §A). §C held at 13
(R234 arrived, offsetting R99's closure). Every closure and every new item this
window landed on current/fresh code; none under flag.

## Scope and method

All **124** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder). For each item the targets it names
(classes, directives, methods, packages, modules) were located in the current
tree under `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/`,
the described problem was checked for whether it still reproduces, and the
changelog was scanned for the item's `R<n>` and key terms to catch work that
shipped without the item being closed.

Every flagged item carried over from the prior audit was re-verified against
current HEAD. The structural landings the prior audits relied on all still hold:
**R276** (`ResultType` is a 4-arm seal at `GraphitronType.java:93-94`;
`PojoResultType` permits only `Backed` at `:119-120`), **R290**
(`LeafTupleAdapter` / `ConstructorField` dissolved), **R305**
(`SingleRecordTableField` gone, re-confirmed as neither a declared type nor a
pattern-match arm anywhere; live carrier is `RecordTableField`,
`model/ChildField.java:829`), and **R316** (the `carrier × intent × mapping` field
model gone, replaced by `(source, operation, target)`; `model/Operation.java` is a
sealed interface). The **`SourceKey` `Wrap` decomposition still has not landed**
(`SourceKey.java:81-82` still carries `Wrap wrap` + `Cardinality cardinality`), so
the standing R71 / observation 4 watch holds.

Line-anchor drift this window was concentrated in **one** file: R389's
joined-table-inheritance emitter grew **`TypeFetcherGenerator.java`** to **6 387**
(**+191**). `BuildContext.java` (**2 675**, +3), `FieldBuilder.java` (**6 293**,
+3), `GraphitronSchemaValidator.java` (**1 343**, held), `MutationInputResolver.java`
(**679**, held), and `FetcherEmitter.java` (**763**) barely moved. The
TypeFetcherGenerator-anchored §C rows (**R92**, **R103**, **R201**, **R240**) took
almost all of this window's re-anchor cost; the BuildContext/FieldBuilder-anchored
rows are nearly stable this window (a reversal from the prior two windows).

**Result: 23 items flagged, 101 current.** Line numbers cited below are as of the
review date and will themselves drift; see observation 3.

## A. Obsolete: should leave the active roadmap (2)

The prior audit's three §A items (R30, R146, R52) were **all actioned this
window** and are gone (see "Changes since"). §A is repopulated by two items whose
recommended action, after this window's evidence, is no longer "re-spec" but
"leave the board."

| Item | Status | Action | Why |
|---|---|---|---|
| **R19** history-squash | **Ready** | **Discard / close (verify supersession by R182 first)** | The prior audit flagged this §B ("regenerate all numbers/SHAs, or compute dynamically"). This window makes the recommendation firmer: **R182** (`unnest-rewrite-aggregator`, Spec) states at line 37 that it is "**not** routing this through an R19-style history squash (**R19 is abandoned**)," and HEAD commits `885928d7` / `e194f9e9` corroborate ("no R19 squash"). So a live Spec item now explicitly supersedes R19's approach. Independently, every number in R19 is a April-2026 snapshot gone wildly stale: it cites "595 (as of 2026-04-24)" and a "566-commit history" against a merge base `ab3daff2` and trunk tip `8a8c5efe`; the branch is now **3 358 ahead** of `origin/main`, current merge base **`65299a7b`**. A Ready item that a Spec item declares abandoned should not stay Ready. Confirm R182 is the accepted path, then discard/close R19. |
| **R263** decode-helper-typename-first-resolution | Backlog | **Discard as speculative** (re-open only if a MUST-route `@nodeId(typeName:)` caller appears) | The prior audit already recommended discard while keeping it in §B; this window promotes it to §A because **R377 settled the open question the discard was hedged on.** `resolveDecodeHelperForTable` moved to `BuildContext.java:2436` (R377 rework); it now resolves through the `@node` `NodeIndex` (`nodes.forTable(...)`), returns `null` on multi-node ambiguity (`:2449-2453`), a validate-time rejection, not the silent `decode<firstType>` the prior finding described, and falls back to the `typeId` suffix only when no `@node` backs the table. The four callers (`BuildContext.java:2248`/`:2386`, `FieldBuilder.java:1317`, `NodeIdLeafResolver.java:275`) all pass the table name as primary; **none** routes an authoritative `@nodeId(typeName:)`. The premise survives only in spirit and no consumer needs it. Re-anchor onto the R377 body if capturing the finding in `changelog.md`, then discard. (Note: R377 has no roadmap `.md` file; it is referenced only in code javadoc / changelog.) |

## B. Outdated: needs re-spec (premise or targets materially changed) (8)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. All eight carry
over from the prior audit and were re-verified this window; **every one still
reproduces.** Six §B rows from the prior audit left this section this window:
**R269** and **R121** (closed), **R261** (cleared by re-spec, now a current Spec
item), **R234** (downgraded to §C), and **R19** + **R263** (promoted to §A, their
recommended action is now discard). Line-anchor drift this window was minimal for
this section (BuildContext/FieldBuilder both held to +3).

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; still the sharpest.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm R276 deleted: `ResultType` is a 4-arm seal (`GraphitronType.java:93-94`), `PojoResultType` permits only `Backed` (`:119-120`). Its primary migration target `FetcherEmitter.propertyOrRecordValue` **no longer exists** (zero hits; the spec's own `:802`/`:131` anchors are dead); only the `GeneratorUtils.buildFkRowKey` half of the duplication survives (`:241`, called `:212`). The `.md` still describes the deleted five-arm / `PojoResultType.NoBacking` model throughout (lines 30-32, 39, 119). Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | **Symbol re-anchor; substance confirmed still future, and the standing structural watch.** Every symbol the mechanism names is dead: no `BatchKeyLifterDirectiveResolver`, no `BatchKey`/`LifterRowKeyed`/`RowKeyed` types anywhere. The live surface is `model/LifterRef.java:25`, routed through `@sourceRow` (`SourceRowDirectiveResolver.java:72`) and `SourceKey.Reader.SourceRowsCall(LifterRef)` (`SourceKey.java:284`). R316 did **not** collapse `wrap`+`cardinality` out of `SourceKey` (`SourceKey.java:81-82` still carries `Wrap wrap` + `Cardinality cardinality`), and the compact constructor still pins `SourceRowsCall → Wrap.Row` (`:124-127`), the exact Row-only asymmetry R71 wants to remove. Re-anchor on `LifterRef`/`SourceRowsCall`/`Wrap`. |
| **R46** service-multi-tenant-fanout | Backlog | **The dependency abandoned the design R46 extends, and this held all window.** `ContextValueRegistration`, `getContextFanOut`, `openContextDslContext`, `getExecutor` all have **zero** `.java` hits repo-wide (R190 sealed `GraphitronContext`). R46's dependency **R45** (`tenant-routing-and-execution-input`, Ready) ships a per-field `TenantIdSource` overlay + `byTenant` on `GraphQLContext`, **not** the sealed `ContextValueRegistration` the whole "Design" section rests on. The `depends-on:` correctly uses the live slug `tenant-routing-and-execution-input`, but the body prose still links the dead `typed-context-value-registry.md` slug at lines 17/156. Re-spec the whole Design section against the `TenantIdSource` overlay R45 actually ships. |
| **R314** dissolve-reentry-leaves-dimensional-emit | Backlog | The design section still names R316-deleted types as the build target: `Carrier.Source` (line 32), `Intent`/`Mapping` as proposed sealed hierarchies (lines 34-37), and `f(carrier.sourceContext, intent.operation, mapping.target)` (line 40), an implementer would search for symbols that no longer exist. R316 narrowed R314's scope rather than mooting it: `Operation.java:78` still notes "Collapses to one carrier under R314," and both `requiresReFetch()` (`model/OutputField.java:128`) and `dispatchPerformsReFetch` (`GraphitronSchemaValidator.java:132`, called `:160`) still co-exist. Re-spec the design prose onto `(source, operation, target)`; `depends-on: [dimensional-model-pivot]` (R222, Spec) is the genuine blocker and resolves. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (Done), so the "R58 is currently *In Review* … if it reverts" framing (lines 21-23) is false and should be removed. Carriers are still String-flattened (`ParsedPath(…, String errorMessage, …)` at `BuildContext.java:990`; `InputFieldResolution.Unresolved(…, String reason)` at `InputFieldResolution.java:20-23`), so the carrier-audit body stays valid; `depends-on: []` is already correct. Lightest re-spec in this section: strike the reversion hedge. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)" (line 2; README too); **R94 shipped** (Done, `changelog.md:393`, slug `emit-input-records`). Blocker cleared, drop the blocked framing from title and body. R94's Done note hands the real remaining dependency to **R98** (`multi-source-input-validation`, re-confirmed Backlog: "picks up the live invalid-input round-trip the moment R98 ships its first SDL constraint"), so re-point the dependency there and the title "(R94-blocked)" should become "(R98-blocked)". |
| **R24** nodeidreferencefield-join-projection-form | Backlog | Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **no longer exists** (dispatch is now `FetcherEmitter.bind` `:179` / `bindRaw` `:359`; the body's "lines 140-162" are dead), and the carrier in the **item title**, `NodeIdReferenceField`, was renamed by R50 to `ColumnReferenceField`/`CompositeColumnReferenceField`. The surviving "not yet implemented" stub is `CompositeColumnReferenceField` at `FetcherEmitter.java:509-517` (the `UnsupportedOperationException` "requires JOIN-with-projection emission" at `:513-516`), and it is the **single** entry in `TypeFetcherGenerator.STUBBED_VARIANTS` (`:302-304`), confirming the release-planning doc's "one tracked stub" claim. Work is valid and unbuilt, but the title symbol + method names need a full re-anchor. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines" and "~30 private methods"; `TypeFetcherGenerator.java` is **6 387** (~3.9×) with **179** `private`-method matches. Lightest-substance re-spec in this section: refresh the LOC/method-count figures and re-survey the cut against the real file, or better, stop hard-coding a count and state "measure at execution time." |

## C. Outdated: update references only (work valid, refs stale) (13)

Substance intact; only paths, line numbers, or dependency tense drifted. **R234**
joined this section this window (downgraded from §B once its body was corrected to
attribute the `JooqRecordInputType` collapse to R222, not R276). The recurring root
cause is unchanged: `TypeFetcherGenerator.java` (**6 387**) / `FieldBuilder.java`
(**6 293**) / `BuildContext.java` (**2 675**) standing at ~2.7 to 3.9× the sizes
the older specs cite, plus a `docs/*.md` → `docs/*.adoc` migration. R389's
`TypeFetcherGenerator` growth (+191) moved the most anchors this window; the
TypeFetcherGenerator-anchored rows (R92, R103, R201, R240) are flagged inline.

| Item | Stale reference |
|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Premise live and unbuilt: `resolvePayloadConstructionShape` on the `PayloadClass` arm (`FieldBuilder.java:491`, called `:2560`); the mutable-bean logic in `tryMutableBean` (`:556`), whose setter-match arm builds `SetterBinding`s off the raw SDL field name via `javaBeanSetterName(sdlFieldName)` (`:574`, predicate `:576`) with **no `@field` read**; the all-fields-ctor arm picks positionally by `getParameterCount()` (`:506`/`:511`/`:534`). The emitter halves are `TypeFetcherGenerator.payloadFactoryLambda` (`:6288`, drifted from the prior audit's `:6097`) and `payloadFactoryLambdaSetters` (`:6305`). Body already frames it as "the output-side mirror of R200"; only the cited line ranges drifted. Re-anchor at the symbols; no premise change. |
| **R308** service-list-payload-arrival | The `@service` list-payload N+1 is real and unbuilt (`checkServiceReturnMatchesPayload` `FieldBuilder.java:3019`, called `:2980`, still admits `List<Payload>`). The body still describes the no-DataLoader shape as "`SingleRecordTableField` today, the single-arrival arm of the merged `RecordTableField` after R305", and `SingleRecordTableField` was **deleted** by R305 (re-confirmed: neither a declared type nor a pattern-match arm anywhere; surviving only in Javadoc/comment/test-name mentions). The live carrier `RecordTableField` is at `model/ChildField.java:829`. Strip the "today" framing; bump `last-updated` (still 2026-06-14). Note R305 is no longer a live roadmap `.md` (completed/deleted), so verify that cross-reference on the next touch. (`depends-on` already `[]`.) |
| **R202** honor-field-directive-in-error-type-source-accessors | Premise live and unbuilt: `checkErrorTypeSourceAccessors` (`FieldBuilder.java:2761`, called `:2539`) calls `ClassAccessorResolver.resolve(sourceClass, sdlField.getName(), …)` (`:2780-2785`) with the raw SDL name and **no `@field` read**, exactly the gap R202 describes. Drifted +2 this window. R200's Done note names R202 as a remaining `@field`-symmetry sibling. Re-anchor at the symbol. |
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck as "Obsolete (R287)"; body re-based onto R305's re-fetch substrate; `depends-on` `[]`. Numeric anchors stale: `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` cited at "`:944`" (3 sites) is now a single def at **`:631`** (called `:373`). The `[ID]`-reject scoped to `CarrierFamily.DML` cited "`BuildContext.java:617-625`" is now at `:794-799`. Re-anchor at the symbols and drop the stale "3 sites" count. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` (**679**, held exactly this window) substance holds (emit-half still unbuilt). Anchors: the argument-level `@condition` resolution dispatch is at `:412-418`, the non-`@table` arg rejection (`foundTia.argCondition().isPresent()`) at `:436`; the `@condition(override:true)` admission gate at `:493-498` reads `ARG_OVERRIDE` (the constant lives in `BuildContext.java:137`, consumed at `:1952`). Body cites ~438-440 / :482-498; re-anchor at the symbols (drift is minimal here). |
| **R17** generated-output-walkthrough | Proposes a `.md` target (line 21) + `.md` siblings; should be `.adoc` (the docs dir is all-`.adoc`). The two cited sibling docs (`docs-as-index-into-tests.md` line 54, `rewrite-docs-entrypoint.md` line 57) do not exist under any extension. Relocate or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (confirmed absent, line 13) and stale `ConnectionSynthesis` naming. The live seam is `ConnectionPromoter.synthesiseForField` / `.rebuildAssembledForConnections` (`ConnectionPromoter.java:122`/`:196`); re-anchor there and fix the dangling doc link. |
| **R92** catalog-check-constraint-validation | **Heaviest re-anchor this window** (TypeFetcherGenerator +191). `validatorPreStep` cited `:1326` is now defined **`:1881`** (was `:1694` prior audit; seam call site now **`:1749`**); `DefaultValidatorHolder` at `GraphitronContextInterfaceGenerator.java:84`, `getValidator` at `:95-99` (body cites `:87-97`). No `CheckRecognizer`/`findCheckConstraints` exists; the R12 §5 validator seam this builds on is present as described. Re-anchor the TypeFetcherGenerator lines and the two context-gen lines. |
| **R103** lift-jooq-column-defaults | **Heavy re-anchor this window** (+187). `DSL.defaultValue` emission is clustered in `buildPerCellValueList` (defined **`TypeFetcherGenerator.java:2250`**, was `:2063`; emitting `:2279`/`:2286`/`:2300`/`:2316`/`:2332`), with a deduped sibling `buildPerCellValueListDeduped` at `:2423` (emitting `:2451`/`:2456`). The contract-side gap (no SDL default lift) is unbuilt. |
| **R240** tablemethod-return-type-token-threading | Current: `ServiceCatalog.reflectTableMethod` has two overloads (dispatcher **`:560`**, slot-types-aware **`:574`**; both drifted from `:484`/`:498`); the strict `ClassName.equals` return-type gate is at `:595-596`; `buildQueryTableMethodFetcher` is at **`TypeFetcherGenerator.java:1343`** (was `:1181`; called `:456`). Neither `MethodRef.StaticOnly` (`:657`/`:735`) nor `ReturnTypeRef.TableBoundReturnType` carries a type token yet. Re-anchor at the symbols. |
| **R236** validator-reference-candidate-hint-terminal-table | (Anchors are in the **item body**, lines 17/27.) `classifyInputFieldInternal` is at `BuildContext.java:2038` (body cites `:1665-1677`), and the candidate-hint failure-aggregation (`candidateHint(c, catalog.columnSqlNamesOf(resolvedTable.tableName()))`) at `:2127`, both held nearly exactly this window (BuildContext +3). The hint still draws from the path-origin `resolvedTable.tableName()`, not the `@reference` terminal table (R380's Done note still lists R236 as an open sibling). Re-anchor at the symbol. |
| **R35** source-orientation-javadocs | LOC counts grossly stale (FieldBuilder cited "2 172", now **6 293**; TypeFetcherGenerator "1 646", now **6 387**); no `package-info.java` exists in the rewrite tree (sweep undone). Doc cross-link nuance: `code-generation-triggers` migrated to `.adoc` and is wrongly linked as `.md` (lines 33/35), while `selection-parser-audit` no longer exists (R30 swept) and `decompose-typefetchergenerator` remains a roadmap `.md` (correctly `.md`). Refresh. |
| **R234** jooq-embedded-and-udt-input-backings | **Newly downgraded from §B.** The body was rewritten to correctly attribute the `JooqRecordInputType` collapse to **R222** (not R276), so the prior audit's "premise factually inverted" defect is gone. What remains: the item is a valid, dormant Backlog stub gated behind the R222 collapse (Stages 5–7). Anchors to keep fresh: `JooqRecordInputType` is a live populated input arm (`GraphitronType.java:342`, permitted at `:303-304`); `JooqTableRecordInputType` is a separate sibling (`:357`); the proposed `BackingClass` family is still unbuilt (no `BackingClass.java`; `parentBackingClass` exists only as a component on `AccessorRef.java`). `depends-on: []` is correct (R222 is a prose gate, not a hard edge). |

**Re-confirmed Current (not flagged):** **R45** (`tenant-routing-and-execution-input`,
**Ready**), the `TenantIdSource` overlay, internally consistent, reconciled with
R190/R316. **R13** (`faceted-search`, **Ready**), the prior window's re-spec onto
the live `ConnectionPromoter` seam still holds. **R261** (`wire-coercion-cast-guard`,
**Spec**), cleared by re-spec this window, anchors accurate. **R219**
`unify-inference-rule-by-javatypekey` and **R220** `consolidate-sources-shape-predicates`
both target `ServiceCatalog` parameter-binding inference (`looksLikeSourcesShape`
`:814`, `couldBeSourcesShape` `:1299`, `classifySourcesType` `:854`, still three
separate predicates; anchors drifted but substance holds); valid, unbuilt. **R267**
`nodeid-encoder-deprecated-convert` (the deprecated `DataType.convert(Object)` still
emitted at `NodeIdEncoderClassGenerator.java:146-150`/`:202`/`:266`; class-wide
`@SuppressWarnings`). **R222** `dimensional-model-pivot` stays **Spec** as the
umbrella (`depends-on: []`, Stages 5 through 7 outstanding). **R333**
`coordinate-lowers-to-datafetcher-queryparts` (Spec), still no `depends-on:` key;
correctly not flagged. **R273** `nodeid-skip-mismatch-error-surfacing` (Spec), its
"Settled by R378" section correctly folds in the now-Done R378 policy; `depends-on`
`[]`, no dangle. **R370** `nested-backing-class-accessor-cast` (Spec), **R63**
`dml-dialect-requirement-on-model` (Spec, revised this window), **R396**
`reference-fk-connection-qualified-table-name` (Ready), **R346**
`supported-directives-regen-guard` (Ready), **R398** `sdl-lint-visitor-engine`
(In Progress, absorbed R121/R296 with the retirements noted), and the new **R397** /
**R402** / **R403** / **R404** all re-verified spec-forward on current code, none
under flag. **R360** (`retire-enum-directive`, Backlog) stays valid: `@enum` is still
live (`SchemaDirectiveRegistry.java`, `TypeBuilder.java`, `directives.graphqls`), so
the retirement is genuinely unbuilt, and its R261 coordination edge holds.

## Cross-cutting observations

1. **The roadmap-hygiene backlog got worked, and the flag count fell.** The prior
   audit's standing complaint, that §A items (R30, R146, R52) had no owner because
   their closure came from a sibling rather than a self-transition, was resolved
   this window: R30 was swept, R146/R52 discarded, all three files deleted. Net
   30/100 → 23/101. The board now carries **zero Done tombstones** for the first
   time in this audit series.
2. **A second flagged item was cleared by re-spec, not by code.** **R261** joins
   **R13** (prior window) as an item whose misleading spec was fixed by re-writing
   it against live symbols rather than by the underlying work shipping. This is the
   cheapest way to clear a §B flag and is worth normalising as a maintenance move.
3. **Line-drift recurred but concentrated in a single file this window.** R389's
   joined-table-inheritance emitter grew `TypeFetcherGenerator.java` (**6 387**,
   +191); `BuildContext` (+3) and `FieldBuilder` (+3) were nearly stable, a reversal
   from the prior two windows. The rows that drifted most are all
   TypeFetcherGenerator-anchored: **R92** (`validatorPreStep` `:1694`→`:1881`),
   **R103** (`buildPerCellValueList` `:2063`→`:2250`), **R240**
   (`buildQueryTableMethodFetcher` `:1181`→`:1343`), and **R201**
   (`payloadFactoryLambda` `:6097`→`:6288`). The only durable defense remains
   re-reading the method at re-spec time; this is the structural backdrop behind §C.
4. **The next structural pivot to watch is still the `SourceKey` decomposition.**
   R316's changelog named it the "first concrete consumer once this pivot lands"; it
   will collapse `wrap`+`cardinality` out of `SourceKey` (`SourceKey.java:81-82`,
   `:94-102` today). Re-confirmed verbatim this window: the `Wrap` contract is
   untouched, so the watch holds. When the decomposition ships, **R71** (re-anchors
   on `SourceKey.Wrap`, which it removes), **R314** (the emit re-platforming R316
   pinned to it), and **R234**'s input-carrier collapse will all need re-checking.
5. **R222 remains the open umbrella** (Spec, `depends-on: []`). Stages 5 through 7
   (the `JooqRecordInputType` collapse R234 forward-dates, namespace collapse,
   directive narrowing closing R97) are still outstanding, consistent with R234's
   premise being premature, not wrong-in-spirit, which is exactly why R234 belongs
   in §C now, not §B.
6. **The `@record` / `@enum` retirement continues to bound a small staleness
   class, now smaller.** `@record` survives as a *declared-but-ignored* directive;
   the one item building tooling on its *live-binding* behaviour, R121, **was
   retired this window** (folded into R398), removing the last item stale against
   `@record`. **R360** proposes the same declared-but-rejected retirement for
   `@enum`; `@enum` is still live in code, so R360 is correctly Backlog and no item
   is yet stale against it. `classification-test-dsl-inventory.md` is doubly stale
   (R299/R290 + the R316 corpus recut) and still warrants the "superseded,
   historical" banner; it has **not** been added (left unedited here per scope).
7. **Minor reference lags on otherwise-current items (below flag threshold).**
   Three current items carry a single stale prose reference each, not enough to
   flag but worth a light touch on their next edit: **R396** (Ready) says a
   `depends-on` on R395 "is now declared" and R395 "is in flight," but R395 is Done
   (file deleted) and the front-matter `depends-on` is correctly `[]`; **R26**
   (In Progress) links `remove-notgenerated.md`, a file that is gone because that
   work shipped; **R397** (Backlog) references `redactCatchArm`, renamed to
   `noChannelCatchArm` per R378's changelog entry. None is a dangling `depends-on`
   or a build risk.
8. **Cosmetic front-matter nits, none flag-worthy (carried over).** **R333**
   (`coordinate-lowers-to-datafetcher-queryparts`, Spec) **still has no `depends-on:`
   key**; add one for parseability on its next touch. **R97** lacks
   `created:`/`last-updated:` header fields (an older item predating that
   convention). Neither is a build or dependency risk.
9. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-01._
