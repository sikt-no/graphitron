---
title: "Apollo Federation via federation-jvm transform"
status: Spec
priority: 1
---

# Apollo Federation via federation-jvm transform

> Land Federation 2 support across three surfaces:
> `LinkDirectiveProcessor` from federation-graphql-java-support pre-runs
> our schema build to inject the directive declarations a consumer's
> `@link` imports; a small wrap diagnoses the no-`@link`-no-manual case
> with a targeted recipe; a runtime post-step wraps the built schema in
> `Federation.transform` so `_entities` resolves natively. Closes the
> `QueryEntityField` stub once the runtime path is live.

---

## Goal

Make a Federation-2 SDL build cleanly without consumer-side directive
boilerplate, and make the rewrite-emitted schema a drop-in input to
`federation-graphql-java-support` at runtime. Delegate as much
work as possible to the library; add only the glue and the
diagnostics it does not provide.

Three landing markers:

1. A consumer SDL that opens with `extend schema @link(url:
   "https://specs.apollo.dev/federation/v2.x", import: [...])`
   validates without having to hand-declare each imported directive.
2. A consumer who has not opted into `@link` and has not declared a
   federation directive sees a targeted "use `@link` or declare it
   yourself, here is the one-liner" diagnostic instead of
   graphql-java's raw "tried to use an undeclared directive" error.
3. `Query._entities(representations: [_Any!]!): [_Entity]!` resolves at
   runtime via `Federation.transform`, with no per-consumer wiring
   beyond the existing `Graphitron.buildSchema(...)` call.

## Current state

- **Model.** `QueryField.QueryEntityField` carries `(parentTypeName,
  name, location, returnType)` with no entity-resolution metadata
  (`model/QueryField.java:82`). Classification accepts `_entities`
  as a passthrough at `FieldBuilder.java:1577-1580`.
- **Validation.** `GraphitronSchemaValidator.validateQueryEntityField`
  is an empty method (`GraphitronSchemaValidator.java:294`). Anything
  goes today.
- **Generator.** `TypeFetcherGenerator` routes `QueryEntityField` to
  `stub(f)` with reason "QueryEntityField not yet implemented" pointing
  at this plan (`TypeFetcherGenerator.java:218-219, 349`).
- **Directive declarations.** Only `@tag` is auto-injected
  (`TagApplier.ensureTagDirectiveDeclared`, `TagApplier.java:264-298`),
  with federation-2 parity on the location set. `@key`, `@shareable`,
  `@inaccessible`, `@override`, `@link` itself, and the rest of the
  federation set are not. The rejection fires from
  `SchemaGenerator.makeExecutableSchema(registry, runtimeWiring)`
  inside `GraphitronSchemaBuilder.buildBundle`, not from
  `RewriteSchemaLoader.load()`'s `buildRegistry` call (which does not
  validate directive declarations). Today the error surfaces as a
  `SchemaProblem` from graphql-java with "tried to use an undeclared
  directive ..." and propagates through `ValidationFailedException`.
- **Library available.** `federation-graphql-java-support` exposes
  `LinkDirectiveProcessor.loadFederationImportedDefinitions(registry)`
  as `public static`. It reads `@link` on schema definitions and
  extensions, handles `import` aliases (`as`), gates each directive on
  its minimum spec version, and returns a `Stream<SDLNamedDefinition>`
  the caller adds to the registry. Pinning a directive set in
  Graphitron is unnecessary; the library tracks the spec.
- **Runtime guidance** lives in
  `graphitron-rewrite/docs/getting-started.md:80-102`: consumers wrap
  the built schema in `Federation.transform(base)` themselves. The doc
  pre-supposes the build-time side already works, which it does not
  for non-trivial federation SDL.

## User documentation (first-client check)

Two new doc anchors land in `getting-started.md`. Drafts below; the
final wording moves into the doc when the relevant phase ships.

### Build-time federation directives (new subsection of `## Federation`)

