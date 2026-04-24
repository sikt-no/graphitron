# Plan: first-class Connection type variants + classifier-authoritative emission

> **Status:** Spec
>
> First instalment (Phases 1-3) shipped: `ConnectionType` / `EdgeType` /
> `PageInfoType` are first-class `GraphitronType` variants, `ConnectionSynthesis`
> is deleted, `FieldWrapper.Connection` is down to per-site metadata. The original
> driver — `schema.types()` lying about what the generator emits — is resolved
> for Connections.
>
> The remaining work closes three deviations from the "classifier is authoritative"
> principle that the shipped implementation didn't address, plus extends the
> pattern to the other schema emitters. **No production users yet** — this is
> the right window to finish the job cleanly rather than paper over the gaps
> with indirection.
>
> The endpoint: every emitter in `generators/schema/` reads from `GraphitronSchema`
> only. The `assembled GraphQLSchema` is a classifier output that the runtime
> validator and generated `GraphitronSchema.build()` consume, but no emitter
> reads it. No directive probing at emit time. No fallback iterations over
> two sources.

## Shipped

- **Phase 1** (`0aef2c7`) — `ConnectionType`, `EdgeType`, `PageInfoType` added to
  `GraphitronType` sealed hierarchy; classifier populates them for both
  directive-driven (`@asConnection` on a bare list) and structural
  (hand-written Connection-shaped SDL) paths. Each variant carries its
  `GraphQLObjectType schemaType`. Six `ConnectionTypeCase` classification tests.
- **Phase 2** (`0ecde9d`) — `ObjectTypeGenerator` and
  `GraphitronSchemaClassGenerator` iterate `schema.types()`.
  `ConnectionSynthesis.java` + test deleted (385 + 243 lines).
  `GraphQLRewriteGenerator.runPipeline` passes `schema` to emitters. Emitter
  fetcher-body lookup keyed off `schema.types()` via
  `FetcherRegistrationsEmitter`.
- **Phase 3** (`237d6d3`) — `FieldWrapper.Connection` shrunk to
  `(connectionNullable, defaultPageSize)`. Structural-detection convenience
  constructor deleted. `FetcherRegistrationsEmitter` finds
  connection/edge names from `schema.types()` directly.

## Deviations still in place

The shipped implementation has three gaps from the "classifier is
authoritative" principle. Each is a working workaround today; each is worth
closing pre-launch rather than carrying into production.

### D1 — plain SDL types bypass the classifier

`TypeBuilder.classifyType` returns `null` for object types without
`@table` / `@record` / `@error`. Such types don't enter `schema.types()`.
`ObjectTypeGenerator.generate` accommodates this with a second loop over
`assembled.getAllTypesAsList()` — the exact kind of dual-source iteration this
plan was meant to retire.

*Fix:* classifier records every emittable object type. A new
`GraphitronType.PojoObjectType` (or analogous name) for plain object types,
a variant for enums, and normalization so every SDL-declared type the
generator emits has a `schema.types()` entry.

### D2 — carrier rewrite happens at emit time, via directive probing

Plan Phase 2 envisioned the classifier rewriting the carrier field's
`GraphQLFieldDefinition` via `.transform()` (replacing the bare-list return
type with the Connection type reference, appending `first`/`after` args) so
the emitter reads a pre-rewritten field. That wasn't implemented.
`ObjectTypeGenerator.buildFieldDefinition` currently probes
`field.hasAppliedDirective("asConnection")`, resolves the connection name at
emit time, and rewrites the emitted `CodeBlock`.

*Fix:* classifier rebuilds the parent `GraphQLObjectType` with the carrier
field transformed. Emitter stops reading directives.

### D3 — assembled schema doesn't see synthesised types

Directive-driven Connection / Edge / PageInfo types are built by the
classifier and stored on their respective variants' `schemaType`, but the
`assembled GraphQLSchema` the bundle returns does not contain them.
`assembled.getType("QueryStoresConnection")` returns `null` for directive-driven
connections. Nothing downstream reads assembled for synthesised names today, so
it's latent — but any future code that does will silently miss them.

*Fix:* rebuild the assembled schema with `additionalType(...)` calls for
synthesised types (and carrier-rewritten parent types from D2). Post-rebuild,
the model and the assembled schema agree on what types exist.

### D4 — enums and inputs still bypass the classifier for emission

