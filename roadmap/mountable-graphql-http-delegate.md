---
id: R629
title: "Mountable GraphQL-over-HTTP delegate with an explicit operation policy"
status: Spec
bucket: architecture
priority: 3
theme: runtime-connection
depends-on: []
created: 2026-08-11
last-updated: 2026-08-11
---

# Mountable GraphQL-over-HTTP delegate with an explicit operation policy

## In one paragraph

`graphitron-jakarta-rest` serves exactly one endpoint shape: `GraphqlResource` is annotated
`@Path("/graphql")`, and its whole execution pipeline (JSON parsing, the GET-mutation guard, the
`GraphitronApplication.newExecutionInput()` seam, engine execution, legacy/modern content
negotiation, and the 400/422 watershed in `statusFor`) is private to that class. A consumer that
must serve GraphQL on a different path, in particular a path carrying a template parameter, or that
must apply an operation-type policy that HTTP verb semantics do not express, has no seam: the only
routes are copying the pipeline or subclassing a JAX-RS resource and relying on annotation
inheritance, which is too brittle to build a trust boundary on. This item extracts the pipeline into
a public delegate component (working name `GraphqlHttpHandler`) that any consumer resource can
mount at any path, generalises the GET-mutation guard into an explicit `OperationPolicy` parameter
(allowed operation types plus the rejection status and message), and leaves `GraphqlResource` as a
thin shell over the delegate with an observably unchanged contract.

## The consumer case that drives it

Sikt's `tilgangsstyring-app` (a Quarkus subgraph in the `fs-plattform` repository) runs a
hand-rolled GraphQL-over-HTTP layer today: its own JAX-RS resource plus a `MessageBodyReader`. It
wants to drop that in favour of this library, and needs two things the library does not offer.

1. **Its own path, with a path parameter.** One deployed instance serves every environment and
   exposes `/graphql/{callingEnvironment}` (`/graphql/test`, `/graphql/production`, ...). The
   environment is bound *structurally* to the endpoint: routing decides trust, not a claim inside a
   token. `@Path("/graphql")` is hard-coded on `GraphqlResource`, and subclassing a JAX-RS resource
   to relocate it (annotation inheritance) was rejected as too fragile.

2. **An operation policy independent of the HTTP verb.** The consumer's resource method must be able
   to require "query operations only" *for POST as well*: on non-production environments a mutation
   or subscription must be rejected with **400** and a request-error body (for example
   "Mutation operations are not supported on this endpoint") **before execution**. Today that guard
   exists only inside `GraphqlResource.execute`, welded to the `isGet` flag and to 405 semantics
   (which are spec-mandated for GET and must survive untouched).

