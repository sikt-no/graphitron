# Roadmap staleness audit — 2026-06-11

A point-in-time review of every active roadmap item under
[`graphitron-rewrite/roadmap/`](../) against the **current** state of the
codebase on `claude/graphitron-rewrite`. The goal is to find items whose premise
no longer holds: work already shipped, constructs renamed or removed,
dependencies that have since landed, or specs grown stale enough to mislead an
implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-06-10` staleness audit, which has been deleted —
only the latest audit is retained.

## Changes since the 2026-06-10 audit

Seventy-six commits landed between the prior audit (`0f2db30`, committed
2026-06-10 03:12) and this one (`6e3ef42`, 2026-06-10 22:01). They move the
staleness picture as follows:

- **R275 closed and deleted (clean self-close) — removes a Section C flag.**
  `source-record-carrier-service-error-channel` (error channel + data projection
  for source-record-carrier `@service` mutations) finished its reopened scope
  (`5fa830e` + `32d7e0d` + `77573c4`, red tests `24387b2`, comment refresh
  `905f9ef`) and reached **Done** with an independent In Review sign-off
  (`1de6e48`). The file was deleted on the Done transition. The prior audit
  flagged it in §C (`NoBacking`-removal prose pointing at the deleted R276 plan
  slug); that flag retires with the item. **No longer on the board.**
- **R287 implementation landed (In Review).** `delete-rejects-table-return`
  transitioned Backlog → Spec → Ready → **In Review** and its fix shipped
  (`6e3ef42`): the DELETE → `@table` carrier is gone. Verified live —
  `ChildField.SingleRecordTableFieldFromReturning` and
  `FetcherEmitter.buildSingleRecordTableFromReturningFetcherValue` now return
  **zero** source hits (the emitter method was removed, −95 lines), and
  `MutationInputResolver.validateReturnType` rejects a DELETE → `@table` return
  (+6 lines). The `Projected*` `DmlReturnExpression` arms remain for
  INSERT/UPDATE/UPSERT. The prior audit's forward-looking "Resolved" parenthetical
  now matches the tree. **Current, not flagged.**
- **R291 born and closed (clean self-close).** `strip-internal-directives-from-published-sdl`
  was filed Backlog (`bd643cb`), advanced Spec → Ready → In Progress, shipped
  (`b2c0895`: the published SDL no longer carries Graphitron-internal directives
  and support types), and reached **Done** (`80623b0`). File deleted on Done — no
  orphan.
- **R253 closed as subsumed (clean removal).** `pipeline-runtime-sdl-parity-test`
  was closed as subsumed by R291 (which implemented R253's Route 3 at the print
  seam) and deleted at `80623b0`. The closing author folded the closure into the
  same changelog line as R291's landing — no orphan.
- **R292 born and closed (clean self-close).** `descriptions-on-synthesised-connection-edge-pageinfo`
  reached **Done** (`4102697` + self-review `7a34e4e`, sign-off `97951ed`):
  synthesised Relay boilerplate now carries canonical graphql-relay-js
  descriptions from the single `ConnectionPromoter` synthesis site, landing on
  both the SDL-emit and runtime-rebuild seams. File deleted.
- **R294 born and closed (clean self-close).** `fixture-warnings-as-errors`
  reached **Done** (`36926c4` + `aa3c772`, sign-off `e1ad625`): the
  `@asConnection` hygiene advisory now rides the unified `ctx.addWarning` →
  `schema.warnings()` channel (the `ASCONNECTION_HYGIENE_LOG` SLF4J category is
  retired), redundant `@record`/`@splitQuery` fixture directives were swept, and
  `FixtureWarningsGateTest` pins the example schema's warning multiset. File
  deleted.
- **R295 born and closed (clean self-close).** `connection-synthesis-inherits-federation-tags`
  reached **Done** (`fae7c6f` + `1fdcf18`, sign-off `3786e43`): synthesised
  Connection/Edge/PageInfo type declarations inherit the carrier field's
  federation `@tag` applications. File deleted. It spawned two current Backlog
  follow-ups, R297 and R298 (below), and the changelog records the residual
  type-level-only-tags risk it could not close in-session.
- **R290 added (current).** New Backlog item: `datafetcher-field-dimensional-slots`
  — R222's Stage 3 spin-out (the field-side half of the dimensional pivot), which
  dissolves the fused `QueryField`/`MutationField`/`ChildField` cross-product into
  derived producer/mechanism slots and deletes R281's throwaway `LeafTupleAdapter`.
  Filed `b1bdebc`, blocked by `classification-test-dsl` (R281) and
  `dimensional-model-pivot` (R222). Authored against verified-live constructs.
  Current, not flagged.
- **R293 added (current).** New item `build-warning-cleanup` — per-category
  cleanup plan for the remaining build-time warnings; advanced Backlog → Spec →
  **Ready**. Its inventory citations were corrected mid-cycle (`bb2a6f8`) and the
  strict-mode/`WarningKind` scope was carved out to R296. Current, not flagged.
- **R296 added (current).** New Backlog **Validation** item `deprecated-usage-warnings`
  — emit `BuildWarnings` when a schema uses deprecated directives/arguments,
  riding the R294 channel. Renumbered from an R295 ID collision (`83032ad`), and a
  follow-up (`a1bb5df`) corrected its body to stop claiming R294 ships strict mode.
  Current, not flagged.
- **R297 added (current).** New Backlog item `collapse-connection-shareable-boolean`
  — after R295, the `shareable` boolean on `ConnectionType`/`EdgeType`/`PageInfoType`
  is the asymmetric survivor (a second representation of `@shareable`, redundant
  with `schemaType()`). Targets verified live: the `shareable` component is read
  only by the `pageInfoShareable |=` fold in `ConnectionPromoter` (`:142`, `:148`),
  and the "recorded on the record but not read at emission" comment sits at
  `ConnectionPromoter.java:189`. Current, not flagged.
- **R298 added (current).** New Backlog item `federation-tag-first-client-contract-check`
  — the residual federation-contract verification R295 explicitly could not run
  in-session (build a real Apollo contract and confirm type-level-only `@tag`s
  compose). Inherently current; carries R295's deferred risk. Not flagged.
- **State transitions (none introduces new staleness):** R200 Spec → **Ready**
  (`honor-field-directive-in-inputbeanresolver`, `9508d92`); R281 In Progress →
  **In Review** (`classification-test-dsl`, slices 2–3 + hardening complete,
  `aa7a45e`). Both read current.

Net effect on the counts below: one previously-flagged item left the board
cleanly (**R275**, Done + deleted), five items were born and closed within the
window without ever appearing on a flag list (R291, R292, R294, R295, plus the
R253 subsumption), and five current items were added (R290, R293, R296, R297,
R298). The Section C flag count drops by one (R275 retired); no new flags were
introduced. Two surviving §C flags (R242, R245) drifted **further** this cycle
under the R287 restructuring of `MutationInputResolver.java` / `FetcherEmitter.java`.

## Scope and method

All **114** entries were reviewed (113 `R<n>` item files plus the non-item
`inference-axis-coverage.adoc` placeholder). For each item the targets it names
(classes, directives, methods, packages, modules) were located in the current
tree, the described problem was checked for whether it still reproduces, and the
changelog was scanned for the item's `R<n>` and key terms to catch work that
shipped without the item being closed.

The classifier landing that dominated the prior two audits still holds: **R276**
(`record-binding-reflection-only`, Done) eliminated
`GraphitronType.PlainObjectType`, collapsed `PojoResultType` to a single `Backed`
arm — **deleting `PojoResultType.NoBacking`** — and **deleted
`TypeClassification.UnbackedPojoResult`**. Re-verified this cycle:
`GraphitronType.java:117` (`sealed interface PojoResultType extends ResultType …`),
and a repo-wide `UnbackedPojoResult` / `PlainObjectType` search returning zero
hits.

**Result: 28 items flagged, 85 current.** Line numbers cited below are as of the
review date and will themselves drift.

## A. Obsolete — should leave the active roadmap (3)

These should be deleted. Each was superseded or discarded by a sibling item that
has already reached Done; because the closure came from the sibling rather than a
self-transition, no author ran the file-deletion sweep. (All three are unchanged
since the prior two audits — still stranded.)

| Item | Status | Action | Why |
|---|---|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Backlog | Discard/delete | R244 (Done) changelog: "Supersedes R241, **moots R201**." The root `@service` outcome path is migrated to typed `Outcome<T>`; the `PayloadConstructionShape` family the bug targeted is retired. (Confirmed: file still present, `status: Backlog`.) |
| **R146** mutation-cardinality-safety-unique-index | Backlog | Discard/delete | R246 (Done) changelog: "**Absorbs R146 (PK-or-UK coverage, discarded)**." `JooqCatalog.candidateKeys` + `UpdateRowsWalker` PK-or-UK matching shipped. The Backlog body still reads as live work. (Confirmed: file still present, `status: Backlog`.) |
| **R30** selection-parser-audit | **Done** | Delete the file | Status is Done (confirmed in front-matter). Per workflow, Done items are deleted. The file has outlived its closure — stranded across three audits now. |

## B. Outdated — needs re-spec (premise or targets materially changed) (12)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer reading it. Re-spec before the next state transition.

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **High priority.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm is the fetcher-only fallback the design centred on (`ColumnReadShape` sketch lines 97/119/142/149/153 all name `NoBacking`). R276 deleted `PojoResultType.NoBacking` (re-confirmed: `PojoResultType` permits only `Backed`). Re-spec against the collapsed `Backed`-only model; the "NoBacking is excluded upstream" guard arms it cites no longer have a type to exclude. |
| **R13** faceted-search | Spec | Central seam `ConnectionSynthesis.buildPlan()` gone (synthesis now in `ConnectionPromoter`); `FieldWrapper.Connection` is a 2-arg record without `connectionName`. Phases 2–4 must be rewritten. |
| **R71** recordn-key-parity-lifter | Backlog | R110 replaced `@batchKeyLifter` with `@sourceRow`; `BatchKeyLifterDirectiveResolver` → `SourceRowDirectiveResolver` (re-confirmed: only `SourceRowDirectiveResolver.java` exists, zero `BatchKeyLifterDirectiveResolver` hits), `LifterRowKeyed` split into `Lifter{Leaf,Path}Keyed`. Title and body name a removed directive. |
| **R46** service-multi-tenant-fanout | Backlog | R45 records that R190 sealing the interface dissolved R46's design (`ContextValueRegistration<FanOut>` no longer exists); body still links the dead `typed-context-value-registry.md` slug (renamed to `tenant-routing-and-execution-input.md`). |
| **R19** history-squash | Ready | Every SHA/count is an April-2026 snapshot: the metrics table reads "595 (as of 2026-04-24)" and the prose targets "566 commits"; branch is now **2565 ahead** of `origin/main` (up from 2488 at the prior audit — +77 in 24h, still drifting daily). All numbers/SHAs/drop-lists need regeneration before this can be executed. |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | Premise says R222 *already* collapsed `JooqRecordInputType` with a specific rejection — but `JooqRecordInputType` still exists (`GraphitronType.java:340`), the rejection string ("…but not a TableRecord…") is absent from the code, and R222 is still **Spec**. Forward-dated premise; rephrase as conditional-on-R222 or fold into R222. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines"; `TypeFetcherGenerator.java` is now **5 379** (flat this cycle — R287 net-neutral on it; ~3.3x the cited figure). Re-evaluate decompose-vs-document against real size. |
| **R263** decode-helper-typename-first-resolution | Backlog | Trap still exists, but motivating dependency R195 shipped by sidestepping it — the "next caller" justification is now hypothetical. Re-justify or drop (priority-3). |
| **R269** nullable-to-one-record-into-npe | Spec | R268 shipped (helpers already take a `sourceExpr`), and R271 (dunder retirement) shipped, renaming `__elt`→`element` — so the spec's `__elt`/`__k` code snippets (lines 22-23, 29) are doubly stale. Confirmed it is still the only live spec quoting dunder locals. Strip the snippets and the historical "split out of R268" framing. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (`R58TypedRejectionPipelineTest` in tree) — the "R58 is currently In Review… if it reverts" framing is false. Carriers still String-flattened, so the body stays valid; remove the stale dependency prose. |
| **R261** wire-coercion-cast-guard | Backlog | The specific reported instance (`(SakRecord) raw.get(...)`) is handled by shipped R195 (`CallSiteExtraction.NodeIdDecodeRecord`). Update the coordination framing; the raw-cast invariant is still unbuilt. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)"; R94 shipped (confirmed in changelog). Blocker cleared — drop the blocked framing and treat as directly actionable (R94's Done note explicitly hands this item its annotated walk target). |

## C. Outdated — update references only (work valid, refs stale) (13)

Substance intact; only paths, line numbers, or dependency tense drifted. Two root
causes dominate: a `docs/*.md` → `docs/*.adoc` migration, and `FieldBuilder.java`
(now **5 656**, +112 this cycle) / `TypeFetcherGenerator.java` (now **5 379**,
flat) standing at ~2.5–3.3x the sizes the older specs cite.

| Item | Stale reference |
|---|---|
| **R245** wire-condition-emit-on-mutations | R287 (`6e3ef42`) added 6 lines to `MutationInputResolver.java`, shifting the anchors *again*: argument-level `@condition` rejection (`if (foundTia.argCondition().isPresent()) …Rejection.structural`) is now at **`:410-411`** (body cites "line 446" / "lines ~438-440"), and input-field-level `@condition(override:true)` admission is now at **`:457-463`** (body cites "lines ~482-498" / "`:482-498`"). The emit-half is still unbuilt (substance valid); only the anchors drifted. A standing reminder that anchoring specs to symbol names rather than line numbers would stop the recurrence. |
| **R242** dml-payload-positional-alignment | **Partially refreshed this cycle.** The DELETE-Table-arm steps were correctly struck through and marked "Obsolete (R287)" (the carrier and `buildSingleRecordTableFromReturningFetcherValue` are gone). But the surviving line refs drifted: `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` is cited at "lines 561-610" / "lines 129…", actual is **`:822`**; and the `[ID!]`-admission diagnostic is cited at "`BuildContext.java:680-699`", actual is **`~:620-625`**. Refresh the Id-arm anchors. |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted "firstclass-connection-types" item and stale `ConnectionSynthesis` naming (now `ConnectionPromoter`). |
| **R95** routines-as-data-model-citizens | Claims `RoutineReflection` "already lives in graphitron-common"; it only exists in the legacy `graphitron-java-codegen` module (confirmed: zero hits under `graphitron-common`). |
| **R99** lsp-submodule-sibling-classpath | Path `graphitron-rewrite/graphitron-maven/…` is actually `graphitron-maven-plugin/…` (confirmed: only `graphitron-maven-plugin` exists). |
| **R267** nodeid-encoder-deprecated-convert | Bug live, but path is `generators/util/NodeIdEncoderClassGenerator.java`, not `generators/` (confirmed). |
| **R92** catalog-check-constraint-validation | Line refs into `TypeFetcherGenerator` drifted (file now 5 379 lines). |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue`/`defaulted()` anchor lines stale; relocate before Spec. |
| **R24** nodeidreferencefield-join-projection-form | `FetcherEmitter#dataFetcherValue` stub line refs drifted. |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog`/`TypeFetcherGenerator` line refs drifted (TFG now 5 379). |
| **R236** validator-reference-candidate-hint-terminal-table | Cited `:1665-1677`; the classifier method `classifyInputFieldInternal` and its `columnSqlNamesOf` candidate-hint have drifted past the prior audit's "~1836" under continued `BuildContext.java` growth — re-anchor at the symbol. |
| **R35** source-orientation-javadocs | LOC counts stale (FieldBuilder cited "2 172", now **5 656**, +112 this cycle; TypeFetcherGenerator "1 646", now 5 379); the "FieldBuilder decomposition shipped under R6" claim stays dubious given the file is now ~2.6x larger. |

## Cross-cutting observations

1. **The orphaned-closure backlog did *not* grow this cycle — and one stale flag
   retired.** R275, R291, R292, R294 and R295 all self-closed cleanly (Done +
   file deleted), and R253 was deleted at its subsumption commit — the desired
   pattern held for every closure this window. R201 and R146 (discarded-by-sibling)
   and R30 (self-Done, unswept) remain the only stranded files, unchanged across
   three audits: the workflow rule that *the closing author deletes the file* has
   no owner when the closure comes from a sibling item (mooted/absorbed). Worth a
   one-shot cleanup pass and a workflow note.
2. **R276's landing remains the highest-leverage source of staleness.**
   Eliminating `PlainObjectType`, `PojoResultType.NoBacking`, and
   `UnbackedPojoResult` stranded R180, whose core construct no longer exists —
   making it the highest-value re-spec on the board.
3. **Pervasive line-drift, now compounding into refresh churn.** `FieldBuilder.java`
   (**5 656**, +112 this cycle) keeps growing; `TypeFetcherGenerator.java` (5 379)
   held flat only because R287 was net-neutral on it. The R287 restructuring of
   `MutationInputResolver.java` (+6) and `FetcherEmitter.java` (−95) re-drifted
   **R245** and **R242** *within a single cycle* — R242 even after a same-cycle
   prose refresh — the sharpest reminder yet that specs anchored to symbol names
   rather than line numbers would stop the recurrence.
4. **R271 (dunder retirement) still bounds a small staleness class.** Specs
   quoting generated locals by their old `__`-prefixed names: re-verified that
   only R269 is affected today; future specs should quote the readable names
   (`element`, `row`, `key`, `byPk`).
5. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.
6. **The five newly authored items (R290, R293, R296, R297, R298) are sound.**
   Each is a 2026-06-10 report against verified-live constructs — the `shareable`
   fold in `ConnectionPromoter` (R297), the deferred federation-contract check R295
   could not run (R298), the R294 warning channel (R296), R222's Stage-3 field-side
   pivot (R290), and the remaining warning categories (R293) — and several were
   actively corrected mid-cycle (R293's inventory citations, R296's R294 scope
   claim). They are the well-behaved descendants of the R294/R295/R281 work, each
   filed rather than silently absorbed. No staleness, no action — the same verdict
   the prior audit reached for R287/R288/R289 (all three still current; R287 has
   since shipped its fix).

---

_Review date: 2026-06-11._
