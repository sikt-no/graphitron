# Roadmap staleness audit: 2026-07-06

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `87e4300`, 2026-07-05). The goal is to find items whose premise no longer
holds: work already shipped, constructs renamed or removed, dependencies that
have since landed, or specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-03` staleness audit, which has been deleted;
only the latest staleness audit is retained. Four siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  meant to be edited in place as scope iterates. It now reads further behind: on
  top of the closures the prior audit noted, **R410, R424, R425 and R426** all
  reached Done this window, so its MUST/SHOULD tables lag further. Refreshing it
  is out of scope for this staleness pass.
- `2026-07-04-r222-r333-conformance-analysis.md` is the R222/R333 conformance
  analysis (`6131f99`), a companion to this window's R314/R333 design session. It
  is a targeted implementation-vs-spec conformance record, not a point-in-time
  staleness review; left in place.
- `classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
  inventory, doubly stale (against R299/R290 and the R316 corpus recut). It still
  warrants a "superseded, historical" banner; that banner has **not** been added,
  left unedited here per scope (see observation 6).

## Changes since the 2026-07-03 audit

This was **not** a quiet window. The dominant activity was a cluster of
**architecture/design work**, not localized bug fixes: a multi-tenant re-spec
(R45/R46), a structural design session that re-specced R314 and filed the
long-watched `SourceKey` decomposition as real items (R431/R432), a
connection/transaction lifecycle spec (R429) and the MCP in-process query tool
that rides it (R428), and a two-item principles-doc restructure (R433/R434).
Crucially, **none of it touched the three large generator files**, so unlike
every prior window there is **zero line-anchor drift** in §C (see observation 2).

**Terminal closures this window (Done, all self-deleted their files, none flagged,
all were current):** **R410** (`dev-incremental-compile`, was **In Progress** at the
prior snapshot; the six-slice `graphitron:dev` in-process incremental compile engine
now landed under a new `rewrite/compile/` package), **R424**
(`inline-field-args-from-selectedfield`, was **In Review**), **R425**
(`service-splitquery-key-columns-in-parent-projection`, was **Ready**), and **R426**
(`service-tablerecord-partial-record-nonkey-reads`, was **Spec**). Four closures
total; none was under flag, and each was the top of `changelog.md` at close.

**New items on the board (all filed this window, all current/fresh):** **R428**
`mcp-execute-query-in-process` (**Spec**, `depends-on: R429`), **R429**
`connection-transaction-lifecycle` (**Ready**, `depends-on: []`; graphitron owns
connection/transaction lifecycle, the shared seam R45/R46/R428 all now build on),
**R430** `lsp-compile-diagnostics-publish` (Backlog), **R431** `decompose-sourcekey`
(Backlog; **the eager, mechanical `SourceKey` decomposition the prior three audits only
"watched," now a filed, sequenced item**), **R432** `collapse-split-and-record-table-leaves`
(Backlog, `depends-on: R431`; the R333 beachhead collapsing `SplitTableField` +
`RecordTableField` into one source-gated leaf), **R433** `principles-doc-altitude-trim`
(**In Review**), **R434** `principles-doc-axiom-restructure` (**Spec**,
`depends-on: R433`), and **R435** `routine-table-node-composition` (Backlog, the R333
routine-SDL-surface residue). All re-verified spec-forward; every named anchor
resolves; no dangling `depends-on`.

**Transitions this window (the substance of this pass):**

- **R314** (`dissolve-reentry-leaves-dimensional-emit`) advanced **Backlog → Spec**,
  fully re-specced 2026-07-04 onto R333's vocabulary (the coordinate's facts + the
  named-seam method graph) and away from the `carrier`/`intent`/`mapping` types R316
  deleted. Its `depends-on` is now `[coordinate-lowers-to-datafetcher-queryparts,
  decompose-sourcekey, collapse-split-and-record-table-leaves]` (R333/R431/R432).
  **This clears its §B flag.**
