# `@asConnection` `totalCount` field

> **Status:** Spec

## Overview

Add `totalCount: Int` to every Connection type the rewrite synthesises for `@asConnection`, and wire a resolver that runs `count(*)` against the same table and condition the page query uses. The connection fetcher attaches the parent field's `Table<?>` and `Condition` to `ConnectionResult` at fetch time; `ConnectionHelper.totalCount(env)` reads them back, looks up the request's `DSLContext` the same way the page fetcher does, and runs the count. Lazy on selection: graphql-java only invokes the resolver when the client selects the field, so the count SQL is skipped on every query that does not ask for it. Hand-written ("structural") Connection types that already declare a `totalCount: Int` field get the same wiring; ones that don't are unaffected.

## Current state

The rewrite has no `totalCount` anywhere in the Connection pipeline.

- **Schema synthesis.** `GraphitronSchemaBuilder.buildSynthesisedConnection` builds the directive-driven Connection with exactly three fields: `edges`, `nodes`, `pageInfo`. No `totalCount` is added; no flag, no opt-in.
- **First-class model.** `GraphitronType.ConnectionType` carries `name`, `elementTypeName`, `edgeTypeName`, `itemNullable`, `shareable`, `schemaType`. No count metadata.
- **Fetcher emission.** `TypeFetcherGenerator.buildQueryConnectionFetcher` emits a `select(page.selectFields()).from(tableLocal).where(condition).orderBy(...).seek(...).limit(...).fetch()` and returns a `ConnectionResult(result, page)`. Nothing computes or carries a count.
- **Connection-result carrier.** `ConnectionResultClassGenerator` emits a class with `result`, `pageSize`, `afterCursor`, `beforeCursor`, `backward`, `orderByColumns`. `trimmedResult()`, `hasNextPage()`, `hasPreviousPage()` are the only derivations.
- **Helper resolvers.** `ConnectionHelperClassGenerator` emits `edges`, `nodes`, `pageInfo`, `edgeNode`, `edgeCursor`. The `pageInfo` resolver returns a `LinkedHashMap` containing only `hasNextPage`, `hasPreviousPage`, `startCursor`, `endCursor`.
- **Wiring registration.** `FetcherRegistrationsEmitter.connectionBody` registers `edges`, `nodes`, `pageInfo` against the connection-type coordinates and nothing else.
- **Tests / fixtures.** No fixture in `graphitron-rewrite-fixtures` or the rewrite test schema selects `totalCount`; no pipeline or execution test covers it.

For comparison, the legacy `graphitron-schema-transform/.../MakeConnections.java` (lines 313-320) appends `totalCount: Int` to every synthesised Connection by default, and `graphitron-codegen-parent` emits a per-table count method (`FetchCountDBMethodGenerator`) that the runtime helper invokes only when the field is selected (`ServiceDataFetcherHelper#L102`). The rewrite has neither half.

## Desired end state

Synthesised Connection types carry a `totalCount: Int` field (nullable, matching the legacy default). `ConnectionResult` gains two fields, `Table<?> table` and `Condition condition`, populated by the connection fetcher with the same values it uses for `.from(table).where(condition)` on the page query. `ConnectionHelper.totalCount(env)` pulls them back off `ConnectionResult`, looks up the `DSLContext` via `graphitronContext(env).getDslContext(env)` (the existing per-fetcher convention, now shared with the helper class), and returns `dsl.selectCount().from(cr.table()).where(cr.condition()).fetchOne(0, Integer.class)`. graphql-java only invokes the resolver when the client selects `totalCount`, so the count SQL is skipped on every query that does not ask for it; no separate selection-set inspection is needed.

The carrier-not-closure shape keeps `ConnectionResult` general: future per-connection derivables (faceted search, aggregates) read the same `(table, condition)` pair and add their own helper methods without changing `ConnectionResult`'s constructor surface.

Structural (hand-written) Connection types that declare `totalCount: Int` get the same wiring registration, gated on both presence and `Int`-typing of the field. Structural connections that do not declare one are unchanged: emitting a fetcher coordinate for a non-existent field is a build-time error in graphql-java, so the registrations emitter must check the `ConnectionType.schemaType()` field shape. Structural fields named `totalCount` with any other type are quietly ignored rather than mis-wired.

The behaviour is on by default; no Maven flag mirrors the legacy `totalCountFieldInConnectionsEnabled`. The rewrite has no production users yet, so making it conditional now would only add surface to retire later.

## What we're NOT doing

