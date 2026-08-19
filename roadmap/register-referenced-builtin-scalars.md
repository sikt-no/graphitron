---
id: R672
title: "Register every built-in scalar the emitted schema references, not just the ones the SDL uses"
status: In Review
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-08-14
last-updated: 2026-08-19
---

# Register every built-in scalar the emitted schema references, not just the ones the SDL uses

A schema whose SDL text never mentions `Int` generates without complaint and then fails at application startup with `graphql.AssertException: type Int not found in schema`. Reported against 10.0.0-RC30 on a schema whose only `Int` usage is the pagination surface `@asConnection` synthesises: the author declares `applikasjoner: [Applikasjon] @asConnection`, never writes `Int` anywhere, and the generated `GraphitronSchema.build()` names `Int` through a `typeRef` with no matching `additionalType` registration. Declaring `first: Int` by hand on the field makes the build work, which is the workaround consumers are on today.

## Why it happens

Built-in scalars are not auto-registered on a programmatic schema the way `SchemaGenerator` registers them for SDL, so `GraphitronSchemaClassGenerator` emits one `schemaBuilder.additionalType(...)` per `GraphitronType.ScalarType` row in `schema.types()`. Those rows come from `TypeBuilder.classifyAndRegister`, driven by the classification walk, so the set is "scalars the walk reached through authored SDL", not "scalars the emitted schema references".

Connection synthesis runs after that walk. `ConnectionPromoter.rebuildAssembledForConnections` mints the `first` / `after` arguments, the `Connection` and `Edge` types, `PageInfo` and `totalCount` on the assembled schema once classification is over, so the scalars those surfaces reference are never candidates for registration. `Int` is the reported case; `String` (via `after`, `endCursor`) and `Boolean` (via `hasNextPage`) sit behind the same hole and would surface on a schema that happens not to use them either.

The property the generator does not hold: **every scalar the emitted schema references is registered on the builder, independent of what the author wrote.** Today registration is sourced from author-reachability, and the two sets diverge the moment the generator synthesises a surface of its own.

Reported at https://github.com/sikt-no/graphitron/issues/527.

## Spec findings

Line anchors are as of this spec; symbols are the stable reference.

1. **The hole is scalar-only, and the minter already registers its object types.**
   `ConnectionPromoter.synthesiseForField` registers the minted Connection / Edge / PageInfo /
   Facets rows into `ctx.typeRegistry` *during* the walk (`registerSynthesised`,
   `registerPageInfo`), so those get `additionalType(...)` emission through `TypeUnitCommands`'
   schema-shape rows. The scalars those same surfaces reference are only *referenced*, as
   name-only `GraphQLTypeReference.typeRef(...)`; `TypeUnitCommands` maps `ScalarType` to no
   shape row, and `GraphitronSchemaClassGenerator`'s scalar loop (lines 247-272) reads
   `schema.types()` directly. So the fix's shape is already in the tree: the asymmetry is that
   the promoter registers what it mints but not what its mints reference.

2. **The exposed arm is directive-driven promotion, plus one PageInfo corner.**
   `rewriteCarrierField` takes a `ConnectionSynthesis.DirectiveDriven`, so the minted
   `first: Int` / `after: String` args exist only on that arm; a structural carrier's connection
   surfaces are authored SDL, walk-reached, and classify normally. The corner: an SDL-declared
   `PageInfo` that nothing authored references gets its `PageInfoType` row registered by
   `registerPageInfo`, but its `Boolean` / `String` field scalars were never walk-reached, so
   they sit in the same hole.