> Two ways to declare federation directives in your SDL:
>
> **Option 1, recommended.** Open one of your `.graphqls` files with
> Apollo Federation 2's `@link`:
>
> ```graphql
> extend schema
>   @link(
>     url: "https://specs.apollo.dev/federation/v2.10",
>     import: ["@key", "@shareable", "@inaccessible", "@override", "@tag"]
>   )
> ```
>
> Pick whichever `v2.x` URL covers the directives you import; the
> generator delegates to `federation-graphql-java-support`, which
> tracks the Apollo spec and gates directives on their minimum
> version. The federation runtime reads the same `@link` line.
>
> **Option 2, manual.** If you do not want `@link`, declare each
> directive you use:
>
> ```graphql
> directive @key(fields: String!, resolvable: Boolean = true) repeatable
>     on OBJECT | INTERFACE
> directive @shareable on OBJECT | FIELD_DEFINITION
> ```
>
> Mixing the two on the same directive (importing via `@link` and
> redeclaring it) fails validation; pick one per directive.
>
> **`<schemaInput tag>` is federation-specific.** If you set
> `<schemaInput><tag>...</tag></schemaInput>` in the plugin config,
> the resulting `@tag(name: "...")` directives are only meaningful
> to a federation gateway. The plugin treats this as an implicit
> opt-in to Federation 2:
>
> - If you have not declared `@link`, the plugin synthesises one
>   with `import: ["@tag"]` for you. Nothing else changes; you can
>   still author `@key` / `@shareable` / etc. with manual
>   declarations if you want.
> - If you *have* declared `@link` but `"@tag"` is missing from your
>   `import` list, the build fails with a fatal error pointing at
>   your `@link`. Add `"@tag"` to clear it.
> - If `<schemaInput tag>` is unset, the plugin makes no federation
>   choice on your behalf.

### `_entities` (replacement for the existing `## Federation` snippet)

> The rewrite emits `Query._entities` natively. You no longer need
> `Federation.transform`'s `.fetchEntities(...)` callback unless you
> have additional entity types not classified by Graphitron.
>
> ```java
> GraphQLSchema schema = Graphitron.buildSchema(b -> {});
> // Federation directives + `_entities` are wired automatically.
> ```

If either of these reads awkwardly to a first-time reader, the design
needs to change before implementation lands.

## Plan

Phase ordering is 1 → 2 → 3. Phase 1 lands the bulk of build-time
behaviour by routing through `LinkDirectiveProcessor`. Phase 2 is the
`<schemaInput tag>` glue that depends on Phase 1 having injected
the federation `@tag` declaration. Phase 3 wires `_entities` runtime
resolution and removes the `QueryEntityField` stub.

### Phase 1 — `@link` injection via federation-jvm + recipe diagnostic

**Goal:** make Option 1 work (`@link`-imported directives validate
without manual declarations), and replace graphql-java's bare "tried
to use an undeclared directive 'X'" with a structured `ValidationError`
that names the two recipes.

**Dependency.** Add `com.apollographql.federation:federation-graphql-java-support`
v6.0.0 to `graphitron-rewrite/pom.xml`'s dependency-management section
and pull it into the `graphitron` module. The library already lives in
the runtime path of consumers using federation today; this elevates it
to a build-time dependency too.

**Approach.** Delegate the directive-injection work to
`LinkDirectiveProcessor.loadFederationImportedDefinitions(registry)`,
which Apollo already maintains and version-tracks. It walks
`registry.schemaDefinition()` plus `getSchemaExtensionDefinitions()`
for `@link`s pointing at any `https://specs.apollo.dev/federation/v2.x`
URL, parses the `import` array (string and `{name, as}` object forms),
gates each entry on its minimum spec version, and returns a
`Stream<SDLNamedDefinition>` of declarations to add. Calling it as an
applier-style step right after `RewriteSchemaLoader.load(...)`
populates the registry before our existing
`SchemaGenerator.makeExecutableSchema(registry, runtimeWiring)` validates
directive uses.

No Graphitron-side directive catalogue, no canonical-declaration
table, no version pin. The library tracks the spec; we depend on its
v6.0.0 release (Feb 2026) and bump as the spec evolves.

**Component:** new `FederationLinkApplier` (`schema/input/`) that wraps
the library call:

