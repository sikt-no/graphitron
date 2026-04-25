# `@asConnection` `totalCount` field

> **Status:** Spec

## Overview

Add `totalCount: Int` to every Connection type the rewrite synthesises for `@asConnection`, and wire a resolver that runs `count(*)` against the same table and condition the page query uses. Lazy by construction: the count fetcher is a `Supplier<Integer>` captured at page-fetch time and invoked only when the `totalCount` field is selected (graphql-java only calls the resolver in that case). Hand-written ("structural") Connection types that already declare a `totalCount` field get the same wiring; ones that don't are unaffected.

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

Synthesised Connection types carry a `totalCount: Int` field (nullable, matching the legacy default). The connection fetcher captures a `Supplier<Integer>` that runs `dsl.selectCount().from(<tableLocal>).where(<same condition>).fetchOne(0, Integer.class)` against the same `DSLContext` it used for the page query, and stashes that supplier on `ConnectionResult`. `ConnectionHelper.totalCount(env)` reads `((ConnectionResult) env.getSource()).totalCount()` and returns the integer. graphql-java only invokes the resolver when the client selects the field, so the count SQL is skipped on every query that does not ask for it; no separate selection-set inspection is needed.

Structural (hand-written) Connection types that declare a `totalCount: Int` field get the same wiring registration. Structural connections that do not declare one are unchanged: emitting a fetcher coordinate for a non-existent field is a build-time error in graphql-java, so the registrations emitter must gate the registration on the field's presence on the `ConnectionType.schemaType()`.

The behaviour is on by default; no Maven flag mirrors the legacy `totalCountFieldInConnectionsEnabled`. The rewrite has no production users yet, so making it conditional now would only add surface to retire later.

## What we're NOT doing

- **`totalCount` on nested / Split-Connection carriers.** The first cut targets root `QueryTableField` connections only, the path that goes through `buildQueryConnectionFetcher`. Split-Connection scatter (`scatterConnectionByIdx`) and any future `ChildField`-side connection wiring count rows per parent and need a different aggregation strategy (`COUNT(*) ... GROUP BY parent_key` or per-parent batched counts). Captured as a Backlog follow-up below; out of scope here.
- **Count over JOINed conditions.** `buildQueryConnectionFetcher` emits a single-table `.from(tableLocal).where(condition)` today; the count mirrors that exactly. Conditions that pull in correlated subqueries through jOOQ already work with `selectCount().from(...).where(...)` because the same `Condition` value is reused. If a future condition shape needs explicit `JOIN` clauses, both the page query and the count will need that work; out of scope here, will follow whatever the page side does.
- **Multitable / interface / union connections.** Legacy has a separate `getCodeForMultitableCountMethod` arm because each backing table needs its own count and the totals are summed. None of these field shapes are emitted by the rewrite today (`QueryTableInterfaceField` / `QueryUnionField` are stubs, roadmap §3). The count plumbing here is a single-table count; when interface/union fetchers ship they will need to extend it.
- **Caching the count across executions.** The supplier runs once per execution per selected `totalCount`; that's graphql-java's natural call cardinality. No memoisation table, no dataloader.
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

### 2. Carry a lazy count supplier on `ConnectionResult`

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/util/ConnectionResultClassGenerator.java`.

Add a `Supplier<Integer> totalCount` field, a constructor parameter on both the seven-arg constructor and the convenience `(result, page, totalCount)` constructor, and a `totalCount()` accessor that calls `totalCount.get()`. Naming the accessor `totalCount` (not `totalCountSupplier`) keeps the resolver one line, `cr.totalCount()`, and hides the supplier-vs-value detail from `ConnectionHelper`.

The supplier is required, never null. Split-Connection scatter (which today calls the convenience `(result, page)` constructor with no count to provide) gets a separate constructor overload that supplies a `() -> null` placeholder; that path is explicitly out of scope for this plan but the carrier signature has to make room for it without crashing today's emission. Concretely:

- Primary constructor: takes `Supplier<Integer> totalCount` as its last parameter.
- `(result, page)` convenience constructor (existing): delegates with `() -> null`. This keeps `SplitRowsMethodEmitter.scatterConnectionByIdx` (which constructs `ConnectionResult(sublist, page)`) compiling. Selecting `totalCount` on a Split-Connection field will return `null`; the schema field is nullable and that's the documented behaviour for the carriers we don't cover yet (see "What we're NOT doing").
- New `(result, page, totalCount)` convenience constructor: delegates with the supplier.

Document this in the class Javadoc; the existing block already calls out the `SplitConnection scatter` distinction.

### 3. Emit the count supplier in the connection fetcher

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`, in `buildQueryConnectionFetcher`.

After the existing `Result<Record> result = dsl...fetch();` block and before `return new ConnectionResult(result, page);`, emit the supplier and pass it to the new constructor:

```java
java.util.function.Supplier<Integer> totalCount = () ->
    dsl.selectCount().from(<tableLocal>).where(condition).fetchOne(0, Integer.class);
return new ConnectionResult(result, page, totalCount);
```

Both `condition` and `<tableLocal>` are already in scope (declared earlier in the method as `Condition condition = ...` via `buildConditionCall`, and `<tableLocal>` is the variable name held in `tableLocal` here). The supplier captures `dsl` (the same `DSLContext` the page query used) so the count runs on the same connection / transaction context.

The supplier is plain `java.util.function.Supplier<Integer>`, no graphitron wrapper. Memoisation: not needed; graphql-java only invokes the resolver once per execution per selected field, so the supplier is called at most once.

