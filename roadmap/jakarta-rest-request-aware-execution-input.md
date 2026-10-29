---
id: R1098
title: "A graphitron-jakarta-rest subgraph writes only what is its own"
status: In Review
bucket: feature
priority: 4
theme: runtime-connection
depends-on: []
created: 2026-10-23
last-updated: 2026-10-29
---

# A graphitron-jakarta-rest subgraph writes only what is its own

## Goal

A subgraph served by graphitron-jakarta-rest (the library that mounts a generated Graphitron schema as a GraphQL-over-HTTP endpoint in a Jakarta REST application) on the owned-connection engine (graphitron's own connection handling, which pins one connection per operation and mounts the caller's identity on it) writes only what is specific to that application: how a request is authenticated, which operations an endpoint allows, which identity is mounted, what a rejected identity answers, and which data source, jOOQ settings and extra instrumentation it runs with. Assembling the runtime and the engine, carrying the request's values to the execution input, unwrapping graphitron's exceptions, and serving GraphiQL and the SDL beside a custom path are graphitron's. The test is that the subgraph's code reads naturally to someone who knows Jakarta REST and the subgraph's domain, without knowing graphitron's generated internals.

The core part, supplying session identity per request as code instead of naming static methods in the POM, is its own item (R1120). This item is the Jakarta REST layer on top: the simple flow, where CDI and Jakarta REST are welcome and the consumer is free to use them.

Owned and motivated by fs-plattform: tilgangsstyring (!6441, 10.0.0-RC46) is the acceptance test; opptak is migrating to graphitron-jakarta-rest and the owned engine and is the second consumer.

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

What remains of tilgangsstyring's wiring. The library cannot name generated classes, so the adapter stays hand-written and small; it passes facade references, and the one engine line names no instrumentation.

```java
@Path("/graphql/{callingEnvironment}")
public class EnvironmentGraphqlResource {

    private static final OperationPolicy QUERIES_ONLY =
        OperationPolicy.queriesOnly(Response.Status.BAD_REQUEST);

    @Inject GraphqlEndpoint graphql;                        // the library's: POST, GET, GraphiQL, assets, SDL
    @Inject AuthorizationHeaderAuthenticator authenticator;

    @Path("")
    public GraphqlEndpoint.Request graphql(@PathParam("callingEnvironment") String environment,
                                           @Context HttpHeaders headers) {
        Supplier<ExecutionInput.Builder> input = () -> Graphitron.newOwnedExecutionInput(
            SessionIdentity.sessionFor(authenticator.login(headers, environment), environment));
        return "production".equals(environment)
            ? graphql.request(input)
            : graphql.request(input, QUERIES_ONLY);
    }
}

@ApplicationScoped
public class TilgangsstyringGraphitron extends AbstractGraphitronApplication {

    private final GraphitronRuntime runtime;
    private final GraphQLTelemetry telemetry;

    TilgangsstyringGraphitron(@Named("tilgangsstyring-graphql") DataSource dataSource, GraphQLTelemetry telemetry) {
        super(Graphitron::buildSchema, Graphitron::schemaSdl, Graphitron::sessionMountFailure);
        this.runtime = Graphitron.runtime(dataSource, SQLDialect.POSTGRES_17,
            new Settings().withRenderFormatted(true));
        this.telemetry = telemetry;
    }

    @Override
    public GraphQL.Builder engineBuilder() {
        return runtime.newGraphQL(schema(), telemetry.newInstrumentation());
    }

    @Override
    public Optional<WebApplicationException> rejectedIdentity(Throwable mountCause) {
        return AvvistIdentitet.som(mountCause);
    }
}
```

`SessionIdentity.sessionFor` is the consumer's own helper returning the generated `SessionState`, built with `SessionState.of(mount, unmount)` as R1120 shipped it (the sakila example's `SakilaSessions.of(claims)` is the same shape); the factory takes the state followed by only the schema's declared context arguments. The input supplier runs only when an operation executes, inside the library's guarded block, so the GraphiQL page, assets and SDL need no authentication, a `NotAuthorizedException` from `login` reaches the container unredacted, and any other fault is redacted as today. The supplier runs on the request thread and reads `headers` there; what it hands to graphitron is values, not the request, because R1120's mount callback may run on another thread. No value lives between beans for the duration of a request.

## Decisions

The five questions the Backlog text left open, answered:

