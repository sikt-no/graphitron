---
title: "Apollo Federation via federation-jvm transform"
status: In Progress
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
record EntityResolution(String typeName, TableRef table, List<KeyAlternative> alternatives) {}

record KeyAlternative(
    List<String> requiredFields,
    List<ColumnRef> columns,
    boolean resolvable,
    KeyShape shape
) {
    /**
     * Maps the rep's required-field values to the column values used by
     * the SELECT. Two shapes; the dispatch path is otherwise identical
     * (`Query.node` / `Query.nodes` and `_entities` all hit the same
     * per-type SELECT-by-columns emitter, see "Runtime emission" below).
     */
    enum KeyShape {
        /** requiredFields.size() == columns.size(); rep field values map
         *  index-by-index to column values. Consumer-declared @key path. */
        DIRECT,
        /** requiredFields == ["id"]; the rep's id is a base64 NodeId
         *  decoded by NodeIdEncoder into the columns list. @node path
         *  (whether synthesised or carried by an explicit
         *  `@key(fields: "id")` on a @node type). */
        NODE_ID
    }
}
```

Treat `@node` as implying `@key(fields: "id", resolvable: true)` with
`NODE_ID` shape: at classify time, every `NodeType` gets a synthesised
alternative `KeyAlternative(["id"], <node-key-columns>, true, NODE_ID)`.
Consumer-declared `@key` directives become
`KeyAlternative(parsedFields, <resolved-columns>, parsedResolvable, DIRECT)`.

When a `@node` type also carries an explicit `@key(fields: "id", ...)`,
dedup by **dropping the synthesised alternative and promoting the
consumer's**: keep `parsedResolvable` from the directive and pin the
shape to `NODE_ID` so the dispatcher still decodes `"id"` through
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
untouched and the new parser stands on its own; the federation
`fields:` grammar is narrow enough (no nesting, aliases, arguments,
variables, comments) that a purpose-built parser tied to the
federation rejection diagnostics is the right shape regardless of
what happens to the existing selection parser.

**Classify-time wiring.** A new `EntityResolutionBuilder` (in
`no.sikt.graphitron.rewrite.schema.federation`, alongside
`FederationKeyFieldsParser`) invoked from `TypeBuilder` after the
`NodeType` and `TableType` enrichment passes walks the registry's
`@key` directives plus every `@node` type. It emits one
`EntityResolution` per entity-bearing type into the
`entitiesByType` sidecar map (see "Classify-time model" above), keyed
by GraphQL type name.

For `DIRECT`-shape alternatives, `EntityResolutionBuilder` resolves
each parsed field name to a `ColumnRef` against the type's `@table` by
the same name-to-column mapping used by `@lookupKey` arg resolution.
An unresolvable field name becomes a `ValidationError` (see above).
For `NODE_ID`-shape alternatives, the columns list is the type's
existing node-key columns (already classified onto `NodeType`), reused
verbatim. `@key` on a `TableInterfaceType` is rejected at this point
(see non-goals).

Reuse of the *classification* helper (`BuildContext.resolveColumnByName`
and neighbours) is real. Reuse of the *generation* path
(`LookupValuesJoinEmitter`, `LookupMapping.ColumnMapping`) is not a
drop-in: those are rooted at GraphQL argument names. The dispatcher's
SELECT emitter is a sibling that shares the same SQL shape (a
`VALUES (idx, col1, col2, ...)` derived table joined to the target
table, ordered by `idx`; see "Runtime emission" below) without going
through the argument-name plumbing.

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
3. **Decodes each rep into a column-value row.** `DIRECT`-shape
   alternatives copy the rep's required-field values index-by-index
   into the column-value row. `NODE_ID`-shape alternatives pass the
   rep's `id` through `NodeIdEncoder.decode(__typename, id)`, which
   yields the column values directly because we already know the type
   (no per-id typeId-to-`__typename` peek is needed inside
   `_entities`; the rep brought `__typename`). If `decode` returns
   nothing or the encoded typeId disagrees with `__typename`, the rep
   yields `null` and never enters any group's VALUES table; federation
   surfaces its own resolution-failure error. After step 3, every
   surviving rep has a column-value row plus its original
   pre-grouping index in the federation `representations` list.
4. **Issues one SELECT per `(type, alternative, tenantId)` group via a
   `VALUES (idx, col1, col2, ...)` derived table joined to the type's
   `@table` and ordered by `idx`.** Same SQL shape
   `LookupMapping.ColumnMapping`, `SplitRowsMethodEmitter`, and
   `TypeFetcherGenerator` already emit elsewhere in the rewrite
   (`TypeFetcherGenerator.java:1340-1351`); the dispatcher uses a
   sibling emitter that is not rooted at GraphQL argument names.
   Projection includes `inline("Foo").as("__typename")` plus
   `<TypeName>.$fields(env.getSelectionSet(), table, env)`, the same
   per-participant projection shape
   `TypeFetcherGenerator.buildInterfaceFieldsList` uses for
   single-table interfaces (`TypeFetcherGenerator.java:748-766`).
   graphql-java's `DataFetchingFieldSelectionSet` is type-scoped, so
   `$fields` walking the unioned `_entities` selection set picks up
   only the inline fragment scoped to that `__typename`; no extra
   narrowing step is needed. No cross-`__typename` batching: one
   SELECT per group keeps the projection logic, the typename literal,
   and the `<TypeName>.$fields` invocation each pinned to one type. A
   future cross-type union (when table and key columns coincide) is a
   follow-up if a real consumer's `_entities` shape surfaces the
   need.
5. **Scatters results back to original positions via the derived
   table's `idx` column.** Federation's contract is exact: from the
   subgraph spec, "`Query._entities` must return a list of entity
   objects that correspond to the provided representations, in the
   exact same order. Entries in the list can be null if no entity
   exists for a provided representation." Carrying `idx` through SQL
   means each result row arrives index-tagged; the dispatcher
   allocates a fixed-size `Object[representations.size()]` and slots
   each row into `result[row.idx]`. Reps that yielded `null` at step
   3 (no resolvable alternative matched, or NodeId typeId
   disagreement) never entered any VALUES table and stay `null` by
   construction. Choosing the derived-table batching primitive over
   `WHERE row(...) IN (...)` is deliberate: the index-carrying join
   makes order preservation a SQL property, not a Java post-processing
   step, so the federation contract holds without a separate scatter
   pass.

**`Query.node` and `Query.nodes` reuse this dispatcher.**
`NodeIdEncoder.peekTypeId(id)` recovers the `__typename` from an
opaque id, so the existing fetchers fold into the entity dispatcher:
for each id, peek typeId → look up `__typename` → synthesise one rep
`{__typename, id}` → hand the list to the entity dispatcher. The
`idx`-carrying derived-table SELECT covers `Query.nodes`'s
exact-position contract the same way it covers federation's. The
existing `rowsNodes` body in `QueryNodeFetcherClassGenerator` is
*replaced* by the dispatcher call rather than lifted to a
package-visible entry point; the per-typeId loop disappears because
the dispatcher already groups by `(type, alternative, tenantId)`.

**`resolveType` reads `__typename` off the entity Record.** The
default fetcher's projection always includes the
`inline("Foo").as("__typename")` literal (step 4), so every entity
the default path returns is a jOOQ `Record` carrying the column.
`resolveType` reads it and looks up the `GraphQLObjectType` by name.
If a consumer overrides `fetchEntities` and returns something else
(`Map`, POJO, etc.), the default `resolveType` returns `null` and
federation surfaces its own resolution-failure error. We do not
expose `resolveEntityType` as a Graphitron customization extension
point; consumers who need richer type resolution can call
`fb.resolveEntityType(...)` from the federation customizer (the
SchemaTransformer API permits it), but the behaviour is not
documented or tested as a supported feature, and no `Map`-fallback
or POJO-introspection path is shipped. Lift to a first-class
extension point in a follow-up plan if a real consumer surfaces
needing it.

**Cross-field DataLoader sharing.** Out of scope. The existing
per-type DataLoaders are keyed by `getTenantId(idEnv) + "/" + path`,
and `_entities`'s DFE path (`Query._entities[i]`) does not match any
concrete `Query.foos[i]` path, so loaders would not coalesce
naturally. If a real consumer surfaces a need, a
per-`_entities`-scoped loader can be added in a follow-up;
pre-emptive plumbing is unjustified. (Within `_entities` the
`(type, alternative, tenantId)` grouping above already gives one
SELECT per group.)

**`getting-started.md` updates.** Two changes ship with the
dispatch landing:

- The two-arg-form example (`getting-started.md:94-105`) currently
  mentions `fetchEntities` only. After this lands, the default
  `fetchEntities` works for every type Graphitron classifies; the
  two-arg form remains an escape hatch for entity types Graphitron
  does not classify. Reword the lead as escape-hatch ("if you have
  entity types Graphitron does not classify, supply your own
  `fetchEntities` here") and note that custom fetchers must return
  jOOQ `Record`s with a `__typename` column for the default
  `resolveEntityType` to recognise them. Do not promote
  `resolveEntityType` as a customizable extension point (see
  Non-goals).
- Cosmetic: the intro line "your SDL opens with `extend schema
  @link(...)`" understates what the library accepts; a base
  `schema { ... } @link` also works. Reword to "your SDL declares an
  `@link` to a federation spec".

**Tests.** The existing string-match tests in
`GraphitronSchemaClassGeneratorTest.federation_*` stay. New runtime
tests:

- Pipeline: SDL with `extend schema @link(url: ".../federation/v2.x",
  import: ["@key"])` and a `Foo @key(fields: "id")` type emits a
  schema where `Query._entities(representations: [{__typename: "Foo",
  id: "1"}])` resolves to a `Foo` row from the test fixture DB.
- `_entities` over a `@node` type uses the NODE_ID shape: rep carries
  a base64 id produced by the encoder; assert the SQL bound parameters
  match the decoded column values (not the raw id string), via a
  query-capturing fixture.
- `_entities` over a non-`@node` `@key` type uses the DIRECT shape:
  rep carries literal column values, dispatched through the same
  `VALUES (idx, ...)` derived-table SELECT.
- `Query.node` and `Query.nodes` go through the same dispatcher: a
  fixture that intercepts the dispatcher entry point sees calls from
  both surfaces; the per-typeId loop in `QueryNodeFetcher.rowsNodes`
  is gone (asserted by absence in the emitted source).
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
  wins, the alternative's shape stays `NODE_ID`, and `resolvable`
  carries through.
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
  order, with `null` slots for unresolvable reps. The derived
  table's `idx` column is the mechanism; assert by inspecting the
  emitted SQL that `idx` is in the SELECT list and the outer query
  orders by it.
- Empty representations: `_entities(representations: [])` returns
  `[]` cleanly.
- Unknown `__typename`: a rep whose `__typename` is not in the schema
  surfaces a federation-level error; no NPE in the dispatcher.
- DataLoader / batch shape: two reps of the same type and same
  alternative result in one SQL execution (query-counting fixture).
- Consumer override: `buildSchema(b -> {}, fed ->
  fed.fetchEntities(myCustomFetcher))` actually replaces the default
  fetcher (assert via a fetcher that records its calls).
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

**Delegate applied-directive value coercion to graphql-java.**
`AppliedDirectiveEmitter` translates each applied-directive argument's
`InputValueWithState` to an AST literal that federation-jvm later casts
to a specific subtype (`BooleanValue`, `StringValue`, `IntValue`, etc.).
Doing the translation type-by-type in Graphitron means re-implementing
the per-scalar AST shape that graphql-java already owns through
`Coercing#valueToLiteral`, and getting it wrong for any scalar
(`FloatValue`, custom scalars, input objects, internally-coerced enums,
Long-range Ints) we forget to enumerate.

