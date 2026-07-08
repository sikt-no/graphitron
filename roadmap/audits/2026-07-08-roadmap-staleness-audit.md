# Roadmap staleness audit: 2026-07-08

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `bb7da4b`, 2026-07-08). The goal is to find items whose premise no longer
holds: work already shipped, constructs renamed or removed, dependencies that
have since landed, or specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-07` staleness audit, which has been deleted;
only the latest staleness audit is retained. Four siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  meant to be edited in place as scope iterates. It reads yet further behind this
  window: on top of the closures the prior audits noted, **R434** reached Done and
  **R438** advanced to In Review since it was last touched, so its MUST/SHOULD
  tables lag further. Refreshing it is out of scope for this staleness pass.
- `2026-07-04-r222-r333-conformance-analysis.md` is the R222/R333 conformance
  analysis (`6131f99`), a companion to the R314/R333 design session. It is a
  targeted implementation-vs-spec conformance record, not a point-in-time
  staleness review; left in place.
- `classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
  inventory, doubly stale (against R299/R290 and the R316 corpus recut). It still
  warrants a "superseded, historical" banner; that banner has **not** been added,
  left unedited here per scope (see observation 6).

## Changes since the 2026-07-07 audit

A **structural-landing** window: the marquee event is **R438**
(`materialize-joinpath-facts`) driving the full Backlog → Spec → Ready → In
Progress → In Review arc, landing the two-axis `JoinStep` reshape and **absorbing
and closing R16** (`fkjoin-model-cleanup`) in the process. Alongside it,
**R434** (principles-doc axiom restructure) closed Spec → Done, and **R439**
(background web-env warm-up) was filed and closed Backlog → Done inside the
window as dev-tooling-only work. **R435** churned Ready → Spec → Ready. This is a
marked contrast with the prior bug-fix-and-polish pass: the join model was
reshaped, so line-anchor drift returned across two large files that were
byte-identical last window.

**Terminal closures this window (Done, all self-deleted their files, none flagged,
all were current):**