* **The resource hands over the input through a sub-resource locator, and the handler gains the matching entry points.** `GraphqlEndpoint` is an application-scoped bean; `request(Supplier<ExecutionInput.Builder>)` and `request(Supplier<ExecutionInput.Builder>, OperationPolicy)` return a `GraphqlEndpoint.Request`, a plain object the container introspects as a sub-resource: `@POST`, the two `@GET` arms (JSON, and `text/html` for GraphiQL), `assets/{name}` and `schema`. That is the only shape that gives a custom path GraphiQL, assets and the SDL without the consumer repeating the arms, and it is how a path template stops shadowing them (the locator's own path is the base). Underneath, `GraphqlHttpHandler` gains `post(body, headers, input)`, `post(body, headers, policy, input)` (policy never `null`) and `get(query, operationName, variables, extensions, headers, input)`, mirroring the existing three; those delegate with the application's `newExecutionInput()`, and the unrestricted state is spelled by the overload, not a `null`, at every layer. The handler's reasoning survives unchanged: POST takes the policy the consumer selects, GET carries the specification's queries-only rule unconditionally, and no entry point runs a request with no rule attached. The two `request` overloads map to the handler's two new POST forms, so `null` is not accepted as a policy: "no policy" and "a policy that permits everything" differ (an unrestricted request is never pre-parsed), and an overload says which one the consumer means.
* **The owned engine is assembled in generated code, because the library cannot type the runtime.** The generated runtime gains `newGraphQL(GraphQLSchema, Instrumentation...)`, which chains graphitron's connection instrumentation first and the given ones after it; the one-argument form is unchanged. The facade gains `Graphitron.runtime(DataSource, SQLDialect, Settings)` beside the existing `runtime(DataSource, SQLDialect)`, and a no-argument `buildSchema()` so the adapter passes a method reference instead of `() -> Graphitron.buildSchema(b -> {})`. `engineBuilder()` stays the library's seam (the adapter overrides it in one line); no consumer needs to name `GraphitronConnectionInstrumentation` or `ChainedInstrumentation`, and the javadoc on `GraphitronApplication.engineBuilder()`, `GraphitronRuntime.newGraphQL` and the facade teaches the varargs form instead of the manual chain. The replace warning stays beside it: `.instrumentation(x)` on the returned builder still replaces graphitron's, so the footgun is narrowed to a call nobody needs to make, not removed. The alternative, a library constructor taking the runtime, is not expressible: its type is generated per subgraph.
* **The rejected-identity hook takes the mount's own exception, and recognising the generated exception reuses the walk the generated code already has.** The emitted `TenantConnections` carrier already answers `isMountFailure(Throwable)` in every build (always `false` without a mount) by walking the cause chain for `SessionMountException`. It gains a static `mountCause(Throwable)`, returning the mount's own exception as an `Optional<Throwable>` (empty without a mount); `isMountFailure` delegates to it, and the name is not `mountFailure`, which R1120's carrier already uses for the instance accessor of an operation's first rejected mount, and the facade exposes it as `Graphitron.sessionMountFailure(Throwable)`, emitted unconditionally so the adapter's constructor argument compiles in every build. `GraphitronApplication` gains two defaults, `mountCause(Throwable)` (empty) and `rejectedIdentity(Throwable mountCause)` (returning `Optional<WebApplicationException>`, empty); `AbstractGraphitronApplication` takes the facade method as a third constructor method reference and implements `mountCause` with it. The handler asks them before `clientFault`: a present mount failure goes to `rejectedIdentity`, and a present answer is thrown unredacted; otherwise `clientFault` is consulted as today. The precedence is structural, so an adapter overriding both cannot hide one behind the other. This follows how the schema supplier and SDL printer already reach generated code: method references in the adapter's constructor, the only generated-symbol references in hand-written code.
* **The built-in `/graphql` resource stays automatic with its toggle, and its seam becomes optional.** `GraphitronApplication.newExecutionInput()` becomes a default method that throws an `IllegalStateException` naming the two ways out: override it to serve the built-in endpoint, or return `false` from `builtInEndpointEnabled()`. A subgraph that serves only its custom path therefore writes no dead override, and one that forgets the toggle fails closed (a redacted `500` with the reason logged) rather than open. `GraphqlEngine`'s initialisation also logs one ERROR line at startup when the built-in endpoint is enabled and the application does not override `newExecutionInput()`, since both facts are known at boot. Making the built-in endpoint opt-in would change behaviour for every existing consumer for a convenience this item already delivers.
* **The parameterless `newExecutionInput()` stays as the built-in endpoint's seam.** It is not deprecated: a subgraph with no path parameters and nothing request-specific beyond what the adapter can reach keeps the simplest form, and this item does not take it away. What changes is that it is no longer the only way, and the javadoc stops recommending a `@RequestScoped` holder for it.

## Plan

### Library (`graphitron-jakarta-rest`, compiles at Java 17)

* `GraphqlEndpoint` and `GraphqlEndpoint.Request` as above. `Request` holds the supplier and the policy and delegates to `GraphqlHttpHandler`, `GraphiqlBundle` and the application, the way `GraphqlResource` does; `GraphqlResource` itself becomes the same delegation with `application::newExecutionInput`, so there is one set of arms.
* `GraphqlHttpHandler`: the two overloads taking the supplier. The supplier is called where `application.newExecutionInput()` is called today, inside the ordered two-arm guard, so client faults pass through, `clientFault` is consulted and everything else is redacted exactly as now.
* `GraphitronApplication`: the `mountCause` and `rejectedIdentity` defaults and the `newExecutionInput()` default. `AbstractGraphitronApplication`: the three-function constructor; the existing constructors stay.
* `AcceptLanguage.parse(HttpHeaders)`, returning `List<String>` (language tags, most preferred first), per RFC 9110: split on commas, order by q-value with header order as the tiebreak, drop `q=0` and `*`, empty when the header is absent or malformed. R1096's context argument is exactly this type.
* Javadoc on `GraphitronApplication`, `AbstractGraphitronApplication`, `GraphqlHttpHandler` and `GraphqlResource` rewritten around the supplier flow.

### Generated code (`graphitron`)

* The runtime's `newGraphQL(GraphQLSchema, Instrumentation...)`; the facade's `runtime(DataSource, SQLDialect, Settings)`, `buildSchema()` and `sessionMountFailure(Throwable)` (delegating to a new static `TenantConnections.mountCause`, which `isMountFailure` now delegates to). R1120's emission is on trunk, so this builds on its `SessionMountException` and carrier as they are.

### Adoption

* graphitron-sakila-example's application adapter and its custom-path test resource move to the new shape; its owned-connection execution stays green.
* First step, before the rest: a spike in the sakila example that a returned `GraphqlEndpoint.Request` serves POST, both GETs, assets and the SDL under Quarkus REST, with `@Context` and `@PathParam` arguments reaching the locator method. It is the one assumption the shape rests on; if a runtime refuses it, the handler overloads still stand and the endpoint falls back to a consumer-written resource that delegates to them.

## Constraints

* The library cannot name generated classes, and generated runtime code is emitted into the consumer rather than shipped as a jar.
* graphitron-jakarta-rest compiles at Java 17.
* CDI and Jakarta REST injection are natural here and stay available. Nothing in the flow requires a `@RequestScoped` bean to carry values from the resource to the input.
* Client faults keep today's contract: a `WebApplicationException` from the consumer reaches the container unredacted; other escapes are redacted to a reference-only 500, reference and text still overridable.
* Per-request values are read on the request thread, in the supplier, and travel as values. A request-scoped proxy captured by the session callback is out of contract, since the callback may run elsewhere.

## Requirements carried over

* R1096 reads the client's preferred languages as a declared context argument of type `List<String>`. The flow makes `Accept-Language` reachable where the input is built (the supplier holds the request's `HttpHeaders`), and `AcceptLanguage.parse(HttpHeaders)` produces the list. R1096's own documentation draft shows a `newExecutionInput(HttpHeaders)` override; it moves to the supplier form: `Graphitron.newOwnedExecutionInput(session, AcceptLanguage.parse(headers))`.

