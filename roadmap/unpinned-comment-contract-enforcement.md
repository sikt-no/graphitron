---
id: R527
title: "Pin or enforce the load-bearing comment contracts the javadoc sweep could not anchor"
status: Ready
bucket: testing
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-07-24
last-updated: 2026-07-24
---

# Pin or enforce the load-bearing comment contracts the javadoc sweep could not anchor

The comment-trimming sweep (the R524 changelog entry records it) found load-bearing claims that could be neither deleted (a reader genuinely needs them) nor pinned to a live symbol, test, or docs page; per the sweep's rubric they were left verbatim and routed here. Investigation at Spec time sharpened each into a concrete resolution; per "Every invariant has an enforcer" (`docs/architecture/explanation/development-principles.adoc`), "enforce" here means an enforcer or a correction, never a fourth restatement.

## 1. `JooqRecordInstantiationEmitter#openDescent`: nested present-null coercion

The javadoc asserts graphql-java drops an explicit-null field from a *nested* input-object value during coercion, so inside a descended `Map` a present-null leaf is indistinguishable from an omitted one and the top-level present-null/omitted/value three-way narrows to a nested two-way. External-library behavior, currently unpinned.

Plan: add an execution-tier test in `graphitron-sakila-example` driving a real graphql-java execution of a record-instantiating mutation, asserting the four column-write consequences: (a) top-level explicit-null leaf writes `NULL`; (b) nested explicit-null leaf leaves the column untouched; (c) nested omitted leaf leaves the column untouched; (d) an absent nullable group containing a non-null identity field skips rather than throws. Execution tier is right because every case is a "which rows/columns got written" observation. Pin the *consequence*, not the library internals: the test asserts generated-code behavior; the javadoc paragraph is rewritten to state the two-way narrowing as the pinned fact (naming the test) with the cause at intent altitude ("the coercion behaviour this rests on is graphql-java's, not a choice in the emitted code").

Truth-probe acceptance: if the test disproves the claim (graphql-java 25 retains nested explicit nulls), the deliverable flips to correcting the javadoc and re-examining whether the emitted `containsKey` guard should restore the nested three-way; the test then pins whichever behavior is real.

## 2. `ScalarTypeResolver#resolveFromDirectiveValue`: phantom LSP fix-it rationale

The javadoc justifies mapping a dotless directive value to `ScalarResolution.Rejected.ClassNotFound` by citing "the per-arm LSP fix-it for `ClassNotFound`", which does not exist. The live mechanism is `ScalarTypeCompletions` in `graphitron-lsp` (classpath-scan-backed completion, pinned by `ScalarTypeCompletionsTest`), but a `{@link}` to it from `graphitron` cannot resolve: the module dependency runs LSP → graphitron, and the reference gate would force a downgrade to unchecked `{@code}` prose, the same unpinned shape being replaced.

Plan: restate the rationale against the in-module symbol that already carries it: `parseDirectiveValue`'s `ParsedDirectiveValue.Malformed` arm, whose javadoc already records that the LSP diagnostic and the resolver switch on the same sealed result ("the shape rule lives in one place"). Record the classpath-suggestion linkage on the consumer side, per the consumer-to-producer `{@link}` direction in "Acceptances": a `{@link}` from `ScalarTypeCompletions`' javadoc to `ScalarTypeResolver.ParsedDirectiveValue`. Do not build a per-arm fix-it; prose invented it, and building it to save a sentence would be backwards. If a fix-it is ever wanted it is its own Backlog item.

## 3. `JoinPathEmitter#emitCorrelationWhere`: the empty-slot state is stated four incompatible ways

The routed claim was one false paragraph: the javadoc says the empty-slot fallback emits a "runtime-throwing `DSL.noCondition()` stub" so a catalog mismatch "surfaces at execution". jOOQ's `DSL.noCondition()` is a neutral no-op condition; the emitted query would silently run uncorrelated, the exact silent breakage the paragraph claims to prevent. Investigation (extended by the R526 spec pass, which found a fifth site and routed it here) found the empty-slot fact restated five incompatible ways with no single enforcer:

- `On.ColumnPairs`' record javadoc declares emptiness legal ("empty when the jOOQ catalog is unavailable (unit tests)"), contradicted by its sibling `On.Keying.ForeignKey` doc in the same file (catalog misses route through `FkJoinResolution`, never a provenance-free pair list).
- `FieldBuilder`'s facet path silently degrades empty slots into a classifier rejection (`return Optional.empty()`).
- `JoinPathEmitter#emitKeyedJoin` (name-matched arm) and `TypeFetcherGenerator`'s routine-write hop 0 throw `IllegalStateException` at generation time, their messages asserting the opposite of the model doc: empty slots indicate a classifier bug, not a missing catalog.
- `emitCorrelationWhere` emits the silent `DSL.noCondition()`.
- `TypeFetcherGenerator#buildTableMethodParentCorrelation` repeats `emitCorrelationWhere`'s shape exactly: an empty-slot fallback emitting `DSL.noCondition()` under the same false "runtime-throwing ... surfaces at execution" comment.

