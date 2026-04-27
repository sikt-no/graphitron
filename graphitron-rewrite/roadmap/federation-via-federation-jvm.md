---
title: "Apollo Federation via federation-jvm transform"
status: Spec
priority: 1
---

# Apollo Federation via federation-jvm transform

> Land Federation 2 support across three surfaces: build-time `@link`
> recognition with auto-injected directive declarations, validate-time
> diagnostics that point migrating consumers at one of two declaration
> recipes, and a runtime post-step that wraps the built schema in
> `Federation.transform` so `_entities` resolves natively. Closes the
> `QueryEntityField` stub once the runtime path is live.

---

## Goal

Make a Federation-2 SDL build cleanly without consumer-side directive
boilerplate, and make the rewrite-emitted schema a drop-in input to
`federation-graphql-java-support` at runtime.

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
- **Directive declarations.** Only `@tag` is auto-injected, narrowly
  (`TagApplier.ensureTagDirectiveDeclared`, `TagApplier.java:264-285`).
  `@key`, `@shareable`, `@inaccessible`, `@override`, `@link` itself,
  and the rest of the federation set are not. graphql-java's load step
  rejects them with "tried to use an undeclared directive ..."
  surfaced verbatim through `ValidationFailedException`.
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
>     url: "https://specs.apollo.dev/federation/v2.6",
>     import: ["@key", "@shareable", "@inaccessible", "@override", "@tag"]
>   )
> ```
>
> The generator recognises this declaration and injects each imported
> directive's canonical declaration for you. The federation runtime
> reads the same `@link` line.
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

Phase ordering is 1 → 2 → 3. Phase 1 is independent and lands the most
visible improvement first. Phase 2 builds on Phase 1's directive-name
catalogue. Phase 3 depends on Phase 2 because `Federation.transform`
reads the `@link` declaration to discover the federation contract.

### Phase 1 — Diagnostics for undeclared federation directives

**Goal:** replace graphql-java's bare "tried to use an undeclared
directive 'X'" with a structured `ValidationError` that names the
recipe. No registry mutation; no behavioural change beyond the error
message.

**Approach:** preflight scan over the parsed `Document` before
graphql-java's registry build sees it. Walking the parsed document
(rather than catching graphql-java's load errors and string-matching
the messages) gives us stable structured access to directive use sites
without coupling to graphql-java's error wording.

**Federation directive catalogue:** baked-in `Set<String>` covering the
v2.6 set: `key`, `requires`, `provides`, `external`, `shareable`,
`inaccessible`, `override`, `tag`, `composeDirective`, `interfaceObject`,
plus `link` itself.

**Detection rule.** For each directive *use* in the parsed document:
1. If the directive name is in the federation catalogue, *and*
2. the document does not declare it (no top-level `directive @<name>`
   matching), *and*
3. the document does not contain `@link(import: [..., "@<name>", ...])`
   (textual or by-AST presence; tolerant to the `as` rename),

emit a `ValidationError` keyed on the use site's `SourceLocation` with
message:

```
Federation directive '@<name>' is not declared. Pick one:
  (1) Open one of your .graphqls files with
      `extend schema @link(url: "https://specs.apollo.dev/federation/v2.6",
                           import: ["@<name>", ...])`
  (2) Or declare it yourself:
      directive @<name>(<args>) <repeatable> on <locations>
See graphitron-rewrite/docs/getting-started.md#build-time-federation-directives.
```

The `<args>` / `<repeatable>` / `<locations>` are filled from the same
canonical-declaration table Phase 2 will use to inject. Centralising
the table here keeps Phase 2's injection a one-liner per directive.

**Hook site:** `GraphQLRewriteGenerator.loadAttributedRegistry` runs
parse → applier passes → registry build. Insert the preflight scan
between parse and registry build. If any federation diagnostic fires,
collect *all* of them, then short-circuit with
`ValidationFailedException` carrying the structured list. Today the
loader throws on the first graphql-java error; the preflight gives a
complete list to a migrating consumer in one go.

**Tests.**
- `FederationDiagnosticsTest`: SDL with `@key` use, no declaration, no
  `@link` → exactly one error keyed on the `@key` use site.
- Same SDL with `@key` declared manually → no error.
- Same SDL with `@link(import: ["@key"])` declared on `extend schema`
  → no error.
- SDL with three federation directives undeclared → three errors, in
  source order.
- SDL with `@link(import: [{name: "@key", as: "@primaryKey"}])` and a
  `@primaryKey` use → no error (alias respected).

### Phase 2 — `@link` recognition + directive injection

**Goal:** make Option 1 work: `extend schema @link(url:
"...federation/v2.x", import: [...])` injects each imported
directive's canonical declaration into the registry.

**Component:** new `FederationLinkApplier` mirroring `TagApplier`'s
shape (`schema/input/FederationLinkApplier.java`).

**Order of pipeline passes** in `loadAttributedRegistry`:

```
parse → preflight scan (Phase 1) → registry build
      → FederationLinkApplier  (Phase 2)
      → TagApplier             (existing)
      → DescriptionNoteApplier (existing)
