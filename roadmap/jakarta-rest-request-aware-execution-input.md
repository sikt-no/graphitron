---
id: R1098
title: "A graphitron-jakarta-rest subgraph writes only what is its own"
status: Backlog
bucket: feature
priority: 4
theme: runtime-connection
depends-on: [owned-session-state-supplied-per-request]
created: 2026-10-23
last-updated: 2026-10-28
---

# A graphitron-jakarta-rest subgraph writes only what is its own

## Goal

A subgraph served by graphitron-jakarta-rest on the owned-connection engine writes only what is specific to that application: how a request is authenticated, which operations an endpoint allows, which identity is mounted, what a rejected identity answers, and which data source, jOOQ settings and extra instrumentation it runs with. Assembling the runtime and the engine, carrying the request's values to the execution input, unwrapping graphitron's exceptions, and serving GraphiQL and the SDL beside a custom path are graphitron's. The test is that the subgraph's code reads naturally to someone who knows Jakarta REST and the subgraph's domain, without knowing graphitron's generated internals.

The core part, supplying session identity per request as code instead of naming static methods in the POM, is its own item (R1120). This item is the Jakarta REST layer on top: the simple flow, where CDI and Jakarta REST are welcome and the consumer is free to use them.

Owned and motivated by fs-plattform: tilgangsstyring (!6441, 10.0.0-RC46) is the acceptance test; opptak is migrating to graphitron-jakarta-rest and the owned engine and is the second consumer.

This replaces the earlier plan for R1098, a `newExecutionInput(HttpHeaders)` overload. That overload would have added a second way to reach request data beside the no-argument seam, and would still have left path parameters, and values the resource already computed, to a `@RequestScoped` holder.

## Problem, as it stands

The library owns part of the wiring (it discovers its endpoint, caches the engine, calls the `GraphitronApplication` seam) and leaves the rest to the consumer through workarounds. In !6441:

* **A `@RequestScoped` holder carries values from resource to adapter.** The resource authenticates and stores the result in `AuthenticatedSession`; the `@ApplicationScoped` adapter reads it back in the parameterless `newExecutionInput()`. The holder exists only because the library calls the seam without the request, and the library's own javadoc recommends the pattern.
* **The runtime is assembled by hand.** `new GraphitronRuntime(new GraphitronRuntime.Source(dataSource, SQLDialect.POSTGRES_17, new Settings().withRenderFormatted(true)), Map.of())`, because `Graphitron.runtime(dataSource, dialect)` takes no `Settings`.
* **graphitron's instrumentation is chained by hand.** `GraphQL.newGraphQL(schema()).instrumentation(new ChainedInstrumentation(List.of(new GraphitronConnectionInstrumentation(runtime), telemetry.newInstrumentation())))`, with a comment explaining why: `instrumentation(...)` replaces, so adding telemetry the obvious way removes connection handling. R1097 documented this footgun and named removing it as a successor.
* **graphitron's exception is unwrapped to reach the application's.** `thrown instanceof SessionMountException mount ? AvvistIdentitet.som(mount.getCause()) : Optional.empty()`. The decision (SQLState to status) is the application's; recognising and unwrapping the generated exception is not.
* **GraphiQL and asset routes are copied into the custom resource.** `/graphql/{callingEnvironment}` shadows the built-in resource's sub-paths, so the subgraph repeats the GraphiQL and asset arms and turns the discovered built-in endpoint off with `builtInEndpointEnabled() = false`.
* **Schema assembly boilerplate** (`super(() -> Graphitron.buildSchema(b -> {}))`) that every adapter repeats identically.

### What is the application's own, and stays

* Authenticating a request from its `Authorization` and sudo headers and the calling environment in the path, answering 401 or 400 before execution.
* The endpoint `/graphql/{callingEnvironment}`, and the policy that only `production` runs mutations.
* The session identity itself (`SessionIdentity`), supplied as code per R1120.
* Mapping a rejected identity to a status: SQLState `TS001` and `TS015` to 401, `TS014` to 400 with a body naming the header.
* The data source (`@Named("tilgangsstyring-graphql")`), jOOQ `Settings`, and the OpenTelemetry instrumentation.

## Consumer view (first-client draft)

Illustrative; the mechanism is open below. The point is what remains.

