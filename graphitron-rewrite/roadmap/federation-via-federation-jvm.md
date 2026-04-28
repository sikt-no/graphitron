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

**Classify-time model.** Add an `EntityResolution` value type
co-located with the existing classifier output. Every type whose
declaration carries `@key` or `@node` gets an `EntityResolution`
attached to its classified `GraphitronType` entry; types without
either get none, and the dispatcher routes them through to a
federation-level "entity resolution failed for type X" error.

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

The single variant collapses the earlier "NodeBacked vs KeyBacked"
split. Treat `@node` as implying `@key(fields: "id", resolvable: true)`
with `NodeIdMode`: at classify time, every `NodeType` automatically
gets a synthesised first alternative `KeyAlternative(["id"], true,
NodeIdMode(...))`. Consumer-declared `@key` directives become
`KeyAlternative(parsedFields, parsedResolvable, ColumnsMode(...))`. If
a `@node` type also carries an explicit `@key(fields: "id")`, dedup
in favour of the synthesised NodeId alternative (one alternative, not
two with the same `requiredFields`).

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
already carry `@key(fields: "id")`, attach a synthesised one. If the
schema has no federation `@link`, skip; synthesising a `@key`
without a declaration would fail validation. Source location for the
synthesised directive points at the `@node`'s location so any
downstream error message stays meaningful.

**Validating `@key(fields:)` via `GraphQLSelectionParser`.** Use the
existing `no.sikt.graphitron.rewrite.selection.GraphQLSelectionParser`
(it already supports the *naked* selection-set form `id sku` without
braces, which is exactly the `@key(fields:)` shape). When using it
for federation, the parser's tolerance becomes our problem; the
federation spec's `fields:` grammar is a strict subset. Apply these
rules after `parse(...)` returns:

- Parser throws `GraphQLSelectionParseException` → `ValidationError`,
  message preserves the parse exception text and points at the `@key`
  directive's source location.
- `parse(...)` returns an empty list → `ValidationError`,
  "@key(fields:) cannot be empty".
- For each top-level `ParsedField`:
  - `hasAlias()` → reject ("aliases not permitted in @key fields").
  - `hasArguments()` → reject ("arguments not permitted in @key fields").
  - `hasSelectionSet()` → reject ("nested selections not permitted; see Non-goals").
  - `name()` contains `.` → reject. The parser's lexer treats dots as
    name characters (`some.dotted.field` is one NAME token), so a
    user-typed `@key(fields: "owner.id")` parses silently otherwise.
- Field name not present on the type → `ValidationError`,
  "@key references unknown field 'X' on type Y".

Document these rules in `GraphQLSelectionParser`'s class javadoc with
a short "Used by" note pointing at the federation entity-resolution
path so the next reader does not relax the parser without considering
this caller. The parser is otherwise a pure static function (no shared
state, no I/O); its existing extensions (dotted names, hash-comments,
commas) are tolerable in this caller as long as the post-parse walk
catches them.

**Classify-time wiring.** A new `EntityResolutionBuilder` invoked from
`TypeBuilder` after the `NodeType` and `TableType` enrichment passes
walks the registry's `@key` directives plus every `@node` type. It
emits one `EntityResolution` per entity-bearing type and lands it on
the type's `GraphitronType` entry via a small extension on
`TableBackedType` (a `entityResolution()` accessor returning
`EntityResolution` or `null`).

For `ColumnsMode` resolution, `EntityResolutionBuilder` resolves each
parsed field name to a `ColumnRef` against the type's `@table` (or
the discriminator table for `TableInterfaceType`) by the same name-to-
column mapping used by `@lookupKey` arg resolution. An unresolvable
field name becomes a `ValidationError` (see above). Reuse of the
*classification* helper (`BuildContext.resolveColumnByName` and
neighbours) is real; reuse of the *generation* path
(`LookupValuesJoinEmitter`, `LookupMapping.ColumnMapping`) is *not*
straightforward because those are rooted at GraphQL argument names.
Plan for a fresh per-type SELECT-by-columns emitter (next paragraph),
not a `LookupMapping` reuse.

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
   keys (excluding `__typename`). Ties broken by alternative size; if
   no alternative matches, the rep yields `null` and federation
   surfaces the error.
2. After per-rep selection, groups reps by `(type, alternative)` for
   batching.
3. For a `NodeIdMode` group: peeks the typeId off each rep's `id`
   value via `NodeIdEncoder.peekTypeId`, then issues the same
   `hasIds(typeId, ids, keyColumns)` SELECT used by
   `QueryNodeFetcher.rowsNodes`. Reuse here requires lifting
   `rowsNodes` (or a subset of it) to a package-visible static entry
   point so the dispatcher can call it without reconstructing a
   per-id `DataFetchingEnvironment`; the lift is a real surface
   change in `QueryNodeFetcherClassGenerator`, not a free reuse.
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
  the map. This subsumes the current placeholder `resolveEntityType`
  behaviour from `a24feb4`.

