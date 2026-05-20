---
id: R190
title: Single-tenant schema-driven ExecutionInput factory and sealed GraphitronContext
status: Spec
bucket: architecture
priority: 5
theme: service
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Single-tenant schema-driven ExecutionInput factory and sealed GraphitronContext

R45 (`tenant-routing-and-execution-input.md`) bundles the design every consumer needs — a schema-driven `newExecutionInput` factory whose parameter list reflects the schema's `contextArguments` with reflected Java types, a sealed generated `GraphitronContext` consumers cannot implement, and typed `getContextArgument(env, name, ExpectedType.class)` diagnostics that name the consumer-side fix and the schema-side site — together with the multi-tenant additions (tenant-column classification, `byTenant` factory overload, per-loader name partitioning, `@tenantId` ARGUMENT_DEFINITION directive) that only consumers with multiple tenants ever exercise. The single-tenant slice is independently shippable, unblocks the majority of consumers (who today populate `GraphQLContext` by hand and discover misconfiguration at first fetch), and gives R45 a landed baseline to layer multi-tenant fan-out on top of instead of a single monolithic spec where the simpler half is held up by the harder half.

This item covers the single-tenant design end-to-end; R45 is rescoped to "given the single-tenant factory and sealed context, add tenant column classification + `byTenant` routing + `@tenantId` arg" once this lands.

## Design

### Schema-driven factory shape

`GraphitronFacadeGenerator` today emits two `newExecutionInput` overloads at `GraphitronFacadeGenerator.java:54-72`: `newExecutionInput(GraphitronContext)` taking the full context, and `newExecutionInput(DSLContext)` lambda-adapting `(GraphitronContext) env -> dsl`. R190 collapses these into a single typed overload whose parameter list reflects the schema's declared `contextArguments`.

The codegen walks the SDL, collects every `contextArgument` name referenced by `@service` / `@tableMethod` / `@condition` directives (current call sites: `ServiceDirectiveResolver.java:133`, `TableMethodDirectiveResolver.java:144`, `BuildContext.java:1508`), looks up each name's reflected Java type, and alphabetical-sorts by name for stable parameter order. To get a structured `TypeName` without re-parsing a generic-type string, the catalog lifts `TypeName javaType` onto `MethodRef.Param.Typed` (`MethodRef.java:238`) alongside the existing `String typeName`: `ServiceCatalog.reflectTableMethod` / `reflectServiceMethod` already hold the raw `java.lang.reflect.Type` at the capture site (`ServiceCatalog.java:250`), so it captures `TypeName.get(parameterizedType)` in the same step. This mirrors the precedent set by `MethodRef.returnType` (`MethodRef.java:17-22`), whose javadoc explicitly notes that storing the structured `TypeName` avoids the string→`TypeName` round-trip the classifier would otherwise have to invent.

For a schema using `@service(contextArguments: ["userInfo", "fnr"])` where `fnr: String` and `userInfo: UserInfo`:

```java
public static ExecutionInput.Builder newExecutionInput(
    DSLContext defaultDsl,
    String fnr,
    UserInfo userInfo);
```

`DSLContext defaultDsl` is always first; contextArguments follow alphabetically. The body runs `Objects.requireNonNull(<name>, "<name>")` on `defaultDsl` and on every contextArgument parameter, then populates `graphQLContext` directly — graphql-java's `GraphQLContext` is the carrier — putting `defaultDsl` under key `DSLContext.class`, each contextArgument value under its string name, and the stateless singleton `GraphitronContextImpl` under `GraphitronContext.class` (keeping today's `b.put(GraphitronContext.class, context)` convention at `GraphitronFacadeGenerator.java:60-62` and the downstream `graphitronContext(env)` helper shape). The per-slot `requireNonNull` closes the gap that the factory's typed parameter list does not by itself prevent: Java permits `null` for a reference-typed parameter, and a silent `null`-put would later misdiagnose at `getContextArgument` as "was not supplied" or surface inside jOOQ on the first SQL call. `requireNonNull` surfaces the failure as a tight `NullPointerException` at the actual call site, for both `defaultDsl` and each contextArgument slot. Schemas with zero contextArguments collapse to `newExecutionInput(DSLContext defaultDsl)`, with the `defaultDsl` null-check still emitted.

### Sealed `GraphitronContext`

`GraphitronContextInterfaceGenerator.java:35-117` today emits `public interface GraphitronContext` with four default-or-abstract methods. R190 makes the generated interface `sealed permits GraphitronContextImpl` and adds the impl as a stateless generated singleton in the same package. The impl carries no per-request fields; all per-request values live in the `GraphQLContext` populated by the factory, and every impl method reads from `env.getGraphQlContext()`. Consumers can no longer construct ad-hoc `GraphitronContext` lambdas or anonymous subclasses; the factory is the only path that populates the request.

The method set in single-tenant mode:

- `DSLContext getDslContext(DataFetchingEnvironment env)` — default; reads `env.getGraphQlContext().get(DSLContext.class)`.
- `<T> T getContextArgument(DataFetchingEnvironment env, String name, Class<T> expectedType)` — default; reads `env.getGraphQlContext().get(name)` and applies `expectedType.cast(...)` (see "Typed `getContextArgument` boundary" below).
- `Validator getValidator(DataFetchingEnvironment env)` — default; impl returns `Validation.buildDefaultValidatorFactory().getValidator()` (unchanged behaviour; R192 layers a Mojo-driven override on top). No current consumer overrides this method, so sealing the interface removes no live capability; R192 ships as a strict addition rather than a migration of an existing extension point.