- **R46** (`service-multi-tenant-fanout`) was re-specced in place (three commits). Its
  body now explicitly retires the dead `ContextValueRegistration<FanOut>` /
  `DslContextPerElement` / `getContextFanOut` design (recorded as superseded in git
  history) and re-targets onto R45's `TenantBinding` axis + R429's connection substrate;
  `depends-on: [tenant-routing-and-execution-input, connection-transaction-lifecycle]`,
  both live. **This clears its §B flag.**
- **R45** (`tenant-routing-and-execution-input`) moved **Ready → Spec**, reworked to
  divine the tenant from operation input via tenant-column bindings and re-target
  routing onto R429's DataSource seam (`depends-on: [connection-transaction-lifecycle]`).
  A deliberate rework, not staleness; current.
- **R433** ran Backlog → Spec → Ready → In Progress → **In Review**. **R347**
  (`lsp-structural-consolidation`) holds at **In Progress**. **R222** and **R333** took
  edits folding the design-session findings; both remain **Spec** and current.

**Board accounting.** **130 item files** today. (The board was **126** at the audit's
commit `70484cd`; the prior audit stated 125 as of its earlier review HEAD `1fae7e9`,
before R426 was filed. 8 filed + 4 closed this window → 130.) Status distribution:
**108 Backlog, 17 Spec, 3 Ready, 1 In Progress (R347), 1 In Review (R433); zero Done**
(tombstone-free for the fourth window running). A non-recursive `^status: Done` grep over
`roadmap/*.md` returns nothing. No duplicate `id:`; `next-id: R436` with max allocated id
R435. A `depends-on` sweep over all 130 files found **no dangling slugs**. The board is
structurally clean.

**Net effect on flag counts: 20 flagged, 110 current** (prior window: 22/103). Flag
composition moved **(1 / 8 / 13) → (1 / 6 / 13)**: §A is R263 alone; §B **dropped from
eight to six** (R314 and R46 both re-specced out this window); §C holds its thirteen
verbatim. Every closure and every new item this window landed on current/fresh code; none
under flag. Every remaining flagged item was re-verified against current HEAD and **every
one still reproduces**.

## Scope and method

All **130** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded). For each item the
targets it names (classes, directives, methods, packages) were located in the current
tree under `graphitron/src/main/java/no/sikt/graphitron/rewrite/`, the described problem
was checked for whether it still reproduces, and the changelog was scanned for the item's
`R<n>` and key terms to catch work that shipped without the item being closed.

The structural landings the prior audits relied on all still hold: **R276**
(`ResultType` is a 4-arm seal; `PojoResultType` permits only `Backed`; the
`TypeBackingShape.NoBacking` sub-taxonomy that survives is a *different* type, not the
deleted `ResultType.NoBacking` R180 cites), **R290**, **R305** (`SingleRecordTableField`
gone; live carrier `RecordTableField` in `model/ChildField.java`), and **R316** (the
`carrier × intent × mapping` field model gone, replaced by `(source, operation, target)`).

**The `SourceKey` decomposition still has not landed** and was re-confirmed verbatim:
`SourceKey.java:81-82` still carries `Wrap wrap` + `Cardinality cardinality` as record
components, and the compact constructor still pins `SourceRowsCall → Wrap.Row`
(`:124-126`). SourceKey.java took a **+6-line** non-structural edit this window (comment/doc
region), leaving the decomposition target untouched. The material change is that this
decomposition is **now a filed, sequenced item (R431)** with R432 depending on it and R314
re-specced to depend on both, rather than only a standing watch (see observation 3).

