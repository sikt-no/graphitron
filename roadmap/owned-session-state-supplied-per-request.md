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

* **The handle type is optional data in the switch, and the store answers every question about it.** `<sessionState><handle>fqcn</handle></sessionState>` names the type a mount callback returns; omit it and the mount is handle-less (no `$session` to bind). The class is read like any other consumer class, by the classpath census into the `code_` family, so it must be a compiled public class on a declared classpath entry when capture runs; generation never loads the mount code or the types the mount's signature mentions, which is what forced the module move. A handle that is a jOOQ UDT or record, which the catalog jar already provides, costs nothing new; a consumer-authored record such as opptak's `Identitet` and its component types must sit in a module the build compiles first, which is the residual cost and which the docs state plainly. A generic `SessionState<H>` checked at runtime moves a build-time error to production, and dropping the handle strands `$session` consumers.
* **Session identity is built on the store, not on a model carrier.** Every question the generators and the validator ask (does this graph supply identity, which class is the handle, which members does it offer and what does each yield) is a row, captured from the configuration and the classpath corpus or derived from them in the anchoring phase. The classified model does not learn session identity. Existing session reflection (`SessionHooks`, `resolveSessionHooks`) retires; see The store.
* **Context arguments stay positional and alphabetical.** With the mount payload gone, the biggest source of same-typed swaps (the mount's own parameters, which the consumer controlled by renaming) is removed; what remains are the schema's declared `contextArguments`, unchanged by this item. A named record or builder for them is a separate, schema-wide change and gets its own item when this lands.
* **R1101 and this item deliberately differ, and the docs say why.** `MutationHook` is application-wide behaviour registered once on the runtime; it receives the request as a `GraphQLContext` view because it must read values the request carries. A session callback is a per-request value-bearing object whose inputs the consumer already holds when it builds the input, so it captures them by closure and travels in the context under its own typed key. Forcing one shape on both would make the hook look up what the callback already holds, or make the callback re-register per request.
* **The escape hatch is unchanged.** `newExecutionInput(dsl, ...)` neither takes nor reads a `SessionState`; the consumer owns identity there. `mojo-configuration.adoc` says so beside the new form.

One question the Backlog text missed: the dev tools (`mvn graphitron:dev`, the MCP `execute` tool) construct the mount's payload from `<devDatabase><claims>` through the reflected signature, so a callback build gives them nothing to call. Decided in Dev tooling below.

## Plan

### Configuration

* `SessionStateConfig` becomes `None | Callbacks(Optional<String> handleType)`, strings only. `MethodHooks` and `HookRef` retire. A `<sessionState>` block, with or without `<handle>`, yields `Callbacks`; `<handle>` names the handle class.
* `<mount>` and `<unmount>` retire as POM elements. `SessionStateBinding` keeps them only so that a POM still naming one fails the build with a message pointing at the callback form and the migration section of `mojo-configuration.adoc`, rather than Maven's generic unknown-parameter error. The form is replaced, not deprecated; see Breaking change.
* An execution inheriting a plugin-level `<sessionState>` and writing its own merges the two; `combine.self="override"`, which the sakila pom already uses for this, stays the way to replace rather than merge, and the docs name it.
* `SessionStateWarnings.forConfig` is unchanged in kind: it warns when the config is `None`.

### The store

The discipline in `adding-a-relation` decides the shape: provenance first, capture shaped by the questions, derivation in the anchoring phase into keyed tables, reads upstream only, and an owner, a grain and a mark and sweep for each relation. Relation names are the implementer's to settle; the grains, provenance and keys are the commitment. The questions, from the use sites:

* The emitters ask: does the graph supply session identity, which class is the handle (as a type to emit against), and which members does it offer, each with the accessor to call and what it yields.
* The `$session` checks ask: is any `$session` binding written where there is no identity or no handle, does `$session.<member>` name a member the handle offers, and (the one question the store cannot yet answer, below) does the bound Java parameter's type agree with the handle or the member.

Captured from the configuration (a corpus of its own, as the max page size and the lint settings are), in `StoreEntries`, owner `store`:

* **`store_graph_session_supplied`**: one row per graph whose session identity is supplied per request; row presence is the fact, per the family's absence rule (no row, no identity mounted). Primary key `graph_name`, foreign key to `store_graph`, `touched_at`.
* **`store_graph_session_handle`**: one row per graph that names a handle class, carrying the class as authored; foreign key to `store_graph_session_supplied`, so a handle without the switch is unwritable, after the unmount precedent it replaces. Only the authored string lands here.
* Both join the sweep list in writing order, the registration roster, `meta_relation`/`meta_grain`, and the capture tests; `store_graph_session_mount` and `store_graph_session_unmount` retire with the form they recorded. The capture is exhaustive over the `SessionStateConfig` seal, so a third form is a compile error there.

Captured from the classpath (`code_`): the handle class is read for what it offers. `code_class` already has a row for every public class of every non-transitive entry, jOOQ jars included. `code_read_slot`, which answers "which member names does this class offer an author, through which accessor, yielding what", is today written only for reactor-origin classes and not for classes under the jOOQ package, which the census reads header-only. The handle class is neither a use site of an author's directive nor necessarily a reactor class (the sakila handle is a jOOQ UDT record), so `CodeCapture` gains a pass that reads the slots of the configured handle class regardless of origin and of the header-only package, keyed by `store_graph_session_handle`. That makes the `code` gatherer read a `store` relation, so the edge `code -> store` is declared in `meta_gatherer_dependency` (the stage order already runs `StoreEntries` first). Capture is the right place for this, not a reader that re-reads the classfile: the fact is whole at capture and every reader would otherwise rebuild it.

Narrowings against what R1102 ships, stated so they are decisions and not accidents: a handle's members are the record components and the `get`/`is` accessors the store holds, so a public instance field no longer offers a member (no sakila, opptak or tilgangsstyring handle uses one), and an `is<X>` accessor offers a member whatever it returns.

Derived in the anchoring phase, in the derivation stratum (`DerivationStratum.steps`, which already reads `store` and `code`), each into a table:

* **The handle's members**: one row per readable member of the handle class the graph named, by the property name an author writes after `$session.`, keyed by graph and member name, referencing the `code_read_slot` row of its accessor with `ON DELETE CASCADE`. Selected as the slots the class declares itself (`declaring_class = class_name`), which is R1102's declared-only rule stated in one predicate: a jOOQ record handle offers `principal` and `sessionNo`, not the `Record` API it inherits. The yield (`slot_type`, `erased_class`) is carried by the referenced slot, not copied.
* **Rejections**, one relation modelled on `intent_authored_claim_rejection` (a total projection of the existing typed `Rejection` value, with `kind`, `variant` and `message`), read by a detection wired into `StoreDetections` (`DetectionReadReachGateTest` pins its reads) and given a `diagnostic` arm so the editor shows them. Rows: a `<handle>` class that no classpath entry holds or that is not public; a handle with two accessors for one member name (naming both); a `$session` binding written where no identity is configured; a bare `$session` where no handle is named; a `$session.<member>` the handle does not offer, listing the members it does. The last three are anti-joins between the captured `argMapping` pair rows (the `$session` and `$session.<member>` sources the sigil scan already lifts) and the handle and member relations, so none of them reads a model.

What the store cannot yet answer: whether the Java parameter a `$session` binding fills has the handle's or the member's type. The store does not resolve an `argMapping` pair to the method parameter it binds. That one check (declared parameter type equals the handle, or equals the member's yield with boxing in one direction) stays in `validateSessionHandleBindings` for now, reading the same rows rather than a reflected carrier, and moves into a derived rejection when the store resolves the parameter.

### What the generators and the validator read

* A reader in `no.sikt.graphitron.emit`, `SessionIdentityRows.read(StoreHandle)`, in the shape of `RoutineWriteQuery` and `ErrorChannelRelation`: one statement per grain (the graph row with its optional handle, and the handle's members nested under it as a correlated `MULTISET`), driven from `store_graph_session_supplied`, joined to `code_class`, the member relation and `code_read_slot` scoped through `store.reads(source_name)`, into an immutable value holding no handle on the store, with an `empty()` arm. The handle type and each member's yield are decoded once, here, at the boundary, from the stored type spelling into the javapoet type the emitters need.
* `EmitPlan.produce` reads it beside the routine-write and error-channel relations and decides membership from it; `produceWithoutStore` takes `empty()`. `ConnectionRuntimeClassGenerator`, `GraphitronConnectionInstrumentationGenerator` and `GraphitronDevExecutorGenerator` take the value in place of `SessionHooks`, so the four `instanceof Handled` sites and every mount-reference read go away rather than fork. The unit-tier drivers build the value directly.
* The schema build is handed the same value (the store is captured before the walk), and `validateSessionHandleBindings` reads it for the one check left to it. `GraphitronSchema.sessionHooks()`, `ServiceCatalog.resolveSessionHooks`, `decodeHandleMembers` and `reflectSessionHook` retire, and with them the classifier's `SessionMount` root and its factory slots.
* The classified model gains nothing: no `MutationField` variant, no walk step. `HANDLE_DATA_KEY` moves to the emitted runtime's own constant.

### Emitted types

In `<outputPackage>.schema`, emitted whenever `<sessionState>` is configured, at a Java 17 floor (the sakila example compiles them at 17):

* `MountedSession`: a final value type. `configuration()` is the provider-free `Configuration` carrying the source's dialect and `Settings`, as the retired seam parameter did; `tenant()` exists only in a `<tenantColumn>` build, as `Optional<K>` for the tenant column's type, so a single-tenant consumer cannot call it. A `Connection` seam is not offered: the callback form is `Configuration` only.
* `SessionState`: non-generic, one per build, with the concrete handle type when one is named (never `SessionState<H>`), so each build has one `of` form and lambdas never face overload ambiguity. Handle-less: `of(Mount)` and `of(Mount, Unmount)`, with `Mount` taking a `MountedSession`. With a handle: `Mount` returns the handle type and `Unmount` takes `(MountedSession, handle)`. The functional interfaces may throw `Exception`; any throwable from either becomes the cause the runtime reports, as for the static form. `of` null-checks its arguments.
* `SessionMountException` is emitted as today. The generated `GraphitronSessionHook` unit stays planned whenever the session-identity value is present; it holds the one thing that stays generated code, building the `MountedSession` (the provider-free `Configuration` is assembled there today) and invoking the carried `SessionState`, so it has no static `mount`/`unmount` taking the consumer's payload, and `PinnedConnection` calls it with the request's `SessionState`.

### Factory and request carrier

* `Graphitron.newOwnedExecutionInput(<tenants, multi-tenant only>, SessionState session, <schema context arguments>)`. The tenants parameter keeps its leading place so existing multi-tenant call sites change by one inserted argument, not a reorder, and both multi-tenant overloads (the `Collection<K>` form and the typed `RequestTenants` form) gain `session`. The context arguments are only those the schema declares; the mount contributes none. The escape-hatch factory takes and reads no `SessionState`, but its parameters do change on migration: the old mount's payload parameters are among its context arguments today and are gone. The migration note says so.
* The factory null-checks `session` and writes it to the `GraphQLContext` under a named constant key (the way the tenants value has `TENANTS_KEY_FIELD`), which `GraphitronConnectionInstrumentation.beginExecuteOperation` reads and hands to the `TenantConnections` carrier in place of the payload values. The check there is eager and follows the `RequestTenants` check beside it: an input built without the factory fails the operation before any SQL with an `IllegalStateException` naming `newOwnedExecutionInput`, whether or not the operation would touch the database (introspection included). Only the mount itself stays lazy: an operation that touches no database never calls it. The runtime check is the only enforcer available, since `ExecutionInput` is graphql-java's type.
* The handle's `HANDLE_DATA_KEY` publication and `TenantConnections.sessionHandle` are unchanged.

### Lifecycle (unchanged)

Lazy acquisition on whatever thread first needs a connection (the callback's captured values make that thread irrelevant), one mount per pinned connection and tenant key, autocommit asserted before the mount, unmount at release on every completion path, eviction on a throwing mount or unmount, and the request-level rejection already in place: a throwable from the mount callback is wrapped in `SessionMountException` exactly as the static mount's is today.

### Dev tooling

The dev tools hold the consumer's classloader, so a reflected reference there does not have the before-generation problem; reflection stays in the tooling, the decode boundary, and the generated code carries none. In a build with `<sessionState>` the generated dev executor's `execute` takes a `SessionState` where it takes `claims` today. `<devDatabase>` gains an optional `<session>fqcn#method</session>`, a public static `SessionState method(String claims)`; the dev goal and the MCP `execute` tool resolve it, check that signature, build the state from `<claims>`, and pass it to `execute`, which runs it up front exactly as the dev executor runs the mount and unmount today so a rejected payload fails loud. A misnamed `<session>` is therefore caught only at dev run time, its one enforcer. Without `<session>`, dev execution in such a build fails with a message naming `<devDatabase><session>`; it never executes unmounted.

## Breaking change (decided)

The method-reference form is replaced in one commit; nothing keeps it beside the callback form. The project owner's call: no consumer runs the owned-connection engine in production, so a deprecation period would buy nobody anything and would cost two emit shapes. Consumers to check in that commit: graphitron-sakila-example's executions (the facade pair, the tenant-slot pair, the handle-less org-tenant mount and the direct jOOQ `Routines` pair), which convert to the callback form and keep their assertions, and tilgangsstyring !6441 (on 10.0.0-RC46 as a trial), which moves to it as this item's consumer acceptance. `changelog.md` records the replacement. One capability goes: a jOOQ `Routines` method can no longer be named in the POM as the mount; the docs show it called from a callback instead, which is also what removes the "zero hand-written Java" shape the sakila execution demonstrates today.

## Tests

* Store tier, in `graphitron-model`, beside the capture and derivation tests of the neighbouring relations (`StoreEntriesTest`, `RelationRegistrationGateTest`, `MetaDeclarationGateTest`, `StageOrderGateTest`, `DetectionReadReachGateTest`): every new relation declares an owner and a grain and is registered, and each is pinned directly against fixture sources.
  * Capture: a bare `<sessionState>` writes the supplied row only, `<handle>` adds the handle row, `None` writes none; removing the block and re-capturing sweeps both; the partition test shows another graph's rows untouched.
  * The handle's slots: the configured handle class gets `code_read_slot` rows when it is a reactor class, when it is a sibling's class, and when it sits under the jOOQ package that the census otherwise reads header-only (the sakila `SessionHandleRecord`), and a class nobody configured as a handle gets none it would not have had.
  * Derivation, one anchor per relation: the member relation over a Java record, over a bean with `get` and `is` accessors, over a class whose getter is inherited (excluded), and over the jOOQ UDT record (exactly `principal` and `sessionNo`, and `changed` and `size` absent); the rejection rows for an absent or non-public handle class, two accessors for one member, `$session` with no `<sessionState>`, a bare `$session` with no handle, and `$session.<member>` naming no member (the message lists the members offered).
  * The reader reads nothing from a relation that runs later than the one it is anchored on.
* Unit: `SessionStateConfig` yields `Callbacks` for a bare block and for `<handle>`; a POM naming `<mount>` or `<unmount>` fails with the migration message; `SessionIdentityRows.read` yields `empty()` without a supplied row, the handle type and each member's accessor and yield with one, and decodes a generic yield correctly.
* Pipeline: the type-agreement check still in the validator rejects a parameter whose type disagrees with the handle or the member, naming `<handle>` and the member, with boxing accepted in one direction only; a build with `<sessionState/>` and no method references emits `MountedSession`, `SessionState` (one `of` form per handle arm) and the factory signature, which carries `SessionState` plus only the schema context arguments; a multi-tenant build keeps the tenants parameter first, in both overloads, and gives `MountedSession` its `tenant()`; a single-tenant build does not; a build without `<sessionState>` emits none of the new types and keeps its factory. The member-enumeration pipeline cases R1102 shipped move to the store tier above.
* Unit tier, the generated lifecycle called directly, against a real PostgreSQL. The generated `PinnedConnection` acquisition and release and the `TenantConnections` carrier are driven by a test in the generated schema package (package-private access, with a seam added only where the generated visibility is in the way) with a `DataSource` on the execution tier's PostgreSQL and a hand-built `SessionState`; there is no GraphQL execution and no `ExecutionInput`. The statements the callbacks run go to the real database and the test reads session state back from it. No mocked `DSLContext`, no `MockConnection`, no in-memory database, and no assertion on generated source strings. The tier is in the `execution` group, so it sits outside the inner loop's `-DexcludedGroups=execution` and inside the verification build. It asserts: a mount sees the values captured when the state was built, on the connection being mounted; unmount runs at release with the mounted handle, on success, error and cancellation; a throwing mount evicts the connection and is reported as `SessionMountException` with the throwable as cause, once per request; a throwing unmount evicts; autocommit is asserted before the mount; one mount per pinned connection and, multi-tenant, one per tenant key with that key in `MountedSession.tenant()`; a mount triggered from a scatter worker thread still sees its captured values; a carrier that is never asked for a connection never mounts.
* Execution tier, thin, because the unit tier owns the lifecycle: the sakila executions converted to the callback form (single-tenant with a jOOQ record handle, multi-tenant with the tenant slot, handle-less org-tenant, direct `Routines`) keep `SessionHookExecutionTest`'s existing end-to-end assertions under RLS, and the tier adds only what needs the whole path: `newOwnedExecutionInput` writes the state and the instrumentation reads it, so a callback's captured values reach the database through a real request; an input built without the factory fails before any SQL, introspection included; a request touching no database never calls the mount.
* Dev tooling: `<devDatabase><session>` builds the state from claims and runs it up front; its absence fails loud, never unmounted.
* Consumer acceptance, not part of this repository's build: tilgangsstyring !6441 moved to the callback form keeps `SessionIdentity`, `AuthenticationResult` and `Orgtilganger` in `tilgangsstyring-app`, needs no `-parameters` for the factory, and its 856 tests stay green.

## Documentation

`mojo-configuration.adoc`, Session identity, rewritten around the callback form: the bare switch, `<handle>`, the Goal's example, the `combine.self` note, why values are captured rather than looked up, the thread the mount runs on, the unchanged escape hatch, and a short migration from `<mount>`/`<unmount>`. `runtime-extension-points.adoc`: the same contract from the contributor's side, including why this differs from `MutationHook`. `docs/manual` for the dev tool's `<session>`. Every other page that names `<mount>` or `<unmount>` is rewritten, not annotated.

## Retired vocabulary

`<mount>`, `<unmount>` (POM elements), `SessionStateConfig.MethodHooks`, `HookRef`, the whole of `SessionHooks` (`NotConfigured`, `HandleLess`, `Handled`, `HandleMember` and every accessor on them), `GraphitronSchema.sessionHooks`, `ServiceCatalog.resolveSessionHooks`, `decodeHandleMembers` and `reflectSessionHook`, the classifier's session-mount root (`Site.SessionMount`), the static `mount`/`unmount` on the generated hook unit, `store_graph_session_mount` and `store_graph_session_unmount`. The Done gate's retirement sweep greps for each across code, docs and tests. New `Rejection` leaves, if the derived rejections add any, get their paragraph in `typed-rejection.adoc` as that page requires.

## Relation to other items

* **R1098**: graphitron-jakarta-rest's simpler flow depends on this item.
* **R1097** (Done): request-level rejection applies unchanged to the callback.
* **R1102** (Done): `$session` by member decoded the handle's members by reflection onto `SessionHooks.Handled` and validated against it. This item moves that decode into the store (the member relation over `code_read_slot`), turns the existence checks into derived rejections, narrows the member rule as stated under The store, and leaves the sigil scan and the call-site extraction semantics as they are; the one check that needs the bound parameter's type stays in the validator until the store resolves it.
* **R1101** (Ready): see Decisions; no code dependency.
* **R1108** (Backlog): the manual describes the callback form, not the method reference.

## Other solutions we've considered

* **A runtime-registered session hook reading request values from `GraphQLContext`**, as R1101 does for its hook. Removes the reflection, but values are fetched by string key and cast, and a consumer still has to put them there.
* **A generated interface implemented by a CDI bean.** Natural in a CDI application, but core generated code must not require a container, and a bean that reads request-scoped state depends on the request context being active on the mounting thread. graphitron-jakarta-rest can offer the bean flow on top of this item (R1098).
* **Keeping the static method and generating a named-parameter factory.** Fixes the ordering but keeps the reflection, the module move and the static.
* **Declaring the handle's members in the POM and emitting the handle record.** Removes the last pre-generation class entirely, at the cost of authoring the members twice (POM and use sites) and a second type mapping. A jOOQ record handle already available from the catalog jar has no cost under the chosen form, so this waits for a consumer that needs it.
* **Declaring the handle as a schema `input` (deferred).** Graphitron already emits a class per argument-reachable input type, so the handle could be an `input` marked as a reachability root and left out of the served SDL, which would remove the load-order cost of a consumer-authored class. Not taken now: the fully qualified `<handle>` is the only form. Three things a successor must settle: the emitted artifact is a plain class until graphitron-javapoet can emit records; the built-in scalars have no `Long` (it needs a consumer `@scalarType`); and components are boxed, so a non-null `Long!` member cannot bind a primitive parameter under the boxing rule of the `$session` member item unless non-null members are emitted as primitives.
* **Keeping the method-reference form beside the callback form for a release.** Spec'd earlier and dropped: no consumer holds it in production, and it would cost a second emit shape and a second store relation for nothing.
* **Keeping `SessionHooks` as a model carrier, slimmed to the handle axis.** Spec'd earlier: the carrier survives with the handle type and members, minted by reflection at schema build. Dropped for the store: a carrier is a second home for facts the classpath corpus already holds, every reader of it (four `instanceof` sites, the validator, the classifier) re-asks the same questions the rows answer, and the unloadable-class and `$session` rejections are anti-joins that the store expresses and the walk can only throw.
* **A new classpath relation for the handle's members.** Mirrors `code_read_slot` for one class. Rejected: the family already answers "which member names does this class offer", and the missing coverage (origin and header-only gates) is a capture gap to close in `CodeCapture`, not a reason for a parallel relation.
