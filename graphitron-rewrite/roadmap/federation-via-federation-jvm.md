---
title: "Apollo Federation via federation-jvm transform"
status: Spec
priority: 1
---

# Apollo Federation via federation-jvm transform

> Finish Federation 2 entity dispatch. The build-time scaffolding
> from `a24feb4` emits a federation-wrapped schema via
> `Federation.transform`, but the default `fetchEntities` returns
> `List.of()` and the default `resolveEntityType` only works when the
> consumer's fetcher echoes the representation back. This plan makes
> `Graphitron.buildSchema(b -> {})` against a schema with `@key` or
> `@node` types resolve `_entities` natively, with per-tenant
> partitioning matching `QueryNodeFetcher.dispatchNodes`. A short
> hygiene pass on the shipped scaffold ships alongside.

## Goal

`Query._entities(representations: [_Any!]!): [_Entity]!` resolves at
runtime for every type Graphitron classifies, with no per-consumer
wiring beyond the existing `Graphitron.buildSchema(...)` call.

The two-arg `buildSchema(Consumer, Consumer<SchemaTransformer>)`
overload (emitted by `GraphitronSchemaClassGenerator` when
`Bundle.federationLink=true`) already exposes `Federation.transform`'s
builder so consumers can override defaults. The customizer order is
fixed: `federation-graphql-java-support`'s setters overwrite, and the
federation customizer runs after Graphitron's defaults attach, so a
consumer's `fed.fetchEntities(...)` or `fed.resolveEntityType(...)`
call replaces the default. This plan changes only what the defaults
*do*; the surface stays as-is.

For build-time context: `FederationLinkApplier` wraps
`LinkDirectiveProcessor.loadFederationImportedDefinitions` and runs
between `TagLinkSynthesiser` and `TagApplier` in `loadAttributedRegistry`;
`GraphitronSchemaBuilder.buildBundle` rewrites graphql-java's raw
"undeclared directive" `SchemaProblem`s into a recipe pointing at
`getting-started.md#build-time-federation-directives`; `Bundle`
carries a `federationLink` boolean parsed from the SDL.

## Implementation

**Classify-time model.** Add an `EntityResolution` value type
co-located with the existing classifier output. Every type whose
declaration carries `@key` or `@node` gets an `EntityResolution`
recorded against its name; types without either get none, and the
dispatcher routes them through to a federation-level "entity
resolution failed for type X" error.

Storage shape: a sidecar `Map<String, EntityResolution> entitiesByType`
on `GraphitronSchema`, paralleling the existing `fields` /
`fieldsByType` sidecars. The alternative (lifting an
`entityResolution()` accessor onto `TableBackedType`) blurs runtime
dispatch into the classification record hierarchy and forces every
permitted record to carry a federation-only optional; the sidecar
keeps `GraphitronType` records as immutable per-classification
snapshots, matches how field-level classify-time output is already
threaded, and confines federation knowledge to one map.

```java
record EntityResolution(String typeName, List<KeyAlternative> alternatives) {}

record KeyAlternative(
    List<String> requiredFields,
    boolean resolvable,
    Mode mode
) {
    sealed interface Mode permits NodeIdMode, ColumnsMode {}

    /** Decode requiredFields[0] as a base64 typed NodeId; route through
     *  NodeIdEncoder + the per-type SELECT path used by Query.node(s). */
    record NodeIdMode(String nodeTypeId, List<ColumnRef> nodeKeyColumns) implements Mode {}

    /** Treat each requiredField as a literal column value; route through
     *  a per-type SELECT-by-columns helper emitted alongside the dispatch. */
    record ColumnsMode(TableRef table, List<ColumnRef> columns) implements Mode {}
}
```

Treat `@node` as implying `@key(fields: "id", resolvable: true)` with
`NodeIdMode`: at classify time, every `NodeType` gets a synthesised
alternative `KeyAlternative(["id"], true, NodeIdMode(...))`.
Consumer-declared `@key` directives become `KeyAlternative(parsedFields,
parsedResolvable, ColumnsMode(...))`.