- **`totalCount` on nested / Split-Connection carriers.** The first cut targets root `QueryTableField` connections only, the path that goes through `buildQueryConnectionFetcher`. Split-Connection scatter (`scatterConnectionByIdx`) and any future `ChildField`-side connection wiring count rows per parent and need a different aggregation strategy (`COUNT(*) ... GROUP BY parent_key` or per-parent batched counts). Captured as a Backlog follow-up below; out of scope here.
- **Count over JOINed conditions.** `buildQueryConnectionFetcher` emits a single-table `.from(tableLocal).where(condition)` today; the count mirrors that exactly. Conditions that pull in correlated subqueries through jOOQ already work with `selectCount().from(...).where(...)` because the same `Condition` value is reused. If a future condition shape needs explicit `JOIN` clauses, both the page query and the count will need that work; out of scope here, will follow whatever the page side does.
- **Multitable / interface / union connections.** Legacy has a separate `getCodeForMultitableCountMethod` arm because each backing table needs its own count and the totals are summed. None of these field shapes are emitted by the rewrite today (`QueryTableInterfaceField` / `QueryUnionField` are stubs, roadmap §3). The count plumbing here is a single-table count; when interface/union fetchers ship they will need to extend it.
- **Caching the count across executions.** The helper runs the count when graphql-java invokes the resolver; no memoisation table, no dataloader. Per-instance memoisation on `ConnectionResult` against alias-duplicated `totalCount` selections is also not added: the case is exotic, and protection against malicious alias spam belongs at the depth/complexity-limit layer, not here.
- **Count-only execution path.** Legacy supports running just the count when only `totalCount` is selected (skipping the page query). Worth a follow-up Backlog item; not in this plan because the conditional emission interacts with the cursor-encoding and page-trim invariants that `ConnectionResult` currently asserts unconditionally.
- **Maven configuration flag.** No `totalCountFieldInConnectionsEnabled` analogue. See "Desired end state" rationale.
- **Faceted-search totals.** `plan-faceted-search.md` lists `totalCount` in its desired Relay shape; this plan ships the underlying field and resolver so faceted search can rely on it being there. Faceted search itself is unchanged.

## Implementation approach

### 1. Synthesise the `totalCount` field

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilder.java`, in `buildSynthesisedConnection`.

Append a `totalCount: Int` field (nullable) to the builder before returning, alongside the existing `edges`, `nodes`, `pageInfo` fields:

```java
var totalCountField = GraphQLFieldDefinition.newFieldDefinition()
    .name("totalCount")
    .type(GraphQLTypeReference.typeRef("Int"))
    .build();
```

and `.field(totalCountField)` on the builder. Nullable (no `GraphQLNonNull` wrap) so the field type can survive the lazy / count-skipped path naturally; matches legacy.

The synthesised PageInfo and Edge are unchanged.

### 2. Carry the source table and condition on `ConnectionResult`

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/util/ConnectionResultClassGenerator.java`.

Add two fields to the carrier:

- `org.jooq.Table<?> table`
- `org.jooq.Condition condition`

Thread them through the constructors:

- **Primary constructor** (currently 6-arg, becomes 8-arg): `table` and `condition` as the last two parameters.
- **`(result, page)` convenience constructor** (existing): delegates with `null, null`. This is the path Split-Connection scatter (`SplitRowsMethodEmitter.scatterConnectionByIdx`) uses today; selecting `totalCount` on a Split-Connection field will return `null` until that wiring is filled in (see "What we're NOT doing" and the corresponding Backlog follow-up).
- **New `(result, page, table, condition)` convenience constructor**: delegates with the supplied values. This is what `TypeFetcherGenerator.buildQueryConnectionFetcher` calls.

Add accessors `table()` and `condition()`. They return `null` for the Split-Connection path; the helper checks for null before issuing SQL.

Update the class Javadoc to mention the new fields and the Split-Connection `null` carve-out.

### 3. Pass table and condition into the constructor

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`, in `buildQueryConnectionFetcher`.

`tableLocal` and `condition` are already in scope at the return site (declared earlier in the method via `declareTableLocal` and `buildConditionCall`). Replace the existing `return new ConnectionResult(result, page);` with the four-arg form:

```java
return new ConnectionResult(result, page, <tableLocal>, condition);
```

No closure, no supplier. The fetcher's responsibility is to capture the inputs that downstream resolvers will derive from; the helper class is where the derivation lives.

### 4. Generate a `totalCount` resolver in `ConnectionHelper`

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/util/ConnectionHelperClassGenerator.java`.

Add `totalCount(env)` alongside the other static resolvers:

```java
public static Integer totalCount(DataFetchingEnvironment env) {
    ConnectionResult cr = env.getSource();
    if (cr.table() == null || cr.condition() == null) return null;
    DSLContext dsl = graphitronContext(env).getDslContext(env);
    return dsl.selectCount().from(cr.table()).where(cr.condition()).fetchOne(0, Integer.class);
}
```