## Breaking change

None in behaviour: the parameterless `newExecutionInput()`, `clientFault`, the three existing `GraphqlHttpHandler` entry points and every `AbstractGraphitronApplication` constructor keep working, and the generated additions are overloads. The `@RequestScoped`-holder flow still works; its javadoc stops recommending it. One source edge: a call that passes a literal `null` as the policy, `handler.post(body, headers, null)`, is now ambiguous between the policy and supplier overloads and stops compiling; the documented unrestricted form `handler.post(body, headers)` is unaffected, and the javadoc says to write that. Known consumers: tilgangsstyring (!6441), opptak (migrating), graphitron-sakila-example.

## Tests

The library has no test tree of its own, so the tests live in graphitron-sakila-example's `app` package beside `MountedEndpointTest` and `PolicyMountedGraphqlResource`, which already run it under Quarkus.

* Endpoint: a custom-path resource returning `GraphqlEndpoint.Request` serves execution, GraphiQL, assets and the SDL; a templated path no longer needs its own asset and SDL arms; `request(input, policy)` rejects a mutation with the policy's status before the supplier runs, and `request(input)` runs it.
* Per-request values: the supplier's values reach the input without a scoped bean, including under concurrent requests carrying different values; the supplier is not called for GraphiQL, assets or the SDL.
* Faults: a `WebApplicationException` from the supplier reaches the container unredacted; any other escape is redacted as today; a rejected mount reaches `rejectedIdentity` with the mount's own exception, on POST and on GET, and its answer is thrown unredacted; an adapter that overrides both `rejectedIdentity` and `clientFault` gets the former for a mount failure and the latter for anything else; a build without session identity still compiles the three-argument constructor and `mountCause` answers empty.
* Built-in seam: with `newExecutionInput()` not overridden and the toggle left on, `/graphql` answers the redacted `500` and logs the reason, and startup logs the one ERROR line; with the toggle off it answers `404`. A returned `GraphqlEndpoint.Request` keeps serving with `builtInEndpointEnabled()` false, and `graphiqlEnabled()` still gates its GraphiQL page and assets.
* `AcceptLanguage.parse`: q ordering, ties, `q=0`, `*`, malformed, absent.
* Engine assembly: `newGraphQL(schema, extra)` runs the extra instrumentation after graphitron's and connection handling still works, including the per-mutation-field transaction scope; `Settings` reach graphitron's SQL and the mount.
* Consumer acceptance: tilgangsstyring !6441 rewritten to this shape keeps only the "application's own" list above, and its suite stays green (856 tests on RC46 at the time of writing). The rewrite is done in fs-plattform against a snapshot and is the review evidence for Done.