When a `@node` type also carries an explicit `@key(fields: "id", ...)`,
dedup by **dropping the synthesised alternative and promoting the
consumer's**: keep `parsedResolvable` from the directive and rewrite
its `Mode` to `NodeIdMode(...)` so the dispatcher still routes through
`NodeIdEncoder` rather than treating the literal `"id"` string as a
column value. This preserves the documented opt-out path (a consumer
who writes `@key(fields: "id", resolvable: false)` keeps the type out
of `_Entity`); silently preferring the synthesised resolvable=true
alternative would defeat that opt-out.

`KeyAlternative.resolvable` mirrors the federation-spec `resolvable`
argument (`@key(fields:, resolvable: Boolean = true)`). When `false`,
the dispatcher must skip this alternative during matching: the
subgraph declares the key for reference-only and must not attempt a
fetch. Federation treats the rep as non-resolvable here and surfaces
its own error.

Compound keys (`fields: "tenantId sku"`) become a single
`KeyAlternative` with multiple required fields. Multiple `@key`
directives (`@key(fields: "id") @key(fields: "sku")`) become multiple
alternatives. Nested selections (`fields: "owner { id }"`) are
rejected at classify time; see "Validating `@key(fields:)`" below.

**Build-time `@key` synthesis for `@node` types.** Since `@node`
implies `@key(fields: "id", resolvable: true)`, the build-time SDL
must show that directive on every `@node` type so the supergraph
composer sees the entity declaration. Implement as a small post-step
in `loadAttributedRegistry` (after `FederationLinkApplier` so the
`@key` definition is in scope): for each `@node` type that does not
already carry `@key(fields: "id")`, attach a synthesised one. The
"already-present" check honours the consumer-side opt-out — if the
SDL writes `@key(fields: "id", resolvable: false)`, no synthesis
fires, and the classify-time alternative carries `resolvable: false`
through to the dispatcher. If the schema has no federation `@link`,
skip the whole step; synthesising a `@key` without its declaration
would fail validation. Source location for the synthesised directive
points at the `@node`'s location so any downstream error message
stays meaningful.

**Parsing `@key(fields:)`.** The federation `fields:` grammar is a
strict subset of GraphQL selection-set syntax: a non-empty
whitespace-separated list of field names, optionally enclosed in
braces, optionally containing nested selections (which we reject per
non-goal). It does not allow aliases, arguments, variables, dotted
names, hash-comments, or string/numeric values.

