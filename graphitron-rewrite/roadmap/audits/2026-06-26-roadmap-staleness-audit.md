# Roadmap staleness audit — 2026-06-26

A point-in-time review of every active roadmap item under
[`graphitron-rewrite/roadmap/`](../) against the **current** state of the
codebase on `claude/graphitron-rewrite` (HEAD `b90d0a21`, 2026-06-25 19:14). The
goal is to find items whose premise no longer holds: work already shipped,
constructs renamed or removed, dependencies that have since landed, or specs
grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-06-25` staleness audit, which has been deleted;
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

## Changes since the 2026-06-25 audit

**75 commits** landed between the prior audit's review point (`eb6c2db3`,
committed 2026-06-24 21:18) and this one (HEAD `b90d0a21`, 2026-06-25 19:14). The
prior audit file itself was committed at `204c796d`, the first substantive commit
after its review point. This was the **largest feature-landing window** the audit
series has seen, and it had a single dominant axis: **the MCP programme (R118)
went from a two-slice skeleton to a standalone `graphitron-mcp` module** carrying
catalog tools, structured read-tools, cross-reference edges, and a full semantic
(RAG) foundation. A second front opened on `@reference` (three items filed), LSP
navigation closed several items, and `R333` absorbed a heavy documentation rework.
Still **no structural axis pivot** on the scale of R316: the field model is
unchanged, and (critically for this audit) **no flagged item was resolved,
reclassified, or created** this window.

**Terminal closures this window (all clean, all self-deleted their files):**

- **R148** `source-location-skips-description` → Done. Re-anchored the LSP
  validator diagnostics off the doc block onto the definition name, so squiggles
  point at the field not its description. **Existed at the prior review point as
  Backlog; transitioned In Progress → Done and deleted this window.**
- **R362** `mcp-catalog-tools` → Done. R118 slice 2: `catalog.tables` /
  `catalog.describe` over a build-time `CatalogFacts` projection (new
  `catalog/CatalogFacts.java`, +224 lines). **Existed at the prior review point as
  Ready; closed and deleted this window.**
- **R363** `multitable-interface-query-filter-lowering` → Done. Lowered `@field`
  filter inputs and `@condition` onto multitable-interface queries, scoped to
  branch-safe extractions; shares `MultiTablePolymorphicEmitter` (now **1 824**
  lines) with R365/R366/R367. The source of most of the FieldBuilder line drift in
  §B/§C this window. **Existed at the prior review point as Spec; closed and
  deleted this window.** Filed **R382** and **R383** for deferred remainders.
- **R365** `polymorphic-entity-service-return` → Done. Route (a): return a
  polymorphic entity from a `@service` mutation, resolving the participant by the
  returned record's runtime type, narrowed to the multitable-interface case
  (union / same-table rejected). **Existed at the prior review point as Ready;
  closed and deleted this window.**
- **R368** `mcp-workspace-read-tools` → Done. R118 slices 3-6: MCP structured
  read-tools over the live `Workspace` (adopted option B, widening
  `DirectiveShape`). New `SchemaView` (+446), `McpWire`, `CodeTools`,
  `DirectivesResource`, `DiagnosticsTool`. **Existed at the prior review point as
  Spec; closed and deleted this window.**
- **R371** `declaration-name-hover-reads-source-index` → Done. Declaration-name
  hover overlays jOOQ class/column Javadoc by reading the source index, parity with
  R353's goto-definition. **Existed at the prior review point as In Progress;
  closed and deleted this window.**

**Filed and closed within the window (net zero; leave no file, recorded in
`changelog.md`):**

- **R372** `mcp-rag-foundation` → Done. R118 slice 8: the semantic layer
  (`Embedder` / `EmbeddingStore` / `WarmState` seams, `BgeEmbedder` + Lucene store,
  `AsyncWarm`) under `graphitron-mcp/.../rag/`, with the heavy ONNX/Lucene
  dependencies quarantined to `graphitron-mcp` (R341). Blocks slices 9/10/11.
- **R374** `mcp-cross-reference-edges` → Done. R118 slice 7: an `edges` tool and a
  sealed `NodeRef` model with a lazy `ReverseEdgeIndex` for impact analysis, the
  arm-to-kind mapping pinned by an exhaustive no-`default` switch over every
  classifier permit (cross-module drift guard).
- **R375** `empty-list-in-filter` → Done. Empty list to a fetch list-IN filter now
  lowers to `noCondition` instead of `IN ()` (which rendered `false` and zeroed the
  query).
- **R376** `goto-definition-service-backed` → Done. LSP goto-definition on
  service-backed / computed field names, with arity-primary resolution and a
  name-level fallback for overloaded service names.
- **R377** `decode-helper-nodeindex-multi-table` → Done. Resolve the
  `decode<typeId>` helper via the `@node` `NodeIndex` when multiple `@table` types
  share a table. **Rewrote `BuildContext.resolveDecodeHelperForTable` — see R263 in
  §A, whose cited internals this closure invalidated.**

**New items still on the board (seven, all filed this window):**

- **R373** `surefire-redirect-test-output` (Backlog, testing) — redirect
  Surefire/Failsafe output to per-class files. `depends-on: []`. (Renumbered from a
  transient R372 when trunk allocated R372 to the RAG item.)
- **R378** `nodeid-filter-malformed-vs-mismatched` (Backlog, architecture) — a
  filter `@nodeId` decode should distinguish a malformed ID from a wrong-type ID.
- **R379** `reference-terminal-hop-target-validation` (Spec, bug) — validate that a
  `@reference` terminal hop resolves to the field return type's table.
- **R380** `reference-join-filter-conditions` (Spec, feature) — implement
  `@reference` join-subquery filter conditions on input fields.
- **R381** `lsp-reference-path-authoring` (Spec, architecture) — LSP-guided
  `@reference` path authoring; `depends-on: [reference-terminal-hop-target-validation]`
  (R379), correctly resolves.
- **R382** `multitable-interface-query-orderby-lowering` (Backlog, bug) — filed by
  R363: lower `@orderBy` onto multitable-interface queries (scoped out of R363).
- **R383** `multitable-interface-filter-extraction-kinds` (Backlog, bug) — filed by
  R363: widen the filter-extraction kinds beyond the branch-safe subset R363 shipped.

**The MCP programme (R118) is now a shipped module, not a frontier sketch.** At the
prior review point R118 was a programme umbrella with R361 (Done) the only landed
slice, R362 (Ready) and R368 (Spec) pending. This window **R362, R368, R372, and
R374 all closed**, standing up `graphitron-mcp` as a real module (~3 000 lines of
new main + test code) with build-time catalog facts, live-Workspace read-tools, a
cross-reference edge graph, and a semantic-search foundation. **R118 remains
correctly Backlog as the programme umbrella;** slices 9-11 (the `docs.search` /
`catalog.search` semantic tools R372 unblocks) are the live remainder. None of this
is flagged: every slice is spec-forward and on fresh code.

**R333 absorbed a documentation rework, not a code pivot.** `R333`
(`coordinate-lowers-to-datafetcher-queryparts`, retitled **"The Graphitron data
model"**) took ~10 commits this window, all of them documentation/diagram work:
adopting the GraphQL `SchemaCoordinate` as the model's natural key, reworking the ER
diagram onto the shipped two-layer architecture, marking sealed-union entities, and
folding in derived reads, provenance, error mapping, and discrimination as facts. It
did **not** move status (still Spec) and did **not** touch the `SourceKey` `Wrap`
contract (re-confirmed for the R71 / observation 4 watch). The prior audit's
`@sourceRow`-into-join-path note was the *previous* window's code landing (d0954ad6);
this window R333 was purely the data-model doc.

A full `depends-on` sweep across all **127** item files (re-run programmatically
this window) found **no dangling slugs**. **R30** remains the **only** stranded Done
tombstone (re-confirmed: a non-recursive `^status: Done` grep over `roadmap/*.md`
returns exactly `selection-parser-audit.md`).

**Net effect on flag counts: 31 flagged, 96 current** (prior window: 31/95). The
**flag composition did not change this window**: no flagged item was resolved,
reclassified, or created. Board accounting: prior total 126 items, minus six Done
closures whose files were deleted (R148, R362, R363, R365, R368, R371), plus seven
filed this window (R373, R378, R379, R380, R381, R382, R383), with R372/R374/R375/
R376/R377 filed-and-closed within the window (net zero), gives **127** items today;
flagged held at 31, so current rose to 96. All eleven closures and all seven new
items landed on **current/fresh** items, none under flag, so no §A/§B/§C row entered
or left.

## Scope and method

All **127** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder). For each item the targets it names
(classes, directives, methods, packages, modules) were located in the current
tree under `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/`,
the described problem was checked for whether it still reproduces, and the
changelog was scanned for the item's `R<n>` and key terms to catch work that
shipped without the item being closed.

Every flagged item carried over from the prior audit was re-verified against
current HEAD. **All 31 still reproduce.** This window drifted line anchors more
than the last for the FieldBuilder-anchored rows, because R363's multitable
`@field` filter lowering grew `FieldBuilder.java` (**6 137**, +200 from
5 937) and R377's decode-helper rewrite moved `BuildContext.java` (**2 465**, +19)
internals. `TypeFetcherGenerator.java` (**6 175**, +18) and
`GraphitronSchemaValidator.java` (**1 322**, −3) drifted modestly;
`MutationInputResolver.java` (**679**) held exactly. The structural landings the
prior audits relied on all still hold: **R276** (`ResultType` is a 4-arm seal at
`GraphitronType.java:93-94`; `PojoResultType` permits only `Backed` at `:119-120`),
**R290** (`LeafTupleAdapter` / `ConstructorField` dissolved), **R305**
(`SingleRecordTableField` gone; live carrier is `RecordTableField`), and **R316**
(the `carrier × intent × mapping` field model gone, replaced by `(source, operation,
target)`; `model/Operation.java` is a sealed interface).

**Result: 31 items flagged, 96 current.** Line numbers cited below are as of the
review date and will themselves drift; see observation 3.

## A. Obsolete — should leave the active roadmap (3)

Each shipped or was superseded by a sibling already at Done. Because the closure
came from the sibling rather than a self-transition, no author ran the
file-deletion sweep. Composition unchanged from the prior audit.

| Item | Status | Action | Why |
|---|---|---|---|
| **R30** selection-parser-audit | **Done** | **Delete the file** | Status is `Done` (re-confirmed `selection-parser-audit.md:4`). Per workflow (`workflow.adoc:21,74`) Done items are deleted. Re-verified the **sole** stranded Done tombstone (`^status: Done` grep over `roadmap/*.md` returns exactly this one file). Nothing `depends-on` it (the programmatic slug sweep across all 127 files confirms zero inbound edges; the only other files naming it, `changelog.md` and `source-orientation-javadocs.md`, are prose cross-references); it carries no README rollup row, so it is not a build risk, purely an unswept tombstone. If the "parser is needed, keep it" finding is worth retaining, capture it in `changelog.md` first, then delete. **Clean delete, unambiguous.** (Stranded across thirteen audits now.) |
| **R146** mutation-cardinality-safety-unique-index | Backlog | **Discard → delete** (via transition) | R246's changelog entry (Done): "**Absorbs R146 (PK-or-UK coverage, discarded)**" (re-verified verbatim, `changelog.md:289`; R144's entry also files R146 as the follow-up). Re-confirmed in current code: `JooqCatalog.candidateKeys(String)` (`:606`, table overload `:617`) feeds the PK-preferred subset match in `walker/MatchedKeys.java` (`MatchedKey.PrimaryKey`/`UniqueKey`), with a `NoUniqueKeyCoverage` rejection now living on `UpdateRowsError.java:51` (and a sibling `DeleteRowsError.NoUniqueKeyCoverage` at `:54` — additional confirmation the coverage shipped), reached from `UpdateRowsWalker`. R246 is a changelog-only tombstone, not a standalone `R<n>` file, but the "Absorbs R146" wording is present. The `status: Backlog` is stale; the correct mechanism is a `Backlog → Discarded` transition (which couples with file deletion per `workflow.adoc:23`), not a raw delete. |
| **R52** lift-operation-taxonomy | Backlog | **Discard → delete** if carrier-existence satisfies the team; **else re-spec to the dispatch-migration remainder** | Obsoleted *as a carrier-build item* by R316: `model/Operation.java` is a sealed interface (`:30`) whose `Lookup` arm (`:59`) sits beside `Fetch` (`:41`), `Paginate` (`:54`), and `ServiceCall` (`:75`). The split R52 specified ("lift the lookup-vs-query axis to a first-class sealed `Operation` carrier") **is now in the model**. **Refinement this window:** the carrier exists, but dispatch still switches on **variant/leaf identity**, not the `Operation` axis (e.g. `slf.lookupMapping()` / `rltf.lookupMapping()` at `TypeFetcherGenerator.java:434`/`:524`, and `LookupMapping.ColumnMapping` instanceof checks in the lookup emitters); no cross-cutting consumer yet asks "is this a lookup?" by reading `Operation`. R52's re-spec trigger ("once a cross-cutting consumer needs to ask … without dispatching through variant-shape inspection") has therefore **not** fired. The honest disposition: discard is defensible (the structural handle R52 named was built), but a **thin remainder survives** (migrate the emit/dispatch decisions onto `Operation`), so re-spec to that remainder rather than a clean discard, unless the team judges carrier-existence sufficient. |

## B. Outdated — needs re-spec (premise or targets materially changed) (16)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. All sixteen
carry over from the prior audit and were re-verified this window; **every one
still reproduces.** Line-anchor drift this window is called out per affected row;
the FieldBuilder-anchored rows (R201, R121) and the R377-rewritten row (R263)
drifted the most.

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; still the sharpest.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm R276 deleted: `ResultType` is a 4-arm seal (`GraphitronType.java:93-94`), `PojoResultType` permits only `Backed` (`:119-120`). Its primary migration target `FetcherEmitter.propertyOrRecordValue` **no longer exists** (zero hits); only the `GeneratorUtils.buildFkRowKey` half of the duplication survives (`:241`, called `:212`; `backingClassOf` now `:367`; the shared `recordColumnReadArgs` dispatcher at `:257`). All anchors held verbatim this window. Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. (The `NoBacking` hits in `CatalogBuilder` are the LSP `TypeBackingShape.NoBacking`, a different type, not the deleted result-type arm.) |
| **R201** honor-field-directive-in-payload-construction-shape | Backlog | Premise live: `resolvePayloadConstructionShape` on the live `PayloadClass` arm (`FieldBuilder.java:490`, called `:2406`); the mutable-bean predicate's `javaBeanSetterName` at `:602`, setter-match arm `:572-594`, builds `SetterBinding`s off the raw SDL field name with **no `@field` read** (the all-fields-ctor arm at `:498-518` picks by positional `getParameterCount()` alignment). **Drifted this window** (+200 from R363's FieldBuilder growth): the construction-shape def moved `:488`→`:490`, the call site `:2273`→`:2406`; the emitter halves themselves moved out to `TypeFetcherGenerator` (`payloadFactoryLambda` `:6076`, `payloadFactoryLambdaSetters` `:6093`). R200 shipped the input-side `@field(name:)` axis and explicitly hands "the remaining `@field`-symmetry items" to R201/R202, so R201 is the wanted **output-side mirror**, not a discard. Re-spec: drop the "R244 moots R201" framing (R244 only covered the migrated `Outcome<T>` paths), re-anchor the (now-drifted) construction-shape lines, and frame it as the output payload-construction counterpart of R200. |
| **R13** faceted-search | Spec | Central seam `ConnectionSynthesis.buildPlan()` is long gone (zero hits for both names); the live entry point is `ConnectionPromoter.synthesiseForField` (`ConnectionPromoter.java:120`; `rebuildAssembledForConnections` at `:194`). `FieldWrapper.Connection` is a 2-arg record `(boolean connectionNullable, int defaultPageSize)` with no `connectionName` and no facet slot (`FieldWrapper.java:73-75`). Anchors held this window. Phases 2 through 4 are written entirely against the retired pipeline. |
| **R71** recordn-key-parity-lifter | Backlog | **Symbol re-anchor; substance confirmed still future, and the standing structural watch.** Every symbol the mechanism names is dead: `BatchKeyLifterDirectiveResolver`, `BatchKey.LifterRowKeyed`/`RecordParentBatchKey`, and the `RowKeyed`/`MappedRowKeyed`/`RecordKeyed`/`MappedRecordKeyed` permits do not exist (R110/R222/R290/R305 reworked the surface). The live surface is `model/LifterRef.java:25`, routed through `@sourceRow` (`SourceRowDirectiveResolver`) and `SourceKey.Reader.SourceRowsCall(LifterRef)`. R316 did **not** collapse `wrap`+`cardinality` out of `SourceKey` (`SourceKey.java:81-82` still carries `Wrap wrap` + `Cardinality cardinality`; `Wrap.{Row,Record,TableRecord}` at `:94`/`:96`/`:102`), and the compact constructor (`:124-127`) still pins `SourceRowsCall → Wrap.Row`, the exact Row-only asymmetry R71 wants to remove. **Re-confirmed this window:** R333's documentation rework did not touch the `SourceKey` `Wrap` contract; all cited anchors held verbatim. Re-anchor on `LifterRef`/`SourceRowsCall`/`Wrap`. |
| **R46** service-multi-tenant-fanout | Backlog | R190 (Done) sealed `GraphitronContext` to its generated `Impl` plus a `newExecutionInput(...)` factory, dissolving the `ContextValueRegistration<FanOut>` design the whole "Design" section rests on (`ContextValueRegistration` has zero `.java` hits; the cited `getContextFanOut`/`openContextDslContext`/`getExecutor` generator methods also don't exist). The `depends-on:` key now correctly uses the post-rename slug `tenant-routing-and-execution-input` (= R45, Spec), but the body prose still links the dead `typed-context-value-registry.md` slug at lines 17/156. |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | **Premise factually inverted against current code.** The body claims R222 *already* collapsed `JooqRecordInputType` by rejecting any non-`TableRecord` jOOQ `Record`, but `JooqRecordInputType` is a live, populated input arm (`GraphitronType.java:342`, permitted by `InputType` at `:303-304`), the quoted "…but not a TableRecord…" rejection is **absent**, and `JooqTableRecordInputType` exists as a *separate* sibling (`:357`). The `BackingClass` family R234 proposes extending was never built (no `BackingClass.java`; the only `parentBackingClass` fields live on `LifterRef`/`AccessorRef`). R316 did not touch input-side classification, so the collapse R234 forward-dates still belongs to R222 (Spec, Stages 5 through 7). Re-spec: the "collapse already happened, now reintroduce arms" narrative is inverted; the arm it wants to re-add still exists. Gate behind the R222 collapse shipping. |
| **R314** dissolve-reentry-leaves-dimensional-emit | Backlog | The body (lines 16-53) is written entirely in the retired `carrier × intent × mapping` vocabulary, and R316 deleted all three types; an implementer would search for symbols that no longer exist. R316 *narrowed* R314's scope rather than mooting it: the `dispatchPerformsReFetch` mirror retirement and the transitional `Operation.ServiceCall(Call)` one-carrier collapse are both pinned to R314 (`Operation.java:75`/`:78` "Collapses to one carrier under R314"; both `requiresReFetch` in `model/OutputField.java:128` and `dispatchPerformsReFetch` in `GraphitronSchemaValidator.java:132`, called `:160`, still co-exist). Only the in-file comment moved (`:81`→`:78`) this window. Re-spec the prose onto `(source, operation, target)`; the work is sharper and `depends-on: [dimensional-model-pivot]` (R222) still resolves. |
| **R263** decode-helper-typename-first-resolution | Backlog | **Re-anchor mandatory: R377 rewrote the cited method this window; discard recommendation now firmer.** The trap still exists (`resolveDecodeHelperForTable`, def moved `:2208`→`:2226`), but **R377 replaced its body**: it no longer calls `findGraphQLTypeForTable(sqlTableName)` and there is no longer a "typeId-only fallback at `:2233-2235`." It now resolves through the `@node` `NodeIndex` (`nodes.forTable(sqlTableName)` at `:2235`), returns the single node's `decodeMethod()` (`:2237`), `null` on multi-node ambiguity (`:2243`), and falls back to the `typeId` suffix only when no `@node` backs the table (`:2249-2254`); the signature changed to `(sqlTableName, fallbackTypeId, keyColumns)`. The four callers persist (`:2041`/`:2176` internal, `FieldBuilder.java:1271` (was `:1138`), `NodeIdLeafResolver.java:275`); **all** still pass the table name as primary, **none** route an authoritative `@nodeId(typeName:)` to drive the suffix. R377 even added a javadoc note (`~:2317`) stating the typeName-vs-table trap "does not apply here." The resolution is now node-table-first (still not typeName-first), so R263's premise survives in spirit but its cited internals describe pre-R377 code. **Discard as speculative** (re-open only if a MUST-route `@nodeId(typeName:)` caller appears); re-anchor onto the R377 body before recording the discard. |
| **R269** nullable-to-one-record-into-npe | Spec | R268 shipped (helpers take a `sourceExpr`) and R271 (dunder retirement) shipped, renaming `__elt`→`element`/`__k`→`key`, so the spec's `__elt`/`__k` snippets (lines 22-23, 29, 35) are triply stale (helpers now `GeneratorUtils.buildAccessorKeySingle` `:318`, `buildAccessorKeyMany` `:338`), and the "if R268 lands first … if R269 lands first" coordination fork (lines 62-70) is resolved (R268 landed first). The success-arm NPE itself is **still unguarded** (`GeneratorUtils.java:332-334` emits `element = (($T) sourceExpr).accessor()` then `element.into(...)` with no null check, while the sibling source-bound FK path `buildKeyExtractionWithNullCheck` guards at `:393`/`:407`). Strip the snippets and the R268 framing; re-anchor on `element`/`key`. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (`R58TypedRejectionPipelineTest` in tree, R58 Done), so the "R58 is currently *In Review* … if it reverts" framing (lines 21-23) is false and should be removed. Carriers are still String-flattened (`ParsedPath(…, String errorMessage)` at `BuildContext.java:974`; `Unresolved` at `InputFieldResolution.java:20`; changelog defers these to R66 explicitly), so the carrier-audit body stays valid; `depends-on: []` is already correct. Anchors held this window. |
| **R261** wire-coercion-cast-guard | Backlog | The specific reported instance (`(SakRecord) raw.get(...)`, site A) is handled by shipped R195 (`CallSiteExtraction.NodeIdDecodeRecord`, defined `model/CallSiteExtraction.java:206`, consumed across ~7 files). Update the coordination framing (lines ~100-105) to past tense; the raw-cast invariant for sites B through E is still unbuilt (R195's rejection is jOOQ-record-narrow). **Carried over:** R360 (`retire-enum-directive`, Backlog) explicitly hands R261 the enum-name-divergence rejection at the column-binding site — re-confirmed `retire-enum-directive.md:29` names R261 for that coordination; note the incoming edge when re-specing. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)" (lines 3, 13); R94 shipped (Done, `changelog.md:368`). Blocker cleared, drop the blocked framing from title and body (lines 18-26). R94's Done note hands the real remaining dependency to **R98** (`multi-source-input-validation`, Backlog: re-verified the changelog entry "Unblocks R98 … and R170"), so re-point the dependency there (the title "(R94-blocked)" should become "(R98-blocked)"). |
| **R24** nodeidreferencefield-join-projection-form | Backlog | Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **no longer exists** (dispatch is now `FetcherEmitter.bind` `:179`/`bindRaw` `:359`), and the carrier in the **item title**, `NodeIdReferenceField`, was renamed by R50 to `ColumnReferenceField`/`CompositeColumnReferenceField`. The surviving "not yet implemented" stub is for `CompositeColumnReferenceField` at `FetcherEmitter.java:509-517` (the `UnsupportedOperationException` "requires JOIN-with-projection emission" at `:513-516`); the `ColumnReferenceField` direct-compaction arm at `:503-508` is implemented. **The R363/R365 multitable-polymorphic work this window grew `MultiTablePolymorphicEmitter` but did not touch this stub.** Work is valid and unbuilt, but title symbol + method names need a full re-anchor. |
| **R121** lsp-diagnostic-redundant-splitquery-on-record | Backlog | Mooted/reframed by the `@record` retirement (R301/R307, Done). The live build warning is `warnIfSplitQueryOnRecordParent` at **`FieldBuilder.java:4861`** (drifted +200 this window from the prior audit's `:4661`, via R363's FieldBuilder growth), keying on the reflection/classification-derived **record-backed** determination, not a `TypeContext.hasDirective(typeDef,"record")` SDL predicate. Call sites at `:4517`/`:4602`/`:4684`/`:4809` (all +200; count unchanged at four). Re-spec against the current `warnIfSplitQueryOnRecordParent` wording, or **discard** if the SDL-only LSP can't mirror the reflection-derived determination. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines" and "~30 private methods"; `TypeFetcherGenerator.java` is **6 175** (~3.75× the cited figure) with well over 100 private method declarations (the file also carries 10 nested type declarations). Lightest re-spec in this section: refresh the LOC/method-count figures and re-survey the cut against the real file, or stop hard-coding a count. |
| **R19** history-squash | Ready | Every SHA/count is an April-2026 snapshot: "595 (as of 2026-04-24)", a "566-commit history" (lines 14/28/124), the merge base `ab3daff2`, the expected trunk tip `8a8c5efe` (line 102), and a frozen list of SHAs. The branch is now **3 175 ahead** of `origin/main` (up from the prior audit's ~3 100; ~5.6× the documented 566). All numbers/SHAs/drop-lists need regeneration, or better, computing dynamically at execution time, before this can execute. |

## C. Outdated — update references only (work valid, refs stale) (12)

Substance intact; only paths, line numbers, or dependency tense drifted.
Composition unchanged from the prior audit. The recurring root cause is
unchanged: `TypeFetcherGenerator.java` (**6 175**) / `FieldBuilder.java`
(**6 137**) / `BuildContext.java` (**2 465**) standing at ~2.7 to 3.8× the sizes
the older specs cite, plus a `docs/*.md` → `docs/*.adoc` migration. R363's
multitable filter lowering (FieldBuilder +200) and R377's decode-helper rewrite
moved the most anchors; the affected rows are flagged inline.

| Item | Stale reference |
|---|---|
| **R308** service-list-payload-arrival | Substance fully intact: the `@service` list-payload N+1 is real and unbuilt. But its body still describes the no-DataLoader shape as "`SingleRecordTableField` today, the single-arrival arm of the merged `RecordTableField` after R305", and `SingleRecordTableField` was **deleted** by R305 (surviving only in `ChildField.java` Javadoc at lines 47/184/222/743). The live carrier `RecordTableField` is at `model/ChildField.java:829` (no drift this window). Strip the "today" framing and read it as the single-arrival arm of `RecordTableField`; bump `last-updated`. (`depends-on` already `[]`.) |
| **R202** honor-field-directive-in-error-type-source-accessors | Premise live and unbuilt: `checkErrorTypeSourceAccessors` (`FieldBuilder.java:2607`, called `:2385`) calls `ClassAccessorResolver.resolve(sourceClass, sdlField.getName(), …)` (the resolve call at `:2624-2631`) with the raw SDL name and **no `@field` read**, exactly the gap R202 describes. **Drifted +~130 this window** (def `:2474`→`:2607`, call `:2252`→`:2385`) from R363's FieldBuilder growth. R200's Done note names R202 as a remaining `@field`-symmetry sibling. Only staleness is the body's internal cites; re-anchor at the symbol. |
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck as "Obsolete (R287)"; body re-based onto R305's re-fetch substrate; `depends-on` `[]`. One numeric anchor stale: `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` cited at "`:944`" (3 sites) is now a single def at **`:631`** (called `:373`; the def still iterates the RETURNING `Result<Record>` and appends one id per row at `:651-660`, no `VALUES`-join — substance intact). Re-anchor at the symbol and drop the stale "3 sites" count. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` (**679**, held exactly this window) substance holds (emit-half still unbuilt). Anchors: the argument-level `@condition` resolution dispatch is at `:414-420`, the non-`@table` arg rejection (`foundTia.argCondition().isPresent()`) at `:436`; the `@condition(override:true)` admission gate reads `ARG_OVERRIDE` (the constant lives in `BuildContext.java:137`, consumed `:1745`). Body cites ~438-440 / :482-498; re-anchor at the symbols. |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`. The two cited sibling docs (`docs-as-index-into-tests.md`, `rewrite-docs-entrypoint.md`) do not exist under any extension (repo-wide search returns nothing). Relocate or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (absent repo-wide) and stale `ConnectionSynthesis` naming. The live seam is now `ConnectionPromoter.synthesiseForField` / `.rebuildAssembledForConnections` (`ConnectionPromoter.java:120`/`:194`, no drift this window); re-anchor there. |
| **R99** lsp-submodule-sibling-classpath | Path `graphitron-rewrite/graphitron-maven/…` (body `:18`) is actually `graphitron-maven-plugin/…`; the cited `getAllProjects()` is now reached via `reactorProjects()` at `AbstractRewriteMojo.java:208-209`. **Re-confirmed this window:** R369's `generatedSourceRoots`/`compileSourceRootsOf` (prior window) addressed the *source-walk* root set; `reactorProjects()` still returns `session.getAllProjects()` with **no parent-pom walk-up** for the single-project-reactor case, so R99's *classpath-scan* sub-module miss still reproduces. |
| **R92** catalog-check-constraint-validation | Seam anchors drifted (TypeFetcherGenerator local growth): `validatorPreStep` cited `:1326` is now defined **`:1673`** (was `:1655` prior audit; called **`:1566`**); `DefaultValidatorHolder` at `GraphitronContextInterfaceGenerator.java:84`, `getValidator` at `:95-99` (these two still accurate). No `CheckRecognizer`/`findCheckConstraints` exists; the R12 §5 validator seam this builds on is present as described. Re-anchor the two TypeFetcherGenerator lines. |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue` emission cited at `:1456`/`:1496`/`:1508`/`:1769` is now clustered in `buildPerCellValueList` at **`TypeFetcherGenerator.java:2042`** (emitting `:2071`/`:2078`/`:2092`, with a deduped sibling at `:2243`/`:2248`). The contract-side gap (no SDL default lift) is unbuilt. |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog.reflectTableMethod` has two overloads (dispatcher `:484`, slot-types-aware `:498`); the strict `ClassName.equals` return-type gate is at `:527-529`; `buildQueryTableMethodFetcher` is at **`TypeFetcherGenerator.java:1160`** (was `:1142` prior audit; called `:443`). Neither `MethodRef.StaticOnly` nor `ReturnTypeRef.TableBoundReturnType` carries a type token yet. Re-anchor at the symbols. |
| **R236** validator-reference-candidate-hint-terminal-table | (Anchors are in the **item body**, lines 17/27.) `classifyInputFieldInternal` is at `BuildContext.java:1831` (body cites `:1665-1677`), and the candidate-hint failure-aggregation (`candidateHint(c, catalog.columnSqlNamesOf(resolvedTable.tableName()))`) at `:1920` (both held this window). The hint still draws from the path-origin `resolvedTable.tableName()`, not the `@reference` terminal table. Re-anchor at the symbol. |
| **R35** source-orientation-javadocs | LOC counts grossly stale (FieldBuilder cited "2 172", now **6 137**; TypeFetcherGenerator "1 646", now **6 175**); no `package-info.java` exists in the rewrite tree (sweep undone). Doc cross-link nuance: of the four cited links, only `code-generation-triggers` actually migrated to `.adoc` and is wrongly linked as `.md`; the other three (`selection-parser-audit`, `decompose-typefetchergenerator`, `changelog`) are roadmap `.md` files and are correctly `.md`. The "FieldBuilder decomposition shipped under R6" claim stays dubious given the file is now ~2.8× larger. |

**Re-confirmed Current (not flagged):** the **MCP programme (R118)** is correctly
Backlog as the programme umbrella now that R362/R368/R372/R374 closed and stood up
the `graphitron-mcp` module; slices 9-11 (the `docs.search`/`catalog.search`
semantic tools R372 unblocks) are the live remainder, spec-forward, none flagged.
**R267** nodeid-encoder-deprecated-convert (the deprecated
`col.getDataType().convert(values[i])` still emitted, class-wide `@SuppressWarnings`).
**R219** unify-inference-rule-by-javatypekey and **R220**
consolidate-sources-shape-predicates both target `ServiceCatalog` parameter-binding
inference, untouched this window; valid, unbuilt, not stale. **R222**
dimensional-model-pivot stays **Spec** as the umbrella (`depends-on: []`, Stages 5
through 7 outstanding). **R333** coordinate-lowers-to-datafetcher-queryparts (Spec,
retitled "The Graphitron data model") took a documentation/diagram rework this
window without a status move or generator code change; correctly not flagged. The
new items **R373**/**R378**/**R379**/**R380**/**R381**/**R382**/**R383** are fresh,
on current/fresh code, none under flag. **R360** (`retire-enum-directive`, Backlog)
stays valid: `@enum` is still live in `SchemaDirectiveRegistry`/`TypeBuilder`, so the
retirement is genuinely unbuilt, and its R261 coordination edge holds.

## Cross-cutting observations

1. **Closure hygiene stayed clean; the dependency graph held.** Every item that
   left the board this window self-closed cleanly (R148/R362/R363/R365/R368/R371 Done
   + files deleted; R372/R374/R375/R376/R377 filed-and-closed within the window). A
   full `depends-on` sweep across all 127 item files (re-run programmatically) found
   **no dangling slugs**. **R30** (self-Done, unswept) remains the **only** stranded
   Done tombstone, alongside R146/R52's discard-toward-sibling Backlog files. The
   workflow rule that *the closing author deletes the file* still has no owner when
   the closure comes from a sibling (R146, R52). Worth a one-shot cleanup pass and a
   workflow note.
2. **This was the largest feature-landing window so far, and a single-axis one.**
   The MCP programme (R118) accounted for the bulk of it: a whole new `graphitron-mcp`
   module (~3 000 lines main + test) carrying catalog facts, live-Workspace
   read-tools, a cross-reference edge graph, and a RAG foundation, closing four slices
   (R362/R368/R372/R374). Yet no structural axis moved: the flag composition held at
   exactly 31 (3 / 16 / 12), no flagged item resolved or was created, and all eleven
   closures plus all seven new items landed on current/fresh items. The board grew
   122 → 126 → 127 by ordinary accounting.
3. **Line-drift recurred, concentrated in two files this window.** R363's multitable
   `@field` filter lowering grew `FieldBuilder` (**6 137**, +200), and R377's
   decode-helper rewrite touched `BuildContext` (**2 465**, +19). The §B/§C rows that
   drifted are **R201** (`:490`/`:2406`), **R121** (+200 to `:4861`), **R202** (`:2607`),
   **R263** (method body **rewritten**, def `:2226`), and **R92** (`:1673`); the rest
   held. R263 is the sharpest evidence this window that specs anchored to **symbol
   names** survive better than line numbers, but it also shows even symbol-anchored
   specs go stale when the symbol's *body* is rewritten (R377) — the only durable
   defense there is re-reading the method at re-spec time. This remains the structural
   backdrop behind all of §C.
4. **The next structural pivot to watch is still the `SourceKey` decomposition.**
   R316's changelog named it the "first concrete consumer once this pivot lands"; it
   will collapse `wrap`+`cardinality` out of `SourceKey` (`SourceKey.java:81-82`,
   `:94-102` today). R333's documentation rework this window did **not** touch the
   `Wrap` contract (re-confirmed verbatim), so the watch holds: when the decomposition
   ships, **R71** (re-anchors on `SourceKey.Wrap`, which it removes), **R314** (the
   emit re-platforming R316 pinned to it), and **R234**'s input-carrier collapse will
   all need re-checking. This remains the standing watch.
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
7. **The MCP programme matured from frontier to module; `@reference` is the new
   frontier.** R118 widened last window and shipped four slices this window; the
   live remainder is the semantic-search tools (slices 9-11). Meanwhile a new
   `@reference` cluster opened: **R379** (terminal-hop validation, Spec), **R380**
   (join-subquery filter conditions, Spec), and **R381** (LSP path authoring, Spec,
   blocked by R379). None is flagged; all are spec-forward on fresh code. Worth
   watching that the `@reference` specs re-anchor cleanly as the join-translation
   surface fills in.
8. **Cosmetic front-matter nits, none flag-worthy (carried over).** **R333**
   (`coordinate-lowers-to-datafetcher-queryparts`, Spec) **still has no `depends-on:`
   key** despite the documentation rework this window; add one for parseability on its
   next touch. **R97** lacks `created:`/`last-updated:` header fields (an older item
   predating that convention). Neither is a build or dependency risk.
9. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-06-26._
