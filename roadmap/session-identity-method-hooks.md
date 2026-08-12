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

The consumer writes plain static methods. No interface to implement, nothing to register, no runtime seam:

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

```xml
<sessionState>
  <mount>com.example.KernelIdentity#mount</mount>
  <unmount>com.example.KernelIdentity#unmount</unmount>
  <stateSurvivesTransactions>true</stateSurvivesTransactions>
</sessionState>
```

Graphitron reflects both methods at build time and emits a direct call into the generated hook body. `ServiceCatalog` is already "the mirror of `JooqCatalog` for the service layer: it wraps the catalog and adds the Java-reflection logic needed to introspect service classes at build time", and `MethodRef` is already "a resolved reference to a user-provided Java method" carrying `className`, `methodName`, a javapoet `TypeName` return type off `getGenericReturnType()`, and params in declaration order. A session hook is a user-provided Java method, so this is that carrier's population, not a new one. (This is the opposite conclusion from reusing `RoutineRef`, which is a catalog handle whose params bind from GraphQL arguments and source columns; the distinction is what the thing *is*.)

Everything the item was reaching for is then a reflected fact rather than built machinery:

* **Typed payload.** The mount method's payload parameter type is whatever the consumer declared: a jOOQ `UDTRecord` for a composite, a `String` for a raw JWS, a record of their own. jOOQ does the binding, because it is jOOQ's own generated setter doing it.
* **Typed handle.** The reflected return type. Because `unmount`'s parameter is reflected too, "connect's output type matches disconnect's input type" is checked against two real signatures at build time, and the mismatch message names both. `<handle>` is deleted: its content is the return type being `void` or not.
* **Multiple arguments.** A Java signature fact. `MethodRef.params()` is already an ordered list; the facade grows one typed parameter per reflected payload parameter. No arity machinery.
* **Build time, not runtime.** The consumer's class is named in config and resolved during generation, so nothing is registered, nothing is dispatched polymorphically, and the emitted call site is a direct static invocation. A misnamed *routine* additionally stops needing any check from graphitron: the consumer's module does not compile. The enforcer is javac, in the module that owns the claim.
* **The facade stays static and typed.** Because the payload type is known at generation time, `Graphitron.newOwnedExecutionInput(ClaimsRecord claims, ...)` keeps its current static shape with a better type. No generics on the runtime, no move to an instance method.
* **`R640` collapses.** Publishing the database's identity resolution as a contextArgument becomes "publish the value the runtime already holds", whose type is the reflected return type.

**Graphitron hands over a `Configuration`, and that is load-bearing.** The published request `DSLContext` carries `GraphitronTransactionProvider` on its live configuration, the transaction-demarcation seam; if a hook ran through that, "both halves run outside any transaction" would stop being structural. Because graphitron constructs what it passes, it passes a provider-free `DSL.using(connection, dialect).configuration()`, and the invariant stays structural rather than becoming a documented request. Accept a declared `Connection` parameter too, for a consumer who wants raw JDBC.

**The generated `SessionHook` interface stays, and becomes sealed.** It is generated code between generated classes, so it is not a runtime dependency; it gives `GraphitronRuntime` a field to hold and `PinnedConnection` something to call. Sealing it to permit only the generated implementation and a generated no-op makes "this is not a consumer extension point" structural, exactly as `GraphitronContext` is sealed to permit only `GraphitronContextImpl`. Its two methods take the reflected payload and handle types.

## What this deletes

The whole string-named-routine path, and everything the catalog-reflection design would have needed:

* `<connect>` / `<disconnect>` / `<handle>`; `SessionStateBinding.HookBinding`, `AbstractRewriteMojo.toRawHook`.
* `SessionStateConfig.FunctionHooks`, `Unmount.PairedDisconnect`'s handle component, `SessionStateConfig.RawHook`, and the handle-agreement rules in `SessionStateConfig.from`.
* `ConnectionRuntimeClassGenerator.functionHookImpl` and `functionDisconnect`'s callable-string assembly.
* Never built: routine-name resolution off the catalog, the Oracle package-qualified probe, the `Pk_Ras.Connect` schema-versus-package ambiguity, overload disambiguation grammar, the value-setter versus `Field`-overload pick, the OUT-getter versus inherited-`getReturnValue()` fork, the `<routines>true</routines>` precondition, and the `sql_routine` fact-relation question.

