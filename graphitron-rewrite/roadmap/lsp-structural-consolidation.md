---
id: R347
title: "Consolidate graphitron-lsp navigation, dispatch, and result-building"
status: Ready
bucket: architecture
priority: 5
theme: lsp
depends-on: []
created: 2026-06-19
last-updated: 2026-06-19
---

# Consolidate graphitron-lsp navigation, dispatch, and result-building

`graphitron-lsp` grew feature-by-feature (completions, diagnostics, hover, definition, inlay,
code-action), and each feature re-implemented the same three primitives locally: walking the
tree-sitter AST, dispatching on a coordinate's `Behavior`, and building an lsp4j result from a
node's byte range. The result is a module whose *spine* is sound but whose *connective tissue* is
copy-pasted. The cost is paid on every new feature and every grammar/directive change: a node-kind
rename is a 69-site edit, a new directive carve-out is a five-site edit, and a navigation bug fixed
in one feature stays live in the ten copies next to it (two copies have already drifted into latent
NPEs).

This item is an **umbrella**: it does not introduce new LSP behaviour. Every slice is a
behaviour-preserving consolidation that removes a duplication axis and leaves one authoritative home
for a primitive the features share. The principle:

> **A primitive that every feature needs (AST navigation, behavior dispatch, result construction,
> directive policy) lives in exactly one place; features consume it, they do not re-derive it.**

The sound spine stays untouched and is the thing each slice consolidates *toward*: the `Behavior` /
`SchemaCoordinate` / `CanonicalOverlay` dispatch index with its startup invariant
(`LspVocabulary.java:62-70`), the `DeclarationKind` pattern (node-kind strings as enum constants +
lookup map + uniform traversal entry points), the freshness-aware `LspSchemaSnapshot` matching, and
the recalculate-listener seam. `DeclarationKind` already demonstrates the destination shape for the
node-kind work below; this item generalizes that instinct across the module.

## What's wrong (the duplication axes)

Each row is a primitive re-implemented per feature rather than shared. The slice that retires it is
named in the last column.

[cols="3,4,1"]
|===
| Smell | Evidence | Slice

| `childOfKind` copied verbatim into 11 classes; 2 copies (`Definitions:243`, `TypeContext:154/167`) dropped the null-guard and can NPE
| `LspVocabulary:508`, `NestedArgs:122`, `TypeContext:164`, `DeclarationKind:139`, `Diagnostics:389`, `Hovers:423`, `DeclarationHovers:327`, `ArgNameCompletions:291`, `InlayHints:308`, `SdlActions:174`, `Definitions:240`
| 1

| Other navigation helpers duplicated: `innermostObjectFieldContaining` (`Hovers:412` ≈ `Definitions:229`), `findListValue`/`listElementContaining`, `enclosingInputValueDefinition`, the `descend`/`nodeContains`/`sameNode` family
| Hovers, Definitions, InlayHints, ArgNameCompletions, NestedArgs, LspVocabulary
| 1

| Node-kind names are bare string literals (69 occurrences; `"name"` ×35, `"object_field"` ×12, `"value"` ×11) with no compile-time check
| module-wide; `Nodes` owns only `contains`/`text`/`unquote`
| 1

| The `switch(Behavior)` dispatch is re-walked as a partial subset in 4 consumers, each with its own carve-outs
| `Diagnostics.dispatch:397`, `Hovers.richerHover:163`, `Definitions.bindingDefinition:95`, the completion waterfall `GraphitronTextDocumentService:197`
| 2

| Cross-cutting directive policy is duplicated as magic strings + comments rather than carried on the model: the `"record"` R307 carve-out in 5 places; `METHOD_VALIDATING_DIRECTIVES` owned by `Diagnostics:73` but mirrored by comment in 3 consumers
| `ClassNameCompletions:36`, `Diagnostics:641/725`, `Hovers:176`, `Definitions:110`; `ExternalFieldCompletions:25`
| 2

| Two features still dispatch on directive-name string switches, inconsistent with the coordinate-driven design everywhere else
| `Definitions.compute:72`, `InlayHints.collectInferredDirectiveHints:129`
| 2

| 10 completion providers share no contract; dispatch is a 40-line manual waterfall with bespoke per-provider signatures and load-bearing ordering encoded only in a comment
| `GraphitronTextDocumentService.coordinateBasedCompletions:197`
| 3

