# Roadmap staleness audit: 2026-07-03

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `1fae7e9`, 2026-07-02 20:25). The goal is to find items whose premise no
longer holds: work already shipped, constructs renamed or removed, dependencies
that have since landed, or specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-02` staleness audit, which has been deleted;
only the latest staleness audit is retained. Three siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
  It is a closed-work lineage record, not a point-in-time staleness review.
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  a companion to this audit. It is meant to be edited in place as scope iterates.
  It now reads further behind still: on top of the R182/R398/R370/R406/R405/R332/
  R407/R416 closures the prior audit flagged, **R384, R396, R408, R413, R414,
  R415, R418 and R421** have all reached Done since it was last touched, so its
  MUST/SHOULD tables lag; refreshing it is out of scope for this staleness pass.
- `classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
  inventory, doubly stale (against R299/R290 and against the R316 corpus recut).
  It still warrants a "superseded, historical" banner; that banner has **not**
  been added, left unedited here per scope (see observation 7).

## Changes since the 2026-07-02 audit

This is a **quiet, incremental window**: no structural pivot on the scale of the
prior window's R182 unnesting. The dominant activity was a run of localized bug
fixes and connection/`@service` correctness work, plus a cluster of freshly-filed
`@service`-child items. The R182 repo-root path layout the prior audit re-rooted
onto is fully settled; **no citation in this refresh needed re-rooting**, and the
line-anchor drift is modest (see observation 2).