`getTenantId(env)` is **not declared** in single-tenant mode — its current default returning `""` is removed. R45 reintroduces it on top of the sealed surface, which is method-set widening (additive) rather than a signature change, so the absence-to-presence transition is non-breaking for code that compiled against R190's shape.

### Typed `getContextArgument` boundary

`ArgCallEmitter.java:191` and `:285` today emit an untyped two-arg call `<ctx>.getContextArgument(env, "<name>")`, leaving the cast to Java's `<T>` type inference at the consumer-side method-parameter slot. R190 grows the signature with a third `Class<T> expectedType` parameter so the default body can apply a typed runtime check:

```java
<ctx>.getContextArgument(env, "fnr", String.class)
```

The default body reads `env.getGraphQlContext().get(name)` and runs `expectedType.cast(...)` on the result. Both failure paths below are reachable only when the consumer bypasses the factory (hand-rolls an `ExecutionInput.Builder` and omits the stash); routing through `Graphitron.newExecutionInput(...)` makes a missing or wrong-typed contextArgument a compile error, because the factory's typed parameter slot IS the reflected expected type. The runtime messages therefore stay deliberately terse — the compile-time signal on the factory is the load-bearing diagnostic, not the throw:

- **Missing value** (`value == null`): `IllegalStateException("context value 'fnr' was not supplied; call Graphitron.newExecutionInput(...) to populate it")`. No per-call-site coordinate; the factory's typed signature is what readers reach for.
- **Type mismatch**: `expectedType.cast(value)` throws the JDK's default `ClassCastException`; no custom wrapping.

### Cross-site contextArgument type-agreement (load-bearing classifier)

The factory emitter pastes one `TypeName` per contextArgument name into the generated parameter list. If two directive sites reference the same name with mutually-incompatible Java types (`@service(contextArguments: ["fnr"])` on a `(String fnr)` method and `@condition(contextArguments: ["fnr"])` on a `(Long fnr)` method), the emitter cannot produce a single factory parameter type. A new classifier check runs after `ServiceCatalog.reflectTableMethod` and `reflectServiceMethod` have populated `MethodRef.Param.Typed.javaType` (the new lifted-`TypeName` field) across the catalog:

- Collect `Map<String, List<(MethodRef, TypeName javaType)>>` keyed by contextArgument name across all directive sites.
- For each name with more than one distinct `javaType` (`TypeName.equals` is structural), reject. Disagreement on the declared Java type for a single contextArgument name is always an error, even when one type is assignable to another (`Number` and `Long` reject; `String` and `String` accept). No "more-specific type wins" fallback: a `@condition` site that declared `(Number fnr)` and a `@service` site that declared `(Long fnr)` would force the factory emitter to pick a winner at the boundary, silently widening or narrowing what the author wrote. The classifier therefore demands exact structural equality across sites and stores the single agreed `TypeName` on `ResolvedContextArg.javaType`; both emitters read that field directly and never re-derive a winner.
- On reject, produce `Rejection.contextArgumentTypeConflict(name, sites)`, a new factory on the `Rejection` sealed interface (`Rejection.java:18`) returning a new `AuthorError.TypeConflict` arm. The arm carries the contextArgument name and the list of conflicting sites as typed structured data (not prose), so the validator-side surface, the IDE quick-fix surface a future LSP cycle wants to read from, and the renderer that produces the user-facing error message all draw from the same typed record rather than re-parsing a string.

The classifier method is annotated `@LoadBearingClassifierCheck(key = "context-argument.type-agreement", description = "Cross-site agreement on contextArgument Java types; consumed by the factory emitter and the getContextArgument call-site emitter")`. Both the factory-emitting method in `GraphitronFacadeGenerator` and the call-site-emitting code in `ArgCallEmitter` (the `$T.class` literal at the third arg slot) are annotated `@DependsOnClassifierCheck(key = "context-argument.type-agreement", reliesOn = "single TypeName per name, read from ResolvedContextArg.javaType")`. The two emitters MUST read the same `ResolvedContextArg.javaType` field — that single source of truth is what prevents the factory's typed `put` and the call-site's typed `cast` from drifting; if either emitter reconstructs the type from `MethodRef.Param.Typed.typeName` independently, the load-bearing guarantee collapses. `LoadBearingGuaranteeAuditTest` (`LoadBearingGuaranteeAuditTest.java:57-67`) enforces that the producer/consumer pairing is wired.

### DataLoader name emission

Five sites today emit `String name = <ctx>.getTenantId(env) + "/" + String.join("/", env.getExecutionStepInfo().getPath().getKeysOnly())`:

- `DataLoaderFetcherEmitter.java:135`
- `TypeFetcherGenerator.java:4553`
- `MultiTablePolymorphicEmitter.java:810`
- `MultiTablePolymorphicEmitter.java:876`
- `QueryNodeFetcherClassGenerator.java:161` (a slight variant inside a `.map(id -> {...})` lambda, using a pre-computed `path` local instead of `getKeysOnly()`)

Today's default `getTenantId(env)` returns `""`, so the de-facto runtime name is `"/" + path` — a leading-slash artifact. R190 drops the `<ctx>.getTenantId(env) + "/"` segment at all five sites; the emitted name becomes the path expression alone. DataLoader names lose the leading slash. R45 reintroduces the prefix at all five sites when `<tenantColumn>` is configured, restoring the tenant-partitioned shape.

