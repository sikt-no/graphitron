# Roadmap staleness audit — 2026-06-16

A point-in-time review of every active roadmap item under
[`graphitron-rewrite/roadmap/`](../) against the **current** state of the
codebase on `claude/graphitron-rewrite` (HEAD `18d3aee1`, 2026-06-15 21:50). The
goal is to find items whose premise no longer holds: work already shipped,
constructs renamed or removed, dependencies that have since landed, or specs
grown stale enough to mislead an implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

This audit supersedes the `2026-06-15` staleness audit, which has been deleted —
only the latest audit is retained. (The sibling `classification-test-dsl-inventory.md`
in this directory is **not** a staleness audit: it is R281's permanent
corpus-retirement inventory. It is left in place, but see observation 6 — it
remains materially stale and still warrants a "superseded" banner that has not
yet been added.)

## Changes since the 2026-06-15 audit

**51 commits** landed between the prior audit commit (`79dc9cbc`, committed
2026-06-15 03:18) and this one (HEAD `18d3aee1`, 2026-06-15 21:50). The dominant
events this window are the **`@field(name:)` input-side symmetry landing** (R200)
and the **R305 carrier-dimension expansion**, which shipped slices 2–3 —
enriching `Carrier` with a sealed `Source{shape, cardinality}` arm and
**collapsing `SingleRecordTableField` into `RecordTableField`**.

Four items reached a terminal state and **all deleted their files cleanly** — no
new stranded tombstones:

- **R200** (`InputBeanResolver` `@field(name:)`) → Done (`e7be7f4`). Honors
  `@field(name:)` for `@service` input-bean/record member binding — the input-side
  mirror of R191's output accessor axis. Its Done note explicitly states
  "**R201 / R202 carry the remaining `@field`-symmetry items**," which is the
  single most consequential staleness signal this window (see §A/§B below).
- **R309** `query-as-view-projection-descriptions` → Done (`82f2ba3`). Comment-carrier
  descriptions in corpus doc-example projections; test/docs tooling only.
- **R312** `decode-registry-threading` → Done (`2524d8c`). Threaded
  `CompositeDecodeHelperRegistry` through the inline/split reference-field filter
  emitters. (Bears on R263 — see §B.)
- **R313** `scalar-alias-name-mismatch` → Done (`adfaeff` + build-through `43645d9`).
  Routed aliasing `@scalarType` scalars through `ScalarResolution.Synthesised`.

**R305** (`collapse-singlerecordtablefield-into-recordtablefield`, **In Review**)
shipped its code this window — `SingleRecordTableField` is **deleted**; it now
survives only as nine past-tense javadoc/comment references ("former
`SingleRecordTableField`"). The item itself stays In Review (one rework round on
the source-shape mirror, `18d3aee1`), so its file correctly remains.

New items filed this window: **R310** (DML-carrier forbidden-directive diagnostic,
Ready), **R311** (jOOQ `TableRecord` `@service` input param, Spec), **R314**
(dissolve re-fetch/reentry leaves via dimensional emit, Backlog). All carry sane
front-matter; R314's `depends-on:` correctly resolves to the live
`collapse-singlerecordtablefield-into-recordtablefield` and `dimensional-model-pivot`
slugs. A full dangling-`depends-on` sweep across all items found **none**
(observation 1).

**Net effect on the flag counts:** No previously-flagged item dropped off the
board, but the board moved as follows: **R201** is **re-bucketed from §A
(obsolete) up to §B (re-spec)** because R200 shipped its input-side sibling and
explicitly named R201 as a remaining symmetry item — undercutting the "discard"
reading; and **R202** is **newly flagged into §C** for the same reason (its
premise is live and unbuilt; only line anchors drifted). Result: **30 items
flagged, 85 current** (up from 29/84).

## Scope and method

All **115** entries were reviewed (114 `R<n>` item files plus the non-item
`inference-axis-coverage.adoc` placeholder). For each item the targets it names
(classes, directives, methods, packages, modules) were located in the current
tree under `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/`,
the described problem was checked for whether it still reproduces, and the
changelog was scanned for the item's `R<n>` and key terms to catch work that
shipped without the item being closed.

