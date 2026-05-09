---
id: R3
title: "Surface silent @splitQuery on @record-parent fields as a warning"
status: Spec
priority: 5
theme: docs
depends-on: []
---

# Surface silent @splitQuery on @record-parent fields as a warning

`@splitQuery` on a `@record`-parent field is a no-op: the record handoff already opens a new DataLoader-backed scope, so the directive cannot change anything. The classifier accepts it without complaint, the generator emits the same `RecordTableField` / `RecordLookupTableField` it would emit without the directive, and a developer who added `@splitQuery` to "force batching" never learns it changed nothing. [`code-generation-triggers.adoc:105`](../docs/code-generation-triggers.adoc) has been promising a build *warning* for this case for months; the code is silent.

Silent acceptance is a communication failure. The doc and the code disagree on what's supposed to happen, and the developer is the one who pays for the disagreement, with a non-obvious "wait, isn't this batched?" several refactors downstream. The user-stated goal of R3 ("make it easier to communicate around what graphitron does and why") cashes out exactly here: tell the author when their directive is structurally redundant, in both surfaces where they meet graphitron — `mvn install` (build-tier `BuildWarning`) and live SDL editing (LSP-tier `DiagnosticSeverity.Warning`).

## Implementation

The work has two surfaces sharing one rule and one message marker.

### Marker constant

A single `static final String` declared once, referenced by both consumers and both tests. Captures the descriptive core of the message:

> `"@splitQuery is redundant on a @record-parent field"`

The natural home is a small `BuildWarnings` constants holder in the graphitron module (sibling of `BuildWarning.java`); both `FieldBuilder` and the LSP's `Diagnostics.java` reach it through the `graphitron-lsp → graphitron` Maven dependency that already exists. As more `BuildWarning` producers land, each contributes one constant; the file stays a flat collection until enumerated structure earns its keep (see "Out of scope").

The constant exists because there are *two* consumers, not one. Build-tier and LSP-tier both pin against it, so any drift in the wording is a compile-time error rather than a prose-divergence trap. The existing `ServiceCatalog.emitParametersWarning` (`ServiceCatalog.java:663`) vs `Diagnostics.java:252` precedent shows the failure mode of *not* sharing a constant: the two prose strings have already drifted apart. R3 ships both surfaces in lockstep so this one stays aligned.

### Build-tier (graphitron module)

`FieldBuilder.classifyChildFieldOnResultType` (`FieldBuilder.java:2693`) is the one classifier path that produces the result-mapped `RecordTableField` / `RecordLookupTableField` arms; it does not read `DIR_SPLIT_QUERY` today. Add a check at the seam where the field has been confirmed to classify as one of those two arms (i.e. after the `@sourceRow` / `@service` / scalar branches return their own outcomes; immediately before the property/record path constructs the `RecordTableField` / `RecordLookupTableField`). When the field carries `@splitQuery`, call `ctx.addWarning(new BuildWarning(...))` with the marker constant and the field's coordinate:

> `<ParentType>.<fieldName>: @splitQuery is redundant on a @record-parent field; the record handoff already opens a new DataLoader-backed scope. The directive will be ignored.`

The classification outcome is unchanged. The warning is purely informational.

This mirrors `TypeBuilder.java:663`'s `@table`-shadowed-by-`@record` warning: same family ("redundant directive on a parent shape that decides the structural outcome"), same channel (`BuildContext.addWarning`), same prose-message form. The marker-logger seam (`IdReferenceShimWarnFormatTest`, `AsConnectionSameTableWarnFormatTest`) is for warnings whose *content* is parsed by downstream migration tooling (canonical replacement directive text, FK-disambiguation hints); a redundancy advisory has no such consumer and belongs on `BuildWarning`.

The directive is read at classify time, not validate time, because the warning is purely informational: it does not gate any generator branch and does not need the validator's `ValidationError` channel (which is build-failing by contract). `GraphitronSchema.warnings()` is the existing advisory channel populated at the same site that decides the classification outcome.

