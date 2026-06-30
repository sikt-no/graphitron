---
id: R399
title: "Reusable JAX-RS library serving a Graphitron schema per the GraphQL-over-HTTP spec"
status: Spec
bucket: feature
priority: 5
theme: service
depends-on: []
created: 2026-06-30
last-updated: 2026-06-30
---

# Reusable JAX-RS library serving a Graphitron schema per the GraphQL-over-HTTP spec

## Motivation

Every Graphitron subgraph that exposes its schema over HTTP today hand-copies the same JAX-RS
plumbing, and the copies have already drifted. The internal `tilgangsstyring` subgraph ships five
near-identical classes:

- a `@Path("/graphql")` resource doing GraphQL-over-HTTP content negotiation (POST `application/json`,
  GET, a `/schema` SDL endpoint, a GraphiQL HTML page), wiring the `GraphQLSchema` into a
  `GraphQL.newGraphQL(...).instrumentation(...).execute(input)` call, and applying the modern/legacy
  status-code rule (200 when data present, 400 otherwise, but always 200 in legacy mode);
- an `ExecutionBodyHandler` (`MessageBodyReader<ExecutionInput>` + `MessageBodyWriter<ExecutionResult>`)
  parsing `{query, operationName, variables, extensions}`, validating that `query` is present, building
  the input via `Graphitron.newExecutionInput(...)`, and serialising `result.toSpecification()`;
- a `GraphitronSchemaProvider` wrapping `Graphitron.buildSchema(...)` and reading the bundled
  `schema.graphqls` resource;
- a `GraphQLTelemetryProvider` producing an OpenTelemetry `GraphQLTelemetry` instrumentation bean.

Our own `graphitron-sakila-example` carries a *fourth* dialect of the same code
(`GraphqlResource` + `GraphqlEngine` + an inline `GraphqlRequest` record), and it has already diverged
from the consumer copies: it omits the `extensions` field, lacks the `/schema` and GraphiQL endpoints,
does not apply the modern-vs-legacy status-code rule (it always returns 200), and embeds GraphiQL
via a redirect rather than a served page. That divergence between the reference app and real consumers
is exactly the smell this item targets: the GraphQL-over-HTTP contract (media types
`application/json` in / `application/graphql-response+json` out, the legacy `application/json` out
watershed, status-code semantics, error shape, GET-vs-POST rules) is fiddly and easy to get subtly
wrong, and there is no single tested implementation anyone can depend on.

This sits directly on top of the R45/R190 schema-driven `ExecutionInput` factory: the library is the
HTTP-layer consumer of `Graphitron.newExecutionInput(...)`, `Graphitron.buildSchema(...)`, and the
sealed `GraphitronContext`. The runtime substrate the library would lean on already exists; what is
missing is a reusable, spec-conformant JAX-RS surface over it.

## What the library should absorb vs. what stays per-subgraph

The split is along the line of "GraphQL-over-HTTP transport" (generic, belongs in the library) vs.
"how this subgraph authenticates a request and binds a `DSLContext`/context arguments" (subgraph-specific).

**Absorb into the library:**

- The `/graphql` resource: GET/POST handling, content-type negotiation, the modern/legacy
  `Produces` split, status-code semantics, the `/schema` SDL endpoint, and serving a GraphiQL page.
- The `ExecutionInput` reader / `ExecutionResult` writer (request JSON parse + `query`-required
  validation + `toSpecification()` serialisation).
- Schema construction and SDL exposure (over `buildSchema` + the bundled `schema.graphqls`).
- An optional, pluggable instrumentation hook (so OpenTelemetry `GraphQLTelemetry` is opt-in, not
  hard-wired).

**Stays in each subgraph (but plugs into the library via a small SPI):**

- The per-request context wiring: the `tilgangsstyring` `AuthenticatedContextProvider` (its
  `@Named` `DataSource`, connection lifecycle, JWT `app.claims` `set_config` forwarding, and the
  `tilgangstokenJson` claim-projection) is genuinely subgraph-specific and must remain owned by the
  subgraph. The library should consume it through the existing `GraphitronContext` /
  `newExecutionInput(context, ...)` contract rather than re-implementing it.
- The choice of generated `Graphitron` facade class (each subgraph generates its own in its own
  package), which means the library cannot name the facade directly and needs a small provider SPI the
  subgraph implements.

## Open questions for Spec

- **Where does it live?** The rewrite has no runtime/serving module yet (serving code lives only in
  the example app). A new `graphitron-*` runtime module under `graphitron-rewrite/`, or a separate
  artifact consumers depend on alongside generated code.
- **How generic is "JAX-RS"?** The existing copies use Quarkus/RESTEasy Reactive specifics
  (`org.jboss.resteasy.reactive.NoCache`, `@Context ObjectMapper`, CDI `@Inject`). Decide whether the
  library targets plain Jakarta REST + a thin CDI integration, or is explicitly Quarkus-flavoured.
- **The facade SPI shape.** How a subgraph hands the library its generated `Graphitron` facade
  (schema, SDL resource, `newExecutionInput`) and its per-request `GraphitronContext` source, given
  the facade class name differs per subgraph.
- **GraphQL-over-HTTP conformance scope.** Which parts of the spec we commit to (media types, status
  codes, GET/POST, batching, the `application/graphql-response+json` watershed) and how that is tested,
  ideally folded into `graphitron-sakila-example` so the reference app exercises the real library
  instead of its own fourth copy.
- **Relationship to R45 multi-tenant routing.** The tenant-routing factory work should compose with,
  not duplicate, the request-to-`ExecutionInput` path this library owns.
