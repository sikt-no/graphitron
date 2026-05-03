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
2. **The rewrite ships no public answer to "how do I test my schema?"** Internally, `graphitron-test` runs end-to-end coverage against a real Postgres with `@QuarkusTest`-shaped fixtures, approval-style queries, and federation entity dispatch tests. That pattern is exactly what consumers need when they sit down to pin their own schema's behaviour, but today it lives behind a test-internal name (`graphitron-test`) with no documentation surface that says "copy this."

Promoting `graphitron-test` into a consumer-facing artifact closes both: the docs get something honest to point at, and the recommended consumer test pattern becomes a thing you can read and copy from a documented module. The runtime side is small (Quarkus shell + JAX-RS resource); the substantive shift is reframing the artifact's role from "rewrite e2e test" to "runnable reference application that also doubles as our e2e coverage."

## Goal

Ship `graphitron-rewrite/graphitron-sakila-example/` as a Quarkus app that:

- Builds on the schema and jOOQ catalog `graphitron-test` already exercises (Sakila-inspired domain).
- Boots via `mvn quarkus:dev` and serves GraphQL over HTTP via JAX-RS (`quarkus-rest`) per the [GraphQL-over-HTTP spec](https://graphql.github.io/graphql-over-http/) (POST + GET, content-negotiated `application/json` and `application/graphql-response+json`).
- Runs `mvn test` for the existing end-to-end coverage plus a curated consumer-test-pattern subset.
- Carries a README that names the two roles explicitly (runnable reference, recommended test pattern) and tells a reader which directories to copy.

End state: `docs/quick-start.adoc:21,64` repoint at `graphitron-sakila-example`, the legacy `graphitron-example/` becomes purely a courtesy reference for in-flight migrators, and the rewrite has a public answer to the "how do I test my schema" question.

## Module restructure (Stage 0)

Stage 0 lands as the plan's first commit and is purely a rename. After Stage 0 the modules build identically; their names just say what they are.

| Today | Target |
|---|---|
| `graphitron-fixtures` (Sakila-shaped `public` schema + jOOQ codegen execution) | **`graphitron-sakila-db`** |
| `graphitron-fixtures` (services + conditions + extensions: `FilmService`, `CategoryConditions`, `FilmExtensions`, ...) | **`graphitron-sakila-service`** |
| `graphitron-test` (schema + e2e test coverage) | **`graphitron-sakila-example`** |
| `graphitron-fixtures` (residual: `nodeidfixture` + `idreffixture` schemas, only consumed by `graphitron`'s own unit tests) | stays in a slim `graphitron-fixtures` |
| `graphitron-fixtures-codegen` | unchanged |

Dependents to update in the same commit (Maven `<dependency>` entries pointing at the old artifactIds):

- `graphitron-rewrite/graphitron-lsp/pom.xml` — test scope, currently consumes `graphitron-fixtures`'s catalog.
- `graphitron-rewrite/graphitron-maven/src/it/basic-generate/pom.xml` — invoker IT, currently consumes the catalog as a codegen-classpath dependency.
- `graphitron-rewrite/graphitron/pom.xml` — test scope, currently consumes the residual `graphitron-fixtures` for `nodeidfixture` / `idreffixture` (no change to its dependency line; the residual module keeps the old name).
- `graphitron-rewrite/pom.xml` — `<modules>` list reflects the rename and the new module.

Stage 0 exit: `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` builds clean against the new names; no behaviour change; commit subject reads `R67 Stage 0: rename Sakila fixtures and graphitron-test`.

## Stage 1: Quarkus runtime

Layer the runtime shell onto `graphitron-sakila-example`:

- `pom.xml` adds `quarkus-bom` import, `quarkus-rest`, `quarkus-config-yaml`, `quarkus-jdbc-postgresql`, `quarkus-agroal`, plus the `quarkus-maven-plugin` execution.
- `src/main/resources/application.yaml` configures the HTTP port and a Quarkus dev-services PostgreSQL (auto-applied init script for the Sakila-inspired schema; same `init.sql` already used by `graphitron-sakila-db` for codegen).
- `GraphqlEngine` `@ApplicationScoped` bean builds the `GraphQLSchema` once via `Graphitron.buildSchema(b -> ...)` (using the customizer surface the `getting-started.adoc` "Customizer safe surface" section names) and exposes a `GraphQL` engine.
- `GraphqlResource` JAX-RS `@Path("/graphql")`: `POST` accepts `application/json`, returns `application/graphql-response+json`; `GET` for query-only requests; content negotiation per the GraphQL-over-HTTP spec.
- `AppContext implements GraphitronContext` carries per-request `DSLContext` derived from the Quarkus `AgroalDataSource`; `getContextArgument` reads JWT-claim style values per the `getting-started.adoc` worked example.
- One smoke test (`@QuarkusTest`) hits `/graphql` with a basic query and asserts a non-empty response.

Stage 1 exit: `mvn quarkus:dev` from `graphitron-sakila-example/` serves a working `/graphql` against the live Postgres; `docs/quick-start.adoc:21,64` *could* be repointed (but that lands in Stage 3 alongside the README that names the test pattern).

## Stage 2: test surface curation

`graphitron-test`'s existing test classes split into two categories. Stage 2 carries that split into the renamed module so a reader copying the test pattern knows what's pattern and what's generator-internal.

**Consumer-facing pattern** (lives under `src/test/java/.../consumer/` or similar, called out in the README):

- `GraphQLQueryTest` — runs queries against the live Postgres; the canonical "test your schema" shape.
- `FederationEntitiesDispatchTest` — federation entity-dispatch end-to-end against the running server.
- `NoFederationRegressionTest` — sanity check for the non-federation path.
- New: an approval-style test directory mirroring the legacy example's `src/test/resources/approval/` and `src/test/resources/match/` patterns. One worked example each; the README points at them as templates.

**Generator-internal coverage** (lives under `src/test/java/.../internal/`, README explicitly says "you don't need this"):

- `GeneratorDeterminismTest` — pins the rewrite's three-clause idempotent-write contract.
- `GeneratedSourcesSmokeTest` / `GeneratedSourcesLintTest` — generator-internal lints.
- `ScatterSingleByIdxTest` — pins a SQL-side scatter invariant.
- `TierAnnotationEnforcementTest` — pins the test-tier vocabulary (this module's own meta-test).
- `MutationPayloadLifterTest` — pins a generator-internal classification path.
- `AccessorDerivedBatchKeyTest` — pins R65's accessor model.

Stage 2 is mostly directory moves + package renames + README authoring. No new behaviour; no test count change.

Stage 2 exit: `mvn test` passes; the README reads cleanly when explaining "here's the pattern, here's what's not part of it."

## Stage 3: docs touch-up

- `docs/quick-start.adoc:21` and `:64` swap legacy-`graphitron-example` links for `graphitron-sakila-example`.
- `docs/quick-start.adoc` adds one-paragraph mention that the example doubles as a consumer test pattern (with link to the example's README).
- `graphitron-rewrite/docs/getting-started.adoc` adds a one-line "for a complete app and the recommended test setup, see the example module" pointer in the "Hello world" section.

Stage 3 exit: docs land; `docs-site-asciidoc` build still passes; CI on `.adoc` paths green.

## Tests

The artifact's test count after the plan lands ≥ today's `graphitron-test` count (Stage 2 only relocates; Stage 1 adds one smoke test). The two categories named in Stage 2 are the new contract: every future test addition picks a side, and the README explains the rule.

Test tier annotations (`@UnitTier`, `@PipelineTier`, `@CompilationTier`, `@ExecutionTier`) carry through unchanged from `graphitron-test`. The four-tier taxonomy in `testing.adoc` is unchanged; it already covers the case where execution-tier tests double as documentation.

## Out of scope

- *Deleting legacy `graphitron-example/`*. R26 owns that step; it closes when every legacy consumer has migrated. This plan only repoints docs.
- *Subscriptions*. The rewrite generator doesn't emit subscription resolvers.
- *GraphiQL UI bundling*. The legacy example shipped a vendored GraphiQL under `META-INF/resources/`; the rewrite example skips this unless a consumer asks. Reachable via Quarkus dev UI in `quarkus:dev`.
- *Test-pattern variants beyond approval + match*. Two patterns is the recommended-pattern surface; richer test taxonomies (snapshot, property-based) are out.
- *Schema simplification for pedagogy*. The existing test schema stays comprehensive; the example artifact serves the "I want to see this running" audience, not the gentle on-ramp. The on-ramp lives in `getting-started.adoc`.
- *Multi-module restructuring beyond the four-name rename*. `graphitron-fixtures-codegen` and the slim residual `graphitron-fixtures` keep their current names; renaming them is unrelated to the consumer-facing direction this plan locks in.

## Roadmap integration

This plan unblocks the docs-pointing side of [`retire-maven-plugin.md`](retire-maven-plugin.md) (R26): once Stage 3 ships, `quick-start.adoc` no longer relies on the legacy example's continued existence. R26's "delete legacy + unnest" step still gates on every consumer's migration cadence; this plan does not change that timeline.

When Stage 3 lands the plan closes. The `changelog.md` entry records the four landing commits (Stages 0-3) and the test location; the plan file deletes per the delete-on-Done rule. If Stages 0-2 land cleanly but Stage 3 is delayed (e.g., docs PR review queue), the plan stays `In Progress` rather than `In Review` — Stage 3 is small and skipping it would leave the docs lying.
