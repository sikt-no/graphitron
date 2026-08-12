---
id: R639
title: "Session identity is a Java method resolved at build time, not generated SQL"
status: Spec
bucket: architecture
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-08-12
last-updated: 2026-08-12
---

# Session identity is a Java method resolved at build time, not generated SQL

`<sessionState>` names database routines as strings, and graphitron generates the call: `ConnectionRuntimeClassGenerator.functionHookImpl` assembles `{ call <fn>(?) }` and binds the payload with `cs.setString(1, claims)`, behind a generated `SessionHook` interface whose two methods are hardcoded `connect(Connection, String claims)` returning `String` and `disconnect(Connection, String handle)`.

Every problem this item was filed for is downstream of that one choice. A consumer whose routine takes a user-defined type has no way to call it, because nothing casts and the parameter arrives typed `varchar`. A consumer whose routine takes two arguments has nowhere to put the second. A consumer whose routine resolves identity in the database cannot see the result in Java, because the OUT value is captured as an opaque `String` on a private field. `<handle>true</handle>` exists as an author-declared boolean, cross-checked against a second author-declared boolean, to describe what the routine's own signature already states.

Fixing that in place means teaching graphitron to *type* a database call it generates: resolve the routine name against the jOOQ catalog, reflect parameters and directions, distinguish the value setter from the `Field` overload, distinguish an OUT parameter's generated getter from a `RETURNS` function's inherited `getReturnValue()`, handle Oracle's package-qualified naming, reject overloads it cannot disambiguate, and fail closed when jOOQ codegen emitted no routines. That is a catalog-resolution subsystem built to reconstruct type information **jOOQ codegen already generated for the consumer**, in their own module, as a typed routine class with typed setters.

So do not generate the call. Name the consumer's method in config, resolve it at build time with the reflection graphitron already does for `@service`, and emit a direct call.

## Design

The SPI is a signature contract, not a class to implement. Graphitron names a static method in config, reflects it at build time, and emits a direct call; whether jOOQ generated the method or the consumer wrote it is invisible to the resolver, because the contract is signature-shaped:

* **Public static.**
* **Exactly one seam parameter**, typed `org.jooq.Configuration` or `java.sql.Connection`, anywhere in the parameter list. The seam rule is also the overload selector: among same-named methods on the named class, exactly one must carry a seam parameter; zero or several is a build-time rejection naming the candidates.
* **Mount:** the remaining parameters, in declaration order, are the payload; the return type is the handle, `void` meaning handle-less.
* **Unmount:** the remaining parameters are either exactly the handle type or empty. Empty is legal even when mount returns a handle (a disconnect routine that clears session state takes no argument; the handle is still retained for the contextArgument publication); a single parameter of any other type is the handle-mismatch rejection, naming both signatures. The return type is unconstrained and discarded, so pointing unmount at a routine that happens to return a status value is not a rejection.
* **Checked exceptions** go through the existing `MethodRef.declaredExceptions()` rejection; unchecked propagates into the fail-closed eviction.

The contract is written to fit what jOOQ codegen already emits, because that output is not ours to change. For every routine, jOOQ generates into the consumer's `Routines` class a static executing method of exactly this shape (verified against `graphitron-sakila-db`'s generated output):

```java
// jOOQ-generated, in the consumer's own module
public static SessionHandleRecord connect(Configuration configuration, ClaimsRecord pClaims) { ... }
```

So the common case is zero hand-written Java:

```xml
<sessionState>
  <mount>com.example.db.Routines#connect</mount>
  <unmount>com.example.db.Routines#disconnect</unmount>
  <stateSurvivesTransactions>true</stateSurvivesTransactions>
</sessionState>
```

The same-named `Field`-expression overloads jOOQ generates beside the executing method carry no seam parameter, so the seam rule resolves `Routines` names without a grammar for picking an overload.

When the call needs massaging (a procedure with several OUT parameters, a mount combining two calls, a payload reshaped before binding), the consumer writes a facade satisfying the same contract, and the resolver cannot tell the difference:

