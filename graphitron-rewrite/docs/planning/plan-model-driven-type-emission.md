# Plan: model-driven type emission

> **Status:** Spec
>
> `GraphitronSchema` becomes the single source of truth for what gets generated.
> Emitters stop reading the assembled `GraphQLSchema`; they read SDL shape
> records stored on the classified model. Macro-synthesis (Connection today,
> Facet next) moves out of emit-time parallel scans and into the classifier
> itself — synthesised types land in `schema.types()` alongside SDL-declared
> types, and the emitters can't tell the difference.
>
> Work replaces the emit-time `@asConnection` pipeline shipped at `79af12c`.
> Sets the pattern for `@asFacet` (separate plan) and any future schema
> macro directive.

## Problem

Three independent symptoms with one root cause: emission reads from two sources
of truth.

1. `ObjectTypeGenerator`, `EnumTypeGenerator`, `InputTypeGenerator`,
   `GraphitronSchemaClassGenerator` all drive off `assembled.getAllTypesAsList()`
   (the graphql-java schema). `FetcherRegistrationsEmitter` drives off
   `GraphitronSchema.types()` / `.fields()`. Two paths, two iteration orders,
   two filter conventions (`name.startsWith("_")` vs sealed-variant dispatch).
2. `@asConnection` lives in a parallel mechanism (`ConnectionSynthesis`) because
   the synthesised types don't exist in the assembled schema and the classifier
   can't put them there. `buildPlan(assembled)` runs three times per pipeline
   (once each in `ObjectTypeGenerator`, `GraphitronSchemaClassGenerator`,
   `GraphQLRewriteGenerator.runPipeline`) to smuggle them through.
3. `ObjectTypeGenerator.buildFieldDefinition` probes every field's derived name
   against the synthesis plan, even fields without `@asConnection` — a collision
   between one field's explicit `connectionName` and another field's derived
   name silently rewrites the unrelated field as a Connection.

Fix the architecture; the symptoms collapse together.

## Current state

- `GraphitronSchema` holds `types: Map<String, GraphitronType>`,
  `fields: Map<FieldCoordinates, GraphitronField>`, plus
  `fieldsByType` and `warnings`. Each `GraphitronType` variant carries `name`
  and `location`; domain-specific variants carry tables, handlers, key columns.
  No SDL-shape info.
- `ObjectTypeGenerator.generate(GraphQLSchema assembled, Map<String, CodeBlock>
  fetcherBodies)` walks the assembled schema and reads descriptions, interfaces,
  field lists, arguments, applied directives, default values straight from
  `GraphQLObjectType` / `GraphQLFieldDefinition` / `GraphQLArgument`.
- `ConnectionSynthesis` at `.../generators/schema/ConnectionSynthesis.java`
  scans the assembled schema for `@asConnection` on bare-list fields,
  builds a `Plan`, emits synthesised `<ConnName>Type`, `<ConnName>EdgeType`,
  `PageInfoType` TypeSpecs, and feeds the same plan back into
  `ObjectTypeGenerator.buildFieldDefinition` (to rewrite the carrier field's
  return type and append `first` / `after`) and `GraphitronSchemaClassGenerator`
  (to register the synthesised types via `.additionalType(...)`).
- `FieldBuilder.buildWrapper` at `FieldBuilder.java:390` already detects
  `@asConnection` on a bare list and returns `FieldWrapper.Connection` with a
  `connectionName` — so the classifier knows about the directive, it just can't
  contribute the synthesised types to `schema.types()` today.

## Desired end state

- `GraphitronType` and `GraphitronField` expose SDL-shape info directly —
  enough to emit a `TypeSpec` without touching graphql-java.
- Every schema-emitter reads from `GraphitronSchema` only.
  `GraphQLRewriteGenerator.runPipeline` passes `schema`, not `(schema,
  assembled)`, to each emitter.
- The classifier synthesises Connection / Edge / PageInfo entries directly into
  `schema.types()` when it sees `@asConnection`; it rewrites the carrier field's
  `FieldShape.returnType` and arguments in place. `ConnectionSynthesis.java` is
  deleted.
