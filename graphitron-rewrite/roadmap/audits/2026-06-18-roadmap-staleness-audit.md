# Roadmap staleness audit — 2026-06-18

A point-in-time review of every active roadmap item under
[`graphitron-rewrite/roadmap/`](../) against the **current** state of the
codebase on `claude/graphitron-rewrite` (HEAD `eed9b7e2`, 2026-06-17 21:55). The
goal is to find items whose premise no longer holds: work already shipped,
constructs renamed or removed, dependencies that have since landed, or specs
grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-06-17` staleness audit, which has been deleted —
only the latest audit is retained. Two siblings in this directory are **not**
staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis that is the permanent lineage document for **R316** —
  that item links it directly (`source-operation-target-pivot.md:24`). It is an
  argument record, not a point-in-time staleness review, and is not superseded by
  this audit. (R316 was renamed `context-operation-target-pivot` →
  `source-operation-target-pivot` and reframed `(context, operation, target)` →
  `(source, operation, target)` this window, which only *tightens* the match to
  this doc's title.)
- `classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
  inventory. It remains materially stale against R299/R290 and still warrants the
  "superseded — historical" banner the prior audits recommended; it has **not**
  been added (left unedited here per scope — see observation 6).

## Changes since the 2026-06-17 audit

**48 commits** landed between the prior audit commit (`56f264d3`, committed
2026-06-17 00:14) and this one (HEAD `eed9b7e2`, 2026-06-17 21:55). The dominant
events this window are **R279 closing to Done** (`field-first-classification-driver`
shipped its last slices and was deleted on close), **R317 going active**
(`inline-type-classification-into-walk` moved Ready → In Progress and shipped
slices 1, 2, 3a–3d, absorbing R318/R325), and the **R316 reframe** — the pivot
was renamed `(context, operation, target)` → `(source, operation, target)` and
*reopened* Ready → Spec for re-review of the new shape.

Four items reached a terminal state this window and **all closed cleanly** — no
new stranded tombstones, and every dependent was swept (see observation 1):

- **R279** `field-first-classification-driver` → Done (file deleted). Was In
  Progress at the prior audit (dropped from §C then, precisely because an
  actively-implemented item is not stale); shipped slices 4–6 (`ConnectionPromoter`
  fold, orphan-prune observability, single-verb `TypeRegistry` collapse) and closed.
- **R23** `nestingfield-multiparent-tablefield` → Done (`c38779e`, file deleted).
  Lifted the multi-parent shared-shape gate for the `ChildField.TableField` arm.
  On close it **spawned R323** (`nestingfield-multiparent-batchkey-leaves`, Backlog,
  fresh spec) for the harder BatchKey-leaf arms — a clean carve-out, not a tombstone.
- **R321** `one-shot mojos render ValidationFailedException.errors()` → Done
  (`cbfeb9e6`). Filed Backlog and closed **within this window** (filed `32342d56`,
  Done `cbfeb9e6`); never on the board at audit time, leaves no file.
- **R324** `lift single-cardinality multi-hop @splitQuery restriction` → Done
  (`45bf3cb`). Also filed and closed **within this window**; emitter-only fix in
  `SplitRowsMethodEmitter`, removed the classifier guard. Leaves no file.

Two items were filed and **discarded/absorbed within the window**, leaving no
file: **R325** (`retire the eager type pass`) was folded into R317 and discarded
as a duplicate (`1ac9c751`, `a96537b3`); **R318** (`immutable validate phase`) was
inlined into R317 as its closing slice (`10daf8a3`). Both numbers were retired, not
reused.

