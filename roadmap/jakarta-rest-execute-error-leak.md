---
id: R421
title: "jakarta-rest resource leaks internals on server-side execution failure"
status: Spec
bucket: bug
priority: 4
theme: mutations-errors
depends-on: []
created: 2026-07-02
last-updated: 2026-07-02
---

# jakarta-rest resource leaks internals on server-side execution failure

## In one paragraph

`GraphqlResource.execute()` shapes every *request* error (400/405/422) into a clean, spec-compliant
`application/graphql-response+json` body, but it runs `application.newExecutionInput()` and
`engine.get().execute(...)` with no guard. When either throws, the exception escapes the resource
entirely, past every spec-shaping branch, and lands in whatever the consumer's container does with an
uncaught exception. The observed case: a consumer's `newExecutionInput()` override eagerly resolves a
`DSLContext` from a `@RequestScoped` provider, which forces a JDBC connection; with the database down
that throws `jakarta.enterprise.inject.CreationException` wrapping a `PSQLException`, and Quarkus's
generic error handler renders a non-spec JSON body dumping the exception chain, full stack trace, DB
host/port, internal package names, and (in dev mode) a source snippet. The endpoint that is otherwise
careful to own its whole error surface hands its worst-case response to the container. This item makes
`execute()` catch escaping exceptions and return a generic, spec-compliant `500` that leaks nothing,
logging the real cause server-side under a correlation id.

## Why it matters

The module is "the first hand-written runtime artifact consumers depend on" (R399), serving Sikt's
gov/edu consumers. Two properties are at stake:

- **Information disclosure.** The leaked body exposes the internal package structure, the DB host/port,
  the driver, and the framework stack. Quarkus *prod* mode redacts most of this by default (empty
  `stack`, error-id-only `details`, no source snippet), so real-world exposure is usually limited, but
  the redaction is a per-consumer container default that can be reconfigured, not a property this
  library guarantees. The library must not depend on every consumer's container being configured to
  redact correctly.