- `@asFacet` (see [plan-faceted-search.md](plan-faceted-search.md)) lands in
  this shape: one classifier contribution, zero emitter changes.

## Phase 1 — shape records on the model

**Scope.** Add SDL-shape records to `GraphitronSchema` and have the classifier
populate them. Pure additive; no generator reads them yet.

**Touch points.**

- New `TypeShape` (on `GraphitronType` or via a parallel
  `Map<String, TypeShape>` on `GraphitronSchema`). Carries `Kind`
  (`OBJECT | INTERFACE | UNION | ENUM | INPUT | SCALAR`), `description`,
  `interfaces` (for objects), `unionMembers` (for unions), and survivor applied
  directives.
- New `FieldShape` on `GraphitronField` (or keyed by `FieldCoordinates` on the
  schema). Carries `TypeRef returnType`, argument list, description,
  `deprecationReason`, survivor applied directives.
- New `TypeRef` sealed interface (`Named | NonNull | List`) and `ArgumentShape`
  record (`name, type, description, defaultValue, deprecationReason,
  directives`), plus `AppliedDirective(name, arguments)`.
- `GraphitronSchemaBuilder` records shape alongside its existing domain
  classification — it already walks every type and field, just throws this
  info away.

**Preferred placement.** Two options; the implementer picks during this phase:

1. *On the variant.* Every `GraphitronType` variant gains a `TypeShape shape`
   member; every `GraphitronField` variant gains `FieldShape shape`. Widest
   constructor-arity break, tightest coupling.
2. *Parallel maps.* `GraphitronSchema` grows `Map<String, TypeShape> typeShapes`
   and `Map<FieldCoordinates, FieldShape> fieldShapes`. Minimal variant
   disruption; access goes through two lookups.

Option 2 is less invasive on landing; option 1 is the natural end state. Ship
(2) in this phase and collapse to (1) later if the double-lookup irritates
callers. Record the decision in the plan's In-Progress update.

**Test coverage.** One new unit test per shape record: classify a small SDL
fixture, assert `typeShapes.get("Film")` reflects the declared description and
survivor directives, `fieldShapes.get(coords("Film","id"))` has the right
`TypeRef.NonNull(Named("ID"))`. No emitter test changes in this phase.

**Risk.** Low. The classifier already reads this data. Pure addition.

## Phase 2 — flip `ObjectTypeGenerator` to read shapes

**Scope.** Rewrite `ObjectTypeGenerator.generate` to iterate `schema.types()`
and read from `TypeShape` / `FieldShape`. No behavioural change; output is
byte-identical to today for every SDL-declared type. `@asConnection` still goes
through `ConnectionSynthesis` in this phase — don't remove it yet.

**Touch points.**

- `ObjectTypeGenerator.generate(GraphitronSchema schema,
  Map<String, CodeBlock> fetcherBodies)`. The old `GraphQLSchema assembled`
  parameter goes away at the end of Phase 3; in this phase, accept both and
  have the body read only from `schema`.
- `buildObjectTypeSpec`, `buildInterfaceTypeSpec`, `buildUnionTypeSpec` take
  the variant + its `TypeShape`, loop over `TypeShape.fields()` (derived from
  `schema.fieldsOf(typeName)` + their `FieldShape`s), and emit the same
  `CodeBlock` as before.
- `buildFieldDefinition` reads from `FieldShape`. The `@asConnection` branch
  (lines 205-215, 221-228, 242-248) stays for now — the classifier hasn't
  absorbed the synthesis yet. Gate the probe on the directive presence check
  while we're here (fixes the misfire bug before Phase 4 makes it moot).
- `buildTypeRef` rewritten to consume `TypeRef` instead of `GraphQLType`. Pure
  structural translation.
- `AppliedDirectiveEmitter.applicationsFor(...)` overload that takes
  `List<AppliedDirective>` instead of `GraphQLDirectiveContainer`. Keep the
  old overload for callers not yet migrated.

