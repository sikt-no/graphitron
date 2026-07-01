---
id: R398
title: "SDL lint engine with ESLint-style built-in visitors"
status: In Review
bucket: feature
priority: 5
theme: lsp
depends-on: []
created: 2026-06-29
last-updated: 2026-07-01
---

# SDL lint engine with ESLint-style built-in visitors

## Implementation status (In Review)

All Scope and Acceptance-criteria items are landed; the full reactor is green
(`mvn -f graphitron-rewrite/pom.xml install -Plocal-db`). Landed:

- Typed `LintRule` (9 engine rules + 3 classifier advisories, with a `Source` axis), `LintFix`, and
  the sealed `BuildWarning` (`NoRule` / `LintFinding`); all six producers migrated; the three
  classifier advisories tagged with their `LintRule`. MCP `diagnostics` wire projects the rule id.
- The `DeprecationRecognizer` extracted into the `graphitron` build module; `LspVocabulary` delegates
  (zero behaviour change, pinned by `LspVocabularyTest` / `SdlActionDriftTest`).
- The engine (`LintEngine`, `LintVisitor`, `LintContext`, `LintNodeKind`, `LintRules`) and the nine
  syntactic visitors; one shared traversal over the consumer SDL, findings on the `BuildWarning`
  channel, wired into the three report surfaces in `GraphQLRewriteGenerator` (build / validate /
  `buildOutput`) so the LSP and MCP get them for free. Coverage meta-test asserts the declared
  partition; per-rule pipeline tests cover positive / negative / range.
- `LintFix` populated for every fix-bearing rule, computing edit ranges from graphql-java source
  locations: additive inserts (`deprecations-have-a-reason`, `types-and-fields-have-descriptions`),
  local renames (`field-names-camel-case`, `no-typename-prefix`, offered only when the field has no
  description so the name-token range is exact), and the two classifier safe-deletion advisories (bare
  form only). Pipeline tests pin every edit range and the no-fix rules.
- The LSP finding-keyed `QuickFix` branch (`LintQuickFixes`) alongside the detector-driven
  `SdlActions` path: it projects the build-side `LintFix` off the `ValidationReport`, respects the
  R139 freshness silence, and its applied `WorkspaceEdit` yields the corrected SDL (LSP-tier tests).
- `@record` aligned with the deprecation convention (docstring `@deprecated` marker). Deprecation
  comments and quick-fix actions are decoupled (settled with the user): a deprecation may carry no
  registered fix, and a quick fix is registered explicitly, never divined from prose. So `@record`
  carries no migration action (its removal is offered contextually by the redundant-record advisory's
  build-side fix), the `MANUAL_MIGRATION_DEPRECATIONS` allow-list and the drift test's
  every-deprecation-must-be-covered invariant are removed, and `SdlActionDriftTest` keeps only the
  stale-action guard plus the canonical deprecated-set pin.
- Integration gates: an engine finding reaches the `ValidationReport` through `buildOutput()` with its
  fix (`BuildOutputReportPipelineTest`), and an engine finding replays into a `Warning` squiggle,
  silenced on a stale snapshot (`ValidatorDiagnosticsTest`).

Implementer's note for the reviewer: fixes are registered explicitly, never derived from a
deprecation's prose reason (settled with the user: prose-parsing is fragile). Consequently
`no-deprecated-directive-usage` reports without a fix; the message points at the directive's
description, and an explicitly registered successor fix can follow later. Three of the spec's fix
targets also narrowed to the provably-safe subset graphql-java permits, within the spec's "a fix is
offered only when applying it is safe":

- The `@index` to `@order` swap is not shipped as a lint fix: it could only be produced by parsing the
  docstring's "use @order(index:)" prose, which is the divining the user ruled out. It can return as
  an explicitly registered fix.
- The `name:` to `className:` swap is deferred: graphql-java returns a null source location for
  `ObjectField`, so a directive-argument input-field rename cannot be safely ranged build-side. That
  exact migration already ships as R93's tree-sitter `SdlAction`.