### Out of scope

- Tenant column Mojo config (`<tenantColumn>`), tenant-scope classification, `byTenant` factory overload, DataLoader name partitioning by tenant, `@tenantId` ARGUMENT_DEFINITION directive — all R45 after rescope on top of R190.
- Custom validator factory (`<validatorFactory>` Mojo element) — R192.

## Implementation

### Catalog / classification (`graphitron/`)

- Lift `TypeName javaType` onto `MethodRef.Param.Typed` (`MethodRef.java:238`), captured at the same point `ServiceCatalog.java:250` already captures the `String typeName`. The `TypeName` is built via `TypeName.get(parameterizedType)` rather than parsed back from the rendered string; the precedent is `MethodRef.returnType` (`MethodRef.java:17-22`), whose javadoc spells out why the structured form is stored directly.
- New classifier step (location: `BuildContext`-adjacent, after the existing `ServiceCatalog.reflectTableMethod` / `reflectServiceMethod` populate the `MethodRef.Param.Typed` set; before generator emission). Produces `Map<String, ResolvedContextArg>` where `ResolvedContextArg(String name, TypeName javaType, List<MethodRef> sites)`, reading `javaType` straight off `Param.Typed.javaType` after the cross-site agreement check has confirmed exactly one distinct `TypeName` per name. Annotated `@LoadBearingClassifierCheck(key = "context-argument.type-agreement", description = "...")`. The factory emitter and the call-site emitter both read `ResolvedContextArg.javaType` directly — neither re-derives a `TypeName` from `MethodRef.Param.Typed.typeName` or from raw reflection. This single-read pattern is the load-bearing guarantee; the audit's job is to enforce that both consumers are wired against the same producer field. `javaType` is raw `TypeName` (JavaPoet AST) rather than a sealed sub-taxonomy because the only consumers are emitters that paste it verbatim — into the factory parameter list and the call-site cast literal — and neither forks on its shape. If a later slice (R45's tenant-column work) needs to fork on the type, lift the sub-taxonomy at that point.
- New record `ConflictSite(MethodRef site, TypeName declared)` (carrier for per-site coordinates). Captured at the classifier; consumed by the renderer and by any future LSP fix-it that wants to navigate to a declaring method. Not `Map.Entry<MethodRef, String>`: the two reading consumers should not have to learn what "key" and "value" mean for this pair, and `TypeName` is the same JavaPoet AST type the rest of the slice carries.
- New arm `AuthorError.TypeConflict(String contextArgumentName, List<ConflictSite> sites)` on the `AuthorError` sealed sub-hierarchy. Carrying the sites as typed structured data (not as an `AuthorError.Structural.reason` prose string) keeps the typed-rejection principle intact end to end: the renderer turns the typed sites into the human-facing `ValidationError` message, but downstream consumers (LSP fix-its, validators, hypothetical machine-readable rejection feeds) read the typed record. The existing `AuthorError.Structural` is reserved for genuinely formless author errors; multi-site type conflicts are structured. The rendering contract lives on the record itself as a `message()` override following the `AuthorError.RecordBindingMultiProducer.message()` precedent: a header sentence naming the contextArgument, then one indented line per `ConflictSite` rendering `<MethodRef coordinate> declared <TypeName.toString()>`. This is the boundary between typed record and rendered prose; the L4 snapshot asserts that shape, future LSP fix-its read the typed `sites` field directly and ignore `message()`.
- New factory `Rejection.contextArgumentTypeConflict(String name, List<ConflictSite> sites)` on the `Rejection` sealed interface (`Rejection.java`); produces an `AuthorError.TypeConflict`.
- The classified map is stored on the build result and read by `GraphitronFacadeGenerator` and `GraphitronContextInterfaceGenerator`. Rejections from the new classifier check land on the same build-result surface; `GraphitronSchemaValidator.validateSchema` (`GraphitronSchemaValidator.java:36-41`) gains a new cross-cutting check `validateContextArgumentTypeAgreement(schema, errors)` that drains the rejection list into `ValidationError`s, mirroring `validateLocalContextErrorsFieldGuards(schema, errors)` at line 40. This closes the validator-mirrors-classifier loop: the same `Rejection.contextArgumentTypeConflict` the classifier produces surfaces as a build-time error before the factory emitter is ever asked to paste a non-existent `TypeName`.

### Schema-driven factory (`GraphitronFacadeGenerator.java:54-72`)

- Replace the two-overload emission with a single overload: parameter list is `DSLContext defaultDsl` followed by the alphabetical-sorted `(JavaPoet TypeName, name)` pairs read from `ResolvedContextArg.javaType` (same field `ArgCallEmitter` reads — see "Cross-site contextArgument type-agreement" for why this single source matters).
- Factory body: emit `Objects.requireNonNull(defaultDsl, "defaultDsl")` followed by one `Objects.requireNonNull(<name>, "<name>")` per contextArgument parameter, then a `graphQLContext` builder lambda that puts `defaultDsl` under `DSLContext.class`, each contextArgument value under its string name, and the singleton `GraphitronContextImpl` under `GraphitronContext.class`. String-keyed entries mean `env.getGraphQlContext().get(name)` is the canonical read path; the impl needs no per-request fields.
- For the diagnostic literal in `getContextArgument`'s missing-value message, emit `Graphitron.newExecutionInput(...)` via a `$T` reference to the facade `ClassName` rather than as a hand-typed string, so the literal moves with the class if facade naming ever changes.
- Annotated `@DependsOnClassifierCheck(key = "context-argument.type-agreement", reliesOn = "...")`.

