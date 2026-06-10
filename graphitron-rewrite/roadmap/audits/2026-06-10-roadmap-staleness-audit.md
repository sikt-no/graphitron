# Roadmap staleness audit — 2026-06-10

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

This audit supersedes the `2026-06-09` staleness audit, which has been deleted —
only the latest audit is retained.

## Changes since the 2026-06-09 audit

Thirty-six commits landed between the prior audit (`80927dc`, 2026-06-09 03:09)
and this one (`1b8938b`, 2026-06-09 21:42). They move the staleness picture as
follows:

- **R186 closed and deleted (clean self-close).** `nested-input-types-in-mutation-fields`
  transitioned Spec → Ready → In Review → **Done** (`ecdc7c4`): a plain
  (non-`@table`) input object grouping columns of the surrounding `@table` input
  is now admitted on `@mutation` fields. The file was deleted on the Done
  transition — no orphan. **Not flagged.**
- **R266 closed and deleted, retiring `@value` (clean self-close).**
  `deleterows-walker-carrier` reached **Done** (`57cb7b0` + `f1ee7a6`): DELETE
  mutations now ride the `DeleteRows` walker carrier (`Identified | Broadcast`),
  and carving DELETE off `MutationInputResolver.resolveInput` **retired the
  `@value` directive entirely** (absorbing R188). Verified: the declaration,
  `DIR_VALUE`, `DmlKind.acceptsValueMarker`/`requiresPkCoverage`, the
  `valueMarkedNames` machinery and `value.adoc` are all gone (only historical
  comments/test-prose mention `@value`); no R188 file is stranded. This resolves
  the prior audit's cross-cutting **watch item #1**. The DELETE work also surfaced
  a follow-up bug, filed as **R287** (see below).
- **R285 closed and deleted (clean self-close).** What the prior audit listed as
  the freshly-authored Backlog bug **R283** (`service-table-field-reference-subfield-projection`)
  was renumbered to **R285** to resolve an ID collision with the already-Done
  `@oneOf` changelog entry that also held `R283` (`f55b9ac`). R285 then went
  Spec → Ready → In Review → **Done** (`58a7c8f` + `d7d2861`): a child `@service`
  field returning a table-bound type now lifts back through
  `SplitRowsMethodEmitter.buildServiceTableLift`, so non-column sub-fields (the
  first being a `@reference` multiset) resolve. File deleted on Done — no orphan.
- **R287 added (current).** New Backlog **bug**: `delete-rejects-table-return` —
  DELETE cannot legitimately return an `@table` (the row is gone; `RETURNING`
  carries only the PK), yet `ChildField.SingleRecordTableFieldFromReturning` and
  the DELETE carriers can still carry a `Projected*` `DmlReturnExpression` arm.
  Targets verified live (`DmlReturnExpression.ProjectedSingle`/`ProjectedList` at
  `MutationField.java:62-63`). Surfaced by the R281 dimensional-model design and
  the R266 landing. Authored 2026-06-09 — current, not flagged. (Resolved: R287
  removed the carrier and the DELETE -> `@table` classifier paths; the
  `Projected*` arms stay for INSERT/UPDATE/UPSERT.)
- **R288 added (current).** New Backlog **bug**: `inline-interface-and-tablemethod-children`
  — a child `@table` field backed by `ChildField.TableInterfaceField` or
  `ChildField.TableMethodField` gets a synchronous per-parent fetcher
  (`TypeFetcherGenerator.buildTableInterfaceFieldFetcher:833`,
  `buildChildTableMethodFetcher:1147`) rather than inlining as a correlated
  multiset — an N+1 pattern. Filed out of R281 (`f663641`). Targets verified live.
  Current, not flagged.
- **R289 added (current).** New Backlog **doc-only** item:
  `keynodesynthesiser-resolvable-false-entity-doc` — `KeyNodeSynthesiser`'s
  class javadoc (`:33-34`, and `:22`) falsely claims `@key(resolvable: false)`
  keeps a `@node` type out of `_Entity`; federation-jvm injects every `@key`-bearing
  type into the union regardless, as `FederationBuildSmokeTest` now pins (since
  R286). Surfaced during the R286 review. The false claim is verified still present
  in the source. Current, not flagged.
