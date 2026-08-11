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
- **`GraphqlRequest`** is the request-body record. Unchanged, and it stays an implementation detail:
  the handler's published entry points take a raw body or the GET query parameters, so this record
  never appears in a consumer-facing signature. What moves is its javadoc pointer at
  `GraphqlResource`, which names the binder that parses it.
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
parse exception, both documented. It stays on the handler rather than moving to `OperationPolicy`
because it is a decode, raw document text to a typed `Operation`, and the handler is the decode
boundary; the policy is the decision taken *over* that typed value. A `static` on a CDI bean is an
odd-looking published shape, and the alternative homes are worse: on `OperationPolicy` it conflates
resolution with permission, and a third type carrying one method earns nothing.

GET takes no policy overload. The spec's queries-only-with-405 rule is the only meaningful policy
for GET, and letting a consumer weaken it would let a consumer break conformance; a consumer that
wants GET disabled simply does not declare a GET method. State the corollary in the javadoc, because
a consumer reading "trust boundary" will assume otherwise: **the policy governs the verbs the
consumer routes through it.** A consumer mounting both verbs has "no mutation runs here" enforced by
its policy on POST and by the spec's 405 rule on GET, two paths with different statuses. That is
sound (the GET rule is strictly stricter for a queries-only endpoint) but it must be said out loud.

**There is no public `execute(GraphqlRequest, ...)`.** The pre-parsed entry point stays private, for
a reason stronger than surface economy: because the 405 rule rides `get`, a public no-policy
`execute` is the one way a consumer could route GET through the pipeline with no rule attached at
all, and get there by writing something that looks entirely reasonable. Without it, every verb a
consumer can route carries its rule structurally (POST carries the consumer's policy or none, GET
always carries `SPEC_GET`) rather than by a javadoc warning the consumer has to read and heed. It is
also surface nothing has asked for: the driving consumer posts a raw body, and a consumer that
genuinely must pre-parse (multipart, its own `MessageBodyReader`) is a Backlog item to file when
someone asks, which a new public overload makes cheap to answer additively.

`get`'s four adjacent `String` parameters are the one uncomfortable signature here: inside a JAX-RS
resource `@QueryParam` makes order irrelevant, and across a plain method call it does not, so a
transposed `variables`/`extensions` pair compiles and misbehaves quietly. A params carrier was
considered and does not help: the consumer would write the same four positional strings into a
constructor instead of a call, moving the hazard rather than removing it. The mitigation that does
work is showing it, so the consumer example below carries a GET arm whose forwarding call sits
directly under the consumer's own `@QueryParam`-annotated parameters, where a transposition is
visible in the diff. Keep the `@param` names in the javadoc matching the query-string names exactly.

### 2. `OperationPolicy`: a configuration value, not a value object

A public final class with static factories and no public constructor, in the same package:

