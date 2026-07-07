# Roadmap staleness audit: 2026-07-07

A point-in-time review of every active roadmap item under [`roadmap/`](../)
against the **current** state of the codebase on `claude/graphitron-rewrite`
(HEAD `25d4998`, 2026-07-07). The goal is to find items whose premise no longer
holds: work already shipped, constructs renamed or removed, dependencies that
have since landed, or specs grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-07-06` staleness audit, which has been deleted;
only the latest staleness audit is retained. Four siblings in this directory are
**not** staleness audits and are left in place:

- `2026-06-16-source-operation-target-reframe.md` is the `(source, operation,
  target)` reframe analysis, the permanent lineage document for **R316** (Done).
- `2026-06-30-release-planning.md` is the first-release scoping working document,
  meant to be edited in place as scope iterates. It reads further behind again
  this window: on top of the closures the prior audits noted, **R433, R436 and
  R437** all reached Done since it was last touched, so its MUST/SHOULD tables lag
  further. Refreshing it is out of scope for this staleness pass.
- `2026-07-04-r222-r333-conformance-analysis.md` is the R222/R333 conformance
  analysis (`6131f99`), a companion to the R314/R333 design session. It is a
  targeted implementation-vs-spec conformance record, not a point-in-time
  staleness review; left in place.
- `classification-test-dsl-inventory.md` is R281's permanent corpus-retirement
  inventory, doubly stale (against R299/R290 and the R316 corpus recut). It still
  warrants a "superseded, historical" banner; that banner has **not** been added,
  left unedited here per scope (see observation 6).

## Changes since the 2026-07-06 audit

A moderate, **bug-fix-and-polish** window with three clean closures and one
structural-decomposition filing — a marked contrast with the previous
design/architecture-heavy pass. Two of the three closures were **filed and shipped
inside the same window** (R436, R437), so the board never carried them as
open work; the third (R433) was the In-Review principles-doc trim from the prior
snapshot. The rest of the activity was in-place plan rework on the connection/
identity seam (R429/R428) and the principles-doc restructure (R434), plus the
R435 routine-SDL item advancing to Ready behind a newly filed join-path
decomposition (R438).

**Terminal closures this window (Done, all self-deleted their files, none flagged,
all were current):**

- **R433** (`principles-doc-altitude-trim`) — was **In Review** at the prior
  snapshot; closed after a second gate caught and fixed a stale capitalized
  "Thirteen" count in `typed-rejection.adoc`.
- **R436** (`unsafe into() key extraction collides with multiset aliases`) — **filed
  and closed in this window** (Backlog → Spec → Ready → In Progress → In Review →
  Done). It fixed unsafe `TableRecord` key extraction colliding with multiset
  aliases and routed pre-dispatch throws through the `ErrorRouter`; it landed the
  `AliasKeyColumnCollisionValidationTest`, edits to `TableRef.java`,
  `ArgCallEmitter.java` and `TypeFetcherGenerator.java`.
- **R437** (`shape-aware create<Record> @service helper dedup`, the R311 bug) —
  **filed and closed in this window**. It landed the new
  `JooqRecordHelperNames.java` (211 lines) and reworked
  `JooqRecordInstantiationEmitter.java`, `ServiceMethodCallEmitter.java`,
  `TypeClassGenerator.java` and `GeneratorUtils.java` to deduplicate the
  shape-aware `create<Record>` service helpers.

Three closures total; none was under flag, and each was the top of `changelog.md`
at close.

**New items on the board (all filed this window):** **R436** and **R437** (both
filed → Done in-window, see above) and **R438** `materialize-joinpath-facts`
(**Backlog**, `depends-on: []`, `bucket: structural`) — the eager, mechanical
materialization of R333's join-path corner: `JoinStep` as `(tableExpr target, on)`,
explicitly framed as the **join-path twin of R431's source-side `SourceKey`
decomposition**. Filed 2026-07-06, spec-forward, current/fresh.

**Transitions this window:**

- **R435** (`routine-table-node-composition`) advanced **Backlog → Spec → Ready**.
  Its body was restructured to read top-to-bottom and its `depends-on` is now
  `[coordinate-lowers-to-datafetcher-queryparts, materialize-joinpath-facts]`
  (R333/R438); both live. A deliberate promotion, not staleness; current.
- **R429** (`connection-transaction-lifecycle`, **Ready**) was reworked in place
  (multiple commits): rewritten around a runtime split and database-mounted session
  identity, folding the principles-architect self-review to make the change additive,
  and documenting production of the claims payload from the MP JWT. It signed off
  Ready → Spec → Ready again on an independent review; ends **Ready**. Deliberate
  rework, not staleness; current.
- **R428** (`mcp-execute-query-in-process`, **Spec**) was re-synced with R429's
  redesign — identity payload, fail-loud posture, security posture — and stays
  `depends-on: [connection-transaction-lifecycle]`. Current.
- **R434** (`principles-doc-axiom-restructure`) churned Spec → Ready → **Spec**
  (reopened because the `ArgumentRef` exemplar is on the demolition path): reframed
  around single-sourcing as axiom 2 (not "never store a derived fact"), the FCIS +
  normalization spine at the ingress, the ephemeral-hierarchy corollary onto
  fact-gathering, and consolidation of the drift smell to one named home. Ends
  **Spec**; deliberate rework, current.
- **R381** (`lsp-reference-path-authoring`, **Spec**) took a small additive note
  (+8 lines) about the R435 resolved-chain inlay/hover display rung. Stays Spec,
  current.
- **R347** (`lsp-structural-consolidation`) holds at **In Progress**. **R222** and
  **R333** are unchanged from the prior snapshot; both remain **Spec** and current.

**Board accounting.** **130 item files** today, unchanged in count from the prior
audit (3 filed R436/R437/R438 + 3 closed R433/R436/R437 = net zero). Status
distribution: **108 Backlog, 17 Spec, 4 Ready, 1 In Progress (R347); zero In Review,
zero Done** (tombstone-free for the fifth window running). The two status deltas
from the prior audit are **Ready 3 → 4** (R435 arrived) and **In Review 1 → 0**
(R433 closed). A non-recursive `^status: Done` grep over `roadmap/*.md` returns
nothing. No duplicate `id:`; `next-id: R439` (in `changelog.md`) with max allocated
id **R438**. A `depends-on` sweep over all 130 files found **no dangling slugs**;
R435's new `materialize-joinpath-facts` edge resolves to R438. The board is
structurally clean.

**Net effect on flag counts: 20 flagged, 110 current** (unchanged from the prior
window's 20/110). Flag composition holds at **(1 / 6 / 13)**: §A is R263 alone; §B
holds its six; §C holds its thirteen. Every closure and every new item this window
landed on current/fresh code; none was under flag. The R437 create-`<Record>`
helper dedup and the R436 key-extraction fix were **adjacent to but distinct from**
the flagged §C items on the same files (R308 list-payload N+1, R242 DML positional):
neither closure subsumed either flag (see §C notes). Every remaining flagged item
was re-verified against current HEAD and **every one still reproduces**.

## Scope and method

All **130** `R<n>` item files were reviewed (plus the non-item
`inference-axis-coverage.adoc` placeholder, correctly excluded). For each item the
targets it names (classes, directives, methods, packages) were located in the current
tree under `graphitron/src/main/java/no/sikt/graphitron/rewrite/`, the described problem
was checked for whether it still reproduces, and the changelog was scanned for the item's
`R<n>` and key terms to catch work that shipped without the item being closed.

The structural landings the prior audits relied on all still hold: **R276**
(`ResultType` is a 4-arm seal; `PojoResultType` permits only `Backed`), **R290**,
**R305** (`SingleRecordTableField` gone; live carrier `RecordTableField` in
`model/ChildField.java`), and **R316** (the `carrier × intent × mapping` field model
gone, replaced by `(source, operation, target)`).

**The `SourceKey` decomposition still has not landed.** It remains a filed, sequenced
item (**R431**) with **R432** depending on it and **R314** re-specced to depend on
both. This window added the join-path twin **R438** (`materialize-joinpath-facts`),
which decomposes what the *step* conflates the way R431 decomposes what the *source*
endpoint conflates. When R431 (and now R438) land, **R71, R234, R314, R432 and R435**
all need re-checking.

**Line-anchor drift this window: confined to one generator file.** Unlike the prior
"zero drift" window, this window **did** touch a large generator file:
**`TypeFetcherGenerator.java` grew 6 588 → 6 672** (+84 net; the R436 and R437
landings both edited it). The other two large files are **byte-identical**:
**`FieldBuilder.java` 6 363** and **`BuildContext.java` 2 669** (neither is in the
`87e4300..HEAD` diff). Additionally **`GeneratorUtils.java` grew to 578 lines**
(R437's create-`<Record>` dedup). Consequences:

- **§C/§B rows anchored to `FieldBuilder.java` and `BuildContext.java` carry over
  verbatim** — spot-verified: `resolvePayloadConstructionShape`
  `FieldBuilder.java:494`, `tryMutableBean` `:559`, `checkServiceReturnMatchesPayload`
  `:3038`, `checkErrorTypeSourceAccessors` `:2780`, `classifyInputFieldInternal`
  `BuildContext.java:2032` / candidate-hint `:2121`, `resolveDecodeHelperForTable`
  `:2430` — all held exactly.
- **Rows anchored to `TypeFetcherGenerator.java` were re-anchored** (see §B/§C for
  the refreshed line numbers): `payloadFactoryLambda` `:6485 → :6539`,
  `payloadFactoryLambdaSetters` `:6502 → :6556` (R201); `validatorPreStep`
  `:1984 → :1983`, seam call `:1852 → :1851` (R92); `buildPerCellValueList`
  `:2353 → :2404`, `buildPerCellValueListDeduped` `:2526 → :2577` (R103);
  `buildQueryTableMethodFetcher` def `:1439 → :1438`, called `:462 → :470` (R240);
  `STUBBED_VARIANTS` declared `:300` (held), single entry `Map.entry(...)`
  `:311 → :308` (R24).
- **`GeneratorUtils.buildFkRowKey` moved `:241 → :274`** (called `:212 → :245`) for
  R180; `FetcherEmitter.propertyOrRecordValue` is still absent (R180's premise holds).

The other §C-anchored files (`FetcherEmitter.java`, `MutationInputResolver.java` (679),
`ConnectionPromoter.java`, `ServiceCatalog.java`, `InputFieldResolution.java`) are
unchanged this window; their anchors carry over.

**Result: 20 items flagged, 110 current.**

## A. Obsolete: should leave the active roadmap (1)

| Item | Status | Action | Why |
|---|---|---|---|
| **R263** decode-helper-typename-first-resolution | Backlog | **Discard as speculative** (re-open only if a MUST-route `@nodeId(typeName:)` caller appears) | Unchanged from the prior audit, re-verified verbatim on current HEAD. R377 settled the open question the discard hedged on. `resolveDecodeHelperForTable` is defined `BuildContext.java:2430` (held exactly); it resolves through the `@node` `NodeIndex`, returns `null` on multi-node ambiguity (a validate-time rejection, not the silent `decode<firstType>` the finding described), and falls back to the `typeId` suffix only when no `@node` backs the table. Its callers (`BuildContext.java:2242`/`:2380`, both held) all pass the table name as primary; **none** routes an authoritative `@nodeId(typeName:)`. The premise survives only in spirit and no consumer needs it. Re-anchor onto the R377 body if capturing the finding in `changelog.md`, then discard. |

## B. Outdated: needs re-spec (premise or targets materially changed) (6)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. All six carried over
from the prior audit and were re-verified this window; **every one still reproduces**.
The three anchored to `FieldBuilder.java`/`BuildContext.java` hold verbatim; the ones
anchored to `TypeFetcherGenerator.java`/`GeneratorUtils.java` were re-anchored
(that file grew this window).

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; still the sharpest.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm R276 deleted: `ResultType` is a 4-arm seal, `PojoResultType` permits only `Backed`. Its primary migration target `FetcherEmitter.propertyOrRecordValue` **still does not exist** (zero hits; the spec's own `:802`/`:131` anchors are dead); only the `GeneratorUtils.buildFkRowKey` half of the duplication survives — now at **`generators/GeneratorUtils.java:274`, called `:245`** (moved from `:241`/`:212` when R437's create-`<Record>` dedup grew that file to 578 lines). The `.md` still describes the deleted five-arm / `PojoResultType.NoBacking` model throughout. Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. |
| **R71** recordn-key-parity-lifter-and-non-jooq-record-parents | Backlog | **Symbol re-anchor; substance confirmed still future.** Every symbol the mechanism names is dead: no `BatchKeyLifterDirectiveResolver`, no `BatchKey`/`LifterRowKeyed`/`RowKeyed` types anywhere. The live surface is `model/LifterRef.java`, routed through `@sourceRow` and `SourceKey.Reader.SourceRowsCall(LifterRef)`. `SourceKey` still carries `wrap`+`cardinality` and the compact constructor still pins `SourceRowsCall → Wrap.Row`, the exact Row-only asymmetry R71 wants to remove. Re-anchor on `LifterRef`/`SourceRowsCall`/`Wrap`. The decomposition R71 rides is filed as **R431**; wire R71's `depends-on`/prose to R431 on its next touch. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (Done, file absent), so the "R58 is currently *In Review* … if it reverts" framing (line 21) is false and should be removed. Carriers are still String-flattened (`ParsedPath(…, String errorMessage, …)` at `BuildContext.java:990` and `InputFieldResolution.Unresolved(…, String reason, …)` in `InputFieldResolution.java` both remain), so the carrier-audit body stays valid; `depends-on: []` correct. Lightest re-spec in this section: strike the reversion hedge. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)" (front-matter `title:` and README); **R94 shipped** (Done, slug `emit-input-records`, file absent). Blocker cleared, drop the blocked framing. R94's Done note handed the real remaining dependency to **R98** (`multi-source-input-validation`, re-confirmed Backlog, file present), so re-point the dependency there and change the title "(R94-blocked)" to "(R98-blocked)". |
| **R24** nodeidreferencefield-join-projection-form | Backlog | Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **still does not exist** (dispatch is now `FetcherEmitter.bind`/`bindRaw`; `dataFetcherValue` survives only in stale Javadoc; the body's "lines 140-162" are dead), and the carrier in the **item title**, `NodeIdReferenceField`, was renamed by R50 to `Column`/`CompositeColumnReferenceField` (zero `NodeIdReferenceField` hits). The surviving "not yet implemented" stub is `ChildField.CompositeColumnReferenceField` and it is the **single** entry in `TypeFetcherGenerator.STUBBED_VARIANTS` (declared `:300` (held), `Map.entry(...)` now `:308`), whose `planSlug` is R24's own slug, confirming the release-planning "one tracked stub" claim. Work is valid and unbuilt, but the title symbol + method names need a full re-anchor. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale, and now **staler**: body says "1 646 lines" and "~30 private methods"; `TypeFetcherGenerator.java` is **6 672** this window (~4.05×, up from 6 588). Lightest-substance re-spec in this section: refresh the LOC/method-count figures and re-survey the cut against the real file, or better, stop hard-coding a count and state "measure at execution time." |

## C. Outdated: update references only (work valid, refs stale) (13)

Substance intact; only paths, line numbers, or dependency tense drifted. **This window
the drift is confined to `TypeFetcherGenerator.java` (6 672) and `GeneratorUtils.java`
(578); rows on `FieldBuilder.java` (6 363) and `BuildContext.java` (2 669) carry over
verbatim.** The recurring code-side root cause is unchanged: the three large
generators standing at ~2.6 to 4.05× the sizes the older specs cite.

| Item | Stale reference |
|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Premise live and unbuilt: `resolvePayloadConstructionShape` on the `PayloadClass` arm (`FieldBuilder.java:494`; called `:2576`); the mutable-bean logic in `tryMutableBean` (`:559`), whose setter-match arm builds `SetterBinding`s off the raw SDL field name with **no `@field` read**. The emitter halves are `TypeFetcherGenerator.payloadFactoryLambda` (**now `:6539`**) and `payloadFactoryLambdaSetters` (**now `:6556`**) — both drifted +54 when TFG grew this window. Body already frames it as "the output-side mirror of R200"; FieldBuilder anchors held exactly. |
| **R308** service-list-payload-arrival | The `@service` list-payload N+1 is real and unbuilt (`checkServiceReturnMatchesPayload` `FieldBuilder.java:3038`, called `:2999`; still admits `List<Payload>`, held exactly). The body still describes the no-DataLoader shape via `SingleRecordTableField`, which R305 **deleted**; the live carrier `RecordTableField` is in `model/ChildField.java`. Strip the "today" framing; bump `last-updated`. **R437** (`shape-aware create<Record> @service helper dedup`, Done this window) touched the same `@service` emission surface but was a helper-dedup fix, **not** the list-payload N+1 R308 targets, so R308 stands. (`depends-on` already `[]`.) |
| **R202** honor-field-directive-in-error-type-source-accessors | Premise live and unbuilt: `checkErrorTypeSourceAccessors` (`FieldBuilder.java:2780`, called `:2555`) calls `ClassAccessorResolver.resolve(...)` with the raw SDL name and **no `@field` read**, exactly the gap R202 describes. R200's Done note names R202 as a remaining `@field`-symmetry sibling. Anchors held exactly (FieldBuilder unchanged). Re-anchor at the symbol. |
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck as "Obsolete (R287)"; body re-based onto R305's re-fetch substrate; `depends-on` `[]`. `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` (`FetcherEmitter.java:631`, called `:373`, unchanged this window) and the `[ID]`-reject scoped to `CarrierFamily.DML` in `BuildContext.java` still need re-anchoring at the symbol; drop the stale "3 sites" count. **R436** (`unsafe into() key extraction`, Done this window) edited the same DML/positional territory in `TypeFetcherGenerator.java` but fixed the multiset-alias key-extraction collision, **not** the positional input/output alignment R242 targets; R242 stands. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` (**679**, unchanged this window) substance holds (emit-half still unbuilt). The non-`@table` arg rejection (`foundTia.argCondition().isPresent()`) and the `@condition(override:true)` admission gate (`ARG_OVERRIDE`) hold at their prior lines. Body cites older ranges; re-anchor at the symbols (drift is minimal here). |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`, under the **Diataxis `docs/architecture/` tree** R182 introduced, not the old flat `docs/` dir. The two cited sibling docs (`docs-as-index-into-tests.md`, `rewrite-docs-entrypoint.md`) do not exist under any extension or location. Relocate onto the Diataxis layout or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (absent) and stale `ConnectionSynthesis` naming. The live seam is `ConnectionPromoter.synthesiseForField` / `.rebuildAssembledForConnections` (`ConnectionPromoter.java`, both methods present in the javadoc + body, file unchanged this window); re-anchor there and fix the dangling doc link. |
| **R92** catalog-check-constraint-validation | `validatorPreStep` is defined **`TypeFetcherGenerator.java:1983`** (seam call site **`:1851`**) — both drifted −1 when TFG grew; `DefaultValidatorHolder`/`getValidator` live in `generators/util/`. No `CheckRecognizer`/`findCheckConstraints` exists; the R12 §5 validator seam this builds on is present as described. |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue` emission is clustered in `buildPerCellValueList` (**now `TypeFetcherGenerator.java:2404`**; sibling `buildPerCellValueListDeduped` **now `:2577`**) — both drifted +51 this window. The contract-side gap (no SDL default lift) is unbuilt. R413 (Done a prior window) bound parent-input VALUES cells through the column Converter DataType in this same cluster but did **not** address the SDL-default lift R103 targets. |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog.reflectTableMethod` (`ServiceCatalog.java:565`, unchanged this window) has two overloads; the strict `ClassName.equals` return-type gate follows; `buildQueryTableMethodFetcher` **now at `TypeFetcherGenerator.java:1438`** (called **`:470`**) drifted this window. Neither `MethodRef.StaticOnly` nor `ReturnTypeRef.TableBoundReturnType` carries a type token yet. Re-anchor at the symbols. |
| **R236** validator-reference-candidate-hint-terminal-table | (Anchors are in the **item body**.) `classifyInputFieldInternal` (`BuildContext.java:2032`) and the candidate-hint failure-aggregation (`candidateHint(c, catalog.columnSqlNamesOf(resolvedTable.tableName()))` at `:2121`) are in `BuildContext.java`. The hint still draws from the path-origin `resolvedTable.tableName()`, not the `@reference` terminal table (R380's Done note still lists R236 as an open sibling). Anchors held exactly (BuildContext unchanged). |
| **R234** jooq-embedded-and-udt-input-backings | Valid, dormant Backlog stub gated behind the R222 collapse (Stages 5–7). Anchors to keep fresh: `JooqRecordInputType` is a live populated input arm (`GraphitronType.java`); `JooqTableRecordInputType` is a separate sibling; the proposed `BackingClass` family is still unbuilt (no `BackingClass.java`; `parentBackingClass` exists only as a component on `AccessorRef.java`). `depends-on: []` correct (R222 is a prose gate, not a hard edge). **Watch:** R431/R432/R438 sit on the same `SourceKey`/leaf/join-path surface; re-check R234 when they land. |
| **R35** source-orientation-javadocs | LOC counts grossly stale (FieldBuilder cited "2 172", now **6 363**; TypeFetcherGenerator "1 646", now **6 672**); no `package-info.java` exists in the rewrite tree (sweep undone, re-confirmed). The architecture docs are the Diataxis `docs/architecture/` tree (`.adoc`), and `selection-parser-audit` no longer exists (R30 swept). Refresh the figures and re-point the doc links onto the Diataxis layout. |

**Re-confirmed Current (not flagged):** **R435** (`routine-table-node-composition`,
**Ready** this window), restructured and wired `depends-on` R333/R438. **R438**
(`materialize-joinpath-facts`, **Backlog**, `depends-on: []`), freshly filed as R333's
join-path decomposition and the twin of R431. **R429**
(`connection-transaction-lifecycle`, **Ready**), reworked around the runtime split and
database-mounted identity. **R428** (`mcp-execute-query-in-process`, **Spec**),
re-synced with R429. **R434** (`principles-doc-axiom-restructure`, **Spec**), reopened
and reframed around single-sourcing. **R381** (`lsp-reference-path-authoring`, **Spec**),
took the R435-display note. **R314** (`dissolve-reentry-leaves-dimensional-emit`,
**Spec**), on R333's vocabulary, `depends-on` R333/R431/R432. **R46**
(`service-multi-tenant-fanout`, **Backlog**) and **R45**
(`tenant-routing-and-execution-input`, **Spec**), both on the R45/R429 tenant
substrate. **R13** (`faceted-search`, Ready) on the live `ConnectionPromoter` seam;
**R222** (`dimensional-model-pivot`, Spec) stays the umbrella (`depends-on: []`,
Stages 5–7 outstanding); **R333** (`coordinate-lowers-to-datafetcher-queryparts`, Spec);
**R346** (Ready), **R347** (In Progress), **R430** (Backlog), **R431** (Backlog),
**R432** (Backlog) all re-verified spec-forward, none under flag.

## Cross-cutting observations

1. **A bug-fix-and-polish window, not a design one.** After the prior
   design/architecture-heavy pass, this window was three clean closures (R433 doc
   trim; R436 key-extraction collision fix; R437 create-`<Record>` helper dedup) plus
   in-place rework on R429/R428/R434 and the R435 promotion. Net flag count **held at
   20/110** on a board that stayed at 130. Zero Done tombstones for the fifth window
   running.
2. **Line-anchor drift returned, but narrowly.** Unlike the prior "zero drift" pass,
   the R436 and R437 landings grew `TypeFetcherGenerator.java` (6 588 → 6 672) and
   `GeneratorUtils.java` (→ 578). Every §C/§B row anchored to those two files was
   re-anchored above; rows on the byte-identical `FieldBuilder.java` (6 363) and
   `BuildContext.java` (2 669) carry over verbatim. No flag's *substance* moved —
   only line numbers on two files.
3. **The `SourceKey`/join-path decomposition surface grew a second filed item.** R431
   (`decompose-sourcekey`, source-side) is now joined by **R438**
   (`materialize-joinpath-facts`, step-side: `JoinStep` as `(tableExpr target, on)`),
   its explicit twin, with **R432** and **R314** already sequenced on R431 and **R435**
   now depending on R438. The `SourceKey` surface itself is unchanged (Wrap +
   Cardinality still record components; compact constructor still pins
   `SourceRowsCall → Wrap.Row`). When R431/R438 land, **R71, R234, R314, R432 and R435**
   all need re-checking.
4. **Two same-file closures did not subsume the flagged work on those files.** R437
   (`@service` helper dedup) and R308 (`@service` list-payload N+1) touch the same
   surface; R436 (into() key extraction) and R242 (DML positional alignment) touch the
   same surface. In both cases the closure fixed a distinct defect and left the flagged
   gap standing — verified at the symbol, not inferred from adjacency (see §C notes).
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
   window's promotion to Ready). Neither is a build or dependency risk.
8. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated placeholder,
   not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-07-07._
