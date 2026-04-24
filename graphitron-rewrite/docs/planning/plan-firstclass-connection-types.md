# Plan: promote Connection, Edge, and PageInfo to first-class `GraphitronType` variants

> **Status:** In Review
>
> Today `schema.types()` lies about what the generator emits. A schema with
> `stores: [Store!]! @asConnection` gets `Store` as a `TableType` entry and
> nothing for `QueryStoresConnection` / `QueryStoresEdge` / `PageInfo` — those
> three types are magicked into the output at emit time by `ConnectionSynthesis`
> scanning the assembled schema in parallel. The carrier field's
> `FieldWrapper.Connection` holds the edge of the missing nodes: it says "this
> field is a Connection of Store" and points at types that aren't in the model.
>
> This plan promotes Connection, Edge, and PageInfo to first-class
> `GraphitronType` variants so `schema.types()` is honest. The parallel
> `ConnectionSynthesis` mechanism dissolves; emission iterates the model
> uniformly; structural and directive-driven Connections stop being artificial
> different paths.

## Problem

Three consequences of the current collapse:

1. **The model misrepresents the output.** `schema.types()` claims to be the
   type index; a caller who trusts it cannot find `QueryStoresConnection`
   because the classifier never records it. Any future tool that iterates
   `types()` for emission, validation, introspection, or diagnostics gets an
   incomplete answer.
2. **Directive-driven vs. structural Connections are artificially different.**
   A hand-written `FilmsConnection { edges, nodes, pageInfo }` produces a
   `TableType` in `types()` (misclassified, but present). A directive-driven
   `[Film!]! @asConnection` produces nothing in `types()`. Both are
   "Connection of Film" semantically; the model distinguishes them for
   implementation reasons, not design reasons.
3. **`ConnectionSynthesis` exists to paper over the first two.** It scans the
   assembled schema three times (once each from
   `GraphQLRewriteGenerator.runPipeline`, `ObjectTypeGenerator.generate`,
   `GraphitronSchemaClassGenerator.generate`) to re-derive the types the
   classifier already saw but didn't record. The parallel scan is why the
   misfire bug in `ObjectTypeGenerator.buildFieldDefinition` exists (probing
   by derived name instead of directive presence) and why the
   fetcher-body duplication in `FetcherRegistrationsEmitter` exists (the
   structural path needs bodies keyed by name; the synthesis path reinvents
   them inline).

The technical debt is that `FieldWrapper.Connection` absorbed responsibility
that belongs on dedicated type variants. Moving it back makes every downstream
problem disappear.

## Current state

- `GraphitronType` sealed hierarchy at
  `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronType.java`
  has variants for every SDL type the classifier recognises (TableType,
  NodeType, ResultType family, RootType, InterfaceType, UnionType, ErrorType,
  InputType family, TableInputType, UnclassifiedType). No variant for
  Connection, Edge, or PageInfo.
- `FieldWrapper.Connection` at
  `.../model/FieldWrapper.java` carries `connectionNullable`, `itemNullable`,
  `defaultPageSize`, `connectionName`. The `connectionName` field is the only
  link between a carrier and its (missing) type.
- `FieldBuilder.buildWrapper` at `FieldBuilder.java:384-412` has two arms
  producing `FieldWrapper.Connection` — directive-driven (line 390) and
  structural (line 406). Neither adds a type to `schema.types()`.
- `ConnectionSynthesis.java` (385 lines) exists solely to re-derive what the
  classifier saw. It scans the assembled schema, builds a Plan, emits
  TypeSpecs for the missing types, and feeds the same plan back to
  `ObjectTypeGenerator.buildFieldDefinition` (carrier rewrite) and
  `GraphitronSchemaClassGenerator.generate` (`.additionalType(...)` wiring).
- Runtime behaviour is correct today — the TypeSpecs are emitted, the
  `additionalType(...)` calls are generated, fetchers wire up. The lie is
  in the model, not the output.

## Desired end state

- `GraphitronType` has three new variants: `ConnectionType`, `EdgeType`,
  `PageInfoType`. Each carries the semantic info it needs (element type,
  nullability, shareable flag).
- The classifier populates these variants in `schema.types()` whenever it
  encounters `@asConnection` on a bare list (directive-driven) or a
  hand-written Connection-shaped type (structural). Both paths produce the
  same variant.
- Each variant exposes its graphql-java form — either as a constructor field
  populated at classification time (SDL: reference from the assembled schema;
  synthesised: built programmatically) or via a `schemaType()` method. The
  generator reads this uniformly.
- `ObjectTypeGenerator` and `GraphitronSchemaClassGenerator` iterate
  `schema.types()` (not `assembled.getAllTypesAsList()`) and emit from the
  variant's graphql-java form.