```

`FederationLinkApplier` runs before `TagApplier` so that when `@tag` is
imported via `@link`, the federation declaration (with the full
location set) wins and `TagApplier`'s narrow auto-injection is bypassed.

**Detection.**
1. Walk `registry.schemaDefinition()` and `registry.getSchemaExtensionDefinitions()`.
2. For each `@link` directive application, parse:
   - `url: String!` (required). Match against
     `https?://specs\.apollo\.dev/federation/v(2\.\d+)`. Other URLs are
     a non-goal for this round; emit a
     `BuildWarning("@link url '<url>' is not a recognised Apollo
     Federation spec; imported directives are not auto-declared")`
     and skip.
   - `import: [Import!]!` (required). Each import is either a string
     (`"@key"`) or an object (`{name: "@key", as: "@primaryKey"}`).
     Strip the leading `@` to get the directive name; record the
     optional alias.

**Canonical declarations.** Baked-in table mapping each supported v2.6
directive name to its `DirectiveDefinition`. Source: Apollo's federation
spec at the matched version. Concretely (illustrative; full table lives
in the implementation):

| Name | Args | Repeatable | Locations |
|---|---|---|---|
| `key` | `fields: String!, resolvable: Boolean = true` | yes | `OBJECT \| INTERFACE` |
| `shareable` | (none) | yes | `OBJECT \| FIELD_DEFINITION` |
| `external` | `reason: String` | no | `OBJECT \| FIELD_DEFINITION` |
| `requires` | `fields: String!` | no | `FIELD_DEFINITION` |
| `provides` | `fields: String!` | no | `FIELD_DEFINITION` |
| `override` | `from: String!` | no | `FIELD_DEFINITION` |
| `inaccessible` | (none) | no | full federation set |
| `tag` | `name: String!` | yes | full federation set incl. `INPUT_OBJECT` |
| `composeDirective` | `name: String!` | yes | `SCHEMA` |
| `interfaceObject` | (none) | no | `OBJECT` |

Pinning v2.6 explicitly is intentional; v2.7+ directives are a
non-goal (see below).

**Injection.**
1. For each imported directive, look up the canonical `DirectiveDefinition`.
2. If `as` was present, rename the definition's `name` to the alias.
3. If the registry already declares the (possibly aliased) directive,
   skip injection and emit a
   `ValidationError("@link imports '@<name>' but the schema also
   declares 'directive @<name>'; remove one")`. Mixing the two paths
   on a single directive is the open-decision default below.
4. Otherwise add the declaration via `registry.add(...)`. Surface any
   graphql-java error from `add()` as `ValidationError`.

**`@link` itself.** The federation `@link` directive is also a
declaration the registry needs. The applier injects its declaration
unconditionally on detection, before processing the `import` array.
Federation's `link` declaration is well-known:

```graphql
directive @link(url: String!, as: String, for: link__Purpose,
                import: [link__Import]) repeatable on SCHEMA
scalar link__Import
enum link__Purpose { SECURITY EXECUTION }
```

**Tests.**
- `FederationLinkApplierTest`: minimal SDL with `extend schema
  @link(url: ".../v2.6", import: ["@key"])` plus `type Foo @key(fields:
  "id")`; registry post-apply has both `directive @link` and `directive
  @key` declarations; no error.
- Multi-import: `import: ["@key", "@shareable", "@inaccessible"]`
  injects three declarations.
- Alias: `import: [{name: "@key", as: "@primaryKey"}]` injects under
  `@primaryKey`; `@key` remains undeclared.
- Conflict: schema imports `@key` *and* declares `directive @key`;
  applier emits the structured `ValidationError` named above.
- Unknown URL: `url: "https://example.com/custom"` triggers a
  `BuildWarning` and no injection.
- Unknown import name: `import: ["@madeUp"]` fires a
  `ValidationError("unknown federation import: '@madeUp'; supported
  imports are: @key, @shareable, ...")`.

