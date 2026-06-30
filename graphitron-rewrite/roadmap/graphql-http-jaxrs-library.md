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

## Design

### Module

A new runtime module `graphitron-jakarta-rest` under `graphitron-rewrite/`. It is the first
hand-written *runtime* artifact consumers depend on, distinct from both generator code (Java 25) and
generated output (Java 17). It compiles with `<release>17</release>`: consumers compile generated
output and their own code on a Java 17 floor (see `graphitron-sakila-example`, which sets
`<release>17</release>` precisely to verify that floor), and a runtime jar they put on that same
classpath must not require newer bytecode. The module joins the publishable deploy set (the modules
listed in `docs/README.adoc`); it is a real artifact consumers pull, unlike `graphitron-sakila-example`
(which carries `maven.deploy.skip=true` and is only the first consumer, never the artifact).

Reviewer note: the Java-version principle in `docs/rewrite-design-principles.adoc` today draws a
two-way split (generator implementation = Java 25; generated source = Java 17) and does not name this
third category. This item is the first instance of it; the principle should grow a bullet for
"hand-written runtime artifacts consumers depend on -> Java 17" so a later reader does not apply the
literal two-way split and license Java 25 here.

### The dependency-inversion seam

The library cannot name the generated `Graphitron` facade: its class lives in a per-subgraph package,
and `newExecutionInput` varies per schema in both arity and parameter type (its context-arg values are
resolved per request from auth). So the subgraph's adapter is the only place that names the facade,
and the library depends on an interface it defines:

```java
public interface GraphitronApplication {
    // The single executable schema. Source of truth for both the engine and the SDL endpoint.
    GraphQLSchema schema();

    // Per-request, auth-seeded input builder. The library layers query/variables/
    // operationName/extensions from the HTTP body on top, then executes.
    ExecutionInput.Builder newExecutionInput();

    // Engine assembly via graphql-java's own builder seam. Default uses schema();
    // override to chain .instrumentation(...), a custom ExecutionStrategy, etc.
    default GraphQL.Builder engineBuilder() {
        return GraphQL.newGraphQL(schema());
    }
}
```

Two refinements over the first interview sketch, both from the `principles-architect` pass:

- **Engine, not ingredients.** The first sketch had a `List<Instrumentation> instrumentations()`
  default and let the library hand-assemble the engine, which re-derives downstream what graphql-java's
  builder already owns and expresses only one kind of customization. `engineBuilder()` returns the
  standard `GraphQL.Builder` instead, so instrumentation (OpenTelemetry `GraphQLTelemetry`), execution
  strategies, and any future engine knob ride the seam graphql-java and the facade's `newGraphQL()`
  already define. The library caches `engineBuilder().build()` once (application scope). The default
  delegates to `schema()` rather than the facade's `newGraphQL()` so there is exactly one built schema,
  not one for the engine and another for the SDL endpoint.
- **One schema, no second SDL source.** The first sketch had a `schemaSdl()` method reading the
  bundled `schema.graphqls` resource: a second source of truth that can drift from `schema()` (a
  `buildSchema` customizer that adds types appears in the executable schema but not in the bundled
  file). Dropped from the SPI; the library renders the `/schema` SDL endpoint from `schema()` via
  graphql-java's `SchemaPrinter` at the HTTP boundary. (Federation note: the executable schema is
  `Federation.transform`-wrapped, so `SchemaPrinter` shows the augmented view, which is correct for the
  human-facing `/schema` convenience; gateway composition uses the federation `_service { sdl }` field,
  not this endpoint. If a consumer ever needs byte-exact source SDL the printer cannot reproduce, the
  abstract base exposes an overridable SDL hook rather than re-adding a SPI method.)

**Hand-written adapter, abstract base.** The adapter is hand-written by each consumer, not generated.
Generating it would give every generated codebase a compile-time dependency on `graphitron-jakarta-rest`,
coupling the generated-output version to the library version and breaking the generator/runtime
decoupling the generator deliberately keeps (`GraphitronContextInterfaceGenerator` depends only on
jOOQ + graphql-java, never a runtime jar). To remove the boilerplate, the library ships an abstract
base that implements `schema()` (cached) and the engine/SDL plumbing from a schema supplier the
consumer passes in, so the consumer's concrete class writes only the auth-bearing `newExecutionInput()`
and, optionally, an `engineBuilder()` override:

