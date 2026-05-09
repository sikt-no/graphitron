---
id: R3
title: "Surface silent @splitQuery on @record-parent fields as a build warning"
status: Spec
priority: 5
theme: docs
depends-on: []
---

# Surface silent @splitQuery on @record-parent fields as a build warning

`@splitQuery` on a `@record`-parent field is a no-op: the record handoff already opens a new DataLoader-backed scope, so the directive cannot change anything. The classifier accepts it without complaint, the generator emits the same `RecordTableField` / `RecordLookupTableField` it would emit without the directive, and a developer who added `@splitQuery` to "force batching" never learns it changed nothing. [`code-generation-triggers.adoc:105`](../docs/code-generation-triggers.adoc) has been promising a build *warning* for this case for months; the code is silent.

Silent acceptance is a communication failure. The doc and the code disagree on what's supposed to happen, and the developer is the one who pays for the disagreement, with a non-obvious "wait, isn't this batched?" several refactors downstream. The user-stated goal of R3 ("make it easier to communicate around what graphitron does and why") cashes out exactly here: tell the author when their directive is structurally redundant.

## Implementation

`FieldBuilder.classifyChildFieldOnResultType` (`FieldBuilder.java:2693`) is the one classifier path that produces the result-mapped `RecordTableField` / `RecordLookupTableField` arms; it does not read `DIR_SPLIT_QUERY` today. Add a check at the seam where the field has been confirmed to classify as one of those two arms (i.e. after the `@sourceRow` / `@service` / scalar branches return their own outcomes; immediately before the property/record path constructs the `RecordTableField` / `RecordLookupTableField`). When the field carries `@splitQuery`, call `ctx.addWarning(new BuildWarning(...))` with a message that names the field's coordinate and explains the redundancy:

> `<ParentType>.<fieldName>: @splitQuery is redundant on a @record-parent field; the record handoff already opens a new DataLoader-backed scope. The directive will be ignored.`

The classification outcome is unchanged. The warning is purely informational.

This mirrors `TypeBuilder.java:663`'s `@table`-shadowed-by-`@record` warning: same family ("redundant directive on a parent shape that decides the structural outcome"), same channel (`BuildContext.addWarning`), same prose-message form. The marker-logger seam (`IdReferenceShimWarnFormatTest`, `AsConnectionSameTableWarnFormatTest`) is for warnings whose *content* is parsed by downstream migration tooling (canonical replacement directive text, FK-disambiguation hints); a redundancy advisory has no such consumer and belongs on `BuildWarning`.

The directive is read at classify time, not validate time, because the warning is purely informational: it does not gate any generator branch and does not need the validator's `ValidationError` channel (which is build-failing by contract). `GraphitronSchema.warnings()` is the existing advisory channel populated at the same site that decides the classification outcome.

`BuildContext.addWarning` (`BuildContext.java:192`) and `GraphitronSchema.warnings()` already exist; current producers are the federation entity-resolution pass (`EntityResolutionBuilder.java:208`) and the `@table`-shadowed-by-`@record` case (`TypeBuilder.java:663`). `GraphQLRewriteGenerator.java:222` already drains `schema.warnings()` to logback at build time. This item adds the third producer on the same channel; no new public API.

## Test surface

Pipeline-tier test: a fixture with a `@record`-parent + `@table`-returning child carrying `@splitQuery`. Asserts:

1. Classification succeeds and produces `RecordTableField` (or `RecordLookupTableField` for the `@lookupKey` variant).
2. `schema.warnings()` carries one `BuildWarning` whose `message()` contains the field's coordinate and the substring `"@splitQuery is redundant on a @record-parent field"`.
3. `schema.errors()` is empty.

`GraphitronSchemaBuilderTest.TABLE_PLUS_RECORD` is the shape comparator: same channel, same `BuildWarning::message` substring assertion. The test inlines the substring literal directly. No marker-constant; no unit-tier message-format companion (the pipeline-tier assertion is the primary signal per the test-tier guide; a duplicate unit-tier pin is the bookkeeping `AsConnectionSameTableWarnFormatTest:30-35`'s own javadoc warns against absent a downstream parser).

## Acceptance criteria

- `FieldBuilder.classifyChildFieldOnResultType` emits a `BuildWarning` when the field carries `@splitQuery`. Classification still produces `RecordTableField` / `RecordLookupTableField`. `errors()` is unchanged.
- The warning's message names the field's coordinate (`<ParentType>.<fieldName>`) and contains the substring `"@splitQuery is redundant on a @record-parent field"`.
- Pipeline-tier test pins the warning's presence + the structural classification outcome on at least one `RecordTableField` fixture and one `RecordLookupTableField` fixture.
- Build green: `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes.

## Out of scope

- Generalising the `BuildWarning` channel (introducing a `WarningKind` enum, a `warning` top-level kind in the diagnostics glossary, and extending `DiagnosticsDocCoverageTest` to enforce it). The three current producers (federation entity-resolution, `@table`-shadowed-by-`@record`, and the new `@splitQuery`-on-`@record` advisory) all carry curated prose messages; none has a downstream parser today. Earning the structure first is the principle; if a fourth producer or an LSP fix-it consumer surfaces, that's a separate plan with the right grounding.
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
