---
title: "Context-value registry + native multi-tenant fan-out for `@service`"
status: Spec
priority: 5
---

# Context-value registry + native multi-tenant fan-out for `@service`

## Motivation

Two custom resolvers in a downstream Sikt project today bypass `@service` and
write the resolver by hand because the directive can't express what they need:

1. **Multi-tenant person lookup** (`megVedLarested`): for each tenant the
   logged-in user belongs to, open a tenant-scoped `DSLContext`, fan out in
   parallel on the executor, drop nulls, return the union. The service method
   itself is GraphQL-free Java; what doesn't fit `@service` is the
   `ConnectionManager` lookup, the per-tenant `DSLContext` plumbing, and the
   `executor.allOf().join()` shape.

2. **Relay `nodes`**: call the `node` resolver per id in a list argument and
   `allOf` the results. This is GraphQL-resolver composition, not a service
   call; the service can't express it without learning what a `DataFetcher`
   is. Out of scope here, tracked separately as
   [`auto-nodes-relay-resolver.md`](auto-nodes-relay-resolver.md).

The design constraints, from review:

- **Schema carries minimal logic.** No expression language inside directive
  arguments, no per-field flags for orchestration mode.
- **Services are GraphQL-free.** They receive plain values (`String fnr`,
  `String tenantId`, `DSLContext ctx`), never `DataFetchingEnvironment`,
  never `UserInfo` if a derived scalar suffices.
- **Multi-tenant is native.** Per-tenant fan-out is a first-class graphitron
  concept that the schema author opts into by naming `tenantId` in
  `contextArguments`, not a per-directive switch.

## Design

### Context-value registry

Today, `@service(contextArguments: ["tenantId", "userId"])` produces an
untyped per-name lookup: `graphitronContext(env).getContextArgument(env,
"tenantId")` (`ArgCallEmitter.java:100-101`). The name is a literal string
into a `Map<String, Object>` the consumer populates somewhere. There is no
registry of known names, no derived-value support (the `fnr` =
`sudoFodselsnr ?? fodselsnr` rule has to be repeated by every caller), and no
type information at the call site.

The plan is to make the consumer-supplied registry first-class:

```java
public interface ContextValueRegistry {
    void register(ContextValueRegistration<?> registration);
}

public sealed interface ContextValueRegistration<T> {
    String name();
    Class<T> type();

    record Scalar<T>(String name, Class<T> type,
                     Function<DataFetchingEnvironment, T> resolver)
        implements ContextValueRegistration<T> {}

    record FanOut<T>(String name, Class<T> type,
                     Function<DataFetchingEnvironment, Collection<T>> resolver,
                     Optional<DslContextPerElement> dslContext)
        implements ContextValueRegistration<T> {}
}

public interface DslContextPerElement {
    DSLContext open(DataFetchingEnvironment env, Object element);
}
```

The consumer implements `ContextValueRegistry` once (likely on or beside
`GraphitronContext`) and registers names like:

```java
registry.register(new Scalar<>("userInfo", UserInfo.class,
    env -> graphQlContext(env).get("userInfo")));

registry.register(new Scalar<>("fnr", String.class, env -> {
    UserInfo ui = graphQlContext(env).get("userInfo");
    return ui.getSudoFodselsnr() != null ? ui.getSudoFodselsnr() : ui.getFodselsnr();
}));

registry.register(new FanOut<>("tenantId", String.class,
    env -> {
        UserInfo ui = graphQlContext(env).get("userInfo");
        return ui.getInstitusjonsroller().keySet();
    },
    Optional.of((env, element) -> graphQlContext(env)
        .get("connectionManager")
        .createDSLContextWithSettings((String) element))));
```

`fnr`'s sudo fallback is now defined once, in Java, where it belongs.

### Code-generation: discovery and validation

`ServiceCatalog` already reflects consumer service classes at generate time;
add the same shape for the registry. A new `ContextValueCatalog` discovers
the registrations (a single Java entry-point class the consumer points the
generator at, parallel to how services are discovered), captures
`(name, type, kind)` for each, and feeds:

