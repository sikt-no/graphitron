---
id: R67
title: "Promote graphitron-test to graphitron-sakila-example (rename, Quarkus runtime, consumer test pattern)"
status: Spec
bucket: architecture
theme: legacy-migration
depends-on: []
---

# Promote graphitron-test to graphitron-sakila-example

Two gaps close with the same artifact:

1. **The public docs point at the wrong example.** `docs/quick-start.adoc:21,64` link to root-level `graphitron-example/`, which wires the *legacy* `graphitron-maven-plugin` and the legacy `graphitron-servlet` runtime. Anyone following those links lands on the stack R26 retires.
2. **The rewrite ships no public answer to "how do I test my schema?"** Internally, `graphitron-test` runs query-to-database coverage against a real Postgres: tests build the schema in-process via `Graphitron.buildSchema(...)`, execute queries via `graphql-java`, and assert on the rows that come back. Approval-style queries and federation entity dispatch tests round it out. That pattern is exactly what consumers need when they sit down to pin their own schema's behaviour, but today it lives behind a test-internal name (`graphitron-test`) with no documentation surface that says "copy this."

Promoting `graphitron-test` into a consumer-facing artifact closes both: the docs get something honest to point at, and the recommended consumer test pattern becomes a thing you can read and copy from a documented module. The runtime side is small (Quarkus shell + JAX-RS resource); the substantive shift is reframing the artifact's role from "rewrite e2e test" to "runnable reference application that also doubles as our query-to-database coverage."

## Goal

Ship `graphitron-rewrite/graphitron-sakila-example/` as a Quarkus app that:

- Builds on the schema and jOOQ catalog `graphitron-test` already exercises (Sakila-inspired domain). Both schemas the module generates today (`schema.graphqls` non-federated, `federated-schema.graphqls`) keep generating; the runtime boots only the non-federated `Graphitron`, while the federated one stays for in-process federation tests.
- Boots via `mvn quarkus:dev` and serves GraphQL over HTTP via JAX-RS (`quarkus-rest`) per the [GraphQL-over-HTTP spec](https://graphql.github.io/graphql-over-http/) (POST + GET, content-negotiated `application/json` and `application/graphql-response+json`).
- Runs `mvn test` for the existing query-to-database coverage plus a curated consumer-test-pattern subset.
- Carries a README that names the two roles explicitly (runnable reference, recommended test pattern) and tells a reader which directories to copy.

End state: `docs/quick-start.adoc:21,64` repoint at `graphitron-sakila-example`, the legacy `graphitron-example/` becomes purely a courtesy reference for in-flight migrators, and the rewrite has a public answer to the "how do I test my schema" question.

## Module restructure (Stage 0)

Stage 0 lands as the plan's first commit and is a rename + dependency rewire. The reactor still has one database, one `init.sql`, and one jOOQ codegen module (with the same three executions covering `public`, `nodeidfixture`, `idreffixture`); none of those are split. After Stage 0 the modules build identically; their names just say what they are.

| Today | Target |
|---|---|
| `graphitron-fixtures` (full module: `init.sql` covering `public`/`nodeidfixture`/`idreffixture`, three jOOQ codegen executions) | **`graphitron-sakila-db`** |
| `graphitron-fixtures` (services + conditions + extensions: `FilmService`, `CategoryConditions`, `FilmExtensions`, `SampleQueryService`, ...) | **`graphitron-sakila-service`** |
| `graphitron-test` (schema + query-to-database test coverage) | **`graphitron-sakila-example`** |
| `graphitron-fixtures-codegen` | unchanged |

The catalog module is named for its Sakila-flavored headline content; the two fixture sub-schemas (`nodeidfixture`, `idreffixture`) ride along inside `init.sql` and the jOOQ build emits all three from one Postgres instance. No separate tier-annotations artifact and no slim residual `graphitron-fixtures`.

Tier annotations (`UnitTier` / `PipelineTier` / `CompilationTier` / `ExecutionTier`) move from `graphitron-fixtures/src/main/java/.../test/tier/` to `graphitron/src/test/java/.../test/tier/`, and `graphitron`'s pom configures a test-jar (`maven-jar-plugin`'s `test-jar` goal). Today the only consumers are `graphitron` itself and `graphitron-test`; after the move `graphitron` uses them directly from its own test source root, and `graphitron-sakila-example` consumes them via `<type>test-jar</type><classifier>tests</classifier><scope>test</scope>` on its `graphitron` dep. The package path stays `no.sikt.graphitron.rewrite.test.tier`, so consumer import statements do not change. This keeps test-only annotations off the database catalog module's main classpath, which is what reading `graphitron-sakila-db`'s description out loud should suggest.

