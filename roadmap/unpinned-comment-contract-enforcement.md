---
id: R527
title: "Pin or enforce the load-bearing comment contracts the javadoc sweep could not anchor"
status: In Progress
bucket: testing
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-07-24
last-updated: 2026-07-25
---

# Pin or enforce the load-bearing comment contracts the javadoc sweep could not anchor

The comment-trimming sweep (the R524 changelog entry records it) found load-bearing claims that could be neither deleted (a reader genuinely needs them) nor pinned to a live symbol, test, or docs page; per the sweep's rubric they were left verbatim and routed here. Investigation at Spec time sharpened each into a concrete resolution; per "Every invariant has an enforcer" (`docs/architecture/explanation/development-principles.adoc`), "enforce" here means an enforcer or a correction, never a fourth restatement.

## 1. `JooqRecordInstantiationEmitter#openDescent`: nested present-null coercion

The javadoc asserts graphql-java drops an explicit-null field from a *nested* input-object value during coercion, so inside a descended `Map` a present-null leaf is indistinguishable from an omitted one and the top-level present-null/omitted/value three-way narrows to a nested two-way. External-library behavior, currently unpinned.

Shipped, with the truth-probe firing in full. Implementation found the four consequences already execution-pinned in `GraphQLQueryTest` (`describeEndorsement_explicitNull_writesNull`, `customerUpsert_explicitNullNestedLeaf_collapsesToOmitted`, `customerUpsert_nestedLeafSet_landsOnColumn_omittedSiblingUntouched`, `customerUpsert_omittedNullableIdentityGroup_skipsNonNullIdentity_noThrow`), so no duplicate tests were authored — but the causal story all of them told was false. A coercion probe showed graphql-java 25 retains a nested explicit-null at every depth, variables and inline literals alike; the observed collapse is jOOQ-side: the identity group's trailing `fromArray` key decode resets the touched flag of every null-valued column record-wide (`Record.from()` null-skip semantics), erasing the earlier `NULL` write. Added `customerUpsert_explicitNullNestedLeaf_noIdentityDecode_writesNull` pinning the counterpart (no decode, `NULL` write survives), rewrote the `openDescent` / `emitColumnBinding` / `emitKeyDecode` javadoc and the two false test/service comments to the verified mechanism, and routed the behavior question (should key decodes stop erasing explicit-null writes?) to a follow-on Backlog item.

## 2. `ScalarTypeResolver#resolveFromDirectiveValue`: phantom LSP fix-it rationale

The javadoc justifies mapping a dotless directive value to `ScalarResolution.Rejected.ClassNotFound` by citing "the per-arm LSP fix-it for `ClassNotFound`", which does not exist. The live mechanism is `ScalarTypeCompletions` in `graphitron-lsp` (classpath-scan-backed completion, pinned by `ScalarTypeCompletionsTest`), but a `{@link}` to it from `graphitron` cannot resolve: the module dependency runs LSP → graphitron, and the reference gate would force a downgrade to unchecked `{@code}` prose, the same unpinned shape being replaced.

Shipped as planned: `resolveFromDirectiveValue`'s javadoc now leans on `ParsedDirectiveValue.Malformed` / `parseDirectiveValue` ("the one place both this resolver and the LSP diagnostic read it from"), and `ScalarTypeCompletions`' class javadoc carries the consumer-to-producer `{@link}` to `ScalarTypeResolver.ParsedDirectiveValue`. No fix-it built.

## 3. `JoinPathEmitter#emitCorrelationWhere`: the empty-slot state is stated four incompatible ways

The routed claim was one false paragraph: the javadoc says the empty-slot fallback emits a "runtime-throwing `DSL.noCondition()` stub" so a catalog mismatch "surfaces at execution". jOOQ's `DSL.noCondition()` is a neutral no-op condition; the emitted query would silently run uncorrelated, the exact silent breakage the paragraph claims to prevent. Investigation (extended by the R526 spec pass, which found a fifth site and routed it here) found the empty-slot fact restated five incompatible ways with no single enforcer:

- `On.ColumnPairs`' record javadoc declares emptiness legal ("empty when the jOOQ catalog is unavailable (unit tests)"), contradicted by its sibling `On.Keying.ForeignKey` doc in the same file (catalog misses route through `FkJoinResolution`, never a provenance-free pair list).
- `FieldBuilder`'s facet path silently degrades empty slots into a classifier rejection (`return Optional.empty()`).
- `JoinPathEmitter#emitKeyedJoin` (name-matched arm) and `TypeFetcherGenerator`'s routine-write hop 0 throw `IllegalStateException` at generation time, their messages asserting the opposite of the model doc: empty slots indicate a classifier bug, not a missing catalog.
- `emitCorrelationWhere` emits the silent `DSL.noCondition()`.
- `TypeFetcherGenerator#buildTableMethodParentCorrelation` repeats `emitCorrelationWhere`'s shape exactly: an empty-slot fallback emitting `DSL.noCondition()` under the same false "runtime-throwing ... surfaces at execution" comment.