- `ConnectionSynthesis.java` is deleted. Its test file is deleted; assertions
  migrate to classifier and emitter tests.
- `FieldWrapper.Connection` keeps only what's per-carrier-site
  (`defaultPageSize`); fields that describe the Connection *type*
  (`connectionName`, `itemNullable`) move to `ConnectionType`.
- The assembled `GraphQLSchema` produced by `GraphitronSchemaBuilder` contains
  the synthesised types so runtime introspection and validation see them.

## Scope: why this is a Connection-only problem

Every other `GraphitronType` variant has a corresponding entry in `types()`
when its SDL type is present. `@record` → `ResultType` family. `@error` →
`ErrorType`. `@table` → `TableType`. `@node` → `NodeType`. `@table` on input
→ `TableInputType`. Hand-written enums, interfaces, unions, and inputs land
as their corresponding variants. None of them synthesise an SDL type the
author didn't write, and none of them get collapsed into a field wrapper.

Connection is unique because `@asConnection` *is* a synthesis directive — it
instructs the generator to add types the author did not declare. The current
code handles the synthesis at emit time and neglects to notify the model.
No other directive has this shape today; when `@asFacet` lands it will
(separately, per [plan-faceted-search.md](plan-faceted-search.md)), and the
pattern this plan establishes — directives that synthesise types populate
first-class variants via the classifier — is the precedent `@asFacet`
inherits.

## Phase 1 — model additions (no emitter changes)

**Scope.** Introduce the three variants and have the classifier populate
them. Pure additive. No existing emitter reads them yet;
`ConnectionSynthesis` remains the source of truth for emission.

**Touch points.**

- `GraphitronType.java` — add `ConnectionType`, `EdgeType`, `PageInfoType` to
  the `permits` clause and declare the records:

  ```java
  record ConnectionType(
      String name, SourceLocation location,
      String elementTypeName,
      String edgeTypeName,
      boolean itemNullable,
      boolean shareable,
      GraphQLObjectType schemaType         // graphql-java form
  ) implements GraphitronType {}

  record EdgeType(
      String name, SourceLocation location,
      String elementTypeName, boolean itemNullable, boolean shareable,
      GraphQLObjectType schemaType
  ) implements GraphitronType {}

  record PageInfoType(
      String name, SourceLocation location, boolean shareable,
      GraphQLObjectType schemaType
  ) implements GraphitronType {}
  ```

- `FieldBuilder.buildWrapper` (both arms) — after producing
  `FieldWrapper.Connection`, contribute the corresponding `ConnectionType`,
  `EdgeType`, and (when absent) `PageInfoType` to the schema under
  construction. Dedup by name. Resolve name / element / flags from the
  existing wrapper-building logic; compute `schemaType` by:
  - For the structural arm: reference the already-built `GraphQLObjectType`
    from the assembled schema.
  - For the directive-driven arm: build a `GraphQLObjectType` programmatically
    using graphql-java builders, with fields `edges` / `nodes` / `pageInfo`
    (Connection), `cursor` / `node` (Edge), or the four Relay fields
    (PageInfo). Shareable applied via `.withAppliedDirective(...)` as today.
- `GraphitronSchemaBuilder` — also ensure the assembled `GraphQLSchema`
  includes the synthesised types. Either (a) mutate the registry before
  graphql-java builds the schema, or (b) rebuild a fresh schema with
  `additionalType(...)` calls from the synthesised variants. Pick (b) — it's
  local to the builder and doesn't touch `TypeDefinitionRegistry`.

**Test coverage.** One classifier test per variant: classify
`type Query { stores: [Store!]! @asConnection } type Store { id: ID! }`,
assert `schema.types().get("QueryStoresConnection") instanceof ConnectionType`
with the right element name, edge name, flags. Parallel test for the
structural path with a hand-written `FilmsConnection` — same
`ConnectionType` variant produced, flagged as structural via the
`connectionName == typeName` coincidence (or via an explicit marker if
distinguishing is useful — probably not, given the goal is to erase the
distinction).

**Risk.** Low. Additive; nothing downstream reads the new variants in
this phase.

## Phase 2 — emitters read from `types()`; delete `ConnectionSynthesis`

**Scope.** Flip `ObjectTypeGenerator` and `GraphitronSchemaClassGenerator`
to iterate `schema.types()` and read graphql-java form from each variant.
Delete `ConnectionSynthesis`.

**Touch points.**