```java
public final class KernelIdentity {
    public static SessionHandleRecord mount(Configuration cfg, ClaimsRecord claims) {
        var call = new Connect();          // jOOQ-generated routine class
        call.setPClaims(claims);           // typed setter, jOOQ binds the composite
        call.execute(cfg);
        return call.getPHandle();
    }
    public static void unmount(Configuration cfg, SessionHandleRecord handle) { /* symmetric */ }
}
```

Graphitron reflects the named methods at build time and emits a direct call into the generated hook body. `ServiceCatalog` is already "the mirror of `JooqCatalog` for the service layer: it wraps the catalog and adds the Java-reflection logic needed to introspect service classes at build time", and `MethodRef` is already "a resolved reference to a user-provided Java method" carrying `className`, `methodName`, a javapoet `TypeName` return type off `getGenericReturnType()`, and params in declaration order. A session hook is a user-provided Java method, so this is that carrier's population, not a new one. (This is the opposite conclusion from reusing `RoutineRef`, which is a catalog handle whose params bind from GraphQL arguments and source columns; the distinction is what the thing *is*.)

Everything the item was reaching for is then a reflected fact rather than built machinery:

* **Typed payload.** The mount method's payload parameter type is whatever the consumer declared: a jOOQ `UDTRecord` for a composite, a `String` for a raw JWS, a record of their own. jOOQ does the binding, because it is jOOQ's own generated setter doing it.
* **Typed handle.** The reflected return type. Because `unmount`'s parameter is reflected too, "connect's output type matches disconnect's input type" is checked against two real signatures at build time, and the mismatch message names both. `<handle>` is deleted: its content is the return type being `void` or not.
* **Multiple arguments.** A Java signature fact. `MethodRef.params()` is already an ordered list; each payload parameter becomes either a dedicated factory slot or a binding to a declared contextArgument (below). No arity machinery.
* **Build time, not runtime.** The consumer's class is named in config and resolved during generation, so nothing is registered, nothing is dispatched polymorphically, and the emitted call site is a direct static invocation. A misnamed *routine* additionally stops needing any check from graphitron: the consumer's module does not compile. The enforcer is javac, in the module that owns the claim.
* **The facade stays static and typed.** Because the payload type is known at generation time, `Graphitron.newOwnedExecutionInput(ClaimsRecord claims, ...)` keeps its current static shape with a better type. No generics on the runtime, no move to an instance method.
* **`R640` collapses.** Publishing the database's identity resolution as a contextArgument becomes "publish the value the runtime already holds", whose type is the reflected return type.

**How the payload travels: every hop is generated, so every hop is typed.** The facade constructs a generated carrier record with one component per mount payload parameter, in declaration order (for the example above, `record MountArguments(ClaimsRecord claims)`), and stores it in the `GraphQLContext` under one graphitron-owned key, replacing `CLAIMS_KEY`'s bare `String`. The instrumentation reads the record typed and hands it to `acquire`; the generated `SessionHook` implementation spreads the components back into the direct call; `PinnedConnection` retains the record, which is what the `afterSettle` re-mount replays, and mount's return value is stored typed beside it, so unmount's inputs are wholly runtime-internal and the consumer supplies the payload exactly once per request. The record is generated uniformly even for a single payload parameter, one code path and a stable named type under the key (which the handle's contextArgument publication also leans on); a mount whose only parameter is the seam gets no record and no factory slot. The one untyped hop, the string-keyed `GraphQLContext`, has its write and read sites generated from the same reflection pass, so it is safe by construction, the same trick the instrumentation already plays with today's `String`.