### Sealed `GraphitronContext` (`GraphitronContextInterfaceGenerator.java:35-117`)

- Add `sealed permits GraphitronContextImpl` to the interface declaration.
- Drop the `getTenantId(env)` default method.
- Demote `getDslContext` from `abstract` to `default`; body reads `env.getGraphQlContext().get(DSLContext.class)`.
- Grow `getContextArgument` to `<T> T getContextArgument(DataFetchingEnvironment env, String name, Class<T> expectedType)`. Default body reads `env.getGraphQlContext().get(name)` and applies `expectedType.cast(...)`; `null` becomes the documented `IllegalStateException`, wrong-type falls through to the JDK's `ClassCastException`.
- Emit a generated stateless singleton `GraphitronContextImpl` in the same package — shape choice (enum singleton, no-component record, or final class with private constructor) deferred to implementation, since all per-request state lives in `GraphQLContext` and the impl carries no fields. Singleton closes off subclassing; sealed-interface closes off alternate implementations.

### `getContextArgument` typed call sites (`ArgCallEmitter.java:191, :285`)

- Emit the third arg: the expected-type literal. Read the `TypeName` from `ResolvedContextArg.javaType` on the classified map (not from `MethodRef.Param.Typed.typeName` directly — see the load-bearing-classifier note above); emit `$T.class` with the raw type (parameterised types collapse to their erasure for the runtime cast check, which is sufficient — the static-side type is already pinned by the consumer-side method parameter).
- Change `CodeBlock.of("$L.getContextArgument(env, $S)", ctx.graphitronContextCall(), param.name())` to `CodeBlock.of("$L.getContextArgument(env, $S, $T.class)", ctx.graphitronContextCall(), param.name(), rawType)`.
- Annotated `@DependsOnClassifierCheck(key = "context-argument.type-agreement", reliesOn = "...")`.

### DataLoader name emission (five sites)

Each of the five sites listed under "DataLoader name emission" above drops the `<ctx>.getTenantId(env) + "/"` segment. Helper extraction (a shared `DataLoaderNames.untenanted(env)` utility) is optional and can be deferred; the five sites share the same shape and R45 will need to revisit them anyway when it reintroduces the prefix.

### Runtime contract (`graphitron-rewrite-runtime/`)

No new runtime surface for R190. The `getContextArgument` signature change is in the generated interface, not the runtime artifact. R192 introduces the runtime functional interface for the validator factory.

### User documentation

Twelve pages update in the same commit window as the generator change (plus one verified-no-change). The drafts and revision instructions live under "User documentation (first-client check)" below; the two primary rewrites (`graphitron-rewrite/docs/getting-started.adoc`, `graphitron-rewrite/docs/runtime-extension-points.adoc`) move their drafted prose into place, the other ten pages take the per-page revisions listed.

## Tests

- **Pipeline (L4).** `GraphitronFacadeGeneratorPipelineTest` (or the closest existing test for that generator) gains a case: SDL with multiple `@service(contextArguments: [...])` sites producing the alphabetical-sorted factory parameter list. Snapshot the emitted factory `TypeSpec` (including the `graphQLContext` builder lambda) and the sealed `GraphitronContext` interface + `GraphitronContextImpl` singleton. Loader-name snapshot for one of the five DataLoader sites pins the un-prefixed shape.
- **Classification (L2).** `ContextArgumentTypeAgreementTest` (new): two SDL fixtures, one accepted (single typeName per name across `@service` + `@tableMethod` + `@condition`), one rejected. The rejected fixture forces a three-site conflict (`@service` declares `(Long fnr)`, `@condition` declares `(Number fnr)`, `@tableMethod` declares `(String fnr)`) and asserts the rejection's typed structure: `name == "fnr"`, `sites.size() == 3`, each `ConflictSite` carrying the expected `MethodRef` and `TypeName declared`. A second accepted-but-assignability-shaped case (`(Number fnr)` and `(Long fnr)`) asserts that "assignable but not equal" is treated as a conflict, pinning the always-error semantics.
- **Validator (L4).** `GraphitronSchemaValidatorPipelineTest` gains a case: the new `validateContextArgumentTypeAgreement` check, fed a `Rejection.contextArgumentTypeConflict` with three sites declaring three distinct types, produces one `ValidationError` whose rendered message names every site (each by its `MethodRef` coordinate, no truncation). The snapshot pins the renderer's behaviour against the typed `ConflictSite` list; a regression that drops or de-duplicates sites in the prose surface is caught here, separate from the L2 classifier assertion on the typed `sites` field.
- **Audit (L2).** `LoadBearingGuaranteeAuditTest` is already wired; the new key `context-argument.type-agreement` lands with one producer (the classifier) and two consumers (the factory emitter and the `ArgCallEmitter` call-site emitter) and the test stays green by construction. If the audit fails after the slice, the producer/consumer annotations are mis-keyed.
- **Compile (L5).** `graphitron-sakila-example` gains an SDL fragment with at least one `@service(contextArguments: ["userId"])` site so the new factory shape appears in the compile fixture; generated code passes `mvn compile -pl :graphitron-sakila-example -Plocal-db`.
- **Execute (L6).** `graphitron-sakila-example`'s test suite calls `Graphitron.newExecutionInput(dsl, userId)` and verifies the contextArgument round-trips through `getContextArgument` to the user method. A second test exercises the missing-value diagnostic by hand-rolling an `ExecutionInput.Builder` that omits the stash, and asserts the message text matches the documented form (substring match on `"call Graphitron.newExecutionInput(...)"`).

