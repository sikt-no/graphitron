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

Bout 3 (census rows 71-130, 58 workable files, verified, reactor green with both guards):

70. `graphitron/.../NodeIdPipelineTest.java`: 139 -> 117. Two dangling `CallSiteExtraction.NodeIdDecodeKeys.*` links repointed to the real nesting; roadmap-file citation removed.
71. `graphitron/.../TestConditionStub.java`: 138 -> 138. Rewording only; phase label dropped.
72. `graphitron/.../generators/TenantDslEmitter.java`: 134 -> 127.
73. `graphitron/.../catalog/TypeClassification.java`: 133 -> 128.
74. `graphitron/.../SingleRecordTableFieldServiceProducerPipelineTest.java`: 132 -> 112. Interim-rejection and pre-fix narration cut.
75. `graphitron-javapoet/.../CodeBlock.java`: 132 -> 132. Vendored fork; corrupted javadoc gutter line repaired, upstream API doc kept.
76. `graphitron/.../GraphitronSchema.java`: 128 -> 114. Rot-prone diagnostics-kind inventory cut; invariant kept.
77. `graphitron/.../catalog/LspSchemaSnapshot.java`: 125 -> 118.
78. `graphitron/.../catalog/SourceWalker.java`: 123 -> 119.
79. `graphitron/.../generators/QueryConditionsGenerator.java`: 119 -> 103. EmissionContext fix prescription cut; the fails-to-compile known-gap fact kept.
80. `graphitron/.../generators/JoinPathEmitter.java`: 118 -> 111. False `terminalTable` condition-join claim corrected (parameter is unused). 1 claim routed.
81. `graphitron/.../generators/schema/GraphitronSchemaClassGeneratorTest.java`: 113 -> 110. Track/stage plan labels cut.
82. `graphitron/.../OrderByResolver.java`: 112 -> 76.
83. `graphitron/.../schema/federation/EntityResolutionBuilder.java`: 112 -> 92. Stale error-channel claim (UnclassifiedType demotion) corrected to the diagnosticSink registration the code performs.
84. `graphitron/.../model/ParticipantRef.java`: 111 -> 95. Garbled `TableBacked` parenthetical repaired.
85. `graphitron/.../ServiceRecordCompositeCarrierPipelineTest.java`: 111 -> 59. Heavy pre-fix/commit-hash narration cut.
86. `graphitron-lsp/.../definition/Definitions.java`: 111 -> 106.
87. `graphitron-javapoet/.../TypeSpec.java`: 109 -> 109. Vendored fork, untouched.
88. `graphitron/.../generators/InlineLookupTableFieldEmitter.java`: 106 -> 81. Two stale claims fixed (VALUES+USING shape; USING case-sensitivity rationale on an ON join).
89. `graphitron/.../generators/DataLoaderFetcherEmitter.java`: 106 -> 87. Replaces-three-builders narration cut (two named methods no longer exist).
90. `graphitron/.../catalog/TypeBackingShape.java`: 105 -> 87.
91. `graphitron/.../MultiSchemaPipelineTest.java`: 104 -> 72.
92. `graphitron/.../SourceRowDirectiveResolver.java`: 103 -> 83. Stale self-contradictory joinPath claim corrected (empty on the leaf-PK path).
93. `graphitron/.../model/Operation.java`: 103 -> 90. Retired-intent-enum and re-platforming narration cut.
94. `graphitron/.../FieldSourceSigil.java`: 102 -> 64.
95. `graphitron/.../NodeIdLeafResolverTest.java`: 102 -> 92. Two rotting line-number citations replaced with mechanism names.
96. `graphitron/.../MutationTableArgClassificationTest.java`: 102 -> 101. History rewritten present-tense; plan-position labels cut.
97. `graphitron/.../model/JoinStep.java`: 101 -> 72. Stale day-one-always-a-Catalog claim cut (RoutineCall targets exist).
98. `graphitron/.../ArgBindingMap.java`: 101 -> 78. Inaccurate single-layer-of-non-null claim corrected (code strips wrappers in a loop).
99. `graphitron/.../generators/util/ValuesJoinRowBuilder.java`: 100 -> 71.
100. `graphitron/.../RewriteContext.java`: 99 -> 73.
101. `graphitron/.../model/ErrorChannel.java`: 99 -> 92. Stale two-arms claim removed (permits three); phase labels repointed to the live `ErrorsSlot` arms.
102. `graphitron/.../walker/UpdateRowsWalker.java`: 99 -> 83. Backlog-slug citation removed.
103. `graphitron/.../catalog/ClasspathScanner.java`: 97 -> 78.
104. `graphitron/.../generators/FetchersHelperNames.java`: 97 -> 93.
105. `graphitron/.../generators/JooqRecordHelperNames.java`: 96 -> 92.
106. `graphitron/.../model/BodyParam.java`: 94 -> 57.
107. `graphitron-lsp/.../parsing/DeclTarget.java`: 94 -> 73.
108. `graphitron-sakila-example/.../GeneratedSourcesLintTest.java`: 93 -> 72. Stale currently-enforces-one-rule claim fixed (five tests exist).
109. `graphitron-javapoet/.../NameAllocator.java`: 91 -> 91. Vendored fork, untouched.
110. `graphitron-mcp/.../rag/CatalogSearchIndex.java`: 91 -> 77.
111. `graphitron/.../model/OrderBySpec.java`: 85 -> 62. Unverified emit-a-warning promises cut; `uniformAsc` contract pinned to its consumer.
112. `graphitron/.../ConditionResolver.java`: 84 -> 46.
113. `graphitron-javapoet/.../TypeName.java`: 84 -> 84. Vendored fork, untouched.
114. `graphitron/.../model/TableRef.java`: 83 -> 80.
115. `graphitron/.../generators/util/NodeIdEncoderClassGenerator.java`: 83 -> 64. Inaccurate explicit-cast claim dropped; no-`var` rule named to its live enforcer.
116. `graphitron/.../RecordParentMultiTablePolymorphicPipelineTest.java`: 83 -> 59.
117. `graphitron/.../classifieddsl/QueryViewRenderer.java`: 83 -> 67. Five spec-section citations removed.
118. `graphitron-sakila-example/.../GraphQLOverHttpConformanceTest.java`: 80 -> 48. Coverage-pointer table cut (restated `@DisplayName`s).
119. `graphitron/.../catalog/CatalogFacts.java`: 79 -> 76.
120. `graphitron/.../model/KeyLift.java`: 79 -> 70. Retired seven-arm `SourceKey.Reader` history cut.
121. `graphitron/.../selection/GraphQLSelectionParser.java`: 78 -> 58.
122. `graphitron/.../generators/CompositeDecodeHelperRegistry.java`: 77 -> 69. Stale ownership claim corrected; no-`var` claim pinned to its enforcer.
123. `graphitron/.../MappingsConstantNameDedup.java`: excluded (R494 exclusion list).
124. `graphitron-mcp/.../rag/RagLogQuieting.java`: excluded (R494 exclusion list).
125. `graphitron/.../ServiceProjectionPipelineTest.java`: 73 -> 71. Stale `collectRequiredProjectionColumns` cite corrected to `collectRequiredProjection`.
126. `graphitron/.../SchemaReachability.java`: 72 -> 55. False no-object-to-interface-descent claim deleted (contradicted `childrenOf`).
127. `graphitron/.../model/ParamSource.java`: 72 -> 56.
128. `graphitron-sakila-example/.../FixtureWarningsGateTest.java`: 72 -> 51. Dangling `{@link}` to a nonexistent test method repointed; stale carve-out claim corrected against the file's own assertions.
129. `graphitron/.../ArrivalIndex.java`: 69 -> 68. Monoid-role prose replaced with a `{@link Arrival#tensor(Arrival)}` pin.