```java
var defs = LinkDirectiveProcessor.loadFederationImportedDefinitions(registry);
if (defs == null) return;  // no federation @link present
defs.forEach(def -> registry.add(def).ifPresent(err -> errors.add(toValidationError(err))));
```

A thin wrapper, but it carries the federation-bit detection (whether
`defs != null`) into the bundle for Phase 3 and converts any
`registry.add(...)` rejection into a `ValidationError` with
`RejectionKind.INVALID_SCHEMA`.

**Recipe diagnostic.** When a consumer uses a federation directive
without `@link` and without a manual declaration, graphql-java's
`SchemaGenerator.makeExecutableSchema` throws a `SchemaProblem` whose
errors include "tried to use an undeclared directive". `GraphitronSchemaBuilder`
already runs that call; wrap the `SchemaProblem` catch site to detect
"undeclared directive '<name>'" entries where `<name>` is in
federation-jvm's known directive set (read from `FederationDirectives.allNames`)
and rewrite each one as:

```
Federation directive '@<name>' is not declared. Pick one:
  (1) Open one of your .graphqls files with
      `extend schema @link(url: "https://specs.apollo.dev/federation/v2.x",
                           import: ["@<name>", ...])`
  (2) Or declare it manually with `directive @<name> ... on ...`.
See graphitron-rewrite/docs/getting-started.md#build-time-federation-directives.
```

Non-federation undeclared-directive errors flow through unchanged.
This is the only Graphitron-side string-match against graphql-java's
error wording; if the wording shifts in a future graphql-java release,
the test below catches it.

**Hook site.** `GraphQLRewriteGenerator.loadAttributedRegistry` runs
`RewriteSchemaLoader.load(...)` → `TagApplier.apply(...)` →
`DescriptionNoteApplier.apply(...)` today. Insert
`FederationLinkApplier.apply(registry)` before `TagApplier` (Phase 2's
`<schemaInput tag>` synthesis runs between them; see Phase 2). The
recipe-diagnostic wrap lives in `GraphitronSchemaBuilder.buildBundle`
around the `makeExecutableSchema` call.

**Tests.**
- `FederationLinkApplierTest`: SDL with `extend schema @link(url:
  ".../v2.x", import: ["@key"])` and a `Foo @key(fields: "id")` type;
  registry post-apply has both `directive @link` and `directive @key`
  declarations injected by the library; no error.
- Multi-import: `import: ["@key", "@shareable", "@inaccessible"]`
  injects three declarations.
