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

## Plan

* **Configuration.** `<sessionState/>` (or an equivalent switch; the spec round names it) turns the feature on with no method references. The emitted session hook, `SessionMountException` and the owned factory are generated as today. `<mount>` and `<unmount>` are deprecated; see Breaking change.
* **Emitted types**, in `<outputPackage>.schema`, gated on the switch:
  * `MountedSession`: what graphitron knows about the connection being mounted. `configuration()` (provider-free, carrying the source's dialect and `Settings`, as the `Configuration` parameter does today), and `tenant()` as an `Optional` of the tenant column's type in a multi-tenant build.
  * `SessionState`: the per-request pair of a mount callback and an optional unmount callback, built with `SessionState.of(mount)` / `SessionState.of(mount, unmount)`. A mount that returns a handle passes it to unmount and to `$session` bindings (see Open questions).
* **Factory.** `Graphitron.newOwnedExecutionInput(SessionState session, <schema context arguments>)`. The context arguments are only those the schema declares (`@service` / `@condition` `contextArguments`); the mount no longer contributes any. A build with the switch off keeps today's `newOwnedExecutionInput(<schema context arguments>)`.
* **Lifecycle unchanged.** Lazy acquisition, one mount per pinned connection and tenant key, unmount at release on every completion path, autocommit asserted before the mount, eviction on a throwing mount or unmount, and the request-level rejection already in place: an exception from the mount callback is wrapped in `SessionMountException` exactly as an exception from the static mount is today.
* **Carrier.** The per-request `SessionState` travels in the `GraphQLContext` under a typed key the factory writes and `GraphitronConnectionInstrumentation` reads, the way the payload context arguments travel today.

## Open questions for the spec round

* **The handle type.** Today `$session` bindings are type-checked against the static mount's return type at build time. With a callback, generation needs the handle type from somewhere: a type named in the switch (data, not code), a generic `SessionState<H>` with `$session` checked at runtime, or dropping the handle in favour of R1102's member binding. Decide together with R1102.
* **Context arguments by name.** The schema context arguments remain positional and alphabetical on the factory, with the same swap risk for same-typed values. Whether to give the factory a generated record or builder with named components belongs here or in a follow-on.
* **Alignment with R1101.** `MutationHook` is registered once on the runtime and reads request values from `GraphQLContext` by string key. A per-request session callback captures them by closure instead. Either both items take the same shape, or the docs say why they differ.
* **Escape hatch.** Nothing changes for `newExecutionInput(dsl, ...)`, where the consumer owns identity; confirm the docs say so.

## Breaking change

Consumers naming `<mount>` / `<unmount>` today: tilgangsstyring (!6441), graphitron-sakila-example's fixtures, and opptak once migrated. Either both forms live side by side for one release with a deprecation warning at build, or the change follows the breaking-change convention in one commit with those consumers checked and `changelog.md` updated.

## Tests

* Execution tier, extending `SessionHookExecutionTest`: a callback mount sees the values captured when the input was built, on the connection being mounted; unmount runs at release; a throwing callback rejects the request once as `SessionMountException` with the callback's exception as cause; a request touching no database never calls the mount.
* Multi-tenant: one mount per tenant key with that key in `MountedSession.tenant()`, including scatter workers on other threads.
* Pipeline tier: with the switch on and no method references, the emitted types and factory signature; the factory carries only schema context arguments.
* Consumer acceptance: tilgangsstyring !6441 moved to the callback form keeps `SessionIdentity`, `AuthenticationResult` and `Orgtilganger` in `tilgangsstyring-app`, needs no `-parameters` dependency for the factory, and its suite (856 tests, green on the method-reference form) stays green.

## Documentation

The session identity section of `mojo-configuration.adoc` and `runtime-extension-points.adoc`: the switch, the callback form, why values are captured rather than looked up, and the thread the mount runs on.

## Relation to other items

* **R1098**: graphitron-jakarta-rest's simpler flow depends on this item.
* **R1097** (Done): request-level rejection applies unchanged to the callback.
* **R1102** (Spec): `$session` by member; the handle question above is shared.
* **R1101** (Spec): same move for the mutation hook; see Alignment.
* **R1108** (Backlog): the manual describes the callback form, not the method reference.

## Other solutions we've considered

* **A runtime-registered session hook reading request values from `GraphQLContext`**, as R1101 does for its hook. Removes the reflection, but values are fetched by string key and cast, and a consumer still has to put them there.
* **A generated interface implemented by a CDI bean.** Natural in a CDI application, but core generated code must not require a container, and a bean that reads request-scoped state depends on the request context being active on the mounting thread. graphitron-jakarta-rest can offer the bean flow on top of this item (R1098).
* **Keeping the static method and generating a named-parameter factory.** Fixes the ordering but keeps the reflection, the module move and the static.