- **Validation:** `FieldBuilder.resolveServiceField()`
  (`FieldBuilder.java:180-203`) checks each name in `contextArguments`
  against the catalog. Unknown name = classification error with a clear
  message, matching the existing pattern for unresolved service refs.
- **Type matching:** `ServiceCatalog.reflectServiceMethod()`
  (`ServiceCatalog.java:148-216`) currently captures the Java parameter
  type as a string but does no validation against the schema's
  `contextArguments`. Add a check: the registered context-value type must be
  assignable to the matching Java parameter type. For `FanOut`, the
  parameter type matches the element type (`String tenantId`, not
  `Set<String>`).
- **Fan-out classification:** if any context arg is a `FanOut` registration,
  the field becomes a fan-out service field. Constraint: at most one
  fan-out context arg per field (multi-axis fan-out is out of scope; flag
  it as a classification error so we don't paint ourselves into a corner).
  The field's GraphQL return type must be a list whose element type matches
  the service method's return type.

### Code-generation: emission

`ArgCallEmitter.java:100-101` becomes type-aware:

```java
case ParamSource.Context ctx -> registry.lookup(param.name()) match {
    case Scalar(_, type, _) ->
        CodeBlock.of("($T) graphitronContext(env).getContextArgument(env, $S)",
            type, param.name());
    case FanOut(_, _, _, _) ->
        CodeBlock.of("$L", fanOutLoopVarName(param.name()));
};
```

(Pseudocode; the real switch is on `ContextValueCatalog.Entry.Kind`, not
the registration sealed type.)

`Scalar` lookups gain a cast to the registered type, fixing the untyped-
`Object` smell at the call site without changing the runtime contract.

`TypeFetcherGenerator.buildQueryServiceRecordFetcher`
(`TypeFetcherGenerator.java:687-707`) gains a fan-out branch when the field
has a fan-out context arg:

```java
// Generated for: megVedLarested: [PersonProfil]
//   @service(service: ..., contextArguments: ["fnr", "tenantId"])
public CompletableFuture<List<PersonProfil>> megVedLarested(DataFetchingEnvironment env) {
    var ctx = graphitronContext(env);
    var executor = ctx.getExecutor(env);
    var fnr = (String) ctx.getContextArgument(env, "fnr");
    var elements = ctx.<String>getContextFanOut(env, "tenantId");

    var futures = elements.stream()
        .map(tenantId -> CompletableFuture.supplyAsync(() -> {
            DSLContext dsl = ctx.openContextDslContext(env, "tenantId", tenantId);
            try {
                return PersonService.getPersonVedLarested(dsl, fnr, tenantId);
            } catch (RuntimeException e) {
                return null;
            }
        }, executor))
        .toList();

    return CompletableFuture
        .allOf(futures.toArray(CompletableFuture[]::new))
        .thenApply(v -> futures.stream()
            .map(CompletableFuture::join)
            .filter(Objects::nonNull)
            .toList());
}
```

`getContextFanOut`, `openContextDslContext`, and `getExecutor` are new
methods on the generated `GraphitronContext` interface
(`GraphitronContextInterfaceGenerator.java:41-49,51-60`). The consumer's
registry implementation backs all three.

The "open the DSLContext on the worker thread, join on the orchestrator
thread so connections close on the right thread" rule is the easy thing to
get wrong by hand and the main reason fan-out should live in codegen.

The non-fan-out path (no `FanOut` context arg present) keeps emitting the
existing synchronous shape; the only change is the cast on `Scalar` lookups.

### Service signature for the motivating example

```java
public class PersonService {
    public PersonProfil getPersonVedLarested(DSLContext ctx, String fnr, String tenantId) {
        var fodselsdato = Integer.parseInt(fnr.substring(0, 6));
        var personnr = Integer.parseInt(fnr.substring(6));
        return ctx.select(...).from(PERSON)
            .where(PERSON.INSTITUSJONSNR_EIER.eq(tenantId))
            .and(PERSON.FODSELSDATO.eq(fodselsdato))
            .and(PERSON.PERSONNR.eq(personnr))
            .fetchOne(0, PersonProfil.class);
    }
}
```

Schema:

```graphql
megVedLarested: [PersonProfil] @service(
    service: { className: "PersonService", method: "getPersonVedLarested" },
    contextArguments: ["fnr", "tenantId"]
)
```

No `notGenerated`, no hand-written `CompletableFuture` plumbing, no
`ConnectionManager` reference outside the registry.

## User documentation (first-client check)

This will land as a new section in the `@service` reference (location TBD;
likely under `graphitron-rewrite/docs/`). Drafted here as the design's
first reader check; if it doesn't read simply, the design is wrong:

> ### Context values
>
> A service method can ask for values from the request context, like the
> caller's identity or the tenant a query targets. Name them in
> `contextArguments`, accept them as plain Java parameters:
>
> ```graphql
> minProfil: PersonProfil @service(
>     service: { className: "PersonService", method: "getProfile" },
>     contextArguments: ["fnr", "tenantId"]
> )
> ```
>
> ```java
> PersonProfil getProfile(DSLContext ctx, String fnr, String tenantId) { ... }
> ```
>
> Context values are registered once per project. Graphitron ships
> registrations for `tenantId`, `userInfo`, and a few others; declare your
> own (e.g. derived values like `fnr`) by implementing
> `ContextValueRegistry` and pointing the generator at it via
> `<contextValueRegistry>` in the Mojo config.
>
> ### Multi-tenant fan-out
>
> If `tenantId` is registered as a fan-out value (the default in
> multi-tenant projects), and a `@service` field returns a list of `T`
> while the service method returns `T`, graphitron fans the call out across
> the user's tenants. Each call gets its own `DSLContext` (from the
> registered per-tenant `DSLContext` factory), runs in parallel on the
> executor, and the framework collects the results, drops nulls, and
> returns the list. The service method writes single-tenant logic; fan-out
> is invisible to it.