```java
public final class OperationPolicy {
    public static OperationPolicy queriesOnly(Response.StatusType status);
    public static OperationPolicy queriesOnly(Response.StatusType status, String message);
    public static OperationPolicy allowing(Set<OperationDefinition.Operation> allowed,
                                           Response.StatusType status, String message);

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

Only `permits` is published, not an `allowed()` accessor: the set is the factory's input, and every
stated use is the permission question, so an accessor would be a second way to ask it.

`Response.StatusType` rather than `int`: it is already on the module's provided classpath, and it is
what `Response.status(...)` consumes. It does *not* remove the factory's validation, and the first
draft's claim that it replaces the range check was wrong: `StatusType` constrains nothing, so
`queriesOnly(Response.Status.OK)` would compile and produce a rejection no client can read as one.
The factories reject an empty `allowed` set, and reject a status outside the `CLIENT_ERROR` and
`SERVER_ERROR` families (`status.getFamily()`). Both families stay open rather than narrowing to
4xx, because `501 Not Implemented` is a defensible answer for "this endpoint serves no subscription
transport" and nothing is gained by forbidding it.

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
two-argument `post` overload; the pipeline's private entry point takes the guard as a nullable
parameter that only the library can construct, and null means "no guard, no parse". A
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

Same class, same `@Path("/graphql")`, same five methods with the same annotations and the same return
types, each now the §6 gate call plus one line delegating to `GraphqlHttpHandler` (post, get, schema)
or `GraphiqlBundle` (page, asset). The public
`GRAPHQL_RESPONSE_JSON` constant is defined on the handler and re-exported from `GraphqlResource` as
`public static final String GRAPHQL_RESPONSE_JSON = GraphqlHttpHandler.GRAPHQL_RESPONSE_JSON;`. That
initialiser is still a constant expression, so the field remains a constant variable and existing
`@Produces({GraphqlResource.GRAPHQL_RESPONSE_JSON, ...})` annotations in consumer code keep
compiling; an implementer must not "simplify" it into a method call. The class javadoc keeps
describing the endpoint's spec behaviour and gains a pointer to the delegates for consumers who need
a different mount point.

One place where the extraction is not pure motion: today's private `execute(request, isGet, legacy)`
receives a precomputed `legacy` flag, and the handler's `post` / `get` still need that flag before
they reach the pipeline, for their own 422 paths. Derive it once at the top of each public entry
point and pass it down to the private pipeline method; do not let `isLegacy` become two calls per
request that can disagree.

### 6. Turning the built-in endpoint off

This is the security-relevant half of "my own path". A consumer that mounts
`/graphql/{callingEnvironment}` must not also be serving the library's own `/graphql`, where no
environment is bound and where their authentication code never runs. Bean discovery registers
`GraphqlResource` automatically today, so the consumer cannot simply not use it.

Add a defaulted SPI toggle alongside `graphiqlEnabled()`:

```java
default boolean builtInEndpointEnabled() { return true; }
```

`builtInEndpointEnabled`, not `defaultEndpointEnabled`: "default" invites the reading "the endpoint
you get unless configured otherwise", which is what the toggle *changes* rather than what it names,
where "built-in" says plainly that the library ships this route itself. The word is this item's, not
yet the tree's: `modules.adoc`, the example README and `graphitron-jakarta-rest` say "self-hosted",
"out of the box" and "the `/graphql` resource", and none of them says "built-in" today. The prose this
item adds to the first two is what introduces it, so the name and the docs land together rather than
the name claiming support that does not exist yet. The placement rides the SPI for the reason `graphiqlEnabled()`'s javadoc
already carries: the framework decision is vendor-neutral Jakarta with no dependency outside the
parent pom's pinned set, so the library cannot reach for a config framework itself.

`GraphqlResource` consults it first in all five methods, through one private gate that throws rather
than returning a status:

```java
private void requireBuiltInEndpoint() {
    if (!application.builtInEndpointEnabled()) {
        throw new NotFoundException();
    }
}
```

Throwing, not `return Response.status(404).build()`, because `schema()` returns `String`: a
status-returning gate is expressible in four of the five methods and not the fifth, and the fix is
not to widen `schema()`'s published return type for the sake of the gate. One throwing helper gates
all five uniformly, and `jakarta.ws.rs.NotFoundException` yields the same 404 *status* as the
`graphiqlEnabled()` gate. Not necessarily the same body: a thrown `NotFoundException` is rendered by
the container's exception mapper, while `Response.status(NOT_FOUND).build()` returns an empty entity,
so the two 404s can differ below the status line. Nothing in the contract depends on the body of a
disabled route, so test 22 asserts the status only, and the two gates are deliberately not claimed to
agree further than that. The gate runs in `GraphqlResource` before it delegates, so this
`WebApplicationException` never meets the pipeline's passthrough arm; it goes straight to the
container, which is the same mechanism §8 preserves for the consumer's own 401.

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
`assets/{name}` and `schema` from its own resource if it wants them.

This is a claim about the container's matching algorithm across two root resource classes, so the
test plan pins it in a running container rather than asserting it. It pins the *algorithm*, with a
neutral pair of fixture paths, and does not stage the real `/graphql` overlap. That is not
squeamishness, it is the only shape available: a `@Path` class in test sources is registered from the
build-time index for every `@QuarkusTest` deployment in the module, and a `@TestProfile` selects
config, alternatives and a build profile without changing the deployment's class set, so a fixture
mounted at `/graphql/{...}` would shadow the built-in sub-paths for every other test in the module,
not just its own. The `Application`-subclass route above is application-wide for the same reason and
scopes nothing per test class. An implementer who "restores" the realistic paths will break
`GraphqlResourceSmokeTest`'s page and asset cases; the neutral pair is load-bearing, not a
placeholder.

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

- `GraphqlHttpHandler.java`: the execution pipeline, holding the `JSONB` binder, the private
  `execute`, `resolveOperation`, `statusFor`, `requestError`, `serialise`, `errorBody`,
  `responseType`, `isLegacy`, `isBlank`, `parseMapParam`, `schema`, the `LOGGER`, and both the
  `GRAPHQL_RESPONSE_JSON` constant and the `GRAPHQL_RESPONSE_TYPE` media type `isLegacy` compares
  against. Package-level javadoc quality, with the consumer example below.
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
  `requireBuiltInEndpoint()` gate, and the re-exported constant. Its class javadoc currently names
  the mechanics that leave (`{@link #execute}`, the `Jsonb` binder it no longer holds); it keeps the
  spec narrative and points at the delegates. The `{@link #execute}` reference is a dangling link the
  moment the method moves, so the javadoc reference gate catches this one for you.
- `GraphitronApplication.java`: add `builtInEndpointEnabled()`, with javadoc saying it is a 404 gate
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

Two prose sites name `GraphqlResource` as the thing that streams the GraphiQL bundle, and both are
false once that behaviour lands on `GraphiqlBundle`. Neither is Java, so no build gate can see them:

- `graphitron-jakarta-rest/src/main/resources/no/sikt/graphitron/jakarta/rest/graphiql.html`: the
  comment in the page head, "GraphqlResource streams them from the assets endpoint".
- `graphitron-jakarta-rest/tools/graphiql-build/README.md`: two occurrences, "`GraphqlResource`
  streams it from its `assets/{name}` endpoint" and "the absolute `{{ASSET_BASE}}` prefix that
  `GraphqlResource` injects into `graphiql.html` at serve time".

The `## Retired vocabulary` section below exists so the Done gate greps for this class of rot rather
than trusting this list to be complete.

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
    @Produces({GraphqlHttpHandler.GRAPHQL_RESPONSE_JSON, MediaType.APPLICATION_JSON})
    public Response get(@PathParam("callingEnvironment") String environment,
                        @QueryParam("query") String query,
                        @QueryParam("operationName") String operationName,
                        @QueryParam("variables") String variables,
                        @QueryParam("extensions") String extensions,
                        @Context HttpHeaders headers) {
        claims.populate(authenticate(headers, Environment.parse(environment)));
        // Parameter order matches the four @QueryParam names directly above. GET carries the
        // spec's queries-only rule (405 + Allow: POST) with no policy argument to pass.
        return handler.get(query, operationName, variables, extensions, headers);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response graphiql(@Context UriInfo uriInfo) {
        // Same shape as the built-in resource: one @GET per produced type, so a browser sending
        // Accept: text/html lands here and curl/POST traffic does not. Omit this arm and a browser
        // gets 406 from the JSON-only arm above.
        return graphiql.page(uriInfo);    // {{ASSET_BASE}} resolves to this request's path + assets/
    }

    @GET
    @Path("assets/{name}")
    public Response asset(@PathParam("name") String name) {
        return graphiql.asset(name);      // gated behind GraphitronApplication.graphiqlEnabled()
    }
}
```

Four properties the javadoc states explicitly around it. The resource method runs to completion
before the handler touches the seam, so a `@RequestScoped` holder populated here is visible to
`newExecutionInput()`. A `WebApplicationException` thrown either here or from the seam reaches the
container unredacted, which is how 401 and 403 are produced. Mounting under `/graphql/{...}`
shadows the built-in resource's sub-paths (§7), so this consumer serves the assets itself and would
have to serve `schema` itself too. And the two verbs carry their rules differently: POST takes the
policy the consumer selects per request, GET carries the spec's 405 rule unconditionally, which is
why there is one `get` and two `post`s and no way to route a verb through the pipeline without a
rule attached.

## Test plan

Coverage splits by what each case actually asserts, per the rubric in
`docs/architecture/how-to/testing.adoc` ("tier is determined by what's asserted, not by what module
the file lives in"). The guard is a pure decision over `(query, operationName, policy)`; observing it
only through a Quarkus boot, live Postgres, JAX-RS routing, JSON-B, content negotiation and the
engine would be nine layers of plumbing around three arguments. The first draft did exactly that, and
its own cleverest case (choosing a mutation field that does not exist so that 400-not-422 proves the
guard ran first) was the tell: it was compensating for observing a pure decision through its most
distant effect.

All three new test classes live in `graphitron-sakila-example`'s test sources. The runtime module stays free of
`@Test` classes: adding them would mean test-scope JUnit plus a dependency on `graphitron`'s
tier-annotation test-jar, which inverts the layering of a runtime artifact that today depends on
graphql-java and four provided APIs and nothing else. `ScatterSingleByIdxTest` is the standing
precedent for a `@UnitTier` class living in that module purely for dependency reasons.

**Unit tier** (`OperationGuardTest`, `@UnitTier`, no container, no database): a table over the guard
decision, calling `GraphqlHttpHandler.resolveOperation` and `OperationPolicy.permits` directly. Named
for the guard rather than for either collaborator, because the decision under test is the pair: the
resolution and the permission check are separately uninteresting.

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
11. Factory validation: an empty `allowed` set is rejected, and so is a status outside the
    `CLIENT_ERROR` / `SERVER_ERROR` families (`queriesOnly(Response.Status.OK)` throws;
    `queriesOnly(Response.Status.NOT_IMPLEMENTED)` is accepted). This is the enforcer for the §2
    claim that `StatusType` narrows the type but not the value.

**Execution tier** (`MountedEndpointTest`, `@QuarkusTest` + `@ExecutionTier`), the cases only a
running container can produce. Fixture: a `PolicyMountedGraphqlResource` in test sources at
`/env/{callingEnvironment}/graphql`, shaped like the consumer example above: POST delegating with
`queriesOnly` for the non-production path value and no policy for the production one, a GET arm
forwarding to `handler.get`, plus `assets/{name}`, the page, and `schema`. Test sources for the same
reason `FaultInjectingGraphitronApplication` is there: the shipped reference app stays a pristine
copy-paste template.

12. Mutation over POST on the queries-only mount: 400, body is the policy message, no `data` member.
    One end-to-end case pinning that the unit-tier decision reaches the wire with the right status,
    media type and shape.
13. A mutation over POST on the no-policy mount: 200. The policy is a per-call argument, not a mode.
14. A GET resolving to a mutation on the mount's GET arm: 405 with `Allow: POST`, not the policy's
    400. Pins §1's "the policy governs the verbs the consumer routes through it": a consumer-routed
    GET carries `SPEC_GET` whether or not the consumer passes a policy anywhere, which is the
    invariant that lets the published surface omit a no-policy `execute` entirely.
15. Unresolvable operation on the queries-only mount falls through to the engine: 422 from
    graphql-java, not the policy message.
16. Unparseable document on the queries-only mount: 400 with the "could not be parsed" request-error
    body.
17. Legacy `Accept: application/json` on a policy rejection: still 400, error body in
    `application/json`. Pins the deliberate non-downgrade.
18. GraphiQL page from the templated mount: `{{ASSET_BASE}}` resolves to `/env/test/graphql/assets/`
    and `GET /env/test/graphql/assets/graphiql.js` streams 200 `text/javascript`.
19. `schema` under the templated mount returns SDL.
20. Ordering: the mounted resource populates a `@RequestScoped` holder from the path parameter before
    delegating; the test-scoped adapter records what it saw in `newExecutionInput()` and the resource
    echoes it back as a response header. Asserts the seam observed what the resource set, which is
    the property the consumer's trust model rests on.
21. A `WebApplicationException` thrown by the mounted resource method before delegating (missing
    `Authorization` header) surfaces as 401, unredacted.
22. `builtInEndpointEnabled() == false`: all five built-in `/graphql` routes answer 404 (including
    `/graphql/schema`, the one whose gate throws rather than returns) while the mounted endpoint keeps
    working. Realised by the test adapter reading a request header, following the `FAULT_HEADER`
    precedent, so no second Quarkus boot is needed. Note for the implementer: this works *because*
    the toggle is evaluated per request, so it must not be read as evidence for the design fork in
    §6; under the registration-level alternative the test could not exist at all.

**Routing overlap** (`OverlappingMountTest`, `@QuarkusTest` + `@ExecutionTier`, no profile and no
second boot). §7's claim is about Jakarta REST's matching algorithm across two root resource classes,
so it gets pinned rather than asserted, at paths that overlap each other and nothing else. Two
fixtures in test sources: `@Path("/probe")` with a `schema` sub-path and an `assets/{name}`
sub-resource, and `@Path("/probe/{p}")` catching a sub-path, each answering with its own identity so
which class served the request is visible in the body. Isomorphic to the real case by construction:
`/probe/` is seven literal characters against `/probe`'s six, the same one-character margin `/graphql/`
holds over `/graphql`.

- `/probe/schema` reaches the templated class with `p = "schema"`, not the literal class's sub-path.
- `/probe/assets/probe.js` reaches the templated class too, with the remainder as its sub-path.
- Bare `/probe` reaches the literal class, because the templated pattern needs a second segment.

Those three are §7's three consequences with the names changed, and the arithmetic that carries them
across (nine against eight rather than seven against six) is in the assertion messages so the
correspondence is not left to a reader. What this deliberately does not pin is the `/graphql` overlap
itself, for the reason §7 gives: staging it would shadow the built-in sub-paths for every test in the
module rather than for this one class. The toggle half of the old formulation is not lost, it was
always better placed in test 22, which asserts all five built-in routes answer 404 with the toggle
off.

**Regression, unchanged expectations.** Every case in `GraphQLOverHttpConformanceTest` and
`GraphqlResourceSmokeTest` passes with no edit to its expectations, plus one case the suite is
missing today and this design requires:

23. An unparseable document over the built-in `POST /graphql` returns graphql-java's `InvalidSyntax`
    error body (assert on the error shape graphql-java produces, for instance the presence of
    `locations`), not the resource's "could not be parsed" wording. This is the enforcer for §3: it
    is the only case that can catch a future implementer making the pre-parse unconditional, since
    both bodies come back as 400 and every existing assertion is on the status alone.

That is the observable contract for "`GraphqlResource` is now a shell": GET-to-mutation stays 405
with `Allow: POST`, the 400/422 watershed holds, legacy stays 200, the redaction and passthrough
cases hold, and the GraphiQL page and assets still resolve under `/graphql`.

## Retired vocabulary

Every name and prose claim below is false once this item lands, so the Done gate has a grep query for
the retirement sweep (`roadmap/workflow.adoc`, "Retirement sweep"). The two `.adoc` / `.html` / `.md`
sites named in the Implementation section are the ones already found; the sweep exists to catch the
ones that list missed, because none of these surfaces is reachable by the javadoc reference gate.

- `operationType`, the private resolution helper, renamed to `resolveOperation`.
- `GraphqlResource#execute`, `GraphqlResource#statusFor`, `GraphqlResource#requestError`: the pipeline
  members, now on `GraphqlHttpHandler`. Live `{@link}` references to them fail the build, but prose
  and non-Java comments naming them do not.
- "`GraphqlResource` streams the GraphiQL assets", and any phrasing of `GraphqlResource` injecting
  `{{ASSET_BASE}}` or serving `assets/{name}`. That is `GraphiqlBundle`.
- "Request parsing and response serialisation live in this resource", and any phrasing of
  `GraphqlResource`'s `Jsonb` binder, its JSON parsing, or its ownership of the status watershed. All
  of it moves to the handler; the resource keeps only the mount and the toggle gate.
- "the only endpoint shape", "`/graphql` is hard-coded", and neighbouring claims that the library
  serves exactly one path. Any of these surviving in module docs or the example README is the item
  failing to land its own point.

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
   same type) while publishing neither. `Response.StatusType` replaces `int` because it is what
   `Response.status(...)` consumes, not because it validates anything. A sealed hierarchy was considered and rejected on the module's
   Java 17 floor: sealed compiles at 17 but pattern labels in `switch` do not, so it would dispatch
   through `instanceof` chains and give up what makes sealed worthwhile.
