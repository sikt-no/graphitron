---
id: R524
title: "Trim verbose javadoc and align comments with the terse-and-pinned conventions"
status: In Progress
bucket: cleanup
priority: 7
theme: docs
depends-on: []
created: 2026-07-24
last-updated: 2026-07-24
---

# Trim verbose javadoc and align comments with the terse-and-pinned conventions

Several main-source classes carry long narrative javadoc and comment blocks that were written while the design was still moving: they restate what the code already shows, walk through implementation history, or argue for decisions in paragraphs of unpinned prose. The R483 drift audit corrected claims that had become *wrong*, but its unit of work was accuracy, not length; a comment can be fully accurate today and still be the kind of prose that diverges as the code matures, because nothing mechanical breaks when it goes stale. The conventions have since firmed up (CLAUDE.md "Javadoc conventions": prefer terse over verbose, name live things via `{@link}` so the R492 reference gate checks them, no transient citations per the R482/R484 guards), and the existing comment stock predates them.

## Census (2026-07-24)

Measured over the `GuardScope.IN_SCOPE_MODULES` hand-authored trees (`src/main/java` + `src/test/java`): 1,098 files, 236k lines, of which 58.9k (24.9%) are comment lines; 43 contiguous comment blocks of 40+ lines and a further 242 of 25-39 lines. Top files by comment volume: `FieldBuilder` (2,896 comment lines), `TypeFetcherGenerator` (2,528), `BuildContext` (1,284, 41% of the file), `MultiTablePolymorphicEmitter` (781), `TypeBuilder` (748), `JooqCatalog` (713, 44%), `ChildField` (624, 46%), `GraphitronSchemaBuilder` (559), with `MutationField` (49%), `GraphitronType` (57%), and `ArgCallEmitter` (47%) leading by share. Largest single blocks: `ConnectionRuntimeClassGenerator` (118 lines), `MutationField.MutationBulkDmlRecordField` (60), `GraphitronTransactionProviderGenerator` (56), `JoinStep` (54).

Spot-checking the largest blocks shows they are not dead narration: they carry real contracts, several already pinned to named tests. But they are shot through with the drift-prone material the conventions ban. **The verdict unit is therefore the claim, not the block**; block length alone is a symptom, never the defect.

## Trimming rubric

Each claim inside a comment or javadoc region gets exactly one verdict:

1. **Transition narration** ("was carved off onto X", "is now structurally pinned by", "no longer", "the latter used to"): delete, or restate as a present-tense fact if the fact itself is load-bearing. History lives in `roadmap/changelog.md`.
2. **Future-work promises** ("a future UPSERT lift adds...", "at that point this relaxes to..."): delete. The roadmap owns futures; javadoc describing an unbuilt design is unpinned prose that rots the moment the plan changes (and per the conventions the item cannot even be cited). Exception: a forward-looking note explaining why a present structural element exists ahead of its consumer (a classified-but-undispatched sealed arm like `TableExpr.MethodCall`, an invariant that prices a future variant as a compile error) is restated as present-tense rationale pinned to its enforcer ("classified but not yet dispatched; ValidateMojo rejects it as a stubbed variant"), not deleted; the exhaustive switch and the validator carry that claim, so it is not unpinned prose.
3. **Code restatement** (prose quoting the guard conditions, enum arms, or call chain the reader can see below it): delete, or reduce to an `{@link}` where it names a non-local symbol.
4. **Load-bearing claims** (invariants, fail-closed contracts, non-obvious rationale, cross-module coupling a reader cannot recover from the code): keep, terse. If unpinned, pin it: `{@link}` for symbol claims (checked by the reference gate), a named test for behavioral claims, a published-docs pointer for design rationale. A claim that cannot be cleanly pinned or deleted is routed to a follow-on item, never rewritten into fresh confident prose (the R483 routing discipline).
5. **Orientation on-ramp** (the blessed intent-altitude case per "Documentation names only live tests/code" in `development-principles.adoc`: a class-level scope / entry-point / model-output / doc-pointer blurb): keep and tighten, never delete as restatement. This is the surface R35 is chartered to *add* on the same central files; verdict 5 is what keeps the two sweeps from editing one block in opposite directions.

Formatting damage rides along where touched (e.g. the flush-left javadoc lines inside `MutationField.MutationBulkDmlRecordField`).

## Mechanics

Reuse the R483 shape, which this repo has already validated: a batched reader fan-out over the census-ranked worklist, each batch's edits passed through an adversarial verify stage prompted to *restore* deletions, i.e. to argue that a deleted claim was load-bearing and unrecoverable from the code (R483's verify stage caught exactly one wrongful deletion out of 90 edits; this sweep deletes far more aggressively, so the stage matters more here). Start from the top of the census and work down; the tail (files under ~100 comment lines with no 25+ line block) is explicitly allowed to go untouched, since the cost of sweeping it exceeds the drift risk it carries.