### 4. Generate a `totalCount` resolver in `ConnectionHelper`

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/util/ConnectionHelperClassGenerator.java`.

Add a `totalCount(env)` static method alongside `edges`, `nodes`, `pageInfo`:

```java
public static Integer totalCount(DataFetchingEnvironment env) {
    ConnectionResult cr = env.getSource();
    return cr.totalCount();
}
```

Register it on the `TypeSpec` builder. Update the class Javadoc bullet list to include `totalCount(env)`.

### 5. Wire `totalCount` from the registrations emitter

**File:** `graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/schema/FetcherRegistrationsEmitter.java`, `connectionBody`.

Append a fourth registration line, but gated on the field being present on the connection's `schemaType()`:

```java
if (connectionType.schemaType().getFieldDefinition("totalCount") != null) {
    body.add("\n.dataFetcher($T.coordinates($S, $S), $T::totalCount)",
        FIELD_COORDS, connectionTypeName, "totalCount", helper);
}
```

The synthesised path always trips the gate because Phase 1 unconditionally adds the field. The structural path trips it only when the SDL author declared `totalCount`, leaving structural connections without one untouched.

`connectionBody` currently takes only `(connectionTypeName, utilPackage)`; thread the `ConnectionType` (or just its `schemaType`) through from the caller so the gate can run. The caller already has the `ConnectionType` in hand from `schema.types()`.

### 6. Roadmap entry

**File:** `graphitron-rewrite/docs/planning/rewrite-roadmap.md`.

Add a new row to the Active table at the top (status `Spec`, linking this plan):

| `@asConnection` `totalCount` field | Spec | [plan](plan-asconnection-totalcount.md) |

Add to the Backlog (Cleanup or Priority, implementer's call) for the explicitly-deferred follow-ups:

> **`totalCount` for nested / Split-Connection carriers** **[Backlog]**: the `@asConnection` `totalCount` plan covered root `QueryTableField` connections. Split-Connection scatter and any future `ChildField` connection wiring count rows per parent and need either `COUNT(*) ... GROUP BY parent_key` aggregation or a per-parent batched count. Until shipped, selecting `totalCount` on these carriers returns `null`.

> **`totalCount`-only execution skips the page query** **[Backlog]**: when `totalCount` is the only selected connection field, the connection fetcher could skip the page query entirely. Interacts with `ConnectionResult` invariants (`trimmedResult`, cursor encoding) that today assume a page result exists; needs `result == empty` paths added throughout. Defer until a real query pattern surfaces.

### 7. Tests

Per `rewrite-design-principles.md` test tiers: no code-string assertions on generated bodies; the four tiers are unit, pipeline, compilation, execution.

**Pipeline test** (`GraphitronSchemaBuilderTest`): assert that the synthesised `ConnectionType` for an `@asConnection` carrier has a `totalCount: Int` (nullable) field on its `schemaType()`. One case is enough; the synthesis is unconditional so a single fixture covers all.

**Pipeline test for structural connections**: author one fixture with a hand-written Connection-shaped type that declares `totalCount: Int`, and one without. Both classify successfully; only the first ends up with `totalCount` wired (verifiable by inspecting the emitted `FetcherRegistrationsClass` source via the existing pipeline-test scaffolding for "what coordinates were registered", which the firstclass-connections work already exercises).

**Compilation test**: the generated `ConnectionResult`, `ConnectionHelper`, and per-fetcher classes compile. This is `GeneratedSourcesSmokeTest`'s normal job; nothing new to add beyond making sure the new constructor overload, supplier field, and `totalCount` resolver are reached by an existing fixture's emission.

**Execution test** (`graphitron-rewrite/graphitron-rewrite-test/...`): pick an existing connection fixture and add a query that selects `totalCount` alongside `edges { node { ... } }`. Assert the count matches `dsl.fetchCount(<table>)` for the unfiltered case, and equals the WHERE-filtered count for a filter-bearing case. A second query that omits `totalCount` should not log a count SQL statement (capture via `ExecuteListener` or assert against the existing query-count test harness, whichever is in place; if neither is, omit the negative assertion and rely on the resolver-not-called invariant).

**No unit test for the supplier-capture lambda.** It's emitted code, asserted via execution.

## Success criteria

### Automated

- `mvn test -pl :graphitron-rewrite` passes; includes the new `@asConnection`-synthesises-totalCount pipeline case and the structural-with / structural-without pair.
- `(cd graphitron-rewrite && mvn test -Plocal-db)` passes; includes the execution case selecting `totalCount` and the filter-bearing variant. `-Plocal-db` is required, see CLAUDE.md's fixtures-clobber note.
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
- Helper class: `ConnectionHelperClassGenerator.generate` (the `pageInfoMethod` siblings).
- Wiring registration: `FetcherRegistrationsEmitter.connectionBody`.
- Connection model: `GraphitronType.ConnectionType` (no shape change needed; just used to gate the structural-path registration on `schemaType().getFieldDefinition("totalCount")`).
- Legacy reference: `MakeConnections#L313-320` (synthesis), `FetchCountDBMethodGenerator` (count emission), `ServiceDataFetcherHelper#L102` (lazy-on-selection).
- Sibling plan: [plan-faceted-search.md](plan-faceted-search.md) lists `totalCount` in its desired Relay shape; this plan ships the underlying field so faceted search can rely on it.