The helper class needs its own private static `graphitronContext(env)` shim, mirroring the one `TypeFetcherGenerator.buildGraphitronContextHelper` emits onto each Fetchers class:

```java
private static GraphitronContext graphitronContext(DataFetchingEnvironment env) {
    return env.getGraphQlContext().get(GraphitronContext.class);
}
```

Emit it once on the helper class. Register `totalCount` on the `TypeSpec` builder. Update the class Javadoc bullet list to include `totalCount(env)`.

### 5. Wire `totalCount` from the registrations emitter

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/schema/FetcherRegistrationsEmitter.java`, `connectionBody`.

Append a fourth registration line, gated on the field being present on the connection's `schemaType()` *and* typed `Int`:

```java
GraphQLFieldDefinition fd = connectionType.schemaType().getFieldDefinition("totalCount");
if (fd != null && GraphQLTypeUtil.unwrapAll(fd.getType()) == Scalars.GraphQLInt) {
    body.add("\n.dataFetcher($T.coordinates($S, $S), $T::totalCount)",
        FIELD_COORDS, connectionTypeName, "totalCount", helper);
}
```

The synthesised path always trips the gate because Step 1 unconditionally adds the field as `Int`. The structural path trips it only when the SDL author declared `totalCount: Int` (or `Int!`); structural fields named `totalCount` with any other type are quietly ignored rather than mis-wired.

`connectionBody` currently takes only `(connectionTypeName, utilPackage)`. Change the signature to take the full `ConnectionType` so the gate can read `schemaType()`. While editing the file, fold in two incidental cleanups (small, in scope):

- **Drop the intermediate `Map<String, String> connectionTypeMap`** at lines 68-73 and iterate `schema.types().values()` directly for `ConnectionType` instances. The map collapses each `ConnectionType` record down to a `(name, edgeName)` pair only for the call site to need the rest back. Iterating the records directly gives `connectionBody` the full type without any intermediate projection, and the `putIfAbsent` defensiveness goes away (`schema.types()` is itself keyed by name, duplicates can't occur).
- **Remove the unused `ConnectionWiring` record** declared at the top of the class. It's a refactor stub that nothing references.

### 6. Roadmap entry

**File:** `graphitron-rewrite/docs/planning/rewrite-roadmap.md`.

The Active table already lists `@asConnection \`totalCount\` field` linked to this plan. No change there.

