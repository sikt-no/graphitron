---
id: R1102
title: "A value the session mount derives binds where a contextArgument binds today"
status: Spec
bucket: feature
priority: 4
theme: runtime-connection
depends-on: []
created: 2026-10-26
last-updated: 2026-10-26
---

# A value the session mount derives binds where a contextArgument binds today

## Goal

A value the `<sessionState>` mount derives, such as a user id the database looked up or provisioned while mounting, can be bound by `@service` and `@condition` methods without the consumer resolving it before execution. The mount's return value (the *handle*, whose type the build already reads) becomes the `$session` variable of `argMapping`, addressable by member: `argMapping: "brukerId: $session.brukerId"` binds one member of the handle to one Java parameter, checked against the mount's declared return type at build time. Today `$session` binds only the whole handle and only in `@service`. Meanwhile `newOwnedExecutionInput` needs every `contextArgument` before execution starts, and the mount cannot write to the request's `GraphQLContext`. A consumer whose identity is an output of authentication therefore has to choose between taking two connections per request (resolve, then mount) and rewriting every binding and signature to take the whole handle.

Consumer view, with a mount `Identitet mount(Configuration cfg, String token)` where `Identitet` is a Java record `(long brukerId, Long sokerId)`:

```graphql
type Query {
    mineSoknader: [Soknad!]! @condition(condition: {
        className: "no.sikt.opptak.Tilgang", method: "egneSoknader",
        argMapping: "sokerId: $session.sokerId"})
    profil: Profil @service(service: {
        className: "no.sikt.opptak.ProfilService", method: "hent",
        argMapping: "brukerId: $session.brukerId"})
}
```

```java
public static Condition egneSoknader(Soknad soknad, Long sokerId) {
    return soknad.SOKER_ID.eq(sokerId);
}
```

The condition and service methods keep the parameters they have today; only the `argMapping` names the source. A member the handle type does not have, or a parameter whose type does not match the member's, fails the build naming both declarations. Under `@tenantFanOut`, `$session` stays service-only in this item: a fanned statement is built before any tenant's connection exists, so a condition there is rejected at build time.

## Motivating consumer

fs-plattform's opptak subgraph, moving from the escape hatch to the owned-connection engine on Postgres with RLS and a single tenant. Its mount looks up the caller (`auth.finn_identitet`), may provision a `SOKER` row, and yields `brukerId` and `sokerId`. Its schema reads those two values through `contextArguments` in 26 places, across services and conditions. Every use is a database field or condition, so reading the handle (which pins the connection) costs nothing extra.

## The shape today

* `ArgMappingSigil` (graphitron-model) owns the sigil vocabulary. `scan` recognises exactly `$session` and `$source.<column>` and lifts them as raw strings (`ScanResult.Ok.sigilBindings`); `Site.admitsSessionSigil()` is true only for `SERVICE`, so `$session` in `@condition` is rejected by name today, and any other `$session...` text is an unknown sigil.
* Services: `ServiceDirectiveResolver` carries the lifted names as `Set<String> sessionBound` into `ServiceCatalog.reduceClaims` (`ParamRole.SessionBound`), which makes the parameter `ParamSource.SessionHandle`; `ServiceMethodCallWalker` mints `MappingEntry.FromSessionHandle` for the call carrier. Two emitters read it: `ArgCallEmitter`'s `SessionHandle` arm and `ServiceMethodCallEmitter`'s `FromSessionHandle` arm, both through `TenantConnections.sessionHandle(dsl, fieldCoordinate)`, which reads the handle off the resolved `DSLContext`'s `configuration().data(...)` slot. That read is what makes the handle per connection (a tenant-routed call sees its tenant's handle) and what turns the escape hatch, where nothing was mounted, into a located throw. `TenantBindingIndex` reads both forms as well.
* `SessionHandle` has a second producer: the `<unmount>` hook's handle parameter.
* `SessionHooks` is resolved after the classification walk (`GraphitronSchemaBuilder`, `ServiceCatalog.resolveSessionHooks`), so classification cannot see the handle type; `GraphitronSchemaValidator.validateSessionHandleBindings` is the one home of the cross-artifact check (declared parameter type equals `Handled.handleType()`, no mount, `void` mount). `Handled` carries the handle type as a `TypeName` only.
* Conditions: `BuildContext.readConditionDirective` and `resolveConditionRef` keep only the `argMapping` overrides and drop sigil bindings; `ServiceCatalog.reflectTableMethod` binds only `Arg` and `Context` parameters; `MethodRef.callParams()` admits only those two. The glue (`ConditionGlueRenderer`) reads `CallParam`s through `CallSiteExtraction` arms, takes `args`, and takes `env` when a row reads the request context (`CallParam.readsRequestContext()`, rolled up by `ConditionCommand` and carried as `GlueCall(method, takesEnv)`). `!readsRequestContext()` is used elsewhere to mean "reads the argument map" (`ProjectionCommands.readsSelectedFieldArguments`, `Presence`).
* Where the glue runs: a `Single`-tenancy root launcher has `dsl` as a parameter; a fanned launcher builds its condition and its `$project` select list on the dispatch thread, before `fanOutRows(env, dsl -> ...)`, deliberately, so scatter workers read no graphql-java state; batched fanned rows build the WHERE inside the per-tenant lambda but hoist `$project`. The `$project(grouped, table, env)` rail has no `dsl`; `env` threads through it unconditionally because it is request-global.

