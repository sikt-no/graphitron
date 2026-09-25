---
id: R975
title: "Routed tenant acquisition: authorize the key and hand it to the session mount"
status: Spec
bucket: bug
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-09-25
last-updated: 2026-09-25
---

# Routed tenant acquisition: authorize the key and hand it to the session mount

## Goal

Under database-per-tenant routing (a `<tenantColumn>` build, where a field's tenant value is divined from its arguments, a node id or its parent and selects that tenant's `DataSource`), a routed field acquires a connection only for a tenant the request is authorized to use, and the `<sessionState>` mount learns which tenant it is mounting for. Today neither holds: a caller can route to any hosted tenant by naming it in an argument, and the mount receives the same payload for every tenant, so a consumer whose session identity is per tenant (Sikt's sis: VPD institution, roles and RAS realm all set per institution) can neither refuse the request nor mount the right identity. For sis this is a security blocker for going live, not a convenience.

When this lands, every `<tenantColumn>` build's request factory takes the set of tenants the request may touch, graphitron refuses any routed field whose tenant is outside it before a connection is taken, and a mount method may declare an `Optional<K>` parameter (`K` being the tenant column's Java type) that receives the tenant it is mounting for:

```java
public static RasHandle mount(Configuration cfg, Optional<Long> institusjonsnr, Claims claims) { ... }

ExecutionInput input = Graphitron.newOwnedExecutionInput(permittedInstitutions(jwt), claims)
    .query(query).build();
```

## Premises (verified against the generator source, 2026-09-25)

- **Routed acquisition checks hosting, not authorization.** The generated `GraphitronRuntime.acquireForTenant(tenantKey, payload...)` (emitted by `ConnectionRuntimeClassGenerator`) throws `NoSuchElementException` only when `sourcesByTenant` lacks the key. The request's tenant collection, written into the GraphQL context under `TenantConnections.FAN_OUT_TENANTS_KEY`, is read in exactly one place: `TenantConnections.fanOutDomain`, the fan-out path the manual's tenant-scoping how-to calls "the authorization pre-filter". The carrier's routed path (`TenantConnections.entryFor(Optional<K>)` into `runtime.acquireForTenant`) never consults it.
- **The collection exists only with `@tenantFanOut`.** `GraphitronFacadeGenerator` adds the `Collection<K> fanOutTenants` factory parameter only when `schema.hasFanOutBinding()`, and `GraphitronDevExecutorGenerator.ownedFactoryArgs` mirrors that gate. A routed-only schema has nothing to check against.
- **The mount never sees the key.** `ServiceCatalog.reflectSessionHook` classifies a mount parameter as the seam by type (`Configuration`/`Connection` into `ParamSource.SessionSeam`) and every other one as payload (`ParamSource.Context`, which becomes a factory contextArgument). The generated `SessionHookImpl.mount(connection, dialect, settings, payload...)` and `PinnedConnection.acquire` carry no key, and the carrier holds one payload for the whole request, so encoding the tenant in the payload cannot work: under fan-out one payload reaches every tenant's mount.
- **The output side is already per tenant.** A carrier entry (one pinned connection plus its `DSLContext`) is per tenant key, and the `$session` handle a service reads is the handle of the connection its call runs on. Only the input side lacks the key.
- **Client-facing refusal has a precedent in the carrier.** `TenantConnections`' tenant-agreement check (`divinedTenantAgree`) throws the generated `GraphitronClientException`, which `ErrorRouter.surfaceClientErrorOrRedact` surfaces unredacted.