Bout 4 (census rows 131-190 plus the rejoined row 4, 61 workable files, verified, reactor green with both guards):

Census row 4, `graphitron/.../BuildContext.java` (rejoined per Mechanics once its exclusion owner landed): 1284 -> 1250. Severed registry-sync claim confirmed absent; stale `TargetKeys` claim corrected to the live `NodeIndex` read path; dead slug citation removed; two `§`-numbered channel-rule comments routed.

130. `graphitron/.../walker/DeleteRowsWalker.java`: 68 -> 48. Stage-numbering vocabulary dropped.
131. `graphitron/.../methodgraph/EmittedMethodClosure.java`: 68 -> 64. Level/thread plan vocabulary cut; oracle pinned to `MethodClosureOracleTest`.
132. `graphitron/.../model/PayloadConstructionShape.java`: 67 -> 62. Reference to nonexistent `ReflectionHelpers` removed.
133. `graphitron/.../model/LoaderRegistration.java`: 67 -> 55. Components list deduplicated onto the enum-constant docs.
134. `graphitron/.../generators/schema/ObjectTypeGenerator.java`: 67 -> 61.
135. `graphitron/.../compile/IncrementalCompiler.java`: 67 -> 66. One future-work promise cut; rest load-bearing.
136. `graphitron/.../WrapperAlgebraTest.java`: 67 -> 57. Laws deduplicated onto the assertion descriptions.
137. `graphitron/.../TypeRegistry.java`: 66 -> 60. Verb-collapse history restated present-tense.
138. `graphitron/.../catalog/InferredDirectiveArgs.java`: 66 -> 55.
139. `graphitron-maven-plugin/.../CodegenLoaderTest.java`: 66 -> 63. False generated-via-javac claim corrected to the hand-rolled `writeMarkerClass` bytes.
140. `graphitron/.../walker/ServiceMethodCallWalker.java`: 65 -> 54. Spec-vs-implementation narrative cut.
141. `graphitron/.../generators/util/HandleMethodBody.java`: 64 -> 64. Three byte-identical-history anchors cut; rewraps kept the count level.
142. `graphitron-sakila-example/.../RoutineFieldExecutionTest.java`: 64 -> 53. Design-alternative label dropped.
143. `graphitron/.../generators/schema/GraphitronFacadeGenerator.java`: 63 -> 55.
144. `graphitron/.../generators/util/QueryNodeFetcherClassGenerator.java`: 62 -> 52. Stale dispatch claim corrected to `EntityFetcherDispatch.resolveByReps`; changelog citation removed.
145. `graphitron/.../model/ParentRowDemand.java`: 59 -> 41. Gap-label plan vocabulary dropped.
146. `graphitron/.../model/DmlReturnExpression.java`: 55 -> 48.
147. `graphitron/.../model/SourceKey.java`: 54 -> 41. Stale `columnClass()` cite corrected.
148. `graphitron/.../model/ErrorChannelWalkerError.java`: 60 -> 53. Stale two-arms header corrected (three arms carry the classification-raised doc).
149. `graphitron/.../model/Source.java`: 60 -> 45. `OnlyChild` honesty clause kept with an explicit nothing-machine-enforces marker; 1 claim routed.
150. `graphitron/.../generators/util/GraphitronTransactionProviderGenerator.java`: 60 -> 54. Fidelity claim pinned to its unit test by name.
151. `graphitron/.../methodgraph/MethodClosureOracleTest.java`: 60 -> 50. Seam-worklist row/thread labels cut.
152. `graphitron-sakila-example/.../SealedHierarchyDocCoverageTest.java`: 60 -> 59. Stale path claim and retired vocabulary corrected.
153. `graphitron/.../generators/ParentProjectionContainmentCheck.java`: 59 -> 53.
154. `graphitron/.../generators/RowsMethodSkeleton.java`: 59 -> 35. Phase framing cut; seams pinned to the live emitters.
155. `graphitron/.../generators/schema/GraphitronDevExecutorGenerator.java`: 58 -> 57.
156. `graphitron/.../generators/util/GraphitronConnectionInstrumentationGenerator.java`: 58 -> 58. Slice labels and successor promises restated present-tense; false `CLAIMS_KEY_VALUE` cross-reference dropped.
157. `graphitron/.../CheckedExceptionMatcher.java`: 57 -> 33. False `throws Error` claim deleted; exemptions pinned to `CheckedExceptionClassificationTest`.
158. `graphitron-sakila-example/.../DirectiveDocCoverageTest.java`: 57 -> 48.
159. `graphitron/.../TableMethodDirectiveResolver.java`: 56 -> 34. Helper list corrected to the two helpers the code calls.
160. `graphitron/.../model/RowsMethodBody.java`: 56 -> 40. Stale wiring promise cut (consumers already live); rotted permit list dropped.
161. `graphitron/.../RetiredVocabularyGuardTest.java`: 56 -> 48. Remediation prose deduplicated onto the assertion messages.
162. `graphitron/.../model/ScalarResolution.java`: 55 -> 48. Aspirational LSP consumer dropped from the consumer list.
163. `graphitron/.../LookupKeyDirectiveResolver.java`: 53 -> 26.
164. `graphitron/.../PaginationResolver.java`: 53 -> 28.
165. `graphitron/.../model/WireCoercionError.java`: 53 -> 44. Spec/audit site labels cut; assignability claim pinned to `{@link CallSiteExtraction.Direct}`.
166. `graphitron/.../generators/schema/ErrorRouterClassGenerator.java`: 51 -> 22. Dead spec-file citation removed; contracts deduplicated onto the emitted-javadoc literals.
167. `graphitron/.../SettKvotesporsmalShapeRegressionTest.java`: 52 -> 21. Stale pin bullet for a deleted test removed; self-contradictory classification sentence and wrong accessor claim fixed.
168. `graphitron/.../generators/PolymorphicProjectionFilterPinTest.java`: 53 -> 33. Rot-prone occurrence count softened and pinned to the counting helper.
169. `graphitron/.../ContextArgumentClassifier.java`: 52 -> 33.
170. `graphitron/.../generators/util/EntityFetcherDispatchClassGenerator.java`: 52 -> 48. Dunder rationale pinned to `DunderFreeEmissionPipelineTest`.
171. `graphitron/.../compile/TypeSpecReferenceWalk.java`: 52 -> 47. Nonexistent `walkEdges` corrected to `edges(u)`; subset contract pinned to the harness oracle test.
172. `graphitron-lsp/.../code_action/SdlAction.java`: 52 -> 43.
173. `graphitron-maven-plugin/.../SessionStateBinding.java`: 52 -> 52. No trimmable claim (author-facing config contract).
174. `graphitron/.../LookupMappingResolver.java`: 51 -> 35. Arm enumeration reduced to the non-local downstream facts.
175. `graphitron/.../model/ReturnTypeRef.java`: 51 -> 34. Stale not-yet-implemented polymorphic-stub claim corrected, pinned to the live `MultiTablePolymorphicEmitter`.
176. `graphitron/.../DunderFreeEmissionPipelineTest.java`: 51 -> 35.
177. `graphitron/.../QualifiedParticipantCrossTableReferencePipelineTest.java`: 51 -> 46. Pre-fix narration restated present-tense.
178. `graphitron/.../ExternalFieldDirectiveResolver.java`: 50 -> 29.
179. `graphitron/.../model/ColumnRef.java`: 50 -> 47.
180. `graphitron/.../RoadmapReferenceScanner.java`: 40 -> 40. No trimmable claim (the guard's own deliberately oblique phrasing; any rewrite risks the pattern it scans for).
181. `graphitron/.../generators/schema/OutcomeClassGenerator.java`: 49 -> 31. Contracts deduplicated onto the emitted-javadoc literals.
182. `graphitron/.../methodgraph/MethodCommandRegistry.java`: 49 -> 41. Closure oracle named; migration promises restated present-tense.
183. `graphitron/.../classifieddsl/ClassifiedDslTest.java`: 49 -> 44. Spec-section citation cut; gap prose deduplicated onto the map literals.
184. `graphitron/.../model/DomainReturnType.java`: 48 -> 34. Dead validator-method name removed; enforcement restated against the live builder path.
185. `graphitron-mcp/.../rag/LuceneEmbeddingStore.java`: 48 -> 43. Warm-gating claim pinned to `{@link WarmState}`.
186. `graphitron/.../WireCoercionResolver.java`: 47 -> 46. Plan labels cut; failure mode pinned to `{@link CallSiteExtraction.Direct}`.
187. `graphitron/.../schema/federation/FederationKeyFieldsParser.java`: 47 -> 26. Accept/reject lists deduplicated onto the lexer; caller contract pinned to `{@link EntityResolutionBuilder}`.
188. `graphitron/.../generators/schema/OneOfDirectiveSdl.java`: 47 -> 39.
189. `graphitron-lsp/.../definition/IntraSchemaDefinitions.java`: 47 -> 41.

Claims routed out of the sweep (follow-ons to file at Done): `GraphitronType.ResultType#fqClassName` null caveat vs non-null construction paths; `GraphitronType` InputType garbled "(or `@table`)" parenthetical; `TypeFetcherGenerator` L2085 comment contradicting `DSL.noCondition()` emission; `TypeFetcherGenerator` stale "Mapped is not produced yet" claims (x2) whose adjacent throw-message string literals repeat the stale claim; `RecordBindingResolver#fromAnyProducer` is dead API (zero callers). From bout 2: `JooqRecordInstantiationEmitter#openDescent` javadoc's graphql-java nested present-null coercion claim (load-bearing external-library behavior with no named execution test to pin to); `ScalarTypeResolver#resolveFromDirectiveValue` javadoc's rationale citing a per-arm LSP `ClassNotFound` fix-it that does not exist in `graphitron-lsp` main sources. From bout 3: `JoinPathEmitter#emitCorrelationWhere` javadoc's claim that the empty-slot fallback's emitted `DSL.noCondition()` stub is runtime-throwing (behavioral claim about generated output with no named pin); `MultiTablePolymorphicEmitter` (two comments) and `RowsMethodCall` still cite the removed `buildSplitQueryDataFetcher`/`buildRecordBasedDataFetcher` methods (the former survived the bout-1 pass; the latter is below the census cutoff). Also for the generated-output item: "slice" mentions inside `ConnectionRuntimeClassGenerator` `.addJavadoc` string literals render into generated output. From bout 4: `BuildContext`'s two `§`-numbered channel-rule comments (rule-family names pinned to fixtures, but the numbered rule spec they cite resolves to no live artifact); `Source.OnlyChild`'s row-correctness contract (load-bearing, explicitly marked as machine-unenforced; needs an enforcer or a pin). String-literal findings for the generated-output item (untouchable by this sweep's comment-only constraint): emitted javadoc in `ErrorRouterClassGenerator`, `FieldBuilder`, and `WithErrorChannel` cites the dead `error-handling-parity.md` spec file and carries future-work phrasing; `GraphitronConnectionInstrumentationGenerator`'s emitted incremental-delivery rejection message says "it is a named follow-on"; `BuildContext` emits "file a roadmap item if this shape needs admission" / "a future Backlog item may admit multi-data carriers" diagnostics. Test-source note: `ClassifiedDslTest`'s `OPERATION_KNOWN_GAPS` map values cite roadmap ids in string literals (out of the guard's string-scan scope by design).

## Out of scope

- Adding missing orientation javadoc and `package-info.java` files: [`source-orientation-javadocs.md`](source-orientation-javadocs.md). Rubric verdict 5 and the sequencing note above coordinate the two items; this sweep preserves and tightens on-ramps but never authors new ones.
- Generated-output javadoc and hygiene: [`generated-output-hygiene-sweep.md`](generated-output-hygiene-sweep.md).
- Fixing individually tracked drifted claims (R496, R498); bound by the exclusion list under Mechanics, not by intent alone. R494 landed mid-sweep, so its files rejoined the worklist (BuildContext swept in bout 4; SchemaDirectiveRegistry sits below the census cutoff).
