---
id: R3
title: "Surface silent @splitQuery on @record-parent fields as a build warning"
status: In Review
priority: 5
theme: docs
depends-on: []
---

# Surface silent @splitQuery on @record-parent fields as a build warning

`@splitQuery` on a `@record`-parent field is a no-op: the record handoff already opens a new DataLoader-backed scope, so the directive cannot change anything. The classifier accepts it without complaint, the generator emits the same `RecordTableField` / `RecordLookupTableField` it would emit without the directive, and a developer who added `@splitQuery` to "force batching" never learns it changed nothing. [`code-generation-triggers.adoc:105`](../docs/code-generation-triggers.adoc) has been promising a build *warning* for this case for months; the code is silent.

Silent acceptance is a communication failure. The doc and the code disagree on what's supposed to happen, and the developer is the one who pays for the disagreement, with a non-obvious "wait, isn't this batched?" several refactors downstream. The user-stated goal of R3 ("make it easier to communicate around what graphitron does and why") cashes out exactly here: tell the author when their directive is structurally redundant, in the surface they already meet at build time (`mvn install` console output, IDE Maven integration). Edit-time surfacing through the LSP is its own arm and split out as [`R121 lsp-diagnostic-redundant-splitquery-on-record`](lsp-diagnostic-redundant-splitquery-on-record.md).

## Implementation

`FieldBuilder.classifyChildFieldOnResultType` (`FieldBuilder.java:2693`) is the one classifier path that produces the result-mapped `RecordTableField` / `RecordLookupTableField` arms; it does not read `DIR_SPLIT_QUERY` today. The two arms are constructed at two seams within that method: the `@sourceRow` branch (`FieldBuilder.java:2721/2724`, after `SourceRowDirectiveResolver.resolve` returns `Ok`) and the regular `@record`-parent branch (`FieldBuilder.java:2819/2822`, after `resolveRecordParentBatchKey` returns `Resolved`). Both branches end up DataLoader-batched ; lifter-keyed for `@sourceRow`, parent-record-keyed for the regular path ; so `@splitQuery` is structurally redundant on either: the directive cannot change the scope. The warning fires on both seams. When the field carries `@splitQuery`, call `ctx.addWarning(new BuildWarning(...))` with a message that names the field's coordinate and explains the redundancy:

> `<ParentType>.<fieldName>: @splitQuery is redundant on a @record-parent field; the record handoff already opens a new DataLoader-backed scope. The directive will be ignored.`

The classification outcome is unchanged. The warning is purely informational. Implementer's choice whether to inline the `addWarning` call at each seam or extract a small private helper; the principled requirement is that both seams emit, not the call shape.

This mirrors `TypeBuilder.java:663`'s `@table`-shadowed-by-`@record` warning: same family ("redundant directive on a parent shape that decides the structural outcome"), same channel (`BuildContext.addWarning`), same prose-message form. The marker-logger seam (`IdReferenceShimWarnFormatTest`, `AsConnectionSameTableWarnFormatTest`) is for warnings whose *content* is parsed by downstream migration tooling (canonical replacement directive text, FK-disambiguation hints); a redundancy advisory has no such consumer and belongs on `BuildWarning`.

The directive is read at classify time, not validate time, because the warning is purely informational: it does not gate any generator branch and does not need the validator's `ValidationError` channel (which is build-failing by contract). `GraphitronSchema.warnings()` is the existing advisory channel populated at the same site that decides the classification outcome.

`BuildContext.addWarning` (`BuildContext.java:192`) and `GraphitronSchema.warnings()` already exist; current producers are the federation entity-resolution pass (`EntityResolutionBuilder.java:208`) and the `@table`-shadowed-by-`@record` case (`TypeBuilder.java:663`). `GraphQLRewriteGenerator.java:222` already drains `schema.warnings()` to logback at build time. This item adds the third producer on the same channel; no new public API.

## Test surface

Pipeline-tier tests cover both warning-producing seams. Three fixtures, each with a child field carrying `@splitQuery`:

1. A `@record`-parent + `@table`-returning child (regular `@record`-parent path, classifies to `RecordTableField`).
2. The same shape with `@lookupKey` on the child (regular path, classifies to `RecordLookupTableField`).
3. A `@sourceRow` field on a `@record`-parent (DTO-parent path through `SourceRowDirectiveResolver`, classifies to `RecordTableField` or `RecordLookupTableField` ; one fixture is enough since both arms share the same emit-warning seam at lines 2721/2724).

Each fixture asserts:

1. Classification succeeds and produces the expected arm (i.e. is the right `RecordTableField` / `RecordLookupTableField` shape, not an `UnclassifiedField` ; this also covers the "errors are unchanged" intent without reaching for a non-existent `schema.errors()` API).
2. `schema.warnings()` carries one `BuildWarning` whose `message()` contains the field's coordinate and the substring `"@splitQuery is redundant on a @record-parent field"`.

