---
id: R67
title: "Rewrite-flavoured graphitron-example: Quarkus + JAX-RS"
status: Spec
bucket: architecture
theme: legacy-migration
depends-on: []
---

# Rewrite-flavoured graphitron-example: Quarkus + JAX-RS

The public docs (`docs/quick-start.adoc:21,64`) point new users at `graphitron-example/` at the repo root, but that project wires the *legacy* `graphitron-maven-plugin` and the legacy `graphitron-servlet` runtime. Anyone following those links gets an example that targets the stack R26 retires. We need a sibling example under `graphitron-rewrite/` that wires `graphitron-maven` (the rewrite plugin) and the runtime contract documented in `graphitron-rewrite/docs/getting-started.adoc`, so the docs have something honest to point at and so consumers migrating off the legacy stack have a worked-out reference for the new shape.

The example also unblocks the closing step in [`retire-maven-plugin.md`](retire-maven-plugin.md) (R26), which lists `graphitron-example` among the directories deleted at legacy retirement: once this rewrite-side example exists and the docs point at it, deleting the legacy one stops being a docs-breaking change.

## Goal

Ship a complete, runnable Quarkus app under `graphitron-rewrite/graphitron-example/` that:

- Generates resolvers via `graphitron-maven:generate` against a Sakila-backed jOOQ catalog.
- Serves GraphQL over HTTP via JAX-RS (`quarkus-rest`) per the [GraphQL-over-HTTP spec](https://graphql.github.io/graphql-over-http/) (POST + GET, content-negotiated `application/json` and `application/graphql-response+json`).
- Demonstrates the runtime extension points documented in `getting-started.adoc`: `GraphitronContext`, the customizer surface on `Graphitron.buildSchema(...)`, `@service` / `@condition` wiring, custom scalars, federation, and per-request `DSLContext`.
- Carries an approval-style integration test suite that boots the Quarkus server and exercises the resolvers against a real Postgres.

End state: `docs/quick-start.adoc` "Working example" link points at `graphitron-rewrite/graphitron-example/`, and the legacy `graphitron-example/` is the only remaining root-level reason `R26`'s "delete legacy + unnest" step has not closed.

## Module shape

Three Maven modules under `graphitron-rewrite/graphitron-example/`. The split is forced by the rewrite plugin's `ServiceCatalog` reflecting `Class.forName(...)` on `@service(class:)` targets at `generate-sources` time: service classes have to be compiled before the plugin runs, which a same-module `src/main/java` cannot satisfy without an awkward early-`compile` phase binding. jOOQ codegen has the same constraint for the catalog package.

```
graphitron-rewrite/graphitron-example/
├── pom.xml                       # aggregator
├── graphitron-example-db/        # jOOQ codegen against Sakila
├── graphitron-example-services/  # @service / @condition / custom-scalar classes
└── graphitron-example/           # Quarkus app: schema + plugin + JAX-RS resource
```

`graphitron-example-db` and `graphitron-example-services` stand alone (DB module is a static jOOQ catalog jar; services module is plain Java classes referenceable by FQN), and the app module depends on both.

The application module inlines the schema and plugin invocation rather than carrying a separate `graphitron-example-spec` module. The schema isn't reused outside the app, and Quarkus's source watcher picks up regenerated sources under the app's own `target/generated-sources/graphitron` automatically — splitting the schema into a sibling module would block that dev-loop with a cross-module rebuild.

### `graphitron-example-db`

- `docker-compose.yml` for the Sakila Postgres image (`sakiladb/postgres:15`).
- `init/init-script.sql` (Sakila schema bootstrap; same source as the legacy module).
- `jooq-configuration.xml` driving the `org.jooq:jooq-codegen-maven` plugin.
- A `generate-jooq` profile so the codegen only runs when the consumer asks for it; baseline build consumes the checked-in generated sources under `src/main/java/no/sikt/graphitron/example/generated/jooq/`.
- Any custom jOOQ converters the example needs.

### `graphitron-example-services`

- `@service` Java classes (e.g., `HelloWorldService`, `CustomerService` with the JWT-claim demo from `getting-started.adoc`).
- `@condition` classes (e.g., `StaffConditions`, `CustomerConditions`).
- Custom-scalar provider class for the customizer demo.
- Java records for `@record`-typed payloads.
- Compiled with `-parameters` so the rewrite plugin's `ServiceCatalog` can read parameter names without warnings.

### `graphitron-example` (the application module)

- `src/main/resources/graphql/*.graphqls` — the schema. One `schema.graphqls` plus one `.graphql` per feature demoed (mutations, services, conditions, federation), so a reader can scan the schema slice that belongs to each feature without a 1000-line file.
- `src/main/resources/application.yaml` — Quarkus config (HTTP port, datasource via dev services, log levels).
- `pom.xml` carries the `graphitron-maven:generate` execution and the `quarkus-maven-plugin`. The plugin block adds `graphitron-example-db` and `graphitron-example-services` as `<dependencies>` of the plugin execution so they're on the codegen classpath.
- `GraphqlResource` — JAX-RS `@Path("/graphql")` resource implementing the GraphQL-over-HTTP transport.
- `AppContext implements GraphitronContext` — the per-request context binding `DSLContext` and JWT-claim arguments per `getting-started.adoc`'s "Hello world" / "Context arguments from a JWT claim" sections.
- `GraphqlEngine` — `@ApplicationScoped` bean that builds the `GraphQLSchema` once via `Graphitron.buildSchema(b -> ...)` and exposes a per-request `GraphQL` engine.
- An integration test suite (`@QuarkusTest`) running approval-style and match-style tests against the live server, mirroring the legacy example's `match/` and `approval/` directories.

## Phases

Single plan, multi-phase, multi-commit. **Review after all phases land**: status stays `In Progress` through phases A-D and flips to `In Review` only when the suite is complete.

### Phase A: minimal end-to-end

The slice that lets us repoint the docs.

- Three-module skeleton wired up; aggregator pom; build green.
- Sakila checked-in jOOQ output under `graphitron-example-db` (one-shot regeneration off a running Sakila container; thereafter the generated sources are committed source artefacts so the example builds without Docker).
- `schema.graphqls` covering the Customer / Address / Store / Film core: simple object types, two queries (single-fetch + connection), one mutation (create or update a Customer).
- Plugin execution generates resolvers under `target/generated-sources/graphitron`.
- `GraphqlResource` POST `/graphql` returning `application/graphql-response+json` per the GraphQL-over-HTTP spec; GET `/graphql` for query-only requests; per-request `AppContext` carrying a `DSLContext` derived from the Quarkus `AgroalDataSource`.
- Quarkus dev-services starts a Sakila Postgres container on `mvn quarkus:dev`.
- One smoke test (`@QuarkusTest`) hits the endpoint with a basic query and asserts a non-empty response.

Phase A exit: `mvn install` from `graphitron-rewrite/graphitron-example/` builds clean, `mvn quarkus:dev` serves a working `/graphql`, and `docs/quick-start.adoc:21,64` can be repointed at the new module without lying.

### Phase B: services, conditions, custom scalars

Layer on the runtime extension points the `getting-started.adoc` "safe surface" section describes.

- `@service` walkthrough: a `HelloWorldService` for the simplest case, a `CustomerService` reading a JWT claim from `GraphitronContext.getContextArgument`.
- `@condition` walkthrough: `StaffConditions` and `CustomerConditions`, with a query argument that drives the predicate.
- Custom-scalar walkthrough: a `Date` scalar registered via the `Graphitron.buildSchema(b -> b.additionalType(...))` customizer, used on a Sakila timestamp column.
- One approval-style test per walkthrough.

### Phase C: Apollo Federation

The rewrite ships entity dispatch (R31, federation-via-federation-jvm). Demonstrate it.

- A second `.graphqls` file declaring the `@link(url: "...federation/v2.10")` opt-in and `@key` on Customer.
- One federation test exercising `_entities` end-to-end against the running server, modelled on `FederationEntitiesDispatchTest` from `graphitron-test`.

### Phase D: error handling

Gated on R12 (`error-handling-parity`). Lift the legacy example's error-handling demos onto the rewrite's per-fetcher error-channel emission once R12 lands. Until then this phase stays a stub in the plan; if R12 ships before Phase A-C, this phase joins Phase B's slot. If R12 doesn't ship before this plan otherwise completes, Phase D defers and this plan closes without it (a follow-up roadmap item picks it up; the example ships error-handling-free in the meantime).

## Tests

Every phase that adds a feature also adds the test that exercises it through the live Quarkus server. Two test shapes, both running under `@QuarkusTest`:

- **Approval tests** — query goes in, response JSON is asserted byte-equal against a checked-in `*.approved.json`. Same shape as the legacy example's `src/test/resources/approval/`.
- **Match tests** — query goes in, response is asserted via Hamcrest / AssertJ matchers (used when JSON byte equality is too brittle, e.g. timestamp fields). Same shape as the legacy example's `src/test/resources/match/`.

Tests live under `graphitron-example/src/test/`. The Quarkus dev-services Postgres container serves both `mvn quarkus:dev` and `mvn test`.

No `pipeline` / `compilation` / `execution` tier annotations; this module is consumer-tier integration, not generator-tier coverage. The four-tier taxonomy in `testing.adoc` is for the generator's own test suites under `graphitron/` and `graphitron-test/`.

## Documentation touch-up

When Phase A lands:

- `docs/quick-start.adoc:21` — replace the legacy link with `https://github.com/sikt-no/graphitron/tree/claude/graphitron-rewrite/graphitron-rewrite/graphitron-example`.
- `docs/quick-start.adoc:64` — same swap on the "Working example" section.
- `graphitron-rewrite/docs/getting-started.adoc` — add a one-line "for a complete app, see the example module" pointer at the top of "Hello world".

No new doc page. The example *is* the documentation for "what does a real wiring look like"; `getting-started.adoc` already covers the snippets. A long-form tutorial is a separate roadmap item if it's ever wanted.

## Out of scope

- *Subscriptions*. The rewrite generator doesn't emit subscription resolvers today.
- *GraphiQL UI*. The legacy example bundles GraphiQL under `META-INF/resources/graphiql/`; we can ship the same bundle if it's cheap, but it isn't a Phase-A blocker. File as a follow-up if a consumer asks.
- *`mise` task wrapping*. The legacy example uses `mise r build` / `mise r start` shorthands. Reproduce only if it materially shortens the README; otherwise stop at `mvn install` / `mvn quarkus:dev`.
- *Schema feature filter*. Legacy-only schema-rewrite pass; the rewrite generator has no equivalent and it doesn't belong in a fresh example.
- *Validating dev-loop interactions* (Quarkus auto-reload picking up schema edits via `graphitron:dev`). The dev loop is documented in `getting-started.adoc`; the example consumes it implicitly via Quarkus's source watcher. A standalone test that asserts hot-reload semantics would be over-spec for this plan.

## Roadmap integration

This plan unblocks the docs-pointing side of [`retire-maven-plugin.md`](retire-maven-plugin.md). It does *not* by itself enable the legacy-example deletion: that closes when every legacy consumer has migrated (cadence dictated by per-consumer feature work, per R26's body). Once Phase A lands and the docs repoint, the legacy `graphitron-example/` is purely a courtesy reference for in-flight migrators; its eventual deletion is R26's call.

When this plan closes, the entry in `changelog.md` records the landing commit(s) and the test location, and the file deletes per the delete-on-Done rule.