Why sis needs both halves: one Oracle database hosts several institutions under VPD (Oracle's row-level security), and RAS users share one `DataSource` per database across all its institutions. The sis mount first calls `FS.PK_VPD.set_instnr(<institution>)`, the only thing separating two institutions on that shared pool, then enables the user's roles at that institution and sets the per-institution person number that RAS realm predicates filter on. Without the key the mount can only guess, and a guess mounts institution A on a connection serving institution B. sis v9 checked `harTilgangTil(institusjonsnr)` before opening a connection and passed the institution to its session setup; this item gives graphitron the same two guarantees.

## Implementation

### The request tenant set (authorization)

- **One model fact gates the parameter.** The request tenant set is present exactly when `schema.tenantScopes()` is `TenantScopes.Configured`, in every such build, whatever its bindings. Resolve that once as a model fact (a `GraphitronSchema` accessor replacing the `hasFanOutBinding()` reads at the factory and dev-executor gates) so the facade, the instrumentation, the carrier and the dev executor cannot disagree. Gating on "has a routed or fanned binding" is rejected: it would put an evolving schema property on a public factory signature.
- **Factory.** `GraphitronFacadeGenerator` emits the `Collection<K>` parameter on both factory forms under the new gate, renamed from `fanOutTenants` to `tenants`, null-checked as today, and written under the renamed context key `TenantConnections.TENANTS_KEY`. There is no null or "unrestricted" meaning: a caller that may see everything passes the hosted set explicitly (`runtime.tenantKeys()`).
- **The carrier owns the set.** `GraphitronConnectionInstrumentationGenerator` decodes the context value once in `beginExecuteOperation`, copies it into an immutable `Set<K>` (so membership is well defined and O(1) whatever collection the caller passed), and hands it to the `TenantConnections` constructor beside the payload. `fanOutDomain` reads the carrier's set instead of the context, so the context key is a write-once boundary detail and fan-out and routing read one fact.
- **The check sits at mint time in `entryFor`.** For a present key, `entryFor` checks membership in the request set first, before the per-key `computeIfAbsent`, so only authorized keys ever enter the entry map; a refused key throws `GraphitronClientException` with a message naming the refusal but not whether the tenant is hosted. The membership check runs before the hosting check in `acquireForTenant`, so an unauthorized caller cannot probe which tenants a deployment hosts. Every keyed acquisition already funnels through `entryFor` (static `dslFor(env, key)`, the instance `dslFor(key)`, and the scatter worker); the implementation confirms no generated site calls `runtime.acquireForTenant` directly, and the check's javadoc states that invariant. `Inherited` children re-check a key their parent already passed, which is redundant and harmless.
- **Batches fail per tenant group.** The per-row dispatch surfaces (`node`/`nodes` via `QueryNodeFetcherClassGenerator`, `_entities` via `EntityFetcherDispatchClassGenerator`) partition a batch per decoded tenant. A refused tenant must fail only its own group's elements, with the path-bearing client error, not the whole batch. The implementer establishes how a throw from `entryFor` inside one partition surfaces today and makes it per-group if it is not; the mixed-batch execution test below pins it.
- **Dev executor.** `GraphitronDevExecutorGenerator` runs single-connection with no tenant map, so no routed field can execute there; it passes an empty set, as it passes `List.of()` today, and a routed field fails with the refusal rather than the unhosted-tenant error. The MCP `execute` tool runs through this executor and needs no change of its own.

### The tenant slot on the mount (identity)

- **Recognition by type.** In a `<tenantColumn>` build, `reflectSessionHook` classifies a mount parameter typed exactly `java.util.Optional<K>`, with `K` the boxed catalog tenant type, into a new `ParamSource.SessionTenant` arm: not payload, not a factory slot. This follows the `SessionSeam` precedent in the same method (seam by type, unmount handle by position). `resolveSessionHooks` takes the tenant type as a `TypeName` (null for single-tenant builds), not the `TenantScopes` object; `GraphitronSchemaBuilder` already has `ctx.tenantScopes` when it calls it.
- **Rejections, as typed `ReflectionError`s drained through the existing `<sessionState>: ` channel:** two `Optional<K>` parameters; and any other `Optional`-typed mount parameter in a `<tenantColumn>` build (`Optional<String>` against an `Integer` column, `Optional<Long>` against `Integer`), which would otherwise fall through silently into a payload slot, and which has no sensible meaning as a contextArgument anyway. In a single-tenant build nothing is recognised as the tenant slot and an `Optional` parameter stays payload, as today. A bare `K` parameter stays payload in every build, so an `Integer userId` payload in an `Integer`-tenant build is not taken for the key; the manual says so.
- **Resolved fact.** `SessionHooks` gains `tenantSlot()` beside `payloadParams()`, returning the slot parameter when present, so emitters read one resolved fact instead of each filtering on the arm; `payloadParams()` stays `Context`-only and therefore unchanged.
- **Emission.** `SessionHookImpl.mount` and `PinnedConnection.acquire` gain an `Optional<K> tenant` parameter in multi-tenant builds (after `settings`, before the payload), and `hookCallArgs` spreads it into the mount's own declared position when the mount declares the slot, dropping it otherwise. `GraphitronRuntime.acquireForTenant` passes `Optional.of(tenantKey)` and `acquire` passes `Optional.empty()`. The dev executor's preflight passes `Optional.empty()`. Single-tenant builds emit exactly what they emit today.
- **Unmount does not take the key.** The handle is already the per-entry output; a mount that needs the tenant at unmount puts it in the handle it returns.
- **Pool keying is per key.** Several tenant keys may map to one `DataSource`; each key still gets its own carrier entry, its own pinned connection and its own mount with its own key, so session identity is per (connection, tenant). This is today's behaviour; the manual states it.

### Documentation

`docs/manual/how-to/tenant-scoping.adoc` §2 gains the request tenant set and the tenant slot, and §3 drops its fan-out-only framing of the collection. `docs/manual/reference/mojo-configuration.adoc`'s session-identity section gains the tenant slot beside the seam and payload. The `@tenantFanOut` reference page's factory wording follows the rename.

## User documentation (first-client check)

Draft for `tenant-scoping.adoc` §2, after the `GraphitronRuntime` constructor example:

> Every request states which tenants it may touch. The generated factories take the set as a parameter ahead of your mount's payload, typed as a collection of your tenant key type, so leaving it out is a compile error:
>
> ```java
> Set<Long> permitted = permittedInstitutions(jwt);   // your derivation from the caller's claims
> ExecutionInput input = Graphitron.newOwnedExecutionInput(permitted, claims)
>     .query(query).build();
> ```
>
> Graphitron checks every tenant an operation routes to against this set before it takes a connection. A field whose tenant is outside it, whether named by an argument, decoded from a node id or handed down from a parent, fails with a client error and no connection to that tenant is opened. In a batch spanning tenants (`nodes`, `_entities`), only the refused tenant's elements fail. The same set bounds `@tenantFanOut` fields (see §3). A caller entitled to everything passes the hosted set, `runtime.tenantKeys()`; there is no "unrestricted" value.
>
> Your mount can learn which tenant it is mounting for. Declare a parameter typed `Optional` of your tenant key type, anywhere in the signature:
>
> ```java
> public static RasHandle mount(Configuration cfg, Optional<Long> tenant, Claims claims) {
>     long institution = tenant.orElseThrow();   // or handle the default source
>     ...
> }
> ```
>
> A connection routed to a tenant receives that tenant; the default source, which serves your global tables, receives `Optional.empty()`. The parameter is not part of the payload and does not appear on the factory. Graphitron recognises it by type alone, so any other `Optional` parameter on a multi-tenant mount is a build error, and a plain `Long tenant` parameter is ordinary payload that your factory will ask for. Tenant keys that share one `DataSource` still get one connection and one mount each, each with its own key. A mount that throws fails the request closed and evicts the connection, so it is also the place for any per-tenant check the tenant set cannot express.

## Tests

- **Execution tier, `TenantDivinedRoutingExecutionTest`** (counting `DataSource`s and `TenantSessionFixture` already in place): a caller whose set is `{1}` running `films(filmId: 2)` gets the refusal error, and `TENANT_2_OPENED` stays zero; the same caller routing through a node id decoded to tenant 2 is refused the same way, which also pins that a NodeId-sourced key compares equal to the set's boxed elements; a `nodes` batch spanning tenants 1 and 2 under set `{1}` returns tenant 1's element and fails only tenant 2's, with tenant 2's database never opened. The existing tests pass `List.of(1, 2)` and keep passing.
- **Execution tier, mount receives the key:** the multitenant execution (`rewrite-generate-multitenant` in `graphitron-sakila-example/pom.xml`) gets its own `<sessionState>` pointing at a new facade in `graphitron-sakila-service`, `mount(Configuration cfg, Optional<Integer> tenant, String claims)`, which records the key it received and binds it into the `tenant` field `session_claims` already carries. A test asserts one mount per routed tenant with that tenant's key and `Optional.empty()` for a default-source field, and a second runtime mapping keys 1 and 2 to one `DataSource` asserts two mounts carrying 1 and 2. The plugin-level facade stays as it is for the single-tenant executions.
- **Unit tier, reflection:** `ServiceCatalog` session-hook tests cover `Optional<K>` classified as `SessionTenant`, two `Optional<K>` rejected, `Optional<String>` and `Optional<Long>` against an `Integer` column rejected, `Optional<Integer>` in a single-tenant build staying payload, and a bare `K` staying payload.
- **Pipeline tier:** `SessionHookImplGeneratorTest` and `TenantRuntimeKeyTypeTest` pin the tenant parameter on `mount`/`acquire` and its spread position; `TenantConnectionsGeneratorTest` pins the membership check ahead of the entry-map mint and `fanOutDomain` reading the carrier's set; `TenantFanOutFetcherPipelineTest` and the facade tests pin the renamed parameter on a routed-only multi-tenant schema.

## Retired vocabulary

- `FAN_OUT_TENANTS_KEY`, `FAN_OUT_TENANTS_KEY_FIELD`, `FAN_OUT_TENANTS_KEY_VALUE` (`no.sikt.graphitron.request.fanOutTenants`)
- `fanOutTenants` (factory parameter), and "fan-out tenant collection" in prose
- `hasFanOutBinding` as the factory-parameter gate

## Other solutions we've considered

- **Only hand the key to the mount, and let the consumer authorize there.** It reproduces sis v9's semantics, but leaves the invariant with no enforcer: a mount that declares no tenant slot (legitimate where identity is tenant-independent) routes fail-open, and fan-out and routing would answer "may this request touch tenant X" from two sources. The mount slot stays for identity; authorization is graphitron's.
- **A `<tenantParameter>` element in `<sessionState>`.** It restates a fact the signature already carries and needs its own rejections for missing, mistyped and single-tenant cases. Recognition by type matches how the seam is already recognised.
- **Passing the key as a bare `K`, with `null` for the default source.** It would take legitimate `K`-typed payload for the key and puts a null on the consumer's surface; `Optional<K>` is both unambiguous and honest about the default source.

## Related

- R468 (Oracle/RAS execution coverage for session mounts) is adjacent: the sis mount is its load-bearing worked example.
- `tenant-fanout-argument-narrowing` concerns narrowing the fan-out domain by argument and reads the same request set this item moves onto the carrier.
- Out of scope, filed separately by the sis session: `@service` fields neither divining nor stamping a tenant, and a self-FK `@reference` on UPDATE writing the tenant column in `SET`.

## Reviewer findings

### Round 1: Spec → Ready, revisions requested (session_01WaMqpfyJp6Gkfm6iJhGQtQ, 2026-09-25)

Question 1 passes: the goal reads on its own, and the premises hold against the tree. Question 2 passes for the single-key path (the membership check in `entryFor`, the carrier owning the set, the `SessionTenant` arm, the emission changes). Two parts of the plan are not yet something to hand to an implementer as-is.

1. **Per-tenant batch failure is left to the implementer, and the code already answers the question the spec leaves open (question 2).** The Implementation bullet "Batches fail per tenant group" asks the implementer to establish how a throw from `entryFor` inside one partition surfaces today, and to make it per-group if it is not. It is not. `HandleMethodBody.emitGroupDispatch` calls `TenantConnections.dslFor` inside the per-tenant loop with no catch, so a throw escapes `handle<Type>` and then `resolveByReps`, and the whole batch fails. `EntityFetcherDispatch.resolveByReps` returns an `Object[]` of `Record`-or-`null`, which has no per-element error channel. `QueryNodeFetcher.dispatchNodes` joins every loader future, so one failed element fails the whole `nodes` field. Delivering the behaviour the user-docs draft promises ("only the refused tenant's elements fail") therefore changes the contract of a dispatcher shared by `node`, `nodes` and `_entities`, and each of its callers. That is the design this gate exists to review, and it should not be settled mid-implementation. The spec should state the per-element failure channel and how each caller turns it into a null element plus a path-bearing error. The fan-out path's `FanOutFailure` marker, collapsed by `collapseFanOut`, is the in-tree precedent to extend or rule out. While settling that, also decide whether a refused `node`/`nodes` id is an error or a Relay "unknown id" `null`: the plan's own rule that a caller must not be able to probe which tenants a deployment hosts bears on that choice. Alternatively, scope batch refusal down to failing the whole batch closed (the way `agreeOnTenant` already rejects disagreement), file per-group failure as its own Backlog item, and align the user-docs draft and the mixed-batch test with that.

2. **One set, two meanings (questions 1 and 2).** The plan's premise is that fan-out and routing "read one fact". But `fanOutDomain` treats a named tenant the deployment does not host as a request error (its javadoc: the set is "the statement that data could exist there"), while the plan redefines the set as "the tenants the request may touch", an authorization set, and the first-client draft passes `permittedInstitutions(jwt)` straight through. In a schema with any `@tenantFanOut` field, a caller permitted at an institution this deployment does not host would then fail every fan-out, while routing ignores that same tenant. The Documentation section only says §3 "drops its fan-out-only framing". The spec should say which meaning the set carries and reconcile the two. Either keep the unhosted-tenant rule, and have the draft and §3 say that the caller passes its permitted set narrowed to the hosted tenants, and why. Or have `fanOutDomain` intersect silently, with the reason the completeness argument no longer holds.

Non-blocking:

- The escape-hatch factory now carries the set in every `<tenantColumn>` build, but on that path nothing reads it: `TenantConnections.of(env)` throws before any routed or fanned field runs. Today's fan-out gate has the same property, so this is not new. One sentence saying the symmetry is deliberate would save the implementer the question.
- Say what `beginExecuteOperation` does when the context holds no set (an `ExecutionInput` built without the generated factory). Failing closed there keeps "no unrestricted meaning" true beyond the factory. Today that is `fanOutDomain`'s `IllegalStateException`, raised at field time.