Delegate the whole conversion to graphql-java instead:

```java
private static CodeBlock emitAstLiteralValue(GraphQLAppliedDirectiveArgument arg) {
    Value<?> ast = ValuesResolver.valueToLiteral(
        arg.getArgumentValue(),
        arg.getType(),
        GraphQLContext.getDefault(),
        Locale.getDefault());
    return CodeBlock.of("$T.parseValue($S)", PARSER, AstPrinter.printAst(ast));
}
```

`ValuesResolver.valueToLiteral` dispatches through each scalar's
`Coercing#valueToLiteral` to produce a canonical `Value<?>`;
`AstPrinter.printAst` renders it; `Parser.parseValue` reparses it at
class-init time on the consumer side. Custom scalars (federation-namespaced
`FieldSet`, `Policy`, `Scope`, plus any user-defined scalar) work because
their own `Coercing` decides the AST shape; `Float`, `BigDecimal`, input
objects, and internally-coerced enums all get the right shape without
further enumeration. Runtime cost is one `Parser.parseValue` per applied
argument at class-init time, negligible against schema build.

The hand-rolled `emitAstLiteralValue` / `emitAstNode` / `emitArrayAstNode`
/ `javaValueToAstNode` cases delete with this change.
`GraphQLValueEmitter`'s Javadoc still claims a connection to
`AppliedDirectiveEmitter` that no longer holds; update it. The
`emitInputType` method stays (the `.type(...)` slot on
`GraphQLAppliedDirectiveArgument.Builder` is structural and orthogonal
to the value coercion).

