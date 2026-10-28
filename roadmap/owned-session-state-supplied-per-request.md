---
id: R1120
title: "The POM switches session identity on; the consumer supplies it per request as code"
status: Backlog
bucket: feature
priority: 4
theme: runtime-connection
depends-on: []
created: 2026-10-28
last-updated: 2026-10-28
---

# The POM switches session identity on; the consumer supplies it per request as code

## Goal

On the owned-connection engine, the plugin configuration only switches session identity on, and the consumer supplies the identity per request as ordinary code passed to the generated input factory:

```java
var input = Graphitron.newOwnedExecutionInput(
        SessionState.of(
            session -> SessionIdentity.mount(session.configuration(), environment, authentication),
            session -> SessionIdentity.unmount(session.configuration())))
    .query(query)
    .build();
```

`environment` and `authentication` are the consumer's own values, captured by the lambdas when the input is built. Graphitron calls the mount lazily, on whatever thread first needs a connection, exactly as today; the values travel with the callback, so no scope or ambient state has to be active there. `session` is a generated type holding what graphitron knows about the connection being mounted: the provider-free `Configuration`, and in a multi-tenant build the tenant. Everything else the mount needs comes from the consumer's own code.

Owned and motivated by fs-plattform (tilgangsstyring !6441 on 10.0.0-RC46, opptak migrating). graphitron-jakarta-rest's simpler flow (R1098) builds on this item.

## Problem, as it stands

`<sessionState>` does two jobs with one mechanism: it switches the feature on in generation, and it plugs consumer code in by naming static methods (`<mount>fqcn#method</mount>`, `<unmount>…</unmount>`) that generation reflects on. The second job makes the contract fragile:

* **The factory is derived from a method signature.** Every mount parameter after `Configuration` becomes a parameter of `newOwnedExecutionInput`, sorted alphabetically, named after the method's own parameter names (so the module must compile with `-parameters`). In tilgangsstyring the mount is `mount(Configuration, String callingEnvironment, AuthenticationResult.Success authentication)` and the factory becomes `newOwnedExecutionInput(AuthenticationResult.Success authentication, String callingEnvironment)`. Renaming or reordering a mount parameter silently changes a generated API in another module; two parameters of the same type can be swapped at the call site and still compile. The consumer's javadoc has to explain the coupling, which is the smell.
* **The consumer's classes must exist before generation.** Generation loads the mount class, so it and every type in its signature must sit in a reactor sibling. tilgangsstyring moved `SessionIdentity`, `AuthenticationResult` and `Orgtilganger` from `tilgangsstyring-app` to `tilgangsstyring-service` for that reason alone.
* **The mount is static.** It can use nothing but its parameters, so anything it needs becomes payload, and payload becomes factory parameters. opptak's identity resolution needs provisioning services and configuration; as statics they would have to be threaded through the factory.
* **Build-time checks happen by reflection** in the plugin, where the compiler could do them in the consumer's own code.

R1101 met the same limit for its mutation hook and moved it to the runtime as an emitted functional interface (`MutationHook`, `GraphitronRuntime.withMutationHook`), rejecting the reflected static form because a class compiled before generation cannot see emitted types. This item does the same for session identity, per request.

## Decisions

The four questions the Backlog text left open, answered:

