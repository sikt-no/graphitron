# Plan: Rewrite owns `@asConnection` via emit-time synthesis

> **Status:** Spec
>
> Sub-item of the "Dissolve `graphitron-schema-transform` module"
> umbrella, Phase 3. Makes `@asConnection` a first-class rewrite
> concept end-to-end: the classifier already understands it; this plan
> teaches the schema emitters to synthesise `Connection` / `Edge` /
> `PageInfo` types programmatically when a `FieldWrapper.Connection`
> field's schema type is a bare list. No registry pre-pass, no
> `MergeExtensions` dependency. Unblocks facet synthesis running
> in-module (the umbrella's strategic trigger) and matches the
> direction of the "prebuilt programmatic `GraphQLSchema`" landing
> (`81fa607` + `5b4ecce` → `4088cb1`).

## Goal

`@asConnection` on a `[T]` field works end-to-end in the rewrite
pipeline with zero registry mutation. The runtime `GraphQLSchema`
emitted by `GraphitronSchema.buildSchema` exposes the synthesised
Connection / Edge / PageInfo types to clients; the generated SDL
served via `Graphitron.getTypeRegistry()` matches. The legacy
`MakeConnections` class is not ported.

## Scope boundaries

- **In scope:**
  - Emit-time synthesis in `ObjectTypeGenerator` /
    `InterfaceTypeGenerator` (creating new `<Conn>Type.java` and
    `<Conn>EdgeType.java` TypeSpecs for each distinct Connection
    name, plus a once-only `PageInfoType.java`).
  - Field-level rewrites inside those generators: when emitting a
    field classified as `FieldWrapper.Connection` whose SDL return
    type is a bare list, substitute the Connection type reference,
    add `first` / `after` input-value builders, strip the
    `@asConnection` applied directive from the emitted builder call.
  - `GraphitronSchemaClassGenerator` additions: register each
    synthesised type via `.additionalType(...)` so graphql-java's
    programmatic `GraphQLSchema.build` picks them up.
  - Federation `@shareable` propagation on synthesised types:
    `@asConnection` + `@shareable` on a field produces a shareable
    Connection + Edge; shareable `PageInfo` whenever any such field
    fires.
- **Out of scope:**
  - Any registry-level applier. `TypeDefinitionRegistry` is not
    mutated for this feature.
  - `MergeExtensions` (trivially unnecessary — nothing new walks the
    registry).
  - Structural detection for hand-written Connection types
    (`FieldBuilder.java:402-409`): stays as-is. The two paths
    coexist; consumers can either hand-write Connection types or use
    `@asConnection` and get synthesis.
  - Deletion of the legacy `MakeConnections` class. Stays in
    `graphitron-schema-transform` until the legacy plugin retires
    (Phase 4).
- **Non-goal:** any change to the `nodes` / `totalCount` field
  emission policy. Legacy flags stay wired as internal constants
  (`true`, `true`) inside the new synthesis helper; a future Mojo
  surface can expose them if a consumer asks.

## Current state

`FieldBuilder.buildWrapper` at `FieldBuilder.java:384-411` already
has two Connection-detection paths:

1. **Directive-driven.** `fieldDef.hasAppliedDirective(DIR_AS_CONNECTION)`
   on a bare `[T]` return → `FieldWrapper.Connection` with
   `connectionName` read from the directive argument (falls back to
   `<Parent><FieldCap>Connection`) and `defaultPageSize` from
   `@asConnection(defaultFirstValue:)` (falls back to the directive
   declaration's own default, then to `1000`).
2. **Structural.** `ctx.isConnectionType(typeName)` picks up the
   `edges.node` shape of a pre-expanded or hand-written Connection
   type.

The classifier is covered: `GraphitronSchemaBuilderTest.AS_CONNECTION_SPLIT_CLASSIFIED`
at `GraphitronSchemaBuilderTest.java:590` exercises a raw
`customers: [Customer!]! @asConnection @splitQuery` field and
asserts `FieldWrapper.Connection`. The fetcher side is covered too:
`TypeFetcherGenerator` branches on `FieldWrapper.Connection` at 4+
sites (e.g. `TypeFetcherGenerator.java:311`, `:430`) and emits
Connection-shaped bodies irrespective of whether the return type is a
raw list or a hand-written Connection.

The emitter side is **not** covered for the directive-driven case.
`ObjectTypeGenerator.generate` at `ObjectTypeGenerator.java:70-85`
iterates `assembled.getAllTypesAsList()`; Connection / Edge / PageInfo
types that aren't in the assembled schema are never emitted, and the
field itself emits with its bare-list return type. The rewrite-test
fixture sidesteps this by hand-writing `ActorsConnection` /
`FilmsConnection` / `PageInfo`
(`graphitron-rewrite-test/src/main/resources/graphql/schema.graphqls:305-323`);
every `@asConnection` mention in that file is in a comment, not a
field declaration. There is no end-to-end test of `[T] @asConnection`
in the execution tier — the classifier-level passes are the only
coverage.

Legacy's `MakeConnections.transform` at
`graphitron-schema-transform/src/main/java/.../MakeConnections.java:37`
does the synthesis at registry-mutation time. Items it resolves
(Connection name, element type, default-first value, `@shareable`
propagation, once-per-schema `PageInfo`) all still need to happen in
the rewrite; this plan moves them to the emitter.

## Why emit-time synthesis (not a registry applier)

The rewrite landed at `81fa607` + `5b4ecce` → `4088cb1` moved schema
construction from SDL-based assembly (`SchemaGenerator.makeExecutableSchema`
consuming a registry) to programmatic builder calls in the emitted
`Graphitron.buildSchema(...)` facade. Every type, field, argument,
directive application, and fetcher registration now flows through a
generated call. Legacy's "mutate the registry then hand it to
SchemaGenerator" pattern is the previous architecture; the rewrite's
direction is "classifier informs the emitter, emitter calls the
builder".

`@asConnection` synthesis fits that direction cleanly. The classifier
already carries every fact synthesis needs in a `FieldWrapper.Connection`:
`connectionName` (resolved with the same `<Parent><FieldCap>Connection`
fallback legacy uses), `defaultPageSize`, `itemNullable`. Emitting
`ConnectionType` / `EdgeType` TypeSpecs from that record is direct;
graphql-java's `SchemaGenerator` never gets a chance to see a bare
list with `@asConnection` because the emitter doesn't pass the
registry-level bare list to it in the first place — it emits builder
code that puts the Connection type reference on the field.

**Extensions fall out.** With no registry walk, there's nothing to
worry about when `@asConnection` appears under `extend type`. The
assembled `GraphQLSchema` already merges extensions (graphql-java does
that in `SchemaGenerator.makeExecutableSchema`), so the classifier
sees the merged view, and the emitter works off the classifier model.
`MergeExtensions` is trivially unnecessary for this feature.

**The one downside,** and it is real, is that the synthesised
Connection types are not visible on the `GraphQLSchema` that
graphql-java produces from the raw registry. Any rewrite component
that wanted to read Connection types off the *pre-emission* schema
(e.g. a validator checking cross-field references to Connection
types) would have to traverse the classifier model instead. Today
no such component exists, and the rewrite's validator already works
off the classifier-derived `GraphitronSchema` rather than the raw
graphql-java schema, so this is observational rather than a
blocker.

## Design

### Synthesis plan

Three emission surfaces need to change:

1. **Connection / Edge TypeSpec emission** — new type-class files
   must exist (one `<Conn>Type.java` per distinct Connection name,
   one `<Conn>EdgeType.java` per Connection, one shared
   `PageInfoType.java` if any synthesis fires).
2. **Field emission** — when `ObjectTypeGenerator` /
   `InterfaceTypeGenerator` emit a field that classifies as
   `FieldWrapper.Connection` over a bare list, the emitted builder
   call uses the Connection type reference and carries the pagination
   args.
3. **Schema-level additional-type registration** — each synthesised
   type registered on the programmatic builder via
   `.additionalType(...)` so graphql-java's `.build()` actually
   includes it on the runtime `GraphQLSchema`.

Each of these is an edit to an existing generator, not a new
pipeline stage. No changes to `GraphQLRewriteGenerator.java` or the
registry-loading path.

### New synthesis helper

`ConnectionSynthesis` in
`no.sikt.graphitron.rewrite.generators.schema`, alongside the other
schema-emission helpers (`AppliedDirectiveEmitter`,
`DirectiveDefinitionEmitter`, etc.). One data record captures the
plan, one method computes it from the classifier model, one method
produces TypeSpecs:

```java
record Plan(
    // Connection-name → element-type (by classified GraphitronType ref),
    // item-nullable, shareable. Keyed so two fields sharing a name
    // dedup.
    Map<String, ConnectionDef> connections,
    boolean needPageInfo,
    boolean pageInfoShareable
) {}

static Plan buildPlan(GraphitronSchema model);
static List<TypeSpec> emitSupportingTypes(Plan plan, String outputPackage);
```

`ConnectionDef` carries: element type name, element-nullable,
shareable, `nodesFieldEnabled` (constant `true`), `totalCountEnabled`
(constant `true`). `emitSupportingTypes` returns one TypeSpec per
Connection, one per Edge, plus one PageInfo if `needPageInfo`.

### Emit-site integration

`ObjectTypeGenerator.buildFieldDefinition` — the helper that emits
one `newField().name(...).type(...)...build()` builder block for a
`GraphQLFieldDefinition` — takes a lookup callback today only for
`fetcherBodies`. Extend it to also consult the classifier model:
when the field classifies as `FieldWrapper.Connection` over a bare
list, the emitter substitutes the Connection `TypeReference`, appends
two `.argument(...)` calls (`first: Int = <default>`, `after: String`),
and skips the `@asConnection` applied-directive emission via an
exclusion in `AppliedDirectiveEmitter`.

Same edit in `InterfaceTypeGenerator` for interface carriers.
(Legacy `MakeConnections` supports interfaces; the rewrite parity
holds.)

`GraphitronSchemaClassGenerator.generate(assembled, …)` already emits
the top-level `buildSchema` call. After this change, it additionally:

- calls `ConnectionSynthesis.buildPlan(model)` (passing the classifier
  model, which the generator already has via `fetcherBodies.keySet()`
  at the current signature — signature widens to `(assembled, model,
  outputPackage)`);
- appends `.additionalType(<Conn>Type.type())`,
  `.additionalType(<Conn>EdgeType.type())` per entry, and
  `.additionalType(PageInfoType.type())` once if `needPageInfo` and
  the schema does not already declare `PageInfo`.

`GraphQLRewriteGenerator.runPipeline` writes the new TypeSpecs from
`emitSupportingTypes(plan, outputPackage)` to the `"schema"`
sub-package alongside existing `<TypeName>Type` classes. One new line.

### Default-first-value lookup

`FieldBuilder.resolveDefaultFirstValue` already reads
`@asConnection(defaultFirstValue:)` with fallback to the directive
declaration's default. `ConnectionSynthesis.buildPlan` reuses it via
the classifier model — no duplicate directive-parsing.

### Connection-type-name collision

Two fields with the same resolved Connection name must produce one
synthesised type. `buildPlan` handles this by keying on the name and
accumulating into a single `ConnectionDef`. On collision:

- **Element type or nullability disagrees** → fail-fast with a
  `BuildWarning` or validation error (legacy silently first-wins;
  flagged in Open Decisions below).
- **Shareable flag disagrees** → OR them (any field's `@shareable`
  makes the Connection shareable, matching legacy semantics).

### Interaction with `FieldBuilder`'s existing paths

Both detection paths at `FieldBuilder.java:384-411` stay live:

1. **Directive-driven** — now the canonical path for `@asConnection`
   authored fields. Covered by existing classifier tests; becomes
   covered end-to-end after this landing.
2. **Structural** — continues to cover hand-written Connection types
   (the rewrite-test fixture's `ActorsConnection` / `FilmsConnection`
   pattern). No change.

Both produce `FieldWrapper.Connection`. The emitter's new behaviour
is gated on whether the *SDL return type* is a bare list; the
structural path's fields already have a Connection SDL return type
and skip synthesis automatically.

Cleanup: the javadoc on `buildWrapper` currently says "pre-expanded
connection from the schema transform or hand-written". Update to
"hand-written Connection type" — the rewrite no longer has a
pre-expansion step to reference.

## Open decisions

**D1: Connection-name collision with element-type disagreement.**
Legacy silently first-wins when two `@asConnection(connectionName:)`
declarations with the same name target different element types. The
rewrite should fail-fast via a `BuildWarning` raised to validation
error, since the disagreement is almost always an author bug. Pin
fail-fast behaviour in a classifier-tier test.

**D2: `PageInfo` singleton semantics when a hand-written `PageInfo`
exists.** If the schema already declares `type PageInfo { ... }` (the
rewrite-test fixture does), synthesis must skip `PageInfo` emission
and let every synthesised Connection reference the hand-written one.
The check is `assembled.getType("PageInfo") != null` at emit time. If
the hand-written one is missing fields that legacy's synthesised one
provides (`hasPreviousPage`, `hasNextPage`, `startCursor`,
`endCursor`), that's an author bug — surface as a validation error,
don't silently overwrite.

**D3: `PageInfo` shareability.** Legacy gives the synthesised
`PageInfo` `@shareable` iff any Connection type ends up shareable.
Keep this semantics; it's the correct federation pattern. When
`PageInfo` is hand-written, the author owns the shareability flag
(don't rewrite someone else's type).

**D4: Description-note interaction.** `DescriptionNoteApplier` adds
a trailing blank-line note to field descriptions on tagged sources.
It runs well before emission, so when `ObjectTypeGenerator` emits the
`@asConnection` field, the description already carries the note —
no interaction; the description passes through untouched. Confirm
with a pipeline test covering a tagged source with `@asConnection`.

**D5: Coexistence path with the legacy `MakeConnections` transform.**
A consumer running the rewrite through the legacy plugin (still
supported during migration) gets `@asConnection` expanded in the
registry by legacy, and the classifier's structural path takes over.
That path is already tested. After this landing, a consumer running
the rewrite through `graphitron-rewrite-maven` gets emit-time
synthesis instead. Both paths must coexist until Phase 4 retires
legacy. Pin the legacy-plugin path with an integration-style test
that runs `mvn install` under the legacy transform and asserts the
same emitted output as the new synthesis path.

## Tests

Three tiers, matching the established unit / pipeline / execution
split. Tier-1 coverage shifts from "does the applier produce the
right registry" (legacy question) to "does the emitter produce the
right TypeSpecs / schema builder calls" (Option B question).

**Unit — `ConnectionSynthesisTest`** in `graphitron-rewrite`.
`.graphqls` input per case, run through the classifier, invoke
`ConnectionSynthesis.buildPlan` and `emitSupportingTypes`, assert on
the returned TypeSpecs and plan record. Cases adapt from the legacy
fixtures at
`graphitron-schema-transform/src/test/resources/asConnectionRewriterTest/`:
simple, withBangs, withDefault, withDuplicates, withInterface,
shareable, shareableDuplicate, shareableMixed, plus the invalid
fixtures (unnamed non-list element, duplicate directive, bare
non-list return). Legacy's approval-style `SchemaPrinter` snapshot
does not port as-is (no registry output to print); use the existing
`TypeSpecAssertions` idiom for structural checks plus targeted
string matches for the pagination args + Connection type
references.

**Unit — emitter integration in `ObjectTypeGeneratorTest` /
`GraphitronSchemaClassGeneratorTest`.** Extend existing unit coverage
to assert:
- A `[T] @asConnection` field emits a `.type(...)` builder call
  referencing the Connection type, not the list type.
- The `@asConnection` applied-directive does **not** appear in the
  emitted field's applied-directive list.
- `GraphitronSchemaClassGenerator` emits `.additionalType(...)` for
  each synthesised Connection + Edge and once for `PageInfo` when
  synthesis fires.
- Structural-path fields (hand-written Connection type) are
  untouched — emitter still walks `assembled.getAllTypesAsList()`
  and emits them normally; no duplicate `.additionalType(...)`.

**Pipeline — `AsConnectionPipelineTest`** in `graphitron-rewrite-test`.
Add one `[T] @asConnection` field to the rewrite-test fixture schema
alongside the existing hand-written Connection types. Run the full
generator; assert that:
- A `<Parent><Field>ConnectionType.java` and corresponding
  `<…>EdgeType.java` exist in the `schema` sub-package.
- The hand-written Connection types are still emitted (both paths
  coexist without duplication).
- The classifier reports `FieldWrapper.Connection` for both kinds of
  field.

**Execution — 1 case in `GraphQLQueryTest`.** Run a real Relay-shaped
paginated query against the emit-synthesised Connection, assert
edges, pageInfo.hasNextPage, cursor round-trip. This is the ratchet
that proves SDL → classifier → emit-time synthesis → programmatic
`GraphQLSchema` → fetcher runtime composes end-to-end. Without this
test, the "classifier accepts it but emitter never fired" latent-gap
problem from the current state would just reappear in different form.

## Rollout

Single-commit landing (unit + pipeline + execution all green).
Rewrite-test fixture edit is part of the same commit: add one
`[T] @asConnection` field to exercise emit-time synthesis alongside
the existing hand-written Connection types.

Legacy `MakeConnections` stays in `graphitron-schema-transform` for
the duration of Phase 3 + 4. Rewrite-test has not invoked the legacy
transform pipeline since the Maven-plugin landing (`76754b3` retired
`<transform>` executions in `graphitron-rewrite-test/pom.xml`), so
the new synthesis does not collide with any leftover legacy
expansion. Consumers still on the legacy plugin keep using legacy
`MakeConnections` until they migrate; the classifier's structural
detection path handles their already-expanded output.

No new Mojo parameters in this landing; the `nodes` / `totalCount`
flags stay internal to `ConnectionSynthesis`.

## Roadmap integration

Edits required to `rewrite-roadmap.md`:

1. Under "Phase 3: Migrate remaining schema-transform passes into
   rewrite", the `Rewrite owns @asConnection` bullet is already
   `[Spec]` with a link to this plan; update the descriptive text to
   mention emit-time synthesis rather than "registry-level applier".
2. Strike the `Rewrite owns type-extension merging` bullet entirely
   and add a one-sentence note that nothing in Phase 3 walks the
   registry in a way that would need pre-merged extensions —
   `@asConnection` is emit-time, and the remaining passes
   (`@notGenerated` removal, directive stripping, feature-flag
   splits) can each be written to walk extension nodes natively or
   operate on the classifier model.
3. Phase 3 LOC budget: `MergeExtensions`'s 65 LOC never ships. The
   emit-time synthesis roughly matches legacy `MakeConnections`'s
   365 LOC in total (reparceled: collision / planning logic moves
   into `ConnectionSynthesis.buildPlan`, TypeSpec emission replaces
   `ObjectTypeDefinition` construction, the field-level rewrite
   becomes a branch inside `ObjectTypeGenerator.buildFieldDefinition`).

On landing, move this plan's entry to `## Done` in the roadmap with
a one-line summary citing the commit sha and the `ConnectionSynthesis`
helper location, and delete this file.
