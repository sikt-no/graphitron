# Roadmap staleness audit — 2026-06-25

A point-in-time review of every active roadmap item under
[`graphitron-rewrite/roadmap/`](../) against the **current** state of the
codebase on `claude/graphitron-rewrite` (HEAD `eb6c2db3`, 2026-06-24 21:18). The
goal is to find items whose premise no longer holds: work already shipped,
constructs renamed or removed, dependencies that have since landed, or specs
grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-06-24` staleness audit, which has been deleted;
only the latest audit is retained. Two siblings in this directory are **not**
staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316**. R316
  is Done (the pivot is built), so this doc is a closed-work lineage record
  rather than a forward-looking argument. It is not a point-in-time staleness
  review and is not superseded by this audit.
- `classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
  inventory. R316 slice 4b migrated the `@classified` R281 corpus onto
  `source:`/`operation:`/`target:`, so this inventory is **doubly** stale
  (against R299/R290 as before, and against the R316 corpus recut). It still
  warrants the "superseded — historical" banner prior audits recommended; it has
  **not** been added (left unedited here per scope, see observation 6).

## Changes since the 2026-06-24 audit

**~60 commits** landed between the prior audit's review point (`cfd5d629`,
committed 2026-06-23 22:06) and this one (HEAD `eb6c2db3`, 2026-06-24 21:18). The
prior audit file itself was committed at `b777f8f7`, the first commit after its
review point. Unlike the throughput window the prior audit described, this was a
**feature-landing window**: seven items closed cleanly to Done with real code
(polymorphic-emission support, the shared-column overlap unification, an
`@service` split-query type fix, two LSP/dev-tooling landings, and the first two
slices of the MCP programme), six fresh items were filed, and the MCP item was
widened into a programme. Still **no structural axis pivot** on the scale of
R316: the field model is unchanged.

**Terminal closures this window (all clean, all self-deleted their files):**

- **R353** `lsp-backing-class-member-navigation` → Done. LSP goto-definition from
  an SDL declaration name to the Java the model bound it to, via a new
  `definition/DeclarationDefinitions` dispatching on `TypeBackingShape` through the
  sealed `DefinitionTarget` and the LSP-owned `SourceWalker.Index`. **Existed at
  the prior review point; deleted this window.**
- **R356** `unify-shared-column-overlap-analysis` → Done. Unified the
  per-column shared-column overlap analysis across six accreted DML write-path
  sites onto one shared primitive — new `model/ColumnOverlap` (`ColumnWriter` view,
  `Contributor`, `OverlapColumn`, `groupByColumn`). A pure refactor that retired
  `analyzeOverlap`/`InsertCol`/`SetCol` and friends. **Existed at the prior review
  point as Backlog; transitioned Spec → Done and deleted this window.** This is the
  source of most of the FieldBuilder / MutationInputResolver / TypeFetcherGenerator
  line drift noted in §C.
- **R361** `mcp-shared-model-seam` → Done. R118 programme slice 1: widened
  `GraphitronMcpServer`'s constructor to hold the live `Workspace`, declared the
  `tools` capability with a liveness `status` tool. **Filed and closed within this
  window**; leaves no file. Builds on R341.
- **R364** `service-split-query-scalar-leaf` → Done. Fixed the `@service @splitQuery`
  rows-method return type for enum / non-built-in-scalar leaf fields (was emitting a
  doubly-nested `Map<K, Map<K, V>>` that did not compile). Added
  `RowsMethodShape.perKeyFromOuter` and closed the validator gap in
  `ServiceDirectiveResolver.validateChildServiceReturnType`. **Filed and closed
  within this window**; leaves no file. Defers `emit-text-mapped-enum-fields-as-enum-type`
  (R231) explicitly.
- **R366** `loadmany-list-polymorphic-splitquery` → Done. Emitted `loadMany`
  dispatch for list-cardinality polymorphic `@splitQuery` on record-backed parents
  (the prior unconditional `loader.load(key, …)` referenced an out-of-scope local
  for a `MANY` parent `SourceKey`). **Filed and closed within this window**; leaves
  no file. Shares `MultiTablePolymorphicEmitter` with R363/R367.
- **R367** `polymorphic-child-record-parent-single-cardinality` → Done.
  Single-cardinality polymorphic child on a record-backed (Pojo / JavaRecord) parent,
  closing the capability gap whose `Rejection.deferred(planSlug:…)` pointed at a
  roadmap doc that never existed. **Filed and closed within this window**; leaves no
  file. Filed **R370** for the residual nested-backing-class `Outer$Nested`
  non-compiling-cast hazard; enables R365 shape (b).
