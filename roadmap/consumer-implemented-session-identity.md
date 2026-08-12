---
id: R639
title: "Let the consumer implement the session-identity seam instead of generating its SQL"
status: Spec
bucket: architecture
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-08-12
last-updated: 2026-08-12
---

# Let the consumer implement the session-identity seam instead of generating its SQL

The generated `SessionHook` interface describes itself, in its own emitted javadoc, as "the consumer-owned database session-identity seam". The consumer cannot own it. Its two methods are hardcoded `connect(Connection, String claims)` returning `String` and `disconnect(Connection, String handle)`, so the runtime bakes a graphitron-generated implementation (`ConnectionRuntimeClassGenerator.functionHookImpl`) built from a `<sessionState>` config block that names database routines as strings and binds their arguments with `cs.setString(1, claims)` into a hand-assembled `{ call <fn>(?) }`.

Every problem this item was originally filed for is downstream of that one choice. A consumer whose connect routine takes a user-defined type has no way to call it, because nothing casts and the parameter arrives typed `varchar`. A consumer whose routine takes two arguments has nowhere to put the second. A consumer whose routine resolves identity in the database cannot see the result in Java, because the OUT value is captured as an opaque `String` and stored on a private field. `<handle>true</handle>` exists as an author-declared boolean, cross-checked against a second author-declared boolean, to describe something the routine's own signature already states.

The reason those are hard to fix in place is that fixing them means teaching graphitron to *type* a database call it is generating: resolve the routine name against the jOOQ catalog, reflect its parameters and their directions, distinguish the value setter from the `Field` overload, distinguish an OUT parameter's generated getter from a `RETURNS` function's inherited `getReturnValue()`, handle Oracle's package-qualified naming, reject overloads it cannot disambiguate, and fail closed when jOOQ codegen did not emit routines at all. That is a catalog-resolution subsystem, and it exists only to reconstruct type information that **jOOQ codegen already generated for the consumer**, in their own module, as a typed routine class with typed setters.

So the simpler answer: do not generate the call. Make the seam a real seam.

## Design

`SessionHook` becomes generic in the payload and the handle, and the runtime accepts an implementation instead of baking one:

```java
public interface SessionHook<A, H> {
    H mount(Connection connection, A args) throws SQLException;
    void unmount(Connection connection, H handle) throws SQLException;
    /** Declares that mounted state survives a transaction settle, so no re-fire is needed. */
    default boolean survivesTransactions() { return false; }
}
```

The consumer implements it against their own jOOQ-generated routine class, which is where the typing already lives:

```java
final class KernelIdentity implements SessionHook<ClaimsRecord, SessionHandleRecord> {
    @Override public SessionHandleRecord mount(Connection c, ClaimsRecord claims) {
        var call = new Connect();               // jOOQ-generated routine class
        call.setPClaims(claims);                // typed setter, jOOQ binds the composite
        call.execute(DSL.using(c, SQLDialect.ORACLE).configuration());
        return call.getPHandle();
    }
    @Override public void unmount(Connection c, SessionHandleRecord handle) { /* symmetric */ }
    @Override public boolean survivesTransactions() { return true; }
}
```

```java
var runtime = Graphitron.runtime(dataSource, SQLDialect.POSTGRES, new KernelIdentity());
var input = runtime.newOwnedExecutionInput(claims, userId).query(q).build();
```

Everything the original item was reaching for falls out of the type parameters rather than being built:

* **Typed payload.** `A` is whatever the consumer's routine takes: a jOOQ `UDTRecord` for a composite, a `String` for a raw JWS, a record of their own for anything else. jOOQ binds it, because it is jOOQ's own generated setter doing the binding.
* **Typed handle.** `H` is whatever the routine returns. Connect's output type and disconnect's input type cannot disagree: they are the same type parameter, so the invariant `<handle>` was approximating becomes structural, enforced by javac rather than by a config cross-check.
* **Multiple arguments.** A non-question. One parameter of type `A`; if the routine takes three arguments, `A` is a record with three components, or the mount body simply calls three setters. No arity machinery, no factory parameter-group ordering, no argument-tuple carrier.
* **Build-time verification, stronger and free.** A misnamed routine no longer needs a POM-string resolution check that fails the build: the consumer's module does not compile. The enforcer is javac, in the module that owns the claim.
* **`R640` collapses too.** Publishing the database's identity resolution as a contextArgument becomes "publish the `H` the runtime already holds", with `H` a Java type the consumer named. No UDT resolution, no classifier type-check against a generated record class.

What graphitron still generates is only what is schema-derived: the `SessionHook` interface itself (trivially, with no config input), `GraphitronRuntime<A, H>`, `PinnedConnection<A, H>`, and the owned factory whose contextArgument parameters remain reflected from the schema. What it stops generating is the SQL, which is the part it was never well placed to type.

## What this deletes