- **State transitions (one introduces new staleness — see R245 in §C):**
  R200 Backlog → **Spec** (`honor-field-directive-in-inputbeanresolver`, description
  refreshed after R195); R281 Spec → Ready → **In Progress** (`classification-test-dsl`,
  spec reworked around the producer × mapping × wiring dimensional model, slice 1
  underway). Neither R200 nor R281 was flagged and both read current.

Net effect on the counts below: three items left the active board cleanly
(R186, R266, R285 — all Done + deleted), three current items were added
(R287, R288, R289), and one previously-current item (**R245**) became
reference-stale because R186/R266 restructured `MutationInputResolver.java`.

## Scope and method

All **111** entries were reviewed (110 `R<n>` item files plus the non-item
`inference-axis-coverage.adoc` placeholder). For each item the targets it names
(classes, directives, methods, packages, modules) were located in the current
tree, the described problem was checked for whether it still reproduces, and the
changelog was scanned for the item's `R<n>` and key terms to catch work that
shipped without the item being closed.

The classifier landing that dominated the prior audit still holds: **R276**
(`record-binding-reflection-only`, Done) eliminated
`GraphitronType.PlainObjectType`, collapsed `PojoResultType` to a single `Backed`
arm — **deleting `PojoResultType.NoBacking`** — and **deleted
`TypeClassification.UnbackedPojoResult`**. Re-verified this cycle:
`GraphitronType.java:117-118` (`sealed interface PojoResultType … permits
PojoResultType.Backed`), and a repo-wide `UnbackedPojoResult` search returning
zero hits.

**Result: 29 items flagged, 81 current.** Line numbers cited below are as of the
review date and will themselves drift.

## A. Obsolete — should leave the active roadmap (3)

These should be deleted. Each was superseded or discarded by a sibling item that
has already reached Done; because the closure came from the sibling rather than a
self-transition, no author ran the file-deletion sweep. (All three are unchanged
since the prior audit — still stranded.)

| Item | Status | Action | Why |
|---|---|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Backlog | Discard/delete | R244 (Done) changelog: "Supersedes R241, **moots R201**." The root `@service` outcome path is migrated to typed `Outcome<T>`; the `PayloadConstructionShape` family the bug targeted is retired. (Confirmed: file still present, `status: Backlog`.) |
| **R146** mutation-cardinality-safety-unique-index | Backlog | Discard/delete | R246 (Done) changelog: "**Absorbs R146 (PK-or-UK coverage, discarded)**." `JooqCatalog.candidateKeys` + `UpdateRowsWalker` PK-or-UK matching shipped. The Backlog body still reads as live work. (Confirmed: file still present, `status: Backlog`.) |
| **R30** selection-parser-audit | **Done** | Delete the file | Status is Done (confirmed in front-matter); per workflow, Done items are deleted. The file has outlived its closure. |