`BuildContext.addWarning` (`BuildContext.java:192`) and `GraphitronSchema.warnings()` already exist; current producers are the federation entity-resolution pass (`EntityResolutionBuilder.java:208`) and the `@table`-shadowed-by-`@record` case (`TypeBuilder.java:663`). `GraphQLRewriteGenerator.java:222` already drains `schema.warnings()` to logback at build time. This item adds the third producer on the same channel; no new public API.

### LSP-tier (graphitron-lsp module)

The LSP already re-derives one build-time warning at edit time: `Diagnostics.java:252-256` mirrors `ServiceCatalog.emitParametersWarning` as a `DiagnosticSeverity.Warning`, with a javadoc comment at line 245-247 explicitly naming the build-tier sibling so the drift seam is visible. R3 follows the same pattern.

Add a new validator method to `Diagnostics.compute` that walks `@splitQuery`-bearing field directives. For each, use `TypeContext.enclosingTypeDefinition` (the same primitive `validateField` already uses) to find the enclosing type, then check whether the type carries `@record`. If yes, emit a `DiagnosticSeverity.Warning` whose message contains the marker constant. The diagnostic's range pinpoints the `@splitQuery` directive node so the LSP client surfaces it inline.

`TypeContext` currently exposes `tableNameOf`; a sibling `hasDirective(typeDef, "record")` (or generalising `tableNameOf` to a directive-walking primitive both reads can call) is the cleanest seam. SDL-only — no jOOQ catalog touched, no classifier run, no classpath scan; cheap enough to run on every parse.

The LSP check is a re-derivation of the build-tier rule, not a re-use. The two tree-walks are independent (tree-sitter AST in the LSP, GraphQL-Java + jOOQ in the build) and pin the same predicate via tests on each side. The shared marker constant aligns the *message*, not the *predicate* — predicate drift surfaces as a test failure on whichever side regressed.

## Test surface

**Pipeline-tier (graphitron module).** Fixture with a `@record`-parent + `@table`-returning child carrying `@splitQuery`. Asserts:

1. Classification succeeds and produces `RecordTableField` (or `RecordLookupTableField` for the `@lookupKey` variant).
2. `schema.warnings()` carries one `BuildWarning` whose `message()` contains the field's coordinate and the marker constant.
3. `schema.errors()` is empty.

`GraphitronSchemaBuilderTest.TABLE_PLUS_RECORD` is the shape comparator: same channel, same `BuildWarning::message` substring assertion.

**LSP-tier (graphitron-lsp module).** Fixture in `DiagnosticsTest` with a `@record`-bound type containing a `@splitQuery`-bearing field. Asserts:

1. `Diagnostics.compute` returns one `Diagnostic` with `severity == Warning` whose `message` contains the marker constant.
2. The diagnostic's range covers the `@splitQuery` directive node.
3. The negative case — a `@table`-bound parent with the same `@splitQuery` — emits no warning.

Both tests reference the marker constant. A wording change to the constant fails both unless they're updated together; a predicate drift on either side fails its own test.

No unit-tier message-format companion. The marker constant is the canonical pin; the two consumer-side tests cover both sites the constant flows to. `AsConnectionSameTableWarnFormatTest:30-35`'s own javadoc warns against duplicate-pin defence-in-depth absent a downstream parser, and that argument applies equally here.

## Acceptance criteria