Domain-object-typed entities (consumer overrides returning POJOs
without a `__typename` column or map key) are out of scope; consumers
in that case must register their own `resolveEntityType` via the
two-arg `buildSchema` overload. Document in `getting-started.md`.

**Batching and DataLoader scope.** Within a single `_entities` call,
`(type, alternative)` grouping issues one SELECT per group, matching
the batching shape `Query.nodes(ids:)` uses internally. Cross-field
DataLoader sharing with sibling per-field fetches in the same request
is **not** in scope for Phase 4: the existing per-type DataLoaders are
keyed by `getTenantId(idEnv) + "/" + path`, and `_entities`'s DFE path
(`Query._entities[i]`) does not match any concrete `Query.foos[i]`
path, so loaders would not coalesce naturally. If a real consumer
surfaces a need, a per-`_entities`-scoped loader can be added in a
follow-up; pre-emptive plumbing is unjustified.

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

**`getting-started.md` updates that ship with Phase 4.** The two-arg-
form example (`getting-started.md:94-105`) currently mentions
`fetchEntities` only. After Phase 4 the default `fetchEntities` works
for every type Graphitron classifies, but a consumer with a
hand-rolled `fetchEntities` returning POJOs must override
`resolveEntityType` too. Reword the lead as escape-hatch ("if you have
entity types Graphitron does not classify, or want non-default
resolution") and add a one-liner pointing at `resolveEntityType` for
the POJO-fetcher case. The `@link`-wording cosmetic ("your SDL opens
with...") stays in Phase 5.

**Tests.** The existing string-match tests in
`GraphitronSchemaClassGeneratorTest.federation_*` stay; Phase 4 adds
runtime tests:

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
  classifies with one alternative, not two; the NodeId-mode one wins.
- `@key(resolvable: false)`: a representation matching the
  non-resolvable alternative does not run a SELECT (assert via the
  query-counting fixture); federation surfaces its own error.
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
  trailing brace, dotted-path (`"owner.id"`), aliased
  (`"foo: id"`), with arguments (`"id(x: 1)"`) all surface targeted
  `ValidationError`s; no `GraphQLSelectionParseException` leaks
  unwrapped.
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

**Doc fix in `getting-started.md` (cosmetic).** The intro line "your
SDL opens with `extend schema @link(...)`" understates what the
library accepts; a base `schema { ... } @link` also works. Reword to
"your SDL declares an `@link` to a federation spec". (The two-arg
form's example reword ships in Phase 4; see the Phase 4 section's
`getting-started.md` block.)

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
itself into sub-commits is the implementer's call. Three plausible
seams exist:
1. `EntityResolution` model + `@key(fields:)` parsing/validation +
   `@node`-implies-`@key(fields: "id")` synthesis. Pure value types and
   classify-time logic, testable in isolation.
2. `QueryNodeFetcher` lift (move `rowsNodes`'s SELECT internals to a
   package-visible static helper, no behaviour change).
3. `EntityFetcherDispatchClassGenerator` plus the schema-builder wire-
   up that replaces the placeholder lambdas, plus `getting-started.md`
   updates.

Land as one commit or three; the seams are real but the runtime half
is what consumers see, so don't sit on (1) and (2) for long.

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
- **Subgraph-private `@node` types.** Phase 4 makes every `@node`
  type an entity (synthesises `@key(fields: "id", resolvable: true)`
  when a federation `@link` is present). A consumer who wants a
  globally-identified `@node` type kept out of `_Entity` must
  declare `@key(fields: "id", resolvable: false)` themselves, which
  the synthesis step respects (it only inserts when no `@key` already
  exists). True opt-out via a Graphitron-side directive is a follow-up
  plan if anyone asks.

## Open decisions

- **Where `EntityFetcherDispatch` lives.** Lean: emitted under
  `<outputPackage>.fetchers.EntityFetcherDispatch`, alongside the
  per-type fetcher emitters. Alternative: inline into
  `GraphitronSchema` itself. Inlining keeps the emitted-class count
  down but makes `GraphitronSchema`'s body grow with every entity
  type. Lean toward the standalone class.
- **`QueryNodeFetcher` reuse surface.** Lean: lift `rowsNodes`'s
  per-typeId SELECT logic to a package-visible static helper on
  `QueryNodeFetcher` so `EntityFetcherDispatch` can call it without
  reconstructing a per-id `DataFetchingEnvironment`. Alternative:
  duplicate the SELECT shape inside the dispatcher. Lean toward the
  lift; the duplication risk is high otherwise.
- **POJO-fetcher `resolveType`.** Lean: out of scope; consumer
  overrides returning POJOs without a synthetic `__typename` column
  must register their own `resolveEntityType` via the two-arg
  `buildSchema` overload. Documented in `getting-started.md`. Reviewer
  to confirm or push back if a default introspection path is wanted.