**Line-anchor drift this window: none.** The three large generator files are **byte-identical**
to the prior audit: **`TypeFetcherGenerator.java` 6 588**, **`FieldBuilder.java` 6 363**,
**`BuildContext.java` 2 669** (all unchanged). They are not in the `70484cd..HEAD` diff at
all. Spot-verification confirmed every §C/§B/§A code anchor sits exactly where the
2026-07-03 audit placed it: `resolvePayloadConstructionShape` `FieldBuilder.java:494`,
`tryMutableBean` `:559`, `checkServiceReturnMatchesPayload` `:3038`,
`checkErrorTypeSourceAccessors` `:2780`, `validatorPreStep` `TypeFetcherGenerator.java:1984`,
`classifyInputFieldInternal` `BuildContext.java:2032` / candidate-hint `:2121`,
`resolveDecodeHelperForTable` `:2430`. The other §C-anchored files (`FetcherEmitter.java`,
`MutationInputResolver.java`, `GeneratorUtils.java`, `ConnectionPromoter.java`,
`ServiceCatalog.java`, `InputFieldResolution.java`) are likewise all unchanged. **Every §C
row therefore carries over with its prior anchors intact and needs no re-anchoring.**

**Result: 20 items flagged, 110 current.**

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Action | Why |
|---|---|---|---|
| **R263** decode-helper-typename-first-resolution | Backlog | **Discard as speculative** (re-open only if a MUST-route `@nodeId(typeName:)` caller appears) | Unchanged from the prior audit, re-verified verbatim on current HEAD. R377 settled the open question the discard hedged on. `resolveDecodeHelperForTable` is defined `BuildContext.java:2430` (held exactly); it resolves through the `@node` `NodeIndex`, returns `null` on multi-node ambiguity (a validate-time rejection, not the silent `decode<firstType>` the finding described), and falls back to the `typeId` suffix only when no `@node` backs the table. Its callers (`BuildContext.java:2242`/`:2380`, both held) all pass the table name as primary; **none** routes an authoritative `@nodeId(typeName:)`. The premise survives only in spirit and no consumer needs it. Re-anchor onto the R377 body if capturing the finding in `changelog.md`, then discard. |

