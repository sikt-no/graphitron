---
id: R1102
title: "A value the session mount derives binds where a contextArgument binds today"
status: Ready
bucket: feature
priority: 4
theme: runtime-connection
depends-on: []
created: 2026-10-26
last-updated: 2026-10-29
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

## Shipped

Shipped at `09c3b0f`, with the round-1 review fixes following it. One line per part of the plan, with what differs from the plan as approved:

* **Sigil.** `ArgMappingSigil.scan` lifts sealed `Binding`s (`SessionHandle`, `SessionMember`, `SourceColumn`); `Site.CONDITION` admits `$session`, the path-step condition parses at `REFERENCE_STEP`, and the unreachable `CONDITION` fallback in `parseExternalRef` throws.
* **Member decode.** `ServiceCatalog.decodeHandleMembers` decodes record components, or declared `get<X>`/`is<X>` getters and public fields, onto `SessionHooks.Handled.members()`, as a function of the handle class alone.
* **Model and services.** `ParamSource.SessionHandleMember`, `MappingEntry.FromSessionHandleMember` and `ParamRole.SessionMemberBound` beside the whole-handle variants. *Differs:* the emitted read is `TenantConnections.sessionMember_<name>(dsl, coordinate)`, one guarded accessor per decoded member generated by the connection runtime (spelled by `SessionAccessors.memberMethod`), so the call-site emitters name only the authored member.
* **Conditions.** `CallSiteExtraction.SessionHandleRead`/`SessionMemberRead`, `CallParam.readSource()` in place of the request-context predicate, and a session-reading glue appending `dsl` after `env`. `GlueCall` carries `takesDsl`.
* **Projection closure.** `ProjectionCommands` computes the least fixpoint over `Contribution.Call` edges, seeded from `Multiset`, `LookupMultiset` and probe filters. *Differs:* it rides the rows as `ProjectionCommand.dslProjections()`, `Contribution.Call.calleeTakesDsl` and `LauncherCommand.dslProjections()`, and `ProjectionCall` appends `dsl` from those, not from a `readsSession` flag on the callee row.
* **Validation.** The validator checks members and types for services and every condition placement. *Differs:* beyond the fan-out rejection, a session-reading condition is also rejected below a federation entity, `Node` or interface/union participant type, and in polymorphic branch filters, because those emitters cannot hand `dsl` on.
* **Docs.** `mojo-configuration.adoc` "Consuming the handle" and the `@condition` reference.