```java
@ApplicationScoped
public class MySubgraphApplication extends AbstractGraphitronApplication {
    @Inject AuthenticatedContextProvider auth;   // @RequestScoped, per-subgraph

    public MySubgraphApplication() {
        super(() -> Graphitron.buildSchema(b -> {}));   // only facade reference, via lambda
    }

    @Override
    public ExecutionInput.Builder newExecutionInput() {
        return Graphitron.newExecutionInput(auth.getContext());   // + any context args
    }
}
```

The supplier is a lambda over the static facade, so the abstract base never names a per-subgraph type;
the only generated-symbol references live in this tiny subclass.

### What the library owns

- The `@Path("/graphql")` JAX-RS resource and an application-scoped engine bean holding the cached
  `GraphQL`.
- Request parsing and serialisation in the resource itself, with **no custom JAX-RS providers**. The
  resource reads the raw body, parses it with the library's `ObjectMapper` into a small `GraphqlRequest`
  record (`query`, `operationName`, `variables`, `extensions`), validates `query` is present, and both
  the GET and POST verbs funnel through one `execute(...)` helper that builds the input via the seam,
  executes, and serialises `result.toSpecification()` (wire-format encoding stays at the HTTP boundary,
  never in the model). The first copies used a `MessageBodyReader<ExecutionInput>` /
  `MessageBodyWriter<ExecutionResult>` provider pair; we drop it deliberately, for two reasons beyond
  "single consumer":
  - **No reuse to capture.** A reader/writer pair earns its keep when several resources share the same
    entity marshalling; the library owns exactly one resource and both verbs already funnel through one
    helper, so the providers would centralise across a single call site.
  - **The fiddly parts cannot live in a provider anyway.** A `MessageBodyWriter<ExecutionResult>`
    cannot set `Response.status` from "request error vs. execution began", so the status watershed
    stays in the resource regardless and the writer would only wrap `toSpecification()`, which Jackson
    already does for the resulting `Map`. And spec-compliant parse-error shaping argues against
    auto-binding too: letting the stock JSON provider bind a malformed body yields the framework's
    default error, not a spec `4xx` `application/graphql-response+json`, so the resource must own
    parsing to control the error shape.

  We keep the original `ExecutionBodyHandler`'s intent, explicit parse-error -> `4xx` and explicit
  `.toSpecification()` so the raw graphql-java object is never serialised, without the `@Provider`
  machinery.
- The `GET /graphql/schema` SDL endpoint (`SchemaPrinter` over `schema()`).
- A bundled, CDN-based GraphiQL page served at `GET /graphql` with `Accept: text/html`. The toggle
  rides the SPI seam, not a config framework: an overridable `default boolean graphiqlEnabled()`
  (default `true`) on `AbstractGraphitronApplication`, mirroring how `engineBuilder()` and the
  instrumentation opt-in ride the seam. This is deliberate: the framework decision is vendor-neutral
  Jakarta with no RESTEasy/Quarkus types, and the project principles forbid adding dependencies not
  already pinned in the parent pom, so reaching for Quarkus `@ConfigProperty` or pulling MicroProfile
  Config to gate GraphiQL would violate a stated constraint. A consumer that wants the toggle wired to
  *its own* config framework overrides `graphiqlEnabled()` to read from wherever it likes; the library
  stays dependency-free. (A consumer that wants to serve a customized GraphiQL page can ride the same
  overridable-method pattern with an SDL-hook-style override returning the page resource.)

### GraphQL-over-HTTP conformance

Targets the current draft of the spec. Committed scope:

- **Media types.** Accept `application/json` request bodies; produce `application/graphql-response+json`
  (modern) or `application/json` (legacy) by content negotiation.