## B. Outdated: needs re-spec (premise or targets materially changed) (6)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. All six carried over
from the prior audit and were re-verified this window; **every one still reproduces**,
with anchors intact (zero drift). **Two prior §B rows are gone this window: R314 was
re-specced onto R333's vocabulary and R46 onto the R45/R429 tenant substrate; both are now
current** (see the transitions list and §"Re-confirmed current").

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; still the sharpest.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm R276 deleted: `ResultType` is a 4-arm seal, `PojoResultType` permits only `Backed`. Its primary migration target `FetcherEmitter.propertyOrRecordValue` **still does not exist** (zero hits; the spec's own `:802`/`:131` anchors are dead); only the `GeneratorUtils.buildFkRowKey` half of the duplication survives (`generators/GeneratorUtils.java:241`, called `:212`; both held exactly). The `.md` still describes the deleted five-arm / `PojoResultType.NoBacking` model throughout. Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | **Symbol re-anchor; substance confirmed still future.** Every symbol the mechanism names is dead: no `BatchKeyLifterDirectiveResolver`, no `BatchKey`/`LifterRowKeyed`/`RowKeyed` types anywhere. The live surface is `model/LifterRef.java`, routed through `@sourceRow` and `SourceKey.Reader.SourceRowsCall(LifterRef)`. `SourceKey` still carries `wrap`+`cardinality` and the compact constructor still pins `SourceRowsCall → Wrap.Row`, the exact Row-only asymmetry R71 wants to remove. Re-anchor on `LifterRef`/`SourceRowsCall`/`Wrap`. **New this window:** the decomposition R71 rides is now filed as **R431**; wire R71's `depends-on`/prose to R431 on its next touch. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (Done), so the "R58 is currently *In Review* … if it reverts" framing (line 21) is false and should be removed. Carriers are still String-flattened (`ParsedPath(…, String errorMessage, …)` at `BuildContext.java:990` and `InputFieldResolution.Unresolved(…, String reason, …)` in `InputFieldResolution.java` both remain), so the carrier-audit body stays valid; `depends-on: []` correct. Lightest re-spec in this section: strike the reversion hedge. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)" (front-matter `title:` and README); **R94 shipped** (Done, slug `emit-input-records`, file absent). Blocker cleared, drop the blocked framing. R94's Done note handed the real remaining dependency to **R98** (`multi-source-input-validation`, re-confirmed Backlog, file present), so re-point the dependency there and change the title "(R94-blocked)" to "(R98-blocked)". |
| **R24** nodeidreferencefield-join-projection-form | Backlog | Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **still does not exist** (dispatch is now `FetcherEmitter.bind`/`bindRaw`; `dataFetcherValue` survives only in stale Javadoc; the body's "lines 140-162" are dead), and the carrier in the **item title**, `NodeIdReferenceField`, was renamed by R50 to `Column`/`CompositeColumnReferenceField` (zero `NodeIdReferenceField` hits). The surviving "not yet implemented" stub is `ChildField.CompositeColumnReferenceField` and it is the **single** entry in `TypeFetcherGenerator.STUBBED_VARIANTS` (declared `:300`, mapping `:311`), whose `planSlug` is R24's own slug, confirming the release-planning "one tracked stub" claim. Work is valid and unbuilt, but the title symbol + method names need a full re-anchor. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines" and "~30 private methods"; `TypeFetcherGenerator.java` is **6 588** (~4.0×). Lightest-substance re-spec in this section: refresh the LOC/method-count figures and re-survey the cut against the real file, or better, stop hard-coding a count and state "measure at execution time." |

## C. Outdated: update references only (work valid, refs stale) (13)

Substance intact; only paths, line numbers, or dependency tense drifted. **This window is
the exception: because the three large generator files did not change, every anchor below
carries over from the 2026-07-03 audit verbatim.** The recurring code-side root cause is
unchanged: `TypeFetcherGenerator.java` (**6 588**) / `FieldBuilder.java` (**6 363**) /
`BuildContext.java` (**2 669**) standing at ~2.6 to 4.0× the sizes the older specs cite.

| Item | Stale reference |
|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Premise live and unbuilt: `resolvePayloadConstructionShape` on the `PayloadClass` arm (`FieldBuilder.java:494`; called `:2576`); the mutable-bean logic in `tryMutableBean` (`:559`), whose setter-match arm builds `SetterBinding`s off the raw SDL field name with **no `@field` read**. The emitter halves are `TypeFetcherGenerator.payloadFactoryLambda` (`:6485`) and `payloadFactoryLambdaSetters` (`:6502`). Body already frames it as "the output-side mirror of R200"; anchors held exactly this window. |
| **R308** service-list-payload-arrival | The `@service` list-payload N+1 is real and unbuilt (`checkServiceReturnMatchesPayload` `FieldBuilder.java:3038`, called `:2999`; still admits `List<Payload>`). The body still describes the no-DataLoader shape via `SingleRecordTableField`, which R305 **deleted**; the live carrier `RecordTableField` is in `model/ChildField.java`. Strip the "today" framing; bump `last-updated`. The `@service`-child cluster that closed this window (R424/R425/R426, all Done) was adjacent but distinct and did **not** subsume the list-payload N+1 R308 targets, so R308 stands. (`depends-on` already `[]`.) |
| **R202** honor-field-directive-in-error-type-source-accessors | Premise live and unbuilt: `checkErrorTypeSourceAccessors` (`FieldBuilder.java:2780`, called `:2555`) calls `ClassAccessorResolver.resolve(...)` with the raw SDL name and **no `@field` read**, exactly the gap R202 describes. R200's Done note names R202 as a remaining `@field`-symmetry sibling. Re-anchor at the symbol. |
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck as "Obsolete (R287)"; body re-based onto R305's re-fetch substrate; `depends-on` `[]`. Numeric anchors stale: `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` and the `[ID]`-reject scoped to `CarrierFamily.DML` in `BuildContext.java` still need re-anchoring at the symbol (`FetcherEmitter.java` unchanged this window); drop the stale "3 sites" count. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` (**679**, unchanged this window) substance holds (emit-half still unbuilt). The non-`@table` arg rejection (`foundTia.argCondition().isPresent()`) and the `@condition(override:true)` admission gate (`ARG_OVERRIDE`) hold at their prior lines. Body cites older ranges; re-anchor at the symbols (drift is minimal here). |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`, under the **Diataxis `docs/architecture/` tree** R182 introduced, not the old flat `docs/` dir. The two cited sibling docs (`docs-as-index-into-tests.md`, `rewrite-docs-entrypoint.md`) do not exist under any extension or location. Relocate onto the Diataxis layout or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (absent) and stale `ConnectionSynthesis` naming. The live seam is `ConnectionPromoter.synthesiseForField` / `.rebuildAssembledForConnections` (`ConnectionPromoter.java:122`/`:196`, file unchanged this window); re-anchor there and fix the dangling doc link. |
| **R92** catalog-check-constraint-validation | `validatorPreStep` is defined `TypeFetcherGenerator.java:1984` (seam call site `:1852`); `DefaultValidatorHolder`/`getValidator` live in `generators/util/`. No `CheckRecognizer`/`findCheckConstraints` exists; the R12 §5 validator seam this builds on is present as described. Anchors held exactly this window. |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue` emission is clustered in `buildPerCellValueList` (`TypeFetcherGenerator.java:2353`; sibling `buildPerCellValueListDeduped` `:2526`). The contract-side gap (no SDL default lift) is unbuilt. R413 (Done a prior window) bound parent-input VALUES cells through the column Converter DataType in this same cluster but did **not** address the SDL-default lift R103 targets. |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog.reflectTableMethod` has two overloads; the strict `ClassName.equals` return-type gate follows; `buildQueryTableMethodFetcher` at `TypeFetcherGenerator.java:1439` (called `:462`). Neither `MethodRef.StaticOnly` nor `ReturnTypeRef.TableBoundReturnType` carries a type token yet. `ServiceCatalog.java` unchanged this window; re-anchor at the symbols. |
| **R236** validator-reference-candidate-hint-terminal-table | (Anchors are in the **item body**.) `classifyInputFieldInternal` (`BuildContext.java:2032`) and the candidate-hint failure-aggregation (`candidateHint(c, catalog.columnSqlNamesOf(resolvedTable.tableName()))` at `:2121`) are in `BuildContext.java`. The hint still draws from the path-origin `resolvedTable.tableName()`, not the `@reference` terminal table (R380's Done note still lists R236 as an open sibling). Anchors held exactly this window. |
| **R234** jooq-embedded-and-udt-input-backings | Valid, dormant Backlog stub gated behind the R222 collapse (Stages 5–7). Anchors to keep fresh: `JooqRecordInputType` is a live populated input arm (`GraphitronType.java`); `JooqTableRecordInputType` is a separate sibling; the proposed `BackingClass` family is still unbuilt (no `BackingClass.java`; `parentBackingClass` exists only as a component on `AccessorRef.java`). `depends-on: []` correct (R222 is a prose gate, not a hard edge). **Watch:** R431/R432 sit on the same `SourceKey`/leaf surface; re-check R234 when they land. |
| **R35** source-orientation-javadocs | LOC counts grossly stale (FieldBuilder cited "2 172", now **6 363**; TypeFetcherGenerator "1 646", now **6 588**); no `package-info.java` exists in the rewrite tree (sweep undone, re-confirmed). The architecture docs are the Diataxis `docs/architecture/` tree (`.adoc`), and `selection-parser-audit` no longer exists (R30 swept). Refresh the figures and re-point the doc links onto the Diataxis layout. |

**Re-confirmed Current (not flagged):** **R314** (`dissolve-reentry-leaves-dimensional-emit`,
**Spec**), re-specced this window onto R333's vocabulary; body now names
`(source, operation, target)` and `depends-on` R333/R431/R432. **R46**
(`service-multi-tenant-fanout`, **Backlog**), re-specced onto R45's `TenantBinding` axis +
R429; the dead `ContextValueRegistration` design is explicitly retired. **R45**
(`tenant-routing-and-execution-input`, **Spec**), the operation-divined tenant-column
routing reworked onto R429's DataSource seam. All eight new items — **R428** (Spec),
**R429** (Ready), **R430** (Backlog), **R431** (Backlog), **R432** (Backlog), **R433** (In
Review), **R434** (Spec), **R435** (Backlog) — re-verified spec-forward on current code, none
under flag. **R13** (`faceted-search`, Ready) on the live `ConnectionPromoter` seam; **R222**
(`dimensional-model-pivot`, Spec) stays the umbrella (`depends-on: []`, Stages 5–7
outstanding); **R333** (`coordinate-lowers-to-datafetcher-queryparts`, Spec); **R273**,
**R346**, **R360**, **R347** (In Progress) all re-verified.

## Cross-cutting observations

1. **A design/architecture window, not a bug-fix one.** The activity was a multi-tenant
   re-spec (R45/R46), a structural design session (R314 re-spec + R431/R432 filed + R222/R333
   edits), a connection-lifecycle spec and its MCP consumer (R429/R428), the R410 incremental
   compile engine landing, and a principles-doc restructure (R433/R434). Net flag count
   **dropped 22 → 20** on a board that grew 126 → 130. Zero Done tombstones for the fourth
   window running.
2. **Zero line-anchor drift.** For the first time in this audit series, the three large
   generator files did not change (`TypeFetcherGenerator.java` 6 588, `FieldBuilder.java`
   6 363, `BuildContext.java` 2 669, all byte-identical; not in the `70484cd..HEAD` diff).
   Every §C row and every code-anchored §B row carries over with its prior anchors intact.
   The window's code work landed in the new `rewrite/compile/` package (R410), the MCP
   diagnostics surface (R428/R430: `DiagnosticsTool`, `GraphitronMcpServer`), and
   `ArgCallEmitter`/`TypeClassGenerator`/`GraphQLRewriteGenerator`/`SourceKey` (small,
   non-anchor edits).
3. **The `SourceKey` decomposition graduated from watch to filed work.** The prior three
   audits flagged it as "the next structural pivot to watch." This window it became **R431**
   (`decompose-sourcekey`, the eager mechanical decomposition of
   `(target, columns, path, wrap, cardinality, reader)`) with **R432**
   (`collapse-split-and-record-table-leaves`) sequenced behind it and **R314** re-specced to
   depend on both. The surface itself is unchanged (Wrap + Cardinality still record
   components; compact constructor still pins `SourceRowsCall → Wrap.Row`). When R431 lands,
   **R71, R234, R314 and R432** all need re-checking.
4. **R222 remains the open umbrella** (Spec, `depends-on: []`), now with a dedicated
   conformance analysis (`2026-07-04-r222-r333-conformance-analysis.md`) recording where the
   implementation and docs sit against it. Stages 5–7 (the `JooqRecordInputType` collapse
   R234 forward-dates, namespace collapse, directive narrowing closing R97) are still
   outstanding, consistent with R234 staying in §C.
5. **A shared connection/tenant seam consolidated onto R429.** R429
   (`connection-transaction-lifecycle`, Ready) is now the single seam that R45 (routing), R46
   (fan-out) and R428 (in-process MCP execution) all build on, replacing the per-item ad-hoc
   connection plumbing the prior R46 design carried. This is the structural reason R46 and R45
   could be re-specced clean this window.
6. **`classification-test-dsl-inventory.md`** is doubly stale (R299/R290 + the R316 corpus
   recut) and still warrants the "superseded, historical" banner; it has **not** been added
   (left unedited here per scope).
7. **Cosmetic front-matter nits, none flag-worthy (carried over).** **R97** lacks
   `created:`/`last-updated:` header fields (an older item predating that convention).
   **R435** has no `priority:` key (a fresh Backlog stub). Neither is a build or dependency
   risk.
8. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated placeholder, not a
   roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-06._