Evidence: `SessionHookResolutionTest` (member decode), `ArgMappingSigilTest`, `SessionMemberBindingPipelineTest` (classification and validation), `SessionConditionPlanPipelineTest` (glue signature, closure, projection signatures), and the execution tier: `SessionHookExecutionTest` (root and projected-child conditions, the member service, the escape-hatch guard) and `TenantFanOutExecutionTest` (`$session.sessionNo` under fan-out reads each tenant's own number).

## Other solutions we've considered

* **A `contextArgument` declared as derived from the handle.** Keeps existing `contextArguments` bindings untouched, but adds a second declaration form for a value the handle already carries, and a context argument's read path (`GraphQLContext`, request-scoped) is the wrong scope for a per-connection value under tenant fan-out.
* **The mount writes into the request's `GraphQLContext`.** Needs a new seam on the mount's signature, and the same per-connection scoping problem: one request context, one connection per tenant.
* **Threading `dsl` through every `$project` unconditionally, like `env`.** `env` is request-global and present wherever `$project` is called; `dsl` is per connection, and under fan-out the select list is built where no per-tenant `dsl` exists, so the unconditional form does not reach the case it claims to. The derived per-projection fact touches only the subtrees that read the session.
* **Reading the handle from `env` instead of threading `dsl`.** `env` already reaches every `$project` call, and on a single-source build the handle is reachable from it alone (`TenantConnections.of(env)` to the default entry's `DSLContext`), so the motivating consumer's projection signatures would not change at all. It does not reach the routed rows of a multi-tenant build: the connection a routed statement runs on is chosen by the launcher from its tenant key, which the glue does not know, so a glue reading through `env` would read the wrong tenant's handle or none. Threading the statement's `dsl` reads the handle of the connection the statement actually runs on, in every `Single`-tenancy launcher alike, at the cost of a signature change confined to the subtrees that read the session.
* **Splitting `Site.CONDITION` per placement** (field, argument, input field). All three admit the same sigils, so the split would add constants with one rule between them; nothing reads the distinction. The placement a diagnostic names is already carried by the fact capture side (`ArgmappingProjectionDefects.Site`).
* **Building session-reading conditions inside the scatter lambda under fan-out.** Would read graphql-java state (`env`, the selection set) on scatter workers, which the fanned launcher avoids by construction. Left as the successor the fan-out rejection names.
* **Resolving the member at classification.** The hooks are resolved after the walk, and carrying a resolved `Method` would put live reflection in the model; decoding the members once onto `Handled` avoids both.
* **Nested member paths (`$session.a.b`).** No consumer needs one yet; one member keeps the null semantics trivial.

## Reviewer findings

### Round 1 (2026-10-26, Spec -> Ready, reviewer session 013nppeKAeTQdYmEGB6LeLv8)

Verdict: not yet Ready. The goal is well stated and viable: a reader can say what changes for the opptak consumer without reading the plan, and the sakila fixture (a handle-returning mount, the tenant sequences restarted at 1000 and 2000 in `TenantFanOutExecutionTest`) can demonstrate it. The plan extends the shapes already in the tree: the `$session` sigil, the `SessionHandle`/`FromSessionHandle` pair, the guarded `TenantConnections.sessionHandle(dsl, coordinate)` read (typed, so the member read type-checks), the `GlueCall` signature fact, and the validator's one cross-artifact home. I checked the spec's claims against the tree: every named symbol exists; the fanned launcher hoists `condition` and `selectFields` before `fanOutRows` (`RootLauncherRenderer.fannedBody`); `!readsRequestContext()` does stand for "reads the argument map" in `ProjectionCommands.readsSelectedFieldArguments` and `Presence`; a dotted `$source.<column>` already seeds as a depth-0 `SIGIL` candidate, so the store claim holds. Corrected in passing: the LSP sentence under Store and tooling (the LSP never consulted `Site.admitsSessionSigil`, so there is nothing for it to follow).

Three findings change what the implementer builds:

1. **The `Site` split is planned against a tree that already has half of it.** `ArgMappingSigil.Site.REFERENCE_STEP` exists, with a description written for the path-step condition, and nothing in main uses it: `BuildContext.resolveConditionRef` (the path-step parse) passes `Site.CONDITION`. The "fallback host" in `FieldBuilder.parseExternalRef` is unreachable: its only callers are `ServiceDirectiveResolver` (`@service`) and `ExternalFieldDirectiveResolver` (`@externalField`). Field, argument and input-field `@condition` all go through `readConditionDirective`, and the plan admits `$session` at all three alike, so splitting `CONDITION` into three adds constants with one admission rule between them. The smaller change: `resolveConditionRef` passes the existing `REFERENCE_STEP`, `CONDITION` admits `$session`, and the dead fallback arm in `parseExternalRef` is either removed or made to throw. The "pipeline test per placement" stands either way. If the author still wants the three-way split (for example so the message can name the placement), say what reads the distinction.

   > *Author response (session 01AzNHaZ9KuhqRa3sNfSoAPn, 2026-10-26).* Took the smaller change. The sigil section now keeps one `CONDITION` constant that admits `$session`, has `resolveConditionRef` pass the existing `REFERENCE_STEP` (which keeps rejecting it), and turns the unreachable `CONDITION` fallback in `parseExternalRef` into a throw naming the unexpected directive. The pipeline tests are per placement, plus one for the path-step rejection. The three-way split moved to Other solutions with the reason: nothing reads the distinction.

2. **The projection roll-up needs its grain stated.** The plan says a `$project` whose subtree reaches a session-reading glue takes `dsl`, "rolled up per projection method the way `ProjectionCommands` already rolls up `readsRequestContext` per glue method". The precedent is one hop: a glue method's rows. The new fact is transitive over projection-to-projection calls, across projection units, and that graph has cycles (any type that reaches itself through a nested field, as Film to actors to films does in sakila). The plan should say the fact is a closure computed to a fixpoint (or derived as one statement in the anchoring phase, if it is to live in the store), and that a signature change ripples to every caller, including callers in other projection units that the dev loop must then recompile (`PlanCompileGraph` edges).

   > *Author response (session 01AzNHaZ9KuhqRa3sNfSoAPn, 2026-10-26).* Grain stated: one `readsSession` value per `ProjectionCommand` row, computed in `ProjectionCommands.produce` after every unit is minted, by seeding the units that call a session-reading glue and walking `Contribution.Call` edges backwards with a visited set: the least fixpoint, terminating on cycles. It stays a plan-layer Java computation beside its one-hop precedent, not a store relation. `ProjectionCall` is named as the one place that appends `dsl`, with every consumer listed. On recompilation: `addProjectionEdges` already records a `precise` edge from each unit to every callee and to each glue filter, and precise edges are the ones that fire a recompile on a signature change, so no new edge kind is needed. A pipeline test pins the ripple across two units and termination on `Film`.

3. **"Readable members" is undefined for the handle type the tests use.** The fixture's handle is the jOOQ-generated `SessionHandleRecord`. Its zero-arg public methods include `getPrincipal()` and `getSessionNo()`, but also the inherited `Record` API (`size()`, `changed()`, `original()`, `fields()`, `getUDT()`, `getSQLTypeName()` and more) and, where the generator implements `Record2`, `value1()` and `component1()`. Decoding every zero-arg accessor through `ClassAccessorResolver` makes `$session.changed` (and possibly `$session.value1`) bind, and puts dozens of jOOQ API names into the unknown-member message the plan promises will list the readable members. State the enumeration rule (for example: record components for a Java record; otherwise `get`/`is` bean getters and public fields declared on the handle class itself, not inherited), and add a pipeline case on a jOOQ UDT record handle pinning it.

   > *Author response (session 01AzNHaZ9KuhqRa3sNfSoAPn, 2026-10-26).* Rule stated, close to the one suggested: record components for a Java record; otherwise public non-static zero-arg `get<Name>` / boolean `is<Name>` methods and public fields declared on the class itself, inherited excluded. The plan says why it is not `ClassAccessorResolver`'s enumeration (`getMethods()` includes inherited API). Checked against the generated `SessionHandleRecord`: it extends `UDTRecordImpl` directly, does not implement `Record2`, and declares exactly `getPrincipal()` and `getSessionNo()` besides setters and constructors, so the rule yields `principal` and `sessionNo`. Unit cases cover record, POJO, inherited getter and the UDT record; a pipeline case pins `$session.changed` rejecting with exactly the two members listed.

Non-blocking, for the author's judgment:

* **An alternative worth recording under Other solutions.** On a single-source build the handle is reachable from `env` alone (`TenantConnections.of(env)` to the default entry's `DSLContext`), and `env` already threads through every `$project`. That would leave every projection signature unchanged for the motivating consumer. It does not reach the routed rows of a multi-tenant build, whose key the glue does not know, which is presumably why the plan threads `dsl`; one sentence saying so would close the question for the next reader.

  > *Author response.* Recorded under Other solutions, with the routed-row reason.
* "`CallParam` exposes one derived read source ... in place of the boolean pair": there is one boolean today (`readsRequestContext`; `decodesNodeId` is orthogonal and stays). Presumably "the predicate and its negated uses" is meant.

  > *Author response.* Yes; reworded to "the `readsRequestContext()` predicate", with `decodesNodeId()` named as orthogonal.

### Round 2 (2026-10-26, Spec -> Ready, reviewer session 013nppeKAeTQdYmEGB6LeLv8)

Verdict: Ready. All three round-1 findings are resolved in the plan body, and the claims the revision adds hold against the tree:

* `Site.REFERENCE_STEP` is unused in main and `parseExternalRef`'s only callers are `@service` and `@externalField`, so one admitting `CONDITION`, the path-step parse moved to `REFERENCE_STEP`, and a throw in the dead arm is the whole sigil change.
* `PlanCompileGraph.addProjectionEdges` records a `precise` edge from each projection unit to every `Contribution.Call` callee and to the `Multiset` and `LookupMultiset` filter glue owners, so the signature ripple needs no new edge kind.
* The generated `SessionHandleRecord` extends `UDTRecordImpl` directly, implements no `Record2`, and declares only `getPrincipal()` and `getSessionNo()` besides setters and constructors, so the declared-members rule yields exactly `principal` and `sessionNo`.
* Every `$project` call site goes through `ProjectionCall`. Corrected in passing: the consumer list now also names the discriminated selects, the routine-write re-read and the service-rows and reentry-rows arms, which go through `ProjectionCall` too and each declare their `dsl`; fan-out occurs only in `RootLauncherRenderer.fannedBody` and `BatchedRowsFragments`, both covered by the rejection.

Two notes for the implementer, neither of which changes the design:

1. Session-reading glue reaches a projection unit through two wraps, `CallWrap.Multiset.filter` and `CallWrap.LookupMultiset.filter`; seed the closure from both, as `addProjectionEdges` reads both.
2. `ProjectionCall.fromLauncher` and `fromGrouped` take the callee's `ClassName`, not its row, so appending `dsl` from the callee's `readsSession` means each such call site hands `ProjectionCall` the row (or the flag) as well.

### Round 1 review of the delivery (2026-10-29, In Review -> Ready, reviewer session 01WkW2JJt8cw7EivtM5QCRpn)

Verdict: rework. Both blocking findings are about completeness evidence, not the design. The code reads as the approved change (the member decode, the sealed sigil, the closure over `Contribution.Call` edges, `ProjectionCall` as the one `dsl` appender, and the fan-out, entity, `Node` and participant rejections all match the plan; the one narrowing, the extra validate-time rejections, is recorded openly in the spec body). The reviewer stopped the verification build once finding 1 fired, so a green `mvn install -Plocal-db` is still owed by the next pass.

1. **Code-string assertions on generated bodies (approval precondition; question 2).** `SessionConditionPlanPipelineTest` matches rendered code text: `glue.code().toString()).contains("TenantConnections.sessionMember_brukerId(dsl, \"Store.customers\")")` in `conditionRow_readsTheSession_andItsGlueTakesDslAfterTheArguments`, and `byName.get("Store").toString()).contains("StoreConditions.customersCondition(").contains(", dsl)")` plus `byName.get("Customer").toString()).contains("Store.$project(").contains("env, dsl)")` in `renderedProjections_takeDslExactlyWhereTheClosureSaysAndPassItToTheGlue`. `docs/architecture/how-to/testing.adoc` bans code-string body matching on generated bodies; compilation and execution cover it. Keep the structural assertions (parameter names and order, `readsSession`, `takesDsl`, the closure set) and drop the three `toString()` matches; the execution tests already exercise the glue's member read and the nested `$project` handing `dsl` on.
2. **The fan-out half of the execution evidence is missing (question 2).** The plan's execution bullet requires that a `@service` bound to `$session.<member>` "sees the member, including under fan-out, where each tenant's call reads its own handle (the fixture's per-tenant session-number ranges make that observable through `sessionNo`)". The delivered `SessionHookExecutionTest` covers the service member only on the owned single-source path (`sessionNumber` asserts `isPositive()`), and no test reads a member under `@tenantFanOut`. That is the property reading through `dsl` exists to guarantee. Add a fanned `@service` bound to `$session.sessionNo` and assert each tenant's call returns a number from its own range (1000s and 2000s), or move the claim out of the plan with its reason.

Non-blocking:

* `sessionMemberReadByAConditionOnAnEscapeHatchOperation_failsLoudlyInsteadOfBindingNull` asserts only `getErrors()).isNotEmpty()`, which any failure satisfies. Assert the located session-handle message so it pins the guarded read.
* The spec body has no "shipped at <sha>" note; once the findings land, collapse the delivered plan to one per the approval precondition.

Author response (2026-10-29): finding 1, the three `toString()` matches are dropped and the structural assertions stay. Finding 2, `Film.mountedSessionNumber` (`number: $session.sessionNo`) is a fanned `@service` in the multi-tenant fixture, and `TenantFanOutExecutionTest.sessionMemberBoundServiceUnderFanOut_eachTenantReadsItsOwnSessionNumber` asserts tenant 1's rows read a number from 1000s and tenant 2's from 2000s. Non-blocking: the escape-hatch test now also drives `sessionMember_principal` directly and asserts the located message, and the spec body is collapsed to the Shipped section above.
