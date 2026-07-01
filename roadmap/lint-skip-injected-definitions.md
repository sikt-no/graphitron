---
id: R407
title: "Exclude generator-injected federation/link definitions from linting"
status: Ready
bucket: cleanup
priority: 5
theme: lsp
depends-on: []
created: 2026-07-01
last-updated: 2026-07-01
---

# Exclude generator-injected federation/link definitions from linting

## Problem

The R398 lint engine warns on definitions the developer never wrote and cannot fix. A
consumer whose schema carries a federation `@link` sees findings like:

```
null:22:1: warning: Type name 'federation__FieldSet' should be PascalCase.
null:22:1: warning: Type 'federation__FieldSet' should have a description.
null:35:1: warning: Type name 'link__Import' should be PascalCase.
null:35:1: warning: Type 'link__Import' should have a description.
```

These types are not in the consumer's SDL. `FederationLinkApplier.apply`
(`schema/input/FederationLinkApplier.java`, via
`LinkDirectiveProcessor.loadFederationImportedDefinitions`) injects the federation `@link`
import definitions (`federation__FieldSet`, `federation__Scope`, `link__Import`,
`link__Purpose`, ...) into the registry (`GraphQLRewriteGenerator.loadAttributedRegistry`, lines
176-187, captures its return value). The `null` source name in
the warning is the tell: these definitions were parsed from no consumer `.graphqls`. The
type name (`federation__`, `link__`) is dictated by the federation spec, and the description
is owned by `federation-graphql-java-support`; the developer can neither rename them nor
document them, so `type-names-pascal-case` and `types-and-fields-have-descriptions` firing on
them is pure noise. A warning the reader cannot act on erodes trust in every other warning.

The engine already excludes graphitron's own bundled directive surface: `LintEngine` skips
`BUNDLED_TYPE_NAMES` (`LintEngine.java:44-45, 76-80, 184-190`), the set of type/scalar names
parsed from `directives.graphqls`, with the class javadoc framing the exclusion as "the
generator's surface, not author input". The federation/link definitions are the **same
category**, "generator-owned surface, not author input", contributed by a different producer.
The gap is that the engine's exclusion only knows about graphitron's own producer, not the
federation injectors.

## Decision

Identify the injected definitions **by provenance from the injector**, not by a name
heuristic. This is a same-predicate-two-consumers case (the Generation-thinking principle):
"is this name generator-injected?" is a fact the injector knows precisely, and re-deriving it
at the lint boundary as a `name.contains("__")` heuristic (or by borrowing
`ScalarTypeResolver.FEDERATION_NAMESPACE_SCALARS`) would be a third independent encoding of
the same membership, free to drift from what was actually injected. The authoritative source
is the code that adds the definitions.

- `FederationLinkApplier.apply` already returns a `boolean` (present / not) that the pipeline
  captures onto `AttributedRegistry.federationLink`. Widen it to also yield the names of the
  definitions it injected (the `defs.forEach` loop at `FederationLinkApplier.java:65-70` is the
  single place they are added). This is the **sole** contributor of injected names.
  `KeyNodeSynthesiser.apply` (`schema/federation/KeyNodeSynthesiser.java:55-69`) is *not* a
  contributor: it injects no new definitions. It walks existing types and decorates
  author-written `@node` object types **in place** (`registry.remove(old)` +
  `registry.add(transformed)`) by attaching a `@key` directive. Those types are author-owned and
  carry real source locations, and none of the lint rules fire on the added `@key` directive (the
  casing/description rules fire on type/field names and descriptions; the only applied-directive
  rule is `no-deprecated-directive-usage`, and `@key` is not deprecated). So there is nothing to
  exclude on its behalf. Unioning the names it touched into the exclusion set would silence
  legitimate findings on author `@node` types, directly violating acceptance criterion 2.
  `KeyNodeSynthesiser.apply` keeps its `void` signature.
- Carry the union of injected names on `AttributedRegistry` alongside the existing
  `federationLink` flag (a new `injectedNames()` component; the `federationLink` boolean is then
  derivable as "injected anything", so this collapses state rather than adding a parallel
  carrier). `AttributedRegistry.from(...)` (the test convenience) derives the set the same way
  it derives the boolean today.
- `LintEngine.run` takes the injected-name set (thread `AttributedRegistry`, or pass the set)
  and excludes `BUNDLED_TYPE_NAMES ∪ injectedNames` at the two skip points it already has
  (`LintEngine.java:76, 80`). No new skip mechanism; the existing name-set exclusion widens to a
  second contributor.