- **Contract.** Even fully redacted, the escape produces a container error page, not
  `application/graphql-response+json` with an `errors` array. A GraphQL client hitting a subgraph whose
  context provider is down receives a non-spec response. The resource already owns the request-error
  watershed (its class Javadoc: "Owning parsing lets the resource shape parse errors as spec 4xx
  responses"); a server-side failure is the one hole in that ownership.

## Where it comes from

`graphitron-jakarta-rest/src/main/java/no/sikt/graphitron/jakarta/rest/GraphqlResource.java`, in
`execute()` (currently ~lines 230-245):

```java
ExecutionInput.Builder builder = application.newExecutionInput()   // (1) escapes unguarded
    .query(request.query())
    .operationName(request.operationName());
// ... variables / extensions ...
ExecutionResult result = engine.get().execute(builder.build());    // (2) escapes unguarded
```

Both `(1)` and `(2)` sit outside any try/catch. `(1)` is the observed path: the consumer-implemented
SPI `GraphitronApplication.newExecutionInput()` (documented as "per-request, auth-seeded") is free to
touch fallible resources, and graphitron cannot constrain what a consumer's override does. `(2)` is
included for completeness: graphql-java converts *data-fetcher* exceptions into field errors (a 200),
but an exception raised while building/validating the input before execution begins, or any unchecked
throwable from engine internals, would still escape here.

## Scope

**In scope.** Guard `(1)` and `(2)` in `execute()`. The guard is a two-arm catch: an ordered
`catch (WebApplicationException wae) { throw wae; }` first, then `catch (Exception)` (see *The
WebApplicationException passthrough* below for why the order is load-bearing). On any `Exception`
caught by the second arm: generate a correlation id, log the real cause server-side, and return a
generic, spec-compliant response, HTTP `500` in modern mode (media type
`application/graphql-response+json`; `200` + `application/json` for legacy clients, per fork 2), whose
body **reuses the redaction contract the generated `ErrorRouter` already emits** rather than inventing
a second one (see *Align with the existing redaction path* below):

```json
{ "errors": [ { "message": "An error occurred. Reference: <uuid>." } ] }
```

The correlation id rides *in the message text*, with no `extensions`, exactly as
`ErrorRouterClassGenerator.redactBody()` produces it, so a consumer's log-correlation tooling sees one
wire shape whether the fault was thrown inside a fetcher (ErrorRouter) or while building the input
(this resource). No exception message, class name, or stack in the response. Reuse `serialise()` /
`responseType()` / `errorBody()` directly: `errorBody(String message)` (`GraphqlResource.java:312`)
already takes an arbitrary message, so the redaction message passes straight through, no extension
needed. Add a conformance case to the `GraphQLOverHttpConformanceTest` suite in
`graphitron-sakila-example` (this module, `graphitron-jakarta-rest`, carries no `@Test` classes of its
own by R399; its coverage lives in the sakila-example conformance suite, which carries many authored
`@Test` classes including that one).

**Out of scope (name explicitly).**

- **The resolution-path message leak.** An exception thrown *during* data fetching is the
  generator/runtime domain, and it is already handled there: the generator does not use graphql-java's
  default `DataFetcherExceptionHandler` (which would copy `exception.getMessage()` into the field
  error); it wraps each fetcher in a try/catch that routes through the generated `ErrorRouter`, whose
  `redact` path logs + returns the correlation-id-only message (see `ErrorRouterClassGenerator`). Any
  residual default-handler exposure is a separate generator/runtime concern, not this HTTP resource.
- **Consumer/container hardening.** Whether a consumer runs Quarkus in prod mode or configures stack
  suppression is the consuming app's responsibility, not graphitron's.

## Why a second redaction site (not redundant with ErrorRouter)

The generated `ErrorRouter.redact()` already implements this exact "internal fault -> logged
correlation id, redacted client message" contract, but it is a *per-fetcher* catch that runs *inside*
graphql-java execution. The escape this item fixes is `newExecutionInput()`, which runs **before**
execution begins, so neither `ErrorRouter` nor graphql-java's own exception handling can ever see it.
This resource-level guard is the only site that can cover the pre-execution region; it is the
structural complement to `ErrorRouter`, not a duplicate of it. That distinction is the justification
for a second redaction site and should be stated as such in the code.

## Align with the existing redaction path

Because two sites now redact, they must produce **one** client-facing shape, or consumer
log-correlation tooling and clients reading the reference must handle both and they drift. Adopt the
`ErrorRouterClassGenerator.redactBody()` contract verbatim: message `"An error occurred. Reference: "
+ correlationId + "."`, no `extensions`, the id embedded in the message. The resource cannot reference
the generated `ErrorRouter` (wrong module; `redact` returns a `DataFetcherResult`, not an HTTP body),
so "one contract" is enforced by a Spec-level format decision plus a conformance assertion that the
fetcher-thrown path and the input-building path yield the same error shape, not by code reuse. If a
future change wants `extensions.errorId` instead, that is a change to the generated `redactBody()` too,
in scope of that change, not a divergence introduced only here.

## Design notes and constraints

- **Vendor neutrality is the hard constraint.** The module pulls only graphql-java plus `provided`
  Jakarta APIs (REST, CDI, JSON-B); CLAUDE.md forbids adding dependencies not pinned in the parent
  pom, and R416 already rejected Quarkus-only mechanisms (`META-INF/resources/`) on exactly this
  ground. So the fix must be a plain try/catch in the resource, **not** a JAX-RS
  `ExceptionMapper` provider (which would still be reasonable but is a second registration surface)
  and certainly not a Quarkus `@ServerExceptionMapper`. A try/catch inside `execute()` keeps the whole
  error surface in the one method that already owns it.
- **Logging goes through SLF4J, not `java.util.logging`.** `slf4j-api` (2.0.17) is already pinned in
  the parent pom's `dependencyManagement`, and the generated `ErrorRouter` already logs the redaction
  through `org.slf4j.Logger` (`LOGGER.error("Unmatched exception in fetcher; correlation id = {}",
  correlationId, thrown)`). Reusing SLF4J keeps both redaction paths on one logging surface (operators
  look in one place) and adds no *unpinned* dependency. Add `org.slf4j:slf4j-api` at `provided` scope,
  matching the vendor-neutral pattern the module's other three deps already follow; a consumer that
  runs a generated `ErrorRouter` already binds SLF4J at runtime. Log a parallel message, e.g.
  `"Uncaught exception building/executing GraphQL request; correlation id = {}"`.
- **The correlation id** lets an operator find the full cause in server logs while the client sees only
  the reference, the standard split. Generate with `java.util.UUID.randomUUID()` (JDK, no dependency).

## The WebApplicationException passthrough (the 4xx-vs-fault watershed)

`newExecutionInput()` is the auth-seeded SPI seam, so it can throw two very different kinds of
exception: a *genuine internal fault* (the observed DB-down `CreationException`, a true 500) and a
*client fault* (an authentication/authorization failure while seeding the builder, a 401/403). A naive
single `catch (Exception)` would redact **both** to a generic 500, silently reclassifying a real
401/403 as an internal error, exactly the request-error-vs-fault watershed the rest of this resource
is built to own (its Javadoc: it owns "the request-error-vs-field-error status watershed").

The reconciliation is an **ordered two-arm catch**, and the order is load-bearing:

```java
try {
    // build input via application.newExecutionInput(), then engine.execute(...)
} catch (WebApplicationException wae) {
    throw wae;                       // let JAX-RS map it to the consumer's intended status
} catch (Exception e) {
    // redact: correlation id + log + generic spec-compliant body
}
```

A consumer wanting auth-failure-as-4xx throws a Jakarta `WebApplicationException` (or a subtype, e.g.
`NotAuthorizedException` -> 401, `ForbiddenException` -> 403) from its adapter's `newExecutionInput()`.
Because that catch runs **inside** `execute()`, JAX-RS exception mapping only sees the exception if it
*propagates out of the resource method*, so the first arm must re-throw `WebApplicationException`
unredacted; the container then maps it to the status the consumer chose. Only the second arm redacts.
`WebApplicationException` is a `RuntimeException` (hence an `Exception`), so without the ordered first
arm the redaction arm would swallow it, which is the bug this section exists to prevent. The type is
`jakarta.ws.rs.WebApplicationException`, already on the classpath via the module's `provided`
`jakarta.ws.rs-api` dependency, so honoring the passthrough adds no dependency and names no
RESTEasy/Quarkus type. This is the deliberate division: the resource redacts genuine faults; the SPI
seam, via `WebApplicationException`, carries any client-facing 4xx. (A full mirror of the generator's
`GraphitronClientException` surface-vs-redact model is out of reach here anyway: the resource is
vendor-neutral and cannot name that generated type.)

## Open design forks (resolve during In Progress)

1. **`Exception` vs. `Throwable`.** The redaction arm catches `Exception`, so `Error` (e.g.
   `OutOfMemoryError`) propagates, the usual JVM convention, and `WebApplicationException` is handled
   by the earlier arm (see above). The leak we saw is an `Exception`. Recommendation: catch
   `Exception` in the redaction arm, let `Error` through.
2. **Status for legacy clients.** The legacy `application/json` mode is always-200 across the resource
   (see `legacyApplicationJsonIsAlways200`; `requestError` maps every modern status to 200 for legacy).
   Silently emitting 500 for legacy would break that invariant; silently emitting 200 hides a real
   fault. **Recommendation:** mirror the existing rule, 200 + the generic `errors` body for legacy,
   500 for modern, so the fourth disposition follows the same watershed as the other three rather than
   forking it. (This applies only to the redaction arm; a re-thrown `WebApplicationException` is mapped
   by the container regardless of media type.) Confirm this is the intended reading.

## Testing

Add cases to `GraphQLOverHttpConformanceTest` (RestAssured + `@QuarkusTest` against the sakila
reference app, the same harness the existing 400/405/422/200 cases use). The primary case drives a
request that makes `newExecutionInput()` throw, then asserts: modern mode returns `500` with
`application/graphql-response+json`; the body has an `errors` array whose message is the
`"An error occurred. Reference: <uuid>."` shape; and the body contains none of the exception's
internals (no class name, stack frame, or `localhost`/port substring). This needs a **fault-injection
seam** in the sakila adapter (e.g. a sentinel query, header, or operation that makes the adapter's
`newExecutionInput()` throw on demand); choosing the least-intrusive seam is part of In Progress. A
legacy-mode variant asserts the 200 + generic `errors` body per fork 2. A third case asserts the
`WebApplicationException` passthrough: when the fault-injection seam throws a `WebApplicationException`
(e.g. `ForbiddenException`), the response carries the container-mapped status (403), **not** a redacted
500, proving the ordered first catch arm re-throws rather than swallows. Where practical, also pin that
the input-building-throw response shape matches the fetcher-throw redaction shape (the existing
`Film.durabilityError` @service leaf exercises the fetcher path), so the single-contract requirement of
*Align with the existing redaction path* is enforced by a test rather than by convention.

## Done when

- `execute()` guards both the input-building and execution calls with an ordered two-arm catch: a
  `WebApplicationException` (and subtypes) is re-thrown unredacted so the container maps its status; no
  *other* `Exception` escapes the resource.
- The 500 (modern) / 200 (legacy) response is spec-compliant `errors` JSON whose single error uses the
  same `"An error occurred. Reference: <uuid>."` shape the generated `ErrorRouter` emits, no extensions,
  no exception message/class/stack.
- The real cause is logged server-side under that id via SLF4J.
- Conformance cases in `GraphQLOverHttpConformanceTest` assert: the sanitized redaction response and the
  absence of any leaked internal string (no class name, stack frame, or `localhost`/port substring); the
  `WebApplicationException` passthrough (a thrown `ForbiddenException` yields the container-mapped 403,
  not a redacted 500); and that the input-building-throw shape matches the fetcher-throw shape.
- The only dependency added to the module's pom is `org.slf4j:slf4j-api` at `provided` scope (already
  version-pinned in the parent `dependencyManagement`); no unpinned dependency and no RESTEasy/Quarkus
  type is introduced.