`graphitron-sakila-service` depends on `graphitron-sakila-db` (services reference the generated jOOQ tables). `graphitron-sakila-example` depends on both, plus pulls `graphitron-sakila-service` onto the `graphitron-maven` plugin's codegen classpath via `<plugin><dependencies>` so `ServiceCatalog.reflectServiceMethod` can `Class.forName(...)` the service classes at generate-time (same wiring `graphitron-test` uses today).

Dependents to update in the same commit:

- `graphitron-rewrite/graphitron-lsp/pom.xml` (test scope): repoint at `graphitron-sakila-db`.
- `graphitron-rewrite/graphitron-maven/src/it/basic-generate/pom.xml` (invoker IT, codegen-classpath): repoint at `graphitron-sakila-db`.
- `graphitron-rewrite/graphitron/pom.xml`: repoint the test-scope catalog dep at `graphitron-sakila-db` (consumed by `JooqCatalogNodeIdMetadataTest`, `NodeIdLeafResolverTest`, `JooqCatalogIdRefTest` for the `nodeidfixture` / `idreffixture` catalogs); add the `maven-jar-plugin` `test-jar` goal so other modules can consume tier annotations.
- `graphitron-rewrite/graphitron-sakila-example/pom.xml`: replace the (former) `graphitron-fixtures` compile-scope dep with two narrower deps: `graphitron-sakila-db` (jOOQ catalog), `graphitron-sakila-service` (services on the codegen plugin classpath); add `<type>test-jar</type><classifier>tests</classifier><scope>test</scope>` to the existing `graphitron` test-scope dep so tier annotations resolve.
- `graphitron-rewrite/pom.xml`: `<modules>` list now names `graphitron-sakila-db`, `graphitron-sakila-service`, `graphitron-sakila-example`.

Stage 0 exit: `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` builds clean against the new names; no behaviour change; commit subject reads `R67 Stage 0: split graphitron-fixtures into Sakila-named modules; rename graphitron-test`.

## Stage 1: Quarkus runtime

Layer the runtime shell onto `graphitron-sakila-example`. Both `graphitron-maven` generator executions stay: `schema.graphqls` emits to `no.sikt.graphitron.generated`, `federated-schema.graphqls` emits to `no.sikt.graphitron.generated.federated`. The Quarkus runtime boots only the **non-federated** `Graphitron`; the federated execution stays so federation tests in Stage 2 can build and verify it in-process. Two endpoints would muddy the example's "this is what consumers do" message; the federated build stays a test-only artifact.

Why `quarkus-rest` and not `quarkus-smallrye-graphql`: SmallRye GraphQL ships its own engine and would collide with the `Graphitron`-built `GraphQL`. Plain JAX-RS plus a single `/graphql` resource is the minimal correct shape.

- `pom.xml` adds `quarkus-bom` import, `quarkus-rest`, `quarkus-config-yaml`, `quarkus-jdbc-postgresql`, `quarkus-agroal`, `quarkus-hibernate-validator` (the generator emits `jakarta.validation` references unconditionally, replacing the existing test-scope `hibernate-validator` + `expressly` deps), plus the `quarkus-maven-plugin` execution.
- `src/main/resources/application.yaml` configures the HTTP port and a Quarkus dev-services PostgreSQL. Dev-services is keyed off `init.sql` from `graphitron-sakila-db`'s classpath (Quarkus picks up `db/init/*.sql` resources from the runtime classpath; the catalog module exposes `init.sql` so the same DDL drives both codegen and dev runtime). The `local-db` profile, kept for compatibility with `mvn test`, sets `%test.quarkus.datasource.jdbc.url` so `mvn test -Plocal-db` continues to talk to a pre-existing local Postgres without dev-services.
- `GraphqlEngine` `@ApplicationScoped` bean builds the `GraphQLSchema` once via `Graphitron.buildSchema(b -> ...)` (using the customizer surface the `getting-started.adoc` "Customizer safe surface" section names) and exposes a `GraphQL` engine.
- `GraphqlResource` JAX-RS `@Path("/graphql")`: `POST` accepts `application/json`, returns `application/graphql-response+json`; `GET` for query-only requests; content negotiation per the GraphQL-over-HTTP spec. Each request builds a fresh `DataLoaderRegistry` and stashes the `AppContext` under `GraphitronContext.class` on the `ExecutionInput`, per `getting-started.adoc:343` and the worked example in `getting-started.adoc:60`.
- `AppContext implements GraphitronContext` carries per-request `DSLContext` derived from the Quarkus `AgroalDataSource`; `getContextArgument` reads JWT-claim style values per the `getting-started.adoc` worked example.
- One smoke test (`@QuarkusTest`) hits `/graphql` with a basic query and asserts a non-empty response. This is the only HTTP-shaped test in the module; everything else stays at the schema/engine level (Stage 2).

