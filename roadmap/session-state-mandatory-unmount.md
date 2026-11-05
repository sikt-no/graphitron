---
id: R1151
title: "Require an unmount on SessionState so identity never rests on a pooled connection"
status: In Review
bucket: bug
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-11-05
last-updated: 2026-11-05
---

# Require an unmount on SessionState so identity never rests on a pooled connection

## Goal

A request's identity no longer stays on a pooled connection after the request that mounted it has released it. Today the generated `SessionState` offers `of(Mount)`, a *mount* (the consumer's callback that sets the caller's identity on a connection when graphitron takes it, typically `set_config('app.user_id', ...)` read by row-level security) with no *unmount* (the matching callback that clears it at release). Its javadoc and the manual's "The unmount is optional" section justify that by saying the next request's mount overwrites the identity wholesale. That holds only when every later borrower of the pool's connection mounts every variable. It fails when an anonymous request's mount skips `set_config` for claims it does not have, when a variable is set on some requests only, and whenever anything else borrows from the same `DataSource`: escape-hatch operations, `@service` code using an injected `DataSource`, background jobs, health checks. Each of those runs as the previous caller, silently.

When this lands, `SessionState.of(mount, unmount)` is the only way to build a session state, so every connection graphitron releases has run the consumer's clear. A consumer still on `of(mount)` gets a compile error at the call site, and the manual says what to write instead (see *User documentation*). The guarantee is the consumer's unmount; the PostgreSQL reset that R1152 proposes stays a separate backstop for an unmount that is wrong, and is not what makes this safe.

Out of scope: connections graphitron never borrows. The escape-hatch path has no `SessionState` and its identity example is R1155's; the end-to-end test through a real pool is R1153's.

## Implementation

All generator changes are in `ConnectionRuntimeClassGenerator`; release already treats every session-identity build as one that unmounts (`pinnedConnection` sets `unmounts` from `emitsHookImplementation()`, independent of the state), so only the emitted `SessionState` and prose change.

- `sessionState(...)`: drop the `of(Mount)` factory. `of(Mount, Unmount)` keeps its `requireNonNull` on both. The emitted `unmount(...)` instance method calls the callback unconditionally (the `if (unmount != null)` guard goes, since the field can no longer be null).
- Javadoc that calls the unmount optional, all of it emitted or describing emitted code: the `SessionState` class javadoc ("an optional unmount callback"), the generator method's own javadoc (names `SessionState.of(mount)`), and the generated hook's `unmount` javadoc ("when it supplied one"). Each now says the state carries a mount and an unmount, and the unmount clears what the mount set.
- `graphitron-maven-plugin` `SessionStateBinding` javadoc ("an optional unmount callback").
- Test fixtures: `SessionStates.convertedTenant` in `graphitron-sakila-example` is the one mount-only caller. Its mount (`OrgTenantSessionIdentity.mount`) records the tenant and sets no database state, so it gains the no-op unmount `session -> {}`, which is also the documented pattern for such a mount.
- Docs: `docs/manual/reference/mojo-configuration.adoc` § Session identity (the `of` bullet, and "The unmount is optional" replaced as drafted below), the `docs/manual/how-to/tenant-scoping.adoc` example (gains an unmount), and `docs/architecture/reference/runtime-extension-points.adoc` ("a mount callback and an optional unmount callback").
- `roadmap/changelog.md` at Done: a `Breaking:` entry carrying the one-line migration (add an unmount that clears what the mount sets; `s -> {}` when the mount sets nothing).
- R1153's pool suite lists this item among the siblings that owe it a case. Once a mount-only state no longer compiles there is no case to write: R1153's own case 4 (a request with an unmount, then a borrower outside the engine) already pins the behaviour every state now has. So the debt is bookkeeping, whichever lands first. If R1153's suite has landed, delete the mount-only gap from its class javadoc; if not, delete R1151 from the sibling list in R1153's Scope section.

## User documentation (first-client check)

The `of` bullet in § Supplying it per request becomes:

> * The first callback is the mount. It runs on each connection graphitron takes, before any SQL on it, and returns the handle (or nothing, when no `<handle>` is configured). The second is the unmount. It runs when graphitron gives the connection back to the pool, and clears what the mount set. Both are required, and both may throw any exception.

"The unmount is optional" is replaced by:

> === The unmount is required
>
> When a request ends, graphitron gives its connection back to the pool, and the pool hands it to whoever asks next. That may not be a graphitron request: a health check, your own code using the same `DataSource`, a background job, an operation on the escape hatch. None of them mount, so they run as whatever identity the connection still carries. A graphitron request can be caught the same way when its mount sets only some variables, for example an anonymous request whose mount skips the claims it does not have. The unmount closes this: it runs before the connection goes back, so the next borrower always finds a connection with no identity on it. That is why `SessionState.of` takes both callbacks.
>
> Clear to the value your policies treat as "no identity" (on Postgres the empty string, not `NULL`; see below). If your mount sets no database state, for example it only records the request for auditing, say so with an unmount that does nothing:
>
> ```java
> var session = SessionState.of(s -> audit.record(claims), s -> {});
> ```
>
> If an unmount throws, graphitron evicts the connection instead of returning it to the pool.

