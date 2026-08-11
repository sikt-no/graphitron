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
inheritance, which is too brittle to build a trust boundary on. This item extracts the surface into
two public delegates (`GraphqlHttpHandler` for the execution pipeline, `GraphiqlBundle` for the page
and its assets) that any consumer resource can mount at any path, generalises the GET-mutation guard
into an explicit `OperationPolicy` argument (allowed operation types plus the rejection status and
message), and leaves `GraphqlResource` as a thin shell over both with an observably unchanged
contract.

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

### 1. Two delegates, split on the axis consumers actually fork on

Two public, `@ApplicationScoped` CDI beans, with every JAX-RS input passed as a parameter rather
than injected with `@Context`. Both are stateless, so application scope is correct, and neither
names a vendor type: `Response`, `HttpHeaders`, `UriInfo` are all `jakarta.ws.rs.core`.

```java
// GraphqlHttpHandler: the decode/decide/execute/encode pipeline.
// Injects GraphqlEngine and GraphitronApplication, exactly as the resource does now.
public Response post(String body, HttpHeaders headers);
public Response post(String body, HttpHeaders headers, OperationPolicy policy);
public Response get(String query, String operationName, String variables, String extensions,
                    HttpHeaders headers);
public Response execute(GraphqlRequest request, HttpHeaders headers);
public Response execute(GraphqlRequest request, HttpHeaders headers, OperationPolicy policy);
public String schema();
public static OperationDefinition.Operation resolveOperation(String query, String operationName);

// GraphiqlBundle: the self-hosted page and its classpath assets.
// Injects GraphitronApplication only, for the graphiqlEnabled() gate.
public Response page(UriInfo uriInfo);
public Response asset(String name);
```

Putting all of it on one bean would have reproduced `GraphqlResource` with the annotations stripped
off. The two groups share nothing: the pipeline is the decode/encode boundary (body and `Accept` in,
typed `GraphqlRequest` and a policy decision in the middle, `toSpecification()` plus the status
watershed out) and needs the engine, the SPI, JSON-B and graphql-java; the bundle is a classpath
streamer with a placeholder rewrite and an allowlist, coupled to the rest only through
`graphiqlEnabled()`. Fused, every consumer who mounts POST also drags in a class initialiser that
eagerly reads `graphiql.html` into a `static final` String, and the public method set becomes the
cross-product of surface and mount point. Split, each consumer mounts what it serves. It is the same
code in two files.

`schema()` is a one-line `new SchemaPrinter().print(application.schema())`. It is not a third axis;
it sits on the handler because that is where the SPI is already injected.

`resolveOperation` is public because it is the decision a consumer may legitimately want to make for
itself (metrics, logging, its own routing), and because it makes the guard's decision table testable
without a container. The pipeline calls exactly this method, so the test covers the real path rather
than a restatement of it. It returns `null` when no operation resolves and propagates graphql-java's
parse exception, both documented.

GET takes no policy overload. The spec's queries-only-with-405 rule is the only meaningful policy
for GET, and letting a consumer weaken it would let a consumer break conformance; a consumer that
wants GET disabled simply does not declare a GET method. State the corollary in the javadoc, because
a consumer reading "trust boundary" will assume otherwise: **the policy governs the verbs the
consumer routes through it.** A consumer mounting both verbs has "no mutation runs here" enforced by
its policy on POST and by the spec's 405 rule on GET, two paths with different statuses. That is
sound (the GET rule is strictly stricter for a queries-only endpoint) but it must be said out loud.

### 2. `OperationPolicy`: a configuration value, not a value object

A public final class with static factories and no public constructor, in the same package:

```java
public final class OperationPolicy {
    public static OperationPolicy queriesOnly(Response.StatusType status);
    public static OperationPolicy queriesOnly(Response.StatusType status, String message);
    public static OperationPolicy allowing(Set<OperationDefinition.Operation> allowed,
                                           Response.StatusType status, String message);

    public Set<OperationDefinition.Operation> allowed();
    public boolean permits(OperationDefinition.Operation operation);
}
```