**Payload parameters bind by name to declared contextArguments.** The schema's contextArguments and the mount payload are the same kind of thing, named per-request values, and giving them parallel factory slots would let the same fact enter the request twice: a caller could pass one tenant to the fetchers and another to the identity mount, and nothing anywhere could notice. So mount payload parameters follow the rule `@service` methods already follow for `ParamSource.Context`: the context key is the parameter name. A payload parameter whose name matches a declared contextArgument gets no new factory slot; the factory assembles the carrier record from the contextArgument parameter it already has, so the value enters once and flows to both destinations from one parameter. A parameter matching no contextArgument becomes a dedicated leading slot, which is exactly right for the claims-like inputs whose types (a jOOQ `UDTRecord`, a raw JWS) are deliberately not schema-declarable. Type agreement between a bound parameter and its contextArgument is checked at build time, beside the existing contextArgument type-agreement validation, with a rejection naming both declarations. The factory's arity is therefore the claims-like slots plus the *union* of names, not the sum. Two consequences are accepted explicitly. Adding a contextArgument whose name matches an existing mount parameter re-binds it and removes a factory slot; that is a signature change on a schema edit, but it fails the consumer's compile visibly with both declarations in view. And parameter names become load-bearing: when the hook's class was compiled without `-parameters`, names are absent, every payload parameter degrades to a dedicated slot with a synthesized name, and `ServiceCatalog`'s existing once-per-build warning fires. jOOQ's `Routines` methods land in the dedicated-slot case by default (`pClaims` matches no contextArgument), and a hand-written facade opts into binding by choosing the name, in its own signature, with no annotation and no extra config.

**Graphitron hands over a `Configuration`, and that is load-bearing.** The published request `DSLContext` carries `GraphitronTransactionProvider` on its live configuration, the transaction-demarcation seam; if a hook ran through that, "both halves run outside any transaction" would stop being structural. Because graphitron constructs what it passes, it passes a provider-free `DSL.using(connection, dialect).configuration()`, and the invariant stays structural rather than becoming a documented request. Accept a declared `Connection` parameter too, for a consumer who wants raw JDBC.

**The generated `SessionHook` interface stays, and becomes sealed.** It is generated code between generated classes, so it is not a runtime dependency; it gives `GraphitronRuntime` a field to hold and `PinnedConnection` something to call. Sealing it to permit only the generated implementation and a generated no-op makes "this is not a consumer extension point" structural, exactly as `GraphitronContext` is sealed to permit only `GraphitronContextImpl`. Its two methods take the reflected payload and handle types.

## What this deletes

The whole string-named-routine path, and everything the catalog-reflection design would have needed:

* `<connect>` / `<disconnect>` / `<handle>`; `SessionStateBinding.HookBinding`, `AbstractRewriteMojo.toRawHook`.
* `SessionStateConfig.FunctionHooks`, `Unmount.PairedDisconnect`'s handle component, `SessionStateConfig.RawHook`, and the handle-agreement rules in `SessionStateConfig.from`.
* `ConnectionRuntimeClassGenerator.functionHookImpl` and `functionDisconnect`'s callable-string assembly.
* Never built: routine-name resolution off the catalog, the Oracle package-qualified probe, the `Pk_Ras.Connect` schema-versus-package ambiguity, overload disambiguation grammar, the value-setter versus `Field`-overload pick, the OUT-getter versus inherited-`getReturnValue()` fork, the `<routines>true</routines>` precondition, and the `sql_routine` fact-relation question.

`SessionStateConfig` keeps three arms (`None`, `Variables`, and the method-hook arm replacing `FunctionHooks`), and `<stateSurvivesTransactions>` stays a declaration, since it is not a signature fact. `MethodRef` already carries `declaredExceptions()`, which `FieldBuilder.checkDeclaredCheckedExceptions` uses to reject an uncovered checked exception at classify time, so the hook's `throws` story is existing machinery rather than an open question.

## Unmount is optional

The shipped design rejects `<connect>` without `<disconnect>` on the grounds that "identity that mounts must unmount", and offers an empty `<disconnect/>` as an explicit opt-out whose javadoc calls it an exposure. That is too strong, and it charges every request a round trip for it.

Mount-only is sound whenever mount establishes identity *wholesale* rather than merging into whatever the connection already had, because then the mount at the start of operation N+1 overwrites everything the mount at operation N wrote. Since the same mount method runs every time, the set of state it writes is constant, so the overwrite is total by construction. Nothing can observe stale identity, because nothing reads scoped data on a pooled connection before a mount has run on it. The condition on that argument is the part worth stating rather than assuming: it holds while every reader of scoped data mounts first, which in practice means the pool is graphitron's. A consumer sharing that pool with their own DAO code, a migration tool, or an admin script has a reader that does not mount, and for them unmount is what keeps the previous caller's identity from being read. That is a property of the deployment, not of the schema, so it is the consumer's call and not something graphitron can infer.