The consumer authenticates per request in its own resource method: it reads the `Authorization`
header and the path parameter, authenticates, and populates a `@RequestScoped` claims holder, all
*before* execution. The no-arg `GraphitronApplication.newExecutionInput()` seam therefore does
**not** change: ordering is already correct because the consumer's resource method runs before it
delegates, and the adapter reads the holder. The `WebApplicationException` passthrough in the
current `execute` (the consumer's seam throwing `NotAuthorizedException` for 401, and so on) is a
property this consumer actively relies on: it must be preserved, and documented more prominently
than it is today.

## What exists today

All references are to
`graphitron-jakarta-rest/src/main/java/no/sikt/graphitron/jakarta/rest/`.

- **`GraphqlResource`** carries the entire surface: the class-level `@Path("/graphql")`, the
  `post` / `get` / `graphiql` / `asset` / `schema` resource methods, the shared static `JSONB`
  binder and `GRAPHIQL_HTML` resource, the public `GRAPHQL_RESPONSE_JSON` constant, and the private
  helpers `execute`, `statusFor`, `operationType`, `requestError`, `serialise`, `errorBody`,
  `responseType`, `isLegacy`, `parseMapParam`, `assetMediaType`, `loadResource`. The operation guard
  lives in `execute` behind `if (isGet)` and hard-codes 405 plus `Allow: POST`.
- **`GraphqlEngine`** caches `application.engineBuilder().build()` at application scope. Unchanged by
  this item; the delegate injects it exactly as the resource does now.
- **`GraphitronApplication`** is the consumer-implemented SPI: `schema()`, `newExecutionInput()`,
  and the defaulted `engineBuilder()` / `graphiqlEnabled()`. Its javadoc states the vendor-neutral
  constraint (no Quarkus or RESTEasy types, no dependency outside the parent pom's pinned set) that
  binds everything below.
- **`AbstractGraphitronApplication`** is the boilerplate-removing base with the cached schema.
  Unchanged.
- **`GraphqlRequest`** is the request-body record. Unchanged in shape, but it stops being an
  implementation detail of one resource: it becomes part of the delegate's public signature, so its
  javadoc pointer at `GraphqlResource` moves to the delegate.
- **`META-INF/beans.xml`** marks the jar for annotated bean discovery; its comment enumerates what
  the container is expected to find and gains the delegate.

## Design

### 1. `GraphqlHttpHandler`, the mountable delegate

A public, `@ApplicationScoped` CDI bean holding the whole HTTP behaviour, with every JAX-RS input
passed as a parameter rather than injected with `@Context`. It injects `GraphqlEngine` and
`GraphitronApplication` exactly as the resource does now. It is stateless, so application scope is
correct, and it names no vendor type: `Response`, `HttpHeaders`, `UriInfo` are all
`jakarta.ws.rs.core`.

```java
public Response post(String body, HttpHeaders headers);
public Response post(String body, HttpHeaders headers, OperationPolicy policy);
public Response get(String query, String operationName, String variables, String extensions,
                    HttpHeaders headers);
public Response execute(GraphqlRequest request, HttpHeaders headers, OperationPolicy policy);
public Response graphiql(UriInfo uriInfo);
public Response asset(String name);
public String schema();
```

The delegate covers the **whole** surface, not just the execution path, so a consumer on its own
path can offer POST, GET-query, the GraphiQL page, the asset stream, and `/schema` without copying
anything. `execute(GraphqlRequest, ...)` is the entry point for a consumer that already parsed the
body itself (the case `tilgangsstyring-app` is migrating away from, but a real one for anyone with a
custom body shape).

GET takes no policy overload. The spec's queries-only-with-405 rule is the only meaningful policy
for GET, and letting a consumer weaken it would let a consumer break conformance; a consumer that
wants GET disabled simply does not declare a GET method.

### 2. `OperationPolicy`, the explicit guard parameter

A public record in the same package:

```java
public record OperationPolicy(Set<OperationDefinition.Operation> allowed,
                              int rejectionStatus,
                              Function<OperationDefinition.Operation, String> rejectionMessage,
                              Map<String, String> rejectionHeaders) {
    public static OperationPolicy unrestricted();
    public static OperationPolicy queriesOnly(int rejectionStatus, String rejectionMessage);
    public static OperationPolicy allowing(Set<OperationDefinition.Operation> allowed,
                                           int rejectionStatus,
                                           Function<OperationDefinition.Operation, String> message,
                                           Map<String, String> headers);
}
```

The compact constructor defensively copies both collections (`Set.copyOf`, `Map.copyOf`), rejects an
empty `allowed` set, and rejects a `rejectionStatus` outside 4xx. The message is a function of the
rejected operation because the GET policy's message is already operation-dependent
("GraphQL mutation operations must use POST, not GET."); `queriesOnly` wraps a fixed string for the
common consumer case. `rejectionHeaders` exists solely so the GET policy can keep emitting
`Allow: POST`; consumers normally pass an empty map.

The library owns one internal constant, the GET policy: `allowed = {QUERY}`, status 405, message
`op -> "GraphQL " + op.name().toLowerCase() + " operations must use POST, not GET."`, headers
`{Allow: POST}`. That is today's behaviour expressed as data.

**Rejection is not legacy-downgraded.** Today's GET guard returns 405 regardless of the negotiated
media type (only the body's media type varies), because it is an HTTP-level rule rather than a
GraphQL request error. The generalised policy keeps that: a rejection returns `rejectionStatus` for
both modern and legacy clients, with the error body serialised in the negotiated type. This is what
the consumer needs, since 400 is the required answer for their non-production endpoints regardless
of what the caller sends in `Accept`.

### 3. `unrestricted()` must be a genuine no-op

The pre-parse the policy needs (`Parser.parse` in the current `operationType`) does not happen on
POST today. If the extracted pipeline ran it unconditionally, a syntactically invalid document over
POST would start returning the resource's own `"The GraphQL document could not be parsed."`
request-error body instead of graphql-java's `InvalidSyntax` result. Both are 400, so the existing
conformance test still passes, but the response body would change for every consumer. The pipeline
therefore skips the parse entirely when the policy allows every operation type
(`allowed.containsAll(EnumSet.allOf(Operation.class))`), which is exactly the built-in POST path.
Under a restrictive policy the parse does run, and a parse failure yields 400 with the resource's
request-error body, which is what the consumer asked for.

### 4. Operation resolution and the smuggling question

`operationType` (the resolution helper, moved into the delegate) keeps its current semantics: parse
the document, pick the operation named by `operationName` when one is given, otherwise the first
operation, and return `null` when nothing resolves. `null` falls through to the engine, unchanged.
That is safe against smuggling, and the behaviour of graphql-java 25.0 was verified directly rather
than assumed:

- `query A { a } mutation B { b }` with `operationName: "B"`: the mutation **executes**. The policy
  must and does catch this, because resolution honours `operationName`.
- The same document with no `operationName`: graphql-java returns
  `UnknownOperationException: Must provide operation name if query contains multiple operations.`
  as a request error with no data, so the endpoint answers 422 and the mutation never runs. Picking
  the first operation for the policy check cannot let anything through here.
- `operationName` matching no operation: `UnknownOperationException: Unknown operation named 'X'.`,
  again 422 with no data.
- Duplicate operation names across a query and a mutation: the `DuplicateOperationName` validation
  rule fires before execution, 422.
- A document with only fragments: no operation resolves, validation rejects it, 422.

So "resolved operation" is the right unit for the policy, and the unresolvable case genuinely
belongs to the engine. This reasoning is worth keeping in the delegate's javadoc, since it is the
argument for why the guard is not "reject if any definition in the document is a mutation".

### 5. `GraphqlResource` becomes a thin shell

Same class, same `@Path("/graphql")`, same five methods with the same annotations, each one line
delegating to the injected handler with the built-in policies. The public `GRAPHQL_RESPONSE_JSON`
constant is defined on the handler and re-exported from `GraphqlResource` so existing
`@Produces({GraphqlResource.GRAPHQL_RESPONSE_JSON, ...})` in consumer code keeps compiling. The
class javadoc keeps describing the endpoint's spec behaviour and gains a pointer to the delegate for
consumers who need a different mount point.

### 6. Turning the built-in endpoint off

This is the security-relevant half of "my own path". A consumer that mounts
`/graphql/{callingEnvironment}` must not also be serving the library's own `/graphql`, where no
environment is bound and where their authentication code never runs. Bean discovery registers
`GraphqlResource` automatically today, so the consumer cannot simply not use it.

Add a defaulted SPI toggle alongside `graphiqlEnabled()`:

```java
default boolean defaultEndpointEnabled() { return true; }
```

`GraphqlResource` consults it first in all five methods and returns 404 when it is false, exactly as
`graphiqlEnabled()` already gates the page and the asset stream. Vendor-neutral, no new dependency,
and it rides the seam the library already uses for this kind of toggle. The javadoc also names the
registration-level alternative that needs no library support (declaring a
`jakarta.ws.rs.core.Application` subclass with an explicit `getClasses()`), so a consumer who
prefers to control registration rather than behaviour has a documented route.

### 7. GraphiQL under a templated path

`graphiql()` rewrites `{{ASSET_BASE}}` from `uriInfo.getAbsolutePath()`. `getAbsolutePath()` returns
the concrete request URI with template parameters already resolved, so a page served from
`/graphql/test` gets `/graphql/test/assets/`, and the consumer's own `assets/{name}` sub-path serves
it. Nothing in the rewrite needs to change; what it needs is a test that pins it under a templated
mount, and a javadoc sentence stating the requirement it puts on the consumer: if you serve the
page, serve the asset stream at `assets/{name}` relative to the same path, or the bundle 404s.

One JAX-RS matching note for the consumer's javadoc: with the built-in resource still enabled,
`/graphql/{env}` and the built-in `/graphql/schema` overlap, and JAX-RS resolves that in favour of
the literal path. Combined with a `{env}` value that is validated by the consumer's own method, this
is benign, but disabling the built-in endpoint (previous section) removes the question entirely.

### 8. What does not change

- `GraphitronApplication.newExecutionInput()` stays no-arg. Ordering is the consumer's resource
  method's business, and it already runs first.
- The `WebApplicationException` passthrough keeps its ordered-first catch arm, moved verbatim into
  the delegate. Its javadoc is promoted from an inline comment into the delegate's and the SPI
  method's javadoc, because it is the documented way for a consumer's authentication code (or seam)
  to return 401/403.
- The redaction guard, the correlation id, `statusFor`, `isLegacy`, and every status code the
  conformance suite pins.

## Implementation

New files in `graphitron-jakarta-rest/src/main/java/no/sikt/graphitron/jakarta/rest/`:

- `GraphqlHttpHandler.java`: the delegate above, holding everything moved out of `GraphqlResource`
  (the `JSONB` binder, `GRAPHIQL_HTML`, `execute`, `statusFor`, `operationType`, `requestError`,
  `serialise`, `errorBody`, `responseType`, `isLegacy`, `parseMapParam`, `assetMediaType`,
  `loadResource`, and the `GRAPHQL_RESPONSE_JSON` constant). Package-level javadoc quality, with the
  consumer example below. Java 17 syntax only, per the module's pinned `default-compile`.
- `OperationPolicy.java`: the record and its factories, with the internal GET policy exposed to the
  package.

Edited:

- `GraphqlResource.java`: reduced to the five annotated methods, the `defaultEndpointEnabled()` gate,
  and the re-exported constant. Javadoc keeps the spec narrative and points at the delegate.
- `GraphitronApplication.java`: add `defaultEndpointEnabled()`; extend `newExecutionInput()`'s
  javadoc with the `WebApplicationException` contract.
- `GraphqlRequest.java`: javadoc pointer moves from `GraphqlResource` to the delegate.
- `META-INF/beans.xml`: comment names the delegate.
- `docs/architecture/reference/modules.adoc`: the module row gains the delegate and the policy in its
  one-line surface description.
- `graphitron-sakila-example/README.md`: the app section says "everything HTTP-shaped comes from the
  library"; add one sentence that a consumer needing its own path or a stricter operation policy
  mounts the delegate instead of the built-in resource.
- `graphitron-sakila-example/.../FaultInjectingGraphitronApplication.java`: its javadoc links
  `GraphqlResource#execute`, which moves. Repoint at the delegate.

`AbstractGraphitronApplication` and `GraphqlEngine` are untouched.

## Consumer example (delegate javadoc)

The delegate's javadoc carries this sketch, which is the consumer case reduced to its shape:

```java
@Path("/graphql/{callingEnvironment}")
public class EnvironmentGraphqlResource {

    private static final OperationPolicy QUERIES_ONLY = OperationPolicy.queriesOnly(
        400, "Mutation operations are not supported on this endpoint.");

    @Inject GraphqlHttpHandler handler;
    @Inject CallerClaims claims;          // @RequestScoped, read by this app's SPI adapter

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({GraphqlHttpHandler.GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
    public Response post(@PathParam("callingEnvironment") String environment,
                         String body,
                         @Context HttpHeaders headers) {
        Environment env = Environment.parse(environment);              // 404 on an unknown value
        claims.populate(authenticate(headers, env));                   // 401 via NotAuthorizedException
        return handler.post(body, headers, env.isProduction()
            ? OperationPolicy.unrestricted()
            : QUERIES_ONLY);
    }

    @GET
    @Path("assets/{name}")
    public Response asset(@PathParam("name") String name) {
        return handler.asset(name);
    }
}
```

Two properties the javadoc states explicitly around it: the resource method runs to completion
before the delegate touches the seam, so a `@RequestScoped` holder populated here is visible to
`newExecutionInput()`; and a `WebApplicationException` thrown either here or from the seam reaches
the container unredacted, which is how 401 and 403 are produced.

## Test plan

The module carries no `@Test` classes by design; coverage lives in `graphitron-sakila-example`'s
execution tier (`@QuarkusTest`, `@ExecutionTier`), which is where the library is exercised through a
real container. This item keeps that placement.

**New test fixture (test sources).** A `PolicyMountedGraphqlResource` at
`/env/{callingEnvironment}/graphql` delegating to the handler: POST with `OperationPolicy.queriesOnly(400, ...)`
for a non-production path value and `OperationPolicy.unrestricted()` for the production one, plus
`assets/{name}`, the GraphiQL page, and `/schema`. It lives in test sources for the same reason
`FaultInjectingGraphitronApplication` does: the shipped reference app stays a pristine
copy-paste template.

**New cases** (a `MountedEndpointPolicyTest`, or a new section of the conformance suite):

1. Mutation over POST on a queries-only endpoint: 400, body is the policy message, no `data` member.
   The document names a field that does not exist on `Mutation`, so an engine that saw it would
   answer 422 with a validation error; 400 with the policy message is therefore positive evidence
   that the guard ran before execution.
2. Subscription over POST on the same endpoint: 400 with the policy message. The reference schema
   declares no `Subscription` type at all, so again only a pre-execution guard can produce this.
3. Multi-operation document where `operationName` selects the mutation: 400. The smuggling case.
4. Same document where `operationName` selects the query: 200 with that operation's data and no
   trace of the mutation's selection set.
5. Multi-operation document with no `operationName` on the queries-only endpoint: falls through to
   the engine, 422 (`Must provide operation name ...`), not the policy message.
6. `operationName` matching no operation: falls through, 422.
7. Unparseable document on the queries-only endpoint: 400 with the "could not be parsed"
   request-error body.
8. A mutation over POST on the unrestricted mounted endpoint: 200. The policy is a per-call
   argument, not a global mode.
9. Legacy `Accept: application/json` on a policy rejection: still 400, error body in
   `application/json`. Pins the deliberate non-downgrade.
10. GraphiQL page from the templated mount: `{{ASSET_BASE}}` resolves to
    `/env/test/graphql/assets/`, and `GET /env/test/graphql/assets/graphiql.js` streams 200
    `text/javascript`. Pins the placeholder rewrite under a path template.
11. `/schema` under the templated mount returns SDL.
12. Ordering: the mounted resource populates a `@RequestScoped` holder from the path parameter
    before delegating; the test-scoped adapter records what it saw in `newExecutionInput()`, and the
    resource echoes it back as a response header. Asserts the seam observed the value the resource
    set, which is the property the consumer's trust model rests on.
13. A `WebApplicationException` thrown by the mounted resource method before delegating (missing
    `Authorization` header) surfaces as 401, unredacted.
14. `defaultEndpointEnabled() == false`: all five built-in `/graphql` routes answer 404 while the
    mounted endpoint keeps working. Realised by the test adapter reading a request header, following
    the `FAULT_HEADER` precedent in `FaultInjectingGraphitronApplication`, so no second Quarkus boot
    is needed; a `@TestProfile` is the fallback if the reviewer prefers a static toggle.

**Regression, unchanged expectations.** Every case in `GraphQLOverHttpConformanceTest` and
`GraphqlResourceSmokeTest` passes with no edit to its expectations. That is the observable contract
for "`GraphqlResource` is now a shell": GET-to-mutation stays 405 with `Allow: POST`, the 400/422
watershed holds, legacy stays 200, the redaction and passthrough cases hold, and the GraphiQL page
and assets still resolve under `/graphql`.

## Non-goals

- No change to `newExecutionInput()`'s arity or to how a consumer seeds auth.
- No authentication, authorisation, or environment model in the library. The consumer owns all of
  it; the library owns only where the guard sits in the pipeline.
- No subscription transport (WebSocket, SSE). A subscription operation is something the policy can
  reject, not something the library serves.
- No config-framework binding for the new toggle, for the same reason `graphiqlEnabled()` has none.
- Not a substitute for database-level read-only enforcement (R460). This policy is an HTTP-level
  operation-type gate; it says nothing about what SQL a permitted operation can issue.

## Open decisions

1. **Name.** `GraphqlHttpHandler` is the working name. `GraphqlEndpoint` is the runner-up and reads
   better next to `GraphqlResource` and `GraphqlEngine`; "handler" is the more conventional word for
   a delegate that returns `Response`. Decide at Spec sign-off, before the name is public API.
2. **`OperationPolicy` shape.** The flat four-component record is the proposal. The alternatives are
   a nested `Rejection(status, headers, message)` value (fewer components, more types) and an
   interface with a single `check(Operation)` method returning an optional rejection (most
   extensible, most ceremony). A record component that is a `Function` makes `equals` structurally
   meaningless, which is worth an explicit "this is a configuration value, not a value object" note
   either way.
3. **Toggle name and granularity.** `defaultEndpointEnabled()` versus `builtInEndpointEnabled()`,
   and whether one toggle for all five built-in routes is the right granularity or whether the SDL
   endpoint should be separately gateable.
4. **Where the mounted-endpoint fixture lives.** Test sources (proposed, matching the fault-injecting
   precedent) versus main sources of the reference app, where it would double as a worked example
   for the manual how-to R530 will write.
5. **Whether `unrestricted()` should be spelled as an absent policy** (`post(body, headers)` with no
   third argument) rather than a policy value that happens to allow everything. The proposal keeps
   both: the overload for ergonomics, the value so a consumer can select a policy per request in one
   expression, as the example does.