- **Status codes (media-type-driven).** With `application/graphql-response+json`, the draft pins
  distinct codes per *request error* class (a request error prevents execution; a *field error* arises
  during execution):
  - GraphQL document cannot be parsed -> `400` ("Requests where the _GraphQL document_ cannot be
    parsed should result in status code `400`").
  - Not a well-formed GraphQL-over-HTTP request, e.g. missing `query` -> `422` ("A request that does
    not constitute a well-formed _GraphQL-over-HTTP request_ SHOULD result in status code `422`").
  - GraphQL validation failure -> `422` ("If a request fails _GraphQL validation_, the server SHOULD
    return a status code of `422` ... without proceeding to GraphQL execution").
  - Variable coercion failure -> `422` ("If [CoerceVariableValues()] raises a _GraphQL request error_,
    the server SHOULD NOT execute the request and SHOULD return a status code of `422`").
  - Execution begins -> `200`, even with field errors ("This is the case even if a _GraphQL field
    error_ is raised during GraphQL's ExecuteQuery()").

  With legacy `application/json`: `200` for every well-formed request regardless of GraphQL errors.
  This supersedes both prior copies: the `tilgangsstyring` consumer copy uses a looser
  `result.isDataPresent() || isLegacy` rule (200 when data present, 400 otherwise), and the
  `graphitron-sakila-example` copy always returns `200`; neither implements the media-type-driven
  per-class mapping above.
- **GET.** POST is mandatory; GET is supported for read-only queries (the spec makes GET a MAY; we opt
  in for cacheable reads and browser links). A GET whose `query`/`operationName` resolves to a
  mutation returns `405 Method Not Allowed`, as the spec requires.
- **Batching.** The spec does not define batching; out of scope for v1, recorded as a possible
  follow-up.

### Testing and the conformance harness

`graphitron-sakila-example` is the first consumer and the conformance harness: its current
`GraphqlResource` + `GraphqlEngine` copy is replaced by a thin `GraphitronApplication` adapter on the
library, and a GraphQL-over-HTTP conformance test suite is added so the reference app exercises the
real library and cannot drift again.

**Spec-traceable conformance tests.** Each conformance assertion must quote the normative spec text it
verifies, with a stable reference to the section, so a reader can cross-check against the spec and so a
spec change is easy to locate and update. Concretely:

- One test (or named case) per normative requirement we claim to satisfy: POST-accepted, GET-MAY +
  mutation-over-GET -> `405`, and the per-class `application/graphql-response+json` status codes pinned
  above (unparseable document -> `400`; malformed request / missing `query` -> `422`; validation
  failure -> `422`; variable-coercion failure -> `422`; executed-with-field-errors -> `200`), legacy
  `application/json` always-`200`, `query`-required, and the
  `variables`/`operationName`/`extensions` request-parameter handling.
- Each case carries the verbatim MUST/SHOULD/MAY sentence as a doc comment or a `@DisplayName`, plus a
  section pointer (the spec section heading or anchor) and the spec revision the text was taken from,
  so drift between our behaviour and a future spec revision surfaces at the exact failing case.
- A short pointer table (requirement -> spec section -> test) lives next to the suite so coverage of
  the committed scope above is auditable at a glance, and a gap (a committed requirement with no
  citing test) is visible.

All behavioural coverage lives in this sakila-example suite, which is already in the tier-enforcement
in-scope list; `graphitron-jakarta-rest` itself carries **no `@Test` classes**. This is deliberate:
keeping the library test-free means the per-module tier-enforcement in-scope list need not grow, and no
library-module test can silently escape the tier guard. If unit-level tests of the library are ever
wanted (for example to exercise the status watershed in isolation), the module must join the
tier-enforcement in-scope list at that point.

Reviewer note: the conformance suite is the first behavioural test of hand-written *runtime* code
rather than generated output, and it does not fit the four-tier taxonomy (unit / pipeline /
compilation / execution) cleanly, since those tiers describe generated-code behaviour. The per-module
enforcement test (`testing.adoc`) fails the build on any `@Test` class lacking exactly one tier tag, so
the conformance suite carries `@ExecutionTier` (closest: a full request over a real engine). If a body
of runtime-library transport tests grows, the tier guide should note that they reuse `@ExecutionTier`.

### Decisions (resolved forks)

| Fork | Decision |
|---|---|
| Framework | Jakarta REST + Jakarta CDI, vendor-neutral; no RESTEasy/Quarkus-specific types |
| Module / Java level | New `graphitron-jakarta-rest`, `<release>17</release>`, publishable |
| Seam | `GraphitronApplication` SPI (`schema()`, `newExecutionInput()`, default `engineBuilder()`); hand-written adapter + abstract base |
| GraphiQL | Bundled CDN page, served; toggled by an overridable `graphiqlEnabled()` (default `true`) on the abstract base, no config-framework dependency |
| GET | Supported for queries; `405` on mutation |
| Batching | Out of scope for v1 |
| Status codes | Media-type-driven per spec |
| Body marshalling | No custom JAX-RS providers; resource parses to a `GraphqlRequest` record and serialises `toSpecification()`, owning parse-error `4xx` and the status watershed |
| Sakila | First consumer + conformance harness; spec-citing tests |

### Relationship to R45 / R190

The auth-to-context flow rides the existing `GraphitronContext` / `newExecutionInput` seam rather than
re-implementing per-request wiring; the `@RequestScoped` provider feeding `newExecutionInput()` is
consumer business logic staying consumer-side. R45 multi-tenant routing composes with this: it varies
what `newExecutionInput()` returns (a tenant-routed `DSLContext` and context args), it does not
duplicate the request-to-`ExecutionInput` path this library owns.