3. **Both options in the original notes decline.** Registering the all-declared superset
   (`scalarVerdicts` over `getAllTypesAsList`) over-registers unused scalars into the observable
   schema and flips the one-directional contract
   `scalarRegistration_unreferencedSpecBuiltInIsNotEmitted` pins (registered set follows the
   referenced set, not declaration). Whether an unused `Int` is even present in
   `getAllTypesAsList` is contested in the tree: that test's inline comment claims graphql-java
   pulls `Int` / `Boolean` / `ID` in for any schema, a claim the comment does not assert and the
   issue's failure does not settle either way. The design below is independent of the answer
   (slice 1's fallback), so the spec deliberately does not rest on it; slice 1's test work
   settles the comment. A reference sweep as the registration *source* was also declined: it
   would move registration authority from the classified model into the generator layer. The
   sweep is spent as the enforcer instead (slice 2).

4. **A hardcoded register-`{Int, String, Boolean}` triple was considered and declined.** It
   would restate what the `buildSynthesised*` builders spell as `typeRef(...)` some four hundred
   lines away with nothing binding the two lists; the next scalar on a synthesised surface would
   fall through silently, which is this bug in a new coat. The fix derives the demanded names
   from the minted forms themselves.

5. **Two producers of one row is a provenance bug waiting.** A `ScalarType` row built from a
   `graphql.Scalars` constant and one built by `classifyScalarType` over `ctx.schema`'s instance
   differ in `schemaType` identity (and location); `TypeRegistry.register`'s same-class arm
   replaces, so which row survives would depend on registration order. The fix keeps one
   production path.

## Design

**Slice 1 (the fix): the minter demands, the classifier produces.**

- In `ConnectionPromoter`, wherever a promotion registers a schema form (`registerSynthesised`
  for Connection / Edge / Facets / FacetValue, `registerPageInfo` for both its arms), sweep that
  form's field output types and argument types, unwrap to named types, and demand registration
  for every scalar name found. Names arrive as `GraphQLTypeReference` on synthesised forms and
  as real `GraphQLScalarType` instances on SDL-wrapped forms (the declared-PageInfo arm), so the
  sweep collects names, not instances. This is single-sourced against the builders: a future
  synthesised surface referencing a new scalar demands it by construction, and the
  declared-but-unreached PageInfo corner (finding 2) is covered because its wrapped form is
  swept like any other.
- Demand goes through one production path: a small `TypeBuilder` entry
  (`ensureScalarRegistered(String name)` or similar) that no-ops when the registry already
  carries the name, otherwise classifies through the existing `classifyScalarType` machinery,
  over `ctx.schema.getType(name)` when the assembled schema carries the scalar instance, falling
  back to `ScalarTypeResolver.resolveBuiltIn` plus the `graphql.Scalars` constant for a spec
  built-in the assembled schema omits. No needed-scalar set is carried across passes; the
  promoter calls at demand time, the classifier owns row construction, and a scalar that is both
  authored-reachable and demanded produces one row through one path (the no-op arm makes order
  irrelevant).
- The rewrite-time `first` / `after` args reference `Int` and `String` by name; both names are
  already demanded by the synthesised Connection and Edge forms (`totalCount`, `cursor`), so the
  arg minting adds no name today. Slice 2 is what catches a future divergence, not a comment.
- A built-in absent from the SDL has no author site, so its row's `location` is whatever the
  classifier derives from the programmatic instance (the synthesised PageInfo's null-location
  rationale transfers: no single carrier is the actionable site).

**Slice 2 (the enforcer): reference closure over the emitted population.**

- A one-directional guard in the mould of `GraphitronSchemaBuilder.rejectDanglingTypeReferences`
  (whose message already predicts this exact failure for SDL object return types): every scalar
  name the generated schema class will reference must have a `ScalarType` row in
  `schema.types()`. Registered-but-unreferenced stays legal; the
  `unreferencedSpecBuiltInIsNotEmitted` contract is untouched.
- The swept population is the generator's own inputs, not the rebuilt assembled schema: the
  schema forms of the rows `TypeUnitCommands` turns into schema-shape units, survivor directive
  definitions (`DirectiveDefinitionEmitter.survivors`), and schema-level applied directives.
  `Bundle.assembled()` is a strict superset of what is emitted (underscore-prefixed federation
  types, strictly-internal directive-support inputs, demoted `UnclassifiedType` names), so
  sweeping it would fail builds whose generated schema starts fine.
- Rejection arms: a referenced-but-unregistered scalar behind a synthesised surface is a
  *generator* defect, so the diagnostic says so plainly (name the referencing coordinate and the
  missing scalar; do not borrow `rejectDanglingTypeReferences`' author-actionable "give X a
  binding" shape). A name that already carries a scalar demotion diagnostic
  (`classifyScalarType`'s `Rejected` arms producing `UnclassifiedType`) is suppressed here: the
  author-caused case already has its own richer report, and the guard must not double-report it.
- Placement: after `rebuildAssembledForConnections` (the survivors sweep needs the rebuilt
  schema), before emission, reporting through the same diagnostic channel as the sibling guard.
  Without a scalar-registration command row (see Out of scope) the guard re-derives the
  registered set by the same `ScalarType` filter the generator applies; keep the two filters
  adjacent or shared so they cannot drift.

## Tests

- **Pipeline tier (the pin):** in `GraphitronSchemaBuilderTest`, the built-in-free carrier
  fixture (`type Query { films: [Film!]! @asConnection }` with `type Film { id: ID! }`, already
  in use at the unit tier) asserts `schema.types()` carries `ScalarType` rows for `Int`,
  `String`, and `Boolean` with `Resolved` resolutions naming the `graphql.Scalars` constants. A
  variant with an SDL-declared but otherwise unreferenced `PageInfo` pins the finding-2 corner.
- **Execution tier (the reported failure):** a schema whose only built-in scalar usage is the
  `@asConnection` surface, generated in `graphitron-sakila-example`, with a test asserting
  `GraphitronSchema.build()` succeeds and `assembled.getType("Int")` is a `GraphQLScalarType`
  (the proof shape `aliasingScalar_registeredUnderSdlNameAndResolvesEndToEnd` established). No
  existing example schema qualifies (each either uses `Int` or has no `@asConnection`), so
  either extend `multischema-mutation.graphqls` (today: zero `Int` references, zero connections)
  with a connection-carrying query field, or add a minimal schema plus generator execution;
  implementer's pick, the extension preferred if that schema's query surface allows it.
- **Guard coverage:** the suppression arm is pipeline-fixturable (a misconfigured `@scalarType`
  scalar plus a field referencing it, asserting the demotion diagnostic appears once and the
  guard adds nothing). The generator-defect arm has no SDL fixture once slice 1 lands, so it is
  unit-fixturable with a hand-built model row referencing a scalar that has no row.
- **Comment hygiene:** correct the inline comment on
  `scalarRegistration_unreferencedSpecBuiltInIsNotEmitted` (its `Int` / `Boolean` / `ID` claim
  is unasserted and, per this item, misleading); the test's assertion itself stays, it pins the
  one-directional contract slice 2 preserves.

## Out of scope

- **A scalar-registration command row.** Which scalars get registered is currently decided
  inside the render shell (`GraphitronSchemaClassGenerator`'s filter over `schema.types()`),
  which pushes against the complete-commands principle; a registration row sibling to
  `SchemaShapeUnit` (name plus `ScalarResolution` arm) would turn slice 2's sweep into a join
  over command relations. That is planner surface beyond a bug fix. If slice 2 proves
  unreasonably awkward without it, file it as its own item rather than growing this one.
- **Non-connection synthesised surfaces** (federation machinery, `@nodeId` shapes). Slice 1
  does not touch them; slice 2's sweep is scoped to the emitted population, so if any of them
  reference an unregistered scalar the guard names the coordinate instead of this spec guessing.
- **The assembled-schema rebuild itself.** The standing item proposing to drop the rebuild is
  unaffected: slice 1 registers model rows during the walk, and slice 2's population is defined
  by the generator's inputs, whatever schema object carries them.