The whole function-hook configuration path, and with it the catalog-resolution subsystem this item previously proposed to build:

* `<sessionState>`'s `<connect>`, `<disconnect>`, `<handle>` and `<stateSurvivesTransactions>` elements; `SessionStateBinding.HookBinding` and `AbstractRewriteMojo.toRawHook`.
* `SessionStateConfig.FunctionHooks`, `SessionStateConfig.Unmount` (both arms), `SessionStateConfig.RawHook`, and the entire pairing-validation family in `SessionStateConfig.from` (connect-without-disconnect, handle agreement, the survival-declaration cross-checks). `SessionStateConfig` shrinks to `None | Variables`.
* `ConnectionRuntimeClassGenerator.functionHookImpl` and `functionDisconnect`.
* Everything the reflection design needed and now does not: routine-name resolution off the catalog, the Oracle package-qualified probe, the `Pk_Ras.Connect` schema-versus-package ambiguity, overload disambiguation grammar in the `<call>` value, the value-setter versus `Field`-overload pick, the OUT-getter versus inherited-`getReturnValue()` fork, the `<routines>true</routines>` precondition, the located-pom-coordinate rejection family, and the `sql_routine` fact-relation question.

**The `<variables>` sugar is untouched.** It is the one place graphitron genuinely should generate SQL, because there is no consumer routine to call: it emits `set_config` from a declared variable list. Its generated implementation simply implements `SessionHook<String, Void>`, and the two-argument `Graphitron.runtime(dataSource, dialect)` keeps baking it (or `NONE`), so every current sugar consumer, including `graphitron-sakila-example` and `SessionHookExecutionTest`, needs no change.

## Implementation

* **Generify the interface.** `ConnectionRuntimeClassGenerator.sessionHook` emits `SessionHook<A, H>` with `mount` / `unmount` / `survivesTransactions`. `NONE` becomes `SessionHook<Void, Void>`. Keep the state-contract javadoc: it is the load-bearing prose and it now addresses an implementer who can actually read it as a contract.
* **Thread the type parameters.** `GraphitronRuntime<A, H>` gains a constructor taking the hook; `PinnedConnection<A, H>` retypes its retained `claims` field to `A` and its mutable `handle` field to `H`. `acquire` keeps normalizing autocommit before `mount` and keeps failing closed by evicting on a throwing mount. `afterSettle` keeps the re-fire, now gated on `hook.survivesTransactions()` rather than on a config-derived boolean.
* **Move the owned factory onto the runtime.** `newOwnedExecutionInput(A args, ...contextArgs)` becomes an instance method on `GraphitronRuntime<A, H>`, because that is where `A` is known. The escape-hatch `Graphitron.newExecutionInput(dsl, ...)` stays static and unchanged. Call sites to update: `GraphitronDevExecutorGenerator`, `SakilaGraphitronApplication`, and the docs.
* **Retype the request carrier.** The `graphQLContext` entry holds an `A`; `CLAIMS_KEY` is replaced by a key constant named for what it now carries. `GraphitronConnectionInstrumentationGenerator` reads it typed rather than as `String`.
* **Config surface.** Delete the function-hook elements and their validation; `SessionStateConfig` keeps `None` and `Variables` only.
* **Fixture and execution coverage.** The function-hook form has never run end to end: `graphitron-sakila-example` configures the sugar in both executions, `SessionHookExecutionTest` drives the sugar, and no connect/disconnect routine exists in `graphitron-sakila-db`. Add a composite payload type, a composite handle type, and mount/unmount routines to `init.sql`; add a test-scope `SessionHook` implementation calling them through their generated routine classes; prove the round trip against real PostgreSQL with RLS, in the shape `SessionHookExecutionTest` already establishes. This is also the first proof that a *consumer-written* hook composes with the autocommit contract and the settle re-fire.

## Trade-offs to accept explicitly

* **The consumer writes ~10 lines of Java where they previously wrote a POM string.** For a deployment whose identity logic lives in the database, those lines are their own generated jOOQ API, fully typed and reviewable in their own module, in exchange for deleting a resolution subsystem from graphitron. The zero-code path still exists for the common Postgres case, which is the `<variables>` sugar.
* **The unmount-free build warning disappears.** `SessionStateWarnings` currently warns when `<disconnect/>` opts out of unmounting, because graphitron can see the config say so. It cannot see that an implemented `unmount` has an empty body. The exposure (identity mounted and never unmounted) becomes visible in the consumer's own code instead of in graphitron's warning. The `session-state-convention-fence` warning is unaffected: it fires on the `<variables>` sugar, which survives.
* **The dev goal and MCP `execute` tool need an `A`.** `<devDatabase><claims>` supplies one string, which works unchanged for the sugar and for any `SessionHook<String, ?>`. A custom `A` cannot be built from a string by graphitron. Recommendation: the dev tool supports string-payload hooks and the sugar, and degrades with a message naming the limitation, rather than growing a parse method on the interface for a dev tool's benefit.

