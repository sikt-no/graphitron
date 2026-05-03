# graphitron-sakila-example

Two things at once: a **runnable reference application** that boots a Quarkus + JAX-RS server over a Graphitron-generated schema, and the **recommended test pattern** for pinning your own schema's behaviour against a real PostgreSQL.

If you're picking up Graphitron for a new project, this module is the answer to "what does a complete app look like" and to "how do I test my schema." Both questions resolve to directories you can copy.

## What to copy

| If you're | Copy |
|---|---|
| Standing up a Quarkus app over a Graphitron schema | [`src/main/java/.../app/`](src/main/java/no/sikt/graphitron/sakila/example/app/), [`src/main/resources/graphql/schema.graphqls`](src/main/resources/graphql/), [`src/main/resources/application.properties`](src/main/resources/application.properties) |
| Wiring a GraphiQL playground onto your `/graphql` endpoint | [`src/main/resources/META-INF/resources/graphiql/`](src/main/resources/META-INF/resources/graphiql/) (committed pre-built assets), [`tools/graphiql-build/`](tools/graphiql-build/) (the Vite recipe to refresh them), the redirect branch in [`GraphqlResource`](src/main/java/no/sikt/graphitron/sakila/example/app/GraphqlResource.java) |
| Pinning your schema's behaviour against PostgreSQL | [`src/test/java/.../querydb/`](src/test/java/no/sikt/graphitron/rewrite/test/querydb/) |