3. **No permissive policy value; absence of the argument is the state** (§3). `unrestricted()` was a
   decision re-derived from its own inputs at the consumption site, and it bought a behaviour cliff
   keyed on set equality (two policies with identical permission semantics, different response
   bodies for a syntax error). The invariant it protected now has an enforcer, test 23, which is the
   single most load-bearing addition in this revision: without it, an implementer who makes the
   pre-parse unconditional changes the response body of every existing consumer and no test fails.
4. **The toggle keeps its placement, and its javadoc gets honest** (§6). It is a 404
   gate on a route that stays registered, exactly like the `graphiqlEnabled()` sibling it sits next
   to. What changed is the claim around it: the first draft said disabling the built-in endpoint
   "removes the question entirely" for the routing overlap, which is false, and had the JAX-RS
   matching backwards on top of that. §7 now states the real consequence, and `OverlappingMountTest`
   pins the matching algorithm that produces it (at neutral paths, per decision 12).
5. **Coverage splits by tier, not by habit** (test plan). Ten of the first draft's fourteen
   execution-tier cases were assertions about a pure decision, reachable without a container; they
   are now a unit-tier table, with the container keeping only what only a container can show
   (routing, the templated asset base, request-scope ordering, the exception passthrough, the
   toggle). The fixture stays in test sources; folding it into the reference app's main sources
   would trade a pristine copy-paste template for a worked example that the manual how-to (R530) is
   the right home for anyway.

