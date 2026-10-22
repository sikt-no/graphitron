---
id: R1090
title: "A connection has no maximum page size, so first: 100000 reads 100000 rows"
status: In Progress
bucket: enhancement
priority: 3
theme: pagination
depends-on: []
created: 2026-10-22
last-updated: 2026-10-22
---

# A connection has no maximum page size, so first: 100000 reads 100000 rows

## Goal

A consumer can set a maximum page size for every `@asConnection` field (a field Graphitron serves as a Relay cursor-paginated connection) with one Maven plugin parameter, `<maxAllowedPageSize>`, the name Graphitron 9 used. Today a client that sends `first: 100000` (or `last:`) gets that many rows in one query, and the manual tells consumers to enforce a cap themselves with a graphql-java instrumentation. After this item a request for more than the maximum gets the maximum, with `hasNextPage: true` (`hasPreviousPage: true` for `last:`), so the client pages on as it would for any other page. That is what Graphitron 9 did, and what sis's clients already rely on. Without the parameter, behaviour and generated code are unchanged.

With `<maxAllowedPageSize>100</maxAllowedPageSize>`, against the sakila example's `filmsConnection` (1000 films, ordered by primary key):

```graphql
type Query {
    filmsConnection(
        first: Int, last: Int, after: String, before: String
    ): FilmsConnection! @defaultOrder(primaryKey: true)
}
```

```graphql
{ filmsConnection(first: 500) { nodes { title } pageInfo { hasNextPage endCursor } } }
```

returns 100 films and `hasNextPage: true`; today it returns 500. `first: 40` still returns 40, and following `endCursor` walks the whole table 100 rows at a time.

## Why in Graphitron rather than in each consumer

Reported by sis, whose Graphitron 9 build set `<maxAllowedPageSize>1000</maxAllowedPageSize>` (`sis-graphql-spec/pom.xml`). Under 9 a larger `first:` was silently clamped to 1000 with `hasNextPage: true`, and sis's consumers page on that basis. A consumer can rebuild this with an instrumentation that rewrites `first`/`last` before the fetcher reads them, but:

- **The instrumentation has to work out which fields are connections**, from argument names and return-type naming. The generator already knows, and every connection path goes through one emitted method, `ConnectionHelper.pageRequest` (below).
- **Every Graphitron 9 consumer has the same need.** In fs-plattform that is sis now, and utdanningsregisteret and opptak when they move to Graphitron 10. Each would write and test the same instrumentation.
- **A cap protects the database**, which is the generator's concern as much as the API layer's. `defaultFirstValue:` already shows the generator owning page-size policy for the omitted-`first:` case.

The manual records that the setting was "deliberately excised"; the changelog gives no reason beyond the Maven plugin's parameter clean-up. This item brings the capability back as opt-in, under the old name.

## Decisions