## User documentation (first-client check)

R190 changes the primary onboarding surface (`Graphitron.newExecutionInput`) and the runtime extension model (`GraphitronContext` becomes sealed, `getTenantId` disappears from the single-tenant shape). Per `docs/workflow.adoc` §"Plans with a user-visible surface", the docs draft is the first client of the design; if it doesn't read simply, the design is wrong. Tracing every `GraphitronContext` / `getTenantId` / `implements GraphitronContext` / `new GraphitronContext() {...}` reference through the manual, R190 touches **twelve pages plus one verified-no-change**. The two primary onboarding rewrites carry drafted replacement prose below; the other ten describe the actual current content of each page and the concrete shape of the revision. This is not a search-and-replace: each page's framing differs, several touch user-facing code blocks whose shape changes, and three pages get a deferral banner because their content is fully rescoped to R45's tenant-column work.

### Primary rewrite: `graphitron-rewrite/docs/getting-started.adoc` § Hello world

The current page (lines 14-72) frames onboarding around `implements GraphitronContext`. Under R190 there is no consumer impl: the factory IS the wiring. Replacement draft:

[quote]
____
== Hello world

Build the schema and engine once per app, then create one `ExecutionInput` per request via the schema-driven factory:

[source,java]
----
import com.example.app.Graphitron;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import org.jooq.DSLContext;

GraphQLSchema schema = Graphitron.buildSchema(b -> {});
GraphQL engine = GraphQL.newGraphQL(schema).build();

ExecutionInput input = Graphitron.newExecutionInput(dsl)
    .query(query)
    .build();

var result = engine.execute(input);
----

`Graphitron.newExecutionInput(...)` is the one-call factory. Its parameter list is _schema-driven_: a `DSLContext` first, then one parameter per `contextArgument` named in your `@service` / `@tableMethod` / `@condition` directives, in alphabetical order, with the reflected Java type. A schema declaring `@service(contextArguments: ["fnr", "userInfo"])` against a method `(String fnr, UserInfo userInfo)` emits:

[source,java]
----
public static ExecutionInput.Builder newExecutionInput(
    DSLContext defaultDsl,
    String fnr,
    UserInfo userInfo);
----

A missing or wrong-typed contextArgument is a compile error at the call site, not a runtime surprise: the factory's typed parameter list is the load-bearing diagnostic. The body null-checks each parameter (`Objects.requireNonNull`) and populates the per-request `GraphQLContext`; generated fetchers read each value back through `getContextArgument(env, name, ExpectedType.class)`.

If you bypass the factory and hand-roll an `ExecutionInput.Builder` (for example, to construct a test fixture without `Graphitron`), and a contextArgument slot ends up missing, the generated fetcher throws at first read:

[source,text]
----
java.lang.IllegalStateException: context value 'fnr' was not supplied;
    call Graphitron.newExecutionInput(...) to populate it
----

The message names the contextArgument and the factory that supplies it. Going through `Graphitron.newExecutionInput(...)` makes the same mistake a compile error at the call site instead.

For a complete app and the recommended test setup, see the
https://github.com/sikt-no/graphitron/tree/claude/graphitron-rewrite/graphitron-rewrite/graphitron-sakila-example[`graphitron-sakila-example`]
module: a Quarkus + JAX-RS shell over a generated schema, plus in-process query-to-database tests with match-style and approval-style worked examples.
____

The page's two follow-on subsections (`Tenant-scoped DSLContext`, `Context arguments from a JWT claim`) collapse into the new shape: per-tenant routing belongs to R45, and JWT-claim contextArguments are now factory parameters the consumer populates at request entry. The "tenant-scoped" subsection becomes a one-line forward pointer to R45's how-to; the "JWT-claim contextArgument" subsection becomes a one-paragraph note showing the JWT-claim value passed as the factory's typed parameter.

### Primary rewrite: `graphitron-rewrite/docs/runtime-extension-points.adoc` § GraphitronContext

The current chapter (lines 15-182) frames `GraphitronContext` as _the_ runtime extension point that consumers implement. Under R190 the interface is sealed; the extension model moves to the factory's typed parameter list and (in follow-ups) Mojo configuration. Replacement framing:

[quote]
____
== GraphitronContext

`GraphitronContext` is the sealed per-request contract every generated DataFetcher reads from. It is _emitted_ per app under `<outputPackage>.schema` and sealed to permit only the generator's own `GraphitronContextImpl` singleton; apps no longer implement it directly. The extension surface moves to the points where per-request values cross the boundary:

* *Per-request `DSLContext`*: pass it as the first parameter of `Graphitron.newExecutionInput(dsl, ...)`. Per-tenant routing is the subject of R45's tenant-column work; until that lands, single-tenant apps pass a single `DSLContext` per request.
* *Per-request `contextArgument` values*: pass each one as a typed parameter to `Graphitron.newExecutionInput(...)`. The factory's parameter list reflects the schema's declared `contextArguments` and their reflected Java types, so the consumer's request-entry code threads each value through a typed slot rather than stashing arbitrary entries on `GraphQLContext` by hand.
* *Custom validator factory*: covered by a follow-up Mojo configuration item (R192). The default reads `Validation.buildDefaultValidatorFactory().getValidator()`; this is unchanged by R190 and the single existing override point is currently unused, so no migration is required.