- Alias: `import: [{name: "@key", as: "@primaryKey"}]` injects under
  `@primaryKey` (library's responsibility; we just assert it propagates).
- No `@link`: `loadFederationImportedDefinitions` returns null;
  applier no-ops, federation bit on the bundle stays false.
- `RecipeDiagnosticTest`: SDL with `@key` use, no `@link`, no manual
  declaration → `ValidationFailedException` whose error message contains
  "Pick one: (1) ..." and the doc anchor; assert the recipe replaced
  graphql-java's wording.
- Non-federation undeclared directive (e.g. `@madeUp`): unchanged
  graphql-java error, no recipe rewrite.

### Phase 2 — `<schemaInput tag>` opt-in + `TagApplier` cleanup

**Goal:** route `<schemaInput tag>`'s `@tag` uses through the same
`@link`-import path Phase 1 enabled, and delete `TagApplier`'s
hand-rolled directive-declaration auto-inject.

**Component:** new `TagLinkSynthesiser` (`schema/input/`) that adds a
synthesised `@link(import: ["@tag"])` schema extension when
`<schemaInput tag>` is configured and the SDL has no `@link`. Runs
*before* `FederationLinkApplier` (from Phase 1), so the synthesised
extension is processed by the same library call as author-written
`@link`s. One code path handles both.

**Order of pipeline passes** in `loadAttributedRegistry`:

```
RewriteSchemaLoader.load
  → TagLinkSynthesiser     (Phase 2; conditional on <schemaInput tag>)
  → FederationLinkApplier  (Phase 1)
  → TagApplier             (existing, minus ensureTagDirectiveDeclared)
  → DescriptionNoteApplier (existing)
```

**`<schemaInput tag>` and `@link` interaction.**

`<schemaInput tag>` config emits `@tag(name: "...")` directives. `@tag`
is inherently a federation construct: only a federation gateway
consumes the resulting tags. Setting `<schemaInput tag>` is therefore
an implicit opt-in to Apollo Federation 2; the canonical declaration
path for the resulting `@tag` uses is `@link(import: ["@tag"])`, not
`TagApplier`'s hand-rolled auto-inject. This phase makes the canonical
path the only path:

| Schema state | `<schemaInput tag>` set? | Action |
|---|---|---|
| No `@link` declaration | yes | Synthesise `extend schema @link(url: "https://specs.apollo.dev/federation/v2.x", import: ["@tag"])`. `FederationLinkApplier` (Phase 1) processes it via `LinkDirectiveProcessor`, which injects the `@tag` declaration. Use whichever v2.x URL the project's federation runtime depends on; `v2.10` is fine. |
| `@link` declared, `"@tag"` in `import` list | yes | No-op. |
| `@link` declared, `"@tag"` *absent* from `import` list | yes | Fatal `ValidationError` keyed on the `@link` directive's `SourceLocation`: "`<schemaInput tag>` is configured but `'@tag'` is not in the `@link` import list at `<file>:<line>`. Add `\"@tag\"` to the `import` array." |
| (any) | no | No action; `<schemaInput tag>` is not in play. |

Single synthesis / single error, not per-tagged-input: the action
is the same regardless of how many tagged inputs the consumer has.

**Source attribution for the synthesised extension.**
`TagLinkSynthesiser` emits the synthesised `SchemaExtensionDefinition`
with a sentinel `SourceLocation` of
`("<graphitron-synthesised:tag-link>", 1, 1)`. That gives any
downstream error message pointing at the synthesised extension a
stable, unmistakably-non-user source name. Existing attribution in
`TagApplier` / `DescriptionNoteApplier` keys on authored sources only;
the sentinel name does not collide.

The hard error on `@link`-without-`"@tag"`-import is the explicit-author
counterpart to the synthesis: an author who has chosen to declare
`@link` themselves has taken control of the federation surface, so a
mismatch between plugin config and `@link` import list is an author
bug, not a defaulting decision the plugin should make silently.

**`TagApplier.ensureTagDirectiveDeclared` deletes in this phase.**
After Phase 2 lands, every `@tag` declaration comes from the `@link`
import path: either the author's own `@link`, or the synthesised one
above. The hand-rolled auto-inject at `TagApplier.java:264-298`
becomes dead code and is removed in the same commit. Authors who
manually use `@tag` without `<schemaInput tag>` and without `@link`
fall through to Phase 1's recipe diagnostic, which already names both
the `@link` and manual recipes.

Tests:
- `<schemaInput tag>` set, no `@link` in SDL: registry post-apply
  contains a synthesised `@link` schema extension and federation's
  `@tag` declaration (the latter contributed by `LinkDirectiveProcessor`);
  no error, no warning.
- `<schemaInput tag>` set, SDL has `@link(import: ["@tag"])`: no
  synthesis, no error.
- `<schemaInput tag>` set, SDL has `@link(import: [{name: "@tag", as:
  "@maturity"}])`: alias counts as importing `@tag`; no synthesis,
  no error.
- `<schemaInput tag>` set, SDL has `@link(import: ["@key"])` (no
  `"@tag"`): fatal `ValidationError` keyed on the `@link` directive's
  source location; error message contains the file path and the line
  of the offending `@link`.
- `<schemaInput tag>` *not* set, no `@link`, no `@tag` use anywhere:
  no synthesis, no error (regression guard against blanket federation
  opt-in).
- `<schemaInput tag>` *not* set, manual `@tag` use in SDL, no
  `@link`: Phase 1's recipe diagnostic fires (no fallback
  declaration auto-inject, since `TagApplier`'s
  `ensureTagDirectiveDeclared` is gone).
- Two tagged inputs, no `@link`: still one synthesised `@link`, not
  two.

### Phase 3 — `_entities` runtime wiring + `QueryEntityField` removal