## Plan

### The sigil

* `ArgMappingSigil.scan` lifts a small sealed sigil value instead of a raw string: the whole session handle, a session-handle member (`$session.<member>`, exactly one name after the dot; a deeper path rejects naming the one-member form), or a source column. `entries` keeps returning the right-hand side verbatim for fact capture. Every consumer of the lifted map switches on the sealed value, so a member cannot silently reach one emitter and not the other.
* `Site.CONDITION` splits into the placements the store side already names (`ArgmappingProjectionDefects.Site`): field, argument and input-field `@condition`, plus the path-step `@condition` and the fallback host `FieldBuilder` uses for other external-code references. `$session` (whole or member) is admitted at `SERVICE` and at field, argument and input-field `@condition`; the path-step and fallback placements keep rejecting it, as do routine, record, external-field and enum sites. `notAdmittedMessage` names the admitted set. Every admitted placement consumes the binding; a pipeline test per placement pins that none parses it and drops it.

### The handle's members, decoded once

* `ServiceCatalog.resolveSessionHooks`, where the mount's class is live, decodes the handle type's readable members through `ClassAccessorResolver` (zero-arg: record component, bean getter including `is<Name>` for a boolean, public field) and carries them on `SessionHooks.Handled` as values: member name, accessor form (method call or field read, by name) and `TypeName`. No `Method` or `Field` object enters the model.
* A binding carries only the authored member name. The resolved accessor is the join of binding and `Handled` members, read by both the validator and the emitters.

### The model

* `ParamSource` and `MappingEntry` gain a sibling variant for a member (`SessionHandleMember(member)` and `FromSessionHandleMember(javaName, javaType, member)`), beside the whole-handle variants, so every exhaustive switch decides, and an unmount parameter with a member stays unrepresentable.
* Services: `reduceClaims` takes the lifted sigil values instead of `Set<String>` and claims a member parameter the way it claims a whole-handle one; `ServiceMethodCallWalker`, `ArgCallEmitter`, `ServiceMethodCallEmitter` and `TenantBindingIndex` each gain the member arm. The emitted read is the guarded accessor plus the member's accessor, `TenantConnections.sessionHandle(dsl, coordinate).getPrincipal()`.
* Conditions: `readConditionDirective` and `resolveConditionRef` keep the sigil bindings; `reflectTableMethod` binds a sigil-claimed parameter as `SessionHandle` or `SessionHandleMember`; `MethodRef.callParams()` admits them; the condition's `CallParam` carries a new `CallSiteExtraction` arm for a session read (whole or member, with the member's accessor from the join).
* `CallParam` exposes one derived read source (argument map, request context, session) in place of the boolean pair; `readsRequestContext()` and the negated uses become reads of that source, so "reads the argument map" is said positively. A row's glue signature is a value derived from the set of sources its bindings read, and `GlueCall` carries that value instead of `takesEnv`: a row reading the session takes the executing `DSLContext` as a trailing parameter, the way a row reading the request context takes `env`.

### Where a session-reading condition can run

