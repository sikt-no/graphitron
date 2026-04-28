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
> `Federation.transform` so `_entities` resolves natively.

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

Markers 1 and 2 ship at `a24feb4`. Marker 3's wiring is in place; the
runtime dispatch that actually resolves entities is the remaining work.

## Shipped (commit `a24feb4`)

The build-time half of the plan plus the federation-transform shell
landed in one commit; the entity-dispatch teeth did not. Concretely,
the following pieces are in trunk and need no further change:

- **Phase 1 (`@link` injection + recipe diagnostic).**
  `FederationLinkApplier` (`schema/input/`) wraps
  `LinkDirectiveProcessor.loadFederationImportedDefinitions` and runs
  in `loadAttributedRegistry` between `TagLinkSynthesiser` and
  `TagApplier`. `GraphitronSchemaBuilder.buildBundle` catches
  `SchemaProblem` from `makeExecutableSchema` and rewrites
  undeclared-federation-directive entries into a recipe message that
  names both the `@link` and manual options and points at
  `getting-started.md#build-time-federation-directives`.
  `Bundle` carries a `federationLink` boolean so Phase 3 can gate on
  the parsed-from-SDL bit.
- **Phase 2 (`<schemaInput tag>` opt-in).** `TagLinkSynthesiser`
  (`schema/input/`) synthesises `extend schema @link(url:
  FederationLinkApplier.DEFAULT_FEDERATION_SPEC_URL, import: ["@tag"])`
  when any `schemaInput` is tagged and no federation `@link` is already
  present. When a federation `@link` is present without `"@tag"` in
  imports, it raises a fatal `ValidationError` keyed on the `@link`'s
  source location. Aliased imports (`{name: "@tag", as: "@maturity"}`)
  count as importing `@tag`. `TagApplier.ensureTagDirectiveDeclared` is
  deleted; every `@tag` declaration now flows through the `@link`
  import path. The synthesised extension uses sentinel source name
  `<graphitron-synthesised:tag-link>`.
- **Phase 3 wiring shell.** `GraphitronSchemaClassGenerator` emits a
  two-method `build()` when `federationLink=true`: the one-arg form
  delegates to the two-arg form, which builds the base schema, applies
  the schema customizer, then calls `Federation.transform(base)
  .setFederation2(true).resolveEntityType(...).fetchEntities(...)`,
  applies the federation customizer, and returns the wrapped schema.
  `GraphitronFacadeGenerator` emits the matching two-arg
  `buildSchema(Consumer, Consumer<SchemaTransformer>)` overload. The
  `QueryEntityField` model variant, its `FieldBuilder` passthrough,
  `TypeFetcherGenerator` stub arm, `NOT_IMPLEMENTED_REASONS` entry, and
  empty `validateQueryEntityField` are all deleted; `_entities`
  classifies as `UnclassifiedField` and is owned by the federation
  wrap.

## What did not ship

The two-arg `build()`'s defaults are placeholders:

- `resolveEntityType` returns `getObjectType(rep.get("__typename"))`,
  which works only when the entity result is itself a representation
  map (i.e. the consumer's `fetchEntities` echoes the representation
  back). It returns `null` for any domain object, which surfaces as a
  federation-level error.
- `fetchEntities` returns `List.of()` unconditionally. Every
  `_entities` query against a stock-built schema returns an empty
  list, regardless of the representations passed.

So a consumer's `_entities` works only if they pass a custom
`fetchEntities` *and* a custom `resolveEntityType` matching their
fetcher's return shape, via the two-arg `buildSchema(...)`. That is
not the contract the docs describe (`docs/getting-started.md:81-92`
promises `_entities` is wired automatically) and not what marker 3
above demands.

A handful of review findings on the shipped pieces also need follow-up;
they are scoped into Phase 5 below rather than treated as "rework
Phase 1/2/3" since the existing behaviour is correct, just fragile.

## Plan

### Phase 4: entity dispatch

**Goal:** make the default `fetchEntities` resolve entities for every
type Graphitron classifies, and make the default `resolveEntityType`
work alongside it. After Phase 4 lands, `Graphitron.buildSchema(b ->
{})` returns a federation-wrapped schema whose `_entities` resolves
without any consumer code.

**Classify-time model.** Add a sealed `EntityResolution` model variant
co-located with the existing classifier output. Every type whose
declaration carries `@key` or `@node` gets an `EntityResolution`
attached to its classified `GraphitronType` entry; types without
either get none, and dispatch falls through to `null` (federation
reports "entity resolution failed for type X").

```java
sealed interface EntityResolution {
    String typeName();

    record NodeBacked(String typeName, NodeKey key)
        implements EntityResolution {}

    record KeyBacked(String typeName, List<KeyAlternative> alternatives)
        implements EntityResolution {}

    record KeyAlternative(List<String> requiredFields) {}
}
```