**Runtime smoke test for the federation `build()` overload.** A pipeline
test that compiles a federation-enabled `GraphitronSchema` and invokes
`build(b -> {}, fed -> {})`, asserting `Federation.transform` accepts the
schema and the federation surface is present. Land first: locks the
existing scaffold before the dispatch work moves things, and catches the
federation-jvm API drift class of bug independently.

*Test fixture isolation.* The federated SDL is a separate file from the
shared module fixture (`graphitron-test/src/main/resources/graphql/schema.graphqls`).
Configure a second `graphitron-maven` execution in `graphitron-test/pom.xml`
that points at the federated SDL (e.g.
`src/main/resources/graphql/federated-schema.graphqls`) and generates into
a distinct output package (e.g. `no.sikt.graphitron.generated.federated`).
The smoke test imports the federated facade from that package;
non-federation tests keep importing from `no.sikt.graphitron.generated`.
Reasoning: putting `@link` + `@key` on the shared fixture flips
`federationLink` for every test in the module, so every
`Graphitron.buildSchema(b -> {})` call goes through `Federation.transform`,
silently widening the seam-0 blast radius (`GraphQLQueryTest` and friends
start exercising federation emitters), losing the non-federation
regression guard, and adding `_Service` / `_entities` / `_Entity` to every
schema-introspection assertion in the module.