* `Single` tenancy (every single-tenant build, and the routed, non-fanned rows of a multi-tenant one): root and batched rows methods pass their `dsl`. A `$project` whose subtree reaches a session-reading glue takes `dsl` too, decided by a derived fact rolled up per projection method the way `ProjectionCommands` already rolls up `readsRequestContext` per glue method; projections that read no session keep their signature. Callers pass the `dsl` of the statement they build.
* Under fan-out the per-tenant handle exists only inside the scatter lambda, and the condition and `$project` are built on the dispatch thread by design. v1 does not move them: a `$session` condition on a fanned launcher, or in a subtree projected under one, is rejected at validate time, naming the fanned field and stating that `$session` in a service still works under fan-out. Lifting that needs the session-reading part of the statement built per tenant without reading graphql-java state on a worker, a separate design.
* `$session` in a `@service` keeps working under fan-out unchanged, since each call already reads its own connection's handle.

### Validation

* `validateSessionHandleBindings` extends to members and to the condition placements, reading the binding × `Handled` join: no mount configured, or a `void` mount, rejects as today; an unknown member rejects naming the handle type and its readable members; a parameter whose type is not the member's rejects naming both declarations. Boxing counts in one direction only: a primitive member may bind its boxed type, a boxed member may not bind a primitive parameter (it may be `null`).
* A `null` member value is passed as `null`, as an absent `contextArgument` is.

### Store and tooling

* Fact capture records the right-hand side verbatim, and `ArgMappingCandidates.seedSigilRoots` already seeds any `$`-prefixed value as an opaque `SIGIL` candidate, so `$session.<member>` needs no new relation to capture or match. The LSP needs no change: it holds no sigil admission rule of its own, and `seedSigilRoots` seeds whatever `$`-prefixed value was written as that entry's own candidate, at any site. The handle's member set is a classpath-corpus fact the store does not hold, so completion after `$session.` and an unknown-member diagnostic in the editor are deferred; capturing members later is a schema change (the `SIGIL`-at-depth-0 check on `graphitron_argmapping_candidate`), left to a successor.

### Tests