[source,java]
----
// GENERATED (illustrative shape; full method set evolves with the schema)
public sealed interface GraphitronContext permits GraphitronContextImpl {
    default DSLContext getDslContext(DataFetchingEnvironment env) { ... }
    default <T> T getContextArgument(DataFetchingEnvironment env, String name, Class<T> expectedType) { ... }
    default Validator getValidator(DataFetchingEnvironment env) { ... }
}
----

`getContextArgument` takes the expected Java type as its third parameter; the default body reads the value from `env.getGraphQlContext()` and applies `expectedType.cast(...)`. A missing entry throws `IllegalStateException` naming the contextArgument; a wrong-typed entry throws the JDK's default `ClassCastException`. Both paths are only reachable when a consumer hand-rolls an `ExecutionInput.Builder` outside `Graphitron.newExecutionInput(...)`; the typed factory makes the same mistake a compile error at the call site.
____

The chapter's `=== Registration`, `=== getDslContext`, `=== getContextArgument`, and `=== getTenantId` subsections (lines 56-182) reshape: `Registration` becomes a forward pointer to `getting-started.adoc`; the three per-method subsections collapse into a single "Where each per-request value comes from" subsection that maps each value to its factory parameter slot. `=== getTenantId` deletes entirely (R45 reintroduces tenant routing on top of the sealed surface). The diagram at `[#diagram-d4]` (lines 84-107) keeps the same call shape; the only change is that the per-app consumer impl box becomes the generated singleton.

The `== Complementary Technologies` section (lines 186-298) stays as-is in shape; the "per-request decisions" bullet (lines 198-201) rephrases from "your `getDslContext` implementation" to "the `DSLContext` you pass into `Graphitron.newExecutionInput`".

### Substantive rewrite: `docs/manual/reference/runtime-api.adoc` § `GraphitronContext` interface (lines 54-136)

The page's `== GraphitronContext interface` chapter runs from line 54 (the `==` heading) through line 136 (the end of `=== getValidator`). Today it frames the interface as "the per-request extension point apps implement, register via typed key"; the four method subsections (`getDslContext`, `getContextArgument`, `getTenantId`, `getValidator`) each show a consumer override snippet. Under R190 the framing reverses: consumers no longer implement, the factory IS the wiring, and `getTenantId` disappears entirely. Concrete edits:

