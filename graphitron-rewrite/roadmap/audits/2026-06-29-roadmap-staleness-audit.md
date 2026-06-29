# Roadmap staleness audit — 2026-06-29

A point-in-time review of every active roadmap item under
[`graphitron-rewrite/roadmap/`](../) against the **current** state of the
codebase on `claude/graphitron-rewrite` (HEAD `5a03de2d`, 2026-06-28 08:15). The
goal is to find items whose premise no longer holds: work already shipped,
constructs renamed or removed, dependencies that have since landed, or specs
grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-06-26` staleness audit, which has been deleted;
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

## Changes since the 2026-06-26 audit

**77 commits** landed between the prior audit's review point (`b90d0a21`,
committed 2026-06-25 19:14) and this one (HEAD `5a03de2d`, 2026-06-28 08:15). The
prior audit file itself was committed at `f8032acd`, the first substantive commit
after its review point. Unlike the previous (single-axis MCP) window, this one
had **three** active fronts and — for the first time in several windows — moved
the flag composition: **a flagged item was resolved by a targeted re-spec
(R13), and a second was downgraded a tier (R201, §B → §C).**

The three fronts:

- **The `@reference` frontier matured.** **R379** (`@reference` path
  joins-compile validation, terminal-hop target + condition-param tables) and
  **R380** (`@reference` join-subquery filter conditions, the correlated `EXISTS`
  on both filter surfaces) both closed Done and **deleted their files**. R379's
  work added a typed `TerminalTargetVerdict` and reworked `BuildContext.parsePath`,
  which is the dominant cause of the `BuildContext.java` line drift this window
  (**2 672**, +207). R380 filed **R387** as a Backlog follow-up (migrate
  `TypeConditionsGeneratorTest` off code-string assertions).
- **Discriminated / joined-table inheritance opened as a cluster.** **R388**
  (qualify the discriminator column + reject `@reference`-on-base-column) and
  **R392** (route the discriminated `TypeResolver` off a synthetic
  `__discriminator__` alias) closed Done within the window; **R389**
  (first-class discriminated joined-table inheritance) was filed and is now
  **Spec** (it walked Backlog→Spec→Ready→In Progress→reopened-Spec this window);
  **R393** (base→detail FK override disambiguation, `depends-on: [R389]`) and the
  roadmap-tool tripwire item were filed.
- **MCP slices 9–10 landed.** **R385** (`docs.search`, R118 slice 9) closed
  Done; **R386** (`catalog.search`, slice 10) is **In Review**. Plus **R390**
  (retain connection-carrier element subgraph) and **R391** (facade
  default-case `newGraphQL()` helper) both closed Done within the window.

**Re-spec activity on flagged / dependency items (the composition movers):**

- **R13** `faceted-search` → **resolved out of the flagged set.** Heavily
  re-specced this window (the 1 125-line rewrite, Spec → Ready). The prior audit
  flagged it (§B) because Phases 2–4 were written against the retired
  `ConnectionSynthesis.buildPlan()` pipeline; the rewrite re-anchors onto the
  live `ConnectionPromoter.synthesiseForField` (`ConnectionPromoter.java:122`) /
  `rebuildAssembledForConnections` (`:196`) seam, names the dead `buildPlan()`
  explicitly, and relocates facet specs onto the slim 2-arg `FieldWrapper.Connection`
  / `GraphitronType.ConnectionType`. **The spec no longer misleads — R13 is now
  a current Ready item, not flagged.**
- **R45** `tenant-routing-and-execution-input` (not itself flagged) → Spec →
  Ready, reworked onto a per-field `TenantIdSource` overlay + `byTenant` on
  `GraphQLContext`, **dropping** the sealed `ContextValueRegistration` design.
  This sharpens the **R46** flag (see §B): R46 extends a design its own
  dependency has now abandoned.
- **R99** `lsp-submodule-sibling-classpath` → Spec → Ready with a partial ref
  refresh. The two `DevMojo` cites the sign-off named were refreshed, but the
  bulk of the `AbstractRewriteMojo` cites still drifted, so R99 **stays flagged**
  (§C).

**Terminal closures this window (Done, all self-deleted their files):** R379,
R380, R383 (`multitable-interface-filter-extraction-kinds`, the R363 follow-up).
None was flagged; all were current.

**Filed and closed within the window (net zero; leave no file, recorded in
`changelog.md`):** R385, R388, R390, R391, R392.

**New items still on the board (six, all filed this window, all current/fresh):**
R384 (`multitable-interface-converted-nodeid-condition-filters`, Spec), R386
(`mcp-catalog-search`, In Review), R387 (`type-conditions-test-code-string-migration`,
Backlog), R389 (`discriminated-joined-table-inheritance`, Spec), R393
(`joined-table-base-detail-fk-override`, Backlog), R394
(`roadmap-tool-tripwire-buildfailure`, Backlog). All re-verified spec-forward on
fresh code; every named anchor resolves; no dangling `depends-on`.

**R393 renumbering is clean.** The tripwire item was first filed as R393
(`f2b6ec50`), the duplicate-id was resolved (`2264c624`, tripwire → **R394**),
and R393 was repurposed to the base→detail FK override (`5a03de2d`). Current
state: exactly one R393 and one R394; `next-id: R395`; no duplicate remains.

A full `depends-on` sweep across all **130** item files (re-run programmatically
this window) found **no dangling slugs**. **R30** remains the **only** stranded
Done tombstone (re-confirmed: a non-recursive `^status: Done` grep over
`roadmap/*.md` returns exactly `selection-parser-audit.md`).

**Net effect on flag counts: 30 flagged, 100 current** (prior window: 31/96).
Board accounting: prior total 127 items, minus three Done closures whose files
were deleted (R379, R380, R383), plus six filed this window (R384, R386, R387,
R389, R393, R394), with R385/R388/R390/R391/R392 filed-and-closed within the
window (net zero), gives **130** items today. Flag composition moved from
(3 / 16 / 12) to **(3 / 14 / 13)**: R13 resolved and left the flagged set
entirely; R201 was reclassified §B → §C (its misleading framing was already gone,
leaving only line-anchor drift). All eleven closures and all six new items landed
on **current/fresh** items, none under flag.

## Scope and method

All **130** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder). For each item the targets it names
(classes, directives, methods, packages, modules) were located in the current
tree under `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/`,
the described problem was checked for whether it still reproduces, and the
changelog was scanned for the item's `R<n>` and key terms to catch work that
shipped without the item being closed.

Every flagged item carried over from the prior audit was re-verified against
current HEAD. The structural landings the prior audits relied on all still hold:
**R276** (`ResultType` is a 4-arm seal at `GraphitronType.java:93-94`;
`PojoResultType` permits only `Backed` at `:120`), **R290**
(`LeafTupleAdapter` / `ConstructorField` dissolved), **R305**
(`SingleRecordTableField` gone; live carrier is `RecordTableField`), and **R316**
(the `carrier × intent × mapping` field model gone, replaced by `(source, operation,
target)`; `model/Operation.java` is a sealed interface at `:30`). The **`SourceKey`
`Wrap` decomposition still has not landed** (`SourceKey.java:81-82` still carries
`Wrap wrap` + `Cardinality cardinality`), so the standing R71 / observation 4
watch holds.

Line-anchor drift this window was concentrated in two files: **`BuildContext.java`**
(**2 672**, +207, from R379's `parsePath` / `TerminalTargetVerdict` rework) and
**`FieldBuilder.java`** (**6 290**, +153, from R383's nested-input multitable
filter lowering). `TypeFetcherGenerator.java` (**6 196**, +21) and
`GraphitronSchemaValidator.java` (**1 343**, +21) drifted modestly;
`MutationInputResolver.java` (**679**) held exactly. The BuildContext-anchored
rows (R263, R236, R66, R245) and the FieldBuilder-anchored rows (R201, R202,
R121) drifted the most.

**Result: 30 items flagged, 100 current.** Line numbers cited below are as of the
review date and will themselves drift; see observation 3.

## A. Obsolete — should leave the active roadmap (3)

Each shipped or was superseded by a sibling already at Done. Because the closure
came from the sibling rather than a self-transition, no author ran the
file-deletion sweep. Composition unchanged from the prior audit.

| Item | Status | Action | Why |
|---|---|---|---|
| **R30** selection-parser-audit | **Done** | **Delete the file** | Status is `Done` (re-confirmed `selection-parser-audit.md:4`). Per workflow Done items are deleted. Re-verified the **sole** stranded Done tombstone (`^status: Done` grep over `roadmap/*.md` returns exactly this one file). Nothing `depends-on` it (the programmatic slug sweep across all 130 files confirms zero inbound edges; the only other files naming it, `changelog.md` and `source-orientation-javadocs.md`, are prose cross-references); it carries no README rollup row, so it is not a build risk, purely an unswept tombstone. If the "parser is needed, keep it" finding is worth retaining, capture it in `changelog.md` first, then delete. **Clean delete, unambiguous.** (Stranded across fourteen audits now.) |
| **R146** mutation-cardinality-safety-unique-index | Backlog | **Discard → delete** (via transition) | R246's changelog entry (Done): "**Absorbs R146 (PK-or-UK coverage, discarded)**" (re-verified verbatim, now `changelog.md:294`; the prior audit's `:289` drifted under new entries this window). Re-confirmed in current code: `JooqCatalog.candidateKeys(String)` (`:606`, `Table<?>` overload `:617`) feeds the PK-preferred subset match in `walker/MatchedKeys.java:59-60` (`MatchedKey.PrimaryKey`/`UniqueKey`, sealed at `model/MatchedKey.java:18`), with a `NoUniqueKeyCoverage` rejection on `UpdateRowsError.java:51` (and a sibling `DeleteRowsError.NoUniqueKeyCoverage` at `DeleteRowsError.java:54` — additional confirmation the coverage shipped), reached from `UpdateRowsWalker`. R246 is a changelog-only tombstone, not a standalone `R<n>` file, but the "Absorbs R146" wording is present. The `status: Backlog` is stale; the correct mechanism is a `Backlog → Discarded` transition (which couples with file deletion per the workflow), not a raw delete. |
| **R52** lift-operation-taxonomy | Backlog | **Discard → delete** | Obsoleted *as a carrier-build item* by R316: `model/Operation.java` is a sealed interface (`:30`) whose `Lookup` arm (`:59`) sits beside `Fetch` (`:41`), `Paginate` (`:54`), and `ServiceCall` (`:75`) — and R316 has since **expanded** the seal well beyond these (now also `Count`/`Facet`/`Nest`/`NodeResolve`/`EntityResolve`/writes), so the structural handle R52 named demonstrably exists. The "thin remainder" the prior audit hedged on (migrate dispatch onto the `Operation` axis) does **not** justify keeping the item: `Operation.Lookup` is only *constructed* as a derived projection (`ChildField.java:112-114`, `QueryField.java:48`); grep finds **no consumer reading `.operation()`** to ask "is this a lookup?", and dispatch still forks on leaf/variant identity (`TypeFetcherGenerator.java:434`/`:524`, `LookupMapping.ColumnMapping` instanceof checks). R52's re-spec trigger ("once a cross-cutting consumer needs to ask … without dispatching through variant-shape inspection") has not fired and there is no signal it will. **Discard → delete; re-file fresh if such a consumer ever appears.** |

## B. Outdated — needs re-spec (premise or targets materially changed) (14)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. All fourteen
carry over from the prior audit and were re-verified this window; **every one
still reproduces.** Two §B rows from the prior audit left this section this
window: **R13** (resolved by re-spec, now a current Ready item) and **R201**
(downgraded to §C — its misleading framing was already gone, leaving only
line-anchor drift). Line-anchor drift this window is called out per affected row;
the BuildContext-anchored rows (R263, R66) and the FieldBuilder-anchored rows
(R121) drifted the most.

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; still the sharpest.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm R276 deleted: `ResultType` is a 4-arm seal (`GraphitronType.java:93-94`), `PojoResultType` permits only `Backed` (`:120`). Its primary migration target `FetcherEmitter.propertyOrRecordValue` **no longer exists** (zero hits, though `FetcherEmitter.java` still exists); only the `GeneratorUtils.buildFkRowKey` half of the duplication survives (`:241`, called `:212`; `backingClassOf` now `:367`; the shared `recordColumnReadArgs` dispatcher at `:257`). The `.md` still says "five-arm sealed interface (`GraphitronType.java:91`)" with a `PojoResultType.NoBacking` arm. Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. |
| **R71** recordn-key-parity-lifter | Backlog | **Symbol re-anchor; substance confirmed still future, and the standing structural watch.** Every symbol the mechanism names is dead: there is **no** `BatchKeyLifterDirectiveResolver.java`, **no** `BatchKey` type (the lone "BatchKey" hit is a comment in `GraphitronSchemaValidator.java:957`), and no `LifterRowKeyed`/`RecordParentBatchKey`/`RowKeyed`/`MappedRowKeyed`/`RecordKeyed`/`MappedRecordKeyed` permits (R110/R222/R290/R305 reworked the surface). The live surface is `model/LifterRef.java:25`, routed through `@sourceRow` (`SourceRowDirectiveResolver`) and `SourceKey.Reader.SourceRowsCall(LifterRef)` (`SourceKey.java:284`). R316 did **not** collapse `wrap`+`cardinality` out of `SourceKey` (`SourceKey.java:81-82` still carries `Wrap wrap` + `Cardinality cardinality`; `Wrap.{Row,Record,TableRecord}` at `:94`/`:96`/`:102`), and the compact constructor (`public SourceKey {` at `:117`) still pins `SourceRowsCall → Wrap.Row` at `:124-127`, the exact Row-only asymmetry R71 wants to remove. Re-anchor on `LifterRef`/`SourceRowsCall`/`Wrap`. |
| **R46** service-multi-tenant-fanout | Backlog | **Now worse than the prior audit found — the dependency abandoned the design R46 extends.** R190 (Done) sealed `GraphitronContext` to its generated `Impl` plus a `newExecutionInput(...)` factory, dissolving the `ContextValueRegistration<FanOut>` design the whole "Design" section rests on (`ContextValueRegistration`, `getContextFanOut`, `openContextDslContext`, `getExecutor` all have zero `.java` hits). **New this window:** R46's dependency **R45** (`tenant-routing-and-execution-input`, Spec → Ready) was reworked to **drop** the sealed `ContextValueRegistration` design entirely, routing tenancy through a per-field `TenantIdSource` overlay + `byTenant` on `GraphQLContext`. So R46 now proposes extending a type R45 will never ship. The `depends-on:` correctly uses the post-rename slug `tenant-routing-and-execution-input` (= R45), but the body prose still links the dead `typed-context-value-registry.md` slug at lines 17/156. Re-spec the whole Design section against the `TenantIdSource` overlay R45 actually ships. |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | **Premise factually inverted, and its R222 reference is now also broken.** The body claims R222 *already* collapsed `JooqRecordInputType` by rejecting any non-`TableRecord` jOOQ `Record`, but `JooqRecordInputType` is a live, populated input arm (`GraphitronType.java:342`, permitted by `InputType` at `:303-304`), the quoted "…not a TableRecord…" rejection is **absent**, and `JooqTableRecordInputType` exists as a *separate* sibling (`:357`). The `BackingClass` family R234 proposes was never built (no `BackingClass.java`; `parentBackingClass` exists only as a component on `AccessorRef.java:41`). The cited "R222" is `dimensional-model-pivot.md` (Spec), whose own body lists `JooqRecordInputType` as one of four live `InputType` variants — affirmatively contradicting R234's premise. Re-spec: the "collapse already happened, now reintroduce arms" narrative is inverted; gate behind the R222 collapse actually shipping (Stages 5–7). |
| **R314** dissolve-reentry-leaves-dimensional-emit | Backlog | **Prior finding ("written entirely in retired vocabulary") overstated, but a residual still misleads.** The lead (lines 16-19) was already reworked to frame the dimensional axes as a "*derived view* over [the leaves], not the emit substrate." But the design section still names R316-deleted types as the build target: `Carrier.Source` (line 32), `Intent`/`Mapping` as proposed sealed hierarchies (lines 34-37), and `f(carrier.sourceContext, intent.operation, mapping.target)` (line 40) — an implementer would search for symbols that no longer exist. R316 *narrowed* R314's scope rather than mooting it: the `dispatchPerformsReFetch` mirror retirement and the transitional `Operation.ServiceCall(Call)` one-carrier collapse are both pinned to R314 (`Operation.java:75`/`:78` "Collapses to one carrier under R314"; both `requiresReFetch` in `model/OutputField.java:128` and `dispatchPerformsReFetch` in `GraphitronSchemaValidator.java:132`, called `:160`, still co-exist). Re-spec the design section's prose onto `(source, operation, target)`; `depends-on: [dimensional-model-pivot]` (R222, still Spec) is the genuine blocker and resolves. |
| **R263** decode-helper-typename-first-resolution | Backlog | **Discard recommendation now firm — R377's rewrite settled the open question, and no `@nodeId(typeName:)`-authoritative caller appeared.** The trap method `resolveDecodeHelperForTable` moved `BuildContext.java:2226`→**`:2433`** (signature `(sqlTableName, fallbackTypeId, keyColumns)`); R377 replaced its body, which now resolves through the `@node` `NodeIndex` (`nodes.forTable(sqlTableName)` at `:2442`), returns the single node's `decodeMethod()` (`:2444`), `null` on multi-node ambiguity (`:2450`), and falls back to the `typeId` suffix only when no `@node` backs the table (`:2456-2461`); it no longer calls `findGraphQLTypeForTable` (which survives unrelated at `:2294`). The four callers persist (`:2248`/`:2383` internal, `FieldBuilder.java:1315`, `NodeIdLeafResolver.java:275`); **all** pass the table name as primary, **none** route an authoritative `@nodeId(typeName:)` to drive the suffix. The resolution is node-table-first, still not typeName-first, so R263's premise survives only in spirit. **Discard as speculative** (re-open only if a MUST-route `@nodeId(typeName:)` caller appears); re-anchor onto the R377 body before recording the discard. |
| **R269** nullable-to-one-record-into-npe | Spec | R268 shipped (helpers take a `sourceExpr`) and R271 (dunder retirement) shipped, renaming `__elt`→`element`/`__k`→`key`, so the spec's `__elt`/`__k` snippets (lines 22-23, 29, 35) are triply stale (helpers now `GeneratorUtils.buildAccessorKeySingle` `:318`, `buildAccessorKeyMany` `:338`), and the "if R268 lands first … if R269 lands first" coordination fork (lines 62-70) is resolved (R268 landed first). The success-arm NPE itself is **still unguarded** (`GeneratorUtils.java:332-334` emits `element = (($T) sourceExpr).accessor()` then `key = element.into(...)` with no null check, while the sibling source-bound FK path `buildKeyExtractionWithNullCheck` guards at `:393`/`:407`). Strip the snippets and the R268 framing; re-anchor on `element`/`key`. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (`R58TypedRejectionPipelineTest` in tree, R58 Done), so the "R58 is currently *In Review* … if it reverts" framing (lines 21-23) is false and should be removed. Carriers are still String-flattened (`ParsedPath(…, String errorMessage)` now at `BuildContext.java:990`, drifted +16 from the prior audit's `:974`; `Unresolved` at `InputFieldResolution.java:20`; the changelog defers these to R66 explicitly), so the carrier-audit body stays valid; `depends-on: []` is already correct. |
| **R261** wire-coercion-cast-guard | Backlog | The specific reported instance (`(SakRecord) raw.get(...)`, site A) is handled by shipped R195 (`CallSiteExtraction.NodeIdDecodeRecord`, defined `model/CallSiteExtraction.java:206`, re-confirmed consumed across **7** files). Update the coordination framing (lines ~100-105) to past tense; the raw-cast invariant for sites B through E is still unbuilt (raw `(($T) raw.get($S))` casts persist e.g. `InputBeanInstantiationEmitter.java:189`). **Carried over:** R360 (`retire-enum-directive`, Backlog) explicitly hands R261 the enum-name-divergence rejection at the column-binding site (`retire-enum-directive.md:29-30` names R261); note the incoming edge when re-specing. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)" (line 2; README too); R94 shipped (Done, `changelog.md:373`, slug `emit-input-records`). Blocker cleared, drop the blocked framing from title and body. R94's Done note hands the real remaining dependency to **R98** (`multi-source-input-validation`, Backlog: re-verified "Unblocks R98 … and R170"), so re-point the dependency there and the title "(R94-blocked)" should become "(R98-blocked)". |
| **R24** nodeidreferencefield-join-projection-form | Backlog | Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **no longer exists** (dispatch is now `FetcherEmitter.bind` `:179`/`bindRaw` `:359`), and the carrier in the **item title**, `NodeIdReferenceField`, was renamed by R50 to `ColumnReferenceField`/`CompositeColumnReferenceField`. The surviving "not yet implemented" stub is for `CompositeColumnReferenceField` at `FetcherEmitter.java:509-517` (the `UnsupportedOperationException` "requires JOIN-with-projection emission" at `:513-516`); the `ColumnReferenceField` direct-compaction arm at `:503-508` is implemented (`columnByAlias`). Work is valid and unbuilt, but title symbol + method names need a full re-anchor. |
| **R121** lsp-diagnostic-redundant-splitquery-on-record | Backlog | Mooted/reframed by the `@record` retirement (R301/R307, Done). The live build warning is `warnIfSplitQueryOnRecordParent` now at **`FieldBuilder.java:5013`** (drifted +152 this window from the prior audit's `:4861`), keying on the reflection/classification-derived **record-backed** determination (`fieldDef.hasAppliedDirective(DIR_SPLIT_QUERY)`), not a `TypeContext.hasDirective(typeDef,"record")` SDL predicate. Call sites at `:4669`/`:4754`/`:4836`/`:4961` (all +152; count unchanged at four). Re-spec against the current `warnIfSplitQueryOnRecordParent` wording, or **discard** if the SDL-only LSP can't mirror the reflection-derived determination. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines" and "~30 private methods"; `TypeFetcherGenerator.java` is **6 196** (~3.75× the cited figure) with **162** `private`-method matches plus nested type declarations. Lightest re-spec in this section: refresh the LOC/method-count figures and re-survey the cut against the real file, or stop hard-coding a count. |
| **R19** history-squash | Ready | Every SHA/count is an April-2026 snapshot: "595 (as of 2026-04-24)", a "566-commit history" (lines 14/22/124), the merge base `ab3daff2`, the expected trunk tip `8a8c5efe` (line 101), and a frozen list of SHAs. The branch is now **3 252 ahead** of `origin/main` (~5.7× the documented 566); the current merge base is **`65299a7b`** and `origin/main` is at **`143f4ae0`**. All numbers/SHAs/drop-lists need regeneration, or better, computing dynamically at execution time, before this can execute. |

## C. Outdated — update references only (work valid, refs stale) (13)

Substance intact; only paths, line numbers, or dependency tense drifted.
**R201** joined this section this window (downgraded from §B: its misleading
"R244 moots R201" framing is already absent and the body already frames it as
the output-side mirror of R200 — only line anchors drifted). The recurring root
cause is unchanged: `TypeFetcherGenerator.java` (**6 196**) / `FieldBuilder.java`
(**6 290**) / `BuildContext.java` (**2 672**) standing at ~2.7 to 3.8× the sizes
the older specs cite, plus a `docs/*.md` → `docs/*.adoc` migration. R379's
`BuildContext` rework (+207) and R383's FieldBuilder growth (+153) moved the most
anchors; affected rows are flagged inline.

| Item | Stale reference |
|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | **Newly downgraded from §B.** The premise is live and unbuilt: `resolvePayloadConstructionShape` on the `PayloadClass` arm (`FieldBuilder.java:490`, called `:2558`); the mutable-bean logic is extracted into `tryMutableBean` (`:555`), whose setter-match arm builds `SetterBinding`s off the raw SDL field name via `javaBeanSetterName(sdlFieldName)` (`:573-594`, predicate `:602`) with **no `@field` read**; the all-fields-ctor arm picks positionally by `getParameterCount()` (`:505`/`:510`/`:533`). The emitter halves are at `TypeFetcherGenerator.payloadFactoryLambda` (`:6097`) and `payloadFactoryLambdaSetters` (`:6114`). The body **already** frames it as "the output-side mirror of R200" with the "R244 moots R201" framing gone (the prior audit's recommended reframe was already in place); only the cited line ranges (`:506-609`/`:589-591`/`:519-535`/`:498-505`) drifted from the `tryMutableBean` extraction. Re-anchor at the symbols; no premise change. |
| **R308** service-list-payload-arrival | The `@service` list-payload N+1 is real and unbuilt (`checkServiceReturnMatchesPayload` `FieldBuilder.java:3017`, called `:2978`, still admits `List<Payload>`). The body still describes the no-DataLoader shape as "`SingleRecordTableField` today, the single-arrival arm of the merged `RecordTableField` after R305", and `SingleRecordTableField` was **deleted** by R305 (surviving only in Javadoc/comments repo-wide — not just `ChildField.java` as the prior audit implied: also `OutputField.java`, `FetcherRegistrationsEmitter.java`, `TypeFetcherGenerator.java`, `GraphitronSchemaValidator.java`, `FieldBuilder.java`). The live carrier `RecordTableField` is at `model/ChildField.java:829`. Strip the "today" framing; bump `last-updated`. (`depends-on` already `[]`.) |
| **R202** honor-field-directive-in-error-type-source-accessors | Premise live and unbuilt: `checkErrorTypeSourceAccessors` (`FieldBuilder.java:2759`, called `:2537`) calls `ClassAccessorResolver.resolve(sourceClass, sdlField.getName(), …)` (the resolve call now `:2778-2783`, `sdlField.getName()` passed at `:2780`) with the raw SDL name and **no `@field` read**, exactly the gap R202 describes. **Drifted +~150 this window** (def `:2607`→`:2759`, call `:2385`→`:2537`) from R383's FieldBuilder growth. R200's Done note names R202 as a remaining `@field`-symmetry sibling. Re-anchor at the symbol. |
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck as "Obsolete (R287)"; body re-based onto R305's re-fetch substrate; `depends-on` `[]`. One numeric anchor stale: `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` cited at "`:944`" (3 sites) is now a single def at **`:631`** (called `:373`; the `isList` arm iterates the RETURNING `Result<Record>` and appends one id per row at `:651-661` — substance intact). Secondary: the `[ID]`-reject scoped to `CarrierFamily.DML` cited "`BuildContext.java:617-625`" is now at `:794-802`. Re-anchor at the symbols and drop the stale "3 sites" count. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` (**679**, held exactly this window despite R380's `@reference` touch) substance holds (emit-half still unbuilt). Anchors: the argument-level `@condition` resolution dispatch is at `:412-418`, the non-`@table` arg rejection (`foundTia.argCondition().isPresent()`) at `:436`; the `@condition(override:true)` admission gate at `:493-494` reads `ARG_OVERRIDE` (the constant lives in `BuildContext.java:137`, consumed at `:1952` — drifted from the prior audit's `:1745`). Body cites ~438-440 / :482-498; re-anchor at the symbols. |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`. The two cited sibling docs (`docs-as-index-into-tests.md`, `rewrite-docs-entrypoint.md`) do not exist under any extension (repo-wide search returns nothing). Relocate or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (absent repo-wide) and stale `ConnectionSynthesis` naming. The live seam is now `ConnectionPromoter.synthesiseForField` / `.rebuildAssembledForConnections` (`ConnectionPromoter.java:122`/`:196`, +2 this window; **R390 did not remove `rebuildAssembledForConnections`**); re-anchor there. |
| **R99** lsp-submodule-sibling-classpath | Ready (re-specced this window with a **partial** ref refresh). The two `DevMojo` cites the sign-off named were refreshed (startup `~:170`, WARN `~:180`), but the bulk of the `AbstractRewriteMojo` cites still drifted: `reactorProjects()` ~208→**227-231** (still `session.getAllProjects()` with a `List.of(project)` fallback and **no parent-pom walk-up** — the single-project-reactor sub-module miss still reproduces), `resolveClasspathRoots()` ~221→**240**, `resolveCompileSourceRoots()` ~254→**273**, `buildCodegenLoader()` ~491→**510**, `unwalkedScannedModules()` `:349`→**367**/**394**. The body's `graphitron-maven` → `graphitron-maven-plugin` path correction is in place. Finish the `AbstractRewriteMojo` ref refresh. |
| **R92** catalog-check-constraint-validation | Seam anchors drifted (TypeFetcherGenerator local growth): `validatorPreStep` cited `:1326` is now defined **`:1694`** (was `:1673` prior audit; called **`:1587`**); `DefaultValidatorHolder` at `GraphitronContextInterfaceGenerator.java:84`, `getValidator` at `:95-99` (body cites `:76-85`/`:87-97`, slightly off → re-anchor). No `CheckRecognizer`/`findCheckConstraints` exists; the R12 §5 validator seam this builds on is present as described. Re-anchor the two TypeFetcherGenerator lines and the two context-gen lines. |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue` emission cited at `:1456`/`:1496`/`:1508`/`:1769` is now clustered in `buildPerCellValueList` (defined **`TypeFetcherGenerator.java:2063`**, emitting `:2092`/`:2099`/`:2113`/`:2129`/`:2145`), with a deduped sibling `buildPerCellValueListDeduped` at `:2236` (emitting `:2264`/`:2269`). The contract-side gap (no SDL default lift) is unbuilt. |
| **R240** tablemethod-return-type-token-threading | The `.md` was touched this window (`87b35273`) but only to scrub `@LoadBearingClassifierCheck` framing — **not** a re-anchor, so its `:1035`/`:1114`/`ServiceCatalog.java:498` cites are unchanged-stale. Current: `ServiceCatalog.reflectTableMethod` has two overloads (dispatcher `:484`, slot-types-aware `:498`); the strict `ClassName.equals` return-type gate is at `:528-529`; `buildQueryTableMethodFetcher` is at **`TypeFetcherGenerator.java:1181`** (was `:1160` prior audit; called `:443`). Neither `MethodRef.StaticOnly` nor `ReturnTypeRef.TableBoundReturnType` carries a type token yet. Re-anchor at the symbols. |
| **R236** validator-reference-candidate-hint-terminal-table | (Anchors are in the **item body**, lines 17/27.) `classifyInputFieldInternal` is now at `BuildContext.java:2038` (body cites `:1665-1677`), and the candidate-hint failure-aggregation (`candidateHint(c, catalog.columnSqlNamesOf(resolvedTable.tableName()))`) at `:2127` (both drifted ~+200 this window from R379's BuildContext rework). R379/R380's `@reference` work did **not** touch the candidate-hint logic; the hint still draws from the path-origin `resolvedTable.tableName()`, not the `@reference` terminal table (R380's Done note still lists R236 as an open sibling). Re-anchor at the symbol. |
| **R35** source-orientation-javadocs | LOC counts grossly stale (FieldBuilder cited "2 172", now **6 290**; TypeFetcherGenerator "1 646", now **6 196**); no `package-info.java` exists in the rewrite tree (sweep undone). Doc cross-link nuance: of the cited links, only `code-generation-triggers` actually migrated to `.adoc` and is wrongly linked as `.md` (lines 33/35); `selection-parser-audit` and `decompose-typefetchergenerator` are roadmap `.md` files and are correctly `.md`. The "FieldBuilder decomposition shipped under R6" claim is *literally* true (R6 is Done, shrank FieldBuilder ~3 301→~2 534 at the time) but the file has since regrown to 6 290 (~2.5× its post-R6 size), so the cross-reference is no longer helpful. |

**Re-confirmed Current (not flagged):** the **MCP programme (R118)** is correctly
Backlog as the programme umbrella now that slices 9 (R385, Done) and 10 (R386, In
Review) have landed/are landing; slice 11 (an optional Method-search tool) is the
live remainder, spec-forward. **R13** (`faceted-search`, **Ready**) — re-specced
this window onto the live `ConnectionPromoter` seam; no longer flagged. **R45**
(`tenant-routing-and-execution-input`, **Ready**) — reworked onto a `TenantIdSource`
overlay, internally consistent, reconciled with R190/R316, not stale. **R267**
nodeid-encoder-deprecated-convert (the deprecated `col.getDataType().convert(values[i])`
still emitted at `NodeIdEncoderClassGenerator:248`, class-wide `@SuppressWarnings`
at `:151`; distinct from R384's `ArgCallEmitter`-site deprecation handling — no
conflict). **R219** unify-inference-rule-by-javatypekey and **R220**
consolidate-sources-shape-predicates both target `ServiceCatalog` parameter-binding
inference (`looksLikeSourcesShape` `:758`, `couldBeSourcesShape` `:1083`,
`classifySourcesType` `:798`, still three separate predicates; arity/type-unique
branches at `:1142` still unconsolidated), untouched this window; valid, unbuilt.
**R222** dimensional-model-pivot stays **Spec** as the umbrella (`depends-on: []`,
Stages 5 through 7 outstanding). **R333** coordinate-lowers-to-datafetcher-queryparts
(Spec) — no status move and still no `depends-on:` key; correctly not flagged. The
new items **R384**/**R386**/**R387**/**R389**/**R393**/**R394** are fresh, on
current/fresh code, none under flag. **R360** (`retire-enum-directive`, Backlog)
stays valid: `@enum` is still live (`SchemaDirectiveRegistry.java:61`,
`TypeBuilder.java:1899`, `directives.graphqls:67`), so the retirement is genuinely
unbuilt, and its R261 coordination edge holds.

## Cross-cutting observations

1. **The flag composition moved this window — for the first time in several
   windows.** Two items left §B: **R13** was resolved by a targeted re-spec (the
   first time in this audit series that a flagged item was cleared by re-specing
   rather than by code shipping), and **R201** was downgraded §B → §C once its
   misleading framing was confirmed already gone. No new item entered the flagged
   set: all eleven closures and all six new items landed on current/fresh code.
   Net 31/96 → 30/100.
2. **Closure hygiene stayed clean; the dependency graph held.** Every item that
   left the board this window self-closed cleanly (R379/R380/R383 Done + files
   deleted; R385/R388/R390/R391/R392 filed-and-closed within the window). The
   R393 duplicate-id was resolved cleanly (tripwire → R394, R393 repurposed); one
   R393, one R394, `next-id: R395`. A full `depends-on` sweep across all 130 item
   files found **no dangling slugs**. **R30** (self-Done, unswept) remains the
   **only** stranded Done tombstone, alongside R146/R52's discard-toward-sibling
   Backlog files. The workflow rule that *the closing author deletes the file*
   still has no owner when the closure comes from a sibling (R146, R52). Worth a
   one-shot cleanup pass and a workflow note.
3. **Line-drift recurred, concentrated in two files this window.** R379's
   `parsePath` / `TerminalTargetVerdict` rework grew `BuildContext` (**2 672**,
   +207), and R383's nested-input multitable filter lowering grew `FieldBuilder`
   (**6 290**, +153). The BuildContext-anchored rows that drifted most are
   **R263** (method def `:2226`→`:2433`), **R236** (`:1831`→`:2038`, hint
   `:1920`→`:2127`), **R66** (`:974`→`:990`), and **R245** (`ARG_OVERRIDE` consume
   `:1745`→`:1952`); the FieldBuilder-anchored rows are **R121** (+152 to `:5013`),
   **R202** (`:2607`→`:2759`), and **R201** (the `tryMutableBean` extraction). R263
   remains the sharpest evidence that even symbol-anchored specs go stale when the
   symbol's *body* is rewritten — the only durable defense is re-reading the method
   at re-spec time. This is the structural backdrop behind all of §C.
4. **The next structural pivot to watch is still the `SourceKey` decomposition.**
   R316's changelog named it the "first concrete consumer once this pivot lands"; it
   will collapse `wrap`+`cardinality` out of `SourceKey` (`SourceKey.java:81-82`,
   `:94-102` today). Re-confirmed verbatim this window: the `Wrap` contract is
   untouched, so the watch holds. When the decomposition ships, **R71** (re-anchors
   on `SourceKey.Wrap`, which it removes), **R314** (the emit re-platforming R316
   pinned to it), and **R234**'s input-carrier collapse will all need re-checking.
5. **R222 remains the open umbrella** (Spec, `depends-on: []`). R316 implemented
   one of its slices and closed independently; Stages 5 through 7 (the
   `JooqRecordInputType` collapse R234 forward-dates, namespace collapse, directive
   narrowing closing R97) are still outstanding, consistent with R234's premise
   being premature, not wrong-in-spirit.
6. **The `@record` / `@enum` retirement continues to bound a small staleness
   class.** `@record` survives as a *declared-but-ignored* directive; the one item
   building tooling on its *live-binding* behaviour, **R121**, stays mooted.
   **R360** proposes the same declared-but-rejected retirement for `@enum`;
   `@enum` is still live in code, so R360 is correctly Backlog and no item is yet
   stale against it. `classification-test-dsl-inventory.md` is doubly stale
   (R299/R290 + the R316 corpus recut) and still warrants the "superseded —
   historical" banner; it has **not** been added (left unedited here per scope).
7. **The `@reference` frontier matured into a join-translation surface, and
   joined-table inheritance opened as the new frontier.** R379 (terminal-hop +
   condition-param validation) and R380 (correlated-`EXISTS` join-subquery
   filters) both closed Done, filling in the join-translation surface the prior
   audit flagged as worth watching for clean re-anchoring; R380 filed R387 as an
   honest test-debt follow-up. Meanwhile a discriminated/joined-table inheritance
   cluster opened: R388/R392 (Done), R389 (Spec, first-class), R393 (Backlog,
   base→detail FK disambiguation). None is flagged; all are spec-forward on fresh
   code. Worth watching that R389/R393 re-anchor cleanly as the inheritance
   emission surface fills in.
8. **R45's rework cascaded onto a dependent.** R45 dropping the
   `ContextValueRegistration` design (Spec → Ready) sharpened the **R46** flag:
   R46's "Design" section now proposes extending a sealed type its own dependency
   has abandoned (see §B). This is the one place a current item's *re-spec*
   degraded a flagged item rather than improving it — the dependency edge held
   structurally (the slug resolves), but the design premise diverged.
9. **Cosmetic front-matter nits, none flag-worthy (carried over).** **R333**
   (`coordinate-lowers-to-datafetcher-queryparts`, Spec) **still has no `depends-on:`
   key**; add one for parseability on its next touch. **R97** lacks
   `created:`/`last-updated:` header fields (an older item predating that
   convention). Neither is a build or dependency risk.
10. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
    placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-06-29._
