# Roadmap staleness audit — 2026-06-12

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

This audit supersedes the `2026-06-11` staleness audit, which has been deleted —
only the latest audit is retained. (The sibling `classification-test-dsl-inventory.md`
in this directory is **not** a staleness audit: it is R281's permanent
corpus-retirement inventory, committed as a durable artifact of that item's
landing, and is left in place.)

## Changes since the 2026-06-11 audit

Six commits landed between the prior audit (`c9c86e5a`, committed 2026-06-11
03:11) and this one (`cf8262e2`, 2026-06-11 06:37). They move the staleness
picture as follows:

- **R281 closed and deleted (clean self-close).** `classification-test-dsl`
  (the `@classified` spec-by-example DSL) reached **Done** with an independent
  In Review sign-off (`6e6be542`); the file was deleted on the Done transition.
  The prior audit listed it as **In Review, current** — it was never on a flag
  list, so no flag retires with it. Two side effects: (a) it committed its
  retirement inventory at `roadmap/audits/classification-test-dsl-inventory.md`,
  which remains as a permanent artifact (not a staleness audit, left untouched);
  (b) it unblocked **R290**, whose front-matter dropped the `classification-test-dsl`
  dependency (R290 is now blocked only by R222). The throwaway `LeafTupleAdapter`
  R290 is slated to delete shipped under R281. **No longer on the board.**
- **R287 closed and deleted (clean self-close).** `delete-rejects-table-return`
  transitioned In Review → **Done** (`905da892`) and the file was deleted. Its
  *code* (`6e3ef42`, the removal of the DELETE → `@table` carrier) was already in
  the prior audit's baseline — only the Done transition + file deletion are new
  this window. The prior audit listed it as **current, not flagged**. Its prose
  survives correctly as bare `R287` mentions in R242 (lines 139, 205, 215) — no
  dangling links to the deleted file. **No longer on the board.**
- **R293 implementation landed, Ready → In Review.** `build-warning-cleanup`
  shipped its full sweep: phases 1–2 (`fc03387b`, handwritten / infra /
  generated-code warnings), phases 3–4 (`6ab51274`, jOOQ ambiguous-key fix +
  per-module `-Werror` guard), the In Review transition (`97fbc02e`), and a
  follow-up emitting a checked `Outcome.Success<T>` pattern instead of an
  unchecked cast (`cf8262e2`). It touched `FetcherEmitter.java` (+73),
  `ServiceMethodCallEmitter.java`, `TypeFetcherGenerator.java` (net-neutral —
  still **5 379**), `QueryConditionsGenerator.java`, `InputRecordGenerator.java`,
  `BuildContext.java` (+3), `CatalogBuilder.java`, and added `-Werror` to the
  child-module poms. Current, not flagged.

Net effect on the counts below: **no previously-flagged item left the board** —
the two items that left (R281, R287) were both *current* (unflagged) at the prior
audit, so the Section A/B/C flag count is unchanged at 28. The current count
drops by two (85 → 83) under those two clean closures. **No new flags were
introduced.** One existing §C flag drifted *further* within this window: R293's
`FetcherEmitter.java` edit shifted **R242**'s Id-arm anchor
(`buildSingleRecordIdFromReturningFetcherValue`) from `:822` to **`:839`** — a
warning-cleanup commit re-drifting a spec, the sharpest illustration yet of the
recurring line-anchor problem (observation 3).

## Scope and method

All **112** entries were reviewed (111 `R<n>` item files plus the non-item
`inference-axis-coverage.adoc` placeholder) — down from 114 at the prior audit
(R281 and R287 deleted). For each item the targets it names (classes, directives,
methods, packages, modules) were located in the current tree, the described
problem was checked for whether it still reproduces, and the changelog was
scanned for the item's `R<n>` and key terms to catch work that shipped without
the item being closed.

The classifier landing that dominated the prior three audits still holds: **R276**
(`record-binding-reflection-only`, Done) eliminated
`GraphitronType.PlainObjectType`, collapsed `PojoResultType` to a single `Backed`
arm — **deleting `PojoResultType.NoBacking`** — and **deleted
`TypeClassification.UnbackedPojoResult`**. Re-verified this cycle:
`GraphitronType.java:117` (`sealed interface PojoResultType extends ResultType …`,
permitting only `Backed`), and a repo-wide `UnbackedPojoResult` / `PlainObjectType`
search returning a single hit — a *contrastive javadoc* at `TypeBuilder.java:489`
("left unclassified … instead of a `PlainObjectType`"), prose describing the
absence, not a live symbol. The types themselves are gone.

**Result: 28 items flagged, 83 current.** Line numbers cited below are as of the
review date and will themselves drift.

## A. Obsolete — should leave the active roadmap (3)

These should be deleted. Each was superseded or discarded by a sibling item that
has already reached Done; because the closure came from the sibling rather than a
self-transition, no author ran the file-deletion sweep. (All three are unchanged
since the prior three audits — now stranded across **four**.)

