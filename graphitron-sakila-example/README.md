# graphitron-sakila-example

Two things at once: a **runnable reference application** that boots a Quarkus + JAX-RS server over a Graphitron-generated schema, and the **recommended test pattern** for pinning your own schema's behaviour against a real PostgreSQL.

If you're picking up Graphitron for a new project, this module is the answer to "what does a complete app look like" and to "how do I test my schema." Both questions resolve to directories you can copy.

## What to copy

| If you're | Copy |
|---|---|
| Standing up a Quarkus app over a Graphitron schema | [`src/main/java/.../app/`](src/main/java/no/sikt/graphitron/sakila/example/app/), [`src/main/resources/graphql/schema.graphqls`](src/main/resources/graphql/), [`src/main/resources/application.properties`](src/main/resources/application.properties) |
| Wiring a GraphiQL playground onto your `/graphql` endpoint | Nothing to copy: `graphitron-jakarta-rest` serves a self-hosted, version-pinned GraphiQL playground at `GET /graphql` (`Accept: text/html`) out of the box. To customise or bump it, see [`graphitron-jakarta-rest/tools/graphiql-build/`](../graphitron-jakarta-rest/tools/graphiql-build/) |
| Pinning your schema's behaviour against PostgreSQL | [`src/test/java/.../querydb/`](src/test/java/no/sikt/graphitron/rewrite/test/querydb/) |

The `pom.xml` shows the shape you need: Quarkus BOM, the rewrite's `graphitron-maven-plugin` plugin, jOOQ codegen, the `graphitron-jakarta-rest` runtime dependency, and Yasson as the JSON-B provider that library requires. Adjust dependencies and packages, drop in your schema, you're done.

## Runnable reference (the app)

One hand-written file covers the runtime: [`SakilaGraphitronApplication`](src/main/java/no/sikt/graphitron/sakila/example/app/SakilaGraphitronApplication.java), an implementation of the [`GraphitronApplication`](../graphitron-jakarta-rest/src/main/java/no/sikt/graphitron/jakarta/rest/GraphitronApplication.java) SPI riding the recommended owned-connection runtime path. Everything HTTP-shaped comes from the [`graphitron-jakarta-rest`](../graphitron-jakarta-rest/) dependency: the `/graphql` resource implementing the [GraphQL-over-HTTP spec](https://graphql.github.io/graphql-over-http/), engine caching, status-code semantics, the `/graphql/schema` SDL endpoint, and the GraphiQL page.

That built-in `/graphql` resource is a thin mount, so an app needing its own path (say `/graphql/{callingEnvironment}`, where routing binds the trust boundary) or an operation policy HTTP verbs cannot express (say "queries only, 400 on a mutation" outside production) injects the library's `GraphqlHttpHandler` and `GraphiqlBundle` into a resource of its own and turns the built-in route off with `GraphitronApplication.builtInEndpointEnabled()`. `GraphqlHttpHandler`'s javadoc carries the worked example. This module keeps the plain mount, which is what most apps want.

The adapter's javadoc narrates its three seams (the schema supplier, `engineBuilder()`, `newExecutionInput()`); read the linked file rather than a paraphrase here. One caveat before copying it: the example hard-codes the claims payload and `userId` in `newExecutionInput()` as placeholders. A real application passes the authenticated request's token or claims instead; see "Producing the claims payload" in [`runtime-extension-points.adoc`](../docs/architecture/reference/runtime-extension-points.adoc).

Why the stack is plain JAX-RS via `graphitron-jakarta-rest` rather than `quarkus-smallrye-graphql`: SmallRye GraphQL ships its own engine and would collide with the `Graphitron`-built `GraphQL`. A single `/graphql` resource is the minimal correct shape.

The runtime boots only the **non-federated** schema. Both `graphitron-maven-plugin` generator executions in `pom.xml` still run (`schema.graphqls` to `no.sikt.graphitron.generated`, `federated-schema.graphqls` to `no.sikt.graphitron.generated.federated`); the federated build stays a test-only artifact, exercised by [`FederationEntitiesDispatchTest`](src/test/java/no/sikt/graphitron/rewrite/test/querydb/FederationEntitiesDispatchTest.java) and [`FederationBuildSmokeTest`](src/test/java/no/sikt/graphitron/rewrite/test/querydb/FederationBuildSmokeTest.java) in-process.

### Run the app

```bash
mvn install -Plocal-db
cd graphitron-sakila-example
mvn quarkus:dev
```

`application.properties` reads `${DB_URL:jdbc:postgresql://localhost:5432/rewrite_test}` (and matching `DB_USERNAME` / `DB_PASSWORD`); the defaults match the `local-db` profile, so `mvn quarkus:dev` works out of the box if you have the example's Postgres up. The app does not own the database; bring your own (with `init.sql` from `graphitron-sakila-db` already loaded for the example's data).

### GraphiQL playground