## Implementation

Files to touch, by area:

### Runtime contract (`graphitron-rewrite-runtime/`)

- New `ContextValueRegistry`, `ContextValueRegistration` (sealed scalar /
  fan-out), `DslContextPerElement` interfaces.
- Extend the generated `GraphitronContext` shape so it can answer
  `getExecutor(env)`, `getContextFanOut(env, name)`,
  `openContextDslContext(env, name, element)`, in addition to the existing
  `getContextArgument(env, name)`. The default impl backs them with the
  registry; consumers customise by replacing the registry, not the context.
- Built-in registrations: `userInfo` (scalar, GraphQL-context lookup),
  `tenantId` (scalar in single-tenant mode, fan-out in multi-tenant mode,
  selected by Mojo config).

### Catalog + classification (`graphitron-rewrite/.../classification/`)

- New `ContextValueCatalog` mirroring `ServiceCatalog`'s reflection shape;
  loaded from the consumer's registry-impl class, configured via Mojo.
- `FieldBuilder.resolveServiceField()` (`FieldBuilder.java:180-203`):
  validate each name against the catalog; reject unknown names; record
  whether the field is fan-out and (if so) which arg drives it.
- `ServiceCatalog.reflectServiceMethod()` (`ServiceCatalog.java:148-216`):
  add type-assignability check between catalog entry and Java parameter
  (element type for fan-out).
- New field model carrier on the existing service field types
  (`QueryServiceTableField`, `QueryServiceRecordField`, mutation /
  child equivalents): an optional `FanOutContextArg` ref pointing at the
  driving param.

### Emission (`generators/.../`)

- `ArgCallEmitter.java:100-101`: typed `Scalar` cast; emit fan-out loop
  variable for `FanOut` params.
- `TypeFetcherGenerator.java:687-707` (and mutation / child equivalents):
  branch on the field's fan-out flag; emit the fan-out scaffold described
  above in the fan-out arm; leave the existing synchronous emission
  untouched in the scalar-only arm.
- Generated method return type for fan-out fields: keep the schema-driven
  list type but wrap in `CompletableFuture<List<...>>` so graphql-java's
  data-loader dispatch can join the orchestrator thread.

### Configuration (Mojo)

- `<contextValueRegistry>` Mojo parameter (the consumer's registry-impl
  class; mirrors how services are discovered).