So: `<unmount>` is simply optional. Omitting it means mount-only, with no opt-out ceremony and no rejection. This collapses `SessionStateConfig.Unmount` (and both its arms) into an optional method reference on the method-hook arm, and it removes the pairing-validation family from `SessionStateConfig.from` along with the handle rules.

Latency is the payoff, and it is larger than one call. Unmount costs a round trip at every release. It also costs at every mutation-field settle: without `<stateSurvivesTransactions>`, `PinnedConnection.afterSettle` re-fires the *pair*, so a mount-only hook halves the re-fire to a single re-mount. That re-mount is exactly the overwrite the mount-only argument rests on, so the two fit together rather than trading off.

**Correcting the record while deleting it.** `Unmount.UnmountFree`'s javadoc states that "the generation-time warning in `SessionStateWarnings` names this exposure". No such warning exists: `SessionStateWarnings.forConfig`'s `FunctionHooks` arm returns `List.of()`, and the only two warnings are `no-session-state` and `session-state-convention-fence`. The exposure was asserted in prose with nothing enforcing it, which is the shape the project's own principles call out, and it is evidence that the mount-must-unmount rule was never load-bearing in code. The stale sentence goes with the type. Separately, the `session-state-convention-fence` message text points readers at "the function-hook `<connect>`/`<disconnect>` form" and needs updating to the new element names.

**The `<variables>` sugar is untouched.** It is the one place graphitron genuinely should generate SQL, because there is no consumer method to call. Its generated implementation keeps its current shape, and every current sugar consumer, including `graphitron-sakila-example` and `SessionHookExecutionTest`, changes nothing.

## Implementation

* **Config.** Replace `<connect>`/`<disconnect>` with `<mount>`/`<unmount>` taking `fqcn#method`, `<unmount>` optional. Keep `<stateSurvivesTransactions>`; drop the handle rules and the pairing rules, and collapse `Unmount` into an optional method reference. An `<unmount>` with no `<mount>` stays a rejection: unmounting what nothing mounted is a defect in either direction of reading it.
* **Resolution.** Reflect both methods through `ServiceCatalog`'s existing Java-reflection path into `MethodRef.StaticOnly` (already the variant for "static-by-construction method references"), producing a resolved carrier holding both `MethodRef`s plus the payload and handle types. The seam parameter (`Configuration` or `Connection`) is identified by type, not position, among each method's declared parameters, and doubles as the overload selector: among same-named methods on the named class, exactly one may carry a seam parameter. After the seam is removed, each mount payload parameter is classified context-bound (its name matches a declared contextArgument, and the types must agree) or dedicated (everything else). Rejections are the existing reflection family (`ReflectionError`, unresolvable class or method, non-static, no seam parameter or more than one candidate, seam-overload ambiguity naming the candidates, handle-type mismatch between the two signatures, context-binding type mismatch naming both declarations).
* **Emission.** `functionHookImpl` becomes a direct-call emitter that spreads the carrier record back into declaration order: `return com.example.db.Routines.connect(cfg, args.claims());`. The generated hook builds its own provider-free `Configuration` from the connection and the runtime's dialect, or passes the raw `Connection` when that is what the method declares.
* **Carrier record.** Generated beside the runtime classes, one component per mount payload parameter in declaration order, context-bound and dedicated alike; the factory constructs it, the instrumentation reads it typed under the graphitron-owned key, `acquire` takes it. Component names come from reflection when `-parameters` is present, synthesized otherwise.
* **Interface.** `ConnectionRuntimeClassGenerator.sessionHook` emits a sealed `SessionHook` typed by the reflected payload and handle types, permitting the generated implementation and a generated no-op replacing the current anonymous `NONE`.
* **Carrier retyping.** `PinnedConnection`'s retained payload field holds the carrier record and the mutable handle field takes the reflected handle type; `acquire` keeps normalizing autocommit before mount and failing closed by evicting on a throwing mount; `afterSettle` keeps the re-fire, replaying the retained record. `CLAIMS_KEY` is replaced by the carrier record's own key, read typed in `GraphitronConnectionInstrumentationGenerator`.
* **Facade.** `newOwnedExecutionInput` gains one dedicated leading slot per unbound payload parameter, ahead of the schema's contextArgument slots; context-bound parameters add no slot, and the factory assembles the carrier record from the contextArgument parameters it already declares. Javadoc is emitted from the same resolved facts.
* **Two prose corrections that ship with the code, not before it.** `Unmount.UnmountFree`'s javadoc claims `SessionStateWarnings` warns about an unmount-free configuration; it does not, and which way that resolves depends on this item's unmount decision (delete the claim if unmount becomes optional, add the warning if it does not), so the edit belongs here rather than as a standalone comment fix. And `SessionStateWarnings`' `session-state-convention-fence` message directs readers to "the function-hook `<connect>`/`<disconnect>` form with a SECURITY DEFINER connect"; that text is accurate against today's elements and becomes wrong the moment they are renamed, so it is repointed in the same change that renames them.
* **Fixture and execution coverage.** The form has never run end to end: `graphitron-sakila-example` configures the `<variables>` sugar at the plugin level, so all five `rewrite-generate*` executions inherit and generate it; `SessionHookExecutionTest` drives the sugar, and no mount/unmount routine exists in `graphitron-sakila-db`. Add a composite payload type, a composite handle type, and the two routines to `init.sql`, plus a fixture class whose static methods call them through their generated routine classes. Prove the round trip against real PostgreSQL with RLS, in the shape `SessionHookExecutionTest` establishes: RLS-scoped reads see only permitted rows, a mutation's post-commit read-back still does (the settle re-fire), identity is absent after unmount, and a throwing mount fails closed.