## Decisions taken at the sign-off reviews

**First pass.** The review checked the claims above against the tree rather than reading them: graphql-java
25.0's `NodeUtil.getOperation(Document, String)` does exist and its class does carry
`@graphql.Internal` (runtime-retained), so §4's rejection is grounded; §7's literal-character sort
works out as described (`/graphql/` is nine characters against `/graphql`'s eight, and the templated
pattern does not match a single segment); the re-exported constant is a constant expression per
JLS 15.29, so consumer `@Produces` annotations keep compiling; and
`GraphQLOverHttpConformanceTest.unparseableDocumentIs400` really does assert nothing but the status,
so §3's invariant is as unenforced today as claimed. Both open questions are now closed, and six
things changed:

6. **The published surface has no `execute`** (§1). The two `execute(GraphqlRequest, ...)` overloads
   were the one way a consumer could route GET through the pipeline with no operation rule attached,
   quietly breaking the conformance the library exists to provide. Dropping them turns "GET always
   carries the spec's 405 rule" from a javadoc warning into a property of the type, and drops surface
   nothing has asked for. A consumer that must pre-parse its own body (multipart, its own
   `MessageBodyReader`) gets a fresh Backlog item and an additive overload when it asks. Test 14 pins
   the invariant that replaced the warning.
7. **The toggle is `builtInEndpointEnabled()`, not `defaultEndpointEnabled()`** (§6). "Default" named
   the thing the toggle changes rather than the thing it gates. Cheapest to settle before it is
   published SPI on a Maven-Central artifact. (The rationale first given here for the winning name
   was wrong on a checkable point; decision 13 corrects it.)
