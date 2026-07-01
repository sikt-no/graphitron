---
id: R408
title: "Lint finding suppression mechanism"
status: Spec
bucket: feature
priority: 6
theme: lsp
depends-on: []
created: 2026-07-01
last-updated: 2026-07-01
---

# Lint finding suppression mechanism

## Problem

The R398 lint engine ships every built-in rule on, as a warning, with no consumer-facing way
to turn any of it off. R398 deliberately deferred this: its Severity-and-enablement section
states "No per-rule configuration in v1 ... there is no consumer-facing disable or severity
override yet", and names "the configurability follow-on: per-rule enable/disable, severity
overrides". This item is that follow-on for the suppression half.

Two distinct populations need it, and they are genuinely different from R407:

- A team disagrees with a rule wholesale (a common one is `input-object-name-suffix`, an
  opinionated rule R398 shipped on with no off switch). They want it silent everywhere without
  patching graphitron.
- A specific author-owned type cannot comply for a reason the linter cannot see (a legacy name
  kept for API compatibility, an enum whose values mirror an external system). The developer has
  decided not to fix it and wants that one finding silenced without hiding the rule everywhere.

R407 handles the orthogonal "cannot fix" case (generator-injected federation/link definitions
the author never wrote); this item handles "author-owned, chooses not to fix". Both must exist
for lint output to stay actionable: every remaining warning is then something the reader can
act on, so the signal is trustworthy.

## Open design fork (for the Ready reviewer to settle)

Which suppression surface ships in v1. The three candidates and the recommendation:

- **(A) Project config, whole-rule + type-name-pattern disable (recommended v1).** A `<lint>`
  block on the Maven plugin config listing rule ids to disable globally and/or type-name
  patterns to exclude. This is the shape R398 already named ("per-rule enable/disable"), it is
  config-shaped rather than node-shaped, it keeps the published SDL clean, and it matches the
  Buf / graphql-schema-linter prior art R398 cites. It covers the "team disagrees with a rule"
  population fully and the "one legacy type" population coarsely (exclude that type by name).
- **(B) SDL directive, node-local suppression (recommended follow-on, not v1).**
  `@lintDisable(rules: [String!])` on a type / field / node. Node-local, author-authored,
  travels with the schema, graphitron-native (directive-driven). But it *pollutes the published
  SDL surface* with generator-tooling metadata that is not part of the API contract, so
  graphitron would have to strip it on emit the way it already keeps internal directive surface
  out of the assembled schema (`DirectiveSupportTypes`). It is a genuinely different capability
  (suppress *this occurrence*, not *this rule everywhere*) and is the right axis *once a consumer
  needs occurrence-level granularity that a name pattern cannot express*.
- **(C) Inline SDL comments** (`# graphitron-disable-next-line <rule>`, ESLint-style). Rejected:
  graphql-java comment-to-node association is unreliable, so the anchor would be fragile.

Recommendation: ship **(A)** in v1 and defer **(B)** until a consumer hits granularity (A)
cannot express. Committing to both at once is the premature-abstraction the R398 discipline
("do not build the extensibility abstraction before a real population of rules exists to justify
its shape") warns against; the narrower axis first is the project's R3-then-R121 pattern. The
sections below spec option (A); if the Ready reviewer prefers (B) or both, the plan is revised
before sign-off.

## Decision (assuming option A)

Suppression is applied **build-side, in the engine, before findings reach `ValidationReport`**.
This is the R398 build-is-the-single-evaluator spine: because the LSP replays `ValidationReport`
and the MCP `diagnostics` tool projects it, applying suppression at the one build evaluator means
a suppressed finding never surfaces in CI, the editor squiggle, or the MCP tool, from one
definition, with no second filter and no drift seam. The config must therefore reach *every*
construction site of the engine, the build mojos (`GenerateMojo` / `ValidateMojo`) and the dev
server (`DevMojo.bindServer`), so the LSP and MCP suppress identically. Suppression that lived
only in the Maven log would violate the spine (the editor would still squiggle).

Config identity is typed against the rule enum. A configured rule id is validated to resolve to
a `LintRule` constant; an unknown id fails the build with a message listing the valid ids
(kebab-case `LintRule.id()` values). This is the typed-not-stringly guard: the config names a
rule by its stable id, and a typo is a build error, not a silently-ignored line.

## User documentation (first-client check)

Draft of the `docs/manual/reference/mojo-configuration.adoc` (Maven plugin configuration
reference) section, written first so the design is validated against how a consumer reads it.
This is the page that already documents every `<configuration>` parameter of the plugin, so the
new `<lint>` block joins it there. If this does not read simply, the design is wrong and changes
before implementation.

> ### Silencing lint warnings
>
> Graphitron's schema linter reports style and convention warnings (naming, missing
> descriptions, deprecated-directive usage, ...). Every rule is on by default. To silence a rule
> you disagree with, or to exclude types you cannot change, add a `<lint>` block to the plugin
> configuration:
>
> ```xml
> <configuration>
>   <lint>
>     <!-- Turn a rule off everywhere, by its rule id. -->
>     <disabledRules>
>       <rule>input-object-name-suffix</rule>
>       <rule>types-and-fields-have-descriptions</rule>
>     </disabledRules>
>     <!-- Skip linting types whose name matches a pattern (glob). -->
>     <excludedTypes>
>       <type>Legacy*</type>
>     </excludedTypes>
>   </lint>
> </configuration>
> ```
>
> Rule ids are the kebab-case names shown in each warning and in the `graphitron:diagnostics`
> MCP tool. A misspelled rule id fails the build with the list of valid ids.
>
> `disabledRules` silences a rule everywhere it would fire, in the build log, in your editor's
> squiggles, and in the MCP diagnostics tool. `excludedTypes` skips the schema linter's checks
> on the matching types. A handful of advisories come from graphitron's schema classifier rather
> than the linter (for example `redundant-record-directive`); these are not tied to a single
> type name, so they are silenced by rule id in `disabledRules`, not by `excludedTypes`.

