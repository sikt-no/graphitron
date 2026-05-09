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

`BuildContext.addWarning` (`BuildContext.java:192`) and `GraphitronSchema.warnings()` already exist; the federation entity-resolution pass is the existing producer (`EntityResolutionBuilder.java:208`), and `GraphQLRewriteGenerator.java:222` already drains `schema.warnings()` to logback at build time. This item adds the second producer on the same channel; no new public API.

## Test surface

Pipeline-tier test: a fixture with a `@record`-parent + `@table`-returning child carrying `@splitQuery`. Asserts:

1. Classification succeeds and produces `RecordTableField` (or `RecordLookupTableField` for the `@lookupKey` variant).
2. `schema.warnings()` carries one `BuildWarning` whose `message()` contains the field's coordinate and a stable marker substring (e.g. `"@splitQuery is redundant on a @record-parent field"`).
3. `schema.errors()` is empty.

Unit-tier message-format test: pin the marker substring on a single fixture so future drift in the message wording is caught at the assertion that future migration tooling (or an LSP rule) might want to parse. Mirrors `IdReferenceShimWarnFormatTest` / `AsConnectionSameTableWarnFormatTest`'s shape, except this warning rides the `BuildWarning` channel rather than a logback marker logger; the corresponding pattern already exists in `GraphitronSchemaBuilderTest.TABLE_PLUS_RECORD` (asserting on `BuildWarning::message`).

Anchor the marker as a `static final String` on `FieldBuilder` so test assertions and any future LSP/migration tooling reference the same constant rather than copy-pasting prose.

## Diagnostics glossary

`docs/manual/reference/diagnostics-glossary.adoc` covers errors and deferred reasons but has no "warnings" section. Add one — even a one-paragraph header explaining that warnings are non-fatal author signals, surfaced at build time alongside errors but never failing the build — and an entry for this warning, anchored on the marker constant. This serves the user-stated goal directly: the next developer who hits the warning has a doc page that names it and explains why their directive is redundant. The drift seam comes naturally because the diagnostics-glossary doc-coverage tests already exist for the error / deferred sections; extending coverage to warnings is a clean ride-along.

## Acceptance criteria

- `FieldBuilder.classifyChildFieldOnResultType` emits a `BuildWarning` when the field carries `@splitQuery`. Classification still produces `RecordTableField` / `RecordLookupTableField`. `errors()` is unchanged.
- The warning's message names the field's coordinate (`<ParentType>.<fieldName>`) and contains a stable marker constant declared on `FieldBuilder`.
- Pipeline-tier test pins the warning's presence + the structural classification outcome on at least one `RecordTableField` fixture and one `RecordLookupTableField` fixture.
- Unit-tier message-format test pins the marker substring against the constant.
- `docs/manual/reference/diagnostics-glossary.adoc` gains a warnings section and an entry for this warning, anchored on the marker constant.
- Build green: `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes.

## Out of scope

- Generalising the warnings channel further (kinds, severity levels, structured rendering). The federation pass is the only existing producer; this item adds a second. If a third producer needs structure, it's a separate concern.
- Worked example + execution test for `@lookupKey + @condition` (the lookup-condition method signature). The code emits these correctly today; the residual gap is doc-only and warrants its own Backlog item if the user wants it surfaced. The N×M contract is already documented in `batching-model.adoc` (R68) and `design-decisions.adoc`.
- Lock-down pipeline test for `InlineTableFieldEmitter` filter-method wiring. The implementation fix shipped under "Generated-fetcher quality pass" (`srcAlias` threaded through `ArgCallEmitter.buildCallArgs`); a regression test for already-shipped behavior is below the priority of the active gap.
- `FkJoin.alias` dead-storage cleanup. Split out as [`R120 fkjoin-alias-dead-storage`](fkjoin-alias-dead-storage.md) — cosmetic and unrelated to the communication goal.
- Any change that *rejects* `@splitQuery` on `@record`-parent fields. The directive remains classified-but-no-op; the warning makes the no-op visible without breaking schemas that carry the redundant directive today.

## History

This spec was rewritten from a six-item umbrella ("classification vocabulary follow-ups", drafted 2026-04-17 during the `code-generation-triggers.md` rewrite) to a single focused item after a 2026-05-09 reverification against the branch:

- Item 1 (G6 table) was already Done before R3 entered Spec; the table moved into `code-generation-triggers.adoc` correctly.
- Item 3 (audit other docs) was largely subsumed by R68's Diataxis user manual, which installed `docs/manual/explanation/{batching-model,classifier-mental-model,design-decisions}.adoc` with the corrected vocabulary. The remaining audit target named in the original spec (`graphitron-codegen-parent/graphitron-java-codegen/README.md`) is now out of scope per `CLAUDE.md`.
- Item 4's framing ("real blocker, gates G5/G6 execution tests") no longer applies: G5 has shipped (changelog line 124); the residual lookup-condition worked example is doc-only and warrants its own item if surfaced.
- Item 5 (`FkJoin.alias` dead storage) split to [`R120`](fkjoin-alias-dead-storage.md).
- Item 6's implementation fix shipped under "Generated-fetcher quality pass" (changelog line 137); the lock-down test is regression-protection for already-shipped behavior and not worth a dedicated item.