- The field renames supply a fix only for undescribed fields (a described node's graphql-java location
  is the description, not the name token).
- The `@record` deletion is offered only for the bare form (graphql-java gives no end location to span
  `@record(record: {...})`); `@splitQuery`, argument-less, is always deletable.

## Problem

Graphitron validates its own directive coordinates against the jOOQ catalog and re-derives a handful of build-tier warnings as LSP squiggles (`Diagnostics.java`), but there is no general lint-rule engine. Every diagnostic is hand-wired into `Diagnostics.compute`, and consumers cannot enforce their own SDL conventions (naming, required directive pairings, banned shapes). The dispatch is already visitor-shaped (one walk over the directive nodes, dispatch on the coordinate `Behavior` arm), yet it is bespoke per rule rather than a registry of independent rules.

## Decision (settled with the user)

Adopt the ESLint execution model: parse the SDL once into the typed AST, run a single shared traversal, and dispatch each node to the visitors that subscribed to its kind/coordinate; each visitor reports problems carrying a source range, message, and severity. Rules are independent visitors registered against the engine, not branches inside one method. A rule may optionally supply a suggested fix the user can accept in the editor; the fix capability is available to every rule but required of none.

Ship a useful set of **built-in** visitors first. The plugin model (consumer-supplied custom visitor functions wired into the same registry) is deliberately deferred to a follow-on item until the built-in set has proven the engine and shipped value. This mirrors the project's own R3 then R121 discipline: do not build the extensibility abstraction before a real population of rules exists to justify its shape.

## Architecture

Build-side evaluation is the spine. The authoritative tier is the build, where graphitron already parses with graphql-java and runs its classifier. The GraphQL-native idiom (graphql-schema-linter) is exactly this: it reuses graphql-js's own `validate()` / `visitInParallel` / `TypeInfo` to run all rule visitors in one pass, and reports via `GraphQLError(node)` so locations fall out of the node's source position. Graphitron's analog is direct:

- graphql-java `Parser` / `Document` and `NodeTraverser` / `NodeVisitor` for the one-pass traversal.
- The classifier plus jOOQ catalog as a *richer* `TypeInfo`: it knows table/column/FK resolution, not just GraphQL types (available to any future schema-aware engine rule; the classification advisories already live inside the classifier, and the v1 engine visitors are syntactic and need only the AST).
- Output rides the existing warning channel, not a new parallel carrier. `BuildWarning` is today a flat `record(String message, SourceLocation location)`; this item seals it (see Rule and finding contract) into a no-rule arm (the pre-existing untagged warnings) and a lint-finding arm carrying a typed `LintRule` plus an `Optional<LintFix>`, so a finding's rule is a type and its fix lives only on the arm where it is meaningful, not behind a nullable field. The `ValidationError` / `Rejection` error channel stays sealed and untouched; v1 lint findings are warnings only (see Severity and enablement).

The LSP gets parity for free. Diagnostics already replays the build's `ValidationReport` into squiggles (`Diagnostics.validatorDiagnostics`, with the freshness-aware silence policy of R139). Emitting custom-rule findings into the same report means a rule fires in CI and in the editor from one definition, with no second evaluator and no drift seam.

