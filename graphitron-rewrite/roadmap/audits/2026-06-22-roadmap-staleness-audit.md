# Roadmap staleness audit — 2026-06-22

A point-in-time review of every active roadmap item under
[`graphitron-rewrite/roadmap/`](../) against the **current** state of the
codebase on `claude/graphitron-rewrite` (HEAD `02356430`, 2026-06-21 21:47). The
goal is to find items whose premise no longer holds: work already shipped,
constructs renamed or removed, dependencies that have since landed, or specs
grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-06-18` staleness audit, which has been deleted —
only the latest audit is retained. Two siblings in this directory are **not**
staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` was the `(source, operation,
  target)` reframe analysis — the permanent lineage document for **R316**. R316
  **shipped to Done this window** (the pivot is built; see below), so this doc is
  now a closed-work lineage record rather than a forward-looking argument. It is
  not a point-in-time staleness review and is not superseded by this audit; it is
  retained as the design record for the landed pivot.
- `classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
  inventory. R316 slice 4b migrated the `@classified` R281 corpus onto
  `source:`/`operation:`/`target:`, so this inventory is now **doubly** stale
  (against R299/R290 as before, and now against the R316 corpus recut). It still
  warrants the "superseded — historical" banner prior audits recommended; it has
  **not** been added (left unedited here per scope — see observation 6).

## Changes since the 2026-06-18 audit

**166 commits** landed between the prior audit commit (`eed9b7e2`, committed
2026-06-17 21:55) and this one (HEAD `02356430`, 2026-06-21 21:47). The dominant
event of the window is **R316 closing to Done** — the `(source, operation,
target)` field-model pivot the prior audit tracked as a *standing watch*
(observation 4) is now **built and merged**, not a plan. That single landing is
the source of almost every flag-count change below.

Many items reached a terminal state this window and **all closed cleanly** — a
full `depends-on` sweep across all 124 item files found **no dangling slugs**
(observation 1). The terminal closures include, among others:

- **R316** `source-operation-target-pivot` → Done (file deleted). Replaced the
  `carrier × intent × mapping` field model with `(source, operation, target)`:
  deleted the four retired model types (`Carrier`/`Intent`/`Mapping`/
  `SourceCardinality`), introduced `model/Operation.java` (a sealed interface with
  `Fetch`/`Paginate`/`Lookup`/`ServiceCall`/`Count`/`Facet`/… arms) and
  `OutputField.source()/operation()/target()`. Resolves the standing watch.
- **R317** `inline-type-classification-into-walk` → Done (`04518899`). Single
  classify-and-emit walk + immutable validate phase.
- **R300** `jooq-routine-fields` → Done. First-class jOOQ routine support
  (table-valued read). On its Backlog→Spec move it **discarded R95**
  (`routines-as-data-model-citizens`, superseded wholesale) — clearing the
  §B item the prior audit flagged for R95/R300 reconciliation (`081e25f2`).
- **R315** (FK-reference `@nodeId` onto jOOQ-record `@service` params), **R322**
  (`@nodeId` shared-column value-agreement), **R328** (self-FK `@nodeId` on DML
  inputs), **R330** (FK-target `@nodeId` + `@condition(override)`), **R331** (LSP
  participant cross-table `@field` scope), **R336**, **R338**, **R339**, **R340**,
  **R343**, **R344**, **R349**, **R350**, **R90** — all → Done. None left a
  stranded tombstone or dangling dependent.

New items filed this window that remain on the board — **R327, R329, R332, R333,
R334, R335, R337, R341, R342, R345, R346, R347, R348, R351, R352** — all carry
sane front-matter, resolve their `depends-on`, and were spot-checked against
current code: **all fresh** save one cosmetic prose nit (R332 calls R327
"Ready"; R327 reopened to Spec on 2026-06-20 — a non-load-bearing status label,
noted in observation 6, not flagged).

**Net effect on the flag counts: 31 flagged, 93 current** (prior window: 30/87).
The composition changed in exactly three places, all traceable to closures:

- **R95 left §B** — discarded (absorbed by R300, now Done). The prior audit's
  "reconcile R95 against R300" finding is resolved by the discard.
- **R52 entered §A** (`lift-operation-taxonomy`) — **newly obsolete.** R316 landed
  the sealed `Operation` carrier with a first-class `Lookup` arm
  (`Operation.java:59`), which is *exactly* the lift R52 specified. R52's own
  re-spec trigger ("Re-spec when that changes") fired; the deliverable shipped.
- **R314 entered §B** (`dissolve-reentry-leaves-dimensional-emit`) — **newly
  outdated.** Its spec is written entirely in the retired `carrier × intent ×
  mapping` vocabulary, all three types now deleted. R316 *narrowed* its scope to
  "pure emit re-platforming" (the `dispatchPerformsReFetch` mirror and the
  transitional `Operation.ServiceCall(Call)` holder are both pinned to R314 by
  the R316 changelog), so the work is sharper, not obsolete — but the prose must
  be re-anchored onto the landed axes.

The two §B items that **forward-dated against R316 landing** — **R71** and
**R234** — were re-checked now that R316 is Done. The pivot did **not** collapse
`wrap`+`cardinality` out of `SourceKey` (the changelog defers that: "the
`SourceKey` decomposition becomes the first concrete consumer once this pivot
lands"), so their *substance is still future work*; both stay §B, but their
premises are now testable and confirmed stale on symbol names (details below).

## Scope and method

All **124** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder). For each item the targets it names
(classes, directives, methods, packages, modules) were located in the current
tree under `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/`,
the described problem was checked for whether it still reproduces, and the
changelog was scanned for the item's `R<n>` and key terms to catch work that
shipped without the item being closed.

Two structural landings from prior windows were re-verified and still hold:
**R276** (deleted `PojoResultType.NoBacking` / `UnbackedPojoResult` — `ResultType`
is a 4-arm seal at `GraphitronType.java:93-95`; `PojoResultType` permits only
`Backed` at `:119-120`) and **R290** (deleted `LeafTupleAdapter`; dissolved
`ConstructorField`). **R305**'s `SingleRecordTableField` collapse remains fully
closed (class gone; the live carrier is `RecordTableField`); its only residue is
R308's body still naming the deleted class (§C). The dominant new structural fact
this window is **R316** — the `carrier × intent × mapping` field model is gone,
replaced by `(source, operation, target)`.

**Result: 31 items flagged, 93 current.** Line numbers cited below are as of the
review date and will themselves drift — see observation 3.

## A. Obsolete — should leave the active roadmap (3)

Each shipped or was superseded by a sibling already at Done. Because the closure
came from the sibling rather than a self-transition, no author ran the
file-deletion sweep.

| Item | Status | Action | Why |
|---|---|---|---|
| **R30** selection-parser-audit | **Done** | **Delete the file** | Status is `Done` (re-confirmed); per workflow (`workflow.adoc:21,74`) Done items are deleted. Re-verified the **sole** stranded Done tombstone in the roadmap (`status: Done` grep returns exactly this one file). Nothing `depends-on` it (the three other files naming it — `changelog.md`, `experimental-construct-type.md`, `source-orientation-javadocs.md` — are prose cross-references, not dependency edges) and it carries no README rollup row, so it is not a build risk — purely an unswept tombstone. If the "parser is needed, keep it" finding is worth retaining, capture it in `changelog.md` first, then delete. **Clean delete — unambiguous.** (Stranded across nine audits now.) |
| **R146** mutation-cardinality-safety-unique-index | Backlog | **Discard → delete** (via transition) | R246 (Done) changelog: "**Absorbs R146 (PK-or-UK coverage, discarded)**." Re-verified in current code: `JooqCatalog.candidateKeys(String)` feeds `walker/MatchedKeys.java:59-60` (`MatchedKey.PrimaryKey`/`UniqueKey`) → `UpdateRowsWalker` PK-preferred coverage matching with a `NoUniqueKeyCoverage` rejection (`:109`), shipped and tested. The `status: Backlog` is stale; the correct mechanism is a `Backlog → Discarded` transition (which couples with file deletion per `workflow.adoc:23`), not a raw delete of a Backlog file. |
| **R52** lift-operation-taxonomy | Backlog | **Discard → delete** (via transition) | **Newly obsolete this window.** R52 asked to "lift the lookup-vs-query axis to a first-class sealed `Operation` carrier … once a cross-cutting consumer needs to ask 'is this a lookup?' without dispatching through variant-shape inspection." **R316 did exactly that:** `model/Operation.java` is now a sealed interface (`:30`) whose `Lookup` arm (`:59`) sits beside `Fetch` (`:41`), `Paginate` (`:54`), `ServiceCall` (`:75`) and the declared-gap arms. The split is no longer "encoded only by variant identity"; it is the sealed model handle R52 specified, and R316's dispatcher rewrite is the forcing function R52 named. Its stated re-spec trigger ("Re-spec when that changes") has fired — but the change satisfies the goal rather than mooting it. **Verify the `Operation.Lookup` arm fully covers R52's intent, then discard via `Backlog → Discarded`** (or, if a thin remainder survives, re-spec to that remainder only). |

## B. Outdated — needs re-spec (premise or targets materially changed) (16)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. Fourteen carry
over from the prior audit and were re-verified; **R314** is new this window
(R316 retired its vocabulary), and **R95 left** this section (discarded). **R71**
and **R234** are now confirmed against the landed R316 (their forward-dated
collapse did not happen — substance still future, symbols stale).

| Item | Status | Why re-spec |
|---|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Backlog | Premise is live: `PayloadConstructionShape` resolution reached on the live `PayloadClass` arm (`resolvePayloadConstructionShape` at **`FieldBuilder.java:488`**, called **`:2282`**; the mutable-bean predicate's `javaBeanSetterName` at **`:600`**, setter-match arm `:571-591`), and R244 preserves that arm. R200 shipped the **input-side** `@field(name:)` axis and explicitly hands "the remaining `@field`-symmetry items" to R201/R202 — so R201 is the wanted **output-side mirror**, not a discard. Re-spec: drop the "R244 moots R201" framing (R244 only covered the migrated `Outcome<T>` paths), re-anchor the construction-shape lines (body's own internal cites `:506-609`/`:589-591`/`:519-535` are also stale), and frame it as the output payload-construction counterpart of R200. |
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; still worsening.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm R276 deleted — `PojoResultType` permits only `Backed` (`GraphitronType.java:119-120`); `ResultType` is a 4-arm seal (`:93-95`). Its primary migration target `FetcherEmitter.propertyOrRecordValue` **no longer exists** (zero hits); only the `GeneratorUtils.buildFkRowKey` (**`:241`**, called `:212`) half of the duplication survives (`backingClassOf` now `:367`). Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. (The `NoBacking` hits that remain in `CatalogBuilder` are the LSP `TypeBackingShape.NoBacking`, a different type kept by R276 — not the deleted result-type arm.) |
| **R13** faceted-search | Spec | Central seam `ConnectionSynthesis.buildPlan()` is long gone (zero hits); the live entry point is **`ConnectionPromoter.synthesiseForField`** (`ConnectionPromoter.java:120`; `rebuildAssembledForConnections` at `:194`). `FieldWrapper.Connection` is a 2-arg record `(boolean connectionNullable, int defaultPageSize)` with no `connectionName` and no facet slot (`FieldWrapper.java:73-75`). Phases 2–4 are written entirely against the retired pipeline. |
| **R71** recordn-key-parity-lifter | Backlog | **Symbol re-anchor; substance confirmed still future post-R316.** Every symbol the mechanism names is dead: `BatchKeyLifterDirectiveResolver`, `BatchKey.LifterRowKeyed`/`RecordParentBatchKey`, and the `RowKeyed`/`MappedRowKeyed`/`RecordKeyed`/`MappedRecordKeyed` permits **do not exist** (R110/R222/R290/R305 reworked the surface). The live surface is `model/LifterRef.java:25`, routed through `@sourceRow` (`SourceRowDirectiveResolver`) and `SourceKey.Reader.SourceRowsCall(LifterRef)` (`SourceKey.java:284`). The prior audit's "forward-dating against R316" is now testable: **R316 did not collapse `wrap`+`cardinality` out of `SourceKey`** (`SourceKey.java:81-84` still carries `Wrap wrap` + `Cardinality cardinality`; `Wrap.{Row,Record,TableRecord}` at `:92-107`), and the compact constructor (`:124-127`) currently *pins* `SourceRowsCall → Wrap.Row` — the exact Row-only asymmetry R71 wants to remove. Re-anchor on `LifterRef`/`SourceKey.Reader.SourceRowsCall`/`Wrap`; the intent is unaddressed and valid. |
| **R46** service-multi-tenant-fanout | Backlog | R190 (Done) sealed `GraphitronContext` to its generated `Impl` (`GraphitronContextInterfaceGenerator.java:12-13`, `CLASS_NAME`/`IMPL_CLASS_NAME` `:34-35`) + a `newExecutionInput(...)` factory (`GraphitronFacadeGenerator.java:116`), dissolving the `ContextValueRegistration<FanOut>` design the whole "Design" section rests on (`ContextValueRegistration` has zero `.java` hits; the cited `getContextFanOut`/`openContextDslContext`/`getExecutor` generator methods also don't exist). Body still links the dead `typed-context-value-registry.md` slug at lines 17/156 (renamed to `tenant-routing-and-execution-input.md` = R45, Spec). |
| **R19** history-squash | Ready | Every SHA/count is an April-2026 snapshot: "595 (as of 2026-04-24)", a "566-commit history" (lines 14/124), the "~303 docs + 263 code" split (`:28`), and frozen `8a8c5efe`/`ac3df0b7`/… SHAs. Branch is now **~2 973 ahead** of `origin/main` (up from the prior audit's 2 807; ~5.3× the documented 566). All numbers/SHAs/drop-lists need regeneration — or, better, computing dynamically at execution time — before this can execute. |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | **Premise factually false against current code.** The body claims R222 *already* collapsed `JooqRecordInputType` by rejecting any non-`TableRecord` jOOQ `Record` — but `JooqRecordInputType` is a live, populated input arm (`GraphitronType.java:342-348`, permitted by `InputType` at `:303-304`, constructed at `TypeBuilder.java:1432`), the quoted "…but not a TableRecord…" rejection is **absent**, and `JooqTableRecordInputType` exists as a *separate* sibling (`:357`). The `BackingClass` family R234 proposes extending was never built (no `BackingClass.java`). **R316 did not touch input-side classification**, so the collapse R234 forward-dates still belongs to R222 (Spec). Re-spec: the "collapse already happened, now reintroduce arms" narrative is inverted — the arm it wants to re-add still exists. Gate behind the R222 collapse shipping, or reconcile against the R311 split. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines"; `TypeFetcherGenerator.java` is **5 796** (~3.5× the cited figure, up from 5 446 last window). Lightest re-spec in this section: refresh the LOC/method-count figures and re-survey the cut against the real file, or stop hard-coding a count. |
| **R263** decode-helper-typename-first-resolution | Backlog | **Discard recommendation now firm.** The trap still exists (`resolveDecodeHelperForTable` at `BuildContext.java:2177-2205`, internals rewritten by R317 to resolve via `nodes.forName(typeName)` at `:2189`, disclaimer restated `:2269`), but its only two callers (`FieldBuilder.java:1138`, `NodeIdLeafResolver.java:266`) both pass the table name as primary with `typeId` as fallback — neither routes an authoritative `@nodeId(typeName:)` expecting it to drive the suffix. Two consecutive consumers (R195, R312) **sidestepped** it; no MUST-route caller has materialized across the intervening windows. The keep-condition the prior audit set has not been met — **discard as speculative** (re-open only if such a caller appears). |
| **R269** nullable-to-one-record-into-npe | Spec | R268 shipped (helpers take a `sourceExpr`) and R271 (dunder retirement) shipped, renaming `__elt`→`element`/`__k`→`key` — so the spec's `__elt`/`__k` snippets (lines 22-23, 29, 35) are **triply stale** (helpers now `GeneratorUtils.java:318`/`:338`), and the "if R268 lands first … if R269 lands first" coordination fork (lines 62-70) is resolved (R268 landed first). The success-arm NPE itself is **still unguarded** (`GeneratorUtils.java:332-334` emits `element = ((Backing) source).accessor()` then `element.into(...)` with no null check, while the sibling table path guards at `:393`). Strip the snippets and the R268 framing; re-anchor on `element`/`key`. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (`R58TypedRejectionPipelineTest` in tree, R58 Done) — the "R58 is currently *In Review* … if it reverts" framing (lines 21-23) is false and should be removed. Carriers are still String-flattened (`ParsedPath(…, String errorMessage)` at `BuildContext.java:947`; `Unresolved` at `InputFieldResolution.java:20`; changelog defers these to R66 explicitly), so the carrier-audit body stays valid; `depends-on: []` is already correct. |
| **R261** wire-coercion-cast-guard | Backlog | The specific reported instance (`(SakRecord) raw.get(...)`, site A) is handled by shipped R195 (`CallSiteExtraction.NodeIdDecodeRecord`, present across ~7 files). Update the coordination framing (lines ~100-105) to past tense; the raw-cast invariant for sites B–E is still unbuilt (R195's rejection is jOOQ-record-narrow). |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)" (lines 3, 13); R94 shipped (Done). Blocker cleared — drop the blocked framing from title and body (lines 18-26). R94's own Done note hands the real remaining dependency to **R98** ("R170 picks up the live invalid-input round-trip the moment R98 ships its first SDL constraint"; R98 is Backlog), so re-point the dependency there. |
| **R24** nodeidreferencefield-join-projection-form | Backlog | Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **no longer exists** (dispatch is now `FetcherEmitter.bind`/`bindRaw`, `bindRaw` at `:359`), and the carrier in the **item title** — `NodeIdReferenceField` — was renamed by R50 to `ColumnReferenceField`/`CompositeColumnReferenceField` (sibling `ParticipantColumnReferenceField` at `:480`). The surviving "not yet implemented" stub is for `CompositeColumnReferenceField` at `FetcherEmitter.java:499-507` (the `UnsupportedOperationException` "requires JOIN-with-projection emission" at `:504-506`); the `ColumnReferenceField` direct-compaction arm at `:493-497` is implemented. Work is valid + unbuilt, but title symbol + method names need a full re-anchor. |
| **R121** lsp-diagnostic-redundant-splitquery-on-record | Backlog | Mooted/reframed by the `@record` retirement (R301/R307, Done). The live build warning is `warnIfSplitQueryOnRecordParent` at **`FieldBuilder.java:4589-4596`** (message "@splitQuery is redundant on a record-backed parent field" at `:4593`; drifted from the prior audit's `:4445`), keying on the reflection-derived **record-backed** determination, not a `TypeContext.hasDirective(typeDef,"record")` SDL predicate. Sibling redundancy warnings now also exist (R275: `:4281`, `:4415`; `MutationInputResolver.java:208`). Re-spec against the current `warnIfSplitQueryOnRecordParent` wording (and decide whether to fold in the siblings), or **discard** if the SDL-only LSP can't mirror the reflection-derived determination. |
| **R314** dissolve-reentry-leaves-dimensional-emit | Backlog | **New this window.** The body (lines 16-53) is written entirely in the retired `carrier × intent × mapping` vocabulary ("Carrier carries the source context", "Intent … the service-lift", "Mapping carries the output projection") — and R316 **deleted all three types**. An implementer would search for symbols that no longer exist. R316 *narrowed* R314's scope rather than mooting it: the changelog pins the `dispatchPerformsReFetch` mirror retirement and the transitional `Operation.ServiceCall(Call)` one-carrier collapse explicitly to R314 ("retiring it is R314's emit re-platforming"; `Operation.java:75-78`, "Collapses to one carrier under R314"; both `requiresReFetch` in `OutputField` and `dispatchPerformsReFetch` in `GraphitronSchemaValidator` still co-exist). Re-spec the prose onto `(source, operation, target)`; the work is sharper and `depends-on: [dimensional-model-pivot]` (R222) still resolves. |

## C. Outdated — update references only (work valid, refs stale) (12)

Substance intact; only paths, line numbers, or dependency tense drifted. The
recurring root cause is unchanged: `FieldBuilder.java` (now **5 870**, +89 this
window) / `TypeFetcherGenerator.java` (**5 796**, +350) / `BuildContext.java`
(**2 415**, +161, moved further by R317's index work) standing at ~2.7–3.5× the
sizes the older specs cite, plus a `docs/*.md` → `docs/*.adoc` migration.

| Item | Stale reference |
|---|---|
| **R308** service-list-payload-arrival | Substance fully intact — the `@service` list-payload N+1 (carrier arrives as a list, the inline-no-loader fetcher fires once per element) is real and unbuilt. But its body still describes the no-DataLoader shape as "`SingleRecordTableField` today, the single-arrival arm of the merged `RecordTableField` after R305" — and `SingleRecordTableField` was **deleted** by R305 (now only survives in javadoc prose). Strip the "today" framing and read it as the single-arrival arm of `RecordTableField`; bump `last-updated`. (`depends-on` already `[]`.) |
| **R202** honor-field-directive-in-error-type-source-accessors | Premise live and unbuilt: `checkErrorTypeSourceAccessors` (**`FieldBuilder.java:2483`**, called **`:2261`**) calls `ClassAccessorResolver.resolve(sourceClass, sdlField.getName(), …)` (**`:2502`**, raw SDL name at `:2504`) with **no `@field` read** — exactly the gap R202 describes. R200's Done note names R202 as a remaining `@field`-symmetry sibling. Only staleness is line drift (drifted again with FieldBuilder's +89); re-anchor at the symbol. |
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck as "Obsolete (R287)"; body re-based onto R305's re-fetch substrate; `depends-on` `[]`. One numeric anchor stale: `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` cited at "`:944`" (3 sites) is now a single def at **`:594`** (called `:373`). Re-anchor at the symbol and drop the stale "3 sites" count. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` substance holds, emit-half still unbuilt. Anchors drifted: argument-level `@condition` rejection (`argCondition`) is at **`:415`** (used `:417-423`, `:439`; body cites ~438-440); the `@condition(override:true)` admission gate (`ARG_OVERRIDE`) is at **`:496-498`** (body cites :482-498). Re-anchor at the symbols. |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`. The two cited sibling docs (`docs-as-index-into-tests.md`, `rewrite-docs-entrypoint.md`) do not exist under any extension — relocate or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (absent) and stale `ConnectionSynthesis` naming. The live seam is now `ConnectionPromoter.synthesiseForField` / `.rebuildAssembledForConnections` (`ConnectionPromoter.java:120`/`:194`) — re-anchor there. |
| **R99** lsp-submodule-sibling-classpath | Path `graphitron-rewrite/graphitron-maven/…` is actually `graphitron-maven-plugin/…`; `getAllProjects()` cited ~`:113` is now `AbstractRewriteMojo.java:212-213` (second use `:243-244`). |
| **R92** catalog-check-constraint-validation | Seam anchors drifted (TypeFetcherGenerator grew): `validatorPreStep` cited `:1326` is now defined **`:1626`** (called `:1519`); `DefaultValidatorHolder` is at `GraphitronContextInterfaceGenerator.java:84`, `getValidator` at `:95-99` (these two still accurate). Re-anchor the two TypeFetcherGenerator lines. |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue` emission cited at `:1456`/`:1496`/`:1508`/`:1769` is now clustered in `buildPerCellValueList` at **`TypeFetcherGenerator.java:1995`** (emitting `:2024`/`:2031`/`:2045`/`:2061`/`:2077`; additional sites at `:2194`/`:2361`). |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog.reflectTableMethod` now has two overloads (dispatcher **`:484`**, slot-types-aware **`:498`**); the strict `ClassName.equals` return-type gate is at **`:528-529`** (cited `:524-525`); `buildQueryTableMethodFetcher` is at `TypeFetcherGenerator.java:1123` (called `:436`). Re-anchor at the symbols. |
| **R236** validator-reference-candidate-hint-terminal-table | (Anchors are in the **item body**, lines 17/27.) Drifted further this window from R317's `BuildContext` index work: `classifyInputFieldInternal` is now at **`BuildContext.java:1804`** (body cites `:1665-1677`; prior audit `:1726`), and the candidate-hint failure-aggregation (`columnSqlNamesOf(...)`) is at **`:1891`**. Re-anchor at the symbol. |
| **R35** source-orientation-javadocs | LOC counts grossly stale (FieldBuilder cited "2 172", now **5 870**; TypeFetcherGenerator "1 646", now **5 796**); doc cross-links use `.md` and should be `.adoc` (`code-generation-triggers.md`, `selection-parser-audit.md`, `decompose-typefetchergenerator.md`, `changelog.md`). The "FieldBuilder decomposition shipped under R6" claim stays dubious given the file is now ~2.7× larger. |

**Re-confirmed Current (not flagged):** **R267** nodeid-encoder-deprecated-convert
— the deprecated `col.getDataType().convert(values[i])` is still emitted
(`NodeIdEncoderClassGenerator.java:266`, class-wide `@SuppressWarnings({"deprecation","removal"})`
at `:151-152`); symbols correct. **R219** unify-inference-rule-by-javatypekey and
**R220** consolidate-sources-shape-predicates — re-checked after R316; both target
`ServiceCatalog` parameter-binding inference (`inferBindingsByType:1043`,
`looksLikeSourcesShape:758`, `classifySourcesType:798`, `couldBeSourcesShape:1181`),
which R316 explicitly left out of scope and **did not touch** — valid, unbuilt, not
stale. **R222** dimensional-model-pivot stays **Spec** as the umbrella: R316 was one
of its slices reaching Done independently; `depends-on: []`, no stranded edge.
**R319/R323/R326** (filed last window) and **R327/R329/R332/R333/R334/R335/R337/
R341/R342/R345/R346/R347/R348/R351/R352** (filed this window) — fresh specs, sane
front-matter, `depends-on` resolves; not stale.

## Cross-cutting observations

1. **Closure hygiene stayed clean — the dependency graph held.** Every item that
   left the board this window self-closed cleanly (Done + file deleted, or
   filed-and-closed within the window), and R95 was discarded cleanly (absorbed by
   R300). A full `depends-on` sweep across all 124 item files found **no dangling
   slugs**. R30 (self-Done, unswept) remains the **only** stranded Done tombstone —
   alongside R146/R52's discard-by-or-toward-sibling Backlog files. The workflow
   rule that *the closing author deletes the file* still has no owner when the
   closure comes from a sibling (R146, R52). Worth a one-shot cleanup pass and a
   workflow note.
2. **R316 landing is this window's defining event — it both resolved and created
   flags.** The standing watch (prior observation 4) fired: the
   `carrier × intent × mapping` model is gone, replaced by `(source, operation,
   target)`. That **resolved R52** (the sealed `Operation.Lookup` lift it asked
   for) and **created R314's flag** (its prose names the three deleted types). It
   also let the two forward-dating §B items be tested against reality: R316 did
   **not** collapse `wrap`+`cardinality` out of `SourceKey` (deferred to the next
   slice), so **R71** and **R234** keep their §B substance — their premises are now
   *confirmed* stale on symbol names rather than merely forecast.
3. **Line-drift recurs from ordinary refactors.** `FieldBuilder` is **5 870**
   (+89), `TypeFetcherGenerator` **5 796** (+350), `BuildContext` **2 415** (+161;
   R236's `classifyInputFieldInternal` moved `:1726`→`:1804`), while `FetcherEmitter`
   (726) keeps R242's `buildSingleRecordIdFromReturningFetcherValue` at `:594` though
   the prose still cites `:944`. Sharpest evidence yet that specs anchored to
   **symbol names** rather than line numbers would stop the recurrence; that bloat
   is the structural backdrop behind most of §C.
4. **The next structural pivot to watch is the `SourceKey` decomposition.** R316's
   changelog names it the "first concrete consumer once this pivot lands"; it will
   collapse `wrap`+`cardinality` out of `SourceKey` (`SourceKey.java:81-84`,
   `:92-107` today). When it ships, **R71** (re-anchors on `SourceKey.Wrap`, which it
   removes) and **R314** (the emit re-platforming R316 pinned to it) will need
   re-checking, and **R234**'s input-carrier collapse depends on R222's remaining
   stages (5–7). Flagging those further now would be premature; this is the standing
   watch for the next audit, replacing the now-fired R316 watch.
5. **R222 remains the open umbrella** (Spec, `depends-on: []`). R316 implemented one
   of its slices and closed independently; Stages 5–7 (the `JooqRecordInputType`
   collapse R234 forward-dates, namespace collapse, directive narrowing) are still
   outstanding — consistent with R234's premise being premature, not wrong-in-spirit.
6. **The `@record` retirement (R301/R307) continues to bound a small staleness
   class.** `@record` survives as a *declared-but-ignored* directive; the one item
   building tooling on its *live-binding* behaviour — **R121** — stays mooted.
   `classification-test-dsl-inventory.md` is now doubly stale (R299/R290 + the R316
   corpus recut) and still warrants the "superseded — historical" banner; it has
   **not** been added (left unedited here per scope). One cosmetic nit not worth a
   flag row: **R332**'s cluster section calls **R327** "Ready", but R327 reopened to
   Spec on 2026-06-20 — a non-load-bearing status label; fix on R332's next touch.
7. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-06-22._