- **R434** (`principles-doc-axiom-restructure`) — was **Spec** ("Re-confirmed
  Current") at the prior snapshot; closed Spec → Ready → In Progress → In Review →
  Done. Docs-only: the flat 28-section `rewrite-design-principles.adoc` is replaced
  by `docs/architecture/explanation/development-principles.adoc` (six axioms, each
  principle carrying rule + exemplar + smell + an `*Enforced by:*` line), the
  Emitter Conventions catalogue extracted to
  `docs/architecture/reference/emitter-conventions.adoc`, budgeted at 3,500 words
  (landed 3,456) and enforced by a new `DocSizeBudgetTest`. No generator code.
- **R439** (`background web-env warm-up`) — **filed and closed in this window**
  (Backlog `66dea39` → In Review `ea67d6c` at the user's direction with the
  implementation pre-landed → Done). Dev-tooling only, **no generator code
  touched**: the SessionStart hook (`.claude/scripts/session-start-web-env.sh`)
  now runs asynchronously in Claude Code Web sessions, warming the reactor in the
  background with a PreToolUse guard (`.claude/scripts/wait-for-web-env.sh`) that
  holds `mvn`/`psql` through the prereqs+warm-build window so a foreground build
  cannot race the background one into the catalog-jar clobber.

**R16 closed by absorption (not a self-transition):** **R16**
(`fkjoin-model-cleanup`, Backlog) was **absorbed and closed by R438's slice 1 +
cutover** (file deleted, changelog entry added). The join-condition calling
convention is now typed (`JoinConditionRef` wraps the `MethodRef` population
called `method(srcAlias, tgtAlias)`); R16's `whereFilter` naming complaint
dissolved structurally because the ON-clause condition and the WHERE-appended
filter became differently-named components (`On.Predicate.condition` vs
`JoinStep.Hop.filter`). R16 was **not** under flag in the prior audit; it was
counted current and is now off the board.

**New items on the board (filed this window):** **R439** (filed → Done in-window,
see above). No other item files were created; `next-id` advanced R439 → **R440**.

**Transitions this window:**

- **R438** (`materialize-joinpath-facts`) drove **Backlog → Spec → Ready → In
  Progress → In Review** and **ends In Review**. It minted the two axes
  (`TableExpr`, `On`, and the `Hop` permit; `JoinStep` is now a sealed
  `permits JoinStep.Hop, JoinStep.LiftedHop`, replacing the flat variants), cut
  producers over to `Hop`, migrated every reader, and deleted the flat variants.
  This is the join-path twin of R431's source-side `SourceKey` decomposition,
  landing eagerly and mechanically. Deliberate structural landing, current.
- **R434** (`principles-doc-axiom-restructure`) closed to **Done** (see above).
- **R439** filed and closed to **Done** (see above).
- **R435** (`routine-table-node-composition`) churned **Ready → Spec → Ready**
  (reopened for `enforced-by` sharpenings from a post-R434 re-consult, then
  re-signed off on an independent pass). Its `depends-on` stays
  `[coordinate-lowers-to-datafetcher-queryparts, materialize-joinpath-facts]`
  (R333/R438); both live. Ends **Ready**; deliberate rework, current.
- **R429** (`connection-transaction-lifecycle`, **Ready**), **R428**
  (`mcp-execute-query-in-process`, **Spec**) and **R381**
  (`lsp-reference-path-authoring`, **Spec**) are **unchanged this window** (their
  files are not in the `25d4998..HEAD` diff); all remain current.
- **R347** (`lsp-structural-consolidation`) holds at **In Progress**. **R222** and
  **R333** remain **Spec** and current.

**Board accounting.** **128 item files** today, down 2 from the prior audit's 130
(R439 filed → Done nets zero on file count; R434 Done and R16 absorbed each remove
a file). Status distribution: **106 Backlog, 16 Spec, 4 Ready, 1 In Progress
(R347), 1 In Review (R438); zero Done**. The status deltas from the prior audit are
**Backlog 108 → 106** (R16 off board, R438 promoted out of Backlog), **Spec 17 → 16**
(R434 closed), and **In Review 0 → 1** (R438 arrived); **Ready holds at 4** (R435
churned but ends Ready). A non-recursive `^status: Done` grep over `roadmap/*.md`
returns nothing (tombstone-free for the sixth window running; R438's file is present
as In Review, not a Done tombstone). No duplicate `id:`; `next-id: R440` (in
`changelog.md`) with max allocated id **R439**. A `depends-on` sweep over all 128
files found **no dangling slugs**; R435's `materialize-joinpath-facts` edge resolves
to R438, and R314's `[coordinate-lowers-to-datafetcher-queryparts,
decompose-sourcekey, collapse-split-and-record-table-leaves]` resolves to
R333/R431/R432. The board is structurally clean.

**Net effect on flag counts: 20 flagged, 108 current** (flag count unchanged from
the prior window's 20; current drops 110 → 108 for the two closures). Flag
composition holds at **(1 / 6 / 13)**: §A is R263 alone; §B holds its six; §C holds
its thirteen. Every closure and every transition this window landed on current/fresh
code; none was under flag. **R438's cutover did not subsume any flagged item** — it
reshaped the join model and typed the join-condition convention (closing R16), but
the source-side `SourceKey` decomposition R71/R234/R314/R432/R435 ride is **R431**,
which is still filed and pending (`SourceKey` still carries `Wrap` + `Cardinality`
and the compact constructor still pins `SourceRowsCall → Wrap.ROW`, verified below).
Every remaining flagged item was re-verified against current HEAD and **every one
still reproduces**.

## Scope and method

All **128** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded). For each item the
targets it names (classes, directives, methods, packages) were located in the current
tree under `graphitron/src/main/java/no/sikt/graphitron/rewrite/`, the described problem
was checked for whether it still reproduces, and the changelog was scanned for the item's
`R<n>` and key terms to catch work that shipped without the item being closed.

The structural landings the prior audits relied on all still hold: **R276**
(`ResultType` is a 4-arm seal; `PojoResultType` permits only `Backed`), **R290**,
**R305** (`SingleRecordTableField` gone; live carrier `RecordTableField` in
`model/ChildField.java`, confirmed at `:849`), and **R316** (the `carrier × intent ×
mapping` field model gone, replaced by `(source, operation, target)`).

**The `SourceKey` decomposition still has not landed.** It remains a filed, sequenced
item (**R431**, Backlog, `depends-on: []`) with **R432** depending on it and **R314**
re-specced to depend on both. This window landed the join-path twin **R438**
(`materialize-joinpath-facts`, now In Review), which decomposes what the *step*
conflates the way R431 decomposes what the *source* endpoint conflates. R438's landing
did **not** touch the `SourceKey` surface materially: `model/SourceKey.java` still
carries `Wrap` (ROW / RECORD / TableRecord) and `Cardinality` (ONE / MANY) as record
components and still pins `SourceRowsCall → Wrap.ROW` in its documented mapping, so
the Row-only asymmetry R71 wants to remove is intact. When R431 lands (and once R438
reaches Done), **R71, R234, R314, R432 and R435** all need re-checking.

**Line-anchor drift this window: returned across two large files.** Unlike the prior
window where `FieldBuilder.java` and `BuildContext.java` were byte-identical, R438's
cutover **touched both**:

- **`BuildContext.java` grew 2 669 → 2 689** (+20; R438 cutover). §C/§B rows anchored
  to it were re-anchored (see below).
- **`FieldBuilder.java` grew 6 363 → 6 373** (+10; R438 cutover). §C rows re-anchored.
- **`TypeFetcherGenerator.java` grew 6 672 → 6 673** (+1). Rows re-anchored.
- **`ServiceCatalog.java` changed** (R240's `reflectTableMethod` gained a slot-types
  overload; the base def moved).
- **`ChildField.java`, `SourceKey.java`, `ParticipantRef.java`** changed as part of the
  join-model reshape; substance-critical anchors (`RecordTableField`, `SourceKey`'s
  `Wrap`/`Cardinality`/`SourceRowsCall` pin) all verified intact.
- **`GeneratorUtils.java` (578), `FetcherEmitter.java` (763), `MutationInputResolver.java`
  (679), `ConnectionPromoter.java`, `InputFieldResolution.java`** are unchanged this
  window; their anchors carry over verbatim.

Refreshed anchors (2026-07-08 line numbers):

- `resolveDecodeHelperForTable` `BuildContext.java:2450` (was `:2430`); callers
  `:2262`/`:2400` (were `:2242`/`:2380`) — R263.
- `classifyInputFieldInternal` `BuildContext.java:2050` (was `:2032`); candidate-hint
  `candidateHint(c, catalog.columnSqlNamesOf(resolvedTable.tableName()))` `:2139`
  (was `:2121`) — R236. `ParsedPath(…, String errorMessage, …)` decl `:991` (was `:990`).
- `resolvePayloadConstructionShape` `FieldBuilder.java:499` (was `:494`); `tryMutableBean`
  `:564` (was `:559`) — R201. `payloadFactoryLambda` `TypeFetcherGenerator.java:6540`
  (was `:6539`); `payloadFactoryLambdaSetters` `:6557` (was `:6556`) — R201.
- `checkServiceReturnMatchesPayload` `FieldBuilder.java:3043` (was `:3038`) — R308.
- `checkErrorTypeSourceAccessors` `FieldBuilder.java:2785` (was `:2780`) — R202.
- `validatorPreStep` `TypeFetcherGenerator.java:1984` (was `:1983`); seam call `:1852`
  (was `:1851`) — R92.
- `buildPerCellValueList` `TypeFetcherGenerator.java:2405` (was `:2404`);
  `buildPerCellValueListDeduped` `:2578` (was `:2577`) — R103.
- `buildQueryTableMethodFetcher` def `TypeFetcherGenerator.java:1439` (was `:1438`),
  called `:471` (was `:470`); `reflectTableMethod` base def `ServiceCatalog.java:566`,
  slot-types overload `:580` (was a single def `:565`) — R240.
- `STUBBED_VARIANTS` declared `TypeFetcherGenerator.java:301` (was `:300`), single
  `Map.entry(ChildField.CompositeColumnReferenceField.class, …)` `:309` (was `:308`) — R24.
- `GeneratorUtils.buildFkRowKey` `:274` (called `:245`) — **unchanged**;
  `FetcherEmitter.propertyOrRecordValue` still absent (R180's premise holds).
- `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` `:631` (called `:373`) —
  **unchanged** (R242); `FetcherEmitter#dataFetcherValue` **still absent** (R24 premise holds).