**Goal:** `Federation.transform` runs as a post-step inside
`GraphitronSchemaBuilder` whenever the SDL has an `@link` to a
federation spec; `Query._entities` resolves natively; the
`QueryEntityField` model variant, the empty validator method, and the
stub generator branch all delete.

**Trigger condition.** The post-step runs iff Phase 1 detected a
federation `@link` (i.e. `LinkDirectiveProcessor.loadFederationImportedDefinitions`
returned a non-null stream). Non-federation consumers do not pay for
the `Federation.transform` rebuild (which walks the full schema).
Carry the boolean on `GraphitronSchemaBuilder.Bundle` (the existing
`record Bundle(GraphitronSchema model, GraphQLSchema assembled)`
extends to a third `boolean federationLink` component). `RewriteContext`
is the wrong carrier: it is constructed once by the Mojo before any
parsing happens, while the federation-link bit is derived from the
parsed schema. The bundle already pairs classifier output with the
assembled schema and is the natural place for parsing-derived flags.

The `GraphQLSchema` post-step uses the `Federation.transform(GraphQLSchema)`
overload, *not* the registry overload. Phase 1's `FederationLinkApplier`
has already injected the directive declarations into the registry, so
by the time `makeExecutableSchema` runs the schema has the federation
directives wired; the post-step only needs to add `_Service` /
`_Entity` and the entity resolvers.

**Wiring.** In `GraphitronSchemaClassGenerator` (or a new
`FederationPostStep` co-located with the generator), after the base
`GraphQLSchema` is built:

```java
GraphQLSchema base = /* existing build */;
if (ctx.hasFederationLink()) {
    return Federation.transform(base)
        .resolveEntityType(this::resolveEntityType)
        .fetchEntities(this::fetchEntities)
        .build();
}
return base;
```

**`resolveEntityType`.** Reads `__typename` from the representation
map and looks up the matching `GraphQLObjectType` from the schema.
Mirrors the shape of `Query.node`'s typeId-based dispatch; details in
the implementation. Unknown `__typename` returns `null`, which surfaces
as a federation-level error to the caller.

**`fetchEntities`.** For each representation, dispatch by `__typename`
to the corresponding type's existing fetcher, with the representation
map as the lookup-key carrier. Two sub-cases:

1. The type is a `@node` type. The representation carries a
   typed-NodeId-shaped lookup; route through the same path
   `Query.node` uses (`QueryNodeFetcherClassGenerator` / `NodeIdEncoder`).
2. The type is a `@key`-bound entity backed by a jOOQ table. The
   representation carries the key columns directly. Route through the
   per-type fetcher with a synthesised `@lookupKey`-equivalent path.

The two sub-cases share a sealed `EntityResolution` model variant added
during this phase; `FieldBuilder` populates it from the type's `@key`
or `@node` directive at classify time.

**Deletions.** All in the Phase-3 commit:
- `QueryField.QueryEntityField` record (`model/QueryField.java:82-87`).
- `validateQueryEntityField` (`GraphitronSchemaValidator.java:69, 294`).
- The `QueryEntityField` arm in `TypeFetcherGenerator`
  (`TypeFetcherGenerator.java:218-219, 349`) and its
  `NOT_IMPLEMENTED_REASONS` entry.
- The classify-time passthrough for `_entities` at
  `FieldBuilder.java:1577-1580`. After this phase, `_entities` is
  not a Graphitron-classified field; it lives on the federation-wrapped
  schema only.

**Consumer override hook.** Some consumers will have entity types not
classified by Graphitron (hand-rolled, plus federated). Expose
`Graphitron.buildSchema(Consumer<GraphQLSchema.Builder>)`'s existing
customizer surface plus a new entry point:

```java
GraphQLSchema schema = Graphitron.buildSchema(b -> { /* base customizer */ },
    fed -> fed.fetchEntities(myCustomFetcher));
```

The two-arg form is additive: the federation builder is pre-configured
with Graphitron's resolvers, and the consumer adds or overrides on top.
Single-arg call sites stay unchanged.