`EnumTypeGenerator` and `InputTypeGenerator` still iterate
`assembled.getAllTypesAsList()`. The D1 / D2 / D3 work is scoped to objects;
these two emitters were left alone in Phase 2. Pre-launch is the right window
to flip them too, so the architecture is uniform.

*Fix:* classifier records enum and plain-input types in `schema.types()`;
both emitters iterate `schema.types()` only.

## End state

After the remaining phases ship:

- `schema.types()` is the single source of truth for what the generator
  emits. Every emittable type — SDL-declared or directive-synthesised — has
  an entry, with an appropriate `GraphitronType` variant.
- Every `GraphitronType` variant exposes its graphql-java form via a common
  `schemaType()` accessor (or equivalent). SDL-declared variants carry the
  reference from the assembled schema; directive-synthesised variants carry a
  programmatically-built instance.
- `ObjectTypeGenerator`, `EnumTypeGenerator`, `InputTypeGenerator`,
  `GraphitronSchemaClassGenerator` iterate `schema.types()` only. No fallback
  loops. No directive probing at emit time.
- The `assembled GraphQLSchema` returned by the builder is coherent with the
  model: every type in `schema.types()` is findable via
  `assembled.getType(name)`. Downstream runtime consumers (validator,
  generated `GraphitronSchema.build()`) see a uniform view.
- `FieldWrapper.Connection` stays at its current shrunk shape
  (`connectionNullable`, `defaultPageSize`). All per-Connection-type
  information lives on `ConnectionType` in `schema.types()`.
- `@asFacet` (see [plan-faceted-search.md](plan-faceted-search.md)) inherits
  this as precedent: classifier produces dedicated variants; emitters read
  the model; no parallel scan.

## Pre-launch justification

We have no production users. Carrying D1-D4 into a v1 release means every
future emitter author has to know that `schema.types()` isn't actually
authoritative, that directive-probing lingers in one place, that the
assembled schema might miss types the model claims exist, and that two of
the four schema emitters read from different sources. Each deviation is
individually small; taken together they make the system harder to extend
(notably for `@asFacet`, which lands on the same seams).

Closing them now costs one compact phase per deviation, before the surface
area grows. The alternative — fix after launch — requires a migration for
every consumer who came to rely on the current behaviour in the meantime.

## Phase 4 — classifier records every emittable object type (closes D1)

**Scope.** Make `schema.types()` complete for object, interface, and union
types. `ObjectTypeGenerator.generate` loses its fallback iteration over
`assembled.getAllTypesAsList()`.

**Touch points.**

- `GraphitronType` — add a variant for plain object types without directives.
  Naming candidates: `PojoObjectType` (parallels `PojoInputType`),
  `PlainObjectType`, `NestingType` (matches the "nesting" concept the
  classifier uses elsewhere). Pick one; keep it terse.
- `TypeBuilder.classifyType` — plain `GraphQLObjectType` without
  `@table` / `@record` / `@error` returns the new variant instead of `null`.
- `TypeBuilder.buildTypes` — add case to the enrich-phase switch.
- `GraphitronSchemaValidator.validateType` — add exhaustive case (no
  structural validation needed for plain objects).
- `ObjectTypeGenerator.generate` — delete the second loop over
  `assembled.getAllTypesAsList()`. Iterate `schema.types()` only. Delete
  `seen` / `emitForName` dual-iteration machinery.
- `GraphitronSchemaClassGenerator.planFor` — iterate `schema.types()` only
  for non-enum kinds; the enum fallback remains until Phase 6.

**Test coverage.** Classifier test: plain `type Foo { bar: String }` classifies
as the new variant. Snapshot diff on the test-spec's generated `schema/`
directory before/after — zero diff (same TypeSpecs produced).

**Risk.** Low. The variant is additive; most code paths are
exhaustive-switch handlers that fail to compile if missed.
`VariantCoverageTest` requires a classification-case entry for the new
variant.

## Phase 5 — classifier rewrites carriers + rebuilds assembled (closes D2, D3)

**Scope.** Classifier rebuilds each directive-driven `@asConnection`
carrier's parent `GraphQLObjectType` via `.transform()` — replacing the
bare-list return type with a Connection reference and appending `first` /
`after` arguments. It also rebuilds the `assembled GraphQLSchema` with
`.additionalType(...)` for synthesised types. Emission reads the rewritten
assembled schema directly; no directive probing.