*Smoke-test assertions* (against the federated fixture):
- `_Service` object type and `_entities` query field present.
- `_Entity` union contains every `@key`-bearing type in the federated
  fixture (one initially: `Film`). Catches the case where `@key` parsing
  silently drops the type from the entity union.
- Default `fetchEntities` is the placeholder no-op:
  `_entities(representations: [{__typename: "Film", filmId: 1}])` returns
  `[null]`. Locks the placeholder so the dispatch work has to move it
  intentionally; without this assertion the seam-1/2/3 work can quietly
  start resolving entities while the seam-0 test stays green.
- A federation customizer that *replaces* `fetchEntities` wins over the
  default: replace with a marker fetcher returning a sentinel and assert
  the sentinel surfaces through `_entities`. Locks the customizer order
  ("federation customizer runs after Graphitron's defaults attach"),
  not just "the customizer was invoked".
- One-arg `Graphitron.buildSchema(b -> {})` produces a federation-wrapped
  result equivalent to the two-arg form (delegation is preserved).

*Non-federation regression guard* (against the shared, non-federated
fixture): `Graphitron.buildSchema(b -> {})` returns a schema with no
`_Service` and no `_entities` field. Catches the case where federation
accidentally turns on for a schema with no `@link`. This guard lives
alongside the smoke test, not in the dispatch tests, because it
exercises the seam-0 wire-up rather than entity resolution.

## Sequencing