## B. Outdated — needs re-spec (premise or targets materially changed) (12)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer reading it. Re-spec before the next state transition.

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **High priority.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm is the fetcher-only fallback the design centred on (`ColumnReadShape` sketch lines 97/119/142/149/153 all name `NoBacking`). R276 deleted `PojoResultType.NoBacking` (confirmed: `PojoResultType` permits only `Backed`). Re-spec against the collapsed `Backed`-only model; the "NoBacking is excluded upstream" guard arms it cites no longer have a type to exclude. |
| **R13** faceted-search | Spec | Central seam `ConnectionSynthesis.buildPlan()` gone (synthesis now in `ConnectionPromoter`); `FieldWrapper.Connection` is a 2-arg record without `connectionName`. Phases 2–4 must be rewritten. |
| **R71** recordn-key-parity-lifter | Backlog | R110 replaced `@batchKeyLifter` with `@sourceRow`; `BatchKeyLifterDirectiveResolver` → `SourceRowDirectiveResolver` (confirmed: only `SourceRowDirectiveResolver` exists, zero `BatchKeyLifterDirectiveResolver` hits), `LifterRowKeyed` split into `Lifter{Leaf,Path}Keyed`. Title and body name a removed directive. |
| **R46** service-multi-tenant-fanout | Backlog | R45 records that R190 sealing the interface dissolved R46's design (`ContextValueRegistration<FanOut>` no longer exists); body still links the dead `typed-context-value-registry.md` slug (renamed to `tenant-routing-and-execution-input.md`). |
| **R19** history-squash | Ready | Every SHA/count is an April-2026 snapshot: body targets "566 commits"; branch is now **2488 ahead** of `origin/main` (up from 2451 at the prior audit — drifting daily, +37 in 24h). All numbers/SHAs/drop-lists need regeneration before this can be executed. |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | Premise says R222 *already* collapsed `JooqRecordInputType` with a specific rejection — but `JooqRecordInputType` still exists (`GraphitronType.java:340`), the rejection string ("…but not a TableRecord…") is absent from the code, and R222 is still **Spec**. Forward-dated premise; rephrase as conditional-on-R222 or fold into R222. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines"; `TypeFetcherGenerator.java` is now **5 379** (up from 5 099 a day ago — still climbing, +280 from R281/R285 work). Re-evaluate decompose-vs-document against real size. |
| **R263** decode-helper-typename-first-resolution | Backlog | Trap still exists, but motivating dependency R195 shipped by sidestepping it — the "next caller" justification is now hypothetical. Re-justify or drop (priority-3). |
| **R269** nullable-to-one-record-into-npe | Spec | R268 shipped (helpers already take a `sourceExpr`), and R271 (dunder retirement) shipped, renaming `__elt`→`element` — so the spec's `__elt`/`__k` code snippets (lines 22-23, 29) are doubly stale. Confirmed it is still the only live spec quoting dunder locals. Strip the snippets and the historical "split out of R268" framing. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (`R58TypedRejectionPipelineTest` in tree) — the "R58 is currently In Review… if it reverts" framing is false. Carriers still String-flattened, so the body stays valid; remove the stale dependency prose. |
| **R261** wire-coercion-cast-guard | Backlog | The specific reported instance (`(SakRecord) raw.get(...)`) is handled by shipped R195 (`CallSiteExtraction.NodeIdDecodeRecord`). Update the coordination framing; the raw-cast invariant is still unbuilt. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)"; R94 shipped (confirmed in changelog). Blocker cleared — drop the blocked framing and treat as directly actionable (R94's Done note explicitly hands this item its annotated walk target). |

## C. Outdated — update references only (work valid, refs stale) (14)

Substance intact; only paths, line numbers, or dependency tense drifted. Two root
causes dominate: a `docs/*.md` → `docs/*.adoc` migration, and `FieldBuilder.java`
(now **5 544**) / `TypeFetcherGenerator.java` (now **5 379**) growing ~2.5–3x.