Not a record. A record would have to publish the two things that exist only for the library's own
GET rule: a message that interpolates the rejected operation kind ("GraphQL **mutation** operations
must use POST, not GET.") and a header map that carries `Allow: POST` and nothing else. Those are an
internal constant's shape leaking into the public API of a Maven-Central-published artifact, and
three of the four record components would sit unfilled at every consumer callsite. A record whose
component is a `Function` also has structurally meaningless `equals`, which is the symptom of asking
one shape to express both a hardcoded conformance rule and a consumer configuration.

The final class keeps one enforcement path without publishing either. Internally it holds the
allowed set plus a `Function<Operation, Rejection>` where `Rejection` is a package-private
`(StatusType status, String message, Map<String, String> headers)`, and the GET rule is one more
instance of the same type: a package-private `OperationPolicy.SPEC_GET` with `allowed = {QUERY}`,
405, the interpolated message, and `Allow: POST`. One type, one guard, no second rule to keep in
agreement. Identity `equals` is correct for a configuration value and no longer misleading.

`Response.StatusType` rather than `int`: it is already on the module's provided classpath, it is
what `Response.status(...)` consumes, and it removes the compact-constructor range check that was
standing in for a type. The factories still reject an empty `allowed` set.

The two `queriesOnly` overloads exist because the consumer needs both wordings: the one-argument
form produces the library's own operation-interpolated wording ("GraphQL subscription operations are
not supported on this endpoint."), which is what a fixed consumer string gets wrong the moment a
subscription rather than a mutation arrives. `allowing` earns its place on the one policy neither
`queriesOnly` nor the absent-policy case expresses: queries and mutations permitted, subscriptions
refused, which is exactly the shape of a read-write endpoint on a library that serves no
subscription transport.

**Rejection is not legacy-downgraded.** Today's GET guard returns 405 regardless of the negotiated
media type (only the body's media type varies), because it is an HTTP-level rule rather than a
GraphQL request error. The generalised policy keeps that: a rejection returns the policy's status
for both modern and legacy clients, with the error body serialised in the negotiated type. This is
what the consumer needs, since 400 is the required answer for their non-production endpoints
regardless of what the caller sends in `Accept`.

### 3. No policy is an absent argument, never a permissive policy value

The pre-parse the policy needs (`Parser.parse`, inside `resolveOperation`) does not happen on POST
today. If the extracted pipeline ran it unconditionally, a syntactically invalid document over POST
would start returning the resource's own `"The GraphQL document could not be parsed."` request-error
body instead of graphql-java's `InvalidSyntax` result. Both are 400, so the existing conformance
test would still pass while the response body changed for every consumer.

The first draft of this item expressed that as an `unrestricted()` policy value whose permission set
was special-cased to skip the parse. That is a decision re-derived at the consumption site from the
value's inputs, and it buys a behaviour cliff keyed on set equality: a consumer writing a policy that
happens to permit every operation type would get a different response body for a syntax error than a
consumer who passed no policy, with identical permission semantics. It also leaves the skip
predicate silently wrong the day the policy grows any rejection reason other than operation type.

So there is no permissive policy value. **Absence of the argument is the state**, expressed by the
two-argument `post` / `execute` overloads; the pipeline's private entry point takes the guard as a
nullable parameter that only the library can construct, and null means "no guard, no parse". A
consumer selecting per request writes the selection as the call, not as a value:

```java
return env.isProduction() ? handler.post(body, headers)
                          : handler.post(body, headers, QUERIES_ONLY);
```

**The invariant needs its own enforcer.** The whole reason the skip exists is that no existing test
can see its violation. The test plan therefore adds the case that can: an unparseable document over
the built-in `POST /graphql` must still return graphql-java's `InvalidSyntax` error body, not the
resource's request-error wording.

### 4. Operation resolution and the smuggling question

`resolveOperation` (today's private `operationType`, promoted per §1) keeps its current semantics:
parse the document, pick the operation named by `operationName` when one is given, otherwise the
first operation, and return `null` when nothing resolves. `null` falls through to the engine,
unchanged. That is safe against smuggling, and the behaviour of graphql-java 25.0 was verified
directly rather than assumed:

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
belongs to the engine. Conservative rejection (refuse if any operation *definition* anywhere in the
document is non-permitted) was considered and rejected: it breaks the legitimate case where a client
ships one document holding both a query and a mutation and selects the query by `operationName`,
which the queries-only endpoint must serve.

**Two consequences for how this is written down.** First, the resolution is a hand-written
reimplementation of "which operation will graphql-java run", and this item promotes it from a 405
nicety on GET to the property a consumer's trust model rests on. Single-sourcing it against
graphql-java was investigated: the engine resolves through `graphql.language.NodeUtil.getOperation`,
which is annotated `@graphql.Internal` in 25.0, so calling it would pin us to a surface its authors
reserve the right to break. We keep our own resolution, and every one of the five behaviours above
gets a test rather than staying prose. Second, the delegate's javadoc keeps the *intent* ("the
resolved operation is the unit, because the engine honours `operationName`") and names the tests
that pin it; it does not transcribe graphql-java 25.0's exception messages, which are true when
written and silently falsified by an upgrade.

### 5. `GraphqlResource` becomes a thin shell

Same class, same `@Path("/graphql")`, same five methods with the same annotations, each one line
delegating to `GraphqlHttpHandler` (post, get, schema) or `GraphiqlBundle` (page, asset). The public
`GRAPHQL_RESPONSE_JSON` constant is defined on the handler and re-exported from `GraphqlResource` as
`public static final String GRAPHQL_RESPONSE_JSON = GraphqlHttpHandler.GRAPHQL_RESPONSE_JSON;`. That
initialiser is still a constant expression, so the field remains a constant variable and existing
`@Produces({GraphqlResource.GRAPHQL_RESPONSE_JSON, ...})` annotations in consumer code keep
compiling; an implementer must not "simplify" it into a method call. The class javadoc keeps
describing the endpoint's spec behaviour and gains a pointer to the delegates for consumers who need
a different mount point.

One place where the extraction is not pure motion: today's private `execute(request, isGet, legacy)`
receives a precomputed `legacy` flag, while the public `execute(GraphqlRequest, HttpHeaders, ...)`
must derive it, and `post` / `get` need it before delegating for their own 422 paths. Resolve it
with a private overload taking the resolved flag, so `isLegacy` runs once per request; do not let it
become two calls that can disagree.

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
`graphiqlEnabled()` already gates the page and the asset stream. The two toggles have identical
mechanics, which is the argument for the name and the placement: `graphiqlEnabled()` is also a
per-request 404 gate on a route that stays discovered, and its javadoc already carries the reasoning
for why a toggle like this rides the SPI rather than a config framework the library may not depend
on.

**Its javadoc must say what it is: a 404 gate, not a de-registration.** The resource class is still
discovered, still on the routing table, and still occupies the `/graphql` namespace when the toggle
is false. A consumer who needs the route genuinely gone declares a `jakarta.ws.rs.core.Application`
subclass with an explicit `getClasses()`, which needs no library support; the cost that keeps the
toggle worth having is that in Quarkus such a declaration becomes definitive for the whole
application, which is a large hammer for excluding one resource. Both routes are documented, with
that trade-off stated, so the choice is a decision rather than an unexplained preference.

### 7. Mounting under a templated path: the asset base, and the routing overlap

`page(UriInfo)` rewrites `{{ASSET_BASE}}` from `uriInfo.getAbsolutePath()`, which returns the
concrete request URI with template parameters already resolved, so a page served from
`/graphql/test` gets `/graphql/test/assets/`. Nothing in the rewrite changes. What it needs is a
test pinning it under a templated mount, and a javadoc sentence stating the two requirements it puts
on the consumer: serve the asset stream at `assets/{name}` relative to the same path or the bundle
404s, and note that `GraphiqlBundle.asset` is itself gated behind `graphiqlEnabled()`, so a consumer
delegating to it inherits that gate.

**The routing overlap is real and the first draft of this item got it backwards.** Jakarta REST
sorts candidate root resource classes by literal-character count descending. A consumer mounting
`@Path("/graphql/{callingEnvironment}")` has nine literal characters (`/graphql/`) against the
built-in `@Path("/graphql")`'s eight, so the *templated* class sorts first for any request with two
or more path segments. The consequences, none of which the toggle removes:

- `/graphql/schema` reaches the consumer's resource with `callingEnvironment = "schema"`, not the
  library's SDL endpoint.
- `/graphql/assets/graphiql.js` likewise reaches the consumer's class (with the remaining segment as
  a sub-path), so the built-in GraphiQL bundle stops resolving.
- Bare `/graphql` still reaches the built-in resource, because the templated pattern needs a second
  segment. That is exactly the ungated endpoint §6's toggle is for.

So the honest statement for the javadoc and the consumer example is: a consumer mounting directly
under `/graphql/{...}` shadows the built-in sub-paths whatever the toggle says, and must serve
`assets/{name}` and `schema` from its own resource if it wants them. This is a claim about the
container's matching algorithm across two resource classes, so the test plan pins it rather than
asserting it, in the one place a claim like that can be pinned: a running container.

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

- `GraphqlHttpHandler.java`: the execution pipeline, holding the `JSONB` binder, `execute`,
  `resolveOperation`, `statusFor`, `requestError`, `serialise`, `errorBody`, `responseType`,
  `isLegacy`, `parseMapParam`, `schema`, and the `GRAPHQL_RESPONSE_JSON` constant. Package-level
  javadoc quality, with the consumer example below.
- `GraphiqlBundle.java`: the page and asset surface, holding `GRAPHIQL_HTML`, the `{{ASSET_BASE}}`
  rewrite, the name allowlist, `assetMediaType`, and `loadResource`, behind the existing
  `graphiqlEnabled()` gate.
- `OperationPolicy.java`: the final class, its factories, the package-private `Rejection` and the
  package-private `SPEC_GET` constant carrying today's GET rule.

Java 17 syntax only in all three, per the module's pinned `default-compile`. This is the constraint
that also settles the sealed-hierarchy question in §2: sealed types compile at 17, but pattern
labels in `switch` do not (21 and up), so a sealed policy hierarchy in this module would dispatch
through virtual calls or `instanceof` chains and give up most of what makes sealed attractive
elsewhere in the reactor.

Edited:

- `GraphqlResource.java`: reduced to the five annotated methods delegating to the two beans, the
  `defaultEndpointEnabled()` gate, and the re-exported constant. Javadoc keeps the spec narrative and
  points at the delegates.
- `GraphitronApplication.java`: add `defaultEndpointEnabled()`, with javadoc saying it is a 404 gate
  rather than a de-registration and naming the `Application`-subclass alternative; extend
  `newExecutionInput()`'s javadoc with the `WebApplicationException` contract.
- `GraphqlRequest.java`: javadoc pointer moves from `GraphqlResource` to the handler.
- `META-INF/beans.xml`: comment names both delegates.
- `docs/architecture/reference/modules.adoc`: the module row gains the delegates and the policy in
  its one-line surface description.
- `graphitron-sakila-example/README.md`: the app section says "everything HTTP-shaped comes from the
  library"; add one sentence that a consumer needing its own path or a stricter operation policy
  mounts the delegates instead of the built-in resource.
- `graphitron-sakila-example/.../FaultInjectingGraphitronApplication.java`: its javadoc links
  `GraphqlResource#execute`, which moves. Repoint at the handler.

`AbstractGraphitronApplication` and `GraphqlEngine` are untouched.

## Consumer example (handler javadoc)

The handler's javadoc carries this sketch, which is the consumer case reduced to its shape:

```java
@Path("/graphql/{callingEnvironment}")
public class EnvironmentGraphqlResource {

    private static final OperationPolicy QUERIES_ONLY =
        OperationPolicy.queriesOnly(Response.Status.BAD_REQUEST);

    @Inject GraphqlHttpHandler handler;
    @Inject GraphiqlBundle graphiql;
    @Inject CallerClaims claims;          // @RequestScoped, read by this app's SPI adapter

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({GraphqlHttpHandler.GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
    public Response post(@PathParam("callingEnvironment") String environment,
                         String body,
                         @Context HttpHeaders headers) {
        Environment env = Environment.parse(environment);              // 404 on an unknown value
        claims.populate(authenticate(headers, env));                   // 401 via NotAuthorizedException
        return env.isProduction()
            ? handler.post(body, headers)                              // no policy: every operation
            : handler.post(body, headers, QUERIES_ONLY);               // 400 before execution
    }

    @GET
    @Path("assets/{name}")
    public Response asset(@PathParam("name") String name) {
        return graphiql.asset(name);      // gated behind GraphitronApplication.graphiqlEnabled()
    }
}
```

Three properties the javadoc states explicitly around it. The resource method runs to completion
before the handler touches the seam, so a `@RequestScoped` holder populated here is visible to
`newExecutionInput()`. A `WebApplicationException` thrown either here or from the seam reaches the
container unredacted, which is how 401 and 403 are produced. And mounting under `/graphql/{...}`
shadows the built-in resource's sub-paths (§7), so this consumer serves the assets itself and would
have to serve `schema` itself too.

## Test plan

Coverage splits by what each case actually asserts, per the rubric in
`docs/architecture/how-to/testing.adoc` ("tier is determined by what's asserted, not by what module
the file lives in"). The guard is a pure decision over `(query, operationName, policy)`; observing it
only through a Quarkus boot, live Postgres, JAX-RS routing, JSON-B, content negotiation and the
engine would be nine layers of plumbing around three arguments. The first draft did exactly that, and
its own cleverest case (choosing a mutation field that does not exist so that 400-not-422 proves the
guard ran first) was the tell: it was compensating for observing a pure decision through its most
distant effect.

Both new files live in `graphitron-sakila-example`'s test sources. The runtime module stays free of
`@Test` classes: adding them would mean test-scope JUnit plus a dependency on `graphitron`'s
tier-annotation test-jar, which inverts the layering of a runtime artifact that today depends on
graphql-java and four provided APIs and nothing else. `ScatterSingleByIdxTest` is the standing
precedent for a `@UnitTier` class living in that module purely for dependency reasons.

**Unit tier** (`OperationPolicyTest`, `@UnitTier`, no container, no database): a table over the guard
decision, calling `GraphqlHttpHandler.resolveOperation` and `OperationPolicy.permits` directly.

1. Mutation document, queries-only policy: rejected.
2. Subscription document, queries-only policy: rejected. Pins that the guard is
   `operation != QUERY`, not a mutation special case.
3. Multi-operation document, `operationName` selects the mutation: resolves to the mutation, so the
   policy rejects. The smuggling case, at its own altitude.
4. Same document, `operationName` selects the query: permitted.
5. Multi-operation document, no `operationName`: resolves to the first operation.
6. `operationName` matching nothing: resolves to `null`, so no rejection is possible and the request
   falls through to the engine.
7. Fragment-only document: resolves to `null`, same fall-through.
8. Unparseable document: `resolveOperation` propagates graphql-java's parse exception, which is what
   the pipeline turns into 400.
9. `allowing({QUERY, MUTATION}, ...)`: query and mutation permitted, subscription rejected.
10. The rejection carried by `queriesOnly(status)` interpolates the rejected operation kind, so a
    subscription is not reported as a mutation.

**Execution tier** (`MountedEndpointTest`, `@QuarkusTest` + `@ExecutionTier`), the cases only a
running container can produce. Fixture: a `PolicyMountedGraphqlResource` in test sources at
`/env/{callingEnvironment}/graphql`, delegating with `queriesOnly` for the non-production path value
and no policy for the production one, plus `assets/{name}`, the page, and `schema`. Test sources for
the same reason `FaultInjectingGraphitronApplication` is there: the shipped reference app stays a
pristine copy-paste template.

11. Mutation over POST on the queries-only mount: 400, body is the policy message, no `data` member.
    One end-to-end case pinning that the unit-tier decision reaches the wire with the right status,
    media type and shape.
12. A mutation over POST on the no-policy mount: 200. The policy is a per-call argument, not a mode.
13. Unresolvable operation on the queries-only mount falls through to the engine: 422 from
    graphql-java, not the policy message.
14. Unparseable document on the queries-only mount: 400 with the "could not be parsed" request-error
    body.
15. Legacy `Accept: application/json` on a policy rejection: still 400, error body in
    `application/json`. Pins the deliberate non-downgrade.
16. GraphiQL page from the templated mount: `{{ASSET_BASE}}` resolves to `/env/test/graphql/assets/`
    and `GET /env/test/graphql/assets/graphiql.js` streams 200 `text/javascript`.
17. `schema` under the templated mount returns SDL.
18. Ordering: the mounted resource populates a `@RequestScoped` holder from the path parameter before
    delegating; the test-scoped adapter records what it saw in `newExecutionInput()` and the resource
    echoes it back as a response header. Asserts the seam observed what the resource set, which is
    the property the consumer's trust model rests on.
19. A `WebApplicationException` thrown by the mounted resource method before delegating (missing
    `Authorization` header) surfaces as 401, unredacted.
20. `defaultEndpointEnabled() == false`: the built-in `/graphql` routes answer 404 while the mounted
    endpoint keeps working. Realised by the test adapter reading a request header, following the
    `FAULT_HEADER` precedent, so no second Quarkus boot is needed. Note for the implementer: this
    works *because* the toggle is evaluated per request, so it must not be read as evidence for the
    design fork in §6; under the registration-level alternative the test could not exist at all.

**Routing overlap** (`OverlappingMountTest`, `@QuarkusTest` + `@ExecutionTier`, behind a
`@TestProfile`). §7's claim is about Jakarta REST's matching algorithm across two resource classes,
so it gets pinned rather than asserted. The profile boots a second app instance carrying a fixture
mounted at `@Path("/graphql/{callingEnvironment}")`, overlapping the built-in resource, with
`defaultEndpointEnabled()` false. It pins that `/graphql/schema` and `/graphql/assets/graphiql.js`
reach the *consumer's* class rather than the library's, and that bare `/graphql` is 404 under the
toggle. It needs its own profile precisely because an overlapping mount in the shared test app would
shadow the built-in sub-paths for every other test in the module, which is itself the finding.

**Regression, unchanged expectations.** Every case in `GraphQLOverHttpConformanceTest` and
`GraphqlResourceSmokeTest` passes with no edit to its expectations, plus one case the suite is
missing today and this design requires:

21. An unparseable document over the built-in `POST /graphql` returns graphql-java's `InvalidSyntax`
    error body (assert on the error shape graphql-java produces, for instance the presence of
    `locations`), not the resource's "could not be parsed" wording. This is the enforcer for §3: it
    is the only case that can catch a future implementer making the pre-parse unconditional, since
    both bodies come back as 400 and every existing assertion is on the status alone.

That is the observable contract for "`GraphqlResource` is now a shell": GET-to-mutation stays 405
with `Allow: POST`, the 400/422 watershed holds, legacy stays 200, the redaction and passthrough
cases hold, and the GraphiQL page and assets still resolve under `/graphql`.

## Non-goals

- No change to `newExecutionInput()`'s arity or to how a consumer seeds auth.
- No authentication, authorisation, or environment model in the library. The consumer owns all of
  it; the library owns only where the guard sits in the pipeline.
- No subscription transport (WebSocket, SSE). A subscription operation is something the policy can
  reject, not something the library serves.
- No config-framework binding for the new toggle, for the same reason `graphiqlEnabled()` has none.
- Not a substitute for database-level read-only enforcement (R460). This policy is an HTTP-level
  operation-type gate; it says nothing about what SQL a permitted operation can issue.

## Decisions taken during Spec

The first draft left five decisions open. A principles consult closed all five; the reasoning is
recorded here so the sign-off reviewer sees what was weighed rather than only what was chosen.

1. **Names: `GraphqlHttpHandler` and `GraphiqlBundle`, on two beans, not one.** The surface splits on
   the axis consumers fork on (§1): a consumer mounts the execution pipeline, or the bundle, or
   both, and the two share no collaborator. One bean would have been `GraphqlResource` with the
   annotations stripped off, which is the shape this item exists to break up. `GraphqlEndpoint` was
   the runner-up name for the pipeline bean and was dropped because "endpoint" is what the consumer
   mounts, not what the library hands them.
2. **`OperationPolicy` is a final class with static factories, not a record** (§2). The record form
   would have published a `Function` and a header map that exist only for the library's own GET rule,
   on a Maven-Central-published artifact, with three of four components unfilled at every consumer
   callsite. The final class keeps one enforcement path (the GET rule is one more instance of the
   same type) while publishing neither. `Response.StatusType` replaces `int` so the 4xx range check
   stops standing in for a type. A sealed hierarchy was considered and rejected on the module's
   Java 17 floor: sealed compiles at 17 but pattern labels in `switch` do not, so it would dispatch
   through `instanceof` chains and give up what makes sealed worthwhile.
3. **No permissive policy value; absence of the argument is the state** (§3). `unrestricted()` was a
   decision re-derived from its own inputs at the consumption site, and it bought a behaviour cliff
   keyed on set equality (two policies with identical permission semantics, different response
   bodies for a syntax error). The invariant it protected now has an enforcer, test 21, which is the
   single most load-bearing addition in this revision: without it, an implementer who makes the
   pre-parse unconditional changes the response body of every existing consumer and no test fails.
4. **The toggle keeps its name and its placement, and its javadoc gets honest** (§6). It is a 404
   gate on a route that stays registered, exactly like the `graphiqlEnabled()` sibling it sits next
   to. What changed is the claim around it: the first draft said disabling the built-in endpoint
   "removes the question entirely" for the routing overlap, which is false, and had the JAX-RS
   matching backwards on top of that. §7 now states the real consequence and test class
   `OverlappingMountTest` pins it.
5. **Coverage splits by tier, not by habit** (test plan). Ten of the first draft's fourteen
   execution-tier cases were assertions about a pure decision, reachable without a container; they
   are now a unit-tier table, with the container keeping only what only a container can show
   (routing, the templated asset base, request-scope ordering, the exception passthrough, the
   toggle). The fixture stays in test sources; folding it into the reference app's main sources
   would trade a pristine copy-paste template for a worked example that the manual how-to (R530) is
   the right home for anyway.

## Open questions for the reviewer

None blocking. Two judgement calls worth a second opinion at sign-off:

- `GraphqlHttpHandler.resolveOperation` is public partly so the guard's decision table is testable
  from another module, and partly because a consumer may legitimately want the same determination.
  If the reviewer reads that as testability leaking into published API, the fallback is
  package-private plus an in-module unit test, at the cost of the test-dependency layering described
  in the test plan.
- The `allowing(Set, StatusType, String)` factory serves a policy no consumer has asked for yet
  (queries and mutations permitted, subscriptions refused). It is defensible on a library that
  serves no subscription transport, but it is permanent public surface bought ahead of demand.