`graphitron-jakarta-rest` serves a self-hosted [GraphiQL 5](https://github.com/graphql/graphiql/tree/main/packages/graphiql) IDE at `GET /graphql` with `Accept: text/html`, so a browser opening `http://localhost:8080/graphql` lands on the playground while curl/POST traffic routes to the engine. The assets are a **version-pinned bundle committed inside the library** and streamed from its `assets/{name}` endpoint: no runtime CDN, so the playground works offline, behind a strict CSP, and on air-gapped networks. Nothing GraphiQL-related lives in this example module anymore; you get the playground for free by depending on `graphitron-jakarta-rest`.

When the GraphiQL or React versions need bumping, [`graphitron-jakarta-rest/tools/graphiql-build/`](../graphitron-jakarta-rest/tools/graphiql-build/) holds the small Vite recipe (`package.json`, `vite.config.js`, `src/main.jsx`); a one-shot `npm install && npm run build` rewrites the committed bundle in place. That recipe is the only place node lives in the repo, and the Maven build never invokes it.

## Recommended test pattern (the tests)

Tests live under [`src/test/java/no/sikt/graphitron/rewrite/test/querydb/`](src/test/java/no/sikt/graphitron/rewrite/test/querydb/) and stay **in-process**: each builds the engine via `Graphitron.newGraphQL().build()` (or `Graphitron.buildSchema(...)` directly when extra wiring is needed), executes via graphql-java, and asserts against a live Postgres `DSLContext`. Where the app above runs the owned-connection runtime path, these tests run the escape hatch, `Graphitron.newGraphQL().build()` over a test-owned `DSLContext`; both paths are described in [`runtime-extension-points.adoc`](../docs/architecture/reference/runtime-extension-points.adoc). They do not go through the Quarkus HTTP stack. Keeping the pattern in-process means you can copy it without bringing Quarkus into your test classpath.

Two shapes are worked out for you:

- **Match** ([`MatchQueryExampleTest`](src/test/java/no/sikt/graphitron/rewrite/test/querydb/MatchQueryExampleTest.java) + [`src/test/resources/match/queries/`](src/test/resources/match/queries/)): load a `.graphql` file, execute, assert specific paths on the response. Use this when "this query returns rows whose `firstName` is non-null" is the contract you care about.
- **Approval** ([`ApprovalQueryExampleTest`](src/test/java/no/sikt/graphitron/rewrite/test/querydb/ApprovalQueryExampleTest.java) + [`src/test/resources/approval/queries/`](src/test/resources/approval/queries/) + [`src/test/resources/approval/approvals/`](src/test/resources/approval/approvals/)): execute a `.graphql` file, serialise the response as canonical JSON, compare to a checked-in `.approved.json`. Use this when the entire response shape is the contract; when it diverges, the test writes a sibling `.actual.json` next to the approved file so the next iteration is "diff the two; mv onto approved if intentional."

Both worked examples are self-contained: each test class carries its own `@BeforeAll` Postgres setup (Testcontainers by default, or pre-existing local Postgres when surefire passes `-Dtest.db.url=...` under the `-Plocal-db` profile). Copy one file, adapt the package, drop in your queries.

For richer assertion shapes, [`GraphQLQueryTest`](src/test/java/no/sikt/graphitron/rewrite/test/querydb/GraphQLQueryTest.java) is the canonical "test your schema" reference: 180+ tests covering selection-set scoping, DataLoader batching counts, pagination, filters, federation entity dispatch, error channels, and so on. Same shape (in-process, real Postgres, AssertJ on the response map), wider surface.

HTTP-tier coverage lives under [`src/test/java/.../app/`](src/test/java/no/sikt/graphitron/sakila/example/app/): mostly `@QuarkusTest` classes, plus `OperationGuardTest`, which covers the operation-policy decision without booting anything; everything under [`querydb/`](src/test/java/no/sikt/graphitron/rewrite/test/querydb/) stays in-process and Quarkus-free. The canonical exemplar is [`GraphQLOverHttpConformanceTest`](src/test/java/no/sikt/graphitron/sakila/example/app/GraphQLOverHttpConformanceTest.java), which boots the Quarkus shell and pins the GraphQL-over-HTTP spec's normative requirements through the real JAX-RS stack.

## What `internal/` is for

[`src/test/java/no/sikt/graphitron/rewrite/test/internal/`](src/test/java/no/sikt/graphitron/rewrite/test/internal/) holds **generator-internal coverage**: writer mechanics (`IdempotentWriterTest`), three-clause determinism (`GeneratorDeterminismTest`), generated-source hygiene (`GeneratedSourcesSmokeTest`, `GeneratedSourcesLintTest`), classification path pins (`MutationPayloadLifterTest`, `AccessorDerivedSourceTest`), the test-tier vocabulary's own meta-test (`TierAnnotationEnforcementTest`), and a handful of others. These tests live here for module-dependency reasons (they need the generated sources or the live catalog to assert against), not because they're part of the consumer pattern.

**You do not need to copy anything from `internal/`.** It exists to ratchet the rewrite's invariants. Consumer test files live under [`querydb/`](src/test/java/no/sikt/graphitron/rewrite/test/querydb/).
