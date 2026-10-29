---
id: R1120
title: "The POM switches session identity on; the consumer supplies it per request as code"
status: Spec
bucket: feature
priority: 4
theme: runtime-connection
depends-on: []
created: 2026-10-28
last-updated: 2026-10-29
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

* **The handle type is optional data in the switch.** `<sessionState><handle>fqcn</handle></sessionState>` names the type a mount callback returns; omit it and the mount is handle-less (no `$session` to bind). Generation loads that class and the types of its readable members, to decode them for `$session.<member>` bindings and to type the emitted callback; it never loads the mount code or the types the mount's signature mentions, which is what forced the module move. A handle that is a jOOQ UDT or record, which the catalog jar already provides, costs nothing new; a consumer-authored record such as opptak's `Identitet` and its component types must sit in a module generation can load, which is the residual cost and which the docs state plainly. The alternatives lose something real: a generic `SessionState<H>` checked at runtime moves a build-time error to production, and dropping the handle strands `$session` consumers.
* **Context arguments stay positional and alphabetical.** With the mount payload gone, the biggest source of same-typed swaps (the mount's own parameters, which the consumer controlled by renaming) is removed; what remains are the schema's declared `contextArguments`, unchanged by this item. A named record or builder for them is a separate, schema-wide change and gets its own item when this lands.
* **R1101 and this item deliberately differ, and the docs say why.** `MutationHook` is application-wide behaviour registered once on the runtime; it receives the request as a `GraphQLContext` view because it must read values the request carries. A session callback is a per-request value-bearing object whose inputs the consumer already holds when it builds the input, so it captures them by closure and travels in the context under its own typed key. Forcing one shape on both would make the hook look up what the callback already holds, or make the callback re-register per request.
* **The escape hatch is unchanged.** `newExecutionInput(dsl, ...)` neither takes nor reads a `SessionState`; the consumer owns identity there. `mojo-configuration.adoc` says so beside the new form.

One question the Backlog text missed: the dev tools (`mvn graphitron:dev`, the MCP `execute` tool) construct the mount's payload from `<devDatabase><claims>` through the reflected signature, so a callback build gives them nothing to call. Decided in Dev tooling below.

## Plan

### Configuration

* `SessionStateConfig` becomes `None | Callbacks(Optional<String> handleType)`, strings only. `MethodHooks` and `HookRef` retire. A `<sessionState>` block, with or without `<handle>`, yields `Callbacks`; `<handle>` names the handle type.
* `<mount>` and `<unmount>` retire as POM elements. `SessionStateBinding` keeps them only so that a POM still naming one fails the build with a message pointing at the callback form and the migration section of `mojo-configuration.adoc`, rather than Maven's generic unknown-parameter error. The form is replaced, not deprecated; see Breaking change.
* An execution inheriting a plugin-level `<sessionState>` and writing its own merges the two; `combine.self="override"`, which the sakila pom already uses for this, stays the way to replace rather than merge, and the docs name it.

### The resolved carrier

* `SessionHooks` keeps `NotConfigured`, `HandleLess` and `Handled(TypeName handleType)` as its seal; the handle axis is the only axis left. `HandleLess` is a `<sessionState>` with no `<handle>`, `Handled` one that names it. `Handled` is also where the member decode lands once the member-binding item has run, so that decode and the validator's handle checks hold unchanged.
* The reflected parts retire with the form: `mountRef`, `unmountRef`, `payloadParams`, `tenantSlot`, `stringConstructiblePayload`, the `MethodRef.StaticOnly` references the arms carry, the mount-payload root fed to `ContextArgumentClassifier`, and the mount-only parameter sources. Every site that read them either goes or reads the new fact directly; the compiler lists them. The four `instanceof SessionHooks.Handled` sites keep working unchanged.
* `resolveSessionHooks` loads the `<handle>` class and reflects nothing else; an unloadable class drains as a schema-wide `ValidationError`, as a mount reflection failure does today, and mints `NotConfigured`. `emitsHookImplementation()` stays the membership fact the plan and the connection runtime share.

### Emitted types

In `<outputPackage>.schema`, emitted whenever `<sessionState>` is configured, at a Java 17 floor (the sakila example compiles them at 17):

* `MountedSession`: a final value type. `configuration()` is the provider-free `Configuration` carrying the source's dialect and `Settings`, as the retired seam parameter did; `tenant()` exists only in a `<tenantColumn>` build, as `Optional<K>` for the tenant column's type, so a single-tenant consumer cannot call it. A `Connection` seam is not offered: the callback form is `Configuration` only.
* `SessionState`: non-generic, one per build, with the concrete handle type when `Handled` (never `SessionState<H>`), so each build has one `of` form and lambdas never face overload ambiguity. Handle-less: `of(Mount)` and `of(Mount, Unmount)`, with `Mount` taking a `MountedSession`. Handled: `Mount` returns the handle type and `Unmount` takes `(MountedSession, handle)`. The functional interfaces may throw `Exception`; any throwable from either becomes the cause the runtime reports, as for the static form. `of` null-checks its arguments.
* `SessionMountException` is emitted as today. The generated `GraphitronSessionHook` unit stays planned whenever `emitsHookImplementation()` holds; it holds the one thing that stays generated code, building the `MountedSession` (the provider-free `Configuration` is assembled there today) and invoking the carried `SessionState`, so it has no static `mount`/`unmount` taking the consumer's payload, and `PinnedConnection` calls it with the request's `SessionState`.

### Factory and carrier

* `Graphitron.newOwnedExecutionInput(<tenants, multi-tenant only>, SessionState session, <schema context arguments>)`. The tenants parameter keeps its leading place so existing multi-tenant call sites change by one inserted argument, not a reorder, and both multi-tenant overloads (the `Collection<K>` form and the typed `RequestTenants` form) gain `session`. The context arguments are only those the schema declares; the mount contributes none. The escape-hatch factory takes and reads no `SessionState`, but its parameters do change on migration: the old mount's payload parameters are among its context arguments today and are gone. The migration note says so.
* The factory null-checks `session` and writes it to the `GraphQLContext` under a named constant key (the way the tenants value has `TENANTS_KEY_FIELD`), which `GraphitronConnectionInstrumentation.beginExecuteOperation` reads and hands to the `TenantConnections` carrier in place of the payload values. The check there is eager and follows the `RequestTenants` check beside it: an input built without the factory fails the operation before any SQL with an `IllegalStateException` naming `newOwnedExecutionInput`, whether or not the operation would touch the database (introspection included). Only the mount itself stays lazy: an operation that touches no database never calls it. The runtime check is the only enforcer available, since `ExecutionInput` is graphql-java's type.
* The handle's `HANDLE_DATA_KEY` publication and `TenantConnections.sessionHandle` are unchanged.

### Lifecycle (unchanged)

Lazy acquisition on whatever thread first needs a connection (the callback's captured values make that thread irrelevant), one mount per pinned connection and tenant key, autocommit asserted before the mount, unmount at release on every completion path, eviction on a throwing mount or unmount, and the request-level rejection already in place: a throwable from the mount callback is wrapped in `SessionMountException` exactly as the static mount's is today.

### Dev tooling

The dev tools hold the consumer's classloader, so a reflected reference there does not have the before-generation problem; reflection stays in the tooling, the decode boundary, and the generated code carries none. In a build with `<sessionState>` the generated dev executor's `execute` takes a `SessionState` where it takes `claims` today. `<devDatabase>` gains an optional `<session>fqcn#method</session>`, a public static `SessionState method(String claims)`; the dev goal and the MCP `execute` tool resolve it, check that signature, build the state from `<claims>`, and pass it to `execute`, which runs it up front exactly as the dev executor runs the mount and unmount today so a rejected payload fails loud. A misnamed `<session>` is therefore caught only at dev run time, its one enforcer. Without `<session>`, dev execution in such a build fails with a message naming `<devDatabase><session>`; it never executes unmounted.

### The store

`StoreEntries.sessionState` transcribes the configuration so the store never misstates it. The `store_graph_session_mount` and `store_graph_session_unmount` provenance relations retire with the form they record, and are replaced by `store_graph_session_supplied` (grain: one row per graph whose session identity is supplied per request) with the family's primary key on `graph_name`, `touched_at` mark and sweep, and `meta_relation` owner, plus a child relation `store_graph_session_handle(graph_name` referencing it, `handle_type` not null, `touched_at)` whose row presence is the fact, after the unmount precedent, so the optional handle type is not a nullable column. With one form left there is no exclusion between relations to enforce. The implementer follows the `adding-a-relation` skill; only the authored string lands here, never a reflected fact.

## Breaking change (decided)

The method-reference form is replaced in one commit; nothing keeps it beside the callback form. The project owner's call: no consumer runs the owned-connection engine in production, so a deprecation period would buy nobody anything and would cost two emit shapes. Consumers to check in that commit: graphitron-sakila-example's executions (the facade pair, the tenant-slot pair, the handle-less org-tenant mount and the direct jOOQ `Routines` pair), which convert to the callback form and keep their assertions, and tilgangsstyring !6441 (on 10.0.0-RC46 as a trial), which moves to it as this item's consumer acceptance. `changelog.md` records the replacement. One capability goes: a jOOQ `Routines` method can no longer be named in the POM as the mount; the docs show it called from a callback instead, which is also what removes the "zero hand-written Java" shape the sakila execution demonstrates today.

## Tests

* Unit: `SessionStateConfig` yields `Callbacks` for a bare block and for `<handle>`; a POM naming `<mount>` or `<unmount>` fails with the migration message; `resolveSessionHooks` mints `Handled` for a loadable handle, `HandleLess` without one, and drains an unloadable class as a `ValidationError`.
* Pipeline, validation: `$session` without a `<handle>` rejects naming `<handle>`; a parameter whose type disagrees with the declared handle rejects naming `<handle>`; the validator's mount-named messages are gone with the mount.
* Pipeline: with a bare `<sessionState/>`, the emitted `MountedSession`, `SessionState` (one `of` form per handle arm) and the factory signature, which carries `SessionState` plus only the schema context arguments; a multi-tenant build keeps the tenants parameter first, in both overloads, and gives `MountedSession` its `tenant()`; a single-tenant build does not; a build without `<sessionState>` emits none of the new types and keeps its factory.
* Execution, the sakila executions converted to the callback form (single-tenant with a jOOQ record handle, multi-tenant with the tenant slot, handle-less org-tenant, direct `Routines`), `SessionHookExecutionTest` keeping its existing assertions and gaining: a callback sees the values captured when the input was built, on the connection being mounted; unmount runs at release with the mounted handle; a throwing callback rejects the request once as `SessionMountException` with the callback's throwable as cause; a request touching no database never calls the mount; an input built without the factory fails before any SQL; a mount running on a scatter worker thread still sees its captured values, and under multi-tenancy each tenant key mounts once with that key in `MountedSession.tenant()`.
* Dev tooling: `<devDatabase><session>` builds the state from claims and runs it up front; its absence fails loud, never unmounted.
* Consumer acceptance, not part of this repository's build: tilgangsstyring !6441 moved to the callback form keeps `SessionIdentity`, `AuthenticationResult` and `Orgtilganger` in `tilgangsstyring-app`, needs no `-parameters` for the factory, and its 856 tests stay green.

## Documentation

`mojo-configuration.adoc`, Session identity, rewritten around the callback form: the bare switch, `<handle>`, the Goal's example, the `combine.self` note, why values are captured rather than looked up, the thread the mount runs on, the unchanged escape hatch, and a short migration from `<mount>`/`<unmount>`. `runtime-extension-points.adoc`: the same contract from the contributor's side, including why this differs from `MutationHook`. `docs/manual` for the dev tool's `<session>`. Every other page that names `<mount>` or `<unmount>` is rewritten, not annotated.

## Retired vocabulary

`<mount>`, `<unmount>` (POM elements), `SessionStateConfig.MethodHooks`, `HookRef`, `SessionHooks.mountRef`, `unmountRef`, `payloadParams`, `tenantSlot`, `stringConstructiblePayload`, the static `mount`/`unmount` on the generated hook unit, `store_graph_session_mount` and `store_graph_session_unmount`. The Done gate's retirement sweep greps for each across code, docs and tests.

## Relation to other items

* **R1098**: graphitron-jakarta-rest's simpler flow depends on this item.
* **R1097** (Done): request-level rejection applies unchanged to the callback.
* **R1102** (Done): `$session` by member decoded the handle's members onto `SessionHooks.Handled` and extended `validateSessionHandleBindings`, against the reflected mount. This item changes where the handle type comes from (the declared `<handle>`, not a mount's return type) and rewrites the validator messages that name a mount or a `void` mount to name `<handle>`; its sigil, member decode and emission are untouched.
* **R1101** (Ready): see Decisions; no code dependency.
* **R1108** (Backlog): the manual describes the callback form, not the method reference.

## Other solutions we've considered

* **A runtime-registered session hook reading request values from `GraphQLContext`**, as R1101 does for its hook. Removes the reflection, but values are fetched by string key and cast, and a consumer still has to put them there.
* **A generated interface implemented by a CDI bean.** Natural in a CDI application, but core generated code must not require a container, and a bean that reads request-scoped state depends on the request context being active on the mounting thread. graphitron-jakarta-rest can offer the bean flow on top of this item (R1098).
* **Keeping the static method and generating a named-parameter factory.** Fixes the ordering but keeps the reflection, the module move and the static.
* **Declaring the handle's members in the POM and emitting the handle record.** Removes the last pre-generation class entirely, at the cost of authoring the members twice (POM and use sites) and a second type mapping. A jOOQ record handle already available from the catalog jar has no cost under the chosen form, so this waits for a consumer that needs it.
* **Declaring the handle as a schema `input` (deferred).** Graphitron already emits a class per argument-reachable input type, so the handle could be an `input` marked as a reachability root and left out of the served SDL, which would remove the load-order cost of a consumer-authored class. Not taken now: the fully qualified `<handle>` is the only form. Three things a successor must settle: the emitted artifact is a plain class until graphitron-javapoet can emit records; the built-in scalars have no `Long` (it needs a consumer `@scalarType`); and components are boxed, so a non-null `Long!` member cannot bind a primitive parameter under the boxing rule of the `$session` member item unless non-null members are emitted as primitives.
* **Keeping the method-reference form beside the callback form for a release.** Spec'd earlier and dropped: no consumer holds it in production, and it would cost a second emit shape and a second store relation for nothing.