`SessionStateConfig` keeps three arms (`None`, `Variables`, and the method-hook arm replacing `FunctionHooks`) and its pairing shape: mount-without-unmount is still rejected, the empty-`<unmount/>` opt-out still exists, and `<stateSurvivesTransactions>` stays a declaration, since none of those are signature facts. Two things the interface-based sketch had to give up come back: the **unmount-free warning survives** (an omitted `<unmount>` is visible to the generator where an empty method body is not), and `MethodRef` already carries `declaredCheckedExceptions`, which `FieldBuilder.checkDeclaredCheckedExceptions` uses to reject an uncovered checked exception at classify time, so the hook's `throws` story is existing machinery rather than an open question.

**The `<variables>` sugar is untouched.** It is the one place graphitron genuinely should generate SQL, because there is no consumer method to call. Its generated implementation keeps its current shape, and every current sugar consumer, including `graphitron-sakila-example` and `SessionHookExecutionTest`, changes nothing.

## Implementation

* **Config.** Replace `<connect>`/`<disconnect>` with `<mount>`/`<unmount>` taking `fqcn#method`. Keep the pairing rules and `<stateSurvivesTransactions>` in `SessionStateConfig.from`; drop the handle rules.
* **Resolution.** Reflect both methods through `ServiceCatalog`'s existing Java-reflection path into `MethodRef.StaticOnly` (already the variant for "static-by-construction method references"), producing a resolved carrier holding both `MethodRef`s plus the payload and handle types. Rejections are the existing reflection family (`ReflectionError`, unresolvable class or method, non-static, wrong first parameter, handle-type mismatch between the two signatures).
* **Emission.** `functionHookImpl` becomes a direct-call emitter: `return com.example.KernelIdentity.mount(cfg, args);`. The generated hook builds its own provider-free `Configuration` from the connection and the runtime's dialect, or passes the raw `Connection` when that is what the method declares.
* **Interface.** `ConnectionRuntimeClassGenerator.sessionHook` emits a sealed `SessionHook` typed by the reflected payload and handle types, permitting the generated implementation and a generated no-op replacing the current anonymous `NONE`.
* **Carrier retyping.** `PinnedConnection`'s retained payload field and mutable handle field take the reflected types; `acquire` keeps normalizing autocommit before mount and failing closed by evicting on a throwing mount; `afterSettle` keeps the re-fire. `CLAIMS_KEY` is replaced by a key named for what it now carries, read typed in `GraphitronConnectionInstrumentationGenerator`.
* **Facade.** `newOwnedExecutionInput` takes one typed parameter per reflected payload parameter, ahead of the schema's contextArgument slots; javadoc is emitted from the same resolved facts.
* **Fixture and execution coverage.** The form has never run end to end: the example configures the sugar in both executions, `SessionHookExecutionTest` drives the sugar, and no mount/unmount routine exists in `graphitron-sakila-db`. Add a composite payload type, a composite handle type, and the two routines to `init.sql`, plus a fixture class whose static methods call them through their generated routine classes. Prove the round trip against real PostgreSQL with RLS, in the shape `SessionHookExecutionTest` establishes: RLS-scoped reads see only permitted rows, a mutation's post-commit read-back still does (the settle re-fire), identity is absent after unmount, and a throwing mount fails closed.

## Trade-offs to accept explicitly

* **The consumer writes ~6 lines of Java where they previously wrote a routine name.** Those lines are their own generated jOOQ API, typed and reviewable in their own module, and they replace a resolution subsystem in graphitron. The zero-code path still exists for the common Postgres case.
* **The dev goal and MCP `execute` tool need a payload.** `<devDatabase><claims>` supplies one string, which works unchanged for the sugar and for any `String`-payload method. A custom payload type cannot be built from a string by graphitron. Recommendation: support string payloads and the sugar, and degrade with a message naming the limitation, rather than requiring consumers to write a parse method for a dev tool's benefit.

## Open decisions for the reviewer