(When the feature ships, this block moves out of the plan into its real home and drops any
`R<n>` / phase vocabulary.)

## Implementation (option A)

- A config carrier (e.g. a `LintConfig` record: `Set<String> disabledRuleIds`,
  `List<String> excludedTypePatterns`) built from the new `<lint>` Maven parameter on
  `AbstractRewriteMojo`, threaded through `RewriteContext` into `GraphQLRewriteGenerator`.
- Validate `disabledRuleIds` against `LintRule.id()` at config-build time; unknown id →
  build failure naming the valid ids.
- Suppression is applied in `GraphQLRewriteGenerator.withLintFindings`, where the classifier
  advisories (`schema.warnings()`) and the SDL engine findings (`LintEngine.builtIn().run(...)`)
  are already combined into one `List<BuildWarning>`. Two filters at two different boundaries,
  and they cover deliberately different scopes:
  - a **disabled-rule filter** over the *combined* list, dropping any `BuildWarning.LintFinding`
    whose `LintRule.id()` is in `disabledRuleIds`. Because it keys on the typed rule id and runs
    after concatenation, it covers both the engine findings and the classifier-sourced
    `LintFinding` arms (redundant `@record`, `@splitQuery`-on-record, same-table
    `@asConnection`). A classifier advisory is therefore suppressible by rule id like any other.
  - a **type-name-pattern filter** inside the engine, widening the existing exclusion set at
    `LintEngine.run`'s skip points (the same boundary R407 widens for injected names). It is
    **scoped to engine findings only**, and this is the asymmetry to settle in prose rather than
    leave the implementer to invent: the classifier advisories never pass through the engine's
    AST walk (they arrive pre-formed on `schema.warnings()`), and the flat `BuildWarning` list
    they ride carries a `SourceLocation` plus a message string but no structured owning-type
    handle to glob against. Reverse-mapping a location, or scraping the type name out of the
    message text, back to a type would be exactly the fragile-anchor trap this spec already
    rejects for option C. So `excludedTypes` skips the linter's checks on the matching types,
    but a classifier advisory on an excluded type still fires; classifier advisories are
    suppressible by rule id (`disabledRules`) only. The asymmetry is pinned by a test below.
- `DevMojo.bindServer` and the validate path construct the engine with the same `LintConfig`, so
  the LSP and MCP suppress identically.

## Tests

- **Pipeline tier (primary).** Fixture SDL that trips a rule; with that rule id in
  `disabledRules`, assert the finding set no longer contains it and *other* rules still fire.
  Second fixture: a type matching an `excludedTypes` pattern produces no engine findings while a
  non-matching sibling still does. Assert on the typed `LintRule` set, not message substrings.
- **Config validation.** An unknown rule id in `disabledRules` fails config build with a message
  listing valid ids (assert the failure, not the exact wording).
- **Single-evaluator parity.** One LSP-tier test (template: `ValidatorDiagnosticsTest`) that a
  finding suppressed at the build does not replay as a squiggle, and one MCP-tier test (template:
  `GraphitronMcpServerTest`) that it does not surface through `diagnostics`. This pins that
  suppression rides the single evaluator rather than being a log-only filter.
- **Classifier-advisory suppression by rule id.** Disabling a `Source.CLASSIFIER` rule id (e.g.
  `redundant-record-directive`) suppresses that advisory too, verifying the disabled-rule filter
  sits on the combined `BuildWarning` channel and not inside the engine's AST walk (which never
  sees classifier advisories).
- **`excludedTypes` asymmetry (pins the intended scope).** A fixture where an `excludedTypes`
  pattern matches a type that trips *both* an engine rule and a classifier advisory: assert the
  engine finding is gone while the classifier advisory on that same excluded type still fires.
  This locks in that `excludedTypes` is engine-scoped and prevents a future refactor from
  quietly extending it to the classifier channel (which would need the fragile owning-type
  reverse-map option C rejects).

## Non-goals

- Severity overrides and error-capable lint (still the configurability follow-on's remainder;
  this item is suppression only, everything stays a warning).
- The node-local `@lintDisable` directive (option B), unless the Ready reviewer pulls it into
  v1.
- Per-rule enablement of *new* rules not yet in the built-in set (there is no plugin SPI yet;
  that is a separate deferred item in R398).

## Relationships

- **R398** (SDL lint engine): names this as its configurability follow-on and pins the
  build-is-single-evaluator spine this item rides. The typed `LintRule` id R398 already puts on
  the MCP wire is the config's identity axis.
- **R407** (exclude generator-injected definitions): the "cannot fix" sibling. R407 excludes
  non-author definitions with no config; R408 lets the author suppress findings on their own
  types. The type-name-pattern filter here lands at the same `LintEngine.run` boundary R407
  widens, so the two should be sequenced to avoid colliding on that edit (either order works;
  the second to land rebases onto the first).
