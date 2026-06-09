# Roadmap staleness audit — 2026-06-09

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

This audit supersedes the `2026-06-08` staleness audit, which has been deleted —
only the latest audit is retained.

## Changes since the 2026-06-08 audit

Twenty-one commits landed between the prior audit (`1b41999`, 2026-06-08 07:16)
and this one. They affect the staleness picture as follows:

- **R149 closed and deleted (was flagged in §B).** The prior audit recommended
  "drop the completed half; only the `buildOutput()` report-population test
  remains." That is exactly what happened: the report-population test landed and
  R149 transitioned **In Review → Done**, after which its file
  (`r147-followup-end-to-end-publish-diagnostics-tests.md`) was deleted. A clean,
  self-closed item — no orphaned file left behind. **Removed from the flagged
  list.**
- **R283 added (current).** New Backlog **bug**:
  `service-table-field-reference-subfield-projection` — non-column sub-fields
  (first instance: a `@reference` relation) on a `ChildField.ServiceTableField`'s
  returned jOOQ `Record` fail at query time because the rows method never
  re-projects through a Graphitron query. Targets verified live
  (`FieldClassification.java:307` names `ChildField.ServiceTableField`). Freshly
  authored 2026-06-08 — current, not flagged.
- **R284 added (current).** New **In Review** bug:
  `bridging-hop-conditionjoin-alias-order` — reversed source/target alias order in
  bridging-hop `@reference` `ConditionJoin` emission. `ConditionJoin` construct
  verified live. Authored 2026-06-08 — current, not flagged.
- **State transitions (none change the flagged set):** R266 Backlog → **In
  Review** (DELETE-onto-`DeleteRows` carrier; will retire `@value` on landing),
  R186 Spec → **Ready** (nested input types), R281 (`classification-test-dsl`)
  reworked in Spec, R253 citation refreshed. None of these were flagged and none
  introduce new staleness.

Net effect on the counts below: one item left the flagged set (R149), two
current items were added (R283, R284).

## Scope and method

All **111** entries were reviewed (110 `R<n>` item files plus the non-item
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
`GraphitronType.java:118` (`permits PojoResultType.Backed`), a repo-wide
`UnbackedPojoResult` search returning zero hits, and a single residual
`PlainObjectType` hit (the kept Javadoc mention). (The surviving `NoBacking`
matches are the *kept* LSP `TypeBackingShape.NoBacking` projection, a different
type.)

**Result: 28 items flagged, 82 current.** Line numbers cited below are as of the
review date and will themselves drift.

## A. Obsolete — should leave the active roadmap (3)

These should be deleted. Each was superseded or discarded by a sibling item that
has already reached Done; because the closure came from the sibling rather than a
self-transition, no author ran the file-deletion sweep.

| Item | Status | Action | Why |
|---|---|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Backlog | Discard/delete | R244 (Done) changelog: "Supersedes R241, **moots R201**." The root `@service` outcome path is migrated to typed `Outcome<T>`; the `PayloadConstructionShape` family the bug targeted is retired. |
| **R146** mutation-cardinality-safety-unique-index | Backlog | Discard/delete | R246 (Done) changelog: "**Absorbs R146 (PK-or-UK coverage, discarded)**." `JooqCatalog.candidateKeys` + `UpdateRowsWalker` PK-or-UK matching shipped. The Backlog body still reads as live work. |
| **R30** selection-parser-audit | **Done** | Delete the file | Status is Done (confirmed in front-matter); per workflow, Done items are deleted. The file has outlived its closure. |

## B. Outdated — needs re-spec (premise or targets materially changed) (12)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer reading it. Re-spec before the next state transition.

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **High priority.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm is the fetcher-only fallback the design centred on (`ColumnReadShape` sketch lines 97/119/142/149/153 all name `NoBacking`). R276 deleted `PojoResultType.NoBacking` (confirmed: `PojoResultType` permits only `Backed`). Re-spec against the collapsed `Backed`-only model; the "NoBacking is excluded upstream" guard arms it cites no longer have a type to exclude. |
| **R13** faceted-search | Spec | Central seam `ConnectionSynthesis.buildPlan()` gone (synthesis now in `ConnectionPromoter`); `FieldWrapper.Connection` is a 2-arg record without `connectionName`. Phases 2–4 must be rewritten. |
| **R71** recordn-key-parity-lifter | Backlog | R110 replaced `@batchKeyLifter` with `@sourceRow`; `BatchKeyLifterDirectiveResolver` → `SourceRowDirectiveResolver`, `LifterRowKeyed` split into `Lifter{Leaf,Path}Keyed`. Title and body name a removed directive. |
| **R46** service-multi-tenant-fanout | Backlog | R45 records that R190 sealing the interface dissolved R46's design (`ContextValueRegistration<FanOut>` no longer exists); body still links the dead `typed-context-value-registry.md` slug (renamed to `tenant-routing-and-execution-input.md`). |
| **R19** history-squash | Ready | Every SHA/count is an April-2026 snapshot: body targets "566 commits"; branch is now **2451 ahead** of `origin/main` (up from 2427 at the prior audit — drifting daily). All numbers/SHAs/drop-lists need regeneration before this can be executed. |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | Premise says R222 *already* collapsed `JooqRecordInputType` with a specific rejection — but `JooqRecordInputType` still exists (`GraphitronType.java:340`), the rejection string ("…but not a TableRecord…") is absent from the code, and R222 is still **Spec**. Forward-dated premise; rephrase as conditional-on-R222 or fold into R222. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines"; `TypeFetcherGenerator.java` is now **5 099** (and still climbing — 5 034 one day ago). Re-evaluate decompose-vs-document against real size. |
| **R263** decode-helper-typename-first-resolution | Backlog | Trap still exists, but motivating dependency R195 shipped by sidestepping it — the "next caller" justification is now hypothetical. Re-justify or drop (priority-3). |
| **R269** nullable-to-one-record-into-npe | Spec | R268 shipped (helpers already take a `sourceExpr`), and R271 (dunder retirement) shipped, renaming `__elt`→`element` — so the spec's `__elt`/`__k` code snippets (lines 22-23, 29) are doubly stale. It is the only live spec still quoting dunder locals. Strip the snippets and the historical "split out of R268" framing. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped — the "R58 is currently In Review… if it reverts" framing is false. Carriers still String-flattened, so the body stays valid; remove the stale dependency prose. |
| **R261** wire-coercion-cast-guard | Backlog | The specific reported instance (`(SakRecord) raw.get(...)`) is handled by shipped R195 (`CallSiteExtraction.NodeIdDecodeRecord`). Update the coordination framing; the raw-cast invariant is still unbuilt. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)"; R94 shipped. Blocker cleared — drop the blocked framing and treat as directly actionable (R94's Done note explicitly hands this item its annotated walk target). |