- **R369** `graphitron-dev-generated-sources-coverage` → Done. `graphitron:dev`
  now walks generated-sources of scanned reactor modules so goto-definition / hover
  reaches jOOQ tables in a separate module; fix is in the shared resolver
  (`AbstractRewriteMojo.generatedSourceRoots` / `compileSourceRootsOf`), not a
  dev-only branch. **Filed and closed within this window**; leaves no file. Builds on
  R351/R352/R90. *(This touches `AbstractRewriteMojo`, the same class R99 cites —
  see §C; it does not resolve R99's sub-module classpath-scan premise.)*

**New items still on the board (six, all filed this window):**

- **R362** `mcp-catalog-tools` (Ready, feature) — R118 slice 2: `catalog.tables` /
  `catalog.describe` over a build-time `CatalogFacts` projection. `depends-on: []`.
- **R363** `multitable-interface-query-filter-lowering` (Spec, bug) — lower `@field`
  filter inputs and `@condition` onto multitable-interface queries; shares
  `MultiTablePolymorphicEmitter` with R365/R366/R367.
- **R365** `polymorphic-entity-service-return` (Ready, bug) — return a polymorphic
  entity (interface/union) from a `@service` mutation; route (a) resolves the
  participant by the returned record's runtime type, and added a same-table
  discriminability validation floor (the +44 lines in `GraphitronSchemaValidator`).
- **R368** `mcp-workspace-read-tools` (Spec, feature) — R118 slices 3-6: MCP
  structured read-tools over the live Workspace.
- **R370** `nested-backing-class-accessor-cast` (Backlog, bug) — filed by R367: a
  record-backed parent with a nested backing class emits a non-compiling
  `$`-qualified cast (`ClassName.bestGuess` over a binary `Outer$Nested` name).
- **R371** `declaration-name-hover-reads-source-index` (In Progress, bug) —
  declaration-name hover surfaces jOOQ class/column Javadoc by reading the source
  index, parity with R353's goto-definition.

**R118 widened into a programme (b3f502be), R333 took a code landing (d0954ad6).**
R118 (`graphitron-mcp-server`, Backlog) absorbed the earlier single-feature item and
became the agent-facing MCP programme; R361/R362/R368 are its slices. R333
(`coordinate-lowers-to-datafetcher-queryparts`, Spec) **folded `@sourceRow` into the
join path as source-side key provenance** this window — a code change to
`ServiceDirectiveResolver`, not a status move. It does **not** collapse `wrap` +
`cardinality` out of `SourceKey` (still the standing watch behind R71 / §B — see
observation 4).

A full `depends-on` sweep across all **126** item files found **no dangling slugs**
(re-verified: every bracketed slug — `capability-catalog`,
`catalog-check-constraint-validation`, `consumer-derived-input-tables`,
`dimensional-model-pivot`, `retire-maven-plugin`, `service-walker-substrate-absorption`,
`sis-rewrite-migration`, `tenant-routing-and-execution-input` — resolves to a live
file). **R30** remains the **only** stranded Done tombstone (re-confirmed: a
non-recursive `^status: Done` grep over `roadmap/*.md` returns exactly that one file).

**Net effect on flag counts: 31 flagged, 95 current** (prior window: 31/91). The
**flag composition did not change this window**: no flagged item was resolved or
created. Board accounting: prior total 122 items, minus R353 and R356 (Done, files
deleted), plus R362/R363/R365/R368/R370/R371 (six filed), with R361/R364/R366/R367/R369
filed-and-closed within the window (net zero), gives **126** items today; flagged held
at 31, so current rose to 95. All seven closures and all six new items landed on
**current/fresh** items, none under flag, so no §A/§B/§C row entered or left.

## Scope and method

All **126** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder). For each item the targets it names
(classes, directives, methods, packages, modules) were located in the current
tree under `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/`,
the described problem was checked for whether it still reproduces, and the
changelog was scanned for the item's `R<n>` and key terms to catch work that
shipped without the item being closed.

