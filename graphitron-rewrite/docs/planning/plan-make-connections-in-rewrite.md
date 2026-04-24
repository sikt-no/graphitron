# Plan: Rewrite owns `@asConnection` → Connection synthesis

> **Status:** Spec
>
> Sub-item of the "Dissolve `graphitron-schema-transform` module"
> umbrella, Phase 3. Migrates `MakeConnections` from
> `graphitron-schema-transform` into `graphitron-rewrite` as a
> registry-level applier, so `@asConnection` expansion happens in the
> same module that classifies it. Unblocks facet synthesis running
> in-module (the umbrella's strategic trigger). Does **not** port
> `MergeExtensions`: the new applier handles `extend` nodes directly,
> so extension merging never becomes a rewrite-owned pass.

## Goal

A registry-level applier in `graphitron-rewrite` that expands every
`@asConnection` field into a Relay-shaped Connection + Edge pair (plus
a shared `PageInfo` type when any expansion fires), with no dependency
on a pre-pass that collapses `extend type` nodes into their bases.

## Scope boundaries

- **In scope:** port of `MakeConnections` (~365 LOC) into
  `graphitron-rewrite` as an applier; `extend type` support on object
  and interface carriers; legacy-parity test fixtures; pipeline
  integration in `GraphQLRewriteGenerator.loadAttributedRegistry`.
- **Out of scope:** `MergeExtensions` (not needed; see §Why the
  applier does not need a merge pre-pass). Connection runtime /
  fetcher behaviour in `FieldBuilder` — the directive-driven +
  structural detection paths at `FieldBuilder.java:384-411` already
  handle both pre- and post-expansion cases. Deletion of the legacy
  `MakeConnections` class — stays until the `expandConnections`
  config flag retires on the legacy Mojo side, tracked in Phase 4.
- **Non-goal:** any change to the `nodes` / `totalCount` field
  emission policy. Legacy flags stay wired as internal constants
  (`true`, `true`) inside the rewrite applier; a future Mojo surface
  can expose them if a consumer asks. The rewrite-test fixture and
  every known consumer set both to `true` today.

## Current state

Legacy `MakeConnections.transform` at
`graphitron-schema-transform/src/main/java/.../MakeConnections.java:37`
runs as the second registry-level transform in
`SchemaTransformer.getRegistryTransforms()` (after `MergeExtensions`).
For every `@asConnection`-marked field on a base
`ObjectTypeDefinition` / `InterfaceTypeDefinition`:

1. Resolves the inner element type by unwrapping `[T]` or `[T!]!`.
2. Resolves the Connection type name from `@asConnection(connectionName:)`
   or synthesises `<ParentType><FieldCapitalized>Connection`.
3. Creates the Connection and Edge object types (if the Connection name
   is not already in the registry) and appends them as top-level types.
4. Rewrites the field: type becomes the Connection `TypeName`, adds
   `first: Int = <default>` and `after: String` input value definitions,
   strips the `@asConnection` directive.
5. Collects federation `@shareable` propagation: if any field targeting
   a given Connection name is itself `@shareable`, the synthesized
   Connection + Edge types get `@shareable`.
6. Adds a shared `PageInfo` type at the Query type's source location
   once, if any expansion fired and `PageInfo` is not already declared.

`MakeConnections` reads only
`typeDefinitionRegistry.getTypes(ObjectTypeDefinition.class)` and
`getTypes(InterfaceTypeDefinition.class)`. Fields declared under
`extend type` are invisible to it; that is why legacy needs
`MergeExtensions` to flatten extensions into bases first.

The rewrite currently has no port of this pass. The rewrite-test
fixture schema hand-writes Connection / Edge / PageInfo types
(`graphitron-rewrite-test/src/main/resources/graphql/schema.graphqls:305-323`);
`@asConnection` is not exercised end-to-end in the rewrite's
standalone pipeline. `FieldBuilder.buildWrapper` at
`FieldBuilder.java:384-411` has a directive-driven `@asConnection`
branch for `[T]` return types and a structural branch for pre-expanded
Connection types; both are live, so the ported applier and the existing
classifier cleanly compose without double-handling.

## Why the applier does not need a merge pre-pass

graphql-java's `SchemaGenerator.makeExecutableSchema(registry, ...)`
merges `extend` nodes into their base types natively when it assembles
a `GraphQLSchema`. The rewrite classifier reads from that assembled
schema (`GraphitronSchemaBuilder.java:64-70` → `BuildContext.schema`),
so by the time any classification runs, extensions are already
collapsed.

The reason legacy needs `MergeExtensions` is not that downstream cares
about merged types in general — it is that `MakeConnections` itself
walks the raw registry before assembly. Fix that one pass and the
prerequisite dissolves.

Concretely, the rewrite applier walks both:

- `registry.getTypes(ObjectTypeDefinition.class)` — base object types.
- `registry.objectTypeExtensions().values()` — every
  `ObjectTypeExtensionDefinition` list, flattened.

and the interface equivalents. When the applier finds an
`@asConnection` field on an extension node, it rewrites that extension
node in place (field list replaced with the Connection-shaped one)
rather than folding the extension into its base. The synthesized
Connection, Edge, and PageInfo types always land as top-level
definitions — they never sit inside an `extend` block regardless of
where the triggering field was declared.

This mirrors the two existing appliers (`TagApplier`,
`DescriptionNoteApplier` in
`graphitron-rewrite/src/main/java/.../schema/input/`), which already
walk `registry.types()` + every `*TypeExtensions()` map and replace
extension nodes in place via a collect-then-swap pattern.

## Design

### New class + package

`MakeConnectionsApplier` in
`no.sikt.graphitron.rewrite.schema.transform` (new sub-package;
`schema.input` stays reserved for input-metadata appliers that
attach federation / description data without reshaping the schema).
Public signature:

```java
public final class MakeConnectionsApplier {
    private MakeConnectionsApplier() {}
    public static void apply(TypeDefinitionRegistry registry);
}
```

No config object on the signature; the two legacy flags
(`nodesFieldInConnectionsEnabled`, `totalCountFieldInConnectionsEnabled`)
stay as private constants set to `true`. If a consumer later needs to
flip either, we add a `ConnectionSynthesisConfig` record argument and
thread it from the Mojo — that is a pure additive change.

### Pipeline integration

Inside `GraphQLRewriteGenerator.loadAttributedRegistry` at
`GraphQLRewriteGenerator.java:81-87`, one new line after the two
input-metadata appliers:

```java
var bySource = SchemaInputAttribution.build(ctx.schemaInputs());
var registry = RewriteSchemaLoader.load(bySource.keySet());
TagApplier.apply(registry, bySource);
DescriptionNoteApplier.apply(registry, bySource);
MakeConnectionsApplier.apply(registry);       // new
return registry;
```

`validate()` and `generate()` both funnel through
`loadAttributedRegistry`, so Connection synthesis runs for both
paths. The validator then sees the synthesized Connection types and
can reject malformed references against them.

### Walk: base + extension, collect then swap

Two passes, single scan each:

**Pass 1 — collect.** Over all object carriers (base +
extension) and all interface carriers (base + extension), for each
`@asConnection` field:

- Resolve Connection type name + element type + default-first value
  (lifted verbatim from legacy helpers:
  `getConnectionTypeName`, `getWrappedType`, `getDefaultFirstValue`).
- Accumulate into:
  - a `Map<CarrierKey, List<FieldDefinition>>` of rewritten field
    lists (keyed by carrier identity, so a base and each of its
    extensions rewrite independently);
  - a `Map<String, ObjectTypeDefinition>` of new Connection types
    to add;
  - a `Map<String, ObjectTypeDefinition>` of new Edge types to add;
  - a `Set<String>` of Connection names needing `@shareable`;
  - a `boolean anyExpansionFired` flag for the PageInfo decision.

The `@shareable` propagation walks the same union of base + extension
carriers as the rewrite pass. A shareable field under an extension
propagates to the Connection type exactly as a base-declared shareable
field would.

**Pass 2 — swap.** For each carrier with a rewritten field list,
`registry.remove(oldCarrier); registry.add(newCarrier)` using the
extension-aware `transform(...)` variant
(`ObjectTypeExtensionDefinition.transform` for extension nodes,
`ObjectTypeDefinition.transform` for bases, same for interfaces).
This mirrors the fix landed during the `TagApplier` / `DescriptionNoteApplier`
review round 1 where `ObjectTypeDefinition.transform()` on an
extension node silently returned a base definition.

Then add the new Connection and Edge types to the registry. Add
`PageInfo` once, gated by `anyExpansionFired && !registry.hasType(PAGE_INFO)`,
with source location taken from the registry's Query type if present
(same as legacy; falls back to `null` if Query is absent).

### Connection-type-name collision across carriers

An `@asConnection(connectionName: "Foo")` declared on two different
parent fields (same or different carriers) must resolve to one
Connection type, not two. The legacy code handles this incidentally
because `maybeCreateConnectionType` checks
`typeDefinitionRegistry.hasType(connectionType)` per call; during a
single applier invocation the second call sees the first's insert.

The rewrite applier does the equivalent via the `new-types` maps: if
two carriers both trigger the same Connection name, the second
resolution short-circuits on a `containsKey` check. Fail-fast if the
two resolutions disagree on the element type or nullability (legacy
silently takes the first-wins; the rewrite should hard-error since
the disagreement is almost always an author bug).

### Directive-declaration auto-injection

Legacy pulls `@asConnection` (and friends) from a bundled
`/schema/directives.graphqls` and uses it only to derive the default
`firstDefault` value, not to declare the directive. In the rewrite
pipeline, `RewriteSchemaLoader` already auto-injects the directive
declarations via
`graphitron-rewrite/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`,
so `@asConnection` is declared in the registry before the applier
runs. The applier can read the default-first value directly from the
declaration via `registry.getDirectiveDefinition(AS_CONNECTION)`; no
need to duplicate-parse the directives file.

### Interaction with `FieldBuilder`'s existing paths

`FieldBuilder.buildWrapper` at `FieldBuilder.java:384-411` has two
detection paths:

1. Directive-driven (`fieldDef.hasAppliedDirective(DIR_AS_CONNECTION)` on
   a bare `[T]` return).
2. Structural (`ctx.isConnectionType(typeName)` matches `edges.node`).

After the applier runs, `@asConnection` is stripped from the field
and the return type becomes the Connection type, so only the
structural path fires for applier-expanded fields. The directive-driven
path stays for the case where a consumer runs the rewrite without
the applier (e.g. during migration). Both paths produce the same
`FieldWrapper.Connection` classified result, so there is no classifier
branch to remove.

One cleanup candidate: the javadoc on `buildWrapper` references "the
schema transform" as the source of pre-expanded Connections. Update
it to say "the rewrite's `MakeConnectionsApplier` or a hand-written
Connection type" as part of this landing.

## Open decisions

**D1: `extend` semantics for `@asConnection(connectionName:)` cross-file
collisions.** Legacy silently first-wins when the same explicit
Connection name is chosen by two `@asConnection` declarations with
differing element types. The rewrite should fail-fast. Legacy's
behaviour is a latent-bug surface rather than intentional; pin
fail-fast in a test.

**D2: Location for synthesized Connection / Edge types.** Legacy emits
them as top-level `ObjectTypeDefinition` nodes with
`sourceLocation = fieldDefinition.getSourceLocation()`, i.e. pointing
at the source of the `@asConnection` field that triggered the
synthesis. Keep this. It means two fields that share a named
Connection produce one type pointing at whichever field the applier
processed first — non-deterministic against map iteration order. Fix
by iterating base + extension carriers in source-name + line order
before the collect pass so the winner is stable.

**D3: `PageInfo` shareability.** Legacy gives `PageInfo` the
`@shareable` directive iff any Connection type ends up shareable, so
that a shareable Connection can legally have a non-shareable
`PageInfo` as a sub-selection in federation. Keep this as-is; it is
the correct federation behaviour.

**D4: Interaction with description-note suffix.** `DescriptionNoteApplier`
runs before `MakeConnectionsApplier`. Description notes attach to
source-authored `@asConnection` fields, but the applier rewrites
those fields to strip `@asConnection` and add pagination args. The
field keeps its description (legacy `transform(builder -> ...)`
preserves the unchanged properties), so the note persists. Confirm
with a pipeline test covering a tagged source with `@asConnection`.

## Tests

Three tiers, matching the established unit / pipeline / execution
split.

**Unit — `MakeConnectionsApplierTest`** in `graphitron-rewrite`. One
`.graphqls` input string per case, applier run, assertions against
the post-apply `TypeDefinitionRegistry`. Port every
legacy fixture under
`graphitron-schema-transform/src/test/resources/asConnectionRewriterTest/`
(simple, withBangs, withDefault, withDuplicates, withInterface,
shareable, shareableDuplicate, shareableMixed, plus the three invalid
fixtures) into unit-test cases. Approval-style `SchemaPrinter`
snapshot assertions are preferred over bespoke structural checks;
they match the legacy test shape and catch unintended changes in
emission order.

Extension-specific cases (new — not in legacy):

- `@asConnection` field declared in `extend type Query { ... }`
  produces the Connection type and rewrites the extension node in
  place, base Query untouched.
- `@asConnection` field declared in `extend interface Foo { ... }`
  same as above for interfaces.
- Two extensions of the same base type each declaring an
  `@asConnection` field — both extensions get their fields rewritten;
  two distinct Connection types land at top level.
- Base type declares an `@asConnection` field, extension declares
  another `@asConnection` field on the same type — both rewrite, base
  and extension each keep their own rewritten field list.

**Pipeline — `MakeConnectionsPipelineTest`** in `graphitron-rewrite-test`.
Full `GraphQLRewriteGenerator.run` over a fixture that uses
`@asConnection` (not just pre-expanded Connection types), asserts
that `FieldBuilder.buildWrapper` produces `FieldWrapper.Connection`
via the structural path, and that one end-to-end test query against
the generated fetcher returns the expected edges + pageInfo shape.

Add one `@asConnection` field to the existing rewrite-test schema
(`graphitron-rewrite-test/src/main/resources/graphql/schema.graphqls`)
on a type that currently only has pre-expanded connections, then
pin the classification and the generated fetcher's signature in the
pipeline test. Confirms the applier composes with the classifier.

**Execution — 1 case in `GraphQLQueryTest`.** Run a real Relay-shaped
paginated query against the applier-synthesized Connection type, assert
edges, pageInfo.hasNextPage, cursor round-trip. This is the "does the
whole pipeline actually work" ratchet; any earlier tier failure is a
reproduction, but execution coverage pins the wiring from SDL → jOOQ
→ emitted fetcher → runtime.

## Rollout

Single-commit landing (unit + pipeline + execution all green).
Rewrite-test fixture edit is part of the same commit: add one
`@asConnection` field to exercise the path end-to-end.

Legacy `MakeConnections` stays in `graphitron-schema-transform` for
the duration of Phase 3 + 4. Rewrite-test has not invoked the legacy
transform pipeline since the Maven-plugin landing (`76754b3` retired
`<transform>` executions in `graphitron-rewrite-test/pom.xml`), so
running the applier in the rewrite pipeline does not double-expand
anything. Consumers still on the legacy plugin keep using legacy
`MakeConnections` until they migrate.

No new Mojo parameters in this landing; the `nodes` / `totalCount`
flags stay internal.

## Roadmap integration

Edits required to `rewrite-roadmap.md`:

1. Under "Phase 3: Migrate remaining schema-transform passes into
   rewrite", change `Rewrite owns @asConnection → Connection
   synthesis` from `[Backlog]` to `[Spec]` with link to this plan.
2. Strike the `Rewrite owns type-extension merging` bullet entirely
   and add a one-sentence note that extension handling is folded
   into each registry-level applier's own walk, not a separate
   pass. Landed-behaviour proof point: the two input-metadata
   appliers already walk extension nodes, and this applier will do
   the same.
3. No change to the Phase 3 LOC budget; `MergeExtensions`'s 65 LOC
   simply never ships, and `MakeConnections`'s 365 LOC lands with
   small additions for extension walks (extension-carrier iteration
   adds ~20-30 LOC net).

On landing, move this plan's entry to `## Done` in the roadmap with
a one-line summary citing the commit sha and the new applier's FQN,
and delete this file.