| Verbatim provider duplication: `formatSignature` byte-identical in 2 providers; the `CompletionItem` build idiom in 8; quote-length logic in 2
| `MethodCompletions:63` = `ExternalFieldCompletions:90`; `ArgMappingCompletions:131` ≈ `CompletionContext:52`
| 3

| Each feature re-implements the byte-range → lsp4j `Range`/result conversion
| `Diagnostics.diagnostic/byteDiagnostic`, `Hovers.hover`, `DeclarationHovers.hover`, `InlayHints.makeHint`
| 4

| Test-only `compute()` overloads pollute the production API and re-parse the SDL per call via `LspVocabulary.load()`; `SdlActions` does it on every code-action request
| `Diagnostics:91`, `Hovers:51/64`, `Definitions:61`, `SdlActions:100`
| 5

| `Workspace.setBuildOutput:192` writes three `volatile` fields non-atomically; a reader can see a new snapshot against an old catalog (torn read)
| `Workspace.java:192`, consumed in `GraphitronTextDocumentService:124`
| 5

| `WorkspaceFile`'s native `Tree`/`Parser` not freed on `didClose`; `CodeActions` calls `rewrite()` 3× per match; recalc queue dedups via O(n) `List.contains`
| `Workspace.didClose`, `CodeActions:167-186`, `Workspace.enqueueTouched`
| 5

| `LspVocabulary` is 802 lines holding 4 separable concerns (overlay policy, cursor-walk, document-walk, registry/deprecation lookup)
| `LspVocabulary.java`; `CanonicalOverlay` 755-801, `DeprecationInfo`/`LspStartupException` 724-748
| 6
|===

## Slices

Each slice is behaviour-preserving and ships as its own spin-out roadmap item (with its own
Spec → Ready → Done cycle); the order below names dependency edges, not a fixed schedule. Slice 1 is
the beachhead and unblocks the rest; Slices 2-6 are largely independent once it lands. The
acceptance bar for every slice is the same: **the existing `graphitron-lsp` test suite passes
unchanged** (it is the behaviour oracle), and the slice deletes more code than it adds.

### Slice 1 (beachhead) — one navigation home