The existing `default-compile` ratchet to `<release>17</release>` stays in place. After Stage 1 it covers the new hand-written `AppContext` / `GraphqlEngine` / `GraphqlResource` alongside the generated sources, which is a feature: it ratchets that the recommended consumer setup compiles under Java 17 just like the generated code.

Stage 1 exit: `mvn quarkus:dev` from `graphitron-sakila-example/` serves a working `/graphql` against the live Postgres; the federated schema's generator execution still produces classes the federation tests reference; `docs/quick-start.adoc:21,64` *could* be repointed (but that lands in Stage 3 alongside the README that names the test pattern).

## Stage 2: test surface curation + README

`graphitron-test`'s existing test classes split into two categories. Stage 2 carries that split into the renamed module, adds two new approval-style worked examples, relocates `IdempotentWriterTest` from `graphitron`, and lands the README that names the two roles a reader is here for.

**Query-to-database pattern** (lives under `src/test/java/.../querydb/`, called out in the README). These tests stay in-process: they build the schema via `Graphitron.buildSchema(...)`, instantiate a `GraphQL` engine, execute via `graphql-java`, and assert against a live Postgres `DSLContext`. They do *not* go through the Quarkus HTTP stack. Keeping the pattern in-process means consumers can copy it without bringing Quarkus into their test classpath; the Stage 1 `@QuarkusTest` smoke test is the single HTTP-shaped check in the module.

- `GraphQLQueryTest`: runs queries against the live Postgres; the canonical "test your schema" shape.
- `FederationEntitiesDispatchTest`: federation entity-dispatch in-process against the federated schema execution.
- `FederationBuildSmokeTest`: pins that the federated schema builds with `_Service` / `_Entity` wiring in place.
- `NoFederationRegressionTest`: sanity check that the non-federation path still works.
- New: an approval-style test directory mirroring the legacy example's `src/test/resources/approval/` and `src/test/resources/match/` patterns. One worked example each; the README points at them as templates.

**Generator-internal coverage** (lives under `src/test/java/.../internal/`, README explicitly says "you don't need this"):