Shipped with the preferred shape. Producer audit confirmed both main-source synthesis paths (`synthesizeFkJoin`, `synthesizeNameMatchedJoin`) already guarantee non-empty slots and a missing catalog routes through `FkJoinResolution` before any pair list exists, so the record javadoc's "empty when the catalog is unavailable (unit tests)" was pure fiction: only test fixtures minted empty slots (eleven `TestFixtures.fkJoin` callers passing `List.of()`, all given real column pairs). The compact constructor now rejects empty slots (`columnPairs_emptySlots_rejectedAtConstruction` pins the throw); the two generation-time guards (`emitKeyedJoin` name-matched arm, routine-write hop 0), both `noCondition()` fallbacks with their false "runtime-throwing" comments, and `singleHopFkColumnPairs`' reject-on-empty were deleted in the same commit; the record javadoc states the non-empty fact. Bonus enforcement: the invariant converts `resolveFkColumnRefs`' silent column-drop on a partial catalog into a loud failure.

## 4. Dead §-anchor families in comment regions

The routed bullet named `BuildContext`'s two channel-rule comments; the defect is a family. Comment regions across main sources cite section-numbers of dead plan artifacts that resolve to nothing:

- The error-channel rule spec (`error-handling-parity.md`, deleted): §1/§2b/§2c/§3/§5 cites across `FieldBuilder` (the bulk), `BuildContext`, `ErrorMappingsClassGenerator`, `GraphitronSchemaValidator`, `GraphitronType`, `WithErrorChannel`, plus prose citations of the dead file name itself.
- The "Invariants §1/§2/§3" family across `TypeFetcherGenerator`, `ArgCallEmitter`, `GraphitronSchemaValidator`.
- Any sibling dead plan-file §-anchor an implementation-time grep for `§` surfaces. External-spec cites are live references and stay: JLS cites like §14.4.2 / §12.4.1, and the LSP-protocol §2.1.1 cite in `graphitron-lsp`'s `Positions`.

Shipped per the mechanical fix rule: every dead §-anchor in main-source comment regions repointed to its live enforcer (`checkDeclaredCheckedExceptions`, `ServiceCatalog.reflectTableMethod`/`reflectServiceMethod`, `ServiceDirectiveResolver.validateRootInvariants`/`validateRootListTableBoundReturnPair`, `TableMethodDirectiveResolver`) or deleted where the surrounding prose already names the fact; no numbered inventory authored anywhere. "Rule 7"/"Rule 8" survive in prose deliberately: `ErrorChannelWalkerError.ChannelRuleViolation.ruleNumber` is a live model component that renders the number into diagnostics, so the names are anchored, only the dead § cites went. The `rule7`/`rule8` locals were renamed to `handlerCardinality`/`duplicateMatchCriteria` at all three sites (`BuildContext`, `FieldBuilder`, `ErrorChannelWalker`). One string-literal site outside the scope split (`ErrorMappingsClassGenerator`'s generator-crash message, and its `ArgCallEmitter` sibling) carried the same dead ordinals and was corrected here since neither renders into generated output.

Scope boundaries, stated so no site gets dropped: this item owns *comment regions in main sources*; §-anchors inside string literals that render into generated output or diagnostics belong to the generated-output hygiene item (R521), whose routed bullet slightly misattributes the split (`FieldBuilder`'s and `WithErrorChannel`'s dead-file cites are comment regions and belong here; only `ErrorRouterClassGenerator`'s is emitted text). `MappingsConstantNameDedup` is excluded: R496 owns that file's javadoc; its §3 cite is handled by R496 or after it lands. Test-source ordinals in display names and assertion messages render to no consumer surface and stay out.

## 5. `Source.OnlyChild`: the machine-unenforced honesty clause

The clause states: any emit strategy `OnlyChild` ever licenses must stay row-correct under query-alias fan-out (aliases can materialize `k` parents even on an arrival-`One` chain); today the emitters keep leaf-identity dispatch, so populating the arm changes no generated code; and "nothing machine-enforces this clause".

Shipped: `ArrivalUniformEmitPinTest` scans the generators package's code regions (comment/literal habitats excluded via the guard family's lexer) and asserts zero `OnlyChild` tokens, with a vacuity floor and an anti-over-deletion carve-out pinning that `ChildField#source` still mints both arms. The `Source.OnlyChild` javadoc drops "nothing machine-enforces this clause", names the pin, and keeps the row-correctness constraint as the strategy-agnostic forward burden. The hand-off is recorded in R471's body (invert or retire the pin together with the aliasing enforcer).

## Acceptance

- Each of the five claims resolved as specified: pinned to a named test, repointed to a gate-checked `{@link}`, or corrected in code with the false prose deleted; no claim rewritten as fresh unpinned confident prose.
- Claim 1's execution test and claim 5's meta-test land with the javadoc edits that name them; claim 3 lands the constructor invariant with all five restatement sites reconciled in one commit.
- Claim 4 leaves zero dead §-anchors in main-source comment regions (`grep -rn '§' graphitron*/src/main/java` shows only external-spec cites (JLS, LSP protocol) and R496-owned/R521-owned sites), and no new numbered inventory anywhere.
- Full reactor green under `mvn install -Plocal-db` with the `{@link}` reference gate and `RoadmapReferenceGuardTest` active.
- Follow-ons filed for anything routed out rather than resolved.