* **Config grammar.** `<mount>com.example.KernelIdentity#mount</mount>` versus a `<class>` plus `<method>` pair, versus one `<class>` with conventional `mount`/`unmount` names. The `#` form matches how a reader already reads a method reference; the split form matches `SchemaInputBinding`'s style of nested elements.
* **Whether the methods must be static.** `MethodRef.StaticOnly` is the natural fit and a session hook has no state worth holding, but `@service` supports instance call shapes via `MethodRef.Service.callShape()`, so instance support is available if a consumer case wants dependency injection into the hook.
* **Where the payload parameter sits relative to the connection.** `mount(Configuration, ClaimsRecord)` reads naturally, but reflecting "first parameter is the connection seam, the rest is payload" is a positional convention. Consider requiring the seam parameter first and rejecting otherwise, which is a clear rule, versus detecting it by type anywhere in the list.

## User documentation (first-client check)

Draft replacement for the function-hook subsection of `docs/manual/reference/mojo-configuration.adoc` § Session identity. If this does not read more simply than what it replaces, the design is wrong.

> **Mounting identity from your own code.** When identity lives behind a database routine, write two methods and name them. `mount` runs on the freshly pinned connection before any operation SQL; `unmount` runs at release. Graphitron reads their signatures at build time, so the payload type and the handle type are whatever you declared, and you call your routine through the class jOOQ generated for it:
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
> ```xml
> <sessionState>
>   <mount>com.example.KernelIdentity#mount</mount>
>   <unmount>com.example.KernelIdentity#unmount</unmount>
> </sessionState>
> ```
>
> The generated factory then takes your payload type, so passing the wrong shape is a compile error rather than a cast failure inside the database:
>
> ```java
> var input = Graphitron.newOwnedExecutionInput(claims, userId).query(query).build();
> ```
>
> The `Configuration` you are handed is bound to the pinned connection and carries no transaction provider, because both methods run outside any transaction, on a connection normalized to autocommit. Declare a `Connection` parameter instead if you want raw JDBC. A throwing `mount` evicts the connection and fails the request before any SQL runs. Identity that mounts must unmount, so `<mount>` without `<unmount>` fails the build; an empty `<unmount/>` is the explicit opt-out. Add `<stateSurvivesTransactions>true</stateSurvivesTransactions>` only if your mounted state genuinely survives a commit or rollback; otherwise graphitron re-fires the pair after each mutation-field settle.
>
> For the common Postgres case you need none of this: the `<variables>` sugar generates both halves from a list of session variables.

Also rewrite: the "Producing the claims payload" and integrity-gradient sections of `docs/architecture/reference/runtime-extension-points.adoc`, `docs/security.adoc`, and the owned-path paragraph of `docs/manual/how-to/tenant-scoping.adoc`. The integrity gradient reads better against a method than against a config block: the cryptographic fence is a `mount` passing a raw token for in-database verification, the enforced fence is a `mount` calling a definer-rights package, and the gradient is visible in the consumer's own code.

## Tests

* **Unit.** `SessionStateConfigTest` for the method-hook arm and the surviving pairing rules; the handle rules go with `<handle>`. Reflection rejections: unresolvable class, unresolvable method, non-static, wrong seam parameter, handle-type mismatch between the two signatures. `SessionHookImplGeneratorTest` for the emitted direct call, the sealed interface, the generated no-op, and the provider-free `Configuration`.
* **Pipeline.** The owned factory's parameter list against a reflected multi-parameter payload, including the single-`String` case that reproduces today's signature.
* **Compilation.** A consumer-shaped hook class and the generated sources compile at `<release>17</release>` in `graphitron-sakila-example`.
* **Execution.** The form's first end-to-end proof, with composite types both directions, per the fixture above.
* **Warnings.** `SessionStateWarningsTest` keeps the unmount-free warning (now keyed on an omitted `<unmount>`) and `session-state-convention-fence`, which is a sugar-side warning and unaffected.

## Retired vocabulary

* `<sessionState>`'s `<connect>`, `<disconnect>` and `<handle>` elements; "connect callable", "disconnect callable", "the callables", and "function-hook form".
* `SessionStateConfig.FunctionHooks`, `SessionStateConfig.RawHook`, `SessionStateBinding.HookBinding`, and `handle` as a component of `Unmount.PairedDisconnect`.
* `CLAIMS_KEY` and its `no.sikt.graphitron.request.claims` value.
* "opaque claims payload" / "the opaque claims" as the description of the mount parameter. The word `claims` itself is not retired: the `<variables>` sugar's `<claim>` mapping and the dev-goal `<claims>` config both name a genuine claims document.