Hygiene items are independent of the dispatch work; each is a small
targeted change with no shared seams. Recommended order: smoke test
first to lock the scaffold, then the `AppliedDirectiveEmitter`
delegation (refactors what the smoke test already exercises and
removes a class of latent bugs before dispatch lands more directive
shapes), then the dispatch in one push, then the remaining hygiene
items. The dispatch itself has three plausible seams the implementer
can split or fold at their discretion:

1. `EntityResolution` model + `FederationKeyFieldsParser` +
   `@node`-implies-`@key(fields: "id")` synthesis. Pure value types
   and classify-time logic, testable in isolation.
2. `EntityFetcherDispatchClassGenerator` plus the
   `VALUES (idx, col1, col2, ...)` derived-table SELECT emitter,
   keyed by `(type, alternative, tenantId)` and ordered by `idx`.
   This is the canonical fetch path for both `_entities` and
   `Query.node` / `Query.nodes`.
3. `QueryNodeFetcherClassGenerator` rewired: the per-typeId loop in
   `rowsNodes` is *replaced* by a call into the entity dispatcher
   (peek typeId → look up `__typename` → synthesise rep → dispatch).
   No new public entry point on `QueryNodeFetcher`; the dispatcher
   becomes the single SELECT path. Plus the schema-builder wire-up
   that replaces the placeholder lambdas, plus `getting-started.md`
   updates.

The runtime half is what consumers see, so don't sit on (1) and (2)
for long.

## User documentation (draft)

Two targeted changes to `getting-started.md`; draft text below.

**Revised `@link` intro (replaces line 81).**

> When your SDL declares an `@link` to a federation spec,
> `Graphitron.buildSchema(...)` returns the federation-wrapped schema directly:

*(Previously overstated the form: `extend schema @link(...)` is not the only
accepted shape; a base `schema { ... } @link(...)` also works.)*

**Revised escape-hatch paragraph (replaces lines 94-105).**

> For every type Graphitron classifies, `_entities` resolution is wired
> automatically: `@node` types resolve via the NodeId path; types with a
> `@key` directive resolve via a column-value lookup. Both share the same
> per-type batched SELECT, so no extra wiring is needed beyond the
> existing `buildSchema(b -> {})` call.
>
> If you have entity types Graphitron does not classify (hand-rolled
> objects, or types pulled in from a non-Graphitron source), supply your
> own `fetchEntities` via the two-arg form:
>
> ```java
> GraphQLSchema schema = Graphitron.buildSchema(
>     b -> {},
>     fed -> fed.fetchEntities(myCustomFetcher));
> ```
>
> The federation builder arrives pre-configured with Graphitron's
> defaults; the customizer replaces on top. Custom fetchers must return
> entities the default `resolveEntityType` can recognise (jOOQ `Record`s
> with a `__typename` column); richer type-resolution shapes are not
> currently supported.

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
  classified or dispatched. Subgraphs that need it must supply their
  own `fetchEntities`. Lift in a follow-up plan if a real consumer
  surfaces.
- **`TableInterfaceType` as a federation entity.** `@key` on an
  interface-typed Graphitron classification is rejected at classify
  time; declare `@key` on the implementations instead. The dispatcher
  has no recursive fan-out across implementations, and adding one is
  unjustified speculation today. (`EntityResolutionBuilder` resolves
  `DIRECT`-shape columns against the `@table` of `TableType` /
  `NodeType` only; `TableInterfaceType` is not a permitted carrier.)
- **Customizable `resolveEntityType` extension point.** The default
  `resolveType` reads `__typename` off jOOQ `Record`s only. Consumers
  who override `fetchEntities` and return a different shape (Map,
  POJO, etc.) are on their own for type resolution; the
  `SchemaTransformer` API permits calling `fb.resolveEntityType(...)`
  from the federation customizer, but Graphitron does not document or
  test that path as a supported extension. Lift to a first-class
  customization point (with documented contract, tests, and a fallback
  story for `Map` / POJO entities) in a follow-up plan if a real
  consumer surfaces needing it. Until then, generating the
  implementation directly (adding the type to the schema, classifying
  it) is the preferred path.

## Open decisions

None.