## Open decisions for the reviewer

* **Handle-free hooks.** A hook that mounts without producing a handle sets `H = Void` and returns `null`. Acceptable, or is a separate handle-free interface (with `unmount(Connection)`) worth the second type?
* **Naming.** `SessionHook<A, H>` keeps the existing name and minimises churn; `connect`/`disconnect` are renamed to `mount`/`unmount` here because the old names described callables. Confirm, or keep the original method names.
* **Whether `graphitron-jakarta-rest` should carry the interface** instead of it being generated per schema. Generating it costs nothing and needs no config, but a hand-written runtime type would let a consumer implement the hook without a generation step. Java 17 constrains that module's syntax.

## User documentation (first-client check)

Draft replacement for the function-hook subsection of `docs/manual/reference/mojo-configuration.adoc` § Session identity. Note that it moves out of the Mojo reference entirely, since there is no configuration left to document; its new home is the runtime API reference beside the other consumer-implemented seams.

> **Mounting identity yourself.** When your identity lives behind a database routine, implement `SessionHook<A, H>`: `mount` runs on the freshly pinned connection before any operation SQL, `unmount` runs at release. `A` is whatever your routine takes and `H` is whatever it returns, so call it through the routine class jOOQ generated for you and both ends stay typed:
>
> ```java
> final class KernelIdentity implements SessionHook<ClaimsRecord, SessionHandleRecord> {
>     @Override public SessionHandleRecord mount(Connection c, ClaimsRecord claims) {
>         var call = new Connect();
>         call.setPClaims(claims);
>         call.execute(DSL.using(c, SQLDialect.ORACLE).configuration());
>         return call.getPHandle();
>     }
>     @Override public void unmount(Connection c, SessionHandleRecord handle) { ... }
>     @Override public boolean survivesTransactions() { return true; }
> }
> ```
>
> Pass it to the runtime, and the per-request factory takes your payload type:
>
> ```java
> var runtime = Graphitron.runtime(dataSource, SQLDialect.ORACLE, new KernelIdentity());
> var input = runtime.newOwnedExecutionInput(claims, userId).query(query).build();
> ```
>
> Graphitron guarantees the pair runs at mount and release, outside any transaction, on a connection normalized to autocommit, and that a throwing `mount` evicts the connection and fails the request before any SQL runs. Your side owns what identity means. Return `true` from `survivesTransactions()` only if your mounted state genuinely survives a commit or rollback; otherwise graphitron re-fires the pair after each mutation-field settle.
>
> For the common Postgres case you do not need this at all: the `<variables>` sugar generates both halves from a list of session variables.

Also rewrite: the "Producing the claims payload" and integrity-gradient sections of `docs/architecture/reference/runtime-extension-points.adoc`, `docs/security.adoc`, and the owned-path paragraph of `docs/manual/how-to/tenant-scoping.adoc`. The integrity gradient reads better against an implemented method than against a config block: the cryptographic fence is a `mount` that passes a raw token for in-database verification, the enforced fence is a `mount` that calls a definer-rights package, and the gradient is visible in the consumer's own code.

## Tests

* **Unit.** `SessionStateConfigTest` shrinks to the two surviving arms; the deleted pairing rejections go with the elements they validated. `SessionHookImplGeneratorTest` covers the generified interface, `NONE`, and the sugar's implementation of it.
* **Pipeline.** The owned factory's parameter list: `A` first, then the schema's contextArguments, asserted where `GraphitronFacadeGeneratorPipelineTest` asserts it today, plus its move onto the runtime.
* **Compilation.** A consumer-shaped `SessionHook` implementation compiles at `<release>17</release>` in `graphitron-sakila-example`.
* **Execution.** The seam's first end-to-end proof with a consumer-written hook and composite types both directions: RLS-scoped reads see only permitted rows, a mutation's post-commit read-back still does (the settle re-fire), identity is absent after unmount, and a throwing `mount` fails closed.

## Retired vocabulary

* `<sessionState>`'s `<connect>`, `<disconnect>`, `<handle>` and `<stateSurvivesTransactions>` elements, and the phrase "function-hook form".
* `SessionStateConfig.FunctionHooks`, `SessionStateConfig.Unmount`, `Unmount.PairedDisconnect`, `Unmount.UnmountFree`, `SessionStateConfig.RawHook`, `SessionStateBinding.HookBinding`.
* `CLAIMS_KEY` and its `no.sikt.graphitron.request.claims` value.
* "connect callable" / "disconnect callable" / "the callables", and "opaque claims payload" as the description of the mount parameter. The word `claims` itself is not retired: the `<variables>` sugar's `<claim>` mapping and the dev-goal `<claims>` config both name a genuine claims document.