**Terminal closures this window (Done, all self-deleted their files, none flagged,
all were current):** **R384** (converted/`@nodeId`/developer-`@condition` multitable
filters, was **In Review** at the prior snapshot), **R414**
(`nested-connection-totalcount-always-null`, per-parent totalCount on split/DataLoader
connections), **R415** (`connection-first-last-negative-unclamped`, first/last clamp +
unified no-channel disposition), **R413** (`split-parent-input-values-converter-datatype`,
bind parent-input VALUES cells through the column Converter DataType), **R396**
(`reference-fk-connection-qualified-table-name`, identity-based FK source-side predicate
for schema-qualified `@table`; filed **R422** for the qualified-return-type residual),
**R408** (`lint-finding-suppression`, was **In Review** in the prior audit; the
suppression mechanism riding on R398's lint engine), plus two items **filed and closed
within this same window**: **R418** (`reseed-rewrite-test-on-sessionstart`, always
drop-and-recreate `rewrite_test` on the SessionStart hook) and **R421**
(jakarta-rest `execute()` error-leak guard). Eight closures total; none was under flag.

**New items on the board (all filed this window, all current/fresh):** **R419**
`list-nodeid-reference-insert-rejection` (Backlog), **R420**
`list-nodeid-reference-insert-fanout` (Backlog), **R422**
`reference-terminal-verdict-return-type-identity` (Backlog, the R396 residual),
**R423** `redaction-reference-id-from-otel-traceid` (Backlog), **R424**
`inline-field-args-from-selectedfield` (**In Review**; inline `@reference` fields
read filter/pagination args off the wrong `DataFetchingEnvironment`), **R425**
`service-splitquery-key-columns-in-parent-projection` (**Ready**; parent projection
omits a `@splitQuery`/`@service` child's key columns), **R426**
`service-tablerecord-partial-record-nonkey-reads` (**Spec**; `depends-on: R425`,
TableRecord-sourced `@service` keys are partial records), **R427**
`relevance-ranked-search` (Backlog). All re-verified spec-forward on fresh code;
every named anchor resolves; no dangling `depends-on`.

**Transitions this window:** **R410** (`dev-incremental-compile`) advanced **Ready →
In Progress** (slices 1-2 shipped: the writer's per-run delta, then the model-sourced
compile-dependency graph behind `CompileDependencyGraph`). **R424** ran
Backlog → Spec → Ready → In Progress → **In Review**. **R425** ran Backlog → Spec →
**Ready**. **R426** ran Backlog → **Spec**. **R347** (`lsp-structural-consolidation`)
holds at **In Progress**.

**Corrections to the prior audit's "re-confirmed current" list.** Three items the
prior audit listed as current are, on the durable record (`roadmap/changelog.md`
landings, item files absent), **already Done** and must not appear on the active
board. **R396** (`reference-fk-connection-qualified-table-name`, prior audit: Ready)
closed **this** window. **R261** (`wire-coercion-cast-guard`) and **R63**
(`dml-dialect-requirement-on-model`), both listed **Spec** by the prior audit, sit
**below** this window's boundary in the changelog (below R398/R409), so they were Done
before the prior audit was written; that listing was already stale. (The local clone
is shallow, 50 commits rooted at the prior audit commit, so their exact close date is
not recoverable from git; the changelog is authoritative.) R411
(`reject-wire-coercion-nonservice-sites`, Backlog) correctly carries R261's Slice 2 and
is unaffected.

**Board accounting.** **125 item files** today (prior window: 124). Status
distribution: **105 Backlog, 13 Spec, 4 Ready, 2 In Progress (R347, R410), 1 In
Review (R424); zero Done** (tombstone-free for the third window running). A
non-recursive `^status: Done` grep over `roadmap/*.md` returns nothing. No duplicate
`id:`; `next-id: R428` with max allocated id R427. A `depends-on` sweep found no
dangling slugs (R426's `depends-on: [service-splitquery-key-columns-in-parent-projection]`
resolves to the live R425). The board is structurally clean.

**Net effect on flag counts: 22 flagged, 103 current** (prior window: 22/102, on a
smaller board). Flag composition holds at **(1 / 8 / 13)**: §A is R263 alone; §B holds
its eight; §C holds its thirteen. Every closure and every new item this window landed
on current/fresh code; none under flag. Every flagged item carried over from the prior
audit was re-verified against current HEAD and **every one still reproduces**.

## Scope and method

All **125** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder). For each item the targets it names
(classes, directives, methods, packages, modules) were located in the current
tree under `graphitron/src/main/java/no/sikt/graphitron/rewrite/`, the described
problem was checked for whether it still reproduces, and the changelog was scanned
for the item's `R<n>` and key terms to catch work that shipped without the item
being closed.

The structural landings the prior audits relied on all still hold: **R276**
(`ResultType` is a 4-arm seal; `PojoResultType` permits only `Backed`), **R290**
(`LeafTupleAdapter` / `ConstructorField` dissolved), **R305** (`SingleRecordTableField`
gone, re-confirmed as neither a declared type nor a pattern-match arm; surviving only in
Javadoc/comment mentions; live carrier `RecordTableField` in `model/ChildField.java`),
and **R316** (the `carrier × intent × mapping` field model gone, replaced by
`(source, operation, target)`; `model/Operation.java:30` is a `sealed interface`, and
`:78` still notes "Collapses to one carrier under R314"). The **`SourceKey` `Wrap`
decomposition still has not landed** (`SourceKey.java` still carries `Wrap wrap` +
`Cardinality cardinality` as record components, and the compact constructor still pins
`SourceRowsCall → Wrap.Row`), so the standing R71 / observation 3 watch holds verbatim.
The two new `@service`-child items now additionally pin on `SourceKey.Wrap.TableRecord`
(R426) and `sourceKey().columns()` (R425), which deepens that watch rather than easing it.

Line-anchor drift this window was again concentrated in the large generator files,
driven by the R384/R396/R413/R414/R415/R408 landings:
**`TypeFetcherGenerator.java`** grew to **6 588** (**+52** from the prior audit's 6 536),
**`FieldBuilder.java`** to **6 363** (**+3** from 6 360), and **`BuildContext.java`**
**shrank** to **2 669** (**−6** from 2 675). The TypeFetcherGenerator-anchored §C rows
took a uniform **≈+43-48** shift (R92, R103, R201, R240, R242); the FieldBuilder-anchored
rows shifted **≈+13** below their (held) top-of-file methods (R202, R308); the
BuildContext-anchored rows shifted **≈−6** (R236, R263).

**Result: 22 items flagged, 103 current.** Line numbers cited below are as of the
review date and will themselves drift; see observation 2.

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Action | Why |
|---|---|---|---|
| **R263** decode-helper-typename-first-resolution | Backlog | **Discard as speculative** (re-open only if a MUST-route `@nodeId(typeName:)` caller appears) | Unchanged from the prior audit's §A promotion, and re-verified on current HEAD. **R377 settled the open question the discard was hedged on.** `resolveDecodeHelperForTable` is now defined `BuildContext.java:2430` (was `:2436`; BuildContext shrank 6 lines this window); it resolves through the `@node` `NodeIndex`, returns `null` on multi-node ambiguity (a validate-time rejection, not the silent `decode<firstType>` the original finding described), and falls back to the `typeId` suffix only when no `@node` backs the table. Its callers (`BuildContext.java:2242`/`:2380`, was `:2248`/`:2386`, plus the `FieldBuilder`/`NodeIdLeafResolver` sites) all pass the table name as primary; **none** routes an authoritative `@nodeId(typeName:)`. The premise survives only in spirit and no consumer needs it. Re-anchor onto the R377 body if capturing the finding in `changelog.md`, then discard. (R377 has no roadmap `.md` file; it is referenced only in code javadoc / changelog.) |

## B. Outdated: needs re-spec (premise or targets materially changed) (8)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. All eight carry
over from the prior audit and were re-verified this window; **every one still
reproduces.** (The R182 repo-root path shift the prior audit re-rooted onto is now
fully settled, so unlike the prior window these rows carry no extra path churn.)

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; still the sharpest.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm R276 deleted: `ResultType` is a 4-arm seal, `PojoResultType` permits only `Backed`. Its primary migration target `FetcherEmitter.propertyOrRecordValue` **still does not exist** (zero hits; the spec's own `:802`/`:131` anchors are dead); only the `GeneratorUtils.buildFkRowKey` half of the duplication survives (`generators/GeneratorUtils.java:241`, called `:212`; both held exactly this window). The `.md` still describes the deleted five-arm / `PojoResultType.NoBacking` model throughout. Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | **Symbol re-anchor; substance confirmed still future, and the standing structural watch.** Every symbol the mechanism names is dead: no `BatchKeyLifterDirectiveResolver`, no `BatchKey`/`LifterRowKeyed`/`RowKeyed` types anywhere. The live surface is `model/LifterRef.java`, routed through `@sourceRow` and `SourceKey.Reader.SourceRowsCall(LifterRef)`. R316 did **not** collapse `wrap`+`cardinality` out of `SourceKey` (`SourceKey.java` still carries both record components), and the compact constructor still pins `SourceRowsCall → Wrap.Row`, the exact Row-only asymmetry R71 wants to remove. Re-anchor on `LifterRef`/`SourceRowsCall`/`Wrap`. |
| **R46** service-multi-tenant-fanout | Backlog | **The dependency abandoned the design R46 extends, and this held all window.** `ContextValueRegistration`, `getContextFanOut`, `openContextDslContext`, `getExecutor` all have **zero** `.java` hits repo-wide (R190 sealed `GraphitronContext`). R46's dependency **R45** (`tenant-routing-and-execution-input`, Ready) ships a per-field `TenantIdSource` overlay + `byTenant` on `GraphQLContext`, **not** the sealed `ContextValueRegistration` the whole "Design" section rests on. The `depends-on:` correctly uses the live slug `tenant-routing-and-execution-input`, but the body prose still links the dead `typed-context-value-registry.md` slug. Re-spec the whole Design section against the `TenantIdSource` overlay R45 actually ships. |
| **R314** dissolve-reentry-leaves-dimensional-emit | Backlog | The design section still names R316-deleted types as the build target: `Carrier.Source`, `Intent`/`Mapping` as proposed sealed hierarchies, and `f(carrier.sourceContext, intent.operation, mapping.target)`; an implementer would search for symbols that no longer exist. R316 narrowed R314's scope rather than mooting it: `Operation.java:78` still notes "Collapses to one carrier under R314," and both `requiresReFetch()` (`model/OutputField.java`) and `dispatchPerformsReFetch` (`GraphitronSchemaValidator.java`) still co-exist. Re-spec the design prose onto `(source, operation, target)`; `depends-on: [dimensional-model-pivot]` (R222, Spec) is the genuine blocker and resolves. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (Done), so the "R58 is currently *In Review* … if it reverts" framing (line 21) is false and should be removed. Carriers are still String-flattened (`ParsedPath(…, String errorMessage, …)` at `BuildContext.java:990` and `InputFieldResolution.Unresolved(…, String reason, …)` at `InputFieldResolution.java:20` both remain), so the carrier-audit body stays valid; `depends-on: []` is already correct. Lightest re-spec in this section: strike the reversion hedge. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)" (front-matter `title:` and README); **R94 shipped** (Done, slug `emit-input-records`, file absent). Blocker cleared, drop the blocked framing from title and body. R94's Done note hands the real remaining dependency to **R98** (`multi-source-input-validation`, re-confirmed Backlog, file present), so re-point the dependency there and the title "(R94-blocked)" should become "(R98-blocked)". |
| **R24** nodeidreferencefield-join-projection-form | Backlog | Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **still does not exist** (dispatch is now `FetcherEmitter.bind`/`bindRaw`; `dataFetcherValue` survives only in stale Javadoc at `model/ChildField.java:1062`/`:1089`; the body's "lines 140-162" are dead), and the carrier in the **item title**, `NodeIdReferenceField`, was renamed by R50 to `ColumnReferenceField`/`CompositeColumnReferenceField` (zero `NodeIdReferenceField` hits). The surviving "not yet implemented" stub is `CompositeColumnReferenceField` and it is the **single** entry in `TypeFetcherGenerator.STUBBED_VARIANTS` (`:300`, the `CompositeColumnReferenceField.class` mapping at `:308-310`, "not yet implemented"), confirming the release-planning doc's "one tracked stub" claim. Work is valid and unbuilt, but the title symbol + method names need a full re-anchor. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale and now further off: body says "1 646 lines" and "~30 private methods"; `TypeFetcherGenerator.java` is **6 588** (~4.0×). Lightest-substance re-spec in this section: refresh the LOC/method-count figures and re-survey the cut against the real file, or better, stop hard-coding a count and state "measure at execution time." |

## C. Outdated: update references only (work valid, refs stale) (13)

Substance intact; only paths, line numbers, or dependency tense drifted. The
recurring code-side root cause is unchanged: `TypeFetcherGenerator.java` (**6 588**) /
`FieldBuilder.java` (**6 363**) / `BuildContext.java` (**2 669**) standing at ~2.6 to
4.0× the sizes the older specs cite. The R384/R396/R413/R414/R415/R408 landings moved the
TypeFetcherGenerator anchors ≈+43-48 and the FieldBuilder mid-file anchors ≈+13; the
BuildContext-anchored rows shifted ≈−6.

| Item | Stale reference |
|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Premise live and unbuilt: `resolvePayloadConstructionShape` on the `PayloadClass` arm (`FieldBuilder.java:494`, held; called `:2576`, was `:2563`); the mutable-bean logic in `tryMutableBean` (`:559`, held), whose setter-match arm builds `SetterBinding`s off the raw SDL field name via `javaBeanSetterName(sdlFieldName)` (`:606`, held; predicate `:577`) with **no `@field` read**. The emitter halves are `TypeFetcherGenerator.payloadFactoryLambda` (`:6485`, drifted from `:6437`) and `payloadFactoryLambdaSetters` (`:6502`, was `:6454`). Body already frames it as "the output-side mirror of R200"; only the cited line ranges drifted. Re-anchor at the symbols; no premise change. |
| **R308** service-list-payload-arrival | The `@service` list-payload N+1 is real and unbuilt (`checkServiceReturnMatchesPayload` `FieldBuilder.java:3038`, was `:3025`; called `:2999`, was `:2986`; still admits `List<Payload>`). The body still describes the no-DataLoader shape as "`SingleRecordTableField` today, the single-arrival arm of the merged `RecordTableField` after R305", and `SingleRecordTableField` was **deleted** by R305 (re-confirmed: neither a declared type nor a pattern-match arm). The live carrier `RecordTableField` is in `model/ChildField.java`. Strip the "today" framing; bump `last-updated`. **Note the new `@service`-child cluster (R424/R425/R426) is adjacent but distinct** (inline-arg env, DataLoader key-column projection, and partial-record reads, respectively; none is the list-payload N+1 R308 targets), so R308 is not subsumed. R305 is no longer a live roadmap `.md`; verify that cross-reference on the next touch. (`depends-on` already `[]`.) |
| **R202** honor-field-directive-in-error-type-source-accessors | Premise live and unbuilt: `checkErrorTypeSourceAccessors` (`FieldBuilder.java:2780`, was `:2767`; called `:2555`, was `:2542`) calls `ClassAccessorResolver.resolve(...)` (`:2799`) with the raw SDL name and **no `@field` read**, exactly the gap R202 describes. R200's Done note names R202 as a remaining `@field`-symmetry sibling. Re-anchor at the symbol. |
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck as "Obsolete (R287)"; body re-based onto R305's re-fetch substrate; `depends-on` `[]`. Numeric anchors stale: `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` is at `generators/FetcherEmitter.java:631` (called `:373`), and the `[ID]`-reject scoped to `CarrierFamily.DML` in `BuildContext.java` still needs re-anchoring at the symbol; drop the stale "3 sites" count. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` (**679**, held **exactly** this window) substance holds (emit-half still unbuilt). Anchors: the non-`@table` arg rejection (`foundTia.argCondition().isPresent()`) is at `:436` (held); the `@condition(override:true)` admission gate reads `ARG_OVERRIDE` (imported from `BuildContext` at `:26`, held) with the override gate at `:493`. Body cites older ranges; re-anchor at the symbols (drift is minimal here). |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`, under the **Diataxis `docs/architecture/` tree** R182 introduced, not the old flat `docs/` dir. The two cited sibling docs (`docs-as-index-into-tests.md`, `rewrite-docs-entrypoint.md`) do not exist under any extension or location. Relocate onto the Diataxis layout or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (confirmed absent) and stale `ConnectionSynthesis` naming. The live seam is `ConnectionPromoter.synthesiseForField` / `.rebuildAssembledForConnections` (`ConnectionPromoter.java:122`/`:196`, both held; file now 498 lines); re-anchor there and fix the dangling doc link. |
| **R92** catalog-check-constraint-validation | `validatorPreStep` is now defined `TypeFetcherGenerator.java:1984` (was `:1941`; seam call site `:1852`, was `:1809`); `DefaultValidatorHolder` and `getValidator` live in the `generators/util/` subpackage (`generators/util/GraphitronContextInterfaceGenerator.java`). No `CheckRecognizer`/`findCheckConstraints` exists; the R12 §5 validator seam this builds on is present as described. Re-anchor the TypeFetcherGenerator line and the context-gen lines. |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue` emission is clustered in `buildPerCellValueList` (defined `TypeFetcherGenerator.java:2353`, was `:2310`; emitting sites `:2288`/`:2295`/`:2302`, was `:2245`/`:2252`/`:2259`), with a deduped sibling `buildPerCellValueListDeduped` at `:2526` (was `:2483`). The contract-side gap (no SDL default lift) is unbuilt. Note R413 (Done this window) bound parent-input VALUES cells through the column Converter DataType in this same cluster, but did **not** address the SDL-default lift R103 targets. |
| **R240** tablemethod-return-type-token-threading | Current: `ServiceCatalog.reflectTableMethod` has two overloads (dispatcher `:565`, slot-types-aware `:579`; **both held exactly** this window); the strict `ClassName.equals` return-type gate follows; `buildQueryTableMethodFetcher` is at `TypeFetcherGenerator.java:1439` (was `:1396`; called `:462`). Neither `MethodRef.StaticOnly` nor `ReturnTypeRef.TableBoundReturnType` carries a type token yet. Re-anchor at the symbols. |
| **R236** validator-reference-candidate-hint-terminal-table | (Anchors are in the **item body**.) `classifyInputFieldInternal` (`BuildContext.java:2032`) and the candidate-hint failure-aggregation (`candidateHint(c, catalog.columnSqlNamesOf(resolvedTable.tableName()))` at `:2121`) are in `BuildContext.java`. The hint still draws from the path-origin `resolvedTable.tableName()`, not the `@reference` terminal table (R380's Done note still lists R236 as an open sibling). Re-anchor at the symbol (drift is −6 from the BuildContext shrink). |
| **R35** source-orientation-javadocs | LOC counts grossly stale and now further off (FieldBuilder cited "2 172", now **6 363**; TypeFetcherGenerator "1 646", now **6 588**); no `package-info.java` exists in the rewrite tree (sweep undone, re-confirmed). Doc cross-link nuance: the architecture docs are the Diataxis `docs/architecture/` tree (`.adoc`), and `selection-parser-audit` no longer exists (R30 swept). Refresh the figures and re-point the doc links onto the Diataxis layout. |
| **R234** jooq-embedded-and-udt-input-backings | Valid, dormant Backlog stub gated behind the R222 collapse (Stages 5–7). Anchors to keep fresh: `JooqRecordInputType` is a live populated input arm (`GraphitronType.java`); `JooqTableRecordInputType` is a separate sibling; the proposed `BackingClass` family is still unbuilt (no `BackingClass.java`; `parentBackingClass` exists only as a component on `AccessorRef.java`). `depends-on: []` is correct (R222 is a prose gate, not a hard edge). |

**Re-confirmed Current (not flagged):** **R45** (`tenant-routing-and-execution-input`,
**Ready**), the `TenantIdSource` overlay, internally consistent, reconciled with
R190/R316. **R13** (`faceted-search`, **Ready**), the re-spec onto the live
`ConnectionPromoter` seam still holds. **R219** `unify-inference-rule-by-javatypekey`
and **R220** `consolidate-sources-shape-predicates` both target `ServiceCatalog`
parameter-binding inference; valid, unbuilt. **R267** `nodeid-encoder-deprecated-convert`.
**R222** `dimensional-model-pivot` stays **Spec** as the umbrella (`depends-on: []`,
Stages 5 through 7 outstanding). **R333** `coordinate-lowers-to-datafetcher-queryparts`
(Spec), still no `depends-on:` key; correctly not flagged. **R273**
`nodeid-skip-mismatch-error-surfacing` (Spec), its "Settled by R378" section correctly
folds in the now-Done R378 policy. **R346** `supported-directives-regen-guard` (Ready)
and **R360** (`retire-enum-directive`, Backlog; `@enum` still live, retirement genuinely
unbuilt) re-verified. The new **R424** (In Review), **R425** (Ready), **R426** (Spec),
**R419**/**R420**/**R422**/**R423**/**R427** (Backlog), plus **R347** and **R410** (both In
Progress), all re-verified spec-forward on current code, none under flag.

## Cross-cutting observations

1. **A quiet, incremental window.** No structural pivot this time; the activity was
   localized bug fixes (R384, R396, R413, R414, R415), an infra/test-harness closure
   (R418), an error-leak guard (R421), a lint-suppression closure (R408), and a fresh
   `@service`-child defect cluster (R424/R425/R426). Net flag count held at 22 on a
   board that grew by one item (124 → 125). Zero Done tombstones for the third window
   running.
2. **Line-drift recurred, small and concentrated in the two large generator files.**
   `TypeFetcherGenerator.java` grew **+52** (6 536 → 6 588), `FieldBuilder.java` **+3**
   (6 360 → 6 363), and `BuildContext.java` **shrank −6** (2 675 → 2 669). The
   TypeFetcherGenerator-anchored §C rows took a uniform ≈+43-48 shift (R92, R103, R201,
   R240, R242), the FieldBuilder mid-file rows ≈+13 (R202, R308), and the BuildContext
   rows ≈−6 (R236, R263). The only durable defense remains re-reading the method at
   re-spec time; this is the structural backdrop behind §C.
3. **The next structural pivot to watch is still the `SourceKey` decomposition,** and it
   is now more load-bearing than before. It will collapse `wrap`+`cardinality` out of
   `SourceKey`. Re-confirmed verbatim this window: the `Wrap` contract is untouched, the
   compact constructor still pins `SourceRowsCall → Wrap.Row`. The new `@service`-child
   items **R425** (force-includes `sourceKey().columns()` into the parent projection) and
   **R426** (keys on `SourceKey.Wrap.TableRecord`) now sit directly on this surface, so
   when the decomposition ships, **R71**, **R314**, **R234**, **R425** and **R426** will
   all need re-checking.
4. **R222 remains the open umbrella** (Spec, `depends-on: []`). Stages 5 through 7 (the
   `JooqRecordInputType` collapse R234 forward-dates, namespace collapse, directive
   narrowing closing R97) are still outstanding, consistent with R234's premise being
   premature, not wrong-in-spirit, which is why R234 stays in §C.
5. **A new `@service`-child correctness cluster landed on the board and is all current.**
   **R424** (In Review, inline `@reference` args read off the wrong `env`), **R425**
   (Ready, parent projection omits a `@splitQuery`/`@service` child's key columns), and
   **R426** (Spec, TableRecord-sourced `@service` keys are partial records) are three
   distinct silent-null data-correctness bugs. They are adjacent to the flagged §C item
   **R308** (`@service` list-payload N+1) but do **not** subsume it; R308's stale
   `SingleRecordTableField` reference is the only staleness in that neighborhood.
6. **The `@enum` retirement continues to bound a small staleness class.** **R360**
   proposes a declared-but-rejected retirement for `@enum`; `@enum` is still live in
   code, so R360 is correctly Backlog and no item is yet stale against it.
7. **`classification-test-dsl-inventory.md`** is doubly stale (R299/R290 + the R316
   corpus recut) and still warrants the "superseded, historical" banner; it has **not**
   been added (left unedited here per scope).
8. **Prior-audit "re-confirmed current" corrections.** **R396** (Ready → Done this
   window), **R261** and **R63** (both listed Spec by the prior audit but already Done
   per the changelog, below this window's boundary) are all off the active board now.
   The prior audit's observation-7 minor lags (**R26** links `remove-notgenerated.md`,
   gone because that work shipped; **R397** references `redactCatchArm`, renamed to
   `noChannelCatchArm`) still stand; neither is a dangling `depends-on` or build risk.
9. **Cosmetic front-matter nits, none flag-worthy (carried over).** **R333** still has
   no `depends-on:` key; add one for parseability on its next touch. **R97** lacks
   `created:`/`last-updated:` header fields (an older item predating that convention).
   Neither is a build or dependency risk.
10. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
    placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-03._