8. **One throwing gate, because `schema()` returns `String`** (§6). The draft said all five methods
   "return 404", which four of them can and the fifth cannot. `requireBuiltInEndpoint()` throwing
   `NotFoundException` gates all five uniformly and needs no change to a published return type.
   Test 22 asserts all five routes, `/graphql/schema` included.
9. **The factories validate the status family; `allowed()` is not published** (§2). The draft claimed
   `StatusType` retired the range check, which it does not: `StatusType` constrains nothing, so
   `queriesOnly(Response.Status.OK)` would have compiled into a rejection no client can read as one.
   The factories now reject anything outside `CLIENT_ERROR` / `SERVER_ERROR` (both, so `501` stays
   available for "no subscription transport here"), and test 11 enforces it. The `allowed()` accessor
   is dropped: `permits` answers the only question anyone asked.
10. **`resolveOperation` stays public, and stays on the handler.** Public on the consumer argument
    alone (metrics, logging, a consumer's own routing); the testability argument is the weaker half and
    should not be what carries it. It stays on the handler because it is a decode, and the handler is
    the decode boundary; on `OperationPolicy` it would conflate resolving the operation with judging
    it. The unit-tier class is renamed `OperationGuardTest`, which is what it actually covers.
    `allowing(Set, StatusType, String)` is kept as drafted: it is the general case of which
    `queriesOnly` is a specialisation, not a second mechanism, so the usual cost of surface bought
    ahead of demand does not apply.
11. **The change list gained the two prose sites it was missing, and the item gained a
    `Retired vocabulary` section.** `graphiql.html` and `tools/graphiql-build/README.md` both name
    `GraphqlResource` as the asset streamer, and neither is Java, so no gate can see them. More to the
    point, without a `Retired vocabulary` section the Done gate's retirement sweep is explicitly
    skipped, so this whole class of rot had no enforcer anywhere in the pipeline. Now it has one.

**Second pass.** The next reviewer found the first pass had left a mechanism claim unverified and a
naming claim ungrounded. Four more changes:

12. **The routing-overlap test drops the real `/graphql` overlap for a neutral `/probe` pair**
    (§7, test plan). The plan had the overlapping fixture "behind a `@TestProfile`", which does not
    scope it: a `@Path` class in test sources is registered from the build-time index for every
    `@QuarkusTest` deployment in the module, and a test profile selects config, alternatives and a
    build profile without changing the deployment's class set. Implemented as written, the fixture
    would have shadowed `/graphql/schema` and `/graphql/assets/*` for the whole module and broken
    `GraphqlResourceSmokeTest`'s page and asset cases. The neutral pair carries the same
    one-character literal margin, pins the same three consequences, needs no profile and no second
    boot, and cannot break a neighbour. §7 now records why the realistic paths are unavailable, so
    the next implementer does not restore them.
13. **The `builtInEndpointEnabled()` naming argument is corrected** (§6). The draft said "built-in" is
    the word the module docs and the example README already use. A grep says otherwise: those surfaces
    say "self-hosted", "out of the box" and "the `/graphql` resource", and none says "built-in". The
    name is still the better of the two on the merits, and the prose this item adds is what introduces
    the word, so the argument now says that instead of claiming support it does not have. That is the
    second naming rationale in this item falsified by a grep, which is a standing warning about
    asserting what the tree says without looking.
14. **The 404 claim is narrowed to the status** (§6). A thrown `NotFoundException` renders through the
    container's exception mapper; `Response.status(NOT_FOUND).build()` returns an empty entity. Same
    code, possibly different body, so the two gates are claimed to agree on the status only and test 22
    asserts nothing more.
15. **The consumer example gains the GraphiQL page arm.** It injected `GraphiqlBundle` and called only
    `asset`, so a consumer copying it served assets for a page it never served, and a browser sending
    `Accept: text/html` would get 406 from the JSON-only `@GET`. The example now matches both the
    built-in resource's shape and the execution-tier fixture's.