## Trade-offs to accept explicitly

* **The contract is a convention to learn, but the common case is zero hand-written Java.** `<mount>Routines#connect</mount>` names jOOQ's own generated method; the consumer writes a facade only when the call needs massaging, and the facade is those ~6 lines of their own generated jOOQ API, typed and reviewable in their own module. What replaces the deleted resolution subsystem is a signature convention (seam parameter, declaration order, name binding), and it is the convention `@service` already teaches. The zero-code path for the common Postgres case, the `<variables>` sugar, also still exists.
* **Factory arity still grows with genuinely distinct per-request facts.** Name binding removes the duplication, not the union. The factory is one call site per application, written once in the consumer's HTTP layer, so long-but-flat is tolerable there; and a consumer drowning in payload slots can always bundle, since `mount(Configuration cfg, MyRequestIdentity bundle)` is one slot by the same contract. A growth-proof named surface over the factory (a staged builder) would apply to contextArguments generally, predates this item's additions, and belongs to a separate Backlog item rather than being absorbed here.
* **The dev goal and MCP `execute` tool need a payload.** `<devDatabase><claims>` supplies one string, which works unchanged for the sugar and for any `String`-payload method. A custom payload type cannot be built from a string by graphitron. Recommendation: support string payloads and the sugar, and degrade with a message naming the limitation, rather than requiring consumers to write a parse method for a dev tool's benefit.

## Design decisions closed at Spec → Ready review

* **Config grammar.** Keeps `<mount>com.example.KernelIdentity#mount</mount>`, not a `<class>`/`<method>` pair. `ExternalCodeReference` (the SDL-level convention for naming a Java class and method, used by `@service`/`@condition`/`@externalField`/`@enum`) splits them into separate `className`/`method` fields, but `<sessionState>` is Mojo XML config, not an SDL input type, and its immediate local precedent, the `<call>Pk_Ras.Connect</call>` single-string form it replaces, is compact. The `#` form the spec already uses throughout Design, Implementation, and the user-doc draft is consistent with that local precedent and needs no rewrite.
* **Whether the methods must be static.** Static-only (`MethodRef.StaticOnly`) for v1, as the Design and Implementation sections already commit to throughout. A session hook holds no state worth injecting, so there is no case pulling toward `MethodRef.Service.callShape()` yet; add instance support via a fresh Backlog item if a real consumer needs it.
* **Where the payload parameter sits relative to the connection.** Detect the seam parameter by type, not position: exactly one declared parameter typed `Configuration` or `Connection`, anywhere in the parameter list; zero or more than one match is a reflection rejection naming the method. This avoids a positional convention a consumer has to remember, and it costs nothing extra to implement since the reflection walk already has to inspect every parameter's type to build `MethodRef.params()`.