Add to the Backlog (Cleanup or Priority, implementer's call) for the explicitly-deferred follow-ups:

> **`totalCount` for nested / Split-Connection carriers** **[Backlog]**: the `@asConnection` `totalCount` plan covered root `QueryTableField` connections. Split-Connection scatter and any future `ChildField` connection wiring count rows per parent and need either `COUNT(*) ... GROUP BY parent_key` aggregation or a per-parent batched count. Until shipped, selecting `totalCount` on these carriers returns `null`.

> **`totalCount`-only execution skips the page query** **[Backlog]**: when `totalCount` is the only selected connection field, the connection fetcher could skip the page query entirely. Interacts with `ConnectionResult` invariants (`trimmedResult`, cursor encoding) that today assume a page result exists; needs `result == empty` paths added throughout. Defer until a real query pattern surfaces.

Two additional Backlog items, surfaced during plan review and worth filing alongside this work:

> **Remove `@shareable` propagation from synthesised Relay types** **[Backlog]**: `GraphitronSchemaBuilder` currently propagates `@shareable` from the source query field onto the synthesised Connection, Edge, and PageInfo types. This is overreach: a directive on a query field doesn't carry cross-subgraph sharing intent, and Federation v2 type-level `@shareable` silently extends to every present and future field on the type (including new synthesised siblings such as `totalCount`). Drop the propagation entirely. Users who need `@shareable` on PageInfo or any other Relay-shaped type declare a structural type with the directives directly; the synthesiser only runs when no structural type is provided. Touches `buildSynthesisedConnection`, `buildSynthesisedEdge`, `buildSynthesisedPageInfo`, the `pageInfoShareable` aggregation, the `shareable` constructor parameters, and the `shareable` fields on `ConnectionType` / `EdgeType` (audit consumers, likely removable).

> **Constrain Connection types to a single source field** **[Backlog]**: A hand-written `Connection` type may currently be the return type of multiple `QueryTableField`s, each with its own parent table and conditions. This forces request-time context (table, condition) to be captured per-field rather than carried on the schema model, blocks model-time metadata for `totalCount` and faceted search, and divides the synthesised path (always 1-to-1) from the structural path (n-to-1). Enforce 1-to-1 via a classifier check; emit a build-time error pointing at the conflicting fields. No production users yet, so this is the cheapest moment to draw the line.

### 7. Tests

Per `rewrite-design-principles.md` test tiers: no code-string assertions on generated bodies; the four tiers are unit, pipeline, compilation, execution.

**Pipeline test** (`GraphitronSchemaBuilderTest`): assert that the synthesised `ConnectionType` for an `@asConnection` carrier has a `totalCount: Int` (nullable) field on its `schemaType()`. One case is enough; the synthesis is unconditional so a single fixture covers all.

**Pipeline test for structural connections**: author three fixtures: a hand-written Connection-shaped type that declares `totalCount: Int`, one without `totalCount`, and one with `totalCount: String` (or some other non-Int type). Only the first ends up with `totalCount` wired; the other two do not. Verify by inspecting the emitted `FetcherRegistrationsClass` source via the existing pipeline-test scaffolding for "what coordinates were registered", which the firstclass-connections work already exercises.

**Compilation test**: the generated `ConnectionResult`, `ConnectionHelper`, and per-fetcher classes compile. This is `GeneratedSourcesSmokeTest`'s normal job; nothing new to add beyond making sure the new `(table, condition)` constructor parameters, the helper's `graphitronContext` shim, and the `totalCount` resolver are reached by an existing fixture's emission.

**Execution test** (`graphitron-rewrite/graphitron-rewrite-test/...`): pick an existing connection fixture and add a query that selects `totalCount` alongside `edges { node { ... } }`. Assert the count matches `dsl.fetchCount(<table>)` for the unfiltered case, and equals the WHERE-filtered count for a filter-bearing case.

**Negative execution test**: a second query that omits `totalCount` must not issue a count SQL statement. Register a jOOQ `ExecuteListener` on the test `DSLContext` that records the rendered SQL for each statement; the test fails if any captured statement begins with `select count`. The lazy-on-selection property is the whole point of the design and deserves a real assertion, not a faith-based one.

## Success criteria

### Automated

- `mvn test -pl :graphitron-rewrite` passes; includes the new pipeline cases (synthesised, structural-with-`totalCount: Int`, structural-without, structural-with-non-Int-`totalCount`).
- `(cd graphitron-rewrite && mvn test -Plocal-db)` passes; includes the execution case selecting `totalCount`, the filter-bearing variant, and the negative `ExecuteListener` assertion. `-Plocal-db` is required, see CLAUDE.md's fixtures-clobber note.
- Grepping the rewrite for `totalCount` returns hits in `GraphitronSchemaBuilder`, `ConnectionResultClassGenerator`, `ConnectionHelperClassGenerator`, `TypeFetcherGenerator`, `FetcherRegistrationsEmitter`, plus the new fixtures and tests, and nowhere else.
- Selecting `totalCount` on a Split-Connection carrier returns `null` without throwing.

### Manual

- Run a query against the example server that selects `edges { node { ... } } totalCount` against a paginated root field; verify the integer matches `SELECT count(*) FROM <table> WHERE <same predicate>`.
- Omit `totalCount` from the same query; verify (e.g. via SQL log) that no `select count` statement is emitted.

## References

Identifier-level references (line numbers drift):

- Synthesis site: `GraphitronSchemaBuilder.buildSynthesisedConnection`.
- Carrier class: `ConnectionResultClassGenerator.generate` (constructors, accessor list).
- Fetcher emission: `TypeFetcherGenerator.buildQueryConnectionFetcher` (the `dsl.select(...)...fetch();` block followed by `return new ConnectionResult(...)`).
- DSLContext lookup: `TypeFetcherGenerator.buildGraphitronContextHelper` (the per-class shim the helper class will mirror).
- Helper class: `ConnectionHelperClassGenerator.generate` (the `pageInfoMethod` siblings).
- Wiring registration: `FetcherRegistrationsEmitter.connectionBody` and the `connectionTypeMap` build at the top of `emit`.
- Connection model: `GraphitronType.ConnectionType` (no shape change needed; just used to gate the structural-path registration on `schemaType().getFieldDefinition("totalCount")`).
- Legacy reference: `MakeConnections#L313-320` (synthesis), `FetchCountDBMethodGenerator` (count emission), `ServiceDataFetcherHelper#L102` (lazy-on-selection).
- Sibling plan: [plan-faceted-search.md](plan-faceted-search.md) lists `totalCount` in its desired Relay shape; this plan ships the underlying field and `(table, condition)` carrier so faceted search and other per-connection derivables can read from the same place.