| Item | Status | Action | Why |
|---|---|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Backlog | Discard/delete | R244 (Done) changelog: "Supersedes R241, **moots R201**." The root `@service` outcome path is migrated to typed `Outcome<T>`; the `PayloadConstructionShape` family the bug targeted is retired. (Re-confirmed: file still present, `status: Backlog`.) |
| **R146** mutation-cardinality-safety-unique-index | Backlog | Discard/delete | R246 (Done) changelog: "**Absorbs R146 (PK-or-UK coverage, discarded)**." `JooqCatalog.candidateKeys` + `UpdateRowsWalker` PK-or-UK matching shipped. The Backlog body still reads as live work. (Re-confirmed: file still present, `status: Backlog`.) |
| **R30** selection-parser-audit | **Done** | Delete the file | Status is Done (re-confirmed in front-matter). Per workflow, Done items are deleted. The file has outlived its closure — stranded across four audits now. |

## B. Outdated — needs re-spec (premise or targets materially changed) (12)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer reading it. Re-spec before the next state transition.

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **High priority.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm is the fetcher-only fallback the design centred on (`ColumnReadShape` sketch lines 97/119/142/149/153 all name `NoBacking`). R276 deleted `PojoResultType.NoBacking` (re-confirmed: `PojoResultType` permits only `Backed`). Re-spec against the collapsed `Backed`-only model; the "NoBacking is excluded upstream" guard arms it cites no longer have a type to exclude. |
| **R13** faceted-search | Spec | Central seam `ConnectionSynthesis.buildPlan()` gone (synthesis now in `ConnectionPromoter`; re-confirmed `buildPlan` returns zero hits and no `ConnectionSynthesis` class survives); `FieldWrapper.Connection` is a 2-arg record without `connectionName`. Phases 2–4 must be rewritten. |
| **R71** recordn-key-parity-lifter | Backlog | R110 replaced `@batchKeyLifter` with `@sourceRow`; `BatchKeyLifterDirectiveResolver` → `SourceRowDirectiveResolver` (re-confirmed: zero `BatchKeyLifterDirectiveResolver` hits, `SourceRowDirectiveResolver` lives), `LifterRowKeyed` split into `Lifter{Leaf,Path}Keyed`. Title and body name a removed directive. |
| **R46** service-multi-tenant-fanout | Backlog | R45 records that R190 sealing the interface dissolved R46's design (`ContextValueRegistration<FanOut>` no longer exists); body still links the dead `typed-context-value-registry.md` slug (renamed to `tenant-routing-and-execution-input.md`). |
| **R19** history-squash | Ready | Every SHA/count is an April-2026 snapshot: the metrics table reads "595 (as of 2026-04-24)" and the prose targets "566 commits"; branch is now **2 572 ahead** of `origin/main` (up from 2 565 at the prior audit — +7 in the window, still drifting daily). All numbers/SHAs/drop-lists need regeneration before this can be executed. |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | Premise says R222 *already* collapsed `JooqRecordInputType` with a specific rejection — but `JooqRecordInputType` still exists (`GraphitronType.java:340`), the rejection string ("…but not a TableRecord…") is absent from the code, and R222 is still **Spec**. Forward-dated premise; rephrase as conditional-on-R222 or fold into R222. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines"; `TypeFetcherGenerator.java` is **5 379** (flat this cycle — R293 net-neutral on it; ~3.3x the cited figure). Re-evaluate decompose-vs-document against real size. |
| **R263** decode-helper-typename-first-resolution | Backlog | Trap still exists, but motivating dependency R195 shipped by sidestepping it — the "next caller" justification is now hypothetical. Re-justify or drop (priority-3). |
| **R269** nullable-to-one-record-into-npe | Spec | R268 shipped (helpers already take a `sourceExpr`), and R271 (dunder retirement) shipped, renaming `__elt`→`element` — so the spec's `__elt`/`__k` code snippets (lines 22-23, 29) are doubly stale. Still the only live spec quoting dunder locals. Strip the snippets and the historical "split out of R268" framing. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (`R58TypedRejectionPipelineTest` in tree) — the "R58 is currently In Review… if it reverts" framing is false. Carriers still String-flattened, so the body stays valid; remove the stale dependency prose. |
| **R261** wire-coercion-cast-guard | Backlog | The specific reported instance (`(SakRecord) raw.get(...)`) is handled by shipped R195 (`CallSiteExtraction.NodeIdDecodeRecord`). Update the coordination framing; the raw-cast invariant is still unbuilt. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)"; R94 shipped (re-confirmed in changelog). Blocker cleared — drop the blocked framing and treat as directly actionable (R94's Done note explicitly hands this item its annotated walk target). |

## C. Outdated — update references only (work valid, refs stale) (13)

Substance intact; only paths, line numbers, or dependency tense drifted. Two root
causes dominate: a `docs/*.md` → `docs/*.adoc` migration, and `FieldBuilder.java`
(now **5 656**, flat this cycle — R293 did not touch it) /
`TypeFetcherGenerator.java` (now **5 379**, flat) standing at ~2.5–3.3x the sizes
the older specs cite.

