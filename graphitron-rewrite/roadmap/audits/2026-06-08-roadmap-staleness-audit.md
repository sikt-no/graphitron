# Roadmap staleness audit — 2026-06-08

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

## Scope and method

All **110** entries were reviewed (109 `R<n>` item files plus the non-item
`inference-axis-coverage.adoc` placeholder). For each item the targets it names
(classes, directives, methods, packages, modules) were located in the current
tree, the described problem was checked for whether it still reproduces, and the
changelog was scanned for the item's `R<n>` and key terms to catch work that
shipped without the item being closed.

A recent classifier landing dominates the staleness picture: **R276**
(`record-binding-reflection-only`, Done) eliminated
`GraphitronType.PlainObjectType` (now a Javadoc mention only), collapsed
`PojoResultType` to a single `Backed` arm — **deleting `PojoResultType.NoBacking`**
— and **deleted `TypeClassification.UnbackedPojoResult`**. Verified in
`GraphitronType.java:117-118` (`permits PojoResultType.Backed`) and a repo-wide
`UnbackedPojoResult` search returning zero hits. (The surviving `NoBacking`
matches are the *kept* LSP `TypeBackingShape.NoBacking` projection, a different
type.)

**Result: 29 items flagged, 80 current.** Line numbers cited below are as of the
review date and will themselves drift.

## A. Obsolete — should leave the active roadmap (3)

These should be deleted. Each was superseded or discarded by a sibling item that
has already reached Done; because the closure came from the sibling rather than a
self-transition, no author ran the file-deletion sweep.

| Item | Status | Action | Why |
|---|---|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Backlog | Discard/delete | R244 (Done) changelog: "Supersedes R241, **moots R201**." The root `@service` outcome path is migrated to typed `Outcome<T>`; the `PayloadConstructionShape` family the bug targeted is retired. |
| **R146** mutation-cardinality-safety-unique-index | Backlog | Discard/delete | R246 (Done) changelog: "**Absorbs R146 (PK-or-UK coverage, discarded)**." `JooqCatalog.candidateKeys` + `UpdateRowsWalker` PK-or-UK matching shipped. The Backlog body still reads as live work. |
| **R30** selection-parser-audit | **Done** | Delete the file | Status is Done; per workflow, Done items are deleted. The file has outlived its closure. |

## B. Outdated — needs re-spec (premise or targets materially changed) (13)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer reading it. Re-spec before the next state transition.

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **High priority.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm is the fetcher-only fallback the design centred on (`ColumnReadShape` sketch lines 97/119/142/149/153 all name `NoBacking`). R276 deleted `PojoResultType.NoBacking` (confirmed: `PojoResultType` permits only `Backed`). Re-spec against the collapsed `Backed`-only model; the "NoBacking is excluded upstream" guard arms it cites no longer have a type to exclude. |
| **R13** faceted-search | Spec | Central seam `ConnectionSynthesis.buildPlan()` gone (synthesis now in `ConnectionPromoter`); `FieldWrapper.Connection` is a 2-arg record without `connectionName`. Phases 2–4 must be rewritten. |
| **R71** recordn-key-parity-lifter | Backlog | R110 replaced `@batchKeyLifter` with `@sourceRow`; `BatchKeyLifterDirectiveResolver` → `SourceRowDirectiveResolver`, `LifterRowKeyed` split into `Lifter{Leaf,Path}Keyed`. Title and body name a removed directive. |
| **R46** service-multi-tenant-fanout | Backlog | R45 records that R190 sealing the interface dissolved R46's design (`ContextValueRegistration<FanOut>` no longer exists); body still links the dead `typed-context-value-registry.md` slug (renamed to `tenant-routing-and-execution-input.md`). |
| **R19** history-squash | Ready | Every SHA/count is an April-2026 snapshot: body targets "566 commits"; branch is now **2427 ahead** of `origin/main`. All numbers/SHAs/drop-lists need regeneration before this can be executed. |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | Premise says R222 *already* collapsed `JooqRecordInputType` with a specific rejection — but `JooqRecordInputType` still exists (`GraphitronType.java:340`), the rejection string ("…but not a TableRecord…") is absent from the code, and R222 is still **Spec**. Forward-dated premise; rephrase as conditional-on-R222 or fold into R222. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines"; `TypeFetcherGenerator.java` is now **5 034**. Re-evaluate decompose-vs-document against real size. |
| **R263** decode-helper-typename-first-resolution | Backlog | Trap still exists, but motivating dependency R195 shipped by sidestepping it — the "next caller" justification is now hypothetical. Re-justify or drop (priority-3). |
| **R269** nullable-to-one-record-into-npe | Spec | R268 shipped (helpers already take a `sourceExpr`), and R271 (dunder retirement) shipped, renaming `__elt`→`element` — so the spec's `__elt`/`__k` code snippets (lines 22-23, 29) are doubly stale. It is the only live spec still quoting dunder locals. Strip the snippets and the historical "split out of R268" framing. |
| **R149** r147-followup-publish-diagnostics-tests | Backlog | First deferred test delivered by R196 (`BuildTriggerPublishesDiagnosticsTest`). Drop the completed half; only the `buildOutput()` report-population test remains. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped — the "R58 is currently In Review… if it reverts" framing is false. Carriers still String-flattened, so the body stays valid; remove the stale dependency prose. |
| **R261** wire-coercion-cast-guard | Backlog | The specific reported instance (`(SakRecord) raw.get(...)`) is handled by shipped R195 (`CallSiteExtraction.NodeIdDecodeRecord`). Update the coordination framing; the raw-cast invariant is still unbuilt. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)"; R94 shipped. Blocker cleared — drop the blocked framing and treat as directly actionable (R94's Done note explicitly hands this item its annotated walk target). |

