# Roadmap staleness audit — 2026-06-05

A point-in-time review of every active roadmap item under
[`graphitron-rewrite/roadmap/`](../) against the **current** state of the
codebase on `claude/graphitron-rewrite`. The goal was to find items whose
premise no longer holds: work already shipped, constructs renamed or removed,
dependencies that have since landed, or specs grown stale enough to mislead an
implementer.

This file is an analysis artifact, not a roadmap item: it lives in a
subdirectory so the roadmap-tool (which scans `roadmap/*.md` non-recursively and
requires `id:` front-matter on each) ignores it, and it is Markdown so the
`check-adoc-tables` build step (which scans `.adoc` only) leaves it alone.

## Scope and method

All **115** entries were reviewed (114 `R<n>` items plus the non-item
`inference-axis-coverage.adoc` placeholder). For each item the targets it names
(classes, directives, methods, packages, modules) were located in the current
tree, the described problem was checked for whether it still reproduces, and
`changelog.md` was grepped for the item's `R<n>` and key terms to catch work
that shipped without the item being closed.

**Result: 35 items flagged, 79 current.** Line numbers cited below are as of the
audit date and will themselves drift.

## A. Obsolete — work already shipped or superseded (8)

These should leave the active roadmap. Two are `In Review` and require an
independent reviewer session to flip to Done per the workflow reviewer-rule;
the rest are mechanical deletes/discards.

| Item | Status | Action | Why |
|---|---|---|---|
| **R255** dedupe-reference-projection | In Review | Mark Done + delete | Fix landed: `$fields()` is a `LinkedHashSet` (`TypeClassGenerator.java:202`); `DedupeReferenceProjectionPipelineTest` exists. |
| **R254** schema-class-bounded-emission | In Review | Mark Done + delete | Both moves implemented (`GraphitronSchemaClassGenerator.java:204` statement-per-element; `ObjectTypeGenerator` helper-method sink); `SchemaEmissionChainDepthPipelineTest` exists. |
| **R214** infer-argmapping-for-unambiguous-signatures | Backlog | Mark Done + delete | Spec fully implemented (written in past tense): `ServiceCatalog.inferBindingsByType:1039`, both fixtures + `ServiceCatalogTest` cases present. Never flipped. |
| **R227** translate-md-tables-in-mdbodytoadoc | Backlog | Mark Done + delete | Already in `roadmap-tool/Main.java`: `mdBodyToAdoc:778` detects MD tables, `emitAdocTable:861` synthesises the AsciiDoc block. (Only GFM alignment markers unhandled.) |
| **R201** honor-field-directive-in-payload-construction-shape | Backlog | Discard/Delete | Changelog R244 says it "moots R201" — root `@service` outcome path migrated to typed `Outcome<T>`, retiring the targeted construction path. |
| **R146** mutation-cardinality-safety-unique-index | Backlog | Discard/Delete | Changelog R246: "Absorbs R146 (PK-or-UK coverage, discarded)"; `JooqCatalog.candidateKeys:527` + `UpdateRowsWalker` PK-or-UK matching shipped. |
| **R64** runtime-stub-takes-deferred-rejection | Backlog | Discard/Delete | Premise dissolved: `SplitRowsMethodEmitter.unsupportedReason` gone, the 4 named call sites gone, stubs now emit inline as `CodeBlock`. |
| **R30** selection-parser-audit | **Done** | Delete the file | Status is Done but the file lingers; per workflow, Done items are deleted. Conclusion was sound. |

## B. Outdated — needs re-spec (premise or targets materially changed) (14)

Still wanted in spirit, but a reviewer/implementer reading the current spec body
would be misled. Re-spec before the next state transition.

