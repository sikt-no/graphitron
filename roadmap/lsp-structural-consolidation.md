---
id: R347
title: "Consolidate graphitron-lsp navigation, dispatch, and result-building"
status: In Progress
bucket: architecture
priority: 5
theme: lsp
depends-on: []
created: 2026-06-19
last-updated: 2026-07-01
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

| Two features still dispatch on directive-name string switches, inconsistent with the coordinate-driven design everywhere else. `Definitions` retired in Slice 2; `InlayHints` moved to Slice 3 (it keys on `InferredDirectiveArgs.Entry`, not a coordinate `Behavior`, so it needs the provider-registry shape, not `behaviorAt`)
| `Definitions.compute:72`, `InlayHints.collectInferredDirectiveHints:129`
| 2,3

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

Each slice is behaviour-preserving; the order below names dependency edges, not a fixed schedule.
The slices land as sequential **phases of this item** (R347 carries `In Progress` until the last
worthwhile slice ships); each phase's section collapses to a one-line "shipped" note as it lands.
Slice 1 is the beachhead and unblocks the rest; Slices 2-6 are largely independent once it lands.
The acceptance bar for every slice is the same: **the existing `graphitron-lsp` test suite passes
unchanged** (it is the behaviour oracle), and the slice reduces duplication (deleting more than it
adds, beachhead infrastructure aside).

### Slice 1 (beachhead) — one navigation home — SHIPPED

Shipped: introduced `GraphqlNodeKind` (sibling to `DeclarationKind`, settling the open question in
favour of a sibling so `DeclarationKind` keeps its declaration-only semantics) and grew `Nodes` into
the single tree-sitter navigation toolkit (`childOfKind`, `nodeContains`, `sameNode`,
`innermostObjectFieldContaining`). Deleted the 12 verbatim `childOfKind` copies, the 3
`innermostObjectFieldContaining` near-duplicates, the 2 `nodeContains` copies and the stray
`sameNode` / `findInnermostObjectField`; routed every structural node-kind comparison
(`name`/`value`/`object_field`/… ) through `GraphqlNodeKind.matches(...)`.

Learnings vs. the original sketch:

- The latent-NPE count was **three**, not two: `Definitions:240` and `TypeContext:164` (the two
  named in the table) plus an unguarded `argNode.getType()` in `TypeContext.stringArg`. All three
  are fixed by the null-safe shared `childOfKind` / `matches`. `NodesTest` pins the null-safety
  contract as the explicit regression oracle.
- `GraphqlNodeKind` covers structural / intra-declaration kinds only. The lone declaration-kind
  literal (`"scalar_type_definition"` in `ScalarTypeCompletions`) was left for the
  `DeclarationKind`-consumer cleanup rather than blurring the sibling split; the `case "field"` /
  `case "table"` directive-name switches stay for Slice 2.
- Line accounting: tracked deletions exceed additions by 36 lines; including the new
  `GraphqlNodeKind` the main source is roughly net-neutral (+17), with the duplication axis itself
  collapsed (12 → 1, 3 → 1, 2 → 1).

### Slice 2 — one behavior dispatch, policy on the model — SHIPPED

Shipped: introduced `DirectivePolicy` (a string-keyed `final class` in `parsing`, sibling to
`Behavior`; the user settled the fork in favour of a standalone table over arm-flags on `Behavior`,
since the carve-outs key on directive *name* where a single coordinate is shared across directives).
Two predicates, `bindsLiveClass(name)` (false only for `@record`, R307) and `bindsLiveMethod(name)`
(the former `METHOD_VALIDATING_DIRECTIVES` set), now own the two constant sets; the five copy-pasted
`"record".equals(...)` carve-outs (`ClassNameCompletions`, `Diagnostics` ×2, `Hovers`, `Definitions`)
and the privately-owned method-validating set route through them. `DirectivePolicyTest` pins the
contract. Collapsed `Definitions.compute` + `bindingDefinition` into one coordinate-driven dispatch
(`locateAt` → `behaviorAt` → exhaustive `Behavior` switch, no `default`), so `Definitions` joins
`Diagnostics` / `Hovers` on the shared dispatch shape and its `case "table"/"field"/"reference"`
directive-name switch retires; the jOOQ-half helpers now read the cursor's leaf value. The
exhaustive switch closes the latent gap where the old `default -> Optional.empty()` silently dropped
the three catalog bindings.

