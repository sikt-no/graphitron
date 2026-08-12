---
id: R639
title: "Session hooks are reflected routine calls, not string-bound callables"
status: Spec
bucket: architecture
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-08-12
last-updated: 2026-08-12
---

# Session hooks are reflected routine calls, not string-bound callables

The claims payload is stringly typed end to end, and the string is not merely the Java carrier: it is also how the payload reaches the database, so a consumer whose connect routine takes a user-defined type has no supported way to call it. `GraphitronFacadeGenerator.generate` hardcodes the owned factory's leading parameter as `ClassName.get(String.class)`, `GraphitronConnectionInstrumentationGenerator.instrumentation` reads it back as `String` from the `graphQLContext` under the emitted `CLAIMS_KEY` constant, the `SessionHook.connect` signature emitted by `ConnectionRuntimeClassGenerator.sessionHook` is `(Connection, String claims)`, and `ConnectionRuntimeClassGenerator.functionHookImpl` binds the payload with `cs.setString(1, claims)` into a hand-assembled `{ call <fn>(?) }`. Nothing casts, so the parameter arrives typed `varchar` and a routine declared `connect(app.claims)` does not resolve against it; whether it works at all depends on the pgjdbc `stringtype` setting deciding to send the parameter as unspecified and letting Postgres infer the composite from its text representation. That is a fragile accident, not a contract, and it inverts the project's own posture everywhere else on this boundary: the tenant key's Java type is read off the catalog so a mis-typed map is a compile error, and contextArguments are typed from the consumer's declared parameter type so a wrong value is a compile error at the factory call site, while the one payload that carries identity, the security-load-bearing value, is an untyped blob that fails (if at all) inside the database at a cast.

## Approach: the signature is already a catalog fact