**Test coverage.** Snapshot test: run the full rewrite pipeline against the
existing test-spec schema, diff generated sources with the pre-change branch,
assert zero diff on every `<TypeName>Type.java` file.
`ObjectTypeGeneratorTest`'s existing assertions continue to hold against the
new signature (may need one call-site update per test to pass `schema`
instead of `assembled`).

**Risk.** Medium. Easy to miss an SDL detail — default-value emission,
deprecation reason formatting, survivor-directive argument order. The
snapshot diff catches this mechanically.

## Phase 3 — flip enum, input, and schema-class emitters; drop `assembled`

**Scope.** Migrate `EnumTypeGenerator`, `InputTypeGenerator`, and
`GraphitronSchemaClassGenerator.planFor` to read from `schema.types()` and
`TypeShape`. Remove the `GraphQLSchema assembled` parameter from every
emitter in `generators/schema/`. `GraphQLRewriteGenerator.runPipeline`
stops passing `assembled` to emission.

**Touch points.**

- `EnumTypeGenerator.generate(GraphitronSchema schema)` — iterate enum types
  from `schema.types()`, read values from `TypeShape` (new `List<EnumValue>`
  member when `Kind == ENUM`).
- `InputTypeGenerator.generate(GraphitronSchema schema)` — same shape, but the
  inner-field walk uses `FieldShape` / `ArgumentShape`.
- `GraphitronSchemaClassGenerator.generate(GraphitronSchema schema,
  Set<String> typesWithFetchers, String outputPackage)`. `planFor` iterates
  `schema.types()`; the `startsWith("_")` filter moves to a predicate on
  `TypeShape` (or the classifier skips federation-injected types at
  classification time and never puts them in `schema.types()` — cleaner).
- `GraphitronSchemaBuilder.buildBundle` still produces an `assembled`
  `GraphQLSchema` for the validator and for anything that needs the runtime
  type; callers outside `generators/schema/` can keep reading it. Emission
  doesn't.

**Test coverage.** Extend the Phase 2 snapshot diff to cover enum / input /
`GraphitronSchema.java` outputs. Delete any `generate(GraphQLSchema)`
overloads that now have no caller.

**Risk.** Low once Phase 2 is green. Structural parallel to Phase 2.

## Phase 4 — move `@asConnection` synthesis into the classifier

**Scope.** The classifier, when it encounters `@asConnection` on a bare list,
adds the Connection / Edge / (PageInfo-if-absent) entries to `schema.types()`
with their shape records, and rewrites the carrier field's `FieldShape` in
place. `ConnectionSynthesis.java` deletes entirely; the emit-time rewrite
in `ObjectTypeGenerator.buildFieldDefinition` deletes; the synthesised-types
loop in `GraphitronSchemaClassGenerator.generate` deletes.

**Touch points.**

- `FieldBuilder.buildWrapper` (`FieldBuilder.java:390`) already detects the
  directive and produces `FieldWrapper.Connection` with a resolved
  `connectionName`. Extend it (or a neighbouring classifier pass — the
  implementer decides) to:
  1. Compute the synthesised type names (existing logic in
     `ConnectionSynthesis.resolveConnectionName` / `resolveEdgeName`).
  2. Skip when a Connection type of that name already exists in the model
     (structural path — unchanged behavior).
  3. Build `TypeShape` + `FieldShape` records for Connection / Edge /
     PageInfo and add them to the schema-under-construction.
  4. Rewrite the carrier field's `FieldShape.returnType` from
     `List(NonNull(Named("Store")))` to `NonNull(Named("QueryStoresConnection"))`
     and append `first` / `after` to `FieldShape.arguments`.
  5. Require the directive before rewriting — the misfire probe disappears
     by construction.
- Delete `generators/schema/ConnectionSynthesis.java`. Delete
  `ConnectionSynthesisTest.java` (its coverage migrates to classifier tests).