**Touch points.**

- `GraphitronSchemaBuilder.buildBundle` — after `promoteConnectionTypes`
  populates `ctx.types` with synthesised variants, run a second pass that:
  1. For each directive-driven carrier field, rebuild its parent
     `GraphQLObjectType` with a transformed `GraphQLFieldDefinition` (return
     type → Connection `typeRef`, appended pagination args).
  2. Build a new `GraphQLSchema` — either
     `GraphQLSchema.newSchema(existing)...additionalType(synthConn)...` or
     via `SchemaTransformer`. Prototype both; pick the shorter shape that
     preserves applied directives on rebuilt parents.
  3. Return the rebuilt `assembled` in the `Bundle`.
- `ObjectTypeGenerator.buildFieldDefinition` — delete the
  `field.hasAppliedDirective("asConnection")` probe, `resolveConnectionName`
  helper, `defaultPageSizeFor` fallback, and `baseTypeName` helper. The
  carrier's graphql-java form already has the final return type and
  arguments.
- `FieldWrapper.Connection.defaultPageSize` stays — fetcher wiring reads it
  for keyset pagination, and its value matches the `first` argument's
  default (both populated from `@asConnection(defaultFirstValue:)` at
  classification time).
- `AppliedDirectiveEmitter` still filters `@asConnection` as a generator-only
  directive; the carrier's applied-directive list won't leak it.

**Test coverage.** Classifier test: after `buildBundle`, a carrier like
`stores: [Store!]! @asConnection` has
`assembled.getObjectType("Query").getFieldDefinition("stores")` returning a
field whose type resolves to `QueryStoresConnection!` with `first` / `after`
arguments; `assembled.getType("QueryStoresConnection")` returns the
synthesised object type. Existing execution tests cover end-to-end; a
targeted `SchemaPrinter` snapshot guards against drift in SDL-level output.

**Risk.** Medium. Graphql-java's schema-rebuild surface is fussy: every type
reference in the rebuilt schema must resolve; applied directives on rebuilt
parents need to be preserved; the code registry must survive. Biggest
unknown — worth a spike before committing.

## Phase 6 — model-driven enum and input emission (closes D4)

**Scope.** `EnumTypeGenerator` and `InputTypeGenerator` read from
`schema.types()` only.

**Touch points.**

- `GraphitronType` — add an `EnumType(name, location, GraphQLEnumType schemaType)`
  variant. Thin by default: enum values and per-value directives are pure
  schema shape unless current classifier code interprets enum-level
  directives (audit `@index` / `@order` handling during impl).
- `TypeBuilder.classifyType` — returns `EnumType` instead of `null` for
  `GraphQLEnumType`.
- `EnumTypeGenerator.generate(GraphitronSchema)` — iterate `schema.types()`
  for `EnumType` variants; read `schemaType()` for emission.
- `InputTypeGenerator.generate(GraphitronSchema)` — iterate `schema.types()`
  for `InputType` / `TableInputType` variants. Move the
  `InputDirectiveInputTypes.NAMES` filter into the classifier so the
  generator doesn't need an `assembled` parameter.
- `GraphitronSchemaClassGenerator.planFor` — enum fallback branch drops;
  enums now come through `schema.types()`.
- `GraphQLRewriteGenerator.runPipeline` — emitter call sites simplify.

**Test coverage.** Classification-case additions for the new `EnumType`
variant; snapshot diff on generated enum / input TypeSpecs.

**Risk.** Low. Structural parallel to Phase 4; no novel mechanism.

## Phase 7 — common `schemaType()` accessor (optional)

**Scope.** Factor the per-variant `schemaType()` accessor into a method on
`GraphitronType`. Removes duplication across variants that carry their
graphql-java form.

**Touch points.**

- `GraphitronType` — add `GraphQLNamedType schemaType()` as an abstract
  method.
- Every variant implements — SDL variants receive their form at
  classification time (`TypeBuilder.classifyType` already has it in hand);
  synthesised variants already carry it.
- `ObjectTypeGenerator.graphqlTypeFor` helper — deletes; every variant
  answers its own `schemaType()`.

**Test coverage.** None new — pure refactor, existing tests verify.

**Risk.** Low. Medium touch (constructor-arity break across the whole
`GraphitronType` hierarchy). Do only if Phase 4-6 surface duplication worth
collapsing; may not be worth the churn.