- `<multiTenant>` Mojo parameter, default `false`. When true, the built-in
  `tenantId` registration is fan-out; when false, scalar.

## Tests

Match the existing tier conventions
([rewrite-design-principles.md](../docs/rewrite-design-principles.md));
extend each tier rather than duplicating coverage:

- **Classification (L2)** — extend `GraphitronSchemaBuilderTest`,
  `ServiceCatalogTest`: unknown context-value name rejected; type mismatch
  rejected; fan-out flag set when one fan-out arg present; multi-fan-out
  rejected; non-list return on fan-out field rejected.
- **Validation (L3)** — extend the four `*ServiceFieldValidationTest`
  classes: validate the new field model carrier surfaces clean error
  messages.
- **Pipeline (L4)** — extend `ServiceRootFetcherPipelineTest`,
  `FetcherPipelineTest`: end-to-end SDL → emitted fetcher with fan-out
  context arg compiles and matches a structural snapshot of the emitted
  method (no code-string assertions on bodies; assert call shape via
  `MethodSpec` introspection per the design principles).
- **Compile (L5)** — extend the rewrite-fixtures package with a
  schema/service pair using each shape (scalar-only, fan-out scalar,
  fan-out with per-element DSLContext) so the generated code passes
  javac under the existing compile-spec gate.
- **Execute (L6)** — one or two execution fixtures hitting a fan-out
  service with `-Plocal-db`, verifying parallel calls happen on the
  executor and per-tenant `DSLContext` flows through. Null-filtering case
  too.

## Open questions for the reviewer

1. **Where does the registry-impl class live in consumer projects?**
   Co-located with their `GraphitronContext` impl, or a separate top-level
   class wired via Mojo? Implications for how the
   `graphitroncontext-extension-point-docs.md` plan describes the surface.
2. **Multi-tenant as a flag vs. as a fan-out registration?** The current
   draft makes `<multiTenant>true</multiTenant>` flip the built-in
   `tenantId` registration from scalar to fan-out. Cleaner alternative:
   no flag, the consumer's registry registers `tenantId` as fan-out
   themselves, and graphitron ships no built-in. Preference?
3. **Null filtering on fan-out always-on vs. opt-out?** The motivating
   resolver drops nulls; could plausibly want a strict mode that fails the
   whole field if any element threw. Default-on, no opt-out, until a real
   case appears?
4. **Is checked-exception handling in the fan-out arm in scope here, or
   does it wait for [`checked-exceptions-typed-errors.md`](checked-exceptions-typed-errors.md)?**
   The motivating resolver swallows exceptions to null; that's the cheap
   default. Anything richer should compose with the typed-errors plan.
5. **Per-element DSLContext: registered on the fan-out value, or a
   separate registration?** Drafted as a field on `FanOut`; could instead
   be a sibling registration keyed by the same name. Field-on-`FanOut` is
   simpler; sibling allows one fan-out to drive multiple context shapes.
6. **Multi-axis fan-out (e.g., over `tenantId × roleId`):** rejected at
   classification today. Real demand is hypothetical; keep rejected unless
   a use case shows up.

## Roadmap entries (siblings / dependencies)

- **Updates** [`graphitroncontext-extension-point-docs.md`](graphitroncontext-extension-point-docs.md):
  the registry interface is the documentable extension point that plan is
  about. Either subsume that plan into this one's docs deliverable, or
  treat it as a follow-up that documents the shape this plan ships.
- **Coordinates with** [`dslcontext-on-condition-tablemethod.md`](dslcontext-on-condition-tablemethod.md):
  both touch `ArgCallEmitter`'s param-walking. Sequencing matters; this
  plan should land after that one or share its `params()` walk fix.
- **Independent of** [`set-parent-keys-on-service.md`](set-parent-keys-on-service.md),
  [`checked-exceptions-typed-errors.md`](checked-exceptions-typed-errors.md):
  no shared file edits, but error-channel design overlaps with the
  null-filtering question above.
- **New sibling Backlog item:** `auto-nodes-relay-resolver.md` (auto-emit
  Relay `nodes` when `node` exists; not part of this plan because it isn't
  a `@service` extension).
