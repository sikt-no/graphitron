---
id: R1153
title: "Execution tests that identity never crosses requests through a real pool"
status: Spec
bucket: bug
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-11-05
last-updated: 2026-11-05
---

# Execution tests that identity never crosses requests through a real pool

## Goal

The claim "identity never crosses requests" is pinned by an execution test that would fail if it stopped holding, end to end, through a real connection pool. Today it is covered piecewise: the generator tier checks mount/unmount/evict ordering against fake connections, and `SessionHookExecutionTest` checks the example's identity routines on a single raw connection (mount scopes reads, unmount clears to empty, a throwing mount evicts). Nothing sends two users through the engine and a pool and checks what the second one sees. When this lands, a consumer asking "do you test that `app.claims` cannot leak between requests?" gets a yes that points at a test.

## Scope: what this item pins, and what it leaves to its siblings

This item pins the guarantees that hold today, so a regression in any of them fails the build. Three siblings each change the lifecycle and each owes this suite a case when it lands, not before: R1151 (a mount-only `SessionState` followed by a borrower that does not mount, which leaks today), R1152 (a consumer unmount that clears nothing, which leaks today), and R1154 (an acquisition after release in the single-tenant carrier, which orphans a mounted connection today). Writing those cases here would mean landing red tests or tests that assert the defect; both are worse than a named gap. The suite's class javadoc names the three gaps in prose (no item ids, per the javadoc convention) so a reader of the test knows what it does not yet prove.

## Fixture

Terms: the *owned-connection path* is the engine built by `GraphitronRuntime.newGraphQL(schema)`, where Graphitron takes a connection from the consumer's `DataSource`, runs the request's mount callback on it, and runs the unmount callback before returning it. The *physical connection* is the database session underneath the pool's wrapper; session variables such as `app.user_id` live on it and survive a return to the pool.

- **A real pool of one.** An Agroal `AgroalDataSource` (chosen over HikariCP; see Other solutions) (already on the example's classpath through `quarkus-agroal`; no new dependency) with `maxSize = 1`, built in the test's `@BeforeAll` and closed in `@AfterAll`. Size one is what makes "the next request got the previous request's physical connection" a certainty rather than a likelihood. The pool connects as a dedicated non-superuser role, because superusers bypass row-level security; the role name is this class's own (classes run concurrently in this module and roles are cluster-global, so it must not share `SessionHookExecutionTest`'s `rls_probe_user`).
- **The RLS table already in the catalog.** `secure_note` carries a read policy keyed on `nullif(current_setting('app.user_id', true), '')`, the fail-closed shape. The test seeds its own rows through the superuser context under owners only it uses (for example `pool-alice`, `pool-bob`) and deletes them in `@AfterAll`, per the execution tier's shared-database rule.
- **One new query field.** No query reads `secure_note` today (only the `createSecureNote` payload does). Add `Query.secureNotes: [SecureNote!]!` to `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`, a plain table field with no condition, so what a caller sees is decided by the policy alone. That is the point: a `$session`-bound condition would filter in SQL that Graphitron generated, and would pass even if the connection carried the wrong identity.
- **Identity through the example's own callbacks.** `SakilaSessions.of(claims)`, whose mount calls `session_connect` (session-scoped `set_config`, `coalesce(sub, '')`) and whose unmount calls `session_disconnect` (clears to `''`). An anonymous request is claims with no `sub`.
- **One helper, `identityOnNextBorrow()`.** Takes a connection from the pool outside the engine, reads `current_setting('app.user_id', true)`, returns it. The assertion everywhere is that it reads `NULL` or `''`. Paired with Agroal's `getMetrics().destroyCount()` before and after (the pool is built with `metricsEnabled(true)`; without it the counters read zero), so each case also states whether the connection it ran on was returned or evicted; an evicted connection trivially reads `NULL` on the replacement, and the case should say which of the two it is proving.

## Tests

One execution-tier class, `SessionIdentityPoolIsolationExecutionTest`, in `graphitron-sakila-example/src/test/java/.../test/querydb/`.

*The happy path.*

1. Alice queries `secureNotes`: sees exactly her rows. Then `identityOnNextBorrow()` reads empty and the connection was returned, not evicted.
2. Alice, then Bob, on the same physical connection: Bob sees only his rows.
3. Alice, then an anonymous request: sees no rows.
4. Alice, then a plain borrower that does not go through the engine at all: reads no rows from `secure_note`. This is the case the consumer's question is really about: anything else sharing the pool.

*Failure paths*, each run as Alice and followed by the next-borrow check and an anonymous `secureNotes` read that must see nothing. The fields come from the existing `MutationScopeService` fixtures, which write to `mutation_scope_marker` (the role needs `insert, select` on it and usage on its sequence):

5. A mutation field whose service throws after writing (`scopeWriteTwiceThenThrow`): the field rolls back, the connection is returned with identity cleared.
6. A mutation field whose commit fails at settle (`scopeWriteADeferredOrphan`, a deferred constraint).
7. A mutation field whose nested consumer transaction fails and is caught (`scopeNestedFailsCaught`).
8. A query field that throws without touching the database (`scopeQueryFailsWithoutDatabase`) next to one that reads `secureNotes`: the request mounts once and releases cleanly.
9. A rejected mount (claims `sub = "reject-me"`): `execute` throws `SessionMountException`, the connection is evicted (destroy count rises by one), and the next request as Bob sees only Bob's rows.
10. A cancelled request: `ExecutionInput.cancel()` (graphql-java 25) after the input is built and before `execute`, and again while a field is in flight. The in-flight cancel comes from a test-only instrumentation, passed through `newGraphQL(schema, instrumentations...)` so Graphitron's own stays first in the chain, that calls `parameters.getExecutionContext().getExecutionInput().cancel()` at `beginFieldFetching` of the second root field of a two-field query, after the first has read. In both, the next borrow reads empty. The straggler case, where a fetcher acquires after release, is R1154's and is out of scope here.