- A marker constant (string descriptor `"@splitQuery is redundant on a @record-parent field"`) is declared once and referenced by both consumers and both tests.
- `FieldBuilder.classifyChildFieldOnResultType` emits a `BuildWarning` when the field carries `@splitQuery`. The message contains the field's coordinate (`<ParentType>.<fieldName>`) and the marker constant. Classification still produces `RecordTableField` / `RecordLookupTableField`. `errors()` is unchanged.
- `Diagnostics.compute` (graphitron-lsp) emits a `Diagnostic` with `severity == Warning` whose message contains the marker constant and whose range covers the `@splitQuery` directive node, on every `@splitQuery`-bearing field whose enclosing type carries `@record`. Negative case (`@table`-bound parent) emits no diagnostic.
- Pipeline-tier test (graphitron module) pins the build-tier warning's presence + the structural classification outcome on at least one `RecordTableField` fixture and one `RecordLookupTableField` fixture.
- LSP-tier test (graphitron-lsp module, in `DiagnosticsTest`) pins the LSP-tier diagnostic's presence + range + negative case.
- Both tests reference the marker constant directly so a wording change requires updating both.
- Build green: `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes.

## Out of scope

- Generalising the `BuildWarning` channel (introducing a `WarningKind` enum, a `warning` top-level kind in the diagnostics glossary, and extending `DiagnosticsDocCoverageTest` to enforce it). The three current producers (federation entity-resolution, `@table`-shadowed-by-`@record`, and the new `@splitQuery`-on-`@record` advisory) all carry curated prose messages; none is consumed by an LSP code-action or a migration parser. The marker constant this item adds is *unstructured* (one `String` per warning, no kind tag, no severity field) — sufficient for inline LSP surfacing and build-tier rendering, insufficient for code-action dispatch. When an LSP fix-it consumer or a fourth producer surfaces, that's the plan with the right grounding to introduce structure.
- Worked example + execution test for `@lookupKey + @condition` (the lookup-condition method signature). The code emits these correctly today; the residual gap is doc-only and warrants its own Backlog item if the user wants it surfaced. The N×M contract is already documented in `batching-model.adoc` (R68) and `design-decisions.adoc`.
- Lock-down pipeline test for `InlineTableFieldEmitter` filter-method wiring. The implementation fix shipped under "Generated-fetcher quality pass" (`srcAlias` threaded through `ArgCallEmitter.buildCallArgs`); the test-tier guide treats pipeline tests as the primary tier *for new behaviour*, and lock-down tests for shipped behaviour absent a specific regression hypothesis are unit-tier bookkeeping below the line for a deliberate plan.
- `FkJoin.alias` dead-storage cleanup. Split out as [`R120 fkjoin-alias-dead-storage`](fkjoin-alias-dead-storage.md) — cosmetic and unrelated to the communication goal.
- Any change that *rejects* `@splitQuery` on `@record`-parent fields. The directive remains classified-but-no-op; the warning makes the no-op visible without breaking schemas that carry the redundant directive today.

## History

This spec was rewritten from a six-item umbrella ("classification vocabulary follow-ups", drafted 2026-04-17 during the `code-generation-triggers.md` rewrite) to a single focused item after a 2026-05-09 reverification against the branch, then folded the LSP-tier diagnostic in after the architect-review pass:

- Item 1 (G6 table) was already Done before R3 entered Spec; the table moved into `code-generation-triggers.adoc` correctly.
- Item 3 (audit other docs) was largely subsumed by R68's Diataxis user manual, which installed `docs/manual/explanation/{batching-model,classifier-mental-model,design-decisions}.adoc` with the corrected vocabulary. The remaining audit target named in the original spec (`graphitron-codegen-parent/graphitron-java-codegen/README.md`) is now out of scope per `CLAUDE.md`.
- Item 4's framing ("real blocker, gates G5/G6 execution tests") no longer applies: G5 has shipped (changelog line 124); the residual lookup-condition worked example is doc-only and warrants its own item if surfaced.
- Item 5 (`FkJoin.alias` dead storage) split to [`R120`](fkjoin-alias-dead-storage.md).
- Item 6's implementation fix shipped under "Generated-fetcher quality pass" (changelog line 137); the lock-down test is regression-protection for already-shipped behavior and not worth a dedicated item.
- The architect-review pass tentatively dropped the marker constant + the unit-tier message-format pin as premature abstraction (no concrete present consumer). The LSP-fold reverses both decisions: the LSP becomes a present consumer of the same warning, and the marker constant is now what aligns build-tier and LSP-tier so prose drift between them is a compile-time error rather than a slow divergence (the failure mode the existing `ServiceCatalog.emitParametersWarning` vs `Diagnostics.java:252` precedent already exhibits). The unit-tier message-format pin stays dropped — the two consumer-side tests already pin the constant from each side, so a unit-tier companion would be the duplicate-pin defence-in-depth `AsConnectionSameTableWarnFormatTest:30-35`'s javadoc warns against.