Two structural landings continue to dominate the staleness picture and were
re-verified: **R276** (deleted `PlainObjectType` / `PojoResultType.NoBacking` /
`UnbackedPojoResult` — `PojoResultType` permits only `Backed` at
`GraphitronType.java:119-120`; `ResultType` is a 4-arm seal at `:93-95`) and
**R290** (deleted `LeafTupleAdapter` — zero hits in `src/main`; dissolved
`ConstructorField` — only a historical-prose comment survives at
`FieldBuilder.java:855`). New this window: **R305**'s `SingleRecordTableField`
collapse (the class is gone; `ChildField` switches now case on `RecordTableField`).

**Result: 30 items flagged, 85 current.** Line numbers cited below are as of the
review date and will themselves drift — see observation 3.

## A. Obsolete — should leave the active roadmap (2)

Each was superseded or discarded by a sibling item already at Done. Because the
closure came from the sibling rather than a self-transition, no author ran the
file-deletion sweep. (Both were stranded across the prior five audits — now six.)

| Item | Status | Action | Why |
|---|---|---|---|
| **R30** selection-parser-audit | **Done** | **Delete the file** | Status is `Done` (re-confirmed); per workflow (`workflow.adoc:21,74`) Done items are deleted. Re-verified the **sole** stranded Done tombstone in the roadmap (`status: Done` grep returns exactly one file). Nothing `depends-on` it and it carries no README rollup row, so it is not a build risk — purely an unswept tombstone. If the "parser is needed, keep it" finding is worth retaining, capture it in `changelog.md` first, then delete. **Clean delete — unambiguous.** |
| **R146** mutation-cardinality-safety-unique-index | Backlog | **Discard → delete** (via transition) | R246 (Done) changelog: "**Absorbs R146 (PK-or-UK coverage, discarded)**." `JooqCatalog.candidateKeys` (`JooqCatalog.java:527`) feeds `walker/MatchedKeys.java:37` → `UpdateRowsWalker` PK-or-UK matching, shipped and tested. The file's `status: Backlog` is stale; the correct mechanism is a `Backlog → Discarded` transition (which couples with file deletion per `workflow.adoc:23`), not a raw delete of a Backlog file. |

**Dropped from §A this window:** **R201** honor-field-directive-in-payload-construction-shape
— re-bucketed up to §B (re-spec). The prior audit parked it here under "R244
moots R201," with a hedge to re-scope rather than discard. This window resolves
the hedge against discard: **R200** shipped the input-side `@field(name:)` axis
and its Done note explicitly names "R201 / R202 carry the remaining `@field`-symmetry
items," and `PayloadConstructionShape.java` is confirmed **live** on the
non-deferred `PayloadClass` error-channel arm (`FieldBuilder.resolvePayloadConstructionShape`
at `:484`, called at `:2221`). R201 is the unbuilt output-side mirror of R200 —
wanted, not obsolete. See §B.

## B. Outdated — needs re-spec (premise or targets materially changed) (16)

Still wanted in spirit, but the current spec body would mislead an
implementer/reviewer. Re-spec before the next state transition. Fifteen carried
over from the prior audit (all re-verified — several worsened this window); the
sixteenth (R201) is re-bucketed up from §A.