The tenant-scoping how-to example gains `(s, handle) -> Routines.disconnect(s.configuration(), handle)` as its second argument, matching the reference example.

## Tests

- **Generator tier** (`ConnectionRuntimeClassGeneratorTest`): the emitted `SessionState` declares exactly one static `of`, taking `Mount` and `Unmount`, and passing a null unmount fails with `NullPointerException` naming `unmount`. This is what demonstrates the goal: a mount-only state cannot be built.
- **Execution tier** (`SessionHookExecutionTest`): retire `nextMountOverwritesWholesale_theMountOnlyContractsGround`. It pins the overwrite argument this item withdraws, and the clear it would be rewritten to show is already pinned by `facadeShape_mountsScopesReadsAndRoundTripsTheCompositeHandle`, which reads the probe table on the same connection after the generated unmount and finds nothing. Strengthen that assertion to also read `current_setting('app.user_id', true)` as the empty string, so it states what a borrower that never mounts sees, not only what RLS hides. Update the class javadoc's list of what it pins (it names "the next mount overwrites wholesale").
- The full verification build covers the fixture change: `graphitron-sakila-example` compiling is the check that no mount-only caller remains.

## Retired vocabulary

- `SessionState.of(Mount)` / `SessionState.of(mount)` as a single-argument factory.
- "The unmount is optional", "optional unmount" (callback), "mounts and never unmounts".
- The overwrite argument: "the next request's mount overwrites ... wholesale", "the next mount overwrites wholesale".
- `nextMountOverwritesWholesale_theMountOnlyContractsGround`.

The sweep covers the manual, `docs/architecture/`, generator javadoc and emitted javadoc strings, the maven plugin, and test javadoc. `docs/security.adoc` uses "overwrite" for a different point (any SQL on the connection can overwrite a GUC) and stays.

## Other solutions we've considered

- **Keep `of(mount)`, made safe by R1152's reset.** No API break, but the reset is PostgreSQL-only (R1152 is open on whether Oracle has a cheap equivalent) and R1152 is still considering an opt-out, so mount-only would be safe on one dialect and only while a backstop stays on. The consumer's unmount is the one mechanism that works on every database.
- **Rename it (`mountOnly(mount)`) with a sharper javadoc.** Leaves the safety condition, that nothing else borrows from the pool, for the consumer to verify, and a consumer cannot reliably verify it.
- **Wait for R1153's pool suite and add this item's case there.** It would hold a priority-1 security fix behind a test-infrastructure item, for a case that cannot be written once this lands (see the last Implementation bullet). The tests above pin the change itself.

A required unmount can still be `s -> {}` on a mount that does set state. The compile error forces a decision, not a correct clear; R1152 is what catches a wrong one on PostgreSQL.

## Provenance

Found in an adversarial review of the owned-connection session lifecycle, prompted by a consumer asking whether `app.claims` can leak between requests. Siblings filed from the same review: R1152 (reset at release), R1153 (pool execution tests), R1154 (single-tenant carrier acquisition after release), R1155 (escape-hatch identity example).

## Implementation notes

Landed in commit "R1151: SessionState takes a mount and an unmount, and nothing less". Every Implementation and Tests bullet above shipped as written, with two notes for the Done reviewer:

- The unreachable `mounts`-only clause in `PinnedConnection.release()`'s javadoc (named in Round 1 below) is gone with the rest of the retired vocabulary.
- R1153's pool suite had not landed, so the bookkeeping took that arm: R1151 is out of the sibling list in R1153's Scope section, which now names three gaps.

Still owed at Done: the `Breaking:` changelog entry described in the Implementation list.

## Reviewer findings

### Round 1: Spec → Ready, signed off (session_01FY5UvsFUbYCtxEERLrkDPp, 2026-11-05)

Read against trunk `a5b2aea`. The plan was written by session `01FY5UvuWneQFNFz7rjEhBEJ` (`22b2409`); this session wrote only this round and the status flip.

* **Goal well communicated and viable.** Yes. The first paragraph says what a consumer sees change (a mount-only `SessionState` no longer compiles; every released connection has run the consumer's clear) and why the overwrite argument it retires does not hold. Every edit site the plan names exists as described: `of(Mount)` with its `null` unmount, the `if (unmount != null)` guard, the three javadoc sites, `SessionStateBinding`, `SessionStates.convertedTenant` as the one mount-only caller, and the three doc pages.
* **Fits the architecture we have.** Yes. Release already unmounts on every session-identity build (`pinnedConnection` derives `unmounts` from `emitsHookImplementation()`), so the change narrows the emitted factory rather than adding a mechanism.

One site the Implementation list misses, which the retirement sweep catches: the `release()` javadoc in `pinnedConnection` carries a `mounts`-only clause ("the next request's mount overwrites this one's identity wholesale, the mount-only contract") inside the `!unmounts` arm. Since `mounts` and `unmounts` are the same fact, that clause is unreachable; drop it with the rest of the retired vocabulary.