Learnings / scope vs. the sketch:

- **Behaviour delta (beneficial, called out):** because `Definitions` now dispatches on the resolved
  coordinate rather than the directive name, a class binding nested inside a jOOQ directive (e.g.
  `condition.className` inside `@reference(path:)`) now resolves goto-definition through the service
  half instead of being silently ignored. Net-additive; the existing 419-test oracle stays green.
- **InlayHints switch deferred to Slice 3, not dropped.** The `switch(entry.directiveName())` in
  `InlayHints.collectInferredDirectiveHints` keys on `InferredDirectiveArgs.Entry`, not a coordinate
  `Behavior`, so the spec's "route through `behaviorAt`" mechanism does not apply. The clean
  retirement (a present-arm strategy mirroring the existing sealed `AbsentArm`) cannot live on the
  catalog `Entry`: `graphitron` cannot depend on `graphitron-lsp`, yet the present renderers need
  LSP-only context (`WorkspaceFile`, `TypeContext`, the built snapshot). The only compile-safe home
  is an LSP-side renderer registry asserted complete by test, which is exactly Slice 3's
  provider-contract shape; moved there.

### Slice 3 — a completion-provider contract — SHIPPED

Shipped: introduced the `CompletionProvider` functional seam over a shared `CompletionRequest` (the
union of the ten providers' bespoke argument tuples), and a `Completions` dispatcher whose
`providersFor(Behavior)` is an exhaustive sealed switch over `Behavior`, replacing the 40-line
hand-ordered completion waterfall that lived in `GraphitronTextDocumentService`. The load-bearing
ordering (`@externalField`'s narrowed method list ahead of the generic one) is now the list order in
that switch, not a comment, and a new `Behavior` arm is a compile error in the switch until it names
its provider(s). Folded the byte-identical `formatSignature` (two copies) and the
`new CompletionItem` + `setKind` + `setTextEdit` idiom (ten copies) into a `CompletionItems` factory,
and hoisted the triple-vs-single quote-delimiter logic to one `CompletionContext.openingQuoteLength`
shared by the range builder and `ArgMappingCompletions`.

Retired `InlayHints.collectInferredDirectiveHints`'s `switch(entry.directiveName())` (moved here from
Slice 2) for a `Map<String, InferredDirectiveRenderer>` registry keyed by directive name; the old
`default -> {}` that silently dropped an unrendered `InferredDirectiveArgs.Entry` is gone, and
`InlayHintRendererCoverageTest` now fails the build when an entry has no renderer, the LSP-side mirror
of the catalog's sealed `AbsentArm`.

Learnings / scope vs. the sketch:

- **Behavior guards stayed in the providers, they did not "move into the dispatcher."** The dispatcher
  owns provider *selection* by arm (the sealed switch), but each provider keeps its
  `behaviorAt(coordinate) instanceof …` guard. The deciding constraint was the behaviour oracle: the
  per-provider unit tests call each `generate(...)` directly and pin the guard (e.g.
  `ClassNameCompletionsTest.referencePathTopLevelClassNameDoesNotComplete` asserts emptiness that is
  *the guard's* doing, not the dispatcher's). Moving the guard out would force re-leveling those
  negative tests to dispatcher-level tests, a behaviour-risking change disproportionate to the win;
  the retained guard is now a cheap confirm of the arm the switch already selected, and keeps each
  provider independently unit-testable. The registry adapts the shared `CompletionRequest` to each
  provider's unchanged `generate(...)` signature via a lambda, so the ten provider APIs and their
  tests are untouched.

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
- **Directive policy shape** (Slice 2): SETTLED in favour of a standalone `DirectivePolicy`
  predicate table over arm-flags on `Behavior`. The deciding reason was not record width but axis
  orthogonality: the carve-outs key on directive *name* where a single `Behavior` coordinate is
  shared across directives (`ExternalCodeReference.className` under both `@record` and `@enum`), so
  the policy genuinely cannot hang off the arm.
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