- `GeneratorDeterminismTest`: pins the rewrite's three-clause idempotent-write contract end-to-end against the full fixture schema.
- `IdempotentWriterTest`: pins writer mechanics (tamper-detection, orphan sweep, scope preservation) with a trivial inline SDL. Relocated from `graphitron/src/test/java/no/sikt/graphitron/rewrite/`. It is `@UnitTier` and references only public main-classpath classes from `graphitron` (`RewriteContext`, `GraphQLRewriteGenerator`, `SchemaInput`), so the move is mechanical. Co-locating writer-mechanics and end-to-end determinism is what the `getting-started.adoc:283` cross-reference between the two tests already implies.
- `GeneratedSourcesSmokeTest` / `GeneratedSourcesLintTest`: generator-internal lints.
- `ScatterSingleByIdxTest`: pins a SQL-side scatter invariant.
- `TierAnnotationEnforcementTest`: pins the test-tier vocabulary (this module's own meta-test).
- `MutationPayloadLifterTest`: pins a generator-internal classification path.
- `AccessorDerivedBatchKeyTest`: pins R65's accessor model.

**README** (`graphitron-sakila-example/README.md`) names the two roles in its first paragraph (runnable reference, recommended test pattern) and points at the directories a consumer should copy: `src/main/java/.../app/` (Quarkus shell), `src/main/resources/graphql/schema.graphqls`, `src/main/resources/application.yaml`, `src/test/java/.../querydb/` (test pattern). It explains the carve-out: anything under `internal/` is generator-internal coverage and not part of the pattern.

Stage 2 is directory moves + package renames + README authoring + two new approval/match worked examples + the `IdempotentWriterTest` relocation. The Stage-1 + Stage-2 net delta is +3 tests in `graphitron-sakila-example` (Quarkus smoke, approval worked example, match worked example) plus the `IdempotentWriterTest` move from `graphitron`; `graphitron`'s test count drops by exactly that one.

Stage 2 exit: `mvn test` passes; the README reads cleanly when explaining "here's the pattern, here's what's not part of it."

## Stage 3: docs touch-up

- `docs/quick-start.adoc:21` and `:64` swap legacy-`graphitron-example` links for `graphitron-sakila-example`.
- `docs/quick-start.adoc` adds one-paragraph mention that the example doubles as a consumer test pattern (with link to the example's README).
- `graphitron-rewrite/docs/getting-started.adoc` adds a one-line "for a complete app and the recommended test setup, see the example module" pointer in the "Hello world" section.

Stage 3 exit: docs land; `docs-site-asciidoc` build still passes; CI on `.adoc` paths green.

## Tests

After the plan lands `graphitron-sakila-example` carries every test today's `graphitron-test` carries, plus three additions (Quarkus smoke, one approval worked example, one match worked example), plus `IdempotentWriterTest` relocated from `graphitron`. The two categories named in Stage 2 (query-to-database vs generator-internal) are the new contract: every future test addition picks a side, and the README explains the rule.

Test tier annotations (`@UnitTier`, `@PipelineTier`, `@CompilationTier`, `@ExecutionTier`) carry through unchanged at the source level. They relocate from `graphitron-fixtures`'s main source root to `graphitron`'s test source root (Stage 0 detail above); the package path stays `no.sikt.graphitron.rewrite.test.tier` so import statements do not change. The four-tier taxonomy in `testing.adoc` is unchanged; it already covers the case where execution-tier tests double as documentation.

## Out of scope

- *Deleting legacy `graphitron-example/`*. R26 owns that step; it closes when every legacy consumer has migrated. This plan only repoints docs.
- *Subscriptions*. The rewrite generator doesn't emit subscription resolvers.
- *GraphiQL UI bundling*. The legacy example shipped a vendored GraphiQL under `META-INF/resources/`; the rewrite example skips this unless a consumer asks. Reachable via Quarkus dev UI in `quarkus:dev`.
- *Test-pattern variants beyond approval + match*. Two patterns is the recommended-pattern surface; richer test taxonomies (snapshot, property-based) are out.
- *HTTP-shaped query-to-database tests*. The pattern stays in-process via `graphql-java`. The Stage 1 `@QuarkusTest` is the only HTTP-shaped check in the module; rewriting the existing query-to-database tests on top of REST-Assured is a separate undertaking.
- *Schema simplification for pedagogy*. The existing test schema stays comprehensive; the example artifact serves the "I want to see this running" audience, not the gentle on-ramp. The on-ramp lives in `getting-started.adoc`.
- *Multi-module restructuring beyond the three-name rename*. `graphitron-fixtures-codegen` keeps its current name; the catalog stays a single module (`graphitron-sakila-db`) carrying all three of `init.sql`'s schemas. Splitting any further is unrelated to the consumer-facing direction this plan locks in.

## Roadmap integration

This plan unblocks the docs-pointing side of [`retire-maven-plugin.md`](retire-maven-plugin.md) (R26): once Stage 3 ships, `quick-start.adoc` no longer relies on the legacy example's continued existence. R26's "delete legacy + unnest" step still gates on every consumer's migration cadence; this plan does not change that timeline.

When Stage 3 lands the plan closes. The `changelog.md` entry records the four landing commits (Stages 0-3) and the test location; the plan file deletes per the delete-on-Done rule. If Stages 0-2 land cleanly but Stage 3 is delayed (e.g., docs PR review queue), the plan stays `In Progress` rather than `In Review`, since Stage 3 is small and skipping it would leave the docs lying.
