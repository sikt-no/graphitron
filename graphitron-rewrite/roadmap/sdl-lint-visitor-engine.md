---
id: R398
title: "SDL lint engine with ESLint-style built-in visitors"
status: Backlog
bucket: feature
priority: 5
theme: lsp
depends-on: []
created: 2026-06-29
last-updated: 2026-06-29
---

# SDL lint engine with ESLint-style built-in visitors

## Problem

Graphitron validates its own directive coordinates against the jOOQ catalog and re-derives a handful of build-tier warnings as LSP squiggles (`Diagnostics.java`), but there is no general lint-rule engine. Every diagnostic is hand-wired into `Diagnostics.compute`, and consumers cannot enforce their own SDL conventions (naming, required directive pairings, banned shapes). The dispatch is already visitor-shaped (one walk over the directive nodes, dispatch on the coordinate `Behavior` arm), yet it is bespoke per rule rather than a registry of independent rules.

## Decision (settled with the user)

Adopt the ESLint execution model: parse the SDL once into the typed AST, run a single shared traversal, and dispatch each node to the visitors that subscribed to its kind/coordinate; each visitor reports problems carrying a source range, message, and severity. Rules are independent visitors registered against the engine, not branches inside one method.

Ship a useful set of **built-in** visitors first. The plugin model (consumer-supplied custom visitor functions wired into the same registry) is deliberately deferred to a follow-on item until the built-in set has proven the engine and shipped value. This mirrors the project's own R3 then R121 discipline: do not build the extensibility abstraction before a real population of rules exists to justify its shape.

## Architecture

Build-side evaluation is the spine. The authoritative tier is the build, where graphitron already parses with graphql-java and runs its classifier. The GraphQL-native idiom (graphql-schema-linter) is exactly this: it reuses graphql-js's own `validate()` / `visitInParallel` / `TypeInfo` to run all rule visitors in one pass, and reports via `GraphQLError(node)` so locations fall out of the node's source position. Graphitron's analog is direct:

- graphql-java `Parser` / `Document` and `NodeTraverser` / `NodeVisitor` for the one-pass traversal.
- The classifier plus jOOQ catalog as a *richer* `TypeInfo`: it knows table/column/FK resolution, not just GraphQL types.
- Output rides the existing sealed channels (`BuildWarning` / `ValidationError` / `Rejection`), never a new stringly-typed carrier, so `Diagnostics.severityOf` and the report machinery stay single-sourced.

The LSP gets parity for free. Diagnostics already replays the build's `ValidationReport` into squiggles (`Diagnostics.validatorDiagnostics`, with the freshness-aware silence policy of R139). Emitting custom-rule findings into the same report means a rule fires in CI and in the editor from one definition, with no second evaluator and no drift seam.

Two rule tiers, split by what a rule needs to see:

- Schema-aware rules (need resolved types, catalog, classification) run build-side over the graphql-java AST, the graphql-schema-linter shape.
- Purely syntactic rules (naming, directive presence/absence, nesting) could additionally run over the LSP's tree-sitter tree for instant edit-time feedback, but CI parity still requires they run build-side. Whether to share one tree-sitter engine across both tiers (moving the tree-sitter substrate below the LSP in the module graph) or keep the build on graphql-java and let the LSP project is an open Spec decision (see Open questions).

## Scope

In scope:

- A visitor/rule engine over the build's typed AST: a rule contract (subscribe to node kind/coordinate, report a finding with range, severity, message), a registry, and a single-pass traversal that dispatches to all registered rules.
- A useful starter set of built-in lint visitors. The exact set is chosen at Spec; candidates include the existing R121 redundant `@splitQuery` on `@record` advisory, R296 deprecated-directive usage, and common SDL-convention checks (naming, required directive pairings).
- Findings emitted through the existing `ValidationReport` / `BuildWarning` channel so they surface both at build/CI and in the LSP via the existing replay path.

Out of scope (deferred):

- Consumer-supplied custom visitor functions, i.e. a plugin SPI. This is the explicit follow-on; file it once the built-in set ships. The engine should be shaped so wiring external visitors in later is additive, but no public extension API is committed here.
- A declarative rule-config DSL (Spectral-style selector plus closed predicate library) as an alternative no-code authoring surface. Noted as prior art; not part of this item.
- Running the full classifier inside the LSP, and any new protocol surface.

## Prior-art references (research, 2026-06-29)

- **ESLint**: pluggable parser to ESTree, one shared traversal, rules as visitor factories (`create(context)` returning node-kind-keyed listeners), range-based `context.report`, range-based autofix. The `no-restricted-syntax` rule shows a declarative-selector escape hatch living in config; plugins are the code path.
- **graphql-schema-linter** (most relevant): a thin layer over graphql-js. `parse` then `buildASTSchema` then `validate(schema, ast, rules)`, which builds one `ValidationContext`, merges rule visitors with `visitInParallel`, and walks once with `visitWithTypeInfo`. Rules are `function(context){ return { EnumValueDefinition(node){...} } }`; custom rules are the same shape, code only, no declarative DSL.
- **graphql-eslint**: adapts the GraphQL AST to ESTree (renames `kind` to `type`, lazy `node.typeInfo()`, `parserServices.schema`) so ESLint owns the traversal; reuses graphql-js only for parse plus `TypeInfo`.
- **Spectral**: the reference for a *declarative* authoring surface (JSONPath `given` plus a closed predicate-function library plus severity/message), code only as a fallback. The alternative we are not taking now.
- **ast-grep**: declarative rule object over tree-sitter, including custom grammars; the model for the tree-sitter route if syntactic rules ever want a no-code surface.
- **Buf**: a closed, curated rule set selected and tuned via config, custom rules only as compiled plugins; the precedent for keeping the built-in set closed and deferring extensibility.

Cross-tool synthesis: the schema-linting idiom is visitor-over-typed-AST rules reusing the schema library's own validation traversal. Graphitron is well-positioned because graphql-java *is* that library and the classifier is a superset of `TypeInfo`. A declarative config layer is optional sugar, not what the GraphQL-native tools actually do.

## Relationships

- **R347** (LSP structural consolidation): its Slice 3 introduces a `CompletionProvider` registry keyed by `Behavior`, replacing a manual dispatch waterfall. This item is the diagnostics-side sibling of that registry shape; landing on the consolidated navigation/dispatch primitives is preferable to forking another copy.
- **R121** (redundant `@splitQuery` on `@record`): a natural first built-in visitor; its marker-constant note already anticipates a shared rule home.
- **R296** (deprecated-directive usage) and **R345** (schema parse-failure squiggle): further built-in-visitor candidates and adjacent diagnostics.
- **R139** freshness-aware silence policy governs how LSP-projected findings behave on stale snapshots; built-in visitors must place themselves relative to it.

## Open questions (to settle at Spec)

- Which parse representation the engine evaluates against for syntactic rules: one shared tree-sitter engine moved below the LSP (CI parity through tree-sitter), or build-side graphql-java with the LSP projecting (CI parity through the existing replay). The latter is the lower-risk default; the former avoids a double parse but forces a module-graph change.
- The exact starter set of built-in visitors and their severities.
- The rule contract's shape (how a visitor subscribes and reports) and where the registry lives, ideally consistent with R347's provider-registry instinct.
- Whether the deferred extensibility surface, when it arrives, is code visitors (the ESLint and graphql-schema-linter path the user chose) or also a declarative config layer.