## Design decisions closed at the SPI revision

A design pass on the SPI shape, prompted by the observation that this is a mapping problem over methods the consumer already has, closed four more:

* **The SPI is a signature contract, not a facade class.** jOOQ's generated `Routines` executing methods satisfy the contract as-is, so the primary documented path is naming them directly, and the hand-written facade demotes to the escape hatch. The contract is written to fit jOOQ's fixed output, not the other way around; the resolver never learns whether a method was generated or hand-written, which is what makes the two paths one path.
* **Overload selection is the seam rule.** jOOQ emits same-named `Field`-expression overloads beside every executing method, so overloading is the normal case, not a corner. Exactly one same-named method may carry a seam parameter; ambiguity is a rejection naming the candidates. No grammar for naming a specific overload.
* **Context binding triggers on the parameter name, not an XML declaration.** An explicit `<bind>` element was considered and rejected: parameter-name-as-context-key is the established `ParamSource.Context` semantics `@service` methods already use, so a second grammar for the same mapping would be the anomaly, and the binding is visible in the consumer's own signature. The cost, load-bearing parameter names, is bounded by the `-parameters` degradation (dedicated slots plus the existing warning) and by the fact that a binding change surfaces as a factory signature change at the consumer's compile.
* **The carrier record is uniform.** Generated even for a single payload parameter, so there is one transport path, and the graphitron-owned context key always holds a stable named type.

## User documentation (first-client check)

Draft replacement for the function-hook subsection of `docs/manual/reference/mojo-configuration.adoc` § Session identity. If this does not read more simply than what it replaces, the design is wrong.

> **Mounting identity from your own code.** When identity lives behind a database routine, you usually already have the method: jOOQ generates an executing method for every routine into your `Routines` class. Name it. `mount` runs on the freshly pinned connection before any operation SQL; `unmount` runs at release:
>
> ```xml
> <sessionState>
>   <mount>com.example.db.Routines#connect</mount>
>   <unmount>com.example.db.Routines#disconnect</unmount>
> </sessionState>
> ```
>
> Graphitron reads the signatures at build time: everything but the `Configuration` parameter is the payload, and the return type is the handle later passed to `unmount`. The generated factory then takes your payload type, so passing the wrong shape is a compile error rather than a cast failure inside the database:
>
> ```java
> var input = Graphitron.newOwnedExecutionInput(claims, userId).query(query).build();
> ```
>
> When the call needs massaging, a procedure with several OUT parameters, a mount that combines two calls, a payload reshaped before binding, write a static method of the same shape and name that instead:
>
> ```java
> public final class KernelIdentity {
>     public static SessionHandleRecord mount(Configuration cfg, ClaimsRecord claims) {
>         var call = new Connect();
>         call.setPClaims(claims);
>         call.execute(cfg);
>         return call.getPHandle();
>     }
>     public static void unmount(Configuration cfg, SessionHandleRecord handle) { ... }
> }
> ```
>
> A payload parameter named like one of your schema's contextArguments is fed from that contextArgument and adds no factory parameter: name a parameter `tenantId` and it means "the tenantId this request already carries", exactly as it would on a `@service` method, so the identity mount and your resolvers cannot be handed different tenants.
>
> The `Configuration` you are handed is bound to the pinned connection and carries no transaction provider, because both methods run outside any transaction, on a connection normalized to autocommit. Declare a `Connection` parameter instead if you want raw JDBC. A throwing `mount` evicts the connection and fails the request before any SQL runs.
>
> **`<unmount>` is optional.** If your `mount` establishes identity wholesale rather than adding to whatever the connection already carried, the next request's mount overwrites the last one's, nothing can read stale identity, and unmount only costs you a round trip per request. Omit it. Keep an unmount when something other than graphitron reads scoped data on the same pool, your own DAO code, a migration tool, an admin script, because those readers never mount and would inherit the previous caller's identity.
>
> Add `<stateSurvivesTransactions>true</stateSurvivesTransactions>` only if your mounted state genuinely survives a commit or rollback; otherwise graphitron re-mounts after each mutation-field settle (re-firing the pair, if you have an unmount).
>
> For the common Postgres case you need none of this: the `<variables>` sugar generates both halves from a list of session variables.