Plan: decide emptiness once, at the producer, per "Shape the type as precisely as the fact allows". Preferred shape: reject empty `slots` in the `On.ColumnPairs` compact constructor, making the state unrepresentable; then delete the two emitter guards and both `noCondition()` fallbacks (with their false comments), correct the `On.ColumnPairs` record javadoc (itself an unpinned claim of exactly this item's family, missed by the sweep), and reconcile `FieldBuilder`'s reject-on-empty in the same commit (per "Acceptances": relaxing or tightening a producer audits every consumer of the shape in the same commit). Implementation gate: run the full reactor first to confirm nothing mints empty slots; the model doc claims unit tests do, so fix any `TestFixtures` producers that mint the degenerate shape. If a live population genuinely needs emptiness representable, fall back to a named degenerate state emitters switch on exhaustively, not a bare empty list. Either way this claim's resolution is behavior-shaped (constructor invariant), which is the charter's "enforcer", not scope creep; a fourth defensive throw is the shape that produced the contradiction and is rejected.

## 4. Dead §-anchor families in comment regions

The routed bullet named `BuildContext`'s two channel-rule comments; the defect is a family. Comment regions across main sources cite section-numbers of dead plan artifacts that resolve to nothing:

- The error-channel rule spec (`error-handling-parity.md`, deleted): §1/§2b/§2c/§3/§5 cites across `FieldBuilder` (the bulk), `BuildContext`, `ErrorMappingsClassGenerator`, `GraphitronSchemaValidator`, `GraphitronType`, `WithErrorChannel`, plus prose citations of the dead file name itself.
- The "Invariants §1/§2/§3" family across `TypeFetcherGenerator`, `ArgCallEmitter`, `GraphitronSchemaValidator`.
- Any sibling dead plan-file §-anchor an implementation-time grep for `§` surfaces. External-spec cites are live references and stay: JLS cites like §14.4.2 / §12.4.1, and the LSP-protocol §2.1.1 cite in `graphitron-lsp`'s `Positions`.

One mechanical fix rule per site: replace the ordinal with a `{@link}` to the live enforcer that carries the rule (`FieldBuilder#checkChannelLevelHandlerRules`, `ChannelRuleChecks#checkDuplicateMatchCriteria` and siblings, the fixture-pinned diagnostics), or delete the ordinal where the surrounding prose already names the fact. Where grouping information matters (parse-time table vs payload shape vs dispatch vs validation wrapper), state it in prose at intent altitude; do **not** author a numbered rule inventory in the architecture docs, which would be exactly the unguarded inventory "Principles are stated at altitude" rejects. The ordinal has also leaked into identifiers: `BuildContext`'s `rule7`/`rule8` locals get renamed to their rule names (`handlerCardinality` / `duplicateMatchCriteria` or similar).

Scope boundaries, stated so no site gets dropped: this item owns *comment regions in main sources*; §-anchors inside string literals that render into generated output or diagnostics belong to the generated-output hygiene item (R521), whose routed bullet slightly misattributes the split (`FieldBuilder`'s and `WithErrorChannel`'s dead-file cites are comment regions and belong here; only `ErrorRouterClassGenerator`'s is emitted text). `MappingsConstantNameDedup` is excluded: R496 owns that file's javadoc; its §3 cite is handled by R496 or after it lands. Test-source ordinals in display names and assertion messages render to no consumer surface and stay out.

## 5. `Source.OnlyChild`: the machine-unenforced honesty clause

The clause states: any emit strategy `OnlyChild` ever licenses must stay row-correct under query-alias fan-out (aliases can materialize `k` parents even on an arrival-`One` chain); today the emitters keep leaf-identity dispatch, so populating the arm changes no generated code; and "nothing machine-enforces this clause".

Plan: enforce the half that is enforceable today. The claim is universal (no emitter reads the arm at all), so prefer a census meta-test over a single-coordinate pipeline test: assert no generator main-source site dispatches on `Source.OnlyChild` distinctly from `Child` (same family as the repo's existing meta-tests), named for the fact it pins (arrival-uniform emit: populating `OnlyChild` changes no generated code), not for a Child/OnlyChild output equality. The javadoc then drops "nothing machine-enforces this clause", names the pin, and keeps the row-correctness constraint as intent-altitude prose, the constraint any future direct-SQL strategy must discharge.

Coordination: the direct-SQL OnlyChild item (`roadmap/direct-sql-onlychild-reentry-emit.md`, R471) is chartered to *start* reading the arm and already names the aliasing enforcer as its own deliverable. This item's meta-test is the tripwire R471 knowingly inverts or retires when it lands its strategy plus the aliasing test; record that hand-off in R471's body when this item ships. The javadoc cannot cite R471 by id (roadmap-reference guard), so the forward burden stays as the strategy-agnostic constraint prose it already is.

## Acceptance

- Each of the five claims resolved as specified: pinned to a named test, repointed to a gate-checked `{@link}`, or corrected in code with the false prose deleted; no claim rewritten as fresh unpinned confident prose.
- Claim 1's execution test and claim 5's meta-test land with the javadoc edits that name them; claim 3 lands the constructor invariant with all five restatement sites reconciled in one commit.
- Claim 4 leaves zero dead §-anchors in main-source comment regions (`grep -rn '§' graphitron*/src/main/java` shows only external-spec cites (JLS, LSP protocol) and R496-owned/R521-owned sites), and no new numbered inventory anywhere.
- Full reactor green under `mvn install -Plocal-db` with the `{@link}` reference gate and `RoadmapReferenceGuardTest` active.
- Follow-ons filed for anything routed out rather than resolved.