The `pom.xml` shows the shape you need (Quarkus BOM, the rewrite's `graphitron-maven` plugin, jOOQ codegen). Adjust dependencies and packages, drop in your schema, you're done.

## Runnable reference (the app)

Three files cover the runtime under [`src/main/java/no/sikt/graphitron/sakila/example/app/`](src/main/java/no/sikt/graphitron/sakila/example/app/):

- [`GraphqlEngine`](src/main/java/no/sikt/graphitron/sakila/example/app/GraphqlEngine.java) (`@ApplicationScoped`): builds the `GraphQLSchema` once at startup via `Graphitron.buildSchema(b -> {})` and exposes a single `GraphQL` engine. graphql-java engines are immutable and thread-safe; one instance handles every request.
- [`GraphqlResource`](src/main/java/no/sikt/graphitron/sakila/example/app/GraphqlResource.java) (`@Path("/graphql")`): a JAX-RS resource implementing the [GraphQL-over-HTTP spec](https://graphql.github.io/graphql-over-http/). POST accepts `application/json`, GET takes `?query=&operationName=`, both negotiate to `application/graphql-response+json`. A third method, `@GET @Produces(text/html)`, returns a 303 redirect to `/graphiql/`, so a browser visiting `http://localhost:8080/graphql` lands on the playground while curl/POST traffic routes to the engine. Each request gets a fresh `DataLoaderRegistry` (graphql-java requires one even when no DataLoader is used) and a per-request `AppContext` stashed on the `ExecutionInput`.
- [`AppContext`](src/main/java/no/sikt/graphitron/sakila/example/app/AppContext.java) (`implements GraphitronContext`): per-request `DSLContext` derived from the Quarkus-managed `AgroalDataSource`, plus a context-values map fed into `getContextArgument` lookups. Wire JWT-claim extraction or any other request-scoped state through that map.

Why plain JAX-RS rather than `quarkus-smallrye-graphql`: SmallRye GraphQL ships its own engine and would collide with the `Graphitron`-built `GraphQL`. A single `/graphql` resource is the minimal correct shape.

The runtime boots only the **non-federated** schema. Both `graphitron-maven` generator executions in `pom.xml` still run (`schema.graphqls` to `no.sikt.graphitron.generated`, `federated-schema.graphqls` to `no.sikt.graphitron.generated.federated`); the federated build stays a test-only artifact, exercised by [`FederationEntitiesDispatchTest`](src/test/java/no/sikt/graphitron/rewrite/test/querydb/FederationEntitiesDispatchTest.java) and [`FederationBuildSmokeTest`](src/test/java/no/sikt/graphitron/rewrite/test/querydb/FederationBuildSmokeTest.java) in-process.

### Run the app

```bash
mvn -f graphitron-rewrite/pom.xml install -Plocal-db
cd graphitron-rewrite/graphitron-sakila-example
mvn quarkus:dev
```

`application.properties` reads `${DB_URL:jdbc:postgresql://localhost:5432/rewrite_test}` (and matching `DB_USERNAME` / `DB_PASSWORD`); the defaults match the `local-db` profile, so `mvn quarkus:dev` works out of the box if you have the example's Postgres up. The app does not own the database; bring your own (with `init.sql` from `graphitron-sakila-db` already loaded for the example's data).

### GraphiQL playground

A browser opening `http://localhost:8080/graphql` redirects to a self-hosted [GraphiQL 5](https://github.com/graphql/graphiql/tree/main/packages/graphiql) IDE at `/graphiql/`. The bundle is **pre-built and committed** under [`src/main/resources/META-INF/resources/graphiql/`](src/main/resources/META-INF/resources/graphiql/); Quarkus serves anything under `META-INF/resources/` as a static asset, so no extension, no Node tooling, and no npm download happens during `mvn install` or in CI.

When the GraphiQL or React versions need bumping, [`tools/graphiql-build/`](tools/graphiql-build/) holds the small Vite recipe (`package.json`, `vite.config.js`, `index.html`, `src/main.jsx`); a one-shot `npm install && npm run build` rewrites the committed assets in place. The recipe is the only place node lives in this repo; CI never touches it.

## Recommended test pattern (the tests)

Tests live under [`src/test/java/no/sikt/graphitron/rewrite/test/querydb/`](src/test/java/no/sikt/graphitron/rewrite/test/querydb/) and stay **in-process**: each builds the schema via `Graphitron.buildSchema(...)`, instantiates a `GraphQL` engine, executes via graphql-java, and asserts against a live Postgres `DSLContext`. They do not go through the Quarkus HTTP stack. Keeping the pattern in-process means you can copy it without bringing Quarkus into your test classpath.

Two shapes are worked out for you:

- **Match** ([`MatchQueryExampleTest`](src/test/java/no/sikt/graphitron/rewrite/test/querydb/MatchQueryExampleTest.java) + [`src/test/resources/match/queries/`](src/test/resources/match/queries/)): load a `.graphql` file, execute, assert specific paths on the response. Use this when "this query returns rows whose `firstName` is non-null" is the contract you care about.
- **Approval** ([`ApprovalQueryExampleTest`](src/test/java/no/sikt/graphitron/rewrite/test/querydb/ApprovalQueryExampleTest.java) + [`src/test/resources/approval/queries/`](src/test/resources/approval/queries/) + [`src/test/resources/approval/approvals/`](src/test/resources/approval/approvals/)): execute a `.graphql` file, serialise the response as canonical JSON, compare to a checked-in `.approved.json`. Use this when the entire response shape is the contract; when it diverges, the test writes a sibling `.actual.json` next to the approved file so the next iteration is "diff the two; mv onto approved if intentional."

Both worked examples are self-contained: each test class carries its own `@BeforeAll` Postgres setup (Testcontainers by default, or pre-existing local Postgres when surefire passes `-Dtest.db.url=...` under the `-Plocal-db` profile). Copy one file, adapt the package, drop in your queries.

For richer assertion shapes, [`GraphQLQueryTest`](src/test/java/no/sikt/graphitron/rewrite/test/querydb/GraphQLQueryTest.java) is the canonical "test your schema" reference: 180+ tests covering selection-set scoping, DataLoader batching counts, pagination, filters, federation entity dispatch, error channels, and so on. Same shape (in-process, real Postgres, AssertJ on the response map), wider surface.

There's also one HTTP-shaped test: [`GraphqlResourceSmokeTest`](src/test/java/no/sikt/graphitron/sakila/example/app/GraphqlResourceSmokeTest.java) (`@QuarkusTest`) boots the Quarkus shell and POSTs a query through the JAX-RS resource. It's the single check that the runtime layer wires up correctly; everything else stays at the schema/engine level so the pattern stays Quarkus-free.

## What `internal/` is for

[`src/test/java/no/sikt/graphitron/rewrite/test/internal/`](src/test/java/no/sikt/graphitron/rewrite/test/internal/) holds **generator-internal coverage**: writer mechanics (`IdempotentWriterTest`), three-clause determinism (`GeneratorDeterminismTest`), generated-source hygiene (`GeneratedSourcesSmokeTest`, `GeneratedSourcesLintTest`), classification path pins (`MutationPayloadLifterTest`, `AccessorDerivedBatchKeyTest`), the test-tier vocabulary's own meta-test (`TierAnnotationEnforcementTest`), and a handful of others. These tests live here for module-dependency reasons (they need the generated sources or the live catalog to assert against), not because they're part of the consumer pattern.

**You do not need to copy anything from `internal/`.** It exists to ratchet the rewrite's invariants. Consumer test files live under [`querydb/`](src/test/java/no/sikt/graphitron/rewrite/test/querydb/).
