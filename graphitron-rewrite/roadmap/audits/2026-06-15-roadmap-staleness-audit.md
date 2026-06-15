# Roadmap staleness audit — 2026-06-15

A point-in-time review of every active roadmap item under
[`graphitron-rewrite/roadmap/`](../) against the **current** state of the
codebase on `claude/graphitron-rewrite` (HEAD `71fa2630`, 2026-06-14). The goal
is to find items whose premise no longer holds: work already shipped, constructs
renamed or removed, dependencies that have since landed, or specs grown stale
enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-06-12` staleness audit, which has been deleted —
only the latest audit is retained. (The sibling `classification-test-dsl-inventory.md`
in this directory is **not** a staleness audit: it is R281's permanent
corpus-retirement inventory. It is left in place, but see observation 6 — it has
gone materially stale this window and now warrants a "superseded" banner.)

## Changes since the 2026-06-12 audit

**89 commits** landed between the prior audit (`b9d90eaf`, committed 2026-06-12
03:10) and this one (HEAD `71fa2630`, 2026-06-14 21:26). The dominant event is
the **field-side dimensional-model rework**: R222 added the refined
`carrier × intent × mapping` model, and **R290** (`datafetcher-field-dimensional-slots`)
shipped it to Done — materialising those slots on the field, **deleting
`LeafTupleAdapter`**, and **dissolving `ConstructorField` /
`FieldClassification.Constructor`** as a wrong-by-design misfeature. This is the
single highest-leverage staleness source this cycle (compare R276 in prior audits).

Nine items reached a terminal state and **all deleted their files cleanly** — no
new stranded tombstones:

- **R290** `datafetcher-field-dimensional-slots` → Done (`dee7d02d`). Slot
  materialisation + `LeafTupleAdapter` deletion + `ConstructorField` dissolution.
  Slice 3 (the `SingleRecordTableField` collapse) was split out to **R305**; the
  `ChildField → SourceField` rename was split out to **R302**.
- **R299** `intention-classification-dimension` → Done (`ee19cf20`). Migrated the
  R281 classification corpus to `carrier × intent × mapping`; `ProducerStep`
  retired. (This is what made the `classification-test-dsl-inventory.md` artifact
  go stale — observation 6.)
- **R301** `align-docs-record-directive-ignored` → Done (`e6401e51`) and **R307**
  `retire-stale-record-directive-references` → Done (`bcfa8558`). Together they
  retired `@record` as a **live binding** directive: the directive declaration
  survives in `directives.graphqls` but is now parsed-but-ignored, the LSP no
  longer treats it as a live `ExternalCodeReference`, and the report-wiring build
  warning swapped from a redundant `@record` to a redundant `@splitQuery`. (This
  reframes **R121** — see §B.)
- **R303** `reify-inline-datafetchers-to-methods` → Done (`67cb6b74`). Reified
  inline datafetchers into named `XFetchers` methods; reshuffled `FetcherEmitter`
  (now 1 076 lines) and grew `TypeFetcherGenerator` to 5 398. Deferred the
  `@error PayloadAccessor` reification to the new **R304**.
- **R293** `build-warning-cleanup` → Done (`a3a5f393`); **R284**
  `bridging-hop-conditionjoin-alias-order` → Done (`e5cbb158`); **R264**
  `roadmap-tool-title-quote-roundtrip` → Done (`28a5c439`); **R306** closed as a
  duplicate of R264 (`ddfeba25`, content folded into the survivor).

New items filed this window: **R300** (jOOQ routine fields), **R302**
(`ChildField → SourceField` rename), **R304** (`@error` PayloadAccessor fetcher
reification), **R305** (carrier-dimension expansion / `SingleRecordTableField`
collapse), **R308** (`@service` list-payload N+1). All carry sane front-matter
and are not immediately stale; R304's `depends-on:` is correctly empty (R303's
slug was pruned on its Done), confirming no dangling-slug build risk.

**Net effect on the flag counts:** No previously-flagged item left the board
(every closure this window was a *current/unflagged* item). The board moved as
follows: **R267 drops off** (its references verified valid — see below); **R121
is newly flagged** (mooted/reframed by the `@record` retirement); **R279 is newly
flagged** (light — references the dissolved `ConstructorField` verdict); and
**R95** and **R24** are re-bucketed from §C (update-refs) up to §B (re-spec)
because the model/rename work this window turned reference-drift into
premise-drift. Result: **29 items flagged, 84 current.**

## Scope and method

All **113** entries were reviewed (112 `R<n>` item files plus the non-item
`inference-axis-coverage.adoc` placeholder). For each item the targets it names
(classes, directives, methods, packages, modules) were located in the current
tree under `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/`,
the described problem was checked for whether it still reproduces, and the
changelog was scanned for the item's `R<n>` and key terms to catch work that
shipped without the item being closed.

Two pre-existing landings still dominate the staleness picture and were
re-verified: **R276** (deleted `PlainObjectType` / `PojoResultType.NoBacking` /
`UnbackedPojoResult` — `PojoResultType` permits only `Backed` at
`GraphitronType.java:119-120`), and now **R290** (deleted `LeafTupleAdapter` —
zero hits in `src/main`; dissolved `ConstructorField` — only a historical-prose
comment survives at `FieldBuilder.java:855`).

**Result: 29 items flagged, 84 current.** Line numbers cited below are as of the
review date and will themselves drift — see observation 3.

## A. Obsolete — should leave the active roadmap (3)

Each was superseded or discarded by a sibling item already at Done. Because the
closure came from the sibling rather than a self-transition, no author ran the
file-deletion sweep. (All three were stranded across the prior four audits — now
five.)

| Item | Status | Action | Why |
|---|---|---|---|
| **R30** selection-parser-audit | **Done** | **Delete the file** | Status is Done (re-confirmed); per workflow (`workflow.adoc:21,74`) Done items are deleted. Re-verified the **sole** stranded Done tombstone in the roadmap (`status: Done` grep returns one file). If the "parser is needed, keep it" finding is worth retaining, capture it in `changelog.md` first, then delete. **Clean delete — unambiguous.** |
| **R146** mutation-cardinality-safety-unique-index | Backlog | **Discard → delete** (via transition) | R246 (Done) changelog: "**Absorbs R146 (PK-or-UK coverage, discarded)**." `JooqCatalog.candidateKeys` (`JooqCatalog.java:527`) + `UpdateRowsWalker` PK-or-UK matching shipped and are tested. The file's `status: Backlog` is stale; the correct mechanism is a `Backlog → Discarded` transition (which couples with file deletion per `workflow.adoc:23`), not a raw delete of a Backlog file. |
| **R201** honor-field-directive-in-payload-construction-shape | Backlog | **Discard → delete, *after* confirming the live remnant** | R244 (Done) changelog: "Supersedes R241, **moots R201**." **Caveat (corrected from prior audits):** the family is *not* fully retired — `PayloadConstructionShape.java` is **still live** for the deferred `PayloadClass` paths (`FieldBuilder`, `ErrorChannel`, `ErrorsSlot`, `PayloadConstructionShapeTest`), and R244 itself says "the `PayloadClass` arm stays live for those paths." If honoring `@field(name:)` is still wanted on those surviving paths, R201 should be **re-spec'd narrowly** rather than discarded. Recommend a maintainer disposition: discard if R244's typed-`Outcome<T>` migration is deemed to cover the remaining paths, else re-scope. |

## B. Outdated — needs re-spec (premise or targets materially changed) (15)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. The first twelve
carried over from the prior audit (all re-verified — several worsened this
window); the last three (R95, R24, R121) are new to §B.

| Item | Status | Why re-spec |
|---|---|---|
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; worse this window.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm (the fetcher-only fallback) R276 deleted — `PojoResultType` permits only `Backed` (`GraphitronType.java:119-120`); `ResultType` is a 4-arm seal (`:93-95`). Additionally its primary migration target `FetcherEmitter.propertyOrRecordValue` **no longer exists** (R303 reshuffled `FetcherEmitter`); only the `GeneratorUtils.buildFkRowKey` (`:234`) half of the duplication survives, weakening the "evaluated at every site" premise. Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. |
| **R13** faceted-search | Spec | Central seam `ConnectionSynthesis.buildPlan()` gone (synthesis now in `ConnectionPromoter`; both `ConnectionSynthesis` and `buildPlan` return zero hits); `FieldWrapper.Connection` is now a 2-arg record `(boolean connectionNullable, int defaultPageSize)` with no `connectionName` (`FieldWrapper.java:73-76`). Phases 2–4 are written entirely against the retired pipeline. |
| **R71** recordn-key-parity-lifter | Backlog | R110 replaced `@batchKeyLifter` with `@sourceRow`; `BatchKeyLifterDirectiveResolver` → `SourceRowDirectiveResolver` (zero hits for the old directive/resolver), and `LifterRowKeyed` has already been split into `LifterLeafKeyed`/`LifterPathKeyed` (`SplitRowsMethodEmitter.java:241`). The title + body name a removed directive *and* propose splitting a seal (`LifterRowKeyed`) that no longer exists. Retitle and re-anchor on the current symbols. |
| **R46** service-multi-tenant-fanout | Backlog | R190 (Done) sealed `GraphitronContext` + a `newExecutionInput(...)` factory, dissolving the `ContextValueRegistration<FanOut>` design the whole "Design" section (lines 27-117) rests on (`ContextValueRegistration` has zero `.java` hits). Body still links the dead `typed-context-value-registry.md` slug at lines 17 and 156 (renamed to `tenant-routing-and-execution-input.md` = R45, Spec). |
| **R19** history-squash | Ready | Every SHA/count is an April-2026 snapshot: metrics table reads "595 (as of 2026-04-24)" and prose targets "566 commits" (lines 14/28/124/286), with frozen `8a8c5efe`/`ab3daff2` SHAs. Branch is now **2 662 ahead** of `origin/main` (up from 2 572 at the prior audit — ~4.7× the documented 566). All numbers/SHAs/drop-lists need regeneration before this can execute. |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | Premise says R222 *already* collapsed `JooqRecordInputType` with a specific rejection — but `JooqRecordInputType` still exists (`GraphitronType.java:342`), the quoted rejection string ("…but not a TableRecord…") is absent from the code, and **R222 is still Spec** (the dimensional model that landed was R290, a different item). Forward-dated premise; rephrase as conditional-on-R222 or fold into R222. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines"; `TypeFetcherGenerator.java` is now **5 398** (R303 grew it +19 this window; ~3.3× the cited figure). Re-evaluate decompose-vs-document against real size, or stop hard-coding a count. |
| **R263** decode-helper-typename-first-resolution | Backlog | Trap still exists (`resolveDecodeHelperForTable` at `BuildContext.java:2000`, disclaimer at `:2086`), but motivating dependency R195 shipped by sidestepping it — the "next caller" justification is now hypothetical. Re-justify or drop (priority-3). Lightest item in this section. |
| **R269** nullable-to-one-record-into-npe | Spec | R268 shipped (helpers take a `sourceExpr`) and R271 (dunder retirement) shipped, renaming `__elt`→`element`/`__k`→`key` — so the spec's `__elt`/`__k` snippets (lines 22-23, 29, 35) are doubly stale, and the "if R268 lands first … if R269 lands first" coordination fork (lines 62-70) is resolved. Re-verified as the **only** live spec quoting dunder locals. Strip the snippets and the R268 framing. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (`R58TypedRejectionPipelineTest` in tree) — the "R58 is currently *In Review* … if it reverts" framing (lines 21-23) is false. Carriers are still String-flattened, so the body stays valid; remove the stale dependency prose. |
| **R261** wire-coercion-cast-guard | Backlog | The specific reported instance (`(SakRecord) raw.get(...)`, site A) is handled by shipped R195 (`CallSiteExtraction.NodeIdDecodeRecord`, present across 7 files). Update the coordination framing to past tense; the raw-cast invariant for sites B–E is still unbuilt. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)"; R94 shipped (Done). Blocker cleared — drop the blocked framing from title and body. Note R94's own Done note hands the real remaining dependency to **R98** ("R170 picks up the live invalid-input round-trip the moment R98 ships its first SDL constraint"), so re-point the dependency there. |
| **R95** routines-as-data-model-citizens | Backlog | **Re-bucketed from §C → §B this window.** Two problems: (1) the claim that `RoutineReflection` "already lives in graphitron-common next to TableReflection" is **false** — it lives only in legacy `graphitron-java-codegen` (`mappings/RoutineReflection.java`); zero hits under `graphitron-common`. (2) New item **R300** (`jooq-routine-fields`) overlaps R95's scope heavily and `depends-on: [dimensional-model-pivot]` rather than R95, with neither item cross-referencing the other. Reconcile: R300 is the dimensional-axis mapping (RoutineCall carrier + Procedure intent), R95 is the data-model framing — decide supersedes/merge/precursor and cross-link, then fix the `graphitron-common` claim. |
| **R24** nodeidreferencefield-join-projection-form | Backlog | **Re-bucketed from §C → §B this window.** Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **no longer exists** (dispatch is now `FetcherEmitter.bind`/`bindRaw` at `:179`/`:198`; only stale javadoc at `ChildField.java:960,987` still names it), and the carrier in the **item title** — `NodeIdReferenceField` — was renamed by R50 to `ColumnReferenceField`/`CompositeColumnReferenceField` (`ChildField.java:280`). The surviving "not yet implemented" stub is for `CompositeColumnReferenceField` at `FetcherEmitter.java:507-516`. The work is valid + unbuilt, but title symbol + method names need a full re-anchor. |
| **R121** lsp-diagnostic-redundant-splitquery-on-record | Backlog | **NEW flag — mooted/reframed by the `@record` retirement (R301/R307, Done).** Its premise is an LSP diagnostic mirroring "R3's `@record`-parent build warning" for `@splitQuery` on a field whose enclosing type carries `@record`. R307 retired `@record` as a live binding, deleted the LSP's `@record` className tooling, and swapped that build warning's trigger from a redundant `@record` to a redundant `@splitQuery`. The grounding the item mirrors no longer exists in the described form. Re-spec against the new `@splitQuery`-redundancy warning the classifier now emits, or **discard** if the LSP-side mirror is no longer wanted. |

## C. Outdated — update references only (work valid, refs stale) (11)

Substance intact; only paths, line numbers, or dependency tense drifted. The
recurring root cause is unchanged: `FieldBuilder.java` (now **5 661** lines) /
`TypeFetcherGenerator.java` (now **5 398**) standing at ~2.6–3.3× the sizes the
older specs cite, plus a `docs/*.md` → `docs/*.adoc` migration. R303's
`FetcherEmitter`/`TypeFetcherGenerator` reshuffle re-drifted several anchors this
window — exactly as observation 3 predicts.

| Item | Stale reference |
|---|---|
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck through as "Obsolete (R287)". Surviving Id-arm anchors drifted: `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` is cited at "561-610" but is now at **`:944`** (R303 reshuffle); the `[ID!]`-admission diagnostic cited at "`BuildContext.java:680-699`" is now **`:617-625`**. Re-anchor at the symbols. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` was **not** touched by R303/R290, so the substance holds. Anchors drifted (the file is older): argument-level `@condition` rejection (`argCondition`) is at **`:410`** (body cites ~438-440); input-field `@condition(override:true)` admission is at **`:457-463`** (body cites :482-498). Emit-half still unbuilt. |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`. The two cited sibling docs (`docs-as-index-into-tests.md`, `rewrite-docs-entrypoint.md`) do not exist under any extension — relocate or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (absent) and stale `ConnectionSynthesis` naming (now `ConnectionPromoter`). |
| **R99** lsp-submodule-sibling-classpath | Path `graphitron-rewrite/graphitron-maven/…` is actually `graphitron-maven-plugin/…`; `getAllProjects()` cited ~`:113` is now `AbstractRewriteMojo.java:208`. |
| **R92** catalog-check-constraint-validation | Line refs into `TypeFetcherGenerator`/validator seam drifted: `validatorPreStep` cited `:1326` is now `:1542` (called `:1435`, emit `:1579`); `DefaultValidatorHolder`/`getValidator` cited `:76-85`/`:87-97` are now `:84-93`/`:95-99`. |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue` emission cited at `:1456`/`:1496`/`:1508`/`:1769` is now clustered at `TypeFetcherGenerator.java:1915-1968` (descriptive anchor `:1876`). |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog.reflectTableMethod` cited `:498` is now **`:525`**; `buildQueryTableMethodFetcher` cited `:1035` is now `TypeFetcherGenerator.java:1103`. |
| **R236** validator-reference-candidate-hint-terminal-table | (Prior audit's "README cites" framing was wrong — the anchors are in the **item body**, lines 17/27.) `classifyInputFieldInternal` is at `:1627`, but the candidate-hint failure-aggregation block (`columnSqlNamesOf(...)` at `:1714`) moved to ~`:1701-1718` (body cites `:1665-1677`). Re-anchor at the symbol. |
| **R35** source-orientation-javadocs | LOC counts grossly stale (FieldBuilder cited "2 172", now **5 661**; TypeFetcherGenerator "1 646", now **5 398**); doc cross-links use `.md` and should be `.adoc`. The "FieldBuilder decomposition shipped under R6" claim stays dubious given the file is now ~2.6× larger. |
| **R279** field-first-classification-driver | **NEW (light).** Status Ready. References the `ConstructorField` verdict + its `@ProjectionFor` sibling (lines ~205-206) in mixed tense; R290 **dissolved** `ConstructorField`. Confirm no live instruction depends on the deleted verdict and align the tense before this Ready item is picked up. |

**Dropped from §C this window:** **R267** nodeid-encoder-deprecated-convert — re-verified and **no longer flagged**. The bug is still live (`NodeIdEncoderClassGenerator.java:227` emits `.getDataType().convert(...)`, with `@SuppressWarnings({"deprecation","removal"})` at `:146`), but the file already lives at the correct `generators/util/` path the prior audit asked to fix, and the item references symbols (not line anchors), so nothing is stale. Current.

## Cross-cutting observations

1. **The orphaned-closure backlog did *not* grow this cycle.** All nine items
   that left the board (R290, R293, R299, R301, R303, R307, R284, R264, R306)
   self-closed cleanly (Done/duplicate-close + file deleted), and pruned their
   slugs from dependents (R304's `depends-on:` is empty, not dangling). R201,
   R146 (discarded-by-sibling) and R30 (self-Done, unswept) remain the only
   stranded files — unchanged across **five** audits. The workflow rule that *the
   closing author deletes the file* still has no owner when the closure comes from
   a sibling item (mooted/absorbed). Worth a one-shot cleanup pass and a workflow note.
2. **R290's landing is this cycle's highest-leverage staleness source** (the
   role R276 played in prior audits). Deleting `LeafTupleAdapter` and dissolving
   `ConstructorField` did *not* strand any spec — R302 correctly attributes both
   deletions to R290 in the past tense, and R279's only exposure is a mixed-tense
   reference (§C). The clean handling is the well-behaved-descendant pattern
   (R302/R305/R308 all track R290's closure rather than going stale against it).
3. **Line-drift recurs from ordinary refactors.** R303 (reify inline datafetchers)
   re-drifted R242's Id-arm anchors (`buildSingleRecordIdFromReturningFetcherValue`
   → `:944`) and R24's dispatch method (`dataFetcherValue` → `bind`/`bindRaw`) in
   this single window, while `MutationInputResolver.java` — untouched by R303 —
   kept R245's anchors valid. The contrast is the sharpest evidence yet that specs
   anchored to **symbol names** rather than line numbers would stop the recurrence;
   `FieldBuilder.java` (5 661) / `TypeFetcherGenerator.java` (5 398) bloat is the
   structural backdrop behind most of §C.
4. **The `@record` retirement (R301/R307) bounds a small new staleness class.**
   `@record` survives as a *declared-but-ignored* directive; specs referencing it
   as a type-shape label ("record-backed parent") remain valid, but the one item
   building tooling on its *live-binding* behaviour — **R121** — is mooted/reframed
   (now in §B). R51 was checked and is fine (it names the `RecordField` Java type,
   not the directive).
5. **R271 (dunder retirement) still bounds a small staleness class.** Re-verified
   that only **R269** quotes generated locals by their old `__`-prefixed names;
   future specs should quote the readable names (`element`, `row`, `key`, `byPk`).
6. **`classification-test-dsl-inventory.md` has gone stale this window and now
   warrants a banner.** R281's permanent retirement inventory records every
   migrated verdict in the *pre-R299* `(producer, mapping)` vocabulary (e.g. the
   `CONSTRUCTOR_FIELD → ChildField.ConstructorField` row at line 72), but R299
   migrated the corpus to `(carrier, intent, mapping)` and R290 dissolved
   `ConstructorField` (its `constructor` corpus example became a rejection
   fixture). A reader treating the inventory as current would be misled. It is a
   dated artifact, not a roadmap item, so it stays — but it should get a
   "superseded by R299/R290 — historical" header (or a note in the next changelog).
   Left unedited here per scope.
7. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-06-15._