**Result: 20 items flagged, 108 current.**

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Action | Why |
|---|---|---|---|
| **R263** decode-helper-typename-first-resolution | Backlog | **Discard as speculative** (re-open only if a MUST-route `@nodeId(typeName:)` caller appears) | Unchanged in substance from the prior audit, re-verified on current HEAD (line anchors drifted +20 with the R438 cutover). R377 settled the open question the discard hedged on. `resolveDecodeHelperForTable` is now defined `BuildContext.java:2450` (was `:2430`); it resolves through the `@node` `NodeIndex`, returns `null` on multi-node ambiguity (a validate-time rejection, not the silent `decode<firstType>` the finding described), and falls back to the `typeId` suffix only when no `@node` backs the table. Its callers (`BuildContext.java:2262`/`:2400`, both moved +20) all pass the table name as primary; **none** routes an authoritative `@nodeId(typeName:)`. The premise survives only in spirit and no consumer needs it. Re-anchor onto the R377 body if capturing the finding in `changelog.md`, then discard. |

## B. Outdated: needs re-spec (premise or targets materially changed) (6)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. All six carried over
from the prior audit and were re-verified this window; **every one still reproduces**.
The three anchored to `FieldBuilder.java`/`BuildContext.java`/`ServiceCatalog.java`
were re-anchored (those files changed in the R438 cutover); the `GeneratorUtils.java`
half of R180 held verbatim.

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; still the sharpest.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm R276 deleted: `ResultType` is a 4-arm seal, `PojoResultType` permits only `Backed`. Its primary migration target `FetcherEmitter.propertyOrRecordValue` **still does not exist** (zero hits; the spec's own `:802`/`:131` anchors are dead); only the `GeneratorUtils.buildFkRowKey` half of the duplication survives — at **`generators/GeneratorUtils.java:274`, called `:245`** (unchanged this window; that file was not in the R438 cutover). The `.md` still describes the deleted five-arm / `PojoResultType.NoBacking` model throughout. Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | **Symbol re-anchor; substance confirmed still future.** Every symbol the mechanism names is dead: no `BatchKeyLifterDirectiveResolver`, no `BatchKey`/`LifterRowKeyed`/`RowKeyed` types anywhere. The live surface is `model/LifterRef.java`, routed through `@sourceRow` and `SourceKey.Reader.SourceRowsCall(LifterRef)`. `SourceKey` still carries `Wrap`+`Cardinality` and its documented mapping still pins `SourceRowsCall → Wrap.ROW` (verified `model/SourceKey.java:60-62`), the exact Row-only asymmetry R71 wants to remove — **R438's cutover did not touch this** (it reshaped the *step*, not the *source* endpoint). Re-anchor on `LifterRef`/`SourceRowsCall`/`Wrap`. The decomposition R71 rides is filed as **R431**; wire R71's `depends-on`/prose to R431 on its next touch. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (Done, file absent), so the "R58 is currently *In Review* … if it reverts" framing (line 21) is false and should be removed. Carriers are still String-flattened (`ParsedPath(…, String errorMessage, …)` at `BuildContext.java:991` and `InputFieldResolution.Unresolved(…, String reason, …)` in `InputFieldResolution.java` both remain), so the carrier-audit body stays valid; `depends-on: []` correct. Lightest re-spec in this section: strike the reversion hedge. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)" (front-matter `title:` and README); **R94 shipped** (Done, slug `emit-input-records`, file absent). Blocker cleared, drop the blocked framing. R94's Done note handed the real remaining dependency to **R98** (`multi-source-input-validation`, re-confirmed Backlog, file present), so re-point the dependency there and change the title "(R94-blocked)" to "(R98-blocked)". |
| **R24** nodeidreferencefield-join-projection-form | Backlog | Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **still does not exist** (dispatch is now `FetcherEmitter.bind`/`bindRaw`; `dataFetcherValue` survives only in stale Javadoc; the body's "lines 140-162" are dead), and the carrier in the **item title**, `NodeIdReferenceField`, was renamed by R50 to `Column`/`CompositeColumnReferenceField` (zero `NodeIdReferenceField` hits). The surviving "not yet implemented" stub is `ChildField.CompositeColumnReferenceField` and it is the **single** entry in `TypeFetcherGenerator.STUBBED_VARIANTS` (declared `:301`, `Map.entry(…)` now `:309`; both +1 this window), whose `planSlug` is R24's own slug, confirming the release-planning "one tracked stub" claim. Work is valid and unbuilt, but the title symbol + method names need a full re-anchor. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines" and "~30 private methods"; `TypeFetcherGenerator.java` is **6 673** this window (~4.05×). Lightest-substance re-spec in this section: refresh the LOC/method-count figures and re-survey the cut against the real file, or better, stop hard-coding a count and state "measure at execution time." |

## C. Outdated: update references only (work valid, refs stale) (13)

Substance intact; only paths, line numbers, or dependency tense drifted. **This window
the drift widened**: the R438 cutover touched `BuildContext.java` (2 689),
`FieldBuilder.java` (6 373), `TypeFetcherGenerator.java` (6 673) and `ServiceCatalog.java`,
so rows on those files were re-anchored. The recurring code-side root cause is unchanged:
the three large generators standing at ~2.6 to 4.05× the sizes the older specs cite.

| Item | Stale reference |
|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Premise live and unbuilt: `resolvePayloadConstructionShape` on the `PayloadClass` arm (`FieldBuilder.java:499`; called `:2581`); the mutable-bean logic in `tryMutableBean` (`:564`), whose setter-match arm builds `SetterBinding`s off the raw SDL field name with **no `@field` read**. The emitter halves are `TypeFetcherGenerator.payloadFactoryLambda` (**now `:6540`**) and `payloadFactoryLambdaSetters` (**now `:6557`**). Body already frames it as "the output-side mirror of R200"; all anchors drifted with the R438 cutover (+5 in FieldBuilder, +1 in TFG). |
| **R308** service-list-payload-arrival | The `@service` list-payload N+1 is real and unbuilt (`checkServiceReturnMatchesPayload` `FieldBuilder.java:3043`, called `:3004`; still admits `List<Payload>`). The body still describes the no-DataLoader shape via `SingleRecordTableField`, which R305 **deleted**; the live carrier `RecordTableField` is in `model/ChildField.java:849`. Strip the "today" framing; bump `last-updated`. (`depends-on` already `[]`.) |
| **R202** honor-field-directive-in-error-type-source-accessors | Premise live and unbuilt: `checkErrorTypeSourceAccessors` (`FieldBuilder.java:2785`, called `:2560`) calls `ClassAccessorResolver.resolve(...)` with the raw SDL name and **no `@field` read**, exactly the gap R202 describes. R200's Done note names R202 as a remaining `@field`-symmetry sibling. Re-anchor at the symbol (+5 with the R438 cutover). |
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck as "Obsolete (R287)"; body re-based onto R305's re-fetch substrate; `depends-on` `[]`. `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` (`FetcherEmitter.java:631`, called `:373`, **unchanged this window**) and the `[ID]`-reject scoped to `CarrierFamily.DML` in `BuildContext.java` still need re-anchoring at the symbol; drop the stale "3 sites" count. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` (**679**, unchanged this window) substance holds (emit-half still unbuilt). The non-`@table` arg rejection (`foundTia.argCondition().isPresent()`) and the `@condition(override:true)` admission gate (`ARG_OVERRIDE`) hold at their prior lines. Body cites older ranges; re-anchor at the symbols (drift is minimal here). |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`, under the **Diataxis `docs/architecture/` tree** R182 introduced, not the old flat `docs/` dir. The two cited sibling docs (`docs-as-index-into-tests.md`, `rewrite-docs-entrypoint.md`) do not exist under any extension or location. Relocate onto the Diataxis layout or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (absent) and stale `ConnectionSynthesis` naming. The live seam is `ConnectionPromoter.synthesiseForField` / `.rebuildAssembledForConnections` (`ConnectionPromoter.java`, both methods present, file unchanged this window); re-anchor there and fix the dangling doc link. |
| **R92** catalog-check-constraint-validation | `validatorPreStep` is defined **`TypeFetcherGenerator.java:1984`** (seam call site **`:1852`**) — both drifted +1 with the R438 cutover; `DefaultValidatorHolder`/`getValidator` live in `generators/util/`. No `CheckRecognizer`/`findCheckConstraints` exists; the R12 §5 validator seam this builds on is present as described. |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue` emission is clustered in `buildPerCellValueList` (**now `TypeFetcherGenerator.java:2405`**; sibling `buildPerCellValueListDeduped` **now `:2578`**) — both drifted +1 this window. The contract-side gap (no SDL default lift) is unbuilt. R413 (Done a prior window) bound parent-input VALUES cells through the column Converter DataType in this same cluster but did **not** address the SDL-default lift R103 targets. |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog.reflectTableMethod` (**base def `ServiceCatalog.java:566`, slot-types overload `:580`** — the file changed this window; R438 threaded slot types) with the strict `ClassName.equals` return-type gate following; `buildQueryTableMethodFetcher` **now at `TypeFetcherGenerator.java:1439`** (called **`:471`**) drifted this window. Neither `MethodRef.StaticOnly` nor `ReturnTypeRef.TableBoundReturnType` carries a type token yet. Re-anchor at the symbols. |
| **R236** validator-reference-candidate-hint-terminal-table | (Anchors are in the **item body**.) `classifyInputFieldInternal` (**now `BuildContext.java:2050`**) and the candidate-hint failure-aggregation (`candidateHint(c, catalog.columnSqlNamesOf(resolvedTable.tableName()))` at **`:2139`**) are in `BuildContext.java`. The hint still draws from the path-origin `resolvedTable.tableName()`, not the `@reference` terminal table (R380's Done note still lists R236 as an open sibling). Both anchors drifted +18 with the R438 cutover. |
| **R234** jooq-embedded-and-udt-input-backings | Valid, dormant Backlog stub gated behind the R222 collapse (Stages 5–7). Anchors to keep fresh: `JooqRecordInputType` is a live populated input arm (`GraphitronType.java`); `JooqTableRecordInputType` is a separate sibling; the proposed `BackingClass` family is still unbuilt (no `BackingClass.java`; `parentBackingClass` exists only as a component on `AccessorRef.java`). `depends-on: []` correct (R222 is a prose gate, not a hard edge). **Watch:** R431/R432 sit on the same `SourceKey`/leaf surface, and R438 (now In Review) reshaped the join-path step; re-check R234 when R431 lands and R438 reaches Done. |
| **R35** source-orientation-javadocs | LOC counts grossly stale (FieldBuilder cited "2 172", now **6 373**; TypeFetcherGenerator "1 646", now **6 673**); no `package-info.java` exists in the rewrite tree (sweep undone, re-confirmed). The architecture docs are the Diataxis `docs/architecture/` tree (`.adoc`), and `selection-parser-audit` no longer exists (R30 swept). Refresh the figures and re-point the doc links onto the Diataxis layout. |

**Re-confirmed Current (not flagged):** **R438** (`materialize-joinpath-facts`,
**In Review** this window), landed the two-axis `JoinStep` reshape (`Hop | LiftedHop`)
and absorbed R16; current. **R435** (`routine-table-node-composition`, **Ready**),
churned Ready → Spec → Ready with `enforced-by` sharpenings, `depends-on` R333/R438.
**R429** (`connection-transaction-lifecycle`, **Ready**), unchanged this window.
**R428** (`mcp-execute-query-in-process`, **Spec**), unchanged. **R381**
(`lsp-reference-path-authoring`, **Spec**), unchanged. **R314**
(`dissolve-reentry-leaves-dimensional-emit`, **Spec**), on R333's vocabulary,
`depends-on` R333/R431/R432. **R46** (`service-multi-tenant-fanout`, **Backlog**) and
**R45** (`tenant-routing-and-execution-input`, **Spec**), both on the R45/R429 tenant
substrate. **R13** (`faceted-search`, Ready) on the live `ConnectionPromoter` seam;
**R222** (`dimensional-model-pivot`, Spec) stays the umbrella (`depends-on: []`,
Stages 5–7 outstanding); **R333** (`coordinate-lowers-to-datafetcher-queryparts`, Spec);
**R346** (Ready), **R347** (In Progress), **R430** (Backlog), **R431** (Backlog),
**R432** (Backlog) all re-verified spec-forward, none under flag.

## Cross-cutting observations

1. **A structural-landing window, not a polish one.** After the prior
   bug-fix-and-polish pass, this window was one large structural landing (R438's
   two-axis `JoinStep` reshape, which absorbed and closed R16), one docs closure
   (R434's axiom restructure), and one dev-tooling closure filed-and-shipped
   in-window (R439's background web-env warm-up). Net flag count **held at 20**;
   current dropped **110 → 108** for the two board-removing closures on a board that
   fell 130 → 128. Zero Done tombstones for the sixth window running.
2. **Line-anchor drift returned across two large files.** Unlike the prior window,
   R438's cutover touched `BuildContext.java` (2 669 → 2 689), `FieldBuilder.java`
   (6 363 → 6 373), `TypeFetcherGenerator.java` (6 672 → 6 673) and
   `ServiceCatalog.java` (new `reflectTableMethod` overload). Every §C/§B row anchored
   to those files was re-anchored above; no flag's *substance* moved — only line
   numbers. Rows on the unchanged `GeneratorUtils.java` (578), `FetcherEmitter.java`
   (763), `MutationInputResolver.java` (679) and `ConnectionPromoter.java` carry over
   verbatim.
3. **The join-path decomposition landed; the source-side twin has not.** **R438**
   (`materialize-joinpath-facts`, step-side: `JoinStep` is now a sealed
   `Hop | LiftedHop` over `TableExpr`/`On`) reached In Review this window and closed
   R16 by typing the join-condition calling convention (`JoinConditionRef`,
   `On.Predicate.condition` vs `JoinStep.Hop.filter`). Its source-side twin **R431**
   (`decompose-sourcekey`) is still Backlog; the `SourceKey` surface itself is
   unchanged (Wrap + Cardinality still record components; the documented mapping still
   pins `SourceRowsCall → Wrap.ROW`). When R431 lands and R438 reaches Done, **R71,
   R234, R314, R432 and R435** all need re-checking.
4. **R438's cutover did not subsume any flagged item.** It reshaped the join model and
   typed the join-condition convention (closing the un-flagged R16), but the flags on
   `SourceKey`/leaf/source-endpoint territory (R71, R234) ride R431, and the flags on
   the touched files (R201, R202, R236, R240, R308, R92, R103) are distinct defects on
   distinct methods — verified at the symbol, not inferred from adjacency (see §B/§C
   notes).
5. **R222 remains the open umbrella** (Spec, `depends-on: []`), with its dedicated
   conformance analysis (`2026-07-04-r222-r333-conformance-analysis.md`). Stages 5–7
   (the `JooqRecordInputType` collapse R234 forward-dates, namespace collapse, directive
   narrowing closing R97) are still outstanding, consistent with R234 staying in §C.
6. **`classification-test-dsl-inventory.md`** is doubly stale (R299/R290 + the R316
   corpus recut) and still warrants the "superseded, historical" banner; it has **not**
   been added (left unedited here per scope).
7. **Cosmetic front-matter nits, none flag-worthy (carried over).** **R97** lacks
   `created:`/`last-updated:` header fields (an older item predating that convention).
   **R435** still has no `priority:` key (carried from its Backlog stub through this
   window's churn). Neither is a build or dependency risk.
8. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated placeholder,
   not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-08._