The MCP server is a third consumer, also for free. `GraphitronMcpServer` holds the very same `Workspace` the LSP holds (wired once by `DevMojo.bindServer`) and already exposes a `diagnostics` tool that projects `workspace.validationReport().errors()` and `.warnings()` to agents. Because lint findings ride `ValidationReport` as warnings, they surface through that existing tool the moment they are emitted, with no new plumbing. The only addition is carrying the typed `LintRule` id onto the MCP wire mapping so an agent sees which rule fired, not just the message text (the sealed `BuildWarning` arms land in the `graphitron` module; only the wire projection of the lint-finding arm's `LintRule` is MCP-local). This is the build-evaluates-everyone-projects spine paying off: one emission reaches CI, the editor, and an MCP-aware agent. A dedicated lint-filtered tool or fix exposure can follow later; the data is reachable on the shared seam without it.

Decided: the v1 engine evaluates **build-side over the graphql-java AST**, and the LSP projects findings through the existing `ValidationReport` replay. The tree-sitter-shared-engine alternative (moving the tree-sitter substrate below the LSP for a second evaluator) is deferred; build-side graphql-java is the lower-risk single evaluator and the GraphQL-native idiom.

This splits the built-in rules by what they need to see, and the split determines *where each rule lives*:

- **Syntactic rules** (naming, descriptions, deprecation-marker usage, type-name prefix) are re-derivable from the AST alone. These are the engine's actual new visitors; they own no classification logic.
- **Classification-derived advisories** (whether a parent is record-backed, whether a backing class is inferred, whether an `@asConnection` filter is same-table PK-IN) are *verdicts the classifier already computes*, fired today as `BuildWarning`s from inside `FieldBuilder` / `TypeBuilder`. The engine does **not** re-evaluate these: `warnIfSplitQueryOnRecordParent` alone fires from four distinct classification sites gated on different resolved outcomes, so re-deriving the condition in a second AST walk is the same-predicate-two-consumers drift the Generation-thinking principle warns against. The classifier stays their single emitter; this item surfaces them through the same report channel into the LSP and tags them with a typed rule id. No condition is emitted twice.

## Rule and finding contract

Pinned at Spec so the implementer builds against a typed shape, not a string bag:

- **Rule identity is a type.** A `LintRule` enum enumerates the built-in rules; each constant carries its stable kebab-case id. It is an enum, not a free string; the plugin follow-on can widen it to a sealed interface when external rules arrive. A finding names its `LintRule`, never a bare id string. v1 severity is uniformly warning (see Severity and enablement), so the enum carries no per-rule severity field yet; per-rule severity arrives with the configurability follow-on.
- **The visitor shape** mirrors the ESLint / graphql-schema-linter contract: a visitor declares its `LintRule` and subscribes to graphql-java AST node kinds (the `create(context)`-returning-node-kind-listeners shape), receiving a context that exposes the parsed `Document` (and, for any future schema-aware rule, the classifier projection) plus a `report(node, message)` sink. Method bodies are implementation; the contract is the visitor interface plus the registry.
- **Findings ride the warning channel as a sealed shape.** `BuildWarning` becomes a sealed type with two arms: a no-rule arm (the pre-existing untagged warnings, for example the one `EntityResolutionBuilder` emits) and a lint-finding arm carrying the typed `LintRule` plus an `Optional<LintFix>`. This is deliberately not a nullable `LintRule` field with an only-meaningful-when-non-null `LintFix` beside it, which would be a variant-by-nullity encoding of a real two-arm split; the sealed shape is what the project's sub-taxonomies-for-outcomes principle wants. No `(ruleId-string, message, severity)` parallel carrier is introduced. Both arms flow into `ValidationReport` exactly as today's warnings do, so the LSP replay (`Diagnostics.validatorDiagnostics`) surfaces them with no new transport. Sealing migrates every existing `BuildWarning` construction site to the no-rule arm (the three classification advisories instead become lint-finding arms carrying their `LintRule`); the migration is mechanical and lands with this item.
- **One registry over a declared partition.** A `LintRules` registry pairs every `LintRule` with its visitor; the engine's single traversal dispatches each node to the subscribed visitors. Alongside the rule-to-visitor map the engine declares the explicit set of graphql-java node kinds it deliberately does not lint, so completeness is asserted over a declared partition (every encounterable kind is subscribed or declared-not-linted) rather than trusting a silent `default`, the way `GeneratorCoverageTest` reads its partition. The coverage test (see Testing strategy) mirrors the `VariantCoverageTest` / R374 `EdgeCoverageTest` no-silent-default pattern and R347's `Behavior`-keyed provider registry.
- **Optional suggested fix.** A rule may populate the lint-finding arm's `Optional<LintFix>`: a typed, ordered list of edits, each a source range (`SourceLocation` start and end) plus replacement text. It is optional (most rules leave it empty) and is a *suggestion*, not a build-time mutation, the build never rewrites the consumer's SDL. The fix rides on the finding through the same channel; the LSP turns a fix-bearing finding into a `QuickFix` `CodeAction` (a `WorkspaceEdit` of `TextEdit`s) keyed on the finding's diagnostic. This is a new finding-keyed code-action branch, not a reuse of the existing dispatch: `CodeActions.compute` today is detector-driven (it re-scans each open document through the action's detector and does not read the request's diagnostics), so the lint path adds a branch alongside the detector-driven `SdlActions` path and shares only the `WorkspaceEdit` / `TextEdit` / `QuickFix` emit primitives (the same ones serving the R93 quick-fixes). The rule owns its fix; the LSP only projects it, with no LSP-side recompute of how to fix a given rule, keeping the build the single evaluator.

## Severity and enablement (v1)

- **Warnings only.** Every built-in finding is a warning in v1. It maps to `BuildWarning` (a non-fatal advisory that never fails the build) and projects to `DiagnosticSeverity.Warning` in the LSP, the severity that channel already hardcodes. No lint finding is an error in v1, so `severityOf` needs no lint arm and the error channel is untouched.
- **No per-rule configuration in v1.** Built-in rules are all on; there is no consumer-facing disable or severity override yet. The earlier `input-object-name-suffix` and `redundant-record-directive` "off-by-default" hedges are dropped: both ship on as warnings.
- **Deferred to the configurability follow-on:** per-rule enable/disable, severity overrides, and any error-capable lint (which would need the typed finding to carry a severity axis and a `severityOf` lint arm). The motivation's stronger form, a convention that *fails CI*, lives there; v1 deliberately delivers advisories and the engine they ride on, not enforcement.
- **Fixes are user-accepted suggestions.** A suggested fix is offered as an editor QuickFix the user chooses to apply; it is never auto-applied at build time and there is no bulk apply-all. A fix is offered only when applying it is safe within the SDL (see Suggested fixes under Starter rule set); a rule whose only correct fix would ripple to references it cannot see supplies no fix in v1.

## Scope

In scope:

- A lint engine over the build's graphql-java AST: the `LintRule` enum, the visitor contract, the `LintRules` registry, and a single shared traversal that dispatches each node to its subscribed visitors.
- The nine **syntactic** built-in visitors listed under Starter rule set (the engine's new rules), each emitting a typed `BuildWarning`.
- Surfacing the three existing **classification-derived advisories** through the same report channel into the LSP, and tagging them with a typed `LintRule`, without re-deriving their conditions (the classifier stays their sole emitter).
- The sealed `BuildWarning` (no-rule arm vs lint-finding arm carrying `LintRule` + `Optional<LintFix>`), and the `LintRuleRegistryCoverageTest` drift guard.
- The optional per-rule suggested-fix mechanism: a typed `LintFix` on the finding, projected to a `QuickFix` `CodeAction` by a new finding-keyed code-action branch (alongside the detector-driven `SdlActions` dispatch, sharing only its `WorkspaceEdit` / `TextEdit` emit primitives), for the v1 fix-bearing rules listed under Starter rule set.
- Projecting the typed `LintRule` id onto the MCP `diagnostics` wire mapping by pattern-matching the lint-finding arm of the sealed `BuildWarning` (the no-rule arm carries no id, so there is no nullable field to guard; the arms land in `graphitron` per the bullet above, only this projection is in `graphitron-mcp`). `GraphitronMcpServer` already surfaces `ValidationReport` warnings via that tool over the shared `Workspace`, so an MCP-aware agent sees which rule fired with no new tool or seam; findings reach the MCP for free.

Out of scope (deferred):

- Re-implementing classification verdicts as engine visitors. Verdicts stay in the classifier; the engine never recomputes them.
- Reference-aware rename refactoring (the coordinated multi-site edit a type rename needs). The rename-class rules ship no fix in v1; a workspace-wide rename is its own follow-on.
- Consumer-supplied custom visitor functions (the plugin SPI), per-rule enable/disable, severity overrides, and error-capable lint. These are the configurability follow-on; the engine should be shaped so wiring external visitors in later is additive, but no public extension API is committed here.
- A declarative rule-config DSL (Spectral-style selector plus closed predicate library). Noted as prior art; not part of this item.
- A second tree-sitter evaluator below the LSP, and any new protocol surface. v1 evaluates build-side and projects.

## Starter rule set (first iteration)

The first iteration delivers nine new engine visitors and surfaces three existing classifier advisories, so a consumer sees about a dozen lint findings out of the gate. The set is curated from the cross-vendor GraphQL-SDL-linter consensus (graphql-schema-linter, graphql-eslint, Apollo GraphOS, WunderGraph Cosmo) plus graphitron's directive conventions.

Curation principles (why these and not others):

- **Do not duplicate what graphql-java already enforces.** Graphitron parses with graphql-java, whose schema validation already hard-errors the type-system-correctness and uniqueness family (`known-type-names`, `known-directives`, `known-argument-names`, `provided-required-arguments`, `unique-*`, `lone-schema-definition`, `possible-type-extension`). These are parse-time errors we get for free, not lint rules. Excluded.
- **Do not duplicate graphitron's existing hard rejections.** Directive conflicts (mutually-exclusive directive groups), removed directives (`@notGenerated`, `@multitableReference`, `@lookupKey` on inputs), `@scalarType` on a spec built-in, and the UPSERT / `multiRow` refusals are already `Rejection` arms that fail the build. Lint rules must not restate them. Excluded.
- **Adopt the style/convention consensus** that no parser enforces: naming, descriptions, deprecation hygiene, orphan types.
- **Surface, do not re-derive, graphitron's existing classification advisories.** The `@splitQuery`-on-record, redundant-`@record`, and same-table-`@asConnection` conditions are classifier verdicts already emitted as `BuildWarning`s; the engine routes them to the LSP and tags them with a typed `LintRule`, but the classifier remains their sole emitter (no double-emit, no re-walk).

Engine visitors (new; syntactic; evaluated build-side over the graphql-java AST and projected to the LSP):

1. **type-names-pascal-case**: object, interface, union, enum, input, and scalar type names are PascalCase. Severity warning. Consensus: graphql-schema-linter `types-are-capitalized`, Apollo `TYPE_NAMES_SHOULD_BE_PASCAL_CASE`, Cosmo.
2. **field-names-camel-case**: object and interface field names are camelCase. The single most agreed-upon GraphQL rule, and safe in graphitron because the SDL field name is decoupled from the column via `@field(name:)`. Severity warning. Consensus: all four tools.
3. **input-and-argument-names-camel-case**: input-object field names and field-argument names are camelCase. Severity warning. Consensus: graphql-schema-linter, Apollo.
4. **enum-values-screaming-snake-case**: enum value names are UPPER_SNAKE_CASE, independent of any `@order` / `@enum` directive on the value. Severity warning. Consensus: all four tools.
5. **deprecations-have-a-reason**: every `@deprecated` carries a non-empty `reason`. Universal hygiene rule; complements graphitron's existing deprecation doc-coverage culture. Severity warning. Consensus: graphql-schema-linter, graphql-eslint, Apollo, Cosmo.
6. **types-and-fields-have-descriptions**: type definitions and their fields carry descriptions, with configurable scope to keep noise down (default: types plus root-operation fields; opt-in to all fields). Severity warning (advisory, not error). Recurring: graphql-schema-linter's `*-have-descriptions` family, Apollo `ALL_ELEMENTS_REQUIRE_DESCRIPTION`, Cosmo.
7. **input-object-name-suffix**: input object type names end in `Input`. Severity warning. Provenance: Apollo `INPUT_TYPE_SUFFIX`, Cosmo. Opinionated, but v1 has no off switch so it ships on; a future config can make it opt-out.
8. **no-deprecated-directive-usage**: a deprecated graphitron directive or directive argument is used in the consumer SDL. Deprecation is recognized through graphitron's established `@deprecated`-marker convention, which already unifies the two forms GraphQL forces apart (the native `@deprecated(reason:)` directive is valid on field / argument / input-field / enum-value, but not on a directive *definition*): the native marker on an argument or input field (`@asConnection(connectionName:)` at `directives.graphqls:237`, `ExternalCodeReference.name` at `:309`, use `className`), and a docstring `@deprecated` token in a directive definition's description for the whole-directive cases (`@index`, description at `:243`, use `@order`). `LspVocabulary.deprecationOf` / `deprecatedCoordinates()` implement exactly this today: the `DESCRIPTION_DEPRECATED_TOKEN` pattern plus the native-marker read, carried as the typed `DeprecationInfo` with `Shape{NATIVE, DOCSTRING}`, with `SdlActionDriftTest` pinning `SdlActions` against the resulting set. No curated hardcoded list. Because the engine runs build-side and `LspVocabulary` sits above it (graphitron-lsp depends on graphitron, not the reverse), the recognizer cannot be consumed in place: `DESCRIPTION_DEPRECATED_TOKEN`, `DeprecationInfo`, and the `SchemaCoordinate`-keyed `deprecatedCoordinates()` all live in `graphitron-lsp/parsing/` today. The pure graphql-java recognizer (the regex, the native-marker read, and `DeprecationInfo`, all over graphql-java `DirectiveDefinition` / `InputValueDefinition`) is extracted down into the `graphitron` build module where visitor 8 consumes it; `LspVocabulary` then delegates to it and keeps its `SchemaCoordinate` adapter LSP-side (`SchemaCoordinate` does not move). This is a coordinated move: `SdlActionDriftTest` pins `deprecatedCoordinates()` exactly (line 55), so the extraction, `LspVocabulary`'s delegation, and the drift-test update land in one change, the same lands-together-not-before sequencing the `@record`-marker edit gets. Usage detection is then an AST walk over the consumer SDL. Visitor 8 yields for any coordinate a classification advisory already emits on (today `@record`, owned by the redundant-record advisory below), so each coordinate is warned exactly once. Severity warning. Subsumes the retired R296.
9. **no-typename-prefix**: an object or interface field name must not be prefixed with its enclosing type's name (for example `User.userName` should be `User.name`). Auto-fixable; purely syntactic. Severity warning. Provenance: graphql-eslint `no-typename-prefix`. This replaced an earlier `nodeid-target-is-node-type` candidate, which `NodeIdLeafResolver.resolve` already hard-rejects (a `@nodeId(typeName:)` target that does not exist, is not `@table`-annotated, or has no resolvable `@node` identity for its backing table is a `Rejection.structural` at classification), so a lint rule there would restate a hard error.

Classification-sourced advisories (classifier-owned; surfaced and tagged here, not re-derived; severity warning):

- **splitquery-redundant-on-record-parent**: `@splitQuery` on a field whose enclosing type is record-backed, where the record handoff already opens a new scope. Emitted by `FieldBuilder.warnIfSplitQueryOnRecordParent` (four classification sites). Subsumes the retired R121; this item adds the edit-time surface R121 wanted.
- **redundant-record-directive**: `@record` on a type that also carries `@table`, whose backing class graphitron already infers, or whose declared backing class disagrees with the inferred one. Emitted by `TypeBuilder.emitDirectiveIgnoredWarning` (`TypeBuilder.java:578`, three arms: shadowed-by-`@table`, matches-inferred, disagrees-with-inferred). This advisory owns the `@record` signal; visitor 8 excludes `@record` so the coordinate is warned exactly once.
- **asconnection-same-table-pk-in**: `@asConnection` on a same-table PK-IN filter with required `@nodeId` arguments, which forces full-table pagination instead of keyed-range pagination. Emitted by `FieldBuilder.warnAsConnectionSameTable`.

Aligning `@record` with the deprecation convention. `@record`'s description does not yet carry the docstring `@deprecated` marker `@index` uses (`directives.graphqls:243`); its deprecation lives in advisory prose only. The implementation adds the marker so the convention is uniform and `@record` reads as deprecated everywhere it is surfaced (hover, `deprecatedCoordinates()`), independent of visitor 8, which excludes `@record` regardless. This is a coordinated edit, not a lone description tweak: marking `@record` adds it to `LspVocabulary.deprecatedCoordinates()`, and `SdlActionDriftTest` both pins that set exactly (line 55) and requires every deprecated coordinate to carry a migration action or a `MANUAL_MIGRATION_DEPRECATIONS` allow-list entry (lines 88-95). So the same change updates that expected set and gives `@record` a `remove @record` migration, the same deletion the redundant-record advisory already suggests as its fix. Done piecemeal it reds the build, so it lands with the visitor work, not before.

Suggested fixes (v1). A fix is offered only where the edit is safe within the SDL document and the user opts in:

- **Additive fixes** (insert text, change nothing existing): `deprecations-have-a-reason` (insert a `reason: "..."` placeholder) and `types-and-fields-have-descriptions` (insert a description placeholder).
- **Local replacements** (rewrite one token or directive, no SDL reference affected): `no-deprecated-directive-usage` (swap `@index` to `@order`, and `name:` to `className:`) and the field renames `no-typename-prefix` and `field-names-camel-case` (a field name is not referenced elsewhere in the SDL, so the edit is document-local; offered as a suggestion the user accepts).
- **No v1 fix** (the correct fix ripples to references the rule cannot rewrite alone, or needs confirmation first): `type-names-pascal-case` and `input-object-name-suffix` (a type rename touches every SDL reference) and `enum-values-screaming-snake-case` (an enum-value rename can hit SDL default values). `input-and-argument-names-camel-case` also defaults to no fix: an argument or input-field rename is document-local in the SDL text, but a renamed argument can still be referenced by a default value or a directive-arg coordinate, so it stays no-fix unless the implementer confirms those references are out of SDL scope. These await the reference-aware rename follow-on (or, for the last, that confirmation); until then they report without a fix.

The two classification advisories whose directive is ignored anyway, `redundant-record-directive` (remove the ignored `@record`) and `splitquery-redundant-on-record-parent` (remove the redundant `@splitQuery`), can carry a safe deletion fix. Because they are classifier-emitted, the classifier emits the lint-finding arm carrying the `LintFix` directly; the engine does not re-derive them.

Deliberately deferred rule candidates (not in iteration one): rules gated on unfinished features (UPSERT, `@mutation` UPDATE `multiRow`, composite-NodeId reference, polymorphic `@service` returns); alphabetical-ordering rules (opinionated, high churn, revisit once the engine is proven); and Relay-connection-conformance rules (graphitron synthesizes Connection / Edge / PageInfo via `@asConnection`, so that shape is generator-controlled, not author-authored; a rule there would police generated output, not author input).

## Testing strategy

The behaviour oracle is the finding set a rule produces over a fixture, asserted on the typed finding and its range. Code-string / generated-body assertions are banned at every tier (per the design principles); assert on the typed `LintRule` and the `SourceLocation`, not on substring-matching rendered diagnostic text beyond the minimum identity check.

- **Per-rule, pipeline tier** (graphitron module: SDL fixture in, `ValidationReport` findings out). Each engine visitor gets three cases: positive (non-compliant SDL yields exactly one finding naming the right `LintRule`), negative (compliant SDL yields none), and range (the finding's `SourceLocation` points at the offending node, for example the type name or the directive node, not the whole definition). This is the primary tier.
- **LSP-projection parity, LSP module tier.** One test pins the "parity for free" invariant: a build-side finding replays into a squiggle with the same range, at `Warning` severity, and respects the R139 freshness-silence policy on a stale snapshot. `ValidatorDiagnosticsTest` is the template.
- **MCP-projection access, MCP module tier.** One `graphitron-mcp` handler test pins the "reachable for free" invariant the same way: a lint finding emitted into `ValidationReport` surfaces through the existing `diagnostics` tool over the shared `Workspace`, carrying its typed `LintRule` id on the wire. Without this test the MCP-access claim is prose, not behaviour. `GraphitronMcpServerTest` is the template.
- **Advisory tagging and single-emit, pipeline tier.** For the three classification advisories, a test pins that the existing classifier warning now carries the correct typed `LintRule`, reaches `ValidationReport`, and is emitted exactly once (the no-double-emit guard, given the engine does not re-derive them).
- **Registry coverage, meta-test tier.** `LintRuleRegistryCoverageTest` asserts every `LintRule` enum constant is registered to exactly one visitor (no orphan rule, none registered twice), and reads the engine's declared partition: every graphql-java node kind it can encounter is either subscribed by a rule or in the declared not-linted set, no silent skip. Mirrors `VariantCoverageTest` / `EdgeCoverageTest`.
- **Message constants.** Each rule's message is a named constant pinned by a test, so wording is single-sourced; this is the guard the absorbed R121 marker-constant note anticipated.
- **Fixes, both tiers.** For each fix-bearing rule, a pipeline-tier test asserts the produced `LintFix` (edit ranges and replacement text) on a non-compliant fixture; an LSP-tier test asserts the `QuickFix` `CodeAction` is offered for the finding's diagnostic and that applying its `WorkspaceEdit` yields the expected corrected SDL. A rule that supplies no fix asserts none is offered.

## Acceptance criteria

- The engine parses the SDL once and dispatches to registered visitors in a single traversal; adding a visitor is registering it, not editing a central switch.
- The nine syntactic visitors each emit a typed `BuildWarning` (typed `LintRule`, correct range) on non-compliant SDL and nothing on compliant SDL; all are warnings.
- The three classification advisories surface in the LSP carrying their typed `LintRule`, emitted once each, with the classifier still their sole producer.
- `LintRuleRegistryCoverageTest` passes: every `LintRule` registered exactly once, every node kind has a known dispatch status, no silent skip.
- One LSP-projection parity test passes, including the R139 stale-snapshot silence.
- Fix-bearing rules attach a valid `LintFix`; the LSP offers it as a `QuickFix` whose applied `WorkspaceEdit` produces the corrected SDL. Rules without a fix offer none, and no fix is auto-applied at build time.
- The MCP `diagnostics` tool surfaces lint findings (they ride `ValidationReport` warnings) carrying their typed `LintRule` id, verified by a `graphitron-mcp` handler test.
- No code-string / body assertions in any test; findings asserted on the typed shape and range.
- Full reactor green: `mvn -f graphitron-rewrite/pom.xml install -Plocal-db`.

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
- **R121** (redundant `@splitQuery` on `@record`): subsumed and retired (file deleted, superseded by this item). Its condition is a classifier verdict already emitted by `FieldBuilder.warnIfSplitQueryOnRecordParent`, so R398 surfaces and tags that existing warning in the LSP (the edit-time surface R121 wanted) rather than re-implementing it as a visitor.
- **R296** (deprecated-directive usage): subsumed and retired (file deleted, superseded by this item) as engine visitor 8 (`no-deprecated-directive-usage`), which is genuinely syntactic.
- **R345** (schema parse-failure squiggle): adjacent diagnostic, not part of the rule engine (it is a freshness-policy carve-out); stays independent.
- **R139** freshness-aware silence policy governs how LSP-projected findings behave on stale snapshots; built-in visitors must place themselves relative to it.

## Decisions resolved at Spec

The forks this item existed to settle are decided above: build-side graphql-java evaluation with LSP projection (not a second tree-sitter evaluator); a typed `LintRule` and a sealed `BuildWarning` (no-rule arm vs lint-finding arm carrying `LintRule` + `Optional<LintFix>`), not a nullable field or a stringly carrier; warnings-only with no per-rule config in v1; the engine hosts only syntactic rules while the classification advisories stay classifier-owned and are surfaced, not re-derived; and a rule may optionally supply a user-accepted suggested fix, surfaced as an editor QuickFix via a new finding-keyed code-action branch.

Left to the implementer or the Ready reviewer (judgment, not open design):

- The node-kind subscription granularity of the registry (per-kind callbacks vs a coordinate key), to land consistently with R347's provider-registry shape.
- The deferred extensibility surface (code visitors vs also a declarative config layer) is the configurability follow-on's decision, not this one's.
