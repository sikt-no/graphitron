# Roadmap staleness audit — 2026-06-17

A point-in-time review of every active roadmap item under
[`graphitron-rewrite/roadmap/`](../) against the **current** state of the
codebase on `claude/graphitron-rewrite` (HEAD `ab6aaccd`, 2026-06-17 00:12). The
goal is to find items whose premise no longer holds: work already shipped,
constructs renamed or removed, dependencies that have since landed, or specs
grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-06-16` staleness audit, which has been deleted —
only the latest audit is retained. Two siblings in this directory are **not**
staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source/context,
  operation, target)` reframe analysis filed this window as the permanent lineage
  document for **R316** — that item links it directly. It is an argument record,
  not a point-in-time staleness review, and is not superseded by this audit.
- `classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
  inventory. It remains materially stale against R299/R290 and still warrants the
  "superseded — historical" banner the prior two audits recommended; it has
  **not** been added (left unedited here per scope — see observation 6).

## Changes since the 2026-06-16 audit

**43 commits** landed between the prior audit commit (`edad44f0`, committed
2026-06-16 03:17) and this one (HEAD `ab6aaccd`, 2026-06-17 00:12). The dominant
events this window are **R279 going active** (`field-first-classification-driver`
moved Ready → In Progress and shipped slices 1–6, folding connection synthesis
and type-registry writes into the field-first walk) and the **R316 dimensional
pivot** being filed and signed off to Ready — a planned redraw of the
`carrier × intent × mapping` model into `(context, operation, target)`.

Three items reached a terminal state and **all deleted their files cleanly** — no
new stranded tombstones, and (unlike the prior window) every dependent was also
swept (see observation 1):

- **R305** `collapse-singlerecordtablefield-into-recordtablefield` → Done
  (`eed6566e`, "source-shape mirror now genuinely pinned"). Was In Review at the
  prior audit; the `SingleRecordTableField` class was already deleted then, and
  the item now closed cleanly.
- **R310** `dml-carrier-forbidden-directive-diagnostic` → Done (`6e7d3777`). New
  this prior window (filed Ready); names a forbidden directive on the DML payload
  carrier data field. Build-tooling diagnostic only.
- **R311** `jooq-record-service-input-param` → Done (`268c7b4f`). New (filed Spec);
  binds a jOOQ `TableRecord` `@service` input param (column-axis `@field` +
  `@nodeId` scalar-key decode), broadened in review to `List<TableRecord>` and
  full child-`@service` InputBean parity. **It added a sibling input-carrier type
  `JooqTableRecordInputType`** (`GraphitronType.java:304`) alongside the still-live
  `JooqRecordInputType` (`:342`) — bears on R234 (see §B).

New items filed this window: **R315** (`fk-reference-nodeid-service-record-input`,
Spec — "generalize R311": port legacy `@reference` FK resolution onto jOOQ-record
`@service` params), **R316** (`context-operation-target-pivot`, Ready), **R317**
(`inline-type-classification-into-walk`, Ready — retire `TypeBuilder.buildTypes`).
All carry sane front-matter; R316 `depends-on` resolves to the live
`dimensional-model-pivot` (R222) and R317 to the live
`field-first-classification-driver` (R279). A full dangling-`depends-on` sweep
across all 114 items found **none** (observation 1).

**Net effect on the flag counts:** the count holds at **30 flagged, 85 current**,
but the composition of §C shifted: **R279** is **dropped** (it went In Progress
and its spec was rewritten and re-verified against current code this window — an
actively-maintained, actively-implemented item is by definition not stale), and
**R308** (`service-list-payload-arrival`) is **newly flagged into §C** (its
`depends-on` was cleaned when R305 closed, but its body still names the
now-deleted `SingleRecordTableField` in the present tense). No previously-flagged
item left the board; none changed status except R279.

## Scope and method

All **115** entries were reviewed (114 `R<n>` item files plus the non-item
`inference-axis-coverage.adoc` placeholder). For each item the targets it names
(classes, directives, methods, packages, modules) were located in the current
tree under `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/`,
the described problem was checked for whether it still reproduces, and the
changelog was scanned for the item's `R<n>` and key terms to catch work that
shipped without the item being closed.

Two structural landings continue to dominate the staleness picture and were
re-verified: **R276** (deleted `PlainObjectType` / `PojoResultType.NoBacking` /
`UnbackedPojoResult` — `PojoResultType` permits only `Backed` at
`GraphitronType.java:119-120`; `ResultType` is a 4-arm seal at `:93-95`) and
**R290** (deleted `LeafTupleAdapter`; dissolved `ConstructorField` to a
historical-prose comment). **R305**'s `SingleRecordTableField` collapse (verified
last window) is now fully closed: the class is gone and `ChildField` cases on
`RecordTableField`; the only fresh effect this window is R308's body still naming
the deleted class (§C).

**Result: 30 items flagged, 85 current.** Line numbers cited below are as of the
review date and will themselves drift — see observation 3.

## A. Obsolete — should leave the active roadmap (2)

Each was superseded or discarded by a sibling item already at Done. Because the
closure came from the sibling rather than a self-transition, no author ran the
file-deletion sweep. (Both were stranded across the prior six audits — now seven.)

| Item | Status | Action | Why |
|---|---|---|---|
| **R30** selection-parser-audit | **Done** | **Delete the file** | Status is `Done` (re-confirmed); per workflow (`workflow.adoc:21,74`) Done items are deleted. Re-verified the **sole** stranded Done tombstone in the roadmap (`status: Done` grep returns exactly one file). Nothing `depends-on` it and it carries no README rollup row, so it is not a build risk — purely an unswept tombstone. If the "parser is needed, keep it" finding is worth retaining, capture it in `changelog.md` first, then delete. **Clean delete — unambiguous.** |
| **R146** mutation-cardinality-safety-unique-index | Backlog | **Discard → delete** (via transition) | R246 (Done) changelog: "**Absorbs R146 (PK-or-UK coverage, discarded)**." `JooqCatalog.candidateKeys` (`JooqCatalog.java:527`) feeds `walker/MatchedKeys.java:37` → `UpdateRowsWalker` PK-or-UK matching, shipped and tested. The file's `status: Backlog` is stale; the correct mechanism is a `Backlog → Discarded` transition (which couples with file deletion per `workflow.adoc:23`), not a raw delete of a Backlog file. |

**No change to §A this window.** R201 (re-bucketed here→§B last window) stays §B;
its premise — the output-side `@field(name:)` mirror of R200 — is unchanged and
unbuilt.

## B. Outdated — needs re-spec (premise or targets materially changed) (16)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. All sixteen carry
over from the prior audit and were re-verified; two (R13, R234) drifted further
this window and are updated below.

| Item | Status | Why re-spec |
|---|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Backlog | Premise is live: `PayloadConstructionShape.java` is reached on the still-live `PayloadClass` arm (`resolvePayloadConstructionShape` at `FieldBuilder.java:484`, called `:2221`; the mutable-bean predicate's `javaBeanSetterName` at ~`:596`), and R244 itself preserves that arm. R200 shipped the **input-side** `@field(name:)` axis and explicitly hands "the remaining `@field`-symmetry items" to R201/R202 — so R201 is the wanted **output-side mirror**, not a discard. Re-spec: drop the "R244 moots R201" framing (R244 only covered the migrated `Outcome<T>` paths), re-anchor the construction-shape lines, and frame it as the output payload-construction counterpart of R200. |
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; still worsening.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm R276 deleted — `PojoResultType` permits only `Backed` (`GraphitronType.java:119-120`); `ResultType` is a 4-arm seal (`:93-95`). Its primary migration target `FetcherEmitter.propertyOrRecordValue` **no longer exists** (zero hits); only the `GeneratorUtils.buildFkRowKey` (`:241`) half of the duplication survives. Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. |
| **R13** faceted-search | Spec | **Drifted again this window.** Central seam `ConnectionSynthesis.buildPlan()` was already gone (synthesis moved to `ConnectionPromoter`); now **R279 slice 5 renamed the entry point** `ConnectionPromoter.promote` → **`ConnectionPromoter.synthesiseForField`** (`ConnectionPromoter.java:120`, called per field inside the walk at `GraphitronSchemaBuilder.java:301`; `rebuildAssembledForConnections` retained at `:194`/`:262`). `FieldWrapper.Connection` is a 2-arg record `(boolean connectionNullable, int defaultPageSize)` with no `connectionName` and no facet slot (`FieldWrapper.java:73`). Phases 2–4 are written entirely against the retired pipeline. |
| **R71** recordn-key-parity-lifter | Backlog | **Heavier re-spec.** R110 replaced `@batchKeyLifter` with `@sourceRow` (`BatchKeyLifterDirectiveResolver` → `SourceRowDirectiveResolver`). The `BatchKey` lifter taxonomy the body proposes splitting (`LifterRowKeyed`/`RowKeyed`/`MappedRowKeyed`/`RecordKeyed`) no longer exists as sealed types — they survive only as stale comments. R222 replaced the model with a flat `SourceKey` record carrying orthogonal `Wrap{Row,Record,TableRecord}` (`SourceKey.java:92-102`) and `Reader` slots. Re-anchor on `SourceKey.Wrap` + the `@sourceRow` resolver. **(Note: R316, Ready, plans to collapse `wrap`+`cardinality` out of `SourceKey` — re-spec R71 *after* deciding whether R316 lands first, or it will re-stale immediately.)** |
| **R46** service-multi-tenant-fanout | Backlog | R190 (Done) sealed `GraphitronContext` to its generated `Impl` (`GraphitronContextInterfaceGenerator.java:12-13`) + a `newExecutionInput(...)` factory (`GraphitronFacadeGenerator.java:116`), dissolving the `ContextValueRegistration<FanOut>` design the whole "Design" section rests on (`ContextValueRegistration` has zero `.java` hits). Body still links the dead `typed-context-value-registry.md` slug at lines 17/156 (renamed to `tenant-routing-and-execution-input.md` = R45, Spec). |
| **R19** history-squash | Ready | Every SHA/count is an April-2026 snapshot: metrics table reads "595 (as of 2026-04-24)" and prose targets a "566-commit history" (lines 14/22/24), with frozen `8a8c5efe`/`ac3df0b7` SHAs. Branch is now **2 758 ahead** of `origin/main` (up from 2 714 at the prior audit — ~4.9× the documented 566). All numbers/SHAs/drop-lists need regeneration — or, better, computing dynamically at execution time — before this can execute. |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | Premise says R222 *already* collapsed `JooqRecordInputType` with a specific rejection — but `JooqRecordInputType` still exists (`GraphitronType.java:342`), the quoted rejection string ("…but not a TableRecord…") is absent from the code, and **R222 is still Spec**. New this window: **R311 (Done) added a *sibling* input-carrier `JooqTableRecordInputType`** (`:304`) for the TableRecord case — so the input-carrier hierarchy is now two-armed and *further* from the single-collapsed-arm R234 forward-dates. Rephrase as conditional-on-R222 (gate it behind R222 reaching Done with that collapse shipped), fold into R222, or reconcile against the R311 split. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines"; `TypeFetcherGenerator.java` is now **5 446** (~3.3× the cited figure; +37 this window from R279 slice 6's output-composite prune). Lightest re-spec in this section: refresh the LOC/method-count figures and re-survey the cut against the real file, or stop hard-coding a count. |
| **R263** decode-helper-typename-first-resolution | Backlog | **Leaning discard.** Trap still exists (`resolveDecodeHelperForTable` at `BuildContext.java:2059`, disclaimer `:2145`), but two consecutive consumers (R195, then R312 last window) **sidestepped** it rather than routing through — its callers remain `NodeIdLeafResolver.java:261`, `FieldBuilder.java:1123`, and two BuildContext-internal sites (`:1894`/`:2029`), all pre-R312, unchanged this window. The "trap waiting to bite the next caller" motivation stays weakened. Re-spec only if a caller that *must* route an authoritative `@nodeId(typeName:)` through this path is identified; otherwise **discard as speculative**. |
| **R269** nullable-to-one-record-into-npe | Spec | R268 shipped (helpers take a `sourceExpr`) and R271 (dunder retirement) shipped, renaming `__elt`→`element`/`__k`→`key` — so the spec's `__elt`/`__k` snippets (lines 22-23, 29, 35) are now **triply stale** (helpers now at `GeneratorUtils.java:318`/`:338`), and the "if R268 lands first … if R269 lands first" coordination fork (lines 62-70) is resolved (R268 landed first). The success-arm NPE itself is still unguarded. Strip the snippets and the R268 framing; re-anchor on `element`/`key`. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (`R58TypedRejectionPipelineTest` in tree, R58 Done) — the "R58 is currently *In Review* … if it reverts" framing (lines 21-23) is false. Carriers are still String-flattened (changelog defers `ParsedPath.errorMessage`, `Unresolved.reason`, etc. explicitly to R66), so the body stays valid; remove the stale dependency prose (`depends-on: []` is already correct). |
| **R261** wire-coercion-cast-guard | Backlog | The specific reported instance (`(SakRecord) raw.get(...)`, site A) is handled by shipped R195 (`CallSiteExtraction.NodeIdDecodeRecord`, present across ~8 files). Update the coordination framing (lines ~100-105) to past tense; the raw-cast invariant for sites B–E is still unbuilt (R195's rejection is jOOQ-record-narrow). |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)"; R94 shipped (Done). Blocker cleared — drop the blocked framing from title and body. R94's own Done note hands the real remaining dependency to **R98** ("R170 picks up the live invalid-input round-trip the moment R98 ships its first SDL constraint"), so re-point the dependency there. |
| **R95** routines-as-data-model-citizens | Backlog | Two problems: (1) the claim that `RoutineReflection` "already lives in graphitron-common next to TableReflection" is **false** — it lives only in legacy `graphitron-java-codegen` (`mappings/RoutineReflection.java`); zero hits under `graphitron-common` (asserted twice, lines 38/77). (2) Item **R300** (`jooq-routine-fields`) overlaps R95's scope heavily and `depends-on: [dimensional-model-pivot]` rather than R95, with neither item cross-referencing the other. Reconcile (supersede/merge/precursor + cross-link) and fix the `graphitron-common` claim. |
| **R24** nodeidreferencefield-join-projection-form | Backlog | Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **no longer exists** (zero hits; dispatch is now `FetcherEmitter.bind`/`bindRaw`), and the carrier in the **item title** — `NodeIdReferenceField` — was renamed by R50 to `ColumnReferenceField`/`CompositeColumnReferenceField` (`ChildField`; note the sibling `ParticipantColumnReferenceField`). The surviving "not yet implemented" stub is for `CompositeColumnReferenceField` at `FetcherEmitter.java:499-507` (the `UnsupportedOperationException` "requires JOIN-with-projection emission" at `:504-506`). Work is valid + unbuilt, but title symbol + method names need a full re-anchor. |
| **R121** lsp-diagnostic-redundant-splitquery-on-record | Backlog | Mooted/reframed by the `@record` retirement (R301/R307, Done). Its premise mirrors R3's `@record`-parent build warning, but R307 retired `@record` as a live binding and the build warning's wording drifted from "`@record`-parent field" to "**record-backed** parent field" (`FieldBuilder.java:4445`); sibling redundancy warnings now also exist at `:4133` (R275) and `:4267` (`@sourceRow`). The `TypeContext.hasDirective(typeDef,"record")` predicate the item assumes is no longer the live binding. Re-spec against the current `record-backed`-parent warning (and decide whether to fold in the siblings), or **discard** if the SDL-only LSP can't mirror the reflection-derived determination. |

## C. Outdated — update references only (work valid, refs stale) (12)

Substance intact; only paths, line numbers, or dependency tense drifted. The
recurring root cause is unchanged: `FieldBuilder.java` (now **5 686**) /
`TypeFetcherGenerator.java` (now **5 446**) standing at ~2.6–3.3× the sizes the
older specs cite, plus a `docs/*.md` → `docs/*.adoc` migration. **R308** is newly
flagged; **R279** is dropped (now actively In Progress with a freshly-rewritten
spec — see observation 2).

| Item | Stale reference |
|---|---|
| **R308** service-list-payload-arrival | **NEW flag.** Substance fully intact — the `@service` list-payload N+1 (carrier arrives as a list, the inline-no-loader fetcher fires once per element) is real and unbuilt. But R305 reached Done this window: its `depends-on` was correctly cleaned to `[]`, yet the body still describes the no-DataLoader shape as "`SingleRecordTableField` today, the single-arrival arm of the merged `RecordTableField` after R305" — and `SingleRecordTableField` is now **deleted**. Strip the "today" framing and read it as the single-arrival arm of `RecordTableField`; bump `last-updated` (not advanced when the `depends-on` was edited). |
| **R202** honor-field-directive-in-error-type-source-accessors | Premise live and unbuilt: `checkErrorTypeSourceAccessors` (`FieldBuilder.java:2422`) calls `ClassAccessorResolver.resolve(sourceClass, sdlField.getName(), …)` at `:2441-2446`, passing the raw SDL name with **no `@field` read** — exactly the gap R202 describes. R200's Done note names R202 as a remaining `@field`-symmetry sibling. Only staleness is line drift (body cites `:2281-2316`); re-anchor at the symbol. |
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck as "Obsolete (R287)"; body was re-based onto R305's re-fetch substrate (prior window). `depends-on` was cleaned to `[]` this window when R305 closed. One numeric anchor remains stale: `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` is cited at "`:944`" (3 sites) but is at **`:594`** (`FetcherEmitter` is 726 LOC). Re-anchor at the symbol. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` substance holds, emit-half still unbuilt. Anchors drifted: argument-level `@condition` rejection (`argCondition`) is at **`:408-416`** (body cites ~438-440); the `@condition(override:true)` admission gate (`ARG_OVERRIDE`) is at **`:460-479`** (body cites :482-498). Re-anchor at the symbols. |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`. The two cited sibling docs (`docs-as-index-into-tests.md`, `rewrite-docs-entrypoint.md`) do not exist under any extension — relocate or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (absent) and stale `ConnectionSynthesis` naming. The live seam is now `ConnectionPromoter.synthesiseForField` / `.rebuildAssembledForConnections` (renamed from `.promote` by R279 slice 5 this window) — re-anchor there. |
| **R99** lsp-submodule-sibling-classpath | Path `graphitron-rewrite/graphitron-maven/…` is actually `graphitron-maven-plugin/…`; `getAllProjects()` cited ~`:113` is now `AbstractRewriteMojo.java:208-209`. |
| **R92** catalog-check-constraint-validation | Seam anchors drifted (TypeFetcherGenerator grew): `validatorPreStep` cited `:1326` is now defined **`:1560`** (called `:1453`); `DefaultValidatorHolder` is at `GraphitronContextInterfaceGenerator.java:84`, `getValidator` at `:95-99`. Re-anchor at the symbols. |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue` emission cited at `:1456`/`:1496`/`:1508`/`:1769` is now clustered at `TypeFetcherGenerator.java:1952-2005` (descriptive anchor `:1913`). |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog.reflectTableMethod` cited `:498` is now **`:494`** and the strict `ClassName.equals` return-type gate moved to **`:524-525`**; `buildQueryTableMethodFetcher` cited `:1035` is now in `TypeFetcherGenerator.java`. Re-anchor at the symbols. |
| **R236** validator-reference-candidate-hint-terminal-table | (Anchors are in the **item body**, lines 17/27.) `classifyInputFieldInternal` is at `BuildContext.java:1627`, but the candidate-hint failure-aggregation block (`columnSqlNamesOf(...)`) moved to ~`:1706-1717` (body cites `:1665-1677`). Re-anchor at the symbol. |
| **R35** source-orientation-javadocs | LOC counts grossly stale (FieldBuilder cited "2 172", now **5 686**; TypeFetcherGenerator "1 646", now **5 446**); doc cross-links use `.md` and should be `.adoc`. The "FieldBuilder decomposition shipped under R6" claim stays dubious given the file is now ~2.6× larger. |

**Re-confirmed Current (not flagged):** **R267** nodeid-encoder-deprecated-convert
— the deprecated `.getDataType().convert(...)` is still emitted
(`NodeIdEncoderClassGenerator.java`, `@SuppressWarnings({"deprecation","removal"})`),
file at the correct path with correct symbols. **R315/R316/R317** (new this
window) — specs are fresh, front-matter sane, `depends-on` resolves; not stale.

## Cross-cutting observations

1. **Closure hygiene improved this window — the dependency graph stayed clean.**
   All three items that left the board (R305, R310, R311) self-closed cleanly
   (Done + file deleted), **and** every dependent of the deleted R305 slug had it
   removed from `depends-on` (R308, R314, R242 all swept to drop
   `collapse-singlerecordtablefield-into-recordtablefield`). A full `depends-on`
   sweep across all 114 items found **no dangling slugs**. R30 (self-Done, unswept)
   remains the **only** stranded Done tombstone — alongside R146/R201's
   discard-by-sibling Backlog files — unchanged across **seven** audits. The
   workflow rule that *the closing author deletes the file* still has no owner when
   the closure comes from a sibling. Worth a one-shot cleanup pass and a workflow
   note.
2. **R279 going active is this window's clearest "un-flagging" signal.** Moving
   Ready → In Progress, it shipped slices 1–6 (reachability observatory,
   order-independent participant classification, field-first inversion of the
   classification driver, `DomainReturnType` enforcement to a validator rule,
   connection-synthesis fold, and TypeRegistry single-verb collapse + output-composite
   prune) and its spec was rewritten/refreshed against current code three times
   (`refresh spec assumptions … after R290`, `adapt … to the pivoted R222 carrier
   model`, `sharpen … to the field-first traversal model`). An item under active
   implementation with a freshly-maintained spec is the opposite of stale — so it
   leaves §C. The cost is line-drift it caused elsewhere: slice 5 renamed
   `ConnectionPromoter.promote` → `synthesiseForField` (re-staling R13/R10) and
   slice 6 grew `TypeFetcherGenerator` to 5 446.
3. **Line-drift recurs from ordinary refactors.** `TypeFetcherGenerator` grew
   1 646 → 5 446 (the §C cluster of `R92`/`R103`/`R240` all re-anchored this window),
   `FieldBuilder` is 5 686, and `FetcherEmitter` (726) keeps R242's
   `buildSingleRecordIdFromReturningFetcherValue` at `:594` while the prose still
   cites `:944`. Sharpest evidence yet that specs anchored to **symbol names**
   rather than line numbers would stop the recurrence; that bloat is the structural
   backdrop behind most of §C.
4. **R316 (Ready) is a large pending structural pivot to watch — not yet stale.**
   It plans to redraw the shipped `carrier × intent × mapping` model into
   `(context, operation, target)`, folding `Carrier`/`SourceShape`/`SourceCardinality`
   into one `context` hierarchy and collapsing `wrap`+`cardinality` out of `SourceKey`,
   with an explicit thoroughness constraint ("the old vocabulary must be gone…not
   merely shadowed"). It currently moots nothing — the present model is fully live
   (`SourceKey.Wrap{Row,Record,TableRecord}` at `SourceKey.java:92-102`) and R316 is
   a plan, so items premised on it stay valid. But the moment R316 reaches Done, a
   cluster of model-classification items will need re-checking: **R71** (re-anchors
   on `SourceKey.Wrap`, which R316 removes), **R234** (input-carrier collapse),
   **R52** (`lift-operation-taxonomy`), **R219/R220** (inference/source-shape
   consolidation), and **R314** (which R316 already shrinks to "pure emit
   re-platforming" in its Downstream section). Flagging now would be premature; this
   is a standing watch entry for the next audit.
5. **R222 remains Spec but was amended again** (decomposed `SourceKey` into a
   source-field key; named the source-object vs source-field cardinality
   distinction), and R316 became its declared downstream consumer / the pivot
   itself. The `JooqRecordInputType` collapse R234 forward-dates has **not** landed
   (`GraphitronType.java:342` still live; R311 added the *sibling* `:304`) — R234
   stays §B with a forward-dated premise.
6. **The `@record` retirement (R301/R307) continues to bound a small staleness
   class.** `@record` survives as a *declared-but-ignored* directive; the one item
   building tooling on its *live-binding* behaviour — **R121** — stays mooted.
   `classification-test-dsl-inventory.md` remains stale against R299/R290 and still
   warrants the "superseded — historical" banner the prior audits recommended; it
   has **not** been added (left unedited here per scope).
7. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-06-17._