## Order + gating

Strict order on the closure phases:

1. **Phase 4** (D1) — plain object classification. Unblocks removing the
   fallback loop.
2. **Phase 5** (D2 + D3) — classifier rewrites + assembled rebuild. Largest
   change; deserves its own commit.
3. **Phase 6** (D4) — enum + input emitters flip. Independent of 4-5 but
   lands cleaner after 4.
4. **Phase 7** — optional `schemaType()` uplift. Skip unless the previous
   phases reveal motivating duplication.

Each phase leaves the build green and runtime behaviour byte-identical on
existing fixtures.

## Non-goals

- **Rewriting `FetcherRegistrationsEmitter`.** Phase 3 already switched it
  to `schema.types()`.
- **Collapsing `GraphitronType` domain variants.** `TableType` / `NodeType` /
  `ResultType` etc. stay; shape extensions layer on top.
- **Touching the generated `GraphitronSchema.build()` output.** Stable since
  Phase 2; cleanup is generator-internal.
- **Moving away from graphql-java types in the model.** Shape records were
  considered and rejected in design; model still carries graphql-java forms
  for schema emission.
- **`@asFacet` scaffolding.** Facets land on the pattern this plan
  establishes (see [plan-faceted-search.md](plan-faceted-search.md)), but
  their classifier contribution is that plan's concern.

## Testing strategy

- **Classification coverage.** `VariantCoverageTest` demands classification
  cases for new variants (Phase 4 plain object, Phase 6 enum).
- **Snapshot diff.** Run generate-sources on the rewrite test-spec before
  and after each phase; diff the `schema/` output directory; assert
  byte-identical output on Phases 4, 6, 7. Phase 5 may legitimately change
  ordering or formatting when the assembled schema rebuild shifts field
  definitions; diff should still be small and explicable.
- **Execution.** Existing `GraphQLQueryTest` cursor-pagination tests are the
  runtime safety net for Phase 5. They exercise classifier → emission →
  compilation → execution against Sakila.
- **Plan-shipped gap.** Add a direct `ObjectTypeGenerator` test that
  constructs a `ConnectionType` variant (without the classifier) and passes
  it through emission. Today the emission path for synthesised variants is
  only reachable via classification, so a classifier bug could mask an
  emitter bug.

## Risks and open questions

1. **Graphql-java schema rebuild (Phase 5).** `SchemaTransformer` vs.
   `GraphQLSchema.newSchema(existing).additionalType(...)` — prototype both
   on the test-spec schema and measure before committing. Prefer the shorter
   shape; fall back to `SchemaTransformer` only if `newSchema` drops applied
   directives on rebuilt parents.
2. **`defaultPageSize` redundancy.** After Phase 5 it appears both on
   `FieldWrapper.Connection` and as the default on the carrier's `first`
   argument. Two sources for the same number. Collapse or annotate with an
   invariant; decide during Phase 5 impl.
3. **Enum variant richness.** Whether `EnumType` is thin (name + schemaType)
   or richer depends on whether the classifier currently reads enum-level
   directives like `@index` / `@order`. Audit during Phase 6 impl.
4. **`assembled` parameter on emitter signatures.** Should drop entirely
   after Phase 6. Confirm during Phase 6 review.

## References

- Shipped commits: `0aef2c7` (Phase 1), `0ecde9d` (Phase 2), `237d6d3` (Phase 3).
- `graphitron-rewrite/.../model/GraphitronType.java` — sealed hierarchy;
  Phase 4 / 6 / 7 targets.
- `graphitron-rewrite/.../TypeBuilder.java` — `classifyType` and
  `buildTypes`; Phase 4 / 6 targets.
- `graphitron-rewrite/.../GraphitronSchemaBuilder.java` —
  `promoteConnectionTypes` populates synthesised variants today; Phase 5
  adds the `.transform()` + schema rebuild.
- `graphitron-rewrite/.../generators/schema/ObjectTypeGenerator.java` —
  `buildFieldDefinition` probe deleted in Phase 5; fallback loop deleted in
  Phase 4.
- `graphitron-rewrite/.../generators/schema/EnumTypeGenerator.java`,
  `.../InputTypeGenerator.java` — Phase 6 targets.
- [plan-faceted-search.md](plan-faceted-search.md) — downstream consumer;
  inherits the pattern after Phase 5 lands.