---
id: R629
title: "Mountable GraphQL-over-HTTP delegate with an explicit operation policy"
status: In Review
bucket: architecture
priority: 3
theme: runtime-connection
depends-on: []
created: 2026-08-11
last-updated: 2026-08-11
---

# Mountable GraphQL-over-HTTP delegate with an explicit operation policy

## In one paragraph

Stated in the tense it was planned in; "What landed" below is the present tense.

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

## What landed

All of it, in one pass. Every file below is on the branch; the reactor builds green with
`mvn install -Plocal-db`.

**New in `graphitron-jakarta-rest/src/main/java/no/sikt/graphitron/jakarta/rest/`:**

- `GraphqlHttpHandler`: the whole decode/decide/execute/encode pipeline as an `@ApplicationScoped`
  bean taking every Jakarta REST input as a parameter. Published surface is `post(String,
  HttpHeaders)`, `post(String, HttpHeaders, OperationPolicy)`, `get(String, String, String, String,
  HttpHeaders)`, `schema()`, and the static `resolveOperation(String, String)`, plus the
  `GRAPHQL_RESPONSE_JSON` constant. Its class javadoc carries the consumer example.
- `GraphiqlBundle`: `page(UriInfo)` and `asset(String)`, behind the existing `graphiqlEnabled()`
  gate.
- `OperationPolicy`: final class, three static factories, one published `permits` method, the
  package-private `Rejection` record and the package-private `SPEC_GET` instance carrying the
  specification's GET rule.

**Edited:** `GraphqlResource` is now five annotated methods, each the `requireBuiltInEndpoint()`
gate plus one delegating call, and the re-exported constant expression. `GraphitronApplication`
gained `builtInEndpointEnabled()` and the `WebApplicationException` contract on
`newExecutionInput()`'s javadoc. `GraphqlRequest`'s javadoc pointer, `META-INF/beans.xml`'s comment,
`modules.adoc`'s module row, the example README's app and testing sections, the `graphiql.html` head
comment, and both `tools/graphiql-build/README.md` occurrences all moved off `GraphqlResource`.

**What a reviewer should check, in rough order of how badly it would hurt to get wrong.**

1. **No entry point runs a request with no operation rule attached.** GET is routed with
   `SPEC_GET`, POST with the consumer's policy or `null`, and the nullable-guard entry point is
   private. That is the invariant that lets the published surface omit `execute` entirely.
2. **The pre-parse is conditional.** An unrestricted POST is never parsed by the pipeline, so a
   syntactically invalid document still comes back as graphql-java's `InvalidSyntax` result rather
   than this library's parse-failure wording. Both are 400;
   `GraphQLOverHttpConformanceTest.unparseableDocumentIsTheEnginesInvalidSyntaxResult` is the only
   assertion in the tree that can see the difference.
3. **The rejection is not legacy-downgraded**, because it is an HTTP-level rule rather than a
   GraphQL request error. Only the error body's media type follows negotiation.
4. **The gate throws.** `requireBuiltInEndpoint()` raises `NotFoundException` so `schema()`, which
   returns `String`, is gated the same way as the four `Response` methods, with no published return
   type widened for the gate's sake.
5. **The re-exported constant is still a constant expression**, so consumer `@Produces` annotations
   naming `GraphqlResource.GRAPHQL_RESPONSE_JSON` keep compiling.
6. **`isLegacy` is derived once per request** at the top of each public entry point and threaded
   down, not recomputed where it is needed.

## Coverage that landed

**Unit tier**, `OperationGuardTest` (12 cases, no container, no database): the guard as a decision
table over `resolveOperation` and `permits`. Resolution honouring `operationName` in a mixed
document (both directions), first-operation fallback, both unresolvable cases returning `null`, the
parse failure propagating, `allowing` as the read-write endpoint, the defensive copy of the
permitted set, and all four factory validations.

**Execution tier**, `MountedEndpointTest` (12 cases, `@QuarkusTest`), over a
`PolicyMountedGraphqlResource` fixture at `/env/{callingEnvironment}/graphql` shaped like the
javadoc example: the policy refusal on the wire with its status, media type and message; the same
mutation executing on the mount that passes no policy; a GET resolving to a mutation answering 405
with `Allow: POST` on *both* mounts; the fall-through to the engine; the parse failure; the legacy
non-downgrade; the asset base resolving under a templated mount; SDL under the same; the seam
observing what the resource method set; a `NotAuthorizedException` thrown before delegation
surfacing as 401; and all five built-in routes answering 404 with the toggle off while the mounted
endpoint keeps working.

**Execution tier**, `OverlappingMountTest` (3 cases, same deployment, no profile and no second
boot): Jakarta REST's root-resource sort pinned on `/probe` against `/probe/{p}`, whose
one-character literal margin is the margin `/graphql/` holds over `/graphql`. The three consequences
are the three the spec named (a declared sub-path taken by the templated class, a deeper sub-path
taken with the remainder as its own sub-path, and the bare path still reaching the literal class),
with the arithmetic carried in the assertion messages.

**Regression:** `GraphQLOverHttpConformanceTest` and `GraphqlResourceSmokeTest` pass with no edit to
any existing expectation, and the conformance suite gained the unparseable-POST case above.

## Deviations from the plan

Seven, none of which change the design.

1. **Test-plan case 10 moved from unit tier to execution tier.** The interpolated rejection wording
   is only reachable through `Rejection`, which is package-private, and the test classes live in
   `graphitron-sakila-example`. Asserting it on the wire is where it was observable:
   `MountedEndpointTest.refusalNamesTheRejectedOperationType` sends a subscription and requires the
   message to name one. The plan's point survives; only its tier moved.
2. **`allowing`'s `message` accepts `null`**, meaning the library's interpolated wording. That makes
   `queriesOnly(status)` literally `allowing(Set.of(QUERY), status, null)` rather than a parallel
   construction, so there is one message path instead of two. No signature changed.
3. **The consumer example is a bare `<pre>` with `&#64;` escapes, not `<pre>{@code}`.** Javadoc reads
   an `@` in a line's first column as a block tag even inside `{@code}` and silently truncates the
   class description there; the first draft of the javadoc lost the whole example that way, which
   the reference gate does not catch because it is not a broken link. A comment above the javadoc
   block records why the escapes are there.
4. **`FaultInjectingGraphitronApplication` gained two more test seams** (the toggle read off a
   request header, and the `CallingEnvironment` observation). Only one `@Alternative` adapter can be
   selected, so a second class was not available; its javadoc now says so.
5. **Three more test-source fixtures than the plan enumerated:** `CallingEnvironment` (the
   `@RequestScoped` holder that makes ordering observable), `ProbeLiteralResource` and
   `ProbeTemplatedResource` (the overlap pair).
6. **The example README's testing section needed one more sentence than the plan listed**, because
   it claimed everything under `app/` is a `@QuarkusTest` class and `OperationGuardTest` is not.
7. **The mutation used in the execution-tier cases is `searchManyMutation`**, a read-only `@service`
   mutation, so the unrestricted mount can execute one without writing to the example database.

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

The design sections these decisions cite were removed when the work shipped, since the code is now
the statement of what was decided. The section numbers survive in the text below and read: §1 the
two delegates and their published surface, §2 `OperationPolicy`'s shape, §3 no permissive policy
value, §4 operation resolution and smuggling, §5 `GraphqlResource` as a shell, §6 the built-in
endpoint toggle, §7 the templated mount and the routing overlap, §8 what does not change.

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