`GraphitronSchemaBuilderTest.TABLE_PLUS_RECORD` is the shape comparator: same channel, same `BuildWarning::message` substring assertion. The test inlines the substring literal directly. No marker constant; no unit-tier message-format companion (the pipeline-tier assertion is the primary signal per the test-tier guide; a duplicate unit-tier pin is the bookkeeping `AsConnectionSameTableWarnFormatTest:30-35`'s own javadoc warns against absent a downstream parser). When [`R121`](lsp-diagnostic-redundant-splitquery-on-record.md) lands and adds a second consumer, the marker constant earns its keep at that point and both R3's pipeline test and R121's LSP test will reference it.

## Acceptance criteria

- `FieldBuilder.classifyChildFieldOnResultType` emits a `BuildWarning` when the field carries `@splitQuery` at *both* construction seams: the `@sourceRow` branch (lines 2721/2724) and the regular `@record`-parent branch (lines 2819/2822). Classification still produces `RecordTableField` / `RecordLookupTableField` (no fallthrough to `UnclassifiedField`).
- The warning's message names the field's coordinate (`<ParentType>.<fieldName>`) and contains the substring `"@splitQuery is redundant on a @record-parent field"`.
- Pipeline-tier tests pin the warning's presence + the structural classification outcome on three fixtures: one `RecordTableField` (regular `@record`-parent path), one `RecordLookupTableField` (regular path), and one `@sourceRow` field carrying `@splitQuery` (DTO-parent path).
- Build green: `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes.

## Out of scope

- Generalising the `BuildWarning` channel (introducing a `WarningKind` enum, a `warning` top-level kind in the diagnostics glossary, and extending `DiagnosticsDocCoverageTest` to enforce it). The three current producers (federation entity-resolution, `@table`-shadowed-by-`@record`, and the new `@splitQuery`-on-`@record` advisory) all carry curated prose messages; none has a downstream parser today. Earning the structure first is the principle; if a fourth producer or an LSP code-action consumer surfaces, that's a separate plan with the right grounding.
- LSP-tier diagnostic for the same warning. Split out as [`R121 lsp-diagnostic-redundant-splitquery-on-record`](lsp-diagnostic-redundant-splitquery-on-record.md). R121 lands the LSP arm + lifts R3's literal substring into a shared `BuildWarnings` constant when it has a real second consumer.
- A directive-composition rule registry (a closed declarative table of redundancy rules evaluated by both the build-tier classifier and the LSP). That's the broader architectural move that scales when the warning count justifies it; left for a deliberate plan grounded in the rule count we have at the time.
- Worked example + execution test for `@lookupKey + @condition` (the lookup-condition method signature). The code emits these correctly today; the residual gap is doc-only and warrants its own Backlog item if the user wants it surfaced. The N×M contract is already documented in `batching-model.adoc` (R68) and `design-decisions.adoc`.
- Lock-down pipeline test for `InlineTableFieldEmitter` filter-method wiring. The implementation fix shipped under "Generated-fetcher quality pass" (`srcAlias` threaded through `ArgCallEmitter.buildCallArgs`); the test-tier guide treats pipeline tests as the primary tier *for new behaviour*, and lock-down tests for shipped behaviour absent a specific regression hypothesis are unit-tier bookkeeping below the line for a deliberate plan.
- `FkJoin.alias` dead-storage cleanup. Split out as [`R120 fkjoin-alias-dead-storage`](fkjoin-alias-dead-storage.md) — cosmetic and unrelated to the communication goal.
- Any change that *rejects* `@splitQuery` on `@record`-parent fields. The directive remains classified-but-no-op; the warning makes the no-op visible without breaking schemas that carry the redundant directive today.

## History

This spec was rewritten from a six-item umbrella ("classification vocabulary follow-ups", drafted 2026-04-17 during the `code-generation-triggers.md` rewrite) to a single focused item after a 2026-05-09 reverification against the branch:

- Item 1 (G6 table) was already Done before R3 entered Spec; the table moved into `code-generation-triggers.adoc` correctly.
- Item 3 (audit other docs) was largely subsumed by R68's Diataxis user manual, which installed `docs/manual/explanation/{batching-model,classifier-mental-model,design-decisions}.adoc` with the corrected vocabulary. The remaining audit target named in the original spec (`graphitron-codegen-parent/graphitron-java-codegen/README.md`) is now out of scope per `CLAUDE.md`.
- Item 4's framing ("real blocker, gates G5/G6 execution tests") no longer applies: G5 has shipped (changelog line 124); the residual lookup-condition worked example is doc-only and warrants its own item if surfaced.
- Item 5 (`FkJoin.alias` dead storage) split to [`R120`](fkjoin-alias-dead-storage.md).
- Item 6's implementation fix shipped under "Generated-fetcher quality pass" (changelog line 137); the lock-down test is regression-protection for already-shipped behavior and not worth a dedicated item.

The narrowed spec went through one revision pass each in two architectural directions before settling on build-tier-only:

- An architect-review pass tightened the build-tier-only spec by dropping a marker constant + unit-tier message-format pin as premature abstraction (no concrete present consumer).
- An LSP-fold attempt expanded R3 to ship the LSP-tier diagnostic alongside, which would have justified the marker constant by adding a real second consumer. The fold was reversed and the LSP arm split to [`R121 lsp-diagnostic-redundant-splitquery-on-record`](lsp-diagnostic-redundant-splitquery-on-record.md): R3's scope ("docs theme, focused communication-gap fix") shouldn't grow into a two-module architectural lift, and R121 lands deliberately with the constant earning its keep at that point.

A reviewer pass on 2026-05-09 surfaced an asymmetry in the original seam wording: `classifyChildFieldOnResultType` constructs `RecordTableField` / `RecordLookupTableField` at two places (the `@sourceRow` branch and the regular `@record`-parent branch), and only the regular path was wired for the warning. Neither resolver reads `DIR_SPLIT_QUERY`, so `@splitQuery` is just as silently no-op on `@sourceRow` fields. An initial revision deferred the `@sourceRow` arm to a follow-up backlog item; that was the wrong call ; the redundancy story is identical, the implementation cost of covering both seams is one extra check, and the communication-failure framing of the spec doesn't admit a carve-out. Tightened to fire on both seams with a third pipeline-tier fixture covering the `@sourceRow` shape.