| Item | Stale reference |
|---|---|
| **R242** dml-payload-positional-alignment | **Re-drifted again this window.** The DELETE-Table-arm steps are correctly struck through and marked "Obsolete (R287)" (the carrier and `buildSingleRecordTableFromReturningFetcherValue` are gone — and R287 has since reached Done + been deleted, its bare `R287` mentions here remaining valid). But the surviving Id-arm refs drifted under R293's `FetcherEmitter.java` edit: `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` is cited at "lines 561-610" / "lines 129…", actual is now **`:839`** (was `:822` last audit — R293 shifted it +17); and the `[ID!]`-admission diagnostic is cited at "`BuildContext.java:680-699`", actual is **`~:623-634`**. Refresh the Id-arm anchors. |
| **R245** wire-condition-emit-on-mutations | R293 did **not** touch `MutationInputResolver.java`, so the prior audit's corrected anchors still hold and were re-confirmed: argument-level `@condition` rejection (`if (foundTia.argCondition().isPresent()) …`) at **`:410`** (body cites "line 446" / "lines ~438-440"), and input-field-level `@condition(override:true)` admission at **`:457-463`** (body cites "lines ~482-498" / "`:482-498`"). The emit-half is still unbuilt (substance valid); only the body's anchors are stale. A standing reminder that anchoring specs to symbol names rather than line numbers would stop the recurrence. |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted "firstclass-connection-types" item and stale `ConnectionSynthesis` naming (now `ConnectionPromoter`). |
| **R95** routines-as-data-model-citizens | Claims `RoutineReflection` "already lives in graphitron-common"; it only exists in the legacy `graphitron-java-codegen` module (re-confirmed: zero hits under `graphitron-common`). |
| **R99** lsp-submodule-sibling-classpath | Path `graphitron-rewrite/graphitron-maven/…` is actually `graphitron-maven-plugin/…` (re-confirmed: only `graphitron-maven-plugin` exists). |
| **R267** nodeid-encoder-deprecated-convert | Bug live, but path is `generators/util/NodeIdEncoderClassGenerator.java`, not `generators/` (confirmed). |
| **R92** catalog-check-constraint-validation | Line refs into `TypeFetcherGenerator` drifted (file 5 379 lines, flat this cycle). |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue`/`defaulted()` anchor lines stale; relocate before Spec. |
| **R24** nodeidreferencefield-join-projection-form | `FetcherEmitter#dataFetcherValue` stub line refs drifted (R293 re-shuffled `FetcherEmitter`). |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog`/`TypeFetcherGenerator` line refs drifted (TFG 5 379). |
| **R236** validator-reference-candidate-hint-terminal-table | README cites `:1665-1677`; the classifier method `classifyInputFieldInternal` now sits at **`:1627`** and its `columnSqlNamesOf` candidate-hint at **`:1714`** — re-anchor at the symbol. |
| **R35** source-orientation-javadocs | LOC counts stale (FieldBuilder cited "2 172", now **5 656**; TypeFetcherGenerator "1 646", now 5 379); the "FieldBuilder decomposition shipped under R6" claim stays dubious given the file is now ~2.6x larger. |

## Cross-cutting observations

1. **The orphaned-closure backlog did *not* grow this cycle.** Both items that
   left the board (R281, R287) self-closed cleanly (Done + file deleted) — the
   desired pattern held again. R201 and R146 (discarded-by-sibling) and R30
   (self-Done, unswept) remain the only stranded files, unchanged across **four**
   audits: the workflow rule that *the closing author deletes the file* has no
   owner when the closure comes from a sibling item (mooted/absorbed). Worth a
   one-shot cleanup pass and a workflow note.
2. **R276's landing remains the highest-leverage source of staleness.**
   Eliminating `PlainObjectType`, `PojoResultType.NoBacking`, and
   `UnbackedPojoResult` stranded R180, whose core construct no longer exists —
   making it the highest-value re-spec on the board.
3. **Line-drift now recurs even from non-structural commits.** R293 is a
   *warning-cleanup* item, yet its `FetcherEmitter.java` edit alone re-drifted
   **R242**'s Id-arm anchor `:822`→`:839` inside this single window. This is the
   third consecutive cycle in which one window re-drifted R242; combined with the
   `FieldBuilder.java` (5 656) / `TypeFetcherGenerator.java` (5 379) bloat behind
   the bulk of §C, it is the sharpest reminder yet that specs anchored to symbol
   names rather than line numbers would stop the recurrence. `MutationInputResolver.java`
   was untouched this window, which is exactly why R245's anchors held — the
   contrast proves the point.
4. **R271 (dunder retirement) still bounds a small staleness class.** Specs
   quoting generated locals by their old `__`-prefixed names: re-verified that
   only R269 is affected today; future specs should quote the readable names
   (`element`, `row`, `key`, `byPk`).
5. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.
6. **R290 was unblocked, not stranded, by R281's Done.** R281's landing removed
   one of R290's two blockers; its front-matter and rollup were updated in the
   same window to depend only on R222, and the `LeafTupleAdapter` it is slated to
   delete shipped under R281 as designed. This is the well-behaved descendant
   pattern: a follow-up that tracks its predecessor's closure rather than going
   stale against it. No action — current.

---

_Review date: 2026-06-12._