### Phase 3 — `_entities` runtime wiring + `QueryEntityField` removal

**Goal:** `Federation.transform` runs as a post-step inside
`GraphitronSchemaBuilder` whenever the SDL has an `@link` to a
federation spec; `Query._entities` resolves natively; the
`QueryEntityField` model variant, the empty validator method, and the
stub generator branch all delete.

**Trigger condition.** The post-step runs iff Phase 2 detected a valid
federation `@link`. Non-federation consumers do not pay for the
`Federation.transform` rebuild (which walks the full schema). Carry
the boolean from `loadAttributedRegistry` to
`GraphitronSchemaBuilder.buildBundle` via a new field on
`RewriteContext` (or on the bundle, depending on phase ordering with
the LSP work).

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

**Tests.**
- Pipeline: SDL with `extend schema @link(...federation/v2.6,
  import: ["@key"])` and a `Foo @key(fields: "id")` type emits a
  schema where `Query._entities(representations: [{__typename: "Foo",
  id: "1"}])` resolves to a `Foo` row (against the test fixture DB).
- Round-trip: federated schema printed via `SchemaPrinter` round-trips
  through `Federation.transform(schema, sdl).build()` for supergraph
  compose.
- `_entities` over a `@node` type uses the NodeId path.
- `_entities` over a non-`@node` `@key` type uses the column-key path.
- Consumer override: `buildSchema(b -> {}, fed -> fed.fetchEntities(...))`
  replaces the default fetcher; the override actually fires.
- Determinism: `IdempotentWriterTest`-style ratchet over the federated
  schema's printed SDL across two runs.

## Non-goals

- **Federation 1** (`@link`-less, hand-written `_service` SDL).
  The legacy `<removeFederationDefinitions>` flag carried this; the
  rewrite drops it. Consumers on Federation 1 must migrate to v2's
  `@link` form before adopting this plan.
- **Federation v2.7+ directives** (`@policy`, `@authenticated`,
  `@requiresScopes`, `@cost`, `@listSize`). Add to the canonical
  declaration table when a consumer needs them; the table extension
  is mechanical.
- **`@composeDirective` runtime support** beyond the directive
  declaration. Composing custom directives across subgraphs is a
  supergraph concern, handled by the gateway, not this subgraph.
- **Subgraph SDL artefact emission.** `_service.sdl` is reconstructed
  at runtime by `federation-graphql-java-support` from the programmatic
  schema; no build-time SDL artefact is emitted.
- **`@tag` location-set widening.** Tracked separately from this plan;
  Phase 2's `@link` import path supplies the full federation set when a
  consumer opts in via `@link(import: ["@tag"])`, which covers the
  common case.
- **Per-version `@link` URL parsing.** The plan recognises any
  `specs.apollo.dev/federation/v2.x` URL and treats them all as v2.6
  for the canonical-declaration table. A v2.5 consumer importing
  `@composeDirective` (added in v2.1) gets the v2.6 declaration, which
  is forward-compatible. If a consumer needs strict per-version
  declarations, that becomes its own follow-up.

## Open decisions

- **Phase 1 diagnostic severity.** Default position: fatal
  `ValidationError`, since "undeclared directive" is fatal in
  graphql-java today and downgrading to a `BuildWarning` would mask
  real bugs. Reviewer to confirm. If a migrating consumer needs a
  warning-only mode while in transition, that lands as a separate
  flag, not as the default.
- **Phase 2: import-the-same-directive-and-redeclare-it conflict.**
  Default: hard error. Alternative: silent precedence (manual wins,
  `@link` import is ignored for that name). Hard error gives a clearer
  diagnostic with no ambiguity in subsequent passes; the cost is that
  consumers cannot incrementally migrate manual declarations into
  `@link` imports without a synchronised edit. Reviewer to confirm.
- **Phase 3: condition for running `Federation.transform`.** Default:
  only when Phase 2 detected a federation `@link`. Alternative: always
  run when any federation directive is present (declared or imported).
  The default avoids paying the schema-rebuild cost for non-federation
  consumers; the alternative would cover hand-declared-directives-only
  consumers but adds a code path that is only exercised by an
  intermediate migration state. Lean toward the default; reconsider if
  a real consumer surfaces stuck halfway through migration.
- **Where the federation-link bit lives.** `RewriteContext` is the
  natural carrier (it already threads through the pipeline) but the
  bit is derived from the parsed schema, not from plugin
  configuration. Alternative: a field on the bundle returned from
  `loadAttributedRegistry`. Pick whichever lands cleanly in
  implementation; either is acceptable.