`NodeBacked` carries the existing `NodeType.key()` shape (typed-NodeId
encoder name + typeId), so `_entities` reuses the `Query.node` lookup
machinery without a parallel implementation.

`KeyBacked.alternatives` is the parsed list of `@key(fields:)`
selections, one entry per `@key` directive on the type. Compound keys
(`fields: "tenantId sku"`) become a single `KeyAlternative` with
multiple required fields. Multiple keys (`@key(fields: "id") @key(fields:
"sku")`) become two alternatives. Nested selections (`fields: "owner {
id }"`) are rejected at classify time with a `ValidationError` naming
the directive and the offending selection; see "Non-goals" below.

**Classify-time wiring.** `FieldBuilder` (or a new
`EntityResolutionBuilder` invoked from `TypeBuilder`) reads each type's
`@key` and `@node` applied directives, parses the `fields:` argument
through graphql-java's selection parser (`Parser.parseValue` over the
`SelectionSet`-shaped string is the cleanest entry; a hand-rolled
tokenizer is the fallback if parser-reuse is awkward), and emits the
appropriate variant. The variant lands on `GraphitronType.NodeType`
and on a new `KeyEntityType` mixin shared by table-bound types that
carry `@key` without `@node`.

The classifier already records the type's `@table` name and column set
when present, so `KeyAlternative` field names map onto column lookups
the same way `@lookupKey` does today. Reuse the existing
`LookupMapping` resolution path rather than building a parallel one.

**Runtime emission.** `GraphitronSchemaClassGenerator` replaces the
two-arg `build()`'s placeholder lambdas with calls into a new
`EntityFetcherDispatch` helper class generated alongside the schema:

```java
fb = Federation.transform(base)
    .setFederation2(true)
    .resolveEntityType(EntityFetcherDispatch::resolveType)
    .fetchEntities(EntityFetcherDispatch::fetchEntities);
federationCustomizer.accept(fb);
return fb.build();
```

`EntityFetcherDispatch` is emitted by a new
`EntityFetcherDispatchClassGenerator` in the `fetchers` subpackage. It
holds an emitted `Map<String, EntityHandler>` keyed by `__typename`,
populated at class load time from one entry per `EntityResolution` on
the classified schema. Each `EntityHandler` knows how to:

1. Match a representation map's keys against the type's recorded
   `KeyAlternative`s, picking the most-specific match (the alternative
   whose required-field set is a subset of the representation, with
   ties broken by alternative size).
2. For `NodeBacked`: decode the representation's typed-NodeId via
   `NodeIdEncoder`, then call into the same per-type fetcher
   `Query.node` uses (`QueryNodeFetcherClassGenerator`'s emitted
   `getNode(typeId, ids)` arm).
3. For `KeyBacked`: build a where-clause from the matched
   `KeyAlternative`'s required columns, route through the per-type
   fetcher already emitted for `@lookupKey` resolution.
4. Return the row (or `null`) as a domain object whose
   `__typename` resolution is just a constant lookup back into
   `EntityFetcherDispatch.resolveType`.

`resolveType` reads `__typename` directly from the representation map
when the entity object is itself a map, and falls back to a per-type
constant when the entity is a returned domain object (every emitted
handler tags its result so the resolver does not need to introspect
domain object types).

**Batching.** Federation passes all representations from a single
`_entities` call into one `fetchEntities` invocation. The handler
groups by `__typename` first, then by matched `KeyAlternative` within
each type, and issues one query per `(type, alternative)` group. This
matches the batching shape `Query.nodes(ids:)` already uses and reuses
its DataLoader plumbing where the per-type fetcher already participates
in DataLoader keys.

Order preservation: the federation library expects the returned list
to align positionally with the input representations. The dispatcher
records the input index of each representation before grouping, then
re-sorts the merged result list to match.

**Consumer override hook (already in place).** The two-arg
`buildSchema(Consumer, Consumer<SchemaTransformer>)` overload from
`a24feb4` keeps its current shape. Phase 4 only changes what the
default `fetchEntities` and `resolveEntityType` *do*; the surface stays
the same. A consumer who calls `fed.fetchEntities(myCustomFetcher)`
replaces the Graphitron default; one who calls
`fed.resolveEntityType(myResolver)` replaces it. The customizer order
established at `a24feb4` (federation customizer runs after Graphitron
defaults attach) is unchanged, so an additive setter call wins over
the default.

**Tests.** The existing string-match tests in
`GraphitronSchemaClassGeneratorTest.federation_*` stay; Phase 4 adds
runtime tests:

- Pipeline: SDL with `extend schema @link(url: ".../federation/v2.x",
  import: ["@key"])` and a `Foo @key(fields: "id")` type emits a
  schema where `Query._entities(representations: [{__typename: "Foo",
  id: "1"}])` resolves to a `Foo` row from the test fixture DB.
