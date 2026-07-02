# Roadmap staleness audit: 2026-07-02

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `516067f`, 2026-07-01 22:25). The goal is to find items whose premise no
longer holds: work already shipped, constructs renamed or removed, dependencies
that have since landed, or specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-01` staleness audit, which has been deleted;
only the latest staleness audit is retained. Three siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
  It is a closed-work lineage record, not a point-in-time staleness review.
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  a companion to this audit. It is meant to be edited in place as scope iterates.
  Note it now reads further behind: R182, R398, R370, R406, R405, R332, R407 and
  R416 have all reached Done since it was last touched, so its MUST/SHOULD tables
  lag; refreshing it is out of scope for this staleness pass.
- `classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
  inventory, doubly stale (against R299/R290 and against the R316 corpus recut).
  It still warrants a "superseded, historical" banner; that banner has **not**
  been added, left unedited here per scope (see observation 6).

## Changes since the 2026-07-01 audit

The dominant event of this window is that **R182 (`unnest-rewrite-aggregator`)
landed** (In Review → Done, changelog landing sequence culminating in the
repo-root unwrap; R19 discarded at `adb071a`). This is a structural pivot, not an
ordinary closure, and it retroactively invalidated the prior audit's own path
citations: the reactor moved out of the `graphitron-rewrite/` subdirectory to the
**repo root**, so every `graphitron-rewrite/graphitron/src/main/java/...` anchor
the prior audit wrote is now one path segment too deep. This refresh **re-roots
every citation** onto the current layout (`graphitron/src/main/java/no/sikt/graphitron/rewrite/...`),
which is the single largest source of "stale reference" churn in this pass, and
the reason a same-substance §C set still needed a full re-anchor rather than a
line-number touch-up.

**Structural landing (the pivot the prior audits kept flagging around):**

- **R182** `unnest-rewrite-aggregator` → **Done, file deleted.** Retired the
  legacy `graphitron-parent` reactor (six legacy modules + legacy root POM gone),
  promoted `graphitron-rewrite-parent` to the root POM with its eleven modules +
  `docs`, collapsed the duplicate `graphitron-javapoet`, and closed the
  `release`-event republish hazard on `main`. Docs were restructured from the flat
  `graphitron-rewrite/docs/` tree into a Diataxis-shaped
  `docs/architecture/{explanation,reference,how-to}/`, and roadmap-internal
  `workflow.adoc` moved to `roadmap/workflow.adoc`. **Implication for this audit:
  the `docs/*.md` → `docs/*.adoc` migration the §C rows tracked is now compounded
  by a directory reshape; R17 and R35's doc cross-links need re-pointing at the
  Diataxis tree, not just an extension swap.**

**§A item resolved this window (its exact recommended action was executed):**