The consumer has *already* named the routine. `<connect><call>` is a pointer into the database, and everything about the call shape is a property of what it points at, so further `<sessionState>` elements describing the payload type or the handle would make the author restate facts the catalog holds, and admit restating them wrongly. This item resolves the named routines against the catalog and reads their signatures: parameter names, IN/OUT direction, Java types, return type. That is how the generator already treats the database (`@routine`'s reference says "the routine's shape (table-valued) is read off its jOOQ kind, not declared"; the tenant key's Java type is read off the column), and it is the difference between typed code the author configures and typed code the author merely *gets*.

`JooqCatalog.findNonTableValuedRoutine` already does the hard half and throws the answer away. It locates the generated per-routine class for exactly the population a session hook draws from, a procedure or scalar function that is not table-valued, instantiates it, and verifies `org.jooq.Routine.getName()` against the SQL name so a naming-strategy mismatch degrades to a miss rather than a false positive. It then discards the resolved routine and returns `JooqCatalog.RoutineResolution.NonTableValuedRoutine`, whose only payload is a prose `detail` string used to give `@routine` a better rejection message.

Binding then follows for free: a UDT-typed parameter on a generated jOOQ routine class is already typed as the generated `UDTRecord`, and jOOQ already binds it. Calling the routine *through its generated class* rather than through an assembled callable string is what makes UDT support fall out, instead of needing `PGobject`/`setObject` plumbing and a `Schema.getUDTs()` lookup of our own. R234 (UDT records as input backings) is not a prerequisite, though the two should agree on how a UDT surfaces in the model.

**Freedom to redesign rather than extend.** There are no known users of the function-hook form, and the tree confirms it has never run end to end: both plugin executions in `graphitron-sakila-example` configure the `<variables>` sugar, `SessionHookExecutionTest` drives that sugar's emitted hook, and no connect/disconnect fixture routine exists in `graphitron-sakila-db`. The form is unit-tier only (`SessionStateConfigTest`, `SessionHookImplGeneratorTest`, `SessionStateWarningsTest`). So the config surface and the emitted shape change outright; the additive-then-cutover technique in `roadmap/workflow.adoc` applies within the reactor (slice 1 lands unconsumed, so the execution tier stays green at every commit) but no dual-sourced author-facing surface is kept.

**Carrier: a sibling, not a reuse.** The Backlog body proposed riding the existing `RoutineRef` / `ParamSource` family. That is the wrong read of the principle and this spec revises it. `RoutineRef`'s own javadoc argues that a catalog handle deserves a carrier separate from `MethodRef` rather than a shared accessor whose meaning depends on the variant, and the same argument separates a session hook from `@routine`: the call surface differs (a per-routine generated class driven through typed setters, versus a `Routines`-class convenience method returning a `Table<R>`), and the parameter sources differ (the request boundary, versus `ParamSource.Arg` / `ParamSource.SourceColumn`, neither of which a session hook has). A `SessionRoutineRef` sibling is the honest shape. What genuinely single-sources is the *resolution seam*: one `JooqCatalog` method that resolves a name to a verified generated routine, which both `@routine`'s deferred-rejection probe and `<sessionState>` read.

## Implementation

### Slice 1: catalog resolution (lands unconsumed)

* `JooqCatalog`: add `resolveCallableRoutine(String call)` returning a new sealed `CallableRoutineResolution`. Arms: `Resolved`, `NotInCatalog`, `AmbiguousOverload` (candidate list), `AmbiguousQualification` (see below), `NoGeneratedRoutine` (jOOQ codegen produced no routines at all, the `verifyTablesClassPresent` analogue), `UnbindableSignature` (a parameter whose generated setter cannot be identified). Every failure arm carries what was probed, in the register `NoConvenienceMethod` already uses.
* `Resolved` carries the generated routine `ClassName`, the ordered IN parameters (jOOQ-generated name, Java `TypeName`, and the *reflected setter method name*), and the handle as an `Optional` carrying its type plus how it is read. Accessor names are reflected off the generated class and verified against the parameter's Java type, never reconstructed from jOOQ's naming transform; a parameter with no matching accessor is `UnbindableSignature`, mirroring how `resolveTableValuedFunction` reflects the convenience method rather than guessing its name. Two details the generated shape imposes (checked against `RentalCountForCustomer` in `graphitron-sakila-db`'s jOOQ output):
  * Each IN parameter generates *two* setters, a value form (`setPCustomerId(Integer)`) and an expression form (`setPCustomerId(Field<Integer>)`). Resolution must pick the value form, using the same `org.jooq.Field`-parameter filter `resolveTableValuedFunction` already applies when choosing the table-form convenience overload.
  * The handle is read two different ways depending on how the routine declares it. A `RETURNS <type>` function carries a `RETURN_VALUE` parameter read through `AbstractRoutine.getReturnValue()`, inherited and not generated; an OUT parameter gets a generated getter. `setValue` is protected on `AbstractRoutine`, so the generated accessors are the only external path and this fork is not avoidable. The arm records which read applies so the emitter does not re-derive it.
* Fold `findNonTableValuedRoutine` into the new method so the probe exists once. `resolveTableValuedFunction`'s `NonTableValuedRoutine` arm keeps its prose detail, now derived from the shared resolution.
* Qualification ambiguity is real and must be an arm, not a first-hit: the shipped canonical example `Pk_Ras.Connect` is parsed by `parseQualifiedTableName` as schema `Pk_Ras` + routine `Connect`, but it is written as package `Pk_Ras` + procedure `Connect`. Both readings must be attempted and a name that resolves under both is `AmbiguousQualification`.
* Oracle package routines: teach the probe the generated package location (verify jOOQ's layout at implementation; the current probe looks only at `<schema>.routines.<PascalName>`). The Oracle worked example is the load-bearing one for Sikt's kernel API, so Postgres-only resolution would miss the primary case.
* Overload addressing: `<call>` accepts an optional parenthesised argument-type list (`app.mount_identity(app.claims)`) so an overloaded name is addressable. A bare overloaded name is `AmbiguousOverload` naming the candidates.

### Slice 2: config, model, emission, facade (the cutover)

* `SessionStateConfig`: delete `handle` from `Unmount.PairedDisconnect`, and with it the "declared on both or neither" cross-check in `SessionStateConfig.from`. Keep `<stateSurvivesTransactions>` and `Unmount.UnmountFree`: both are claims about what a routine body does, which no signature reveals. The rejections that survive are the ones about the *config's* shape (connect without disconnect, both forms configured at once); signature-derived rejections belong to the resolution seam and reach the build through the same Mojo boundary.
* New `SessionRoutineRef` model carrier holding the resolved connect and disconnect call surfaces plus the handle type, built once at the `RewriteContext` boundary where `SessionStateConfig` already threads through, so emitters read a resolved fact rather than re-resolving.
* `ConnectionRuntimeClassGenerator.functionHookImpl` / `functionDisconnect`: emit a jOOQ call through the generated routine class (instantiate, typed value-form setter per IN parameter, `execute(configuration)`, then the resolved handle read) in place of `CallableStatement` and `setString`. The hook builds a `Configuration` over the connection it is handed. The state contract is unchanged and must stay pinned: both halves run outside any transaction, the connection is normalized to autocommit at acquisition, and `PinnedConnection.afterSettle`'s re-fire (disconnect old handle, connect fresh) keeps working.
* `ConnectionRuntimeClassGenerator.sessionHook` (the `SessionHook` interface): `connect` takes the reflected parameter list and returns the reflected handle type; `disconnect` takes that handle type. `PinnedConnection`'s `handle` field and the stashed arguments retype accordingly.
* Generated `GraphitronSessionArgs` record: the typed carrier for the reflected argument tuple, one component per IN parameter. It is what the facade factory populates, what goes into the `graphQLContext`, and what `PinnedConnection` holds for the re-fire. `CLAIMS_KEY` is replaced by a `SESSION_ARGS_KEY` constant, keeping the single-constant-so-write-and-read-cannot-drift property it exists for.
* `GraphitronFacadeGenerator`: `newOwnedExecutionInput` grows one typed parameter per reflected IN parameter, in declaration order, ahead of the alphabetical contextArgument slots. A single `text` parameter reproduces today's `(String claims, ...)` signature exactly, which is why the `<variables>` sugar and the raw-JWS cryptographic-fence pattern need no special case: they are signatures that happen to be one string. The javadoc emitters (`ownedExecutionInputJavadoc`, `escapeHatchJavadoc`, `runtimeJavadoc`) describe the parameters from the same resolved fact.
* A resolution miss fails the build naming what was probed. Silently falling back to a string bind would revert identity plumbing to the weaker form, a security-relevant regression rather than a convenience.

### Slice 3: fixture and execution coverage

* `graphitron-sakila-db/src/main/resources/init.sql`: a composite type for the payload, a composite type for the handle, a connect routine taking the payload composite and producing the handle composite, a disconnect routine bound to it, and RLS policies on a probe table reading what connect mounted. Both directions must be composite so the fixture proves typed IN *and* typed OUT binding; R640 then adds only publication.
* `graphitron-sakila-example/pom.xml`: a new plugin execution configuring the function-hook form into its own output subpackage, following the isolation pattern the federation and multitenant executions already use, so the `<variables>` sugar keeps its own coverage untouched.

## Open decisions for the reviewer

* **The dead claims parameter under `SessionStateConfig.None`.** The owned factory takes `String claims` even with no `<sessionState>` configured, and the value is written to the `graphQLContext` where nothing reads it. Deriving the parameter list from the hook says that parameter should not exist. Recommendation: drop it. The honest caveat is that this changes an emitted signature for consumers on the owned path *without* session state, which is outside the function-hook form's blast radius and therefore outside the "no known users" licence this item otherwise leans on.
* **`<devDatabase><claims>` and the MCP `execute` tool.** `GRAPHITRON_DEV_CLAIMS` and `<allowClaimsOverride>` supply one inline-or-`@file` string. This is the surface most likely to want to stay string-ish for ergonomics, since an agent composing a composite literal is worse than one composing JSON. Decide deliberately rather than by inheritance; a defensible answer is that the dev seam accepts a JSON object and maps it onto the reflected parameters by name.
* **Whether slice 3's fixture belongs on Postgres only.** `R468`'s Oracle execution coverage is blocked on a container; slice 1's Oracle package resolution is unit-testable against a generated catalog but not execution-provable here. Confirm that shipping Oracle resolution without Oracle execution proof is acceptable, given the item's own "every invariant has an enforcer" posture.

## User documentation (first-client check)

Draft replacement for the function-hook subsection of `docs/manual/reference/mojo-configuration.adoc` § Session identity. If this does not read more simply than what it replaces, the design is wrong.

> **Function-hook form.** Name consumer-authored database routines. `connect` mounts identity; `disconnect` unmounts it. Graphitron reads the routines' signatures from the jOOQ catalog, so the payload's type, the number of arguments, and whether a handle is produced are all facts you declare once, in the database:
>
> ```xml
> <sessionState>
>   <connect><call>app.mount_identity</call></connect>
>   <disconnect><call>app.unmount_identity</call></disconnect>
> </sessionState>
> ```
>
> The generated factory's parameters follow the connect routine's IN parameters. A routine taking one `text` parameter gives you `newOwnedExecutionInput(String claims, ...)`; a routine taking a composite type gives you `newOwnedExecutionInput(ClaimsRecord claims, ...)` with typed field setters, and passing the wrong shape is a compile error rather than a cast failure inside the database. If connect produces an OUT value or returns one, graphitron captures it and binds it to `disconnect`; the two signatures must agree on its type, and the build fails naming both if they do not.
>
> If the name is overloaded, address the overload you mean: `<call>app.mount_identity(app.claims)</call>`.

Also rewrite: the "Producing the claims payload" and integrity-gradient sections of `docs/architecture/reference/runtime-extension-points.adoc`, `docs/security.adoc`, and the owned-path paragraph of `docs/manual/how-to/tenant-scoping.adoc`. The integrity gradient improves rather than merely being corrected: the cryptographic fence is a routine taking `text` it verifies in-database, the enforced fence is a routine taking a composite behind a definer-rights package, and the gradient becomes readable off the signature.

## Tests

* **Unit.** `CallableRoutineResolution` arm coverage per failure mode, including both ambiguity arms and the package form. `SessionStateConfigTest` for the restructured config and the rejections that survive. `SessionHookImplGeneratorTest` for the emitted jOOQ call shape, the typed handle, and the re-fire.
* **Pipeline.** `GraphitronFacadeGeneratorPipelineTest` asserts the `newOwnedExecutionInput` parameter list against a reflected multi-parameter signature, including the degenerate one-string case that must reproduce today's signature.
* **Compilation.** Generated sources for the new fixture execution compile at `<release>17</release>` in `graphitron-sakila-example`.
* **Execution.** The function-hook form's first execution-tier proof, in the shape `SessionHookExecutionTest` establishes for the sugar: an RLS-scoped read sees only permitted rows, a mutation's post-commit read-back still does (exercising the settle re-fire), identity is absent after disconnect, and a composite payload and composite handle round-trip through the real emitted hook.
* **Warnings.** `SessionStateWarningsTest` still fires `session-state-convention-fence` where it should; check whether reflected signatures let that warning become more precise (a routine taking a verified token is not the same exposure as one taking bare JSON).

## Retired vocabulary

* `CLAIMS_KEY` (constant and its `no.sikt.graphitron.request.claims` value), replaced by `SESSION_ARGS_KEY`.
* The `<handle>` configuration element, and `handle` as a component of `SessionStateConfig.Unmount.PairedDisconnect`.
* "opaque claims payload" / "the opaque claims" as the description of the connect parameter, in emitted javadoc and in prose. The word `claims` itself is *not* retired: the `<variables>` sugar's `<claim>` mapping and the dev-goal `<claims>` config both name a genuine claims document.
* `RoutineResolution.NonTableValuedRoutine` as a prose-only arm, if slice 1 folds it into the shared resolution.