Also rewrite: the "Producing the claims payload" and integrity-gradient sections of `docs/architecture/reference/runtime-extension-points.adoc`, `docs/security.adoc`, and the owned-path paragraph of `docs/manual/how-to/tenant-scoping.adoc`. The integrity gradient reads better against a method than against a config block: the cryptographic fence is a `mount` passing a raw token for in-database verification, the enforced fence is a `mount` calling a definer-rights package, and the gradient is visible in the consumer's own code.

## Tests

* **Unit.** `SessionStateConfigTest` for the method-hook arm with and without `<unmount>`, and for the surviving unmount-without-mount rejection; the handle and pairing rules go with the elements they validated. Reflection rejections: unresolvable class, unresolvable method, non-static, wrong seam parameter, seam-overload ambiguity (two same-named seam-bearing methods) and its complement (jOOQ's triple-overload shape resolving to the one executing method), handle-type mismatch between the two signatures, context-binding type mismatch naming both declarations. Contract acceptance: handle-ignoring unmount (empty non-seam parameters against a handle-returning mount), discarded unmount return value. Carrier record: declaration order, single-parameter uniformity, seam-only mount producing no record and no slot, synthesized component names when `-parameters` is absent (with the existing once-per-build warning). `SessionHookImplGeneratorTest` for the emitted direct call spreading the record, the mount-only shape (release does nothing, `afterSettle` re-mounts without an unmount call), the sealed interface, the generated no-op, and the provider-free `Configuration`.
* **Pipeline.** The owned factory's parameter list against a reflected multi-parameter payload, including the single-`String` case that reproduces today's signature, and the union arity: a payload parameter sharing a declared contextArgument's name adds no slot, and the record is assembled from the shared parameter.
* **Compilation.** A consumer-shaped hook class and the generated sources compile at `<release>17</release>` in `graphitron-sakila-example`.
* **Execution.** The form's first end-to-end proof, with composite types both directions, per the fixture above. Point `<mount>` directly at the jOOQ-generated `Routines` method for one execution shape and at the hand-written facade for another, so "generated and hand-written are indistinguishable to the resolver" is proven rather than asserted.
* **Warnings.** `SessionStateWarningsTest` keeps `no-session-state` and `session-state-convention-fence` (a sugar-side warning, unaffected except for its message text naming the old elements). No unmount-free warning is added: mount-only is a supported configuration, and its precondition is about the consumer's pool, which graphitron cannot see.

## Retired vocabulary

* `<sessionState>`'s `<connect>`, `<disconnect>` and `<handle>` elements; "connect callable", "disconnect callable", "the callables", and "function-hook form".
* `SessionStateConfig.FunctionHooks`, `SessionStateConfig.RawHook`, `SessionStateBinding.HookBinding`, and the whole `SessionStateConfig.Unmount` hierarchy (`Unmount`, `Unmount.PairedDisconnect`, `Unmount.UnmountFree`), replaced by an optional method reference.
* "identity that mounts must unmount", "unmount-free opt-out", and the claim that `SessionStateWarnings` warns about an unmount-free configuration (it never did).
* `CLAIMS_KEY` and its `no.sikt.graphitron.request.claims` value.
* "opaque claims payload" / "the opaque claims" as the description of the mount parameter. The word `claims` itself is not retired: the `<variables>` sugar's `<claim>` mapping and the dev-goal `<claims>` config both name a genuine claims document.