**Facade generator change.** `Graphitron.buildSchema(...)` is emitted
by `GraphitronFacadeGenerator`. Adding the two-arg overload requires a
second `MethodSpec` in that generator and a corresponding
`build(Consumer, Consumer)` overload on `GraphitronSchemaClassGenerator`.
The customizer-contract javadoc on the existing one-arg method covers
the schema builder; the new overload's javadoc enumerates the
federation builder's contract (additive only on `fetchEntities` /
`resolveEntityType`; do not call `.build()` from the customizer).

**Tests.**
- Pipeline: SDL with `extend schema @link(...federation/v2.x,
  import: ["@key"])` and a `Foo @key(fields: "id")` type emits a
  schema where `Query._entities(representations: [{__typename: "Foo",
  id: "1"}])` resolves to a `Foo` row (against the test fixture DB).
- `_entities` over a `@node` type uses the NodeId path.
- `_entities` over a non-`@node` `@key` type uses the column-key path.
- Consumer override: `buildSchema(b -> {}, fed -> fed.fetchEntities(...))`
  replaces the default fetcher; the override actually fires.
- Determinism: `IdempotentWriterTest`-style ratchet over the federated
  schema's printed SDL across two runs.
- Non-federation regression guard: pipeline test on a schema with no
  `@link` returns the exact base `GraphQLSchema` reference (assert
  `schema == base`); confirms the post-step is skipped, not just
  benign.
- Multi-key: a `Foo @key(fields: "id") @key(fields: "sku")` type
  resolves both `_entities([{__typename: "Foo", id: "1"}])` and
  `_entities([{__typename: "Foo", sku: "X"}])`; the fetcher dispatches
  on the *set* of provided fields, not just `__typename`.

## Non-goals

- **Federation 1** (`@link`-less, hand-written `_service` SDL).
  The legacy `<removeFederationDefinitions>` flag carried this; the
  rewrite drops it. Consumers on Federation 1 must migrate to v2's
  `@link` form before adopting this plan.
- **Maintaining a Graphitron-side federation directive catalogue.**
  `LinkDirectiveProcessor` owns the directive set, the per-version
  gating, and the canonical declarations. We bump the
  `federation-graphql-java-support` dependency when the spec gains
  directives; we don't curate our own table.
- **`@composeDirective` runtime support** beyond the directive
  declaration. Composing custom directives across subgraphs is a
  supergraph concern, handled by the gateway, not this subgraph.
- **Subgraph SDL artefact emission.** `_service.sdl` is reconstructed
  at runtime by `federation-graphql-java-support` from the programmatic
  schema; no build-time SDL artefact is emitted.

## Open decisions

- **Phase 1 recipe-diagnostic severity.** Default position: fatal
  `ValidationError`, since "undeclared directive" is fatal in
  graphql-java today and downgrading to a `BuildWarning` would mask
  real bugs. Reviewer to confirm. If a migrating consumer needs a
  warning-only mode while in transition, that lands as a separate
  flag, not as the default.
- **Phase 3: condition for running `Federation.transform`.** Default:
  only when Phase 1 detected a federation `@link`. Alternative: always
  run when any federation directive is present (declared or imported).
  The default avoids paying the schema-rebuild cost for non-federation
  consumers; the alternative would cover hand-declared-directives-only
  consumers but adds a code path that is only exercised by an
  intermediate migration state. Lean toward the default; reconsider if
  a real consumer surfaces stuck halfway through migration.
- **Phase 2: hard error vs warning when `@link` is declared without
  `"@tag"` in imports and `<schemaInput tag>` is set.** Default:
  fatal `ValidationError`, since an explicit `@link` declaration is
  the author taking control of the federation surface and a missing
  import while `<schemaInput tag>` is configured is an author bug.
  Alternative: warn and synthesise a separate `@link` extension that
  imports only `"@tag"`. The alternative produces two `@link`
  declarations on the schema, which Apollo Federation 2 permits but
  is harder for a reader to reason about. Lean toward the default.
- **Pinning the federation-jvm version.** Default: pin to v6.0.0 in
  the rewrite parent pom and bump explicitly when a consumer needs
  newer-spec directives. Alternative: track the latest 6.x via a
  range. Lean toward the explicit pin to keep the consumer's
  federation runtime version under the consumer's control; the
  rewrite's build-time use should follow the runtime, not lead it.