* **The handle type is optional data in the switch.** `<sessionState><handle>fqcn</handle></sessionState>` names the type a mount callback returns; omit it and the mount is handle-less (a `Consumer`-shaped callback, no `$session` to bind). Generation loads only that one class, a leaf value type (a record such as opptak's `Identitet`, or a jOOQ UDT record), to read its members for `$session.<member>` bindings and to type the emitted callback; it never loads the mount code or the types the mount's signature mentions, which is what forced the module move. The alternatives lose something real: a generic `SessionState<H>` checked at runtime moves a build-time error to production, and dropping the handle strands `$session` consumers. This is the one place the "classes must exist before generation" constraint survives, now confined to a type the consumer chose to bind from, and the docs say so. The handle's declared members are what the existing member decoding reads; see Relation to other items.
* **Context arguments stay positional and alphabetical.** With the mount payload gone, the biggest source of same-typed swaps (the mount's own parameters, which the consumer controlled by renaming) is removed; what remains are the schema's declared `contextArguments`, unchanged by this item. A named record or builder for them is a separate, schema-wide change and gets its own item when this lands.
* **R1101 and this item deliberately differ, and the docs say why.** `MutationHook` is application-wide behaviour registered once on the runtime; it receives the request as a `GraphQLContext` view because it must read values the request carries. A session callback is a per-request value-bearing object whose inputs the consumer already holds when it builds the input, so it captures them by closure and travels in the context under its own typed key. Forcing one shape on both would make the hook look up what the callback already holds, or make the callback re-register per request.
* **The escape hatch is unchanged.** `newExecutionInput(dsl, ...)` neither takes nor reads a `SessionState`; the consumer owns identity there. `mojo-configuration.adoc` says so beside the new form.

One question the Backlog text missed: the dev tools (`mvn graphitron:dev`, the MCP `execute` tool) construct the mount's payload from `<devDatabase><claims>` through the reflected signature, so a callback build gives them nothing to call. Decided in Dev tooling below.

## Plan

### Configuration

* `SessionStateConfig` gains a third arm, `Callbacks(Optional<String> handleType)`, strings only like its siblings. A `<sessionState>` block with neither `<mount>` nor `<unmount>` yields it; `<handle>` is read only on this arm. `<handle>` beside `<mount>`, or an `<unmount>` without a `<mount>`, fails the build naming the element, as the existing shape rejections do. The form is therefore chosen by presence: `<mount>` set is the method-reference form, absent is this one.
* An execution that inherits a plugin-level `<mount>` and writes a bare `<sessionState/>` merges to the method-reference form; the docs name `combine.self="override"`, which the sakila pom already uses for the same reason.
* `SessionStateWarnings.forConfig` yields no advisory for `Callbacks`, as for `MethodHooks`; it adds a deprecation warning for `MethodHooks` (see Breaking change).

### The resolved carrier

* `SessionHooks` keeps the handle axis as its seal and gains the supplied arms beside the reflected ones: `SuppliedHandleLess` and `SuppliedHandled(TypeName handleType)`. `resolveSessionHooks` loads the `<handle>` class and reflects nothing else; an unloadable class drains as a schema-wide `ValidationError`, as a mount reflection failure does today, and mints `NotConfigured`.
* The mount-derived defaults (`mountRef`, `unmountRef`, `payloadParams`, `tenantSlot`, `stringConstructiblePayload`) are empty on the supplied arms, so nothing downstream of them (the classifier's mount-payload root, the factory's payload slots) fires for this form. A new `suppliesPerRequest()` fact is the one predicate the factory, the connection runtime and the instrumentation read to choose the supplied emit shape; `emitsHookImplementation()` stays true for both forms so the plan membership fact does not fork.

### Emitted types

In `<outputPackage>.schema`, emitted exactly when `suppliesPerRequest()`, at a Java 17 floor (the sakila example compiles them at 17):

* `MountedSession`: a final value type. `configuration()` is the provider-free `Configuration` carrying the source's dialect and `Settings`, as the legacy seam parameter does; `tenant()` exists only in a `<tenantColumn>` build, as `Optional<K>` for the tenant column's type, so a single-tenant consumer cannot call it. A `Connection` seam is not offered: the callback form is `Configuration` only.
* `SessionState`: the per-request pair, shaped by the carrier arm so each build has one `of` form and lambdas never face overload ambiguity. Handle-less: `of(Mount)` and `of(Mount, Unmount)` with `Mount` taking a `MountedSession`. Handled: `Mount<H>` returning the handle type and `Unmount` taking `(MountedSession, handle)`. The functional interfaces may throw `Exception`; any throwable from either becomes the cause the runtime reports, as for the static form. `of` null-checks its arguments.
* `SessionMountException` and the hook machinery are emitted as today. The generated `SessionHookImpl` no longer has static `mount`/`unmount` for this form; `PinnedConnection` calls the carried `SessionState` directly.

### Factory and carrier

* `Graphitron.newOwnedExecutionInput(<tenants, multi-tenant only>, SessionState session, <schema context arguments>)`: the tenants parameter keeps its leading place so existing multi-tenant call sites change by one inserted argument, not a reorder. The context arguments are only those the schema declares; the mount contributes none. The escape-hatch factory and the build with `NotConfigured` keep their signatures.
* The factory null-checks `session` and writes it to the `GraphQLContext` under the key `SessionState.class`, as the carrier is written under `TenantConnections.class`. `GraphitronConnectionInstrumentation.beginExecuteOperation` reads it and hands it to the `TenantConnections` carrier in place of the payload values. A context lacking it (an input built without the factory) fails the operation before any SQL with an `IllegalStateException` naming `newOwnedExecutionInput`; a request that touches no database is still never mounted.
* The handle's `HANDLE_DATA_KEY` publication and `TenantConnections.sessionHandle` are unchanged; the handle is typed by the declared `<handle>` instead of a reflected return type.

### Lifecycle (unchanged)

Lazy acquisition on whatever thread first needs a connection (the callback's captured values make that thread irrelevant), one mount per pinned connection and tenant key, autocommit asserted before the mount, unmount at release on every completion path, eviction on a throwing mount or unmount, and the request-level rejection already in place: a throwable from the mount callback is wrapped in `SessionMountException` exactly as the static mount's is today.

### Dev tooling

The dev executor runs the consumer's compiled classes at dev time, so a reflected reference there does not have the before-generation problem. In a `Callbacks` build, `<devDatabase>` gains an optional `<session>fqcn#method</session>` naming a public static method `SessionState method(String claims)` (a build with a different handle shape still has exactly one generated `SessionState`, so the signature is the emitted type). The dev executor resolves it at run time, builds the `SessionState` from `<claims>`, and runs it up front exactly as it runs the legacy pair today, so a rejected payload fails loud. Without `<session>`, dev execution in a `Callbacks` build fails with a message naming `<devDatabase><session>`, the same fail-loud posture the legacy path has for claims it cannot construct; it never executes unmounted. The legacy path keeps `stringConstructiblePayload`.

### The store

`StoreEntries.sessionState` is exhaustive over the seal, so `Callbacks` forces a decision. It transcribes the switch and the optional handle type into one new provenance relation, `store_graph_session_supplied` (grain: one row per graph that supplies session identity per request, carrying the handle type as authored when one is named), with the same primary key on `graph_name`, `touched_at` mark and sweep, and `meta_relation` owner as `store_graph_session_mount`. The implementer follows the `adding-a-relation` skill; the authored string only lands here, never a reflected fact.

## Breaking change (decided)

Both forms live side by side for one release: `<mount>` / `<unmount>` keep working, byte-for-byte, and emit a build warning (a `BuildWarning` from `SessionStateWarnings.forConfig`) naming the replacement. The reasons are the consumers that cannot move in one commit: tilgangsstyring (!6441) is on 10.0.0-RC46 with a green 856-test suite and migrates in its own merge request, opptak migrates onto the callback form from the start, and graphitron-sakila-example's existing executions double as the regression suite for the legacy form. The cost is two emit shapes in the connection runtime and the factory; they are mutually exclusive by configuration and share the lifecycle, so the fork is one `suppliesPerRequest()` read at each of three sites. Removing the legacy form is a follow-on item filed when this ships, with `changelog.md` and the consumers checked then.

## Tests

* Unit: `SessionStateConfig` accepts a bare block as `Callbacks`; `<handle>` beside `<mount>` and `<unmount>` alone reject by element name; `SessionStateWarnings` warns on `MethodHooks` and not on `Callbacks`; `resolveSessionHooks` mints `SuppliedHandled` for a loadable handle, `SuppliedHandleLess` without one, and drains an unloadable class as a `ValidationError`.
* Pipeline: with a bare `<sessionState/>` and no method references, the emitted `MountedSession`, `SessionState` (one `of` form per arm) and the factory signature, which carries `SessionState` plus only the schema context arguments; a multi-tenant build keeps the tenants parameter first and gives `MountedSession` its `tenant()`; a single-tenant build does not; a legacy build emits none of the new types and keeps its factory.
* Execution, new fixture executions in graphitron-sakila-example (single-tenant handled with a jOOQ record handle, and a multi-tenant one), extending `SessionHookExecutionTest`: a callback sees the values captured when the input was built, on the connection being mounted; unmount runs at release with the mounted handle; a throwing callback rejects the request once as `SessionMountException` with the callback's throwable as cause; a request touching no database never calls the mount; an input built without the factory fails before any SQL; a mount running on a scatter worker thread still sees its captured values, and under multi-tenancy each tenant key mounts once with that key in `MountedSession.tenant()`.
* Dev tooling: `<devDatabase><session>` builds the state from claims and runs it up front; its absence fails loud, never unmounted.
* Consumer acceptance, not part of this repository's build: tilgangsstyring !6441 moved to the callback form keeps `SessionIdentity`, `AuthenticationResult` and `Orgtilganger` in `tilgangsstyring-app`, needs no `-parameters` for the factory, and its 856 tests stay green.

## Documentation

`mojo-configuration.adoc`, Session identity: the bare switch, `<handle>`, the callback form with the Goal's example, the `combine.self` note, why values are captured rather than looked up, the thread the mount runs on, and the unchanged escape hatch; the `<mount>` form is marked deprecated with the migration. `runtime-extension-points.adoc`: the same contract from the contributor's side, including why this differs from `MutationHook`. `docs/manual` for the dev tool's `<session>`.

## Relation to other items

* **R1098**: graphitron-jakarta-rest's simpler flow depends on this item.
* **R1097** (Done): request-level rejection applies unchanged to the callback.
* **R1102** (Ready): `$session` by member decodes the handle's declared members onto `SessionHooks.Handled`. That decode runs over any arm that carries a handle type; whichever item lands second extends it to the other's arm (`SuppliedHandled` here), and nothing in R1102's sigil, validation or emission changes, since they read the handle type and members and not the mount's reflection.
* **R1101** (Ready): see Decisions; no code dependency.
* **R1108** (Backlog): the manual describes the callback form, not the method reference.

## Other solutions we've considered

* **A runtime-registered session hook reading request values from `GraphQLContext`**, as R1101 does for its hook. Removes the reflection, but values are fetched by string key and cast, and a consumer still has to put them there.
* **A generated interface implemented by a CDI bean.** Natural in a CDI application, but core generated code must not require a container, and a bean that reads request-scoped state depends on the request context being active on the mounting thread. graphitron-jakarta-rest can offer the bean flow on top of this item (R1098).
* **Keeping the static method and generating a named-parameter factory.** Fixes the ordering but keeps the reflection, the module move and the static.