*Multi-tenant carrier.* The multi-tenant `TenantConnections` is separate emitted code with its own release loop, so one case runs there too: a request that touches two tenant sources, each a pool of one, then `identityOnNextBorrow()` on each pool reads empty. The tenant databases need the session objects `TenantSessionFixture.installSessionObjects` already installs; no RLS table is needed for this case, the variable check is the assertion.

## Documentation

`docs/architecture/reference/runtime-extension-points.adoc`, the hook state contract section, gains one sentence naming the suite as the execution evidence for "a connection is returned with identity cleared on every completion path", so the claim and its proof point at each other.

## Other solutions we've considered

- **A latch-blocking `@service` fixture for the in-flight cancel.** A fetcher cannot reach its `ExecutionInput`, so case 10 needs a seam. A service that blocks on a latch the test releases after calling `cancel()` would work, but it adds a field to the shared example schema for one test and its cancel point depends on thread timing. The test-registered instrumentation cancels at a fixed point and touches nothing shared.
- **HikariCP instead of Agroal.** HikariCP is what most consumers outside Quarkus run, but it shares the one property under test (a return to the pool does not reset session variables) and would be a new dependency. Agroal is already on the classpath and is what the example application deploys with.

## Context

Found in an adversarial review of the owned-connection session lifecycle. Related items: R1151 (mount-only `SessionState`), R1152 (reset at release), R1154 (post-release acquisition in the single-tenant carrier); each of those should add its case to this suite rather than to a separate test.


## Reviewer findings

### Round 1: Spec → Ready, changes requested (session_01WkW2JHW91m3TVr2w8wkx8z, 2026-11-05)

The goal reads cleanly on its own and is reachable; the plan extends the execution tier with existing fixtures (`MutationScopeService`, `SakilaSessions`, `TenantSessionFixture`, the `secure_note` policy) and the runtime's own `newGraphQL(schema, instrumentations...)` seam rather than standing anything parallel. The schema, routine, fixture and API names the spec gives all resolve in the tree. Three findings bear on whether the named tests would demonstrate the goal (gate question one); a fourth is non-blocking.

1. **Case 6 can pass while testing case 5's path.** `MutationScopeService.writeADeferredOrphan` inserts into `mutation_scope_deferred_child` as well as `mutation_scope_marker`, but the Fixture grants the probe role `insert, select` on `mutation_scope_marker` and its sequence only. Without `insert` on `mutation_scope_deferred_child` (and usage on its `serial` sequence) the orphan insert fails at statement time with a permission error, so the field fails before settle and the commit-time path the case names is never reached; the next-borrow check would still go green. The grant list needs the deferred child table, and the case should assert the failure it ran into was the deferred foreign key at commit (SQLState `23503`), not some earlier error.
2. **The multi-tenant case has no positive control and no named setup.** `TenantSessionFixture`'s `session_connect` mounts `coalesce(sub, '')`, so a request built as the existing tenant tests build theirs (`SessionStates.multitenant("{}")`) mounts the empty string, and `identityOnNextBorrow()` reading empty afterwards proves nothing. The single-tenant cases have their control built in (Alice sees her rows); this one needs a non-empty `sub` plus evidence the mount happened on each tenant connection before release (a `$session` read, or a read of `current_setting` inside the request). The spec also leaves the implementer to reconstruct what the case runs on: the multi-tenant engine is a separate generated package (`no.sikt.graphitron.generated.multitenant`, from `multitenant.graphqls`) built as `new GraphitronRuntime(defaultDataSource, Map<key, DataSource>, dialect)`; the tenant databases must be created by this class under names of its own (classes run concurrently, as the Fixture already notes for roles) and carry the tables of whichever tenant-routed field the request reads. Name that field, and say whether the default `DataSource` is a pool too.
3. **The cancel cases need to show the cancel took effect.** For the pre-execute cancel, if graphql-java aborts before the connection instrumentation's `beginExecution`, no connection is ever borrowed and the next-borrow check is vacuous; the case should state which outcome it expects and assert it (Agroal's `acquireCount()` delta alongside `destroyCount()`). For the in-flight cancel, assert the result carries the abort (an `AbortExecutionException` error, the second field unresolved), otherwise a graphql-java change that stopped honouring `cancel()` at that point would leave the case green while testing an ordinary completion. The ordering premise itself holds: single-tenant fetchers run synchronously on the calling thread (only the multi-tenant scatter uses `supplyAsync`), so the first root field has read before the second's `beginFieldFetching`; saying so in the spec would spare the implementer re-deriving it.
4. *(Non-blocking.)* Other solutions rejects the latch fixture partly because it "adds a field to the shared example schema for one test", which the plan also does with `Query.secureNotes`. The deciding reason is the fixed cancel point versus thread timing; leading with that alone would read more consistently.