- Chapter intro (line 56): replace "The per-request extension point that brokers runtime values into generated fetchers:" with "The sealed per-request contract every generated DataFetcher reads from. Apps no longer implement it; the only impl is the generator's own `GraphitronContextImpl` singleton, populated by `Graphitron.newExecutionInput(...)`."
- Interface code block (lines 58-74): replace with the sealed shape:
+
[source,java]
----
public sealed interface GraphitronContext permits GraphitronContextImpl {
    default DSLContext getDslContext(DataFetchingEnvironment env) { ... }
    default <T> T getContextArgument(DataFetchingEnvironment env, String name, Class<T> expectedType) { ... }
    default Validator getValidator(DataFetchingEnvironment env) { ... }
}
----
- Registration code block + surrounding paragraph (lines 76-86): replace `ExecutionInput.newExecutionInput()...graphQLContext(b -> b.put(GraphitronContext.class, impl))...build()` with `Graphitron.newExecutionInput(dsl, ...)... .query(query).build()`. Rewrite the surrounding paragraph to: "The factory IS the registration point; the parameter list is schema-driven (`DSLContext` first, then one parameter per `contextArgument` named in `@service` / `@tableMethod` / `@condition` directives, alphabetically). Generated fetchers retrieve the impl through a private helper emitted once per `*Fetchers` class (`graphitronContext(env)`); generated helpers that issue SQL on behalf of those fetchers emit the same shim. The sealed-interface + singleton-impl pair means `GraphitronContext` is global to the build, not per-request; all per-request state lives in the `GraphQLContext` the factory populates."
- `=== getDslContext` (lines 88-94): rewrite the method's role. It is no longer the "seam for connection scope, transaction boundaries, session variables, per-tenant `Configuration` objects". The seam moves to the `defaultDsl` parameter on the factory; the consumer threads its per-request `DSLContext` through that parameter. Draft body: "Reads the per-request `DSLContext` from `env.getGraphQlContext().get(DSLContext.class)`, populated by the factory's `defaultDsl` parameter. The default impl is the only impl; this is not an override point. To return a tenant-specific `DSLContext` per request, build the right `DSLContext` at request entry and pass it to `Graphitron.newExecutionInput(dsl, ...)`." The tenant-scoping xref at the end becomes "(reintroduced under R45's tenant-column work)".
- `=== getContextArgument` (lines 96-108): replace the worked override snippet with a description of the typed default. Draft: "Grows a third `Class<T> expectedType` parameter so the default body can apply a typed runtime check. The default reads the value from `env.getGraphQlContext()` (under the contextArgument's string name) and runs `expectedType.cast(...)`. A missing entry throws `IllegalStateException` naming the contextArgument and pointing the reader at `Graphitron.newExecutionInput(...)`; a wrong-typed entry throws the JDK's default `ClassCastException`. Both throw paths are only reachable when a consumer hand-rolls an `ExecutionInput.Builder` outside the factory; going through `Graphitron.newExecutionInput(...)` makes the same mistake a compile error at the call site, because the factory's typed parameter slot IS the reflected expected type." The cross-link to `condition-cascade.adoc` stays as-is.
- `=== getTenantId` (lines 110-123): delete the entire subsection. R45 reintroduces tenant routing on top of the sealed surface.
- `=== getValidator` (lines 125-136): keep the body unchanged. Add a one-line note that the method is no longer an override point on the sealed interface; custom-validator-factory configuration ships under R192 as a Mojo-driven hook.
- "See also" cross-link at line 180 (`How-to: Tenant scoping covers `getDslContext` + `getTenantId` cooperation, the cache-key contract, and PostgreSQL row-level security`): rephrase to "covers per-request `DSLContext` routing (recipe is being rewritten under R45; the link resolves but the body shows a deprecation banner)".

### Substantive rewrite: `docs/manual/how-to/test-your-schema.adoc`

The execution-tier test recipe today wires `GraphitronContext` as an anonymous inner class at three test-helper sites (lines 76-79, 199-203, 247-256), registering via `.graphQLContext(b -> b.put(GraphitronContext.class, context))` on a hand-rolled `ExecutionInput.newExecutionInput()`. Under R190 the anonymous-impl pattern is a compile error against a sealed interface; the registration shape changes; and the per-tenant test (lines 247-260) uses a `getTenantId` override that no longer exists. Concrete edits:

- Chapter intro line 11 (`== The setup: Postgres + GraphitronContext + GraphQL`) → `== The setup: Postgres + DSLContext + GraphQL`.
- Intro bullet line 16 ("A `GraphitronContext` per request, wrapping that `DSLContext` and any context arguments your `@service` and `@condition` methods need.") → "A call to `Graphitron.newExecutionInput(dsl, ...)` per request, threading the `DSLContext` and any `@service` / `@condition` contextArguments through the typed parameter list."
- First test-helper (lines 75-85): replace `var context = new GraphitronContext() {...}; var input = ExecutionInput.newExecutionInput()... .graphQLContext(b -> b.put(GraphitronContext.class, context)) ...build()` with `var input = Graphitron.newExecutionInput(dsl).query(query).build()`. The local `var context` deletes; the helper holds a `DSLContext` directly.
- Per-tenant aside line 95: rephrase from "wires the same `dsl` for every test, but production tests that exercise per-tenant routing build a new context per request whose `getDslContext` returns the tenant-specific `DSLContext`" to "wires the same `dsl` for every test, but production tests that exercise per-tenant routing build a per-tenant `DSLContext` at request entry and pass it to `Graphitron.newExecutionInput(perTenantDsl, ...)`. The per-tenant routing recipe is being rewritten under R45 alongside the tenant-column work."
- Second test-helper (lines 197-209): same shape change as the first.
- ContextArgument test-helper (lines 230-241): rewrite to take typed values: change `executeWithContext(String query, GraphitronContext context)` to `executeWithContext(String query, String userId)` (or whatever the recipe's example contextArgument is) and replace the `.graphQLContext(b -> b.put(GraphitronContext.class, context))` body with `Graphitron.newExecutionInput(dsl, userId).query(query).build()`. The `GraphitronContext context` parameter drops; the value is a typed factory argument now.
- Per-tenant context test (lines 245-260): replace the `new GraphitronContext() { ... getTenantId ... }` block with a per-tenant `DSLContext` selection at request entry. Note that the multi-tenant assertion (the `nodes_perTenantPartition_separateBatchPerTenant` shape) is being rewritten under R45 alongside the tenant-column work; until then, this section either drops or carries the R45 deferral banner.

### Tenant-routing pages: rescope under R45

Three pages frame content around `getTenantId`-override mechanics that don't survive R190. Leaving the pre-R190 body in place behind a banner sits oddly against the first-client check — a consumer arriving from search would read a recipe that doesn't compile against the post-R190 generated `GraphitronContext`. R190's choice is to replace the affected bodies with one-line forward pointers; R45 reintroduces the real content on top of the sealed surface. Per-page treatment:

- `docs/manual/how-to/tenant-scoping.adoc` — replace the entire body (everything below the title line) with a one-page stub:
+
[quote]
____
== Tenant scoping

The per-tenant `DSLContext` routing recipe is being rewritten under R45 alongside the tenant-column work. Until R45 ships, single-tenant apps pass a single `DSLContext` to `Graphitron.newExecutionInput(dsl, ...)`; multi-tenant routing through the generated `byTenant` factory overload, the `@tenantId` argument directive, and per-tenant DataLoader partitioning all land together under R45.

For the single-tenant request-entry shape, see xref:../getting-started.adoc[Getting started] and xref:../reference/runtime-api.adoc[Runtime API reference].
____
- `docs/manual/how-to/apollo-federation.adoc` lines 86-91 — delete the per-rep `getTenantId` subsection outright. The rest of the federation recipe (transport, key declaration, entity fetcher swapping) is unaffected by R190; the deleted subsection is reintroduced under R45 in a shape that matches the post-R190 sealed surface.
- `docs/manual/how-to/split-vs-inline.adoc` lines 44-58 — delete the "Per-tenant key prefix" bullet entirely. The registry is request-scoped, the cross-tenant collision scenario it warned about doesn't arise in practice (already labelled "rare but possible" at line 56), and under R190 the per-tenant prefix doesn't exist anyway. R45 may reintroduce the bullet when `<tenantColumn>` is configured and the prefix returns at the five emission sites.

### Index-page updates (one line each)

- `docs/manual/reference/index.adoc:9` — the "Runtime API reference" bullet enumerates the interface methods as `(getDslContext, getContextArgument, getTenantId, getValidator)`. Drop `getTenantId` from the listing.
- `docs/manual/how-to/index.adoc:33` — "Tenant scoping: wire `getDslContext` and `getTenantId` on `GraphitronContext` so each request reaches a tenant-specific `DSLContext`." Rephrase to: "Tenant scoping: per-request routing through `Graphitron.newExecutionInput(dsl, ...)`. The full recipe (per-tenant DataLoader partitioning, `@tenantId` directive, `byTenant` factory overload) ships under R45; until then, single-tenant apps pass a single `DSLContext`."

### Small in-prose touch-ups (one or two sentences each)

- `docs/manual/explanation/how-it-works.adoc:32` — "*Calls into jOOQ.* The fetcher reads the per-request `DSLContext` off the `GraphitronContext`, builds a typed query..." → "*Calls into jOOQ.* The fetcher reads the per-request `DSLContext` off the `GraphQLContext` that `Graphitron.newExecutionInput(dsl, ...)` populated, builds a typed query..." The sentence's structure stays; only the source-of-truth name changes.
- `docs/manual/explanation/batching-model.adoc:45` — "...the xref:../reference/runtime-api.adoc[runtime API] documents the per-request `GraphitronContext` interface; the host application's job is just to provide the `DSLContext` and any context arguments." → "...the xref:../reference/runtime-api.adoc[runtime API] documents the sealed `GraphitronContext` contract; the host application's job is to call `Graphitron.newExecutionInput(dsl, ...)` with the `DSLContext` and any contextArgument values, and the generator wires the per-request `GraphQLContext` from those typed parameters." The tenant-scoping xref later in the paragraph stays (now points at the banner-bearing recipe).
- `docs/manual/tutorial/06-going-further.adoc:23` — "...walks through copying that pattern into your own project: how to wire `GraphitronContext` against Testcontainers..." → "...walks through copying that pattern into your own project: how to wire `Graphitron.newExecutionInput(dsl, ...)` against Testcontainers..."
- `docs/security.adoc:21` — "See xref:architecture/runtime-extension-points.adoc[Runtime Extension Points] for how that wires together with `GraphitronContext`." → "See xref:architecture/runtime-extension-points.adoc[Runtime Extension Points] for how that wires through the per-request `DSLContext` you pass to `Graphitron.newExecutionInput(dsl, ...)`." Optional; the existing phrasing is technically still accurate (sealed `GraphitronContext` is the per-request contract), but the rewrite reads better post-R190.

### Verified-no-change (cross-links remain coherent)

`docs/manual/how-to/condition-cascade.adoc:181` and `docs/manual/how-to/add-custom-conditions.adoc:164` cross-link to `tenant-scoping.adoc` and `runtime-api.adoc` for the supplying-side context-argument story. The cross-links remain coherent once those two targets land; no edits needed on the pages themselves.

### Total scope (twelve pages plus one no-change)

Substantive rewrites (four): `getting-started.adoc`, `runtime-extension-points.adoc`, `runtime-api.adoc`, `test-your-schema.adoc`.
Tenant-routing deferral banners (three): `tenant-scoping.adoc`, `apollo-federation.adoc` (one section only), `split-vs-inline.adoc` (one bullet only).
Index-page updates (two): `reference/index.adoc`, `how-to/index.adoc`.
Small in-prose touch-ups (three): `how-it-works.adoc`, `batching-model.adoc`, `06-going-further.adoc`.
Optional touch-up (one): `security.adoc` (cross-link reads coherently as-is; rewrite is a polish).
Verified-no-change (two): `condition-cascade.adoc`, `add-custom-conditions.adoc`.

The implementer is on hook to land all twelve in the same commit window as the generator change, so the docs site does not ship in a half-state. If a thirteenth page surfaces during implementation, fold it into the same commit window with the same "first-client check" lens: would a consumer arriving at this page from search read code that compiles against the post-R190 generated `GraphitronContext`?

## Open questions

None outstanding for the single-tenant slice.

## Roadmap entries (siblings / dependencies)

- **Splits from** [`tenant-routing-and-execution-input.md`](tenant-routing-and-execution-input.md) (R45). R45 awaits this landing before its Spec rescopes to the multi-tenant additions on top.
- **Reshapes** [`service-multi-tenant-fanout.md`](service-multi-tenant-fanout.md) (R46) transitively via R45: the public `ContextValueRegistration` permit and `GraphitronContext` extension-point assumptions R46 was built on dissolve here.
- **Affects** [`helper-emission-non-fetcher-hosts.md`](helper-emission-non-fetcher-hosts.md) (R85). The host-class `graphitronContext(env)` helper-emission gate stays structurally unchanged because the sealed interface keeps the same method set in single-tenant mode (no `getTenantId` to call). If R45 widens that set, R85 sees the addition cleanly.
- **Coordinates with** [`dslcontext-on-condition-tablemethod.md`](dslcontext-on-condition-tablemethod.md): both touch `ArgCallEmitter`'s param walk; no shared file edits but adjacent emission paths.
- **Spawns** [`custom-validator-factory.md`](custom-validator-factory.md) (R192) as the carved-out validator-override item.