Grow `Nodes` into the single tree-sitter navigation toolkit and introduce a `GraphqlNodeKind`
constants holder (or extend `DeclarationKind`'s pattern to intra-declaration kinds). Move
`childOfKind` and the recursive containment/descent helpers there, typed on node-kind constants
rather than raw strings; delete the 11+ copies. This is the cheapest honest demonstration of the
cut: it touches every feature file, fixes the two latent NPEs in one stroke, and converts a
node-kind rename from a 69-site edit into a one-line constant change. It exercises the "shared
primitive, not re-derived" principle on the most-duplicated primitive first, so the later slices
inherit a navigation layer instead of forking one.

### Slice 2 — one behavior dispatch, policy on the model

Make the `Behavior` switch authoritative: lift the per-consumer carve-outs onto the arm or a small
`DirectivePolicy` so `"record"` and `METHOD_VALIDATING_DIRECTIVES` resolve from one place instead of
scattered literals + "mirrors …" comments (e.g. `ClassNameBinding` carries a `bindsLiveClass` flag;
a `DirectivePolicy.methodValidating(name)` predicate replaces the set). Route the jOOQ half of
`Definitions.compute` and the `InlayHints` inferred-directive dispatch through `behaviorAt` / the
overlay so all four consumers share one dispatch shape and the remaining directive-name string
switches retire.

### Slice 3 — a completion-provider contract

Introduce a `CompletionProvider` seam keyed on `Behavior` and replace the manual waterfall with a
registry (a `Map<Class<? extends Behavior>, CompletionProvider>` or a sealed switch). The shared
behavior-guard that opens nearly every provider moves into the dispatcher; the load-bearing ordering
becomes data, not a comment. Fold the verbatim `formatSignature` and the `CompletionItem`-build
idiom into a `CompletionItems` factory and share the quote-length logic on `CompletionContext`.

### Slice 4 — one result-builder

A small `LspResults`/`Ranges` helper owns the byte-range → `Range` and node → result construction
that `Diagnostics`, `Hovers`, `DeclarationHovers`, and `InlayHints` each re-implement. Optionally
consolidate the markdown rendering split between `Hovers` (catalog metadata) and
`DeclarationHovers` (classification) behind a `HoverContent` builder if it earns its keep.

### Slice 5 — API hygiene, correctness, concurrency, perf

A grab-bag of independently shippable fixes that share no new abstraction:

- Make `vocabulary` a required parameter on the feature `compute()` methods; delete the
  `LspVocabulary.load()`-calling overloads (a test helper supplies the bundled vocabulary once);
  thread `workspace.vocabulary()` into `SdlActions` so code-action requests stop re-parsing the SDL.
- Bundle `catalog` / `snapshot` / `validationReport` into one immutable `BuildOutput` record behind a
  single `volatile` reference, so `setBuildOutput` is an atomic swap and readers never see a torn
  triple.
- Give `WorkspaceFile` a `close()` (free the native `Tree`/`Parser`) and call it on `didClose`.
- Materialize `CodeActions` rewrite results once per match, then partition (drop the 3× call).
- Switch the recalc queue dedup to a `LinkedHashSet`; collapse the duplicated native-library path
  probing between `BundledLibraryLookup` and `GraphqlLanguage` (incl. the identical `addVcpkgDll`)
  into one parameterized table; remove the `ArgMapping.parseSpans` no-op alias and the stale
  `CodeActions.openUris` comment.

### Slice 6 (optional) — split `LspVocabulary`

Extract `CanonicalOverlay` (the "what binds where" policy) and `DeprecationInfo` /
`LspStartupException` into their own files, and separate the parallel cursor-walk and document-walk
traversal groups. Lowest urgency; do it only if Slices 1-2 leave it still hard to navigate.

## Relationships

- **R307** (`@record` deprecation): the source of the `"record"` carve-out Slice 2 centralizes. This
  item does not change the carve-out's behaviour, only its home.
- **Other `lsp` theme items** (`lsp-defaultorder-column-completion`, `lsp-nodetype-hover-column-scoping`,
  `lsp-diagnostic-redundant-splitquery-on-record`, `parent-context-aware-schema-coordinates`, …):
  feature work that will *consume* the consolidated primitives. Sequencing is soft, but landing
  Slice 1 first means those features add one provider/arm against a shared navigation layer rather
  than forking another copy. No hard dependency either way.
- **`intellij-lsp-plugin`** / **`mcp-server-skeleton`**: downstream surfaces of the same server; they
  benefit from the cleaner seams but are not blocked on this.

## Open questions (to settle before / during Ready)

- **Node-kind constants home**: extend `DeclarationKind` (which already centralizes declaration-level
  kinds) to cover intra-declaration kinds, or introduce a sibling `GraphqlNodeKind`? Leaning sibling,
  so `DeclarationKind` keeps its declaration-only semantics.
- **Directive policy shape** (Slice 2): flags on `Behavior` arms vs. a standalone `DirectivePolicy`
  predicate table. The arm-flag route keeps policy adjacent to dispatch but widens the records; the
  table keeps records lean but adds a second lookup. Pick during Slice 2's own Spec.
- **Slice independence**: confirm Slices 2-5 carry no hidden ordering once Slice 1 lands (expected
  yes; each touches a disjoint concern). If a real edge surfaces, file it as a `depends-on`.
- **Whether Slice 6 is worth doing at all** after 1-2; defer the decision to the end.

## Scope

In scope: behaviour-preserving consolidation of the four shared primitives (AST navigation, behavior
dispatch, result construction, directive policy), plus the API-hygiene / correctness / concurrency /
perf fixes in Slice 5, all under `graphitron-rewrite/graphitron-lsp/`. The behaviour oracle is the
existing test suite; no new LSP behaviour, no protocol-surface changes, no new completions /
diagnostics / hovers.

Out of scope: any new LSP feature; changes to the `Behavior` / `SchemaCoordinate` / `CanonicalOverlay`
dispatch *semantics* (only their callers); the legacy modules at the repo root; the IntelliJ plugin
and MCP server surfaces (separate items).

## Lineage

Surfaced 2026-06-19 from a structural code review of `graphitron-lsp` requested to ease future
maintenance. The review walked all ~7700 lines of main source and grouped the findings into the
duplication axes tabulated above; this item slices those findings into behaviour-preserving,
independently shippable consolidations.