- Delete the `@asConnection` branch in
  `ObjectTypeGenerator.buildFieldDefinition` (lines 205-215, 221-228,
  242-248 in the shipped code).
- Delete the synthesised-types loop in
  `GraphitronSchemaClassGenerator.generate` (lines 91-101 in the shipped
  code). Synthesised types now flow through the same `schema.types()` iteration
  as SDL-declared types.
- `GraphQLRewriteGenerator.runPipeline` drops the
  `write(ConnectionSynthesis.emitSupportingTypes(...), ...)` line.
- `FetcherRegistrationsEmitter.connectionBody` / `edgeBody` stay unchanged;
  the bodies now flow through the normal `ObjectTypeGenerator` path
  (`fetcherBodies.get(typeName)` attaches them as the synthesised
  TypeSpec's `registerFetchers` method). The inline fetcher-body builders
  previously duplicated inside `ConnectionSynthesis` go away with the file.

**Test coverage.**

- Existing `GeneratedSourcesSmokeTest` assertion — `QueryStoresConnectionType`
  and `QueryStoresEdgeType` loadable — unchanged; passes against the new path.
- Existing `GraphQLQueryTest` execution tests — two cursor-pagination
  round-trips over Sakila stores — unchanged; same runtime behavior.
- Migrate relevant assertions from `ConnectionSynthesisTest` into a new
  classifier test (`GraphitronSchemaBuilderTest` or a dedicated
  `ConnectionClassifierTest`): `@asConnection` on a bare list produces a
  `TypeShape` for the Connection in `schema.types()` with the right fields,
  the carrier's `FieldShape.returnType` is rewritten, `first` / `after`
  appended with the right defaults, shareable propagation, explicit
  `connectionName` override, dedup on same name.
- New negative test: two fields where one has
  `@asConnection(connectionName: "FooConnection")` and a sibling's derived
  name happens to equal `FooConnection` without the directive. Assert the
  sibling is not rewritten.

**Risk.** Medium. Classifier surface grows; the existing `ConnectionSynthesis`
unit coverage (22 tests) must translate to classifier-level tests without
loss. Two nuances to watch:

- **Shareable on interface fields.** `ConnectionSynthesis.buildPlan` scans
  interface types; verify the classifier pass does too (or documents the
  exclusion).
- **Assembled schema** still needs the synthesised types registered via
  `.additionalType(...)` so the runtime resolves them. That wiring falls out
  of Phase 3 naturally — `GraphitronSchemaClassGenerator.planFor` iterates
  `schema.types()`, which now contains the synthesised names.

## Order + gating

Phases are strictly ordered; each phase leaves the build green and the emitted
sources byte-identical on any schema that doesn't exercise `@asConnection`.
Phase 4 changes emitter ownership of `@asConnection` but is designed to keep
runtime behaviour identical (same TypeSpecs, same `additionalType(...)` calls,
same fetcher wiring).

A reasonable first cut for the implementer: ship Phase 1 + 2 as one commit
(the shape records have no consumer until Phase 2 reads them), then Phase 3
as its own, then Phase 4. Three commits total; each is independently
reviewable. If Phase 1's shape-records surface proves contentious, split it
off alone — the rest of the plan reads unchanged.

## Non-goals

- **`@asFacet`.** Handled by [plan-faceted-search.md](plan-faceted-search.md)
  on top of the architecture this plan lands. The current facets plan assumes
  a registry-level pass; once this plan lands, that prerequisite resolves to
  "classifier contribution" instead.
- **Collapsing `GraphitronType` variants.** The domain-classification sealed
  hierarchy (`TableType`, `NodeType`, `ResultType`, …) stays as-is. Shape is
  orthogonal.
- **Removing `assembled` from the bundle entirely.** The validator and
  runtime-registration code still want a graphql-java `GraphQLSchema`; the
  bundle keeps producing one. Only emission stops reading it.
- **Emitting non-schema classes from the model.** `TypeFetcherGenerator`,
  `QueryConditionsGenerator`, and friends remain model-driven as they are
  today — they were never part of the bifurcation.
- **Macro abstraction / `SchemaMacro` interface.** Two macros in the
  classifier don't justify extraction; if a third appears, revisit then.

## Testing strategy

- **Unit:** new coverage for `TypeShape` / `FieldShape` population in Phase 1;
  classifier-level `@asConnection` synthesis coverage in Phase 4 (migrating
  from the deleted `ConnectionSynthesisTest`).
- **Pipeline:** the existing `ObjectTypeGeneratorTest`,
  `GraphitronSchemaClassGeneratorTest`, and `GraphQLSchemaBuilderTest`
  coverage runs against the new signatures. Any test that constructs a
  `GraphQLSchema` to feed an emitter switches to building a `GraphitronSchema`
  — a mechanical change.
- **Snapshot diff:** generate-sources against the rewrite test-spec schema
  before and after each phase's merge, diff the `schema/` output directory,
  assert zero diff on phases 1–3 and only the expected deletions
  (`ConnectionSynthesis`-driven files consolidating with their neighbours)
  on phase 4.
- **Execution:** the two existing `GraphQLQueryTest` cursor-pagination
  tests are the runtime safety net for Phase 4. They exercise the full
  loop (classifier → emission → compilation → execution) against Sakila.

## Risks and open questions

1. **Federation-injected `_` types.** Current emitters filter by
   `name.startsWith("_")`. Decide in Phase 1 whether the classifier skips
   them at classification time (cleaner — they never enter `schema.types()`)
   or records them with a `kind = FEDERATION` marker that emitters skip.
   Either works; pick and document.
2. **Survivor-directive ordering.** `AppliedDirectiveEmitter` walks
   `container.getAppliedDirectives()` in graphql-java's iteration order.
   The shape record stores an explicit `List<AppliedDirective>`; order
   in the list drives emission order. Confirm the classifier preserves
   SDL declaration order (snapshot diff will catch any drift).
3. **Default-value encoding.** `GraphQLArgument.getArgumentDefaultValue`
   returns values in graphql-java-specific shapes (AST `Value` nodes,
   coerced Java objects, etc.) that `GraphQLValueEmitter.emit` knows how
   to print. `ArgumentShape.defaultValue` should carry the same thing
   in the same form, so `GraphQLValueEmitter` stays the single emission
   surface. Verify the Phase 1 shape-population path stores the AST form
   (not the coerced form) — the emitter relies on it.
4. **Custom scalars and introspection types.** Kept as `SCALAR`-kind
   entries or left out of `schema.types()` entirely? The assembled schema
   still needs them registered via `additionalType(graphql.Scalars.GraphQLInt)`
   etc.; that wiring is currently in
   `GraphitronSchemaClassGenerator.generate` (lines 105-109) and can stay
   hardcoded or move to a model-driven entry in Phase 3.

## References

- `.../generators/schema/ConnectionSynthesis.java` — emit-time synthesis,
  deleted in Phase 4.
- `.../generators/schema/ObjectTypeGenerator.java` — Phase 2 target;
  `@asConnection` branch deleted in Phase 4.
- `.../generators/schema/GraphitronSchemaClassGenerator.java` — Phase 3
  target; synthesised-types loop deleted in Phase 4.
- `.../generators/schema/EnumTypeGenerator.java`,
  `.../generators/schema/InputTypeGenerator.java` — Phase 3 targets.
- `.../FieldBuilder.java:390` — existing `@asConnection` classifier
  detection; extended in Phase 4.
- `.../GraphitronSchema.java`, `.../model/GraphitronType.java`,
  `.../model/GraphitronField.java` — Phase 1 targets.
- `.../GraphQLRewriteGenerator.java:runPipeline` — emission call-site;
  `assembled` parameter drops in Phase 3.
- [plan-faceted-search.md](plan-faceted-search.md) — downstream consumer
  of this architecture.
- Shipped emit-time baseline: `79af12c` (roadmap Done entry).