| Item | Status | Why re-spec |
|---|---|---|
| **R201** honor-field-directive-in-payload-construction-shape | Backlog | **Re-bucketed from §A → §B this window.** Premise is live: `PayloadConstructionShape.java` is reached on the still-live `PayloadClass` arm (`resolvePayloadConstructionShape` at `FieldBuilder.java:484`, called `:2221`; the mutable-bean predicate's `javaBeanSetterName` at ~`:596`), and R244 itself preserves that arm. R200 just shipped the **input-side** `@field(name:)` axis and explicitly hands "the remaining `@field`-symmetry items" to R201/R202 — so R201 is the wanted **output-side mirror**, not a discard. Re-spec: drop the "R244 moots R201" framing (R244 only covered the migrated `Outcome<T>` paths), re-anchor the construction-shape lines, and frame it as the output payload-construction counterpart of R200. |
| **R180** record-parent-column-read-helper | Spec | **Highest-value re-spec; still worsening.** The whole spec is built on a 5-arm `ResultType` whose `NoBacking` arm R276 deleted — `PojoResultType` permits only `Backed` (`GraphitronType.java:119-120`); `ResultType` is a 4-arm seal (`:93-95`). Its primary migration target `FetcherEmitter.propertyOrRecordValue` **no longer exists** (zero hits); only the `GeneratorUtils.buildFkRowKey` (`:241`) half of the duplication survives. Re-spec against the collapsed `Backed`-only model and the single surviving callsite, or close. |
| **R13** faceted-search | Spec | Central seam `ConnectionSynthesis.buildPlan()` gone (synthesis now in `ConnectionPromoter`; both `ConnectionSynthesis` and `buildPlan` return zero hits — entry points `ConnectionPromoter.promote`/`.rebuildAssembledForConnections` at `GraphitronSchemaBuilder.java:279-281`); `FieldWrapper.Connection` is now a 2-arg record `(boolean connectionNullable, int defaultPageSize)` with no `connectionName` and no facet slot (`FieldWrapper.java:73-76`). Phases 2–4 are written entirely against the retired pipeline. |
| **R71** recordn-key-parity-lifter | Backlog | **Worse than line-drift; heavier re-spec.** R110 replaced `@batchKeyLifter` with `@sourceRow` (`BatchKeyLifterDirectiveResolver` → `SourceRowDirectiveResolver`, zero hits for the old directive/resolver). Beyond that, the **entire `BatchKey` lifter taxonomy the body proposes splitting** (`LifterRowKeyed`/`RowKeyed`/`MappedRowKeyed`/`RecordKeyed`) no longer exists as sealed types — they survive only as stale comments (`SplitRowsMethodEmitter.java:254`, `FieldBuilder.java:4287`). R222 replaced the model with a flat `SourceKey` record carrying orthogonal `Wrap{Row,Record,TableRecord}` and `Reader` slots. The "widen-or-split a seal" premise is moot under slots-over-permits. Re-anchor on `SourceKey.Wrap` + the `@sourceRow` resolver. |
| **R46** service-multi-tenant-fanout | Backlog | R190 (Done) sealed `GraphitronContext` to its generated `Impl` (`GraphitronContextInterfaceGenerator.java:12-13`) + a `newExecutionInput(...)` factory (`GraphitronFacadeGenerator.java:116`), dissolving the `ContextValueRegistration<FanOut>` design the whole "Design" section rests on (`ContextValueRegistration` has zero `.java` hits). Body still links the dead `typed-context-value-registry.md` slug at lines 17/156 (renamed to `tenant-routing-and-execution-input.md` = R45, Spec). |
| **R19** history-squash | Ready | Every SHA/count is an April-2026 snapshot: metrics table reads "595 (as of 2026-04-24)" and prose targets a "566-commit history" (lines 14/22/24), with frozen `8a8c5efe`/`ac3df0b7` SHAs. Branch is now **2 714 ahead** of `origin/main` (up from 2 662 at the prior audit — ~4.7× the documented 566). All numbers/SHAs/drop-lists need regeneration — or, better, computing dynamically at execution time — before this can execute. |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | Premise says R222 *already* collapsed `JooqRecordInputType` with a specific rejection — but `JooqRecordInputType` still exists (`GraphitronType.java:342`), the quoted rejection string ("…but not a TableRecord…") is absent from the code, and **R222 is still Spec**. R222 was *amended* this window (slices 1–2 enriched the carrier) but **neither landed slice collapsed the input arm** — the premise stays forward-dated. Rephrase as conditional-on-R222 (gate it behind R222 reaching Done with that collapse shipped) or fold into R222. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: body says "1 646 lines"; `TypeFetcherGenerator.java` is now **5 409** (~3.3× the cited figure). Lightest re-spec in this section: refresh the LOC/method-count figures and re-survey the cut against the real file, or stop hard-coding a count. |
| **R263** decode-helper-typename-first-resolution | Backlog | **Now leaning discard.** Trap still exists (`resolveDecodeHelperForTable` at `BuildContext.java:2000`, disclaimer `:2086`), but this window **R312** became the *second* consumer (after R195) to **sidestep** it — R312 threaded `CompositeDecodeHelperRegistry` through the reference-field filter emitters without ever calling `resolveDecodeHelperForTable` (its callers remain `NodeIdLeafResolver.java:261`, `FieldBuilder.java:1123`, and two BuildContext-internal sites, all pre-R312). Two consecutive sidesteps weaken — not re-justify — the "trap waiting to bite the next caller" motivation. Re-spec only if a caller that *must* route an authoritative `@nodeId(typeName:)` through this path is identified; otherwise **discard as speculative**. |
| **R269** nullable-to-one-record-into-npe | Spec | R268 shipped (helpers take a `sourceExpr`) and R271 (dunder retirement) shipped, renaming `__elt`→`element`/`__k`→`key` — so the spec's `__elt`/`__k` snippets (lines 22-23, 29, 35) are now **triply stale** (helpers now at `GeneratorUtils.java:318`/`:338`), and the "if R268 lands first … if R269 lands first" coordination fork (lines 62-70) is resolved (R268 landed first). The success-arm NPE itself is still unguarded. Strip the snippets and the R268 framing; re-anchor on `element`/`key`. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped (`R58TypedRejectionPipelineTest` in tree, R58 Done) — the "R58 is currently *In Review* … if it reverts" framing (lines 21-23) is false. Carriers are still String-flattened (changelog defers `ParsedPath.errorMessage`, `Unresolved.reason`, etc. explicitly to R66), so the body stays valid; remove the stale dependency prose (`depends-on: []` is already correct). |
| **R261** wire-coercion-cast-guard | Backlog | The specific reported instance (`(SakRecord) raw.get(...)`, site A) is handled by shipped R195 (`CallSiteExtraction.NodeIdDecodeRecord`, present across ~8 files). Update the coordination framing (lines ~100-105) to past tense; the raw-cast invariant for sites B–E is still unbuilt (R195's rejection is jOOQ-record-narrow). |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)"; R94 shipped (Done). Blocker cleared — drop the blocked framing from title and body. R94's own Done note hands the real remaining dependency to **R98** ("R170 picks up the live invalid-input round-trip the moment R98 ships its first SDL constraint"), so re-point the dependency there. |
| **R95** routines-as-data-model-citizens | Backlog | Two problems: (1) the claim that `RoutineReflection` "already lives in graphitron-common next to TableReflection" is **false** — it lives only in legacy `graphitron-java-codegen` (`mappings/RoutineReflection.java`); zero hits under `graphitron-common` (asserted twice, lines 38/77). (2) New item **R300** (`jooq-routine-fields`) overlaps R95's scope heavily and `depends-on: [dimensional-model-pivot]` rather than R95, with neither item cross-referencing the other. Reconcile (supersede/merge/precursor + cross-link) and fix the `graphitron-common` claim. |
| **R24** nodeidreferencefield-join-projection-form | Backlog | Not mere line-drift: the cited `FetcherEmitter#dataFetcherValue` method **no longer exists** (zero hits; dispatch is now `FetcherEmitter.bind`/`bindRaw` at `:179`/`:359`; only stale javadoc at `ChildField.java:960,987` still names it), and the carrier in the **item title** — `NodeIdReferenceField` — was renamed by R50 to `ColumnReferenceField`/`CompositeColumnReferenceField` (`ChildField.java:281`; note the new sibling `ParticipantColumnReferenceField` at `:315`). The surviving "not yet implemented" stub is for `CompositeColumnReferenceField` at `FetcherEmitter.java:499-507` (drifted from `:507-516` as the file shrank to 726 LOC). Work is valid + unbuilt, but title symbol + method names need a full re-anchor. |
| **R121** lsp-diagnostic-redundant-splitquery-on-record | Backlog | Mooted/reframed by the `@record` retirement (R301/R307, Done). Its premise mirrors R3's `@record`-parent build warning, but R307 retired `@record` as a live binding and the build warning's wording drifted from "`@record`-parent field" to "**record-backed** parent field" (`FieldBuilder.java:4445`); sibling redundancy warnings now also exist at `:4133` (R275) and `:4267` (`@sourceRow`). The `TypeContext.hasDirective(typeDef,"record")` predicate the item assumes is no longer the live binding. Re-spec against the current `record-backed`-parent warning (and decide whether to fold in the siblings), or **discard** if the SDL-only LSP can't mirror the reflection-derived determination. |

## C. Outdated — update references only (work valid, refs stale) (12)

Substance intact; only paths, line numbers, or dependency tense drifted. The
recurring root cause is unchanged: `FieldBuilder.java` (now **5 683** lines) /
`TypeFetcherGenerator.java` (now **5 409**) standing at ~2.6–3.3× the sizes the
older specs cite, plus a `docs/*.md` → `docs/*.adoc` migration. This window's
`FetcherEmitter` reshuffle (down to **726** LOC, from ~1 076) re-drifted several
anchors — exactly as observation 3 predicts. Eleven carried over; the twelfth
(R202) is newly flagged.

| Item | Stale reference |
|---|---|
| **R202** honor-field-directive-in-error-type-source-accessors | **NEW flag.** Premise live and unbuilt: `checkErrorTypeSourceAccessors` (`FieldBuilder.java:2422`) calls `ClassAccessorResolver.resolve(sourceClass, sdlField.getName(), …)` at `:2441-2446`, passing the raw SDL name with **no `@field` read** — exactly the gap R202 describes. R200's Done note names R202 as a remaining `@field`-symmetry sibling. Only staleness is line drift (body cites `:2281-2316`); re-anchor at the symbol. |
| **R242** dml-payload-positional-alignment | DELETE-Table-arm steps correctly struck as "Obsolete (R287)"; the body was *re-based onto R305's re-fetch substrate* this window (`2a322c3b`) so the narrative is current — but one numeric anchor went *more* stale: `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue` is cited at "`:944`" (3 sites) but is now at **`:594`** (FetcherEmitter shrank). The `[ID!]`-admission diagnostic at `BuildContext.java:617-625` is now correct. Re-anchor at the symbol. |
| **R245** wire-condition-emit-on-mutations | `MutationInputResolver.java` untouched, substance holds. Anchors drifted: argument-level `@condition` rejection (`argCondition`) is at **`:410`** (body cites ~438-440); input-field `@condition(override:true)` admission is at **`:455-464`** (body cites :482-498). Emit-half still unbuilt. |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`. The two cited sibling docs (`docs-as-index-into-tests.md`, `rewrite-docs-entrypoint.md`) do not exist under any extension — relocate or drop. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted `firstclass-connection-types.md` item (absent) and stale `ConnectionSynthesis` naming (now `ConnectionPromoter`). |
| **R99** lsp-submodule-sibling-classpath | Path `graphitron-rewrite/graphitron-maven/…` is actually `graphitron-maven-plugin/…`; `getAllProjects()` cited ~`:113` is now `AbstractRewriteMojo.java:208-209`. |
| **R92** catalog-check-constraint-validation | Seam anchors drifted: `validatorPreStep` cited `:1326` is now defined **`:1542`** (called `:1435`); `DefaultValidatorHolder` cited `:76-85` is now **`:84`**, `getValidator` cited `:87-97` now **`:95-99`**. |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue` emission cited at `:1456`/`:1496`/`:1508`/`:1769` is now clustered at `TypeFetcherGenerator.java:1915-1968` (descriptive anchor `:1876`). |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog.reflectTableMethod` cited `:498` is now **`:494`** and the strict `ClassName.equals` return-type gate moved to **`:524-525`** (the body's `:498` now lands on an unrelated null-check); `buildQueryTableMethodFetcher` cited `:1035` is now `TypeFetcherGenerator.java:1103`. Re-anchor at the symbols. |
| **R236** validator-reference-candidate-hint-terminal-table | (Anchors are in the **item body**, lines 17/27.) `classifyInputFieldInternal` is at `BuildContext.java:1627`, but the candidate-hint failure-aggregation block (`columnSqlNamesOf(...)` at `:1714`) moved to ~`:1706-1717` (body cites `:1665-1677`). Re-anchor at the symbol. |
| **R35** source-orientation-javadocs | LOC counts grossly stale (FieldBuilder cited "2 172", now **5 683**; TypeFetcherGenerator "1 646", now **5 409**); doc cross-links use `.md` and should be `.adoc`. The "FieldBuilder decomposition shipped under R6" claim stays dubious given the file is now ~2.6× larger. |
| **R279** field-first-classification-driver | Status Ready. References the `ConstructorField` verdict + its `@ProjectionFor` sibling (lines ~204-206), but on re-read the prose is already **past-tense** ("R290 … removed two enum rows … when it dissolved `ConstructorField`") and no live instruction depends on the deleted verdict. Lightest item in this section — confirm-only; barely actionable. |

**Re-confirmed Current (not flagged):** **R267** nodeid-encoder-deprecated-convert
— the deprecated `.getDataType().convert(...)` is still emitted
(`NodeIdEncoderClassGenerator.java`, `@SuppressWarnings({"deprecation","removal"})`),
but the file is at the correct path and references symbols, so nothing is stale.

## Cross-cutting observations

1. **The orphaned-closure backlog did *not* grow this cycle, and the dependency
   graph is clean.** All four items that left the board (R200, R309, R312, R313)
   self-closed cleanly (Done + file deleted) and a full `depends-on` sweep across
   all 114 items found **no dangling slugs** (no dependent points at a deleted
   Done item). R30 (self-Done, unswept) remains the **only** stranded Done
   tombstone — alongside R146/R201's discard-by-sibling Backlog files — unchanged
   across **six** audits. The workflow rule that *the closing author deletes the
   file* still has no owner when the closure comes from a sibling. Worth a
   one-shot cleanup pass and a workflow note.
2. **R200's landing is this window's highest-leverage staleness signal.** It
   shipped the input-side `@field(name:)` axis and explicitly named "R201 / R202
   carry the remaining `@field`-symmetry items," which (a) lifted **R201** out of
   "obsolete" into re-spec — its `PayloadConstructionShape` target is confirmed
   live on the `PayloadClass` arm — and (b) surfaced **R202** as a fresh §C flag
   (premise live, only line anchors drifted). The coherent through-line is the
   four-corner symmetry: R191 (output accessor, Done), R200 (input bean, Done),
   R201 (output payload-construction), R202 (`@error` accessor).
3. **Line-drift recurs from ordinary refactors — and shrinking files drift just
   as hard as growing ones.** `FetcherEmitter` *shrank* 1 076 → 726 this window
   (R305 slice 3 + R312), re-drifting R24's stub (`:507-516` → `:499-507`) and
   R242's `buildSingleRecordIdFromReturningFetcherValue` (`:944` → `:594`) — and
   R242 was *explicitly re-based onto R305 this window*, yet the prose update left
   the numeric anchor behind. Sharpest evidence yet that specs anchored to
   **symbol names** rather than line numbers would stop the recurrence;
   `FieldBuilder.java` (5 683) / `TypeFetcherGenerator.java` (5 409) bloat is the
   structural backdrop behind most of §C.
4. **R305's `SingleRecordTableField` collapse is this window's structural event
   and was well-handled.** The class is deleted (surviving only in past-tense
   comments), `ChildField` now switches on `RecordTableField`, and the
   well-behaved-descendant pattern held: R314 and R308 track R305 via `depends-on`
   rather than going stale against it, and R302 (`ChildField → SourceField`
   rename) is orthogonal — different hierarchy, rebases trivially. No spec was
   stranded by the collapse.
5. **R222 remains Spec but was amended (slices 1–2) without closing R234's gap.**
   The carrier was enriched with a sealed `Source{shape, cardinality}` arm, but
   the `JooqRecordInputType` collapse R234 forward-dates has **not** landed
   (`GraphitronType.java:342` still live) — R234 stays §B with a forward-dated
   premise.
6. **The `@record` retirement (R301/R307) continues to bound a small staleness
   class, and it drifted further this window.** `@record` survives as a
   *declared-but-ignored* directive; the one item building tooling on its
   *live-binding* behaviour — **R121** — stays mooted, and its mirrored build
   warning's wording moved from "`@record`-parent" to "record-backed parent
   field" (`FieldBuilder.java:4445`). `classification-test-dsl-inventory.md`
   remains stale against R299/R290 and still warrants the "superseded —
   historical" banner the prior audit recommended; it has **not** been added
   (left unedited here per scope).
7. **`inference-axis-coverage.adoc`** remains an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly excluded.

---

_Review date: 2026-06-16._