- `ObjectTypeGenerator.generate(GraphitronSchema schema, Map<String, CodeBlock> fetcherBodies)` —
  iterate `schema.types().values()`, dispatch on variant kind where needed
  (object-like → `buildObjectTypeSpec`; interface → `buildInterfaceTypeSpec`;
  union → `buildUnionTypeSpec`; ConnectionType / EdgeType / PageInfoType →
  use their `schemaType()` through the object-like path). The per-variant
  dispatch can be an exhaustive sealed switch matching the recent
  `TypeFetcherGenerator` refactor (commit `3357928`).
- `ObjectTypeGenerator.buildFieldDefinition` — delete the `@asConnection`
  probe (lines 205-215, 221-228, 242-248). The carrier field's return type
  is already rewritten in the graphql-java form held on the parent type
  (via `.transform(...)` in the classifier); the generator reads that
  rewritten form directly. First/after args are part of the rewritten
  arguments list.
- `GraphitronSchemaClassGenerator.planFor` — iterate `schema.types()`
  instead of `assembled.getAllTypesAsList()`. Delete the synthesised-types
  loop (lines 91-101). One loop over all type names; Connection/Edge/PageInfo
  entries flow through naturally.
- `GraphQLRewriteGenerator.runPipeline` — stop passing `assembled` to
  `ObjectTypeGenerator` and `GraphitronSchemaClassGenerator`. The bundle
  still produces `assembled` for runtime use; emission no longer depends
  on it.
- `ConnectionSynthesis.java` — delete.
- `ConnectionSynthesisTest.java` — delete; assertions about the plan move
  to classifier tests (Phase 1), assertions about emitted TypeSpec content
  move to `ObjectTypeGeneratorTest` with the variant as input.
- `FetcherRegistrationsEmitter.connectionBody` / `edgeBody` — stay as-is;
  they produce fetcher bodies keyed by name, which flow through the normal
  `ObjectTypeGenerator` path now that Connection/Edge names are in
  `types()`.

**Test coverage.**

- Snapshot diff: run generate-sources on the full rewrite test-spec before
  and after, assert zero diff on every `<TypeName>Type.java` file. Runtime
  behaviour must be byte-identical.
- Existing `GeneratedSourcesSmokeTest` assertion for
  `QueryStoresConnectionType` / `QueryStoresEdgeType` — unchanged, passes
  on the new path.
- Existing `GraphQLQueryTest` cursor-pagination execution tests —
  unchanged, passes on the new path.
- New negative test: a schema with field `@asConnection(connectionName: "FooConnection")`
  and a sibling non-directive field whose derived name collides with
  `FooConnection`. Assert the sibling is not rewritten (the misfire bug
  can't happen anymore because the probe is gone — this test locks in
  that invariant).

**Risk.** Medium. The snapshot diff is the safety net. Two nuances to
watch:

- **Carrier rewrite via `.transform(...)`.** Graphql-java's immutable types
  mean rewriting the carrier's parent requires rebuilding the parent's
  `GraphQLObjectType`. The rebuilt parent replaces the original in
  `schema.types()`. Test that this chains correctly through parent types.
- **Assembled schema coherence.** After Phase 1's (b) rebuild, the
  assembled schema contains synthesised types. Validator coverage on a
  schema exercising `@asConnection` confirms the runtime still validates.

## Phase 3 — simplify `FieldWrapper.Connection`

**Scope.** Remove fields from `FieldWrapper.Connection` that are now
properties of `ConnectionType`. Update callers.

**Touch points.**

- `FieldWrapper.Connection` reduces to `(connectionNullable, defaultPageSize)`.
  `connectionName` and `itemNullable` are derivable from the carrier's return
  type (`ConnectionType.name()` / `ConnectionType.itemNullable()`).
- Call sites that read `conn.connectionName()` or `conn.itemNullable()`
  switch to reading from the carrier field's return-type reference into
  `schema.types()`. Grep-driven sweep; mechanical.
- The structural-detection convenience constructor at `FieldWrapper.java:80`
  goes away — nothing distinguishes structural from directive-driven now
  that both produce a `ConnectionType` entry.

**Test coverage.** Existing tests still pass. The wrapper's shrunk
record is the only surface change; validators/classifier/fetcher paths
that referenced the old fields now go through `schema.types()`.

**Risk.** Low. Grep sweeps are mechanical; any missed call site fails
to compile.

## Order + gating

Strict order: Phase 1 → Phase 2 → Phase 3. Each phase leaves the build
green. Phase 1 adds surface with no consumer; Phase 2 swings the consumer
over and deletes the old mechanism; Phase 3 cleans up the now-redundant
wrapper fields.

Phases 1 and 2 can land in separate commits for review readability, or
together if the diff stays tractable. Phase 3 is best kept as its own
commit — the wrapper shrink touches call sites across fetcher / condition /
validator code and reads more clearly as an isolated change.