```java
@Path("/graphql/{callingEnvironment}")
public class EnvironmentGraphqlResource {

    @Inject GraphqlEndpoint graphql;                        // the library's: POST, GET, GraphiQL, assets, SDL
    @Inject AuthorizationHeaderAuthenticator authenticator;

    @Path("")
    public GraphqlEndpoint.Request graphql(@PathParam("callingEnvironment") String environment,
                                           @Context HttpHeaders headers) {
        return graphql.request(
            () -> Graphitron.newOwnedExecutionInput(
                SessionIdentity.forCaller(authenticator.login(headers, environment), environment)),
            "production".equals(environment) ? null : QUERIES_ONLY);
    }
}

@ApplicationScoped
public class TilgangsstyringGraphitron extends GraphitronJakartaApplication {

    TilgangsstyringGraphitron(@Named("tilgangsstyring-graphql") DataSource dataSource, GraphQLTelemetry telemetry) {
        super(Graphitron.runtime(dataSource, SQLDialect.POSTGRES_17)
                  .withSettings(new Settings().withRenderFormatted(true)),
              telemetry.newInstrumentation());
    }

    @Override
    public Optional<WebApplicationException> rejectedIdentity(Throwable cause) {
        return AvvistIdentitet.som(cause);
    }
}
```

The input supplier is evaluated only when an operation executes, inside the library's guarded block, so the GraphiQL page and assets need no authentication, a `NotAuthorizedException` from `login` reaches the container unredacted, and any other fault is redacted as today. No value lives between beans for the duration of a request.

## Constraints

* The library cannot name generated classes, and generated runtime code is emitted into the consumer rather than shipped as a jar.
* graphitron-jakarta-rest compiles at Java 17.
* CDI and Jakarta REST injection are natural here and stay available. Nothing in the flow should require a `@RequestScoped` bean to carry values from the resource to the input.
* Client faults keep today's contract: a `WebApplicationException` from the consumer reaches the container unredacted; other escapes are redacted to a reference-only 500, reference and text still overridable.

## Open questions for the spec round

* **How the resource hands over the input.** A per-call `Supplier<ExecutionInput.Builder>` on `GraphqlHttpHandler`'s entry points, a sub-resource locator returning a configured endpoint (which also serves GraphiQL, assets and SDL under any path), or both. The handler documents why it has exactly three entry points today; the shape chosen should keep that reasoning.
* **Where the owned engine is assembled.** The library taking the runtime plus extra instrumentations and chaining graphitron's first, versus a generated `runtime.newGraphQL(schema, Instrumentation...)` the adapter calls. Either way the consumer never names `GraphitronConnectionInstrumentation` or `ChainedInstrumentation`. A `Settings` overload or `withSettings` on the runtime belongs with whichever is chosen.
* **The rejected-identity hook.** A dedicated hook receiving the mount's own exception, versus keeping `clientFault(Throwable)` and having the library unwrap `SessionMountException` before asking. The library cannot name the generated exception, so it needs a structural way to recognise it.
* **Discovery of the built-in `/graphql` resource.** Whether it stays automatic (with the 404 toggle) or becomes opt-in once a custom path gets the full endpoint from the library.
* **The parameterless `newExecutionInput()`.** Whether it stays as the built-in endpoint's seam, becomes a default, or goes.

## Requirements carried over

* R1096 reads the client's preferred languages as a declared context argument. The flow must make `Accept-Language` reachable where the input is built; `AcceptLanguage.parse(HttpHeaders)` from the earlier plan stays in scope as a library utility (RFC 9110: split on commas, sort by q-value with header order as tiebreak, drop `q=0` and `*`).

## Breaking change

Changes to `GraphitronApplication`, `AbstractGraphitronApplication` and `GraphqlHttpHandler` follow the breaking-change convention, or keep the current forms for one release. Known consumers: tilgangsstyring (!6441), opptak (migrating), graphitron-sakila-example.

## Tests

* Consumer acceptance: !6441 rewritten to the new shape keeps only the "application's own" list above, and its full suite stays green (856 tests on RC46 at the time of writing). The rewrite is done in fs-plattform against a snapshot and is the review evidence for Done.
* graphitron-jakarta-rest: a custom-path resource gets execution, GraphiQL, assets and SDL; per-request values reach the input without a scoped bean, including under concurrent requests; a rejected mount reaches the application's hook with the mount's own exception; client-fault and redaction behaviour unchanged; `AcceptLanguage.parse` (q ordering, ties, `q=0`, `*`, malformed, absent).
* Engine assembly: an extra instrumentation runs after graphitron's and connection handling still works; `Settings` reach graphitron's SQL and the mount.
* graphitron-sakila-example's server module adopts the new shape.

## Documentation

`GraphitronApplication`, `AbstractGraphitronApplication` and `GraphqlHttpHandler` javadoc stop recommending a `@RequestScoped` holder and the manual instrumentation chain. The serving how-to and R1108 use the consumer view above.

## Relation to other items

* **R1120** (`owned-session-state-supplied-per-request`): the core item this depends on.
* **R1097** (Done): added `clientFault` and documented the instrumentation footgun; this item removes the footgun and may narrow the hook.
* **R1102** (Ready): `$session` by member; opptak needs it alongside this item.
* **R1108** (Backlog): the manual describes the shape this item lands.
* **R1096** (Spec): consumes the request-value path for `Accept-Language`.