- `_entities` over a `@node` type uses the NodeId path (assert via a
  spy on `NodeIdEncoder.decode`, or via a fixture row whose ID was
  encoded by the same encoder).
- `_entities` over a non-`@node` `@key` type uses the column-key path
  (assert via a fixture lookup).
- Multi-key: a `Foo @key(fields: "id") @key(fields: "sku")` type
  resolves both `[{__typename: "Foo", id: "1"}]` and
  `[{__typename: "Foo", sku: "X"}]`; assert the dispatcher selected
  the right alternative.
- Compound key: a `Foo @key(fields: "tenantId sku")` type resolves
  `[{__typename: "Foo", tenantId: "T", sku: "X"}]`; a representation
  that omits one of the two keys returns `null` (federation reports
  the resolution failure) rather than silently picking up a partial
  match.
- Most-specific-alternative tie-break: a type with `@key(fields: "id")
  @key(fields: "id sku")` and a representation containing both `id`
  and `sku` selects the compound alternative, not the single-key one.
- Order preservation: a single `_entities` call with three
  representations of mixed `__typename` returns results in the same
  order, with `null` slots for unresolvable representations.
- Consumer override: `buildSchema(b -> {}, fed ->
  fed.fetchEntities(myCustomFetcher))` actually replaces the default
  fetcher (assert via a fetcher that records its calls).
- Nested-selection-key rejection: `@key(fields: "owner { id }")`
  surfaces a `ValidationError` at classify time naming the directive
  and the `owner { id }` selection.
- Determinism: ratchet over the federated schema's printed SDL across
  two runs, mirroring `IdempotentWriterTest`.
- Non-federation regression guard: pipeline test on a schema with no
  `@link` returns the exact base `GraphQLSchema` reference (asserts
  the post-step is skipped, not just benign-noop).

### Phase 5: review hygiene and determinism

Findings from the `a24feb4` review that do not block Phase 4 but are
in scope for the overall goal. Each is a small, targeted change.

**Lazy-load the federation directive name set.**
`GraphitronSchemaBuilder.FEDERATION_DIRECTIVE_NAMES` is computed in a
static initialiser that calls into the federation library. If the
library ever fails to load definitions for the pinned URL, the entire
`GraphitronSchemaBuilder` class becomes unloadable and every build
pipeline path dies with `NoClassDefFoundError`, not just the
federation-using ones. Move the set behind a holder class so the
federation library is only touched when the recipe diagnostic actually
needs to inspect a directive name.

**Single source of truth for federation directive names.** Today there
are two: `GraphitronSchemaBuilder.FEDERATION_DIRECTIVE_NAMES` (loaded
from the library) and
`SchemaDirectiveRegistry.FEDERATION_DIRECTIVES` (hardcoded list of 11
names). Reconcile by deriving the hardcoded set from the same library
call, or by making the library-derived set the only one and routing
the survivor decision through it. Pick whichever keeps
`SchemaDirectiveRegistry` independent of the federation artifact at
runtime if that constraint still matters.

**Document the deviation from the spec's `FederationDirectives.allNames`
recommendation.** The shipped code uses
`FederationDirectives.loadFederationSpecDefinitions(URL)` instead of
`allNames`, because in v6.0.0 `allNames` is the Federation 1 set only
and would miss `@shareable`, `@inaccessible`, `@override`, `@tag`,
`@composeDirective`, and `@interfaceObject`. Add a one-line comment on
`loadFederationDirectiveNames()` so the next reader does not "fix" it
back.

**Tighten `buildRecipeErrors` mixed-error semantics.** When a
`SchemaProblem` mixes federation and non-federation undeclared-directive
entries, the current code converts every error in the bag to a
`ValidationError` with `RejectionKind.INVALID_SCHEMA`. The plan said
non-federation errors should "flow through unchanged". Either:
(a) document the trade-off in code (we cannot keep the
`SchemaProblem` and also throw `ValidationFailedException`, so the
preserved-message-but-rewrapped form is the chosen behaviour); or
(b) split into two passes, raise the recipe-rewrap `ValidationError`s,
and rethrow the original `SchemaProblem` for the rest. Pick (a) unless
a real consumer surfaces wanting the original exception type.

**Move `DEFAULT_FEDERATION_SPEC_URL` to a neutral location.**
`TagLinkSynthesiser` reaches into `FederationLinkApplier` for the URL
constant, inverting the runtime ordering (`TagLinkSynthesiser` runs
first). Either lift the constant into `FederationConstants` (or
similar) or accept the cross-coupling and pin it with a comment. Lean
toward extracting the constant; the coupling is invisible until
something else needs the URL.