## C. Outdated — update references only (work valid, refs stale) (13)

Substance intact; only paths, line numbers, or dependency tense drifted. Two root
causes dominate: a `docs/*.md` → `docs/*.adoc` migration, and `FieldBuilder.java`
(now **5 417**) / `TypeFetcherGenerator.java` (now **5 034**) growing ~2.5x.

| Item | Stale reference |
|---|---|
| **R275** source-record-carrier-service-error-channel | In Progress. The "Carrier model and `NoBacking` removal moved to R276" section (line 82) reads "**Once R276 lands**, every carrier is a `ResultType`…" — but R276 is now **Done** and its plan file (`record-binding-reflection-only`) is deleted. Update the prose to past tense; the slug it names no longer resolves to a file. |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted "firstclass-connection-types" item and stale `ConnectionSynthesis` naming (now `ConnectionPromoter`). |
| **R95** routines-as-data-model-citizens | Claims `RoutineReflection` "already lives in graphitron-common"; it only exists in the legacy `graphitron-java-codegen` module (confirmed: zero hits under `graphitron-common`). |
| **R99** lsp-submodule-sibling-classpath | Path `graphitron-rewrite/graphitron-maven/…` is actually `graphitron-maven-plugin/…`. |
| **R267** nodeid-encoder-deprecated-convert | Bug live, but path is `generators/util/NodeIdEncoderClassGenerator.java`, not `generators/` (confirmed). |
| **R92** catalog-check-constraint-validation | Line refs into `TypeFetcherGenerator` drifted (file now 5 034 lines). |
| **R242** dml-payload-positional-alignment | `FetcherEmitter` line refs drifted. |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue`/`defaulted()` anchor lines stale; relocate before Spec. |
| **R24** nodeidreferencefield-join-projection-form | `FetcherEmitter#dataFetcherValue` stub line refs drifted. |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog`/`TypeFetcherGenerator` line refs drifted. |
| **R236** validator-reference-candidate-hint-terminal-table | Cited `:1665-1677`; actual `~:1825`. |
| **R35** source-orientation-javadocs | LOC counts stale (FieldBuilder cited "2 172", now 5 417; TypeFetcherGenerator "1 646", now 5 034); the "FieldBuilder decomposition shipped under R6" claim stays dubious given the file is now ~2.5x larger. |

## Cross-cutting observations

1. **Orphaned closures never get deleted.** R201, R146, and R30 are all
   Done/discarded-by-sibling, yet their files remain on the board. The workflow
   rule that *the closing author deletes the file* has no owner when the closure
   comes from a sibling item (mooted/absorbed). Worth a one-shot cleanup pass and
   a workflow note.
2. **R276's landing is the highest-leverage source of staleness.** Eliminating
   `PlainObjectType`, `PojoResultType.NoBacking`, and `UnbackedPojoResult`
   stranded R180, whose core construct no longer exists — making it the
   highest-value re-spec on the board.
3. **Pervasive line-drift.** `FieldBuilder.java` (5 417) and
   `TypeFetcherGenerator.java` (5 034) keep growing; nearly every item citing them
   by line is stale, and the drift strengthens R7 (decompose) and R35. Anchoring
   future specs to symbol names rather than line numbers would stop the
   recurrence.
4. **R271 (dunder retirement) created a small staleness class:** specs that quote
   generated locals by their old `__`-prefixed names. Only R269 is affected today;
   future specs should quote the readable names (`element`, `row`, `key`, `byPk`).
5. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-06-08._