| Item | Stale reference |
|---|---|
| **R245** wire-condition-emit-on-mutations | **Newly flagged this cycle.** R186 (`ecdc7c4`) and R266 (`57cb7b0`) restructured `MutationInputResolver.java`: the body's cited line refs are now stale — argument-level `@condition` rejection is at `~:405` (cited "line 446"), and input-field-level `@condition(override:true)` admission is at `~:451-457` (cited "lines ~482-498"). The emit-half is still unbuilt (substance valid); only the anchors drifted. The README `blocked by deleterows-walker-carrier` link was already auto-cleared on regeneration when R266 closed. |
| **R275** source-record-carrier-service-error-channel | In Progress. The "Carrier model and `NoBacking` removal moved to R276" section (line ~82) reads "**Once R276 lands**, every carrier is a `ResultType`…" — but R276 is now **Done** and its plan file (`record-binding-reflection-only`) is deleted. Update the prose to past tense; the slug it names no longer resolves to a file. |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted "firstclass-connection-types" item and stale `ConnectionSynthesis` naming (now `ConnectionPromoter`). |
| **R95** routines-as-data-model-citizens | Claims `RoutineReflection` "already lives in graphitron-common"; it only exists in the legacy `graphitron-java-codegen` module (confirmed: zero hits under `graphitron-common`). |
| **R99** lsp-submodule-sibling-classpath | Path `graphitron-rewrite/graphitron-maven/…` is actually `graphitron-maven-plugin/…` (confirmed: only `graphitron-maven-plugin` exists). |
| **R267** nodeid-encoder-deprecated-convert | Bug live, but path is `generators/util/NodeIdEncoderClassGenerator.java`, not `generators/` (confirmed). |
| **R92** catalog-check-constraint-validation | Line refs into `TypeFetcherGenerator` drifted (file now 5 379 lines). |
| **R242** dml-payload-positional-alignment | `FetcherEmitter` line refs drifted. |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue`/`defaulted()` anchor lines stale; relocate before Spec. |
| **R24** nodeidreferencefield-join-projection-form | `FetcherEmitter#dataFetcherValue` stub line refs drifted. |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog`/`TypeFetcherGenerator` line refs drifted (TFG now 5 379). |
| **R236** validator-reference-candidate-hint-terminal-table | Cited `:1665-1677`; actual now `~:1836` (`classifyInputFieldInternal` at `:1749`, `columnSqlNamesOf` candidate-hint at `:1836` — drifted further from the prior audit's "~1825"). |
| **R35** source-orientation-javadocs | LOC counts stale (FieldBuilder cited "2 172", now 5 544; TypeFetcherGenerator "1 646", now 5 379); the "FieldBuilder decomposition shipped under R6" claim stays dubious given the file is now ~2.5x larger. |

## Cross-cutting observations

1. **The orphaned-closure backlog did *not* grow this cycle — but the three
   stale ones remain.** R186, R266 and R285 all self-closed cleanly (Done +
   file deleted), the desired pattern. R201 and R146 (discarded-by-sibling) and
   R30 (self-Done, unswept) are still stranded from earlier cycles: the workflow
   rule that *the closing author deletes the file* has no owner when the closure
   comes from a sibling item (mooted/absorbed). Worth a one-shot cleanup pass and
   a workflow note. **Prior watch item resolved:** R266 reached Done, retired
   `@value` in full, and left no R188 orphan — confirmed.
2. **R276's landing remains the highest-leverage source of staleness.**
   Eliminating `PlainObjectType`, `PojoResultType.NoBacking`, and
   `UnbackedPojoResult` stranded R180, whose core construct no longer exists —
   making it the highest-value re-spec on the board.
3. **Pervasive line-drift, now compounding.** `FieldBuilder.java` (5 544, flat
   this cycle) and `TypeFetcherGenerator.java` (5 379, **+280 in one day** from
   R281/R285) keep growing; nearly every item citing them by line is stale, and
   the drift strengthens R7 (decompose) and R35. This cycle the drift also
   reached **R245**, whose `MutationInputResolver.java` anchors moved under the
   R186/R266 restructuring — a fresh reminder that anchoring specs to symbol
   names rather than line numbers would stop the recurrence.
4. **R271 (dunder retirement) still bounds a small staleness class.** Specs
   quoting generated locals by their old `__`-prefixed names: re-verified that
   only R269 is affected today; future specs should quote the readable names
   (`element`, `row`, `key`, `byPk`).
5. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.
6. **Newly authored items (R287, R288, R289) are sound.** All three are
   2026-06-08/09 reports against verified-live constructs
   (`DmlReturnExpression.Projected*`, `ChildField.TableInterfaceField`/
   `TableMethodField`, `KeyNodeSynthesiser` javadoc); no staleness, no action.
   They are the well-behaved descendants of the R266/R281/R286 work — each filed
   rather than silently absorbed.

---

_Review date: 2026-06-10._