## Non-goals

- **Generalising the variant introduction pattern to other macros.**
  `@asFacet` will follow the same shape when it lands — classifier produces
  dedicated variants, emitter reads them uniformly — but `@asFacet`'s plan
  owns that work. This plan only fixes the Connection collapse.
- **Removing `FieldWrapper.Connection` entirely.** The wrapper still
  encodes "this carrier field returns a Connection" distinct from the
  Connection type itself, which matters for per-site metadata like
  `defaultPageSize`. The wrapper shrinks; it doesn't vanish.
- **Reshaping the `FetcherRegistrationsEmitter` output.** It keeps producing
  bodies keyed by type name; Phase 2 just relies on those bodies being
  found by `ObjectTypeGenerator` via the normal path now that the type
  names exist in `types()`.
- **`assembled` schema removal from the bundle.** Validator and any future
  runtime-wired consumer still want a full `GraphQLSchema`. The bundle
  continues to produce one. Only emission stops reading it.
- **Auditing other variants for similar collapses.** Every other
  `GraphitronType` variant corresponds to an SDL-declared type; none
  synthesise. Nothing to audit.

## Testing strategy

- **Unit (Phase 1):** `FieldBuilderTest` / `GraphitronSchemaBuilderTest`
  assertions on the three new variants being present in `types()` with
  correct data. Two new cases per variant (directive-driven and
  structural inputs).
- **Unit (Phase 2):** `ObjectTypeGeneratorTest` takes a
  `ConnectionType` variant and asserts the emitted TypeSpec has the
  right field list (edges/nodes/pageInfo), etc. Migrated assertions
  from the deleted `ConnectionSynthesisTest`.
- **Snapshot diff (Phase 2):** pre/post generate-sources diff on the
  full rewrite test-spec — zero diff on every emitted file.
- **Execution (unchanged):** existing `GraphQLQueryTest` cursor-pagination
  tests cover the round trip; Phase 2 keeps them green.
- **Regression:** an execution fixture using a hand-written structural
  Connection (if one exists in test-spec) must continue to work without
  modification. If the test suite lacks one, add a small one — the
  structural path is hit by the same code post-refactor and deserves
  explicit coverage.

## Risks and open questions

1. **Schema rebuild vs. registry mutation.** Phase 1's assembled-schema
   coherence needs synthesised types included. Rebuilding with
   `additionalType(...)` is local but adds a step; mutating the registry
   is more invasive but reuses `SchemaGenerator`. Decide during Phase 1
   implementation.
2. **`schemaType` on variants vs. parallel map.** Storing `GraphQLObjectType`
   on each variant is tight coupling but matches the principle "variants
   know how they appear in the schema." A parallel
   `Map<String, GraphQLNamedType>` on `GraphitronSchema` decouples but
   duplicates keys with `types()`. Prefer on-variant; swap if a concrete
   reason appears.
3. **Structural-vs-directive marker.** Some callers today branch on
   "is this Connection synthesised?" (in `FieldBuilder.buildWrapper`
   `:405` structural constructor). After this plan, they're unified.
   Sweep to confirm no remaining caller needs to know; delete the
   convenience constructor.
4. **Interface-type Connections.** `ConnectionSynthesis.buildPlan` scans
   interface types too. Confirm the classifier's `FieldBuilder` also
   visits interface fields; if not, a small extension covers parity
   (no-test-coverage today, so this is latent).

## References

- `graphitron-rewrite/.../model/GraphitronType.java` — Phase 1 target.
- `graphitron-rewrite/.../model/FieldWrapper.java` — Phase 3 target.
- `graphitron-rewrite/.../FieldBuilder.java:384-412` — classifier entry
  points for the two Connection arms; extended in Phase 1.
- `graphitron-rewrite/.../GraphitronSchemaBuilder.java` — bundle
  construction; assembled-schema coherence concern from Phase 1.
- `graphitron-rewrite/.../generators/schema/ConnectionSynthesis.java` —
  deleted in Phase 2.
- `graphitron-rewrite/.../generators/schema/ObjectTypeGenerator.java` —
  Phase 2 target; `@asConnection` branch deleted.
- `graphitron-rewrite/.../generators/schema/GraphitronSchemaClassGenerator.java` —
  Phase 2 target; synthesised-types loop deleted.
- `graphitron-rewrite/.../GraphQLRewriteGenerator.java:runPipeline` —
  `assembled` parameter drops from emitter signatures in Phase 2.
- [plan-faceted-search.md](plan-faceted-search.md) — downstream beneficiary
  of the first-class-variant pattern.
- Shipped emit-time baseline (to be replaced): `79af12c` (roadmap Done
  entry).