Do **not** consolidate with `ScalarTypeResolver.FEDERATION_NAMESPACE_SCALARS`
(`ScalarTypeResolver.java:105`). That set is a hand-maintained *expectation* the scalar emitter
uses to synthesise inline scalars, extended by hand on every federation spec bump (per its own
javadoc). Coupling lint correctness to it would re-introduce exactly the drift this item
eliminates: a federation spec bump that adds a namespaced scalar would silently start emitting
noise until someone remembered to extend the hand-list. Provenance from the injector is
drift-free by construction. If the two lists are ever unified, the direction is the reverse
(derive the emitter's expectation from what the link processor actually loads); that is a
separate item and this one must not depend on it.

## Non-goals

- No suppression surface for author-owned types. That is R408 (the configurability follow-on
  R398 named); this item is only about definitions the author did not write and cannot touch.
- No change to the rule set, no new `LintRule`, no new `LintNodeKind`. If the fix finds itself
  adding a rule constant or a node-kind arm, the exclusion has leaked into the wrong layer; it
  belongs at the traversal boundary, exactly where `BUNDLED_TYPE_NAMES` already sits.
- No change to `ScalarTypeResolver.FEDERATION_NAMESPACE_SCALARS`.

## Implementation

- `schema/input/FederationLinkApplier.java`: widen `apply` to return the injected definition
  names (an `Injected(boolean present, Set<String> names)` record, or a `Set<String>` with
  emptiness meaning "no federation `@link`"). Collect names in the existing `defs.forEach` loop.
  `KeyNodeSynthesiser` is untouched (it injects nothing; see Decision) and keeps its `void`
  signature.
- `AttributedRegistry.java`: add an `injectedNames()` component carrying the set; keep
  `federationLink` (derive it from non-empty, or retain both for a minimal diff, implementer's
  call). Update `from(TypeDefinitionRegistry)` to populate the set for ad-hoc test registries.
- `GraphQLRewriteGenerator.loadAttributedRegistry`: thread the injected names from
  `FederationLinkApplier.apply` into the `AttributedRegistry` it returns. No second injector to
  thread; the `KeyNodeSynthesiser.apply(registry)` call at line 182 stays as-is.
- `lint/LintEngine.java`: accept the injected-name set (via `AttributedRegistry` or an explicit
  argument on `run`) and union it into the exclusion tested at the two skip points. Update
  `GraphQLRewriteGenerator.withLintFindings` (line 297-299) to pass it.

## Tests

- **Pipeline tier (primary oracle).** Add a fixture SDL that carries a federation `@link`
  import and *also* an author-written non-compliant type. Make that type an author `@node` object
  with a non-compliant name (e.g. `type lowercase @node { id: ID! }`): it is both a real author
  violation *and* the exact type `KeyNodeSynthesiser` decorates in place, so the single fixture
  guards against a future implementer folding decorated `@node` names into the exclusion set.
  Assert the finding set contains the `type-names-pascal-case` (and description) findings for
  the authored `@node` type and **zero** findings on any injected `federation__*` / `link__*`
  name. This one fixture pins both halves: injected names are silent, real author violations
  (including on decorated `@node` types) still fire. Assert on the typed `LintRule` and the
  offending node, never on rendered message substrings (R398 testing-strategy ban).
- Do **not** add a per-name unit test enumerating `federation__FieldSet`, `link__Import`, etc.
  That would re-encode the `FEDERATION_NAMESPACE_SCALARS` hand-list as a test assertion and
  would pass even if the provenance plumbing were bypassed by a coincidental heuristic. The
  federation-fixture pipeline test exercises the real injection path and is the honest oracle.
- `LintRuleRegistryCoverageTest`, message-constant tests, and `DirectiveSupportTypesTest` are
  untouched (no new rule, no new node kind). If any of them needs editing, that is a signal the
  exclusion landed in the wrong layer.

## Acceptance criteria

- A schema with a federation `@link` produces no lint findings on the injected
  `federation__*` / `link__*` definitions.
- Author-written non-compliant types in the same schema still produce their findings, including
  author `@node` types that `KeyNodeSynthesiser` decorates in place with a `@key` directive.
- The injected-name set is sourced from the injectors (provenance), not a name heuristic and
  not `FEDERATION_NAMESPACE_SCALARS`.
- The federation-fixture pipeline test above is added and green; no per-name unit list.
- Full reactor green: `mvn install -Plocal-db`.

## Relationships

- **R398** (SDL lint engine): this closes a gap R398 did not foresee, the interaction between
  the lint traversal and the federation-injection pipeline stage. It reuses R398's
  `BUNDLED_TYPE_NAMES` exclusion mechanism rather than adding a parallel one.
- **R408** (lint finding suppression): the sibling follow-on for suppressing findings on
  author-owned types the developer chooses not to fix. R407 is the "cannot fix" half (built-in,
  no author action); R408 is the "chooses not to fix" half (author-driven). Independent; either
  can land first.