**Pass `federationLink` from `apply` to `buildBundle`.**
`FederationLinkApplier.hasFederationLink(registry)` is called once in
the pipeline (after `apply`) and would re-walk the registry. Cheap
today, but the asymmetric "applier returns boolean / inspector returns
boolean / both walk the registry" shape invites future drift. Thread
the boolean through `RewriteContext` or a side-channel on the
`TypeDefinitionRegistry` so the second call disappears.

**Doc fixes in `getting-started.md`.**
- The intro line "your SDL opens with `extend schema @link(...)`"
  understates what the library accepts; a base `schema { ... } @link`
  also works. Reword to "your SDL declares an `@link` to a federation
  spec".
- The two-arg form's example only mentions `fetchEntities`. After
  Phase 4 lands the default `resolveEntityType` works for any type
  Graphitron classifies, but a consumer with a hand-rolled
  fetcher that returns domain objects must override
  `resolveEntityType` too. Add a one-liner noting this and pointing at
  the `SchemaTransformer` builder's setter.

**Runtime smoke test for the federation `build()` overload.** Even
before Phase 4 lands the dispatch, add a unit test that compiles the
emitted `GraphitronSchema` and invokes `build(b -> {}, fed -> {})`
against a tiny federated SDL, asserting `Federation.transform`
accepts the schema and the result has `_Service` and `_entities`
entries. Catches the federation-jvm API drift class of bug
independently of Phase 4 progress.

## Sequencing

Phase 5 is independent of Phase 4 and can land first, in parallel, or
after; each item is a small targeted change with no shared seams. The
recommended order is the runtime smoke test first (locks the existing
behaviour before Phase 4 starts moving things), then Phase 4 in one
push, then the remaining Phase 5 hygiene items. Splitting Phase 4
itself into "model + classify" and "runtime emission" sub-commits is
the implementer's call: the seam exists (the `EntityResolution` model
is a pure value type usable by tests in isolation) but the runtime
half is what consumers see, so a split-then-finish-immediately is
fine.

## Non-goals

- **Federation 1** (`@link`-less, hand-written `_service` SDL).
  The legacy `<removeFederationDefinitions>` flag carried this; the
  rewrite drops it. Consumers on Federation 1 must migrate to v2's
  `@link` form before adopting this plan.
- **Maintaining a Graphitron-side federation directive catalogue.**
  `LinkDirectiveProcessor` owns the directive set, the per-version
  gating, and the canonical declarations. We bump the
  `federation-graphql-java-support` dependency when the spec gains
  directives; we don't curate our own table. (Phase 5's "single source
  of truth" item is about removing today's accidental duplicate, not
  growing it.)
- **`@composeDirective` runtime support** beyond the directive
  declaration. Composing custom directives across subgraphs is a
  supergraph concern, handled by the gateway, not this subgraph.
- **Subgraph SDL artefact emission.** `_service.sdl` is reconstructed
  at runtime by `federation-graphql-java-support` from the programmatic
  schema; no build-time SDL artefact is emitted.
- **Nested-selection `@key(fields:)`**. The `a { b }` form, used for
  entity references that span types, is rejected at classify time.
  Lift the restriction in a follow-up plan if a real consumer surfaces
  needing it; the dispatcher's grouping logic would have to grow a
  recursive lookup path that is wholly unjustified speculation today.

## Open decisions

- **Default `resolveEntityType` shape.** Lean: handler-tagged domain
  objects (each emitted handler stamps its result so the resolver
  reads the type from the object) rather than a parallel map-walk. The
  alternative, "handlers return `Map<String, Object>` mirroring the
  representation", forces every consumer query for a federated entity
  through a map detour and breaks the per-type fetcher contract.
  Reviewer to confirm the tagging approach.
- **Where `EntityFetcherDispatch` lives.** Lean: emitted under
  `<outputPackage>.fetchers.EntityFetcherDispatch`, alongside the
  per-type fetcher emitters. Alternative: inline into
  `GraphitronSchema` itself. Inlining keeps the emitted-class count
  down but makes `GraphitronSchema`'s body grow with every entity
  type. Lean toward the standalone class.
- **DataLoader reuse for `KeyBacked` types.** Lean: route through the
  per-type fetcher's existing DataLoader plumbing, the same way
  `Query.nodes` does. If a real consumer surfaces wanting a separate
  per-`_entities`-call loader (because federation batches differ from
  in-query batches), lift it then; pre-emptive plumbing is out of
  scope.
- **Whether to keep both customizers in the docs first-client check.**
  Today's docs show `fetchEntities` only; after Phase 4 the default
  works without override, so the example arguably moves to a "rare
  override" section. Lean: keep the override example in `## Federation`
  but reword the lead so it reads as escape-hatch rather than
  required wiring.
