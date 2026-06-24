# Roadmap staleness audit — 2026-06-24

A point-in-time review of every active roadmap item under
[`graphitron-rewrite/roadmap/`](../) against the **current** state of the
codebase on `claude/graphitron-rewrite` (HEAD `cfd5d629`, 2026-06-23 22:06). The
goal is to find items whose premise no longer holds: work already shipped,
constructs renamed or removed, dependencies that have since landed, or specs
grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-06-23` staleness audit, which has been deleted;
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
  (against R299/R290 as before, and now against the R316 corpus recut). It still
  warrants the "superseded — historical" banner prior audits recommended; it has
  **not** been added (left unedited here per scope, see observation 6).

## Changes since the 2026-06-23 audit

**30 commits** landed between the prior audit's review point (`7cbece9b`,
committed 2026-06-22 21:25) and this one (HEAD `cfd5d629`, 2026-06-23 22:06). The
prior audit file itself was committed at `1961df69`, the first commit after its
review point; the 30 commits counted here include that audit commit and the 29
that followed. As last window, this was a steady-throughput window, not a
structural pivot: no field-model or classification-substrate change on the scale
of R316. Three items closed cleanly to Done (one of them unblocking a fourth),
and two fresh cleanup items were filed.

**Terminal closures this window (all clean, all self-deleted their files):**

- **R341** `mcp-server-skeleton` → Done. Embedded the smallest-useful MCP server
  in `graphitron:dev`: a **new `graphitron-mcp` module** (`GraphitronMcpServer`)
  hosting the MCP Java SDK 2.0.0 Streamable-HTTP transport in embedded Jetty 12,
  loopback-only on `127.0.0.1:8488`, serving static handshake `instructions` plus
  a single argument-less `about` prompt. The dependency-quarantine module is the
  transport-and-lifecycle seam **R118** builds its live catalog/schema discovery
  on; closing R341 **unblocked R118** (the closing author removed the
  `depends-on: [mcp-server-skeleton]` edge — re-verified `depends-on: []` at
  `graphitron-mcp-server.md:7`, and the README rollup dropped the "blocked by"
  annotation). Module count 9 → 10.
- **R357** `case-insensitive-table-name-match` → Done. A `@service`
  record-composite carrier (R329 shape) whose `@table(name:)` children declared a
  case differing from the lowercase jOOQ catalog misclassified every child as
  `UnclassifiedField`; the one-line fix aligned `collectAccessorMatches`'
  element-table guard to `equalsIgnoreCase`. **Filed and closed within this
  window**; leaves no file.
- **R358** `table-name-comparison-case-guard` → Done. Generalised R357: moved the
  comparison onto the type as `TableRef.sameTable(String)` /
  `denotesSameTableAs(TableRef)` (case-insensitive, null-safe; verified at
  `model/TableRef.java:61`/`:66`), migrated ~10 comparison sites across
  `FieldBuilder` / `TypeBuilder` / `GraphitronSchemaValidator` / `BuildContext` /
  `NodeIdLeafResolver`, and added `TableNameComparisonCaseGuardTest` forbidding
  the raw `.tableName().equals(` spelling. **Filed and closed within this
  window**; leaves no file. This is the source of the small FieldBuilder line
  drift noted in §C.

**New items still on the board (both Backlog, both fresh):**

- **R359** `column-sqlname-comparison-case-guard` (Backlog, cleanup) — the
  column-identity sibling of R358, filed by R358 so the structurally identical
  `ColumnRef.sqlName()` defect (one live `.sqlName().equals(` at
  `GraphitronSchemaValidator.java:883` alongside six `equalsIgnoreCase` sites)
  does not fall into a blind spot. `depends-on: []` resolves; named symbols
  spot-checked present.
- **R360** `retire-enum-directive` (Backlog, cleanup) — retire `@enum`
  (`enumReference:`) the way `@record` was retired, inferring the enum's Java
  backing from producers (column `javaType`, `@service` signature, accessor
  return type). The model was decided in **R333**'s *Enum facts* this window; this
  item is the code retirement. `@enum` is **still live in code**
  (`generators/util/SchemaDirectiveRegistry.java`, `TypeBuilder.java`), so the
  Backlog status is correct — R360 is the not-yet-done removal, and it explicitly
  coordinates with the flagged **R261** (which owns the enum-name-divergence
  rejection at the column-binding site).

**R333 spec expansion (no code, no status change).** `R333`
(`coordinate-lowers-to-datafetcher-queryparts`, Spec) grew +390 lines of plan
body this window — a directive-coverage map and gap inventory, the enum facts
(seeding R360), dissolving the transform axis into node/column facts, and folding
routine handling into the `@reference` join path. This is spec authoring on an
Active item, not a landing; it does not move any flag.

A full `depends-on` sweep across all **122** item files found **no dangling
slugs** (re-verified by script: every bracketed slug resolves to a live file; the
`mcp-server-skeleton` edge is gone). **R30** remains the **only** stranded Done
tombstone (re-confirmed: a non-recursive `^status: Done` grep over `roadmap/*.md`
returns exactly that one file).

**Net effect on flag counts: 31 flagged, 91 current** (prior window: 31/90). The
**flag composition did not change this window**: no flagged item was resolved or
created. Board accounting: prior total 121 items, minus R341 (Done), plus R359
and R360 (filed), with R357/R358 filed-and-closed within the window (net zero),
gives **122** items today; flagged held at 31, so current rose to 91. The three
closures and both new items landed on **current/fresh** items, none of which were
under flag, so no §A/§B/§C row entered or left.

## Scope and method

All **122** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder). For each item the targets it names
(classes, directives, methods, packages, modules) were located in the current
tree under `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/`,
the described problem was checked for whether it still reproduces, and the
changelog was scanned for the item's `R<n>` and key terms to catch work that
shipped without the item being closed.

Every flagged item carried over from the prior audit was re-verified against
current HEAD. **All 31 still reproduce**; only line numbers drifted, and only in
`FieldBuilder.java` (+5 this window from R358's site migration) — see observation
3. The structural landings the prior audits relied on still hold: **R276**
(`ResultType` is a 4-arm seal at `GraphitronType.java:93-95`; `PojoResultType`
permits only `Backed` at `:119-120`), **R290** (`LeafTupleAdapter` /
`ConstructorField` dissolved), **R305** (`SingleRecordTableField` gone; live
carrier is `RecordTableField` at `model/ChildField.java:807`), and **R316** (the
`carrier × intent × mapping` field model gone, replaced by `(source, operation,
target)`; `model/Operation.java` is a sealed interface).

**Result: 31 items flagged, 91 current.** Line numbers cited below are as of the
review date and will themselves drift; see observation 3.

## A. Obsolete — should leave the active roadmap (3)

Each shipped or was superseded by a sibling already at Done. Because the closure
came from the sibling rather than a self-transition, no author ran the
file-deletion sweep. Composition unchanged from the prior audit.

| Item | Status | Action | Why |
|---|---|---|---|
| **R30** selection-parser-audit | **Done** | **Delete the file** | Status is `Done` (re-confirmed). Per workflow (`workflow.adoc:21,74`) Done items are deleted. Re-verified the **sole** stranded Done tombstone (`^status: Done` grep over `roadmap/*.md` returns exactly this one file). Nothing `depends-on` it (the only other files naming it, `changelog.md` and `source-orientation-javadocs.md:58,63`, are prose cross-references, not dependency edges); it carries no README rollup row, so it is not a build risk, purely an unswept tombstone. If the "parser is needed, keep it" finding is worth retaining, capture it in `changelog.md` first, then delete. **Clean delete, unambiguous.** (Stranded across eleven audits now.) |
| **R146** mutation-cardinality-safety-unique-index | Backlog | **Discard → delete** (via transition) | R246's changelog entry (`changelog.md:275`, Done): "**Absorbs R146 (PK-or-UK coverage, discarded)**." Re-verified in current code: `JooqCatalog.candidateKeys(String)` (`:593`) feeds `walker/MatchedKeys.java:59-60` (`MatchedKey.PrimaryKey`/`UniqueKey`) → PK-preferred coverage with a `NoUniqueKeyCoverage` rejection at `UpdateRowsWalker.java:125-128` (R342 deleted Stage 2b and re-staged the walker last window; unchanged this window), shipped and tested. Note R246 is a changelog-only tombstone, not a standalone `R<n>` file, but the "Absorbs R146" wording is present verbatim. The `status: Backlog` is stale; the correct mechanism is a `Backlog → Discarded` transition (which couples with file deletion per `workflow.adoc:23`), not a raw delete. |
| **R52** lift-operation-taxonomy | Backlog | **Discard → delete** (via transition) | Obsoleted by R316; re-confirmed. R52 asked to "lift the lookup-vs-query axis to a first-class sealed `Operation` carrier … once a cross-cutting consumer needs to ask 'is this a lookup?' without dispatching through variant-shape inspection." **R316 did exactly that:** `model/Operation.java` is a sealed interface whose `Lookup` arm sits beside `Fetch`, `Paginate`, and `ServiceCall`; its own javadoc attributes the lift to R316. The split is the sealed model handle R52 specified, not "encoded only by variant identity." Its re-spec trigger ("Re-spec when that changes") has fired, but the change satisfies the goal rather than mooting it. **Verify the `Operation.Lookup` arm fully covers R52's intent, then discard via `Backlog → Discarded`** (or, if a thin remainder survives, re-spec to that remainder only). |

## B. Outdated — needs re-spec (premise or targets materially changed) (16)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. All sixteen
carry over from the prior audit and were re-verified this window; **every one
still reproduces.** The only change this window is `FieldBuilder.java` line drift
(+5 net from R358's site migration; +3 below line 5082, +5 above), called out per
affected row.

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; still the sharpest.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm R276 deleted: `ResultType` is a 4-arm seal (`GraphitronType.java:93-95`), `PojoResultType` permits only `Backed` (`:119-120`). Its primary migration target `FetcherEmitter.propertyOrRecordValue` **no longer exists** (zero hits); only the `GeneratorUtils.buildFkRowKey` half of the duplication survives (`:241`, called `:212`; `backingClassOf` now `:367`). Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. (The `NoBacking` hits in `CatalogBuilder` are the LSP `TypeBackingShape.NoBacking`, a different type, not the deleted result-type arm.) |
| **R201** honor-field-directive-in-payload-construction-shape | Backlog | Premise live: `resolvePayloadConstructionShape` reached on the live `PayloadClass` arm (`FieldBuilder.java:488`, called **`:2273`**); the mutable-bean predicate's `javaBeanSetterName` at `:600`, setter-match arm `:571`, builds bindings off the raw SDL field name with **no `@field` read** (all four below FieldBuilder's drift point, unchanged). R200 shipped the input-side `@field(name:)` axis and explicitly hands "the remaining `@field`-symmetry items" to R201/R202, so R201 is the wanted **output-side mirror**, not a discard. Re-spec: drop the "R244 moots R201" framing (R244 only covered the migrated `Outcome<T>` paths), re-anchor the construction-shape lines (the body's internal cites are also stale), and frame it as the output payload-construction counterpart of R200. |
| **R13** faceted-search | Spec | Central seam `ConnectionSynthesis.buildPlan()` is long gone (zero hits for both names); the live entry point is `ConnectionPromoter.synthesiseForField` (`ConnectionPromoter.java:120`; `rebuildAssembledForConnections` at `:194`). `FieldWrapper.Connection` is a 2-arg record `(boolean connectionNullable, int defaultPageSize)` with no `connectionName` and no facet slot (`FieldWrapper.java:73-76`). Phases 2 through 4 are written entirely against the retired pipeline. |
| **R71** recordn-key-parity-lifter | Backlog | **Symbol re-anchor; substance confirmed still future.** Every symbol the mechanism names is dead: `BatchKeyLifterDirectiveResolver`, `BatchKey.LifterRowKeyed`/`RecordParentBatchKey`, and the `RowKeyed`/`MappedRowKeyed`/`RecordKeyed`/`MappedRecordKeyed` permits do not exist (R110/R222/R290/R305 reworked the surface; the names survive only in one comment and one string literal). The live surface is `model/LifterRef.java:25`, routed through `@sourceRow` (`SourceRowDirectiveResolver`) and `SourceKey.Reader.SourceRowsCall(LifterRef)` (`SourceKey.java:284`). R316 did **not** collapse `wrap`+`cardinality` out of `SourceKey` (`SourceKey.java:81-82` still carries `Wrap wrap` + `Cardinality cardinality`; `Wrap.{Row,Record,TableRecord}` at `:94`/`:96`/`:102`), and the compact constructor (`:124-127`) currently pins `SourceRowsCall → Wrap.Row`, the exact Row-only asymmetry R71 wants to remove. Re-anchor on `LifterRef`/`SourceKey.Reader.SourceRowsCall`/`Wrap`; the intent is unaddressed and valid. |
| **R46** service-multi-tenant-fanout | Backlog | R190 (Done) sealed `GraphitronContext` to its generated `Impl` (`GraphitronContextInterfaceGenerator.java` `CLASS_NAME`/`IMPL_CLASS_NAME` `:34-35`) plus a `newExecutionInput(...)` factory (`GraphitronFacadeGenerator.java:116`), dissolving the `ContextValueRegistration<FanOut>` design the whole "Design" section rests on (`ContextValueRegistration` has zero `.java` hits; the cited `getContextFanOut`/`openContextDslContext`/`getExecutor` generator methods also don't exist). Body still links the dead `typed-context-value-registry.md` slug at lines 17/156 (renamed to `tenant-routing-and-execution-input.md` = R45, Spec). |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | **Premise factually inverted against current code.** The body claims R222 *already* collapsed `JooqRecordInputType` by rejecting any non-`TableRecord` jOOQ `Record`, but `JooqRecordInputType` is a live, populated input arm (`GraphitronType.java:342`, permitted by `InputType` at `:303-304`), the quoted "…but not a TableRecord…" rejection is **absent**, and `JooqTableRecordInputType` exists as a *separate* sibling (`:357`). The `BackingClass` family R234 proposes extending was never built (no `BackingClass.java`). R316 did not touch input-side classification, so the collapse R234 forward-dates still belongs to R222 (Spec, Stages 5 through 7). Re-spec: the "collapse already happened, now reintroduce arms" narrative is inverted; the arm it wants to re-add still exists. Gate behind the R222 collapse shipping. |
| **R314** dissolve-reentry-leaves-dimensional-emit | Backlog | The body (lines 16-53) is written entirely in the retired `carrier × intent × mapping` vocabulary ("Carrier carries the source context", "Intent … the service-lift", "Mapping carries the output projection"), and R316 deleted all three types; an implementer would search for symbols that no longer exist. R316 *narrowed* R314's scope rather than mooting it: the `dispatchPerformsReFetch` mirror retirement and the transitional `Operation.ServiceCall(Call)` one-carrier collapse are both pinned to R314 (`Operation.java:75`/`:78`/`:81`, "Collapses to one carrier under R314"; both `requiresReFetch` in `model/OutputField.java:128` and `dispatchPerformsReFetch` in `GraphitronSchemaValidator.java:132`, called `:160`, still co-exist). Re-spec the prose onto `(source, operation, target)`; the work is sharper and `depends-on: [dimensional-model-pivot]` (R222) still resolves. |
| **R263** decode-helper-typename-first-resolution | Backlog | **Discard recommendation now firmer still.** The trap still exists (`resolveDecodeHelperForTable` at `BuildContext.java:2208`), but R317's index rewrite made it resolve **table-first** (`findGraphQLTypeForTable(sqlTableName)` at `:2215`, with `typeId` only a fallback suffix at `:2233-2235`), which is the inverse of the typeName-first resolution R263 proposes. It now has **four** callers, not two (two internal BuildContext callers at `:2041`/`:2176`, plus `FieldBuilder.java:1138` and `NodeIdLeafResolver.java:275`); **all** pass the table name as primary with `typeId` as fallback, and **none** route an authoritative `@nodeId(typeName:)` expecting it to drive the suffix. Three consumers (R195, R312, and the R317 index work) sidestepped it; no MUST-route caller has materialized. The keep-condition has not been met across the intervening windows. **Discard as speculative** (re-open only if such a caller appears). |
| **R269** nullable-to-one-record-into-npe | Spec | R268 shipped (helpers take a `sourceExpr`) and R271 (dunder retirement) shipped, renaming `__elt`→`element`/`__k`→`key`, so the spec's `__elt`/`__k` snippets (lines 22-23, 29, 35) are triply stale (helpers now `GeneratorUtils.buildAccessorKeySingle` `:318`, `buildAccessorKeyMany` `:338`), and the "if R268 lands first … if R269 lands first" coordination fork (lines 62-70) is resolved (R268 landed first). The success-arm NPE itself is **still unguarded** (`GeneratorUtils.java:332-334` emits `element = ((Backing) source).accessor()` then `element.into(...)` with no null check, while the sibling FK path guards at `:433-436`). Strip the snippets and the R268 framing; re-anchor on `element`/`key`. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (`R58TypedRejectionPipelineTest` in tree, R58 Done), so the "R58 is currently *In Review* … if it reverts" framing (lines 21-23) is false and should be removed. Carriers are still String-flattened (`ParsedPath(…, String errorMessage)` at **`BuildContext.java:974`**; `Unresolved` at `InputFieldResolution.java:20`; changelog defers these to R66 explicitly), so the carrier-audit body stays valid; `depends-on: []` is already correct. |
| **R261** wire-coercion-cast-guard | Backlog | The specific reported instance (`(SakRecord) raw.get(...)`, site A) is handled by shipped R195 (`CallSiteExtraction.NodeIdDecodeRecord`, defined `model/CallSiteExtraction.java:206`, permitted `:33`, consumed across ~7 files). Update the coordination framing (lines ~100-105) to past tense; the raw-cast invariant for sites B through E is still unbuilt (R195's rejection is jOOQ-record-narrow). **New this window:** R360 (`retire-enum-directive`, Backlog) explicitly hands R261 the enum-name-divergence rejection at the column-binding site — note that incoming coordination when re-specing. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)" (lines 3, 13); R94 shipped (Done, `changelog.md:354`). Blocker cleared, drop the blocked framing from title and body (lines 18-26). R94's Done note hands the real remaining dependency to **R98** (`multi-source-input-validation`, Backlog: "R170 picks up the live invalid-input round-trip the moment R98 ships its first SDL constraint"), so re-point the dependency there. |
| **R24** nodeidreferencefield-join-projection-form | Backlog | Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **no longer exists** (dispatch is now `FetcherEmitter.bind` `:179`/`bindRaw` `:359`; only stale javadoc in `model/ChildField.java:1029`/`:1056` still names it), and the carrier in the **item title**, `NodeIdReferenceField`, was renamed by R50 to `ColumnReferenceField`/`CompositeColumnReferenceField` (sibling `ParticipantColumnReferenceField` at `FetcherEmitter.java:490`). The surviving "not yet implemented" stub is for `CompositeColumnReferenceField` at `:509-517` (the `UnsupportedOperationException` "requires JOIN-with-projection emission" at `:514-516`); the `ColumnReferenceField` direct-compaction arm at `:503-508` is implemented. Work is valid and unbuilt, but title symbol + method names need a full re-anchor. |
| **R121** lsp-diagnostic-redundant-splitquery-on-record | Backlog | Mooted/reframed by the `@record` retirement (R301/R307, Done). The live build warning is `warnIfSplitQueryOnRecordParent` at **`FieldBuilder.java:4629-4636`** (message "@splitQuery is redundant on a record-backed parent field" at **`:4633`**; drifted +3 from the prior audit's `:4626`/`:4630` via R358's FieldBuilder migration), keying on the reflection-derived **record-backed** determination, not a `TypeContext.hasDirective(typeDef,"record")` SDL predicate. Call sites at `:4294`/`:4379`/`:4461`/`:4577`; sibling redundancy warnings (R275) now also exist (`:4291`, `:4455`, `:4572`; `MutationInputResolver.java:208`). Re-spec against the current `warnIfSplitQueryOnRecordParent` wording (and decide whether to fold in the siblings), or **discard** if the SDL-only LSP can't mirror the reflection-derived determination. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines" and "~30 private methods"; `TypeFetcherGenerator.java` is **6 170** (~3.75× the cited figure; unchanged this window) with **175** private method declarations. Lightest re-spec in this section: refresh the LOC/method-count figures and re-survey the cut against the real file, or stop hard-coding a count. |
| **R19** history-squash | Ready | Every SHA/count is an April-2026 snapshot: "595 (as of 2026-04-24)" (`:22`), a "566-commit history" (lines 16/124/151), the "~303 docs + 263 code" split (`:28`), the merge base `ab3daff2` (lines 23/287), the "16 new main commits" (lines 24/257), and a frozen list of `ac3df0b7`/`f7d2be49`/`76754b33`/… SHAs (`:26`) plus the expected trunk tip `8a8c5efe` (`:101`). The branch is now **~3 040 ahead** of `origin/main` (up from the prior audit's ~3 010; ~5.4× the documented 566). All numbers/SHAs/drop-lists need regeneration, or better, computing dynamically at execution time, before this can execute. |

## C. Outdated — update references only (work valid, refs stale) (12)

Substance intact; only paths, line numbers, or dependency tense drifted.
Composition unchanged from the prior audit. The recurring root cause is
unchanged: `TypeFetcherGenerator.java` (**6 170**, unchanged this window) /
`FieldBuilder.java` (**5 912**, +5 this window from R358) / `BuildContext.java`
(**2 446**, unchanged) standing at ~2.7 to 3.8× the sizes the older specs cite,
plus a `docs/*.md` → `docs/*.adoc` migration.

| Item | Stale reference |
|---|---|
| **R308** service-list-payload-arrival | Substance fully intact: the `@service` list-payload N+1 (carrier arrives as a list, the inline-no-loader fetcher fires once per element) is real and unbuilt. But its body still describes the no-DataLoader shape as "`SingleRecordTableField` today, the single-arrival arm of the merged `RecordTableField` after R305", and `SingleRecordTableField` was **deleted** by R305 (now only survives in javadoc/comment prose; live carrier is `RecordTableField` at `model/ChildField.java:807`). Strip the "today" framing and read it as the single-arrival arm of `RecordTableField`; bump `last-updated`. (`depends-on` already `[]`.) |
| **R202** honor-field-directive-in-error-type-source-accessors | Premise live and unbuilt: `checkErrorTypeSourceAccessors` (**`FieldBuilder.java:2474`**, called **`:2252`**, both below the FieldBuilder drift point and unchanged) calls `ClassAccessorResolver.resolve(sourceClass, sdlField.getName(), …)` (`:2493-2498`, raw SDL name at `:2495`) with **no `@field` read**, exactly the gap R202 describes. R200's Done note names R202 as a remaining `@field`-symmetry sibling. Only staleness is the body's internal cites; re-anchor at the symbol. |
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck as "Obsolete (R287)"; body re-based onto R305's re-fetch substrate; `depends-on` `[]`. One numeric anchor stale: `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` cited at "`:944`" (3 sites) is now a single def at **`:631`** (called `:373`; FetcherEmitter unchanged this window). Re-anchor at the symbol and drop the stale "3 sites" count. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` substance holds (emit-half still unbuilt). Anchors: argument-level `@condition` rejection (`argCondition`) at `:415` (used `:417-423`, `:439`); the `@condition(override:true)` admission gate (`ARG_OVERRIDE`) at `:496-498`. Body cites ~438-440 / :482-498; re-anchor at the symbols. |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`. The two cited sibling docs (`docs-as-index-into-tests.md`, `rewrite-docs-entrypoint.md`) do not exist under any extension (repo-wide `find` returns nothing). Relocate or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (absent repo-wide) and stale `ConnectionSynthesis` naming. The live seam is now `ConnectionPromoter.synthesiseForField` / `.rebuildAssembledForConnections` (`ConnectionPromoter.java:120`/`:194`); re-anchor there. |
| **R99** lsp-submodule-sibling-classpath | Path `graphitron-rewrite/graphitron-maven/…` (body `:18`) is actually `graphitron-maven-plugin/…`; the cited `getAllProjects()` is now reached via `reactorProjects()` at `AbstractRewriteMojo.java:208-209`. |
| **R92** catalog-check-constraint-validation | Seam anchors drifted (TypeFetcherGenerator grew): `validatorPreStep` cited `:1326` is now defined **`:1641`** (called `:1534`); `DefaultValidatorHolder` is at `GraphitronContextInterfaceGenerator.java:84`, `getValidator` at `:95-99` (these two still accurate). Re-anchor the two TypeFetcherGenerator lines. |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue` emission cited at `:1456`/`:1496`/`:1508`/`:1769` is now clustered in `buildPerCellValueList` at **`TypeFetcherGenerator.java:2010`** (emitting `:2039`/`:2046`/`:2060`/`:2076`/`:2092`; deduped-helper sites at `:2209`/`:2214`; coalesce site at `:2376`). |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog.reflectTableMethod` has two overloads (dispatcher `:484`, slot-types-aware `:498`); the strict `ClassName.equals` return-type gate is at `:527-529` (cited `:524-525`); `buildQueryTableMethodFetcher` is at **`TypeFetcherGenerator.java:1128`** (called `:437`). Re-anchor at the symbols. |
| **R236** validator-reference-candidate-hint-terminal-table | (Anchors are in the **item body**, lines 17/27.) `classifyInputFieldInternal` is at **`BuildContext.java:1831`** (body cites `:1665-1677`), and the candidate-hint failure-aggregation (`columnSqlNamesOf(...)`) at **`:1920`** (BuildContext unchanged this window). Re-anchor at the symbol. |
| **R35** source-orientation-javadocs | LOC counts grossly stale (FieldBuilder cited "2 172", now **5 912**; TypeFetcherGenerator "1 646", now **6 170**); doc cross-links use `.md` and should be `.adoc` (`code-generation-triggers.md`, `selection-parser-audit.md`, `decompose-typefetchergenerator.md`, `changelog.md`). The "FieldBuilder decomposition shipped under R6" claim stays dubious given the file is now ~2.7× larger. |

**Re-confirmed Current (not flagged):** **R118** graphitron-mcp-server
(Backlog) was **unblocked this window** by R341 going Done; `depends-on: []` now
resolves, the README "blocked by" annotation is gone, and its spec-forward
catalog/RAG symbols are correctly not-yet-built. **R267**
nodeid-encoder-deprecated-convert (the deprecated
`col.getDataType().convert(values[i])` still emitted at
`NodeIdEncoderClassGenerator.java:266`, class-wide `@SuppressWarnings`).
**R219** unify-inference-rule-by-javatypekey and **R220**
consolidate-sources-shape-predicates both target `ServiceCatalog`
parameter-binding inference (`inferBindingsByType`, `looksLikeSourcesShape`,
`classifySourcesType`, `couldBeSourcesShape`), untouched this window; valid,
unbuilt, not stale. **R222** dimensional-model-pivot stays **Spec** as the
umbrella (`depends-on: []`, Stages 5 through 7 outstanding). **R333**
coordinate-lowers-to-datafetcher-queryparts (Spec) grew its plan body this
window (directive-coverage map, gap inventory, enum facts seeding R360) — spec
authoring, not a landing; symbols spec-forward and not-yet-resolved as expected.
The new items **R359** (Backlog, `depends-on: []`; `GraphitronSchemaValidator.java:883`
and the six `equalsIgnoreCase` sites present) and **R360** (Backlog, `depends-on:
[]`; `@enum` still live in `SchemaDirectiveRegistry`/`TypeBuilder`, so the
retirement is genuinely unbuilt) are fresh.

## Cross-cutting observations

1. **Closure hygiene stayed clean; the dependency graph held.** Every item that
   left the board this window self-closed cleanly (R341 Done + file deleted;
   R357/R358 filed-and-closed within the window), and — notably — the R341
   closer **removed the `depends-on` edge from R118**, the dependent it
   unblocked. A full `depends-on` sweep across all 122 item files found **no
   dangling slugs**. **R30** (self-Done, unswept) remains the **only** stranded
   Done tombstone, alongside R146/R52's discard-toward-sibling Backlog files. The
   workflow rule that *the closing author deletes the file* still has no owner
   when the closure comes from a sibling (R146, R52). Worth a one-shot cleanup
   pass and a workflow note.
2. **This was a throughput window, not a pivot.** Unlike the R316 landing two
   windows ago, no structural axis moved: the flag composition held at exactly 31
   (3 / 16 / 12), no flagged item resolved or was created, and the three closures
   plus both new items landed on current/fresh items. The board grew from 121 to
   122 by ordinary accounting (−R341 +R359 +R360, R357/R358 net-zero), not by any
   spec going stale or fresh.
3. **Line-drift recurs from ordinary refactors — but was unusually small this
   window.** Only `FieldBuilder` moved (**5 912**, +5, from R358's ~10-site
   `tableName()` → `sameTable()` migration); `TypeFetcherGenerator` (**6 170**),
   `BuildContext` (**2 446**), and `FetcherEmitter` (**763**) were untouched. The
   sole §B/§C row that drifted is **R121** (+3 to `:4629-4636`); every other
   anchor held. This is the sharpest evidence yet that specs anchored to **symbol
   names** rather than line numbers would stop the recurrence, and it remains the
   structural backdrop behind all of §C.
4. **The next structural pivot to watch is still the `SourceKey` decomposition.**
   R316's changelog named it the "first concrete consumer once this pivot lands";
   it will collapse `wrap`+`cardinality` out of `SourceKey` (`SourceKey.java:81-82`,
   `:94-102` today, unchanged this window). When it ships, **R71** (re-anchors on
   `SourceKey.Wrap`, which it removes), **R314** (the emit re-platforming R316
   pinned to it), and **R234**'s input-carrier collapse (depends on R222's
   remaining stages) will all need re-checking. This remains the standing watch.
5. **R222 remains the open umbrella** (Spec, `depends-on: []`). R316 implemented
   one of its slices and closed independently; Stages 5 through 7 (the
   `JooqRecordInputType` collapse R234 forward-dates, namespace collapse,
   directive narrowing) are still outstanding, consistent with R234's premise
   being premature, not wrong-in-spirit.
6. **The `@record` retirement (R301/R307) continues to bound a small staleness
   class — and R360 opens a parallel one for `@enum`.** `@record` survives as a
   *declared-but-ignored* directive; the one item building tooling on its
   *live-binding* behaviour, **R121**, stays mooted. R360 this window proposes the
   same declared-but-rejected retirement for `@enum` (model decided in R333's
   *Enum facts*); `@enum` is still live in code, so R360 is correctly Backlog and
   no item is yet stale against it. `classification-test-dsl-inventory.md` is
   doubly stale (R299/R290 + the R316 corpus recut) and still warrants the
   "superseded — historical" banner; it has **not** been added (left unedited here
   per scope).
7. **Cosmetic front-matter nits, none flag-worthy.** **R333**
   (`coordinate-lowers-to-datafetcher-queryparts`, Spec) still has **no
   `depends-on:` key** despite a heavily-expanded body this window; add one for
   parseability on its next touch. **R97** lacks `created:`/`last-updated:` header
   fields (an older item predating that convention). Neither is a build or
   dependency risk.
8. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-06-24._
