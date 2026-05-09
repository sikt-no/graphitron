---
id: R121
title: "LSP diagnostic for redundant @splitQuery on @record-parent fields"
status: Backlog
bucket: feature
priority: 7
theme: lsp
depends-on: [classification-vocabulary-followups]
---

# LSP diagnostic for redundant @splitQuery on @record-parent fields

R3 (`classification-vocabulary-followups`) lands the build-tier warning when `@splitQuery` is applied to a field whose enclosing type carries `@record`. The build-tier warning is only visible when the user runs `mvn install` (or hits a build via the IDE's Maven integration); SDL editing is a much faster feedback loop, and the LSP can surface the same advisory inline at edit time.

The LSP already has a strict precedent for this pattern: `Diagnostics.java:252-256` re-derives the build-time `-parameters`-missing warning (`ServiceCatalog.emitParametersWarning`) as a `DiagnosticSeverity.Warning`, with a javadoc comment at line 245-247 explicitly naming the build-tier sibling so the drift seam is visible. This item follows the same pattern for R3's warning.

## Implementation sketch

Add a new validator method to `Diagnostics.compute` (`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/diagnostics/Diagnostics.java`) that walks `@splitQuery`-bearing field directives. For each, use `TypeContext.enclosingTypeDefinition` (the same primitive `validateField` already uses) to find the enclosing type, then check whether the type carries `@record`. If yes, emit a `DiagnosticSeverity.Warning` whose range pinpoints the `@splitQuery` directive node so the LSP client surfaces it inline.

`TypeContext` currently exposes `tableNameOf`; a sibling `hasDirective(typeDef, "record")` (or generalising `tableNameOf` to a directive-walking primitive both reads can call) is the cleanest seam. SDL-only — no jOOQ catalog touched, no classifier run, no classpath scan; cheap enough to run on every parse.

## Marker-constant opportunity

R3 ships its build-tier warning with the message substring `"@splitQuery is redundant on a @record-parent field"` inlined as a literal. When this item lands, lift that literal to a shared `BuildWarnings` holder in the graphitron module so both consumers reference one source-of-truth. The `graphitron-lsp → graphitron` Maven dependency already exists; both `FieldBuilder` and `Diagnostics` reach the constant with no new wiring. Both tests pin the constant; drift in the wording becomes a compile-time error rather than a slow prose divergence (the failure mode the existing `ServiceCatalog.emitParametersWarning` vs `Diagnostics.java:252` precedent already exhibits).

The architect's principle from R3's review is observed: anchor the constant on a *concrete* present consumer. R3 alone has one consumer and no constant; this item adds the second consumer and earns the constant. Don't introduce the constant pre-emptively in R3.

## Test surface

LSP-tier test in `graphitron-lsp/src/test/java/.../DiagnosticsTest.java`:
- Positive: `@record`-bound type with a `@splitQuery`-bearing field → one `Diagnostic` with `severity == Warning`, range over the `@splitQuery` directive node, message contains the marker.
- Negative: `@table`-bound parent with the same `@splitQuery` → no diagnostic.

When the marker constant lands, both R3's pipeline test and this item's LSP test reference it directly.

## Acceptance criteria

- `Diagnostics.compute` emits a `Diagnostic` (`severity == Warning`) on every `@splitQuery`-bearing field whose enclosing type carries `@record`. Negative case (`@table`-bound parent) emits no diagnostic.
- The diagnostic's range covers the `@splitQuery` directive node.
- Marker constant lives in a shared location (e.g. `BuildWarnings` in graphitron module). R3's `FieldBuilder` call site is updated to reference the constant.
- LSP-tier test pins positive + negative + range.
- R3's existing pipeline test is updated to reference the constant.
- Build green: `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes.

## Out of scope

- A general "directive-composition rule registry" that lifts every redundancy advisory into a shared declarative table evaluated by both surfaces. That's a larger architectural item — closed-set rules + portable predicates over two SDL representations (GraphQL-Java's typed AST vs tree-sitter's syntactic tree) — and warrants its own plan grounded in the rule count we have at the time. This item is the focused per-warning re-derivation that mirrors the existing `Diagnostics.java:252` precedent.
- Running the full classifier inside the LSP. Even bigger scope; performance, partial-SDL, range-mapping, and catalog reconciliation each have to be considered.

## Why split from R3

R3 was originally drafted as a docs-themed item ("Classification vocabulary follow-ups"), focused on fixing a silent-acceptance footgun in the build-tier classifier. During spec revision the LSP-fold was attempted but expanded R3's scope across two modules + two test surfaces + a shared-constant abstraction, which strayed from the focused communication-gap goal R3 was narrowed to. The LSP arm is its own cohesive piece of work; splitting it lets R3 ship its specific build-tier win quickly and lets this item land deliberately with the right grounding (one new consumer, one earned constant).