| Item | Status | Why re-spec |
|---|---|---|
| **R13** faceted-search | Spec | Central seam `ConnectionSynthesis.buildPlan()` gone (synthesis now in `ConnectionPromoter`); `FieldWrapper.Connection` is a 2-arg record without `connectionName`. Phases 2–4 must be rewritten. |
| **R71** recordn-key-parity-lifter | Backlog | R110 replaced `@batchKeyLifter` with `@sourceRow`; `BatchKeyLifterDirectiveResolver` → `SourceRowDirectiveResolver`, `LifterRowKeyed` split into `Lifter{Leaf,Path}Keyed`. Title names a removed directive. |
| **R46** service-multi-tenant-fanout | Backlog | R45 records that R190 sealing the interface dissolved R46's design (`ContextValueRegistration<FanOut>` no longer exists); body also links the dead `typed-context-value-registry.md` slug. |
| **R19** history-squash | Ready | Every SHA/count is an April-2026 snapshot: targets "566 commits"; branch is now ~2386 ahead of `origin/main`. All numbers/SHAs/drop-lists need regeneration. |
| **R180** record-parent-column-read-helper | Spec | Built around the 5-arm `ResultType` including `PojoResultType.NoBacking`, which R276 (In Progress) is deleting. Re-spec for a 4-arm `ResultType` after R276 lands. |
| **R234** jooq-embedded-and-udt-input-backings | Backlog | Premise says R222 already collapsed `JooqRecordInputType` with a specific rejection — but that type still exists/is constructed, the rejection string is absent, and R222 is still Spec. |
| **R7** decompose-typefetchergenerator | Backlog | Figure badly stale: says "1,646 lines"; `TypeFetcherGenerator.java` is now ~5,034. Re-evaluate decompose-vs-document against real size. |
| **R263** decode-helper-typename-first-resolution | Backlog | Trap still exists, but motivating dependency R195 shipped by sidestepping it — the "next caller" justification is now hypothetical. Re-justify or drop (priority-3). |
| **R259** fk-key-hint-scope-and-namespace | Backlog | Core ask already implemented (`BuildContext.fkCandidateNames` scopes + renames); only the `unknownForeignKeyRejection` surface still global. Re-spec down to the residual or delete. |
| **R269** nullable-to-one-record-into-npe | Spec | R268 shipped — helpers already take `sourceExpr`/use the `element` local. Strip stale `__elt`/`__k` refs and the now-historical "Coordination with R268" section. |
| **R149** r147-followup-publish-diagnostics-tests | Backlog | First deferred test delivered by R196 (`BuildTriggerPublishesDiagnosticsTest`). Drop the completed half; only the `buildOutput()` report-population test remains. |
| **R66** rejection-string-carrier-widening | Backlog | R58 shipped — the "R58 is currently In Review… if it reverts" framing is false. Carriers still String-flattened, so the body stays valid; remove the stale dependency prose. |
| **R261** wire-coercion-cast-guard | Backlog | The specific reported instance (`(SakRecord) raw.get(...)`) is now handled by shipped R195 (`CallSiteExtraction.NodeIdDecodeRecord`). Update the coordination framing; the raw-cast invariant is still unbuilt. |
| **R170** validator-integration-execute-coverage | Backlog | Titled "(R94-blocked)"; R94 shipped. Blocker cleared — drop the blocked framing and treat as directly actionable. |

## C. Outdated — update references only (work valid, refs stale) (13)

Substance is intact; only paths and line numbers drifted. Two root causes
dominate: a `docs/*.md` → `docs/*.adoc` migration, and `FieldBuilder.java`
(~5,346) / `TypeFetcherGenerator.java` (~5,034) growing ~2.5x.

| Item | Stale reference |
|---|---|
| **R8** docs-as-index-into-tests | `code-generation-triggers.md` is now `.adoc`; all `.md` doc refs stale. |
| **R17** generated-output-walkthrough | Proposes a `.md` target + `.md` siblings; should be `.adoc`. |
| **R10** drop-assembled-schema-rebuild | Cites a deleted "firstclass-connection-types" item and stale `ConnectionSynthesis` naming (now `ConnectionPromoter`). |
| **R95** routines-as-data-model-citizens | Claims `RoutineReflection` "already lives in graphitron-common"; it only exists in the legacy java-codegen module. |
| **R99** lsp-submodule-sibling-classpath | Path `graphitron-rewrite/graphitron-maven/…` is actually `graphitron-maven-plugin/…`. |
| **R267** nodeid-encoder-deprecated-convert | Bug live, but path is `generators/util/NodeIdEncoderClassGenerator.java`, not `generators/`. |
| **R92** catalog-check-constraint-validation | Line refs into `TypeFetcherGenerator` drifted (file grew ~3x). |
| **R242** dml-payload-positional-alignment | `FetcherEmitter` line refs drifted (`561-610` → ~`754`/`840`). |
| **R103** lift-jooq-column-defaults | `DSL.defaultValue`/`defaulted()` anchor lines stale; relocate before Spec. |
| **R24** nodeidreferencefield-join-projection-form | `FetcherEmitter#dataFetcherValue` stub now ~382-397, not "140-162". |
| **R240** tablemethod-return-type-token-threading | `ServiceCatalog`/`TypeFetcherGenerator` line refs drifted. |
| **R236** validator-reference-candidate-hint-terminal-table | Cited `:1665-1677`; actual `~:1825`. |
| **R35** source-orientation-javadocs | LOC counts stale; the "FieldBuilder decomposition shipped under R6" claim is dubious given the file is now larger. |

## Cross-cutting observations

1. **Workflow hygiene.** R30 is `Done` but its file was not deleted.
2. **Pervasive line-drift.** `FieldBuilder.java` and `TypeFetcherGenerator.java`
   have grown ~2.5x; nearly every item citing them by line is stale. Anchoring
   future specs to symbol names rather than line numbers would prevent
   recurrence, and the drift itself strengthens the case for R7 (decompose) and
   R35.
3. **Renamed-slug dead links.** R45 was renamed
   `typed-context-value-registry.md` → `tenant-routing-and-execution-input.md`;
   R46 (and R47) still link the old slug.
4. **`inference-axis-coverage.adoc`** is an intentional CI-regenerated
   placeholder, not a roadmap item (no `R<n>`), correctly empty by design.