## C. Outdated — update references only (work valid, refs stale) (13)

Substance intact; only paths, line numbers, or dependency tense drifted. Two root
causes dominate: a `docs/*.md` → `docs/*.adoc` migration, and `FieldBuilder.java`
(now **5 544**) / `TypeFetcherGenerator.java` (now **5 099**) growing ~2.5x.

| Item | Stale reference |
|---|---|
| **R275** source-record-carrier-service-error-channel | In Progress. The "Carrier model and `NoBacking` removal moved to R276" section (line 82) reads "**Once R276 lands**, every carrier is a `ResultType`…" — but R276 is now **Done** and its plan file (`record-binding-reflection-only`) is deleted. Update the prose to past tense; the slug it names no longer resolves to a file. |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted "firstclass-connection-types" item and stale `ConnectionSynthesis` naming (now `ConnectionPromoter`). |
| **R95** routines-as-data-model-citizens | Claims `RoutineReflection` "already lives in graphitron-common"; it only exists in the legacy `graphitron-java-codegen` module (confirmed: zero hits under `graphitron-common`). |
| **R99** lsp-submodule-sibling-classpath | Path `graphitron-rewrite/graphitron-maven/…` is actually `graphitron-maven-plugin/…` (confirmed: only `graphitron-maven-plugin` exists). |
| **R267** nodeid-encoder-deprecated-convert | Bug live, but path is `generators/util/NodeIdEncoderClassGenerator.java`, not `generators/` (confirmed). |
| **R92** catalog-check-constraint-validation | Line refs into `TypeFetcherGenerator` drifted (file now 5 099 lines). |
| **R242** dml-payload-positional-alignment | `FetcherEmitter` line refs drifted. |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue`/`defaulted()` anchor lines stale; relocate before Spec. |
| **R24** nodeidreferencefield-join-projection-form | `FetcherEmitter#dataFetcherValue` stub line refs drifted. |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog`/`TypeFetcherGenerator` line refs drifted. |
| **R236** validator-reference-candidate-hint-terminal-table | Cited `:1665-1677`; actual `~:1825`. |
| **R35** source-orientation-javadocs | LOC counts stale (FieldBuilder cited "2 172", now 5 544; TypeFetcherGenerator "1 646", now 5 099); the "FieldBuilder decomposition shipped under R6" claim stays dubious given the file is now ~2.5x larger. |

## Cross-cutting observations

1. **Orphaned closures never get deleted.** R201 and R146 are both
   discarded-by-sibling, yet their files remain on the board; R30 is self-Done but
   not yet swept. The workflow rule that *the closing author deletes the file* has
   no owner when the closure comes from a sibling item (mooted/absorbed). Worth a
   one-shot cleanup pass and a workflow note. **Watch item:** R266 (now In Review)
   will retire the `@value` directive and discard R188 on landing — R188 already
   has no live file, so the only follow-through needed there is the `@value`
   directive/machinery removal, but confirm no sibling file is stranded when R266
   reaches Done. *(R149, flagged here in the prior audit, closed cleanly this
   cycle — the self-close path leaves no orphan.)*
2. **R276's landing is the highest-leverage source of staleness.** Eliminating
   `PlainObjectType`, `PojoResultType.NoBacking`, and `UnbackedPojoResult`
   stranded R180, whose core construct no longer exists — making it the
   highest-value re-spec on the board.
3. **Pervasive line-drift.** `FieldBuilder.java` (5 544) and
   `TypeFetcherGenerator.java` (5 099) keep growing — both gained ~100 lines in a
   single day since the prior audit; nearly every item citing them by line is
   stale, and the drift strengthens R7 (decompose) and R35. Anchoring future specs
   to symbol names rather than line numbers would stop the recurrence.
4. **R271 (dunder retirement) created a small staleness class:** specs that quote
   generated locals by their old `__`-prefixed names. Only R269 is affected today;
   future specs should quote the readable names (`element`, `row`, `key`, `byPk`).
5. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.
6. **Newly authored items (R283, R284) are sound.** Both are 2026-06-08 bug
   reports against verified-live constructs (`ChildField.ServiceTableField`,
   `ConditionJoin`); no staleness, no action.

---

_Review date: 2026-06-09._