- **A build-time Maven parameter, not a runtime setting.** `pageRequest` is a static method that takes no `DataFetchingEnvironment`, and `GraphitronRuntime` (the emitted object that owns the consumer's `DataSource`) exists only on the owned-connection path; a consumer wiring its own connections never builds one. A plugin parameter reaches every consumer, and it is where 9 put it. A deployment that wants a different cap regenerates, which is what sis did under 9.
- **The old name, `<maxAllowedPageSize>`.** Maven does not fail on an unknown plugin parameter (3.9 logs a warning), so a pom migrated from 9 that kept the line builds today with no cap at all. Reusing the name makes that leftover line start working rather than leaving a second name to discover.
- **Clamp, never reject.** A `first:` or `last:` above the maximum is served at the maximum. This is 9's behaviour and what sis's clients depend on, and it is correct under the Relay connection spec: the query fetches `pageSize + 1` rows, so `hasNextPage` (or `hasPreviousPage` for `last:`) reports the remainder truthfully. A reject mode is not offered; see Other solutions.
- **The runtime clamp covers every connection**, `@asConnection` carriers and hand-declared connection types alike, and every value that reaches `pageRequest`: `first:`, `last:`, and the default when the client sends neither. It runs before the existing overflow guard, so with a maximum configured `first: 2147483647` returns a page instead of the "page size must be less than 2147483647" error.
- **The default page size is decided once, at build time, and never exceeds the maximum.** graphql-java fills an omitted `first:` from the argument's published SDL default, so the default a client actually gets is the one the schema advertises. Graphitron's own default (100, where the author wrote none) becomes the smaller of 100 and the maximum, in the minted `first: Int = …` argument and in the Java fallback alike. The runtime clamp then only ever fires on values a client sent.
- **An authored default above the maximum fails the build.** `@asConnection(defaultFirstValue: 5000)`, or an authored `first: Int = 5000` on an `@asConnection` carrier, under a maximum of 1000 advertises a default no request can receive, so the build reports it at the field. The check is a store detection over the resolved `first` default (see Implementation), so the build log, `graphitron:dev`'s editor diagnostics and the MCP `diagnostics` tool report it alike.
- **One maximum per build, no per-field override.** Nobody has asked for one; it can be added later as an `@asConnection` argument without changing this item's surface.

## User documentation (first-client check)

New row in the shared parameter table of `docs/manual/reference/mojo-configuration.adoc`:

> `maxAllowedPageSize` | `Integer` | (none) | Largest page any connection field returns. A request whose `first:` or `last:` exceeds it gets this many rows, with `hasNextPage` (or `hasPreviousPage`) `true` so the client can keep paging; nothing is rejected. Applies to every connection field, `@asConnection` or a connection type you declared yourself. Graphitron's built-in default page size of 100 is lowered to it when it is smaller; a `defaultFirstValue:` you wrote above it fails the build. Must be at least 1. Omit for no maximum.

`docs/manual/how-to/connections.adoc`, the section now titled "Page size: `defaultFirstValue` and the missing cap", becomes "Page size: the default and the maximum":

> Page size resolves at request time:
>
> * *Client supplies `first: N` or `last: N`.* Use `N`.
> * *Client supplies neither.* Use the directive's `defaultFirstValue:`, or 100 when `defaultFirstValue:` is also omitted. The default appears in your published schema as the `first` argument's default value.
>
> Then, if the build sets `<maxAllowedPageSize>`, a page size above it is lowered to it. The client is not told with an error; it sees a full page and `hasNextPage: true` (or `hasPreviousPage: true` for `last:`), and follows the cursor as usual. With `<maxAllowedPageSize>1000</maxAllowedPageSize>`, `first: 5000` returns 1000 rows. Without the parameter there is no maximum, and a client passing `first: 100000` gets that many rows.
>
> `defaultFirstValue:` is a default, not a maximum. With `<maxAllowedPageSize>` set, the built-in default of 100 becomes the maximum when the maximum is smaller (`<maxAllowedPageSize>50</maxAllowedPageSize>` publishes `first: Int = 50`), and a `defaultFirstValue:` you wrote that is larger than the maximum fails the build, since no request could receive it.
>
> The maximum covers connections only. A plain list field with a `first:` argument is not a connection and is not limited by it.

The "Constraints and pitfalls" bullet "No server-side page-size cap" is replaced with one pointing at the parameter. `asConnection.adoc` keeps "not a cap" in the `defaultFirstValue` row and its Constraints bullet, and names `<maxAllowedPageSize>` as the cap. In `migrating-from-legacy.adoc` the "Plugin configuration migrates separately" bullet says `<maxAllowedPageSize>` carries over unchanged and now also caps `last:`.

## Implementation

The setting enters as configuration, is transcribed into the fact store (the SQL database Graphitron fills from the schema, the jOOQ catalog and the build configuration, and derives its decisions from) like every other build parameter, and is read from there by the minted default and the build rejection; the generator reads it off `RunContext` for its Java default fallback and to emit the runtime clamp.

**Configuration**

- **`AbstractRewriteMojo`**: `@Parameter Integer maxAllowedPageSize`, shared by every goal like `<tenantColumn>`, so `validate` and `dev` report the default conflict as `generate` does. A value below 1 fails the mojo with a `MojoExecutionException` naming the parameter: a maximum of 0 serves empty pages with `hasNextPage: true` forever, and a negative one reaches `LIMIT` negative after the existing guards have run.
- **`RunContext`**: an `OptionalInt maxAllowedPageSize` component, empty meaning no maximum, defaulted by every convenience overload (a `with…` copy method, as `withTenantColumn` has, if that is cleaner than widening the overloads). The compact constructor rejects a present value below 1, so the invariant holds for non-Maven callers too.
- **`SubjectConfig`** gains the component in `SubjectConfig.of(ctx)` (not at a caller; its javadoc says why), and **`StoreEntries`** writes it to a new **`store_graph_max_allowed_page_size`** relation in `graphitron-model.sql`, shaped like `store_graph_tenant_column`: `graph_name` primary key with a foreign key into `store_graph`, a `max_page_size INT NOT NULL CHECK (max_page_size > 0)` column, `touched_at` for the sweep; row presence is the fact and an uncapped build has no row. Added to `TABLES_TO_SWEEP`, to the `meta_relation` registration, and to `RelationRegistrationGateTest` and `StoreEntriesTest`. The `graphitron` gatherer already declares `store` as an upstream dependency, so its views may read the relation.

**The default page size, decided once**

- **`graphitron_argument_minted_candidate`**: the pagination arm's `COALESCE(default_first_value, 100)` becomes `COALESCE(default_first_value, LEAST(100, cap))` over a left join to `store_graph_max_allowed_page_size`, so the minted `first: Int = …` a client sees never exceeds the maximum. The authored `defaultFirstValue:` passes through untouched; the detection below judges it.
- **`PaginationResolver.defaultPageSize`** (and the hand-declared-connection fallback in `BuildContext`'s connection-wrapper resolution, which uses `ConnectionDefaults.DEFAULT_PAGE_SIZE` directly) applies the same `min(100, maximum)` to the unauthored fallback, reading the maximum off `RunContext`. This is the value passed to `pageRequest` as `defaultPageSize` when graphql-java supplies no `first`. `ConnectionDefaults.DEFAULT_PAGE_SIZE` stays the one Java spelling of 100; the SQL literal is its existing twin.

**The build rejection, as a store detection**

- A new entry-defect code, `DEFAULT_PAGE_SIZE_ABOVE_MAXIMUM` (severity `error`), minted into `graphitron_entry_defect` by a view joining each `@asConnection` carrier's resolved `first` argument (`graphitron_connection_carrier` to `graphitron_argument`, whose `default_value_sdl` holds the authored `defaultFirstValue:`, an authored `first: Int = N`, or the minted default) to `store_graph_max_allowed_page_size`, where the default exceeds the maximum. The row sits at the written position the author edits: the `defaultFirstValue:` argument, or the authored `first` argument's default. The minted default cannot fire it, by the `LEAST` above.
- A `StoreDetections` family class reading that code, shaped like `UnlowerableOrderings` (its `READS` set, a typed row, a composed message such as `Query.films: @asConnection(defaultFirstValue: 5000) exceeds <maxAllowedPageSize> 1000; no request can receive a page that large`), added to the `StoreDetections` record and to `violations()`, and to `DetectionReadReachGateTest`. The defect catalog row beside `ORDERING_ACROSS_PARTICIPANTS`'s gives the editor its code and category.
- Hand-declared connection types (a field returning a `FooConnection` the author wrote, with no `@asConnection`) are outside the detection: the store has no relation that recognises them as connections yet. Their authored `first` default above the maximum is clamped at runtime like any client value.

**The runtime clamp**

- **`ConnectionHelperClassGenerator.generate`** takes the maximum (`OptionalInt`, beside the existing `multiTenant` flag; `GraphQLRewriteGenerator`'s `CONNECTION_HELPER` case passes `ctx.maxAllowedPageSize()`). When present it emits a `private static final int MAX_PAGE_SIZE` constant and, in `pageRequest`, lowers `pageSize` to it after the negative-argument guards and before the `Integer.MAX_VALUE` guard. When absent it emits today's class. Nothing downstream changes: `PageRequest.limit()` is `pageSize + 1`, and every caller (`RootLauncherRenderer`, `BatchedRowsFragments`, `MultiTablePolymorphicEmitter`) and `ConnectionResult`'s page flags already read `limit()` and `pageSize()` off the returned `PageRequest`. No connection bypasses `pageRequest`: `FieldBuilder` rejects `@asConnection` on a non-`@splitQuery` table field, the one shape that would.

**Docs**: the four pages under User documentation. `MojoDocCoverageTest` fails the build until the `mojo-configuration.adoc` row exists in the shared table.

## Tests

- **Unit, `graphitron`**: `ConnectionHelperClassGenerator.generate` with a maximum yields a `ConnectionHelper` carrying a `MAX_PAGE_SIZE` field of that value, and without one yields no such field (structural assertions on the `TypeSpec`, not method-body strings). `RunContext` rejects `0` and a negative value and accepts empty.
- **Store, `graphitron-model`**: `StoreEntriesTest` round-trips the relation and sweeps it when a later run drops the parameter. A minted-argument case shows a bare `@asConnection` under a maximum of 50 minting `first` with default `50`, under 500 minting `100`, and with no maximum minting `100`.
- **Detection, `graphitron`**: beside `UnlowerableOrderingsTest` and `UnlowerableOrderingRejectionPipelineTest`, a family test and a pipeline test: under a maximum of 10, `@asConnection(defaultFirstValue: 50)` and an `@asConnection` carrier with an authored `first: Int = 50` each report one error at the authored position; `defaultFirstValue: 10` and a bare `@asConnection` report none; `defaultFirstValue: 50` with no maximum reports none.
- **Execution, `graphitron-sakila-example`**, the acceptance evidence: a new generate execution (`rewrite-generate-page-cap`, its own output package, as `rewrite-generate-multischema` has) over a small schema file holding a root `@asConnection` over `film` with no `defaultFirstValue:`, a hand-declared root connection over `film` whose `first` has no default, one `@splitQuery` child connection (`Film.actorsConnection` or equivalent), and one multi-table polymorphic connection, with `<maxAllowedPageSize>5</maxAllowedPageSize>`. One test class in the shape of `MultiSchemaQueryTest` asserts:
  - `first: 6` returns 5 nodes and `hasNextPage: true`; `first: 3` returns 3;
  - `last: 6` returns the final 5 rows and `hasPreviousPage: true`;
  - with neither argument, both root connections return 5, and the `@asConnection` field's introspected `first` default is `5`;
  - `first: 2147483647` returns 5 nodes and no error;
  - following `endCursor` with `first: 100` visits every row exactly once;
  - the `@splitQuery` child connection under several films returns at most 5 actors per film, with `hasNextPage` true exactly for the films that have more;
  - the polymorphic connection returns 5 with `hasNextPage: true`.

  The maximum sits on its own execution because the main schema's tests read pages up to `first: 100` and pin the uncapped overflow error (`GraphQLQueryTest.filmsConnection_firstMaxInt_surfacesClientError`), and `film` holds exactly 1000 rows, so a cap above every existing request could not show a clamp on it.

## Out of scope

- A page-size limit on plain list fields that take a `first:` argument (`ProjectionUnitRenderer`'s `limitByFirstArgument` arm). They are not connections, 9's setting did not cover them, and a silent clamp there would have no `hasNextPage` to tell the client.
- Advertising the maximum itself in the schema, for example in the minted `first` argument's description. The view that mints the argument could read it once this item lands; nobody has asked for it.

## Other solutions we've considered

- **A `GraphitronRuntime` setting**, changeable per deployment without regenerating. It does not reach consumers on the escape-hatch path, who build no runtime, and threading it into `pageRequest` means passing the environment or a runtime handle through every connection fetcher. The cap is schema policy, which the build owns.
- **Rejecting an oversized request with a `GraphitronClientException`.** Explicit to the client, and what some public APIs do, but it breaks sis's clients, which page on 9's clamp, and a consumer who wants rejection can still write the instrumentation the manual describes, now as a stricter layer over a generator-enforced bound. Offering both behaviours behind a second parameter doubles the surface for a need nobody has stated.
- **A new parameter name** such as `<maxPageSize>`. Reads slightly better but leaves every migrated 9 pom silently uncapped until someone notices the warning.