- **R19** `history-squash` (was §A, "Discard / close, verify supersession by R182
  first") → **Discarded, file deleted** (`adb071a`, folded into the R182 landing).
  The prior audit's §A call, "confirm R182 is the accepted path, then discard/close
  R19," was carried out verbatim as part of R182 going Done. **§A drops from two
  items to one** (R263 only).

**Terminal closures this window (Done, all self-deleted their files, none
flagged, all were current):** **R398** (`sdl-lint-visitor-engine`, was **In
Progress** in the prior audit; ESLint-style built-in visitor set shipped),
**R370** (`nested-backing-class-accessor-cast`, was a re-confirmed-current **Spec**
in the prior audit; now resolves nested backing classes to `Outer.Nested`),
**R332** (`@table`-on-input deprecation signal), **R405** (single-table
discriminated interface as a `@service` polymorphic return), **R406** (the DML
`@mutation`-return cognate of R405), **R407** (exclude generator-injected
federation/link definitions from linting), and **R416**
(`self-host GraphiQL assets in graphitron-jakarta-rest`). None was under flag; all
were current when they closed.

**New items on the board (all filed this window, all current/fresh):** **R408**
`lint-finding-suppression` (**In Review**; the suppression mechanism riding on
R398's now-Done lint engine), **R410** `dev-incremental-compile` (**Ready**),
**R411** `reject-wire-coercion-nonservice-sites` (Backlog), **R412**
`nested-backing-class-emitter-lift` (Backlog), **R413**
`split-parent-input-values-converter-datatype` (Backlog), **R414**
`nested-connection-totalcount-always-null` (Backlog), **R415**
`connection-first-last-negative-unclamped` (Backlog), **R417**
`sakila-readme-app-section-r399-drift` (Backlog). All re-verified spec-forward on
fresh code; every named anchor resolves; no dangling `depends-on`. **R347**
(`lsp-structural-consolidation`) advanced **Spec → In Progress** this window
(Slice 3, completion-provider contract + inferred-directive renderer registry).

**Board accounting.** Prior window closed at 124 item files; this window deleted
eight by closure/transition (R182, R398, R370, R332, R405, R406, R407, R416, plus
the R19 discard), filed eight new (R408, R410, R411, R412, R413, R414, R415,
R417), for **124** item files today. Status distribution: **103 Backlog, 13 Spec,
6 Ready, 1 In Progress (R347), 1 In Review (R408); zero Done** (tombstone-free for
the second window running). A non-recursive `^status: Done` grep over
`roadmap/*.md` returns nothing. No duplicate `id:`; `next-id: R418` with max
allocated id R417. A `depends-on` sweep found no dangling slugs. The board is
structurally clean.

**Net effect on flag counts: 22 flagged, 102 current** (prior window: 23/101).
Flag composition moved from (2 / 8 / 13) to **(1 / 8 / 13)**: §A shed R19
(discarded), leaving R263 alone; §B held its eight (all re-verified, all still
reproduce); §C held its thirteen. Every closure and every new item this window
landed on current/fresh code; none under flag.

## Scope and method

All **124** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder). For each item the targets it names
(classes, directives, methods, packages, modules) were located in the current
tree under `graphitron/src/main/java/no/sikt/graphitron/rewrite/`, the described
problem was checked for whether it still reproduces, and the changelog was scanned
for the item's `R<n>` and key terms to catch work that shipped without the item
being closed.

Every flagged item carried over from the prior audit was re-verified against
current HEAD. The structural landings the prior audits relied on all still hold:
**R276** (`ResultType` is a 4-arm seal at `GraphitronType.java:93-94`;
`PojoResultType` permits only `Backed` at `:119-120`), **R290** (`LeafTupleAdapter`
/ `ConstructorField` dissolved), **R305** (`SingleRecordTableField` gone,
re-confirmed as neither a declared type nor a pattern-match arm; surviving only in
Javadoc/comment mentions; live carrier `RecordTableField` in `model/ChildField.java`),
and **R316** (the `carrier × intent × mapping` field model gone, replaced by
`(source, operation, target)`; `model/Operation.java` is a sealed interface). The
**`SourceKey` `Wrap` decomposition still has not landed** (`SourceKey.java:81-82`
still carries `Wrap wrap` + `Cardinality cardinality`, and the compact constructor
still pins `SourceRowsCall → Wrap.Row` at `:124-126`), so the standing R71 /
observation 4 watch holds verbatim.

Line-anchor drift this window was again concentrated in the two large generator
files, driven by the R398 lint engine, R406 and R370 landings:
**`TypeFetcherGenerator.java`** grew to **6 536** (**+149** from the prior audit's
6 387) and **`FieldBuilder.java`** to **6 360** (**+67** from 6 293).
**`BuildContext.java`** held **exactly** at **2 675**. So the
TypeFetcherGenerator- and FieldBuilder-anchored §C rows (R92, R103, R201, R240,
R242, R202, R308) took the re-anchor cost this window; the BuildContext-anchored
rows (R236, R263) are stable.

**Result: 22 items flagged, 102 current.** Line numbers cited below are as of the
review date and will themselves drift; see observation 3.

## A. Obsolete: should leave the active roadmap (1)

The prior audit's other §A item, **R19**, was **discarded this window** as part of
the R182 landing (see "Changes since"), so §A carries a single item.

| Item | Status | Action | Why |
|---|---|---|---|
| **R263** decode-helper-typename-first-resolution | Backlog | **Discard as speculative** (re-open only if a MUST-route `@nodeId(typeName:)` caller appears) | Unchanged from the prior audit's §A promotion, and re-verified on current HEAD. **R377 settled the open question the discard was hedged on.** `resolveDecodeHelperForTable` sits at `BuildContext.java:2436` (held exactly, BuildContext did not move this window); it resolves through the `@node` `NodeIndex` (`nodes.forTable(...)` at `:2445`), returns `null` on multi-node ambiguity (a validate-time rejection, not the silent `decode<firstType>` the original finding described), and falls back to the `typeId` suffix only when no `@node` backs the table. Its callers (`BuildContext.java:2248`/`:2386`, plus the `FieldBuilder`/`NodeIdLeafResolver` sites) all pass the table name as primary; **none** routes an authoritative `@nodeId(typeName:)`. The premise survives only in spirit and no consumer needs it. Re-anchor onto the R377 body if capturing the finding in `changelog.md`, then discard. (R377 has no roadmap `.md` file; it is referenced only in code javadoc / changelog.) |

## B. Outdated: needs re-spec (premise or targets materially changed) (8)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. All eight carry
over from the prior audit and were re-verified this window; **every one still
reproduces.** Beyond their own symbol drift, all eight now also carry the
**global `graphitron-rewrite/` → repo-root path shift** from R182, so the re-spec
that clears each one should re-root its paths as well.

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; still the sharpest.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm R276 deleted: `ResultType` is a 4-arm seal (`GraphitronType.java:93-94`), `PojoResultType` permits only `Backed` (`:119-120`). Its primary migration target `FetcherEmitter.propertyOrRecordValue` **still does not exist** (zero hits; the spec's own `:802`/`:131` anchors are dead); only the `GeneratorUtils.buildFkRowKey` half of the duplication survives (`generators/GeneratorUtils.java:241`, called `:212`). The `.md` still describes the deleted five-arm / `PojoResultType.NoBacking` model throughout. Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | **Symbol re-anchor; substance confirmed still future, and the standing structural watch.** Every symbol the mechanism names is dead: no `BatchKeyLifterDirectiveResolver`, no `BatchKey`/`LifterRowKeyed`/`RowKeyed` types anywhere. The live surface is `model/LifterRef.java`, routed through `@sourceRow` and `SourceKey.Reader.SourceRowsCall(LifterRef)` (`SourceKey.java:284`). R316 did **not** collapse `wrap`+`cardinality` out of `SourceKey` (`SourceKey.java:81-82` still carries `Wrap wrap` + `Cardinality cardinality`), and the compact constructor still pins `SourceRowsCall → Wrap.Row` (`:124-126`), the exact Row-only asymmetry R71 wants to remove. Re-anchor on `LifterRef`/`SourceRowsCall`/`Wrap`. |
| **R46** service-multi-tenant-fanout | Backlog | **The dependency abandoned the design R46 extends, and this held all window.** `ContextValueRegistration`, `getContextFanOut`, `openContextDslContext`, `getExecutor` all have **zero** `.java` hits repo-wide (R190 sealed `GraphitronContext`). R46's dependency **R45** (`tenant-routing-and-execution-input`, Ready) ships a per-field `TenantIdSource` overlay + `byTenant` on `GraphQLContext`, **not** the sealed `ContextValueRegistration` the whole "Design" section rests on. The `depends-on:` correctly uses the live slug `tenant-routing-and-execution-input`, but the body prose still links the dead `typed-context-value-registry.md` slug. Re-spec the whole Design section against the `TenantIdSource` overlay R45 actually ships. |
| **R314** dissolve-reentry-leaves-dimensional-emit | Backlog | The design section still names R316-deleted types as the build target: `Carrier.Source`, `Intent`/`Mapping` as proposed sealed hierarchies, and `f(carrier.sourceContext, intent.operation, mapping.target)`; an implementer would search for symbols that no longer exist. R316 narrowed R314's scope rather than mooting it: `Operation.java` still notes "Collapses to one carrier under R314," and both `requiresReFetch()` (`model/OutputField.java`) and `dispatchPerformsReFetch` (`GraphitronSchemaValidator.java`) still co-exist. Re-spec the design prose onto `(source, operation, target)`; `depends-on: [dimensional-model-pivot]` (R222, Spec) is the genuine blocker and resolves. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (Done), so the "R58 is currently *In Review* … if it reverts" framing (line 21) is false and should be removed. Carriers are still String-flattened (the `ParsedPath(…, String errorMessage, …)` and `InputFieldResolution.Unresolved(…, String reason)` carriers remain), so the carrier-audit body stays valid; `depends-on: []` is already correct. Lightest re-spec in this section: strike the reversion hedge. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)" (front-matter `title:` and README); **R94 shipped** (Done, slug `emit-input-records`). Blocker cleared, drop the blocked framing from title and body. R94's Done note hands the real remaining dependency to **R98** (`multi-source-input-validation`, re-confirmed Backlog), so re-point the dependency there and the title "(R94-blocked)" should become "(R98-blocked)". |
| **R24** nodeidreferencefield-join-projection-form | Backlog | Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **still does not exist** (dispatch is now `FetcherEmitter.bind`/`bindRaw`; the body's "lines 140-162" are dead), and the carrier in the **item title**, `NodeIdReferenceField`, was renamed by R50 to `ColumnReferenceField`/`CompositeColumnReferenceField`. The surviving "not yet implemented" stub is `CompositeColumnReferenceField` and it is the **single** entry in `TypeFetcherGenerator.STUBBED_VARIANTS` (`:300-311`, the `UnsupportedOperationException` "requires JOIN-with-projection emission"), confirming the release-planning doc's "one tracked stub" claim. Work is valid and unbuilt, but the title symbol + method names need a full re-anchor. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale and now further off: body says "1 646 lines" and "~30 private methods"; `TypeFetcherGenerator.java` is **6 536** (~4.0×). Lightest-substance re-spec in this section: refresh the LOC/method-count figures and re-survey the cut against the real file, or better, stop hard-coding a count and state "measure at execution time." |

## C. Outdated: update references only (work valid, refs stale) (13)

Substance intact; only paths, line numbers, or dependency tense drifted. **Every
row in this section additionally inherits the R182 path shift**
(`graphitron-rewrite/graphitron/src/main/java/...` → `graphitron/src/main/java/...`),
which is the bulk of the "stale reference" content this window. The recurring
code-side root cause is unchanged: `TypeFetcherGenerator.java` (**6 536**) /
`FieldBuilder.java` (**6 360**) / `BuildContext.java` (**2 675**) standing at ~2.7
to 4.0× the sizes the older specs cite. The R398/R406/R370 landings moved the
TypeFetcherGenerator and FieldBuilder anchors; the BuildContext-anchored rows
(R236) are stable.

| Item | Stale reference |
|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Premise live and unbuilt: `resolvePayloadConstructionShape` on the `PayloadClass` arm (`FieldBuilder.java:494`, called `:2563`); the mutable-bean logic in `tryMutableBean` (`:559`), whose setter-match arm builds `SetterBinding`s off the raw SDL field name via `javaBeanSetterName(sdlFieldName)` (`:606`, predicate `:577`) with **no `@field` read**. The emitter halves are `TypeFetcherGenerator.payloadFactoryLambda` (`:6437`, drifted from the prior audit's `:6288`) and `payloadFactoryLambdaSetters` (`:6454`). Body already frames it as "the output-side mirror of R200"; only the cited line ranges drifted. Re-anchor at the symbols; no premise change. |
| **R308** service-list-payload-arrival | The `@service` list-payload N+1 is real and unbuilt (`checkServiceReturnMatchesPayload` `FieldBuilder.java:3025`, called `:2986`, still admits `List<Payload>`). The body still describes the no-DataLoader shape as "`SingleRecordTableField` today, the single-arrival arm of the merged `RecordTableField` after R305", and `SingleRecordTableField` was **deleted** by R305 (re-confirmed: neither a declared type nor a pattern-match arm; surviving only in Javadoc/comment mentions). The live carrier `RecordTableField` is in `model/ChildField.java`. Strip the "today" framing; bump `last-updated`. Note R305 is no longer a live roadmap `.md` (completed/deleted), so verify that cross-reference on the next touch. (`depends-on` already `[]`.) |
| **R202** honor-field-directive-in-error-type-source-accessors | Premise live and unbuilt: `checkErrorTypeSourceAccessors` (`FieldBuilder.java:2767`, called `:2542`) calls `ClassAccessorResolver.resolve(sourceClass, sdlField.getName(), …)` (`:2786`) with the raw SDL name and **no `@field` read**, exactly the gap R202 describes. R200's Done note names R202 as a remaining `@field`-symmetry sibling. Re-anchor at the symbol. |
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck as "Obsolete (R287)"; body re-based onto R305's re-fetch substrate; `depends-on` `[]`. Numeric anchors stale (and now further drifted by the +149 TypeFetcherGenerator growth): `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` and the `[ID]`-reject scoped to `CarrierFamily.DML` in `BuildContext.java` both need re-anchoring at the symbols; drop the stale "3 sites" count. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` (**679**, held exactly this window) substance holds (emit-half still unbuilt). Anchors: the argument-level `@condition` resolution dispatch is at `:412-420`, the non-`@table` arg rejection (`foundTia.argCondition().isPresent()`) at `:436`; the `@condition(override:true)` admission gate reads `ARG_OVERRIDE` (imported from `BuildContext` at `:26`). Body cites older ranges; re-anchor at the symbols (drift is minimal here). |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`, and now under the **Diataxis `docs/architecture/` tree** R182 introduced, not the old flat `docs/` dir. The two cited sibling docs (`docs-as-index-into-tests.md`, `rewrite-docs-entrypoint.md`) do not exist under any extension or location. Relocate onto the Diataxis layout or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (confirmed absent) and stale `ConnectionSynthesis` naming. The live seam is `ConnectionPromoter.synthesiseForField` / `.rebuildAssembledForConnections` (`ConnectionPromoter.java:122`/`:196`, both held; path re-rooted from R182); re-anchor there and fix the dangling doc link. |
| **R92** catalog-check-constraint-validation | **Path move plus re-anchor this window.** `validatorPreStep` is now defined `TypeFetcherGenerator.java:1941` (was `:1881`; seam call site `:1809`); `DefaultValidatorHolder` and `getValidator` moved to the `generators/util/` subpackage (`generators/util/GraphitronContextInterfaceGenerator.java:84`/`:95-99`). No `CheckRecognizer`/`findCheckConstraints` exists; the R12 §5 validator seam this builds on is present as described. Re-anchor the TypeFetcherGenerator line and the two (relocated) context-gen lines. |
| **R103** lift-jooq-column-defaults | **Heavy re-anchor this window.** `DSL.defaultValue` emission is clustered in `buildPerCellValueList` (defined `TypeFetcherGenerator.java:2310`, was `:2250`; emitting sites `:2245`/`:2252`/`:2259`), with a deduped sibling `buildPerCellValueListDeduped` at `:2483` (was `:2423`). The contract-side gap (no SDL default lift) is unbuilt. |
| **R240** tablemethod-return-type-token-threading | Current: `ServiceCatalog.reflectTableMethod` has two overloads (dispatcher `:565`, slot-types-aware `:579`; both drifted from `:560`/`:574`); the strict `ClassName.equals` return-type gate follows; `buildQueryTableMethodFetcher` is at `TypeFetcherGenerator.java:1396` (was `:1343`; called `:462`). Neither `MethodRef.StaticOnly` nor `ReturnTypeRef.TableBoundReturnType` carries a type token yet. Re-anchor at the symbols. |
| **R236** validator-reference-candidate-hint-terminal-table | (Anchors are in the **item body**.) `classifyInputFieldInternal` and the candidate-hint failure-aggregation (`candidateHint(c, catalog.columnSqlNamesOf(resolvedTable.tableName()))`) are in `BuildContext.java`, which held exactly this window (no drift beyond the R182 path shift). The hint still draws from the path-origin `resolvedTable.tableName()`, not the `@reference` terminal table (R380's Done note still lists R236 as an open sibling). Re-anchor at the symbol and re-root the path. |
| **R35** source-orientation-javadocs | LOC counts grossly stale and now further off (FieldBuilder cited "2 172", now **6 360**; TypeFetcherGenerator "1 646", now **6 536**); no `package-info.java` exists in the rewrite tree (sweep undone, re-confirmed). Doc cross-link nuance compounded by R182: the architecture docs are now the Diataxis `docs/architecture/` tree (`.adoc`), and `selection-parser-audit` no longer exists (R30 swept). Refresh the figures and re-point the doc links onto the Diataxis layout. |
| **R234** jooq-embedded-and-udt-input-backings | Valid, dormant Backlog stub gated behind the R222 collapse (Stages 5–7). Anchors to keep fresh: `JooqRecordInputType` is a live populated input arm (`GraphitronType.java`, permitted alongside the other input arms at `:303`); `JooqTableRecordInputType` is a separate sibling; the proposed `BackingClass` family is still unbuilt (no `BackingClass.java`; `parentBackingClass` exists only as a component on `AccessorRef.java`). `depends-on: []` is correct (R222 is a prose gate, not a hard edge). |

**Re-confirmed Current (not flagged):** **R45** (`tenant-routing-and-execution-input`,
**Ready**), the `TenantIdSource` overlay, internally consistent, reconciled with
R190/R316. **R13** (`faceted-search`, **Ready**), the re-spec onto the live
`ConnectionPromoter` seam still holds. **R261** (`wire-coercion-cast-guard`,
**Spec**), anchors accurate. **R219** `unify-inference-rule-by-javatypekey` and
**R220** `consolidate-sources-shape-predicates` both target `ServiceCatalog`
parameter-binding inference (three separate predicates; anchors drifted but
substance holds); valid, unbuilt. **R267** `nodeid-encoder-deprecated-convert`.
**R222** `dimensional-model-pivot` stays **Spec** as the umbrella (`depends-on: []`,
Stages 5 through 7 outstanding). **R333** `coordinate-lowers-to-datafetcher-queryparts`
(Spec), still no `depends-on:` key; correctly not flagged. **R273**
`nodeid-skip-mismatch-error-surfacing` (Spec), its "Settled by R378" section correctly
folds in the now-Done R378 policy. **R63** `dml-dialect-requirement-on-model` (Spec),
**R396** `reference-fk-connection-qualified-table-name` (Ready), **R346**
`supported-directives-regen-guard` (Ready), and **R360** (`retire-enum-directive`,
Backlog; `@enum` still live, retirement genuinely unbuilt) all re-verified. The new
**R408** (In Review), **R410** (Ready), **R411**–**R415** and **R417** (Backlog), plus
**R347** (now In Progress), all re-verified spec-forward on current code, none under
flag.

## Cross-cutting observations

1. **The pivot the audits kept watching for landed.** R182 unnested the rewrite to
   the repo root and retired the legacy reactor; the prior audit's §A recommendation
   on R19 (discard, superseded by R182) was executed as part of that landing. Net
   flag count 23/101 → 22/101; §A shrank to a single item. The board carries **zero
   Done tombstones** for the second window running.
2. **The largest staleness source this window was not code churn but the path shift.**
   R182 moving the reactor out of `graphitron-rewrite/` invalidated the path prefix on
   *every* citation in the prior audit (including the prior audit's own header). The
   substance of the §B/§C set did not change; the re-root did. This is a one-time cost
   now paid in this refresh, but it is worth flagging that any item body still written
   against `graphitron-rewrite/...` paths is now wrong by one segment.
3. **Line-drift recurred, concentrated in the two large generator files.** The R398
   lint engine, R406 and R370 landings grew `TypeFetcherGenerator.java` (**6 536**,
   +149) and `FieldBuilder.java` (**6 360**, +67); `BuildContext.java` held **exactly**
   at 2 675. The rows that drifted most are all TypeFetcherGenerator/FieldBuilder-anchored:
   **R92** (`validatorPreStep` `:1881`→`:1941`), **R103** (`buildPerCellValueList`
   `:2250`→`:2310`), **R240** (`buildQueryTableMethodFetcher` `:1343`→`:1396`), **R201**
   (`payloadFactoryLambda` `:6288`→`:6437`). The only durable defense remains re-reading
   the method at re-spec time; this is the structural backdrop behind §C.
4. **The next structural pivot to watch is still the `SourceKey` decomposition.** It
   will collapse `wrap`+`cardinality` out of `SourceKey` (`SourceKey.java:81-82`,
   `:124-126` today). Re-confirmed verbatim this window: the `Wrap` contract is
   untouched, so the watch holds. When the decomposition ships, **R71** (re-anchors on
   `SourceKey.Wrap`, which it removes), **R314** (the emit re-platforming R316 pinned to
   it), and **R234**'s input-carrier collapse will all need re-checking.
5. **R222 remains the open umbrella** (Spec, `depends-on: []`). Stages 5 through 7 (the
   `JooqRecordInputType` collapse R234 forward-dates, namespace collapse, directive
   narrowing closing R97) are still outstanding, consistent with R234's premise being
   premature, not wrong-in-spirit, which is why R234 stays in §C.
6. **The `@enum` retirement continues to bound a small staleness class.** **R360**
   proposes a declared-but-rejected retirement for `@enum`; `@enum` is still live in
   code, so R360 is correctly Backlog and no item is yet stale against it.
   `classification-test-dsl-inventory.md` is doubly stale (R299/R290 + the R316 corpus
   recut) and still warrants the "superseded, historical" banner; it has **not** been
   added (left unedited here per scope).
7. **Minor reference lags on otherwise-current items (below flag threshold).** **R396**
   (Ready) says a `depends-on` on R395 "is now declared" and R395 "is in flight," but
   R395 is Done (file deleted) and the front-matter `depends-on` is correctly `[]`;
   **R26** links `remove-notgenerated.md`, a file gone because that work shipped;
   **R397** (Backlog) references `redactCatchArm`, renamed to `noChannelCatchArm` per
   R378's changelog entry. None is a dangling `depends-on` or a build risk.
8. **Cosmetic front-matter nits, none flag-worthy (carried over).** **R333** still has
   no `depends-on:` key; add one for parseability on its next touch. **R97** lacks
   `created:`/`last-updated:` header fields (an older item predating that convention).
   Neither is a build or dependency risk.
9. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-02._