Every flagged item carried over from the prior audit was re-verified against
current HEAD. **All 31 still reproduce.** This window drifted more line anchors than
the last, because real code landed: `FieldBuilder.java` (**5 937**, +25 from R356's
overlap migration and R367's polymorphic resolver), `GraphitronSchemaValidator.java`
(**1 325**, +44 from R365's same-table validation floor), `MutationInputResolver.java`
(**679**, +52 from R356/R365), `model/ChildField.java` (`RecordTableField` moved
`:807` → `:829` from R366/R367), and `TypeFetcherGenerator.java` (**6 157**, net −13
but locally +14 around the validator/tablemethod region from R356/R364). The
structural landings the prior audits relied on still hold: **R276** (`ResultType` is a
4-arm seal at `GraphitronType.java:93-95`; `PojoResultType` permits only `Backed` at
`:119-120`), **R290** (`LeafTupleAdapter` / `ConstructorField` dissolved), **R305**
(`SingleRecordTableField` gone; live carrier is `RecordTableField`), and **R316** (the
`carrier × intent × mapping` field model gone, replaced by `(source, operation,
target)`; `model/Operation.java` is a sealed interface).

**Result: 31 items flagged, 95 current.** Line numbers cited below are as of the
review date and will themselves drift; see observation 3.

## A. Obsolete — should leave the active roadmap (3)

Each shipped or was superseded by a sibling already at Done. Because the closure
came from the sibling rather than a self-transition, no author ran the
file-deletion sweep. Composition unchanged from the prior audit.

| Item | Status | Action | Why |
|---|---|---|---|
| **R30** selection-parser-audit | **Done** | **Delete the file** | Status is `Done` (re-confirmed `selection-parser-audit.md:4`). Per workflow (`workflow.adoc:21,74`) Done items are deleted. Re-verified the **sole** stranded Done tombstone (`^status: Done` grep over `roadmap/*.md` returns exactly this one file). Nothing `depends-on` it (the only other files naming it, `changelog.md` and `source-orientation-javadocs.md`, are prose cross-references, not dependency edges); it carries no README rollup row, so it is not a build risk, purely an unswept tombstone. If the "parser is needed, keep it" finding is worth retaining, capture it in `changelog.md` first, then delete. **Clean delete, unambiguous.** (Stranded across twelve audits now.) |
| **R146** mutation-cardinality-safety-unique-index | Backlog | **Discard → delete** (via transition) | R246's changelog entry (Done): "**Absorbs R146 (PK-or-UK coverage, discarded)**" (re-verified verbatim). Re-confirmed in current code: `JooqCatalog.candidateKeys(String)` (`:593`) feeds the PK-preferred subset match in `walker/MatchedKeys.java` (`MatchedKey.PrimaryKey`/`UniqueKey`), with a `NoUniqueKeyCoverage` rejection now living on `UpdateRowsError.java:51-75` (R342 re-staged the walker last window; reached from `UpdateRowsWalker`), shipped and tested. R246 is a changelog-only tombstone, not a standalone `R<n>` file, but the "Absorbs R146" wording is present. The `status: Backlog` is stale; the correct mechanism is a `Backlog → Discarded` transition (which couples with file deletion per `workflow.adoc:23`), not a raw delete. |
| **R52** lift-operation-taxonomy | Backlog | **Discard → delete** (via transition) | Obsoleted by R316; re-confirmed. R52 asked to "lift the lookup-vs-query axis to a first-class sealed `Operation` carrier … once a cross-cutting consumer needs to ask 'is this a lookup?' without dispatching through variant-shape inspection." **R316 did exactly that:** `model/Operation.java` is a sealed interface (`:30`) whose `Lookup` arm (`:59`, carrying `LookupMapping lookupMapping`) sits beside `Fetch` (`:41`), `Paginate` (`:54`), and `ServiceCall` (`:75`). The split is the sealed model handle R52 specified, not "encoded only by variant identity." **Verify the `Operation.Lookup` arm fully covers R52's intent, then discard via `Backlog → Discarded`** (or, if a thin remainder survives, re-spec to that remainder only). |

## B. Outdated — needs re-spec (premise or targets materially changed) (16)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. All sixteen
carry over from the prior audit and were re-verified this window; **every one
still reproduces.** Line-anchor drift this window is called out per affected row.

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; still the sharpest.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm R276 deleted: `ResultType` is a 4-arm seal (`GraphitronType.java:93-95`), `PojoResultType` permits only `Backed` (`:119-120`). Its primary migration target `FetcherEmitter.propertyOrRecordValue` **no longer exists** (zero hits); only the `GeneratorUtils.buildFkRowKey` half of the duplication survives (`:241`, called `:212`; `backingClassOf` now `:367`; the shared `recordColumnReadArgs` dispatcher at `:257`). Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. (The `NoBacking` hits in `CatalogBuilder` are the LSP `TypeBackingShape.NoBacking`, a different type, not the deleted result-type arm.) |
| **R201** honor-field-directive-in-payload-construction-shape | Backlog | Premise live: `resolvePayloadConstructionShape` on the live `PayloadClass` arm (`FieldBuilder.java:488`, called `:2273`); the mutable-bean predicate's `javaBeanSetterName` at `:600`, setter-match arm `:571`, builds bindings off the raw SDL field name with **no `@field` read** (all above FieldBuilder's R356/R367 insertion point, so anchors held this window). R200 shipped the input-side `@field(name:)` axis and explicitly hands "the remaining `@field`-symmetry items" to R201/R202, so R201 is the wanted **output-side mirror**, not a discard. Re-spec: drop the "R244 moots R201" framing (R244 only covered the migrated `Outcome<T>` paths), re-anchor the construction-shape lines (the body's internal cites are also stale), and frame it as the output payload-construction counterpart of R200. |
| **R13** faceted-search | Spec | Central seam `ConnectionSynthesis.buildPlan()` is long gone (zero hits for both names); the live entry point is `ConnectionPromoter.synthesiseForField` (`ConnectionPromoter.java:120`; `rebuildAssembledForConnections` at `:194`). `FieldWrapper.Connection` is a 2-arg record `(boolean connectionNullable, int defaultPageSize)` with no `connectionName` and no facet slot (`FieldWrapper.java:73-76`). Phases 2 through 4 are written entirely against the retired pipeline. |
| **R71** recordn-key-parity-lifter | Backlog | **Symbol re-anchor; substance confirmed still future, and freshly relevant.** Every symbol the mechanism names is dead: `BatchKeyLifterDirectiveResolver`, `BatchKey.LifterRowKeyed`/`RecordParentBatchKey`, and the `RowKeyed`/`MappedRowKeyed`/`RecordKeyed`/`MappedRecordKeyed` permits do not exist (R110/R222/R290/R305 reworked the surface). The live surface is `model/LifterRef.java:25`, routed through `@sourceRow` (`SourceRowDirectiveResolver`) and `SourceKey.Reader.SourceRowsCall(LifterRef)`. R316 did **not** collapse `wrap`+`cardinality` out of `SourceKey` (`SourceKey.java:81-82` still carries `Wrap wrap` + `Cardinality cardinality`; `Wrap.{Row,Record,TableRecord}` at `:94`/`:96`/`:102`), and the compact constructor (`:124-127`) still pins `SourceRowsCall → Wrap.Row`, the exact Row-only asymmetry R71 wants to remove. **New this window:** R333's `@sourceRow`-into-join-path fold (d0954ad6) touched the `@sourceRow` resolution path but left the `SourceKey` `Wrap` contract unchanged; re-confirm the lifter symmetry intent is still unaddressed when re-specing, and re-anchor on `LifterRef`/`SourceRowsCall`/`Wrap`. |
| **R46** service-multi-tenant-fanout | Backlog | R190 (Done) sealed `GraphitronContext` to its generated `Impl` plus a `newExecutionInput(...)` factory, dissolving the `ContextValueRegistration<FanOut>` design the whole "Design" section rests on (`ContextValueRegistration` has zero `.java` hits; the cited `getContextFanOut`/`openContextDslContext`/`getExecutor` generator methods also don't exist). Body still links the dead `typed-context-value-registry.md` slug at lines 17/156 (renamed to `tenant-routing-and-execution-input.md` = R45, Spec). |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | **Premise factually inverted against current code.** The body claims R222 *already* collapsed `JooqRecordInputType` by rejecting any non-`TableRecord` jOOQ `Record`, but `JooqRecordInputType` is a live, populated input arm (`GraphitronType.java:342`, permitted by `InputType` at `:303-304`), the quoted "…but not a TableRecord…" rejection is **absent**, and `JooqTableRecordInputType` exists as a *separate* sibling (`:357`). The `BackingClass` family R234 proposes extending was never built (no `BackingClass.java`). R316 did not touch input-side classification, so the collapse R234 forward-dates still belongs to R222 (Spec, Stages 5 through 7). Re-spec: the "collapse already happened, now reintroduce arms" narrative is inverted; the arm it wants to re-add still exists. Gate behind the R222 collapse shipping. |
| **R314** dissolve-reentry-leaves-dimensional-emit | Backlog | The body (lines 16-53) is written entirely in the retired `carrier × intent × mapping` vocabulary, and R316 deleted all three types; an implementer would search for symbols that no longer exist. R316 *narrowed* R314's scope rather than mooting it: the `dispatchPerformsReFetch` mirror retirement and the transitional `Operation.ServiceCall(Call)` one-carrier collapse are both pinned to R314 (`Operation.java:75`/`:78`/`:81`, "Collapses to one carrier under R314"; both `requiresReFetch` in `model/OutputField.java:128` and `dispatchPerformsReFetch` in `GraphitronSchemaValidator.java:132`, called `:160`, still co-exist). Re-spec the prose onto `(source, operation, target)`; the work is sharper and `depends-on: [dimensional-model-pivot]` (R222) still resolves. |
| **R263** decode-helper-typename-first-resolution | Backlog | **Discard recommendation now firmer still.** The trap still exists (`resolveDecodeHelperForTable` at `BuildContext.java:2208`), but R317's index rewrite made it resolve **table-first** (`findGraphQLTypeForTable(sqlTableName)` at `:2215`, with `typeId` only a fallback suffix at `:2233-2235`), which is the inverse of the typeName-first resolution R263 proposes. It has **four** callers (`:2041`/`:2176` internal, `FieldBuilder.java:1138`, `NodeIdLeafResolver.java:275`); **all** pass the table name as primary with `typeId` as fallback, and **none** route an authoritative `@nodeId(typeName:)` expecting it to drive the suffix. No MUST-route caller has materialized across the intervening windows. **Discard as speculative** (re-open only if such a caller appears). |
| **R269** nullable-to-one-record-into-npe | Spec | R268 shipped (helpers take a `sourceExpr`) and R271 (dunder retirement) shipped, renaming `__elt`→`element`/`__k`→`key`, so the spec's `__elt`/`__k` snippets (lines 22-23, 29, 35) are triply stale (helpers now `GeneratorUtils.buildAccessorKeySingle` `:318`, `buildAccessorKeyMany` `:338`), and the "if R268 lands first … if R269 lands first" coordination fork (lines 62-70) is resolved (R268 landed first). The success-arm NPE itself is **still unguarded** (`GeneratorUtils.java:332-334` emits `element = ((Backing) source).accessor()` then `element.into(...)` with no null check, while the sibling FK path guards at `:433-436`). Strip the snippets and the R268 framing; re-anchor on `element`/`key`. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (`R58TypedRejectionPipelineTest` in tree, R58 Done), so the "R58 is currently *In Review* … if it reverts" framing (lines 21-23) is false and should be removed. Carriers are still String-flattened (`ParsedPath(…, String errorMessage)` at `BuildContext.java:974`; `Unresolved` at `InputFieldResolution.java:20`; changelog defers these to R66 explicitly), so the carrier-audit body stays valid; `depends-on: []` is already correct. |
| **R261** wire-coercion-cast-guard | Backlog | The specific reported instance (`(SakRecord) raw.get(...)`, site A) is handled by shipped R195 (`CallSiteExtraction.NodeIdDecodeRecord`, defined `model/CallSiteExtraction.java:206`, consumed across ~7 files). Update the coordination framing (lines ~100-105) to past tense; the raw-cast invariant for sites B through E is still unbuilt (R195's rejection is jOOQ-record-narrow). **Carried over from prior window:** R360 (`retire-enum-directive`, Backlog) explicitly hands R261 the enum-name-divergence rejection at the column-binding site — re-confirmed the `retire-enum-directive.md` file names R261 for that coordination; note the incoming edge when re-specing. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)" (lines 3, 13); R94 shipped (Done, in `changelog.md`). Blocker cleared, drop the blocked framing from title and body (lines 18-26). R94's Done note hands the real remaining dependency to **R98** (`multi-source-input-validation`, Backlog: "R170 picks up the live invalid-input round-trip the moment R98 ships its first SDL constraint"), so re-point the dependency there (the title "(R94-blocked)" should become "(R98-blocked)"). |
| **R24** nodeidreferencefield-join-projection-form | Backlog | Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **no longer exists** (dispatch is now `FetcherEmitter.bind` `:179`/`bindRaw` `:359`), and the carrier in the **item title**, `NodeIdReferenceField`, was renamed by R50 to `ColumnReferenceField`/`CompositeColumnReferenceField`. The surviving "not yet implemented" stub is for `CompositeColumnReferenceField` at `FetcherEmitter.java:509-517` (the `UnsupportedOperationException` "requires JOIN-with-projection emission" at `:514-516`); the `ColumnReferenceField` direct-compaction arm at `:503-508` is implemented. **The polymorphic-emission work this window (R365/R366/R367) added `MultiTablePolymorphicEmitter` but did not touch this stub.** Work is valid and unbuilt, but title symbol + method names need a full re-anchor. |
| **R121** lsp-diagnostic-redundant-splitquery-on-record | Backlog | Mooted/reframed by the `@record` retirement (R301/R307, Done). The live build warning is `warnIfSplitQueryOnRecordParent` at **`FieldBuilder.java:4661`** (drifted +25 this window from the prior audit's `:4629-4636`, via R356/R367's FieldBuilder growth), keying on the reflection-derived **record-backed** determination, not a `TypeContext.hasDirective(typeDef,"record")` SDL predicate. Call sites at `:4317`/`:4402`/`:4484`/`:4609`. Re-spec against the current `warnIfSplitQueryOnRecordParent` wording, or **discard** if the SDL-only LSP can't mirror the reflection-derived determination. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines" and "~30 private methods"; `TypeFetcherGenerator.java` is **6 157** (~3.75× the cited figure) with **~171** private method declarations (~5.7×). Lightest re-spec in this section: refresh the LOC/method-count figures and re-survey the cut against the real file, or stop hard-coding a count. |
| **R19** history-squash | Ready | Every SHA/count is an April-2026 snapshot: "595 (as of 2026-04-24)", a "566-commit history" (lines 16/124/151), the "~303 docs + 263 code" split, the merge base `ab3daff2`, and a frozen list of SHAs plus the expected trunk tip `8a8c5efe`. The branch is now **~3 100 ahead** of `origin/main` (up from the prior audit's ~3 040; ~5.5× the documented 566). All numbers/SHAs/drop-lists need regeneration, or better, computing dynamically at execution time, before this can execute. |

## C. Outdated — update references only (work valid, refs stale) (12)

Substance intact; only paths, line numbers, or dependency tense drifted.
Composition unchanged from the prior audit. The recurring root cause is
unchanged: `TypeFetcherGenerator.java` (**6 157**) / `FieldBuilder.java`
(**5 937**) / `BuildContext.java` (**2 446**) standing at ~2.7 to 3.8× the sizes
the older specs cite, plus a `docs/*.md` → `docs/*.adoc` migration. This window
moved more anchors than the last (R356 / R364 / R365 / R366 / R367 landings); the
affected rows are flagged inline.

| Item | Stale reference |
|---|---|
| **R308** service-list-payload-arrival | Substance fully intact: the `@service` list-payload N+1 is real and unbuilt. But its body still describes the no-DataLoader shape as "`SingleRecordTableField` today, the single-arrival arm of the merged `RecordTableField` after R305", and `SingleRecordTableField` was **deleted** by R305. **Drifted this window:** the live carrier `RecordTableField` moved to `model/ChildField.java:829` (was `:807`; R366/R367 grew `ChildField`). Strip the "today" framing and read it as the single-arrival arm of `RecordTableField`; bump `last-updated`. (`depends-on` already `[]`.) |
| **R202** honor-field-directive-in-error-type-source-accessors | Premise live and unbuilt: `checkErrorTypeSourceAccessors` (`FieldBuilder.java:2474`, called `:2252`, both above the FieldBuilder insertion point and unchanged this window) calls `ClassAccessorResolver.resolve(sourceClass, sdlField.getName(), …)` with the raw SDL name and **no `@field` read**, exactly the gap R202 describes. R200's Done note names R202 as a remaining `@field`-symmetry sibling. Only staleness is the body's internal cites; re-anchor at the symbol. |
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck as "Obsolete (R287)"; body re-based onto R305's re-fetch substrate; `depends-on` `[]`. One numeric anchor stale: `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` cited at "`:944`" (3 sites) is now a single def at **`:631`** (called `:373`; FetcherEmitter unchanged this window). Re-anchor at the symbol and drop the stale "3 sites" count. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` (**679**, +52 this window from R356/R365) substance holds (emit-half still unbuilt). Anchors: the argument-level `@condition` rejection (`argCondition`) is now at `:412` (resolution dispatch `:415-423`); the `@condition(override:true)` admission gate reads `ARG_OVERRIDE` (the constant lives in `BuildContext.java:137`, consumed `:1745`). Body cites ~438-440 / :482-498; re-anchor at the symbols, which moved this window. |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`. The two cited sibling docs (`docs-as-index-into-tests.md`, `rewrite-docs-entrypoint.md`) do not exist under any extension (repo-wide `find` returns nothing). Relocate or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (absent repo-wide) and stale `ConnectionSynthesis` naming. The live seam is now `ConnectionPromoter.synthesiseForField` / `.rebuildAssembledForConnections` (`ConnectionPromoter.java:120`/`:194`); re-anchor there. |
| **R99** lsp-submodule-sibling-classpath | Path `graphitron-rewrite/graphitron-maven/…` (body `:18`) is actually `graphitron-maven-plugin/…`; the cited `getAllProjects()` is now reached via `reactorProjects()` at `AbstractRewriteMojo.java:208-209`. **Note this window:** R369 (`graphitron:dev` generated-sources) touched `AbstractRewriteMojo` (added `generatedSourceRoots`/`compileSourceRootsOf`) but addressed the *source-walk* root set, **not** R99's *classpath-scan* sub-module miss — R99's premise still reproduces. |
| **R92** catalog-check-constraint-validation | Seam anchors drifted (TypeFetcherGenerator local growth): `validatorPreStep` cited `:1326` is now defined **`:1655`** (was `:1641` prior audit; called `:1548`); `DefaultValidatorHolder` at `GraphitronContextInterfaceGenerator.java:84`, `getValidator` at `:95-99` (these two still accurate). Re-anchor the two TypeFetcherGenerator lines. |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue` emission cited at `:1456`/`:1496`/`:1508`/`:1769` is now clustered in `buildPerCellValueList` at **`TypeFetcherGenerator.java:2010`** (emitting `:2039`/`:2046`/`:2060`/`:2076`/`:2092`). |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog.reflectTableMethod` has two overloads (dispatcher `:484`, slot-types-aware `:498`); the strict `ClassName.equals` return-type gate is at `:527-529` (cited `:524-525`); `buildQueryTableMethodFetcher` is at **`TypeFetcherGenerator.java:1142`** (was `:1128` prior audit; called `:441`). Re-anchor at the symbols. |
| **R236** validator-reference-candidate-hint-terminal-table | (Anchors are in the **item body**, lines 17/27.) `classifyInputFieldInternal` is at **`BuildContext.java:1831`** (body cites `:1665-1677`), and the candidate-hint failure-aggregation (`columnSqlNamesOf(...)`) at **`:1920`** (BuildContext unchanged this window). Re-anchor at the symbol. |
| **R35** source-orientation-javadocs | LOC counts grossly stale (FieldBuilder cited "2 172", now **5 937**; TypeFetcherGenerator "1 646", now **6 157**); doc cross-links use `.md` and should be `.adoc` (`code-generation-triggers.md`, `selection-parser-audit.md`, `decompose-typefetchergenerator.md`, `changelog.md`). The "FieldBuilder decomposition shipped under R6" claim stays dubious given the file is now ~2.7× larger. |

**Re-confirmed Current (not flagged):** **R118** graphitron-mcp-server was
**widened into a programme** this window (b3f502be) and is correctly Backlog as the
programme umbrella; its slices **R361** (Done), **R362** (Ready), and **R368** (Spec)
are correctly tracked. **R267** nodeid-encoder-deprecated-convert (the deprecated
`col.getDataType().convert(values[i])` still emitted, class-wide `@SuppressWarnings`).
**R219** unify-inference-rule-by-javatypekey and **R220**
consolidate-sources-shape-predicates both target `ServiceCatalog` parameter-binding
inference, untouched this window; valid, unbuilt, not stale. **R222**
dimensional-model-pivot stays **Spec** as the umbrella (`depends-on: []`, Stages 5
through 7 outstanding). **R333** coordinate-lowers-to-datafetcher-queryparts (Spec)
took a code landing this window — folding `@sourceRow` into the join path
(d0954ad6) — without a status move; symbols spec-forward and not-yet-resolved as
expected. The new items **R362**/**R363**/**R365**/**R368**/**R370**/**R371** are
fresh, on current/fresh code, none under flag. **R360** (`retire-enum-directive`,
Backlog) stays valid: `@enum` is still live in `SchemaDirectiveRegistry`/`TypeBuilder`,
so the retirement is genuinely unbuilt, and its R261 coordination edge holds.

## Cross-cutting observations

1. **Closure hygiene stayed clean; the dependency graph held.** Every item that
   left the board this window self-closed cleanly (R353/R356 Done + files deleted;
   R361/R364/R366/R367/R369 filed-and-closed within the window). A full `depends-on`
   sweep across all 126 item files found **no dangling slugs**. **R30** (self-Done,
   unswept) remains the **only** stranded Done tombstone, alongside R146/R52's
   discard-toward-sibling Backlog files. The workflow rule that *the closing author
   deletes the file* still has no owner when the closure comes from a sibling
   (R146, R52). Worth a one-shot cleanup pass and a workflow note.
2. **This was a feature-landing window, not a pivot.** Unlike the throughput window
   the prior audit described, real code landed (polymorphic emission R365/R366/R367,
   the R356 overlap unification, R364's split-query fix, R353/R369 LSP/dev tooling,
   R361 MCP seam), but no structural axis moved: the flag composition held at exactly
   31 (3 / 16 / 12), no flagged item resolved or was created, and all closures plus
   all six new items landed on current/fresh items. The board grew from 122 to 126
   by ordinary accounting (−R353 −R356 +six filed, R361/R364/R366/R367/R369 net-zero).
3. **Line-drift recurred more sharply this window — from real code, not refactor
   churn.** `FieldBuilder` (**5 937**, +25), `GraphitronSchemaValidator` (**1 325**,
   +44), `MutationInputResolver` (**679**, +52), `model/ChildField` (`RecordTableField`
   `:807`→`:829`), and local +14 inside `TypeFetcherGenerator` (net −13) all moved
   anchors. The §B/§C rows that drifted are **R121** (+25 to `:4661`), **R245**
   (MutationInputResolver re-anchor), **R308** (`RecordTableField` `:829`), **R92**
   (`:1655`), and **R240** (`:1142`); the rest held. This is the sharpest evidence yet
   that specs anchored to **symbol names** rather than line numbers would stop the
   recurrence, and it remains the structural backdrop behind all of §C.
4. **The next structural pivot to watch is still the `SourceKey` decomposition.**
   R316's changelog named it the "first concrete consumer once this pivot lands"; it
   will collapse `wrap`+`cardinality` out of `SourceKey` (`SourceKey.java:81-82`,
   `:94-102` today). R333's `@sourceRow`-into-join-path fold this window moved the
   `@sourceRow` resolution but **left the `Wrap` contract intact**, so the watch holds:
   when the decomposition ships, **R71** (re-anchors on `SourceKey.Wrap`, which it
   removes), **R314** (the emit re-platforming R316 pinned to it), and **R234**'s
   input-carrier collapse will all need re-checking. This remains the standing watch.
5. **R222 remains the open umbrella** (Spec, `depends-on: []`). R316 implemented
   one of its slices and closed independently; Stages 5 through 7 (the
   `JooqRecordInputType` collapse R234 forward-dates, namespace collapse, directive
   narrowing) are still outstanding, consistent with R234's premise being premature,
   not wrong-in-spirit.
6. **The `@record` retirement (R301/R307) continues to bound a small staleness
   class — and R360 still holds the parallel one open for `@enum`.** `@record`
   survives as a *declared-but-ignored* directive; the one item building tooling on
   its *live-binding* behaviour, **R121**, stays mooted. **R360** proposes the same
   declared-but-rejected retirement for `@enum`; `@enum` is still live in code, so
   R360 is correctly Backlog and no item is yet stale against it.
   `classification-test-dsl-inventory.md` is doubly stale (R299/R290 + the R316 corpus
   recut) and still warrants the "superseded — historical" banner; it has **not** been
   added (left unedited here per scope).
7. **The MCP programme is now the live frontier.** R118 widened into a programme
   (b3f502be) with R361 (Done), R362 (Ready), and R368 (Spec) as slices, plus R341
   (Done last window) as the skeleton. None is flagged; all are spec-forward and
   correctly tracked. Worth watching that the slice specs re-anchor cleanly as the
   `Workspace`/`CatalogFacts` seam fills in.
8. **Cosmetic front-matter nits, none flag-worthy (carried over).** **R333**
   (`coordinate-lowers-to-datafetcher-queryparts`, Spec) **still has no `depends-on:`
   key** despite a code landing this window; add one for parseability on its next
   touch. **R97** lacks `created:`/`last-updated:` header fields (an older item
   predating that convention). Neither is a build or dependency risk.
9. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-06-25._