* Unit: `ArgMappingSigil.scan` lifts each sealed value; a two-segment path rejects; admitted and rejected placements as above; `$source` unchanged.
* Pipeline: a member binding classifies to the member variant on both services and each admitted condition placement; each validation rejection fires with its message (unknown member, type mismatch, boxed member into a primitive rejected, primitive into boxed accepted, no mount, `void` mount); a condition row reading `$session` derives the session read source and its glue takes `dsl`; a projection method reaching it takes `dsl` and one that does not keeps its signature; a `$session` condition under a fanned launcher, directly and projected, rejects.
* Execution, against the sakila fixture, whose facade mount returns `SessionHandleRecord` (`principal`, `sessionNo`, read through `getPrincipal()`): a root `@condition` bound to `$session.principal` filters a fixture table by the mounted principal; the same condition on a field projected into its parent's statement does too; a `@service` bound to `$session.principal` sees the member, including under fan-out, where each tenant's call reads its own handle (the fixture's per-tenant session-number ranges make that observable through `sessionNo`); and the escape hatch fails with the located error rather than binding `null`.

### Documentation

* `docs/manual/reference/mojo-configuration.adoc`, "Consuming the handle in services", becomes "Consuming the handle": the member form, `@condition` beside `@service` with its placements, the build-time checks, the fan-out limit for conditions, and the opptak-shaped example from the Goal.
* The `@condition` directive reference names `$session` among the `argMapping` sources.

## Other solutions we've considered

* **A `contextArgument` declared as derived from the handle.** Keeps existing `contextArguments` bindings untouched, but adds a second declaration form for a value the handle already carries, and a context argument's read path (`GraphQLContext`, request-scoped) is the wrong scope for a per-connection value under tenant fan-out.
* **The mount writes into the request's `GraphQLContext`.** Needs a new seam on the mount's signature, and the same per-connection scoping problem: one request context, one connection per tenant.
* **Threading `dsl` through every `$project` unconditionally, like `env`.** `env` is request-global and present wherever `$project` is called; `dsl` is per connection, and under fan-out the select list is built where no per-tenant `dsl` exists, so the unconditional form does not reach the case it claims to. The derived per-projection fact touches only the subtrees that read the session.
* **Building session-reading conditions inside the scatter lambda under fan-out.** Would read graphql-java state (`env`, the selection set) on scatter workers, which the fanned launcher avoids by construction. Left as the successor the fan-out rejection names.
* **Resolving the member at classification.** The hooks are resolved after the walk, and carrying a resolved `Method` would put live reflection in the model; decoding the members once onto `Handled` avoids both.
* **Nested member paths (`$session.a.b`).** No consumer needs one yet; one member keeps the null semantics trivial.

## Reviewer findings

### Round 1 (2026-10-26, Spec -> Ready, reviewer session 013nppeKAeTQdYmEGB6LeLv8)

Verdict: not yet Ready. The goal is well stated and viable: a reader can say what changes for the opptak consumer without reading the plan, and the sakila fixture (a handle-returning mount, the tenant sequences restarted at 1000 and 2000 in `TenantFanOutExecutionTest`) can demonstrate it. The plan extends the shapes already in the tree: the `$session` sigil, the `SessionHandle`/`FromSessionHandle` pair, the guarded `TenantConnections.sessionHandle(dsl, coordinate)` read (typed, so the member read type-checks), the `GlueCall` signature fact, and the validator's one cross-artifact home. I checked the spec's claims against the tree: every named symbol exists; the fanned launcher hoists `condition` and `selectFields` before `fanOutRows` (`RootLauncherRenderer.fannedBody`); `!readsRequestContext()` does stand for "reads the argument map" in `ProjectionCommands.readsSelectedFieldArguments` and `Presence`; a dotted `$source.<column>` already seeds as a depth-0 `SIGIL` candidate, so the store claim holds. Corrected in passing: the LSP sentence under Store and tooling (the LSP never consulted `Site.admitsSessionSigil`, so there is nothing for it to follow).

Three findings change what the implementer builds:

1. **The `Site` split is planned against a tree that already has half of it.** `ArgMappingSigil.Site.REFERENCE_STEP` exists, with a description written for the path-step condition, and nothing in main uses it: `BuildContext.resolveConditionRef` (the path-step parse) passes `Site.CONDITION`. The "fallback host" in `FieldBuilder.parseExternalRef` is unreachable: its only callers are `ServiceDirectiveResolver` (`@service`) and `ExternalFieldDirectiveResolver` (`@externalField`). Field, argument and input-field `@condition` all go through `readConditionDirective`, and the plan admits `$session` at all three alike, so splitting `CONDITION` into three adds constants with one admission rule between them. The smaller change: `resolveConditionRef` passes the existing `REFERENCE_STEP`, `CONDITION` admits `$session`, and the dead fallback arm in `parseExternalRef` is either removed or made to throw. The "pipeline test per placement" stands either way. If the author still wants the three-way split (for example so the message can name the placement), say what reads the distinction.

2. **The projection roll-up needs its grain stated.** The plan says a `$project` whose subtree reaches a session-reading glue takes `dsl`, "rolled up per projection method the way `ProjectionCommands` already rolls up `readsRequestContext` per glue method". The precedent is one hop: a glue method's rows. The new fact is transitive over projection-to-projection calls, across projection units, and that graph has cycles (any type that reaches itself through a nested field, as Film to actors to films does in sakila). The plan should say the fact is a closure computed to a fixpoint (or derived as one statement in the anchoring phase, if it is to live in the store), and that a signature change ripples to every caller, including callers in other projection units that the dev loop must then recompile (`PlanCompileGraph` edges).

3. **"Readable members" is undefined for the handle type the tests use.** The fixture's handle is the jOOQ-generated `SessionHandleRecord`. Its zero-arg public methods include `getPrincipal()` and `getSessionNo()`, but also the inherited `Record` API (`size()`, `changed()`, `original()`, `fields()`, `getUDT()`, `getSQLTypeName()` and more) and, where the generator implements `Record2`, `value1()` and `component1()`. Decoding every zero-arg accessor through `ClassAccessorResolver` makes `$session.changed` (and possibly `$session.value1`) bind, and puts dozens of jOOQ API names into the unknown-member message the plan promises will list the readable members. State the enumeration rule (for example: record components for a Java record; otherwise `get`/`is` bean getters and public fields declared on the handle class itself, not inherited), and add a pipeline case on a jOOQ UDT record handle pinning it.

Non-blocking, for the author's judgment:

* **An alternative worth recording under Other solutions.** On a single-source build the handle is reachable from `env` alone (`TenantConnections.of(env)` to the default entry's `DSLContext`), and `env` already threads through every `$project`. That would leave every projection signature unchanged for the motivating consumer. It does not reach the routed rows of a multi-tenant build, whose key the glue does not know, which is presumably why the plan threads `dsl`; one sentence saying so would close the question for the next reader.
* "`CallParam` exposes one derived read source ... in place of the boolean pair": there is one boolean today (`readsRequestContext`; `decodesNodeId` is orthogonal and stays). Presumably "the predicate and its negated uses" is meant.