**Exclusion list (binds the out-of-scope rule).** The open drift follow-ons own specific in-source claims that still sit in their un-fixed form, and a top-down sweeper cannot recognize them as owned; "must not rewrite" needs a mechanism. The fan-out worklist therefore excludes these files outright until their items land: `SchemaDirectiveRegistry` and the `BuildContext` `DIR_ROUTINE`/`DIR_AS_FACET` javadoc (R494, a possible correctness bug that must not be pinned as terse confident fact), `MappingsConstantNameDedup` (R496), and the RAG dev-warm hint site in `graphitron-mcp` (R498). If any of the three lands before this sweep runs, its file rejoins the worklist.

**Sequencing with R35.** Verdict 5 protects existing on-ramps, but the cleaner order is R35's class-level on-ramps landing first so this sweep trims around them; if R524 runs first, it must not manufacture on-ramps (that is R35's charter), only preserve what exists.

Acceptance:

- Full reactor green under `mvn install -Plocal-db`, with the `{@link}` reference gate and `RoadmapReferenceGuardTest` active (both guards constrain the rewrite direction).
- Edits are comment/javadoc-only, plus any test added to pin a kept behavioral claim.
- **Per-file ledger as the anti-vacuous-pass pin** (mirroring R483's 12-row ledger and the scanned-file floors elsewhere in the repo): every census-ranked file above the tail cutoff gets a recorded verdict in the Done summary, either edited-with-SHA or explicitly "no trimmable claim". A before/after census re-run is reported alongside as context only; no numeric reduction gate, since a quota would incentivize deleting load-bearing prose to hit a number. The rubric, not a quota, decides each claim.
- Follow-ons filed for every claim routed out rather than resolved.

The sweep is re-runnable; if the methodology proves cheap, a recurring cadence can be considered as a follow-on.

## Ledger

Cutoff rule with the tilde resolved, fixed before the worklist was built: a file is on the worklist iff it has >= 100 comment lines or a contiguous comment block of >= 25 lines. Census script counts both trees of the `GuardScope.IN_SCOPE_MODULES` modules; worklist is 241 files, 3 excluded, 238 workable. Every row below is verdict "edited" unless marked otherwise; counts are comment lines before -> after. Each bout's edits passed the adversarial verify stage (prompted to restore deletions) before landing.

Bout 1 (census rows 1-25, verified, reactor green with both guards):

1. `graphitron/.../FieldBuilder.java`: 2896 -> 2822. Two dangling `{@link}`s fixed.
2. `graphitron/.../generators/TypeFetcherGenerator.java`: 2528 -> 2469. Dead doc paths repointed; partition claims pinned to `GeneratorCoverageTest`. 3 claims routed.
3. `graphitron-sakila-example/.../GraphQLQueryTest.java`: 1640 -> 1596. Pre-fix narration restated present-tense; one stale claim contradicting its assertion deleted.
4. `graphitron/.../GraphitronSchemaBuilderTest.java`: 1092 -> 940. Corpus-migration tombstones compressed; dead doc citations repointed; one stale demote-claim fixed.
5. `graphitron/.../generators/MultiTablePolymorphicEmitter.java`: 781 -> 740.
6. `graphitron/.../TypeBuilder.java`: 748 -> 629. One contradictory qualifier fixed.
7. `graphitron/.../JooqCatalog.java`: 713 -> 631. Verify stage restored two `RoutineResolution` arm docs lost in the trim (the one wrongful deletion of the bout).
8. `graphitron/.../model/ChildField.java`: 624 -> 585. Stale third-arm claim fixed; `sourceShape` pinned to `SourceShapeProjectionTest`.
9. `graphitron/.../GraphitronSchemaBuilder.java`: 559 -> 457. Exemplar on-ramp preserved; contradictory carve-out claim removed.
10. `graphitron/.../generators/SplitRowsMethodEmitter.java`: 525 -> 460.
11. `graphitron/.../ServiceCatalog.java`: 518 -> 422. One wrong slot-position claim deleted.
12. `graphitron/.../TestServiceStub.java`: 513 -> 508. Fixture-shape docs kept per verdict 5.
13. `graphitron/.../GraphitronSchemaValidator.java`: 481 -> 439. Stubbed-variant claim repinned to `TypeFetcherGenerator#STUBBED_VARIANTS`; dangling principles-doc cite removed.
14. `graphitron/.../generators/TypeFetcherGeneratorTest.java`: 429 -> 367. Stale mirrors repointed to live tests.
15. `graphitron/.../RecordBindingResolver.java`: 411 -> 370. Two verifiably-stale claims deleted.
16. `graphitron/.../generators/ArgCallEmitter.java`: 393 -> 317. Stale `ParamSource`/registry claims deleted; decode-shape docs single-homed on `CompositeDecodeHelperRegistry`.
17. `graphitron/.../model/MutationField.java`: 379 -> 313. Worst-case `MutationBulkDmlRecordField` javadoc 60 -> 38 lines; flush-left damage fixed; wrong DELETE-sibling claim fixed.
18. `graphitron/.../InputBeanResolver.java`: 378 -> 292. Rejection bullet lists reduced to fail-closed pins.
19. `graphitron/.../classifieddsl/ClassifiedCorpus.java`: 371 -> 362. Spec-by-example commentary kept per verdict 4/5.
20. `graphitron/.../model/GraphitronType.java`: 369 -> 355. Stale null-`PojoResultType` claim deleted. 2 claims routed.
21. `graphitron-maven-plugin/.../AbstractRewriteMojo.java`: 362 -> 274. POM config-surface docs kept.
22. `graphitron/.../generators/util/ConnectionRuntimeClassGenerator.java`: 343 -> 324. The 118-line block judged claim-by-claim; it is contract, kept.
23. `graphitron/.../generators/FetcherEmitter.java`: 336 -> 280.
24. `graphitron/.../generators/GeneratorUtils.java`: 315 -> 245. Duplicated javadocs single-homed; one dangling overload link fixed.

Bout 2 (census rows 26-70, 45 files, verified, reactor green with both guards):

25. `graphitron/.../model/CallSiteExtraction.java`: 306 -> 283. Stale SkipMismatchedElement example deleted (contradicted the ThrowOnMismatch doc); bare slug citation removed.
26. `graphitron/.../compile/CompileDependencyGraphBuilder.java`: 283 -> 272. Collapse-target futures cut; edge sourcing pinned to `TypeSpecReferenceWalk`.
27. `graphitron/.../model/Rejection.java`: 278 -> 231. Stale sub-arm counts fixed; per-arm boilerplate single-homed on the `AuthorError` interface doc.
28. `graphitron/.../MutationInputResolver.java`: 274 -> 211. Stale DELETE-only table-arg claim repointed to `TABLE_ARG_SUPPORTED_VERBS`; two stale accessor claims fixed.
29. `graphitron/.../catalog/CatalogBuilder.java`: 273 -> 254. Follow-up-once-LSP-wired promises cut; exhaustiveness contract single-homed.
30. `graphitron/.../NodeIdLeafResolver.java`: 263 -> 255. Stale `validateChildConnectionParentPk` cite repointed to the live `validateChildMultiTableParentPk`.
31. `graphitron/.../generators/TypeClassGenerator.java`: 256 -> 223. 14-line duplicate taxonomy block cut; stale declaration-order claim fixed.
32. `graphitron-maven-plugin/.../DevMojo.java`: 254 -> 251. Deferred/slice promises cut; closed-loader contract kept.
33. `graphitron-lsp/.../diagnostics/Diagnostics.java`: 250 -> 237. Ships-in-same-commit promise cut; roadmap-slug-hint comment rewritten.
34. `graphitron/.../catalog/FieldClassification.java`: 247 -> 240.
35. `graphitron/.../ScalarTypeResolver.java`: 241 -> 226. Contradictory federation-set pointer fixed, instruction pinned to `FederationSpec#URL`. 1 claim routed.
36. `graphitron-lsp/.../parsing/LspVocabulary.java`: 223 -> 212. Leaf contract single-homed on `Leaf#valueNode()`.
37. `graphitron-mcp/.../GraphitronMcpServer.java`: 215 -> 196. Slice narration cut; stale constructor-arity claims fixed. No RAG dev-warm hint comments present (exclusion moot).
38. `graphitron/.../TestFixtures.java`: 213 -> 198. Dangling `On.ColumnPairs#fk()` link repointed to `On.Keying.ForeignKey#fk()`.
39. `graphitron-sakila-example/.../DmlBulkMutationsExecutionTest.java`: 209 -> 201.
40. `graphitron/.../generators/LookupValuesJoinEmitter.java`: 206 -> 144. Five stale claims fixed (dangling link, wrong param name, caller-declares-dsl, decode-binding scope, renamed method mentions).
41. `graphitron/.../generators/FetcherPipelineTest.java`: 194 -> 162. Three tombstone blocks cut; the one load-bearing fact relocated to a live test.
42. `graphitron-lsp/.../state/Workspace.java`: 194 -> 192.
43. `graphitron/.../ServiceDirectiveResolver.java`: 185 -> 122. Live roadmap-slug citation removed (x2); dangling annotation-contract claim deleted.
44. `graphitron/.../ConnectionPromoter.java`: 184 -> 179. Facet gating upgraded to `{@link FacetFieldValidation#definitionKeyedRejection}`.
45. `graphitron/.../ServiceCatalogTest.java`: 184 -> 180. Plan-section citations dropped.
46. `graphitron/.../generators/JooqRecordInstantiationEmitter.java`: 179 -> 166. Verify stage restored the non-null containsKey-guard rationale (the one wrongful deletion of the bout). 1 claim routed.
47. `graphitron/.../model/MethodRef.java`: 176 -> 169.
48. `graphitron/.../ArgumentRef.java`: 175 -> 170. Dangling plan-step citation removed.
49. `graphitron/.../EnumMappingResolver.java`: 168 -> 109. Retired-permit narration restated; parity-home claim pinned.
50. `graphitron/.../generators/schema/GraphitronSchemaClassGenerator.java`: 168 -> 166.
51. `graphitron/.../SingleRecordPayloadPipelineTest.java`: 166 -> 163. Phase labels stripped.
52. `graphitron/.../JooqCatalogMultiSchemaTest.java`: 164 -> 159.
53. `graphitron/.../model/OutputField.java`: 161 -> 156. Validator mirror upgraded to `{@link}`; wrapper-fold claim repinned to `WrapperAlgebraTest`.
54. `graphitron/.../generators/ServiceMethodCallEmitter.java`: 160 -> 155. Stale dual-interface claim deleted (verified stale); `outputPackage` promise restated as verified-unused.
55. `graphitron-lsp/.../DiagnosticsTest.java`: 160 -> 155. Stale eight-bindings count fixed; misattached javadoc moved to the method it describes.
56. `graphitron/.../model/QueryField.java`: 159 -> 156.
57. `graphitron/.../generators/InlineTableFieldEmitter.java`: 158 -> 156.
58. `graphitron/.../ClassAccessorResolver.java`: 156 -> 156. Equivalence claim pinned by name to its meta-test.
59. `graphitron/.../model/ParentCorrelation.java`: 155 -> 150. Formerly-arm narration cut across 8 sites.
60. `graphitron/.../catalog/CompletionData.java`: 153 -> 153. Broken wrapped line fixed.
61. `graphitron/.../MutationDmlNodeIdClassificationTest.java`: 153 -> 115.
62. `graphitron-sakila-example/.../FederationEntitiesDispatchTest.java`: 151 -> 146. Reproducer provenance dropped.
63. `graphitron/.../TenantBindingIndex.java`: 150 -> 150. Wrong self-link repointed to `Fold#sweepUnreachedFanOutMarkers`.
64. `graphitron/.../generators/util/ConnectionHelperClassGenerator.java`: 150 -> 128. Blame contract single-homed at the `decodeCursor` emission.
65. `graphitron/.../GraphQLRewriteGenerator.java`: 149 -> 149. Jargon replaced with `{@link MethodCommandRegistry}` + `MethodClosureOracleTest` pin.
66. `graphitron/.../generators/InputBeanInstantiationEmitter.java`: 145 -> 146. Dangling `{@link Configuration}` repointed to `org.jooq.Configuration`.
67. `graphitron/.../ErrorChannelClassificationTest.java`: 143 -> 125. Three deleted-test tombstones cut.
68. `graphitron/.../generators/JooqRecordServiceParamPipelineTest.java`: 142 -> 140.
69. `graphitron/.../model/InputField.java`: 140 -> 139. Stale folds-two-cases claim corrected to the three cases the doc lists.

Claims routed out of the sweep (follow-ons to file at Done): `GraphitronType.ResultType#fqClassName` null caveat vs non-null construction paths; `GraphitronType` InputType garbled "(or `@table`)" parenthetical; `TypeFetcherGenerator` L2085 comment contradicting `DSL.noCondition()` emission; `TypeFetcherGenerator` stale "Mapped is not produced yet" claims (x2) whose adjacent throw-message string literals repeat the stale claim; `RecordBindingResolver#fromAnyProducer` is dead API (zero callers). From bout 2: `JooqRecordInstantiationEmitter#openDescent` javadoc's graphql-java nested present-null coercion claim (load-bearing external-library behavior with no named execution test to pin to); `ScalarTypeResolver#resolveFromDirectiveValue` javadoc's rationale citing a per-arm LSP `ClassNotFound` fix-it that does not exist in `graphitron-lsp` main sources. Also for the generated-output item: "slice" mentions inside `ConnectionRuntimeClassGenerator` `.addJavadoc` string literals render into generated output.

## Out of scope

- Adding missing orientation javadoc and `package-info.java` files: [`source-orientation-javadocs.md`](source-orientation-javadocs.md). Rubric verdict 5 and the sequencing note above coordinate the two items; this sweep preserves and tightens on-ramps but never authors new ones.
- Generated-output javadoc and hygiene: [`generated-output-hygiene-sweep.md`](generated-output-hygiene-sweep.md).
- Fixing individually tracked drifted claims (R494, R496, R498); bound by the exclusion list under Mechanics, not by intent alone.