## Documentation

`GraphitronApplication`, `AbstractGraphitronApplication`, `GraphqlHttpHandler` and the generated `newGraphQL` javadoc stop recommending a `@RequestScoped` holder and the manual instrumentation chain. The serving how-to (R530) and the manual catch-up (R1108) use the consumer view above.

## Relation to other items

* **R1120** (`owned-session-state-supplied-per-request`): the session-identity callback this flow carries. Its implementation is on trunk and in a review rework round; `sessionMountFailure` builds on its emitted `SessionMountException`.
* **R1101** (Spec): the per-mutation-field transaction scope is a graphitron instrumentation; the new `newGraphQL` overload must keep it working under an extra instrumentation.
* **R1129** (Backlog): the mutation hook registered on the runtime (`withMutationHook`) would chain after the `runtime(dataSource, dialect, Settings)` factory in the adapter's runtime line; no conflict.
* **R1097** (Done): added `clientFault` and documented the instrumentation footgun; this item removes the footgun and layers `rejectedIdentity` over the hook.
* **R1102** (Spec): `$session` by member; opptak needs it alongside this item.
* **R1108** (Backlog) and **R530** (Backlog): the manual describes the shape this item lands.
* **R1096** (Spec): consumes `AcceptLanguage.parse` and the supplier flow for `Accept-Language`.

## Delivery notes

Shipped in one change on the branch. Where it differs from the plan above, and what the reviewer should look at:

* **The one source edge** is the literal-`null` ambiguity named under Breaking change, which the plan had not seen: adding `post(body, headers, Supplier)` beside the existing `post(body, headers, OperationPolicy)` is what makes it.
* **`GraphqlResource` is now a locator**, as the plan intended: one `@Path("")` method behind the built-in-endpoint gate returns the same `GraphqlEndpoint.Request` a consumer's resource does, so there is one set of arms. The existing conformance, mounted-endpoint and overlap suites run through it unchanged.
* **The startup check is best-effort.** A container proxy declares every method itself, so `GraphqlEngine` looks past classes named like a generated proxy or subclass (`_ClientProxy`, `_Subclass`, `$$`, `$Proxy`) to the application's own class, and counts an unrecognised type as overriding, so the ERROR line can be missing but never wrong. The default method's refusal is what answers a request.
* **What the tests show and do not.** `EndpointMountedTest` runs the locator under Quarkus REST, the only JAX-RS runtime in the repo; no other runtime was tried. The default seam's refusal and the startup decision are unit-tested (`BuiltInSeamTest`), but the redacted `500` for a built-in endpoint with no seam, and the startup log line itself, are not asserted end to end, because the test adapter overrides the seam. The jOOQ `Settings` reaching the pinned connection is pinned in `ConnectionRuntimeClassGeneratorTest`, not through the sakila example, whose adapter passes default settings. The library has no test tree, so its tests sit in `graphitron-sakila-example`, the library's own two in package `no.sikt.graphitron.jakarta.rest` there so they can see its package-private decision.
* **Not done here:** the consumer acceptance, tilgangsstyring !6441 rewritten to this shape with its suite green. It is done in fs-plattform against a snapshot and is the evidence the plan names for Done.

## Other solutions we've considered

* **A `newExecutionInput(HttpHeaders)` overload on the SPI.** Adds a second way to reach request data beside the no-argument seam, and leaves path parameters and values the resource already computed to a `@RequestScoped` holder.
* **A `ContainerRequestFilter` that stores the request for the adapter to read.** Still ambient state between beans, now hidden in a filter.
* **A per-call `Supplier` on the handler only, with every consumer writing the resource arms.** This is the underlying mechanism and stays public, but alone it leaves GraphiQL, assets and the SDL for the consumer to copy beside a custom path.
* **A generated adapter, or a library constructor taking the runtime.** The first couples every generated codebase to a graphitron-jakarta-rest version; the second cannot be written, since the runtime's type is generated per subgraph.