Graphitron already ships
`no.sikt.graphitron.rewrite.selection.GraphQLSelectionParser`, but
that parser is built for a different caller and tolerates several
constructs forbidden by the federation grammar (dotted names like
`some.dotted.field`, aliases, arguments, hash-comments, variables).
Reusing it would force the federation path to defensively re-reject
each of those constructs, and would couple us to whatever the
selection parser grows in the future. Instead, copy the bones of its
implementation (the lexer's whitespace handling and name reader) into
a new purpose-built `FederationKeyFieldsParser` and drop the
extensions we do not need. Suggested home:
`no.sikt.graphitron.rewrite.schema.federation` (new package,
co-located with the other federation classify-time pieces).

The parser accepts:
- A naked field list: `id sku tenantId`
- A braced field list: `{ id sku }`
- Standard GraphQL whitespace and line terminators between names

The parser rejects (with `ValidationError` carrying the directive's
source location):
- Empty / whitespace-only input
- Any character other than ASCII whitespace, `{`, `}`, and standard
  GraphQL name characters (`[_A-Za-z][_0-9A-Za-z]*`)
- Nested selections: any `{` after a name token. Diagnostic names the
  offending field (e.g. `"@key(fields: \"owner { id }\"): nested
  selections are not supported on this subgraph; see Non-goals"`)
- Unbalanced or stray `{` / `}`

After parsing, the classifier resolves each field name to a
`ColumnRef` against the type's `@table`; an unresolvable name becomes
a `ValidationError` ("@key references unknown field 'X' on type Y").

Tests live alongside the parser. `GraphQLSelectionParser` is left
untouched. The Backlog [`selection-parser-audit`](selection-parser-audit.md)
item revisits whether `GraphQLSelectionParser` should be replaced
with graphql-java's own `Parser`; the federation `fields:` grammar is
narrower than even graphql-java exposes (no aliases, arguments,
variables, comments) and a parser bound to the federation rejection
diagnostics belongs separately regardless of how that audit lands.
The new `FederationKeyFieldsParser` does not gate on that audit.

**Classify-time wiring.** A new `EntityResolutionBuilder` (in
`no.sikt.graphitron.rewrite.schema.federation`, alongside
`FederationKeyFieldsParser`) invoked from `TypeBuilder` after the
`NodeType` and `TableType` enrichment passes walks the registry's
`@key` directives plus every `@node` type. It emits one
`EntityResolution` per entity-bearing type into the
`entitiesByType` sidecar map (see "Classify-time model" above), keyed
by GraphQL type name.

For `ColumnsMode` resolution, `EntityResolutionBuilder` resolves each
parsed field name to a `ColumnRef` against the type's `@table` by the
same name-to-column mapping used by `@lookupKey` arg resolution. An
unresolvable field name becomes a `ValidationError` (see above).
`@key` on a `TableInterfaceType` is rejected at this point (see
non-goals). Reuse of the *classification* helper
(`BuildContext.resolveColumnByName` and neighbours) is real; reuse of
the *generation* path (`LookupValuesJoinEmitter`,
`LookupMapping.ColumnMapping`) is *not* straightforward because those
are rooted at GraphQL argument names. Plan for a fresh per-type
SELECT-by-columns emitter (next paragraph), not a `LookupMapping`
reuse.

**Runtime emission.** `GraphitronSchemaClassGenerator` replaces the
two-arg `build()`'s placeholder lambdas with calls into a new
`EntityFetcherDispatch` helper class generated by a new
`EntityFetcherDispatchClassGenerator` in the `fetchers` subpackage:

```java
fb = Federation.transform(base)
    .setFederation2(true)
    .resolveEntityType(EntityFetcherDispatch::resolveType)
    .fetchEntities(EntityFetcherDispatch::fetchEntities);
federationCustomizer.accept(fb);
return fb.build();
```

`fetchEntities` returns `CompletableFuture<List<Object>>` so the
DataLoader path is available without later signature churn.

`EntityFetcherDispatch` holds an emitted `Map<String, EntityHandler>`
keyed by `__typename`, populated at class load time from one entry per
`EntityResolution` on the classified schema. Each `EntityHandler`:

1. Selects, **per representation**, the most-specific *resolvable*
   `KeyAlternative` whose `requiredFields` is a subset of the rep's
   keys (excluding `__typename`). Ties broken first by alternative
   size (more required fields wins), then by declaration order in the
   classified `alternatives` list. If no alternative matches, the rep
   yields `null` and federation surfaces the error.
2. After per-rep selection, groups reps by `(type, alternative,
   tenantId)` for batching, where `tenantId` is resolved per-rep via a
   single-rep DFE the same way `QueryNodeFetcher.dispatchNodes` builds
   one (`graphitronContext(repEnv).getTenantId(repEnv)`). Folding reps
   from different tenants into one SELECT would either return wrong
   rows or violate tenant isolation, depending on how the consumer's
   `getTenantId`/`getDslContext` wires up; partition before issuing
   SQL.
3. For a `NodeIdMode` group: peeks the typeId off each rep's `id`
   value via `NodeIdEncoder.peekTypeId`, then issues the same
   `hasIds(typeId, ids, keyColumns)` SELECT used by
   `QueryNodeFetcher.rowsNodes`. Reuse here requires lifting
   `rowsNodes` (or a subset of it) to a package-visible static entry
   point so the dispatcher can call it without reconstructing a
   per-id `DataFetchingEnvironment`; the lift is a real surface
   change in `QueryNodeFetcherClassGenerator`, not a free reuse.

   Selection-set scoping caveat: `rowsNodes` projects via
   `<TypeName>.$fields(env.getSelectionSet(), t, env)`. The
   `_entities` DFE's selection set is the union of every inline
   fragment across all `_Entity` types in the query, so each per-type
   call must extract only the fragment scoped to that `__typename`.
   The lifted entry point therefore takes a `DataFetchingFieldSelectionSet`
   that is already narrowed to the type being fetched (or a separate
   selected-fields list); it cannot pass the raw `_entities`
   selection set straight through. Implementer's call whether to
   narrow at the dispatcher (walk the selection set's
   `getFields("__typename/...")` once per type) or to extend the
   `<TypeName>.$fields` helper with a per-type-filter overload, but
   the call site must not assume the federation DFE behaves like a
   `Query.nodes` DFE.
4. For a `ColumnsMode` group: builds a `WHERE (col1, col2, ...) IN
   ((v1a, v2a, ...), (v1b, v2b, ...), ...)` clause and runs it
   against the type's table, projecting `inline("Foo").as("__typename")`
   alongside the columns the consumer's selection set asked for. The
   projection mechanism mirrors `QueryNodeFetcher.fetchById` so
   `resolveType` can read the type back uniformly (next bullet).
5. Returns each result row as a jOOQ `Record` whose synthetic
   `__typename` column carries the GraphQL type name. Order
   preservation: the dispatcher records the input index of each rep
   before grouping and re-scatters the merged results to match.

`resolveType` reads `__typename` off the entity. Two cases:
- The entity is a `Record` with a synthetic `__typename` column (the
  default-fetcher path). Read the column and look up the
  `GraphQLObjectType` by name.
- The entity is a `Map` (the consumer-override path, where the
  consumer's `fetchEntities` echoes the rep). Read `__typename` off
  the map.

Domain-object-typed entities (consumer overrides returning POJOs
without a `__typename` column or map key) are out of scope; consumers
in that case must register their own `resolveEntityType` via the
two-arg `buildSchema` overload. When the default `resolveType` falls
through (entity is neither `Record` nor `Map`, or carries no
`__typename`), throw a targeted `IllegalStateException` naming the
override that's missing ("custom `fetchEntities` returned a POJO; pair
it with `fed.resolveEntityType(...)` in the same `buildSchema` call;
see getting-started.md") rather than letting federation surface its
generic "could not determine type" error.

**Cross-field DataLoader sharing.** Out of scope. The existing
per-type DataLoaders are keyed by `getTenantId(idEnv) + "/" + path`,
and `_entities`'s DFE path (`Query._entities[i]`) does not match any
concrete `Query.foos[i]` path, so loaders would not coalesce
naturally. If a real consumer surfaces a need, a
per-`_entities`-scoped loader can be added in a follow-up;
pre-emptive plumbing is unjustified. (Within `_entities` the
`(type, alternative, tenantId)` grouping above already gives one
SELECT per group.)

**`getting-started.md` updates.** The two-arg-form example
(`getting-started.md:94-105`) currently mentions `fetchEntities` only.
After this lands, the default `fetchEntities` works for every type
Graphitron classifies, but a consumer with a hand-rolled
`fetchEntities` returning POJOs must override `resolveEntityType` too.
Reword the lead as escape-hatch ("if you have entity types Graphitron
does not classify, or want non-default resolution") and add a
one-liner pointing at `resolveEntityType` for the POJO-fetcher case.
The cosmetic `@link`-wording fix ships with the hygiene pass below.

**Tests.** The existing string-match tests in
`GraphitronSchemaClassGeneratorTest.federation_*` stay. New runtime
tests:

- Pipeline: SDL with `extend schema @link(url: ".../federation/v2.x",
  import: ["@key"])` and a `Foo @key(fields: "id")` type emits a
  schema where `Query._entities(representations: [{__typename: "Foo",
  id: "1"}])` resolves to a `Foo` row from the test fixture DB.
- `_entities` over a `@node` type uses the NodeId path (fixture row
  whose ID was produced by the same encoder; assert no
  `ColumnsMode` SELECT was issued via a query-counting fixture).
- `_entities` over a non-`@node` `@key` type uses the columns path.
- Multi-key: a `Foo @key(fields: "id") @key(fields: "sku")` type
  resolves both `[{__typename: "Foo", id: "1"}]` and
  `[{__typename: "Foo", sku: "X"}]`; assert the dispatcher selected
  the right alternative.
- Compound key: a `Foo @key(fields: "tenantId sku")` type resolves
  `[{__typename: "Foo", tenantId: "T", sku: "X"}]`; a representation
  that omits one of the two keys returns `null` (federation reports
  the resolution failure) rather than silently picking up a partial
  match.
- Most-specific tie-break: a type with `@key(fields: "id") @key(fields:
  "id sku")` and a representation containing both `id` and `sku`
  selects the compound alternative.
- `@node` + explicit `@key(fields: "id")` dedup: assert the type
  classifies with one alternative, not two; the consumer's directive
  wins (NodeIdMode preserved, `resolvable` carries through).
- `@node` + explicit `@key(fields: "id", resolvable: false)` opt-out:
  the type drops out of `_entities` resolution. A representation for
  it does not run a SELECT (query-counting fixture) and federation
  surfaces its own error. Regression guard for the dedup rule.
- `@key(resolvable: false)` on a non-`@node` type: a representation
  matching the non-resolvable alternative does not run a SELECT;
  federation surfaces its own error.
- Multi-tenancy: a single `_entities` call with two reps of the same
  `__typename` whose ids resolve to different tenants issues two
  SELECTs (one per tenant), not one. Use the same per-id DFE-rebinding
  shape `QueryNodeFetcher.dispatchNodes` uses, asserted via a
  `getTenantId` stub that records calls plus the query-counting
  fixture.
- Classified-but-non-entity type: a rep whose `__typename` names a
  type Graphitron classifies but which has no `@key` or `@node`
  yields `null` and federation surfaces "entity resolution failed for
  type X"; no NPE.
- Order preservation: a single `_entities` call with three
  representations of mixed `__typename` returns results in the same
  order, with `null` slots for unresolvable reps.
- Empty representations: `_entities(representations: [])` returns
  `[]` cleanly.
- Unknown `__typename`: a rep whose `__typename` is not in the schema
  surfaces a federation-level error; no NPE in the dispatcher.
- DataLoader / batch shape: two reps of the same type and same
  alternative result in one SQL execution (query-counting fixture).
- Consumer override: `buildSchema(b -> {}, fed ->
  fed.fetchEntities(myCustomFetcher))` actually replaces the default
  fetcher (assert via a fetcher that records its calls).
- Consumer override returning a `Map`: `resolveType` reads
  `__typename` off the map (regression guard for the `a24feb4`
  placeholder semantics).
- Compound key with non-String column types (Int, custom scalar):
  coercion path covered.
- Nested-selection-key rejection: `@key(fields: "owner { id }")`
  surfaces a `ValidationError` at classify time naming the directive
  and the `owner { id }` selection.
- Malformed `fields:` strings: empty string, whitespace-only,
  unbalanced braces, dotted-path (`"owner.id"`), aliased
  (`"foo: id"`), with arguments (`"id(x: 1)"`), comments (`"# nope"`),
  variables (`"$id"`) all surface targeted `ValidationError`s pointing
  at the `@key` directive's source location.
- Build-time `@key(fields: "id")` synthesis for `@node` types is
  visible in the printed SDL (a federated schema's `_Service.sdl`
  output names `@key(fields: "id")` on every `@node` type, even when
  the consumer did not write it).
- Non-federation regression guard: pipeline test on a schema with no
  `@link` returns the exact base `GraphQLSchema` reference (asserts
  the post-step is skipped, not just benign-noop).
- `@link`-but-no-entities regression guard: SDL with `@link` but no
  `@key`/`@node` types builds successfully and `_entities([])`
  returns `[]`; the dispatcher's handler map is empty, not broken.
- Determinism is already covered by `IdempotentWriterTest` running
  over the full emitted source set; no separate federation-only
  ratchet is needed.

## Hygiene and determinism

Review findings on the shipped scaffold that don't block entity
dispatch but are in scope for the overall goal. Each is a small,
targeted change.

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
`ValidationError` with `RejectionKind.INVALID_SCHEMA`, losing the
original exception type for the non-federation half. Either:
(a) document the trade-off in code (we cannot keep the `SchemaProblem`
and also throw `ValidationFailedException`, so the preserved-
message-but-rewrapped form is the chosen behaviour); or
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
the pipeline (after `apply`) and re-walks the registry. Cheap today,
but the asymmetric "applier returns boolean / inspector returns
boolean / both walk the registry" shape invites future drift. Make
`apply` return the boolean and store it on `RewriteContext`; readers
go through the context, the registry-walking inspector method
disappears. (Side-channelling on `TypeDefinitionRegistry` works too
but couples a graphql-java type to Graphitron metadata; the context
is the right home.)

**Doc fix in `getting-started.md` (cosmetic).** The intro line "your
SDL opens with `extend schema @link(...)`" understates what the
library accepts; a base `schema { ... } @link` also works. Reword to
"your SDL declares an `@link` to a federation spec". (The two-arg
form's example reword ships with the entity-dispatch landing; see the
implementation section's `getting-started.md` block.)

**Runtime smoke test for the federation `build()` overload.** A unit
test that compiles the emitted `GraphitronSchema` and invokes
`build(b -> {}, fed -> {})` against a tiny federated SDL, asserting
`Federation.transform` accepts the schema and the result has
`_Service` and `_entities` entries. Land this first: it locks the
existing scaffold's behaviour before the dispatch work moves things,
and catches the federation-jvm API drift class of bug independently.

## Sequencing

Hygiene items are independent of the dispatch work; each is a small
targeted change with no shared seams. Recommended order: runtime
smoke test first, then the dispatch in one push, then the remaining
hygiene items. The dispatch itself has three plausible seams the
implementer can split or fold at their discretion:

1. `EntityResolution` model + `FederationKeyFieldsParser` +
   `@node`-implies-`@key(fields: "id")` synthesis. Pure value types
   and classify-time logic, testable in isolation.
2. `QueryNodeFetcher.rowsNodes` lift to a package-visible static
   helper that takes `(keys, dsl, selectionSet)` instead of pulling
   them off a `DataFetchingEnvironment`. No behaviour change.
3. `EntityFetcherDispatchClassGenerator` plus the schema-builder
   wire-up that replaces the placeholder lambdas, plus
   `getting-started.md` updates.

The runtime half is what consumers see, so don't sit on (1) and (2)
for long.

## Non-goals

- **Federation 1** (`@link`-less, hand-written `_service` SDL).
  The legacy `<removeFederationDefinitions>` flag carried this; the
  rewrite drops it. Consumers on Federation 1 must migrate to v2's
  `@link` form before adopting this plan.
- **Maintaining a Graphitron-side federation directive catalogue.**
  `LinkDirectiveProcessor` owns the directive set, the per-version
  gating, and the canonical declarations. We bump the
  `federation-graphql-java-support` dependency when the spec gains
  directives; we don't curate our own table. (The hygiene pass's
  "single source of truth" item is about removing today's accidental
  duplicate, not growing it.)
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
- **Subgraph-private `@node` types.** Every `@node` type becomes an
  entity by default (synthesised `@key(fields: "id", resolvable:
  true)` when a federation `@link` is present). A consumer who wants
  a globally-identified `@node` type kept out of `_Entity` must
  declare `@key(fields: "id", resolvable: false)` themselves; the
  synthesis step skips, and the dedup rule preserves the
  `resolvable: false` flag. True opt-out via a Graphitron-side
  directive is a follow-up plan if anyone asks.
- **`@interfaceObject`.** v2's interface-entity surface is not
  classified or dispatched. Subgraphs that need it must register their
  own `fetchEntities`/`resolveEntityType`. Lift in a follow-up plan
  if a real consumer surfaces.
- **`TableInterfaceType` as a federation entity.** `@key` on an
  interface-typed Graphitron classification is rejected at classify
  time; declare `@key` on the implementations instead. The dispatcher
  has no recursive fan-out across implementations, and adding one is
  unjustified speculation today. (`EntityResolutionBuilder` resolves
  `ColumnsMode` columns against the `@table` of `TableType` /
  `NodeType` only; `TableInterfaceType` is not a permitted carrier.)

## Open decisions

- **POJO-fetcher `resolveType`.** Default left out of scope: a
  consumer override returning POJOs without a `__typename` column or
  map key must pair its `fetchEntities` with a custom
  `resolveEntityType`. The default `resolveType` raises a targeted
  `IllegalStateException` naming the missing override (see
  Implementation). Reviewer to confirm or push back if a default
  introspection path is wanted instead of the targeted error.