New items filed this window that remain on the board: **R319**
(`warn-on-pruned-unreachable-types`, Backlog — surface R279 slice 6's now-silent
reachability prune as a build warning), **R322** (`nodeid-shared-column-agreement`,
Backlog — runtime value-agreement check when multiple `@nodeId` decode onto shared
record columns), **R323** (`nestingfield-multiparent-batchkey-leaves`, Backlog — the
R23 follow-up), and **R326** (`render-mermaid-diagrams-on-docs-site`, Backlog —
render R316's `classDiagram` on the AsciiDoctor site). All four carry sane
front-matter and were created 2026-06-16/17, so none is stale. A full
dangling-`depends-on` sweep across all 116 items found **none** (observation 1).

**Net effect on the flag counts:** the count **holds at 30 flagged, 87 current**,
and the **composition of §A/§B/§C is unchanged**. No previously-flagged item
closed, changed status, or changed bucket this window — every one was re-verified
and still reproduces. The only flag-relevant motion is internal: R316 moving Ready
→ Spec pushes its two forward-dating dependents (**R71**, **R234**) *further* from
resolution, and R317's BuildContext index work (slices 3a–3d) drifted the §C
anchors of **R236** (and the §C-adjacent R202) further — both noted inline. The
"un-flagging" signal this window is **R317** (now In Progress with a freshly
rewritten spec), exactly as **R279** was last window (observation 2).

## Scope and method

All **117** entries were reviewed (116 `R<n>` item files plus the non-item
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
historical-prose comment). **R305**'s `SingleRecordTableField` collapse remains
fully closed (class gone; `ChildField` cases on `RecordTableField`); its only live
residue is R308's body still naming the deleted class (§C).

**Result: 30 items flagged, 87 current.** Line numbers cited below are as of the
review date and will themselves drift — see observation 3.

## A. Obsolete — should leave the active roadmap (2)

Each was superseded or discarded by a sibling item already at Done. Because the
closure came from the sibling rather than a self-transition, no author ran the
file-deletion sweep. (Both were stranded across the prior seven audits — now eight.)

| Item | Status | Action | Why |
|---|---|---|---|
| **R30** selection-parser-audit | **Done** | **Delete the file** | Status is `Done` (re-confirmed); per workflow (`workflow.adoc:21,74`) Done items are deleted. Re-verified the **sole** stranded Done tombstone in the roadmap (`status: Done` grep returns exactly one file). Nothing `depends-on` it and it carries no README rollup row, so it is not a build risk — purely an unswept tombstone. If the "parser is needed, keep it" finding is worth retaining, capture it in `changelog.md` first, then delete. **Clean delete — unambiguous.** |
| **R146** mutation-cardinality-safety-unique-index | Backlog | **Discard → delete** (via transition) | R246 (Done) changelog: "**Absorbs R146 (PK-or-UK coverage, discarded)**." `JooqCatalog.candidateKeys` (`JooqCatalog.java:527`) feeds `walker/MatchedKeys.java:37` → `UpdateRowsWalker` PK-or-UK matching, shipped and tested. The file's `status: Backlog` is stale; the correct mechanism is a `Backlog → Discarded` transition (which couples with file deletion per `workflow.adoc:23`), not a raw delete of a Backlog file. |

**No change to §A this window.** R201 (re-bucketed here→§B two windows ago) stays
§B; its premise — the output-side `@field(name:)` mirror of R200 — is unchanged
and unbuilt.

## B. Outdated — needs re-spec (premise or targets materially changed) (16)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. All sixteen carry
over from the prior audit and were re-verified; two (**R71**, **R234**) drifted
further this window because R316 — the pivot they forward-date against — moved
Ready → Spec, pushing the collapse they assume *out*, not in.

| Item | Status | Why re-spec |
|---|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Backlog | Premise is live: `PayloadConstructionShape` resolution is reached on the still-live `PayloadClass` arm (`resolvePayloadConstructionShape` at `FieldBuilder.java:484`, called `:2235`; the mutable-bean predicate's `javaBeanSetterName` at `:596`), and R244 itself preserves that arm. R200 shipped the **input-side** `@field(name:)` axis and explicitly hands "the remaining `@field`-symmetry items" to R201/R202 — so R201 is the wanted **output-side mirror**, not a discard. Re-spec: drop the "R244 moots R201" framing (R244 only covered the migrated `Outcome<T>` paths), re-anchor the construction-shape lines, and frame it as the output payload-construction counterpart of R200. |
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; still worsening.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm R276 deleted — `PojoResultType` permits only `Backed` (`GraphitronType.java:119-120`); `ResultType` is a 4-arm seal (`:93-95`). Its primary migration target `FetcherEmitter.propertyOrRecordValue` **no longer exists** (zero hits); only the `GeneratorUtils.buildFkRowKey` (`:241`) half of the duplication survives. Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. |
| **R13** faceted-search | Spec | Central seam `ConnectionSynthesis.buildPlan()` is long gone; the live entry point is **`ConnectionPromoter.synthesiseForField`** (`ConnectionPromoter.java:120`, called per field inside the walk; `rebuildAssembledForConnections` at `:194`) — renamed from `.promote` by R279 slice 5 (last window; **unchanged this window**, so the drift has settled, but the spec has not caught up). `FieldWrapper.Connection` is a 2-arg record `(boolean connectionNullable, int defaultPageSize)` with no `connectionName` and no facet slot (`FieldWrapper.java:73`). Phases 2–4 are written entirely against the retired pipeline. |
| **R71** recordn-key-parity-lifter | Backlog | **Heavier re-spec, and now blocked on a moving target.** R110 replaced `@batchKeyLifter` with `@sourceRow` (`BatchKeyLifterDirectiveResolver` → `SourceRowDirectiveResolver`). The `BatchKey` lifter taxonomy the body proposes splitting no longer exists as sealed types — they survive only as stale comments. R222 replaced the model with a flat `SourceKey` record carrying orthogonal `Wrap{Row,Record,TableRecord}` (`SourceKey.java:92-102`) and `Reader` slots. Re-anchor on `SourceKey.Wrap` + the `@sourceRow` resolver. **(Note: R316 — now back at *Spec* after reopening Ready→Spec this window — plans to collapse `wrap`+`cardinality` out of `SourceKey` entirely. R316 moved *further* from landing this window, so a `SourceKey.Wrap` re-anchor is safe for now, but re-spec R71 only after R316's fate is settled or it will re-stale.)** |
| **R46** service-multi-tenant-fanout | Backlog | R190 (Done) sealed `GraphitronContext` to its generated `Impl` (`GraphitronContextInterfaceGenerator.java:12-13`) + a `newExecutionInput(...)` factory (`GraphitronFacadeGenerator.java:116`), dissolving the `ContextValueRegistration<FanOut>` design the whole "Design" section rests on (`ContextValueRegistration` has zero `.java` hits). Body still links the dead `typed-context-value-registry.md` slug at lines 17/156 (renamed to `tenant-routing-and-execution-input.md` = R45, Spec). |
| **R19** history-squash | Ready | Every SHA/count is an April-2026 snapshot: metrics table reads "595 (as of 2026-04-24)" and prose targets a "566-commit history" (lines 14/22/24), with frozen `8a8c5efe`/`ac3df0b7` SHAs. Branch is now **2 807 ahead** of `origin/main` (up from 2 758 at the prior audit — ~5.0× the documented 566). All numbers/SHAs/drop-lists need regeneration — or, better, computing dynamically at execution time — before this can execute. |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | Premise says R222 *already* collapsed `JooqRecordInputType` with a specific rejection — but `JooqRecordInputType` still exists (`GraphitronType.java:342`), the quoted rejection string ("…but not a TableRecord…") is absent from the code, and **R222 is still Spec**. The input-carrier hierarchy is two-armed: `JooqRecordInputType` (`:342`) alongside R311's sibling `JooqTableRecordInputType` (`:357`, both members of the seal at `:304`) — *further* from the single-collapsed-arm R234 forward-dates. New this window: the collapse R234 assumes now also belongs to **R316** (Spec) — which moved Ready → Spec, so the collapse is *further* out, not nearer. Rephrase as conditional-on-R222/R316 (gate it behind that collapse shipping), fold into R222, or reconcile against the R311 split. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines"; `TypeFetcherGenerator.java` is **5 446** (~3.3× the cited figure; unchanged this window). Lightest re-spec in this section: refresh the LOC/method-count figures and re-survey the cut against the real file, or stop hard-coding a count. |
| **R263** decode-helper-typename-first-resolution | Backlog | **Leaning discard.** Trap still exists (`resolveDecodeHelperForTable` at `BuildContext.java`, with disclaimer), but two consecutive consumers (R195, then R312) **sidestepped** it rather than routing through. Its callers remain pre-R312 and unchanged this window. The "trap waiting to bite the next caller" motivation stays weakened. Re-spec only if a caller that *must* route an authoritative `@nodeId(typeName:)` through this path is identified; otherwise **discard as speculative**. |
| **R269** nullable-to-one-record-into-npe | Spec | R268 shipped (helpers take a `sourceExpr`) and R271 (dunder retirement) shipped, renaming `__elt`→`element`/`__k`→`key` — so the spec's `__elt`/`__k` snippets (lines 22-23, 29, 35) are now **triply stale** (helpers now at `GeneratorUtils.java:318`/`:338`), and the "if R268 lands first … if R269 lands first" coordination fork (lines 62-70) is resolved (R268 landed first). The success-arm NPE itself is still unguarded. Strip the snippets and the R268 framing; re-anchor on `element`/`key`. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (`R58TypedRejectionPipelineTest` in tree, R58 Done) — the "R58 is currently *In Review* … if it reverts" framing (lines 21-23) is false. Carriers are still String-flattened (changelog defers `ParsedPath.errorMessage`, `Unresolved.reason`, etc. explicitly to R66), so the body stays valid; remove the stale dependency prose (`depends-on: []` is already correct). |
| **R261** wire-coercion-cast-guard | Backlog | The specific reported instance (`(SakRecord) raw.get(...)`, site A) is handled by shipped R195 (`CallSiteExtraction.NodeIdDecodeRecord`, present across ~8 files). Update the coordination framing (lines ~100-105) to past tense; the raw-cast invariant for sites B–E is still unbuilt (R195's rejection is jOOQ-record-narrow). |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)"; R94 shipped (Done). Blocker cleared — drop the blocked framing from title and body. R94's own Done note hands the real remaining dependency to **R98** ("R170 picks up the live invalid-input round-trip the moment R98 ships its first SDL constraint"), so re-point the dependency there. |
| **R95** routines-as-data-model-citizens | Backlog | Two problems: (1) the claim that `RoutineReflection` "already lives in graphitron-common next to TableReflection" is **false** — it lives only in legacy `graphitron-java-codegen` (`mappings/RoutineReflection.java`); zero hits under `graphitron-common` (asserted twice, lines 38/77). (2) Item **R300** (`jooq-routine-fields`) overlaps R95's scope heavily and `depends-on: [dimensional-model-pivot]` rather than R95, with neither item cross-referencing the other. Reconcile (supersede/merge/precursor + cross-link) and fix the `graphitron-common` claim. |
| **R24** nodeidreferencefield-join-projection-form | Backlog | Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **no longer exists** (zero hits; dispatch is now `FetcherEmitter.bind`/`bindRaw`), and the carrier in the **item title** — `NodeIdReferenceField` — was renamed by R50 to `ColumnReferenceField`/`CompositeColumnReferenceField` (`ChildField`; note the sibling `ParticipantColumnReferenceField`). The surviving "not yet implemented" stub is for `CompositeColumnReferenceField` at `FetcherEmitter.java:499-507` (the `UnsupportedOperationException` "requires JOIN-with-projection emission" at `:506`; unchanged this window). Work is valid + unbuilt, but title symbol + method names need a full re-anchor. |
| **R121** lsp-diagnostic-redundant-splitquery-on-record | Backlog | Mooted/reframed by the `@record` retirement (R301/R307, Done). Its premise mirrors R3's `@record`-parent build warning, but R307 retired `@record` as a live binding and the build warning's wording drifted from "`@record`-parent field" to "**record-backed** parent field" (`FieldBuilder.java:4445`); sibling redundancy warnings now also exist (R275, `@sourceRow`). The `TypeContext.hasDirective(typeDef,"record")` predicate the item assumes is no longer the live binding. Re-spec against the current `record-backed`-parent warning (and decide whether to fold in the siblings), or **discard** if the SDL-only LSP can't mirror the reflection-derived determination. |

## C. Outdated — update references only (work valid, refs stale) (12)

Substance intact; only paths, line numbers, or dependency tense drifted. The
recurring root cause is unchanged: `FieldBuilder.java` (now **5 781**, +95 this
window) / `TypeFetcherGenerator.java` (**5 446**) / `BuildContext.java` (**2 254**,
moved by R317's index work) standing at ~2.6–3.5× the sizes the older specs cite,
plus a `docs/*.md` → `docs/*.adoc` migration. **R236** and **R202** drifted
further this window (R317 slices 3a–3d reshuffled `BuildContext`/`FieldBuilder`).

| Item | Stale reference |
|---|---|
| **R308** service-list-payload-arrival | Substance fully intact — the `@service` list-payload N+1 (carrier arrives as a list, the inline-no-loader fetcher fires once per element) is real and unbuilt. But its body still describes the no-DataLoader shape as "`SingleRecordTableField` today, the single-arrival arm of the merged `RecordTableField` after R305" — and `SingleRecordTableField` was **deleted** by R305 (Done). Strip the "today" framing and read it as the single-arrival arm of `RecordTableField`; bump `last-updated`. (`depends-on` already cleaned to `[]` when R305 closed.) |
| **R202** honor-field-directive-in-error-type-source-accessors | Premise live and unbuilt: `checkErrorTypeSourceAccessors` (`FieldBuilder.java:2436`, called `:2214`) calls `ClassAccessorResolver.resolve(sourceClass, sdlField.getName(), …)` passing the raw SDL name with **no `@field` read** — exactly the gap R202 describes. R200's Done note names R202 as a remaining `@field`-symmetry sibling. Only staleness is line drift (drifted again this window with FieldBuilder's +95); re-anchor at the symbol. |
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck as "Obsolete (R287)"; body re-based onto R305's re-fetch substrate; `depends-on` cleaned to `[]`. One numeric anchor remains stale: `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` is cited at "`:944`" (3 sites) but is at **`:594`** (`FetcherEmitter` is 726 LOC; unchanged this window). Re-anchor at the symbol. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` substance holds, emit-half still unbuilt. Anchors drifted: argument-level `@condition` rejection (`argCondition`) is at **`:408-416`** (body cites ~438-440); the `@condition(override:true)` admission gate (`ARG_OVERRIDE`) is at **`:479`** (body cites :482-498). Re-anchor at the symbols. |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`. The two cited sibling docs (`docs-as-index-into-tests.md`, `rewrite-docs-entrypoint.md`) do not exist under any extension — relocate or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (absent) and stale `ConnectionSynthesis` naming. The live seam is now `ConnectionPromoter.synthesiseForField` / `.rebuildAssembledForConnections` (renamed from `.promote` by R279 slice 5; both at `ConnectionPromoter.java:120`/`:194`, unchanged this window) — re-anchor there. |
| **R99** lsp-submodule-sibling-classpath | Path `graphitron-rewrite/graphitron-maven/…` is actually `graphitron-maven-plugin/…`; `getAllProjects()` cited ~`:113` is now `AbstractRewriteMojo.java:208-209`. |
| **R92** catalog-check-constraint-validation | Seam anchors drifted (TypeFetcherGenerator grew): `validatorPreStep` cited `:1326` is now defined **`:1560`** (called `:1453`); `DefaultValidatorHolder` is at `GraphitronContextInterfaceGenerator.java:84`, `getValidator` at `:95-99`. Re-anchor at the symbols. (Unchanged this window.) |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue` emission cited at `:1456`/`:1496`/`:1508`/`:1769` is now clustered at `TypeFetcherGenerator.java:1952-2005` (descriptive anchor `:1913`). |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog.reflectTableMethod` cited `:498` is now **`:494`** and the strict `ClassName.equals` return-type gate moved to **`:524-525`**; `buildQueryTableMethodFetcher` cited `:1035` is now in `TypeFetcherGenerator.java`. Re-anchor at the symbols. |
| **R236** validator-reference-candidate-hint-terminal-table | (Anchors are in the **item body**, lines 17/27.) Drifted again this window from R317's `BuildContext` index work: `classifyInputFieldInternal` is now at `BuildContext.java:1726` (body cites `:1665-1677` / R-prior cited `:1627`), and the candidate-hint failure-aggregation (`columnSqlNamesOf(...)`) is at `:1813`. Re-anchor at the symbol. |
| **R35** source-orientation-javadocs | LOC counts grossly stale (FieldBuilder cited "2 172", now **5 781**; TypeFetcherGenerator "1 646", now **5 446**); doc cross-links use `.md` and should be `.adoc`. The "FieldBuilder decomposition shipped under R6" claim stays dubious given the file is now ~2.7× larger. |

**Re-confirmed Current (not flagged):** **R267** nodeid-encoder-deprecated-convert
— the deprecated `.getDataType().convert(...)` is still emitted
(`NodeIdEncoderClassGenerator.java`, `@SuppressWarnings({"deprecation","removal"})`),
file at the correct path with correct symbols. **R315/R316/R317** — specs are
freshly maintained this window (R315 revised through Spec→Ready review then back;
R316 reframed; R317 under active implementation), front-matter sane, `depends-on`
resolves; not stale. **R319/R322/R323/R326** (new this window) — fresh specs,
sane front-matter; not stale.

## Cross-cutting observations

1. **Closure hygiene stayed clean — the dependency graph held.** All four items
   that left the board (R279, R23, R321, R324) self-closed cleanly (Done + file
   deleted, or filed-and-closed within the window), R325/R318 were folded/discarded
   cleanly, and R23's carve-out (R323) landed as a fresh Backlog item rather than a
   stranded reference. A full `depends-on` sweep across all 116 items found **no
   dangling slugs**. R30 (self-Done, unswept) remains the **only** stranded Done
   tombstone — alongside R146/R201's discard-by-sibling Backlog files — unchanged
   across **eight** audits. The workflow rule that *the closing author deletes the
   file* still has no owner when the closure comes from a sibling. Worth a one-shot
   cleanup pass and a workflow note.
2. **R317 going active is this window's clearest "un-flagging" signal — the exact
   shape R279 had last window.** Moving Ready → In Progress, it shipped slices 1, 2,
   and 3a–3d (interface/union participant enrichment folded onto the node visit;
   multiple `@node` types per table with use-site encoder disambiguation;
   NodeType/participant reverse-lookups and table/node/error membership lifted to
   fixed-point indices; `NestingType` registration and carrier promotion folded onto
   the producing edge; the orphan verdict made registry-free) and absorbed R318 +
   R325, and its spec was rewritten/refreshed against current code several times
   (rescope to single edge-driven pass + immutable validation; pin the slice-5
   diagnostic-channel design; scrub the reverted one-NodeType-per-table guard). An
   item under active implementation with a freshly-maintained spec is the opposite
   of stale — so it is not flagged. The cost is line-drift it caused elsewhere:
   slices 3a–3d reshuffled `BuildContext` (re-staling R236) and grew `FieldBuilder`
   to 5 781 (re-staling R202).
3. **Line-drift recurs from ordinary refactors.** `FieldBuilder` is **5 781** (+95
   this window), `TypeFetcherGenerator` **5 446**, `BuildContext` **2 254** (R236's
   `classifyInputFieldInternal` moved `:1627`→`:1726`), and `FetcherEmitter` (726)
   keeps R242's `buildSingleRecordIdFromReturningFetcherValue` at `:594` while the
   prose still cites `:944`. Sharpest evidence yet that specs anchored to **symbol
   names** rather than line numbers would stop the recurrence; that bloat is the
   structural backdrop behind most of §C.
4. **R316 (Spec) is a large pending structural pivot to watch — reframed this
   window, not yet stale.** It was renamed `(context, operation, target)` →
   `(source, operation, target)` and *reopened* Ready → Spec for re-review of the
   new shape (source/target modelled as `wrapper(shape)` endpoints; `operation` a
   sealed interface with `Paginate` split from `Fetch`; a model-at-a-glance Mermaid
   diagram added, which in turn spawned R326). It plans to fold
   `Carrier`/`SourceShape`/`SourceCardinality` into one `source`/`target` hierarchy
   and collapse `wrap`+`cardinality` out of `SourceKey`, with an explicit
   thoroughness constraint ("the old vocabulary must be gone…not merely shadowed").
   It currently moots nothing — the present model is fully live
   (`SourceKey.Wrap{Row,Record,TableRecord}` at `SourceKey.java:92-102`) and R316 is
   a plan that moved *further* from landing this window. But the moment R316 reaches
   Done, a cluster will need re-checking: **R71** (re-anchors on `SourceKey.Wrap`,
   which R316 removes), **R234** (input-carrier collapse), **R52**
   (`lift-operation-taxonomy`), **R219/R220** (inference/source-shape consolidation),
   and **R314** (which R316 already shrinks to "pure emit re-platforming"). Flagging
   those now would be premature; this is a standing watch entry for the next audit.
5. **R222 remains Spec** and is R316's declared upstream (`depends-on:
   [dimensional-model-pivot]`). The `JooqRecordInputType` collapse R234 forward-dates
   has **not** landed (`GraphitronType.java:342` still live; R311's sibling `:357`
   still present) — R234 stays §B with a forward-dated premise that now spans both
   R222 and R316.
6. **The `@record` retirement (R301/R307) continues to bound a small staleness
   class.** `@record` survives as a *declared-but-ignored* directive; the one item
   building tooling on its *live-binding* behaviour — **R121** — stays mooted.
   `classification-test-dsl-inventory.md` remains stale against R299/R290 and still
   warrants the "superseded — historical" banner the prior audits recommended; it
   has **not** been added (left unedited here per scope).
7. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-06-18._
